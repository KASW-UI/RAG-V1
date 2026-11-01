package com.rag.common.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * PostgreSQL <b>{@code text}</b> 列 ↔ {@code Map<String, String>} 的 TypeHandler。
 *
 * <p><b>2026-10-25（第十八轮 · global B10）：本类现在是 admin-rpc 里
 * {@code extra_info} 列的<b>唯一</b> handler</b>，同模块的
 * {@code JsonbTypeHandler} 已<b>整体删除</b>。
 *
 * <p><b>为什么不能用「jsonb 味」的写法</b>（{@code ps.setObject(i, json, Types.OTHER)}
 * 让 PG 按 jsonb 解释参数）——
 * 那只对 **jsonb 列**成立。本模块的两张表（{@code admin_admin} / {@code users_user}）
 * 的 {@code extra_info} 列在 B 侧都是 <b>text</b>
 * （{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/mapper/JsonTextTypeHandler.java:25:11} 与 {@code :43} 的 {@code serializer:json} ⇒ GORM 按 String 建列），
 * 而 jsonb 列比 text 列<b>多一套校验</b>，会拒收 {@code encoding/json} 能合法产出的内容。
 *
 * <p><b>这不是洁癖，有活体对拍证据（DRIFT-ARPC-004①，2026-10-23；B10，2026-10-25）</b>：
 * <pre>
 *   同一份请求体（extra_info 含 U+0000）：
 *     B 侧  → HTTP 200 {code:200}，行已落库
 *     RAG → HTTP 200 {code:5001}，无行，PG 报 22P05
 *            ERROR: unsupported Unicode escape sequence
 *            DETAIL: \u0000 cannot be converted to text.
 * </pre>
 * 根因：{@code encoding/json} 把 U+0000 转义成 6 个 ASCII 字符 {@code \u0000}
 * （**不是**裸 NUL 字节），而 jsonb 的 unicode escape 校验拒绝 {@code \u0000}；
 * text 列照单全收，与 B 侧 完全一致。
 *
 * <p>⚠️ <b>2026-10-25 订正</b>：本文件此前写「<b>不要把本 handler 与 JsonbTypeHandler 合并</b>：
 * admin-rpc 里另一张表的同名列 {@code users_user.extra_info} 仍是 JSONB」——
 * <b>那个前提早已失效</b>：
 * <ul>
 *   <li>{@code users-common/.../db/01_create_user.sql:41} 建表就是 {@code extra_info  TEXT}；</li>
 *   <li>{@code :103} 每次启动幂等 {@code ALTER ... TYPE text USING extra_info::text}
 *       （{@code SchemaInitializer} 每次启动原样重放）⇒ 这一列<b>不可能</b>是 jsonb；</li>
 *   <li>⇒ 两张表的同名列<b>都是 text</b> ⇒ 两个 handler 没有存在理由 ⇒ 合并为
 *       <b>本类</b>。</li>
 * </ul>
 *
 * <p><b>写</b>：{@code ps.setString}（text 列的原生绑定）。
 * <b>读</b>：{@code ResultSet.getString} 取回 JSON 文本，Jackson 解析。
 *
 * <p>配套要求：实体上的 {@code @TableName} 必须带 {@code autoResultMap = true}，
 * 否则 MyBatis-Plus 生成的 ResultMap 不采用字段级 TypeHandler（读回来恒 null）。
 *
 * <p><b>B 出处（声明点 + 消费点）</b>：
 * <ul>
 *   <li>声明点 {@code service/user/admin/rpc/internal/model/data_model.go:11}（{@code admin_admin}）
 *       与 {@code :43}（{@code users_user}）：
 *       {@code ExtraInfo map[string]string `gorm:"column:extra_info;serializer:json"`}</li>
 *   <li>消费点 {@code gorm.io/gorm@v1.31.1/schema/field.go}：字段带 {@code SERIALIZER} tag 时
 *       {@code field.DataType} 被置为 {@code String} ⇒ 列按<b>字符串</b>建；
 *       {@code serializer/json} 的 {@code Value()} 返回 {@code json.Marshal} 的 <b>string</b></li>
 * </ul>
 *
 * <p><b>⚠️ N:1 合并（映射表 §1.7 第 193 行 · 决策锁 L-8「副本合 1」）</b>：
 * RAG 侧有 **2 份** {@code JsonTextTypeHandler}（{@code user-rpc} + {@code admin-rpc}）
 * ⇒ 剥掉 package / import / 注释后<b>代码体逐字一致</b> ⇒ 本类取 admin 版 ⇒ 副本从 2 份收敛为 1 份。
 *
 * <p><b>抄写基准</b>：RAG {@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/mapper/JsonTextTypeHandler.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类 Jackson 写法在 RAG 侧<b>本就是 Jackson 2</b> ⇒ 零回改）。
 */
@MappedTypes(Map.class)
public class JsonTextTypeHandler extends BaseTypeHandler<Map<String, String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeReference<Map<String, String>> type;

    public JsonTextTypeHandler(Class<?> ignored) {
        this(new TypeReference<Map<String, String>>() {});
    }

    public JsonTextTypeHandler(TypeReference<Map<String, String>> type) {
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setString(i, toJson(parameter));
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<String, String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static String toJson(Map<String, String> map) throws SQLException {
        if (map == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new SQLException("Failed to serialize Map to JSON", e);
        }
    }

    private Map<String, String> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new SQLException("Failed to deserialize JSON to Map: " + json, e);
        }
    }
}