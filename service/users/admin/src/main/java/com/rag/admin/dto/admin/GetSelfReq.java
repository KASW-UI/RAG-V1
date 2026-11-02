package com.rag.admin.dto.admin;

/**
 * <p><b>职责</b>：admin getSelf 用例的 HTTP 请求 DTO —— <b>空 struct</b>（uid 从 JWT claim 解析）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；<b>零组件</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— B 侧也是空 struct（uid 走 JWT claim，不在 body 里）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/GetSelfReq.java}</li>
 * </ol>
 */
public record GetSelfReq() {
}
