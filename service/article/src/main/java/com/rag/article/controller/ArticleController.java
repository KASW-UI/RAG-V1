package com.rag.article.controller;

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
import com.rag.article.service.ArticleApiService;
import com.rag.article.service.ArticleApiSupport;
import com.rag.article.web.ArticleQueryValues;
import com.rag.common.web.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p><b>article-api 的 7 个端点</b> —— 对应 Go {@code internal/handler/routes.go} 的<b>三段</b>
 * {@code server.AddRoutes}。
 *
 * <h2>路径表逐条对齐（这是 L1 契约，改一个就是破坏兼容）</h2>
 * <pre>
 *   第一段（WithJwt）：
 *     POST   /v1/article           CreateArticle
 *     PUT    /v1/article/:id       UpdateArticle
 *     DELETE /v1/article/:id       DeleteArticle
 *     GET    /v1/me/article/:id    GetAuthorArticle
 *   第二段（**无** WithJwt）：
 *     GET    /v1/article/:id       GetArticle
 *     GET    /v1/articles          ListArticles
 *   第三段（**无** WithJwt，WithMaxBytes 10MB）：
 *     POST   /v1/upload            UploadImage
 * </pre>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       ① B 侧的 7 个 {@code *Service} 合并为<b>一个</b> {@link ArticleApiService}
 *       （映射表 §1.2 已批准的 N:1；D2 域内直连）；
 *       ② 包名压平（{@code com.rag.article.article.api.controller} → {@code com.rag.article.controller}，D7）；
 *       ③ B 侧的手写必填校验（{@code GoStyleRequiredFields}）换成 <b>Bean Validation</b>（A10）；
 *       ④ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b>，除两处已登记的<b>文案</b>偏离（见下）——
 *       路径表、method、鉴权边界、query/form 参数的<b>声明形态</b>（全部 {@code String[]}）、
 *       upload 的裸响应（不走信封）全部逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/article/article-api/src/main/java/com/rag/article/article/api/controller/ArticleController.java:79-108（类声明 + 构造器）
 * B 出处：…/controller/ArticleController.java:123-129（POST /v1/article）
 * B 出处：…/controller/ArticleController.java:137-149（PUT /v1/article/{id}）
 * B 出处：…/controller/ArticleController.java:152-157（DELETE /v1/article/{id}）
 * B 出处：…/controller/ArticleController.java:167-171（GET /v1/me/article/{id}）
 * B 出处：…/controller/ArticleController.java:181-185（GET /v1/article/{id}）
 * B 出处：…/controller/ArticleController.java:201-216（GET /v1/articles）
 * B 出处：…/controller/ArticleController.java:237-241（POST /v1/upload）
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ Go 侧 7 个 handler「同质」，这里合并成一个 controller 是 L4（实现机制）</h2>
 * <p>Go 每个 handler 都是这同一段：
 * <pre>
 *   var req types.XxxReq
 *   if err := httpx.Parse(r, &req); err != nil { httpx.ErrorCtx(r.Context(), w, err); return }
 *   l := article.NewXxxLogic(r.Context(), svcCtx)
 *   resp, code := l.Xxx(&req)
 *   httpx.OkJson(w, &response.Response{Code: code, Msg: errmsg.GetErrMsg(code), Data: resp})
 * </pre>
 * ⇒ <b>差异只有 req 类型与 logic 方法名</b>。Java 侧按「一个 controller + 一个 service」
 * 组织：路由表在一处可见、鉴权边界在一处可见，
 * 而「入参解释」（path/form/json 三条通道）在 Spring 里本来就是三种不同的绑定方式，
 * 硬要「同质」就得自己写一套解析 —— 那正是 {@code §13-E} 说的「已由框架承担的职责不要重造」。
 *
 * <h2>⚠️ {@code POST /v1/upload} <b>公开</b></h2>
 * <p>Go 第三段 {@code AddRoutes} <b>没有</b> {@code rest.WithJwt}。
 * 「上传图片要登录」是一个凭直觉就会加上去的规则，但 Go 侧<b>没有</b>。
 * 由 {@code JwtAuthInterceptor} 的受保护表保证它不被拦截
 * （见该类注释「7 个端点里恰好 4 个要 JWT」）。
 *
 * <h2>⚠️ query / form 参数刻意声明成 {@link String}</h2>
 * {@code incr_view} / {@code page} / {@code page_size} / {@code sort_by} / {@code desc}
 * 的真值表与默认值规则由 {@link ArticleQueryValues} 承担；
 * 若让 Spring 直接转 boolean/int，{@code ?incr_view=1} 会从「true」变成 400。
 */
