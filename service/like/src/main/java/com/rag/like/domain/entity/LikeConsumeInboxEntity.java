package com.rag.like.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>消费收件箱 ORM 实体</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/domain/LikeConsumeInboxEntity.java:14-106}
 * （表 {@code like_consume_inbox}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.domain.LikeConsumeInboxEntity}
 * ⇒ C 侧 {@code com.rag.like.domain.entity.LikeConsumeInboxEntity}。
 *
 * <h3>⚠️ 主键是字符串 msg_id（不是自增序列）</h3>
 * <p>幂等完全建立在 Kafka 消息的雪花 msg_id 上。
 * 插入走 {@code INSERT ... ON CONFLICT (msg_id) DO NOTHING}，靠 {@code RowsAffected == 0}
 * 判断「这条消息之前来过」，见 {@code service/LikeMessageIngestService}。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class LikeConsumeInboxEntity {
 *     private String msgId;
 *     private String payloadHash;
 *     private String topic;
 *     private String consumer;
 *     private int status;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public String getMsgId() { return msgId; }
 *     public void setMsgId(String msgId) { this.msgId = msgId; }
 *     public String getPayloadHash() { return payloadHash; }
 *     public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
 *     public String getTopic() { return topic; }
 *     public void setTopic(String topic) { this.topic = topic; }
 *     public String getConsumer() { return consumer; }
 *     public void setConsumer(String consumer) { this.consumer = consumer; }
 *     public int getStatus() { return status; }
 *     public void setStatus(int status) { this.status = status; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("like_consume_inbox")
public class LikeConsumeInboxEntity {

    /** 对应 B 侧 {@code MsgId string `primaryKey;type:varchar(64)`}。 */
    @TableId(type = IdType.INPUT)
    private String msgId;

    /**
     * 对应 B 侧 {@code PayloadHash string `varchar(64);not null;default:''`}。
     *
     * <p>语义：同一条 msg_id 若带着<b>不同业务载荷</b>重投，视为消息 ID 被复用，
     * 消费侧直接报错（B 侧：{@code "like message %s reused with conflicting payload"}）。
     * 由 {@code service/LikeMessageDigest} 计算。
     */
    private String payloadHash;

    /** 对应 B 侧 {@code Topic string `varchar(64);not null`}。B 侧固定字面量 {@code "like-topic"}。 */
    private String topic;

    /** 对应 B 侧 {@code Consumer string `varchar(64);not null`}。B 侧固定字面量 {@code "like_update_service"}。 */
    private String consumer;

    /** 对应 B 侧 {@code Status int32 `smallint;not null;default:0`}：0 处理中、1 已完成。 */
    private int status;

    /** 对应 B 侧 {@code CreatedAt time.Time `autoCreateTime`}。 */
    private OffsetDateTime createdAt;

    /** 对应 B 侧 {@code UpdatedAt time.Time `autoUpdateTime`}。 */
    private OffsetDateTime updatedAt;
}
