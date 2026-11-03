package com.rag.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.task.domain.entity.TaskProgressEntity;
import com.rag.task.support.TaskRealInfraTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <p><b>三条 upsert 的 DB 级不变量（真 PostgreSQL）</b>。</p>
 *
 * <h2>🔴 为什么必须是真 PG（不能 mock Mapper、也不能 H2）</h2>
 * <p>本域三条 upsert 的语义<b>全部在 SQL 里</b>：
 * {@code ON CONFLICT (user_id, task_id) DO NOTHING} /
 * {@code GREATEST(...)} / {@code CASE WHEN ... THEN ... ELSE EXCLUDED.status END} /
 * {@code COALESCE(...)}。
 * <p>⇒ 用 mock 的 Mapper 断言，只能证明「我调了这个方法」，<b>证不了 SQL 的语义</b>；
 * ⇒ 用 H2 断言，{@code GREATEST} / {@code ON CONFLICT ... DO UPDATE} 的方言差异会让
 * 「跑绿」变成<b>假绿</b>（H2 上 {@code ON CONFLICT DO UPDATE} 的列引用规则与 PG 不同）。</p>
 *
 * <h2>⚠️ 三条不变量各自对应一条 Go 出处</h2>
 * <table border="1">
 *   <caption>不变量 ↔ Go</caption>
 *   <tr><th>不变量</th><th>Go 出处</th></tr>
 *   <tr><td>DO NOTHING：已存在的行<b>完全不动</b>（不是重置为 0）</td>
 *       <td>{@code like_sink.go:237-240} / {@code article_sink.go:324-327} 的
 *           {@code clause.OnConflict{DoNothing: true}}</td></tr>
 *   <tr><td>GREATEST：进度<b>只增不减</b></td>
 *       <td>{@code like_sink.go:311-323} 的
 *           {@code gorm.Expr("GREATEST(task_progress.progress, EXCLUDED.progress)")}</td></tr>
 *   <tr><td>CASE：<b>done 不被回退成 doing</b></td>
 *       <td>{@code like_sink.go:313-316} 的
 *           {@code CASE WHEN task_progress.status = 'done' THEN task_progress.status ELSE EXCLUDED.status END}</td></tr>
 *   <tr><td>COALESCE：<b>首次完成时间不可被覆盖</b></td>
 *       <td>{@code like_sink.go:379-381} 的
 *           {@code gorm.Expr("COALESCE(task_progress.done_at, now())")}</td></tr>
 *   <tr><td>markDone 的 progress 是<b>常量覆盖</b>（不是 GREATEST）</td>
 *       <td>{@code like_sink.go:378} 的 {@code progress = target}</td></tr>
 * </table>
 */
class TaskProgressMapperRealPgTest extends TaskRealInfraTestBase {

