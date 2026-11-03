package com.rag.article.service;

import com.rag.article.contract.ErrCodes;
import com.rag.article.contract.ErrMsg;
import com.rag.article.domain.service.ArticleDomainService;
import com.rag.article.domain.service.ArticlePublicDomainService;
import com.rag.article.dto.article.Article;
import com.rag.article.dto.article.CreateArticleReq;
import com.rag.article.dto.article.CreateArticleResp;
import com.rag.article.dto.article.DeleteArticleReq;
import com.rag.article.dto.article.DeleteArticleResp;
import com.rag.article.dto.article.GetArticleResp;
import com.rag.article.dto.article.ListArticlesReq;
import com.rag.article.dto.article.ListArticlesResp;
import com.rag.article.dto.article.UpdateArticleReq;
import com.rag.article.dto.article.UpdateArticleResp;
import com.rag.article.dto.upload.UploadImageResp;
import com.rag.article.exception.ArticleExceptions;
import com.rag.common.exception.BusinessException;
import com.rag.common.protection.ServiceBusyException;
import com.rag.common.web.BadRequestException;
import com.rag.common.web.GrpcStatusMapper;
import com.rag.common.web.HttpErrorException;
import com.rag.article.web.ArticleQueryValues;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.CreateArticleRequest;
import com.rag.proto.article.CreateArticleResponse;
import com.rag.proto.article.DeleteArticleRequest;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import com.rag.proto.article.ListArticlesRequest;
import com.rag.proto.article.ListArticlesResponse;
import com.rag.proto.article.UpdateArticleRequest;
import com.rag.proto.article.UpdateArticleResponse;
import com.rag.proto.article.UploadFileRequest;
import com.rag.proto.article.UploadFileResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>article 域的 API 层业务入口</b> —— <b>合并 B 侧 7 个 {@code *Service}</b>。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       ① B 侧 {@code ArticleCreateService} / {@code ArticleGetService} /
 *       {@code ArticleGetAuthorService} / {@code ArticleListService} /
 *       {@code ArticleUpdateService} / {@code ArticleDeleteService} /
 *       {@code ArticleUploadService} <b>七个类</b>合并为本类
 *       （映射表 §5.4「用例内聚」五问判据已批准）；
 *       ② <b>D2 域内直连</b>：B 侧走跨进程 gRPC（{@code ArticleRpcClient}），
 *       C 侧<b>直接注入</b> {@link ArticleDomainService} / {@link ArticlePublicDomainService}
 *       ⇒ <b>不再有网络跳</b>；
 *       ③ 包名压平（{@code com.rag.article.article.api.service} → {@code com.rag.article.service}，D7）；
 *       ④ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 7 条用例的<b>判定顺序</b>、
 *       <b>每条 gRPC code → 业务码的分支表</b>、<b>「先读后写」的两段鉴权</b>、
 *       ownership 双检、列表的「任一行非已发布 ⇒ 整条失败」、
 *       上传的「读文件失败 400」与「下游失败按 gRPC 码映射状态码」——
 *       全部逐条照抄（逐条对照见各类方法的 javadoc）。
 *       <br>⚠️ <b>唯一需要论证的一处</b>：D2 去掉 gRPC 跳之后，<b>异常类型</b>从
 *       {@code StatusRuntimeException} 变成了领域层的 {@link ArticleExceptions.Rpc} /
 *       {@link ArticleExceptions.Raw} ⇒ ⇒ 本类用 {@link #statusCodeOf(RuntimeException)}
 *       <b>逐条复刻</b> {@code grpc/ArticleGrpcService} 的三条 catch 分支
 *       （Rpc ⇒ 取其 code；StatusRuntimeException ⇒ <b>原样透传 code</b>；
 *       Raw / 其它 ⇒ UNKNOWN）⇒ ⇒ <b>业务码判定结果逐条相同</b>。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：…/api/service/ArticleCreateService.java:20-61（createArticle + 5 分支）
 * B 出处：…/api/service/ArticleGetService.java:25-53（getArticle + 3 分支 + 安全闸门）
 * B 出处：…/api/service/ArticleGetAuthorService.java:24-55（getAuthorArticle + 4 分支 + ownership）
 * B 出处：…/api/service/ArticleListService.java:27-72（listArticles + 2 分支 + 整包拒绝）
 * B 出处：…/api/service/ArticleUpdateService.java:81-93（鉴权读 4 分支）+ :138-147（写 3 分支）
 * B 出处：…/api/service/ArticleDeleteService.java:50-62（读/写共用的 4 分支）
 * B 出处：…/api/service/ArticleUploadService.java:22-59（uploadImage + 两条 catch）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 为什么要保留「先读后写」的两段鉴权</h2>
 * <p>B 侧的 update / delete <b>不是</b>直接把写请求丢给 rpc，而是<b>先读一次</b>
 * （{@code getArticle} 带 {@code requesterId}、{@code incrView=false}）做鉴权，
 * <b>再写</b>。这一步<b>不是冗余</b>，它决定了**错误码形状**：
 * <pre>
 *   文章不存在：
 *     B 侧 ① 鉴权读 ⇒ NOT_FOUND ⇒ 1002「文章不存在」
 *     C 侧若直接调领域层的写 ⇒ 领域层抛的是 UNKNOWN("record not found")
 *        ⇒ api 的 default 分支 ⇒ **1015「服务繁忙」** ← 错！
 * </pre>
 * ⇒ ⇒ 本类<b>照抄 B 侧的两段结构</b>（鉴权读 + ownership 校验 + 写），
 * 错误码形状才与 B 侧逐条一致。
 */
@Service
public class ArticleApiService {

    /**
     * multipart 的表单字段名 —— <b>L1 契约</b>（Go 侧 {@code r.FormFile("image")}）。
     *
     * <p>B 出处：{@code …/api/service/ArticleUploadService.java:17}。
     */
    public static final String FORM_FIELD = "image";

    private final ArticleDomainService domainService;
    private final ArticlePublicDomainService publicService;

    public ArticleApiService(ArticleDomainService domainService, ArticlePublicDomainService publicService) {
        this.domainService = domainService;
        this.publicService = publicService;
    }

    // ==================================================================
    // 1. 创建文章
    // ==================================================================

    /**
     * Go {@code CreateArticleHandler} 的 logic 段。
     *
     * <p><b>⚠️ 五条分支的「有没有 data」各不相同，顺序与 Go 一致</b>：
     * <table border="1">
     *   <caption>B 侧 {@code ArticleCreateService.java:41-53}</caption>
     *   <tr><th>gRPC code</th><th>业务码</th><th>data</th></tr>
     *   <tr><td>{@code ALREADY_EXISTS}</td><td>1001 文章已存在</td><td><b>null</b></td></tr>
     *   <tr><td>{@code FAILED_PRECONDITION}</td><td>1004 文章发布失败</td><td><b>带 article_id</b></td></tr>
     *   <tr><td>{@code DEADLINE_EXCEEDED}</td><td>1003 文章处理中</td><td><b>带 article_id</b></td></tr>
     *   <tr><td>{@code INTERNAL}</td><td>5001 系统内部错误</td><td><b>null</b></td></tr>
     *   <tr><td>其它</td><td>1015 服务繁忙</td><td><b>null</b></td></tr>
     * </table>
     *
     * <p>B 出处：{@code …/api/service/ArticleCreateService.java:20-61}。
     */
    public CreateArticleResp createArticle(CreateArticleReq req, String authorId) {
        CreateArticleResponse rpcResp;
        try {
            CreateArticleRequest.Builder builder = CreateArticleRequest.newBuilder()
                    .setTitle(nullToEmpty(req.title()))
                    // ⚠️ 恒 set：proto3 optional 的 presence 语义（见类注释）
                    .setBrief(nullToEmpty(req.brief()))
                    .setMarkdownContent(nullToEmpty(req.content()))
                    .setCoverImageUrl(nullToEmpty(req.coverImageUrl()))
                    .setManualTypeTag(nullToEmpty(req.manualTypeTag()))
                    .setAuthorId(authorId);
            // Go: SecondaryTags: req.SecondaryTags —— repeated 字段，nil slice 与空 slice 同形
            //   （protobuf 的 addAll 对空列表是 no-op，对端 len() == 0 两种来源一致）
            if (req.secondaryTags() != null) {
                builder.addAllSecondaryTags(req.secondaryTags());
            }
            rpcResp = domainService.createArticle(builder.build());
        } catch (RuntimeException e) {
            Status.Code code = statusCodeOf(e);
            String articleId = extractArticleIdFromStatusMessage(statusDescriptionOf(e));
            if (code == Status.Code.ALREADY_EXISTS) {
                throw business(ErrCodes.ERROR_ARTICLE_EXIST);                       // data = null
            }
            if (code == Status.Code.FAILED_PRECONDITION) {
                throw new CreateFailure(ErrCodes.ERROR_ARTICLE_PUBLISH_FAILED, articleId);
            }
            if (code == Status.Code.DEADLINE_EXCEEDED) {
                throw new CreateFailure(ErrCodes.ERROR_ARTICLE_PUBLISHING, articleId);
            }
            if (code == Status.Code.INTERNAL) {
                throw business(ErrCodes.ERROR_SERVER_COMMON);                       // data = null
            }
            throw business(ErrCodes.CODE_SERVER_BUSY);                              // data = null
        }
        // Go: create_article_logic.go:77-79
        // ⚠️ rpcResp 理论上传回 nil（Go 是指针）。Go 侧 `rpcResp.ArticleId` 会在 nil 上 panic
        //    ⇒ recover → 裸 500。本侧等价：proto 的 getter 对 null 消息返回默认值，
        //    不会 panic。要完全复刻 panic 语义需要显式判空并抛 —— 但那会把
        //    「一个理论上不该发生的情况」变成生产 500 路径，此处选择与 Go 等价的
        //    「拿到空 article_id」，并由 article-rpc 保证不返回 nil。
        return new CreateArticleResp(rpcResp.getArticleId());
    }

    /**
     * 从状态描述里抽 article_id（B 侧靠「最后一个冒号之后」这一约定）。
     *
     * <p>B 出处：{@code …/api/service/ArticleCreateService.java:63-72}。
     */
    static String extractArticleIdFromStatusMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        int idx = message.lastIndexOf(':');
        if (idx < 0 || idx + 1 >= message.length()) {
            return "";
        }
        return message.substring(idx + 1).trim();
    }

    // ==================================================================
    // 2. 公开读详情
    // ==================================================================

    /**
     * Go {@code GetArticleHandler} 的 logic 段（<b>公开</b>读）。
     *
     * <p><b>三条分支</b>（B 侧 {@code ArticleGetService.java:36-43}）：
     * {@code NOT_FOUND → 1002} / {@code INTERNAL → 5001} / 其它 → {@code 1015}。
     * <br>⚠️ {@code PermissionDenied} <b>落进 default</b>（⇒ 1015）—— 与作者读的 4 分支<b>刻意不同</b>。
     *
     * <p>🔴 <b>安全闸门</b>：老版本 rpc 忽略 {@code public_only} ⇒ 这里必须自己再判一次
     * （B 侧 {@code ArticleGetService.java:47-50}）。
     *
     * <p>B 出处：{@code …/api/service/ArticleGetService.java:25-53}。
     */
    public GetArticleResp getArticle(String articleId, String rawIncrView) {
        // Go: form 的 bool 真值表（1/0/true/false，非法值 ⇒ 400）由 ArticleQueryValues 承担
        // B 出处：…/api/service/ArticleGetService.java:26
        boolean incrView = ArticleQueryValues.bool(rawIncrView, "incr_view", false);
        ArticlePublicDomainService.PublicArticleView view;
        try {
            view = publicService.getPublicArticle(articleId, incrView);
        } catch (RuntimeException e) {
            // Go: switch st.Code() —— 只有三个分支，PermissionDenied 落进 default
            Status.Code code = statusCodeOf(e);
            if (code == Status.Code.NOT_FOUND) {
                throw business(ErrCodes.ERROR_ARTICLE_NONE);
            }
            if (code == Status.Code.INTERNAL) {
                throw business(ErrCodes.ERROR_SERVER_COMMON);
            }
            throw business(ErrCodes.CODE_SERVER_BUSY);
        }
        // 🔴 安全闸门：老版本 rpc 忽略 public_only ⇒ 这里必须自己再判一次
        // ⚠️ 领域层已经对「未发布」抛 NOT_FOUND(articleNotPublished) ⇒ 与本闸门同码(1002)
        //    ⇒ 保留本闸门是为了逐条对齐 B 侧的控制流（双保险，不是新增行为）。
        if (view == null || !ArticleApiSupport.isPublished(view.status)) {
            throw business(ErrCodes.ERROR_ARTICLE_NONE);
        }
        Article article = ArticleApiSupport.toArticle(view);
        return new GetArticleResp(ArticleApiSupport.enrichArticleAuthor(article));
    }

    // ==================================================================
    // 3. 作者读详情
    // ==================================================================

    /**
     * Go {@code GetAuthorArticleHandler} 的 logic 段。
     *
     * <p><b>四条分支</b>（B 侧 {@code ArticleGetAuthorService.java:34-44}）：
     * {@code NOT_FOUND → 1002} / {@code PERMISSION_DENIED → 1006} /
     * {@code INTERNAL → 5001} / 其它 → {@code 1015}。
     * <br>⚠️ 比公开读<b>多一个</b> {@code PermissionDenied → 1006}。
     * <br>⚠️ <b>不检查是否已发布</b>：作者可以读到自己的 REVIEWING 草稿。
     *
     * <p>B 出处：{@code …/api/service/ArticleGetAuthorService.java:24-55}。
     */
    public GetArticleResp getAuthorArticle(String articleId, String authorId) {
        GetArticleResponse res;
        try {
            res = domainService.getArticle(GetArticleRequest.newBuilder()
                    .setArticleId(articleId)
                    .setRequesterId(authorId)
                    // IncrView / PublicOnly 均不设 ⇒ false，与 Go 一致
                    .build());
        } catch (RuntimeException e) {
            Status.Code code = statusCodeOf(e);
            if (code == Status.Code.NOT_FOUND) {
                throw business(ErrCodes.ERROR_ARTICLE_NONE);
            }
            if (code == Status.Code.PERMISSION_DENIED) {
                throw business(ErrCodes.ERROR_ARTICLE_FORBIDDEN);
            }
            if (code == Status.Code.INTERNAL) {
                throw business(ErrCodes.ERROR_SERVER_COMMON);
            }
            throw business(ErrCodes.CODE_SERVER_BUSY);
        }
        if (res == null || !res.hasArticle()) {
            throw business(ErrCodes.ERROR_ARTICLE_NONE);
        }
        // 🔴 ownership 闸门（Go 的注释解释过：旧 rpc 会忽略 requester_id）
        Article article = ArticleApiSupport.toArticle(res.getArticle());
        if (!authorId.equals(article.authorId())) {
            throw business(ErrCodes.ERROR_ARTICLE_FORBIDDEN);
        }
        return new GetArticleResp(ArticleApiSupport.enrichArticleAuthor(article));
    }

    // ==================================================================
    // 4. 公开列表
    // ==================================================================

    /**
     * Go {@code ListArticlesHandler} 的 logic 段。
     *
     * <p><b>两条分支</b>（B 侧 {@code ArticleListService.java:43-47}）：
     * {@code INTERNAL → 5001} / 其它（含 {@code NotFound}）→ {@code 1015}。
     * <br>🔴 <b>任一行非已发布 ⇒ 整条请求失败</b>（Go 是 return nil，不是 continue）。
     * <br>⚠️ <b>空列表保持 {@code null}</b>（Go 的 nil slice ⇒ {@code "articles":null}）。
     *
     * <p>B 出处：{@code …/api/service/ArticleListService.java:27-72}。
     */
    public ListArticlesResp listArticles(ListArticlesReq req) {
        ListArticlesResponse res;
        try {
            res = publicService.listPublicArticles(ListArticlesRequest.newBuilder()
                    .setManualTypeTag(req.manualTypeTag())
                    .setSecondaryTag(req.secondaryTag())
                    .setRelatedGameId(req.relatedGameId())
                    .setAuthorId(req.authorId())
                    .setPage(req.page())
                    .setPageSize(req.pageSize())
                    .setSortBy(req.sortBy())
                    .setDesc(req.desc())
                    .setPublicOnly(true)
                    .build());
        } catch (RuntimeException e) {
            Status.Code code = statusCodeOf(e);
            if (code == Status.Code.INTERNAL) {
                throw business(ErrCodes.ERROR_SERVER_COMMON);
            }
            throw business(ErrCodes.CODE_SERVER_BUSY);
        }
        if (res == null) {
            // Go: list_articles_logic.go:58-60 `if res == nil { return nil, errmsg.ErrorServerCommon }`
            throw business(ErrCodes.ERROR_SERVER_COMMON);
        }
        List<Article> articles = null;
        for (com.rag.proto.article.Article item : res.getArticlesList()) {
            // 🔴 任一行非已发布 ⇒ 整条请求失败（Go 是 return nil，不是 continue）
            if (!ArticleApiSupport.isPublished(item.getStatusValue())) {
                throw business(ErrCodes.ERROR_SERVER_COMMON);
            }
            if (articles == null) {
                articles = new ArrayList<>(res.getArticlesCount());
            }
            articles.add(ArticleApiSupport.toArticle(item));
        }
        // ⚠️ 保持 null：Go 的 `var articles []types.Article` 在无行时是 nil slice
        //    ⇒ JSON "articles":null。本类刻意不用 List.of()。
        articles = ArticleApiSupport.enrichArticleAuthors(articles);
        return new ListArticlesResp(articles, res.getTotal(), res.getPage(), res.getPageSize());
    }

    // ==================================================================
    // 5. 更新文章
    // ==================================================================

    /**
     * Go {@code UpdateArticleHandler} 的 logic 段。
     *
     * <p><b>两段结构</b>：① 鉴权读（<b>4 分支</b>）＋ ownership 校验 → ② 组装可选字段 → ③ 写（<b>3 分支</b>）。
     * <br>⚠️ <b>鉴权读与写的分支表刻意不同</b>（鉴权读有 {@code PermissionDenied → 1006}，写没有）
     * —— 把两处合并成一个 mapper 就是一次 L2 漂移（B 侧 javadoc 明写）。
     * <br>⚠️ 鉴权那次读<b>不能</b>加阅读量（{@code incrView=false}）。
     *
     * <p>B 出处：{@code …/api/service/ArticleUpdateService.java:81-149}。
     */
    public UpdateArticleResp updateArticle(String articleId, UpdateArticleReq req, String currentUserId) {
        // ── ① 先读后写：鉴权 + 错误码形状 ──
        GetArticleResponse articleResp;
        try {
            articleResp = domainService.getArticle(GetArticleRequest.newBuilder()
                    .setArticleId(articleId)
                    // ⚠️ 鉴权用的读**不能**加阅读量
                    .setIncrView(false)
                    .setRequesterId(currentUserId)
                    .build());
        } catch (RuntimeException e) {
            throw mapAuthReadError(e);
        }
        if (articleResp == null || !articleResp.hasArticle()) {
            throw business(ErrCodes.ERROR_ARTICLE_NONE);
        }
        // 🔴 ownership 闸门（Go: update_article_logic.go:65-67）
        if (!currentUserId.equals(articleResp.getArticle().getAuthorId())) {
            throw business(ErrCodes.ERROR_ARTICLE_FORBIDDEN);
        }
        // ── ② 组装可选字段 ──
        // ⚠️ req 可能为 null（B 侧的空 body ⇒ 零值 struct ⇒ 「什么都不改」是一次合法请求）
        //    ⇒ 全部字段取 null ⇒ 「非空才下发」自然变成「一个字段都不下发」。
        UpdateArticleRequest.Builder builder = UpdateArticleRequest.newBuilder().setArticleId(articleId);
        setIfNotEmpty(builder, (req == null ? null : req.title()), UpdateArticleRequest.Builder::setTitle);
        setIfNotEmpty(builder, (req == null ? null : req.brief()), UpdateArticleRequest.Builder::setBrief);
        setIfNotEmpty(builder, (req == null ? null : req.content()), UpdateArticleRequest.Builder::setMarkdownContent);
        setIfNotEmpty(builder, (req == null ? null : req.coverImageUrl()), UpdateArticleRequest.Builder::setCoverImageUrl);
        setIfNotEmpty(builder, (req == null ? null : req.manualTypeTag()), UpdateArticleRequest.Builder::setManualTypeTag);
        // Go: SecondaryTags 恒下发（repeated 字段，nil 与空 slice 在 proto 层同形）
        List<String> secondaryTags = req == null ? null : req.secondaryTags();
        if (secondaryTags != null) {
            builder.addAllSecondaryTags(secondaryTags);
        }
        // Go: if req.Status != 0 { rpcReq.Status = ArticleStatus(req.Status).Enum() }
        // ⚠️ Enum() 对未知数值返回 nil ⇒ 字段不下发
        Integer status = req == null ? null : req.status();
        if (status != null && status != 0) {
            ArticleStatus enumValue = ArticleStatus.forNumber(status);
            if (enumValue != null) {
                builder.setStatus(enumValue);
            }
        }
        // ── ③ 写 ──
        UpdateArticleResponse rpcResp;
        try {
            rpcResp = domainService.updateArticle(builder.build());
        } catch (RuntimeException e) {
            // Go: update_article_logic.go:95-106 —— 只有三个分支
            // ⚠️ **没有** PermissionDenied → 1006；越权在这里会落进 default → 1015。
            //    但正常路径下 ① 已经把越权拦在 1006 了，所以这条分支实际不可达越权场景。
            Status.Code code = statusCodeOf(e);
            if (code == Status.Code.NOT_FOUND) {
                throw business(ErrCodes.ERROR_ARTICLE_NONE);
            }
            if (code == Status.Code.INTERNAL) {
                throw business(ErrCodes.ERROR_SERVER_COMMON);
            }
            throw business(ErrCodes.CODE_SERVER_BUSY);
        }
        // Go: return &types.UpdateArticleResp{Success: rpcResp.GetSuccess()}, errmsg.Success
        return new UpdateArticleResp(rpcResp.getSuccess());
    }

    /**
     * 鉴权那次读的 4 分支映射 —— 与「写」那条的 3 分支<b>刻意不同</b>。
     *
     * <p>B 出处：{@code …/api/service/ArticleUpdateService.java:145-160}。
     */
    private static BusinessException mapAuthReadError(RuntimeException e) {
        Status.Code code = statusCodeOf(e);
        if (code == Status.Code.NOT_FOUND) {
            return business(ErrCodes.ERROR_ARTICLE_NONE);
        }
        if (code == Status.Code.PERMISSION_DENIED) {
            return business(ErrCodes.ERROR_ARTICLE_FORBIDDEN);
        }
        if (code == Status.Code.INTERNAL) {
            return business(ErrCodes.ERROR_SERVER_COMMON);
        }
        return business(ErrCodes.CODE_SERVER_BUSY);
    }

    private static void setIfNotEmpty(UpdateArticleRequest.Builder builder,
                                      String value,
                                      java.util.function.BiConsumer<UpdateArticleRequest.Builder, String> setter) {
        if (value != null && !value.isEmpty()) {
            setter.accept(builder, value);
        }
    }

    // ==================================================================
    // 6. 删除文章
    // ==================================================================

    /**
     * Go {@code DeleteArticleHandler} 的 logic 段。
     *
     * <p><b>读、写两处是同一个 4 分支</b>（B 侧 {@code ArticleDeleteService.java:50-62}）：
     * {@code NOT_FOUND → 1002} / {@code PERMISSION_DENIED → 1006} /
     * {@code INTERNAL → 5001} / 其它 → {@code 1015}。
     *
     * <p>B 出处：{@code …/api/service/ArticleDeleteService.java:19-48}。
     */
    public DeleteArticleResp deleteArticle(DeleteArticleReq req) {
        String operatorId = req.operatorId();
        // ── ① 鉴权读 ──
        GetArticleResponse articleResp;
        try {
            articleResp = domainService.getArticle(GetArticleRequest.newBuilder()
                    .setArticleId(req.articleId())
                    .setIncrView(false)
                    .setRequesterId(operatorId)
                    .build());
        } catch (RuntimeException e) {
            throw mapError(e);
        }
        if (articleResp == null || !articleResp.hasArticle()) {
            throw business(ErrCodes.ERROR_ARTICLE_NONE);
        }
        if (!operatorId.equals(articleResp.getArticle().getAuthorId())) {
            throw business(ErrCodes.ERROR_ARTICLE_FORBIDDEN);
        }
        // ── ② 删除 ──
        try {
            domainService.deleteArticle(DeleteArticleRequest.newBuilder()
                    .setArticleId(req.articleId())
                    .setOperatorId(operatorId)
                    .build());
        } catch (RuntimeException e) {
            // Go 侧读、写两处是**同一个** 4 分支 switch
            throw mapError(e);
        }
        return new DeleteArticleResp(true);
    }

    private static BusinessException mapError(RuntimeException e) {
        Status.Code code = statusCodeOf(e);
        if (code == Status.Code.NOT_FOUND) {
            return business(ErrCodes.ERROR_ARTICLE_NONE);
        }
        if (code == Status.Code.PERMISSION_DENIED) {
            return business(ErrCodes.ERROR_ARTICLE_FORBIDDEN);
        }
        if (code == Status.Code.INTERNAL) {
            return business(ErrCodes.ERROR_SERVER_COMMON);
        }
        return business(ErrCodes.CODE_SERVER_BUSY);
    }

    // ==================================================================
    // 7. 上传图片
    // ==================================================================

    /**
     * Go {@code UploadImageHandler} 的 logic 段。
     *
     * <p>⚠️ <b>本端点是全域唯一不走信封的端点</b>（裸 {@code {"image_url":…}}）。
     *
     * <p>两条 catch 的形状<b>刻意不同</b>：
     * <ul>
     *   <li><b>读文件失败</b> ⇒ {@link BadRequestException} ⇒ <b>400 + text/plain</b>；</li>
     *   <li><b>下游失败</b> ⇒ {@link HttpErrorException} ⇒ 状态码<b>按 gRPC 码映射</b>
     *       （{@code Unavailable ⇒ 503} / {@code Internal ⇒ 500} / {@code DeadlineExceeded ⇒ 504} …）
     *       ＋ body 复刻 grpc-go 的 {@code "rpc error: code = X desc = Y"}；</li>
     *   <li><b>熔断打开</b> ⇒ {@link ServiceBusyException} ⇒ <b>400 + text/plain</b>
     *       （Go 侧 breaker 的错误<b>不是</b> gRPC 错误 ⇒ 落 {@code http.Error} 的恒 400 分支）。</li>
     * </ul>
     *
     * <p>⚠️ 业务码 {@code ErrorMinioUpload(5004)} 在 Go 侧<b>只进日志</b>。
     *
     * <p>B 出处：{@code …/api/service/ArticleUploadService.java:22-59}。
     */
    public UploadImageResp uploadImage(MultipartFile file) {
        String filename = file.getOriginalFilename();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            // Go: io.ReadAll 失败 → return nil, err → httpx.ErrorCtx → 400
            throw new BadRequestException("read uploaded file failed: " + e.getMessage(), e);
        }
        UploadFileResponse rpcResp;
        try {
            rpcResp = domainService.uploadFile(UploadFileRequest.newBuilder()
                    .setContent(com.google.protobuf.ByteString.copyFrom(content))
                    // ⚠️ Go 侧取的是 multipart 的原始 filename（header.Filename），不做清洗
                    .setFileName(filename == null ? "" : filename)
                    .build());
        } catch (ServiceBusyException e) {
            // Go 分支 ③（responses.go:156-157）：**非** gRPC 错误 ⇒ 恒 400。
            // 典型来源：Go 侧 breaker 打开时返回的 breaker.ErrServiceUnavailable
            // = errors.New("circuit breaker is open")（core/breaker/breaker.go:19）——
            // 它是普通 error，不实现 GRPCStatus() ⇒ IsGrpcError 为假。
            // ⚠️ 共享层的 ServiceBusyException 同样是**普通** RuntimeException
            //   （common/protection/ClientCircuitGateInterceptor.java:145），
            //   若不在这里接住，会一路落到兜底 ⇒ 裸 500，与 Go 的 400 不一致。
            throw new BadRequestException(e.getMessage(), e);
        } catch (RuntimeException e) {
            // Go 分支 ②（responses.go:150-153）：err 是 gRPC 状态错误 ⇒ IsGrpcError 为真
            //   ⇒ http.Error(w, err.Error(), errcode.CodeFromGrpcError(err))
            // ⇒ 状态码按 gRPC 码映射（Unavailable⇒503 / Internal⇒500 / DeadlineExceeded⇒504 …），
            //   body 复刻 grpc-go 的 "rpc error: code = X desc = Y"。
            Status.Code code = statusCodeOf(e);
            throw new HttpErrorException(GrpcStatusMapper.httpStatusOf(code),
                    GrpcStatusMapper.goErrorMessage(code, statusDescriptionOf(e)));
        }
        return new UploadImageResp(rpcResp.getFileUrl());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 🔴 <b>D2 后的状态码提取</b> —— 逐条复刻 {@code grpc/ArticleGrpcService} 的三条 catch 分支。
     *
     * <pre>
     *   ArticleExceptions.Rpc      ⇒ 取其自带的 code（Rpc 构造器里就是 gRPC code）
     *   StatusRuntimeException     ⇒ **原样透传其 code**（不得改写成 UNKNOWN ——
     *                                 共享层的 SqlTimeoutStatusTranslator 正是靠抛
     *                                 Status.DEADLINE_EXCEEDED 把 PG 的 57014 翻成正确 gRPC 码）
     *   ArticleExceptions.Raw      ⇒ UNKNOWN（grpc 层的兜底分支）
     *   其它 RuntimeException      ⇒ UNKNOWN（grpc 层的兜底分支）
     * </pre>
     *
     * <p>⚠️ <b>为什么这一步必须逐条复刻</b>：D2 去掉 gRPC 跳之后，领域层抛的是
     * {@code ArticleExceptions.*} 而不是 {@code StatusRuntimeException}
     * ⇒ 若本方法只认 {@code StatusRuntimeException}，则<b>全部</b>失败都会落成 UNKNOWN
     * ⇒ 「文章不存在」会从 1002 变成 1015 ⇒ 一次 L2 漂移。
     */
    static Status.Code statusCodeOf(RuntimeException e) {
        if (e instanceof ArticleExceptions.Rpc rpc) {
            return rpc.getCode();
        }
        if (e instanceof StatusRuntimeException sre) {
            return sre.getStatus().getCode();
        }
        return Status.Code.UNKNOWN;
    }

    /**
     * 状态描述 —— 对应 grpc 层 {@code Status.fromCode(code).withDescription(msg)}。
     */
    static String statusDescriptionOf(RuntimeException e) {
        if (e instanceof ArticleExceptions.Rpc rpc) {
            return rpc.getMessage();
        }
        if (e instanceof StatusRuntimeException sre) {
            return sre.getStatus().getDescription();
        }
        return e.getMessage();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static BusinessException business(ErrCodes code) {
        return new BusinessException(code.getCode(), ErrMsg.getMsg(code.getCode()));
    }

    /**
     * 「失败但<b>带 data</b>」的例外 —— 仅创建路径的 {@code FAILED_PRECONDITION} /
     * {@code DEADLINE_EXCEEDED} 两条。
     *
     * <p>Go {@code create_article_logic.go:60-69} 对这两条返回 <b>带 article_id 的 resp</b>，
     * 同时给 1004 / 1003：
     * <pre>
     *   {"code":1003,"msg":"文章处理中，请稍后查看","data":{"article_id":"1792…"}}
     * </pre>
     * ⇒ 客户端会收到「HTTP 200 + code≠200 + data 非空」。这是<b>有意</b>的：
     * 文章已落库、只是发布流水线没跑完，客户端需要那个 id 才能继续轮询/跳转。
     *
     * <p>⚠️ <b>不能</b>把它并进普通的业务异常处理：那个方法产出 {@code data = null}
     * ⇒ 合并会把「失败也带 data」这个 L2 语义抹掉。
     *
     * <p>B 出处：{@code …/api/service/ArticleCreateService.java:79-89}。
     */
    public static class CreateFailure extends BusinessException {

        private static final long serialVersionUID = 1L;

        private final transient CreateArticleResp payload;

        public CreateFailure(ErrCodes code, String articleId) {
            super(code.getCode(), ErrMsg.getMsg(code.getCode()));
            this.payload = new CreateArticleResp(articleId);
        }

        public CreateArticleResp getPayload() {
            return payload;
        }
    }
}
