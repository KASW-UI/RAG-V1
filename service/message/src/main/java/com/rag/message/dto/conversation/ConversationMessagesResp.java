package com.rag.message.dto.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>职责</b>：会话消息列表响应 —— 对应 Go {@code types.go} 的 {@code ConversationMessagesResp}。
 *
 * <pre>
 * Go:
 *   type ConversationMessagesResp struct {
 *       Items             []ChatMessageItem `json:"items"`
 *       Status            int32             `json:"status"`
 *       CanSend           bool              `json:"can_send"`
 *       PendingSenderId   int64             `json:"pending_sender_id,string"`   // 字符串
 *       PendingSenderRole int32             `json:"pending_sender_role"`
 *   }
 * </pre>
 *
 * <p><b>会话状态机（{@code status}）</b>由 message-rpc 判定，api 层只透传：
 * <pre>
 *   PENDING(1)  首条消息已发、等对方回复 —— can_send=false, pending_sender_id=对方
 *   OPEN(2)     双向可发 —— can_send=true
 * </pre>
 * 「只有管理员能发起」等规则（{@code ErrorAdminOnlyInit}=5205）在 rpc 侧，
 * api 层<b>不</b>预判，否则会出现同一规则两处实现。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.conversation}（D7 + D11）。
 *       字段集合、顺序、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的一处机制搬运</b>：B 侧靠字段初始化 {@code items = new ArrayList<>()}
 *       保证「无数据时输出 {@code []} 而不是 null」⇒ record 无字段初始化 ⇒ 该不变式落到
 *       <b>规范构造器</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— {@code pending_sender_id} 仍是
 *       {@code @JsonFormat(shape = STRING)}（JSON <b>字符串</b>），
 *       {@code status} / {@code pending_sender_role} 仍是 JSON <b>数字</b>，
 *       {@code items} 永远序列化成数组。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/ConversationMessagesResp.java:34-96}
 *       （字段声明在 {@code :36-46}）</li>
 * </ol>
 *
 * @param items             消息列表（无数据时是空数组，不是 null）
 * @param status            会话状态（PENDING=1 / OPEN=2，由 message-rpc 判定）
 * @param canSend           当前是否可发
 * @param pendingSenderId   PENDING 态下的「等待方」id（<b>序列化为字符串</b>；非 PENDING 时为 0）
 * @param pendingSenderRole PENDING 态下的「等待方」角色（proto 枚举 → 数字）
 */
public record ConversationMessagesResp(
        @JsonProperty("items") List<ChatMessageItem> items,
        @JsonProperty("status") int status,
        @JsonProperty("can_send") boolean canSend,
        @JsonProperty("pending_sender_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long pendingSenderId,
        @JsonProperty("pending_sender_role") int pendingSenderRole) {

    /**
     * 规范构造器 —— 承接 B 侧 {@code private List<ChatMessageItem> items = new ArrayList<>();}。
     *
     * <p>B 侧注释写明「{@code items} 在无数据时是 {@code []} 而不是 {@code null}」
     * ⇒ record 无字段初始化 ⇒ 把同一条不变式搬到构造器里。
     */
    public ConversationMessagesResp {
        items = items == null ? List.of() : items;
    }
}
