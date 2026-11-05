package com.rag.comment.support;

import com.rag.common.security.JwtUtil;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Date;

/**
 * <p><b>comment 域测试用 JWT 构造器</b>。
 *
 * <h2>⚠️ 密钥必须与 {@code src/test/resources/application-test.properties} 里的完全一致</h2>
 * <p>否则验签必失败（401 空 body），而且失败原因会被误判成「鉴权层坏了」。
 * <p>⇒ 两处值必须<b>逐字一致</b>：{@code comment-api.user-auth.access-secret} 与
 * {@code USER_JWT_SECRET}（后者是主 yml 里 {@code ${USER_JWT_SECRET}} 的解析源）。
 *
 * <h2>🔴 本类签出的 token 与「真实验签路径」的兼容性（最易做错的一处）</h2>
 * <p>本域的真实验签路径是
 * {@code interceptor/CommentJwtAuthInterceptor#preHandle}，它的四个关键约定：
 * <table border="1">
 *   <caption>验签路径的约定 ⇒ 本类怎么配合</caption>
 *   <tr><th>验签侧做的事</th><th>本类的配合</th></tr>
 *   <tr>
 *     <td>验签 key = {@code Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(secret))}
 *         ⇒ 算法名 {@code HmacSHA256}、算法 {@code HS256}</td>
 *     <td>正常 token 走<b>共享层</b> {@link JwtUtil#getToken}（它把 JCA 算法名与
 *         {@code Jwts.SIG.HS256} <b>都钉死</b>）⇒ 两侧字节一致；<br>
 *         非数字 claim 走 {@code Keys.hmacShaKeyFor(...)} ＋ 单参 {@code signWith(key)}
 *         ⇒ 该重载「照着 key 的 JCA 算法名选算法」= {@code HmacSHA256} = {@code HS256}
 *         ⇒ 与验签侧同算法</td>
 *   </tr>
 *   <tr>
 *     <td>claim 的 request attribute 键名 = {@code "userId"}
 *         （{@code CommentJwtAuthInterceptor#CLAIM_ATTRIBUTE}）</td>
 *     <td>本类签的 claim 键名<b>就是</b> {@code "userId"}
 *         ⇒ 取值判定由业务层 {@code service/CommentApiSupport#userId} 负责</td>
 *   </tr>
 *   <tr>
 *     <td><b>不</b>校验 claim 取值（只验签名与 exp）⇒ 验签通过即进业务层</td>
 *     <td>本类<b>刻意</b>造出 4 种「验签能过但取值判定会失败」的形态
 *         （见下表）⇒ 用来断言<b>业务层</b>的 1006，而不是鉴权层的 401</td>
 *   </tr>
 *   <tr>
 *     <td>额外校验 {@code iat}：<b>iat 在未来 ⇒ 401</b>（jjwt 不查 iat，B 侧查）</td>
 *     <td>本类的 {@code iat} 一律取「当前秒或更早」⇒ 不会误触这条</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ 六种 token 形态 ⇒ 对应 comment 域六条不同的判据</h2>
 * <table border="1">
 *   <caption>token 形态 → 被测判据（期望值沿用 B 侧，见「B 出处」）</caption>
 *   <tr><th>形态</th><th>期望</th><th>依据</th></tr>
 *   <tr>
 *     <td>数字 {@code userId} claim</td>
 *     <td>正常</td>
 *     <td>正常签发</td>
 *   </tr>
 *   <tr>
 *     <td><b>字符串</b> {@code userId} claim（可解析为 int64）</td>
 *     <td><b>被接受</b> ⇒ HTTP 200 + {code:200}</td>
 *     <td>本域接受字符串 claim：Go 侧 {@code strconv.ParseInt(value, 10, 64)}</td>
 *   </tr>
 *   <tr>
 *     <td><b>字符串</b> {@code userId} claim（<b>不可</b>解析为 int64）</td>
 *     <td><b>被拒</b> ⇒ HTTP 200 + {code:1006}（<b>不是</b> 401）</td>
 *     <td>Go 的 {@code ParseInt} 失败 ⇒ 业务层判定失败</td>
 *   </tr>
 *   <tr>
 *     <td><b>小数</b> {@code userId} claim</td>
 *     <td><b>被拒</b> ⇒ HTTP 200 + {code:1006}</td>
 *     <td>Go 侧 {@code json.Number.Int64()} 对 {@code "1.5"} 失败 ⇒ 同口径</td>
 *   </tr>
 *   <tr>
 *     <td><b>越界整数</b> {@code userId} claim</td>
 *     <td><b>被拒</b> ⇒ HTTP 200 + {code:1006}</td>
 *     <td>Go 的 {@code ParseInt} 溢出 ⇒ <b>不得回绕成负数</b></td>
 *   </tr>
 *   <tr>
 *     <td>无 {@code userId} claim</td>
 *     <td><b>HTTP 200 + {code:1006}</b>（不是 401）</td>
 *     <td>验签成功但 claim 缺席 ⇒ 业务层判定失败</td>
 *   </tr>
 *   <tr>
 *     <td>错密钥 / 过期</td>
 *     <td><b>401 + 空 body</b></td>
 *     <td>鉴权层就拒了 ⇒ 根本没进业务层</td>
 *   </tr>
 * </table>
 *
 * <h3>🔴 为什么「字符串 claim」在 comment 域是<b>接受</b>而 like 域是<b>拒绝</b></h3>
 * <p>这是<b>逐域</b>的判据（<b>同名文件、不同内容</b> ⇒ AGENTS.md §八「逐域读」）：
 * <ul>
 *   <li><b>comment 域</b>：B 侧 {@code comment-api/…/api/service/CommentApiSupport.java:36}
 *       的 {@code case string: return strconv.ParseInt(value, 10, 64)}
 *       ⇒ <b>接受</b>可解析为 int64 的字符串；</li>
 *   <li><b>like 域</b>：判据来自 B 侧 {@code like-api/…/service/LikeApiSupport.java}，
 *       它只接受<b>整型</b>（Byte/Short/Integer/Long/BigInteger）⇒ <b>拒绝</b>字符串。</li>
 * </ul>
 * ⇒ ⇒ 两个域的 Go 出处不同 ⇒ <b>判定口径必须不同</b> ⇒ ⇒ 本类<b>保留</b>
 * {@link #tokenWithStringUserIdClaim(String)} 是<b>对的</b>，但<b>判据不可跨域统一</b>
 * （把 like 的「字符串 ⇒ 1006」照抄到 comment ⇒ 会把一条<b>本该通过</b>的用例判成失败）。
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比参照实现（like 域同款）多做了什么</b>：
 *       <ul>
 *         <li>新增 {@link #tokenWithDecimalUserIdClaim(double)} 与
 *             {@link #tokenWithBigIntegerUserIdClaim(String)} 两种形态 ——
 *             因为本域业务层<b>逐条</b>有「浮点 ⇒ 1006」「越界整数 ⇒ 1006」两个分支
 *             （B 侧测试资产里也各有对应用例）⇒ like 域没有这两条分支，不需要这两个方法；</li>
 *         <li>密钥换成<b>本域专属</b>的值（与 like 域、B 侧各自不同）⇒
 *             跨域误用同一个 token 会被<b>验签失败</b>当场抓住，而不是静默通过。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 签发方式（共享层 {@link JwtUtil#getToken} ＋
 *       非数字 claim 时用 jjwt 直接签）、算法（{@code HS256}）、claim 键名（{@code "userId"}）、
 *       有效期语义（{@link #EXPIRE_SEC} 秒）全部与参照实现一致；
 *       新增的两个方法只是<b>多造两种 B 侧已定义判据</b>的 token，不改变任何既有方法的产出。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>token 形态与期望值：B 侧测试资产
 *             {@code service/comment/comment-api/src/test/java/com/rag/comment/comment/api/CommentApiBehaviorTest.java}
 *             —— 正常签发 {@code :87-89}、非数字 claim 构造 {@code :341-348}、
 *             浮点 ⇒ 1006 {@code :358-372}、越界整数 ⇒ 1006 {@code :374-387}、
 *             字符串走 ParseInt {@code :389-407}；</li>
 *         <li>uid 判定口径：B 侧
 *             {@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/service/CommentApiSupport.java:26-47}
 *             （Go {@code helper.go:10-29} 的 {@code extractUserID} 复刻）；</li>
 *         <li>验签路径：C 侧
 *             {@code service/comment/src/main/java/com/rag/comment/interceptor/CommentJwtAuthInterceptor.java:92-170}
 *             （key 构造 {@code :92-97}、验签 {@code :140-150}、iat 校验 {@code :152-158}、
 *             attribute 写入 {@code :169}）。</li>
 *       </ul></li>
 * </ol>
 */
