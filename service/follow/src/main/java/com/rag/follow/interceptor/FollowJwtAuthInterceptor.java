package com.rag.follow.interceptor;

import com.rag.common.exception.AuthenticationException;
import com.rag.common.security.BearerTokenExtractor;
import com.rag.common.security.JwtUtil;
import com.rag.follow.config.FollowApiProperties;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.ErrMsg;
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

/**
 * <p><b>follow 域的 JWT 验签拦截器</b> —— 对应 B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/interceptor/FollowJwtAuthInterceptor.java:73-144}
 * ＋ 同包 {@code auth/} 下 4 个类（{@code FollowJwtAuthPaths} / {@code FollowJwtSignatureVerifier} /
 * {@code FollowJwtVerificationResult} / {@code GoStyleJwtAuthFilter}）。
 *
 * <h2>⚠️ L4 落点变化：<b>验签能力收进本类（自包含）</b></h2>
 * <p>B 侧把「路径判据 / 验签 / 结果载体 / Filter」拆成 4 个类，原因是
 * <b>DRIFT-USERAPI-009②</b>：B 侧（go-zero）的链序是
 * <b>原生中间件 → JWT 验签 → observability(3s 计时) → 黑名单 → handler</b>
 * ⇒ <b>3s 预算不覆盖 JWT 验签</b>；而 Servlet 的所有 {@code Filter} 都在
 * {@code DispatcherServlet} <b>外面</b>、{@code HandlerInterceptor} 在<b>里面</b>
 * ⇒ 验签要出 3s 预算就必须是 Filter ⇒ B 侧把它拆成了 {@code GoStyleJwtAuthFilter}。
 * <p>⚠️ <b>C 侧 follow 域没有那个 Filter 落点</b>（本域只有 2 个 {@code interceptor/} 空壳，
 * 且本次任务的文件清单不含任何 {@code filter/} 类）⇒ ⇒ 验签只能落在本拦截器里。
 * <p><b>语义等价论证</b>：本类的<b>可观察行为</b>与 B 侧完全一致 ——
 * ① 鉴权失败一律 <b>401 + 空 body</b>；② claim 的<b>原始对象</b>写进 request attribute
 * （键名 {@code "userId"}，与 B 侧 {@code ctx.Value("userId")} 同名）；
 * ③ <b>不</b>校验 claim 的取值。
 * ⇒ 唯一被放弃的是「验签耗时是否计入 3s 预算」这条<b>计时口径</b>（不是本域可观测的 wire 行为）；
 * ⇒ 该偏离按 D2/DRIFT 的口径登记在 follow 域台账，不在本次改动里。
 *
 * <h2>⚠️ 三条不能改的语义</h2>
 * <ol>
 *   <li><b>失败 ⇒ {@link AuthenticationException} ⇒ 401 空 body</b>（不是 5004、不是 200）。
 *       B 侧 {@code rest/handler/authhandler.go:106 unauthorized()} 只写状态码、不写字节。</li>
 *   <li><b>不校验 claim 的取值</b>：B 侧的 {@code handler.Authorize} 只验签名与 exp；
 *       claim 是不是数字、能不能转 int64，由<b>业务层</b>
 *       （{@code FollowApiSupport#userId}）判定，失败返回 <b>5004</b>
 *       ⇒ <b>HTTP 200 + {code:5004}</b>。
 *       ⇒ 若在本层把它变成 401，B 侧返回 200 + 5004 的场景在 C 侧会变成 401 ⇒ <b>状态码就错了</b>。</li>
 *   <li><b>未匹配路径不经鉴权</b>：判据用 {@code handler instanceof HandlerMethod}
 *       （见下方注释）。</li>
 * </ol>
 *
 * <h3>⚠️ 本域<b>没有 public 路径</b></h3>
 * <p>B 出处：B 侧 {@code FollowJwtAuthPaths.java:50-55} 的 {@code isProtected} 对
 * {@code /follow/v1/**} 一律为真 —— B 侧 {@code routes.go:16-64} 是一段
 * {@code server.AddRoutes(..., rest.WithJwt(secret), rest.WithPrefix("/follow/v1"))}
 * ⇒ <b>8 个端点全带 JWT</b> ⇒ ⇒ 本类<b>不</b>提供白名单。
 *
 * <h3>⚠️ token 的取法：大小写<b>不</b>敏感</h3>
 * <p>B 侧 {@code rest/token/tokenparser.go:81-86} 走 go-zero 的
 * {@code request.AuthorizationHeaderExtractor} → golang-jwt 的
 * {@code stripBearerPrefixFromTokenString} ⇒ 前缀<b>大小写不敏感</b>、不匹配时原样返回整串
 * ⇒ C 侧用共享层的 {@link BearerTokenExtractor#tokenForSignatureVerification(String)}（逐条等价）。
 * ⚠️ <b>这与黑名单层的取法刻意不同</b>（B 侧自身的不一致），见 {@link FollowBlacklistInterceptor}。
 */
