package com.rag.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.user.domain.entity.UserAvatarHistoryEntity;
import com.rag.user.domain.entity.UserEntity;
import com.rag.user.exception.UserExceptions.AvatarHistoryNotFoundException;
import com.rag.user.exception.UserExceptions.NotFoundException;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>{@code users_user_avatar_history} 表的 MyBatis-Plus Mapper</b>。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/mapper/UserAvatarHistoryMapper.java}
 * （逐条照抄）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>与 {@link UserMapper} 同套路；</li>
 *   <li>4 步原子（{@code createAvatarHistory}）与 5 步原子（{@code selectAvatarHistory}）用
 *       Spring {@code @Transactional} 保证 D-005 / D-015：必须原子；history 查询必须带 uid 条件（防越权）。</li>
 * </ul>
 *
 * <p>⚠️ <b>本 mapper 上挂的 {@code @Transactional} 方法需要 Spring AOP 代理</b>：
 * MyBatis-Plus mapper 是接口，由 {@code MybatisAutoConfiguration} 注册的 {@code SqlSessionTemplate}
 * 提供事务管理 ⇒ 本域的 {@code AvatarTransactionWiringTest} 用<b>真 PostgreSQL</b> 验证
 * 「4 步原子」在中途失败时整体回滚（不是靠 Mockito 桩）。
 *
 * <p>🔴 <b>该断言的实测形态（2026-11-02 · users-user lane）</b>：注解写在<b>接口的 {@code default} 方法</b>上
 * ⇒ 是本仓<b>最易静默失效</b>的形态（三处前提任一不成立，注解就是装饰品且零报错）⇒ ⇒ 判据 =
 * <ul>
 *   <li>用 {@code history_id} 的 <b>UNIQUE 约束</b>让 W3 的 INSERT <b>真失败</b>（真实 {@code DuplicateKeyException}）</li>
 *   <li>断言 <b>W2 的 UPDATE 不留痕</b>（旧 history 仍是 {@code is_current=true}）</li>
 *   <li>配<b>同序列成功路径</b>作非空洞对照 + <b>变异实测</b>（摘掉注解 ⇒ 判据变红）</li>
 * </ul>
 * ⇒ 证据见 {@code docs/DRIFT/users/verification-users-user-2026-11-02.md} §11.7 / §11.8 ·
 * 方法论见 {@code docs/Pitfalls/users/_user-domain-pitfalls.md} P-USER-06。
 */
@Mapper
public interface UserAvatarHistoryMapper extends BaseMapper<UserAvatarHistoryEntity> {

