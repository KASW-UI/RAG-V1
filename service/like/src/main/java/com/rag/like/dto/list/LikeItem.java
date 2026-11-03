package com.rag.like.dto.list;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>点赞人条目</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/LikeItem.java:20-48}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type LikeItem struct {
 *     UserId    int64 `json:"user_id"`
 *     Timestamp int64 `json:"timestamp"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/gettargetlikerlist}（{@link GetTargetLikerListResp} 的元素）。
 *
 * <p>⚠️ {@code timestamp} 的单位是<b>毫秒</b>（不是 Unix 秒）：
 * B 侧读路径用 {@code score >> 22} 从 Redis ZSet 的 score 反解出<b>裸 Unix 毫秒</b>
 * （见 {@code service/LikeCursorIdGenerator#timestampOf}）。
 *
 * @param userId    点赞人 id
 * @param timestamp 点赞时间戳（<b>Unix 毫秒</b>）
 */
public record LikeItem(
        @JsonProperty("user_id") long userId,
        @JsonProperty("timestamp") long timestamp) {
}
