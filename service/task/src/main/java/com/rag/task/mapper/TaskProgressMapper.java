package com.rag.task.mapper;

import com.rag.task.domain.entity.TaskProgressEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p><b>task_progress 数据访问</b> —— 1:1 对应 Go 端三处 GORM 调用。</p>
 *
 * <h2>⚠️ 全部写显式 SQL 而不用 BaseMapper / LambdaQueryWrapper</h2>
 * <p>Go 侧这三条 upsert 的语义<b>有一半在 GORM 的 {@code clause.OnConflict} 隐式行为里</b>：
 * DO UPDATE 的赋值是 {@code clause.Assignments(map)}，里面还嵌了
 * {@code gorm.Expr("GREATEST(...)")}、{@code CASE WHEN}、{@code COALESCE(...)}，
 * 并且<b>插入列集由结构体字段决定</b>（两个 sink 的局部结构体都<b>没有</b>时间字段，
 * 所以 INSERT 不含 created_at/updated_at，而 DO UPDATE 里又显式写 updated_at）。
 * 换成 Wrapper 会把这些判据改写成"看起来等价"的形态，其中最容易错的是
 * 插入时多带一列 null 时间戳 —— 那会让已 done 的行被新的 null 覆盖。
 *
 * <h2>⚠️ 三条 upsert 是<b>幂等链路</b>的基石，全部依赖 {@code ON CONFLICT (user_id, task_id)}</h2>
 * <p>对应 Go 的复合主键（{@code TaskProgress} 两个字段都带 {@code primary_key}）。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mapper} → {@code com.rag.task.mapper}（D7）；</li>
 *         <li>实体包名随包压平而变（{@code domain} → {@code domain.entity}）⇒ import 随之调整；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 三条 SQL 的<b>列集</b>、<b>冲突目标</b>、
 *       <b>DO UPDATE 的三个赋值</b>逐字照抄（DB 级不变量全在 SQL 里）。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mapper/TaskProgressMapper.java:26-27（@Mapper + 接口声明）
 * B 出处：…/mapper/TaskProgressMapper.java:39-44（selectByUserId）
 * B 出处：…/mapper/TaskProgressMapper.java:63-72（batchInsertIfAbsent）
 * B 出处：…/mapper/TaskProgressMapper.java:93-106（upsertDoingProgress）
 * B 出处：…/mapper/TaskProgressMapper.java:122-134（markDone）
 *       </pre></li>
 * </ol>
 */
@Mapper
public interface TaskProgressMapper {

    /**
     * 对应 Go {@code get_task_logic.go:53-57}：
     * {@code Model(&Model.TaskProgress{}).Select("user_id, task_id, status, progress, target").Where("user_id = ?", uid).Find(&rows)}。
     *
     * <p><b>列集必须逐字一致</b>：Go 显式 Select 的就是这 5 列，
     * 少一列会让 {@code packProgress} 拿不到 status/target，多一列无影响但不忠实。
     *
     * <p>⚠️ <b>没有 ORDER BY</b>：Go 也没有。多行时顺序不确定，
     * 但调用方是逐行塞进 map（key=task_id），顺序不影响结果。
     */
    @Select("""
            SELECT user_id, task_id, status, progress, target
            FROM task_progress
            WHERE user_id = #{userId}
            """)
    List<TaskProgressEntity> selectByUserId(@Param("userId") long userId);

