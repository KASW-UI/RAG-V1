package com.rag.article.domain.service;

import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.exception.ArticleExceptions;
import com.rag.article.support.ArticleRealPgTestBase;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import com.rag.proto.article.UpdateArticleRequest;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p><b>真触发</b>：{@code ArticleDomainService} 的<b>分支判定顺序</b>与
 * <b>失败口径</b> —— 数据库层是<b>真 PG + 真 Mapper + 真事务</b>，
 * 只有两个<b>外部系统</b>（对象存储 / Kafka）被桩掉。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <p>本类的判据全部是「<b>哪条分支被选中</b>」与「<b>失败时抛出什么</b>」，
 * 而 B 侧原版（{@code ArticleDomainServiceSemanticsTest}）是<b>全 Mockito</b>的：
 * mapper 被 mock ⇒ 「文章不存在 ⇒ NOT_FOUND」「作者不符 ⇒ PERMISSION_DENIED」
 * 这类判据<b>根本没碰过数据库</b>。
 * <p>⇒ 本类把 mapper 换回<b>真 PG</b>：种子数据真的写进库、真的被查出来、
 * 真的被 {@code updateById} 改写 ⇒ 分支判据落在<b>真实数据</b>上。
 * <p>⚠️ 只有 MinIO 与 Kafka 被桩：这两个系统在测试环境<b>不可达</b>，
 * 且本类的判据<b>不依赖</b>它们的真实行为（只依赖「有没有发起调用」）。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleDomainServiceSemanticsTest.java:82-360}（19 例判据可移植）；</li>
 *   <li>Go 出处 {@code service/article/rpc/internal/logic/get_article_logic.go:40-124}
 *       （三分支的判定顺序）与 {@code update_article_logic.go:46-127}（A/B/C 三分支）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code getArticle} 里 ① {@code PublicOnly} 与 ② {@code RequesterId+IncrView}
 *       两条判断调换 ⇒ 用例 ① <b>立刻红</b>（公开读变成 InvalidArgument）；</li>
 *   <li>把作者校验挪到取正文之后 ⇒ 用例 ③ <b>立刻红</b>
 *       （{@code verify(storage, never()).readString(...)} 会失败）；</li>
 *   <li>把 {@code incrViewCount} 的 best-effort 改成致命 ⇒ 用例 ④ <b>立刻红</b>；</li>
 *   <li>把 update 的 A/B 分支判定顺序调换 ⇒ 用例 ⑤ <b>立刻红</b>
 *       （会退化成重审 + 入队审核）；</li>
 *   <li>把 B 分支的 {@code setSyncState} 的 eventId 参数从 {@code ""} 改成真实 ID
 *       ⇒ 用例 ⑥ <b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类专属作者号 {@code 907000001}；行 id 前缀 {@code 907001}。
 */
class ArticleDomainServiceRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "907000001";
    private static final String OTHER_AUTHOR = "907000999";

    /** 审核消息的 topic（来自 {@code article-rpc.kq-pusher-conf.topic}）。 */
    private static final String REVIEW_TOPIC = "article-creation-topic";

    @Autowired
    private ArticleDomainService domainService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 对象存储桩 —— 本类只关心「有没有发起读取」，不关心内容。
     *
     * <p>⚠️ 用 {@code @MockitoBean}（Spring Framework 6.2 / Boot 3.4+ 的新注解），
     * 不是已废弃的 {@code @MockBean}。
     */
    @MockitoBean
    private ArticleObjectStorageClient storage;

    /** Kafka 桩 —— 让 {@code enqueueReview} 的 {@code send(...).get()} 立刻成功。 */
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
    // ① PublicOnly 优先于 RequesterId+IncrView 校验
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：PublicOnly **优先于** RequesterId+IncrView 校验（公开读+作者身份+计阅读量 是合法的）")
    void publicOnlyShortCircuitsTheInvalidArgumentBranch() {
        seedPublishedArticleWithPointer("907001001");

        GetArticleRequest request = GetArticleRequest.newBuilder()
                .setArticleId("907001001")
                .setRequesterId(OTHER_AUTHOR)   // 传一个**非作者**的身份
                .setIncrView(true)               // 且要求计阅读量
                .setPublicOnly(true)            // 但走公开读
                .build();

        // ⚠️ 若 PublicOnly 不是第一条判据 ⇒ 会落 ② ⇒ 抛 INVALID_ARGUMENT
        GetArticleResponse response = domainService.getArticle(request);

        assertNotNull(response, "公开读 + 作者身份 + 计阅读量 必须**正常返回**（不是 InvalidArgument）");
        assertNotNull(response.getArticle());
        assertEquals("907001001", response.getArticle().getId());
        assertEquals(1, publicViewCountOf("907001001"), "公开读的计阅读量必须真的发生");
    }

    // ==================================================================
    // ② 文章不存在 ⇒ NOT_FOUND
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：文章不存在 ⇒ NOT_FOUND \"article not found\"（selectById 返 null，不是抛异常）")
    void missingArticleIsNotFound() {
        ArticleExceptions.Rpc e = assertThrows(ArticleExceptions.Rpc.class,
                () -> domainService.getArticle(GetArticleRequest.newBuilder()
                        .setArticleId("907001404").build()),
                "文章不存在必须映射成 NOT_FOUND");
        assertEquals(Status.Code.NOT_FOUND, e.getCode());
        assertEquals("article not found", e.getMessage(), "Go: get_article_logic.go:58");
    }

    // ==================================================================
    // ③ 作者不符 ⇒ PERMISSION_DENIED，且早于 MinIO 取正文
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：作者不符 ⇒ PERMISSION_DENIED，且**早于** MinIO 取正文")
    void otherAuthorIsDeniedBeforeObjectFetch() throws Exception {
        seedReviewingArticle("907001002");

        ArticleExceptions.Rpc e = assertThrows(ArticleExceptions.Rpc.class,
                () -> domainService.getArticle(GetArticleRequest.newBuilder()
                        .setArticleId("907001002")
                        .setRequesterId(OTHER_AUTHOR)
                        .build()),
                "作者不符必须映射成 PERMISSION_DENIED");
        assertEquals(Status.Code.PERMISSION_DENIED, e.getCode());
        assertEquals("article author differs", e.getMessage(), "Go: get_article_logic.go:67");

        // ⚠️ 关键：不得发起对象读取（Go get_article_logic.go:66-68 在取正文之前 return）
        verify(storage, never()).readString(anyString());
    }

    // ==================================================================
    // ④ 浏览量自增是 best-effort
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：浏览量自增是 best-effort —— 失败只记日志，仍返回成功（不可对称化）")
    void viewCountIncrementIsBestEffortOnAuthorPath() throws Exception {
        seedReviewingArticle("907001003");

        // ⚠️ 让**真 PG** 的 UPDATE 失败（加一条 CHECK 约束）
        jdbcTemplate.execute(
                "ALTER TABLE article ADD CONSTRAINT chk_force_view_fail CHECK (view_count < 0) NOT VALID");
        try {
            GetArticleResponse response = domainService.getArticle(GetArticleRequest.newBuilder()
                    .setArticleId("907001003")
                    .setIncrView(true)
                    .build());

            assertNotNull(response.getArticle(),
                    "自增失败不应让整个请求失败（Go get_article_logic.go:72-75 只记日志）");
            assertEquals("# body", response.getArticle().getMarkdownContent());
            // 响应里的 viewCount 是**内存 +1**（Go:76 article.ViewCount++），不是读库结果
            assertEquals(1, response.getArticle().getViewCount(),
                    "响应里的 viewCount 必须是内存 +1 后的值");
            assertEquals(0, rawViewCountOf("907001003"),
                    "库里没写成（UPDATE 失败）⇒ 两处口径必须对得上");
        } finally {
            jdbcTemplate.execute("ALTER TABLE article DROP CONSTRAINT IF EXISTS chk_force_view_fail");
        }
    }

    // ==================================================================
    // ⑤ A 分支优先于 B
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：A 分支**优先于** B —— 已发布 + 显式降级 + 内容变更 ⇒ 撤回发布（不是重审）")
    void branchAPrevailsOverBranchB() {
        seedPublishedArticleWithPointer("907001004");

        domainService.updateArticle(UpdateArticleRequest.newBuilder()
                .setArticleId("907001004")
                .setTitle("new title")                 // sourceChanged = true
                .setStatus(ArticleStatus.DRAFT)        // 显式非发布
                .build());

        // 走 A：撤回发布（指针 retracted + 域事件 + 同步 outbox），**不**入队审核
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = '907001004'", Integer.class),
                "🔴 状态必须是 DRAFT(1)（A 分支）；若是 REVIEWING(3) ⇒ 走了 B（重审）");
        assertEquals("retracted", jdbcTemplate.queryForObject(
                "SELECT state FROM article_publication WHERE article_id = '907001004'", String.class),
                "A 分支必须把指针置为 retracted");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_domain_outbox WHERE aggregate_id = '907001004'",
                Integer.class), "A 分支必须写一条 retracted 域事件");
        assertEquals("community.article.retracted", jdbcTemplate.queryForObject(
                "SELECT event_type FROM article_domain_outbox WHERE aggregate_id = '907001004'",
                String.class), "域事件类型必须是 community.article.retracted");

        // ⚠️ B 分支的标志是「入队审核」⇒ 必须**没有**发生
        verify(kafkaTemplate, never()).send(eq(REVIEW_TOPIC), anyString(), anyString());
    }

    // ==================================================================
    // ⑥ B 分支把 last_sync_event_id 置空
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：B 分支把 last_sync_event_id **置空**，让新一轮审核能通过幂等判据")
    void branchBClearsLastSyncEventId() {
        // ⚠️ B 分支的前置条件是 **prevPublished**（status == PUBLISHED(2)）
        //    ⇒ 必须种成已发布；种成 REVIEWING 会落进 C 分支（C 分支**不**调 setSyncState）。
        seedPublishedArticleWithPointer("907001005");
        jdbcTemplate.update("UPDATE article SET ext_info = jsonb_set(ext_info, "
                + "'{last_sync_event_id}', '\"old-event\"') WHERE id = ?", "907001005");

        domainService.updateArticle(UpdateArticleRequest.newBuilder()
                .setArticleId("907001005")
                .setTitle("new title")     // 只改标题 ⇒ sourceChanged，无显式状态 ⇒ B 分支
                .build());

        ArticleEntity after = jdbcTemplate.queryForObject(
                "SELECT ext_info FROM article WHERE id = '907001005'", (rs, i) -> {
                    ArticleEntity e = new ArticleEntity();
                    e.setExtInfo(JsonbTypeHandlerSupport.readMap(rs.getString("ext_info")));
                    return e;
                });
        assertNotNull(after);
        assertEquals("", after.getExtInfo().get("last_sync_event_id"),
                "🔴 Go update_article_logic.go:87 的 eventId 参数是 \"\"；"
                        + "置空才能让审核消费者的幂等判据（pending && lastSyncEventID != \"\"）为 false");

        // B 分支的另一个标志：状态回到 REVIEWING 且**入队审核**
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = '907001005'", Integer.class),
                "B 分支必须把状态置回 REVIEWING(3)");
        verify(kafkaTemplate).send(eq(REVIEW_TOPIC), eq("907001005"), anyString());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 一篇 REVIEWING(3) 的文章（作者视角读路径用）。 */
    private void seedReviewingArticle(String articleId) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'row-title', 'row-brief', ?, '', 'tag', '[]'::jsonb, ?, 3, 0, 0, 0, 0,
                        jsonb_build_object('reco_sync_state', 'pending',
                                           'last_sync_event_id', 'old-event',
                                           'last_sync_version_ms', '123',
                                           'last_sync_reason', 'create',
                                           'pending_sync_reason', 'create',
                                           'review_nonce', 'nonce'),
                        now(), now())
                """, articleId, "articles/content/" + articleId + ".md", AUTHOR);
    }

    /** 一篇已发布（status=2）+ 社区指针（state=published / pointer_version=1）的文章。 */
    private void seedPublishedArticleWithPointer(String articleId) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'row-title', 'row-brief', ?, '', 'tag', '[]'::jsonb, ?, 2, 0, 0, 0, 0,
                        jsonb_build_object('publish_stage', 'published',
                                           'reco_sync_state', 'succeeded',
                                           'published_once', 'true',
                                           'last_sync_event_id', 'evt-seed',
                                           'last_sync_version_ms', '1000',
                                           'last_sync_reason', 'create'),
                        now(), now())
                """, articleId, "articles/content/" + articleId + ".md", AUTHOR);
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 1, ?, ?, 'source-object', repeat('a', 64),
                        'rev-title', 'rev-brief', '', 'tag', '[]'::jsonb, '# approved', now(), ?)
                """, articleId, articleId + ":r1", AUTHOR, "sync-" + articleId);
        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES (?, ?, 1, 'published', 'community.article.evt-seed.published')
                """, articleId, articleId + ":r1");
    }

    private int rawViewCountOf(String articleId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT view_count FROM article WHERE id = ?", Integer.class, articleId);
        return v == null ? 0 : v;
    }

    private int publicViewCountOf(String articleId) {
        return rawViewCountOf(articleId);
    }

    /** 读回 jsonb → Map（用域内的 JsonbTypeHandler，避免另写一份解析口径）。 */
    static final class JsonbTypeHandlerSupport {
        private JsonbTypeHandlerSupport() {
        }

        static java.util.Map<String, String> readMap(String json) {
            try {
                return com.rag.article.mapper.JsonbTypeHandler.readMap(json);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
