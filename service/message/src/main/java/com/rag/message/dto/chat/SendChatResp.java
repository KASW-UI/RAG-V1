package com.rag.message.dto.chat;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.message.dto.conversation.ChatMessageItem;

/**
 * <p><b>职责</b>：发私信响应 —— 对应 Go {@code types.go} 的 {@code SendChatResp}。
 *
 * <pre>
 * Go:
 *   type SendChatResp struct {
 *       ConversationId int64           `json:"conversation_id,string"`
 *       Message        ChatMessageItem `json:"message"`
 *       Status         int32           `json:"status"`
 *       CanSend        bool            `json:"can_send"`
 *   }
 * </pre>
 *
 * <p><b>{@code message} 永远是对象</b>：Go 的 {@code shared.ToSendChatResp} 在
 * {@code in.Message == nil} 时用零值 struct 占位，
 * 所以客户端永远可以 {@code resp.message.content} 而不必判空。
 * Java 侧在映射时保持：proto message 为 null 时给一个全零 DTO，<b>不返回 null</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.chat}（D7 + D11）。
 *       字段集合、顺序、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的结构性差异</b>：B 侧靠字段初始化 {@code message = new ChatMessageItem()}
 *       保证「永不返回 null」，record 没有字段初始化 ⇒ 该不变式落到
 *       <b>规范构造器</b>（{@code message == null} ⇒ 换成全零 {@link ChatMessageItem#zero()}）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— {@code conversation_id} 仍是
 *       {@code @JsonFormat(shape = STRING)}（输出 JSON <b>字符串</b>），
 *       {@code message} 在任何路径下都序列化成对象（B 侧零值 struct 的等价物），
 *       字段顺序与 JSON 键序逐条照抄。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/SendChatResp.java:30-77}
 *       （字段声明在 {@code :32-41}）</li>
 * </ol>
 *
 * @param conversationId 会话 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param message        私信内容（<b>永不返回 null</b>；缺失时是全零对象）
 * @param status         会话状态（由 message-rpc 判定，api 层只透传）
 * @param canSend        当前是否可发（由 message-rpc 判定）
 */
public record SendChatResp(
        @JsonProperty("conversation_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long conversationId,
        @JsonProperty("message") ChatMessageItem message,
        @JsonProperty("status") int status,
        @JsonProperty("can_send") boolean canSend) {

    /**
     * 规范构造器 —— 承接 B 侧 {@code private ChatMessageItem message = new ChatMessageItem();}。
     *
     * <p>B 侧靠字段初始化保证 {@code message} 非 null；record 无字段初始化 ⇒ 把同一条不变式
     * 搬到构造器里（等价落点，不是新规则）。
     */
    public SendChatResp {
        message = message == null ? ChatMessageItem.zero() : message;
    }
}
