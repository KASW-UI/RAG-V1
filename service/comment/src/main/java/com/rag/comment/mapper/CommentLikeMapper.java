package com.rag.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.rag.comment.domain.entity.CommentLikeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p><b>评论互动（赞/踩）记录数据访问</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/CommentLikeMapper.java:19-101}
 * （B 侧对应 Go {@code service/comment/rpc/internal/model/comment.go:472-598} 的
 * {@code LikeCommentTx} 中作用于 {@code comment_like} 表的三步）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.mapper.CommentLikeMapper}
 * ⇒ C 侧 {@code com.rag.comment.mapper.CommentLikeMapper}。
 *
 * <p><b>三步的顺序本身就是语义，不可调换</b>（comment.go:481-495）：
 * <pre>
 * // A state-0 row is the lock anchor even for the first concurrent action.
 * seed := CommentLike{UserId: …, CommentId: …, TargetType: …, TargetId: …, State: 0}
 * tx.Clauses(clause.OnConflict{DoNothing: true}).Create(&amp;seed)          // ① 播锁锚
 * err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
 *        Where("user_id = ? AND comment_id = ?", …).First(&amp;likeRecord)    // ② FOR UPDATE 取它
 * </pre>
 * ① 把并发首次互动的两个事务收敛到同一行上；② 让它们串行地算差量。
 * 少了 ①，两个并发首次点赞会各自插入、算出两份差量、评论点赞数翻倍。
 */
@Mapper
public interface CommentLikeMapper {

    /**
     * 对应 Go 的 ① {@code tx.Clauses(clause.OnConflict{DoNothing: true}).Create(&seed)}。
     *
     * <p><b>{@code ON CONFLICT DO NOTHING 不带 Columns</b>：走 PG 默认的任意唯一冲突目标，
     * 即 {@code idx_user_comment_like (user_id, comment_id)}。
     * 冲突时静默跳过 —— 这正是「锁锚只插一次」的表达。
     *
     * <p>⚠️ 插入时 {@code state} 恒为 0（Go 的 seed 显式写 {@code State: 0}）。
     */
    @Insert("""
            INSERT INTO comment_like (user_id, comment_id, target_type, target_id, state,
                                      created_at, updated_at)
            VALUES (#{userId}, #{commentId}, #{targetType}, #{targetId}, 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """)
    int insertLockAnchor(@Param("userId") long userId,
                         @Param("commentId") long commentId,
                         @Param("targetType") String targetType,
                         @Param("targetId") String targetId);

    /**
     * 对应 Go 的 ②：
     * {@code tx.Clauses(clause.Locking{Strength: "UPDATE"}).
     *        Where("user_id = ? AND comment_id = ?", userId, commentId).First(&likeRecord)}。
     *
     * <p>行级悲观锁。⚠️ 它的存在让「同一用户对同一评论的连续互动」严格串行，
     * 而不同用户之间仍然并行 —— 这是本域唯一的互斥粒度。
     *
     * <p>正常情况下 ① 刚保证了行存在，因此零行 = 数据被外部破坏 ⇒ 抛异常使事务回滚
     * （Go 侧同样把 err 上抛）。
     */
    @Select("""
            SELECT * FROM comment_like
            WHERE user_id = #{userId} AND comment_id = #{commentId}
            FOR UPDATE
            """)
    CommentLikeEntity selectForUpdate(@Param("userId") long userId, @Param("commentId") long commentId);

    /**
     * 对应 Go {@code LikeCommentTx:553}：
     * {@code tx.Model(&likeRecord).Update("state", newState)}。
     *
     * <p>⚠️ Go 用 {@code Model(&likeRecord)} 带主键更新，等价于本方法的
     * {@code WHERE user_id = ? AND comment_id = ?}（唯一键等价），但**要显式推进 updated_at**
     * —— GORM 的 {@code Update} 会自动做这件事。
     */
    @Update("""
            UPDATE comment_like SET state = #{state}, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND comment_id = #{commentId}
            """)
    int updateState(@Param("userId") long userId,
                    @Param("commentId") long commentId,
                    @Param("state") int state);

    /**
     * 对应 Go {@code fact_authority.go:178} 的互动记录读取（<b>不加锁</b>）：
     * {@code Where("user_id = ? AND comment_id = ?", subjectID(...), payload.CommentID).Limit(1).Find(&record)}。
     *
     * <p>用于权威读的「重放出的最终状态必须与当前库内状态一致」校验（fact_authority.go:183）。
     */
    @Select("""
            SELECT * FROM comment_like
            WHERE user_id = #{userId} AND comment_id = #{commentId}
            """)
    CommentLikeEntity findOneByUserAndComment(@Param("userId") long userId, @Param("commentId") long commentId);
}
