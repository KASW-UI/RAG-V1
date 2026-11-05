package com.rag.favorite.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>启动后校验「Go 侧有、RAG 侧容易静默丢失」的 schema 资产</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteSchemaGate.java}
 * （逐条照抄 · L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <p>对应 Go {@code service/favorite/rpc/internal/model/db.go:33} 的
 * {@code db.AutoMigrate(...)} 与三个 {@code log.Fatalln} / {@code log.Fatalf}。
 *
 * <h2>⚠️ 为什么这道闸对本域<b>尤其</b>重要</h2>
 *
 * <p>Go 侧 DDL 有<b>两处事实源</b>：{@code AutoMigrate}（3 个实体）与 4 个手写 SQL。
 * 后者独占的资产 —— <b>AutoMigrate 绝不创建</b> —— 是：
 * <ol>
 *   <li>{@code CHECK (aggregate_version IN (1,2))}（001:6）</li>
 *   <li>部分索引 {@code favorite_fact_outbox_pending}（001:14-15）</li>
 *   <li>🔴 不可变触发器 {@code favorite_fact_identity_immutable}（002:22-25）</li>
 *   <li>🔴 整张表 {@code favorite_legacy_fact_marker} + 5 个 CHECK（004）</li>
 *   <li>🔴 不可变触发器 {@code favorite_legacy_marker_immutable}（004:24-27）</li>
 * </ol>
 *
 * <p><b>缺它们的后果分两类，第二类尤其阴</b>：
 * <ul>
 *   <li><b>静默降级</b>：缺触发器 ⇒ 「冻结信封不可变」从数据库硬约束退化成约定，
 *       dispatcher 照常发事件，没有任何报错。</li>
 *   <li><b>错误码静默改变</b>：缺 {@code favorite_legacy_fact_marker} 表 ⇒
 *       {@code favoriteAssertionSchema} 走 legacy 分支时拿到
 *       {@code relation ... does not exist}，那<b>不是</b> NotFound
 *       ⇒ 删除收藏返回 {@code Internal/5008} 而不是设计的
 *       {@code FailedPrecondition/5304}。
 *       而只有「没有 v1 断言」的行会走到那里 ⇒ 新建收藏完全正常 ⇒
 *       <b>只有历史数据才会暴露</b>。</li>
 * </ul>
 *
 * <p>同款论证见 {@code like-rpc/bootstrap/LikeSchemaGate.java}。
 *
 * <p><b>不抛异常、不阻止启动</b>：Go 侧迁移失败会退出进程，而本单 JVM 里还跑着
 * gRPC 服务。这里只<b>大声告警</b>；真的缺表时首条 SQL 自然会失败。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.favorite.favorite.rpc.bootstrap} 压平为
 *           {@code com.rag.favorite.wiring}（D7 包压平 + D10 {@code bootstrap} → {@code wiring}）；</li>
 *       <li><b>报错文案里的 Flyway 依赖名改成 D14 的 Boot 3.5 口径</b> ——
 *           B 侧写的是「是否引了 {@code org.springframework.boot:spring-boot-flyway}（Boot 4 必需）」，
 *           而 Boot 3.5 里<b>没有这个 artifact</b>（D14：{@code spring-boot-flyway} 只在 Boot 4 存在）
 *           ⇒ 照抄会让人去引一个不存在的依赖。
 *           改成 like 域同款口径：{@code org.flywaydb:flyway-core} + {@code flyway-database-postgresql}；</li>
 *       <li>补了 {@code spring.flyway.table=flyway_schema_history_favorite} 这一条提示
 *           （C 侧 {@code application.yml:46} 确实配了这个独立账本表名）——
 *           与 like 域 {@code LikeSchemaGate} 的同款提示一致。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。<b>检查口径逐条未改</b>：4 张表 + 2 个不可变触发器
 *       + 5 个 CHECK/UNIQUE 约束 + 1 个部分索引；「缺表 ⇒ 直接收尾（不再查 pg_trigger）」
 *       的短路；「只记 ERROR / WARN、<b>不抛异常、不阻止启动</b>」逐条未改。</li>
 *   <li><b>B 出处</b>：
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteSchemaGate.java:48-164}
 *       （类声明 :48-49 · 常量 :54-63 · 字段与构造器 :65-69 ·
 *       {@code verifySchema} :71-164 · 表检查 :74-96 · 触发器检查 :99-118 ·
 *       CHECK 检查 :121-147 · 索引检查 :150-156 · 汇总 :158-160 · 兜底 :161-163）。</li>
 * </ol>
 */
