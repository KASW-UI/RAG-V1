package com.rag.like.dto.list;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>某稿件的点赞人列表响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetTargetLikerListResp.java:38-84}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetTargetLikerListResp struct {
 *     List       []LikeItem `json:"list"`
 *     IsEnd      bool       `json:"is_end"`
 *     NextCursor int64      `json:"next_cursor"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/gettargetlikerlist}。
 *
 * <h3>⚠️ {@code list} 在 B 侧永远非 nil</h3>
 * <p>{@code gettargetlikerlistlogic.go:49} 是
 * {@code list := make([]types.LikeItem, 0, len(rpcResp.List))} —— 无条件 make，
 * 空结果也是 <b>[]</b> 而非 null。
 * ⇒ ⇒ 该保证落在<b>服务层</b>（构造响应时必给空 list），本 record 本身不强制。
 *
 * <p><b>⚠️ 字段顺序即 wire 顺序</b>：B 侧 Go struct 的字段声明序 = JSON 输出序
 * ⇒ record 分量顺序按 B 侧逐条对齐（{@code list} → {@code is_end} → {@code next_cursor}）。
 *
 * @param list       点赞人列表（<b>非 null</b>）
 * @param isEnd      是否到头
 * @param nextCursor 下一次查询的游标位置
 */
public record GetTargetLikerListResp(
        @JsonProperty("list") List<LikeItem> list,
        @JsonProperty("is_end") boolean isEnd,
        @JsonProperty("next_cursor") long nextCursor) {
}
