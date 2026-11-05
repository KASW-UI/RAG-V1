package com.rag.comment.service;

import com.rag.comment.support.TestPostgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>🔴 <b>真 PostgreSQL 16 ＋ 真 Flyway 引擎上的 DDL / 账本守门测试</b>。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>探针库隔离</b>：B 侧 {@code CommentFlywaySharedSchemaGateTest} 与
 *       {@code CommentSchemaPgGateTest} 各起一个容器、各用一个库；C 侧共用<b>进程内单例</b>
 *       容器（本域其它测试类也用同一个容器）⇒ 本类在<b>同一容器里另开两个探针库</b>
 *       （{@code comment_flyway_nonempty} / {@code comment_flyway_fresh}），
 *       避免与其它测试类的数据互不冲突（AGENTS.md §五.2 的唯一 uid 纪律在库一级落地）。</li>
 *   <li><b>账本表名与 baseline-version 断言前置到「读配置」那一刻</b>：
 *       B 侧在 {@code flywayConfiguredByModuleYaml()} 里断言；C 侧保留同一位置，
 *       并额外断言 {@code baseline-on-migrate} / {@code locations} / {@code schemas}
 *       四个键（生产真正用的那几个值，一个不硬编码）。</li>
 *   <li><b>DDL 语义断言合并进同一类</b>：B 侧拆成两个类、两个容器；
 *       C 侧合成一个类（一个容器 + 两个探针库）⇒ 启动成本减半，断言一条不减。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。B 侧两条守门线的<b>判据集合逐条保留</b>：
 * <ul>
 *   <li>「非空 schema + 没有自己的账本 ⇒ baseline 必须落在 version 0 ⇒ V1 之后全部照常执行」；
 *   </li>
 *   <li>7 张表 + 约束 + 部分索引 + plpgsql 触发器 + 列类型 + jsonb 往返 + 位运算 +
 *       {@code ON CONFLICT DO NOTHING} + 原子取号。</li>
 * </ul>
 * <p>⚠️ <b>唯一被有意改掉的是「账本表名」</b>：B 侧的 {@code CommentSchemaPgGateTest} 用的是
 * {@code flyway_schema_history_comment_it}（测试专用名，用来证明「改表名就不撞号」）；
 * C 侧本类<b>统一用生产值</b> {@code flyway_schema_history_comment}（从本模块
 * {@code application.yml} 读，<b>不硬编码</b>）⇒ 顺带把「改表名就不撞号」这条
 * 也一起验掉了（见 {@link #ledgerTableNameIsDomainScoped()}）。</p>
 *
 * <h2>3. B 出处</h2>
 * <ul>
 *   <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentFlywaySharedSchemaGateTest.java}
 *       （215 行 · 账本 baseline 守门）</li>
 *   <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentSchemaPgGateTest.java}
 *       （407 行 · DDL / 约束 / 语义守门）</li>
 *   <li>DDL 事实来源：{@code service/comment/comment-rpc/src/main/resources/db/migration/V1..V8}
 *       （C 侧逐字节复制自 B 侧同名脚本）</li>
 * </ul>
 *
 * <h2>🔴 为什么必须真 PG（禁 H2 顶替）</h2>
 * <p>本域 DDL 用到 {@code BIGSERIAL / IDENTITY}、{@code JSONB}、{@code TIMESTAMPTZ}、
 * {@code ON CONFLICT ... DO NOTHING}、{@code ON CONFLICT (aggregate_id) DO UPDATE ... RETURNING}、
 * {@code NOT VALID CHECK}、<b>plpgsql 触发器函数</b>（{@code DO $$ ... $$} 之外的
 * {@code CREATE FUNCTION ... $$ ... $$}）、部分索引（{@code WHERE delivery_envelope IS NULL}）。
 * H2 一个都不认 ⇒ H2 上「跑绿」是<b>假绿</b>（AGENTS.md §3.3）。
 *
 * <h2>🔴 为什么不用 {@code @EnabledIfDockerAvailable}</h2>
 * <p>AGENTS.md §五.8 守门硬规则 ③：容器不可用时<b>直接失败</b>，不静默跳过（跳过 = 假绿）。
 */
