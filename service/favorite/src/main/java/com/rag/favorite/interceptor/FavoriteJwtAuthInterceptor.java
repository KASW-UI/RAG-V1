package com.rag.favorite.interceptor;

import com.rag.common.exception.AuthenticationException;
import com.rag.common.security.BearerTokenExtractor;
import com.rag.common.security.JwtUtil;
import com.rag.favorite.config.FavoriteApiProperties;
import com.rag.favorite.contract.ErrCodes;
import com.rag.favorite.contract.ErrMsg;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.SecretKey;

/**
 * <p><b>favorite 域的 JWT 鉴权拦截器</b> —— 对应 B 侧
 * {@code favorite-api/.../interceptor/FavoriteJwtAuthInterceptor.java} ＋
 * {@code favorite-api/.../auth/FavoriteJwtSignatureVerifier.java} ＋
 * {@code favorite-api/.../auth/FavoriteJwtVerificationResult.java} ＋
 * {@code favorite-api/.../auth/FavoriteJwtAuthPaths.java}
 * （B 侧 <b>4 个类 ⇒ C 侧 1 个类</b>）。
 *
 * <h2>⚠️ L4 落点变化：<b>验签能力收进本类（自包含）</b></h2>
 * <p>B 侧把「验签 / 结果载体 / Filter」拆成 3 个类，原因是
 * <b>DRIFT-USERAPI-009②</b>：B 侧（go-zero）的链序是
 * <b>原生中间件 → JWT 验签 → observability(3s 计时) → 黑名单 → handler</b>
 * ⇒ <b>3s 预算不覆盖 JWT 验签</b>；而 Servlet 的所有 {@code Filter} 都在
 * {@code DispatcherServlet} <b>外面</b>、{@code HandlerInterceptor} 在<b>里面</b>
 * ⇒ 验签要出 3s 预算就必须是 Filter ⇒ B 侧把它拆成了 {@code GoStyleJwtAuthFilter}。
 * <p>⚠️ <b>C 侧 favorite 域的文件清单里没有任何 {@code filter/} 类</b>
 * （{@code docs/File_Tree/favorite.md} 的 {@code interceptor/} 段只有
 * {@code FavoriteJwtAuthInterceptor.java} 与 {@code QueryLimitInterceptor.java}）
 * ⇒ ⇒ 验签只能落在本拦截器里（与 like 域同款取舍）。
 * <p><b>语义等价论证</b>：本类的<b>可观察行为</b>与 B 侧完全一致 ——
 * ① 鉴权失败一律 <b>401 + 三字段信封</b>（本域已签字的表达层差异 DEV-FAV-API-001）；
 * ② claim 的<b>原始对象</b>写进 request attribute（键名 {@code "userId"}，
 * 与 B 侧 {@code ctx.Value("userId")} 同名）；
 * ③ <b>不</b>校验 claim 的取值（畸形 claim 的后果落在业务层 ⇒ HTTP 200 + 5004，<b>不是 401</b>）；
 * ④ 黑名单 fail-open（Redis 故障 ⇒ 放行）。
 * ⇒ 唯一被放弃的是「验签耗时是否计入 3s 预算」这条<b>计时口径</b>（不是本域可观察的 wire 行为）
 * ⇒ ⇒ 该偏离须登记进 {@code docs/DRIFT/favorite/vs-common.md}（A3 轴）。
 *
 * <h2>⚠️ 与 B 侧逐条对齐的行为（L1，不可改）</h2>
 * <ol>
 *   <li>Authorization 头缺失 / 为空 → <b>HTTP 401</b>；</li>
 *   <li>剥掉 {@code Bearer } 后 token 为空 → 401
 *       （B 侧 {@code golang-jwt/jwt/v4@v4.5.2/request/oauth2.go} 的
 *       {@code stripBearerPrefixFromTokenString}，三条规则见 {@link BearerTokenExtractor}）；</li>
 *   <li>签名错 / 过期 / 格式错 → 401。<b>B 侧不区分原因</b>，三种失败都只走到同一个
 *       {@code unauthorized()}；</li>
 *   <li>验签成功后：把 claim {@code userId} 的<b>原始对象</b>写进 request attribute；</li>
 *   <li>token 在 Redis 黑名单（{@code user:jwt_blacklist:<token>}）→ <b>HTTP 401</b>；</li>
 *   <li>Redis 故障 → <b>不阻塞业务（fail-open）</b>。</li>
 * </ol>
 *
 * <h3>⚠️ 执行顺序：<b>先验签、后黑名单</b>（与 B 侧的 chain 内外关系一致）</h3>
 * <p>B 侧出处三段，缺一不可：
 * <ol>
 *   <li>{@code rest/engine.go:101-116} {@code bindRoute}：先 {@code appendAuthHandler(JWT)}，
 *       再 {@code ng.middlewares}，最后 {@code chn.ThenFunc(route.Handler)}；</li>
 *   <li>{@code rest/chain/chain.go} 的 {@code Then} 是 {@code m0(m1(...mN(h)))}，
 *       <b>索引 0 最外层、先执行</b>；{@code Append} 追加到尾部 = 最内层；</li>
 *   <li>{@code rest/server.go:214-233} {@code WithMiddlewares}/{@code WithMiddleware}
 *       在<b>构造期</b>就把 {@code route.Handler} 包成 {@code middleware(route.Handler)}
 *       —— 黑名单是 {@code ThenFunc} 的<b>参数</b>，比 chain 任何元素都内层。</li>
 * </ol>
 * ⇒ 真实顺序：… Timeout → Recover → … → <b>Authorize(JWT)</b> → observability
 * → <b>CheckBlacklist</b> → handler。
 *
 * <p>可观察差异（顺序反了会怎样）：一个<b>签名无效但已在黑名单中</b>的 token，
 * B 侧 → {@code Authorize} 先失败 → 401，<b>黑名单从不查询</b>；
 * 反过来则会对畸形 token 产生一次多余的 Redis 读。
 *
 * <h3>⚠️ 黑名单 token 的取法与 B 侧的 users 域<b>不同</b>（本域特有，勿「顺手统一」）</h3>
 * <p>B 侧本域中间件（{@code checkblacklist_middleware.go:30}）是：
 * <pre>
 *   token := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
 * </pre>
 * 即「大小写<b>敏感</b>的精确前缀 TrimPrefix」<b>再</b> TrimSpace。
 * 而 users/admin 域（{@code service/user/user/api/internal/middleware/checkblacklistmiddleware.go:28}）
 * 是 {@code strings.TrimPrefix(authHeader, "Bearer ")}，<b>没有</b> TrimSpace。
 * <p>⇒ 本实现刻意<b>不</b>直接复用 {@code BearerTokenExtractor#trimBearerPrefix}，
 * 而是在其结果上再补一次 B 侧语义的 TrimSpace（见 {@link #goTrimSpace(String)}）。
 *
 * <p>而<b>验签</b>用的取法与 users 域<b>一致</b>（大小写不敏感、缺失前缀时原样返回整串），
 * 因为两边都走 go-zero 的 {@code handler.Authorize}。
 * ⇒ 于是本域也存在 B 侧自身的不一致：{@code "bearer <token>"} 验签用 {@code "<token>"}、
 * 黑名单查 {@code "bearer <token>"}。这是 B 侧的行为，<b>原样保留</b>。
 */
