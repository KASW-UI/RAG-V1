package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.support.ArticleRealPgTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：MyBatis-Plus {@code updateStrategy = NOT_NULL} 的写入语义，
 * 与 Go 侧 GORM {@code Save}（{@code Selects="*"}）逐条对拍。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <p>台账里曾经有一条「未展开」的相邻差异写成：
 * <blockquote>Go 的 {@code Save} 更新时使用<b>全部</b>字段（含零值字段）；
 * MP {@code updateById} 默认 {@code NOT_NULL}，<b>只写非 null 字段</b>
 * ⇒ 一个「把某列显式置为零值」的更新，两侧结果不同。</blockquote>
 * <p>⚠️ <b>这个推理是错的，而且错在关键处</b>：MP 的 {@code NOT_NULL} 跳过的只是
 * <b>null</b>，<b>零值（{@code ""} / {@code 0} / {@code false}）照样会被写</b>
 * ⇒ 两侧在零值上<b>本来就一致</b>。
 * <p>⇒ 这条只能靠<b>真的 UPDATE 一行再读库</b>来判；读注解定义<b>证明不了任何事</b>。
 *
 * <h2>Go 侧探针结论（B 侧 {@code ArticleNotNullUpdateStrategyWiringTest} 的类注释逐条抄录）</h2>
 * <pre>
 * 探针 A：只改 title 后 Save（实体从库里读出）⇒ secondary_tags / ext_info 被**原样写回**
 * 探针 B：把 SecondaryTags / ExtInfo 显式置 nil 后 Save ⇒ 写成 **jsonb 'null'**（不是 SQL NULL）
 * 探针 C：把 Brief 置空串、ViewCount 置 0 后 Save ⇒ 写成 **''/0**（不是 NULL，也不是「跳过」）
 * 探针 D：库里的 jsonb 列本来就是 SQL NULL，再 Save 一次 ⇒ SQL NULL 被升级成 jsonb 'null'；
 *         但**读回的值不变**（都是 nil）
 * </pre>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleNotNullUpdateStrategyWiringTest.java:142-274}（6 例可移植）；</li>
 *   <li>Go 侧 {@code gorm@v1.31.1/callbacks/update.go} 的 {@code Selects} 拼接
 *       （{@code finisher_api.go} 的 {@code Selects = append(Selects, "*")}）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把实体上的 {@code @TableField(updateStrategy = …)} 改成 {@code IGNORED}
 *       ⇒ 用例 ③ <b>立刻红</b>（null 列被写成 jsonb 'null'）；</li>
 *   <li>改成 {@code ALWAYS} ⇒ 用例 ① <b>立刻红</b>（零值不再被写）；</li>
 *   <li>若把零值也跳过（把 primitive int 换成 Integer 且策略写成 NOT_EMPTY）
 *       ⇒ 用例 ① <b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类行 id 前缀 {@code 904001}；不依赖 author 过滤（每个用例只看自己那一行）。
 */
