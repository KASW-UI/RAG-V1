package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：文章响应投影 —— article 域对外输出的文章结构体（L1 wire 契约）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/Article.java}
 * （类声明 {@code :93} · {@code @JsonPropertyOrder} {@code :88-92} · 各字段 {@code :95-142}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 → immutable record</b>（D5 + A9）＋ <b>包名压平</b>
 *       （{@code com.rag.article.article.api.dto} → {@code com.rag.article.dto.article}，D11）＋
 *       增补 {@link #withAuthorName(String)}（record 不可变 ⇒ 昵称补全需要「造新实例」的表达层手段）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名逐个不变</b>：见下方「JSON key 逐条对照」表；</li>
 *         <li><b>字段顺序不变</b>：17 个键按 Go 的 {@code encoding/json} struct <b>声明序</b>
 *             （B 出处：{@code types.go:6-24}）⇒ C 侧是 <b>immutable record</b>
 *             ⇒ Jackson 2 默认即按<b>声明序</b>输出 ⇒ <b>天然与 Go 一致</b>
 *             （<b>实测</b>：删掉本注解字节不变 ⇒ 见 {@code tools/difftest/jackson-key-order/}），
 *             ⇒ ⇒ {@code @JsonPropertyOrder} <b>保留</b>，但它在 <b>C 侧</b>的作用<b>不是</b>
 *             「修正默认字母序」（那是 B 侧 Jackson 3 + 可变 class 的形态），而是
 *             <b>把顺序显式钉死</b> —— 组件一旦被重排，无注解时字节序会静默漂移；</li>
 *         <li><b>「缺席 vs null」不变</b>：{@code author_name} 仍用 {@code NON_EMPTY}（空 ⇒ key 消失），
 *             {@code secondary_tags} / {@code ext_info} 仍保持 {@code null}（<b>不</b>用 {@code NON_EMPTY}）；</li>
 *         <li>record 的<b>访问器名</b>（{@code id()} 而非 {@code getId()}）只影响 Java 侧调用，
 *             <b>不进入 wire</b> ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>JSON key 逐条对照（L1 · 不可改）</h2>
 * <table border="1">
 *   <caption>Go {@code types.go:6-24} → 本 record</caption>
 *   <tr><th>Go tag</th><th>本 record 组件</th><th>备注</th></tr>
 *   <tr><td>{@code id}</td><td>{@code id}</td><td>—</td></tr>
 *   <tr><td>{@code title}</td><td>{@code title}</td><td>—</td></tr>
 *   <tr><td>{@code brief}</td><td>{@code brief}</td><td>—</td></tr>
 *   <tr><td>{@code content}</td><td>{@code content}</td><td>⚠️ 值取 proto 的 {@code markdown_content}</td></tr>
 *   <tr><td>{@code cover_image_url}</td><td>{@code coverImageUrl}</td><td>显式改名</td></tr>
 *   <tr><td>{@code manual_type_tag}</td><td>{@code manualTypeTag}</td><td>显式改名</td></tr>
 *   <tr><td>{@code secondary_tags}</td><td>{@code secondaryTags}</td><td>空 ⇒ {@code null}（<b>不是</b> {@code []}）</td></tr>
 *   <tr><td>{@code author_id}</td><td>{@code authorId}</td><td>显式改名</td></tr>
 *   <tr><td>{@code author_name,omitempty}</td><td>{@code authorName}</td><td>⚠️ <b>唯一</b>带 {@code NON_EMPTY} 的字段</td></tr>
 *   <tr><td>{@code create_time}</td><td>{@code createTime}</td><td>毫秒时间戳</td></tr>
 *   <tr><td>{@code update_time}</td><td>{@code updateTime}</td><td>毫秒时间戳</td></tr>
 *   <tr><td>{@code status}</td><td>{@code status}</td><td>int32（<b>数值</b>，不是枚举名）</td></tr>
 *   <tr><td>{@code view_count}</td><td>{@code viewCount}</td><td>显式改名</td></tr>
 *   <tr><td>{@code like_count}</td><td>{@code likeCount}</td><td>显式改名</td></tr>
 *   <tr><td>{@code comment_count}</td><td>{@code commentCount}</td><td>显式改名</td></tr>
 *   <tr><td>{@code share_count}</td><td>{@code shareCount}</td><td>显式改名</td></tr>
 *   <tr><td>{@code ext_info}</td><td>{@code extInfo}</td><td>空 map ⇒ {@code null}；<b>key 按码点序</b></td></tr>
 * </table>
 *
 * <p>⚠️ <b>本仓没有全局 snake_case 命名策略</b>（全仓无 {@code property-naming-strategy} 配置）
 * ⇒ snake_case 一律<b>显式</b> {@link JsonProperty}，与 message 域的 DTO 约定一致。
 */
@JsonPropertyOrder({
        "id", "title", "brief", "content", "cover_image_url", "manual_type_tag",
        "secondary_tags", "author_id", "author_name", "create_time", "update_time",
        "status", "view_count", "like_count", "comment_count", "share_count", "ext_info"
})
public record Article(

        String id,

        String title,

        String brief,

        String content,

        @JsonProperty("cover_image_url")
        String coverImageUrl,

        @JsonProperty("manual_type_tag")
        String manualTypeTag,

        @JsonProperty("secondary_tags")
        List<String> secondaryTags,

        @JsonProperty("author_id")
        String authorId,

        /**
         * Go {@code AuthorName string `json:"author_name,omitempty"`} ⇒ 空串时<b>整个 key 消失</b>。
         *
         * <p>⚠️ <b>只有本字段</b>带 {@code NON_EMPTY}；{@code secondary_tags} / {@code ext_info}
         * 在 Go 侧<b>没有</b> {@code omitempty} ⇒ 必须输出 {@code null} 而不是省略。
         */
        @JsonProperty("author_name")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String authorName,

        @JsonProperty("create_time")
        long createTime,

        @JsonProperty("update_time")
        long updateTime,

        int status,

        @JsonProperty("view_count")
        int viewCount,

        @JsonProperty("like_count")
        int likeCount,

        @JsonProperty("comment_count")
        int commentCount,

        @JsonProperty("share_count")
        int shareCount,

        @JsonProperty("ext_info")
        Map<String, String> extInfo) {

    /**
     * 造一个「只换昵称」的副本 —— record 不可变，昵称补全无法原地改。
     *
     * <p><b>L4 idiomatization（三段式）</b>：
     * <ol>
     *   <li><b>比 B 侧多做了什么</b>：B 侧是可变类 + {@code setAuthorName}（B 侧
     *       {@code ArticleApiSupport.java:137} / {@code :163} / {@code :168} / {@code :173} 直接 set）。
     *       本仓 DTO 层是 immutable record（D5 + A9）⇒ 改成「造新实例」。</li>
     *   <li><b>语义还一样吗</b>：一样 —— 昵称补全的<b>时机</b>（列表/详情映射完成后）与<b>取值</b>
     *       （{@code fallbackAuthorName} 的 4 条回落）逐条不变；改变的只是「怎么把新值放回去」。
     *       ⚠️ 因为 record 不可变，<b>补全后必须用返回值替换原引用</b>
     *       —— 调用点见 {@code ArticleApiSupport#enrichArticleAuthors}。</li>
     *   <li><b>B 出处</b>：{@code .../dto/Article.java:116-119}（字段）
     *       ＋ {@code .../service/ArticleApiSupport.java:133-175}（三个 set 点）。</li>
     * </ol>
     *
     * @param newAuthorName 新的昵称（{@code null} ⇒ 该 key 从输出中消失）
     */
    public Article withAuthorName(String newAuthorName) {
        return new Article(
                id, title, brief, content, coverImageUrl, manualTypeTag, secondaryTags,
                authorId, newAuthorName, createTime, updateTime,
                status, viewCount, likeCount, commentCount, shareCount, extInfo);
    }
}
