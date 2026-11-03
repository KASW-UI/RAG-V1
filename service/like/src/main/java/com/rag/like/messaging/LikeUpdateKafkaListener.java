package com.rag.like.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.observability.MqConsumer;
import com.rag.like.contract.KafkaLikeMsg;
import com.rag.like.metrics.LikeMqMetrics;
import com.rag.like.metrics.LikeRpcMetrics;
import com.rag.like.service.LikeMessageIngestService;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;

/**
 * <p><b>点赞消息消费入口</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeUpdateKafkaListener.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.like.like.rpc.mq} 移到 {@code com.rag.like.messaging}（D7）；</li>
 *       <li>{@code LikeEventContracts.KafkaLikeMsg} → {@link KafkaLikeMsg}（1:N 拆分）；</li>
 *       <li>{@code LikeSpans} 的常量并入 {@link LikeRpcMetrics}（映射表第 209 行）；</li>
 *       <li><b>观测包装的入参形态</b>：B 侧调共享层
 *           {@code MqConsumer.traceConsumer(Context.current(), …)}（otel Context）；
 *           C 侧共享层的签名是
 *           {@code traceConsumer(ObservationRegistry, tracerName, spanName, attrs, fn)}
 *           （Micrometer Observation 形态）⇒ 本类改为<b>注入 {@link ObservationRegistry}</b>
 *           并把 lambda 的入参类型换成 {@code Observation.Context}。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。四类消费结果（{@code json_error} / {@code empty_msg_id} /
 *       {@code outbox_error} / {@code tx_error} / {@code committed}）、「失败即丢弃不重投」的
 *       {@code ForceCommit=true} 语义、字段校验的 5 条判据逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 失败即丢弃（不重投）</h2>
 * <p>B 侧的 {@code ForceCommit} 默认 true ⇒ 消费失败也<b>提交位点</b>（消息不重投）。
 * 本类的 {@link #goAlignedErrorHandler()} 用 {@code FixedBackOff(0, 0)} 表达同一件事。
 * <p>⚠️ <b>这不是「吞异常」</b>：业务失败在 {@link #consume(String)} 里被<b>转成返回值</b>，
 * 由 {@link MqConsumer#traceConsumer} 的返回值判定 ⇒ 只有<b>未被归类的</b>异常才走 error handler。
 */
@Component
public class LikeUpdateKafkaListener {

    private static final Logger LOG = LoggerFactory.getLogger(LikeUpdateKafkaListener.class);

    private final ObjectMapper objectMapper;
    private final LikeMessageIngestService ingestService;
    private final LikeMqMetrics metrics;
    private final ObservationRegistry observationRegistry;

    public LikeUpdateKafkaListener(ObjectMapper objectMapper,
                                   LikeMessageIngestService ingestService,
                                   LikeMqMetrics metrics,
                                   ObservationRegistry observationRegistry) {
        this.objectMapper = objectMapper;
        this.ingestService = ingestService;
        this.metrics = metrics;
        this.observationRegistry = observationRegistry;
    }

    @KafkaListener(id = "like-update-consumer",
            topics = "${like.kafka.like-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, String value) {
        Throwable failure = MqConsumer.traceConsumer(
                observationRegistry,
                LikeRpcMetrics.MQ_TRACER_NAME,
                LikeRpcMetrics.MQ_SPAN_NAME,
                LikeRpcMetrics.messagingAttributes(record.key()),
                ctx -> consume(value));
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException re) {
            throw re;
        }
        if (failure instanceof Error err) {
            throw err;
        }
        throw new IllegalStateException(failure);
    }

    /**
     * 消费一条消息 —— 逐条照抄 B 侧。
     *
     * <p>⚠️ <b>五条判据</b>（B 侧 {@code isValid}）：{@code msg_id} 非空且可解析为正整数、
     * {@code user_id > 0}、{@code target_type} 非空、{@code target_id} 非空、
     * {@code state ∈ [1,4]}。
     *
     * <p>⚠️ <b>「首次点赞 outbox」的构造在事务外</b>：Go 在消费入口就把草稿造好塞进来，
     * 事务内只在 {@code oldState==0 && newState==1} 时插入 ⇒ 造对象和写库分开的目的是
     * 让「雪花 ID 生成失败」也能在事务外被单独观测（这里记 {@code outbox_error} 并丢弃）。
     */
    private Throwable consume(String value) {
        KafkaLikeMsg msg;
        try {
            msg = objectMapper.readValue(value, KafkaLikeMsg.class);
        } catch (Exception e) {
            metrics.consume("receive", "json_error");
            LOG.warn("drop like message with invalid json: err={}", e.getMessage());
            return null;
        }
        if (!isValid(msg)) {
            metrics.consume("receive", "empty_msg_id");
            LOG.warn("drop like message with invalid fields: msgId={} userId={} state={}",
                    msg.msgId, msg.userId, msg.state);
            return null;
        }
        LikeMessageIngestService.LikeProcessPayload payload = new LikeMessageIngestService.LikeProcessPayload();
        payload.msgId = msg.msgId;
        payload.userId = msg.userId;
        payload.targetType = msg.targetType;
        payload.targetId = msg.targetId;
        payload.authorId = msg.authorId;
        payload.action = msg.state;
        payload.occurredAt = msg.createdAt;
        payload.isFirst = msg.isFirst;
        try {
            payload.firstLikeOutbox = ingestService.buildFirstLikeOutbox(payload);
        } catch (Exception e) {
            metrics.consume("receive", "outbox_error");
            LOG.warn("build first-like outbox failed, will be dropped: msgId={} err={}", msg.msgId, e.getMessage());
            return e;
        }
        try {
            ingestService.processBatch(List.of(payload));
        } catch (Exception e) {
            metrics.consume("receive", "tx_error");
            LOG.warn("commit like message failed, will be dropped: msgId={} err={}", msg.msgId, e.getMessage());
            return e;
        }
        metrics.consume("receive", "committed");
        return null;
    }

    private static boolean isValid(KafkaLikeMsg msg) {
        if (msg.msgId == null || msg.msgId.isBlank()) {
            return false;
        }
        final long operationId;
        try {
            operationId = Long.parseLong(msg.msgId);
        } catch (NumberFormatException e) {
            return false;
        }
        return operationId > 0
                && msg.userId > 0
                && msg.targetType != null && !msg.targetType.isEmpty()
                && msg.targetId != null && !msg.targetId.isEmpty()
                && msg.state >= 1 && msg.state <= 4;
    }

    /**
     * 对齐 Go {@code ForceCommit=true} 的错误处理器：失败也提交位点（不重投）。
     *
     * <p>⚠️ <b>{@code FixedBackOff(0, 0)}</b> = 「不重试」⇒ 与 Go 的「提交位点、丢弃消息」同形。
     */
    public static CommonErrorHandler goAlignedErrorHandler() {
        return new DefaultErrorHandler(
                (record, exception) ->
                        LOG.error("like message dropped (offset advances, matches Go ForceCommit=true): "
                                        + "topic={} partition={} offset={} err={}",
                                record.topic(), record.partition(), record.offset(), exception.getMessage()),
                new FixedBackOff(0, 0));
    }
}
