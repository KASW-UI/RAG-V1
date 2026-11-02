package com.rag.message.domain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.rag.message.client.AdminRpcClient;
import com.rag.message.client.UserRpcClient;
import com.rag.message.config.MessageProperties;
import com.rag.message.contract.GrpcErrorMapper;
import com.rag.message.domain.entity.ConversationEntity;
import com.rag.message.domain.entity.ConversationMessageEntity;
import com.rag.message.domain.entity.NotificationEntity;
import com.rag.message.mapper.ConversationMapper;
import com.rag.message.mapper.ConversationMessageMapper;
import com.rag.message.mapper.NotificationMapper;
import com.rag.message.metrics.MessageRpcMetrics;
import com.rag.message.support.TestPostgres;
import com.rag.proto.message.ConversationListReq;
import com.rag.proto.message.ConversationListResp;
import com.rag.proto.message.ConversationStatus;
import com.rag.proto.message.MarkAllNotificationsReq;
import com.rag.proto.message.MarkNotificationReq;
import com.rag.proto.message.NotificationListReq;
import com.rag.proto.message.NotificationListResp;
import com.rag.proto.message.SenderRole;
import com.rag.proto.message.SendChatMessageReq;
import com.rag.proto.message.SendChatMessageResp;
import com.rag.proto.message.UnreadSummaryReq;
import com.rag.proto.message.UnreadSummaryResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
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
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * <p><b>message 域领域层在「真 PostgreSQL」上的行为对齐测试</b>。
 *
 * <p><b>B 出处（本次迁移的行为基线 · D16：B 侧 RAG 仓是唯一判定基准）</b>：
 * <pre>
 *   B: RAG  service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageDomainService.java
 *      :166-389   doSendChatMessage（校验次序 / PENDING 分支 / 50 码点上限 / 建会话分支）
 *      :309-312   首条私聊 &gt; 50 **码点** ⇒ INVALID_ARGUMENT + 5206
 *      :355-360   PENDING 且发送方 == pending_sender ⇒ FAILED_PRECONDITION + 5204
 *      :523-534   listConversations：limit / offset 传反 ⇒ LIMIT 0 ⇒ 列表恒空（DRIFT-MSG-V-012）
 *      :836-852   listNotifications：total 与 items 共用 unreadOnly 过滤（DRIFT-MSG-V-001）
 *      :913-930   markNotificationRead：RowsAffected == 0 ⇒ NOT_FOUND + 5203
 *      :959-978   markAllNotificationsRead
 *      :1004-1037 getUnreadSummary
 *   B: RAG  service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageAtomicService.java
 *      :102-108   createConversationWithMessageTx（DRIFT-MSG-002 的修复点：事务内写回 conversationId）
 *      :130-140   createOrSendMessageTx（先 UPDATE 会话、再 INSERT 消息）
 *   B: RAG  service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageRpcSupport.java
 *      :284-289   runeLen（按**码点**计数，不是 char）
 *   B: RAG  service/message/message-rpc/src/main/resources/application.yml:83-124
 *      spring.flyway（table = flyway_schema_history_message / baseline-on-migrate / baseline-version: 0）
 *   B: RAG  service/message/message-rpc/src/main/resources/db/migration/V1..V5（DDL 真源）
 * </pre>
 *
 * <h2>⚠️ 为什么用「最小 Spring 上下文」而不是 {@code @SpringBootTest}</h2>
 * <p>{@code @Transactional} 靠 <b>AOP 代理</b>生效 ⇒ 必须经容器拿 bean，直接 {@code new} 出来的
 * {@link MessageAtomicService} 是<b>无代理裸对象</b> ⇒ 6 个事务方法会退化成 auto-commit
 * ⇒ <b>回滚测不出来</b>（这正是 B 侧把它拆成独立 Bean 的原因 · DRIFT-MSG-001）。
 * <p>⇒ 本类用 {@link AnnotationConfigApplicationContext} 只装配
 * <b>DataSource + 事务管理器 + 3 个 Mapper + MessageAtomicService</b>：
 * <ul>
 *   <li>不拉起 Nacos / gRPC / Actuator / OTel ⇒ 无外部依赖 ⇒ 快且稳；</li>
 *   <li>但<b>代理是真的</b> ⇒ {@code @Transactional} 真生效 ⇒ 事务边界真被验证。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么必须跑真 PG（不能用 H2 顶替）</h2>
 * <p>DDL 里用到 {@code TIMESTAMPTZ} / {@code ALTER COLUMN ... TYPE ... USING ... AT TIME ZONE ...} /
 * {@code JSONB} / {@code CREATE UNIQUE INDEX ... IF NOT EXISTS} ⇒ H2 一个都不认
 * ⇒ H2 上「跑绿」只能证明字符串被解析了，不能证明 DDL 与约束真的生效 ⇒ <b>假绿</b>。
 *
 * <h2>跨域 gRPC 客户端为什么要 stub</h2>
 * <p>{@link UserRpcClient} / {@link AdminRpcClient} 是<b>跨域</b>客户端（调 user-rpc / admin-rpc）
 * ⇒ 真连会失败 ⇒ 必须 stub。<b>但 stub 不得掩盖真 DB 行为</b> ⇒ 只 stub 这两个
 * <b>纯外部</b>调用（{@code loadProfile} / {@code collectBroadcastRecipients}），
 * <b>三个 Mapper 一律用真的</b>（真表、真 SQL、真约束）。
 */
