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
 * <p><b>会话内单条消息表 ORM 实体</b> —— {@code message_conversation_message} 表的 MyBatis-Plus 映射。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/domain/ConversationMessageEntity.java}
 * （B 侧类 javadoc {@code :11-15} · 注解与字段 {@code :17-71} · 手写 accessor {@code :73-92}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.domain} →
 *       {@code com.rag.message.domain.entity}，D7 包压平）＋ <b>Lombok 化</b>
 *       （{@code @Getter} ＋ {@code @Setter} 替掉 B 侧 {@code :73-92} 的 20 行手写 accessor，
 *       「不用 Lombok 时的完整代码」见下方展开块）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段名 / 类型 / MP 注解<b>逐条一致</b>，
 *       含「已读」字段那条<b>不可改的</b> {@code read} 命名（机理见该字段的 javadoc）。
 *       ⚠️ <b>刻意不用 {@code @Data}</b>：B 侧本类<b>只有</b> getter/setter，
 *       <b>没有</b> {@code equals} / {@code hashCode} / {@code toString}
 *       ⇒ {@code @Data} 会<b>凭空新增 3 个方法</b> = 行为变化 ⇒ 只用 {@code @Getter} ＋ {@code @Setter}。</li>
 *   <li><b>B 出处</b>：同上（{@code .../domain/ConversationMessageEntity.java:11-92}）。</li>
 * </ol>
 *
 * <p><b>框架职责</b>：{@code @TableName} 钉表名、{@code @TableId(type = IdType.ASSIGN_ID)} 钉雪花主键、
 * {@code @TableField(fill = FieldFill.INSERT)} 把 {@code created_at} 交给 MyBatis-Plus 自动填充。
 *
 * <p><b>L1 表名</b>：{@code message_conversation_message}（B 侧 {@code @TableName}，未改动）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class ConversationMessageEntity {
 *     private Long id;
 *     private long conversationId;
 *     private long senderId;
 *     private int senderRole;
 *     private long recipientId;
 *     private int recipientRole;
 *     private String content;
 *     private boolean read;
 *     private OffsetDateTime readAt;
 *     private OffsetDateTime createdAt;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public long getConversationId() { return conversationId; }
 *     public void setConversationId(long conversationId) { this.conversationId = conversationId; }
 *     public long getSenderId() { return senderId; }
 *     public void setSenderId(long senderId) { this.senderId = senderId; }
 *     public int getSenderRole() { return senderRole; }
 *     public void setSenderRole(int senderRole) { this.senderRole = senderRole; }
 *     public long getRecipientId() { return recipientId; }
 *     public void setRecipientId(long recipientId) { this.recipientId = recipientId; }
 *     public int getRecipientRole() { return recipientRole; }
 *     public void setRecipientRole(int recipientRole) { this.recipientRole = recipientRole; }
 *     public String getContent() { return content; }
 *     public void setContent(String content) { this.content = content; }
 *     public boolean isRead() { return read; }
 *     public void setRead(boolean read) { this.read = read; }
 *     public OffsetDateTime getReadAt() { return readAt; }
 *     public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("message_conversation_message")
public class ConversationMessageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对应 Go {@code ConversationID int64 gorm:"column:conversation_id;not null"}。 */
    private long conversationId;

    /** 对应 Go {@code SenderID int64 gorm:"column:sender_id;not null"}。 */
    private long senderId;

    /** 对应 Go {@code SenderRole int32 gorm:"column:sender_role;not null"}。 */
    private int senderRole;

    /** 对应 Go {@code RecipientID int64 gorm:"column:recipient_id;not null"}。 */
    private long recipientId;

    /** 对应 Go {@code RecipientRole int32 gorm:"column:recipient_role;not null"}。 */
    private int recipientRole;

    /** 对应 Go {@code Content string gorm:"column:content;type:text;not null"}。 */
    private String content;

    /**
     * 对应 Go {@code IsRead bool `gorm:"column:is_read;not null;default:false"`}。
     *
     * <p>🔴 <b>字段名必须是 {@code read} 而不是 {@code isRead}（2026-10-23 修，DRIFT-MSG-V-017）</b>，
     * 与 {@link NotificationEntity#read} 是<b>同一条教训、同一个修法</b>
     * （那个类 2026-10-21 已经付过运行期代价，本模块当时只改了一半）。
     *
     * <p><b>机理</b>：MyBatis-Plus 的 lambda 列名解析依赖
     * {@code TableInfo.propertyMap}，其 key 由<b>字段</b>决定；
     * 而 {@code E::isRead} 这种方法引用指向的是<b>方法名</b>。
     * 字段叫 {@code isRead} 时两者对不上 ⇒ 抛
     * {@code MybatisPlusException: can not find lambda cache for this property [read]}。
     * 字段叫 {@code read}、getter 仍写 {@code isRead()} 时两者统一到 {@code read} ⇒ 命中。
     *
     * <p>⚠️ <b>getter/setter 签名刻意保持 {@code isRead()} / {@code setRead(boolean)} 不变</b>：
     * 改签名会波及 {@code MessageRpcSupport:130/149} 等调用点，
     * 而那与本条缺陷<b>无关</b>（AGENTS.md §3.2：不顺手做无行为变更的重命名）。
     *
     * <p>⚠️ <b>本条修复前无调用方</b>：生产路径不走 {@code markAllReadByRecipient}
     * （走的是手写 {@code @Update} 的 {@code markReadByConversationAndRecipient}），
     * 所以这是<b>潜伏缺陷</b>，今天<b>无可观察差异</b> —— 修它是为了拆掉
     * 「一旦有人接线就抛异常」那颗雷，<b>不是为了改变今天的行为</b>。
     *
     * <p><b>本仓的等价物</b>：Lombok 的 {@code @Getter} 对 {@code boolean read} 生成的正是
     * {@code isRead()}、{@code @Setter} 生成 {@code setRead(boolean)} ⇒ 与 B 侧手写版<b>逐字一致</b>
     * （见类 javadoc 的展开块）。
     */
    @TableField("is_read")
    private boolean read;

    /** 对应 Go {@code ReadAt *time.Time gorm:"column:read_at"}。 */
    private OffsetDateTime readAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
