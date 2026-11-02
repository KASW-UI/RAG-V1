package com.rag.follow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.follow.domain.entity.BlockRelationEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p><b>拉黑关系数据访问</b> —— 对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/mapper/BlockRelationMapper.java:25-130}
 * ⇒ <b>逐条一致</b>（方法集 / 方法名 / 参数表 / SQL 文本 / 注解）。
 *
 * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java} 全文（6 个方法）。
 * 对应 Go 端 {@code service/follow/rpc/internal/model/follow.go} 中操作 {@code BlockRelation} 的
 * {@code CreateBlockAndCleanup} 的 INSERT 半段 / {@code DeleteBlock} /
 * {@code ExistsAnyBlock} / {@code ListBlockTargets} /
 * 以及推荐算法用的 {@code ListAllBlockTargets} / {@code ListBlockerUsers}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>表达层</b> —— 包路径压平
 *       （B 侧 {@code com.rag.follow.follow.rpc.mapper} ⇒ 本仓 {@code com.rag.follow.mapper}，
 *       D7 域内单层包树，见 {@code docs/File_Tree/follow.md}）＋ 补 B 出处行号。
 *       <b>SQL 文本、注解、方法名、参数表一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 逐字相同 ⇒ 双向命中 / 幂等 / 排序 / 有无 LIMIT 全部相同。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p>⚠️ 六个方法里有三个<b>只被 GetRecommendations 用</b>
 * （{@link #listAllBlockTargets} / {@link #listBlockerUsers}，以及本接口没有但 follow 侧有的
 * 全量拉取），两个只被列表 RPC 用，两个只被 Block/Unblock 用 ——
 * 但<b>它们的 WHERE 条件两两不同</b>，抄错一个方向就会让「我拉黑的人」和「拉黑我的人」互换。
 * 每个方法的 javadoc 都标了对应的 Go 行号，改动前请回原文核对。
 */
public interface BlockRelationMapper extends BaseMapper<BlockRelationEntity> {

    /**
     * 对应 Go {@code CreateBlockAndCleanup} 的第一条语句（{@code model/follow.go:35-42}）：
     * <pre>
     * tx.Clauses(clause.OnConflict{
     *     Columns:   []clause.Column{{Name: "user_id"}, {Name: "target_id"}},
     *     DoNothing: true,
     * }).Create(&amp;BlockRelation{UserId: userID, TargetId: targetID}).Error
     * }</pre>
     *
     * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java:43-51}（SQL 与参数表逐字一致）。
     *
     * <p><b>幂等语义</b>：重复拉黑不报错（0 行受影响），与 Go 一致。
     * 冲突目标必须与 {@code V2__create_block_relation.sql} 的 {@code uk_block_pair} 逐字一致。
     *
     * <p>⚠️ 本方法<b>没有</b>包事务 —— 事务边界在
     * {@code FollowBlockTransactionService#createBlockAndCleanup}，
     * 它把本方法与 {@code FollowRelationMapper#deleteFollowBothDirections} 绑成一体。
     */
    @Insert("""
            INSERT INTO block_relation (user_id, target_id, create_time, update_time)
            VALUES (#{userId}, #{targetId}, #{createTime}, #{updateTime})
            ON CONFLICT (user_id, target_id) DO NOTHING
            """)
    int insertIgnoreConflict(@Param("userId") long userId,
                             @Param("targetId") long targetId,
                             @Param("createTime") java.time.OffsetDateTime createTime,
                             @Param("updateTime") java.time.OffsetDateTime updateTime);

    /**
     * 对应 Go {@code DeleteBlock}（{@code model/follow.go:56-60}）。
     *
     * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java:61-62}（SQL 与参数表逐字一致）。
     *
     * <p><b>⚠️ 解除一个不存在的拉黑照样返回 SUCCESS</b>（删 0 行、{@code .Error == nil}）。
     * 与 {@code DeleteFollow} 同一个不对称：<b>两个删除操作都不校验 affected rows</b>，
     * 而两个创建操作（{@code CreateFollow} / {@code Block}）会先 {@code ensureUserExists}。
     * 照原样保留，不要「顺手补上存在性校验」。
     */
    @Delete("DELETE FROM block_relation WHERE user_id = #{userId} AND target_id = #{targetId}")
    int deleteByPair(@Param("userId") long userId, @Param("targetId") long targetId);

    /**
     * 对应 Go {@code ExistsAnyBlock}（{@code model/follow.go:62-75}）：
     * <pre>
     * m.conn.Model(&amp;BlockRelation{}).Where(
     *     "(user_id = ? AND target_id = ?) OR (user_id = ? AND target_id = ?)",
     *     userID, targetID, targetID, userID).Count(&amp;count)
     * return count &gt; 0, err
     * }</pre>
     *
     * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java:81-86}（SQL 与参数表逐字一致）。
     *
     * <p><b>⚠️ 双向检查，这是「拉黑」与「关注」的关键不对称</b>：
     * 判断「能不能关注 B」时，B 已经拉黑 A 同样要拒绝。
     * 后三个占位符是 <b>(targetID, userID)</b>，顺序反了就变成单向检查 ——
     * 那样「B 拉黑 A 之后 A 仍可关注 B」会成为可通过的路径，是安全语义漏洞。
     *
     * <p>返回 <b>原始 count</b> 而非 boolean，让调用方复刻 Go 的 {@code count > 0}；
     * 顺带保留「将来若要区分 1 条与多条」的可能，而 Go 侧本来就能。
     */
    @Select("""
            SELECT COUNT(*) FROM block_relation
            WHERE (user_id = #{userId} AND target_id = #{targetId})
               OR (user_id = #{targetId} AND target_id = #{userId})
            """)
    long countAnyBlock(@Param("userId") long userId, @Param("targetId") long targetId);

    /**
     * 对应 Go {@code ListBlockTargets}（{@code model/follow.go:123-144}）：
     * 我拉黑了谁，{@code create_time desc}，投影 {@code target_id}。
     *
     * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java:92-99}（SQL 与参数表逐字一致）。
     */
    @Select("""
            SELECT target_id FROM block_relation
            WHERE user_id = #{userId}
            ORDER BY create_time DESC
            ${limitOffset}
            """)
    List<Long> listBlockTargets(@Param("userId") long userId,
                                @Param("limitOffset") String limitOffset);

    /**
     * 对应 Go {@code ListAllBlockTargets}（{@code model/follow.go:166-180}）：
     * 我拉黑了谁的全集，{@code create_time desc}。
     *
     * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java:109-114}（SQL 逐字一致）。
     *
     * <p><b>⚠️ 这条查询没有任何 LIMIT</b> —— Go 侧签名里就没有 limit 参数。
     * 它是推荐算法的<b>过滤集合</b>（blockedSet），必须完整：
     * 漏掉任何一条都会让被拉黑的人出现在推荐结果里。
     */
    @Select("""
            SELECT target_id FROM block_relation
            WHERE user_id = #{userId}
            ORDER BY create_time DESC
            """)
    List<Long> listAllBlockTargets(@Param("userId") long userId);

    /**
     * 对应 Go {@code ListBlockerUsers}（{@code model/follow.go:182-196}）：
     * 谁拉黑了我，{@code create_time desc}，投影 {@code user_id}。
     *
     * <p><b>B 出处</b>：B 侧 {@code BlockRelationMapper.java:124-129}（SQL 逐字一致）。
     *
     * <p>⚠️ 与 {@link #listBlockTargets} 的差别<b>只在过滤列</b>（{@code target_id} vs {@code user_id}），
     * 投影都是「对端 id」。在推荐算法里它构成 blockerSet，
     * 过滤掉「拉黑了我的人」，与 blockedSet 一起构成双向屏蔽。
     */
    @Select("""
            SELECT user_id FROM block_relation
            WHERE target_id = #{userId}
            ORDER BY create_time DESC
            """)
    List<Long> listBlockerUsers(@Param("userId") long userId);
}
