package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>管理评论响应</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/ManageCommentResp.java:9-25}
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/manage}。
 *
 * <h3>⚠️ {@code success} <b>透传 rpc 回值</b>（与 {@link DeleteCommentResp} 的硬编码不对称）</h3>
 * <p>本 Resp 的 {@code success} 是 rpc 回值（<b>可能为 false</b>）；
 * 而 {@link DeleteCommentResp#success()} 是 B 侧硬编码的 {@code true}。
 * ⇒ 同一个字段名在本域 5 个 Resp 里有<b>两种来源</b>，这是 B 侧的既有事实。
 * ⚠️ <b>不要「顺手统一」</b> ⇒ 由 {@code CommentDtoContractTest} 逐个钉死。
 *
 * @param success 是否成功（<b>透传 rpc 回值</b>，可能为 false）
 */
public record ManageCommentResp(
        @JsonProperty("success") boolean success) {
}
