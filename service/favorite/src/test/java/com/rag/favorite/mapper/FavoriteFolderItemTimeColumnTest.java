package com.rag.favorite.mapper;

import com.rag.favorite.FavoriteServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴🔴 真 PG 上的 <b>folder / item 时间列</b>校验 —— 守住「时间戳来自应用层时钟、
 * 而不是 DB 时钟」这条漂移。
 *
 * <h2>B 出处</h2>
 * <p>B 侧测试：{@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/
 * FavoriteFolderItemTimeColumnTest.java:1-219}（逐条移植，见每个用例的 {@code B 出处} 行内注释）。
 *
 * <h2>这条漂移是怎么被查出来的（B 侧原注释照抄）</h2>
 * <p>由 B 侧 {@code tools/sql-statement-probe/favorite/}（SQL 语句层双侧活体对拍）实测查出：
 * Go 侧 {@code favorite_folder.create_time} / {@code update_time} 与
 * {@code favorite_item.create_time} 由 <b>GORM 在应用层赋值</b>（进程时钟），
 * RAG 侧（修前）<b>根本不绑这几列</b> ⇒ DDL 无 DEFAULT ⇒ 落库 <b>NULL</b>
 * ⇒ 对外响应的 {@code created_at} / {@code updated_at} 变成 <b>0</b>。
 *
 * <h2>Go 出处（消费点，不是声明点）</h2>
 * <ul>
 *   <li>struct tag：{@code internal/model/data_model.go:10-11}（folder 两个 auto-time 字段）
 *       / {@code :30}（item 只有一个）</li>
 *   <li>赋值点：{@code gorm.io/gorm@v1.31.1/callbacks/create.go:246}
 *       —— {@code curTime := stmt.DB.NowFunc()} <b>整条 INSERT 只取一次</b>
 *       ⇒ folder 的两个 auto-time 字段拿到<b>同一个时刻</b></li>
 *   <li>填充分支：{@code create.go:302-305}（字段零值时才填）</li>
 *   <li>改名路径：{@code gorm.io/gorm@v1.31.1/callbacks/update.go:236-254}
 *       —— map 形式的 {@code Update} 会<b>自动追加</b> {@code AutoUpdateTime} 字段</li>
 * </ul>
 *
 * <h2>本类断言的六件事</h2>
 * <ol>
 *   <li>folder INSERT 写进去的 {@code create_time} / {@code update_time}
 *       <b>精确等于</b>传入的时刻，且<b>两列相等</b>（Go 的 {@code curTime} 只取一次）</li>
 *   <li>item INSERT 的 {@code create_time} 精确等于传入的时刻</li>
 *   <li>改名 <b>推进</b> {@code update_time} 到传入值（Go 的 autoUpdateTime）</li>
 *   <li>🔴 反证：这些值<b>不等于</b> DB 的 {@code now()}
 *       ⇒ 证明它们来自<b>应用层时钟</b>而不是数据库时钟</li>
 *   <li>🔴 三列在 {@code information_schema} 里<b>没有 DEFAULT</b>
 *       ⇒ 漏绑会落 NULL（<b>不是</b>被静默兜底）⇒ 与 Go 同款地暴露</li>
 *   <li>outbox 的两个投递列落库是<b>空串</b>（Go 的非指针 string 零值）而<b>不是 NULL</b></li>
 * </ol>
 *
 * <h2>§3.2 三段式留痕（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>🔴 <b>禁 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 *           ⇒ B 侧 {@code FavoriteFolderItemTimeColumnTest.java:64} 有该注解
 *           ⇒ C 侧<b>删掉</b> ⇒ Docker 不可用时本类<b>直接红</b>（不是静默跳过 = 假绿）。</li>
 *       <li>🔴 容器改为<b>共享单例</b>（见 {@link FavoritePgParityTestBase}），
 *           B 侧是每类一个 {@code @Container}（{@code FavoriteFolderItemTimeColumnTest.java:77-83}）。</li>
 *       <li>🔴 <b>每个事实带行号</b>（AGENTS.md §3.3.3 强制三条 ①）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 5 条用例的判据、口径、期望值逐条未改
 *       （期望值全部沿用 B 侧：T0/T1 两个固定时刻、三列无 DEFAULT、
 *       两个投递列为空串、{@code update_time} 被改名推进）。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p>⚠️ 与 {@code FavoriteOutboxTimeColumnTest} 同款纪律：<b>真 PG</b>、<b>真 Flyway</b>、
 * <b>真 mapper</b>；不用 H2（timestamptz 的 DEFAULT 与精度行为都测不到）。
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
class FavoriteFolderItemTimeColumnTest extends FavoritePgParityTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FavoriteFolderMapper folderMapper;

    @Autowired
    private FavoriteItemMapper itemMapper;

    @Autowired
    private FavoriteFactOutboxMapper outboxMapper;

    /** 固定时刻（微秒精度 —— PostgreSQL 的 timestamptz 只到微秒）。 */
    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 123456000, ZoneOffset.UTC);
    private static final OffsetDateTime T1 =
            OffsetDateTime.of(2026, 3, 4, 5, 6, 8, 654321000, ZoneOffset.UTC);

    /**
     * 清库。
     *
     * <p>⚠️ 本类的 id 段：folder {@code 9001-9003} / item {@code 7001} /
     * outbox event {@code favorite.9004.v1} ⇒ 与
     * {@code FavoriteOutboxTimeColumnTest} 的 {@code favorite.100x.v1} 段<b>不重叠</b>
     * ⇒ 共用同一个库也不会互相撞（{@link FavoritePgParityTestBase} 的「共用库」约定）。
     */
    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE favorite_fact_outbox CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE favorite_item CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE favorite_folder CASCADE");
    }

    private OffsetDateTime folderCol(long folderId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM favorite_folder WHERE folder_id = ?",
                (rs, i) -> rs.getObject(column, OffsetDateTime.class), folderId);
    }

    private OffsetDateTime itemCol(long favoriteId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM favorite_item WHERE favorite_id = ?",
                (rs, i) -> rs.getObject(column, OffsetDateTime.class), favoriteId);
    }

    /**
     * B 出处：B 侧 {@code FavoriteFolderItemTimeColumnTest.java:137-156}。
     * 被测 SQL：C 侧 {@code FavoriteFolderMapper#insertFolder}
     * （= B 侧 {@code FavoriteFolderMapper.java:59-67} 逐字照抄）。
     */
    @Test
    @DisplayName("🔴 folder INSERT 的 create_time/update_at 必须是【应用层传入的时刻】，不是 DB 的 now()")
    void folderInsertBindsApplicationLayerClock() {
        assertEquals(1, folderMapper.insertFolder(9001L, 2001L, "f1", T0, T0));

        assertEquals(T0, folderCol(9001L, "create_time"),
                "create_time 必须精确等于应用层传入的时刻 —— 修前不绑这一列 ⇒ 落库 NULL ⇒ 对外时间戳 = 0");
        assertEquals(T0, folderCol(9001L, "update_time"),
                "update_time 必须精确等于应用层传入的时刻 —— 修前同样不绑");

        // 🔴 Go 的 curTime 整条 INSERT 只取一次 ⇒ 两个 auto-time 字段拿到**同一个时刻**
        assertEquals(folderCol(9001L, "create_time"), folderCol(9001L, "update_time"),
                "Go 侧两个 auto-time 字段是同一个 curTime ⇒ 应当相等");

        // 🔴 反证：DB 的 now() 与 T0 差着「真实的当前时间」，绝不可能相等
        OffsetDateTime dbNow = jdbcTemplate.queryForObject(
                "SELECT now()", (rs, i) -> rs.getObject(1, OffsetDateTime.class));
        assertTrue(!dbNow.equals(folderCol(9001L, "create_time")),
                "create_time 竟然等于 DB 的 now() ⇒ 说明它来自 DB 时钟而不是应用层时钟");
    }

    /**
     * B 出处：B 侧 {@code FavoriteFolderItemTimeColumnTest.java:158-167}。
     * 被测 SQL：C 侧 {@code FavoriteItemMapper#insertItem}
     * （= B 侧 {@code FavoriteItemMapper.java:100-108} 逐字照抄）。
     */
    @Test
    @DisplayName("🔴 item INSERT 的 create_time 必须是【应用层传入的时刻】")
    void itemInsertBindsApplicationLayerClock() {
        assertEquals(1, folderMapper.insertFolder(9002L, 2002L, "f2", T0, T0));
        assertEquals(1, itemMapper.insertItem(7001L, 9002L, 2002L, "t-1", "post",
                null, "title-1", "cover-1", T0));

        assertEquals(T0, itemCol(7001L, "create_time"),
                "favorite_item.create_time 必须精确等于应用层传入的时刻 —— 修前不绑 ⇒ 落库 NULL");
    }

    /**
     * B 出处：B 侧 {@code FavoriteFolderItemTimeColumnTest.java:169-180}。
     * 被测 SQL：C 侧 {@code FavoriteFolderMapper#updateNameByFolderId}
     * （= B 侧 {@code FavoriteFolderMapper.java:145-152} 逐字照抄）。
     */
    @Test
    @DisplayName("🔴 改名必须推进 update_time（Go 的 map 形式 Update 会自动追加 autoUpdateTime）")
    void renameAdvancesUpdatedAt() {
        assertEquals(1, folderMapper.insertFolder(9003L, 2003L, "old", T0, T0));
        assertEquals(T0, folderCol(9003L, "update_time"));

        assertEquals(1, folderMapper.updateNameByFolderId(9003L, "new", T1));

        assertEquals(T1, folderCol(9003L, "update_time"),
                "改名之后 update_time 必须推进到传入值 —— Go 侧 GORM 的 autoUpdateTime 会刷新它；"
                        + "不刷新就是漂移（修前 RAG 侧只写 name ⇒ updated_at 永远不变）");
    }

    /**
     * B 出处：B 侧 {@code FavoriteFolderItemTimeColumnTest.java:182-199}。
     * DDL 出处：B 侧 {@code V1__create_favorite_folder.sql} / {@code V2__create_favorite_item.sql}
     * （这三列<b>没有</b> DEFAULT）。
     */
    @Test
    @DisplayName("🔴 三列在 schema 上【没有 DEFAULT】⇒ 漏绑会落 NULL，不会被静默兜底")
    void timeColumnsHaveNoDefault() {
        List<String> defaults = jdbcTemplate.queryForList(
                "SELECT table_name || '.' || column_name || '=' || COALESCE(column_default, '<NULL>') "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND ((table_name = 'favorite_folder' AND column_name IN ('create_time','update_time')) "
                        + "  OR (table_name = 'favorite_item' AND column_name = 'create_time')) "
                        + "ORDER BY 1",
                String.class);

        assertEquals(3, defaults.size(), "应当查到三列");
        for (String d : defaults) {
            assertTrue(d.endsWith("=<NULL>"),
                    "时间列不应有 DEFAULT（实测：" + d + "）—— 有 DEFAULT 会掩盖「漏绑」这个回归");
        }
    }

    /**
     * B 出处：B 侧 {@code FavoriteFolderItemTimeColumnTest.java:201-218}。
     * 被测 SQL：C 侧 {@code FavoriteFactOutboxMapper#insertFact}
     * （= B 侧 {@code FavoriteFactOutboxMapper.java:73-82} 逐字照抄）。
     */
    @Test
    @DisplayName("🔴 outbox 的两个投递列必须是【空串】而不是 NULL（Go 侧是非指针 string 的零值）")
    void outboxTechnicalColumnsAreEmptyStringNotNull() {
        assertEquals(1, outboxMapper.insertFact("favorite.9004.v1", 9004L, 1L,
                "{\"schema_version\":1}", T0, T0));

        String receiptId = jdbcTemplate.queryForObject(
                "SELECT technical_receipt_id FROM favorite_fact_outbox WHERE event_id = ?",
                String.class, "favorite.9004.v1");
        String inputHash = jdbcTemplate.queryForObject(
                "SELECT technical_input_hash FROM favorite_fact_outbox WHERE event_id = ?",
                String.class, "favorite.9004.v1");

        assertNotNull(receiptId, "technical_receipt_id 不得是 NULL —— Go 侧是非指针 string ⇒ 零值是 ''");
        assertNotNull(inputHash, "technical_input_hash 不得是 NULL —— Go 侧是非指针 string ⇒ 零值是 ''");
        assertEquals("", receiptId.trim(), "technical_receipt_id 应为空串（bpchar 的补白要 trim 掉）");
        assertEquals("", inputHash.trim(), "technical_input_hash 应为空串（bpchar 的补白要 trim 掉）");
    }
}
