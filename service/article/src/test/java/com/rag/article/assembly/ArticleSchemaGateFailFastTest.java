package com.rag.article.assembly;

import com.rag.article.ArticleServiceApplication;
import com.rag.article.support.NacosStubConfiguration;
import com.rag.article.support.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>{@code ArticleSchemaGate} 的 fail-fast 真生效</b> ——
 * 「缺 {@code article_revision_immutable} 触发器 ⇒ 进程不启动」这条机制的真触发证据。
 *
 * <h2>🔴 为什么这条闸必须 fail-fast（而不是「启动时打个 warn」）</h2>
 * <p>article 域缺这个触发器，意味着「<b>已发布文章的字节不可变</b>」这条保证被<b>静默取消</b>：
 * 进程照常启动、审核照常发布，但任何一次误 UPDATE 都能改写已发布正文，
 * <b>且不会有任何报错</b>。
 * <p>⇒ Go 侧对此 {@code return err} ⇒ 上层 {@code panic} ⇒ <b>进程不启动</b>。
 * ⇒ C 侧对应物是 {@code ArticleSchemaGate}（{@code SmartInitializingSingleton}），
 * 落在「所有单例已实例化」与「任何 {@code SmartLifecycle} 被启动」<b>之间</b>
 * ⇒ ⇒ <b>缺触发器时进程从不收流量</b>（gRPC server 不绑端口、Kafka 消费者不拉消息）。
 *
 * <h2>🔴 本用例怎么造出「缺触发器」的 schema（<b>不动正式迁移脚本</b>）</h2>
 * <p>⚠️ <b>禁止</b>改 {@code service/article/src/main/resources/db/migration/} 里的正式迁移脚本
 * ⇒ ⇒ 本用例：
 * <ol>
 *   <li>把 classpath 上的 {@code V8 / V9 / V10} <b>原样复制</b>到临时目录；</li>
 *   <li>再加一条 {@code V11}：{@code DROP TRIGGER IF EXISTS article_revision_immutable ...}
 *       ⇒ Flyway 跑完 ⇒ <b>五张表都在、但触发器没了</b>
 *       （这正是「迁移跑了、但不可变保证没了」的真实故障形态）；</li>
 *   <li>把 {@code spring.flyway.locations} 指向那个临时目录，
 *       并把数据源指向一个<b>专用数据库</b>（避免与其它测试共用的库撞账本）。</li>
 * </ol>
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>缺触发器 ⇒ 拒绝启动</td>
 *       <td>Go {@code internal/model/db.go:62-70}（B 侧 javadoc 转录）</td>
 *       <td>{@code wiring/ArticleSchemaGate.java:192-197}</td></tr>
 *   <tr><td>报错文案</td>
 *       <td>Go {@code db.go:70}
 *           ⇒ {@code community revision migration 002_community_revision.sql is required before article RPC startup}</td>
 *       <td>{@code wiring/ArticleSchemaGate.java:194-196}</td></tr>
 *   <tr><td>校验时机（必须在收流量之前）</td>
 *       <td>Go {@code article.go:41-44}（模型建库 ⇒ panic ⇒ 之后才建 server）</td>
 *       <td>{@code wiring/ArticleSchemaGate.java:69-103}（{@code SmartInitializingSingleton}）</td></tr>
 *   <tr><td>触发器名（不可改）</td>
 *       <td>Go {@code db.go:63-65} ⇒ {@code article_revision_immutable}</td>
 *       <td>{@code wiring/ArticleSchemaGate.java:118}</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code ArticleSchemaGate} 的 {@code afterSingletonsInstantiated} 改成不抛
 *       ⇒ 用例 <b>立刻红</b>（启动不再失败）；</li>
 *   <li>把触发器名改掉 ⇒ 用例 <b>立刻红</b>（报错文案与判据对不上）；</li>
 *   <li>把 {@code REQUIRED_TABLES} 缩成 1 张表 ⇒ 本用例仍应红在<b>触发器</b>那条
 *       （说明判据确实落在触发器上，而不是落在「表缺失」上）。</li>
 * </ul>
 */
class ArticleSchemaGateFailFastTest {

    /** 专用数据库名（与其它测试共用的库隔离 ⇒ 避开 Flyway 账本撞号）。 */
    private static final String PROBE_DB = "article_gate_probe";

    @TempDir
    Path migrationDir;

