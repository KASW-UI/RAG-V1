package com.rag.admin.metrics;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <h2>⚠️ L1 观测契约：指标<b>完整名</b>（= namespace_subsystem_name）</h2>
 * <pre>
 *     admin_api_intercept_total
 * </pre>
 * <p>⚠️ 指标名与标签是<b>业务定义</b>，不是实现细节 ⇒ 逐字照抄 Go 侧 `internal/metrics/metrics.go`。
 *
 * <p>Micrometer 承担注册与暴露；<b>指标名、标签、helper 的调用点与触发条件</b>保留为业务代码。
 */
public class AdminApiMetrics {
}
