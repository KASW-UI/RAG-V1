package com.rag.follow.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p><b>follow-rpc 业务指标（L1 观测契约）</b> —— 对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/metrics/FollowRpcMetrics.java}。
 *
 * <p><b>B 侧注册的 5 个 prometheus 指标</b>：
 * <pre>
 * RPCRequestCounter       subsystem=follow_rpc name=request_total              labels=[method, result]
 * RPCRequestDuration      subsystem=follow_rpc name=request_duration_seconds   labels=[method]
 * RelationActionCounter   subsystem=follow_rpc name=relation_action_total      labels=[action, result]
 * DBErrorCounter          subsystem=follow_rpc name=db_error_total             labels=[action, type]
 * RecommendationSizeGauge subsystem=follow_rpc name=recommendation_size        (无 label)
 * </pre>
 *
 * <p>Spring Boot 项目用 Micrometer 代替 prometheus client_golang，
 * <b>指标名与标签逐条 1:1</b> ⇒ 迁移完成后 Prometheus 抓取端不需要改配置。
 *
 * <p><b>⚠️ 四个 Counter 里的 {@code result} 标签取值只有两个</b>：
 * {@code "success"} / {@code "fail"}，判定标准是 {@code err == null}。
 * 这与 B 侧的 {@code ObserveRPC} / {@code ObserveRelation} 完全一致，
 * <b>不要引入第三种取值</b>。
 *
 * <p><b>⚠️ {@code ObserveRelation} 只在 4 个写方法上调用</b>
 * （Follow / Unfollow / Block / Unblock），
 * 三个列表方法与 {@code GetRecommendations} <b>不</b>调用它 ——
 * 所以 {@code relation_action_total} 永远不会有 {@code get_follow_list} 这类 action。
 *
 * <p><b>⚠️ 表达层差异（L4 · 语义等价）</b>：B 侧是「5 个静态注册的 prometheus 指标」，
 * C 侧是「Micrometer 声明即注册」⇒ <b>指标完整名（= {@code follow_rpc_} 前缀 + 名字）
 * 与标签集合逐条一致</b>，差异只在注册机制。
 */
@Component
public class FollowRpcMetrics {

    /** 对应 B 侧 {@code RPCRequestCounter}：{@code follow_rpc_request_total} labels=[method, result]。 */
    private static final String RPC_REQUEST_TOTAL = "follow_rpc_request_total";

    /** 对应 B 侧 {@code RPCRequestDuration}：{@code follow_rpc_request_duration_seconds} labels=[method]。 */
    private static final String RPC_REQUEST_DURATION = "follow_rpc_request_duration_seconds";

    /** 对应 B 侧 {@code RelationActionCounter}：{@code follow_rpc_relation_action_total} labels=[action, result]。 */
    private static final String RELATION_ACTION_TOTAL = "follow_rpc_relation_action_total";

    /** 对应 B 侧 {@code DBErrorCounter}：{@code follow_rpc_db_error_total} labels=[action, type]。 */
    private static final String DB_ERROR_TOTAL = "follow_rpc_db_error_total";

    /** 对应 B 侧 {@code RecommendationSizeGauge}：{@code follow_rpc_recommendation_size}。 */
    private static final String RECOMMENDATION_SIZE = "follow_rpc_recommendation_size";

    private final MeterRegistry registry;

    /** 缓存已注册的 timer（按 method 名缓存），避免每次 observeRpc 都重新 query。 */
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    /**
     * Gauge 的后备状态。
     *
     * <p><b>为什么需要它</b>：Micrometer 的 {@code Gauge} 只持有一个<b>弱引用</b>到数值对象
     * ⇒ 若把 {@code new AtomicInteger()} 直接内联传进去，GC 后 gauge 会读回 NaN。
     * 提升为字段即可保证与 Meter 同生命周期。
     */
    private final AtomicInteger recommendationSize = new AtomicInteger(0);

