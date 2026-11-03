package com.rag.like.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * like 域的 <b>RPC 侧指标 + span 属性埋点</b> —— 对应 B 侧两个文件：
 * <ul>
 *   <li>{@code like-rpc/.../metrics/LikeRpcMetrics.java}（两个 CounterVec）；</li>
 *   <li>{@code like-rpc/.../service/LikeSpans.java}（13 个 span 属性名 + 写入方法）。</li>
 * </ul>
 *
 * <p><b>抄写基准</b>：B 侧两文件（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>把 B 侧 {@code LikeSpans} 并回本类</b>
 *       （映射表 {@code docs/Mapping_Table/like/like.md} 第 209 行：
 *       「B 侧把 span 名常量从指标类里<b>拆出</b> ⇒ C 侧并回 {@code LikeRpcMetrics}」）。
 *       ⇒ 本类同时承载「指标写入」与「span 属性写入」两组静态/实例方法；
 *       <b>属性名、取值、方法签名、写入时机逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。13 个属性名（{@code query.*} / {@code cache.*} /
 *       {@code like.*} / {@code messaging.*}）、两个 Counter 的名字与标签逐字未改。</li>
 *   <li><b>B 出处</b>：见上两个文件。</li>
 * </ol>
 *
 * <h2>⚠️ 指标名（GAP-8：必须按 B 侧修正）</h2>
 * <p>C 侧空壳 javadoc 写的是<b>连字符</b>版 {@code like-rpc_action_total} ——
 * <b>连字符不符合 Prometheus 指标名正则</b> ⇒ 那个名字不会出现在 {@code /metrics}。
 * ⇒ 本类按 B 侧写<b>下划线</b>版：{@code like_rpc_action_total} / {@code like_rpc_query_cache_total}。
 *
 * <table border="1">
 *   <caption>Go ↔ Micrometer 对照</caption>
 *   <tr><th>Go</th><th>Micrometer 名</th><th>labels</th></tr>
 *   <tr><td>{@code LikeActionCount}（Subsystem {@code "like-rpc"}）</td>
 *       <td>{@code like_rpc_action_total}</td><td>{@code target_type / action / result}</td></tr>
 *   <tr><td>{@code LikeQueryCacheCount}（Subsystem {@code "like_rpc"}）</td>
 *       <td>{@code like_rpc_query_cache_total}</td><td>{@code target_type / result}</td></tr>
 * </table>
 *
 * <p><b>⚠️ Go 侧两个 Subsystem 拼写不一致</b>（一个连字符一个下划线），是源码事实。
 * Prometheus 的指标名合法字符集不含连字符，所以 Java 侧统一用下划线；
 * 若要严格对齐 Go 的字面量，Prometheus 侧的抓取配置本身就得先能接受那个名字。
 * <b>此处不构成行为漂移</b>：指标名不是 API 契约，只是运维可读性差异。
 *
 * <p><b>result 取值必须保持 Go 的原样</b>，因为告警规则依赖它们：
 * <ul>
 *   <li>{@code LikeActionCount}：{@code invalid_param / idempotent / redis_error /
 *       snowflake_error / json_error / kafka_error / redis_read_error / success}</li>
 *   <li>{@code LikeQueryCacheCount}：{@code hit / miss / redis_error}</li>
 * </ul>
 * 缺一个或拼错一个，对应的告警就静默失效。
 *
 * <h2>🔴 与 {@code LikeSpans} 的关键区别：<b>span 属性只写「当前 span」，绝不新建 span</b></h2>
 * <p>B 侧 like 域是 {@code span := trace.SpanFromContext(l.ctx)} ⇒ <b>取 ctx 里已有的</b> span，不新建。
 * ⇒ C 侧用 {@link Span#current()} 取「当前线程的当前 span」：
 * <ul>
 *   <li>Go：ctx 里有 span ⇒ 返回它；<b>没有 ⇒ 返回 noop span</b>（{@code setAttribute} 是空操作）；</li>
 *   <li>Java：当前上下文有 span ⇒ 返回它；<b>没有 ⇒ 返回 {@code Span.getInvalid()}（non-recording）</b>，
 *       其 {@code setAttribute} <b>同样是空操作</b>。</li>
 *   <li>⇒ 两边「无 span 时静默丢弃属性」的行为<b>逐条一致</b>。</li>
 * </ul>
 *
 * <p><b>差异</b>：Go 读的是<b>方法参数里的 ctx</b>，Java 读的是<b>线程上下文</b>。
 * 在 gRPC 服务端，两者的载体必须由拦截器把 span 放进「当前上下文」才等价
 * ⇒ 该前提由共享层 {@code com.rag.common.observability.RpcInterceptor} 负责。
 */
@Component
public class LikeRpcMetrics {

    private final MeterRegistry registry;

    public LikeRpcMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * {@code LikeActionCount.WithLabelValues(targetType, action, result).Inc()}
     *
     * <p><b>⚠️ label 取值不做白名单过滤</b>：Go 直接把 {@code target_type} 塞进 label，
     * 而 target_type 来自请求参数。Go 因此存在「高基数」风险，本实现照搬 ——
     * 加白名单会让 {@code unknown} 这类值消失，改变告警可见性。
     */
    public void action(String targetType, String action, String result) {
        Counter.builder("like_rpc_action_total")
                .description("Total number of like/dislike actions")
                .tags("target_type", targetType, "action", action, "result", result)
                .register(registry)
                .increment();
    }

    /**
     * {@code LikeQueryCacheCount.WithLabelValues(targetType, result).Add(n)}
     *
     * @param count Go 侧的 {@code Add(float64(...))}，允许批量值（如一次回源记多个 id）
     */
    public void cache(String targetType, String result, double count) {
        Counter.builder("like_rpc_query_cache_total")
                .description("Cache hit/miss counts for like queries")
                .tags("target_type", targetType, "result", result)
                .register(registry)
                .increment(count);
    }

    /** {@code LikeQueryCacheCount...Inc()} 的计数 1 版本。 */
    public void cache(String targetType, String result) {
        cache(targetType, result, 1.0d);
    }

    /**
     * 记录单个 RPC 的处理耗时。
     *
     * <p><b>Go 侧没有这个指标</b>，新增理由：Go 侧只能靠
     * {@code observability.NewUnaryServerInterceptor} 在 gRPC 层记录，
     * Java 侧已通过 {@code com.rag.common.observability.RpcInterceptor} 统一承担（挂在 server 上）。
     * 本方法<b>不</b>在用例里调用，留给需要按业务名细分耗时的地方；
     * 全局 RPC 耗时由那个公共拦截器负责，避免两套口径。
     */
    public void recordLatency(String operation, long durationNanos) {
        Timer.builder("like_rpc_operation_latency")
                .description("like-rpc operation latency")
                .tag("operation", operation)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    // ==================================================================================
    // 以下为 B 侧 LikeSpans.java 并入本类后的内容（映射表第 209 行）
    // ==================================================================================

    // ---------------------------------------------------------------- query.*

    /** Go {@code attribute.Int64("query.user_id", in.UserId)}。 */
    public static final String ATTR_QUERY_USER_ID = "query.user_id";
    /** Go {@code attribute.String("query.target_type", in.TargetType)}。 */
    public static final String ATTR_QUERY_TARGET_TYPE = "query.target_type";
    /** Go {@code attribute.String("query.target_id", in.TargetId)}。 */
    public static final String ATTR_QUERY_TARGET_ID = "query.target_id";
    /** Go {@code attribute.Int("query.batch_size", len(in.TargetIds))}。 */
    public static final String ATTR_QUERY_BATCH_SIZE = "query.batch_size";
    /** Go {@code attribute.Int64("query.cursor", in.Cursor)}。 */
    public static final String ATTR_QUERY_CURSOR = "query.cursor";
    /** Go {@code attribute.Int64("query.page_size", int64(in.PageSize))}。 */
    public static final String ATTR_QUERY_PAGE_SIZE = "query.page_size";

    // ---------------------------------------------------------------- cache.*

    /** Go {@code attribute.Int("cache.hits", hits)}。 */
    public static final String ATTR_CACHE_HITS = "cache.hits";
    /** Go {@code attribute.Int("cache.misses", misses)}。 */
    public static final String ATTR_CACHE_MISSES = "cache.misses";
    /** Go {@code attribute.String("cache.status", "hit" | "miss")}。 */
    public static final String ATTR_CACHE_STATUS = "cache.status";

    /** Go 的 {@code cache.status} 命中取值。 */
    public static final String CACHE_STATUS_HIT = "hit";
    /** Go 的 {@code cache.status} 未命中取值。 */
    public static final String CACHE_STATUS_MISS = "miss";

    // ---------------------------------------------------------------- like.*

    /** Go {@code attribute.Int64("like.user_id", in.UserId)}。 */
    public static final String ATTR_LIKE_USER_ID = "like.user_id";
    /** Go {@code attribute.String("like.target_type", in.TargetType)}。 */
    public static final String ATTR_LIKE_TARGET_TYPE = "like.target_type";
    /** Go {@code attribute.String("like.target_id", in.TargetId)}。 */
    public static final String ATTR_LIKE_TARGET_ID = "like.target_id";
    /** Go {@code attribute.String("like.action_type", actionStr)}。 */
    public static final String ATTR_LIKE_ACTION_TYPE = "like.action_type";

    // ---------------------------------------------------------------- messaging.*

    /** Go {@code attribute.String("messaging.system", "kafka")}。 */
    public static final String ATTR_MESSAGING_SYSTEM = "messaging.system";
    /** Go {@code attribute.String("messaging.destination", topic)}。 */
    public static final String ATTR_MESSAGING_DESTINATION = "messaging.destination";
    /** Go {@code attribute.String("messaging.operation", "consume")}。 */
    public static final String ATTR_MESSAGING_OPERATION = "messaging.operation";
    /** Go {@code attribute.String("messaging.message.key", key)}。 */
    public static final String ATTR_MESSAGING_MESSAGE_KEY = "messaging.message.key";

    /**
     * Go {@code likemq.go:84} 里 {@code likeMessageAttrs("like-topic", key)} 的<b>字面量 topic</b>。
     *
     * <p>🔴 <b>注意这不是配置里的真 topic</b>（真 topic 是 {@code like_promotion_topic}，
     * 见 {@code messaging/LikeKafkaTopics}）。Go 侧把字面量 {@code "like-topic"} 写死了
     * ⇒ 它的 span 上 {@code messaging.destination} 永远是 {@code "like-topic"}。
     * <p>⇒ RAG-V1 <b>照抄这个字面量</b>：若「顺手改成真 topic」，trace 数据就会比 Go 多一条差异
     * （AGENTS.md §3：差异只允许出现在表达层）。
     */
    public static final String GO_MESSAGING_DESTINATION_LITERAL = "like-topic";

    /** Go {@code likemq.go:84} 的 tracer 名。 */
    public static final String MQ_TRACER_NAME = "like-mq";
    /** Go {@code likemq.go:84} 的 span 名。 */
    public static final String MQ_SPAN_NAME = "LikeUpdateService.Consume";

    // ---------------------------------------------------------------- 属性写入

    /** {@code query.user_id}（Go {@code attribute.Int64}）。 */
    public static void queryUserId(long userId) {
        attr(ATTR_QUERY_USER_ID, userId);
    }

    /** {@code query.target_type}（Go {@code attribute.String}）。 */
    public static void queryTargetType(String targetType) {
        attr(ATTR_QUERY_TARGET_TYPE, targetType);
    }

    /** {@code query.target_id}（Go {@code attribute.String}）。 */
    public static void queryTargetId(String targetId) {
        attr(ATTR_QUERY_TARGET_ID, targetId);
    }

    /** {@code query.batch_size}（Go {@code attribute.Int} ⇒ OTel Java 侧同为 long 型属性）。 */
    public static void queryBatchSize(int batchSize) {
        attr(ATTR_QUERY_BATCH_SIZE, (long) batchSize);
    }

    /** {@code query.cursor}（Go {@code attribute.Int64}）。 */
    public static void queryCursor(long cursor) {
        attr(ATTR_QUERY_CURSOR, cursor);
    }

    /** {@code query.page_size}（Go {@code attribute.Int64}）。 */
    public static void queryPageSize(long pageSize) {
        attr(ATTR_QUERY_PAGE_SIZE, pageSize);
    }

    /** {@code cache.hits}（Go {@code attribute.Int}）。 */
    public static void cacheHits(int hits) {
        attr(ATTR_CACHE_HITS, (long) hits);
    }

    /** {@code cache.misses}（Go {@code attribute.Int}）。 */
    public static void cacheMisses(int misses) {
        attr(ATTR_CACHE_MISSES, (long) misses);
    }

    /** {@code cache.status=hit}（Go {@code attribute.String("cache.status", "hit")}）。 */
    public static void cacheStatusHit() {
        attr(ATTR_CACHE_STATUS, CACHE_STATUS_HIT);
    }

    /** {@code cache.status=miss}（Go {@code attribute.String("cache.status", "miss")}）。 */
    public static void cacheStatusMiss() {
        attr(ATTR_CACHE_STATUS, CACHE_STATUS_MISS);
    }

    /** {@code like.user_id}（Go {@code attribute.Int64}）。 */
    public static void likeUserId(long userId) {
        attr(ATTR_LIKE_USER_ID, userId);
    }

    /** {@code like.target_type}（Go {@code attribute.String}）。 */
    public static void likeTargetType(String targetType) {
        attr(ATTR_LIKE_TARGET_TYPE, targetType);
    }

    /** {@code like.target_id}（Go {@code attribute.String}）。 */
    public static void likeTargetId(String targetId) {
        attr(ATTR_LIKE_TARGET_ID, targetId);
    }

    /** {@code like.action_type}（Go {@code attribute.String}，取值 like/unlike/dislike/undislike/unknown）。 */
    public static void likeActionType(String actionType) {
        attr(ATTR_LIKE_ACTION_TYPE, actionType);
    }

    /**
     * 构造 MQ 消费侧的 4 个属性 —— 对应 Go {@code likemq.go:137-143} 的 {@code likeMessageAttrs}。
     *
     * <p>Go 出处：
     * <pre>
     *   func likeMessageAttrs(topic, key string) []attribute.KeyValue {
     *       return []attribute.KeyValue{
     *           attribute.String("messaging.system", "kafka"),
     *           attribute.String("messaging.destination", topic),
     *           attribute.String("messaging.operation", "consume"),
     *           attribute.String("messaging.message.key", key),
     *       }
     *   }
     * </pre>
     * ⚠️ {@code topic} 传的是<b>字面量 {@code "like-topic"}</b>，不是配置里的真 topic
     * ⇒ 见 {@link #GO_MESSAGING_DESTINATION_LITERAL}。
     *
     * @param key 消费到的这条消息的 Kafka key（Go 侧 {@code Consume(ctx, key, val)} 的 {@code key} 参数）
     */
    public static Map<String, Object> messagingAttributes(String key) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_MESSAGING_SYSTEM, "kafka");
        attrs.put(ATTR_MESSAGING_DESTINATION, GO_MESSAGING_DESTINATION_LITERAL);
        attrs.put(ATTR_MESSAGING_OPERATION, "consume");
        attrs.put(ATTR_MESSAGING_MESSAGE_KEY, key);
        return attrs;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 往「当前 span」上写一个属性。
     *
     * <p>🔴 <b>无当前 span 时必须是空操作</b>（不能抛异常、不能 NPE）：
     * Go 侧 {@code trace.SpanFromContext(ctx)} 在无 span 时返回 noop span，
     * 其 {@code SetAttributes} 同样是空操作 ⇒ 两侧「没有 span 就静默丢弃」的行为一致。
     * OTel Java 的 {@code Span.current()} 在无 span 时返回 {@code Span.getInvalid()}
     * （non-recording）⇒ {@code setAttribute} 对它是空操作 ✓。
     */
    private static void attr(String key, String value) {
        Span.current().setAttribute(key, value);
    }

    private static void attr(String key, long value) {
        Span.current().setAttribute(key, value);
    }
}
