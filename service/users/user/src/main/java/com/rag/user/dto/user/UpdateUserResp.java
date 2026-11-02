package com.rag.user.dto.user;

/**
 * <p><b>更新用户响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/UpdateUserResp.java}
 * （{@code private UserInfo user}，<b>无</b> {@code @JsonInclude}）。
 *
 * <p><b>语义等价论证</b>：JSON 键 = {@code "user"} ✔；B 无 {@code @JsonInclude}
 * ⇒ user 为 null 时<b>仍然输出</b> {@code "user":null} ⇒ C 侧<b>不加</b> {@code @JsonInclude} ✔。
 *
 * <p>⚠️ 与 {@code GetUserResp} 的差别<b>是行为</b>：后者带 {@code NON_NULL}、本类不带
 * ⇒ ⇒ <b>不要</b>把两者的注解「顺手统一」。
 *
 * @param user 更新后的用户信息（B 侧只填 uid / username / email / extraInfo 四项，<b>不含 score</b>）
 */
public record UpdateUserResp(
        UserInfo user
) {
}
