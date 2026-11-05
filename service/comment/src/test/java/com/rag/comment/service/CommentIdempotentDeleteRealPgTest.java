package com.rag.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.service.CommentDomainFactService;
import com.rag.comment.domain.service.CommentTransactionalService;
import com.rag.comment.mapper.CommentContentMapper;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.comment.mapper.CommentFactStreamMapper;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.mapper.CommentLikeMapper;
import com.rag.comment.mapper.SubjectMapper;
import com.rag.comment.metrics.CommentRpcMetrics;
import com.rag.comment.support.TestPostgres;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>🔴 <b>幂等删除只扣一次计数</b>的守门（真 PostgreSQL ＋ 真 MyBatis ＋ 真 Spring 事务）。
 *
 * <h3>守的是什么</h3>
 * <p>B 侧 {@code comment.go:287} 的 {@code if comment.State != 2} 这一句：
 * <b>重复删除同一条评论时，第二次 {@code state} 已是 2 ⇒ 跳过计数回退与 retract 事实</b>
 * ⇒ ⇒ subject 的 {@code total_count} <b>只减一次</b>。
 * <p>少了这个判断，连续两次删除会让总评论数少 2。
 *
 * <h3>🔴 为什么必须走<b>真 PG + 真事务</b>（而不是照抄 B 侧那条裸 SQL 用例）</h3>
 * <p>B 侧那条用例（{@code CommentSchemaPgGateTest:359-380}）是<b>手写 UPDATE</b>：
 * 它证的是「那条 SQL 的效果」，<b>证不了</b>「<b>业务代码</b>在第二次删除时会不会再跑一次那条 UPDATE」。
 * <p>⇒ ⇒ 本类走<b>生产同款</b>的写路径：C 侧 {@code CommentTransactionalService#deleteCommentTx}
 * （<b>消费点</b>：{@code service/comment/src/main/java/com/rag/comment/domain/service/CommentTransactionalService.java:322-397}，
 * 判据在 {@code :346} 的 {@code if (comment.getState() != 2)}）
 * ⇒ ⇒ 并且用<b>真 Spring 事务代理</b>（CGLIB ＋ {@code TransactionInterceptor}）⇒
 * ⇒ {@code @Transactional} 真的生效。
 *
 * <h3>三段式（AGENTS.md §3.2）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>断言从「手写 SQL 的效果」升级为「业务代码的真实行为」</b>：
 *             ⇒ ⇒ 第二次删除若漏了那个 {@code if}，本类会红，而 B 侧那条<b>不会</b>红。</li>
 *         <li><b>多断言了「retract 事实只落一条」</b>：同一个 {@code if} 同时守着
 *             「计数回退」与「事实产出」⇒ ⇒ 两半一起钉住。</li>
 *         <li><b>探针库隔离</b>：B 侧自起一个 PG 容器；C 侧共用<b>进程内单例</b>容器
 *             ⇒ ⇒ 本类在同容器里另开一个探针库 {@code comment_idem_probe}。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 期望值（第一次删 ⇒ 计数 1→0；第二次删 ⇒ 计数<b>仍</b>是 0）
 *       与 B 侧那条用例逐条一致。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentSchemaPgGateTest.java:359-380}
 *             （B 侧那条用例的逐条 oracle）</li>
 *         <li>Go 侧：{@code service/comment/rpc/internal/model/comment.go:287}
 *             （{@code if comment.State != 2}）＋ {@code :295-304}（计数回退）</li>
 *         <li>C 侧落点：{@code service/comment/src/main/java/com/rag/comment/domain/service/CommentTransactionalService.java:346}
 *             （<b>消费点</b>：判据 {@code if (comment.getState() != 2)}）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>，不静默跳过）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
class CommentIdempotentDeleteRealPgTest {

    /** 本类专用探针库（与其它测试类的库互不干扰）。 */
    private static final String PROBE_DB = "comment_idem_probe";

