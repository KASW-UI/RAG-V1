package com.rag.favorite.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * <p><b>legacy 事实标记 ORM 实体</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/domain/FavoriteLegacyFactMarkerEntity.java}
 * （B 侧 :12-131）与 Go {@code service/favorite/rpc/internal/model/favorite_fact.go:28-39} 的
 * {@code FavoriteLegacyFactMarker}。
 *
 * <p>表结构：{@code V4__create_favorite_legacy_fact_marker.sql}（见
 * {@code service/favorite/src/main/resources/db/migration/}）。
 *
 * <h2>🔴 这张表 AutoMigrate 从不创建，Java 侧也不能靠 ORM 自动建</h2>
 *
 * <p>B 侧 {@code db.go:33} 是
 * {@code AutoMigrate(&FavoriteFolder{}, &FavoriteItem{}, &FavoriteFactOutbox{})} ——
 * <b>只有三个实体</b>，{@code FavoriteLegacyFactMarker} 不在其中。
 * 漏建的后果不是「少个功能」，而是<b>错误码变了</b>：见 V4 的文件头说明。
 *
 * <h2>它的语义：唯一的人工「这笔旧数据确实合法」凭证</h2>
 *
 * <p>Go 注释（favorite_fact.go:25-27）：
 * <pre>
 * // FavoriteLegacyFactMarker is an explicit migration-owner approval for one
 * // pre-Outbox business row. Absence of an assert alone can never prove a row is
 * // legacy; these anchors must match the locked live row before a factless delete.
 * </pre>
 * ⇒ 「没有 assert」<b>不能</b>证明是旧数据（也可能是现代写失败）；
 * 只有「没有 assert <b>且</b>标记逐字段匹配活着的业务行」才允许无事实删除。
 *
 * <p>⚠️ 库里有 {@code BEFORE UPDATE OR DELETE} 的不可变触发器
 * ⇒ 一旦写入就<b>不能改、不能删</b>（V4）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅「样板代码由 Lombok 生成」——
 *       生成物与 B 侧手写版<b>逐字等价</b>（展开见下）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 展开块与 B 侧手写代码逐条一致
 *       （字段名、类型、可见性、getter/setter 形状）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/.../domain/FavoriteLegacyFactMarkerEntity.java:12-131}。</li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * @TableName("favorite_legacy_fact_marker")
 * public class FavoriteLegacyFactMarkerEntity {
 *     @TableId(type = IdType.INPUT)
 *     private Long favoriteId;
 *     private Long userId;
 *     private Long folderId;
 *     private String targetType;
 *     private String targetId;
 *     private String targetRevision;
 *     private String sourceSnapshotSha256;
 *     private String approvalRef;
 *
 *     public Long getFavoriteId() { return favoriteId; }
 *     public void setFavoriteId(Long favoriteId) { this.favoriteId = favoriteId; }
 *     public Long getUserId() { return userId; }
 *     public void setUserId(Long userId) { this.userId = userId; }
 *     public Long getFolderId() { return folderId; }
 *     public void setFolderId(Long folderId) { this.folderId = folderId; }
 *     public String getTargetType() { return targetType; }
 *     public void setTargetType(String targetType) { this.targetType = targetType; }
 *     public String getTargetId() { return targetId; }
 *     public void setTargetId(String targetId) { this.targetId = targetId; }
 *     public String getTargetRevision() { return targetRevision; }
 *     public void setTargetRevision(String targetRevision) { this.targetRevision = targetRevision; }
 *     public String getSourceSnapshotSha256() { return sourceSnapshotSha256; }
 *     public void setSourceSnapshotSha256(String sourceSnapshotSha256) { this.sourceSnapshotSha256 = sourceSnapshotSha256; }
 *     public String getApprovalRef() { return approvalRef; }
 *     public void setApprovalRef(String approvalRef) { this.approvalRef = approvalRef; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("favorite_legacy_fact_marker")
public class FavoriteLegacyFactMarkerEntity {

    /** Go {@code FavoriteID int64 `gorm:"column:favorite_id;primaryKey"`}。 */
    @TableId(type = IdType.INPUT)
    private Long favoriteId;

    /** Go {@code UserID int64 `gorm:"column:user_id"`}。 */
    private Long userId;

    /** Go {@code FolderID int64 `gorm:"column:folder_id"`}。 */
    private Long folderId;

    /** Go {@code TargetType string `gorm:"column:target_type"`}。 */
    private String targetType;

    /** Go {@code TargetID string `gorm:"column:target_id"`}。 */
    private String targetId;

    /**
     * Go {@code TargetRevision *string `gorm:"column:target_revision"`}。
     *
     * <p><b>可空有语义</b>：与活着的 {@code favorite_item.target_revision} 做
     * {@code sameFavoriteRevision} 比对（favorite_fact.go:230），
     * 「一侧有另一侧无」也算不匹配 ⇒ 阻断。
     */
    private String targetRevision;

    /**
     * Go {@code SourceSnapshotSHA256 string `gorm:"column:source_snapshot_sha256"`}。
     *
     * <p>对 {@code {favorite_id,user_id,folder_id,target_type,target_id,target_revision}}
     * 做 JCS + SHA-256 的结果（favorite_fact.go:44-58）。
     * ⚠️ <b>刻意不含 title / cover</b>：那两个字段可变，纳入哈希会让每次改名都要重批标记。
     */
    private String sourceSnapshotSha256;

    /**
     * Go {@code ApprovalRef string `gorm:"column:approval_ref"`}。
     *
     * <p>迁移负责人的人工审批引用；DDL 上有 {@code CHECK (approval_ref <> '')}，
     * 应用层也再判一次（favorite_fact.go:232 的 {@code marker.ApprovalRef == ""}）。
     */
    private String approvalRef;
}
