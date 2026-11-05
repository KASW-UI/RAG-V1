package com.rag.comment.interceptor;

import com.rag.common.security.BearerTokenExtractor;
import com.rag.comment.contract.ErrCodes;
import com.rag.comment.contract.ErrMsg;
import com.rag.common.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

/**
 * <p><b>comment 域的 JWT 验签拦截器</b> —— 对应 B 侧
 * {@code comment-api/…/api/interceptor/CommentJwtAuthInterceptor.java}
 * ＋ {@code comment-api/…/api/auth/CommentJwtSignatureVerifier.java}
 * ＋ {@code comment-api/…/api/auth/GoStyleJwtAuthFilter.java}
 * ＋ {@code comment-api/…/api/auth/CommentJwtAuthPaths.java}
 * （B 侧 4 个类 ⇒ C 侧 1 个类，映射表 §1.6 第 248 行）。
 *
 * <h2>⚠️ L4 落点变化：<b>验签能力收进本类（自包含）</b></h2>
 * <p>B 侧把「验签 / 结果载体 / Filter」拆成 3 个类，原因是
 * <b>DRIFT-USERAPI-009②</b>：B 侧（go-zero）的链序是
 * <b>原生中间件 → JWT 验签 → observability(3s 计时) → 黑名单 → handler</b>
 * ⇒ <b>3s 预算不覆盖 JWT 验签</b>；而 Servlet 的所有 {@code Filter} 都在
 * {@code DispatcherServlet} <b>外面</b>、{@code HandlerInterceptor} 在<b>里面</b>
 * ⇒ 验签要出 3s 预算就必须是 Filter ⇒ B 侧把它拆成了 {@code GoStyleJwtAuthFilter}。
 * <p>⚠️ <b>C 侧 comment 域的文件清单里没有任何 {@code filter/} 类</b>（69 个文件是映射表锁定的）
 * ⇒ ⇒ 验签只能落在本拦截器里。
 * <p><b>语义等价论证</b>：本类的<b>可观察行为</b>与 B 侧完全一致 ——
 * ① 鉴权失败一律 <b>401 + 空 body</b>；② claim 的<b>原始对象</b>写进 request attribute
 * （键名 {@code "userId"}，与 B 侧 {@code ctx.Value("userId")} 同名）；
 * ③ <b>不</b>校验 claim 的取值。
 * ⇒ 唯一被放弃的是「验签耗时是否计入 3s 预算」这条<b>计时口径</b>（不是本域可观察的 wire 行为）；
 * ⇒ 该偏离登记在 {@code docs/DRIFT/comment/vs-common.md}（与 like 域同款）。
 *
 * <h2>⚠️ 三条不能改的语义</h2>
 * <ol>
 *   <li><b>失败 ⇒ {@link AuthenticationException} ⇒ 401 空 body</b>（不是 1006、不是 5001）。
 *       B 侧 {@code rest/handler/authhandler.go:106 unauthorized()} 只写状态码、不写字节。</li>
 *   <li><b>不校验 claim 的取值</b>：B 侧的 {@code handler.Authorize} 只验签名与 exp；
 *       claim 是不是数字、能不能转 int64，由<b>业务层</b>
 *       （{@code service/CommentApiSupport#userId}）判定，失败返回 <b>1006</b>
 *       ⇒ <b>HTTP 200 + {code:1006}</b>。
 *       ⇒ 若在本层把它变成 401，B 侧返回 200 + 1006 的场景在 C 侧会变成 401 ⇒ <b>状态码就错了</b>。</li>
 *   <li><b>未匹配路径不经鉴权</b>：判据用 {@code handler instanceof HandlerMethod}（见下方注释）。</li>
 * </ol>
 *
 * <h3>⚠️ token 的取法：大小写<b>不</b>敏感</h3>
 * <p>B 侧 {@code rest/token/tokenparser.go:81-86} 走 go-zero 的
 * {@code request/oauth2.go:7-12}：<b>大小写不敏感</b>；不匹配 {@code Bearer } 前缀时
 * <b>原样返回整串</b>（不是报错）。⇒ 由共享层
 * {@link BearerTokenExtractor#tokenForSignatureVerification(String)} 逐条承接。
 *
 * <h3>⚠️ {@code iat} 必须自己校验（jjwt 不校验）</h3>
 * <p>B 侧（golang-jwt v4）的 {@code MapClaims.Valid()} 会调 {@code VerifyIssuedAt}
 * ⇒ <b>校验 iat</b>；而 jjwt <b>只校验 exp / nbf，不校验 iat</b>
 * ⇒ 若不补这一条，C 侧会接受「iat 在未来」的 token ⇒ <b>C 比 B 松</b>（安全面更宽）
 * ⇒ 与 AGENTS.md §3「差异默认判 C 错」一致 ⇒ 必须补。
 * <p>⚠️ <b>精度对齐</b>：B 侧比较的是<b>秒</b>（{@code time.Unix(cmp,0)} vs
 * {@code newNumericDateFromSeconds(iat)}）；jjwt 的 {@code getIssuedAt()} 返回毫秒精度的
 * {@code Date}，但 token 里的 iat 是整秒 ⇒ {@code issuedAt.toInstant()} 的毫秒部分恒为 0
 * ⇒ 与 B 侧的秒级比较<b>等价</b>。
 *
 * <h3>⚠️ 密钥补齐是 L4 表达差异（jjwt 强制 ≥32 字节）</h3>
 * <p>B 侧（go-zero 的 golang-jwt）<b>不校验 key 长度</b>；jjwt 0.12 强制 HS256 ≥32 字节
 * （RFC 7518）⇒ 必须先补齐。补齐方式是 zero-fill ⇒ 落到 HS256 上字节一致
 * ⇒ token 形状、claim、exp 全一致。
 */
