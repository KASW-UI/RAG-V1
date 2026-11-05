package com.rag.favorite.mapper;

import com.rag.favorite.domain.entity.FavoriteLegacyFactMarkerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p><b>legacy 事实标记数据访问</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/mapper/FavoriteLegacyFactMarkerMapper.java}
 * 的 SQL（逐条照抄，SQL 文本<b>一字未改</b>）。
 *
 * <p>对应 Go {@code service/favorite/rpc/internal/model/favorite_fact.go:220-226}：
 * <pre>
 * var marker FavoriteLegacyFactMarker
 * if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
 *     Where("favorite_id = ?", item.FavoriteID).Take(&amp;marker).Error; err != nil {
 *     if errors.Is(err, gorm.ErrRecordNotFound) { return 0, false, ErrFavoriteFactMigrationBlocked }
 *     return 0, false, err
 * }
 * </pre>
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.mapper.FavoriteLegacyFactMarkerMapper}
 * ⇒ C 侧 {@code com.rag.favorite.mapper.FavoriteLegacyFactMarkerMapper}。
 *
 * <p><b>只服务 legacy 分支</b>：只有「这条收藏没有 v1 断言」时才会走到这里。
 * 新建收藏都有 v1 断言 ⇒ 一次都不会执行本 Mapper。
 */
@Mapper
public interface FavoriteLegacyFactMarkerMapper {

    /**
     * 🔴 对应 Go {@code favorite_fact.go:220-221}。
     *
     * <p><b>{@code FOR UPDATE} 是必需的</b>（Go 的 {@code Locking{Strength: "UPDATE"}}）：
     * 它与 {@code FavoriteFactOutboxMapper#lockAssertByFavoriteId} 一起构成
     * 「同一收藏的 assert 与 retract 不得并发」的锁点。
     *
     * <p>⚠️ <b>缺表与查不到是两件事</b>：
     * <ul>
     *   <li>返回 {@code null} = 查不到 ⇒ Go 侧是 {@code gorm.ErrRecordNotFound}
     *       ⇒ 翻译成 {@code ErrFavoriteFactMigrationBlocked}（:222-223）
     *       ⇒ logic 输出 {@code FailedPrecondition/5304}；</li>
     *   <li>表不存在 = PG 抛 {@code relation "favorite_legacy_fact_marker" does not exist}
     *       ⇒ 那<b>不是</b> NotFound ⇒ 走 :224-225 返回普通 DB 错误
     *       ⇒ logic 输出 {@code Internal/5008}。<b>错误码完全不同</b>。</li>
     * </ul>
     * 本实现用 {@code @Select} 表达第一种；表缺失时 MyBatis 会把
     * {@code BadSqlGrammarException} 抛出并自然冒泡成「普通 DB 错误」，
     * 与 Go 的分支结构一致 ⇒ {@code FavoriteSchemaGate} 会把缺表变成启动告警。
     */
    @Select("""
            SELECT favorite_id, user_id, folder_id, target_type, target_id, target_revision,
                   source_snapshot_sha256, approval_ref
            FROM favorite_legacy_fact_marker
            WHERE favorite_id = #{favoriteId}
            LIMIT 1
            FOR UPDATE
            """)
    FavoriteLegacyFactMarkerEntity lockByFavoriteId(@Param("favoriteId") long favoriteId);
}
