package com.rag.favorite.service;

import com.rag.common.communityfact.Event;
import com.rag.common.communityfact.EventSender;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.common.communityfact.Wire;
import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import com.rag.favorite.mapper.FavoriteFactOutboxMapper;
import io.opentelemetry.context.Context;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 🔴 事实投递的一次尝试 —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_delivery.go:131-202} 的
 * {@code DispatchFavoriteFactOnce}。
 *
 * <h2>🔴🔴 为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</h2>
 *
 * <p>这是本域<b>最容易写错</b>的一处。Go 的形状是：
 * <pre>
 * tx := m.conn.WithContext(ctx).Begin()
 * defer tx.Rollback()
 * ...
 * if 冻结信封非法 { tx.Update(status=3); tx.Commit();  return false, ErrFavoriteFactBlocked }
 * if 发送失败      { tx.Update(status=2, retry+1); tx.Commit(); return false, sendErr }
 *                tx.Update(status=1, 5 个回执列); tx.Commit(); return true, nil
 * </pre>
 *
 * <p><b>三条出口里有两条是「更新状态后<b>提交</b>，然后返回错误」</b>。
 * 如果用 {@code @Transactional} + 抛异常：
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
 * <h2>🔴 事务跨 HTTP 出网</h2>
 * <p>取件行被 {@code FOR UPDATE} 锁住之后，<b>持锁期间</b>会发出一次 HTTP 到 DataCenter。
 * 这是 Go 的设计（favorite_delivery.go:135-195），Go 注释明写
 * 「keeps one outbox row locked until DC has returned its matching durable technical receipt」。
 * 本实现<b>保留</b>该形状：多个 dispatcher 因此会跳过被锁的行，而不会重复投递同一 event_id。
 * ⚠️ 代价是出网慢时会占着数据库连接与行锁 —— 这是 Go 已有的性质，不是 Java 引入的。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.service} 压平为
 *           {@code com.rag.favorite.service}（D7）；</li>
 *       <li>实体 / Mapper 的 import 由 {@code com.rag.favorite.favorite.rpc.*} 改为
 *           {@code com.rag.favorite.domain.entity} / {@code com.rag.favorite.mapper}（D7）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.Sender} → 顶层 {@link EventSender}、
 *           {@code Wire.Event} → 顶层 {@link Event}、
 *           {@code Wire.TechnicalReceipt} → 顶层 {@link TechnicalReceipt}；
 *           {@code Wire.validateReceipt} / {@code Wire.Result} /
 *           {@code Wire.ERR_FROZEN_ENVELOPE_BLOCKED} 仍是共享层门面 {@link Wire} 上的静态成员；</li>
 *       <li>构造器改用 Lombok 的 {@code @RequiredArgsConstructor}（D18）⇒ 展开见下。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。四条出口的方向、「<b>状态落盘先于错误抛出</b>」的顺序、
 *       「发出去的是<b>行内原始字节</b>」、三个写状态分支<b>刻意不</b> {@code setRollbackOnly}
 *       逐条未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteFactDispatchService.java:55-238}
 *       （类声明 :55 · 字段与构造器 :59-69 · {@code dispatchFactOnce} :84-186 ·
 *       {@code DeliveryDeferredException} :197-204 · {@code DispatchOutcome} :208-217 ·
 *       {@code appLayerNow} :234-236）。</li>
 * </ol>
 *
 * <h2>⚠️ 本类<b>不</b> implements 共享层的 {@code Dispatcher} SPI</h2>
 * B 侧 favorite 的投递方法是 <b>2 参</b>（{@code dispatchFactOnce(ctx, sender)}），
 * 而共享层的 {@code Dispatcher} SPI 是 <b>3 参</b>（多一个 {@code logger}）。
 * ⇒ B 侧在<b>编排层</b>用 lambda 适配（{@code FavoriteFactDispatchScheduler} 的
 * {@code (dispatchCtx, wireSender, logger) -> dispatchService.dispatchFactOnce(dispatchCtx, wireSender)}）。
 * ⇒ C 侧<b>照抄这个形状</b>：本类保持 2 参签名，由
 * {@code wiring/FavoriteFactDispatchScheduler} 负责适配
 * （⚠️ 该类属 {@code wiring/} 包，不在本组件范围内）。
 *
 * <h2>⚠️ 日志：<b>不用</b> {@code @Slf4j}，也<b>不用</b> {@code GoStyleLogger}</h2>
 * B 侧本类<b>就是</b> {@code LoggerFactory.getLogger(FavoriteFactDispatchService.class)}
 * （B 侧 {@code :57}），只调用 {@code LOG.atWarn()} / {@code LOG.atInfo()}，
 * <b>没有</b>任何 {@code logBusinessErr} / {@code call_chain} 语义的调用点
 * ⇒ ⇒ 换成 {@code GoStyleLogger} 属于<b>凭空新增</b>日志字段（表达层变更且无 B 侧依据）
 * ⇒ ⇒ <b>照抄普通 slf4j</b>（同 {@code ArticleDomainService} 的先例）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * private final FavoriteFactOutboxMapper outboxMapper;
 * private final FavoriteDeliveryValidator deliveryValidator;
 * private final PlatformTransactionManager transactionManager;
 *
 * public FavoriteFactDispatchService(FavoriteFactOutboxMapper outboxMapper,
 *                                    FavoriteDeliveryValidator deliveryValidator,
 *                                    PlatformTransactionManager transactionManager) {
 *     this.outboxMapper = outboxMapper;
 *     this.deliveryValidator = deliveryValidator;
 *     this.transactionManager = transactionManager;
 * }
 * }</pre>
 *
 * <p>⚠️ 字段声明顺序 = B 侧构造器的参数顺序 ⇒ Lombok 生成的构造器签名逐条一致。
 */