@Component
public class FollowJwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FollowJwtAuthInterceptor.class);

    /**
     * 与 B 侧 {@code FollowJwtAuthInterceptor.java:82} 里 {@code ctx.Value("userId")} 的 key 同名。
     *
     * <p>存的是 claim 的<b>原始对象</b>（不是 long），原因见类注释 —— 取值判定属于业务层。
     */
    public static final String CLAIM_ATTRIBUTE = "userId";

    private final SecretKey key;

    public FollowJwtAuthInterceptor(FollowApiProperties properties) {
        // L4 idiomatization（强制留痕 + B ↔ C 对照）：
        //   B 出处：B 侧 {@code auth/FollowJwtSignatureVerifier.java:68-69} 的
        //     {@code Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(properties.getUserAuth().getAccessSecret()))}
        //     （B 侧那一行的上游是 {@code rest/token/tokenparser.go:83-85} 的
        //      keyfunc 无条件返回 {@code []byte(secret)}）。
        //   Java 表达差异：jjwt 0.12 强制 HS256 密钥 ≥32 字节 ⇒ 必须先补齐。
        //   语义等价论证：Go 侧 secret 是 36 字节 UUID（≥32）⇒ padding 对它是 no-op；
        //     即便换成 <32 字节的 secret，zero-fill 后 HS256 字节一致（RFC 2104，见共享层
        //     {@code JwtUtil} 的类注释）⇒ ⇒ 验签行为零变化。
        this.key = Keys.hmacShaKeyFor(
                JwtUtil.padToHmacKeySize(properties.getUserAuth().getAccessSecret()));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ── B 侧的「路由匹配先于鉴权」：未注册路径**不经 auth** ──
        // 证据：B 侧 {@code auth/GoStyleJwtAuthFilter.java:131-133} 的路径判据
        //       ＋ {@code FollowJwtAuthInterceptor.java:111-113} 的 {@code handler instanceof HandlerMethod}。
        //       B 侧的 chain 只挂在匹配上的路由上 ⇒ /follow/v1/nope 恒为 404，与有没有 token 无关。
        //
        // ⚠️ Spring 侧默认**做不到**：Boot 的静态资源处理器匹配 /**，
        //    于是未注册路径**也拿到了 handler** ⇒ 拦截器照跑 ⇒ 404 被鉴权遮蔽成 401。
        //
        // 判据用 `handler instanceof HandlerMethod`：它精确对应 B 侧那句
        // 「有没有匹配上 @RequestMapping 方法」——ResourceHttpRequestHandler 不是 HandlerMethod。
        // ⚠️ 不要改成「看路径是否以 /follow/v1/ 开头」之类的字符串判断：那是重新发明一遍路由表。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // ---------- 阶段 1：Authorization 头缺失 ----------
        // B 出处：B 侧 {@code auth/FollowJwtSignatureVerifier.java:82-85}
        //   （头缺失 ⇒ errNoAuthParam ⇒ {@code authhandler.go:56 unauthorized()}）。
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("path {} with missing Authorization header", request.getRequestURI());
            throw unauthorized();
        }

        // ---------- 阶段 2：剥 Bearer 前缀 ----------
        // B 出处：B 侧 {@code auth/FollowJwtSignatureVerifier.java:89-93} ＋
        //   golang-jwt/v4 {@code request/oauth2.go:7-12}（大小写不敏感、不匹配时原样返回整串）。
        String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
        if (token.isEmpty()) {
            log.warn("path {} with empty Bearer token", request.getRequestURI());
            throw unauthorized();
        }

        // ---------- 阶段 3：验签 ----------
        // B 出处：B 侧 {@code auth/FollowJwtSignatureVerifier.java:97-108}。
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

        // ---------- 阶段 4：把 claim 的**原始对象**放进 request attribute ----------
        // B 出处：B 侧 {@code auth/FollowJwtSignatureVerifier.java:116} 的
        //   {@code FollowJwtVerificationResult.ok(claims.get("userId"))}
        //   ＋ {@code interceptor/FollowJwtAuthInterceptor.java:142} 的
        //   {@code request.setAttribute(CLAIM_ATTRIBUTE, result.claim())}。
        //
        // ⚠️ 存的是**原始对象**（不是 long）：取值判定属于业务层
        //   （{@code FollowApiSupport#userId}），失败返回 5004
        //   ⇒ HTTP 200 + {code:5004} ⇒ 鉴权层**不能**把它变成 401。
        request.setAttribute(CLAIM_ATTRIBUTE, claims.get("userId"));
        return true;
    }

    /**
     * 统一构造鉴权失败。
     *
     * <p><b>body 形状：401 + 空 body</b>。B 侧的 {@code unauthorized()}
     * （{@code rest/handler/authhandler.go:106-122}）只调
     * {@code writer.WriteHeader(http.StatusUnauthorized)}，<b>不写任何字节</b>，也不设 Content-Type。
     * 由 {@code FollowApiExceptionHandler#handleAuthentication} 负责还原该形状。
     *
     * <p>业务码用 {@code ERROR_UNAUTHORIZED}（5004）：follow 域错误码表
     * （B 侧 {@code service/follow/common/errmsg.go:41}）里鉴权码就是 5004。
     * 注意该 code <b>不会出现在 body 里</b>（body 为空）—— 它只用于日志与
     * {@link FollowBlacklistInterceptor} 那条<b>有 body</b>的 401。
     */
    private static AuthenticationException unauthorized() {
        int code = ErrCodes.ERROR_UNAUTHORIZED;
        return new AuthenticationException(code, ErrMsg.getMsg(code));
    }
}
