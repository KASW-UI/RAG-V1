package com.rag.follow.service;

import com.rag.follow.FollowServiceApplication;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;
import com.rag.follow.support.TestRedis;

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
 * <p><b>Flyway 守门：共享 schema 已被别的 app 占用时，follow 域的 V1 仍必须执行</b>。
 *
 * <h2>🔴 为什么这是本域的一个真陷阱（不是「配了就行」）</h2>
 * <p>本域与其它域<b>共用同一个 {@code public} schema</b>，但各自用<b>独立账本表名</b>
 * （{@code flyway_schema_history_follow}）⇒ 当 follow 域的 app 启动时：
 * <ul>
 *   <li>schema <b>非空</b>（like/comment/… 的表已经在里面了）—— 事实如此；</li>
 *   <li>但 follow 域<b>自己的账本表不存在</b> ⇒ Flyway 走「<b>baseline-on-migrate</b>」分支。</li>
 * </ul>
 * <p>⇒ <b>baseline 标记打在哪一版，决定 V1 的 SQL 会不会执行</b>：
 * <table border="1">
 *   <caption>baseline-version 的两种取值</caption>
 *   <tr><th>取值</th><th>结果</th></tr>
 *   <tr>
 *     <td><b>0</b>（本域配置）</td>
 *     <td>baseline 打在 version <b>0</b> ⇒ <b>V1 之后全部迁移照常执行</b> ⇒ {@code follow_relation} 建出来 ✔</td>
 *   </tr>
 *   <tr>
 *     <td>1（Flyway 默认）</td>
 *     <td>baseline 打在 version <b>1</b> ⇒ <b>V1 的 SQL 从未执行</b> ⇒ {@code follow_relation} <b>永远建不出来</b> ✘</td>
 *   </tr>
 * </table>
 * <p>⚠️ <b>这个失败模式极隐蔽</b>：Flyway <b>不报错</b>、应用<b>照常启动</b>、
 * 日志里只有一行 baseline 提示 ⇒ 直到第一次调用 follow 接口报「relation does not exist」才暴露。
 *
 * <h2>本测试怎么造出「schema 非空 + 自己的账本不存在」这个真实场景</h2>
 * <p>本域其它测试与本测试<b>共用同一个容器</b> ⇒ 主库 {@code first_db} 的 schema 早就非空了，
 * 且 {@code flyway_schema_history_follow} 账本也已存在 ⇒ <b>造不出</b>「账本不存在」这一半。
 * ⇒ ⇒ 本测试在<b>同一个容器里另开一个数据库</b> {@code follow_flyway_probe}：
 * <ol>
 *   <li>先建库（连维护库 {@code postgres} 执行 {@code CREATE DATABASE}）；</li>
 *   <li>在该库里<b>预置一张与 follow 无关的表</b> ⇒ 制造「schema 非空」；</li>
 *   <li>把 {@code spring.datasource.url} 指向它 ⇒ 该库<b>没有</b> follow 的账本表
 *       ⇒ 制造「自己的账本不存在」；</li>
 *   <li><b>让应用自己</b>（不是测试代码手写 Flyway）跑迁移 ⇒ 断言走的是<b>应用真实配置</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 断言的是「应用配置」，不是「测试里手写的配置」</h2>
 * <p>本类<b>不</b>手工 new 一个 {@code Flyway} 实例 ⇒ 那样断言的是「我手写的配置对」，
 * <b>证不了「应用真的会那样配」</b>（§3.1 自举陷阱）⇒ 迁移完全由 Spring Boot 的
 * {@code FlywayAutoConfiguration} 按 {@code spring.flyway.*} 执行。
 */
@SpringBootTest(classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowFlywayBaselineRealPgTest {

    /** 探针库名（与主库 {@code first_db} 同容器、不同库）。 */
    private static final String PROBE_DB = "follow_flyway_probe";

    /** 探针库是否已建好（保证建库只发生一次）。 */
    private static boolean PROBE_DB_PREPARED = false;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // ⚠️ 顺序要紧：先注册容器的默认连接（顺带触发容器启动），再把 url 覆盖到探针库。
        TestPostgres.register(registry);
        TestRedis.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
        registry.add("spring.datasource.url", FollowFlywayBaselineRealPgTest::prepareProbeDatabase);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 建探针库 + 预置一张<b>与 follow 无关</b>的表（制造「schema 非空」）。
     *
     * <p>⚠️ {@code CREATE DATABASE} <b>不能在事务里执行</b> ⇒ 用裸 JDBC 连接（autoCommit=true）。
     *
     * <p>⚠️ <b>幂等保护</b>：{@code registry.add(name, supplier)} 的 supplier 在
     * Spring Boot 内部有值缓存，但<b>不保证只被求值一次</b>（不同读取路径可能各触发一次）
     * ⇒ ⇒ 这里用 {@link #PROBE_DB_PREPARED} 标志保证<b>建库只发生一次</b>
     * —— 否则第二次 {@code DROP DATABASE} 会把正在被连接使用的库删掉。
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
    @DisplayName("🔴 schema 已被别的 app 占用时，follow 的 V1 仍必须执行（baseline-version: 0 生效）")
    void migrationRunsEvenWhenSchemaIsNotEmpty() {
        // 前置事实：schema 确实非空（预置了别的 app 的表）
        Integer unrelated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM probe_other_app_table", Integer.class);
        assertThat(unrelated).isNotNull();

        // 🔴 核心断言：V1 的 SQL **执行了** ⇒ follow_relation 表存在
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'follow_relation'
                """, Integer.class);
        assertThat(exists)
                .as("follow_relation 必须被 V1 建出来 —— 若为 0 说明 baseline 打在了 version 1 "
                        + "（V1 从未执行）⇒ 第一次调接口就会报 relation does not exist")
                .isEqualTo(1);

        // V2 同理
        Integer blockExists = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'block_relation'
                """, Integer.class);
        assertThat(blockExists).isEqualTo(1);

        // 账本表名必须是本域独立的那个（不是 schema 级的 flyway_schema_history）
        Integer ledger = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'flyway_schema_history_follow'
                """, Integer.class);
        assertThat(ledger)
                .as("账本表必须是 follow 域独立的 flyway_schema_history_follow（共用 schema 级账本会撞号）")
                .isEqualTo(1);

        // 账本里必须有 V1 / V2 两条已应用的记录
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history_follow
                WHERE version IN ('1', '2') AND success = true
                """, Integer.class);
        assertThat(applied)
                .as("V1 与 V2 都必须在账本里标记为 success")
                .isEqualTo(2);
    }
}
