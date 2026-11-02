package com.rag.message.dto.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * <p><b>职责</b>：单条通知 —— 对应 Go {@code types.go} 的 {@code NotificationItem}。
 *
 * <pre>
 * Go:
 *   type NotificationItem struct {
 *       Id, RecipientId, SenderId int64             `json:",string"`   // 三个都是字符串
 *       SenderRole  int32          `json:"sender_role"`
 *       Kind        int32          `json:"kind"`
 *       Title       string         `json:"title"`
 *       Content     string         `json:"content"`
 *       IsRead      bool           `json:"is_read"`
 *       CreatedAt   int64          `json:"created_at"`                   // ← 数字，不是字符串
 *       Extra       map[string]string `json:"extra"`
 *   }
 * </pre>
 *
 * <p><b>⚠️ 同一条记录里三种数字表示并存</b>：id 三兄弟是字符串、sender_role/kind 是数字、
 * created_at 也是数字。这是 Go tag 的原样，必须保留 —— 见 {@code UnreadSummaryResp} 的同类说明。
 *
 * <p><b>{@code extra} 没有 {@code ,optional}</b>，但它是 map：Go 对 map 永不做必填校验，
 * 缺席时序列化成 {@code "extra":null}（Go 的 nil map 走 encoding/json 的 null 分支）。
 * Java 侧因此<b>不</b>加 {@code @JsonInclude(NON_NULL)}，让 null 原样输出。
 *
 * <p><b>{@code sender_role} / {@code kind} 是 int32 而非字符串枚举名</b>：
 * Go 侧 {@code shared.ToNotificationListResp} 显式写了 {@code int32(item.SenderRole)}，
 * 即「proto 枚举 → 数字」。Java 侧从 proto 读 {@code getSenderRoleValue()} 拿原始 int。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.notification}（D7 + D11）。
 *       字段集合、<b>顺序</b>、类型、注解逐条照抄 B 侧。
 *       ⚠️ <b>唯一的一处机制搬运</b>：B 侧靠两个字段初始化（{@code title = ""} /
 *       {@code content = ""}）保证零值占位路径输出 {@code ""} 而不是 null
 *       ⇒ record 无字段初始化 ⇒ 该不变式落到<b>规范构造器</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 三个 id 仍是 {@code @JsonFormat(shape = STRING)}
 *       （JSON <b>字符串</b>），{@code sender_role} / {@code kind} / {@code created_at} 仍是
 *       JSON <b>数字</b>，{@code is_read} 的键名照抄 B 侧（不是 {@code read}），
 *       {@code extra} <b>不加</b> {@code @JsonInclude}（null 原样输出）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/NotificationItem.java:39-156}
 *       （字段声明在 {@code :41-66}）</li>
 * </ol>
 *
 * @param id           通知 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param recipientId  接收者 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param senderId     发送者 id（<b>序列化为字符串</b> · B 侧 {@code ,string}）
 * @param senderRole   发送者角色（proto 枚举 → 数字）
 * @param kind         通知类型（数字）
 * @param title        标题（零值占位时输出 {@code ""}，不是 null）
 * @param content      正文（零值占位时输出 {@code ""}，不是 null）
 * @param isRead       是否已读（JSON 键 = {@code is_read}，不是 {@code read}）
 * @param createdAt    创建时间（Unix 秒，JSON <b>数字</b>）
 * @param extra        扩展信息（缺席时是 {@code null}，原样输出）
 */
public record NotificationItem(
        @JsonProperty("id") @JsonFormat(shape = JsonFormat.Shape.STRING) long id,
        @JsonProperty("recipient_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long recipientId,
        @JsonProperty("sender_id") @JsonFormat(shape = JsonFormat.Shape.STRING) long senderId,
        @JsonProperty("sender_role") int senderRole,
        @JsonProperty("kind") int kind,
        @JsonProperty("title") String title,
        @JsonProperty("content") String content,
        @JsonProperty("is_read") boolean isRead,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("extra") Map<String, String> extra) {

    /**
     * 规范构造器 —— 承接 B 侧两个 {@code = ""} 的字段初始化。
     *
     * <p>B 侧注释写明「Go 零值 struct 里 string 字段恒为 ""（不是 null），
     * 零值占位路径（proto message 缺失）依赖它输出 "" 而不是 null」
     * ⇒ record 无字段初始化 ⇒ 把同一条不变式搬到构造器里。
     */
    public NotificationItem {
        title = title == null ? "" : title;
        content = content == null ? "" : content;
    }
}
