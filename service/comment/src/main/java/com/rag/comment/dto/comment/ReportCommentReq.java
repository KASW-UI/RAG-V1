package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>举报评论请求</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/ReportCommentReq.java:14-49}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type ReportCommentReq struct {
 *     CommentId  int64  `json:"comment_id"`
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     Reason     int32  `json:"reason"`
 *     Detail     string `json:"detail"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/report}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 五个字段全部必填</h3>
 * <p>（tag 均无 {@code ,optional}）⇒ 缺字段 / 显式 {@code null} 都是解析失败 → 200 + 5001。
 *
 * <h3>⚠️ {@code reason} 在本 DTO <b>不做取值域校验</b></h3>
 * <p>B 侧该字段的 tag 里<b>没有</b> {@code options=...} ⇒ 解析层<b>不限制</b>取值范围
 * ⇒ ⇒ C 侧<b>不能</b>加 {@code @Pattern} / {@code @Max}（那是新增行为）。
 * 取值域的判定在<b>业务层</b>（{@code CommentDomainService}）。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧手写的 {@code assertGoRequiredFields}
 *       → 换成 Bean Validation {@code @NotNull}（A10 断言要求的形态）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— B 侧只判 null（空串合法）⇒ {@code @NotNull}
 *       同样只判 null ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔。</li>
 *   <li><b>B 出处</b>：{@code ReportCommentReq.java} 的 {@code assertGoRequiredFields}
 *       ＋ {@code GoStyleRequired.field(...)} 五条调用。</li>
 * </ol>
 *
 * @param commentId  评论 id（必填）
 * @param targetType 目标对象类型（必填）
 * @param targetId   目标对象 id（必填）
 * @param reason     举报理由（必填；<b>取值域由业务层判</b>，解析层不限）
 * @param detail     举报详情（必填）
 */
public record ReportCommentReq(
        @NotNull @JsonProperty("comment_id") Long commentId,
        @NotNull @JsonProperty("target_type") String targetType,
        @NotNull @JsonProperty("target_id") String targetId,
        @NotNull @JsonProperty("reason") Integer reason,
        @NotNull @JsonProperty("detail") String detail) {
}