@RestController
@RequestMapping("/v1")
public class ArticleController {

    private final ArticleApiService apiService;

    public ArticleController(ArticleApiService apiService) {
        this.apiService = apiService;
    }

    // ============== 第一段：JWT 保护 ==============

    /**
     * Go {@code CreateArticleHandler}。
     *
     * <p>⚠️ <b>空 body 的处理</b>：Go 侧 {@code httpx.Parse} 在「Content-Length 为 0 或
     * Content-Type 非 JSON」时走 {@code mapping.UnmarshalJsonMap(nil, v)}，即<b>当空对象继续解析</b>
     * ⇒ 随后 {@code title} / {@code content} 缺席 ⇒ {@code field "title" is not set} ⇒ <b>400</b>。
     * <p>⇒ C 侧用 {@code required = false} ＋ 显式补一次同样的 400
     * （<b>文案逐字相同</b>：{@code field "title" is not set}）。
     * ⚠️ <b>不能</b>只靠 {@code @Valid}：<b>body 缺席时 Spring 不会执行校验</b>
     * ⇒ 那会让「空 body」变成 200 ⇒ 与 Go 不一致。
     *
     * <p>⚠️ <b>顺序与 Go 一致</b>：先解析身份（失败 → 1005），再进业务。
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:123-129}。
     */
    @PostMapping("/article")
    public CreateArticleResp createArticle(@RequestBody(required = false) @Valid CreateArticleReq req,
                                           HttpServletRequest httpRequest) {
        CreateArticleReq body = (req == null) ? null : req;
        if (body == null) {
            // Go: 零值 struct ⇒ title 缺席 ⇒ field "title" is not set ⇒ 400 + text/plain
            // ⚠️ 这里刻意**不**走 Bean Validation（body 缺席时它不会执行）
            throw new BadRequestException("field \"title\" is not set");
        }
        return apiService.createArticle(body, ArticleApiSupport.userId(httpRequest));
    }

    /**
     * Go {@code UpdateArticleHandler}。
     *
     * <p>⚠️ {@code articleId} 来自 path，<b>不</b>参与 JSON 绑定。
     * <p>⚠️ <b>空 body 是合法请求</b>：Go 侧它的七个 JSON 字段全部是 {@code ,optional}
     * ⇒ 「只带 URL 里的 id」是一次合法请求（什么都不改）⇒ C 侧同样用 {@code required = false}。
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:137-149}。
     */
    @PutMapping("/article/{id}")
    public UpdateArticleResp updateArticle(@PathVariable("id") String id,
                                           @RequestBody(required = false) UpdateArticleReq req,
                                           HttpServletRequest httpRequest) {
        return apiService.updateArticle(id, req, ArticleApiSupport.userId(httpRequest));
    }

    /**
     * Go {@code DeleteArticleHandler}。Go 侧 {@code DeleteArticleReq} 零校验，故无 normalize。
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:152-157}。
     */
    @DeleteMapping("/article/{id}")
    public DeleteArticleResp deleteArticle(@PathVariable("id") String id,
                                           HttpServletRequest httpRequest) {
        return apiService.deleteArticle(
                new DeleteArticleReq(id, ArticleApiSupport.userId(httpRequest)));
    }

    /**
     * Go {@code GetAuthorArticleHandler}。
     *
     * <p>⚠️ <b>不声明</b> {@code incr_view}：Go 的 {@code GetAuthorArticleLogic}
     * 构造请求时没有 {@code IncrView} 字段（恒 false）。
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:167-171}。
     */
    @GetMapping("/me/article/{id}")
    public GetArticleResp getAuthorArticle(@PathVariable("id") String id,
                                           HttpServletRequest httpRequest) {
        return apiService.getAuthorArticle(id, ArticleApiSupport.userId(httpRequest));
    }

    // ============== 第二段：公开（无 JWT） ==============

