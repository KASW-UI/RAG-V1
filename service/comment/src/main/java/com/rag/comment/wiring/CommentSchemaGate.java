package com.rag.comment.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p><b>启动后校验 7 张 comment 表确实存在</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/bootstrap/CommentSchemaGate.java:1-84}
 * （B 侧的语义来源注释写的是 Go 侧 {@code model/db.go:10-19} 的
 * {@code db.AutoMigrate(&Subject{}, &CommentContent{}, &CommentIndex{}, &CommentLike{},
 * &CommentDomainFactOutbox{}, &CommentFactStream{})} 及其失败即 {@code log.Fatalln} 的行为）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.bootstrap} 移到 {@code com.rag.comment.wiring}（D7 · D10）；</li>
 *       <li>告警文案里的 Flyway 依赖坐标由 B 侧的
 *           {@code org.springframework.boot:spring-boot-flyway} 改为
 *           {@code org.flywaydb:flyway-core} ——
 *           <b>原因</b>：{@code spring-boot-flyway} 是 <b>Boot 4 专有</b>的 artifact，
 *           在 Boot 3.5 <b>不存在</b>（D14）；C 侧本模块的 pom 引的是
 *           {@code org.flywaydb:flyway-core} + {@code org.flywaydb:flyway-database-postgresql}
 *           ⇒ 照抄 B 侧坐标会指向一个不存在的依赖，运维照着查会查不到 ⇒ 属<b>表达层</b>更正，
 *           判定逻辑（查哪些表、缺表怎么办）一字未动。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。7 张表的清单与顺序、查询语句（{@code information_schema.tables}）、
 *       「<b>只告警、不阻止启动</b>」的处置逐字未改。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../bootstrap/CommentSchemaGate.java:1-84}。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么要有这道闸</h2>
 * <p>B 侧用 {@code AutoMigrate}，建表失败直接退出 —— 表与进程同生共死。
 * Java 侧改用 Flyway，于是引入了 B 侧没有的新失败模式：
 * <b>Flyway 引擎在、但自动配置没加载</b> ⇒ 启动日志里<b>连一行 Flyway 都没有</b>
 * ⇒ 7 张表永不创建 ⇒ 进程看起来完全正常 ⇒ <b>第一次真正执行 SQL 时才炸</b>：
 * <pre>
 *   org.postgresql.util.PSQLException: ERROR: relation "subject" does not exist
 * </pre>
 *
 * <p><b>不抛异常、不阻止启动</b>：与 B 侧差异说明如下 ——
 * B 侧迁移失败会退出进程，而 Java 单 JVM 里还跑着 Kafka 消费与 gRPC。
 * 这里只<b>大声告警</b>让运维立刻看到；真的缺表时首条 SQL 自然会失败。
 * （若要改成 fail-fast，只需把 {@code LOG.error} 换成抛异常。）
 *
 * <p>⚠️ 表清单里<b>包含 report_record</b>，而它<b>不在</b> B 侧的 AutoMigrate 列表中
 * （B 侧这张表从不被建出，详见 {@code V5__create_comment_report_record.sql} 的偏离说明）。
 * 这里检查它是为了把「RAG 侧补建」这件事在启动日志里显式化。
 */
@Component
public class CommentSchemaGate {

    private static final Logger LOG = LoggerFactory.getLogger(CommentSchemaGate.class);

    /**
     * 本域 7 张表 —— 与 B 侧 {@code CommentSchemaGate.java:45-53} 逐条一致，
     * 与本仓 {@code resources/db/migration/} 的 {@code V1..V8} 迁移脚本一一对应
     * （V8 是对齐 Go 的 ALTER，不新建表 ⇒ 7 张表）。
     */
    private static final List<String> REQUIRED_TABLES = List.of(
            "subject",
            "comment_index",
            "comment_content",
            "comment_like",
            "report_record",
            "comment_domain_fact_outbox",
            "comment_fact_stream");

    private final JdbcTemplate jdbcTemplate;

    public CommentSchemaGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 启动完成后校验表存在性 —— 对应 B 侧 {@code CommentSchemaGate.java:61-83}。
     *
     * <p>⚠️ 监听 {@link ApplicationReadyEvent}（而不是 {@code ContextRefreshedEvent}）⇒
     * 确保 Flyway 已经跑完、连接池已经就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifySchema() {
        try {
            List<String> present = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);

            List<String> missing = REQUIRED_TABLES.stream().filter(t -> !present.contains(t)).toList();
            if (missing.isEmpty()) {
                LOG.info("CommentSchemaGate: all {} comment tables present", REQUIRED_TABLES.size());
                return;
            }
            LOG.error("""
                    CommentSchemaGate: 以下 comment 表不存在 ⇒ Flyway 迁移未生效，运行时 SQL 会失败。
                      缺失表: {}
                      检查: spring.flyway.enabled / locations=classpath:db/migration /
                            spring.flyway.table=flyway_schema_history_comment /
                            pom 是否引了 org.flywaydb:flyway-core（Boot 3.5 的坐标）
                    """, missing);
        } catch (Exception e) {
            LOG.error("CommentSchemaGate: 表存在性校验失败（不阻止启动）: {}", e.getMessage(), e);
        }
    }
}
