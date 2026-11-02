package com.rag.common.communityfact;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * <p><b>⚠️ L1 wire 契约（D13「L1 以 B 侧 为准」）</b> ——
 * B 出处：{@code service/common/communityfact/wire.go:63-66}
 * （{@code type SubjectRef struct { Issuer string `json:"issuer"`; SubjectID string `json:"subject_id"` }}）。
 *
 * <p><b>2 个字段的 JSON tag 与顺序逐条一致</b>：{@code issuer} · {@code subject_id}。
 *
 * <p><b>⚠️ 校验语义（行为契约，不在 wire 层但与之绑定）</b>：
 * B 的 {@code ParseRTWSubject} 要求 {@code uid > 0} <b>且</b>
 * {@code FormatInt(uid,10) == id}（拒绝 {@code +1001} / {@code 01001} 这类非规范写法）
 * ⇒ 该校验落在 {@code Wire.parseRTWSubject}（门面）里，<b>不在</b>本类型上。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的嵌套类 {@code SubjectRef}（1:N 拆分 · 映射表 §1.2 第 138 行 / 决策锁 L-7）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public final class SubjectRef {
 *     @JsonProperty("issuer") public String issuer;
 *     @JsonProperty("subject_id") public String subjectId;
 *
 *     public SubjectRef() { }
 *     public SubjectRef(String issuer, String subjectId) {
 *         this.issuer = issuer;
 *         this.subjectId = subjectId;
 *     }
 * }</pre>
 */
@NoArgsConstructor
@AllArgsConstructor
public final class SubjectRef {
    @JsonProperty("issuer") public String issuer;
    @JsonProperty("subject_id") public String subjectId;
}
