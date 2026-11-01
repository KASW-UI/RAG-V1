package com.rag.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>Go {@code Telemetry} 段的 3 个配置字段真的能被绑定</b>。
 *
 * <p>⚠️ <b>Go 出处</b>：{@code etc/*.yaml} 的 {@code Telemetry: { Endpoint / Batcher / Sampler }}
 * ⇒ Go 的 {@code otlptracegrpc.WithEndpoint(c.Endpoint) + WithInsecure()}。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code GoStyleOtelConfig} 的 3 个字段（L2 · D13）⇒
 * <b>本轮（批次 E）补齐</b> —— 原先 C 侧只留 slow/httpTimeout/rpcTimeout 三个 Duration。
 *
 * <p>⚠️ <b>这三条字段只承载配置、尚未驱动任何装配</b>（全局 OTel 装配走 **D-03** /
 * **D-07**）⇒ ⇒ 本测试**只断言「能绑上」**，**不**断言「会导出 span」。
 */
class ObservabilityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    com.rag.common.autoconfigure.ObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("Go Telemetry 的 3 个字段可绑定：endpoint / protocol / sampler-ratio")
    void telemetryFieldsAreBindable() {
        runner.withPropertyValues(
                        "observability.endpoint=otel-collector:4317",
                        "observability.protocol=grpc",
                        "observability.sampler-ratio=0.25")
                .run(context -> {
                    ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
                    assertThat(props.getEndpoint()).isEqualTo("otel-collector:4317");
                    assertThat(props.getProtocol()).isEqualTo("grpc");
                    assertThat(props.getSamplerRatio()).isEqualTo(0.25);
                });
    }

    @Test
    @DisplayName("未配置时 endpoint/protocol 为 null、sampler-ratio 为 0（不臆造默认值）")
    void defaultsAreEmpty() {
        runner.run(context -> {
            ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
            assertThat(props.getEndpoint()).isNull();
            assertThat(props.getProtocol()).isNull();
            assertThat(props.getSamplerRatio()).isZero();
        });
    }

    @Test
    @DisplayName("原有的 3 个 Duration 字段仍在（批次 E 只补不改）")
    void durationFieldsStillPresent() {
        runner.withPropertyValues(
                        "observability.slow-threshold=750ms",
                        "observability.http-timeout=3s",
                        "observability.rpc-timeout=4s")
                .run(context -> {
                    ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
                    assertThat(props.getSlowThreshold()).hasMillis(750);
                    assertThat(props.getHttpTimeout()).hasSeconds(3);
                    assertThat(props.getRpcTimeout()).hasSeconds(4);
                });
    }
}
