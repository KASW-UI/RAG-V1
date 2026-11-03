package com.rag.article.domain.service;

import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.config.ArticleRpcProperties;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.contract.CommunityArticleEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.exception.ArticleExceptions;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.mapper.JsonbTypeHandler;
import com.rag.article.metrics.ArticleRpcMetrics;
import com.rag.common.snowflake.Snowflake;
import com.rag.proto.article.Article;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.CreateArticleRequest;
import com.rag.proto.article.CreateArticleResponse;
import com.rag.proto.article.DeleteArticleRequest;
import com.rag.proto.article.DeleteArticleResponse;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import com.rag.proto.article.ListArticlesRequest;
import com.rag.proto.article.ListArticlesResponse;
import com.rag.proto.article.UpdateArticleRequest;
import com.rag.proto.article.UpdateArticleResponse;
import com.rag.proto.article.UploadFileRequest;
import com.rag.proto.article.UploadFileResponse;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：article 域的六个用例（作者视角 + 文件上传）—— 对应 Go 端 {@code internal/logic/}
 * 下的 {@code create / get / update / delete / list / upload_file} 六个 logic。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/service/ArticleDomainService.java}
 * （类声明 {@code :57-58} · 构造器 {@code :71-87} · {@code createArticle} {@code :109-124} ·
 * {@code createArticleBody} {@code :127-207} · {@code getArticle} {@code :226-251} ·
 * {@code getArticleBody} {@code :254-329} · {@code toAuthorProtoArticle} {@code :332-357} ·
 * {@code updateArticle} {@code :392-400} · {@code updateArticleBody} {@code :403-519} ·
 * {@code applySourceUpdates} {@code :535-576} · {@code articleMarkdownObjectName} {@code :579-588} ·
 * {@code setReviewNonce} {@code :591-595} · {@code enqueueReview} {@code :603-614} ·
 * {@code deleteArticle} {@code :636-644} · {@code deleteArticleBody} {@code :647-692} ·
 * {@code listArticles} {@code :716-736} · {@code listArticlesBody} {@code :739-802} ·
 * {@code uploadFile} {@code :830-838} · {@code uploadFileBody} {@code :841-887} ·
 * {@code guessContentType} {@code :901-909} · {@code newEventId} {@code :916-918} ·
 * {@code reviewTopic} {@code :929-931} · {@code nullToEmpty} {@code :933-935}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：五处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.service} →
 *             {@code com.rag.article.domain.service}（D7 包压平）；</li>
 *         <li><b>proto 包名</b>：{@code com.rag.article.article.proto.*} →
 *             {@code com.rag.proto.article.*}（D7 proto 集中）⇒
 *             {@code ArticleStatus} 也随之换包（<b>枚举名与取值一字未改</b>）；</li>
 *         <li><b>常量宿主类改名</b>：B 侧 {@code ArticleSyncEventTypes}（工具类）
 *             ⇒ C 侧拆成 {@link ArticleSyncEvent}（同步事件契约）＋
 *             {@link CommunityArticleEvent}（社区事件契约）两个 record
 *             ⇒ <b>所有被引用的常量与工厂方法名逐字保留</b>
 *             （{@code EXT_*} / {@code OP_*} / {@code REASON_*} / {@code LEGACY_GAP_*} /
 *             {@code H03_GAP_KEY} / {@code ARTICLE_SYNC_SCOPE} /
 *             {@code ensureExtInfo} / {@code setSyncState} / {@code newArticleSyncEvent} /
 *             {@code articleSyncEventKey} / {@code articleDomainEvent}）；</li>
 *         <li><b>span 常量宿主类合并</b>：B 侧 {@code ArticleSpans} ⇒ C 侧并入
 *             {@link ArticleRpcMetrics}（映射表 §1.6 已批准的 1:1 合并）⇒
 *             <b>span 名 / 属性名 / 事件名逐字未改</b>；</li>
 *         <li><b>雪花 ID 取法</b>：B 侧注入 {@code com.rag.common.spring.SnowflakeBean}
 *             ⇒ C 侧改用 {@link Snowflake#nextId()}（<b>静态</b>）。
 *             ⚠️ <b>语义等价论证</b>：B 侧 {@code SnowflakeBean.nextId()} 的方法体就是
 *             {@code return Snowflake.nextId();}（B 侧 {@code service/common/.../spring/SnowflakeBean.java:79-81}）
 *             ⇒ 两者<b>调用同一个静态状态、同一个算法</b>（epoch {@code 1288834974657} +
 *             41/10/12 位宽）⇒ <b>生成的 ID 序列完全相同</b>；
 *             差别只在「谁触发 workerId 解析」（B 侧 = Bean 构造器；C 侧 =
 *             {@code common.autoconfigure.SnowflakeAutoConfiguration} 的
 *             {@code @Bean} 或 {@link Snowflake#init()} 的懒初始化兜底）——
 *             两者<b>都写进同一份静态 {@code STATE}</b> ⇒ <b>无差异</b>。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>6 个用例的语句顺序逐字未动</b>（create 的 4 步、update 的 A/B/C 三分支、
 *             delete 的校验顺序、list 的作者/公开两套分页）；</li>
 *         <li><b>异常类型与文案逐字未动</b>（{@link ArticleExceptions} 的每个工厂都对应 B 侧同一位置）；</li>
 *         <li><b>metric 埋点逐字未动</b>（6 个指标的方法名与实参逐字未改）；</li>
 *         <li><b>span 的属性 / 事件逐字未动</b>（含「公开分支无 span」「记了但不返回」这类形状）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <h2>为什么六个 logic 合并成一个 Bean</h2>
 * 它们有<b>同一变化原因</b>（文章领域用例）与<b>同一依赖边界</b>（article 表 + MinIO + Kafka）。
 * 拆分到六个类的收益只是文件名对应，而代价是六个几乎相同的依赖清单。
 * <br>⚠️ <b>但多步事务不能放在这里</b>：它们在 {@link ArticleAtomicService}
 * （self-invocation 会让 {@code @Transactional} 静默失效，AGENTS.md §3.3）。
 * 公开读路径同理在 {@link ArticlePublicDomainService}（不同持久化语义）。
 *
 * <h2>⚠️ 本类的 6 个方法与 Go logic 的<b>语句顺序逐条一致</b></h2>
 * 尤其是 create：<b>先 MinIO 后 DB</b>（Go {@code create_article_logic.go:61-113}）。
 * 这意味着 MinIO 成功但 DB 失败时会留下孤儿对象 —— <b>Go 明确接受</b>：
 * 「孤儿可接受，因文件可再生」（FileTree §12.3）。
 * 不要"改成先写 DB"或"加补偿删除"：那会改变失败时的可观察状态。
 *
 * <h2>⚠️ 日志：<b>不用</b> {@code @Slf4j}</h2>
 * 本仓的日志契约走 {@code common.logger.GoStyleLogger}（带 B 侧 {@code call_chain} 字段 · A8），
 * <b>禁止</b> Lombok 的 {@code @Slf4j}（D18 / §1.0e 明列）。
 * <br>⚠️ 但本类<b>刻意</b>用<b>普通 slf4j</b>（{@code LoggerFactory.getLogger}）而不是
 * {@code GoStyleLogger}：B 侧本类<b>就是</b> {@code LoggerFactory.getLogger(ArticleDomainService.class)}
 * （B 侧 {@code :60}），<b>只调用</b> {@code LOG.warn(...)} 一处（B 侧 {@code :293-294}），
 * <b>没有</b>任何 {@code logBusinessErr} / {@code call_chain} 语义的调用点
 * ⇒ ⇒ 换成 {@code GoStyleLogger} 属于<b>凭空新增</b>日志字段（表达层变更且无 B 侧依据）
 * ⇒ ⇒ <b>照抄普通 slf4j</b>，这是「零无语义差异」的最小改法。
 */
@Service
public class ArticleDomainService {

    private static final Logger LOG = LoggerFactory.getLogger(ArticleDomainService.class);

    private final ArticleMapper articleMapper;
    private final ArticleObjectStorageClient storage;
    private final ArticleAtomicService atomicService;
    private final ArticlePublicDomainService publicService;
    private final ArticleRpcMetrics metrics;
    private final ArticleRpcProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public ArticleDomainService(ArticleMapper articleMapper,
                                ArticleObjectStorageClient storage,
                                ArticleAtomicService atomicService,
                                ArticlePublicDomainService publicService,
                                ArticleRpcMetrics metrics,
                                ArticleRpcProperties properties,
                                KafkaTemplate<String, String> kafkaTemplate) {
        this.articleMapper = articleMapper;
        this.storage = storage;
        this.atomicService = atomicService;
        this.publicService = publicService;
        this.metrics = metrics;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    // ==================================================================
    // 1. CreateArticle
    // ==================================================================

    /**
     * 对应 Go {@code create_article_logic.go:42-133}。
     *
     * <p><b>语句顺序（不可调换）</b>：
     * <ol>
     *   <li>雪花 ID（失败 ⇒ Internal）</li>
     *   <li>MinIO put {@code <articlePath><id>.md}，{@code text/markdown}
     *       （失败 ⇒ <b>Internal</b>，且<b>不</b>计 file_upload 指标）</li>
     *   <li>插入 article 行（status = <b>REVIEWING</b>，ext_info 写 8 个初始键）</li>
     *   <li>推 Kafka 审核消息（key = articleID；失败 ⇒ <b>Internal</b>，<b>行已插入不回滚</b>）</li>
     * </ol>
     *
     * <p>⚠️ <b>第 4 步失败不回滚第 3 步的行</b>：Go 没有事务，文章行会留下一条
     * {@code REVIEWING} 且无审核任务的记录。这是 Go 的既有行为
     * （人工/补偿可恢复），<b>不要"顺手"加上事务</b>。
     */
    public CreateArticleResponse createArticle(CreateArticleRequest in) {
        // 🔴 L-10（Go 出处：create_article_logic.go:42-48）——Go 在方法开头手写：
        //     tracer := otel.Tracer("article-rpc")
        //     ctx, span := tracer.Start(l.ctx, "CreateArticle", trace.WithAttributes(
        //         attribute.String("author_id", in.AuthorId),
        //         attribute.String("title", in.Title),
        //     ))
        //     defer span.End()
        // ⇒ 业务体抽成 createArticleBody 只为**不把整段缩进**；span 仍覆盖整个业务体（行为等价）。
        return ArticleRpcMetrics.traced(ArticleRpcMetrics.CREATE_ARTICLE,
                span -> {
                    span.setAttribute(ArticleRpcMetrics.ATTR_AUTHOR_ID, in.getAuthorId());
                    span.setAttribute(ArticleRpcMetrics.ATTR_TITLE, in.getTitle());
                },
                span -> createArticleBody(in, span));
    }

    /** {@link #createArticle} 的业务体（span 由调用方持有）。 */
    private CreateArticleResponse createArticleBody(CreateArticleRequest in, Span span) {
        long id;
        try {
            id = Snowflake.nextId();
        } catch (RuntimeException e) {
            // Go create_article_logic.go:50-54：雪花 ID 失败 ⇒ span.RecordError(err) 后返回 Internal。
            // ⚠️ 这里**原样上抛**（不改错误码）—— 错误码语义不是 span 工作能改的东西。
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        String articleId = String.valueOf(id);
        // Go create_article_logic.go:57：span.SetAttributes(attribute.String("article_id", articleID))（**后续**属性）
        span.setAttribute(ArticleRpcMetrics.ATTR_ARTICLE_ID, articleId);

        // ① MinIO 写正文（Go: objectName = ArticlePath + articleID + ".md"，注意<b>无哈希</b>）
        String objectName = storage.getObjectNamePrefixForArticle() + articleId + ".md";
        long start = System.nanoTime();
        try {
            storage.put(objectName, in.getMarkdownContent().getBytes(StandardCharsets.UTF_8),
                    "text/markdown", null);
        } catch (Exception e) {
            metrics.minioError("put");
            // Go create_article_logic.go:70-75：MinIO put 失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw ArticleExceptions.raw(e);
        }
        metrics.observeMinio("put", System.nanoTime() - start);
        metrics.fileUpload("markdown");

        // ② 组装实体。ext_info 的 8 个初始键是 L1 契约（消费者按它们判幂等）
        Map<String, String> extInfo = new LinkedHashMap<>();
        extInfo.put(ArticleSyncEvent.EXT_PUBLISH_STAGE, "queued");
        extInfo.put(ArticleSyncEvent.EXT_RECO_SYNC_STATE, "pending_review");
        extInfo.put(ArticleSyncEvent.EXT_LAST_SYNC_ERROR, "");
        extInfo.put(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID, "");
        extInfo.put(ArticleSyncEvent.EXT_LAST_SYNC_VERSION, "0");
        extInfo.put(ArticleSyncEvent.EXT_PENDING_SYNC_REASON, ArticleSyncEvent.REASON_CREATE);
        extInfo.put(ArticleSyncEvent.EXT_LAST_SYNC_REASON, ArticleSyncEvent.REASON_CREATE);
        // ⚠️ review_nonce 初始值 = articleID 本身（Go: mqs.ExtReviewNonce: articleID）
        extInfo.put(ArticleSyncEvent.EXT_REVIEW_NONCE, articleId);

        ArticleEntity article = new ArticleEntity();
        article.setId(articleId);
        article.setTitle(in.getTitle());
        article.setBrief(in.hasBrief() ? in.getBrief() : "");
        article.setContent(objectName);
        article.setCoverImageUrl(in.hasCoverImageUrl() ? in.getCoverImageUrl() : "");
        article.setManualTypeTag(in.getManualTypeTag());
        article.setSecondaryTags(new ArrayList<>(in.getSecondaryTagsList()));
        article.setAuthorId(in.getAuthorId());
        article.setStatus(ArticleStatus.REVIEWING_VALUE);
        article.setExtInfo(extInfo);
        article.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        article.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        try {
            articleMapper.insert(article);
        } catch (RuntimeException e) {
            // Go create_article_logic.go:109-113：插入失败 ⇒ span.RecordError(err) 后返回 Internal。
            // ⚠️ 原样上抛（RAG 现状就是让 MyBatis-Plus 的运行时异常逃出）—— 不改错误码。
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        metrics.article("create");
        metrics.articleStatus("reviewing");

        // ③ 推审核消息（Go: ReviewNonce = articleID，作幂等键）
        ArticleSyncEvent.ArticleReviewMessage msg = new ArticleSyncEvent.ArticleReviewMessage(
                articleId, in.getAuthorId(), objectName, articleId);
        try {
            kafkaTemplate.send(reviewTopic(), articleId, JsonbTypeHandler.writeJson(msg)).get();
        } catch (Exception e) {
            metrics.kafkaPushError("article_review");
            // Go create_article_logic.go:125-130：推送失败 ⇒ span.RecordError(err) + Internal（**行不回滚**）
            ArticleRpcMetrics.record(span, e);
            // Go: status.Error(codes.Internal, err.Error()) —— 注意行不回滚
            throw ArticleExceptions.internal(e.getMessage(), e);
        }

        return CreateArticleResponse.newBuilder().setArticleId(articleId).build();
    }

    // ==================================================================
    // 2. GetArticle
    // ==================================================================

    /**
     * 对应 Go {@code get_article_logic.go:40-124} + 公开分支 {@code :126-168}。
     *
     * <p><b>⚠️ 三条分支的入口判定顺序不可调换</b>：
     * <pre>
     * if in.PublicOnly { return getPublicArticle(in) }              // ① 最先
     * if in.RequesterId != "" &amp;&amp; in.IncrView {
     *     return InvalidArgument("author read cannot increment view") // ② 其次
     * }
     * </pre>
     * ⇒ {@code PublicOnly=true} 时<b>跳过</b>第 ② 条的校验。
     * 把两条判断调换会让"公开读 + 作者身份 + 计阅读量"从 InvalidArgument 变成正常返回。
     */
    public GetArticleResponse getArticle(GetArticleRequest in) {
        // ① 公开读走独立路径（读者视角：冻结 revision）
        //    🔴 L-10（Go 出处：get_article_logic.go:41-43）：Go 的公开分支在 tracer.Start **之前**就 return
        //       ⇒ **这条路径根本不存在 span** ⇒ 本实现也**不得**建 span。
        if (in.getPublicOnly()) {
            ArticlePublicDomainService.PublicArticleView view =
                    publicService.getPublicArticle(in.getArticleId(), in.getIncrView());
            return GetArticleResponse.newBuilder()
                    .setArticle(ArticlePublicDomainService.toProtoArticle(view))
                    .build();
        }

        // ② 作者身份 + 计阅读量 = 非法组合
        //    🔴 Go 出处：get_article_logic.go:44-46 —— 同样在 span **之前** ⇒ 这条路径也**没有 span**。
        if (!in.getRequesterId().isEmpty() && in.getIncrView()) {
            throw ArticleExceptions.authorReadCannotIncrementView();
        }

        // 🔴 L-10（Go 出处：get_article_logic.go:47-51）：span 从这里才开始
        //     tracer := otel.Tracer("article-rpc")
        //     ctx, span := tracer.Start(l.ctx, "GetArticle", trace.WithAttributes(attribute.String("article_id", in.ArticleId)))
        //     defer span.End()
        return ArticleRpcMetrics.traced(ArticleRpcMetrics.GET_ARTICLE,
                span -> span.setAttribute(ArticleRpcMetrics.ATTR_ARTICLE_ID, in.getArticleId()),
                span -> getArticleBody(in, span));
    }

    /** {@link #getArticle} 的业务体（span 由调用方持有）。 */
    private GetArticleResponse getArticleBody(GetArticleRequest in, Span span) {
        // ③ 作者视角：读当前源行
        //    🔴 Go get_article_logic.go:53：span.AddEvent("start find article") —— 在 FindOne **之前**
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_START_FIND_ARTICLE);
        ArticleEntity article;
        try {
            article = articleMapper.selectById(in.getArticleId());
        } catch (RuntimeException e) {
            // Go get_article_logic.go:59-62：FindOne 的**非** NotFound 错误 ⇒ span.RecordError(err) + 原样返回
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        if (article == null) {
            // Go get_article_logic.go:56-58：ErrRecordNotFound ⇒ span.SetAttributes(article_found=false)
            //    ⚠️ **不 RecordError**（业务失败，Go 侧不记）⇒ 本实现也不记。
            span.setAttribute(ArticleRpcMetrics.ATTR_ARTICLE_FOUND, false);
            throw ArticleExceptions.articleNotFound();
        }
        // Go get_article_logic.go:64-65
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_FIND_ARTICLE_SUCCESS);
        span.setAttribute(ArticleRpcMetrics.ATTR_ARTICLE_FOUND, true);

        // ④ 作者校验（仅当传了 requesterId）
        //    🔴 Go get_article_logic.go:66-68：PermissionDenied ⇒ **不 RecordError**（Go 侧不记）
        if (!in.getRequesterId().isEmpty() && !article.getAuthorId().equals(in.getRequesterId())) {
            throw ArticleExceptions.articleAuthorDiffers();
        }

        // ⑤ 浏览量自增：🔴 best-effort，失败只记日志（Go :72-75）
        if (in.getIncrView()) {
            // Go get_article_logic.go:71：span.AddEvent("start incr view count")
            ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_START_INCR_VIEW_COUNT);
            try {
                articleMapper.incrViewCount(in.getArticleId());
            } catch (Exception e) {
                // ⚠️ 此处**不能记任何指标**。Go get_article_logic.go:70-78 失败时只 LogBusinessErr；
                //    曾调 minioError("get") —— 那是一次 **DB 写失败**，记到 MinIO 错误计数上会脏化
                //    「对象存储是否故障」这个核心信号，让 MinIO 告警误报。
                //    并且 Go 这条路径的该计数器永远是 0。
                LOG.warn("increment article view count failed (best-effort, ignored): articleId={} err={}",
                        in.getArticleId(), e.getMessage());
                // Go get_article_logic.go:73：span.RecordError(err) —— 🔴 **记了但不返回**（继续往下走）
                ArticleRpcMetrics.record(span, e);
            }
            article.setViewCount(article.getViewCount() + 1);
            // Go get_article_logic.go:77：span.AddEvent("incr view count success")
            ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_INCR_VIEW_COUNT_SUCCESS);
        }

        // ⑥ 读正文（MinIO）
        //    🔴 L-10：Go 把「取对象」与「读对象」分成**两阶段**，各带一对事件
        //       （get_article_logic.go:82 / :93 与 :95 / :102）；
        //       而 RAG 的 storage.readString 是**一次**调用（= readAll(get(...))）⇒
        //       ⇒ 成功路径按 Go 的**事件顺序**发全 4 个（保持事件集合一致）；
        //       ⇒ 失败路径无法区分是哪一阶段失败 ⇒ 只发第 1 个（与 Go「get 阶段失败」同形）。
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_START_GET_MINIO_OBJECT);
        String markdown;
        long start = System.nanoTime();
        try {
            markdown = storage.readString(article.getContent());
        } catch (Exception e) {
            metrics.minioError("get");
            // Go get_article_logic.go:86 / :98：读对象失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw ArticleExceptions.raw(e);
        }
        metrics.observeMinio("get", System.nanoTime() - start);
        // Go get_article_logic.go:93 / :95 / :102
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_GET_MINIO_OBJECT_SUCCESS);
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_START_READ_MINIO_OBJECT);
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_READ_MINIO_OBJECT_SUCCESS);

        return GetArticleResponse.newBuilder()
                .setArticle(toAuthorProtoArticle(article, markdown))
                .build();
    }

    /** 作者视角的 article → proto（字段全部取<b>当前源行</b>，与公开视角不同）。 */
    static Article toAuthorProtoArticle(ArticleEntity a, String markdown) {
        Article.Builder builder = Article.newBuilder()
                .setId(nullToEmpty(a.getId()))
                .setTitle(nullToEmpty(a.getTitle()))
                .setBrief(nullToEmpty(a.getBrief()))
                .setMarkdownContent(nullToEmpty(markdown))
                .setCoverImageUrl(nullToEmpty(a.getCoverImageUrl()))
                .setManualTypeTag(nullToEmpty(a.getManualTypeTag()))
                .setAuthorId(nullToEmpty(a.getAuthorId()))
                .setCreateTime(a.getCreatedAt() == null ? 0L : a.getCreatedAt().toInstant().toEpochMilli())
                .setUpdateTime(a.getUpdatedAt() == null ? 0L : a.getUpdatedAt().toInstant().toEpochMilli())
                // ⚠️ 作者视角 status 是**真实状态**（可能是 REVIEWING），与公开视角恒 PUBLISHED 不同
                .setStatus(ArticleStatus.forNumber(a.getStatus()))
                .setViewCount(a.getViewCount())
                .setLikeCount(a.getLikeCount())
                .setCommentCount(a.getCommentCount())
                .setShareCount(a.getShareCount());
        if (a.getSecondaryTags() != null) {
            builder.addAllSecondaryTags(a.getSecondaryTags());
        }
        // Go cloneStringMap: 空 map ⇒ 返回 nil（protobuf 里 map 为空 = 不输出该字段）
        if (a.getExtInfo() != null && !a.getExtInfo().isEmpty()) {
            builder.putAllExtInfo(a.getExtInfo());
        }
        return builder.build();
    }

    // ==================================================================
    // 3. UpdateArticle —— 三条分支
    // ==================================================================

    /**
     * 对应 Go {@code update_article_logic.go:46-127}。
     *
     * <h2>🔴 三条分支的判定顺序是本方法的核心语义</h2>
     * <pre>
     * switch {
     * case prevPublished &amp;&amp; in.Status != nil &amp;&amp; *in.Status != UNSPECIFIED &amp;&amp; requestedStatus != PUBLISHED:
     *     updatePublishedToSourceOnly(...)                    // A：撤回发布
     * case prevPublished &amp;&amp; sourceChanged:
     *     SetSyncState(queued, pending_review, update, "", now, "")
     *     status = REVIEWING; setReviewNonce; Update; enqueueReview   // B：重审
     * default:
     *     if 显式非发布 { status = requestedStatus }
     *     review := status == REVIEWING &amp;&amp; (sourceChanged || prevStatus != REVIEWING)
     *     if review { setReviewNonce }
     *     Update
     *     if review { enqueueReview }                        // C
     * }
     * </pre>
     *
     * <p><b>⚠️ A 优先于 B</b>：已发布文章<b>同时</b>改了内容并显式降级时，
     * 走 A（撤回发布）而<b>不是</b> B（重审）。B 会把文章留在 PUBLISHED 并重审，
     * 那会让"作者主动降级"变成"重新审核"，是语义反转。
     *
     * <p>⚠️ <b>B 路径<b>不</b>把 last_sync_event_id 清成非空</b>：Go 传的是 {@code ""}，
     * 见 {@code update_article_logic.go:87} 的参数 {@code SetSyncState(article, "queued", "pending_review", …, "", now.UnixMilli(), "")}。
     * 第五个参数是 eventID，置空 ⇒ 审核消费者的幂等判据
     * {@code pending && lastSyncEventID != ""} 为 false ⇒ 新一轮审核可以正常入队。
     */
    public UpdateArticleResponse updateArticle(UpdateArticleRequest in) {
        // 🔴 L-10（Go 出处：update_article_logic.go:46-51）：
        //     tracer := otel.Tracer("article-rpc")
        //     ctx, span := tracer.Start(l.ctx, "UpdateArticle", trace.WithAttributes(attribute.String("article_id", in.ArticleId)))
        //     defer span.End()
        return ArticleRpcMetrics.traced(ArticleRpcMetrics.UPDATE_ARTICLE,
                span -> span.setAttribute(ArticleRpcMetrics.ATTR_ARTICLE_ID, in.getArticleId()),
                span -> updateArticleBody(in, span));
    }

    /** {@link #updateArticle} 的业务体（span 由调用方持有）。 */
    private UpdateArticleResponse updateArticleBody(UpdateArticleRequest in, Span span) {
        // ⚠️ Go 这里是**裸返回**（update_article_logic.go:53-63），不是 NotFound：
        //    model/article.go:81-88 的 FindOne 在找不到时返回 (nil, gorm.ErrRecordNotFound)，
        //    而 err.Error() 的字面量就是 "record not found" ⇒ grpc-go 包成 UNKNOWN + 该文案。
        //    注意 Go 里那个 `if article == nil { NotFound }` 分支**不可达**（FindOne 从不返回 (nil, nil)）。
        //    ⇒ 本处用 articleNotFoundMessage() 返回 **UNKNOWN + "record not found"**，
        //      既保住 code（→ api default → 1015），又把文案对齐。
        ArticleEntity article;
        try {
            article = articleMapper.selectById(in.getArticleId());
        } catch (RuntimeException e) {
            // Go update_article_logic.go:54-58：FindOne 失败 ⇒ span.RecordError(err) + 原样返回
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        if (article == null) {
            RuntimeException notFound = ArticleExceptions.articleNotFoundRaw();
            // Go update_article_logic.go:59-63：article == nil ⇒ span.RecordError(NotFound 错误)
            //    🔴 **注意与 get_article 不同**：Go 在这里**记了** NotFound ⇒ 本实现也记。
            ArticleRpcMetrics.record(span, notFound);
            throw notFound;
        }
        int prevStatus = article.getStatus();
        boolean prevPublished = prevStatus == ArticleStatus.PUBLISHED_VALUE;

        boolean sourceChanged;
        try {
            sourceChanged = applySourceUpdates(article, in);
        } catch (RuntimeException e) {
            // Go update_article_logic.go:66-70：applySourceUpdates 失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw e;
        }

        int requestedStatus = prevStatus;
        if (in.hasStatus() && in.getStatus() != ArticleStatus.ARTICLE_STATUS_UNSPECIFIED) {
            requestedStatus = in.getStatusValue();
        }
        long nowMs = System.currentTimeMillis();

        boolean explicitNonPublish = in.hasStatus()
                && in.getStatus() != ArticleStatus.ARTICLE_STATUS_UNSPECIFIED
                && requestedStatus != ArticleStatus.PUBLISHED_VALUE;

        if (prevPublished && explicitNonPublish) {
            // ---------- 分支 A：已发布 → 显式降级 ----------
            //    🔴 Go update_article_logic.go:81-85：整个 updatePublishedToSourceOnly 的错误
            //       （**含内部 newEventID 失败**）都在 :83 一处 RecordError ⇒ 本实现把整块包起来。
            try {
                String eventId = newEventId();
                article.setStatus(requestedStatus);
                Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
                article.setExtInfo(extInfo);
                ArticleSyncEvent.setSyncState(extInfo, "source_only", "pending",
                        ArticleSyncEvent.REASON_STATUS_CHANGE, eventId, nowMs, "");
                atomicService.retractAndUpdate(article, eventId, nowMs,
                        ArticleSyncEvent.REASON_STATUS_CHANGE);
            } catch (RuntimeException e) {
                ArticleRpcMetrics.record(span, e);
                throw e;
            }
        } else if (prevPublished && sourceChanged) {
            // ---------- 分支 B：已发布 + 源变更 → 重审 ----------
            Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
            article.setExtInfo(extInfo);
            ArticleSyncEvent.setSyncState(extInfo, "queued", "pending_review",
                    ArticleSyncEvent.REASON_UPDATE, "", nowMs, "");
            article.setStatus(ArticleStatus.REVIEWING_VALUE);
            // ⚠️ Go update_article_logic.go:89-91：setReviewNonce 失败 ⇒ **直接 return，不 RecordError**
            //    ⇒ 这是 Go「不记这个错误」的三处缺口之一 ⇒ 本实现也**不记**。
            setReviewNonce(article);
            try {
                articleMapper.updateById(article);
            } catch (RuntimeException e) {
                // Go update_article_logic.go:92-96：Update 失败 ⇒ span.RecordError(err)
                ArticleRpcMetrics.record(span, e);
                throw e;
            }
            try {
                enqueueReview(article);
            } catch (RuntimeException e) {
                // Go update_article_logic.go:97-100：enqueueReview 失败 ⇒ span.RecordError(err)
                ArticleRpcMetrics.record(span, e);
                throw e;
            }
        } else {
            // ---------- 分支 C：其余 ----------
            if (explicitNonPublish) {
                article.setStatus(requestedStatus);
            }
            boolean review = article.getStatus() == ArticleStatus.REVIEWING_VALUE
                    && (sourceChanged || prevStatus != ArticleStatus.REVIEWING_VALUE);
            if (review) {
                // ⚠️ Go update_article_logic.go:107-111：setReviewNonce 失败 ⇒ **不 RecordError**（同上）
                setReviewNonce(article);
            }
            try {
                articleMapper.updateById(article);
            } catch (RuntimeException e) {
                // Go update_article_logic.go:112-116：Update 失败 ⇒ span.RecordError(err)
                ArticleRpcMetrics.record(span, e);
                throw e;
            }
            if (review) {
                try {
                    enqueueReview(article);
                } catch (RuntimeException e) {
                    // Go update_article_logic.go:117-122：enqueueReview 失败 ⇒ span.RecordError(err)
                    ArticleRpcMetrics.record(span, e);
                    throw e;
                }
            }
        }

        metrics.article("update");
        return UpdateArticleResponse.newBuilder().setSuccess(true).build();
    }

    /**
     * 应用源字段变更 —— 对应 Go {@code applySourceUpdates}（{@code update_article_logic.go:139-190}）。
     *
     * <h2>⚠️ 正文对象名带内容哈希，这是不可变性的关键</h2>
     * Go {@code articleMarkdownObjectName}（{@code :192-195}）：
     * {@code "<ArticlePath><articleID>-<sha256(markdown)>.md</b>"}。
     * ⇒ <b>每次改内容都写一个新对象</b>，已批准 revision 的 {@code source_object} 指向的对象
     * <b>永不被覆盖</b>。若改成固定名（像 create 那样），已发布的字节会被后续编辑改写，
     * "已发布不可变"当场失效。
     * <br>⚠️ 同理 create 路径<b>不带</b>哈希（{@code <id>.md}）：首版对象名固定，
     * 之后所有编辑都走带哈希的新名。
     *
     * @return 是否发生源变更（决定分支 B / C 的 review 判定）
     */
    private boolean applySourceUpdates(ArticleEntity article, UpdateArticleRequest in) {
        boolean sourceChanged = false;
        if (in.hasTitle()) {
            article.setTitle(in.getTitle());
            sourceChanged = true;
        }
        if (in.hasBrief()) {
            article.setBrief(in.getBrief());
            sourceChanged = true;
        }
        if (in.hasMarkdownContent()) {
            String markdown = in.getMarkdownContent();
            String objectName = articleMarkdownObjectName(
                    storage.getObjectNamePrefixForArticle(), article.getId(), markdown);
            long start = System.nanoTime();
            try {
                storage.put(objectName, markdown.getBytes(StandardCharsets.UTF_8),
                        "text/markdown", null);
            } catch (Exception e) {
                metrics.minioError("put");
                throw ArticleExceptions.raw(e);
            }
            metrics.observeMinio("put", System.nanoTime() - start);
            metrics.fileUpload("markdown");
            article.setContent(objectName);
            sourceChanged = true;
        }
        if (in.hasCoverImageUrl()) {
            article.setCoverImageUrl(in.getCoverImageUrl());
            sourceChanged = true;
        }
        if (in.hasManualTypeTag()) {
            article.setManualTypeTag(in.getManualTypeTag());
            sourceChanged = true;
        }
        // ⚠️ Go 用 len() > 0 判定（update_article_logic.go:184），**不是 hasSecondaryTags**
        if (in.getSecondaryTagsCount() > 0) {
            article.setSecondaryTags(new ArrayList<>(in.getSecondaryTagsList()));
            sourceChanged = true;
        }
        return sourceChanged;
    }

    /** 对应 Go {@code articleMarkdownObjectName}（{@code update_article_logic.go:192-195}）。 */
    static String articleMarkdownObjectName(String prefix, String articleID, String markdown) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(
                    digest.digest(markdown.getBytes(StandardCharsets.UTF_8)));
            return prefix + articleID + "-" + hash + ".md";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 对应 Go {@code setReviewNonce}（{@code update_article_logic.go:129-137}）：换一个新的雪花 ID。 */
    private void setReviewNonce(ArticleEntity article) {
        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
        article.setExtInfo(extInfo);
        extInfo.put(ArticleSyncEvent.EXT_REVIEW_NONCE, newEventId());
    }

    /**
     * 对应 Go {@code enqueueReview}（{@code update_article_logic.go:232-245}）。
     *
     * <p>⚠️ 失败返回 <b>Internal</b>（不是 raw）：Go 这里用 {@code status.Error(codes.Internal, err.Error())}，
     * 而 create 路径也是 Internal。两处一致。
     */
    private void enqueueReview(ArticleEntity article) {
        String reviewNonce = article.getExtInfo() == null
                ? null : article.getExtInfo().get(ArticleSyncEvent.EXT_REVIEW_NONCE);
        ArticleSyncEvent.ArticleReviewMessage msg = new ArticleSyncEvent.ArticleReviewMessage(
                article.getId(), article.getAuthorId(), article.getContent(), reviewNonce);
        try {
            kafkaTemplate.send(reviewTopic(), article.getId(), JsonbTypeHandler.writeJson(msg)).get();
        } catch (Exception e) {
            metrics.kafkaPushError("article_review");
            throw ArticleExceptions.internal(e.getMessage(), e);
        }
    }

    // ==================================================================
    // 4. DeleteArticle
    // ==================================================================

    /**
     * 对应 Go {@code delete_article_logic.go:40-100}。
     *
     * <h2>⚠️ 三条不可改的语义</h2>
     * <ol>
     *   <li><b>操作人校验在事务之外且先于一切</b>：
     *       {@code if in.GetOperatorId() == "" || in.GetOperatorId() != article.AuthorID { PermissionDenied }}
     *       —— 未传操作人<b>与</b>操作人非作者返回<b>同一个</b>码与文案。</li>
     *   <li><b>不删 MinIO 对象</b>（见 {@link ArticleAtomicService#retractAndDelete} 的说明）。</li>
     *   <li><b>重复删除不再发 retract</b>：Go 用 {@code FindOne}（带软删除过滤），
     *       已删文章查不到 ⇒ 返回 NotFound。
     *       Go 测试 {@code community_article_test.go:306-309} 断言域事件数<b>不变</b>。
     *       ⇒ 本实现用 {@code selectById}（带 {@code deleted_at IS NULL}），
     *       已删文章走 null 分支直接返回，不会二次发事件。</li>
     * </ol>
     */
    public DeleteArticleResponse deleteArticle(DeleteArticleRequest in) {
        // 🔴 L-10（Go 出处：delete_article_logic.go:40-45）：
        //     tracer := otel.Tracer("article-rpc")
        //     spanCtx, span := tracer.Start(l.ctx, "DeleteArticle", trace.WithAttributes(attribute.String("article_id", in.ArticleId)))
        //     defer span.End()
        return ArticleRpcMetrics.traced(ArticleRpcMetrics.DELETE_ARTICLE,
                span -> span.setAttribute(ArticleRpcMetrics.ATTR_ARTICLE_ID, in.getArticleId()),
                span -> deleteArticleBody(in, span));
    }

    /** {@link #deleteArticle} 的业务体（span 由调用方持有）。 */
    private DeleteArticleResponse deleteArticleBody(DeleteArticleRequest in, Span span) {
        ArticleEntity article;
        try {
            article = articleMapper.selectById(in.getArticleId());
        } catch (RuntimeException e) {
            // Go delete_article_logic.go:48-52：FindOne 失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        if (article == null) {
            RuntimeException notFound = ArticleExceptions.articleNotFoundRaw();
            // Go delete_article_logic.go:53-57：article == nil ⇒ span.RecordError(NotFound 错误)
            //    🔴 Go 在这里**记了业务失败** ⇒ 本实现也记（与 get_article 的 NotFound 不同）。
            ArticleRpcMetrics.record(span, notFound);
            // ⚠️ 同 update：Go 这里也是裸返回（delete_article_logic.go:47-57）⇒ UNKNOWN + "record not found"
            throw notFound;
        }
        // ⚠️ 空 operator_id 与非作者合并为同一个错误（Go: || 短路）
        if (in.getOperatorId().isEmpty() || !in.getOperatorId().equals(article.getAuthorId())) {
            RuntimeException forbidden = ArticleExceptions.forbidden();
            // Go delete_article_logic.go:58-62：PermissionDenied ⇒ span.RecordError(err)
            //    🔴 Go 在这里**记了业务失败** ⇒ 本实现也记。
            ArticleRpcMetrics.record(span, forbidden);
            throw forbidden;
        }

        String eventId;
        try {
            eventId = newEventId();
        } catch (RuntimeException e) {
            // Go delete_article_logic.go:68-72：newDeleteEventID 失败 ⇒ span.RecordError(err) + Internal
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        long versionMs = System.currentTimeMillis();
        try {
            atomicService.retractAndDelete(in.getArticleId(), eventId, versionMs);
        } catch (RuntimeException e) {
            // Go delete_article_logic.go:92-96：事务失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw e;
        }

        metrics.article("delete");
        return DeleteArticleResponse.newBuilder().setSuccess(true).build();
    }

    // ==================================================================
    // 5. ListArticles —— 作者视角
    // ==================================================================

    /**
     * 对应 Go {@code list_articles_logic.go:30-101} 的非公开分支。
     *
     * <h2>⚠️ 作者视角与公开视角的 page/size 处理<b>不同</b></h2>
     * <ul>
     *   <li>作者视角：<b>直接透传</b> {@code in.Page} / {@code in.PageSize}，
     *       响应里也<b>原样回显</b>（Go {@code :98-99}）。
     *       只在计算 offset 时把负值夹到 0（{@code model/article.go:157-159}）。
     *       ⇒ 传 page=0 会得到 {@code LIMIT pageSize OFFSET 0} 且响应 page=0。</li>
     *   <li>公开视角：<b>先归一</b>（page&lt;1⇒1，size&lt;1⇒20，size&gt;100⇒100），
     *       响应回显<b>归一后的值</b>（Go {@code :124-133}）。</li>
     * </ul>
     * 对称化任何一边都会改变响应里的 {@code page} / {@code page_size} 字段。
     *
     * <p>⚠️ 作者视角的 {@code MarkdownContent} 填的是 <b>{@code article.Content}（对象 key）</b>，
     * 不是正文（Go {@code :79}）—— 这是 Go 的一处<b>不一致</b>（同一 proto 字段在两条路径含义不同）。
     * 照搬：改成真正文需要额外的 MinIO 读取，会让列表响应体暴涨。
     */
    public ListArticlesResponse listArticles(ListArticlesRequest in) {
        // 🔴 L-10（Go 出处：list_articles_logic.go:30-38）：
        //     tracer := otel.Tracer("article-rpc")
        //     ctx, span := tracer.Start(l.ctx, "ListArticles", trace.WithAttributes(
        //         attribute.Int64("page", int64(in.Page)),
        //         attribute.Int64("page_size", int64(in.PageSize)),
        //         attribute.String("sort_by", in.SortBy),
        //         attribute.Bool("desc", in.Desc),
        //     ))
        //     defer span.End()
        //    🔴 与 get_article **不同**：这里的 span 在 PublicOnly 判定**之前**创建
        //       ⇒ **公开分支也有 span**（只有这 4 个起始属性，没有后续属性/事件）。
        return ArticleRpcMetrics.traced(ArticleRpcMetrics.LIST_ARTICLES,
                span -> {
                    span.setAttribute(ArticleRpcMetrics.ATTR_PAGE, (long) in.getPage());
                    span.setAttribute(ArticleRpcMetrics.ATTR_PAGE_SIZE, (long) in.getPageSize());
                    span.setAttribute(ArticleRpcMetrics.ATTR_SORT_BY, in.getSortBy());
                    span.setAttribute(ArticleRpcMetrics.ATTR_DESC, in.getDesc());
                },
                span -> listArticlesBody(in, span));
    }

    /** {@link #listArticles} 的业务体（span 由调用方持有）。 */
    private ListArticlesResponse listArticlesBody(ListArticlesRequest in, Span span) {
        if (in.getPublicOnly()) {
            // Go list_articles_logic.go:46-48：公开分支直接 return
            //    ⇒ **后续那三个条件属性一个都不设** ⇒ 本实现也一个都不设。
            return publicService.listPublicArticles(in);
        }

        int page = in.getPage();
        int size = in.getPageSize();
        String manualTypeTag = in.hasManualTypeTag() ? in.getManualTypeTag() : null;
        String secondaryTag = in.hasSecondaryTag() ? in.getSecondaryTag() : null;
        String authorId = in.hasAuthorId() ? in.getAuthorId() : null;
        // Go list_articles_logic.go:49-60：三个属性**条件设置**（且都在公开分支**之后**）
        if (manualTypeTag != null) {
            span.setAttribute(ArticleRpcMetrics.ATTR_MANUAL_TYPE_TAG, manualTypeTag);
        }
        if (secondaryTag != null) {
            span.setAttribute(ArticleRpcMetrics.ATTR_SECONDARY_TAG, secondaryTag);
        }
        if (authorId != null) {
            span.setAttribute(ArticleRpcMetrics.ATTR_AUTHOR_ID, authorId);
        }
        String secondaryTagJson = (secondaryTag == null || secondaryTag.isEmpty())
                ? null
                : JsonbTypeHandler.writeJson(List.of(secondaryTag));

        // Go list_articles_logic.go:62：span.AddEvent("start db list")
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_START_DB_LIST);
        long total;
        List<ArticleEntity> articles;
        try {
            total = articleMapper.countAuthorView(manualTypeTag, secondaryTagJson, authorId);

            long offset = (long) (page - 1) * size;
            if (offset < 0) {
                offset = 0;
            }

            articles = articleMapper.selectAuthorView(
                    manualTypeTag, secondaryTagJson, authorId,
                    in.getSortBy(), in.getDesc(), size, offset);
        } catch (RuntimeException e) {
            // Go list_articles_logic.go:65-69：db list 失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        // Go list_articles_logic.go:70-71
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_DB_LIST_SUCCESS);
        span.setAttribute(ArticleRpcMetrics.ATTR_TOTAL_ARTICLES, total);

        List<Article> pbArticles = new ArrayList<>();
        if (articles != null) {
            for (ArticleEntity a : articles) {
                pbArticles.add(toAuthorProtoArticle(a, a.getContent()));
            }
        }

        return ListArticlesResponse.newBuilder()
                .addAllArticles(pbArticles)
                .setTotal(total)
                .setPage(page)
                .setPageSize(size)
                .build();
    }

    // ==================================================================
    // 6. UploadFile
    // ==================================================================

    /**
     * 对应 Go {@code upload_file_logic.go:40-91}。
     *
     * <h2>⚠️ content-type 的三条来源（Go :55-61）</h2>
     * <pre>
     * ext := filepath.Ext(in.FileName)                    // 含点，如 ".png"；无扩展名 ⇒ ""
     * contentType := mime.TypeByExtension(ext)
     * if contentType == "" { contentType = "application/octet-stream" }
     * </pre>
     * <br>⚠️ Go 的 {@code mime.TypeByExtension} 查的是<b>系统 mime.types</b>，
     * 同一个扩展名在不同容器里结果可能不同（这是 Go 的行为）。
     * Java 侧用 {@code URLConnection.guessContentTypeFromName}，
     * 它查<b>JDK 内置表</b>（Spring 的 {@code MediaTypeFactory} 也是同一份）。
     * ⇒ 两侧在<b>常见扩展名上</b>（.png/.jpg/.gif/.webp/.svg/.pdf）一致；
     * 在冷门扩展名上可能不同。这是无法在 Java 侧复刻系统 mime.types 的固有差异，
     * <b>已在此留痕</b>，不通过"猜一个更准的表"来掩盖。
     * <br>⚠️ 图片上传<b>带</b> {@code CacheControl: public, max-age=31536000, immutable}，
     * markdown 上传<b>不带</b>。
     *
     * <p>⚠️ 对象名 = {@code <imagePath><snowflakeId><ext>}；<b>ext 含点</b>，
     * 且 imagePath 带尾斜杠（见 {@code ArticleObjectStorageClient} 的类级说明）。
     */
    public UploadFileResponse uploadFile(UploadFileRequest in) {
        // 🔴 L-10（Go 出处：upload_file_logic.go:40-45）：
        //     tracer := otel.Tracer("article-rpc")
        //     ctx, span := tracer.Start(l.ctx, "UploadFile", trace.WithAttributes(attribute.String("file_name", in.FileName)))
        //     defer span.End()
        return ArticleRpcMetrics.traced(ArticleRpcMetrics.UPLOAD_FILE,
                span -> span.setAttribute(ArticleRpcMetrics.ATTR_FILE_NAME, in.getFileName()),
                span -> uploadFileBody(in, span));
    }

    /** {@link #uploadFile} 的业务体（span 由调用方持有）。 */
    private UploadFileResponse uploadFileBody(UploadFileRequest in, Span span) {
        long id;
        try {
            id = Snowflake.nextId();
        } catch (RuntimeException e) {
            // Go upload_file_logic.go:47-52：雪花 ID 失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw e;
        }
        // Go upload_file_logic.go:53：span.SetAttributes(attribute.Int64("file_id", id))（**后续**属性 · Int64）
        span.setAttribute(ArticleRpcMetrics.ATTR_FILE_ID, id);
        String fileName = in.getFileName();
        // Go: filepath.Ext —— 取最后一个点之后的部分（含点）；无点则空串
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            ext = fileName.substring(dot);
        }
        String objectName = storage.getObjectNamePrefixForImage() + id + ext;

        String contentType = guessContentType(ext);
        // Go upload_file_logic.go:62：span.SetAttributes(content_type, object_name)（**后续**属性）
        span.setAttribute(ArticleRpcMetrics.ATTR_CONTENT_TYPE, contentType);
        span.setAttribute(ArticleRpcMetrics.ATTR_OBJECT_NAME, objectName);

        byte[] content = in.getContent().toByteArray();
        long start = System.nanoTime();
        // Go upload_file_logic.go:66：span.AddEvent("start upload to minio")
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_START_UPLOAD_TO_MINIO);
        try {
            storage.put(objectName, content, contentType,
                    "public, max-age=31536000, immutable");
        } catch (Exception e) {
            metrics.minioError("put");
            // Go upload_file_logic.go:75-80：MinIO put 失败 ⇒ span.RecordError(err)
            ArticleRpcMetrics.record(span, e);
            throw ArticleExceptions.raw(e);
        }
        metrics.observeMinio("put", System.nanoTime() - start);
        metrics.fileUpload("image");
        // Go upload_file_logic.go:82：span.AddEvent("upload to minio success")
        ArticleRpcMetrics.event(span, ArticleRpcMetrics.EV_UPLOAD_TO_MINIO_SUCCESS);

        return UploadFileResponse.newBuilder()
                .setFileUrl(storage.buildPublicFileURL(objectName))
                .build();
    }

    /**
     * Go {@code mime.TypeByExtension(ext)} ＋ {@code if contentType == "" { contentType = "application/octet-stream" }}
     * 的<b>合并</b>等价物（{@code upload_file_logic.go:58-61}）。
     *
     * <p><b>⚠️ 回落必须在本方法内完成</b>，而不是留给调用点判断 ——
     * Go 那两行是同一步逻辑，拆开就可能出现"某个调用点忘了加回落"，
     * 让未知扩展名以空 Content-Type 写进对象存储（S3 会存成
     * {@code binary/octet-stream} 或直接拒绝）。
     *
     * @param ext 含点的扩展名，如 {@code ".png"}；无扩展名传空串
     * @return 永不为 null / 永不为空的 content-type
     */
    static String guessContentType(String ext) {
        if (ext != null && !ext.isEmpty()) {
            String guessed = java.net.URLConnection.guessContentTypeFromName("file" + ext);
            if (guessed != null && !guessed.isEmpty()) {
                return guessed;
            }
        }
        return "application/octet-stream";
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 新事件 ID —— 对应 Go 的 {@code newEventID}（雪花 ID 的十进制字符串）。 */
    String newEventId() {
        return String.valueOf(Snowflake.nextId());
    }

    /**
     * 审核 topic —— 对应 Go {@code config.KqPusherConf.Topic}
     * 与 {@code config.KqConsumerConf.Topic}（两者在 yaml 里是<b>同一个</b>值）。
     *
     * <p>⚠️ <b>取 pusher 侧的值</b>：Go 的 push 用 {@code svcCtx.KqPusher}
     * （建自 {@code KqPusherConf.Topic}），consume 用 {@code svcCtx.Config.KqConsumerConf.Topic}。
     * yaml 里两者都是 {@code article-creation-topic}，但<b>配置上是两个独立字段</b>。
     * 改成只保留一个会让运维无法在不改消费端的情况下调整推送目标。
     */
    String reviewTopic() {
        return properties.getKqPusherConf().getTopic();
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
