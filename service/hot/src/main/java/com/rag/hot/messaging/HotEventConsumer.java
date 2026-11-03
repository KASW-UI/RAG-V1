package com.rag.hot.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.observability.MqConsumer;
import com.rag.hot.config.HotRpcProperties;
import com.rag.hot.contract.ArticleHotEvent;
import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.metrics.HotRpcMetrics;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p><b>消息入口</b>：校验 → 计数 → registry.add → 触发快照。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotEventConsumer.java}
 * （1-228 行；类声明 {@code :46-47}；三个常量 {@code :52} / {@code :55} / {@code :58}；
 * 字段 {@code :60-71}；构造器 {@code :73-90}；{@code onMessage} {@code :98-129}；
 * {@code consumeMessage} {@code :151-197}；{@code onPartitionsAssigned} {@code :204-207}；
 * {@code onPartitionsRevoked} {@code :215-218}；两个观测方法 {@code :221-227}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li>包名 {@code com.rag.hot.hot.rpc.mq} → {@code com.rag.hot.messaging}（D7 包压平）；</li>
 *         <li>{@code ArticleHotEvent} / {@code HotRpcProperties} / {@code HeavyKeeper} /
 *             {@code HotRpcMetrics} 的 import 随 D7 包压平调整；</li>
 *         <li><b>trace 载体</b>：B 侧传 OTel 的 {@code Context.current()} 给
 *             {@code MqConsumer.traceConsumer} ⇒ 本仓的共享层签名是
 *             {@code traceConsumer(ObservationRegistry, String, String, Map, Function)}
 *             （D4：手写 OTel span 改为 Micrometer Observation）
 *             ⇒ ⇒ 本类改为<b>注入 {@link ObservationRegistry}</b>；</li>
 *         <li><b>日志风格</b>：本仓走 {@code slf4j} + {@code GoStyleLogger}（A8），
 *             本类的技术日志用 {@code slf4j}，业务错误日志由 {@code HotApiService} 侧承担。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>「永不外抛」这条第一语义</b>逐条保留（见下方「🚨 本类的第一语义」）；</li>
 *         <li><b>校验顺序</b>逐条未动（反序列化 → article_id → 权重 → <b>先计数</b> → 再查分区）；</li>
 *         <li><b>「全局计数器」而非每分区计数</b>逐条未动；</li>
 *         <li><b>分区未持有 ⇒ 静默返回但仍然计数</b>逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🚨 本类的第一语义：<b>永不外抛</b></h2>
 * B 侧的消费循环无条件执行 {@code sess.MarkMessage(msg, "")}，
 * <b>即使 {@code consumeMessage} 返回错误</b>。⇒ 反序列化失败 / 缺 {@code article_id} /
 * 未知或零权重 type 这三类坏消息<b>被静默丢弃、offset 照常推进、永不重试</b>。
 *
 * <p>C 侧的默认行为完全相反：Spring Kafka 的 {@code DefaultErrorHandler} 会 seek + 重试，
 * 一条坏消息能<b>卡死整个分区</b>。因此本类：
 * <ol>
 *   <li>listener 方法<b>绝不向容器抛异常</b>（所有失败在内部吞掉并记 ERROR）⇒ 位点照常提交；</li>
 *   <li>payload 以 {@code String} 接收、手动反序列化（而不是交给 Kafka 反序列化器）
 *       ⇒ 让「解析失败」也落进上面那个吞异常的边界内，与 B 侧的
 *       {@code json.Unmarshal} 在 {@code consumeMessage} 内部发生的位置一致；</li>
 *   <li>容器层的 {@code DefaultErrorHandler} 配成零重试（见 {@code HotKafkaConfig}）作为兜底。</li>
 * </ol>
 */
