package com.rag.follow.dto.list;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * <p><b>用户 id 列表响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/dto/FollowUserListResp.java}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type FollowUserListResp struct {
 *     UserIds []int64 `json:"user_ids"`      // ← 注意：没有 ,string
 * }
 * </pre>
 *
 * <p><b>使用它的 3 个端点</b>：{@code /follow/v1/follow/list}、{@code /follow/v1/follower/list}、
 * {@code /follow/v1/block/list}。
 *
 * <h3>⚠️ 出参是 <b>JSON 数字</b>，不是字符串</h3>
 * <p>{@code UserIds} 的 tag <b>没有 {@code ,string}</b> ⇒ B 侧按 {@code []int64} 正常序列化
 * ⇒ 输出 {@code "user_ids":[1,2,3]}，<b>不是</b> {@code ["1","2","3"]}。
 * 这与同域 {@code RecommendUser#target_id}（<b>带</b> {@code ,string}，出参是字符串）
 * 构成一组<b>必须记住的不对称</b>。
 *
 * <h3>⚠️ 空列表输出 {@code null}（不是 {@code []}）</h3>
 * <p>B 侧 api 的三个 list logic 都是「原样透传 rpc 返回的切片」，看似是透传。
 * 关键在<b>中间隔着一次 protobuf 序列化</b>：
 * <pre>
 *   follow-rpc 进程内：UserListResp{UserIds: make([]int64, 0, 0)}   ← 非 nil 空切片
 *        │  protobuf wire（proto3 repeated 空 ⇒ 字段整体缺失）
 *        ▼
 *   follow-api 进程内：rpcResp.UserIds == nil                        ← B 侧反序列化得 nil
 *        │  encoding/json（无 omitempty）
 *        ▼
 *   HTTP body：{"user_ids":null}
 * </pre>
 * ⇒ <b>B 侧输出的是 {@code null}</b>，不是 {@code []}。
 * ⚠️ 「rpc 侧是非 nil 空切片 ⇒ 输出 []」是<b>错</b>的：那是 rpc <b>进程内</b>的值，
 * 中间隔着一次 protobuf 编解码，wire 上字段已经消失。
 * ⇒ 本类用 {@link JsonInclude.Include#NON_NULL} ⇒ 分量为 null 时<b>键也消失</b>，
 * 而 B 侧是<b>键在、值为 null</b> ⇒ ⚠️ <b>两者不是同一形状</b>。
 *
 * <p><b>⚠️ 结论：这里必须输出 {@code "user_ids":null}（键在、值为 null）</b>
 * ⇒ ⇒ <b>不能</b>用 {@code @JsonInclude(NON_NULL)}（那会连键一起删掉）⇒
 * ⇒ ⇒ 本 record <b>刻意不加</b> {@code @JsonInclude} ⇒ Jackson 默认输出
 * {@code "user_ids":null} ⇒ 与 B 侧<b>逐字节一致</b> ✔。
 * ⇒ 服务层在「rpc 返回空列表」时必须显式传 {@code null}（不能传空 list）——
 * 这一点由 {@code FollowApiSupport#toUserListResp} 承担。
 *
 * <p><b>⚠️ 与 {@code RecommendListResp} 的不对称</b>：推荐列表走的是
 * 「在 api 进程内重新 make 的空切片」⇒ 恒为非 nil ⇒ 输出 {@code []}。
 * ⇒ 两个 DTO 的空列表形态<b>相反</b>，别「顺手统一」。
 *
 * <p><b>⚠️ 键序</b>：本类只有一个键 ⇒ 键序无歧义（{@link JsonPropertyOrder} 非必需，
 * 但显式写出以固化契约）。
 *
 * @param userIds 用户 id 列表（JSON <b>数字</b>数组）；空结果时为 {@code null}（B 侧输出 {@code "user_ids":null}）
 */
@JsonPropertyOrder({"user_ids"})
public record FollowUserListResp(
        @JsonProperty("user_ids") List<Long> userIds
) {
}
