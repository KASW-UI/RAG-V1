package com.rag.common.communityfact;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>⚠️ L1 wire 契约（D13「L1 以 B 侧 为准」）</b> ——
 * B 出处：{@code service/common/communityfact/wire.go:53-61}
 * （{@code type TechnicalReceipt struct { EventID string `json:"event_id"`; ... }}）。
 *
 * <p><b>7 个字段的 JSON tag 与顺序逐条一致</b>：
 * {@code event_id} · {@code producer} · {@code technical_status} · {@code receipt_id} ·
 * {@code input_hash} · {@code offset} · {@code received_at}。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的嵌套类 {@code TechnicalReceipt}（1:N 拆分 · 映射表 §1.2 第 137 行 / 决策锁 L-7）。
 */
public final class TechnicalReceipt {
    @JsonProperty("event_id") public String eventId;
    @JsonProperty("producer") public String producer;
    @JsonProperty("technical_status") public String technicalStatus;
    @JsonProperty("receipt_id") public String receiptId;
    @JsonProperty("input_hash") public String inputHash;
    @JsonProperty("offset") public long offset;
    @JsonProperty("received_at") public String receivedAt;

    public TechnicalReceipt() {}
}
