package com.rag.user.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * <p><b>测试用真 PostgreSQL（进程内单例容器）</b>。
 *
 * <h2>⚠️ 为什么必须跑真 PG（不能用 H2 顶替）</h2>
 * <p>本域的 DDL 里用到 {@code TIMESTAMPTZ} / {@code ALTER COLUMN ... TYPE ... USING ...} /
 * {@code SET TIME ZONE 'UTC'} / {@code pg_advisory_lock} ⇒ H2 一个都不认
 * ⇒ H2 上「跑绿」只能证明字符串被解析了，<b>不能证明 DDL 真的建对了</b> ⇒ <b>假绿</b>。
 * <p>⇒ 本类用 Testcontainers 起真 {@code postgres:16-alpine}（与 B 侧同镜像）。
 *
 * <h2>为什么是「单例」而不是每个测试类一个容器</h2>
 * <p>本域有 3 个 {@code @SpringBootTest}（context / HTTP 契约 / gRPC 契约）⇒ 每个类各起一个容器
 * ⇒ 慢 3 倍且机器上同时跑 3 个 PG。
 * ⇒ ⇒ 单例：<b>整个 JVM 起一次</b>，各测试类共用同一个库。
 * ⚠️ <b>共用库 ⇒ 数据必须互不冲突</b> ⇒ 各测试类用<b>唯一用户名</b>（带随机后缀）。
 * ⚠️ <b>DDL 幂等</b> ⇒ 多个上下文各自跑一次 {@code UserSchemaBootstrap} 也安全
 * （本域的建表走 {@code users-common} 的 {@code SchemaInitializer}，幂等重放 + advisory lock）。
 *
 * <p>📌 <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ ⇒ 容器不可用时**直接失败**，而不是静默跳过（跳过 = 假绿）。
 */
public final class TestPostgres {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    static {
        PG.start();
    }

    private TestPostgres() {
    }

    /**
     * 把容器连接信息注入 Spring 的 {@code spring.datasource.*}。
     */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    /**
     * 容器的 JDBC URL —— 供测试里直接用 {@code JdbcTemplate} 查证据。
     */
    public static String jdbcUrl() {
        return PG.getJdbcUrl();
    }

    public static String username() {
        return PG.getUsername();
    }

    public static String password() {
        return PG.getPassword();
    }
}