    @Autowired
    private TaskProgressMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 本测试类的 uid 号段（与其它测试类不重叠）。 */
    private static final long UID = 880_000_001L;
    private static final long TASK_ID = 1902L;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task_progress WHERE user_id = ?", UID);
    }

    // ==================================================================
    // ① batchInsertIfAbsent —— DO NOTHING
    // ==================================================================

    @Test
    @DisplayName("① 首次插入成功；再次插入同一 (user_id, task_id) ⇒ 原行完全不动")
    void insertIfAbsentDoesNotOverwrite() {
        int first = mapper.batchInsertIfAbsent(List.of(row("doing", 3L, 5L)));
        assertThat(first).isEqualTo(1);

        // 第二次：带不同的 progress / status ⇒ DO NOTHING ⇒ 原行保持不动
        int second = mapper.batchInsertIfAbsent(List.of(row("done", 99L, 7L)));
        assertThat(second).isEqualTo(0);

        TaskProgressEntity stored = mapper.selectByUserId(UID).get(0);
        assertThat(stored.getStatus()).isEqualTo("doing");
        assertThat(stored.getProgress()).isEqualTo(3L);
        assertThat(stored.getTarget()).isEqualTo(5L);
    }

    // ==================================================================
    // ② upsertDoingProgress —— GREATEST + CASE
    // ==================================================================

    @Test
    @DisplayName("② 进度只增不减（GREATEST）：存量 3 时写入 1 ⇒ 仍是 3")
    void progressNeverDecreases() {
        mapper.batchInsertIfAbsent(List.of(row("doing", 3L, 5L)));

        mapper.upsertDoingProgress(UID, TASK_ID, row("doing", 1L, 5L));

        assertThat(mapper.selectByUserId(UID).get(0).getProgress()).isEqualTo(3L);
    }

    @Test
    @DisplayName("②-b 进度确实前进：存量 3 时写入 4 ⇒ 变 4")
    void progressAdvances() {
        mapper.batchInsertIfAbsent(List.of(row("doing", 3L, 5L)));

        mapper.upsertDoingProgress(UID, TASK_ID, row("doing", 4L, 5L));

        assertThat(mapper.selectByUserId(UID).get(0).getProgress()).isEqualTo(4L);
    }

    @Test
    @DisplayName("②-c 🔴 done 不被回退成 doing（乱序 / 重投的迟到快照）")
    void doneStatusIsNeverReverted() {
        mapper.batchInsertIfAbsent(List.of(row("doing", 3L, 5L)));
        mapper.markDone(UID, TASK_ID, 5L);

        mapper.upsertDoingProgress(UID, TASK_ID, row("doing", 4L, 5L));

        TaskProgressEntity stored = mapper.selectByUserId(UID).get(0);
        assertThat(stored.getStatus()).isEqualTo("done");
    }

    @Test
    @DisplayName("②-d target 是常量覆盖（不是 GREATEST）：写 7 ⇒ 变成 7")
    void targetIsConstantOverwrite() {
        mapper.batchInsertIfAbsent(List.of(row("doing", 3L, 5L)));

        mapper.upsertDoingProgress(UID, TASK_ID, row("doing", 4L, 7L));

        assertThat(mapper.selectByUserId(UID).get(0).getTarget()).isEqualTo(7L);
    }

    // ==================================================================
    // ③ markDone —— 常量覆盖 + COALESCE
    // ==================================================================

    @Test
    @DisplayName("③ markDone：status=done、progress=target（常量覆盖，不是 GREATEST）")
    void markDoneOverwritesProgressWithTarget() {
        mapper.batchInsertIfAbsent(List.of(row("doing", 9L, 5L)));

        mapper.markDone(UID, TASK_ID, 5L);

        TaskProgressEntity stored = mapper.selectByUserId(UID).get(0);
        assertThat(stored.getStatus()).isEqualTo("done");
        // ⚠️ 存量是 9 > target 5，仍被压到 5（Go 是 `progress = target`）
        assertThat(stored.getProgress()).isEqualTo(5L);
        assertThat(stored.getTarget()).isEqualTo(5L);
    }

    @Test
    @DisplayName("③-b 🔴 首次完成时间不可被覆盖（COALESCE）：第二次 markDone 不刷新 done_at")
    void doneAtIsNotRefreshed() throws Exception {
        mapper.batchInsertIfAbsent(List.of(row("doing", 3L, 5L)));
        mapper.markDone(UID, TASK_ID, 5L);

        Instant firstDoneAt = jdbc.queryForObject(
                "SELECT done_at FROM task_progress WHERE user_id = ? AND task_id = ?",
                (rs, i) -> rs.getTimestamp("done_at").toInstant(), UID, TASK_ID);
        assertThat(firstDoneAt).isNotNull();

        Thread.sleep(Duration.ofSeconds(1).toMillis());
        mapper.markDone(UID, TASK_ID, 5L);

        Instant secondDoneAt = jdbc.queryForObject(
                "SELECT done_at FROM task_progress WHERE user_id = ? AND task_id = ?",
                (rs, i) -> rs.getTimestamp("done_at").toInstant(), UID, TASK_ID);
        assertThat(secondDoneAt).isEqualTo(firstDoneAt);
    }

    @Test
    @DisplayName("③-c markDone 在行不存在时直接插入一条 done 行")
    void markDoneInsertsWhenAbsent() {
        mapper.markDone(UID, TASK_ID, 5L);

        TaskProgressEntity stored = mapper.selectByUserId(UID).get(0);
        assertThat(stored.getStatus()).isEqualTo("done");
        assertThat(stored.getProgress()).isEqualTo(5L);
    }

    // ==================================================================
    // ④ selectByUserId —— 列集与过滤
    // ==================================================================

    @Test
    @DisplayName("④ selectByUserId 只返回该 uid 的行；别的 uid 看不到")
    void selectByUserIdIsScopedByUser() {
        mapper.batchInsertIfAbsent(List.of(row("doing", 1L, 5L)));
        jdbc.update("INSERT INTO task_progress (user_id, task_id, status, progress, target) "
                + "VALUES (?, ?, 'doing', 0, 5)", UID + 1, TASK_ID);

        List<TaskProgressEntity> rows = mapper.selectByUserId(UID);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(UID);
        assertThat(rows.get(0).getTaskId()).isEqualTo(TASK_ID);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private TaskProgressEntity row(String status, long progress, long target) {
        TaskProgressEntity e = new TaskProgressEntity();
        e.setUserId(UID);
        e.setTaskId(TASK_ID);
        e.setStatus(status);
        e.setProgress(progress);
        e.setTarget(target);
        return e;
    }
}
