package com.rag.task.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * <p><b>文章点赞计数</b> —— 1:1 对应 Go 端 {@code internal/Init/initGoKa.go:84-107} 的
 * {@code processArticleCount} 与 {@code startTaskArticleGoKa} 的 goka group
 * （{@code initGoKa.go:196-202}）。</p>
 *
 * <h2>Go 的组定义</h2>
 * <pre>
 * goka.DefineGroup(
 *     goka.Group(groupTaskArticle),                                          // task.rpc.consumer71
 *     goka.Input(goka.Stream(rawTopicTaskArticle), new(codec.Bytes), processArticleCount),
 *     goka.Persist(new(codec.Int64)),
 *     goka.Output(goka.Stream(rawTopicTaskArticle), new(codec.Bytes)),        // task.logs.filtered.Article
 * )
 * </pre>
 *
 * <h2>⚠️ 输出 value 是 JSON（{@code codec.Bytes}），不是十进制字符串</h2>
 * <p>与用户路不同：本组 {@code goka.Output} 用的是 {@code new(codec.Bytes)}，
 * emit 的是 {@code json.Marshal(TaskArticleEvent{UserID, Cur})} 的字节，
 * 形如 {@code {"user_id":"1902","cur":3}}。
 * JSON 形状见 {@link TaskArticleProgressEvent} 的类注释（字段名 / 顺序 / 类型都是契约）。</p>
 *
 * <h2>🔴 作者 ID 来自<b>解析 raw 日志</b>，不是来自 key</h2>
 * <p>本组的 Kafka key 是<b>文章 ID</b>（路由阶段用 {@code logEvent.ArticleID} 作为 key），
 * 但 emit 出去的 {@code user_id} 是<b>从 raw 日志里重新解析</b>出来的作者 ID
 * （{@code initGoKa.go:87-90} 重新 {@code json.Unmarshal(raw, &logEvent)}）。
 * ⇒ 作者 ID 与文章 ID 是<b>两个不同来源</b>：key 决定「计数归属哪篇文章」，
 * {@code user_id} 决定「进度记到谁头上」。用 key 当 user_id 会把任务记错人。</p>
 *
 * <h2>⚠️ 解析失败仍继续 emit</h2>
 * <p>与路由阶段同款：Go 只 {@code log.Println(err)}，随后用零值 {@code logEvent}
 * （{@code UserID == ""}）继续计数与 emit。下游 article sink 会因
 * {@code UserID == 0} 丢弃该消息（见其 {@code parseVal}）。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mq} → {@code com.rag.task.messaging}（D7）；</li>
 *         <li>{@code TaskKafkaTopics} 随 D7 移到 {@code messaging/} ⇒ import 调整，
 *             <b>配置键名与 group 取值逐字未动</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 「user_id 从 raw 日志重新解析」
 *       「marshal 失败发空串」「emit 失败只记日志」三条逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskArticleCountConsumer.java:45-63（类声明 + 构造）
 * B 出处：…/mq/TaskArticleCountConsumer.java:70-112（listener + count + JSON emit）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskArticleCountConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskArticleCountConsumer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskKafkaTopics topics;
    private final TaskCountStore countStore;
    private final ObjectMapper objectMapper;

    public TaskArticleCountConsumer(KafkaTemplate<String, String> kafkaTemplate,
                                    TaskKafkaTopics topics,
                                    TaskCountStore countStore,
                                    ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.countStore = countStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 按文章 key 累加计数，并把 {@code {user_id, cur}} emit 到 filtered.Article。
     *
     * <p>⚠️ <b>不抛异常</b>，理由同 {@link TaskUserCountConsumer}。</p>
     */
    @KafkaListener(id = "task-goka-article-count",
            topics = "${task.kafka.raw-article-topic}",
            groupId = "${task.kafka.group-goka-article}",
            // ⚠️ goka 硬编码 OffsetOldest（initGoKa.go:207）
            //
            // 🔴 2026-10-24 修正：必须是**点分**的 `auto.offset.reset`。
            //    `@KafkaListener(properties=...)` 不走 Boot 松散绑定，原样传给 Kafka；
            //    连字符形式 `auto-offset-reset` **不是 Kafka 的合法配置名 ⇒ 被直接忽略**
            //    ⇒ 此前这里写的 earliest **从未生效**，实际是全局的 latest。
            //    详见 {@link TaskRawLogRouter} 同名注解处的完整说明。
            properties = "auto.offset.reset=earliest",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        String key = record.key() == null ? "" : record.key();
        String value = record.value();

        // Go: 重新 Unmarshal raw 日志取 user_id（不是用 key）
        TaskLogEvent event = TaskLogEvent.parseLenient(objectMapper, value);

        // Go: cur++ 后 SetValue
        // ⚠️ group 作为命名空间传入：Go 侧两个 goka processor 各有独立 store
        //    （goka processor.go:82 按 group 名分 LevelDB 目录），见 TaskCountStore 类注释。
        long cur = countStore.increment(topics.getGroupGokaArticle(), key);

        // Go: _val := TaskArticleEvent{UserID: logEvent.UserID, Cur: cur}; ctx.Emit(out, ctx.Key(), val)
        TaskArticleProgressEvent out = new TaskArticleProgressEvent(event.userId(), cur);
        String payload;
        try {
            payload = out.toJson(objectMapper);
        } catch (RuntimeException e) {
            // Go: json.Marshal 失败只 log，随后 ctx.Emit(stream, key, nil)
            //      （initGoKa.go:102-105 里 val 为 nil，emit 的是 nil value）
            log.warn("marshal task article count failed: key={} cur={} err={}",
                    key, cur, e.getMessage());
            payload = "";
        }

        try {
            kafkaTemplate.send(topics.getOutArticleTopic(), key, payload);
        } catch (Exception e) {
            log.warn("emit task article count failed: key={} cur={} err={}", key, cur, e.getMessage());
        }
    }
}
