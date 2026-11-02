package com.rag.message.dto.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：管理员发私信请求 —— 对应 Go {@code types.go} 的 {@code AdminSendChatReq}。
 *
 * <pre>
 * Go:
 *   type AdminSendChatReq struct {
 *       RecipientId    int64  `json:"recipient_id,string"`              // 必填
 *       ConversationId int64  `json:"conversation_id,string,optional"`
 *       Content        string `json:"content"`                          // 必填
 *   }
 * </pre>
 *
 * <p><b>{@code recipient_id} 必填（与 user 侧的可选形成对照）</b>：
 * 管理员必须显式指定收件人，Go 侧 {@code ,string} 无 {@code ,optional}
 * ⇒ 缺席即报必填 → 本服务折叠为 {@code {code:5001}}。
 * 这是本 DTO 与 {@link UserSendChatReq} 唯一的结构性差别，<b>不要</b>统一成同一套。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧「{@code Object} 型 setter」→ <b>静态 {@code @JsonCreator} 工厂</b>
 *       （record 没有 setter ⇒ 工厂方法是「唯一反序列化入口」的等价落点）；
 *       B 侧 {@code GoStyleStringInt64} → 共享层 {@code com.rag.common.web.StringInt64}（D3）；
 *       包名 {@code api.dto} → {@code dto.chat}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样，三条依据：
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（不判空串）
 *             ⇒ 用 {@code @NotNull}（<b>不是 {@code @NotBlank}</b>，否则多拒「空串」这一类合法输入）；</li>
 *         <li><b>{@code ,string} 入参口径</b>：B 侧 {@code GoStyleStringInt64.parse} 接受
 *             <b>JSON 字符串与 JSON 整数两者</b>，拒绝浮点 / 布尔 / 超出 int64 ⇒ C 侧逐条交给
 *             {@code StringInt64.parse}（同一份判定口径，逐条照抄）；
 *             ⚠️ <b>不能</b>改成「组件直接声明 {@code Long} 让 Jackson 自己强转」——
 *             Jackson 默认的字符串→整型强转会接受带空白的前缀（{@code " 12"}）与空串
 *             （{@code ""} → null），与 B 侧 {@code strconv.ParseInt} 的判定<b>不同</b>；</li>
 *         <li><b>字段名 / 字段顺序 / JSON 键序</b>：逐条照抄 B 侧（{@code recipient_id} /
 *             {@code conversation_id} / {@code content}）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/AdminSendChatReq.java:33-73}
 *       （必填断言在 {@code :55-59}；两个 {@code ,string} setter 在 {@code :39-49}）</li>
 * </ol>
 *
 * <p><b>⚠️ {@code recipient_role} 在 api 层被硬编码为 USER</b>（B 侧
 * {@code sendadminchatmessagelogic.go} 同样写死 {@code RecipientRole: pb.SenderRole_USER}）⇒
 * <b>不要</b>把它暴露成请求字段。
 *
 * @param recipientId    收件人 id（必填；JSON 字符串与整数都接受）
 * @param conversationId 会话 id（可选；JSON 字符串与整数都接受）
 * @param content        私信正文（必填；空串是合法值，长度判定在 message-rpc 侧）
 */
public record AdminSendChatReq(
        @NotNull @JsonProperty("recipient_id") Long recipientId,
        @JsonProperty("conversation_id") Long conversationId,
        @NotNull @JsonProperty("content") String content) {

    /**
     * 唯一的反序列化入口 —— 承接 B 侧两个 {@code Object} 型 setter。
     *
     * <p><b>为什么入参声明成 {@code Object}</b>：本字段<b>同时接受 JSON 字符串和 JSON 数字</b>
     * （B 侧 {@code ,string} 的「入参宽松」语义）⇒ 只有「不声明具体目标类型、让 Jackson 原样递出」
     * 才能表达这两条都通；随后由 {@link StringInt64} 完全接管判定。
     *
     * <p><b>实测（jackson-databind 2.19.4 探针）</b>：静态 {@code @JsonCreator} 工厂与 record 的
     * 规范构造器<b>共存不冲突</b>；{@code Object} 入参绑定产出
     * {@code Integer / Long / BigInteger / String / Double / Boolean / null}
     * ⇒ {@code StringInt64.parse} 的判定与 B 侧 {@code GoStyleStringInt64.parse} 逐条同形。
     *
     * @param rawRecipientId    {@code recipient_id} 的原始 JSON 值
     * @param rawConversationId {@code conversation_id} 的原始 JSON 值
     * @param content           {@code content}（原样透传）
     * @return 解析后的 DTO
     */
    @JsonCreator
    public static AdminSendChatReq of(
            @JsonProperty("recipient_id") Object rawRecipientId,
            @JsonProperty("conversation_id") Object rawConversationId,
            @JsonProperty("content") String content) {
        return new AdminSendChatReq(
                StringInt64.parse(rawRecipientId, "recipient_id"),
                StringInt64.parse(rawConversationId, "conversation_id"),
                content);
    }
}
