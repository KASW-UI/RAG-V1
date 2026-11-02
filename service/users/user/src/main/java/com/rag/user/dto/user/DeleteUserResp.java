package com.rag.user.dto.user;

/**
 * <p><b>删除用户响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/DeleteUserResp.java}
 * （{@code private boolean success} ＋ {@code isSuccess()}）。
 *
 * <p><b>语义等价论证</b>：B 的访问器 {@code isSuccess()} ⇒ JSON 键 = {@code "success"}
 * ⇒ C 侧 record 分量名取 {@code success} ⇒ 键一致；B 是原始 {@code boolean} ⇒ 恒输出
 * {@code "success":true|false} ⇒ C 侧用原始 {@code boolean} ⇒ 同形。
 *
 * @param success 是否删除成功
 */
public record DeleteUserResp(
        boolean success
) {
}