    // ==================================================================
    // 缺触发器 ⇒ 启动失败（真启动，真失败）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：schema 里缺 article_revision_immutable 触发器 ⇒ 进程**启动失败**"
            + "（报错文案与 Go 侧逐字一致），而不是「打个 warn 继续跑」）")
    void missingImmutabilityTriggerRefusesToStart() throws Exception {
        Path migrations = copyMigrationsAndDropTrigger();
        String jdbcUrl = probeDatabaseUrl();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.username", TestPostgres.username());
        properties.put("spring.datasource.password", TestPostgres.password());
        properties.put("spring.flyway.enabled", true);
        properties.put("spring.flyway.locations", "filesystem:" + migrations.toAbsolutePath());
        properties.put("spring.cloud.nacos.discovery.server-addr", "127.0.0.1:1");
        properties.put("management.otlp.tracing.export.enabled", false);
        properties.put("grpc.server.port", 0);
        properties.put("grpc.client.message-rpc.address", "static://127.0.0.1:1");
        properties.put("spring.kafka.bootstrap-servers", "127.0.0.1:1");
        properties.put("spring.kafka.producer.properties.max.block.ms", 2000);
        properties.put("article-rpc.kq-pusher-conf.brokers", "127.0.0.1:1");
        properties.put("article-rpc.kq-consumer-conf.brokers", "127.0.0.1:1");
        properties.put("article-rpc.article-sync-pusher-conf.brokers", "127.0.0.1:1");
        properties.put("article-rpc.article-sync-result-consumer-conf.brokers", "127.0.0.1:1");
        properties.put("article-rpc.minio.endpoint", "127.0.0.1:1");
        properties.put("POSTGRES_PASSWORD", "password");
        properties.put("USER_JWT_SECRET", "test-only-article-jwt-secret-at-least-32-bytes-long");

        Exception failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder()
                .sources(ArticleServiceApplication.class, NacosStubConfiguration.class)
                .web(WebApplicationType.NONE)
                // 🔴 **必须用 `initializers` + `addFirst`，不能用 `SpringApplicationBuilder#properties(Map)`**
                //   —— 后者加进去的是 **defaultProperties（最低优先级）** ⇒ 会被 yml 里的
                //      `spring.datasource.url: jdbc:postgresql://${POSTGRES_HOST:postgres}:…`
                //      **压过** ⇒ 连的是 host `postgres`（不是容器）⇒ 上下文因**无关原因**失败
                //      ⇒ ⇒ 断言就会落在「连不上库」而不是「缺触发器」上 ⇒ **判据跑偏**。
                .initializers((ApplicationContextInitializer<ConfigurableApplicationContext>) ctx ->
                        ctx.getEnvironment().getPropertySources()
                                .addFirst(new MapPropertySource("article-gate-probe", properties)))
                .run(),
                "🔴 缺 article_revision_immutable 触发器时进程**必须启动失败**"
                        + "（fail-fast，而不是打个 warn 继续跑）");

        String chain = describeCauses(failure);
        assertTrue(chain.contains("community revision migration 002_community_revision.sql is required"),
                "🔴 报错文案必须与 Go 侧 db.go:70 逐字一致；实际异常链 = " + chain);
        assertTrue(chain.contains("article_revision_immutable"),
                "报错必须点名那个触发器；实际异常链 = " + chain);
        assertFalse(chain.contains("missing tables"),
                "⚠️ 五张表都应已由 Flyway 建好（否则判据落在「表缺失」上而不是「触发器缺失」上）；"
                        + "实际异常链 = " + chain);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 把 classpath 上的正式迁移<b>原样复制</b>到临时目录，再加一条「把触发器删掉」的 V11。
     *
     * @return 临时迁移目录
     */
    private Path copyMigrationsAndDropTrigger() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:db/migration/*.sql");
        assertTrue(resources.length >= 3,
                "应当至少找到 V8 / V9 / V10 三份正式迁移；实际 = " + resources.length);

        for (Resource resource : resources) {
            String name = resource.getFilename();
            assertNotNull(name);
            Path target = migrationDir.resolve(name);
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // ⚠️ 这条迁移是「故障注入」：迁移照跑，但不可变触发器被删掉
        //   ⇒ 于是「迁移跑了」与「不可变保证没了」同时成立 —— 正是这道闸要拦的形态
        Path drop = migrationDir.resolve("V11__probe_drop_article_revision_immutable_trigger.sql");
        Files.writeString(drop,
                "-- 测试用故障注入：模拟「迁移跑了，但不可变触发器没了」\n"
                        + "DROP TRIGGER IF EXISTS article_revision_immutable ON article_revision;\n",
                StandardCharsets.UTF_8);
        return migrationDir;
    }

    /**
     * 造一个<b>专用数据库</b>并返回它的 JDBC URL。
     *
     * <p>⚠️ 为什么不用共用库：{@code flyway_schema_history} 是 <b>schema 级</b>账本
     * ⇒ 共用库会让本用例的迁移与其它测试的迁移互相污染（AGENTS.md §六 记的「账本撞号」问题）。
     */
    private static String probeDatabaseUrl() throws Exception {
        String base = TestPostgres.jdbcUrl();
        // ⚠️ Testcontainers 的 URL 可能带查询串（如 ?loggerLevel=OFF）⇒ 只替换库名那一段
        int idx = base.indexOf("/first_db");
        assertTrue(idx > 0, "TestPostgres 的默认库名变了：" + base);
        String probeUrl = base.substring(0, idx) + "/" + PROBE_DB + base.substring(idx + "/first_db".length());
        try (Connection connection = DriverManager.getConnection(
                base, TestPostgres.username(), TestPostgres.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + PROBE_DB);
        } catch (Exception e) {
            // 库已存在（同一 JVM 重跑）⇒ 不致命
            if (!e.getMessage().contains("already exists")) {
                throw e;
            }
        }
        return probeUrl;
    }

    /** 把异常链上的全部消息拼成一段文本（便于逐字断言）。 */
    private static String describeCauses(Throwable throwable) {
        List<String> messages = new ArrayList<>();
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 20) {
            messages.add(current.getClass().getName() + ": " + current.getMessage());
            current = current.getCause();
        }
        return String.join(" || ", messages);
    }
}
