package com.rag.comment.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p><b>comment 域指标 + span 名常量</b> —— 对应 B 侧两个文件：
 * <ul>
 *   <li>{@code comment-rpc/…/rpc/metrics/CommentRpcMetrics.java}（5 个 Prometheus 向量）；</li>
 *   <li>{@code comment-rpc/…/rpc/service/CommentSpans.java}（span 名与属性名常量）。</li>
 * </ul>
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.metrics.CommentRpcMetrics}
 * ⇒ C 侧 {@code com.rag.comment.metrics.CommentRpcMetrics}。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>把 B 侧 {@code CommentSpans} 的常量并回本类</b>
 *       （映射表 {@code docs/Mapping_Table/comment/comment.md} §1.6 第 249 行：
 *       「B 侧把 span 名常量从指标类里<b>拆出</b> ⇒ C 侧并回 {@code CommentRpcMetrics}」）。
 *       ⇒ 本类同时承载「指标写入」与「span 名/属性名常量」两组内容；
 *       <b>常量值逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。5 个指标名与 label 集合、9+6 个 span 属性名逐字未改
 *       （<b>除「累计秒数」那个指标的名称外</b>，见下段）。</li>
 *   <li><b>B 出处</b>：见上两个文件。</li>
 * </ol>
 *
 * <h2>⚠️ 指标名要逐字复刻 —— 它们是<b>运维契约</b></h2>
 * <p>告警规则、看板、Grafana 面板都按
 * {@code comment_request_total{module,action,result}} 这类名字建的。
 * Micrometer 的命名规则会把下划线保留、但 Prometheus registry 会加前缀
 * （{@code prometheus.name} 属性可覆盖），因此这里显式命名。
 *
 * <h3>🔴 唯一一处<b>与 B 侧 Java 实现不同</b>的指标名：{@code comment_request_seconds_counter}</h3>
 * <p>B 侧 Java 实现用的是 {@code registry.timer("comment_request_seconds", …)}（<b>Timer</b>），
 * 而 B 侧原始 Go 侧的向量是 {@code CommentRequestSecondsCounterMetric{module,action}}，
 * 语义是「<b>累加秒数</b>的 Counter」⇒ 导出名应为 {@code comment_request_seconds_counter}。
 * <p>⇒ ⇒ <b>B 侧自身存在命名差</b>（Timer vs Counter + 名字少了 {@code _counter} 后缀）。
 * <p>⇒ ⇒ 映射表 §1.4 与 §7.4 第 9 条<b>两次</b>裁决：C 侧按
 * <b>{@code comment_request_seconds_counter}（Counter，累加秒数）</b>实现，
 * <b>不要照抄</b> B 侧的 {@code registry.timer(...)}。
 * <p>⚠️ <b>本条是 C 侧相对 B 侧 Java 实现的<b>已知差异</b></b> ⇒ 已登记在
 * {@code docs/DRIFT/comment/metrics.md}（分类：<b>继承 Go 侧口径</b>，
 * 非 C 侧自创 —— 与 {@code DEV-COMMENT-PROTO-001} 的定性同款）。
 *
 * <h2>⚠️ Go 的 result 标签集合<b>不一致</b>，必须复刻</h2>
 * <p>6 个写用例的 {@code result ∈ {ok, biz_fail, sys_fail}}，
 * 但 {@code GetComment} 只有 <b>2 个值</b>（B 侧 {@code get_comment_logic.go:35-38}：
 * 默认 "ok"，出错才 "sys_fail" —— 它<b>没有</b> biz_fail 这个分支，
 * 因为本用例从不构造业务错误）。
 * ⇒ 若把它并进同一套标签，就会凭空多出一个永不出现的值。
 * Micrometer 的标签值是自由字符串而不是固定集合，所以「只发两个值」天然成立 ——
 * 关键是<b>不要</b>在 GetComment 的失败分支里统一发 biz_fail。
 *
 * <h2>⚠️ Go 侧 module 标签也有不一致</h2>
 * <p>4 个请求类指标用 {@code module="comment_rpc"}，
 * 而列表长度 Gauge 用的是 {@code module="comment_list"}
 * （B 侧 {@code metrics.go:47} + {@code get_comment_logic.go:147}）。照搬。
 */
