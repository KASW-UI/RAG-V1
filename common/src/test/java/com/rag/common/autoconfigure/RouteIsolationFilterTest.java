package com.rag.common.autoconfigure;

import com.rag.common.protection.RouteResolution;
import com.rag.common.protection.RouteTemplateSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>熔断隔离 key 真的是 {@code METHOD://<路由模板>}</b>，
 * 且<b>失败判据真的是「状态码 ≥ 500」</b>。
 *
 * <p>⚠️ <b>B 出处</b>：
 * <pre>
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:48
 *       chn = chn.Append(handler.BreakerHandler(route.Method, route.Path, metrics))
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:26
 *       brk := breaker.NewBreaker(breaker.WithName(strings.Join([]string{method, path}, "://")))
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:36-39
 *       if cw.Code &lt; http.StatusInternalServerError { promise.Accept() } else { promise.Reject(...) }
 * </pre>
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code GoStyleBreakerFilter#routeKey} 的三态回落语义
 * （TEMPLATE ⇒ 模板；NOT_MATCHED ⇒ 跳过 breaker；UNKNOWN ⇒ <b>回落 raw URI</b>）。
 */
class RouteIsolationFilterTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtectionAutoConfiguration.class));

    @Test
    @DisplayName("隔离 key = METHOD://<注册期路由模板>（不是原始 URI）")
    void isolationKeyUsesRouteTemplate() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RouteTemplateSource.class, () -> templateSource("/article/{id}"))
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/article/42");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, okChain());

                    // ⚠️ 判据：resilience4j **真的**被要求了一个叫 "PUT:///article/{id}" 的实例
                    //    ⇒ 隔离单位是「每路由一个实例」，且模板来自注册期而不是原始 URI。
                    // ⚠️ **三个斜杠是对的**：B 的 key 是
                    //    `strings.Join([]string{method, path}, "://")`，而 `path` 本身以 "/" 开头
                    //    ⇒ B 侧同样是 "PUT:///article/:id" ⇒ 逐字对齐（不是笔误）。
                    assertThat(names(registry)).contains("PUT:///article/{id}");
                    assertThat(names(registry)).doesNotContain("PUT:///article/42");
                });
    }

    @Test
    @DisplayName("失败判据 = 状态码 ≥ 500（4xx 不算失败）")
    void onlyServerErrorsCountAsFailure() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RouteTemplateSource.class, () -> templateSource("/article"))
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    response.setStatus(404);
                    filter.doFilter(request, response, okChain());

                    CircuitBreaker breaker = registry.circuitBreaker("GET:///article");
                    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
                    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("5xx 计入失败（B 的 cw.Code >= 500）")
    void serverErrorCountsAsFailure() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RouteTemplateSource.class, () -> templateSource("/article"))
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    response.setStatus(500);
                    filter.doFilter(request, response, okChain());

                    CircuitBreaker breaker = registry.circuitBreaker("GET:///article");
                    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("V05 修法：模板判不出来（UNKNOWN）⇒ 回落 raw URI 分桶，**熔断仍生效**")
    void unknownTemplateFallsBackToRawUriAndBreakerStillApplies() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RouteTemplateSource.class, () -> request -> RouteResolution.UNKNOWN)
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nope");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, okChain());

                    // ⚠️ **与修复前相反**：修复前「模板取不到 ⇒ 放行、不熔断」⇒ 熔断被静默关掉
                    //    ⇒ 与 RAG 的 `GoStyleBreakerFilter#routeKey`（回落 raw URI）语义相反。
                    //    现断言：仍然建 breaker（隔离单位退化成「每 URI 一个」），且请求已放行到业务链。
                    assertThat(names(registry)).containsExactly("GET:///nope");
                    CircuitBreaker breaker = registry.circuitBreaker("GET:///nope");
                    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("确定未命中（NOT_MATCHED）⇒ 本层完全不参与：不建 breaker、不计数（B 的 404 链没有 breaker）")
    void notMatchedSkipsBreakerEntirely() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RouteTemplateSource.class, () -> request -> RouteResolution.NOT_MATCHED)
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nope");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    boolean[] called = {false};
                    filter.doFilter(request, response, (req, res) -> called[0] = true);

                    assertThat(called[0]).isTrue();
                    // ⚠️ 与 UNKNOWN 的差别就在这里：NOT_MATCHED ⇒ **连 breaker 都不建**
                    assertThat(names(registry)).isEmpty();
                });
    }

    @Test
    @DisplayName("没有 RouteTemplateSource（判不出来）⇒ 回落 raw URI，熔断仍生效")
    void absentTemplateSourceFallsBackToRawUri() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/article/7");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, okChain());

                    assertThat(names(registry)).containsExactly("GET:///article/7");
                });
    }

    /** resilience4j registry 里已建实例的名字集合（registry 没有 circuitBreakerNames()）。 */
    private static java.util.Set<String> names(CircuitBreakerRegistry registry) {
        return registry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /** 恒定返回同一个模板的 {@link RouteTemplateSource}。 */
    private static RouteTemplateSource templateSource(String template) {
        return request -> RouteResolution.template(template);
    }

    private static FilterChain okChain() {
        return (req, res) -> {
            // 什么也不做 ⇒ 响应保持调用方设定的状态码
        };
    }
}
