package com.rag.common.persistence;

import com.rag.common.observability.DeadlineState;
import com.rag.common.observability.ObservabilityProperties;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>「DB 超时 ⇒ 1015（而不是 5001）」这条链的<b>可判别</b>守门</b>。
 *
 * <h2>这条链上有 4 个环节，缺一即失败</h2>
 * <pre>
 *   ① 内层预算（min(http-timeout, rpc-timeout)）     ⇒ ObservabilityProperties.resolveInnerBudget()
 *   ② SQL 被 deadline 取消（pg 57014）                ⇒ DeadlineQueryTimeoutInjector（StatementHandler 拦截）
 *   ③ 57014 → gRPC DEADLINE_EXCEEDED                 ⇒ SqlTimeoutStatusTranslator（Executor 拦截）
 *   ④ MyBatis 的 PersistenceException 包装被解包      ⇒ StatusAwareSqlSession（SqlSessionFactory 装饰）
 * </pre>
 *
 * <h2>⚠️ 为什么每条断言都写成「不是 PersistenceException」</h2>
 * <p>MyBatis 的 {@code DefaultSqlSession} 会把 mapper 调用里的<b>任何</b>异常包成
 * {@code PersistenceException}（源码实测，MyBatis 3.5.19）。
 * ⇒ ⇒ 若只断言「抛了异常」，那么<b>翻译器坏掉</b>时测试<b>照样绿</b>（包一层照样是异常）
 * ⇒ ⇒ 每条断言都必须写明「到达调用方的是一个 {@link StatusRuntimeException}
 * 且 code = {@code DEADLINE_EXCEEDED}」⇒ ⇒ <b>翻译器一坏，红</b>。
 *
 * <h2>⚠️ 为什么必须真 PG（mock / H2 都验不到要验的东西）</h2>
 * <p>要验的三件事<b>全部长在 PG 上</b>：
 * <ul>
 *   <li>{@code 57014}（canceling statement due to user request）—— <b>只有真 PG 被 CancelRequest
 *       取消</b>才会产出这个 SQLState；</li>
 *   <li>{@code pg_sleep} + 驱动 {@code setQueryTimeoutMs} ⇒ <b>取消时刻 == deadline 时刻</b>
 *       —— 只有真驱动的取消定时器才有这个语义；</li>
 *   <li>「PG 不可用 ⇒ Hikari 取连接超时（{@code 08001} + {@code request timed out after}）」
 *       —— 只有真容器停了才会走这条分支。</li>
 * </ul>
 *
 * <h2>B 出处（判定基准）</h2>
 * <p>B 侧 {@code service/common/src/main/java/com/rag/common/persistence/SqlTimeoutStatusTranslator.java}
 * 的类头（「ctx 到期 ⇒ 落 api logic 的 {@code default} 分支 ⇒ <b>1015 CODE_SERVER_BUSY</b>」）
 * ＋ {@code zrpc.RpcServerConf.Timeout = 2s}（= 本仓 {@code observability.rpc-timeout}）。
 */
