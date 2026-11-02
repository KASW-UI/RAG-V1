package com.rag.message.dto.read;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：未读汇总响应 —— 对应 Go {@code types.go} 的 {@code UnreadSummaryResp}。
 *
 * <pre>
 * Go:
 *   type UnreadSummaryResp struct {
 *       NotificationUnread int64 `json:"notification_unread"`   // ← 注意：无 ,string
 *       ConversationUnread int64 `json:"conversation_unread"`
 *       TotalUnread        int64 `json:"total_unread"`
 *   }
 * </pre>
 *
 * <p><b>⚠️ 本响应的三个字段都是 JSON <b>数字</b>，不是字符串。</b>
 * Go 只有写了 {@code ,string} 的字段才序列化成字符串；这三个 tag 里没有，
 * 于是 {@code encoding/json} 直接写数字。同一个服务里
 * {@code ConversationItem.ConversationId} 却是 {@code ,string}（字符串）——
 * <b>混用是 B 侧的既有形态</b>，不要「顺手统一」。
 *
 * <p>Java 侧因此<b>不能</b>给这三个字段加 {@code @JsonFormat(shape = STRING)}，
 * 与 {@code ConversationItem} 的处理正好相反。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.read}（D7 + D11）。
 *       字段集合、顺序、类型、注解逐条照抄 B 侧 —— <b>不加</b> {@code @JsonFormat(STRING)}。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 三个字段仍是 JSON <b>数字</b>，
 *       字段顺序与 JSON 键序逐条照抄 B 侧。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/UnreadSummaryResp.java:28-64}
 *       （字段声明在 {@code :30-35}）</li>
 * </ol>
 *
 * @param notificationUnread 未读通知数（JSON <b>数字</b>，不是字符串）
 * @param conversationUnread 未读会话数（JSON <b>数字</b>，不是字符串）
 * @param totalUnread        未读总数（JSON <b>数字</b>，不是字符串）
 */
public record UnreadSummaryResp(
        @JsonProperty("notification_unread") long notificationUnread,
        @JsonProperty("conversation_unread") long conversationUnread,
        @JsonProperty("total_unread") long totalUnread) {
}
