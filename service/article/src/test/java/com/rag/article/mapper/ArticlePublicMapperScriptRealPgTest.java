package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.domain.entity.ArticleRevisionEntity;
import com.rag.article.support.ArticleRealPgTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：{@code ArticlePublicMapper.countPublic} / {@code selectPublicPage}
 * 的 {@code @Select("<script>…")} <b>真的被 MyBatis 解析并执行</b>，
 * 且<b>可见性谓词的两个分支</b>与<b>标签过滤匹配 revision 而非 article</b>都被守住。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <ol>
 *   <li><b>CDATA 包装是承重的</b>：可见谓词里含 {@code p.current_revision <> ''}，
 *       而 MyBatis 的 {@code XMLLanguageDriver} 会把 {@code @Select("<script>…")}
 *       当 <b>XML 文档</b>解析 ⇒ 裸的 {@code <>} 是标签起始符 ⇒ <b>解析直接失败</b>
 *       （{@code BuilderException: … SAXParseException}）⇒
 *       <b>上下文启动时就炸</b>，不是运行 SQL 时才炸。</li>
 *   <li><b>参数名必须与 {@code @Param} 一致</b>：本仓历史缺陷 ——
 *       {@code @Param("secondaryTagJson")} 配 {@code <if test="secondaryTag …">}
 *       ⇒ {@code BindingException: Parameter 'secondaryTag' not found}
 *       ⇒ 「公开文章列表接口 <b>100% 报错</b>」。</li>
 * </ol>
 * ⇒ 这两条都<b>只在运行期</b>暴露 ⇒ mock 掉 mapper 的测试永远看不见。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>{@code service/article/article-rpc/.../mapper/ArticlePublicMapper.java:45-68}
 *       （{@code PUBLIC_ARTICLE_PREDICATE} 与 {@code PUBLIC_ARTICLE_PREDICATE_CDATA}）；</li>
 *   <li>{@code service/article/rpc/internal/model/article_public.go:20-32}（谓词常量与复用）、
 *       {@code :62-88}（标签过滤）、{@code :132-147}（{@code publicArticleOrder}）；</li>
 *   <li>B 侧测试 {@code ArticleJsonbResultMapWiringTest.java:265-349}
 *       （公开读的两分支 + 标签过滤 + {@code count}/{@code page} 同口径）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code PUBLIC_ARTICLE_PREDICATE_CDATA} 去掉 CDATA 包装
 *       ⇒ 用例 ① <b>立刻红</b>（上下文起不来 / 解析异常）；</li>
 *   <li>把谓词的 legacy 分支 {@code OR (p.article_id IS NULL AND a.status = 2)} 删掉
 *       ⇒ 用例 ② <b>立刻红</b>（legacy 行从公开列表消失）；</li>
 *   <li>把标签过滤的 revision 分支改成读 {@code a.secondary_tags}
 *       ⇒ 用例 ③ <b>立刻红</b>（作者改草稿标签会泄漏给读者）；</li>
 *   <li>让 {@code countPublic} 与 {@code selectPublicPage} 的 where 不一致
 *       ⇒ 用例 ④ <b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类用<b>两个</b>专属作者号（{@code 902000001} / {@code 902000002}）把
 * 「已发布行」与「legacy 行」隔开；所有 count/page 都带作者过滤
 * ⇒ 其它测试类的行永远进不了本类的计数（单例 PG 容器被所有类共用）。
 */
class ArticlePublicMapperScriptRealPgTest extends ArticleRealPgTestBase {

    /** 已发布行的作者号。 */
    private static final String AUTHOR_PUBLISHED = "902000001";
    /** legacy 行的作者号。 */
    private static final String AUTHOR_LEGACY = "902000002";

    /** 已发布（有 publication 指针 + revision）—— 走谓词第一支。 */
    private static final String PUBLISHED_ID = "902001001";
    private static final String PUBLISHED_REVISION_ID = "902001001:r1";

    /** legacy（无 publication 行 + status=2）—— 走谓词第二支。 */
    private static final String LEGACY_ID = "902001002";

    @Autowired
    private ArticlePublicMapper publicMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");

