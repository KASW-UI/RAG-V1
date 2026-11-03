package com.rag.like.dto.list;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>某用户点赞列表查询请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetUserLikeListReq.java:30-66}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type GetUserLikeListReq struct {
 *     TargetType string `json:"target_type"`
 *     Cursor     int64  `json:"cursor"`
 *     PageSize   int32  `json:"page_size"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getuserlikelist}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 3 个字段全是标量 ⇒ <b>全部必填</b></h3>
 * <p>理由与包装类型的必要性同 {@link GetTargetLikerListReq}
 * （B 侧 {@code processNamedFieldWithoutValue} 的 {@code default:} 分支）。
 *
 * <h3>⚠️ 本 DTO <b>没有 userId 字段</b></h3>
 * <p>uid 来自 JWT claim，由 {@code service/LikeApiSupport#userId(HttpServletRequest)}
 * 从 request attribute 取，取不到返回 <b>1006</b>（B 侧 {@code getuserlikelistlogic.go:37-49}）。
 * ⇒ 那是<b>业务层</b>的判定，<b>不</b>是 DTO 的必填校验 —— 两者不要混。
 *
 * @param targetType 目标对象类型（必填）
 * @param cursor     上一页最后一条记录的时间戳游标（必填；可为 0）
 * @param pageSize   每页大小（必填；可非正 ⇒ 领域层归一化成 10）
 */
public record GetUserLikeListReq(
        @NotNull String targetType,
        @NotNull Long cursor,
        @NotNull Integer pageSize) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 显式转发。
     *
     * <p>⚠️ <b>{@code cursor} / {@code page_size} 的入参声明成 {@link Long} / {@link Integer}</b>：
     * B 侧这两个字段<b>没有</b> {@code ,string} ⇒ JSON 字符串是「type mismatch」（B 侧拒绝）
     * ⇒ 由本域的 Jackson 强转补强拒绝（判据与探针实测见 {@link GetTargetLikerListReq}）。
     *
     * @param rawTargetType {@code target_type} 原始值
     * @param rawCursor     {@code cursor} 原始值（<b>整数</b>；字符串/浮点已被强转补强拒）
     * @param rawPageSize   {@code page_size} 原始值（<b>整数</b>；字符串/浮点已被强转补强拒）
     */
    @JsonCreator
    public static GetUserLikeListReq fromJson(@JsonProperty("target_type") String rawTargetType,
                                               @JsonProperty("cursor") Long rawCursor,
                                               @JsonProperty("page_size") Integer rawPageSize) {
        return new GetUserLikeListReq(rawTargetType, rawCursor, rawPageSize);
    }
}
