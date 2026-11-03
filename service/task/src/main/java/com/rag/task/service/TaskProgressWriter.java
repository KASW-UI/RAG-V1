package com.rag.task.service;

import com.rag.task.domain.entity.TaskProgressEntity;
import com.rag.task.mapper.TaskProgressMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p><b>task_progress 的写入器</b> —— 把 Go 两个 sink 里<b>逐字相同</b>的三段 upsert 收敛成唯一实现。</p>
 *
 * <h2>为什么可以合并（L4，实现机制）</h2>
 * <p>Go 侧的对应代码分别在 {@code internal/sink/like_sink.go} 与
 * {@code internal/sink/article_sink.go}，逐条比对后<b>除 target 常量与结构体名外完全一致</b>：
 * <table border="1">
 *   <caption>两处相同 / 不同</caption>
 *   <tr><th>方法</th><th>Go 行号</th><th>差异</th></tr>
 *   <tr><td>lazyInit 的 DB 插入</td>
 *       <td>like_sink.go:237-240 / article_sink.go:324-327</td>
 *       <td>仅结构体名不同（UserTaskProgress / ArticleLikeTaskProgress）</td></tr>
 *   <tr><td>updateDoing 的 CASE+GREATEST</td>
 *       <td>like_sink.go:311-323 / article_sink.go:401-416</td>
 *       <td>仅 target 常量不同（5 / 3）</td></tr>
 *   <tr><td>markDone 的 COALESCE</td>
 *       <td>like_sink.go:375-384 / article_sink.go:477-490</td>
 *       <td>仅 target 常量不同</td></tr>
 * </table>
 * ⇒ 合并是<b>去重</b>而非改写：SQL 文本与冲突判定逐字保持，
 * 差异只以参数 {@code target} 传入。这样也避免了"改一边忘另一边"的漂移入口。
 *
 * <h2>⚠️ 但 Redis 侧<b>没有</b>合并</h2>
 * <p>两条路的 Redis key 拼法、TTL、字段集合都不同（见 {@link TaskRedisKeys}），
 * 所以那部分留在各自的 sink 里。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.service} → {@code com.rag.task.service}（D7）；</li>
 *         <li>实体包名随包压平而变（{@code domain} → {@code domain.entity}）⇒ import 随之调整；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 三个方法的参数、夹取规则、
 *       「返回值被 Go 忽略 ⇒ 异常抛给调用方逐条捕获」的边界，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskProgressWriter.java:35-36（类声明）
 * B 出处：…/service/TaskProgressWriter.java:53-58（lazyInit）
 * B 出处：…/service/TaskProgressWriter.java:74-85（updateDoing + 夹取）
 * B 出处：…/service/TaskProgressWriter.java:96-98（markDone）
 * B 出处：…/service/TaskProgressWriter.java:101-109（clamp）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskProgressWriter {

    private final TaskProgressMapper mapper;

    public TaskProgressWriter(TaskProgressMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 首次见到该 (user, task) 组合时插入一条初始行（doing / 0 / target）——
     * 已存在则<b>什么都不做</b>。
     *
     * <p>对应 Go 的 {@code batchInsertIfAbsent} 调用点
     * （like_sink.go:237-240 / article_sink.go:324-327）。
     *
     * @param records 非空记录列表；空列表直接返回（Go 侧空 slice 会被 GORM 跳过）
     */
    public void lazyInit(List<TaskProgressEntity> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        mapper.batchInsertIfAbsent(records);
    }

    /**
     * 更新"进行中"的进度 —— 对应 Go 的 {@code updateDoingTaskProgress}
     * （like_sink.go:295-326 / article_sink.go:385-419）。
     *
     * <p>⚠️ <b>进度夹取在 Go 里位于本方法之前</b>
     * （{@code like_sink.go:296-301}：{@code if progress < 0 { progress = 0 };
     * if progress > target { progress = target }}），
     * 两处完全相同，故在本类统一完成，调用方直接传原始值。
     *
     * <p>⚠️ <b>返回值被 Go 忽略</b>：两处调用点都是
     * {@code _ = c.updateDoingTaskProgress(ctx, uid, total)}
     * （like_sink.go:275 / article_sink.go:357）⇒ 单个 uid 失败不影响同批其它 uid。
     * 本方法把异常抛给调用方，由 sink 逐条捕获并记日志。
     */
    public void updateDoing(long userId, long taskId, long target, long progress) {
        long clamped = clamp(progress, target);

        TaskProgressEntity record = new TaskProgressEntity();
        record.setUserId(userId);
        record.setTaskId(taskId);
        record.setStatus("doing");
        record.setProgress(clamped);
        record.setTarget(target);

        mapper.upsertDoingProgress(userId, taskId, record);
    }

    /**
     * 标记任务完成 —— 对应 Go 的 {@code markTaskDoneInDB}
     * （like_sink.go:366-385 / article_sink.go:462-491）。
     *
     * <p>⚠️ Go 的 like_sink 签名带一个 {@code total} 参数<b>但函数体从未使用它</b>
     * （写入的 progress 恒为 target）；article_sink 的 {@code doneAt}/{@code total}
     * 同样是被注释掉的字段。本方法<b>不接收这两个无用参数</b> ——
     * 保留无出处的形参只会让读者去找它在哪被用。
     */
    public void markDone(long userId, long taskId, long target) {
        mapper.markDone(userId, taskId, target);
    }

    /** Go 两处相同的夹取：{@code [0, target]}。 */
    private static long clamp(long progress, long target) {
        if (progress < 0L) {
            return 0L;
        }
        if (progress > target) {
            return target;
        }
        return progress;
    }
}
