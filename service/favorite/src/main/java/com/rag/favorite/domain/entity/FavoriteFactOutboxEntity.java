package com.rag.favorite.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.common.persistence.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>收藏事实发件箱 ORM 实体</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/domain/FavoriteFactOutboxEntity.java}
 * （B 侧 :24-176）与 Go {@code service/favorite/rpc/internal/model/favorite_fact.go:60-76} 的
 * {@code FavoriteFactOutbox}。
 *
 * <p>表结构：{@code V3__create_favorite_fact_outbox.sql} + {@code V5__add_favorite_fact_delivery.sql}
 * + {@code V6__align_favorite_schema_with_go.sql}（见
 * {@code service/favorite/src/main/resources/db/migration/}）。
 *
 * <p><b>L4 落点变化（D7 包压平 + D3 共享层收编）</b>：
 * <ul>
 *   <li>B 侧 {@code com.rag.favorite.favorite.rpc.domain.FavoriteFactOutboxEntity}
 *       ⇒ C 侧 {@code com.rag.favorite.domain.entity.FavoriteFactOutboxEntity}；</li>
 *   <li>B 侧域内副本 {@code com.rag.favorite.favorite.rpc.mapper.JsonbTypeHandler}
 *       ⇒ C 侧共享层 {@code com.rag.common.persistence.JsonbTypeHandler}（D3）
 *       ⇒ ⚠️ <b>favorite 域不建此文件</b>（域内 {@code mapper/} 包只有 4 个 Mapper）。</li>
 * </ul>
 *
 * <h2>🔴 {@link #payload} 刻意保持 {@code String}，不映射成 POJO</h2>
 *
 * <p>B 侧 {@code validFavoriteDelivery}（favorite_delivery.go:100）与
 * {@code authoritativeFavoriteRow}（favorite_authority.go:160）都对<b>原始字节</b>做
 * {@code favoriteJCSHash([]byte(row.Payload))}，并与 DC 回执里的 {@code input_hash} 逐字比对。
 * 只有「以字符串原样存取」才能保证
 * <ul>
 *   <li>投递时发出去的字节（{@code json.RawMessage(row.Payload)}），
 *   <li>权威读时重新哈希的字节（{@code row.Payload}），
 *   <li>DC 侧算 hash 时的字节
 * </ul>
 * <b>三者来自同一份内容</b>。
 *
 * <p>若这里走 POJO 往返：JCS 会规范化 key 顺序，但 <b>JCS 不会补回被 Jackson 省略的 null 字段</b>
 * —— Go 的 {@code json.Marshal} 对 {@code *string} nil 会写 {@code "target_revision":null}，
 * 而 Jackson 若配了 {@code NON_NULL} 就会整字段消失 ⇒ JCS 结果不同 ⇒ hash 不同 ⇒
 * <b>权威读永久失败</b>。先例：B 侧 {@code like-rpc/domain/LikeDomainFactOutboxEntity} 的同款论证。
 *
 * <p>{@code autoResultMap = true} 是 {@link JsonbTypeHandler} 生效的前提
 * （没有它，{@code @TableField(typeHandler=…)} 在结果映射时会被忽略）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅「样板代码由 Lombok 生成」——
 *       生成物与 B 侧手写版<b>逐字等价</b>（展开见下）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 展开块与 B 侧手写代码逐条一致
 *       （字段名、类型、可见性、getter/setter 形状）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/.../domain/FavoriteFactOutboxEntity.java:24-176}。</li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * @TableName(value = "favorite_fact_outbox", autoResultMap = true)
 * public class FavoriteFactOutboxEntity {
 *     @TableId(type = IdType.INPUT)
 *     private String eventId;
 *     private Long favoriteId;
 *     private Long aggregateVersion;
 *     @TableField(typeHandler = JsonbTypeHandler.class)
 *     private String payload;
 *     private int status;
 *     private int retryCount;
 *     private String technicalReceiptId;
 *     private String technicalInputHash;
 *     private long technicalOffset;
 *     private OffsetDateTime technicalReceivedAt;
 *     private OffsetDateTime deliveredAt;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public String getEventId() { return eventId; }
 *     public void setEventId(String eventId) { this.eventId = eventId; }
 *     public Long getFavoriteId() { return favoriteId; }
 *     public void setFavoriteId(Long favoriteId) { this.favoriteId = favoriteId; }
 *     public Long getAggregateVersion() { return aggregateVersion; }
 *     public void setAggregateVersion(Long aggregateVersion) { this.aggregateVersion = aggregateVersion; }
 *     public String getPayload() { return payload; }
 *     public void setPayload(String payload) { this.payload = payload; }
 *     public int getStatus() { return status; }
 *     public void setStatus(int status) { this.status = status; }
 *     public int getRetryCount() { return retryCount; }
 *     public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
 *     public String getTechnicalReceiptId() { return technicalReceiptId; }
 *     public void setTechnicalReceiptId(String technicalReceiptId) { this.technicalReceiptId = technicalReceiptId; }
 *     public String getTechnicalInputHash() { return technicalInputHash; }
 *     public void setTechnicalInputHash(String technicalInputHash) { this.technicalInputHash = technicalInputHash; }
 *     public long getTechnicalOffset() { return technicalOffset; }
 *     public void setTechnicalOffset(long technicalOffset) { this.technicalOffset = technicalOffset; }
 *     public OffsetDateTime getTechnicalReceivedAt() { return technicalReceivedAt; }
 *     public void setTechnicalReceivedAt(OffsetDateTime technicalReceivedAt) { this.technicalReceivedAt = technicalReceivedAt; }
 *     public OffsetDateTime getDeliveredAt() { return deliveredAt; }
 *     public void setDeliveredAt(OffsetDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName(value = "favorite_fact_outbox", autoResultMap = true)
public class FavoriteFactOutboxEntity {

    /**
     * Go {@code EventID string `gorm:"primaryKey;type:varchar(128)"`}。
     *
     * <p>⚠️ 必须 {@code IdType.INPUT}：event_id 是<b>业务生成</b>的
     * {@code fmt.Sprintf("favorite.%d.v%d", favoriteId, version)}（favorite_fact.go:141），
     * 绝不能让 MyBatis-Plus 用雪花算法覆盖。
     */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** Go {@code FavoriteID int64 `gorm:"not null;uniqueIndex:uk_favorite_fact_version"`}。 */
    private Long favoriteId;

    /** Go {@code AggregateVersion int64 `gorm:"not null;uniqueIndex:uk_favorite_fact_version"`}：1=assert，2=retract。 */
    private Long aggregateVersion;

    /** 见类注释「payload 刻意保持 String」。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String payload;

    /** Go {@code Status int32 `gorm:"type:smallint;not null;default:0;index"`}：0/1/2/3。 */
    private int status;

    /** Go {@code RetryCount int32 `gorm:"not null;default:0"`}。 */
    private int retryCount;

    /** Go {@code TechnicalReceiptID string `gorm:"type:varchar(128)"`}（可空）。 */
    private String technicalReceiptId;

    /** Go {@code TechnicalInputHash string `gorm:"type:char(64)"`}（可空；JCS+SHA256 hex）。 */
    private String technicalInputHash;

    /** Go {@code TechnicalOffset int64 `gorm:"not null;default:0"`}：DC 的 offset，≥1 才算已接受。 */
    private long technicalOffset;

    /** Go {@code TechnicalReceivedAt *time.Time}（可空）。 */
    private OffsetDateTime technicalReceivedAt;

    /** Go {@code DeliveredAt *time.Time}（可空）。 */
    private OffsetDateTime deliveredAt;

    /** Go {@code CreatedAt time.Time `gorm:"autoCreateTime;not null"`} —— 取件排序键之一。 */
    private OffsetDateTime createdAt;

    /** Go {@code UpdatedAt time.Time `gorm:"autoUpdateTime;not null"`}。 */
    private OffsetDateTime updatedAt;
}
