package com.rag.favorite.mapper;

import com.rag.favorite.FavoriteServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴🔴 真 PG 上的 <b>DDL 双侧对拍守门</b>（C 侧那一半）。
 *
 * <h2>它守什么</h2>
 * <p>本类跑的是<b>真 Flyway</b> 建出来的 schema（与 {@code FavoriteSchemaParityTest} 同款上下文），
 * 对照的 Go 侧模型定义在 {@code service/favorite/rpc/internal/model/data_model.go:5-11}，
 * 然后用<b>与 Go 侧探针共用同一份</b>查询（{@code tools/ddl-parity-probe/favorite/sql/dump.sql}
 * 与 {@code names.sql}）导出规范化 dump，再与<b>冻结的</b>
 * {@code tools/ddl-parity-probe/favorite/expected.txt} 比对。
 *
 * <h2>为什么要两侧共用同一份查询</h2>
 * <p>若 Go 侧与 RAG 侧各写各的查询，<b>查询本身的差异</b>就会伪装成 schema 差异
 * ⇒ 结论会「各说一半」⇒ 对拍失效（AGENTS.md §3.3）。
 *
 * <h2>expected.txt 的两段</h2>
 * <ul>
 *   <li>{@code MAIN|<sha256>} —— 两侧<b>共同</b>的语义内容（列属性 / 索引语义 / 约束语义 /
 *       触发器语义 / 函数体哈希 / 表）的指纹 ⇒ 变了就是漂移。</li>
 *   <li>{@code OBS\t…} —— <b>已知</b>且<b>已登记</b>的差异（命名层 12 条 + 语义层 1 条）
 *       ⇒ 断言「这些差异<b>仍然存在</b>且<b>没有变样</b>」
 *       ⇒ 哪天差异消失了，测试会红，逼人重评登记，而不是让它静默漂移。</li>
 * </ul>
 *
 * <h2>🔴 C 侧为什么「探针资产」是<b>内嵌</b>的而不是读文件</h2>
 * <p>B 侧从仓库的 {@code tools/ddl-parity-probe/favorite/} 读三份文件
 * （{@code expected.txt} + {@code sql/dump.sql} + {@code sql/names.sql}）。
 * <br>⇒ RAG-V1 仓<b>没有</b>这组资产（它的 {@code tools/parity-probe/} 下只有 article 域），
 * 而本次交付的<b>写权面被限定为 5 个测试文件</b>
 * ⇒ ⇒ 三份资产<b>逐字内嵌</b>在本文件里（{@link #FROZEN_EXPECTED} / {@link #DUMP_SQL} /
 * {@link #NAMES_SQL}）⇒ <b>内容与 B 侧逐字一致</b>（未改一个字符）。
 *
 * <h2>§3.2 三段式留痕（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>🔴 <b>禁 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 *           ⇒ B 侧 {@code FavoriteDdlParityTest.java:59} 有该注解
 *           ⇒ C 侧<b>删掉</b> ⇒ Docker 不可用时本类<b>直接红</b>（不是静默跳过 = 假绿）。</li>
 *       <li>🔴 探针资产<b>内嵌</b>（见上）⇒ 不再依赖仓库里的 {@code tools/} 目录。</li>
 *       <li>🔴 容器改为<b>共享单例</b>（见 {@link FavoritePgParityTestBase}），
 *           B 侧是每类一个 {@code @Container}（{@code FavoriteDdlParityTest.java:72-78}）。</li>
 *       <li>🔴 <b>每个事实带行号</b>（AGENTS.md §3.3.3 强制三条 ①）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 断言口径逐条未改：MAIN 指纹（sha256）比对、
 *       13 条 OBS（12 命名 + 1 语义）逐条核对、语义差异「仍然存在」的反向断言。
 *       <b>期望值全部沿用 B 侧</b>（{@link #FROZEN_EXPECTED} 逐字来自 B 侧 {@code expected.txt}）。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/FavoriteDdlParityTest.java:1-209}；
 *       探针资产 {@code /Users/pr/Documents/java&golang/RAG/tools/ddl-parity-probe/favorite/}
 *       （{@code expected.txt} / {@code sql/dump.sql} / {@code sql/names.sql}）。</li>
 * </ol>
 *
 * <p>⚠️ Go 侧那一半由 {@code tools/ddl-parity-probe/favorite/run.sh} 守（要起 Go toolchain +
 * 真 PG + 真源 GORM）⇒ 探针<b>没有</b>挂进 {@code mvn test}（同 {@code errmsg-probe} 的纪律）。
 * <p>⚠️ 本类是 <b>C 侧那一半</b>：进 {@code mvn test} ⇒ CI 里也能守住 C 侧的 DDL 不被改坏。
 */
@SpringBootTest(
        classes = FavoriteServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:1",
                "grpc.client.article-rpc.address=static://127.0.0.1:1",
                // 🔴 C 侧独有：FavoriteApiProperties#verifyAccessSecret 是 fail-closed 的
                // （B 出处：B 侧 favorite-rpc 的 FavoriteApiProperties.java:79-113）
                "favorite-api.user-auth.access-secret=rag-v1-favorite-parity-test-secret",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN"
        })
class FavoriteDdlParityTest extends FavoritePgParityTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * <b>冻结的期望</b> —— 逐字来自 B 侧
     * {@code /Users/pr/Documents/java&golang/RAG/tools/ddl-parity-probe/favorite/expected.txt}。
     *
     * <p>⚠️ 行内 {@code \t} 是<b>真 TAB</b>（B 侧 {@code expected.txt} 用 TAB 分隔 7 段：
     * {@code OBS / kind / 对象种类 / 表 / payload / Go 侧名字 / RAG 侧名字}）。
     */
    private static final String[] FROZEN_EXPECTED = {
        // B 侧 tools/ddl-parity-probe/favorite/expected.txt:1  # DDL 双侧活体对拍 · favorite 域 · 冻结期望
        // B 侧 tools/ddl-parity-probe/favorite/expected.txt:2  # 生成方式：bash tools/ddl-parity-probe/favorite/run.sh（首次运行自动产出）
        // B 侧 tools/ddl-parity-probe/favorite/expected.txt:3  # 判定基准：Go 侧 = sql-first（运维先跑 001..004，应用后起 ⇒ 真源文档指定的顺序）
        "MAIN|fd69ec7e5854b1cc37734c706fcdebf8c39bcf45a1b9765384d6725b4e6854ab",
        "OBS\tname\tcon\tfavorite_fact_outbox\tc|CHECK ((aggregate_version = ANY (ARRAY[(1)::bigint, (2)::bigint])))\tfavorite_fact_outbox_aggregate_version_check\tfavorite_fact_outbox_aggregate_version_range",
        "OBS\tname\tcon\tfavorite_fact_outbox\tu|UNIQUE (favorite_id, aggregate_version)\tfavorite_fact_outbox_favorite_id_aggregate_version_key\tuk_favorite_fact_version",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK (((target_revision IS NULL) OR ((target_type = 'article'::text) AND ((target_revision)::text <> ''::text))))\tfavorite_legacy_fact_marker_check\tfavorite_legacy_marker_revision_scope",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((approval_ref <> ''::text))\tfavorite_legacy_fact_marker_approval_ref_check\tfavorite_legacy_marker_approval_ref_nonempty",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((favorite_id > 0))\tfavorite_legacy_fact_marker_favorite_id_check\tfavorite_legacy_marker_favorite_id_positive",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((folder_id > 0))\tfavorite_legacy_fact_marker_folder_id_check\tfavorite_legacy_marker_folder_id_positive",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((source_snapshot_sha256 ~ '^[a-f0-9]{64}$'::text))\tfavorite_legacy_fact_marker_source_snapshot_sha256_check\tfavorite_legacy_marker_snapshot_hash_format",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((target_id <> ''::text))\tfavorite_legacy_fact_marker_target_id_check\tfavorite_legacy_marker_target_id_nonempty",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((target_type <> ''::text))\tfavorite_legacy_fact_marker_target_type_check\tfavorite_legacy_marker_target_type_nonempty",
        "OBS\tname\tcon\tfavorite_legacy_fact_marker\tc|CHECK ((user_id > 0))\tfavorite_legacy_fact_marker_user_id_check\tfavorite_legacy_marker_user_id_positive",
        "OBS\tname\tidx\tfavorite_folder\tCREATE UNIQUE INDEX <NAME> ON favorite_folder USING btree (folder_id)\tidx_favorite_folder_folder_id\tuk_favorite_folder_folder_id",
        "OBS\tname\tidx\tfavorite_item\tCREATE UNIQUE INDEX <NAME> ON favorite_item USING btree (favorite_id)\tidx_favorite_item_favorite_id\tuk_favorite_item_favorite_id",
        "OBS\tsemantic\tcon\tfavorite_item\tc|CHECK (((target_revision IS NULL) OR (((target_type)::text = 'article'::text) AND ((target_revision)::text <> ''::text))))",
    };

    /**
     * 规范化 schema dump 查询 —— 逐字来自 B 侧
     * {@code tools/ddl-parity-probe/favorite/sql/dump.sql}（两侧共用同一份 ⇒ 查询本身的差异
     * 不会伪装成 schema 差异）。
     */
    private static final String DUMP_SQL = """
                -- DDL 双侧活体对拍 · 规范化 schema dump（**两侧共用同一份**）
                --
                -- ⚠️ 本文件是探针的**唯一真源**：Go 侧用 gorm Raw 读它，RAG 侧用 psql -f 跑它
                --    ⇒ 两侧不可能「各说一半」（若两侧各自写查询，查询本身的差异就会伪装成 schema 差异）。
                --
                -- 输出两类行：
                --   S|<table>|<kind>|<payload>   —— **语义行**：列属性 / 索引语义 / 约束语义 / 触发器语义 / 函数体哈希 / 表
                --                                   ⇒ 不含任何对象名 ⇒ 两侧必须逐行一致
                --   N|<table>|<kind>|<name>      —— **命名行**：对象名
                --                                   ⇒ 两侧可能不同（纯命名层）⇒ 由 run.sh 配对后进 OBS 段
                --
                -- 为什么把「语义」与「名字」拆成两类：
                --   PG 对「内联约束」与「ALTER ADD CONSTRAINT」给的**自动名不同**（`..._check` vs 自定义名），
                --   GORM 对未命名的 uniqueIndex 给 `idx_<table>_<column>` ⇒ 两侧名字天然不同。
                --   ⇒ 若把名字混进语义行 ⇒ 每次都比出一堆**假差异** ⇒ 真差异会被淹没。
                --   ⇒ 拆开后：**语义不一致 = 真漂移**；**仅名字不同 = 命名层差异**（登记后冻结）。
                --
                -- 归一化只做两件事（都是**必做**，否则会出现假差异）：
                --   ① 去掉 `current_database() || '.'` —— 序列名里带库名，两侧库名不同
                --   ② 去掉 `public.` 前缀 —— 两侧 schema 名相同但 PG 输出里可能带前缀

                -- ── 语义行 ────────────────────────────────────────────────────────────────
                SELECT l FROM (

                    -- ① 列：序号 / 类型 / 长度 / 可空性 / DEFAULT
                    SELECT 'S|' || c.table_name || '|col|' || c.ordinal_position || '|' || c.data_type || '|' ||
                           COALESCE(c.character_maximum_length::text, '-') || '|' || c.is_nullable || '|' ||
                           COALESCE(replace(c.column_default, current_database() || '.', ''), '') AS l
                    FROM information_schema.columns c
                    WHERE c.table_schema = current_schema()
                      AND c.table_name NOT LIKE 'flyway_schema_history%'

                    -- ② 索引语义：唯一性 + 列表达式 + 部分索引条件（**去掉了索引名**）
                    UNION ALL
                    SELECT 'S|' || i.tablename || '|idx|' ||
                           replace(
                             replace(
                               replace(i.indexdef, 'INDEX ' || i.indexname || ' ', 'INDEX <NAME> '),
                               current_database() || '.', ''),
                             'public.', '') AS l
                    FROM pg_indexes i
                    WHERE i.schemaname = current_schema()
                      AND i.tablename NOT LIKE 'flyway_schema_history%'

                    -- ③ 约束语义：contype + 定义（pg_get_constraintdef **不含**约束名 ⇒ 天然去名）
                    UNION ALL
                    SELECT 'S|' || c.relname || '|con|' || con.contype::text || '|' ||
                           replace(replace(pg_get_constraintdef(con.oid), current_database() || '.', ''), 'public.', '') AS l
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND c.relname NOT LIKE 'flyway_schema_history%'

                    -- ④ 触发器语义：时机 / 事件 / 粒度（**不可变触发器是行为级的**）
                    UNION ALL
                    SELECT 'S|' || c.relname || '|trg|' ||
                           CASE WHEN (t.tgtype & 2) <> 0 THEN 'BEFORE'
                                WHEN (t.tgtype & 64) <> 0 THEN 'INSTEAD' ELSE 'AFTER' END || '|' ||
                           CASE WHEN (t.tgtype & 4) <> 0 THEN 'INSERT+' ELSE '' END ||
                           CASE WHEN (t.tgtype & 8) <> 0 THEN 'DELETE+' ELSE '' END ||
                           CASE WHEN (t.tgtype & 16) <> 0 THEN 'UPDATE+' ELSE '' END ||
                           CASE WHEN (t.tgtype & 32) <> 0 THEN 'TRUNCATE+' ELSE '' END || '|' ||
                           CASE WHEN (t.tgtype & 1) <> 0 THEN 'ROW' ELSE 'STATEMENT' END AS l
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND NOT t.tgisinternal
                      AND c.relname NOT LIKE 'flyway_schema_history%'

                    -- ⑤ 触发器函数体：**只比哈希**（函数名两侧同名，比全文会引入噪声）
                    UNION ALL
                    SELECT 'S|' || p.proname || '|func|' || md5(p.prosrc) AS l
                    FROM pg_proc p
                    JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = current_schema()

                    -- ⑥ 表：表集合不一致是最粗的漂移
                    UNION ALL
                    SELECT 'S|' || c.relname || '|tbl|' ||
                           CASE c.relkind WHEN 'r' THEN 'table' WHEN 'S' THEN 'sequence' ELSE c.relkind::text END AS l
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND c.relkind IN ('r', 'S')
                      AND c.relname NOT LIKE 'flyway_schema_history%'
                ) t ORDER BY 1;
                """;

    /**
     * 命名行查询 —— 逐字来自 B 侧
     * {@code tools/ddl-parity-probe/favorite/sql/names.sql}。
     */
    private static final String NAMES_SQL = """
                -- DDL 双侧活体对拍 · **命名行**（与 sql/dump.sql 配对使用）
                --
                -- ⚠️ 同样**两侧共用同一份**：Go 侧用 gorm Raw 读它，RAG 侧用 psql -f 跑它。
                --
                -- 输出格式：N|<table>|<kind>|<name>|<payload>
                --   payload = **配对键**：与 sql/dump.sql 里同名 kind 的语义 payload **逐字相同**
                --   ⇒ 两侧按 (table, kind, payload) 就能配对 ⇒ 名字不同 = 命名层差异，
                --     而 payload 不同 = 语义差异（真漂移）。
                --
                -- ⚠️ 归一化（与 dump.sql 完全一致，否则配不上）：
                --   ① 去掉 `current_database() || '.'`
                --   ② 去掉 `public.` 前缀
                --   ③ 索引定义里的索引名替换成 `<NAME>` 占位符

                SELECT l FROM (
                    SELECT 'N|' || i.tablename || '|idx|' || i.indexname || '|' ||
                           replace(
                             replace(
                               replace(i.indexdef, 'INDEX ' || i.indexname || ' ', 'INDEX <NAME> '),
                               current_database() || '.', ''),
                             'public.', '') AS l
                    FROM pg_indexes i
                    WHERE i.schemaname = current_schema()
                      AND i.tablename NOT LIKE 'flyway_schema_history%'

                    UNION ALL
                    SELECT 'N|' || c.relname || '|con|' || con.conname || '|' || con.contype::text || '|' ||
                           replace(replace(pg_get_constraintdef(con.oid), current_database() || '.', ''), 'public.', '') AS l
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND c.relname NOT LIKE 'flyway_schema_history%'

                    UNION ALL
                    SELECT 'N|' || c.relname || '|trg|' || t.tgname || '|' ||
                           CASE WHEN (t.tgtype & 2) <> 0 THEN 'BEFORE'
                                WHEN (t.tgtype & 64) <> 0 THEN 'INSTEAD' ELSE 'AFTER' END || '|' ||
                           CASE WHEN (t.tgtype & 4) <> 0 THEN 'INSERT+' ELSE '' END ||
                           CASE WHEN (t.tgtype & 8) <> 0 THEN 'DELETE+' ELSE '' END ||
                           CASE WHEN (t.tgtype & 16) <> 0 THEN 'UPDATE+' ELSE '' END ||
                           CASE WHEN (t.tgtype & 32) <> 0 THEN 'TRUNCATE+' ELSE '' END || '|' ||
                           CASE WHEN (t.tgtype & 1) <> 0 THEN 'ROW' ELSE 'STATEMENT' END AS l
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND NOT t.tgisinternal
                      AND c.relname NOT LIKE 'flyway_schema_history%'
                ) t ORDER BY 1;
                """;

    /** 执行探针查询并归一化（B 侧 {@code FavoriteDdlParityTest.java:112-121}）。 */
    private List<String> dump(String sql) {
        List<String> out = new ArrayList<>();
        for (String row : jdbcTemplate.queryForList(sql, String.class)) {
            if (row != null && !row.isBlank()) {
                out.add(row.trim());
            }
        }
        return out;
    }

    /**
     * B 出处：B 侧 {@code FavoriteDdlParityTest.java:123-194}。
     */
    @Test
    @DisplayName("🔴 真 Flyway 建出的 schema 必须与【冻结的 expected.txt】一致（语义 + 已知差异）")
    void ddlMatchesFrozenExpectation() {
        List<String> semLines = dump(DUMP_SQL);
        List<String> nameLines = dump(NAMES_SQL);

        Set<String> sem = new TreeSet<>(semLines);
        // 命名行：N|table|kind|name|payload… ⇒ key = (table, kind, payload)
        Map<String, String> names = new LinkedHashMap<>();
        for (String line : nameLines) {
            String[] p = line.split("\\|", -1);
            if (p.length < 5) {
                continue;
            }
            String payload = String.join("|", Arrays.copyOfRange(p, 4, p.length));
            // ⚠️ 分隔符用源码转义 "\0"（= U+0000），**不是真 NUL 字节** ——
            //    真 NUL 会让 git 把本文件当【二进制】⇒ 三方合并静默揉坏且不报冲突。
            //    运行时完全等价（Java 八进制转义 \0 = U+0000 字符）。
            names.put(p[1] + "\0" + p[2] + "\0" + payload, p[3]);
        }

        // ── 读冻结期望 ───────────────────────────────────────────
        String expectedMain = null;
        List<String> expectedObs = new ArrayList<>();
        for (String line : FROZEN_EXPECTED) {
            if (line.startsWith("MAIN|")) {
                expectedMain = line;
            } else if (line.startsWith("OBS\t")) {
                expectedObs.add(line);
            }
        }
        assertTrue(expectedMain != null, "expected.txt 里没有 MAIN|<sha256> 行");
        assertEquals(13, expectedObs.size(), "expected.txt 的 OBS 段应当是 13 条（12 命名 + 1 语义）");

        // ── ① 语义差异（C 侧比 Go 多出的对象）必须【仍然存在】 ─────────
        Set<String> excluded = new TreeSet<>();
        for (String obs : expectedObs) {
            String[] p = obs.split("\t", -1);
            if ("semantic".equals(p[1])) {
                String sLine = "S|" + p[3] + "|" + p[2] + "|" + p[4];
                assertTrue(sem.contains(sLine),
                        "expected.txt 登记的语义差异【已消失】：\n  " + sLine
                                + "\n⇒ 这条有意偏离不再成立 ⇒ 必须重评登记（而不是让它静默漂移）");
                excluded.add(sLine);
            }
        }

        // ── ② MAIN 指纹 ──────────────────────────────────────────
        Set<String> mainLines = new TreeSet<>(sem);
        mainLines.removeAll(excluded);
        String actualMain = "MAIN|" + sha256(String.join("\n", mainLines));

        assertEquals(expectedMain, actualMain,
                "🔴 C 侧的 DDL 语义内容变了（与冻结值不符）\n"
                        + "⇒ 要么是有人改了迁移脚本 / 加了列，要么是探针的归一化口径变了\n"
                        + "⇒ **改 DDL 前必须先确认 B 侧是否也变了**（真源已冻结 ⇒ B 侧不会变）");

        // ── ③ 命名差异：C 侧的【名字】必须与登记的一致 ──────────────
        for (String obs : expectedObs) {
            String[] p = obs.split("\t", -1);
            if (!"name".equals(p[1])) {
                continue;
            }
            String key = p[3] + "\0" + p[2] + "\0" + p[4];
            String ragName = p[6];
            assertEquals(ragName, names.get(key),
                    "🔴 命名差异的对象没了或改名了：key=" + key + "\n"
                            + "⇒ 若是「统一了索引/约束名」⇒ 那是**行为变化**（见 DEV-FAV-RPC-006）⇒ 需重评");
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
