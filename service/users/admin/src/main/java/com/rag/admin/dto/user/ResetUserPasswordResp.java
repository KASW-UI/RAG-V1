package com.rag.admin.dto.user;

/**
 * <p><b>职责</b>：admin resetUserPassword 用例的 HTTP 响应 DTO —— 是否重置成功。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.user}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 单字段 {@code success}（primitive {@code boolean}）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/ResetUserPasswordResp.java}</li>
 * </ol>
 *
 * @param success 是否成功
 */
public record ResetUserPasswordResp(boolean success) {
}
