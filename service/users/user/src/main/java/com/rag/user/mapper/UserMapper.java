package com.rag.user.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.common.persistence.JsonTextTypeHandler;
import com.rag.user.domain.entity.UserEntity;
import com.rag.user.exception.UserExceptions.NotFoundException;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * <p><b>{@code users_user} 表的 MyBatis-Plus Mapper</b>。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/mapper/UserMapper.java}
 * （逐条照抄；仅 TypeHandler 的 import 改为共享层落点）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>{@code @Mapper}（org.apache.ibatis）告诉 MyBatis-Plus 这是一个 mapper 接口；</li>
 *   <li>{@code extends BaseMapper<UserEntity>} 自动获得 selectById / insert / updateById / deleteById / selectList 等 CRUD；</li>
 *   <li>自定义 {@code @Select} / {@code @Delete} 方法一对一对应 B 侧 DataModel 的查询（保持 Go parity 命名）。</li>
 * </ul>
 *
 * <p><b>NotFoundException 抛在 mapper 层</b>（B 侧 D-001）：调用方 catch 后映射成
 * gRPC {@code Status.NOT_FOUND} / {@code Status.INTERNAL}。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * ⚠️ <b>注意 {@code extra_info}（text 列）的 TypeHandler</b>：
     * 手写 {@code @Select} 必须显式带 {@code @Results} 才能把该列正确读成 Map ——
     * MyBatis 不认识 MyBatis-Plus 的 {@code @TableField}，实体字段上的 TypeHandler
     * 只对 MP 自己生成的 SQL 生效。不声明的话 {@code extraInfo} 会<b>静默变成 null</b>（不报错）。
     */
    @Select("SELECT * FROM users_user WHERE username = #{username} LIMIT 1")
    @Results({
            @Result(property = "extraInfo", column = "extra_info",
                    javaType = java.util.Map.class, typeHandler = JsonTextTypeHandler.class)
    })
    UserEntity selectByUsernameRaw(@Param("username") String username);

    /**
     * 对应 Go 端 {@code UserModel.FindOneByUserName}。
     *
     * <p>「找不到」是 <b>error，不是值</b>（与 Go 的 {@code (user, err)} 二元组逐条同构）
     * ⇒ 方法内直接抛 {@link NotFoundException}。
     */
    default UserEntity findOneByUserName(String username) {
        UserEntity u = selectByUsernameRaw(username);
        if (u == null) {
            throw new NotFoundException("user not found by username: " + username);
        }
        return u;
    }

    @Select("SELECT * FROM users_user WHERE uid = #{uid} LIMIT 1")
    @Results({
            @Result(property = "extraInfo", column = "extra_info",
                    javaType = java.util.Map.class, typeHandler = JsonTextTypeHandler.class)
    })
    UserEntity selectByUidRaw(@Param("uid") Long uid);

    /** 对应 Go 端 {@code UserModel.FindOneByUid}。 */
    default UserEntity findOneByUid(Long uid) {
        UserEntity u = selectByUidRaw(uid);
        if (u == null) {
            throw new NotFoundException("user not found by uid: " + uid);
        }
        return u;
    }

    @Delete("DELETE FROM users_user WHERE uid = #{uid}")
    int deleteUserByUid(@Param("uid") Long uid);

    /**
     * 对应 Go 端 GORM 的 {@code Updates(newUser)} —— GORM 默认跳过零值（""/0/false），
     * 等价于 Java 端只把「显式传入且非空」的字段加入 UPDATE 子句。
     *
     * <p><b>D-017 决策点</b>：必须只更新 Username/Password/Email/ExtraInfo 四列。
     * 用 MyBatis-Plus {@code LambdaUpdateWrapper} 动态拼 set 子句。
     *
     * <p>🔴 <b>{@code update_time} 必须<b>无条件</b>进 SET</b>（B 侧 {@code DRIFT-USERRPC-V-010} 已修）：
     * <pre>
     *   Go 出处（消费点）：gorm.io/gorm@v1.31.1/callbacks/update.go:235-252
     *     if field.AutoUpdateTime &gt; 0 &amp;&amp; value[field.Name] == nil &amp;&amp; value[field.DBName] == nil {
     *         … set = append(set, clause.Assignment{Column: …, Value: now})   // ← 无条件进 SET
     *     }}
     * </pre>
     * ⇒ 即便所有业务字段都是零值，{@code autoUpdateTime} 字段<b>仍会被加进 SET</b>
     * ⇒ ⇒ <b>Go 总会发出一条只推进 update_time 的 UPDATE</b>。
     * ⇒ ⇒ 本实现<b>刻意不写</b>「无字段变更就早退」的分支 —— 那是 B 侧被证伪过的旧假设。
     */
    default int updateUserById(Long uid, UserEntity newUser) {
        LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserEntity::getUid, uid);
        // ⚠️ 只写「非空」的业务字段 —— 对齐 GORM Updates(struct) 跳过零值的语义。
        //    （注意：update_time 不受这条约束，见本方法末尾的说明。）
        if (newUser.getUsername() != null && !newUser.getUsername().isEmpty()) {
            wrapper.set(UserEntity::getUsername, newUser.getUsername());
        }
        if (newUser.getPassword() != null && !newUser.getPassword().isEmpty()) {
            wrapper.set(UserEntity::getPassword, newUser.getPassword());
        }
        if (newUser.getEmail() != null && !newUser.getEmail().isEmpty()) {
            wrapper.set(UserEntity::getEmail, newUser.getEmail());
        }
        if (newUser.getExtraInfo() != null) {
            // ⚠️ 不能用 wrapper.set(UserEntity::getExtraInfo, map)：
            //    LambdaUpdateWrapper.set 把参数以 Object 形式塞进 ew.paramNameValuePairs，
            //    不走实体字段上的 @TableField(typeHandler = JsonTextTypeHandler.class)，
            //    MyBatis 只能按 javaType=Object 猜 handler ⇒ 序列化成什么全不保证。
            //    正确做法：直接写 SQL 片段，参数按 String 绑定。
            //
            // ⚠️ B 侧 DRIFT-USERRPC-V-003：列是 text ⇒ **不加 CAST**。
            //    CAST 是当年为 jsonb 列被迫加的；列改回 text 后它不仅多余，
            //    还会**重新引入 jsonb 的规范化**（把 Go 原样存的字节改写成 PG 的重排版）
            //    ⇒ 等于把刚修掉的漂移又请回来 ⇒ 必须一起去掉。
            wrapper.setSql("extra_info = {0}", toJson(newUser.getExtraInfo()));
        }
        // 🔴 update_time **无条件**进 SET（对齐 GORM autoUpdateTime 的 callbacks/update.go:238-252）。
        wrapper.setSql("update_time = CURRENT_TIMESTAMP");
        return update(null, wrapper);
    }

    /**
     * Map → JSON 文本，供 {@code wrapper.setSql("extra_info = {0}", ...)} 使用。
     *
     * <p>刻意不在这里吞异常：序列化失败属于编程/数据错误，向上抛由 domain 层映射成
     * {@code Status.INTERNAL "JSON序列化失败"}。
     */
    private static String toJson(java.util.Map<String, String> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("extraInfo JSON serialize failed", e);
        }
    }
}
