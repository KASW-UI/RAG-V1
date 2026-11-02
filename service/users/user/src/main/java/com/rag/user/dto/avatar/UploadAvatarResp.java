package com.rag.user.dto.avatar;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>上传头像响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/UploadAvatarResp.java}
 * （{@code @JsonProperty("avatar_url")} ＋ {@code AvatarHistoryItem history}）。
 *
 * <p><b>语义等价论证</b>：JSON 键 = {@code "avatar_url"} ＋ {@code "history"} ✔；
 * 两个分量都<b>无</b> {@code @JsonInclude} ⇒ null 时输出 null ⇒ 与 B 同形。
 *
 * @param avatarUrl 落库后的头像 URL（<b>已 TrimSpace</b>）
 * @param history   新建的历史条目（{@code create_time} 为 RFC3339 字符串）
 */
public record UploadAvatarResp(
        @JsonProperty("avatar_url") String avatarUrl,
        AvatarHistoryItem history
) {
}
