package com.rag.users.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真 PostgreSQL 上的 users 域 DDL 重放 + status presence 判定</b>。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/ddl/SchemaInitializer.java:109-136
 *      —— applyAll：advisory lock → 4 个脚本原样重放 → 失败即抛。
 *   B 侧已有的真 PG 证据（本仓沿用同一形态）：
 *      service/users/user/user-rpc/src/test/java/.../UserRpcSchemaTextColumnTest.java
 *        —— 「连跑两次不报错」（幂等）与「text 列」的自愈断言；
 *      service/users/user/user-rpc/src/test/java/.../UserRpcUsersUserTimestamptzParityTest.java
 *        —— 「DDL 真被执行了」的证据口径（本仓沿用：<b>走真实 SchemaInitializer</b>，不是把 SQL 抄一遍）。
 * </pre>
 *
 * <h2>⚠️ 为什么必须跑真 PG（不能用 H2 顶替）</h2>
 * <p>脚本里用到 {@code TIMESTAMPTZ} / {@code ALTER COLUMN ... TYPE ... USING ...} /
 * {@code SET TIME ZONE 'UTC'} / {@code pg_advisory_lock} ⇒ H2 一个都不认
 * ⇒ H2 上「跑绿」只能证明字符串被解析了，不能证明 DDL 真的建对了 ⇒ <b>假绿</b>。
 * <p>⇒ 本类用 Testcontainers 起真 {@code postgres:16-alpine}（与 B 侧同镜像）。
 */
