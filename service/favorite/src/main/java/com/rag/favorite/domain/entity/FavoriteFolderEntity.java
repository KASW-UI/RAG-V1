package com.rag.favorite.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>收藏夹 ORM 实体</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/domain/FavoriteFolderEntity.java}
 * （B 侧 :17-101）与 Go {@code service/favorite/rpc/internal/model/data_model.go:5-16} 的
 * {@code FavoriteFolder}。
 *
 * <p>表结构：{@code V1__create_favorite_folder.sql}（见
 * {@code service/favorite/src/main/resources/db/migration/}）。
 *
 * <p><b>{@code id} 是代理键，业务从不使用</b>：B 侧 {@code Id uint64 `gorm:"primaryKey"`}
 * 是 GORM 的自增主键，真正的业务 ID 是 {@code folder_id}（由 snowflake 生成）。
 * ⇒ 两侧都<b>不</b>允许把 {@code id} 当成对外标识。
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.domain.FavoriteFolderEntity}
 * ⇒ C 侧 {@code com.rag.favorite.domain.entity.FavoriteFolderEntity}。
 *
 * <h3>⚠️ {@code createTime} 用 {@link OffsetDateTime} 而不是 {@code LocalDateTime}</h3>
 * <p>列是 {@code timestamptz}，B 侧 DSN 显式带 {@code TimeZone=Asia/Shanghai}（db.go:21），
 * 而 B 侧 {@code toProtoFolder} 用的是 {@code CreateTime.UnixMilli()}（helper.go:69）
 * —— 那是<b>绝对时刻</b>。若 Java 侧按无时区类型读，时区配置错误会整体平移返回值。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅「样板代码由 Lombok 生成」——
 *       生成物与 B 侧手写版<b>逐字等价</b>（展开见下）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 展开块与 B 侧手写代码逐条一致
 *       （字段名、类型、可见性、getter/setter 形状）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/.../domain/FavoriteFolderEntity.java:17-101}。</li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * @TableName("favorite_folder")
 * public class FavoriteFolderEntity {
 *     @TableId(type = IdType.AUTO)
 *     private Long id;
 *     private Long folderId;
 *     private Long userId;
 *     private String name;
 *     private OffsetDateTime createTime;
 *     private OffsetDateTime updateTime;
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     public Long getFolderId() { return folderId; }
 *     public void setFolderId(Long folderId) { this.folderId = folderId; }
 *     public Long getUserId() { return userId; }
 *     public void setUserId(Long userId) { this.userId = userId; }
 *     public String getName() { return name; }
 *     public void setName(String name) { this.name = name; }
 *     public OffsetDateTime getCreateTime() { return createTime; }
 *     public void setCreateTime(OffsetDateTime createTime) { this.createTime = createTime; }
 *     public OffsetDateTime getUpdateTime() { return updateTime; }
 *     public void setUpdateTime(OffsetDateTime updateTime) { this.updateTime = updateTime; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("favorite_folder")
public class FavoriteFolderEntity {

    /** Go {@code Id uint64 `gorm:"primaryKey"`} —— 自增代理键，bigserial。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Go {@code FolderId int64 `gorm:"column:folder_id;uniqueIndex;not null"`}。 */
    private Long folderId;

    /** Go {@code UserId int64 `gorm:"column:user_id;not null;index;uniqueIndex:uk_user_folder_name"`}。 */
    private Long userId;

    /** Go {@code Name string `gorm:"column:name;type:varchar(100);not null;…"`}。 */
    private String name;

    /** Go {@code CreateTime time.Time `gorm:"column:create_time;autoCreateTime"`}。 */
    private OffsetDateTime createTime;

    /** Go {@code UpdateTime time.Time `gorm:"column:update_time;autoUpdateTime"`}。 */
    private OffsetDateTime updateTime;
}
