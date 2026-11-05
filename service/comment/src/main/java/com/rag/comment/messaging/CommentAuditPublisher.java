package com.rag.comment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.contract.CommentKafkaMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <p><b>评论审计消息生产端</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditPublisher.java:1-113}
 * （B 侧的语义来源注释写的是 Go 端 {@code service_context.go:28} 的
 * {@code kq.NewPusher(c.KqPusherConf.Brokers, c.KqPusherConf.Topic)}）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.service} 移到 {@code com.rag.comment.messaging}（D7 · D10）；</li>
 *       <li><b>载荷类型改为生产侧 wire 契约</b> {@link CommentKafkaMsg}
 *           （B 侧只有一个类、生产消费共用 —— C 侧按 D7 把跨服务契约提到 {@code contract/}，
 *           消费侧另有一份 {@link CommentAuditMessage} DTO，见其类注释的「职责边界」段）。
 *           ⇒ 本类额外提供一个吃 {@link CommentAuditMessage} 的重载（内部走
 *           {@link CommentAuditMessage#toWire()}），用于「回放 / 重投」方向的调用方
 *           ⇒ <b>两个入口产出<b>逐字节相同</b>的 JSON</b>（两个 record 的字段名与
 *           {@code @JsonPropertyOrder} 逐条相同）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。同步阻塞写、失败抛 {@link PublishFailed}（B 侧 5009）、
 *       序列化失败抛 {@link SerializationFailed}（B 侧 5007）、超时上界取
 *       {@code publish-timeout} 逐字未改。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../service/CommentAuditPublisher.java:1-113}。</li>
 * </ol>
 *
 * <h2>⚠️ 同步还是异步：这是本域的一条 L1 行为，必须同步</h2>
 * <p>B 侧的 {@code kq.Pusher.Push} 是<b>同步阻塞</b>写，而
 * {@code create_comment_logic.go:126-132} 把它当作<b>会失败</b>的操作处理：
 * <pre>
 * if err = l.svcCtx.KqPusherClient.Push(ctx, string(msgBytes)); err != nil {
 *     result = "sys_fail"
 *     err = errmsg.NewGrpcErr(errmsg.ErrorKafkaPush, "Kafka推送失败")
 *     return nil, err
 * }
 * </pre>
 * ⇒ <b>Kafka 写失败 ⇒ CreateComment 返回 5009</b>，且<b>响应时间包含 Kafka 往返</b>。
 *
 * <p>Java 的 {@code KafkaTemplate.send} 返回 {@code CompletableFuture}，是异步的。
 * 若不等结果就返回，就会变成「Kafka 挂了但评论创建照样返回成功」——
 * 客户端以为评论发出去了，实际审计链路从未收到消息（评论<b>不会落库</b>，
 * 因为落库发生在 AuditConsumer 里）⇒ 一条<b>永远看不到</b>的评论。
 * 这是<b>方向相反的漂移</b>，比慢一点严重得多。
 *
 * <p>⇒ 本类<b>必须</b> {@code future.get(timeout)}。
 *
 * <p>⚠️ 与 like 域的选择<b>刻意不同</b>：like 域选了 {@code acks=1 + 异步 + 失败回调日志}，
 * 理由是「B 侧失败也只打日志不返回错误」（likeactionlogic.go 那条链路）。
 * comment 域的 B 侧代码<b>真的把错误返回给了客户端</b> ⇒ 不能照抄 like 的选择。
 * 这是「复用模式」与「复用结论」的区别。
 */
@Service
public class CommentAuditPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(CommentAuditPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CommentRpcProperties props;
    private final ObjectMapper objectMapper;

    public CommentAuditPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 CommentRpcProperties props,
                                 ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 推送一条待审计的评论消息（生产侧 wire 契约入口）。
     *
     * <p>对应 B 侧 {@code json.Marshal(msg)} + {@code Push(ctx, string(msgBytes))}。
     *
     * @throws SerializationFailed 序列化失败（对应 B 侧的 {@code ErrorJsonMarshal} 5007）
     * @throws PublishFailed        发送失败或超时（对应 B 侧的 {@code ErrorKafkaPush} 5009）
     */
    public void publish(CommentKafkaMsg msg) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            LOG.error("marshal comment audit message failed: {}", e.getMessage(), e);
            throw new SerializationFailed("Json序列化失败", e);
        }

        String topic = props.getKafka().getAuditTopic();
        try {
            // ⚠️ 同步等待：这是本域 L1 语义的载体，理由见类级 javadoc。
            // 不用 key（B 侧 kq.Pusher.Push 也不设 key），分区由 broker 按 round-robin 决定。
            kafkaTemplate.send(topic, payload)
                    .get(props.getKafka().getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.error("kafka push timeout after {}ms, topic={}", props.getKafka().getPublishTimeout().toMillis(), topic, e);
            throw new PublishFailed("Kafka推送失败", e);
        } catch (InterruptedException e) {
            // B 侧 ctx 被取消时是同步返回错误；Java 侧对应的是线程被中断。
            Thread.currentThread().interrupt();
            throw new PublishFailed("Kafka推送失败", e);
        } catch (Exception e) {
            LOG.error("kafka push failed, topic={}: {}", topic, e.getMessage(), e);
            throw new PublishFailed("Kafka推送失败", e);
        }
        LOG.debug("comment audit message pushed: topic={} comment_id={}", topic, msg.commentId());
    }

    /**
     * 推送一条待审计的评论消息（消费侧 DTO 入口 —— 供「回放 / 重投」方向使用）。
     *
     * <p>⚠️ <b>本重载只是把 DTO 转成生产侧 wire 契约</b>
     * （{@link CommentAuditMessage#toWire()}），随后走<b>完全相同</b>的序列化 + 同步发送路径
     * ⇒ 产出的 JSON 字节与 {@link #publish(CommentKafkaMsg)} <b>逐字节相同</b>。
     *
     * @throws SerializationFailed 序列化失败（对应 B 侧的 {@code ErrorJsonMarshal} 5007）
     * @throws PublishFailed        发送失败或超时（对应 B 侧的 {@code ErrorKafkaPush} 5009）
     */
    public void publish(CommentAuditMessage msg) {
        publish(msg.toWire());
    }

    /** 对应 B 侧 {@code create_comment_logic.go:122} 的 {@code ErrorJsonMarshal}(5007)。 */
    public static class SerializationFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SerializationFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** 对应 B 侧 {@code create_comment_logic.go:130} 的 {@code ErrorKafkaPush}(5009)。 */
    public static class PublishFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PublishFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
