package com.rag.admin.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * <p><b>职责</b>：admin updateSelf 用例的 HTTP 请求 DTO —— 更新自己的用户名 / 密码 / 邮箱 / 扩展信息。
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
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/UpdateSelfReq.java}</li>
 * </ol>
 *
 * <p><b>⚠️ 本 DTO 没有 {@code uid} 组件</b> —— B 侧 {@code UpdateSelfReq} <b>也没有</b>
 * （uid 从 JWT claim 解析，见 B 侧 {@code AdminUpdateSelfService.java:49} 的
 * {@code IdentityResolver.claimedUID(httpRequest)}）⇒ 逐字照抄 B 侧，
 * <b>不要</b>为了「加一个必填 uid」而新增组件（那会改 L1 请求体形状，且比 B 多拒一类合法请求）。
 *
 * <p><b>必填口径（B 侧 {@code assertGoRequiredFields} 逐条）</b>：{@code username} /
 * {@code password} / {@code email} <b>三个标量全必填</b> —— ⚠️ 这是本批最反直觉的一条：
 * B 侧 tag 虽写着 {@code ,omitempty}，但那<b>不是</b> {@code ,optional}
 * ⇒ go-zero 仍判必填 ⇒ <b>该接口在 B 侧无法做部分更新</b>（只想改密码也得把 username 与 email 一起重发）。
 *
 * @param username   用户名（必填）
 * @param password   密码（必填）
 * @param email      邮箱（必填）
 * @param extraInfo  扩展信息（B 侧带 {@code NON_NULL} + {@code @JsonProperty("extra_info")}；<b>不加约束</b>，见 D-01）
 */
public record UpdateSelfReq(
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String username,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String password,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo) {
}
