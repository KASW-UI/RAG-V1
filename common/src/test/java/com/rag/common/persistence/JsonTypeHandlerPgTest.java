package com.rag.common.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 **两个 TypeHandler 的真 PostgreSQL 守门** ——
 * {@code JsonbTypeHandler}（jsonb 列 ↔ String）与 {@code JsonTextTypeHandler}（text 列 ↔ Map）。
 *
 * <h2>为什么必须真 PG（mock / H2 都验不到要验的东西）</h2>
 *
 * <p>这两个类的<b>全部业务语义都长在 PG 的列类型上</b>，不在 Java 代码里：
 * <table border="1">
 *   <caption>四条语义 ↔ PG 侧机制</caption>
 *   <tr><th>语义</th><th>PG 侧机制</th></tr>
 *   <tr><td>jsonb 列<b>规范化</b>空白与键序 ⇒ 读回文本 ≠ 写入原文</td>
 *       <td>jsonb 的<b>内部存储是语义化的</b>（binary 形式）⇒ 只有真 jsonb 列才这样</td></tr>
 *   <tr><td>写 jsonb 列必须显式声明类型</td>
 *       <td>按 VARCHAR 绑定 ⇒ PG 报 <b>42804</b>（datatype_mismatch）</td></tr>
 *   <tr><td>jsonb 列<b>拒收</b> {@code \u0000} 转义</td>
 *       <td>SQLSTATE <b>22P05</b>（untranslatable_character）</td></tr>
 *   <tr><td>text 列<b>照单全收</b> {@code \u0000} 转义</td>
 *       <td>text 列无 JSON 校验 ⇒ 这正是两个 handler 必须分开的唯一判据</td></tr>
 * </table>
 *
 * <p>⇒ mock 数据层不执行 SQL；H2 没有 JSONB 类型也没有 unicode escape 校验
 * ⇒ 二者都<b>把要验的东西换没了</b>（同款理由见 RAG 侧
 * {@code service/article/article-rpc/src/test/.../ArticleJsonbResultMapWiringTest} 的 javadoc）。
 *
 * <h2>与 Go 的对照（为什么「两个 handler」不是重复劳动）</h2>
 *
 * <p>Go 侧 {@code encoding/json} 把 U+0000 转义成 <b>6 个 ASCII 字符</b> {@code \u0000}
 *（<b>不是</b>裸 NUL 字节）⇒ Go 的 {@code text} 列必须收下它；
 * 而 jsonb 列的 unicode escape 校验会拒收（22P05）
 * ⇒ ⇒ <b>Go 侧同一个 handler 若同时服务 jsonb 与 text，text 那条路径就会 500</b>。
 * <p>活体对拍证据（RAG 侧 <b>DRIFT-ARPC-004①</b>，2026-10-23）：
 * <pre>
 *   同一份请求体（extra_info 含 U+0000）：
 *     Go  → HTTP 200 {code:200}，行已落库
 *     RAG → HTTP 200 {code:5001}，无行，PG 报 22P05
 * </pre>
 *
 * <h2>本类的可证伪性（AGENTS.md §3.3.3）</h2>
 *
 * <p>把 {@code JsonbTypeHandler#setNonNullParameter} 的 {@code PGobject} 改成
 * {@code ps.setString(i, parameter)} ⇒ 本类<b>立刻红</b>（PG 报 42804）；
 * 把 {@code JsonTextTypeHandler#getNullableResult} 的解析改成 {@code return null} ⇒ 本类<b>立刻红</b>。
 * ⇒ 这条纪律来自 AGENTS.md §3.3.3「绿测试可能是错误答案的守卫，比没有测试更危险」。
 *
 * <h2>抄写基准</h2>
 *
 * <p>被测实现抄自 RAG（见两类的 javadoc）：
 * {@code service/comment/comment-rpc/.../mapper/JsonbTypeHandler.java}（comment/favorite/like 三份逐字一致）
 * 与 {@code service/users/admin/admin-rpc/.../mapper/JsonTextTypeHandler.java}（user/admin 两份逐字一致）
 * ⇒ L2 业务语义层以 RAG 为基准整段抄（D13）。
 */
@Testcontainers
class JsonTypeHandlerPgTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("common_typehandler")
                    .withUsername("rag")
                    .withPassword("rag");

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    // ══════════════════════════════════════════════════════════════════
    // 探针表与 MyBatis 装配
    // ══════════════════════════════════════════════════════════════════

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new UnpooledDataSource(
                "org.postgresql.Driver",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());

        Configuration configuration =
                new Configuration(new Environment("pg", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ProbeMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE type_handler_probe (
                        id        TEXT PRIMARY KEY,
                        jsonb_col JSONB,
                        text_col  TEXT
                    )
                    """);
        }
    }

    @BeforeEach
    void truncate() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE type_handler_probe");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ① JsonbTypeHandler —— jsonb 列 ↔ String
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("🔴 对照组：同一个 jsonb 列若按 VARCHAR 绑定 ⇒ PG 报 42804（证 PGobject 路径是承重的）")
    void varcharBindingIntoJsonbColumnIsRejectedByPg() throws Exception {
        // 这一条是**对照组**：它证明「我们的 PGobject 绑定」不是可有可无的包装，
        // 而是 PG 唯一接受的形式 ⇒ 主用例若改回 setString，本类立刻红。
        SQLException e = assertThrowsSql(() -> rawUpdate(
                "INSERT INTO type_handler_probe (id, jsonb_col) VALUES (?, ?)",
                "ctl-varchar", "{\"a\":1}"));

        assertThat(e.getSQLState()).isEqualTo("42804");
        assertThat(e.getMessage()).contains("jsonb");
    }

    @Test
    @DisplayName("🔴 JsonbTypeHandler：写走 PGobject ⇒ 真落进 jsonb 列（读回是 PG 的规范化形态）")
    void jsonbHandlerWritesThroughPgobjectIntoRealJsonbColumn() throws Exception {
        withMapper(m -> m.insertJsonb("jb-1", "{\"b\":1,\"a\":2}"));

        // PG 的 jsonb 会规范化键序与空白 ⇒ 读回 **不等于** 写入原文
        // ⇒ 这一条同时证了「列真是 jsonb」与「读走的是 PG 的表示」
        JsonbRow row = withMapper(m -> m.selectJsonb("jb-1"));
        assertThat(row).isNotNull();
        assertThat(row.getPayload()).isEqualTo("{\"a\": 2, \"b\": 1}");

        // 再从原生 JDBC 侧看一次：列类型必须是 jsonb（information_schema 视角）
        assertThat(rawColumnType("jb-1", "jsonb_col")).isEqualTo("jsonb");
    }

    @Test
    @DisplayName("🔴 JsonbTypeHandler：写入前不校验 JSON ⇒ 非法 JSON 由 PG 报错（22P02）")
    void jsonbHandlerLeavesJsonValidationToPg() throws Exception {
        // Go 侧也不校验（payload 由 json.Marshal 产生，一定合法）⇒
        // 上层传了非法 JSON 就让 PG 插入时报错，而不是在 handler 里静默吞掉。
        SQLException e = assertThrowsSql(() -> withMapper(m -> m.insertJsonb("jb-bad", "{invalid")));

        assertThat(e.getSQLState()).isEqualTo("22P02");
        assertThat(e.getMessage()).contains("invalid input syntax for type json");
    }

    @Test
    @DisplayName("🔴 JsonbTypeHandler：jsonb 列拒收 \\u0000 转义（22P05）⇒ 两个 handler 必须分开的判据之一")
    void jsonbHandlerIsRejectedByPgForNulEscape() throws Exception {
        // 这里传的是 **6 个 ASCII 字符** `\u0000`（不是裸 NUL 字节）—— 与 Go 侧
        // encoding/json 的产出形态一致 ⇒ 于是撞上 jsonb 的 unicode escape 校验。
        SQLException e = assertThrowsSql(() -> withMapper(m -> m.insertJsonb("jb-nul", "{\"k\":\"\\u0000\"}")));

        assertThat(e.getSQLState()).isEqualTo("22P05");
        assertThat(e.getMessage()).contains("unsupported Unicode escape sequence");
    }

    // ══════════════════════════════════════════════════════════════════
    // ② JsonTextTypeHandler —— text 列 ↔ Map<String,String>
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("🔴 JsonTextTypeHandler：写走 setString ⇒ 落进 text 列（原样 JSON 文本）")
    void jsonTextHandlerWritesRawJsonTextIntoTextColumn() throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("reco_sync_state", "pending");
        payload.put("last_sync_event_id", "evt-1");

        withMapper(m -> m.insertText("tx-1", payload));

        assertThat(rawTextColumn("tx-1"))
                .isEqualTo("{\"reco_sync_state\":\"pending\",\"last_sync_event_id\":\"evt-1\"}");
        assertThat(rawColumnType("tx-1", "text_col")).isEqualTo("text");
    }

    @Test
    @DisplayName("🔴 JsonTextTypeHandler：text 列照单全收 \\u0000（jsonb 会拒）⇒ Go 对拍证据")
    void jsonTextHandlerAcceptsWhatJsonbRejects() throws Exception {
        // Go 侧 encoding/json 把 U+0000 转义成 6 个 ASCII 字符 `\u0000`
        // ⇒ Go 的 text 列必须收下 ⇒ Java 侧也必须收下 ⇒ 这是 DRIFT-ARPC-004① 的根因所在。
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("k", "\u0000");

        withMapper(m -> m.insertText("tx-nul", payload));

        // 原样落库（是 6 个 ASCII 字符，不是裸 NUL 字节）
        assertThat(rawTextColumn("tx-nul")).isEqualTo("{\"k\":\"\\u0000\"}");
        // 再读回来 ⇒ Jackson 解析回带 NUL 的原值
        TextRow row = withMapper(m -> m.selectText("tx-nul"));
        assertThat(row).isNotNull();
        assertThat(row.getPayload()).containsExactly(Map.entry("k", "\u0000"));
    }

    @Test
    @DisplayName("🔴 JsonTextTypeHandler：读回 Map（字段值逐条相等）")
    void jsonTextHandlerReadsBackMap() throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("a", "1");
        payload.put("b", "2");

        withMapper(m -> m.insertText("tx-read", payload));

        TextRow row = withMapper(m -> m.selectText("tx-read"));
        assertThat(row).isNotNull();
        assertThat(row.getPayload()).containsExactlyInAnyOrderEntriesOf(payload);
    }

    @Test
    @DisplayName("🔴 JsonTextTypeHandler：null map ⇒ 列写 NULL（不是字符串 \"null\"）")
    void jsonTextHandlerWritesNullColumnForNullMap() throws Exception {
        withMapper(m -> m.insertText("tx-null", null));

        // 列里是 SQL NULL —— 不是字符串 "null"（后者是「把 null 当 Map 序列化」的结果）
        assertThat(rawTextColumn("tx-null")).isNull();

        // 读回：MyBatis 在「映射值全为 null」时按 `isReturnInstanceForEmptyRow=false`（默认）
        // ⇒ **丢弃整行、返回 null**。这不是「handler 没跑」：
        // 若 handler 没被应用，auto-mapping 会把 String 直接塞进 `Map` 属性 ⇒ IllegalArgumentException。
        // ⇒ 「返回 null 而非抛异常」本身就是 handler 跑过并给出 null 的证据。
        // 对照组：`jsonTextHandlerReadsBackMap`（列里有合法 JSON ⇒ 行对象正常返回、payload 非空）。
        TextRow row = withMapper(m -> m.selectText("tx-null"));
        assertThat(row).isNull();
    }

    @Test
    @DisplayName("🔴 JsonTextTypeHandler：列里是空白 ⇒ 读回 null（blank 也当空）")
    void jsonTextHandlerTreatsBlankAsNull() throws Exception {
        rawUpdate("INSERT INTO type_handler_probe (id, text_col) VALUES (?, ?)", "tx-blank", "   ");

        // `parse` 的 `json == null || json.isBlank()` 分支 ⇒ handler 返回 null
        // ⇒ MyBatis 丢弃空行 ⇒ 查询结果为 null（语义同上，不是「没跑」）
        TextRow row = withMapper(m -> m.selectText("tx-blank"));
        assertThat(row).isNull();

        // 对照组：同一张表、同一套 @Results，只要列里有合法 JSON ⇒ 行对象正常返回
        // ⇒ 证「上面那个 null」是 **blank→null 这条分支**造成的，不是 SELECT 装配坏了。
        withMapper(m -> m.insertText("tx-blank-ctl", Map.of("k", "v")));
        TextRow ctl = withMapper(m -> m.selectText("tx-blank-ctl"));
        assertThat(ctl).isNotNull();
        assertThat(ctl.getPayload()).containsExactly(Map.entry("k", "v"));
    }

    @Test
    @DisplayName("🔴 JsonTextTypeHandler：列里是非法 JSON ⇒ 包成 SQLException（消息可读）")
    void jsonTextHandlerWrapsParseFailureIntoSqlException() throws Exception {
        rawUpdate("INSERT INTO type_handler_probe (id, text_col) VALUES (?, ?)", "tx-bad", "{invalid");

        SQLException e = assertThrowsSql(() -> withMapper(m -> m.selectText("tx-bad")));

        assertThat(e.getMessage()).startsWith("Failed to deserialize JSON to Map");
        assertThat(e.getMessage()).contains("{invalid");
        assertThat(e.getCause()).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /**
     * 打开 session → 跑 mapper 调用 → 关 session（三步必须<b>在同一个 session 生命周期内</b>）。
     *
     * <p>⚠️ 曾写成「先取 mapper、返回后再开 try-with-resources」⇒ mapper 用的 session
     * 在方法返回时就被关掉 ⇒ 调用时抛 {@code ExecutorException: Executor was closed}。
     */
    private static <T> T withMapper(SqlCall<ProbeMapper, T> call) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return call.apply(session.getMapper(ProbeMapper.class));
        }
    }

    @FunctionalInterface
    private interface SqlCall<M, T> {
        T apply(M mapper);
    }

    /** 执行一条原生 JDBC 写；把 {@link SQLException} 原样抛出（供对照组用）。 */
    private static void rawUpdate(String sql, String... args) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String rawTextColumn(String id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT text_col FROM type_handler_probe WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 从 information_schema 读列的<b>实际</b>类型名（证「真的落在 jsonb / text 列上」）。 */
    private static String rawColumnType(String id, String column) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT data_type FROM information_schema.columns "
                             + "WHERE table_name = 'type_handler_probe' AND column_name = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * 把任意异常链里<b>第一个</b> {@link SQLException} 抠出来。
     *
     * <p>MyBatis 会把 JDBC 异常包一层：写路径包成 {@code ExecutorException("Error executing update ...")}，
     * 读路径包成 {@code ExecutorException("Error executing query ...")}
     * ⇒ 直接 {@code assertThrows(SQLException.class)} 会**永远红** ⇒ 必须取根因。
     */
    private static SQLException assertThrowsSql(ThrowingRunnable action) {
        Throwable thrown = null;
        try {
            action.run();
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown)
                .as("期望抛出异常链中含 SQLException，实际没抛")
                .isNotNull();

        Throwable cur = thrown;
        while (cur != null && !(cur instanceof SQLException)) {
            cur = cur.getCause();
        }
        assertThat(cur).as("异常链里没有 SQLException").isInstanceOf(SQLException.class);
        return (SQLException) cur;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ══════════════════════════════════════════════════════════════════
    // 探针 Mapper —— 走的是**生产同款**的 MyBatis 路径
    // （显式 typeHandler= ⇒ 与 MyBatis-Plus 的 autoResultMap / @TableField(typeHandler=) 同形）
    // ══════════════════════════════════════════════════════════════════

    interface ProbeMapper {

        @Insert("INSERT INTO type_handler_probe (id, jsonb_col) "
                + "VALUES (#{id}, #{payload,typeHandler=com.rag.common.persistence.JsonbTypeHandler})")
        int insertJsonb(@Param("id") String id, @Param("payload") String payload);

        @Select("SELECT jsonb_col FROM type_handler_probe WHERE id = #{id}")
        @Results(id = "jsonbRow", value = @Result(
                property = "payload", column = "jsonb_col",
                typeHandler = com.rag.common.persistence.JsonbTypeHandler.class))
        JsonbRow selectJsonb(@Param("id") String id);

        @Insert("INSERT INTO type_handler_probe (id, text_col) "
                + "VALUES (#{id}, #{payload,jdbcType=VARCHAR,"
                + "typeHandler=com.rag.common.persistence.JsonTextTypeHandler})")
        int insertText(@Param("id") String id, @Param("payload") Map<String, String> payload);

        @Select("SELECT text_col FROM type_handler_probe WHERE id = #{id}")
        @Results(id = "textRow", value = @Result(
                property = "payload", column = "text_col",
                typeHandler = com.rag.common.persistence.JsonTextTypeHandler.class))
        TextRow selectText(@Param("id") String id);
    }

    /** 读回载体（MyBatis 需要一个可 set 的 bean ⇒ 不能直接返回 Map，否则会被塞成 {@code {"payload": {...}}}）。 */
    public static class JsonbRow {
        private String payload;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    /** 读回载体（text 列 ↔ Map）。 */
    public static class TextRow {
        private Map<String, String> payload;

        public Map<String, String> getPayload() {
            return payload;
        }

        public void setPayload(Map<String, String> payload) {
            this.payload = payload;
        }
    }
}
