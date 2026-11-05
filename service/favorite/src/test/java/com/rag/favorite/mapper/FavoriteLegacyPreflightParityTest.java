package com.rag.favorite.mapper;

import com.rag.favorite.FavoriteServiceApplication;
import com.rag.favorite.domain.entity.FavoriteItemEntity;
import com.rag.favorite.service.FavoriteFactEnvelopeFactory;
import com.rag.favorite.service.FavoriteLegacyPreflightService;
import com.rag.favorite.service.FavoriteLegacyPreflightService.Report;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 **legacy 只读预检的报表算法** —— 真 PostgreSQL 上的逐条判定。
 *
 * <h2>它验什么</h2>
 * <p>Go {@code service/favorite/rpc/internal/model/favorite_legacy_preflight.go:30-96} 的
 * {@code FavoriteLegacyPreflight}：
 * <ul>
 *   <li>哪些行算「缺 v1 断言」（{@code assertion.event_id IS NULL}）；</li>
 *   <li>逐行比 marker 的 <b>12 项</b>，分成 {@code approved_legacy} 与 {@code blocked}；</li>
 *   <li>「孤儿撤回」计数（{@code aggregate_version=2} 但没有 {@code aggregate_version=1}）；</li>
 *   <li>{@code Clear()} 的<b>四条</b>判据。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么必须是真 PG</h2>
 * <p>本类要验的是 <b>SQL 的 LEFT JOIN 语义</b>（marker 未命中 ⇒ 那 8 列全是 NULL）
 * 与 <b>两条查询的组合</b> ⇒ H2 / mock 都测不出来
 * ⇒ ⇒ 换掉数据库等于「把要验证的东西换没了」（假绿）。
 *
 * <h2>🔴 hash 这条怎么验才不循环</h2>
 * <p>「marker 的快照哈希必须等于重算出来的业务行哈希」这条判定，
 * 本类用<b>两个方向</b>把它钉死：
 * <ul>
 *   <li><b>哈希正确 ⇒ 判 approved_legacy</b>（{@link #markerWithCorrectSnapshotHashCountsAsApprovedLegacy}）；</li>
 *   <li><b>哈希错误 ⇒ 判 blocked</b>（{@link #markerWithWrongSnapshotHashIsBlocked}）
 *       ⇒ ⇒ 这条证明「哈希真的被比对了」，不是「哈希被忽略了」。</li>
 * </ul>
 * <p>⚠️ 哈希<b>函数本身</b>的正确性（与 Go 的 JCS 口径一致）属共享层
 * {@code CanonicalHash} 的范畴（台账 G-2）⇒ ⇒ 本类<b>不</b>声称验了它。
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一类测试</b> —— B 侧<b>没有</b> preflight 的任何测试
 *       （全仓 grep {@code FavoriteLegacyPreflightRunner} 无测试引用）
 *       ⇒ ⇒ 本类是 C 侧新增的证据。</li>
 *   <li><b>语义还一样吗</b>：不适用（本类不判定 B/C 等价，只钉 C 侧自己的判定口径）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/
 *       FavoriteLegacyPreflightRunner.java:157-231}（判定 + 快照哈希）；
 *       Go：{@code service/favorite/rpc/internal/model/favorite_legacy_preflight.go:30-96}。</li>
 * </ol>
 *
 * <h2>🔴🔴 {@code @SpringBootTest.properties} 必须与兄弟类<b>逐字相同</b>（2026-11-05 实测踩坑）</h2>
 * <p>本类的 {@code properties} 曾与另外 4 个 PG 测试类**差两行**
 * （{@code grpc.client.*.address} 的端口 + {@code access-secret} 的值）
 * ⇒ ⇒ Spring TestContext 的缓存键（{@code MergedContextConfiguration}）**不同**
 * ⇒ ⇒ 框架**另起了一个 Spring 上下文** ⇒ ⇒ 但 {@link FavoritePgParityTestBase} 的
 * {@code GRPC_PORT} 是<b>基类的静态常量</b>（全子类共享<b>同一个</b>端口）
 * ⇒ ⇒ 两个上下文抢同一个 gRPC 端口 ⇒ <b>EADDRINUSE</b>
 * ⇒ ⇒ 第二个上下文 refresh 失败 ⇒ ⇒ Spring 的
 * {@code ApplicationContext failure threshold (1) exceeded} 把<b>后续所有</b>
 * 上下文加载一并跳过 ⇒ ⇒ 表现为「4 个 PG 测试类共 24 个用例全红」，
 * <b>但报错栈里没有 PG、没有 schema、没有端口冲突的字样</b> ⇒ ⇒ 极像「PG 坏了 / schema 漂了」。
 * <p>⇒ ⇒ 本类的 {@code properties} 现在与 {@code FavoriteDdlParityTest} /
 * {@code FavoriteSchemaParityTest} / {@code FavoriteFolderItemTimeColumnTest} /
 * {@code FavoriteOutboxTimeColumnTest} <b>逐字相同</b> ⇒ ⇒ 5 个类共用<b>同一个</b>上下文。
 * <p>⚠️ <b>守门</b>：{@code mapper/FavoritePgTestContextSharingTest} 会反射扫描
 * {@code mapper} 包下所有 {@code FavoritePgParityTestBase} 子类
 * ⇒ 断言它们的 {@code @SpringBootTest.properties} <b>集合相同</b>
 * ⇒ ⇒ 下次有人改了一行 ⇒ <b>测试直接红</b>，而不是变成「偶发 24 红」。
 */
@SpringBootTest(
        classes = FavoriteServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 🔴 这 6 行必须与 FavoritePgParityTestBase 的其它子类逐字相同（见类 javadoc）。
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:1",
                "grpc.client.article-rpc.address=static://127.0.0.1:1",
                "favorite-api.user-auth.access-secret=rag-v1-favorite-parity-test-secret",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN"
        })
