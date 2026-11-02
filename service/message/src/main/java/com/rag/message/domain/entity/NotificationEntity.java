package com.rag.message.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>通知表 ORM 实体</b> —— {@code message_notification} 表的 MyBatis-Plus 映射。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/domain/NotificationEntity.java}
 * （B 侧类 javadoc {@code :13-32} · 注解与字段 {@code :33-113} · 手写 accessor {@code :115-138}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.domain} →
 *       {@code com.rag.message.domain.entity}，D7 包压平）＋ <b>Lombok 化</b>
 *       （{@code @Getter} ＋ {@code @Setter} 替掉 B 侧 {@code :115-138} 的 24 行手写 accessor，
 *       「不用 Lombok 时的完整代码」见下方展开块）＋
 *       {@code @TableField(typeHandler = …)} 的<b>被引用类</b>换成本域副本
 *       {@code com.rag.message.mapper.JsonbMapTypeHandler}（包名随 D7 压平）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段名 / 类型 / 初始值 / MP 注解<b>逐条一致</b>，
 *       含 {@code autoResultMap = true}、{@code @TableField("is_read")}、
 *       {@code extra} 的 {@code new HashMap<>()} 默认值（不为 null）。
 *       ⚠️ <b>刻意不用 {@code @Data}</b>：B 侧本类<b>只有</b> getter/setter，
 *       <b>没有</b> {@code equals} / {@code hashCode} / {@code toString}
 *       ⇒ {@code @Data} 会<b>凭空新增 3 个方法</b> = 行为变化
 *       （且 {@code @Data} 生成的 {@code equals} 含 {@code Map} 字段 ⇒ 与 B 侧的「无 equals」不是一回事）
 *       ⇒ 只用 {@code @Getter} ＋ {@code @Setter}。</li>
 *   <li><b>B 出处</b>：同上（{@code .../domain/NotificationEntity.java:11-138}）。</li>
 * </ol>
 *
 * <p><b>🔴 {@code autoResultMap = true} 不是可选项（B 侧 DRIFT-MSG-V-013）</b>：
 * MyBatis-Plus <b>只有</b>在 {@code @TableName(autoResultMap = true)} 时，
 * 才会把 {@code @TableField(typeHandler = ...)} 应用到<b>自动生成的 SELECT 结果映射</b>上。
 * 少了它，读 {@code jsonb} 列拿到的是 {@code PGobject}，映射成 {@code Map} 直接失败。
 * 这与 AGENTS.md §3.3.3 记录的「断言 Bean 存在 ≠ 断言机制生效」同源 ——
 * {@code typeHandler} 写在字段上 ≠ 它在 SELECT 上生效。
 *
 * <p><b>L1 表名</b>：{@code message_notification}（B 侧 {@code @TableName}，未改动）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class NotificationEntity {
 *     private Long id;
 *     private long recipientId;
 *     private long senderId;
 *     private int senderRole;
 *     private int kind;
 *     private String title;
 *     private String content;
 *     private boolean read;
 *     private OffsetDateTime readAt;
 *     private Map<String, String> extra = new HashMap<>();
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public long getRecipientId() { return recipientId; }
 *     public void setRecipientId(long recipientId) { this.recipientId = recipientId; }
 *     public long getSenderId() { return senderId; }
 *     public void setSenderId(long senderId) { this.senderId = senderId; }
 *     public int getSenderRole() { return senderRole; }
 *     public void setSenderRole(int senderRole) { this.senderRole = senderRole; }
 *     public int getKind() { return kind; }
 *     public void setKind(int kind) { this.kind = kind; }
 *     public String getTitle() { return title; }
 *     public void setTitle(String title) { this.title = title; }
 *     public String getContent() { return content; }
 *     public void setContent(String content) { this.content = content; }
 *     public boolean isRead() { return read; }
 *     public void setRead(boolean read) { this.read = read; }
 *     public OffsetDateTime getReadAt() { return readAt; }
 *     public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
 *     public Map<String, String> getExtra() { return extra; }
 *     public void setExtra(Map<String, String> extra) { this.extra = extra; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName(value = "message_notification", autoResultMap = true)
public class NotificationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对应 Go {@code RecipientID int64 gorm:"column:recipient_id;not null"}。 */
    private long recipientId;

    /** 对应 Go {@code SenderID int64 gorm:"column:sender_id;not null"}。 */
    private long senderId;

    /** 对应 Go {@code SenderRole int32 gorm:"column:sender_role;not null"}（pb.SenderRole 的 int 形式）。 */
    private int senderRole;

    /** 对应 Go {@code Kind int32 gorm:"column:kind;not null"}（pb.NotificationKind 的 int 形式）。 */
    private int kind;

    /** 对应 Go {@code Title string gorm:"column:title;type:varchar(255);not null"}。 */
    private String title;

    /** 对应 Go {@code Content string gorm:"column:content;type:text;not null"}。 */
    private String content;

    /**
     * 对应 Go {@code IsRead bool gorm:"column:is_read;not null;default:false"}。
     *
     * <p><b>字段名必须是 {@code read} 而不是 {@code isRead}（2026-10-21 修，运行期致命）</b>：
     * MyBatis-Plus 的 {@code LambdaXxxWrapper} 解析 {@code Entity::method} 时，
     * 会按 JavaBean 约定剥掉 {@code get}/{@code is} 前缀再拿<b>属性名</b>去查
     * {@code TableInfo.propertyMap}。
     * <ul>
     *   <li>字段叫 {@code isRead} ⇒ 属性名 {@code isRead}、getter {@code isRead()} ⇒
     *       lambda 解析成属性 {@code read} ⇒ <b>propertyMap 里没有 ⇒ 运行期抛
     *       {@code MybatisPlusException: can not find lambda cache for this property [read]}</b></li>
     *   <li>字段叫 {@code read}、getter {@code isRead()} ⇒ lambda 解析成属性 {@code read} ⇒ 命中。</li>
     * </ul>
     *
     * <p>实测（B 侧 {@code Probe} 临时探针，MP 3.5.14）：
     * <pre>
     * 修复前：keySet=[..., isRead-&gt;isRead, ...]
     *         NotificationEntity::isRead → FAILED: can not find lambda cache for this property [read]
     *         NotificationEntity::getReadAt → OK
     * 修复后（字段 read + @TableField("is_read")）：
     *         keySet=[read-&gt;is_read, readAt-&gt;read_at, recipientId-&gt;recipient_id]
     *         E::isRead → (is_read = ? AND recipient_id = ?)   ✅
     * </pre>
     *
     * <p><b>影响面</b>：{@code NotificationMapper.countUnread} /
     * {@code markAllReadByRecipient} / {@code markRead} 三处都用
     * {@code NotificationEntity::isRead} 构造条件 ⇒ 修复前
     * <b>listNotifications(unreadOnly) / getUnreadSummary / markAllNotificationsRead /
     * markNotificationRead 四条业务路径一调即抛异常</b>。
     * 之所以长期没暴露：message-rpc <b>此前没有任何一条会真正执行 SQL 的测试</b>，
     * 全部是纯 Mockito mock（AGENTS.md §3.3.3：mock 数据层不装配 MP 元数据，测不到这条）。
     *
     * <p>{@code @TableField("is_read")} 显式指定列名，不依赖
     * {@code map-underscore-to-camel-case} 的双向推断。
     *
     * <p><b>本仓的等价物</b>：Lombok 的 {@code @Getter} 对 {@code boolean read} 生成的正是
     * {@code isRead()}、{@code @Setter} 生成 {@code setRead(boolean)} ⇒ 与 B 侧手写版<b>逐字一致</b>
     * （见类 javadoc 的展开块）。
     */
    @TableField("is_read")
    private boolean read;

    /** 对应 Go {@code ReadAt *time.Time gorm:"column:read_at"}。 */
    private OffsetDateTime readAt;

    /**
     * 对应 Go {@code Extra JSONMap gorm:"column:extra;type:jsonb"}。
     * JSONB 类型：用 {@link com.rag.message.mapper.JsonbMapTypeHandler} 绑定
     * （**不是** MP 自带的 JacksonTypeHandler —— 它用 setString 绑定会被 jsonb 列拒绝，见该类注释）；
     * 入参默认空 map（不为 null），避免 wire 序列化丢字段。
     */
    @TableField(typeHandler = com.rag.message.mapper.JsonbMapTypeHandler.class)
    private Map<String, String> extra = new HashMap<>();

    /** 对应 Go {@code CreatedAt time.Time gorm:"column:created_at;autoCreateTime"}。 */
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    /** 对应 Go {@code UpdatedAt time.Time gorm:"column:updated_at;autoUpdateTime"}。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
