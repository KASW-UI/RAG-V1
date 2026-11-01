package com.rag.users.common;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * <p><b>{@link IdentityResolver.UserStatusLookup} 的 JDBC 实现</b> —— 读 {@code users_user} 表取 {@code status}。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/user/user-api/src/main/java/com/rag/users/user/api/service/IdentityResolver.java:176-217
 *      —— B 侧走 gRPC：{@code users.getUser(GetUserReq{uid})} ⇒ rpc 侧
 *         {@code FindOneByUid(uid)} ⇒ 回 {@code GetUserResp{found, user{uid, status}}}；
 *         「found=false」⇒ {@code ErrUserNotFoundException}；
 *         「user 是 proto 默认实例」⇒ 同上；
 *         「user.getUid() != uid」⇒ {@code ErrIdentityMismatchException}。
 * </pre>
 *
 * <h2>🔴 为什么从 gRPC 换成 JDBC（表达层差异，行为等价）</h2>
 * <p>B 侧 api 与 rpc 是<b>两个进程</b> ⇒ api 只能经 gRPC 取 user；
 * C 侧按 D1 + D7 把 api + rpc <b>合并成一个进程</b>（每域一个 app）⇒ 「自己调自己」无意义
 * ⇒ 直接查库。
 * <p><b>语义等价论证（逐条对照 B 的判定链）</b>：
 * <ol>
 *   <li><b>「查无此人」</b>：B 靠 rpc 的 {@code found} 标志；C 靠<b>查询结果为空</b> ⇒ 同一个 sentinel
 *       （{@link IdentityResolver.ErrUserNotFoundException}）。</li>
 *   <li><b>「取回的人不是请求的人」</b>：B 靠 proto 里的 {@code user.getUid()}；
 *       C 同样把行里的 {@code uid} 带出来（{@code record.uid()}）⇒ 判定与 B 同款，
 *       <b>不是</b>「按 uid 查就必然相等」的想当然短路。</li>
 *   <li><b>status 的 presence 语义</b>：B 依赖 proto3 {@code optional int64} 的 {@code hasStatus()}；
 *       C 依赖 SQL 的 {@code NULL}（{@code status} 列为 {@code NULL} ⇒ 字段「不存在」）
 *       ⇒ <b>用 {@link IdentityResolver.UserStatusRecord#hasStatus()} 表达同一件事</b>。</li>
 *   <li><b>status 的取值</b>：B 读 proto 的 {@code int64}；C 读 PG 的 {@code BIGINT}
 *       ⇒ 数值域相同（{@code BIGINT} ⇒ {@code long}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么用 {@link JdbcTemplate} 而不是 MyBatis-Plus</b>：本类是<b>域级共享库</b>
 * （users-common），而 MP 的 Mapper 属于各子域的持久层（{@code com.rag.user.mapper} /
 * {@code com.rag.admin.mapper}）⇒ 共享库不该反向依赖子域的持久层设施。
 * ⇒ 本类只依赖 {@code spring-jdbc}（已是本模块既有依赖），各子域若已有 Mapper，
 * 可<b>另写</b>一个 {@link IdentityResolver.UserStatusLookup} 实现直接接 Mapper（端口二选一，不叠加）。
 *
 * <p>⚠️ <b>表名是 L1 契约</b>：{@code users_user} 与 Go 的 {@code users}（GORM 表名）<b>不同名</b>，
 * 这是 B 侧既有的表名约定（users → users_user、admins → admin_admin、admin_invites → admin_invite）
 * ⇒ 本类<b>按 B 的表名</b>，不做「纠正」。
 */
public final class JdbcUserStatusLookup implements IdentityResolver.UserStatusLookup {

    /** users 域用户表名（B 出处：{@code 01_create_user.sql} 的 {@code CREATE TABLE IF NOT EXISTS users_user}）。 */
    public static final String TABLE_USERS_USER = "users_user";

    /** 表名白名单校验 —— 表名来自代码常量，但一旦被改成外部输入就会变成 SQL 注入面。 */
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;

    private final String tableName;

    /**
     * 用默认表 {@value #TABLE_USERS_USER} 构造。
     *
     * @param jdbcTemplate Spring 的 {@link JdbcTemplate}（由消费方注入）
     */
    public JdbcUserStatusLookup(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, TABLE_USERS_USER);
    }

    /**
     * 指定表名构造（供表结构相同的探针 / 测试使用）。
     *
     * @param jdbcTemplate Spring 的 {@link JdbcTemplate}
     * @param tableName    表名（须匹配 {@code [A-Za-z_][A-Za-z0-9_]*}）
     */
    public JdbcUserStatusLookup(JdbcTemplate jdbcTemplate, String tableName) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate must not be null");
        }
        if (tableName == null || !TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("illegal table name: " + tableName);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    @Override
    public IdentityResolver.UserStatusRecord findByUid(long uid) {
        List<IdentityResolver.UserStatusRecord> rows = jdbcTemplate.query(
                "SELECT uid, status FROM " + tableName + " WHERE uid = ?",
                (rs, rowNum) -> {
                    Object rawStatus = rs.getObject("status");
                    Long status = (rawStatus == null) ? null : ((Number) rawStatus).longValue();
                    return new IdentityResolver.UserStatusRecord(true, rs.getLong("uid"), status);
                },
                uid);
        if (rows.isEmpty()) {
            return IdentityResolver.UserStatusRecord.notFound();
        }
        return rows.get(0);
    }
}
