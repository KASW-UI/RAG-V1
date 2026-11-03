package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticleDomainOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p><b>职责</b>：H03 域事件发件箱的数据访问 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/ArticleDomainOutboxMapper.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :13-14} · {@code insert} {@code :15-26} ·
 * {@code selectPending} {@code :27-29} · {@code selectAll} {@code :30-31}）
 * ⇒ <b>整段照抄</b>（SQL 文本与 {@code @Param} 名逐字一致）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.rpc.mapper} →
 *       {@code com.rag.article.mapper}，D7 包压平）＋ <b>本段 javadoc</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 文本、参数名<b>逐字未改</b>。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p>⚠️ <b>{@code UNIQUE(aggregate_id, aggregate_version)} 是事件顺序保证，不可省</b>：
 * 本表的 {@code insert} 在重复写入时会被 PG 拒绝 ⇒ 幂等的数据库级兜底。
 */
@Mapper
public interface ArticleDomainOutboxMapper {

    /**
     * 插入一条待投递的 community 事件行。
     *
     * <p>⚠️ {@code payload} 走 {@code CAST(#{payload} AS jsonb)} ——
     * 写入的是<b>冻结的 JSON 文本</b>，推送时逐字节发原文（不重新序列化）。
     */
    @Insert("""
            INSERT INTO article_domain_outbox
                (event_id, aggregate_id, aggregate_version, event_type, payload, status, retry_count,
                 created_at, updated_at)
            VALUES (#{eventId}, #{aggregateId}, #{aggregateVersion}, #{eventType},
                    CAST(#{payload} AS jsonb), 0, 0, now(), now())
            """)
    int insert(@Param("eventId") String eventId,
               @Param("aggregateId") String aggregateId,
               @Param("aggregateVersion") long aggregateVersion,
               @Param("eventType") String eventType,
               @Param("payload") String payload);

    /**
     * 取待投递的行（{@code status IN (0, 2)}），按 {@code created_at, event_id} 排序。
     *
     * <p>⚠️ 排序键含 {@code event_id} 作为 tie-breaker ⇒ 同刻入队的多行也有稳定顺序
     * （与 DDL 的部分索引 {@code article_domain_outbox_pending} 一致）。
     */
    @Select("SELECT * FROM article_domain_outbox WHERE status IN (0, 2) "
            + "ORDER BY created_at, event_id LIMIT #{limit}")
    List<ArticleDomainOutboxEntity> selectPending(@Param("limit") int limit);

    /**
     * 取全部行（按 {@code aggregate_version, event_id} 排序）—— 供自检/回放使用。
     */
    @Select("SELECT * FROM article_domain_outbox ORDER BY aggregate_version, event_id")
    List<ArticleDomainOutboxEntity> selectAll();
}
