package com.rag.message.dto.read;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：标记会话已读请求 —— 对应 Go {@code types.go} 的 {@code MarkConversationReadReq}。
 *
 * <pre>
 * Go:
 *   type MarkConversationReadReq struct {
 *       ConversationId int64 `json:"conversation_id,string"`   // 必填 + ,string
 *   }
 * </pre>
 *
 * <p>{@code ,string} 与必填的语义见 {@code ConversationMessagesReq} 的类注释（同一套机制）。
 *
 * <p>使用方：{@code MarkConversationRead}（user，operator_role=USER）与
 * {@code MarkAdminConversationRead}（admin，operator_role=ADMIN）。
 * 两个端点共用本 DTO —— B 侧也是同一个 {@code MarkConversationReadReq} 被两个 handler 用。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧「{@code Object} 型 setter」→ <b>静态 {@code @JsonCreator} 工厂</b>；
 *       B 侧 {@code GoStyleStringInt64} → 共享层 {@code com.rag.common.web.StringInt64}（D3）；
 *       包名 {@code api.dto} → {@code dto.read}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 必填口径 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ {@code @NotNull}（<b>不是 {@code @NotBlank}</b>）；
 *       {@code ,string} 入参「字符串与整数都接受、浮点 / 布尔 / 超界一律拒」由
 *       {@code StringInt64.parse} 逐条承接；JSON 键名 {@code conversation_id} 照抄。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/MarkConversationReadReq.java:25-44}
 *       （必填断言在 {@code :35-38}；{@code ,string} setter 在 {@code :29-33}）</li>
 * </ol>
 *
 * @param conversationId 会话 id（必填；JSON 字符串与整数都接受）
 */
public record MarkConversationReadReq(
        @NotNull @JsonProperty("conversation_id") Long conversationId) {

    /**
     * 唯一的反序列化入口 —— 承接 B 侧的 {@code Object} 型 setter。
     *
     * <p><b>为什么入参声明成 {@code Object}</b>：本字段带 {@code ,string}
     * ⇒ <b>同时接受 JSON 字符串和 JSON 数字</b> ⇒ 只有「不声明具体目标类型、让 Jackson 原样递出」
     * 才能表达这两条都通；随后由 {@link StringInt64} 完全接管判定。
     *
     * @param rawConversationId {@code conversation_id} 的原始 JSON 值
     * @return 解析后的 DTO
     */
    @JsonCreator
    public static MarkConversationReadReq of(
            @JsonProperty("conversation_id") Object rawConversationId) {
        return new MarkConversationReadReq(StringInt64.parse(rawConversationId, "conversation_id"));
    }
}
