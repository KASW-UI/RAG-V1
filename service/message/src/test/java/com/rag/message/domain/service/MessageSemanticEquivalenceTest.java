package com.rag.message.domain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.rag.message.client.AdminRpcClient;
import com.rag.message.client.UserRpcClient;
import com.rag.message.config.MessageProperties;
import com.rag.message.domain.entity.NotificationEntity;
import com.rag.message.mapper.ConversationMapper;
import com.rag.message.mapper.ConversationMessageMapper;
import com.rag.message.mapper.NotificationMapper;
import com.rag.message.metrics.MessageRpcMetrics;
import com.rag.message.support.TestPostgres;
import com.rag.proto.message.ConversationListReq;
import com.rag.proto.message.ConversationListResp;
import com.rag.proto.message.ConversationMessageListReq;
import com.rag.proto.message.ConversationMessageListResp;
import com.rag.proto.message.MarkConversationReadReq;
import com.rag.proto.message.NotificationListReq;
import com.rag.proto.message.NotificationListResp;
import com.rag.proto.message.SenderRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * <p><b>message 域「行为对拍」用例 —— 锁住 DRIFT-MSG-V-001 / -002 / -004 / -005 / -006 / -007</b>。
 *
 * <p><b>本类由 B 侧 {@code MessageSemanticEquivalenceTest} 移植，期望值沿用 B 侧（D13 · AGENTS.md §1.0c
 * 「测试资产单独一档」：B 侧测试形态可直接借 ⇒ <b>期望值沿用 B 侧</b>）。</b>
 *
 * <p><b>B 出处（绝对路径 + 关键行号）</b>：
 * <pre>
 *   B: /Users/pr/Documents/java&golang/RAG/service/message/message-rpc/src/test/java/com/rag/message/message/rpc/MessageSemanticEquivalenceTest.java
 *      :37-61    类级 javadoc（为什么这一类用例此前完全存在 / 为什么必须是真 PG / 变异验证记录）
 *      :62-74    @SpringBootTest 注解块（本仓**不用**，见下「移植改法」）
 *      :75-76    @Testcontainers + @EnabledIfDockerAvailable（本仓**删掉 @EnabledIfDockerAvailable**）
 *      :79-107   真容器（@Container / RealContainerPort 固定口 / @DynamicPropertySource）
 *      :109-114  @Autowired domainService / atomicService / jdbcTemplate
 *      :116-121  cleanSlate()（@BeforeEach 清三张表）
 *      :143-171  DRIFT-MSG-V-001  total 必须跟随 unread_only
 *      :188-213  DRIFT-MSG-V-002  6000 条通知（>5461 参数阈值）必须全部落库
 *      :222-243  DRIFT-MSG-V-002  分片后仍是单一事务，中途失败必须全部回滚
 *      :256-281  DRIFT-MSG-V-004  会话消息必须按 created_at ASC 排序
 *      :293-318  DRIFT-MSG-V-005  last_message_at 并列时按 updated_at DESC 决定顺序
 *      :329-367  DRIFT-MSG-V-006  标记会话已读不得改写 conversation.updated_at
 *      :385-422  DRIFT-MSG-V-007  写入的墙钟经 TIMESTAMP 列往返后必须逐秒相等
 *      :428-503  helpers（notification / insertConversation* / insertMessage / assertThrowsAny）
 * </pre>
 *
 * <p><b>为什么这一类用例此前完全不存在，是本轮最值得记的一件事</b>：
 * message-rpc 原有 21 条测试、message-api 59 条测试，加上 {@code tools/semantic-probe} 的
 * 23 例双侧对拍，<b>没有一条能抓到 §6.7 表里的任何一项</b>。三个结构性原因：
 * <ol>
 *   <li><b>没有断言分页接口的 {@code total}</b> —— 测「列表返回了正确的 2 条」不需要
 *       {@code total} 参与断言，于是 {@code total} 成了无人区（DRIFT-MSG-V-001）；</li>
 *   <li><b>没有一条用例造过 &gt;5461 个收件人</b> —— 规模悬崖在正常规模的用例里不可见
 *       （DRIFT-MSG-V-002）；</li>
 *   <li><b>没有一条用例构造过排序并列</b> —— {@code created_at} / {@code last_message_at}
 *       不并列时，两种排序键给出<b>相同</b>结果（DRIFT-MSG-V-004 / -005）。</li>
 * </ol>
 * ⇒ 这三条已经写进 §6.7.5 纪律 A，并沉淀为下面每个 {@code @DisplayName} 的判据说明。
 *
 * <p><b>为什么必须是真 PG</b>：本类验证的是<b>排序与计数</b>在真实 SQL 上的语义。
 * H2 的默认 collation 与 {@code NULLS FIRST/LAST} 行为都不同，
 * 替换等于把要验证的东西换没了（同 {@code MessageTransactionWiringTest} 的理由）。
 *
 * <p><b>本类已做变异验证</b>（AGENTS.md §6.6.5 纪律：先证明缺陷存在时会红，再相信它的绿）：
 * 把 {@code countByRecipient} 的 {@code unreadOnly} 去掉 / 把 {@code orderByAsc(getId)}
 * 改回来 / 去掉 {@code orderByDesc(getUpdatedAt)} / 去掉 {@code insertNotificationsTx} 的切片
 * / 把 {@code touchUpdatedAt} 加回去，<b>对应 5 条用例各自变红</b>。
 *
 * <h2>⚠️ 本仓的移植改法（B 侧原文 → C 侧改法 → 为什么）</h2>
 * <ol>
 *   <li><b>不用 {@code @SpringBootTest}</b>（B 侧 :62-74）⇒ <b>改用与
 *       {@code MessageDomainServiceRealPgTest} 同款的最小手工装配</b>
 *       （{@code DriverManagerDataSource} → 真 Flyway → {@code MybatisSqlSessionFactoryBean}
 *       → {@code AnnotationConfigApplicationContext} + {@code @EnableTransactionManagement}
 *       → {@code new MessageDomainService(...)}）。
 *       <b>为什么</b>：B 侧的 {@code @SpringBootTest} 会拉起 Nacos / gRPC / Actuator / OTel
 *       ⇒ C 侧需要外部依赖才能起上下文 ⇒ 与「无外部依赖、快且稳」相悖；
 *       <b>而 {@code @Transactional} 必须经容器拿代理</b>（AGENTS.md §3.3.3「Bean 存在 ≠ 机制生效」）
 *       ⇒ 最小上下文保留了真代理 ⇒ 事务边界仍被真验证。</li>
 *   <li><b>删掉 {@code @EnabledIfDockerAvailable}</b>（B 侧 :76）⇒ 容器不可用直接失败。
 *       <b>为什么</b>：AGENTS.md §五.8 守门硬规则 ③ —— 禁止 {@code @EnabledIfDockerAvailable}
 *       ⇒ 跳过 = 假绿。</li>
 *   <li><b>真 PG 改用共享单例 {@code TestPostgres}</b>（B 侧 :79-107 的自建容器 +
 *       {@code RealContainerPort} / {@code GrpcTestPort} 固定口）
 *       ⇒ <b>C 侧没有这两个类</b>，且本域已有共享单例容器 ⇒ 同一个 JVM 里多个测试类共用一个库。</li>
 *   <li><b>清表改成 {@code @BeforeEach} + {@code @AfterEach} 各清一次</b>（B 侧只有 :116-121 的
 *       {@code @BeforeEach}）⇒ <b>为什么</b>：C 侧 surefire 默认 {@code forkCount=1, reuseForks=true}
 *       ⇒ 同一 JVM 里本类与 {@code MessageDomainServiceRealPgTest} 共用同一个库
 *       ⇒ 本类的残留数据会污染别的类 ⇒ 两头都清。</li>
 *   <li><b>{@code MessageDomainService} 构造器 8 个参数</b>
 *       （B 侧多一个 {@code LoggerFactoryBean logger} —— 那是只赋值从不读的死字段，
 *       C 侧已按 D16 删掉）⇒ 照 C 侧 {@code MessageDomainServiceRealPgTest} 的调用形态。</li>
 *   <li><b>跨域客户端 mock 必须返回非 null 的 {@code loadProfile}</b>
 *       ⇒ B 侧该测试里 mock 返回 null 会 NPE；C 侧按 {@code MessageDomainServiceRealPgTest}
 *       的做法返回 {@code new String[]{"peer-name", "peer@example.com"}}
 *       ⇒ <b>否则 V-005 会因为「什么都没做」而假绿</b>。</li>
 * </ol>
 *
 * <p><b>类型 / 包名映射（B → C）</b>：
 * {@code com.rag.message.message.proto.X} → {@code com.rag.proto.message.X}；
 * {@code ...rpc.domain.NotificationEntity} → {@code com.rag.message.domain.entity.NotificationEntity}；
 * {@code ...rpc.service.MessageDomainService / MessageAtomicService} →
 * {@code com.rag.message.domain.service.MessageDomainService / MessageAtomicService}。
 */
