package com.rag.like.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 与 Go {@code encoding/json} 输出<b>字节一致</b>的 JSON 序列化 + SHA-256。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/GoCompatibleJson.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧 {@code rfc3339Nano} 用 {@code java.time.ZonedDateTime}
 *       全限定名内联书写；本类把它提到 import 区（纯表达层，不改行为）。
 *       除此之外<b>逐字相同</b>，含 {@code Sha256} 嵌套类的形态。</li>
 *   <li><b>语义还一样吗</b>：一样。逐字符转义表（{@code <} {@code >} {@code &} U+2028 U+2029）
 *       与 SHA-256 小写 hex 的输出逐字节相同。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p><b>为什么不能直接用 Jackson 默认输出</b>：Go 的 {@code encoding/json} 有两处默认行为
 * 与 Jackson 不同，而这两处都会让跨语言哈希/校验失败：
 * <table border="1">
 *   <caption>Go vs Jackson</caption>
 *   <tr><th></th><th>Go {@code encoding/json}</th><th>Jackson 默认</th></tr>
 *   <tr><td>HTML 转义</td>
 *       <td><b>开启</b>：{@code <} → {@code \u003c}，{@code >} → {@code \u003e}，{@code &} → {@code \u0026}</td>
 *       <td><b>关闭</b>：原样输出</td></tr>
 *   <tr><td>字符串里的 U+2028/U+2029</td>
 *       <td>转成 {@code \u2028} / {@code \u2029}</td>
 *       <td>原样输出</td></tr>
 * </table>
 *
 * <p>本类只做一件事：把 Jackson 产出的 JsonNode <b>重新按 Go 的转义规则序列化</b>。
 * 不自己写 JSON 语法（那是 Jackson 的活），只覆盖转义这一层差异 —— 避免重复造轮子。
 *
 * <p><b>对象键顺序</b>不在这里保证：Jackson 的 {@code ObjectNode} 内部是
 * {@code LinkedHashMap}，插入序即输出序，由调用方按 Go 的字段声明顺序 put。
 */
public final class GoCompatibleJson {

    /**
     * U+2028 LINE SEPARATOR。
     *
     * <p>刻意用数值常量而不是字符字面量：Java 源码里的反斜杠 u 转义序列
     * <b>在词法分析阶段就会被展开</b>（注释里也一样），而 U+2028/U+2029 属于行终止符，
     * 直接写会让编译行为不可预期。
     */
    private static final char LINE_SEPARATOR = 0x2028;

    /** U+2029 PARAGRAPH SEPARATOR。 */
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private GoCompatibleJson() {
        // 工具类，禁止实例化
    }

    /**
     * 按 Go {@code encoding/json} 的转义规则输出紧凑 JSON（无空格）。
     *
     * <p>先让 Jackson 输出标准 JSON，再逐字符套用 Go 的转义表。
     * 这样避免了「自己实现 JSON 序列化器」这种高风险重复实现。
     */
    public static String write(JsonNode node) {
        String standard = node.toString(); // Jackson 默认紧凑输出
        StringBuilder out = new StringBuilder(standard.length() + 16);
        for (int i = 0; i < standard.length(); i++) {
            char c = standard.charAt(i);
            switch (c) {
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case LINE_SEPARATOR -> out.append("\\u2028");
                case PARAGRAPH_SEPARATOR -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 字节序列的 SHA-256，小写十六进制 —— 对应 Go {@code sha256.Sum256} + {@code hex.EncodeToString}。
     */
    public static final class Sha256 {

        private Sha256() {
        }

        /**
         * @return 64 位小写十六进制；Go 的 {@code hex.EncodeToString} 输出同样是<b>小写</b>
         */
        public static String hex(String input) {
            return hex(input.getBytes(StandardCharsets.UTF_8));
        }

        public static String hex(byte[] input) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] sum = digest.digest(input);
                StringBuilder sb = new StringBuilder(sum.length * 2);
                for (byte b : sum) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                // JDK 规范要求 SHA-256 必须可用；不可用说明运行环境坏了，直接抛而不是降级
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    /**
     * RFC3339Nano 格式化 —— 对应 Go {@code time.Time.Format(time.RFC3339Nano)}。
     *
     * <p><b>Java 的 {@code ISO_OFFSET_DATE_TIME} 不等价</b>：Go 的 {@code .999999999} 表示
     * 「小数部分去掉<b>末尾</b>的零，全为零时连小数点都不输出」，
     * 而 ISO 输出固定 9 位或裁剪到 3/6/9 位。
     *
     * <p>本域的时间文本会进入：① 事实 payload 的 {@code occurred_at}；② 冻结信封的
     * {@code occurred_at}。二者会被 {@code validLikeDelivery} 要求<b>解析后相等</b>，
     * 且 {@code technical_input_hash} 是信封原文的哈希 ⇒
     * 与 Go 的输出不一致会导致「权威读」判定失败。因此这里手写格式化。
     *
     * @param instant 时刻
     * @return 形如 {@code 2026-10-22T11:22:33.123456789Z}（UTC）或带偏移的形式
     */
    public static String rfc3339Nano(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(30);
        sb.append(zdt.getYear() < 0 ? "-" : "")
                .append(String.format("%04d", Math.abs(zdt.getYear())))
                .append('-').append(String.format("%02d", zdt.getMonthValue()))
                .append('-').append(String.format("%02d", zdt.getDayOfMonth()))
                .append('T').append(String.format("%02d", zdt.getHour()))
                .append(':').append(String.format("%02d", zdt.getMinute()))
                .append(':').append(String.format("%02d", zdt.getSecond()));

        int nanos = zdt.getNano();
        if (nanos > 0) {
            String frac = String.format("%09d", nanos);
            // 去掉末尾的零
            int end = frac.length();
            while (end > 0 && frac.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(frac, 0, end);
        }
        sb.append('Z');
        return sb.toString();
    }
}
