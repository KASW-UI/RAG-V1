package com.rag.follow.service;

import com.rag.common.exception.BusinessException;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.ErrMsg;
import com.rag.follow.dto.list.FollowUserListResp;
import com.rag.follow.dto.recommend.RecommendListResp;
import com.rag.follow.dto.recommend.RecommendUser;
import com.rag.follow.interceptor.FollowJwtAuthInterceptor;
import com.rag.follow.metrics.FollowApiMetrics;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.UserListResp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>follow 域 HTTP 层跨 8 个端点共享的行为</b> —— B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/service/FollowApiSupport.java:56-317}
 * 的等价物（逐条照抄 + L4 落点变化）。
 *
 * <p><b>B 出处逐条对应</b>（B 侧 Java 文件的行号，均已读）：
 * <table border="1">
 *   <caption>B 侧 FollowApiSupport → 本类</caption>
 *   <tr><th>B 侧</th><th>本类</th></tr>
 *   <tr><td>{@code :104-143} 取 claim 的 {@code extractUserID}</td><td>{@link #userId(HttpServletRequest)}</td></tr>
 *   <tr><td>{@code :162-167} {@code resolveUserID}</td><td>{@link #resolveUserId(long, Long)}</td></tr>
 *   <tr><td>{@code :190-197} 归属校验</td><td>{@link #requireSelfScope(long, Long)}</td></tr>
 *   <tr><td>{@code :237-241} rpc 返回码判定</td><td>{@link #requireRpcSuccess(int, String)}</td></tr>
 *   <tr><td>{@code :260-274} 用户 id 列表转换</td><td>{@link #toUserListResp(UserListResp)}</td></tr>
 *   <tr><td>{@code :295-306} 推荐列表转换</td><td>{@link #toRecommendListResp(RecommendResp)}</td></tr>
 *   <tr><td>{@code :314-317} {@code NewErrCode(ErrorUnauthorized)}</td><td>{@link #unauthorized()}</td></tr>
 * </table>
 *
 * <h2>⚠️ L4 落点变化（D2 · 域内调用直连）</h2>
 * <p>B 侧 api 进程经 gRPC 调 rpc 进程；C 侧按 <b>D1 + D7</b> 把 api + rpc 合并成一个进程
 * ⇒ <b>域内调用直连</b> ⇒ <b>不</b>再有 {@code api/client/FollowRpcClient}。
 * ⇒ ⇒ 但 <b>{@link #rpcError(Throwable)} 必须保留</b>（B 侧 {@code FollowApiSupport.java:216-219}）：
 * <p><b>⚠️ 直连消掉的是「往返」，不是「错误载体」</b>：
 * C 侧领域层（{@link com.rag.follow.domain.service.FollowDomainService}）<b>仍然</b>用
 * {@link com.rag.follow.service.FollowRpcSupport#rpcError} 抛 {@code StatusRuntimeException}
 * —— 与 B 侧 follow-rpc 的领域层<b>同一套错误构造入口</b>（B 侧 {@code FollowRpcSupport.java:89-92}）。
 * 而本域的 wire 形态是「<b>业务码藏在 description 文案里</b>」
 * （{@link com.rag.follow.contract.GrpcErrorMapper#grpcError} 用 {@code ErrMsg.getMsg(bizCode)}
 * 当 description）⇒ <b>业务码只能靠文案反查还原</b>
 * （{@link com.rag.follow.contract.GrpcErrorMapper#bizCodeFromError}）。
 * <p>⇒ <b>若不在 api 层反查，5 类业务码会全部塌成 5001</b>：
 * 5201 自关系 / 5202 已拉黑 / 5101 用户不存在 / 5007 DB 写失败 / 5008 DB 读失败
 * —— 因为它们对应的 gRPC 标准码（INVALID_ARGUMENT / PERMISSION_DENIED / NOT_FOUND / INTERNAL）
 * 在兜底映射表里只能还原出<b>粗粒度</b>的码（5003 / 5005 / 5002 / 5001）。
 * <p>⇒ <b>反查位置与 B 侧完全对称</b>：B 侧在 api 层的<b>每个 logic 内</b>
 * {@code catch (StatusRuntimeException e) { throw support.rpcError(e); }}，
 * C 侧同样在 api 层 service 的<b>领域层调用点</b>做这件事（见
 * {@link FollowRelationService} / {@link FollowQueryService}）
 * ⇒ 「正常路径走文案反查 / 未捕获时落到 handler 兜底」这<b>两条路径的分工与 B 侧逐条一致</b>。
 *
 * <h2>⚠️ 本类的「身份解析」为什么带 route</h2>
 * <p>B 侧每个 logic 的第一段是
 * {@code userID, err := extractUserID(ctx); if err != nil { metrics.ObserveReject(route, "user_id_missing"); return nil, err }}。
 * ⇒ 「取 claim」与「记 reject 埋点」在 B 侧是<b>同一段</b>的两半 ⇒ C 侧用
 * {@link #requireIdentity(String, HttpServletRequest)} 把两半收在一起；
 * {@link #userId(HttpServletRequest)} 是其中<b>只做取值</b>的那一半（不带埋点）。
 */
@Component
public class FollowApiSupport {

    /** B 侧 {@code FollowApiSupport.java:60}：{@code Success = 200}。 */
    public static final int SUCCESS = ErrCodes.SUCCESS;

    /** B 侧 {@code logic/follow/helper.go} 的 {@code ObserveReject(route, "user_id_missing")} 字面量。 */
    private static final String REJECT_USER_ID_MISSING = "user_id_missing";

    /** B 侧 {@code logic/follow/helper.go} 的 {@code ObserveReject(route, "forbidden_scope")} 字面量。 */
    private static final String REJECT_FORBIDDEN_SCOPE = "forbidden_scope";

    private final FollowApiMetrics metrics;

    public FollowApiSupport(FollowApiMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:104-143} 的 {@code extractUserID}：
     * <pre>
     * func extractUserID(ctx context.Context) (int64, error) {
     *     raw := ctx.Value("userId")
     *     if raw == nil { return 0, fmt.Errorf("ctx userId is nil") }
     *     switch value := raw.(type) {
     *     case json.Number: return value.Int64()
     *     case string:      return strconv.ParseInt(value, 10, 64)
     *     case int64:       return value, nil
     *     case int:         return int64(value), nil
     *     case float64:     return int64(value), nil
     *     default:          return 0, fmt.Errorf("ctx userId has unexpected type %T", raw)
     *     }
     * }
     * </pre>
     *
     * <h3>⚠️ 本域比 message 域<b>宽</b>：字符串 claim 是被接受的</h3>
     * <p>B 侧的类型分支里有 {@code case string: strconv.ParseInt(...)} ⇒
     * {@code {"userId":"123"}} 这种<b>字符串</b> claim 在 B 侧是<b>合法</b>的，返回 123。
     * （message 域只接受数字 claim ⇒ 两域行为不同，<b>勿「顺手统一」</b>。）
     *
     * <h3>三种失败都归到 <b>5004</b>（而不是 401）</h3>
     * <ol>
     *   <li><b>claim 缺席</b>：鉴权层已验过签名，走到这里说明 token 有效；
     *       B 侧返回 {@code ErrorUnauthorized} ⇒ <b>HTTP 200 + {code:5004}</b>。</li>
     *   <li><b>类型不匹配</b>（bool / 数组 / 对象 claim）⇒ B 侧的 {@code default} 分支。</li>
     *   <li><b>数值不可解析</b>：{@code json.Number("1.5").Int64()} 内部是
     *       {@code strconv.ParseInt("1.5",10,64)} ⇒ 失败。jjwt 对同样的 JSON 会给
     *       {@code Double}，本方法同样判失败。
     *       ⚠️ <b>不能像 Jackson 默认那样静默截断</b> —— 那会把 B 侧判 5004 的输入变成合法 uid，
     *       是实打实的身份伪造风险。</li>
     * </ol>
     *
     * <p>注意本方法<b>不</b>拒绝 {@code uid <= 0}：B 侧同样不拒绝，
     * {@code uid=0} 会原样透传给领域层，由领域层的存在性校验判 5101。
     * api 层提前拒会把业务码换成 5004。
     *
     * @param request 当前请求（claim 由 {@link FollowJwtAuthInterceptor} 写入 request attribute）
     * @return 当前登录用户 id
     * @throws BusinessException 任一失败路径，code = 5004
     */
    public long userId(HttpServletRequest request) {
        Object claim = request.getAttribute(FollowJwtAuthInterceptor.CLAIM_ATTRIBUTE);
        if (claim == null) {
            throw unauthorized();
        }
        if (claim instanceof Byte b) {
            return b.longValue();
        }
        if (claim instanceof Short s) {
            return s.longValue();
        }
        if (claim instanceof Integer i) {
            return i.longValue();
        }
        if (claim instanceof Long l) {
            return l;
        }
        if (claim instanceof BigInteger bi) {
            // Go: strconv.ParseInt 对超出 int64 的字面量报 range error
            if (bi.bitLength() >= 64) {
                throw unauthorized();
            }
            return bi.longValue();
        }
        if (claim instanceof String s) {
            // Go: strconv.ParseInt(s, 10, 64)
            //   拒绝 "" / " 12" / "1.5" / "1e2" / 超 long 范围；接受 "+123"（Java parseLong 同样接受）
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw unauthorized();
            }
        }
        if (claim instanceof BigDecimal || claim instanceof Double || claim instanceof Float) {
            // Go: JSON 浮点字面量经 WithJSONNumber 保留原样字符串，Int64() 的 ParseInt 一律失败
            throw unauthorized();
        }
        // bool / List / Map / 其它类型 ⇒ Go 的 default 分支
        throw unauthorized();
    }

    /**
     * 身份解析 ＋ <b>reject 埋点</b> —— 对应 B 侧每个 logic 的第一段：
     * <pre>
     * userID, err := extractUserID(l.ctx)
     * if err != nil { metrics.ObserveReject(route, "user_id_missing"); return nil, err }
     * </pre>
     *
     * <p>⚠️ <b>顺序与 B 侧一致</b>：<b>先</b>记 reject，<b>后</b>返回 error。
     *
     * @param route   当前端点的 route 字面量（8 个之一）
     * @param request 当前请求
     * @return 当前登录用户 id
     * @throws BusinessException 取值失败时 code = 5004（HTTP 恒为 200）
     */
    public long requireIdentity(String route, HttpServletRequest request) {
        try {
            return userId(request);
        } catch (BusinessException e) {
            metrics.observeReject(route, REJECT_USER_ID_MISSING);
            throw e;
        }
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:162-167}：
     * <pre>
     * func resolveUserID(currentUserID, requestedUserID int64) int64 {
     *     if requestedUserID &gt; 0 { return requestedUserID }
     *     return currentUserID
     * }
     * </pre>
     *
     * <p><b>⚠️ 这不是「权限校验」，只是「取值归一化」</b>：它<b>不</b>判断请求者是否有权
     * 查目标用户。是否比对由调用方决定，而 4 个 list 端点里<b>只有 2 个</b>比对
     * （{@code /follow/v1/block/list} 与 {@code /follow/v1/recommendations}）——
     * 这是 B 侧各自 logic 里的分支，不在本方法内。详见 {@link #requireSelfScope(long, Long)}。
     *
     * @param currentUserId  当前登录用户 id（来自 JWT claim）
     * @param requestedUserId 请求里的 {@code user_id}；{@code null} / 非正数都归一化成当前用户
     */
    public long resolveUserId(long currentUserId, Long requestedUserId) {
        if (requestedUserId != null && requestedUserId > 0) {
            return requestedUserId;
        }
        return currentUserId;
    }

    /**
     * 归属校验 —— 对应 B 侧两处<b>重复出现</b>的判定（各 logic 各写一份，字面量相同）：
     * <pre>
     * queryUserID := resolveUserID(currentUserID, req.UserId)
     * if queryUserID != currentUserID {
     *     metrics.ObserveReject(route, "forbidden_scope")
     *     return nil, followcommon.NewErrCode(followcommon.ErrorForbidden)
     * }
     * </pre>
     *
     * <p><b>⚠️ 它的存在本身就是一条业务语义</b>：B 侧的 4 个 list 端点里
     * <b>只有 {@code /follow/v1/block/list} 与 {@code /follow/v1/recommendations} 有这段</b>，
     * 而 {@code /follow/v1/follow/list} / {@code /follow/v1/follower/list} <b>没有</b> ——
     * 即<b>关注列表与粉丝列表是公开可查的，黑名单与推荐是私有的</b>。
     * <p>这个不对称<b>看起来像遗漏、实际是既定行为</b>，必须原样保留：
     * 若给 4 个端点统一加上校验，就是把「可查他人关注列表」变成 5005，
     * 是新增的拒绝行为（会打破已有客户端）。
     *
     * <p>⚠️ <b>本重载<b>不</b>记 {@code forbidden_scope} 埋点</b>（它没有 route）；
     * 真正被 2 个私有端点调用的是 {@link #requireSelfScope(String, long, Long)}（带 route）。
     *
     * @return 校验通过后的目标用户 id
     * @throws BusinessException code = 5005（HTTP 恒为 200）
     */
    public long requireSelfScope(long currentUserId, Long requestedUserId) {
        return checkSelfScope(currentUserId, requestedUserId, null);
    }

    /**
     * 归属校验 ＋ <b>route 级 reject 埋点</b> —— B 侧 {@code ObserveReject(route, "forbidden_scope")}
     * 那一行的落点。
     *
     * <p>⚠️ 与 {@link #requireSelfScope(long, Long)} 的<b>唯一</b>差异就是多记一次埋点
     * ⇒ 判定逻辑<b>共用同一份</b>（{@link #checkSelfScope}）⇒ 两侧不可能分叉。
     *
     * @param route           当前端点的 route 字面量
     * @param currentUserId   当前登录用户 id
     * @param requestedUserId 请求里的 {@code user_id}
     * @return 校验通过后的目标用户 id
     * @throws BusinessException code = 5005（HTTP 恒为 200）
     */
    public long requireSelfScope(String route, long currentUserId, Long requestedUserId) {
        return checkSelfScope(currentUserId, requestedUserId, route);
    }

    /**
     * 归属判定的<b>唯一实现点</b> —— 两个重载都走它 ⇒ 规则不可能分叉。
     *
     * @param route {@code null} 表示不记埋点（无 route 的重载）
     */
    private long checkSelfScope(long currentUserId, Long requestedUserId, String route) {
        long queryUserId = resolveUserId(currentUserId, requestedUserId);
        if (queryUserId != currentUserId) {
            if (route != null) {
                metrics.observeReject(route, REJECT_FORBIDDEN_SCOPE);
            }
            int code = ErrCodes.ERROR_FORBIDDEN;
            throw new BusinessException(code, ErrMsg.getMsg(code));
        }
        return queryUserId;
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:216-219}：
     * <pre>
     * public BusinessException rpcError(Throwable err) {
     *     int code = FollowGrpcError.bizCodeFromError(err);
     *     return new BusinessException(code, FollowErrorMessages.getMsg(code));
     * }
     * </pre>
     *
     * <p><b>⚠️ msg 从本地码表取，不从 description 取</b>：
     * B 侧 {@code rpcError} 用的是 {@code FollowErrorMessages.getMsg(code)}（查表），
     * 而 {@code requireRpcSuccess} 用的是<b>回传的</b> msg（透传）—— 两者刻意不同：
     * 前者「从错误反查出码，再查表拿文案」，后者「码和文案都来自下游」。
     *
     * <p><b>⚠️ 反查顺序即语义（不可调换）</b>：
     * {@link com.rag.follow.contract.GrpcErrorMapper#bizCodeFromError} 先按
     * description <b>文案反查</b>，未命中才按 gRPC 标准码兜底映射
     * ⇒ <b>文案反查是本域业务码唯一的还原途径</b>。
     *
     * <p><b>⚠️ 为什么 D2 直连之后仍然需要它</b>：见类注释的「L4 落点变化」段 ——
     * 直连消掉的是「跨进程往返」，不是「错误载体」；领域层仍抛 {@code StatusRuntimeException}，
     * 业务码仍在 description 文案里 ⇒ 不反查就会把 5 类业务码塌成 5001。
     *
     * @param err 领域层抛出的异常（通常是 {@code StatusRuntimeException}）
     * @return 可直接抛出的 {@link BusinessException}（code = 反查得到的业务码，msg = 查表文案）
     */
    public BusinessException rpcError(Throwable err) {
        int code = com.rag.follow.contract.GrpcErrorMapper.bizCodeFromError(err);
        return new BusinessException(code, ErrMsg.getMsg(code));
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:237-241}：
     * <pre>
     * if rpcResp.Code != int32(followcommon.Success) {
     *     return nil, followcommon.NewErrCodeMsg(int(rpcResp.Code), rpcResp.Msg)
     * }
     * </pre>
     *
     * <p><b>⚠️ 用 rpc 回传的 msg，不查本地码表</b>：B 侧这里是 {@code NewErrCodeMsg} 而不是
     * {@code NewErrCode} ⇒ msg 就是下游写进响应体的那个字符串。
     * 改成本地查表会在「下游文案与 api 侧码表不一致」时产生不同的 body。
     *
     * @param code 响应码；{@code 200} = 成功
     * @param msg  响应文案（原样透传）
     * @throws BusinessException {@code code != 200} 时，code = 回传值，msg = 回传值
     */
    public void requireRpcSuccess(int code, String msg) {
        if (code != SUCCESS) {
            throw new BusinessException(code, msg);
        }
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:260-274} 的响应 → {@code FollowUserListResp} 透传：
     * {@code return &types.FollowUserListResp{UserIds: rpcResp.UserIds}, nil}。
     *
     * <p>出参是 <b>JSON 数字</b>（B 侧 tag {@code json:"user_ids"} 无 {@code ,string}）。
     *
     * <h3>⚠️ 空结果必须输出 {@code null} 而不是 {@code []}</h3>
     * <p>B 侧 {@code rpcResp.UserIds} 是 <b>protobuf 反序列化后的切片</b>：
     * proto3 的 repeated 字段在为空时整个字段不上 wire ⇒ 反序列化得到 <b>nil</b> ⇒
     * {@code encoding/json}（无 {@code omitempty}）输出 {@code "user_ids":null}。
     * <p>⚠️ 「下游是非 nil 空切片 ⇒ 输出 []」是<b>错</b>的：那是进程内的值，
     * 中间隔着一次 protobuf 编解码，wire 上字段已经消失。
     * ⇒ 这里在返回空列表时显式置 {@code null}，使 body 与 B 侧逐字节相同。
     * <p>对照：{@link #toRecommendListResp(RecommendResp)} <b>不需要</b>这个处理 ——
     * B 侧的推荐列表是在 api 进程内 {@code make(...)} 出来的，恒非 nil ⇒ 输出 {@code []}。
     *
     * @param resp 领域层返回的响应
     * @return 出参 DTO
     * @throws BusinessException 响应码非 200 时（等价于 B 侧的 {@code NewErrCodeMsg} 分支）
     */
    public FollowUserListResp toUserListResp(UserListResp resp) {
        requireRpcSuccess(resp.getCode(), resp.getMsg());
        FollowUserListResp out = new FollowUserListResp(null);
        int count = resp.getUserIdsCount();
        if (count == 0) {
            // Go 侧此刻是 nil 切片 ⇒ JSON null。给空 list 会输出 "user_ids":[]，与 Go 不同。
            return out;
        }
        List<Long> ids = new ArrayList<>(count);
        for (Long id : resp.getUserIdsList()) {
            ids.add(id);
        }
        return new FollowUserListResp(ids);
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:295-306}：
     * <pre>
     * users := make([]types.RecommendUser, 0, len(rpcResp.Users))
     * for _, user := range rpcResp.Users {
     *     if user == nil { continue }
     *     users = append(users, types.RecommendUser{TargetId: user.TargetId, MutualScore: user.MutualScore})
     * }
     * return &types.RecommendListResp{Users: users}, nil
     * </pre>
     *
     * <p><b>⚠️ {@code if user == nil { continue }} 在 Java 侧不需要</b>：
     * proto3 的 {@code repeated} 消息字段在 grpc-java 里<b>永远不会出现 null 元素</b>，
     * B 侧那个判空是防御性代码。保留它等于引入一条永不执行的分支。
     *
     * <p><b>⚠️ {@code target_id} 在出参里是字符串</b>（B 侧 tag 带 {@code ,string}），
     * 转换只发生在这一处（见 {@code RecommendUser} 的注释）。
     *
     * @param resp 领域层返回的响应
     * @return 出参 DTO（{@code users} 恒非 null）
     * @throws BusinessException 响应码非 200 时（等价于 B 侧的 {@code NewErrCodeMsg} 分支）
     */
    public RecommendListResp toRecommendListResp(RecommendResp resp) {
        requireRpcSuccess(resp.getCode(), resp.getMsg());
        List<RecommendUser> users = new ArrayList<>(resp.getUsersCount());
        for (RecommendResp.RecommendUser user : resp.getUsersList()) {
            RecommendUser item = new RecommendUser(String.valueOf(user.getTargetId()), user.getMutualScore());
            users.add(item);
        }
        return new RecommendListResp(users);
    }

    /**
     * 对应 B 侧 {@code FollowApiSupport.java:314-317} 的 {@code NewErrCode(ErrorUnauthorized)}
     * —— 8 个 logic 的第一段出口。
     *
     * <p><b>HTTP 恒为 200</b>：B 侧注册了 {@code httpx.SetErrorHandler}，
     * 业务错误一律 {@code http.StatusOK} + envelope（见 {@code FollowApiExceptionHandler}）。
     */
    public BusinessException unauthorized() {
        int code = ErrCodes.ERROR_UNAUTHORIZED;
        return new BusinessException(code, ErrMsg.getMsg(code));
    }
}
