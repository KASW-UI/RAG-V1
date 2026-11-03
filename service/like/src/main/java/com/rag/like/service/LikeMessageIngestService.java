package com.rag.like.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.snowflake.Snowflake;
import com.rag.like.contract.ArticleHotEvent;
import com.rag.like.contract.KafkaLikeMsg;
import com.rag.like.domain.entity.LikeConsumeInboxEntity;
import com.rag.like.domain.entity.LikeRecordEntity;
import com.rag.like.domain.service.LikeDomainFactService;
import com.rag.like.mapper.LikeConsumeInboxMapper;
import com.rag.like.mapper.LikeOutboxEventMapper;
import com.rag.like.mapper.LikeRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 点赞消息落库 —— 1:1 对应 Go 端
 * {@code service/like/rpc/internal/model/like.go:148-245} 的
 * {@code ProcessLikeMessageBatch} + {@code applyLikeMessage} + {@code markLikeMessageDone}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeMessageIngestService.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.like.like.rpc.service} 压平为 {@code com.rag.like.service}（D7）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.like.like.rpc.domain} / {@code ...rpc.mapper}
 *           改为 {@code com.rag.like.domain.entity} / {@code com.rag.like.mapper}（D7）；</li>
 *       <li>{@code LikeDomainFactService} 由同包改为 {@code com.rag.like.domain.service}（D7：域服务归
 *           {@code domain/service/}）；</li>
 *       <li>{@code LikeEventContracts} 的 4 个字面量常量按语义下沉到
 *           {@link KafkaLikeMsg} / {@link ArticleHotEvent}（值逐字未改，见
 *           {@link LikeDeliveryValidator} 类注释的「常量落点说明」）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。幂等三层、状态机、事务边界、软删守卫逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 事务边界为什么在这里，而不在 listener 上</h2>
 *
 * <p>Go 是显式 {@code db.Transaction(func(tx) error)}。Java 用 {@link Transactional} 表达，
 * <b>但必须保证代理生效</b>：本类是独立 {@code @Service} Bean，由 listener 通过<b>注入</b>调用，
 * 而不是自己内部调用。若把 listener 改成调用本类的另一个方法（自调用），
 * {@code @Transactional} 会静默失效 —— 那是 AGENTS.md §6.6 DRIFT-MSG-001 记录过的真实事故
 * （message-rpc 的 6 个 {@code @Transactional} 因 self-invocation 全部失效，
 * 新建会话失败时残留孤儿会话行）。⇒ 须配一条「能真正触发它」的装配层测试。
 *
 * <h2>幂等三层（顺序不可换）</h2>
 * <ol>
 *   <li><b>inbox 主键冲突</b>（{@code ON CONFLICT (msg_id) DO NOTHING} 影响 0 行）⇒ 这条消息来过；</li>
 *   <li><b>payload_hash 比对</b> ⇒ 同 msg_id 但载荷不同 ⇒ 消息 ID 被复用 ⇒ <b>报错重投</b>
 *       （不是跳过！跳过会让数据静默错误）；</li>
 *   <li><b>inbox.status == 1</b> ⇒ 上次已完整处理 ⇒ 跳过（continue，不重复应用）。</li>
 * </ol>
 *
 * <h2>状态机</h2>
 * action（1~4）→ state（0/1/2）：
 * <pre>
 *   action=1 like       → 1
 *   action=2 unlike     → 仅当 oldState==1 才变 0（否则保持不变）
 *   action=3 dislike    → 2
 *   action=4 undislike  → 仅当 oldState==2 才变 0
 * </pre>
 * ⚠️ action=2/4 的「条件转移」意味着「对没点赞的目标取消点赞」是<b>无操作</b>，
 * 且<b>不会</b>产生事实（{@code newState == oldState}）。照搬。
 */
