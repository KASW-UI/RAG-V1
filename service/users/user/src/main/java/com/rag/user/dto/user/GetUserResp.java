package com.rag.user.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <p><b>查询用户响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/GetUserResp.java}
 * （{@code @JsonInclude(NON_NULL) private UserInfo user} ＋ {@code private boolean found} ＋ {@code isFound()}）。
 *
 * <p><b>语义等价论证</b>：JSON 键 = {@code "user"} ＋ {@code "found"} ✔；
 * {@code user} 带 {@code @JsonInclude(NON_NULL)} ⇒ null 时键消失 ⇒ C 侧照抄 ✔；
 * {@code found} 是原始 {@code boolean} ⇒ 恒输出 ✔。
 *
 * @param user  用户信息（可为 null）
 * @param found 是否命中（B 侧成功路径恒为 {@code true}）
 */
public record GetUserResp(
        @JsonInclude(JsonInclude.Include.NON_NULL) UserInfo user,
        boolean found
) {
}
