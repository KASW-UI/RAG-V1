package com.rag.article.domain.service;

import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.domain.entity.ArticleRevisionEntity;
import com.rag.article.exception.ArticleExceptions;
import com.rag.article.mapper.ArticlePublicMapper;
import com.rag.article.mapper.ArticleRevisionMapper;
import com.rag.common.persistence.JsonbValueTypeHandler;
import com.rag.proto.article.Article;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.ListArticlesRequest;
import com.rag.proto.article.ListArticlesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <p><b>职责</b>：公开读路径 —— 对应 Go 端
 * {@code logic/get_article_logic.go:126-189}（{@code getPublicArticle} + {@code publishedArticleResponse}）
 * 与 {@code logic/list_articles_logic.go:103-133}（{@code listPublicArticles}）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/service/ArticlePublicDomainService.java}
 * （类声明 {@code :53-54} · 构造器 {@code :63-69} · {@code getPublicArticle} {@code :98-149} ·
 * {@code listPublicArticles} {@code :165-222} · {@code readPublication} {@code :273-284} ·
 * {@code incrementPublicView} {@code :301-309} · {@code readLegacyMarkdown} {@code :311-317} ·
 * {@code buildView} {@code :339-374} · {@code toProtoArticle} {@code :377-401} ·
 * {@code nullToEmpty} {@code :403-405} · {@code PublicArticleView} {@code :411-428}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.service} →
 *             {@code com.rag.article.domain.service}（D7 包压平）；</li>
 *         <li><b>proto 包名</b>：{@code com.rag.article.article.proto.*} →
 *             {@code com.rag.proto.article.*}（D7）⇒ {@code ArticleStatus.PUBLISHED_VALUE}
 *             的<b>数值与常量名一字未改</b>；</li>
 *         <li><b>常量宿主类改名</b>：{@code ArticleSyncEventTypes.LEGACY_GAP_KEY / LEGACY_GAP_VALUE}
 *             ⇒ {@link ArticleSyncEvent#LEGACY_GAP_KEY} / {@link ArticleSyncEvent#LEGACY_GAP_VALUE}
 *             （<b>键名与取值逐字未改</b> —— 它进 {@code ext_info}，是 L1 契约）；</li>
 *         <li><b>全限定名缩短</b>：B 侧正文里大量写全限定的
 *             {@code com.rag.article.article.proto.ArticleStatus} / {@code ...proto.Article}，
 *             C 侧改为 <b>import + 短名</b>（D7 的表达层收益）⇒ <b>引用的是同一批类型</b>。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>公开详情的三分支（无指针行 / 指针状态不对 / 正常）逐条未动</b>；</li>
 *         <li><b>浏览量自增的两种失败口径未动</b>（作者路径 best-effort · 公开路径致命）；</li>
 *         <li><b>列表的 page/size 归一与 {@code REPEATABLE_READ} 只读事务未动</b>；</li>
 *         <li><b>公开列表永不返回正文</b>（markdown 恒传 {@code ""}）未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>为什么与 {@link ArticleDomainService} 分开</h2>
 * 「作者视角」与「读者视角」是<b>两套持久化语义</b>：
 * 作者读自己<b>当前的源行</b>（可能正在审核、可能是任意状态、正文来自 MinIO 的最新对象）；
 * 读者读<b>已批准的冻结 revision</b>（正文存在 DB 里，不碰对象存储）。
 * 合并会让"读者看到什么"这条规则无法单独定位与测试。
 *
 * <h2>🔴 三条最容易改错、且改动后 Go 测试会红的行为</h2>
 * <ol>
 *   <li><b>公开详情<b>不读 MinIO</b>（有 revision 时）</b>：
 *       Go 测试 {@code article_public_test.go:173-178} 把 {@code MinioClient} 置 nil 后
 *       仍要求返回正文。把 revision.markdown 换成"再去 MinIO 取"会让这条测试红，
 *       也会让"已发布字节不可变"退化成"取决于对象存储当前内容"。</li>
 *   <li><b>公开列表<b>永不返回正文</b></b>：Go {@code list_articles_logic.go:122} 对每条传空串。
 *       列表返回正文会让响应体积随列表长度线性膨胀。</li>
 *   <li><b>浏览量自增失败在两条路径上<b>不对称</b></b>：
 *       <ul>
 *         <li>作者路径 {@code IncrViewCount} 失败 → <b>只记日志，best-effort</b>
 *             （{@code get_article_logic.go:72-75}），返回仍然成功；</li>
 *         <li>公开路径 {@code IncrPublicViewCount} 失败 → <b>致命</b>，
 *             返回 Internal（{@code :158-160}）；返回 0 行（撤回赢了竞态）→ <b>NotFound</b>
 *             （{@code :162-164}）。</li>
 *       </ul>
 *       对称化任何一边都会改变客户端可见行为。</li>
 * </ol>
 *
 * <h2>⚠️ 日志：<b>不用</b> {@code @Slf4j}</h2>
 * 本仓禁止 Lombok 的 {@code @Slf4j}（D18 / §1.0e）。
 * <br>⚠️ 本类<b>刻意</b>用普通 slf4j（B 侧 {@code :56-57} 就是
 * {@code LoggerFactory.getLogger(ArticlePublicDomainService.class)}），
 * 两条 {@code LOG.warn} 的<b>文案与实参逐字未改</b>；
 * 换成 {@code GoStyleLogger} 会凭空新增 {@code call_chain} 字段 ⇒ 无 B 侧依据，不做。
 */
@Service
public class ArticlePublicDomainService {

    private static final Logger LOG =
            LoggerFactory.getLogger(ArticlePublicDomainService.class);

    private final ArticlePublicMapper publicMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleObjectStorageClient storage;

    public ArticlePublicDomainService(ArticlePublicMapper publicMapper,
                                      ArticleRevisionMapper revisionMapper,
                                      ArticleObjectStorageClient storage) {
        this.publicMapper = publicMapper;
        this.revisionMapper = revisionMapper;
        this.storage = storage;
    }

    /**
     * 公开详情 —— 对应 Go {@code getPublicArticle}（{@code get_article_logic.go:126-168}）。
     *
     * <p><b>⚠️ 三个返回点</b>：NotFound（不可见）、Internal（读失败）、NotFound（撤回赢了竞态）。
     * 最后一个 NotFound 的<b>文案不同</b>（{@code "article no longer published"}），
     * 因为它表达的是"读的过程中被撤下"，与"本来就没发布"是不同的运维信号。
     *
     * @param articleId 文章 ID
     * @param incrView  是否计阅读量
     * @return 已批准版本的视图数据
     */
    // 🔴 **本方法刻意不加任何 @Transactional** —— Go 出处：get_article_logic.go:126-168 / model/article_public.go:34-57
    //
    // ⚠️ 曾经写成 `@Transactional(readOnly = true)`，那会让**每一次 incr_view=true 的公开详情调用都失败**：
    //   本方法内含 incrPublicViewCount 这条 **UPDATE**（:118-126），而 readOnly 事务在 PG 侧是
    //   `BEGIN READ ONLY`（pgjdbc 由 Connection.setReadOnly(true) 触发，见 Connection.setReadOnly → QueryExecutorImpl
    //   的 beginReadOnlyTransactionQuery 常量），任何 UPDATE 都会被 PG 拒：
    //   `25006 cannot execute UPDATE in a read-only transaction`
    //   ⇒ 异常不是 ArticleExceptions.*，被 ArticleGrpcService 的 catch(RuntimeException) 兜成 UNKNOWN
    //   ⇒ 业务码 1015，而 Go 侧成功返回文章。
    //   ⚠️ 该缺陷 79 条既有测试全绿也发现不了：ArticlePublicDomainServiceViewTest 全 Mockito（不经过代理 ⇒ 事务不生效），
    //      两个 Testcontainers 类都没有经代理调用过 getPublicArticle(.., true)。
    //
    // 语义基准：Go 的这条路径**没有事务**——3 条 SELECT、一次 MinIO 读、一次 UPDATE 各自 autocommit。
    // 「去掉注解」正是对齐它；加 `@Transactional`（readOnly=false）会**新增**一个 Go 没有的快照边界。
    // 对比：本类的 listPublicArticles（:144）**必须**保留 readOnly + REPEATABLE_READ，因为那里
    // count + 分页 + 取 revision 需要同一快照，且**不含任何写入**（Go article_public.go:65,128 同构）。
    public PublicArticleView getPublicArticle(String articleId, boolean incrView) {
        ArticleEntity article = readPublication(() -> publicMapper.selectPublicById(articleId));
        if (article == null) {
            // Go: err == gorm.ErrRecordNotFound ⇒ status.Error(codes.NotFound, "article not published")
            throw ArticleExceptions.articleNotPublished();
        }

        // Go: pointer, err := m.Publication(ctx, id)
        //     if err == ErrRecordNotFound && article.Status == 2 { return result, nil }   // legacy：Revision 保持 nil
        //     if pointer.State != "published" || pointer.CurrentRevision == "" { return NotFound }
        ArticlePublicMapper.ArticlePublicationRow pointer =
                readPublication(() -> publicMapper.selectPublication(articleId));
        ArticleRevisionEntity revision = null;
        if (pointer == null) {
            // 无指针行：仅当 legacy（status == PUBLISHED）才继续，且 Revision 保持 nil
            if (article.getStatus() != ArticleStatus.PUBLISHED_VALUE) {
                throw ArticleExceptions.articleNotPublished();
            }
        } else if (!"published".equals(pointer.getState())
                || pointer.getCurrentRevision() == null || pointer.getCurrentRevision().isEmpty()) {
            throw ArticleExceptions.articleNotPublished();
        } else {
            revision = readPublication(
                    () -> revisionMapper.selectByRevisionId(articleId, pointer.getCurrentRevision()));
            if (revision == null) {
                // Go: RevisionByID 出错 ⇒ return PublicArticle{}, err
                //     （selectPublicById 的谓词已保证 EXISTS，这里是防御）
                throw ArticleExceptions.readPublicationFailed();
            }
        }

        String markdown;
        if (revision != null) {
            // 🔴 已批准正文在 DB 里，**不读对象存储**
            markdown = revision.getMarkdown();
        } else {
            // legacy：无 revision 指针 ⇒ 回落到原 MinIO 对象（不伪造 revision）
            markdown = readLegacyMarkdown(articleId, article.getContent());
        }

        if (incrView) {
            int affected = incrementPublicView(articleId);
            if (affected == 0) {
                // Go: if !increased { return NotFound "article no longer published" }
                // 撤回赢了竞态：不能返回陈旧结果，也不能给下架文章计阅读量
                throw ArticleExceptions.articleNoLongerPublished();
            }
            article.setViewCount(article.getViewCount() + 1);
        }

        return buildView(article, revision, markdown);
    }

    /**
     * 公开列表 —— 对应 Go {@code listPublicArticles}（{@code list_articles_logic.go:103-133}）。
     *
     * <h2>🔴 {@code isolation = REPEATABLE_READ} 不是可选项</h2>
     * Go {@code article_public.go:65,128} 把 count + 分页 + revision 取值包在
     * {@code Transaction(fn, &sql.TxOptions{Isolation: LevelRepeatableRead, ReadOnly: true})} 里，
     * 注释写明：<b>「A repeatable-read snapshot keeps the count, page, and revision pointers
     * consistent when an article is edited or retracted concurrently」</b>。
     * <br>⇒ 去掉隔离级别后，count 与 page 可能来自<b>两个不同快照</b>：
     * 并发撤回一篇文章后，total 仍含它但本页取不到它（或反之），
     * 客户端据此算出的总页数会与实际能翻到的内容不符。
     * 锁死用例：{@code ArticlePublicListSnapshotTest}。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ListArticlesResponse listPublicArticles(ListArticlesRequest request) {
        // Go listPublicArticles 先把 opt 传给 ListPublic，在那里做 page/size 归一
        int page = request.getPage();
        int size = request.getPageSize();
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        } else if (size > 100) {
            size = 100;
        }

        String manualTypeTag = request.hasManualTypeTag() ? request.getManualTypeTag() : null;
        String secondaryTag = request.hasSecondaryTag() ? request.getSecondaryTag() : null;
        String authorId = request.hasAuthorId() ? request.getAuthorId() : null;

        // Go: json.Marshal([]string{opt.SecondaryTag}) 再拼进 SQL 的 @> 算子
        // ⚠️ 用 JSON 数组字面量而不是把 tag 直接拼进 SQL 字符串：
        //    Go 侧是拼进 fmt.Sprintf 的（存在引号转义风险），Java 侧改为**参数绑定**
        //    + CAST(? AS jsonb)，既避开注入也避开转义分歧；两侧语义等价。
        String secondaryTagJson = (secondaryTag == null || secondaryTag.isEmpty())
                ? null
                : JsonbValueTypeHandler.writeJson(List.of(secondaryTag));

        long total = publicMapper.countPublic(manualTypeTag, secondaryTagJson, authorId);

        List<ArticleEntity> articles = publicMapper.selectPublicPage(
                manualTypeTag, secondaryTagJson, authorId,
                request.getSortBy(), request.getDesc(), size, (long) (page - 1) * size);

        // 批量取当前指针指向的 revision（Go: article_public.go:109-123）
        Map<String, ArticleRevisionEntity> revisionsByArticle = new HashMap<>();
        if (articles != null && !articles.isEmpty()) {
            List<String> ids = new ArrayList<>(articles.size());
            for (ArticleEntity a : articles) {
                ids.add(a.getId());
            }
            for (ArticleRevisionEntity r : publicMapper.selectRevisionsForPublished(ids)) {
                revisionsByArticle.put(r.getArticleId(), r);
            }
        }

        List<Article> result = new ArrayList<>();
        if (articles != null) {
            for (ArticleEntity a : articles) {
                // ⚠️ markdown 恒传 ""：公开列表不返回正文，也不返回对象 key
                result.add(toProtoArticle(buildView(a, revisionsByArticle.get(a.getId()), "")));
            }
        }

        return ListArticlesResponse.newBuilder()
                .addAllArticles(result)
                .setTotal(total)
                .setPage(page)
                .setPageSize(size)
                .build();
    }

    // ==================================================================
    // 视图构造
    // ==================================================================

    /**
     * legacy 文章的正文读取 —— 对应 Go {@code get_article_logic.go:140-155}。
     *
     * <p><b>两条不同的失败</b>，Go 侧<b>文案相同</b>（都是 {@code "read legacy article failed"}）：
     * GetObject 失败与 ReadAll 失败。照搬 —— 区分它们会改变客户端可见的 description。
     */
    /**
     * Go {@code FindPublicOne} 的错误边界 —— <b>公开读的三处 DB 失败一律映射成 {@code INTERNAL}</b>。
     *
     * <p>Go 出处（{@code model/article_public.go:34-57}）：
     * <pre>
     * func (m *ArticleRepo) FindPublicOne(ctx context.Context, id string) (PublicArticle, error) {
     *     var article Article
     *     if err := publicArticleQuery(...).First(&amp;article).Error; err != nil {
     *         return PublicArticle{}, err            // ① 文章行读失败
     *     }
     *     …
     *     pointer, err := m.Publication(ctx, id)
     *     if err == gorm.ErrRecordNotFound &amp;&amp; article.Status == 2 { return result, nil }
     *     if err != nil { return PublicArticle{}, err }   // ② 指针行读失败
     *     …
     *     revision, err := m.RevisionByID(ctx, id, pointer.CurrentRevision)
     *     if err != nil { return PublicArticle{}, err }   // ③ revision 读失败
     *     …
     * }
     * </pre>
     * ⇒ 三处失败<b>返回的是同一种 error</b>，api 层统一映射：
     * <pre>
     * // internal/logic/get_article_logic.go:129-135
     * if err == gorm.ErrRecordNotFound { return nil, status.Error(codes.NotFound, "article not published") }
     * if err != nil {
     *     logger.LogBusinessErr(l.ctx, errmsg.ErrorDbSelect, err, logger.WithArticleID(in.ArticleId))
     *     return nil, status.Error(codes.Internal, "read publication failed")   // ← **INTERNAL**
     * }
     * </pre>
     *
     * <p>🔴 <b>为什么这里必须显式包一层</b>：
     * RAG 侧把 Go 的一次 {@code FindPublicOne} 拆成了<b>三次</b> mapper 调用，
     * 若不包，{@code DataAccessException} 会一路冒到
     * {@code ArticleGrpcService} 的 {@code catch (RuntimeException)}
     * ⇒ 落 {@code UNKNOWN} ⇒ <b>业务码 5001 被换成 1015</b>（DRIFT-ARTICLE-RPC-003）。
     * <p>而 {@code ArticleExceptions.incrementPublicViewFailed()} 与
     * {@code readPublicationFailed()} 这两个工厂方法<b>本来就是为此准备的</b>
     * —— 其中前者在本轮之前<b>零调用点</b>，本身就是「这条 INTERNAL 曾被设计出来、但接线漏了」的证据。
     */
    private static <T> T readPublication(Supplier<T> read) {
        try {
            return read.get();
        } catch (ArticleExceptions.Rpc e) {
            // 领域异常（Rpc）不是 DB 失败，不该被吞成 INTERNAL
            throw e;
        } catch (RuntimeException e) {
            // Go: status.Error(codes.Internal, "read publication failed")
            LOG.warn("public read failed ⇒ INTERNAL \"read publication failed\": {}", e.toString());
            throw ArticleExceptions.readPublicationFailed();
        }
    }

    /**
     * Go {@code IncrPublicViewCount} 的错误边界 —— 失败 ⇒ {@code INTERNAL "increment public view failed"}。
     *
     * <p>Go 出处（{@code internal/logic/get_article_logic.go:157-163}）：
     * <pre>
     * increased, err := l.svcCtx.ArticleRepo.IncrPublicViewCount(l.ctx, in.ArticleId)
     * if err != nil {
     *     logger.LogBusinessErr(l.ctx, errmsg.ErrorDbUpdate, err, logger.WithArticleID(in.ArticleId))
     *     return nil, status.Error(codes.Internal, "increment public view failed")
     * }
     * if !increased { return nil, status.Error(codes.NotFound, "article no longer published") }
     * </pre>
     * ⚠️ <b>注意两个分支的码不同</b>：<b>失败</b>⇒ INTERNAL（5001），
     * <b>影响 0 行</b>⇒ NOT_FOUND（1002，撤回赢了竞态）⇒ 两者<b>不可合并</b>。
     */
    private int incrementPublicView(String articleId) {
        try {
            return publicMapper.incrPublicViewCount(articleId);
        } catch (RuntimeException e) {
            LOG.warn("increment public view failed ⇒ INTERNAL \"increment public view failed\": {}",
                    e.toString());
            throw ArticleExceptions.incrementPublicViewFailed();
        }
    }

    private String readLegacyMarkdown(String articleId, String objectName) {
        try {
            return storage.readString(objectName);
        } catch (Exception e) {
            throw ArticleExceptions.readLegacyArticleFailed();
        }
    }

    /**
     * 对应 Go {@code publishedArticleResponse}（{@code get_article_logic.go:170-189}）。
     *
     * <h2>⚠️ 有 revision 与 legacy 的字段来源<b>完全不同</b></h2>
     * <table border="1">
     *   <caption>字段取值来源</caption>
     *   <tr><th>字段</th><th>有 revision</th><th>legacy（无 revision）</th></tr>
     *   <tr><td>title / brief / cover_image_url</td><td><b>revision</b></td><td>article 行</td></tr>
     *   <tr><td>manual_type_tag / secondary_tags</td><td><b>revision</b></td><td>article 行</td></tr>
     *   <tr><td>author_id</td><td><b>revision</b></td><td>article 行</td></tr>
     *   <tr><td>update_time</td><td><b>revision.published_at</b></td><td>article.updated_at</td></tr>
     *   <tr><td>create_time</td><td>article.created_at</td><td>article.created_at</td></tr>
     *   <tr><td>status</td><td colspan="2"><b>恒 PUBLISHED</b>（不是 article.status）</td></tr>
     *   <tr><td>ext_info</td><td>{@code {published_revision_id: …}}</td>
     *       <td>{@code {publication_gap: legacy_revision_missing}}</td></tr>
     * </table>
     * ⇒ <b>用 article 行的标题去覆盖读者</b>是本组件最容易引入的一类漂移：
     * 作者改了草稿标题，读者会立刻看到未审核的版本。
     * Go 测试 {@code article_public_test.go:168-172} 专门断言这一点。
     */
    static PublicArticleView buildView(ArticleEntity article, ArticleRevisionEntity revision, String markdown) {
        PublicArticleView view = new PublicArticleView();
        view.id = article.getId();
        view.title = article.getTitle();
        view.brief = article.getBrief();
        view.markdown = markdown;
        view.coverImageUrl = article.getCoverImageUrl();
        view.manualTypeTag = article.getManualTypeTag();
        view.secondaryTags = article.getSecondaryTags();
        view.authorId = article.getAuthorId();
        view.createTimeMs = article.getCreatedAt() == null ? 0L : article.getCreatedAt().toInstant().toEpochMilli();
        view.updateTimeMs = article.getUpdatedAt() == null ? 0L : article.getUpdatedAt().toInstant().toEpochMilli();
        // ⚠️ 恒 PUBLISHED：公开读者永远看不到非发布状态
        view.status = ArticleStatus.PUBLISHED_VALUE;
        view.viewCount = article.getViewCount();
        view.likeCount = article.getLikeCount();
        view.commentCount = article.getCommentCount();
        view.shareCount = article.getShareCount();
        view.extInfo = new LinkedHashMap<>();
        view.extInfo.put(ArticleSyncEvent.LEGACY_GAP_KEY, ArticleSyncEvent.LEGACY_GAP_VALUE);

        if (revision != null) {
            view.title = revision.getTitle();
            view.brief = revision.getBrief();
            view.coverImageUrl = revision.getCoverImageUrl();
            view.manualTypeTag = revision.getManualTypeTag();
            view.secondaryTags = revision.getSecondaryTags();
            view.authorId = revision.getAuthorId();
            // ⚠️ update_time 取的是**发布时间**，不是源行更新时间
            view.updateTimeMs = revision.getPublishedAt() == null
                    ? 0L : revision.getPublishedAt().toInstant().toEpochMilli();
            view.extInfo = new LinkedHashMap<>();
            view.extInfo.put("published_revision_id", revision.getRevisionId());
        }
        return view;
    }

    /** 把内部视图转成 proto 响应对象。 */
    static Article toProtoArticle(PublicArticleView view) {
        Article.Builder builder =
                Article.newBuilder()
                        .setId(nullToEmpty(view.id))
                        .setTitle(nullToEmpty(view.title))
                        .setBrief(nullToEmpty(view.brief))
                        .setMarkdownContent(nullToEmpty(view.markdown))
                        .setCoverImageUrl(nullToEmpty(view.coverImageUrl))
                        .setManualTypeTag(nullToEmpty(view.manualTypeTag))
                        .setAuthorId(nullToEmpty(view.authorId))
                        .setCreateTime(view.createTimeMs)
                        .setUpdateTime(view.updateTimeMs)
                        .setStatus(ArticleStatus.forNumber(view.status))
                        .setViewCount(view.viewCount)
                        .setLikeCount(view.likeCount)
                        .setCommentCount(view.commentCount)
                        .setShareCount(view.shareCount);
        if (view.secondaryTags != null) {
            builder.addAllSecondaryTags(view.secondaryTags);
        }
        if (view.extInfo != null) {
            builder.putAllExtInfo(view.extInfo);
        }
        return builder.build();
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 公开文章的视图数据 —— 对应 Go 的 {@code model.PublicArticle{Article, Revision}} 加上
     * 已解析好的 markdown（Go 是在 response 构造时现算，Java 侧先算好再转 proto）。
     */
    public static class PublicArticleView {
        public String id;
        public String title;
        public String brief;
        public String markdown;
        public String coverImageUrl;
        public String manualTypeTag;
        public List<String> secondaryTags;
        public String authorId;
        public long createTimeMs;
        public long updateTimeMs;
        public int status;
        public int viewCount;
        public int likeCount;
        public int commentCount;
        public int shareCount;
        public Map<String, String> extInfo;
    }
}
