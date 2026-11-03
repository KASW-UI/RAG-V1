package com.rag.like.mapper;

import com.rag.like.domain.entity.LikeRecordEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>点赞记录数据访问</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mapper/LikeRecordMapper.java:20-340}
 * 的全部 SQL（逐条照抄，SQL 文本<b>一字未改</b>）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.mapper.LikeRecordMapper}
 * ⇒ C 侧 {@code com.rag.like.mapper.LikeRecordMapper}。
 *
 * <h3>⚠️ 为什么全部写显式 SQL 而不用 BaseMapper / LambdaQueryWrapper</h3>
 * <p>B 侧这几条查询的语义有一半在 GORM 的<b>隐式行为</b>里（软删除过滤、
 * {@code count(1) as count} 的分组语义、{@code id < cursor} 的倒序分页）。
 * 换成 Wrapper 后这些条件会被「看起来等价」地改写，其中最容易错的是：
 * <ul>
 *   <li>忘了 {@code delete_at IS NULL} ⇒ 已软删的行被算进点赞数；</li>
 *   <li>忘了 {@code state = 1} 与 {@code state IN (1,2)} 的<b>不同取值域</b>
 *       （计数路径要含点踩，分页路径只含点赞）。</li>
 * </ul>
 * 显式 SQL 把每条判据摆在明面上，也便于逐条对回 B 侧。
 *
 * <p>⚠️ <b>软删除</b>：除 {@link #selectForUpdateIncludingDeleted} 外，所有查询都带
 * {@code delete_at IS NULL}（＝B 侧 GORM 的默认作用域）。唯一例外是 B 侧
 * {@code applyLikeMessage} 里刻意用 {@code Unscoped()} 读已软删行并据此报错 —— 见该方法注释。
 */
@Mapper
public interface LikeRecordMapper {

    // ==================== 读路径（对应 B 侧 LikeRecordModel 的 Get* 方法） ====================

    /**
     * 对应 B 侧 {@code GetTotalLikeCount}：
     * {@code WHERE author_id = ? AND state = 1} 计数。
     *
     * <p>这是 {@code GetUserTotalLike} 缓存未命中时的回源查询 —— 注意它统计的是
     * 「author_id = 用户」且 state=1 的行数，即该用户收到的点赞总数。
     */
    @Select("SELECT count(*) FROM like_record WHERE author_id = #{authorId} AND state = 1 AND delete_at IS NULL")
    long countActiveLikeByAuthor(@Param("authorId") long authorId);

    /**
     * 对应 B 侧 {@code GetUserActiveLikeCount}：
     * {@code WHERE user_id = ? AND target_type = ? AND state = 1} 计数。
     *
     * <p>用于 {@code syncTaskProgress} 里算「我给出的赞」，
     * 与 {@code currentUserLikeTotal} 取 {@code max(Redis ZCARD, 本值)}。
     */
    @Select("""
            SELECT count(*) FROM like_record
            WHERE user_id = #{userId} AND target_type = #{targetType} AND state = 1 AND delete_at IS NULL
            """)
    long countActiveLikeByUserAndType(@Param("userId") long userId, @Param("targetType") String targetType);

    /**
     * 对应 B 侧 {@code fact_authority.go:108-109} 的源库状态核对查询：
     * {@code WHERE user_id = ? AND target_type = ? AND target_id = ? LIMIT 1}。
     *
     * <p>🔴 <b>为什么权威读需要它</b>：B 侧的 {@code AuthoritativeFact} 最后一步要拿
     * {@code like_record} 的<b>当前行</b>与「重放出来的状态」对账
     * （{@code record.State == 重放末态} · {@code record.LastOperationID >= payload.OperationID}
     * · {@code record.UserID == uid}）⇒ 对不上就回答「不可用」。
     *
     * <p>⚠️ <b>注意：不带 {@code state = 1} 也不带 {@code delete_at IS NULL}</b> ——
     * B 侧这条查询<b>没有</b>这两个过滤条件（要拿的是「当前行的真实状态」，
     * 含已取消的赞），照抄 B 侧才是行为层一致。
     *
     * <p>⚠️ {@code LIMIT 1}：B 侧用 {@code .Limit(1).Find(&record)} +
     * {@code RowsAffected != 1} 判「恰好一行」⇒ 本方法返回 {@code null} 表示「没有行」，
     * 「多于一行」由调用方按 B 侧的语义另行判定（{@code (user_id,target_type,target_id)}
     * 上有唯一约束，正常只会有一行）。
     *
     * <p>🔴 <b>与 {@link #selectForUpdateIncludingDeleted} 的区别：没有 {@code FOR UPDATE}</b>。
     * B 侧的权威读是<b>无锁只读</b>（{@code .Limit(1).Find(&record)}，无 {@code clause.Locking}）
     * ⇒ 给权威读加行锁会把「DC 回查」变成一个会阻塞写链路的操作
     * ⇒ <b>两条 SQL 看起来几乎一样，但语义不同，不要合并</b>。
     */
    @Select("""
            SELECT id, user_id, target_type, target_id, author_id, state, last_operation_id,
                   created_at, updated_at, delete_at
            FROM like_record
            WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id = #{targetId}
            LIMIT 1
            """)
    LikeRecordEntity selectOneByUserAndTarget(@Param("userId") long userId,
                                               @Param("targetType") String targetType,
                                               @Param("targetId") String targetId);

    /**
     * 对应 B 侧 {@code GetTargetActiveLikeCount}：
     * {@code WHERE target_type = ? AND target_id = ? AND state = 1} 计数。
     *
     * <p>用于 {@code currentArticleLikeTotal}，与 Redis Hash 里的计数取 max。
     */
    @Select("""
            SELECT count(*) FROM like_record
            WHERE target_type = #{targetType} AND target_id = #{targetId} AND state = 1 AND delete_at IS NULL
            """)
    long countActiveLikeByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /**
     * 对应 B 侧 {@code GetBatchLikeCount}：
     * {@code SELECT target_id, state, count(1) ... WHERE target_type = ? AND target_id IN (?) AND state IN (1,2) GROUP BY target_id, state}。
     *
     * <p>⚠️ {@code state IN (1,2)} 而非 {@code = 1}：批量计数<b>同时</b>要赞和踩，
     * 调用方再按 {@code counts[1]} / {@code counts[2]} 取。
     */
    @Select("""
            <script>
            SELECT target_id, state, count(1) AS cnt FROM like_record
            WHERE target_type = #{targetType} AND target_id IN
            <foreach item="id" collection="targetIds" open="(" separator="," close=")">#{id}</foreach>
              AND state IN (1, 2) AND delete_at IS NULL
            GROUP BY target_id, state
            </script>
            """)
    List<StateCountRow> selectStateCountsBatch(@Param("targetType") String targetType,
                                                @Param("targetIds") List<String> targetIds);

    /**
     * 对应 B 侧 {@code GetUserBatchLikeState}：
     * {@code SELECT target_id, state ... WHERE user_id = ? AND target_type = ? AND target_id IN (?)}。
     *
     * <p>无 state 过滤 —— 该用户的记录至多一行（唯一键约束），返回值即状态。
     * <b>查不到的 id 在 B 侧取 map 零值 0 并写回 Redis 防穿透</b>，见 {@code domain/service/LikeDomainService}。
     */
    @Select("""
            <script>
            SELECT target_id, state FROM like_record
            WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id IN
            <foreach item="id" collection="targetIds" open="(" separator="," close=")">#{id}</foreach>
              AND delete_at IS NULL
            </script>
            """)
    List<TargetStateRow> selectUserStatesBatch(@Param("userId") long userId,
                                               @Param("targetType") String targetType,
                                               @Param("targetIds") List<String> targetIds);

    /**
     * 对应 B 侧 {@code GetUserLikeList}：
     * {@code WHERE user_id/target_type/state=1 [+ id < cursor] ORDER BY id DESC LIMIT n}。
     *
     * <p>⚠️ <b>cursor 是「打包后的 score」（{@code timestamp << 22}），不是 id</b>。
     * B 侧 logic 把同一个 cursor 同时当作 ZSet 的 maxScore 与 DB 的 {@code id < cursor} 上界，
     * 两套刻度并不一致。这是 B 侧的既有行为，<b>不修正</b> —— 改了会让翻页结果变化。
     */
    @Select("""
            <script>
            SELECT id, target_type, target_id, created_at FROM like_record
            WHERE user_id = #{userId} AND target_type = #{targetType} AND state = 1 AND delete_at IS NULL
            <if test="cursor != null and cursor > 0"> AND id &lt; #{cursor}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<UserLikeRow> selectUserLikeList(@Param("userId") long userId,
                                         @Param("targetType") String targetType,
                                         @Param("cursor") long cursor,
                                         @Param("limit") int limit);

    /**
     * 对应 B 侧 {@code GetTargetLikerList}：
     * {@code WHERE target_type/target_id/state=1 [+ id < cursor] ORDER BY id DESC LIMIT n}。
     *
     * <p>⚠️ cursor 刻度问题同 {@link #selectUserLikeList}，见其注释。
     */
    @Select("""
            <script>
            SELECT id, user_id, created_at FROM like_record
            WHERE target_type = #{targetType} AND target_id = #{targetId} AND state = 1 AND delete_at IS NULL
            <if test="cursor != null and cursor > 0"> AND id &lt; #{cursor}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<TargetLikerRow> selectTargetLikerList(@Param("targetType") String targetType,
                                               @Param("targetId") String targetId,
                                               @Param("cursor") long cursor,
                                               @Param("limit") int limit);

    // ==================== 写路径（对应 B 侧 applyLikeMessage） ====================

    /**
     * 对应 B 侧 {@code tx.Clauses(clause.OnConflict{DoNothing: true}).Create(&seed)} ——
     * 插入 {@code state=0} 的种子行；已存在则什么都不做。
     *
     * <p>返回影响行数：0 表示唯一键冲突（这行以前就存在），非 0 表示本次新建。
     */
    @Insert("""
            INSERT INTO like_record (user_id, target_type, target_id, author_id, state, last_operation_id, created_at, updated_at)
            VALUES (#{userId}, #{targetType}, #{targetId}, #{authorId}, 0, 0, now(), now())
            ON CONFLICT DO NOTHING
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = false)
    int insertSeedIfAbsent(@Param("userId") long userId,
                           @Param("targetType") String targetType,
                           @Param("targetId") String targetId,
                           @Param("authorId") long authorId);

    /**
     * 对应 B 侧 {@code tx.Unscoped().Clauses(clause.Locking{Strength: "UPDATE"}).Where(...).First(&current)}。
     *
     * <p>⚠️ <b>唯一一条不带 {@code delete_at IS NULL} 的查询</b>：B 侧用 {@code Unscoped()} 明确要读已软删的行，
     * 随后由 {@code if current.DeleteAt.Valid { return error }} 拒绝它。
     * 换成带过滤的查询会让软删行「查不到」，从而跳过那个错误分支、把软删行当成新行重建 —— 行为改变。
     *
     * <p>{@code FOR UPDATE} 是整条幂等链路（同一用户同一目标的并发消息串行化）的基石。
     *
     * @return 命中行；不存在返回 null（调用方须按「查不到」处理）
     */
    @Select("""
            SELECT id, user_id, target_type, target_id, author_id, state, last_operation_id,
                   created_at, updated_at, delete_at
            FROM like_record
            WHERE user_id = #{userId} AND target_type = #{targetType} AND target_id = #{targetId}
            FOR UPDATE
            """)
    LikeRecordEntity selectForUpdateIncludingDeleted(@Param("userId") long userId,
                                                     @Param("targetType") String targetType,
                                                     @Param("targetId") String targetId);

    /**
     * 对应 B 侧 {@code tx.Model(&current).Updates(map[string]any{"state":…, "last_operation_id":…, "author_id":…})}。
     *
     * <p>⚠️ B 侧修正错误断言 B 的结论（原注释写「Go 的 {@code Updates} 用 map 形式不会带上
     * {@code updated_at}」是<b>错的</b>）：真实链路（已读到消费点）——
     * <ol>
     *   <li>B 侧 struct 显式声明自动更新时间 ——
     *       {@code internal/model/data_model.go:19}
     *       {@code UpdatedAt time.Time `gorm:"autoUpdateTime;not null;index:idx_user_list,priority:2;…"`}；</li>
     *   <li>GORM 的 map 分支里<b>同样会追加</b>自动更新时间列 ——
     *       {@code gorm.io/gorm@v1.31.1/callbacks/update.go:201} {@code case map[string]interface{} …}
     *       到 {@code :236-243}：
     *       {@code if field.AutoUpdateTime > 0 && value[field.Name] == nil && value[field.DBName] == nil}
     *       ⇒ 往 SET 里 append {@code updated_at = NowFunc()}。</li>
     * </ol>
     * ⇒ B 侧<b>每次状态变更都会推进 {@code updated_at}</b>；若 C 侧不推进，
     * 会让 {@code idx_user_list(user_id, updated_at)} 的<b>索引内容两侧不同</b>
     * （该索引正是 {@code data_model.go:19} 里 priority:2 指定的那一列，DRIFT-LIKE-008）。
     * ⇒ 本 SQL 已改为与 B 侧一致地推进 {@code updated_at}。
     */
    @Update("""
            UPDATE like_record
            SET state = #{state},
                last_operation_id = #{lastOperationId},
                author_id = #{authorId},
                updated_at = now()
            WHERE id = #{id}
            """)
    int updateState(@Param("id") long id,
                    @Param("state") int state,
                    @Param("lastOperationId") long lastOperationId,
                    @Param("authorId") long authorId);

    // ==================== 结果行 ====================

    /** {@code selectStateCountsBatch} 的一行：某目标在某状态下有 N 条记录。 */
    class StateCountRow {
        /** target_id。 */
        public String targetId;
        /** 状态（1 点赞 / 2 点踩）。 */
        public int state;
        /** 该状态下的记录数。 */
        public long cnt;

        public String getTargetId() {
            return targetId;
        }

        public int getState() {
            return state;
        }

        public long getCnt() {
            return cnt;
        }
    }

    /** {@code selectUserStatesBatch} 的一行。 */
    class TargetStateRow {
        public String targetId;
        public int state;

        public String getTargetId() {
            return targetId;
        }

        public int getState() {
            return state;
        }
    }

    /** {@code selectUserLikeList} 的一行。 */
    class UserLikeRow {
        public Long id;
        public String targetType;
        public String targetId;
        public OffsetDateTime createdAt;

        public Long getId() {
            return id;
        }

        public String getTargetType() {
            return targetType;
        }

        public String getTargetId() {
            return targetId;
        }

        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }
    }

    /** {@code selectTargetLikerList} 的一行。 */
    class TargetLikerRow {
        public Long id;
        public long userId;
        public OffsetDateTime createdAt;

        public Long getId() {
            return id;
        }

        public long getUserId() {
            return userId;
        }

        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }
    }
}
