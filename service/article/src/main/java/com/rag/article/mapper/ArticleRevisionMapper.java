package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticlePublicationEntity;
import com.rag.article.domain.entity.ArticleRevisionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>职责</b>：不可变 revision 表的数据访问 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/ArticleRevisionMapper.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :16-17} · {@code selectByRevisionId} {@code :19-25} ·
 * {@code selectMaxRevision} {@code :27-28} · {@code insert} {@code :30-49} ·
 * {@code selectByArticleId} {@code :51-53}）⇒ <b>整段照抄</b>（SQL 文本与 {@code @Param} 名逐字一致）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.rpc.mapper} →
 *       {@code com.rag.article.mapper}，D7 包压平）＋ <b>本段 javadoc</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 文本、参数名、结果映射<b>逐字未改</b>。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 本 Mapper 只暴露 insert 与 select</b>：本表的不可变性由 PG 触发器
 * {@code article_revision_immutable}（BEFORE UPDATE OR DELETE）强制，
 * 不提供 update 路径是<b>与 B 侧一致</b>的写法，不是遗漏。
 */
@Mapper
public interface ArticleRevisionMapper {

    /**
     * 按业务唯一标识 {@code revision_id} 取一行（复合主键之外的查找路径）。
     *
     * <p>⚠️ 与 {@link #selectByArticleId} 的区别：后者按 {@code article_id} 取<b>全部历史版本</b>
     * （游标分页用复合主键），本方法只取<b>指定的那一版</b>。
     */
    @Select("SELECT * FROM article_revision WHERE article_id = #{articleId} AND revision_id = #{revisionId}")
    @Results(id = "articleRevisionEntityResultMap", value = {
            @Result(column = "secondary_tags", property = "secondaryTags",
                    typeHandler = JsonbTypeHandler.StringListTypeHandler.class)
    })
    ArticleRevisionEntity selectByRevisionId(@Param("articleId") String articleId,
                                             @Param("revisionId") String revisionId);

    /**
     * 取该文章当前的最大 revision 号 —— 新版本号 = {@code COALESCE(MAX(revision),0) + 1}
     * （B 侧 {@code article_sync_result_consumer.go:108-111}）。
     *
     * <p>⚠️ <b>必须在行锁内调用</b>：否则两个并发回调会算出相同的 {@code max+1} 并撞主键。
     */
    @Select("SELECT COALESCE(MAX(revision), 0) FROM article_revision WHERE article_id = #{articleId}")
    long selectMaxRevision(@Param("articleId") String articleId);

    /**
     * 插入一条不可变 revision。
     *
     * <p>⚠️ {@code secondary_tags} 走 {@code CAST(#{secondaryTagsJson} AS jsonb)} ——
     * 与 B 侧一致（写入的是 JSON 文本，由 PG 转成 jsonb 列）。
     */
    @Insert("""
            INSERT INTO article_revision
                (article_id, revision, revision_id, author_id, source_object, content_sha256,
                 title, brief, cover_image_url, manual_type_tag, secondary_tags,
                 markdown, published_at, sync_event_id)
            VALUES (#{articleId}, #{revision}, #{revisionId}, #{authorId}, #{sourceObject}, #{contentSha256},
                    #{title}, #{brief}, #{coverImageUrl}, #{manualTypeTag},
                    CAST(#{secondaryTagsJson} AS jsonb),
                    #{markdown}, #{publishedAt}, #{syncEventId})
            """)
    int insert(@Param("articleId") String articleId,
               @Param("revision") long revision,
               @Param("revisionId") String revisionId,
               @Param("authorId") String authorId,
               @Param("sourceObject") String sourceObject,
               @Param("contentSha256") String contentSha256,
               @Param("title") String title,
               @Param("brief") String brief,
               @Param("coverImageUrl") String coverImageUrl,
               @Param("manualTypeTag") String manualTypeTag,
               @Param("secondaryTagsJson") String secondaryTagsJson,
               @Param("markdown") String markdown,
               @Param("publishedAt") OffsetDateTime publishedAt,
               @Param("syncEventId") String syncEventId);

    /**
     * 取该文章的全部历史版本（按 revision 升序）—— 游标分页用。
     */
    @Select("SELECT * FROM article_revision WHERE article_id = #{articleId} ORDER BY revision")
    @ResultMap("articleRevisionEntityResultMap")
    List<ArticleRevisionEntity> selectByArticleId(@Param("articleId") String articleId);
}
