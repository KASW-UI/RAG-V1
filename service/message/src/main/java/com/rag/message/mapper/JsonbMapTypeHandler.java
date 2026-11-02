package com.rag.message.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>{@code jsonb} 列 ↔ {@code Map<String, String>} 的 TypeHandler</b>（域内副本）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/mapper/JsonbMapTypeHandler.java}
 * （B 侧类 javadoc {@code :16-52} · 类声明 {@code :53} · {@code setNonNullParameter :59-65} ·
 * {@code toJson :67-76} · {@code setParameter :78-86} · 三个 {@code getNullableResult :88-101} ·
 * {@code parse :103-118}）⇒ <b>整段照抄</b>（B 侧本身就用 Jackson 2
 * {@code com.fasterxml.jackson.*} ⇒ D14 零回改）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.mapper} →
 *       {@code com.rag.message.mapper}，D7 包压平）＋ <b>本段 javadoc</b>补上「为何共享层那份类型不兼容」。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 读写路径、异常语义（{@link SQLException} 而非
 *       {@code RuntimeException}）、{@code null} 的处理逐条一致。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么共享层那份<b>不能</b>直接用（待收口事项）</h2>
 *
 * <p>共享层 {@code common/} 现有两个 JSON handler，<b>都不是</b>「jsonb 列 ↔ Map」这一形态：
 * <ul>
 *   <li>{@code com.rag.common.persistence.JsonbTypeHandler} —— 列类型对（jsonb）、
 *       <b>Java 类型不对</b>：它是 {@code BaseTypeHandler<String>}（jsonb ↔ <b>String</b>），
 *       而本域 {@code Notification.extra} 是 {@code Map<String, String>}
 *       ⇒ 类型不兼容，套不上；</li>
 *   <li>{@code com.rag.common.persistence.JsonTextTypeHandler} —— Java 类型对（Map）、
 *       <b>列绑定不对</b>：它是 <b>text</b> 列的 handler，写入走 {@code ps.setString}
 *       ⇒ pgjdbc 按 VARCHAR 发送 ⇒ Postgres 拒收 jsonb 列
 *       （{@code column extra is of type jsonb but expression is of type character varying}）。</li>
 * </ul>
 * ⇒ ⇒ <b>共享层缺「jsonb ↔ Map」这一格</b>，本域只能自带副本。
 * ⚠️ <b>{@code common/} 是共享层，只有「收口域」任务能写</b>（AGENTS.md §五.3 规则 3）
 * ⇒ ⇒ 本 lane <b>不得</b>改共享层 ⇒ ⇒ 本类登记为<b>待收口事项</b>：
 * 若日后收口域把本类上收到 {@code common/persistence/}（如 {@code JsonbMapTypeHandler}），
 * 本域的 {@code @TableField(typeHandler = …)} 与 {@code insertBatch} 的 typeHandler 引用
 * 需<b>同一次提交</b>改指（AGENTS.md §五.3 规则 3：修法同时涉及 common/ 与别域代码时必须同一次提交）。
 *
 * <p>🔴 <b>DRIFT-MSG-V-013（2026-10-22 修）：为什么不能用 MP 自带的 {@code JacksonTypeHandler}</b>
 *
 * <p>MP 的 {@code AbstractJsonTypeHandler.setNonNullParameter} 反编译出来的实现是：
 * <pre>
 * ps.setString(i, toJson(parameter));
 * </pre>
 * 而 pgjdbc 的 {@code setString(i, s)} 内部走的是 {@code setString(i, s, Oid.VARCHAR)}，
 * 即<b>带显式 VARCHAR 类型</b>发送。于是 Postgres 收到的是一个「character varying」类型的参数，
 * 往 {@code jsonb} 列赋值直接被拒：
 * <pre>
 * ERROR: column "extra" is of type jsonb but expression is of type character varying
 * 建议：You will need to rewrite or cast the expression.
 * </pre>
 *
 * <p><b>正确写法是 {@code ps.setObject(i, json, Types.OTHER)}</b>：
 * {@code Types.OTHER} 让 pgjdbc 以 <b>unspecified</b> 类型发送，
 * 由 Postgres 按目标列（{@code jsonb}）自行推断 —— 这是 JDBC 访问 json/jsonb 的标准做法，
 * 且<b>不在 SQL 里写 CAST</b>，因此不把 Postgres 语法泄漏进 mapper 注解。
 *
 * <p><b>影响面</b>：{@code extra} 是 {@code Notification.extra} 的唯一持久化路径
 * （{@code NotificationMapper.insertBatch} 是通知写入的唯一生产路径）。
 * 由于 {@code MessageRpcSupport.newNotificationRecord} 永远给一个非 null 的 map
 * （缺席/空都归一成 {@code emptyMap}），{@code setNonNullParameter} <b>永远</b>被调用
 * ⇒ 修复前 {@code SendNotification} 在真实 DB 上<b>必然失败</b>。
 *
 * <p><b>读取方向同样需要修</b>：MyBatis-Plus 只有在 {@code @TableName(autoResultMap = true)}
 * 时才会把 {@code @TableField(typeHandler=...)} 应用到自动生成的 SELECT 结果映射上。
 * 少了它，读 jsonb 列拿到的是 {@code PGobject}，映射成 {@code Map} 会失败。
 * 两处缺一不可，见 {@link com.rag.message.domain.entity.NotificationEntity}。
 *
 * <p><b>Go 对照</b>：Go 侧是 {@code model.JSONMap}（{@code data_model.go:10-28}），
 * 它的 {@code Value()} 用 {@code json.Marshal}、{@code Scan()} 用 {@code json.Unmarshal}，
 * 读写都由驱动按 jsonb 处理 ⇒ 两侧 wire 上都是合法 JSON 文本，语义等价。
 */
public class JsonbMapTypeHandler extends BaseTypeHandler<Map<String, String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE =
            new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        // ⚠️ 关键：Types.OTHER ⇒ pgjdbc 以 unspecified 发送，Postgres 按 jsonb 列推断。
        //    换成 setString(...) 就会被当作 VARCHAR 而被 jsonb 列拒绝（见类注释）。
        ps.setObject(i, toJson(parameter), Types.OTHER);
    }

    private static String toJson(Map<String, String> parameter) throws SQLException {
        try {
            return MAPPER.writeValueAsString(parameter);
        } catch (Exception e) {
            // Map<String,String> 的序列化理论上不会失败（没有循环引用/自定义 getter），
            // 但仍必须兜住：这里是写库路径，让异常以 SQLException 形态冒泡，
            // 才能被 @Transactional 正确回滚，而不是变成一个不受管的 RuntimeException。
            throw new SQLException("failed to serialize Map<String,String> to jsonb", e);
        }
    }

    @Override
    public void setParameter(PreparedStatement ps, int i, Map<String, String> parameter,
                             JdbcType jdbcType) throws SQLException {
        if (parameter == null) {
            ps.setNull(i, Types.OTHER);
        } else {
            setNonNullParameter(ps, i, parameter, jdbcType);
        }
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

    /**
     * Go 侧 {@code JSONMap.Scan} 在收到 {@code nil} 时把字段置 {@code nil}；
     * 这里同样返回 {@code null}，不返回空 map ——
     * 差别可观察：{@code toNotificationItem} 会把 null map 传给 proto，
     * 而 {@code extra} 为空与「字段为 null」在 wire 上是两种形态。
     */
    private static Map<String, String> parse(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, TYPE);
        } catch (Exception e) {
            throw new SQLException("failed to parse jsonb into Map<String,String>: " + json, e);
        }
    }
}
