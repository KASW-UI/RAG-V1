package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringFields;

import java.util.List;

/**
 * <p><b>职责</b>：更新文章的入参（JSON body）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/UpdateArticleReq.java}
 * （类声明 {@code :57} · 七个字段 {@code :59-65} · 五个 {@code Object} 形参 setter {@code :67-94}
 * ＋ {@code status} 的 {@code Integer} setter {@code :105-108}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>可变类 + setter → immutable record</b>（D5 + A9）⇒ 五个字符串 setter 变成
 *             <b>{@link JsonCreator} 次构造器</b>（形参保持 {@code Object}）；</li>
 *         <li><b>包名压平</b>（D11）。</li>
 *       </ul>
 *       ⚠️ <b>刻意<b>不加</b> {@code @NotNull}</b>：B 侧本类的 7 个 JSON 字段<b>全部</b>是
 *       {@code ,optional} ⇒ go-zero 的 {@code processNamedFieldWithoutValue} 的
 *       {@code default:} 分支（{@code if !opts.optional() { return newInitError } }）<b>永不触发</b>
 *       ⇒ 加一个必填校验会是<b>凭空新增的拒绝路径</b>（B 侧
 *       {@code UpdateArticleReq.java:29-35} 明写了这个纪律）。</li>
 *   </li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名逐字相同</b>（见下方对照表）；</li>
 *         <li><b>类型不符</b>：走 {@link StringFields#parse} ⇒ 文案与 B 侧逐字节相同
 *             ⇒ 400 + text/plain；</li>
 *         <li><b>{@code status}</b>：保持 {@link Integer}（<b>不是</b> {@code int}）⇒
 *             「缺席」与「显式 0」在 proto 侧要分开表达（{@code optional ArticleStatus status}）
 *             ⇒ 用 {@code int} 会把两者压成同一个 0（B 侧 {@code UpdateArticleReq.java:97-104}）；</li>
 *         <li><b>「空 ⇒ 缺席」的转换<b>不在本层</b></b>：那是业务语义，留在 service 层
 *             （B 侧 {@code UpdateArticleReq.java:37-47} 明写「本 DTO 不做『空 ⇒ 缺席』的转换」）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>JSON key 逐条对照（L1 · 不可改）</h2>
 * <pre>
 *   Go (types.go:74-83):
 *     ArticleId     string   `path:"id"`                       ← 来自 URL，不是 JSON
 *     Title         string   `json:"title,optional"`
 *     Brief         string   `json:"brief,optional"`
 *     Content       string   `json:"content,optional"`
 *     CoverImageUrl string   `json:"cover_image_url,optional"`
 *     ManualTypeTag string   `json:"manual_type_tag,optional"`
 *     SecondaryTags []string `json:"secondary_tags,optional"`
 *     Status        int32    `json:"status,optional"`
 * </pre>
 * <table border="1">
 *   <tr><th>Go tag</th><th>本 record 组件</th></tr>
 *   <tr><td>{@code title}</td><td>{@code title}</td></tr>
 *   <tr><td>{@code brief}</td><td>{@code brief}</td></tr>
 *   <tr><td>{@code content}</td><td>{@code content}</td></tr>
 *   <tr><td>{@code cover_image_url}</td><td>{@code coverImageUrl}</td></tr>
 *   <tr><td>{@code manual_type_tag}</td><td>{@code manualTypeTag}</td></tr>
 *   <tr><td>{@code secondary_tags}</td><td>{@code secondaryTags}</td></tr>
 *   <tr><td>{@code status}</td><td>{@code status}</td></tr>
 * </table>
 *
 * <h2>⚠️ {@code articleId} 刻意<b>不在</b>本类里</h2>
 * <p>Go 侧它带的是 {@code path:} tag 而非 {@code json:}，语义上就是 URL 段。
 * 若把它留在 DTO 里，Jackson 默认会按<b>字段名</b> {@code articleId} 绑定它，
 * 正好落进 Go 侧那条「无 json tag ⇒ 回落到字段名」的意外路径
 * ⇒ 交给 controller 的 {@code @PathVariable} 参数，从类型上就断掉了这条路径
 * （B 侧 {@code UpdateArticleReq.java:49-55}）。
 */
public record UpdateArticleReq(

        @JsonProperty("title")
        String title,

        @JsonProperty("brief")
        String brief,

        @JsonProperty("content")
        String content,

        @JsonProperty("cover_image_url")
        String coverImageUrl,

        @JsonProperty("manual_type_tag")
        String manualTypeTag,

        @JsonProperty("secondary_tags")
        List<String> secondaryTags,

        @JsonProperty("status")
        Integer status) {

    /**
     * Jackson 的绑定入口 —— 五个字符串字段的形参刻意声明成 {@code Object}。
     *
     * <p>⚠️ <b>{@code status} 保持 {@link Integer}</b>：它<b>不</b>走
     * {@link StringFields}（B 侧它就是 {@code Integer} 形参的普通 setter，
     * B {@code UpdateArticleReq.java:105-108}）⇒ 类型不符由 Jackson + 共享层 coercion 判
     * ⇒ {@code {"status":"2"}} 被拒成 400（状态码与 B 侧一致，body 文案为 Jackson 措辞）。
     *
     * <p><b>B 出处</b>：{@code .../dto/UpdateArticleReq.java:67-94}（五个 {@code Object} 形参 setter）
     * ＋ {@code .../dto/GoStyleStringFields.java:62-78}（类型判定）。
     */
    @JsonCreator
    public UpdateArticleReq(
            @JsonProperty("title") Object rawTitle,
            @JsonProperty("brief") Object rawBrief,
            @JsonProperty("content") Object rawContent,
            @JsonProperty("cover_image_url") Object rawCoverImageUrl,
            @JsonProperty("manual_type_tag") Object rawManualTypeTag,
            @JsonProperty("secondary_tags") List<String> secondaryTags,
            @JsonProperty("status") Integer status) {
        this(StringFields.parse("title", rawTitle),
                StringFields.parse("brief", rawBrief),
                StringFields.parse("content", rawContent),
                StringFields.parse("cover_image_url", rawCoverImageUrl),
                StringFields.parse("manual_type_tag", rawManualTypeTag),
                secondaryTags,
                status);
    }
}
