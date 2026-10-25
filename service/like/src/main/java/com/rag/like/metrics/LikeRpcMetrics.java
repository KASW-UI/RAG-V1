package com.rag.like.metrics;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <h2>⚠️ L1 观测契约：指标<b>完整名</b>（= namespace_subsystem_name）</h2>
 * <pre>
 *     like-rpc_action_total
 *     like_rpc_query_cache_total
 * </pre>
 * <p>⚠️ 指标名与标签是<b>业务定义</b>，不是实现细节 ⇒ 逐字照抄 Go 侧 `internal/metrics/metrics.go`。
 * ⚠️⚠️ **`like-rpc_action_total` 用了连字符**（Go 侧 `Subsystem: "like-rpc"`，全仓唯一）。
⚠️ Prometheus 指标名正则 `[a-zA-Z_:][a-zA-Z0-9_:]*` **不允许连字符** ⇒ 该指标**不会出现在 /metrics 里**。
⚠️ **照抄拼写，不得改成 `like_rpc`** —— 改了就多出一个 Go 没有的指标（观测层差异）。
⚠️ 已登记为已知缺陷，见 `docs/第二轮深挖-契约盘点.md`。
 *
 * <p>Micrometer 承担注册与暴露；<b>指标名、标签、helper 的调用点与触发条件</b>保留为业务代码。
 */
public class LikeRpcMetrics {
}
