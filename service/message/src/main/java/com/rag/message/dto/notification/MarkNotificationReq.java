package com.rag.message.dto.notification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：标记单条通知已读请求 —— 对应 Go {@code types.go} 的 {@code MarkNotificationReq}。
 *
 * <pre>
 * Go:
 *   type MarkNotificationReq struct {
 *       NotificationId int64 `json:"notification_id,string"`   // 必填 + ,string
 *   }
 * </pre>
 *
 * <p>归属校验<b>不在 api 层</b>：Go 的
 * {@code logic/message/marknotificationreadlogic.go} 只是把 {@code userId}（来自 JWT）
 * 与 {@code notification_id} 一起透传给 rpc，由 message-rpc 的单条原子
 * {@code UPDATE ... WHERE id = ? AND recipient_id = ?} 保证越权读不到
 * （Go 出处 {@code rpc/internal/logic/marknotificationreadlogic.go}；
 * B 侧已按同款条件折叠，回归锁定见 {@code MessageMarkNotificationReadTest}）。
 * api 层<b>不得</b>提前查库判断归属 —— 那会引入 B 侧不存在的 TOCTOU 窗口。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧「{@code Object} 型 setter」→ <b>静态 {@code @JsonCreator} 工厂</b>；
 *       B 侧 {@code GoStyleStringInt64} → 共享层 {@code com.rag.common.web.StringInt64}（D3）；
 *       包名 {@code api.dto} → {@code dto.notification}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 必填口径 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ {@code @NotNull}（<b>不是 {@code @NotBlank}</b>）；
 *       {@code ,string} 入参「字符串与整数都接受、浮点 / 布尔 / 超界一律拒」由
 *       {@code StringInt64.parse} 逐条承接；JSON 键名 {@code notification_id} 照抄。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/MarkNotificationReq.java:27-45}
 *       （必填断言在 {@code :37-40}；{@code ,string} setter 在 {@code :31-35}）</li>
 * </ol>
 *
 * @param notificationId 通知 id（必填；JSON 字符串与整数都接受；归属校验在 message-rpc 侧）
 */
public record MarkNotificationReq(
        @NotNull @JsonProperty("notification_id") Long notificationId) {

    /**
     * 唯一的反序列化入口 —— 承接 B 侧的 {@code Object} 型 setter。
     *
     * <p><b>为什么入参声明成 {@code Object}</b>：本字段带 {@code ,string}
     * ⇒ <b>同时接受 JSON 字符串和 JSON 数字</b> ⇒ 只有「不声明具体目标类型、让 Jackson 原样递出」
     * 才能表达这两条都通；随后由 {@link StringInt64} 完全接管判定。
     *
     * @param rawNotificationId {@code notification_id} 的原始 JSON 值
     * @return 解析后的 DTO
     */
    @JsonCreator
    public static MarkNotificationReq of(
            @JsonProperty("notification_id") Object rawNotificationId) {
        return new MarkNotificationReq(StringInt64.parse(rawNotificationId, "notification_id"));
    }
}
