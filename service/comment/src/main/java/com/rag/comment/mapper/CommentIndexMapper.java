package com.rag.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.rag.comment.domain.entity.CommentIndexEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p><b>评论索引数据访问</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/CommentIndexMapper.java:19-246}
 * （B 侧对应 Go {@code service/comment/rpc/internal/model/comment.go} 里
 * {@code CommentModel} 作用于 {@code comment_index} 表的九个方法）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.mapper.CommentIndexMapper}
 * ⇒ C 侧 {@code com.rag.comment.mapper.CommentIndexMapper}。
 *
 * <p><b>为什么全部写显式 SQL</b>：本表几乎每条语句都带一段「GORM 隐式行为」——
 * {@code RowsAffected == 0 ⇒ sentinel}、位运算 {@code attribute | ?} / {@code attribute & ~?}、
 * 动态拼装的 {@code updateCols}、以及自动推进的 {@code updated_at}。
 * 用 Wrapper 重写这些时最容易被「看起来等价」地改掉，
 * 尤其是<b>漏掉 {@code updated_at}</b>（会让「这条评论被点赞过」在时间戳上不可见）
 * 和<b>漏掉位运算的差量条件</b>（会让 0 差值也写一次库）。
 */
@Mapper
public interface CommentIndexMapper {

    /**
     * 对应 Go {@code InsertCommentTx:80-96} 的 {@code tx.Create(index)}。
     *
     * <p>⚠️ {@code id} 是<b>雪花 ID</b>（来自 Kafka 消息），不是数据库序列 ——
     * 本表 DDL 里 id <b>没有</b> IDENTITY / SERIAL，写错会导致主键与审计链路脱钩。
     *
     * <p>⚠️ {@code state} 取自敏感词判定（0 正常 / 1 审核中），由调用方传入；
     * {@code attribute} 取自消息（Go 侧建消息时恒为 0）。
     *
     * <p>⚠️ <b>{@code created_at} 来自 Kafka 消息里的 {@code create_time}</b>
     * （{@code time.Unix(msg.CreateTime, 0)}）—— <b>评论创建时刻</b>，不是入库时刻。
     *
     * <p>🔴 <b>{@code updated_at} 用 {@code CURRENT_TIMESTAMP}（入库时刻），不是 created_at</b>：
     * Go 侧构造 {@code CommentIndex} 时<b>只赋 {@code CreatedAt}</b>，
     * {@code UpdatedAt} 留零 ⇒ GORM 的 {@code autoUpdateTime} 在 INSERT 时用
     * {@code curTime}（入库时刻）填（{@code gorm.io/gorm/callbacks/create.go:344-347}）。
     * <br>⇒ 两侧「created_at = 评论创建时刻、updated_at = 入库时刻」才一致。
     * 修复前 RAG 侧两列都写 created_at ⇒ INSERT 瞬间两值不同。
     */
    @Insert("""
            INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id,
                                        like_count, dislike_count, reply_count, state, attribute,
                                        created_at, updated_at)
            VALUES (#{id}, #{targetType}, #{targetId}, #{userId}, #{rootId}, #{parentId},
                    0, 0, 0, #{state}, #{attribute}, #{createdAt}, CURRENT_TIMESTAMP)
            """)
    int insert(CommentIndexEntity index);

    /**
     * 对应 Go {@code FindOneCommentById}（comment.go:633-650）：
     * {@code Where("id = ?").First(&comment)}，{@code ErrRecordNotFound → ErrorCommentNotFound}。
     *
     * <p>用于 {@code DeleteComment} 的事务外预检（delete_comment_logic.go:65）。
     */
    @Select("SELECT * FROM comment_index WHERE id = #{id}")
    CommentIndexEntity findOneById(@Param("id") long id);

    /**
     * 对应 Go {@code DeleteCommentTx:258} 的行级悲观锁：
     * {@code tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ?").First(&comment)}。
     *
     * <p>⚠️ 这条 {@code FOR UPDATE} 是删除路径唯一的并发护栏：
     * 它把「读到 state != 2」与「把 state 改成 2」之间变成临界区，
     * 从而保证「只有首次删除会做计数回退并产出 retract 事实」
     * （comment.go:306 注释：「Only the first deletion changes counters and emits a retract fact.」）。
     */
    @Select("SELECT * FROM comment_index WHERE id = #{id} FOR UPDATE")
    CommentIndexEntity selectForUpdate(@Param("id") long id);

