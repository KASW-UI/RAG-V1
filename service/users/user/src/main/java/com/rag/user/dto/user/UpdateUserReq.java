package com.rag.user.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * <p><b>更新用户请求体（部分更新）</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/UpdateUserReq.java}
 * （4 个字段全部带 {@code @JsonInclude(NON_NULL)}；{@code extra_info} 另有 {@code @JsonProperty("extra_info")}）。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li>JSON 键 = {@code "username"} / {@code "password"} / {@code "email"} / {@code "extra_info"} ✔</li>
 *   <li><b>4 个字段全都可缺省</b>：B 的 {@code UserUpdateService} 只在
 *       {@code getUsername() != null} 时才 set ⇒ 缺席 = 不更新该列
 *       ⇒ C 侧 record 的缺席分量 = {@code null} ⇒ 语义逐条一致 ✔</li>
 *   <li>B 侧这 4 个字段<b>没有</b>必填校验（{@code UpdateUserReq} 未实现 {@code GoStyleRequiredFields}）
 *       ⇒ C 侧<b>不加</b> {@code @NotNull} ✔</li>
 * </ul>
 *
 * @param username  新用户名（null = 不改）
 * @param password  新口令（null = 不改；非空时服务端做 bcrypt）
 * @param email     新邮箱（null = 不改）
 * @param extraInfo 新扩展信息（null = 不改）
 */
public record UpdateUserReq(
        @JsonInclude(JsonInclude.Include.NON_NULL) String username,
        @JsonInclude(JsonInclude.Include.NON_NULL) String password,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo
) {
}
