package com.rag.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>断连探针的四态语义真的按 Go / RAG 的口径走</b>。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code GoStyleClientDisconnectProbe} 整段抄（L2 · D13）。
 *
 * <p>⚠️ <b>Tomcat 10.1 的反射链</b>：B 侧的取证基于 Tomcat 11 ⇒ 本仓（Tomcat 10.1.52）
 * 必须重测（审核报告 **F-7**）。本测试用**非 Tomcat** 的 {@code MockHttpServletRequest}
 * 断言「反射链取不到 ⇒ fail-open」（这是最关键的一条：不抛异常、不改变等待语义）。
 */
class ClientDisconnectProbeTest {

    @Test
    @DisplayName("非 HTTP/1.1 ⇒ UNAVAILABLE（HTTP/2 是二进制帧协议，写文本会破坏帧流）")
    void nonHttp11IsUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setProtocol("HTTP/2.0");
        assertThat(ClientDisconnectProbe.probe(request, new MockHttpServletResponse()))
                .isEqualTo(ClientDisconnectProbe.State.UNAVAILABLE);
    }

    @Test
    @DisplayName("响应已提交 ⇒ UNAVAILABLE（已提交 ⇒ 裸写会与 OutputBuffer 交错）")
    void committedResponseIsUnavailable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setProtocol("HTTP/1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("application/json");
        response.getOutputStream().write("{}".getBytes());
        response.flushBuffer();
        assertThat(response.isCommitted()).isTrue();

        assertThat(ClientDisconnectProbe.probe(request, response))
                .isEqualTo(ClientDisconnectProbe.State.UNAVAILABLE);
    }

    @Test
    @DisplayName("反射链取不到（非 Tomcat 容器）⇒ UNAVAILABLE（fail-open，绝不抛异常）")
    void nonTomcatIsUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setProtocol("HTTP/1.1");
        assertThat(ClientDisconnectProbe.probe(request, new MockHttpServletResponse()))
                .isEqualTo(ClientDisconnectProbe.State.UNAVAILABLE);
    }

    @Test
    @DisplayName("request 为 null ⇒ UNAVAILABLE")
    void nullRequestIsUnavailable() {
        assertThat(ClientDisconnectProbe.probe(null, new MockHttpServletResponse()))
                .isEqualTo(ClientDisconnectProbe.State.UNAVAILABLE);
    }

    @Test
    @DisplayName("1xx 行数预算用尽 ⇒ BUDGET_EXHAUSTED（机制可用，但本请求已写够了）")
    void exhaustedBudgetIsReported() {
        String previous = System.getProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
        System.setProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, "2");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.setProtocol("HTTP/1.1");
            // 预置「已写 2 行」⇒ 超过上限 ⇒ 预算用尽
            request.setAttribute("rag.common.http.disconnect-probe-lines", Integer.valueOf(2));
            assertThat(ClientDisconnectProbe.probe(request, new MockHttpServletResponse()))
                    .isEqualTo(ClientDisconnectProbe.State.BUDGET_EXHAUSTED);
            assertThat(ClientDisconnectProbe.probeLinesWritten(request)).isEqualTo(2);
        } finally {
            if (previous == null) {
                System.clearProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
            } else {
                System.setProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("上界配成 0 ⇒ 机制整体关闭 ⇒ UNAVAILABLE")
    void zeroMaxLinesDisablesProbe() {
        String previous = System.getProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
        System.setProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, "0");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.setProtocol("HTTP/1.1");
            assertThat(ClientDisconnectProbe.probe(request, new MockHttpServletResponse()))
                    .isEqualTo(ClientDisconnectProbe.State.UNAVAILABLE);
            assertThat(ClientDisconnectProbe.maxProbeLines()).isZero();
        } finally {
            if (previous == null) {
                System.clearProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
            } else {
                System.setProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("非法上界 ⇒ 回落默认 4（不抛异常）")
    void illegalMaxLinesFallsBack() {
        String previous = System.getProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
        System.setProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, "abc");
        try {
            assertThat(ClientDisconnectProbe.maxProbeLines())
                    .isEqualTo(ClientDisconnectProbe.DEFAULT_MAX_PROBE_LINES);
        } finally {
            if (previous == null) {
                System.clearProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
            } else {
                System.setProperty(ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("探测间隔：默认 500ms，下限 50ms（配更小 ⇒ 被抬到下限）")
    void probeIntervalIsClamped() {
        String previous = System.getProperty(ClientDisconnectProbe.PROBE_INTERVAL_PROPERTY);
        try {
            System.clearProperty(ClientDisconnectProbe.PROBE_INTERVAL_PROPERTY);
            assertThat(ClientDisconnectProbe.probeIntervalMillis())
                    .isEqualTo(ClientDisconnectProbe.DEFAULT_PROBE_INTERVAL_MILLIS);

            System.setProperty(ClientDisconnectProbe.PROBE_INTERVAL_PROPERTY, "1");
            assertThat(ClientDisconnectProbe.probeIntervalMillis())
                    .isEqualTo(ClientDisconnectProbe.MIN_PROBE_INTERVAL_MILLIS);

            System.setProperty(ClientDisconnectProbe.PROBE_INTERVAL_PROPERTY, "200");
            assertThat(ClientDisconnectProbe.probeIntervalMillis()).isEqualTo(200L);
        } finally {
            if (previous == null) {
                System.clearProperty(ClientDisconnectProbe.PROBE_INTERVAL_PROPERTY);
            } else {
                System.setProperty(ClientDisconnectProbe.PROBE_INTERVAL_PROPERTY, previous);
            }
        }
    }
}
