package com.rag.like.dto.list;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>某稿件的点赞人列表查询请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetTargetLikerListReq.java:34-84}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type GetTargetLikerListReq struct {
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     Cursor     int64  `json:"cursor"`
 *     PageSize   int32  `json:"page_size"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/gettargetlikerlist}（<b>无 uid</b> —— 公开读）。
 *
 * <h3>⚠️ 本 DTO 的 4 个字段全是标量 ⇒ 4 个全部必填</h3>
 * <p>包括客户端极易遗忘的 {@code cursor} 与 {@code page_size}。
 * B 侧走的是 {@code processNamedFieldWithoutValue} 的 {@code default:} 分支
 * （标量 + 非 optional ⇒ {@code newInitError} ⇒ {@code field "x" is not set}）。
 *
 * <p>⇒ 客户端<b>必须</b>显式发 {@code cursor} / {@code page_size}，
 * 连首页的 {@code cursor:0} / {@code page_size:20} 也必须发。
 * 少发任一个，B 侧与 C 侧都返回<b>解析失败</b>（⇒ 200 + {code:5001}）。
 *
 * <h3>⚠️ 用包装类型而不是基本类型</h3>
 * <p>{@code Long} / {@code Integer} 能区分「缺席（null）」与「显式传 0」—— 这是上面那条规则能成立的前提。
 * 若用 {@code long} / {@code int}，缺席时是 0、无法区分，必填校验会静默失效
 * （AGENTS.md §3.3「把没看过的东西变成看过了」的同款形态）。
 *
 * <p><b>「必填」= 键必须出现，不是值不能为零</b>：B 侧
 * {@code {"cursor":0}} 是合法的（必填校验只看键在不在）⇒
 * ⇒ C 侧用 {@code @NotNull}（<b>不是</b> {@code @Positive} / {@code @Min}）。
 *
 * @param targetType 目标对象类型（必填）
 * @param targetId   目标对象 id（必填）
 * @param cursor     上一页最后一条记录的时间戳游标（必填；可为 0）
 * @param pageSize   每页大小（必填；可非正 ⇒ 领域层归一化成 10）
 */
public record GetTargetLikerListReq(
        @NotNull String targetType,
        @NotNull String targetId,
        @NotNull Long cursor,
        @NotNull Integer pageSize) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 显式转发。
     *
     * <p>⚠️ <b>{@code cursor} / {@code page_size} 的入参声明成 {@link Long} / {@link Integer}</b>：
     * B 侧这两个字段<b>没有</b> {@code ,string} ⇒ JSON 字符串是「type mismatch」（B 侧拒绝）。
     * ⇒ C 侧交给本域的 Jackson 强转补强（{@code LogicalType.Integer + String → Fail}）拒绝字符串。
     *
     * <p>⚠️ <b>为什么不用 {@code Object} 入参</b>：Jackson 对 {@code Object} 目标类型
     * <b>不做任何强转</b>（探针实测：{@code {"cursor":"10"}} 会<b>静默</b>绑成 {@code 10}）
     * ⇒ 那正是 B 侧明确拒绝的输入 ⇒ 是实打实的行为漂移。
     *
     * <p>⚠️ <b>为什么不用 {@code Long.parseLong(String.valueOf(raw))}</b>：
     * 那会让字符串 {@code "10"} 被<b>当成合法整数解析出来</b> ⇒ 同样是漂移。
     *
     * <p>⇒ 声明成 {@link Long} / {@link Integer} 时，Jackson 走<b>目标类型强转路径</b>
     * ⇒ 共享层 {@code Coercion} 的 {@code ACCEPT_FLOAT_AS_INT=false}（浮点不进整数字段）
     * ＋ 本域补强（{@code Integer ← String = Fail}）<b>逐条生效</b>
     * （探针实测：{@code "10"} / {@code 1.5} / {@code 1.0} / {@code true} / {@code [1]} 全部被拒）。
     *
     * @param rawTargetType {@code target_type} 原始值
     * @param rawTargetId   {@code target_id} 原始值
     * @param rawCursor     {@code cursor} 原始值（<b>整数</b>；字符串/浮点已被强转补强拒）
     * @param rawPageSize   {@code page_size} 原始值（<b>整数</b>；字符串/浮点已被强转补强拒）
     */
    @JsonCreator
    public static GetTargetLikerListReq fromJson(@JsonProperty("target_type") String rawTargetType,
                                                 @JsonProperty("target_id") String rawTargetId,
                                                 @JsonProperty("cursor") Long rawCursor,
                                                 @JsonProperty("page_size") Integer rawPageSize) {
        return new GetTargetLikerListReq(rawTargetType, rawTargetId, rawCursor, rawPageSize);
    }
}
