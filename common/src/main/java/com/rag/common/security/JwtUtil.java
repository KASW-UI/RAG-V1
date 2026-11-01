package com.rag.common.security;




import io.jsonwebtoken.Jwts;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * <p><b>L1 契约类：HS256 JWT 的签发。</b>
 *
 * <p>RAG 出处：{@code service/users/users-common/src/main/java/com/rag/users/common/jwt/JwtUtil.java}
 * （抄写基准 = RAG · D13）。
 *
 * <p>⚠️ <b>D14 适配：jjwt 0.x → 0.12+ 的签名 API</b>：
 * RAG 侧用的是 {@code io.jsonwebtoken.SignatureAlgorithm.HS256}（jjwt **0.x** 的枚举）；
 * 本仓的 {@code jjwt.version = 0.12.6}（见根 pom）⇒ 该枚举虽仍存在但已废弃 ⇒
 * ⇒ 改用新 API {@code Jwts.SIG.HS256}（类型 {@code SecureDigestAlgorithm}）。
 * ⇒ ⇒ <b>签名结果逐字节相同</b>（HS256 本身没变）⇒ 与 B 的互通性不受影响。
 *
 * <p>⚠️ <b>签名算法必须钉死 HS256</b>：{@code signWith(Key) 单参重载会<b>照着 key 的 JCA 算法名</b>
 * 选算法 ⇒ 密钥长度漂移时 {@code header.alg} 会跟着漂 ⇒ B 侧是
 * {@code jwt.New(jwt.SigningMethodHS256)} 显式写死的 ⇒ ⇒ 这里两件事都钉死：
 * JCA 算法名 = {@code HmacSHA256} ＋ 签名算法 = {@code HS256}。
 */
public final class JwtUtil {
    private JwtUtil() {}

    public static String getToken(String secretKey,
                                  long iat,
                                  long seconds,
                                  long userId) {
        // ⚠️ 不能用 Keys.hmacShaKeyFor(...) 返回的 key 直接 signWith(key)：
        // 该工厂在密钥 ≥48 字节时返回 HmacSHA384、≥64 字节时返回 HmacSHA512 的 SecretKeySpec，
        // 而 jjwt 的 signWith(Key) 单参重载**会照着 key 的 JCA 算法名选签名算法** ⇒ token 的
        // header.alg 会随密钥长度漂移。B 侧是 jwt.New(jwt.SigningMethodHS256) 显式写死的。
        // 这里把两件事都钉死：JCA 算法名 = HmacSHA256，签名算法 = HS256。
        SecretKeySpec key = new SecretKeySpec(padToHmacKeySize(secretKey), "HmacSHA256");
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .issuedAt(new Date(iat * 1000L))
                .expiration(new Date((iat + seconds) * 1000L))
                .claim("userId", userId)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 把 secret 字符串补齐到 ≥32 字节（HS256 要求 key ≥256 bits），供签发/验签共用。
     *
     * <p><b>为什么是 public</b>：jjwt 0.12 强制要求 key ≥ 256 bit，而 admin 侧的默认密钥
     * <b>只有 15 字节</b>（真源出处：B 侧 {@code service/user/admin/api/etc/admincenter.yaml:11}
     * 的 {@code AdminAuth.AccessSecret}；<b>此处只记「形状/长度」，不记明文</b> ——
     * 生产密钥不得进仓 ⇒ 2026-10-28 起本 javadoc 不再写出密钥值本身）。
     * 任一调用方（{@code JwtUtil.getToken} 签发、
     * 各 api 模块的 JWT 拦截器/过滤器验签）<b>必须共用同一份补齐过的 key</b>，否则：
     * <ul>
     *   <li>签发端用未补齐的 key → {@code WeakKeyException}</li>
     *   <li>验签端用未补齐的 key → Bean 创建时抛 {@code WeakKeyException} → admin-api 起不来</li>
     *   <li>两侧各自实现 padding → 补齐长度不一致 → 一侧签的 token 另一侧验不过</li>
     * </ul>
     *
     * <p><b>⚠️ 补零为什么「不会」破坏与 B 的互通 —— 唯一的理由是 RFC 2104（2026-10-23 实测钉死）</b>
     * <p>按密码学常识，15 字节密钥与「15 字节 + 17 个 0」的 32 字节密钥
     * <b>应当</b>算出不同的 HMAC。但实测<b>逐字节相同</b>：
     * <pre>
     *   raw  len=15 hex=666978747572652d61646d696e3135
     *   pad  len=32 hex=666978747572652d61646d696e31350000000000000000000000000000000000
     *   sig(rawKey)    = 859b1e02f51e3287295c5b2f22b9effa3802c5f30759dc16fccaeb4096713472
     *   sig(paddedKey) = 859b1e02f51e3287295c5b2f22b9effa3802c5f30759dc16fccaeb4096713472
     * </pre>
     * <p>⚠️ 上面这组 hex/sig 是<b>等长夹具</b>（15 字节，形状与真源 admin 默认密钥一致）的实测值，
     * <b>不是</b>真源密钥本身 —— hex 编码可逆 ⇒ 把真值写进本文件等于二次泄露
     * ⇒ 2026-10-28 起本 javadoc 只保留「形状 + 结论」，不保留真值或其可逆编码。
     * sig 的被签消息为空串（{@code HMAC-SHA256(key, "")}），可自行复算。
     * 该等价性<b>与密钥取值无关</b>（任意短密钥都成立 ⇒ 夹具的实测结果同样成立）。
     * 原因是 <b>HMAC 自身就会把短于分组尺寸（64 字节）的密钥零填充</b>（RFC 2104 §2 第 2 步：
     * 密钥短于 B 时末尾补 0 到 B 字节）。两种写法补到 64 字节后<b>得到完全相同的 K′</b>。
     * <p>B 侧 {@code service/users/users-common/src/main/java/com/rag/users/common/jwt/JwtUtil.java:63:14} {@code token.SignedString([]byte(secretKey))}
     * 用的是原始字节，因此两侧互通。
     * <p><b>实测交叉验签：15 / 32 / 36 / 47 / 48 / 64 字节共 6 档 × 双向 12 次，12/12 全通。</b>
     *
     * <p><b>🔴 三条边界条件 —— 违反任何一条，admin（15 字节）的 token 会立刻与 B 侧 互验失败，
     * 且现有测试无一会红：</b>
     * <ol>
     *   <li><b>补的必须是零</b>。填非零字节会改变 K′ 的 ipad/opad 异或结果。</li>
     *   <li><b>补到 32 字节即止</b>。HMAC 的零填充等价性只在「补出来的字节全是 0」时成立；
     *       补到 33 字节以上仍然等价（多补的仍是 0），但<b>不得截断到 32 字节以下</b>
     *       —— jjwt 会直接抛 {@code WeakKeyException}。</li>
     *   <li><b>本等价性对 &gt;64 字节的密钥不再成立</b>：密钥超过分组尺寸后 HMAC 不再零填充，
     *       改用密钥本身。该情形与 {@link #getToken} 里钉死 HS256 是一起处理的
     *       （见 {@code docs/DRIFT/users/common.md} 的 {@code PAD-01} / {@code DRIFT-USERSCOMMON-004}）。</li>
     * </ol>
     *
     * <p><b>曾被删过、属于回归</b>：本方法一度是 {@code private}，被删后 {@code admin-api} 起不来，
     * 已恢复并保持 {@code public}。
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc2104#section-2">RFC 2104 §2</a>
     */
    public static byte[] padToHmacKeySize(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 32) {
            return bytes;
        }
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        // 剩余字节已经是 0（Java byte[] 默认初始化为 0）
        return padded;
    }
}