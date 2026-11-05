package com.rag.comment.controller;

import com.rag.comment.dto.comment.CreateCommentReq;
import com.rag.comment.dto.comment.CreateCommentResp;
import com.rag.comment.dto.comment.DeleteCommentReq;
import com.rag.comment.dto.comment.DeleteCommentResp;
import com.rag.comment.dto.comment.GetCommentReq;
import com.rag.comment.dto.comment.GetCommentResp;
import com.rag.comment.dto.comment.LikeCommentReq;
import com.rag.comment.dto.comment.LikeCommentResp;
import com.rag.comment.dto.comment.ManageCommentReq;
import com.rag.comment.dto.comment.ManageCommentResp;
import com.rag.comment.dto.comment.ReportCommentReq;
import com.rag.comment.dto.comment.ReportCommentResp;
import com.rag.comment.dto.comment.UpdateSubjectStateReq;
import com.rag.comment.dto.comment.UpdateSubjectStateResp;
import com.rag.comment.service.CommentApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>comment 域 HTTP 入口</b> —— 对应 B 侧
 * {@code comment-api/…/api/controller/CommentController.java:31-154}。
 *
 * <p><b>⚠️ L4 落点变化（D1 · api 与 rpc 合并）</b>：
 * B 侧 {@code com.rag.comment.comment.api.controller.CommentController}
 * ⇒ C 侧 {@code com.rag.comment.controller.CommentController}。
 *
 * <h2>🔴 路由是 <b>L1 契约</b> —— 7 条全 POST，逐字不可改</h2>
 * <table border="1">
 *   <caption>B 侧 {@code CommentController.java:96,104,112,126,133,141,149} 的 7 条 {@code @PostMapping}</caption>
 *   <tr><th>#</th><th>路径</th><th>需 uid</th></tr>
 *   <tr><td>1</td><td>{@code /comment/v1/comment/create}</td><td>✅</td></tr>
 *   <tr><td>2</td><td>{@code /comment/v1/comment/delete}</td><td>✅</td></tr>
 *   <tr><td>3</td><td>{@code /comment/v1/comment/like}</td><td>✅</td></tr>
 *   <tr><td>4</td><td>{@code /comment/v1/comment/list}</td><td>❌</td></tr>
 *   <tr><td>5</td><td>{@code /comment/v1/comment/manage}</td><td>✅</td></tr>
 *   <tr><td>6</td><td>{@code /comment/v1/comment/report}</td><td>✅</td></tr>
 *   <tr><td>7</td><td>{@code /comment/v1/subject/state}</td><td>✅</td></tr>
 * </table>
 * ⚠️ <b>路径里 {@code comment} 出现两次是 B 侧原样</b>（前缀 {@code /comment/v1} ＋
 * {@code /comment/create}）⇒ <b>勿「顺手改好」</b>。
 * ⚠️ <b>第 7 条只有一次 {@code comment}</b>（是 {@code /comment/v1} ＋ {@code /subject/state}）
 * ⇒ <b>不是</b> {@code /comment/v1/comment/subject/state}。
 * ⚠️ <b>第 4 条（list）不接 {@code HttpServletRequest}</b> —— B 侧
 * {@code GetCommentService.java:20-21} 明写本类<b>不</b>调 {@code support.userId(request)}
 * ⇒ 该端点<b>不做身份绑定</b>。
 *
 * <h3>⚠️ 7 个用例类合并为 1 个 {@code CommentApiService}（映射表 §1.2 决策锁 · N:1）</h3>
 * <p>B 侧的 7 个用例类（{@code CreateCommentService} … {@code UpdateSubjectStateService}）
 * ⇒ C 侧合并为 1 个 {@code CommentApiService}（7 个方法）。
 * <p>⇒ 注入点从 7 个改成 1 个，<b>方法名与路径一一对应</b>。
 *
 * <h3>⚠️ 必填校验的落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧在<b>每个 handler 里手写</b>
 *       {@code body.assertGoRequiredFields()}；C 侧改用 <b>Bean Validation</b>
 *       （{@code @Valid} + DTO 上的 {@code @NotNull}）—— 即 A10 断言要求的形态
 *       （本仓禁止手写校验器）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法）
 *       ⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *       ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔；
 *       ⇒ 且两者都产出 <b>HTTP 200 + {code:5001}</b>（B 侧解析失败 ⇒ 兜底 5001；
 *       C 侧由 {@code advice/CommentApiExceptionHandler} 折叠同样的形状）。</li>
 *   <li><b>B 出处</b>：B 侧各 handler 的 {@code body.assertGoRequiredFields()} 调用。</li>
 * </ol>
 */
@RestController
@RequestMapping("/comment/v1")
public class CommentController {

    private final CommentApiService commentApiService;

    public CommentController(CommentApiService commentApiService) {
        this.commentApiService = commentApiService;
    }

    /** 建评论 —— B 侧 {@code CommentController.java:96}。 */
    @PostMapping("/comment/create")
    public CreateCommentResp create(@Valid @RequestBody CreateCommentReq body,
                                    HttpServletRequest request) {
        return commentApiService.create(request, body);
    }

    /** 删评论 —— B 侧 {@code CommentController.java:104}。 */
    @PostMapping("/comment/delete")
    public DeleteCommentResp delete(@Valid @RequestBody DeleteCommentReq body,
                                    HttpServletRequest request) {
        return commentApiService.delete(request, body);
    }

    /** 点赞/点踩 —— B 侧 {@code CommentController.java:112}。 */
    @PostMapping("/comment/like")
    public LikeCommentResp like(@Valid @RequestBody LikeCommentReq body,
                                HttpServletRequest request) {
        return commentApiService.like(request, body);
    }

    /**
     * 拉评论列表 —— B 侧 {@code CommentController.java:126}。
     *
     * <p>⚠️ <b>本端点<b>不</b>接 {@code HttpServletRequest}</b>：B 侧
     * {@code GetCommentService.java:20-21} 明写本类<b>不</b>调
     * {@code support.userId(request)} ⇒ 该端点<b>不做身份绑定</b>。
     */
    @PostMapping("/comment/list")
    public GetCommentResp list(@Valid @RequestBody GetCommentReq body) {
        return commentApiService.get(body);
    }

    /** 管理评论（置顶 / 加精）—— B 侧 {@code CommentController.java:133}。 */
    @PostMapping("/comment/manage")
    public ManageCommentResp manage(@Valid @RequestBody ManageCommentReq body,
                                    HttpServletRequest request) {
        return commentApiService.manage(request, body);
    }

    /** 举报评论 —— B 侧 {@code CommentController.java:141}。 */
    @PostMapping("/comment/report")
    public ReportCommentResp report(@Valid @RequestBody ReportCommentReq body,
                                    HttpServletRequest request) {
        return commentApiService.report(request, body);
    }

    /**
     * 更新评论区状态 —— B 侧 {@code CommentController.java:149}。
     *
     * <p>⚠️ 路径是 {@code /comment/v1/subject/state} —— <b>只有一次 {@code comment}</b>。
     */
    @PostMapping("/subject/state")
    public UpdateSubjectStateResp updateSubjectState(@Valid @RequestBody UpdateSubjectStateReq body,
                                                     HttpServletRequest request) {
        return commentApiService.updateState(request, body);
    }
}
