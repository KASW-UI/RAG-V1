package com.rag.article.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：article 域 jsonb 列的 TypeHandler —— {@code secondary_tags}（数组）
 * ＋ {@code ext_info}（对象）＋ 整体冻结载荷列的 JSON 文本读写。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/JsonbTypeHandler.java}
 * （类声明 {@code :48} · {@code PG_JSONB} {@code :51} · {@code MAPPER} {@code :53} ·
 * {@code toJsonb} {@code :58-64} · {@code StringListTypeHandler} {@code :70-92} ·
 * {@code StringMapTypeHandler} {@code :100-123} · {@code writeJson} {@code :131-136} ·
 * {@code readList} {@code :163-172} · {@code readMap} {@code :185-192}）⇒ <b>整段照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.rpc.mapper} →
 *       {@code com.rag.article.mapper}，D7 包压平）＋ <b>本段 javadoc</b>补上「为何留在域内」。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 三个与 B 侧对齐的语义点（null 读成 null / null 写成 JSON 文本
 *       {@code "null"} / PG 列类型显式声明 jsonb）逐条照抄，见下方小节。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么<b>留在域内</b>（不能上提到共享层 · 与 message 域同款缺口）</h2>
 * <p>映射表 {@code article/article.md} §1.2 / L-3 旧版写「→ {@code common/.../JsonbTypeHandler.java}
 * （D3 上提）」= <b>不成立</b>：
 * <ul>
 *   <li>共享层 {@code common/persistence/JsonbTypeHandler} = {@code BaseTypeHandler<String>}
 *       （<b>jsonb ↔ String</b>），而本域需要的是 <b>jsonb ↔ List&lt;String&gt;</b>
 *       ＋ <b>jsonb ↔ Map&lt;String,String&gt;</b> ⇒ <b>类型不兼容</b>，顶替会编译不过；</li>
 *   <li>共享层 {@code common/persistence/JsonTextTypeHandler} = <b>text ↔ Map</b>
 *       ⇒ <b>列绑定不对</b>（走 {@code ps.setString} ⇒ pgjdbc 按 VARCHAR 发送 ⇒ PG 拒收 jsonb 列）。</li>
 * </ul>
 * ⇒ ⇒ 共享层缺「jsonb ↔ 数组」与「jsonb ↔ 字符串 map」这两格
 * ⇒ ⇒ 且 {@code common/} 是<b>收口域写权</b>（AGENTS.md §五.3 规则 3）⇒ 本 lane <b>不得</b>改共享层
 * ⇒ ⇒ 域内保留照抄副本，并登记为<b>待收口</b>事项（见 {@code docs/DRIFT/article/vs-common.md}）。
 *
 * <h2>为什么不用 MyBatis-Plus 自带的 JacksonTypeHandler</h2>
 * <p>本域两个 jsonb 列语义<b>完全不同</b>：{@code secondary_tags} 是<b>数组</b>，
 * {@code ext_info} 是<b>对象</b>且<b>值必须是 String</b>。MP 的通用 handler 会按目标类型推断，
 * 对 {@code Map<String, String>} 与 {@code List<String>} 的处理路径不稳定，
 * 且 {@code ext_info} 的值里混着<b>存成字符串的数值</b>（{@code last_sync_version_ms: "1792…"}）
 * 和布尔标志（{@code published_once: "true"}）——若被推断成 {@code Object}，
 * 读回来会变成 Integer/Boolean，与 B 侧的 {@code JSONMap map[string]string} 不一致，
 * 进而让 {@code ext_info.get(ExtLastSyncVersion) != String.valueOf(result.VersionMs)}
 * 这类判据<b>恒真</b>，静默破坏同步幂等。
 *
 * <h2>⚠️ 与 B 侧的三条语义对齐点</h2>
 * <ol>
 *   <li><b>列为 null 时读成 null 而不是空集合</b>：B 侧 {@code StringArray.Scan} /
 *       {@code JSONMap.Scan} 在 {@code value == nil} 时 {@code *a = nil}。
 *       读成空 list 会让 {@code len(in.SecondaryTags) > 0} 与
 *       {@code cloneStringMap} 的"空则返回 nil"语义漂移。</li>
 *   <li><b>写入时 null 列</b>：B 侧的 {@code Value()} 对 nil map/slice 会
 *       {@code json.Marshal} 出 {@code "null"}，写入的是 JSON 文本 {@code null}
 *       而不是 SQL NULL。Java 侧同样写 {@code "null"} 文本，保持两侧列内容一致。</li>
 *   <li><b>PG 列类型必须显式声明 jsonb</b>：否则 pgjdbc 按 VARCHAR 绑定，
 *       jsonb 列会因类型不匹配报错（与 like 域的 JsonbTypeHandler 同款理由）。</li>
 * </ol>
 *
 * <p><b>不校验 JSON 合法性</b>：B 侧也不校验（payload 由 json.Marshal 产生，一定合法）。
 * 上层传了非法 JSON 应让 PG 在插入时报错，而不是在这里静默吞掉。
 */
public final class JsonbTypeHandler {