class FavoriteLegacyPreflightParityTest extends FavoritePgParityTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FavoriteLegacyPreflightService preflightService;

    @Autowired
    private FavoriteFactEnvelopeFactory envelopeFactory;

    /**
     * 🔴 用例之间必须清库。
     *
     * <p>为什么用 TRUNCATE 而不是 DELETE：{@code favorite_legacy_fact_marker} 上有
     * {@code favorite_legacy_marker_immutable} BEFORE UPDATE OR DELETE 触发器 ⇒ DELETE 被它拒绝；
     * TRUNCATE 不触发行级 DELETE 触发器。
     */
    @BeforeEach
    void resetTables() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE favorite_folder, favorite_item,
                                 favorite_fact_outbox, favorite_legacy_fact_marker
                CASCADE
                """);
    }

    // ══════════════════════ ① 全都有断言 ⇒ clear ══════════════════════

    @Test
    @DisplayName("所有收藏都有 v1 断言 ⇒ missing=0 · approved=0 · blocked=0 · orphan=0 ⇒ clear")
    void allRowsHaveAssertionsReportIsClear() {
        insertItem(7001L, 1001L, 9001L, "post", "t-1", null);
        insertAssertion(7001L);

        Report report = preflightService.preflight();

        assertEquals(0L, report.missingAssert());
        assertEquals(0L, report.approvedLegacy());
        assertEquals(0L, report.blocked());
        assertEquals(0L, report.orphanRetracts());
        assertTrue(report.clear(), "四条判据全满足 ⇒ clear（Go favorite_legacy_preflight.go:22-25）");
    }

    // ══════════════════════ ② 无 marker ⇒ blocked ══════════════════════

    @Test
    @DisplayName("缺断言且没有 marker ⇒ missing++ · blocked++（marker 未命中 ⇒ 8 列全 NULL）")
    void missingRowWithoutMarkerIsBlocked() {
        insertItem(7002L, 1001L, 9001L, "post", "t-2", null);

        Report report = preflightService.preflight();

        assertEquals(1L, report.missingAssert());
        assertEquals(0L, report.approvedLegacy());
        assertEquals(1L, report.blocked());
        assertFalse(report.clear());
    }

    // ══════════════════════ ③ marker 完全对得上 ⇒ approved_legacy ══════════════════════

    @Test
    @DisplayName("缺断言但 marker 逐字段对得上（含正确快照哈希）⇒ missing++ · approved_legacy++")
    void markerWithCorrectSnapshotHashCountsAsApprovedLegacy() {
        insertItem(7003L, 1001L, 9001L, "post", "t-3", null);
        insertMarker(7003L, 1001L, 9001L, "post", "t-3", null,
                snapshotHash(7003L, 1001L, 9001L, "post", "t-3", null), "approval-7003");

        Report report = preflightService.preflight();

        assertEquals(1L, report.missingAssert());
        assertEquals(1L, report.approvedLegacy());
        assertEquals(0L, report.blocked());
        // Go favorite_legacy_preflight.go:22-25：Clear() 只看
        // 「missing_assert == approved_legacy 且 blocked==0 且 orphan_retracts==0」
        // ⇒ ⇒ 「有缺断言的行，但每一行都有逐字段签字的 legacy 标记」= **已裁决** ⇒ clear
        assertTrue(report.clear(),
                "missing_assert == approved_legacy（1 == 1）且 blocked/orphan 都是 0 ⇒ clear（Go :22-25）");
    }

    // ══════════════════════ ④ marker 的哈希错 ⇒ blocked ══════════════════════

    @Test
    @DisplayName("marker 的快照哈希不对 ⇒ blocked（证明哈希真的被比对了，不是被忽略）")
    void markerWithWrongSnapshotHashIsBlocked() {
        insertItem(7004L, 1001L, 9001L, "post", "t-4", null);
        insertMarker(7004L, 1001L, 9001L, "post", "t-4", null,
                "0000000000000000000000000000000000000000000000000000000000000000", "approval-7004");

        Report report = preflightService.preflight();

        assertEquals(1L, report.missingAssert());
        assertEquals(0L, report.approvedLegacy());
        assertEquals(1L, report.blocked());
    }

    // ══════════════════════ ⑤ marker 的字段对不上 ⇒ blocked ══════════════════════

    @Test
    @DisplayName("marker 的 user_id 与收藏行不符 ⇒ blocked（逐字段相等这条在跑）")
    void markerWithMismatchedUserIdIsBlocked() {
        insertItem(7005L, 1001L, 9001L, "post", "t-5", null);
        // marker 的 user_id 故意写成 2002 ⇒ 其余字段与哈希都按收藏行 7005 算
        insertMarker(7005L, 2002L, 9001L, "post", "t-5", null,
                snapshotHash(7005L, 1001L, 9001L, "post", "t-5", null), "approval-7005");

        Report report = preflightService.preflight();

        assertEquals(1L, report.missingAssert());
        assertEquals(0L, report.approvedLegacy());
        assertEquals(1L, report.blocked());
    }

    // ══════════════════════ ⑥ revision 的「有无」不匹配 ⇒ blocked ══════════════════════

    @Test
    @DisplayName("收藏行有 target_revision 而 marker 没有 ⇒ blocked（「一侧有另一侧无」也是不匹配）")
    void markerRevisionPresenceMismatchIsBlocked() {
        insertItem(7006L, 1001L, 9001L, "article", "t-6", "rev-1");
        insertMarker(7006L, 1001L, 9001L, "article", "t-6", null,
                snapshotHash(7006L, 1001L, 9001L, "article", "t-6", "rev-1"), "approval-7006");

        Report report = preflightService.preflight();

        assertEquals(1L, report.missingAssert());
        assertEquals(0L, report.approvedLegacy());
        assertEquals(1L, report.blocked());
    }

    // ══════════════════════ ⑦ 孤儿撤回 ══════════════════════

    @Test
    @DisplayName("有 aggregate_version=2 但没有 aggregate_version=1 ⇒ orphan_retracts++（且不 clear）")
    void orphanRetractIsCounted() {
        insertItem(7007L, 1001L, 9001L, "post", "t-7", null);
        insertRetract(7007L);

        Report report = preflightService.preflight();

        assertEquals(1L, report.missingAssert());
        assertEquals(1L, report.blocked());
        assertEquals(1L, report.orphanRetracts(), "孤儿撤回必须被计数（Go :86-91）");
        assertFalse(report.clear(), "orphan_retracts > 0 ⇒ 不 clear（clear 的四条判据之一）");
    }

    // ══════════════════════ 种子辅助 ══════════════════════

    private void insertItem(long favoriteId, long userId, long folderId,
                             String targetType, String targetId, String targetRevision) {
        jdbcTemplate.update("""
                INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type,
                                           target_revision, title, cover, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, favoriteId, folderId, userId, targetId, targetType, targetRevision, "t", "c");
    }

    /**
     * v1 断言行（{@code aggregate_version = 1}）⇒ 该收藏**不算**缺断言。
     *
     * <p>🔴 {@code created_at} / {@code updated_at} <b>必须显式给</b>：
     * V7 把这两个列的 DEFAULT <b>删掉</b>了（对齐 Go 的 AutoMigrate 产物 = 无 DEFAULT
     * ⇒ 两侧同样硬失败）⇒ ⇒ 不给就是 NOT NULL 违约。
     */
    private void insertAssertion(long favoriteId) {
        jdbcTemplate.update("""
                INSERT INTO favorite_fact_outbox (event_id, favorite_id, aggregate_version, payload,
                                                  created_at, updated_at)
                VALUES (?, ?, 1, '{}'::jsonb, now(), now())
                """, "evt-assert-" + favoriteId, favoriteId);
    }

    /** v2 撤回行（{@code aggregate_version = 2}）⇒ 没有对应 v1 时算孤儿撤回。 */
    private void insertRetract(long favoriteId) {
        jdbcTemplate.update("""
                INSERT INTO favorite_fact_outbox (event_id, favorite_id, aggregate_version, payload,
                                                  created_at, updated_at)
                VALUES (?, ?, 2, '{}'::jsonb, now(), now())
                """, "evt-retract-" + favoriteId, favoriteId);
    }

    private void insertMarker(long favoriteId, long userId, long folderId,
                              String targetType, String targetId, String targetRevision,
                              String snapshotSha256, String approvalRef) {
        jdbcTemplate.update("""
                INSERT INTO favorite_legacy_fact_marker (favorite_id, user_id, folder_id, target_type,
                                                         target_id, target_revision,
                                                         source_snapshot_sha256, approval_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, favoriteId, userId, folderId, targetType, targetId, targetRevision,
                snapshotSha256, approvalRef);
    }

    /** 用生产组件重算业务行快照哈希（与被测的预检**同一个**组件 ⇒ 见类注释「hash 这条怎么验」）。 */
    private String snapshotHash(long favoriteId, long userId, long folderId,
                                 String targetType, String targetId, String targetRevision) {
        FavoriteItemEntity item = new FavoriteItemEntity();
        item.setFavoriteId(favoriteId);
        item.setUserId(userId);
        item.setFolderId(folderId);
        item.setTargetType(targetType);
        item.setTargetId(targetId);
        item.setTargetRevision(targetRevision);
        return envelopeFactory.legacySnapshotHash(item);
    }
}
