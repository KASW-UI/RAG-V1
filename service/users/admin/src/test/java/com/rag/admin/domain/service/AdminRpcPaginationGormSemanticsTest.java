package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.rag.admin.config.AdminRpcProperties;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.admin.mapper.UserMapper;
import com.rag.admin.metrics.AdminRpcMetrics;
import com.rag.admin.wiring.AdminSchemaBootstrap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * E-3 根因 **R4** 的回归网 —— admin-rpc {@code getUserList} 的分页边界，在<b>真 PG</b> 上。
 *
 * <p><b>为什么必须是真 PG</b>：R4 的内容就是「负 OFFSET / 负 LIMIT 会不会真的打到 PG」。
 * 纯 Mockito 的 mapper 测试<b>根本不生成 SQL</b>，无论 {@code goStylePagination} 返回什么
 * 字符串都测不到 PG 的 {@code 22023: OFFSET must not be negative}。
 * 只有真 PG 能证明「这条 SQL 被执行了、且没有报错」。
 *
 * <p><b>Go 的权威判据（两处来源，互相印证）</b>：
 * <ol>
 *   <li><b>GORM 源码</b>：gorm.io/gorm@v1.31.1 {@code clause/limit.go} 的 {@code Limit.Build}
 *       —— {@code LIMIT} 仅在 {@code *Limit >= 0} 时生成，
 *       {@code OFFSET} 仅在 {@code Offset > 0} 时生成；</li>
 *   <li><b>真进程实测</b>：Go admin-api（18889）curl 直打
 *       {@code page=0} / {@code page=-1} / {@code page_size=-5} /
 *       {@code page=9223372036854775807} —— <b>四种全部 200 + code 200</b>。</li>
 * </ol>
 *
 * <p><b>修之前的形态</b>：{@code listWrapper.last("LIMIT " + pageSize + " OFFSET " + offset)}
 * 原样下推 ⇒ PG 报 22023 ⇒ {@code Status.INTERNAL} ⇒ api 层映射成 <b>5001</b>，
 * 而 Go 是 200。且当时的 javadoc 写的是「Go 无任何保护，page=0 会让 PG 抛错」
 * —— 那句话<b>引用了真实的 model/admin.go，但把 GORM 的行为读反了</b>，
 * 与 §3.4.4 DRIFT-009 同型。
 *
 * <p><b>B 出处</b>：
 * <pre>
 *   B 源文件（绝对路径）：
 *     /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcPaginationGormSemanticsTest.java
 *   B 关键行号：
 *     :48-57    @SpringBootTest + @Testcontainers + @EnabledIfDockerAvailable（后者已按 AGENTS.md §五.8 ③ 删除）
 *     :60-65    PostgreSQLContainer
 *     :85-94    #seed（清表 + 种 5 行 users_user）
 *     :100-113  #paginationStringMirrorsGormBuild（纯函数层）
 *     :119-125  #pageZeroSuppressesOffset
 *     :127-132  #negativePageSuppressesOffset
 *     :134-140  #negativePageSizeSuppressesBoth
 *     :142-150  #int64MaxPageWrapsToNegativeOffset
 *     :152-161  #normalPaginationStillWorks
 *     :163-173  #theOldSqlWouldIndeedFailOnRealPg（变异测试：证明本类不是恒绿）
 * </pre>
 *
 * <p>本类由 B 侧同名类移植，<b>期望值沿用 B 侧（D13）</b> ⇒ <b>断言值 / 断言语义 /
 * {@code @DisplayName} 原文一字未改</b>。
 *
 * <p><b>🔴 C 侧移植的两处机制改动（<b>断言值全部未动</b>）</b>：
 * <ol>
 *   <li><b>不用 {@code @SpringBootTest}，改用「最小 Spring 上下文」</b>
 *       （骨架照抄 C 侧既有 {@code AdminDomainServiceRealPgTest}）——
 *       B 侧的 {@code @SpringBootTest(classes = AdminRpcApplication.class)} 会把
 *       Tomcat + gRPC + Nacos + Redis 全拉起来，而本类 7 条断言<b>一条都不碰它们</b>
 *       （纯函数层 1 条 + 只读 users_user 的 5 条 + 手工 SQL 的变异测试 1 条）。
 *       <br>⚠️ B 侧注释里那条「刻意**不**设 {@code spring.cloud.nacos.discovery.enabled=false}」
 *       是<b>针对全量应用上下文</b>的（C 侧最小上下文里<b>根本没有 Nacos</b>）⇒ 不适用。</li>
 *   <li><b>删掉 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）。</li>
 * </ol>
 *
 * <p><b>⚠️ 数据隔离</b>：本类<b>自带独立容器</b>（C 侧 admin 域<b>没有</b>共享的
 * {@code TestPostgres} 单例类）⇒ 与其它测试类<b>不共用物理库</b>；
 * 类内 {@code delete from users_user} 只清自己那张表 ⇒ 绝对表计数断言安全。
 */
