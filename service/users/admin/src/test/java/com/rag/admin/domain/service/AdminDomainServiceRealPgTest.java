package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.rag.admin.config.AdminRpcProperties;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.UserEntity;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.admin.mapper.UserMapper;
import com.rag.admin.metrics.AdminRpcMetrics;
import com.rag.common.security.PasswordUtil;
import com.rag.users.common.SchemaInitializer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>admin 域领域层在「真 PostgreSQL + 真 Redis」上的行为对齐测试</b>。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java
 *      :232-241   login（4 状态机：NotFound / WrongAnswer / Success）
 *      :282-305   logout（token 空 ⇒ INVALID_ARGUMENT；Redis 写黑名单）
 *      :528-573   createAdmin（邀请码校验 → 4 步事务）
 *      :645-682   createInviteCode（"ADM-" + 8 字节 hex；重试 3 次）
 *      :877-906   getUserList（GORM 的 LIMIT/OFFSET **静默抑制**规则）
 *      :1023-1095 updateUser（username 冲突 → ALREADY_EXISTS）
 *   B: RAG  service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminCreateAtomicService.java
 *      :110-205   4 步原子事务（W1 悲观锁 → W2 username 查重 → bcrypt → insert → 标记 invite used）
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/ddl/SchemaInitializer.java:103-136
 *      —— applyAll：advisory lock → 4 个脚本原样重放（幂等）
 * </pre>
 *
 * <h2>本类钉住什么（每条都是 L1/L2 行为，不是实现细节）</h2>
 * <ol>
 *   <li><b>登录 3 态</b>：查无此人 ⇒ NOT_FOUND；密码错 ⇒ UNAUTHENTICATED；命中 ⇒ 返回 uid。</li>
 *   <li><b>邀请码的「一次性」不变量</b>：同一 code 第二次用 ⇒ PERMISSION_DENIED
 *       （靠 W1 的 {@code SELECT ... FOR UPDATE} 行级悲观锁 + W4 的标记 used）。</li>
 *   <li><b>username 查重在事务内</b>：重名 ⇒ ALREADY_EXISTS，<b>且邀请码不被消耗</b>。</li>
 *   <li><b>GORM 的 LIMIT/OFFSET 静默抑制</b>：{@code page=0} ⇒ 负 offset ⇒ <b>不生成 OFFSET</b>
 *       ⇒ 返回首页（B 侧行为，C 侧不得改成 400）。</li>
 *   <li><b>{@code extra_info} 是 text 不是 jsonb</b>：含 U+0000 的 payload 必须<b>落库成功</b>
 *       （jsonb 会以 22P05 拒收 ⇒ 那是 B 侧修掉的漂移，不能回流）。</li>
 *   <li><b>黑名单 key 前缀</b> = {@code admin:jwt_blacklist:}（必须与 user 子域区分）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么用「最小 Spring 上下文」而不是 {@code @SpringBootTest}</h2>
 * <p>{@code @Transactional} 靠 <b>AOP 代理</b>生效 ⇒ 必须经容器拿 bean，直接 {@code new} 出来的
 * {@link AdminCreateAtomicService} 是<b>无代理裸对象</b> ⇒ 4 步事务会退化成 4 条 auto-commit
 * ⇒ 悲观锁与回滚都测不出来（这正是 B 侧把它拆成独立 Bean 的原因）。
 * <p>⇒ 本类用 {@link AnnotationConfigApplicationContext} 只装配
 * <b>DataSource + 事务管理器 + 3 个 Mapper + 2 个 Service</b>：
 * <ul>
 *   <li>不拉起 Nacos / gRPC / Actuator / OTel ⇒ 无外部依赖 ⇒ 快且稳；</li>
 *   <li>但<b>代理是真的</b> ⇒ {@code @Transactional} 真生效 ⇒ 事务边界真被验证。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么必须跑真 PG（不能用 H2 顶替）</h2>
 * <p>脚本里有 {@code TIMESTAMPTZ} / {@code ALTER COLUMN ... TYPE ... USING ...} /
 * {@code pg_advisory_lock} / {@code FOR UPDATE} ⇒ H2 一个都不认
 * ⇒ H2 上「跑绿」只能证明字符串被解析了，不能证明 DDL 与锁真的生效 ⇒ <b>假绿</b>。
 */
