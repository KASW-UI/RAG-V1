package com.rag.admin.dto.auth;

import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：admin login 用例的 HTTP 请求 DTO —— 账号 / 密码。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       包名 {@code api.dto} → {@code dto.auth}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：必填判定口径一致 —— B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ 用 {@code @NotNull}（<b>不是 {@code @NotBlank}</b>，否则多拒「空串」这一类合法输入）。
 *       字段名与 JSON 键序逐条照抄。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/LoginReq.java}</li>
 * </ol>
 *
 * @param username 账号（必填）
 * @param password 密码（必填）
 */
public record LoginReq(
        @NotNull String username,
        @NotNull String password) {
}
