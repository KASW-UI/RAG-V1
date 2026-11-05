package com.rag.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.domain.service.CommentDomainFactService;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.comment.mapper.CommentFactStreamMapper;
import com.rag.comment.support.TestPostgres;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>🔴 <b>事实载荷的 {@code event_time} 必须归一化到 UTC</b> —— 守门网。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>探针库隔离</b>：B 侧本类自起一个 PG 容器；C 侧共用<b>进程内单例</b>容器
 *       ⇒ 本类在同容器里另开一个探针库 {@code comment_etime_probe}，
 *       避免与其它测试类的 {@code comment_fact_stream} / {@code comment_domain_fact_outbox} 行互撞。</li>
 *   <li><b>新增一条「两个时间基准并存」的守门</b>（B 侧只验了 UTC 一侧）：
 *       事实载荷走 <b>UTC</b>（RFC3339Nano，{@code …Z}），
 *       而 wire 上渲染落库时间走 <b>Asia/Shanghai 墙钟</b>
 *       （{@code yyyy-MM-dd HH:mm:ss}，B 侧 Go 的 {@code "2006-01-02 15:04:05"}）
 *       ⇒ ⇒ 本类把<b>两个基准同时钉住</b>，防住「有人为了统一而把其中一个改掉」。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。B 侧 {@code CommentFactEventTimeGateTest} 的 5 条断言逐条保留
 * （带偏移 ⇒ UTC ／ 已是 UTC ⇒ 不变 ／ 纳秒精度 ／ null ⇒ now ／ 非法 ⇒ 原样保留）。
 *
 * <p>⚠️ <b>一条必须说清的口径差异（不是漂移，是 Go 与 Java 的格式器差异）</b>：
 * Go 的 {@code time.RFC3339Nano} 会把小数秒<b>裁到最短</b>（{@code .1}），
 * 而 Java 的 {@code Instant#toString()} 只输出 <b>0/3/6/9</b> 位小数（{@code .100}）
 * ⇒ 二者在「小数秒末尾有 0」时字节不同。
 * ⇒ 本类<b>照抄 B 侧 Java 测试的断言</b>（9 位纳秒，两侧一致），
 * <b>不</b>把 Go/Java 的格式器差异写成断言（否则会把一条继承自 B 侧的差异固化成 C 侧的守门）。
 * ⇒ 该差异的完整说明见 {@code docs/DRIFT/comment/} 的台账行。</p>
 *
 * <h2>3. B 出处</h2>
 * <ul>
 *   <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentFactEventTimeGateTest.java}
 *       （211 行 · 本类的逐条 oracle）</li>
 *   <li>Go 侧：{@code service/comment/rpc/internal/model/domain_fact.go:95-97}
 *       （{@code if fact.EventTime.IsZero() { fact.EventTime = time.Now().UTC() }
 *       fact.EventTime = fact.EventTime.UTC()  // ← 无条件归一化
 *       fact.OccurredAt = fact.EventTime}）
 *       ＋ {@code :132}（信封 {@code occurred_at}）</li>
 *   <li>C 侧实现：{@code com.rag.comment.domain.service.CommentDomainFactService#normalizeEventTimeToUtc}
 *       （逐字抄 B 侧 {@code comment-rpc/…/rpc/service/CommentDomainFactService.java:198-215}）</li>
 * </ul>
 *
 * <h2>🔴 为什么必须真 PG ＋ 真 MyBatis</h2>
 * <p>本类要验的是<b>落库的那份字节</b>（payload jsonb + delivery_envelope jsonb）
 * ⇒ 必须真的走 {@code INSERT … ON CONFLICT … RETURNING} 与 jsonb 列，
 * mock 数据层测不到任何东西（AGENTS.md §3.3）。
 */
class CommentFactEventTimeRealPgTest {

    /** 本类专用探针库（与其它测试类的库互不干扰）。 */
    private static final String PROBE_DB = "comment_etime_probe";

    private static JdbcTemplate jdbc;
    private static CommentDomainFactService factService;

    @BeforeAll
    static void setUp() throws IOException {
        createProbeDatabase(PROBE_DB);
        migrateWithModuleConfig(PROBE_DB);

        DataSource dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), containerJdbcUrl(PROBE_DB),
                TestPostgres.username(), TestPostgres.password());
        jdbc = new JdbcTemplate(dataSource);

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("eventtime-gate",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(CommentDomainFactOutboxMapper.class);
        configuration.addMapper(CommentFactStreamMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        factService = new CommentDomainFactService(
                template.getMapper(CommentDomainFactOutboxMapper.class),
                template.getMapper(CommentFactStreamMapper.class),
                new ObjectMapper());
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM comment_domain_fact_outbox");
        jdbc.update("DELETE FROM comment_fact_stream");
    }

    /** 造一个最小可用的 fact（Go 的必填四项 + 业务字段）。 */
    private static CommentDomainFactService.CommentFact fact(String aggregateId, String eventTime) {
        CommentDomainFactService.CommentFact f = new CommentDomainFactService.CommentFact();
        f.eventId = "evt-" + aggregateId;
        f.eventType = "community.comment.created";
        f.aggregateId = aggregateId;
        f.operationId = "op-" + aggregateId;
        f.subjectRef = "rtw.identity/platform/2002";
        f.operatorRef = "rtw.identity/platform/1001";
        f.targetType = "article";
        f.targetId = "a-diff";
        f.operation = "created";
        f.sourceRef = "src-" + aggregateId;
        f.commentId = "2113939338147643392";
        f.eventTime = eventTime;
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String column, String aggregateId) {
        String raw = jdbc.queryForObject(
                "SELECT " + column + "::text FROM comment_domain_fact_outbox WHERE aggregate_id = ?",
                String.class, aggregateId);
        try {
            return new ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ====================================================================================
    // ① D3：带偏移的 event_time ⇒ 载荷与信封**全部**归一化到 UTC
    // ====================================================================================

    @Test
    @DisplayName("🔴 D3：event_time 带 +08:00 偏移 ⇒ 载荷与信封**全部**归一化到 UTC")
    void eventTimeWithOffsetIsNormalizedToUtcInPayloadAndEnvelope() {
        factService.append(fact("c4-agg-d3-1", "2026-10-24T18:00:00+08:00"));

        Map<String, Object> payload = readJson("payload", "c4-agg-d3-1");
        Map<String, Object> envelope = readJson("delivery_envelope", "c4-agg-d3-1");

        assertEquals("2026-10-24T10:00:00Z", payload.get("event_time"),
                "Go domain_fact.go:96 fact.EventTime = fact.EventTime.UTC() ⇒ 载荷必须是 UTC");
        assertEquals("2026-10-24T10:00:00Z", payload.get("occurred_at"),
                "Go domain_fact.go:97 fact.OccurredAt = fact.EventTime（归一化之后）");
        assertEquals("2026-10-24T10:00:00Z", envelope.get("occurred_at"),
                "Go domain_fact.go:132 OccurredAt: fact.OccurredAt.Format(time.RFC3339Nano)");
        assertNotEquals("2026-10-24T18:00:00+08:00", payload.get("event_time"),
                "不能保留调用方给的本地偏移");
    }

    // ====================================================================================
    // ② D3：本来就是 UTC ⇒ 原样不动
    // ====================================================================================

    @Test
    @DisplayName("🔴 D3：event_time 本来就是 UTC ⇒ **原样不动**（不许被二次改写）")
    void eventTimeAlreadyUtcIsUnchanged() {
        factService.append(fact("c4-agg-d3-2", "2026-10-24T10:00:00Z"));

        Map<String, Object> payload = readJson("payload", "c4-agg-d3-2");
        assertEquals("2026-10-24T10:00:00Z", payload.get("event_time"));
        assertEquals("2026-10-24T10:00:00Z", payload.get("occurred_at"));
    }

    // ====================================================================================
    // ③ D3：纳秒精度保留
    // ====================================================================================

    @Test
    @DisplayName("🔴 D3：纳秒精度保留（Go RFC3339Nano 语义）")
    void nanoPrecisionMatchesGoRfc3339Nano() {
        factService.append(fact("c4-agg-d3-3", "2026-10-24T18:00:00.123456789+08:00"));

        Map<String, Object> payload = readJson("payload", "c4-agg-d3-3");
        assertEquals("2026-10-24T10:00:00.123456789Z", payload.get("event_time"));
    }

    // ====================================================================================
    // ④ D3：null ⇒ 取当前时刻
    // ====================================================================================

    @Test
    @DisplayName("🔴 D3：event_time 为 null ⇒ 取当前时刻（Go 的 IsZero 分支）")
    void nullEventTimeBecomesNow() {
        factService.append(fact("c4-agg-d3-4", null));

        Map<String, Object> payload = readJson("payload", "c4-agg-d3-4");
        String eventTime = String.valueOf(payload.get("event_time"));
        assertTrue(eventTime.endsWith("Z"),
                "Go 用 time.Now().UTC() ⇒ 必须以 Z 结尾（不能落到 JVM 默认时区）⇒ 实际 " + eventTime);
        assertEquals(eventTime, payload.get("occurred_at"), "Go: OccurredAt = EventTime");
        Instant parsed = Instant.parse(eventTime);
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(parsed, Instant.now())) < 120,
                "应当接近当前时刻 ⇒ 实际 " + eventTime);
    }

    // ====================================================================================
    // ⑤ D3：无法解析 ⇒ 原样保留（不新增 Go 没有的失败模式）
    // ====================================================================================

    @Test
    @DisplayName("🔴 D3：无法解析的 event_time ⇒ **原样保留**（不新增 Go 没有的失败模式）")
    void malformedEventTimeIsKeptAsIs() {
        factService.append(fact("c4-agg-d3-5", "not-a-time"));

        Map<String, Object> payload = readJson("payload", "c4-agg-d3-5");
        assertEquals("not-a-time", payload.get("event_time"),
                "Go 的 EventTime 是 time.Time（不可能非法）⇒ 抛异常等于新增一个 Go 没有的失败模式"
                        + "（会回滚事务）。⇒ 原样保留，交给投递侧 RFC3339 校验判 BLOCKED。");
    }

    // ====================================================================================
    // ⑥ 两个时间基准并存（事实走 UTC；wire 走 Asia/Shanghai 墙钟）
    // ====================================================================================

    @Test
    @DisplayName("🔴 两个时间基准必须并存：事实载荷走 UTC，wire 渲染走 Asia/Shanghai 墙钟")
    void utcFactBasisAndLocalWireBasisCoexist() throws IOException {
        // --- ① 事实基准：UTC（RFC3339Nano，Z 结尾）
        factService.append(fact("c4-agg-basis", "2026-10-24T18:00:00+08:00"));
        Map<String, Object> payload = readJson("payload", "c4-agg-basis");
        assertEquals("2026-10-24T10:00:00Z", payload.get("event_time"),
                "事实载荷的时间基准是 UTC（Go domain_fact.go:96）");

        // --- ② wire 基准的物理载体：生产 JDBC URL 显式钉了 TimeZone=Asia/Shanghai
        //     （C 侧 application.yml:32 · B 侧 comment-rpc/…/application.yml:45-49 · Go comment.yaml:9）
        Properties p = moduleYaml();
        assertThat(p.getProperty("spring.datasource.url"))
                .as("wire 时间基准的物理载体：JDBC URL 必须带 TimeZone=Asia/Shanghai（落库时间列按墙钟渲染）")
                .contains("TimeZone=Asia/Shanghai");

        // --- ③ 真 PG 会话的时区必须真的是 Asia/Shanghai（证明 ② 真的生效）
        String sessionTz = jdbc.queryForObject("SHOW TimeZone", String.class);
        assertThat(sessionTz)
                .as("本测试的连接串必须带 TimeZone=Asia/Shanghai ⇒ PG session 时区就是它")
                .isEqualTo("Asia/Shanghai");

        // --- ④ 同一瞬时在 wire 基准下渲染成 Asia/Shanghai 墙钟（Go 的 \"2006-01-02 15:04:05\"）
        String wireForm = jdbc.queryForObject(
                "SELECT to_char('2026-10-24T10:00:00Z'::timestamptz, 'YYYY-MM-DD HH24:MI:SS')", String.class);
        assertEquals("2026-10-24 18:00:00", wireForm,
                "wire 形态 = Asia/Shanghai 墙钟 + yyyy-MM-dd HH:mm:ss（C 侧 CommentDomainService#WIRE_TIME"
                        + " 对齐 B 侧 Go 的 \"2006-01-02 15:04:05\"）");

        // --- ⑤ 两个基准**同时**存在且指向同一瞬时（不是互相替换）
        assertEquals("2026-10-24T10:00:00Z", payload.get("event_time"));
        assertEquals(wireForm, "2026-10-24 18:00:00");
    }

    @Test
    @DisplayName("🔴 信封与载荷的 occurred_at 必须是同一时刻（且都是 UTC）")
    void envelopeAndPayloadOccurredAtAreTheSameInstant() {
        factService.append(fact("c4-agg-occurred", "2026-10-24T18:00:00+08:00"));

        Map<String, Object> payload = readJson("payload", "c4-agg-occurred");
        Map<String, Object> envelope = readJson("delivery_envelope", "c4-agg-occurred");

        assertEquals(payload.get("occurred_at"), envelope.get("occurred_at"),
                "Go domain_fact.go:97 + :132 ⇒ 载荷与信封的 occurred_at 必须是同一个值");
        assertEquals("2026-10-24T10:00:00Z", envelope.get("occurred_at"));
    }

    // ====================================================================================
    // 基础设施
    // ====================================================================================

    /** 用本模块 {@code application.yml} 的真实 flyway 配置跑迁移（不硬编码）。 */
    private static void migrateWithModuleConfig(String database) throws IOException {
        Properties p = moduleYaml();
        assertThat(p.getProperty("spring.flyway.table"))
                .as("读到的必须是本模块自己的 application.yml（账本表名对不上 ⇒ 可能读串了）")
                .isEqualTo("flyway_schema_history_comment");
        Flyway.configure()
                .dataSource(containerJdbcUrl(database), TestPostgres.username(), TestPostgres.password())
                .locations(p.getProperty("spring.flyway.locations", "classpath:db/migration"))
                .schemas(p.getProperty("spring.flyway.schemas", "public"))
                .table(p.getProperty("spring.flyway.table", "flyway_schema_history"))
                .baselineOnMigrate(Boolean.parseBoolean(
                        p.getProperty("spring.flyway.baseline-on-migrate", "false")))
                .baselineVersion(String.valueOf(p.getProperty("spring.flyway.baseline-version", "0")).trim())
                .load()
                .migrate();
    }

    private static Properties moduleYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("comment-module-app", new ClassPathResource("application.yml"));
        Properties p = new Properties();
        for (PropertySource<?> s : sources) {
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

    private static String containerJdbcUrl(String database) {
        String base = TestPostgres.jdbcUrl();
        int q = base.indexOf('?');
        String bare = q >= 0 ? base.substring(0, q) : base;
        int slash = bare.lastIndexOf('/');
        String host = slash >= 0 ? bare.substring(0, slash + 1) : bare;
        return host + database + "?TimeZone=Asia/Shanghai";
    }

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
}
