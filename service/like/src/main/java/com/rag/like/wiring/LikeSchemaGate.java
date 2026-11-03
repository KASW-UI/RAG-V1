package com.rag.like.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p><b>表存在性启动闸</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/bootstrap/LikeSchemaGate.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：落点由 {@code bootstrap/} 移到 {@code wiring/}（D10）；
 *       B 侧报错文案里的「是否引了 {@code org.springframework.boot:spring-boot-flyway}（Boot 4 必需）」
 *       改成 D14 的 Boot 3.5 口径（{@code flyway-core} ＋ {@code flyway-database-postgresql}）。
 *       <b>检查口径（5 张表）逐条未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。缺表只<b>记 ERROR 日志</b>、<b>不阻止启动</b>；
 *       校验本身失败也只记日志。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 它是「闸」但<b>不是</b>拦路虎</h2>
 * <p>B 侧的措辞是「表与进程同生共死」，但<b>行为</b>是：缺表 ⇒ 记 ERROR 日志，
 * <b>照常启动</b>（运行时 SQL 才失败）⇒ 照抄，别改成「缺表就退出进程」
 * （那是行为变更 —— 会让「先起进程、后跑迁移」的运维顺序彻底不可用）。
 */
@Component
public class LikeSchemaGate {

    private static final Logger LOG = LoggerFactory.getLogger(LikeSchemaGate.class);

    /**
     * 本域的 5 张表 —— B 侧逐字照抄。
     *
     * <p>⚠️ <b>表名是 L1 契约</b>（Flyway 迁移脚本与 mapper 的 SQL 都依赖它）。
     */
    private static final List<String> REQUIRED_TABLES = List.of(
            "like_record",
            "like_consume_inbox",
            "like_outbox_event",
            "like_domain_fact_outbox",
            "like_fact_stream");

    private final JdbcTemplate jdbcTemplate;

    public LikeSchemaGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifySchema() {
        try {
            List<String> present = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);
            List<String> missing = REQUIRED_TABLES.stream().filter(t -> !present.contains(t)).toList();
            if (missing.isEmpty()) {
                LOG.info("LikeSchemaGate: all {} like tables present", REQUIRED_TABLES.size());
                return;
            }
            LOG.error("""
                    LikeSchemaGate: 以下 like 表不存在 ⇒ Flyway 迁移未生效，运行时 SQL 会失败。
                      缺失表: {}
                      检查: spring.flyway.enabled / locations=classpath:db/migration /
                            spring.flyway.table=flyway_schema_history_like（独立账本表）/
                            pom 是否引了 org.flywaydb:flyway-core + flyway-database-postgresql
                    """, missing);
        } catch (Exception e) {
            LOG.error("LikeSchemaGate: 表存在性校验失败（不阻止启动）: {}", e.getMessage(), e);
        }
    }
}