    /**
     * 对应 Go {@code sink/like_sink.go:237-240} 与
     * {@code sink/article_sink.go:324-327} 的
     * {@code Clauses(clause.OnConflict{Columns: {user_id, task_id}, DoNothing: true}).Create(&records)}。
     *
     * <p><b>批量 + DO NOTHING</b>：只在「新用户 / 新任务组合」时插入一条 doing/0/target 的初始行。
     * 已存在的行<b>完全不动</b>（不是 DO UPDATE）—— 这是「首次见到该用户才初始化」的语义，
     * 若改成 upsert 会把已有进度重置为 0。
     *
     * <p>⚠️ 插入列<b>不含</b> created_at / updated_at：Go 的两个局部结构体
     * （UserTaskProgress / ArticleLikeTaskProgress）都没有这两个字段，
     * GORM 因此不生成这两列，让 PG 取默认 NULL。
     * 多插一列 {@code now()} 会与 Go 的行内容不同（虽然当前无人读这两列）。
     *
     * @param records 待初始化记录；空列表时 Java 侧直接跳过（PG 不接受空 VALUES）
     * @return 受影响行数
     */
    @Insert("""
            <script>
            INSERT INTO task_progress (user_id, task_id, status, progress, target) VALUES
            <foreach collection="list" item="it" separator=",">
              (#{it.userId}, #{it.taskId}, #{it.status}, #{it.progress}, #{it.target})
            </foreach>
            ON CONFLICT (user_id, task_id) DO NOTHING
            </script>
            """)
    int batchInsertIfAbsent(@Param("list") List<TaskProgressEntity> records);

    /**
     * 对应 Go {@code sink/like_sink.go:311-323} 与 {@code sink/article_sink.go:401-416} 的
     * {@code updateDoingTaskProgress}。
     *
     * <p>三个赋值各自的语义，缺一不可：
     * <ul>
     *   <li>{@code status = CASE WHEN task_progress.status = 'done' THEN task_progress.status
     *       ELSE EXCLUDED.status END} —— <b>已完成的任务不许被回退成 doing</b>。
     *       消息乱序/重投时，迟到的「未达标」快照不能把 done 改回去。</li>
     *   <li>{@code progress = GREATEST(task_progress.progress, EXCLUDED.progress)} ——
     *       进度只增不减。</li>
     *   <li>{@code target = #{target}} —— 注意是<b>常量覆盖</b>，不是 GREATEST。</li>
     * </ul>
     * {@code updated_at = now()} 只在 DO UPDATE 分支生效，DO NOTHING 时不动原行。
     *
     * <p>⚠️ 调用方在传参前已把 progress 夹到 {@code [0, target]}
     * （like_sink.go:296-301 / article_sink.go:386-391），
     * 所以 GREATEST 实际上只在「存量 > 新值」时才起作用。
     */
    @Insert("""
            INSERT INTO task_progress (user_id, task_id, status, progress, target) VALUES
              (#{userId}, #{taskId}, #{it.status}, #{it.progress}, #{it.target})
            ON CONFLICT (user_id, task_id) DO UPDATE SET
              status = CASE WHEN task_progress.status = 'done'
                            THEN task_progress.status
                            ELSE EXCLUDED.status END,
              progress = GREATEST(task_progress.progress, EXCLUDED.progress),
              target = #{it.target},
              updated_at = now()
            """)
    int upsertDoingProgress(@Param("userId") long userId,
                            @Param("taskId") long taskId,
                            @Param("it") TaskProgressEntity record);

    /**
     * 对应 Go {@code sink/like_sink.go:375-384} 与 {@code sink/article_sink.go:477-490} 的
     * {@code markTaskDoneInDB}。
     *
     * <p>与 {@link #upsertDoingProgress} 的差别有三处，全是行为性的：
     * <ul>
     *   <li>{@code status} 是<b>无条件</b>覆盖成 {@code 'done'}（没有 CASE 保护）——
     *       因为调用它之前 {@code task:done:*} 的 SETNX 已经保证了每人每任务只走一次；</li>
     *   <li>{@code progress} 被<b>常量覆盖</b>成 target，而不是 GREATEST ——
     *       即便旧值更大也会被压到 target；</li>
     *   <li>{@code done_at = COALESCE(task_progress.done_at, now())} —
     *       <b>首次完成时间不可被覆盖</b>，重复调用不刷新它。</li>
     * </ul>
     */
    @Insert("""
            INSERT INTO task_progress (user_id, task_id, status, progress, target) VALUES
              (#{userId}, #{taskId}, 'done', #{target}, #{target})
            ON CONFLICT (user_id, task_id) DO UPDATE SET
              status = 'done',
              progress = #{target},
              target = #{target},
              done_at = COALESCE(task_progress.done_at, now()),
              updated_at = now()
            """)
    int markDone(@Param("userId") long userId,
                 @Param("taskId") long taskId,
                 @Param("target") long target);
}
