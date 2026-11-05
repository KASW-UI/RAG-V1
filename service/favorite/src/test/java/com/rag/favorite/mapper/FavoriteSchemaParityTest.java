package com.rag.favorite.mapper;

import com.rag.favorite.FavoriteServiceApplication;
import com.rag.favorite.support.RealContainerHostPort;
import com.rag.favorite.support.RealTomcatPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 真 PostgreSQL 上的 <b>schema 资产校验</b> —— 校 C 侧 Flyway 建出的 4 张表 +
 * 2 个不可变触发器 + 全部 CHECK / UNIQUE 约束 + 部分索引 + 列类型。
 *
 * <h2>B 出处</h2>
 * <p>B 侧测试：{@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/
 * FavoriteSchemaParityTest.java:1-365}（逐条移植，见每个用例的 {@code B 出处} 行内注释）。
 *
 * <h2>§3.2 三段式留痕（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>🔴 <b>禁 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 *           ⇒ B 侧 {@code FavoriteSchemaParityTest.java:46} 有该注解
 *           ⇒ C 侧<b>删掉</b> ⇒ Docker 不可用时本类<b>直接红</b>（不是静默跳过 = 假绿）。</li>
 *       <li>🔴 <b>容器改成「进程内单例 + 固定段自检」</b>：B 侧每个测试类各起一个容器
 *           并用 {@code RealContainerHostPort.bindPg(...)} 把宿主口钉死在域专用段
 *           （B 侧 {@code FavoriteSchemaParityTest.java:65-71}）
 *           ⇒ C 侧本类的容器由共享基类 {@link FavoritePgParityTestBase} 统一管理
 *           （1 个容器 + 1 个 Spring 上下文，4 个真 PG 测试类共用）。</li>
 *       <li>🔴 <b>断言里补了「每个事实带行号」</b>（AGENTS.md §3.3.3 强制三条 ①）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 13 条用例的<b>判据、口径、期望值逐条未改</b>
 *       （期望值全部沿用 B 侧：4 张表名、2 个触发器名、6 个 CHECK 语义、
 *       2 个唯一键语义、部分索引名、{@code target_id} 的 {@code character varying}、
 *       4 个 {@code timestamp with time zone} 列、{@code favorite_item} 无 {@code update_time}）。
 *       唯一被删掉的是「Docker 不可用就跳过」这一条<b>观测口径</b>（AGENTS.md 明令禁止）。</li>
 *   <li><b>B 出处</b>：见上（B 侧测试文件 + B 侧被测 DDL
 *       {@code service/favorite/favorite-rpc/src/main/resources/db/migration/V1..V7}）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么必须是真 PG（不能用 H2 顶替）</h2>
 * <p>本类要验的三类资产，H2 <b>一样都不支持</b>：
 * {@code BEFORE UPDATE OR DELETE} 的 plpgsql 触发器 + {@code IS DISTINCT FROM} ·
 * {@code CREATE INDEX … WHERE status IN (0,2)} 部分索引 ·
 * {@code CHECK (source_snapshot_sha256 ~ '^[a-f0-9]{64}$')} 正则约束。
 * ⇒ 换成 H2 等于「把要验证的东西换没了」（假绿）。
 */
@SpringBootTest(
        classes = FavoriteServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 绕过 Nacos：@GrpcClient 在 **Bean 初始化期**就建 channel（不是懒建），
                // 测试环境没有 Nacos ⇒ discovery:// 解析失败 ⇒ 上下文起不来。
                // B 出处：B 侧 FavoriteSchemaParityTest.java:51-57（同款注释，抄 like-rpc 先例）。
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:1",
                "grpc.client.article-rpc.address=static://127.0.0.1:1",
                // 🔴 C 侧独有：FavoriteApiProperties#verifyAccessSecret 是 fail-closed 的
                // （@PostConstruct，缺变量 ⇒ 启动失败）⇒ 测试必须给一个非空密钥。
                // B 出处：B 侧 favorite-rpc 的 FavoriteApiProperties.java:79-113。
                "favorite-api.user-auth.access-secret=rag-v1-favorite-parity-test-secret",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN"
        })
