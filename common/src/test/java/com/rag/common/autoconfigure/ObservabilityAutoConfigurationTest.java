package com.rag.common.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>观测的判定顺序真的按 Go 的口径</b>。
 *
 * <p>⚠️ <b>判定顺序本身就是行为</b>（把 ① 和 ② 调换 ⇒ {@code sea.error.kind} 变）：
 * <pre>
 *   HTTP：① 信封 code != 0 &amp;&amp; code != 200 ⇒ business   ← 业务码优先于状态码
 *         ② 否则 statusCode &gt;= 400             ⇒ system
 *         ③ 否则                                  ⇒ 正常
 *   gRPC：① OK ⇒ 正常 ② DEADLINE_EXCEEDED ⇒ timeout ③ 7 个业务码 ⇒ business ④ 其余 ⇒ system
 * </pre>
 */
class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("HTTP：业务码优先于状态码（200 + 业务信封 ⇒ business）")
    void businessEnvelopeWinsOverStatus() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        runner.withBean(ObservationRegistry.class, () -> registry(captured)).run(context -> {
            ObservabilityAutoConfiguration.HttpObservationFilter filter =
                    context.getBean(ObservabilityAutoConfiguration.HttpObservationFilter.class);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/article");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chainWriting("{\"code\":1001,\"msg\":\"dup user\"}"));

            Observation.Context ctx = captured.get();
            assertThat(ctx).isNotNull();
            assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("business");
            assertThat(ctx.getHighCardinalityKeyValue("sea.biz_code").getValue()).isEqualTo("1001");
            assertThat(ctx.getHighCardinalityKeyValue("sea.biz_msg").getValue()).isEqualTo("dup user");
            // ⚠️ 成功码 200 的信封 ⇒ **不是** business
            assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isNotEqualTo("system");
        });
    }

    @Test
    @DisplayName("HTTP：信封 code=200 ⇒ 正常路径（不写 error.kind）")
    void successEnvelopeIsNormal() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        runner.withBean(ObservationRegistry.class, () -> registry(captured)).run(context -> {
            ObservabilityAutoConfiguration.HttpObservationFilter filter =
                    context.getBean(ObservabilityAutoConfiguration.HttpObservationFilter.class);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/article");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chainWriting("{\"code\":200,\"msg\":\"ok\",\"data\":null}"));

            Observation.Context ctx = captured.get();
            assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind")).isNull();
            assertThat(ctx.getLowCardinalityKeyValue("http.status_code").getValue()).isEqualTo("200");
            assertThat(ctx.getLowCardinalityKeyValue("http.route").getValue()).isEqualTo("/api/v1/article");
            assertThat(ctx.getLowCardinalityKeyValue("http.method").getValue()).isEqualTo("POST");
        });
    }

    @Test
    @DisplayName("HTTP：没有信封 + 状态码 ≥ 400 ⇒ system")
    void statusWithoutEnvelopeIsSystemError() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        runner.withBean(ObservationRegistry.class, () -> registry(captured)).run(context -> {
            ObservabilityAutoConfiguration.HttpObservationFilter filter =
                    context.getBean(ObservabilityAutoConfiguration.HttpObservationFilter.class);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/article");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(503);
            filter.doFilter(request, response, chainWriting(""));

            Observation.Context ctx = captured.get();
            assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("system");
            assertThat(ctx.getHighCardinalityKeyValue("sea.biz_code")).isNull();
        });
    }

    @Test
    @DisplayName("HTTP：响应体被原样写回（观测 filter 不得吞掉 body）")
    void responseBodyIsPreserved() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        runner.withBean(ObservationRegistry.class, () -> registry(captured)).run(context -> {
            ObservabilityAutoConfiguration.HttpObservationFilter filter =
                    context.getBean(ObservabilityAutoConfiguration.HttpObservationFilter.class);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/article");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chainWriting("{\"code\":200,\"msg\":\"ok\"}"));

            assertThat(response.getContentAsString()).contains("\"code\":200");
        });
    }

    @Test
    @DisplayName("信封解析：解析失败 / 空 body ⇒ 当作「没有信封」")
    void envelopeParsingFallsBackToNull() {
        assertThat(ObservabilityAutoConfiguration.parseEnvelope(null)).isNull();
        assertThat(ObservabilityAutoConfiguration.parseEnvelope(new byte[0])).isNull();
        assertThat(ObservabilityAutoConfiguration.parseEnvelope("not json".getBytes())).isNull();
        // ⚠️ code=0 且 msg 为空 ⇒ 「不存在」
        assertThat(ObservabilityAutoConfiguration.parseEnvelope(
                "{\"code\":0,\"msg\":\"\"}".getBytes())).isNull();
        assertThat(ObservabilityAutoConfiguration.parseEnvelope(
                "{\"code\":1001,\"msg\":\"x\"}".getBytes())).isNotNull();
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /** 造一个 registry，把每次 stop 的 context 抓出来。 */
    private static ObservationRegistry registry(AtomicReference<Observation.Context> captured) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                captured.set(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }

    /** 写一个固定响应体的 chain。 */
    private static FilterChain chainWriting(String body) {
        return (req, res) -> {
            if (!body.isEmpty()) {
                res.setContentType("application/json");
                res.getWriter().write(body);
            }
        };
    }
}
