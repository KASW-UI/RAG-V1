package com.rag.like.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>领域事实发件箱 ORM 实体</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/domain/LikeDomainFactOutboxEntity.java:19-333}
 * （表 {@code like_domain_fact_outbox}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.domain.LikeDomainFactOutboxEntity}
 * ⇒ C 侧 {@code com.rag.like.domain.entity.LikeDomainFactOutboxEntity}。
 *
 * <h3>三列组合定义了本表的语义边界</h3>
 * <ul>
 *   <li>{@code payload} — 事实正文，业务侧读它；</li>
 *   <li>{@code delivery_envelope} — <b>冻结信封</b>，投递后不可变；
 *       fact_dispatch 在投递前会逐字段校验它与 payload / 行内元数据一致，
 *       不一致就把该行标成「阻塞」并要求人工迁移（不重试、不丢）；</li>
 *   <li>technical_* 四列 — DataCenter 的技术回执，authority 权威读依赖它们证明「确实送到了」。</li>
 * </ul>
 *
 * <p>两个 JSON 列都是 <b>jsonb</b>（与 {@link LikeOutboxEventEntity#getPayload()} 的 json 不同）。
 *
 * <h3>⚠️ {@code payload} 与 {@code deliveryEnvelope} <b>刻意保持 String</b></h3>
 * <p>B 侧的 {@code validLikeDelivery} 拿
 * {@code canonicalHash(event.Payload)}（信封里内嵌的那段原始字节）与
 * {@code canonicalHash([]byte(row.Payload))}（本列原始字节）<b>逐字比对</b>，
 * 只在「JSON 规范化」之后相等。若这里走 POJO 往返，信封内嵌的 payload 会由 Jackson
 * 重新序列化，字段顺序、null 被省略还是被写成 null 都可能与本列不同 ⇒ 校验必然失败。
 * ⇒ 按 B 侧的做法：存原文，在 {@code service/LikeDeliveryValidator} 里再 StrictDecode 成 POJO。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class LikeDomainFactOutboxEntity {
 *     private String eventId;
 *     private String eventType;
 *     private String payload;
 *     private String aggregateId;
 *     private Long factVersion;
 *     private String deliveryEnvelope;
 *     private int deliveryStatus;
 *     private int retryCount;
 *     private String technicalReceiptId;
 *     private String technicalInputHash;
 *     private long technicalOffset;
 *     private OffsetDateTime technicalReceivedAt;
 *     private OffsetDateTime deliveredAt;
 *     private OffsetDateTime createdAt;
 *
 *     public String getEventId() { return eventId; }
 *     public void setEventId(String eventId) { this.eventId = eventId; }
 *     public String getEventType() { return eventType; }
 *     public void setEventType(String eventType) { this.eventType = eventType; }
 *     public String getPayload() { return payload; }
 *     public void setPayload(String payload) { this.payload = payload; }
 *     public String getAggregateId() { return aggregateId; }
 *     public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
 *     public Long getFactVersion() { return factVersion; }
 *     public void setFactVersion(Long factVersion) { this.factVersion = factVersion; }
 *     public String getDeliveryEnvelope() { return deliveryEnvelope; }
 *     public void setDeliveryEnvelope(String deliveryEnvelope) { this.deliveryEnvelope = deliveryEnvelope; }
 *     public int getDeliveryStatus() { return deliveryStatus; }
 *     public void setDeliveryStatus(int deliveryStatus) { this.deliveryStatus = deliveryStatus; }
 *     public int getRetryCount() { return retryCount; }
 *     public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
 *     public String getTechnicalReceiptId() { return technicalReceiptId; }
 *     public void setTechnicalReceiptId(String technicalReceiptId) { this.technicalReceiptId = technicalReceiptId; }
 *     public String getTechnicalInputHash() { return technicalInputHash; }
 *     public void setTechnicalInputHash(String technicalInputHash) { this.technicalInputHash = technicalInputHash; }
 *     public long getTechnicalOffset() { return technicalOffset; }
 *     public void setTechnicalOffset(long technicalOffset) { this.technicalOffset = technicalOffset; }
 *     public OffsetDateTime getTechnicalReceivedAt() { return technicalReceivedAt; }
 *     public void setTechnicalReceivedAt(OffsetDateTime technicalReceivedAt) { this.technicalReceivedAt = technicalReceivedAt; }
 *     public OffsetDateTime getDeliveredAt() { return deliveredAt; }
 *     public void setDeliveredAt(OffsetDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("like_domain_fact_outbox")
public class LikeDomainFactOutboxEntity {

    /** 对应 B 侧 {@code EventID string `column:event_id;primaryKey;type:varchar(128)`}。 */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** 对应 B 侧 {@code EventType string `column:event_type;type:varchar(64);not null`}。 */
    private String eventType;

    /**
     * 对应 B 侧 {@code Payload string `column:payload;type:jsonb;not null`}。
     *
     * <p><b>刻意保持 String，不映射成 POJO</b> —— 理由见类注释。
     */
    private String payload;

    /**
     * 对应 B 侧 {@code AggregateID *string `column:aggregate_id;type:varchar(128);uniqueIndex:uk_like_fact_stream`}。
     *
     * <p><b>B 侧写入值是 state 流的 id</b>（{@code like-state/{userId}/{type}/{id}}），
     * 而 <b>payload 里那个 aggregate_id 是 target</b>（{@code {type}/{id}}）—— 两者刻意不同，
     * 见 B 侧 {@code domain_fact.go:97-100} 的注释。不要以为它们该相等。
     */
    private String aggregateId;

    /** 对应 B 侧 {@code FactVersion *int64 `column:fact_version;uniqueIndex:uk_like_fact_stream`}。 */
    private Long factVersion;

    /**
     * 对应 B 侧 {@code DeliveryEnvelope *string `column:delivery_envelope;type:jsonb`}。冻结信封。
     *
     * <p>同样保持 String，理由见 {@link #payload}。可空：B 侧只有 appendLikeFact 写入时才非空。
     */
    private String deliveryEnvelope;

    /**
     * 对应 B 侧 {@code DeliveryStatus int32 `column:delivery_status;not null;default:0`}。
     *
     * <p>⚠️ B 侧 {@code int32} 落 PG 是 <b>integer</b>（不是 BIGINT）——
     * GORM 的 {@code DataTypeOf}：{@code size <= 32 → integer}；且 B 侧的
     * {@code V6__align_like_domain_fact_outbox_with_go.sql} 已把这两列 ALTER 成 {@code integer}。
     * 取值用 {@code Wire.DeliveryStatus} 的 code：0 待投递 / 1 已接受 / 2 失败 / 3 阻塞。
     */
    private int deliveryStatus;

    /** 对应 B 侧 {@code RetryCount int32 `column:retry_count;not null;default:0`}。 */
    private int retryCount;

    /** 对应 B 侧 {@code TechnicalReceiptID string `column:technical_receipt_id;type:varchar(128);not null;default:''`}。 */
    private String technicalReceiptId;

    /** 对应 B 侧 {@code TechnicalInputHash string `column:technical_input_hash;type:char(64);not null;default:''`}。 */
    private String technicalInputHash;

    /** 对应 B 侧 {@code TechnicalOffset int64 `column:technical_offset;not null;default:0`}。 */
    private long technicalOffset;

    /** 对应 B 侧 {@code TechnicalReceivedAt *time.Time `column:technical_received_at`}。 */
    private OffsetDateTime technicalReceivedAt;

    /** 对应 B 侧 {@code DeliveredAt *time.Time `column:delivered_at`}。 */
    private OffsetDateTime deliveredAt;

    /** 对应 B 侧 {@code CreatedAt time.Time `column:created_at;not null;default:CURRENT_TIMESTAMP`}。 */
    private OffsetDateTime createdAt;
}
