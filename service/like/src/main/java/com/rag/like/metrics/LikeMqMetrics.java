package com.rag.like.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <h2>⚠️ L1 观测契约：指标<b>完整名</b>（= namespace_subsystem_name）</h2>
 * <pre>
 *     like_mq_consume_total
 * </pre>
 * <p>指标名与标签是<b>业务定义</b>，不是实现细节 ⇒ 逐字照抄 Go 侧 <code>internal/metrics/metrics.go</code>。
 *
 * <p>Micrometer 承担注册与暴露；<b>指标名、标签、helper 的调用点与触发条件</b>保留为业务代码。
 *
 * <h2>⚠️ 已知缺陷必须照抄，不得「顺手修正」</h2>
 * <p>like 域另有一个指标 <code>like-rpc_action_total</code> 用<b>连字符</b> ⇒ Prometheus 正则不允许
 * ⇒ 该指标<b>不会出现在 <code>/metrics</code></b>。这是 Go 侧的真实行为 ⇒ 照抄，不修正
 * （改了 = 引入 Go 没有的可观察差异）。
 *
 * @see com.rag.like.metrics.LikeRpcMetrics
 */
public class LikeMqMetrics {
}
