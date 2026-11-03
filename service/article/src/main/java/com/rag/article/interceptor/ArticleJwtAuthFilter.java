package com.rag.article.interceptor;

import com.rag.article.config.ArticleApiProperties;
import com.rag.article.contract.ErrCodes;
import com.rag.article.contract.ErrMsg;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.security.BearerTokenExtractor;
import com.rag.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.List;

/**
 * 🔴 <b>article-api 的 JWT 验签 Filter</b> —— 把验签搬出 70s 预算，对齐 Go 的链序。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       ① B 侧 4 个类（{@code auth/GoStyleJwtAuthFilter} ＋ {@code auth/ArticleJwtSignatureVerifier}
 *       ＋ {@code auth/ArticleJwtVerificationResult} ＋ {@code auth/ArticleJwtProtectedRoutes}）
 *       在 C 侧压平成 <b>本类</b>：路由表与结果载体降级为<b>嵌套类型</b>
 *       （{@link ProtectedRoute} / {@link VerificationResult}）⇒ <b>C 侧文件数不增</b>
 *       （与 {@code ArticleSyncEvent} 用嵌套 record 闭合缺口 G-6 同一手法）；
 *       ② {@code Filter} ⇒ {@link OncePerRequestFilter}（Spring 生态惯例 · D4）；
 *       ③ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 判据（method + path 双命中）、验签流程、
 *       「<b>绝不写响应</b>」这条最吃重的不变式、<b>无条件放行</b>、以及
 *       「失败时把异常对象交给调用方原样重抛」全部逐条照抄；
 *       本类<b>不</b>写任何响应字节 ⇒ 与 {@code JwtAuthInterceptor} 的先后顺序<b>不影响</b>结果。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/article/article-api/src/main/java/com/rag/article/article/api/auth/GoStyleJwtAuthFilter.java:100-102
 *   @Component
 *   @Order(Ordered.HIGHEST_PRECEDENCE + 6)
 *   public class GoStyleJwtAuthFilter implements Filter {
 *
 * B 出处：…/api/auth/ArticleJwtSignatureVerifier.java:88-119（验签本体，不写响应、不查 Redis）
 * B 出处：…/api/auth/ArticleJwtVerificationResult.java:16-19（结果载体 = claims + failure）
 * B 出处：…/api/auth/ArticleJwtProtectedRoutes.java:73-77（受保护路由表）
 * B 出处：…/api/auth/ArticleJwtProtectedRoutes.java:88-101（isProtected：method + path 双命中）
 * B 出处：…/api/auth/ArticleJwtProtectedRoutes.java:112-119（pathWithinContext：剥 context-path）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 为什么验签必须是 Filter（而不是 HandlerInterceptor）</h2>
 * <p>B 侧（go-zero）的链序（真源，逐行核实 go-zero@v1.10.2）：
 * <pre>
 *   rest/engine.go:101-116  bindRoute：
 *     chn = ng.buildChainWithNativeMiddlewares(...)   // 原生中间件（索引 0 = 最外层）
 *     chn = ng.appendAuthHandler(fr, chn, verifier)   // ← JWT 验签在这里 append（:108）
 *     for _, mw := range ng.middlewares { ... }        // ← observability(70s 计时) 在这之后 append（:110-112）
 *   rest/chain/chain.go  Append = 「加在请求流的最后」⇒ 越 Append 越靠内
 *
 *   ⇒ 执行序：原生中间件 → **JWT 验签** → **observability(70s 计时)** → handler
 *   ⇒ 70s 计时器在 Authorize **之后**起算 ⇒ **验签耗时不计入 70s 预算**
 * </pre>
 * <p>⚠️ <b>本域 70s 预算的唯一来源是 observability</b>：
 * {@code service/article/api/articlecenter.go} 的
 * {@code observability.DisableNativeRestTimeout(&c.RestConf)}
 * ＋ {@code server.Use(observability.NewHTTPMiddleware(...))}
 * ⇒ native 那条 Timeout 被关掉了 ⇒ 70s <b>只</b>由 observability 提供
 * ⇒ 「验签在预算之外」才有意义。
 * <p>⇒ <b>Java 侧落点</b>：Servlet 的所有 {@code Filter} 都在 {@code DispatcherServlet} <b>外面</b>，
 * {@code HandlerInterceptor} 在<b>里面</b> ⇒ 要把验签移出 70s 预算，就必须把它搬进 Filter。
 * <p>⚠️ <b>共享层已占用的 Filter 槽位（C 侧实测值，非台账旧值）</b>：
 * <pre>
 *   +2  PathNormalizingFilter    common/web/PathNormalizingFilter.java:78
 *   +3  TraceparentFilter         common/web/TraceparentFilter.java:208
 *   +4  MaxConnsFilter            common/protection/MaxConnsFilter.java:105
 *   +5  RouteIsolationFilter      common/autoconfigure/ProtectionAutoConfiguration.java:206
 *   +6  CpuSheddingFilter         common/autoconfigure/ProtectionAutoConfiguration.java:186
 *   +7  HttpMiddleware            common/autoconfigure/ObservabilityAutoConfiguration.java:153   ← 70s 预算在这里起算
 *   +8  MaxBytesFilter            common/protection/MaxBytesFilter.java:97
 *   +9  JsonBodyLimitFilter       common/web/JsonBodyLimitFilter.java:85
 * </pre>
 * ⇒ 本类的合法窗口是 <b>[+3, +6]</b>（下界 +3 = traceparent 之后；上界 +6 = 70s 预算之前）
 * ⇒ ⇒ 本类取 <b>+6</b>，与 {@code CpuSheddingFilter}(+6) <b>并列</b>。
 * <p>⚠️ <b>并列是否安全？</b> —— <b>安全</b>，因为<b>本类绝不写响应</b>（见下）
 * ⇒ 无论谁先谁后，<b>响应结果完全相同</b>。
 *
 * <h2>🔴🔴 本类 <b>绝不写响应</b>（本类最吃重的不变式）</h2>
 * <p>若本类在验签失败时<b>自己</b>写 401，则：
 * <ul>
 *   <li>本类(+6) 排在 {@code MaxBytesFilter}(+8) <b>之前</b> ⇒ <b>401 会压过 413</b>；</li>
 *   <li>而 Go 侧 MaxBytes 是 native middleware（{@code rest/engine.go:163-165}），
 *       排在 Authorize <b>之外</b>（更外层）⇒ Go 是 <b>413 压过 401</b>。</li>
 * </ul>
 * ⇒ 那会把「413 压过 401」这条语义变成新漂移。
 * <p>⇒ <b>所以本类只验签、只把结果放进 request attribute、然后放行</b>；
 * 401 仍由 {@link JwtAuthInterceptor} 抛 ⇒
 * <ul>
 *   <li>{@code @ExceptionHandler}（{@code ArticleApiExceptionHandler}）仍然管得到
 *       ⇒ <b>401 + 空 body</b> 的形状逐字不变；</li>
 *   <li>401 发生在所有 Filter <b>之后</b> ⇒ MaxBytes 的 413 仍然压过 401（与 Go 一致）。</li>
 * </ul>
 *
 * <h2>⚠️ 本域的 public 端点<b>不受影响</b>（判据共用）</h2>
 * <p>本域有 <b>3 个公开端点</b>（{@code GET /v1/article/{id}} · {@code GET /v1/articles} ·
 * {@code POST /v1/upload}）⇒ 本类用<b>同一份</b>受保护路由表（method + path）判据
 * ⇒ 公开端点<b>根本不会触发验签</b> ⇒ 与 Go 的三段
 * {@code AddRoutes}（第二/三段不带 {@code WithJwt}）逐条一致。
 * <p>⚠️ <b>判据必须只有一份</b>：本类与 {@link JwtAuthInterceptor} 共用
 * {@link #isProtected(String, String)} ⇒ 不会出现「Filter 判为受保护、Interceptor 判为放行」的缝隙
 * （前者 ⇒ ② 未闭环；后者 ⇒ <b>鉴权被绕过</b>）。
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ArticleJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ArticleJwtAuthFilter.class);

    /**
     * 验签结果在 request attribute 里的键（由本类写、由 {@link JwtAuthInterceptor} 读）。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/GoStyleJwtAuthFilter.java:107}。
     */
    public static final String VERIFICATION_ATTRIBUTE = "com.rag.article.jwt.verification";

    /**
     * 验签<b>来源</b>标记（{@code filter} / {@code inline}）—— 供守门测试断言
     * 「interceptor 确实消费了 Filter 的结果」（否则 DRIFT-USERAPI-009② 会静默不闭环）。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/GoStyleJwtAuthFilter.java:113}。
     */
    public static final String VERIFICATION_SOURCE_ATTRIBUTE = "com.rag.article.jwt.verificationSource";

    /** 来源 = 本 Filter（验签在 70s 预算之外）。 */
    public static final String SOURCE_FILTER = "filter";

    /** 来源 = interceptor 兜底就地验签（验签在 70s 预算之内 ⇒ ② 未闭环）。 */
    public static final String SOURCE_INLINE = "inline";

    /**
     * article-api 的<b>全部</b>受保护路由，逐条对应 Go {@code routes.go} 第一段。
     *
     * <p>⚠️ <b>这张表是「受保护集合」的<b>唯一</b>定义</b> —— 改这里等于改安全边界，
     * 本类与 {@link JwtAuthInterceptor} 会同时变 ⇒ <b>不会出现单侧漂移</b>。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtProtectedRoutes.java:73-77}。
     */
    private static final List<ProtectedRoute> PROTECTED_ROUTES = List.of(
            new ProtectedRoute(HttpMethod.POST, "/v1/article"),
            new ProtectedRoute(HttpMethod.PUT, "/v1/article/{id}"),
            new ProtectedRoute(HttpMethod.DELETE, "/v1/article/{id}"),
            new ProtectedRoute(HttpMethod.GET, "/v1/me/article/{id}"));

    private final SecretKey key;

    public ArticleJwtAuthFilter(ArticleApiProperties properties) {
        // L4 idiomatization（强制留痕 + Go ↔ RAG 对照）：
        //   Go 出处：go-zero rest/handler/authhandler.go:84 的
        //     `func(token *jwt.Token) (any, error) { return []byte(secret), nil }`
        //     —— Go 端 golang-jwt 不校验 key 长度，本域的 secret 能直接签。
        //   Java 表达差异：jjwt 0.12 强制 ≥32 字节（HS256 / RFC 7518），故构造器
        //     调 Keys.hmacShaKeyFor 时必须先补齐；本构造器与 JwtUtil.getToken 共用
        //     JwtUtil.padToHmacKeySize，强制签发与验签落在同一份 32 字节 key 上，
        //     否则本模块在默认密钥下 Bean 创建即抛 WeakKeyException。
        //   语义等价论证：padding 是 zero-fill，落到 HS256 上字节一致 → token 形状、
        //     claim、exp 全一致；Go ↔ RAG 仅在 key 长度这一非行为字段上收敛。
        //
        // B 出处：…/api/auth/ArticleJwtSignatureVerifier.java:78-79
        this.key = Keys.hmacShaKeyFor(
                JwtUtil.padToHmacKeySize(properties.getAuth().getAccessSecret()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        // ⚠️ 判据与 interceptor **共用同一份**（method + path ∈ PROTECTED_ROUTES）
        //    ⇒ public 端点不会被误伤 ⇒ 两侧不会单侧漂移。
        if (isProtected(request.getMethod(), pathWithinContext(request))) {
            VerificationResult result = verify(request.getHeader("Authorization"), uri);
            request.setAttribute(VERIFICATION_ATTRIBUTE, result);
            request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);
            if (result.failed()) {
                // 🔴 只记录，**不写响应**（见类注释「本类绝不写响应」）。
                log.debug("article JWT verification failed (deferred to interceptor) : {}", uri);
            }
        }
        // 🔴 无条件放行：验签失败也要继续走完 Filter 链 ⇒
        //    MaxBytes(+8) 仍有机会写 413（Go 侧 MaxBytes 在 Authorize 之外 ⇒ 413 压过 401），
        //    最终的 401 由 interceptor 抛（@ExceptionHandler 仍管得到 ⇒ 空 body 形状不变）。
        chain.doFilter(request, response);
    }

    /**
     * 验签 —— <b>不写响应</b>；失败时把异常装进 {@link VerificationResult} 交给调用方重抛。
     *
     * @param authHeader 请求的 {@code Authorization} 头（可为 null）
     * @param uri        请求路径（只用于日志）
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtSignatureVerifier.java:88-119}。
     */
    public VerificationResult verify(String authHeader, String uri) {
        // Go: rest/token/tokenparser.go doParseToken → request.ParseFromRequest(r,
        //     request.AuthorizationHeaderExtractor, ...)。头缺失时 extractor 返回 errNoAuthParam
        //     → parser.ParseToken 返回 err → authhandler.go:100 unauthorized()。
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("article-api path {} with missing Authorization header", uri);
            return VerificationResult.failed(unauthorized());
        }

        // Go: golang-jwt/jwt/v4 request/oauth2.go stripBearerPrefixFromTokenString
        //     —— 大小写不敏感、不匹配时原样返回整串。三条规则都在 BearerTokenExtractor 里。
        String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
        if (token.isEmpty()) {
            log.warn("article-api path {} with empty Bearer token", uri);
            return VerificationResult.failed(unauthorized());
        }

        Claims claims;
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            claims = jws.getPayload();
        } catch (Throwable t) {
            // 签名错 / 过期 / 格式错 —— Go 侧同样只走到 unauthorized()，不区分原因
            log.warn("article-api JWT verify failed on {} : {}", uri, t.getMessage());
            return VerificationResult.failed(unauthorized());
        }

        return VerificationResult.ok(claims);
    }

    /**
     * 请求是否命中受保护路由（<b>method + path</b> 双双命中才算）。
     *
     * @param method 请求方法
     * @param path   <b>已剥掉 context-path</b> 的请求路径
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtProtectedRoutes.java:88-101}。
     */
    public static boolean isProtected(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        // ⚠️ PathPattern.matches 只接受 PathContainer（Spring 6.2 / 7.0 的签名一致 · 已实测 jar），
        //    不是 String —— 传 String 编译不过。
        PathContainer pathContainer = PathContainer.parsePath(path);
        for (ProtectedRoute route : PROTECTED_ROUTES) {
            if (route.method().matches(method) && route.pattern().matches(pathContainer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取<b>剥掉 context-path</b> 之后的请求路径。
     *
     * <p>⚠️ Go 的 router 看到的是<b>剥掉前缀之后</b>的路径（{@code rest.WithPrefix("/v1")}
     * 已在注册时拼好）。本模块的 context-path 是 {@code /}，减掉它等于原样；
     * 但仍写成减法，是为了在有人改 context-path 时不会静默错位
     * —— 那会让 {@code /v1/article} 永远匹配不上，<b>鉴权整体失效</b>
     * （比报错更危险：所有写接口变公开）。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtProtectedRoutes.java:112-119}。
     */
    public static String pathWithinContext(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }

    /**
     * <b>本域 401 的唯一构造点</b> —— 验签失败的各种形态共用同一形态（Go 不区分原因）。
     *
     * <p><b>body 不会被写出去</b> —— 见 {@code ArticleApiExceptionHandler#handleAuthentication}
     * 对 Go {@code authhandler.go:106 unauthorized()} 的逐字还原：
     * Go 只 {@code WriteHeader(401)}，<b>不写任何字节</b> ⇒ RAG 侧也落<b>空 body</b>。
     *
     * <p>⚠️ 业务码填 {@code 1005}（article 域的 {@code ErrorUnauthorized}）
     * <b>只是为了让日志可读</b>：401 的 body 是空的 ⇒ 这个码<b>不会</b>出现在响应里。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtSignatureVerifier.java:131-136}。
     */
    public static AuthenticationException unauthorized() {
        int code = ErrCodes.ERROR_UNAUTHORIZED.getCode();
        return new AuthenticationException(code, ErrMsg.getMsg(code));
    }

    /**
     * 一次 article JWT 验签的结果 —— <b>数据载体</b>，不含行为。
     *
     * <p><b>失败时携带的是「异常对象」而不是标志位</b> ⇒ interceptor 可以<b>原样重抛</b>
     * ⇒ {@code ArticleApiExceptionHandler#handleAuthentication} 仍然管得到
     * ⇒ <b>401 + 空 body</b>的形状逐字不变（Go 侧 {@code authhandler.go:106-122} 的
     * {@code unauthorized()} 只 {@code WriteHeader(401)} <b>不写任何字节</b>）。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtVerificationResult.java:16-34}。
     *
     * @param claims   验签通过时的 claim 集合（失败时为 {@code null}）
     * @param failure  验签失败时的异常（{@code null} = 验签通过）
     */
    public record VerificationResult(
            Claims claims,
            RuntimeException failure
    ) {

        /** 验签通过。 */
        public static VerificationResult ok(Claims claims) {
            return new VerificationResult(claims, null);
        }

        /** 验签失败 —— {@code failure} 必须非 null（会被 interceptor 原样重抛）。 */
        public static VerificationResult failed(RuntimeException failure) {
            return new VerificationResult(null, failure);
        }

        /** 验签是否失败。 */
        public boolean failed() {
            return failure != null;
        }
    }

    /**
     * 一条受保护路由 = {@code method} + {@code path} 模板。缺一不可。
     *
     * <p><b>B 出处</b>：{@code …/api/auth/ArticleJwtProtectedRoutes.java:122-125}。
     */
    private record ProtectedRoute(HttpMethod method, PathPattern pattern) {
        private ProtectedRoute(HttpMethod method, String pattern) {
            this(method, PathPatternParser.defaultInstance.parse(pattern));
        }
    }
}
