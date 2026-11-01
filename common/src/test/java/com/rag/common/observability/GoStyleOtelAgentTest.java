package com.rag.common.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴🔴 **全局 OpenTelemetry 装配守门**（对应 Go {@code core/trace/agent.go} 的 {@code startAgent}）。
 *
 * <h2>本轮要证的<b>四件事</b>（每件都对应 Go 的一行）</h2>
 * <table border="1">
 *   <caption>四条守门 ↔ Go 出处</caption>
 *   <tr><th>守门</th><th>断言</th><th>Go 出处</th></tr>
 *   <tr>
 *     <td>① <b>全局 provider 是真的</b></td>
 *     <td>真 Spring Boot 上下文启动后，{@code GlobalOpenTelemetry.get()} 建出的 span
 *         <b>SpanContext 有效</b>；全局 propagator <b>能 inject 出 traceparent</b></td>
 *     <td>{@code core/trace/agent.go:127}（{@code otel.SetTracerProvider(tp)}）＋
 *         {@code core/trace/propagation.go:8-11}（{@code SetTextMapPropagator}）</td>
 *   </tr>
 *   <tr>
 *     <td>② <b>span 真的被导出到网络上</b></td>
 *     <td>假 OTLP 接收端（<b>真 gRPC 服务端</b>）收到 ≥1 个非空 Export 请求</td>
 *     <td>{@code core/trace/agent.go:115-124}（{@code if len(c.Endpoint) > 0 { WithBatcher(exp) }}）</td>
 *   </tr>
 *   <tr>
 *     <td>③ <b>关停会 flush</b></td>
 *     <td>关停（{@code shutdown()}）后，pending 的 span 立刻被导出
 *         （<b>早于</b> BatchSpanProcessor 的 5s 批量周期 ⇒ 证明是 flush 带来的）</td>
 *     <td>{@code core/service/serviceconf.go:71-73}（{@code proc.AddShutdownListener(trace.StopAgent)}）
 *         ＋ {@code core/trace/agent.go:30-34}（{@code tp.Shutdown}）</td>
 *   </tr>
 *   <tr>
 *     <td>④ <b>装不上不阻断启动</b></td>
 *     <td>{@code install()} 返回 {@code null} + 全局仍是 no-op（span 无效）</td>
 *     <td>{@code core/trace/agent.go:43-52}（{@code StartAgent} 的 {@code once.Do} 里只 {@code logx.Error}）</td>
 *   </tr>
 * </table>
 *
 * <h2>🔴 为什么 ① 必须用「真 Spring Boot 上下文」</h2>
 * <p>因为本仓走的是 Spring Boot 的<b>自动配置清单</b>机制
 * （{@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}）
 * ⇒ 只有真上下文才证明「清单被读到 ⇒ 自动配置被应用 ⇒ BFPP 跑了 ⇒ 全局装上了」
 * ⇒ <b>mock 上下文证明不了任何一环</b>。
 *
 * <h2>🔴 为什么 ② 必须是「真 gRPC 服务端」</h2>
 * <p>要证的是「<b>字节真的出了机器</b>」，不是「exporter 被配上了」⇒ mock 只能证后半句。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/test/java/com/rag/common/observability/}
 * 下的 {@code GoStyleOtelAgentGateTest} + {@code GoStyleOtelSamplerProtocolGateTest}
 * （L2 · D13 · 测试形态可直接借）。
 */
class GoStyleOtelAgentTest {

    @BeforeEach
    void resetGlobal() {
        // 同 JVM 内其它测试可能装过全局（GlobalOpenTelemetry 全局只能设一次）
        // ⇒ 每个用例都从「干净的全局」开始。
        GlobalOpenTelemetry.resetForTest();
        GoStyleOtelAgent.shutdown();
    }

    @AfterEach
    void cleanupGlobal() {
        GoStyleOtelAgent.shutdown();
        GlobalOpenTelemetry.resetForTest();
    }

    // ====================================================================================
    // ① 全局 provider 是真的（真 Spring Boot 上下文）
    // ====================================================================================

