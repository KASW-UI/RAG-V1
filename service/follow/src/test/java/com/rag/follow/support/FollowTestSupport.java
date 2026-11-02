package com.rag.follow.support;

import com.rag.common.security.JwtUtil;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Date;

/**
 * <p><b>follow 域测试用 JWT 构造器</b>。
 *
 * <h2>⚠️ 密钥必须与 {@code src/test/resources/application-test.properties} 里的完全一致</h2>
 * <p>否则验签必失败（401），而且失败原因会被误判成「鉴权层坏了」。
 *
 * <h2>⚠️ 三种 token 形态对应 B 侧三条不同的判据</h2>
 * <table border="1">
 *   <caption>token 形态 → 被测判据</caption>
 *   <tr><th>形态</th><th>期望</th><th>B 出处</th></tr>
 *   <tr>
 *     <td>数字 {@code userId} claim</td>
 *     <td>正常</td>
 *     <td>正常签发</td>
 *   </tr>
 *   <tr>
 *     <td><b>字符串</b> {@code userId} claim</td>
 *     <td><b>被接受</b>（转成 int64）</td>
 *     <td>B 侧 {@code helper.go:23} 的 {@code case string: strconv.ParseInt(...)}</td>
 *   </tr>
 *   <tr>
 *     <td>无 {@code userId} claim</td>
 *     <td><b>200 + {code:5004}</b>（不是 401）</td>
 *     <td>B 侧 {@code extractUserID} 失败 ⇒ {@code ErrorUnauthorized}</td>
 *   </tr>
 * </table>
 */
public final class FollowTestSupport {

    /**
     * 与 {@code application-test.properties} 一致的密钥（36 字节 UUID 形态 ⇒ HMAC-SHA256 合规）。
     */
    public static final String SECRET = "ae0536f9-6450-4606-8e13-5a19ed505da0";

    private static final long EXPIRE_SEC = 3600L;

    private FollowTestSupport() {
    }

    /** 正常 token：数字 {@code userId} claim。 */
    public static String token(long userId) {
        return JwtUtil.getToken(SECRET, System.currentTimeMillis() / 1000L, EXPIRE_SEC, userId);
    }

    /** {@code Authorization} 头的完整取值。 */
    public static String bearer(long userId) {
        return "Bearer " + token(userId);
    }

    /**
     * {@code userId} claim 是<b>字符串</b>的 token —— follow 域<b>接受</b>它（与 message 域不同）。
     */
    public static String tokenWithStringUserIdClaim(String claimValue) {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("userId", claimValue)
                .signWith(Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(SECRET)))
                .compact();
    }

    /** 不带 {@code userId} claim 的 token。 */
    public static String tokenWithoutUserIdClaim() {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("scope", "user")
                .signWith(Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(SECRET)))
                .compact();
    }

    /** 用<b>别的</b>密钥签的 token（⇒ 验签失败 ⇒ 401 空 body）。 */
    public static String tokenSignedWithWrongSecret(long userId) {
        return JwtUtil.getToken("00000000-0000-0000-0000-000000000000",
                System.currentTimeMillis() / 1000L, EXPIRE_SEC, userId);
    }
}
