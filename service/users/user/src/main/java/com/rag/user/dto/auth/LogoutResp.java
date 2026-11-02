package com.rag.user.dto.auth;

/**
 * <p><b>注销响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/LogoutResp.java}
 * （{@code private boolean success} ＋ {@code isSuccess()}）。
 *
 * <p><b>语义等价论证</b>：B 的访问器是 {@code isSuccess()} ⇒ Jackson 推导出的 JSON 键 = {@code "success"}
 * ⇒ C 侧 record 分量名取 {@code success}（record 的访问器固定叫 {@code success()}）
 * ⇒ <b>JSON 键逐条一致</b>。B 侧是原始 {@code boolean}（无 {@code @JsonInclude}）⇒ 恒输出
 * {@code "success":true|false} ⇒ C 侧用原始 {@code boolean} 分量 ⇒ 同形。
 *
 * @param success 是否注销成功
 */
public record LogoutResp(
        boolean success
) {
}
