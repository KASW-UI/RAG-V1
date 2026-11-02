package com.rag.admin.interceptor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * <p><b>职责</b>：把 admin JWT 的<b>验签</b>搬出 3s 预算 —— 只验签、只把结果放进 request attribute、
 * <b>绝不写响应</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>类名 {@code GoStyleJwtAuthFilter} → {@code AdminJwtSignatureFilter}
 *           （B 侧的 {@code GoStyle*} 命名是 B 仓对 go-zero 的前缀约定，本仓不沿用；<b>行为逐条未动</b>）；</li>
 *       <li>基类 {@code jakarta.servlet.Filter} → Spring 的 {@link OncePerRequestFilter}
 *           （本仓 Boot 3.5 / Spring 6.2 的惯用形态）。
 *           ⚠️ <b>可观察差异</b>：{@code OncePerRequestFilter} 默认<b>不</b>在
 *           ASYNC / ERROR 二次派发时重复执行（{@code shouldNotFilterAsyncDispatch} 默认 true），
 *           而 B 侧的裸 {@code Filter} 每次派发都会跑 ⇒ ⇒ 本域 13 个端点全是同步 MVC
 *           ⇒ ⇒ <b>该差异在本域不可观测</b>；若将来引入异步端点，必须回来对齐。</li>
 *       <li>内部 request attribute 的<b>取值</b>：B 侧用 B 的包名前缀
 *           （{@code com.rag.users.admin.api.jwt.verification}）⇒ 本仓用本域包名前缀。
 *           ⚠️ 该 attribute <b>不进入任何 wire</b>（不发客户端、不被 gRPC 面读）⇒ <b>不是 L1 面</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。链序、槽位、「绝不写响应」这条最吃重的不变式，全部逐条保留
 *       （见下）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/auth/GoStyleJwtAuthFilter.java}。</li>
 * </ol>
 *
 * <h2>🔴 DRIFT-USERAPI-009②（admin）：把 JWT <b>验签</b>搬出 3s 预算 —— 对齐 B 侧的链序。</h2>
 *
 * <h2>B 侧链序（真源，逐行核实 go-zero@v1.10.2）</h2>
 * <pre>
 *   rest/engine.go:101-113  bindRoute：
 *     chn = ng.buildChainWithNativeMiddlewares(...)   // 原生中间件（索引 0 = 最外层）
 *     chn = ng.appendAuthHandler(fr, chn, verifier)   // ← JWT 验签
 *     for _, mw := range ng.middlewares { ... }        // ← observability(3s) 在 JWT **之内**
 *     handle = chn.ThenFunc(route.Handler)
 *   rest/chain/chain.go:38-43  Append = 「加在请求流的最后」⇒ 越 Append 越靠内
 *
 *   ⇒ 执行序：原生中间件 → **JWT 验签** → **observability(3s 计时)** → 黑名单 → handler
 *   ⇒ 3s 计时器在 Authorize **之后**起算 ⇒ **JWT 验签耗时不计入 3s 预算**
 * </pre>
 *
 * <p>⚠️ admin 侧黑名单是 <b>route 级</b>中间件（{@code rest.WithMiddlewares} ⇒
 * {@code rest/server.go:222-233 WithMiddleware} 把 {@code middleware(route.Handler)} 包在
 * handler 外 ⇒ 比 chain 任何元素都内层）⇒ 黑名单在 observability(3s) <b>之内</b>
 * ⇒ ⇒ 黑名单**留在 interceptor**（不变）即可，本类只搬<b>验签</b>。
 *
 * <h2>为什么 Java 侧必须用 Filter</h2>
 *
 * <p>Servlet 的<b>所有</b> {@code Filter} 都在 {@code DispatcherServlet} <b>外面</b>，
 * {@code HandlerInterceptor} 在<b>里面</b> ⇒ 验签要出 3s 预算，就必须是 Filter
 * （{@code HttpMiddlewareFilter} 也是 Filter ⇒ 数值更小的 {@code @Order} 先执行 ⇒ 包在它外面）。
 *
 * <h2>🔴 本 Filter 的顺序为什么是 {@code HIGHEST_PRECEDENCE + 6}</h2>
 *
 * <p>共享层（{@code com.rag.common} starter）已占用的槽位（<b>本仓实测值</b>）：
 * <pre>
 *   +2  PathNormalizingFilter        （路径清洗）
 *   +3  TraceparentFilter            （起 SERVER span）
 *   +4  MaxConnsFilter
 *   +5  BreakerFilter
 *   +6  CpuSheddingFilter
 *   +7  HttpMiddlewareFilter         （3s 预算在这里起算）
 *   +8  MaxBytesFilter
 *   +9  JsonBodyLimitFilter
 * </pre>
 *
 * <p>本 Filter 的合法窗口是 <b>[+3, +6]</b>：
 * <ul>
 *   <li><b>下界 +3</b>：{@code TraceparentFilter}(+3) 会起一个 <b>SERVER span</b>
 *       ⇒ B 侧 TraceHandler 在链首、JWT 验签在 span <b>之内</b>
 *       ⇒ 本 Filter 必须落在 traceparent <b>之后</b>。</li>
 *   <li><b>上界 +6</b>：{@code HttpMiddlewareFilter}(+7) 在 <b>submit worker 线程之前</b>起算 deadline
 *       ⇒ 落在 +7 之后 = 验签仍在预算内 ⇒ <b>DRIFT-USERAPI-009② 不闭环</b>。</li>
 * </ul>
 *
 * <p>⇒ [+3, +6] 四个槽位<b>全被共享层占满</b> ⇒ 本 Filter 与
 * {@code CpuSheddingFilter}(+6) <b>并列</b>。
 * <p>⚠️ <b>并列是否安全？</b> —— <b>安全</b>，因为<b>本 Filter 绝不写响应</b>（见下）⇒
 * 无论谁先谁后，<b>响应结果完全相同</b>。
 *
 * <h2>🔴🔴 本 Filter <b>绝不写响应</b>（本类最吃重的不变式）</h2>
 *
 * <p>若本 Filter 在验签失败时<b>自己</b>写 401，则：
 * <ul>
 *   <li>本 Filter(+6) 排在 {@code MaxBytesFilter}(+8) <b>之前</b>
 *       ⇒ <b>401 会压过 413</b>；</li>
 *   <li>而 B 侧 MaxBytes 是 native middleware，排在 Authorize <b>之外</b>（更外层）
 *       ⇒ B 侧 是 <b>413 压过 401</b>。</li>
 * </ul>
 * ⇒ 那会把「413 压过 401」这条<b>已被变异测试锁死</b>的语义变成新漂移。
 * <p>⇒ <b>所以本 Filter 只验签、只把结果放进 request attribute、然后放行</b>；
 * 401 仍由 {@code AdminJwtAuthInterceptor} 抛 ⇒
 * <ul>
 *   <li>{@code @ExceptionHandler}（{@code AdminApiExceptionHandler}）仍然管得到
 *       ⇒ <b>401 + 完整信封</b>的形状逐字不变（admin 侧与 user 侧同款）；</li>
 *   <li>401 发生在所有 Filter <b>之后</b> ⇒ MaxBytes 的 413 仍然压过 401（与 B 侧 一致）。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class AdminJwtSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminJwtSignatureFilter.class);

    /** 验签结果在 request attribute 里的键（由本 Filter 写、由 {@code AdminJwtAuthInterceptor} 读）。 */
    public static final String VERIFICATION_ATTRIBUTE = "com.rag.admin.interceptor.jwt.verification";

    /**
     * 验签<b>来源</b>标记（{@code filter} / {@code inline}）—— 供守门测试断言
     * 「interceptor 确实消费了 Filter 的结果」（否则 DRIFT-USERAPI-009② 会静默不闭环）。
     */
    public static final String VERIFICATION_SOURCE_ATTRIBUTE = "com.rag.admin.interceptor.jwt.verificationSource";

    /** 来源 = 本 Filter（验签在 3s 预算之外）。 */
    public static final String SOURCE_FILTER = "filter";

    /** 来源 = interceptor 兜底就地验签（验签在 3s 预算之内 ⇒ ② 未闭环）。 */
    public static final String SOURCE_INLINE = "inline";

    private final AdminJwtSignatureVerifier verifier;

    public AdminJwtSignatureFilter(AdminJwtSignatureVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String uri = request.getRequestURI();
        // ⚠️ ERROR 派发（Tomcat 对 sendError 的二次派发）不是一次业务请求
        //   ⇒ 不做验签。
        // 【B 出处】B 侧 `GoStyleJwtAuthFilter` 是**裸 `Filter`** 且未设 `dispatcherTypes`
        //   ⇒ Spring Boot 默认注册为 `REQUEST + ASYNC` ⇒ ⇒ ERROR 派发**不跑**（同构）。
        // 【⚠️ 2026-11-02 · E-4 实测更正】旧注释写「B 侧未知路径走 notFoundHandler」——
        //   那是 go-zero 原版的依据，而 B 侧是 Java/Boot 4 ⇒ 实测：**B 侧无 404 响应面**
        //   （未知路径：无 token ⇒ 401 + 信封 1006；有 token ⇒ 500 + 信封 5001）。
        //   ⇒ ⇒ 本分支与「未知路径」无关：Boot 3.5 下未知路径由静态资源 handler 兜住，
        //     走的是**首次 REQUEST 派发** ⇒ 不经过本分支。
        //   ⇒ 本 Filter 本来就「绝不写响应」，跳过它只是不产生误导性日志。
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR) {
            chain.doFilter(request, response);
            return;
        }
        if (AdminJwtAuthInterceptor.AdminJwtAuthPaths.isProtected(uri)) {
            AdminJwtAuthInterceptor.AdminJwtVerificationResult result =
                    verifier.verify(request.getHeader("Authorization"), uri);
            request.setAttribute(VERIFICATION_ATTRIBUTE, result);
            request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);
            if (result.failed()) {
                // 🔴 只记录，**不写响应**（见类注释「本 Filter 绝不写响应」）。
                log.debug("admin JWT verification failed (deferred to interceptor) : {}", uri);
            }
        }
        // 🔴 无条件放行：验签失败也要继续走完 Filter 链 ⇒
        //    MaxBytes(+8) 仍有机会写 413（B 侧 MaxBytes 在 Authorize 之外 ⇒ 413 压过 401），
        //    最终的 401 由 interceptor 抛（@ExceptionHandler 仍管得到 ⇒ 信封形状不变）。
        chain.doFilter(request, response);
    }
}
