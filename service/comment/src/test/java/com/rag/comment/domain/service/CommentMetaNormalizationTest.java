package com.rag.comment.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.metrics.CommentRpcMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>评论 meta 归一化的纯语义测试</b> —— 对应 Go {@code model/comment.go:222-240} 的
 * {@code normalizeCommentMeta}。
 *
 * <p><b>为什么这是「错了不报错」的地方</b>：meta 是评论的扩展字段，<b>写入库时会被兜底成
 * {@code {"raw": 原文}}</b>，也就是说 —— meta 归一化错了，<b>JSON 依然是合法 JSON</b>、
 * 评论也能正常落库，只是扩展字段的语义悄悄变了 ⇒ 靠集成测试的「有没有报错」永远发现不了。
 *
 * <p><b>为什么必须与被测类同包</b>：{@code normalizeCommentMeta} 与 {@code goJsonQuote}
 * 都是 {@link CommentTransactionalService} 的 <b>package-private</b> 方法
 * ⇒ 测试类必须落在 {@code com.rag.comment.domain.service} 包下才能直接调用。
 * （B 侧两者同在 {@code com.rag.comment.comment.rpc.service} 包内，
 * 故 B 侧的 meta 与 preview 断言可以写在同一个测试类里；
 * C 侧两者分属 {@code domain.service} 与 {@code messaging} 两个包
 * ⇒ 拆成本类与 {@code com.rag.comment.messaging.CommentAuditPreviewTest} 两个测试类。
 * <b>这是「包可见性」造成的拆分，不是覆盖面的拆分</b> —— 两侧断言条数一致。）
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为
 *           {@code com.rag.comment.domain.service}（D7 · 事务边界类是独立 L3 关注点，
 *           落点见 {@code docs/Mapping_Table/comment/comment.md} §1.4）；</li>
 *       <li>构造器参数顺序调整：被测方法所需的 {@link ObjectMapper} 落在第 6 位、
 *           {@link CommentRpcMetrics} 落在第 7 位（B 侧是 metrics 在前、mapper 在后）
 *           ⇒ <b>被测方法本身的逻辑一字未改</b>；</li>
 *       <li>测试类拆分（原因见上）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。C 侧 {@code normalizeCommentMeta} 与
 *       {@code goJsonQuote} 的实现与 B 侧
 *       {@code comment-rpc/…/rpc/service/CommentTransactionalService.java:259-310}
 *       <b>逐字相同</b>（含「合法 JSON 原样返回」「HTML 三字符转义」「控制字符转义」
 *       三条易错规则）⇒ 断言可逐条照抄 B 侧期望值。</li>
 *   <li><b>B 出处</b>：
 *     <ul>
 *       <li>断言来源：B 侧
 *           {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/service/CommentMetaAndPreviewTest.java:27-73}；</li>
 *       <li>被测实现：B 侧
 *           {@code comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentTransactionalService.java:259-274}
 *           （{@code normalizeCommentMeta}）与 {@code :283-310}（{@code goJsonQuote}）；</li>
 *       <li>Go 语义来源：B 侧自记其 Go 出处为 {@code model/comment.go:222-240}。</li>
 *     </ul></li>
 * </ol>
 */
class CommentMetaNormalizationTest {

    /**
     * 被测实例 —— 5 个 mapper / service 依赖全传 {@code null}
     * （本类只测<b>纯函数</b>，不触库、不触 Redis）。
     *
     * <p>⚠️ {@link ObjectMapper} <b>不能传 null</b>：{@code normalizeCommentMeta}
     * 用它做「是不是合法 JSON」的判定（B 侧同样如此，见
     * {@code CommentTransactionalService.java:268}）⇒ 必须给一个真 mapper。
     */
    private final CommentTransactionalService service = new CommentTransactionalService(
            null, null, null, null, null,
            new ObjectMapper(),
            new CommentRpcMetrics(new SimpleMeterRegistry()));

    // ==================== normalizeCommentMeta ====================

    @Test
    @DisplayName("🔴 空 / 空白 ⇒ \"{}\"（Go comment.go:224-226）")
    void emptyBecomesEmptyObject() {
        assertThat(service.normalizeCommentMeta("")).isEqualTo("{}");
        assertThat(service.normalizeCommentMeta("   ")).isEqualTo("{}");
        assertThat(service.normalizeCommentMeta(null)).isEqualTo("{}");
    }

    @Test
    @DisplayName("🔴 合法 JSON 必须原样返回（不重排、不规范化）")
    void validJsonIsReturnedVerbatim() {
        String input = "{\"b\":1,  \"a\":[2,3]}";
        assertThat(service.normalizeCommentMeta(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("🔴 非法 JSON ⇒ 包成 {\"raw\": 原文}")
    void invalidJsonIsWrappedAsRaw() {
        assertThat(service.normalizeCommentMeta("not json at all"))
                .isEqualTo("{\"raw\":\"not json at all\"}");
    }

    @Test
    @DisplayName("🔴 HTML 字符按 Go 的 encoding/json 转义（< > & ⇒ \\u003c \\u003e \\u0026）")
    void htmlCharactersUseGoEscaping() {
        // Go 的 json.Marshal 默认 SetEscapeHTML(true)；Jackson 默认不转义。
        // 若这里产出裸 '<', 缓存与库里的字节就与 Go 不同。
        String wrapped = service.normalizeCommentMeta("<b>&</b>");
        assertThat(wrapped).isEqualTo("{\"raw\":\"\\u003cb\\u003e\\u0026\\u003c/b\\u003e\"}");
    }

    @Test
    @DisplayName("🔴 JSON 标量也算合法（Go Unmarshal 成 any 成功即原样返回）")
    void jsonScalarsAreValid() {
        assertThat(service.normalizeCommentMeta("123")).isEqualTo("123");
        assertThat(service.normalizeCommentMeta("true")).isEqualTo("true");
        assertThat(service.normalizeCommentMeta("\"str\"")).isEqualTo("\"str\"");
    }

    @Test
    @DisplayName("🔴 控制字符与引号按 Go 规则转义")
    void controlCharsEscaped() {
        assertThat(CommentTransactionalService.goJsonQuote("a\"b\\c"))
                .isEqualTo("\"a\\\"b\\\\c\"");
        assertThat(CommentTransactionalService.goJsonQuote("a\nb"))
                .isEqualTo("\"a\\nb\"");
    }
}
