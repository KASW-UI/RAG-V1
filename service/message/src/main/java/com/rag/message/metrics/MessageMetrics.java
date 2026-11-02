package com.rag.message.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * <p><b>message 域指标类的共享基座</b> —— 承载「指标名前缀常量 / 标签键常量 / MeterRegistry 注册的公共方法 /
 * Go 的 {@code prometheus.DefBuckets} 桶边界」这四件<b>两个子类都要用</b>的东西。
 *
 * <p><b>本类是 C 侧新增</b>（B 侧<b>没有</b>对应的类）：B 侧把 HTTP 面与 RPC 面拆成两个进程、
 * 两个包（{@code message-api} / {@code message-rpc}），两侧各自持一份
 * {@code MeterRegistry} 与一整套指标常量；D1 + D7 把两进程合并成一个进程、
 * 一套包树之后，两侧指标常量落进<b>同一个包</b>（{@code com.rag.message.metrics}）⇒
 * ⇒ 「前缀 / 标签键 / 桶边界」这批常量<b>天然重复</b> ⇒ 由本基座收口一次。
 *
 * <p><b>⚠️ 本基座不改变任何指标名、标签或桶</b>：它只是把<b>已经存在的</b>取值集中到一处，
 * 让 {@link MessageApiMetrics} 与 {@link MessageRpcMetrics} 两个具体类<b>各管自己那 3 / 5 个指标</b>。
 * 指标<b>完整名</b>（= {@code namespace_subsystem_name}）仍是 L1 观测契约，一个字都没改
 * （AGENTS.md §八「未统一化」）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>多了一个类</b>（B 侧两侧各持一份常量，无共享基座）——
 *       属于<b>表达层</b>重组（DRY），<b>不新增、不删除、不改任何一个指标</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 子类里每个指标的<b>名字、标签键、标签取值、桶边界</b>
 *       与 B 侧逐条一致；本基座只提供<b>常量与注册手法</b>。</li>
 *   <li><b>B 出处</b>：被收口的常量分别来自
 *       {@code service/message/message-api/src/main/java/com/rag/message/message/api/metrics/MessageApiMetrics.java}
 *       ＋ {@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/metrics/MessageMetrics.java}。</li>
 * </ol>
 *
 * <p><b>⚠️ 抽象类 ⇒ 不是 Spring bean</b>：两个子类各自 {@code @Component}，
 * 本类只提供被继承的成员 ⇒ 容器里只有两个具体 bean。
 */
public abstract class MessageMetrics {

    /**
     * <b>指标名的公共前缀</b>（= B 侧 Go 端 prometheus 的 {@code Namespace} 语义）。
     *
     * <p>⚠️ <b>只用于「拼装时的可读性」</b>：两个子类的指标名常量
     * （{@code message_api_*} / {@code message_rpc_*}）<b>仍是字面量</b>，
     * 不在运行期做字符串拼接 ⇒ 指标完整名与 B 侧<b>逐字节相同</b>。
     */
    protected static final String NAMESPACE = "message";

    /** <b>标签键</b>：{@code result}（取值 {@code success} / {@code fail}）。 */
    protected static final String TAG_RESULT = "result";

    /** <b>标签键</b>：{@code route}（HTTP 侧的完整路径）。 */
    protected static final String TAG_ROUTE = "route";

    /** <b>标签键</b>：{@code reason}（HTTP 侧的拒绝原因）。 */
    protected static final String TAG_REASON = "reason";

    /** <b>标签键</b>：{@code method}（RPC 方法名）。 */
    protected static final String TAG_METHOD = "method";

    /** <b>标签键</b>：{@code kind}（通知动作的 kind）。 */
    protected static final String TAG_KIND = "kind";

    /** <b>标签键</b>：{@code action}（chat 动作 / DB 错误动作）。 */
    protected static final String TAG_ACTION = "action";

    /** <b>标签键</b>：{@code type}（DB 错误的 SQL 类型短名）。 */
    protected static final String TAG_TYPE = "type";

    /** <b>result 标签取值</b>：成功（Go 侧 {@code err == nil}）。 */
    protected static final String RESULT_SUCCESS = "success";

    /** <b>result 标签取值</b>：失败（Go 侧 {@code err != nil}）。 */
    protected static final String RESULT_FAIL = "fail";

