package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：admin getUser 用例的 HTTP 请求 DTO —— 目标用户 UID。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）。</li>
 *   <li><b>语义还一样吗</b>：一样。B 侧字段是 {@code Long uid} 但 getter 返回 {@code String}
 *       ⇒ JSON 里 {@code uid} 是<b>字符串</b>（§D-C：L1 事实，必须复刻）⇒ 本组件类型直接是 {@code String}。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/GetUserReq.java}</li>
 * </ol>
 *
 * <p><b>必填口径</b>：{@code uid} 必填（B 侧 {@code assertGoRequiredFields} 逐条）。
 *
 * @param uid 目标用户 UID，<b>序列化为字符串</b>
 */
public record GetUserReq(@JsonProperty("uid") @NotNull String uid) {
}
