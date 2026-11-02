package com.rag.message.interceptor;

import com.rag.common.exception.AuthenticationException;
import com.rag.common.security.BearerTokenExtractor;
import com.rag.common.security.JwtUtil;
import com.rag.message.config.MessageApiProperties;
import com.rag.message.contract.ErrCodes;
import com.rag.message.contract.ErrMsg;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.SecretKey;

/**
 * <p><b>职责</b>：message 域<b>两份 JWT 逻辑的共享基类</b> —— 持有全部鉴权逻辑，
 * 两个子类（user / admin）<b>只提供「用哪把 key」</b>这一个差异。
 *
 * <p><b>B 出处</b>：<b>三个</b> B 侧文件合并（映射表 message.md §1.6 的「1:N」）：
 * <ul>
 *   <li>{@code service/message/message-api/src/main/java/com/rag/message/message/api/interceptor/AbstractMessageJwtAuthInterceptor.java}
 *       （1-149 行；类声明 {@code :62}；{@code CLAIM_ATTRIBUTE} {@code :70}；
 *       构造器 {@code :82-86}；{@code preHandle} {@code :89-133}；{@code unauthorized} {@code :144-148}）</li>
 *   <li>{@code .../api/auth/MessageJwtSignatureVerifier.java}
 *       （1-163 行；{@code RouteGroup} {@code :73-78}；{@code VerificationResult} {@code :88-97}；
 *       密钥构造 {@code :102-107}；{@code verify} {@code :129-162}）</li>
 *   <li>{@code .../api/auth/MessageJwtAuthFilter.java}
 *       （1-187 行；{@code VERIFICATION_ATTRIBUTE} {@code :121}；{@code doFilter} {@code :148-165}）</li>
 * </ul>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>三个类合并为一个</b>（映射表 §1.6「1:N」：
 *       B 侧的 {@code auth/} 2 个类 + {@code interceptor/} 1 个基类 ⇒ C 侧只保留本类 +
 *       两个子类）。
 *       另外两处<b>纯机械</b>改写：① 包名 {@code api.interceptor} → {@code interceptor}（D7）；
 *       ② proto / 错误契约包名（D7）。
 *       <b>方法体逐条照抄</b>（4 条 L1 行为、claim 取值判定归属、401 的构造）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 4 条 L1 行为（缺头 401 / 剥不出 Bearer 401 /
 *       签名错·过期·格式错 401 / 成功即落 claim）逐条未动；
 *       claim <b>取值</b>判定仍然归属业务层（{@code MessageApiSupport#userId}）。
 *       ⚠️ 唯一的形态差异是<b>验签的所在层</b>（见下方「§1.6 缺口」小节），
 *       它<b>不改变任何响应形状</b>，只改变「验签耗时不计入 / 计入 3s 预算」。</li>
 *   <li><b>B 出处</b>：见上方三条文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>为什么抽成基类而不是两个各自实现的拦截器</b>：B 侧两段路由表的<b>唯一差别</b>是
 * secret 与前缀，鉴权逻辑本身是同一份 {@code handler.Authorize}。写成两个独立类等于
 * 复制一份安全边界（AGENTS.md 记录的 DRIFT-010「admin 侧一份、user 侧另一份，
 * 各自演化出注销绕过」就是这个形态）。本类持有全部逻辑，两个子类只提供 key。
 *
 * <p><b>L1 行为冻结（逐条对照 B 侧源码，非推断）</b>：
 * <ol>
 *   <li>Authorization 头缺失 → {@link AuthenticationException} → <b>HTTP 401</b>；</li>
 *   <li>Bearer 前缀剥离失败 / token 为空 → 401
 *       （B 侧 {@code golang-jwt/jwt/v4@v4.5.2/request/oauth2.go} 的
 *       {@code stripBearerPrefixFromTokenString}，规则见共享层 {@link BearerTokenExtractor}）；</li>
 *   <li>签名错 / 过期 / 格式错 → 401（<b>Go 不区分原因</b>）；</li>
 *   <li>验签成功后：把 claim {@code userId} 的<b>原始对象</b>写进 request attribute。</li>
 * </ol>
 *
 * <p><b>⚠️ 本层刻意<b>不</b>校验 claim 的取值</b>，这是与 user-api 最大的实现差异：
 * <ul>
 *   <li>B 侧 {@code handler.Authorize} 只验签名与 exp，随后把所有非标准 claim 原样塞进 ctx；</li>
 *   <li>claim 是不是数字、是不是正数，由 logic 层
 *       {@code api/internal/logic/shared/helper.go} 的 {@code UserIDFromContext} 判定，
 *       失败返回 {@code ErrorUnauthorized}（<b>5004</b>），
 *       最终输出是 <b>HTTP 200 + {code:5004,msg:"unauthorized"}</b>；</li>
 *   <li>若在本层把它变成 401，Go 返回 200 + 5004 的场景在 RAG 上会变成 401 —— 状态码就错了。</li>
 * </ul>
 * 身份解析的落点见 {@code MessageApiSupport#userId(HttpServletRequest)}。
 *
 * <h3>🔴 映射表 §1.6 记录的 <b>C 侧缺口</b>：验签跑在<b>哪一层</b></h3>
 * <p>B 侧链序（真源）：原生中间件 → <b>Authorize(验签)</b> → <b>observability(3s 计时)</b> → handler
 * ⇒ <b>验签耗时不计入 3s 预算</b>（B 侧把验签搬进了 Servlet Filter
 * {@code MessageJwtAuthFilter}，{@code @Order(+6)}，在 3s 预算 Filter <b>之外</b>）。
 * <p>而 Servlet 的 {@code HandlerInterceptor} 在 {@code DispatcherServlet} <b>里面</b>，
 * 而 3s 预算在 {@code HttpMiddlewareFilter}(+7) 起算、整条下游都在预算内
 * ⇒ 若验签留在拦截器里，就等于<b>验签耗时会吃掉 3s 预算</b> ⇒ 与 B 侧不一致。
 * <p>⇒ <b>C 侧没有独立的 Servlet Filter</b>（映射表 §1.6：RAG 的 {@code auth/} 包 2 个类
 * 映射到 C 侧的 2 个 {@code HandlerInterceptor}）⇒ ⇒ <b>本类把「验签」与「401」
 * 合并在拦截器里</b> ⇒ ⇒ <b>B 侧的 {@code DRIFT-MESSAGEAPI-009②}（验签出预算）在 C 侧
 * <b>未闭环</b> ⇒ 属于 §1.6 明确记录的 <b>C 侧缺口</b>，须在 message 域 DRIFT 台账登记。
 * <p>⚠️ <b>本类<b>不</b>引入任何 B 侧 message 域没有的东西</b>：
 * B 侧 message 的 {@code auth/} 包<b>没有</b>黑名单（那是 user / admin 域的
 * {@code CheckBlacklistMiddleware}）⇒ ⇒ 本类也<b>不</b>有黑名单检查。
 *
 * <p><b>⚠️ 「两把 key 互不通用」是 B 侧的既有语义，不要为了方便合成一把</b>：
 * 拿 user token 打 admin 端点会验签失败 → <b>401</b>（B 侧同样如此），反之亦然。
 */
