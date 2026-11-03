package com.rag.like.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * like 域的 <b>MQ 消费计数</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/metrics/LikeMqMetrics.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无。包名由 {@code com.rag.like.like.rpc.metrics} 压平为
 *       {@code com.rag.like.metrics}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。指标名 {@code like_mq_consume_total} 与两个 label
 *       （{@code action} / {@code result}）逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 指标名（GAP-8：必须按 B 侧修正）</h2>
 * <p>C 侧空壳 javadoc 写的是<b>连字符</b>版 {@code like-rpc_action_total} ——
 * 连字符不符合 Prometheus 指标名正则 ⇒ 那个名字不会出现在 {@code /metrics}。
 * ⇒ 本类按 B 侧写<b>下划线</b>版 {@code like_mq_consume_total}。
 *
 * <p><b>label 名是 {@code action}（不是 {@code stage}）</b>：Go 侧
 * {@code LikeConsumeCount} 的 label 定义就是 {@code action}，
 * 而 Java 侧的<b>形参名</b>叫 {@code stage} —— <b>形参名不进入 wire</b>，
 * 指标上的 label 名仍是 {@code action}（由 {@code .tags("action", stage, …)} 决定）。
 *
 * <p><b>{@code result} 的取值域</b>（B 侧消费侧逐条照抄）：
 * {@code json_error} / {@code empty_msg_id} / {@code outbox_error} /
 * {@code tx_error} / {@code committed}。
 */
@Component
public class LikeMqMetrics {

    private final MeterRegistry registry;

    public LikeMqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 对应 B 侧 {@code LikeConsumeCount.WithLabelValues(action, result).Inc()}。
     *
     * @param stage  消费阶段（Go 侧固定传 {@code "receive"}）
     * @param result 消费结果（见类注释的取值域）
     */
    public void consume(String stage, String result) {
        Counter.builder("like_mq_consume_total")
                .description("Total number of like message consumed from Kafka")
                .tags("action", stage, "result", result)
                .register(registry)
                .increment();
    }
}
