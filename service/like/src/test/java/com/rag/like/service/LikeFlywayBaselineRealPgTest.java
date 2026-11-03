package com.rag.like.service;

import com.rag.like.LikeServiceApplication;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>Flyway 守门：共享 schema 已被别的 app 占用时，like 域的 V1..V6 仍必须全部执行</b>
 * （真 PostgreSQL 16 ＋ 真 Flyway 引擎 ＋ 应用自己的配置）。
 *
 * <h2>🔴 为什么这是本域的一个真陷阱（不是「配了就行」）</h2>
 * <p>本域与其它域<b>共用同一个 {@code public} schema</b>，但各自用<b>独立账本表名</b>
 * （{@code flyway_schema_history_like}）⇒ 当 like 域的 app 启动时：
 * <ul>
 *   <li>schema <b>非空</b>（别的域的表已经在里面了）—— 事实如此；</li>
 *   <li>但 like 域<b>自己的账本表不存在</b> ⇒ Flyway 走「<b>baseline-on-migrate</b>」分支。</li>
 * </ul>
 * <p>⇒ <b>baseline 标记打在哪一版，决定 V1 的 SQL 会不会执行</b>：
 * <table border="1">
 *   <caption>baseline-version 的两种取值</caption>
 *   <tr><th>取值</th><th>结果</th></tr>
 *   <tr>
 *     <td><b>0</b>（本域配置）</td>
 *     <td>baseline 打在 version <b>0</b> ⇒ <b>V1 之后全部迁移照常执行</b> ⇒ 5 张表全部建出来 ✔</td>
 *   </tr>
 *   <tr>
 *     <td>1（Flyway 默认）</td>
 *     <td>baseline 打在 version <b>1</b> ⇒ <b>V1 的 SQL 从未执行</b> ⇒ {@code like_record} <b>永远建不出来</b> ✘</td>
 *   </tr>
 * </table>
 * <p>⚠️ <b>这个失败模式极隐蔽</b>：Flyway <b>不报错</b>、应用<b>照常启动</b>、
 * 日志里只有一行 baseline 提示 ⇒ 直到第一次调用 like 接口报「relation does not exist」才暴露。
 *
 * <h2>🔴 本类同时是「V6 的 {@code $$} 块能不能跑」的守门</h2>
 * <p>V6 用了 PG 的<b>美元引号</b>（{@code DO $$ ... $$} ＋ {@code CREATE FUNCTION ... $$ ... $$}）
 * ⇒ ⇒ 它<b>只在 Flyway 的 PostgreSQL 解析器下</b>才是一条完整语句
 * ⇒ ⇒ 若换回 users 域那套 {@code ScriptUtils} 幂等重放，{@code $$} 会被按 {@code ;} 切碎 ⇒ 直接语法错。
 * <p>⇒ ⇒ 本类的存在同时证明「本域走 Flyway」这条决定是<b>可执行的</b>。
 *
 * <h2>本测试怎么造出「schema 非空 + 自己的账本不存在」这个真实场景</h2>
 * <p>本域其它测试与本测试<b>共用同一个容器</b> ⇒ 主库 {@code first_db} 的 schema 早就非空了，
 * 且 {@code flyway_schema_history_like} 账本也已存在 ⇒ <b>造不出</b>「账本不存在」这一半。
 * ⇒ ⇒ 本测试在<b>同一个容器里另开一个数据库</b> {@code like_flyway_probe}：
 * <ol>
 *   <li>先建库（连维护库 {@code postgres} 执行 {@code CREATE DATABASE}）；</li>
 *   <li>在该库里<b>预置一张与 like 无关的表</b> ⇒ 制造「schema 非空」；</li>
 *   <li>把 {@code spring.datasource.url} 指向它 ⇒ 该库<b>没有</b> like 的账本表
 *       ⇒ 制造「自己的账本不存在」；</li>
 *   <li><b>让应用自己</b>（不是测试代码手写 Flyway）跑迁移 ⇒ 断言走的是<b>应用真实配置</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 断言的是「应用配置」，不是「测试里手写的配置」</h2>
 * <p>本类<b>不</b>手工 new 一个 {@code Flyway} 实例 ⇒ 那样断言的是「我手写的配置对」，
 * <b>证不了「应用真的会那样配」</b>（§3.1 自举陷阱）⇒ 迁移完全由 Spring Boot 的
 * {@code FlywayAutoConfiguration} 按 {@code spring.flyway.*} 执行。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class LikeFlywayBaselineRealPgTest {

    /** 探针库名（与主库 {@code first_db} 同容器、不同库）。 */
    private static final String PROBE_DB = "like_flyway_probe";

    /** 探针库是否已建好（保证建库只发生一次）。 */
    private static boolean PROBE_DB_PREPARED = false;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // ⚠️ 顺序要紧：先注册容器的默认连接（顺带触发容器启动），再把 url 覆盖到探针库。
        TestPostgres.register(registry);
        TestRedis.register(registry);
        registry.add("spring.datasource.url", LikeFlywayBaselineRealPgTest::prepareProbeDatabase);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 建探针库 + 预置一张<b>与 like 无关</b>的表（制造「schema 非空」）。
     *
     * <p>⚠️ {@code CREATE DATABASE} <b>不能在事务里执行</b> ⇒ 用裸 JDBC 连接（autoCommit=true）。
     */
    private static synchronized String prepareProbeDatabase() {
        if (PROBE_DB_PREPARED) {
            return TestPostgres.jdbcUrl().replace("/first_db", "/" + PROBE_DB);
        }
        String adminUrl = TestPostgres.jdbcUrl().replace("/first_db", "/postgres");
        String probeUrl = TestPostgres.jdbcUrl().replace("/first_db", "/" + PROBE_DB);
        try (Connection conn = DriverManager.getConnection(adminUrl, TestPostgres.username(), TestPostgres.password());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + PROBE_DB);
            st.execute("CREATE DATABASE " + PROBE_DB);
        } catch (Exception e) {
            throw new IllegalStateException("探针库创建失败: " + adminUrl, e);
        }
        try (Connection conn = DriverManager.getConnection(probeUrl, TestPostgres.username(), TestPostgres.password());
             Statement st = conn.createStatement()) {
            // ⚠️ 关键：预置一张**别的 app 的**表 ⇒ schema 非空 ⇒ Flyway 走 baseline-on-migrate 分支
            st.execute("CREATE TABLE probe_other_app_table (id BIGSERIAL PRIMARY KEY, note TEXT)");
        } catch (Exception e) {
            throw new IllegalStateException("探针库预置表失败: " + probeUrl, e);
        }
        PROBE_DB_PREPARED = true;
        return probeUrl;
    }

    @Test
    @DisplayName("🔴 schema 已被别的 app 占用时，like 的 V1..V6 仍必须全部执行（baseline-version: 0 生效）")
    void migrationRunsEvenWhenSchemaIsNotEmpty() {
        // 前置事实：schema 确实非空（预置了别的 app 的表）
        Integer unrelated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM probe_other_app_table", Integer.class);
        assertThat(unrelated).isNotNull();

        // 🔴 核心断言：5 张表全部被建出来
        for (String table : new String[]{"like_record", "like_consume_inbox", "like_outbox_event",
                "like_domain_fact_outbox", "like_fact_stream"}) {
            Integer exists = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?
                    """, Integer.class, table);
            assertThat(exists)
                    .as("表 %s 必须被迁移建出来 —— 若为 0 说明 baseline 打在了 version 1 "
                            + "（V1 从未执行）⇒ 第一次调接口就会报 relation does not exist", table)
                    .isEqualTo(1);
        }

        // 账本表名必须是本域独立的那个（不是 schema 级的 flyway_schema_history）
        Integer ledger = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'flyway_schema_history_like'
                """, Integer.class);
        assertThat(ledger)
                .as("账本表必须是 like 域独立的 flyway_schema_history_like（共用 schema 级账本会撞号）")
                .isEqualTo(1);

        Integer sharedLedger = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'
                """, Integer.class);
        assertThat(sharedLedger)
                .as("不得使用 schema 级账本 flyway_schema_history（AGENTS.md §六 的核心问题）")
                .isZero();

        // 账本里 V1..V6 六条都必须是 success
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history_like
                WHERE version IN ('1', '2', '3', '4', '5', '6') AND success = true
                """, Integer.class);
        assertThat(applied)
                .as("V1..V6 六条都必须在账本里标记为 success")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("🔴 V6 的列类型修正必须生效：delivery_status / retry_count 必须是 integer（不是 bigint）")
    void v6NarrowsColumnTypes() {
        for (String column : new String[]{"delivery_status", "retry_count"}) {
            String dataType = jdbcTemplate.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'like_domain_fact_outbox'
                      AND column_name = ?
                    """, String.class, column);
            assertThat(dataType)
                    .as("%s 必须是 integer（Go 的 AutoMigrate 对 int32 的产物）；"
                            + "bigint 说明 V6 的 ALTER 没跑 ⇒ 与 Go 侧结构不一致", column)
                    .isEqualTo("integer");
        }
    }

    @Test
    @DisplayName("🔴 V6 的 NOT VALID CHECK 与不可变触发器必须存在（Go 侧 002/003 的等价物）")
    void v6ConstraintsAndTriggerExist() {
        Integer check = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'like_new_fact_has_dc_wire'
                """, Integer.class);
        assertThat(check)
                .as("Go 侧 002 的 CHECK (aggregate_id/fact_version/delivery_envelope 三件套) 必须落地")
                .isEqualTo(1);

        Integer notValid = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'like_new_fact_has_dc_wire' AND NOT convalidated
                """, Integer.class);
        assertThat(notValid)
                .as("NOT VALID 必须逐字照抄：改成 VALID 会在存量行不满足时直接迁移失败（Go 没有这个行为）")
                .isEqualTo(1);

        Integer versionRange = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'like_fact_stream_version_range'
                """, Integer.class);
        assertThat(versionRange)
                .as("Go 侧 002 的 version 范围 CHECK（2^53-1，JSON-safe 整数上界）必须落地")
                .isEqualTo(1);

        Integer trigger = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger
                WHERE tgname = 'like_domain_fact_identity_immutable' AND NOT tgisinternal
                """, Integer.class);
        assertThat(trigger)
                .as("Go 侧 003 的「信封不可变」触发器必须落地")
                .isEqualTo(1);

        Integer deliveryIndex = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'idx_like_fact_delivery'
                """, Integer.class);
        assertThat(deliveryIndex)
                .as("Go 侧 003 的投递复合索引 (delivery_status, created_at, event_id) 必须落地")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 V1 的唯一索引 uk_user_target 必须存在（幂等的物理载体）")
    void uniqueIndexExists() {
        Integer idx = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uk_user_target'
                """, Integer.class);
        assertThat(idx)
                .as("uk_user_target(user_id, target_type, target_id) 是 MQ 消费幂等与行锁的物理载体")
                .isEqualTo(1);
    }
}
