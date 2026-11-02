package com.rag.user.dto.auth;

/**
 * <p><b>登录响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/LoginResp.java}
 * （可变 class ＋ {@code private String token}）。
 *
 * <p><b>语义等价论证</b>：字段集 = {@code token}（B 无 {@code @JsonProperty} ⇒ JSON 键 = {@code "token"}）
 * ⇒ wire 一致。B 的 getter 无 {@code @JsonInclude} ⇒ token 为 null 时仍输出 {@code "token":null}
 * ⇒ C 侧 record 分量不加 {@code @JsonInclude} ⇒ 同形。
 *
 * @param token JWT（HS256，claim = {@code userId}；由 {@code com.rag.common.security.JwtUtil} 签发）
 */
public record LoginResp(
        String token
) {
}
