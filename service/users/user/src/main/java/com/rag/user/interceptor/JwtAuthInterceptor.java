package com.rag.user.interceptor;

import com.rag.common.exception.AuthenticationException;
import com.rag.user.contract.ErrCodes;
import com.rag.user.contract.ErrMsg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * <p><b>JWT 鉴权拦截器</b> —— 承担 Go 端 {@code rest.WithJwt}（<b>验签结果消费</b>）+
 * {@code CheckBlacklistMiddleware}（<b>黑名单</b>）两段中间件的后半程。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B: service/users/user/user-api/src/main/java/com/rag/users/user/api/interceptor/JwtAuthInterceptor.java
 * </pre>
 *
 * <h2>本类只做三件事</h2>
 * <ol>
 *   <li>消费 Filter 放进 request attribute 的<b>验签结果</b>；</li>
 *   <li>把验签失败<b>原样重抛</b>（异常对象由 Filter 产出 ⇒ {@code @ExceptionHandler} 仍管得到
 *       ⇒ <b>401 + 完整信封</b>的形状逐字不变）；</li>
 *   <li><b>黑名单检查</b>（仍在预算<b>之内</b> ⇒ 与 Go 一致）。</li>
 * </ol>
 *
 * <p><b>L1 行为冻结（与 Go 端 1:1）</b>：
 * <ol>
 *   <li>public 路径（{@code /user/v1/login}、{@code /user/v1/register}）→ <b>直接放行</b>，不校验 token；</li>
 *   <li>protected 路径（其余 7 个）—— 验签由 {@link JwtAuthFilter} 完成，本类消费结果，失败一律 <b>HTTP 401</b>：
 *     <ul>
 *       <li>Authorization 缺失 / token 为空 → {@code AuthenticationException} → 401</li>
 *       <li>JWT 签名错 / 过期 → {@code AuthenticationException} → 401</li>
 *       <li>Redis blacklist（{@code user:jwt_blacklist:<token>}）存在 → {@code AuthenticationException} → 401</li>
 *       <li>Redis 故障 → <b>不阻塞业务</b>（与 Go 端 {@code err == nil && exists} 行为一致，fail-open）</li>
 *     </ul>
 *     以上任一失败都<b>不</b>写 userId / jwt_token attribute。</li>
 *   <li><b>🔴 claim 的「存在性」与「取值」都不在鉴权层拒绝</b>（B 侧 {@code DRIFT-USERAPI-001} 已修）：
 *       「签名合法但 userId claim 缺失 / 是字符串」在 Go 侧<b>不是 401</b>，
 *       而是由业务层 {@code IdentityResolver.claimedUID} 的 type assertion 失败
 *       ⇒ {@code ErrInvalidClaim} ⇒ <b>HTTP 200 + 业务码 1006</b>。</li>
 *   <li><b>uid 取值（{@code uid <= 0}）也不在鉴权层拒绝</b>：那是 identity 层的规则
 *       （{@code IdentityResolver.claimedUID} / {@code claimedUIDAllowNonPositive}）。</li>
 * </ol>
 *
 * <p><b>L4 实现差异</b>：Go 端用两个 {@code rest.Middleware}；Java 端拆成
 * {@code Filter}（验签）＋ {@code HandlerInterceptor}（黑名单 + 抛异常），
 * 以便把验签移出 3s 预算；JWT 算法 HS256 ＋ HMAC-SHA256（与共享层 {@code JwtUtil} 1:1）；
 * Redis key 格式 {@code user:jwt_blacklist:<token>} 1:1 对齐 Go 端。
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    /** Redis blacklist key 前缀（与 Go 端 1:1）。 */
    private static final String BLACKLIST_KEY_PREFIX = "user:jwt_blacklist:";

    private final JwtSignatureVerifier verifier;

    private final StringRedisTemplate redisTemplate;

    public JwtAuthInterceptor(JwtSignatureVerifier verifier, StringRedisTemplate redisTemplate) {
        this.verifier = verifier;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();

        // public 路径直接放行（与 Go 端 rest.AddRoutes(login, register) 不带 WithJwt 一致）
        if (JwtAuthPaths.isPublic(uri)) {
            return true;
        }

        // ---------- 阶段 1：JWT 验签（结果由 JwtAuthFilter 在 3s 预算之外产出）----------
        JwtVerificationResult result = (JwtVerificationResult) request.getAttribute(
                JwtAuthFilter.VERIFICATION_ATTRIBUTE);
        if (result == null) {
            // 🔴 兜底：Filter 未命中（路径判据不一致 / Filter 未被注册）⇒ 就地验签。
            //   行为与 B 侧修复前完全一致（401 照常）⇒ **不会造成鉴权绕过**；
            //   但验签落回 3s 预算**之内** ⇒ 该路径上 ② 未闭环 ⇒ 必须大声记日志。
            log.warn("JWT 验签未命中 filter 缓存 ⇒ 就地验签（DRIFT-USERAPI-009② 在此路径未闭环）: {}", uri);
            request.setAttribute(JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE, JwtAuthFilter.SOURCE_INLINE);
            result = verifier.verify(request.getHeader("Authorization"), uri);
        } else {
            request.setAttribute(JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE, JwtAuthFilter.SOURCE_FILTER);
        }

        // 验签失败 ⇒ 原样重抛（异常对象由 Filter 产出 ⇒ @ExceptionHandler 仍管得到 ⇒ 401 信封形状不变）
        if (result.failed()) {
            throw result.failure();
        }

        // claim 可用才写 userId attribute（与 Go 的「逐个 claim 塞进 ctx」等价）
        if (result.claimUsable()) {
            request.setAttribute(com.rag.users.common.IdentityResolver.CLAIM_ATTRIBUTE, Long.valueOf(result.uid()));
        }

        // ---------- 阶段 2：黑名单检查（对应 Go CheckBlacklistMiddleware.Handle）----------
        // Go 出处 checkblacklistmiddleware.go:22-44：header 为空 → 直接放行；
        // TrimPrefix 后为空 → 直接放行且**不写 jwt_token**；否则查 Redis，
        // 命中即 401；未命中则把【TrimPrefix 结果】写进 ctx["jwt_token"]。
        //
        // 顺序说明：Go 的验签排在黑名单之前 ⇒ 验签失败时黑名单**从不被查**。
        String jwtToken = result.trimmedToken();
        if (!jwtToken.isEmpty() && isBlacklisted(jwtToken)) {
            log.warn("token in blacklist, uid={}", result.claimUsable() ? result.uid() : "n/a");
            throw new AuthenticationException(
                    ErrCodes.ERROR_TOKEN_RUNTIME,
                    ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME)
            );
        }

        request.setAttribute("jwt_token", jwtToken);
        if (!jwtToken.equals(result.signatureToken())) {
            // 与 Go 一致的不一致：非 "Bearer <token>" 规范写法下两段取法不同（Go 自身如此）。保留 warn 便于排查。
            log.warn("user blacklist token != jwt token (auth header is not 'Bearer <token>')");
        }
        return true;
    }

    /**
     * Redis 黑名单查询 —— fail-open 与 Go 逐条对齐。
     *
     * <p>Go: {@code exists, err := m.Redis.ExistsCtx(...); if err == nil && exists { 401 }}。
     * Redis 故障时 {@code err != nil} ⇒ 条件为 false ⇒ <b>放行</b>，不阻塞业务。
     * 本实现把异常吞成 {@code null}，随后 {@code exists != null && exists} 同样为 false ⇒ 放行。
     */
    private boolean isBlacklisted(String token) {
        String blacklistKey = BLACKLIST_KEY_PREFIX + token;
        Boolean exists;
        try {
            exists = redisTemplate.hasKey(blacklistKey);
        } catch (Throwable t) {
            log.warn("Redis blacklist check failed : {}", t.getMessage());
            exists = null;
        }
        return exists != null && exists;
    }
}
