package com.rag.article.messaging;

import com.rag.article.config.ArticleRpcProperties;
import com.rag.article.domain.entity.ArticleSyncOutboxEntity;
import com.rag.article.mapper.ArticleSyncOutboxMapper;
import com.rag.article.metrics.ArticleRpcMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p><b>职责</b>：文章同步发件箱中继 —— 每秒扫一次 {@code article_sync_outbox}，
 * 把待投递事件<b>逐条独立</b>推给 Kafka。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mq/ArticleSyncOutboxRelay.java}
 * （类声明 {@code :48-49} · 字段与构造器 {@code :53-66} · {@code sendPending} {@code :94-106} ·
 * {@code normalizeIntervalMs} {@code :118-120} · {@code relayOnce} {@code :123-155} ·
 * {@code effectiveIntervalMs} {@code :168-170} · {@code effectiveBatchSize} {@code :173-176}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.mq} → {@code com.rag.article.messaging}（D7 包压平）；</li>
 *         <li><b>SpEL 里的类型全名</b>：{@code T(com.rag.article.article.rpc.mq.ArticleSyncOutboxRelay)}
 *             → {@code T(com.rag.article.messaging.ArticleSyncOutboxRelay)}
 *             ⇒（SpEL 的 {@code T(...)} 引用<b>必须写全限定名</b>，包名改了这里必须同步改，
 *             否则<b>调度器在启动期就报找不到类</b>）。</li>
 *       </ul>
 *       ⇒ <b>方法体、SQL 调用序列、推送参数一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>捞取条件仍是 {@code status IN (0,2) AND retry_count < maxRetry} + {@code created_at ASC} + LIMIT N；</li>
 *         <li>仍是<b>逐条独立</b>（一条推失败只 {@code markFailed} 并 continue）；</li>
 *         <li>推送内容仍是 <b>payload 原文</b>（不重新序列化）；</li>
 *         <li>key 仍是 {@code AggregateID}（保证同文章事件在分区内有序）；</li>
 *         <li>仍是 <b>.get() 等待结果</b>（Go 的 {@code PushWithKey} 是同步阻塞）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>为什么用 {@code @Scheduled} 而不是常驻线程</h2>
 * Go 用 {@code service.NewServiceGroup()} 起一个 goroutine + {@code time.Ticker}。
 * Spring 的 {@code @Scheduled(fixedRate)} 由容器托管生命周期、异常不会打死循环，
 * 正好替代手写的 goroutine + select（那属于「自己实现调度器」）。
 *
 * <h2>⚠️ 四条不能改的行为</h2>
 * <ol>
 *   <li><b>捞取条件</b>：{@code status IN (0,2) AND retry_count < maxRetry}
 *       （maxRetry 来自配置，yaml 是 <b>10</b>；{@code <= 0} 时不加该条件 ⇒ 无限重试），
 *       按 {@code created_at ASC} 取前 N 条。超过上限的事件被<b>永久搁置</b>，不进死信、不告警。</li>
 *   <li><b>逐条独立</b>：一条推失败只 {@code markFailed} 并 {@code continue}，
 *       不影响同批其它事件（Go 明确写了 {@code continue}）。
 *       ⚠️ <b>不要改成「一条失败就整批中止」</b>：那会让一条坏事件卡住后面所有事件。</li>
 *   <li><b>推送内容就是 payload 原文</b>：Go {@code PushWithKey(ctx, event.AggregateID, event.Payload)}，
 *       不是重新序列化 —— 保持与事务里写入的字节完全一致。
 *       这正是发布回调「冻结输入比对」能成立的前提。</li>
 *   <li><b>key 是 AggregateID</b>（不是 event_id、不是 null）。这保证同一文章的事件在
 *       Kafka 分区内<b>有序</b>；换成 event_id 会打散顺序，让下游看到乱序版本。</li>
 * </ol>
 *
 * <p><b>⚠️ 同步 vs 异步的差异（已在 application.yml 留痕，不是悄悄改掉）</b>：
 * Go 的 {@code PushWithKey} 是<b>同步阻塞</b>的，返回 error 时 logic 能据此判定失败。
 * Spring Kafka 的 {@code send(...)} 是<b>异步</b>的：立即返回 future，
 * 网络错误在 future 上而非调用栈上。
 * ⇒ 本实现 {@code .get()} 等待结果，让「推送失败」仍能被捕获并 {@code markFailed}，
 * 保持 outbox 状态机的正确性；代价是 relay 线程会阻塞在 broker 往返上
 * （Go 侧同样阻塞，语义一致）。
 */
