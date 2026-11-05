package com.rag.favorite.config;

import com.rag.favorite.interceptor.FavoriteJwtAuthInterceptor;
import com.rag.favorite.interceptor.QueryLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.accept.FixedContentNegotiationStrategy;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>favorite 域的 MVC 装配</b> —— 对应 B 侧
 * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/config/WebMvcConfig.java:38-133}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <p><b>抄写基准</b>：B 侧同名配置类。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>包名压平</b>：{@code com.rag.favorite.favorite.api.config} ⇒
 *           {@code com.rag.favorite.config}（D7）。</li>
 *       <li><b>少一个 Jackson customizer</b>：B 侧注册了
 *           {@code @Bean JsonMapperBuilderCustomizer goStyleCoercionCustomizer()}
 *           （{@code WebMvcConfig.java:73-76}）⇒ C 侧<b>不需要</b>：
 *           共享层 {@code common/autoconfigure/JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer}
 *           （{@code JsonBodyConverterAutoConfiguration.java:78-82}）已经把<b>同一份</b>
 *           {@code Coercion} 规则施加到 Boot 的 mapper 上
 *           ⇒ ⇒ <b>域内再注册一份就是重复</b>。
 *           <br>⚠️ 与 like 域的差别：like 域<b>有</b>自己额外的 2 条强转补强
 *           （{@code LogicalType.Integer ← String = Fail} / {@code Float ← String = Fail}）
 *           ⇒ 才需要域内 customizer；favorite 域<b>没有</b>域内补强
 *           （B 侧 favorite 的 {@code WebMvcConfig} 只调共享层的 {@code GoStyleCoercion::applyTo}）
 *           ⇒ ⇒ 照抄 B 侧的「只调共享层」这一半即可。</li>
 *       <li><b>Jackson 3 → Jackson 2</b>（D14 工具链）：B 侧的
 *           {@code org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer}
 *           在 Boot 3.5 上是
 *           {@code org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer}；
 *           但由于上一条（不注册域内 customizer），本类<b>不需要</b>引用它。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 两条拦截器的注册路径与次序、关闭响应侧内容协商的写法逐条未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 路径注册与 Go 端逐条对应</h2>
 * <pre>
 *   Go  : server.AddRoutes(
 *           rest.WithMiddlewares([]rest.Middleware{serverCtx.CheckBlacklistMiddleware},
 *                               []rest.Route{ 7 个路由 }),
 *           rest.WithJwt(serverCtx.Config.UserAuth.AccessSecret),
 *           rest.WithPrefix("/favorite/v1"))
 * </pre>
 *
 * <h3>⚠️ 为什么只有两条拦截器、且没有 excludePathPatterns</h3>
 * <p>Go 侧 favorite api <b>只注册过一段路由表</b>，7/7 全量 {@code rest.WithJwt}，
 * <b>不存在 public 路径</b>（对照 users 域的 user/admin api：那边有 login / create 两条 public 段，
 * 本域一个都没有）。因此这里不需要 exclude —— 加了反而凭空制造分歧。
 * <br>⚠️ C 侧空壳 javadoc 写着「<b>有 public 段</b>」⇒ <b>那是错的</b>，本实现以 B 侧为准。
 *
 * <h3>⚠️ 一个必须显式说明的表达层差异</h3>
 * <p>Go 的 {@code rest.WithPrefix("/favorite/v1")} 与 {@code /folder/create} 拼接出的是精确路径
 * {@code /favorite/v1/folder/create}；而 Spring 的 {@code /favorite/v1/**} 是<b>前缀通配</b>，
 * 会覆盖 {@code /favorite/v1/ 下任何路径}。因为本服务的 7 个 Controller 端点都挂在
 * {@code @RequestMapping("/favorite/v1")} 下、且全部需要鉴权，两者覆盖集合等价。
 * 真正多出来的只有「未注册的子路径」，那些会由 DispatcherServlet 返回
 * {@code NoResourceFoundException} → 404（见 {@code FavoriteApiExceptionHandler}），
 * 而 Go 的 router 同样不匹配 → 404，两侧一致。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Go 侧路由前缀（{@code rest.WithPrefix("/favorite/v1")}，
     *     {@code service/favorite/api/internal/handler/routes.go:58}）。 */
    private static final String FAVORITE_PREFIX = "/favorite/v1/**";

    private final FavoriteJwtAuthInterceptor favoriteJwtAuthInterceptor;

    private final QueryLimitInterceptor queryLimitInterceptor;

    public WebMvcConfig(FavoriteJwtAuthInterceptor favoriteJwtAuthInterceptor,
                        QueryLimitInterceptor queryLimitInterceptor) {
        this.favoriteJwtAuthInterceptor = favoriteJwtAuthInterceptor;
        this.queryLimitInterceptor = queryLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ⚠️ order 默认 0 ⇒ 鉴权**最先**跑
        //    （B 侧 rest.WithJwt 是中间件，httpx.Parse 在 handler 内部 ⇒ 解析在鉴权之后）。
        registry.addInterceptor(favoriteJwtAuthInterceptor)
                .addPathPatterns(FAVORITE_PREFIX);

        // 🔴 Go 的 query 判据闸门（net/url 的 defaultMaxParams = 10000 ＋ 分号 ＋ 非法转义
        //    ＋ go-zero 的 maxFormParamCount = 2048 非空值计数）—— **7/7 端点都要过**
        //    （Go 侧 7 个 handler 的第一行都是 httpx.Parse ⇒ 逐条见 QueryLimitInterceptor 的类注释）
        // ⇒ order = 1 ⇒ **排在鉴权（order = 0）之后**
        // ⇒ ⇒ 理由：Go 的 `httpx.Parse` 在 **handler 内部** ⇒ 解析在鉴权之后
        // ⇒ ⇒ ⇒ 无 token 时 Go 给 401，本实现也必须给 401（不能提前变成 5003）
        // ⇒ ⇒ ⚠️ 若把它排到鉴权之前 ⇒ 就制造出「先解析后鉴权」的偏离
        //      （共享层那道 form 闸门就是这么排的，见 ValueLimitInterceptor —— 那条偏离已登记）。
        registry.addInterceptor(queryLimitInterceptor)
                .addPathPatterns(FAVORITE_PREFIX)
                .order(1);
    }

    /**
     * <b>关掉响应侧的内容协商</b> —— 对齐 Go 的 {@code httpx.OkJsonCtx}。
     *
     * <p><b>Go 出处</b>：{@code rest/httpx/responses.go:175-183} 的 {@code doWriteJson}
     * <pre>
     *   bs, err := jsonx.Marshal(v)
     *   w.Header().Set(ContentType, header.ContentTypeJson)   // ← 无条件写 application/json
     *   w.WriteHeader(code)
     *   w.Write(bs)
     * </pre>
     * ⇒ <b>go-zero 根本不做内容协商</b>：请求头里 {@code Accept: text/plain}、
     * {@code Accept: application/xml}、{@code Accept: *} 都会被<b>无视</b>，
     * 响应一律是 {@code HTTP 200 + Content-Type: application/json + JSON envelope}。
     *
     * <p><b>⚠️ 不关掉的后果（实测过）</b>：Spring 默认按 {@code Accept} 选转换器，
     * {@code Accept: text/plain} 时找不到能写出该 DTO 的转换器 ⇒ 抛
     * {@code HttpMediaTypeNotAcceptableException} ⇒ 落到兜底分支 ⇒ <b>HTTP 500</b>。
     * 而 Go 在同样的请求下是 <b>200 + 正常 envelope</b>。
     * ⇒ 这是<b>可观察的 L1 差异</b>（状态码 500 vs 200，且客户端拿不到业务码）。
     *
     * <p><b>⚠️ {@code ignoreAcceptHeader(true)} 必须同时补一个策略，否则起不来</b>：
     * Spring 的 {@code ContentNegotiationManagerFactoryBean.build()}（spring-web 6.2）
     * <pre>
     *   if (!this.ignoreAcceptHeader) { strategies.add(new HeaderContentNegotiationStrategy()); }
     *   if (this.defaultNegotiationStrategy != null) { strategies.add(this.defaultNegotiationStrategy); }
     *   ...
     *   new ContentNegotiationManager(strategies)   // ← 策略为空时抛
     *                                             //   "At least one ContentNegotiationStrategy is expected"
     * </pre>
     * ⇒ 只写 {@code ignoreAcceptHeader(true)} 会让策略列表<b>变成空</b>，
     * 容器直接起不来（{@code mvcContentNegotiationManager} Bean 创建失败）。
     * 故这里同时给一个 {@link FixedContentNegotiationStrategy}，语义上正好等价于 Go 的
     * 「无条件写 application/json」。
     *
     * <p><b>只关响应侧</b>：{@code ignoreAcceptHeader} 只影响「写什么 Content-Type」，
     * <b>不影响请求侧的 {@code Content-Type}</b> ——
     * 后者仍由 {@code @RequestBody} 按 Go 的 {@code withJsonBody} 规则判定
     * （非 JSON ⇒ 5003，见 {@code FavoriteApiExceptionHandler#handleMediaTypeNotSupported}）。
     */
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.ignoreAcceptHeader(true);
        configurer.defaultContentTypeStrategy(
                new FixedContentNegotiationStrategy(MediaType.APPLICATION_JSON));
    }
}
