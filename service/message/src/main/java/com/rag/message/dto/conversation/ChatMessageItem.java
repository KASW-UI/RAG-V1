package com.rag.message.dto.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：单条私信 —— 对应 Go {@code types.go} 的 {@code ChatMessageItem}。
 *
 * <pre>
 * Go:
 *   type ChatMessageItem struct {
 *       Id, ConversationId, SenderId, RecipientId int64 `json:",string"`   // 四个都是字符串
 *       SenderRole    int32  `json:"sender_role"`
 *       RecipientRole int32  `json:"recipient_role"`
 *       Content       string `json:"content"`
 *       IsRead        bool   `json:"is_read"`
 *       CreatedAt     int64  `json:"created_at"`                          // 数字
 *   }
 * </pre>
 *
 * <p><b>零值语义</b>：Go 的 {@code shared.ToSendChatResp} 在 {@code in.Message == nil} 时
 * 用<b>全零值</b>的 {@code types.ChatMessageItem{}} 填 {@code message}，
 * 所以 {@code message} 永远是对象（最坏情况是
 * {@code {"id":"0","conversation_id":"0","sender_id":"0","sender_role":0,...}}），
 * <b>不会是 null</b>。Java 侧在 {@code MessageApiSupport} 里保持同样处理。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.conversation}（D7 + D11）。
 *       字段集合、<b>顺序</b>、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的一处机制搬运</b>：B 侧靠字段初始化 {@code content = ""} 保证
 *       「零值占位路径输出 {@code ""} 而不是 null」⇒ record 无字段初始化 ⇒ 该不变式落到
 *       <b>规范构造器</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 四个 id 仍是 {@code @JsonFormat(shape = STRING)}
 *       （输出 JSON <b>字符串</b>），{@code created_at} 仍是 JSON <b>数字</b>，
 *       {@code is_read} 的键名照抄 B 侧（不是 {@code read}）；
 *       组件顺序 = B 侧字段声明顺序 ⇒ JSON 键序逐条同形。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/ChatMessageItem.java:30-142}
 *       （字段声明在 {@code :32-58}）</li>
 * </ol>
 *
 * @param id            消息 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param conversationId 会话 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param senderId      发送者 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param senderRole    发送者角色（proto 枚举 → 数字；B 侧读 {@code getSenderRoleValue()}）
 * @param recipientId   接收者 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param recipientRole 接收者角色（proto 枚举 → 数字）
 * @param content       正文（零值占位时输出 {@code ""}，不是 null）
 * @param isRead        是否已读（JSON 键 = {@code is_read}，不是 {@code read}）
 * @param createdAt     创建时间（Unix 秒，JSON <b>数字</b>）
 */
public record ChatMessageItem(
        @JsonProperty("id") @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        @JsonProperty("conversation_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long conversationId,
        @JsonProperty("sender_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long senderId,
        @JsonProperty("sender_role") int senderRole,
        @JsonProperty("recipient_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long recipientId,
        @JsonProperty("recipient_role") int recipientRole,
        @JsonProperty("content") String content,
        @JsonProperty("is_read") boolean isRead,
        @JsonProperty("created_at") long createdAt) {

    /**
     * 规范构造器 —— 承接 B 侧 {@code private String content = "";}。
     *
     * <p>B 侧注释写明「Go 零值 struct 里 string 字段恒为 ""（不是 null），
     * 零值占位路径（proto message 缺失）依赖它输出 "" 而不是 null」
     * ⇒ record 无字段初始化 ⇒ 把同一条不变式搬到构造器里。
     */
    public ChatMessageItem {
        content = content == null ? "" : content;
    }

    /**
     * 零值对象 —— B 侧 {@code new ChatMessageItem()} 的等价物。
     *
     * <p>用于「proto message 缺失 ⇒ 填全零 DTO 而不返回 null」的占位路径
     * （B 侧 {@code shared.ToSendChatResp} 的零值 struct 语义）。
     *
     * @return 全零的私信项
     */
    public static ChatMessageItem zero() {
        return new ChatMessageItem(0L, 0L, 0L, 0, 0L, 0, "", false, 0L);
    }
}
