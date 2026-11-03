package com.rag.like.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * <p><b>事实流版本号 ORM 实体</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/domain/LikeFactStreamEntity.java:14-38}
 * （表 {@code like_fact_stream}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.domain.LikeFactStreamEntity}
 * ⇒ C 侧 {@code com.rag.like.domain.entity.LikeFactStreamEntity}。
 *
 * <p>只有两列，但它是<b>事实流版本号的唯一权威</b>。每发一条事实前调用
 * {@code mapper/LikeDomainFactOutboxMapper#bumpFactStreamVersion}，
 * 由 PG 用 {@code INSERT .. ON CONFLICT DO UPDATE SET version = version + 1 RETURNING}
 * 原子取号 —— 事务并发下这保证同一状态流的版本号严格递增且无空洞。
 *
 * <p><b>不要用 MyBatis-Plus 的乐观锁或 Wrapper 改写它</b>：那条 SQL 的正确性依赖
 * {@code uk_like_fact_stream} 唯一索引做冲突仲裁，换成 select-then-update 会在并发下丢版本号。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class LikeFactStreamEntity {
 *     private String aggregateId;
 *     private Long version;
 *
 *     public String getAggregateId() { return aggregateId; }
 *     public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
 *     public Long getVersion() { return version; }
 *     public void setVersion(Long version) { this.version = version; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("like_fact_stream")
public class LikeFactStreamEntity {

    /** 对应 B 侧 {@code AggregateID string `column:aggregate_id;primaryKey;type:varchar(128)`}。 */
    @TableId(type = IdType.INPUT)
    private String aggregateId;

    /** 对应 B 侧 {@code Version int64 `column:version;not null`}。 */
    private Long version;
}
