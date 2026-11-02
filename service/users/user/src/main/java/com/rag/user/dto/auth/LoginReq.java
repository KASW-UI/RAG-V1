package com.rag.user.dto.auth;

import jakarta.validation.constraints.NotNull;

/**
 * <p><b>登录请求体</b> —— immutable record（决策 D5：DTO 全部改 record ＋ Bean Validation）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/LoginReq.java}
 * （可变 class ＋ {@code GoStyleRequiredFields} 手写必填校验）。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li><b>字段集逐条一致</b>：B = {@code username} ＋ {@code password}；C 同。
 *       JSON 键名 = Java 字段名（B 侧这两个字段<b>没有</b> {@code @JsonProperty}）⇒ wire 一致。</li>
 *   <li><b>必填判定</b>：B 的 {@code GoStyleRequired.field("username", username)} <b>只判 null</b>
 *       ⇒ <b>空串合法</b>（D-01 note 已钉死）⇒ ⇒ C 侧用 {@link NotNull}（<b>不是</b> {@code @NotBlank}）
 *       ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 与 B <b>逐条等价</b>。</li>
 *   <li><b>错误文案</b>：B = {@code 400 field "username" is not set}；C = Hibernate Validator 文案
 *       ⇒ <b>已登记降级</b>（{@code docs/L3-降级清单.md} D-01 · 状态码 400 ＋ text/plain 结构不变）。</li>
 * </ul>
 *
 * @param username 用户名（B：必填，空串合法）
 * @param password 口令（B：必填，空串合法）
 */
public record LoginReq(
        @NotNull String username,
        @NotNull String password
) {
}
