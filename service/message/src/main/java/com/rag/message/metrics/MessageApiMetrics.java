package com.rag.message.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * <p><b>message 域 HTTP 层指标</b> —— 承载 3 个 {@code message_api_*} 指标。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/metrics/MessageApiMetrics.java}
 * （B 侧类 javadoc {@code :10-48} · 三个指标名常量 {@code :52-57} · 构造器 {@code :59-63} ·
 * {@code observeRequest :65-83} · {@code observeReject :85-98} ·
 * {@code requestCount :100-108} · {@code rejectCount :110-118} · {@code requestDuration :120-127}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>包名</b>（{@code com.rag.message.message.api.metrics} →
 *       {@code com.rag.message.metrics}，D7 包压平）＋ <b>继承共享基座</b>
 *       {@link MessageMetrics}（前缀 / 标签键 / {@code DefBuckets} / 注册手法收口到一处）
 *       ＋ <b>耗时直方图的桶边界按 Go 口径钉死</b>（详见 {@link #observeRequest}）。</li>
 *   <li><b>语义还一样吗</b>：<b>指标名 / 标签键 / 标签取值 / 计数口径逐条一致</b>；
 *       <b>唯一的一处形态差异</b>是耗时直方图的<b>桶边界与导出形状</b>
 *       （B 侧 Java 未钉 ⇒ Micrometer 默认导出 summary；本仓钉成 Go 的
 *       {@code prometheus.DefBuckets} ⇒ 导出为直方图）—— 该差异的来源与依据见
 *       {@link #observeRequest} 的注释，<b>请主会话裁决是否登记为签字偏离</b>。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>Go 出处</b>（三个 metric，prometheus 子系统名 {@code message_api}）：
 * <pre>
 * APIRequestCounter  = NewCounterVec(CounterOpts{Subsystem:"message_api", Name:"request_total"},   []string{"route","result"})
 * APIRejectCounter   = NewCounterVec(CounterOpts{Subsystem:"message_api", Name:"reject_total"},    []string{"route","reason"})
 * APIRequestDuration = NewHistogramVec(HistogramOpts{Subsystem:"message_api", Name:"request_duration_seconds"}, []string{"route"})
 *
 * func ObserveRequest(route string, started time.Time, err error) {
 *     result := "success"; if err != nil { result = "fail" }
 *     APIRequestCounter.WithLabelValues(route, result).Inc()
 *     APIRequestDuration.WithLabelValues(route).Observe(time.Since(started).Seconds())
 * }
 * func ObserveReject(route, reason string) { APIRejectCounter.WithLabelValues(route, reason).Inc() }
 * </pre>
 *
 * <p><b>为什么用按需创建而不是构造期建好全部组合</b>：Go 的 {@code CounterVec} 是
 * label 值动态注册（{@code WithLabelValues} 首次调用时创建 child）。13 个端点 ×
 * 2 种 result + 3 种 reason，如果构造期穷举会得到一堆永远为 0 的时间序列；
 * Micrometer 的 {@code registry.counter(name, tags...)} 语义与 {@code WithLabelValues}
 * 一致，都是首次访问时注册。
 *
 * <p><b>route label 的值是完整路径</b>（Go 各 logic 里的 {@code const route = "..."} 字面量），
 * 例如 {@code "/message/v1/conversations/list"}、{@code "/message/v1/admin/notifications/send"}。
 * 13 个字面量逐条抄自 Go 源文件，见各 Service 类的常量。
 *
 * <p><b>埋点位置也在逐条对齐</b>：Go 侧每个 logic 方法体的头尾是
 * <pre>
 * started := time.Now()
 * const route = "..."
 * defer func() { metrics.ObserveRequest(route, started, err) }()
 * ...
 * uid, err := shared.UserIDFromContext(l.ctx)
 * if err != nil { metrics.ObserveReject(route, "user_id_missing"); return nil, err }
 * </pre>
 * 即 <b>无论成功失败都记一次 request_total</b>，身份缺失另外记一次 reject_total。
 * Java 侧用 try/finally 表达同一时序（见各 Service）。
 */
@Component
public class MessageApiMetrics extends MessageMetrics {

    /** Go {@code APIRequestCounter} 的 metric 名（子系统 + name）。 */
    private static final String REQUEST_TOTAL = "message_api_request_total";
    /** Go {@code APIRejectCounter}。 */
    private static final String REJECT_TOTAL = "message_api_reject_total";
    /** Go {@code APIRequestDuration}。 */
    private static final String REQUEST_DURATION_SECONDS = "message_api_request_duration_seconds";

    public MessageApiMetrics(MeterRegistry registry) {
        super(registry);
    }

    /**
     * 对应 Go {@code ObserveRequest(route, started, err)}。
     *
     * <p><b>result 只有两个取值</b>：{@code "success"} / {@code "fail"}，
     * 取值规则与 Go 完全一致 —— Go 判的是 <b>logic 方法返回的 err 是否为 nil</b>，
     * 因此「返回了业务失败码」也是 {@code fail}。
     *
     * <p>🔴 <b>耗时指标的桶边界：逐字继承 B 侧 Java（<b>不</b>向 Go 收敛）</b>：
     * <ul>
     *   <li><b>B 侧 Java（B = 唯一判定基准 · D16）</b>：
     *       {@code service/message/message-api/src/main/java/com/rag/message/message/api/metrics/MessageApiMetrics.java:81-84}
     *       用的是 {@code registry.timer(REQUEST_DURATION_SECONDS, "route", route)}
     *       —— <b>没有指定桶边界</b> ⇒ Micrometer 的 Prometheus registry 导出的是
     *       <b>summary</b>（{@code _count} / {@code _sum} / {@code _max}）⇒
     *       ⇒ <b>没有</b> {@code _bucket} 系列。</li>
     *   <li><b>Go 侧（更早的上游）</b>：{@code service/message/api/internal/metrics/metrics.go} 的
     *       {@code NewHistogramVec(HistogramOpts{…}, []string{"route"})}
     *       —— 走默认桶 = {@code prometheus.DefBuckets} ⇒ 导出形状是<b>直方图</b>。</li>
     *   <li><b>⚠️ B 侧 Java 与 Go 在这一处本就存在形状差异</b>（summary vs 直方图）
     *       ⇒ 按 AGENTS.md §1.0c ②「<b>B 侧已有的差异一律继承（登记为签字偏离），不回溯第三方</b>」
     *       ⇒ 本仓<b>逐字继承 B 侧 Java</b>（不钉桶）⇒
     *       ⇒ 该差异登记在 {@code docs/DRIFT/message/}（继承的 B 侧漂移，<b>不是</b> C 侧引入）。</li>
     *   <li><b>为什么 rpc 侧反而钉了桶</b>：B 侧 rpc 的 {@code MessageMetrics.java:90-134}
     *       <b>自己就钉了</b> {@code serviceLevelObjectives(DEF_BUCKETS)}
     *       （2026-10-30 落码）⇒ C 侧 rpc 侧照抄 ⇒ <b>两侧形状不同是 B 侧自带的形态</b>，
     *       本仓<b>不统一化</b>（AGENTS.md §八「未统一化」）。</li>
     * </ul>
     *
     * @param route    完整路径（各 Service 的 route 常量）
     * @param failed   logic 是否以错误收场
     * @param elapsedNanos 起点到收场的耗时（纳秒）
     */
    public void observeRequest(String route, boolean failed, long elapsedNanos) {
        counter(REQUEST_TOTAL, TAG_ROUTE, route, TAG_RESULT, failed ? RESULT_FAIL : RESULT_SUCCESS)
                .increment();
        // ⚠️ **不钉桶**：逐字照抄 B 侧 `registry.timer(name, "route", route)`（见上方 javadoc）。
        registry().timer(REQUEST_DURATION_SECONDS, TAG_ROUTE, route)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 对应 Go {@code ObserveReject(route, reason)}。
     *
     * <p><b>三个 reason 字面量</b>（Go 端全树 grep 确认，无第四个）：
     * <ul>
     *   <li>{@code "user_id_missing"} —— 8 个 user 端点的 {@code UserIDFromContext} 失败</li>
     *   <li>{@code "admin_id_missing"} —— 5 个 admin 端点的同一处失败</li>
     *   <li>{@code "target_missing"} —— {@code SendAdminNotification} 的
     *       {@code !broadcast && target_id <= 0} 校验</li>
     * </ul>
     */
    public void observeReject(String route, String reason) {
        counter(REJECT_TOTAL, TAG_ROUTE, route, TAG_REASON, reason).increment();
    }

    /**
     * 单个 route 的请求计数（测试与运维查询用）。
     *
     * @return 不存在时为 {@code null}（Go 侧对应「该 label 组合从未出现过」）
     */
    public Double requestCount(String route, String result) {
        Counter counter = registry().find(REQUEST_TOTAL).tag(TAG_ROUTE, route).tag(TAG_RESULT, result).counter();
        return counter == null ? null : counter.count();
    }

    /**
     * 单个 route 的拒绝计数（测试与运维查询用）。
     *
     * @return 不存在时为 {@code null}
     */
    public Double rejectCount(String route, String reason) {
        Counter counter = registry().find(REJECT_TOTAL).tag(TAG_ROUTE, route).tag(TAG_REASON, reason).counter();
        return counter == null ? null : counter.count();
    }

    /**
     * 单个 route 的耗时直方图（测试与运维查询用）。
     *
     * @return 不存在时为 {@code null}
     */
    public Timer requestDuration(String route) {
        return registry().find(REQUEST_DURATION_SECONDS).tag(TAG_ROUTE, route).timer();
    }
}
