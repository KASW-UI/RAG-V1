package com.rag.like.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.like.domain.entity.LikeFactStreamEntity;
import com.rag.like.mapper.LikeDomainFactOutboxMapper;
import com.rag.like.mapper.LikeFactStreamMapper;
import com.rag.like.service.GoCompatibleJson;
import com.rag.like.service.LikeDeliveryValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 领域事实追加 —— 1:1 对应 Go 端
 * {@code service/like/rpc/internal/model/domain_fact.go} 的 {@code appendLikeFact}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeDomainFactService.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.like.like.rpc.service} 移到 {@code com.rag.like.domain.service}
 *           （D7：领域服务归 {@code domain/service/}）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.like.like.rpc.domain} / {@code ...rpc.mapper}
 *           改为 {@code com.rag.like.domain.entity} / {@code com.rag.like.mapper}（D7）；</li>
 *       <li>{@code LikeEventContracts} 的 4 个事实字面量常量按语义下沉到
 *           {@link LikeDeliveryValidator}（值逐字未改，见该类注释的「常量落点说明」）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。19 个载荷字段的 put 顺序、{@code putNull} 的两处 null、
 *       信封与载荷共用同一份原文、DC 安全整数上界逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>调用时机（不能挪）</h2>
 *
 * <p>只在「{@code like_record.state} 真的发生变化」时调用（Go {@code applyLikeMessage} 的
 * {@code if newState != oldState} 分支内），且<b>与 record 更新、inbox 完成标记在同一个 DB 事务里</b>。
 * 挪到事务外 ⇒ 事实流会出现「有状态变更但没有事实」或「有事实但状态回滚」的裂缝。
 *
 * <h2>为什么 payload / 信封都以<b>原始字符串</b>落库</h2>
 *
 * <p>{@code validLikeDelivery} 会把「payload 列」与「信封内嵌的 payload」做 JCS 规范化后比哈希。
 * 若两者分别经 POJO 序列化产生，「null 被省略」与「null 显式写出」的差异就会让校验失败。
 * 这里让<b>同一份字符串</b>既进 payload 列又内嵌进信封，从构造上消除这个风险。
 *
 * <p>（键顺序本身不是问题：{@code com.rag.common.communityfact.CanonicalHash#canonicalHash}
 * 走 JCS/RFC 8785，会规范化键序。）
 */
@Service
public class LikeDomainFactService {

    /** Go {@code domain_fact.go:117} 的 DC 安全整数上界：{@code 9007199254740991}（2^53-1）。 */
    private static final long DC_MAX_SAFE_INTEGER = 9007199254740991L;

    private final LikeDomainFactOutboxMapper factOutboxMapper;
    private final LikeFactStreamMapper factStreamMapper;
    private final ObjectMapper objectMapper;

    public LikeDomainFactService(LikeDomainFactOutboxMapper factOutboxMapper,
                                 LikeFactStreamMapper factStreamMapper,
                                 ObjectMapper objectMapper) {
        this.factOutboxMapper = factOutboxMapper;
        this.factStreamMapper = factStreamMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条状态变更事实。
     *
     * <p><b>⚠️ 必须在已有事务内调用</b>（本类方法没有 {@code @Transactional}，
     * 事务边界由 {@code service/LikeMessageIngestService} 持有）。自行加事务会与外层合成一个更大的事务，
     * 改变锁范围。
     *
     * @param msgId      inbox 主键，同时是 {@code operation_id}
     * @param userId     状态主体（点赞者）
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param authorId   作者（写入 payload 之外不参与 fact）
     * @param action     消息里的 action（1~4）
     * @param oldState   变更前状态（0/1/2）
     * @param newState   变更后状态（0/1/2）
     * @param occurredAt 消息的 created_at（Unix 秒；<=0 表示缺失，走当前时间）
     * @throws IllegalStateException 任一前置校验不通过 —— Go 侧同样是 {@code return error}，
     *                               会让整个事务回滚、消息重投
     */
    public void appendFact(String msgId, long userId, String targetType, String targetId,
                           long authorId, int action, int oldState, int newState, long occurredAt) {

        // Go: if p.Record.UserID <= 0 || TargetType == "" || TargetID == "" || p.Inbox.MsgId == "" { return err }
        if (userId <= 0 || targetType == null || targetType.isEmpty()
                || targetId == null || targetId.isEmpty() || msgId == null || msgId.isEmpty()) {
            throw new IllegalStateException("incomplete like fact");
        }

        String operation = operationName(action);
        if (operation == null) {
            // Go: return fmt.Errorf("invalid like operation: %d", p.Record.State)
            throw new IllegalStateException("invalid like operation: " + action);
        }

        // Go: eventID := "rtw.like." + p.Inbox.MsgId
        String eventId = LikeDeliveryValidator.FACT_EVENT_ID_PREFIX + msgId;

        // Go: eventTime := time.Unix(p.OccurredAt, 0).UTC(); if p.OccurredAt <= 0 { eventTime = time.Now().UTC() }
        Instant eventInstant = occurredAt > 0 ? Instant.ofEpochSecond(occurredAt) : Instant.now();
        String eventTime = GoCompatibleJson.rfc3339Nano(eventInstant);
        String availableAt = GoCompatibleJson.rfc3339Nano(Instant.now());

        // Go: SubjectRef: "rtw.identity/platform/" + strconv.FormatInt(UserID, 10)
        String subjectRef = "rtw.identity/platform/" + userId;

        // ⚠️ 行内 aggregate_id（state 流）与 payload.aggregate_id（target）**刻意不同**，
        //    见 Go domain_fact.go:97-100 的注释。
        String targetAggregateId = targetType + "/" + targetId;
        String streamId = "like-state/" + userId + "/" + targetType + "/" + targetId;

        // Go: 遗留未版本化事实检查 —— 命中就直接报错要求人工迁移，**不继续**
        long legacy = factOutboxMapper.countUnversionedLegacyFacts(subjectRef, targetType, targetId);
        if (legacy > 0) {
            throw new IllegalStateException(
                    "like aggregate " + streamId + " has unversioned legacy facts");
        }

        // Go: 原子取号：INSERT .. ON CONFLICT DO UPDATE SET version = version + 1 RETURNING
        LikeFactStreamEntity stream = factStreamMapper.bumpFactStreamVersion(streamId);
        if (stream == null || stream.getVersion() == null
                || stream.getVersion() < 1 || stream.getVersion() > DC_MAX_SAFE_INTEGER) {
            throw new IllegalStateException("like fact version out of DC range");
        }
        long factVersion = stream.getVersion();

        // ---------- payload（原文）----------
        ObjectNode fact = objectMapper.createObjectNode();
        fact.put("schema_version", LikeDeliveryValidator.FACT_SCHEMA_VERSION);
        fact.put("event_id", eventId);
        fact.put("event_type", LikeDeliveryValidator.FACT_EVENT_TYPE);
        fact.put("producer", LikeDeliveryValidator.FACT_PRODUCER);
        fact.put("aggregate_id", targetAggregateId);
        fact.putNull("aggregate_version");        // Go: 从不赋值 ⇒ 恒 null
        fact.put("operation_id", msgId);
        fact.put("subject_ref", subjectRef);
        fact.put("target_type", targetType);
        fact.put("target_id", targetId);
        fact.putNull("target_revision");         // Go: 从不赋值 ⇒ 恒 null
        fact.put("revision_status", "unknown");  // targetRevision 为 nil 时唯一合法值
        fact.put("operation", operation);
        fact.put("source_ref", eventId);         // Go: SourceRef: eventID ⇒ 与 event_id 相等
        fact.put("old_state", oldState);
        fact.put("new_state", newState);
        fact.put("event_time", eventTime);
        fact.put("occurred_at", eventTime);
        fact.put("available_at", availableAt);
        String payloadJson = GoCompatibleJson.write(fact);

        // ---------- 冻结信封（内嵌同一份 payload 原文）----------
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("event_id", eventId);
        envelope.put("event_type", LikeDeliveryValidator.FACT_EVENT_TYPE);
        envelope.put("schema_version", 1);       // Go: int（不是字符串），与 payload 里的 string 版本号刻意不同
        envelope.put("producer", LikeDeliveryValidator.FACT_PRODUCER);
        envelope.put("aggregate_id", streamId);  // 信封用的是 state 流 id
        envelope.put("aggregate_version", factVersion);
        envelope.put("operation_id", msgId);
        envelope.put("occurred_at", eventTime);
        // ⚠️ 内嵌 payload 用与 payload 列**完全相同**的原文
        final String envelopeJson;
        try {
            envelope.set("payload", objectMapper.readTree(payloadJson));
            envelopeJson = GoCompatibleJson.write(envelope);
        } catch (Exception e) {
            // Go: json.Marshal 失败即 return error ⇒ 整事务回滚
            throw new IllegalStateException("marshal like fact envelope failed", e);
        }

        factOutboxMapper.insertFact(eventId, LikeDeliveryValidator.FACT_EVENT_TYPE, payloadJson,
                streamId, factVersion, envelopeJson);
    }

    /**
     * 对应 Go {@code appendLikeFact} 里的
     * {@code map[int32]string{1:"like",2:"unlike",3:"dislike",4:"undislike"}}。
     *
     * @return 操作名；action 不在 1~4 时返回 {@code null}（由调用方转成 error）
     */
    private static String operationName(int action) {
        return switch (action) {
            case 1 -> "like";
            case 2 -> "unlike";
            case 3 -> "dislike";
            case 4 -> "undislike";
            default -> null;
        };
    }
}
