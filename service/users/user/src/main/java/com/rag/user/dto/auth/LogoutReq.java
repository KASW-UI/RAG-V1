package com.rag.user.dto.auth;

/**
 * <p><b>注销请求体</b> —— <b>空 record</b>（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/LogoutReq.java}
 * —— B 侧就是一个<b>没有任何字段</b>的可变 class。
 *
 * <p><b>语义等价论证</b>：B 侧 {@code UserController#logout} 的 body 字段<b>不参与</b>业务分支
 * （操作人取自 JWT 的 {@code userId}，token 取自 request attribute {@code jwt_token}）
 * ⇒ 请求体可有可无 ⇒ C 侧保持空 record ＋ {@code @RequestBody(required = false)}。
 *
 * <p>⚠️ <b>空 body 时 Jackson 会给出 {@code null}</b>（不是空实例）⇒ 调用方必须容忍 {@code null}
 * ⇒ 本域的 {@code UserApiService#logout} 不读 body ✔。
 */
public record LogoutReq() {
}