    /** PG 的 jsonb 类型名。 */
    public static final String PG_JSONB = "jsonb";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonbTypeHandler() {
    }

    private static PGobject toJsonb(String value) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType(PG_JSONB);
        jsonb.setValue(value);
        return jsonb;
    }

    /**
     * {@code secondary_tags} jsonb ↔ {@code List<String>}。
     *
     * <p>对应 B 侧 {@code type StringArray []string}。
     */
    public static class StringListTypeHandler extends BaseTypeHandler<List<String>> {

        @Override
        public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
                throws SQLException {
            // B 侧: json.Marshal(a) —— nil slice 也会写出 "null"，不是 SQL NULL
            ps.setObject(i, toJsonb(writeJson(parameter)));
        }

        @Override
        public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
            return readList(rs.getString(columnName));
        }

        @Override
        public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
            return readList(rs.getString(columnIndex));
        }

        @Override
        public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
            return readList(cs.getString(columnIndex));
        }
    }

    /**
     * {@code ext_info} jsonb ↔ {@code Map<String, String>}。
     *
     * <p>对应 B 侧 {@code type JSONMap map[string]string}。
     */
    public static class StringMapTypeHandler extends BaseTypeHandler<Map<String, String>> {

        @Override
        public void setNonNullParameter(PreparedStatement ps, int i, Map<String, String> parameter, JdbcType jdbcType)
                throws SQLException {
            ps.setObject(i, toJsonb(writeJson(parameter)));
        }

        @Override
        public Map<String, String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
            return readMap(rs.getString(columnName));
        }

        @Override
        public Map<String, String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
            return readMap(rs.getString(columnIndex));
        }

        @Override
        public Map<String, String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
            return readMap(cs.getString(columnIndex));
        }
    }

    /**
     * JSON 文本写列 —— 用于 {@code article_sync_outbox.payload} /
     * {@code article_domain_outbox.payload} 这类<b>整体冻结</b>的载荷列。
     *
     * <p>⚠️ B 侧的 {@code MustMarshalSyncEvent} 产出的是一整段 JSON 文本，
     * 落库后<b>推送时必须逐字节一致</b>（relay 推的是 {@code event.Payload} 原文，不是重新序列化）。
     */
    public static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("article jsonb column value is not serializable", e);
        }
    }

    /**
     * 解析 JSON 文本为 {@code List<String>}；{@code null} / 空白 / JSON {@code null} → {@code null}。
     *
     * <p>对应 B 侧 {@code StringArray.Scan}：{@code value == nil ⇒ *a = nil}。
     *
     * <h3>⚠️ 三个分支各自为什么必需</h3>
     * <table border="1">
     *   <caption>删掉任一分支的后果</caption>
     *   <tr><th>分支</th><th>删掉的后果</th><th>实测</th></tr>
     *   <tr><td>{@code json == null}</td>
     *       <td>SQL NULL 会被 Jackson 判成「无内容」而抛异常</td><td>必需</td></tr>
     *   <tr><td>{@code json.isBlank()}</td>
     *       <td>空白串会让 Jackson 抛 {@code MismatchedInputException}</td>
     *       <td>必需（实测：{@code readValue("  ", List) ⇒ MismatchedInputException}）</td></tr>
     *   <tr><td>{@code "null".equals(json)}</td>
     *       <td>⚠️ <b>行为不变</b> —— Jackson 自己就把 JSON 字面量 {@code null} 读成 {@code null}</td>
     *       <td><b>与 Jackson 重复（冗余）</b>；实测：{@code readValue("null", List) ⇒ null}</td></tr>
     * </table>
     *
     * <p>⇒ 最后那一条<b>保留</b>：它让「JSON {@code null} ⇒ null」这个契约<b>显式可见</b>，
     * 而不是隐含在 Jackson 的行为里（万一哪天 Jackson 的行为变了，这里是保险）。
     * ⚠️ 但要知道它<b>不是</b>承重的 —— 变异验证时删掉它，测试<b>照样全绿</b>
     * ⇒ 这条「仍全绿」判为「**变异无效**」，不是「测不到」。
     */
    public static List<String> readList(String json) throws SQLException {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new SQLException("article jsonb array column is not a JSON string array: " + json, e);
        }
    }

    /**
     * 解析 JSON 文本为 {@code Map<String, String>}；{@code null} / 空白 / JSON {@code null} → {@code null}。
     *
     * <p>对应 B 侧 {@code JSONMap.Scan}。
     *
     * <p>⚠️ 非字符串值（如 JSON number）<b>不会</b>被静默转成字符串：
     * Jackson 在此抛异常并冒泡，因为 B 侧 {@code json.Unmarshal} 进
     * {@code map[string]string} 遇到 number 同样会失败。两侧一致地拒绝，
     * 好过一侧悄悄接受 —— 那会让「B 侧写不出这种行、Java 侧写得出」成为数据层漂移。
     */
    public static Map<String, String> readMap(String json) throws SQLException {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new SQLException("article jsonb object column is not a JSON string map: " + json, e);
        }
    }
}
