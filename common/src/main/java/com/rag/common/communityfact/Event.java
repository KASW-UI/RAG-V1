package com.rag.common.communityfact;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>⚠️ L1 wire 契约（D13「L1 以 Go 为准」）</b> ——
 * Go 出处：{@code service/common/communityfact/wire.go:41-51}
 * （{@code type Event struct { EventID string `json:"event_id"`; ... }}）。
 *
 * <p><b>9 个字段的 JSON tag 与顺序逐条一致</b>：
 * {@code event_id} · {@code event_type} · {@code schema_version} · {@code producer} ·
 * {@code aggregate_id} · {@code aggregate_version} · {@code operation_id} ·
 * {@code occurred_at} · {@code payload}。
 *
 * <p><b>⚠️ 两条硬约束</b>：
 * <ol>
 *   <li><b>{@code aggregate_version} 是 {@code int64} <b>非指针</b></b> ⇒ Java 侧用
 *       {@code long}（不是 {@code Long}）⇒ 不存在「字段缺省」这回事；</li>
 *   <li><b>{@code payload} 在 Go 侧是 {@code json.RawMessage} ⇒ <b>逐字输出</b></b>
 *       ⇒ Java 侧用 {@code Object}（不解析成结构体）⇒ <b>原样透传</b>
 *       ⇒ ⚠️ 这条与 {@code DRIFT-CF-004}（未决）相关：不得擅自改成解析后的 POJO。</li>
 * </ol>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的嵌套类 {@code Event}（1:N 拆分 · 映射表 §1.2 第 136 行 / 决策锁 L-7）
 * —— L1 契约层以 Go 为准 · 本类 Jackson 写法在 RAG 侧<b>本就是 Jackson 2</b> ⇒ 零回改。
 */
public final class Event {
    @JsonProperty("event_id") public String eventId;
    @JsonProperty("event_type") public String eventType;
    @JsonProperty("schema_version") public int schemaVersion;
    @JsonProperty("producer") public String producer;
    @JsonProperty("aggregate_id") public String aggregateId;
    @JsonProperty("aggregate_version") public long aggregateVersion;
    @JsonProperty("operation_id") public String operationId;
    @JsonProperty("occurred_at") public String occurredAt;
    @JsonProperty("payload") public Object payload;

    public Event() {} // 默认需要无参构造
}
