package com.rag.admin.interceptor;

import com.rag.common.exception.AuthenticationException;
import com.rag.users.common.ErrCodes;
import com.rag.users.common.ErrMsg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * <p><b>职责</b>：admin 域的 <b>JWT 鉴权拦截器</b> —— 承担 B 侧 {@code rest.WithJwt}
 * （<b>验签结果消费</b>）＋ {@code CheckBlacklistMiddleware}（<b>黑名单</b>）两段中间件的后半程。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>合并</b>：B 侧 {@code api/auth/} 的 4 个类（{@code AdminJwtAuthPaths} /
 *           {@code AdminJwtSignatureVerifier} / {@code AdminJwtVerificationResult} /
 *           {@code GoStyleJwtAuthFilter}）＋ {@code api/interceptor/AdminJwtAuthInterceptor}
 *           = <b>5 个类</b> ⇒ 本域压平成 <b>3 个类</b>（D7 包压平 + mapping table admin.md §1.3 的 N:1）：
 *           <ul>
 *             <li>本类 = {@code interceptor/AdminJwtAuthInterceptor} ＋ 嵌套
 *                 {@link AdminJwtAuthPaths}（路径判据）＋ 嵌套 {@link AdminJwtVerificationResult}（验签结果载体）；</li>
 *             <li>{@code AdminJwtSignatureVerifier}（B 的 {@code auth/AdminJwtSignatureVerifier}）；</li>
 *             <li>{@code AdminJwtSignatureFilter}（B 的 {@code auth/GoStyleJwtAuthFilter}，
 *                 Servlet Filter ⇒ 承载验签）。</li>
 *           </ul>
 *           ⇒ ⇒ <b>验签仍然是独立的一段</b>（B 的 {@code DRIFT-USERAPI-009②} 语义），
 *           <b>没有被合并回拦截器</b> ⇒ 见 {@link AdminJwtSignatureFilter} 的 javadoc。</li>
 *       <li><b>方法体逐字照抄</b>：{@code preHandle} 的两阶段顺序、两个 request attribute
 *           （{@code "userId"} / {@code "jwt_token"}）、黑名单 fail-open、
 *           {@code BearerTokenExtractor} 的两套取法，全部逐条保留。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。唯一的名字级差异是<b>内部</b> request attribute
 *       {@code VERIFICATION_ATTRIBUTE} 的取值（B 侧用 B 的包名前缀 ⇒ 本仓用本域包名前缀）——
 *       它<b>不进入任何 wire</b>（不发给客户端、不被 gRPC 面读）⇒ 不是 L1 面。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/interceptor/AdminJwtAuthInterceptor.java}
 *       （208 行）＋ 同目录 {@code auth/} 下 4 个类。</li>
 * </ol>
 *
 * <h2>🔴 DRIFT-USERAPI-009②：验签在 {@link AdminJwtSignatureFilter}（预算之外）</h2>
 *
 * <p>B 侧链序：<b>原生中间件 → JWT 验签 → observability(3s 计时) → 黑名单 → handler</b>
 * （{@code rest/engine.go:101-113} + {@code rest/chain/chain.go:38-43} 的 Append 语义）
 * ⇒ <b>3s 预算不覆盖 JWT 验签</b>。
 * <p>Servlet 侧所有 {@code Filter} 都在 {@code DispatcherServlet} 外面、
 * {@code HandlerInterceptor} 在里面 ⇒ 验签要出预算，必须搬进 Filter
 * ⇒ ⇒ 验签搬到 {@link AdminJwtSignatureFilter}（{@code @Order(+6)}，在 3s 预算的 {@code +7} 之外）。
 *
 * <p><b>本类现在只做三件事</b>：
 * <ol>
 *   <li>消费 Filter 放进 request attribute 的<b>验签结果</b>；</li>
 *   <li>把验签失败<b>原样重抛</b>（异常对象由 Filter 产出 ⇒
 *       {@code AdminApiExceptionHandler} 的 {@code @ExceptionHandler} 仍管得到
 *       ⇒ <b>401 + 完整信封</b>的形状逐字不变）；</li>
 *   <li><b>黑名单检查</b>（仍在预算<b>之内</b> ⇒ 与 B 侧 一致
 *       —— B 侧的黑名单是 route 级中间件 {@code rest.WithMiddlewares} ⇒
 *       {@code rest/server.go:222-233 WithMiddleware} 把 {@code middleware(route.Handler)}
 *       包在 handler 外 ⇒ 比 chain 任何元素都内层 ⇒ 在 observability(3s) <b>之内</b>）。</li>
 * </ol>
 *
 * <p><b>L1 行为冻结（与 B 侧 1:1）</b>：
 * <ol>
 *   <li>public 路径（{@code /admin/v1/login}、{@code /admin/v1/create}）：<b>直接放行</b>，不校验 token</li>
 *   <li>protected 路径（其余 11 个）—— 验签由 {@link AdminJwtSignatureFilter} 完成，
 *     本类消费结果，失败一律 <b>HTTP 401</b>
 *     （抛 {@code AuthenticationException}，与 user-api 同款，见 DRIFT-004）：
 *     <ul>
 *       <li>Authorization 头缺失 / 为空 → 401（验签阶段）</li>
 *       <li>剥掉 {@code Bearer } 后 token 为空 → 401（验签阶段）</li>
 *       <li>JWT 签名错 / 过期 → 401（验签阶段）</li>
 *       <li>claim.userId 缺失或类型不对 → 401（验签阶段）</li>
 *       <li>Redis blacklist（{@code admin:jwt_blacklist:<token>}）存在 → 401 + <b>不</b>进下游（黑名单阶段）</li>
 *       <li>Redis 故障 → 不阻塞业务（fail-open）</li>
 *     </ul>
 *     验签阶段任一失败都<b>不</b>写 userId / jwt_token attribute，也<b>不查 Redis</b>（与 B 侧 一致）。
 *   </li>
 *   <li><b>{@code uid <= 0} 不在本层拒绝</b>：B 侧的 {@code Authorize} 只验签名 + exp，
 *       {@code uid > 0} 是 identity 层的业务规则（{@code service/user/user/identity/resolve.go:75-77}），
 *       由 {@code com.rag.users.common.IdentityResolver.claimedUID} 承担 → HTTP 200 + {@code {code:1006}}。</li>
 * </ol>
 *
 * <p><b>已回退的两个 DRIFT（2026-10-21，issue #71186893）</b>：
 * <ul>
 *   <li><b>DRIFT-009</b>：执行顺序此前被改成「黑名单先、验签后」⇒ 已改回<b>验签优先</b>。</li>
 *   <li><b>DRIFT-010</b>：token 提取此前用 {@code split(" ", 2).parts[1]}，
 *       对 {@code "XXBearer eyJ..."} 会取出 {@code "eyJ..."} 去验签 ⇒ 构成<b>注销绕过</b>。
 *       已按 B 侧 逐条重写为共享层 {@code BearerTokenExtractor} 的两个方法。</li>
 * </ul>
 *
 * <p><b>⚠️ 本类不新增任何 Counter</b>：B 侧 admin 的拦截器<b>没有埋任何点</b>
 * ⇒ 新增一条 Counter 序列就是<b>新增一条 L1 可观测面</b>（D16：B 侧是唯一判定基准）⇒ ⇒ 不加。
 * 本域的 {@code admin_api_intercept_total} 由 {@code metrics/AdminApiMetrics} 承载，
 * 其 increment 点在<b>业务层</b>（B 侧同样在 Service 层埋点，不在拦截器）。
 */
