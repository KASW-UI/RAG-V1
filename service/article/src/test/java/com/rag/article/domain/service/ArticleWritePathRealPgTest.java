package com.rag.article.domain.service;

import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.support.ArticleRealPgTestBase;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.CreateArticleRequest;
import com.rag.proto.article.CreateArticleResponse;
import com.rag.proto.article.DeleteArticleRequest;
import com.rag.proto.article.DeleteArticleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p><b>真触发</b>：article 域**其余五条写路径** —— 创建 / 删除 / 撤回发布 / 撤回并删除 /
 * 审核通过入同步 outbox —— 各自的<b>业务副作用</b>（落了哪些行、改了哪些状态）。
 *
 * <h2>🔴 为什么这些必须补</h2>
 * <p>台账里已登记的缺口：写路径只覆盖了 {@code acceptSyncResult}（发布回调），
 * 而<b>创建 / 删除 / 两条撤回 / 审核入队</b>五条路径**从未被单独断言过副作用**。
 * ⇒ 「写路径能跑通」≠「写路径落对了库」。
 *
 * <h2>为什么只桩掉 MinIO 与 Kafka</h2>
 * <p>本类的判据全部落在<b>数据库</b>上（哪几行落了、状态改成什么、outbox 有没有写）。
 * ⇒ <b>数据库层必须是真的</b>（真 PG + 真 Mapper + 真事务）。
 * ⇒ MinIO / Kafka 是<b>外部系统</b>，本类只断言「有没有发起调用」与「调用参数是什么」
 * ⇒ 桩掉它们不削弱判据（MinIO / Kafka 的真实链路由另一个用例组覆盖）。
 *
 * <h2>B 出处（逐条）</h2>
 * <ul>
 *   <li>创建：Go {@code create_article_logic.go:42-130}（MinIO 写正文 → 落库 status=REVIEWING
 *       → 推审核消息）；</li>
 *   <li>删除：Go {@code delete_article_logic.go:40-96}（FindOne → 鉴权 → RunInTx{RemovePublication +
 *       CreateSyncOutbox + DeleteTx}）；</li>
 *   <li>撤回发布：Go {@code update_article_logic.go:216-221}（RunInTx{RemovePublicationTx + UpdateTx}）；</li>
 *   <li>撤回并删除：Go {@code delete_article_logic.go:84-96}；</li>
 *   <li>审核入队：Go {@code article_consumer.go:104-136}（两层幂等 + 四元组校验 + 建 outbox）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>创建时把 {@code status} 写成 DRAFT ⇒ ① <b>立刻红</b>；</li>
 *   <li>删除时忘了软删（只删指针）⇒ ② <b>立刻红</b>（{@code deleted_at} 仍为 null）；</li>
 *   <li>撤回时不推进 {@code pointer_version} ⇒ ③④ <b>立刻红</b>；</li>
 *   <li>把事务内那层幂等复查去掉 ⇒ ⑤ <b>立刻红</b>（第二次调用会插出第二条 outbox 行）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类行 id 前缀 {@code 912001}；作者号 {@code 912000001}。
 */
class ArticleWritePathRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "912000001";

    /** 审核消息的 topic（来自 {@code article-rpc.kq-pusher-conf.topic}）。 */
    private static final String REVIEW_TOPIC = "article-creation-topic";

    @Autowired
    private ArticleDomainService domainService;

    @Autowired
    private ArticleAtomicService atomicService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 对象存储桩 —— 本类只关心「发起没发起调用」与「调用参数」。 */
    @MockitoBean
    private ArticleObjectStorageClient storage;

    /** Kafka 桩 —— 让审核消息推送立刻成功。 */
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");

        when(storage.getObjectNamePrefixForArticle()).thenReturn("articles/content/");
        when(storage.getObjectNamePrefixForImage()).thenReturn("articles/images/");
        when(storage.getBucketName()).thenReturn("articles");
        when(storage.readString(anyString())).thenReturn("# body");

        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(ok);
    }

    // ==================================================================
    // ① 创建：落库 status=REVIEWING + 8 个初始 ext_info 键 + 推审核消息
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：创建 ⇒ 正文写对象存储 + 落库 status=REVIEWING + ext_info 8 个初始键 + 推审核消息（key=articleId）")
    void createArticlePersistsReviewingRowAndEnqueuesReview() {
        CreateArticleResponse resp = domainService.createArticle(
                CreateArticleRequest.newBuilder()
                        .setTitle("t-912001001")
                        .setBrief("b")
                        .setMarkdownContent("# md")
                        .setAuthorId(AUTHOR)
                        .build());

        String articleId = resp.getArticleId();
        assertNotNull(articleId, "创建必须返回 article_id");
        assertFalse(articleId.isBlank(), "article_id 不得为空");

        // ① 行确实落库，状态 = REVIEWING(3)，content = 对象存储的 key
        ArticleEntity row = articleMapper.selectById(articleId);
        assertNotNull(row, "创建后必须能查到行");
        assertEquals(ArticleStatus.REVIEWING_VALUE, row.getStatus(),
                "创建后的状态必须是 REVIEWING(3)（Go: create_article_logic.go 的初始状态）");
        assertEquals("articles/content/" + articleId + ".md", row.getContent(),
                "content 存的是**对象存储 key**，不是正文（Go: objectName 无哈希）");
        assertEquals(AUTHOR, row.getAuthorId());

        // ② ext_info 的 8 个初始键是 L1 契约（消费者按它们判幂等）
        Map<String, String> ext = row.getExtInfo();
        assertNotNull(ext, "ext_info 必须落库");
        assertEquals("queued", ext.get(ArticleSyncEvent.EXT_PUBLISH_STAGE));
        assertEquals("pending_review", ext.get(ArticleSyncEvent.EXT_RECO_SYNC_STATE));
        assertEquals("", ext.get(ArticleSyncEvent.EXT_LAST_SYNC_ERROR));
        assertEquals("", ext.get(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID));
        assertEquals("0", ext.get(ArticleSyncEvent.EXT_LAST_SYNC_VERSION));
        assertEquals(ArticleSyncEvent.REASON_CREATE, ext.get(ArticleSyncEvent.EXT_PENDING_SYNC_REASON));
        assertEquals(ArticleSyncEvent.REASON_CREATE, ext.get(ArticleSyncEvent.EXT_LAST_SYNC_REASON));
        assertEquals(articleId, ext.get(ArticleSyncEvent.EXT_REVIEW_NONCE),
                "review_nonce 初始值 = articleID 本身（Go: mqs.ExtReviewNonce: articleID）");

        // ③ 审核消息真的推了，key = articleId（幂等键）
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(REVIEW_TOPIC),
                org.mockito.ArgumentMatchers.eq(articleId), anyString());
    }

    // ==================================================================
    // ② 删除：软删 + 撤指针 + 两条 outbox
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：删除 ⇒ 行被**软删**（deleted_at 非空）+ 指针撤回 + 域事件 + 同步 outbox 各一条")
    void deleteArticleSoftDeletesAndWritesOutboxes() {
        String articleId = "912001002";
        seedPublishedArticleWithPointer(articleId);

        DeleteArticleResponse resp = domainService.deleteArticle(
                DeleteArticleRequest.newBuilder()
                        .setArticleId(articleId)
                        .setOperatorId(AUTHOR)
                        .build());

        assertTrue(resp.getSuccess(), "删除成功必须回 success=true");

        // ① 行是**软删**（不是物理删除）
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM article WHERE id = ?", java.time.OffsetDateTime.class, articleId),
                "🔴 deleted_at 必须被写入（Go 的 gorm.DeletedAt 是软删除）");
        assertEquals(1, countRows("article", articleId),
                "🔴 软删**不得**物理删行（行仍在，只是 deleted_at 非空）"
                        + " —— 历史 revision 的冻结字节仍要能被 H03 读");
        assertNull(articleMapper.selectById(articleId),
                "🔴 软删后 selectById（带 deleted_at IS NULL）必须查不到 ⇒ Go 的 NotFound 语义");

        // ② 指针被撤回并推进版本
        assertEquals("retracted", jdbcTemplate.queryForObject(
                "SELECT state FROM article_publication WHERE article_id = ?", String.class, articleId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT pointer_version FROM article_publication WHERE article_id = ?",
                Long.class, articleId), "指针版本必须从 1 推进到 2");

        // ③ 域事件 + 同步 outbox 各一条
        assertEquals(1, countDomainEvents(articleId));
        assertEquals("community.article.retracted", jdbcTemplate.queryForObject(
                "SELECT event_type FROM article_domain_outbox WHERE aggregate_id = ?",
                String.class, articleId));
        assertEquals(1, countSyncOutbox(articleId));
    }

    // ==================================================================
    // ③ 撤回发布（不删行）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：撤回发布 ⇒ 行**保留**（不软删）+ 指针撤回 + 写 delete 同步事件")
    void retractAndUpdateRetractsPointerButKeepsRow() {
        String articleId = "912001003";
        seedPublishedArticleWithPointer(articleId);

        ArticleEntity forDowngrade = articleMapper.selectById(articleId);
        forDowngrade.setStatus(ArticleStatus.DRAFT_VALUE);
        Map<String, String> ext = ArticleSyncEvent.ensureExtInfo(forDowngrade.getExtInfo());
        ArticleSyncEvent.setSyncState(ext, "source_only", "pending",
                ArticleSyncEvent.REASON_STATUS_CHANGE, "evt-912001003", 2000L, "");
        forDowngrade.setExtInfo(ext);

        atomicService.retractAndUpdate(forDowngrade, "evt-912001003", 2000L,
                ArticleSyncEvent.REASON_STATUS_CHANGE);

        // ① 行保留（撤回 ≠ 删除）
        assertNotNull(articleMapper.selectById(articleId), "撤回发布不得软删行");
        assertEquals(ArticleStatus.DRAFT_VALUE, articleMapper.selectById(articleId).getStatus(),
                "状态必须落到 DRAFT(1)");

        // ② 指针撤回 + 版本推进
        assertEquals("retracted", jdbcTemplate.queryForObject(
                "SELECT state FROM article_publication WHERE article_id = ?", String.class, articleId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT pointer_version FROM article_publication WHERE article_id = ?",
                Long.class, articleId));

        // ③ 域事件 + 同步 outbox
        assertEquals(1, countDomainEvents(articleId));
        assertEquals(1, countSyncOutbox(articleId));
    }

    // ==================================================================
    // ④ 撤回并删除
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：撤回并删除 ⇒ 指针撤回 + 行软删 + 域事件 + 同步 outbox")
    void retractAndDeleteRetractsPointerAndSoftDeletes() {
        String articleId = "912001004";
        seedPublishedArticleWithPointer(articleId);

        atomicService.retractAndDelete(articleId, "evt-912001004", 2000L);

        assertEquals(1, countRows("article", articleId),
                "🔴 行必须被**保留**（软删语义）⇒ 行还在，只是 deleted_at 非空");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM article WHERE id = ?", java.time.OffsetDateTime.class, articleId),
                "deleted_at 必须被写入");
        assertNull(articleMapper.selectById(articleId),
                "🔴 软删后 selectById（带 deleted_at IS NULL）必须查不到");
        assertEquals("retracted", jdbcTemplate.queryForObject(
                "SELECT state FROM article_publication WHERE article_id = ?", String.class, articleId));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT pointer_version FROM article_publication WHERE article_id = ?",
                Long.class, articleId));
        assertEquals(1, countDomainEvents(articleId));
        assertEquals(1, countSyncOutbox(articleId));
    }

    // ==================================================================
    // ⑤ 审核通过入同步 outbox（两层幂等）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：审核入队 ⇒ 首次入队并改写 ext_info；**第二次同 eventId 必须被幂等挡掉**（事务内那层复查）")
    void queueSyncOutboxWithLockIsIdempotentOnSecondCall() {
        String articleId = "912001005";
        String contentPath = "articles/content/" + articleId + ".md";
        seedReviewingArticle(articleId, contentPath, articleId);

        boolean first = atomicService.queueSyncOutboxWithLock(
                articleId, AUTHOR, contentPath, articleId, "# Approved", "evt-912001005", 5000L);
        assertTrue(first, "首次入队必须返回 true（真的入队了）");

        // ① 同步 outbox 落一行
        assertEquals(1, countSyncOutbox(articleId), "首次必须入队一行");
        // ⚠️ outbox 的 event_type 列存的是**事件域**（article_sync），不是 op；
        //    op 在 payload 里（Go: ArticleSyncEventTypes.ARTICLE_SYNC_SCOPE 作 event_type）。
        assertEquals(ArticleSyncEvent.ARTICLE_SYNC_SCOPE, jdbcTemplate.queryForObject(
                "SELECT event_type FROM article_sync_outbox WHERE aggregate_id = ?",
                String.class, articleId), "outbox 的 event_type = 事件域（ARTICLE_SYNC_SCOPE）");
        assertEquals(ArticleSyncEvent.OP_UPSERT, payloadOpOf(articleId),
                "🔴 payload 里的 op 必须是 upsert（审核通过 ⇒ 同步 upsert，不是 delete）");

        // ② ext_info 被改写成「已排队待同步」
        Map<String, String> ext = articleMapper.selectById(articleId).getExtInfo();
        assertEquals("reco_queued", ext.get(ArticleSyncEvent.EXT_PUBLISH_STAGE));
        assertEquals("pending", ext.get(ArticleSyncEvent.EXT_RECO_SYNC_STATE));
        assertEquals("evt-912001005", ext.get(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID));

        // ③ 🔴 第二次同 eventId ⇒ 被**事务内那层幂等**挡掉（不是撞唯一索引）
        boolean second = atomicService.queueSyncOutboxWithLock(
                articleId, AUTHOR, contentPath, articleId, "# Approved", "evt-912001005", 5000L);
        assertFalse(second, "🔴 幂等复查必须让它返回 false（跳过），而不是再入队一行");
        assertEquals(1, countSyncOutbox(articleId),
                "🔴 幂等复查失败 ⇒ 会插出第二条 outbox 行（撞唯一索引 ⇒ 事务失败 ⇒ 重投浪费一轮）");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private int countRows(String table, String id) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return c == null ? 0 : c;
    }

    private int countDomainEvents(String aggregateId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_domain_outbox WHERE aggregate_id = ?",
                Integer.class, aggregateId);
        return c == null ? 0 : c;
    }

    private int countSyncOutbox(String aggregateId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_sync_outbox WHERE aggregate_id = ?",
                Integer.class, aggregateId);
        return c == null ? 0 : c;
    }

    /** 读 outbox payload 里的 {@code op} 字段（冻结的 ArticleSyncEvent JSON）。 */
    private String payloadOpOf(String aggregateId) {
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM article_sync_outbox WHERE aggregate_id = ?",
                String.class, aggregateId);
        assertNotNull(payload, "outbox payload 不得为 null");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(payload).path("op").asText();
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload 不是合法 JSON：" + payload, e);
        }
    }

    /** 建一条「已发布 + 指针」的文章（撤回 / 删除路径的前置条件）。 */
    private void seedPublishedArticleWithPointer(String articleId) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'published title', 'brief', 'pub.md', '', 'tag', '[]'::jsonb, ?, 2, 0, 0, 0, 0,
                        jsonb_build_object(
                          'publish_stage', 'published',
                          'reco_sync_state', 'succeeded',
                          'published_once', 'true',
                          'last_sync_event_id', 'evt-seed',
                          'last_sync_version_ms', '1000',
                          'last_sync_reason', 'create'),
                        now(), now())
                """, articleId, AUTHOR);
        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version, state, last_event_id)
                VALUES (?, ?, 1, 'published', 'community.article.evt-seed.published')
                """, articleId, articleId + ":r1");
    }

    /** 建一条「审核中」的文章（审核入队路径的前置条件：status=REVIEWING + review_nonce）。 */
    private void seedReviewingArticle(String articleId, String contentPath, String reviewNonce) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'reviewing title', 'brief', ?, '', 'tag', '[]'::jsonb, ?, 3, 0, 0, 0, 0,
                        jsonb_build_object(
                          'publish_stage', 'queued',
                          'reco_sync_state', 'pending_review',
                          'last_sync_error', '',
                          'last_sync_event_id', '',
                          'last_sync_version_ms', '0',
                          'pending_sync_reason', 'create',
                          'last_sync_reason', 'create',
                          'review_nonce', ?),
                        now(), now())
                """, articleId, contentPath, AUTHOR, reviewNonce);
    }
}
