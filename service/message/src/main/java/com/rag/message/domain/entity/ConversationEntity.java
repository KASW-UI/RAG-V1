package com.rag.message.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>会话表 ORM 实体</b> —— {@code message_conversation} 表的 MyBatis-Plus 映射。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/domain/ConversationEntity.java}
 * （B 侧类 javadoc {@code :11-19} · 注解与字段 {@code :20-68} · 手写 accessor {@code :70-99}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.domain} →
 *       {@code com.rag.message.domain.entity}，D7 包压平）＋ <b>Lombok 化</b>
 *       （{@code @Getter} ＋ {@code @Setter} 替掉 B 侧 {@code :70-99} 的 30 行手写 accessor，
 *       「不用 Lombok 时的完整代码」见下方展开块）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段名 / 类型 / 初始值 / MP 注解<b>逐条一致</b>。
 *       ⚠️ <b>刻意不用 {@code @Data}</b>：B 侧本类<b>只有</b> getter/setter，
 *       <b>没有</b> {@code equals} / {@code hashCode} / {@code toString}
 *       ⇒ {@code @Data} 会<b>凭空新增 3 个方法</b> ⇒ 那是<b>行为变化</b>（集合去重口径 / 日志形态都变）
 *       ⇒ 只用 {@code @Getter} ＋ {@code @Setter}（D18 · R7「不多不少」）。</li>
 *   <li><b>B 出处</b>：同上（{@code .../domain/ConversationEntity.java:11-99}）。</li>
 * </ol>
 *
 * <p><b>框架职责</b>：{@code @TableName} 钉表名、{@code @TableId(type = IdType.ASSIGN_ID)} 钉雪花主键、
 * {@code @TableField(fill = …)} 把 {@code created_at} / {@code updated_at} 交给 MyBatis-Plus 的
 * {@code FieldFill} 自动填充（不依赖 {@code map-underscore-to-camel-case} 之外的额外配置）。
 *
 * <p><b>L1 表名</b>：{@code message_conversation}（B 侧 {@code @TableName}，未改动）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class ConversationEntity {
 *     private Long id;
 *     private int participant1Role;
 *     private long participant1Id;
 *     private int participant2Role;
 *     private long participant2Id;
 *     private int status;
 *     private int pendingSenderRole;
 *     private long pendingSenderId;
 *     private int createdByRole;
 *     private long createdById;
 *     private long lastMessageId;
 *     private String lastMessagePreview = "";
 *     private OffsetDateTime lastMessageAt;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public int getParticipant1Role() { return participant1Role; }
 *     public void setParticipant1Role(int participant1Role) { this.participant1Role = participant1Role; }
 *     public long getParticipant1Id() { return participant1Id; }
 *     public void setParticipant1Id(long participant1Id) { this.participant1Id = participant1Id; }
 *     public int getParticipant2Role() { return participant2Role; }
 *     public void setParticipant2Role(int participant2Role) { this.participant2Role = participant2Role; }
 *     public long getParticipant2Id() { return participant2Id; }
 *     public void setParticipant2Id(long participant2Id) { this.participant2Id = participant2Id; }
 *     public int getStatus() { return status; }
 *     public void setStatus(int status) { this.status = status; }
 *     public int getPendingSenderRole() { return pendingSenderRole; }
 *     public void setPendingSenderRole(int pendingSenderRole) { this.pendingSenderRole = pendingSenderRole; }
 *     public long getPendingSenderId() { return pendingSenderId; }
 *     public void setPendingSenderId(long pendingSenderId) { this.pendingSenderId = pendingSenderId; }
 *     public int getCreatedByRole() { return createdByRole; }
 *     public void setCreatedByRole(int createdByRole) { this.createdByRole = createdByRole; }
 *     public long getCreatedById() { return createdById; }
 *     public void setCreatedById(long createdById) { this.createdById = createdById; }
 *     public long getLastMessageId() { return lastMessageId; }
 *     public void setLastMessageId(long lastMessageId) { this.lastMessageId = lastMessageId; }
 *     public String getLastMessagePreview() { return lastMessagePreview; }
 *     public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }
 *     public OffsetDateTime getLastMessageAt() { return lastMessageAt; }
 *     public void setLastMessageAt(OffsetDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("message_conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对应 Go {@code Participant1Role int32 gorm:"column:participant1_role;not null"}。 */
    private int participant1Role;

    /** 对应 Go {@code Participant1ID int64 gorm:"column:participant1_id;not null"}。 */
    private long participant1Id;

    /** 对应 Go {@code Participant2Role int32 gorm:"column:participant2_role;not null"}。 */
    private int participant2Role;

    /** 对应 Go {@code Participant2ID int64 gorm:"column:participant2_id;not null"}。 */
    private long participant2Id;

    /** 对应 Go {@code Status int32 gorm:"column:status;not null;default:1"}（pb.ConversationStatus）。 */
    private int status;

    /** 对应 Go {@code PendingSenderRole int32 gorm:"column:pending_sender_role;not null;default:0"}。 */
    private int pendingSenderRole;

    /** 对应 Go {@code PendingSenderID int64 gorm:"column:pending_sender_id;not null;default:0"}。 */
    private long pendingSenderId;

    /** 对应 Go {@code CreatedByRole int32 gorm:"column:created_by_role;not null"}。 */
    private int createdByRole;

    /** 对应 Go {@code CreatedByID int64 gorm:"column:created_by_id;not null"}。 */
    private long createdById;

    /** 对应 Go {@code LastMessageID int64 gorm:"column:last_message_id;not null;default:0"}。 */
    private long lastMessageId;

    /**
     * 对应 Go {@code LastMessagePreview string gorm:"column:last_message_preview;type:varchar(255);not null;default:''"}。
     */
    private String lastMessagePreview = "";

    /** 对应 Go {@code LastMessageAt time.Time gorm:"column:last_message_at;not null;index"}。 */
    private OffsetDateTime lastMessageAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
