package com.rag.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>HTTP 中间件的三条拒绝路径真的按 B 的形状走</b>。
 *
 * <p>⚠️ <b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java}
 * <pre>
 *   case &lt;-ctx.Done():
 *       statusCode := http.StatusServiceUnavailable
 *       if errors.Is(ctx.Err(), context.Canceled) { statusCode = httpClientClosedRequest }  // 499
 *       MarkTimeout(ctx, ctx.Err(), duration, httpAttrs(...))
 *       http.Error(w, httpTimeoutBody, statusCode)
 * </pre>
 * ⇒ ⇒ 超时 = **503 + body "Request Timeout\n"**；断连 = **499**；池饱和 = **裸 503**。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code HttpMiddleware} 整段抄（L2 · D13）。
 */
class HttpMiddlewareTest {

    // ── 形状一：超时 503（带 body） ───────────────────────────────────

    @Test
    @DisplayName("超时 ⇒ 503 + body \"Request Timeout\\n\" + text/plain + nosniff + Content-Length 16")
    void timeoutYieldsGoShaped503() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        HttpMiddleware middleware = middleware(registry, Duration.ofMillis(200));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CountDownLatch release = new CountDownLatch(1);
        middleware.doFilter(request, response, (req, res) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("Request Timeout\n");
        assertThat(response.getContentType()).contains("text/plain");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        // ⚠️ B 的 http.Error → fmt.Fprintln ⇒ Content-Length = len("Request Timeout\n") = 16
        //    （"Request Timeout" 15 字符 + '\n' 1 字节；RAG 侧注释写的「15」是笔误，代码是 +1）
        assertThat(response.getHeader("Content-Length")).isEqualTo("16");

        // 观测：timeout 分类
        Observation.Context ctx = captured.get();
        assertThat(ctx).isNotNull();
        assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("timeout");
        assertThat(ctx.getLowCardinalityKeyValue("sea.timeout").getValue()).isEqualTo("true");

        release.countDown();
    }

    // ── 形状二：客户端断连 499 ─────────────────────────────────────────

    @Test
    @DisplayName("客户端断连（ClientAbort 类异常）⇒ 499（不是 500）")
    void clientAbortYields499() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        HttpMiddleware middleware = middleware(registry, Duration.ofSeconds(5));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();

        middleware.doFilter(request, response, (req, res) -> {
            throw new ClientAbortException("broken pipe");
        });

        assertThat(response.getStatus()).isEqualTo(499);
        assertThat(response.getContentAsString()).isEqualTo("Request Timeout\n");