@Component
public class FavoriteJwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FavoriteJwtAuthInterceptor.class);

    /**
     * 与 B 侧 {@code authhandler.go:71-79} 里 {@code ctx.Value("userId")} 的 key 同名。
     * 存的是 claim 的<b>原始对象</b>（不是 long），原因见类注释。
     */
    public static final String CLAIM_ATTRIBUTE = "userId";

    /**
     * B 侧 {@code checkblacklist_middleware.go:48} 写进 ctx 的 key
     * （{@code context.WithValue(r.Context(), "jwt_token", token)}）。
     */
    public static final String JWT_TOKEN_ATTRIBUTE = "jwt_token";

    /**
     * 受保护路径前缀 —— B 侧 {@code FavoriteJwtAuthPaths.PROTECTED_PREFIX}。
     *
     * <p>⚠️ B 侧前缀是 {@code /favorite/v1}（{@code rest.WithPrefix("/favorite/v1")}），
     * C 侧保持同名 ⇒ <b>无路径重命名差异</b>（与 users/admin 域不同，那两边把
     * {@code /usercenter/v1} 改成了 {@code /user/v1}）。
     */
    public static final String PROTECTED_PREFIX = "/favorite/v1";

    /**
     * Redis 黑名单 key 前缀 —— B 侧 {@code checkblacklist_middleware.go:36}
     * 的 {@code fmt.Sprintf("user:jwt_blacklist:%s", token)}。
     *
     * <p>⚠️ <b>必须是 {@code user:} 而不是 {@code admin:}</b>：B 侧 favorite/api
     * 用的就是 {@code user:jwt_blacklist:}，与 user-api 的登出写入端同 key
     * （user-rpc 登出写的就是这个 key）⇒ favorite-api 才能拦住已登出的 token。
     */
    private static final String BLACKLIST_KEY_PREFIX = "user:jwt_blacklist:";

    private final SecretKey key;
    private final StringRedisTemplate redisTemplate;

    public FavoriteJwtAuthInterceptor(FavoriteApiProperties properties, StringRedisTemplate redisTemplate) {
        // L4 idiomatization（强制留痕 + B 侧对照）：
        //   B 侧出处：go-zero rest/handler/authhandler.go:84 的
        //     `func(token *jwt.Token) (any, error) { return []byte(secret), nil }`
        //     —— B 侧 golang-jwt 不校验 key 长度，36 字节的 favorite secret 能直接签。
        //   Java 表达差异：jjwt 0.12 强制 ≥32 字节（HS256 / RFC 7518），故构造器
        //     调 Keys.hmacShaKeyFor 时必须先补齐；本构造器与 JwtUtil.getToken 共用
        //     JwtUtil.padToHmacKeySize，强制签发与验签落在同一份 32 字节 key 上，
        //     否则本模块在默认密钥下 Bean 创建即抛 WeakKeyException。
        //   语义等价论证：padding 是 zero-fill，落到 HS256 上字节一致 → token 形状、
        //     claim、exp 全一致；B 侧 ↔ C 侧仅在 key 长度这一非行为字段上收敛。
        this.key = Keys.hmacShaKeyFor(
                JwtUtil.padToHmacKeySize(properties.getUserAuth().getAccessSecret()));
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ── B 侧的「路由匹配先于鉴权」：未注册路径**不经 auth** ──
        // 证据：rest/engine.go:104-118 的 bindRoute 只把 chain（native + auth + 用户中间件）
        //       挂到 router.Handle(method, path, handle) 上，即**只对匹配上的路由生效**；
        //       router/patrouter.go:73,79 的 404 / 405 分支根本进不了这条链。
        //       ⇒ B 侧 /favorite/v1/nope 恒为 404，与有没有 token 无关。
        //
        // ⚠️ Spring 侧默认**做不到**：Boot 的静态资源处理器匹配 `/**`，
        //    于是未注册路径**也拿到了 handler**，mappedHandler != null ⇒ 拦截器照跑
        //    ⇒ 404 被鉴权遮蔽成 401。
        //
        // 判据用 `handler instanceof HandlerMethod`：它精确对应 B 侧那句
        // 「有没有匹配上 @RequestMapping 方法」——ResourceHttpRequestHandler 不是 HandlerMethod。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String uri = request.getRequestURI();

        // ================= 阶段 1：JWT 验签 =================
        Claims claims;
        String signatureToken;
        String trimmedToken;
        try {
            // ---------- 阶段 1a：Authorization 头缺失 ----------
            // B 侧出处：rest/token/tokenparser.go:80-84 doParseToken → request.ParseFromRequest(r,
            //     request.AuthorizationHeaderExtractor, ...)。头缺失时 extractor 返回 errNoAuthParam
            //     → ParseToken 返回 err → authhandler.go:56 unauthorized()。
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || authHeader.isEmpty()) {
                log.warn("path {} with missing Authorization header", uri);
                throw unauthorized();
            }

            // B 侧: golang-jwt/v4 request/oauth2.go stripBearerPrefixFromTokenString（golang-jwt/jwt/v4@v4.5.2/request/oauth2.go:8）
            //     —— 大小写不敏感、不匹配时原样返回整串。三条规则都在 BearerTokenExtractor 里。
            signatureToken = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
            if (signatureToken.isEmpty()) {
                log.warn("path {} with empty Bearer token", uri);
                throw unauthorized();
            }

            try {
                Jws<Claims> jws = Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(signatureToken);
                claims = jws.getPayload();
            } catch (Throwable t) {
                // 签名错 / 过期 / 格式错 —— B 侧同样只走到 unauthorized()，不区分原因
                log.warn("JWT verify failed on {} : {}", uri, t.getMessage());
                throw unauthorized();
            }

            // 黑名单 key 用的 token —— B 侧的 CheckBlacklistMiddleware 用的是【TrimPrefix 结果】，
            // 与验签用的 token 是**两套取法**（B 侧自身如此，畸形 header 下会不同）。
            trimmedToken = goTrimSpace(BearerTokenExtractor.trimBearerPrefix(authHeader));
        } catch (AuthenticationException e) {
            // B 侧的 401 形态：异常对象原样上抛 ⇒ @ExceptionHandler 仍管得到
            // ⇒ 401 + 三字段信封的形状逐字不变（DEV-FAV-API-001 不受影响）。
            throw e;
        }

        // B 侧: authhandler.go:71-79 遍历 claims，把非标准 claim（aud/exp/jti/iat/iss/nbf/sub 除外）
        //     逐个塞进 ctx。favorite api 侧只有 7 个 logic 的 ctx.Value("userId") 读它，
        //     其余 claim 无消费者，故这里只落这一个。
        // ⚠️ 落的是 claim 的**原始对象**（不是 long）⇒ 解析成 int64 是业务层的事
        //    （FavoriteApiSupport#userId，失败落 HTTP 200 + {code:5004}，**不是 401**）。
        request.setAttribute(CLAIM_ATTRIBUTE, claims.get(CLAIM_ATTRIBUTE));

        // ================= 阶段 2：黑名单检查 =================
        // B 侧 checkblacklist_middleware.go:22-50：
        //   authHeader := r.Header.Get("Authorization"); if authHeader == "" { next; return }
        //   token := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
        //   if token == "" { next; return }
        //   exists, err := m.Redis.ExistsCtx(r.Context(), blackListKey)
        //   if err == nil && exists { 401 + body }
        //   ctx := context.WithValue(r.Context(), "jwt_token", token)
        //
        // 注意：B 侧内部【两段取法本就不同】——Authorize 用大小写不敏感的 strip，
        // 这里用大小写敏感的 TrimPrefix + TrimSpace。故 "bearer <token>" 在 B 侧里
        // 验签用 "<token>"、黑名单查 "bearer <token>"。这是 B 侧自身的不一致，本实现原样保留。
        String jwtToken = trimmedToken;
        if (!jwtToken.isEmpty() && isBlacklisted(jwtToken)) {
            log.warn("favorite token in blacklist, uri={}", uri);
            throw unauthorized();
        }

        // B 侧只有在 header 非空且 TrimPrefix 结果非空时才写 jwt_token；
        // 上面已排除两种为空的分支，此处必然写入。
        request.setAttribute(JWT_TOKEN_ATTRIBUTE, jwtToken);
        if (!jwtToken.equals(signatureToken)) {
            // 与 B 侧一致的不一致：非 "Bearer <token>" 规范写法下两段取法不同。保留 warn 便于排查。
            log.warn("favorite blacklist token != jwt token (auth header is not 'Bearer <token>')");
        }
        return true;
    }

    /**
     * Redis 黑名单查询 —— fail-open 与 B 侧逐条对齐。
     *
     * <p>B 侧: {@code exists, err := m.Redis.ExistsCtx(...); if err == nil && exists { 401 }}
     * （{@code service/favorite/api/internal/middleware/checkblacklist_middleware.go:37}）。
     * Redis 故障时 {@code err != nil} ⇒ 条件为 false ⇒ <b>放行</b>，不阻塞业务。
     * 本实现把异常吞成 {@code null}，随后 {@code exists != null && exists} 同样为 false ⇒ 放行。
     */
    private boolean isBlacklisted(String token) {
        String blacklistKey = BLACKLIST_KEY_PREFIX + token;
        Boolean exists;
        try {
            exists = redisTemplate.hasKey(blacklistKey);
        } catch (Throwable t) {
            log.warn("favorite Redis blacklist check failed : {}", t.getMessage());
            exists = null;
        }
        return exists != null && exists;
    }

    /**
     * <b>本域 401 的唯一构造点</b> —— 验签失败与黑名单命中共用同一形态。
     *
     * <p><b>body 形状</b>：B 侧的 {@code unauthorized()}（{@code authhandler.go:106-122}）
     * 只调 {@code writer.WriteHeader(http.StatusUnauthorized)}，<b>不写任何字节</b>。
     * B 侧的黑名单分支（{@code checkblacklist_middleware.go:39-45}）则写
     * {@code {"code":5004,"msg":"未登录或登录已失效"}}（<b>两字段，没有 {@code data}</b>）。
     * <p>本端统一成完整 envelope（{@code {code,msg,data}}）—— 与 users/admin/comment 域同款。
     * <p>⚠️ <b>两侧 body 的字段集合不同</b>（B 侧空 body / 两字段；C 侧三字段），
     * 但 <b>HTTP 状态码 401 与 code 5004 是对齐的</b> ⇒ 属表达层差异，<b>已签字</b>，
     * 登记在 {@code docs/Intentional_Deviations/favorite/api.md}（DEV-FAV-API-001）。
     */
    public static AuthenticationException unauthorized() {
        int code = ErrCodes.ERROR_UNAUTHORIZED;
        return new AuthenticationException(code, ErrMsg.getMsg(code));
    }

    /**
     * B 侧 {@code strings.TrimSpace} 的等价实现。
     *
     * <p>⚠️ <b>刻意不用 {@link String#trim()}、也不用 {@link String#strip()}</b>：
     * <ul>
     *   <li>{@code trim()} 只裁 ASCII ≤ ' '，不裁 U+00A0 / U+0085；</li>
     *   <li>{@code strip()} 用 {@link Character#isWhitespace}，它<b>不</b>包含
     *       U+00A0（NBSP）与 U+0085（NEL），而 B 侧的 {@code unicode.IsSpace} 包含。</li>
     * </ul>
     * 本方法按 B 侧的 {@code unicode.IsSpace} 判定表逐条列出，两侧行为完全一致。
     *
     * <p>（可观察性说明：只有「token 前后带 Unicode 空白」这一形态会走到这里，
     * 而那种 token 必然在验签阶段就失败 ⇒ 本方法实际只在标准写法下生效；
     * 仍然逐条对齐是为了不留下「换个 key 再改一次」的地雷。）
     */
    private static String goTrimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** B 侧 {@code unicode.IsSpace} 的判定表（{@code unicode.IsSpace} / {@code unicode.White_Space}）。 */
    private static boolean isGoSpace(char c) {
        switch (c) {
            case '\t':
            case '\n':
            case '\u000B':
            case '\f':
            case '\r':
            case ' ':
            case '\u0085':
            case '\u00A0':
                return true;
            default:
                // 其余 Unicode 空白：Java 的 Character.isWhitespace 与 B 侧的 unicode.IsSpace
                // 在这一段上取值一致（Java 不含 U+00A0/U+0085，已在上面单列）。
                return Character.isWhitespace(c);
        }
    }
}