    @Test
    @DisplayName("🔴 真 Spring 上下文：全局 provider 装上了（span 有效）＋ 全局 propagator 能 inject traceparent")
    void globalProviderIsRealInsideRealSpringContext() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(GateApplication.class)
                // 🔴 强制非 web：classpath 上有 spring-boot-starter-web
                //    ⇒ 不指定的话 Spring 会起 Tomcat 去抢 8080 ⇒ 「端口已被占用」这种
                //    与被测语义毫无关系的失败会盖掉真正的断言。
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(
                        "otel.service.name=common-otel-gate",
                        // 空端点 ⇒ 照 Go 的 `len(c.Endpoint) > 0` ⇒ 不装 exporter（免得连不上 jaeger 刷日志）
                        "otel.exporter.otlp.endpoint=")
                .run()) {

            // --- ①a：全局 tracer 建出的 span 必须**有效** ---
            //     修前：GlobalOpenTelemetry 是 no-op ⇒ SpanContext 恒 INVALID ⇒ 这里必红
            Span span = GlobalOpenTelemetry.get().getTracer("gate").spanBuilder("gate-span").startSpan();
            assertThat(span.getSpanContext().isValid())
                    .as("全局 provider 必须是真 SDK（Go: otel.SetTracerProvider(tp)）")
                    .isTrue();
            span.end();

            // --- ①b：全局 propagator 必须能 inject 出 traceparent ---
            //     Go: core/trace/propagation.go:8-11（init() 里装 composite propagator）
            Map<String, String> carrier = new HashMap<>();
            try (Scope scope = span.makeCurrent()) {
                GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                        .inject(Context.current(), carrier, Map::put);
            }
            assertThat(carrier)
                    .as("全局 propagator 必须能 inject traceparent（Go 的 init() 是**无条件**装的）")
                    .containsKey("traceparent");
            assertThat(carrier.get("traceparent"))
                    .as("traceparent 必须是 W3C 形态：00-<32 hex>-<16 hex>-<2 hex>")
                    .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        }
    }

    // ====================================================================================
    // ② span 真的被导出到网络上（真 gRPC 假 OTLP 接收端）
    // ====================================================================================

    @Test
    @DisplayName("🔴 真 gRPC 假 OTLP 接收端：span 真的被导出（收到 ≥1 个非空 Export 请求）")
    void spanIsActuallyExportedToTheWire() throws Exception {
        try (FakeOtlpCollector collector = new FakeOtlpCollector()) {
            SdkTracerProvider provider = GoStyleOtelAgent.buildProvider(
                    new GoStyleOtelConfig("common-otel-gate", collector.endpoint(), "grpc", 1.0d));
            try {
                Tracer tracer = provider.get("gate");
                tracer.spanBuilder("exported-span").startSpan().end();

                // BatchSpanProcessor 的默认批量周期是 5s ⇒ 这里等它自然导出（**不靠关停**）
                // ⇒ 证明的是「exporter 真的在导出」，而不是「关停时 flush 了一把」。
                awaitTrue(() -> collector.requestCount() > 0, 15_000L);
            } finally {
                provider.close();
            }
            assertThat(collector.requestCount())
                    .as("span 必须真的被送到 OTLP 接收端（不是「exporter 被配上了」）")
                    .isGreaterThanOrEqualTo(1);
            assertThat(collector.receivedBytes())
                    .as("收到的 Export 请求体不能是空的")
                    .isGreaterThan(0);
        }
    }

    // ====================================================================================
    // ③ 关停会 flush（早于批量周期）
    // ====================================================================================

    @Test
    @DisplayName("🔴 关停会 flush：pending 的 span 在关停后立刻被导出（早于 5s 批量周期）")
    void shutdownFlushesPendingSpans() throws Exception {
        try (FakeOtlpCollector collector = new FakeOtlpCollector()) {
            GoStyleOtelAgent.install(
                    new GoStyleOtelConfig("common-otel-gate", collector.endpoint(), "grpc", 1.0d));
            assertThat(GoStyleOtelAgent.isInstalled()).isTrue();

            Tracer tracer = GlobalOpenTelemetry.get().getTracer("gate");
            tracer.spanBuilder("pending-span").startSpan().end();

            // 关停 ⇒ Go: core/trace/agent.go:30-34（tp.Shutdown ⇒ flush 未导出的 span）
            GoStyleOtelAgent.shutdown();

            // ⚠️ 3s 的判定窗口 < BatchSpanProcessor 的 5s 批量周期
            //    ⇒ 「这么快就收到」只能解释成「关停时 flush 了」
            //    ⇒ 若实现退化成「不 flush」，这里会因超时而红。
            awaitTrue(() -> collector.requestCount() > 0, 3_000L);
            assertThat(collector.requestCount())
                    .as("关停必须 flush 未导出的 span（Go: trace.StopAgent → tp.Shutdown）")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ====================================================================================
    // ④ 装不上不阻断启动
    // ====================================================================================

    @Test
    @DisplayName("🔴 装不上**不阻断启动**：install 返回 null + 全局仍是 no-op（Go: logx.Error 后继续启动）")
    void installFailureDoesNotBreakStartup() throws Exception {
        try (FakeOtlpCollector collector = new FakeOtlpCollector()) {
            OpenTelemetry installed;
            try {
                installed = GoStyleOtelAgent.install(
                        new GoStyleOtelConfig("common-otel-gate", collector.endpoint(), "not-a-batcher", 1.0d));
            } catch (RuntimeException e) {
                // Go: StartAgent 的 once.Do 里只 logx.Error ⇒ 异常**不许**逃出来
                throw new AssertionError("install 不许抛异常阻断启动（Go: core/trace/agent.go:43-52）", e);
            }
            assertThat(installed)
                    .as("装不上时 install 必须返回 null（照 Go：全局仍是 no-op）")
                    .isNull();
            assertThat(GoStyleOtelAgent.isInstalled())
                    .as("装不上时「已装配」标志必须保持 false ⇒ 不会误报成已装")
                    .isFalse();

            // 全局仍是 no-op ⇒ 建出的 span 无效
            Span span = GlobalOpenTelemetry.get().getTracer("gate").spanBuilder("x").startSpan();
            assertThat(span.getSpanContext().isValid())
                    .as("装不上时全局仍是 no-op ⇒ span 无效（与 Go 失败路径同构）")
                    .isFalse();
            span.end();
        }
    }

    // ====================================================================================
    // 幂等（Go: sync.Once）
    // ====================================================================================

    @Test
    @DisplayName("🔴 幂等：同 JVM 内第二次 install 不重装（Go: core/trace/agent.go:48 once.Do）")
    void installIsIdempotent() {
        OpenTelemetry first = GoStyleOtelAgent.install(
                new GoStyleOtelConfig("common-otel-gate", null, "grpc", 1.0d));
        assertThat(first).isNotNull();
        assertThat(GoStyleOtelAgent.isInstalled()).isTrue();

        OpenTelemetry second = GoStyleOtelAgent.install(
                new GoStyleOtelConfig("common-otel-gate", null, "grpc", 1.0d));
        assertThat(second).isNotNull();
        // ⚠️ 第二次 install 返回的是**全局**（Go: once.Do ⇒ 第二次什么都不做 ⇒ 返回已装的全局）
        //    ⚠️ 它**不是**第一次那个 SDK 实例本身（全局是 OTel 的 ObfuscatedOpenTelemetry 包装）
        //    ⇒ ⇒ 判据落在「全局仍是同一个 SDK 装出来的、span 依然有效」。
        Span span = GlobalOpenTelemetry.get().getTracer("gate").spanBuilder("x").startSpan();
        assertThat(span.getSpanContext().isValid())
                .as("第二次 install 不得把全局换成 no-op（Go: once.Do ⇒ 只装一次）")
                .isTrue();
        span.end();
        assertThat(GoStyleOtelAgent.isInstalled()).isTrue();
    }

    // ====================================================================================
    // 采样比例 + ParentBased（Go: core/trace/agent.go:110-111）
    // ====================================================================================

    @Test
    @DisplayName("🔴 Sampler=0.0 ⇒ **根 span 不采样 ⇒ 一个 span 都不导出**（Go: TraceIDRatioBased(0.0)）")
    void ratioZeroMeansNothingIsExported() throws Exception {
        try (FakeOtlpCollector collector = new FakeOtlpCollector()) {
            SdkTracerProvider provider = GoStyleOtelAgent.buildProvider(
                    new GoStyleOtelConfig("common-otel-gate", collector.endpoint(), "grpc", 0.0d));
            try {
                Tracer tracer = provider.get("gate");
                Span span = tracer.spanBuilder("root-span").startSpan();

                assertThat(span.isRecording())
                        .as("Sampler=0.0 ⇒ 根 span 必须**不 recording**"
                                + "（Go: sdktrace.TraceIDRatioBased(0.0) ⇒ DROP）")
                        .isFalse();
                assertThat(span.getSpanContext().isSampled())
                        .as("被 DROP 的 span：**SpanContext 仍有效**（有 trace-id/span-id），"
                                + "但 sampled 标志为 false（trace flags = 00）"
                                + " —— Go 的 OTel SDK 同款 ⇒ 响应头里**仍会有** traceparent，只是 flags=00")
                        .isFalse();
                span.end();
            } finally {
                // 关停 ⇒ flush ⇒ 若有 pending span 必被送出（这是「确实没导出」的强证据）
                provider.close();
            }
            assertThat(collector.requestCount())
                    .as("Sampler=0.0 ⇒ **一个 span 都不该出机器**（Go: 采样器把根 span 丢了）")
                    .isEqualTo(0);
        }
    }

    @Test
    @DisplayName("🔴 ParentBased：入向 parent 已采样 ⇒ **即使 Sampler=0.0，子 span 仍被采样并导出**"
            + "（Go: ParentBased(TraceIDRatioBased(0.0))）")
    void parentBasedKeepsSampledParentEvenWhenRatioIsZero() throws Exception {
        try (FakeOtlpCollector collector = new FakeOtlpCollector()) {
            SdkTracerProvider provider = GoStyleOtelAgent.buildProvider(
                    new GoStyleOtelConfig("common-otel-gate", collector.endpoint(), "grpc", 0.0d));
            try {
                // 入向 traceparent：flags = 01 ⇒ **已采样**
                Map<String, String> incoming = new HashMap<>();
                incoming.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

                Context parent = io.opentelemetry.context.propagation.TextMapPropagator
                        .composite(io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance())
                        .extract(Context.root(), incoming, MapGetter.INSTANCE);

                Tracer tracer = provider.get("gate");
                Span span = tracer.spanBuilder("child-span").setParent(parent).startSpan();

                assertThat(span.getSpanContext().isSampled())
                        .as("ParentBased ⇒ 入向 parent 已采样 ⇒ 子 span **必须被采样**"
                                + "（Go: sdktrace.ParentBased ⇒ remoteParentSampled ⇒ AlwaysOn）")
                        .isTrue();
                span.end();
            } finally {
                provider.close();
            }
            awaitTrue(() -> collector.requestCount() > 0, 10_000L);
            assertThat(collector.requestCount())
                    .as("被采样的子 span 必须真的出机器")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /**
     * 最小 Spring Boot 应用：<b>只开自动配置，不开组件扫描</b>。
     *
     * <p>⚠️ 不扫描是关键：否则会扫到 {@code com.rag.common} 里的 servlet 相关组件
     * （多个 Filter 依赖 servlet API）⇒ 非 web 上下文里会炸。
     * ⇒ 本门禁只验「自动配置清单 ⇒ BFPP ⇒ 全局装配」这一条链。
     *
     * <p>⚠️ 排除 {@link DataSourceAutoConfiguration}：本仓的 classpath 上有
     * {@code mybatis-plus-spring-boot3-starter} ⇒ 会带出 {@code spring-boot-starter-jdbc}
     * ⇒ 没有任何 DataSource 配置时启动会报「找不到驱动类」
     * ⇒ ⇒ 那与被测语义（OTel 装配）毫无关系 ⇒ 显式排除。
     *
     * <p>⚠️ 排除 devh 的 {@code GrpcServerAutoConfiguration}：它会真的起一个 gRPC server
     * （默认端口 9090）⇒ 与被测语义无关、且会引入端口占用这种噪声失败 ⇒ 显式排除。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            net.devh.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration.class,
            net.devh.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration.class})
    static class GateApplication {
    }

    /** 极简 getter：只取 {@code traceparent}。 */
    private enum MapGetter implements io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    }

    /** 轮询等待条件成立（超时即失败 ⇒ 失败信息里带最后一次读数）。 */
    private static void awaitTrue(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as("等待条件在 %d ms 内未成立", timeoutMillis).isTrue();
    }
}
