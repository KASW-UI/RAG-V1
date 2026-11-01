package com.rag.common.autoconfigure;

import com.rag.common.protection.RouteTemplateResolver;
import com.rag.common.protection.RouteTemplateSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <p><b>注册「注册期路由模板」解析器</b> —— 让共享层的 HTTP 熔断器
 * 能按 <b>Go 的隔离单位（每条路由一个 breaker）</b>分桶。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleBreakerRouteTemplateAutoConfiguration.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>为什么是自动配置而不是 {@code @Component}</b>：
 * 本类引用 {@link RequestMappingHandlerMapping}（Spring MVC）⇒ 而<b>10 个 rpc 组件没有 spring-webmvc</b>
 * ⇒ 若靠组件扫描，在那些进程里会<b>硬失败</b>（{@code NoClassDefFoundError}）
 * ⇒ 必须 {@code @ConditionalOnClass} 保护（AGENTS.md §3.3：注解存在 ≠ 机制生效，
 * 且 Boot 4 拆模块后硬失败与静默失效都要先实测）。
 *
 * <p><b>⚠️ 为什么必须独立成类、不能作为 {@code ProtectionAutoConfiguration} 的一个 {@code @Bean} 方法</b>：
 * {@code ProtectionAutoConfiguration} 本身<b>不</b>引用任何 Spring MVC 类型
 * ⇒ 它对<b>所有</b>进程（含 10 个 rpc 组件）都能加载；
 * <br>⇒ 而本类引用了 {@code RequestMappingHandlerMapping}
 * ⇒ ⇒ 若把它塞进 {@code ProtectionAutoConfiguration}，那个类的常量池里就会出现 MVC 类型
 * ⇒ ⇒ <b>rpc 进程加载 {@code ProtectionAutoConfiguration} 时就会 {@code NoClassDefFoundError}</b>
 * ⇒ ⇒ 独立成类 + 类级 {@code @ConditionalOnClass} ⇒ 不满足时<b>整个类都不加载</b>。
 *
 * <p><b>Go 出处</b>：{@code rest/engine.go:148-149}（每路由建一个 breaker）·
 * {@code rest/handler/breakerhandler.go:15,19}（键 = {@code METHOD://route.Path} 注册期模板）·
 * {@code rest/engine.go:244-268}（404 链里<b>没有</b> breaker）。
 */
@AutoConfiguration
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RouteTemplateAutoConfiguration {

    /**
     * 注册路由模板解析器。
     *
     * <p>⚠️ <b>刻意用惰性 supplier</b>：Filter 在 Tomcat 启动时构造，
     * 那时 DispatcherServlet 还没初始化 ⇒ 此刻实例化 {@code RequestMappingHandlerMapping}
     * 属于「提前触发」⇒ 惰性化后推迟到第一次请求。
     *
     * <p>⚠️ <b>为什么有 {@code @ConditionalOnMissingBean}</b>：域若自行提供了
     * {@link RouteTemplateSource} 实现 ⇒ 域的实现优先（与 Boot 的
     * 「用户自定义优先」惯例一致）。
     */
    @Bean
    @ConditionalOnMissingBean(RouteTemplateSource.class)
    public RouteTemplateSource routeTemplateSource(
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        return new RouteTemplateResolver(() -> handlerMappings.orderedStream().toList());
    }
}
