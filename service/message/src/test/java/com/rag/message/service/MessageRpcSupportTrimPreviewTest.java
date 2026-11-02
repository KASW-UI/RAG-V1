package com.rag.message.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>移植说明（本文件来自 B 侧测试资产 · AGENTS.md §1.0c「测试资产单独一档」）</b>。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/test/java/com/rag/message/message/rpc/service/MessageRpcSupportTrimPreviewTest.java}
 * （226 行 · **测试体与期望值逐条照搬**）。
 * <p><b>移植时只改了 3 处「纯机械」项</b>：① package {@code com.rag.message.message.rpc.service}
 * → {@code com.rag.message.service}（D7 包压平 · 与 C 侧 {@code MessageRpcSupport} 同包）
 * ② proto import {@code com.rag.message.message.proto.*} → {@code com.rag.proto.message.*}（D7 proto 集中化）
 * ③ 实体 import {@code ...rpc.domain.*} → {@code com.rag.message.domain.entity.*}（D7）。
 * ⇒ ⇒ <b>断言体、期望值、{@code @DisplayName} 里的 DRIFT 编号一个字未改</b>。
 *
 * <p><b>为什么这批用例的期望值可信</b>：它们**不是从实现反推的**，而是
 * <b>直接来自 Go 源码</b> —— {@code service/message/rpc/internal/model/message.go:234-240}
 * 的 {@code TrimPreview} 字面量（80 rune 上限），以及
 * {@code go run} 对 {@code unicode.IsSpace(r), r ∈ [0, 0x3200]} 的<b>枚举实测</b>
 * （Go 1.25.11 {@code src/unicode/graphic.go:128} + {@code tables.go:8570} 的 {@code _White_Space} 表）
 * ⇒ ⇒ 符合 D13「期望值沿用 B 侧」⇒ ⇒ **本类提供的正是 `.agents/verification.md` 第九阶段要求的
 * 「以相同输入比较 B 与 C」** —— 同一份输入（同一批码点）分别喂给 B 侧实现与 C 侧实现，
 * 期望值<b>单一真源</b>（Go 源码）⇒ 两边必须同时相等。
 *
 * <p>⚠️ <b>本类不构成「双侧活体对拍」的全部</b>：它是<b>纯函数级</b>的（无 DB / 无网络 / 无 Spring 上下文），
 * 覆盖面限于「空白集合 + 截断上限 + 代理对计数」三条轴；HTTP / gRPC / 事务 / DB 终态等面
 * 仍需真容器对拍 ⇒ 状态见 {@code docs/DRIFT/message/verification-message-service-2026-11-02.md} §七·4。
 */
class MessageRpcSupportTrimPreviewTest {

    /** 复刻 Go：{"code":1006, "msg":1006, "call_chain":1006, "label":1006} 的四元组。 */
    @Test
    @DisplayName("A-P2：未超 80 rune 时原样返回（trim 后）")
    void shortContentReturnedAsIs() {
        assertEquals("hello", MessageRpcSupport.trimPreview("  hello  "));
        assertEquals("", MessageRpcSupport.trimPreview(null));
    }

    @Test
    @DisplayName("A-P2：恰好 80 rune 不截断")
    void exactly80RunesNotTruncated() {
        String s = "字".repeat(80);
        assertEquals(s, MessageRpcSupport.trimPreview(s));
    }

    @Test
    @DisplayName("A-P2：81 rune 截到 80 —— 上限是 80 不是 255")
    void runes81TruncatedTo80() {
        String s = "字".repeat(81);
        String out = MessageRpcSupport.trimPreview(s);
        assertEquals(80, out.codePointCount(0, out.length()),
                "截断上限必须是 80 个 rune（Go 字面量），不是 255 —— 见 message.go:236");
    }

