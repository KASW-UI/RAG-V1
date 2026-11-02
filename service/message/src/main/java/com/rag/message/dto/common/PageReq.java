package com.rag.message.dto.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：分页请求 —— 对应 Go {@code service/message/api/internal/types/types.go} 的 {@code PageReq}。
 *
 * <p><b>三个字段全部 {@code ,optional}</b> ⇒ 永不做必填校验，缺席即零值
 * （Go 的零值 struct：offset=0 / limit=0 / unread_only=false）。
 *
 * <p><b>为什么用原始 {@code int} 而不是 {@code Integer}</b>：本 DTO 没有「缺席 vs 显式 null」
 * 的语义差 —— 两个都等于零值。message-rpc 的
 * {@code MessageProperties.list.default-limit=20 / max-limit=100} 负责把 0 归一化成默认分页，
 * 对应 Go {@code rpc/etc/message.yaml} 的 {@code List { DefaultLimit: 20, MaxLimit: 100 }}。
 * 归一化<b>不在 api 层做</b>（Go 侧也在 rpc 侧做）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.common}（D7 + D11）。
 *       字段集合、顺序、类型、注解逐条照抄 B 侧。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 三个组件仍是原始类型（{@code int} / {@code boolean}），
 *       「缺席 = 零值」的口径不变；JSON 键名逐条照抄
 *       （{@code offset} / {@code limit} / {@code unread_only}）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/PageReq.java:31-61}</li>
 * </ol>
 *
 * @param offset     偏移量（可选，缺席 = 0）
 * @param limit      每页条数（可选，缺席 = 0；归一化在 message-rpc 侧）
 * @param unreadOnly 是否只看未读（可选，缺席 = false）
 */
public record PageReq(
        @JsonProperty("offset") int offset,
        @JsonProperty("limit") int limit,
        @JsonProperty("unread_only") boolean unreadOnly) {
}
