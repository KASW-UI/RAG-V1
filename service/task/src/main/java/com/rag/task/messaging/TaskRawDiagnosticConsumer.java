package com.rag.task.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * <p><b>原始 topic 的诊断消费者</b> —— 1:1 对应 Go 端 {@code internal/Init/initKafkaRaw.go:12-18} 的
 * {@code PrintConsumer} 与 {@code StartTaskKafkaRaw}。</p>
 *
 * <h2>⚠️ 它是「什么都不做」，但<b>不能删</b></h2>
 * <p>Go 的注释写得很直接：
 * <pre>
 * // 这个是用来测试哪一步的kafka死掉了
 * type PrintConsumer struct{}
 * func (c PrintConsumer) Consume(ctx, key, val string) error {
 *     //log.Printf("got msg key=%s val=%s\n", key, val)   ← 整行被注释
 *     return nil
 * }
 * </pre>
 *
 * <p>它的行为是「占着一个 consumer group 持续消费 in-topic 并确认位点」。
 * 这会改变 Kafka 侧的可观察状态：{@code task.rpc.consumer31} 这个 group
 * <b>会持有位点</b>。删掉它 ⇒ 该 group 不存在、位点不推进，
 * 而 topic 保留时间仍受其它 group 影响 —— 属于状态变化，不是纯重构。
 * 保留它的成本极低（一个空方法），删除的收益也不明确。</p>
 *
 * <p>⚠️ <b>不要"顺手"把打印打开</b>：那会让生产环境每条消息打一行日志。
 * 需要临时排查时应改用 {@code logging.level.com.rag.task} 调整，或临时改代码。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mq} → {@code com.rag.task.messaging}（D7）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 「绝不抛异常」「只留一条 debug 日志」
 *       「{@code auto.offset.reset=latest} 用点分形式」三条逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskRawDiagnosticConsumer.java:33-36（类声明）
 * B 出处：…/mq/TaskRawDiagnosticConsumer.java:44-62（listener：恒正常返回 + debug 日志）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskRawDiagnosticConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskRawDiagnosticConsumer.class);

    /**
     * 消费一条原始消息并确认位点。
     *
     * <p>⚠️ <b>绝不抛异常</b>：Go 的 {@code PrintConsumer.Consume} 恒返回 {@code nil}
     * ⇒ kq 照常 {@code CommitMessages}。这里正常返回 ⇒ 位点照常提交，等价。</p>
     */
    @KafkaListener(id = "task-raw-diagnostic",
            topics = "${task.kafka.in-topic}",
            groupId = "${task.kafka.group-kafka-raw}",
            // ⚠️ kq 硬编码 Offset: "latest"（initKafkaRaw.go:32）
            //
            // 🔴 2026-10-24 修正：必须是**点分**的 `auto.offset.reset`。
            //    `@KafkaListener(properties=...)` 不走 Boot 松散绑定，原样传给 Kafka；
            //    连字符形式 `auto-offset-reset` **不是 Kafka 的合法配置名 ⇒ 被直接忽略**
            //    ⇒ 本处写的 latest 此前**从未生效**（「碰巧对」：全局默认恰好也是 latest）。
            //    详见 {@link TaskRawLogRouter} 同名注解处的完整说明。
            properties = "auto.offset.reset=latest",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        String value = record.value();
        // Go 侧唯一那行 log.Printf 是被注释掉的；这里只留一个 debug，
        // 不在默认 INFO 级别输出，与 Go 的行为对齐。
        log.debug("task raw diagnostic consume: key={} valueSize={}", record.key(),
                value == null ? 0 : value.length());
    }
}
