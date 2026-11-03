package com.rag.like.mapper;

import com.rag.like.domain.entity.LikeDomainFactOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>领域事实发件箱 + 事实流版本号数据访问</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mapper/LikeDomainFactOutboxMapper.java:18-143}
 * 的全部 SQL（逐条照抄）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.mapper.LikeDomainFactOutboxMapper}
 * ⇒ C 侧 {@code com.rag.like.mapper.LikeDomainFactOutboxMapper}。
 *
 * <p>对应 B 侧 {@code internal/model/domain_fact.go}（appendLikeFact）
 * 与 {@code internal/model/fact_delivery.go}（DispatchFactOnce）。
 */
@Mapper
public interface LikeDomainFactOutboxMapper {

    /**
     * 对应 B 侧 {@code appendLikeFact} 里查「同一 subject+target 是否存在未版本化的历史事实」：
     *
     * <pre>
     * WHERE payload-&gt;&gt;'subject_ref' = ? AND payload-&gt;&gt;'target_type' = ?
     *   AND payload-&gt;&gt;'target_id' = ? AND delivery_envelope IS NULL
     * </pre>
     *
     * <p>命中 {@code >0} 时 B 侧直接返回
     * {@code "like aggregate %s has unversioned legacy facts"} 错误（要求人工迁移），<b>不继续</b>。
     * 这依赖 payload 是 jsonb —— {@code ->>} 操作符只对 jsonb 可用。
     *
     * @return 未版本化遗留事实的条数（&gt;0 即应拒绝）
     */
    @Select("""
            SELECT count(*) FROM like_domain_fact_outbox
            WHERE payload ->> 'subject_ref' = #{subjectRef}
              AND payload ->> 'target_type' = #{targetType}
              AND payload ->> 'target_id' = #{targetId}
              AND delivery_envelope IS NULL
            """)
    long countUnversionedLegacyFacts(@Param("subjectRef") String subjectRef,
                                     @Param("targetType") String targetType,
                                     @Param("targetId") String targetId);

    /**
     * 对应 B 侧 {@code tx.Create(&LikeDomainFactOutbox{...})}（appendLikeFact 末尾）。
     *
     * <p>两个 jsonb 列显式声明 {@code ::jsonb}，与 DDL 列类型对齐。
     */
    @Insert("""
            INSERT INTO like_domain_fact_outbox
                (event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope,
                 delivery_status, retry_count, created_at)
            VALUES (#{eventId}, #{eventType}, #{payload}::jsonb, #{aggregateId}, #{factVersion},
                    #{deliveryEnvelope}::jsonb, 0, 0, now())
            """)
    int insertFact(@Param("eventId") String eventId,
                   @Param("eventType") String eventType,
                   @Param("payload") String payload,
                   @Param("aggregateId") String aggregateId,
                   @Param("factVersion") long factVersion,
                   @Param("deliveryEnvelope") String deliveryEnvelope);

    /**
     * 对应 B 侧 {@code DispatchFactOnce} 的认领查询：
     *
     * <pre>
     * WHERE delivery_status IN (0,2) AND delivery_envelope IS NOT NULL
     *   AND NOT EXISTS (
     *       SELECT 1 FROM like_domain_fact_outbox predecessor
     *       WHERE predecessor.aggregate_id = like_domain_fact_outbox.aggregate_id
     *         AND predecessor.fact_version &lt; like_domain_fact_outbox.fact_version
     *         AND predecessor.delivery_status &lt;&gt; 1)
     * ORDER BY created_at, event_id
     * FOR UPDATE SKIP LOCKED
     * LIMIT 1
     * </pre>
     *
     * <p>三个要点一个都不能丢：
     * <ul>
     *   <li>{@code NOT EXISTS} 前驱子句 —— 保证同一状态流<b>按版本序</b>投递；</li>
     *   <li>{@code SKIP LOCKED} —— 多实例并发时各取各的，不互相阻塞；</li>
     *   <li>{@code LIMIT 1} —— 一次只取一条（B 侧用 {@code .Take(&row)}）。</li>
     * </ul>
     */
    @Select("""
            SELECT event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope,
                   delivery_status, retry_count, technical_receipt_id, technical_input_hash,
                   technical_offset, technical_received_at, delivered_at, created_at
            FROM like_domain_fact_outbox
            WHERE delivery_status IN (0, 2)
              AND delivery_envelope IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM like_domain_fact_outbox predecessor
                  WHERE predecessor.aggregate_id = like_domain_fact_outbox.aggregate_id
                    AND predecessor.fact_version < like_domain_fact_outbox.fact_version
                    AND predecessor.delivery_status <> 1)
            ORDER BY created_at, event_id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """)
    LikeDomainFactOutboxEntity claimNextDeliverable();

