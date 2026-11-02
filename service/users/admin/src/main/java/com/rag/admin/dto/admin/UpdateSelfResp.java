package com.rag.admin.dto.admin;

/**
 * <p><b>职责</b>：admin updateSelf 用例的 HTTP 响应 DTO —— 更新结果 + 最新管理员信息。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.admin}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两字段，顺序（{@code success} 在前、{@code admin} 在后）
 *       与 JSON 键序逐条照抄（B 侧两个字段都<b>没有</b> {@code NON_NULL} ⇒ 本实现也不加）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/UpdateSelfResp.java}</li>
 * </ol>
 *
 * @param success 是否成功
 * @param admin   更新后的管理员信息
 */
public record UpdateSelfResp(boolean success, AdminInfo admin) {
}
