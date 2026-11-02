package com.rag.user.dto.avatar;

import java.util.List;

/**
 * <p><b>头像历史列表响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/GetAvatarHistoryResp.java}
 * （可变 class ＋ {@code private List<AvatarHistoryItem> list}）。
 *
 * <p><b>语义等价论证</b>：B 无 {@code @JsonProperty} ⇒ JSON 键 = {@code "list"}；
 * B 无 {@code @JsonInclude} ⇒ null 时输出 {@code "list":null}
 * ⇒ C 侧 record 分量名取 {@code list}、不加 {@code @JsonInclude} ⇒ <b>wire 逐条一致</b>。
 *
 * @param list 头像历史条目列表（B 侧 limit 恒为 30，见 {@code UserApiService#getAvatarHistory}）
 */
public record GetAvatarHistoryResp(
        List<AvatarHistoryItem> list
) {
}
