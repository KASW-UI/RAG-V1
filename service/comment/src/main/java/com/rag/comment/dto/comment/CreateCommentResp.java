package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>建评论响应</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/CreateCommentResp.java:9-33}
 * （B 侧原始 Go 形态见 {@code types.go:31-35}），逐字段取自 rpc 返回
 * （B 侧 {@code createcommentlogic.go:60-64}）。
 *
 * <h3>⚠️ {@code subject_count} 在本域<b>恒为 0</b>（既有缺陷 · 逐字继承）</h3>
 * <p>B 侧原注释明写：本域「创建评论」<b>不写库</b> —— 落库发生在 Kafka 消费侧
 * ⇒ 建评论返回时，评论区还没有任何评论 ⇒ {@code subject_count} 恒为 0。
 * ⇒ ⚠️ <b>不要「顺手修好」它</b>（那会改变可观察行为）⇒ 由
 * {@code CreateCommentRespContractTest} 钉死。
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/create}。
 *
 * @param id           评论 id（雪花算法生成）
 * @param createdAt    创建时间（wire 格式 {@code yyyy-MM-dd HH:mm:ss}，本地时区、无时区后缀）
 * @param subjectCount 评论区评论数（<b>本域恒为 0</b>）
 */
public record CreateCommentResp(
        @JsonProperty("id") long id,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("subject_count") long subjectCount) {
}
