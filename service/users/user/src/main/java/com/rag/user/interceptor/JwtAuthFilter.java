package com.rag.user.interceptor;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <p><b>JWT 验签 Filter</b> —— 把验签搬出 3s 预算，对齐 go-zero 的链序。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B: service/users/user/user-api/src/main/java/com/rag/users/user/api/auth/GoStyleJwtAuthFilter.java
 * </pre>
 *
 * <h2>为什么 Java 侧必须用 Filter</h2>
 * <p>go-zero 的链序（{@code rest/engine.go:101-113} ＋ {@code rest/chain/chain.go:38-43}）：
 * <pre>
 *   原生中间件 → <b>JWT 验签</b> → observability(3s 计时) → 黑名单 → handler
 * </pre>
 * ⇒ 3s 计时器在验签<b>之后</b>起算 ⇒ <b>JWT 验签耗时不计入 3s 预算</b>。
 * Servlet 侧所有 {@code Filter} 都在 {@code DispatcherServlet} <b>外面</b>、
 * {@code HandlerInterceptor} 在<b>里面</b> ⇒ 验签要出预算，就必须搬进 Filter。
 *
 * <h2>🔴 本 Filter 的顺序为什么是 {@code HIGHEST_PRECEDENCE + 6}</h2>
 * <p>共享层（{@code common/}）已占用的槽位（<b>C 侧实测值</b>）：
 * <pre>
 *   +2  PathNormalizingFilter
 *   +3  TraceparentFilter        （起 SERVER span）
 *   +4  MaxConnsFilter
 *   +5  熔断（RouteIsolationFilter）
 *   +6  CPU 降载（AdaptiveShedder）
 *   +7  HttpMiddleware           （3s 预算在这里起算）
 *   +8  MaxBytesFilter
 *   +9  JsonBodyLimitFilter
 * </pre>
 * <p>本 Filter 的合法窗口是 <b>[+3, +6]</b>，两端各有一条硬约束：
 * <ul>
 *   <li><b>下界 +3</b>：{@code TraceparentFilter}(+3) 会起一个 <b>SERVER span</b>
 *       ⇒ 本 Filter 必须落在它<b>之后</b>，否则验签跑到 span 外（观测面差异）。</li>
 *   <li><b>上界 +6</b>：{@code HttpMiddleware}(+7) 在 submit worker 线程之前起算 deadline
 *       ⇒ 落在 +7 之后 = 验签仍在预算内 ⇒ <b>B 侧 {@code DRIFT-USERAPI-009②} 不闭环</b>。</li>
 * </ul>
 * ⇒ ⇒ [+3, +6] 的槽位已被共享层占满 ⇒ 本 Filter 与 CPU 降载（+6）<b>并列</b>。
 * <p>⚠️ <b>并列是否安全</b> —— <b>安全</b>，因为<b>本 Filter 绝不写响应</b>（见下）⇒ 无论谁先谁后，响应结果完全相同。
 *
 * <h2>🔴🔴 本 Filter <b>绝不写响应</b>（本类最吃重的不变式）</h2>
 * <p>若本 Filter 在验签失败时<b>自己</b>写 401，则：
 * <ul>
 *   <li>本 Filter(+6) 排在 {@code MaxBytesFilter}(+8) <b>之前</b> ⇒ <b>401 会压过 413</b>；</li>
 *   <li>而 Go 侧 MaxBytes 是 native middleware，排在验签<b>之外</b>（更外层）⇒ Go 是 <b>413 压过 401</b>。</li>
 * </ul>
 * ⇒ 那会把「413 压过 401」这条语义变成新漂移。
 * ⇒ ⇒ <b>所以本 Filter 只验签、只把结果放进 request attribute、然后放行</b>；
 * 401 仍由 {@link JwtAuthInterceptor} 抛 ⇒
 * <ul>
 *   <li>{@code @ExceptionHandler} 仍然管得到 ⇒ <b>401 + 完整信封</b>的形状逐字不变；</li>
 *   <li>401 发生在所有 Filter <b>之后</b> ⇒ MaxBytes 的 413 仍然压过 401（与 Go 一致）。</li>
 * </ul>
 * ⇒ ⚠️ <b>改本类时若要写响应，必须同时重做「413 压过 401」的语义论证。</b>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class JwtAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** 验签结果在 request attribute 里的键（由本 Filter 写、由 {@link JwtAuthInterceptor} 读）。 */
    public static final String VERIFICATION_ATTRIBUTE = "com.rag.user.jwt.verification";

    /**
     * 验签<b>来源</b>标记（{@code filter} / {@code inline}）—— 供守门测试断言
     * 「interceptor 确实消费了 Filter 的结果」（否则 {@code DRIFT-USERAPI-009②} 会静默不闭环）。
     */
    public static final String VERIFICATION_SOURCE_ATTRIBUTE = "com.rag.user.jwt.verificationSource";

    /** 来源 = 本 Filter（验签在 3s 预算之外）。 */
    public static final String SOURCE_FILTER = "filter";

    /** 来源 = interceptor 兜底就地验签（验签在 3s 预算之内 ⇒ ② 未闭环）。 */
    public static final String SOURCE_INLINE = "inline";

    private final JwtSignatureVerifier verifier;

    public JwtAuthFilter(JwtSignatureVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String uri = request.getRequestURI();
        if (JwtAuthPaths.isProtected(uri)) {
            JwtVerificationResult result = verifier.verify(request.getHeader("Authorization"), uri);
            request.setAttribute(VERIFICATION_ATTRIBUTE, result);
            request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);
            if (result.failed()) {
                // 🔴 只记录，**不写响应**（见类注释「本 Filter 绝不写响应」）。
                log.debug("JWT verification failed (deferred to interceptor) : {}", uri);
            }
        }
        // 🔴 无条件放行：验签失败也要继续走完 Filter 链 ⇒
        //    MaxBytes(+8) 仍有机会写 413（Go 侧 MaxBytes 在验签之外 ⇒ 413 压过 401），
        //    最终的 401 由 interceptor 抛（@ExceptionHandler 仍管得到 ⇒ 信封形状不变）。
        chain.doFilter(req, resp);
    }
}
