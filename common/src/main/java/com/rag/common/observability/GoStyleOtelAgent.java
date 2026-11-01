package com.rag.common.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🔴🔴 **全局 OpenTelemetry 装配** —— 对应 Go 侧
 * {@code go-zero@v1.10.2/core/trace/agent.go} 的 {@code startAgent} +
 * {@code core/trace/propagation.go} 的 {@code init()} +
 * {@code core/service/serviceconf.go} 的 {@code ServiceConf.SetUp()}。
 *
 * <h2>🔴 为什么必须有本类：RAG-V1 侧的 trace 链路<b>整体缺失</b></h2>
 * <p>Go 侧每个服务启动时（{@code rest/server.go:49} → {@code c.SetUp()} →
 * {@code serviceconf.go:67} → {@code trace.StartAgent(sc.Telemetry)}）
 * 都会装一个<b>全局</b> {@code TracerProvider} ⇒ {@code rest} 的
 * {@code TraceHandler}（{@code rest/handler/tracehandler.go:32-64}）建出来的 span
 * <b>真的被导出</b>到 jaeger ⇒ 线上排障时 jaeger 里看得到 trace。
 *
 * <p>而本仓此前：
 * <ul>
 *   <li>全仓<b>没有</b> exporter 装配 ⇒ {@code GlobalOpenTelemetry.get()} 是 <b>no-op</b>
 *       ⇒ 所有 {@code GlobalOpenTelemetry.get().getTracer(...)} 建出来的 span
 *       <b>SpanContext 恒为 INVALID</b> ⇒ <b>既没有 span，也没有导出</b>；</li>
 *   <li>⇒ 连带后果：{@code common/web/TraceparentFilter}（Go 的
 *       {@code rest/handler/tracehandler.go} 的 Java 对应物）<b>写不出 traceparent 响应头</b>
 *       ⇒ <b>响应头少一个</b>（可观察）；</li>
 *   <li>各 yaml 里的 {@code otel: {...}} <b>一直是对的</b> ⇒ 缺的只是<b>装配</b>。</li>
 * </ul>
 *
 * <h2>🔴 逐条照抄 Go 的 {@code startAgent}（{@code core/trace/agent.go:105-133}）</h2>
 * <pre>
 * Go: sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(c.Sampler)))   // :111
 *     sdktrace.WithResource(resource.NewSchemaless(attrResources...))                   // :112
 *     if len(c.Endpoint) &gt; 0 { opts = append(opts, sdktrace.WithBatcher(exp)) }       // :114-121
 *     tp = sdktrace.NewTracerProvider(opts...)                                          // :125
 *     otel.SetTracerProvider(tp)                                                        // :127
 *     otel.SetErrorHandler(otel.ErrorHandlerFunc(func(err error) { logx.Errorf(...) })) // :128-130
 * </pre>
 * ⇒ 对应本类的：{@link #buildProvider} 一行一行对齐（含
 * <b>「endpoint 为空 ⇒ 不装 exporter」</b>这条短路）。
 *
 * <h2>⚠️ Go 的 {@code otel.SetErrorHandler} 在 Java 侧<b>没有对应物</b>（框架版本限制）</h2>
 * <pre>
 * Go: core/trace/agent.go:128-130
 *     otel.SetErrorHandler(otel.ErrorHandlerFunc(func(err error) { logx.Errorf("[otel] error: %v", err) }))
 * </pre>
 * ⇒ 实测 <b>OTel Java 1.44.0 全家（api / sdk / sdk-trace / sdk-common）里
 * {@code ErrorHandler} 这个类/接口根本不存在</b>（{@code javap} + 解包 jar 逐个确认）
 * ⇒ ⇒ <b>本类不设错误处理器</b> ⇒ exporter 的失败由 SDK 默认路径处理。
 *
 * <h2>🔴 传播器也是 Go 的 {@code init()} 装的（无条件）</h2>
 * <pre>
 * Go: core/trace/propagation.go:8-11
 *     func init() { otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
 *         propagation.TraceContext{}, propagation.Baggage{})) }
 * </pre>
 * ⇒ 与「有没有 exporter」<b>无关</b> ⇒ 只要 {@code core/trace} 被 import 就已装好。
 * ⇒ 这条<b>可观察</b>：Go 的 communityfact 发给 DC 的请求<b>带</b> {@code traceparent}
 * ⇒ 本类装上全局传播器后即对齐。
 *
 * <h2>🔴 幂等：照 Go 的 {@code sync.Once}</h2>
 * <p>Go 的 {@code StartAgent}（{@code core/trace/agent.go:43-52}）用 {@code once.Do}
 * ⇒ 「同 JVM 内只装一次」（REST + RPC 同进程时不会重复装）。
 * ⇒ 本类用静态标志位做同款语义。
 *
 * <h2>🔴 装不上时<b>不阻断启动</b>（照 Go）</h2>
 * <p>Go 的 {@code StartAgent} 在 {@code startAgent} 失败时只 {@code logx.Error(err)} ⇒ <b>继续启动</b>。
 * ⇒ 本类同款：{@link IllegalStateException}（例如同 JVM 内已有人设过全局）只<b>记 ERROR</b>，
 * <b>不抛</b> ⇒ 服务照常起 ⇒ 但全局仍是 no-op ⇒ 与 Go 失败时的行为<b>逐条同构</b>。
 *
 * <h2>抄写基准</h2>
 * <p>RAG {@code service/common/src/main/java/com/rag/common/observability/GoStyleOtelAgent.java}
 * 整段抄（L2 业务语义层以 RAG 为基准整段抄 · D13）⇒ <b>零行为改动</b>。
 * ⇒ 类名沿用 {@code GoStyle*} 前缀（与本仓 {@code GoStyleRequestDeadline} /
 * {@code GoStyleLogger} 同款：<b>前缀 = 「这段是 Go 行为的逐条复刻」的标记</b>）。
 *
 * @see GoStyleOtelConfig
 * @see com.rag.common.autoconfigure.GoStyleOtelAutoConfiguration
 */
