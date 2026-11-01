package com.rag.common.communityfact;

/**
 * <p><b>⚠️ L1 契约（取值 0/1/2/3 · D13「L1 以 B 侧 为准」）</b> ——
 * B 出处：{@code service/common/communityfact/wire.go:33-38}
 * （{@code const ( DeliveryPending int32 = iota; DeliveryAccepted; DeliveryFailed; DeliveryBlocked )}）。
 *
 * <p><b>取值逐条一致</b>：{@code DELIVERY_PENDING=0} · {@code DELIVERY_ACCEPTED=1} ·
 * {@code DELIVERY_FAILED=2} · {@code DELIVERY_BLOCKED=3}。
 *
 * <p><b>⚠️ {@code fromCode} 的未知值回落是 {@code DELIVERY_PENDING}</b>（不是抛异常）
 * ⇒ 与 B 的 {@code iota} 常量表语义一致（B 侧没有「反查」函数，
 * 未知 code 由调用方按常量表自行处理）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的嵌套枚举 {@code DeliveryStatus}（1:N 拆分 · 映射表 §1.2 第 140 行 / 决策锁 L-7 第 2 条：
 * 「{@code DeliveryStatus} 常量块拆成独立枚举文件」）。
 */
public enum DeliveryStatus {
    DELIVERY_PENDING(0),
    DELIVERY_ACCEPTED(1),
    DELIVERY_FAILED(2),
    DELIVERY_BLOCKED(3);

    private final int code;

    DeliveryStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DeliveryStatus fromCode(int code) {
        for (DeliveryStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return DELIVERY_PENDING;
    }
}
