package com.rag.favorite.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.favorite.dto.favorite.CreateFavoriteReq;
import com.rag.favorite.dto.favorite.CreateFavoriteResp;
import com.rag.favorite.dto.favorite.DeleteFavoriteReq;
import com.rag.favorite.dto.favorite.DeleteFavoriteResp;
import com.rag.favorite.dto.favorite.GetFavoritesByFolderReq;
import com.rag.favorite.dto.favorite.GetFavoritesByFolderResp;
import com.rag.favorite.dto.folder.CreateFavoriteFolderReq;
import com.rag.favorite.dto.folder.CreateFavoriteFolderResp;
import com.rag.favorite.dto.folder.DeleteFavoriteFolderReq;
import com.rag.favorite.dto.folder.DeleteFavoriteFolderResp;
import com.rag.favorite.dto.folder.GetFavoriteFoldersResp;
import com.rag.favorite.dto.folder.UpdateFavoriteFolderNameReq;
import com.rag.favorite.dto.folder.UpdateFavoriteFolderNameResp;
import com.rag.favorite.service.FavoriteApiService;
import com.rag.favorite.service.FavoriteApiSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * <p><b>favorite 域全部 7 个 HTTP 端点</b> —— B 侧
 * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/controller/FavoriteController.java:74-215}
 * 的等价物（路由契约逐条照抄）。
 *
 * <h2>⚠️ L1 路由契约冻结（勿凭直觉改）</h2>
 * <pre>
 *   POST   /favorite/v1/folder/create   → JSON body
 *   DELETE /favorite/v1/folder/delete   → JSON body
 *   GET    /favorite/v1/folder/list     → 无 body（但仍要解析并校验 body，见下）
 *   POST   /favorite/v1/folder/update   → JSON body
 *   POST   /favorite/v1/item/create     → JSON body
 *   DELETE /favorite/v1/item/delete     → JSON body
 *   GET    /favorite/v1/item/list       → **query string**（form 通道）
 * </pre>
 * ⇒ <b>method 是混合的</b>（Get2 / Post3 / Delete2），<b>不是</b>「统一 POST」。
 * <p>B 出处：B 侧 {@code FavoriteController.java:74-215} 的 7 个映射注解
 * ＋ {@code api/internal/handler/routes.go:15-60} 的路由表
 * （{@code rest.WithPrefix("/favorite/v1")} ＋ 7 条 {@code rest.Route}）。
 *
 * <h2>⚠️ 只有 {@code GET /item/list} 走 query（form 通道）</h2>
 * <p>B 侧 {@code types.GetFavoritesByFolderReq} 的 tag 是 {@code form:"folderId,string"}
 * （{@code api/internal/types/types.go:67}），而 go-zero 的 {@code core/mapping}
 * <b>按 tag 名决定通道</b> —— 带 {@code form} tag 的字段只在 form 通道生效、
 * 带 {@code json} tag 的字段只在 json 通道生效
 * （{@code core/mapping/unmarshaler.go:569-573} 的 {@code usingDifferentKeys}）。
 * 其余 6 个端点的字段全是 {@code json} tag ⇒ 全部走 JSON body。
 *
 * <h2>⚠️ DELETE 也带 JSON body</h2>
 * <p>B 侧 {@code httpx.Parse} 对所有方法都跑 {@code ParseJsonBody}，
 * 与 HTTP method 无关（只有 Content-Type 与 Content-Length 参与判定）。
 *
 * <h2>⚠️ 鉴权划分（L1）：<b>7/7 端点全部受保护，无 public 段</b></h2>
 * <p>B 侧 {@code api/internal/handler/routes.go:57} 对<b>整张路由表</b>套
 * {@code rest.WithJwt(...)} ⇒ <b>不存在</b> public 路径
 * ⇒ C 侧 {@code config/WebMvcConfig} 注册鉴权拦截器时<b>不加</b>
 * {@code excludePathPatterns}。
 * <br>⚠️ C 侧空壳 javadoc 写着「<b>有 public 段</b>」⇒ <b>那是错的</b>，本实现以 B 侧为准。
 *
 * <h2>⚠️ 请求体的 {@code required} 口径</h2>
 * <p>B 侧 7 个 handler <b>全部</b>是 {@code @RequestBody(required = false)} ＋
 * {@code GoStyleRequest.normalize(req, XxxReq::new)}
 * （后者 = 「空 body → 零值 DTO」＋「go-zero 必填校验」）。
 * <p>C 侧 A10 禁用手写校验器 ⇒ 必填校验改用 <b>Bean Validation</b>（DTO 上的 {@code @NotNull}）。
 * <p><b>本域 6 个 JSON body 端点的 DTO <b>全部</b>有必填字段</b>
 * ⇒ 于是统一用 {@code @Valid @RequestBody}（required 默认 true）：
 * <table border="1">
 *   <caption>空 body 的结局（两侧一致）</caption>
 *   <tr><th>情形</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr>
 *     <td>body <b>缺省</b></td>
 *     <td>{@code UnmarshalJsonMap(nil, v)} ⇒ 必填字段全落空
 *         ⇒ {@code field "xxx" is not set} ⇒ <b>200 + 5003</b></td>
 *     <td>Spring 抛 {@code HttpMessageNotReadableException}
 *         ⇒ <b>200 + 5003</b> ✔</td>
 *   </tr>
 *   <tr>
 *     <td>body <b>语法错误</b></td>
 *     <td>JSON 解析失败 ⇒ <b>200 + 5003</b></td>
 *     <td>{@code HttpMessageNotReadableException} ⇒ <b>200 + 5003</b> ✔</td>
 *   </tr>
 *   <tr>
 *     <td>body <b>类型不匹配</b></td>
 *     <td>{@code type mismatch for field "xxx"} ⇒ <b>200 + 5003</b></td>
 *     <td>{@code HttpMessageNotReadableException} ⇒ <b>200 + 5003</b> ✔</td>
 *   </tr>
 *   <tr>
 *     <td>Content-Type <b>不是 JSON</b></td>
 *     <td>{@code withJsonBody} 为 false ⇒ 必填字段全落空 ⇒ <b>200 + 5003</b></td>
 *     <td>{@code HttpMediaTypeNotSupportedException} ⇒ <b>200 + 5003</b> ✔</td>
 *   </tr>
 * </table>
 * ⇒ ⇒ <b>本域不需要「零值 DTO 兜底」那条路</b>（unlike 域的 {@code getusertotallike}：
 * 那个 DTO <b>没有</b>必填字段 ⇒ 必须 {@code required = false} + 零值兜底）。
 *
 * <h2>⚠️ form 通道（{@code GET /item/list}）不走 {@code @RequestBody}</h2>
 * <p>该端点必须<b>自己</b>取原始多值参数，<b>不能</b>用
 * {@code @RequestParam(name="folderId") String}：Spring 对重复参数会<b>逗号拼接</b>成
 * {@code "123,456"}（⇒ 解析失败 ⇒ 5003），而 Go 是「<b>先滤空值再取首值</b>」⇒ 123 ⇒ 200。
 * 详见 {@link FavoriteApiSupport#goFormFirstValue}。
 * <p>⇒ 手工构造出来的 DTO <b>不经 Spring 的 {@code @Valid}</b>
 * ⇒ 用 {@link Validator}（Bean Validation 本身，<b>不是</b>手写校验器 · A10）显式校验，
 * 违规 ⇒ {@link ConstraintViolationException} ⇒ 域内兜底 handler 渲染 <b>200 + 5003</b>。
 */
@RestController
@RequestMapping("/favorite/v1")
public class FavoriteController {

    private final FavoriteApiService favoriteApiService;

    /**
     * Spring 托管的 Jackson 2 mapper —— 用于复刻 Go 对空 struct 请求体的校验
     * （见 {@link #assertGoEmptyStructBody}）。
     *
     * <p><b>为什么注入而不是 new 一个</b>：mapper 的配置来源（{@code Coercion} 等
     * {@code Jackson2ObjectMapperBuilderCustomizer}）都在容器里，自己 new 会绕过配置；
     * 而且 mapper 是重量级对象，应当作为单例共享。
     */
    private final ObjectMapper objectMapper;

    /**
     * Bean Validation 的入口 —— form 通道手工构造的 DTO 靠它校验
     * （见 {@link #getFavoritesByFolder}）。
     */
    private final Validator validator;

    public FavoriteController(FavoriteApiService favoriteApiService,
                              ObjectMapper objectMapper,
                              Validator validator) {
        this.favoriteApiService = favoriteApiService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    // ============== 收藏夹 ==============

    /**
     * 新建收藏夹。
     *
     * <p>B 出处：B 侧 {@code FavoriteController.java:129-135} ＋
     * {@code api/internal/handler/favorite/create_favorite_folder_handler.go:18-33}。
     */
    @PostMapping("/folder/create")
    public CreateFavoriteFolderResp createFavoriteFolder(@Valid @RequestBody CreateFavoriteFolderReq req,
                                                          HttpServletRequest httpRequest) {
        return favoriteApiService.createFavoriteFolder(req, httpRequest);
    }

    /**
     * 删除收藏夹。
     *
     * <p>B 出处：B 侧 {@code FavoriteController.java:137-143} ＋
     * {@code api/internal/handler/favorite/delete_favorite_folder_handler.go:18-33}。
     */
    @DeleteMapping("/folder/delete")
    public DeleteFavoriteFolderResp deleteFavoriteFolder(@Valid @RequestBody DeleteFavoriteFolderReq req,
                                                          HttpServletRequest httpRequest) {
        return favoriteApiService.deleteFavoriteFolder(req, httpRequest);
    }

    /**
     * 查询收藏夹列表。
     *
     * <p>⚠️ <b>看起来这个端点「不需要入参」，但 Go 仍然会【解析并校验请求体】</b>——
     * {@code httpx.Parse} 无条件跑 {@code ParseJsonBody}
     * （{@code go-zero@v1.10.2/rest/httpx/requests.go:65-67}，
     * 在 {@code if kind != Array && kind != Slice} 之外），
     * 且 {@code ParseJsonBody} 先判
     * {@code withJsonBody(r) = ContentLength>0 && Content-Type 含 "application/json"}
     * （{@code rest/httpx/requests.go:153-155}）。
     * ⇒ body 非法时 handler 落 {@code ErrorInvalidParam} ⇒ <b>HTTP 200 + 5003</b>。
     * <br>本端点没声明 {@code @RequestBody} ⇒ Tomcat 不会去读 body
     * ⇒ <b>不补这一步就会把非法 body 的请求从 5003 放过成 200</b>（可观察的 L1 差异）。
     */
    @GetMapping("/folder/list")
    public GetFavoriteFoldersResp getFavoriteFolders(HttpServletRequest httpRequest) {
        assertGoEmptyStructBody(httpRequest);
        return favoriteApiService.getFavoriteFolders(httpRequest);
    }

    /**
     * 修改收藏夹名字。
     *
     * <p>B 出处：B 侧 {@code FavoriteController.java:159-165} ＋
     * {@code api/internal/handler/favorite/update_favorite_folder_name_handler.go:18-33}。
     */
    @PostMapping("/folder/update")
    public UpdateFavoriteFolderNameResp updateFavoriteFolderName(@Valid @RequestBody UpdateFavoriteFolderNameReq req,
                                                                  HttpServletRequest httpRequest) {
        return favoriteApiService.updateFavoriteFolderName(req, httpRequest);
    }

    // ============== 收藏 ==============

    /**
     * 新增收藏。
     *
     * <p>B 出处：B 侧 {@code FavoriteController.java:172-178} ＋
     * {@code api/internal/handler/favorite/create_favorite_handler.go:18-33}。
     */
    @PostMapping("/item/create")
    public CreateFavoriteResp createFavorite(@Valid @RequestBody CreateFavoriteReq req,
                                             HttpServletRequest httpRequest) {
        return favoriteApiService.createFavorite(req, httpRequest);
    }

    /**
     * 删除收藏。
     *
     * <p>B 出处：B 侧 {@code FavoriteController.java:180-186} ＋
     * {@code api/internal/handler/favorite/delete_favorite_handler.go:18-33}。
     */
    @DeleteMapping("/item/delete")
    public DeleteFavoriteResp deleteFavorite(@Valid @RequestBody DeleteFavoriteReq req,
                                             HttpServletRequest httpRequest) {
        return favoriteApiService.deleteFavorite(req, httpRequest);
    }

    /**
     * 查询收藏夹下的收藏 —— <b>form 通道</b>（query string）。
     *
     * <p>Go 出处：{@code types.GetFavoritesByFolderReq} 的 tag 是 {@code form:"folderId,string"}
     * （{@code service/favorite/api/internal/types/types.go:67}）。
     * <p>⚠️ <b>缺席与「值非法」在本域落到<b>同一个出口</b></b>（200 + 5003）：
     * B 侧「参数缺席」走的是 {@code field "folderId" is not set}，
     * <b>不是</b> 400 ⇒ 所以这里让空值正常进来，由必填判定统一处理。
     */
    @GetMapping("/item/list")
    public GetFavoritesByFolderResp getFavoritesByFolder(HttpServletRequest httpRequest) {
        // ⚠️ 必须自己取原始多值参数，不能用 `@RequestParam(name="folderId") String`：
        //    Spring 对重复参数会逗号拼接成 "123,456"（⇒ 解析失败 ⇒ 5003），
        //    而 Go 是「先滤空值再取首值」⇒ 123 ⇒ 200。详见 FavoriteApiSupport#goFormFirstValue。
        GetFavoritesByFolderReq req =
                GetFavoritesByFolderReq.fromForm(FavoriteApiSupport.goFormFirstValue(httpRequest, "folderId"));
        // ⚠️ 手工构造的 DTO 不经 Spring 的 @Valid ⇒ 用 Bean Validation 显式校验
        //    （A10：这不是手写校验器，判据仍是 DTO 上的 @NotNull）。
        Set<ConstraintViolation<GetFavoritesByFolderReq>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return favoriteApiService.getFavoritesByFolder(req, httpRequest);
    }

    /**
     * 复刻 Go 对<b>空 struct 请求体</b>的解析 —— 见 {@link #getFavoriteFolders} 的调用点注释。
     *
     * <p><b>Go 出处</b>：{@code go-zero@v1.10.2/rest/httpx/requests.go:117-124} 的 {@code ParseJsonBody}
     * <pre>
     *   func ParseJsonBody(r *http.Request, v any) error {
     *       if withJsonBody(r) {
     *           reader := io.LimitReader(r.Body, maxBodyLen)
     *           return mapping.UnmarshalJsonReader(reader, v)
     *       }
     *       return mapping.UnmarshalJsonMap(nil, v)
     *   }
     * </pre>
     * 配合 {@code go-zero@v1.10.2/rest/httpx/requests.go:153-155} 的
     * {@code withJsonBody = r.ContentLength > 0 &amp;&amp; strings.Contains(CT, "application/json")}。
     *
     * <p><b>真源探针实测（go-zero v1.10.2 + 逐字空 struct）</b>：
     * <pre>
     *   body={}              → 200
     *   body={"a":1}         → 200   （空 struct 忽略未知字段）
     *   body=null            → 5003  （unsupported type on setting field value）
     *   body=123 / 1.5 / true / "str" → 5003（同上）
     *   body=[] / [1,2]      → 5003  （type mismatch）
     *   body={               → 5003  （unexpected EOF）
     *   body={ CT=text/plain } → 200 （withJsonBody 为 false，body 压根不读）
     *   body={ CT=Application/JSON } → 200（strings.Contains 大小写敏感 ⇒ 判 false）
     * </pre>
     * ⇒ 规则收敛成一句：<b>只有「根是 JSON object」才算通过</b>。这与「用 Jackson 反序列化进
     * 一个空 DTO」等价，但只判根节点类型更直白，也避免把 DTO 字段规则卷进来。
     *
     * @throws IllegalArgumentException ⇒ 域内兜底 handler ⇒
     *                                   <b>HTTP 200 + {code:5003, msg:"参数错误"}</b>
     */
    private void assertGoEmptyStructBody(HttpServletRequest httpRequest) {
        // ⚠️ 用 getContentLengthLong()：chunked 传输时返回 -1，与 Go 的 r.ContentLength = -1
        //    一样落到 `> 0` 为 false ⇒ 不读 body。语义一致。
        if (httpRequest.getContentLengthLong() <= 0) {
            return;
        }
        // Go 用 strings.Contains（大小写敏感），不是 MIME 解析 —— 别用 MediaType.parseMediaType。
        String contentType = httpRequest.getHeader("Content-Type");
        if (contentType == null || !contentType.contains("application/json")) {
            return;
        }
        JsonNode root;
        try (InputStream in = httpRequest.getInputStream()) {
            root = objectMapper.readTree(in);
        } catch (JsonProcessingException e) {
            // ⚠️ Jackson 2 的层级：JsonProcessingException extends JacksonException extends IOException
            //    ⇒ 「语法错误」与「流读不动」都能被 IOException 接住，但本处要分开两条消息
            //    （B 侧 go-zero@v1.10.2/core/jsonx/json.go:63 的语法错误 ⇒ 5003）。
            //    Go: jsonx.UnmarshalFromReader 的语法错误（unexpected EOF）⇒ Parse 报错 ⇒ 5003
            throw new IllegalArgumentException("malformed json body", e);
        } catch (IOException e) {
            // 流本身读不动（连接中断等）
            throw new IllegalArgumentException("unreadable json body", e);
        }
        // null / 标量 / 数组在 Go 侧都落在 "unsupported type on setting field value" 或
        // "type mismatch" ⇒ 同样 5003。只有 object 通过。
        // ⚠️ Jackson 2 的 readTree 对空输入返回 null（或 MissingNode）⇒ 同样不是 object ⇒ 5003。
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("body is not a json object");
        }
    }
}
