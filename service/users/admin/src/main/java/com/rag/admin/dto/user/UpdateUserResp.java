package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <p><b>职责</b>：admin updateUser 用例的 HTTP 响应 DTO —— 更新结果 + 最新用户信息。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.user}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段顺序（{@code success} 在前、{@code user} 在后）逐条照抄；
 *       两个字段都<b>没有</b> {@code NON_NULL}（B 侧如此）⇒ 本实现也不加。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/UpdateUserResp.java}</li>
 * </ol>
 *
 * @param success 是否成功
 * @param user    更新后的用户信息
 */
public record UpdateUserResp(
        boolean success,
        UserInfo user) {
}
