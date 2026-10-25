package com.rag.admin.metrics;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <h2>⚠️ L1 观测契约：指标<b>完整名</b>（= namespace_subsystem_name）</h2>
 * <pre>
 *     admin_rpc_login_total
 *     admin_rpc_high_risk_action_logal
 * </pre>
 * <p>⚠️ 指标名与标签是<b>业务定义</b>，不是实现细节 ⇒ 逐字照抄 Go 侧 `internal/metrics/metrics.go`。
 * ⚠️⚠️ **`high_risk_action_logal` 是拼写错误**（应为 `log` / `audit`）。
⚠️ **照抄，不得修正** —— 指标名是监控看板的依赖项，改了会导致既有看板断裂。
⚠️ 已登记为已知缺陷，见 `docs/第二轮深挖-契约盘点.md`。
 *
 * <p>Micrometer 承担注册与暴露；<b>指标名、标签、helper 的调用点与触发条件</b>保留为业务代码。
 */
public class AdminRpcMetrics {
}
