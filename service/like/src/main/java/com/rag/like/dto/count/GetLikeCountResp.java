package com.rag.like.dto.count;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * <p><b>批量点赞 / 点踩数查询响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetLikeCountResp.java:20-46}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetLikeCountResp struct {
 *     Counts map[string]LikeCountItem `json:"counts"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getlikecount}。
 *
 * <h3>⚠️ {@code counts} 在 B 侧永远非 null</h3>
 * <p>{@code getlikecountlogic.go:48} 是
 * {@code counts := make(map[string]types.LikeCountItem, len(rpcResp.Counts))}
 * —— 无条件 {@code make}，即便 rpc 返回空也得到一个空 map ⇒ JSON 是 <b>{}</b> 而<b>不是 null</b>。
 * ⇒ C 侧因此必须<b>同样无条件 new 一个 map</b>；返回 {@code null} 会让
 * {@code "counts":null}，那是可观察的字节级差异。
 * ⇒ 该保证落在<b>服务层</b>（构造响应时必给空 map），本 record 本身不强制。
 *
 * @param counts 目标 id → 点赞点踩数的映射（<b>非 null</b>）
 */
public record GetLikeCountResp(
        @JsonProperty("counts") Map<String, LikeCountItem> counts) {
}
