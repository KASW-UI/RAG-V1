package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>更新评论区状态请求</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/UpdateSubjectStateReq.java:14-43}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type UpdateSubjectStateReq struct {
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     State      int32  `json:"state"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /comment/v1/subject/state}（<b>需 uid</b>）。
 * ⚠️ <b>注意路径里只有一次 {@code comment}</b> —— 它是
 * {@code /comment/v1} 前缀 ＋ {@code subject/state}，<b>不是</b> {@code comment/subject/state}。
 * 路由是 L1 契约（{@code CommentController} 的 7 条 {@code @PostMapping}）。
 *
 * <h3>⚠️ 三个字段全部必填</h3>
 * <p>（tag 均无 {@code ,optional}）⇒ 缺字段 / 显式 {@code null} 都是解析失败 → 200 + 5001。
 *
 * <h3>⚠️ {@code state} 在本 DTO <b>不做取值域校验</b></h3>
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
 *   <li><b>B 出处</b>：{@code UpdateSubjectStateReq.java} 的 {@code assertGoRequiredFields}
 *       ＋ {@code GoStyleRequired.field(...)} 三条调用。</li>
 * </ol>
 *
 * @param targetType 目标对象类型（必填）
 * @param targetId   目标对象 id（必填）
 * @param state      评论区状态（必填；<b>取值域由业务层判</b>，解析层不限）
 */
public record UpdateSubjectStateReq(
        @NotNull @JsonProperty("target_type") String targetType,
        @NotNull @JsonProperty("target_id") String targetId,
        @NotNull @JsonProperty("state") Integer state) {
}
