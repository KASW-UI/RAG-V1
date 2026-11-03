package com.rag.like.dto.count;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>点赞 / 点踩计数项</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/LikeCountItem.java:20-53}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type LikeCountItem struct {
 *     LikeCount    int64 `json:"like_count"`
 *     DislikeCount int64 `json:"dislike_count"`
 * }
 * </pre>
 *
 * <p>L1 wire key 逐字对齐 {@code like_count} / {@code dislike_count}，
 * 来自 proto {@code LikeCountItem} 的同名字段。
 *
 * @param likeCount    点赞数
 * @param dislikeCount 点踩数
 */
public record LikeCountItem(
        @JsonProperty("like_count") long likeCount,
        @JsonProperty("dislike_count") long dislikeCount) {
}