class FavoriteSchemaParityTest extends FavoritePgParityTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 🔴 用例之间必须清库（B 侧 {@code FavoriteSchemaParityTest.java:92-113} 的原注释照抄）。
     *
     * <p>为什么用 TRUNCATE 而不是 DELETE：{@code favorite_legacy_fact_marker} 上有
     * {@code favorite_legacy_marker_immutable} BEFORE UPDATE OR DELETE 触发器，
     * DELETE 会被它拒绝；TRUNCATE 不触发行级 DELETE 触发器。
     */
    @BeforeEach
    void resetTables() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE favorite_folder, favorite_item,
                                 favorite_fact_outbox, favorite_legacy_fact_marker
                CASCADE
                """);
    }

    // ==================== 4 张表 ====================

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:117-127}。
     * DDL 出处：B 侧 {@code V1__create_favorite_folder.sql:41} / {@code V2__create_favorite_item.sql:51}
     * / {@code V3__create_favorite_fact_outbox.sql} / {@code V4__create_favorite_legacy_fact_marker.sql:51}。
     */
    @Test
    @DisplayName("Flyway 建出 4 张表 —— 缺 favorite_legacy_fact_marker 会把 5304 变成 5008")
    void allFourTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public'",
                String.class);
        for (String table : List.of("favorite_folder", "favorite_item",
                "favorite_fact_outbox", "favorite_legacy_fact_marker")) {
            assertTrue(tables.contains(table), "缺表: " + table);
        }
    }

    // ==================== 🔴 两个不可变触发器（AutoMigrate 绝不创建） ====================

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:131-157}。
     * DDL 出处：B 侧 {@code V6__align_favorite_schema_with_go.sql:128-142}
     * （{@code reject_favorite_fact_identity_change()} + {@code favorite_fact_identity_immutable}）。
     */
    @Test
    @DisplayName("🔴 favorite_fact_identity_immutable：BEFORE UPDATE 拒改冻结信封的 4 列")
    void outboxEnvelopeIsImmutable() {
        jdbcTemplate.update("""
                INSERT INTO favorite_fact_outbox
                    (event_id, favorite_id, aggregate_version, payload, status, retry_count,
                     created_at, updated_at)
                VALUES ('favorite.9001.v1', 9001, 1,
                        '{"event_id":"favorite.9001.v1"}'::jsonb, 0, 0,
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                """);

        // ① 改投递元数据列 ⇒ 必须成功（dispatcher 每天都在做）
        assertEquals(1, jdbcTemplate.update(
                "UPDATE favorite_fact_outbox SET status = 1, technical_offset = 7 WHERE event_id='favorite.9001.v1'"));

        // ② 🔴 改 payload ⇒ 必须被拒
        assertThrowsPg(() -> jdbcTemplate.update(
                "UPDATE favorite_fact_outbox SET payload='{\"tampered\":1}'::jsonb "
                        + "WHERE event_id='favorite.9001.v1'"));
        // ③ 🔴 改 favorite_id ⇒ 必须被拒
        assertThrowsPg(() -> jdbcTemplate.update(
                "UPDATE favorite_fact_outbox SET favorite_id = 9002 WHERE event_id='favorite.9001.v1'"));
        // ④ 🔴 改 aggregate_version ⇒ 必须被拒
        assertThrowsPg(() -> jdbcTemplate.update(
                "UPDATE favorite_fact_outbox SET aggregate_version = 2 WHERE event_id='favorite.9001.v1'"));
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:159-174}。
     * DDL 出处：B 侧 {@code V4__create_favorite_legacy_fact_marker.sql:95-105} +
     * {@code V6__align_favorite_schema_with_go.sql:146-154}。
     */
    @Test
    @DisplayName("🔴 favorite_legacy_marker_immutable：连 DELETE 都禁（防事后抹掉审批证据）")
    void legacyMarkerIsImmutable() {
        jdbcTemplate.update("""
                INSERT INTO favorite_legacy_fact_marker
                    (favorite_id, user_id, folder_id, target_type, target_id,
                     source_snapshot_sha256, approval_ref)
                VALUES (8001, 1001, 2001, 'article', '9001',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'TICKET-123')
                """);
        assertThrowsPg(() -> jdbcTemplate.update(
                "UPDATE favorite_legacy_fact_marker SET approval_ref='TICKET-999' WHERE favorite_id=8001"));
        assertThrowsPg(() -> jdbcTemplate.update(
                "DELETE FROM favorite_legacy_fact_marker WHERE favorite_id=8001"));
    }

    // ==================== 🔴 CHECK / UNIQUE 约束 ====================

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:178-196}。
     * DDL 出处：B 侧 {@code V6__align_favorite_schema_with_go.sql:113}
     * （{@code ADD CONSTRAINT uk_favorite_fact_version UNIQUE (favorite_id, aggregate_version)}）。
     */
    @Test
    @DisplayName("🔴 UNIQUE(favorite_id, aggregate_version)：一个收藏的每个版本恰好一个冻结事实")
    void oneFrozenFactPerAggregateVersion() {
        jdbcTemplate.update("""
                INSERT INTO favorite_fact_outbox
                    (event_id, favorite_id, aggregate_version, payload, status, retry_count,
                     created_at, updated_at)
                VALUES ('favorite.7001.v1', 7001, 1, '{"a":1}'::jsonb, 0, 0,
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                """);
        // 🔴 23505 —— create_favorite_logic.go:157-161 依赖它翻译成 AlreadyExists/5302
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_fact_outbox
                    (event_id, favorite_id, aggregate_version, payload, status, retry_count,
                     created_at, updated_at)
                VALUES ('favorite.7001.v1-dup', 7001, 1, '{"a":1}'::jsonb, 0, 0,
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                """));
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:198-208}。
     * DDL 出处：B 侧 {@code V6__align_favorite_schema_with_go.sql:105}
     * （{@code ADD CONSTRAINT favorite_fact_outbox_aggregate_version_range CHECK (aggregate_version IN (1,2))}）。
     */
    @Test
    @DisplayName("🔴 CHECK (aggregate_version IN (1,2))：挡住无法解释的 v3 事实")
    void aggregateVersionIsConstrained() {
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_fact_outbox
                    (event_id, favorite_id, aggregate_version, payload, status, retry_count,
                     created_at, updated_at)
                VALUES ('favorite.7002.v3', 7002, 3, '{"a":1}'::jsonb, 0, 0,
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                """));
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:210-231}。
     * DDL 出处：B 侧 {@code V6__align_favorite_schema_with_go.sql:92}
     * （{@code ADD CONSTRAINT favorite_item_target_revision_scope CHECK (...)}）。
     */
    @Test
    @DisplayName("🔴 CHECK (target_revision IS NULL OR (target_type='article' AND target_revision<>''))")
    void targetRevisionScopeIsConstrained() {
        jdbcTemplate.update("INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (6001, 1001, 'f')");
        // 合法：post + null revision
        jdbcTemplate.update("""
                INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type)
                VALUES (6001, 6001, 1001, 'p1', 'post')
                """);
        // 🔴 非法：post + 非 null revision
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_item
                    (favorite_id, folder_id, user_id, target_id, target_type, target_revision)
                VALUES (6002, 6001, 1001, 'p2', 'post', 'p2:r1')
                """));
        // 🔴 非法：article + 空串 revision
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_item
                    (favorite_id, folder_id, user_id, target_id, target_type, target_revision)
                VALUES (6003, 6001, 1001, 'a3', 'article', '')
                """));
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:233-258}。
     * DDL 出处：B 侧 {@code V6__align_favorite_schema_with_go.sql:190}
     * （{@code favorite_legacy_marker_snapshot_hash_format}）+
     * {@code :195}（{@code favorite_legacy_marker_approval_ref_nonempty}）。
     */
    @Test
    @DisplayName("🔴 legacy 标记表的 hash 正则 CHECK：非 hex / 长度不对必须被拒")
    void legacyMarkerHashIsConstrained() {
        jdbcTemplate.update("INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (6002, 1001, 'f')");
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_legacy_fact_marker
                    (favorite_id, user_id, folder_id, target_type, target_id,
                     source_snapshot_sha256, approval_ref)
                VALUES (5001, 1001, 6002, 'article', 'a1', 'NOT-A-HASH', 'TICKET-1')
                """));
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_legacy_fact_marker
                    (favorite_id, user_id, folder_id, target_type, target_id,
                     source_snapshot_sha256, approval_ref)
                VALUES (5002, 1001, 6002, 'article', 'a2',
                        'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
                        'TICKET-2')
                """));
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_legacy_fact_marker
                    (favorite_id, user_id, folder_id, target_type, target_id,
                     source_snapshot_sha256, approval_ref)
                VALUES (5003, 1001, 6002, 'article', 'a3',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '')
                """));
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:260-277}。
     * DDL 出处：B 侧 {@code V2__create_favorite_item.sql:83}
     * （{@code CREATE UNIQUE INDEX uk_folder_target ON favorite_item (folder_id, target_id, target_type)}）。
     */
    @Test
    @DisplayName("🔴 uk_folder_target：同文件夹同目标只能收藏一次（小写化后的 target_type）")
    void folderTargetIsUnique() {
        jdbcTemplate.update("INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (4001, 1001, 'f')");
        jdbcTemplate.update("""
                INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type)
                VALUES (4001, 4001, 1001, 'a1', 'article')
                """);
        assertThrowsPg(() -> jdbcTemplate.update("""
                INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type)
                VALUES (4002, 4001, 1001, 'a1', 'article')
                """));
        // 🔴 但 target_type 不同 ⇒ 是两条不同的收藏
        jdbcTemplate.update("""
                INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type)
                VALUES (4003, 4001, 1001, 'a1', 'post')
                """);
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:279-287}。
     * DDL 出处：B 侧 {@code V1__create_favorite_folder.sql:63}
     * （{@code CREATE UNIQUE INDEX uk_user_folder_name ON favorite_folder (user_id, name)}）。
     */
    @Test
    @DisplayName("🔴 uk_user_folder_name：同用户收藏夹名唯一")
    void folderNameIsUniquePerUser() {
        jdbcTemplate.update("INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (3001, 1001, 'dup')");
        assertThrowsPg(() -> jdbcTemplate.update(
                "INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (3002, 1001, 'dup')"));
        // 不同用户可以同名
        jdbcTemplate.update("INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (3003, 1002, 'dup')");
    }

    // ==================== 🔴 部分索引（服务 DispatchOnce 的取件查询） ====================

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:291-304}。
     * DDL 出处：B 侧 {@code V6__align_favorite_schema_with_go.sql:118}
     * （{@code CREATE INDEX IF NOT EXISTS favorite_fact_outbox_pending … WHERE status IN (0,2)}）。
     */
    @Test
    @DisplayName("🔴 favorite_fact_outbox_pending 部分索引存在且条件正确")
    void pendingPartialIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' "
                        + "AND indexname='favorite_fact_outbox_pending'", String.class);
        assertEquals(1, indexes.size(), "取件查询会退化成全表扫");
        String def = indexes.get(0);
        assertTrue(def.contains("status"), def);
        assertTrue(def.contains("created_at"), def);
        // 🔴 部分条件（WHERE）才是这条索引的全部意义
        assertTrue(def.toLowerCase().contains("where"), def);
        assertFalse(def.toLowerCase().contains("where status is null"), def);
    }

    // ==================== 列类型：时区与业务 ID 形态 ====================

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:308-326}。
     * DDL 出处：B 侧 {@code V2__create_favorite_item.sql}（{@code target_id varchar(255)}）。
     */
    @Test
    @DisplayName("🔴 favorite_item.target_id 是 varchar(255) 而非 bigint（article 雪花 ID > 2^53）")
    void targetIdIsVarchar() {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema='public' AND table_name='favorite_item' AND column_name='target_id'
                """, String.class);
        assertEquals("character varying", dataType);

        // 🔴 实测：超 2^53 的目标 ID 必须能原样存入
        jdbcTemplate.update("INSERT INTO favorite_folder (folder_id, user_id, name) VALUES (2001, 1001, 'f')");
        jdbcTemplate.update("""
                INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type)
                VALUES (2001, 2001, 1001, '9223372036854775807', 'article')
                """);
        String stored = jdbcTemplate.queryForObject(
                "SELECT target_id FROM favorite_item WHERE favorite_id=2001", String.class);
        assertEquals("9223372036854775807", stored);
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:328-340}。
     * DDL 出处：B 侧 {@code V1__create_favorite_folder.sql} / {@code V2__create_favorite_item.sql}
     * / {@code V3__create_favorite_fact_outbox.sql:60-62}（{@code timestamptz}）。
     */
    @Test
    @DisplayName("🔴 时间列是 timestamptz 且带时区语义（Go DSN 显式 TimeZone=Asia/Shanghai）")
    void timeColumnsAreTimestamptz() {
        for (String[] pair : new String[][]{
                {"favorite_folder", "create_time"}, {"favorite_folder", "update_time"},
                {"favorite_item", "create_time"}, {"favorite_fact_outbox", "created_at"}}) {
            String dataType = jdbcTemplate.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema='public' AND table_name=? AND column_name=?
                    """, String.class, pair[0], pair[1]);
            assertEquals("timestamp with time zone", dataType, pair[0] + "." + pair[1]);
        }
    }

    /**
     * B 出处：B 侧 {@code FavoriteSchemaParityTest.java:342-350}。
     * DDL 出处：B 侧 {@code V2__create_favorite_item.sql}（{@code favorite_item} 无 {@code update_time} 列）。
     */
    @Test
    @DisplayName("favorite_item 没有 update_time —— 收藏条目不可变")
    void itemHasNoUpdateTime() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='favorite_item' AND column_name='update_time'
                """, Integer.class);
        assertEquals(0, count);
    }

    /** 断言这条 DDL 触发了 PG 的约束/触发器异常（B 侧 {@code FavoriteSchemaParityTest.java:352-364}）。 */
    private static void assertThrowsPg(Runnable statement) {
        try {
            statement.run();
        } catch (RuntimeException expected) {
            // PG 的约束违例在 Spring JDBC 下是 DataAccessException 的子类
            assertTrue(expected.getClass().getName().contains("DataAccess")
                            || expected.getMessage() != null,
                    "异常类型异常: " + expected);
            return;
        }
        throw new AssertionError("期望被数据库约束/触发器拒绝，但语句成功了");
    }
}

/**
 * 🔴🔴 <b>真 PostgreSQL 容器 + 数据源注入的共享基类</b>（favorite 域持久层真 PG 测试专用）。
 *
 * <h2>为什么它存在</h2>
 * <p>本仓 favorite 域有 <b>4 个</b>「真 PG + 真 Flyway」测试类
 * （{@code FavoriteDdlParityTest} / {@code FavoriteSchemaParityTest} /
 * {@code FavoriteFolderItemTimeColumnTest} / {@code FavoriteOutboxTimeColumnTest}）
 * ⇒ 若每个类各起一个容器 ⇒ <b>慢 3 倍</b>且机器上同时跑 4 个 PG。
 * ⇒ ⇒ <b>单例</b>：整个 JVM 起一次，各测试类共用同一个库。
 * <p>⚠️ <b>共用库 ⇒ 数据必须互不冲突</b> ⇒ 各测试类用<b>互不重叠的 id 段</b>
 * （本域各测试类的 id 段见各自文件的 {@code @BeforeEach} 注释）。
 *
 * <h2>容器管理形态：抄 like 域</h2>
 * <p>形态抄 {@code service/like/src/test/java/com/rag/like/support/TestPostgres.java:29-68}
 * （进程内单例 + 静态块启动 + {@code register(DynamicPropertyRegistry)} 注入）。
 *
 * <h3>§3.2 三段式留痕</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>B 侧<b>没有</b>这个基类 ⇒ 每个测试类各写一份 {@code @Container} 字段 +
 *           {@code @DynamicPropertySource}（B 侧 {@code FavoriteSchemaParityTest.java:65-87}
 *           / {@code FavoriteFolderItemTimeColumnTest.java:77-98}
 *           / {@code FavoriteOutboxTimeColumnTest.java:79-100}
 *           / {@code FavoriteDdlParityTest.java:72-93}）。</li>
 *       <li>C 侧<b>多</b>了「容器宿主口段」的约束：like 域直接用 Docker 随机口
 *           ⇒ C 侧 favorite 域沿用同一形态（<b>不钉宿主口</b>），
 *           但<b>gRPC server 端口</b>必须自己挑 ⇒ 复用 C 侧已有的
 *           {@link RealTomcatPort#pick()}（段 {@code 21400-21499}，避开 OS 临时端口段
 *           {@code 49152-65535} —— Docker 随机发布口就在那一段里）。</li>
 *       <li>⚠️ <b>本基类<b>不能</b>放进 {@code support/} 包</b>：本次交付的写权面被限定为
 *           5 个测试文件 ⇒ 它作为<b>包级私有</b>的第二个顶层类寄居在
 *           {@code FavoriteSchemaParityTest.java} 里（Java 允许一个文件里多个顶层类，
 *           只有一个能是 {@code public}）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 容器镜像（{@code postgres:16-alpine}）、库名
 *       （{@code first_db}）、账号（{@code admin/password}）与 like 域/B 侧逐字一致；
 *       差别只在「几个类共用一个容器」这一<b>测试设施</b>层面，
 *       对<b>产品行为</b>零影响（AGENTS.md §3.2：测试设施不是产品行为 ⇒ 不存在语义对照义务）。</li>
 *   <li><b>B 出处</b>：B 侧 {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/
 *       FavoriteSchemaParityTest.java:65-87}（容器与 {@code @DynamicPropertySource} 形态）；
 *       形态先例：{@code service/like/src/test/java/com/rag/like/support/TestPostgres.java:29-68}。</li>
 * </ol>
 *
 * <h2>🔴 禁 {@code @EnabledIfDockerAvailable}</h2>
 * <p>AGENTS.md §五.8 守门硬规则 ③ ⇒ 本基类<b>没有</b>该注解 ⇒
 * Docker 不可用时 {@link PostgreSQLContainer#start()} 直接抛
 * ⇒ 整类<b>红</b>（而不是静默跳过 = 假绿）。
 */
abstract class FavoritePgParityTestBase {

    /**
     * 真 PG（进程内单例）。镜像 / 库名 / 账号与 B 侧、like 域逐字一致。
     *
     * <p>🔴 <b>宿主口必须钉死</b>（2026-11-04 · 守门 {@code RealContainerHostPortTest} 抓出来的）：
     * Testcontainers 默认把容器端口发布到 OS <b>临时端口段</b>（macOS 49152–65535）
     * ⇒ ⇒ 那正是<b>别的会话的 Docker 容器</b>也在用的段 ⇒ ⇒ 会撞口、且撞口的表现是
     * 「客户端把请求打到了别的东西上」（零字节即关）⇒ **看着像被测代码炸了**。
     * ⇒ ⇒ 用 {@code RealContainerHostPort.bindPg(...)} 钉到本域<b>专属</b>宿主口段。
     */
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password")
            .withCreateContainerCmdModifier(
                    RealContainerHostPort.bindPg(RealContainerHostPort.FAVORITE_PG_PARITY));

    /**
     * gRPC server 端口（<b>不用 {@code port=0}</b>）。
     *
     * <p>⚠️ {@code port=0} 让 OS 从临时端口段（macOS 49152–65535）里挑 ⇒ 那是
     * <b>Docker 随机发布容器端口用的同一段</b> ⇒ 别的会话的容器先占住 0.0.0.0:P ⇒
     * 我们再绑同一个端口 ⇒ EADDRINUSE ⇒ <b>整个测试类启动失败</b>
     * （表现为「偶尔红、换个时间又绿」的抖动）。
     * <p>B 出处：B 侧 {@code FavoriteSchemaParityTest.java:82-86}（同款注释，抄法照搬）。
     */
    private static final int GRPC_PORT = RealTomcatPort.pick();

    static {
        // 🔴 容器不可用 ⇒ 这里直接抛 ⇒ 测试类<b>红</b>（不是跳过）
        PG.start();
    }

    /**
     * 把容器连接信息注入 Spring 的 {@code spring.datasource.*}，
     * 并关掉三个后台 fact 组件（它们会自己往 PG 上写语句 ⇒ 污染「某个用例产生的 SQL」）。
     *
     * <p>B 出处：B 侧 {@code FavoriteSchemaParityTest.java:73-87}。
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("favorite.fact.dispatch.enabled", () -> "false");
        registry.add("favorite.fact.authority.enabled", () -> "false");
        registry.add("favorite.fact.legacy-preflight.enabled", () -> "false");
        registry.add("grpc.server.port", () -> String.valueOf(GRPC_PORT));
    }

    /** 容器 JDBC URL —— 供需要自己开连接的测试用。 */
    protected static String jdbcUrl() {
        return PG.getJdbcUrl();
    }
}
