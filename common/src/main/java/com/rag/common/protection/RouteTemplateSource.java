package com.rag.common.protection;

import jakarta.servlet.http.HttpServletRequest;

/**
 * <p><b>「注册期路由模板」解析能力的窄接口</b>。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleRouteTemplateSource.java}（抄写基准 = RAG，L2 语义逐条保留）。
 *
 * <p>⚠️ <b>为什么不直接让 Filter 依赖 {@link RouteTemplateResolver}</b>：
 * 那个实现类引用 {@code org.springframework.web.servlet.HandlerMapping}
 * ⇒ 没有 spring-webmvc 的进程（10 个 rpc 组件）<b>加载不了那个类</b>
 * ⇒ 而 Filter 的依赖注入点一旦写成 {@code ObjectProvider<RouteTemplateResolver>}，
 * Spring 为了判定 Bean 类型<b>仍会加载那个类</b> ⇒ {@code NoClassDefFoundError} ⇒ 硬失败。
 * <p>⇒ 抽出本接口后，Filter 只需认识「能解析出路由模板」这件事，
 * 具体实现（要不要 Spring MVC）由自动配置按 {@code @ConditionalOnClass} 决定。
 */
@FunctionalInterface
public interface RouteTemplateSource {

    /**
     * 解析这条请求对应的<b>注册期</b>路由模板。
     *
     * @param request 当前请求
     * @return 三态结果；{@link RouteResolution.Kind#UNKNOWN}
     *         ⇒ 调用方必须回落旧行为（按 raw URI 分桶、照常进 breaker）
     */
    RouteResolution resolve(HttpServletRequest request);
}