public final class GoStyleOtelAgent {

    private static final Logger LOG = LoggerFactory.getLogger(GoStyleOtelAgent.class);

    private static final Object LOCK = new Object();

    /** Go {@code core/trace/agent.go:48-52} 的 {@code once} 同款：同 JVM 只装一次。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** 已装配的 provider（供关停时 flush）。 */
    private static SdkTracerProvider installedProvider;

    private GoStyleOtelAgent() {
    }

    /**
     * 装配全局 OpenTelemetry（幂等）。
     *
     * <p>🔴 <b>必须早于任何 {@code GlobalOpenTelemetry.get()} 调用</b>：
     * OTel Java 的 {@code GlobalOpenTelemetry.set(...)} 只能调一次，
     * 且<b>先调 {@code get()} 会让后续 {@code set()} 抛
     * {@code IllegalStateException}</b> ⇒ 装配点见
     * {@link com.rag.common.autoconfigure.GoStyleOtelAutoConfiguration}。
     *
     * @param config Go 的 Telemetry 四字段投影
     * @return 已装配的 SDK；未装上时返回 {@code null}（失败只记 ERROR，不抛）
     */
    public static OpenTelemetry install(GoStyleOtelConfig config) {
        synchronized (LOCK) {
            if (INSTALLED.get()) {
                // Go: core/trace/agent.go:48 once.Do ⇒ 第二次调用什么都不做
                return GlobalOpenTelemetry.get();
            }
            // 🔴 buildProvider 也可能失败（batcher 非法 / 端点不可达）
            //   ⇒ 照 Go 的 startAgent 失败路径 —— 记 ERROR ⇒ **继续启动**（全局仍是 no-op）
            //   ⇒ 绝不让异常逃出去阻断容器启动。
            //   Go 出处：core/trace/agent.go:116-120（createExporter 返错 ⇒ logx.Error + return err）
            //           ⇒ core/trace/agent.go:43-52（StartAgent 的 once.Do 里只 logx.Error）
            SdkTracerProvider provider;
            try {
                provider = buildProvider(config);
            } catch (RuntimeException bad) {
                LOG.error("[otel] error: 全局 TracerProvider 装配失败（{}），trace 导出不可用（服务照常启动）",
                        bad.getMessage());
                return null;
            }
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    // Go: core/trace/propagation.go:8-11（TraceContext + Baggage 的 composite）
                    .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                            W3CTraceContextPropagator.getInstance(),
                            W3CBaggagePropagator.getInstance())))
                    .build();
            try {
                GlobalOpenTelemetry.set(sdk);
            } catch (IllegalStateException alreadySet) {
                // Go: core/trace/agent.go:49-51 —— startAgent 失败只 logx.Error，继续跑
                LOG.error("[otel] error: 全局 TracerProvider 装配失败（{}），trace 导出不可用",
                        alreadySet.getMessage());
                provider.close();
                return null;
            }
            installedProvider = provider;
            INSTALLED.set(true);
            // Go: core/service/serviceconf.go:71-73 —— proc.AddShutdownListener(trace.StopAgent)
            //      core/trace/agent.go:55-62 —— StopAgent → tp.Shutdown（flush 未导出的 span）
            Runtime.getRuntime().addShutdownHook(new Thread(GoStyleOtelAgent::shutdown, "otel-shutdown"));
            LOG.info("全局 OpenTelemetry 已装配：service={} endpoint={} sampler={}",
                    config.serviceName(), config.endpoint(), config.samplerRatio());
            return sdk;
        }
    }

    /**
     * 构造 provider —— 对应 Go {@code core/trace/agent.go:105-125} 的 {@code startAgent} 主体。
     *
     * <p>🔴 <b>抽成静态方法是为了可测</b>：它<b>不碰全局</b> ⇒ 守门可以拿它造一个
     * 「指向假 OTLP 接收端」的 provider 做<b>端到端导出</b>验证，而不去污染全局。
     *
     * @param config Go 的 Telemetry 四字段投影
     */
    public static SdkTracerProvider buildProvider(GoStyleOtelConfig config) {
        // 🔴 batcher 校验 —— 照 Go 的 createExporter 的 default 分支
        //   （core/trace/agent.go:100-101：unknown exporter ⇒ 返错 ⇒ 整个 agent 装不上）
        //   ⚠️ 端点为空时**不校验**（Go 同款：createExporter 只在 len(c.Endpoint) > 0 时被调用）。
        config.validateBatcher();
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                // Go: core/trace/agent.go:111
                //   sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(c.Sampler)))
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(config.samplerRatio())))
                // Go: core/trace/agent.go:106 + :112
                //   AddResources(semconv.ServiceNameKey.String(c.Name))
                //   sdktrace.WithResource(resource.NewSchemaless(attrResources...))
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"),
                        config.serviceName() == null ? "" : config.serviceName()))));
        if (config.endpoint() != null) {
            // Go: core/trace/agent.go:115-124 —— if len(c.Endpoint) > 0 才装 batcher
            SpanExporter exporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(config.endpoint())
                    .build();
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }
        return builder.build();
    }

    /**
     * 关停并 flush —— 对应 Go 的 {@code trace.StopAgent()}（{@code core/trace/agent.go:55-62}）。
     *
     * <p>⚠️ <b>本方法会清掉「已装配」标志</b> ⇒ 允许同 JVM 内再次装配（测试用）。
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (installedProvider != null) {
                installedProvider.close();
                installedProvider = null;
            }
            INSTALLED.set(false);
        }
    }

    /** 是否已装配（守门用）。 */
    public static boolean isInstalled() {
        return INSTALLED.get();
    }

    /**
     * 取全局 tracer —— 对应 Go 的 {@code otel.GetTracer(name)}。
     *
     * <p>⚠️ <b>刻意不在静态字段里缓存</b>：全局 provider 是<b>运行期</b>才装的
     * （装配点在容器刷新早期），静态初始化时缓存会拿到 no-op tracer。
     */
    public static Tracer tracer(String name) {
        return GlobalOpenTelemetry.get().getTracer(name);
    }
}
