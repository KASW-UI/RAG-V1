package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.rag.admin.config.AdminRpcProperties;
import com.rag.admin.domain.entity.AdminInviteEntity;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.admin.mapper.UserMapper;
import com.rag.admin.metrics.AdminRpcMetrics;
import com.rag.admin.wiring.AdminSchemaBootstrap;
import com.rag.users.common.SchemaInitializer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 🔴 DRIFT-ARPC-004 ①② 守门测试 —— admin 域 DDL 列类型与 Go 真源同构。
 *
 * <h2>B 出处</h2>
 *
 * <pre>
 *   B 源文件（绝对路径）：
 *     /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcColumnTypeParityTest.java
 *   B 关键行号：
 *     :86-96    @SpringBootTest + @Testcontainers + @EnabledIfDockerAvailable（后者已按 AGENTS.md §五.8 ③ 删除）
 *     :98-103   PostgreSQLContainer
 *     :105-111  @DynamicPropertySource
 *     :126-143  #adminColumnsMatchGoDdlTypes
 *     :173-197  #ddlReplayDoesNotShiftInstantsUnderNonUtcSession
 *     :220-259  #extraInfoAcceptsPayloadJsonbWouldReject
 *     :265-287  #inviteExpiryJudgedOnAbsoluteInstantNotWallClock
 *     :325-358  #userExtraInfoColumnIsTextAndAcceptsJsonbHostilePayload
 *     :372-384  #userExtraInfoLandsVerbatimNotJsonbNormalized
 * </pre>
 *
 * <p>本类由 B 侧同名类移植，<b>期望值沿用 B 侧（D13）</b> ⇒ <b>断言值 / 断言语义 /
 * {@code @DisplayName} 原文一字未改</b>。
 *
 * <h2>本类补的是哪一层证据</h2>
 *
 * <p>2026-10-23 修复了两处 DDL 漂移（用户 22:0x 拍板「DDL 与实体一起改」）：
 * <ul>
 *   <li><b>① {@code admin_admin.extra_info}</b>：{@code JSONB → TEXT}
 *       （Go 的 {@code serializer:json} 按 String 建列，见 {@code data_model.go:11}）</li>
 *   <li><b>② 6 个时间列</b>：{@code timestamp → timestamptz}
 *       （Go 的 {@code time.Time} 无 type tag ⇒ {@code postgres.go:251-256} 生成 timestamptz）</li>
 * </ul>
 *
 * <h2>为什么必须真 PG（不能 mock，也不能只查 DDL 脚本文本）</h2>
 *
 * <p>要证伪的是三件只有真进程能证的事：
 * <ol>
 *   <li><b>列类型真的变了</b> —— 脚本改了不等于库里变了；断言打在
 *       {@code information_schema.columns} 上，是库的真实状态；</li>
 *   <li><b>text 列真的收下 jsonb 会拒收的 payload</b> —— 这条走完整业务链
 *       （{@code updateSelf} → MP 生成 SQL → PG），不是单测一个 handler；</li>
 *   <li><b>跨时区的 invite 有效期判定仍然正确</b> —— 这是 ② 里<b>唯一可观察</b>的那一半。</li>
 * </ol>
 * 纯 Mockito 测数据层天然测不到以上任何一条（§3.3）。
 *
 * <h2>⚠️ 本类各条的「可证伪性」与<b>已知盲区</b>（不夸大证据强度）</h2>
 *
 * <table border="1">
 *   <tr><th>用例</th><th>能抓住什么回退</th><th>盲区</th></tr>
 *   <tr><td>{@code #adminColumnsMatchGoDdlTypes}</td>
 *       <td>DDL 改回 {@code jsonb} / {@code timestamp}</td>
 *       <td>无（查的是库的真实 schema）</td></tr>
 *   <tr><td>{@code #extraInfoAcceptsPayloadJsonbWouldReject}</td>
 *       <td>实体改回 {@code JsonbTypeHandler}、或 {@code updateSelf} 重新 CAST 成 jsonb</td>
 *       <td>无</td></tr>
 *   <tr><td>{@code #inviteExpiryJudgedOnAbsoluteInstantNotWallClock}</td>
 *       <td>列退回 {@code timestamp}（偏移量被 PG 丢弃 ⇒ 绝对时刻错位）</td>
 *       <td><b>实体类型退回 {@code LocalDateTime} 会编译不过</b>（编译期守卫，不是运行期）</td></tr>
 *   <tr><td>{@code #userExtraInfoColumnIsTextAndAcceptsJsonbHostilePayload}
 *       （<b>第十八轮新增</b>）</td>
 *       <td>{@code users_user.extra_info} 退回 jsonb、或 {@code updateUser} 重新 CAST 成 jsonb</td>
 *       <td>无</td></tr>
 *   <tr><td>{@code #userExtraInfoLandsVerbatimNotJsonbNormalized}
 *       （<b>第十八轮新增</b>）</td>
 *       <td>同上（用<b>落库字节</b>这条更细的轴再验一次）</td>
 *       <td>无</td></tr>
 * </table>
 *
 * <p>⇒ 五条<b>叠加</b>覆盖：列类型由第 1 条确定性守住，
 * 跨时区语义由第 3 条在 JVM 时区 ≠ 写入时区时守住（用 {@link #pickForeignZone()} 保证偏移量非零），
 * 边界 payload 由第 2 条守住，
 * <b>另一张表的同名列</b>（{@code users_user.extra_info}）由第 4、5 条守住。
 *
 * <h2>🔴 C 侧移植的三处机制改动（<b>断言值全部未动</b>）</h2>
 * <ol>
 *   <li><b>不用 {@code @SpringBootTest}，改用「最小 Spring 上下文」</b>
 *       （骨架照抄 C 侧既有 {@code AdminDomainServiceRealPgTest}）——
 *       B 侧的 {@code @SpringBootTest(classes = AdminRpcApplication.class)} 会把
 *       Tomcat + gRPC + Nacos + Redis 全拉起来，而本类的 6 条断言<b>一条都不碰它们</b>
 *       ⇒ 拉起来只是变慢 + 多一份与被测行为无关的失败面。
 *       <br>⚠️ 但<b>事务代理必须是真的</b>：{@code createAdmin} 会走
 *       {@code AdminCreateAtomicService.createAdminAtomic} 的 {@code @Transactional}
 *       ⇒ 该 Bean <b>经容器取</b>（{@code new} 出来的是无代理裸对象 ⇒ 事务静默失效）。</li>
 *   <li><b>删掉 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③：
 *       <b>禁止 {@code @EnabledIfDockerAvailable}</b>）⇒ Docker 不可用时本类应当<b>红</b>，
 *       而不是被静默跳过。</li>
 *   <li><b>建表走生产同一条路径</b>：{@code new AdminSchemaBootstrap(ds).initSchema()}
 *       （域内 wiring 件 → 共享层 {@link SchemaInitializer} 幂等重放 {@code db/01..04_*.sql}）
 *       ⇒ <b>不另抄一份 DDL</b>（抄了就不是生产路径了）。</li>
 * </ol>
 */
@Testcontainers
class AdminRpcColumnTypeParityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("admin_rpc_column_parity_it")
                    .withUsername("rag")
                    .withPassword("rag");

    private static final long ADMIN_UID = 8801L;
    /** {@code users_user} 的测试 uid（admin-rpc 也读写这张表，见 AdminSchemaBootstrap 的类注释）。 */
    private static final long USER_UID = 8802L;

    private static AdminDomainService domain;
    private static AdminInviteMapper adminInviteMapper;
    private static JdbcTemplate jdbcTemplate;
    private static DataSource dataSource;

    /** 只为拿到「@Transactional 代理」而存在的最小配置类。 */
    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    @BeforeAll
    static void setUp() throws Exception {
        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource = ds;
        jdbcTemplate = new JdbcTemplate(ds);

        // ── 建表：**生产同一条路径**（域内 wiring 件 → 共享层 SchemaInitializer 幂等重放）──
        // ⚠️ 不另抄一份 DDL：抄了就不是生产路径了（AGENTS.md §3.3「机制必须验证实际生效」）。
        new AdminSchemaBootstrap(ds).initSchema();

        // ── MyBatis-Plus：手工装配 SqlSessionFactory（三个 Mapper 接口）──
        // ⚠️ 不用 `MybatisSqlSessionFactoryBean`：MP 3.5.17 已把它移出 `mybatis-plus-extension`
        //    （落到独立的 `mybatis-plus-spring` artifact）⇒ 本仓 test classpath 上没有它
        //    ⇒ ⇒ 这里改用**普通 MyBatis** 的 `SqlSessionFactoryBuilder` ＋ MP 的 `MybatisConfiguration`
        //    （MP 的 `MybatisConfiguration.addMapper` 会走 `MybatisMapperRegistry`
        //      ⇒ `TableInfoHelper.initTableInfo` 仍会为 `@TableName(autoResultMap = true)`
        //      装配带 `typeHandler` 的 ResultMap ⇒ 与生产路径同构）。
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(AdminMapper.class);
        cfg.addMapper(AdminInviteMapper.class);
        cfg.addMapper(UserMapper.class);
        cfg.setEnvironment(new Environment("admin-rpc-column-parity", new SpringManagedTransactionFactory(), ds));
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AdminMapper adminMapper = template.getMapper(AdminMapper.class);
        AdminInviteMapper adminInviteMapper = template.getMapper(AdminInviteMapper.class);
        UserMapper userMapper = template.getMapper(UserMapper.class);

        AdminRpcProperties props = new AdminRpcProperties();
        props.getAdminAuth().setAccessExpire(2592000L);
        props.getAdminAuth().setAccessSecret("admin-column-parity-secret-0123456789");
        props.getSystem().setDefaultPassword("123456");
        AdminRpcMetrics metrics = new AdminRpcMetrics(new SimpleMeterRegistry());

        // ⚠️ Redis：本类 6 条断言**一条都不碰 Redis**（B 侧同样没碰）
        // ⇒ 不起 Redis 容器，用一个空壳替身即可（省一个容器 + 少一份与被测行为无关的失败面）。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        // ── 最小上下文：只装配事务代理需要的东西 ──
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(TxConfig.class);
        ctx.registerBean(DataSource.class, () -> ds);
        ctx.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        ctx.registerBean(StringRedisTemplate.class, () -> redis);
        ctx.registerBean(AdminMapper.class, () -> adminMapper);
        ctx.registerBean(AdminInviteMapper.class, () -> adminInviteMapper);
        ctx.registerBean(UserMapper.class, () -> userMapper);
        ctx.registerBean(AdminRpcProperties.class, () -> props);
        ctx.registerBean(AdminRpcMetrics.class, () -> metrics);
        // ⚠️ 必须走容器（拿到代理），不能 new
        ctx.registerBean(AdminCreateAtomicService.class,
                () -> new AdminCreateAtomicService(adminMapper, adminInviteMapper));
        ctx.refresh();

        AdminCreateAtomicService atomic = ctx.getBean(AdminCreateAtomicService.class);
        domain = new AdminDomainService(adminMapper, adminInviteMapper, userMapper,
                redis, props, metrics, atomic);
        AdminRpcColumnTypeParityTest.adminInviteMapper = adminInviteMapper;
    }

    // ==================================================================
    // 1. 列类型：库里的真实 schema，不是脚本文本
    // ==================================================================

    @Test
    @DisplayName("✅ admin_admin.extra_info 是 text、6 个时间列是 timestamptz（对齐 Go AutoMigrate）")
    void adminColumnsMatchGoDdlTypes() {
        assertEquals("text", columnType("admin_admin", "extra_info"),
                "Go 的 data_model.go:11 用 serializer:json ⇒ GORM 按 String 建列，即 text。"
                        + "若这里是 jsonb，PG 会拒收 encoding/json 能产出的合法内容（见下一条用例）。");
        assertEquals("text", columnType("admin_admin", "username"),
                "GORM 给 Go 的 string 字段建的是 text/无上限，RAG 侧已统一放宽为 TEXT（E-3/R3 自愈）");

        for (String col : new String[]{"create_time", "update_time"}) {
            assertEquals("timestamp with time zone", columnType("admin_admin", col),
                    "Go 的 data_model.go:12-13 是 time.Time 无 type tag ⇒ GORM 生成 timestamptz");
        }
        for (String col : new String[]{"expires_at", "used_at", "create_time", "update_time"}) {
            assertEquals("timestamp with time zone", columnType("admin_invite", col),
                    "Go 的 data_model.go:24,26,27,28 是 time.Time 无 type tag ⇒ GORM 生成 timestamptz");
        }
    }

    // ==================================================================
    // 1b. 脚本重放的幂等性（**本轮自己引入的隐患，必须自证**）
    // ==================================================================

    /**
     * 非 UTC 会话时区下重放 users 域 DDL，{@code timestamptz} 时刻**不得漂移**。
     *
     * <p><b>为什么这条必须存在</b>：{@code SchemaInitializer.applyAll}
     * <b>每次启动都原样重放</b>这四份脚本（无版本账本，靠重放做升级）。
     * 而 {@code 03} / {@code 04} 里的 ALTER 用了
     * {@code USING create_time AT TIME ZONE 'UTC'} ——
     * 该表达式对<b>已经是 timestamptz</b> 的列**并不恒等</b>：
     * <pre>
     *   timestamptz --AT TIME ZONE 'UTC'--> timestamp(UTC 墙钟)
     *                --按会话 TimeZone 铸回--> timestamptz(可能被平移)
     * </pre>
     * 会话时区非 UTC 时，<b>每启动一次整列时刻就偏移一次，且永不收敛</b>。
     * 两份脚本里写的 {@code SET TIME ZONE 'UTC'} … {@code RESET TIME ZONE}
     * 就是为了让两条路径都恒等 —— <b>而这个前提此前没有任何测试守着</b>
     * （台账本轮 §5 第 6 项自认「未验证」）。
     *
     * <p><b>本用例的可证伪性</b>：把 {@code 03/04} 里的
     * {@code SET TIME ZONE 'UTC'} 那一行删掉 ⇒ 本条立刻红（时刻偏移一个时区差）。
     * 用例自带「会话时区已被钉成 Asia/Shanghai」的前提断言，不依赖跑测试的机器时区。
     *
     * <p><b>为什么不能只查 DDL 脚本文本</b>：脚本文本里有那行 SET 不代表它被执行 ——
     * 语句切分、事务、连接池都可能改变实际效果，必须让脚本<b>真跑一遍</b>。
     */
    @Test
    @DisplayName("✅ 非 UTC 会话时区下重放 DDL，时刻不漂移（SET TIME ZONE 范式的守门）")
    void ddlReplayDoesNotShiftInstantsUnderNonUtcSession() throws Exception {
        seedAdmin(ADMIN_UID, "replay-admin");
        // 用带偏移量的字面量写入，避免 Timestamp.from() 自己就掺进 JVM 默认时区
        jdbcTemplate.update(
                "update admin_admin set create_time = '2026-01-02T03:04:05+00:00'::timestamptz where uid = ?",
                ADMIN_UID);
        long before = epochSecondOfAdminCreateTime(ADMIN_UID);

        // 会话时区钉成非 UTC —— 这正是会让 `AT TIME ZONE 'UTC'` 平移的条件
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.execute("SET TIME ZONE 'Asia/Shanghai'");
            assertEquals("Asia/Shanghai", currentSessionTimeZone(conn),
                    "前提断言：会话时区必须真的是 Asia/Shanghai，否则本用例测不到任何东西");
            // ⚠️ 这里直接调共享层执行器（而不是 AdminSchemaBootstrap）：
            //    bootstrap 自己开连接 ⇒ 无法在**同一条连接**上把会话时区钉成 Asia/Shanghai。
            //    而被测的 DDL 内容与执行器（SchemaInitializer.applyAll）与生产路径**完全同一条**。
            SchemaInitializer.applyAll(conn, "replay-probe");
        }

        long after = epochSecondOfAdminCreateTime(ADMIN_UID);
        assertEquals(before, after,
                "SchemaInitializer 每次启动都重放脚本。若 03/04 的 ALTER 少了 `SET TIME ZONE 'UTC'`，"
                        + "`timestamptz AT TIME ZONE 'UTC'` 会在非 UTC 会话下把整列平移一个时区差，"
                        + "且每次启动累积一次、永不收敛。before=" + before + " after=" + after);
    }

    /** {@code extract(epoch from timestamptz)} 给的是真实 epoch 秒，与会话时区无关。 */
    private long epochSecondOfAdminCreateTime(long uid) {
        Long v = jdbcTemplate.queryForObject(
                "select extract(epoch from create_time)::bigint from admin_admin where uid = ?",
                Long.class, uid);
        assertNotNull(v, "行必须还在（重放不应删数据）");
        return v;
    }

    private static String currentSessionTimeZone(java.sql.Connection conn) throws Exception {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("show timezone")) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ==================================================================
    // 2. extra_info 的 jsonb 边界（DRIFT-ARPC-004① 的核心行为）
    // ==================================================================

    @Test
    @DisplayName("✅ extra_info 收下 jsonb 会拒收的 \\u0000 payload（Go 落库成功的那一类）")
    void extraInfoAcceptsPayloadJsonbWouldReject() {
        seedAdmin(ADMIN_UID, "col-parity-admin");

        // 自证：这个 payload 对 jsonb 确实是敌意的 —— 否则本用例会变成空断言。
        // 若哪天 Jackson 不再把 U+0000 转义成 \u0000，下面这行会抛异常 ⇒ 测试会失败提醒人重判。
        assertThrows(Exception.class,
                () -> jdbcTemplate.queryForObject("select cast(? as jsonb)", String.class, "{\"k\":\"\\u0000\"}"),
                "前提断言：这段字面量必须能被 jsonb 拒收（22P05 unsupported Unicode escape sequence），"
                        + "否则本用例测不到任何东西");

        // 真业务链：updateSelf 原本写死 `extra_info = CAST({0} AS jsonb)`，jsonb 列会直接拒收。
        Map<String, String> payload = Map.of("k", "\u0000");
        domain.updateSelf(ADMIN_UID, null, null, null, payload);

        String stored = jdbcTemplate.queryForObject(
                "select extra_info from admin_admin where uid = ?", String.class, ADMIN_UID);
        assertNotNull(stored, "行必须还在：修复前这里会因 22P05 整条 UPDATE 失败");
        assertTrue(stored.contains("\\u0000"),
                "库里应存着 Jackson 转义后的 6 个 ASCII 字符 \\u0000（不是裸 NUL 字节）。实际 = " + stored);

        // 读回路径同样要过一遍（JsonTextTypeHandler 的 getNullableResult）。
        //
        // ⚠️ 断言的是**整体替换**，不是合并 —— 本条第一次写成「两轮写入后 Map 里两个 key 都在」，
        //    跑出来红了。复核 Go 侧后发现**替换才是对的**，是断言写错了：
        //      updateselflogic.go  →  if in.ExtraInfo != nil { toUpdate.ExtraInfo = in.ExtraInfo }
        //      updateuserlogic.go:84-86 → 同款
        //    GORM 的 Updates 赋值给 map 字段时是**整列覆盖**。
        // ⇒ 这条断言现在顺带锁住「extra_info 是替换而非合并」这个语义
        //    （§3.3.3：先改/删错误断言，再改实现 —— 这次是断言单方面写错）。
        var reread = domain.updateSelf(ADMIN_UID, null, null, null, Map.of("k2", "v2"));
        assertEquals(Map.of("k2", "v2"), reread.getExtraInfo(),
                "Go 侧是整列覆盖（toUpdate.ExtraInfo = in.ExtraInfo ⇒ GORM Updates 替换），"
                        + "不是与旧值合并；本条断言同时锁住这个语义");
        String afterReplace = jdbcTemplate.queryForObject(
                "select extra_info from admin_admin where uid = ?", String.class, ADMIN_UID);
        assertFalse(afterReplace.contains("\\u0000"),
                "整列覆盖后旧 key 必须消失。实际 = " + afterReplace);
    }

    // ==================================================================
    // 3. 跨时区的 invite 有效期判定（② 里唯一可观察的那一半）
    // ==================================================================

    @Test
    @DisplayName("✅ invite 有效期按绝对时刻判定：跨时区写入的未过期邀请码不会被误判为已过期")
    void inviteExpiryJudgedOnAbsoluteInstantNotWallClock() {
        seedAdmin(ADMIN_UID, "invite-zone-admin");
        ZoneId foreign = pickForeignZone();

        // 模拟「实例 A（foreign 时区）建邀请码」：绝对时刻是 10 分钟后，但墙钟与 JVM 时区差一整个偏移。
        OffsetDateTime expiresIn10Min = OffsetDateTime.now(foreign).plusMinutes(10);
        seedInvite("ADM-FOREIGN-ZONE-0001", expiresIn10Min);

        // 模拟「实例 B（JVM 默认时区）消费」：走真实业务链，走真实 PG。
        long uid = domain.createAdmin("zone-boss", "pwd123456", null, Map.of(), "ADM-FOREIGN-ZONE-0001");
        assertTrue(uid > 0, "一个绝对时刻上仍未过期的邀请码必须被接受 —— "
                + "若列是 timestamp，PG 会丢掉偏移量，按墙钟比就会跨时区误判为已过期（这正是修复前的行为）");

        // 反向：确实已过期的必须仍被拒（证明这条判定没有因为修复而放松）
        seedInvite("ADM-ALREADY-EXPIRED-01", OffsetDateTime.now(foreign).minusMinutes(1));
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> domain.createAdmin("zone-boss-2", "pwd123456", null, Map.of(), "ADM-ALREADY-EXPIRED-01"),
                "已过期的邀请码必须仍被拒绝");
        assertEquals(Status.Code.PERMISSION_DENIED, e.getStatus().getCode(),
                "Go 出处 createadminlogic.go:59/67 —— 邀请码不存在与已过期都是 PermissionDenied");
    }

    // ==================================================================
    // 4. users_user.extra_info —— **另一张表的同名列，也是 text**（第十八轮）
    // ==================================================================

    /**
     * 🔴 <b>第十八轮（global · B10）</b>：{@code users_user.extra_info} 与
     * {@code admin_admin.extra_info} 是<b>两张表</b>的<b>同一个列名</b>，
     * 而 2026-10-23 的 {@code DRIFT-ARPC-004①} 只改了<b>后一张</b>。
     *
     * <p><b>被漏掉的那一列，DDL 其实早已是 text</b>：
     * <ul>
     *   <li>{@code users-common/.../db/01_create_user.sql:41} → {@code extra_info  TEXT}</li>
     *   <li>{@code users-common/.../db/01_create_user.sql:103} →
     *       {@code ALTER TABLE users_user ALTER COLUMN extra_info TYPE text USING extra_info::text;}
     *       （<b>每次启动都重放</b> ⇒ 幂等自愈 ⇒ 这一列<b>不可能</b>是 jsonb）</li>
     * </ul>
     * ⇒ 而 {@code AdminDomainService.updateUser} 里的
     * {@code wrapper.setSql("extra_info = CAST({0} AS jsonb)", ...)}
     * 及其注释「<b>该列仍是 jsonb</b>」<b>都是失效的前提</b>。
     *
     * <p><b>可观察差异</b>（真 PG 16 实测，见本方法的「自证」断言）：
     * <pre>
     *   同一份 payload（extra_info 含 U+0000）：
     *     Go  → 落库成功，存下 14 字节的 {"k":"\u0000"}
     *     RAG → CAST 成 jsonb ⇒ PG 拒收
     *            ERROR:  unsupported Unicode escape sequence
     *            DETAIL: \u0000 cannot be converted to text.
     *            SQLSTATE = 22P05 (json_bad_unicode_escape)
     * </pre>
     * ⚠️ <b>SQLSTATE 是 22P05，不是 22P02</b>（{@code invalid_text_representation}）——
     * 台账与旧注释写的是 22P02，<b>本轮已订正</b>。
     *
     * <p><b>Go 出处</b>：{@code service/user/admin/rpc/internal/model/data_model.go:43}
     * {@code ExtraInfo map[string]string `gorm:"column:extra_info;serializer:json"`}
     * ⇒ GORM 的 serializer 产出<b>字符串</b> ⇒ 落进 text 列 ⇒ <b>没有 jsonb 那层校验</b>。
     */
    @Test
    @DisplayName("✅ users_user.extra_info 是 text：updateUser 收下 jsonb 会拒收的 \\u0000 payload")
    void userExtraInfoColumnIsTextAndAcceptsJsonbHostilePayload() {
        seedUser(USER_UID, "col-parity-user");

        // 🔴 列类型：断言打在**库的真实 schema** 上，不是 DDL 脚本文本上。
        //    这一列若退回 jsonb，下面整条业务链都会以 22P05 失败 ⇒ 本断言不是冗余。
        assertEquals("text", columnType("users_user", "extra_info"),
                "users-common/.../01_create_user.sql:41 建表就是 TEXT，:103 每次启动幂等 ALTER 回 text。"
                        + "Go 侧 serializer:json 按 String 落库（data_model.go:43）⇒ 这一列必须是 text。"
                        + "若这里是 jsonb，PG 会拒收 encoding/json 能合法产出的内容（见下一条断言）。");

        // 自证：这个 payload 对 jsonb 确实是敌意的 —— 否则本用例会变成空断言。
        // ⚠️ 2026-10-25（第十八轮）：真 PG 16 实测，PG 对 jsonb 的 \u0000 报错是
        //    SQLSTATE **22P05**（json_bad_unicode_escape），**不是** 22P02。
        //    （台账与旧注释写的是 22P02 —— 本轮已订正。）
        Exception hostile = assertThrows(Exception.class,
                () -> jdbcTemplate.queryForObject("select cast(? as jsonb)", String.class, "{\"k\":\"\\u0000\"}"),
                "前提断言：这段字面量必须能被 jsonb 拒收（unsupported Unicode escape sequence），"
                        + "否则本用例测不到任何东西");
        assertTrue(String.valueOf(hostile.getMessage()).contains("unsupported Unicode escape sequence"),
                "PG 对 jsonb 的 \\u0000 必须报 'unsupported Unicode escape sequence'（SQLSTATE 22P05）。"
                        + "若报错变了（PG 换版本 / 列型又变），本用例的前提要重新判定。实际 = " + hostile.getMessage());

        // 真业务链：updateUser 原本写死 `extra_info = CAST({0} AS jsonb)` ⇒ jsonb 拒收 ⇒ 整条 UPDATE 失败。
        Map<String, String> payload = Map.of("k", "\u0000");
        domain.updateUser(USER_UID, 9001L, null, null, null, payload);

        String stored = jdbcTemplate.queryForObject(
                "select extra_info from users_user where uid = ?", String.class, USER_UID);
        assertNotNull(stored, "行必须还在：修复前这里会因 22P05 整条 UPDATE 失败");
        assertTrue(stored.contains("\\u0000"),
                "库里应存着 Jackson 转义后的 6 个 ASCII 字符 \\u0000（不是裸 NUL 字节）。实际 = " + stored);
    }

    /**
     * 🔴 <b>逐字节落库</b>（第十八轮）：证明写进去的是<b>原文</b>，没被 jsonb 规范化。
     *
     * <p><b>怎么区分 jsonb 与 text</b>：jsonb 的输出函数会重排键、并在冒号后加空格
     * （{@code {"k": "v"}}），text 列则是<b>原样</b>（{@code {"k":"v"}}）。
     * ⇒ 断言整串相等即可区分，不需要碰「键序」那条轴
     * （Go 的 {@code json.Marshal} 按字典序排键、Jackson 保持插入序 ——
     * 那是<b>既有</b>差异，user-rpc 的 JsonTextTypeHandler 类注释已写明<b>不改</b>，本轮不碰）。
     *
     * <p><b>可证伪性</b>：把 {@code CAST({0} AS jsonb)} 加回去 ⇒ 本条立刻红
     * （实际落库会变成 {@code {"k": "v"}}）。
     */
    @Test
    @DisplayName("✅ users_user.extra_info 逐字节落库，没被 jsonb 规范化重排（CAST 残留守门）")
    void userExtraInfoLandsVerbatimNotJsonbNormalized() {
        seedUser(USER_UID, "verbatim-user");

        domain.updateUser(USER_UID, 9001L, null, null, null, Map.of("k", "v"));

        String stored = jdbcTemplate.queryForObject(
                "select extra_info from users_user where uid = ?", String.class, USER_UID);
        assertEquals("{\"k\":\"v\"}", stored,
                "text 列必须原样落库。若这里是 {\"k\": \"v\"}（冒号后有空格），"
                        + "说明值被 CAST 成 jsonb 又转回 text 了 ⇒ jsonb 的规范化仍在链路上（与 Go 不一致）。");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 挑一个<b>与 JVM 默认时区偏移量不同</b>的时区。
     *
     * <p>必要性：若写入时区恰好等于 JVM 时区，即使列退化成 {@code timestamp}
     * （PG 丢偏移）也观察不到错位，用例会假绿。
     * ⇒ 这里保证偏移量<b>非零</b>，让「列类型回退」在<b>任何机器上</b>都能被抓住。
     */
    private static ZoneId pickForeignZone() {
        ZoneId jvm = ZoneId.systemDefault();
        boolean jvmIsUtc = jvm.getRules().getOffset(Instant.now()).equals(ZoneOffset.UTC);
        return jvmIsUtc ? ZoneId.of("Asia/Shanghai") : ZoneOffset.UTC;
    }

    /** 断言打在库的真实 schema 上，而不是 DDL 脚本文本上。 */
    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? and column_name = ?",
                String.class, table, column);
    }

    private void seedAdmin(long uid, String username) {
        jdbcTemplate.update(
                "insert into admin_admin (uid, username, password, email, extra_info) "
                        + "values (?, ?, 'hash', null, null) on conflict (uid) do nothing",
                uid, username);
    }

    /**
     * 种一行 {@code users_user}。
     *
     * <p>⚠️ {@code email} 留 NULL：{@code users_user.email} 是 {@code TEXT UNIQUE}，
     * 而 PG 的唯一索引把多个 NULL 视为互不相同 ⇒ 多行 NULL email 不会撞约束。
     */
    private void seedUser(long uid, String username) {
        jdbcTemplate.update(
                "insert into users_user (uid, username, password, email, extra_info) "
                        + "values (?, ?, 'hash', null, null) on conflict (uid) do nothing",
                uid, username);
    }

    private void seedInvite(String code, OffsetDateTime expiresAt) {
        adminInviteMapper.insert(invite(code, expiresAt));
    }

    private AdminInviteEntity invite(String code, OffsetDateTime expiresAt) {
        AdminInviteEntity e = new AdminInviteEntity();
        e.setCode(code);
        e.setInviterUid(ADMIN_UID);
        e.setExpiresAt(expiresAt);
        return e;
    }
}
