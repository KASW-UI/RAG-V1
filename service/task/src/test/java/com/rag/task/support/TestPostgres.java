package com.rag.task.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * <p><b>测试用真 PostgreSQL（进程内单例容器）</b>。</p>
 *
 * <h2>⚠️ 为什么必须跑真 PG（不能用 H2 顶替）</h2>
 * <p>本域的 DDL 用到 {@code TIMESTAMPTZ} / {@code ON CONFLICT (user_id, task_id) DO NOTHING} /
 * {@code GREATEST(...)} / {@code COALESCE(...)} / <b>复合主键</b>
 * ⇒ H2 一个都不认（尤其 {@code GREATEST} 与 {@code ON CONFLICT ... DO UPDATE} 的方言差异）
 * ⇒ H2 上「跑绿」只能证明字符串被解析了，<b>不能证明三条 upsert 的语义真的对</b> ⇒ <b>假绿</b>。
 * <p>⇒ 本类用 Testcontainers 起真 {@code postgres:16-alpine}（与 B 侧同镜像）。</p>
 *
 * <h2>为什么是「单例」而不是每个测试类一个容器</h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 每个类各起一个容器 ⇒ 慢 3 倍且机器上同时跑多个 PG。
 * ⇒ ⇒ 单例：<b>整个 JVM 起一次</b>，各测试类共用同一个库。
 * <p>⚠️ <b>共用库 ⇒ 数据必须互不冲突</b> ⇒ 各测试类用<b>唯一 uid</b>（带类名前缀的序号段）。</p>
 *
 * <p>📌 <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ ⇒ 容器不可用时<b>直接失败</b>，而不是静默跳过（跳过 = 假绿）。</p>
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
     *
     * <p>⚠️ 本仓主 {@code application.yml} 里 {@code spring.datasource.password: ${POSTGRES_PASSWORD}}
     * <b>没有默认值</b>（fail-closed 纪律）⇒ 必须由外部注入，否则占位符解析失败。</p>
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
