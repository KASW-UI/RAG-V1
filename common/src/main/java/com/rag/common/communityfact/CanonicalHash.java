package com.rag.common.communityfact;

import org.erdtman.jcs.JsonCanonicalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * <p><b>B 出处</b>：{@code service/common/communityfact/wire.go} 的
 * ＋ {@code var receiptHash = regexp.MustCompile(`^[a-f0-9]{64}$`)}。
 *
 * <p><b>⚠️ 规范化顺序不可颠倒（G-2 · 幂等性前提）</b>：
 * <b>先规范化，再哈希</b> —— JCS（RFC 8785）规范化 → SHA-256 → hex。
 * <p>⚠️ **Jackson 的 {@code SORT_PROPERTIES_ALPHABETICALLY} <b>不等价</b>** ⇒
 * 不可用它替代 JCS ⇒ 指纹不一致 ⇒ <b>幂等失效</b>（同一事件重投会被当成新事件）。
 *
 * <p><b>实现对照</b>：
 * <ul>
 *   <li>B 侧：{@code github.com/cyberphone/json-canonicalization/go/src/webpki.org/jsoncanonicalizer}；</li>
 *   <li>Java 侧：{@code org.erdtman.jcs.JsonCanonicalizer}（同为 RFC 8785 JCS 实现）
 *       ⇒ {@code new JsonCanonicalizer(raw).getEncodedString()}；</li>
 *   <li>哈希：{@code SHA-256} → {@code HexFormat.of().formatHex(sum)}（小写 hex，对齐 B 的
 *       {@code hex.EncodeToString}）。</li>
 * </ul>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的静态方法 {@code canonicalHash} / {@code validHash}
 * （1:N 拆分 · 映射表 §1.2 第 141 行 / 决策锁 L-7 第 3 条：
 * 「{@code CanonicalHash} / {@code ValidHash} 拆成独立类（JCS 依赖必须显式）」）。
 */
public final class CanonicalHash {

    private static final Pattern RECEIPT_HASH = Pattern.compile("^[a-f0-9]{64}$");

    /**
     * 对应 B 侧版 {@code func CanonicalHash(raw []byte) (string, error)}：
     * JCS 规范化 → SHA-256 → hex（RFC 8785）。
     */
    public static String canonicalHash(byte[] raw) throws Exception {
        // 对应 B 侧版 github.com/cyberphone/json-canonicalization 的 JsonCanonicalizer.Transform
        // Java 等价物 org.erdtman.jcs.JsonCanonicalizer（JCS，RFC 8785）
        // 用法：new JsonCanonicalizer(byte[]) → getEncodedString() / getEncodedUTF8()
        String canonical = new JsonCanonicalizer(raw).getEncodedString();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] sum = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sum);
    }

    /**
     * 对应 B 侧版 {@code func ValidHash(value string) bool}：sha256 hex 校验。
     */
    public static boolean validHash(String value) {
        return value != null && RECEIPT_HASH.matcher(value).matches();
    }

    private CanonicalHash() {
    }
}