class MessageDomainServiceRealPgTest {

    private static JdbcTemplate jdbc;
    private static MessageDomainService domain;
    private static MessageAtomicService atomic;
    private static NotificationMapper notificationMapper;
    private static FlywayProperties flywayProperties;

    /** 只为拿到「@Transactional 代理」而存在的最小配置类。 */
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
        flywayProperties = bindFlywayProperties();
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
        //      用的那一个）⇒ 它会把 `GlobalConfig` / `IdentifierGenerator` 一并装配好；
        //      裸 `SqlSessionFactoryBuilder` + `MybatisConfiguration` **不装** `IdentifierGenerator`
        //      ⇒ `populateKeys` 会 NPE（MP 3.5.17 的 `GlobalConfig.identifierGenerator` 默认是 null）。
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
        notificationMapper = template.getMapper(NotificationMapper.class);
        ConversationMapper conversationMapper = template.getMapper(ConversationMapper.class);
        ConversationMessageMapper messageMapper = template.getMapper(ConversationMessageMapper.class);

        // ── ③ 跨域客户端：只 stub 纯外部调用，mapper 一律用真的 ──
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

    // ============== 夹具 ==============

    private static boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private static long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private static SendChatMessageReq chatReq(long senderId, long recipientId, String content) {
        return SendChatMessageReq.newBuilder()
                .setSenderId(senderId)
                .setSenderRole(SenderRole.USER)
                .setRecipientId(recipientId)
                .setRecipientRole(SenderRole.USER)
                .setContent(content)
                .build();
    }

    private static NotificationEntity notification(long id, long recipientId, boolean read) {
        NotificationEntity n = new NotificationEntity();
        n.setId(id);
        n.setRecipientId(recipientId);
        n.setSenderId(9000L);
        n.setSenderRole(SenderRole.USER.getNumber());
        n.setKind(2);
        n.setTitle("title");
        n.setContent("content");
        n.setRead(read);
        if (read) {
            n.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        n.setExtra(new LinkedHashMap<>());
        n.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        n.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return n;
    }

    private static ConversationEntity conversation(long id, long p1, long p2) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationEntity conv = new ConversationEntity();
        conv.setId(id);
        conv.setParticipant1Role(SenderRole.USER.getNumber());
        conv.setParticipant1Id(p1);
        conv.setParticipant2Role(SenderRole.USER.getNumber());
        conv.setParticipant2Id(p2);
        conv.setStatus(ConversationStatus.PENDING.getNumber());
        conv.setPendingSenderRole(SenderRole.USER.getNumber());
        conv.setPendingSenderId(p1);
        conv.setCreatedByRole(SenderRole.USER.getNumber());
        conv.setCreatedById(p1);
        conv.setLastMessageId(0L);
        conv.setLastMessagePreview("preview");
        conv.setLastMessageAt(now);
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        return conv;
    }

