package com.rag.like.messaging;

import com.rag.like.domain.entity.LikeOutboxEventEntity;
import com.rag.like.mapper.LikeOutboxEventMapper;
import com.rag.like.service.LikeKafkaPushKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p><b>热点事件发件箱中继</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeHotEventOutboxRelay.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：落点由 {@code com.rag.like.like.rpc.mq} 移到
 *       {@code com.rag.like.messaging}（D7）；实体/Mapper 的 import 随各自落点调整
 *       （{@code domain/entity/} + {@code mapper/}）。</li>
 *   <li><b>语义还一样吗</b>：一样。批次上限 100、单条失败<b>不中断批次</b>、
 *       成功即 {@code markSent}、失败即 {@code markFailed} 逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 单条失败<b>不</b>中断批次</h2>
 * <p>B 侧的循环体把 {@code kafkaTemplate.send} 包在 try/catch 里 ⇒ 一条推失败只记日志 +
 * {@code markFailed}，<b>继续推下一条</b> ⇒ 不能改成「遇错即退」（那是行为变更）。
 */
@Component
public class LikeHotEventOutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(LikeHotEventOutboxRelay.class);

    private static final int BATCH_LIMIT = 100;

    private final LikeOutboxEventMapper outboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LikeKafkaTopics topics;

    public LikeHotEventOutboxRelay(LikeOutboxEventMapper outboxMapper,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   LikeKafkaTopics topics) {
        this.outboxMapper = outboxMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    @Scheduled(fixedRateString = "${like.outbox-relay-interval:1000}")
    public void sendPending() {
        try {
            relayOnce();
        } catch (Exception e) {
            LOG.warn("like hot-event outbox relay iteration failed: {}", e.getMessage());
        }
    }

    /**
     * 推一批待发热点事件。
     *
     * <p>⚠️ <b>推 key 是「当前 Unix 纳秒」</b>（Go {@code Push} 的内部实现，见
     * {@link LikeKafkaPushKey#unixNano()}）⇒ <b>带 key</b>，不是「无 key」。
     */
    public void relayOnce() {
        List<LikeOutboxEventEntity> events = outboxMapper.selectPending(BATCH_LIMIT);
        if (events == null || events.isEmpty()) {
            return;
        }
        for (LikeOutboxEventEntity event : events) {
            try {
                final String pushKey = LikeKafkaPushKey.unixNano();
                kafkaTemplate.send(topics.getHotEventTopic(), pushKey, event.getPayload())
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                LOG.error("push hot event failed (async): eventId={} err={}",
                                        event.getEventId(), error.getMessage());
                            }
                        });
                outboxMapper.markSent(event.getEventId());
            } catch (Exception e) {
                LOG.warn("push hot event failed: eventId={} err={}", event.getEventId(), e.getMessage());
                try {
                    outboxMapper.markFailed(event.getEventId());
                } catch (Exception markErr) {
                    LOG.warn("mark hot event failed error: eventId={} err={}",
                            event.getEventId(), markErr.getMessage());
                }
            }
        }
    }
}