    /**
     * 🔴 <b>Go 的 {@code prometheus.DefBuckets}（11 个边界，升序）</b>。
     *
     * <p><b>Go 出处（真源）</b>：{@code client_golang@v1.23.2/prometheus/histogram.go:271}
     * <pre>
     * var DefBuckets = []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10}
     * </pre>
     * ⇒ 换算成 {@link Duration}：5ms / 10ms / 25ms / 50ms / 100ms / 250ms / 500ms / 1s / 2.5s / 5s / 10s。
     *
     * <p><b>为什么必须逐个钉住</b>：
     * <ul>
     *   <li>Micrometer 的 {@code publishPercentileHistogram()} 用它自己的 <b>HdrHistogram 派生的 68 个桶</b>
     *       （0.001 / 0.001048576 / … / 30.0）⇒ 与 Go 的 11 个边界<b>零重合</b>
     *       ⇒ 同一段 PromQL（{@code histogram_quantile(0.95, …_bucket)}）在两侧<b>给出不同的分位数</b>
     *       ⇒ <b>不是「表达层差异」，是语义差异</b>。</li>
     *   <li>⚠️ <b>不要再加 {@code publishPercentileHistogram()}</b>：实测 Micrometer 会把 SLO 边界
     *       <b>叠加</b>到它自己的 68 个桶上（⇒ 79 个桶）⇒ 反而更不接近 Go。</li>
     *   <li>⚠️ <b>不能用 {@code publishPercentiles()}</b>：那是<b>分位数</b>开关，
     *       Prometheus 文本格式<b>不支持 quantile</b> ⇒ 对抓取端是无效配置。</li>
     * </ul>
     *
     * <p><b>为什么必须钉（SLO 直方图）而不是「不配」</b>：Go 侧是
     * {@code prometheus.NewHistogramVec(...)} ⇒ 暴露的是<b>直方图</b>
     * （{@code _bucket{le=…}} + {@code _count} + {@code _sum}）；
     * 而 Micrometer 的 Timer 在<b>不指定 SLO 边界</b>时，Prometheus registry 默认导出的是
     * <b>summary</b>（{@code _count} / {@code _sum} / {@code _max}）⇒ ⇒ <b>形状都不同</b>
     * （更别说桶边界）⇒ 抓取端的 {@code histogram_quantile} 直接失效。
     * ⇒ ⇒ Micrometer 侧的等价写法是 <b>{@code serviceLevelObjectives(…)}</b>
     * （SLO 直方图以 SLO 边界为桶边界 ⇒ 逐个等于 DefBuckets ⇒ 且导出为直方图）。
     *
     * <p><b>先例</b>：B 侧 RPC 侧同款已修（2026-10-30 落码）⇒
     * {@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/metrics/MessageMetrics.java:90-134}；
     * follow 域同款 ⇒ {@code service/follow/follow-rpc/.../metrics/FollowRpcMetrics.java:96-133}。
     */
    protected static final Duration[] DEF_BUCKETS = {
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
            Duration.ofSeconds(10)   // 10
    };

    private final MeterRegistry registry;

    protected MessageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 供子类查 meter 用的 registry（对应 B 侧两个指标类里那个同名私有字段的可见化）。
     */
    protected MeterRegistry registry() {
        return registry;
    }

    /**
     * 注册一个<b>无 description</b> 的 Counter 并 +1。
     *
     * <p>⚠️ <b>为什么单独留一条「无 description」的入口</b>：HTTP 侧（B 侧
     * {@code MessageApiMetrics}）的三个指标<b>没有</b> description ⇒ Prometheus 文本格式里
     * 它们的 {@code # HELP} 行是<b>空</b>的；RPC 侧（B 侧 {@code MessageMetrics}）的四个
     * Counter <b>有</b> description ⇒ {@code # HELP} 行有内容。
     * ⇒ ⇒ 两条入口都要在，否则 {@code # HELP} 行会变（那是<b>抓取端可见</b>的差异）。
     *
     * <p>对应 Go 的 {@code CounterVec.WithLabelValues(...).Inc()}（首次访问时注册 child）。
     */
    protected Counter counter(String name, String... tagPairs) {
        return registry.counter(name, tagPairs);
    }

    /**
     * 注册一个<b>带 description</b> 的 Counter 并 +1（RPC 侧用法，见 {@link #counter(String, String...)}）。
     */
    protected Counter describedCounter(String name, String description, String... tagPairs) {
        return Counter.builder(name)
                .description(description)
                .tags(tagPairs)
                .register(registry);
    }

    /**
     * 注册一个<b>无 description</b>、<b>桶边界 = Go {@code prometheus.DefBuckets}</b> 的 Timer。
     *
     * <p>⚠️ Micrometer 的 registry 对<b>同名同标签</b>的 meter 是<b>幂等</b>的
     * （后注册者拿到<b>先注册者</b>那个实例）⇒ ⇒ 本方法可以<b>每次调用都调</b>，
     * 不会产生第二个桶组不同的 meter。
     */
    protected Timer defBucketsTimer(String name, String... tagPairs) {
        return Timer.builder(name)
                .tags(tagPairs)
                .serviceLevelObjectives(DEF_BUCKETS)
                .register(registry);
    }

    /**
     * 注册一个<b>带 description</b>、<b>桶边界 = Go {@code prometheus.DefBuckets}</b> 的 Timer
     * （RPC 侧用法，见 {@link #counter(String, String...)}）。
     */
    protected Timer describedDefBucketsTimer(String name, String description, String... tagPairs) {
        return Timer.builder(name)
                .description(description)
                .tags(tagPairs)
                .serviceLevelObjectives(DEF_BUCKETS)
                .register(registry);
    }

    /**
     * {@code result} 标签的取值：{@code err == null} → {@code success}，否则 {@code fail}。
     *
     * <p>⚠️ 判据是「<b>方法是否以错误收场</b>」，与 Go 侧完全一致 ⇒
     * 「返回了业务失败码」也是 {@code fail}。
     */
    protected static String resultLabel(Throwable err) {
        return err == null ? RESULT_SUCCESS : RESULT_FAIL;
    }
}
