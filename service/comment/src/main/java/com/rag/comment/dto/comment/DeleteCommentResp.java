package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>删评论响应</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/DeleteCommentResp.java:9-30}
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/delete}。
 *
 * <h3>⚠️ {@code success} <b>恒为 true</b>（B 侧硬编码 · 与其余 4 个 Resp 不对称）</h3>
 * <p>B 侧 {@code DeleteCommentService.java:58} 直接 {@code resp.setSuccess(true)} ——
 * <b>不取 rpc 回值</b>。而 {@code LikeCommentResp} / {@code ManageCommentResp} /
 * {@code ReportCommentResp} / {@code UpdateSubjectStateResp} 的 {@code success}
 * 都是<b>透传 rpc 回值</b>。
 * ⇒ ⇒ 同一个字段名在本域 5 个 Resp 里有<b>两种来源</b>，这是 B 侧的既有事实。
 * ⚠️ <b>不要「顺手统一」</b>（那会改掉 4 个 Resp 的可观察行为）⇒ 由
 * {@code CommentDtoContractTest} 逐个钉死。
 *
 * @param success           是否成功（<b>本响应恒为 true</b> —— B 侧硬编码）
 * @param subjectTotalCount 评论区评论总数
 */
public record DeleteCommentResp(
        @JsonProperty("success") boolean success,
        @JsonProperty("subject_total_count") long subjectTotalCount) {
}