@Component
public class FavoriteSchemaGate {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteSchemaGate.class);

    /** 本域 4 张表，与 V1..V4 一一对应。 */
    private static final List<String> REQUIRED_TABLES = List.of(
            "favorite_folder",
            "favorite_item",
            "favorite_fact_outbox",
            "favorite_legacy_fact_marker");

    /** 本域 2 个不可变触发器（Go 002 / 004）。 */
    private static final List<String> REQUIRED_TRIGGERS = List.of(
            "favorite_fact_identity_immutable",
            "favorite_legacy_marker_immutable");

    private final JdbcTemplate jdbcTemplate;

    public FavoriteSchemaGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifySchema() {
        try {
            List<String> present = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);

            List<String> missingTables = new ArrayList<>();
            for (String table : REQUIRED_TABLES) {
                if (!present.contains(table)) {
                    missingTables.add(table);
                }
            }
            if (!missingTables.isEmpty()) {
                LOG.error("""
                        FavoriteSchemaGate: 以下 favorite 表不存在 ⇒ Flyway 迁移未生效，运行时 SQL 会失败。
                          缺失表: {}
                          检查: spring.flyway.enabled / locations=classpath:db/migration /
                                spring.flyway.table=flyway_schema_history_favorite（独立账本表）/
                                pom 是否引了 org.flywaydb:flyway-core + flyway-database-postgresql
                                ⚠️ 尤其 favorite_legacy_fact_marker —— 缺它会让「历史未和解」的删除
                                   从 FailedPrecondition/5304 静默退化成 Internal/5008
                        """, missingTables);
                // 表都不在时继续查 pg_trigger 只会刷更多错误，直接收尾
                return;
            }

            // 🔴 触发器：AutoMigrate 绝不创建，缺了就是「冻结信封可被改写」
            List<String> triggers = jdbcTemplate.queryForList(
                    "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal", String.class);
            List<String> missingTriggers = new ArrayList<>();
            for (String trigger : REQUIRED_TRIGGERS) {
                if (!triggers.contains(trigger)) {
                    missingTriggers.add(trigger);
                }
            }
            if (!missingTriggers.isEmpty()) {
                LOG.error("""
                        FavoriteSchemaGate: 以下不可变触发器不存在 ⇒ 「冻结信封不可变」已退化为约定。
                          缺失触发器: {}
                          后果: favorite_fact_outbox 的 event_id/favorite_id/aggregate_version/payload
                                可被 UPDATE 改写，而 authoritativeFavoriteRow 依赖
                                JCS(payload) == technical_input_hash 来证明「投出去的与冻结的是同一份」。
                          修复: 执行 V5__add_favorite_fact_delivery.sql 与
                                V4__create_favorite_legacy_fact_marker.sql
                        """, missingTriggers);
            }

            // 🔴 CHECK 约束：AutoMigrate 同样不创建
            List<String> constraints = jdbcTemplate.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conrelid IN "
                            + "('favorite_item'::regclass, 'favorite_fact_outbox'::regclass, "
                            + "'favorite_legacy_fact_marker'::regclass)",
                    String.class);
            List<String> requiredChecks = List.of(
                    "favorite_fact_outbox_aggregate_version_range",
                    "uk_favorite_fact_version",
                    "favorite_legacy_marker_revision_scope",
                    "favorite_legacy_marker_snapshot_hash_format",
                    "favorite_legacy_marker_approval_ref_nonempty");
            List<String> missingChecks = new ArrayList<>();
            for (String check : requiredChecks) {
                if (!constraints.contains(check)) {
                    missingChecks.add(check);
                }
            }
            if (!missingChecks.isEmpty()) {
                LOG.error("""
                        FavoriteSchemaGate: 以下 CHECK / UNIQUE 约束不存在 ⇒ L2/L3 不变量退化为约定。
                          缺失约束: {}
                          最严重的一条是 aggregate_version IN (1,2)：
                            缺它 ⇒ 可以写入 v3 这种无法解释的事实，
                            而 DispatchOnce 的前驱屏障只认 v1/v2
                        """, missingChecks);
            }

            // 部分索引：服务 DispatchOnce 的取件查询，缺了会退化成全表扫（性能，不阻塞正确性）
            List<String> indexes = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE schemaname='public' "
                            + "AND tablename='favorite_fact_outbox'", String.class);
            if (!indexes.contains("favorite_fact_outbox_pending")) {
                LOG.warn("FavoriteSchemaGate: 部分索引 favorite_fact_outbox_pending 缺失 ⇒ "
                        + "DispatchOnce 的取件查询会退化成全表扫（不影响正确性，只影响吞吐）");
            }

            if (missingTriggers.isEmpty() && missingChecks.isEmpty()) {
                LOG.info("FavoriteSchemaGate: favorite 的 4 张表 + 2 个不可变触发器 + CHECK 约束全部就位");
            }
        } catch (Exception e) {
            LOG.error("FavoriteSchemaGate: schema 校验失败（不阻止启动）: {}", e.getMessage(), e);
        }
    }
}
