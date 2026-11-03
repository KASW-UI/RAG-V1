package com.rag.hot.controller;

import com.rag.hot.dto.article.HotArticlesReq;
import com.rag.hot.dto.article.HotArticlesResp;
import com.rag.hot.service.HotApiService;
import com.rag.hot.service.HotApiSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>单端点 {@code GET /hot/v1/articles}</b> —— hot 域<b>唯一</b>的 HTTP 端点。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/controller/HotArticleController.java}
 * <pre>{@code
 *   @RestController + @RequestMapping("/hot/v1")           :53-55
 *   PARAM_PAGE = "page" / PARAM_PAGE_SIZE = "page_size"    :58 / :61
 *   getHotArticles(HttpServletRequest)                     :77-97
 *     requireFormValueCountWithinLimit(request)            :88
 *     new HotArticlesReq(int32(firstFormValue(...,"page"),1),
 *                         int32(firstFormValue(...,"page_size"),20))  :90-92
 *     requireParsableJsonBody(request)                    :94
 *     return hotArticleQueryService.getHotArticles(req)    :96
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       ① 类名 / 包名压平（{@code com.rag.hot.hot.api.controller} → {@code com.rag.hot.controller}，D7）；
 *       ② 服务依赖 {@code HotArticleQueryService} → {@link HotApiService}（D2 域内直连 + D7 合并）；
 *       ③ <b>form 值上限闸门改由共享层承担</b>（见下方「⚠️ 上限闸门」专段）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>路由（{@code @RequestMapping("/hot/v1")} + {@code @GetMapping("/articles")}）逐字未动；</li>
 *         <li>两个 query 键名（{@code page} / {@code page_size}，<b>下划线</b>）逐字未动；</li>
 *         <li>解析规则（空值丢弃 / 同名多值取首值 / base-10 int32 / JSON body 必须是对象）逐条未动
 *             ⇒ 落在 {@link HotApiSupport} 的静态方法里；</li>
 *         <li>handler 仍是「薄适配层」：解析 → 委托 → 返回，<b>不做值域校验</b>
 *             （{@code page=-1} 原样进 service）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 零鉴权：本类<b>刻意没有</b>任何 interceptor / SecurityFilter</b>：
 * B 侧的路由注册只有前缀，<b>没有</b>鉴权中间件 ⇒ {@code GET /hot/v1/articles} 是<b>公开读</b>端点。
 * 引入鉴权拦截器会让「匿名可拉热榜」变成「必须带 token」—— 这是 L2 级行为变更
 * （不是「补一个缺失的安全措施」）⇒ 本域<b>也不需要</b> {@code WebMvcConfig} 去注册任何东西
 * （见 {@code com.rag.hot.config.WebMvcConfig} 的类注释）。
 *
 * <p><b>⚠️ 两个 query 参数都走 {@link HotApiSupport}，不用 {@code @RequestParam}</b>：
 * 三个原因，逐条依据见 {@link HotApiSupport} 的类注释：
 * <ol>
 *   <li>写成 {@code int} 会让 Spring 用 {@code NumberUtils.parseNumber} 转换，
 *       与 B 侧的 {@code strconv.ParseInt(str, 10, 32)} 有三条分歧
 *       （base 0 vs base 10、int32 越界、空值）。</li>
 *   <li>写成 {@code String} 在<b>同名多值</b>时会被 Spring 拼成 {@code "5,3"}，
 *       而 B 侧取首值（{@code page=5}）。</li>
 *   <li>B 侧的 {@code httpx.Parse} 还有一条本端点可触发的拒绝路径（畸形 JSON body），
 *       {@code @RequestParam} 表达不了。</li>
 * </ol>
 *
 * <p><b>⚠️ form 值个数上限（2048）改由共享层承担</b>：
 * B 侧本类第 1 步调 {@code GoFormValues.requireFormValueCountWithinLimit(request)}；
 * 本仓的共享层 {@code com.rag.common.web.ValueLimitInterceptor}（由
 * {@code common/autoconfigure/WebAutoConfiguration} 装配）按
 * {@code rag.http.go-form-limit.mode} 判定 ⇒ hot 域配 {@code envelope}
 * ⇒ 超限时抛 {@code LimitExceededException} ⇒ 由
 * {@link com.rag.hot.advice.HotApiExceptionHandler} 渲染成
 * <b>200 + {code:5001, msg:"internal server error", data:null}</b>
 * ⇒ ⇒ <b>wire 形状与 B 侧完全一致</b> ⇒ ⇒ 本类<b>不重复实现</b>该闸门
 * （重复实现 ⇒ 同一请求被拒两次，且两处的判据口径不同 ⇒ 必然分叉）。
 */
@RestController
@RequestMapping("/hot/v1")
public class HotArticleController {

    /** B 侧 {@code types.go:23} 的 {@code form:"page,default=1"}。 */
    private static final String PARAM_PAGE = "page";

    /** B 侧 {@code types.go:24} 的 {@code form:"page_size,default=20"}。 */
    private static final String PARAM_PAGE_SIZE = "page_size";

    private final HotApiService hotApiService;

    public HotArticleController(HotApiService hotApiService) {
        this.hotApiService = hotApiService;
    }

    /**
     * {@code GET /hot/v1/articles} —— B 侧的 handler + logic 入口。
     *
     * <p>B 侧的 handler 是「薄适配层」：Parse → Logic → {@code httpx.OkJsonCtx}，
     * <b>不做任何校验</b>（{@code page=-1} 会原样进 logic）。本类保持同样形状：只做解析与委托。
     *
     * <p><b>B 出处</b>：{@code .../api/controller/HotArticleController.java:77-97}。
     *
     * @param request 当前请求
     * @return 热门文章列表响应
     */
    @GetMapping("/articles")
    public HotArticlesResp getHotArticles(HttpServletRequest request) {
        // ⚠️ 这里**刻意不用** @RequestParam：Spring 在同名多值时会把 String[] 用 "," 拼起来
        //    （"5,3"）⇒ parseInt 失败 ⇒ 整包 5001；而 B 侧是「取第 0 个 ⇒ page=5」。
        //
        // 执行顺序对齐 B 侧的 httpx.Parse：
        //   ParsePath → ParseForm（逐字段解析）→ ParseHeaders → ParseJsonBody
        // ⚠️ 三类失败在 wire 上是**同一个形状**（200 + {code:5001,msg:"internal server error"}），
        //    所以顺序只影响日志，不影响可观察结果 —— 这里仍按 B 侧的顺序写，便于对拍。
        HotArticlesReq req = new HotArticlesReq(
                HotApiSupport.parseInt32(HotApiSupport.firstFormValue(request, PARAM_PAGE), PARAM_PAGE, 1),
                HotApiSupport.parseInt32(HotApiSupport.firstFormValue(request, PARAM_PAGE_SIZE), PARAM_PAGE_SIZE, 20));

        HotApiSupport.requireParsableJsonBody(request);

        return hotApiService.getHotArticles(req);
    }
}
