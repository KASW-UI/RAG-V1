package com.rag.common.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <b>MQ 消费端 trace 包装</b> —— 对齐 Go 版的 {@code TraceConsumer}。
 *
 * <p><b>Go 出处</b>：{@code service/common/observability/mq.go}
 * <pre>
 * func TraceConsumer(ctx, tracerName, spanName string, attrs []attribute.KeyValue, fn func(ctx) error) error {
 *     ctx, span := otel.Tracer(tracerName).Start(ctx, spanName,
 *         trace.WithSpanKind(trace.SpanKindConsumer),
 *         trace.WithAttributes(attrs...),
 *     )
 *     defer span.End()
 *     err := fn(consumeCtx)
 *     duration := time.Since(start)
 *     if err != nil {
 *         if IsTimeoutError(err) { MarkTimeout(...) } else { MarkSystemError(...) }
 *         return err
 *     }
 *     MarkDurationAndSlow(...)
 *     return nil
 * }
 * </pre>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/MqConsumer.java}
 * 整段抄（L2 · D13）＋ <b>写入载体由「手写 OTel span」改为「Micrometer Observation + OTel 双载体」</b>
 * （L4 · D4 / D-03）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 形态差（RAG → 本仓）：三处，逐条说明</b>
 * ══════════════════════════════════════════════════════════════════════
 * <ol>
 *   <li><b>「span kind = CONSUMER」在 Micrometer 里没有等价物</b>（Micrometer 的 Observation
 *       没有 span kind 概念）⇒ ⇒ 本类用<b>低基数键值 {@code mq.kind=consumer}</b> 承载该语义。
 *       <br>⚠️ <b>这是本仓<b>新增</b>的一个键</b>（Go / RAG 的属性集合里没有它）⇒
 *       属于 <b>已知差异</b>，登记在 {@code docs/L3-降级清单.md}（D-03 同族）。
 *       <br>取舍理由：<b>「丢掉 kind」与「多一个键」之间</b>，前者会让「这是一次消费」这个语义
 *       <b>彻底不可见</b>，后者只是让属性集合多一项。</li>
 *   <li><b>caller 的 attrs 走 {@code highCardinalityKeyValue}</b>（只进 trace，不进指标）⇒
 *       <b>对齐 OTel 的 span attribute 语义</b>（OTel 的属性只进 trace）⇒ 避免高基数 ID
 *       （如 {@code order.id}）把指标维度打爆。</li>
 *   <li><b>「parent ctx」由 Observation 作用域自动承接</b>：在别的 Observation 作用域里调用本方法时，
 *       Micrometer 会自动把当前 observation 设为父 ⇒ ⇒ 不需要像 OTel 那样显式传 {@code Context}。</li>
 * </ol>
 *
 * <h2>⚠️ panic 语义（照抄 RAG 的 M8 修复，<b>不要</b>加 catch）</h2>
 * <p>Go 版 {@code mq.go:30} 是裸的 {@code err := fn(consumeCtx)}，<b>没有 recover</b>：
 * fn panic 时 panic 继续向上传播（span 仍由 {@code defer span.End()} 收尾，但不会被标成 system error），
 * 由调用方的 recovery 决定后果。
 * <p>⇒ 本类<b>刻意不 catch</b> {@link Throwable}：
 * <ul>
 *   <li>panic 被降级成普通 error 返回值 ⇒ 调用方无从区分「业务失败」与「进程级崩溃」；</li>
 *   <li>连 {@code OutOfMemoryError} / {@code StackOverflowError} 这类 {@code Error} 也会被吞掉。</li>
 * </ul>
 * ⇒ 语义等价论证：Go 侧这两类都不会被转成返回值，Java 侧同样不转，才是一致的。
 */
public final class MqConsumer {

    /** 观测名（对应 RAG 侧的默认 tracer 名 {@code mq-consumer}）。 */
    public static final String DEFAULT_OBSERVATION_NAME = "mq-consumer";

