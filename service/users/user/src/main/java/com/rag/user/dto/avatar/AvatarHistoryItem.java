package com.rag.user.dto.avatar;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>头像历史条目（HTTP 层）</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/AvatarHistoryItem.java}
 * （可变 class ＋ 6 个 {@code @JsonProperty}）。
 *
 * <p><b>语义等价论证</b>（6 个字段逐条）：B 的 JSON 键 = {@code id} / {@code avatar_url} /
 * {@code content_type} / {@code size_bytes} / {@code is_current} / {@code create_time}
 * ⇒ C 侧用 {@link JsonProperty} 显式钉死同样的 6 个键（record 的分量名默认是驼峰，
 * 不可直接沿用）⇒ <b>wire 逐条一致</b>。
 * <ul>
 *   <li>{@code sizeBytes}：B 是原始 {@code long}（无 {@code @JsonInclude}）⇒ 恒输出数字
 *       ⇒ C 侧用原始 {@code long} ✔</li>
 *   <li>{@code isCurrent}：B 侧访问器是 {@code isCurrent()} ＋ 字段 {@code @JsonProperty("is_current")}
 *       ⇒ JSON 键 = {@code "is_current"} ⇒ C 侧 record 分量名取 {@code isCurrent} ＋ 显式
 *       {@code @JsonProperty("is_current")} ✔（record 的访问器固定叫 {@code isCurrent()}，
 *       不会被 JavaBeans 的 {@code is} 前缀规则改名）</li>
 *   <li>其余 4 个 String：B 无 {@code @JsonInclude} ⇒ null 时输出 null ⇒ C 侧同（不加注解）✔</li>
 * </ul>
 *
 * @param id         历史条目 ID（= 服务端雪花 ID 的十进制字符串；B：{@code history_id}）
 * @param avatarUrl  头像 URL
 * @param contentType MIME 类型（空 ⇒ 归一化成 {@code image/png}）
 * @param sizeBytes  字节数
 * @param isCurrent  是否当前头像
 * @param createTime 创建时间（RFC3339 字符串，<b>进程本地时区</b>）
 */
public record AvatarHistoryItem(
        String id,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("size_bytes") long sizeBytes,
        @JsonProperty("is_current") boolean isCurrent,
        @JsonProperty("create_time") String createTime
) {
}
