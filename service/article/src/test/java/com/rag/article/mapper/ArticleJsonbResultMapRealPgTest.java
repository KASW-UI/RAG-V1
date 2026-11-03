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

/**
 * <p><b>真触发</b>：article 域 6 个自定义 {@code @Select} 的 jsonb 列
 * （{@code secondary_tags} / {@code ext_info}）<b>真的被映射进实体</b>。
 *
 * <h2>为什么这个判据必须是「真触发」（且为什么它曾是最严重的缺陷）</h2>
 * <p>{@code secondary_tags}（{@code List<String>}）与 {@code ext_info}
 * （{@code Map<String,String>}）在 MyBatis 的 TypeHandler <b>全局注册表里没有条目</b>。
 * 自定义 {@code @Select} 不带 {@code @ResultMap} 时走<b>注解自动映射</b>，
 * 它按「Java 类型 + JDBC 类型」去那张表里查 —— <b>查不到就静默跳过该属性</b>：
 * <b>不抛异常、不打警告、SQL 全部成功</b>，实体字段恒为 {@code null}。
 * <p>⇒ <b>业务后果不是「读不到」，是「静默早退」</b>：{@code ext_info} 整块为 null ⇒
 * {@code acceptSyncResult} 的 eventId / versionMs 判据<b>永远不成立</b>
 * ⇒ 发布流程每次都在这一步 return，文章永远停在 REVIEWING。
 * <p>⇒ 且 {@code @TableName(autoResultMap = true)} <b>救不了这里</b>：
 * 那张自动结果映射只挂在 MyBatis-Plus <b>自己生成</b>的方法上；
 * 一旦用 {@code @Select} <b>覆盖</b>同名方法，走的就是注解自动映射那条路。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleJsonbResultMapWiringTest.java:181-266}（9 例可移植）；</li>
 *   <li>B 侧 {@code service/article/article-rpc/.../mapper/ArticleMapper.java:70-88}
 *       （{@code articleEntityResultMap} 的声明处与「为什么不能只靠 autoResultMap」的说明）；</li>
 *   <li>B 侧 {@code .../mapper/JsonbTypeHandler.java:70-123}（两个 TypeHandler 的形状）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <p><b>删掉任意一个 {@code @ResultMap} ⇒ 对应用例立刻红</b>：
 * {@code secondaryTags} / {@code extInfo} 会变回 {@code null}。
 * <p>⚠️ 本类刻意用<b>非空</b> jsonb 值做种子：null / 空数组 / 空对象在
 * 「没映射上」与「真的读到空」两种情况下读起来<b>一模一样</b>
 * ⇒ 只有非空值才能把「静默 null」与「真的读到了」区分开。
 *
 * <h2>数据隔离</h2>
 * <p>本类专属作者号 {@code 903000001}；行 id 前缀 {@code 903001}。
 */
class ArticleJsonbResultMapRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "903000001";
    private static final String ARTICLE_ID = "903001001";
    private static final String REVISION_ID = "903001001:r1";

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ArticleRevisionMapper revisionMapper;

    @Autowired
    private ArticlePublicMapper publicMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");

        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'title', 'brief', 'content.md', '', 'tag',
                        '["alpha","beta"]'::jsonb, ?, 3, 0, 0, 0, 0,
                        '{"reco_sync_state":"pending","last_sync_event_id":"evt-903"}'::jsonb,
                        now(), now())
                """, ARTICLE_ID, AUTHOR);

        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 1, ?, ?, 'source-object', repeat('a', 64),
                        'rev-title', 'rev-brief', '', 'rev-tag',
                        '["gamma","delta"]'::jsonb, '# md', now(), 'sync-903-1')
                """, ARTICLE_ID, REVISION_ID, AUTHOR);

        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES (?, ?, 1, 'published', 'evt-903')
                """, ARTICLE_ID, REVISION_ID);
    }

    // ==================================================================
    // ① ArticleMapper 的三个读方法
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：selectById / selectByIdUnscoped / selectByIdForUpdate 的 jsonb **真读出非 null**")
    void articleMapperReadsJsonb() {
        ArticleEntity byId = articleMapper.selectById(ARTICLE_ID);
        assertNotNull(byId, "前置校验：文章应能查到");
        assertEquals(List.of("alpha", "beta"), byId.getSecondaryTags(),
                "🔴 secondary_tags 必须真的读出来；null ⇒ @ResultMap 丢了 ⇒ 业务静默早退");
        assertNotNull(byId.getExtInfo(), "ext_info 同理");
        assertEquals("pending", byId.getExtInfo().get("reco_sync_state"));
        assertEquals("evt-903", byId.getExtInfo().get("last_sync_event_id"));

        ArticleEntity unscoped = articleMapper.selectByIdUnscoped(ARTICLE_ID);
        assertNotNull(unscoped, "Unscoped 必须能查到（同步结果消费者的唯一入口）");
        assertEquals(List.of("alpha", "beta"), unscoped.getSecondaryTags());
        assertNotNull(unscoped.getExtInfo(), "Unscoped 的 ext_info 同理");

        ArticleEntity forUpdate = articleMapper.selectByIdForUpdate(ARTICLE_ID);
        assertNotNull(forUpdate, "FOR UPDATE 必须能查到（审核消费者与发布回调的入口）");
        assertEquals(List.of("alpha", "beta"), forUpdate.getSecondaryTags());
        assertNotNull(forUpdate.getExtInfo(), "FOR UPDATE 的 ext_info 同理");
        assertEquals("pending", forUpdate.getExtInfo().get("reco_sync_state"));
    }

    @Test
    @DisplayName("🔴 真触发：selectAuthorView 的 jsonb 真读出非 null（曾经是「静默早退」的源头）")
    void selectAuthorViewReadsJsonb() {
        List<ArticleEntity> rows = articleMapper.selectAuthorView(
                "tag", null, AUTHOR, "", false, 10, 0);

        assertEquals(1, rows.size(), "前置校验：种子数据应命中一行");
        ArticleEntity row = rows.get(0);
        assertEquals(ARTICLE_ID, row.getId());
        // 🔴 这两条在修复前拿到的是 null
        assertEquals(List.of("alpha", "beta"), row.getSecondaryTags(),
                "selectAuthorView 丢了 @ResultMap ⇒ secondaryTags 恒为 null");
        assertEquals("evt-903", row.getExtInfo().get("last_sync_event_id"),
                "ext_info 恒为 null ⇒ acceptSyncResult 的 eventId 判据永远不成立");
    }

    // ==================================================================
    // ② ArticleRevisionMapper 的两个读方法 + 跨 Mapper 引用
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：selectByRevisionId / selectByArticleId 的 jsonb 真读出非 null")
    void revisionMapperReadsJsonb() {
        ArticleRevisionEntity single = revisionMapper.selectByRevisionId(ARTICLE_ID, REVISION_ID);
        assertNotNull(single, "前置校验：revision 应能查到");
        assertEquals(List.of("gamma", "delta"), single.getSecondaryTags(),
                "selectByRevisionId 是 articleRevisionEntityResultMap 的**声明处**，"
                        + "丢了 @Results 则本方法与所有引用它的方法一起失效");

        List<ArticleRevisionEntity> batch = revisionMapper.selectByArticleId(ARTICLE_ID);
        assertEquals(1, batch.size());
        assertEquals(List.of("gamma", "delta"), batch.get(0).getSecondaryTags(),
                "批量路径与单条路径必须同样能读出 jsonb");
    }

    @Test
    @DisplayName("🔴 真触发：selectRevisionsForPublished（**跨 Mapper 引用** resultMap）的 jsonb 真读出非 null")
    void crossMapperResultMapReferenceWorks() {
        List<ArticleRevisionEntity> rows = publicMapper.selectRevisionsForPublished(List.of(ARTICLE_ID));

        assertEquals(1, rows.size());
        // 🔴 修复前为 null：这条路径喂给公开列表的二级标签会整列空掉
        assertEquals(List.of("gamma", "delta"), rows.get(0).getSecondaryTags(),
                "跨 Mapper 引用 FQN 形式若拼错 ⇒ MyBatis 启动即失败；若漏掉 ⇒ 静默 null");
    }

    // ==================================================================
    // ③ 公开读路径的两个方法
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：selectPublicById / selectPublicPage 的 jsonb 真读出非 null")
    void publicReadsJsonb() {
        ArticleEntity byId = publicMapper.selectPublicById(ARTICLE_ID);
        assertNotNull(byId, "published 行必须可见");
        assertEquals(List.of("alpha", "beta"), byId.getSecondaryTags());
        assertNotNull(byId.getExtInfo(), "公开读的 ext_info 同理");

        List<ArticleEntity> page = publicMapper.selectPublicPage(null, null, AUTHOR, "", false, 10, 0);
        assertEquals(1, page.size(), "公开页应命中一行");
        assertEquals(List.of("alpha", "beta"), page.get(0).getSecondaryTags(),
                "公开页的 jsonb 同样不得为 null");
        assertNotNull(page.get(0).getExtInfo(), "公开页的 ext_info 同理");
    }
}
