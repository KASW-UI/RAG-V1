package com.rag.message.dto.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：用户发私信请求 —— 对应 Go {@code types.go} 的 {@code UserSendChatReq}。
 *
 * <pre>
 * Go:
 *   type UserSendChatReq struct {
 *       RecipientId    int64  `json:"recipient_id,string,optional"`
 *       ConversationId int64  `json:"conversation_id,string,optional"`
 *       Content        string `json:"content"`
 *   }
 * </pre>
 *
 * <p><b>只有 {@code content} 必填</b>：两个 id 都是 {@code ,optional}。
 * 这<b>不是</b>「两个 id 可以同时为空」—— 空 id 的组合语义由 message-rpc 判定
 * （Go {@code rpc/internal/logic/sendchatmessagelogic.go}：
 * {@code conversation_id == 0 && recipient_id == 0} 报 INVALID_ARGUMENT，
 * 自发私信报 SELF_CHAT，首条消息超长报 FIRST_MSG_TOO_LONG 等）。
 * api 层<b>不</b>提前校验这三条，否则会与 rpc 的判定重复且可能不一致。
 *
 * <p>{@code content} 是 json 通道的 String：<b>空串是合法值</b>
 * （Go {@code unmarshaler.go} 的 {@code processFieldPrimitive} 对 String 直接写入，
 * 只判 null 不判空串）。内容长度/空内容是否拒绝由 message-rpc 判定。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧「{@code Object} 型 setter」→ <b>静态 {@code @JsonCreator} 工厂</b>；
 *       B 侧 {@code GoStyleStringInt64} → 共享层 {@code com.rag.common.web.StringInt64}（D3）；
 *       包名 {@code api.dto} → {@code dto.chat}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样，三条依据：
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *             ⇒ {@code @NotNull}（<b>不是 {@code @NotBlank}</b>）；两个 id <b>不加</b>任何约束；</li>
 *         <li><b>{@code ,string} 入参口径</b>：JSON 字符串与 JSON 整数都接受，浮点 / 布尔 / 超出
 *             int64 一律拒 ⇒ 由 {@code StringInt64.parse} 逐条承接（与 B 侧同一份判定口径）；</li>
 *         <li><b>字段名 / 字段顺序 / JSON 键序</b>：逐条照抄 B 侧。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/UserSendChatReq.java:34-73}
 *       （必填断言在 {@code :56-59}；两个 {@code ,string} setter 在 {@code :40-50}）</li>
 * </ol>
 *
 * @param recipientId    收件人 id（可选；JSON 字符串与整数都接受）
 * @param conversationId 会话 id（可选；JSON 字符串与整数都接受）
 * @param content        私信正文（必填；空串是合法值）
 */
public record UserSendChatReq(
        @JsonProperty("recipient_id") Long recipientId,
        @JsonProperty("conversation_id") Long conversationId,
        @NotNull @JsonProperty("content") String content) {

    /**
     * 唯一的反序列化入口 —— 承接 B 侧两个 {@code Object} 型 setter。
     *
     * <p><b>为什么入参声明成 {@code Object}</b>：本字段<b>同时接受 JSON 字符串和 JSON 数字</b>
     * ⇒ 只有「不声明具体目标类型、让 Jackson 原样递出」才能表达这两条都通；
     * 随后由 {@link StringInt64} 完全接管判定。
     *
     * <p><b>实测（jackson-databind 2.19.4 探针）</b>：静态 {@code @JsonCreator} 工厂与 record 的
     * 规范构造器<b>共存不冲突</b>；{@code Object} 入参绑定产出的类型与 B 侧判定口径逐条同形。
     *
     * @param rawRecipientId    {@code recipient_id} 的原始 JSON 值
     * @param rawConversationId {@code conversation_id} 的原始 JSON 值
     * @param content           {@code content}（原样透传）
     * @return 解析后的 DTO
     */
    @JsonCreator
    public static UserSendChatReq of(
            @JsonProperty("recipient_id") Object rawRecipientId,
            @JsonProperty("conversation_id") Object rawConversationId,
            @JsonProperty("content") String content) {
        return new UserSendChatReq(
                StringInt64.parse(rawRecipientId, "recipient_id"),
                StringInt64.parse(rawConversationId, "conversation_id"),
                content);
    }
}
