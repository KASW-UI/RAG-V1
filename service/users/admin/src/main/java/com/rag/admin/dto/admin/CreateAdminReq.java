package com.rag.admin.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * <p><b>职责</b>：admin createAdmin 用例的 HTTP 请求 DTO —— 账号 / 密码 / 邮箱 / 邀请码 / 扩展信息。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）。
 *       字段集合、顺序、JSON 键名逐条照抄。</li>
 *   <li><b>语义还一样吗</b>：必填判定口径一致 —— B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ 本实现用 {@code @NotNull}（<b>不是 {@code @NotBlank}</b>，否则会多拒「空串」这一类合法输入）。
 *       ⚠️ {@code extraInfo} <b>不加</b>约束：B 侧对它走 {@code GoStyleRequired.explicitNull}
 *       （三态：缺席放行 / 显式 null 报 {@code mustn't be nil}），Bean Validation 无等价物
 *       ⇒ 降级登记为 <b>D-01</b>（{@code docs/L3-降级清单.md}）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/CreateAdminReq.java}</li>
 * </ol>
 *
 * <p><b>必填口径（B 侧 {@code assertGoRequiredFields} 逐条）</b>：
 * {@code username} / {@code password} / {@code email} / {@code invite_code} 四个标量必填
 * （⚠️ {@code email} / {@code invite_code} 的 B 侧 tag 虽写着 {@code ,omitempty}，但那<b>不是</b>
 * {@code ,optional} ⇒ go-zero 仍判必填）。
 *
 * @param username   管理员用户名（必填）
 * @param password   明文密码（必填）
 * @param email      邮箱（必填；B 侧带 {@code NON_NULL}）
 * @param inviteCode 邀请码（必填；wire 键名 {@code invite_code}）
 * @param extraInfo  扩展信息（B 侧带 {@code NON_NULL} + {@code @JsonProperty("extra_info")}；<b>不加约束</b>，见 D-01）
 */
public record CreateAdminReq(
        @NotNull String username,
        @NotNull String password,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @NotNull @JsonProperty("invite_code") String inviteCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo) {
}
