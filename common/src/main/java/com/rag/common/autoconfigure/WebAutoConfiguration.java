package com.rag.common.autoconfigure;

import com.rag.common.communityfact.AuthorityHandler;
import com.rag.common.web.GetFormValuesFilter;
import com.rag.common.web.JsonBodyLimitFilter;
import com.rag.common.web.JsonContentTypeAdvice;
import com.rag.common.web.LimitMode;
import com.rag.common.web.PathNormalizingFilter;
import com.rag.common.web.TraceparentFilter;
import com.rag.common.web.ValueLimitInterceptor;
import jakarta.servlet.http.HttpServlet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>Web 层的共享装配点</b> —— 把「批 1 已落地、但因为 starter 化而没人注册」的那些
 * <b>web 层组件</b>重新接进容器。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 为什么必须有本类（D3 starter 化的直接后果）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>批 1 落的 {@code web/} 组件（{@link PathNormalizingFilter} / {@link JsonBodyLimitFilter} /
 * {@link TraceparentFilter} / {@link JsonContentTypeAdvice} / {@link ValueLimitInterceptor}）
 * 在 RAG 侧靠 <b>组件扫描</b> {@code com.rag.common} 注册。
 * <br>⇒ 而本仓的共享层是 <b>starter</b>（D3）⇒ <b>消费方不再扫 {@code com.rag.common}</b>
 * ⇒ ⇒ 这些组件<b>永远扫不到</b> ⇒ <b>静默失效</b>（Bean 不存在 ⇒ 零报错）。
 * <br>⇒ ⇒ 注册动作收进本 auto-config ⇒ <b>消费方零装配代码</b>。
 *
 * <p>⚠️ <b>「组件扫描形态」与「auto-config 形态」不重复注册</b>：
 * 这些类<b>保留</b> {@code @Component}（组件扫描形态兜底），本类用
 * {@code @ConditionalOnMissingBean} 注册 ⇒ 两种形态<b>同时存在也只会有一个实例</b>。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ D14 工具链回退的一处包名回改</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>RAG 侧（Boot 4）用的是 {@code org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory}
 * ⇒ <b>Boot 3.5 里这个类在 {@code org.springframework.boot.web.embedded.tomcat}</b>
 * ⇒ ⇒ 本类用 Boot 3.5 的包名 ⇒ 并用 {@code @ConditionalOnClass(name = …)} 守住
 * 「容器不是 Tomcat ⇒ 本条不装配」。
 *
 * <p><b>抄写基准</b>：RAG
 * {@code service/common/src/main/java/com/rag/common/http/GoStyleFormLimitWebMvcConfig.java}
 * ＋ {@code GoStyleTomcatParameterLimitCustomizer.java}
 * ＋ {@code service/common/src/main/java/com/rag/common/spring/AuthorityHandlerFactory.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）＋ <b>Boot 4 → 3.5 的包名回改</b>（L4 · D14）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebAutoConfiguration {

    /**
     * B 侧 {@code rest/httpx/util.go} 的 form 值计数上限对应的 <b>Tomcat</b> 侧上限。
     *
     * <p>⚠️ <b>为什么是 10001 而不是 2048</b>（照抄 RAG 的结论）：
     * Tomcat 的 {@code maxParameterCount} 默认 10000，计的是<b>参数总个数</b>；
     * 而 B 的 {@code net/url} 上限是 <b>query 段数</b>（{@code strings.Count(query, "&") + 1}）
     * ⇒ 两者口径差 1 ⇒ RAG 侧把 Tomcat 上限<b>抬到 10001</b> 让差值闭合
     * ⇒ ⇒ <b>「拒绝形状」由应用层的 {@link ValueLimitInterceptor} 负责</b>（它按 B 侧 口径判定）
     * ⇒ ⇒ 容器这一侧只负责「别比 B 侧 更早掐死」。
     */
    public static final int TOMCAT_MAX_PARAMETER_COUNT = 10001;

    // ── 三个 servlet filter ────────────────────────────────────────────────

    /**
     * 路径规范化 filter（B 的 {@code rest/httpx} 路径清洗）。
     *
     * <p>⚠️ 它必须<b>早于</b>路由匹配执行 ⇒ 注册为 Bean 即可（filter 的 order 由类上的
     * {@code @Order} 决定，本仓沿用批 1 落地时的取值）。
     */
    @Bean
    @ConditionalOnMissingBean
    public PathNormalizingFilter pathNormalizingFilter() {
        return new PathNormalizingFilter();
    }

    /**
     * JSON body 8 MiB 上限 filter（B 侧 {@code rest/httpx/requests.go:20-21} 的 {@code maxBodyLen}）。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonBodyLimitFilter jsonBodyLimitFilter() {
        return new JsonBodyLimitFilter();
    }

    /**
     * {@code traceparent} 透传 filter。
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceparentFilter traceparentFilter() {
        return new TraceparentFilter();
    }

    // ── ResponseBodyAdvice：JSON 响应的 Content-Type ────────────────────────

    /**
     * 让 JSON 响应的 {@code Content-Type} 与 B 侧 一致地带上 {@code charset=utf-8}。
     *
     * <p>⚠️ <b>它能被 Spring 认出来，靠的是类上的 {@code @RestControllerAdvice}</b>
     * ⇒ ⇒ {@code RequestMappingHandlerAdapter#initAdvice} 会把
     * 「既是 {@code @ControllerAdvice} 又是 {@code ResponseBodyAdvice}」的 bean 收进 advice 链
     * ⇒ ⇒ 这里<b>只要它是容器里的一个 bean</b>就生效（不需要再手工塞进 adapter）。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonContentTypeAdvice jsonContentTypeAdvice() {
        return new JsonContentTypeAdvice();
    }

    // ── form / query 参数闸门 ──────────────────────────────────────────────

    /**
     * form / query 参数闸门拦截器（按 {@code rag.http.go-form-limit.mode} 分叉拒绝形状）。
     *
     * <p>⚠️ <b>非法 mode 值在构造期就炸</b>（{@link LimitMode#parse(String)}）⇒
     * 「非法配置 ⇒ 进程起不来」⇒ 不会「静默装了个不生效的闸门」。
     */
    @Bean
    @ConditionalOnMissingBean
    public ValueLimitInterceptor valueLimitInterceptor(
            @Value("${" + LimitMode.PROPERTY + ":off}") String mode) {
        return new ValueLimitInterceptor(mode);
    }

    /**
     * 把闸门挂到 {@code HIGHEST_PRECEDENCE} ⇒ <b>先于域内鉴权</b>。
     *
     * <p>⚠️ <b>这与 B 的中间件顺序相反</b>（B 侧 form 限额在 native middleware 内、
     * 鉴权在其外）⇒ 已登记为<b>签字偏离</b>，不「顺手修回」。
     */
    @Bean
    @ConditionalOnMissingBean(name = "seaFormLimitMvcConfigurer")
    public WebMvcConfigurer seaFormLimitMvcConfigurer(ValueLimitInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }

    // ── Tomcat 参数上限 ────────────────────────────────────────────────────

    /**
     * 把 Tomcat 的 {@code maxParameterCount} 抬到 {@link #TOMCAT_MAX_PARAMETER_COUNT}。
     *
     * <p>⚠️ <b>用 {@code WebServerFactory} 而不是 {@code ConfigurableTomcatWebServerFactory} 做泛型</b>：
     * Boot 的 {@code WebServerFactoryCustomizerBeanPostProcessor} 会把**所有**
     * {@code WebServerFactoryCustomizer} 都套到当前容器上 ⇒ 泛型写窄了，换个容器就会在
     * 桥接方法里抛 {@code ClassCastException} ⇒ ⇒ 泛型宽、运行时再判。
     */
    @Bean
    @ConditionalOnMissingBean(name = "seaTomcatParameterLimitCustomizer")
    @ConditionalOnClass(name = "org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory")
    public WebServerFactoryCustomizer<WebServerFactory> seaTomcatParameterLimitCustomizer() {
        return factory -> {
            if (factory instanceof org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory tomcat) {
                tomcat.addConnectorCustomizers(connector ->
                        connector.setMaxParameterCount(TOMCAT_MAX_PARAMETER_COUNT));
            }
        };
    }

    // ── 内部事实查询端点的注册入口 ─────────────────────────────────────────

    /**
     * 内部事实查询端点的路径（与 B 侧版 {@code /internal/v1/community/facts} 1:1）。
     */
    public static final String AUTHORITY_PATH = "/internal/v1/community/facts";

    /**
     * 业务模块在自己的 {@code @Bean} 方法里调本工厂，注册内部事实查询端点。
     *
     * <p>⚠️ <b>为什么不能由本 auto-config 无参注册</b>：
     * {@link AuthorityHandler.AuthorityLookup} 是<b>业务特定</b>的（每个域的查法不同）
     * ⇒ 共享层拿不到 ⇒ ⇒ 只能提供工厂方法，由业务提供 lookup。
     *
     * <p><b>用法</b>：
     * <pre>{@code
     * @Bean
     * public ServletRegistrationBean<HttpServlet> authorityHandler(Config config, AuthorityLookup lookup) {
     *     return WebAutoConfiguration.registerAuthorityHandler(config.getProducer(), config.getToken(), lookup);
     * }
     * }</pre>
     *
     * @param producer 生产者标识（写入事实载荷）
     * @param token    访问令牌
     * @param lookup   业务特定的事实查询函数
     */
    public static ServletRegistrationBean<HttpServlet> registerAuthorityHandler(
            String producer,
            String token,
            AuthorityHandler.AuthorityLookup lookup) {
        HttpServlet servlet = AuthorityHandler.create(producer, token, lookup);
        ServletRegistrationBean<HttpServlet> bean = new ServletRegistrationBean<>(servlet);
        // ⚠️ Boot 3.5 用 addUrlMappings(...)（复数）—— 与 Boot 4 同名同参 ✔
        bean.addUrlMappings(AUTHORITY_PATH);
        return bean;
    }

    /**
     * B 的 form / query 段数上限常量（供域侧守门引用，避免各域各抄一份）。
     */
    public static int maxFormParamCount() {
        return GetFormValuesFilter.MAX_FORM_PARAM_COUNT;
    }

    /**
     * B 侧 {@code net/url} 的 query 段数上限常量。
     */
    public static int maxUrlQueryParams() {
        return GetFormValuesFilter.MAX_URL_QUERY_PARAMS;
    }
}
