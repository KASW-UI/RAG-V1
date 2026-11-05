package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>评论条目</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/CommentItem.java:13-182}
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/list}（作为 {@link GetCommentResp#comment()} 的元素）。
 *
 * <h3>⚠️ {@code children} 是<b>递归</b>字段，且恒为空列表</h3>
 * <p>B 侧 {@code CommentItem.children} 是 {@code List<CommentItem>}，<b>递归引用自身</b>。
 * ⇒ Java 侧 record 的分量类型必须能引用自身 ⇒ 用 {@code List<CommentItem>}（接口类型），
 * <b>不能</b>用 {@code List<CommentItem>} 的具体实现类型做泛型自引用陷阱（那在 record 里合法，
 * 但 record 是不可变的 ⇒ children 只能是<b>不可变列表</b>）。
 *
 * <p>⚠️ <b>B 侧恒为 {@code []}（空列表），永不填充</b>：B 侧 {@code CommentItem.children}
 * 初始化为 {@code new ArrayList<>()}，但<b>没有任何代码往里放东西</b>
 * ⇒ 序列化出来恒为 {@code "children":[]}。
 * ⇒ ⚠️ <b>不要「顺手把回复填进去」</b>（那会改掉可观察行为）⇒ 由
 * {@code CommentItemContractTest} 钉死。
 *
 * <h3>⚠️ record + 递归 list 的可变性</h3>
 * <p>record 本身不可变，但 {@code children} 若传入可变 {@link List}，元素仍可被外部改动
 * ⇒ 为保证「不可变 record」的真实语义，构造时用 {@link List#copyOf} 固化。
 *
 * @param id           评论 id
 * @param userId       评论者 id
 * @param content      评论正文
 * @param rootId       根评论 id
 * @param parentId     父评论 id
 * @param likeCount    点赞数
 * @param dislikeCount 点踩数
 * @param replyCount   回复数
 * @param attribute    扩展属性位
 * @param state        评论状态
 * @param createdAt    创建时间（wire 格式 {@code yyyy-MM-dd HH:mm:ss}）
 * @param meta         附加元信息
 * @param children     子评论（<b>本域恒为空列表</b>）
 */
public record CommentItem(
        @JsonProperty("id") long id,
        @JsonProperty("user_id") long userId,
        @JsonProperty("content") String content,
        @JsonProperty("root_id") long rootId,
        @JsonProperty("parent_id") long parentId,
        @JsonProperty("like_count") long likeCount,
        @JsonProperty("dislike_count") long dislikeCount,
        @JsonProperty("reply_count") long replyCount,
        @JsonProperty("attribute") long attribute,
        @JsonProperty("state") int state,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("meta") String meta,
        @JsonProperty("children") List<CommentItem> children) {

    /**
     * 紧凑构造器 —— 把 {@code children} 固化为不可变列表。
     *
     * <p>⚠️ <b>{@code null} 归一为空列表</b>：B 侧该字段恒为 {@code new ArrayList<>()}
     * ⇒ wire 上恒为 {@code "children":[]}，<b>不是</b> {@code null}。
     * ⇒ ⇒ 归一后保证「record 不可变」与「wire 形状」两件事同时成立。
     */
    public CommentItem {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
