package com.rag.favorite.mapper;

import com.rag.favorite.domain.entity.FavoriteItemEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>收藏条目数据访问</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/mapper/FavoriteItemMapper.java}
 * 的全部 SQL（逐条照抄，SQL 文本<b>一字未改</b>）。
 *
 * <p>对应 Go {@code service/favorite/rpc/internal/model/favorite.go:82-118}
 * 的 4 个 Find 方法，以及 {@code favorite_fact.go:283,321,330,373} 的事务内
 * INSERT / DELETE。
 *
 * <p>⚠️ AGENTS.md §4.1 已锁定的决策：Go 的单个 {@code FavoriteModel} 按实体拆成多个 Mapper。
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.mapper.FavoriteItemMapper}
 * ⇒ C 侧 {@code com.rag.favorite.mapper.FavoriteItemMapper}。
 *
 * <p><b>「查不到」的约定</b>：单条查询返回 {@code null}，由
 * {@code FavoriteDomainService} 统一翻译成 {@code NotFound} / {@code AlreadyExists}
 * （B 侧是 {@code model.ErrorNotFound} 哨兵在各 logic 分派）。
 *
 * <p>⚠️ <b>全部无 LIMIT</b>（B 侧 {@code FindFavoritesByFolderId} 也没有分页，
 * internal/model/favorite.go:82-92）⇒ 这里不加 {@code LIMIT}。
 */
@Mapper
public interface FavoriteItemMapper {

    /**
     * 对应 B 侧 {@code FindFavoritesByFolderId}（internal/model/favorite.go:82-92）：
     * <pre>Where("folder_id = ?", folderId).Order("create_time desc")</pre>
     *
     * <p>🔴 {@code ORDER BY create_time DESC} 是 L2 语义（条目列表展示顺序）。
     */
    @Select("""
            SELECT id, favorite_id, folder_id, user_id, target_id, target_type,
                   target_revision, title, cover, create_time
            FROM favorite_item
            WHERE folder_id = #{folderId}
            ORDER BY create_time DESC
            """)
    List<FavoriteItemEntity> findByFolderId(@Param("folderId") long folderId);

    /** 对应 B 侧 {@code FindFavoriteByFavoriteId}（internal/model/favorite.go:94-104）。 */
    @Select("""
            SELECT id, favorite_id, folder_id, user_id, target_id, target_type,
                   target_revision, title, cover, create_time
            FROM favorite_item
            WHERE favorite_id = #{favoriteId}
            LIMIT 1
            """)
    FavoriteItemEntity findByFavoriteId(@Param("favoriteId") long favoriteId);

    /**
     * 对应 B 侧 {@code FindFavoriteByFolderTarget}（internal/model/favorite.go:106-118）：
     * {@code Where("folder_id = ? AND target_id = ? AND target_type = ?")}。
     *
     * <p>🔴 三个条件缺一不可：{@code target_type} 参与 {@code uk_folder_target}
     * 唯一键，所以「同一文件夹里 article 123 与 post 123 是两条不同的收藏」。
     */
    @Select("""
            SELECT id, favorite_id, folder_id, user_id, target_id, target_type,
                   target_revision, title, cover, create_time
            FROM favorite_item
            WHERE folder_id = #{folderId} AND target_id = #{targetId} AND target_type = #{targetType}
            LIMIT 1
            """)
    FavoriteItemEntity findByFolderTarget(@Param("folderId") long folderId,
                                          @Param("targetId") String targetId,
                                          @Param("targetType") String targetType);

