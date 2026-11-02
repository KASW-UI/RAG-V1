package com.rag.user.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.common.persistence.JsonTextTypeHandler;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * <p><b>{@code users_user} 表实体</b> —— MyBatis-Plus {@code @TableName} ＋ {@code JsonTextTypeHandler}。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/domain/UserEntity.java}
 * （逐条照抄；仅 TypeHandler 的落点由 B 的域内
 * {@code com.rag.users.user.rpc.mapper.JsonTextTypeHandler} 改为共享层的
 * {@code com.rag.common.persistence.JsonTextTypeHandler} —— 见下方说明）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>{@code @TableName("users_user")} —— <b>L1 表名</b>（B 出处：Go 侧 {@code model.InitDB} 的活体建表语句）；</li>
 *   <li>{@code @TableId(type = IdType.ASSIGN_ID)} 主键策略（雪花 ID）；</li>
 *   <li>{@code @TableField("snake_case")} 显式映射列名；</li>
 *   <li>{@code extra_info}（<b>text</b> 列）由 {@link JsonTextTypeHandler} 序列化。</li>
 * </ul>
 *
 * <p>⚠️ <b>{@code autoResultMap = true} 不能去掉</b>：没有它，MyBatis-Plus 自己生成的 ResultMap
 * 不会采用 {@code extra_info} 字段上声明的 TypeHandler ⇒ 读回来的 JSON 文本会是 {@code null}（<b>不报错</b>）。
 *
 * <p>⚠️ <b>TypeHandler 落点变化（L4 · 零行为差异）</b>：B 侧该类在<b>域内</b>
 * {@code user-rpc/.../mapper/JsonTextTypeHandler.java}；C 侧的共享层已提供<b>逐条相同</b>的
 * {@code com.rag.common.persistence.JsonTextTypeHandler}（由 {@code users-common} lane 从 B 侧上提）
 * ⇒ ⇒ 域内不再保留第二份 ⇒ <b>避免「同一规则的第二份实现」</b>（B 侧自身吃过这个亏）。
 *
 * <p>⚠️ <b>2026-10-23（B 侧 DRIFT-USERRPC-V-003）：{@code extra_info} 列是 <b>text</b>，不是 jsonb</b>
 * （与 Go 侧 {@code serializer:json} 建出来的列类型一致）⇒ 原注释里「MP 的 JacksonTypeHandler 会被 jsonb 列拒收」
 * 的理由<b>已随列类型一起失效</b>。
 */
@TableName(value = "users_user", autoResultMap = true)
public class UserEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("uid")
    private Long uid;

    @TableField("username")
    private String username;

    @TableField("password")
    private String password;

    @TableField("email")
    private String email;

    /**
     * 对应 Go {@code Status int64 `gorm:"column:status;default:0"`}
     * （声明点 {@code service/user/user/rpc/internal/model/data_model.go:11}，<b>无 not null</b>）。
     *
     * <p>🔴 <b>{@code = 0L} 是等价性的实现载体，不是历史包袱</b>：
     * <ul>
     *   <li><b>Go 的语义</b>：{@code status IS NULL} 读出来是 {@code 0}，<b>不报错</b>
     *       —— {@code gorm@v1.31.1/schema/field.go:616-622} 给 int64 字段装的 setter 在
     *       {@code *data == nil} 时<b>什么都不做</b>（字段留零值 0），
     *       而 {@code database/sql/convert.go:431-435} 的 {@code case reflect.Pointer: if src == nil { dv.SetZero(); return nil }}
     *       ⇒ <b>不报错</b>（{@code convert.go:438} 那条 {@code case reflect.Int64} <b>永远走不到</b>，
     *       因为 dest 的 kind 是 Pointer 而不是 Int64）。</li>
     *   <li><b>本类的机制与之逐条同形</b>：MyBatis 的 {@code DefaultResultSetHandler} 在
     *       {@code value == null && !callSettersOnNulls} 时<b>跳过 setter</b>
     *       （{@code callSettersOnNulls} 默认 {@code false}）⇒ 字段初始化值 {@code 0L} 留了下来
     *       ⇒ {@code status IS NULL} 同样读成 <b>{@code 0L}</b>。</li>
     *   <li>⇒ 曾被删掉过一次（并加了「status 为 null ⇒ 抛 INTERNAL」的守卫），
     *       那个守卫的理由<b>已被上面第 1 段证伪</b>（双侧活体对拍：Go 对 {@code status IS NULL}
     *       的行返回 200 + token）⇒ <b>删掉 {@code = 0L} 才是不等价的那一侧</b>。</li>
     * </ul>
     * <p>⚠️ <b>删除/恢复初始化值都不改变写入语义</b>：INSERT 走 {@code UserDomainService#register}
     * 显式 {@code u.setStatus(0L)}；UPDATE 走 {@code UserMapper#updateUserById} 的
     * {@code LambdaUpdateWrapper.set(...)} <b>逐字段显式 set</b>（不走 {@code updateById(entity)}）。
     */
    @TableField("status")
    private Long status = 0L;

    @TableField("score")
    private Integer score;

    @TableField(value = "extra_info", typeHandler = JsonTextTypeHandler.class)
    private Map<String, String> extraInfo;

    /**
     * 🔴 类型为 {@link OffsetDateTime} 而非 {@code LocalDateTime}（B 侧 {@code DRIFT-USERRPC-V-004}）。
     *
     * <p><b>Go 出处</b>：声明点 {@code data_model.go:14-15} 是 {@code time.Time}
     * ＋ {@code gorm:"column:create_time;autoCreateTime"}，<b>无 type tag</b>；
     * 消费点 {@code gorm.io/driver/postgres@v1.6.0/postgres.go:251-256} 的
     * {@code case schema.Time} 分支在 {@code field.Precision == 0} 时返回 {@code "timestamptz"}。
     *
     * <p><b>为什么必须是带偏移的类型</b>：Go 的 {@code time.Time} 自带 location，
     * {@code avatarhelpers.go:17-18} 的 {@code item.CreateTime.Format(time.RFC3339)}
     * 输出形如 {@code 2026-01-01T08:00:00+08:00}，<b>带偏移量</b>。
     * {@code LocalDateTime} 没有偏移量 ⇒ 凑不出 Go 那个字符串 ⇒ <b>wire 可见</b>。
     *
     * <p>⚠️ <b>与 DDL 必须同一提交</b>：列改 timestamptz 而实体仍是 LocalDateTime，
     * 会让 JVM 默认时区参与换算，把「列类型不一致」换成「值算错」——比现状更糟。
     */
    @TableField("create_time")
    private OffsetDateTime createTime;

    /**
     * 🔴 同 {@link #createTime}（B 侧 {@code DRIFT-USERRPC-V-004}）。Go 声明点
     * {@code data_model.go:30-31}，消费点同为 {@code postgres.go:251-256}。
     */
    @TableField("update_time")
    private OffsetDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Map<String, String> getExtraInfo() {
        return extraInfo;
    }

    public void setExtraInfo(Map<String, String> extraInfo) {
        this.extraInfo = extraInfo;
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
