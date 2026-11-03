package com.rag.task.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p><b>user_like_count 数据访问</b> —— 对应 Go 端 {@code internal/sink/like_sink.go:406-422} 的
 * {@code upsertChunk}。</p>
 *
 * <p>⚠️ 本表<b>只有这一条写路径</b>：{@code like_sink.go} 的 {@code flushPostgres}，
 * 按 {@code pgBatchMax}（2000）分块调用。task 域自己不读它。
 *
 * <p>⚠️ {@code user_id} 是 <b>TEXT</b>（Go 的 {@code UserLikeCount.UserID string}），
 * 与 task_progress 的 BIGINT 不同，见 {@code UserLikeCountEntity} 的类注释。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mapper} → {@code com.rag.task.mapper}（D7）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— INSERT 的<b>列集</b>（含 {@code updated_at = now()}）、
 *       {@code ON CONFLICT (user_id)} 的冲突目标、{@code GREATEST} 赋值逐字照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mapper/UserLikeCountMapper.java:19-20（@Mapper + 接口声明）
 * B 出处：…/mapper/UserLikeCountMapper.java:44-55（batchUpsertGreatest）
 * B 出处：…/mapper/UserLikeCountMapper.java:65-96（UserLikeCountRow）
 *       </pre></li>
 * </ol>
 */
@Mapper
public interface UserLikeCountMapper {

    /**
     * 批量 upsert 点赞总数，语义是<b>只增不减</b>。
     *
     * <p>对应 Go：
     * <pre>
     * c.gdb.Clauses(clause.OnConflict{
     *     Columns: []clause.Column{{Name: "user_id"}},
     *     DoUpdates: clause.Assignments(map[string]interface{}{
     *         "like_count": gorm.Expr("GREATEST(user_like_count.like_count, EXCLUDED.like_count)"),
     *         "updated_at": gorm.Expr("now()"),
     *     }),
     * }).Create(&records)
     * </pre>
     *
     * <p>⚠️ <b>插入时带 updated_at = now()</b>：Go 的 {@code UserLikeCount} 结构体有
     * {@code UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`}，
     * GORM 在 Create 路径会自动填该字段 ⇒ INSERT 语句<b>包含</b>这一列。
     * 漏掉它 ⇒ 新行的 updated_at 为 NULL，与 Go 侧不同（task_progress 的两条 upsert
     * 反而<b>不能</b>带，理由见 TaskProgressMapper 的注释 —— 两处不一样，别互相抄）。
     *
     * <p>GREATEST 保证乱序重投 / 计数回退时不会把 like_count 写小。
     */
    @Insert("""
            <script>
            INSERT INTO user_like_count (user_id, like_count, updated_at) VALUES
            <foreach collection="list" item="it" separator=",">
              (#{it.userId}, #{it.likeCount}, now())
            </foreach>
            ON CONFLICT (user_id) DO UPDATE SET
              like_count = GREATEST(user_like_count.like_count, EXCLUDED.like_count),
              updated_at = now()
            </script>
            """)
    int batchUpsertGreatest(@Param("list") List<UserLikeCountRow> list);

    /**
     * {@link #batchUpsertGreatest} 的一行。
     *
     * <p>⚠️ 用独立 POJO 而不是 {@code UserLikeCountEntity}：
     * 实体上标了 {@code @TableId} 与 {@code @TableName}，在这里只作为参数容器使用，
     * 混用会让"MyBatis-Plus 会接管这条 SQL"的错觉成立 —— 实际上它不会，
     * 注解 SQL 里写死的是表名与列名。
     */
    class UserLikeCountRow {

        /** 对应 Go {@code UserLikeCount.UserID}（字符串，表中为 TEXT）。 */
        public String userId;

        /** 对应 Go {@code UserLikeCount.LikeCount}。 */
        public long likeCount;

        public UserLikeCountRow() {
        }

        public UserLikeCountRow(String userId, long likeCount) {
            this.userId = userId;
            this.likeCount = likeCount;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public long getLikeCount() {
            return likeCount;
        }

        public void setLikeCount(long likeCount) {
            this.likeCount = likeCount;
        }
    }
}