    /** 本类用的评论 ID 段（与其它测试类的 ID 段错开）。 */
    private static final long COMMENT_ID = 9201001L;

    /** 被删评论的目标 ID（探针用）。 */
    private static final String TARGET_ID = "idem-probe-target";

    /** 评论作者 = subject 的 owner（⇒ 删自己的评论 ⇒ 过鉴权）。 */
    private static final long USER_ID = 7L;

    private static JdbcTemplate jdbc;
    private static SubjectMapper subjectMapper;
    private static CommentIndexMapper indexMapper;
    private static CommentContentMapper contentMapper;
    private static CommentLikeMapper likeMapper;
    private static CommentDomainFactService factService;

    /** 带 {@code @Transactional} 代理的领域事务服务（见类注释「真 Spring 事务」段）。 */
    private static CommentTransactionalService transactionalService;

    @BeforeAll
    static void setUp() throws IOException {
        createProbeDatabase(PROBE_DB);
        migrateWithModuleConfig(PROBE_DB);

        DataSource dataSource = new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB), TestPostgres.username(), TestPostgres.password());
        jdbc = new JdbcTemplate(dataSource);

        // --- 真 MyBatis：与生产同构的「mapUnderscoreToCamelCase」+ Spring 事务感知的会话模板
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("idem-gate",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(SubjectMapper.class);
        configuration.addMapper(CommentIndexMapper.class);
        configuration.addMapper(CommentContentMapper.class);
        configuration.addMapper(CommentLikeMapper.class);
        configuration.addMapper(CommentDomainFactOutboxMapper.class);
        configuration.addMapper(CommentFactStreamMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        subjectMapper = template.getMapper(SubjectMapper.class);
        indexMapper = template.getMapper(CommentIndexMapper.class);
        contentMapper = template.getMapper(CommentContentMapper.class);
        likeMapper = template.getMapper(CommentLikeMapper.class);

        ObjectMapper objectMapper = new ObjectMapper();
        factService = new CommentDomainFactService(
                template.getMapper(CommentDomainFactOutboxMapper.class),
                template.getMapper(CommentFactStreamMapper.class),
                objectMapper);

        // --- 真 Spring 事务：CGLIB 代理 + TransactionInterceptor ⇒ @Transactional 真的生效
        CommentTransactionalService target = new CommentTransactionalService(
                subjectMapper, indexMapper, contentMapper, likeMapper, factService,
                objectMapper, new CommentRpcMetrics(new SimpleMeterRegistry()));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        transactionalService = (CommentTransactionalService) proxyFactory.getProxy();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM subject");
        jdbc.update("DELETE FROM comment_index");
        jdbc.update("DELETE FROM comment_content");
        jdbc.update("DELETE FROM comment_domain_fact_outbox");
        jdbc.update("DELETE FROM comment_fact_stream");
    }

    // ====================================================================================
    // 守门点：重复删除同一条评论 ⇒ 计数只扣一次
    // ====================================================================================

    @Test
    @DisplayName("🔴 幂等删除：重复删除同一条评论 ⇒ subject.total_count **只扣一次**（第二次删不再回退计数）")
    void repeatedDeleteDecrementsTheCounterOnlyOnce() {
        seedCommentAndSubject();

        // ---- 第一次删除：计数 1 → 0
        long remainAfterFirst = transactionalService.deleteCommentTx(COMMENT_ID, USER_ID, "article", TARGET_ID);

        assertThat(remainAfterFirst)
                .as("第一次删除后剩余总数 = 0（原值 1 被回退 1 次）")
                .isZero();
        assertThat(totalCount())
                .as("第一次删除后 subject.total_count 必须被扣掉 1")
                .isZero();
        assertThat(rootCount())
                .as("这条是根评论（root_id=0）⇒ root_count 也要扣 1")
                .isZero();
        assertThat(commentState())
                .as("软删除 ⇒ comment_index.state = 2（不是物理删除）")
                .isEqualTo(2);
        assertThat(retractFactCount())
                .as("第一次删除产出 1 条 retract 事实")
                .isEqualTo(1);

        // ---- 第二次删除：计数**不动**
        long remainAfterSecond = transactionalService.deleteCommentTx(COMMENT_ID, USER_ID, "article", TARGET_ID);

        assertThat(remainAfterSecond)
                .as("第二次删除返回的剩余总数仍应是 0")
                .isZero();
        assertThat(totalCount())
                .as("🔴 核心断言：第二次删除**不得**再扣一次计数"
                        + "（Go comment.go:287 的 `if comment.State != 2` 守卫）"
                        + " ⇒ 少了这个判断 ⇒ 连删两次会让总评论数少 2")
                .isZero();
        assertThat(rootCount())
                .as("root_count 同理：第二次删除不得再扣")
                .isZero();
        assertThat(retractFactCount())
                .as("🔴 同一个守卫也守着「retract 事实只落一条」⇒ 第二次删不得再产事实")
                .isEqualTo(1);
    }

    /**
     * 🔴 <b>负控</b>：<b>第一次</b>删除确实扣了计数（否则上一条可能是因为「根本没删」而绿）。
     *
     * <p>⚠️ 这一条与上一条是<b>同一个探针</b>，但拆成两条用例是为了让「负控」在
     * 台账里<b>单独可见</b> ⇒ ⇒ 若实现改成「永远不扣计数」，上一条会红、
     * 这一条也会红（⇒ 不会因为「都不扣」而假绿）。
     */
    @Test
    @DisplayName("✅ 负控：第一次删除确实扣了计数 ⇒ 上一条的红是「第二次多扣」而不是「一直没扣」")
    void firstDeleteDecrementsTheCounter() {
        seedCommentAndSubject();

        transactionalService.deleteCommentTx(COMMENT_ID, USER_ID, "article", TARGET_ID);

        assertThat(totalCount())
                .as("第一次删除 ⇒ total_count 必须 1 → 0")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_index WHERE id = ?", Integer.class, COMMENT_ID))
                .as("软删除不是物理删除 ⇒ 评论行仍在")
                .isEqualTo(1);
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /** 造一条「state=0 的评论」＋ 一条「total_count=1 / root_count=1 的 subject」。 */
    private static void seedCommentAndSubject() {
        CommentIndexEntity index = new CommentIndexEntity();
        index.setId(COMMENT_ID);
        index.setTargetType("article");
        index.setTargetId(TARGET_ID);
        index.setUserId(USER_ID);
        index.setRootId(0L);
        index.setParentId(0L);
        index.setState(0);
        index.setAttribute(0L);
        // ⚠️ created_at 是 NOT NULL ⇒ 必须显式给（生产里它来自 Kafka 消息的 create_time）
        index.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        indexMapper.insert(index);

        jdbc.update("INSERT INTO subject (target_type, target_id, total_count, root_count, state, attribute, owner_id) "
                + "VALUES ('article', ?, 1, 1, 0, 0, ?)", TARGET_ID, USER_ID);
    }

    private static long totalCount() {
        return ((Number) jdbc.queryForMap(
                "SELECT total_count FROM subject WHERE target_id = ?", TARGET_ID).get("total_count"))
                .longValue();
    }

    private static long rootCount() {
        return ((Number) jdbc.queryForMap(
                "SELECT root_count FROM subject WHERE target_id = ?", TARGET_ID).get("root_count"))
                .longValue();
    }

    private static int commentState() {
        return jdbc.queryForObject(
                "SELECT state FROM comment_index WHERE id = ?", Integer.class, COMMENT_ID);
    }

    private static int retractFactCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM comment_domain_fact_outbox WHERE event_id = ?",
                Integer.class, "rtw.comment." + COMMENT_ID + ".deleted");
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
