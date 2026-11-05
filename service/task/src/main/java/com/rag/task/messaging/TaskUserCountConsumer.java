package com.rag.task.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * <p><b>用户点赞计数</b> —— 1:1 对应 Go 端 {@code internal/Init/initGoKa.go:72-82} 的
 * {@code processUserCount} 与 {@code startTaskUserGoKa} 的 goka group
 * （{@code initGoKa.go:164-169}）。</p>
 *
 * <h2>Go 的组定义</h2>
 * <pre>
 * goka.DefineGroup(
 *     goka.Group(groupTaskUser),                                          // task.rpc.consumer61
 *     goka.Input(goka.Stream(rawTopicTaskUser), new(codec.Bytes), processUserCount),
 *     goka.Persist(new(codec.Int64)),                                     // ← 每 key 本地累加器
 *     goka.Output(goka.Stream(outTopicTaskUser), new(codec.Int64)),       // task.logs.filtered.User
 * )
 * </pre>
 *
 * <h2>🔴 value 是<b>十进制字符串</b>，不是 8 字节二进制</h2>
 * <p>这一条曾经是本迁移最危险的猜测点。goka 的 {@code codec.Int64} 实现是
 * {@code []byte(strconv.FormatInt(intVal, 10))}（{@code goka/codec/codec.go:56}）——
 * <b>它编码成十进制 ASCII，不是网络字节序的 int64</b>。
 * 若按"goka 是 table-store、应该是二进制"去实现，下游 sink 的
 * {@code strconv.ParseInt(val, 10, 64)} 会全部失败。</p>
 *
 * <p>另一侧同样重要：消费端 go-queue 的 {@code Consume(ctx, key, val string)}
 * 用的是 {@code string(msg.Value)}（{@code go-queue/kq/queue.go:230}），
 * <b>没有任何反序列化器</b>。所以 application.yml 里
 * {@code value-deserializer} 必须是 {@code StringDeserializer}，用 ByteArray 会改变行为。</p>
 *
 * <h2>🔴 与 like-rpc 共用同一个输出 topic</h2>
 * <p>{@code task.logs.filtered.User} 同时被 Go/Java 的 like-rpc 用
 * {@code PushWithKey(userId, decimalString)} 生产（{@code likeactionlogic.go:317-320}）。
 * <b>两边的 value 形状恰好都是十进制字符串</b>，所以能共存。
 * 若把本条改成二进制，like-rpc 推来的进度会全部退化成 1（sink 解析失败回落默认值）。</p>
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
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 「group 作为命名空间传入」
 *       「计数写十进制字符串」「emit 失败只记日志不抛」三条逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskUserCountConsumer.java:44-59（类声明 + 构造）
 * B 出处：…/mq/TaskUserCountConsumer.java:72-102（listener + count + emit）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskUserCountConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskUserCountConsumer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskKafkaTopics topics;
    private final TaskCountStore countStore;

    public TaskUserCountConsumer(KafkaTemplate<String, String> kafkaTemplate,
                                 TaskKafkaTopics topics,
                                 TaskCountStore countStore) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.countStore = countStore;
    }

    /**
     * 按 key 累加计数，并把新计数 emit 到 filtered.User。
     *
     * <p>⚠️ <b>无失败分支</b>：Go 的 {@code processUserCount} 唯一会出错的是
     * {@code v.(int64)} 的类型断言，而 goka 用 {@code codec.Int64} 保证了类型 ⇒ 实际不会触发。
     * 所以本方法<b>不主动抛异常</b>（位点照常提交），与 Go 一致。</p>
     *
     * <p>⚠️ 唯一的例外是 <b>计数器存储本身</b>不可用（Redis 挂）——那种情况下
     * {@link TaskCountStore#increment} 会抛，位点不提交、该条重投，
     * 对应 Go 侧 goka store 写失败时的退避重投。</p>
     */
    @KafkaListener(id = "task-goka-user-count",
            topics = "${task.kafka.raw-user-topic}",
            groupId = "${task.kafka.group-goka-user}",
            // ⚠️ goka 硬编码 OffsetOldest（initGoKa.go:173），与 kq 那三处的 "latest" 相反
            //
            // 🔴 2026-10-24 修正：必须是**点分**的 `auto.offset.reset`。
            //    `@KafkaListener(properties=...)` 不走 Boot 松散绑定，原样传给 Kafka；
            //    连字符形式 `auto-offset-reset` **不是 Kafka 的合法配置名 ⇒ 被直接忽略**
            //    （实测：consumer config dump 里 `auto.offset.reset = latest`，
            //      且 Kafka 打出 "These configurations '[auto-offset-reset]' were supplied but are not used yet."）
            //    ⇒ 此前这里写的 earliest **从未生效**，实际是全局的 latest。
            //    详见 {@link TaskRawLogRouter} 同名注解处的完整说明。
            properties = "auto.offset.reset=earliest",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        String key = record.key() == null ? "" : record.key();

        // Go: cur := ctx.Value()（首条为 nil ⇒ 0）; cur++; ctx.SetValue(cur)
        // ⚠️ group 作为命名空间传入：Go 侧两个 goka processor 各有独立 store
        //    （goka processor.go:82 按 group 名分 LevelDB 目录），见 TaskCountStore 类注释。
        long cur = countStore.increment(topics.getGroupGokaUser(), key);

        // Go: ctx.Emit(goka.Stream(outTopicTaskUser), ctx.Key(), cur)
        try {
            kafkaTemplate.send(topics.getOutUserTopic(), key, String.valueOf(cur));
        } catch (Exception e) {
            // goka 的 ctx.Emit 不返回错误；失败只有 goka 自己重试。
            // 这里同样只记日志、不抛异常 ⇒ 位点照常提交，与 Go 一致。
            log.warn("emit task user count failed: key={} cur={} err={}", key, cur, e.getMessage());
        }
    }
}
