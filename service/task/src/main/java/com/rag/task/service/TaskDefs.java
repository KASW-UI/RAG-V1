package com.rag.task.service;

import java.util.List;

/**
 * <p><b>任务定义</b> —— 对应 Go 端 {@code internal/Model/taskModel.go:15-25} 的
 * {@code TaskDef} 结构体与 {@code var AllTaskDefs} 切片。</p>
 *
 * <h2>🔴 关键：Go 里有<b>两份彼此独立的真相</b>，不要合并</h2>
 * <table border="1">
 *   <caption>三处硬编码常量</caption>
 *   <tr><th>常量</th><th>Go 出处</th><th>谁在读</th></tr>
 *   <tr><td>{@code AllTaskDefs}</td><td>{@code internal/Model/taskModel.go:22-25}</td>
 *       <td><b>只有 {@code GetTask}</b>（{@code get_task_logic.go:84}）</td></tr>
 *   <tr><td>{@code taskID=1902, target=5}</td><td>{@code sink/like_sink.go:31-34}</td>
 *       <td>点赞 sink 的全部写入</td></tr>
 *   <tr><td>{@code articleTaskID=190, articleTarget=3}</td>
 *       <td>{@code sink/article_sink.go:20-21}</td>
 *       <td>文章 sink 的全部写入</td></tr>
 * </table>
 *
 * <p>Go <b>从未</b>校验这三处一致，也没有从数据库读任务定义
 * （{@code TaskDef} 的表 {@code task_def} 在 {@code internal/Model/taskModel.go:6-14} 里整段被注释，
 * 且不在 {@code service_context.go:47} 的 {@code AutoMigrate} 列表里 ⇒ 该表在真实库里不存在）。
 *
 * <p>⇒ Java 侧<b>保留同样的"两套真相"</b>：
 * 本类对应 {@code AllTaskDefs}（读路径），两个 sink 的常量由
 * {@code task.definitions.like} / {@code task.definitions.article} 配置承载（写路径）。
 * 把它们收敛成一份看起来是"正确"的单一来源，会新增一条 Go 没有的一致性约束 ——
 * 而这类约束在灰度期表现为「Java 侧突然开始按 target=3 判定点赞任务是否完成」。
 *
 * <h2>⚠️ 文案与 id 是 L1 外部契约</h2>
 * <p>{@code name} / {@code desc} 会<b>原样上线</b>给客户端（{@code get_task_logic.go:94-100}
 * 直接把 d.Name / d.Desc 塞进 proto 的 {@code name} / {@code desc} 字段），
 * 而 proto 侧没有任何默认值。改一个字就是客户端可见的文案变更。
 *
 * <h2>⚠️ 顺序也是契约</h2>
 * <p>Go 按切片顺序遍历并 {@code append}，响应里 {@code task} 列表的顺序
 * 就是 {@code AllTaskDefs} 的顺序（190 在前、1902 在后）。
 * Java 用 {@link List#of} 保持同样的顺序。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.service} → {@code com.rag.task.service}（D7）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 两条定义的
 *       {@code taskId} / {@code name} / {@code desc} / {@code requiredProgress} 与
 *       <b>顺序</b>逐字照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskDefs.java:42-43（类声明）
 * B 出处：…/service/TaskDefs.java:61-62（TaskDef record）
 * B 出处：…/service/TaskDefs.java:82-84（ALL_TASK_DEFS 两条字面量）
 *       </pre></li>
 * </ol>
 */
public final class TaskDefs {

    private TaskDefs() {
        // 纯静态工具，禁止实例化
    }

    /**
     * 一条任务定义。
     *
     * <p>对应 Go {@code Model.TaskDef}（{@code internal/Model/taskModel.go:15-20}）——
     * 注意这是<b>注释掉 gorm tag 之后</b>的版本：Go 刻意去掉了 {@code TableName}
     * 与全部 {@code gorm:"column:..."}，因此它<b>不是</b>一个可持久化的模型，
     * 只是一份内存里的常量表。
     *
     * @param taskId           proto 字段 {@code task_id}（字段号 3）
     * @param name             proto 字段 {@code name}（字段号 1）
     * @param desc             proto 字段 {@code desc}（字段号 2）
     * @param requiredProgress proto 字段 {@code required_progress}（字段号 5）
     */
    public record TaskDef(long taskId, String name, String desc, long requiredProgress) {
    }

    /**
     * Go {@code AllTaskDefs} 的逐字对照。
     *
     * <pre>
     * var AllTaskDefs = []TaskDef{
     *     {TaskID: 190,  Name: "文章获赞任务", Desc: "有一篇文章点赞大于3", RequiredProgress: 3},
     *     {TaskID: 1902, Name: "点赞任务",     Desc: "给文章点赞5次",    RequiredProgress: 5},
     * }
     * </pre>
     *
     * <p>⚠️ {@code RequiredProgress} 取自<b>这里</b>，<b>不是</b> {@code task_progress.target} 列。
     * Go 源码有一句注释把这件事说得很明确（{@code get_task_logic.go:86-87}）：
     * <blockquote>
     * required_progress 来自任务定义（更合理，不要用 progress 表的 target 覆盖 defs）
     * </blockquote>
     * 这意味着：<b>DB 里 target 被写成别的值时，客户端看到的 required_progress 不变。</b>
     * 改成读 target 就是一个可观察的语义变更。
     */
    public static final List<TaskDef> ALL_TASK_DEFS = List.of(
            new TaskDef(190L, "文章获赞任务", "有一篇文章点赞大于3", 3L),
            new TaskDef(1902L, "点赞任务", "给文章点赞5次", 5L));
}
