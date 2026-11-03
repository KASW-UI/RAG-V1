package com.rag.article.storage;

import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.domain.service.ArticlePublicDomainService;
import com.rag.article.domain.service.ArticlePublicDomainService.PublicArticleView;
import com.rag.article.exception.ArticleExceptions;
import com.rag.article.mapper.ArticlePublicMapper;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>U-12 真触发</b>：{@code ArticlePublicMapper.selectPublicById} 取到 <b>legacy 行</b>
 * （无 publication 指针 + {@code status = 2}）之后，
 * {@code ArticlePublicDomainService.readLegacyMarkdown} <b>真的去真 MinIO 读对象</b>并<b>真的读到内容</b>。
 *
 * <h2>为什么这个缺口必须用<b>真 MinIO</b>（而不是「MinIO 桩」）</h2>
 * <p>缺口登记见 {@code docs/DRIFT/article/verification-article-service-2026-11-03.md:426}
 * （<b>U-12</b> 行：「{@code readLegacyMarkdown} 需 MinIO 桩 ⇒ 补 MinIO 桩 + 用例」）。
 * <br>本轮把它升级为<b>真容器</b>：因为这条路径要验的是
 * 「<b>DB 里的 object key</b> ⇒ <b>对象存储里的字节</b>」这条<b>跨存储</b>的接缝
 * —— 桩只能证明「代码调用了 readString」，<b>证不了</b>「key 与对象真的对得上」。
 *
 * <h2>B 侧 oracle</h2>
 * <ul>
 *   <li>B 侧 {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/service/ArticlePublicDomainService.java}
 *       {@code :129-136}（legacy 分支：<b>无 revision ⇒ 回落读原对象</b>）、
 *       {@code :311-317}（{@code readLegacyMarkdown}：<b>读失败 ⇒ readLegacyArticleFailed</b>）；</li>
 *   <li>B 侧 {@code .../mapper/ArticlePublicMapper.java} {@code :87-93}（{@code selectPublicById}
 *       + 可见性谓词的 legacy 支 {@code OR (p.article_id IS NULL AND a.status = 2)}）；</li>
 *   <li>B 侧 {@code .../exception/ArticleExceptions.java}
 *       （{@code readLegacyArticleFailed} = {@code INTERNAL "read legacy article failed"}）；</li>
 *   <li>B 侧测试 {@code .../service/ArticlePublicDomainServiceViewTest.java:114-131}
 *       （legacy 路径断言：正文回落 + {@code publication_gap} 标记 + <b>不伪造 revision_id</b>）
 *       ⇒ 本类沿用其<b>期望值</b>，但把「桩」换成<b>真 MinIO</b>。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code readLegacyMarkdown} 改成不读对象存储（直接返回空串）
 *       ⇒ 用例 ① <b>立刻红</b>（markdown 不等于对象内容）；</li>
 *   <li>把可见性谓词的 legacy 支删掉 ⇒ 用例 ① <b>立刻红</b>（{@code selectPublicById} 取不到行）；</li>
 *   <li>把 {@code readLegacyMarkdown} 的 catch 去掉（让原始异常冒出去）
 *       ⇒ 用例 ② <b>立刻红</b>（抛的不是 {@code ArticleExceptions.Rpc}）；</li>
 *   <li>让「有 revision」那条路径也去读对象存储 ⇒ 用例 ③ <b>立刻红</b>
 *       （对象不在 MinIO ⇒ 读失败）；</li>
 *   <li>把 {@code article.content} 当成正文而不是 object key
 *       ⇒ 用例 ① <b>立刻红</b>（读的是错的 key）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>PG 容器是<b>进程内单例</b>（{@code support/TestPostgres}）⇒ 本类用专属 uid 段
 * {@code 904xxxxx}（其它测试类用 901/902/903/905/906/907/908）。
 * <br>⚠️ {@code article_revision} 上有 {@code BEFORE UPDATE OR DELETE} 触发器
 * ⇒ 清理只能用 {@code TRUNCATE}，不能用 {@code DELETE}。
 */
class ArticlePublicLegacyMarkdownRealMinioTest extends MinioSpringTestBase {

    /** 本类专属作者号。 */
    private static final String AUTHOR = "904000001";

    /** legacy 行：{@code status = 2} 且<b>从无</b> publication 行。 */
    private static final String LEGACY_ID = "904001001";

