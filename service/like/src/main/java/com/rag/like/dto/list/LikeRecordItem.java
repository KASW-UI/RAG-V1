package com.rag.like.dto.list;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>点赞记录条目</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/LikeRecordItem.java:20-56}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type LikeRecordItem struct {
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     Timestamp  int64  `json:"timestamp"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getuserlikelist}（{@link GetUserLikeListResp} 的元素）。
 *
 * <p>⚠️ {@code timestamp} 的单位是<b>毫秒</b>（不是 Unix 秒）—— 与 {@link LikeItem} 同源，
 * 见 {@code service/LikeCursorIdGenerator#timestampOf} 的推导。
 *
 * @param targetType 目标对象类型
 * @param targetId   目标对象 id
 * @param timestamp  点赞时间戳（<b>Unix 毫秒</b>）
 */
public record LikeRecordItem(
        @JsonProperty("target_type") String targetType,
        @JsonProperty("target_id") String targetId,
        @JsonProperty("timestamp") long timestamp) {
}
