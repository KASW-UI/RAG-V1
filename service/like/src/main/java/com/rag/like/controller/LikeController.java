package com.rag.like.controller;

import com.rag.like.dto.action.LikeActionReq;
import com.rag.like.dto.action.LikeActionResp;
import com.rag.like.dto.count.GetLikeCountReq;
import com.rag.like.dto.count.GetLikeCountResp;
import com.rag.like.dto.count.GetUserTotalLikeReq;
import com.rag.like.dto.count.GetUserTotalLikeResp;
import com.rag.like.dto.list.GetTargetLikerListReq;
import com.rag.like.dto.list.GetTargetLikerListResp;
import com.rag.like.dto.list.GetUserLikeListReq;
import com.rag.like.dto.list.GetUserLikeListResp;
import com.rag.like.dto.state.GetLikeStateReq;
import com.rag.like.dto.state.GetLikeStateResp;
import com.rag.like.service.LikeApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>like 域全部 6 个 HTTP 端点</b> —— B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/controller/LikeController.java:64-155}
 * 的等价物（路由契约逐条照抄）。
 *
 * <h2>⚠️ L1 路由契约冻结（勿凭直觉改）</h2>
 * <pre>
 *   POST /like/v1/getlikecount       → 点赞/点踩数批量查询
 *   POST /like/v1/getlikestate       → 某用户对一批作品的状态
 *   POST /like/v1/gettargetlikerlist → 某作品的点赞人列表
 *   POST /like/v1/getuserlikelist    → 某用户给出的点赞列表
 *   POST /like/v1/getusertotallike   → 某作者的累计获赞数
 *   POST /like/v1/likeaction         → 点赞/取消点赞/点踩/取消点踩
 * </pre>
 * ⇒ <b>6 个端点全是 {@code @PostMapping}</b>（<b>无 GET / PUT / DELETE</b>）。
 * B 出处：B 侧 {@code LikeController.java:39-40}（"方法全是 POST，没有 GET、没有 DELETE"）
 * ＋ {@code :97-145} 的 6 个 {@code @PostMapping}。
 *
 * <p>⚠️ <b>路径前缀只有表达层一处差异</b>：B 侧用 {@code rest.WithPrefix("/like/v1")}
 * ＋ 相对 path；Spring 用类级 {@code @RequestMapping("/like/v1")} ＋ 方法级相对 path。
 *
 * <h2>⚠️ 请求体的 {@code required} 口径：<b>两类 DTO，两种口径</b></h2>
 * <p>B 侧 6 个 handler <b>全部</b>是 {@code @RequestBody(required = false)} ＋
 * {@code GoStyleRequest.normalize(req, XxxReq::new)}
 * （后者 = 「空 body → 零值 DTO」＋「go-zero 必填校验」）。
 * <p>C 侧 A10 禁用手写校验器 ⇒ 必填校验改用 <b>Bean Validation</b>（DTO 上的 {@code @NotNull}）。
 * ⇒ 于是分成两类：
 * <table border="1">
 *   <caption>两类 DTO 的 {@code required} 口径</caption>
 *   <tr><th>端点</th><th>B 侧必填字段</th><th>C 侧写法</th><th>空 body 的结局</th></tr>
 *   <tr>
 *     <td>{@code getlikecount}</td>
 *     <td>{@code target_type} · {@code target_ids}</td>
 *     <td>{@code @Valid @RequestBody}</td>
 *     <td>Spring 抛 {@code HttpMessageNotReadableException} ⇒ <b>200 + {code:5001}</b> ✔</td>
 *   </tr>
 *   <tr>
 *     <td>{@code getlikestate}</td>
 *     <td>{@code target_type} · {@code target_ids}</td>
 *     <td>{@code @Valid @RequestBody}</td>
 *     <td>同上 ✔</td>
 *   </tr>
 *   <tr>
 *     <td>{@code gettargetlikerlist}</td>
 *     <td>{@code target_type} · {@code target_id} · {@code cursor} · {@code page_size}</td>
 *     <td>{@code @Valid @RequestBody}</td>
 *     <td>同上 ✔</td>
 *   </tr>
 *   <tr>
 *     <td>{@code getuserlikelist}</td>
 *     <td>{@code target_type} · {@code cursor} · {@code page_size}</td>
 *     <td>{@code @Valid @RequestBody}</td>
 *     <td>同上 ✔</td>
 *   </tr>
 *   <tr>
 *     <td>{@code getusertotallike}</td>
 *     <td><b>无</b>（B 侧 {@code GetUserTotalLikeReq.assertGoRequiredFields()} 是空实现）</td>
 *     <td>{@code @RequestBody(required = false)} + 零值兜底</td>
 *     <td>body 缺省 ⇒ 零值 DTO ⇒ <b>继续往下走</b> ✔</td>
 *   </tr>
 *   <tr>
 *     <td>{@code likeaction}</td>
 *     <td>{@code target_type} · {@code target_id} · {@code action_type} · {@code author_id}</td>
 *     <td>{@code @Valid @RequestBody}</td>
 *     <td>同上 ✔</td>
 *   </tr>
 * </table>
 *
 * <h3>⚠️ 为什么「有必填字段」用 {@code required = true} ＋ {@code @Valid}（B 侧是 required=false ＋ 手写校验器）</h3>
 * <p>B 侧是 {@code @RequestBody(required = false)} ＋ <b>手写必填校验器</b>
 * （{@code GoStyleRequired.field("target_type", targetType)}）；C 侧手写校验器已被 <b>A10</b> 禁用
 * ⇒ 改用 <b>Bean Validation</b>（DTO 上的 {@code @NotNull}）。
 * <p><b>语义等价论证（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把「手写校验器」换成「Bean Validation」—— 表达层手段不同，
 *       <b>判据完全相同</b>：B 侧的 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法），
 *       C 侧的 {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *       ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔；</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两侧的失败出口都是<b>解析/校验失败</b>
 *       ⇒ 都被异常处理器折叠成 <b>200 + {code:5001}</b>（B 侧 {@code httpx.SetErrorHandler}
 *       把一切业务错误压成 200；C 侧 {@code LikeApiExceptionHandler} 逐条照抄）✔；</li>
 *   <li><b>B 出处</b>：B 侧 {@code LikeController.java:97-145} 的 6 个
 *       {@code @RequestBody(required = false)} ＋ B 侧各 DTO 的
 *       {@code assertGoRequiredFields()}（{@code GoStyleRequired.field} 只判 null）。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么 {@code getusertotallike} 必须 {@code required = false} ＋ 零值兜底</h3>
 * <p>{@link GetUserTotalLikeReq} 在 B 侧<b>没有任何必填字段</b>（uid 来自 JWT claim，不来自 body）
 * ⇒ 空 body 时 B 侧得到<b>零值 struct</b>并<b>继续往下走</b>
 * ⇒ C 侧必须 {@code required = false}（body 可缺省）<b>且</b>把「缺省」翻译成零值 DTO
 * （{@link #zeroIfNull(GetUserTotalLikeReq)}）⇒ 否则 {@code req.targetType()} 会 NPE。
 *
 * <h3>⚠️ 本类<b>不</b>做任何业务判定</h3>
 * <p>身份解析（uid 提取 + 1006 + reject 指标）、{@code target_ids} 的 null 元素语义、
 * gRPC/领域错误映射全部在 {@code service/LikeApiService} / {@code service/LikeApiSupport} 里
 * ⇒ 本类只做「HTTP 形状 → 用例调用」。
 */
@RestController
@RequestMapping("/like/v1")
public class LikeController {

    private final LikeApiService likeApiService;

    public LikeController(LikeApiService likeApiService) {
        this.likeApiService = likeApiService;
    }

    /**
     * 查询某类作品的一批点赞 / 点踩数。
     *
     * <p>⚠️ <b>公开读</b>：B 侧的 {@code LikeCountService} <b>不</b>取 uid ⇒ 不需要 JWT claim。
     */
    @PostMapping("/getlikecount")
    public GetLikeCountResp getLikeCount(@Valid @RequestBody GetLikeCountReq req) {
        return likeApiService.getLikeCount(req);
    }

    /**
     * 查询某用户对一批作品的状态。
     */
    @PostMapping("/getlikestate")
    public GetLikeStateResp getLikeState(@Valid @RequestBody GetLikeStateReq req,
                                         HttpServletRequest httpRequest) {
        return likeApiService.getLikeState(req, httpRequest);
    }

    /**
     * 查询某作品的点赞人列表。
     *
     * <p>⚠️ <b>公开读</b>：B 侧的 {@code TargetLikerListService} <b>不</b>取 uid。
     */
    @PostMapping("/gettargetlikerlist")
    public GetTargetLikerListResp getTargetLikerList(@Valid @RequestBody GetTargetLikerListReq req) {
        return likeApiService.getTargetLikerList(req);
    }

    /**
     * 查询某用户给出的点赞列表。
     */
    @PostMapping("/getuserlikelist")
    public GetUserLikeListResp getUserLikeList(@Valid @RequestBody GetUserLikeListReq req,
                                                HttpServletRequest httpRequest) {
        return likeApiService.getUserLikeList(req, httpRequest);
    }

    /**
     * 查询某作者的累计获赞数。
     *
     * <p>⚠️ <b>body 可缺省</b>（B 侧该 DTO 无必填字段）⇒ 缺省时用零值 DTO。
     */
    @PostMapping("/getusertotallike")
    public GetUserTotalLikeResp getUserTotalLike(@RequestBody(required = false) GetUserTotalLikeReq req,
                                                  HttpServletRequest httpRequest) {
        return likeApiService.getUserTotalLike(zeroIfNull(req), httpRequest);
    }

    /**
     * 执行一次点赞 / 取消点赞 / 点踩 / 取消点踩。
     */
    @PostMapping("/likeaction")
    public LikeActionResp likeAction(@Valid @RequestBody LikeActionReq req,
                                     HttpServletRequest httpRequest) {
        return likeApiService.likeAction(req, httpRequest);
    }

    /**
     * 空 body → 零值 DTO。
     *
     * <p>对应 B 侧 {@code GoStyleRequest.normalize(req, GetUserTotalLikeReq::new)} 的
     * 「空 body ⇒ 零值 struct」那一半（该 DTO 无必填字段 ⇒ 另一半的校验是空操作）。
     */
    private static GetUserTotalLikeReq zeroIfNull(GetUserTotalLikeReq req) {
        return req == null ? new GetUserTotalLikeReq() : req;
    }
}
