package com.rag.common.autoconfigure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §3.3「机制可证」守门：<b>熔断隔离 key 真的是 {@code METHOD://<路由模板>}</b>，
 * 且<b>失败判据真的是「状态码 ≥ 500」</b>。
 *
 * <p>⚠️ <b>Go 出处</b>：
 * <pre>
 *   go-zero@v1.10.2/rest/engine.go:149
 *       chn = chn.Append(handler.BreakerHandler(route.Method, route.Path, metrics))
 *   go-zero@v1.10.2/rest/handler/breakerhandler.go:18-19
 *       brk := breaker.NewBreaker(breaker.WithName(strings.Join([]string{method, path}, "://")))
 *   go-zero@v1.10.2/rest/handler/breakerhandler.go:36-42
 *       if cw.Code &lt; http.StatusInternalServerError { promise.Accept() } else { promise.Reject(...) }
 * </pre>
 */
class RouteIsolationFilterTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtectionAutoConfiguration.class));

    @Test
    @DisplayName("隔离 key = METHOD://<注册期路由模板>（不是原始 URI）")
    void isolationKeyUsesRouteTemplate() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RequestMappingHandlerMapping.class, () -> mappingOf("/article/{id}"))
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/article/42");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, okChain());

                    // ⚠️ 判据：resilience4j **真的**被要求了一个叫 "PUT:///article/{id}" 的实例
                    //    ⇒ 隔离单位是「每路由一个实例」，且模板来自注册期而不是原始 URI。
                    // ⚠️ **三个斜杠是对的**：Go 的 key 是
                    //    `strings.Join([]string{method, path}, "://")`，而 `path` 本身以 "/" 开头
                    //    ⇒ Go 侧同样是 "PUT:///article/:id" ⇒ 逐字对齐（不是笔误）。
                    assertThat(names(registry)).contains("PUT:///article/{id}");
                    assertThat(names(registry)).doesNotContain("PUT:///article/42");
                });
    }

    @Test
    @DisplayName("失败判据 = 状态码 ≥ 500（4xx 不算失败）")
    void onlyServerErrorsCountAsFailure() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RequestMappingHandlerMapping.class, () -> mappingOf("/article"))
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
    @DisplayName("5xx 计入失败（Go 的 cw.Code >= 500）")
    void serverErrorCountsAsFailure() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RequestMappingHandlerMapping.class, () -> mappingOf("/article"))
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
    @DisplayName("路由模板取不到时**放行**（绝不静默关掉熔断保护，也不按原始 URI 退化隔离）")
    void unresolvedTemplatePassesThrough() throws Exception {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        runner
                .withBean(CircuitBreakerRegistry.class, () -> registry)
                .withBean(RequestMappingHandlerMapping.class, () -> mappingOf(null))
                .run(context -> {
                    ProtectionAutoConfiguration.RouteIsolationFilter filter =
                            context.getBean(ProtectionAutoConfiguration.RouteIsolationFilter.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nope");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    boolean[] called = {false};
                    filter.doFilter(request, response, (req, res) -> {
                        called[0] = true;
                    });

                    assertThat(called[0]).isTrue();
                    assertThat(names(registry)).isEmpty();
                });
    }

    /** resilience4j registry 里已建实例的名字集合（registry 没有 circuitBreakerNames()）。 */
    private static java.util.Set<String> names(CircuitBreakerRegistry registry) {
        return registry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /**
     * 造一个 {@link RequestMappingHandlerMapping}：它的 {@code getHandler(...)}
     * 会像 {@code AbstractHandlerMapping} 那样把模板写进<b>请求属性</b>。
     */
    private static RequestMappingHandlerMapping mappingOf(String template) {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        try {
            when(mapping.getHandler(any())).thenAnswer(invocation -> {
                jakarta.servlet.http.HttpServletRequest request = invocation.getArgument(0);
                if (template != null) {
                    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
                }
                return new HandlerExecutionChain(new Object());
            });
        } catch (Exception e) {
            // ⚠️ `AbstractHandlerMapping#getHandler` 声明 throws Exception ⇒ 这里就地收掉
            throw new IllegalStateException(e);
        }
        return mapping;
    }

    private static FilterChain okChain() {
        return (req, res) -> {
            // 什么也不做 ⇒ 响应保持调用方设定的状态码
        };
    }
}