@Testcontainers
class AdminRpcPaginationGormSemanticsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("admin_rpc_page_it")
                    .withUsername("rag")
                    .withPassword("rag");

    private static AdminDomainService adminDomainService;
    private static JdbcTemplate jdbcTemplate;

    /** 只为拿到「@Transactional 代理」而存在的最小配置类。 */
    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    @BeforeAll
    static void setUp() throws Exception {
        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);

        // ── 建表：**生产同一条路径**（域内 wiring 件 → 共享层 SchemaInitializer 幂等重放）──
        new AdminSchemaBootstrap(ds).initSchema();

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(AdminMapper.class);
        cfg.addMapper(AdminInviteMapper.class);
        cfg.addMapper(UserMapper.class);
        cfg.setEnvironment(new Environment("admin-rpc-page-semantics", new SpringManagedTransactionFactory(), ds));
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AdminMapper adminMapper = template.getMapper(AdminMapper.class);
        AdminInviteMapper adminInviteMapper = template.getMapper(AdminInviteMapper.class);
        UserMapper userMapper = template.getMapper(UserMapper.class);

        AdminRpcProperties props = new AdminRpcProperties();
        props.getAdminAuth().setAccessExpire(2592000L);
        props.getAdminAuth().setAccessSecret("admin-page-semantics-secret-0123456789");
        props.getSystem().setDefaultPassword("123456");
        AdminRpcMetrics metrics = new AdminRpcMetrics(new SimpleMeterRegistry());

        // ⚠️ Redis：本类 7 条断言一条都不碰 Redis ⇒ 不起容器，用空壳替身即可。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

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
        adminDomainService = new AdminDomainService(adminMapper, adminInviteMapper, userMapper,
                redis, props, metrics, atomic);
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from users_user");
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update(
                    "insert into users_user (uid, username, email, status, create_time, update_time)"
                            + " values (?,?,?,0, now(), now())",
                    1000L + i, "page_user_" + i, "page_user_" + i + "@x.com");
        }
    }

    // ==================================================================
    // 纯函数层：goStylePagination 的规则本身
    // ==================================================================

    @Test
    @DisplayName("R4 规则：LIMIT 仅在 pageSize>=0 时生成，OFFSET 仅在 offset>0 时生成")
    void paginationStringMirrorsGormBuild() {
        // GORM clause/limit.go 的四条分支，逐条对齐
        assertEquals("LIMIT 20", AdminDomainService.goStylePagination(20, 0),
                "offset=0 → GORM 不生成 OFFSET");
        assertEquals("LIMIT 20 OFFSET 20", AdminDomainService.goStylePagination(20, 20));
        assertEquals("OFFSET 5", AdminDomainService.goStylePagination(-5, 5),
                "pageSize<0 → GORM 不生成 LIMIT，但 OFFSET 仍生成（且前面没有空格）");
        assertEquals("", AdminDomainService.goStylePagination(-5, -5),
                "两个都被抑制 → 空片段，绝不能拼出 'LIMIT -5 OFFSET -5'");
        assertEquals("LIMIT 0", AdminDomainService.goStylePagination(0, 0),
                "pageSize=0 是 >= 0 ⇒ GORM 仍生成 LIMIT 0");
    }

    // ==================================================================
    // 真 PG 层：这些 SQL 必须真的跑得通
    // ==================================================================

    @Test
    @DisplayName("R4：page=0 → 不报错，返回首页（Go 是 200；修复前 RAG 是 5001）")
    void pageZeroSuppressesOffset() {
        AdminDomainService.UserListResult r = adminDomainService.getUserList(0L, 2L, "page_user");
        assertEquals(5L, r.total());
        assertEquals(2, r.users().size(), "offset=-20 被抑制 ⇒ 不带 OFFSET，等价于首页");
    }

    @Test
    @DisplayName("R4：page=-1 → 不报错，返回首页")
    void negativePageSuppressesOffset() {
        AdminDomainService.UserListResult r = adminDomainService.getUserList(-1L, 2L, "page_user");
        assertEquals(2, r.users().size(), "offset=-40 被抑制");
    }

    @Test
    @DisplayName("R4：page_size=-5 → LIMIT 与 OFFSET 双双被抑制，返回全量（Go 是全量，不是 400）")
    void negativePageSizeSuppressesBoth() {
        AdminDomainService.UserListResult r = adminDomainService.getUserList(1L, -5L, "page_user");
        assertEquals(5L, r.total());
        assertEquals(5, r.users().size(), "GORM 对负 LIMIT 是静默忽略 ⇒ 全量返回");
    }

    @Test
    @DisplayName("R4：page=int64max → int64 回绕成负 offset，被抑制后仍返回 200")
    void int64MaxPageWrapsToNegativeOffset() {
        // Go: (2^63-2)*20 在 int64 下回绕成 -64 → 负 → 不生成 OFFSET
        // ⚠️ 这一条同时锁住「必须用 long 算」——用 int 算 page 会先在窄化处溢出成别的值
        AdminDomainService.UserListResult r =
                adminDomainService.getUserList(Long.MAX_VALUE, 2L, "page_user");
        assertEquals(2, r.users().size());
    }

    @Test
    @DisplayName("R4 对照：正常分页不受影响（第 2 页应与第 1 页内容不同）")
    void normalPaginationStillWorks() {
        AdminDomainService.UserListResult p1 = adminDomainService.getUserList(1L, 2L, "page_user");
        AdminDomainService.UserListResult p2 = adminDomainService.getUserList(2L, 2L, "page_user");
        assertEquals(2, p1.users().size());
        assertEquals(2, p2.users().size());
        assertNotEquals(p1.users().get(0).getId(), p2.users().get(0).getId(),
                "第 2 页的第 1 行不该等于第 1 页的第 1 行 —— 说明 OFFSET 没生效");
    }

    @Test
    @DisplayName("R4 对照：修复前的形态必须真的会被 PG 拒掉（证明这条用例不是恒绿）")
    void theOldSqlWouldIndeedFailOnRealPg() {
        // 变异测试：手工执行修复前的拼法，PG 必须报 22023。
        // 没有这条，上面几条就可能因为「压根没查库」而假绿。
        var ex = assertThrows(Exception.class, () -> jdbcTemplate.queryForList(
                "select uid from users_user order by id desc LIMIT -5 OFFSET -10"));
        assertTrue(String.valueOf(ex.getMessage()).contains("OFFSET must not be negative")
                        || String.valueOf(ex.getCause()).contains("OFFSET must not be negative"),
                "PG 没有以 22023 拒掉负 OFFSET，测试前提不成立：" + ex.getMessage());
    }
}
