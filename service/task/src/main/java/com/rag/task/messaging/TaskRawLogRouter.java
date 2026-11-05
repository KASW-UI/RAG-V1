package com.rag.task.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * <p><b>原始日志路由</b> —— 1:1 对应 Go 端 {@code internal/Init/initGoKa.go:60-70} 的
 * {@code process} 与 {@code startTaskGoKa} 里的<b>主 goka group</b>（{@code initGoKa.go:123-128}）。</p>
 *
 * <h2>Go 的组定义</h2>
 * <pre>
 * goka.DefineGroup(
 *     goka.Group(groupTask),                                            // task.rpc.consumer41
 *     goka.Input(goka.Stream(inTopicTask), new(codec.Bytes), process),   // task.logs.raw
 *     goka.Output(goka.Stream(rawTopicTaskUser), new(codec.Bytes)),      // task.logs.raw.User
 *     goka.Output(goka.Stream(rawTopicTaskArticle), new(codec.Bytes)),  // task.logs.raw.Article
 * )
 * </pre>
 *
 * <h2>🔴 关键：value 是<b>原始 raw 字节</b>，不是重新序列化的对象</h2>
 * <p>Go {@code ctx.Emit(stream, key, raw)} 里 {@code raw} 就是 {@code msg.([]byte)}，
 * 即 Kafka 上的原值。<b>原样透传</b>意味着下游收到的 JSON 与上游逐字节相同
 * （含上游的多余字段、字段顺序、空格）。
 * 重新序列化会改变字节序列 ⇒ 下游的 hash / 去重 / 审计都可能出现差异。</p>
 *
 * <h2>🔴 关键：解析失败<b>不阻断</b> emit</h2>
 * <p>Go 只 {@code log.Println(err)} 就继续用零值 {@code logEvent} 发出去
 * （两个 key 都是空串）。见 {@link TaskLogEvent} 的类注释。</p>
 *
 * <h2>⚠️ goka 没有「输出即成功」的确认语义</h2>
 * <p>goka 的 processor 内部缓冲 + 后台提交；{@code ctx.Emit} 本身不返回错误。
 * Spring Kafka 的 {@code send()} 也是异步（只表示"交给 producer"）。
 * 两者的失败都<b>不会</b>让 listener 抛异常 ⇒ 位点照常提交。这与 Go 一致。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mq} → {@code com.rag.task.messaging}（D7）；</li>
 *         <li>{@code TaskKafkaTopics} 从 {@code config/} 移到 {@code messaging/}（D7：Kafka 相关落点归一处）⇒
 *             本类的 import 随之调整，<b>配置键名与取值逐字未动</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 「value 用 raw 原文」「解析失败仍 emit」
 *       「emit 失败只记日志不抛」「{@code auto.offset.reset=earliest} 用点分形式」
 *       四条逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskRawLogRouter.java:41-56（类声明 + 构造）
 * B 出处：…/mq/TaskRawLogRouter.java:65-95（listener + route：value 用 raw 原文）
 * B 出处：…/mq/TaskRawLogRouter.java:97-107（emit：失败只记日志）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskRawLogRouter {

    private static final Logger log = LoggerFactory.getLogger(TaskRawLogRouter.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskKafkaTopics topics;
    private final ObjectMapper objectMapper;

    public TaskRawLogRouter(KafkaTemplate<String, String> kafkaTemplate,
                            TaskKafkaTopics topics,
                            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费一条原始日志，按 user_id / article_id 各转发一份到两个 raw topic。
     *
     * <p>⚠️ <b>绝不抛异常</b>：Go 的 {@code process} 没有返回值，失败只打日志。
     * 一旦这里抛异常，Spring 就不提交位点 ⇒ 该消息会被反复重投，
     * 而 Go 侧是会正常走完的 —— 那是新增行为。</p>
     */
    @KafkaListener(id = "task-goka-router",
            topics = "${task.kafka.in-topic}",
            groupId = "${task.kafka.group-goka}",
            // ⚠️ Go 的 goka 三个 processor 全部硬编码 cfg.Consumer.Offsets.Initial = sarama.OffsetOldest
            //    （initGoKa.go:131, 173, 207），**与** kq 那三处的 "latest" 相反。
            //    这里是 goka 的第一个 group ⇒ 必须 earliest，否则历史消息永远不参与计数。
            //
            // 🔴 2026-10-24 修正：这里**原来写的是 `auto-offset-reset=earliest`（连字符）**，
            //    而 **Kafka 的合法配置名是点分的 `auto.offset.reset`** ——
            //    `@KafkaListener(properties=...)` **不走 Spring Boot 的松散绑定**，
            //    原样传给 Kafka ⇒ Kafka 认不出连字符形式 ⇒ **直接忽略**！
            //    实测证据（真 Kafka，RAG 侧 consumer 的 config dump）：
            //      auto.offset.reset = latest
            //      AbstractConfig : These configurations '[auto-offset-reset]' were supplied but are not used yet.
            //    ⇒ 实际生效的是全局的 `spring.kafka.consumer.auto-offset-reset: latest`
            //      （那个生效是因为它走 Boot 松散绑定，会被转成点分形式）
            //    ⇒ 后果：**「group 首次消费无位点」时 RAG 从队尾开始、Go 从队头开始** ⇒ 行为分叉。
            //    （此前的对拍全绿是因为它们都是「先起消费者、后灌消息」⇒ latest/earliest 无差别。）
            properties = "auto.offset.reset=earliest",
            containerFactory = "kafkaListenerContainerFactory")
    public void route(ConsumerRecord<String, String> record) {
        // ⚠️ value 必须是 raw 原文（见类注释）
        String value = record.value() == null ? "" : record.value();
        TaskLogEvent event = TaskLogEvent.parseLenient(objectMapper, value);

        // ctx.Emit(goka.Stream(rawTopicTaskUser), logEvent.UserID, raw)
        emit(topics.getRawUserTopic(), event.userId(), value, "User");

        // ctx.Emit(goka.Stream(rawTopicTaskArticle), logEvent.ArticleID, raw)
        emit(topics.getRawArticleTopic(), event.articleId(), value, "Article");
    }

    private void emit(String topic, String key, String rawValue, String routeName) {
        try {
            // ⚠️ value 必须是 raw 原文（见类注释）
            kafkaTemplate.send(topic, key == null ? "" : key, rawValue == null ? "" : rawValue);
        } catch (Exception e) {
            // Go 侧 ctx.Emit 不返回错误，发不出去也只有 goka 自己的重试/日志。
            // 这里同样只记日志、继续处理另一条路由，不影响位点提交。
            log.warn("emit task raw log to {} route failed: topic={} key={} err={}",
                    routeName, topic, key, e.getMessage());
        }
    }
}