    private static ConversationMessageEntity chatMessage(long id, long senderId, long recipientId, String content) {
        ConversationMessageEntity msg = new ConversationMessageEntity();
        msg.setId(id);
        msg.setSenderId(senderId);
        msg.setSenderRole(SenderRole.USER.getNumber());
        msg.setRecipientId(recipientId);
        msg.setRecipientRole(SenderRole.USER.getNumber());
        msg.setContent(content);
        msg.setRead(false);
        msg.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return msg;
    }

    // ============== A1 · Flyway 真的建出了 3 张表 + 独立账本 ==============

    @Test
    @DisplayName("Flyway：真的建出 3 张表；账本表名是 flyway_schema_history_message 且含 V1~V5 共 5 条")
    void flywayBuiltThreeTablesWithDomainScopedLedger() {
        // ① 三张业务表
        assertTrue(tableExists("message_notification"), "V1 应建出 message_notification");
        assertTrue(tableExists("message_conversation"), "V2 应建出 message_conversation");
        assertTrue(tableExists("message_conversation_message"), "V3 应建出 message_conversation_message");

        // ② 账本表名必须是**域内独立**的那个（B 侧 message-rpc/src/main/resources/application.yml:124）
        assertEquals("flyway_schema_history_message", flywayProperties.getTable());
        assertTrue(tableExists("flyway_schema_history_message"), "账本表必须叫 flyway_schema_history_message");
        assertFalse(tableExists("flyway_schema_history"),
                "默认账本表名不该出现（多域共 schema 会撞号 · AGENTS.md §六）");

        // ③ 账本里恰好 5 条 V1~V5，全部 success
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT version, description, success FROM flyway_schema_history_message ORDER BY installed_rank");
        assertEquals(5, ledger.size(), "V1..V5 共 5 条迁移");
        List<String> versions = new ArrayList<>();
        for (Map<String, Object> row : ledger) {
            versions.add(String.valueOf(row.get("version")));
            assertEquals(Boolean.TRUE, row.get("success"));
        }
        assertEquals(List.of("1", "2", "3", "4", "5"), versions);

        // ④ baseline-version 必须钉 0（非空 schema + 默认 1 ⇒ V1 永不执行 · 见 yml 注释）
        assertEquals("0", flywayProperties.getBaselineVersion());
        assertTrue(flywayProperties.isBaselineOnMigrate());
    }

    // ============== A2 · 新建会话时消息的 conversation_id 必须等于会话 id ==============

    @Test
    @DisplayName("sendChatMessage：USER→USER 建出会话(PENDING) + 一条消息，消息的 conversation_id 等于会话 id（非 0）")
    void sendChatMessageLinksMessageToNewConversation() {
        SendChatMessageResp resp = domain.sendChatMessage(chatReq(1001L, 1002L, "hello there"));

        assertEquals(200, resp.getCode());
        assertEquals("OK", resp.getMsg());
        assertTrue(resp.getConversationId() > 0L);
        assertEquals(ConversationStatus.PENDING, resp.getStatus());
        assertFalse(resp.getCanSend());

        // 🔴 DRIFT-MSG-002 的修复点（B 侧 MessageAtomicService.java:92-108）
        assertTrue(resp.getMessage().getConversationId() != 0L,
                "conversation_id 不得为 0（B 侧漏写 ⇒ 所有消息写成 0）");
        assertEquals(resp.getConversationId(), resp.getMessage().getConversationId());

        // DB 层证据
        Long dbConvId = jdbc.queryForObject(
                "SELECT conversation_id FROM message_conversation_message WHERE id = ?",
                Long.class, resp.getMessage().getId());
        assertEquals(resp.getConversationId(), dbConvId);
    }

