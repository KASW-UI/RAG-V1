package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.support.ArticleRealPgTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：{@code ArticleMapper.countAuthorView} / {@code selectAuthorView}
 * 的 {@code @Select("<script>…")} <b>真的被 MyBatis 解析并执行</b>。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <p>{@code <script>} 里的<b>参数名</b>与 {@code @Param} 的一致性、{@code <if>} 的真假、
 * {@code <choose>} 的分支选择，全都是<b>运行期</b>才解析的：
 * <ul>
 *   <li>参数名写错 ⇒ MyBatis 解析 script 时抛
 *       {@code BindingException: Parameter 'secondaryTag' not found}
 *       ⇒ <b>编译期与静态检查都看不出来</b>；</li>
 *   <li>本仓历史上有过一模一样的缺陷：{@code countAuthorView} 声明的是
 *       {@code @Param("secondaryTagJson")}，而 {@code <if>} 判的是 {@code secondaryTag}
 *       ⇒ 「作者视角 ListArticles <b>100% 报错</b>」。</li>
 * </ul>
 * <p>⇒ 「有一批 Mockito 单测全绿」完全不能证明这段 script 是好的 ——
 * 走 mock 的路径<b>根本不建 SqlSource</b>（B 侧
 * {@code ArticleJsonbResultMapWiringTest.java:296-299} 明确记了这条教训）。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>{@code service/article/article-rpc/.../mapper/ArticleMapper.java:123-220}
 *       （{@code countAuthorView} / {@code selectAuthorView} 的 SQL 文本）；</li>
 *   <li>{@code service/article/rpc/internal/model/article.go:161-176}（排序分支的<b>消费点</b>）；
 *       {@code :145-146}（二级标签过滤 {@code secondary_tags @> '["tag"]'}）；</li>
 *   <li>B 侧测试 {@code ArticleJsonbResultMapWiringTest.java:296-349}
 *       （<b>参数名不一致会抛 BindingException</b> 的实证）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code countAuthorView} 的 {@code <if test="secondaryTagJson …">} 改成
 *       {@code secondaryTag} ⇒ 用例 ① <b>立刻红</b>（BindingException）；</li>
 *   <li>把 {@code selectAuthorView} 的<b>方向</b> {@code <choose>} 从三分支简化成两分支
 *       （删掉 {@code <when test="sortBy == null or sortBy == ''">DESC</when>}）
 *       ⇒ 用例 ② <b>立刻红</b>（默认排序整页颠倒）；</li>
 *   <li>把 {@code countAuthorView} 的 where 条件改成与 {@code selectAuthorView} 不同
 *       ⇒ 用例 ④ <b>立刻红</b>（total ≠ 实际行数）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>单例 PG 容器被所有测试类共用 ⇒ 本类<b>只用自己的作者号</b> {@code 901000001}
 * 作为 {@code author_id} 过滤 ⇒ 别的测试类的行永远进不了本类的 count。
 */
class ArticleMapperScriptRealPgTest extends ArticleRealPgTestBase {

    /** 本类专属作者号 —— 所有查询都带它 ⇒ 与其它测试类的数据天然隔离。 */
    private static final String AUTHOR = "901000001";

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");