@Component
public class CommentRpcMetrics {

    /** B 侧 {@code metrics.go:46} 的 module 标签值。 */
    private static final String MODULE_RPC = "comment_rpc";

    /** B 侧 {@code get_comment_logic.go:147} 的 module 标签值（与上面<b>刻意不同</b>）。 */
    private static final String MODULE_LIST = "comment_list";

    /** B 侧 {@code metrics.go:34} 的 module 标签值。 */
    private static final String MODULE_REDIS = "comment_redis";

    /** B 侧 {@code metrics.go:28} 的 module 标签值。 */
    private static final String MODULE_POSTGRES = "comment_postgres";

    /** B 侧 {@code CommentSpans.SYSTEM_ERROR_CODE_FLOOR}：业务码 ≥ 5000 视为系统失败。 */
    private static final int SYSTEM_ERROR_CODE_FLOOR = 5000;

    // ==================================================================================
    // 以下为 B 侧 CommentSpans.java 并入本类后的常量（映射表 §1.6 第 249 行）
    // ==================================================================================

    /** B 侧 {@code CommentSpans.TRACER_NAME}。 */
    public static final String TRACER_NAME = "comment-rpc";

    /** B 侧 {@code CommentSpans.TRACER_NAME_LIST}。 */
    public static final String TRACER_NAME_LIST = "comment.rpc";

    /** B 侧 {@code CommentSpans.CREATE}。 */
    public static final String CREATE = "Action-Comment-Create";

    /** B 侧 {@code CommentSpans.DELETE}。 */
    public static final String DELETE = "Action-Comment-Delete";

    /** B 侧 {@code CommentSpans.MANAGE}。 */
    public static final String MANAGE = "Action-Comment-Manage";

    /** B 侧 {@code CommentSpans.REPORT}。 */
    public static final String REPORT = "Action-Comment-Report";

    /** B 侧 {@code CommentSpans.UPDATE_SUBJECT_STATE}。 */
    public static final String UPDATE_SUBJECT_STATE = "Action-Comment-UpdateSubjectState";

    /** B 侧 {@code CommentSpans.GET_COMMENT}。 */
    public static final String GET_COMMENT = "GetComment";

    // ---------- audit.* ：5 个 Action-Comment-* span 用的 9 个属性名 ----------

    /** B 侧 {@code CommentSpans.ATTR_OPERATOR_ID}。 */
    public static final String ATTR_OPERATOR_ID = "audit.operator_id";

    /** B 侧 {@code CommentSpans.ATTR_TARGET_TYPE}。 */
    public static final String ATTR_TARGET_TYPE = "audit.target_type";

    /** B 侧 {@code CommentSpans.ATTR_TARGET_ID}。 */
    public static final String ATTR_TARGET_ID = "audit.target_id";

    /** B 侧 {@code CommentSpans.ATTR_ROOT_ID}。 */
    public static final String ATTR_ROOT_ID = "audit.root_id";

    /** B 侧 {@code CommentSpans.ATTR_PARENT_ID}。 */
    public static final String ATTR_PARENT_ID = "audit.parent_id";

    /** B 侧 {@code CommentSpans.ATTR_COMMENT_ID}。 */
    public static final String ATTR_COMMENT_ID = "audit.comment_id";

    /** B 侧 {@code CommentSpans.ATTR_ACTION_TYPE}。 */
    public static final String ATTR_ACTION_TYPE = "audit.action_type";

    /** B 侧 {@code CommentSpans.ATTR_REASON}。 */
    public static final String ATTR_REASON = "audit.reason";

    /** B 侧 {@code CommentSpans.ATTR_TARGET_STATE}。 */
    public static final String ATTR_TARGET_STATE = "audit.target_state";

