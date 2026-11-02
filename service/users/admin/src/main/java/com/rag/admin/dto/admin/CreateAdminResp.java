package com.rag.admin.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：admin createAdmin 用例的 HTTP 响应 DTO —— 新建管理员的 UID。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.admin}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样。B 侧字段是 {@code Long uid} 但 getter 返回 {@code String}
 *       ⇒ JSON 里 {@code uid} 是<b>字符串</b>（§D-C：L1 事实，必须复刻）⇒ 本组件类型直接是 {@code String}。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/CreateAdminResp.java}</li>
 * </ol>
 *
 * @param uid 新建管理员的 Snowflake UID，<b>序列化为字符串</b>
 */
public record CreateAdminResp(@JsonProperty("uid") String uid) {
}
