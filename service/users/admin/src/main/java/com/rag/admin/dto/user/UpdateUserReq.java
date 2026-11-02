package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * <p><b>职责</b>：admin updateUser 用例的 HTTP 请求 DTO —— 目标用户 UID + 用户名 / 密码 / 邮箱 / 扩展信息
 * （{@code operator_id} 从 JWT claim 解析）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）。
 *       字段集合、顺序、JSON 键名逐条照抄。</li>
 *   <li><b>语义还一样吗</b>：必填判定口径一致 —— B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ 用 {@code @NotNull}（<b>不是 {@code @NotBlank}</b>，否则多拒「空串」这一类合法输入）。
 *       ⚠️ {@code extraInfo} <b>不加</b>约束：B 侧对它走 {@code GoStyleRequired.explicitNull}
 *       （三态：缺席放行 / 显式 null 报 {@code mustn't be nil}），Bean Validation 无等价物
 *       ⇒ 降级登记为 <b>D-01</b>。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/UpdateUserReq.java}</li>
 * </ol>
 *
 * <p><b>必填口径（B 侧 {@code assertGoRequiredFields} 逐条）</b>：{@code uid} / {@code username} /
 * {@code password} / {@code email} <b>四个标量全必填</b> —— ⚠️ {@code username} / {@code password} /
 * {@code email} 的 B 侧 tag 虽写着 {@code ,omitempty}，但那<b>不是</b> {@code ,optional}
 * ⇒ go-zero 仍判必填 ⇒ <b>该接口在 B 侧无法做部分更新</b>，且<b>不传 uid 直接 400</b>。
 * ⚠️ 注意这与 user-api 侧的 {@code user.update}（全 {@code ,optional}、支持部分更新）<b>恰好相反</b> ——
 * 两端同名结构但 tag 不同，<b>不要跨域套用结论</b>（AGENTS.md §3.4.3 N-3 的同款警告）。
 *
 * @param uid       目标用户 UID，<b>序列化为字符串</b>（必填）
 * @param username  用户名（必填）
 * @param password  密码（必填）
 * @param email     邮箱（必填）
 * @param extraInfo 扩展信息（B 侧带 {@code NON_NULL} + {@code @JsonProperty("extra_info")}；<b>不加约束</b>，见 D-01）
 */
public record UpdateUserReq(
        @JsonProperty("uid") @NotNull String uid,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String username,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String password,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo) {
}