    /**
     * 对应 B 侧 {@code InsertFavorite} 事务内的 {@code tx.Create(item)}（favorite_fact.go:283）。
     *
     * <p>🔴 {@code target_revision} 写的是 <b>可为 null</b> 的原值
     * （{@code #{targetRevision}}，MyBatis 对 {@code null} 走
     * {@code setNull(..., Types.VARCHAR)}）。绝不能写成 {@code COALESCE(#{targetRevision}, '')} ——
     * 那是把「无修订」变成「有一个空修订」，见 {@code FavoriteItemEntity} 的类注释第 ① 条。
     *
     * <p>⚠️ 23505（唯一冲突）在此抛出，由 {@code FavoriteRpcSupport.isUniqueViolation}
     * 识别并翻译成 {@code AlreadyExists/5302}（create_favorite_logic.go:157-161）。
     *
     * <p>🔴 <b>2026-10-26 第二十三轮修</b>：本方法<b>原来不绑</b> {@code create_time}
     * ⇒ DDL（{@code V2__create_favorite_item.sql:44}）该列<b>没有 DEFAULT</b> ⇒ 落库 <b>NULL</b>
     * ⇒ 对外 {@code created_at} = {@code 0}（{@code FavoriteRpcSupport.unixMillis}：{@code null → 0L}）
     * ⇒ <b>L1 可观察的漂移</b>。
     *
     * <p><b>B 侧出处（消费点）</b>：struct tag {@code internal/model/data_model.go:30}
     * （{@code create_time} 带 {@code autoCreateTime}）⇒ 赋值点
     * {@code gorm.io/gorm@v1.31.1/callbacks/create.go:302-305}（零值时填 {@code curTime}）
     * ⇒ <b>值来自 B 侧进程时钟</b>。
     *
     * <p><b>语义等价论证</b>：本实现绑 <b>Java 进程时钟</b>（{@code Instant.now(clock)}）
     * ⇒ 与 B 侧同为「应用进程时钟」⇒ 同源；⚠️ 不用 PG 的 {@code DEFAULT now()}（另一个时钟源，
     * 且该列<b>没有</b>这个 DEFAULT）。
     *
     * <p>⚠️ {@code favorite_item} <b>没有</b> {@code update_time}（B 侧同样没有，
     * {@code data_model.go:30} 只声明 {@code create_time}）⇒ 本方法<b>不写</b>该列。
     *
     * <p><b>L4 表达层差异</b>：B 侧用全限定名 {@code java.time.OffsetDateTime}，
     * C 侧改为 import 后简写 ⇒ <b>类型逐字相同</b>，无语义差异。
     */
    @Insert("""
            INSERT INTO favorite_item
                (favorite_id, folder_id, user_id, target_id, target_type, target_revision, title, cover,
                 create_time)
            VALUES (#{favoriteId}, #{folderId}, #{userId}, #{targetId}, #{targetType},
                    #{targetRevision}, #{title}, #{cover},
                    #{createTime})
            """)
    int insertItem(@Param("favoriteId") long favoriteId,
                   @Param("folderId") long folderId,
                   @Param("userId") long userId,
                   @Param("targetId") String targetId,
                   @Param("targetType") String targetType,
                   @Param("targetRevision") String targetRevision,
                   @Param("title") String title,
                   @Param("cover") String cover,
                   @Param("createTime") OffsetDateTime createTime);

    /**
     * 对应 B 侧 {@code DeleteFavoriteByFavoriteId} 事务内的
     * {@code tx.Where("favorite_id = ?", favoriteID).Delete(&FavoriteItem{})}（favorite_fact.go:330）。
     *
     * <p>⚠️ <b>物理删，不是软删</b>：删除后业务行消失，唯一的墓碑是 v2 retract 事实
     * （{@code favoriteAssertionSchema} 的 legacy 分支则<b>不留</b>事实，只删行）。
     */
    @Delete("DELETE FROM favorite_item WHERE favorite_id = #{favoriteId}")
    int deleteByFavoriteId(@Param("favoriteId") long favoriteId);

    /**
     * 对应 B 侧 {@code DeleteFolderCascade} 事务内的
     * {@code tx.Where("folder_id = ?", folderID).Delete(&FavoriteItem{})}（favorite_fact.go:373）。
     *
     * <p>必须在「逐条写入 retract 事实」之后、「删 folder」之前调用。
     */
    @Delete("DELETE FROM favorite_item WHERE folder_id = #{folderId}")
    int deleteByFolderId(@Param("folderId") long folderId);

    /**
     * 🔴 对应 B 侧 {@code DeleteFolderCascade} 里的
     * <pre>
     * tx.Clauses(clause.Locking{Strength: "UPDATE"}).
     *    Where("folder_id = ?", folderID).Order("favorite_id").Find(&items)
     * </pre>
     * （favorite_fact.go:353-355）。
     *
     * <p>三个要素一个都不能丢：
     * <ul>
     *   <li>{@code FOR UPDATE} —— 逐行悲观锁，保证同一收藏夹的并发删除不会
     *       各自算出不同的 retract 集合；</li>
     *   <li>{@code ORDER BY favorite_id} —— <b>确定性</b>的事件生成顺序。
     *       B 侧显式排序就是为了让同一批事件的 {@code created_at}/{@code event_id}
     *       序列可复现；去掉 ORDER BY 就变成未定义的。</li>
     *   <li><b>无 LIMIT</b> —— 一次必须拿全，否则 retract 会缺行。</li>
     * </ul>
     */
    @Select("""
            SELECT id, favorite_id, folder_id, user_id, target_id, target_type,
                   target_revision, title, cover, create_time
            FROM favorite_item
            WHERE folder_id = #{folderId}
            ORDER BY favorite_id
            FOR UPDATE
            """)
    List<FavoriteItemEntity> lockAllByFolderIdOrdered(@Param("folderId") long folderId);

    /**
     * 对应 B 侧 {@code DeleteFavoriteByFavoriteId} 里的
     * <pre>tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("favorite_id = ?", favoriteID).First(&item)</pre>
     * （favorite_fact.go:306-307）。返回 {@code null} ⇒ B 侧的
     * {@code gorm.ErrRecordNotFound} ⇒ 归一化为 {@code model.ErrorNotFound}。
     */
    @Select("""
            SELECT id, favorite_id, folder_id, user_id, target_id, target_type,
                   target_revision, title, cover, create_time
            FROM favorite_item
            WHERE favorite_id = #{favoriteId}
            LIMIT 1
            FOR UPDATE
            """)
    FavoriteItemEntity lockByFavoriteId(@Param("favoriteId") long favoriteId);
}
