package com.rag.article.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.ArticleServiceApplication;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.messaging.ArticleSyncResultConsumer;
import com.rag.article.support.KafkaTestBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rag.proto.article.ArticleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <p><b>真 Kafka 上的「同步结果消费者」消息链路测试</b> ——
 * {@code revision_id} 的格式解析与落库、指针推进、域事件写入。
 *
 * <h2>🔴 为什么这条链路必须跑<b>真 Kafka</b></h2>
 * <p>同步结果消费者是整个 article 域<b>唯一</b>把「审核通过」落成「已发布」的地方，
 * 而它的判据链（Go {@code article_sync_result_consumer.go:72-164}）<b>顺序不可调换</b>：
 * <pre>
 * ① 已软删 ⇒ 不动
 * ② ext[last_sync_event_id] != result.EventID
 *   || ext[last_sync_version] != FormatInt(result.VersionMs)
 *   || article.Status == PUBLISHED            ⇒ 不动（② 必须在 ③ 之前）
 * ③ !result.Success ⇒ status=REVIEWING + 记失败态
 * ④ article.Status != REVIEWING ⇒ 不动
 * ⑤ 读回 outbox 的冻结 payload ⇒ 六项比对不符 ⇒ error（重投）
 * ⑥ 建 revision + 推进指针 + 写域事件 + status=PUBLISHED
 * </pre>
 * ⇒ <b>「迟到 / 重放的结果绝不能改变状态」</b>这条保证，
 * 只有在<b>消息真的经由 broker 投递给 listener</b>时才能验。
 *
 * <h2>被测对象是<b>生产装配本身</b></h2>
 * <ul>
 *   <li>消费者：生产的 {@code ArticleSyncResultConsumer}；</li>
 *   <li>事务载体：生产的 {@code ArticleAtomicService#acceptSyncResult}
 *       （<b>{@code @Transactional} 真生效</b> ⇒ 判定与写入在同一个行锁事务内）；</li>
 *   <li>topic / group：<b>真实名字</b> {@code article-sync-results} / {@code article-sync-result}；</li>
 *   <li>数据库：<b>真 PG</b>（真 DDL ⇒ 真触发器 ⇒ 真约束）。</li>
 * </ul>
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>结果消息的字段名（event_id / article_id / op / version_ms / success）</td>
 *       <td>{@code mq/ArticleSyncEventTypes.java:273-299}（B 侧）</td>
 *       <td>{@code contract/ArticleSyncEvent.java:262-270}</td></tr>
 *   <tr><td>{@code revision_id} 格式 {@code <articleID>:r<revision>}</td>
 *       <td>{@code service/ArticleAtomicService.java:453}（B 侧）
 *           ＋ {@code domain/ArticleRevisionEntity.java:23-24}</td>
 *       <td>{@code domain/service/ArticleAtomicService.java:533}</td></tr>
 *   <tr><td>六项冻结输入比对（⑤）</td>
 *       <td>{@code service/ArticleAtomicService.java:95-107}（B 侧，Go 语义）</td>
 *       <td>{@code domain/service/ArticleAtomicService.java:434-440}</td></tr>
 *   <tr><td>发布成功 ⇒ {@code publish_stage=published} / {@code reco_sync_state=succeeded}</td>
 *       <td>Go {@code article_sync_result_consumer.go:133-148}（B 侧 javadoc 转录）</td>
       <td>{@code domain/service/ArticleAtomicService.java:568-570}</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code revision.setRevisionId(...)} 改成别的格式（例如用 eventId 代替）
 *       ⇒ 用例 ① <b>立刻红</b>；</li>
 *   <li>把六项比对里的 {@code versionMs} 一项去掉 ⇒ 用例 ② <b>立刻红</b>
 *       （版本不符的迟到结果会把文章推进到已发布）；</li>
 *   <li>把 {@code acceptSyncResult} 的 {@code @Transactional} 去掉
 *       ⇒ 用例 ② 的「指针版本」与「域事件版本」不再同生共死（可能只落一半）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>单例 PG 容器共用 ⇒ 本类用专属文章 id 段 {@code 902002xxx}。
 */
class ArticleKafkaSyncResultConsumerRealKafkaTest extends KafkaTestBase {

    /** 本类专属作者号。 */
    private static final String AUTHOR = "902000002";

