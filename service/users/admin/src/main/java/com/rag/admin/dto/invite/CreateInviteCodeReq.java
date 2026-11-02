package com.rag.admin.dto.invite;

/**
 * <p><b>职责</b>：admin createInviteCode 用例的 HTTP 请求 DTO —— <b>空 struct</b>
 * （{@code operator_id} 从 JWT claim 解析）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；<b>零组件</b>；
 *       <b>类名</b>：B 侧 Java 类就叫 {@code CreateInviteCodeReq}（<b>只有 B 侧</b>叫 {@code CreateInviteReq}）
 *       ⇒ 类名逐字照抄 B 侧 Java，本仓的旧空壳名 {@code CreateInviteReq} 已按批准改名。</li>
 *   <li><b>语义还一样吗</b>：一样 —— B 侧也是空 struct。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/CreateInviteCodeReq.java}</li>
 * </ol>
 */
public record CreateInviteCodeReq() {
}
