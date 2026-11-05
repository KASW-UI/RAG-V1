package com.rag.favorite.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>收藏条目 ORM 实体</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/domain/FavoriteItemEntity.java}
 * （B 侧 :19-140）与 Go {@code service/favorite/rpc/internal/model/data_model.go:18-35} 的
 * {@code FavoriteItem}。
 *
 * <p>表结构：{@code V2__create_favorite_item.sql}（见
 * {@code service/favorite/src/main/resources/db/migration/}）。
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.domain.FavoriteItemEntity}
 * ⇒ C 侧 {@code com.rag.favorite.domain.entity.FavoriteItemEntity}。
 *
 * <h2>🔴 本表有三条「不能照直觉」的规则</h2>
 * <ol>
 *   <li><b>{@code targetRevision} 必须可空</b>（{@code String} 不用
 *       {@code Optional}）。B 侧是 {@code TargetRevision *string}，注释（B 侧 :22-26）明写
 *       「Nil means the source had no provable published revision when this favorite
 *        was created. In particular, do not backfill legacy rows.」
 *       ⇒ <b>绝不能</b>用 {@code ""} 代替 null：那会让
 *       {@code sameFavoriteRevision}（favorite_authority.go:96-101）与 legacy 标记比对
 *       把「没有修订」误判成「有一个空修订」。</li>
 *   <li><b>没有 {@code updateTime}</b>。收藏条目不可变；更新语义只存在于
 *       {@code UpdateFolderNameByFolderId}（收藏夹名）。</li>
 *   <li><b>{@code targetType} 存的是已小写化的值</b>（B 侧 helper.go:60-62）
 *       ⇒ 唯一键 {@code uk_folder_target} 建在规范化之后的值上。</li>
 * </ol>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅「样板代码由 Lombok 生成」——
 *       生成物与 B 侧手写版<b>逐字等价</b>（展开见下）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 展开块与 B 侧手写代码逐条一致
 *       （字段名、类型、可见性、getter/setter 形状）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/.../domain/FavoriteItemEntity.java:19-140}。</li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * @TableName("favorite_item")
 * public class FavoriteItemEntity {
 *     @TableId(type = IdType.AUTO)
 *     private Long id;
 *     private Long favoriteId;
 *     private Long folderId;
 *     private Long userId;
 *     private String targetId;
 *     private String targetType;
 *     private String targetRevision;
 *     private String title;
 *     private String cover;
 *     private OffsetDateTime createTime;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public Long getFavoriteId() { return favoriteId; }
 *     public void setFavoriteId(Long favoriteId) { this.favoriteId = favoriteId; }
 *     public Long getFolderId() { return folderId; }
 *     public void setFolderId(Long folderId) { this.folderId = folderId; }
 *     public Long getUserId() { return userId; }
 *     public void setUserId(Long userId) { this.userId = userId; }
 *     public String getTargetId() { return targetId; }
 *     public void setTargetId(String targetId) { this.targetId = targetId; }
 *     public String getTargetType() { return targetType; }
 *     public void setTargetType(String targetType) { this.targetType = targetType; }
 *     public String getTargetRevision() { return targetRevision; }
 *     public void setTargetRevision(String targetRevision) { this.targetRevision = targetRevision; }
 *     public String getTitle() { return title; }
 *     public void setTitle(String title) { this.title = title; }
 *     public String getCover() { return cover; }
 *     public void setCover(String cover) { this.cover = cover; }
 *     public OffsetDateTime getCreateTime() { return createTime; }
 *     public void setCreateTime(OffsetDateTime createTime) { this.createTime = createTime; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("favorite_item")
public class FavoriteItemEntity {

    /** Go {@code Id uint64 `gorm:"primaryKey"`} —— 自增代理键，bigserial。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Go {@code FavoriteId int64 `gorm:"column:favorite_id;uniqueIndex;not null"`}。 */
    private Long favoriteId;

    /** Go {@code FolderId int64 `gorm:"column:folder_id;not null;index;…uk_folder_target"`}。 */
    private Long folderId;

    /** Go {@code UserId int64 `gorm:"column:user_id;not null;index"`}。 */
    private Long userId;

    /**
     * Go {@code TargetId string `gorm:"column:target_id;type:varchar(255);not null;…"}。
     *
     * <p>⚠️ <b>是字符串不是 long</b>：目标可以是 article 雪花 ID（&gt;2^53）也可以是
     * 非数字形态。B 侧 db.go:43-63 那段 {@code migrateFavoriteTargetIDToString} 就是历史包袱。
     */
    private String targetId;

    /** Go {@code TargetType string `gorm:"column:target_type;type:varchar(50);not null;…"}。 */
    private String targetType;

    /**
     * Go {@code TargetRevision *string `gorm:"column:target_revision;type:varchar(96)`}。
     *
     * <p><b>null 有业务含义</b>，见类注释第 ① 条。
     */
    private String targetRevision;

    /** Go {@code Title string `gorm:"column:title;type:varchar(255)"`}（可空）。 */
    private String title;

    /** Go {@code Cover string `gorm:"column:cover;type:varchar(500)"`}（可空）。 */
    private String cover;

    /** Go {@code CreateTime time.Time `gorm:"column:create_time;autoCreateTime"`}。 */
    private OffsetDateTime createTime;
}
