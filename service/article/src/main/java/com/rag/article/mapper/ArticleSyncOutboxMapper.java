package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticleSyncOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p><b>职责</b>：文章同步事件发件箱的数据访问 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/ArticleSyncOutboxMapper.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :14-15} · {@code insert} {@code :16-25} ·
 * {@code fetchPending} {@code :26-33} · {@code markSent} {@code :34-36} ·
 * {@code markFailed} {@code :37-39} · {@code selectByEventId} {@code :40-42}）
 * ⇒ <b>整段照抄</b>（SQL 文本与 {@code @Param} 名逐字一致）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.rpc.mapper} →
 *       {@code com.rag.article.mapper}，D7 包压平）＋ <b>本段 javadoc</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 文本、参数名<b>逐字未改</b>
 *       ⇒ relay 的捞取条件与状态流转完全一致。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p>⚠️ <b>{@code event_key} 唯一索引是幂等保证</b>：
 * {@code uk_article_sync_outbox_event_key} ⇒ 同一逻辑事件（{@code article:op:eventId}）不会重复入队。
 */
@Mapper
public interface ArticleSyncOutboxMapper {

    /**
     * 插入一条待投递的同步事件行。
     *
     * <p>⚠️ {@code payload} 走 {@code CAST(#{payload} AS jsonb)} ——
     * 写入的是<b>冻结的 JSON 文本</b>（{@code ArticleSyncEvent} 的序列化结果），
     * 推送时<b>逐字节发原文</b>（不重新序列化）⇒ 已冻结的历史 payload 永远解得出来。
     */
    @Insert("""
            INSERT INTO article_sync_outbox
                (event_id, event_key, event_type, aggregate_id, payload, status, retry_count, created_at, updated_at)
            VALUES (#{eventId}, #{eventKey}, #{eventType}, #{aggregateId}, CAST(#{payload} AS jsonb),
                    0, 0, now(), now())
            """)
    int insert(@Param("eventId") String eventId,
               @Param("eventKey") String eventKey,
               @Param("eventType") String eventType,
               @Param("aggregateId") String aggregateId,
               @Param("payload") String payload);

    /**
     * relay 的捞取条件 —— 对应 B 侧的 {@code FetchPending}。
     *
     * <p>⚠️ 条件是 {@code status IN (0, 2) AND retry_count < maxRetry}（默认 maxRetry = 10）：
     * {@code 0 待发送 / 2 发送失败} 都要重试；超过上限就不再捞（避免无限重试）。
     * <br>⚠️ {@code maxRetry} 的过滤是<b>有条件的</b>（{@code maxRetry > 0} 才生效）——
     * 与 B 侧一致，不要改成无条件过滤。
     */
    @Select("<script>"
            + "SELECT * FROM article_sync_outbox\n"
            + "WHERE status IN (0, 2)\n"
            + "<if test=\"maxRetry != null and maxRetry > 0\"> AND retry_count &lt; #{maxRetry}</if>\n"
            + "ORDER BY created_at ASC\n"
            + "LIMIT #{limit}\n"
            + "</script>")
    List<ArticleSyncOutboxEntity> fetchPending(@Param("limit") int limit, @Param("maxRetry") int maxRetry);

    /**
     * 标记为已发送（{@code status = 1}）并清空 {@code last_error}。
     */
    @Update("UPDATE article_sync_outbox SET status = 1, last_error = '', updated_at = now() "
            + "WHERE event_id = #{eventId}")
    int markSent(@Param("eventId") String eventId);

    /**
     * 标记为发送失败（{@code status = 2}）并 {@code retry_count + 1}。
     */
    @Update("UPDATE article_sync_outbox SET status = 2, retry_count = retry_count + 1, "
            + "last_error = #{lastError}, updated_at = now() WHERE event_id = #{eventId}")
    int markFailed(@Param("eventId") String eventId, @Param("lastError") String lastError);

    /**
     * 按事件 ID 取一行（幂等判据用）。
     */
    @Select("SELECT * FROM article_sync_outbox WHERE event_id = #{eventId}")
    ArticleSyncOutboxEntity selectByEventId(@Param("eventId") String eventId);
}
