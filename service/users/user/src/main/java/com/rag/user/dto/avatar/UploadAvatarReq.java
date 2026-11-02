package com.rag.user.dto.avatar;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>上传头像请求体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/UploadAvatarReq.java}
 * （{@code avatar_url} 必填；{@code content_type} / {@code size_bytes} 带 {@code @JsonInclude(NON_NULL)}）。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li>JSON 键 = {@code "avatar_url"} / {@code "content_type"} / {@code "size_bytes"} ✔</li>
 *   <li>{@code content_type} / {@code size_bytes} 在 B 侧是 {@code @JsonInclude(NON_NULL)}
 *       ⇒ 为 null 时<b>整个键消失</b> ⇒ C 侧逐条照抄该注解 ✔（否则会多出 {@code "content_type":null}
 *       ⇒ 是 wire 差异）</li>
 *   <li>必填：B 的 {@code GoStyleRequired.field("avatar_url", ...)} 只判 null ⇒ 空串合法
 *       ⇒ C 侧用 {@link NotNull}（不是 {@code @NotBlank}）⇒ 等价</li>
 * </ul>
 *
 * @param avatarUrl  头像 URL（B：必填，空串合法；实际判空在 {@code UserApiService} 里 TrimSpace 后判空）
 * @param contentType MIME 类型（可空 ⇒ 归一化成 {@code image/png}）
 * @param sizeBytes  字节数（可空）
 */
public record UploadAvatarReq(
        @NotNull @JsonProperty("avatar_url") String avatarUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("content_type") String contentType,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("size_bytes") Long sizeBytes
) {
}
