package com.rag.user.dto.avatar;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>选定历史头像响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/SelectAvatarResp.java}
 * （{@code @JsonProperty("avatar_url")} ＋ {@code AvatarHistoryItem history}）。
 *
 * <p><b>语义等价论证</b>：JSON 键 = {@code "avatar_url"} ＋ {@code "history"}（后者 B 无注解 ⇒ 由字段名推导）
 * ⇒ C 侧逐条一致。两个分量都<b>无</b> {@code @JsonInclude} ⇒ null 时输出 null ⇒ 与 B 同形。
 *
 * @param avatarUrl 被选中的头像 URL
 * @param history   被选中的历史条目
 */
public record SelectAvatarResp(
        @JsonProperty("avatar_url") String avatarUrl,
        AvatarHistoryItem history
) {
}