    /**
     * <b>本项最关键的一条</b>：中文是 BMP 字符，{@code String.length()} 与 rune 计数相等，
     * 所以「255 → 80」这个错误用中文测不出来。必须用<b>代理对</b>字符才能暴露 char ≠ rune。
     */
    @Test
    @DisplayName("A-P2：代理对（emoji）必须按 rune 计数，且不得截出半个代理对")
    void surrogatePairCountedAsOneRune() {
        // 100 个 emoji = 100 rune，但 String.length() 是 200
        String emoji = "😀".repeat(100);
        assertEquals(200, emoji.length(), "前置条件：emoji 是代理对，UTF-16 占 2 个 code unit");
        assertEquals(100, emoji.codePointCount(0, emoji.length()), "前置条件：码点数为 100");

        String out = MessageRpcSupport.trimPreview(emoji);
        assertEquals(80, out.codePointCount(0, out.length()),
                "必须截到 80 个码点；按 length() 截会得到 80 个 code unit = 40 个 emoji");
        // 不含孤立代理对 —— 半截代理对序列化后是乱码
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            assertTrue(!Character.isHighSurrogate(c) || (i + 1 < out.length()
                            && Character.isLowSurrogate(out.charAt(i + 1))),
                    "位置 " + i + " 出现孤立的高代理对 —— 截断点落在代理对中间");
        }
    }

    @Test
    @DisplayName("A-P2：emoji 恰好 80 个不截断")
    void exactly80EmojiNotTruncated() {
        String emoji = "😀".repeat(80);
        assertEquals(emoji, MessageRpcSupport.trimPreview(emoji),
                "80 个 emoji 应原样返回；按 length()=160 判断会误截");
    }

    @Test
    @DisplayName("A-P2：混合中文 + emoji 也按 rune 对齐 Go")
    void mixedChineseAndEmoji() {
        // 78 个中文 + 10 个 emoji = 88 rune
        String mixed = "字".repeat(78) + "😀".repeat(10);
        String out = MessageRpcSupport.trimPreview(mixed);
        assertEquals(80, out.codePointCount(0, out.length()));
        assertEquals(78, out.substring(0, out.offsetByCodePoints(0, 78)).codePointCount(0, 78));
    }

    // ==================================================================
    // DRIFT-MSG-V-008（2026-10-22）：空白集合必须等于 Go strings.TrimSpace
    // ==================================================================

    /**
     * <b>为什么上面 6 条测不出 DRIFT-MSG-V-008</b>：它们清一色用 ASCII 空格，
     * 而 {@code String.trim()}（修复前的实现）与 {@code strings.TrimSpace}
     * 对 ASCII 空格的判定<b>完全相同</b>。
     * ⇒ 6 条全绿而缺陷存在，是 §3.3「绿测试守不住空白集合」的直接实例。
     *
     * <p>⇒ <b>本组用例的判据不能只写一个字符</b>：必须同时覆盖
     * 「{@code trim()} 会漏的」与「{@code strip()} 也会漏的」两类，
     * 否则换一种实现就又绿了。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-008：NBSP(U+00A0) / 全角空格(U+3000) 必须被 TrimSpace 掉")
    void goTrimSpaceRemovesNbspAndIdeographicSpace() {
        // U+00A0 NBSP：trim() 不去、strip() 也不去（non-breaking）
        assertEquals("hello", MessageRpcSupport.trimPreview(pad(NBSP, "hello")),
                "U+00A0 NBSP 在 Go strings.TrimSpace 里是空白；"
                        + "String.trim() 与 String.strip() 都不去它");
        assertEquals("hello", MessageRpcSupport.trimContent(pad(NBSP, "hello")));

        // U+3000 全角空格：trim() 不去、strip() 会去
        assertEquals("hello", MessageRpcSupport.trimPreview(pad(IDEOGRAPHIC_SPACE, "hello")),
                "U+3000 全角空格在 Go 里是空白；String.trim() 不去它");
        assertEquals("hello", MessageRpcSupport.trimContent(pad(IDEOGRAPHIC_SPACE, "hello")));
    }

    @Test
    @DisplayName("DRIFT-MSG-V-008：NEL / 行分隔符 / 窄不换行空格 / 数学空格 / 欧甘空格也必须被去掉")
    void goTrimSpaceRemovesSeparatorCharacters() {
        int[] alsoMissedByBothJavaTrims = {
                NEL,               // U+0085 NEXT LINE
                LINE_SEPARATOR,     // U+2028
                PARAGRAPH_SEPARATOR,// U+2029
                NARROW_NBSP,        // U+202F —— strip() 也不去
                MEDIUM_MATH_SPACE,  // U+205F
                OGHAM_SPACE_MARK,   // U+1680
                FIGURE_SPACE};      // U+2007 —— strip() 也不去
        for (int cp : alsoMissedByBothJavaTrims) {
            assertEquals("hi", MessageRpcSupport.trimPreview(pad(cp, "hi")),
                    "Go unicode.IsSpace 含 U+" + hex(cp) + "；Java 两个 trim 变体都漏");
            assertEquals("hi", MessageRpcSupport.trimContent(pad(cp, "hi")));
        }
    }

    /**
     * <b>反向断言</b>：{@code U+001C}–{@code U+001F}（file/group/record/unit separator）
     * 在 Go 里<b>不是</b>空白，但 Java 的 {@code trim()} 与 {@code strip()}
     * <b>都会</b>把首尾的它们删掉。
     *
     * <p>⇒ 这条锁的是「不要为了修漏而换成 {@code strip()}」：
     * {@code strip()} 会把本该保留的内容删掉，是<b>另一个方向</b>的漂移。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-008：U+001C-U+001F 在 Go 里不是空白，必须保留")
    void goTrimSpaceKeepsAsciiFileSeparators() {
        for (int cp = 0x1C; cp <= 0x1F; cp++) {
            String padded = pad(cp, "hi");
            assertEquals(padded, MessageRpcSupport.trimPreview(padded),
                    "Go unicode.IsSpace 对 r<=0xFF 只特判 8 个码点，不含 U+001C-U+001F；"
                            + "换用 String.strip() 会把它们删掉");
        }
    }

    /**
     * 全 Go 空白集合的完整枚举 —— 25 个码点。
     *
     * <p><b>来源不是记忆</b>：{@code go run} 枚举 {@code unicode.IsSpace(r), r ∈ [0, 0x3200]}
     * 的结果（Go 1.25.11 {@code src/unicode/graphic.go:128} + {@code tables.go:8570} 的
     * {@code _White_Space} 表）。本数组与那份实测输出逐项对应。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-008：25 个 Go 空白码点必须全被去掉")
    void fullGoWhiteSpaceSetIsTrimmed() {
        int[] goSpace = {
                0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
                0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
                0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
                0x3000};
        assertEquals(25, goSpace.length, "Go unicode.IsSpace 在 r<=0x3200 范围内恰好 25 个码点");
        for (int cp : goSpace) {
            assertEquals("x", MessageRpcSupport.trimContent(pad(cp, "x")),
                    "U+" + hex(cp) + " 必须是 Go 空白");
            assertEquals("x", MessageRpcSupport.trimPreview(pad(cp, "x")),
                    "U+" + hex(cp) + " 必须是 Go 空白");
        }
    }

    /**
     * {@code trimContent} 的结果直接决定 {@code SendChatMessage} 走哪条分支：
     * 全空白 ⇒ {@code content == ""} ⇒ {@code INVALID_ARGUMENT + 5003}。
     *
     * <p>⇒ 这就是 DRIFT-MSG-V-008 的<b>可观察出口</b>：
     * 修复前 {@code "\u00A0\u00A0"} 不被判空，Go 侧则会拒绝。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-008：全 NBSP 的 content 被判为空 ⇒ 与 Go 一样进 5003 分支")
    void whitespaceOnlyContentCollapsesToEmpty() {
        assertEquals("", MessageRpcSupport.trimContent(pad(NBSP, "")),
                "Go: TrimSpace(\"\\u00A0\\u00A0\") = \"\" ⇒ content == \"\" ⇒ 5003");
        assertEquals("", MessageRpcSupport.trimContent(pad(IDEOGRAPHIC_SPACE, "")),
                "U+3000 同理");
        String mixedWs = " \t\r\n" + (char) NBSP + (char) IDEOGRAPHIC_SPACE;
        assertEquals("", MessageRpcSupport.trimContent(mixedWs),
                "ASCII + Unicode 空白混合");
    }

    // ==================================================================
    // helpers —— 全部按码点构造，**不写不可见字面量**
    // ==================================================================

    private static final int NBSP = 0x00A0;
    private static final int NEL = 0x0085;
    private static final int OGHAM_SPACE_MARK = 0x1680;
    private static final int FIGURE_SPACE = 0x2007;
    private static final int LINE_SEPARATOR = 0x2028;
    private static final int PARAGRAPH_SEPARATOR = 0x2029;
    private static final int NARROW_NBSP = 0x202F;
    private static final int MEDIUM_MATH_SPACE = 0x205F;
    private static final int IDEOGRAPHIC_SPACE = 0x3000;

    /** 用码点构造 {@code ws + mid + ws}；mid 为空串时即"全空白"。 */
    private static String pad(int ws, String mid) {
        String w = new String(Character.toChars(ws));
        return w + mid + w;
    }

    private static String hex(int cp) {
        return Integer.toHexString(cp).toUpperCase();
    }
}
