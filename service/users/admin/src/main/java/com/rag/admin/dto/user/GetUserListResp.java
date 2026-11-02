package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * <p><b>职责</b>：admin getUserList 用例的 HTTP 响应 DTO —— 用户列表 + 总数。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.user}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段顺序（{@code list} 在前、{@code total} 在后）逐条照抄。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/GetUserListResp.java}</li>
 * </ol>
 *
 * <p><b>⚠️ {@code list} 故意不加 {@code @JsonInclude(NON_NULL)}</b>（B 侧 A-01 已修的口径）：
 * B 侧 {@code GetUserListResp.List} 的 tag 是 {@code `json:"list"`} —— <b>没有 omitempty</b> ⇒
 * 空结果时序列化成 <b>{@code "list": null}</b>（字段出现、值为 null），<b>不是</b> {@code []}，
 * 也<b>不是</b>「字段消失」⇒ service 层空结果必须置 {@code null}。
 *
 * @param list  用户列表（空结果时为 {@code null} ⇒ 序列化成字面量 {@code null}）
 * @param total 总数
 */
public record GetUserListResp(
        List<UserInfo> list,
        long total) {
}
