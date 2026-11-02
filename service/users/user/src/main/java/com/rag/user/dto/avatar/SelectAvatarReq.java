package com.rag.user.dto.avatar;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>选定历史头像请求体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/SelectAvatarReq.java}
 * （{@code @JsonProperty("history_id")} ＋ {@code GoStyleRequired.field("history_id", historyId)}）。
 *
 * <p><b>语义等价论证</b>：JSON 键 = {@code "history_id"} ✔；必填判定 B 只判 null ⇒ 空串合法
 * ⇒ C 侧用 {@link NotNull}（<b>不是</b> {@code @NotBlank}）⇒ 与 B 等价（文案差异已登记 D-01）。
 *
 * @param historyId 头像历史条目 ID（B：必填，空串合法；实际判空在 {@code UserApiService} 里做 TrimSpace 后判空）
 */
public record SelectAvatarReq(
        @NotNull @JsonProperty("history_id") String historyId
) {
}
