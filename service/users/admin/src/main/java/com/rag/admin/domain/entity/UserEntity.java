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
 * {@code users_user} 表的 MyBatis-Plus 实体（admin 子域独立持有）。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/domain/UserEntity.java}
 * ⇒ <b>本类为同构照抄</b>，字段 / 列名 / 类型 / 注解逐条一致。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅两处纯机械改动 ——
 *       ① {@code typeHandler} 类名换到共享层 {@link JsonTextTypeHandler}（D3 上提，字节级同源）；
 *       ② 包名 {@code rpc.domain} → {@code domain.entity}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>⚠️ 与 user 子域共用同一张物理表</b>（两侧都建 {@code users_user}）
 * ⇒ 本字段类型必须与 user 子域那份逐条一致，否则同一行数据两侧读出的时间字符串不同，
 * 属跨组件漂移。
 *
 * <p><b>🔴 {@code status = 0L} 的初始值不是缺陷，不要删</b>：
 * B 侧用 GORM 扫描 {@code status IS NULL} 的行时，{@code database/sql} 的
 * {@code convertAssign} 对「dest 是指针、src 为 nil」走 {@code dv.SetZero()} ⇒ <b>不报错</b>，
 * GORM 的 int setter 随后把字段留在零值 0 ⇒ ⇒ <b>B 侧把 NULL 读成 0</b>。
 * 删掉本类的 {@code = 0L} 会让本域读出 {@code null}（而 B 读出 0）⇒ <b>新的漂移</b>。
 *
 * <p><b>⚠️ {@code updateTime} 用 {@link OffsetDateTime} 而非 {@code LocalDateTime}</b>：
 * B 侧的列是 {@code timestamptz}（无 type tag ⇒ GORM postgres driver 生成 timestamptz），
 * 若实体仍是 {@code LocalDateTime}，JVM 默认时区会参与换算
 * ⇒ 把「列类型不一致」换成「值算错」⇒ 必须与 DDL 同源。
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 *public class UserEntity {
 *    private Long id;
 *    private Long uid;
 *    private String username;
 *    private String password;
 *    private String email;
 *    private Long status;
 *    private Integer score;
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
 *    public Long getStatus() { return status; }
 *    public void setStatus(Long status) { this.status = status; }
 *    public Integer getScore() { return score; }
 *    public void setScore(Integer score) { this.score = score; }
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
 *        if (!(o instanceof UserEntity)) return false;
 *        UserEntity other = (UserEntity) o;
 *        return                 Objects.equals(id, other.id) &&
                Objects.equals(uid, other.uid) &&
                Objects.equals(username, other.username) &&
                Objects.equals(password, other.password) &&
                Objects.equals(email, other.email) &&
                Objects.equals(status, other.status) &&
                Objects.equals(score, other.score) &&
                Objects.equals(extraInfo, other.extraInfo) &&
                Objects.equals(createTime, other.createTime) &&
                Objects.equals(updateTime, other.updateTime);
 *    }
 *
 *    @Override
 *    public int hashCode() {
 *        return Objects.hash(id, uid, username, password, email, status, score, extraInfo, createTime, updateTime);
 *    }
 *
 *    @Override
 *    public String toString() {
 *        return "UserEntity(id=" + id + ", " + uid=" + uid + ", " + username=" + username + ", " + password=" + password + ", " + email=" + email + ", " + status=" + status + ", " + score=" + score + ", " + extraInfo=" + extraInfo + ", " + createTime=" + createTime + ", " + updateTime=" + updateTime)";
 *    }
 *}
 * }</pre>

 */
@Data
@TableName(value = "users_user", autoResultMap = true)
public class UserEntity {

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

    @TableField("status")
    private Long status = 0L;

    @TableField("score")
    private Integer score;

    @TableField(value = "extra_info", typeHandler = JsonTextTypeHandler.class)
    private Map<String, String> extraInfo;

    @TableField(value = "create_time", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private OffsetDateTime createTime;

    @TableField(value = "update_time", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private OffsetDateTime updateTime;
}
