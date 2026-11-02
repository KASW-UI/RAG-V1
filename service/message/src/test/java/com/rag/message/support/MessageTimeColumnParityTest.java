package com.rag.message.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>DRIFT-MSG-V-020 的真 PG 守门</b>：断言**运行中的库**里 8 个时间列确实是
 * {@code timestamp with time zone}，且可空性与真源一致。
 *
 * <h3>为什么不能只靠「读代码 + 反射断言字段类型」</h3>
 * <p>实体改成 {@code OffsetDateTime} 只能证明<b>Java 侧</b>准备好了；
 * 列类型若仍是 {@code TIMESTAMP}，pgjdbc 会<b>静默丢掉 offset</b>，
 * 读回来仍是无时区墙钟 —— 而这一切<b>编译通过、单测全绿</b>。
 * 这是 AGENTS.md §3.3.3「注解/类型存在 ≠ 机制生效」的 DDL 版本。
 * ⇒ 必须连真 PG 查 {@code information_schema.columns}。
 *
 * <h3>Go 侧基准</h3>
 * <pre>
 *   gorm.io/driver/postgres@v1.6.0/postgres.go:251-255
 *       case schema.Time: ... return "timestamptz"
 *   双侧活体 dump（同一个 postgres:16-alpine）实测：
 *       Go  的 created_at / updated_at  可空（tag 里没有 not null）
 *       只有 Conversation.LastMessageAt 带 not null
 * </pre>
 *
 * <p>⚠️ 本类只断言<b>列类型与可空性</b>；{@code DEFAULT CURRENT_TIMESTAMP} 与真源
 * 不同，属<b>刻意保留</b>的残差（两侧 INSERT 都显式绑定这些列 ⇒ 默认永不触发），
 * 原因与登记见 {@code V5} 迁移的头部注释与 DRIFT 台账 V-020 节。
 *
 * <h3>B 出处（真源 · 只读参考）</h3>
 * <pre>
 *   B: /Users/pr/Documents/java&amp;golang/RAG/service/message/message-rpc/src/test/java/
 *      com/rag/message/message/rpc/MessageRpcTimeColumnParityTest.java
 *      :26-49    类注释（为什么不能只靠「读代码 + 反射断言字段类型」）
 *      :97-110   expectedColumns() —— 8 列名 + 可空性 + timestamp with time zone
 *      :112-140  用例①：8 个时间列的列类型与可空性
 *      :142-175  用例②：写入 +08:00 的瞬时 ⇒ 读回同一瞬时
 *   B: /Users/pr/Documents/java&amp;golang/RAG/service/message/message-rpc/src/main/resources/
 *      db/migration/V1..V5（DDL 真源 · C 侧 V1..V5 与之**逐字节相同**）
 * </pre>
 *
 * <p>📌 <b>本类由 B 侧 {@code MessageRpcTimeColumnParityTest} 移植，期望值沿用 B 侧（D13）</b>：
 * 8 个列名、可空性、列类型字符串与两个 {@code @DisplayName} <b>逐字沿用</b> B 侧原文，
 * <b>一个字都没改</b>（AGENTS.md §1.0c「测试资产单独一档」· D13「期望值沿用 B 侧」）。
 *
 * <h3>⚠️ 与 B 侧的实现形态差异（只改<b>怎么连库</b>，不改<b>断言什么</b>）</h3>
 * <ul>
 *   <li>B 侧用 {@code @SpringBootTest(classes = MessageRpcApplication.class)} + 自己的
 *       {@code @Container PostgreSQLContainer} + {@code @DynamicPropertySource} 注入数据源；
 *       C 侧本仓<b>没有</b> {@code MessageRpcApplication}（D7/D1 把 api/rpc 压平成
 *       {@code com.rag.message.MessageServiceApplication}），且<b>不允许</b>为了一个 DDL 断言
 *       把整个 app 上下文（Tomcat + gRPC + Nacos + Actuator + OTel）拉起来。</li>
 *   <li>⇒ 改为<b>轻量形态</b>：复用本域共享的<b>进程内单例容器</b>
 *       {@link TestPostgres}（<b>不起自己的容器</b>）＋ {@code DriverManagerDataSource}
 *       ＋ {@link JdbcTemplate} 直连；Flyway 迁移<b>先跑</b>（否则表不存在）。</li>
 *   <li>Flyway 的配置（独立账本表 {@code flyway_schema_history_message} ＋
 *       {@code baseline-on-migrate} ＋ {@code baseline-version: 0} ＋ {@code locations}
 *       ＋ {@code schemas}）<b>直接取自 C 侧 application.yml 的 spring.flyway 段</b>
 *       （照抄 {@code MessageDomainServiceRealPgTest#bindFlywayProperties}），
 *       ⇒ 断言的对象是「yml 里那几行配置」本身，而不是测试里另抄一份。</li>
 * </ul>
 *
 * <p>⚠️ <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ 容器不可用时<b>直接失败</b>，而不是静默跳过（跳过 = 假绿）。
 * ⚠️ 本类<b>不用 Lombok</b>（AGENTS.md §1.0e / D18：DTO 层与测试层不引入）。
 */
class MessageTimeColumnParityTest {

    /**
     * 本用例专属的通知 id —— <b>固定值</b>（B 侧用的是 {@code System.currentTimeMillis()}）。
     *
     * <p>⚠️ <b>为什么改成固定值</b>：共享单例容器是<b>同一个库</b>，
     * 本域其它测试类也用它 ⇒ 用时间戳当 id 会<b>撞主键</b>（{@code message_notification.id}
     * 是 {@code BIGINT PRIMARY KEY}）⇒ 偶发红，且红的原因与本用例无关。
     * <p>⚠️ 取值 {@code 9_900_001L}：本域其它测试类用的是
     * {@code 3_001~3_003} / {@code 4_001~4_003} / {@code 5_000_001} /
     * {@code 9_100_001,9_100_002,9_100_011,9_100_012} ⇒ <b>不与之重叠</b>。
     */
    private static final long NOTIFICATION_ID = 9_900_001L;

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                TestPostgres.jdbcUrl(), TestPostgres.username(), TestPostgres.password());
        jdbcTemplate = new JdbcTemplate(ds);

        // ── Flyway：配置**直接取自 C 侧 application.yml 的 spring.flyway 段** ──
        //    ⇒ 断言的对象是「yml 里那几行配置」本身，而不是测试里另抄一份。
        //    （照抄 MessageDomainServiceRealPgTest#setUp 的 ① 段 —— 逐字。）
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

    /** 列名 → Go 侧期望（isNullable 取自 GORM tag：没有 not null 就是可空）。 */
    private static Map<String, String[]> expectedColumns() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("message_notification.read_at", new String[]{"YES", "timestamp with time zone"});
        m.put("message_notification.created_at", new String[]{"YES", "timestamp with time zone"});
        m.put("message_notification.updated_at", new String[]{"YES", "timestamp with time zone"});
        // ⚠️ Go 的 LastMessageAt 带 `not null` ⇒ 两侧都必须是 NO
        m.put("message_conversation.last_message_at", new String[]{"NO", "timestamp with time zone"});
        m.put("message_conversation.created_at", new String[]{"YES", "timestamp with time zone"});
        m.put("message_conversation.updated_at", new String[]{"YES", "timestamp with time zone"});
        m.put("message_conversation_message.read_at", new String[]{"YES", "timestamp with time zone"});
        m.put("message_conversation_message.created_at", new String[]{"YES", "timestamp with time zone"});
        return m;
    }

    @Test
    @DisplayName("🔴 V-020：8 个时间列在真 PG 里必须是 timestamp with time zone，且可空性与真源一致")
    void allTimeColumnsAreTimestamptzWithGoMatchingNullability() {
        List<String> found = expectedColumns().keySet().stream().toList();
        String inClause = "'" + String.join("','", found) + "'";

        Map<String, Map<String, String>> actual = new LinkedHashMap<>();
        jdbcTemplate.query("select table_name||'.'||column_name as col, is_nullable, data_type "
                        + "from information_schema.columns "
                        + "where table_schema='public' and (table_name||'.'||column_name) in (" + inClause + ")",
                rs -> {
                    actual.put(rs.getString("col"), Map.of(
                            "is_nullable", rs.getString("is_nullable"),
                            "data_type", rs.getString("data_type")));
                });

        assertEquals(8, actual.size(), "8 个时间列应当全部存在；实际查到 " + actual.keySet()
                + "⇒ 是不是 V5 迁移没跑？（Flyway table=flyway_schema_history_message）");

        expectedColumns().forEach((col, want) -> {
            Map<String, String> got = actual.get(col);
            assertNotNull(got, col + " 未找到");
            assertEquals(want[1], got.get("data_type"),
                    col + " 列类型应与 GORM schema.Time→timestamptz 对齐；"
                            + "若仍是 timestamp without time zone，pgjdbc 会静默丢掉 offset");
            assertEquals(want[0], got.get("is_nullable"),
                    col + " 可空性应与 GORM tag 一致（tag 里没有 not null 就是可空）");
        });
    }

    /**
     * 端到端往返：写入一个**非 UTC offset** 的瞬时，读回来必须是**同一瞬时**。
     *
     * <p>这条比列类型断言更强：列类型对但映射器用错（例如把 offset 丢掉再按 JVM 时区补）
     * 时它照样会红。
     */
    @Test
    @DisplayName("往返：写入 +08:00 的瞬时 ⇒ 读回同一瞬时（offset 不丢、不按 JVM 时区重解释）")
    void instantRoundTripsWithoutLosingOffset() {
        OffsetDateTime written = OffsetDateTime.of(2026, 10, 23, 2, 0, 0, 0, ZoneOffset.ofHours(8));
        long id = NOTIFICATION_ID;

        // ⚠️ **补一步防御性清理**（B 侧没有）：id 是固定值 ⇒ 上一次跑崩在本断言之前时，
        //    残行会留在共享库里 ⇒ 下一次 INSERT 直接撞主键（PK 冲突）⇒ 红的原因与本用例无关。
        //    ⇒ 先删一次，保证「本用例只依赖 DDL、不依赖上一次跑的残留」。
        jdbcTemplate.update("DELETE FROM message_notification WHERE id = ?", id);

        jdbcTemplate.update("""
                INSERT INTO message_notification
                    (id, recipient_id, sender_id, sender_role, kind, title, content,
                     is_read, created_at, updated_at)
                VALUES (?, 1, 1, 1, 1, 't', 'c', false, ?, ?)
                """, id, written, written);

        OffsetDateTime readBack = jdbcTemplate.queryForObject(
                "SELECT created_at FROM message_notification WHERE id = ?",
                OffsetDateTime.class, id);

        assertNotNull(readBack);
        assertEquals(written.toEpochSecond(), readBack.toEpochSecond(),
                "读回的 epoch 必须等于写入的瞬时；不等说明 offset 被丢掉后按 JVM 时区重解释了");
        // ⚠️ 用 isEqual() 而不是 equals()：OffsetDateTime.equals() 要求**瞬时与 offset 都相同**，
        //    而本用例刻意写 +08:00、连接会话时区是 UTC ⇒ 读回必然是 Z 表示（同一瞬时、offset 不同）。
        //    这正是要验的性质：**读回来的 offset 由会话决定，但瞬时必须不变**。
        assertTrue(written.isEqual(readBack),
                "读回值必须与写入值代表**同一瞬时**；offset 不同是正常的（会话时区不同）");

        jdbcTemplate.update("DELETE FROM message_notification WHERE id = ?", id);
    }
}
