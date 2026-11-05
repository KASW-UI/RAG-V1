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
 * <p><b>评论正文表 ORM 实体</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/domain/CommentContentEntity.java:13-118}
 * （表 {@code comment_content}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.domain.CommentContentEntity}
 * ⇒ C 侧 {@code com.rag.comment.domain.entity.CommentContentEntity}。
 *
 * <h3>⚠️ {@code autoResultMap = true} 不可省</h3>
 * <p>{@code meta} 列是 <b>jsonb</b>，走 {@link JsonbTypeHandler}。
 * ⇒ MyBatis-Plus 的 {@code typeHandler} <b>只在 {@code autoResultMap=true} 时生效</b>
 * （否则走的是全局默认的 ResultMap，{@code @TableField(typeHandler=…)} 被忽略）
 * ⇒ ⇒ 少了这个参数 ⇒ <b>读写 jsonb 列都失败</b>（PG 报类型不匹配）。
 *
 * <h3>⚠️ {@code JsonbTypeHandler} 的落点（D3 · 收进共享层）</h3>
 * <p>B 侧是<b>域内副本</b> {@code com.rag.comment.comment.rpc.mapper.JsonbTypeHandler}
 * ⇒ C 侧收进 starter：{@code com.rag.common.persistence.JsonbTypeHandler}
 * （本域<b>零文件</b>，与 like / article 域同款）。
 *
 * <h3>⚠️ 主键用 {@code IdType.INPUT}</h3>
 * <p>本表主键 {@code comment_id} 就是 {@link CommentIndexEntity#getId()}（雪花 ID）
 * ⇒ <b>不自增</b> ⇒ {@code IdType.INPUT}。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class CommentContentEntity {
 *     private Long commentId;
 *     private String content;
 *     private String meta;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public Long getCommentId() { return commentId; }
 *     public void setCommentId(Long commentId) { this.commentId = commentId; }
 *     public String getContent() { return content; }
 *     public void setContent(String content) { this.content = content; }
 *     public String getMeta() { return meta; }
 *     public void setMeta(String meta) { this.meta = meta; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName(value = "comment_content", autoResultMap = true)
public class CommentContentEntity {

    /** 对应 B 侧 {@code CommentID int64 `primaryKey;autoIncrement:false`}（＝ CommentIndex 的雪花 ID）。 */
    @TableId(type = IdType.INPUT)
    private Long commentId;

    /** 对应 B 侧 {@code Content string `type:text;not null`}（评论正文）。 */
    private String content;

    /**
     * 对应 B 侧 {@code Meta string `type:jsonb`}（扩展内容，可空）。
     *
     * <p>⚠️ <b>写入前不校验 JSON 合法性</b> —— B 侧也不校验
     * （{@code normalizeCommentMeta} 只保证是合法 JSON）。
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String meta;

    /** 对应 B 侧 {@code CreatedAt time.Time `autoCreateTime`}。 */
    private OffsetDateTime createdAt;

    /** 对应 B 侧 {@code UpdatedAt time.Time `autoUpdateTime`}。 */
    private OffsetDateTime updatedAt;
}
