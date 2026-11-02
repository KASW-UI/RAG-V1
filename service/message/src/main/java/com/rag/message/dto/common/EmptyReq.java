package com.rag.message.dto.common;

/**
 * <p><b>职责</b>：空请求 / 空响应 —— 对应 Go {@code types.go} 的 {@code type EmptyReq struct{}}。
 *
 * <p><b>双向用途</b>（B 侧同一个类型就是这样用的）：
 * <ul>
 *   <li><b>请求</b>：{@code GetUnreadSummaryHandler} / {@code MarkAllNotificationsReadHandler}
 *       都 {@code var req types.EmptyReq} ⇒ C 侧对应 {@code @RequestBody(required = false)} 入参；</li>
 *   <li><b>响应</b>：{@code return &types.EmptyReq{}, nil}（markConversationRead /
 *       markNotificationRead / markAllNotificationsRead 三个端点）⇒ C 侧对应 Service 返回值
 *       → 被包成 {@code data:{}}。</li>
 * </ul>
 *
 * <p><b>注意响应是 {@code {}} 而不是 {@code null}</b>：B 侧返回的是非 nil 的空 struct 指针，
 * {@code encoding/json} 序列化成 {@code {}}。若 C 侧返回 {@code null}，
 * 客户端会看到 {@code "data":null} —— 契约就变了。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class（零字段）→ <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.common}（D7 + D11）。
 *       B 侧「零字段 ⇒ 不实现 {@code GoStyleRequiredFields}」的取舍逐条保留 ⇒
 *       C 侧<b>零 {@code @NotNull}</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 零字段 ⇒ 零必填校验，序列化结果是 {@code {}}（实测同形）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/EmptyReq.java:31-32}</li>
 * </ol>
 */
public record EmptyReq() {
}