        // ① 已发布行：**article 行上是作者的草稿标签**，revision 上才是**批准时**的标签。
        //    两者刻意不同 ⇒ 用例 ③ 才有判别力。
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'published-title', 'brief', 'content.md', '', 'draft-manual',
                        '["draft-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, '2026-01-01T00:00:00Z'::timestamptz, now())
                """, PUBLISHED_ID, AUTHOR_PUBLISHED);

        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 1, ?, ?, 'source-object', repeat('a', 64),
                        'approved-title', 'approved-brief', '', 'approved-manual',
                        '["approved-tag"]'::jsonb, '# approved', now(), 'sync-902-1')
                """, PUBLISHED_ID, PUBLISHED_REVISION_ID, AUTHOR_PUBLISHED);

        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES (?, ?, 1, 'published', 'evt-902-1')
                """, PUBLISHED_ID, PUBLISHED_REVISION_ID);

        // ② legacy 行：PUBLISHED(2) 且**从无** publication 行 ⇒ 走谓词第二支
        //    （p.article_id IS NULL AND a.status = 2）
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'legacy-title', 'brief', 'content.md', '', 'legacy-manual',
                        '["legacy-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, '2026-02-01T00:00:00Z'::timestamptz, now())
                """, LEGACY_ID, AUTHOR_LEGACY);
    }

    // ==================================================================
    // ① <script> 真能解析（CDATA 包装生效）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：countPublic / selectPublicPage 的 <script> 真能解析（CDATA 包装生效）")
    void publicScriptIsReallyParsed() {
        // ⚠️ 这一句就是落点：谓词里含 `p.current_revision <> ''`，裸 <>/> 在 XML 里会炸。
        //    三个判据全给（最坏情况：每个 <if> 都要拼一段 CDATA 化的 SQL）。
        long total = publicMapper.countPublic("approved-manual", "[\"approved-tag\"]", AUTHOR_PUBLISHED);
        assertEquals(1L, total, "三条件同时给 ⇒ 只命中已发布那一行");

        List<ArticleEntity> rows = publicMapper.selectPublicPage(
                "approved-manual", "[\"approved-tag\"]", AUTHOR_PUBLISHED, "", false, 10, 0);
        assertEquals(1, rows.size(), "page 与 count 同口径");
        assertEquals(PUBLISHED_ID, rows.get(0).getId());

        // ⚠️ null 判据是最常见形态（proto3 空串 → 归一为 null），同样必须能解析
        assertEquals(1L, publicMapper.countPublic(null, null, AUTHOR_PUBLISHED),
                "null 判据下已发布行可见");
        assertEquals(1, publicMapper.selectPublicPage(null, null, AUTHOR_PUBLISHED, "", false, 10, 0).size(),
                "null 判据下已发布行可见（page）");

        // 三个判据可组合（Go 是链式 .Where()，由 ORM 以 AND 组合）
        assertEquals(0L, publicMapper.countPublic("approved-manual", "[\"approved-tag\"]", "999999999"),
                "加上不匹配的 authorId ⇒ 0 行");
        assertEquals(0L, publicMapper.countPublic("no-such-manual", null, AUTHOR_PUBLISHED),
                "不存在的 manualTypeTag ⇒ 0 行");
    }

    // ==================================================================
    // ② 可见性谓词的两分支
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：可见性谓词两分支 —— published+revision 可见；legacy（无指针 + status=2）**仍可见**")
    void visibilityPredicateHasBothBranches() {
        // 第一支：published 指针 + 指向一个真实 revision
        assertEquals(1L, publicMapper.countPublic(null, null, AUTHOR_PUBLISHED),
                "published 行必须可见");
        ArticleEntity published = publicMapper.selectPublicById(PUBLISHED_ID);
        assertNotNull(published, "published 行必须能被 selectPublicById 取到");
        assertEquals(PUBLISHED_ID, published.getId());

        // 第二支：legacy（p.article_id IS NULL AND a.status = 2）
        // ⚠️ 删掉这一支会让**全部历史 PUBLISHED 文章**从公开列表消失 ⇒ 必须守住
        assertEquals(1L, publicMapper.countPublic(null, null, AUTHOR_LEGACY),
                "legacy 行（无 publication 指针 + status=2）必须仍然可见");
        ArticleEntity legacy = publicMapper.selectPublicById(LEGACY_ID);
        assertNotNull(legacy, "legacy 行必须能被 selectPublicById 取到");
        assertEquals(LEGACY_ID, legacy.getId());
        assertEquals(2, legacy.getStatus(), "legacy 行的 status 必须是 PUBLISHED(2)");

        // 反向：草稿（status != 2）既没有指针也不算 legacy ⇒ 不可见
        jdbcTemplate.update("UPDATE article SET status = 3 WHERE id = ?", LEGACY_ID);
        assertEquals(0L, publicMapper.countPublic(null, null, AUTHOR_LEGACY),
                "status=3（审核中）不得被 legacy 分支放出来");
    }

    // ==================================================================
    // ③ 标签过滤匹配 revision 而非 article
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：标签过滤匹配 **revision** 而非 article（作者改了草稿标签**不泄漏**给读者）")
    void tagFilterMatchesRevisionNotArticle() {
        // Go 出处：internal/model/article_public.go:67-85
        //   published 分支 → r.secondary_tags @> ?
        //   legacy 分支   → a.secondary_tags @> ?
        // ⇒ 读者看到的分类是**批准时**的值；作者改草稿标签不该泄漏。

        assertEquals(1L, publicMapper.countPublic(null, "[\"approved-tag\"]", AUTHOR_PUBLISHED),
                "revision 上的标签必须命中已发布那一行");
        assertEquals(0L, publicMapper.countPublic(null, "[\"draft-tag\"]", AUTHOR_PUBLISHED),
                "🔴 article 行上的草稿标签**不得**泄漏给读者（published 分支只匹配 revision）");

        // legacy 行没有 revision ⇒ 标签过滤落在 a.secondary_tags 上
        assertEquals(1L, publicMapper.countPublic(null, "[\"legacy-tag\"]", AUTHOR_LEGACY),
                "legacy 分支的标签过滤落在 article 行上");
        assertEquals(0L, publicMapper.countPublic(null, "[\"approved-tag\"]", AUTHOR_LEGACY),
                "legacy 行不该被 revision 上的标签命中");

        // manualTypeTag 同理
        assertEquals(1L, publicMapper.countPublic("approved-manual", null, AUTHOR_PUBLISHED),
                "revision 的一级标签必须命中");
        assertEquals(0L, publicMapper.countPublic("draft-manual", null, AUTHOR_PUBLISHED),
                "🔴 article 行上的草稿一级标签**不得**泄漏给读者");

        // 作者在发布后改了草稿标签 ⇒ 读者看到的仍是批准时的标签
        jdbcTemplate.update("UPDATE article SET secondary_tags = '[\"leaked-tag\"]'::jsonb "
                + "WHERE id = ?", PUBLISHED_ID);
        assertEquals(0L, publicMapper.countPublic(null, "[\"leaked-tag\"]", AUTHOR_PUBLISHED),
                "作者改草稿标签后，读者按新标签搜**搜不到**");
        assertEquals(1L, publicMapper.countPublic(null, "[\"approved-tag\"]", AUTHOR_PUBLISHED),
                "读者按批准时的标签**仍然搜得到**");
    }

    // ==================================================================
    // ④ count 与 page 的谓词一致
    // ==================================================================

    @Test
    @DisplayName("真触发：countPublic 与 selectPublicPage 的谓词一致（total == 实际行数）")
    void countAndPageShareTheSamePredicate() {
        String[][] filters = {
                {null, null},
                {"approved-manual", null},
                {null, "[\"approved-tag\"]"},
                {"approved-manual", "[\"approved-tag\"]"},
                {"no-such", "[\"no-such\"]"},
        };

        for (String author : new String[]{AUTHOR_PUBLISHED, AUTHOR_LEGACY}) {
            for (String[] filter : filters) {
                long total = publicMapper.countPublic(filter[0], filter[1], author);
                List<ArticleEntity> rows = publicMapper.selectPublicPage(
                        filter[0], filter[1], author, "", false, 50, 0);
                assertEquals(total, rows.size(),
                        "count 与 page 必须同口径（author=" + author + ", manualTypeTag=" + filter[0]
                                + ", secondaryTagJson=" + filter[1] + "）：total=" + total
                                + " 但 page 返回 " + rows.size() + " 行");
            }
        }

        // legacy 侧单独验一遍「标签过滤落在 article 行上」的口径
        long legacyTotal = publicMapper.countPublic(null, "[\"legacy-tag\"]", AUTHOR_LEGACY);
        List<ArticleEntity> legacyRows = publicMapper.selectPublicPage(
                null, "[\"legacy-tag\"]", AUTHOR_LEGACY, "", false, 50, 0);
        assertEquals(legacyTotal, legacyRows.size(), "legacy 侧 count 与 page 也必须同口径");
        assertEquals(1L, legacyTotal, "legacy 侧只命中一行");

        // 分页时 total 是全量、page 是本页
        long total = publicMapper.countPublic(null, null, AUTHOR_PUBLISHED);
        List<ArticleEntity> firstPage = publicMapper.selectPublicPage(
                null, null, AUTHOR_PUBLISHED, "", false, 1, 0);
        assertEquals(1L, total, "本作者下全量 1 行");
        assertEquals(1, firstPage.size(), "limit=1 ⇒ 本页 1 行");
        assertTrue(firstPage.get(0).getId().startsWith("902001"), "本页首行必须来自本类的数据");
    }

    // ==================================================================
    // 附：selectRevisionsForPublished（跨 Mapper 引用 resultMap 的读路径）
    // ==================================================================

    @Test
    @DisplayName("真触发：selectRevisionsForPublished 只取当前指针指向的那一版（join 条件含 current_revision）")
    void revisionsForPublishedOnlyReturnsTheCurrentPointer() {
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 2, ?, ?, 'source-object', repeat('b', 64),
                        'older-title', 'older-brief', '', 'older-manual',
                        '["older-tag"]'::jsonb, '# older', now(), 'sync-902-2')
                """, PUBLISHED_ID, "902001001:r2", AUTHOR_PUBLISHED);

        List<ArticleRevisionEntity> revisions = publicMapper.selectRevisionsForPublished(
                List.of(PUBLISHED_ID));
        assertEquals(1, revisions.size(),
                "⚠️ join 条件含 current_revision = revision_id ⇒ 只取指针指向的那一版；"
                        + "若只按 article_id IN 取，会把该文章**所有历史版本**都拉回来");
        assertEquals(PUBLISHED_REVISION_ID, revisions.get(0).getRevisionId());
    }
}
