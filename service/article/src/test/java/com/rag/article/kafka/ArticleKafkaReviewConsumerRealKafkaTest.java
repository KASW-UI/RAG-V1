package com.rag.article.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.ArticleServiceApplication;
import com.rag.article.client.UnavailableContentAuditClient;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.messaging.ArticleReviewConsumer;
import com.rag.article.support.KafkaTestBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rag.proto.article.ArticleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * <p><b>真 Kafka 上的「审核消费者」消息链路测试</b> —— 审核四元组校验、
 * 内容审核恒 fail-loud、失败消息的位点处置。
 *
 * <h2>🔴 为什么这三条判据<b>只能在真 Kafka 上验</b></h2>
 * <p>本组件的全部价值在于「<b>哪些分支必须丢、哪些必须重投</b>」：
 * <pre>
 * Go kq/queue.go:230-242   if err := consumeOne(...); err != nil {
 *                             if errorHandler != nil { errorHandler(ctx, msg, err) }
 *                             if !q.c.ForceCommit { continue }      ← ForceCommit 默认 true ⇒ 不 continue
 *                         }
 *                         q.consumer.CommitMessages(ctx, msg)        ← 失败也照样提交位点
 * </pre>
 * ⇒ Go：<b>失败 ⇒ 提交位点 ⇒ 消息被丢弃</b>，且<b>进程内无重试循环</b>。
 * ⇒ Java 侧必须把 Go 的<b>五条「丢」分支</b>翻译成「正常 return」、
 * 把<b>三条「重投」分支</b>翻译成「抛异常」。
 * <p>⚠️ <b>方向写反不会让任何 mock 单测变红</b>（mock 只看「方法有没有被调用」），
 * 只在真 broker 上才显形（消息被无限重投 / 被静默吞掉）。
 *
 * <h2>被测对象是<b>生产装配本身</b></h2>
 * <ul>
 *   <li>消费者：生产的 {@code ArticleReviewConsumer}（含四元组校验 + 审核 + 幂等 + 写 outbox）；</li>
 *   <li>容器工厂：生产的 {@code articleKafkaListenerContainerFactory}
 *       （{@code concurrency=8} ＋ {@code AckMode.RECORD}）；</li>
 *   <li>错误处理器：生产的 {@code articleNoCommitErrorHandler()}（{@code FixedBackOff(0,0)}）；</li>
 *   <li>topic / group：<b>真实名字</b>（{@code article-creation-topic} / {@code dataclean}），
 *       取自 {@code application.yml}（B 侧 yaml 同名）。</li>
 *   <li>⚠️ <b>唯一被顶掉的是 MinIO</b>（本机无 MinIO 镜像）⇒ 见 {@link KafkaTestBase} 的说明；
 *       <b>审核端口保持生产装配</b>（{@code UnavailableContentAuditClient}）。</li>
 * </ul>
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>四元组（status/content/author/nonce 四项全等）</td>
 *       <td>{@code ArticleReviewConsumer.java:234-240}（B 侧）
 *           ＋ Go {@code article_consumer.go:62-68}</td>
 *       <td>{@code messaging/ArticleReviewConsumer.java:326-332}</td></tr>
 *   <tr><td>四元组不齐 ⇒ 丢弃（不重投）</td>
 *       <td>{@code ArticleReviewConsumer.java:174-179}（B 侧）</td>
 *       <td>{@code messaging/ArticleReviewConsumer.java:262-266}</td></tr>
 *   <tr><td>审核 RPC 失败 ⇒ 抛错重投</td>
 *       <td>{@code ArticleReviewConsumer.java:202-203} ＋ {@code :274-277}（B 侧）</td>
 *       <td>{@code messaging/ArticleReviewConsumer.java:290} ＋ {@code :389-392}</td></tr>
 *   <tr><td>审核不可用 ⇒ 恒抛（fail-loud，绝不 fail-open）</td>
 *       <td>{@code client/UnavailableContentSecurityClient.java:71-77}（B 侧）</td>
 *       <td>{@code client/UnavailableContentAuditClient.java:91-96}</td></tr>
 *   <tr><td>失败消息的位点照样提交（ForceCommit=true）</td>
 *       <td>Go {@code kq/queue.go:230-242}；B 侧 Java
 *           {@code ArticleRpcApplication.java:228-243}</td>
 *       <td>{@code ArticleServiceApplication.java:175-190}</td></tr>
 *   <tr><td>期望值沿用 B 侧已有测试</td>
 *       <td>{@code mq/ArticleReviewConsumerTest.java:128-140}（丢弃③ 四元组）
 *           ＋ {@code :196-211}（重投② 审核 RPC 失败）</td>
 *       <td>本类</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code matchesReviewTuple} 的 nonce 一项去掉 ⇒ 用例 ① <b>立刻红</b>
 *       （旧 nonce 的消息会被受理）；</li>
 *   <li>把「四元组不齐」的分支改成抛异常 ⇒ 用例 ① <b>立刻红</b>
 *       （消息会被重投，位点不再前进）；</li>
 *   <li>把 {@code UnavailableContentAuditClient} 改成「返回 isAd=false 就算通过」
 *       ⇒ 用例 ② <b>立刻红</b>（outbox 会多出一行）；</li>
 *   <li>把容器的 {@code AckMode.RECORD} 改成 {@code BATCH}、
 *       或把 {@code FixedBackOff(0,0)} 改成 {@code (0,9)}
 *       ⇒ 用例 ② 的「只投递一次」<b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>单例 PG 容器被所有测试类共用 ⇒ 本类用专属作者号 {@code 902000001}
 * ＋ 每条用例专属的文章 id（{@code 902001xxx}）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArticleKafkaReviewConsumerRealKafkaTest extends KafkaTestBase {

    /** 本类专属作者号。 */
    private static final String AUTHOR = "902000001";

    /** 四元组齐全时的正文对象 key 模板。 */
    private static final String CONTENT_PREFIX = "articles/content/";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    // ==================================================================
    // 夹具
    // ==================================================================

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
        // ⚠️ MinIO 被顶掉（见 KafkaTestBase）：读正文必须成功，否则永远走不到「内容审核」分支
        try {
            org.mockito.Mockito.when(articleObjectStorageClient.readString(anyString()))
                    .thenReturn("# approved body");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        attachAppender(ArticleServiceApplication.class);
        attachAppender(ArticleReviewConsumer.class);
        attachAppender(UnavailableContentAuditClient.class);
    }

    @AfterEach
    void tearDown() {
        for (ListAppender<ILoggingEvent> appender : appenders) {
            loggerOf(ArticleServiceApplication.class).detachAppender(appender);
            loggerOf(ArticleReviewConsumer.class).detachAppender(appender);
            loggerOf(UnavailableContentAuditClient.class).detachAppender(appender);
        }
        appenders.clear();
    }

    private void attachAppender(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loggerOf(type).addAppender(appender);
        appenders.add(appender);
    }

    /** 收集本轮挂上的 logger 里的全部消息（运行期日志 = 真触发的直接证据）。 */
    private List<String> logMessages() {
        List<String> out = new ArrayList<>();
        for (ListAppender<ILoggingEvent> appender : appenders) {
            for (ILoggingEvent event : appender.list) {
                out.add(event.getFormattedMessage());
            }
        }
        return out;
    }

    private long countLog(String needle) {
        return logMessages().stream().filter(m -> m.contains(needle)).count();
    }

    private void awaitLog(String needle, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (countLog(needle) > 0) {
                return;
            }
            sleep(100);
        }
        fail("等待运行期日志片段超时（" + timeout + "）：「" + needle + "」"
                + "；本轮实际日志 = " + logMessages());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 插入一篇「审核中」的文章。
     *
     * @param articleId   文章 id
     * @param status      状态（{@link ArticleStatus#REVIEWING_VALUE} / {@link ArticleStatus#DRAFT_VALUE}）
     * @param content     {@code article.content}（= MinIO 对象 key）
     * @param authorId    作者 id
     * @param reviewNonce {@code ext_info.review_nonce}
     */
    private void seedArticle(String articleId, int status, String content,
                             String authorId, String reviewNonce) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'title-' || ?, 'brief', ?, '', 'tag-a',
                        CAST('["x","y"]' AS jsonb), ?, ?, 0, 0, 0, 0,
                        CAST(? AS jsonb), now(), now())
                """,
                articleId, articleId, content, authorId, status,
                "{\"review_nonce\":\"" + reviewNonce + "\"}");
    }

    /** 审核消息 JSON —— 字段名与 B 侧 {@code ArticleReviewMessage} 逐字一致。 */
    private String reviewJson(String articleId, String authorId, String contentPath, String nonce)
            throws Exception {
        ArticleSyncEvent.ArticleReviewMessage message = new ArticleSyncEvent.ArticleReviewMessage(
                articleId, authorId, contentPath, nonce);
        return mapper.writeValueAsString(message);
    }

    private long outboxCount(String articleId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM article_sync_outbox WHERE aggregate_id = ?",
                Long.class, articleId);
        return count == null ? -1L : count;
    }

    // ==================================================================
    // ① 四元组不齐 ⇒ 丢弃（Go :62-68 · B 侧 ArticleReviewConsumer.java:174-179）
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("🔴 真 Kafka：审核四元组任一不匹配 ⇒ 消费者丢弃该消息（不写 outbox、不改文章行、位点照常推进）")
    void staleReviewTupleIsDroppedOnRealKafka() throws Exception {
        String good = "902001001";
        String draft = "902001002";
        String contentGood = CONTENT_PREFIX + good + ".md";
        String contentDraft = CONTENT_PREFIX + draft + ".md";

        // A：状态正确、内容/作者/nonce 都能对上 —— 供「破坏消息」的三条用例用
        seedArticle(good, ArticleStatus.REVIEWING_VALUE, contentGood, AUTHOR, "nonce-1");
        // B：状态不是 REVIEWING —— 供「破坏状态」的一条用例用
        seedArticle(draft, ArticleStatus.DRAFT_VALUE, contentDraft, AUTHOR, "nonce-2");

        long offsetBefore = committedOffset(GROUP_REVIEW, TOPIC_REVIEW);

        // 四条消息：每条恰好破坏四元组里的一个分量
        // ① 破坏 content（消息里的 content_path 与 article.content 不一致）
        publish(TOPIC_REVIEW, good, reviewJson(good, AUTHOR, CONTENT_PREFIX + "OTHER.md", "nonce-1"));
        // ② 破坏 author（消息里的 author_id 与 article.author_id 不一致）
        publish(TOPIC_REVIEW, good, reviewJson(good, "902999999", contentGood, "nonce-1"));
        // ③ 破坏 nonce（消息里的 review_nonce 与 ext_info.review_nonce 不一致）
        publish(TOPIC_REVIEW, good, reviewJson(good, AUTHOR, contentGood, "STALE-NONCE"));
        // ④ 破坏 status（文章本身不在审核中 ⇒ 四元组第一项就不成立）
        publish(TOPIC_REVIEW, draft, reviewJson(draft, AUTHOR, contentDraft, "nonce-2"));

        // 🔴 场景有效性断言①：消费者**真的收到了这四条消息**并逐条判了四元组
        awaitLog("article review skipped because source or status changed", Duration.ofSeconds(60));
        awaitCount("article review skipped because source or status changed", 4, Duration.ofSeconds(60));

        // 🔴 场景有效性断言②：**没有一条走到内容审核分支**
        //   （走到审核分支 ⇒ 说明四元组没拦住 ⇒ 判据失效）
        assertEquals(0L, countLog("content security client not initialized"),
                "四元组不齐的消息不该走到内容审核分支；实际日志 = " + logMessages());

        // 🔴 场景有效性断言③：消费组的位点**推进了 4 条** ⇒ 真 broker 上真消费了
        awaitOffsetAdvance(offsetBefore, 4, Duration.ofSeconds(60));

        // ── 真正的判据：什么都没被处理 ──
        assertEquals(0L, outboxCount(good), "四元组不齐 ⇒ 绝不能写同步 outbox");
        assertEquals(0L, outboxCount(draft), "状态不对 ⇒ 绝不能写同步 outbox");

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, good);
        assertEquals(ArticleStatus.REVIEWING_VALUE, status, "文章状态不应被改");
        String extInfo = jdbcTemplate.queryForObject(
                "SELECT ext_info::text FROM article WHERE id = ?", String.class, good);
        assertNotNull(extInfo);
        assertTrue(extInfo.contains("nonce-1"), "ext_info 不应被改写：" + extInfo);
        assertTrue(!extInfo.contains("publish_stage"),
                "四元组不齐 ⇒ 不得推进发布阶段状态机：" + extInfo);
    }

    // ==================================================================
    // ② 内容审核恒 fail-loud ⇒ 恒不写 outbox（已签字偏离，不是缺陷）
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("🔴 真 Kafka：内容审核恒 fail-loud（security 域不在迁移范围）⇒ 消费者恒不写 outbox，"
            + "失败消息只投递一次（Go kq ForceCommit=true 语义）")
    void contentAuditFailLoudNeverWritesOutbox() throws Exception {
        String articleId = "902001010";
        String content = CONTENT_PREFIX + articleId + ".md";
        seedArticle(articleId, ArticleStatus.REVIEWING_VALUE, content, AUTHOR, "nonce-10");

        long offsetBefore = committedOffset(GROUP_REVIEW, TOPIC_REVIEW);

        // 四元组齐全 ⇒ 消费者会一路走到「内容审核」这一步
        publish(TOPIC_REVIEW, articleId, reviewJson(articleId, AUTHOR, content, "nonce-10"));

        // 🔴 场景有效性断言①：**生产装配的**兜底审核实现真的抛了
        //   （文案逐字取自 B 侧 UnavailableContentSecurityClient.java:71-77）
        awaitLog("content security client not initialized", Duration.ofSeconds(60));

        // 🔴 场景有效性断言②：确实**读到了正文**（即真的越过了 MinIO 分支、走到了审核分支）
        verify(articleObjectStorageClient, atLeastOnce()).readString(content);

        // 🔴 场景有效性断言③：错误处理器按 Go 的 ForceCommit=true 语义**丢弃**了这条消息
        awaitLog("offset committed as in Go kq (ForceCommit=true)", Duration.ofSeconds(30));

        // 🔴 场景有效性断言④：位点推进 ⇒ 消息被消费并提交了位点（不是卡住）
        awaitOffsetAdvance(offsetBefore, 1, Duration.ofSeconds(60));

        // ── 真正的判据：恒不写 outbox ──
        assertEquals(0L, outboxCount(articleId),
                "内容审核恒 fail-loud ⇒ 消费者恒不能写同步 outbox"
                        + "（本仓不迁移 security 域 ⇒ 这是已签字偏离，不是缺陷）");

        // 文章行必须**原地不动**：状态仍是审核中、同步状态机没被推进
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, articleId);
        assertEquals(ArticleStatus.REVIEWING_VALUE, status,
                "审核不可用时文章必须停在 REVIEWING（绝不能被判成 REJECTED）");
        String extInfo = jdbcTemplate.queryForObject(
                "SELECT ext_info::text FROM article WHERE id = ?", String.class, articleId);
        assertNotNull(extInfo);
        assertTrue(!extInfo.contains("publish_stage"),
                "审核不可用时不得推进发布阶段状态机：" + extInfo);

        // ── 真正的判据：失败消息**不被进程内重投**（Go kq 没有重试循环）──
        long deliveries = countLog("content security client not initialized");
        assertEquals(1L, deliveries, "失败消息应当只被投递一次，实际 " + deliveries + " 次");
        sleep(5_000L);
        assertEquals(deliveries, countLog("content security client not initialized"),
                "失败消息被进程内重投了 ⇒ 与 Go kq/queue.go:230-242（无重试循环 + 照样提交位点）不同构");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void awaitCount(String needle, long expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (countLog(needle) >= expected) {
                return;
            }
            sleep(100);
        }
        fail("等待日志条数超时（期望 " + expected + " 条，实际 " + countLog(needle) + " 条）：「"
                + needle + "」；本轮实际日志 = " + logMessages());
    }

    private void awaitOffsetAdvance(long before, long expectedAdvance, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long now = before;
        while (System.nanoTime() < deadline) {
            now = committedOffset(GROUP_REVIEW, TOPIC_REVIEW);
            if (now >= before + expectedAdvance) {
                return;
            }
            sleep(200);
        }
        fail("消费组 " + GROUP_REVIEW + " 的位点没有推进 " + expectedAdvance + " 条"
                + "（before=" + before + " now=" + now + "）⇒ 消息没被真消费");
    }
}
