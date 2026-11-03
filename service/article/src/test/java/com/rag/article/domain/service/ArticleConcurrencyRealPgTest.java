package com.rag.article.domain.service;

import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.support.ArticleRealPgTestBase;
import com.rag.proto.article.ArticleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：article 域写路径的<b>并发语义</b> —— 同一篇文章被「降级」与「删除」同时命中时
 * ① 是否无死锁、② 是否串行化、③ <b>锁序</b>到底是 article → publication 还是反过来。
 *
 * <h2>为什么这三条必须是真 PG + 真并发</h2>
 * <ul>
 *   <li>要验证的是<b>行锁的串行化语义</b>（{@code SELECT ... FOR UPDATE} 在两个事务之间的行为）
 *       —— H2 与 mock 数据层都测不到；</li>
 *   <li>要验证的是<b>死锁是否发生</b> —— 单线程根本造不出来；</li>
 *   <li>要验证的是<b>指针版本单调</b>（{@code article_publication.pointer_version}）——
 *       它靠 {@code FOR UPDATE} + {@code UNIQUE(aggregate_id, aggregate_version)} 一起保证。</li>
 * </ul>
 *
 * <h2>🔴 锁序那条为什么必须<b>单独</b>做（不能靠结果反推）</h2>
 * <p>CASE-R12 测的是「两个事务的<b>最终结果</b>」，而<b>锁序</b>是另一维：
 * 只要两条路径的锁序<b>彼此一致</b>，结果就一样 ⇒ CASE-R12 <b>证伪不了锁序</b>。
 * ⇒ 必须换一条<b>能唯一判定</b>的探针：<b>同时扣住两把锁</b> ⇒ 被测代码必然卡在
 * <b>它最先要的那把锁</b>上 ⇒ 被阻塞后端正在执行的 SQL <b>唯一确定</b>它的第一把锁。
 *
 * <h3>本实现比 B 侧更精确的一处</h3>
 * <p>B 侧用 {@code application_name = 'article-lockorder'} 过滤被阻塞会话
 * （靠给 JDBC URL 加 {@code ApplicationName=} 实现）。
 * ⇒ C 侧<b>不改共享的 {@code support/TestPostgres}</b>（那个类的 {@code jdbcUrl()}
 * 被 {@code ArticleSchemaGateFailFastTest} 引用，改它会波及别处）
 * ⇒ 改用 <b>更精确</b>的定位：{@code pg_blocking_pids(pid)}
 * —— 直接问 PG「<b>谁在挡我</b>」，于是「被我的 holder 连接挡住」这件事
 * <b>唯一确定</b>那个被阻塞的后端 ⇒ <b>不依赖 application_name</b>，也不会误抓别的会话。
 *
 * <h2>B 出处（逐条）</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleTransactionWiringTest.java}：
 *       <ul>
 *         <li>{@code :966-1045} {@code concurrentRetractAndDeleteOnSameArticleSerializesWithoutDeadlock}
 *             （CASE-R12）⇒ ①</li>
 *         <li>{@code :1090-1141} {@code lockOrderIsArticleThenPublication} ⇒ ②</li>
 *       </ul></li>
 *   <li>Go 侧：{@code update_article_logic.go:216-221}（降级 RunInTx）·
 *       {@code delete_article_logic.go:84-96}（删除 RunInTx）·
 *       {@code community_article_event.go:112-114}（{@code FOR UPDATE publication}）·
 *       {@code community_article_event.go:123-125}（指针状态冲突判据）·
 *       {@code community_revision.go:84-85}（{@code Unscoped + FOR UPDATE article}）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code retractAndUpdate} 里的 {@code selectByIdForUpdate} 拿掉 ⇒
 *       ② <b>立刻红</b>（被阻塞的 SQL 会变成 publication 那把锁）；</li>
 *   <li>把 {@code retractAndDelete} 的取锁顺序反过来 ⇒ ② <b>立刻红</b>；</li>
 *   <li>把 {@code retractPublicationTx} 的冲突判据去掉（不抛异常）⇒
 *       ① <b>立刻红</b>（两个事务都会「成功」⇒ 指针版本会到 3）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类行 id 前缀 {@code 910001}；作者号 {@code 910000001}。
 */
class ArticleConcurrencyRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "910000001";

    @Autowired
    private ArticleAtomicService atomicService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDomainTables() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
    }

    // ==================================================================
    // ① CASE-R12 —— 并发降级 + 并发删除
    // ==================================================================

    @Test
    @DisplayName("CASE-R12: 同一篇文章并发「降级」与「删除」⇒ 无死锁；指针版本单调到 2；先到者赢、后到者报 pointer conflicts")
    void concurrentRetractAndDeleteOnSameArticleSerializesWithoutDeadlock() throws Exception {
        String articleId = "910001001";
        seedPublishedArticleWithPointer(articleId);

        // ⚠️ 实体在**主线程**里读（与生产一致：调用点先读、后调 atomicService），
        //    两个线程各拿一份实例 —— 不要在被测线程里用带 deleted_at IS NULL 过滤的读，
        //    并发窗口里读到 null 那是测试代码的竞态，不是被测行为。
        ArticleEntity forDowngrade = articleMapper.selectById(articleId);
        forDowngrade.setStatus(ArticleStatus.DRAFT_VALUE);
        Map<String, String> ext = ArticleSyncEvent.ensureExtInfo(forDowngrade.getExtInfo());
        ArticleSyncEvent.setSyncState(ext, "source_only", "pending",
                ArticleSyncEvent.REASON_STATUS_CHANGE, "evt-down", 2000L, "");
        forDowngrade.setExtInfo(ext);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> down = pool.submit(() -> {
                start.await();
                try {
                    atomicService.retractAndUpdate(forDowngrade, "evt-down", 2000L,
                            ArticleSyncEvent.REASON_STATUS_CHANGE);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Throwable> del = pool.submit(() -> {
                start.await();
                try {
                    atomicService.retractAndDelete(articleId, "evt-del", 2000L);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            start.countDown();
            // ⚠️ 60s 超时是**死锁的哨兵**：真死锁时两个事务互相等待，future 永不完成
            //    ⇒ get() 抛 TimeoutException ⇒ 用例失败（而不是永远挂着）。
            Throwable tDown = down.get(60, TimeUnit.SECONDS);
            Throwable tDel = del.get(60, TimeUnit.SECONDS);

            // ① 无死锁：两个 future 都在超时内完成，且失败也不是 PG 的死锁（SQLSTATE 40P01）
            assertFalse(isDeadlock(tDown), "降级路径出现死锁：" + rootMessage(tDown));
            assertFalse(isDeadlock(tDel), "删除路径出现死锁：" + rootMessage(tDel));

            // ② 恰好一个成功：后到者撞 Go 的「pointer conflicts」
            boolean downOk = (tDown == null);
            boolean delOk = (tDel == null);
            assertTrue(downOk ^ delOk,
                    "两个事务必须串行化 ⇒ 恰好一个成功（down=" + rootMessage(tDown)
                            + " / del=" + rootMessage(tDel) + "）");
            Throwable loser = downOk ? tDel : tDown;
            assertTrue(rootMessage(loser).contains(
                            "community publication pointer conflicts with published article"),
                    "后到者必须撞 Go 的判据（community_article_event.go:123-125），实际："
                            + rootMessage(loser));

            // ③ 指针版本单调到 2（不是 3）—— 后到者不得再推一次
            assertEquals(2L, jdbcTemplate.queryForObject(
                    "SELECT pointer_version FROM article_publication WHERE article_id = ?",
                    Long.class, articleId), "指针版本必须单调到 2（后到者不得再推一次）");
            assertEquals("retracted", jdbcTemplate.queryForObject(
                    "SELECT state FROM article_publication WHERE article_id = ?",
                    String.class, articleId), "指针状态 = retracted（Go community_article_event.go:134）");

            // ④ 域事件只有 1 条，aggregate_version = 指针版本（Go :125）
            assertEquals(1, countDomainEvents(articleId),
                    "只有成功的那次能写域事件（后到者在写之前就抛了）");
            assertEquals(2L, jdbcTemplate.queryForObject(
                    "SELECT aggregate_version FROM article_domain_outbox WHERE aggregate_id = ?",
                    Long.class, articleId), "域事件的 aggregate_version = 指针版本（Go :125）");
            assertEquals("community.article.retracted", jdbcTemplate.queryForObject(
                    "SELECT event_type FROM article_domain_outbox WHERE aggregate_id = ?",
                    String.class, articleId), "域事件的 event_type = community.article.retracted（Go :91）");
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // ② 锁序实测 —— 同时扣住两把锁，看它先要哪把
    // ==================================================================

    @Test
    @DisplayName("🔴 锁序实测 —— 同时扣住 article 与 publication 两把锁 ⇒ 被阻塞的必须是 article 那把（锁序 = article → publication）")
    void lockOrderIsArticleThenPublication() throws Exception {
        String articleId = "910001002";
        seedPublishedArticleWithPointer(articleId);

        // ⚠️ 必须是**另一条连接**：同一个连接上的事务锁不住自己。
        Connection holder = jdbcTemplate.getDataSource().getConnection();
        holder.setAutoCommit(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            int holderPid = pidOf(holder);
            holdRowLock(holder, "SELECT * FROM article WHERE id = ? FOR UPDATE", articleId);
            holdRowLock(holder, "SELECT * FROM article_publication WHERE article_id = ? FOR UPDATE", articleId);

            ArticleEntity forDowngrade = articleMapper.selectById(articleId);
            forDowngrade.setStatus(ArticleStatus.DRAFT_VALUE);
            Map<String, String> ext = ArticleSyncEvent.ensureExtInfo(forDowngrade.getExtInfo());
            ArticleSyncEvent.setSyncState(ext, "source_only", "pending",
                    ArticleSyncEvent.REASON_STATUS_CHANGE, "evt-lo", 2000L, "");
            forDowngrade.setExtInfo(ext);

            Future<Throwable> f = pool.submit(() -> {
                try {
                    atomicService.retractAndUpdate(forDowngrade, "evt-lo", 2000L,
                            ArticleSyncEvent.REASON_STATUS_CHANGE);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            String blocked = awaitBlockedBy(holderPid, 30_000L);
            assertNotNull(blocked,
                    "没观测到被阻塞的 FOR UPDATE ⇒ 探针本身失效（不是被测行为的问题）");
            // ⚠️ 判别子串用 "FROM article WHERE"（**带 WHERE**）——
            //    "FROM article_publication WHERE" 里**不含**它（article_publication 是下划线连接），
            //    否则两条 SQL 会互相误判。
            assertTrue(blocked.contains("FROM article WHERE"),
                    "锁序必须是 article → publication ⇒ 它应该先卡在 **article** 的 FOR UPDATE 上；"
                            + "实际被阻塞的语句：" + blocked);
            assertFalse(blocked.contains("FROM article_publication"),
                    "若卡在 publication 上 ⇒ 第一把锁是 publication ⇒ 与实现的锁序不符：" + blocked);

            // 释放扣着的锁，让被测线程收尾（它会撞上 pointer conflicts，符合预期）
            holder.rollback();
            Throwable outcome = f.get(60, TimeUnit.SECONDS);
            assertNull(outcome, "释放锁后它本该成功（此时两把锁都没被扣着了），实际：" + rootMessage(outcome));
        } finally {
            try {
                holder.rollback();
            } catch (Exception ignored) {
                // holder 可能已被关闭
            }
            holder.close();
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 在给定连接上扣住一行（{@code FOR UPDATE}），事务保持打开。 */
    private static void holdRowLock(Connection conn, String sql, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                // 必须把结果读完，否则 PG 会等客户端消费 ⇒ 拿不到锁
                while (rs.next()) {
                    // no-op
                }
            }
        }
    }

    /** 取当前连接的后端 PID（{@code pg_backend_pid()}）。 */
    private static int pidOf(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rs.next(), "pg_backend_pid() 必须返回一行");
            return rs.getInt(1);
        }
    }

    /**
     * 等到某个后端卡在 {@code FOR UPDATE} 上，返回它<b>正在执行的 SQL</b>。
     *
     * <p><b>定位方式比 B 侧更精确</b>：不用 {@code application_name} 过滤，而是用
     * {@code pg_blocking_pids(pid)} —— PG 直接回答「谁在挡这个会话」。
     * 于是「<b>被 holder 连接挡住</b>」这件事唯一确定那个被阻塞的后端。
     */
    private String awaitBlockedBy(int holderPid, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> blocked = jdbcTemplate.queryForList(
                    "SELECT query FROM pg_stat_activity "
                            + "WHERE pid <> pg_backend_pid() "
                            + "AND wait_event_type = 'Lock' "
                            + "AND query ILIKE '%FOR UPDATE%' "
                            + "AND ? = ANY(pg_blocking_pids(pid))",
                    String.class, holderPid);
            for (String q : blocked) {
                if (q != null && !q.isBlank()) {
                    return q;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** 取异常链里每一层的消息拼串（Spring 会把底层异常包成好几层）。 */
    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append(" | ");
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return sb.toString();
    }

    /** 是否是 PG 的死锁（{@code 40P01 deadlock detected}）。 */
    private static boolean isDeadlock(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.sql.SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
            String m = cur.getMessage();
            if (m != null && m.toLowerCase().contains("deadlock")) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }

    private int countDomainEvents(String aggregateId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_domain_outbox WHERE aggregate_id = ?",
                Integer.class, aggregateId);
        return count == null ? 0 : count;
    }

    /**
     * 建一条<b>已发布</b>的文章 + 社区指针（{@code state='published'} / {@code pointer_version=1}）。
     *
     * <p>这是 retract 路径能真正推进指针的<b>前置条件</b>：
     * Go 的 {@code RetractPublicationTx} 在指针<b>缺失</b>时走 legacy 缺口分支（标记后正常返回），
     * 只有在 {@code state != "published"} 时才抛冲突 ⇒ 不铺这条指针，测的就不是同一条路径。
     */
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
}