    /** span 名（对应 RAG 侧的默认 span 名 {@code message.consume}）。 */
    public static final String DEFAULT_SPAN_NAME = "message.consume";

    /** 「这是一次消费」的语义键（⚠️ 本仓新增，见类注释「L4 形态差」第 ① 条）。 */
    public static final String ATTR_MQ_KIND = "mq.kind";

    private MqConsumer() {
    }

    /**
     * 消费端 trace 包装。
     *
     * @param registry   观测注册表（{@code null} ⇒ <b>不产出观测</b>，但错误分类与返回值不变）
     * @param tracerName tracer 名（{@code null} / 空 ⇒ {@link #DEFAULT_OBSERVATION_NAME}）
     * @param spanName   span 名（{@code null} / 空 ⇒ {@link #DEFAULT_SPAN_NAME}）
     * @param attrs      调用方属性（{@code null} / 空 ⇒ 不写；值为 {@code null} 的条目<b>跳过</b>）
     * @param fn         消费逻辑（拿到 {@link Observation.Context} 作为「当前 span」的载体）
     * @return 业务错误（{@code null} ⇒ 消费成功）
     */
    public static Throwable traceConsumer(
            ObservationRegistry registry,
            String tracerName,
            String spanName,
            Map<String, Object> attrs,
            Function<Observation.Context, Throwable> fn) {

        final String finalName = (tracerName == null || tracerName.isEmpty())
                ? DEFAULT_OBSERVATION_NAME : tracerName;
        final String finalSpanName = (spanName == null || spanName.isEmpty())
                ? DEFAULT_SPAN_NAME : spanName;

        Observation observation = null;
        if (registry != null) {
            observation = Observation.createNotStarted(finalName, registry)
                    .contextualName(finalSpanName)
                    .lowCardinalityKeyValue(ATTR_MQ_KIND, "consumer");
            for (KeyValue kv : toKeyValues(attrs)) {
                observation.highCardinalityKeyValue(kv.getKey(), kv.getValue());
            }
            observation.start();
        }

        Observation.Context ctx = observation == null ? null : observation.getContext();
        long startNanos = System.nanoTime();
        try {
            // ⚠️ 刻意**不** catch Throwable：panic 继续向上传播（照抄 Go / RAG 的 M8 修复）
            Throwable err = fn.apply(ctx);
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            if (err != null) {
                if (ObservabilityCustomizer.isTimeoutError(err)) {
                    ObservabilityCustomizer.markTimeout(ctx, err, duration);
                } else {
                    ObservabilityCustomizer.markSystemError(ctx, err, duration);
                }
                return err;
            }
            ObservabilityCustomizer.markDurationAndSlow(ctx, duration, ObservabilityCustomizer.slowThreshold());
            return null;
        } finally {
            if (observation != null) {
                try {
                    observation.stop();
                } catch (RuntimeException ignore) {
                    // ⚠️ 观测收尾失败**不得**影响消费结果
                }
            }
        }
    }

    /**
     * 把 caller 的 {@code Map<String,Object>} 转成 Micrometer 的键值列表。
     *
     * <p>⚠️ Micrometer 的 {@link KeyValue} <b>值只有 String</b> ⇒ 非字符串值统一
     * {@code String.valueOf}（对齐 OTel 属性「值会被规范化成字符串/数字」的观感，
     * 但<b>类型信息在 Micrometer 侧丢失</b> ⇒ OTel 侧仍由
     * {@link ObservabilityCustomizer} 的双载体写入路径保类型）。
     * <p>⚠️ <b>值为 {@code null} 的条目跳过</b>（照抄 RAG 的
     * {@code if (e.getValue() == null) continue;}）。
     */
    private static List<KeyValue> toKeyValues(Map<String, Object> attrs) {
        List<KeyValue> result = new ArrayList<>();
        if (attrs == null || attrs.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            result.add(KeyValue.of(e.getKey(), String.valueOf(e.getValue())));
        }
        return result;
    }
}