    // ---------- 无前缀 ：GetComment 的 6 个属性名（与上面同名但**不同 key**）----------

    /** B 侧 {@code CommentSpans.ATTR_LIST_TARGET_ID}。 */
    public static final String ATTR_LIST_TARGET_ID = "target_id";

    /** B 侧 {@code CommentSpans.ATTR_LIST_ROOT_ID}。 */
    public static final String ATTR_LIST_ROOT_ID = "root_id";

    /** B 侧 {@code CommentSpans.ATTR_LIST_PAGE}。 */
    public static final String ATTR_LIST_PAGE = "page";

    /** B 侧 {@code CommentSpans.ATTR_LIST_PAGE_SIZE}。 */
    public static final String ATTR_LIST_PAGE_SIZE = "page_size";

    /** B 侧 {@code CommentSpans.ATTR_LIST_TARGET_TYPE}。 */
    public static final String ATTR_LIST_TARGET_TYPE = "target_type";

    /** B 侧 {@code CommentSpans.ATTR_LIST_SORT_TYPE}。 */
    public static final String ATTR_LIST_SORT_TYPE = "sort_type";

    private final MeterRegistry registry;

    /** 对应 {@code CommentListSizeGaugeMetric}；Go 是 Gauge，Java 用 AtomicInteger 持有。 */
    private final AtomicInteger lastListSize = new AtomicInteger(0);

    public CommentRpcMetrics(MeterRegistry registry) {
        this.registry = registry;

        // 注册列表长度 Gauge。Micrometer 的 Gauge 需要一个「持有者」，
        // 这里把 lastListSize 作为持有对象，读数即当前值。
        Gauge.builder("comment_list_size", lastListSize, AtomicInteger::get)
                .tags(Tags.of("module", MODULE_LIST, "action", "GetComment"))
                .description("Last returned size of comment list")
                .register(registry);
    }

    /**
     * 请求计数 + 累计耗时 —— 对应 B 侧每个 logic 顶部的 {@code defer} 埋点块。
     *
     * <p>B 侧的两个向量分别是：
     * <pre>
     * CommentRequestCounterMetric{module,action,result}     Inc()
     * CommentRequestSecondsCounterMetric{module,action}     Add(duration.Seconds())
     * </pre>
     * C 侧用<b>两个 Counter</b> 表达同一个「计数 + 累计秒数」，
     * 避免两次取时钟（B 侧也是只取一次 {@code start := time.Now()}）。
     *
     * <p>⚠️ <b>第二个 Counter 的名字是 {@code comment_request_seconds_counter}</b>，
     * <b>不是</b> B 侧 Java 写的 {@code comment_request_seconds}（Timer）——
     * 见类注释「唯一一处与 B 侧 Java 实现不同的指标名」。
     *
     * @param action      RPC 名，如 {@code CreateComment}
     * @param startNanos  进入用例时 {@code System.nanoTime()} 的值
     * @param resultLabel {@code ok} / {@code biz_fail} / {@code sys_fail}
     */
    public void observeRpc(String action, long startNanos, String resultLabel) {
        long elapsed = System.nanoTime() - startNanos;

        Counter.builder("comment_request_total")
                .tags("module", MODULE_RPC, "action", action, "result", resultLabel)
                .description("Total number of comment rpc requests")
                .register(registry)
                .increment();

        Counter.builder("comment_request_seconds_counter")
                .tags("module", MODULE_RPC, "action", action)
                .description("Accumulated elapsed seconds of comment rpc requests")
                .register(registry)
                .increment(elapsed / 1_000_000_000.0d);
    }

    /**
     * 对应 B 侧 {@code CommentListSizeGaugeMetric{module="comment_list", action="GetComment"}.Set(n)}
     * （B 侧 {@code get_comment_logic.go:146-148}）。
     */
    public void setListSize(int size) {
        lastListSize.set(size);
    }

