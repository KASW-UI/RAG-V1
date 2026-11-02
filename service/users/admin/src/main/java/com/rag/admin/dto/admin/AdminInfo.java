package com.rag.admin.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * <p><b>职责</b>：管理员信息 DTO —— 4 字段（uid / username / email / extra_info）；被
 * {@code GetSelfResp} / {@code UpdateSelfResp} 嵌套。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.admin}（D7 + D11）。字段集合、顺序、注解逐条照抄。</li>
 *   <li><b>语义还一样吗</b>：一样。<b>唯一有意的类型差别</b>：B 侧字段是 {@code Long uid}，
 *       但 getter 返回 {@code String}（{@code uid == null ? null : uid.toString()}）
 *       ⇒ JSON 里 {@code uid} 是<b>字符串</b>。本 record 的 {@code uid} 组件直接就是
 *       {@code String}，序列化结果与 B 侧<b>逐字节一致</b>（§D-C：uid 字符串化是 L1 事实，必须复刻）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/AdminInfo.java}</li>
 * </ol>
 *
 * <p><b>⚠️ 不要给 {@code uid} 加 {@code @JsonInclude(NON_NULL)}</b> —— B 侧
 * {@code AdminInfo.uid} <b>没有</b>该注解（只有 username / email / extra_info 有）⇒ 逐字照抄。
 *
 * <p><b>⚠️ 不要给 {@code username} / {@code email} 加 {@code @NotBlank}</b> —— B 侧空串合法
 * （见 D-01 降级：标量必填一律用 {@code @NotNull}）。
 *
 * @param uid       Snowflake UID，<b>序列化为字符串</b>（B 侧 {@code getUid()} 返回 {@code String}）
 * @param username  管理员用户名（B 侧带 {@code NON_NULL}）
 * @param email     邮箱（B 侧带 {@code NON_NULL}；B 侧 service 层无条件 set ⇒ 空值也输出 {@code ""}）
 * @param extraInfo 扩展信息（B 侧带 {@code NON_NULL} + {@code @JsonProperty("extra_info")}）
 */
public record AdminInfo(
        @JsonProperty("uid") String uid,
        @JsonInclude(JsonInclude.Include.NON_NULL) String username,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo) {
}