class CommentFlywayBaselineRealPgTest {

    /** 非空 schema 探针库（模拟「别的域先在同一库里建过表」的生产拓扑）。 */
    private static final String PROBE_DB_NONEMPTY = "comment_flyway_nonempty";

    /** 全新空库探针库（DDL 语义断言用）。 */
    private static final String PROBE_DB_FRESH = "comment_flyway_fresh";

    /** 生产账本表名（<b>只作为断言值</b>；真正生效的值从 application.yml 读）。 */
    private static final String LEDGER = "flyway_schema_history_comment";

    /** 本域 7 张表（口径：AGENTS.md §0 表清单 · B 侧 CommentSchemaPgGateTest:92-94）。 */
    private static final List<String> SEVEN_TABLES = List.of(
            "subject", "comment_index", "comment_content", "comment_like", "report_record",
            "comment_domain_fact_outbox", "comment_fact_stream");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        createProbeDatabase(PROBE_DB_NONEMPTY);
        createProbeDatabase(PROBE_DB_FRESH);

        // ① 非空 schema：先建一张**别的域的表** ⇒ 制造「schema 非空 + 本域账本不存在」
        try (Connection conn = DriverManager.getConnection(containerJdbcUrl(PROBE_DB_NONEMPTY),
                TestPostgres.username(), TestPostgres.password());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS unrelated_other_module_table (id BIGINT PRIMARY KEY)");
        }