    /**
     * Go {@code GetArticleHandler}（公开读详情）。
     *
     * @param incrView <b>原始</b> query 值（多值数组），交给
     *                  {@link ArticleQueryValues#first(String[])} 按 Go 规则取首，
     *                  再由 {@code ArticleQueryValues.bool} 按 Go 真值表解释
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:181-185}。
     */
    @GetMapping("/article/{id}")
    public GetArticleResp getArticle(@PathVariable("id") String id,
                                     @RequestParam(name = "incr_view", required = false) String[] incrView) {
        return apiService.getArticle(id, ArticleQueryValues.first(incrView));
    }

    /**
     * Go {@code ListArticlesHandler}（公开列表）。
     *
     * <p>⚠️ 八个参数<b>全部</b>声明成 {@link String} 交给 {@link ListArticlesReq} 的静态工厂，
     * 因为 Go 的默认值与真值表规则（空值丢弃 / {@code 1,0} / base-10）由那一处统一处理。
     * 这里<b>不</b>用 {@code @RequestParam(defaultValue = "1")} ——
     * 那会让「默认值」这件事散到 4 个注解上，且 {@code desc} 的 {@code "1"} 就变成 400。
     *
     * <h2>⚠️ 为什么声明成 {@code String[]} 而不是 {@code String}</h2>
     * <p>Go 侧同名 key 出现多次时<b>取第一个</b>（{@code unmarshaler.go:835-846}），
     * 而 Spring 的 {@code @RequestParam String} 会把多值<b>拼成一个字符串</b>
     * （形如 {@code "1,2"}）⇒ 本域的 {@code ArticleQueryValues.int32} 解析失败 ⇒ <b>400</b>。
     * <p>⇒ 声明成数组，再用 {@link ArticleQueryValues#first(String[])} 按 Go 规则取首。
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:201-216}。
     */
    @GetMapping("/articles")
    public ListArticlesResp listArticles(
            @RequestParam(name = "manual_type_tag", required = false) String[] manualTypeTag,
            @RequestParam(name = "secondary_tag", required = false) String[] secondaryTag,
            @RequestParam(name = "related_game_id", required = false) String[] relatedGameId,
            @RequestParam(name = "author_id", required = false) String[] authorId,
            @RequestParam(name = "page", required = false) String[] page,
            @RequestParam(name = "page_size", required = false) String[] pageSize,
            @RequestParam(name = "sort_by", required = false) String[] sortBy,
            @RequestParam(name = "desc", required = false) String[] desc) {
        return apiService.listArticles(ListArticlesReq.of(
                ArticleQueryValues.first(manualTypeTag), ArticleQueryValues.first(secondaryTag),
                ArticleQueryValues.first(relatedGameId), ArticleQueryValues.first(authorId),
                ArticleQueryValues.first(page), ArticleQueryValues.first(pageSize),
                ArticleQueryValues.first(sortBy), ArticleQueryValues.first(desc)));
    }

    // ============== 第三段：公开 multipart ==============

    /**
     * Go {@code UploadImageHandler} —— <b>本模块唯一不走信封的端点</b>。
     *
     * <p>返回 {@link ResponseEntity} 是<b>刻意</b>的：{@code ArticleApiResponseAdvice}
     * 对 {@code ResponseEntity} 放行（不包信封），从而复现 Go
     * {@code httpx.OkJsonCtx(ctx, w, resp)} 的裸 {@code {"image_url":…}}。
     * 若改成直接返回 {@link UploadImageResp}，advice 会把它包成
     * {@code {"code":200,…,"data":{…}}} —— 与 Go 不一致。
     *
     * <p>⚠️ {@code required = true}：Go 侧 {@code r.FormFile("image")} 取不到就
     * {@code httpx.ErrorCtx} ⇒ 400 text/plain。Spring 的
     * {@code MissingServletRequestPartException} 被
     * {@code ArticleApiExceptionHandler#handleParseFailure} 映到同一个 400 text/plain。
     *
     * <p>⚠️ multipart 字段名固定为 {@code "image"}
     * （见 {@code ArticleApiService#FORM_FIELD}），这是 L1 契约。
     *
     * <p><b>B 出处</b>：{@code …/controller/ArticleController.java:237-241}。
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadImageResp> uploadImage(
            @RequestParam(ArticleApiService.FORM_FIELD) MultipartFile image) {
        return ResponseEntity.ok(apiService.uploadImage(image));
    }
}
