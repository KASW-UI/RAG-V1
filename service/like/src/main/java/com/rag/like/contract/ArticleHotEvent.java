package com.rag.like.contract;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <p>B 出处：service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/ArticleHotEvent.java。like → hot 的跨域事件
⚠️ **跨服务消息契约 = L1**（字段名与顺序即消费方解析依据）。
 */
public record ArticleHotEvent(
) {
}