    /**
     * 对应 Go 端 {@code ListAvatarHistory}：
     * <pre>
     *   limit &lt;= 0 || limit &gt; 30  → 默认 30（D-010：静默 clamp，不抛异常）
     *   ORDER BY create_time DESC
     * </pre>
     */
    default List<UserAvatarHistoryEntity> listAvatarHistory(Long uid, int limit) {
        if (limit <= 0 || limit > 30) {
            limit = 30;
        }
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserAvatarHistoryEntity>()
                .eq("uid", uid)
                .orderByDesc("create_time")
                .last("LIMIT " + limit));
    }

    /**
     * 对应 Go 端 {@code SelectAvatarHistory} 的 W2：
     * 查 history（带 uid 隔离条件；不存在 → {@link AvatarHistoryNotFoundException}）。
     */
    @Select("SELECT * FROM users_user_avatar_history WHERE uid = #{uid} AND history_id = #{historyId} LIMIT 1")
    UserAvatarHistoryEntity selectByUidAndHistoryIdRaw(
            @Param("uid") Long uid, @Param("historyId") String historyId);

    default UserAvatarHistoryEntity findOneByUidAndHistoryId(Long uid, String historyId) {
        UserAvatarHistoryEntity h = selectByUidAndHistoryIdRaw(uid, historyId);
        if (h == null) {
            throw new AvatarHistoryNotFoundException(
                    "avatar history not found: uid=" + uid + " historyId=" + historyId);
        }
        return h;
    }

    /**
     * UPDATE 旧 current → false（uid 范围内所有 is_current=true 的行）。
     * 对应 createAvatarHistory W2 与 selectAvatarHistory W3。
     */
    @Update("UPDATE users_user_avatar_history SET is_current = false, update_time = CURRENT_TIMESTAMP " +
            "WHERE uid = #{uid} AND is_current = true")
    int clearCurrentByUid(@Param("uid") Long uid);

    /**
     * UPDATE 目标 history → is_current=true。
     * 对应 selectAvatarHistory W4。
     */
    @Update("UPDATE users_user_avatar_history SET is_current = true, update_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{id}")
    int markCurrentById(@Param("id") Long id);

    /**
     * INSERT 新 history（is_current=true）。
     * 对应 createAvatarHistory W3。
     *
     * <p>🔴 <b>B 侧 {@code DRIFT-USERRPC-V-009}：create_time / update_time 必须由应用侧绑定，
     * 不能用 {@code CURRENT_TIMESTAMP}</b>：
     * <pre>
     *   Go 出处：service/user/user/rpc/internal/model/user.go:86-95
     *     if err := tx.Create(&amp;history).Error; err != nil { return err }
     * </pre>
     * GORM 的 {@code autoCreateTime / autoUpdateTime} 会在 <b>Create 之前</b>把这两个字段
     * 写成应用侧时钟（{@code gorm.io/gorm/callbacks/create.go} 的
     * {@code ConvertToCreateValues} 用 {@code stmt.DB.NowFunc()}），并把它们<b>随 INSERT 一起写进库里</b>；
     * 由于传进去的是 {@code &history}（指针），<b>同一个结构体被回填</b>
     * ⇒ {@code avatarHistoryItemFromModel(&history)} 拿到的是<b>真实时刻</b>
     * ⇒ {@code UploadAvatarResp.history.create_time} 是一个非空 RFC3339 字符串。
     * <p>⇒ 修法与 Go 同构：<b>应用侧时钟写库 + 同一个实体回填</b>。
     */
    @Insert("INSERT INTO users_user_avatar_history " +
            "(history_id, uid, avatar_url, content_type, size_bytes, is_current, create_time, update_time) " +
            "VALUES (#{historyId}, #{uid}, #{avatarUrl}, #{contentType}, #{sizeBytes}, #{isCurrent}, " +
            "#{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertHistory(UserAvatarHistoryEntity history);

    /**
     * UPDATE {@code user.extra_info["avatar"] = avatarURL}（D-014：双写 user 表的 JSON 字段）。
     * 对应 createAvatarHistory W4 / selectAvatarHistory W5。
     *
     * <p>⚠️ <b>列是 text ⇒ 不加 CAST</b>（B 侧 {@code DRIFT-USERRPC-V-003}）：
     * {@code jsonb → text} 走的是 assignment cast，会先经一次 jsonb 规范化
     * （键重排 / 去重 / 删空格 / 冒号后补空格）再落库
     * ⇒ 语法上不报错、测试也可能照样绿，但<b>库里存的字节已经不是 Go 存的那份了</b>。
     */
    @Update("UPDATE users_user SET extra_info = #{extraInfoJson}, " +
            "update_time = CURRENT_TIMESTAMP WHERE uid = #{uid}")
    int updateUserExtraInfo(@Param("uid") Long uid, @Param("extraInfoJson") String extraInfoJson);

    /**
     * <b>4 步原子事务</b>，对应 Go 端 {@code m.conn.WithContext(ctx).Transaction(func(tx *gorm.DB) error {...})}。
     *
     * <p>D-005：4 步必须原子，任何一步失败整体回滚。
     *
     * @param userMapper 用于双写 {@code users.extra_info}（D-014）
     */
    @org.springframework.transaction.annotation.Transactional
    default UserAvatarHistoryEntity createAvatarHistory(
            UserMapper userMapper,
            Long uid, String historyId, String avatarUrl, String contentType, Long sizeBytes) {
        // W1 查 user
        UserEntity user = userMapper.selectByUidRaw(uid);
        if (user == null) {
            throw new NotFoundException("user not found by uid: " + uid);
        }

        // W2 UPDATE 旧 current → false
        clearCurrentByUid(uid);

        // W3 INSERT 新 history
        UserAvatarHistoryEntity history = new UserAvatarHistoryEntity();
        history.setHistoryId(historyId);
        history.setUid(uid);
        history.setAvatarUrl(avatarUrl);
        history.setContentType(contentType);
        history.setSizeBytes(sizeBytes);
        history.setIsCurrent(true);
        // 🔴 应用侧时钟写库 + 同一实体回填（Go 的 GORM autoCreateTime/autoUpdateTime 同构）。
        //   create_time 是**唯一 wire 可见**的时间字段（proto AvatarHistoryItem.create_time）
        //   ⇒ 若不写库时回填，响应里 create_time 会是空串。
        //   Go 侧 GORM 在 Create 之前把两个字段都设成 stmt.DB.NowFunc()（应用侧时钟）
        //   ⇒ 这里同样用**同一个** now 赋给 create/update_time。
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        history.setCreateTime(now);
        history.setUpdateTime(now);
        insertHistory(history);

        // W4 UPDATE user.extra_info["avatar"] = avatarURL（D-014：双写）
        Map<String, String> extra = user.getExtraInfo();
        if (extra == null) {
            extra = new HashMap<>();
        }
        extra.put("avatar", avatarUrl);
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(extra);
            updateUserExtraInfo(uid, json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize extraInfo to JSON", e);
        }
        // 同步内存副本，保持与 DB 一致
        user.setExtraInfo(extra);
        // ⚠️ 这里**不能**再调 userMapper.updateById(user) 做「第二次写入」：
        //   1. updateUserExtraInfo 已经把同一份数据写进去了，重复写没有意义；
        //   2. 重复写会绕过 updateUserExtraInfo 的那条 SQL 语义。
        return history;
    }

    /**
     * <b>5 步原子事务</b>，对应 Go 端 {@code SelectAvatarHistory}：
     * <pre>
     *   W1 查 user（不存在 → NotFoundException）
     *   W2 查 history（带 uid 隔离；不存在 → AvatarHistoryNotFoundException）
     *   W3 UPDATE 旧 current → false
     *   W4 UPDATE 目标 history → is_current=true
     *   W5 UPDATE user.extra_info["avatar"] = history.AvatarURL（D-014）
     * </pre>
     *
     * <p>D-005 / D-015：必须原子；history 查询必须带 uid 条件（防越权取别人头像）。
     */
    @org.springframework.transaction.annotation.Transactional
    default UserAvatarHistoryEntity selectAvatarHistory(UserMapper userMapper, Long uid, String historyId) {
        // W1
        UserEntity user = userMapper.selectByUidRaw(uid);
        if (user == null) {
            throw new NotFoundException("user not found by uid: " + uid);
        }

        // W2
        UserAvatarHistoryEntity history = findOneByUidAndHistoryId(uid, historyId);

        // W3
        clearCurrentByUid(uid);

        // W4
        markCurrentById(history.getId());
        // ⚠️ 必须同步回写内存实体：W4 的 UPDATE 只改 DB，不改这里持有的对象。
        //    若不写回，返回给调用方的 SelectAvatarResp.history.is_current 会是
        //    「更新前」的旧值（通常是 false），与刚落库的 is_current=true 自相矛盾。
        history.setIsCurrent(true);

        // W5
        Map<String, String> extra = user.getExtraInfo();
        if (extra == null) {
            extra = new HashMap<>();
        }
        extra.put("avatar", history.getAvatarUrl());
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(extra);
            updateUserExtraInfo(uid, json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize extraInfo to JSON", e);
        }
        // 同 createAvatarHistory：不再补一次 updateById（理由见该方法内注释）

        return history;
    }
}
