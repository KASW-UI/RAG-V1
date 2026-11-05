package com.rag.comment.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>评论索引表 ORM 实体</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/domain/CommentIndexEntity.java:13-178}
 * （表 {@code comment_index}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.domain.CommentIndexEntity}
 * ⇒ C 侧 {@code com.rag.comment.domain.entity.CommentIndexEntity}。
 *
 * <h3>⚠️ 主键用 {@code IdType.INPUT}（不是 AUTO · 不是 ASSIGN_ID）</h3>
 * <p>B 侧 GORM 是 {@code primaryKey;autoIncrement:false} ⇒ 值来自<b>雪花算法</b>
 * （由 Kafka 消息携带落库）⇒ DDL 里是裸 {@code BIGINT PRIMARY KEY}（无 IDENTITY / BIGSERIAL）。
 * <p>⇒ C 侧用 {@code IdType.INPUT}：<b>MyBatis-Plus 不介入主键生成</b>，
 * 用实体里已带上的雪花 ID 原样写入。
 * ⚠️ 若误用 {@code AUTO} ⇒ PG 会分配一个与雪花 ID 无关的序列值 ⇒ <b>ID 冲突</b>。
 * ⚠️ 若误用 {@code ASSIGN_ID} ⇒ MP 会自己生成一个雪花 ID ⇒ <b>覆盖消息携带的真 ID</b>。
 *
 * <h3>⚠️ 「评论正文的宽表拆分」</h3>
 * <p>本表存<b>统计</b>（点赞数 / 点踩数 / 回复数 / 状态），
 * 正文在 {@link CommentContentEntity}（表 {@code comment_content}）。
 * 二者靠 {@code comment_id ↔ id} 一一对应，读取时<b>分两次批量读再按下标配对</b>
 * ⇒ ⇒ <b>不能</b>用一条 JOIN 替代（那会改变「正文缺失时索引仍可见」的既有行为）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class CommentIndexEntity {
 *     private Long id;
 *     private String targetType;
 *     private String targetId;
 *     private long userId;
 *     private long rootId;
 *     private long parentId;
 *     private long likeCount;
 *     private long dislikeCount;
 *     private long replyCount;
 *     private int state;
 *     private long attribute;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public String getTargetType() { return targetType; }
 *     public void setTargetType(String targetType) { this.targetType = targetType; }
 *     public String getTargetId() { return targetId; }
 *     public void setTargetId(String targetId) { this.targetId = targetId; }
 *     public long getUserId() { return userId; }
 *     public void setUserId(long userId) { this.userId = userId; }
 *     public long getRootId() { return rootId; }
 *     public void setRootId(long rootId) { this.rootId = rootId; }
 *     public long getParentId() { return parentId; }
 *     public void setParentId(long parentId) { this.parentId = parentId; }
 *     public long getLikeCount() { return likeCount; }
 *     public void setLikeCount(long likeCount) { this.likeCount = likeCount; }
 *     public long getDislikeCount() { return dislikeCount; }
 *     public void setDislikeCount(long dislikeCount) { this.dislikeCount = dislikeCount; }
 *     public long getReplyCount() { return replyCount; }
 *     public void setReplyCount(long replyCount) { this.replyCount = replyCount; }
 *     public int getState() { return state; }
 *     public void setState(int state) { this.state = state; }
 *     public long getAttribute() { return attribute; }
 *     public void setAttribute(long attribute) { this.attribute = attribute; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("comment_index")
public class CommentIndexEntity {

    /** 对应 B 侧 {@code ID int64 `primaryKey;autoIncrement:false`}（雪花 ID，由 Kafka 消息携带）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 对应 B 侧 {@code TargetType string `type:varchar(32);not null`}。 */
    private String targetType;

    /** 对应 B 侧 {@code TargetID string `type:varchar(64);not null`}。 */
    private String targetId;

    /** 对应 B 侧 {@code UserID int64 `not null`}（发布者 uid）。 */
    private long userId;

    /** 对应 B 侧 {@code RootID int64 `not null;default:0`}（根评论 ID；0 = 根评论本身）。 */
    private long rootId;

    /** 对应 B 侧 {@code ParentID int64 `not null;default:0`}（父评论 ID）。 */
    private long parentId;

    /** 对应 B 侧 {@code LikeCount int64 `not null;default:0`}。 */
    private long likeCount;

    /** 对应 B 侧 {@code DislikeCount int64 `not null;default:0`}。 */
    private long dislikeCount;

    /** 对应 B 侧 {@code ReplyCount int64 `not null;default:0`}（子评论数）。 */
    private long replyCount;

    /** 对应 B 侧 {@code State int32 `not null;default:0`}（0 正常 / 1 审核 / 2 删除 / 3 封禁）。 */
    private int state;

    /** 对应 B 侧 {@code Attribute int64 `not null;default:0`}（属性位图）。 */
    private long attribute;

    /** 对应 B 侧 {@code CreatedAt time.Time `autoCreateTime`}。 */
    private OffsetDateTime createdAt;

    /** 对应 B 侧 {@code UpdatedAt time.Time `autoUpdateTime`}。 */
    private OffsetDateTime updatedAt;
}
