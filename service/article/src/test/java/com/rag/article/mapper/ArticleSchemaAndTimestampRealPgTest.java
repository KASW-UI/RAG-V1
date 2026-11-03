package com.rag.article.mapper;

import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.support.ArticleRealPgTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：article 域 DDL 的<b>三条强制手段</b>（不可变触发器 / CHECK 约束 / Flyway 真跑）
 * ＋ {@code updated_at} 的<b>两条镜像语义</b>（普通更新推进 / 单列自增不推进）
 * ＋ 软删除的<b>可见性 split</b>。
 *
 * <h2>为什么这六条必须是「真 PG」而不是读注解或读 DDL 文本</h2>
 * <p>这六条断言的对象<b>本身就是 PG 的行为</b>：
 * <ul>
 *   <li>{@code article_revision_immutable} —— {@code BEFORE UPDATE OR DELETE} 触发器
 *       ⇒ H2 没有这个能力，DDL 文本里写着也不等于触发器真的建出来了；</li>
 *   <li>{@code article_publication.state} / {@code pointer_version} 的 {@code CHECK} 约束
 *       ⇒ 约束「建了」与「真的拦得住」是两件事；</li>
 *   <li>{@code updated_at} 的自动推进 —— 依赖 MP 的 {@code updateFill} + {@code MetaObjectHandler}
 *       的<b>运行期</b>装配 ⇒ 两者都是<b>静默失效</b>型（不报错、不打警告）；</li>
 *   <li>软删除可见性 —— 依赖 {@code deleted_at IS NULL} 谓词落在<b>哪几个方法</b>上。</li>
 * </ul>
 * ⇒ 读注解 / 读 SQL 文本只能证明「写成了这样」，**证不了「跑起来是这样」**。
 *
 * <h2>B 出处（逐条）</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleTransactionWiringTest.java}（本类 6 条的**逐条**移植）：
 *       <ul>
 *         <li>{@code :146-165} {@code flywayMigrationsActuallyRan} ⇒ ①</li>
 *         <li>{@code :169-185} {@code pgTriggerRejectsRevisionMutation} ⇒ ②</li>
 *         <li>{@code :189-198} {@code publicationCheckConstraintsAreEnforced} ⇒ ③</li>
 *         <li>{@code :869-892} {@code updateByIdAdvancesUpdatedAt}（S-3）⇒ ④</li>
 *         <li>{@code :912-925} {@code viewCountIncrementDoesNotTouchUpdatedAt}（S-4）⇒ ⑤</li>
 *         <li>{@code :591-607} {@code softDeleteVisibilityMatchesGoMethodSplit} ⇒ ⑥</li>
 *       </ul></li>
 *   <li>Go 侧：{@code service/article/rpc/internal/model/article.go:31}
 *       （{@code UpdatedAt time.Time `gorm:"autoUpdateTime"`}）·
 *       {@code gorm@v1.31.1/callbacks/update.go:238-241}（autoUpdateTime 的消费点）·
 *       {@code community_article_test.go:224-227}（revision 不可变的 Go 断言）·
 *       {@code internal/model/article.go:123} 与 {@code article_public.go:159}
 *       （两处 {@code UpdateColumn("view_count", …)} ⇒ {@code SkipHooks=true}）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>删掉 {@code @TableField(fill = INSERT_UPDATE)} 或让 {@code MetaObjectHandler}
 *       不再写 {@code updated_at} ⇒ ④ <b>立刻红</b>（两处都是静默的，不会有任何报错）；</li>
 *   <li>把 {@code incrViewCount} 从原生 {@code @Update} 改写成
 *       {@code update(null, wrapper)} ⇒ ⑤ <b>立刻红</b>
 *       （⇒ handler 的作用面会比 Go 的 {@code SkipHooks} 更宽）；</li>
 *   <li>给 {@code selectById} 去掉 {@code deleted_at IS NULL} ⇒ ⑥ <b>立刻红</b>；</li>
 *   <li>把 {@code V10} 里的触发器与 CHECK 约束删掉 ⇒ ②③ <b>立刻红</b>
 *       （而 Flyway 那条 ① 会先红 ⇒ 三条互为对照，谁坏谁红）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类行 id 前缀 {@code 908001}；作者号 {@code 908000001}。
 * ⚠️ <b>清理只能用 TRUNCATE，不能用 DELETE</b> ——
 * {@code article_revision} 上有 {@code BEFORE DELETE} 触发器，DELETE 会被它挡下来。
 */
class ArticleSchemaAndTimestampRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "908000001";

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDomainTables() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
    }

    // ==================================================================
    // ① U-8-1：Flyway 迁移真的执行了
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：Flyway 迁移真的执行了 —— 5 张表 + article_revision_immutable 触发器都在")
    void flywayMigrationsActuallyRan() {
        java.util.List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertTrue(tables.contains("article"), "article 表缺失 ⇒ Flyway 未执行");
        assertTrue(tables.contains("article_sync_outbox"), "article_sync_outbox 表缺失");
        assertTrue(tables.contains("article_revision"), "article_revision 表缺失");
        assertTrue(tables.contains("article_publication"), "article_publication 表缺失");
        assertTrue(tables.contains("article_domain_outbox"), "article_domain_outbox 表缺失");

        // ⚠️ 这条断言是 ArticleSchemaGate 存在的理由：
        // 缺 spring-boot-flyway 时，启动日志里连一行 Flyway 都没有，表与触发器都不创建，
        // 而进程「看起来完全正常」（B 侧 DRIFT-MSG-006 同款）。
        Boolean trigger = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_trigger
                WHERE tgname = 'article_revision_immutable'
                  AND tgrelid = to_regclass('article_revision'))
                """, Boolean.class);
        assertTrue(trigger, "不可变触发器缺失 ⇒ 已发布正文可被改写（Go 侧会 panic）");
    }

    // ==================================================================
    // ② U-8-2：PG 触发器真的拒绝 revision 的 UPDATE / DELETE
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：article revision is immutable —— PG 触发器真的拒绝 UPDATE / DELETE")
    void pgTriggerRejectsRevisionMutation() {
        String articleId = insertArticle("908001001");
        insertRevision(articleId, 1L, "908001001:r1", "sync-908001001", "# first", 1000L);

        // 对应 Go 测试 community_article_test.go:224-227
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE article_revision SET markdown = 'tampered' WHERE revision_id = ?", "908001001:r1"),
                "PG 允许修改不可变 revision ⇒ 触发器没生效");
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "DELETE FROM article_revision WHERE revision_id = ?", "908001001:r1"),
                "PG 允许删除不可变 revision ⇒ 触发器没生效");

        // 正文必须仍是原值
        String markdown = jdbcTemplate.queryForObject(
                "SELECT markdown FROM article_revision WHERE revision_id = ?", String.class, "908001001:r1");
        assertEquals("# first", markdown);
    }

    // ==================================================================
    // ③ U-8-3：article_publication 的 CHECK 约束真的拦得住
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：article_publication 的 CHECK 约束生效（state 与 pointer_version）")
    void publicationCheckConstraintsAreEnforced() {
        // ⚠️ 本表**没有**指向 article 的外键 ⇒ 这两条失败只可能来自 CHECK 约束本身
        //    （若有外键，插入不存在的 article_id 会先撞外键 ⇒ 断言「假通过」）。
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO article_publication (article_id, current_revision, pointer_version, state, last_event_id)"
                        + " VALUES ('908001002', 'r1', 1, 'bogus-state', 'e1')"),
                "state CHECK 约束未生效");
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO article_publication (article_id, current_revision, pointer_version, state, last_event_id)"
                        + " VALUES ('908001003', 'r1', 0, 'published', 'e1')"),
                "pointer_version > 0 CHECK 约束未生效");
    }

    // ==================================================================
    // ④ U-9-1（S-3）：updateById 推进 updated_at
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：updateById 推进 updated_at（GORM autoUpdateTime 的 Java 对应）")
    void updateByIdAdvancesUpdatedAt() {
        String articleId = insertArticle("908001004");
        // 把 updated_at 推到 2020，让「有没有前进」无歧义
        jdbcTemplate.update("UPDATE article SET updated_at = TIMESTAMPTZ '2020-01-01 00:00:00+00' "
                + "WHERE id = ?", articleId);
        // ⚠️ 必须**在 updateById 之前**从库里取值 ——
        //    MP 的 updateFill 会**原地改写**传入实体的 updatedAt
        //    ⇒ 若在调用之后再读这个对象，拿到的已是新值 ⇒ isAfter 恒为 false ⇒ 假红。
        OffsetDateTime before = updatedAtOf(articleId);

        // 关键：走真实读-改-写路径（域服务写路径全是这个形状）
        ArticleEntity loaded = articleMapper.selectById(articleId);
        assertNotNull(loaded, "前置校验：实体读得到");
        assertEquals(before, loaded.getUpdatedAt(), "前置校验：实体确实携带了陈旧值");

        loaded.setBrief("brief-changed");
        articleMapper.updateById(loaded);

        OffsetDateTime after = updatedAtOf(articleId);
        assertTrue(after.isAfter(before),
                "updated_at 必须前进（GORM autoUpdateTime 的语义）；"
                        + "若仍等于 " + before + "，说明 MetaObjectHandler 没触发"
                        + " 或 @TableField(fill=INSERT_UPDATE) 没生效 —— 注意这两者都是静默的");
        assertEquals("brief-changed", jdbcTemplate.queryForObject(
                "SELECT brief FROM article WHERE id = ?", String.class, articleId),
                "同一句 UPDATE 里的业务列必须真的写进去（否则本用例可能只是在测一个空转的语句）");
    }

    // ==================================================================
    // ⑤ U-9-2（S-4）：单列自增**不**推进 updated_at（Go 的 SkipHooks 语义）
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：view_count 自增**不**推进 updated_at（等价于 UpdateColumn 的 SkipHooks）")
    void viewCountIncrementDoesNotTouchUpdatedAt() {
        String articleId = insertArticle("908001005");
        jdbcTemplate.update("UPDATE article SET updated_at = TIMESTAMPTZ '2020-01-01 00:00:00+00' "
                + "WHERE id = ?", articleId);
        OffsetDateTime before = updatedAtOf(articleId);

        articleMapper.incrViewCount(articleId);

        assertEquals(1, viewCountOf(articleId), "前置校验：阅读量确实自增了");
        assertEquals(before, updatedAtOf(articleId),
                "Go 的 UpdateColumn 置 SkipHooks=true ⇒ autoUpdateTime 不生效；"
                        + "若这里也前进了，说明 handler 的作用面比 Go 更宽");
    }

    // ==================================================================
    // ⑥ U-10：软删除可见性 split
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：软删除 —— selectById 看不到已删行，selectByIdUnscoped 能看到（Go 的两种方法）")
    void softDeleteVisibilityMatchesGoMethodSplit() {
        String articleId = insertArticle("908001006");
        assertNotNull(articleMapper.selectById(articleId));
        assertNotNull(articleMapper.selectByIdUnscoped(articleId));

        assertEquals(1, articleMapper.softDelete(articleId));

        // Go: FindOne 带软删除过滤 ⇒ 查不到 ⇒ GetArticle/DeleteArticle 返回 NotFound
        assertNull(articleMapper.selectById(articleId),
                "已删文章仍能被 selectById 查到 ⇒ Go 的 NotFound 语义漂移");
        // Go: FindOneUnscoped 是同步结果消费者的唯一入口 ⇒ 必须仍能看到
        assertNotNull(articleMapper.selectByIdUnscoped(articleId),
                "已删文章无法被 Unscoped 查询 ⇒ 迟到同步结果无法落库");

        // 二次删除是 no-op（Go: 影响 0 行，不是错误）
        assertEquals(0, articleMapper.softDelete(articleId));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private OffsetDateTime updatedAtOf(String articleId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM article WHERE id = ?", OffsetDateTime.class, articleId);
    }

    private int viewCountOf(String articleId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT view_count FROM article WHERE id = ?", Integer.class, articleId);
        return v == null ? 0 : v;
    }

    private String insertArticle(String id) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'title-' || ?, 'brief', 'content.md', '', 'tag', '[]'::jsonb, ?, 3,
                        0, 0, 0, 0, '{}'::jsonb, now(), now())
                """, id, id, AUTHOR);
        return id;
    }

    private void insertRevision(String articleId, long revision, String revisionId,
                                String syncEventId, String markdown, long publishedAtMs) {
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, ?, ?, ?, ?, repeat('a', 64), 'rev title', 'rev brief', '', 'tag',
                        '[]'::jsonb, ?, to_timestamp(? / 1000.0), ?)
                """, articleId, revision, revisionId, AUTHOR, articleId + "-r1.md", markdown,
                (double) publishedAtMs, syncEventId);
    }
}
