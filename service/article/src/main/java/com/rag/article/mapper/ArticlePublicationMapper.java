package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticlePublicationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p><b>职责</b>：source 域指针表的数据访问 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/ArticlePublicationMapper.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :14-15} · {@code selectByArticleId} {@code :16-17} ·
 * {@code selectForUpdate} {@code :18-19} · {@code markRetracted} {@code :20-24} ·
 * {@code upsert} {@code :25-37}）⇒ <b>整段照抄</b>（SQL 文本与 {@code @Param} 名逐字一致）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.rpc.mapper} →
 *       {@code com.rag.article.mapper}，D7 包压平）＋ <b>本段 javadoc</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 文本、参数名<b>逐字未改</b>
 *       ⇒ 指针推进与撤回的可观察语义完全一致。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 */
@Mapper
public interface ArticlePublicationMapper {

    /**
     * 取指针行（不加锁）。
     *
     * <p>⚠️ 返回 {@code null} 有<b>三种含义</b>，必须由上层区分（见
     * {@code ArticlePublicDomainService} 的三分支）：<b>无指针行</b>（legacy，看 article.status 决定）、
     * <b>指针非 published / 空版本</b>（NotFound）、<b>正常</b>（取 revision）。
     */
    @Select("SELECT * FROM article_publication WHERE article_id = #{articleId}")
    ArticlePublicationEntity selectByArticleId(@Param("articleId") String articleId);

    /**
     * 取指针行并<b>加行级悲观锁</b>（{@code FOR UPDATE}）。
     *
     * <p>⚠️ <b>必须加锁</b>：指针推进（{@code pointer_version + 1}）与撤回
     * 都要与「读当前指针 → 判断 → 写新指针」串行化，否则两个并发事务会算出同一个
     * {@code pointer_version} ⇒ 事件顺序保证（H03）失效。
     */
    @Select("SELECT * FROM article_publication WHERE article_id = #{articleId} FOR UPDATE")
    ArticlePublicationEntity selectForUpdate(@Param("articleId") String articleId);

    /**
     * 标记为已撤回（{@code state = 'retracted'}）并推进指针版本。
     *
     * <p>⚠️ <b>不改 {@code current_revision}</b>：撤回后指针仍指向旧 revision，
     * 但那篇文章已不可见（公开读的谓词要求 {@code state = 'published'}）。
     */
    @Update("UPDATE article_publication SET state = 'retracted', pointer_version = #{pointerVersion}, "
            + "last_event_id = #{lastEventId}, updated_at = now() WHERE article_id = #{articleId}")
    int markRetracted(@Param("articleId") String articleId,
                      @Param("pointerVersion") long pointerVersion,
                      @Param("lastEventId") String lastEventId);

    /**
     * 插入或推进指针（{@code ON CONFLICT DO UPDATE}）。
     *
     * <p>⚠️ <b>必须用 upsert 而非 insert-or-update 两步</b>：两步之间存在窗口，
     * 并发下会撞 {@code UNIQUE(article_id)} 主键。
     */
    @Insert("""
            INSERT INTO article_publication
                (article_id, current_revision, pointer_version, state, last_event_id, updated_at)
            VALUES (#{articleId}, #{currentRevision}, #{pointerVersion}, #{state}, #{lastEventId}, now())
            ON CONFLICT (article_id) DO UPDATE SET
                current_revision = EXCLUDED.current_revision,
                pointer_version  = EXCLUDED.pointer_version,
                state            = EXCLUDED.state,
                last_event_id    = EXCLUDED.last_event_id,
                updated_at       = now()
            """)
    int upsert(@Param("articleId") String articleId,
               @Param("currentRevision") String currentRevision,
               @Param("pointerVersion") long pointerVersion,
               @Param("state") String state,
               @Param("lastEventId") String lastEventId);
}