class MessageSemanticEquivalenceTest {

    private static JdbcTemplate jdbc;
    private static MessageDomainService domain;
    private static MessageAtomicService atomic;

    /** 只为拿到「@Transactional 代理」而存在的最小配置类（同 MessageDomainServiceRealPgTest）。 */
    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    @BeforeAll
    static void setUp() throws Exception {
        DataSource ds = new DriverManagerDataSource(
                TestPostgres.jdbcUrl(), TestPostgres.username(), TestPostgres.password());
        jdbc = new JdbcTemplate(ds);

        // ── ① Flyway：配置**直接取自 C 侧 application.yml 的 spring.flyway 段** ──
        //    ⇒ 断言的对象是「yml 里那几行配置」本身，而不是测试里另抄一份。
        FlywayProperties flywayProperties = bindFlywayProperties();
        Flyway flyway = Flyway.configure()
                .dataSource(TestPostgres.jdbcUrl(), TestPostgres.username(), TestPostgres.password())
                .locations(flywayProperties.getLocations().toArray(new String[0]))
                .baselineOnMigrate(flywayProperties.isBaselineOnMigrate())
                .baselineVersion(flywayProperties.getBaselineVersion())
                .table(flywayProperties.getTable())
                .schemas(flywayProperties.getSchemas().toArray(new String[0]))
                .load();
        flyway.migrate();

        // ── ② MyBatis-Plus：手工装配 SqlSessionFactory（三个 Mapper 接口）──
        //    ⚠️ 用 MP 自己的 `MybatisSqlSessionFactoryBean`（= 生产路径 `MybatisPlusAutoConfiguration`
        //      用的那一个）⇒ 它会把 `GlobalConfig` / `IdentifierGenerator` 一并装配好。
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(NotificationMapper.class);
        cfg.addMapper(ConversationMapper.class);
        cfg.addMapper(ConversationMessageMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setConfiguration(cfg);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        NotificationMapper notificationMapper = template.getMapper(NotificationMapper.class);
        ConversationMapper conversationMapper = template.getMapper(ConversationMapper.class);
        ConversationMessageMapper messageMapper = template.getMapper(ConversationMessageMapper.class);

        // ── ③ 跨域客户端：只 stub 纯外部调用，mapper 一律用真的 ──
        //    ⚠️ loadProfile 必须返回**非 null**数组：B 侧该测试里 mock 返回 null ⇒ NPE；
        //       C 侧按 MessageDomainServiceRealPgTest 的做法返回非 null，否则 V-005 假绿。
        UserRpcClient userRpcClient = mock(UserRpcClient.class, invocation -> {
            if ("loadProfile".equals(invocation.getMethod().getName())) {
                return new String[]{"peer-name", "peer@example.com"};
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        AdminRpcClient adminRpcClient = mock(AdminRpcClient.class, invocation -> {
            if ("loadProfile".equals(invocation.getMethod().getName())) {
                return new String[]{"admin-name", "admin@example.com"};
            }
            if ("collectBroadcastRecipients".equals(invocation.getMethod().getName())) {
                return List.of();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        MessageProperties properties = new MessageProperties();
        MessageRpcMetrics metrics = new MessageRpcMetrics(new SimpleMeterRegistry());

        // ── ④ 最小上下文：只装配事务代理需要的东西 ──
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(TxConfig.class);
        ctx.registerBean(DataSource.class, () -> ds);
        ctx.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        ctx.registerBean(NotificationMapper.class, () -> notificationMapper);
        ctx.registerBean(ConversationMapper.class, () -> conversationMapper);
        ctx.registerBean(ConversationMessageMapper.class, () -> messageMapper);
        // ⚠️ 必须走容器（拿到代理），不能 new
        ctx.registerBean(MessageAtomicService.class,
                () -> new MessageAtomicService(conversationMapper, messageMapper, notificationMapper));
        ctx.refresh();

        atomic = ctx.getBean(MessageAtomicService.class);
        // ⚠️ C 侧构造器是 8 个参数（没有 B 侧那个只赋值从不读的 LoggerFactoryBean logger）
        domain = new MessageDomainService(notificationMapper, conversationMapper, messageMapper,
                userRpcClient, adminRpcClient, properties, metrics, atomic);
    }

    /**
     * 把 <b>C 侧 application.yml</b> 的 {@code spring.flyway} 段绑成 {@link FlywayProperties}。
     *
     * <p>⚠️ <b>为什么读 yml 而不是测试里另抄一份</b>：另抄一份 ⇒ 断言的是「测试里的副本」，
     * yml 改错了测试照样绿 ⇒ <b>假绿</b>（AGENTS.md §3.3.3 同款形态）。
     */
    private static FlywayProperties bindFlywayProperties() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));
        Binder binder = new Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources));
        return binder.bind("spring.flyway", FlywayProperties.class).orElseGet(FlywayProperties::new);
    }

    // ==================================================================
    // 数据隔离：B 侧只有 @BeforeEach；C 侧补 @AfterEach（见类级 javadoc 移植改法 ④）
    // ==================================================================

    @BeforeEach
    void cleanSlateBefore() {
        cleanSlate();
    }

    @AfterEach
    void cleanSlateAfter() {
        cleanSlate();
    }

    private void cleanSlate() {
        jdbc.update("DELETE FROM message_conversation_message");
        jdbc.update("DELETE FROM message_conversation");
        jdbc.update("DELETE FROM message_notification");
    }

    // ==================================================================
    // DRIFT-MSG-V-001：listNotifications 的 total 必须跟随 unread_only
    // ==================================================================

    /**
     * <b>§6.7.5 纪律 A 的原型用例</b>：{@code total} 与 {@code items} <b>必须成对断言</b>。
     *
     * <pre>
     * Go  message.go:39-48
     *      query := Where("recipient_id = ?")
     *      if unreadOnly { query = query.Where("is_read = ?", false) }   ← 先加过滤
     *      total = query.Count()                                         ← 复用同一 query
     *
     * 修复前 RAG
     *      items  = listByRecipient(..., unreadOnly, ...)   ← 有 is_read
     *      total  = selectCount(QueryWrapper.eq("recipient_id", uid))  ← 无 is_read
     * </pre>
     *
     * <p>修复前本用例在 {@code unreadOnly=true} 分支会得到 {@code total=5 / items.size=3}。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-001：unread_only=true 时 total 与 items 必须同为未读数")
    void listNotificationsTotalMustRespectUnreadOnly() {
        long uid = 9001L;
        // 前 3 条**未读**、后 2 条**已读** ⇒ 共 5 条、其中未读 3 条
        // 走生产的 insertNotificationsTx 而不是 BaseMapper.insert：后者不带 extra 的
        // JsonbMapTypeHandler，撞 jsonb 列会报 "column extra is of type jsonb but
        // expression is of type character varying" —— 它不是生产路径，不要拿它做 setup。
        List<NotificationEntity> seed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seed.add(notification(1000L + i, uid, /* read = */ i >= 3));
        }
        atomic.insertNotificationsTx(seed);

        NotificationListResp all = domain.listNotifications(
                NotificationListReq.newBuilder().setUserId(uid).setLimit(50).build());
        assertEquals(5, all.getItemsCount(), "unread_only 未开：items 应返回全部 5 条");
        assertEquals(5, all.getTotal(), "unread_only 未开：total 应为全部 5 条");
        assertEquals(3, all.getUnreadCount(), "unread_count 与过滤无关，恒为未读数");

        NotificationListResp unread = domain.listNotifications(
                NotificationListReq.newBuilder().setUserId(uid).setUnreadOnly(true).setLimit(50).build());
        assertEquals(3, unread.getItemsCount(), "unread_only=true：items 应只返回 3 条未读");
        assertEquals(3, unread.getTotal(),
                "DRIFT-MSG-V-001：unread_only=true 时 total 必须与 items 同为 3。"
                        + "Go 的 total 复用带 is_read=false 的 query —— 见 message.go:40-48");
        assertEquals(unread.getItemsCount(), (int) unread.getTotal(),
                "纪律 A：total 与 items 必须成对一致，否则前端分页会多翻空页");
    }

    // ==================================================================
    // DRIFT-MSG-V-002：批量插入必须切片，否则 >5461 收件人整批失败
    // ==================================================================

    /**
     * <b>规模悬崖用例</b>。
     *
     * <p>{@code insertBatch} 是 12 列/行的一条 {@code <foreach>} 多行 INSERT；
     * Postgres 单条 SQL 的绑定参数上限是 65535 ⇒ {@code floor(65535/12) = 5461}。
     * 修复前这里会发一条 6000×12 = 72000 个参数的 SQL ⇒ <b>整批失败、事务回滚、
     * 一条通知都发不出去</b>。Go 侧 {@code CreateInBatches(items, 200)} 无此上限。
     *
     * <p>⚠️ <b>为什么必须真的造出 6000 条</b>：用 5 条、50 条都测不出来 ——
     * 分片与不分片的差别<b>只在大批量时显现</b>。这是 §3.3「小样本让规模缺陷隐形」的又一例。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-002：6000 条通知（>5461 参数阈值）必须全部落库")
    void notificationBatchInsertMustSurviveTheParameterLimit() {
        final int recipients = 6000;
        List<NotificationEntity> batch = new ArrayList<>(recipients);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < recipients; i++) {
            NotificationEntity n = notification(2000L + i, 777L, false);
            n.setCreatedAt(now);
            n.setUpdatedAt(now);
            batch.add(n);
        }

        atomic.insertNotificationsTx(batch);

        Integer persisted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_notification", Integer.class);
        assertEquals(recipients, persisted,
                "DRIFT-MSG-V-002：6000 条必须全部落库。修复前是一条 6000 行的 foreach INSERT，"
                        + "绑定参数 6000×12=72000 超过 Postgres 的 65535 上限 ⇒ 整批失败");

        // 分片不是"少插几条"的另一种失败方式：必须一条不少、id 齐全
        Integer distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT id) FROM message_notification", Integer.class);
        assertEquals(recipients, distinct, "分片后 id 必须唯一且齐全，不能重复或丢失");
    }

    /**
     * <b>分片不能削弱原子性</b>：Go 的 {@code CreateInBatches(items, 200)} 整批在事务里，
     * 修复后的 Java 也必须整批在一个事务里 —— 任一片失败 ⇒ 全部回滚。
     *
     * <p>做法：传一个 id 与首片冲突的批次（首片插入成功、第二片主键冲突）
     * ⇒ 期望<b>一条都不留</b>，而不是留下前 200 条。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-002：分片后仍是单一事务，中途失败必须全部回滚")
    void batchInsertMustStayAtomicAcrossChunks() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<NotificationEntity> batch = new ArrayList<>();
        for (int i = 0; i < 250; i++) {   // > INSERT_BATCH_SIZE(200) ⇒ 必被切成 2 片
            NotificationEntity n = notification(3000L + i, 888L, false);
            n.setCreatedAt(now);
            n.setUpdatedAt(now);
            batch.add(n);
        }
        // 把第 2 片里的一条 id 改成与第 1 片某条相同 ⇒ 插入到第二片时主键冲突
        batch.get(200).setId(batch.get(0).getId());

        assertThrowsAny(() -> atomic.insertNotificationsTx(batch));

        Integer persisted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_notification", Integer.class);
        assertEquals(0, persisted,
                "第 2 片冲突时第 1 片也必须回滚 —— 整批在同一个 @Transactional 里，"
                        + "与 Go CreateInBatches 的原子性一致");
    }

    // ==================================================================
    // DRIFT-MSG-V-004：会话消息按 created_at 排序，不是 id
    // ==================================================================

    /**
     * <b>并列敏感用例</b>：构造「id 顺序与 created_at 顺序相反」的两行。
     *
     * <p>不并列时两种排序键结果相同 ⇒ 这类漂移在常规数据上<b>永远测不出来</b>。
     * 雪花 id 单调这一点会让人误以为「按 id 排 ≈ 按 created_at 排」，
     * 但 id 的毫秒内次序由 (机器号, 序列号) 决定，与 created_at 的并列次序不必一致。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-004：会话消息必须按 created_at ASC 排序（构造与 id 相反的次序）")
    void conversationMessagesMustBeOrderedByCreatedAtNotId() {
        long convId = 7001L;
        insertConversation(convId, 1L, 11L, OffsetDateTime.now(ZoneOffset.UTC));

        OffsetDateTime t1 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime t2 = t1.plusHours(1);
        // id 小的那行 created_at 更大 ⇒ 按 id 得 [id1, id2]，按 created_at 得 [id2, id1]
        insertMessage(9001L, convId, t2, 11L, SenderRole.USER.getNumber());
        insertMessage(9002L, convId, t1, 11L, SenderRole.USER.getNumber());

        ConversationMessageListResp resp = domain.getConversationMessages(
                ConversationMessageListReq.newBuilder()
                        .setOperatorId(11L)
                        .setOperatorRoleValue(SenderRole.USER.getNumber())
                        .setConversationId(convId)
                        .setLimit(10)
                        .build());

        assertEquals(2, resp.getItemsCount());
        assertEquals(9002L, resp.getItems(0).getId(),
                "DRIFT-MSG-V-004：第一条必须是 created_at 更早的那行（id=9002），"
                        + "修复前按 id 排序会返回 9001 —— 见 message.go:163 的 Order(\"created_at asc\")");
        assertEquals(9001L, resp.getItems(1).getId());
    }

    // ==================================================================
    // DRIFT-MSG-V-005：会话列表的次级排序键 updated_at DESC
    // ==================================================================

    /**
     * <b>并列敏感用例</b>：两个会话 {@code last_message_at} 完全相同，
     * 只有 {@code updated_at} 不同 ⇒ 次级排序键缺失时顺序不确定。
     *
     * <p>批量发消息极易产生 {@code last_message_at} 并列，所以这不是罕见路径。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-005：last_message_at 并列时必须按 updated_at DESC 决定顺序")
    void conversationListMustUseUpdatedAtAsTiebreaker() {
        long uid = 12L;
        OffsetDateTime sameLastMessageAt = OffsetDateTime.of(2026, 2, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        // 两个会话的 last_message_at 完全相同，只有 updated_at 不同。
        // ⚠️ 参与者组合必须不同：uk_conversation_pair 是 (p1_role,p1_id,p2_role,p2_id) 唯一，
        //    同一对参与者只能有一个会话。让 operator 分别落在 p1 / p2 两侧即可绕开。
        // conv A 的 updated_at 更早 ⇒ 按 (last_message_at desc, updated_at desc) 应排在后面
        insertConversationAsP2(7101L, 99L, uid, sameLastMessageAt,
                sameLastMessageAt.minusDays(2));
        insertConversationAsP1(7102L, uid, 98L, sameLastMessageAt,
                sameLastMessageAt.plusDays(1));

        ConversationListResp resp = domain.listConversations(
                ConversationListReq.newBuilder()
                        .setOperatorId(uid)
                        .setOperatorRoleValue(SenderRole.USER.getNumber())
                        .setLimit(10)
                        .build());

        assertEquals(2, resp.getItemsCount());
        assertEquals(7102L, resp.getItems(0).getConversationId(),
                "DRIFT-MSG-V-005：updated_at 更新的会话应排在前 —— Go 的次级排序键是 "
                        + "Order(\"updated_at desc\")（message.go:126），修复前 RAG 只有主键");
    }

    // ==================================================================
    // DRIFT-MSG-V-006：markConversationRead 不得改写 conversation.updated_at
    // ==================================================================

    /**
     * <b>副作用集用例</b>：Go 的 {@code MarkConversationRead} 只 UPDATE 消息表，
     * 不触碰 conversation 表。修复前 RAG 额外调了 {@code touchUpdatedAt}，
     * 于是「标记已读」这个写操作会改写会话的 {@code updated_at}。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-006：标记会话已读不得改写 conversation.updated_at")
    void markConversationReadMustNotTouchConversationUpdatedAt() {
        long convId = 7201L;
        long uid = 13L;
        OffsetDateTime originalUpdatedAt = OffsetDateTime.of(2026, 3, 3, 8, 0, 0, 0, ZoneOffset.UTC);
        insertConversation(convId, 1L, uid, OffsetDateTime.now(ZoneOffset.UTC), originalUpdatedAt);

        OffsetDateTime createdAt = OffsetDateTime.of(2026, 3, 3, 7, 0, 0, 0, ZoneOffset.UTC);
        // recipient 必须就是 operator（uid/USER）—— MarkConversationRead 的 WHERE 带
        // recipient_role + recipient_id，对不上就更新 0 行，本用例会因"什么都没做"而假绿。
        insertMessage(9101L, convId, createdAt, uid, SenderRole.USER.getNumber());

        OffsetDateTime before = jdbc.queryForObject(
                "SELECT updated_at FROM message_conversation WHERE id = ?",
                OffsetDateTime.class, convId);
        assertEquals(originalUpdatedAt, before);

        domain.markConversationRead(
                MarkConversationReadReq.newBuilder()
                        .setOperatorId(uid)
                        .setOperatorRoleValue(SenderRole.USER.getNumber())
                        .setConversationId(convId)
                        .build());

        OffsetDateTime after = jdbc.queryForObject(
                "SELECT updated_at FROM message_conversation WHERE id = ?",
                OffsetDateTime.class, convId);
        assertEquals(originalUpdatedAt, after,
                "DRIFT-MSG-V-006：Go 的 MarkConversationRead 只 UPDATE 消息表（message.go:176-184）。"
                        + "修复前 RAG 额外 touch 了 conversation.updated_at —— "
                        + "一个「读类写操作」改了会话元数据，两端副作用集不同");

        // 正向确认：消息本身确实被标成已读（避免本用例因"什么都没做"而假绿）
        Boolean read = jdbc.queryForObject(
                "SELECT is_read FROM message_conversation_message WHERE id = ?",
                Boolean.class, 9101L);
        assertEquals(Boolean.TRUE, read, "消息必须真的被标为已读，否则本用例是假绿");
    }

    // ==================================================================
    // DRIFT-MSG-V-007：timestamp（无时区）列对墙钟必须无损
    // ==================================================================

    /**
     * <b>DB 往返用例</b>：单测证明了「转换函数算得对」，
     * 这条证明**它依赖的前提也成立** —— 即 {@code TIMESTAMP}（无时区）列对
     * {@code OffsetDateTime} 墙钟<b>不做任何换算、原样存取</b>。
     *
     * <p>整个 DRIFT-MSG-V-007 的修法（读侧用 {@code atZone(systemDefault())}）就建立在这个前提上：
     * 若某天有人把列改成 {@code TIMESTAMPTZ}、或换掉 JDBC 驱动，
     * 这个前提就不成立了，读侧也必须跟着改 —— <b>而没有任何单测会发现</b>。
     *
     * <p>断言用「写入一个非当前时间的固定墙钟」，避免用 {@code OffsetDateTime.now(ZoneOffset.UTC)}
     * 时把「写对读错」和「读写都对」混在一起看不出来。
     */
    @Test
    @DisplayName("DRIFT-MSG-V-007：写入的墙钟经 TIMESTAMP 列往返后必须逐秒相等")
    void notificationTimestampsMustRoundTripLosslessly() {
        long uid = 9500L;
        OffsetDateTime wallClock = OffsetDateTime.of(2026, 10, 23, 2, 0, 0, 0, ZoneOffset.UTC);

        NotificationEntity n = notification(1900L, uid, false);
        n.setCreatedAt(wallClock);
        n.setUpdatedAt(wallClock);
        atomic.insertNotificationsTx(List.of(n));

        // 直接从库里读，绕开 mapper，确认列本身没做换算
        OffsetDateTime rawFromDb = jdbc.queryForObject(
                "SELECT created_at FROM message_notification WHERE id = ?",
                OffsetDateTime.class, 1900L);
        assertEquals(wallClock, rawFromDb,
                "TIMESTAMP（无时区）列必须原样存取墙钟。"
                        + "若这一步发生换算，读侧的 atZone(systemDefault) 就不再是正确修法");

        // 再走完整的 service 层，断言 proto 里的 epoch
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            NotificationListResp resp = domain.listNotifications(
                    NotificationListReq.newBuilder().setUserId(uid).setLimit(10).build());

            assertEquals(1, resp.getItemsCount());
            // 🔴 V-020：列已是 TIMESTAMPTZ、实体是 OffsetDateTime ⇒ 值自带 offset，
            //    「按本地时区解释」这一步**已被删除**。这里刻意在 JVM=Asia/Shanghai 下
            //    断言一个**用 +08:00 构造**的瞬时 —— 若实现偷偷把 offset 丢掉、按 JVM
            //    时区重新解释，结果会差 8 小时而本用例转红。
            long expected = wallClock.toEpochSecond();
            assertEquals(expected, resp.getItems(0).getCreatedAt(),
                    "端到端：带 offset 的瞬时 → TIMESTAMPTZ 列 → proto epoch，必须原样往返");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private NotificationEntity notification(long id, long recipientId, boolean read) {
        NotificationEntity n = new NotificationEntity();
        n.setId(id);
        n.setRecipientId(recipientId);
        n.setSenderId(1L);
        n.setSenderRole(SenderRole.ADMIN.getNumber());
        n.setKind(1);
        n.setTitle("t");
        n.setContent("c");
        n.setRead(read);
        n.setExtra(new HashMap<>());
        // created_at / updated_at 在 DDL 里是 NOT NULL，且 insertBatch 是手写 @Insert
        // —— @TableField(fill = INSERT) 的自动填充**只作用于 MP 生成的 SQL**，
        // 手写 SQL 不填。生产的 MessageRpcSupport.newNotificationRecord 总是显式赋值，
        // 所以这里也必须显式给，否则撞 NOT NULL。
        n.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        n.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return n;
    }

    private void insertConversation(long id, long p1Id, long p2Id, OffsetDateTime lastMessageAt) {
        insertConversation(id, p1Id, p2Id, lastMessageAt, lastMessageAt);
    }

    private void insertConversation(long id, long p1Id, long p2Id,
                                    OffsetDateTime lastMessageAt, OffsetDateTime updatedAt) {
        insertConversationAsP1(id, p1Id, p2Id, lastMessageAt, updatedAt);
    }

    /** operator 落在 participant1 侧。 */
    private void insertConversationAsP1(long id, long p1Id, long p2Id,
                                        OffsetDateTime lastMessageAt, OffsetDateTime updatedAt) {
        insertConversationRow(id, p1Id, p2Id, lastMessageAt, updatedAt);
    }

    /** operator 落在 participant2 侧（用于绕开 uk_conversation_pair 的唯一约束）。 */
    private void insertConversationAsP2(long id, long peerId, long operatorId,
                                        OffsetDateTime lastMessageAt, OffsetDateTime updatedAt) {
        insertConversationRow(id, operatorId, peerId, lastMessageAt, updatedAt);
    }

    private void insertConversationRow(long id, long p1Id, long p2Id,
                                       OffsetDateTime lastMessageAt, OffsetDateTime updatedAt) {
        OffsetDateTime createdAt = lastMessageAt;
        jdbc.update("""
                INSERT INTO message_conversation
                  (id, participant1_role, participant1_id, participant2_role, participant2_id,
                   status, pending_sender_role, pending_sender_id,
                   created_by_role, created_by_id,
                   last_message_id, last_message_preview, last_message_at,
                   created_at, updated_at)
                VALUES (?, 1, ?, 1, ?, 2, 0, 0, 1, ?, 0, '', ?, ?, ?)
                """,
                id, p1Id, p2Id, p1Id, lastMessageAt, createdAt, updatedAt);
    }

    private void insertMessage(long id, long convId, OffsetDateTime createdAt,
                               long recipientId, int recipientRole) {
        jdbc.update("""
                INSERT INTO message_conversation_message
                  (id, conversation_id, sender_id, sender_role, recipient_id, recipient_role,
                   content, is_read, read_at, created_at)
                VALUES (?, ?, 1, 1, ?, ?, 'hello', FALSE, NULL, ?)
                """,
                id, convId, recipientId, recipientRole, createdAt);
    }

    private static void assertThrowsAny(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException expected) {
            assertNotNull(expected);
            return;
        }
        throw new AssertionError("预期抛异常（主键冲突），但正常返回了");
    }
}
