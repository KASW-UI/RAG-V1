package com.rag.message.dto.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：会话列表项 —— 对应 Go {@code types.go} 的 {@code ConversationItem}。
 *
 * <pre>
 * Go:
 *   type ConversationItem struct {
 *       ConversationId, PeerId int64 `json:",string"`   // 两个是字符串
 *       PeerRole     int32  `json:"peer_role"`
 *       PeerName     string `json:"peer_name"`
 *       PeerEmail    string `json:"peer_email"`
 *       Status       int32  `json:"status"`             // proto 的 ConversationStatus → 数字
 *       UnreadCount  int64  `json:"unread_count"`       // 数字
 *       LatestMessage     string `json:"latest_message"`
 *       LatestMessageAt   int64  `json:"latest_message_at"`   // 数字
 *       UpdatedAt         int64  `json:"updated_at"`          // 数字
 *       CanSend     bool   `json:"can_send"`
 *   }
 * </pre>
 *
 * <p>⚠️ 三个时间/计数字段都是 <b>JSON 数字</b>（Go 内部是 Unix 秒），
 * 只有两个 id 是字符串 —— 同一条记录里混用两种表示是 Go 的既有形态。
 *
 * <p><b>{@code peer_name} / {@code peer_email} 可能为空串</b>：
 * 对方是 admin 时 email 可能没有。Go 侧不做任何占位替换，Java 侧也不加。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.conversation}（D7 + D11）。
 *       字段集合、<b>顺序</b>、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的一处机制搬运</b>：B 侧靠三个字段初始化（{@code peerName = ""} /
 *       {@code peerEmail = ""} / {@code latestMessage = ""}）保证零值占位路径输出 {@code ""}
 *       而不是 null ⇒ record 无字段初始化 ⇒ 该不变式落到<b>规范构造器</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两个 id 仍是 {@code @JsonFormat(shape = STRING)}
 *       （JSON <b>字符串</b>），{@code unread_count} / 两个时间戳仍是 JSON <b>数字</b>
 *       （<b>不要</b>顺手统一成字符串）；字段顺序 = B 侧字段声明顺序 ⇒ JSON 键序同形。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/ConversationItem.java:34-170}
 *       （字段声明在 {@code :36-69}）</li>
 * </ol>
 *
 * @param conversationId  会话 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param peerId          对端 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param peerRole        对端角色（proto 枚举 → 数字）
 * @param peerName        对端昵称（零值占位时输出 {@code ""}，不是 null）
 * @param peerEmail       对端邮箱（零值占位时输出 {@code ""}，不是 null；admin 可能为空串）
 * @param status          会话状态（proto 的 ConversationStatus → 数字）
 * @param unreadCount     未读数（JSON 数字）
 * @param latestMessage   最近一条消息预览（零值占位时输出 {@code ""}，不是 null）
 * @param latestMessageAt 最近一条消息时间（Unix 秒，JSON 数字）
 * @param updatedAt       会话更新时间（Unix 秒，JSON 数字）
 * @param canSend         当前是否可发
 */
public record ConversationItem(
        @JsonProperty("conversation_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long conversationId,
        @JsonProperty("peer_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long peerId,
        @JsonProperty("peer_role") int peerRole,
        @JsonProperty("peer_name") String peerName,
        @JsonProperty("peer_email") String peerEmail,
        @JsonProperty("status") int status,
        @JsonProperty("unread_count") long unreadCount,
        @JsonProperty("latest_message") String latestMessage,
        @JsonProperty("latest_message_at") long latestMessageAt,
        @JsonProperty("updated_at") long updatedAt,
        @JsonProperty("can_send") boolean canSend) {

    /**
     * 规范构造器 —— 承接 B 侧三个 {@code = ""} 的字段初始化。
     *
     * <p>B 侧注释写明「Go 零值 struct 里 string 字段恒为 ""（不是 null），
     * 零值占位路径（proto message 缺失）依赖它输出 "" 而不是 null」
     * ⇒ record 无字段初始化 ⇒ 把同一条不变式搬到构造器里。
     */
    public ConversationItem {
        peerName = peerName == null ? "" : peerName;
        peerEmail = peerEmail == null ? "" : peerEmail;
        latestMessage = latestMessage == null ? "" : latestMessage;
    }
}
