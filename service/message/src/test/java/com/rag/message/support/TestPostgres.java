package com.rag.message.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * <p><b>测试用真 PostgreSQL（进程内单例容器）</b>。
 *
 * <h2>⚠️ 为什么必须跑真 PG（不能用 H2 顶替）</h2>
 * <p>本域的 DDL 里用到 {@code TIMESTAMPTZ} / {@code ALTER COLUMN ... TYPE ... USING ... AT TIME ZONE ...} /
 * {@code SET TIME ZONE 'Asia/Shanghai'} / {@code JSONB} / {@code CREATE UNIQUE INDEX ... IF NOT EXISTS} /
 * {@code ALTER TABLE ... DROP CONSTRAINT IF EXISTS}
 * ⇒ H2 一个都不认（H2 也不认 {@code jsonb} 的 {@code ps.setObject(…, Types.OTHER)} 绑定）
 * ⇒ H2 上「跑绿」只能证明字符串被解析了，<b>不能证明 DDL 真的建对了</b> ⇒ <b>假绿</b>。
 * ⇒ ⇒ 本类用 Testcontainers 起真 {@code postgres:16-alpine}（<b>与 B 侧同镜像</b>）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/resources/db/migration/}
 * （V1~V5 五个迁移脚本的 DDL 逐字节照抄 B 侧 ⇒ 断言的对象就是 B 侧的 DDL 本身）。
 *
 * <h2>为什么是「单例」而不是每个测试类一个容器</h2>
 * <p>本域的 {@code MessageDomainServiceRealPgTest} 需要<b>真 Flyway 迁移 + 真 MP 装配 + 真事务代理</b>
 * ⇒ 起容器本身就很贵 ⇒ 整个 JVM 起一次，各测试类共用同一个库。
 * ⚠️ <b>共用库 ⇒ 数据必须互不冲突</b> ⇒ 各测试用例用<b>互不重叠的参与者 id / 收件人 id</b>
 * （本仓的 {@code uk_conversation_pair} 是真 PG 上的唯一索引 ⇒ 同 pair 第二次插入会真冲突）。
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
