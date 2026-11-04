package com.rag.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴🔴 <b>DRIFT-COMMON-V21 · F-15 的确定性守门</b>：
 * gRPC 之外的 HTTP 面上，<b>「HTTP 预算到点」与「业务失败收尾」同时到点</b>时，
 * <b>两条写路径不得把两个响应混成一个</b>。
 *
 * <h2>这条修的是什么</h2>
 * <pre>
 *   ① <b>容器线程</b>（预算耗尽）⇒ HttpMiddleware:441-445
 *        rec.markTimedOut(); workerFuture.cancel(true);
 *        rec.writeTimeoutResponseQuietly(503, httpReq);
 *        ⇒ 写 status=503 + Content-Type=text/plain + body "Request Timeout\n" + flush() ⇒ <b>commit</b>
 *   ② <b>worker 线程</b>（业务异常 ⇒ @ExceptionHandler）⇒ ObservabilityResponseWriter#flushBuffer
 *        original.flushBuffer();  ⇒ <b>commit</b>（此刻 body 还在捕获缓冲里 ⇒ wire 上是空的）
 *
 *   ⚠️ 两条路径都 commit ⇒ 客户端拿到哪个形状，取决于**谁先落定**：
 *        · ① 先 ⇒ 503 + text/plain + "Request Timeout\n"（干净）
 *        · ② 先 ⇒ 200 + application/json + <b>body 被 ① 追加成 "Request Timeout\n"</b>
 *                 ⇒ ⇒ <b>混血</b>：状态码与 Content-Type 是业务信封的，body 却是纯文本
 *                 ⇒ ⇒ <b>信封契约被打断</b>（客户端按 JSON 解会失败）
 * </pre>
 *
 * <h2>🔴 为什么用<b>确定性</b>的写法而不是「真 Tomcat + 卡下游」</h2>
 * <p>竞态窗口只有<b>亚毫秒级</b> ⇒ 真并发复现它<b>必然 flaky</b> ⇒ ⇒ 守门不能靠运气。
 * ⇒ ⇒ 本类<b>显式控制两个写者的先后</b>（先提交 or 先超时），把两种顺序各钉一遍
 * ⇒ ⇒ 每次跑都必现同一形状 ⇒ ⇒ 变异（拆掉「已提交就弃权」那道闸）⇒ <b>必红</b>。
 *
 * <h2>🔴 为什么响应替身要「仿 Tomcat 的 commit 语义」</h2>
 * <p>Spring 的 {@code MockHttpServletResponse} <b>不冻结</b>已提交响应的 status / Content-Type
 * ⇒ ⇒ 用它做断言会<b>看不到混血</b>（status 会被改成 503，看起来像正常超时）
 * ⇒ ⇒ 本类的替身按 Tomcat 真实语义实现：
 * <ul>
 *   <li>{@code commit}（= {@code flushBuffer()}）⇒ <b>冻结</b>状态码与 Content-Type；</li>
 *   <li>commit 之后再改 status / Content-Type ⇒ <b>无效</b>（Tomcat：头已上 wire）；</li>
 *   <li>commit 之后<b>仍可追加 body</b>（Tomcat 的输出流在 commit 后可写）⇒ ⇒ 这正是混血的成因。</li>
 * </ul>
 */
class HttpTimeoutRaceNoMixedResponseTest {

    /** 业务信封（B 侧形状：{@code {code,msg,data}}）。 */
    private static final String ENVELOPE = "{\"code\":5001,\"msg\":\"internal server error\",\"data\":null}";

    /** 超时响应体（B 侧 {@code http.Error} 的形状）。 */
    private static final String TIMEOUT_BODY = "Request Timeout\n";

    /** HTTP 预算：够短 ⇒ 测试快；够长 ⇒ worker 有时间提交。 */
    private static final Duration HTTP_BUDGET = Duration.ofMillis(500);

    // ── 形状一：worker 先提交、预算随后到点 ⇒ 不得混血 ──────────────────

