package com.rag.comment.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.domain.entity.SubjectEntity;
import com.rag.comment.mapper.CommentContentMapper;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.mapper.SubjectMapper;
import com.rag.comment.metrics.CommentRpcMetrics;
import com.rag.comment.support.TestPostgres;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>D6 / D7 的守门网（C 侧移植）</b> —— 锁住「comment 缓存层的 Redis 指标 label 与降级语义」。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>探针库隔离</b>：B 侧 {@code CommentCacheRedisMetricGateTest} 自起一个 PG 容器并直接用
 *       默认库 {@code first_db}；C 侧共用<b>进程内单例</b> PG 容器（{@link TestPostgres}）
 *       ⇒ 本类在同容器里另开一个探针库 {@code comment_cache_gate_probe}，
 *       与本域其它测试类的数据互不冲突（AGENTS.md §五.2 的唯一 uid 纪律在库一级落地）。</li>
 *   <li><b>账本表名独立</b>：本类用 {@code flyway_schema_history_comment_cache_gate}
 *       （与 B 侧 {@code CommentCacheRedisMetricGateTest.java:111} 同名同款）
 *       ⇒ 与本模块生产账本 {@code flyway_schema_history_comment}
 *       （{@code service/comment/src/main/resources/application.yml:133}）以及其它测试类的账本
 *       <b>三者互不撞号</b>。</li>
 *   <li><b>Redis 容器自起、不绑固定端口</b>：B 侧用
 *       {@code RealContainerHostPort.bindRedis(…)} 绑固定端口（B 侧专有类，C 侧没有）
 *       ⇒ C 侧改用 Testcontainers 的<b>随机映射端口</b>，避免与并发 worker 抢端口。</li>
 *   <li><b>「死口」端口改从专用段取</b>：B 侧 2026-10-28（G3）用 {@code RealGrpcPort.pick()}；
 *       C 侧无该类 ⇒ 本类在 <b>20000–21000</b> 专用段用 {@link ServerSocket} 绑一次确认空闲再释放。
 *       ⚠️ <b>不能用 OS 临时端口</b>：macOS 临时端口段 49152–65535 与 Docker 随机发布口同段
 *       ⇒ 别的会话的容器可能占住它 ⇒ 「连不上 = 死口」的前提会被打破。</li>
 *   <li><b>断言库从 JUnit 原生换成 AssertJ</b>（本仓 C 侧测试的通行风格，
 *       见 {@code CommentDomainFactRealPgTest}）：<b>判据与期望值逐条未改</b>。</li>
 *   <li><b>补 2 条 C 侧自有守门</b>：① <b>阳性对照</b>（断言 meter <b>真的被注册进 registry</b>，
 *       能区分「打了」与「没打」）；② <b>读穿结果一致性</b>（四种 Redis 结局都回源到同一行 DB 数据）。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。B 侧 5 条用例（D6 的三种结局 ＋ D7 的回填失败）<b>逐条保留</b>，
 * 判据（label 名、tag 名、精确次数）与期望值<b>逐字未改</b>；另加 2 条 C 侧补的守门
 * ⇒ 共 7 条，断言只增不减。
 * <p>⚠️ <b>指标名与 tag 是运维契约</b> ⇒ 这里断言的是<b>真实注册进 Micrometer registry 的 meter</b>，
 * 不是自己记的计数：C 侧 {@code com.rag.comment.metrics.CommentRpcMetrics#observeRedisError}
 * 写入的 meter 名是 {@code comment_redis_error_total}，tag 是
 * {@code module="comment_redis"} / {@code action} / {@code type}
 * （C 侧 {@code service/comment/src/main/java/com/rag/comment/metrics/CommentRpcMetrics.java:246-252}）
 * ⇒ 与 B 侧<b>同名同 tag</b>（B 侧 {@code …/rpc/metrics/CommentRpcMetrics.java} 同款）。
 *
 * <h2>3. B 出处（逐条读原文取证）</h2>
 * <ul>
 *   <li><b>oracle 测试类</b>：{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/service/CommentCacheRedisMetricGateTest.java}
 *       （B 侧 294 行 · 本类的逐条 oracle）</li>
 *   <li><b>oracle 实现类</b>：{@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentCacheService.java}
 *       —— 打点<b>消费点</b>：{@code :149}（外层读 ⇒ {@code GetSubjectWithCache}/query）、
 *       {@code :160}（singleflight 双检 ⇒ {@code GetSubjectWithCache}/query）、
 *       {@code :253}（Redis 真异常 ⇒ {@code GetSubjectCache}/query）、
 *       {@code :265}（JSON 解析失败 ⇒ <b>不</b>打 {@code GetSubjectCache}）、
 *       {@code :282}（回填失败 ⇒ {@code SetSubjectCache}/set）</li>
 *   <li><b>C 侧对应消费点</b>：{@code service/comment/src/main/java/com/rag/comment/service/CommentCacheService.java}
 *       {@code :189}（外层读）、{@code :200}（双检）、{@code :304}（Redis 真异常）、
 *       {@code :313-317}（JSON 解析失败，<b>不打点</b>）、{@code :341}（回填失败）</li>
 * </ul>
 *
 * <h2>🔴 为什么必须是「真 Redis ＋ 真 PG ＋ 真 Micrometer」</h2>
 * <ul>
 *   <li><b>真 Redis</b>：JSON 解析失败那条必须真的从 Redis 读到坏字节 ——
 *       用 mock 的 {@code opsForValue().get()} 造不出「Redis 返回了但内容坏了」这个形态。</li>
 *   <li><b>真 Redis 传输层失败</b>：把连接指向一个<b>没人监听的端口</b>
 *       ⇒ Lettuce 真去连、真被拒 ⇒ 走的是真异常分支（不是 mock 出来的异常）。</li>
 *   <li><b>真 PG ＋ 真 MyBatis</b>：回源 DB 那条路必须是真 SQL
 *       （本仓纪律：mock 数据层的单测不算数）。</li>
 *   <li><b>真 Micrometer registry</b>：指标名与 tag 是<b>运维契约</b>
 *       ⇒ 断言的是真实注册进去的 meter，不是自己记的计数。</li>
 * </ul>
 *
 * <p>⚠️ <b>禁 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ 容器起不来就<b>直接失败</b>（跳过 = 假绿）。
 *
 * <h2>🧬 变异实测（守门能变红 —— 三条都已<b>实跑验证</b>）</h2>
 * <p>⚠️ 变异在<b>仓库外</b>的临时副本上做（不碰 {@code src/main/**}、不碰共享 {@code target/}），
 * 变异体编译产物前置到 classpath 覆盖原类后再跑本类。
 * <ol>
 *   <li>把 C 侧 {@code readSubjectCacheDetailed} 的两个 catch <b>合并</b>
 *       （JSON 解析失败也打 {@code GetSubjectCache}）
 *       ⇒ 实跑：<b>{@link #jsonParseFailureEmitsOnlyGetSubjectWithCache()} 变红</b>
 *       （1 failed / 6 passed）—— 多出 {@code action=GetSubjectCache}。</li>
 *   <li>把回填的 try/catch 去掉 ⇒ 实跑：<b>{@link #writeBackFailureDoesNotFailTheRead()} 变红</b>
 *       （3 failed / 4 passed；另两条因异常上抛一并变红）—— 抛异常而不是返回 DB 结果。</li>
 *   <li>把本组四条守门点涉及的 {@code observeRedisError} 调用全部去掉
 *       ⇒ 实跑：<b>{@link #redisErrorMeterIsReallyRegisteredOnlyWhenErrorsHappen()} 与
 *       {@link #redisTransportFailureEmitsBothLabels()} 同时变红</b>（4 failed / 3 passed）。</li>
 * </ol>
 */
@Testcontainers
class CommentCacheRedisMetricGateTest {

    /** 本类自起的真 Redis（随机映射端口 ⇒ 不与别的 worker 抢端口）。 */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /** 本类专用探针库（与本域其它测试类的库互不干扰）。 */
    private static final String PROBE_DB = "comment_cache_gate_probe";

    /**
     * 本类独立账本（不与本模块生产账本 {@code flyway_schema_history_comment}
     * 及其它测试类的账本撞号）—— 与 B 侧
     * {@code CommentCacheRedisMetricGateTest.java:111} 同名同款。
     */
    private static final String LEDGER = "flyway_schema_history_comment_cache_gate";

    /** C 侧真实 meter 名（{@code metrics/CommentRpcMetrics.java:247}）。 */
    private static final String METRIC = "comment_redis_error_total";

    private static final String TARGET_TYPE = "article";
    private static final String TARGET_ID = "a-cache";

    /** 种子行的 total_count（读穿结果断言用）。 */
    private static final long SEEDED_TOTAL_COUNT = 3L;
    /** 种子行的 root_count。 */
    private static final long SEEDED_ROOT_COUNT = 1L;
    /** 种子行的 owner_id。 */
    private static final long SEEDED_OWNER_ID = 2002L;

    private static JdbcTemplate jdbc;
    private static SubjectMapper subjectMapper;
    private static CommentIndexMapper indexMapper;
    private static CommentContentMapper contentMapper;
    private static StringRedisTemplate redis;
    /** 指向「没人监听的端口」的模板 ⇒ 真连接被拒 ⇒ 真异常分支。 */
    private static StringRedisTemplate deadRedis;
    /** 本类起过的全部 Lettuce 工厂（@AfterAll 统一销毁，避免线程泄漏）。 */
    private static final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private static int deadPort;

    @BeforeAll
    static void setUp() throws Exception {
        createProbeDatabase(PROBE_DB);
        migrateWithIndependentLedger(PROBE_DB);

        DataSource dataSource = new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB), TestPostgres.username(), TestPostgres.password());
        jdbc = new JdbcTemplate(dataSource);

        // --- 真 MyBatis：与生产同构的「mapUnderscoreToCamelCase」+ Spring 事务感知的会话模板
        //     （手工装配段照抄 C 侧 CommentDomainFactRealPgTest.java:174-189 的同款手法）
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("cache-gate",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(SubjectMapper.class);
        configuration.addMapper(CommentIndexMapper.class);
        configuration.addMapper(CommentContentMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        subjectMapper = template.getMapper(SubjectMapper.class);
        indexMapper = template.getMapper(CommentIndexMapper.class);
        contentMapper = template.getMapper(CommentContentMapper.class);

        redis = templateFor(REDIS.getHost(), REDIS.getMappedPort(6379));
        // 🔴 「真异常」场景：指向一个**没人监听的端口** ⇒ Lettuce 真去连、真被拒。
        //    端口从 20000–21000 专用段取（避开 OS 临时端口段与 Docker 发布端口段）。
        deadPort = pickDeadPort();
        deadRedis = templateFor("127.0.0.1", deadPort);

        jdbc.update("DELETE FROM subject WHERE target_type = ? AND target_id = ?", TARGET_TYPE, TARGET_ID);
        jdbc.update("INSERT INTO subject (target_type, target_id, total_count, root_count, state, "
                        + "attribute, owner_id) VALUES (?, ?, 3, 1, 0, 0, 2002)",
                TARGET_TYPE, TARGET_ID);
    }

    @AfterAll
    static void tearDown() {
        for (LettuceConnectionFactory factory : factories) {
            factory.destroy();
        }
        factories.clear();
    }

    // ====================================================================================
    // 夹具与断言辅助
    // ====================================================================================

    private static StringRedisTemplate templateFor(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        StringRedisTemplate t = new StringRedisTemplate(factory);
        t.afterPropertiesSet();
        factories.add(factory);
        return t;
    }

    /**
     * 读某个 action label 上的 redis 错误计数（0 ⇒ 该 label 从未被用过）。
     *
     * <p>⚠️ {@code registry.find(name).tag(k,v)} 是<b>部分 tag 匹配</b> ⇒ C 侧多出来的
     * {@code module="comment_redis"} tag 不会影响命中（tag 集合见
     * {@code metrics/CommentRpcMetrics.java:247-249}）。
     */
    private static double count(MeterRegistry registry, String action, String type) {
        var counter = registry.find(METRIC)
                .tag("action", action).tag("type", type).counter();
        return counter == null ? 0.0d : counter.count();
    }

    /** 断言四个 label 上的 redis 错误计数全为 0。 */
    private static void assertNoRedisErrorMetric(MeterRegistry registry) {
        assertThat(count(registry, "GetSubjectCache", "query")).as("GetSubjectCache 不该被打点").isEqualTo(0.0d);
        assertThat(count(registry, "GetSubjectWithCache", "query")).as("GetSubjectWithCache 不该被打点").isEqualTo(0.0d);
        assertThat(count(registry, "GetSubjectWithCache", "db_fallback")).as("db_fallback 不该被打点").isEqualTo(0.0d);
        assertThat(count(registry, "SetSubjectCache", "set")).as("SetSubjectCache 不该被打点").isEqualTo(0.0d);
    }

    /**
     * 缓存专用 Jackson —— 与被测类内部那套<b>同配置</b>
     * （{@code service/CommentCacheService.java:156-159}：关掉 FAIL_ON_UNKNOWN_PROPERTIES）。
     */
    private static ObjectMapper cacheMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static CommentCacheService service(StringRedisTemplate template, MeterRegistry registry) {
        // ⚠️ props 在 B 侧构造器里也是「收下但不用」（C 侧同款，见
        //    service/comment/src/main/java/com/rag/comment/service/CommentCacheService.java:145-160）
        //    ⇒ 这里照抄该签名，不擅自删参。
        return new CommentCacheService(template, subjectMapper, indexMapper, contentMapper,
                new CommentRpcProperties(), new CommentRpcMetrics(registry));
    }

    /** 断言返回的实体就是种子行（读穿确实回源到了 DB）。 */
    private static void assertIsSeededDatabaseRow(SubjectEntity got) {
        assertThat(got).as("读穿结果必须来自 DB 回源").isNotNull();
        assertThat(got.getTargetType()).isEqualTo(TARGET_TYPE);
        assertThat(got.getTargetId()).isEqualTo(TARGET_ID);
        assertThat(got.getTotalCount()).isEqualTo(SEEDED_TOTAL_COUNT);
        assertThat(got.getRootCount()).isEqualTo(SEEDED_ROOT_COUNT);
        assertThat(got.getState()).isEqualTo(0);
        assertThat(got.getAttribute()).isEqualTo(0L);
        assertThat(got.getOwnerId()).isEqualTo(SEEDED_OWNER_ID);
    }

    // ====================================================================================
    // ① 命中 ⇒ 0 个指标
    // ====================================================================================

    @Test
    @DisplayName("🔴 D6：缓存命中 ⇒ **0 个** redis 错误指标"
            + "（C 侧消费点 service/CommentCacheService.java:184-190 · B 侧 …/rpc/service/CommentCacheService.java:143-150）")
    void cacheHitEmitsNoRedisErrorMetric() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        SubjectEntity subject = subjectMapper.findOneByTarget(TARGET_TYPE, TARGET_ID);
        assertThat(subject).isNotNull();

        String json = cacheMapper().writeValueAsString(CommentCacheService.SubjectCacheDto.from(subject));
        redis.opsForValue().set(CommentRedisKeys.subjectKey(TARGET_ID), json, Duration.ofMinutes(5));

        SubjectEntity got = service(redis, registry).getSubjectWithCache(TARGET_TYPE, TARGET_ID);

        assertIsSeededDatabaseRow(got);
        assertNoRedisErrorMetric(registry);
    }

    // ====================================================================================
    // ② 未命中 ⇒ 0 个指标（redis.Nil 不打点）
    // ====================================================================================

    @Test
    @DisplayName("🔴 D6：缓存未命中（redis.Nil）⇒ **0 个** redis 错误指标（Go 不打点）"
            + "（C 侧消费点 service/CommentCacheService.java:307-310 · B 侧 …/rpc/service/CommentCacheService.java:256-259）")
    void cacheMissEmitsNoRedisErrorMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();
        redis.delete(CommentRedisKeys.subjectKey(TARGET_ID));

        SubjectEntity got = service(redis, registry).getSubjectWithCache(TARGET_TYPE, TARGET_ID);

        assertThat(got).as("未命中应回源 DB").isNotNull();
        assertThat(got.getTotalCount()).isEqualTo(SEEDED_TOTAL_COUNT);
        assertNoRedisErrorMetric(registry);
    }

    // ====================================================================================
    // ③ JSON 解析失败 ⇒ 只有 GetSubjectWithCache 一个
    // ====================================================================================

    @Test
    @DisplayName("🔴 D6：缓存内容 JSON 坏了 ⇒ **只**打 GetSubjectWithCache（GetSubjectCache 不打点）"
            + "（C 侧消费点 service/CommentCacheService.java:313-317 + :189 + :200 · B 侧 …:265 / :149 / :160）")
    void jsonParseFailureEmitsOnlyGetSubjectWithCache() {
        MeterRegistry registry = new SimpleMeterRegistry();
        redis.opsForValue().set(CommentRedisKeys.subjectKey(TARGET_ID), "{not-json", Duration.ofMinutes(5));

        SubjectEntity got = service(redis, registry).getSubjectWithCache(TARGET_TYPE, TARGET_ID);

        assertThat(got).as("解析失败按「未命中」处理 ⇒ 仍应回源 DB").isNotNull();
        assertThat(got.getTotalCount()).isEqualTo(SEEDED_TOTAL_COUNT);
        assertThat(count(registry, "GetSubjectCache", "query"))
                .as("C 侧 service/CommentCacheService.java:313-317 明确「JSON 解析失败不打 GetSubjectCache」"
                        + " ⇒ 这个 label 不该出现")
                .isEqualTo(0.0d);
        assertThat(count(registry, "GetSubjectWithCache", "query"))
                .as("C 侧 :189（外层读）与 :200（singleflight 双检）各打一次 ⇒ 共 2 次")
                .isEqualTo(2.0d);
    }

    // ====================================================================================
    // ④ Redis 真异常 ⇒ 两个 label 都打
    // ====================================================================================

    @Test
    @DisplayName("🔴 D6：Redis **真异常** ⇒ 打 **两个**（GetSubjectCache + GetSubjectWithCache，各 2 次）"
            + "（C 侧消费点 service/CommentCacheService.java:304 + :189 + :200 · B 侧 …:253 / :149 / :160）")
    void redisTransportFailureEmitsBothLabels() {
        MeterRegistry registry = new SimpleMeterRegistry();

        SubjectEntity got = service(deadRedis, registry).getSubjectWithCache(TARGET_TYPE, TARGET_ID);

        assertThat(got).as("Redis 挂掉时仍然回源 DB").isNotNull();
        assertThat(got.getTotalCount()).isEqualTo(SEEDED_TOTAL_COUNT);
        assertThat(count(registry, "GetSubjectCache", "query"))
                .as("C 侧 :304：Redis 真异常 ⇒ 打 GetSubjectCache（query）；外层读 + 双检 ⇒ 共 2 次")
                .isEqualTo(2.0d);
        assertThat(count(registry, "GetSubjectWithCache", "query"))
                .as("C 侧 :189（外层读）与 :200（singleflight 双检）各打一次 ⇒ 共 2 次")
                .isEqualTo(2.0d);
    }

    // ====================================================================================
    // ⑤ D7：回填失败 ⇒ 读路径照常成功
    // ====================================================================================

    @Test
    @DisplayName("🔴 D7：回填 Redis 失败 ⇒ **读路径照常返回** DB 回源结果"
            + "（C 侧消费点 service/CommentCacheService.java:341 + :213-217 · B 侧 …:282 / :169-176）")
    void writeBackFailureDoesNotFailTheRead() {
        MeterRegistry registry = new SimpleMeterRegistry();

        // deadRedis：读失败 + 回填也失败
        SubjectEntity got = service(deadRedis, registry).getSubjectWithCache(TARGET_TYPE, TARGET_ID);

        assertThat(got)
                .as("C 侧 service/CommentCacheService.java:213-217 显式 catch 回填异常"
                        + " ⇒ 读路径必须照常返回 DB 回源的结果，而不是把异常抛出去")
                .isNotNull();
        assertThat(got.getTotalCount()).isEqualTo(SEEDED_TOTAL_COUNT);
        assertThat(count(registry, "SetSubjectCache", "set"))
                .as("C 侧 :341：回填失败仍要打 SetSubjectCache（set）")
                .isGreaterThanOrEqualTo(1.0d);
    }

    // ====================================================================================
    // ⑥ 阳性对照：meter 必须真的注册进 registry（能区分「打了」与「没打」）
    // ====================================================================================

    @Test
    @DisplayName("🔴 阳性对照：Redis 错误 meter **真的注册进 registry**（能区分「打了」与「没打」）"
            + "（C 侧写入点 metrics/CommentRpcMetrics.java:246-252）")
    void redisErrorMeterIsReallyRegisteredOnlyWhenErrorsHappen() throws Exception {
        // --- ① 命中路径：计数器**根本不该被注册**（不是「注册了但计数为 0」）
        MeterRegistry clean = new SimpleMeterRegistry();
        SubjectEntity subject = subjectMapper.findOneByTarget(TARGET_TYPE, TARGET_ID);
        assertThat(subject).isNotNull();
        redis.opsForValue().set(CommentRedisKeys.subjectKey(TARGET_ID),
                cacheMapper().writeValueAsString(CommentCacheService.SubjectCacheDto.from(subject)),
                Duration.ofMinutes(5));
        service(redis, clean).getSubjectWithCache(TARGET_TYPE, TARGET_ID);
        assertThat(clean.find(METRIC).counters())
                .as("命中路径下 Redis 错误计数器不该被注册进 registry")
                .isEmpty();

        // --- ② JSON 坏了：同一个 registry 上，计数器**必须真的被注册进去**
        MeterRegistry dirty = new SimpleMeterRegistry();
        redis.opsForValue().set(CommentRedisKeys.subjectKey(TARGET_ID), "{not-json", Duration.ofMinutes(5));
        service(redis, dirty).getSubjectWithCache(TARGET_TYPE, TARGET_ID);
        assertThat(dirty.find(METRIC).counters())
                .as("JSON 解析失败 ⇒ 该 meter 必须真的被注册进 registry")
                .isNotEmpty();
        assertThat(dirty.find(METRIC).tag("action", "GetSubjectWithCache").tag("type", "query").counter())
                .as("被注册的 meter 必须带 action=GetSubjectWithCache / type=query 两个 tag")
                .isNotNull();
    }

    // ====================================================================================
    // ⑦ 读穿结果正确性：四种 Redis 结局都回源到同一行 DB 数据
    // ====================================================================================

    @Test
    @DisplayName("🔴 读穿结果正确性：命中 / 未命中 / JSON 坏了 / Redis 真异常 ⇒ 四种结局都返回 total_count=3 的 DB 行"
            + "（C 侧消费点 service/CommentCacheService.java:203（回源 DB）· B 侧 …/rpc/service/CommentCacheService.java:163）")
    void everyRedisOutcomeFallsBackToTheSameDatabaseRow() throws Exception {
        SubjectEntity subject = subjectMapper.findOneByTarget(TARGET_TYPE, TARGET_ID);
        assertThat(subject).isNotNull();

        // --- ① 命中
        redis.opsForValue().set(CommentRedisKeys.subjectKey(TARGET_ID),
                cacheMapper().writeValueAsString(CommentCacheService.SubjectCacheDto.from(subject)),
                Duration.ofMinutes(5));
        assertIsSeededDatabaseRow(service(redis, new SimpleMeterRegistry())
                .getSubjectWithCache(TARGET_TYPE, TARGET_ID));

        // --- ② 未命中
        redis.delete(CommentRedisKeys.subjectKey(TARGET_ID));
        assertIsSeededDatabaseRow(service(redis, new SimpleMeterRegistry())
                .getSubjectWithCache(TARGET_TYPE, TARGET_ID));

        // --- ③ JSON 坏了
        redis.opsForValue().set(CommentRedisKeys.subjectKey(TARGET_ID), "{not-json", Duration.ofMinutes(5));
        assertIsSeededDatabaseRow(service(redis, new SimpleMeterRegistry())
                .getSubjectWithCache(TARGET_TYPE, TARGET_ID));

        // --- ④ Redis 真异常
        assertIsSeededDatabaseRow(service(deadRedis, new SimpleMeterRegistry())
                .getSubjectWithCache(TARGET_TYPE, TARGET_ID));
    }

    // ====================================================================================
    // 探针库 / 迁移 / 端口
    // ====================================================================================

    private static void migrateWithIndependentLedger(String database) {
        Flyway.configure()
                .dataSource(containerJdbcUrl(database), TestPostgres.username(), TestPostgres.password())
                .locations("classpath:db/migration")
                .schemas("public")
                // ⚠️ 账本表名独立 ⇒ 不与本模块生产账本（flyway_schema_history_comment）撞号
                .table(LEDGER)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
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

    private static String containerJdbcUrl(String database) {
        String base = TestPostgres.jdbcUrl();
        int q = base.indexOf('?');
        String bare = q >= 0 ? base.substring(0, q) : base;
        int slash = bare.lastIndexOf('/');
        String host = slash >= 0 ? bare.substring(0, slash + 1) : bare;
        return host + database + "?TimeZone=Asia/Shanghai";
    }

    /**
     * 从 <b>20000–21000 专用段</b>挑一个当前空闲的端口（「真异常」场景的死口）。
     *
     * <p>⚠️ <b>为什么不能用 {@code ServerSocket(0)}</b>：那拿到的是 <b>OS 临时端口段</b>
     * （macOS 为 49152–65535）⇒ 与 Docker 随机发布容器端口<b>同段</b>
     * ⇒ 别的会话的容器可能占住它 ⇒ 「连不上 = 死口」的前提被打破
     * （B 侧 2026-10-28 / G3 踩过这个坑）。
     */
    private static int pickDeadPort() {
        for (int port = 20000; port < 21000; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", port));
                return socket.getLocalPort();
            } catch (IOException e) {
                // 该端口已被占用 ⇒ 试下一个
            }
        }
        throw new IllegalStateException("20000-21000 段内找不到空闲端口，无法构造「Redis 真异常」场景");
    }
}
