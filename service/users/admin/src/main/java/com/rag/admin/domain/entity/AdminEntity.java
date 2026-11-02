package com.rag.admin.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.common.persistence.JsonTextTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * {@code admin_admin} 表的 MyBatis-Plus 实体。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/domain/AdminEntity.java}
 * （B 侧 = RAG 仓，唯一行为判定基准 · D16）⇒ <b>本类为同构照抄</b>，字段 / 列名 / 类型 / 注解逐条一致。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅两处<b>纯机械</b>改动 ——
 *       ① {@code typeHandler} 的类名从域内 {@code com.rag.users.admin.rpc.mapper.JsonTextTypeHandler}
 *       换成共享层 {@link JsonTextTypeHandler}（D3 上提；<b>实现字节级同源</b>，B 侧那份在 2026-10-25
 *       已被 B 自己删成 1 份共享层副本）；② 包名 {@code rpc.domain} → {@code domain.entity}（D7 包压平）。</li>
 *   <li><b>语义还一样吗</b>：一样。列名、nullable 语义、时间类型（{@link OffsetDateTime}）、
 *       {@code FieldStrategy.NEVER} 全部逐条未动 ⇒ <b>行为零差异</b>。</li>
 *   <li><b>B 出处</b>：同上；本类的每个字段注解都能在 B 侧同名文件里逐字找到。</li>
 * </ol>
 *
 * <p>L1 表结构（权威来源 = B 侧 {@code service/users/users-common/src/main/resources/db/03_create_admin.sql}）：
 * <pre>
 *   id          BIGSERIAL PRIMARY KEY
 *   uid         BIGINT NOT NULL UNIQUE         -- Snowflake uid（业务主键）
 *   username    TEXT UNIQUE                    -- nullable（admin 可没 username）
 *   password    TEXT                           -- bcrypt 哈希
 *   email       TEXT UNIQUE                    -- nullable（1:1 保留 B 侧 *string 语义）
 *   extra_info  TEXT                           -- JsonTextTypeHandler（text，不是 jsonb）
 *   create_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
 *   update_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
 * </pre>
 *
 * <p><b>⚠️ {@code extra_info} 是 {@code text} 不是 {@code jsonb}</b> —— 与 B 侧同构。
 * 显式 {@code CAST(... AS jsonb)} 会把 text 强制转回 jsonb、重新引入 jsonb 那套 unicode escape 校验
 * （B 侧实测：含 U+0000 的 extra_info，B 侧落库成功、jsonb 以 22P05 拒收）
 * ⇒ ⇒ <b>两处 {@code setSql("extra_info = {0}", …)} 都不能加 CAST</b>。
 *
 * <p><b>⚠️ {@code update_time} 的 {@code FieldStrategy.NEVER} 是刻意的</b> ——
 * 它表示「实体层永不写这一列」；本域所有 UPDATE 走 {@code update(null, wrapper)} 路径
 * （实体为 null ⇒ MP 的 {@code MetaObjectHandler} 不会被调用）⇒ 必须靠
 * {@code wrapper.setSql("update_time = CURRENT_TIMESTAMP")} 推进时间戳。
 * ⇒ 该列在 admin.proto 的 13 个 message 里<b>没有</b>对应字段 ⇒ 不可观察。
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 *public class AdminEntity {
 *    private Long id;
 *    private Long uid;
 *    private String username;
 *    private String password;
 *    private String email;
 *    private Map<String, String> extraInfo;
 *    private OffsetDateTime createTime;
 *    private OffsetDateTime updateTime;
 *
 *    public Long getId() { return id; }
 *    public void setId(Long id) { this.id = id; }
 *    public Long getUid() { return uid; }
 *    public void setUid(Long uid) { this.uid = uid; }
 *    public String getUsername() { return username; }
 *    public void setUsername(String username) { this.username = username; }
 *    public String getPassword() { return password; }
 *    public void setPassword(String password) { this.password = password; }
 *    public String getEmail() { return email; }
 *    public void setEmail(String email) { this.email = email; }
 *    public Map<String, String> getExtraInfo() { return extraInfo; }
 *    public void setExtraInfo(Map<String, String> extraInfo) { this.extraInfo = extraInfo; }
 *    public OffsetDateTime getCreateTime() { return createTime; }
 *    public void setCreateTime(OffsetDateTime createTime) { this.createTime = createTime; }
 *    public OffsetDateTime getUpdateTime() { return updateTime; }
 *    public void setUpdateTime(OffsetDateTime updateTime) { this.updateTime = updateTime; }
 *
 *    @Override
 *    public boolean equals(Object o) {
 *        if (this == o) return true;
 *        if (!(o instanceof AdminEntity)) return false;
 *        AdminEntity other = (AdminEntity) o;
 *        return                 Objects.equals(id, other.id) &&
                Objects.equals(uid, other.uid) &&
                Objects.equals(username, other.username) &&
                Objects.equals(password, other.password) &&
                Objects.equals(email, other.email) &&
                Objects.equals(extraInfo, other.extraInfo) &&
                Objects.equals(createTime, other.createTime) &&
                Objects.equals(updateTime, other.updateTime);
 *    }
 *
 *    @Override
 *    public int hashCode() {
 *        return Objects.hash(id, uid, username, password, email, extraInfo, createTime, updateTime);
 *    }
 *
 *    @Override
 *    public String toString() {
 *        return "AdminEntity(id=" + id + ", " + uid=" + uid + ", " + username=" + username + ", " + password=" + password + ", " + email=" + email + ", " + extraInfo=" + extraInfo + ", " + createTime=" + createTime + ", " + updateTime=" + updateTime)";
 *    }
 *}
 * }</pre>

 */
@Data
@TableName(value = "admin_admin", autoResultMap = true)
public class AdminEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("uid")
    private Long uid;

    @TableField("username")
    private String username;

    @TableField("password")
    private String password;

    @TableField("email")
    private String email;

    @TableField(value = "extra_info", typeHandler = JsonTextTypeHandler.class)
    private Map<String, String> extraInfo;

    @TableField(value = "create_time", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private OffsetDateTime createTime;

    @TableField(value = "update_time", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private OffsetDateTime updateTime;
}
