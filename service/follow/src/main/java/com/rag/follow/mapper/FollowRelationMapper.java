package com.rag.follow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.follow.domain.entity.FollowRelationEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p><b>关注关系数据访问</b> —— 对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/mapper/FollowRelationMapper.java:35-142}
 * ⇒ <b>逐条一致</b>（方法集 / 方法名 / 参数表 / SQL 文本 / 注解）。
 *
 * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java} 全文（5 个方法 + 1 条跨表 DELETE）。
 * 对应 Go 端 {@code service/follow/rpc/internal/model/follow.go} 中操作 {@code FollowRelation} 的
 * {@code CreateFollow} / {@code DeleteFollow} / {@code ListFollowTargets} /
 * {@code ListFollowerUsers} / {@code ListAllFollowTargets}
 * （外加 {@code CreateBlockAndCleanup} 里的那条跨表 DELETE）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>表达层</b> —— 包路径压平
 *       （B 侧 {@code com.rag.follow.follow.rpc.mapper} ⇒ 本仓 {@code com.rag.follow.mapper}，
 *       D7 域内单层包树，见 {@code docs/File_Tree/follow.md}）＋ 补 B 出处行号。
 *       <b>SQL 文本、注解、方法名、参数表一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 逐字相同 ⇒ 行数 / 幂等性 / 双向性 / 排序 / 分页窗口全部相同；
 *       {@code ${limitOffset}} 插值语义也相同（见 {@link GoStyleLimitOffset}）。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p><b>⚠️ 本接口刻意不覆盖 Go 的 {@code CreateBlockAndCleanup}</b>：
 * 那个方法是「插 block + 双向删 follow」的<b>事务</b>，事务边界属于
 * {@code FollowBlockTransactionService}，不在数据访问层。
 * {@link #deleteFollowBothDirections} 是那笔事务里的第二条语句，单独暴露出来。
 *
 * <p><b>⚠️ 三处刻意不用 MyBatis-Plus 的通用 API</b>：
 * <ol>
 *   <li><b>{@link #insertIgnoreConflict}</b>：Go 用 GORM 的
 *       {@code Clauses(clause.OnConflict{Columns: [user_id,target_id], DoNothing: true})}。
 *       MP 的 {@code insert(entity)} 不支持 ON CONFLICT，BaseMapper 没有对应能力。</li>
 *   <li><b>{@link #listFollowTargets} 等三个列表</b>：Go 的 LIMIT/OFFSET 是<b>条件拼接</b>的
 *       （见 {@link GoStyleLimitOffset}），MP 的 {@code Page} / {@code Last} 是另一套分页语义
 *       （会额外发一条 count），且 {@code offset<=0} 时行为不同。</li>
 *   <li><b>聚合查询</b>：{@code existsAnyBlock} 在 BlockRelationMapper 上，这里同理。</li>
 * </ol>
 */
public interface FollowRelationMapper extends BaseMapper<FollowRelationEntity> {

    /**
     * 对应 Go {@code CreateFollow}（{@code model/follow.go:18-25}）：
     * <pre>
     * m.conn.Clauses(clause.OnConflict{
     *     Columns:   []clause.Column{{Name: "user_id"}, {Name: "target_id"}},
     *     DoNothing: true,
     * }).Create(&amp;FollowRelation{UserId: userID, TargetId: targetID}).Error
     * </pre>
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java:55-63}（SQL 与参数表逐字一致）。
     *
     * <p><b>幂等语义</b>：重复关注<b>不报错</b>，返回 0 行受影响，Go 侧同样 {@code Error == nil}
     * ⇒ logic 层照常返回 SUCCESS。
     * ⚠️ 冲突目标 {@code (user_id, target_id)} 必须与 {@code V1__create_follow_relation.sql}
     * 里的 {@code uk_follow_pair} 唯一索引<b>逐字一致</b>，否则 PG 报
     * {@code there is no unique or exclusion constraint matching the ON CONFLICT specification}。
     *
     * <p><b>create_time / update_time 由调用方赋值</b>（对应 Go 的 {@code autoCreateTime} /
     * {@code autoUpdateTime} tag —— 时间戳由应用进程产生，不是 DB 默认值）。
     */
    @Insert("""
            INSERT INTO follow_relation (user_id, target_id, create_time, update_time)
            VALUES (#{userId}, #{targetId}, #{createTime}, #{updateTime})
            ON CONFLICT (user_id, target_id) DO NOTHING
            """)
    int insertIgnoreConflict(@Param("userId") long userId,
                             @Param("targetId") long targetId,
                             @Param("createTime") java.time.OffsetDateTime createTime,
                             @Param("updateTime") java.time.OffsetDateTime updateTime);

    /**
     * 对应 Go {@code DeleteFollow}（{@code model/follow.go:27-31}）：
     * <pre>{@code Where("user_id = ? AND target_id = ?", userID, targetID).Delete(&FollowRelation{})}</pre>
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java:73-74}（SQL 与参数表逐字一致）。
     *
     * <p><b>⚠️ 删 0 行不算错误</b>：Go 侧同样只看 {@code .Error}，
     * 取消一个从未关注过的人照样返回 SUCCESS。这是 Go 的实际行为，照搬。
     * ⇒ 本方法<b>不</b>判 affected rows，也<b>不</b>据此抛异常。
     */
    @Delete("DELETE FROM follow_relation WHERE user_id = #{userId} AND target_id = #{targetId}")
    int deleteByPair(@Param("userId") long userId, @Param("targetId") long targetId);

    /**
     * {@code CreateBlockAndCleanup} 事务里的第二条语句（{@code model/follow.go:44-52}）：
     * <pre>{@code
     * tx.Where("(user_id = ? AND target_id = ?) OR (user_id = ? AND target_id = ?)",
     *        userID, targetID, targetID, userID).Delete(&amp;FollowRelation{})
     * }</pre>
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java:87-92}（SQL 与参数表逐字一致）。
     *
     * <p><b>为什么是双向删除</b>：拉黑 A→B 之后，A 关注 B 与 B 关注 A 两条关系都要清掉，
     * 否则拉黑后还能从「粉丝列表」里看到对方。四个占位符的顺序不可调换 ——
     * 第 3、4 个是 (targetID, userID) 而不是 (userID, targetID)。
     */
    @Delete("""
            DELETE FROM follow_relation
            WHERE (user_id = #{userId} AND target_id = #{targetId})
               OR (user_id = #{targetId} AND target_id = #{userId})
            """)
    int deleteFollowBothDirections(@Param("userId") long userId, @Param("targetId") long targetId);

    /**
     * 对应 Go {@code ListFollowTargets}（{@code model/follow.go:77-98}）：
     * 取该用户关注了谁，按 {@code create_time desc} 排序，取 {@code target_id} 列表。
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java:101-108}（SQL 与参数表逐字一致）。
     *
     * <p>Go 侧还做了一步「查出整行 → 逐行 append TargetId」的映射（{@code model/follow.go:93-97}），
     * SQL 层语义等价于 {@code SELECT target_id ...}，差别只是多传了 4 列。
     */
    @Select("""
            SELECT target_id FROM follow_relation
            WHERE user_id = #{userId}
            ORDER BY create_time DESC
            ${limitOffset}
            """)
    List<Long> listFollowTargets(@Param("userId") long userId,
                                 @Param("limitOffset") String limitOffset);

    /**
     * 对应 Go {@code ListFollowerUsers}（{@code model/follow.go:100-121}）：
     * 取谁关注了该用户，按 {@code create_time desc}，返回 {@code user_id}。
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java:117-124}（SQL 与参数表逐字一致）。
     *
     * <p>⚠️ <b>过滤列与投影列不同</b>：{@code WHERE target_id = ?} 但取的是 {@code user_id}。
     * 三个列表方法里只有这一个不是「同列过滤同列投影」，最容易抄错。
     */
    @Select("""
            SELECT user_id FROM follow_relation
            WHERE target_id = #{userId}
            ORDER BY create_time DESC
            ${limitOffset}
            """)
    List<Long> listFollowerUsers(@Param("userId") long userId,
                                 @Param("limitOffset") String limitOffset);

    /**
     * 对应 Go {@code ListAllFollowTargets}（{@code model/follow.go:146-164}）：
     * <b>只有 limit、没有 offset</b>（Go 侧这个方法签名里就没有 offset）。
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowRelationMapper.java:134-141}（SQL 与参数表逐字一致）。
     *
     * <p>两个消费点：{@code GetRecommendations} 的第 1 层（取直关），
     * 以及 BFS 每一层对每个源用户的展开。⚠️ 因此这条 SQL 的 limit 一旦失效成「无上限」，
     * 影响面是<b>整次推荐</b>而不是单页。
     */
    @Select("""
            SELECT target_id FROM follow_relation
            WHERE user_id = #{userId}
            ORDER BY create_time DESC
            ${limitOffset}
            """)
    List<Long> listAllFollowTargets(@Param("userId") long userId,
                                    @Param("limitOffset") String limitOffset);
}
