package com.rag.task.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>两个 sink 的 OTEL span 属性</b> —— 对应 Go 端
 * {@code internal/sink/like_sink.go:147-163} 的 {@code taskMessageAttrs} 与
 * {@code taskFlushAttrs}（Go 里它们是<b>同一个包级</b>的函数，所以 article sink 直接复用了
 * like sink 的定义）。</p>
 *
 * <p>⚠️ attribute 的<b>名字与取值</b>属于可观察行为（Jaeger 里的 span 结构）：
 * <ul>
 *   <li>{@code messaging.system} = {@code kafka}；</li>
 *   <li>{@code messaging.destination} —— ⚠️ <b>传的不是 topic 名</b>，
 *       而是 Go 源码里写死的一对逻辑名：用户路 {@code user_like_sink}、
 *       文章路 {@code article_like_sink}；</li>
 *   <li>{@code messaging.operation} = {@code consume} 或 {@code flush}；</li>
 *   <li>consume 带 {@code messaging.message.key}，flush 带 {@code batch.size}（int）。</li>
 * </ul>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mq} → {@code com.rag.task.messaging}
 *             （D7 包压平：Kafka 相关的落点归 {@code messaging/}）；</li>
 *         <li><b>可见性</b>：B 侧是包级私有（{@code final class}），本类是
 *             {@code public} —— <b>只放宽了可见性，行为零变化</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 4 个 attribute 的名字、取值、
 *       写入顺序（{@link LinkedHashMap} 保证顺序可观察）逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskTraceAttributes.java:21-25（类声明）
 * B 出处：…/mq/TaskTraceAttributes.java:28-35（message = Go taskMessageAttrs）
 * B 出处：…/mq/TaskTraceAttributes.java:38-45（flush = Go taskFlushAttrs）
 *       </pre></li>
 * </ol>
 */
public final class TaskTraceAttributes {

    private TaskTraceAttributes() {
        // 纯静态工具，禁止实例化
    }

    /** Go {@code taskMessageAttrs}：消费一条消息时的 span 属性。 */
    public static Map<String, Object> message(String destination, String key) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("messaging.system", "kafka");
        attrs.put("messaging.destination", destination);
        attrs.put("messaging.operation", "consume");
        attrs.put("messaging.message.key", key);
        return attrs;
    }

    /** Go {@code taskFlushAttrs}：刷盘一批时的 span 属性。 */
    public static Map<String, Object> flush(String destination, int batchSize) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("messaging.system", "kafka");
        attrs.put("messaging.destination", destination);
        attrs.put("messaging.operation", "flush");
        attrs.put("batch.size", batchSize);
        return attrs;
    }
}