@Component
public class HotEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(HotEventConsumer.class);

    /** B 侧 {@code hot_handler.go:105} 的 tracer 名。 */
    private static final String TRACER_NAME = "hot-rpc";

    /** B 侧 {@code hot_handler.go:105} 的 span 名。 */
    private static final String SPAN_NAME = "HotHandler.Consume";

    /** B 侧 {@code hot_handler.go:146}：单条消费超过 100ms 打慢日志（与全局 SlowThreshold 无关）。 */
    private static final long SLOW_LOG_THRESHOLD_MILLIS = 100L;

    private final ObjectMapper objectMapper;
    private final HotRpcProperties props;
    private final HotRpcMetrics metrics;
    private final HotPartitionSketchRegistry registry;
    private final HotRedisSnapshotStore store;
    private final TaskExecutor taskExecutor;
    private final ObservationRegistry observationRegistry;

    /** 对应 B 侧 {@code h.counter atomic.Int64}。 */
    private final AtomicLong counter = new AtomicLong();

    /** 对应 B 侧 {@code hot.go:47-50}：按 {@code Name} 建 map，{@code Code} 从不读取。 */
    private final Map<String, Integer> weights = new ConcurrentHashMap<>();

    /**
     * 构造器注入 —— B 侧逐字照抄（含构造时按 {@code Name} 建权重 map 这一步）。
     *
     * @param objectMapper       JSON 解析器
     * @param props              hot 域的 rpc 段配置
     * @param metrics            rpc 指标
     * @param registry           分区 sketch 注册表
     * @param store              Redis 快照存储
     * @param taskExecutor       异步执行器
     * @param observationRegistry 观测注册表（trace 载体 · D4）
     */
    public HotEventConsumer(ObjectMapper objectMapper,
                            HotRpcProperties props,
                            HotRpcMetrics metrics,
                            HotPartitionSketchRegistry registry,
                            HotRedisSnapshotStore store,
                            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                            ObservationRegistry observationRegistry) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.metrics = metrics;
        this.registry = registry;
        this.store = store;
        this.taskExecutor = taskExecutor;
        this.observationRegistry = observationRegistry;
        for (HotRpcProperties.Weight w : props.getInteraction().getWeights()) {
            if (w.getName() != null) {
                weights.put(w.getName(), w.getWeight());
            }
        }
    }

    /**
     * <p><b>Kafka 入口。本方法不向外抛任何异常。</b>
     *
     * <p>payload 用 {@code String} 接收：让 JSON 解析失败发生在方法内部（被吞），
     * 而不是发生在 Kafka 反序列化器里（会触发容器错误处理与重试）。
     *
     * @param record Kafka 记录
     */
    @KafkaListener(topics = "${hot-rpc.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "hotKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        final int partitionId = record.partition();
        final String key = record.key();
        final String value = record.value();

        Map<String, Object> attrs = Map.of(
                "messaging.system", "kafka",
                "messaging.destination", record.topic(),
                "messaging.operation", "consume",
                "messaging.kafka.partition", partitionId,
                "messaging.message.key", key == null ? "" : key);

        // B 侧：`_ = observability.TraceConsumer(...)` —— 返回的 error 被丢弃，位点照常 MarkMessage。
        // ⚠️ 关键：error 必须**交给** traceConsumer 才能被写进 span（markSystemError）。
        // 若在 lambda 内就吞掉，traceConsumer 收到的是 null ⇒ span 会被标成成功，与 B 侧不一致。
        Throwable err = MqConsumer.traceConsumer(observationRegistry, TRACER_NAME, SPAN_NAME, attrs,
                ctx -> {
                    try {
                        return consumeMessage(partitionId, value);
                    } catch (RuntimeException e) {
                        // 非预期异常也降级成返回值，绝不让它逃出 listener（否则触发容器重试）
                        return e;
                    }
                });

        if (err != null) {
            log.debug("[HotHandler] consume dropped (offset still committed): {}", err.getMessage());
        }
    }

    /**
     * <p><b>单条消息的处理</b>。
     *
     * <p>校验顺序<b>逐条对照 B 侧</b>：
     * <ol>
     *   <li>反序列化失败 ⇒ 返回 error（仍被 mark ⇒ drop）</li>
     *   <li>{@code ArticleID == ""} ⇒ 返回 error（drop）</li>
     *   <li>{@code weights[type]} 缺失或 {@code <= 0} ⇒ 返回 error（drop）</li>
     *   <li><b>校验全过之后</b>才 {@code eventConsumeTotal.Inc(type)}</li>
     *   <li>分区不在 {@code hkMap} ⇒ <b>静默返回</b>：事件被接受、offset 推进、
     *       <b>仍然计数</b>（指标在第 4 步已加），只是不入 sketch</li>
     *   <li>{@code hk.Add(id, weight)}</li>
     *   <li>全局计数器 {@code % syncEvery == 0} ⇒ 异步触发一次快照</li>
     * </ol>
     *
     * @param partitionId 分区号
     * @param value       消息体（JSON）
     * @return 等价 B 侧的 {@code error} 返回值：三类坏消息返回非 {@code null}，
     *         分区未持有与正常路径返回 {@code null}。
     *         ⚠️ 该返回值<b>必须</b>交给 {@code traceConsumer} 才能让 span 记录到 error，
     *         之后才在 {@link #onMessage} 层丢弃。
     */
    Throwable consumeMessage(int partitionId, String value) {
        long startNanos = System.nanoTime();

        ArticleHotEvent event;
        try {
            event = objectMapper.readValue(value, ArticleHotEvent.class);
        } catch (Exception e) {
            log.error("[HotHandler] unmarshal event failed: {}, value: {}", e.getMessage(), value);
            return e;
        }

        if (event.articleId() == null || event.articleId().isEmpty()) {
            log.error("[HotHandler] invalid event: {}", value);
            return new IllegalArgumentException("invalid hot event: missing article id");
        }

        Integer weight = event.type() == null ? null : weights.get(event.type());
        if (weight == null || weight <= 0) {
            log.error("[HotHandler] unknown or zero-weight type: {}, event: {}",
                    event.type(), value);
            return new IllegalArgumentException(
                    "unknown or zero-weight hot event type: " + event.type());
        }

        // ★ 必须在 hkMap 查找之前：B 侧的顺序是 Inc → 查 hkMap
        metrics.eventConsumed(event.type());

        HeavyKeeper hk = registry.heavyKeeperFor(partitionId);
        if (hk == null) {
            // B 侧静默 return nil：已计数、已 mark、只是不入 sketch
            return null;
        }

        hk.add(event.articleId(), weight);

        long syncEvery = props.getInteraction().getSyncEvery();
        if (syncEvery > 0 && counter.incrementAndGet() % syncEvery == 0) {
            // B 侧 `go h.syncToRedis(context.Background(), partitionID)`
            taskExecutor.execute(() -> store.flush(partitionId, hk));
        }

        long costMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (costMillis > SLOW_LOG_THRESHOLD_MILLIS) {
            log.warn("[HotHandler] slow consume, cost: {}ms, value: {}", costMillis, value);
        }
        return null;
    }

    /**
     * <p><b>分区获得</b>：建 sketch + 从 Redis 回灌。对应 B 侧 {@code OnPartitionsAssigned}。
     *
     * <p>回灌在锁外进行（见 {@link HotPartitionSketchRegistry} 的偏差论证）。
     *
     * @param partitions 获得的分区号
     */
    public void onPartitionsAssigned(Collection<Integer> partitions) {
        Map<Integer, HeavyKeeper> created = registry.assign(partitions);
        created.forEach(store::loadInto);
    }

    /**
     * <p><b>分区撤销</b>：先落盘、再从注册表摘除。对应 B 侧 {@code OnPartitionsRevoked}。
     *
     * <p>顺序与 B 侧的意图一致（{@code syncToRedis} 后 {@code delete}），但落盘在锁外完成，
     * 以避开 B 侧「持写锁再取读锁」的必然死锁。
     *
     * @param partitions 撤销的分区号
     */
    public void onPartitionsRevoked(Collection<Integer> partitions) {
        Map<Integer, HeavyKeeper> detached = registry.detach(partitions);
        detached.forEach(store::flush);
    }

    /**
     * 当前已处理的全局事件计数（测试观测用；B 侧 {@code h.counter} 无对外接口）。
     *
     * @return 计数
     */
    long processedCount() {
        return counter.get();
    }

    /**
     * 事件类型 → 权重（测试观测用）。
     *
     * @return 权重表
     */
    Map<String, Integer> weights() {
        return Map.copyOf(weights);
    }
}