public abstract class AbstractMessageJwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AbstractMessageJwtAuthInterceptor.class);

    /**
     * 与 B 侧 {@code handler.Authorize} 里 {@code ctx.Value("userId")} 的 key 同名。
     *
     * <p>存的是 claim 的<b>原始对象</b>（不是 long），原因见类注释 —— 取值判定属于业务层。
     *
     * <p><b>⚠️ 值必须是 {@code "userId"}（小写 u、驼峰式），不能写成 {@code "user_id"}</b>
     * ⇒ 它是 L1 契约（JWT claim 名），业务层
     * {@code MessageApiSupport#userId(HttpServletRequest)} 读的正是这个常量。
     *
     * <p><b>B 出处</b>：{@code .../interceptor/AbstractMessageJwtAuthInterceptor.java:70}。
     */
    public static final String CLAIM_ATTRIBUTE = "userId";

    /**
     * 路由组 —— 对应 B 侧两段 {@code AddRoutes} 的两把 key。
     *
     * <p><b>B 出处</b>：{@code .../auth/MessageJwtSignatureVerifier.java:73-78}。
     */
    public enum RouteGroup {
        /** {@code /message/v1/**}（不含 admin）—— B 侧第一段，UserAuth secret。 */
        USER,
        /** {@code /message/v1/admin/**} —— B 侧第二段，AdminAuth secret。 */
        ADMIN
    }

    /**
     * 验签结果。
     *
     * @param failed 是否失败（{@code true} ⇒ 401）
     * @param claim  成功时 claim {@code userId} 的<b>原始对象</b>；失败时为 {@code null}；
     *               成功但 token 里<b>没有</b> userId claim 时也为 {@code null}
     *               （<b>这不是失败</b> —— B 侧同样放行，由业务层判 5004）
     */
    public record VerificationResult(boolean failed, Object claim) {

        /** 验签成功（claim 可能是 {@code null}）。 */
        public static VerificationResult success(Object claim) {
            return new VerificationResult(false, claim);
        }

        /** 验签失败。 */
        public static VerificationResult failure() {
            return new VerificationResult(true, null);
        }
    }

    /**
     * 本拦截器守的路由组 —— 决定用<b>哪把 key</b>验签。
     *
     * <p>⚠️ 用<b>显式</b>组别而不是「按路径推断」：拦截器的组别由 Spring 的注册路径决定
     * （{@code WebMvcConfig}），比字符串前缀推断更可靠。
     * ⇒ ⇒ B 侧 {@code MessageJwtAuthFilter} 的「按路径分派」（{@code verifyForPath}）
     * 在 C 侧<b>不需要</b>：本类由子类显式给出组别 ⇒ <b>连「路径分派」这条路径都省掉了</b>
     * ⇒ ⇒ 少一处可能与 {@code WebMvcConfig} 的注册条件走偏的判据。
     */
    private final RouteGroup routeGroup;

    /** user 组的验签密钥（B 侧 {@code UserAuth.AccessSecret}）。 */
    private final SecretKey userKey;

    /** admin 组的验签密钥（B 侧 {@code AdminAuth.AccessSecret}）。 */
    private final SecretKey adminKey;

    /**
     * 构造器 —— 两把 key 在此一次性构造（B 侧 {@code MessageJwtSignatureVerifier} 的构造器逐字照抄）。
     *
     * <p><b>⚠️ 密钥补齐（N-5 留档 · B 侧同款）</b>：B 侧的 admin secret 只有 15 字节，
     * golang-jwt 不校验长度；jjwt 0.12 强制 ≥256 bit ⇒ 统一由
     * {@link JwtUtil#padToHmacKeySize(String)} zero-pad 到 32 字节。
     * 代价是 <b>B 侧签的 admin token 在 RAG 验不过，反之亦然</b>（两套部署各自闭环）。
     * 这是运行环境约束，不是行为差异 —— token 的 payload / claim / 过期语义完全一致。
     *
     * <p><b>B 出处</b>：{@code .../auth/MessageJwtSignatureVerifier.java:102-107}。
     *
     * @param routeGroup  本拦截器守的路由组（决定用哪把 key）
     * @param properties  HTTP 层配置（承载两段 secret）
     */
    protected AbstractMessageJwtAuthInterceptor(RouteGroup routeGroup, MessageApiProperties properties) {
        this.routeGroup = routeGroup;
        this.userKey = Keys.hmacShaKeyFor(
                JwtUtil.padToHmacKeySize(properties.getUserAuth().getAccessSecret()));
        this.adminKey = Keys.hmacShaKeyFor(
                JwtUtil.padToHmacKeySize(properties.getAdminAuth().getAccessSecret()));
    }

    /**
     * 请求进入前的鉴权 —— B 侧逐条照抄（含「未匹配路由不鉴权」的判据）。
     *
     * <p><b>B 出处</b>：{@code .../interceptor/AbstractMessageJwtAuthInterceptor.java:89-133}。
     *
     * @param request  当前请求
     * @param response 当前响应（本类不写响应）
     * @param handler  即将执行的 handler
     * @return 恒为 {@code true}（放行）；鉴权失败时<b>抛</b> {@link AuthenticationException}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ── 路径 0（C 侧补的一条）：ERROR 派发不是一次「业务请求」──
        // B 侧 Go 的「路由匹配先于鉴权」：未注册路径**不经 auth**（Go 侧 /message/v1/nope 恒为 404）。
        // C 侧补这条的理由：Boot 的静态资源处理器匹配 `/**`，未注册路径也拿到了 handler；
        // 而 Tomcat 的 ERROR 派发会**重新进 DispatcherServlet**，这次 handler 是
        // `BasicErrorController#error`（它**是** HandlerMethod ⇒ 过得了下面那道判据
        // ⇒ 会继续验签）⇒ ⇒ 404 会被鉴权顶成 401。
        // 本仓同款修法见 `service/users/admin/.../interceptor/AdminJwtAuthInterceptor.java:146-148`
        // （2026-11-02 真 Tomcat 契约测试实测后加）。
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }

        // ── Go 的「路由匹配先于鉴权」：未注册路径**不经 auth** ──
        // 证据：B 侧 rest/engine.go:104-118 的 bindRoute 只把 chain（native + auth + 用户中间件）
        //       挂到 router.Handle(method, path, handle) 上，即**只对匹配上的路由生效**；
        //       router/patrouter.go:73,79 的 404 / 405 分支根本进不了这条链。
        //       ⇒ Go 侧 /message/v1/nope 与 /like/v1/nope 恒为 404，与有没有 token 无关。
        //
        // ⚠️ Spring 侧默认**做不到**：Boot 的静态资源处理器匹配 `/**`，
        //    于是未注册路径**也拿到了 handler**，mappedHandler != null ⇒ 拦截器照跑
        //    ⇒ 404 被鉴权遮蔽成 401（2026-10-22 真 Tomcat 实测）。
        //
        //    判据用 `handler instanceof HandlerMethod`：它精确对应 Go 那句
        //    「有没有匹配上 @RequestMapping 方法」——ResourceHttpRequestHandler 不是 HandlerMethod。
        //    ⚠️ 不要改成「看路径是否以 /v1/ 开头」之类的字符串判断：那是重新发明一遍路由表。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // ── 验签（B 侧 MessageJwtSignatureVerifier#verify 的逐字照抄）──
        String uri = request.getRequestURI();
        VerificationResult result = verify(routeGroup, request.getHeader("Authorization"), uri);
        if (result.failed()) {
            throw unauthorized();
        }
        // 验签成功 ⇒ 把 claim 的**原始对象**写进 attribute（取值判定归业务层）
        request.setAttribute(CLAIM_ATTRIBUTE, result.claim());
        return true;
    }

    /**
     * 按<b>显式</b>组别验签 —— B 侧 {@code MessageJwtSignatureVerifier#verify} 逐字照抄。
     *
     * <p><b>语义等价论证（逐条对齐 B 侧的 4 条行为）</b>：
     * <ol>
     *   <li>Authorization 头缺失 / 为空 → 失败；</li>
     *   <li>Bearer 前缀剥离失败 / token 为空 → 失败（规则见共享层 {@link BearerTokenExtractor}）；</li>
     *   <li>签名错 / 过期 / 格式错 → 失败（<b>不区分原因</b>）；</li>
     *   <li>成功 → 返回 claim {@code userId} 的<b>原始对象</b>（不是 long）。</li>
     * </ol>
     *
     * <p><b>⚠️ 本方法<b>不写响应</b>、<b>不抛异常</b></b>：失败时只返回
     * {@link VerificationResult#failure()} ⇒ 由调用方（{@link #preHandle}）决定怎么处理
     * ⇒ ⇒ 「401 + 空 body」的形状仍由 {@code MessageApiExceptionHandler} 统一产出。
     *
     * <p><b>B 出处</b>：{@code .../auth/MessageJwtSignatureVerifier.java:129-162}。
     *
     * @param group      路由组（决定用哪把 key）
     * @param authHeader 请求的 {@code Authorization} 头（可为 {@code null}）
     * @param uri        请求路径（只用于日志）
     * @return 验签结果
     */
    protected VerificationResult verify(RouteGroup group, String authHeader, String uri) {
        SecretKey key = group == RouteGroup.ADMIN ? adminKey : userKey;

        // B 侧：rest/token/tokenparser.go:81-86 doParseToken → request.ParseFromRequest(r,
        //     request.AuthorizationHeaderExtractor, ...)。头缺失时 extractor 返回 errNoAuthParam
        //     → ParseToken 返回 err → authhandler.go:56 unauthorized()。
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("{} path {} with missing Authorization header", group, uri);
            return VerificationResult.failure();
        }

        // B 侧：golang-jwt/jwt/v4 request/oauth2.go stripBearerPrefixFromTokenString
        //     —— 大小写不敏感、不匹配时原样返回整串。三条规则都在 BearerTokenExtractor 里。
        String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
        if (token.isEmpty()) {
            log.warn("{} path {} with empty Bearer token", group, uri);
            return VerificationResult.failure();
        }

        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            // B 侧：authhandler.go:63-72 遍历 claims，把非标准 claim（aud/exp/jti/iat/iss/nbf/sub 除外）
            //     逐个塞进 ctx。message api 侧只有 logic/shared/helper.go 的 UserIDFromContext 读
            //     "userId"，其余 claim 无消费者，故这里只取这一个。
            return VerificationResult.success(jws.getPayload().get("userId"));
        } catch (Throwable t) {
            // 签名错 / 过期 / 格式错 —— B 侧同样只走到 unauthorized()，不区分原因
            log.warn("{} JWT verify failed on {} : {}", group, uri, t.getMessage());
            return VerificationResult.failure();
        }
    }

    /**
     * 统一构造鉴权失败。
     *
     * <p>业务码用 {@code ERROR_UNAUTHORIZED}（5004）而不是 user-api 的 1006：
     * message 域的错误码表里没有 1006，鉴权码就是 5004。<b>body 不会被写出去</b> ——
     * 见 {@code MessageApiExceptionHandler#handleAuthentication} 对 B 侧
     * {@code authhandler.go:106 unauthorized()} 的逐字还原。
     *
     * <p><b>B 出处</b>：{@code .../interceptor/AbstractMessageJwtAuthInterceptor.java:144-148}。
     *
     * @return 鉴权失败异常（401 + 空 body）
     */
    private static AuthenticationException unauthorized() {
        return new AuthenticationException(
                ErrCodes.ERROR_UNAUTHORIZED.getCode(),
                ErrMsg.getMsg(ErrCodes.ERROR_UNAUTHORIZED.getCode()));
    }
}
