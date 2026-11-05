package com.rag.comment.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.common.persistence.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>领域事实 outbox 表 ORM 实体</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/domain/CommentDomainFactOutboxEntity.java:13-197}
 * （表 {@code comment_domain_fact_outbox}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.domain.CommentDomainFactOutboxEntity}
 * ⇒ C 侧 {@code com.rag.comment.domain.entity.CommentDomainFactOutboxEntity}。
 *
 * <h3>⚠️ 本表承载 comment 域的三类事实</h3>
 * <p>{@code community.comment.{created,deleted,interaction}} ⇒ append-only、
 * 事务内冻结信封、事务内分配 DC 版本号。
 * <p>⚠️ 它与 article / hot / like 域的事实表<b>没有共享键</b>，
 * 只是共用 {@code common} 的 {@code Wire} 契约 ⇒ <b>别把两域的表混谈</b>。
 *
 * <h3>⚠️ {@code autoResultMap = true} 不可省</h3>
 * <p>{@code payload} 与 {@code delivery_envelope} 两列是 <b>jsonb</b>，
 * 走 {@link JsonbTypeHandler}。
 * ⇒ MyBatis-Plus 的 {@code typeHandler} <b>只在 {@code autoResultMap=true} 时生效</b>
 * ⇒ ⇒ 少了这个参数 ⇒ <b>读写 jsonb 列都失败</b>。
 *
 * <h3>⚠️ 主键用 {@code IdType.INPUT}</h3>
 * <p>主键 {@code event_id} 由业务侧给定（事件 ID）⇒ MP 不介入。
 *
 * <h3>⚠️ 「投递元数据可变，来源身份与冻结信封不可变」</h3>
 * <p>DDL 里有 {@code comment_fact_identity_immutable} 触发器
 * （BEFORE UPDATE / FOR EACH ROW）：{@code event_id} / {@code event_type} / {@code payload} /
 * {@code aggregate_id} / {@code fact_version} / {@code delivery_envelope}
 * 任一列变动即 {@code RAISE EXCEPTION}。
 * ⇒ 正常投递路径只 UPDATE {@code delivery_status} / {@code retry_count} /
 * {@code technical_*} / {@code delivered_at} ⇒ <b>永远不触发</b>；
 * 它拦的是「有人事后改写已冻结的事实」这一类事故。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class CommentDomainFactOutboxEntity {
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
@TableName(value = "comment_domain_fact_outbox", autoResultMap = true)
public class CommentDomainFactOutboxEntity {

    /** 对应 B 侧 {@code EventID string `column:event_id;primaryKey;type:varchar(128)`}。 */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** 对应 B 侧 {@code EventType string `type:varchar(64);not null`}。 */
    private String eventType;

    /** 对应 B 侧 {@code Payload string `type:jsonb;not null`}。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payload;

    /** 对应 B 侧 {@code AggregateID *string `type:varchar(128)`}。 */
    private String aggregateId;

    /** 对应 B 侧 {@code FactVersion *int64}。 */
    private Long factVersion;

    /** 对应 B 侧 {@code DeliveryEnvelope *string `type:jsonb`}（事务内冻结的信封）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String deliveryEnvelope;

    /** 对应 B 侧 {@code DeliveryStatus int32}（0 待投递 / 1 已接受 / 2 失败 / 3 阻塞）。 */
    private int deliveryStatus;

    /** 对应 B 侧 {@code RetryCount int32}。 */
    private int retryCount;

    /** 对应 B 侧 {@code TechnicalReceiptID string `type:varchar(128)`}。 */
    private String technicalReceiptId;

    /** 对应 B 侧 {@code TechnicalInputHash string `type:char(64)`}。 */
    private String technicalInputHash;

    /** 对应 B 侧 {@code TechnicalOffset int64}。 */
    private long technicalOffset;

    /** 对应 B 侧 {@code TechnicalReceivedAt *time.Time}（可空）。 */
    private OffsetDateTime technicalReceivedAt;

    /** 对应 B 侧 {@code DeliveredAt *time.Time}（可空）。 */
    private OffsetDateTime deliveredAt;

    /** 对应 B 侧 {@code CreatedAt time.Time `not null;default:CURRENT_TIMESTAMP`}。 */
    private OffsetDateTime createdAt;
}
