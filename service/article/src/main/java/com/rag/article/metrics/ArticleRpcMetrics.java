package com.rag.article.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <p><b>职责</b>：article 域的 <b>6 个指标</b> ＋ <b>手写 span 的常量与工具</b>。
 *
 * <p><b>B 出处</b>（两个文件合并）：
 * <ul>
 *   <li>{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/metrics/ArticleRpcMetrics.java}
 *       （类声明 {@code :49-50} · {@code NAMESPACE} {@code :59} · {@code MINIO_BUCKETS} {@code :62-63} ·
 *       {@code MINIO_BUCKET_DURATIONS} {@code :70} · {@code toDurations} {@code :72-78} ·
 *       {@code registry} {@code :80} · 构造器 {@code :82-84} · {@code article} {@code :87-93} ·
 *       {@code articleStatus} {@code :100-106} · {@code fileUpload} {@code :109-115} ·
 *       {@code observeMinio} {@code :148-157} · {@code minioError} {@code :160-166} ·
 *       {@code kafkaPushError} {@code :177-183}）；</li>
 *   <li>{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/service/ArticleSpans.java}
 *       （类声明 {@code :122-123} · {@code TRACER_NAME} {@code :131} · 6 个 logic span 名 {@code :136-146} ·
 *       2 个 consumer span 名 {@code :151,154} · 2 个 destination 逻辑名 {@code :162,170} ·
 *       15 个 logic 属性名 {@code :175-203} · 12 个事件名 {@code :208-230} ·
 *       4 个 messaging.* 属性名 {@code :234-237} · {@code MESSAGING_SYSTEM_KAFKA} {@code :240} ·
 *       {@code MESSAGING_OPERATION_CONSUME} {@code :243} · {@code traced} {@code :261-272} ·
 *       {@code record} {@code :283-288} · {@code event} {@code :296-301} ·
 *       {@code consumerAttrs} {@code :313-320}）。</li>
 * </ul>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.metrics} →
 *             {@code com.rag.article.metrics}（D7 包压平）；</li>
 *         <li><b>文件合并</b>：B 侧 {@code ArticleSpans}（service 包）＋
 *             {@code ArticleRpcMetrics}（metrics 包）两个文件 ⇒ C 侧<b>并成一个</b>
 *             （映射表 §1.6 已批准的 1:1 合并：<b>只合文件、不合语义</b>）；</li>
 *         <li><b>本段 javadoc</b>补上「为什么可以并」与「B 侧 {@code ArticleSpans} 的
 *             注释域判据与 comment 域不同」两条论证（原注释随类合并一并保留）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>6 个指标的完整名 / 标签 / description / 分桶边界逐字未改</b>；</li>
 *         <li><b>span 名 / 属性名 / 事件名逐字未改</b>（含事件名里的<b>空格</b>）；</li>
 *         <li>{@code traced} / {@code record} / {@code event} / {@code consumerAttrs}
 *             四个工具方法的<b>签名与语义逐字未改</b>
 *             ⇒ 合并只是「换了个宿主类」，<b>调用点与可观察输出完全一致</b>。</li>
 *       </ul>
 *       ⇒ <b>⚠️ 注意</b>：合并后 {@code ArticleRpcMetrics} 既是 {@code @Component}
 *       （有实例状态：{@code registry}）又持有静态常量与静态工具方法 ——
 *       这在 Java 里完全合法（静态成员与实例成员共存），<b>不产生任何行为差异</b>。</li>
 *   <li><b>B 出处</b>：见上方两个文件路径 + 行号（逐成员另给行号）。</li>
 * </ol>
 *
 * <h2>🔴 L1：指标名与标签是运维可观测契约</h2>
 * <p>Prometheus 抓的是<b>全名</b>，任何差异都会让既有 dashboard / 告警<b>静默失效</b>
 * （不会报错，只会「没有数据」）。
 * <p>⚠️ <b>Go 的 {@code Namespace = "article_service"} 是指标名的<b>前缀</b></b>
 * （B 侧 {@code ArticleRpcMetrics.java:59}），而 Micrometer 不提供前缀机制 ——
 * 它按 {@code Meter.Type} 自动补后缀（Counter → {@code _total}，Timer → {@code _seconds}）：
 * {@code io.micrometer.prometheusmetrics.PrometheusNamingConvention#name(String, Meter.Type, String)}。
 * ⇒ 本类的 meter name <b>必须写掉这个后缀</b>，才能输出 Go 的全名：
 * <table border="1">
 *   <caption>Go 输出全名 ↔ Java meter name</caption>
 *   <tr><th>Go Prometheus 全名</th><th>Java meter name（本类）</th></tr>
 *   <tr><td>{@code article_service_article_total}</td><td>{@code article_service_article}</td></tr>
 *   <tr><td>{@code article_service_article_status_total}</td><td>{@code article_service_article_status}</td></tr>
 *   <tr><td>{@code article_service_file_upload_total}</td><td>{@code article_service_file_upload}</td></tr>
 *   <tr><td>{@code article_service_minio_request_errors_total}</td><td>{@code article_service_minio_request_errors}</td></tr>
 *   <tr><td>{@code article_service_kafka_push_errors_total}</td><td>{@code article_service_kafka_push_errors}</td></tr>
 *   <tr><td>{@code article_service_minio_request_duration_seconds}</td>
 *       <td>{@code article_service_minio_request_duration}（Timer 自动补 {@code _seconds}）</td></tr>
 * </table>
 * <p>⚠️ <b>本文件原空壳 javadoc 写的是「本组没有 Namespace / Subsystem ⇒ 指标名是裸名」</b>，
 * 与 B 侧源码<b>不符</b>（映射表 §7 · D-1 已把该文档缺陷登记）⇒ 本实现<b>按 B 侧源码</b>写带前缀的全名。
 *
 * <h2>⚠️ 六个指标中只有一个<b>不完整</b>，且不许"补全"</h2>
 * {@code article_status_total} 的 Go 侧<b>只有 {@code reviewing} 有调用点</b>
 * （{@code create_article_logic.go:115}），{@code published} 与 {@code rejected}
 * <b>没有任何 +1 的地方</b>。本类<b>照搬这个不完整</b>：
 * 只在 create 时记录 reviewing，发布成功与审核拒绝<b>不记录</b>。
 * 补全它们属于「新增 Go 侧不存在的埋点」—— 会让告警规则
 * （例如"published 计数应该增长"）从不触发变成触发，这是可观察的行为变化。
 *
 * <p><b>标签取值也照抄</b>：{@code action} 只有 create/update/delete
 * （Go 注释明写），不要加 publish/reject。
 *
 * <h2>🔴 与 comment 域的 {@code CommentSpans} <b>判据不同</b>，不能照抄</h2>
 * <p>comment 域用「<b>异常逃出时按业务码段判断一次</b>」，
 * 因为 comment 的 Go 侧 {@code RecordError} 精确落在 {@code result="sys_fail"} 的分支上。
 * <p><b>article 不适用</b>：article 的 Go 侧<b>连业务失败也记</b>，而且有「<b>记了但不返回</b>」：
 * <table border="1">
 *   <caption>article 侧「Go 记不记」的三个反例（comment 域的码段判据在这里全判错）</caption>
 *   <tr><th>位置</th><th>Go 的行为</th><th>comment 域码段判据会得出</th></tr>
 *   <tr><td>{@code update_article_logic.go:61}</td>
 *       <td>NotFound ⇒ <b>RecordError</b>（业务失败也记）</td><td>「业务码 ⇒ 不记」⇒ <b>错</b></td></tr>
 *   <tr><td>{@code delete_article_logic.go:55 / :60}</td>
 *       <td>NotFound / PermissionDenied ⇒ <b>RecordError</b></td><td>同上 ⇒ <b>错</b></td></tr>
 *   <tr><td>{@code get_article_logic.go:73-75}</td>
 *       <td>IncrViewCount 失败 ⇒ <b>RecordError 但继续往下走</b></td>
 *       <td>「异常逃出时才记」⇒ 根本不会触发 ⇒ <b>错</b></td></tr>
 * </table>
 * ⇒ ⇒ 本类采用<b>「显式逐点记录」</b>：把 live {@link Span} 交回业务方法，
 * 在 Go 的<b>每一个</b> {@code RecordError} / {@code AddEvent} / {@code SetAttributes} 点显式调用。
 * ⇒ ⇒ 代价是「Go 记了但不返回」这种形状<b>天然可表达</b>（catch 里只记、不抛）。
 *
 * <h2>🔴 {@code RecordError} 的等价物 = {@code recordException}，<b>且都不改状态</b></h2>
 * <p>Go 侧 otel-go <b>v1.44.0</b>（真源 {@code go.mod:18-21}）的
 * {@code sdk/trace/span.go:631-655}：
 * <pre>
 * func (s *recordingSpan) RecordError(err error, opts ...trace.EventOption) {
 *     …
 *     opts = append(opts, trace.WithAttributes(
 *         semconv.ExceptionType(typeStr(err)),
 *         semconv.ExceptionMessage(err.Error()),
 *     ))
 *     …
 *     s.addEvent(semconv.ExceptionEventName, opts...)   // ← 只加事件，**不** SetStatus
 * }
 * </pre>
 * ⇒ 它<b>只</b>加一个名为 {@code exception} 的事件（带 {@code exception.type} /
 * {@code exception.message} / {@code exception.stacktrace}），
 * <b>不</b>把 span 状态改成 ERROR。
 * <p>Java 侧 {@code Span#recordException(Throwable)} <b>同样</b>只加 {@code exception} 事件、
 * <b>同样</b>不改状态 ⇒ ⇒ <b>等价</b>。
 * <p>⚠️ article 的 6 个 logic <b>没有任何一处</b>显式 {@code span.SetStatus(...)}
 * （实测 {@code SetStatus} 在 6 个 Go logic 里命中数 = 0）
 * ⇒ ⇒ <b>失败时 span 状态保持 UNSET</b> ⇒ 本类<b>不得</b>调 {@code setStatus}。
 * ⇒ ⇒ 与 comment 域的 {@code tracedWithErrorStatus} <b>不是</b>同一种形状。
 *
 * <h2>🔴 {@code messaging.destination} 是「逻辑名」，<b>不是</b> kafka topic</h2>
 * <p>Go 侧 kafka topic 其实是 {@code article-creation-topic}（真源 {@code etc/article.yaml:49}），
 * 而 {@code articleMessageAttrs} 的第一个参数传的是 {@code "article_review"} / {@code "article_sync_result"}
 * —— 那是<b>逻辑名</b> ⇒ ⇒ <b>属性值不能取 {@code record.topic()}</b>（那会变成 {@code article-creation-topic}）
 * ⇒ ⇒ 与真源不一致。
 * <p>⚠️ <b>别照抄 hot-rpc 的写法</b>：{@code HotEventConsumer} 用的是 {@code record.topic()}
 * ⇒ 那是<b>另一个域</b>的形状 ⇒ 本域必须用逻辑名。
 *
 * <h2>🔴 子 span 的父子关系与 Go 同构</h2>
 * <p>本类用 {@link GlobalOpenTelemetry#getTracer(String)}，与共享层用的是<b>同一个 API</b>
 * （Go 侧也是 {@code otel.Tracer(...)}）⇒ <b>article 域模块内即可完成</b>，
 * 不需要改共享模块，也不需要改 pom。
 * <p>⇒ {@code spanBuilder(...).startSpan()}（隐式取 {@code Context.current()}）自动挂到
 * 共享层 {@code RpcInterceptor} 的 SERVER span 之下 ⇒
 * ⇔ Go 侧 {@code tracer.Start(l.ctx, …)} 用的 {@code l.ctx} 也带着 SERVER span ⇒ <b>同构</b>。
 *
 * <h2>L4 idiomatization（span 工具的三段式 · AGENTS.md §3.2）</h2>
 * <p><b>比 Go 多做了什么</b>：① Go 用 {@code ctx} 显式传递；Java 没有 ctx 参数这一说
 * ⇒ 用 {@link Scope} 模拟「span 进入当前上下文、退出时还原」，
 * 并用 {@link Function} 代替 Go 的「后续代码内联在同一个函数里」；
 * ② Go 的 {@code span.RecordError(err)} 是<b>逐点</b>调用；Java 的 {@code recordException} 也是逐点，
 * 但 Java 没有 Go 那种「把 err 变量一路带着走」的写法 ⇒ 本类用<b>显式的 {@link #record} 调用点</b>表达。
 * <p><b>语义等价论证</b>：Go 的 {@code defer span.End()} ⇔ 本类的 {@code finally}；
 * Go 的 {@code span.RecordError(err)} ⇔ 本类的 {@code span.recordException(e)}（见上：两者都只加事件、都不改状态）；
 * Go 的 {@code span.AddEvent("…")} ⇔ 本类的 {@code span.addEvent("…")}（<b>事件名逐字保留，含空格</b>）；
 * Go 的 {@code trace.WithAttributes(...)}（起始属性）⇔ 本类在 {@code startSpan()} 之后立刻
 * {@code setAttribute}（<b>导出结果相同</b>：OTel 不区分「起始属性」与「后续属性」）；
 * Go 的 {@code span.SetAttributes(...)}（后续属性）⇔ 本类的 {@code span.setAttribute(...)}。
 */
@Component
public class ArticleRpcMetrics {

    // ==================================================================
    // 一、指标（6 个 · B 侧 ArticleRpcMetrics.java :49-183）
    // ==================================================================

    /**
     * 对应 Go {@code prometheus.Namespace = "article_service"}（B 侧 {@code metrics.go:6}）。
     *
     * <p>⚠️ 它是<b>已压入各 meter name 的前缀</b>（见类级说明的对照表），
     * 而<b>不是一个运行时可用的配置项</b>—— Micrometer 没有"指标名前缀"这个概念。
     * 它保留在这里只为了让上表的前缀有一个可引用的单一源。
     */
    public static final String NAMESPACE = "article_service";

    /** 对应 Go {@code MinioRequestDuration} 的 buckets（B 侧 {@code metrics.go:46}），单位秒。 */
    private static final double[] MINIO_BUCKETS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1};

    /**
     * 同一组 buckets 的 {@link Duration} 形式 —— Micrometer 的 API 只收 Duration。
     *
     * <p>由 {@link #MINIO_BUCKETS} 换算而来，不独立手写，避免两处数值漂移。
     */
    private static final java.time.Duration[] MINIO_BUCKET_DURATIONS = toDurations(MINIO_BUCKETS);

    private static java.time.Duration[] toDurations(double[] seconds) {
        java.time.Duration[] result = new java.time.Duration[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            result[i] = java.time.Duration.ofNanos((long) (seconds[i] * 1_000_000_000L));
        }
        return result;
    }

    private final MeterRegistry registry;

    public ArticleRpcMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 对应 Go {@code ArticleTotal.WithLabelValues("create"|"update"|"delete")}。 */
    public void article(String action) {
        Counter.builder("article_service_article")
                .description("Total number of articles processed")
                .tag("action", action)
                .register(registry)
                .increment();
    }

    /**
     * 对应 Go {@code ArticleStatusTotal.WithLabelValues("reviewing")}。
     *
     * <p>⚠️ <b>全模块唯一调用点是 create</b>；见类级说明，不要补 published/rejected。
     */
    public void articleStatus(String status) {
        Counter.builder("article_service_article_status")
                .description("Total number of article status changes")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    /** 对应 Go {@code FileUploadTotal.WithLabelValues("image"|"markdown")}。 */
    public void fileUpload(String type) {
        Counter.builder("article_service_file_upload")
                .description("Total number of files uploaded")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次 MinIO 操作耗时 —— 对应 Go 的
     * {@code prometheus.NewTimer(metrics.MinioRequestDuration.WithLabelValues(op))} + {@code ObserveDuration()}。
     *
     * <p>用法：{@code metrics.observeMinio("put", elapsedNanos)}。
     * Go 的 Timer 语义是"从 NewTimer 到 ObserveDuration 的间隔"，
     * Java 侧由调用方测量区间后传入，两者是同一个量。
     *
     * <h2>🔴 分桶边界由 {@link #MINIO_BUCKETS} 决定，且<b>绝不能</b>加 {@code publishPercentileHistogram()}</h2>
     * Go 侧是 {@code Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1}}（秒）
     * （B 侧 {@code metrics.go:46}）；Micrometer 的 {@code serviceLevelObjectives} 收 {@link Duration}
     * （不是 double 秒），换算后<b>数值一致</b> ⇒ 两侧的分桶边界相同，
     * 这让 {@code histogram_quantile} 的告警阈值在迁移前后不变。
     *
     * <h3>⚠️ 为什么不能同时写 {@code publishPercentileHistogram()}</h3>
     * Micrometer 1.16.7 的 {@code DistributionStatisticConfig.getHistogramBuckets(supportsAggregablePercentiles)}
     * 取的是<b>并集</b>，不是"SLO 优先"：
     * <pre>
     * if (percentileHistogram != null &amp;&amp; percentileHistogram &amp;&amp; supportsAggregablePercentiles) {
     *     buckets.addAll(PercentileHistogramBuckets.buckets(this));   // ← 额外塞进一整套默认百分位桶
     *     buckets.add(minimumExpectedValue);                          // → 0.001
     *     buckets.add(maximumExpectedValue);                          // → 30.0
     * }
     * if (serviceLevelObjectives != null) { for (...) buckets.add(sloBoundary); }  // ← 只是"再加"
     * </pre>
     * ⇒ 实测（真 {@code PrometheusMeterRegistry} 抓取，2026-10-24）：
     * 桶边界从 Go 的 <b>9</b> 个（8 个 + Prometheus 固有的 {@code +Inf}）
     * 涨到 <b>77</b> 个 ⇒ {@code histogram_quantile} 的结果与迁移前不同，
     * 且这条指标的 series 基数翻了几倍。
     * ⇒ 锁死用例：{@code ArticleSemanticsRegressionTest#minioHistogramBucketsMatchGoExactly}。
     */
    public void observeMinio(String operation, long durationNanos) {
        Timer.builder("article_service_minio_request_duration")
                .description("Latency of MinIO requests.")
                .tag("operation", operation)
                // ⚠️ 不要加 .publishPercentileHistogram()：它与 serviceLevelObjectives 取**并集**
                //    ⇒ 实际桶边界变成 77 个，与 Go 的 8 个不一致（见上方 javadoc）。
                .serviceLevelObjectives(MINIO_BUCKET_DURATIONS)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /** 对应 Go {@code MinioRequestErrors.WithLabelValues(op).Inc()}。 */
    public void minioError(String operation) {
        Counter.builder("article_service_minio_request_errors")
                .description("Total number of errors for MinIO requests")
                .tag("operation", operation)
                .register(registry)
                .increment();
    }

    /**
     * 对应 Go {@code KafkaPushErrors.WithLabelValues(topic).Inc()}。
     *
     * <p>⚠️ <b>标签名是 {@code topic} 但传的其实是"用途名"</b>：
     * Go 侧的实际取值是 {@code "article_review"}（create/update 入队审核）
     * 与 {@code "article_sync_event"}（outbox relay），
     * <b>不是</b> Kafka 的 topic 字符串。这是一处命名与取值不符，
     * 但它决定了历史告警规则能否继续命中 ⇒ 照搬取值，不"修正"成真 topic。
     */
    public void kafkaPushError(String topic) {
        Counter.builder("article_service_kafka_push_errors")
                .description("Total number of errors for Kafka push")
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    // ==================================================================
    // 二、span 常量与工具（B 侧 ArticleSpans.java :122-321）
    // ==================================================================

    /**
     * Go 出处：6 个 logic 与 2 个 consumer <b>全部</b>写死 {@code otel.Tracer("article-rpc")}
     * （{@code create_article_logic.go:43} · {@code get_article_logic.go:47} ·
     * {@code update_article_logic.go:47} · {@code delete_article_logic.go:41} ·
     * {@code list_articles_logic.go:31} · {@code upload_file_logic.go:41} ·
     * {@code article_consumer.go:45} · {@code article_sync_result_consumer.go:40}）。
     */
    public static final String TRACER_NAME = "article-rpc";

    // ---------- 6 个 logic span 名（Go：⚠️ **无域前缀**，逐字保留）----------

    /** Go 出处：{@code create_article_logic.go:44}。 */
    public static final String CREATE_ARTICLE = "CreateArticle";
    /** Go 出处：{@code get_article_logic.go:48}。 */
    public static final String GET_ARTICLE = "GetArticle";
    /** Go 出处：{@code update_article_logic.go:48}。 */
    public static final String UPDATE_ARTICLE = "UpdateArticle";
    /** Go 出处：{@code delete_article_logic.go:42}。 */
    public static final String DELETE_ARTICLE = "DeleteArticle";
    /** Go 出处：{@code list_articles_logic.go:32}。 */
    public static final String LIST_ARTICLES = "ListArticles";
    /** Go 出处：{@code upload_file_logic.go:42}。 */
    public static final String UPLOAD_FILE = "UploadFile";

    // ---------- 2 个 consumer span 名（Go：⚠️ **有类名前缀**，与 logic span 不同）----------

    /** Go 出处：{@code article_consumer.go:45} 的 span 名。 */
    public static final String CONSUMER_REVIEW = "ArticleConsumer.Consume";

    /** Go 出处：{@code article_sync_result_consumer.go:40} 的 span 名。 */
    public static final String CONSUMER_SYNC_RESULT = "ArticleSyncResultConsumer.Consume";

    /**
     * Go 出处：{@code article_consumer.go:45} 传的 <b>逻辑名</b>（{@code "article_review"}）。
     *
     * <p>⚠️ <b>不是 kafka topic</b>（真源 {@code etc/article.yaml:49} 的 topic 是
     * {@code article-creation-topic}）⇒ ⇒ 不可用 {@code record.topic()} 代替。
     */
    public static final String DESTINATION_REVIEW = "article_review";

    /**
     * Go 出处：{@code article_sync_result_consumer.go:40} 传的 <b>逻辑名</b>
     * （{@code "article_sync_result"}）。
     *
     * <p>⚠️ 同上，<b>不是</b> kafka topic {@code article-sync-results}。
     */
    public static final String DESTINATION_SYNC_RESULT = "article_sync_result";

    // ---------- 15 个 logic 属性名（Go：⚠️ **裸名**，无前缀）----------

    /** Go 出处：{@code create_article_logic.go:45}（String）。 */
    public static final String ATTR_AUTHOR_ID = "author_id";
    /** Go 出处：{@code create_article_logic.go:46}（String）。 */
    public static final String ATTR_TITLE = "title";
    /** Go 出处：{@code create_article_logic.go:57}（String，<b>后续</b>属性）。 */
    public static final String ATTR_ARTICLE_ID = "article_id";
    /** Go 出处：{@code get_article_logic.go:57 / :65}（<b>Bool</b>）。 */
    public static final String ATTR_ARTICLE_FOUND = "article_found";
    /** Go 出处：{@code list_articles_logic.go:33}（<b>Int64</b>）。 */
    public static final String ATTR_PAGE = "page";
    /** Go 出处：{@code list_articles_logic.go:34}（<b>Int64</b>）。 */
    public static final String ATTR_PAGE_SIZE = "page_size";
    /** Go 出处：{@code list_articles_logic.go:35}（String）。 */
    public static final String ATTR_SORT_BY = "sort_by";
    /** Go 出处：{@code list_articles_logic.go:36}（<b>Bool</b>）。 */
    public static final String ATTR_DESC = "desc";
    /** Go 出处：{@code list_articles_logic.go:51}（String，<b>条件设置</b>）。 */
    public static final String ATTR_MANUAL_TYPE_TAG = "manual_type_tag";
    /** Go 出处：{@code list_articles_logic.go:55}（String，<b>条件设置</b>）。 */
    public static final String ATTR_SECONDARY_TAG = "secondary_tag";
    /** Go 出处：{@code list_articles_logic.go:71}（<b>Int64</b>，<b>后续</b>属性）。 */
    public static final String ATTR_TOTAL_ARTICLES = "total_articles";
    /** Go 出处：{@code upload_file_logic.go:43}（String）。 */
    public static final String ATTR_FILE_NAME = "file_name";
    /** Go 出处：{@code upload_file_logic.go:53}（<b>Int64</b>，<b>后续</b>属性）。 */
    public static final String ATTR_FILE_ID = "file_id";
    /** Go 出处：{@code upload_file_logic.go:62}（String，<b>后续</b>属性）。 */
    public static final String ATTR_CONTENT_TYPE = "content_type";
    /** Go 出处：{@code upload_file_logic.go:62}（String，<b>后续</b>属性）。 */
    public static final String ATTR_OBJECT_NAME = "object_name";

    // ---------- 12 个事件名（Go：⚠️ **带空格**，逐字保留）----------

    /** Go 出处：{@code get_article_logic.go:53}。 */
    public static final String EV_START_FIND_ARTICLE = "start find article";
    /** Go 出处：{@code get_article_logic.go:64}。 */
    public static final String EV_FIND_ARTICLE_SUCCESS = "find article success";
    /** Go 出处：{@code get_article_logic.go:71}。 */
    public static final String EV_START_INCR_VIEW_COUNT = "start incr view count";
    /** Go 出处：{@code get_article_logic.go:77}。 */
    public static final String EV_INCR_VIEW_COUNT_SUCCESS = "incr view count success";
    /** Go 出处：{@code get_article_logic.go:82}。 */
    public static final String EV_START_GET_MINIO_OBJECT = "start get minio object";
    /** Go 出处：{@code get_article_logic.go:93}。 */
    public static final String EV_GET_MINIO_OBJECT_SUCCESS = "get minio object success";
    /** Go 出处：{@code get_article_logic.go:95}。 */
    public static final String EV_START_READ_MINIO_OBJECT = "start read minio object";
    /** Go 出处：{@code get_article_logic.go:102}。 */
    public static final String EV_READ_MINIO_OBJECT_SUCCESS = "read minio object success";
    /** Go 出处：{@code list_articles_logic.go:62}。 */
    public static final String EV_START_DB_LIST = "start db list";
    /** Go 出处：{@code list_articles_logic.go:70}。 */
    public static final String EV_DB_LIST_SUCCESS = "db list success";
    /** Go 出处：{@code upload_file_logic.go:66}。 */
    public static final String EV_START_UPLOAD_TO_MINIO = "start upload to minio";
    /** Go 出处：{@code upload_file_logic.go:82}。 */
    public static final String EV_UPLOAD_TO_MINIO_SUCCESS = "upload to minio success";

    // ---------- 4 个 messaging.* 属性名（Go: article_consumer.go:146-149）----------

    public static final String ATTR_MESSAGING_SYSTEM = "messaging.system";
    public static final String ATTR_MESSAGING_DESTINATION = "messaging.destination";
    public static final String ATTR_MESSAGING_OPERATION = "messaging.operation";
    public static final String ATTR_MESSAGING_MESSAGE_KEY = "messaging.message.key";

    /** Go 出处：{@code article_consumer.go:146} 写死 {@code "kafka"}。 */
    public static final String MESSAGING_SYSTEM_KAFKA = "kafka";

    /** Go 出处：{@code article_consumer.go:148} 写死 {@code "consume"}。 */
    public static final String MESSAGING_OPERATION_CONSUME = "consume";

    /**
     * 在一个<b>手写 logic span</b> 里执行 {@code body}。
     *
     * <p><b>失败时<b>不</b>改 span 状态</b>（Go 的 6 个 logic 里 {@code SetStatus} 命中数 = 0 ⇒ 状态恒 UNSET）。
     * <p><b>失败时也<b>不</b>自动 {@code recordException}</b> ⇒ 记录点由业务方法<b>显式</b>调用
     * {@link #record(Span, Throwable)} 决定（Go 的 {@code RecordError} 是逐点的，见类级说明）。
     *
     * @param spanName   6 个 logic span 名之一（{@link #CREATE_ARTICLE} 等）
     * @param attributes <b>起始属性</b>（对应 Go 的 {@code trace.WithAttributes(...)}）；
     *                   Go 在 {@code Start} 之后设置的属性用 {@code span.setAttribute(...)} 逐点调用
     * @param body       业务体；拿到的 {@link Span} 就是「live span」，供逐点记录
     */
    public static <T> T traced(String spanName, Consumer<Span> attributes, Function<Span, T> body) {
        Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            if (attributes != null) {
                attributes.accept(span);
            }
            return body.apply(span);
        } finally {
            span.end();
        }
    }

    /**
     * 等价于 Go 的 {@code span.RecordError(err)}。
     *
     * <p>🔴 <b>只加事件、不改状态</b> —— 与 otel-go v1.44.0
     * {@code sdk/trace/span.go:631-655} 一致（见类级说明）。
     *
     * @param span live span；{@code null} 时静默跳过（便于「可选 span」的调用点）
     * @param err  要记的错误；{@code null} 时静默跳过（Go 侧 {@code RecordError(nil)} 也是 no-op）
     */
    public static void record(Span span, Throwable err) {
        if (span == null || err == null) {
            return;
        }
        span.recordException(err);
    }

    /**
     * 等价于 Go 的 {@code span.AddEvent(name)} —— <b>事件名逐字保留（含空格）</b>。
     *
     * @param span live span；{@code null} 时静默跳过
     * @param name 事件名（如 {@code "start find article"}）
     */
    public static void event(Span span, String name) {
        if (span == null || name == null) {
            return;
        }
        span.addEvent(name);
    }

    /**
     * Go 侧 {@code articleMessageAttrs(topic, key)} 的等价物（{@code article_consumer.go:144-150}）。
     *
     * <p>⚠️ <b>4 个属性，不是 5 个</b>：Go 侧<b>没有</b> {@code messaging.kafka.partition}
     * （{@code HotEventConsumer} 有那一个 ⇒ 那是别的域的形状 ⇒ <b>不可照抄</b>）。
     *
     * @param destination <b>逻辑名</b>（{@link #DESTINATION_REVIEW} 或 {@link #DESTINATION_SYNC_RESULT}），
     *                     <b>不是</b> kafka topic
     * @param key        kafka 消息 key；Go 侧是 {@code string}（不会为 nil）⇒ ⇒ 这里 null 归一成空串
     */
    public static Map<String, Object> consumerAttrs(String destination, String key) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_MESSAGING_SYSTEM, MESSAGING_SYSTEM_KAFKA);
        attrs.put(ATTR_MESSAGING_DESTINATION, destination);
        attrs.put(ATTR_MESSAGING_OPERATION, MESSAGING_OPERATION_CONSUME);
        attrs.put(ATTR_MESSAGING_MESSAGE_KEY, key == null ? "" : key);
        return attrs;
    }
}
