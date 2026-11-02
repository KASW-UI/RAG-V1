package com.rag.user.dto.user;

/**
 * <p><b>查询用户请求体</b> —— <b>空 record</b>（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/GetUserReq.java}
 * —— B 侧就是一个<b>没有任何字段</b>的可变 class。
 *
 * <p><b>语义等价论证</b>：B 侧 {@code UserController#getUser} 的 body 字段<b>不参与</b>业务分支
 * （uid 走 JWT claim）⇒ 请求体可有可无
 * ⇒ C 侧保持空 record ＋ {@code @RequestBody(required = false)}。
 *
 * <p>⚠️ <b>空 body 时 Jackson 给出 {@code null}</b> ⇒ 调用方必须容忍 {@code null}
 * ⇒ 本域的 {@code UserApiService#getUser} 不读 body ✔。
 */
public record GetUserReq() {
}