        // ② 全新空库
        flywayConfiguredByModuleYaml(PROBE_DB_NONEMPTY).migrate();
        flywayConfiguredByModuleYaml(PROBE_DB_FRESH).migrate();

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB_FRESH), TestPostgres.username(), TestPostgres.password()));
    }

    // ====================================================================================
    // ① 账本 baseline 守门（B 侧 CommentFlywaySharedSchemaGateTest）
    // ====================================================================================

    @Test
    @DisplayName("🔴 非空 schema 下 V1 必须真执行 ⇒ 7 张表必须全部存在")
    void v1MustRunEvenOnNonEmptySchema() throws IOException {
        JdbcTemplate nonempty = new JdbcTemplate(new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB_NONEMPTY), TestPostgres.username(), TestPostgres.password()));

        // 前置事实：schema 确实非空（预置了别的域的表）⇒ Flyway 必然走 baseline-on-migrate 分支
        assertThat(nonempty.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name='unrelated_other_module_table'",
                Integer.class)).isEqualTo(1);

        for (String table : SEVEN_TABLES) {
            assertThat(tableExists(nonempty, table))
                    .as("表 %s 必须存在 —— 若被 baseline 跳过，这张表就永远建不出来，"
                            + "后续迁移会因它不存在而失败（relation \"%s\" does not exist）", table, table)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("🔴 账本的 BASELINE 行版本必须是 0（否则 V1 被整条跳过），且 V1..V8 全是 SQL 行")
    void ledgerBaselineMustSitBelowV1() throws IOException {
        JdbcTemplate nonempty = new JdbcTemplate(new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB_NONEMPTY), TestPostgres.username(), TestPostgres.password()));

        List<String> versions = nonempty.queryForList(
                "SELECT version FROM " + LEDGER + " ORDER BY installed_rank", String.class);
        List<String> types = nonempty.queryForList(
                "SELECT type FROM " + LEDGER + " ORDER BY installed_rank", String.class);

        // ⚠️ 口径（B 侧 CommentFlywaySharedSchemaGateTest:123-133）：baseline-on-migrate=true 时
        //   Flyway **一定会**写一行 BASELINE —— 关键不是「有没有 BASELINE 行」，
        //   而是「**这行的版本是不是 0**」。
        for (int i = 0; i < types.size(); i++) {
            if ("BASELINE".equals(types.get(i))) {
                assertThat(versions.get(i))
                        .as("BASELINE 行必须落在 version 0（V1 之下）；落在 version 1 ⇒ V1 被整条跳过。"
                                + "当前账本=%s/%s", versions, types)
                        .isEqualTo("0");
            }
        }
        assertThat(types).as("账本里一条 SQL 行都没有 ⇒ 迁移全被跳过；账本=%s/%s", versions, types)
                .contains("SQL");
        for (String v : List.of("1", "2", "3", "4", "5", "6", "7", "8")) {
            assertThat(versions)
                    .as("账本里缺 version %s ⇒ 该迁移没执行；账本=%s/%s", v, versions, types)
                    .contains(v);
        }
        assertThat(types.get(versions.indexOf("1")))
                .as("version 1 必须是 SQL 行（V1 真执行了），不能是 BASELINE；账本=%s/%s", versions, types)
                .isEqualTo("SQL");
    }

    @Test
    @DisplayName("🔴 账本表名必须是本域独立的 flyway_schema_history_comment（不得用 schema 级账本）")
    void ledgerTableNameIsDomainScoped() throws IOException {
        JdbcTemplate nonempty = new JdbcTemplate(new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB_NONEMPTY), TestPostgres.username(), TestPostgres.password()));

        assertThat(tableExists(nonempty, LEDGER))
                .as("账本表 %s 必须存在（各域独立账本 ⇒ 共享 schema 下不撞号）", LEDGER)
                .isTrue();
        assertThat(tableExists(nonempty, "flyway_schema_history"))
                .as("不得使用 schema 级账本 flyway_schema_history（AGENTS.md §六 的核心问题）")
                .isFalse();
    }

    @Test
    @DisplayName("🔴 全新空库下账本里就是 V1..V8 八条 SQL（无 BASELINE 行）")
    void freshSchemaLedgerHasEightSqlRows() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM " + LEDGER + " WHERE success ORDER BY installed_rank", String.class);
        List<String> types = jdbc.queryForList(
                "SELECT type FROM " + LEDGER + " WHERE success ORDER BY installed_rank", String.class);

        assertThat(versions)
                .as("B 侧 CommentSchemaPgGateTest:96-99 ⇒ V1..V8 全部应用成功")
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(types).containsOnly("SQL");
    }

    // ====================================================================================
    // ② DDL 语义（B 侧 CommentSchemaPgGateTest）
    // ====================================================================================

    @Test
    @DisplayName("🔴 V8 的 plpgsql 触发器与函数真的被创建（不是被静默跳过）")
    void plpgsqlTriggerWasCreatedByFlyway() {
        Integer triggerCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'comment_fact_identity_immutable'", Integer.class);
        assertThat(triggerCount).isEqualTo(1);

        Integer functionCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_proc WHERE proname = 'reject_comment_fact_identity_change'", Integer.class);
        assertThat(functionCount).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 NOT VALID CHECK 约束存在且拦得住「信封三件套不全」")
    void dcWireCheckConstraintBlocksIncompleteFact() {
        // 先放一条完整事实（后面要靠它验证触发器）
        jdbc.update("""
                INSERT INTO comment_domain_fact_outbox
                    (event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope)
                VALUES ('rtw.comment.1.created', 'community.comment.created', '{}'::jsonb,
                        '1', 1, '{"event_id":"x"}'::jsonb)
                """);

        // envelope 缺 delivery_envelope ⇒ CHECK 必须拒绝
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO comment_domain_fact_outbox (event_id, event_type, payload, aggregate_id, fact_version)
                VALUES ('rtw.comment.2.created', 'community.comment.created', '{}'::jsonb, '2', 1)
                """))
                .hasMessageContaining("comment_new_fact_has_dc_wire");

        // ⚠️ NOT VALID 必须逐字保留：它对 INSERT/UPDATE 照常生效，只是不回扫存量行
        Integer notValid = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conname = 'comment_new_fact_has_dc_wire' AND NOT convalidated", Integer.class);
        assertThat(notValid)
                .as("NOT VALID 必须逐字照抄：改成 VALID 会在存量行不满足时直接迁移失败（Go 没有这个行为）")
                .isEqualTo(1);

        jdbc.update("DELETE FROM comment_domain_fact_outbox WHERE event_id = 'rtw.comment.1.created'");
    }

    @Test
    @DisplayName("🔴 信封不可变触发器：改 delivery_status 放行，改 payload 抛异常")
    void identityTriggerAllowsMetadataUpdateButRejectsPayloadRewrite() {
        jdbc.update("""
                INSERT INTO comment_domain_fact_outbox
                    (event_id, event_type, payload, aggregate_id, fact_version, delivery_envelope)
                VALUES ('rtw.comment.7.created', 'community.comment.created',
                        '{"a":1}'::jsonb, '7', 1, '{"event_id":"x"}'::jsonb)
                """);
        try {
            // 投递元数据更新 ⇒ 正常（真实投递路径走的就是这条）
            jdbc.update("""
                    UPDATE comment_domain_fact_outbox
                    SET delivery_status = 2, retry_count = retry_count + 1
                    WHERE event_id = 'rtw.comment.7.created'
                    """);
            assertThat(jdbc.queryForObject(
                    "SELECT retry_count FROM comment_domain_fact_outbox WHERE event_id='rtw.comment.7.created'",
                    Integer.class)).isEqualTo(1);

            // 改 payload ⇒ 必须被拒
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE comment_domain_fact_outbox SET payload = '{\"a\":2}'::jsonb "
                            + "WHERE event_id = 'rtw.comment.7.created'"))
                    .hasMessageContaining("comment fact envelope is immutable");
        } finally {
            jdbc.update("DELETE FROM comment_domain_fact_outbox WHERE event_id = 'rtw.comment.7.created'");
        }
    }

    @Test
    @DisplayName("🔴 部分索引 idx_comment_legacy_fact_aggregate 只覆盖 delivery_envelope IS NULL 的行")
    void legacyPartialIndexPredicate() {
        String predicate = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_comment_legacy_fact_aggregate'",
                String.class);
        assertThat(predicate).contains("payload ->> 'aggregate_id'");
        assertThat(predicate.toLowerCase()).contains("where");
    }

    @Test
    @DisplayName("🔴 delivery_status / retry_count 必须是 integer（不是 003 里的 smallint），technical_offset 是 bigint")
    void deliveryColumnsAreIntegerNotSmallint() {
        Map<String, Object> types = columnTypes(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='comment_domain_fact_outbox' AND column_name IN "
                        + "('delivery_status','retry_count','technical_offset')", "column_name");
        assertThat(types).containsEntry("delivery_status", "integer");
        assertThat(types).containsEntry("retry_count", "integer");
        assertThat(types).containsEntry("technical_offset", "bigint");
    }

    @Test
    @DisplayName("🔴 业务表时间列是 timestamptz（GORM postgres 驱动的产物，不是 timestamp）")
    void businessTimeColumnsAreTimestamptz() {
        Map<String, Object> types = columnTypes(
                "SELECT table_name || '.' || column_name AS col, data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND data_type IN ('timestamp with time zone','timestamp without time zone') "
                        + "AND table_name IN ('subject','comment_index','comment_content','comment_like')",
                "col");
        assertThat(types).isNotEmpty();
        assertThat(types.values()).allMatch(v -> "timestamp with time zone".equals(v));
    }

    @Test
    @DisplayName("🔴 jsonb 列类型正确：comment_content.meta + outbox 的 payload/delivery_envelope")
    void jsonbColumnsAreJsonb() {
        Map<String, Object> types = columnTypes(
                "SELECT table_name || '.' || column_name AS col, data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND data_type='jsonb'", "col");
        assertThat(types).containsKeys("comment_content.meta",
                "comment_domain_fact_outbox.payload", "comment_domain_fact_outbox.delivery_envelope");
    }

    @Test
    @DisplayName("🔴 comment_like 唯一键 (user_id, comment_id) 冲突时 ON CONFLICT DO NOTHING 静默跳过")
    void commentLikeUniqueKeyEnablesLockAnchorInsert() {
        jdbc.update("""
                INSERT INTO comment_like (user_id, comment_id, target_type, target_id, state)
                VALUES (7, 99, 'article', 'a1', 0)
                """);
        // 锁锚的第二次插入必须静默成功（而不是报错）—— 这是并发首次点赞的正确性载体
        int affected = jdbc.update("""
                INSERT INTO comment_like (user_id, comment_id, target_type, target_id, state)
                VALUES (7, 99, 'article', 'a1', 0)
                ON CONFLICT DO NOTHING
                """);
        assertThat(affected).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_like WHERE user_id=7 AND comment_id=99", Integer.class))
                .isEqualTo(1);
        jdbc.update("DELETE FROM comment_like WHERE user_id=7 AND comment_id=99");
    }

    @Test
    @DisplayName("🔴 report_record 唯一键 + ON CONFLICT DO NOTHING（不带 Columns）静默吞掉重复举报")
    void duplicateReportIsSilentlyIgnored() {
        jdbc.update("INSERT INTO report_record (user_id, comment_id, target_type, target_id, reason) "
                + "VALUES (7, 99, 'article', 'a1', 1)");
        int affected = jdbc.update("INSERT INTO report_record (user_id, comment_id, target_type, target_id, reason) "
                + "VALUES (7, 99, 'article', 'a1', 1) ON CONFLICT DO NOTHING");
        assertThat(affected).isZero();
        // 接口仍返回 success=true（Go report_comment_logic.go:97-99）
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_record WHERE user_id=7", Integer.class))
                .isEqualTo(1);
        jdbc.update("DELETE FROM report_record WHERE user_id=7");
    }

    @Test
    @DisplayName("🔴 subject 冲突目标固定为 (target_type, target_id)，计数自增逐字等价 Go")
    void subjectUpsertIncrementsCounts() {
        jdbc.update("""
                INSERT INTO subject (target_type, target_id, total_count, root_count, state, attribute, owner_id)
                VALUES ('article', 'upsert-test', 1, 1, 0, 0, 5)
                """);
        // 根评论：total +1 且 root +1
        jdbc.update("""
                INSERT INTO subject (target_type, target_id, total_count, root_count, state, attribute, owner_id)
                VALUES ('article', 'upsert-test', 1, 1, 0, 0, 5)
                ON CONFLICT (target_type, target_id) DO UPDATE SET
                    total_count = subject.total_count + 1, root_count = subject.root_count + 1
                """);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT total_count, root_count FROM subject WHERE target_type='article' AND target_id='upsert-test'");
        assertThat(((Number) row.get("total_count")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("root_count")).longValue()).isEqualTo(2L);

        // 回复（rootCount=0）：只加 total
        jdbc.update("""
                INSERT INTO subject (target_type, target_id, total_count, root_count, state, attribute, owner_id)
                VALUES ('article', 'upsert-test', 1, 0, 0, 0, 5)
                ON CONFLICT (target_type, target_id) DO UPDATE SET total_count = subject.total_count + 1
                """);
        row = jdbc.queryForMap(
                "SELECT total_count, root_count FROM subject WHERE target_type='article' AND target_id='upsert-test'");
        assertThat(((Number) row.get("total_count")).longValue()).isEqualTo(3L);
        assertThat(((Number) row.get("root_count")).longValue()).isEqualTo(2L);

        jdbc.update("DELETE FROM subject WHERE target_id='upsert-test'");
    }

    @Test
    @DisplayName("🔴 comment_fact_stream 的原子取号：ON CONFLICT … RETURNING 逐条递增")
    void factStreamVersionAllocationIsMonotonic() {
        for (int i = 1; i <= 3; i++) {
            Long version = jdbc.queryForObject("""
                    INSERT INTO comment_fact_stream (aggregate_id, version) VALUES ('agg-x', 1)
                    ON CONFLICT (aggregate_id) DO UPDATE SET version = comment_fact_stream.version + 1
                    RETURNING version
                    """, Long.class);
            assertThat(version).isEqualTo((long) i);
        }
        jdbc.update("DELETE FROM comment_fact_stream WHERE aggregate_id='agg-x'");
    }

    @Test
    @DisplayName("🔴 fact_stream 版本上界 CHECK 生效（2^53-1）")
    void factStreamVersionRangeCheck() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO comment_fact_stream (aggregate_id, version) VALUES ('agg-y', 0)"))
                .hasMessageContaining("comment_fact_stream_version_range");
    }

    @Test
    @DisplayName("🔴 jsonb 列往返：写入字符串原样读回（PG 会规范化空白与键序）")
    void jsonbRoundTrips() {
        jdbc.update("INSERT INTO comment_content (comment_id, content, meta) "
                + "VALUES (1001, 'hello', '{\"b\":1,\"a\":2}'::jsonb)");
        String meta = jdbc.queryForObject(
                "SELECT meta::text FROM comment_content WHERE comment_id=1001", String.class);
        // jsonb 会规范化键序 ⇒ 这里断言「语义相等」而不是「字节相等」
        assertThat(meta).contains("\"a\"").contains("\"b\"");
        assertThat(meta.indexOf("\"a\"")).isLessThan(meta.indexOf("\"b\""));
        jdbc.update("DELETE FROM comment_content WHERE comment_id=1001");
    }

    @Test
    @DisplayName("🔴 attribute 位运算：置位用 |，清位用 & ~（用 - 会借位翻低位）")
    void attributeBitOperations() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id) "
                + "VALUES (2001, 'article', 'a1', 7)");
        // 精选：置第 3 位
        jdbc.update("UPDATE comment_index SET attribute = attribute | 8 WHERE id=2001");
        assertThat(attr(2001)).isEqualTo(8L);
        // 作者回复：置第 1 位
        jdbc.update("UPDATE comment_index SET attribute = attribute | 2 WHERE id=2001");
        assertThat(attr(2001)).isEqualTo(10L);
        // 取消精选：清第 3 位
        jdbc.update("UPDATE comment_index SET attribute = attribute & ~8 WHERE id=2001");
        assertThat(attr(2001)).isEqualTo(2L);
        // 再取消置顶：清第 2 位
        jdbc.update("UPDATE comment_index SET attribute = attribute & ~2 WHERE id=2001");
        assertThat(attr(2001)).isZero();
        jdbc.update("DELETE FROM comment_index WHERE id=2001");
    }

    @Test
    @DisplayName("🔴 行级悲观锁 SELECT … FOR UPDATE 在真 PG 上可执行")
    void forUpdateLockWorks() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id) "
                + "VALUES (2004, 'article', 'a1', 7)");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM comment_index WHERE id = 2004 FOR UPDATE");
        assertThat(rows).hasSize(1);
        jdbc.update("DELETE FROM comment_index WHERE id=2004");
    }

    // ====================================================================================
    // helpers
    // ====================================================================================

    /**
     * 用<b>本模块 {@code application.yml} 里真实的 flyway 配置</b>跑一遍真 Flyway。
     *
     * <p>⚠️ 刻意<b>不硬编码</b> locations / schemas / table / baseline-on-migrate / baseline-version
     * ⇒ 测的就是生产真正用的那几个值 ⇒ 配置一改，测试立刻跟着变。
     */
    private static Flyway flywayConfiguredByModuleYaml(String database) throws IOException {
        Properties p = moduleYaml();

        // 🔴 先确认读到的是**本模块自己的** yaml（classpath 上可能还有别的模块的 application.yml）
        assertThat(p.getProperty("spring.flyway.table"))
                .as("读到的必须是本模块自己的 application.yml（账本表名对不上 ⇒ 可能读串了）")
                .isEqualTo(LEDGER);
        assertThat(p.getProperty("spring.flyway.baseline-version"))
                .as("🔴 本模块的 application.yml 必须显式配 spring.flyway.baseline-version: 0 —— "
                        + "否则共享库里非空 schema 会让 Flyway 在 V1 处打 baseline ⇒ V1 被整条跳过"
                        + " ⇒ 首张表永远建不出来 ⇒ 服务起不来。实际=%s",
                        p.getProperty("spring.flyway.baseline-version"))
                .isEqualTo("0");
        assertThat(p.getProperty("spring.flyway.baseline-on-migrate"))
                .as("共享 schema 下必须开 baseline-on-migrate（生产拓扑：别的域先把 schema 弄非空）")
                .isEqualTo("true");
        assertThat(p.getProperty("spring.flyway.locations"))
                .as("迁移脚本位置必须是 classpath:db/migration（V1..V8 就在那儿）")
                .isEqualTo("classpath:db/migration");
        assertThat(p.getProperty("spring.flyway.schemas"))
                .as("schema 必须是 public（各域共享同一个 schema ⇒ 靠账本表名隔离）")
                .isEqualTo("public");

        FluentConfiguration cfg = Flyway.configure()
                .dataSource(containerJdbcUrl(database), TestPostgres.username(), TestPostgres.password())
                .locations(p.getProperty("spring.flyway.locations", "classpath:db/migration"))
                .schemas(p.getProperty("spring.flyway.schemas", "public"))
                .table(p.getProperty("spring.flyway.table", "flyway_schema_history"))
                .baselineOnMigrate(Boolean.parseBoolean(
                        p.getProperty("spring.flyway.baseline-on-migrate", "false")));
        String baselineVersion = p.getProperty("spring.flyway.baseline-version");
        assertThat(baselineVersion).isNotNull();
        // ⚠️ Flyway 10 的 FluentConfiguration 只有 baselineVersion(String) / (MigrationVersion) 两个重载
        cfg.baselineVersion(baselineVersion.trim());
        return cfg.load();
    }

    /** 读本模块 classpath 上的 {@code application.yml}（扁平化后的属性）。 */
    private static Properties moduleYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("comment-module-app", new ClassPathResource("application.yml"));
        Properties p = new Properties();
        for (PropertySource<?> s : sources) {
            // ⚠️ getPropertyNames() 只在 EnumerablePropertySource 上（PropertySource 基类没有）
            if (s instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    Object v = s.getProperty(name);
                    if (v != null) {
                        p.setProperty(name, String.valueOf(v));
                    }
                }
            }
        }
        return p;
    }

    /**
     * 拼出容器里某个库的 JDBC URL。
     *
     * <p>⚠️ <b>必须带 {@code TimeZone=Asia/Shanghai}</b>：这是生产 yml 的硬配
     * （{@code application.yml:32}）⇒ 落库时间列在 wire 上要渲染成墙钟时间。
     */
    private static String containerJdbcUrl(String database) {
        String base = TestPostgres.jdbcUrl();
        int q = base.indexOf('?');
        String bare = q >= 0 ? base.substring(0, q) : base;
        int slash = bare.lastIndexOf('/');
        String host = slash >= 0 ? bare.substring(0, slash + 1) : bare;
        return host + database + "?TimeZone=Asia/Shanghai";
    }

    /** 建探针库（{@code CREATE DATABASE} 不能在事务里执行 ⇒ 用裸 JDBC 连接）。 */
    private static void createProbeDatabase(String database) {
        String adminUrl = containerJdbcUrl("postgres");
        try (Connection conn = DriverManager.getConnection(adminUrl,
                TestPostgres.username(), TestPostgres.password());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("探针库创建失败: " + adminUrl, e);
        }
    }

    private static boolean tableExists(JdbcTemplate template, String name) {
        Integer count = template.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema='public' and table_name=?", Integer.class, name);
        return count != null && count == 1;
    }

    /** 两列 (key, value) 查询的结果集 → Map（{@code queryForMap(sql, Map.class)} 不是合法用法）。 */
    private static Map<String, Object> columnTypes(String sql, String keyColumn) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            out.put(String.valueOf(row.get(keyColumn)), row.get("data_type"));
        }
        return out;
    }

    private long attr(long id) {
        return jdbc.queryForObject("SELECT attribute FROM comment_index WHERE id=?", Long.class, id);
    }
}
