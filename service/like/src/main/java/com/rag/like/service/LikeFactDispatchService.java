package com.rag.like.service;

import com.rag.common.communityfact.Dispatcher;
import com.rag.common.communityfact.Event;
import com.rag.common.communityfact.EventSender;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.common.communityfact.Wire;
import com.rag.like.domain.entity.LikeDomainFactOutboxEntity;
import com.rag.like.mapper.LikeDomainFactOutboxMapper;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 单条事实投递 —— 对应 Go 端
 * {@code service/like/rpc/internal/model/fact_delivery.go:52-126} 的 {@code DispatchFactOnce}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeFactDispatchService.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.like.like.rpc.service} 压平为 {@code com.rag.like.service}（D7）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.like.like.rpc.domain} / {@code ...rpc.mapper}
 *           改为 {@code com.rag.like.domain.entity} / {@code com.rag.like.mapper}（D7）；</li>
 *       <li>共享层 1:N 拆分 ⇒ {@code Dispatcher.DispatcherImpl} → 顶层 {@link Dispatcher}、
 *           {@code Wire.Sender} → {@link EventSender}、{@code Wire.Event} → {@link Event}、
 *           {@code Wire.TechnicalReceipt} → {@link TechnicalReceipt}；</li>
 *       <li>{@code LikeEventContracts.FACT_PRODUCER} → {@link LikeDeliveryValidator#FACT_PRODUCER}。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。四条出口的方向、「状态落盘先于错误抛出」的顺序、
 *       「发的是信封列原始字节」逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>🔴🔴 为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</h2>
 *
 * <p>Go 的形状是：
 * <pre>
 * tx := m.db.WithContext(ctx).Begin()
 * defer tx.Rollback()
 * ...
 * if 冻结信封非法 { tx.Update(status=3); tx.Commit();  return false, ErrFrozenEnvelopeBlocked }
 * if 发送失败      { tx.Update(status=2, retry+1); tx.Commit(); return false, sendErr }
 *                tx.Update(status=1, 5 个回执列); tx.Commit(); return true, nil
 * </pre>
 *
 * <p><b>三条出口里有两条是「更新状态后<b>提交</b>，然后返回错误」</b>。
 * 若用 {@code @Transactional} + 抛异常：
 * <ul>
 *   <li>{@code rollbackFor} 默认只对 {@code RuntimeException} 回滚 ⇒ 恰好会回滚
 *       ⇒ <b>「标记 blocked」「记录重试次数」这两件事被一起丢掉</b>；</li>
 *   <li>改成不回滚 ⇒ 事务语义失控，将来任何人加一条 {@code return} 都会静默提交半成品。</li>
 * </ul>
 *
 * <p>⇒ 用 {@link TransactionTemplate} 显式控制 {@code commit()} / {@code rollback()}，
 * 与 Go 的 {@code Begin} / {@code defer Rollback} / 三处 {@code Commit} 逐条同构。
 * 这是 Spring 提供的<b>编程式事务</b>，不是绕过框架。
 *
 * <h2>⚠️ 本方法<b>在一个事务里做网络 IO</b>，这是 Go 的刻意选择</h2>
 * <p>Go 的 {@code DispatchFactOnce} 手工 {@code Begin()} → 取件（{@code FOR UPDATE SKIP LOCKED}）
 * → <b>持锁期间</b>调 {@code sender.Send} → 落回执 → {@code Commit()}。
 * ⇒ 「源行在拿到匹配技术回执之前不能被第二个投递进程领走」是<b>正确性要求</b>，不是性能选择。
 * 把 {@code sender.Send} 挪到事务外（先提交再发）会产生：
 * 进程在提交与发送之间崩溃 ⇒ 该行永远停在 pending，而下一个进程可能已经领走了它的事实流后继。
 * 事务内的行锁把这条路径彻底关掉。
 *
 * <p>⚠️ 代价是<b>事务持有时间包含 DC 的往返</b>（Go 侧用 {@code context.WithTimeout(10s)} 兜底，
 * 由共享层 {@code common.Dispatcher} 施加，Java 侧沿用同一条路径）。这是等式的一部分，不做优化。
 *
 * <h2>⚠️ 发出去的是<b>信封列</b>的原始字节</h2>
 * <p>Go {@code fact_delivery.go:92}：{@code sender.Send(ctx, event, json.RawMessage(*row.DeliveryEnvelope))}
 * ⇒ 发的是 {@code delivery_envelope} 列的字节，<b>不是</b>「解码后再序列化」的字节
 * ⇒ DC 算出的 {@code input_hash} 与权威读重算的 {@code input_hash} 才必然相同。
 *
 * <p><b>本方法默认不启用</b>：由 {@code wiring/LikeFactDispatchScheduler} 在
 * {@code like.fact.dispatch-enabled=true} 时才驱动（见该类注释）。
 */
@Service
public class LikeFactDispatchService implements Dispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(LikeFactDispatchService.class);

    private final LikeDomainFactOutboxMapper outboxMapper;
    private final LikeDeliveryValidator validator;
    private final PlatformTransactionManager transactionManager;

    public LikeFactDispatchService(LikeDomainFactOutboxMapper outboxMapper,
                                   LikeDeliveryValidator validator,
                                   PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.validator = validator;
        this.transactionManager = transactionManager;
    }

    /**
     * 对应 Go {@code DispatchFactOnce}（{@code fact_delivery.go:52-126}）：领一条 → 校验 → 投递 → 落回执。
     *
     * <p>本类<b>显式实现</b> {@link Dispatcher}（{@code dispatchFactOnce(ctx, sender, logger)}）
     * ⇒ 由 {@code wiring/LikeFactDispatchScheduler} 用 {@code Dispatcher.run(...)} 驱动
     * （批次 16 / 节奏 1s / 单次 10s deadline —— 三项都在共享层，本类不重复实现）。
     * ⚠️ <b>显式 implements 而不是靠方法引用「碰巧对上」</b>：
     * 方法引用能编译过不代表语义对得上（签名相同、行为不同也可能过）⇒ 写成 implements 后，
     * 签名一旦与共享层的契约漂移，<b>编译期就红</b>。
     *
     * <p><b>四条出口的方向必须保持</b>：
     * <ol>
     *   <li>outbox 空 ⇒ 返回 {@code false}（<b>不抛错</b>），调度器据此结束内层批次；</li>
     *   <li>冻结信封非法 ⇒ 置 <b>BLOCKED</b> 并抛
     *       {@link Wire#ERR_FROZEN_ENVELOPE_BLOCKED}（<b>不是</b>可重试的失败）；</li>
     *   <li>投递失败或回执不匹配 ⇒ 置 <b>FAILED</b> + {@code retry_count + 1} 并抛错（可重试）；</li>
     *   <li>成功 ⇒ 置 <b>ACCEPTED</b> 并写入技术元数据，返回 {@code true}。</li>
     * </ol>
     *
     * <p>🔴 <b>错误在事务提交之后才抛出</b>（Go 的 {@code tx.Commit()} 在 {@code return err} 之前）。
     * 这是「状态落盘」与「向上报错」两件事同时成立的前提。
     *
     * @return {@code true} = 已成功投递一条；{@code false} = outbox 里没有可投递的事实
     */
    @Override
    public boolean dispatchFactOnce(Context ctx, EventSender sender, Logger logger)
            throws Exception {
        long started = System.nanoTime();
        if (sender == null || logger == null) {
            throw new IllegalStateException("like fact dispatcher is not configured");
        }

        // Go: fact_delivery.go:57-61 —— tx := Begin(); defer tx.Rollback()
        // ⚠️ 显式编程式事务：见类注释「为什么用 TransactionTemplate」
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        DispatchResult result = template.execute(status -> {
            // --- ① 取件（FOR UPDATE SKIP LOCKED + 前序屏障，锁持有到本方法返回）
            //     Go: fact_delivery.go:62-69
            LikeDomainFactOutboxEntity row = outboxMapper.claimNextDeliverable();
            if (row == null) {
                // Go: fact_delivery.go:70-72 —— ErrRecordNotFound ⇒ (false, nil)：队列空，不是错误
                status.setRollbackOnly();   // 只读事务，无写入 ⇒ 回滚即可（Go 的 defer Rollback）
                return DispatchResult.of(DispatchOutcome.EMPTY, null);
            }

            // --- ② 校验冻结信封
            //     Go: fact_delivery.go:77-88（StrictDecode 失败或 validLikeDelivery 为假 ⇒ 一律 BLOCKED）
            // 🔴🔴 下面三个写状态分支都**刻意不调用** status.setRollbackOnly()：
            //   Go 在这三处是 `tx.Commit()`，不是 `tx.Rollback()`。
            //   标 blocked / 记重试次数 / 结算回执这三件事**必须落库** ——
            //   否则一次投递失败会永远停在 status=0，每秒被反复取出、反复失败。
            Event event;
            try {
                event = validator.decodeEnvelope(row.getDeliveryEnvelope());
            } catch (RuntimeException e) {
                event = null;
            }
            if (event == null || !validator.isValidDelivery(row, event)) {
                outboxMapper.markBlocked(row.getEventId());
                logger.warn("like fact frozen envelope blocked: event={} event_id={} producer={} "
                                + "outcome={} error_code={} duration_ms={}",
                        "like.fact_delivery.blocked", row.getEventId(), LikeDeliveryValidator.FACT_PRODUCER,
                        "manual_migration_required", "INVALID_FROZEN_ENVELOPE", millisSince(started));
                return DispatchResult.of(DispatchOutcome.BLOCKED, null);
            }

            logger.info("like fact delivery started: event={} event_id={} producer={} aggregate_version={}",
                    "like.fact_delivery.started", event.eventId, event.producer, event.aggregateVersion);

            // --- ③ 投递 + 回执校验（持锁期间）
            //     Go: fact_delivery.go:92-93
            TechnicalReceipt receipt = null;
            Exception sendErr = null;
            try {
                // 🔴 发的是**信封列**的原始字节（Go fact_delivery.go:92），不是重新序列化的字节
                receipt = sender.send(ctx, event, rawBytes(row.getDeliveryEnvelope()));
            } catch (Exception e) {
                sendErr = e;
            }
            Wire.Result<Instant> validated = receipt == null
                    ? new Wire.Result<>(null, false)
                    : Wire.validateReceipt(event, receipt);

            if (sendErr != null || !validated.isOk()) {
                // Go: fact_delivery.go:95-97 / :109 —— sendErr == nil 时才**合成**一句文案；
                //      sendErr != nil 时**原样返回**那个错误（fact_delivery.go:109 `return false, sendErr`）。
                //      ⇒ 这里必须把原始错误带出去，不能一律换成通用文案（那是把排障信息丢了）。
                if (sendErr == null) {
                    sendErr = new IllegalStateException(
                            "DataCenter technical receipt does not match like event");
                }
                outboxMapper.markDeliveryFailed(row.getEventId());
                logger.warn("like fact delivery deferred: event={} event_id={} producer={} outcome={} "
                                + "duration_ms={} retry_count={}",
                        "like.fact_delivery.deferred", event.eventId, event.producer, "retryable",
                        millisSince(started), row.getRetryCount() + 1);
                return DispatchResult.of(DispatchOutcome.FAILED, sendErr);
            }

            // --- ④ 落技术回执
            //     Go: fact_delivery.go:111-120
            // 🔴 technical_received_at 必须是 **DC 回执里的 received_at**，不是本地投递时刻。
            //    Go: fact_delivery.go:93 `received, receiptOK := ValidateReceipt(event, receipt)`
            //        → :114 `"technical_received_at": received`
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime receivedAt = validated.get().atOffset(ZoneOffset.UTC);
            outboxMapper.markDelivered(row.getEventId(), receipt.receiptId, receipt.inputHash,
                    receipt.offset, receivedAt, now);
            logger.info("like fact technical receipt committed: event={} event_id={} producer={} "
                            + "outcome={} duration_ms={} receipt_id={} offset={}",
                    "like.fact_delivery.accepted", event.eventId, event.producer, "succeeded",
                    millisSince(started), receipt.receiptId, receipt.offset);
            return DispatchResult.of(DispatchOutcome.SENT, null);
        });

        if (result == null) {
            return false;
        }
        // Go: fact_delivery.go:89 / :109 —— 在 Commit 之后才把错误交出去（保证状态变更已落库）
        if (result.outcome == DispatchOutcome.BLOCKED) {
            throw Wire.ERR_FROZEN_ENVELOPE_BLOCKED;
        }
        if (result.outcome == DispatchOutcome.FAILED) {
            // 🔴 保留原始错误作为 cause：Go 的 fact_delivery.go:109 是 `return false, sendErr`（原样交出），
            //    排障时靠它判断是超时、连接被拒、还是回执不匹配。
            Exception cause = result.error;
            throw new DeliveryDeferredException(cause.getMessage(), cause);
        }
        return result.outcome == DispatchOutcome.SENT;
    }

    /**
     * 「本次尝试失败，但行状态已被持久化（可重试）」的信号。
     *
     * <p>🔴 <b>刻意不是</b> {@link Wire.FrozenEnvelopeBlockedException}：
     * {@code Dispatcher.deliveryErrorCode} 按<b>类型</b>区分二者
     * （common 的 M16 修复：不再按文案字符串匹配），
     * 本类若复用那个类型，retryable 的失败会被上报成
     * {@code INVALID_FROZEN_ENVELOPE}。
     *
     * <p>⚠️ <b>原始投递错误必须挂在 {@code cause} 上</b>：
     * Go 的 {@code fact_delivery.go:109} 是 {@code return false, sendErr}（原样交出），
     * 不是换成一句通用文案 ⇒ 排障时能看出到底是超时、拒连还是回执不匹配。
     */
    public static class DeliveryDeferredException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DeliveryDeferredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Go 的 {@code (bool, error)} 三种可能结果。 */
    private enum DispatchOutcome {
        /** {@code (false, nil)} —— 队列空。 */
        EMPTY,
        /** 冻结信封非法 ⇒ 已提交 blocked。 */
        BLOCKED,
        /** 发送失败/回执不匹配 ⇒ 已提交 failed + retry_count+1。 */
        FAILED,
        /** {@code (true, nil)} —— 已提交 sent。 */
        SENT
    }

    /**
     * 事务体的返回值：Go 的 {@code (bool, error)} 二元组。
     *
     * <p>之所以要把它单独建模：<b>错误必须带着事务提交一起出来</b>。
     * 若把异常直接抛在事务体内，Spring 会回滚 ⇒ 状态落不了盘。
     */
    private static final class DispatchResult {

        final DispatchOutcome outcome;
        final Exception error;

        private DispatchResult(DispatchOutcome outcome, Exception error) {
            this.outcome = outcome;
            this.error = error;
        }

        static DispatchResult of(DispatchOutcome outcome, Exception error) {
            return new DispatchResult(outcome, error);
        }
    }

    private static byte[] rawBytes(String envelope) {
        return envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }
}
