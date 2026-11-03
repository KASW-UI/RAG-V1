package com.rag.like.support;

import com.rag.common.security.JwtUtil;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Date;

/**
 * <p><b>like 域测试用 JWT 构造器</b>。
 *
 * <h2>⚠️ 密钥必须与 {@code src/test/resources/application-test.properties} 里的完全一致</h2>
 * <p>否则验签必失败（401），而且失败原因会被误判成「鉴权层坏了」。
 *
 * <h2>⚠️ 四种 token 形态对应 like 域四条不同的判据</h2>
 * <table border="1">
 *   <caption>token 形态 → 被测判据</caption>
 *   <tr><th>形态</th><th>期望</th><th>依据</th></tr>
 *   <tr>
 *     <td>数字 {@code userId} claim</td>
 *     <td>正常</td>
 *     <td>正常签发</td>
 *   </tr>
 *   <tr>
 *     <td><b>字符串</b> {@code userId} claim</td>
 *     <td><b>被拒</b> ⇒ HTTP 200 + {@code {code:1006}}（<b>不是</b> 401）</td>
 *     <td>like 域的 {@code LikeApiSupport#userId} 只接受<b>整型</b>
 *     （Byte/Short/Integer/Long/BigInteger）⇒ 字符串落 token_invalid 分支
 *   </tr>
 *   <tr>
 *     <td><b>小数</b> {@code userId} claim</td>
 *     <td><b>被拒</b> ⇒ HTTP 200 + {@code {code:1006}}</td>
 *     <td>Go 侧 {@code json.Number.Int64()} 对 {@code "1.5"} 失败 ⇒ 同口径</td>
 *   </tr>
 *   <tr>
 *     <td>无 {@code userId} claim</td>
 *     <td><b>HTTP 200 + {@code {code:1006}}</b>（不是 401）</td>
 *     <td>验签成功但 claim 缺席 ⇒ 业务层判定失败</td>
 *   </tr>
 * </table>
 *
 * <h3>🔴 为什么「字符串 claim」在 like 域是<b>拒绝</b>而 follow 域是<b>接受</b></h3>
 * <p>这是<b>逐域</b>的判据（<b>同名文件、不同内容</b> ⇒ AGENTS.md §八「逐域读」）：
 * like 域的判据来自 B 侧 {@code like-api/.../service/LikeApiSupport.java}，
 * 它对 claim 只接受整型；follow 域的判据来自 B 侧 follow 的 {@code helper.go:23}
 * （接受字符串并 ParseInt）⇒ ⇒ <b>两个域的测试期望值不同，不能互相照抄</b>。
 */
public final class LikeTestSupport {

    /**
     * 与 {@code application-test.properties} 一致的密钥（36 字节 UUID 形态 ⇒ HMAC-SHA256 合规）。
     */
    public static final String SECRET = "5b7d3f19-8c2a-4e64-b0d5-1f3a9c7e2d48";

    private static final long EXPIRE_SEC = 3600L;

    private LikeTestSupport() {
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
     * {@code userId} claim 是<b>字符串</b>的 token —— like 域<b>拒绝</b>它（⇒ 1006）。
     */
    public static String tokenWithStringUserIdClaim(String claimValue) {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("userId", claimValue)
                .signWith(Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(SECRET)))
                .compact();
    }

    /**
     * {@code userId} claim 是<b>小数</b>的 token —— like 域<b>拒绝</b>它（⇒ 1006）。
     *
     * <p>⚠️ jjwt 会把 JSON 小数 claim 解成 {@code Double} ⇒ 落
     * {@code LikeApiSupport#userId} 的「非整型」分支。
     */
    public static String tokenWithDecimalUserIdClaim(double claimValue) {
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

    /** 已过期的 token（⇒ 验签失败 ⇒ 401 空 body）。 */
    public static String expiredToken(long userId) {
        long past = System.currentTimeMillis() / 1000L - 7200L;
        return JwtUtil.getToken(SECRET, past, 1L, userId);
    }
}
