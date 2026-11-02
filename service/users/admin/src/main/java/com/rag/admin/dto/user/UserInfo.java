package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * <p><b>职责</b>：用户信息 DTO —— 5 字段（uid / status / username / email / extra_info）；
 * 被 {@code GetUserResp} / {@code UpdateUserResp} / {@code GetUserListResp} 嵌套。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.user}（D7 + D11）。字段集合、顺序、注解逐条照抄。</li>
 *   <li><b>语义还一样吗</b>：一样。<b>唯一有意的类型差别</b>：B 侧字段是 {@code Long uid}，
 *       但 getter 返回 {@code String} ⇒ JSON 里 {@code uid} 是<b>字符串</b>
 *       （§D-C：L1 事实，必须复刻）⇒ 本组件类型直接是 {@code String}。
 *       ⚠️ HTTP DTO <b>不暴露</b> {@code score}（proto 侧的 {@code UserInfo} 多一个 score 字段，但 B 侧 HTTP 面没有）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/UserInfo.java}</li>
 * </ol>
 *
 * <p><b>⚠️ 不要给 {@code uid} / {@code status} / {@code username} 加 {@code NON_NULL}</b> ——
 * B 侧这三个字段<b>没有</b>该注解（只有 {@code email} / {@code extra_info} 有）⇒ 逐字照抄。
 *
 * @param uid       用户 UID，<b>序列化为字符串</b>（B 侧 {@code getUid()} 返回 {@code String}）
 * @param status    用户状态（primitive {@code long} ⇒ 永远出现在 JSON 里）
 * @param username  用户名
 * @param email     邮箱（B 侧带 {@code NON_NULL}；B 侧 service 层<b>空串时不 set</b> ⇒ 字段整个省略）
 * @param extraInfo 扩展信息（B 侧带 {@code NON_NULL} + {@code @JsonProperty("extra_info")}）
 */
public record UserInfo(
        @JsonProperty("uid") String uid,
        long status,
        String username,
        @JsonInclude(JsonInclude.Include.NON_NULL) String email,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("extra_info") Map<String, String> extraInfo) {
}
