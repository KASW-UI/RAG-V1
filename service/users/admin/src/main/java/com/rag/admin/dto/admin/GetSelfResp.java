package com.rag.admin.dto.admin;

/**
 * <p><b>职责</b>：admin getSelf 用例的 HTTP 响应 DTO —— 当前登录管理员的 {@link AdminInfo}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.admin}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 单字段，字段名 / 顺序 / JSON 键名逐字照抄
 *       （B 侧 {@code admin} <b>没有</b> {@code NON_NULL} ⇒ 本实现也不加）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/GetSelfResp.java}</li>
 * </ol>
 *
 * @param admin 当前管理员信息
 */
public record GetSelfResp(AdminInfo admin) {
}