@Testcontainers
class AdminDomainServiceRealPgTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static AdminDomainService domain;
    private static AdminCreateAtomicService atomic;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;

    /** 只为拿到「@Transactional 代理」而存在的最小配置类。 */
    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    @BeforeAll
    static void setUp() throws Exception {
        DataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);

        // ── 幂等 DDL 重放（真实执行器，不是把 SQL 抄一遍）──
        try (Connection conn = ds.getConnection()) {
            SchemaInitializer.applyAll(conn, "admin-domain-test");
        }

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
        cfg.setEnvironment(new Environment("admin-domain-test", new SpringManagedTransactionFactory(), ds));
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AdminMapper adminMapper = template.getMapper(AdminMapper.class);
        AdminInviteMapper adminInviteMapper = template.getMapper(AdminInviteMapper.class);
        UserMapper userMapper = template.getMapper(UserMapper.class);

        // ── 真 Redis ──
        LettuceConnectionFactory cf = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();

        AdminRpcProperties props = new AdminRpcProperties();
        props.getAdminAuth().setAccessExpire(2592000L);
        props.getAdminAuth().setAccessSecret("admin-domain-test-secret-0123456789");
        props.getSystem().setDefaultPassword("123456");
        AdminRpcMetrics metrics = new AdminRpcMetrics(new SimpleMeterRegistry());

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

        atomic = ctx.getBean(AdminCreateAtomicService.class);
        domain = new AdminDomainService(adminMapper, adminInviteMapper, userMapper,
                redis, props, metrics, atomic);
    }

    // ============== 夹具 ==============

    private static long seedAdmin(String username) {
        long uid = 9000L + Math.abs(username.hashCode() % 900);
        jdbc.update("INSERT INTO admin_admin (uid, username, password, email) VALUES (?, ?, ?, ?)",
                uid, username, PasswordUtil.encrypt("secret-pw"), username + "@example.com");
        return uid;
    }

    private static void seedUser(long uid, String username, long status) {
        jdbc.update("INSERT INTO users_user (uid, username, password, email, status) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (uid) DO NOTHING",
                uid, username, PasswordUtil.encrypt("secret-pw"), username + "@example.com", status);
    }

    private static String seedInvite(long inviterUid, int minutesValid) {
        String code = "ADM-TEST" + Math.abs((inviterUid + minutesValid) % 100000);
        jdbc.update("INSERT INTO admin_invite (code, inviter_uid, expires_at) VALUES (?, ?, ?)",
                code, inviterUid, OffsetDateTime.now().plusMinutes(minutesValid));
        return code;
    }

    // ============== 1. login 三态 ==============

    @Test
    @DisplayName("login：查无此人 ⇒ NOT_FOUND（不是 UNAUTHENTICATED）")
    void loginUnknownUserIsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.login("no-such-admin", "whatever"));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("login：密码错 ⇒ UNAUTHENTICATED")
    void loginWrongPasswordIsUnauthenticated() {
        seedAdmin("login-admin");
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.login("login-admin", "wrong-password"));
        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("login：命中 ⇒ 返回该 admin 的 uid")
    void loginSuccessReturnsUid() {
        long uid = seedAdmin("login-ok-admin");
        assertEquals(uid, domain.login("login-ok-admin", "secret-pw"));
    }

    // ============== 2. 邀请码一次性不变量 ==============

    @Test
    @DisplayName("createAdmin：邀请码为空 ⇒ PERMISSION_DENIED「管理员邀请码错误」")
    void createAdminWithoutInviteIsPermissionDenied() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.createAdmin("someone", "pw", null, null, "  "));
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("createAdmin：有效邀请码 ⇒ 建号成功且邀请码被标记 used（一次性不变量）")
    void createAdminConsumesInviteExactlyOnce() {
        long inviter = seedAdmin("inviter-admin");
        String code = seedInvite(inviter, 10);

        long uid = domain.createAdmin("new-admin-" + inviter, "pw", null, null, code);
        assertTrue(uid > 0);

        // 邀请码已被标记：used_by_uid + used_at 非空
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT used_by_uid, used_at FROM admin_invite WHERE code = ?", code);
        assertNotNull(row.get("used_by_uid"));
        assertNotNull(row.get("used_at"));

        // 第二次用同一邀请码 ⇒ PERMISSION_DENIED（悲观锁 + used 标记双保险）
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.createAdmin("other-admin-" + inviter, "pw", null, null, code));
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("createAdmin：已过期邀请码 ⇒ PERMISSION_DENIED")
    void createAdminWithExpiredInviteIsPermissionDenied() {
        long inviter = seedAdmin("expired-inviter");
        String code = seedInvite(inviter, -5);
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.createAdmin("expired-admin", "pw", null, null, code));
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("createAdmin：username 重名 ⇒ ALREADY_EXISTS，且邀请码**不被消耗**")
    void createAdminDuplicateUsernameKeepsInvite() {
        long inviter = seedAdmin("dup-inviter");
        seedAdmin("dup-admin");
        String code = seedInvite(inviter, 10);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.createAdmin("dup-admin", "pw", null, null, code));
        assertEquals(Status.Code.ALREADY_EXISTS, ex.getStatus().getCode());

        // ⚠️ 关键：W2 查重失败 ⇒ 整个事务回滚 ⇒ 邀请码必须**仍然可用**
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT used_by_uid FROM admin_invite WHERE code = ?", code);
        assertNull(row.get("used_by_uid"), "事务回滚后邀请码不得被标记 used");
    }

    // ============== 3. GORM 的 LIMIT/OFFSET 静默抑制 ==============

    @Test
    @DisplayName("getUserList：page=0 ⇒ 负 offset 被抑制 ⇒ 返回首页（不是 400、不是空）")
    void getUserListWithZeroPageReturnsFirstPage() {
        for (int i = 0; i < 3; i++) {
            seedUser(7000L + i, "page-user-" + i, 0L);
        }
        AdminDomainService.UserListResult result = domain.getUserList(0L, 10L, "page-user-");
        assertEquals(3L, result.total());
        assertEquals(3, result.users().size());
    }

    @Test
    @DisplayName("getUserList：page_size 为负 ⇒ LIMIT 被抑制 ⇒ 返回全量（B 侧 GORM 行为）")
    void getUserListWithNegativePageSizeReturnsAll() {
        seedUser(7100L, "neg-size-user", 0L);
        AdminDomainService.UserListResult result = domain.getUserList(1L, -5L, "neg-size-user");
        assertEquals(1L, result.total());
        assertEquals(1, result.users().size());
    }

    @Test
    @DisplayName("getUserList：keyword **不 trim** ⇒ \" a \" 匹配不到 \"a\"（B 侧口径）")
    void getUserListKeywordIsNotTrimmed() {
        seedUser(7200L, "trimme", 0L);
        AdminDomainService.UserListResult padded = domain.getUserList(1L, 10L, " trimme ");
        assertEquals(0L, padded.total(), "keyword 不得 trim —— B 侧 len>0 即走 LIKE");
    }

    // ============== 4. extra_info 是 text 不是 jsonb ==============

    @Test
    @DisplayName("updateUser：extra_info 含 U+0000 ⇒ 落库成功（text 列；jsonb 会以 22P05 拒收）")
    void updateUserStoresExtraInfoWithNulByte() {
        seedUser(7300L, "nul-user", 0L);
        Map<String, String> extra = new HashMap<>();
        extra.put("k", String.valueOf((char) 0));   // 含 NUL 字符

        domain.updateUser(7300L, 1L, null, null, null, extra);

        String stored = jdbc.queryForObject(
                "SELECT extra_info FROM users_user WHERE uid = ?", String.class, 7300L);
        assertNotNull(stored);
        assertTrue(stored.contains("\\u0000"),
                "extra_info 应以 JSON 文本落库（含转义的 NUL），实际 = " + stored);
    }

    @Test
    @DisplayName("updateUser：username 重名 ⇒ ALREADY_EXISTS")
    void updateUserDuplicateUsernameIsAlreadyExists() {
        seedUser(7400L, "upd-user", 0L);
        seedUser(7401L, "upd-other", 0L);
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.updateUser(7400L, 1L, "upd-other", null, null, null));
        assertEquals(Status.Code.ALREADY_EXISTS, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("updateUser：成功 ⇒ update_time 推进（MP 的 FieldStrategy.NEVER 不会自动推进）")
    void updateUserAdvancesUpdateTime() {
        seedUser(7500L, "time-user", 0L);
        jdbc.update("UPDATE users_user SET update_time = '2000-01-01 00:00:00+00' WHERE uid = 7500");
        Object before = jdbc.queryForObject(
                "SELECT update_time FROM users_user WHERE uid = 7500", Object.class);

        domain.updateUser(7500L, 1L, "time-user-renamed", null, null, null);

        Object after = jdbc.queryForObject(
                "SELECT update_time FROM users_user WHERE uid = 7500", Object.class);
        assertTrue(!before.equals(after), "update_time 必须被 CURRENT_TIMESTAMP 推进");
    }

    // ============== 5. 封禁 / 解封 / 删除 ==============

    @Test
    @DisplayName("banUser / unBanUser：status 在 1 与 0 之间切换")
    void banAndUnbanToggleStatus() {
        seedUser(7600L, "ban-user", 0L);
        domain.banUser(7600L, 1L);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT status FROM users_user WHERE uid = 7600", Long.class));
        domain.unBanUser(7600L, 1L);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT status FROM users_user WHERE uid = 7600", Long.class));
    }

    @Test
    @DisplayName("banUser：用户不存在 ⇒ NOT_FOUND")
    void banUnknownUserIsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.banUser(7699L, 1L));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("deleteUser：删除存在用户 ⇒ 成功；删除不存在用户 ⇒ NOT_FOUND")
    void deleteUserSemantics() {
        seedUser(7700L, "del-user", 0L);
        assertTrue(domain.deleteUser(7700L, 1L));
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.deleteUser(7700L, 1L));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }

    // ============== 6. logout 的 Redis 黑名单 ==============

    @Test
    @DisplayName("logout：token 为空 ⇒ INVALID_ARGUMENT「Token不能为空」")
    void logoutWithEmptyTokenIsInvalidArgument() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.logout(""));
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("logout：写黑名单 ⇒ key 前缀 admin:jwt_blacklist:（与 user 子域区分）")
    void logoutWritesAdminScopedBlacklistKey() {
        String token = "token-for-blacklist-check";
        assertTrue(domain.logout(token));
        assertNotNull(redis.opsForValue().get("admin:jwt_blacklist:" + token),
                "黑名单 key 必须带 admin: 前缀");
        assertNull(redis.opsForValue().get("user:jwt_blacklist:" + token),
                "不得写进 user 子域的命名空间");
    }

    // ============== 7. 邀请码生成 ==============

    @Test
    @DisplayName("createInviteCode：生成 \"ADM-\" 前缀 + 大写 hex，且 expires_at 是绝对时刻")
    void createInviteCodeShape() {
        long inviter = seedAdmin("invite-gen-admin");
        AdminDomainService.InviteCodeResult result = domain.createInviteCode(inviter);
        assertTrue(result.code().startsWith("ADM-"), "code 必须带 ADM- 前缀，实际 = " + result.code());
        assertEquals("ADM-".length() + 16, result.code().length());
        assertTrue(result.code().substring(4).equals(result.code().substring(4).toUpperCase()));
        assertTrue(result.expiresAt() > OffsetDateTime.now().toEpochSecond());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT expires_at FROM admin_invite WHERE code = ?", result.code());
        assertNotNull(row.get("expires_at"));
    }

    @Test
    @DisplayName("createInviteCode：operator 不是 admin ⇒ NOT_FOUND")
    void createInviteCodeWithUnknownOperatorIsNotFound() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> domain.createInviteCode(8888L));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }
}