@Component
public class AdminJwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminJwtAuthInterceptor.class);

    /** Redis blacklist key 前缀（与 B 侧 1:1：admin:jwt_blacklist:）。 */
    private static final String BLACKLIST_KEY_PREFIX = "admin:jwt_blacklist:";

    private final AdminJwtSignatureVerifier verifier;
    private final StringRedisTemplate redisTemplate;

    public AdminJwtAuthInterceptor(AdminJwtSignatureVerifier verifier, StringRedisTemplate redisTemplate) {
        // ⚠️ 密钥构造已移入 {@link AdminJwtSignatureVerifier}（验签搬进 Filter 后的归属）；
        //   L4 idiomatization 的 B 侧 ↔ RAG 等价性论证见该类的构造器注释（内容未变）。
        this.verifier = verifier;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();

        // ==================================================================
        // 🔴 2026-11-02 真 Tomcat 契约测试实测后加：ERROR 派发**不进鉴权**
        //
        // B 出处（go-zero v1.10.2 rest/engine.go:244-268 notFoundHandler）：
        //   未知路径 ⇒ 路由器走 **notFoundHandler** ⇒ 它**另起一条链**
        //   （chain.New(TraceHandler) [+ 日志中间件]）⇒ **这条链里没有 JWT 鉴权**
        //   ⇒ ⇒ B 侧未知路径 = **404**（**从不进 JWT 中间件**）
        //
        // C 侧为什么会变成 401：
        //   ① Boot 3.5 的 `DispatcherServlet.throwExceptionIfNoHandlerFound` 默认 **false**
        //      ⇒ 路由没命中 ⇒ `response.sendError(404)`
        //   ② Tomcat 随后做一次 **ERROR 派发** ⇒ 重新进 DispatcherServlet
        //      ⇒ 这次有 handler 了（`BasicErrorController` 的 `/error`）
        //      ⇒ ⇒ `applyPreHandle` **会跑** ⇒ 本拦截器按 URI 前缀判「受保护」
        //      ⇒ ⇒ 抛 AuthenticationException ⇒ **404 被顶成 401**
        //
        // ⇒ 修法：ERROR 派发一律放行（它不是一次「业务请求」）
        // ⇒ ⇒ 未知路径回到 **404**（与 B 侧一致）
        // ⇒ ⇒ 顺带把本类 javadoc 里那条「MaxBytes 的 413 仍然压过 401」的不变式
        //     **真正闭环**（413 也是 sendError ⇒ ERROR 派发 ⇒ 鉴权不再插手）
        // ==================================================================
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR) {
            return true;
        }

        // ⚠️ **判据是「是不是 admin 的 11 个受保护端点」，不是「URI 长不像」**（见 PROTECTED_PATHS 的论证）：
        //   public 的 2 个端点 ⇒ 放行；
        //   未知路径（如 /admin/v1/not-exist）⇒ **放行** ⇒ 由路由层给 404（与 B 侧 notFoundHandler 同构）。
        if (!AdminJwtAuthPaths.isProtected(uri)) {
            return true;
        }

        // ==================================================================
        // 阶段 1：JWT 验签（结果由 AdminJwtSignatureFilter 在 3s 预算之外产出）
        //
        // B 出处（三段，缺一不可，顺序是结论的关键）：
        //   1. rest/engine.go:101-113 bindRoute：先 appendAuthHandler(JWT)，再 ng.middlewares，
        //      最后 chn.ThenFunc(route.Handler)
        //   2. rest/chain/chain.go 的 Then 是 m0(m1(...mN(h)))，**索引 0 最外层、先执行**；
        //      Append 追加到尾部 = 最内层
        //   3. rest/server.go:222-233 WithMiddleware 在**构造期**就把 route.Handler 包成
        //      middleware(route.Handler) —— 黑名单是 ThenFunc 的**参数**，比 chain 任何元素都内层
        //
        // ⇒ 真实顺序：… Timeout → Recover → … → **Authorize(JWT)** → signature → observability
        //            → **CheckBlacklist** → handler
        //
        // 【DRIFT-009 已回退】本类此前实现的是「黑名单先、验签后」⇒ 已改回验签优先。
        //
        // 可观察差异（修复前）：一个**签名无效但已在黑名单中**的 token，
        //   B 侧   → Authorize 先失败 → 401 + 空 body，黑名单**从不查询**
        //   RAG  → 先打一次 Redis hasKey → 401 + envelope{code:1006}
        //
        // 【DRIFT-010 已回退】本类此前还在此处做 `uid <= 0 → BusinessException(1006)`。
        // B 侧的 Authorize **不校验 claim 取值**，`uid > 0` 是 identity 层的业务规则 ⇒ 移出。
        // ==================================================================

        AdminJwtVerificationResult result = (AdminJwtVerificationResult) request.getAttribute(
                AdminJwtSignatureFilter.VERIFICATION_ATTRIBUTE);
        if (result == null) {
            // 🔴 兜底：Filter 未命中（路径判据不一致 / Filter 未被注册）⇒ 就地验签。
            //   行为与 DRIFT-USERAPI-009② 修复前完全一致（401 照常）⇒ **不会造成鉴权绕过**；
            //   但验签落回 3s 预算**之内** ⇒ 该路径上 ② 未闭环 ⇒ 必须大声记日志。
            log.warn("admin JWT 验签未命中 filter 缓存 ⇒ 就地验签（DRIFT-USERAPI-009② 在此路径未闭环）: {}", uri);
            request.setAttribute(AdminJwtSignatureFilter.VERIFICATION_SOURCE_ATTRIBUTE,
                    AdminJwtSignatureFilter.SOURCE_INLINE);
            result = verifier.verify(request.getHeader("Authorization"), uri);
        } else {
            request.setAttribute(AdminJwtSignatureFilter.VERIFICATION_SOURCE_ATTRIBUTE,
                    AdminJwtSignatureFilter.SOURCE_FILTER);
        }

        // 验签失败 ⇒ 原样重抛（异常对象由 Filter 产出 ⇒ @ExceptionHandler 仍管得到 ⇒ 401 信封形状不变）
        if (result.failed()) {
            throw result.failure();
        }

        // claim 可用才写 userId attribute
        // ⚠️ admin 侧与 user 侧**刻意不同**：畸形 claim 在 admin 侧是 **401**（由 verifier 产出 failure），
        //   而 user 侧按 DRIFT-USERAPI-001 改成「不拒绝、交给 identity 层」⇒ **不在此统一两域**。
        request.setAttribute("userId", Long.valueOf(result.uid()));

        // ---------- 阶段 2：黑名单检查（对应 B 侧 CheckBlacklistMiddleware.Handle）----------
        // B 侧 checkblacklistmiddleware.go:22-44：
        //   authHeader := r.Header.Get("Authorization"); if authHeader == "" { next; return }
        //   token := strings.TrimPrefix(authHeader, "Bearer ")   ← 大小写【敏感】的精确前缀
        //   if token == "" { next; return }
        //   exists, err := Redis.ExistsCtx(key); if err == nil && exists { 401 + body }
        //   ctx := context.WithValue(ctx, "jwt_token", token)   ← 写的是 TrimPrefix 结果
        //
        // 注意 B 侧 内部【两段取法本就不同】：Authorize 用大小写不敏感的 strip，
        // 这里用大小写敏感的 TrimPrefix。故 "bearer <token>" 在 B 侧 里
        // 验签用 "<token>"、黑名单查 "bearer <token>"。这是 B 侧 自身的不一致，本实现原样保留。
        String jwtToken = result.trimmedToken();
        if (!jwtToken.isEmpty() && isBlacklisted(jwtToken)) {
            log.warn("admin token in blacklist, uri={}", uri);
            throw new AuthenticationException(
                    ErrCodes.ERROR_TOKEN_RUNTIME,
                    ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        // B 侧 只有在 header 非空且 TrimPrefix 结果非空时才写 jwt_token；
        // 上面已排除两种为空的分支，此处必然写入。
        request.setAttribute("jwt_token", jwtToken);
        if (!jwtToken.equals(result.signatureToken())) {
            // 与 B 侧 一致的不一致：非 "Bearer " 规范写法下两段取法不同。保留 warn 便于排查。
            log.warn("admin blacklist token != jwt token (auth header is not 'Bearer <token>')");
        }
        return true;
    }

    /**
     * Redis 黑名单查询 —— fail-open 与 B 侧 逐条对齐。
     *
     * <p>B 侧: {@code exists, err := m.Redis.ExistsCtx(...); if err == nil && exists { 401 }}。
     * Redis 故障时 {@code err != nil} ⇒ 条件为 false ⇒ <b>放行</b>，不阻塞业务。
     * 本实现把异常吞成 {@code null}，随后 {@code exists != null && exists} 同样为 false ⇒ 放行。
     */
    private boolean isBlacklisted(String token) {
        String blacklistKey = BLACKLIST_KEY_PREFIX + token;
        Boolean exists;
        try {
            exists = redisTemplate.hasKey(blacklistKey);
        } catch (Throwable t) {
            log.warn("admin Redis blacklist check failed : {}", t.getMessage());
            exists = null;
        }
        return exists != null && exists;
    }

    /**
     * <p><b>admin-api 的 JWT 路径判据 —— Filter 与 Interceptor 共用同一份</b>。
     *
     * <h2>B 侧依据（真源，逐行）</h2>
     * <pre>
     *   service/user/admin/api/internal/handler/routes.go
     *     server.AddRoutes(
     *         rest.WithMiddlewares([]rest.Middleware{serverCtx.CheckBlacklistMiddleware}, ...),
     *         rest.WithJwt(serverCtx.Config.AdminAuth.AccessSecret),
     *         rest.WithPrefix("/admincenter/v1"))
     *     server.AddRoutes(
     *         []rest.Route{{Method: http.MethodPost, Path: "/login"}},
     *         rest.WithPrefix("/admincenter/v1"))
     *     server.AddRoutes(
     *         []rest.Route{{Method: http.MethodPost, Path: "/create"}},
     *         rest.WithPrefix("/admincenter/v1"))
     * </pre>
     * ⇒ <b>public = login / create</b>（这两段<b>不</b>带 {@code WithJwt}），其余 11 个端点带 JWT
     * ⇒ 即本类的 {@link #isProtected}。
     *
     * <p>⚠️ B 侧的 prefix 是 {@code /admincenter/v1}，RAG 侧是 {@code /admin/v1}
     * ⇒ 属<b>已登记的表达层差异</b>（路径重命名），本类按 <b>RAG 侧实际路径</b>判定
     * ⇒ 与 {@code controller/AdminController} 的 {@code @RequestMapping("/admin/v1")} 一致。
     *
     * <h2>为什么必须只有一份</h2>
     * <p>验签拆成两段：验签在 {@link AdminJwtSignatureFilter}（Servlet Filter，3s 预算之外），
     * 响应在 {@code AdminJwtAuthInterceptor}（HandlerInterceptor，3s 预算之内）。
     * <p>两段若各写一份路径判据，就会出现「Filter 判为受保护、Interceptor 判为放行」的缝隙：
     * <ul>
     *   <li>后者 ⇒ <b>鉴权被绕过</b> ⇒ 安全漏洞；</li>
     *   <li>前者 ⇒ interceptor 拿不到验签结果 ⇒ 落进兜底分支
     *       ⇒ 验签退回 3s 预算之内 ⇒ <b>DRIFT-USERAPI-009② 静默不闭环</b>。</li>
     * </ul>
     * ⇒ 判据收敛到这一个类，两个缝隙同时消失。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/auth/AdminJwtAuthPaths.java}。
     */
    public static final class AdminJwtAuthPaths {

        /** public 路径（HUMAN_ONLY.MD 决策：login / create 不挂 JWT）。 */
        public static final Set<String> PUBLIC_PATHS = Set.of(
                "/admin/v1/login", "/admin/v1/create"
        );

        /**
         * <b>🔴 2026-11-02 真 Tomcat 契约测试实测后改：受保护路径从「前缀」改成<b>显式路径集合</b>。</b>
         *
         * <p><b>为什么</b>：此前 {@code isProtected} 判的是「URI 以 {@code /admin/v1} 开头」，
         * 而 Spring Boot 的静态资源 handler（{@code /**} → {@code ResourceHttpRequestHandler}）
         * <b>会兜住任何未知路径</b> ⇒ ⇒ {@code /admin/v1/not-exist} 也<b>有 handler</b>
         * ⇒ ⇒ 拦截器会被调用 ⇒ ⇒ 按前缀判 ⇒ <b>把 404 顶成 401</b>（实测复现）。
         *
         * <p><b>B 出处（go-zero v1.10.2）</b>：JWT 中间件是<b>挂在 route 上</b>的
         * （{@code rest/engine.go:103 bindRoute → chn = ng.appendAuthHandler(fr, chn, verifier)}），
         * 而<b>未知路径根本进不了 route</b>：{@code rest/engine.go:244-268 notFoundHandler}
         * <b>另起一条链</b>（只有 TraceHandler + 日志中间件）⇒ <b>没有鉴权</b>
         * ⇒ ⇒ B 侧未知路径 = <b>404</b>。
         *
         * <p>⇒ ⇒ 判据必须与 B 侧同构：<b>「这个 URI 是不是 admin 的那 11 个受保护端点」</b>，
         * <b>不是</b>「它是不是长这样」。
         * ⇒ ⇒ 本集合是 <b>L1 契约</b>（与 {@code controller/AdminController} 的 13 个
         * {@code @PostMapping} 一一对应）⇒ 由
         * {@code AdminHttpContractRealTomcatTest#pathSetMatchesControllerMappings} 钉死，
         * <b>加端点忘了加这里 ⇒ 测试立刻红</b>。
         */
        public static final Set<String> PROTECTED_PATHS = Set.of(
                "/admin/v1/logout",
                "/admin/v1/getself",
                "/admin/v1/update",
                "/admin/v1/updateuser",
                "/admin/v1/delete",
                "/admin/v1/resetuserpassword",
                "/admin/v1/banuser",
                "/admin/v1/unbanuser",
                "/admin/v1/getuser",
                "/admin/v1/getuserlist",
                "/admin/v1/invite/create"
        );

        private AdminJwtAuthPaths() {
        }

        /** 是否 public（不挂 JWT，直接放行）。 */
        public static boolean isPublic(String uri) {
            return uri != null && PUBLIC_PATHS.contains(uri);
        }

        /**
         * 是否受保护（要验签）—— <b>显式路径集合</b>（见 {@link #PROTECTED_PATHS} 的论证）。
         *
         * <p>⚠️ <b>集合口径</b>：public 的 2 个 + protected 的 11 个 = 13 个 = B 侧全部端点。
         * <b>集合外的任何 URI</b>（未知路径、其它域的路径）⇒ <b>一律不鉴权</b>
         * ⇒ ⇒ 与 B 侧「JWT 挂在 route 上、未知路径走 notFoundHandler（无鉴权）」同构。
         */
        public static boolean isProtected(String uri) {
            return uri != null && PROTECTED_PATHS.contains(uri);
        }
    }

    /**
     * <p>一次 admin JWT 验签的结果 —— <b>数据载体</b>，不含行为。
     *
     * <h2>与 user-api 同名类型的差异（<b>刻意不同</b>）</h2>
     *
     * <p>user-api 的验签结果类型带 {@code claimUsable} 标志位，
     * 因为 <b>user 侧畸形 claim 不拒绝</b>（B 侧的 {@code rest.WithJwt} 只验签名 + exp ⇒
     * 畸形 claim 的后果发生在业务层 ⇒ HTTP 200 + 业务码 1006，见 DRIFT-USERAPI-001）。
     * <p>而 <b>admin 侧畸形 claim 是 401</b> ⇒ ⇒ admin 的失败<b>只有一种形态</b>（要抛异常）
     * ⇒ ⇒ 不需要标志位：{@code failure == null} ⇔ 验签通过且 claim 可用。
     *
     * @param uid            claim {@code userId} 的值（claim 不可用时为 {@code -1}，且此时 {@code failure != null}）
     * @param signatureToken <b>验签用</b>的 token（B 侧 {@code stripBearerPrefixFromTokenString} 的产物）
     * @param trimmedToken   <b>黑名单 key 用</b>的 token（B 侧 {@code TrimPrefix} 的产物）—— 两者<b>可以不同</b>（B 侧 自身如此）
     * @param failure        验签失败时的异常（{@code null} = 验签通过）
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/auth/AdminJwtVerificationResult.java}。
     */
    public record AdminJwtVerificationResult(
            long uid,
            String signatureToken,
            String trimmedToken,
            RuntimeException failure
    ) {

        /** 验签通过。 */
        public static AdminJwtVerificationResult ok(long uid, String signatureToken, String trimmedToken) {
            return new AdminJwtVerificationResult(uid, signatureToken, trimmedToken, null);
        }

        /** 验签失败 —— {@code failure} 必须非 null（会被 interceptor 原样重抛）。 */
        public static AdminJwtVerificationResult failed(RuntimeException failure) {
            return new AdminJwtVerificationResult(-1L, "", "", failure);
        }

        /** 验签是否失败。 */
        public boolean failed() {
            return failure != null;
        }
    }
}
