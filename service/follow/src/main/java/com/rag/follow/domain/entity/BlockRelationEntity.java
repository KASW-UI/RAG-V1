package com.rag.follow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>拉黑关系表 ORM 实体</b> —— 对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/domain/BlockRelationEntity.java:23-80}
 * ⇒ <b>逐条一致</b>（表名 / 主键策略 / 5 个字段的类型与顺序 / 10 个 accessor 的签名）。
 *
 * <p><b>B 出处（逐项）</b>：
 * <ul>
 *   <li>表名 {@code block_relation} —— B 侧 {@code BlockRelationEntity.java:22}；</li>
 *   <li>主键 {@code @TableId(type = IdType.AUTO)} —— B 侧 {@code :26-27}；</li>
 *   <li>字段 {@code userId / targetId / createTime / updateTime} —— B 侧 {@code :29-39}；</li>
 *   <li>手写 accessor 10 个 —— B 侧 {@code :41-79}（本类改由 Lombok 生成，原文见下方展开块）。</li>
 * </ul>
 *
 * <p><b>表结构</b>：{@code V2__create_block_relation.sql}（本域 {@code src/main/resources/db/migration/}）。
 *
 * <p><b>⚠️ 为什么与 {@link FollowRelationEntity} <b>不复用同一个类</b></b>：
 * 两张表结构同构，唯一差别在唯一索引名（B 侧分别是 {@code uniqueIndex:uk_follow_pair} /
 * {@code uniqueIndex:uk_block_pair}）—— B 侧也是两个 struct + 两个 {@code TableName()}；
 * 而一个 Java 类只能写一个 {@code @TableName} ⇒ 复用即丢表名。
 * 这正是「把 Go 的结构照搬进 Java」的典型错误，本类<b>刻意不复用</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>表达层</b>两处 ——
 *       ① <b>包路径压平</b>：B 侧 {@code com.rag.follow.follow.rpc.domain} ⇒ 本仓
 *       {@code com.rag.follow.domain.entity}（D7 域内单层包树 + D11 的 {@code domain/entity} 落点，
 *       见 {@code docs/File_Tree/follow.md}）；
 *       ② <b>样板 accessor 改由 Lombok 生成</b>（原手写代码原样写在下方展开块）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 展开块与 B 侧手写 accessor <b>逐条一致</b>
 *       （字段名 / 类型 / 可见性 / 返回类型 / 参数类型 / 顺序）；
 *       只用 {@code @Getter} + {@code @Setter}（<b>不用</b> {@code @Data}）⇒ 不新增
 *       equals/hashCode/toString ⇒ 字节码公开方法集与 B 侧手写版等价。</li>
 *   <li><b>B 出处</b>：见上（B 侧 {@code BlockRelationEntity.java:23-80}）。</li>
 * </ol>
 *
 * <p><b>主键 / 时间列 / 时间戳来源</b>的语义与 {@link FollowRelationEntity} <b>完全一致</b>
 * （{@link IdType#AUTO} 序列主键 · {@link OffsetDateTime} 对应 DDL 的 {@code TIMESTAMPTZ} ·
 * 时间戳由应用进程显式赋值而非框架填充 · 本表零 UPDATE ⇒ {@code update_time} 永不推进），
 * 详见该类注释。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class BlockRelationEntity {
 *     private Long id;
 *     private long userId;
 *     private long targetId;
 *     private OffsetDateTime createTime;
 *     private OffsetDateTime updateTime;
 *
 *     public Long getId() {
 *         return id;
 *     }
 *
 *     public void setId(Long id) {
 *         this.id = id;
 *     }
 *
 *     public long getUserId() {
 *         return userId;
 *     }
 *
 *     public void setUserId(long userId) {
 *         this.userId = userId;
 *     }
 *
 *     public long getTargetId() {
 *         return targetId;
 *     }
 *
 *     public void setTargetId(long targetId) {
 *         this.targetId = targetId;
 *     }
 *
 *     public OffsetDateTime getCreateTime() {
 *         return createTime;
 *     }
 *
 *     public void setCreateTime(OffsetDateTime createTime) {
 *         this.createTime = createTime;
 *     }
 *
 *     public OffsetDateTime getUpdateTime() {
 *         return updateTime;
 *     }
 *
 *     public void setUpdateTime(OffsetDateTime updateTime) {
 *         this.updateTime = updateTime;
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@TableName("block_relation")
public class BlockRelationEntity {

    /** 对应 Go {@code ID uint64 `gorm:"primaryKey"`} ⇒ BIGSERIAL。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应 Go {@code UserId int64 `gorm:"column:user_id;not null;index;uniqueIndex:uk_block_pair"`}（拉黑者）。 */
    private long userId;

    /** 对应 Go {@code TargetId int64 `gorm:"column:target_id;not null;index;uniqueIndex:uk_block_pair"`}（被拉黑者）。 */
    private long targetId;

    /** 对应 Go {@code CreateTime time.Time `gorm:"column:create_time;autoCreateTime`}。 */
    private OffsetDateTime createTime;

    /** 对应 Go {@code UpdateTime time.Time `gorm:"column:update_time;autoUpdateTime`}。 */
    private OffsetDateTime updateTime;
}
