package com.rag.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 **Go {@code Telemetry} 段的配置投影守门** —— 对应 Go
 * {@code core/trace/config.go} 的 {@code Name / Endpoint / Sampler / Batcher} 四个字段。
 *
 * <h2>要证的<b>五件事</b>（逐条对应 Go / B 的一行）</h2>
 * <table border="1">
 *   <caption>五条守门 ↔ 出处</caption>
 *   <tr><th>守门</th><th>Go / B 出处</th></tr>
 *   <tr><td>① 服务名三套拼法都认（且有优先级）</td>
 *       <td>B 侧 {@code GoStyleOtelConfig#from}（api 模块写
 *           {@code otel.resource.attributes.service.name}、rpc 模块写 {@code otel.service.name}）</td></tr>
 *   <tr><td>② 裸 {@code host:port} 补 {@code http://}（= Go 的 {@code WithInsecure()}）</td>
 *       <td>Go {@code core/trace/agent.go:71-72}</td></tr>
 *   <tr><td>③ 采样器<b>名字</b> → Go 的<b>比例</b></td>
 *       <td>Go {@code core/trace/config.go:9}（{@code Sampler float64}）</td></tr>
 *   <tr><td>④ 非法采样器名 ⇒ <b>炸</b>（不静默降级成 always_on）</td>
 *       <td>Go 的 yaml 解析器按 tag 校验 ⇒ 启动即炸</td></tr>
 *   <tr><td>⑤ batcher（协议）校验：端点为空<b>不校验</b>；端点非空 + 非法 ⇒ 装不上</td>
 *       <td>Go {@code core/trace/agent.go:100-101} + {@code :115-124}</td></tr>
 * </table>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/test/java/com/rag/common/observability/GoStyleOtelSamplerProtocolGateTest.java}
 * （L2 · D13 · 测试形态可直接借）。
 */
class GoStyleOtelConfigTest {

    // ── ① 服务名 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 服务名：otel.service.name 优先于 otel.resource.attributes.service.name，再回落 spring.application.name")
    void serviceNameResolvesInPriorityOrder() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("otel.service.name", "like-api")
                .withProperty("otel.resource.attributes.service.name", "comment-api")
                .withProperty("spring.application.name", "fallback-name");
        assertThat(GoStyleOtelConfig.from(env).serviceName()).isEqualTo("like-api");

        MockEnvironment onlyResource = new MockEnvironment()
                .withProperty("otel.resource.attributes.service.name", "comment-api")
                .withProperty("spring.application.name", "fallback-name");
        assertThat(GoStyleOtelConfig.from(onlyResource).serviceName()).isEqualTo("comment-api");

        MockEnvironment onlyApp = new MockEnvironment()
                .withProperty("spring.application.name", "fallback-name");
        assertThat(GoStyleOtelConfig.from(onlyApp).serviceName()).isEqualTo("fallback-name");
    }

    @Test
    @DisplayName("🔴 服务名：一个都没有 ⇒ null（不臆造）")
    void serviceNameNullWhenNothingConfigured() {
        assertThat(GoStyleOtelConfig.from(new MockEnvironment()).serviceName()).isNull();
    }

    // ── ② 端点归一 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 裸 host:port ⇒ 补 http://（对齐 Go 的 WithInsecure()）")
    void bareEndpointGetsHttpScheme() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("otel.exporter.otlp.endpoint", "jaeger:4317");
        assertThat(GoStyleOtelConfig.from(env).endpoint()).isEqualTo("http://jaeger:4317");
    }

    @Test
    @DisplayName("🔴 已带 scheme 的端点原样保留（http:// 与 https:// 都不动）")
    void endpointWithSchemeKeptAsIs() {
        MockEnvironment http = new MockEnvironment()
                .withProperty("otel.exporter.otlp.endpoint", "http://collector:4317");
        assertThat(GoStyleOtelConfig.from(http).endpoint()).isEqualTo("http://collector:4317");

        MockEnvironment https = new MockEnvironment()
                .withProperty("otel.exporter.otlp.endpoint", "https://collector:4317");
        assertThat(GoStyleOtelConfig.from(https).endpoint()).isEqualTo("https://collector:4317");
    }

    @Test
    @DisplayName("🔴 端点为空串 ⇒ null ⇒ **不装 exporter**（照 Go 的 len(c.Endpoint) > 0）")
    void emptyEndpointMeansNoExporter() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("otel.exporter.otlp.endpoint", "   ");
        assertThat(GoStyleOtelConfig.from(env).endpoint()).isNull();
    }

    // ── ③ 协议 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 协议缺省 = grpc（= Go 的 Batcher: otlpgrpc）")
    void protocolDefaultsToGrpc() {
        assertThat(GoStyleOtelConfig.from(new MockEnvironment()).protocol()).isEqualTo("grpc");

        MockEnvironment env = new MockEnvironment()
                .withProperty("otel.exporter.otlp.protocol", "grpc");
        assertThat(GoStyleOtelConfig.from(env).protocol()).isEqualTo("grpc");
    }

    // ── ④ 采样 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 sampler 名字 → 比例 的映射：always_on⇒1.0 / always_off⇒0.0 / traceidratio⇒arg")
    void samplerNameMapsToGoRatio() {
        assertThat(GoStyleOtelConfig.toRatio("always_on", null)).isEqualTo(1.0d);
        assertThat(GoStyleOtelConfig.toRatio("parentbased_always_on", null)).isEqualTo(1.0d);
        assertThat(GoStyleOtelConfig.toRatio("always_off", null)).isEqualTo(0.0d);
        assertThat(GoStyleOtelConfig.toRatio("parentbased_always_off", null)).isEqualTo(0.0d);
        assertThat(GoStyleOtelConfig.toRatio("traceidratio", 0.25d)).isEqualTo(0.25d);
        assertThat(GoStyleOtelConfig.toRatio("parentbased_traceidratio", 0.5d)).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("🔴 非法 sampler 名 ⇒ 炸（不能静默降级成 always_on）")
    void unknownSamplerNameMustFail() {
        assertThatThrownBy(() -> GoStyleOtelConfig.toRatio("never_heard_of_it", null))
                .as("非法采样器名必须炸")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never_heard_of_it");
    }

    @Test
    @DisplayName("🔴 otel.traces.sampler + otel.traces.sampler.arg ⇒ 走比例分支")
    void samplerNameAndArgAreRead() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("otel.traces.sampler", "traceidratio")
                .withProperty("otel.traces.sampler.arg", "0.25");
        assertThat(GoStyleOtelConfig.from(env).samplerRatio()).isEqualTo(0.25d);
    }

    @Test
    @DisplayName("🔴 sampler 未配置 ⇒ 1.0（Go 的 Sampler 默认值 = 1.0）")
    void samplerDefaultsToOne() {
        assertThat(GoStyleOtelConfig.from(new MockEnvironment()).samplerRatio()).isEqualTo(1.0d);
    }

    // ── ⑤ batcher（协议）校验 ───────────────────────────────────────────

    @Test
    @DisplayName("🔴 非法协议 + 端点非空 ⇒ 装不上（Go: unknown exporter ⇒ 整个 agent 装不上）")
    void unknownProtocolMustFailInsteadOfSilentlyUsingGrpc() {
        assertThatThrownBy(() -> GoStyleOtelConfig
                .from(new MockEnvironment()
                        .withProperty("otel.exporter.otlp.endpoint", "collector:4317")
                        .withProperty("otel.exporter.otlp.protocol", "not-a-batcher"))
                .validateBatcher())
                .as("非法协议必须抛错（Go: core/trace/agent.go:100-101 unknown exporter）"
                        + "—— 静默按 grpc 处理就是**静默降级**")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown exporter")
                .hasMessageContaining("not-a-batcher");
    }

    @Test
    @DisplayName("🔴 Go 支持但本仓未实现的 batcher（写成 Go 的名字 otlphttp）⇒ 同样装不上")
    void goSupportedButUnimplementedProtocolMustFail() {
        assertThatThrownBy(() -> GoStyleOtelConfig
                .from(new MockEnvironment()
                        .withProperty("otel.exporter.otlp.endpoint", "collector:4317")
                        .withProperty("otel.exporter.otlp.protocol", "otlphttp"))
                .validateBatcher())
                .as("Go 的 Batcher 合法集合含 otlphttp，但本仓**只实现了 grpc**（= Go 的 otlpgrpc）"
                        + " ⇒ 不能静默按 grpc 处理")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown exporter")
                .hasMessageContaining("otlphttp")
                .hasMessageContaining("未实现");
    }

    @Test
    @DisplayName("🔴 端点为空 ⇒ **根本不校验** batcher（Go: createExporter 只在 len(Endpoint)>0 时被调用）")
    void emptyEndpointSkipsProtocolValidation() {
        GoStyleOtelConfig config = GoStyleOtelConfig
                .from(new MockEnvironment().withProperty("otel.exporter.otlp.protocol", "not-a-batcher"));
        // Go 出处：core/trace/agent.go:115-120 —— 端点为空 ⇒ 不建 exporter ⇒ 不走 createExporter
        assertThat(config.endpoint()).isNull();
        org.assertj.core.api.Assertions.assertThatCode(config::validateBatcher)
                .as("端点为空时必须能通过校验（Go 同款：只少了 exporter）")
                .doesNotThrowAnyException();
    }
}
