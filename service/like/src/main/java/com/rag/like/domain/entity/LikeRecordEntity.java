package com.rag.like.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>点赞记录表 ORM 实体</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/domain/LikeRecordEntity.java:14-152}
 * （表 {@code like_record}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.like.like.rpc.domain.LikeRecordEntity}
 * ⇒ C 侧 {@code com.rag.like.domain.entity.LikeRecordEntity}
 * （映射表 {@code docs/Mapping_Table/like/like.md} §0.5「{@code like/rpc/domain/} → {@code domain/entity/}」）。
 *
 * <h3>⚠️ 故意不标 {@code @TableLogic}</h3>
 * <p>B 侧用 GORM 的 {@code gorm.DeletedAt}：它会给<b>每一条</b>经由
 * {@code db.Model(&LikeRecord{})} 的查询自动加 {@code delete_at IS NULL}，
 * 而 {@code applyLikeMessage} 又用 {@code tx.Unscoped()} <b>显式绕开</b>这个过滤去读已软删的行
 * （B 侧 {@code model/like.go:195}），随后还要靠 {@code current.DeleteAt.Valid} 判定并<b>报错</b>。
 * 这两条语义相反 ⇒ 用 {@code @TableLogic} 的隐式行为表达会得到一个<b>既不是 B 侧也不是 C 侧</b>的第三种东西。
 * ⇒ C 侧改为：所有需要过滤的查询在 mapper 里<b>显式写</b> {@code delete_at IS NULL}，
 * 需要绕过的那条单独一条 SQL（{@code LikeRecordMapper#selectForUpdateIncludingDeleted}），两处一眼可见。
 *
 * <h3>⚠️ 主键用 {@code IdType.AUTO}（不是 ASSIGN_ID）</h3>
 * <p>B 侧 GORM 是 {@code autoIncrement}；DDL 用 BIGSERIAL ⇒ 让 PG 分配，行为与 B 侧一致。
 *
 * <h3>⚠️ {@code state} 的取值域 = 0/1/2（不是消息里的 1~4）</h3>
 * <p>0 无 / 1 已点赞 / 2 已点踩。
 * ⚠️ 消费消息里的 {@code action} 用的是 <b>1~4</b>（like/unlike/dislike/undislike），
 * <b>不是</b>本字段的取值域 —— 两者由消费侧的 {@code applyLikeMessage} 的 switch 转换，
 * 混淆这两套编码是本域最容易出错的地方。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class LikeRecordEntity {
 *     private Long id;
 *     private long userId;
 *     private String targetType;
 *     private String targetId;
 *     private long authorId;
 *     private int state;
 *     private long lastOperationId;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *     private OffsetDateTime deleteAt;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public long getUserId() { return userId; }
 *     public void setUserId(long userId) { this.userId = userId; }
 *     public String getTargetType() { return targetType; }
 *     public void setTargetType(String targetType) { this.targetType = targetType; }
 *     public String getTargetId() { return targetId; }
 *     public void setTargetId(String targetId) { this.targetId = targetId; }
 *     public long getAuthorId() { return authorId; }
 *     public void setAuthorId(long authorId) { this.authorId = authorId; }
 *     public int getState() { return state; }
 *     public void setState(int state) { this.state = state; }
 *     public long getLastOperationId() { return lastOperationId; }
 *     public void setLastOperationId(long lastOperationId) { this.lastOperationId = lastOperationId; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *     public OffsetDateTime getDeleteAt() { return deleteAt; }
 *     public void setDeleteAt(OffsetDateTime deleteAt) { this.deleteAt = deleteAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("like_record")
public class LikeRecordEntity {

    /** 对应 B 侧 {@code ID int64 `primaryKey;autoIncrement`}。由 PG 序列分配。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应 B 侧 {@code UserID int64 `not null`}（点赞者）。 */
    private long userId;

    /** 对应 B 侧 {@code TargetType string `varchar(32);not null`}。 */
    private String targetType;

    /** 对应 B 侧 {@code TargetID string `varchar(64);not null`}。 */
    private String targetId;

    /** 对应 B 侧 {@code AuthorID int64 `not null`}（被点赞内容作者）。 */
    private long authorId;

    /**
     * 对应 B 侧 {@code State int32 `smallint;not null;default:0`}。
     *
     * <p>取值域见类注释：0 无 / 1 已点赞 / 2 已点踩。
     */
    private int state;

    /** 对应 B 侧 {@code LastOperationID int64 `column:last_operation_id;not null;default:0`}。 */
    private long lastOperationId;

    /** 对应 B 侧 {@code CreatedAt time.Time `autoCreateTime`}。 */
    private OffsetDateTime createdAt;

    /** 对应 B 侧 {@code UpdatedAt time.Time `autoUpdateTime`}。 */
    private OffsetDateTime updatedAt;

    /** 对应 B 侧 {@code DeleteAt gorm.DeletedAt `index`}（软删除时间，可空）。 */
    private OffsetDateTime deleteAt;
}