@Testcontainers
class DeadlineBudgetPersistencePgTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("deadline_probe")
            .withUsername("probe")
            .withPassword("probe");

    /** 内层预算 = min(http 3s, rpc 2s) = <b>2s</b>（B 侧 gRPC 中间件那一层）。 */
    private static final Duration INNER_BUDGET = Duration.ofSeconds(2);

    /** 游标语句的完整 id（MyBatis 的命名空间 = mapper 接口的全限定名）。 */
    private static final String CURSOR_STATEMENT =
            DeadlineBudgetPersistencePgTest.ProbeMapper.class.getName() + ".selectRowsCursor";

    private static HikariDataSource hikari;
    private static DataSource effectiveDataSource;
    private static SqlSessionFactory factory;
    private static ObjectProvider<ObservabilityProperties> propertiesProvider;
    /** ⚠️ 必须在容器**停之前**取（Testcontainers：停机后取 mapped port 会抛 IllegalState）。 */
    private static String jdbcUrl;

    @BeforeAll
    static void setUp() throws Exception {
        // ① 内层预算：ObservabilityProperties.resolveInnerBudget() = min(http-timeout, rpc-timeout)
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setHttpTimeout(Duration.ofSeconds(3));
        properties.setRpcTimeout(Duration.ofSeconds(2));
        assertThat(properties.resolveInnerBudget()).isEqualTo(INNER_BUDGET);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("observabilityProperties", properties);
        propertiesProvider = beanFactory.getBeanProvider(ObservabilityProperties.class);

        // ② 连接池 + BPP（收紧 connectionTimeout + 包一层翻译）
        jdbcUrl = PG.getJdbcUrl();
        hikari = new HikariDataSource();
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(PG.getUsername());
        hikari.setPassword(PG.getPassword());
        hikari.setConnectionTimeout(TimeUnit.SECONDS.toMillis(30));
        hikari.setMaximumPoolSize(4);

        Object processed = new DeadlineBoundedConnectionTimeout(propertiesProvider)
                .postProcessAfterInitialization(hikari, "dataSource");
        effectiveDataSource = (DataSource) processed;

        // ③ 真 MyBatis：两个拦截器 + 被装饰的工厂
        factory = buildFactory(effectiveDataSource);

        try (Connection connection = effectiveDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS probe_row (id bigint primary key)");
            statement.execute("INSERT INTO probe_row (id) VALUES (1) ON CONFLICT DO NOTHING");
        }
    }

    @AfterAll
    static void tearDown() {
        if (hikari != null) {
            hikari.close();
        }
    }

    /** 造一个「真 MyBatis + 两个拦截器 + 被装饰的工厂」。 */
    private static SqlSessionFactory buildFactory(DataSource dataSource) {
        Configuration configuration =
                new Configuration(new Environment("probe", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ProbeMapper.class);
        configuration.addInterceptor(new DeadlineQueryTimeoutInjector());
        configuration.addInterceptor(new SqlTimeoutStatusTranslator());
        return new StatusAwareSqlSessionFactory(new SqlSessionFactoryBuilder().build(configuration));
    }

    // ── ① 内层预算落到连接池 ─────────────────────────────────────────────

    @Test
    @DisplayName("🔴 内层预算（min(http,rpc)=2s）必须把连接池的 connectionTimeout 从 30s 收到 2s")
    void connectionTimeoutIsBoundedByInnerBudget() {
        assertThat(hikari.getConnectionTimeout())
                .as("Hikari 默认 connectionTimeout=30s ⇒ 若不被收紧，PG 停时会干等 30s "
                        + "⇒ 响应面变 503 + 裸文本（实测旧行为）")
                .isEqualTo(INNER_BUDGET.toMillis());
    }

    @Test
    @DisplayName("🔴 包装后的 DataSource 必须是 DeadlineTranslatingDataSource（Boot 3.5 能解包 ⇒ 不丢池指标）")
    void effectiveDataSourceIsTranslating() {
        assertThat(effectiveDataSource).isInstanceOf(DeadlineTranslatingDataSource.class);
    }

    // ── ② SQL 被取消 ⇒ 57014 ⇒ DEADLINE_EXCEEDED（且不被 MyBatis 包住） ──

    @Test
    @DisplayName("🔴 SQL 被 deadline 取消（pg 57014）⇒ 调用方收到 StatusRuntimeException(DEADLINE_EXCEEDED)"
            + "（不是 PersistenceException —— 否则域层会把它改写成 5001）")
    void cancelledSqlBecomesDeadlineExceeded() {
        try (SqlSession session = factory.openSession()) {
            assertThatThrownBy(() -> {
                try (DeadlineState.Scope scope =
                             DeadlineState.bindRemainingNanos(TimeUnit.MILLISECONDS.toNanos(300))) {
                    session.getMapper(ProbeMapper.class).sleep(30_000L);
                }
            })
                    .as("翻译器坏掉时这里会抛 PersistenceException ⇒ 断言必须写明类型与 code")
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                            .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
        }
    }

    @Test
    @DisplayName("🔴 游标查询路径（Executor.queryCursor = MP 的 BaseMapper.selectOne 走的路）"
            + "同样必须翻译成 DEADLINE_EXCEEDED —— 此前 @Intercepts 漏签 ⇒ 实测落 5001")
    void cancelledCursorQueryBecomesDeadlineExceeded() {
        try (SqlSession session = factory.openSession()) {
            assertThatThrownBy(() -> {
                try (DeadlineState.Scope scope =
                             DeadlineState.bindRemainingNanos(TimeUnit.MILLISECONDS.toNanos(300))) {
                    try (Cursor<Map<String, Object>> cursor =
                                 session.selectCursor(CURSOR_STATEMENT, Map.of("millis", 30_000L))) {
                        cursor.iterator().hasNext();
                    }
                }
            })
                    .as("漏签 queryCursor 时这里会抛 PersistenceException ⇒ 断言必须写明类型与 code")
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                            .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
        }
    }

    // ── ③ PG 不可用 ⇒ 取连接超时 ⇒ DEADLINE_EXCEEDED ────────────────────

    @Test
    @DisplayName("🔴 PG 不可用 ⇒ 取连接超时 ⇒ 调用方收到 StatusRuntimeException(DEADLINE_EXCEEDED)"
            + "（不是 PersistenceException / 不是 5001）")
    void connectionAcquisitionTimeoutBecomesDeadlineExceeded() throws Exception {
        PG.stop();
        HikariDataSource deadPool = null;
        try {
            // ⚠️ **必须新建一个池**：容器停掉后，老池里可能还留着「看起来活着、其实已死」的连接
            //    ⇒ ⇒ 借到它 ⇒ 失败在「I/O error」而不是「borrow 超时」⇒ ⇒ 走的不是被测分支。
            //    新池 initializationFailTimeout=-1 ⇒ 启动时不强连 ⇒ 借连接时才会等 connectionTimeout。
            deadPool = new HikariDataSource();
            deadPool.setJdbcUrl(jdbcUrl);
            deadPool.setUsername(PG.getUsername());
            deadPool.setPassword(PG.getPassword());
            deadPool.setInitializationFailTimeout(-1L);
            deadPool.setConnectionTimeout(TimeUnit.SECONDS.toMillis(30));
            deadPool.setMaximumPoolSize(2);

            Object processed = new DeadlineBoundedConnectionTimeout(propertiesProvider)
                    .postProcessAfterInitialization(deadPool, "dataSource");
            SqlSessionFactory deadFactory = buildFactory((DataSource) processed);

            try (SqlSession session = deadFactory.openSession()) {
                long t0 = System.nanoTime();
                assertThatThrownBy(() -> session.getMapper(ProbeMapper.class).selectRows())
                        .as("翻译层坏掉时会抛 PersistenceException(08001) ⇒ 断言必须写明类型与 code")
                        .isInstanceOf(StatusRuntimeException.class)
                        .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                                .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
                long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;
                assertThat(elapsedMillis)
                        .as("必须在内层预算（2s）附近失败，而不是干等到 HTTP 的 3s 预算（⇒ 503 裸文本面）")
                        .isBetween(1_500L, 4_000L);
            }
        } finally {
            if (deadPool != null) {
                deadPool.close();
            }
            PG.start();
        }
    }

    // ── ④ 「非超时」的异常不得被误翻 ─────────────────────────────────────

    @Test
    @DisplayName("🔴 非 DEADLINE_EXCEEDED 的异常不得被解包改写（爆炸半径最小）")
    void nonDeadlineExceptionsAreUntouched() {
        SqlSessionFactory raw = new SqlSessionFactoryBuilder().build(new Configuration(
                new Environment("probe", new JdbcTransactionFactory(), new UnpooledDataSource())));
        StatusAwareSqlSessionFactory decorated = new StatusAwareSqlSessionFactory(raw);
        try (SqlSession session = decorated.openSession()) {
            assertThatThrownBy(() -> session.selectList("does.not.exist"))
                    .as("表不存在 ⇒ 普通的 PersistenceException ⇒ 不得被改写成 status")
                    .isNotInstanceOf(StatusRuntimeException.class);
        }
    }

    // ── 测试用 mapper ────────────────────────────────────────────────────

    interface ProbeMapper {

        @Select("SELECT pg_sleep(#{millis})")
        void sleep(@Param("millis") long millis);

        @Select("SELECT id FROM probe_row")
        List<Map<String, Object>> selectRows();

        /** 游标版：{@code LATERAL (SELECT pg_sleep(...))} 让这条游标查询**真的阻塞**。 */
        @Select("SELECT p.id FROM probe_row p, LATERAL (SELECT pg_sleep(#{millis})) s")
        Cursor<Map<String, Object>> selectRowsCursor(@Param("millis") long millis);
    }
}
