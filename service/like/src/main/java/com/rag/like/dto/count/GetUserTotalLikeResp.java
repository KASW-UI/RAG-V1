package com.rag.like.dto.count;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>某用户总获赞数响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetUserTotalLikeResp.java:20-38}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetUserTotalLikeResp struct {
 *     TotalLikeCount int64 `json:"total_like_count"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getusertotallike}。
 *
 * @param totalLikeCount 该用户收到的点赞总数
 */
public record GetUserTotalLikeResp(
        @JsonProperty("total_like_count") long totalLikeCount) {
}
