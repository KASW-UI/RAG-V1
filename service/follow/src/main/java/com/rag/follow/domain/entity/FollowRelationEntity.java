package com.rag.follow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>关注关系表 ORM 实体</b> —— 对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/domain/FollowRelationEntity.java:35-92}
 * ⇒ <b>逐条一致</b>（表名 / 主键策略 / 5 个字段的类型与顺序 / 10 个 accessor 的签名）。
 *
 * <p><b>B 出处（逐项）</b>：
 * <ul>
 *   <li>表名 {@code follow_relation} —— B 侧 {@code FollowRelationEntity.java:34}；</li>
 *   <li>主键 {@code @TableId(type = IdType.AUTO)} —— B 侧 {@code :38-39}；</li>
 *   <li>字段 {@code userId / targetId / createTime / updateTime} —— B 侧 {@code :41-51}；</li>
 *   <li>手写 accessor 10 个 —— B 侧 {@code :53-91}（本类改由 Lombok 生成，原文见下方展开块）。</li>
 * </ul>
 *
 * <p><b>表结构</b>：{@code V1__create_follow_relation.sql}（本域 {@code src/main/resources/db/migration/}）。
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
 *   <li><b>B 出处</b>：见上（B 侧 {@code FollowRelationEntity.java:35-92}）。</li>
 * </ol>
 *
 * <p><b>⚠️ 主键必须显式 {@link IdType#AUTO}</b>：B 侧 Go 的 {@code ID uint64 `gorm:"primaryKey"`}
 * 未显式关闭 autoIncrement，而 GORM 对整数主键<b>默认开启</b> ⇒ 产物是 {@code bigserial}
 * （见 {@code V1__create_follow_relation.sql} 的 {@code id BIGSERIAL PRIMARY KEY}）。
 * Java 侧因此用 {@link IdType#AUTO}，插入时让 PG 序列分配。
 * ⚠️ 若改成 {@code IdType.ASSIGN_ID}（像 users 域那样）就变成雪花 ID ——
 * 那是<b>新增行为</b>，且与 B 侧序列产生的主键空间不同。
 *
 * <p><b>⚠️ createTime / updateTime 由业务代码显式赋值，不由框架填充</b>：
 * B 侧 Go 的 {@code autoCreateTime} / {@code autoUpdateTime} 是 <b>GORM struct tag</b>，
 * GORM 在 INSERT 前把当时的时刻写进 struct 再落库 —— 时间戳<b>由应用进程产生</b>，
 * 不是 DB 的 {@code DEFAULT now()}。
 * ⇒ 本仓<b>不</b>引入 {@code MetaObjectHandler} 自动填充（那会多一层隐式全局行为），
 * 由 {@code FollowRelationMapper} 的调用点显式 set，语义一眼可见。
 * ⚠️ <b>本表零 UPDATE</b>（关注关系只有 INSERT / DELETE）⇒ {@code update_time} 写入后<b>永不推进</b>，
 * 这是 B 侧的实际行为，<b>不要</b>给它加 UPDATE fill（加了就是行为变化）。
 * ⚠️ 也<b>不要</b>给这两列加 {@code DEFAULT CURRENT_TIMESTAMP}：那会把时间来源从应用进程换成 DB，
 * 时区与时钟都跟着变（follow 域是多副本部署）。
 *
 * <p><b>⚠️ 时间类型用 {@link OffsetDateTime} 而非 {@code LocalDateTime}</b>：
 * DDL 是 {@code TIMESTAMPTZ}（GORM 对 Go {@code time.Time} 的映射），带时区语义；
 * {@code LocalDateTime} 会丢掉 offset，读出值随 JVM 默认时区漂移。
 *
 * <p><b>为什么本类不写 {@code @TableField}（与 B 侧逐条一致）</b>：
 * 字段 → 列名的推导由 MyBatis-Plus 的 {@code TableInfo} 完成，
 * {@code GlobalConfig.DbConfig#tableUnderline} 默认 {@code true}
 * （MP 3.5.17 字节码实测：{@code DbConfig} 构造器内 {@code iconst_1; putfield tableUnderline}，
 * {@code TableFieldInfo} 内调 {@code StringUtils.camelToUnderline}）
 * ⇒ {@code userId ↔ user_id}、{@code createTime ↔ create_time}。
 * B 侧同样不写 ⇒ 两侧行为一致；<b>不要</b>在本域「顺手补」{@code @TableField}
 * （那会引入第二套映射来源，与 B 侧的单一来源分叉）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class FollowRelationEntity {
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
@TableName("follow_relation")
public class FollowRelationEntity {

    /** 对应 Go {@code ID uint64 `gorm:"primaryKey"`} ⇒ BIGSERIAL，由 PG 序列分配。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应 Go {@code UserId int64 `gorm:"column:user_id;not null;index;uniqueIndex:uk_follow_pair"`}（关注者）。 */
    private long userId;

    /** 对应 Go {@code TargetId int64 `gorm:"column:target_id;not null;index;uniqueIndex:uk_follow_pair"`}（被关注者）。 */
    private long targetId;

    /** 对应 Go {@code CreateTime time.Time `gorm:"column:create_time;autoCreateTime`}。列表排序键（{@code create_time desc}）。 */
    private OffsetDateTime createTime;

    /** 对应 Go {@code UpdateTime time.Time `gorm:"column:update_time;autoUpdateTime`}。 */
    private OffsetDateTime updateTime;
}
