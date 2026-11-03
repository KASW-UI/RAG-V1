package com.rag.article.domain.service;

import com.rag.article.exception.ArticleExceptions;
import com.rag.article.mapper.ArticlePublicMapper;
import com.rag.article.support.ArticleRealPgTestBase;
import com.rag.proto.article.ListArticlesRequest;
import com.rag.proto.article.ListArticlesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：{@code ArticlePublicDomainService} 的公开读路径 ——
 * <b>计阅读量能提交</b>、<b>两种 DB 失败映射成 INTERNAL</b>、
 * <b>列表的只读可重复读事务</b>。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <ol>
 *   <li><b>「计阅读量能提交」只能靠真事务证明</b>：
 *       一版实现给 {@code getPublicArticle} 打了 {@code @Transactional(readOnly = true)}，
 *       而方法内含一条 {@code UPDATE} ⇒ pgjdbc 发 {@code BEGIN READ ONLY}
 *       ⇒ PG 拒：{@code 25006 cannot execute UPDATE in a read-only transaction}
 *       ⇒ <b>每次调用均失败</b>。
 *       ⚠️ 以前的测试拿不到：全 Mockito 的视图测试<b>不经过代理</b> ⇒ 事务根本不生效。</li>
 *   <li><b>「DB 失败 ⇒ INTERNAL 而不是 UNKNOWN」不能用 Mockito 打桩</b>：
 *       那样测不到「真 SQL 失败会不会被翻译成 INTERNAL」。
 *       ⇒ 本类让<b>真 PG 真的报错</b>（改表名 / 加 CHECK 约束），走真 MyBatis + 真异常翻译。</li>
 *   <li><b>隔离级别与只读标志</b>：用 Spring 自己的
 *       {@link AnnotationTransactionAttributeSource} 解析出
 *       {@code TransactionInterceptor} <b>运行期实际使用</b>的属性值
 *       （不是简单读注解），再配合真 PG 上的 count/page 一致性。</li>
 * </ol>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code ArticleTransactionWiringTest.java:664-760}
 *       （计阅读量能提交 / 不计数 / 两种 DB 失败 ⇒ INTERNAL）；</li>
 *   <li>Go 出处 {@code service/article/rpc/internal/logic/get_article_logic.go:126-168}
 *       （公开详情三分支 + 两种失败文案）；</li>
 *   <li>Go 出处 {@code service/article/rpc/internal/model/article_public.go:34-57}
 *       （{@code FindPublicOne} 的三处 DB 失败返回<b>同一种</b> error）
 *       与 {@code :65,128}（count + page + revision 走
 *       {@code LevelRepeatableRead, ReadOnly: true}）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>给 {@code getPublicArticle} 加回 {@code @Transactional(readOnly = true)}
 *       ⇒ 用例 ① <b>立刻红</b>（25006 / view_count 不变）；</li>
 *   <li>去掉 {@code readPublication} 的包装 ⇒ 用例 ③ <b>立刻红</b>
 *       （变成裸 UNKNOWN ⇒ 业务码 1015 而不是 5001）；</li>
 *   <li>把 {@code incrementPublicView} 的异常映射改成 UNKNOWN ⇒ 用例 ④ <b>立刻红</b>；</li>
 *   <li>把 {@code listPublicArticles} 的 isolation 改成 DEFAULT ⇒ 用例 ⑤ <b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类专属作者号 {@code 906000001}；行 id 前缀 {@code 906001}。
 */
class ArticlePublicDomainServiceRealPgTest extends ArticleRealPgTestBase {

    private static final String AUTHOR = "906000001";

    @Autowired
    private ArticlePublicDomainService publicService;

    @Autowired
    private ArticlePublicMapper publicMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");

        // 已发布 + 指针 + revision ⇒ 公开详情走「不读对象存储」的分支
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES ('906001001', 'row-title', 'row-brief', '906001001.md', '', 'row-manual',
                        '["row-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, now(), now())
                """, AUTHOR);
        insertRevision("906001001", 1L, "906001001:r1", "sync-906-1", "# Approved");
        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES ('906001001', '906001001:r1', 1, 'published', 'evt-906-1')
                """);

