package com.rag.user.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * <p><b>{@code users_user_avatar_history} 表实体</b> —— MyBatis-Plus {@code @TableName} 风格。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/domain/UserAvatarHistoryEntity.java}
 * （逐条照抄）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>与 {@link UserEntity} 一致的 {@code @TableName} / {@code @TableId} / {@code @TableField} 套路；</li>
 *   <li>{@code historyId} 是 Snowflake 生成的字符串 ID（B 侧 D-013：<b>服务端生成，不接受请求传入</b>）；</li>
 *   <li>{@code isCurrent} 的 getter 用 {@code getIsCurrent()}，匹配 Go 端 {@code UserAvatarHistory.IsCurrent}。</li>
 * </ul>
 *
 * <p><b>L1 表名</b>：{@code users_user_avatar_history}（B 出处：Go 侧 {@code model.InitDB} 的活体建表语句）。
 */
@TableName("users_user_avatar_history")
public class UserAvatarHistoryEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("history_id")
    private String historyId;

    @TableField("uid")
    private Long uid;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("content_type")
    private String contentType;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("is_current")
    private Boolean isCurrent = false;

    /**
     * 🔴 类型为 {@link OffsetDateTime} 而非 {@code LocalDateTime}（B 侧 {@code DRIFT-USERRPC-V-004} 的<b>可观察半边</b>）。
     *
     * <p><b>为什么这一处是「可观察」的</b>：本表的 {@code create_time} 经
     * {@code AvatarHistoryItem.create_time}（proto {@code string}）<b>直接进 wire</b>，
     * 渲染形态是客户端可见字符串 —— 与 {@code users_user.create_time}（不进任何 wire 字段）不同。
     *
     * <p><b>偏移量由「进程本地时区」决定，不是 PG 会话时区</b>（B 侧 2026-10-23 双侧活体对拍实测）：
     * Go 侧 pgx 的 {@code TimestamptzCodec.ScanLocation} 默认 nil ⇒ 落回 {@code time.Local}
     * ⇒ {@code Format(RFC3339)} 打印的是<b>进程本地时区</b>的偏移量；
     * 而 pgjdbc 把 timestamptz 取成 {@code OffsetDateTime} 时<b>恒用 UTC</b>
     * ⇒ ⇒ 渲染时<b>必须显式换算到 JVM 本地时区</b>（见 {@code AvatarDomainService#fromModel}）。
     *
     * <p>⚠️ <b>与 DDL 必须同一提交</b>（列改 timestamptz 而实体仍是 LocalDateTime ⇒ 值算错）。
     */
    @TableField("create_time")
    private OffsetDateTime createTime;

    /**
     * 🔴 同 {@link #createTime}（B 侧 {@code DRIFT-USERRPC-V-004}）。Go 声明点
     * {@code data_model.go:31}，消费点同为 {@code postgres.go:251-256}。
     * <p>⚠️ 本列<b>不进 wire</b>（proto 的 {@code AvatarHistoryItem} 没有 update_time 字段）
     * ⇒ 它的偏移量差异属 DB 层、不可观察。
     */
    @TableField("update_time")
    private OffsetDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Boolean getIsCurrent() {
        return isCurrent;
    }

    public void setIsCurrent(Boolean isCurrent) {
        this.isCurrent = isCurrent;
    }

    public OffsetDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(OffsetDateTime createTime) {
        this.createTime = createTime;
    }

    public OffsetDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(OffsetDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
