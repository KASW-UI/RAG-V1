package com.rag.like.dto.action;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>点赞动作响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/LikeActionResp.java:22-84}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type LikeActionResp struct {
 *     Success      bool   `json:"success"`
 *     Message      string `json:"message"`
 *     LikeCount    int64  `json:"like_count"`
 *     DislikeCount int64  `json:"dislike_count"`
 * }
 * </pre>
 *
 * <h3>⚠️⚠️ {@code success} 是「HTTP 成功」标志，恒为 true，绝不是业务结果标志</h3>
 * <p>B 出处 {@code likeactionlogic.go:70-75}：
 * <pre>
 * return &amp;types.LikeActionResp{
 *     Success:      true,                 ← 硬编码字面量
 *     Message:      rpcResp.Message,
 *     LikeCount:    rpcResp.LikeCount,
 *     DislikeCount: rpcResp.DislikeCount,
 * }, nil
 * </pre>
 * <p>注意 B 侧<b>没有</b>读 proto 里的 {@code success} 字段（那个 bool 字段被完全忽略），
 * 只透传 {@code Message} / 两个计数。
 *
 * <p><b>语义反转是本项目已经栽过的坑</b>：users 域曾把同形的 {@code success} 当成
 * 「点赞是否真的生效」的业务结果，导致失败路径被写成 {@code success:false} + HTTP 200，
 * 客户端按 {@code code == 200 && success} 判成功时会把失败当成功。
 * ⇒ 这里<b>保持 B 侧的硬编码 true</b>；业务成败的判据是 envelope 的 {@code code}。
 *
 * <p><b>推论</b>：点赞「执行成功但业务上被拒（如防刷 / 热点降级）」时，
 * B 侧返回的仍是 {@code success:true}，失败信息在 {@code message} 里。
 *
 * <p><b>⚠️ 访问器命名</b>：record 的分量访问器是 {@code success()}（无 {@code get} 前缀）
 * ⇒ 与 B 侧的 {@code Success} 字段<b>恰好一致</b>；且 record 没有 setter ⇒ 彻底切断了
 * B 侧那条「可变 POJO」的路（A9）。
 *
 * @param success      恒为 {@code true}（B 侧硬编码）
 * @param message      相关信息（成功为 {@code Success}，失败为失败信息）
 * @param likeCount    操作后的点赞数
 * @param dislikeCount 操作后的点踩数
 */
public record LikeActionResp(
        @JsonProperty("success") boolean success,
        @JsonProperty("message") String message,
        @JsonProperty("like_count") long likeCount,
        @JsonProperty("dislike_count") long dislikeCount) {
}