    /** legacy 行的 {@code article.content} —— 存的是 <b>MinIO 对象 key</b>（B-03）。 */
    private static final String LEGACY_OBJECT = "articles/content/904001001.md";

    /** 真 MinIO 里那个对象的正文。 */
    private static final String LEGACY_MARKDOWN =
            "# legacy 正文\n\n这一段是从**真 MinIO 容器**里读出来的。";

    /** 对照组：已发布（有 publication 指针 + revision）。 */
    private static final String PUBLISHED_ID = "904001002";
    private static final String PUBLISHED_REVISION_ID = "904001002:r1";

    /** 对照组行的 object key —— <b>故意不在 MinIO 里放这个对象</b>。 */
    private static final String PUBLISHED_OBJECT = "articles/content/904001002.md";

    /** 对照组的正文在 <b>DB</b> 里（article_revision.markdown）。 */
    private static final String PUBLISHED_MARKDOWN = "# 已批准正文（存在 DB 里，不在对象存储）";

    @Autowired
    private ArticlePublicDomainService publicService;

    @Autowired
    private ArticlePublicMapper publicMapper;

    @Autowired
    private ArticleObjectStorageClient storage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() throws Exception {
        // ⚠️ 只能用 TRUNCATE：article_revision 上有 BEFORE UPDATE OR DELETE 触发器
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");

        // ① legacy 行：status = 2（PUBLISHED）且**从无** publication 行
        //    ⇒ 走可见性谓词的第二支 OR (p.article_id IS NULL AND a.status = 2)
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'legacy-title', 'legacy-brief', ?, '', 'legacy-manual',
                        '["legacy-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, '2026-03-01T00:00:00Z'::timestamptz, now())
                """, LEGACY_ID, LEGACY_OBJECT, AUTHOR);

        // ② 对照组：已发布（有 publication 指针 + 指向一个真实 revision）
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'draft-title', 'draft-brief', ?, '', 'draft-manual',
                        '["draft-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, '2026-03-02T00:00:00Z'::timestamptz, now())
                """, PUBLISHED_ID, PUBLISHED_OBJECT, AUTHOR);

        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 1, ?, ?, ?, repeat('c', 64),
                        'approved-title', 'approved-brief', '', 'approved-manual',
                        '["approved-tag"]'::jsonb, ?, now(), 'sync-904-1')
                """, PUBLISHED_ID, PUBLISHED_REVISION_ID, AUTHOR, PUBLISHED_OBJECT, PUBLISHED_MARKDOWN);

        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES (?, ?, 1, 'published', 'evt-904-1')
                """, PUBLISHED_ID, PUBLISHED_REVISION_ID);

        // ③ 真 MinIO：建桶 + 只放 **legacy** 那个对象
        //    （对照组的对象**故意不放** ⇒ 用例③ 若去读对象存储就会失败）
        storage.ensureBucketWithPublicRead();
        storage.put(LEGACY_OBJECT, LEGACY_MARKDOWN.getBytes(StandardCharsets.UTF_8),
                "text/markdown", null);
    }

    // ==================================================================
    // ① legacy 正文回落：真去真 MinIO 读
    // ==================================================================

    /**
     * <p><b>真触发</b>：{@code selectPublicById} 取到 legacy 行 ⇒ {@code readLegacyMarkdown}
     * <b>真的向真 MinIO 发请求</b>并<b>真的读回对象内容</b>。
     *
     * <p><b>B 出处</b>：
     * <ul>
     *   <li>B 侧 {@code ArticlePublicDomainService.java:129-136}：
     *       <pre>
     *       String markdown;
     *       if (revision != null) {
     *           // 🔴 已批准正文在 DB 里，**不读对象存储**
     *           markdown = revision.getMarkdown();
     *       } else {
     *           // legacy：无 revision 指针 ⇒ 回落到原 MinIO 对象（不伪造 revision）
     *           markdown = readLegacyMarkdown(articleId, article.getContent());
     *       }
     *       </pre></li>
     *   <li>B 侧 {@code ArticlePublicDomainService.java:311-317}：
     *       <pre>
     *       private String readLegacyMarkdown(String articleId, String objectName) {
     *           try { return storage.readString(objectName); }
     *           catch (Exception e) { throw ArticleExceptions.readLegacyArticleFailed(); }
     *       }
     *       </pre></li>
     *   <li>B 侧 {@code ArticlePublicMapper.java:87-93} + 谓词常量（含 legacy 支
     *       {@code OR (p.article_id IS NULL AND a.status = 2)}）；</li>
     *   <li>B 侧测试 {@code ArticlePublicDomainServiceViewTest.java:114-131}
     *       （期望值沿用：正文回落 + {@code publication_gap} 标记 + 不伪造 revision_id）。</li>
     * </ul>
     *
     * <p><b>怎么才能证伪</b>：
     * <ul>
     *   <li>把 {@code readLegacyMarkdown} 改成直接返回空串 ⇒ markdown 断言红；</li>
     *   <li>把 {@code article.getContent()} 换成 {@code getObjectNamePrefixForArticle() + id}
     *       ⇒ 读的是别的 key ⇒ markdown 断言红（真 MinIO 里没有那个对象）；</li>
     *   <li>删掉可见性谓词的 legacy 支 ⇒ {@code selectPublicById} 返回 null ⇒ 取行断言红。</li>
     * </ul>
     */
    @Test
    @DisplayName("🔴 真触发：legacy 行（无 revision 指针）⇒ 正文真从真 MinIO 读出来")
    void legacyArticleMarkdownIsReadFromRealMinio() {
        // ① 先证明 mapper 这一半：legacy 行确实能被 selectPublicById 取到（谓词第二支）
        ArticleEntity row = publicMapper.selectPublicById(LEGACY_ID);
        assertNotNull(row,
                "legacy 行（无 publication 指针 + status=2）必须能被 selectPublicById 取到"
                        + "（B 侧 ArticlePublicMapper 的谓词第二支）");
        assertEquals(LEGACY_OBJECT, row.getContent(),
                "🔴 article.content 存的是 **MinIO 对象 key**，不是正文（B 侧 ArticleEntity 字段说明）");
        assertEquals(2, row.getStatus(), "legacy 行的 status 必须是 PUBLISHED(2)");

        // ② 再证明 domain service 这一半：正文真的从真 MinIO 读回来
        PublicArticleView view = publicService.getPublicArticle(LEGACY_ID, false);
        assertNotNull(view, "legacy 行必须能取到公开视图");
        assertEquals(LEGACY_MARKDOWN, view.markdown,
                "🔴 legacy 正文必须**真的**从真 MinIO 读到（B :135 ⇒ :311-317）");
        assertEquals("legacy-title", view.title, "legacy 行的字段来源是 article 行（无 revision 可覆盖）");
        assertEquals(AUTHOR, view.authorId);
        assertEquals(2, view.status, "status 恒 PUBLISHED");
        assertEquals(ArticleSyncEvent.LEGACY_GAP_VALUE,
                view.extInfo.get(ArticleSyncEvent.LEGACY_GAP_KEY),
                "legacy 必须打 publication_gap 标记（B 侧 buildView 的 legacy 分支）");
        assertFalse(view.extInfo.containsKey("published_revision_id"),
                "🔴 legacy **不伪造** revision_id（B 侧 ArticlePublicDomainServiceViewTest:129-130）");

        // ③ 反向：status 改成 3（审核中）⇒ legacy 支不再放行 ⇒ mapper 取不到行
        jdbcTemplate.update("UPDATE article SET status = 3 WHERE id = ?", LEGACY_ID);
        assertNull(publicMapper.selectPublicById(LEGACY_ID),
                "status != 2 ⇒ legacy 支不放行（B 侧谓词：OR (p.article_id IS NULL AND a.status = 2)）");
    }

    // ==================================================================
    // ② 对象缺失 ⇒ INTERNAL "read legacy article failed"
    // ==================================================================

    /**
     * <p><b>真触发</b>：legacy 对象在 MinIO 上不存在时，{@code readLegacyMarkdown}
     * 把异常换成 <b>领域异常</b> {@code INTERNAL "read legacy article failed"}。
     *
     * <p><b>B 出处</b>：B 侧 {@code ArticlePublicDomainService.java:311-317}
     * （{@code catch (Exception e) { throw ArticleExceptions.readLegacyArticleFailed(); }}）
     * ＋ B 侧 {@code ArticleExceptions.readLegacyArticleFailed()}
     * = {@code status.Error(codes.Internal, "read legacy article failed")}。
     *
     * <p><b>怎么才能证伪</b>：把 {@code readLegacyMarkdown} 的 catch 去掉
     * ⇒ 抛的是 SDK 的 {@code ErrorResponseException} 而不是 {@code ArticleExceptions.Rpc}
     * ⇒ 本用例 <b>立刻红</b>（错误契约变了：客户端会看到 1015 而不是 5001）。
     */
    @Test
    @DisplayName("🔴 真触发：legacy 对象不在 MinIO ⇒ INTERNAL \"read legacy article failed\"")
    void missingLegacyObjectFailsWithReadLegacyArticleFailed() throws Exception {
        // 前置：把对象从真 MinIO 上删掉
        storage.getClient().removeObject(RemoveObjectArgs.builder()
                .bucket(storage.getBucketName())
                .object(LEGACY_OBJECT)
                .build());
        assertThrows(ErrorResponseException.class,
                () -> storage.getClient().statObject(StatObjectArgs.builder()
                        .bucket(storage.getBucketName()).object(LEGACY_OBJECT).build()),
                "前置：对象确实已从真 MinIO 删除");

        ArticleExceptions.Rpc ex = assertThrows(ArticleExceptions.Rpc.class,
                () -> publicService.getPublicArticle(LEGACY_ID, false),
                "🔴 读失败必须换成领域异常（B :311-317），不能把 SDK 异常原样抛出");
        assertEquals(Status.Code.INTERNAL, ex.getCode(),
                "B 侧 readLegacyArticleFailed ⇒ INTERNAL（api 层映射成业务码 5001）");
        assertEquals("read legacy article failed", ex.getMessage(),
                "文案必须逐字一致（B 侧 ArticleExceptions.readLegacyArticleFailed）");
    }

    // ==================================================================
    // ③ 对照组：有 revision ⇒ 不读对象存储
    // ==================================================================

    /**
     * <p><b>真触发（对照组）</b>：已发布（有 publication 指针 + revision）的文章
     * 正文来自 <b>DB</b>，<b>完全不碰对象存储</b> —— 该文章的对象 key 在 MinIO 上
     * <b>根本不存在</b>，若代码去读就会失败。
     *
     * <p><b>B 出处</b>：B 侧 {@code ArticlePublicDomainService.java:129-136}
     * （「🔴 已批准正文在 DB 里，**不读对象存储**」）＋ B 侧类级 javadoc 第 ① 条
     * （「公开详情<b>不读 MinIO</b>（有 revision 时）… Go 测试把 {@code MinioClient} 置 nil 后
     * 仍要求返回正文」）。
     *
     * <p><b>怎么才能证伪</b>：把 {@code markdown = revision.getMarkdown()} 改成也去读对象存储
     * ⇒ 本用例 <b>立刻红</b>（对象不存在 ⇒ 抛 {@code read legacy article failed}）。
     */
    @Test
    @DisplayName("🔴 真触发（对照组）：有 revision ⇒ 正文来自 DB，**不读**对象存储")
    void publishedRevisionIsReadFromDbWithoutTouchingObjectStorage() {
        // 前置：对照组的对象在真 MinIO 上不存在
        assertThrows(ErrorResponseException.class,
                () -> storage.getClient().statObject(StatObjectArgs.builder()
                        .bucket(storage.getBucketName()).object(PUBLISHED_OBJECT).build()),
                "前置：对照组的对象**故意不放**在 MinIO 里 ⇒ 若代码去读就会失败");

        PublicArticleView view = publicService.getPublicArticle(PUBLISHED_ID, false);
        assertNotNull(view, "已发布文章必须能取到公开视图");
        assertEquals(PUBLISHED_MARKDOWN, view.markdown,
                "🔴 已发布正文来自 article_revision.markdown（B :130-132），**不读**对象存储");
        assertEquals("approved-title", view.title,
                "有 revision 时字段来源是 revision（B 侧 buildView 的 revision 分支）");
        assertEquals(PUBLISHED_REVISION_ID, view.extInfo.get("published_revision_id"),
                "有 revision 时 ext_info 是 published_revision_id（B 侧 buildView）");
        assertFalse(view.extInfo.containsKey(ArticleSyncEvent.LEGACY_GAP_KEY),
                "有 revision ⇒ **没有** publication_gap 标记（B 侧 buildView 的两个分支互斥）");
        assertTrue(view.extInfo.containsKey("published_revision_id"), "对照组的标记必须在");
    }
}