@Component
public class ArticleSyncOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ArticleSyncOutboxRelay.class);

    private final ArticleSyncOutboxMapper outboxMapper;

    private final KafkaTemplate<String, String> kafkaTemplate;

    private final ArticleRpcProperties properties;

    private final ArticleRpcMetrics metrics;

    /**
     * 构造器 —— 依赖逐条照抄 B 侧。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleSyncOutboxRelay.java:58-66}。
     *
     * @param outboxMapper 发件箱的数据访问
     * @param kafkaTemplate Kafka 模板（全局单例）
     * @param properties    本域配置（topic / 间隔 / 批量 / 最大重试）
     * @param metrics       指标
     */
    public ArticleSyncOutboxRelay(ArticleSyncOutboxMapper outboxMapper,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ArticleRpcProperties properties,
                                  ArticleRpcMetrics metrics) {
        this.outboxMapper = outboxMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 每 {@code pollIntervalMs} 毫秒扫一次发件箱。
     *
     * <h2>🔴 两条 Go 语义必须由这一处同时兑现</h2>
     * <pre>
     * // Go article.go:62-65（间隔兜底）
     * relayInterval := time.Duration(c.ArticleSyncOutbox.PollIntervalMs) * time.Millisecond
     * if relayInterval &lt;= 0 { relayInterval = time.Second }
     *
     * // Go article.go:94,101（首次触发时机）
     * ticker := time.NewTicker(s.interval)     // ← 首次 tick 在 interval **之后**
     * for { select { case &lt;-ticker.C: … } }
     * </pre>
     * <ol>
     *   <li><b>兜底</b>：配置 ≤ 0 ⇒ <b>1s</b>。
     *       ⚠️ {@code @Scheduled} 的 fixedRate <b>不接受非正数</b>
     *       ⇒ 不能直接用 {@code ${...}} 占位符（配置写 0 会让 Spring 启动期报错）。
     *       ⇒ 改用 <b>SpEL</b>：{@code #{T(...).normalizeIntervalMs(${...})}}
     *       —— Spring 先解析 {@code ${...}}，再求值 {@code #{...}}，于是兜底能真正生效。</li>
     *   <li><b>首次时机</b>：Go 的 ticker <b>首次在 interval 之后</b>触发；
     *       而 {@code @Scheduled(fixedRate)} 未设 {@code initialDelay} 时按 Spring 默认
     *       <b>立即</b>执行首次 ⇒ 本仓会比 Go 早 ≤1s 推第一批 outbox。
     *       ⇒ 显式给 {@code initialDelayString} <b>同一个</b>归一化后的间隔。</li>
     * </ol>
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleSyncOutboxRelay.java:94-106}。
     */
    @Scheduled(
            fixedRateString = "#{T(com.rag.article.messaging.ArticleSyncOutboxRelay)"
                    + ".normalizeIntervalMs(${article-rpc.article-sync-outbox.poll-interval-ms:1000})}",
            initialDelayString = "#{T(com.rag.article.messaging.ArticleSyncOutboxRelay)"
                    + ".normalizeIntervalMs(${article-rpc.article-sync-outbox.poll-interval-ms:1000})}")
    public void sendPending() {
        try {
            relayOnce();
        } catch (Exception e) {
            // Go: logx.Errorf("send article sync outbox failed: %v", err) —— 不让循环停掉
            log.warn("article sync outbox relay iteration failed: {}", e.getMessage());
        }
    }

    /**
     * Go {@code article.go:62-65} 的间隔兜底 —— <b>配置 ≤ 0 ⇒ 1000ms</b>。
     *
     * <p>⚠️ <b>必须是 {@code static}</b>：{@code @Scheduled} 的属性是在
     * <b>容器装配期</b>求值的，那时还拿不到本类的实例注入，
     * 所以只能用 SpEL 的 {@code T(...)} 类型引用调这个静态方法。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleSyncOutboxRelay.java:118-120}。
     *
     * @param configured 配置的间隔毫秒数
     * @return 归一化后的间隔（≤0 ⇒ 1000）
     */
    public static long normalizeIntervalMs(long configured) {
        return configured <= 0L ? 1000L : configured;
    }

    /**
     * 单次中继；抽出来便于测试直接调用，不依赖调度器。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleSyncOutboxRelay.java:123-155}。
     */
    public void relayOnce() {
        int batchSize = effectiveBatchSize();
        int maxRetry = properties.getArticleSyncOutbox().getMaxRetry();

        List<ArticleSyncOutboxEntity> events = outboxMapper.fetchPending(batchSize, maxRetry);
        if (events == null || events.isEmpty()) {
            return;
        }

        for (ArticleSyncOutboxEntity event : events) {
            try {
                // Go: PushWithKey(ctx, event.AggregateID, event.Payload)
                //     —— key=AggregateID，value=payload 原文，不重新序列化
                kafkaTemplate.send(
                                properties.getArticleSyncPusherConf().getTopic(),
                                event.getAggregateId(),
                                event.getPayload())
                        .get();
                outboxMapper.markSent(event.getEventId());
            } catch (Exception e) {
                metrics.kafkaPushError("article_sync_event");
                log.warn("push article sync event failed: eventId={} aggregateId={} err={}",
                        event.getEventId(), event.getAggregateId(), e.getMessage());
                // Go: 记日志 → MarkFailed → continue（不影响同批其它事件）
                try {
                    outboxMapper.markFailed(event.getEventId(), e.getMessage());
                } catch (Exception markErr) {
                    log.warn("mark article sync outbox failed error: eventId={} err={}",
                            event.getEventId(), markErr.getMessage());
                }
            }
        }
    }

    /**
     * Go {@code article.go:62-65} 的间隔兜底：{@code if interval <= 0 { interval = time.Second }}。
     *
     * <p>⚠️ 之所以需要它：{@code @Scheduled} 的 fixedRate 不接受 ≤ 0，
     * 而 Go 侧允许配置为 0 并自行兜底。少了这个兜底，
     * 「配置写 0」会从「每秒」变成「Spring 启动即报错」。
     *
     * <p>⚠️ <b>兜底逻辑只有一处</b>：本方法与
     * {@code @Scheduled} 属性里的 SpEL <b>都</b>调 {@link #normalizeIntervalMs(long)}
     * —— 两边若各写一份，改一处就会漂移。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleSyncOutboxRelay.java:168-170}。
     *
     * @return 归一化后的间隔毫秒数
     */
    long effectiveIntervalMs() {
        return normalizeIntervalMs(properties.getArticleSyncOutbox().getPollIntervalMs());
    }

    /**
     * Go {@code article.go:57-60} 的批量兜底：{@code if batchSize <= 0 { batchSize = 100 }}。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleSyncOutboxRelay.java:173-176}。
     *
     * @return 归一化后的批量大小
     */
    int effectiveBatchSize() {
        int configured = properties.getArticleSyncOutbox().getBatchSize();
        return configured <= 0 ? 100 : configured;
    }
}
