package com.rag.hot.dto.article;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * <p><b>热门文章卡片的 HTTP 层投影</b> —— 13 个字段，与 B 侧 {@code HotArticleCard} 逐条一致。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/dto/HotArticleCard.java}
 * <pre>{@code
 *   @JsonPropertyOrder({13 个键，顺序 = Go 结构体声明序})   :84-87
 *   类声明 + 13 个字段（全部 @JsonProperty 显式钉键名）      :88-127
 *   无参构造器                                              :129-131
 *   13 组手写 getter/setter                                 :132-233
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       ① <b>可变 POJO → immutable record</b>（D5 + A9）⇒ setter 全部消失；
 *       ② 包名 {@code com.rag.hot.hot.api.dto} → {@code com.rag.hot.dto.article}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       ① 13 个字段的<b>名字</b>、<b>类型</b>、<b>JSON 键名</b>、<b>输出顺序</b>逐条未动
 *       （{@code @JsonPropertyOrder} 逐条照抄 B 侧 :84-87）；
 *       ② 两处「Go 的值与 Java 默认值不同」的字段（{@code secondary_tags} 的 null / {@code hot_score} 的无符号）
 *          <b>转换逻辑留在构造处</b>（见 {@code HotApiService}）⇒ 本 record 只是<b>载体</b>，
 *          不改变任何字段的可观察取值。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>✅ 命名债已消除（2026-11-10）</b>：
 * B 侧刻意把 HTTP 层的卡片命名成 {@code HotArticleCard}，为的是<b>避开</b>与 proto message
 * {@code hot.HotArticleItem}（<b>只有 2 个字段</b>：{@code article_id} / {@code hot_score}）同名。
 * ⇒ ⇒ 本仓此前<b>沿用了</b> {@code HotArticleItem} ⇒ 两个同名不同形的类型并存：
 * <pre>
 *   com.rag.hot.dto.article.HotArticleCard   ← 本类（13 字段，HTTP 层投影）
 *   com.rag.proto.hot.HotArticleItem         ← proto 生成物（2 字段，gRPC 层投影）
 * </pre>
 * ⇒ ⇒ **本类的类名与 B 侧逐字一致**（`HotArticleCard`）⇒ ⇒ **不再需要写全限定名**来区分两者
 * ⇒ ⇒ 与 B 侧的类名差异**归零**（此前登记在映射表 hot.md §1.2 的「命名债」一行 ⇒ **已销账**）。
 * ⚠️ **proto 侧那个 {@code HotArticleItem} 保持不动** —— 它是 proto 契约（L1）的一部分。
 *
 * <p><b>⚠️ {@code secondary_tags} 必须是 {@code null}（不是 {@code []}）</b>：
 * B 侧的 proto3 repeated 字段在无元素时是 nil slice ⇒ JSON 输出 {@code "secondary_tags":null}；
 * protobuf-java 永远返回非 null 列表 ⇒ 必须由构造处显式还原（见 {@code HotApiService}）。
 *
 * <p><b>⚠️ {@code hot_score} 声明为 {@code long}</b>：proto 字段是 {@code uint32}，
 * protobuf-java 把它映射成有符号 {@code int} ⇒ 直接输出会在 score ≥ 2^31 时写成负数，
 * 而 B 侧的 JSON 写的是<b>无符号十进制</b> ⇒ 构造处用 {@code Integer.toUnsignedLong(...)} 还原。
 *
 * <p><b>⚠️ 字段顺序必须显式钉住（字节级 L1）</b>：
 * Jackson 的默认行为是<b>声明序</b>，但全局特性
 * {@code MapperFeature.SORT_PROPERTIES_ALPHABETICALLY} 一旦被打开，输出就会变成<b>字母序</b>
 * ⇒ 与 B 侧的字节不同 ⇒ {@link JsonPropertyOrder} <b>显式</b>钉死顺序（注解优先于全局特性）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：DTO 层按 D5 + A9 一律用 record ⇒ Lombok 被明令禁止。
 */
@JsonPropertyOrder({
        "article_id", "rank", "hot_score", "title", "brief", "cover_image_url", "author_id",
        "create_time", "view_count", "like_count", "comment_count", "manual_type_tag", "secondary_tags"
})
public record HotArticleCard(
        @com.fasterxml.jackson.annotation.JsonProperty("article_id") String articleId,
        @com.fasterxml.jackson.annotation.JsonProperty("rank") int rank,
        @com.fasterxml.jackson.annotation.JsonProperty("hot_score") long hotScore,
        @com.fasterxml.jackson.annotation.JsonProperty("title") String title,
        @com.fasterxml.jackson.annotation.JsonProperty("brief") String brief,
        @com.fasterxml.jackson.annotation.JsonProperty("cover_image_url") String coverImageUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("author_id") String authorId,
        @com.fasterxml.jackson.annotation.JsonProperty("create_time") long createTime,
        @com.fasterxml.jackson.annotation.JsonProperty("view_count") int viewCount,
        @com.fasterxml.jackson.annotation.JsonProperty("like_count") int likeCount,
        @com.fasterxml.jackson.annotation.JsonProperty("comment_count") int commentCount,
        @com.fasterxml.jackson.annotation.JsonProperty("manual_type_tag") String manualTypeTag,
        @com.fasterxml.jackson.annotation.JsonProperty("secondary_tags") List<String> secondaryTags) {
}
