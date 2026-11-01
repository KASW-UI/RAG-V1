package com.rag.common.communityfact;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对应 B 侧版 service/common/communityfact/logging.go 的进程日志 + OTEL 本地追踪启动。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link ProcessMetadata}：进程元数据 + 校验</li>
 *   <li>{@link #newProcessLogger(OutputStream, ProcessMetadata)}：按 B 侧版 OBS-r3 envelope 输出 JSON，
 *       metadata 通过自定义 logback appender 在 logger 实例上绑定（不依赖 MDC，避免跨线程插值）</li>
 *   <li>{@link #installLocalTracing()}：本地 OpenTelemetry（AlwaysSample + W3C trace context + baggage）</li>
 * </ul>
 *
 * <p><b>与 B 侧版的差异（已评估）</b>：
 * <ul>
 *   <li>B 侧版用 {@code log/slog}；Java 版用 logback + Jackson 自定义 appender。</li>
 *   <li>B 侧版通过 {@code base.WithAttrs([]slog.Attr)} 把 metadata 绑到 logger 实例；
 *       Java 版用 {@link ProcessLogAppender} 实例字段持有 metadata（每个 logger 一份 appender），
 *       <b>不依赖 SLF4J MDC</b>（MDC 是 thread-local，会跨线程插值，破坏跨线程一致性）。</li>
 *   <li>B 侧版 {@code slog.ReplaceAttr} 重命名 time/level/msg；Java 版在 encoder 阶段做同样重命名。</li>
 *   <li>Java 端没有 {@code logx} 等价物；{@code InstallFrameworkLogger} 在 Java 版不需要，
 *       删除对应方法，避免为了模仿而再造一层。</li>
 * </ul>
 */
public final class ProcessLogger {

    /** 严格保证字段顺序，便于测试断言。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * M18 修复：时间戳格式对齐 B 侧版 {@code slog.ReplaceAttr} 的输出。
     *
     * <p>B 侧 {@code service/common/src/main/java/com/rag/common/communityfact/ProcessLogger.java:61:53} 用的是：
     * <pre>
     * case slog.TimeKey:
     *     return slog.String("timestamp", attr.Value.Time().UTC().Format("2006-01-02T15:04:05.999999999Z07:00"))
     * </pre>
     * B 侧 布局里的 <b>9 个 {@code 9} 表示「小数部分按需输出、尾零省略」</b> ——
     * 纳秒为 0 时完全不出现小数点，纳秒尾零也被裁掉。
     *
     * <p>旧 Java 版用 {@code "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'"}：
     * <ul>
     *   <li>永远输出 9 位小数、尾零不裁 → {@code .123000000} 而 B 侧 是 {@code .123}</li>
     *   <li>而 {@code event.getTimeStamp()} 只有<b>毫秒</b>精度，后 6 位恒为 0，
     *       于是每条日志都带一串假的 {@code 000000}</li>
     * </ul>
     * 下游若按字符串比对时间戳，两边永远对不上。
     */
    private static final DateTimeFormatter BASE_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * M18：按 B 的 {@code .999999999} 裁剪语义格式化时间戳。
     *
     * @param epochMillis 毫秒时间戳（logback {@code ILoggingEvent#getTimeStamp()} 的精度）
     */
    static String formatTimestamp(long epochMillis) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        String base = BASE_TS_FMT.format(zdt);
        int millis = zdt.getNano() / 1_000_000;
        if (millis == 0) {
            // B 侧: .999999999 在全 0 时输出不带小数点的形式
            return base + "Z";
        }
        StringBuilder frac = new StringBuilder(String.format("%03d", millis));
        while (frac.length() > 0 && frac.charAt(frac.length() - 1) == '0') {
            frac.setLength(frac.length() - 1);
        }
        return base + "." + frac + "Z";
    }

    /**
     * 对应 B 侧版 {@code func NewProcessLogger(output io.Writer, meta ProcessMetadata) *slog.Logger}。
     *
     * <p>返回一个 SLF4J {@link Logger}，底层 logback 配置把 JSON 写到 {@code output}，
     * metadata 通过 {@link ProcessLogAppender} 实例字段注入（不依赖 MDC）。
     *
     * <p>logger 名唯一化（按 service + environment + component 拼），避免多实例互窜。
     */
    public static Logger newProcessLogger(OutputStream output, ProcessMetadata meta) {
        meta.validate();

        ch.qos.logback.classic.LoggerContext lc =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        String loggerName = "communityfact.process."
                + meta.resolved(meta.service) + "."
                + meta.resolved(meta.environment) + "."
                + meta.resolved(meta.component);
        ch.qos.logback.classic.Logger logger = lc.getLogger(loggerName);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.setAdditive(false);
        logger.detachAndStopAllAppenders();

        ProcessLogAppender appender = new ProcessLogAppender(output, meta);
        appender.setContext(lc);
        appender.start();
        logger.addAppender(appender);
        return logger;
    }

    /**
     * 对应 B 侧版 {@code func InstallLocalTracing() *sdktrace.TracerProvider}。
     */
    public static SdkTracerProvider installLocalTracing() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .build();
        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())))
                .buildAndRegisterGlobal();
        return tracerProvider;
    }

    /**
     * 把当前 OpenTelemetry 上下文的 trace_id/span_id 注入业务 envelope。
     * 对应 B 侧版 {@code traceLogHandler.Handle}：record.AddAttrs(slog.String("trace_id", ...), ...)。
     *
     * <p><b>M19 修复</b>：B 侧版 {@code Handle(ctx, record)} 的 ctx 是<b>由调用方显式传入</b>的，
     * 所以跨线程打日志照样能带上正确的 trace。Java 的 logback appender 拿不到 ctx 参数，
     * 只能读线程局部 {@code Context.current()} —— 一旦业务把日志丢进别的线程（或用异步 appender），
     * trace 关联就静默丢失。
     *
     * <p>现补一条 MDC 兜底：先读 {@code Context.current()}，无效时读 MDC 里的
     * {@code trace_id} / {@code span_id} / {@code trace_sampled}。
     * 业务只要在切线程前把这三个键塞进 MDC（{@code Logger} 已经会自动写 {@code trace_id}），
     * 跨线程日志就能重新带上 trace 关联。
     *
     * <p>本方法由 {@link ProcessLogAppender} 内部调用，业务层不需要直接调。
     */
    private static Map<String, Object> traceContextAttrs() {
        Span span = Span.fromContext(io.opentelemetry.context.Context.current());
        if (span.getSpanContext().isValid()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trace_id", span.getSpanContext().getTraceId());
            m.put("span_id", span.getSpanContext().getSpanId());
            m.put("trace_sampled", span.getSpanContext().isSampled());
            return m;
        }
        Map<String, Object> fromMdc = new LinkedHashMap<>();
        String traceId = MDC.get("trace_id");
        if (traceId != null && !traceId.isEmpty() && !"unknown".equals(traceId)) {
            fromMdc.put("trace_id", traceId);
        }
        String spanId = MDC.get("span_id");
        if (spanId != null && !spanId.isEmpty()) {
            fromMdc.put("span_id", spanId);
        }
        String sampled = MDC.get("trace_sampled");
        if (sampled != null && !sampled.isEmpty()) {
            fromMdc.put("trace_sampled", Boolean.parseBoolean(sampled));
        }
        return fromMdc;
    }

    /**
     * 自定义 logback appender：把 metadata 注入输出 JSON，不依赖 MDC。
     *
     * <p>对应 B 侧版 {@code NewProcessLogger} 的 base.WithAttrs 注入字段 + traceLogHandler 注入 trace 字段。
     *
 * <p><b>⚠️ 拆出后的落点差异</b>：RAG 侧的 {@code ProcessMetadata} 是本类的<b>嵌套</b>类型
 * ⇒ ⇒ RAG-V1 拆成顶层类 {@code ProcessMetadata}（映射表 §1.2 第 147 行 / 决策锁 L-7 第 7 条）
 * ⇒ ⇒ 调用点由 {@code ProcessLogger.ProcessMetadata} 改为 {@code ProcessMetadata} ⇒ **import 要同步改**。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/ProcessLogger.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类 Jackson 写法在 RAG 侧<b>本就是 Jackson 2</b> ⇒ 零回改）。
 */
    private static final class ProcessLogAppender extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {
        private final OutputStream output;
        private final ProcessMetadata meta;

        ProcessLogAppender(OutputStream output, ProcessMetadata meta) {
            this.output = output;
            this.meta = meta;
        }

        @Override
        protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            Map<String, Object> json = new LinkedHashMap<>();
            // base attrs（slog base.WithAttrs 等价）
            json.put("service", meta.resolved(meta.service));
            json.put("environment", meta.resolved(meta.environment));
            json.put("service_version", meta.resolved(meta.version));
            json.put("instance_id", meta.resolved(meta.instanceId));
            json.put("component", meta.resolved(meta.component));
            json.put("log_source", "application");
            // 字段重命名（slog ReplaceAttr 等价）
            json.put("timestamp", formatTimestamp(event.getTimeStamp()));
            json.put("level", event.getLevel().toString().toLowerCase());
            json.put("message", event.getFormattedMessage());
            // trace 字段（traceLogHandler 等价）
            json.putAll(traceContextAttrs());
            // 业务 SLF4J fluent API 携带的 KV（addKeyValue 等价）
            // M17 修复：保留值本身的类型。
            // B 侧 slog 的 Attr 是带类型的（BoolAttr / Int64Attr / Float64Attr），JSON 输出保留 JSON 类型；
            // 旧 Java 版一律 kv.value.toString()，把 bool / number 全变成字符串：
            //   B 侧: {"sent":true}          Java（旧）: {"sent":"true"}
            //   B 侧: {"duration_ms":1.5}    Java（旧）: {"duration_ms":"1.5"}
            // 这直接破坏了 OBS-r3 信封的 JSON schema —— 下游按 boolean / number 解析会拿到 string。
            // Jackson 的 Map<String,Object> 序列化会按值的实际类型写 JSON，
            // 因此这里直接 put 原始对象即可，无需手工转换。
            event.getKeyValuePairs().forEach(kv -> {
                if (kv.key != null && kv.value != null) json.put(kv.key, kv.value);
            });
            try (JsonGenerator gen = MAPPER.getFactory().createGenerator(output)) {
                MAPPER.writeValue(gen, json);
                gen.flush();
                output.write('\n');
                output.flush();
            } catch (IOException e) {
                throw new IllegalStateException("community process logger write failed", e);
            }
        }
    }

    private ProcessLogger() {
    }
}