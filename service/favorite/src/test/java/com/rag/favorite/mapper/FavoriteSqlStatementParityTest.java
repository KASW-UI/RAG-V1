package com.rag.favorite.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴🔴🔴 <b>SQL 语句层</b>的双侧对照守门 —— favorite 域（<b>静态</b>形态）。
 *
 * <h2>它补的是什么缺口</h2>
 * <p>B 侧台账 {@code docs/DRIFT/favorite/rpc.md} §29 的最后一层：
 * <b>DDL 层</b>（{@code tools/ddl-parity-probe/favorite/}）、
 * <b>错误码契约层</b>（{@code tools/errmsg-probe/}）、
 * <b>事务语义层</b> 三层都已闭，剩下的就是<b>语句层</b>：
 * 「两侧为了让同一个业务结果，<b>各自对数据库说了什么话</b>」。
 *
 * <p>这句话之所以重要，是因为<b>落库等价性只证伪「结果一样」</b>，
 * 证不了<b>「过程一样」</b>：少一句 {@code FOR UPDATE}、多一次全表扫、
 * 事务边界切得不一样 —— 这些都可能<b>在单次运行里看不出差别</b>，
 * 却会在<b>并发</b>下变成死锁或脏写。
 *
 * <h2>🔴 C 侧形态与 B 侧形态的差异（必读）</h2>
 * <table border="1">
 *   <caption>B 侧（活体双侧对拍） vs C 侧（静态冻结对照）</caption>
 *   <tr><th></th><th>B 侧</th><th>C 侧（本类）</th></tr>
 *   <tr><td>对照对象</td>
 *       <td>Go 真源<b>活进程</b>（靠 PG 服务端日志 + {@code application_name} 分流 +
 *           {@code SELECT 'SQLMARK::…'} marker 逐用例分段）</td>
 *       <td><b>B 侧 Mapper 源码的冻结转写</b>（{@link #B_SIDE_SQL}）</td></tr>
 *   <tr><td>取证位置</td><td>PG 服务端日志（零侵入，两侧都不改 logger）</td>
 *       <td>C 侧 Mapper 接口上的 <b>MyBatis 注解</b>（运行时反射读 {@code value()}）</td></tr>
 *   <tr><td>依赖</td><td>Go toolchain + 真 PG + 探针编排脚本</td>
 *       <td><b>无</b>（不依赖 Docker / PG / Go）⇒ 可进普通 {@code mvn test}</td></tr>
 * </table>
 * <p>⚠️ <b>为什么 C 侧只能是静态形态</b>：RAG-V1 仓里<b>没有 Go 侧</b>
 * ⇒ 「Go 活进程 vs C 侧活进程」的活体对拍在本仓<b>无法复现</b>
 * ⇒ 而 D13/D16 已定「<b>B 侧是唯一 oracle</b>」⇒ 把 B 侧的语句层资产<b>冻结</b>成本仓的判定基准，
 * 是与该决策<b>同向</b>的（且比「拿 RAG-V1 自己的实现当基准」的自举陷阱强得多）。
 *
 * <h2>🔴 残留风险（必须留档）</h2>
 * <p>本类的 oracle 是<b>冻结转写</b> ⇒ 若<b>B 侧</b>日后改了 Mapper 的 SQL，
 * 本类<b>不会自动知道</b>（要等冻结表被刷新）⇒
 * ⇒ <b>B 侧改动必须同步刷新本文件的 {@link #B_SIDE_SQL}</b>。
 * 这是「静态对照」相对「活体对拍」的<b>唯一</b>代价，已在此留档。
 *
 * <h2>本类<b>不</b>做什么</h2>
 * <ul>
 *   <li><b>不</b>断言两侧 SQL 文本相同 —— 本类<b>就是</b>断言 C 侧 SQL 与 B 侧<b>逐字</b>相同
 *       （B 侧的 Mapper 是<b>手写 SQL</b>，不是 ORM 生成的 ⇒ 没有「库级噪声」要滤）。</li>
 *   <li><b>不</b>替代 DDL 层对拍（{@code FavoriteDdlParityTest}）与
 *       schema 层校验（{@code FavoriteSchemaParityTest}）—— 三层互补。</li>
 * </ul>
 *
 * <h2>§3.2 三段式留痕（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>🔴 <b>形态</b>：B 侧是<b>活体双侧对拍</b>
 *           （{@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/sql/
 *           FavoriteSqlStatementParityTest.java:1-518}），
 *           C 侧改为<b>静态冻结对照</b>（理由见上）。</li>
 *       <li>🔴 <b>多一条「变异自证」用例</b>（{@link #comparatorIsNotTautological()}）：
 *           证明比较器<b>不是恒真</b>（AGENTS.md §3.3.3「能证测试不是假绿」）。</li>
 *       <li>🔴 <b>多一条「方法集合守恒」断言</b>：C 侧多一条/少一条 SQL 语句都必须红
 *           （AGENTS.md §3.3.3「脚本批量改 N 处必须配守恒断言」）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 判定的<b>对象</b>没变：仍是「两侧对数据库说了什么话」；
 *       变的只是<b>取证手段</b>（服务端日志 → 注解反射）与<b>对照基准</b>（Go 活进程 → B 侧源码冻结）。
 *       判定强度：<b>逐字相等</b>（比 B 侧的「语义签名」更严）。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/sql/
 *       FavoriteSqlStatementParityTest.java:1-518}；
 *       B 侧被测对象 {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/mapper/*.java}
 *       （逐条的行号见 {@link #B_SIDE_SQL} 的行内注释）。</li>
 * </ol>
 */
class FavoriteSqlStatementParityTest {

    /**
     * <b>B 侧 Mapper 的 SQL 冻结表</b> —— 逐条转写自 B 侧
     * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/mapper/}。
     *
     * <p>⚠️ <b>转写方式</b>：由脚本从 B 侧源码按 Java 文本块的
     * 「incidental whitespace stripping」规则逐条抽出（<b>未手工誊写</b> ⇒ 无誊写误差），
     * 每条附 B 侧 {@code 文件:行号}。
     */
    private static final Map<String, String> B_SIDE_SQL = new LinkedHashMap<>();

    static {
        // B 侧 FavoriteFolderMapper.java:161  @<Delete>
        B_SIDE_SQL.put("FavoriteFolderMapper.deleteByFolderId", "DELETE FROM favorite_folder WHERE folder_id = #{folderId}");
        // B 侧 FavoriteFolderMapper.java:85  @<Select>
        B_SIDE_SQL.put("FavoriteFolderMapper.findByFolderId", "SELECT id, folder_id, user_id, name, create_time, update_time\nFROM favorite_folder\nWHERE folder_id = #{folderId}\nLIMIT 1\n");
        // B 侧 FavoriteFolderMapper.java:105  @<Select>
        B_SIDE_SQL.put("FavoriteFolderMapper.findByFolderIdForUpdate", "SELECT id, folder_id, user_id, name, create_time, update_time\nFROM favorite_folder\nWHERE folder_id = #{folderId}\nLIMIT 1\nFOR UPDATE\n");
        // B 侧 FavoriteFolderMapper.java:76  @<Select>
        B_SIDE_SQL.put("FavoriteFolderMapper.findByUserId", "SELECT id, folder_id, user_id, name, create_time, update_time\nFROM favorite_folder\nWHERE user_id = #{userId}\nORDER BY create_time DESC\n");
        // B 侧 FavoriteFolderMapper.java:115  @<Select>
        B_SIDE_SQL.put("FavoriteFolderMapper.findByUserIdAndName", "SELECT id, folder_id, user_id, name, create_time, update_time\nFROM favorite_folder\nWHERE user_id = #{userId} AND name = #{name}\nLIMIT 1\n");
        // B 侧 FavoriteFolderMapper.java:59  @<Insert>
        B_SIDE_SQL.put("FavoriteFolderMapper.insertFolder", "INSERT INTO favorite_folder (folder_id, user_id, name, create_time, update_time)\nVALUES (#{folderId}, #{userId}, #{name}, #{createTime}, #{updateTime})\n");
        // B 侧 FavoriteFolderMapper.java:145  @<Update>
        B_SIDE_SQL.put("FavoriteFolderMapper.updateNameByFolderId", "UPDATE favorite_folder\nSET name = #{name}, update_time = #{updateTime}\nWHERE folder_id = #{folderId}\n");
        // B 侧 FavoriteItemMapper.java:125  @<Delete>
        B_SIDE_SQL.put("FavoriteItemMapper.deleteByFavoriteId", "DELETE FROM favorite_item WHERE favorite_id = #{favoriteId}");
        // B 侧 FavoriteItemMapper.java:134  @<Delete>
        B_SIDE_SQL.put("FavoriteItemMapper.deleteByFolderId", "DELETE FROM favorite_item WHERE folder_id = #{folderId}");
        // B 侧 FavoriteItemMapper.java:45  @<Select>
        B_SIDE_SQL.put("FavoriteItemMapper.findByFavoriteId", "SELECT id, favorite_id, folder_id, user_id, target_id, target_type,\n       target_revision, title, cover, create_time\nFROM favorite_item\nWHERE favorite_id = #{favoriteId}\nLIMIT 1\n");
        // B 侧 FavoriteItemMapper.java:35  @<Select>
        B_SIDE_SQL.put("FavoriteItemMapper.findByFolderId", "SELECT id, favorite_id, folder_id, user_id, target_id, target_type,\n       target_revision, title, cover, create_time\nFROM favorite_item\nWHERE folder_id = #{folderId}\nORDER BY create_time DESC\n");
        // B 侧 FavoriteItemMapper.java:61  @<Select>
        B_SIDE_SQL.put("FavoriteItemMapper.findByFolderTarget", "SELECT id, favorite_id, folder_id, user_id, target_id, target_type,\n       target_revision, title, cover, create_time\nFROM favorite_item\nWHERE folder_id = #{folderId} AND target_id = #{targetId} AND target_type = #{targetType}\nLIMIT 1\n");
        // B 侧 FavoriteItemMapper.java:100  @<Insert>
        B_SIDE_SQL.put("FavoriteItemMapper.insertItem", "INSERT INTO favorite_item\n    (favorite_id, folder_id, user_id, target_id, target_type, target_revision, title, cover,\n     create_time)\nVALUES (#{favoriteId}, #{folderId}, #{userId}, #{targetId}, #{targetType},\n        #{targetRevision}, #{title}, #{cover},\n        #{createTime})\n");
        // B 侧 FavoriteItemMapper.java:155  @<Select>
        B_SIDE_SQL.put("FavoriteItemMapper.lockAllByFolderIdOrdered", "SELECT id, favorite_id, folder_id, user_id, target_id, target_type,\n       target_revision, title, cover, create_time\nFROM favorite_item\nWHERE folder_id = #{folderId}\nORDER BY favorite_id\nFOR UPDATE\n");
        // B 侧 FavoriteItemMapper.java:171  @<Select>
        B_SIDE_SQL.put("FavoriteItemMapper.lockByFavoriteId", "SELECT id, favorite_id, folder_id, user_id, target_id, target_type,\n       target_revision, title, cover, create_time\nFROM favorite_item\nWHERE favorite_id = #{favoriteId}\nLIMIT 1\nFOR UPDATE\n");
        // B 侧 FavoriteFactOutboxMapper.java:117  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.claimNextDeliverable", "SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,\n       technical_receipt_id, technical_input_hash, technical_offset,\n       technical_received_at, delivered_at, created_at, updated_at\nFROM favorite_fact_outbox\nWHERE status IN (0, 2)\n  AND (aggregate_version = 1 OR EXISTS (\n        SELECT 1 FROM favorite_fact_outbox predecessor\n        WHERE predecessor.favorite_id = favorite_fact_outbox.favorite_id\n          AND predecessor.aggregate_version = 1\n          AND predecessor.status = 1))\nORDER BY created_at, event_id\nFOR UPDATE SKIP LOCKED\nLIMIT 1\n");
        // B 侧 FavoriteFactOutboxMapper.java:324  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.countLiveItemsByFavoriteId", "SELECT count(*) FROM favorite_item WHERE favorite_id = #{favoriteId}");
        // B 侧 FavoriteFactOutboxMapper.java:339  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.countOrphanRetracts", "SELECT count(*) FROM favorite_fact_outbox retract\nWHERE retract.aggregate_version = 2\n  AND NOT EXISTS (\n      SELECT 1 FROM favorite_fact_outbox assertion\n      WHERE assertion.favorite_id = retract.favorite_id\n        AND assertion.aggregate_version = 1)\n");
        // B 侧 FavoriteFactOutboxMapper.java:265  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.countRetractsByFavoriteId", "SELECT count(*) FROM favorite_fact_outbox\nWHERE favorite_id = #{favoriteId} AND aggregate_version = 2\n");
        // B 侧 FavoriteFactOutboxMapper.java:284  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.countRetractsForAuthority", "SELECT count(*) FROM favorite_fact_outbox\nWHERE favorite_id = #{favoriteId} AND aggregate_version = 2\n");
        // B 侧 FavoriteFactOutboxMapper.java:73  @<Insert>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.insertFact", "INSERT INTO favorite_fact_outbox\n    (event_id, favorite_id, aggregate_version, payload, status, retry_count,\n     technical_receipt_id, technical_input_hash,\n     created_at, updated_at)\nVALUES (#{eventId}, #{favoriteId}, #{aggregateVersion}, #{payload}::jsonb, 0, 0,\n        '', '',\n        #{createdAt}, #{updatedAt})\n");
        // B 侧 FavoriteFactOutboxMapper.java:245  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.lockAssertByFavoriteId", "SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,\n       technical_receipt_id, technical_input_hash, technical_offset,\n       technical_received_at, delivered_at, created_at, updated_at\nFROM favorite_fact_outbox\nWHERE favorite_id = #{favoriteId} AND aggregate_version = 1\nLIMIT 1\nFOR UPDATE\n");
        // B 侧 FavoriteFactOutboxMapper.java:192  @<Update>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.markAccepted", "UPDATE favorite_fact_outbox\nSET status = 1,\n    technical_receipt_id = #{receiptId},\n    technical_input_hash = #{inputHash},\n    technical_offset = #{offset},\n    technical_received_at = #{receivedAt},\n    delivered_at = #{deliveredAt},\n    updated_at = #{updatedAt}\nWHERE event_id = #{eventId}\n");
        // B 侧 FavoriteFactOutboxMapper.java:155  @<Update>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.markBlocked", "UPDATE favorite_fact_outbox\nSET status = 3, updated_at = #{updatedAt}\nWHERE event_id = #{eventId}\n");
        // B 侧 FavoriteFactOutboxMapper.java:170  @<Update>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.markFailed", "UPDATE favorite_fact_outbox\nSET status = 2, retry_count = retry_count + 1, updated_at = #{updatedAt}\nWHERE event_id = #{eventId}\n");
        // B 侧 FavoriteFactOutboxMapper.java:302  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.selectAssertPredecessor", "SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,\n       technical_receipt_id, technical_input_hash, technical_offset,\n       technical_received_at, delivered_at, created_at, updated_at\nFROM favorite_fact_outbox\nWHERE event_id = #{eventId} AND favorite_id = #{favoriteId} AND aggregate_version = 1\nLIMIT 1\n");
        // B 侧 FavoriteFactOutboxMapper.java:223  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.selectByEventId", "SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,\n       technical_receipt_id, technical_input_hash, technical_offset,\n       technical_received_at, delivered_at, created_at, updated_at\nFROM favorite_fact_outbox\nWHERE event_id = #{eventId}\nLIMIT 1\n");
        // B 侧 FavoriteFactOutboxMapper.java:362  @<Select>
        B_SIDE_SQL.put("FavoriteFactOutboxMapper.selectRowsWithoutAssertion", "SELECT i.favorite_id, i.user_id, i.folder_id, i.target_type, i.target_id, i.target_revision,\n       m.favorite_id AS marker_favorite_id, m.user_id AS marker_user_id,\n       m.folder_id AS marker_folder_id, m.target_type AS marker_target_type,\n       m.target_id AS marker_target_id, m.target_revision AS marker_target_revision,\n       m.source_snapshot_sha256 AS marker_source_snapshot_sha256,\n       m.approval_ref AS marker_approval_ref\nFROM favorite_item i\nLEFT JOIN favorite_fact_outbox assertion\n  ON assertion.favorite_id = i.favorite_id AND assertion.aggregate_version = 1\nLEFT JOIN favorite_legacy_fact_marker m ON m.favorite_id = i.favorite_id\nWHERE assertion.event_id IS NULL\nORDER BY i.favorite_id\n");
        // B 侧 FavoriteLegacyFactMarkerMapper.java:46  @<Select>
        B_SIDE_SQL.put("FavoriteLegacyFactMarkerMapper.lockByFavoriteId", "SELECT favorite_id, user_id, folder_id, target_type, target_id, target_revision,\n       source_snapshot_sha256, approval_ref\nFROM favorite_legacy_fact_marker\nWHERE favorite_id = #{favoriteId}\nLIMIT 1\nFOR UPDATE\n");
    }

    /** C 侧被测的 4 个 Mapper（D7 包压平后的落点）。 */
    private static final List<Class<?>> C_SIDE_MAPPERS = List.of(
            FavoriteFolderMapper.class,
            FavoriteItemMapper.class,
            FavoriteFactOutboxMapper.class,
            FavoriteLegacyFactMarkerMapper.class);

    /**
     * 从 C 侧 Mapper 接口上反射抽出全部 MyBatis SQL 注解。
     *
     * <p>⚠️ 抽的是 {@code @Select/@Insert/@Update/@Delete} 的 {@code value()}
     * ⇒ 那正是 MyBatis <b>真正会发出去</b>的语句（不是「ORM 打算生成什么」）。
     */
    private static Map<String, String> extractCSideSql() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Class<?> mapper : C_SIDE_MAPPERS) {
            for (Method m : mapper.getDeclaredMethods()) {
                String sql = sqlOf(m);
                if (sql != null) {
                    out.put(mapper.getSimpleName() + "." + m.getName(), sql);
                }
            }
        }
        return out;
    }

    private static String sqlOf(Method m) {
        Select select = m.getAnnotation(Select.class);
        if (select != null) {
            return join(select.value());
        }
        Insert insert = m.getAnnotation(Insert.class);
        if (insert != null) {
            return join(insert.value());
        }
        Update update = m.getAnnotation(Update.class);
        if (update != null) {
            return join(update.value());
        }
        Delete delete = m.getAnnotation(Delete.class);
        if (delete != null) {
            return join(delete.value());
        }
        return null;
    }

    /**
     * ⚠️ MyBatis 3.5.x 的注解值是 {@code String[]}（多元素 = 脚本）⇒ 本仓全部是<b>单元素</b>
     * ⇒ 用 {@code \n} 连接后与冻结表的<b>逐字</b>相等。
     */
    private static String join(String[] parts) {
        return String.join("\n", parts);
    }

    /**
     * 归一化：把连续空白折叠成<b>单个空格</b>并去首尾空白。
     *
     * <p>⚠️ 本域 29 条 SQL 里<b>没有任何含空格的字符串字面量</b>
     * （只有 {@code #{…}} 占位符与 {@code insertFact} 里的 {@code ''}）
     * ⇒ 折叠空白<b>不会</b>掩盖任何语义差异。
     */
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 逐条比对，返回差异描述（空列表 = 完全一致）。 */
    private static List<String> diff(Map<String, String> expected, Map<String, String> actual) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) {
                out.add(e.getKey() + ": C 侧<b>没有</b>这条 SQL（B 侧有）");
            } else if (!normalize(e.getValue()).equals(normalize(got))) {
                out.add(e.getKey() + ": SQL 变了\n      B 侧 = " + normalize(e.getValue())
                        + "\n      C 侧 = " + normalize(got));
            }
        }
        return out;
    }

    /**
     * B 出处：B 侧测试 {@code sql/FavoriteSqlStatementParityTest.java:1-518}
     * （「两侧为了让同一个业务结果，各自对数据库说了什么话」这一层）。
     * 被测对象：B 侧 {@code mapper/FavoriteFolderMapper.java:59-162} 等 4 个 Mapper。
     */
    @Test
    @DisplayName("🔴 C 侧 29 条 Mapper SQL 必须与【B 侧逐字一致】（语句层对拍）")
    void sqlStatementsMatchBSide() {
        Map<String, String> actual = extractCSideSql();

        assertEquals(B_SIDE_SQL.size(), actual.size(),
                "🔴 SQL 条数守恒破了：B 侧 " + B_SIDE_SQL.size() + " 条，C 侧 " + actual.size()
                        + " 条 ⇒ 有人增删了 Mapper 方法（多一条/少一条都算漂移）");

        List<String> differences = diff(B_SIDE_SQL, actual);
        assertTrue(differences.isEmpty(),
                "🔴 C 侧的 Mapper SQL 与 B 侧不一致（" + differences.size() + " 条）：\n  - "
                        + String.join("\n  - ", differences));
    }

    /**
     * 🔴 <b>逐字</b>（不只语义）相等 —— 守住「C 侧 Mapper 的 SQL 是<b>逐字照抄</b> B 侧」这条声明。
     *
     * <p>B 出处：C 侧 4 个 Mapper 的类注释都写着「SQL 逐字照抄，SQL 文本<b>一字未改</b>」；
     * B 侧对应源文件见 {@link #B_SIDE_SQL} 的行内 {@code 文件:行号}。
     */
    @Test
    @DisplayName("🔴 C 侧 Mapper SQL 必须【逐字】（不只是语义）等于 B 侧")
    void sqlStatementsAreByteIdenticalToBSide() {
        Map<String, String> actual = extractCSideSql();
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> e : B_SIDE_SQL.entrySet()) {
            String got = actual.get(e.getKey());
            if (!e.getValue().equals(got)) {
                differences.add(e.getKey() + ": 逐字不等\n      B 侧 = " + e.getValue()
                        + "\n      C 侧 = " + got);
            }
        }
        assertTrue(differences.isEmpty(),
                "🔴 C 侧的 Mapper SQL 不是 B 侧的逐字副本（" + differences.size() + " 条）：\n  - "
                        + String.join("\n  - ", differences));
    }

    /**
     * 🔴 <b>方法集合守恒</b> —— C 侧多一条 / 少一条 SQL 都必须红。
     *
     * <p>为什么单列一条：{@link #sqlStatementsMatchBSide()} 的条数断言已经覆盖了「总数」，
     * 但<b>总数相同、名字不同</b>（比如把 {@code markFailed} 改名成 {@code markFailedV2}）
     * 只会被「逐条比对」发现 ⇒ 本条把<b>名字集合</b>单独钉死，报错更直接。
     */
    @Test
    @DisplayName("🔴 SQL 语句的【名字集合】必须与 B 侧完全一致（不多不少不少改名）")
    void sqlStatementNamesAreConserved() {
        Set<String> expected = new TreeSet<>(B_SIDE_SQL.keySet());
        Set<String> actual = new TreeSet<>(extractCSideSql().keySet());

        Set<String> onlyB = new LinkedHashSet<>(expected);
        onlyB.removeAll(actual);
        Set<String> onlyC = new LinkedHashSet<>(actual);
        onlyC.removeAll(expected);

        assertTrue(onlyB.isEmpty(), "🔴 B 侧有、C 侧没有的 SQL：" + onlyB);
        assertTrue(onlyC.isEmpty(), "🔴 C 侧多出来的 SQL：" + onlyC);
        assertEquals(expected, actual);
    }

    /**
     * 🔴 <b>变异自证</b> —— 证明比较器<b>不是恒真</b>（AGENTS.md §3.3.3）。
     *
     * <p>做法：把冻结表的一条 SQL <b>改掉一个字</b>再跑同一个比较器
     * ⇒ 比较器必须<b>报出差异</b> ⇒ 否则「全绿」就是假绿。
     */
    @Test
    @DisplayName("🔴 比较器自证：把 B 侧 SQL 改一个字 ⇒ 必须报出差异（证明本类不是假绿）")
    void comparatorIsNotTautological() {
        Map<String, String> mutated = new LinkedHashMap<>(B_SIDE_SQL);
        String key = "FavoriteFolderMapper.findByUserId";
        mutated.put(key, mutated.get(key).replace("ORDER BY create_time DESC", "ORDER BY create_time ASC"));

        List<String> differences = diff(mutated, extractCSideSql());
        assertFalse(differences.isEmpty(),
                "🔴 比较器<b>没</b>报出差异 ⇒ 本类的「全绿」是假绿（变异体必须被抓住）");
        assertTrue(differences.get(0).contains(key), "差异描述必须点名被变异的那条：" + differences);
    }

    /**
     * 🔴 <b>抽取器自证</b> —— 证明「反射抽 SQL」这一步<b>真的抽到了东西</b>。
     *
     * <p>为什么必须单列：若抽取器某天坏了（注解换了 / 方法改名 / 反射写错），
     * {@link #diff(Map, Map)} 会因为「两边都空」而<b>全绿</b> ⇒ 假绿。
     */
    @Test
    @DisplayName("🔴 抽取器自证：29 条 SQL 一条都不能少抽（防「反射坏了 ⇒ 静默全绿」）")
    void extractorFindsEveryStatement() {
        Map<String, String> actual = extractCSideSql();
        assertEquals(29, actual.size(), "抽取到的 SQL 条数不对（实际 " + actual.size() + "）");
        for (Map.Entry<String, String> e : actual.entrySet()) {
            assertNotNull(e.getValue(), e.getKey() + " 的 SQL 是 null");
            assertFalse(e.getValue().isBlank(), e.getKey() + " 的 SQL 是空串");
        }
    }
}