    /**
     * 对应 Go {@code InsertCommentTx} 的缓存 miss 回源（comment.go:600-631）：
     * {@code Where("id IN ?", filtered).Find(&list)}。
     *
     * <p>⚠️ Go 侧返回顺序<b>不保证</b>与入参一致（PG 无 ORDER BY）；
     * 「按入参顺序重排」由缓存层负责（comment_index_cache.go:108-116），
     * 本方法只负责取集合。
     */
    @Select("""
            <script>
            SELECT * FROM comment_index WHERE id IN
            <foreach item="cid" collection="ids" open="(" separator="," close=")">#{cid}</foreach>
            </script>
            """)
    List<CommentIndexEntity> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 对应 Go {@code GetReplyIDsByPage}（comment.go:355-416）。
     *
     * <p><b>排序分支逐字保留</b>（comment.go:392-399）：
     * <pre>
     * case ReplySortHot:  Order("like_count DESC").Order("id DESC")
     * case ReplySortTime: fallthrough
     * default:            Order("created_at DESC").Order("id DESC")
     * </pre>
     * ⚠️ {@code id DESC} 是<b>两条分支都有</b>的稳定次序，不是可选项 ——
     * 没有它，同一 timestamp 下的翻页结果会漂移。
     *
     * <p>⚠️ {@code onlyNormal} 为 true 时才加 {@code state = 0}；
     * 但本域唯一的调用方 {@code GetComment} 传的是 <b>false</b>
     * （get_comment_logic.go:98）⇒ 已删除/审核中的评论<b>会出现在列表里</b>。
     * 这是 Go 的现状，不修正。
     *
     * @param hot true = 热度序（like_count DESC）；false = 时间序（created_at DESC）
     */
    @Select("""
            <script>
            SELECT id FROM comment_index
            WHERE target_type = #{targetType} AND target_id = #{targetId} AND root_id = #{rootId}
            <if test="onlyNormal">AND state = 0</if>
            ORDER BY
            <choose>
                <when test="hot">like_count DESC, id DESC</when>
                <otherwise>created_at DESC, id DESC</otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Long> selectReplyIdsByPage(@Param("targetType") String targetType,
                                    @Param("targetId") String targetId,
                                    @Param("rootId") long rootId,
                                    @Param("onlyNormal") boolean onlyNormal,
                                    @Param("hot") boolean hot,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /**
     * 对应 Go {@code InsertCommentTx:131} 的父评论回复数自增：
     * {@code tx.Model(&CommentIndex{}).Where("id = ?", msg.ParentId).Updates(updateCols)}。
     *
     * <p>其中 {@code updateCols} 含两个<b>条件性</b>列（comment.go:127-130）：
     * <ul>
     *   <li>{@code reply_count = reply_count + 1} —— 总是写；</li>
     *   <li>{@code attribute = attribute | 2} —— <b>仅当</b> {@code msg.UserId == msg.OwnerId}
     *       （作者回复了自己的评论 ⇒ 第 1 位「作者回复过」置起）。</li>
     * </ul>
     *
     * @return 受影响行数；0 ⇒ 父评论不存在（comment.go:138-140 ⇒ {@code ErrorCommentNotFound}，整个事务回滚）
     */
    @Update("""
            <script>
            UPDATE comment_index SET reply_count = reply_count + 1, updated_at = CURRENT_TIMESTAMP
            <if test="markAuthorReplied">, attribute = attribute | 2</if>
            WHERE id = #{parentId}
            </script>
            """)
    int incrementParentReplyCount(@Param("parentId") long parentId,
                                  @Param("markAuthorReplied") boolean markAuthorReplied);

    /**
     * 对应 Go {@code DeleteCommentTx:308} 的父评论回复数回退：
     * {@code Where("id = ?", comment.ParentId).Update("reply_count", gorm.Expr("reply_count - 1"))}。
     *
     * @return 受影响行数；0 ⇒ 父评论不存在（comment.go:315-317 ⇒ {@code ErrorCommentNotFound}，事务回滚）
     */
    @Update("UPDATE comment_index SET reply_count = reply_count - 1, updated_at = CURRENT_TIMESTAMP WHERE id = #{parentId}")
    int decrementParentReplyCount(@Param("parentId") long parentId);

    /**
     * 对应 Go {@code DeleteCommentTx:288} 的软删除：
     * {@code Where("id = ?").Update("state", 2)}。
     *
     * <p>⚠️ <b>state=2 是「已删除」而不是物理删除</b>，正文与索引行都还在
     * ⇒ GetComment 的列表会继续返回它（onlyNormal=false，见
     * {@link #selectReplyIdsByPage} 的说明）。这是 Go 的现状。
     */
    @Update("UPDATE comment_index SET state = 2, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int markDeleted(@Param("id") long id);

    /**
     * 对应 Go {@code ManageCommentAttribute}（comment.go:157-182）的<b>置位</b>分支：
     * {@code Update("attribute", gorm.Expr("attribute | ?", 1 << bitOffset))}。
     *
     * <p>调用方 {@code ManageComment} 的 bitOffset 映射（manage_comment_logic.go:66-86）：
     * {@code MANAGE_PIN ⇒ 2}（第 2 位）、{@code MANAGE_FEATURE ⇒ 3}（第 3 位）。
     * ⛔ 其它 actionType 在 logic 层已被 default 分支拦成 2001，<b>不会</b>走到这里。
     *
     * @return 受影响行数；0 ⇒ 评论不存在（comment.go:177-179 ⇒ {@code ErrorCommentNotFound}）
     */
    @Update("UPDATE comment_index SET attribute = attribute | #{mask}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int setAttributeBits(@Param("id") long id, @Param("mask") long mask);

    /**
     * 对应 Go {@code ManageCommentAttribute} 的<b>清位</b>分支：
     * {@code Update("attribute", gorm.Expr("attribute & ~?", 1 << bitOffset))}。
     *
     * <p>⚠️ 清位必须写 {@code attribute & ~mask} 而不是 {@code attribute - mask} ——
     * 后者在该位为 0 时会<b>借位</b>，把相邻低位全部翻成 1。
     *
     * @return 受影响行数；0 ⇒ 评论不存在
     */
    @Update("UPDATE comment_index SET attribute = attribute & ~#{mask}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int clearAttributeBits(@Param("id") long id, @Param("mask") long mask);

    /**
     * 对应 Go {@code LikeCommentTx:561-583} 的评论统计差量更新。
     *
     * <p>三个<b>条件性</b>列（comment.go:561-583），逐条对应：
     * <ul>
     *   <li>{@code like_count = like_count + #{likeDiff}} —— 仅当 {@code likeDiff != 0}；</li>
     *   <li>{@code like_count - 1} / {@code dislike_count - 1} 等同理；</li>
     *   <li>{@code attribute | 1}（作者赞过）或 {@code attribute & ~1}（作者取消赞）
     *       —— <b>仅当 {@code userId == ownerId}</b>，且按 {@code newState} 取置/清。</li>
     * </ul>
     *
     * <p>⚠️ Go 侧是 {@code if len(updateCols) > 0} 才执行（comment.go:576）。
     * 这里用 {@code <if>} 保证「三个条件都不满足时 SQL 退化成恒假、零行」，
     * 由调用方以 0 行视作 no-op；<b>不要</b>用一条恒真的 SQL 去「保证至少执行一次」。
     */
    @Update("""
            <script>
            UPDATE comment_index
            <set>
              <if test="likeDiff != 0">like_count = like_count + #{likeDiff},</if>
              <if test="dislikeDiff != 0">dislike_count = dislike_count + #{dislikeDiff},</if>
              <if test="ownerAttributeSet">attribute = attribute | 1,</if>
              <if test="ownerAttributeClear">attribute = attribute &amp; ~1,</if>
              updated_at = CURRENT_TIMESTAMP,
            </set>
            WHERE id = #{id}
            </script>
            """)
    int applyInteractionDeltas(@Param("id") long id,
                               @Param("likeDiff") long likeDiff,
                               @Param("dislikeDiff") long dislikeDiff,
                               /**
                                * {@code true} = 作者且新状态为已点赞 ⇒ 置第 0 位。
                                * 与 {@link #ownerAttributeClear} <b>互斥</b>，同时为 false 时不动 attribute。
                                */
                               @Param("ownerAttributeSet") boolean ownerAttributeSet,
                               @Param("ownerAttributeClear") boolean ownerAttributeClear);

    /**
     * 对应 Go {@code LikeCommentTx:499} 的评论读取（<b>不带锁</b>）：
     * {@code tx.Model(&CommentIndex{}).Where("id = ?", commentId).First(&comment)}。
     *
     * <p>⚠️ 注意与 {@link #selectForUpdate} 的区别：点赞路径锁的是
     * {@code comment_like} 行（comment.go:489），评论行只做普通读取 ——
     * 这条「不锁评论行」是 Go 的选择（点赞与删除可以并发，不必互斥）。
     */
    @Select("SELECT * FROM comment_index WHERE id = #{id}")
    CommentIndexEntity findOneByIdPlain(@Param("id") long id);
}
