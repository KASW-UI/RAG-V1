package com.rag.message.dto.notification;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>职责</b>：通知列表响应 —— 对应 Go {@code types.go} 的 {@code NotificationListResp}。
 *
 * <pre>
 * Go:
 *   type NotificationListResp struct {
 *       Items       []NotificationItem `json:"items"`
 *       Total       int64              `json:"total"`          // 数字
 *       UnreadCount int64              `json:"unread_count"`   // 数字
 *   }
 * </pre>
 *
 * <p><b>{@code items} 在无数据时是 {@code []} 而不是 {@code null}</b>：
 * Go 侧 {@code shared.ToNotificationListResp} 第一行就是
 * {@code items := make([]types.NotificationItem, 0, len(in.Items))}，
 * 非 nil 空切片被 {@code encoding/json} 序列化成 {@code []}。
 * Java 侧必须初始化成空 List，否则会输出 {@code "items":null} —— 客户端的
 * {@code for (item of items)} 会直接抛 NPE。
 *
 * <p>另外 Go 的映射循环里有 {@code if item == nil { continue }}，
 * 即 proto 的 repeated 元素为 nil 时<b>跳过</b>而不是产出空对象。
 * Java 侧 protobuf 不会给 repeated 塞 null，天然满足。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.notification}（D7 + D11）。
 *       字段集合、顺序、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的一处机制搬运</b>：B 侧靠字段初始化 {@code items = new ArrayList<>()}
 *       保证「无数据时输出 {@code []} 而不是 null」⇒ record 无字段初始化 ⇒ 该不变式落到
 *       <b>规范构造器</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— {@code items} 永远序列化成数组，
 *       {@code total} / {@code unread_count} 仍是 JSON <b>数字</b>（B 侧 tag 无 {@code ,string}）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/NotificationListResp.java:32-64}
 *       （字段声明在 {@code :35-39}）</li>
 * </ol>
 *
 * @param items       通知列表（无数据时是空数组，不是 null）
 * @param total       通知总数
 * @param unreadCount 未读数
 */
public record NotificationListResp(
        @JsonProperty("items") List<NotificationItem> items,
        @JsonProperty("total") long total,
        @JsonProperty("unread_count") long unreadCount) {

    /**
     * 规范构造器 —— 承接 B 侧 {@code private List<NotificationItem> items = new java.util.ArrayList<>();}。
     *
     * <p>B 侧注释写明「{@code items} 在无数据时是 {@code []} 而不是 {@code null}」
     * ⇒ record 无字段初始化 ⇒ 把同一条不变式搬到构造器里。
     */
    public NotificationListResp {
        items = items == null ? List.of() : items;
    }
}