@Service
public class LikeMessageIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(LikeMessageIngestService.class);

    /** state 的合法取值域：0 无 / 1 赞 / 2 踩。 */
    private static final int STATE_NONE = 0;
    private static final int STATE_LIKE = 1;
    private static final int STATE_DISLIKE = 2;

    /** action 的合法取值域。 */
    private static final int ACTION_MIN = 1;
    private static final int ACTION_MAX = 4;

    private final LikeRecordMapper recordMapper;
    private final LikeConsumeInboxMapper inboxMapper;
    private final LikeOutboxEventMapper outboxMapper;
    private final LikeMessageDigest digest;
    private final LikeDomainFactService factService;

    public LikeMessageIngestService(LikeRecordMapper recordMapper,
                                    LikeConsumeInboxMapper inboxMapper,
                                    LikeOutboxEventMapper outboxMapper,
                                    LikeMessageDigest digest,
                                    LikeDomainFactService factService) {
        this.recordMapper = recordMapper;
        this.inboxMapper = inboxMapper;
        this.outboxMapper = outboxMapper;
        this.digest = digest;
        this.factService = factService;
    }

    /** 一条待落库的点赞消息 —— 对应 Go {@code model.LikeProcessPayload}。 */
    public static final class LikeProcessPayload {

        /** 消费消息 ID（Kafka 消息里的 msg_id，雪花 ID 的字符串形式）。 */
        public String msgId;

        public long userId;
        public String targetType;
        public String targetId;
        public long authorId;

        /** action 值：1~4。 */
        public int action;

        /** 消息的 created_at（Unix 秒）。 */
        public long occurredAt;

        /** Lua 返回 2 时为 true。 */
        public boolean isFirst;

        /**
         * 首次点赞时预构造的热点事件（可为 null）。
         *
         * <p>Go 在<b>事务外</b>（消费入口）就把它造好塞进来，事务内只在
         * {@code oldState==0 && newState==1} 时插入 —— 造对象和写库分开的目的是
         * 让「雪花 ID 生成失败」也能在事务外被单独观测。
         */
        public LikeOutboxDraft firstLikeOutbox;
    }

    /** 热点事件草稿 —— 对应 Go 的 {@code *model.LikeOutboxEvent}。 */
    public static final class LikeOutboxDraft {

        public String eventId;
        public String eventKey;
        public String eventType;
        public String aggregateId;
        public String payload;
    }

    /**
     * 批量处理点赞消息 —— 对应 Go {@code ProcessLikeMessageBatch}。
     *
     * @throws RuntimeException 任一消息处理失败 ⇒ <b>整个事务回滚</b>，消息不提交位点、稍后重投
     */
    @Transactional(rollbackFor = Exception.class)
    public void processBatch(List<LikeProcessPayload> payloads) {
        for (LikeProcessPayload payload : payloads) {
            applyOne(payload);
        }
    }

    /** 对应 Go {@code ProcessLikeMessageBatch} 循环体 + {@code applyLikeMessage}。 */
    private void applyOne(LikeProcessPayload p) {
        // Go: 载荷前置校验（Record / Inbox 非空 + 字段取值域）
        if (p == null || p.msgId == null || p.msgId.isEmpty()
                || p.userId <= 0
                || p.targetType == null || p.targetType.isEmpty()
                || p.targetId == null || p.targetId.isEmpty()
                || p.action < ACTION_MIN || p.action > ACTION_MAX) {
            throw new IllegalStateException("invalid like message payload");
        }

        // Go: operationID, err := strconv.ParseInt(p.Inbox.MsgId, 10, 64); if err != nil || <= 0 → error
        long operationId;
        try {
            operationId = Long.parseLong(p.msgId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid like operation id: \"" + p.msgId + "\"");
        }
        if (operationId <= 0) {
            throw new IllegalStateException("invalid like operation id: \"" + p.msgId + "\"");
        }

        String payloadHash = digest.hash(p.userId, p.targetType, p.targetId,
                p.authorId, p.action, p.occurredAt, p.isFirst);

        // Go: INSERT ... ON CONFLICT (msg_id) DO NOTHING
        int inserted = inboxMapper.insertInboxIfAbsent(p.msgId, payloadHash,
                KafkaLikeMsg.INBOX_TOPIC,
                KafkaLikeMsg.INBOX_CONSUMER);

        if (inserted == 0) {
            // Go: SELECT ... FOR UPDATE 拿既有行
            LikeConsumeInboxEntity prior = inboxMapper.selectInboxForUpdate(p.msgId);
            if (prior == null) {
                throw new IllegalStateException("like inbox row vanished after conflict: " + p.msgId);
            }
            String priorHash = prior.getPayloadHash();
            if (priorHash != null && !priorHash.isEmpty() && !priorHash.equals(payloadHash)) {
                // Go: return fmt.Errorf("like message %s reused with conflicting payload", p.Inbox.MsgId)
                throw new IllegalStateException(
                        "like message " + p.msgId + " reused with conflicting payload");
            }
            if (prior.getStatus() == 1) {
                // Go: if prior.Status == 1 { continue } —— 已完整处理过，直接跳过
                LOG.debug("like message already applied, skip: msgId={}", p.msgId);
                return;
            }
        }

        applyLikeMessage(p, operationId);
    }

    /** 对应 Go {@code applyLikeMessage(tx, p, operationID)}。 */
    private void applyLikeMessage(LikeProcessPayload p, long operationId) {
        // Go: 先插一条 state=0 的种子行（ON CONFLICT DO NOTHING），确保下面 FOR UPDATE 一定能查到行
        recordMapper.insertSeedIfAbsent(p.userId, p.targetType, p.targetId, p.authorId);

        // Go: tx.Unscoped().FOR UPDATE —— 唯一一条**不过滤软删**的读
        LikeRecordEntity current = recordMapper.selectForUpdateIncludingDeleted(
                p.userId, p.targetType, p.targetId);
        if (current == null) {
            throw new IllegalStateException(
                    "like record not found after seed: " + p.userId + "/" + p.targetType + "/" + p.targetId);
        }

        // Go: if current.DeleteAt.Valid { return fmt.Errorf("like record is soft deleted") }
        if (current.getDeleteAt() != null) {
            throw new IllegalStateException("like record is soft deleted");
        }

        // Go: if current.State < 0 || current.State > 2
        //       || (current.State == 2 && current.LastOperationID == 0)
        //     { return fmt.Errorf("legacy like state %d requires reconciliation before fact emission", …) }
        // ⚠️ state==2 且 last_operation_id==0 = 事实流上线之前写入的遗留数据，
        //    它没有对应的事实，追加新事实会破坏状态流连续性 ⇒ 要求先人工对齐。
        if (current.getState() < STATE_NONE || current.getState() > STATE_DISLIKE
                || (current.getState() == STATE_DISLIKE && current.getLastOperationId() == 0)) {
            throw new IllegalStateException("legacy like state " + current.getState()
                    + " requires reconciliation before fact emission");
        }

        // Go: if operationID <= current.LastOperationID { return markLikeMessageDone(...) }
        // 乱序消息：更早的 operation 直接标记完成，不回放状态变更。
        if (operationId <= current.getLastOperationId()) {
            inboxMapper.markDone(p.msgId);
            return;
        }

        int oldState = current.getState();
        int newState = switch (p.action) {
            case 1 -> STATE_LIKE;
            case 2 -> (oldState == STATE_LIKE ? STATE_NONE : oldState);   // 只有真点赞才取消
            case 3 -> STATE_DISLIKE;
            case 4 -> (oldState == STATE_DISLIKE ? STATE_NONE : oldState); // 只有真点踩才取消
            default -> oldState;
        };

        recordMapper.updateState(current.getId(), newState, operationId, p.authorId);

        // Go: if newState != oldState { appendLikeFact(...); if outbox != nil && old==0 && new==1 { insert outbox } }
        if (newState != oldState) {
            factService.appendFact(p.msgId, p.userId, p.targetType, p.targetId, p.authorId,
                    p.action, oldState, newState, p.occurredAt);

            if (p.firstLikeOutbox != null && oldState == STATE_NONE && newState == STATE_LIKE) {
                outboxMapper.insertIfAbsent(
                        p.firstLikeOutbox.eventId,
                        p.firstLikeOutbox.eventKey,
                        p.firstLikeOutbox.eventType,
                        p.firstLikeOutbox.aggregateId,
                        p.firstLikeOutbox.payload);
            }
        }

        // Go: return markLikeMessageDone(tx, p.Inbox.MsgId)
        inboxMapper.markDone(p.msgId);
    }

    /**
     * 造「首次点赞」热点事件草稿 —— 对应 Go {@code buildFirstLikeOutbox}。
     *
     * <p>在<b>事务外</b>调用（消息进入落库流程之前），原因见 {@link LikeProcessPayload#firstLikeOutbox}。
     *
     * @return 仅当「action==1 且 isFirst」时返回草稿，其余返回 {@code null}
     * @throws IllegalStateException 雪花 ID 生成失败 —— Go 侧同样直接返回 error
     */
    public LikeOutboxDraft buildFirstLikeOutbox(LikeProcessPayload p) {
        // Go: if !(task.Record.State == 1 && task.IsFirst) { return nil, nil }
        if (!(p.action == 1 && p.isFirst)) {
            return null;
        }

        String payload;
        try {
            payload = GoCompatibleJson.write(new ObjectMapper()
                    .createObjectNode()
                    .put("article_id", p.targetId)
                    .put("type", ArticleHotEvent.EVENT_TYPE_LIKE)
                    .put("user_id", String.valueOf(p.userId))
                    .put("timestamp", Instant.now().getEpochSecond())
                    .put("is_first", true));
        } catch (Exception e) {
            throw new IllegalStateException("marshal article hot event failed", e);
        }

        // Go: eventKey := fmt.Sprintf("first_like:%d:%s:%s", userID, targetType, targetID)
        String eventKey = "first_like:" + p.userId + ":" + p.targetType + ":" + p.targetId;

        final long eventId;
        try {
            eventId = Snowflake.nextId();
        } catch (Exception e) {
            // Go: 记 snowflake_error 后 return err ⇒ 消息重投
            throw new IllegalStateException("generate outbox event id failed", e);
        }

        LikeOutboxDraft draft = new LikeOutboxDraft();
        draft.eventId = String.valueOf(eventId);
        draft.eventKey = eventKey;
        draft.eventType = ArticleHotEvent.OUTBOX_EVENT_TYPE_ARTICLE_HOT_LIKE;
        draft.aggregateId = p.targetId;
        draft.payload = payload;
        return draft;
    }
}
