package com.rag.user.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * <p><b>注册（创建用户）响应体</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/RegisterResp.java}
 * （{@code @JsonProperty("uid") private Long uid} ＋ {@code public String getUid() { return uid == null ? null : uid.toString(); }}）。
 *
 * <p><b>⚠️ L1 契约：{@code uid} 在 wire 上是<b>字符串</b></b>（理由同 {@code UserInfo}）
 * ⇒ C 侧保留 {@code Long} 分量 ＋ {@link ToStringSerializer} ⇒ 输出 {@code "uid":"123"}，与 B 逐条一致。
 *
 * <p><b>类名差异（L4 表达层）</b>：B 侧 HTTP DTO 叫 {@code RegisterResp}，proto 侧叫 {@code CreateUserResp}
 * ⇒ C 侧取 proto 名 ⇒ JSON 键与字段集不变。
 *
 * @param uid 新建用户的 UID（服务端雪花 ID；wire 上为十进制字符串）
 */
public record CreateUserResp(
        @JsonProperty("uid") @JsonSerialize(using = ToStringSerializer.class) Long uid
) {
}
