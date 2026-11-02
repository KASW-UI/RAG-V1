package com.rag.user.interceptor;

import com.rag.common.exception.AuthenticationException;
import com.rag.common.exception.BusinessException;
import com.rag.common.security.BearerTokenExtractor;
import com.rag.common.security.JwtUtil;
import com.rag.user.config.UserApiProperties;
import com.rag.user.contract.ErrCodes;
import com.rag.user.contract.ErrMsg;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

/**
 * <p><b>JWT 验签</b> —— 只做「解析 ＋ 验签名 ＋ 取 claim」，<b>不写响应、不查 Redis</b>。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B: service/users/user/user-api/src/main/java/com/rag/users/user/api/auth/JwtSignatureVerifier.java
 * </pre>
 *
 * <h2>为什么验签要与拦截器分开（两个职责，两个生命周期）</h2>
 * <p>B 侧把验签搬进 Servlet Filter 是为了对齐 go-zero 的链序
 * （原生中间件 → <b>JWT 验签</b> → observability(3s 计时) → 黑名单 → handler）
 * ⇒ <b>3s 预算不覆盖 JWT 验签</b>。
 * Servlet 的所有 {@code Filter} 都在 {@code DispatcherServlet} <b>外面</b>，
 * {@code HandlerInterceptor} 在<b>里面</b> ⇒ 验签要出预算就必须搬进 Filter
 * ⇒ 本类被 {@link JwtAuthFilter} 调用（Filter 侧），结果经 request attribute 交给
 * {@link JwtAuthInterceptor}（Interceptor 侧）。
 *
 * <p><b>⚠️ claim 的「存在性」与「取值」都<b>不</b>在鉴权层拒绝</b>（B 侧 {@code DRIFT-USERAPI-001} 已修）：
 * go-zero 的 {@code rest.WithJwt} 只验签名与 exp，claim 校验属于 identity 层
 * ⇒ 畸形 claim 的后果发生在业务层（{@code IdentityResolver.claimedUID}）⇒ HTTP 200 ＋ 业务码 1006。
 * ⇒ 本类<b>只记 warn</b>、<b>不写 userId attribute</b>、<b>不抛异常</b>，把判定交给业务层。
 *
 * <p><b>⚠️ 黑名单 key 用的 token 与验签用的 token 是两套取法</b>（B 侧自身如此）：
 * 验签用 {@code tokenForSignatureVerification}（大小写不敏感地剥 {@code Bearer } 前缀，不匹配时原样返回），
 * 黑名单用 {@code trimBearerPrefix}（大小写<b>敏感</b>地剥前缀）⇒ 畸形 header 下两者可能不同 ⇒ 逐条照抄。
 */
@Component
public class JwtSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtSignatureVerifier.class);

    private final SecretKey key;

    public JwtSignatureVerifier(UserApiProperties properties) {
        // L4 idiomatization（强制留痕 + Go ↔ RAG 对照）：
        //   Go 出处：service/user/common/jwt/jwt.go `token.SignedString([]byte(secretKey))`
        //     —— Go 侧 golang-jwt 不校验 key 长度，user secret 是 36 字节 UUID 已 ≥ 32 字节，
        //     走「返回 bytes 原长」分支，padding 是 no-op。
        //   Java 表达差异：jjwt 0.12 强制 ≥32 字节，所以即便 user secret 已 ≥32 仍统一调
        //     padToHmacKeySize —— 这样签发方与验签方的 padding 逻辑收口在 `JwtUtil` 一份。
        //   语义等价论证：bytes.length ≥ 32 时返回原 bytes，行为与直接 getBytes 一致；
        //     token 形状、claim、exp 全一致。Go ↔ RAG 无行为差异。
        this.key = Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(properties.getUserAuth().getAccessSecret()));
    }

    /**
     * 验签 —— <b>不写响应</b>；失败时把异常装进 {@link JwtVerificationResult} 交给调用方重抛。
     *
     * @param authHeader 请求的 {@code Authorization} 头（可为 null）
     * @param uri        请求路径（只用于日志）
     */
    public JwtVerificationResult verify(String authHeader, String uri) {
        // ---------- 阶段 1a：Authorization 头缺失 ----------
        // Go 出处：rest/handler/authhandler.go:54-56 ParseToken 失败 ⇒ unauthorized(401)
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("protected path {} without Authorization header", uri);
            return JwtVerificationResult.failed(new AuthenticationException(
                    ErrCodes.ERROR_TOKEN_RUNTIME,
                    ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME)
            ));
        }

        // Go 出处：golang-jwt/jwt/v4@v4.5.2/request/oauth2.go:7-12
        //   stripBearerPrefixFromTokenString：前缀【大小写不敏感】（strings.ToUpper 比较），
        //   且【不匹配时原样返回整串】而不是报错。
        String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
        if (token.isEmpty()) {
            log.warn("protected path {} with empty Bearer token", uri);
            return JwtVerificationResult.failed(new AuthenticationException(
                    ErrCodes.ERROR_TOKEN_RUNTIME,
                    ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME)
            ));
        }

        long uid = -1L;
        boolean claimUsable = false;
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            Object claim = jws.getPayload().get("userId");
            if (claim instanceof Number) {
                uid = ((Number) claim).longValue();
                claimUsable = true;
                // ⚠️ 这里<b>不</b>做 uid <= 0 的拒绝判断 —— 那是业务层（IdentityResolver）的规则。
            } else {
                // 🔴 B 侧 DRIFT-USERAPI-001 已修：**不在鉴权层拒绝畸形 claim**。
                // ⇒ 现在的行为：不写 userId attribute、不抛异常，继续走黑名单检查，
                //   让业务层（IdentityResolver.claimedUID）拒掉它 ⇒ 200 + 1006。
                //   黑名单检查仍会执行 —— Go 的链序是 JWT → 黑名单 → handler，
                //   所以「畸形 claim + 已拉黑」在 B 侧也是 401，本类同样先查黑名单 ⇒ 一致。
                log.warn("JWT claim userId missing or wrong type : {}",
                        claim == null ? "null" : claim.getClass());
            }
        } catch (BusinessException e) {
            // 保持原样上抛（与 B 侧 catch (BusinessException e) { throw e; } 语义一致）
            return JwtVerificationResult.failed(e);
        } catch (Throwable t) {
            // 签名错 / 过期 —— 与 Go 端 silent 行为一致（authhandler.go:56 → unauthorized → 401）
            log.warn("JWT verify failed : {}", t.getMessage());
            return JwtVerificationResult.failed(new AuthenticationException(
                    ErrCodes.ERROR_TOKEN_RUNTIME,
                    ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME)
            ));
        }

        // 黑名单 key 用的 token —— Go 的 CheckBlacklistMiddleware 用的是【TrimPrefix 结果】，
        // 与验签用的 token 是**两套取法**（Go 自身如此，畸形 header 下会不同）。
        String trimmedToken = BearerTokenExtractor.trimBearerPrefix(authHeader);
        return JwtVerificationResult.ok(uid, claimUsable, token, trimmedToken);
    }
}
