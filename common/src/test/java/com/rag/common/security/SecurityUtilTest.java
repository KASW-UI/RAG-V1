package com.rag.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：**JWT / Bearer / 口令** 三条 L1 契约。
 *
 * <p>⚠️ <b>JWT 是 L1 契约</b>（客户端拿它调接口）⇒ ⇒ 「C 签的 token，B 侧
 * {@code token.SignedString([]byte(secretKey))} 能不能验过」是<b>必须被证</b>的，
 * 不能靠「用了同一个库」推断。
 *
 * <p>⚠️ <b>本测试刻意用「手写 HMAC 验签」而不是 jjwt 的 parser</b>：
 * jjwt 强制要求 key ≥ 256 bit ⇒ 用它验签就<b>必然</b>用补齐过的 key ⇒
 * ⇒ **验不出「B 侧 用原始短密钥能否验过」这件事** ⇒ ⇒ 手写才是真判据。
 */
class SecurityUtilTest {

    // ── JwtUtil ────────────────────────────────────────────────────────

    @Test
    @DisplayName("JWT：B 侧 用**原始短密钥**就能验过 C 签的 token（RFC 2104 零填充等价性）")
    void tokenVerifiesWithRawShortSecretLikeGo() throws Exception {
        // ⚠️ 15 字节 —— 与真源 admin 默认密钥**同长度**（形状，不含明文）
        String secret = "fifteen-bytes!!";
        assertThat(secret.getBytes(StandardCharsets.UTF_8).length).isEqualTo(15);

        String token = JwtUtil.getToken(secret, 1_700_000_000L, 3600L, 42L);

        // ⚠️ **手写验签**：完全按 B 的方式（原始密钥 ＋ HMAC-SHA256）
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(signingInput);
        byte[] actual = Base64.getUrlDecoder().decode(parts[2]);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("JWT：签名算法钉死 HS256（密钥长度变化不得让 header.alg 漂移）")
    void algorithmIsAlwaysHs256() {
        String header = JwtUtil.getToken("0123456789abcdef0123456789abcdef", 1L, 60L, 1L)
                .split("\\.")[0];
        String decoded = new String(Base64.getUrlDecoder().decode(header), StandardCharsets.UTF_8);
        assertThat(decoded).contains("\"alg\":\"HS256\"").contains("\"typ\":\"JWT\"");
    }

    @Test
    @DisplayName("JWT：padToHmacKeySize 把 <32 字节的密钥补零到 32 字节（≥32 原样返回）")
    void padToHmacKeySize() {
        byte[] padded = JwtUtil.padToHmacKeySize("short");
        assertThat(padded).hasSize(32);
        assertThat(padded[0]).isEqualTo((byte) 's');
        // ⚠️ 补的必须是**零**（填非零会改变 HMAC 的 ipad/opad 异或结果）
        for (int i = "short".length(); i < 32; i++) {
            assertThat(padded[i]).isZero();
        }
        assertThat(JwtUtil.padToHmacKeySize("0123456789abcdef0123456789abcdef")).hasSize(32);
    }

    // ── BearerTokenExtractor ───────────────────────────────────────────

    @Test
    @DisplayName("Bearer：验签用 token 剥前缀（大小写不敏感 ＋ 长度必须 > 6 ＋ 不匹配原样返回）")
    void bearerTokenForSignatureVerification() {
        assertThat(BearerTokenExtractor.tokenForSignatureVerification("Bearer abc")).isEqualTo("abc");
        // ⚠️ B 侧 是 strings.ToUpper(tok[0:7]) ⇒ 大小写不敏感
        assertThat(BearerTokenExtractor.tokenForSignatureVerification("bearer abc")).isEqualTo("abc");
        assertThat(BearerTokenExtractor.tokenForSignatureVerification("BEARER abc")).isEqualTo("abc");
        // ⚠️ 判定是 len(tok) > 6 ⇒ 正好 7 字符（"Bearer "）⇒ 剥成空串
        assertThat(BearerTokenExtractor.tokenForSignatureVerification("Bearer ")).isEmpty();
        // ⚠️ 6 字符（"Bearer"）不满足 > 6 ⇒ 原样当 token
        assertThat(BearerTokenExtractor.tokenForSignatureVerification("Bearer")).isEqualTo("Bearer");
        // ⚠️ **不匹配时原样返回整串**（不置空、不抛错）⇒ 这是注销绕过的防线
        assertThat(BearerTokenExtractor.tokenForSignatureVerification("XXBearer eyJ"))
                .isEqualTo("XXBearer eyJ");
    }

    @Test
    @DisplayName("Bearer：黑名单 key 用 token 剥前缀是**大小写敏感**的 TrimPrefix（与验签那套不同 ⇒ B 侧 自身的不一致）")
    void bearerTrimPrefixIsCaseSensitive() {
        assertThat(BearerTokenExtractor.trimBearerPrefix("Bearer abc")).isEqualTo("abc");
        // ⚠️ 大小写敏感 ⇒ "bearer abc" 原样返回（B 的 TrimPrefix 语义）
        assertThat(BearerTokenExtractor.trimBearerPrefix("bearer abc")).isEqualTo("bearer abc");
        assertThat(BearerTokenExtractor.trimBearerPrefix("XXBearer eyJ")).isEqualTo("XXBearer eyJ");
    }

    // ── PasswordUtil ───────────────────────────────────────────────────

    @Test
    @DisplayName("口令：匹配 ⇒ true；不匹配 / 空串 / 非 bcrypt ⇒ false；**畸形 hash 也不抛异常**")
    void passwordCheckNeverThrows() {
        String hash = PasswordUtil.encrypt("s3cret");

        assertThat(PasswordUtil.check("s3cret", hash)).isTrue();
        assertThat(PasswordUtil.check("wrong", hash)).isFalse();
        assertThat(PasswordUtil.check("s3cret", "")).isFalse();
        assertThat(PasswordUtil.check("s3cret", "abc")).isFalse();
        assertThat(PasswordUtil.check("s3cret", "$2a$10$tooshort")).isFalse();

        // ⚠️ **cost 越界** ⇒ Spring 会抛 IllegalArgumentException ⇒ 必须被吞成 false
        //    （B 的 `err == nil` 语义 ⇒ 任何 err 都返回 false）
        assertThat(PasswordUtil.check("s3cret", "$2a$99$" + "a".repeat(53))).isFalse();
    }

    @Test
    @DisplayName("口令：checkDetailed 能区分「密码错」与「hash 损坏」（可观测性增强，不改对外行为）")
    void passwordCheckDetailedDistinguishesMalformedHash() {
        String hash = PasswordUtil.encrypt("s3cret");
        assertThat(PasswordUtil.checkDetailed("s3cret", hash)).isEqualTo(PasswordUtil.Outcome.MATCHED);
        assertThat(PasswordUtil.checkDetailed("wrong", hash)).isEqualTo(PasswordUtil.Outcome.MISMATCHED);
        assertThat(PasswordUtil.checkDetailed("s3cret", "$2a$99$" + "a".repeat(53)))
                .isEqualTo(PasswordUtil.Outcome.MALFORMED_HASH);
    }
}
