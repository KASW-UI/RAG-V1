package com.rag.article.interceptor;

import com.rag.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * article-api 的 JWT 鉴权 —— Go 端第一段 {@code server.AddRoutes(..., rest.WithJwt(secret), ...)}
 * 的等价物。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       B 侧的验签器（{@code auth/ArticleJwtSignatureVerifier}）在 C 侧落进
 *       {@link ArticleJwtAuthFilter}（Servlet Filter，70s 预算<b>之外</b>），
 *       本类改成<b>消费</b>它的结果、失败<b>原样重抛</b>；
 *       受保护路由表（{@code auth/ArticleJwtProtectedRoutes}）降级为
 *       {@link ArticleJwtAuthFilter#isProtected(String, String)}（<b>判据共用一份</b>）；
 *       补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 「路由匹配先于鉴权」守卫、「method + path 双命中才鉴权」、
 *       「失败原样重抛 ⇒ 401 + <b>空 body</b>」、claim 的<b>原始对象</b>写进
 *       {@code request} attribute（键名 {@code "userId"}）、<b>不</b>校验 claim 取值 ——
 *       五条全部逐条照抄。
 *       ⚠️ <b>唯一被放弃的是「兜底路径上的计时口径」</b>：当 Filter 未命中时本类就地验签
 *       （⇒ 验签落回 70s 预算<b>之内</b>）⇒ 那条路径上 ② 未闭环 ⇒ <b>大声记日志</b>。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/article/article-api/src/main/java/com/rag/article/article/api/interceptor/JwtAuthInterceptor.java:124-167
 *   @Override
 *   public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
 *       if (!(handler instanceof HandlerMethod)) { return true; }          // 守卫 1
 *       if (!isProtected(request)) { return true; }                        // 守卫 2
 *       ArticleJwtVerificationResult result = (ArticleJwtVerificationResult) request.getAttribute(
 *               GoStyleJwtAuthFilter.VERIFICATION_ATTRIBUTE);
 *       if (result == null) { —— 兜底：就地验签 + log.warn + SOURCE_INLINE —— }
 *       else { request.setAttribute(…, SOURCE_FILTER); }
 *       if (result.failed()) { throw result.failure(); }
 *       request.setAttribute(CLAIM_ATTRIBUTE, result.claims().get(CLAIM_ATTRIBUTE));
 *       return true;
 *   }
 *       </pre></li>
 * </ol>
 *
 * <h2>Go 出处（{@code internal/handler/routes.go:19-47}）</h2>
 * <pre>
 *   server.AddRoutes([]rest.Route{
 *       {POST,   "/article",        CreateArticleHandler},        ← 第一段：带 WithJwt
 *       {PUT,    "/article/:id",    UpdateArticleHandler},
 *       {DELETE, "/article/:id",    DeleteArticleHandler},
 *       {GET,    "/me/article/:id", GetAuthorArticleHandler},
 *   }, rest.WithJwt(serverCtx.Config.Auth.AccessSecret),
 *      rest.WithPrefix("/v1"), rest.WithTimeout(70*time.Second))
 *
 *   server.AddRoutes([]rest.Route{
 *       {GET, "/article/:id", GetArticleHandler},                 ← 第二段：**无** WithJwt
 *       {GET, "/articles",   ListArticlesHandler},
 *   }, rest.WithPrefix("/v1"))
 *
 *   server.AddRoutes([]rest.Route{
 *       {POST, "/upload", UploadImageHandler},                    ← 第三段：**无** WithJwt
 *   }, rest.WithPrefix("/v1"), rest.WithMaxBytes(10MB))
 * </pre>
 * ⇒ 7 个端点里<b>恰好 4 个</b>要 JWT。<b>特别是 {@code POST /v1/upload} 是公开的</b> ——
 * 「上传图片要登录」是一个很容易凭直觉加上去、但 Go 侧<b>没有</b>的规则。
 *
 * <h2>为什么必须带 method 一起匹配（而不是只用路径）</h2>
 * <p>Spring 的 {@code addPathPatterns} 只按<b>路径</b>匹配，不看 method。
 * 若把 {@code /v1/article/{id}} 整条交给鉴权：
 * <pre>
 *   POST /v1/article/123        Spring: 鉴权 → 401（无 token）
 *                               Go:     该路由未注册 → 404，且**不进鉴权链**
 * </pre>
 * ⇒ 凭空多出一条「401 压过 404」的路径。
 * ⇒ 所以判据是 {@link ArticleJwtAuthFilter#isProtected(String, String)} 的「method + PathPattern」表。
 *
 * <h2>「路由匹配先于鉴权」这条守卫也要保留</h2>
 * <pre>
 *   rest/engine.go:104-118   bindRoute 只把 chain（native + auth + 用户中间件）
 *                            挂到 router.Handle(method, path, handle) 上
 *   rest/router/patrouter.go:73,79   404 / 405 分支根本进不了这条链
 * </pre>
 * ⇒ Go 侧 {@code /v1/nope} 恒 404，与有没有 token 无关。
 * Spring 侧默认做不到（Boot 静态资源处理器匹配 {@code "/**"} ⇒ 未注册路径也拿到 handler
 * ⇒ 鉴权把 404 遮蔽成 401）。守卫是 {@code handler instanceof HandlerMethod}。
 * 本条在本模块<b>必须</b>存在：article-api 有 3 个<b>公开</b>端点，
 * 一旦这条守卫丢失，404 的形状会随路径落在受保护区还是公开区而变化。
 *
 * <h2>⚠️ 本层刻意<b>不</b>校验 claim 的取值</h2>
 * <ul>
 *   <li>Go 侧 {@code handler.Authorize}（{@code rest/handler/authhandler.go:45-84}）只验签名与 exp，
 *       随后把所有<b>非标准</b> claim（除 aud/exp/jti/iat/iss/nbf/sub）原样塞进 ctx；</li>
 *   <li>claim 是不是数字、是不是正数，由 logic 层判定，失败返回
 *       {@code ErrorUnauthorized}（<b>1005</b>），最终输出是 <b>HTTP 200 + {code:1005,msg:"请先登录"}</b>。</li>
 * </ul>
 * 若在本层把它变成 401，Go 返回 200 + 1005 的场景在 RAG 上会变成 401 —— 状态码就错了。
 * 取值判定见 {@code ArticleApiSupport#userId}。
 *
 * <h2>401 的 body 是<b>空的</b></h2>
 * <pre>
 *   rest/handler/authhandler.go:106-122  unauthorized()
 *       writer.WriteHeader(http.StatusUnauthorized)     ← 只写状态码，不写 body、不设 Content-Type
 * </pre>
 * ⇒ {@code ArticleApiExceptionHandler#handleAuthentication} 返回
 * {@code ResponseEntity.status(401).build()}（<b>不能</b>是 {@code .body(...)}，
 * 否则容器会补一个 {@code Content-Type}，多出一项响应头）。
 *
 * <h3>🔴 验签与响应被拆成两段（DRIFT-USERAPI-009②）</h3>
 * <p>Go 侧 70s 预算<b>不覆盖</b>验签（计时器在 {@code Authorize} 之后起算），
 * 而 RAG 侧验签原本内联在本类里（{@code HandlerInterceptor} ⇒ DispatcherServlet <b>内</b>）
 * ⇒ 验签<b>被计入</b> 70s 预算 ⇒ 与 Go 相反。
 * <p>⇒ 修法：<b>验签</b>搬进 {@link ArticleJwtAuthFilter}（{@code @Order(+6)} 的 Servlet Filter
 * ⇒ 在 70s 预算的 {@code +7} <b>之外</b>），本类只<b>消费</b>它的结果、失败<b>原样重抛</b>。
 * <p>⇒ 401 的<b>空 body 形状逐字不变</b>（异常对象由 Filter 产出 ⇒
 * {@code @ExceptionHandler} 仍然管得到）；「413 压过 401」也不变
 * （本 Filter <b>绝不写响应</b> ⇒ 401 仍发生在所有 Filter 之后）。
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    /**
     * 与 Go {@code extractCurrentUserID} 里 {@code ctx.Value("userId")} 的 key 同名。
     * 存的是 claim 的<b>原始对象</b>（不是 String / long），原因见类注释「不校验 claim 的取值」。
     *
     * <p><b>B 出处</b>：{@code …/api/interceptor/JwtAuthInterceptor.java:110}。
     */
    public static final String CLAIM_ATTRIBUTE = "userId";

    /**
     * 验签器 —— 🔴 <b>2026-10-27（DRIFT-USERAPI-009②）：验签已从本类搬进
     * {@link ArticleJwtAuthFilter} 的路径</b>（由它在 70s 预算之外调用），
     * 本类改成<b>消费</b>它的结果；仅在 Filter 未命中时<b>兜底就地验签</b>。
     */
    private final ArticleJwtAuthFilter filter;

    public JwtAuthInterceptor(ArticleJwtAuthFilter filter) {
        this.filter = filter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ── 守卫 1：路由匹配先于鉴权（未注册路径不进鉴权链） ──
        // B 出处：…/api/interceptor/JwtAuthInterceptor.java:126-128
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // ── 守卫 2：只有「method + path」双双命中受保护表才鉴权 ──
        //    public 端点（GET /v1/article/:id、GET /v1/articles、POST /v1/upload）
        //    以及「路径像但 method 不对」的组合都在这里放行。
        // ⚠️ 判据与 `ArticleJwtAuthFilter` **共用同一份**
        //    ⇒ 两侧不会单侧漂移（DRIFT-USERAPI-009② 的前提）。
        // B 出处：…/api/interceptor/JwtAuthInterceptor.java:134-136
        if (!isProtected(request)) {
            return true;
        }

        String uri = request.getRequestURI();

        // ================= 阶段 1：JWT 验签 =================
        // 结果由 ArticleJwtAuthFilter 在 70s 预算**之外**产出（本类只消费，不自己验签）。
        // B 出处：…/api/interceptor/JwtAuthInterceptor.java:142-155
        ArticleJwtAuthFilter.VerificationResult result =
                (ArticleJwtAuthFilter.VerificationResult) request.getAttribute(
                        ArticleJwtAuthFilter.VERIFICATION_ATTRIBUTE);
        if (result == null) {
            // 🔴 兜底：Filter 未命中（路径判据不一致 / Filter 未被注册）⇒ 就地验签。
            //   行为与 DRIFT-USERAPI-009② 修复前完全一致（401 照常）⇒ **不会造成鉴权绕过**；
            //   但验签落回 70s 预算**之内** ⇒ 该路径上 ② 未闭环 ⇒ 必须大声记日志。
            log.warn("article-api JWT 验签未命中 filter 缓存 ⇒ 就地验签（DRIFT-USERAPI-009② 在此路径未闭环）: {}", uri);
            request.setAttribute(ArticleJwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE,
                    ArticleJwtAuthFilter.SOURCE_INLINE);
            result = filter.verify(request.getHeader("Authorization"), uri);
        } else {
            request.setAttribute(ArticleJwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE,
                    ArticleJwtAuthFilter.SOURCE_FILTER);
        }

        // 验签失败 ⇒ **原样重抛**（异常对象由 Filter 产出 ⇒ @ExceptionHandler 仍管得到
        // ⇒ 401 + **空 body** 的形状逐字不变）。
        // B 出处：…/api/interceptor/JwtAuthInterceptor.java:159-161
        if (result.failed()) {
            throw result.failure();
        }

        // Go: authhandler.go:63-78 遍历 claims，把非标准 claim（aud/exp/jti/iat/iss/nbf/sub 除外）
        //     逐个塞进 ctx。article-api 侧只有 helper.go:18 的 extractCurrentUserID 读
        //     "userId"，其余 claim 无消费者，故这里只落这一个。
        // B 出处：…/api/interceptor/JwtAuthInterceptor.java:166
        request.setAttribute(CLAIM_ATTRIBUTE, result.claims().get(CLAIM_ATTRIBUTE));
        return true;
    }

    /**
     * 请求是否命中受保护路由 —— 判据<b>共用</b> {@link ArticleJwtAuthFilter#isProtected(String, String)}。
     *
     * <p>⚠️ 取的是 {@code requestURI - contextPath}：Go 的 router 看到的是<b>剥掉前缀之后</b>的路径
     * （{@code rest.WithPrefix("/v1")} 已在注册时拼好）。本模块的 context-path 是 {@code /}，
     * 减掉它等于原样；但仍写成减法，是为了在有人改 context-path 时不会静默错位
     * —— 那会让 {@code /v1/article} 永远匹配不上，<b>鉴权整体失效</b>
     * （比报错更危险：所有写接口变公开）。
     *
     * <p><b>B 出处</b>：{@code …/api/interceptor/JwtAuthInterceptor.java:179-182}。
     */
    private boolean isProtected(HttpServletRequest request) {
        return ArticleJwtAuthFilter.isProtected(
                request.getMethod(), ArticleJwtAuthFilter.pathWithinContext(request));
    }

    /**
     * 鉴权失败的唯一构造点 —— 复用 Filter 侧的 {@code unauthorized()}，
     * 保证<b>两侧的 401 形态逐字同源</b>（码值 1005 + 空 body）。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtSignatureVerifier.java:131-136}。
     */
    static AuthenticationException unauthorized() {
        return ArticleJwtAuthFilter.unauthorized();
    }
}
