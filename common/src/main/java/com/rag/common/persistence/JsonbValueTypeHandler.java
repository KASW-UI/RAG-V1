package com.rag.common.persistence;

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
 * <p><b>职责</b>：jsonb 列 ↔ <b>结构化值</b>（{@code List<String>} / {@code Map<String,String>}）
 * 的 TypeHandler 集合 —— 对应 B 侧 {@code model/article.go} 的
 * {@code StringArray} 与 {@code JSONMap} 两个自定义 {@code driver.Valuer}/{@code sql.Scanner}。
 *
 * <p><b>抄写基准</b>：RAG {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/JsonbTypeHandler.java}
 * （类声明 {@code :48} · {@code PG_JSONB} {@code :51} · {@code MAPPER} {@code :53} ·
 * {@code toJsonb} {@code :58-64} · {@code StringListTypeHandler} {@code :70-92} ·
 * {@code StringMapTypeHandler} {@code :100-123} · {@code writeJson} {@code :131-136} ·
 * {@code readList} {@code :163-172} · {@code readMap} {@code :185-192}）⇒ <b>方法体逐条照抄</b>
 * （L2 业务语义层以 B 侧为基准整段抄 · D13 · D16）。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>落点</b>：B 侧在 {@code article-rpc/…/mapper/} 包 ⇒ 本仓落进共享层
 *             {@code com.rag.common.persistence}（D3 上收）；</li>
 *         <li><b>类名</b>：{@code JsonbTypeHandler} → {@code JsonbValueTypeHandler}
 *             —— ⚠️ <b>命名避让</b>：共享层<b>已有</b> {@code com.rag.common.persistence.JsonbTypeHandler}
 *             （= {@code BaseTypeHandler<String>}，**jsonb ↔ 文本**）⇒ 同名会撞车 ⇒ 本类改名。
 *             改名依据语义区分：<b>「Value」= jsonb 列的结构化值（列表 / 字典）</b>，
 *             既有那份<b>只当文本</b>（{@code String}）。</li>
 *         <li><b>异常文案里的 {@code "article"} 前缀<b>逐字保留</b></b>：B 侧原文就是
 *             {@code "article jsonb column value is not serializable"} 等三处 ⇒
 *             L1 文案以 B 侧为准（D16）⇒ <b>不改</b>（哪怕类已上收到共享层）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 判定条件、异常类型（{@link SQLException}）、
 *       {@code null} 的处理路径、PG 列类型声明方式<b>逐条照抄</b> ⇒ 落库内容逐字节相同。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么<b>不</b>与 {@link JsonbMapTypeHandler} 合并成一份</h2>
 * <p>共享层另有 {@code JsonbMapTypeHandler}（**jsonb ↔ Map&lt;String,String&gt;**）——
 * 两者<b>面向同一对 Java 类型</b>（{@code Map<String,String>}），但<b>行为不同</b>，
 * 合并会<b>改行为</b>：
 * <table border="1">
 *   <caption>两版逐条差异（合并即漂移）</caption>
 *   <tr><th>维度</th><th>本类（{@code JsonbValueTypeHandler}）</th><th>{@code JsonbMapTypeHandler}</th></tr>
 *   <tr><td><b>写 null</b></td>
 *       <td>不覆写 {@code setParameter} ⇒ MyBatis 默认路径</td>
 *       <td>覆写 {@code setParameter} ⇒ {@code ps.setNull(i, Types.OTHER)}</td></tr>
 *   <tr><td><b>写非 null</b></td>
 *       <td>{@code ps.setObject(i, PGobject(jsonb))}</td>
 *       <td>{@code ps.setObject(i, jsonString, Types.OTHER)}</td></tr>
 *   <tr><td><b>读空白串</b></td>
 *       <td>返回 {@code null}</td>
 *       <td>Jackson 抛 ⇒ {@code SQLException}</td></tr>
 * </table>
 * ⇒ ⇒ 两者<b>各自照抄 B 侧对应文件</b>，<b>不合并</b>（合并 = 凭空制造数据层漂移）。
 *
 * <h2>为什么不用 MyBatis-Plus 自带的 JacksonTypeHandler</h2>
 * <p>本域两个 jsonb 列语义<b>完全不同</b>：{@code secondary_tags} 是<b>数组</b>，
 * {@code ext_info} 是<b>对象</b>且<b>值必须是 String</b>。MP 的通用 handler 会按目标类型推断，
 * 对 {@code Map<String, String>} 与 {@code List<String>} 的处理路径不稳定，
 * 且 {@code ext_info} 的值里混着<b>存成字符串的数值</b>（{@code last_sync_version_ms: "1792…"}）
 * 和布尔标志（{@code published_once: "true"}）——若被推断成 {@code Object}，
 * 读回来会变成 Integer/Boolean，与 Go 的 {@code JSONMap map[string]string} 不一致，
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
 *
 * <p><b>锁死用例</b>：B 侧 {@code ArticleJsonbMappingTest}（真 PG，验证两侧 null/空/非空三种形态逐字节一致）。
 *
 * <h2>✅ 收口留痕（D-11 · 2026-11-03）</h2>
 * <p>本类此前只存在于 article 域内（{@code com.rag.article.mapper.JsonbTypeHandler}）
 * ⇒ 共享层<b>缺对应物</b>（既有的 {@code JsonbTypeHandler} 是 jsonb ↔ 文本、
 * {@code JsonTextTypeHandler} 是 text ↔ Map，<b>列绑定与 Java 类型都不对</b>）
 * ⇒ 该域被迫留副本。
 * ⇒ 本次上收到共享层 ＋ <b>域内副本已删</b> ⇒ <b>副本数 1 → 0</b>
 * ⇒ article 域的 entity / mapper / service 改 import
 * {@code com.rag.common.persistence.JsonbValueTypeHandler}。
 * <p>⚠️ <b>与原副本不得分叉</b> ⇒ 上收时以 B 侧 195 行原类为基准逐条校对
 * （本次<b>方法体逐条照抄，零改写</b>；含三处带 {@code "article"} 前缀的异常文案）。
 */
public final class JsonbValueTypeHandler {

    /** PG 的 jsonb 类型名。 */
    public static final String PG_JSONB = "jsonb";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonbValueTypeHandler() {
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
     * <h3>⚠️ 三个分支各自为什么必需（2026-10-24 逐条实测）</h3>
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
     * <p>⇒ 最后那一条**保留**：它让「JSON {@code null} ⇒ null」这个契约<b>显式可见</b>，
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
