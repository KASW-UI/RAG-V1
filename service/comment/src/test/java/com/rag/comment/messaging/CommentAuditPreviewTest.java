package com.rag.comment.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>评论内容预览的纯语义测试</b> —— 对应 Go {@code mqs/audit_consumer.go:122-132} 的
 * {@code previewComment}。
 *
 * <p><b>为什么这是「错了不报错」的地方</b>：预览文案被拼进给用户看的通知标题/正文里
 * （{@code CommentAuditListener} 的「你的文章收到了一条新评论：」与
 * 「你的评论收到了新的回复：」两条出口）⇒ 截断错了<b>不抛异常、不影响落库</b>，
 * 只会让通知里出现半个 emoji 或超长文本。
 *
 * <p><b>为什么必须与被测类同包</b>：{@code previewComment} 是
 * {@link CommentAuditListener} 的 <b>package-private static</b> 方法
 * ⇒ 测试类必须落在 {@code com.rag.comment.messaging} 包下才能直接调用。
 * （B 侧它与 meta 断言同在 {@code com.rag.comment.comment.rpc.service} 包内；
 * C 侧 {@code CommentTransactionalService} 落在 {@code domain.service} 包
 * ⇒ 拆成本类与 {@code com.rag.comment.domain.service.CommentMetaNormalizationTest}。
 * <b>拆分只因包可见性，覆盖面与 B 侧一一对应</b>。）
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为
 *           {@code com.rag.comment.messaging}（D7 · Kafka 消费侧落
 *           {@code messaging/} 技术角色包）；</li>
 *       <li>测试类拆分（原因见上）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。C 侧 {@code previewComment} 的实现与 B 侧
 *       {@code comment-rpc/…/rpc/service/CommentAuditListener.java:261-275}
 *       <b>逐字相同</b>（含 {@code PREVIEW_RUNE_LIMIT = 40} 这一常量）
 *       ⇒ 断言可逐条照抄 B 侧期望值。</li>
 *   <li><b>B 出处</b>：
 *     <ul>
 *       <li>断言来源：B 侧
 *           {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/service/CommentMetaAndPreviewTest.java:77-104}；</li>
 *       <li>被测实现：B 侧
 *           {@code comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditListener.java:261-275}，
 *           常量 {@code PREVIEW_RUNE_LIMIT = 40} 在同文件 {@code :54}；</li>
 *       <li>Go 语义来源：B 侧自记其 Go 出处为
 *           {@code service/comment/rpc/internal/mqs/audit_consumer.go:122-132}。</li>
 *     </ul></li>
 * </ol>
 */
class CommentAuditPreviewTest {

    @Test
    @DisplayName("🔴 空 ⇒ \"(no content)\"")
    void emptyPreview() {
        assertThat(CommentAuditListener.previewComment("")).isEqualTo("(no content)");
        assertThat(CommentAuditListener.previewComment("   ")).isEqualTo("(no content)");
        assertThat(CommentAuditListener.previewComment(null)).isEqualTo("(no content)");
    }

    @Test
    @DisplayName("🔴 ≤40 rune 原样返回；>40 取前 40 + \"...\"")
    void truncationAtFortyRunes() {
        assertThat(CommentAuditListener.previewComment("短评论")).isEqualTo("短评论");
        String forty = "一".repeat(40);
        assertThat(CommentAuditListener.previewComment(forty)).isEqualTo(forty);
        String fortyOne = "一".repeat(41);
        assertThat(CommentAuditListener.previewComment(fortyOne)).isEqualTo(forty + "...");
    }

    @Test
    @DisplayName("🔴 按 rune 而非 UTF-16 code unit 截断（emoji 是代理对）")
    void truncatesByCodePointNotChar() {
        // 41 个 emoji：Java 的 String.length() 会算成 82，若按 char 截会只留 20 个
        String emoji = "😀".repeat(41);
        assertThat(emoji.length()).isEqualTo(82);          // 前提：确实含代理对
        String preview = CommentAuditListener.previewComment(emoji);
        assertThat(preview).endsWith("...");
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(43); // 40 + "..."
    }

    /**
     * 🔴 <b>「恰好 40 个 rune 且含代理对」不得被截断</b>。
     *
     * <p>B 出处：B 侧 {@code CommentAuditListener.java:270} 的判据是
     * {@code runeCount <= PREVIEW_RUNE_LIMIT}（<b>小于等于</b> ⇒ 40 个 rune 原样返回），
     * 而 {@code :271-274} 的截断分支只在 {@code runeCount > 40} 时进入。
     *
     * <p>⚠️ 这条是<b>边界值</b>的门禁点：若把判据误写成 {@code < 40}，
     * 则「正好 40 个 rune」会被多截一个并补上 {@code "..."}
     * ⇒ 上面 {@code truncatesByCodePointNotChar} 仍会绿（本条多截的 emoji 也在 41 个里），
     * <b>只有本条能把它照出来</b>。
     */
    @Test
    @DisplayName("🔴 恰好 40 个 rune（含代理对）必须原样返回，不得补 \"...\"")
    void exactlyFortyRunesIsNotTruncated() {
        String emoji = "😀".repeat(40);
        assertThat(emoji.length()).isEqualTo(80);          // 前提：40 个代理对 = 80 个 code unit
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(40);
        assertThat(CommentAuditListener.previewComment(emoji)).isEqualTo(emoji);
    }

    /**
     * 🔴 <b>预览前会去掉首尾空白</b>。
     *
     * <p>B 出处：B 侧 {@code CommentAuditListener.java:265} 先做
     * {@code String trimmed = content.strip()}，<b>之后</b>才判空与截断
     * ⇒ 返回值是<b>去空白后</b>的正文，不是原串。
     *
     * <p>⚠️ 与 Go 侧一致：Go 的 {@code strings.TrimSpace} 同样先于截断发生。
     */
    @Test
    @DisplayName("🔴 预览前去掉首尾空白（strip 先于截断）")
    void previewStripsSurroundingWhitespace() {
        assertThat(CommentAuditListener.previewComment("  hi  ")).isEqualTo("hi");
        // strip 之后才判长度 ⇒ 首尾有空白时不占用 40 的配额
        String fortyWithSpaces = "  " + "一".repeat(40) + "  ";
        assertThat(CommentAuditListener.previewComment(fortyWithSpaces))
                .isEqualTo("一".repeat(40));
    }
}
