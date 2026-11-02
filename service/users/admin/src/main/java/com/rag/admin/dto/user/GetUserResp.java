package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <p><b>职责</b>：admin getUser 用例的 HTTP 响应 DTO —— 用户信息 + 是否找到。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.user}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段顺序（{@code user} 在前、{@code found} 在后）与 JSON 键序逐条照抄；
 *       {@code user} 带 {@code NON_NULL}（B 侧 {@code user} 为 optional，null 时字段整个省略），
 *       {@code found} 为 primitive {@code boolean} ⇒ 永远出现在 JSON 里。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/GetUserResp.java}</li>
 * </ol>
 *
 * @param user  用户信息（null 时字段整个省略）
 * @param found 是否找到
 */
public record GetUserResp(
        @JsonInclude(JsonInclude.Include.NON_NULL) UserInfo user,
        boolean found) {
}
