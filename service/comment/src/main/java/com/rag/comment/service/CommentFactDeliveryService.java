package com.rag.comment.service;

import com.rag.comment.domain.entity.CommentDomainFactOutboxEntity;
import com.rag.comment.domain.service.CommentDomainFactService;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.common.communityfact.Dispatcher;
import com.rag.common.communityfact.Event;
import com.rag.common.communityfact.EventSender;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.common.communityfact.Wire;
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
 * {@code service/comment/rpc/internal/model/fact_delivery.go:53-127} 的 {@code DispatchFactOnce}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentFactDeliveryService.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为 {@code com.rag.comment.service}（D7）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.comment.comment.rpc.domain} /
 *           {@code ...rpc.mapper} 改为 {@code com.rag.comment.domain.entity} /
 *           {@code com.rag.comment.mapper}（D7）；</li>
 *       <li>共享层 1:N 拆分 ⇒ {@code Wire.Sender} → {@link EventSender}、
 *           {@code Wire.Event} → {@link Event}、{@code Wire.TechnicalReceipt} → {@link TechnicalReceipt}、
 *           {@code Wire.Result} → {@link Wire.Result}；</li>
 *       <li><b>显式 implements {@link Dispatcher}</b>（共享层 SPI）⇒ 本类提供
 *           {@link #dispatchFactOnce}；同时保留 B 侧方法名 {@link #dispatchOnce}
 *           作为<b>等价入口</b>（委托，不复制逻辑）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。四条出口的方向、「状态落盘先于错误抛出」的顺序、
 *       「发的是信封列原始字节」逐字未改。</li>
 *   <li><b>B 出处</b>：见上；逐方法行号见各方法 javadoc。</li>
 * </ol>
 *
 * <h2>🔴🔴 为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</h2>
 *
 * <p>Go 的形状是：
 * <pre>
 * tx := m.conn.WithContext(ctx).Begin()
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
 * <p>🔴 <b>B 侧本轮修复的正是这个缺陷</b>：修复前本类用 {@code @Transactional}，
 * 于是「blocked / failed 永不落盘」—— 该行永远停在 {@code delivery_status=0}，
 * 调度器每秒重新领到它 ⇒ <b>无限重领活锁</b>。
 *
 * <h2>⚠️ 本方法<b>在一个事务里做网络 IO</b>，这是 Go 的刻意选择</h2>
 * <p>Go 的 {@code DispatchFactOnce} 手工 {@code Begin()} → 取件（{@code FOR UPDATE SKIP LOCKED}）
 * → <b>持锁期间</b>调 {@code sender.Send} → 落回执 → {@code Commit()}。
 * 方法注释说得很直白（fact_delivery.go:50-52）：
 * <pre>
 * // DispatchFactOnce keeps one source row locked until a matching DataCenter
 * // technical receipt is committed. Earlier versions of the same comment stream
 * // must be accepted first, so SKIP LOCKED cannot reorder a retract.
 * </pre>
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
 * <h2>⚠️ 失败(2) 与 阻塞(3) 语义不同</h2>
 * <p>{@code markFailed} 置的是<b>失败(2)</b> ⇒ 该行仍可被下一次取件重新领走（<b>可重试</b>）；
 * {@code markBlocked} 置的是<b>阻塞(3)</b> ⇒ 该行<b>不再</b>被调度器领走（等人工迁移）。
 * 两者都只改 {@code delivery_status} 等投递元数据列，<b>绝不碰信封列</b>
 * ⇒ DDL 的 {@code comment_fact_identity_immutable} 触发器不会拦。
 *
 * <p><b>本方法默认不启用</b>：由 {@code wiring/CommentFactDispatchScheduler} 在
 * {@code comment.fact.dispatch-enabled=true} 时才驱动（见该类注释）。
 */
@Service
public class CommentFactDeliveryService implements Dispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CommentFactDeliveryService.class);

    private final CommentDomainFactOutboxMapper outboxMapper;
    private final CommentDeliveryValidator validator;
    private final PlatformTransactionManager transactionManager;

    public CommentFactDeliveryService(CommentDomainFactOutboxMapper outboxMapper,
                                      CommentDeliveryValidator validator,
                                      PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.validator = validator;
        this.transactionManager = transactionManager;
    }

    /**
     * 对应 Go {@code DispatchFactOnce}：领一条 → 校验 → 投递 → 落回执。
     *
     * <p>本类<b>显式实现</b> {@link Dispatcher}（{@code dispatchFactOnce(ctx, sender, logger)}）
     * ⇒ 由 {@code wiring/CommentFactDispatchScheduler} 用 {@code EventDispatcher.runAsync(...)} 驱动
     * （批次 16 / 节奏 1s / 单次 10s deadline —— 三项都在共享层，本类不重复实现）。
     * ⚠️ <b>显式 implements 而不是靠方法引用「碰巧对上」</b>：
     * 方法引用能编译过不代表语义对得上（签名相同、行为不同也可能过）⇒ 写成 implements 后，
     * 签名一旦与共享层的契约漂移，<b>编译期就红</b>。
     *
     * <p><b>四条出口的方向必须保持</b>：
     * <ol>
     *   <li>outbox 空 ⇒ 返回 {@code false}（不抛错），调度器据此结束内层批次；</li>
     *   <li>冻结信封非法 ⇒ 置 <b>BLOCKED</b> 并抛
     *       {@link Wire#ERR_FROZEN_ENVELOPE_BLOCKED}（<b>不是</b>可重试的失败）；</li>
     *   <li>投递失败或回执不匹配 ⇒ 置 <b>FAILED</b> + {@code retry_count + 1} 并抛错（可重试）；</li>
     *   <li>成功 ⇒ 置 <b>ACCEPTED</b> 并写入 6 列技术元数据，返回 {@code true}。</li>
     * </ol>
     *
     * <p>🔴 <b>错误在事务提交之后才抛出</b>（Go 的 {@code tx.Commit()} 在 {@code return err} 之前）。
     * 这是「状态落盘」与「向上报错」两件事同时成立的前提。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentFactDeliveryService.java:106-230}。
     *
     * @return {@code true} = 已成功投递一条；{@code false} = outbox 里没有可投递的事实
     */
    @Override
    public boolean dispatchFactOnce(Context ctx, EventSender sender, Logger logger)
            throws Exception {
        long started = System.nanoTime();
        if (sender == null || logger == null) {
            throw new IllegalStateException("comment fact dispatcher is not configured");
        }

        // Go: fact_delivery.go:58-62 —— tx := Begin(); defer tx.Rollback()
        // ⚠️ 显式编程式事务：见类注释「为什么用 TransactionTemplate」
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        DispatchResult result = template.execute(status -> {
            // --- ① 取件（FOR UPDATE SKIP LOCKED + 前序屏障，锁持有到本方法返回）
            //     Go: fact_delivery.go:64-70；前序屏障与 SKIP LOCKED 都在 SQL 里（见
            //     CommentDomainFactOutboxMapper#selectNextDispatchableForUpdate 的注释），
            //     应用层**不重复实现**。
            CommentDomainFactOutboxEntity row = outboxMapper.selectNextDispatchableForUpdate();
            if (row == null) {
                // Go: fact_delivery.go:71-73 —— ErrRecordNotFound ⇒ (false, nil)：队列空，不是错误
                status.setRollbackOnly();   // 只读事务，无写入 ⇒ 回滚即可（Go 的 defer Rollback）
                return DispatchResult.of(DispatchOutcome.EMPTY, null);
            }

            // --- ② 校验冻结信封
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
                // ⚠️ 只改 delivery_status（→ 3），**绝不碰信封列** ⇒ 不可变触发器不会拦
                outboxMapper.markBlocked(row.getEventId());
                logger.warn("comment fact frozen envelope blocked: event={} event_id={} producer={} "
                                + "outcome={} error_code={} duration_ms={}",
                        "comment.fact_delivery.blocked", row.getEventId(), CommentDomainFactService.PRODUCER,
                        "manual_migration_required", "INVALID_FROZEN_ENVELOPE", millisSince(started));
                return DispatchResult.of(DispatchOutcome.BLOCKED, null);
            }

            logger.info("comment fact delivery started: event={} event_id={} producer={} aggregate_version={}",
                    "comment.fact_delivery.started", event.eventId, event.producer, event.aggregateVersion);

            // --- ③ 投递 + 回执校验（持锁期间）
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
                // Go: fact_delivery.go:96-98 —— sendErr == nil 时才**合成**一句文案；
                //      sendErr != nil 时**原样返回**那个错误（fact_delivery.go:110 `return false, sendErr`）。
                //      ⇒ 这里必须把原始错误带出去，不能一律换成通用文案（那是把排障信息丢了）。
                if (sendErr == null) {
                    sendErr = new IllegalStateException(
                            "DataCenter technical receipt does not match comment event");
                }
                // ⚠️ 失败(2) ⇒ 该行仍可被下次取件领走（可重试）；retry_count 在数据库侧自增
                outboxMapper.markFailed(row.getEventId());
                logger.warn("comment fact delivery deferred: event={} event_id={} producer={} outcome={} "
                                + "error_code={} duration_ms={} retry_count={}",
                        "comment.fact_delivery.deferred", event.eventId, event.producer, "retryable",
                        "DELIVERY_ATTEMPT_FAILED", millisSince(started), row.getRetryCount() + 1);
                return DispatchResult.of(DispatchOutcome.FAILED, sendErr);
            }

            // --- ④ 落技术回执
            // 🔴 technical_received_at 必须是 **DC 回执里的 received_at**，不是本地投递时刻。
            //    Go: fact_delivery.go:94 `received, receiptOK := ValidateReceipt(event, receipt)`
            //        → :115 `"technical_received_at": received`
            //    修复前这里把本地 `now` 同时写进了两列 ⇒ 权威读响应里的
            //    `technical_receipt.received_at` 报的是本地时刻，不是 DC 的接收时刻。
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime receivedAt = validated.get().atOffset(ZoneOffset.UTC);
            outboxMapper.markAccepted(row.getEventId(), receipt.receiptId, receipt.inputHash,
                    receipt.offset, receivedAt, now);
            logger.info("comment fact technical receipt committed: event={} event_id={} producer={} "
                            + "outcome={} duration_ms={} receipt_id={} offset={}",
                    "comment.fact_delivery.accepted", event.eventId, event.producer, "succeeded",
                    millisSince(started), receipt.receiptId, receipt.offset);
            // ⚠️ 这里**刻意不写 span 属性**（2026-10-27 删掉原先的 `Span.current().setAttribute` 两行）。
            //    ⚠️ **订正一处早期误判**：此前注记写「Go 侧 fact 投递链路根本不用 span」⇒ **不准确**：
            //       Go 侧**有**这个 span，只是建在**共享层**、且**不带属性**：
            //       `service/common/communityfact/process.go:26`
            //         `otel.Tracer("sea.rtw.communityfact").Start(ctx, "community.fact_delivery.dispatch")`
            //       （全文 69 行、**零** `SetAttributes`）⇒ Go 的 `DispatchFactOnce` 从不写 span 属性
            //       （域内 `service/comment/rpc/internal/model/` 整包 10 个非测试文件**零** span 命中）。
            //    · RAG 侧等价 span 同样在**共享层**：`common/…/communityfact/EventDispatcher.java`
            //      （同名 `community.fact_delivery.dispatch` ＋ 同 tracer `sea.rtw.communityfact`）
            //      ⇒ **span 拓扑两侧已对齐**，差的只是这两个属性。
            //    · 为什么 RAG 侧这两个属性**也**写不进去（这才是删它的真正理由）：
            //      共享层把 span 放进 **Context**（`ctx.with(span)`）而**从不 `makeCurrent`**
            //      ⇒ 且 `dispatchFactOnce` 跑在**另一个线程**
            //      （`communityfact-dispatch` 单线程池 + `supplyAsync`）
            //      ⇒ OTel 默认 `ThreadLocalContextStorage` 用的是**普通** `ThreadLocal`（**不继承**）
            //      ⇒ 投递线程上 `Span.current()` 恒为 **invalid span**
            //      ⇒ `if (span != null)` 恒真、`setAttribute` 落到 invalid span ⇒ **静默丢弃**（otel-java 的 no-op）。
            //    ⇒ ⇒ 两侧可观测行为一致（**都没有** `comment.fact.event_id` / `comment.fact.aggregate_version`）
            //      ⇒ 删掉是**零行为变化**的清理，**不是**改语义。
            // ⇒ 关键信息（`event_id` / `aggregate_version`）已在上面的 `logger.info` 里 ⇒ 不丢信息。
            return DispatchResult.of(DispatchOutcome.SENT, null);
        });

        if (result == null) {
            return false;
        }
        // Go: fact_delivery.go:89 / :110 —— 在 Commit 之后才把错误交出去（保证状态变更已落库）
        if (result.outcome == DispatchOutcome.BLOCKED) {
            throw Wire.ERR_FROZEN_ENVELOPE_BLOCKED;
        }
        if (result.outcome == DispatchOutcome.FAILED) {
            // 🔴 保留原始错误作为 cause：Go 侧 `return false, sendErr` 是把**原错误**交出去的，
            //    排障时靠它判断是超时、连接被拒、还是回执不匹配。
            Exception cause = result.error;
            throw new DeliveryDeferredException(cause.getMessage(), cause);
        }
        return result.outcome == DispatchOutcome.SENT;
    }

    /**
     * B 侧方法名 {@code dispatchOnce} 的<b>等价入口</b> —— 委托给 {@link #dispatchFactOnce}，
     * <b>不复制逻辑</b>（同一份实现、同一份语义）。
     *
     * <p>⚠️ 保留它的理由：B 侧 comment 域的调度器写的是 {@code deliveryService::dispatchOnce}
     * （B 出处：{@code comment-rpc/…/rpc/bootstrap/CommentFactDispatchScheduler.java:95}）
     * ⇒ 保留同名入口 ⇒ B 侧调用点<b>不必改写</b>，也不会因方法名不同而编译不过。
     *
     * <p>⚠️ 共享层 SPI 的方法名是 {@code dispatchFactOnce}
     * （{@code common/…/communityfact/Dispatcher.java:33}）⇒ 本类<b>显式 implements {@link Dispatcher}**
     * ⇒ 两条入口都成立，且共用同一份事务体。
     *
     * <p>（Java 的方法引用<b>不要求</b>被引用方法与 SAM 同名 —— 例如 {@code String::length}
     * 可用于 {@code Function.apply} ⇒ 所以两种方法引用写法都能编译通过。）
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentFactDeliveryService.java:106-230}。
     */
    public boolean dispatchOnce(Context ctx, EventSender sender, Logger logger) throws Exception {
        return dispatchFactOnce(ctx, sender, logger);
    }

    /**
     * 「本次尝试失败，但行状态已被持久化（可重试）」的信号。
     *
     * <p>🔴 <b>刻意不是</b> {@link Wire.FrozenEnvelopeBlockedException}：
     * 共享层 {@code Dispatcher.deliveryErrorCode} 按<b>类型</b>区分二者
     * （common 的 M16 修复：不再按文案字符串匹配），
     * 本类若复用那个类型，retryable 的失败会被上报成
     * {@code INVALID_FROZEN_ENVELOPE}。
     *
     * <p>⚠️ <b>原始投递错误必须挂在 {@code cause} 上</b>：
     * Go 的 {@code fact_delivery.go:110} 是 {@code return false, sendErr}（原样交出），
     * 不是换成一句通用文案 ⇒ 排障时能看出到底是超时、拒连还是回执不匹配。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentFactDeliveryService.java:245-252}。
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
     * 若把异常直接抛在事务体内，Spring 会回滚 ⇒ 状态落不了盘（这正是 B 侧修掉的漂移）。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentFactDeliveryService.java:272-285}。
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
