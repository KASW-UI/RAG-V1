package com.rag.hot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * <p><b>hot 域 rpc 面的 2 个业务指标</b> —— 对应 B 侧
 * {@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/metrics/HotRpcMetrics.java}。
 *
 * <p><b>B 出处</b>：B 侧 {@code HotRpcMetrics.java}
 * （1-83 行；类声明 {@code :33-34}；两个指标名常量 {@code :37} / {@code :40}；
 * 两个 status 常量 {@code :42-43}；{@code eventConsumed} {@code :58-64}；
 * {@code redisSyncSuccess} {@code :67-69}；{@code redisSyncFail} {@code :71-74}；
 * {@code redisSync} {@code :76-82}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.hot.hot.rpc.metrics} →
 *       {@code com.rag.hot.metrics}，D7 包压平 + D1 合并进程）＋ <b>类名沿用</b>。
 *       方法体<b>逐字照抄</b>：两个指标名、两个 status 取值、三个方法的调用次序。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>渲染后的 Prometheus 名与 B 侧逐字一致：
 *             {@code article_hot_kafka_consume_total{type}} /
 *             {@code article_hot_redis_sync_total{status}}；</li>
 *         <li><b>标签取值域</b>逐条未动（见下方 javadoc）；</li>
 *         <li><b>「先计数再查分区」的次序</b>逐条保留（见 {@link #eventConsumed}）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>标签取值域</b>：
 * <ul>
 *   <li>{@code type} = {@code event.Type}，且只在「权重校验通过之后」才 Inc
 *       ⇒ 实际出现的取值域 ⊆ 配置里 {@code Interaction.Weights} 的 {@code Name} 集合。
 *       未知或零权重的 type 会被 drop，永不出现在指标里。</li>
 *   <li>{@code status} = {@code "success"} / {@code "fail"}，对应 B 侧 {@code syncToRedis} 的两条分支。</li>
 * </ul>
 *
 * <p>⚠️ B 侧还有 {@code internal/metrics/metrics.go} 的 {@code InitMetrics}（:9091 promhttp），
 * <b>全仓零调用点</b>（实测 grep 确认）⇒ 死代码，<b>不迁移</b>。
 */
@Component
public class HotRpcMetrics {

    /** 对应 B 侧 {@code article_hot_kafka_consume_total labels: [type]}。 */
    private static final String KAFKA_CONSUME_TOTAL = "article_hot_kafka_consume_total";

    /** 对应 B 侧 {@code article_hot_redis_sync_total labels: [status]}。 */
    private static final String REDIS_SYNC_TOTAL = "article_hot_redis_sync_total";

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAIL = "fail";

    private final MeterRegistry registry;

    /**
     * 构造器注入。
     *
     * @param registry Micrometer 注册表
     */
    public HotRpcMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 对应 B 侧 {@code eventConsumeTotal.Inc(event.Type)}。
     *
     * <p>⚠️ 调用位置语义敏感：B 侧在<b>三类校验全部通过之后</b>才 Inc，
     * 且<b>早于</b> {@code hkMap} 查找 ⇒ 即分区不在 map 时<b>仍然计数</b>，
     * 只是不入 sketch。调用方必须保持该次序。
     *
     * @param type 事件类型
     */
    public void eventConsumed(String type) {
        Counter.builder(KAFKA_CONSUME_TOTAL)
                .description("Total number of consumed hot events")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /** 对应 B 侧 {@code redisSyncTotal.Inc("success")}。 */
    public void redisSyncSuccess() {
        redisSync(STATUS_SUCCESS);
    }

    /** 对应 B 侧 {@code redisSyncTotal.Inc("fail")}（两处失败分支）。 */
    public void redisSyncFail() {
        redisSync(STATUS_FAIL);
    }

    /**
     * 计数 Redis 同步结果。
     *
     * @param status {@code success} / {@code fail}
     */
    private void redisSync(String status) {
        Counter.builder(REDIS_SYNC_TOTAL)
                .description("Total number of redis sync operations")
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