        Observation.Context ctx = captured.get();
        assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("timeout");
    }

    // ── 形状三：池饱和 ⇒ 裸 503（无 body、无 Content-Type） ─────────────

    @Test
    @DisplayName("worker 池饱和 ⇒ 裸 503（无 body、无 Content-Type）")
    void saturatedPoolYieldsBare503() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        HttpMiddleware middleware = middleware(registry, Duration.ofSeconds(5));

        // 把池占满：512 个 worker 全部卡住
        CountDownLatch hold = new CountDownLatch(1);
        Thread[] fillers = new Thread[HttpMiddleware.MAX_HTTP_WORKERS];
        for (int i = 0; i < fillers.length; i++) {
            fillers[i] = new Thread(() -> {
                try {
                    HttpMiddleware.httpExecutor().submit(() -> {
                        try {
                            hold.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (RuntimeException ignore) {
                    // 池已满 ⇒ 忽略
                }
            });
            fillers[i].start();
        }
        try {
            // 等池真的被占满
            long deadline = System.currentTimeMillis() + 5000;
            while (HttpMiddleware.httpExecutor().getActiveCount() < HttpMiddleware.MAX_HTTP_WORKERS
                    && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
            MockHttpServletResponse response = new MockHttpServletResponse();
            middleware.doFilter(request, response, (req, res) -> { });

            assertThat(response.getStatus()).isEqualTo(503);
            // ⚠️ 裸 503 ⇒ 无 body、无 Content-Type（刻意与「超时 503」不同）
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(response.getContentType()).isNull();
        } finally {
            hold.countDown();
            for (Thread t : fillers) {
                t.join(3000);
            }
        }
    }

    // ── 其它语义 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("无超时 ⇒ 同线程跑完 + 正常响应")
    void noTimeoutRunsInline() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        HttpMiddleware middleware = middleware(registry, null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();
        middleware.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpRes = (HttpServletResponse) res;
            httpRes.setStatus(200);
            httpRes.setContentType("application/json");
            httpRes.getOutputStream().write("{\"code\":200,\"msg\":\"ok\"}".getBytes());
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":200");
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind")).isNull();
    }

    @Test
    @DisplayName("SSE / WebSocket ⇒ 跳过超时中间件（照抄 B 的 shouldSkipHTTPTimeout）")
    void sseAndWebsocketSkipTimeout() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        HttpMiddleware middleware = middleware(registry, Duration.ofMillis(100));

        MockHttpServletRequest sse = new MockHttpServletRequest("GET", "/sse");
        sse.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse sseResponse = new MockHttpServletResponse();
        CountDownLatch release = new CountDownLatch(1);
        middleware.doFilter(sse, sseResponse, (req, res) -> {
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // ⚠️ 跳过超时 ⇒ 不该被超时 503
        assertThat(sseResponse.getStatus()).isEqualTo(200);
        release.countDown();

        MockHttpServletRequest ws = new MockHttpServletRequest("GET", "/ws");
        ws.addHeader("Upgrade", "websocket");
        MockHttpServletResponse wsResponse = new MockHttpServletResponse();
        middleware.doFilter(ws, wsResponse, (req, res) -> { });
        assertThat(wsResponse.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("worker 池上限 = 512（线程名 obs-http-worker）")
    void workerPoolShape() throws Exception {
        assertThat(HttpMiddleware.maxWorkers()).isEqualTo(512);
        assertThat(HttpMiddleware.MAX_HTTP_WORKERS).isEqualTo(512);
        String threadName = HttpMiddleware.httpExecutor()
                .submit(() -> Thread.currentThread().getName())
                .get(5, TimeUnit.SECONDS);
        assertThat(threadName).isEqualTo("obs-http-worker");
    }

    @Test
    @DisplayName("池被终止后 ⇒ 下一次使用按需重建（否则每个请求都变成 503）")
    void poolIsRebuiltAfterShutdown() {
        HttpMiddleware middleware = middleware(registry(new AtomicReference<>()), null);
        middleware.shutdown();
        assertThat(HttpMiddleware.poolTerminated()).isTrue();

        assertThat(HttpMiddleware.httpExecutor().isShutdown()).isFalse();
        middleware.shutdown();
    }

    @Test
    @DisplayName("信封解析：解析失败 / 空 body ⇒ 当作「没有信封」")
    void envelopeParsingFallsBackToNull() {
        assertThat(HttpMiddleware.parseEnvelope(null)).isNull();
        assertThat(HttpMiddleware.parseEnvelope(new byte[0])).isNull();
        assertThat(HttpMiddleware.parseEnvelope("not json".getBytes())).isNull();
        // ⚠️ code=0 且 msg 为空 ⇒ 「不存在」
        assertThat(HttpMiddleware.parseEnvelope("{\"code\":0,\"msg\":\"\"}".getBytes())).isNull();
        assertThat(HttpMiddleware.parseEnvelope("{\"code\":1001,\"msg\":\"x\"}".getBytes())).isNotNull();
    }

    @Test
    @DisplayName("业务码优先于状态码（200 + 业务信封 ⇒ business）")
    void businessEnvelopeWinsOverStatus() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        HttpMiddleware middleware = middleware(registry, null);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();
        middleware.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpRes = (HttpServletResponse) res;
            httpRes.setContentType("application/json");
            httpRes.getOutputStream().write("{\"code\":1001,\"msg\":\"dup user\"}".getBytes());
        });

        Observation.Context ctx = captured.get();
        assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("business");
        assertThat(ctx.getHighCardinalityKeyValue("sea.biz_code").getValue()).isEqualTo("1001");
    }

    @Test
    @DisplayName("探针判定断连 ⇒ 499（不等超时，直接写 499）")
    void probeDetectedDisconnectYields499() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setHttpTimeout(Duration.ofSeconds(10));
        HttpMiddleware middleware = new HttpMiddleware(provider(registry), properties, "test-service",
                (req, resp) -> ClientDisconnectProbe.State.DISCONNECTED);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CountDownLatch release = new CountDownLatch(1);
        middleware.doFilter(request, response, (req, res) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(response.getStatus()).isEqualTo(499);
        assertThat(response.getContentAsString()).isEqualTo("Request Timeout\n");
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind").getValue())
                .isEqualTo("timeout");
        release.countDown();
    }

    @Test
    @DisplayName("探针返回 ALIVE / UNAVAILABLE ⇒ 不影响等待语义（fail-open）")
    void probeAliveDoesNotShortCircuit() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setHttpTimeout(Duration.ofSeconds(10));
        HttpMiddleware middleware = new HttpMiddleware(provider(registry), properties, "test-service",
                (req, resp) -> ClientDisconnectProbe.State.ALIVE);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();
        middleware.doFilter(request, response, (req, res) -> {
            try {
                res.getOutputStream().write("{\"code\":200,\"msg\":\"ok\"}".getBytes());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });

        // ⚠️ 探针没判定断连 ⇒ 走正常路径 ⇒ 业务响应原样返回（不是 499）
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":200");
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    private static HttpMiddleware middleware(ObservationRegistry registry, Duration httpTimeout) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setHttpTimeout(httpTimeout);
        return new HttpMiddleware(provider(registry), properties, "test-service");
    }

    private static ObjectProvider<ObservationRegistry> provider(ObservationRegistry registry) {
        return new ObjectProvider<ObservationRegistry>() {
            @Override
            public ObservationRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public ObservationRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public ObservationRegistry getIfUnique() {
                return registry;
            }
        };
    }

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

    /** 模拟 Tomcat 的 {@code ClientAbortException}（名字必须含 "ClientAbort"）。 */
    static class ClientAbortException extends java.io.IOException {
        ClientAbortException(String message) {
            super(message);
        }
    }
}