public final class CommentTestSupport {

    /**
     * 与 {@code application-test.properties} 一致的密钥（36 字节 UUID 形态 ⇒ HMAC-SHA256 合规）。
     *
     * <p>⚠️ jjwt 0.12 强制 HMAC 密钥 ≥32 字节 ⇒ 36 字节天然满足
     * ⇒ ⇒ 本密钥<b>不依赖</b>共享层 {@link JwtUtil#padToHmacKeySize(String)} 的补齐逻辑
     * ⇒ ⇒ 「密钥长度」这条不变量在本域<b>不构成被测对象</b>（那是 admin 域 15 字节密钥才有的问题）。
     */
    public static final String SECRET = "7c4e2a91-3d68-4f0b-9a52-6e1c74b8d305";

    private static final long EXPIRE_SEC = 3600L;

    private CommentTestSupport() {
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
     * {@code userId} claim 是<b>字符串</b>的 token。
     *
     * <p>🔴 <b>本域接受</b>可解析为 int64 的字符串（Go 侧 {@code strconv.ParseInt}）
     * ⇒ ⇒ 传 {@code "31337"} ⇒ <b>正常通过</b>（HTTP 200 + {code:200}）；
     * 传 {@code "abc"} / {@code "1.5"} ⇒ <b>被拒</b>（HTTP 200 + {code:1006}）。
     *
     * <p>⚠️ <b>与 like 域相反</b>：like 域<b>拒绝</b>一切字符串 claim ⇒ 期望值不可跨域照抄。
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
     * {@code userId} claim 是<b>小数</b>的 token —— 本域<b>被拒</b>（⇒ HTTP 200 + {code:1006}）。
     *
     * <p>⚠️ jjwt 会把 JSON 小数 claim 解成 {@code Double} ⇒ 落
     * {@code CommentApiSupport#userId} 的「非整型」分支。
     */
    public static String tokenWithDecimalUserIdClaim(double claimValue) {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("userId", claimValue)
                .signWith(Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(SECRET)))
                .compact();
    }

    /**
     * {@code userId} claim 是<b>越界整数</b>的 token —— 本域<b>被拒</b>（⇒ HTTP 200 + {code:1006}）。
     *
     * <p>⚠️ 传十进制<b>字面量字符串</b>（如 {@code "9223372036854775808"}），
     * 内部转成 {@link java.math.BigInteger} —— Go 的 {@code ParseInt} 对它<b>必然溢出</b>
     * ⇒ ⇒ C 侧<b>不得</b>把它回绕成负数（那是「静默的错误身份」）。
     */
    public static String tokenWithBigIntegerUserIdClaim(String decimalLiteral) {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("userId", new java.math.BigInteger(decimalLiteral))
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
