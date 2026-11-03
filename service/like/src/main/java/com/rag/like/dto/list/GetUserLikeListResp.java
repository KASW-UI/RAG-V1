package com.rag.like.dto.list;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>某用户点赞列表响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetUserLikeListResp.java:38-84}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetUserLikeListResp struct {
 *     List       []LikeRecordItem `json:"list"`
 *     IsEnd      bool             `json:"is_end"`
 *     NextCursor int64            `json:"next_cursor"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getuserlikelist}。
 *
 * <p>与 {@link GetTargetLikerListResp} 结构同构，差异只在 list 的元素类型
 * （本端点是<b>某用户点过什么</b> ⇒ {@link LikeRecordItem}，带 target_type/target_id）。
 *
 * <h3>⚠️ {@code list} 在 B 侧永远非 nil</h3>
 * <p>{@code getuserlikelistlogic.go:65} 是
 * {@code list := make([]types.LikeRecordItem, 0, len(rpcResp.List))} —— 无条件 make，
 * 空结果也是 <b>[]</b>。
 *
 * <p><b>⚠️ 字段顺序即 wire 顺序</b>：B 侧 Go struct 的字段声明序 = JSON 输出序
 * ⇒ record 分量顺序按 B 侧逐条对齐（{@code list} → {@code is_end} → {@code next_cursor}）。
 *
 * @param list       点赞记录列表（<b>非 null</b>）
 * @param isEnd      是否到头
 * @param nextCursor 下一次查询的游标位置
 */
public record GetUserLikeListResp(
        @JsonProperty("list") List<LikeRecordItem> list,
        @JsonProperty("is_end") boolean isEnd,
        @JsonProperty("next_cursor") long nextCursor) {
}