@Testcontainers
class UsersSchemaAndStatusLookupTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initSchema() throws SQLException {
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        try (Connection conn = PG.createConnection("")) {
            SchemaInitializer.applyAll(conn, "users-common-test");
        }
        // ⚠️ **夹具必须在 @BeforeAll 落库**：用例执行顺序不确定 ⇒ 放在单个用例里插，
        //    会让依赖它的端到端用例因「用户不存在」而偶发变红（自举式假红）。
        // 1001 = 活跃（status=0）；1002 = 封禁（status=1）
        jdbc.update("INSERT INTO users_user (uid, username, password, email, status) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (uid) DO NOTHING",
                1001L, "alice", "hash", "a@example.com", 0L);
        jdbc.update("INSERT INTO users_user (uid, username, password, email, status) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (uid) DO NOTHING",
                1002L, "bob", "hash", "b@example.com", 1L);
        // 探针表：status 可空 ⇒ 用来验证「字段不存在」这条分支（users_user.status 是 NOT NULL）
        jdbc.execute("CREATE TABLE IF NOT EXISTS users_user_probe ("
                + "uid BIGINT NOT NULL UNIQUE, status BIGINT)");
        jdbc.update("INSERT INTO users_user_probe (uid, status) VALUES (?, ?) "
                + "ON CONFLICT (uid) DO NOTHING", 2001L, null);
    }

    // ============== DDL 重放 ==============

    @Test
    @DisplayName("applyAll 连跑两次不报错（幂等重放，无版本账本）")
    void applyAllIsIdempotent() {
        assertDoesNotThrow(() -> {
            try (Connection conn = PG.createConnection("")) {
                SchemaInitializer.applyAll(conn, "users-common-test-2nd");
                SchemaInitializer.applyAll(conn, "users-common-test-3rd");
            }
        });
    }

    @Test
    @DisplayName("4 张表都被建出来（B 的 SCRIPTS 逐条对应）")
    void allFourTablesExist() {
        assertTrue(tableExists("users_user"));
        assertTrue(tableExists("users_user_avatar_history"));
        assertTrue(tableExists("admin_admin"));
        assertTrue(tableExists("admin_invite"));
        assertEquals(4, SchemaInitializer.scripts().length);
    }

    @Test
    @DisplayName("列类型按 B 的脚本落地（extra_info=text · create_time=timestamptz）")
    void columnTypesMatchScripts() {
        assertEquals("text", columnType("users_user", "extra_info"));
        assertEquals("text", columnType("users_user", "username"));
        assertEquals("timestamp with time zone", columnType("users_user", "create_time"));
        assertEquals("timestamp with time zone", columnType("users_user_avatar_history", "create_time"));
        assertEquals("text", columnType("admin_admin", "extra_info"));
        assertEquals("timestamp with time zone", columnType("admin_admin", "create_time"));
        assertEquals("timestamp with time zone", columnType("admin_invite", "expires_at"));
    }

    @Test
    @DisplayName("索引建出来了（B 脚本里的 CREATE INDEX IF NOT EXISTS）")
    void indexesExist() {
        assertTrue(indexExists("idx_users_user_uid"));
        assertTrue(indexExists("idx_users_user_avatar_history_uid"));
        assertTrue(indexExists("idx_admin_admin_uid"));
        assertTrue(indexExists("idx_admin_invite_code"));
    }

    @Test
    @DisplayName("status 列是 NOT NULL（B 的 DDL）⇒ presence 在 users_user 上恒为「存在」")
    void statusColumnIsNotNull() {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'users_user' AND column_name = 'status'",
                String.class);
        assertEquals("NO", nullable);
    }

    // ============== JdbcUserStatusLookup 的 presence 语义 ==============

    @Test
    @DisplayName("查无此人 ⇒ notFound（found=false）")
    void unknownUidIsNotFound() {
        IdentityResolver.UserStatusRecord record =
                new JdbcUserStatusLookup(jdbc).findByUid(999_999L);
        assertFalse(record.found());
        assertFalse(record.hasStatus());
    }

    @Test
    @DisplayName("status=0 ⇒ 存在且为 0（活跃）")
    void activeUserHasZeroStatus() {
        IdentityResolver.UserStatusRecord record = new JdbcUserStatusLookup(jdbc).findByUid(1001L);
        assertTrue(record.found());
        assertTrue(record.hasStatus());
        assertEquals(0L, record.status());
        assertEquals(1001L, record.uid());
    }

    @Test
    @DisplayName("status=1 ⇒ 存在且非 0（⇒ 触发 inactive）")
    void bannedUserHasNonZeroStatus() {
        IdentityResolver.UserStatusRecord record = new JdbcUserStatusLookup(jdbc).findByUid(1002L);
        assertTrue(record.found());
        assertTrue(record.hasStatus());
        assertEquals(1L, record.status());
    }

    @Test
    @DisplayName("status 列为 NULL ⇒ presence 缺失（proto3 hasStatus() 的等价表达）")
    void nullStatusMeansAbsent() {
        // users_user.status 是 NOT NULL ⇒ 用一张同构的可空探针表验证「字段不存在」这条分支
        IdentityResolver.UserStatusRecord record =
                new JdbcUserStatusLookup(jdbc, "users_user_probe").findByUid(2001L);
        assertTrue(record.found());
        assertFalse(record.hasStatus(), "NULL 的 status 必须读成「字段不存在」");
        assertNull(record.status());
    }

    // ============== 端到端：真 PG + 真解析器 ==============

    @Test
    @DisplayName("端到端：活跃用户 ⇒ 三元组；封禁用户 ⇒ ErrUserInactive")
    void endToEndAgainstRealPostgres() {
        JdbcUserStatusLookup lookup = new JdbcUserStatusLookup(jdbc);

        MockHttpServletRequest active = new MockHttpServletRequest();
        active.setAttribute(IdentityResolver.CLAIM_ATTRIBUTE, 1001L);
        IdentityResolver.SubjectRef ref = IdentityResolver.resolveSubjectRef(active, lookup);
        assertEquals("rtw.identity", ref.getAuthorityId());
        assertEquals("platform", ref.getTenantId());
        assertEquals("1001", ref.getSubjectId());

        MockHttpServletRequest banned = new MockHttpServletRequest();
        banned.setAttribute(IdentityResolver.CLAIM_ATTRIBUTE, 1002L);
        assertThrows(IdentityResolver.ErrUserInactiveException.class,
                () -> IdentityResolver.resolveSubjectRef(banned, lookup));

        MockHttpServletRequest ghost = new MockHttpServletRequest();
        ghost.setAttribute(IdentityResolver.CLAIM_ATTRIBUTE, 999_999L);
        assertThrows(IdentityResolver.ErrUserNotFoundException.class,
                () -> IdentityResolver.resolveSubjectRef(ghost, lookup));
    }

    @Test
    @DisplayName("advisory lock 键与 B 相同（改键 = 并发建表保护失效）")
    void advisoryLockKeyIsFrozen() {
        assertEquals(8_675_309L, SchemaInitializer.advisoryLockKey());
    }

    // ============== helpers ==============

    private static boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, table);
        return n != null && n > 0;
    }

    private static boolean indexExists(String index) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                Integer.class, index);
        return n != null && n > 0;
    }

    private static String columnType(String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}
