package com.rag.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.rag.comment.domain.entity.CommentDomainFactOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>评论域事实 outbox 数据访问</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/CommentDomainFactOutboxMapper.java:19-225}
 * （B 侧对应 Go {@code service/comment/rpc/internal/model/fact_delivery.go:53-127} 的
 * {@code DispatchFactOnce} 与 {@code fact_authority.go} 的 {@code AuthoritativeFact}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.mapper.CommentDomainFactOutboxMapper}
 * ⇒ C 侧 {@code com.rag.comment.mapper.CommentDomainFactOutboxMapper}。
 *
 * <p><b>这张表的写入只在业务事务内（{@code CommentDomainFactService#append}），
 * 而这里的 UPDATE 只发生在投递链路（{@code CommentFactDeliveryService}）。
 * 两个写者的字段集合严格不交叠</b> —— 前者只写
 * event_id / event_type / payload / aggregate_id / fact_version / delivery_envelope，
 * 后者只写 delivery_status / retry_count / technical_* / delivered_at。
 * DDL 里的 {@code comment_fact_identity_immutable} 触发器就是把这个不交叠<b>强制</b>掉：
 * 一旦有人跨界改写，PG 直接 {@code RAISE EXCEPTION 'comment fact envelope is immutable'}。
 */
@Mapper
public interface CommentDomainFactOutboxMapper {

    /** 对应 Go {@code communityfact.DeliveryPending}（wire.go:29）。 */
    int DELIVERY_PENDING = 0;
    /** 对应 Go {@code communityfact.DeliveryAccepted}（wire.go:30）。 */
    int DELIVERY_ACCEPTED = 1;
    /** 对应 Go {@code communityfact.DeliveryFailed}（wire.go:31）。 */
    int DELIVERY_FAILED = 2;
    /** 对应 Go {@code communityfact.DeliveryBlocked}（wire.go:32）。 */
    int DELIVERY_BLOCKED = 3;

    /** 对应 Go 的事件类型常量（comment.go:145 / :329 / :588）。 */
    String EVENT_TYPE_CREATED = "community.comment.created";
    String EVENT_TYPE_DELETED = "community.comment.deleted";
    String EVENT_TYPE_INTERACTION = "community.comment.interaction";

    /**
     * 对应 Go {@code appendCommentFact:137-141} 的 outbox 追加。
     *
     * <p>⚠️ 两个 jsonb 列必须<b>显式</b>带 typeHandler：MyBatis-Plus 自动生成的 insert
     * 不保证带上 {@code @TableField(typeHandler=…)}，靠默认路径会把 jsonb 当 VARCHAR 绑定。
     * 这里手写 SQL 把这件事钉死。
     * ⚠️ <b>D3 落点变化</b>：B 侧是域内副本
     * {@code com.rag.comment.comment.rpc.mapper.JsonbTypeHandler}，
     * C 侧收进共享层 ⇒ {@code com.rag.common.persistence.JsonbTypeHandler}。
     *
     * <p>⚠️ Go 的 {@code CreatedAt} 用 struct 的 {@code autoCreateTime} 赋值（comment.go:29
     * 是 tag，domain_fact.go:29 是 DDL 默认），此处同样交给 DDL 的
     * {@code DEFAULT CURRENT_TIMESTAMP}。
     */
    @Insert("""
            INSERT INTO comment_domain_fact_outbox
                (event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope)
            VALUES (#{eventId}, #{eventType},
                    #{payload, typeHandler=com.rag.common.persistence.JsonbTypeHandler,
                            jdbcType=OTHER},
                    #{aggregateId}, #{factVersion},
                    #{deliveryEnvelope, typeHandler=com.rag.common.persistence.JsonbTypeHandler,
                            jdbcType=OTHER})
            """)
    int appendFact(@Param("eventId") String eventId,
                   @Param("eventType") String eventType,
                   @Param("payload") String payload,
                   @Param("aggregateId") String aggregateId,
                   @Param("factVersion") long factVersion,
                   @Param("deliveryEnvelope") String deliveryEnvelope);

    /**
     * 对应 Go {@code DispatchFactOnce:64-70} 的取件查询，逐字照抄：
     * <pre>
     * tx.Clauses(clause.Locking{Strength: "UPDATE", Options: "SKIP LOCKED"}).
     *    Where("delivery_status IN ? AND delivery_envelope IS NOT NULL", {Pending, Failed}).
     *    Where(`NOT EXISTS (SELECT 1 FROM comment_domain_fact_outbox predecessor
     *             WHERE predecessor.aggregate_id=comment_domain_fact_outbox.aggregate_id
     *             AND predecessor.fact_version &lt; comment_domain_fact_outbox.fact_version
     *             AND predecessor.delivery_status &lt;&gt; ?)`, Accepted).
     *    Order("created_at,event_id").Take(&amp;row)
     * </pre>
     *
     * <p><b>三处语义都在 SQL 里，不能靠应用层补</b>：
     * <ol>
     *   <li><b>{@code SKIP LOCKED}</b> ⇒ 多个投递进程并行时互不阻塞，各自领走不同的行。
     *       <b>但</b>方法注释明说「Earlier versions of the same comment stream must be accepted
     *       first, so SKIP LOCKED cannot reorder a retract」⇒ 必须有下一条的 NOT EXISTS 屏障。</li>
     *   <li><b>前序屏障（NOT EXISTS … fact_version &lt; … AND delivery_status &lt;&gt; Accepted）</b>
     *       ⇒ 同一 aggregate 的 v1 还没被接受时，v2 不可被领取。
     *       删掉它 ⇒ 「删除」事实可能先于「创建」事实到达 DC，
     *       而 DC 侧看到的是一条<b>看似完整却顺序颠倒</b>的事实流。</li>
     *   <li><b>{@code ORDER BY created_at, event_id}</b> ⇒ 同批内按创建序，且用 event_id 消歧，
     *       保证多进程下的领取顺序<b>确定</b>。</li>
     * </ol>
     *
     * <p><b>必须在事务内调用</b>：{@code SELECT … FOR UPDATE} 的锁一直持有到该事务提交，
     * 而 Go 侧刻意在<b>持锁期间</b>做网络投递（fact_delivery.go:93），
     * 以保证「一条源行在拿到匹配的 DC 技术回执之前不会被第二个投递进程领走」。
     */
    @Select("""
            SELECT * FROM comment_domain_fact_outbox
            WHERE delivery_status IN (0, 2)
              AND delivery_envelope IS NOT NULL
              AND NOT EXISTS (
                    SELECT 1 FROM comment_domain_fact_outbox predecessor
                    WHERE predecessor.aggregate_id = comment_domain_fact_outbox.aggregate_id
                      AND predecessor.fact_version < comment_domain_fact_outbox.fact_version
                      AND predecessor.delivery_status <> 1)
            ORDER BY created_at, event_id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    CommentDomainFactOutboxEntity selectNextDispatchableForUpdate();

    /**
     * 对应 Go {@code fact_delivery.go:79-80} 的「冻结信封不可解码 ⇒ 冻结该行」分支：
     * {@code Update("delivery_status", communityfact.DeliveryBlocked)}。
     *
     * <p>⚠️ 它<b>只改 delivery_status</b>，绝不碰信封列 ⇒ 触发器不会拦。
     * 语义是「这条事实需要人工迁移处理，调度器不要再碰它」。
     */
    @Update("UPDATE comment_domain_fact_outbox SET delivery_status = 3 WHERE event_id = #{eventId}")
    int markBlocked(@Param("eventId") String eventId);

    /**
     * 对应 Go {@code fact_delivery.go:99-101} 的投递失败分支：
     * {@code Updates(map{"delivery_status": DeliveryFailed, "retry_count": gorm.Expr("retry_count + 1")})}。
     *
     * <p>⚠️ 状态是<b>失败(2)</b> 而不是阻塞(3) ⇒ 它仍可被下一次取件重新领走（可重试）。
     * 且 {@code retry_count} 在数据库侧自增，与 Go 一致。
     */
    @Update("""
            UPDATE comment_domain_fact_outbox
            SET delivery_status = 2, retry_count = retry_count + 1
            WHERE event_id = #{eventId}
            """)
    int markFailed(@Param("eventId") String eventId);

    /**
     * 对应 Go {@code fact_delivery.go:113-116} 的成功分支：
     * <pre>
     * updates := map[string]any{"delivery_status":     DeliveryAccepted,
     *                          "technical_receipt_id": receipt.ReceiptID,
     *                          "technical_input_hash": receipt.InputHash,
     *                          "technical_offset":     receipt.Offset,
     *                          "technical_received_at": received,
     *                          "delivered_at":          deliveredAt}
     * </pre>
     *
     * <p>⚠️ 六列全部落在「投递元数据」侧，<b>没有一列在不可变清单里</b>
     * ⇒ {@code comment_fact_identity_immutable} 触发器不会拦下这条更新。
     */
    @Update("""
            UPDATE comment_domain_fact_outbox
            SET delivery_status = 1,
                technical_receipt_id = #{receiptId},
                technical_input_hash = #{inputHash},
                technical_offset = #{offset},
                technical_received_at = #{receivedAt},
                delivered_at = #{deliveredAt}
            WHERE event_id = #{eventId}
            """)
    int markAccepted(@Param("eventId") String eventId,
                     @Param("receiptId") String receiptId,
                     @Param("inputHash") String inputHash,
                     @Param("offset") long offset,
                     @Param("receivedAt") OffsetDateTime receivedAt,
                     @Param("deliveredAt") OffsetDateTime deliveredAt);

    /**
     * 对应 Go {@code appendCommentFact:107-110} 的 legacy 守卫：
     * {@code Where("payload->>'aggregate_id' = ? AND delivery_envelope IS NULL", fact.AggregateID).Count(&legacy)}。
     *
     * <p>语义（Go 注释，domain_fact.go:103-105，逐字）：
     * 「A legacy row on this aggregate has no trusted fact-stream version. A migration/reconciliation
     * must resolve it before any newer fact can be emitted; otherwise DC would see an apparently
     * complete but false stream.」
     *
     * <p>⇒ {@code > 0} 时必须拒绝发新事实（不是补号、不是跳过）。
     */
    @Select("""
            SELECT count(*) FROM comment_domain_fact_outbox
            WHERE payload->>'aggregate_id' = #{aggregateId} AND delivery_envelope IS NULL
            """)
    long countLegacyUnversioned(@Param("aggregateId") String aggregateId);

    /**
     * 对应 Go {@code AuthoritativeFact:56}：{@code Where("event_id = ?", eventID).Limit(1).Find(&row)}。
     *
     * <p>零行 ⇒ 权威读返回「不可用」（Go 侧是 {@code ErrAuthorityUnavailable} ⇒ HTTP 404）。
     */
    @Select("SELECT * FROM comment_domain_fact_outbox WHERE event_id = #{eventId}")
    CommentDomainFactOutboxEntity findByEventId(@Param("eventId") String eventId);

    /**
     * 对应 Go {@code fact_authority.go:96-98} 的 retract 事实计数：
     * {@code Where("event_id = ?", "rtw.comment."+commentID+".deleted").Count(&retractCount)}，
     * 判定 {@code retractCount == 1}。
     */
    @Select("SELECT count(*) FROM comment_domain_fact_outbox WHERE event_id = #{eventId}")
    long countByEventId(@Param("eventId") String eventId);

    /**
     * 对应 Go {@code authoritativeCommentInteraction:139-142} 的互动事实流读取：
     * <pre>
     * Where("event_type = ? AND payload->>'subject_ref' = ? AND payload->>'comment_id' = ?", …).
     * Order("fact_version,event_id").Find(&rows)
     * </pre>
     *
     * <p><b>按 {@code fact_version, event_id} 排序是权威重放的前提</b>：
     * 权威读要按这个顺序把整条互动链重放一遍，逐条校验
     * {@code old_state} 是否等于上一条的 {@code new_state}（fact_authority.go:156-163）。
     * 顺序错了整条链的重放结论就错了。
     */
    @Select("""
            SELECT * FROM comment_domain_fact_outbox
            WHERE event_type = #{eventType}
              AND payload->>'subject_ref' = #{subjectRef}
              AND payload->>'comment_id' = #{commentId}
            ORDER BY fact_version, event_id
            """)
    List<CommentDomainFactOutboxEntity> selectInteractionStream(@Param("eventType") String eventType,
                                                                 @Param("subjectRef") String subjectRef,
                                                                 @Param("commentId") String commentId);
}
