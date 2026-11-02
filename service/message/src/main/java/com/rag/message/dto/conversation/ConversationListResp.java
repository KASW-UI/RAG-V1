package com.rag.message.dto.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>职责</b>：会话列表响应 —— 对应 Go {@code types.go} 的 {@code ConversationListResp}。
 *
 * <pre>
 * Go:
 *   type ConversationListResp struct {
 *       Items       []ConversationItem `json:"items"`
 *       Total       int64              `json:"total"`
 *       UnreadCount int64              `json:"unread_count"`
 *   }
 * </pre>
 *
 * <p>{@code items} 无数据时为 {@code []}（Go 的 {@code make([]T, 0, len)}），
 * 原因与 {@link NotificationListResp} 相同。
 *
 * <p><b>{@code unread_count} 的语义</b>：该操作人<b>全部</b>会话的未读总数，
 * 不是当前页的未读之和 —— Go 侧 rpc 端按 recipient 聚合，与分页无关。
 * 分页取数与聚合是两件事，api 层原样透传。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.conversation}（D7 + D11）。
 *       字段集合、顺序、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的一处机制搬运</b>：B 侧靠字段初始化 {@code items = new ArrayList<>()}
 *       保证「无数据时输出 {@code []} 而不是 null」⇒ record 无字段初始化 ⇒ 该不变式落到
 *       <b>规范构造器</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— {@code items} 永远序列化成数组（B 侧
 *       {@code shared.ToConversationListResp} 的 {@code make([]T, 0, len)} 语义），
 *       {@code total} / {@code unread_count} 仍是 JSON <b>数字</b>（B 侧 tag 无 {@code ,string}）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/ConversationListResp.java:29-59}
 *       （字段声明在 {@code :31-34}）</li>
 * </ol>
 *
 * @param items       会话列表（无数据时是空数组，不是 null）
 * @param total       会话总数
 * @param unreadCount 该操作人全部会话的未读总数（与分页无关）
 */
public record ConversationListResp(
        @JsonProperty("items") List<ConversationItem> items,
        @JsonProperty("total") long total,
        @JsonProperty("unread_count") long unreadCount) {

    /**
     * 规范构造器 —— 承接 B 侧 {@code private List<ConversationItem> items = new ArrayList<>();}。
     *
     * <p>B 侧注释写明「{@code items} 无数据时为 {@code []}（Go 的 {@code make([]T, 0, len)}）」
     * ⇒ record 无字段初始化 ⇒ 把同一条不变式搬到构造器里。
     */
    public ConversationListResp {
        items = items == null ? List.of() : items;
    }
}
