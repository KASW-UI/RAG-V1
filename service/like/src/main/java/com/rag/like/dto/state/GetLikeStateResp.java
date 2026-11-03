package com.rag.like.dto.state;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * <p><b>某用户对一批目标的点赞状态响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetLikeStateResp.java:30-50}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetLikeStateResp struct {
 *     States map[string]int32 `json:"states"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getlikestate}。
 *
 * <h3>⚠️ 与 {@link GetLikeCountResp} 的非 nil 保证来源不同，但结果同样非 null</h3>
 * <p>api 侧是直接透传 {@code States: rpcResp.States}（B 侧 {@code getlikestatelogic.go:66}），
 * 非 nil 由<b>rpc 侧</b>保证 —— B 侧 {@code getlikestatelogic.go:34-36} 是
 * {@code resp := &pb.GetLikeStateResp{States: make(map[string]int32)}}，
 * 且成功路径上每个 {@code target_ids} 都必然写入一条（Redis 命中或 DB 回源填零值），
 * 所以成功时 {@code states} 既非 null 也非空。
 *
 * <p>value 类型用 {@link Integer}：proto 的 {@code map<string,int32>} 语义等价。
 *
 * @param states 目标 id → 状态值（0 无 / 1 已点赞 / 2 已点踩）
 */
public record GetLikeStateResp(
        @JsonProperty("states") Map<String, Integer> states) {
}
