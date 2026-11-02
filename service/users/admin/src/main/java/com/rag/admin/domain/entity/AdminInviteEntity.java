package com.rag.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * {@code admin_invite} 表的 MyBatis-Plus 实体。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/domain/AdminInviteEntity.java}
 * ⇒ <b>本类为同构照抄</b>，字段 / 列名 / 类型 / 注解逐条一致。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.domain} → {@code domain.entity}（D7 包压平）。</li>
 *   <li><b>语义还一样吗</b>：一样。列名 / nullable / {@link OffsetDateTime} / {@code FieldStrategy.NEVER} 全未动。</li>
 *   <li><b>B 出处</b>：同上，逐字段可 grep 对齐。</li>
 * </ol>
 *
 * <p>L1 表结构（权威来源 = B 侧 {@code service/users/users-common/src/main/resources/db/04_create_admin_invite.sql}）：
 * <pre>
 *   id           BIGSERIAL PRIMARY KEY
 *   code         TEXT NOT NULL UNIQUE            -- 前缀 "ADM-" + 8 字节 hex（大写）
 *   inviter_uid  BIGINT NOT NULL                 -- 创建邀请码的 admin uid
 *   expires_at   TIMESTAMPTZ NOT NULL
 *   used_by_uid  BIGINT                          -- nullable，使用该 invite 的新 admin uid
 *   used_at      TIMESTAMPTZ                     -- nullable，使用时间
 *   create_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
 *   update_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
 * </pre>
 *
 * <p><b>⚠️ {@code expires_at} 是本域唯一可观察的时间列</b>：它参与「邀请码是否仍可用」的判定，
 * 而判定代码<b>结构同构、语义要求绝对时刻</b> ——
 * B 侧的 {@code invite.ExpiresAt} 来自 {@code timestamptz} ⇒ 带 location 的绝对时刻
 * ⇒ {@code .After(now)} 比绝对时刻；若用 {@code LocalDateTime}（墙钟），跨时区多实例部署时
 * （A 区建码、B 区消费）邀请码会<b>刚生成就判为已过期</b> ⇒ ⇒ <b>必须用 {@link OffsetDateTime}</b>。
 *
 * <p>其余三列（{@code used_at} / {@code create_time} / {@code update_time}）不可观察：
 * admin.proto 全部 13 个 rpc message 里没有这三个字段；{@code used_at} 在业务代码里只做 {@code != null} 判定。
 *
 * <p>行为约束（来自 B 侧 CreateInviteCode + CreateAdmin）：
 * <ul>
 *   <li>invite 仅在 {@code used_at IS NULL} 且 {@code expires_at > NOW} 时可用；</li>
 *   <li>一旦 {@code used_by_uid} 被写入，邀请码永久作废（即使 expires_at 未到）；</li>
 *   <li>CreateInviteCode 生成的 code 唯一约束冲突 → 重试最多 3 次；</li>
 *   <li>CreateAdmin 在事务中悲观锁 SELECT + UPDATE 原子写入（4 步事务，见 {@code AdminCreateAtomicService}）。</li>
 * </ul>
 */
@Data
@TableName("admin_invite")
public class AdminInviteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("code")
    private String code;

    @TableField("inviter_uid")
    private Long inviterUid;

    @TableField("expires_at")
    private OffsetDateTime expiresAt;

    @TableField("used_by_uid")
    private Long usedByUid;

    @TableField("used_at")
    private OffsetDateTime usedAt;

    @TableField(value = "create_time", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private OffsetDateTime createTime;

    @TableField(value = "update_time", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private OffsetDateTime updateTime;
}
