package com.rag.like.contract;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <p>Go 出处：service/like/rpc/internal/types/types.go。like 域的 Kafka 消息载荷
⚠️ **跨服务消息契约 = L1**（字段名与顺序即消费方解析依据）。
 */
public record KafkaLikeMsg(
) {
}
