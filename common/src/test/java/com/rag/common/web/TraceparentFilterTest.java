package com.rag.common.web;

import com.rag.common.observability.GoStyleOtelAgent;
import com.rag.common.observability.GoStyleOtelConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴🔴 **`traceparent` 响应头守门** —— 对应 Go 的
 * {@code rest/handler/tracehandler.go}（{@code propagator.Inject(spanCtx, HeaderCarrier(w.Header()))}）。
 *
 * <h2>🔴 为什么这条守门是「全局 OTel 装配」这件事的<b>可观察后果</b></h2>
 * <p>{@code TraceparentFilter} 从<b>全局</b> provider 取 tracer
 * （{@code GlobalOpenTelemetry.get().getTracer(...)}）⇒
 * ⇒ 全局没装 ⇒ {@code get()} 是 no-op ⇒ 建出的 span <b>SpanContext 恒 INVALID</b>
 * ⇒ ⇒ W3C propagator 的 {@code inject} 遇到无效 context <b>直接不写</b>
 * ⇒ ⇒ <b>响应头少一个</b>（可观察的行为差异）。
 * <p>⇒ 所以：<b>装了全局 SDK ⇒ 头出现；没装 ⇒ 头不出现</b>
 * ⇒ ⇒ 这条守门同时钉住两件事：① 装配真的生效；② 装配的**可观察后果**真的到位。
 *
 * <h2>三条语义（Go 逐条）</h2>
 * <ol>
 *   <li><b>注入的是 server span 自己</b>，不是回显入向 parent（最常见的「看起来对」的错误实现）；</li>
 *   <li><b>头名 canonical 化</b>（{@code Traceparent}）⇒ 与 Go 的
 *       {@code textproto.CanonicalMIMEHeaderKey} 字节级一致；</li>
 *   <li><b>trace-id 沿用入向</b>（server span 是入向 context 的子 span）⇒ 客户端能把两侧串起来。</li>
 * </ol>
 */
class TraceparentFilterTest {

    private final TraceparentFilter filter = new TraceparentFilter();

    @BeforeEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
        GoStyleOtelAgent.shutdown();
    }

    @AfterEach
    void cleanupGlobal() {
        GoStyleOtelAgent.shutdown();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    @DisplayName("🔴 没装全局 SDK ⇒ **不写** traceparent 头（no-op provider ⇒ span 无效 ⇒ inject 写不出）")
    void noHeaderWithoutGlobalProvider() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/article/list");
        request.setRequestURI("/api/article/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getHeader("Traceparent"))
                .as("全局没装时不得凭空造头（Go: span 无效 ⇒ propagator 不 inject）")
                .isNull();
    }

    @Test
    @DisplayName("🔴 装了全局 SDK ⇒ 响应头出现 Traceparent，且是 W3C 形态")
    void headerAppearsWithGlobalProvider() throws Exception {
        installGlobalSdk();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/article/list");
        request.setRequestURI("/api/article/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        String traceparent = response.getHeader("Traceparent");
        assertThat(traceparent)
                .as("装了全局 SDK ⇒ 响应必须带 traceparent（Go: tracehandler.go 的 Inject）")
                .isNotNull();
        assertThat(traceparent)
                .as("traceparent 必须是 W3C 形态：00-<32 hex>-<16 hex>-<2 hex>")
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    @DisplayName("🔴 头名是 canonical 的 Traceparent（Go 的 textproto.CanonicalMIMEHeaderKey）")
    void headerNameIsCanonicalised() throws Exception {
        installGlobalSdk();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/article/list");
        request.setRequestURI("/api/article/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        assertThat(response.getHeader("Traceparent")).isNotNull();
        // ⚠️ servlet 规范的 `getHeader(name)` 是**大小写不敏感**的 ⇒ 查不出「键名到底写成什么」
        //    ⇒ 必须查「实际存了哪些头名」。
        assertThat(response.getHeaderNames())
                .as("Tomcat 会原样发出头名 ⇒ 必须由本类自己 canonical 化（小写头名与 Go 字节级不一致）")
                .contains("Traceparent")
                .doesNotContain("traceparent");
    }

    @Test
    @DisplayName("🔴 注入的是 server span 自己：trace-id 沿用入向，但 span-id ≠ 入向 span-id")
    void injectsServerSpanNotInboundParent() throws Exception {
        installGlobalSdk();

        String inboundTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String inboundSpanId = "00f067aa0ba902b7";

        Tracer tracer = GlobalOpenTelemetry.get().getTracer("inbound");
        Span parent = tracer.spanBuilder("inbound").startSpan();
        assertThat(parent.getSpanContext().getTraceId()).isNotEqualTo(inboundTraceId);
        parent.end();

        // 用入向头构造请求（真实链路就是客户端带 traceparent 进来）
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/article/list");
        request.setRequestURI("/api/article/list");
        request.addHeader("traceparent",
                "00-" + inboundTraceId + "-" + inboundSpanId + "-01");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, resp) -> {
        });

        String out = response.getHeader("Traceparent");
        assertThat(out).isNotNull();
        assertThat(out)
                .as("trace-id 必须沿用入向（server span 是入向 context 的子 span）")
                .startsWith("00-" + inboundTraceId + "-");
        assertThat(out)
                .as("span-id 必须是 server span 自己的（回显入向 span-id 是最常见的错误实现）")
                .doesNotContain(inboundSpanId);
    }

    /** 装一个**真的**全局 SDK（无 exporter ⇒ 不连任何网络）。 */
    private static void installGlobalSdk() {
        GoStyleOtelAgent.install(new GoStyleOtelConfig("traceparent-gate", null, "grpc", 1.0d));
    }
}
