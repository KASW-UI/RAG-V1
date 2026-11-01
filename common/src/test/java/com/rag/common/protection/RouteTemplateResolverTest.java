package com.rag.common.protection;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §3.3「机制可证」守门：<b>路由模板解析真的能区分三态</b>
 * （TEMPLATE / NOT_MATCHED / UNKNOWN）—— 三态是「404 不进 breaker」与
 * 「解析不了要回落旧行为」这两条 B 侧 语义的地基。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code GoStyleRouteTemplateResolver}。
 *
 * <p>⚠️ <b>B 出处</b>：
 * <pre>
 *   rest/engine.go:244-268  notFoundHandler ⇒ 404 的链里**没有 breaker**
 *   rest/handler/breakerhandler.go:19 ⇒ 键 = METHOD://route.Path（注册期模板）
 * </pre>
 */
class RouteTemplateResolverTest {

    @Test
    @DisplayName("匹配上注解路由 ⇒ TEMPLATE（模板来自注册期，不是原始 URI）")
    void matchedRouteYieldsTemplate() {
        RouteTemplateResolver resolver = new RouteTemplateResolver(
                List.of(mappingOf("/article/{id}", new HandlerExecutionChain(handlerMethod()))));

        RouteResolution resolution = resolver.resolve(requestOf("GET", "/article/42"));

        assertThat(resolution.kind()).isEqualTo(RouteResolution.Kind.TEMPLATE);
        assertThat(resolution.template()).isEqualTo("/article/{id}");
    }

    @Test
    @DisplayName("没有任何 mapping 匹配上 ⇒ NOT_MATCHED（B 侧 404 不进 breaker）")
    void noMatchYieldsNotMatched() {
        RouteTemplateResolver resolver = new RouteTemplateResolver(
                List.of(mappingOf(null, null)));

        RouteResolution resolution = resolver.resolve(requestOf("GET", "/nope"));

        assertThat(resolution.kind()).isEqualTo(RouteResolution.Kind.NOT_MATCHED);
        assertThat(resolution.template()).isNull();
    }

    @Test
    @DisplayName("解析过程抛异常 ⇒ UNKNOWN（判不出来，不得当成未命中）")
    void exceptionYieldsUnknown() {
        RequestMappingHandlerMapping broken = mock(RequestMappingHandlerMapping.class);
        try {
            when(broken.getHandler(any())).thenThrow(new IllegalStateException("boom"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        RouteTemplateResolver resolver = new RouteTemplateResolver(List.of(broken));

        RouteResolution resolution = resolver.resolve(requestOf("GET", "/article/42"));

        assertThat(resolution.kind()).isEqualTo(RouteResolution.Kind.UNKNOWN);
    }

    @Test
    @DisplayName("匹配上的不是 HandlerMethod（如静态资源处理器）⇒ UNKNOWN")
    void nonHandlerMethodYieldsUnknown() {
        RouteTemplateResolver resolver = new RouteTemplateResolver(
                List.of(mappingOf("/static/**", new HandlerExecutionChain(new Object()))));

        RouteResolution resolution = resolver.resolve(requestOf("GET", "/static/a.js"));

        assertThat(resolution.kind()).isEqualTo(RouteResolution.Kind.UNKNOWN);
    }

    @Test
    @DisplayName("本进程没有 RequestMappingHandlerMapping ⇒ UNKNOWN（回落旧行为，不静默关掉熔断）")
    void noMappingsYieldsUnknown() {
        RouteTemplateResolver resolver = new RouteTemplateResolver(List.of());

        RouteResolution resolution = resolver.resolve(requestOf("GET", "/article/42"));

        assertThat(resolution.kind()).isEqualTo(RouteResolution.Kind.UNKNOWN);
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /**
     * 造一个 {@link RequestMappingHandlerMapping}：它的 {@code getHandler(...)}
     * 会像 {@code AbstractHandlerMapping} 那样把模板写进<b>请求属性</b>。
     *
     * @param template 匹配成功时写入的模板；{@code null} ⇒ 不匹配（返回 null chain）
     * @param chain    匹配成功时返回的链；{@code null} ⇒ 不匹配
     */
    private static RequestMappingHandlerMapping mappingOf(String template, HandlerExecutionChain chain) {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        try {
            when(mapping.getHandler(any())).thenAnswer(invocation -> {
                if (chain == null) {
                    return null;
                }
                HttpServletRequest request = invocation.getArgument(0);
                if (template != null) {
                    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
                }
                return chain;
            });
        } catch (Exception e) {
            // ⚠️ `AbstractHandlerMapping#getHandler` 声明 throws Exception ⇒ 这里就地收掉
            throw new IllegalStateException(e);
        }
        return mapping;
    }

    private static HandlerMethod handlerMethod() {
        return mock(HandlerMethod.class);
    }

    private static HttpServletRequest requestOf(String method, String uri) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