    /**
     * 对应 B 侧冻结信封校验失败时的处理：
     * {@code Update("delivery_status", DeliveryBlocked)} 后 <b>提交事务</b>并返回错误。
     *
     * <p>状态 3 = 阻塞，既不重试也不丢弃，等待人工迁移。
     */
    @Update("UPDATE like_domain_fact_outbox SET delivery_status = 3 WHERE event_id = #{eventId}")
    int markBlocked(@Param("eventId") String eventId);

    /** 对应 B 侧投递失败：{@code delivery_status = 2, retry_count = retry_count + 1}。 */
    @Update("""
            UPDATE like_domain_fact_outbox
            SET delivery_status = 2, retry_count = retry_count + 1
            WHERE event_id = #{eventId}
            """)
    int markDeliveryFailed(@Param("eventId") String eventId);

    /**
     * 对应 B 侧投递成功：
     * {@code delivery_status=1, technical_receipt_id, technical_input_hash, technical_offset, technical_received_at, delivered_at}。
     *
     * <p>🔴 <b>{@code delivered_at} 由 SQL 的 {@code now()} 改为<b>调用方传入</b></b>——
     * B 侧 {@code fact_delivery.go:111} 写的是 {@code deliveredAt := time.Now().UTC()}
     * ⇒ 是<b>应用进程的墙钟</b>；而 SQL 的 {@code now()} 是<b>数据库服务器的墙钟</b>
     * ⇒ 两者在「应用与数据库不同机 / 不同时区 / 时钟漂移」时<b>写的不是同一个时刻</b>。
     * ⇒ 改成传参 ⇒ 与 B 侧逐条同构（同源：调用方取 {@code OffsetDateTime.now(UTC)}）。
     */
    @Update("""
            UPDATE like_domain_fact_outbox
            SET delivery_status = 1,
                technical_receipt_id = #{receiptId},
                technical_input_hash = #{inputHash},
                technical_offset = #{offset},
                technical_received_at = #{receivedAt},
                delivered_at = #{deliveredAt}
            WHERE event_id = #{eventId}
            """)
    int markDelivered(@Param("eventId") String eventId,
                      @Param("receiptId") String receiptId,
                      @Param("inputHash") String inputHash,
                      @Param("offset") long offset,
                      @Param("receivedAt") OffsetDateTime receivedAt,
                      @Param("deliveredAt") OffsetDateTime deliveredAt);

    /**
     * 对应 B 侧 {@code AuthoritativeFact} 的两条查询。
     *
     * <p>① 按 event_id 取单行（B 侧用 {@code .Limit(1).Find(&requested)}，<b>不过滤</b>未投递状态 —— 是否可读由后续校验判定）。
     */
    @Select("""
            SELECT event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope,
                   delivery_status, retry_count, technical_receipt_id, technical_input_hash,
                   technical_offset, technical_received_at, delivered_at, created_at
            FROM like_domain_fact_outbox
            WHERE event_id = #{eventId}
            LIMIT 1
            """)
    LikeDomainFactOutboxEntity selectByEventId(@Param("eventId") String eventId);

    /**
     * ② 取同一状态流的全部事实，<b>按版本升序</b> —— authority 用它重放整条状态机，
     * 校验每一行的 {@code fact_version} 必须等于其行序号（1,2,3…）。
     */
    @Select("""
            SELECT event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope,
                   delivery_status, retry_count, technical_receipt_id, technical_input_hash,
                   technical_offset, technical_received_at, delivered_at, created_at
            FROM like_domain_fact_outbox
            WHERE aggregate_id = #{aggregateId}
            ORDER BY fact_version, event_id
            """)
    List<LikeDomainFactOutboxEntity> selectByAggregateIdOrderByVersion(@Param("aggregateId") String aggregateId);
}