    @Test
    @DisplayName("🔴 worker 已提交响应、随后 HTTP 预算到点 ⇒ 客户端拿到**干净的业务信封**"
            + "（修前：200 + application/json + body \"Request Timeout\\n\" ⇒ 混血）")
    void committedWorkerResponseMustNotBeMixedWithTimeoutBody() throws Exception {
        TomcatLikeResponse response = new TomcatLikeResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/thing");

        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> observedBody = new AtomicReference<>();

        middleware().doFilter(request, response, (req, res) -> {
            HttpServletResponse wrapper = (HttpServletResponse) res;
            try {
                // ① 模拟 Spring 的 @ExceptionHandler：先写信封（进**捕获缓冲**），再 flush（= commit）
                wrapper.setStatus(200);
                wrapper.setContentType("application/json");
                wrapper.getWriter().write(ENVELOPE);
                wrapper.flushBuffer();
                // ② 提交之后**继续占着 worker** ⇒ 容器线程的预算随后到点 ⇒ 走超时分支
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        observedBody.set(response.getContentAsString());

        // ① 状态码与 Content-Type：**commit 那一刻**就冻结了 ⇒ 客户端看到的是业务信封那套
        assertThat(response.wireStatus())
                .as("commit 之后状态码已上 wire ⇒ 超时分支改不了它")
                .isEqualTo(200);
        assertThat(response.wireContentType())
                .as("commit 之后 Content-Type 已上 wire ⇒ 超时分支改不了它")
                .isEqualTo("application/json");

        // ② 🔴 body：**绝不能**是超时的纯文本（那就是混血）⇒ 必须是业务信封
        assertThat(observedBody.get())
                .as("🔴 混血防御：worker 已提交 ⇒ 超时分支必须**完全弃权**"
                        + "（不得再写 status / CT / body）⇒ body 只能是业务信封")
                .doesNotContain("Request Timeout")
                .isEqualTo(ENVELOPE);

        release.countDown();
    }

    // ── 形状二（反向对照）：超时先落定 ⇒ 仍是干净的 503，且不得混进信封 ──

    @Test
    @DisplayName("✅ 反向对照：预算先到点（worker 还没提交）⇒ 仍是**干净的 503 纯文本**（优先级没被改）")
    void timeoutFirstStillYieldsClean503() throws Exception {
        TomcatLikeResponse response = new TomcatLikeResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/thing");

        CountDownLatch release = new CountDownLatch(1);

        middleware().doFilter(request, response, (req, res) -> {
            try {
                // 一直占着 worker ⇒ 预算先到点 ⇒ 容器线程写 503 并返回
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 预算已过 ⇒ 这时才提交 ⇒ **必须被丢弃**（markTimedOut 已置位）
            try {
                HttpServletResponse wrapper = (HttpServletResponse) res;
                wrapper.setStatus(200);
                wrapper.setContentType("application/json");
                wrapper.getWriter().write(ENVELOPE);
                wrapper.flushBuffer();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(response.wireStatus())
                .as("超时先落定 ⇒ 客户端拿到 503")
                .isEqualTo(503);
        assertThat(response.getContentAsString())
                .as("超时先落定 ⇒ body 是 B 侧的纯文本，且**不得**混进业务信封")
                .isEqualTo(TIMEOUT_BODY)
                .doesNotContain("5001");

        release.countDown();
    }

    // ── 装配辅助 ──────────────────────────────────────────────────────────

    private static HttpMiddleware middleware() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setHttpTimeout(HTTP_BUDGET);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                // 本类只关心**响应形状** ⇒ 观测读数不参与断言
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
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

    /**
     * 🔴 仿 Tomcat 的 <b>commit 语义</b>的响应替身。
     *
     * <p>Spring 的 {@code MockHttpServletResponse} <b>不冻结</b>已提交响应的 status / Content-Type
     * ⇒ ⇒ 用它断言会<b>看不到混血</b> ⇒ ⇒ 这里按 Tomcat 真实语义实现。
     *
     * <p>⚠️ <b>commit 的两个触发点都要覆盖</b>（少一个 ⇒ 断言会失真）：
     * <ul>
     *   <li>{@code flushBuffer()}（Spring 的 {@code @ExceptionHandler} 路径走这条）；</li>
     *   <li><b>{@code getWriter().flush()}</b>（共享层 {@code writeTimeoutResponse} 走这条
     *       —— Tomcat 的 {@code PrintWriter.flush()} ⇒ {@code OutputBuffer.flush()} ⇒
     *       <b>状态行与头就此上 wire</b> ⇒ commit）。</li>
     * </ul>
     */
    static class TomcatLikeResponse extends MockHttpServletResponse {

        private boolean committed = false;
        private int wireStatus = 200;
        private String wireContentType = null;

        @Override
        public boolean isCommitted() {
            return committed;
        }

        @Override
        public void flushBuffer() {
            super.flushBuffer();
            commit();
        }

        @Override
        public java.io.PrintWriter getWriter() throws java.io.UnsupportedEncodingException {
            final java.io.PrintWriter delegate = super.getWriter();
            return new java.io.PrintWriter(new java.io.Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) {
                    delegate.write(cbuf, off, len);
                }

                @Override
                public void flush() {
                    delegate.flush();
                    // 🔴 Tomcat：writer 的 flush ⇒ 输出流 flush ⇒ **状态行与头上 wire** ⇒ commit
                    commit();
                }

                @Override
                public void close() {
                    delegate.close();
                }
            });
        }

        @Override
        public void setStatus(int sc) {
            if (committed) {
                return;
            }
            super.setStatus(sc);
        }

        @Override
        public void setContentType(String type) {
            if (committed) {
                return;
            }
            super.setContentType(type);
        }

        /** commit 这一刻把状态码与 Content-Type <b>冻结</b>（= 已经上 wire）。 */
        private void commit() {
            if (committed) {
                return;
            }
            committed = true;
            wireStatus = getStatus();
            wireContentType = getContentType();
        }

        /** 客户端在 wire 上看到的状态码（= commit 那一刻的快照）。 */
        int wireStatus() {
            return wireStatus;
        }

        /** 客户端在 wire 上看到的 Content-Type（= commit 那一刻的快照）。 */
        String wireContentType() {
            return wireContentType;
        }
    }
}
