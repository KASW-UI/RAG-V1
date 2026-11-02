package com.rag.admin.dto.invite;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：admin createInviteCode 用例的 HTTP 响应 DTO —— 邀请码 + 过期时间（unix 秒）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       <b>类名</b>：B 侧 Java 类就叫 {@code CreateInviteCodeResp}（<b>只有 B 侧</b>叫 {@code CreateInviteResp}）
 *       ⇒ 类名逐字照抄 B 侧 Java，本仓的旧空壳名 {@code CreateInviteResp} 已按批准改名。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段名（{@code inviteCode} / {@code expiresAt}）与 wire 键名
 *       （{@code invite_code} / {@code expires_at}）逐条照抄。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/CreateInviteCodeResp.java}</li>
 * </ol>
 *
 * @param inviteCode 邀请码（{@code "ADM-"} + 16 位大写 hex），wire 键名 {@code invite_code}
 * @param expiresAt  过期时间（unix 秒），wire 键名 {@code expires_at}
 */
public record CreateInviteCodeResp(
        @JsonProperty("invite_code") String inviteCode,
        @JsonProperty("expires_at") long expiresAt) {
}
