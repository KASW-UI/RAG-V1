package com.rag.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.task.domain.entity.TaskProgressEntity;
import com.rag.task.domain.entity.UserLikeCountEntity;
import com.rag.task.mapper.TaskProgressMapper;
import com.rag.task.mapper.UserLikeCountMapper;
import com.rag.task.support.TaskRealInfraTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <p><b>task 域上下文装配自检</b> —— 证明 D1（api+rpc 合并）后的<b>唯一入口</b>
 * 真能把全链条装配起来。</p>
 *
 * <h2>⚠️ 为什么必须「真启动」而不是「读源码断言」</h2>
 * <p>AGENTS.md §3.3.3：<b>不得凭注解名称断定机制已启用</b>；
 * §3.3.1：判断某个能力是否生效<b>必须有运行证据</b>。
 * <p>本类断言的每一条都是<b>运行期事实</b>（bean 真在容器里 / 迁移账本里真有那两个版本 /
 * 上下文真起来了）⇒ 而非静态相似度。</p>
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <p>B 侧是<b>两个进程</b>（{@code task-api} + {@code task-rpc}）⇒ <b>没有</b>
 * 「一个上下文装起全链条」这件事 ⇒ B 侧无法有本类这种形态的测试。
 * <p>C 侧按 D1 合并为<b>单一入口</b> ⇒ <b>才有</b>「起一次 ⇒ 全链条装配到位」这条判据。
 * ⚠️ 这是<b>拓扑产物</b>（D1/D7），<b>不是</b> B 侧行为的等价物 ⇒ 本类<b>不产出</b>任何
 * 「与 B 侧行为一致」的结论，它只证明 <b>C 侧自己的装配是完整的</b>。</p>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>（就「本域该有的东西都在」这个命题而言）——
 * 断言涉及的每一个表名、每一个 bean 职责，都能在 B 侧找到对应物（B 出处见下表）；
 * 差别只在<b>它们被装进同一个上下文</b>（B 侧分两个进程）。
 * ⇒ ⚠️ <b>效力边界</b>：本类<b>不</b>证明「HTTP / gRPC 的对外行为一致」⇒ 那件事由
 * {@code http/} 里的真 Tomcat 测试承担。</p>
 *
 * <h2>③ B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>表名 {@code task_progress}</td>
 *       <td>{@code service/task/task-rpc/src/main/resources/db/migration/V1__create_task_progress.sql:23}
 *           （{@code CREATE TABLE IF NOT EXISTS task_progress (}）</td></tr>
 *   <tr><td>表名 {@code user_like_count}</td>
 *       <td>{@code …/db/migration/V2__create_user_like_count.sql:17}</td></tr>
 *   <tr><td>迁移版本号是 V1 / V2（<b>不得重编号</b>）</td>
 *       <td>同上两个文件名的版本号 ⇒ C 侧 {@code application.yml} 逐字保留</td></tr>
 *   <tr><td>{@code @MapperScan} 覆盖两个 Mapper</td>
 *       <td>{@code service/task/task-rpc/.../TaskRpcApplication.java:81}
 *           （{@code @MapperScan("com.rag.task.task.rpc.mapper")}）</td></tr>
 * </table>
 */
class TaskServiceApplicationTests extends TaskRealInfraTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TaskProgressMapper taskProgressMapper;

    @Autowired
    private UserLikeCountMapper userLikeCountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("🔴 上下文能起来（合并单进程 · 全量 main 代码装配到位）—— D1 的守门")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(TaskServiceApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("🔴 @MapperScan 真生效：两个 Mapper bean 都在容器里")
    void mapperBeansAreRegistered() {
        assertThat(taskProgressMapper).isNotNull();
        assertThat(userLikeCountMapper).isNotNull();
        assertThat(context.getBeanNamesForType(TaskProgressMapper.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(UserLikeCountMapper.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 @EnableConfigurationProperties 真生效：三个 Properties 各一个 bean")
    void configurationPropertiesBeansAreRegistered() {
        // ⚠️ D7 包压平后 TaskKafkaTopics 落在 messaging/ ⇒ 若改成扫 config 包就会漏掉它
        //    ⇒ 上下文直接起不来（like 域踩过这个坑）⇒ 这里断言「三个都在」。
        assertThat(context.getBeanNamesForType(com.rag.task.messaging.TaskKafkaTopics.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(com.rag.task.config.TaskSinkProperties.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(com.rag.task.config.TaskRewardProperties.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 迁移按版本真跑过：账本里有 V1/V2 且全部 success（版本号没被重编号）")
    void flywayLedgerRecordsTheBVersions() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT version, success FROM flyway_schema_history_task
                ORDER BY installed_rank
                """);

        assertThat(rows)
                .as("账本表名必须是 task 域的**独立**账本（application.yml 的 task.flyway.table），"
                        + "不能用默认的 flyway_schema_history（schema 级账本会与其它域撞号）")
                .isNotEmpty();

        List<String> versions = rows.stream()
                .map(r -> String.valueOf(r.get("version")))
                .toList();

        assertThat(versions)
                .as("迁移版本号必须保持 B 侧的 V1 / V2")
                .contains("1", "2");

        assertThat(rows)
                .as("账本里每一条都必须 success=true")
                .allSatisfy(r -> assertThat(r.get("success")).isEqualTo(Boolean.TRUE));
    }

    @Test
    @DisplayName("🔴 实体映射的表名是 B 侧那两张（L1 可见面）")
    void entityTableNamesMatchB() {
        assertThat(tableNameOf(TaskProgressEntity.class)).isEqualTo("task_progress");
        assertThat(tableNameOf(UserLikeCountEntity.class)).isEqualTo("user_like_count");
    }

    @Test
    @DisplayName("🔴 两张表真的建出来了（Flyway 生效 ⇒ TaskSchemaGate 的前提成立）")
    void bothTablesExist() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN ('task_progress','user_like_count')",
                String.class);

        assertThat(present).containsExactlyInAnyOrder("task_progress", "user_like_count");
    }

    /** 读实体上的 {@code @TableName} 值（表名是 L1 契约 ⇒ 逐字对比）。 */
    private static String tableNameOf(Class<?> entityType) {
        TableName tableName = entityType.getAnnotation(TableName.class);
        assertThat(tableName)
                .as("%s 必须有 @TableName", entityType.getSimpleName())
                .isNotNull();
        return tableName.value();
    }
}
