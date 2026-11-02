package com.rag.follow.controller;

import com.rag.follow.dto.list.FollowListReq;
import com.rag.follow.dto.list.FollowUserListResp;
import com.rag.follow.dto.recommend.RecommendListResp;
import com.rag.follow.dto.relation.RelationActionReq;
import com.rag.follow.dto.relation.RelationActionResp;
import com.rag.follow.service.FollowQueryService;
import com.rag.follow.service.FollowRelationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>follow 域全部 8 个端点</b> —— B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/controller/FollowController.java:64-155}
 * 的等价物（路由契约逐条照抄）。
 *
 * <h2>⚠️ L1 路由契约冻结（勿凭直觉改）</h2>
 * <pre>
 *   POST /follow/v1/follow           → 关注       ← FollowRelationService#follow
 *   POST /follow/v1/unfollow         → 取关       ← FollowRelationService#unfollow
 *   POST /follow/v1/block            → 拉黑       ← FollowRelationService#block
 *   POST /follow/v1/unblock          → 解拉黑     ← FollowRelationService#unblock
 *   POST /follow/v1/follow/list      → 关注列表   ← FollowQueryService#getFollowList
 *   POST /follow/v1/follower/list    → 粉丝列表   ← FollowQueryService#getFollowerList
 *   POST /follow/v1/block/list       → 黑名单     ← FollowQueryService#getBlockList
 *   POST /follow/v1/recommendations  → 推荐列表   ← FollowQueryService#getRecommendations
 * </pre>
 * ⇒ <b>8 个端点全是 {@code @PostMapping}</b>（<b>无 GET / PUT / DELETE</b>）。
 * B 出处：B 侧 {@code FollowController.java:39-40}（"方法全是 POST，没有 GET、没有 DELETE"）
 * ＋ {@code :97-145} 的 8 个 {@code @PostMapping}。
 *
 * <p>⚠️ <b>路径前缀只有表达层一处差异</b>：B 侧用 {@code rest.WithPrefix("/follow/v1")}
 * ＋ 相对 path；Spring 用类级 {@code @RequestMapping("/follow/v1")} ＋ 方法级相对 path。
 *
 * <h2>⚠️ 请求体的 {@code required} 口径：<b>两类 DTO，两种口径</b></h2>
 * <p>B 侧 8 个 handler <b>无一例外</b>都调 {@code httpx.Parse(r, &req)} ⇒
 * <b>C 侧 8 个端点都要绑定 body</b>（不许有端点不读 body）⇒
 * 「畸形 JSON / 必填缺失」这两类 B 侧返回 {@code {code:5001}} 的请求在 C 侧也必须同样失败。
 * <table border="1">
 *   <caption>两类 DTO 的 {@code required} 口径</caption>
 *   <tr><th>端点</th><th>DTO</th><th>必填字段</th><th>C 侧写法</th><th>空 body 的结局</th></tr>
 *   <tr>
 *     <td>4 个动作端点</td>
 *     <td>{@link RelationActionReq}</td>
 *     <td>{@code target_id}（B 侧无 {@code ,optional}）</td>
 *     <td>{@code @Valid @RequestBody}（{@code required = true}）</td>
 *     <td>Spring 抛 {@code HttpMessageNotReadableException} ⇒ <b>200 + {code:5001}</b> ✔</td>
 *   </tr>
 *   <tr>
 *     <td>4 个 list 端点</td>
 *     <td>{@link FollowListReq}</td>
 *     <td>无（三字段全 {@code ,optional}）</td>
 *     <td>{@code @RequestBody(required = false)}</td>
 *     <td>body 缺省 ⇒ 零值 DTO ⇒ <b>继续往下走</b> ✔</td>
 *   </tr>
 * </table>
 *
 * <h3>⚠️ 为什么动作端点用 {@code required = true} ＋ {@code @Valid}（B 侧是 required=false ＋ 手写校验器）</h3>
 * <p>B 侧是 {@code @RequestBody(required = false)} ＋ <b>手写必填校验器</b>
 * （{@code GoStyleRequired.field("target_id", targetId)}）；C 侧手写校验器已被 <b>A10</b> 禁用
 * ⇒ 改用 <b>Bean Validation</b>（DTO 上的 {@code @NotNull}）。
 * <p><b>语义等价论证（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把「手写校验器」换成「Bean Validation」—— 表达层手段不同，
 *       <b>判据完全相同</b>：B 侧的 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法），
 *       C 侧的 {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *       ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔；</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两侧的失败出口都是<b>解析失败</b>
 *       ⇒ 都被异常处理器折叠成 <b>200 + {code:5001}</b>（B 侧 {@code httpx.SetErrorHandler}
 *       把一切业务错误压成 200；C 侧 {@code FollowApiExceptionHandler} 逐条照抄）✔；</li>
 *   <li><b>B 出处</b>：B 侧 {@code FollowController.java:98-101}（{@code required = false}）
 *       ＋ {@code service/follow/follow-api/.../dto/RelationActionReq.java} 的
 *       {@code assertGoRequiredFields}。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么 list 端点必须 {@code required = false} ＋ 零值兜底</h3>
 * <p>{@link FollowListReq} 的三个字段在 B 侧<b>全是 {@code ,optional}</b> ⇒ 空 body 时 B 侧得到
 * <b>零值 struct</b>（user_id=0 / offset=0 / limit=0）并<b>继续往下走</b>。
 * ⇒ C 侧必须 {@code required = false}（body 可缺省）<b>且</b>把「缺省」翻译成零值 DTO
 * （{@link #zeroIfNull(FollowListReq)}）⇒ 否则 {@code req.userId()} 会 NPE。
 *
 * <h3>⚠️ 本类<b>不</b>做任何业务判定</h3>
 * <p>身份解析（{@code user_id_missing} 埋点 ＋ 5004）、归属校验（{@code forbidden_scope} 埋点 ＋ 5005）、
 * proto → DTO 转换全部在 {@code FollowRelationService} / {@code FollowQueryService} /
 * {@code FollowApiSupport} 里 ⇒ 本类只做「HTTP 形状 → 用例调用」。
 */
@RestController
@RequestMapping("/follow/v1")
public class FollowController {

    private final FollowRelationService followRelationService;
    private final FollowQueryService followQueryService;

    public FollowController(FollowRelationService followRelationService,
                            FollowQueryService followQueryService) {
        this.followRelationService = followRelationService;
        this.followQueryService = followQueryService;
    }

    // ============== 动作类（4 个）==============

    /**
     * 关注用户。
     *
     * <p>必填：{@code target_id}（B 侧 {@code RelationActionReq.target_id} 无 {@code ,optional}）。
     * ⚠️ 本端点<b>没有</b>归属校验：自关系 / 重复关注 / 被拉黑等判定在领域层。
     */
    @PostMapping("/follow")
    public RelationActionResp follow(@Valid @RequestBody RelationActionReq req,
                                     HttpServletRequest httpRequest) {
        return followRelationService.follow(req, httpRequest);
    }

    /**
     * 取关。
     *
     * <p>必填：{@code target_id}。
     * ⚠️ 取关「不存在的关系」<b>不是错误</b>（领域层删 0 行也照样成功）—— api 层不预判。
     */
    @PostMapping("/unfollow")
    public RelationActionResp unfollow(@Valid @RequestBody RelationActionReq req,
                                       HttpServletRequest httpRequest) {
        return followRelationService.unfollow(req, httpRequest);
    }

    /**
     * 拉黑。
     *
     * <p>必填：{@code target_id}。
     * ⚠️ B 侧的「是否已被对方拉黑」判定在领域层 ⇒ A 拉黑 B 时两个 block 行都可能存在（B 侧既定行为）。
     */
    @PostMapping("/block")
    public RelationActionResp block(@Valid @RequestBody RelationActionReq req,
                                    HttpServletRequest httpRequest) {
        return followRelationService.block(req, httpRequest);
    }

    /**
     * 解拉黑。
     *
     * <p>必填：{@code target_id}。
     */
    @PostMapping("/unblock")
    public RelationActionResp unblock(@Valid @RequestBody RelationActionReq req,
                                      HttpServletRequest httpRequest) {
        return followRelationService.unblock(req, httpRequest);
    }

    // ============== 列表类（4 个）==============

    /**
     * 关注列表（我关注了谁）。
     *
     * <p>⚠️ <b>三个字段全可选</b>（{@code user_id} / {@code offset} / {@code limit}）⇒
     * {@code required = false}，body 可缺省。
     * ⚠️ <b>本端点没有归属校验</b> —— 关注列表是公开可查的（B 侧既定行为，见
     * {@code FollowQueryService#getFollowList} 的注释）。
     */
    @PostMapping("/follow/list")
    public FollowUserListResp getFollowList(@RequestBody(required = false) FollowListReq req,
                                            HttpServletRequest httpRequest) {
        return followQueryService.getFollowList(zeroIfNull(req), httpRequest);
    }

    /**
     * 粉丝列表（谁关注了我）。
     *
     * <p>⚠️ <b>三个字段全可选</b> ⇒ {@code required = false}，body 可缺省。
     * ⚠️ <b>本端点没有归属校验</b> —— 粉丝列表也是公开可查的。
     */
    @PostMapping("/follower/list")
    public FollowUserListResp getFollowerList(@RequestBody(required = false) FollowListReq req,
                                              HttpServletRequest httpRequest) {
        return followQueryService.getFollowerList(zeroIfNull(req), httpRequest);
    }

    /**
     * 黑名单列表。
     *
     * <p>⚠️ <b>三个字段全可选</b> ⇒ {@code required = false}，body 可缺省。
     * ⚠️ <b>本端点有归属校验</b> —— 黑名单是私有的；不一致 ⇒ <b>200 + {code:5005}</b>。
     */
    @PostMapping("/block/list")
    public FollowUserListResp getBlockList(@RequestBody(required = false) FollowListReq req,
                                           HttpServletRequest httpRequest) {
        return followQueryService.getBlockList(zeroIfNull(req), httpRequest);
    }

    /**
     * 推荐列表。
     *
     * <p>⚠️ <b>三个字段全可选</b> ⇒ {@code required = false}，body 可缺省。
     * ⚠️ <b>本端点有归属校验</b> —— 推荐是私有的；不一致 ⇒ <b>200 + {code:5005}</b>。
     * ⚠️ 出参 {@code target_id} 是 <b>JSON 字符串</b>，而 {@code user_ids} 是 JSON 数字。
     */
    @PostMapping("/recommendations")
    public RecommendListResp getRecommendations(@RequestBody(required = false) FollowListReq req,
                                               HttpServletRequest httpRequest) {
        return followQueryService.getRecommendations(zeroIfNull(req), httpRequest);
    }

    /**
     * 空 body → 零值 DTO —— 对应 B 侧 {@code FollowController.java:153-155} 的 {@code zeroIfNull}
     * （B 侧原文注释：「对应 Go 侧 {@code mapping.UnmarshalJsonMap(nil, v)}」）。
     *
     * <p>{@link FollowListReq} 的三个字段全是 {@code ,optional}，
     * 所以零值 DTO <b>不需要</b>再跑一次必填校验（跑也是空转）。
     *
     * <p>⚠️ <b>为什么必须在这里兜底</b>：{@code @RequestBody(required = false)} 在 body 缺省时
     * 交给我们的是 {@code null} ⇒ 不兜底则 {@code req.userId()} 会 NPE ⇒
     * 兜底成零值 DTO 才是 B 侧「零值 struct 继续往下走」的等价物。
     */
    private static FollowListReq zeroIfNull(FollowListReq req) {
        return req == null ? new FollowListReq(null, 0, 0) : req;
    }
}
