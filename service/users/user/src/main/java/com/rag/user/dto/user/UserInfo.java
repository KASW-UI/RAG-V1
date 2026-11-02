package com.rag.user.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.Map;

/**
 * <p><b>用户信息（HTTP 层）</b> —— immutable record（决策 D5）。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/UserInfo.java}
 * （可变 class ＋ {@code @JsonProperty("uid")} ＋ {@code @JsonInclude(NON_NULL)} 的 email / extra_info）。
 *
 * <p><b>⚠️ L1 契约：{@code uid} 在 wire 上是<b>字符串</b>，不是数字</b>
 * <pre>
 *   B 侧：{@code @JsonProperty("uid") private Long uid;} ＋ {@code public String getUid() { return uid == null ? null : uid.toString(); }}
 *   ⇒ Jackson 用<b>访问器</b>序列化 ⇒ 输出 {@code "uid":"123"}（<b>带引号的字符串</b>）
 *   ⇒ 客户端若按数字解析会失败 ⇒ 这是 L1 级 wire 形状，<b>不可</b>「顺手改成数字」。
 * </pre>
 * ⇒ C 侧保留 {@code Long} 分量 ＋ {@link ToStringSerializer} ⇒ 输出同样是字符串，<b>逐条一致</b>。
 *
 * <p><b>其余字段的等价论证</b>：
 * <ul>
 *   <li>{@code score}：B 的 getter 返回 {@code score == null ? 0L : score} ⇒ <b>恒为数字</b>
 *       ⇒ C 侧用原始 {@code long} 分量（缺省 0）⇒ 恒输出数字 ✔</li>
 *   <li>{@code username}：无注解 ⇒ 键 = {@code "username"} ✔</li>
 *   <li>{@code email}：B 带 {@code @JsonInclude(NON_NULL)} ⇒ null 时键消失 ⇒ C 侧照抄 ✔</li>
 *   <li>{@code extraInfo}：B 带 {@code @JsonProperty("extra_info")} ＋ {@code @JsonInclude(NON_NULL)}
 *       ⇒ C 侧两条都照抄 ✔</li>
 * </ul>
 *
 * @param uid       用户 UID（wire 上为十进制字符串）
 * @param score     积分（HTTP 层恒为 0 —— B 侧 HTTP 层不填 score，见 D-011）
 * @param username  用户名
 * @param email     邮箱（可为 null）
 * @param extraInfo 扩展信息（可为 null）
 */
public record UserInfo(
        @JsonProperty("uid") @JsonSerialize(using = ToStringSerializer.class) Long uid,
        long score,
        String username,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo
) {
}
