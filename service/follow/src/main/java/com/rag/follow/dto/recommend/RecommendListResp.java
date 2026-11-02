package com.rag.follow.dto.recommend;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * <p><b>推荐列表响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/dto/RecommendListResp.java}
 * （可变 class，字段初值为空 list）。B 侧原始形态：
 * <pre>
 * type RecommendListResp struct {
 *     Users []RecommendUser `json:"users"`
 * }
 * </pre>
 *
 * <p><b>唯一使用它的端点</b>：{@code /follow/v1/recommendations}。
 *
 * <h3>⚠️ 空列表输出 {@code []} 而不是 {@code null} —— 这里 B 侧是<b>无条件</b>保证</h3>
 * <p>与 {@code FollowUserListResp} 不同，本类的空列表形态<b>不依赖 rpc 的返回</b>，
 * 而是被 api 层自己的代码<b>写死</b>了：
 * <pre>
 *   users := make([]types.RecommendUser, 0, len(rpcResp.Users))
 *   for _, user := range rpcResp.Users {
 *       if user == nil { continue }
 *       users = append(users, types.RecommendUser{...})
 *   }
 *   return &amp;types.RecommendListResp{Users: users}, nil
 * </pre>
 * {@code make(..., 0, n)} <b>永远是非 nil 的空切片</b> ⇒ 输出恒为 {@code "users":[]}。
 * <p>⇒ 本类的 {@code users} 分量<b>恒为非 null</b>（服务层传空 list 而不是 null）
 * ⇒ 输出恒为 {@code "users":[]} ✔。
 * ⇒ ⚠️ <b>改成 null 会在「没有推荐」这一<b>高频</b>场景上输出 B 侧从不输出的
 * {@code "users":null}</b>。
 *
 * <p><b>⚠️ 与 {@code FollowUserListResp} 的不对称</b>：那边的空结果是 {@code null}
 * （因为中间隔着一次 protobuf 编解码，wire 上字段消失），这边是 {@code []}
 * （因为是 api 进程内新建的切片）⇒ <b>两个 DTO 的空列表形态相反</b>，别「顺手统一」。
 *
 * <p><b>⚠️ 键序</b>：本类只有一个键 ⇒ 键序无歧义（显式写出以固化契约）。
 *
 * @param users 推荐项列表（<b>恒非 null</b>；空结果输出 {@code "users":[]}）
 */
@JsonPropertyOrder({"users"})
public record RecommendListResp(
        @JsonProperty("users") List<RecommendUser> users
) {
}