    // ============== A3 · 同方连发 ⇒ FAILED_PRECONDITION + 5204 ==============

    @Test
    @DisplayName("sendChatMessage：同一会话同方连发 ⇒ FAILED_PRECONDITION + \"waiting for the other side to reply\"（5204）")
    void sameSenderSecondMessageInPendingConversationIsRejected() {
        domain.sendChatMessage(chatReq(1011L, 1012L, "first"));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.sendChatMessage(chatReq(1011L, 1012L, "second")));
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.getStatus().getCode());
        assertEquals("waiting for the other side to reply", ex.getStatus().getDescription());
        assertEquals(5204, GrpcErrorMapper.bizCodeFromError(ex));
    }

    // ============== A4 · 首条私聊 50 **码点** 上限 ==============

    @Test
    @DisplayName("sendChatMessage：首条私聊 > 50 码点 ⇒ INVALID_ARGUMENT + 5206；恰好 50 码点 ⇒ 成功")
    void firstPrivateMessageIsLimitedToFiftyCodePoints() {
        // ① 51 个 emoji = 51 码点（102 个 char）⇒ 拒
        String emoji51 = "😀".repeat(51);
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.sendChatMessage(chatReq(1021L, 1022L, emoji51)));
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        assertEquals("first private message cannot exceed 50 characters", ex.getStatus().getDescription());
        assertEquals(5206, GrpcErrorMapper.bizCodeFromError(ex));

        // ② 恰好 50 个 ASCII ⇒ 成功
        SendChatMessageResp ok50 = domain.sendChatMessage(chatReq(1041L, 1042L, "a".repeat(50)));
        assertEquals(200, ok50.getCode());

        // ③ 26 个 emoji = 26 **码点**但 52 个 char ⇒ 成功
        //    ⇒ 度量单位是码点（B 侧 runeLen），不是 UTF-16 code unit
        String emoji26 = "😀".repeat(26);
        assertEquals(52, emoji26.length(), "26 个 emoji 在 Java 里是 52 个 char");
        SendChatMessageResp ok26 = domain.sendChatMessage(chatReq(1031L, 1032L, emoji26));
        assertEquals(200, ok26.getCode());
    }

    // ============== A5 · listConversations 不得恒空 ==============

    @Test
    @DisplayName("listConversations：造了 1 个会话 ⇒ 列表返回 1 条（DRIFT-MSG-V-012：limit/offset 传反会恒空）")
    void listConversationsReturnsTheSeededConversation() {
        domain.sendChatMessage(chatReq(2001L, 2002L, "hi there"));

        ConversationListResp resp = domain.listConversations(ConversationListReq.newBuilder()
                .setOperatorId(2001L)
                .setOperatorRole(SenderRole.USER)
                .build());

        assertEquals(1, resp.getItemsCount(), "列表不得恒空");
        assertEquals(1L, resp.getTotal());
        assertEquals(2002L, resp.getItems(0).getPeerId());
        assertEquals("peer-name", resp.getItems(0).getPeerName(), "对端昵称来自 loadProfile（已 stub）");
        assertEquals(ConversationStatus.PENDING, resp.getItems(0).getStatus());
    }

    // ============== A6 · listNotifications 的 total 与 items 同口径 ==============

    @Test
    @DisplayName("listNotifications：unreadOnly=true ⇒ total 与 items 同为 1；unreadOnly=false ⇒ total=3")
    void listNotificationsTotalSharesTheSameFilterAsItems() {
        notificationMapper.insertBatch(List.of(
                notification(3_001L, 3001L, true),
                notification(3_002L, 3001L, true),
                notification(3_003L, 3001L, false)));

        NotificationListResp unread = domain.listNotifications(NotificationListReq.newBuilder()
                .setUserId(3001L)
                .setUnreadOnly(true)
                .build());
        assertEquals(1, unread.getItemsCount());
        assertEquals(1L, unread.getTotal(), "total 必须与 items 用同一个 is_read 过滤（DRIFT-MSG-V-001）");
        assertEquals(1L, unread.getUnreadCount());

        NotificationListResp all = domain.listNotifications(NotificationListReq.newBuilder()
                .setUserId(3001L)
                .setUnreadOnly(false)
                .build());
        assertEquals(3, all.getItemsCount());
        assertEquals(3L, all.getTotal());
        assertEquals(1L, all.getUnreadCount());
    }

    // ============== A7 · markNotificationRead 对不存在的 id ⇒ NOT_FOUND ==============

    @Test
    @DisplayName("markNotificationRead：不存在的 id ⇒ NOT_FOUND + \"notification not found\"（5203）")
    void markNotificationReadOnMissingIdIsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.markNotificationRead(MarkNotificationReq.newBuilder()
                        .setUserId(3101L)
                        .setNotificationId(3_101_999L)
                        .build()));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
        assertEquals("notification not found", ex.getStatus().getDescription());
        assertEquals(5203, GrpcErrorMapper.bizCodeFromError(ex));
    }

    // ============== A8 · markAllNotificationsRead + getUnreadSummary ==============

    @Test
    @DisplayName("markAllNotificationsRead：该 recipient 的未读全变已读，getUnreadSummary 的 notification_unread 变 0")
    void markAllNotificationsReadClearsUnreadCounter() {
        notificationMapper.insertBatch(List.of(
                notification(4_001L, 4001L, false),
                notification(4_002L, 4001L, false),
                notification(4_003L, 4001L, true)));
        assertEquals(2L, countRows(
                "SELECT COUNT(*) FROM message_notification WHERE recipient_id = ? AND is_read = FALSE", 4001L));

        domain.markAllNotificationsRead(MarkAllNotificationsReq.newBuilder().setUserId(4001L).build());

        assertEquals(0L, countRows(
                "SELECT COUNT(*) FROM message_notification WHERE recipient_id = ? AND is_read = FALSE", 4001L));
        assertEquals(3L, countRows(
                "SELECT COUNT(*) FROM message_notification WHERE recipient_id = ? AND is_read = TRUE", 4001L));

        UnreadSummaryResp summary = domain.getUnreadSummary(UnreadSummaryReq.newBuilder()
                .setUserId(4001L).build());
        assertEquals(0L, summary.getNotificationUnread());
        assertEquals(0L, summary.getTotalUnread());
    }

    // ============== A9 · 事务真的生效（真 DB 约束 ⇒ 唯一索引 / 主键冲突） ==============

    @Test
    @DisplayName("MessageAtomicService：第二步（INSERT 消息）撞主键 ⇒ 第一步（INSERT 会话）被回滚")
    void transactionRollsBackTheConversationWhenMessageInsertFails() {
        // ① 先造一条**主键相同**的消息行 ⇒ 第二步必然撞主键（真 PG 约束，不是 Mockito 桩）
        long clashMessageId = 9_000_002L;
        jdbc.update("INSERT INTO message_conversation_message "
                        + "(id, conversation_id, sender_id, sender_role, recipient_id, recipient_role, "
                        + " content, is_read, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                clashMessageId, 9_000_009L, 7001L, SenderRole.USER.getNumber(),
                7002L, SenderRole.USER.getNumber(), "preexisting", false,
                OffsetDateTime.now(ZoneOffset.UTC));

        long convId = 9_000_001L;
        ConversationEntity conv = conversation(convId, 7001L, 7002L);
        ConversationMessageEntity msg = chatMessage(clashMessageId, 7001L, 7002L, "will clash");

        assertThrows(RuntimeException.class, () -> atomic.createConversationWithMessageTx(conv, msg));

        // ⚠️ 关键断言：第一步的 INSERT 明明成功了，事务却把它**回滚**了
        //    ⇒ 若 @Transactional 静默失效（auto-commit），这一行会留在库里 ⇒ 测试会红。
        assertEquals(0L, countRows("SELECT COUNT(*) FROM message_conversation WHERE id = ?", convId),
                "事务未回滚 ⇒ @Transactional 没生效（AGENTS.md §3.3.3「Bean 存在 ≠ 机制生效」）");
        // 第二步没有插入新行（主键冲突），且原有行未被破坏
        assertEquals(1L, countRows(
                "SELECT COUNT(*) FROM message_conversation_message WHERE id = ?", clashMessageId));
    }

    @Test
    @DisplayName("MessageAtomicService：第二步撞 uk_conversation_pair ⇒ 第二步从未执行（无孤儿消息行）")
    void uniqueConstraintOnConversationPairStopsTheSecondStep() {
        long p1 = 7101L;
        long p2 = 7102L;

        ConversationEntity first = conversation(9_100_001L, p1, p2);
        ConversationMessageEntity firstMsg = chatMessage(9_100_011L, p1, p2, "first");
        atomic.createConversationWithMessageTx(first, firstMsg);

        // 同一 pair 的第二个会话 ⇒ uk_conversation_pair 冲突（真 PG 上的唯一索引）
        ConversationEntity second = conversation(9_100_002L, p1, p2);
        ConversationMessageEntity secondMsg = chatMessage(9_100_012L, p1, p2, "second");
        assertThrows(RuntimeException.class, () -> atomic.createConversationWithMessageTx(second, secondMsg));

        assertEquals(1L, countRows("SELECT COUNT(*) FROM message_conversation "
                        + "WHERE participant1_role = ? AND participant1_id = ? "
                        + "AND participant2_role = ? AND participant2_id = ?",
                SenderRole.USER.getNumber(), p1, SenderRole.USER.getNumber(), p2),
                "同一 pair 只允许一个会话");
        assertEquals(0L, countRows(
                "SELECT COUNT(*) FROM message_conversation_message WHERE id = ?", 9_100_012L),
                "第一步失败 ⇒ 第二步从未执行（事务内不得留孤儿行）");
        // 第一条会话确实提交了（证明上一步不是「整体没跑」）
        assertEquals(1L, countRows("SELECT COUNT(*) FROM message_conversation WHERE id = ?", 9_100_001L));
    }

    // ============== A10 · jsonb 的 extra 写进去能读回来，且键序被 PG 规范化 ==============

    @Test
    @DisplayName("notification.extra：jsonb 写进去能读回来，且键序被 PG 规范化（不是插入顺序）")
    void notificationExtraRoundTripsThroughJsonbWithNormalizedKeyOrder() {
        long notificationId = 5_000_001L;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("zz", "1");
        extra.put("aa", "2");

        NotificationEntity n = notification(notificationId, 6001L, false);
        n.setExtra(extra);
        notificationMapper.insertBatch(List.of(n));

        // ① DB 层：PG 把 jsonb 的对象键规范化了（按 长度 → 字节序）
        String raw = jdbc.queryForObject(
                "SELECT extra::text FROM message_notification WHERE id = ?", String.class, notificationId);
        assertNotNull(raw);
        assertTrue(raw.indexOf("\"aa\"") < raw.indexOf("\"zz\""),
                "PG 应把 jsonb 的键规范化成 aa,zz；实际 = " + raw);

        // ② Java 层：读回来（autoResultMap + JsonbMapTypeHandler）⇒ 键值对完整、键序同 PG
        NotificationEntity read = notificationMapper.selectById(notificationId);
        assertNotNull(read);
        assertNotNull(read.getExtra());
        assertEquals(List.of("aa", "zz"), new ArrayList<>(read.getExtra().keySet()));
        assertEquals("1", read.getExtra().get("zz"));
        assertEquals("2", read.getExtra().get("aa"));
    }

    // ============== 配置默认值 ==============

    @Test
    @DisplayName("MessageProperties：默认 limit=20 / maxLimit=100 / broadcast pageSize=200（与 B 侧 Go 的 json:\",default=\" 一致）")
    void messagePropertiesDefaultsMatchBside() {
        MessageProperties properties = new MessageProperties();
        assertEquals(20, properties.getList().getDefaultLimit());
        assertEquals(100, properties.getList().getMaxLimit());
        assertEquals(200L, properties.getBroadcast().getPageSize());
    }
}
