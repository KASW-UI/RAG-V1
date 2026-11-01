package com.rag.common.communityfact;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>⚠️ L1 wire 契约（D13「L1 以 Go 为准」）</b> ——
 * Go 出处：{@code service/common/communityfact/wire.go:68-74}
 * （{@code type AuthorityFact struct { Event Event `json:"event"`; SubjectRef SubjectRef `json:"subject_ref"`; ... }}）。
 *
 * <p><b>5 个字段的 JSON tag 与顺序逐条一致</b>：
 * {@code event} · {@code subject_ref} · {@code predecessor_event_id} ·
 * {@code technical_receipt} · {@code source_event_hash}。
 *
 * <p><b>⚠️ {@code predecessor_event_id} 带 {@code omitempty}</b>（Go 侧<b>唯一</b>一个带 omitempty 的字段）
 * ⇒ ⇒ Java 侧对应 {@code @JsonInclude(JsonInclude.Include.NON_EMPTY)}
 * ⇒ ⇒ 空串/缺失时该键<b>不出现</b>（与 Go 一致）；其余 4 个字段<b>不带</b> omitempty
 * ⇒ ⇒ <b>不得</b>给它们加 {@code @JsonInclude}（否则键会消失 ⇒ 可观察形状不等价）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的嵌套类 {@code AuthorityFact}（1:N 拆分 · 映射表 §1.2 第 139 行 / 决策锁 L-7）。
 */
public final class AuthorityFact {
    @JsonProperty("event") public Event event;
    @JsonProperty("subject_ref") public SubjectRef subjectRef;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("predecessor_event_id") public String predecessorEventId;

    @JsonProperty("technical_receipt") public TechnicalReceipt technicalReceipt;
    @JsonProperty("source_event_hash") public String sourceEventHash;

    public AuthorityFact() {}
}
