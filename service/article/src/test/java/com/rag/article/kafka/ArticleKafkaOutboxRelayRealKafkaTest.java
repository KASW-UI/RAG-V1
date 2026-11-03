package com.rag.article.kafka;

import com.rag.article.domain.entity.ArticleSyncOutboxEntity;
import com.rag.article.messaging.ArticleSyncOutboxRelay;
import com.rag.article.support.KafkaTestBase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <p><b>真 Kafka 上的「同步发件箱中继」投递语义测试</b> ——
 * {@code @Scheduled} 真生效、payload 原文推送、key 取 {@code aggregate_id}、
 * 以及 <b>「超过 maxRetry 的事件被静默搁置」这条 B 侧既有缺陷</b>。
 *
 * <h2>🔴 为什么「静默搁置」这条必须被照抄（而不是「顺手修掉」）</h2>
 * <p>B 侧 {@code ArticleSyncOutboxRelay.java:123-155}（Go {@code article.go:57-89}）的捞取条件是
 * <pre>
 * status IN (0, 2) AND retry_count &lt; maxRetry      ← maxRetry 来自配置（yaml = 10）
 * ORDER BY created_at ASC LIMIT N
 * </pre>
 * ⇒ <b>retry_count 达到上限的事件被<b>永久搁置</b>：不进死信、不告警、状态停在 0/2。</b>
 * <p>⚠️ AGENTS.md §1.0c：<b>B 侧是唯一 oracle</b> ⇒ B 侧已有的缺陷<b>一律继承</b>
 * （登记为签字偏离），<b>不得在本仓「顺手修好」</b> —— 那是「新增行为」，
 * 会让运维侧的告警规则与历史数据的行为对不上。
 * <p>⇒ ⇒ 本测试<b>把这条缺陷钉成断言</b>：改了它，本用例立刻红。
 *
 * <h2>被测对象是<b>生产装配本身</b></h2>
 * <ul>
 *   <li>中继：生产的 {@code ArticleSyncOutboxRelay}（含 {@code @Scheduled} 的 SpEL 间隔）；</li>
 *   <li>发件箱取数：生产的 {@code ArticleSyncOutboxMapper#fetchPending}（SQL 文本逐字未改）；</li>
 *   <li>投递：生产的 {@code KafkaTemplate}（真 broker）。</li>
 * </ul>
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>捞取条件 {@code status IN (0,2) AND retry_count &lt; maxRetry}</td>
 *       <td>{@code mq/ArticleSyncOutboxRelay.java:127}（B 侧）
 *           ＋ Go {@code article.go:57-60}</td>
 *       <td>{@code mapper/ArticleSyncOutboxMapper.java:63-70}</td></tr>
 *   <tr><td>推送内容 = payload 原文（不重新序列化）</td>
 *       <td>{@code mq/ArticleSyncOutboxRelay.java:134-140}（B 侧）</td>
 *       <td>{@code messaging/ArticleSyncOutboxRelay.java:180-186}</td></tr>
 *   <tr><td>key = {@code aggregate_id}（同文章事件分区内有序）</td>
 *       <td>{@code mq/ArticleSyncOutboxRelay.java:136-139}（B 侧）</td>
 *       <td>{@code messaging/ArticleSyncOutboxRelay.java:182-185}</td></tr>
 *   <tr><td>超 maxRetry ⇒ 静默搁置（不进死信、不告警）</td>
 *       <td>{@code mq/ArticleSyncOutboxRelay.java:27-29}（B 侧 javadoc 转录 Go 语义）</td>
 *       <td>{@code messaging/ArticleSyncOutboxRelay.java:53-55}</td></tr>
 *   <tr><td>成功 ⇒ {@code markSent}（status=1）</td>
 *       <td>{@code mq/ArticleSyncOutboxRelay.java:141}（B 侧）</td>
 *       <td>{@code messaging/ArticleSyncOutboxRelay.java:187}</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code fetchPending} 的 {@code retry_count &lt; maxRetry} 条件去掉
 *       ⇒ 用例 <b>立刻红</b>（超限事件被投递）；</li>
 *   <li>把「超限事件」改成进死信 / 标告警 ⇒ 用例 <b>立刻红</b>（状态不再停在 0/2）；</li>
 *   <li>把推送的 value 改成「重新序列化 event 对象」⇒ 用例 <b>立刻红</b>
 *       （字节不再与冻结 payload 一致 ⇒ 发布回调的「冻结输入比对」失去前提）；</li>
 *   <li>把 key 从 {@code aggregate_id} 改成 {@code event_id} 或 {@code null}
 *       ⇒ 用例 <b>立刻红</b>（分区有序性被破坏）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>单例 PG 容器共用 ⇒ 本类用专属事件 id 段 {@code evt-902003xxx}。
 */
