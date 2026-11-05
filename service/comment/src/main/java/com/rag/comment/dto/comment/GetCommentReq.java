package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>拉评论请求</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/GetCommentReq.java:33-79}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type GetCommentReq struct {
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     SortType   int64  `json:"sort_type"`
 *     RootId     int64  `json:"root_id"`
 *     Page       int64  `json:"page"`
 *     PageSize   int64  `json:"page_size"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/list}（<b>不需 uid</b> ——
 * B 侧 {@code GetCommentService.java:20-21} 明写本类<b>不</b>调
 * {@code support.userId(request)}）。
 *
 * <h3>⚠️ 六个字段全部必填</h3>
 * <p>（tag 均无 {@code ,optional}）⇒ 缺字段 / 显式 {@code null} 都是解析失败 → 200 + 5001。
 *
 * <p><b>⚠️ 这与「分页有兜底默认值」<b>不矛盾</b></b>：兜底发生在
 * {@code GetCommentService}（B 侧 {@code getcommentlogic.go:47-55}，处理
 * {@code page<=0} / {@code pageSize<=0}），是<b>业务层</b>；解析层仍然要求这些 key
 * 必须出现在 body 里。两者是<b>先后两道关卡</b>，不是同一道。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧手写的 {@code assertGoRequiredFields}
 *       → 换成 Bean Validation {@code @NotNull}（A10 断言要求的形态）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— B 侧只判 null（空串合法）⇒ {@code @NotNull}
 *       同样只判 null ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔。</li>
 *   <li><b>B 出处</b>：{@code GetCommentReq.java} 的 {@code assertGoRequiredFields}
 *       ＋ {@code GoStyleRequired.field(...)} 六条调用。</li>
 * </ol>
 *
 * @param targetType 目标对象类型（必填）
 * @param targetId   目标对象 id（必填）
 * @param sortType   排序类型（必填；1=热门 / 2=时间，见 {@code CommentIndexMapper} 的常量）
 * @param rootId     根评论 id（必填；拉顶层评论时为 0）
 * @param page       页码（必填；{@code <=0} 时业务层兜底）
 * @param pageSize   每页条数（必填；{@code <=0} 时业务层兜底）
 */
public record GetCommentReq(
        @NotNull @JsonProperty("target_type") String targetType,
        @NotNull @JsonProperty("target_id") String targetId,
        @NotNull @JsonProperty("sort_type") Long sortType,
        @NotNull @JsonProperty("root_id") Long rootId,
        @NotNull @JsonProperty("page") Long page,
        @NotNull @JsonProperty("page_size") Long pageSize) {
}
