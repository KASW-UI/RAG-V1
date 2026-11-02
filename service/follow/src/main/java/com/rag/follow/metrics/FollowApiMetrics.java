package com.rag.follow.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>follow 域 HTTP 层三个 metric</b> —— B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/metrics/FollowApiMetrics.java:51-179}
 * 的等价物（逐条照抄）。
 *
 * <p><b>B 出处（逐字，已读）</b>：B 侧 {@code FollowApiMetrics.java:55-59} 的三个 metric 名、
 * {@code :79-91} 的桶边界、{@code :110-135} 的 {@code observeRequest}、
 * {@code :148-150} 的 {@code observeReject}。
 *
 * <h2>⚠️ L1 观测契约：指标<b>完整名</b> ＋ label 逐条不可改</h2>
 * <pre>
 *   follow_api_request_total{route, result}              ← result ∈ {success, fail}
 *   follow_api_reject_total{route, reason}               ← reason ∈ {user_id_missing, forbidden_scope}
 *   follow_api_request_duration_seconds{route}           ← Histogram（秒）
 * </pre>
 * <p>8 个 {@code route} 字面量（各 logic 的 {@code const route}）：
 * <pre>
 *   /follow/v1/follow          /follow/v1/unfollow       /follow/v1/block         /follow/v1/unblock
 *   /follow/v1/follow/list     /follow/v1/follower/list  /follow/v1/block/list    /follow/v1/recommendations
 * </pre>
 * ⇒ 路由常量<b>落在调用方</b>（{@code FollowRelationService} / {@code FollowQueryService} 各自持有），
 * 本类只负责「按 label 记一次」。
 *
 * <h3>⚠️ 为什么<b>不加</b> {@code tags.application}</h3>
 * <p>指标名<b>已带</b> {@code follow_api_} 前缀 ⇒ 再加一个 {@code application} tag 是冗余的
 * （B 侧也没有）⇒ 加了会让 {@code /metrics} 的输出形状与 B 侧不同 ⇒ <b>刻意不加</b>。
 *
 * <h3>⚠️ 桶边界必须显式钉成 Go 的 {@code DefBuckets}（已实测）</h3>
 * <p>Micrometer 的 {@code publishPercentileHistogram()} 会启用 <b>HdrHistogram 的 68 个百分位桶</b>
 * ⇒ 与 Go 的 {@code prometheus.DefBuckets}（11 个边界）<b>零重合</b> ⇒ 两侧的
 * {@code _bucket{le=...}} 序列完全不同 ⇒ {@code histogram_quantile()} 的结果不可比。
 * <p>⚠️ <b>本仓用 {@code serviceLevelObjectives(...)} 单独钉边界</b>，Micrometer 1.16.7 的
 * {@code DistributionStatisticConfig#isPublishingHistogram()} 对「SLO 非空」为真
 * ⇒ Prometheus registry 走 {@code TimeWindowFixedBoundaryHistogram}
 * ⇒ <b>导出恰好是 DefBuckets 的 11 个边界 + {@code +Inf}</b>。
 * <p>⚠️ <b>实测证据</b>（本仓工具链 micrometer-core 1.16.7 + prometheus-metrics 1.4.3，
 * 真 {@code PrometheusMeterRegistry} 抓 {@code /metrics} 文本）：
 * <pre>
 *   变体 A（只用 serviceLevelObjectives）  ⇒ le=0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0 +Inf   ← 与 Go 逐条一致
 *   变体 B（再加 publishPercentileHistogram）⇒ 上述 11 个之外还多出 68 个 HdrHistogram 边界            ← 两侧不可比
 * </pre>
 * ⇒ ⇒ <b>本类只调 {@code serviceLevelObjectives(...)}，绝不加 {@code publishPercentileHistogram()}</b>。
 *
 * <h3>⚠️ 指标名在 Prometheus 侧的最终形态（已实测）</h3>
 * <p>本类注册的 Micrometer meter 名<b>就是</b> {@code follow_api_request_total} 等三个字面量。
 * Micrometer 的 Prometheus 命名约定对 Counter <b>不</b>追加 {@code _total}
 * （追加是 Prometheus 新客户端在 exposition 时做的，且它<b>要求</b> meter 名<b>不含</b> {@code _total}）
 * ⇒ ⇒ 实测导出名为 {@code follow_api_request_total} / {@code follow_api_reject_total} /
 * {@code follow_api_request_duration_seconds}，<b>与 B 侧逐条一致</b>（没有 {@code _total_total}）。
 *
 * <h3>为什么「构造期不穷举 label 组合」</h3>
 * <p>B 侧用 {@code WithLabelValues}（首次访问时注册）；本类同样在<b>观测时</b>取
 * {@code registry.counter(...)}，而不是在构造器里把 8 个 route × 2 个 result 预先建出来
 * ⇒ 后者会产出一堆永远为 0 的时间序列，改变 {@code /metrics} 的输出形状（那也是可观察的）。
 *
 * <h3>⚠️ 本类<b>刻意不用 Lombok</b>（D18 的例外，逐条理由）</h3>
 * <p>本类只持有<b>一个</b> {@code final MeterRegistry} 字段：① {@code @Getter} 对它没有意义
 * （暴露 registry 等于把可变状态容器变成公开契约）；② 三个 meter 都是<b>按观测时动态注册</b>的
 * ⇒ <b>没有</b>可供 {@code @Getter} 暴露的 meter 字段（users 域 {@code UserApiMetrics} 有 7 个
 * {@code final Counter} 字段、故它用 {@code @Getter}，本域形态不同 ⇒ 不照抄）。
 * ⇒ ⇒ 本类<b>无类级 Lombok 注解</b> ⇒ A13 判据对本类不适用（见 {@code tools/difftest/check-arch-invariants.py}）。
 */
@Component
public class FollowApiMetrics {

    /** B 侧 {@code FollowApiMetrics.java:55} 的 {@code APIRequestCounter} metric 名。 */
    private static final String REQUEST_TOTAL = "follow_api_request_total";

    /** B 侧 {@code FollowApiMetrics.java:57} 的 {@code APIRejectCounter} metric 名。 */
    private static final String REJECT_TOTAL = "follow_api_reject_total";

    /** B 侧 {@code FollowApiMetrics.java:59} 的 {@code APIRequestDuration} metric 名。 */
    private static final String REQUEST_DURATION_SECONDS = "follow_api_request_duration_seconds";

    /**
     * Go {@code prometheus.DefBuckets} —— <b>逐字抄写</b>，单位是<b>秒</b>
     * （B 侧观测的就是 {@code time.Since(started).Seconds()}）。
     *
     * <p>B 出处：{@code FollowApiMetrics.java:79-91}（11 个边界逐条对应
     * {@code .005 .01 .025 .05 .1 .25 .5 1 2.5 5 10}）。
     *
     * <p>⚠️ <b>必须显式指定</b>：不指定 ⇒ Micrometer 用自己的默认桶 ⇒ 两侧
     * {@code _bucket{le=...}} 的边界与数量都不同 ⇒ {@code histogram_quantile()} 的结果不可比。
     * ⚠️ <b>且只能靠 {@code serviceLevelObjectives} 指定</b>（见类注释的实测证据）：
     * 加 {@code publishPercentileHistogram()} 会把 68 个 HdrHistogram 边界一起塞进来。
     */
    private static final Duration[] GO_DEF_BUCKETS = {
            Duration.ofMillis(5),    // 0.005 s
            Duration.ofMillis(10),   // 0.01  s
            Duration.ofMillis(25),   // 0.025 s
            Duration.ofMillis(50),   // 0.05  s
            Duration.ofMillis(100),  // 0.1   s
            Duration.ofMillis(250),  // 0.25  s
            Duration.ofMillis(500),  // 0.5   s
            Duration.ofSeconds(1),   // 1     s
            Duration.ofMillis(2500), // 2.5   s
            Duration.ofSeconds(5),   // 5     s
            Duration.ofSeconds(10)   // 10    s
    };

    private final MeterRegistry registry;

    public FollowApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 对应 B 侧 {@code FollowApiMetrics.java:110-135} 的 {@code ObserveRequest(route, started, err)}。
     *
     * <p><b>result 只有两个取值</b>：{@code "success"} / {@code "fail"}，
     * 取值规则与 B 侧一致 —— B 侧判的是「logic 方法返回的 err 是否为 nil」，
     * 因此「返回了业务失败码（如 5201 自关注）」也是 {@code fail}。
     *
     * @param route        完整路径（各 logic 的 route 常量）
     * @param failed       logic 是否以错误收场
     * @param elapsedNanos 起点到收场的耗时（纳秒）
     */
    public void observeRequest(String route, boolean failed, long elapsedNanos) {
        registry.counter(REQUEST_TOTAL,
                        "route", route,
                        "result", failed ? "fail" : "success")
                .increment();

        // ⚠️ **桶边界只能靠 serviceLevelObjectives 钉**（类注释有实测证据）：
        //   加 publishPercentileHistogram() ⇒ 多出 68 个 HdrHistogram 边界 ⇒ 与 Go 的 DefBuckets 不可比。
        // ⚠️ 每次观测都走 builder：Micrometer 的 register 对「同名 + 同 tag」的 meter 是幂等的
        //   （第二次返回首次注册的那个）⇒ 与 B 侧 WithLabelValues 的「首次访问时注册」同形。
        Timer.builder(REQUEST_DURATION_SECONDS)
                .tag("route", route)
                .serviceLevelObjectives(GO_DEF_BUCKETS)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 对应 B 侧 {@code FollowApiMetrics.java:148-150} 的 {@code ObserveReject(route, reason)}。
     *
     * <p><b>两个 reason 字面量</b>（B 侧全树只有这两个）：
     * <ul>
     *   <li>{@code "user_id_missing"} —— 8 个 logic 全部在开头调 {@code extractUserID} 失败时记这一条；</li>
     *   <li>{@code "forbidden_scope"} —— 仅 {@code /follow/v1/block/list} 与
     *       {@code /follow/v1/recommendations} 记这一条（那两个端点才做归属校验）。</li>
     * </ul>
     *
     * @param route  完整路径
     * @param reason 拒绝原因字面量
     */
    public void observeReject(String route, String reason) {
        registry.counter(REJECT_TOTAL, "route", route, "reason", reason).increment();
    }

    /**
     * 单个 route 的请求计数（测试与运维查询用）。
     *
     * @return 不存在时为 {@code null}（B 侧对应「该 label 组合从未出现过」）
     */
    public Double requestCount(String route, String result) {
        Counter counter = registry.find(REQUEST_TOTAL).tag("route", route).tag("result", result).counter();
        return counter == null ? null : counter.count();
    }

    /**
     * 单个 route 的拒绝计数（测试与运维查询用）。
     *
     * @return 不存在时为 {@code null}
     */
    public Double rejectCount(String route, String reason) {
        Counter counter = registry.find(REJECT_TOTAL).tag("route", route).tag("reason", reason).counter();
        return counter == null ? null : counter.count();
    }

    /**
     * 单个 route 的耗时直方图（测试与运维查询用）。
     *
     * @return 不存在时为 {@code null}
     */
    public Timer requestDuration(String route) {
        return registry.find(REQUEST_DURATION_SECONDS).tag("route", route).timer();
    }
}