    /**
     * 对应 B 侧 {@code CommentPostgresErrorCounterMetric{module="comment_postgres", action, type}}。
     *
     * <p>⚠️ B 侧的 {@code type} 标签有 5 个取值，逐个对应到 {@code model/comment.go} 里的调用点：
     * {@code query} / {@code insert} / {@code update} / {@code upsert} / {@code lock_query}。
     * 这里不枚举，由调用点原样传入。
     */
    public void observePostgresError(String action, String type) {
        Counter.builder("comment_postgres_error_total")
                .tags("module", MODULE_POSTGRES, "action", action, "type", type)
                .description("Total number of postgres errors in comment service")
                .register(registry)
                .increment();
    }

    /**
     * 对应 B 侧 {@code CommentRedisErrorCounterMetric{module="comment_redis", action, type}}。
     *
     * <p>⚠️ <b>B 侧有一处指标名与实际错误来源不符</b>，必须照搬：
     * {@code comment_subject_cache.go:52-55} 在<b>DB 回源失败</b>时打的也是
     * {@code CommentRedisErrorCounterMetric{…, type="db_fallback"}}。
     * 看着该进 postgres 指标，实际进了 redis 指标。
     * 修正它会让「redis 错误率」的看板历史曲线断裂 ⇒ 保留。
     */
    public void observeRedisError(String action, String type) {
        Counter.builder("comment_redis_error_total")
                .tags("module", MODULE_REDIS, "action", action, "type", type)
                .description("Total number of redis errors in comment service")
                .register(registry)
                .increment();
    }

    /** 便于排障：本域已注册的 comment_* 指标名清单。 */
    public List<String> registeredMetricNames() {
        return registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .filter(n -> n.startsWith("comment"))
                .distinct()
                .sorted()
                .toList();
    }

    /** 取当前列表长度 Gauge 的读数（供测试断言）。 */
    public int currentListSize() {
        return lastListSize.get();
    }

