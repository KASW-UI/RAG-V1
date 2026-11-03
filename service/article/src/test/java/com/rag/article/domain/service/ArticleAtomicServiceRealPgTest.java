package com.rag.article.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.mapper.ArticlePublicationMapper;
import com.rag.article.mapper.ArticleSyncOutboxMapper;
import com.rag.article.support.ArticleRealPgTestBase;
import com.rag.common.persistence.JsonbValueTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：{@code ArticleAtomicService.acceptSyncResult} 的
 * <b>成功路径 / 事务回滚 / 幂等 / 通知判定</b>，全部在<b>真 PG + 真 AOP 代理</b>上跑。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <ol>
 *   <li><b>事务必须真的回滚</b>：本类<b>直接 {@code new} 服务对象就退化成 auto-commit</b>
 *       ⇒ 异常不会回滚 ⇒ 「回滚」这条判据<b>按定义</b>就测不到。
 *       ⇒ 必须从容器里拿<b>代理</b>（{@code @SpringBootTest} 注入的就是代理）。</li>
 *   <li><b>「域事件写失败 ⇒ 三者全部回滚」需要真约束</b>：用 PG 触发器注入失败
 *       （B 侧同款手法），让真 SQL 报错 ⇒ 真事务回滚。</li>
 *   <li><b>四者同时落库</b>（revision + 指针 + 域事件 + 文章状态）是<b>四条独立写入</b>
 *       ⇒ 必须是同一次提交里的四个可观察副作用。</li>
 * </ol>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleTransactionWiringTest.java:214-525}
 *       （成功路径 / 通知判定 / 冻结标题 vs 当前行标题 / 回滚 / AOP 代理 / 域事件顺序）；</li>
 *   <li>Go 出处 {@code service/article/rpc/internal/mqs/article_sync_result_consumer.go:74-153}
 *       （{@code WithArticleTx} 闭包：判定链 + 五步写入）；</li>
 *   <li>Go 出处 {@code .../internal/mq/community_article_event.go:54-137}
 *       （域事件信封与撤回）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code acceptSyncResult} 上的 {@code @Transactional} 去掉
 *       ⇒ 用例 ② <b>立刻红</b>（revision 残留 ⇒ 没回滚）；</li>
 *   <li>把 {@code publishApprovedRevision} 改成「先写域事件再写 revision」
 *       ⇒ 用例 ① 的顺序类断言可能红；把指针推进删掉 ⇒ ① <b>立刻红</b>；</li>
 *   <li>把通知标题改成取冻结 payload 的 title ⇒ 用例 ⑥ <b>立刻红</b>；</li>
 *   <li>把 {@code shouldNotify} 的判据里的 {@code published_once} 去掉
 *       ⇒ 用例 ⑤ <b>立刻红</b>（已发布过仍会通知）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类专属作者号 {@code 905000001}；行 id 前缀 {@code 905001}。
 */
class ArticleAtomicServiceRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "905000001";

    @Autowired
    private ArticleAtomicService atomicService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ArticlePublicationMapper publicationMapper;

    @Autowired
    private ArticleSyncOutboxMapper syncOutboxMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetDomainTables() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS deny_domain_event ON article_domain_outbox");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS deny_domain_event()");
    }

    // ==================================================================
    // ① 成功路径：revision + 指针 + 域事件 + 文章状态 四者同时落库
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：acceptSyncResult **成功**路径 ⇒ revision + 指针 + 域事件 + 文章状态 四者同时落库")
    void acceptSyncResultSuccessPathPublishesEverything() {
        String articleId = "905001001";
        seedReviewingArticle(articleId, "905001001.md", "sync-905-1", 2000L,
                ArticleSyncEvent.REASON_UPDATE, "row-title");
        insertRevisionReadyOutbox(articleId, "sync-905-1", 2000L, "frozen-title");

        ArticleSyncEvent.ArticleSyncResult result = new ArticleSyncEvent.ArticleSyncResult(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, "sync-905-1", articleId,
                ArticleSyncEvent.OP_UPSERT, 2000L, true, null);

        ArticleAtomicService.PublishOutcome outcome = atomicService.acceptSyncResult(result, mapper);

        // ⚠️ 种子里 last_sync_reason = 'update' ⇒ Go 的通知条件
        //    （syncReason == create && ExtPublishedOnce == ""）**不成立** ⇒ 不通知
        assertNull(outcome, "reason=update ⇒ 不该要求发通知（Go article_sync_result_consumer.go:146-148）");

        // ① revision：内容取自**冻结 payload**，不是当前 article 行
        assertEquals(1, count("SELECT COUNT(*) FROM article_revision WHERE article_id = ?", articleId),
                "成功路径必须落一条 revision");
        assertEquals(1L, longOf("SELECT revision FROM article_revision WHERE article_id = ?", articleId),
                "revision 号 = MAX(revision)+1");
        assertEquals(articleId + ":r1",
                jdbcTemplate.queryForObject(
                        "SELECT revision_id FROM article_revision WHERE article_id = ?", String.class, articleId),
                "revision_id 形如 \"<articleId>:r<n>\"");
        assertEquals("# Approved",
                jdbcTemplate.queryForObject(
                        "SELECT markdown FROM article_revision WHERE article_id = ?", String.class, articleId),
                "正文必须取**冻结 payload** 的 markdown");
        assertEquals("frozen-title",
                jdbcTemplate.queryForObject(
                        "SELECT title FROM article_revision WHERE article_id = ?", String.class, articleId),
                "标题必须取**冻结 payload** 的 title");
        assertEquals("sync-905-1",
                jdbcTemplate.queryForObject(
                        "SELECT sync_event_id FROM article_revision WHERE article_id = ?", String.class, articleId),
                "revision 记下这次同步的 event_id");
        assertNotNull(jdbcTemplate.queryForObject(
                        "SELECT published_at FROM article_revision WHERE article_id = ?",
                        java.time.OffsetDateTime.class, articleId),
                "published_at 必须落库");

        // ② 指针：current_revision 指向刚建的 revision，state=published，pointerVersion=1
        assertEquals(articleId + ":r1",
                jdbcTemplate.queryForObject(
                        "SELECT current_revision FROM article_publication WHERE article_id = ?",
                        String.class, articleId), "指针必须指向刚冻结的 revision");
        assertEquals(1L, longOf("SELECT pointer_version FROM article_publication WHERE article_id = ?", articleId),
                "指针不存在时 pointerVersion 从 0+1=1 起");
        assertEquals("published",
                jdbcTemplate.queryForObject(
                        "SELECT state FROM article_publication WHERE article_id = ?", String.class, articleId),
                "state = published");
        assertEquals(articleId + ":r1", publicationMapper.selectByArticleId(articleId).getCurrentRevision(),
                "⚠️ 走**真正的 publication mapper** 读指针（不另写一份 SQL 口径）");

        // ③ 域事件：aggregate_version 与指针版本一致，event_type = community.article.published
        assertEquals(1, count("SELECT COUNT(*) FROM article_domain_outbox WHERE aggregate_id = ?", articleId),
                "成功路径必须落一条域事件");
        assertEquals(1L, longOf("SELECT aggregate_version FROM article_domain_outbox WHERE aggregate_id = ?", articleId),
                "域事件的 aggregate_version = pointerVersion");
        assertEquals("community.article.published",
                jdbcTemplate.queryForObject(
                        "SELECT event_type FROM article_domain_outbox WHERE aggregate_id = ?",
                        String.class, articleId), "域事件的 event_type = community.article.published");
        assertEquals("community.article.sync-905-1.published",
                jdbcTemplate.queryForObject(
                        "SELECT event_id FROM article_domain_outbox WHERE aggregate_id = ?",
                        String.class, articleId), "域事件 id = community.article.<eventId>.<operation>");
        assertEquals(jdbcTemplate.queryForObject(
                        "SELECT last_event_id FROM article_publication WHERE article_id = ?",
                        String.class, articleId),
                jdbcTemplate.queryForObject(
                        "SELECT event_id FROM article_domain_outbox WHERE aggregate_id = ?",
                        String.class, articleId),
                "指针的 last_event_id 必须指向这条域事件");

        // ④ 文章本身：PUBLISHED + ext_info 状态机
        ArticleEntity after = articleMapper.selectById(articleId);
        assertNotNull(after, "文章行必须还在");
        assertEquals(2, after.getStatus(), "状态必须变成 PUBLISHED(2)");
        assertNotNull(after.getExtInfo(), "ext_info 回读为 null ⇒ jsonb 映射没生效");
        assertEquals("published", after.getExtInfo().get(ArticleSyncEvent.EXT_PUBLISH_STAGE));
        assertEquals("succeeded", after.getExtInfo().get(ArticleSyncEvent.EXT_RECO_SYNC_STATE));
        assertEquals("true", after.getExtInfo().get(ArticleSyncEvent.EXT_PUBLISHED_ONCE));
        assertEquals("sync-905-1", after.getExtInfo().get(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID));
        assertEquals("2000", after.getExtInfo().get(ArticleSyncEvent.EXT_LAST_SYNC_VERSION),
                "last_sync_version_ms 推进（存成**字符串**）");
    }

    // ==================================================================
    // ② 域事件写失败 ⇒ 三者全部回滚
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：域事件写失败 ⇒ revision/指针/状态三者全部回滚（真实回滚，非静默）")
    void domainOutboxFailureRollsBackEverything() {
        String articleId = "905001002";
        seedReviewingArticle(articleId, "905001002.md", "sync-905-2", 1000L,
                ArticleSyncEvent.REASON_UPDATE, "row-title");
        insertRevisionReadyOutbox(articleId, "sync-905-2", 1000L, "frozen-title");

        // ⚠️ 前置断言：acceptSyncResult 里有**五处静默 no-op**，任一命中就 return null，
        //    表现为「什么都没发生」而不是报错 ⇒ 不钉死前置条件就无法区分
        //    「回滚没生效」与「压根没进发布分支」。
        ArticleEntity before = articleMapper.selectById(articleId);
        assertNotNull(before, "seed 失败");
        assertEquals(3, before.getStatus(), "种子文章不是 REVIEWING(3) ⇒ 会走 ④ no-op");
        assertNotNull(before.getExtInfo(), "ext_info 回读为 null ⇒ typeHandler 没生效");
        assertEquals("sync-905-2", before.getExtInfo().get(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID),
                "last_sync_event_id 回读不到 ⇒ 会走 ② no-op");
        assertEquals("1000", before.getExtInfo().get(ArticleSyncEvent.EXT_LAST_SYNC_VERSION),
                "last_sync_version_ms 回读不到 ⇒ 会走 ② no-op");
        assertNotNull(syncOutboxMapper.selectByEventId("sync-905-2"),
                "冻结 payload 行不存在 ⇒ 会走 ⑤ 抛错分支而非回滚分支");

        // 用触发器注入域事件写入失败（B 侧 community_article_test.go:254-259 同款手法）
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION deny_domain_event() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected domain outbox failure'; END $$;
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER deny_domain_event BEFORE INSERT ON article_domain_outbox
                FOR EACH ROW EXECUTE FUNCTION deny_domain_event()
                """);
        try {
            ArticleSyncEvent.ArticleSyncResult result = new ArticleSyncEvent.ArticleSyncResult(
                    ArticleSyncEvent.ARTICLE_SYNC_SCOPE, "sync-905-2", articleId,
                    ArticleSyncEvent.OP_UPSERT, 1000L, true, null);

            assertThrows(Exception.class, () -> atomicService.acceptSyncResult(result, mapper),
                    "域事件写失败却被当成发布成功");

            // 三者都必须为空/未变
            assertEquals(0, count("SELECT COUNT(*) FROM article_revision WHERE article_id = ?", articleId),
                    "revision 在失败事务后残留 ⇒ 没有真的回滚");
            assertEquals(0, count("SELECT COUNT(*) FROM article_publication WHERE article_id = ?", articleId),
                    "指针在失败事务后残留 ⇒ 没有真的回滚");
            assertEquals(0, count("SELECT COUNT(*) FROM article_domain_outbox WHERE aggregate_id = ?", articleId),
                    "域事件在失败事务后残留 ⇒ 没有真的回滚");

            ArticleEntity article = articleMapper.selectById(articleId);
            assertNotNull(article);
            assertEquals(3, article.getStatus(),
                    "业务状态在没有域事件的情况下被改成 PUBLISHED");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS deny_domain_event ON article_domain_outbox");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS deny_domain_event()");
        }
    }

    // ==================================================================
    // ③ 域事件顺序保证
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：同一 (aggregate_id, aggregate_version) 不能有两条域事件")
    void domainOutboxUniqueConstraintOrdersEvents() {
        jdbcTemplate.update("INSERT INTO article_domain_outbox "
                + "(event_id, aggregate_id, aggregate_version, event_type, payload, status, retry_count, created_at, updated_at)"
                + " VALUES ('e905-1', ?, 1, 'community.article.published', '{}'::jsonb, 0, 0, now(), now())",
                AUTHOR);

        assertThrows(Exception.class, () -> jdbcTemplate.update("INSERT INTO article_domain_outbox "
                        + "(event_id, aggregate_id, aggregate_version, event_type, payload, status, retry_count, created_at, updated_at)"
                        + " VALUES ('e905-2', ?, 1, 'community.article.retracted', '{}'::jsonb, 0, 0, now(), now())",
                AUTHOR),
                "同一版本号能插入两条域事件 ⇒ 事件顺序保证失效");

        // 不同版本号可以共存
        jdbcTemplate.update("INSERT INTO article_domain_outbox "
                + "(event_id, aggregate_id, aggregate_version, event_type, payload, status, retry_count, created_at, updated_at)"
                + " VALUES ('e905-3', ?, 2, 'community.article.retracted', '{}'::jsonb, 0, 0, now(), now())",
                AUTHOR);
        assertEquals(2, count("SELECT COUNT(*) FROM article_domain_outbox WHERE aggregate_id = ?", AUTHOR));
    }

    // ==================================================================
    // ④ AOP 代理
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：ArticleAtomicService 必须是 AOP 代理（否则 @Transactional 静默失效）")
    void atomicServiceIsAopProxied() {
        assertTrue(AopUtils.isAopProxy(atomicService),
                "ArticleAtomicService 不是 AOP 代理 ⇒ @Transactional 静默失效，"
                        + "retract/soft-delete/outbox 三步会各自 auto-commit");
    }

    // ==================================================================
    // ⑤ 通知判定
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：首次发布（reason=create）⇒ 要求发通知；**已发布过** ⇒ 不再通知（但发布照样发生）")
    void acceptSyncResultNotifiesOnlyOnFirstPublish() {
        // ① 首次发布：last_sync_reason = create 且 published_once 为空 ⇒ 要通知
        String firstId = "905001003";
        seedReviewingArticle(firstId, "905001003.md", "sync-905-3", 3000L,
                ArticleSyncEvent.REASON_CREATE, "row-title");
        insertRevisionReadyOutbox(firstId, "sync-905-3", 3000L, "frozen-title");

        ArticleSyncEvent.ArticleSyncResult first = new ArticleSyncEvent.ArticleSyncResult(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, "sync-905-3", firstId,
                ArticleSyncEvent.OP_UPSERT, 3000L, true, null);

        ArticleAtomicService.PublishOutcome outcome = atomicService.acceptSyncResult(first, mapper);
        assertNotNull(outcome, "reason=create 且从未发布过 ⇒ 必须要求发通知");
        assertTrue(outcome.notifyRequired(), "notifyRequired 必须为 true");
        assertEquals(AUTHOR, outcome.authorId(), "通知对象是文章作者");
        assertEquals("row-title", outcome.title(), "通知标题取**当前行**的标题");

        // ② 已发布过 ⇒ 不再通知
        String secondId = "905001004";
        seedReviewingArticle(secondId, "905001004.md", "sync-905-4", 4000L,
                ArticleSyncEvent.REASON_CREATE, "row-title");
        jdbcTemplate.update("UPDATE article SET ext_info = jsonb_set(ext_info, '{published_once}', '\"true\"') "
                + "WHERE id = ?", secondId);
        insertRevisionReadyOutbox(secondId, "sync-905-4", 4000L, "frozen-title");

        ArticleSyncEvent.ArticleSyncResult second = new ArticleSyncEvent.ArticleSyncResult(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, "sync-905-4", secondId,
                ArticleSyncEvent.OP_UPSERT, 4000L, true, null);

        ArticleAtomicService.PublishOutcome outcome2 = atomicService.acceptSyncResult(second, mapper);
        assertNull(outcome2, "published_once 已非空 ⇒ 不该再通知");
        // ⚠️ 但**发布本身**照样发生（published_once 只挡通知，不挡发布）
        assertEquals(2, articleMapper.selectById(secondId).getStatus(),
                "published_once 只挡通知，**不挡发布**");
    }

    // ==================================================================
    // ⑥ 冻结 payload 的标题 vs 当前行的标题
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：revision 标题取**冻结 payload**，通知标题取**当前行**（两个源不可混用）")
    void acceptSyncResultUsesFrozenTitleForRevisionButCurrentRowTitleForNotification() {
        String articleId = "905001005";
        // 当前行标题 = 作者在审核期间改过的新标题
        seedReviewingArticle(articleId, "905001005.md", "sync-905-5", 5000L,
                ArticleSyncEvent.REASON_CREATE, "作者改过的标题");
        // 冻结 payload 的标题 = 入队那一刻的旧标题
        insertRevisionReadyOutbox(articleId, "sync-905-5", 5000L, "冻结时的标题");

        ArticleSyncEvent.ArticleSyncResult result = new ArticleSyncEvent.ArticleSyncResult(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, "sync-905-5", articleId,
                ArticleSyncEvent.OP_UPSERT, 5000L, true, null);

        ArticleAtomicService.PublishOutcome outcome = atomicService.acceptSyncResult(result, mapper);

        // ① revision：冻结 payload 的标题
        assertEquals("冻结时的标题",
                jdbcTemplate.queryForObject(
                        "SELECT title FROM article_revision WHERE article_id = ?", String.class, articleId),
                "revision 的标题必须取**冻结 payload**");

        // ② 通知：当前行的标题
        assertNotNull(outcome, "reason=create 且从未发布过 ⇒ 必须返回 PublishOutcome");
        assertEquals("作者改过的标题", outcome.title(),
                "通知标题必须取**当前行**，不是冻结 payload 的标题");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private int count(String sql, Object... args) {
        Integer c = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    private long longOf(String sql, Object... args) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    /**
     * 种一篇 REVIEWING(3) 的文章，{@code ext_info} 带上发布回调的<b>双重幂等判据</b>
     * （{@code last_sync_event_id} + {@code last_sync_version_ms}）。
     */
    private void seedReviewingArticle(String articleId, String contentPath, String eventId,
                                      long versionMs, String reason, String rowTitle) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, ?, 'brief', ?, '', 'tag', '[]'::jsonb, ?, 3, 0, 0, 0, 0,
                        jsonb_build_object(
                          'reco_sync_state', 'pending',
                          'last_sync_event_id', ?,
                          'last_sync_version_ms', ?::text,
                          'last_sync_reason', ?,
                          'pending_sync_reason', ?,
                          'review_nonce', ?),
                        now(), now())
                """, articleId, rowTitle, contentPath, AUTHOR, eventId,
                String.valueOf(versionMs), reason, reason, eventId);
    }

    /**
     * 建一条 pending 的同步 outbox 行（发布回调要读回它的<b>冻结 payload</b>）。
     *
     * <p>⚠️ payload 必须是 {@code ArticleSyncEvent} 的真实序列化结果 ——
     * {@code acceptSyncResult} 会把它反序列化回 {@code ArticleSyncEvent} 做六项比对。
     */
    private void insertRevisionReadyOutbox(String articleId, String eventId, long versionMs,
                                            String frozenTitle) {
        ArticleSyncEvent frozen = new ArticleSyncEvent(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE,
                eventId,
                articleId,
                ArticleSyncEvent.OP_UPSERT,
                ArticleSyncEvent.REASON_UPDATE,
                AUTHOR,
                "PUBLISHED",
                versionMs,
                frozenTitle,
                "frozen-brief",
                "",
                "approved-manual",
                List.of("approved-tag"),
                "# Approved");
        syncOutboxMapper.insert(eventId,
                ArticleSyncEvent.articleSyncEventKey(articleId, ArticleSyncEvent.OP_UPSERT, eventId),
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, articleId, JsonbValueTypeHandler.writeJson(frozen));
    }
}