        // 第二行：已发布 + revision（用于列表用例）
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES ('906001002', 'row-title-2', 'row-brief', '906001002.md', '', 'row-manual',
                        '["row-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, now(), now())
                """, AUTHOR);
        insertRevision("906001002", 1L, "906001002:r1", "sync-906-2", "# Approved 2");
        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES ('906001002', '906001002:r1', 1, 'published', 'evt-906-2')
                """);
    }

    private void insertRevision(String articleId, long revision, String revisionId,
                                String syncEventId, String markdown) {
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, ?, ?, ?, 'source-object', repeat('a', 64),
                        'rev-title', 'rev-brief', '', 'rev-manual',
                        '["rev-tag"]'::jsonb, ?, now(), ?)
                """, articleId, revision, revisionId, AUTHOR, markdown, syncEventId);
    }

    // ==================================================================
    // ① 公开详情计阅读量是能提交的
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：公开详情计阅读量是能提交的（**不得**落在 read-only 事务里）")
    void publicViewCountIncrementActuallyCommits() throws Exception {
        // ⚠️ 结构判据：getPublicArticle 上**不能有** @Transactional
        //    （B 侧那次回归就是 readOnly=true ⇒ UPDATE 被 PG 拒：25006）
        Method getPublic = ArticlePublicDomainService.class
                .getMethod("getPublicArticle", String.class, boolean.class);
        TransactionAttribute attr = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(getPublic, ArticlePublicDomainService.class);
        assertNull(attr,
                "🔴 getPublicArticle 上**不得**有 @Transactional —— readOnly 事务会让本方法里的 "
                        + "UPDATE 被 PG 拒（25006 cannot execute UPDATE in a read-only transaction）；"
                        + "Go 侧这条路径本来就没有事务（get_article_logic.go:126-168）");

        assertEquals(0, viewCountOf("906001001"), "前置校验：初始阅读量应为 0");

        ArticlePublicDomainService.PublicArticleView view =
                publicService.getPublicArticle("906001001", true);

        assertNotNull(view, "公开详情应正常返回；若 readOnly 事务未移除，"
                + "这里会是 PSQLException: 25006 cannot execute UPDATE in a read-only transaction");
        assertEquals(1L, viewCountOf("906001001"),
                "PG 里的 view_count 必须真的 +1（说明 UPDATE 已提交，而不是被只放在内存里）");
        assertEquals(1, view.viewCount, "响应里的 view_count 是内存 +1 后的值");
        assertEquals("# Approved", view.markdown, "正文取冻结的 revision.markdown（不读对象存储）");
        assertEquals("rev-title", view.title, "标题取 **revision**（读者看到的分类/标题是批准时的值）");
    }

    // ==================================================================
    // ② incrView=false ⇒ 不计数
    // ==================================================================

    @Test
    @DisplayName("真触发：公开详情 incrView=false 时不计数")
    void publicViewCountNotIncrementedWhenNotRequested() {
        ArticlePublicDomainService.PublicArticleView view =
                publicService.getPublicArticle("906001001", false);

        assertNotNull(view);
        assertEquals(0, viewCountOf("906001001"), "不请求计数时不应写入");
        assertEquals(0, view.viewCount, "响应里的 view_count 也不应被内存 +1");
    }

    // ==================================================================
    // ③ 公开读的 DB 失败 ⇒ INTERNAL "read publication failed"
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：公开读的 DB 失败 ⇒ INTERNAL \"read publication failed\"（不是 UNKNOWN ⇒ 业务码 1015）")
    void publicReadDbFailureIsInternalNotUnknown() {
        // 让公开读用到的表**真的不存在** ⇒ 真 SQL 错误（42P01）⇒ 真 DataAccessException
        jdbcTemplate.execute("ALTER TABLE article_publication RENAME TO article_publication_hidden");
        try {
            ArticleExceptions.Rpc ex = assertThrows(ArticleExceptions.Rpc.class,
                    () -> publicService.getPublicArticle("906001001", false),
                    "公开读的 DB 失败必须被翻译成 gRPC INTERNAL，而不是裸 UNKNOWN");
            assertEquals(io.grpc.Status.Code.INTERNAL, ex.getCode(),
                    "Go: status.Error(codes.Internal, \"read publication failed\")");
            assertEquals("read publication failed", ex.getMessage(),
                    "Go 的文案逐字：get_article_logic.go:133");
        } finally {
            jdbcTemplate.execute("ALTER TABLE article_publication_hidden RENAME TO article_publication");
        }
    }

    // ==================================================================
    // ④ 计阅读量的 DB 失败 ⇒ INTERNAL "increment public view failed"
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：公开读计阅读量的 DB 失败 ⇒ INTERNAL \"increment public view failed\"（不是 UNKNOWN）")
    void publicViewIncrementDbFailureIsInternalNotUnknown() {
        // 只让 **UPDATE** 失败、SELECT 仍成功 ⇒ 精确命中 incrPublicViewCount 这一条路径
        // （若改名 article 表，前面的 SELECT 会先失败 ⇒ 测不到计数那条路径）
        // ⚠️ NOT VALID 是必需的：既有行的 view_count=0 不满足 view_count<0，
        //    不加 NOT VALID 的话 ALTER 自己就会失败。
        jdbcTemplate.execute(
                "ALTER TABLE article ADD CONSTRAINT chk_force_view_fail CHECK (view_count < 0) NOT VALID");
        try {
            ArticleExceptions.Rpc ex = assertThrows(ArticleExceptions.Rpc.class,
                    () -> publicService.getPublicArticle("906001001", true),
                    "计阅读量的 DB 失败必须被翻译成 gRPC INTERNAL");
            assertEquals(io.grpc.Status.Code.INTERNAL, ex.getCode(),
                    "Go: status.Error(codes.Internal, \"increment public view failed\")");
            assertEquals("increment public view failed", ex.getMessage(),
                    "Go 的文案逐字：get_article_logic.go:160");
        } finally {
            jdbcTemplate.execute("ALTER TABLE article DROP CONSTRAINT IF EXISTS chk_force_view_fail");
        }
    }

    // ==================================================================
    // ⑤ listPublicArticles 是 REPEATABLE_READ + readOnly
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：listPublicArticles 走 REPEATABLE_READ + readOnly 事务（count/page/revision 同一快照）")
    void listPublicArticlesRunsInRepeatableReadOnlyTransaction() throws Exception {
        // ⚠️ 用 Spring 自己的 TransactionAttributeSource 解析 ——
        //    这正是 TransactionInterceptor **运行期实际使用**的属性值（不是简单读注解）。
        Method listMethod = ArticlePublicDomainService.class
                .getMethod("listPublicArticles", ListArticlesRequest.class);
        TransactionAttribute attr = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(listMethod, ArticlePublicDomainService.class);

        assertNotNull(attr, "listPublicArticles 必须带 @Transactional");
        assertTrue(attr.isReadOnly(),
                "🔴 listPublicArticles 必须 readOnly（Go: sql.TxOptions{ReadOnly: true}）");
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, attr.getIsolationLevel(),
                "🔴 隔离级别必须是 REPEATABLE_READ（Go: LevelRepeatableRead；"
                        + "去掉它 ⇒ count 与 page 可能来自两个不同快照）");

        // 行为侧：count 与 page 必须同口径，且 revision 取值与页面行匹配
        ListArticlesResponse response = publicService.listPublicArticles(
                ListArticlesRequest.newBuilder().setPage(1).setPageSize(20).build());

        assertEquals(2L, response.getTotal(), "total 必须是可见文章的总数");
        assertEquals(2, response.getArticlesCount(), "页内容行数必须与 total 同口径");
        assertEquals(1, response.getPage(), "page 归一（page<1 ⇒ 1）");
        assertEquals(20, response.getPageSize(), "page_size 归一（size<1 ⇒ 20）");

        // 公开列表**永不返回正文**（Go list_articles_logic.go:122 传空串）
        for (com.rag.proto.article.Article a : response.getArticlesList()) {
            assertEquals("", a.getMarkdownContent(), "🔴 公开列表不得返回正文");
            assertEquals("rev-title", a.getTitle(), "标题取 **revision**（批准时的值）");
        }

        // 直查 count 与 mapper 的 count 必须一致（同一谓词）
        long mapperCount = publicMapper.countPublic(null, null, AUTHOR);
        assertEquals(mapperCount, response.getTotal(), "mapper 的 count 与服务的 total 必须一致");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private int viewCountOf(String articleId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT view_count FROM article WHERE id = ?", Integer.class, articleId);
        return v == null ? 0 : v;
    }
}