    public FollowRpcMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(RECOMMENDATION_SIZE, recommendationSize, AtomicInteger::doubleValue)
                .description("Last generated recommendation size")
                .register(registry);
    }

    /**
     * 对应 B 侧 {@code ObserveRPC(method, started, err)}：Counter +1（method + result 标签）＋ Timer 记录耗时。
     *
     * <p><b>8 个方法全部调用</b>：B 侧每个用例的 defer 第 1 行。
     * method 名是 B 侧的字符串字面量（{@code "Follow"} / {@code "Unfollow"} / …），
     * <b>首字母大写</b> —— 与 proto 的 RPC 名一致，不要改成小写。
     *
     * <p><b>⚠️ 桶边界必须显式钉成 B 侧的 DefBuckets</b>：
     * B 侧是 {@code prometheus.NewHistogramVec(..., Buckets: prometheus.DefBuckets)}
     * ⇒ 暴露 {@code _bucket{le=...}}。
     * <b>只用 {@code publishPercentileHistogram()} 是不够的</b> ⇒ Micrometer 会用它自己的
     * <b>HdrHistogram 派生的 68 个桶</b>（0.001 / 0.001048576 / … / 30.0）
     * ⇒ 与 B 侧的 {@code prometheus.DefBuckets}（11 个：.005/.01/.025/.05/.1/.25/.5/1/2.5/5/10）
     * <b>零重合</b>。
     * ⇒ 后果：同一段 PromQL（{@code histogram_quantile(0.95, …_bucket)}）在两侧<b>给出不同的分位数</b>。
     * ⇒ ⇒ <b>修法 = 只用 {@code serviceLevelObjectives(...)}</b>：Micrometer 的 SLO 直方图
     * <b>以 SLO 边界为桶边界</b> ⇒ 逐个等于 DefBuckets。
     * ⚠️ <b>不要再加 {@code publishPercentileHistogram()}</b>：实测 Micrometer 会把
     * <b>SLO 边界「叠加」到它自己的 68 个桶上</b>（⇒ 79 个桶）⇒ 反而更不接近 B 侧。
     * ⚠️ <b>也</b>不能用 {@code publishPercentiles()}：那是<b>分位数</b>开关，
     * Prometheus 文本格式<b>不支持</b>（只有 OpenMetrics 才有 quantile）⇒ 对着 Prometheus 抓取端无效。
     */
    public void observeRpc(String method, long startedAtNanos, Throwable err) {
        String resultLabel = (err == null) ? "success" : "fail";
        Counter.builder(RPC_REQUEST_TOTAL)
                .description("Total number of follow rpc requests")
                .tag("method", method)
                .tag("result", resultLabel)
                .register(registry)
                .increment();

        long durationNanos = System.nanoTime() - startedAtNanos;
        timerCache.computeIfAbsent(method, name -> Timer.builder(RPC_REQUEST_DURATION)
                        .description("Follow rpc request duration in seconds")
                        .tag("method", name)
                        // ⚠️ **顺序 = B 侧 DefBuckets 的顺序**（升序）⇒ 逐个对应。
                        .serviceLevelObjectives(
                                Duration.ofMillis(5),    // .005
                                Duration.ofMillis(10),   // .01
                                Duration.ofMillis(25),   // .025
                                Duration.ofMillis(50),   // .05
                                Duration.ofMillis(100),  // .1
                                Duration.ofMillis(250),  // .25
                                Duration.ofMillis(500),  // .5
                                Duration.ofSeconds(1),   // 1
                                Duration.ofMillis(2500), // 2.5
                                Duration.ofSeconds(5),   // 5
                                Duration.ofSeconds(10))  // 10
                        .register(registry))
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 对应 B 侧 {@code ObserveRelation(action, err)}。
     *
     * <p>action 取值：{@code "follow"} / {@code "unfollow"} / {@code "block"} / {@code "unblock"}
     * —— <b>全小写</b>，与 proto RPC 名的大小写约定相反，别抄串。
     */
    public void observeRelation(String action, Throwable err) {
        String resultLabel = (err == null) ? "success" : "fail";
        Counter.builder(RELATION_ACTION_TOTAL)
                .description("Total number of relation actions")
                .tag("action", action)
                .tag("result", resultLabel)
                .register(registry)
                .increment();
    }

    /**
     * 对应 B 侧 {@code ObserveDBError(action, typ)}。
     *
     * <p><b>type 取值逐条对应 B 侧各用例里传的第二个字符串</b>，共 12 个，
     * 少一个就少一个可观测维度：
     * <pre>
     *   exists_any_block          （follow 路径）
     *   create_follow             （follow 路径）
     *   delete_follow             （unfollow 路径）
     *   create_block_and_cleanup  （block 路径）
     *   delete_block              （unblock 路径）
     *   list_follow_targets       （follow/list 路径）
     *   list_block_targets        （block/list 路径）
     *   list_follower_users       （follower/list 路径）
     *   list_direct_follows       （recommendations 第 1 层）
     *   list_block_targets        （recommendations 的 blocked 集合）← 与上面同名，B 侧就是重复的
     *   list_blocker_users        （recommendations 的 blocker 集合）
     *   list_layer_follow_targets （recommendations 的 BFS 每层展开）
     * </pre>
     */
    public void observeDbError(String action, String type) {
        Counter.builder(DB_ERROR_TOTAL)
                .description("Total number of follow rpc database errors")
                .tag("action", action)
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * 对应 B 侧 {@code ObserveRecommendationSize(size)} —— 是个 Gauge，
     * 记的是<b>最近一次</b>推荐的条数，不是累计。
     *
     * <p><b>⚠️ 有 3 个上报点，其中 2 个是 {@code 0}</b>：
     * <ul>
     *   <li>直关为空 ⇒ 0</li>
     *   <li>offset 越界 ⇒ 0</li>
     *   <li>正常返回 ⇒ 实际条数</li>
     * </ul>
     * 漏掉前两个会让「推不出推荐」的两种情形在图上<b>看不出区别</b>（值停留在上一次成功）。
     */
    public void observeRecommendationSize(int size) {
        recommendationSize.set(size);
    }

    /**
     * 读取 Gauge 当前值 —— 对应 B 侧的 {@code RecommendationSizeGauge} 单值语义。
     *
     * <p><b>public 而非 package-private</b>：{@code recommendation_size} 是本域<b>唯一</b>
     * 需要断言「上报点有没有真的被走到」的指标 ——
     * 它有 3 个上报点，其中 2 个上报 0，而这两条路径返回的是 {@code null} 响应、
     * <b>单靠返回值断言不出来</b>。
     */
    public int currentRecommendationSize() {
        return recommendationSize.get();
    }
}
