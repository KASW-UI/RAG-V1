package com.rag.comment.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * <p><b>事实流版本号表 ORM 实体</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/domain/CommentFactStreamEntity.java:13-45}
 * （表 {@code comment_fact_stream}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.domain.CommentFactStreamEntity}
 * ⇒ C 侧 {@code com.rag.comment.domain.entity.CommentFactStreamEntity}。
 *
 * <h3>⚠️ 主键用 {@code IdType.INPUT}</h3>
 * <p>主键是 {@code aggregate_id}（字符串），由业务侧给定 ⇒ MP 不介入。
 *
 * <h3>⚠️ 本表虽只有两列，却是「事实流版本号的唯一权威」</h3>
 * <p>发事实前执行（逐字照抄 B 侧）：
 * <pre>
 * INSERT INTO comment_fact_stream (aggregate_id, version) VALUES (?, 1)
 * ON CONFLICT (aggregate_id) DO UPDATE SET version = comment_fact_stream.version + 1
 * RETURNING aggregate_id, version
 * </pre>
 * ⇒ 原子递增并取回新版本。
 * ⚠️ 注意 {@code version = comment_fact_stream.version + 1} 里的<b>表名限定前缀</b>：
 * PG 的 {@code ON CONFLICT DO UPDATE} 里必须用表名限定才能引用既有行值
 * （写成裸 {@code version + 1} 会报「column reference is ambiguous」）。
 * ⇒ C 侧逐字保留，见 {@code CommentFactStreamMapper#bumpVersion}。
 *
 * <h3>⚠️ {@code version} 的 CHECK 上界是 2^53-1（不是性能约束）</h3>
 * <p>DDL 里 {@code CONSTRAINT comment_fact_stream_version_range CHECK (version BETWEEN 1 AND 9007199254740991)}。
 * ⚠️ 语义约束不是性能约束：{@code version} 要进 JSON 信封
 * （{@code delivery_envelope.aggregate_version}）⇒ 上界是 <b>JSON 安全整数上限</b>
 * ⇒ 超出会被下游 JSON 解析器<b>静默丢精度</b>。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class CommentFactStreamEntity {
 *     private String aggregateId;
 *     private long version;
 *
 *     public String getAggregateId() { return aggregateId; }
 *     public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
 *     public long getVersion() { return version; }
 *     public void setVersion(long version) { this.version = version; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("comment_fact_stream")
public class CommentFactStreamEntity {

    /** 对应 B 侧 {@code AggregateID string `column:aggregate_id;primaryKey;type:varchar(128)`}。 */
    @TableId(type = IdType.INPUT)
    private String aggregateId;

    /** 对应 B 侧 {@code Version int64 `not null`}（取值 1 .. 2^53-1）。 */
    private long version;
}
