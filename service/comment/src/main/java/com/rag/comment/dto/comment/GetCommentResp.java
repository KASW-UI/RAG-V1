package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>拉评论响应</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/GetCommentResp.java:16-52}
 * （B 侧原始 Go 形态见 {@code types.go:57-60}）：
 * <pre>
 * type GetCommentResp struct {
 *     Comment []CommentItem `json:"comment"`
 *     Subject SubjectInfo   `json:"subject"`
 * }
 * </pre>
 *
 * <h3>⚠️ 两个字段在 wire 上都恒不为 null</h3>
 * <p>B 侧 {@code getcommentlogic.go:78-80} 先建 {@code resp = &types.GetCommentResp{
 * Comment: make([]types.CommentItem, 0)}}，且 {@code Subject} 是<b>值类型</b>
 * ⇒ 空结果输出 {@code "comment":[], "subject":{全零}}，而不是 {@code null} 或被省略。
 * <p>⇒ Java 侧据此：构造时 {@code comment} 给<b>空列表</b>（不是 null）、
 * {@code subject} 给<b>全零对象</b>（不是 null）。
 * ⚠️ record 的紧凑构造器无法自动做「null → 空列表」的兜底 ⇒
 * 由 {@code GetCommentService} 负责传入非 null 值，
 * 并由 {@code GetCommentRespContractTest} 钉死「空结果也输出两个非 null 字段」。
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/list}。
 *
 * @param comment 评论列表（<b>非 null</b>；空结果为 {@code []}）
 * @param subject 评论区信息（<b>非 null</b>；空结果为全零对象）
 */
public record GetCommentResp(
        @JsonProperty("comment") List<CommentItem> comment,
        @JsonProperty("subject") SubjectInfo subject) {
}
