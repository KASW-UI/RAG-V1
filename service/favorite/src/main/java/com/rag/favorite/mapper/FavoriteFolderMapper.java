package com.rag.favorite.mapper;

import com.rag.favorite.domain.entity.FavoriteFolderEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>收藏夹数据访问</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/mapper/FavoriteFolderMapper.java}
 * 的全部 SQL（逐条照抄，SQL 文本<b>一字未改</b>）。
 *
 * <p>对应 Go {@code service/favorite/rpc/internal/model/favorite.go:33-80}
 * 的 5 个方法（{@code InsertFolder} / {@code FindFoldersByUserId} / {@code FindFolderByFolderId}
 * / {@code FindFolderByUserIdAndName} / {@code UpdateFolderNameByFolderId}）。
 *
 * <p>⚠️ AGENTS.md §4.1 已锁定的决策：Go 的单个 {@code FavoriteModel} 按实体拆成多个 Mapper。
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.mapper.FavoriteFolderMapper}
 * ⇒ C 侧 {@code com.rag.favorite.mapper.FavoriteFolderMapper}。
 *
 * <p><b>「查不到」的约定</b>：B 侧所有 Find* 方法把 {@code gorm.ErrRecordNotFound}
 * 归一化成 {@code model.ErrorNotFound}（internal/model/favorite.go:55,69,101,115），由 logic 决定
 * 翻译成哪个业务码。⇒ 本 Mapper 的单条查询<b>返回 {@code null}</b>，
 * 由 {@code FavoriteDomainService} 统一翻译（避免每处各写一遍判空）。
 *
 * <p>⚠️ <b>全部无 LIMIT</b>（B 侧 internal/model/favorite.go:37-47 的 {@code FindFoldersByUserId}
 * 也没有分页）⇒ 这里<b>不加</b> {@code LIMIT}。加了就改变了可观察行为。
 */
@Mapper
public interface FavoriteFolderMapper {

    /**
     * 对应 B 侧 {@code InsertFolder}（internal/model/favorite.go:33-35）：
     * {@code m.conn.WithContext(ctx).Create(folder).Error}。
     *
     * <p>{@code id} 是 bigserial 自增代理键，<b>不写入</b>（B 侧的 struct 里
     * {@code Id uint64} 零值 0 也是由 PG 分配）。
     *
     * <p>🔴 <b>2026-10-26 第二十三轮修</b>：本方法<b>原来不绑</b> {@code create_time} /
     * {@code update_time} ⇒ DDL（{@code V1__create_favorite_folder.sql:24-26}）里这两列
     * <b>没有 DEFAULT</b> ⇒ 落库 <b>NULL</b> ⇒ 对外 {@code created_at} / {@code updated_at}
     * 变成 {@code 0}（{@code FavoriteRpcSupport.unixMillis}：{@code null → 0L}）⇒
     * <b>L1 可观察的漂移</b>（B 侧是真实毫秒）。
     *
     * <p><b>B 侧出处（消费点，不是声明点）</b>：
     * <ul>
     *   <li>struct tag：{@code internal/model/data_model.go:10-11}
     *       （{@code create_time} / {@code update_time} 带 {@code autoCreateTime} / {@code autoUpdateTime}）</li>
     *   <li>赋值点：{@code gorm.io/gorm@v1.31.1/callbacks/create.go:246} —— {@code curTime := stmt.DB.NowFunc()}
     *       <b>整条 INSERT 只取一次</b> ⇒ 两个 auto-time 字段拿到<b>同一个时刻</b>
     *       ⇒ 本实现绑**同一个** {@code now}（{@code create_time} 与 {@code update_time} 相等）</li>
     *   <li>填充分支：{@code gorm.io/gorm@v1.31.1/callbacks/create.go:302-305}（字段零值时才填）</li>
     * </ul>
     *
     * <p><b>语义等价论证</b>：B 侧的值来自 <b>B 侧进程时钟</b>（{@code NowFunc} = {@code time.Now()}）
     * ⇒ 本实现绑 <b>Java 进程时钟</b>（{@code Instant.now(clock)}）⇒ <b>时钟源同侧</b>（应用进程，
     * <b>不是</b>数据库侧）⇒ 与 B 侧同源。
     * ⚠️ <b>不能</b>用 DDL 的 {@code DEFAULT now()}（PG 服务器时钟）⇒ 那是<b>另一个时钟源</b>
     * ⇒ 应用与数据库时钟偏移时会产生分歧 ⇒ 且 DDL 根本<b>没有</b>这个 DEFAULT。
     *
     * <p><b>L4 表达层差异</b>：B 侧用全限定名 {@code java.time.OffsetDateTime}，
     * C 侧改为 import 后简写 ⇒ <b>类型逐字相同</b>，无语义差异。
     */
    @Insert("""
            INSERT INTO favorite_folder (folder_id, user_id, name, create_time, update_time)
            VALUES (#{folderId}, #{userId}, #{name}, #{createTime}, #{updateTime})
            """)
    int insertFolder(@Param("folderId") long folderId,
                     @Param("userId") long userId,
                     @Param("name") String name,
                     @Param("createTime") OffsetDateTime createTime,
                     @Param("updateTime") OffsetDateTime updateTime);