    /**
     * 记录单次 RPC 的耗时（纳秒）—— 供需要按业务名细分耗时的地方使用。
     *
     * <p>⚠️ 本方法<b>不在用例里调用</b>：全局 RPC 耗时由共享层的
     * {@code com.rag.common.observability.RpcPrometheusInterceptor} 统一承担
     * ⇒ 避免两套口径。
     */
    public void recordLatency(String operation, long durationNanos) {
        io.micrometer.core.instrument.Timer.builder("comment_rpc_operation_latency")
                .description("comment-rpc operation latency")
                .tag("operation", operation)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    // ==================================================================================
    // 以下为 B 侧 CommentSpans.java 并入本类后的 span 机制（映射表 §1.6 第 249 行）
    // ==================================================================================

    /**
     * B 侧 {@code CommentSpans#isSystemFailure} 的判据 —— <b>业务码落在哪一段</b>。
     *
     * <p>⚠️ 判据（逐字照抄 B 侧）：
     * <ul>
     *   <li>{@code CommentExceptions.Rpc} 且码 <b>≥ 5000</b> ⇒ 系统类 ⇒ Go 会 {@code RecordError}；</li>
     *   <li>{@code CommentExceptions.Rpc} 且码 <b>&lt; 5000</b> ⇒ 业务类 ⇒ Go <b>不</b>记；</li>
     *   <li><b>不是</b> {@code Rpc} 的异常（如 {@code GetComment} 那条链上的裸
     *       {@code RuntimeException} 与三个 sentinel）⇒ 一律按系统失败处理
     *       （Go 侧那些分支都是 {@code result = "sys_fail"}）。</li>
     * </ul>
     */
    public static boolean isSystemFailure(Throwable e) {
        if (e instanceof com.rag.comment.exception.CommentExceptions.Rpc rpc) {
            return rpc.getBizCode() >= SYSTEM_ERROR_CODE_FLOOR;
        }
        return true;
    }

    /**
     * B 侧 {@code CommentSpans#traced} 的等价物 —— <b>新建</b> span 并在期间保持 current。
     *
     * <p>⚠️ <b>与 like 域的 {@code Span.current()} 写属性不同</b>：
     * B 侧 comment 域的 logic 是<b>自己开 span</b>（{@code tracer.spanBuilder(spanName).startSpan()}）
     * ⇒ 属性写在<b>自己这个 span</b> 上，不是当前上下文里别人的 span。
     * ⇒ 该差异是<b>可观察的</b>（span 的父子关系）⇒ 必须保留。
     *
     * @param tracerName  tracer 名
     * @param spanName    span 名
     * @param attributes  属性写入动作
     * @param body        业务体
     * @param <T>         返回类型
     * @return 业务体的返回值
     */
    public static <T> T traced(String tracerName, String spanName,
                                java.util.function.Consumer<io.opentelemetry.api.trace.Span> attributes,
                                java.util.function.Supplier<T> body) {
        return doTraced(tracerName, spanName, attributes, body, false);
    }

    /**
     * 同 {@link #traced}，但失败时<b>额外</b>把 span 状态置为 ERROR。
     *
     * <p>⚠️ B 侧只有 {@code GetComment} 用这个变体
     * （Go {@code get_comment_logic.go} 的 4 个错误分支<b>额外</b>显式 {@code SetStatus(Error)}）。
     */
    public static <T> T tracedWithErrorStatus(String tracerName, String spanName,
                                               java.util.function.Consumer<io.opentelemetry.api.trace.Span> attributes,
                                               java.util.function.Supplier<T> body) {
        return doTraced(tracerName, spanName, attributes, body, true);
    }

    private static <T> T doTraced(String tracerName, String spanName,
                                  java.util.function.Consumer<io.opentelemetry.api.trace.Span> attributes,
                                  java.util.function.Supplier<T> body,
                                  boolean errorStatusOnFailure) {
        io.opentelemetry.api.trace.Tracer tracer =
                io.opentelemetry.api.GlobalOpenTelemetry.getTracer(tracerName);
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder(spanName).startSpan();
        try (io.opentelemetry.context.Scope scope = span.makeCurrent()) {
            if (attributes != null) {
                attributes.accept(span);
            }
            return body.get();
        } catch (RuntimeException | Error e) {
            // 🔴 只在「系统失败」时记 —— Go 的 RecordError 精确落在 result="sys_fail" 的分支上。
            if (isSystemFailure(e)) {
                span.recordException(e);
                if (errorStatusOnFailure) {
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR,
                            e.getMessage() == null ? e.getClass().getName() : e.getMessage());
                }
            }
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Go 的 proto 枚举 {@code String()} 语义（{@code protoimpl.X.EnumStringOf}）：
     * <b>已知值 ⇒ proto 值名</b>；<b>未知值 ⇒ 该数字的字符串</b>。
     *
     * <p>B 侧的 {@code audit.action_type}（{@code in.ActionType.String()}）与
     * {@code audit.reason}（{@code in.Reason.String()}）用的就是它 ⇒
     * 「未知枚举值」在 Go 侧会写成 {@code "9"} 这样的<b>数字</b>，
     * 而 Java 侧 {@code value.name()} 会写成 {@code "UNRECOGNIZED"} ⇒ ⇒ 必须用本方法对齐。
     *
     * @param value     枚举实例（protobuf-java 的生成物）
     * @param rawNumber 该枚举的<b>原始编号</b>（用 {@code getXxxValue()} 取，<b>不要</b>用
     *                   {@code getXxx().getNumber()}：proto3 未知值在生成物里是
     *                   {@code UNRECOGNIZED}，它的 {@code getNumber()} <b>抛异常</b>）
     */
    public static String enumName(Enum<?> value, int rawNumber) {
        if (value == null) {
            return "";
        }
        if ("UNRECOGNIZED".equals(value.name())) {
            // Go 的 EnumStringOf 对未知编号返回数字字符串
            return String.valueOf(rawNumber);
        }
        return value.name();
    }
}
