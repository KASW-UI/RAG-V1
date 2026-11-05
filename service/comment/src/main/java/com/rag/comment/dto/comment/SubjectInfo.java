package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>评论区信息</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/SubjectInfo.java:9-73}
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/list}（作为 {@link GetCommentResp#subject()}）。
 *
 * <h3>⚠️ {@code owner_id} <b>恒为 0</b>（B 侧既有事实 · 逐字继承）</h3>
 * <p>B 侧 {@code SubjectInfo} 的 {@code owner_id} 字段<b>两端都不填</b>
 * ⇒ 序列化出来恒为 {@code 0} ⇒ 客户端<b>永远拿不到</b>评论区的属主 id。
 * ⇒ ⚠️ <b>不要「顺手修好」它</b>（那会改掉可观察行为）⇒ 由
 * {@code SubjectInfoContractTest} 钉死。
 *
 * <h3>⚠️ {@code target_type} / {@code target_id} 的默认值是空串</h3>
 * <p>B 侧字段初始化为 {@code ""}（不是 null）⇒ 空结果输出
 * {@code "target_type":"", "target_id":""} ⇒ 而<b>不是</b> {@code null} 或被省略。
 * ⇒ C 侧 record 的对应分量<b>没有默认构造值</b> ⇒ 由 {@code GetCommentService}
 * 负责传入空串（而不是 null）。
 *
 * @param targetType 目标对象类型（<b>默认空串</b>）
 * @param targetId   目标对象 id（<b>默认空串</b>）
 * @param totalCount 评论总数
 * @param rootCount  根评论数
 * @param state      评论区状态
 * @param attribute  扩展属性位
 * @param ownerId    属主 id（<b>本域恒为 0</b> —— B 侧两端都不填）
 */
public record SubjectInfo(
        @JsonProperty("target_type") String targetType,
        @JsonProperty("target_id") String targetId,
        @JsonProperty("total_count") long totalCount,
        @JsonProperty("root_count") long rootCount,
        @JsonProperty("state") int state,
        @JsonProperty("attribute") long attribute,
        @JsonProperty("owner_id") long ownerId) {

    /**
     * 「全零对象」—— B 侧 {@code SubjectInfo} 的值类型零值形态。
     *
     * <p>⚠️ B 侧 {@code GetCommentResp.Subject} 是<b>值类型</b> ⇒ 空结果时
     * 序列化成 {@code {"target_type":"","target_id":"","total_count":0,…}}，
     * <b>不是</b> {@code null}、也<b>不是</b>被省略。
     * ⇒ 由本工厂提供该零值，避免各处手写 7 个参数。
     *
     * @return 全零的评论区信息
     */
    public static SubjectInfo zero() {
        return new SubjectInfo("", "", 0L, 0L, 0, 0L, 0L);
    }
}
