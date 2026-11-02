package com.rag.admin.dto.auth;

/**
 * <p><b>职责</b>：admin login 用例的 HTTP 响应 DTO —— HS256 JWT 令牌。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.auth}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 单字段 {@code token}，字段名 / JSON 键序逐字照抄。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/LoginResp.java}</li>
 * </ol>
 *
 * @param token HS256 JWT
 */
public record LoginResp(String token) {
}