class ArticleKafkaOutboxRelayRealKafkaTest extends KafkaTestBase {

    /** 生产配置里的 maxRetry（application.yml: {@code article-rpc.article-sync-outbox.max-retry}）。 */
    private static final int MAX_RETRY = 10;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ArticleSyncOutboxRelay relay;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
    }

    // ==================================================================
    // ① @Scheduled 真生效 + 原文推送 + key 取 aggregate_id
    // ② 超 maxRetry ⇒ 静默搁置（同一轮里的 A/B 对照 ⇒ 自带场景有效性）
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Kafka：relay 的 @Scheduled 真在跑 ⇒ 正常事件被投递（payload 原文 + key=aggregate_id）"
            + "；而 retry_count 达到 maxRetry 的事件被**静默搁置**（不投递、不改状态、不告警）")
    void relayDeliversPendingEventsAndSilentlyShelvesOverLimitOnes() {
        String normalEventId = "evt-902003001";
        String normalAggregate = "902003001";
        String overLimitEventId = "evt-902003002";
        String overLimitAggregate = "902003002";

        String normalPayload = frozenPayload(normalEventId, normalAggregate);
        String overLimitPayload = frozenPayload(overLimitEventId, overLimitAggregate);

        insertOutboxRow(normalEventId, normalAggregate, normalPayload, 0);
        insertOutboxRow(overLimitEventId, overLimitAggregate, overLimitPayload, MAX_RETRY);

        // 🔴 场景有效性断言①：**不调 relayOnce()** ⇒ 只有 @Scheduled 真的在跑，
        //    正常事件才会被投递 ⇒ 这条断言本身就是「调度机制真生效」的证据。
        awaitStatus(normalEventId, 1, Duration.ofSeconds(60));

        // ── 判据①：正常事件被投递，且推的是 payload 原文、key 是 aggregate_id ──
        List<ConsumerRecord<String, String>> delivered =
                drain(TOPIC_SYNC_EVENTS, Duration.ofSeconds(10));

        ConsumerRecord<String, String> normal = delivered.stream()
                .filter(r -> r.value() != null && r.value().contains(normalEventId))
                .findFirst()
                .orElse(null);
        assertNotEquals(null, normal, "正常事件必须被投递到 " + TOPIC_SYNC_EVENTS
                + "；实际收到的消息 = " + delivered.stream().map(ConsumerRecord::value).toList());

        assertEquals(normalAggregate, normal.key(),
                "⚠️ key 必须是 aggregate_id（不是 event_id、不是 null）⇒ 同文章事件分区内有序");

        String storedPayload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM article_sync_outbox WHERE event_id = ?",
                String.class, normalEventId);
        assertEquals(storedPayload, normal.value(),
                "⚠️ 推送的必须是 payload 原文（不重新序列化）"
                        + " —— 这是发布回调「冻结输入比对」能成立的前提");

        // ── 判据②：超 maxRetry 的事件被**静默搁置** ──
        //   （不投递 ⇒ topic 上不得出现它）
        assertTrue(delivered.stream().noneMatch(r -> r.value() != null
                        && r.value().contains(overLimitEventId)),
                "⚠️ retry_count 达到 maxRetry 的事件**不得**被投递"
                        + "（B 侧既有缺陷：永久搁置、不进死信、不告警）；实际收到的消息 = "
                + delivered.stream().map(ConsumerRecord::value).toList());

        //   （不告警 ⇒ 状态与重试计数都不得被改）
        Integer overLimitStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM article_sync_outbox WHERE event_id = ?",
                Integer.class, overLimitEventId);
        Integer overLimitRetry = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM article_sync_outbox WHERE event_id = ?",
                Integer.class, overLimitEventId);
        assertEquals(0, overLimitStatus,
                "超限事件必须保持 status=0（既没被投递成功、也没被标记失败）");
        assertEquals(MAX_RETRY, overLimitRetry,
                "超限事件的 retry_count 不得再增长（它根本没被捞起来）");
        assertEquals("", jdbcTemplate.queryForObject(
                        "SELECT last_error FROM article_sync_outbox WHERE event_id = ?",
                        String.class, overLimitEventId),
                "超限事件不得留下 last_error（静默搁置 ⇒ 无告警痕迹）");
    }

    // ==================================================================
    // ② 显式调 relayOnce() 的投递路径（不依赖调度器 ⇒ 判据更聚焦）
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Kafka：显式 relayOnce() ⇒ 逐条独立投递、逐条 markSent（成功那条 status=1）")
    void explicitRelayOnceMarksSentAndPushesPayloadVerbatim() {
        String eventId = "evt-902003010";
        String aggregate = "902003010";
        String payload = frozenPayload(eventId, aggregate);
        insertOutboxRow(eventId, aggregate, payload, 0);

        relay.relayOnce();

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM article_sync_outbox WHERE event_id = ?", Integer.class, eventId);
        assertEquals(1, status, "推送成功 ⇒ 必须 markSent（status=1）");

        List<ConsumerRecord<String, String>> delivered =
                drain(TOPIC_SYNC_EVENTS, Duration.ofSeconds(10));
        ConsumerRecord<String, String> record = delivered.stream()
                .filter(r -> r.value() != null && r.value().contains(eventId))
                .findFirst()
                .orElse(null);
        assertNotEquals(null, record, "relayOnce() 必须真的把事件推到 Kafka");
        assertEquals(aggregate, record.key(), "key 必须是 aggregate_id");
        assertEquals(jdbcTemplate.queryForObject(
                        "SELECT payload::text FROM article_sync_outbox WHERE event_id = ?",
                        String.class, eventId),
                record.value(), "推送的必须是 payload 原文");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private void insertOutboxRow(String eventId, String aggregateId, String payload, int retryCount) {
        jdbcTemplate.update("""
                INSERT INTO article_sync_outbox
                    (event_id, event_key, event_type, aggregate_id, payload, status, retry_count,
                     last_error, created_at, updated_at)
                VALUES (?, ?, 'article_sync', ?, CAST(? AS jsonb), 0, ?, '', now(), now())
                """,
                eventId, aggregateId + ":upsert:" + eventId, aggregateId, payload, retryCount);
    }

    /** 一份「冻结的」同步事件 payload（形状与 {@code ArticleSyncEvent} 一致）。 */
    private static String frozenPayload(String eventId, String articleId) {
        return "{\"event_scope\":\"article_sync\",\"event_id\":\"" + eventId + "\","
                + "\"article_id\":\"" + articleId + "\",\"op\":\"upsert\","
                + "\"reason\":\"create\",\"author_id\":\"902000003\",\"status\":\"PUBLISHED\","
                + "\"version_ms\":1700000000000,\"title\":\"t\",\"markdown\":\"# body\"}";
    }

    private void awaitStatus(String eventId, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Integer status = null;
        while (System.nanoTime() < deadline) {
            status = jdbcTemplate.queryForObject(
                    "SELECT status FROM article_sync_outbox WHERE event_id = ?",
                    Integer.class, eventId);
            if (status != null && status == expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("等待 outbox 事件 " + eventId + " 的 status=" + expected + " 超时；实际 = " + status
                + " ⇒ relay 的 @Scheduled 没有真在跑");
    }
}