    /**
     * 对应 B 侧 {@code FindFoldersByUserId}（internal/model/favorite.go:37-47）：
     * <pre>Where("user_id = ?", userId).Order("create_time desc")</pre>
     *
     * <p>🔴 <b>{@code ORDER BY create_time DESC} 是 L2 语义</b>：收藏夹列表的展示顺序。
     * 去掉它，列表顺序就变成未定义的（PG 不保证无 ORDER BY 的返回序）。
     */
    @Select("""
            SELECT id, folder_id, user_id, name, create_time, update_time
            FROM favorite_folder
            WHERE user_id = #{userId}
            ORDER BY create_time DESC
            """)
    List<FavoriteFolderEntity> findByUserId(@Param("userId") long userId);

    /** 对应 B 侧 {@code FindFolderByFolderId}（internal/model/favorite.go:49-59）：{@code Where("folder_id = ?").First()}。 */
    @Select("""
            SELECT id, folder_id, user_id, name, create_time, update_time
            FROM favorite_folder
            WHERE folder_id = #{folderId}
            LIMIT 1
            """)
    FavoriteFolderEntity findByFolderId(@Param("folderId") long folderId);

    /**
     * 🔴 对应 B 侧 {@code InsertFavorite}（favorite_fact.go:276-277）与
     * {@code DeleteFolderCascade}（favorite_fact.go:342-343）里的
     * <pre>tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("folder_id = ?", …).First(&amp;folder)</pre>
     *
     * <p>⚠️ <b>必须带 {@code FOR UPDATE}</b>：它是「同一收藏夹的并发创建 / 并发删除」的锁点。
     * 缺它的后果（B 侧注释 {@code favorite_fact.go:267-269} 强调的就是这一点）：
     * 两个并发 {@code CreateFavorite} 可能都通过事务外的归属校验，
     * 各自算出「文件夹里还没有这个目标」，最后靠唯一键让其中一个失败 ——
     * 那个失败是 <b>23505</b>，被翻译成 {@code AlreadyExists/5302}，
     * 看起来对，但中间态是两个事务都持有同一文件夹的写意向。
     */
    @Select("""
            SELECT id, folder_id, user_id, name, create_time, update_time
            FROM favorite_folder
            WHERE folder_id = #{folderId}
            LIMIT 1
            FOR UPDATE
            """)
    FavoriteFolderEntity findByFolderIdForUpdate(@Param("folderId") long folderId);

    /** 对应 B 侧 {@code FindFolderByUserIdAndName}（internal/model/favorite.go:61-73）。 */
    @Select("""
            SELECT id, folder_id, user_id, name, create_time, update_time
            FROM favorite_folder
            WHERE user_id = #{userId} AND name = #{name}
            LIMIT 1
            """)
    FavoriteFolderEntity findByUserIdAndName(@Param("userId") long userId,
                                             @Param("name") String name);

    /**
     * 🔴 对应 B 侧 {@code UpdateFolderNameByFolderId}（internal/model/favorite.go:75-80）。
     *
     * <p>🔴 <b>2026-10-26 第二十三轮修</b>：本方法<b>原来只写 name 一列</b>，
     * 且当时的 javadoc <b>自相矛盾</b>（前半句说「B 侧也只写 name」，后半句又说「GORM 会追加
     * autoUpdateTime」，而结论却是「刻意不碰 update_time」）⇒ <b>结论是错的</b>。
     *
     * <p><b>B 侧出处（消费点）</b>：{@code gorm.io/gorm@v1.31.1/callbacks/update.go:236-254}
     * —— map 形式的 {@code Update} 会<b>自动追加</b>所有 {@code AutoUpdateTime > 0} 的字段：
     * {@code now := stmt.DB.NowFunc()} ⇒ 追加 {@code update_time = now}。
     * ⇒ <b>B 侧实际写两列：name + update_time</b>。
     *
     * <p><b>语义等价论证</b>：本实现写 {@code name} + {@code update_time}（应用层时钟）
     * ⇒ 与 B 侧的两列写入<b>逐列一致</b>；值来自 <b>Java 进程时钟</b> vs B 侧的
     * <b>B 侧进程时钟</b> ⇒ 时钟源同侧。
     *
     * <p><b>为什么这是 L1 可观察的</b>：{@code GetFavoriteFolders} 把
     * {@code UpdateTime.UnixMilli()} 放进响应（{@code internal/logic/helper.go:70}）
     * ⇒ 不推进 {@code update_time} ⇒ 客户端看到的 {@code updated_at} <b>永远不变</b>
     * ⇒ 与 B 侧不一致（第二轮 SQL 层对拍实测：B 侧 {@code u=} 是新毫秒，C 侧是旧毫秒）。
     */
    @Update("""
            UPDATE favorite_folder
            SET name = #{name}, update_time = #{updateTime}
            WHERE folder_id = #{folderId}
            """)
    int updateNameByFolderId(@Param("folderId") long folderId,
                              @Param("name") String name,
                              @Param("updateTime") OffsetDateTime updateTime);

    /**
     * 🔴 对应 B 侧 {@code DeleteFolderCascade} 的最后一步（favorite_fact.go:376）：
     * {@code tx.Where("folder_id = ?", folderID).Delete(&FavoriteFolder{}).Error}。
     *
     * <p><b>在本事务内最后一步</b>，前面已经删完全部 {@code favorite_item}。
     * 顺序不可交换：先删 folder 会让 items 变成孤儿。
     */
    @Delete("DELETE FROM favorite_folder WHERE folder_id = #{folderId}")
    int deleteByFolderId(@Param("folderId") long folderId);
}
