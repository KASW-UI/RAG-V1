package com.rag.user.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * <p><b>注册（创建用户）请求体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/RegisterReq.java}
 * （可变 class ＋ {@code GoStyleRequiredFields}：{@code username} / {@code password} / {@code email} 必填，
 * {@code extra_info} 走 {@code GoStyleRequired.explicitNull} 的三态判定）。
 *
 * <p><b>⚠️ 类名差异（L4 表达层 · 登记于本域 DRIFT 台账）</b>：B 侧 HTTP DTO 叫 {@code RegisterReq} /
 * {@code RegisterResp}，而 B 侧 <b>proto</b> 侧叫 {@code CreateUserReq} / {@code CreateUserResp}
 * ⇒ C 侧取 proto 名（{@code CreateUser*}）⇒ <b>JSON 键与字段集完全不变</b>（类名不进 wire）
 * ⇒ 仅 Java 符号名不同 ⇒ 见 {@code docs/Mapping_Table/users/_shared.md} F-5。
 *
 * <p><b>必填判定的等价论证</b>：
 * <ul>
 *   <li>B 的 {@code GoStyleRequired.field(...)} <b>只判 null</b>（空串合法）⇒ C 侧用 {@link NotNull}
 *       （<b>不是</b> {@code @NotBlank}）⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔</li>
 *   <li>B 的 {@code email} <b>也是必填</b>（B 侧 {@code RegisterReq.java:57}）⇒ C 侧同样 {@code @NotNull} ✔</li>
 *   <li>{@code extra_info}：B 的三态判定（缺席不报 / 显式 null 报）<b>在 record 形态下不可表达</b>
 *       ⇒ C 侧<b>不加任何约束</b>（缺席合法 ✔，显式 null 也放行 ✘）
 *       ⇒ 该差异属 <b>已批准的 L3 降级</b>（{@code docs/L3-降级清单.md} D-01 ②）</li>
 * </ul>
 *
 * @param username  用户名（B：必填，空串合法）
 * @param password  口令（B：必填，空串合法）
 * @param email     邮箱（B：必填，空串合法）
 * @param extraInfo 扩展信息（可选）
 */
public record CreateUserReq(
        @NotNull String username,
        @NotNull String password,
        @NotNull @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo
) {
}
