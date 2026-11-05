package com.rag.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.rag.comment.domain.entity.SubjectEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p><b>评论区（subject）数据访问</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/SubjectMapper.java:19-175}
 * （B 侧对应 Go {@code service/comment/rpc/internal/model/comment.go} 里 {@code CommentModel} 的四个 subject 方法）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.mapper.SubjectMapper}
 * ⇒ C 侧 {@code com.rag.comment.mapper.SubjectMapper}。
 *
 * <p><b>为什么全部写显式 SQL 而不用 BaseMapper / LambdaQueryWrapper</b>：
 * B 侧这几条语句的语义有相当一部分在 GORM 的<b>隐式行为</b>里 ——
 * {@code RowsAffected == 0 ⇒ 返回 sentinel 错误}（这是本域多处分支的判据）、
 * upsert 里 {@code subject.} 前缀指向既有行、{@code Update} 自动推进 {@code updated_at}。
 * 换成 Wrapper 后这些条件会被「看起来等价」地改写，其中最容易错的是
 * <b>把「零行」当成「空结果」</b> —— 那会把「评论区不存在(1012)」降级成「评论区为空」。
 */
@Mapper
public interface SubjectMapper {

    /**
     * 对应 Go {@code FindOneSubjectByTarget}（comment.go:652-669）：
     * {@code Where("target_type = ? AND target_id = ?").First(&subject)}，
     * {@code ErrRecordNotFound → ErrorSubjectNotFound}。
     *
     * <p><b>零行 ⇒ 返回 {@code null}</b>，由调用方映射成 {@code SubjectNotFound}。
     * Go 侧靠 {@code err == gorm.ErrRecordNotFound} 判定，Java 侧靠返回值 null ——
     * 两者在这里是同一个判据。
     */
    @Select("SELECT * FROM subject WHERE target_type = #{targetType} AND target_id = #{targetId}")
    SubjectEntity findOneByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /**
     * 对应 Go {@code GetOwnerId}（comment.go:203-220）：
     * <pre>
     * db := conn.Model(&amp;Subject{}).Where("target_type = ? AND target_id = ?", …).
     *        Select("owner_id").Scan(&amp;ownerId)
     * if db.RowsAffected == 0 { return 0, ErrorSubjectNotFound }
     * </pre>
     *
     * <p><b>用 {@code Long} 承接而不是 {@code int}</b>：Go 的 {@code Scan(&ownerId)}
     * 作用在标量上，零行时 ownerId 保持 0 且靠 RowsAffected 判定。Java 侧若用
     * {@code int}/{@code Integer}，查不到行与「owner_id 真的等于 0」会撞在一起。
     *
     * <p>⚠️ 两个调用点的错误码<b>不一样</b>，这是 Go 的现状，Java 侧照搬：
     * <ul>
     *   <li>{@code CreateComment} → 1012 {@code ErrorSubjectNotExist}（create_comment_logic.go:82）；</li>
     *   <li>{@code LikeComment}   → 1012，但它的兜底分支发的是
     *       <b>2001 {@code ErrorInputWrong}</b> 而非 5004
     *       （like_comment_logic.go:63 —— 日志记 5004、线上发 2001，是 Go 源码的事实，
     *       见 LikeComment 用例注释）。</li>
     * </ul>
     */
    @Select("SELECT owner_id FROM subject WHERE target_type = #{targetType} AND target_id = #{targetId}")
    Long selectOwnerId(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /**
     * 对应 Go {@code InsertCommentTx} 里的 subject upsert（comment.go:99-123）。
     *
     * <p><b>三条必须逐字保留的细节：</b>
     * <ol>
     *   <li><b>冲突目标固定是 {@code (target_type, target_id)}</b> —— 与 {@code V1} 的唯一索引
     *       {@code idx_target} 一致；写别的列会退化成「每行都插入」。</li>
     *   <li><b>{@code total_count = subject.total_count + 1} 里的表名限定前缀不能省</b>：
     *       PG 的 {@code ON CONFLICT DO UPDATE} 里必须写 {@code subject.total_count} 才能引用既有行值，
     *       写成裸 {@code total_count + 1} 会报 <i>column reference is ambiguous</i>
     *       （insert 的 VALUES 与既有行同名）。</li>
     *   <li><b>root_count 的自增是条件性的</b>：只有 {@code msg.RootId == 0}（本条即根评论）
     *       才 {@code +1}（comment.go:112-114）；回复类评论只动 total_count。</li>
     * </ol>
     *
     * <p>⚠️ <b>冲突分支不更新 owner_id / state / attribute / created_at / updated_at</b>：
     * Go 的 {@code DoUpdates: clause.Assignments(updateCols)} 只含那 1~2 个计数列。
     * 这里补上 {@code updated_at} 会让「谁最后评论的」通过时间戳泄漏出来，属新增可观察行为。
     */
    @Insert("""
            <script>
            INSERT INTO subject (target_type, target_id, total_count, root_count, state, attribute, owner_id,
                                 created_at, updated_at)
            VALUES (#{targetType}, #{targetId}, 1, #{rootCount}, 0, 0, #{ownerId},
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (target_type, target_id) DO UPDATE SET total_count = subject.total_count + 1
            <if test="rootCount == 1">, root_count = subject.root_count + 1</if>
            </script>
            """)
    int upsertCommentSubject(@Param("targetType") String targetType,
                             @Param("targetId") String targetId,
                             /** 根评论传 1、回复传 0 —— 同时决定 VALUES 里的 root_count 与是否 +1。 */
                             @Param("rootCount") int rootCount,
                             @Param("ownerId") long ownerId);

    /**
     * 对应 Go {@code UpdateSubjectState}（comment.go:184-201）：
     * {@code Model(&Subject{}).Where(target_type = ? AND target_id = ?).Update("state", state)}，
     * {@code RowsAffected == 0 ⇒ ErrorSubjectNotFound}。
     *
     * <p>⚠️ Go 的 {@code Update} 会自动推进 {@code updated_at}（struct 上有 autoUpdateTime），
     * Java 侧必须显式写出这一列，否则「评论区状态被改过」在时间戳上不可见。
     *
     * @return 受影响行数；0 ⇒ 评论区不存在（调用方发 1012）
     */
    @Update("""
            UPDATE subject SET state = #{state}, updated_at = CURRENT_TIMESTAMP
            WHERE target_type = #{targetType} AND target_id = #{targetId}
            """)
    int updateState(@Param("targetType") String targetType,
                    @Param("targetId") String targetId,
                    @Param("state") int state);

    /**
     * 对应 Go {@code DeleteCommentTx} 里的 subject 计数回退（comment.go:295-304）。
     *
     * <p>与 {@link #upsertCommentSubject} 成对：删评论时 {@code total_count - 1}，
     * 且仅当被删的是根评论（{@code comment.RootId == 0}）才 {@code root_count - 1}。
     *
     * <p>⚠️ Go 侧<b>不检查</b>这一步的 RowsAffected —— 与 {@code updateState} 不同。
     *
     * <p>🔴🔴 <b>2026-10-24 双侧活体对拍时修掉的真实缺陷</b>：
     * 此前 {@code <if>} 块被拼在 <b>WHERE 子句之后</b>，生成
     * <pre>
     * UPDATE subject SET total_count = total_count - 1, updated_at = CURRENT_TIMESTAMP
     * WHERE target_type = ? AND target_id = ? , root_count = root_count - 1
     * </pre>
     * ⇒ {@code rootCountDecrement=true} 时是<b>语法错误</b>
     * （PG: {@code syntax error at or near ","}）⇒ <b>删根评论必然失败</b>
     * ⇒ 删评论走 {@code DeleteCommentTx} ⇒ 整个事务回滚 ⇒ 对外报 5002「DB更新失败」。
     *
     * <p>⚠️ 为什么单元测试抓不到：此前的 rpc 测试全是纯 Java 逻辑断言 + 裸 JDBC，
     * <b>不经过 MyBatis 动态拼接</b> ⇒ 是<b>双侧活体对拍</b>把它抓出来的。
     *
     * <p>⇒ 现在用 MyBatis 的 {@code <set>}：它会<b>自动去掉最后一项的尾逗号</b>
     * （与 {@link CommentIndexMapper#applyInteractionDeltas} 同款写法）。
     */
    @Update("""
            <script>
            UPDATE subject
            <set>
              total_count = total_count - 1,
              <if test="rootCountDecrement">root_count = root_count - 1,</if>
              updated_at = CURRENT_TIMESTAMP,
            </set>
            WHERE target_type = #{targetType} AND target_id = #{targetId}
            </script>
            """)
    int decrementCounts(@Param("targetType") String targetType,
                        @Param("targetId") String targetId,
                        @Param("rootCountDecrement") boolean rootCountDecrement);

    /**
     * 对应 Go {@code DeleteCommentTx:344-347} 的「删除后重读 subject 取 remainCount」。
     *
     * <p>⚠️ 注意它读的是<b>事务内</b>的值，且用的是 {@code First}（不是 Find）——
     * 若 subject 行此时不存在，Go 会返回 {@code ErrRecordNotFound} 使整个事务回滚。
     * Java 侧返回 {@code null}，由调用方抛 {@code SubjectNotFound}。
     */
    @Select("SELECT * FROM subject WHERE target_type = #{targetType} AND target_id = #{targetId}")
    SubjectEntity selectForRemainCount(@Param("targetType") String targetType,
                                       @Param("targetId") String targetId);
}
