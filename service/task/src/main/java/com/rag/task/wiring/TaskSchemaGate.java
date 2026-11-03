package com.rag.task.wiring;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * <p><b>启动后校验 task 域两张表确实存在</b> —— 对应 Go 侧 {@code service_context.go:47} 的
 * {@code gdb.AutoMigrate(&Model.TaskProgress{}, &Model.UserLikeCount{})}。</p>
 *
 * <h2>⚠️ 为什么要有这道闸</h2>
 * <p>Go 的 AutoMigrate 失败会 {@code panic} —— 表与进程同生共死。
 * Java 侧改用 Flyway，于是引入了 Go 没有的新失败模式：
 * <b>Flyway 引擎在、但自动配置没加载</b> ⇒ <b>启动日志里连一行 Flyway 都没有</b>
 * （不是报错，是根本没被加载）⇒ 两张表永不创建
 * ⇒ 进程看起来完全正常 ⇒ <b>第一次真正执行 SQL 时才炸</b>：
 * <pre>
 *   org.postgresql.util.PSQLException: ERROR: relation "task_progress" does not exist
 * </pre>
 *
 * <p>本类把「迁移到底跑没跑」变成<b>启动时可见</b>的一条 ERROR 日志。</p>
 *
 * <p><b>不抛异常、不阻止启动</b>：Go 迁移失败会退出进程，
 * 而 Java 单 JVM 里还跑着 5 个 Kafka listener 与 gRPC server。
 * 这里只<b>大声告警</b>；真的缺表时首条 SQL 自然会失败。
 * （若要改成 fail-fast，把 {@code log.error} 换成抛异常即可。）</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>落点</b>：{@code bootstrap/} → {@code wiring/}（D10）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 两张表的表名清单、查
 *       {@code information_schema.tables} 的 SQL、「缺表打 ERROR、不缺表打 INFO、
 *       校验本身失败也只打 ERROR」三条分支逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/bootstrap/TaskSchemaGate.java:35-49（类声明 + 必需表清单）
 * B 出处：…/bootstrap/TaskSchemaGate.java:51-73（verifySchema：三条分支）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskSchemaGate {

    private static final Logger log = LoggerFactory.getLogger(TaskSchemaGate.class);

    /** 本域 2 张表，与 V1 / V2 迁移脚本一一对应。 */
    private static final List<String> REQUIRED_TABLES = List.of(
            "task_progress",
            "user_like_count");

    private final JdbcTemplate jdbcTemplate;

    public TaskSchemaGate(JdbcTemplate jdbcTemplate) {
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
                log.info("TaskSchemaGate: all {} task tables present", REQUIRED_TABLES.size());
                return;
            }
            log.error("""
                    TaskSchemaGate: 以下 task 表不存在 ⇒ Flyway 迁移未生效，运行时 SQL 会失败。
                      缺失表: {}
                      检查: spring.flyway.enabled / locations=classpath:db/migration /
                            spring.flyway.table=flyway_schema_history_task /
                            pom 是否引了 org.springframework.boot:spring-boot-flyway（Boot 3.5 必需）
                    """, missing);
        } catch (Exception e) {
            log.error("TaskSchemaGate: 表存在性校验失败（不阻止启动）: {}", e.getMessage(), e);
        }
    }
}
