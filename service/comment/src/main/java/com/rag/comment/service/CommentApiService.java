package com.rag.comment.service;

import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.dto.comment.CommentItem;
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
import com.rag.comment.dto.comment.SubjectInfo;
import com.rag.comment.dto.comment.UpdateSubjectStateReq;
import com.rag.comment.dto.comment.UpdateSubjectStateResp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>comment 域 7 个 HTTP 用例</b> —— 对应 B 侧
 * {@code comment-api/src/main/java/com/rag/comment/comment/api/service/} 下的 7 个用例类
 * （{@code CreateCommentService} / {@code DeleteCommentService} / {@code GetCommentService} /
 * {@code LikeCommentService} / {@code ManageCommentService} / {@code ReportCommentService} /
 * {@code UpdateSubjectStateService}）。
 *
 * <p><b>抄写基准</b>：B 侧 7 个类（映射表 {@code comment.md} §1.2 决策锁第 3 条：
 * <b>N:1 · 用例内聚</b>，7 类同层同质、不是职责边界 ⇒ 合并为 1 个）。
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>7 个类合 1 个</b>（D5 判据：7 个类都在 api 层、都只做「取身份 → 组请求 → 调下游 → 映射响应」
 *           这四步，职责同质）。</li>
 *       <li><b>不再经过自我 gRPC</b>（<b>D2</b>）：B 侧是
 *           {@code api 用例 → CommentRpcClient → gRPC → comment-rpc 进程 → CommentDomainService}；
 *           C 侧改为<b>域内直连</b> {@link CommentDomainService} ⇒ 少一层序列化往返。</li>
 *       <li>请求/响应的<b>出参类型</b>从 protobuf 换成 DTO record（<b>A9</b>：DTO 必须 immutable record）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 7 个用例的判据、鉴权不对称（6 个要 uid / 1 个不要）、
 *       错误码与文案逐字未改。<b>唯一需要额外论证的是错误映射</b>：B 侧 api 收到的是 gRPC status 异常，
 *       C 侧直连后收到的是领域异常 ⇒ 映射点见 {@link CommentApiSupport#rpcError(Throwable)} 的
 *       {@code CommentExceptions.Rpc} 分支（与 B 侧 status 分支逐字等价）。</li>
 *   <li><b>B 出处</b>：见上 7 个类，逐条标注在各方法 javadoc 里。</li>
 * </ol>
 *
 * <h2>🔴 鉴权不对称是 B 侧显式行为，不能抹平</h2>
 * <table border="1">
 *   <caption>7 个端点的 uid 需求</caption>
 *   <tr><th>端点</th><th>要 uid？</th><th>B 侧出处</th></tr>
 *   <tr><td>{@code create}</td><td>是</td><td>{@code CreateCommentService} 第 1 行</td></tr>
 *   <tr><td>{@code delete}</td><td>是</td><td>{@code DeleteCommentService} 第 1 行</td></tr>
 *   <tr><td>{@code like}</td><td>是</td><td>{@code LikeCommentService} 第 1 行</td></tr>
 *   <tr><td>{@code list}</td><td><b>否</b>（公开读）</td>
 *       <td>{@code GetCommentService} 里<b>没有</b> {@code support.userId(...)} 调用</td></tr>
 *   <tr><td>{@code manage}</td><td>是</td><td>{@code ManageCommentService} 第 1 行</td></tr>
 *   <tr><td>{@code report}</td><td>是</td><td>{@code ReportCommentService} 第 1 行</td></tr>
 *   <tr><td>{@code state}</td><td>是</td><td>{@code UpdateSubjectStateService} 第 1 行</td></tr>
 * </table>
 * ⇒ 6 个要 uid 的端点：claim 解析失败 ⇒ <b>1006</b>（不是 401）⇒ 由
 * {@link CommentApiSupport#userId(HttpServletRequest)} 判定。
 *
 * <h2>⚠️ 「要 uid」是<b>用例级</b>判定，不是拦截器判定</h2>
 * <p>拦截器（{@code interceptor/CommentJwtAuthInterceptor}）只负责<b>验签</b>，
 * <b>不</b>判断「这个端点要不要 uid」⇒ 否则公开读的 {@code list} 端点会被误伤成 1006。
 */
@Service
public class CommentApiService {

    /** Go {@code getcommentlogic.go:51}：{@code if req.PageSize <= 0 { req.PageSize = 20 }}。 */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    /** Go {@code getcommentlogic.go:53}：{@code if req.PageSize > 50 { req.PageSize = 50 }}。 */
    private static final long MAX_PAGE_SIZE = 50L;

    private final CommentDomainService domainService;

    private final CommentApiSupport support;

    public CommentApiService(CommentDomainService domainService, CommentApiSupport support) {
        this.domainService = domainService;
        this.support = support;
    }

    // ==================== 1. 建评论 ====================

    /**
     * 建评论 —— 对应 B 侧 {@code CreateCommentService#create}。
     *
     * <p><b>执行顺序（与 Go 逐条一致）</b>：
     * <ol>
     *   <li>{@code extractUserID} → 失败即 <b>1006</b>（在 try 之外，与 B 侧同位置）</li>
     *   <li>组装请求（userId 来自 token，<b>不</b>来自 body）</li>
     *   <li>调下游</li>
     *   <li>错误 ⇒ 转换（见下）</li>
     *   <li>成功：原样映射 3 个字段</li>
     * </ol>
     *
     * <p><b>⚠️ 错误转换的范围</b>：B 侧 catch 的是 {@code StatusRuntimeException}
     * （gRPC 客户端把<b>任何</b>下游失败都包成 status 异常）⇒ C 侧对应 catch
     * {@link RuntimeException}：{@code CommentExceptions.Rpc} 走「业务码 + 原文」，
     * 其它运行时异常走 B 侧 rpc 层兜底那一支（<b>5001 + 「系统内部错误」</b>）。
     */
    public CreateCommentResp create(HttpServletRequest request, CreateCommentReq body) {
        long uid = support.userId(request);

        com.rag.proto.comment.CreateCommentResp rpcResp;
        try {
            rpcResp = domainService.createComment(
                    com.rag.proto.comment.CreateCommentReq.newBuilder()
                            .setUserId(uid)
                            .setTargetType(body.targetType())
                            .setTargetId(body.targetId())
                            .setRootId(body.rootId())
                            .setParentId(body.parentId())
                            .setContent(body.content())
                            .setMeta(body.meta())
                            .build());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }

        return new CreateCommentResp(
                rpcResp.getId(),
                rpcResp.getCreatedAt(),
                rpcResp.getSubjectCount());
    }

    // ==================== 2. 删评论 ====================

    /**
     * 删评论 —— 对应 B 侧 {@code DeleteCommentService#delete}。
     *
     * <p><b>⚠️ 响应里的 {@code success} 是字面量 {@code true}，不是 rpc 的返回值</b>：
     * Go {@code deletecommentlogic.go:57-60} 写的是
     * <pre>
     * return &amp;types.DeleteCommentResp{
     *     Success:           true,                       // ← 字面量
     *     SubjectTotalCount: rpcResp.SubjectTotalCount,
     * }, nil
     * </pre>
     * 而 {@code pb.DeleteCommentResp} 里<b>本来就有</b> {@code bool success = 1}，
     * Go 却<b>没有</b>读取它。若 Java 侧写成 {@code rpcResp.getSuccess()}，
     * 在 rpc 侧返回 {@code success=false} 的形态下两侧就会分叉。
     * 按 AGENTS.md「不擅自修复 B 侧行为」，原样保留。
     *
     * <p><b>授权不在本层</b>：proto 注释写明 {@code user_id} 是
     * 「用于检测是否为本人或 up 主操作防止出现安全问题」，该判定在 comment-rpc。
     * api 只负责把 token 里的身份如实传下去。
     */
    public DeleteCommentResp delete(HttpServletRequest request, DeleteCommentReq body) {
        long uid = support.userId(request);

        com.rag.proto.comment.DeleteCommentResp rpcResp;
        try {
            rpcResp = domainService.deleteComment(
                    com.rag.proto.comment.DeleteCommentReq.newBuilder()
                            .setUserId(uid)
                            .setCommentId(body.commentId())
                            .setTargetType(body.targetType())
                            .setTargetId(body.targetId())
                            .build());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }

        // ⚠️ 字面量 true，不是 rpcResp.getSuccess()（理由见方法 javadoc）
        return new DeleteCommentResp(true, rpcResp.getSubjectTotalCount());
    }

    // ==================== 3. 点赞 / 点踩 ====================

    /**
     * 点赞 / 点踩 —— 对应 B 侧 {@code LikeCommentService#like}。
     *
     * <p><b>⚠️ {@code action_type} 用 {@code setActionType(int)} 而非枚举入口</b>：
     * proto 里该字段声明为 <b>{@code int32 action_type = 5}</b>（<b>不是</b>枚举），
     * 所以 Java 生成代码没有 {@code setActionTypeValue} 这一对之外的枚举入口，
     * 直接 {@code setActionType(4)} 即为原样透传，proto3 开放语义天然成立。
     * 0=点赞 / 1=取消点赞 / 2=点踩 / 3=取消点踩（见 proto 注释）；
     * 范围校验归 comment-rpc，本层不预判。
     */
    public LikeCommentResp like(HttpServletRequest request, LikeCommentReq body) {
        long uid = support.userId(request);

        com.rag.proto.comment.LikeCommentResp rpcResp;
        try {
            rpcResp = domainService.likeComment(
                    com.rag.proto.comment.LikeCommentReq.newBuilder()
                            .setUserId(uid)
                            .setTargetType(body.targetType())
                            .setTargetId(body.targetId())
                            .setCommentId(body.commentId())
                            .setActionType(body.actionType())
                            .build());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }

        return new LikeCommentResp(rpcResp.getSuccess(), rpcResp.getLikeCount());
    }

    // ==================== 4. 拉评论列表 ====================

    /**
     * 拉评论列表 —— 对应 B 侧 {@code GetCommentService#get}。
     *
     * <p><b>⚠️ 本方法是 7 个用例里唯一一个行为形状不同的，必须逐条照 B 侧走</b>，
     * 不能照其余 6 个的模板「顺手统一」。四处差异：
     *
     * <h3>差异 1：不解析身份</h3>
     * <p>B 侧全篇<b>没有</b> {@code extractUserID}，proto 请求里也没有 {@code user_id}。
     * ⇒ 本方法<b>不</b>调 {@code support.userId(request)}。
     * 这意味着：token 里缺 {@code userId} claim 时，本端点<b>不会</b>返回 1006，
     * 而其余 6 个端点会。这是 B 侧既有的不对称，原样保留。
     *
     * <h3>差异 2：入参校验抛的是<b>裸 gRPC status error</b></h3>
     * <pre>
     * if strings.TrimSpace(req.TargetType) == "" {
     *     return nil, status.Error(codes.InvalidArgument, "target_type is required")
     * }
     * </pre>
     * 该 error <b>不是</b> {@code *errmsg.CodeError} ⇒ Go 的
     * {@code SetErrorHandler} 里 {@code errors.As} 判否 ⇒ 落进兜底分支
     * ⇒ 客户端实际收到的是 <b>HTTP 200 + {code:5001, msg:"系统内部错误"}</b>，
     * <b>而不是</b> {@code "target_type is required"}，也不是 3（InvalidArgument）。
     * <p>⇒ Java 侧<b>不能</b>把它转成 {@code BusinessException(3, "target_type is required")}：
     * 那会让客户端看到一个 B 侧从未输出过的码与文案。
     * 这里原样抛 {@code StatusRuntimeException}，由
     * {@code CommentApiExceptionHandler#handleRpcStatus} 兜底成 5001 —— 与 B 侧同路径。
     *
     * <h3>差异 3：RPC 失败<b>不</b>做 code/msg 转换（本方法<b>没有 try/catch</b>）</h3>
     * <p>B 侧 {@code getcommentlogic.go:72-75} 是<b>裸 {@code return nil, err}</b>，
     * 没有 {@code status.FromError} + {@code NewErrCodeMsg} 那三行。
     * ⇒ 下游返回 {@code NotFound} 时，本端点给客户端的是 <b>5001</b>，
     * 而其余 6 个端点会给 <b>5</b>。两个端点的错误契约就是不一样，
     * 照抄「统一模板」会破坏它。
     * <p>⇒ C 侧对应地<b>不写 catch</b>：领域异常直接逃逸 ⇒
     * {@code CommentApiExceptionHandler#handleAny} 兜底成 5001 ⇒ 与 B 侧
     * {@code handleGrpcStatus} 的响应<b>逐字相同</b>（200 + {code:5001, msg:"系统内部错误"}）。
     * <p>⚠️ <b>此处若「顺手加上 catch + rpcError」就会造出 B 侧永不产生的码</b>
     * （例如 1012 / 1013 / 5003 会被原样透出）—— 那是行为漂移，不是对齐。
     *
     * <h3>差异 4：五处入参兜底</h3>
     * <pre>
     * if req.SortType != 0 &amp;&amp; req.SortType != 1 { req.SortType = 0 }   // 只认 0=热度 / 1=时间
     * if req.Page     &lt;= 0 { req.Page     = 1 }
     * if req.PageSize &lt;= 0 { req.PageSize = 20 }
     * if req.PageSize &gt; 50 { req.PageSize = 50 }
     * if req.RootId   &lt;  0 { req.RootId   = 0 }                        // 负数无意义 ⇒ 查根评论
     * </pre>
     * 这是<b>业务层</b>兜底，发生在解析层（「字段必填」）之后，两道关卡互不替代。
     *
     * <p><b>⚠️ 校验只判空、<b>不</b>回写 trim 结果</b>：
     * B 侧只用 {@code TrimSpace(...)} 做判空，<b>没有</b> {@code req.TargetType = ...}，
     * 所以带空格的 target_type 会<b>原样</b>发给下游。若 Java 侧顺手 {@code strip()} 后回写，
     * 下游收到的值就变了 —— 可能命中与原值不同的评论区。
     */
    public GetCommentResp get(GetCommentReq body) {
        // ── 差异 2：入参校验，裸 gRPC status error ──
        // ⚠️ 只判空不回写：B 侧没有 trim 后的赋值。
        if (isBlank(body.targetType())) {
            throw io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("target_type is required")
                    .asRuntimeException();
        }
        if (isBlank(body.targetId())) {
            throw io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("target_id is required")
                    .asRuntimeException();
        }

        // ── 差异 4：五处入参兜底 ──
        long sortType = body.sortType() == 0L || body.sortType() == 1L ? body.sortType() : 0L;
        long page = body.page() <= 0L ? 1L : body.page();
        long pageSize = body.pageSize() <= 0L
                ? DEFAULT_PAGE_SIZE
                : (body.pageSize() > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : body.pageSize());
        long rootId = body.rootId() < 0L ? 0L : body.rootId();

        // ── 差异 3：失败不转换，裸抛（无 try/catch）──
        com.rag.proto.comment.GetCommentResp rpcResp = domainService.getComment(
                com.rag.proto.comment.GetCommentReq.newBuilder()
                        .setTargetType(body.targetType())
                        .setTargetId(body.targetId())
                        .setSortType(sortType)
                        .setRootId(rootId)
                        .setPage(page)
                        .setPageSize(pageSize)
                        .build());

        // B 侧: resp = &types.GetCommentResp{ Comment: make([]types.CommentItem, 0) }
        //     ⇒ comment 恒为 []，即使下游一条评论都没返回
        List<CommentItem> items = new ArrayList<>();
        for (com.rag.proto.comment.CommentItem item : rpcResp.getCommentList()) {
            items.add(toCommentItem(item));
        }

        // B 侧: 只有 rpcResp.GetSubject() != nil 才赋值；SubjectInfo 是**值类型**，
        //     所以没返回时输出的是一个全零对象而不是 null / 省略。
        //     proto3 的 message 字段无「null」形态 ⇒ 用 hasSubject() 表达 B 侧的判空。
        SubjectInfo subject = rpcResp.hasSubject()
                ? new SubjectInfo(
                        rpcResp.getSubject().getTargetType(),
                        rpcResp.getSubject().getTargetId(),
                        rpcResp.getSubject().getTotalCount(),
                        rpcResp.getSubject().getRootCount(),
                        rpcResp.getSubject().getStateValue(),
                        rpcResp.getSubject().getAttribute(),
                        // ⚠️⚠️ 恒为 0：B 侧 getcommentlogic.go:91 写的是字面量 OwnerId: 0，
                        //         **没有** 读 rpcResp.GetSubject().GetOwnerId()。
                        //         proto 里明明有 int64 owner_id = 7，B 侧却丢弃了它。
                        //         这是客户端可观察的行为（拿到的 owner_id 一直是 0），
                        //         按 AGENTS.md 不擅自修复，原样保留。
                        0L)
                : SubjectInfo.zero();

        return new GetCommentResp(items, subject);
    }

    /**
     * 复刻 B 侧 {@code convertPbCommentItemToAPI}（{@code getcommentlogic.go:106-135}）。
     *
     * <p><b>⚠️ state 用 {@code getStateValue()} 取数值</b>：B 侧写的是
     * {@code State: int32(in.GetState())} —— 把 proto 枚举<b>降成数值</b>。
     * 用 {@code getState()} 再 {@code getNumber()} 结果等价，
     * 但 {@code *Value()} 直接就是 wire 上那个 int，且对未识别枚举值不会 NPE。
     *
     * <p><b>⚠️ children 递归且每层都先建空切片</b>（B 侧 {@code Children: make(..., 0)}），
     * 所以任意深度的叶子节点都输出 {@code "children":[]}，绝不是 {@code null}。
     */
    private static CommentItem toCommentItem(com.rag.proto.comment.CommentItem in) {
        List<CommentItem> children = new ArrayList<>();
        for (com.rag.proto.comment.CommentItem child : in.getChildrenList()) {
            children.add(toCommentItem(child));
        }
        return new CommentItem(
                in.getId(),
                in.getUserId(),
                in.getContent(),
                in.getRootId(),
                in.getParentId(),
                in.getLikeCount(),
                in.getDislikeCount(),
                in.getReplyCount(),
                in.getAttribute(),
                in.getStateValue(),
                in.getCreatedAt(),
                in.getMeta(),
                children);
    }

    // ==================== 5. 管理评论（置顶 / 精选） ====================

    /**
     * 评论管理（置顶 / 精选）—— 对应 B 侧 {@code ManageCommentService#manage}。
     *
     * <p><b>⚠️⚠️ {@code action_type} 必须用 {@code setActionTypeValue(int)}</b>。
     * 这是本模块最容易被「自然写成错」的一处：
     * <ul>
     *   <li>B 侧 {@code managecommentlogic.go:46} 写的是
     *       {@code ActionType: pb.ManageType(req.ActionType)} ——
     *       <b>纯类型转换</b>，没有任何有效性检查，所以 proto3 开放枚举下
     *       <b>未定义值（如 99）也能原样发到下游</b>。</li>
     *   <li>Java 若写成 {@code setActionType(ManageType.forNumber(v))}，
     *       {@code forNumber(99)} 返回 {@code null} ⇒ 字段被当成缺省，
     *       线上实际发出 <b>0（UNKNOWN）</b> ⇒ <b>静默改写客户端意图</b>，
     *       而且不报任何错。</li>
     * </ul>
     * ⇒ 用 protobuf 为枚举字段生成的「原始数值」setter，它不做有效性过滤。
     * {@code report} / {@code updateState} 同理。
     */
    public ManageCommentResp manage(HttpServletRequest request, ManageCommentReq body) {
        long uid = support.userId(request);

        com.rag.proto.comment.ManageCommentResp rpcResp;
        try {
            rpcResp = domainService.manageComment(
                    com.rag.proto.comment.ManageCommentReq.newBuilder()
                            .setUserId(uid)
                            .setCommentId(body.commentId())
                            .setTargetType(body.targetType())
                            .setTargetId(body.targetId())
                            // ⚠️ 必须是 *Value：理由见方法 javadoc
                            .setActionTypeValue(body.actionType())
                            .build());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }

        return new ManageCommentResp(rpcResp.getSuccess());
    }

    // ==================== 6. 举报评论 ====================

    /**
     * 举报评论 —— 对应 B 侧 {@code ReportCommentService#report}。
     *
     * <p><b>⚠️ {@code reason} 必须用 {@code setReasonValue(int)}</b>：proto 里
     * {@code ReportReason reason = 5} 是<b>枚举</b>，而 B 侧
     * {@code reportcommentlogic.go:46} 是 {@code Reason: pb.ReportReason(req.Reason)} 的
     * 纯转换，未定义值同样原样透传。用 {@code forNumber} 会把未知值（如 98）静默变成 0。
     * 推理见 {@link #manage} 的 javadoc。
     */
    public ReportCommentResp report(HttpServletRequest request, ReportCommentReq body) {
        long uid = support.userId(request);

        com.rag.proto.comment.ReportCommentResp rpcResp;
        try {
            rpcResp = domainService.reportComment(
                    com.rag.proto.comment.ReportCommentReq.newBuilder()
                            .setUserId(uid)
                            .setCommentId(body.commentId())
                            .setTargetType(body.targetType())
                            .setTargetId(body.targetId())
                            // ⚠️ 必须是 *Value：理由见方法 javadoc
                            .setReasonValue(body.reason())
                            .setDetail(body.detail())
                            .build());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }

        return new ReportCommentResp(rpcResp.getSuccess());
    }

    // ==================== 7. 改评论区状态 ====================

    /**
     * 改评论区状态 —— 对应 B 侧 {@code UpdateSubjectStateService#updateState}。
     *
     * <p><b>⚠️ {@code state} 必须用 {@code setStateValue(int)}</b>：proto 里
     * {@code SubjectState state = 4} 是枚举，B 侧
     * {@code updatesubjectstatelogic.go:44} 为纯转换。推理见 {@link #manage}。
     *
     * <p><b>授权不在本层</b>：proto 注释写明「只有 up 主或管理员才有权关闭评论区」，
     * 该判定在 comment-rpc（其 {@code ErrorUserNoRight}=1008 是本域高频码）。
     * api 层<b>不</b>预判，否则会把 rpc 的码换掉。
     */
    public UpdateSubjectStateResp updateState(HttpServletRequest request, UpdateSubjectStateReq body) {
        long uid = support.userId(request);

        com.rag.proto.comment.UpdateSubjectStateResp rpcResp;
        try {
            rpcResp = domainService.updateSubjectState(
                    com.rag.proto.comment.UpdateSubjectStateReq.newBuilder()
                            .setUserId(uid)
                            .setTargetType(body.targetType())
                            .setTargetId(body.targetId())
                            // ⚠️ 必须是 *Value：理由见方法 javadoc
                            .setStateValue(body.state())
                            .build());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }

        return new UpdateSubjectStateResp(rpcResp.getSuccess());
    }

    // ==================== 工具方法 ====================

    /**
     * B 侧 {@code strings.TrimSpace(s) == ""} 的等价判定。
     *
     * <p>用 {@link String#isBlank()}（底层 {@code strip()}，按 Unicode 空白判定），
     * 与 B 侧的 {@code unicode.IsSpace} 覆盖面一致。
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
