package com.rag.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>观测层自己用的 3 个 Duration 字段真的能被绑定</b>。
 *
 * <p>⚠️ <b>上游的 {@code Telemetry} 段已不在本类</b>（2026-11-02 批次 F）——
 * {@code endpoint} / {@code protocol} / {@code sampler-ratio} 三个字段
 * ⇒ 已<b>移出</b>本类、归位到 {@link GoStyleOtelConfig}
 * ⇒ 因为它们必须**真正驱动装配**（全局 OTel SDK + OTLP exporter），而不是「只承载配置」。
 * ⇒ ⇒ 它们的绑定与解析断言在 {@code GoStyleOtelConfigTest} 里。
 *
 * <p>⚠️ <b>为什么键名回到 {@code otel.*}</b>：上游的 {@code Telemetry} 段在 B 侧被投影成
 * {@code otel.exporter.otlp.*} / {@code otel.traces.sampler} ⇒ 本仓沿用<b>同一套键名</b>
 * ⇒ ⇒ **L1 配置契约与 B 侧逐字节同构**（yml 搬运时零改写）。
 */
class ObservabilityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    com.rag.common.autoconfigure.ObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("原有的 3 个 Duration 字段仍可绑定（slow-threshold / http-timeout / rpc-timeout）")
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

    @Test
    @DisplayName("未配置时 3 个 Duration 字段为 null（缺省即不限时 ⇒ 对齐上游的 TimeoutFromMillis(0) → 0）")
    void durationsDefaultToNull() {
        runner.run(context -> {
            ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
            assertThat(props.getSlowThreshold()).isNull();
            assertThat(props.getHttpTimeout()).isNull();
            assertThat(props.getRpcTimeout()).isNull();
        });
    }
}
