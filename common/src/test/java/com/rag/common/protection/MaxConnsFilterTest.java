package com.rag.common.protection;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>并发连接闸门真的在超限时立刻 503</b>（而不是排队）。
 *
 * <p>⚠️ <b>B 出处</b>：{@code rest/handler/maxconnshandler.go:12-36}
 * <pre>
 *   if latch.TryBorrow() { defer latch.Return(); next.ServeHTTP(w, r) }
 *   else { w.WriteHeader(http.StatusServiceUnavailable) }   // ← 裸 503，无 body
 * </pre>
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code GoStyleMaxConnsFilter} 整段抄（L2 · D13）。
 */
class MaxConnsFilterTest {

    @Test
    @DisplayName("闸门拿不到 ⇒ 裸 503（无 body、无 Content-Type）")
    void rejectsWithBare503WhenExhausted() throws Exception {
        ProtectionProperties props = new ProtectionProperties();
        props.setMaxConns(1);
        MaxConnsFilter filter = new MaxConnsFilter(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/article");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 先占住唯一的名额（用一个不返回的 chain 卡在 in-flight）
        FilterChain blocker = (req, res) -> { };
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                filter.doFilter(request, response, (req, res) -> {
                    try {
                        hold.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception ignore) {
                // 忽略：下面靠 interrupt 收尾
            }
        });        holder.start();
        // 等 holder 真的占住名额
        long deadline = System.currentTimeMillis() + 2000;
        while (filter.inFlight() == 0 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(filter.inFlight()).isEqualTo(1);

        // 第二个请求 ⇒ 超限
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/article"), rejected, blocker);

        assertThat(rejected.getStatus()).isEqualTo(503);
        assertThat(rejected.getContentAsByteArray()).isEmpty();
        assertThat(rejected.getContentType()).isNull();

        hold.countDown();
        holder.join(2000);
    }

    @Test
    @DisplayName("maxConns <= 0 ⇒ no-op（照抄 B 的短路）")
    void zeroMeansNoop() throws Exception {
        ProtectionProperties props = new ProtectionProperties();
        props.setMaxConns(0);
        MaxConnsFilter filter = new MaxConnsFilter(props);

        assertThat(filter.maxConns()).isZero();
        assertThat(filter.inFlight()).isZero();

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] called = {false};
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), response,
                (req, res) -> called[0] = true);

        assertThat(called[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("请求结束后名额归还（defer latch.Return）")
    void releasesPermitAfterChain() throws Exception {
        ProtectionProperties props = new ProtectionProperties();
        props.setMaxConns(2);
        MaxConnsFilter filter = new MaxConnsFilter(props);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, (req, res) -> { });

        assertThat(filter.inFlight()).isZero();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("默认值 = 10000（B 的 RestConf.MaxConns tag 默认）")
    void defaultIsTenThousand() {
        assertThat(new ProtectionProperties().getMaxConns()).isEqualTo(10000);
        assertThat(new MaxConnsFilter(new ProtectionProperties()).maxConns()).isEqualTo(10000);
    }
}
