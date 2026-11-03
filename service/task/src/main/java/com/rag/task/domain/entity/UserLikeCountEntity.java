package com.rag.task.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>用户点赞总数</b> —— 对应 Go 端 {@code internal/Model/taskModel.go:43-50} 的 {@code UserLikeCount}
 * （以及 {@code internal/sink/like_sink.go:17-25} 的同表结构体，两者是同一张表）。</p>
 *
 * <p>⚠️ <b>{@code userId} 在本表是 String / TEXT，在 {@link TaskProgressEntity} 里是 Long / BIGINT。</b>
 * 这是 Go 的现状（{@code UserLikeCount.UserID string} vs {@code TaskProgress.UserID int64}），
 * 不要"顺手统一"：统一会让 Go 侧写入的行在 Java 侧查不出来（或反之）。
 *
 * <p>写入语义是<b>只增不减</b>：{@code like_sink.go} 的 upsertChunk 用
 * {@code GREATEST(user_like_count.like_count, EXCLUDED.like_count)}，
 * 因此乱序重投不会把计数写小。
 *
 * <p>⚠️ task 域<b>自己不读本表</b>，只写。
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
 *   <li><b>语义还一样吗</b>：一样 —— 字段名、列名、类型逐条一致。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/domain/UserLikeCountEntity.java:22-23（@TableName + 类声明）
 * B 出处：…/domain/UserLikeCountEntity.java:26-27（userId · String · @TableId）
 * B 出处：…/domain/UserLikeCountEntity.java:30（likeCount）
 * B 出处：…/domain/UserLikeCountEntity.java:33（updatedAt）
 *       </pre></li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * @TableName("user_like_count")
 * public class UserLikeCountEntity {
 *
 *     @TableId(value = "user_id")
 *     private String userId;
 *
 *     private Long likeCount;
 *
 *     private OffsetDateTime updatedAt;
 *
 *     public String getUserId() { return userId; }
 *     public void setUserId(String userId) { this.userId = userId; }
 *     public Long getLikeCount() { return likeCount; }
 *     public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *
 *     public UserLikeCountEntity() {
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("user_like_count")
public class UserLikeCountEntity {

    /** Go {@code UserID string} ⇒ <b>TEXT</b>（与 task_progress.user_id 的 BIGINT 不同）。 */
    @TableId(value = "user_id")
    private String userId;

    /** Go {@code LikeCount int64} ⇒ BIGINT。 */
    private Long likeCount;

    /** Go {@code UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`} ⇒ TIMESTAMPTZ。 */
    private OffsetDateTime updatedAt;
}