@Component
public class CommentJwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CommentJwtAuthInterceptor.class);

    /**
     * claim 的 request attribute 键名 —— 与 B 侧 {@code ctx.Value("userId")} <b>同名</b>。
     */
    public static final String CLAIM_ATTRIBUTE = "userId";

    private final SecretKey key;

    public CommentJwtAuthInterceptor(com.rag.comment.config.CommentApiProperties properties) {
        // ⚠️ 见类注释「密钥补齐是 L4 表达差异」：jjwt 强制 ≥32 字节，zero-fill 后与 B 侧字节一致。
        this.key = Keys.hmacShaKeyFor(
                com.rag.common.security.JwtUtil.padToHmacKeySize(
                        properties.getUserAuth().getAccessSecret()));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ── B 侧的「路由匹配先于鉴权」：未注册路径**不经 auth** ──
        // 证据：B 侧 {@code rest/engine.go} 的 bindRoute 只把 chain 挂到
        //       {@code router.Handle(method, path, handle)} 上，即**只对匹配上的路由生效**；
        //       {@code router/patrouter.go} 的 404 / 405 分支进不了这条链。
        //       ⇒ B 侧 /comment/v1/nope 恒为 404，与有没有 token 无关。
        //
        // ⚠️ Spring 侧默认**做不到**：静态资源处理器匹配 /**，未注册路径也拿到了 handler，
        //    mappedHandler != null ⇒ 拦截器照跑 ⇒ 404 被鉴权遮蔽成 401。
        //    判据用 `handler instanceof HandlerMethod`：精确对应 B 侧那句「有没有匹配上处理方法」。
        //
        // ⚠️ 本条判据**只管「要不要拦」**；B 侧的「要不要验签」是另一条判据
        //    （{@code CommentJwtAuthPaths#isProtected}，由 Filter 使用）⇒ 两者刻意不同源。
        //    C 侧合并为单类后，两条判据合并为一条（HandlerMethod 判据）
        //    ⇒ 「未注册路径不验签」这一可观察行为保持一致。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // ---------- 阶段 1：Authorization 头缺失 ----------
        // B 出处：B 侧 {@code auth/CommentJwtSignatureVerifier.java:82-85}
        //   （头缺失 ⇒ errNoAuthParam ⇒ {@code authhandler.go:56 unauthorized()}）。
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("path {} with missing Authorization header", request.getRequestURI());
            throw unauthorized();
        }

        // ---------- 阶段 2：剥 Bearer 前缀 ----------
        // B 出处：B 侧 {@code auth/CommentJwtSignatureVerifier.java:89-93} ＋
        //   golang-jwt/v4 {@code request/oauth2.go:7-12}（大小写不敏感、不匹配时原样返回整串）。
        String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
        if (token.isEmpty()) {
            log.warn("path {} with empty Bearer token", request.getRequestURI());
            throw unauthorized();
        }

        // ---------- 阶段 3：验签 ----------
        // B 出处：B 侧 {@code auth/CommentJwtSignatureVerifier.java:97-108}。
        //   签名错 / 过期 / 格式错 —— B 侧同样只走到 unauthorized()，不区分原因。
        Claims claims;
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            claims = jws.getPayload();
        } catch (Throwable t) {
            log.warn("JWT verify failed on {} : {}", request.getRequestURI(), t.getMessage());
            throw unauthorized();
        }

        // ---------- 阶段 4：iat 校验（jjwt 不做，B 侧做）----------
        Date issuedAt = claims.getIssuedAt();
        if (issuedAt != null && issuedAt.toInstant().isAfter(Instant.now())) {
            // B 侧：MapClaims.Valid() → "Token used before issued" ⇒ unauthorized()
            log.warn("JWT issued-at in the future on {}", request.getRequestURI());
            throw unauthorized();
        }

        // ---------- 阶段 5：把 claim 的**原始对象**放进 request attribute ----------
        // B 出处：B 侧 {@code auth/CommentJwtSignatureVerifier.java:116} 的
        //   {@code result.ok(claims.get("userId"))}
        //   ＋ {@code interceptor/CommentJwtAuthInterceptor.java:142} 的
        //   {@code request.setAttribute(CLAIM_ATTRIBUTE, result.claims().get(CLAIM_ATTRIBUTE))}。
        //
        // ⚠️ 存的是**原始对象**（不是 long）：取值判定属于业务层
        //   （{@code service/CommentApiSupport#userId}），失败返回 1006
        //   ⇒ HTTP 200 + {code:1006} ⇒ 鉴权层**不能**把它变成 401。
        request.setAttribute(CLAIM_ATTRIBUTE, claims.get(CLAIM_ATTRIBUTE));
        return true;
    }

    /**
     * 统一构造鉴权失败。
     *
     * <p><b>body 形状：401 + 空 body</b>。B 侧的 {@code unauthorized()}
     * （{@code rest/handler/authhandler.go:106-122}）只调
     * {@code writer.WriteHeader(http.StatusUnauthorized)}，<b>不写任何字节</b>，也不设 Content-Type。
     * 由 {@code advice/CommentApiExceptionHandler#handleAuthentication} 负责还原该形状。
     *
     * <p>业务码用 {@code ERROR_TOKEN_RUNTIME}（1006）：comment 域错误码表
     * （B 侧 {@code comment-common/…/errmsg/CommentErrorCode.java:58}）里 token 运行时错误码就是 1006。
     * 注意该 code <b>不会出现在 body 里</b>（body 为空）—— 它只用于日志与
     * {@code service/CommentApiSupport#userId} 那条<b>有 body</b>的 1006（那是业务层的判定，不是本层）。
     */
    private static AuthenticationException unauthorized() {
        int code = ErrCodes.ERROR_TOKEN_RUNTIME;
        return new AuthenticationException(code, ErrMsg.getMsg(code));
    }
}
