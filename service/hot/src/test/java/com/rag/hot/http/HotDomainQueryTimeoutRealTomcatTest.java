package com.rag.hot.http;

import com.rag.hot.service.HotRankingQueryService;
import com.rag.hot.support.HotRealStackTestBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * <p><b>域内查询方向的 2s 出向上界</b>的守护测试（真 Tomcat + 真 Spring 上下文）。</p>
 *
 * <h2>🔴 为什么要有这条</h2>
 * <p>B 侧这条方向走 gRPC ⇒ 共享层按 {@code cap-millis = 2000} 封顶 ⇒
 * <b>Redis 卡住时 2s 就失败</b> ⇒ hot-api 走 fatal ⇒ <b>HTTP 200 + 5001 业务信封</b>。</p>
 * <p>本仓 D2 域内直连 ⇒ <b>不经过 gRPC ⇒ 共享层那个封顶管不到它</b>
 * ⇒ ⇒ Redis 要等到自己的 socket timeout（3s）才抛，而 HTTP 请求预算<b>也是 3s</b>
 * ⇒ ⇒ 两者同时到点 ⇒ 落进共享层的「HTTP 预算 × 业务失败」竞态（F-15）
 * ⇒ ⇒ 客户端可能拿到 <b>503 + 纯文本</b>（<b>不是</b>业务信封）⇒ ⇒ 与 B 侧形状不同。</p>
 * <p>⇒ ⇒ 本类锁死「<b>上界生效 ⇒ 失败形状回到 B 侧</b>」这条契约。</p>
 *
 * <h2>⚠️ 为什么下游用 mock 而不是卡真 Redis</h2>
 * <p>本域的真 Redis 是<b>整个 JVM 共用的单例容器</b> ⇒ 用 {@code CLIENT PAUSE} 卡它
 * ⇒ ⇒ <b>同一 JVM 内并行跑的其它测试类</b>（合并 / 快照 / 真 Tomcat）会一起被阻塞
 * ⇒ ⇒ 变成「测一条用例、污染一批用例」。</p>
 * <p>⇒ ⇒ 本类把「下游慢」用<b>替身</b>制造（{@link HotRankingQueryService} 的替身），
 * 而<b>被测对象是域内那条「超时 → fatal → 信封」的控制流</b>
 * （超时机制用的是 JDK 的 {@code CompletableFuture#orTimeout}，不是被 mock 掉的部分）
 * ⇒ ⇒ 断言仍然是真的。</p>
 * <p>⚠️ <b>双侧活体的最终证据在 {@code tools/difftest/hot/run.sh} 的 M5 面</b>：
 * 那里卡的是<b>两侧各自独立的</b>真 Redis ⇒ 比的是<b>真实失败形状 + 真实时延</b>。</p>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-api/src/main/resources/application.yml:154-171}
 *       （{@code rag.protection.outbound-deadline.cap-millis: 2000}）；</li>
 *   <li>B 侧 {@code service/hot/hot-api/.../api/service/HotArticleQueryService.java:130-134}
 *       （出向失败 ⇒ {@code observeRpcError} ＋ {@code throw fatal}）。</li>
 * </ul>
 */
class HotDomainQueryTimeoutRealTomcatTest extends HotRealStackTestBase {

    /** 下游「慢」的时长：必须**大于**域内上界（2s）且**小于** HTTP 请求预算（3s）。 */
    private static final long SLOW_DOWNSTREAM_MILLIS = 2_500L;

    /** 域内上界（B 侧 cap-millis 同值）。 */
    private static final long DOMAIN_QUERY_TIMEOUT_MILLIS = 2_000L;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 把域内查询的下游换成「慢」的替身。
     *
     * <p>⚠️ {@code @MockitoBean} 会<b>改变 Spring 上下文缓存键</b> ⇒ 本类会另起一个上下文
     * ⇒ ⇒ 不会影响其它测试类用的那个共享上下文。</p>
     */
    @MockitoBean
    private HotRankingQueryService rankingQuery;

    @Test
    @DisplayName("域内查询超过 2s 上界 ⇒ **200 + {code:5001,…}**（不是 503 纯文本）＋ rpc_error 指标 +1")
    void domainQueryTimeoutYieldsBusinessEnvelope() throws Exception {
        when(rankingQuery.getHotArticles(anyInt())).thenAnswer(invocation -> {
            Thread.sleep(SLOW_DOWNSTREAM_MILLIS);
            return List.of();
        });

        double before = rpcErrorCount();

        long start = System.nanoTime();
        String raw = rawHttp("GET /hot/v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // ① 状态码：必须是 200（B 侧 go-zero 的 SetErrorHandler 同款），**不是** 503
        assertThat(finalStatusLineOf(raw)).as("超上界也必须是 HTTP 200；实际状态行：%s", finalStatusLineOf(raw))
                .startsWith("HTTP/1.1 200");

        // ② body：必须是**业务信封**（B 侧逐字），**不是**纯文本
        assertThat(decodedBodyOf(raw).trim())
                .as("超上界的失败形状必须与 B 侧一致（200 + 5001 业务信封）")
                .isEqualTo("{\"code\":5001,\"msg\":\"internal server error\",\"data\":null}");

        // ③ 时延：必须**早于** HTTP 请求预算（3s）⇒ 证明是域内上界（2s）而不是等 HTTP 预算到期
        assertThat(elapsedMillis)
                .as("失败时延应落在「域内上界 2s」附近，而不是「HTTP 预算 3s」；实测 %d ms", elapsedMillis)
                .isGreaterThanOrEqualTo(DOMAIN_QUERY_TIMEOUT_MILLIS - 200L)
                .isLessThan(2_800L);

        // ④ 指标：与 B 侧一致（出向失败记在 hot_rpc 这个 callee 名下）
        assertThat(rpcErrorCount())
                .as("出向失败必须记 hot_api_rpc_error_total{callee=hot_rpc_get_hot_articles}")
                .isEqualTo(before + 1.0d);
    }

    @Test
    @DisplayName("域内查询在 2s 内完成 ⇒ **正常 200 业务信封**（上界不误伤正常路径）")
    void fastDomainQueryIsNotAffectedByTimeout() throws Exception {
        when(rankingQuery.getHotArticles(anyInt())).thenReturn(List.of());

        String raw = rawHttp("GET /hot/v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");

        assertThat(finalStatusLineOf(raw)).startsWith("HTTP/1.1 200");
        assertThat(decodedBodyOf(raw)).contains("\"code\":200");
    }

    /**
     * 读 {@code hot_api_rpc_error_total{callee="hot_rpc_get_hot_articles"}} 的计数值。
     *
     * @return 计数值（指标还没出现时返回 0）
     */
    private double rpcErrorCount() {
        Counter counter = meterRegistry.find("hot_api_rpc_error_total")
                .tag("callee", "hot_rpc_get_hot_articles").counter();
        return counter == null ? 0d : counter.count();
    }
}
