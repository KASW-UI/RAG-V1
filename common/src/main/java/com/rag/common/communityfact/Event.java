package com.rag.common.communityfact;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <h2>⚠️ communityfact 是 comment / favorite / like 三域<b>共享</b>的跨服务事件协议</h2>
 * <p>Go 出处：`service/common/communityfact/`（wire.go / process.go / authority_http.go / logging.go）。
 * <p>本包承担：事件信封 + 技术回执 + 主体引用 + <b>canonical JSON 哈希</b> + 投递状态机 + 权限端点。
 * <p>Go 出处：service/common/communityfact/wire.go 的 `type Event struct`。
⚠️ **跨服务事件信封 = L1**：event_id / event_type / schema_version / producer / aggregate_id / aggregate_version / operation_id / occurred_at / payload。
⚠️ **字段顺序即消费方解析依据**，且 payload 是 `json.RawMessage`（延迟反序列化）。
 */
public record Event(
) {
}