class ArticleUpdateStrategyRealPgTest extends ArticleRealPgTestBase {

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
    }

    /**
     * 种一行。
     *
     * <p>⚠️ jsonb 列的值必须<b>内联</b>（{@code '["old"]'::jsonb}）——
     * 用占位符传进去会变成 varchar ⇒ PG 报
     * {@code column "secondary_tags" is of type jsonb but expression is of type character varying}。
     */
    private void seed(String id, String brief, int viewCount, String tags, String ext) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, secondary_tags, ext_info,
                                     status, view_count, created_at, updated_at)
                VALUES ('%s', 'title-%s', %s, 'content-%s', %s, %s, 0, %d, now(), now())
                """.formatted(id, id, brief, id, tags, ext, viewCount));
    }

    // ==================================================================
    // ① 零值照样被写（对拍 Go 探针 C）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：零值（空串 / 0）**照样被写**（NOT_NULL 只跳过 null，不跳过零值）—— 与 Go 探针 C 一致")
    void zeroValuesAreWrittenJustLikeGoSave() {
        seed("904001001", "'brief-904001001'", 7, "'[\"old\"]'::jsonb", "'{\"k\":\"v\"}'::jsonb");

        ArticleEntity a = articleMapper.selectById("904001001");
        assertNotNull(a, "种子行读不到");
        assertEquals("brief-904001001", a.getBrief());
        assertEquals(7, a.getViewCount());

        // 把两个字段置成**零值**（不是 null）
        a.setBrief("");
        a.setViewCount(0);
        articleMapper.updateById(a);

        // ⭐ Go 探针 C：Save 把零值写成 ''/0 —— 不是 NULL，也不是「跳过」
        assertEquals("", jdbcTemplate.queryForObject(
                "SELECT brief FROM article WHERE id = '904001001'", String.class),
                "brief 的零值必须被写下去（Go 侧 Save 写的是 ''）；"
                        + "若仍是旧值，说明 MP 把零值也跳过了 ⇒ 与 Go 不一致");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT view_count FROM article WHERE id = '904001001'", Integer.class),
                "view_count 的零值必须被写下去（Go 侧 Save 写的是 0）");

        // 读回也必须一致
        ArticleEntity after = articleMapper.selectById("904001001");
        assertEquals("", after.getBrief());
        assertEquals(0, after.getViewCount());
    }

    // ==================================================================
    // ② 没碰的 jsonb 列原样写回（对拍 Go 探针 A）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：没碰过的 jsonb 列被**原样写回**（不被写成 null）—— 与 Go 探针 A 一致")
    void untouchedJsonbColumnsAreWrittenBackUnchanged() {
        seed("904001002", "'brief-904001002'", 3, "'[\"old\"]'::jsonb", "'{\"k\":\"v\"}'::jsonb");

        ArticleEntity a = articleMapper.selectById("904001002");
        assertNotNull(a);
        a.setTitle("title-A");
        articleMapper.updateById(a);

        // ⚠️ 断言落在**字段/值**上，不是「查询成功」上
        assertEquals("[\"old\"]", jdbcTemplate.queryForObject(
                "SELECT secondary_tags::text FROM article WHERE id = '904001002'", String.class),
                "secondary_tags 必须原样写回（Go 探针 A 同结论）");
        assertEquals("v", jdbcTemplate.queryForObject(
                "SELECT ext_info->>'k' FROM article WHERE id = '904001002'", String.class),
                "ext_info 必须原样写回（值 k 仍是 v）");

        ArticleEntity after = articleMapper.selectById("904001002");
        assertEquals(List.of("old"), after.getSecondaryTags());
        assertEquals(Map.of("k", "v"), after.getExtInfo());
    }

    // ==================================================================
    // ③ null 的 jsonb 字段被跳过（Go 侧会写 jsonb 'null'）⇒ 存储表示不同
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：实体的 jsonb 字段为 null ⇒ 该列被**跳过**（Go 侧会写 jsonb 'null' ⇒ 存储表示不同）")
    void nullJsonbFieldsAreSkippedInsteadOfWrittenAsJsonNull() {
        seed("904001003", "'brief-904001003'", 1, "'[\"old\"]'::jsonb", "'{\"k\":\"v\"}'::jsonb");

        ArticleEntity a = articleMapper.selectById("904001003");
        assertNotNull(a);
        assertEquals(List.of("old"), a.getSecondaryTags());

        a.setSecondaryTags(null);
        a.setExtInfo(null);
        a.setTitle("title-904001003");
        articleMapper.updateById(a);

        // ⭐ 与 Go 探针 B 的**存储表示**差异：Go 写 jsonb 'null'，RAG 跳过 ⇒ 列保持原值
        assertEquals("[\"old\"]", jdbcTemplate.queryForObject(
                "SELECT secondary_tags::text FROM article WHERE id = '904001003'", String.class),
                "RAG 侧 null ⇒ 该列被跳过（保持原值）；Go 侧会写成 jsonb 'null'"
                        + " ⇒ 这是**存储表示**层面的差异（第 ④ 条会证明它不可观察）");
        assertEquals("v", jdbcTemplate.queryForObject(
                "SELECT ext_info->>'k' FROM article WHERE id = '904001003'", String.class),
                "ext_info 同理");
    }

    // ==================================================================
    // ④ 读回值与 Go 完全一致 ⇒ 该差异不可观察
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：库里的 jsonb 列本就是 SQL NULL 时：RAG 保持 SQL NULL，但**读回与 Go 完全一致** ⇒ 不可观察")
    void sqlNullJsonbColumnsReadBackIdenticallyToGo() {
        // 显式种成 SQL NULL（不是 jsonb 'null'）
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, secondary_tags, ext_info,
                                     status, view_count, created_at, updated_at)
                VALUES ('904001004', 't4', 'brief-904001004', 'content-904001004', NULL, NULL, 0, 0, now(), now())
                """);

        ArticleEntity a = articleMapper.selectById("904001004");
        assertNotNull(a);
        assertNull(a.getSecondaryTags(), "SQL NULL 必须被读成 null");
        assertNull(a.getExtInfo(), "SQL NULL 必须被读成 null");

        a.setTitle("title-904001004");
        articleMapper.updateById(a);

        // 存储表示：RAG 保持 SQL NULL（Go 探针 D 会把它升级成 jsonb 'null'）
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT secondary_tags IS NULL FROM article WHERE id = '904001004'", Boolean.class),
                "RAG 侧 null ⇒ 列被跳过 ⇒ 仍是 SQL NULL（Go 探针 D 是 jsonb 'null'）");
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT ext_info IS NULL FROM article WHERE id = '904001004'", Boolean.class),
                "ext_info 同理");

        // ⭐ 但**读回的值**与 Go 探针 D 完全一致（都是 nil）⇒ API 层面不可观察
        ArticleEntity after = articleMapper.selectById("904001004");
        assertNull(after.getSecondaryTags(),
                "⭐ 读回必须与 Go 一致（Go 侧读回也是 nil）⇒ 这条存储差异**不可观察**");
        assertNull(after.getExtInfo(),
                "⭐ 读回必须与 Go 一致 ⇒ 这条存储差异**不可观察**");
    }

    // ==================================================================
    // ⑤ 过滤面也不受影响（补齐「不可观察」的另一半）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：过滤面同样不可观察：SQL NULL 与 jsonb 'null' 都把行排除掉")
    void filterBehaviourIsIdenticalForSqlNullAndJsonNull() {
        // 一行：secondary_tags 是 SQL NULL
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, secondary_tags, ext_info,
                                     status, view_count, created_at, updated_at)
                VALUES ('904001005', 't5', 'b5', 'c5', NULL, '{}'::jsonb, 0, 0, now(), now())
                """);
        // 另一行：secondary_tags 是 jsonb 'null'（Go 探针 D 存下来的那种）
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, secondary_tags, ext_info,
                                     status, view_count, created_at, updated_at)
                VALUES ('904001006', 't6', 'b6', 'c6', 'null'::jsonb, '{}'::jsonb, 0, 0, now(), now())
                """);

        Integer sqlNullHits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM article WHERE id = '904001005' "
                        + "AND secondary_tags @> CAST('[\"x\"]' AS jsonb)", Integer.class);
        Integer jsonNullHits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM article WHERE id = '904001006' "
                        + "AND secondary_tags @> CAST('[\"x\"]' AS jsonb)", Integer.class);
        assertEquals(0, sqlNullHits,
                "SQL NULL 行的过滤结果必须是「不匹配」（表达式为 NULL）");
        assertEquals(0, jsonNullHits,
                "jsonb 'null' 行的过滤结果必须是「不匹配」（'null' @> '[\"x\"]' = false）"
                        + " ⇒ 两种存储表示在过滤面上**结果相同** ⇒ 差异不可观察");

        // ⭐ 走**真正的 mapper 过滤**（不是另写一份 SQL）：口径必须与手写 SQL 一致
        assertEquals(0L, articleMapper.countAuthorView(null, "[\"x\"]", null),
                "两种存储表示的行都不得被标签过滤命中");
    }
}