    /** 事件版本号（毫秒时间戳）—— 必须与 {@code ext_info.last_sync_version_ms} 逐字一致。 */
    private static final long VERSION_MS = 1_700_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loggerOf(ArticleServiceApplication.class).addAppender(appender);
        loggerOf(ArticleSyncResultConsumer.class).addAppender(appender);
        appenders.add(appender);
    }

    @AfterEach
    void tearDown() {
        for (ListAppender<ILoggingEvent> appender : appenders) {
            loggerOf(ArticleServiceApplication.class).detachAppender(appender);
            loggerOf(ArticleSyncResultConsumer.class).detachAppender(appender);
        }
        appenders.clear();
    }

    // ==================================================================
    // ① 审核通过 ⇒ 落 revision（revision_id 格式）+ 推进指针 + 写域事件
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Kafka：同步结果（op=upsert）⇒ 建 revision（revision_id = <articleID>:r1）"
            + " + 推进社区指针 + 写域事件 + 文章转 PUBLISHED")
    void upsertResultCreatesRevisionWithCanonicalRevisionId() throws Exception {
        String articleId = "902002001";
        String eventId = "evt-902002001";
        String content = "articles/content/" + articleId + ".md";
        String markdown = "# approved body";

        seedReviewingArticle(articleId, content, eventId, VERSION_MS);
        seedFrozenOutboxRow(articleId, eventId, VERSION_MS, markdown);

        long offsetBefore = committedOffset(GROUP_SYNC_RESULT, TOPIC_SYNC_RESULTS);

        // 发布审核/同步结果消息（字段名与 B 侧 ArticleSyncResult 逐字一致）
        ArticleSyncEvent.ArticleSyncResult result = new ArticleSyncEvent.ArticleSyncResult(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, eventId, articleId,
                ArticleSyncEvent.OP_UPSERT, VERSION_MS, true, "");
        publish(TOPIC_SYNC_RESULTS, articleId, mapper.writeValueAsString(result));

        // 🔴 场景有效性断言：消费组的位点推进 ⇒ 消息真的经由 broker 被消费了
        awaitOffsetAdvance(offsetBefore, 1, Duration.ofSeconds(60));

        // ── 判据①：revision 落库，且 revision_id 格式 = <articleID>:r<revision> ──
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT * FROM article_revision WHERE article_id = ? ORDER BY revision", articleId);
        assertEquals(1, revisions.size(), "应当恰好建一条 revision；实际 = " + revisions);
        Map<String, Object> revision = revisions.get(0);

        assertEquals(articleId + ":r1", revision.get("revision_id"),
                "🔴 revision_id 必须是 <articleID>:r<revision> 的格式"
                        + "（B 侧 ArticleAtomicService.java:453）；实际 = " + revision.get("revision_id"));
        assertEquals(1L, ((Number) revision.get("revision")).longValue(), "revision 序号应为 1");
        assertEquals(eventId, revision.get("sync_event_id"), "revision 必须记下这次同步的事件 id");
        assertEquals(AUTHOR, revision.get("author_id"));
        assertEquals(content, revision.get("source_object"),
                "source_object 必须是 article.content（MinIO 对象 key），不是正文");
        assertEquals(sha256Hex(markdown), revision.get("content_sha256"),
                "content_sha256 必须是冻结正文的 SHA-256（十六进制小写）");
        assertEquals(markdown, revision.get("markdown"), "revision 必须冻结当时的正文");

        // ── 判据②：社区指针推进（current_revision 指向刚建的 revision）──
        Map<String, Object> pointer = jdbcTemplate.queryForMap(
                "SELECT * FROM article_publication WHERE article_id = ?", articleId);
        assertEquals(articleId + ":r1", pointer.get("current_revision"),
                "指针必须指向刚建的 revision");
        assertEquals(1L, ((Number) pointer.get("pointer_version")).longValue(),
                "首版指针版本应为 1");
        assertEquals("published", pointer.get("state"), "指针状态必须是 published");

        // ── 判据③：域事件写入（H03 顺序保证：aggregate_version 唯一且单调）──
        Long domainEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM article_domain_outbox WHERE aggregate_id = ?",
                Long.class, articleId);
        assertEquals(1L, domainEvents, "应当恰好写一条域事件");
        String eventType = jdbcTemplate.queryForObject(
                "SELECT event_type FROM article_domain_outbox WHERE aggregate_id = ?",
                String.class, articleId);
        assertTrue(eventType != null && eventType.endsWith("published"),
                "域事件类型必须是 published 事件；实际 = " + eventType);

        // ── 判据④：文章行转 PUBLISHED，同步状态机推进到「已发布」──
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, articleId);
        assertEquals(ArticleStatus.PUBLISHED_VALUE, status, "文章应转为 PUBLISHED");
        String extInfo = jdbcTemplate.queryForObject(
                "SELECT ext_info::text FROM article WHERE id = ?", String.class, articleId);
        assertNotNull(extInfo);
        // ⚠️ PostgreSQL 的 `jsonb::text` 规范化输出**冒号后带一个空格**（`"k": "v"`）
        //   ⇒ 断言前先归一化，否则会因为「空格」而不是「值」变红
        String normalizedExtInfo = extInfo.replace(": ", ":");
        assertTrue(normalizedExtInfo.contains("\"publish_stage\":\"published\""),
                "publish_stage 应为 published；实际 = " + extInfo);
        assertTrue(normalizedExtInfo.contains("\"reco_sync_state\":\"succeeded\""),
                "reco_sync_state 应为 succeeded；实际 = " + extInfo);
        assertTrue(normalizedExtInfo.contains("\"published_once\":\"true\""),
                "published_once 应被置为 true（一次性通知守卫）；实际 = " + extInfo);
    }

    // ==================================================================
    // ② 版本不匹配的迟到结果 ⇒ 不得推进状态（② 判据链的第一条）
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Kafka：版本号不匹配的迟到结果 ⇒ 文章状态与 revision 均不被推进"
            + "（② 判据链：版本不符 ⇒ 什么都不做）")
    void staleVersionResultDoesNotAdvanceState() throws Exception {
        String articleId = "902002002";
        String eventId = "evt-902002002";
        String content = "articles/content/" + articleId + ".md";
        String markdown = "# approved body";

        seedReviewingArticle(articleId, content, eventId, VERSION_MS);
        seedFrozenOutboxRow(articleId, eventId, VERSION_MS, markdown);

        long offsetBefore = committedOffset(GROUP_SYNC_RESULT, TOPIC_SYNC_RESULTS);

        // ⚠️ 版本号故意**不匹配**（冻结的是 VERSION_MS，这条带 VERSION_MS + 1）
        ArticleSyncEvent.ArticleSyncResult stale = new ArticleSyncEvent.ArticleSyncResult(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE, eventId, articleId,
                ArticleSyncEvent.OP_UPSERT, VERSION_MS + 1L, true, "");
        publish(TOPIC_SYNC_RESULTS, articleId, mapper.writeValueAsString(stale));

        // 🔴 场景有效性断言：位点推进 ⇒ 消息真的被消费了（不是「压根没收到」）
        awaitOffsetAdvance(offsetBefore, 1, Duration.ofSeconds(60));

        // ── 判据：状态机一步都没动 ──
        Long revisions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM article_revision WHERE article_id = ?", Long.class, articleId);
        assertEquals(0L, revisions, "版本不符的迟到结果绝不能建 revision");

        Long pointers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM article_publication WHERE article_id = ?", Long.class, articleId);
        assertEquals(0L, pointers, "版本不符的迟到结果绝不能推进指针");

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, articleId);
        assertEquals(ArticleStatus.REVIEWING_VALUE, status,
                "版本不符的迟到结果不得把状态推进（更不能回退成 REVIEWING 之外的值）");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 插入一篇「审核中」且同步判据齐全的文章。
     *
     * <p>⚠️ {@code last_sync_event_id} / {@code last_sync_version_ms} 是判据链 ② 的两条判据，
     * 必须<b>与结果消息逐字一致</b>（B 侧 {@code Go FormatInt(result.VersionMs)} 是十进制字符串）。
     */
    private void seedReviewingArticle(String articleId, String content, String eventId, long versionMs) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'title-' || ?, 'brief', ?, '', 'tag-a',
                        CAST('["x","y"]' AS jsonb), ?, ?, 0, 0, 0, 0,
                        CAST(? AS jsonb), now(), now())
                """,
                articleId, articleId, content, AUTHOR, ArticleStatus.REVIEWING_VALUE,
                "{\"review_nonce\":\"nonce\",\"last_sync_event_id\":\"" + eventId + "\","
                        + "\"last_sync_version_ms\":\"" + versionMs + "\","
                        + "\"last_sync_reason\":\"create\","
                        + "\"pending_sync_reason\":\"create\","
                        + "\"publish_stage\":\"reco_queued\","
                        + "\"reco_sync_state\":\"pending\"}");
    }

    /**
     * 插入「冻结的」同步事件 outbox 行 —— 发布回调要读回它做<b>六项比对</b>。
     *
     * <p>⚠️ payload 必须是 {@link ArticleSyncEvent} 的序列化结果
     * （B 侧「推送的是原文，不重新序列化」这条纪律的前提）。
     */
    private void seedFrozenOutboxRow(String articleId, String eventId, long versionMs, String markdown)
            throws Exception {
        ArticleSyncEvent frozen = new ArticleSyncEvent(
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE,
                eventId,
                articleId,
                ArticleSyncEvent.OP_UPSERT,
                ArticleSyncEvent.REASON_CREATE,
                AUTHOR,
                "PUBLISHED",
                versionMs,
                "frozen-title",
                "frozen-brief",
                "",
                "tag-a",
                List.of("x", "y"),
                markdown);
        jdbcTemplate.update("""
                INSERT INTO article_sync_outbox
                    (event_id, event_key, event_type, aggregate_id, payload, status, retry_count,
                     last_error, created_at, updated_at)
                VALUES (?, ?, 'article_sync', ?, CAST(? AS jsonb), 0, 0, '', now(), now())
                """,
                eventId,
                ArticleSyncEvent.articleSyncEventKey(articleId, ArticleSyncEvent.OP_UPSERT, eventId),
                articleId,
                mapper.writeValueAsString(frozen));
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void awaitOffsetAdvance(long before, long expectedAdvance, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long now = before;
        while (System.nanoTime() < deadline) {
            now = committedOffset(GROUP_SYNC_RESULT, TOPIC_SYNC_RESULTS);
            if (now >= before + expectedAdvance) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("消费组 " + GROUP_SYNC_RESULT + " 的位点没有推进（before=" + before + " now=" + now
                + "）⇒ 消息没被真消费");
    }
}