        // 三行可见文章 + 一行已软删（deleted_at 非空 ⇒ 不可见）
        insert("901001001", "[\"alpha\",\"beta\"]", "tag-a", 3, "2026-01-01T00:00:00Z", null);
        insert("901001002", "[\"gamma\"]", "tag-b", 9, "2026-02-01T00:00:00Z", null);
        insert("901001003", "[\"alpha\"]", "tag-a", 5, "2026-03-01T00:00:00Z", null);
        insert("901001004", "[\"alpha\"]", "tag-a", 7, "2026-04-01T00:00:00Z", "2026-05-01T00:00:00Z");
    }

    private void insert(String id, String tagsJson, String manualTypeTag, int viewCount,
                        String createdAt, String deletedAt) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at, deleted_at)
                VALUES (?, 'title-' || ?, 'brief', 'content.md', '', ?,
                        CAST(? AS jsonb), ?, 3, ?, 0, 0, 0, '{}'::jsonb, ?::timestamptz, now(), ?::timestamptz)
                """, id, id, manualTypeTag, tagsJson, AUTHOR, viewCount, createdAt, deletedAt);
    }

    // ==================================================================
    // ① <script> 真被解析 + 过滤生效
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：countAuthorView/selectAuthorView 的 <script> 真被解析 —— "
            + "传 secondaryTagJson 不抛 BindingException 且过滤生效")
    void scriptIsReallyParsedAndSecondaryTagFilterWorks() {
        // ⚠️ 这一句就是「真触发」的落点：参数名与 <if> 判据不一致时，MyBatis 在**解析 script 时**
        //    抛 BindingException（不是运行 SQL 时才炸）。
        long total = articleMapper.countAuthorView(null, "[\"alpha\"]", AUTHOR);

        assertEquals(2L, total,
                "secondary_tags @> '[\"alpha\"]' 应命中两行（901001001 / 901001003）；"
                        + "已软删的 901001004 必须被 deleted_at IS NULL 排除");

        List<ArticleEntity> rows = articleMapper.selectAuthorView(
                null, "[\"alpha\"]", AUTHOR, "", false, 10, 0);
        assertEquals(2, rows.size(), "page 与 count 必须同口径");
        assertTrue(rows.stream().allMatch(r -> r.getId().startsWith("901001")),
                "过滤结果不得包含别的作者的行：" + rows.stream().map(ArticleEntity::getId).toList());

        // 反向：只含在另一行上的标签不应命中
        assertEquals(1L, articleMapper.countAuthorView(null, "[\"gamma\"]", AUTHOR),
                "gamma 只在 901001002 上 ⇒ 恰好一行");
        assertEquals(0L, articleMapper.countAuthorView(null, "[\"nope\"]", AUTHOR),
                "不存在的标签 ⇒ 0 行");

        // manualTypeTag 判据（与 secondaryTagJson 共用同一段拼接）
        assertEquals(2L, articleMapper.countAuthorView("tag-a", null, AUTHOR),
                "manual_type_tag='tag-a' ⇒ 两行（001 / 003；004 已删）");
        assertEquals(0L, articleMapper.countAuthorView("no-such-tag", null, AUTHOR),
                "不存在的 manualTypeTag ⇒ 0 行");

        // ⚠️ null 判据是**最常见**的调用形态（proto3 空串 → 归一为 null）
        assertEquals(3L, articleMapper.countAuthorView(null, null, AUTHOR),
                "null 判据同样必须能解析（判据名写错时它同样会抛 BindingException）");
    }

    // ==================================================================
    // ② 默认排序恒为 created_at DESC（方向标志被完全忽略）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：作者视角默认排序恒为 created_at DESC（sortBy=\"\" 时 desc 标志被**完全忽略**）")
    void authorViewDefaultsToCreatedAtDescAndIgnoresDescFlag() {
        // Go 出处（消费点）：internal/model/article.go:161-176
        //   order := "created_at desc"
        //   if opt.SortBy != "" { switch … ; if opt.Desc { order += " desc" } else { order += " asc" } }
        // ⇒ 方向拼接整体位于 `if opt.SortBy != ""` **之内** ⇒ SortBy 为空 ⇒ Desc 被忽略。

        List<ArticleEntity> descFalse = articleMapper.selectAuthorView(
                null, null, AUTHOR, "", false, 10, 0);
        List<ArticleEntity> descTrue = articleMapper.selectAuthorView(
                null, null, AUTHOR, "", true, 10, 0);

        assertEquals(List.of("901001003", "901001002", "901001001"),
                descFalse.stream().map(ArticleEntity::getId).toList(),
                "sortBy=\"\" ⇒ 恒 created_at DESC（最新在前）");
        assertEquals(descFalse.stream().map(ArticleEntity::getId).toList(),
                descTrue.stream().map(ArticleEntity::getId).toList(),
                "🔴 sortBy=\"\" 时 desc 标志必须被**完全忽略**（Go 的方向拼接在 `if opt.SortBy != \"\"` 之内）；"
                        + "若两者顺序不同，说明方向分支被提前到了 sortBy 判空之外");
    }

    // ==================================================================
    // ③ sortBy 非空时 desc 标志生效
    // ==================================================================

    @Test
    @DisplayName("真触发：sortBy=view_count 时 desc 标志生效（false ⇒ ASC / true ⇒ DESC）")
    void authorViewHonoursDescWhenSortByIsPresent() {
        // view_count: 001=3, 002=9, 003=5
        List<ArticleEntity> asc = articleMapper.selectAuthorView(
                null, null, AUTHOR, "view_count", false, 10, 0);
        List<ArticleEntity> desc = articleMapper.selectAuthorView(
                null, null, AUTHOR, "view_count", true, 10, 0);

        assertEquals(List.of("901001001", "901001003", "901001002"),
                asc.stream().map(ArticleEntity::getId).toList(),
                "sort_by=view_count + desc=false ⇒ ASC（Go: order += \" asc\"）");
        assertEquals(List.of("901001002", "901001003", "901001001"),
                desc.stream().map(ArticleEntity::getId).toList(),
                "sort_by=view_count + desc=true ⇒ DESC（Go: order += \" desc\"）");
    }

    // ==================================================================
    // ④ count 与 page 的 where 口径一致
    // ==================================================================

    @Test
    @DisplayName("真触发：selectAuthorView 与 countAuthorView 的 where 口径一致（total == 实际行数）")
    void countAndPageShareTheSameWhereClause() {
        // Go 出处：B 侧用**同一个 db 对象**链式加 where，再分别 Count / Find
        //   （internal/model/article.go:142-159）⇒ 拆成两个方法后口径必须仍然一致。
        String[][] filters = {
                {null, null},
                {"tag-a", null},
                {null, "[\"alpha\"]"},
                {"tag-a", "[\"alpha\"]"},
                {"no-such-tag", "[\"alpha\"]"},
                {null, "[\"gamma\"]"},
        };

        for (String[] filter : filters) {
            String manualTypeTag = filter[0];
            String secondaryTagJson = filter[1];

            long total = articleMapper.countAuthorView(manualTypeTag, secondaryTagJson, AUTHOR);
            List<ArticleEntity> rows = articleMapper.selectAuthorView(
                    manualTypeTag, secondaryTagJson, AUTHOR, "", false, 50, 0);

            assertEquals(total, rows.size(),
                    "count 与 page 的 where 口径必须一致（manualTypeTag=" + manualTypeTag
                            + ", secondaryTagJson=" + secondaryTagJson + "）："
                            + "total=" + total + " 但 page 返回 " + rows.size() + " 行");
        }

        // 非本页数时也要一致（total 是全量，page 是本页）
        long total = articleMapper.countAuthorView(null, null, AUTHOR);
        List<ArticleEntity> firstPage = articleMapper.selectAuthorView(
                null, null, AUTHOR, "", false, 2, 0);
        assertEquals(3L, total, "全量 count = 3 行");
        assertEquals(2, firstPage.size(), "limit=2 ⇒ 本页 2 行");
        assertNotNull(firstPage.get(0), "本页首行必须存在");
        assertFalse(firstPage.get(0).getId().isEmpty(), "首行 id 不得为空");
    }
}