@Service
@RequiredArgsConstructor
public class FavoriteFactDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteFactDispatchService.class);

    /**
     * ⚠️ <b>字段声明顺序 = B 侧构造器的参数顺序</b> ⇒ Lombok 的
     * {@code @RequiredArgsConstructor} 生成的构造器签名与 B 侧逐条一致
     * （展开块见类 javadoc）。
     */
    private final FavoriteFactOutboxMapper outboxMapper;
    private final FavoriteDeliveryValidator deliveryValidator;
    private final PlatformTransactionManager transactionManager;

    /**
     * 🔴 对应 Go {@code DispatchFavoriteFactOnce(ctx, sender)}（favorite_delivery.go:131-202）。
     *
     * <p>它实现 {@code common/communityfact/Dispatcher} 的形状
     * （{@code dispatchFactOnce(ctx, sender)}），
     * 由 {@code FavoriteFactDispatchScheduler} 用
     * {@code EventDispatcher.runAsync(...)} 驱动（批次 16 / 节奏 1s / 单次 10s deadline）。
     *
     * @param ctx otel context（由 dispatcher 建立 span 并传入）
     * @param sender DC 发送器（Go 的 {@code FavoriteFactSender}）
     * @return {@code true} = 已投递一条；{@code false} = 队列空（<b>不是错误</b>）
     * @throws Wire.FrozenEnvelopeBlockedException 冻结信封非法（已标 blocked 并<b>提交</b>）
     */
    public boolean dispatchFactOnce(Context ctx, EventSender sender) {
        if (sender == null) {
            throw new IllegalStateException("favorite fact dispatcher is not configured");
        }

        // Go: :135-139 —— tx := Begin(); defer tx.Rollback()
        // ⚠️ 显式编程式事务：见类注释「为什么用 TransactionTemplate」
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        DispatchOutcome outcome = template.execute(status -> {
            // Go: service/favorite/rpc/internal/model/favorite_delivery.go:140-152 —— SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1
            FavoriteFactOutboxEntity row = outboxMapper.claimNextDeliverable();
            if (row == null) {
                // Go: service/favorite/rpc/internal/model/favorite_delivery.go:148-150 —— ErrRecordNotFound ⇒ (false, nil)：队列空，不是错误
                status.setRollbackOnly();   // 只读事务，无写入 ⇒ 回滚即可（Go 的 defer Rollback）
                return DispatchOutcome.EMPTY;
            }

            // Go: service/favorite/rpc/internal/model/favorite_delivery.go:154-167 —— 解码 + 校验失败 ⇒ status=3 blocked + Commit + 返回错误
            //
            // 🔴🔴 下面三个写状态分支都**刻意不调用** status.setRollbackOnly()：
            //   Go 在这三处是 `tx.Commit()`，不是 `tx.Rollback()`。
            //   标 blocked / 记重试次数 / 结算回执这三件事**必须落库** ——
            //   否则一次投递失败会永远停在 status=0，每秒被反复取出、反复失败。
            FavoriteDeliveryValidator.FrozenEvent event =
                    FavoriteDeliveryValidator.decodeLenient(row.getPayload());
            if (event == null || !deliveryValidator.validDelivery(row, event)) {
                outboxMapper.markBlocked(row.getEventId(), appLayerNow());
                LOG.atWarn()
                        .addKeyValue("event", "favorite.delivery.blocked")
                        .addKeyValue("event_id", row.getEventId())
                        .addKeyValue("producer", FavoriteFactEnvelopeFactory.PRODUCER)
                        .addKeyValue("outcome", "manual_migration_required")
                        .addKeyValue("error_code", "INVALID_FROZEN_ENVELOPE")
                        .log("favorite fact frozen envelope blocked");
                return DispatchOutcome.BLOCKED;
            }

            LOG.atInfo()
                    .addKeyValue("event", "favorite.delivery.started")
                    .addKeyValue("event_id", event.eventId)
                    .addKeyValue("producer", event.producer)
                    .addKeyValue("aggregate_version", event.aggregateVersion)
                    .log("favorite fact delivery started");

            // Go: service/favorite/rpc/internal/model/favorite_delivery.go:170 —— sender.Send(ctx, event, json.RawMessage(row.Payload))
            // ⚠️ 发出去的是**行内原始字节**，不是「解码后再序列化」的字节
            //   ⇒ DC 算出的 input_hash 与权威读重算的 input_hash 才必然相同。
            TechnicalReceipt receipt = null;
            Throwable sendError = null;
            try {
                receipt = sender.send(ctx, FavoriteDeliveryValidator.toWireEvent(event),
                        row.getPayload().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                sendError = t;
            }

            // Go: service/favorite/rpc/internal/model/favorite_delivery.go:171 —— received, receiptOK := validFavoriteReceipt(event, receipt)
            Wire.Result<Instant> receiptCheck = Wire.validateReceipt(
                    FavoriteDeliveryValidator.toWireEvent(event), receipt);
            boolean receiptOk = sendError == null && receiptCheck.isOk();

            if (!receiptOk) {
                // Go: service/favorite/rpc/internal/model/favorite_delivery.go:172-187 —— status=2 + retry_count+1 + Commit + 返回错误
                // 🔴 同样**不** setRollbackOnly：重试次数必须落库。
                outboxMapper.markFailed(row.getEventId(), appLayerNow());
                LOG.atWarn()
                        .addKeyValue("event", "favorite.delivery.deferred")
                        .addKeyValue("event_id", row.getEventId())
                        .addKeyValue("producer", event.producer)
                        .addKeyValue("outcome", "retryable")
                        .addKeyValue("retry_count", row.getRetryCount() + 1)
                        .log("favorite fact delivery deferred");
                return DispatchOutcome.FAILED;
            }

            // Go: service/favorite/rpc/internal/model/favorite_delivery.go:188-194 —— status=1 + 5 个回执列 + delivered_at
            java.time.OffsetDateTime acceptedAt = appLayerNow();
            outboxMapper.markAccepted(row.getEventId(), receipt.receiptId, receipt.inputHash,
                    receipt.offset, receiptCheck.get().atOffset(java.time.ZoneOffset.UTC),
                    acceptedAt, acceptedAt);
            LOG.atInfo()
                    .addKeyValue("event", "favorite.delivery.accepted")
                    .addKeyValue("event_id", event.eventId)
                    .addKeyValue("producer", event.producer)
                    .addKeyValue("outcome", "succeeded")
                    .addKeyValue("receipt_id", receipt.receiptId)
                    .addKeyValue("offset", receipt.offset)
                    .log("favorite fact technical receipt committed");
            return DispatchOutcome.SENT;
        });

        if (outcome == null) {
            return false;
        }
        // Go: service/favorite/rpc/internal/model/favorite_delivery.go:166 / :186 —— 在 Commit 之后才把错误交出去（保证状态变更已落库）
        if (outcome == DispatchOutcome.BLOCKED) {
            throw Wire.ERR_FROZEN_ENVELOPE_BLOCKED;
        }
        if (outcome == DispatchOutcome.FAILED) {
            throw new DeliveryDeferredException("DC technical receipt does not match favorite event");
        }
        return outcome == DispatchOutcome.SENT;
    }

    /**
     * 「本次尝试失败，但行状态已被持久化（可重试）」的信号。
     *
     * <p>🔴 <b>刻意不是</b> {@link Wire.FrozenEnvelopeBlockedException}：
     * {@code EventDispatcher.deliveryErrorCode} 按<b>类型</b>区分二者
     * （common 的 M16 修复：不再按文案字符串匹配），
     * 本类若复用那个类型，retryable 的失败会被上报成
     * {@code INVALID_FROZEN_ENVELOPE}。
     */
    public static class DeliveryDeferredException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DeliveryDeferredException(String message) {
            super(message);
        }
    }

    /** Go 的 {@code (bool, error)} 三种可能结果
     *     （{@code service/favorite/rpc/internal/model/favorite_delivery.go:131}）。
     *
     *  <p>⚠️ <b>B 侧既有缺陷，本实现照抄</b>：javadoc 说「三种可能结果」，
     * 但枚举实际有 <b>4 个</b>常量（{@code EMPTY} / {@code BLOCKED} / {@code FAILED} / {@code SENT}）。
     * 差一个的是「队列空」—— 它在 Go 侧是 {@code (false, nil)}，<b>不是 error</b>，
     * 所以「三种 error」的说法才成立 ⇒ 枚举项数与文案不一致，但<b>行为无差异</b>。
     */
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
     * 🔴 <b>应用层时钟</b>（2026-10-25 第七轮修）。
     *
     * <p><b>Go 出处</b>：Go 的 {@code tx.Model(&FavoriteFactOutbox{}).Where(...).Update(...)}
     * 走 GORM 的 Update 回调 ⇒ {@code autoUpdateTime} 字段<b>每次 UPDATE 都被 GORM 在应用层刷新</b>
     * （{@code gorm.io/gorm@v1.31.1/callbacks/update.go} 的 {@code field.AutoUpdateTime > 0} 分支）
     * ⇒ 值来自 <b>Go 进程时钟</b>。
     *
     * <p><b>修前的问题</b>：{@code markBlocked} / {@code markFailed} <b>根本不写 {@code updated_at}</b>
     * ⇒ 行的 {@code updated_at} 永远停在插入时刻；{@code markAccepted} 写的是
     * {@code updated_at = now()} ⇒ 值来自 <b>PG 服务器时钟</b> ⇒ 两处都与 Go 不同源。
     *
     * <p>⚠️ 用 {@link java.time.Clock#systemUTC()}：本类没有注入时钟（Go 侧的 dispatcher
     * 也没有可注入时钟源）⇒ 与同方法里 {@code delivered_at} 的取法保持一致。
     */
    private java.time.OffsetDateTime appLayerNow() {
        return java.time.OffsetDateTime.now(java.time.Clock.systemUTC());
    }
}
