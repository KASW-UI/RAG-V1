package com.rag.task.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>用户任务进度</b> —— 对应 Go 端 {@code internal/Model/taskModel.go:28-41} 的 {@code TaskProgress}。</p>
 *
 * <p><b>为什么本实体在本模块几乎不被使用</b>：
 * Go 的写路径<b>没有</b>用 GORM 的 {@code Model.TaskProgress}，
 * 而是在两个 sink 里各自定义了一个只含 4~5 个字段的局部结构体
 * （{@code sink/like_sink.go:36-42} 的 {@code UserTaskProgress}、
 * {@code sink/article_sink.go:33-45} 的 {@code ArticleLikeTaskProgress}），
 * 因为 upsert 的列集与 DO UPDATE 赋值必须逐条对齐 GORM 的
 * {@code clause.OnConflict} 语义，手写 SQL 更直接。
 * Java 侧同理：写入一律走 {@code TaskProgressMapper} 的显式 SQL，
 * 本实体只承载<b>读路径</b>（{@code GetTask} 的回源查询）
 * 与 {@code TaskSchemaGate} 的存在性说明。
 *
 * <p>⚠️ 列类型按 GORM AutoMigrate 的规则对齐，别按直觉改成 Integer / Timestamp：
 * int64 ⇒ BIGINT、string ⇒ TEXT、time.Time ⇒ TIMESTAMPTZ。见 {@code V1__create_task_progress.sql}。
 *
 * <p>⚠️ {@code userId} + {@code taskId} 是<b>复合主键</b>（Go 两个字段都带 {@code primary_key}），
 * 所以这里两个字段都标 {@link TableId}。
 * ⚠️ MyBatis-Plus <b>不支持复合主键</b>：第二个 {@code @TableId} 会覆盖前一个并打一条
 * {@code "… is the table primary key by @TableId annotation …"} 的 WARN（实测 MP 3.5.5）。
 * ⇒ 本仓<b>不使用</b> {@code selectById}/{@code updateById} 等依赖单主键的 API，
 * 全部走 {@code TaskProgressMapper} 的<b>显式 SQL</b> ⇒ 该 WARN 不影响任何行为。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.domain} → {@code com.rag.task.domain.entity}（D7）；</li>
 *         <li><b>Lombok 化</b>：getter/setter/无参构造器样板由 Lombok 生成
 *             （<b>展开块见下</b> ⇒ D18）⇒ 生成物与 B 侧手写版<b>逐字等价</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段名、列名、类型、可见性、构造器逐条一致。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/domain/TaskProgressEntity.java:28-29（@TableName + 类声明）
 * B 出处：…/domain/TaskProgressEntity.java:32-33（userId · @TableId）
 * B 出处：…/domain/TaskProgressEntity.java:36-37（taskId · @TableId）
 * B 出处：…/domain/TaskProgressEntity.java:40-47（status/progress/target）
 * B 出处：…/domain/TaskProgressEntity.java:49-55（doneAt/createdAt/updatedAt）
 *       </pre></li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * @TableName("task_progress")
 * public class TaskProgressEntity {
 *
 *     @TableId(value = "user_id")
 *     private Long userId;
 *
 *     @TableId(value = "task_id")
 *     private Long taskId;
 *
 *     private String status;
 *
 *     private Long progress;
 *
 *     private Long target;
 *
 *     private OffsetDateTime doneAt;
 *
 *     private OffsetDateTime createdAt;
 *
 *     private OffsetDateTime updatedAt;
 *
 *     public Long getUserId() { return userId; }
 *     public void setUserId(Long userId) { this.userId = userId; }
 *     public Long getTaskId() { return taskId; }
 *     public void setTaskId(Long taskId) { this.taskId = taskId; }
 *     public String getStatus() { return status; }
 *     public void setStatus(String status) { this.status = status; }
 *     public Long getProgress() { return progress; }
 *     public void setProgress(Long progress) { this.progress = progress; }
 *     public Long getTarget() { return target; }
 *     public void setTarget(Long target) { this.target = target; }
 *     public OffsetDateTime getDoneAt() { return doneAt; }
 *     public void setDoneAt(OffsetDateTime doneAt) { this.doneAt = doneAt; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *
 *     public TaskProgressEntity() {
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("task_progress")
public class TaskProgressEntity {

    /** Go {@code UserID int64} ⇒ BIGINT。 */
    @TableId(value = "user_id")
    private Long userId;

    /** Go {@code TaskID int64} ⇒ BIGINT。 */
    @TableId(value = "task_id")
    private Long taskId;

    /** Go {@code Status string} ⇒ TEXT；取值 {@code doing} / {@code done}。 */
    private String status;

    /** Go {@code Progress int64} ⇒ BIGINT。 */
    private Long progress;

    /** Go {@code Target int64} ⇒ BIGINT。注意 <b>GetTask 不用这一列</b>（见 TaskDomainService）。 */
    private Long target;

    /** Go {@code DoneAt *time.Time} ⇒ TIMESTAMPTZ，可空。 */
    private OffsetDateTime doneAt;

    /** Go {@code CreatedAt time.Time} ⇒ TIMESTAMPTZ。 */
    private OffsetDateTime createdAt;

    /** Go {@code UpdatedAt time.Time} ⇒ TIMESTAMPTZ。 */
    private OffsetDateTime updatedAt;
}
