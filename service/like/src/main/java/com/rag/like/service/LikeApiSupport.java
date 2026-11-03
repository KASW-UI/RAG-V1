package com.rag.like.service;

import com.rag.like.contract.CodeError;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.GrpcErrFactory;
import com.rag.like.interceptor.LikeJwtAuthInterceptor;
import com.rag.like.metrics.LikeApiMetrics;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>api 侧的共享支撑</b> —— 对应 B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/service/LikeApiSupport.java}
 * ＋ 同包 {@code GoStyleTargetIds.java}（GAP-4：B 侧 2 个类 ⇒ C 侧 1 个类）。
 *
 * <p><b>抄写基准</b>：B 侧两文件（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>把 B 侧 {@code GoStyleTargetIds} 并入本类</b>
 *       （映射表第 115 行：「{@code like-api/.../service/GoStyleTargetIds.java} →
 *       {@code service/LikeApiSupport.java}（<b>无独立文件</b>）」）。
 *       ⇒ 本类同时承载「uid 提取 / reject 指标 / gRPC 错误映射 / target_ids 归一化」四组职责。</li>
 *   <li><b>语义还一样吗</b>：一样。claim 的整型判定、1006 的失败码、reject 指标的 route/reason、
 *       {@code target_ids} 的 null 元素语义逐字未改。</li>
 *   <li><b>B 出处</b>：见上两个文件。</li>
 * </ol>
 *
 * <h2>⚠️ 鉴权不对称是 B 侧显式行为，不能抹平</h2>
 * <p>{@code getlikecount} / {@code gettargetlikerlist} 是<b>公开读</b> ⇒ <b>不</b>做身份解析；
 * {@code getlikestate} / {@code getuserlikelist} / {@code getusertotallike} / {@code likeaction}
 * <b>要</b> uid ⇒ 走 {@link #userId(HttpServletRequest)}。
 * <p>⇒ 「要不要 uid」是<b>用例级</b>判定，落点在本类的调用方
 * （{@code service/LikeApiService}），<b>不是</b>拦截器 —— 拦截器只验签、不判「这个端点要不要 uid」。
 */
@Service
public class LikeApiSupport {

    private final LikeApiMetrics metrics;

    public LikeApiSupport(LikeApiMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 对应 Go 侧 4 处 {@code ctx.Value("userId").(json.Number)} + {@code .Int64()}：
     * <pre>
     * userId, ok := l.ctx.Value("userId").(json.Number)
     * if !ok {
     *     metrics.ApiRejectCount.WithLabelValues("/like/likeaction", "token_invalid").Inc()
     *     return nil, errmsg.NewErrCode(errmsg.ErrorTokenRuntime)
     * }
     * uid, err := userId.Int64()
     * if err != nil {
     *     metrics.ApiRejectCount.WithLabelValues("/like/likeaction", "token_parse_error").Inc()
     *     return nil, errmsg.NewErrCode(errmsg.ErrorTokenRuntime)
     * }
     * </pre>
     *
     * <h3>四条必须逐条复刻的规则</h3>
     * <ol>
     *   <li><b>失败码是 1006，不是 401，也不是 5001。</b>
     *       Go 归成 {@code ErrorTokenRuntime}（文案 "TOKEN已过期"），
     *       输出 <b>HTTP 200 + {code:1006,msg:"TOKEN已过期"}</b>。
     *       改写成 401 会让客户端的「token 过期需重新登录」流程走错分支。</li>
     *   <li><b>msg 查表</b>：Go 这里是 {@code NewErrCode}（不是 {@code NewErrCodeMsg}），
     *       所以 msg 来自码表，<b>不是</b> ctx 里那条 "ctx userId is not json.Number"
     *       的日志文本（那条只进 logger）。</li>
     *   <li><b>claim 不是数字 ⇒ 1006</b>：Go 的类型断言目标是 {@code json.Number}，
     *       字符串 claim（{@code "userId":"abc"}）直接落 {@code !ok} 分支。
     *       这里只接受<b>整型</b>（Byte/Short/Integer/Long/BigInteger）。</li>
     *   <li><b>数字不是整数 ⇒ 1006</b>：Go 的 {@code json.Number.Int64()} 内部是
     *       {@code strconv.ParseInt(s,10,64)}，对 {@code "1.5"} / {@code "1e2"} / {@code "1.0"}
     *       一律失败。因此 {@link Double} / {@link java.math.BigDecimal} <b>一律拒</b> ——
     *       否则 RAG-V1 会把 Go 判 1006 的输入静默截断成合法 uid，那是<b>实打实的越权风险</b>。</li>
     * </ol>
     *
     * <p><b>🔴 JSON 字符串 claim（含 {@code "123"}）一律拒绝 ⇒ 1006</b>。
     * <p>错误注释曾写：「字符串形态 Go 侧同样是 {@code json.Number("123")} ⇒ 接受」——
     * 那是错的。{@code UseNumber()} 只影响 <b>JSON 数字</b>的解码形态，
     * <b>不改变 JSON 字符串本身的类型</b>：golang-jwt v4.5.2 {@code parser.go:154-156}
     * 之后，{@code "userId":"123"} 解出来就是 Go 的 {@code string}，
     * {@code .(json.Number)} 断言<b>失败</b> ⇒ Go 返 1006。
     * <p><b>为什么这条必须钉死</b>：一旦有人「把代码改成和注释一致」而放行字符串 claim，
     * 任何 {@code "userId":"<任意 id>"} 形态的 claim 都会被静默接受 ⇒
     * <b>越权取数</b>（可以把 uid 换成别人的）。
     *
     * <p><b>本方法不拒绝 {@code uid <= 0}</b>：Go 侧同样不拒绝，
     * {@code uid=0} 会原样透传给 like-rpc 走它自己的校验。api 层提前拒会把业务码换掉。
     */
    public long userId(HttpServletRequest request) {
        Object claim = request.getAttribute(LikeJwtAuthInterceptor.CLAIM_ATTRIBUTE);
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
            // Go 的 json.Number.Int64() 对越界字面量报 range error ⇒ 落在「有值但解析不出」的分支。
            // ⚠️ bitLength() 去掉符号位，所以 Long.MIN_VALUE 是 63；>=64 才真的越界。
            if (bi.bitLength() >= 64) {
                metrics.incApiReject(LikeApiMetrics.ROUTE_LABEL_GO_HARDCODED,
                        LikeApiMetrics.REASON_TOKEN_PARSE_ERROR);
                throw tokenRuntime();
            }
            return bi.longValue();
        }
        // 缺席 / String / Double / BigDecimal / Boolean / Map / 数组
        // ⇒ Go 的 `.(json.Number)` 类型断言失败，落 token_invalid 分支
        metrics.incApiReject(LikeApiMetrics.ROUTE_LABEL_GO_HARDCODED,
                LikeApiMetrics.REASON_TOKEN_INVALID);
        throw tokenRuntime();
    }

    /**
     * Go {@code NewErrCode(ErrorTokenRuntime)} 的等价物：<b>查表</b>得到
     * {@code "TOKEN已过期"}，而不是把 Go 那条日志文本塞进 msg。
     */
    private static CodeError tokenRuntime() {
        return CodeError.newCode(ErrCodes.ERROR_TOKEN_RUNTIME);
    }

    /**
     * 对应 Go 侧 6 处完全相同的错误映射：
     * <pre>
     * st, ok := status.FromError(err)
     * if ok {
     *     return nil, errmsg.NewErrCodeMsg(int(st.Code()), st.Message())
     * }
     * logger.LogBusinessErr(l.ctx, errmsg.ErrorServerCommon, err)
     * return nil, errmsg.NewErrCode(errmsg.ErrorServerCommon)
     * </pre>
     *
     * <h3>⚠️⚠️ 两条分支的差异是本类最容易做错的地方</h3>
     * <table border="1">
     *   <caption>Go 的 ok 标志决定一切</caption>
     *   <tr><th>错误形态</th><th>Go 侧结果</th></tr>
     *   <tr>
     *     <td>gRPC status 错误</td>
     *     <td>{@code NewErrCodeMsg(业务码, st.Message())} —— <b>msg 用 status 原文，不查表</b></td>
     *   </tr>
     *   <tr>
     *     <td>其它任何错误</td>
     *     <td>{@code NewErrCode(ErrorServerCommon)} ⇒ <b>5001 + "系统内部错误"</b></td>
     *   </tr>
     * </table>
     *
     * <p><b>为什么不能直接调 {@link GrpcErrFactory#bizCodeFromError(Throwable)} 就完事</b>：
     * 那个方法对<b>非 status</b> 异常返回 {@link ErrCodes#CODE_SERVER_BUSY}（1015），
     * 而 Go 在这条分支上给的是 <b>5001</b>。两者相差一整个业务码段，
     * 且 msg 来源完全不同（1015 查表得"服务繁忙"，5001 查表得"系统内部错误"）。
     * ⇒ <b>必须先自己判定「是不是 status 错误」</b>（即 Go 的 {@code ok}），
     * 再分别走两条分支。
     *
     * <p><b>msg 取 {@code status.getDescription()}，绝不查表</b>：
     * Go like-rpc 用 {@code NewGrpcErr(5003, "DB查询失败")} 发的是<b>自定义文案</b>，
     * 码表里 5003 的文案是"查询数据库失败"，<b>两者不相等</b>。
     * 查表会把客户端看到的错误信息换掉。
     *
     * <p><b>description 为 null 时按空串处理</b>：Go 的 {@code st.Message()} 在无 message 时
     * 返回 {@code ""} ⇒ {@code NewErrCodeMsg(code, "")}，msg 是<b>空串</b>。
     */
    public CodeError rpcError(Throwable err) {
        // ⚠️ D2（域内直连）的<b>关键适配</b>：B 侧这里收到的是<b>gRPC status 异常</b>
        // （api → LikeRpcClient → grpc → rpc 进程），本域业务码藏在 trailer
        // （{@code rag-like-biz-code}）里、原始文案藏在 description 里。
        // ⇒ D2 之后中间那层 gRPC 往返<b>被取消</b> ⇒ 领域层直接抛
        // {@link com.rag.like.exception.LikeExceptions.Rpc}（它带 bizCode + msg 两个字段）。
        //
        // ⇒ 这一分支与 B 侧的「gRPC status 分支」<b>逐字等价</b>：
        //   B: status(description=msg, trailer=bizCode) ⇒ newCodeMsg(bizCode, msg)
        //   C: LikeExceptions.Rpc(bizCode, msg)          ⇒ newCodeMsg(bizCode, msg)
        //
        // ⚠️ <b>绝不能</b>把它当成「非 status 异常」⇒ 那会落进下面的 5001 分支
        // ⇒ 业务码被换掉（5003 → 5001）且文案从「DB查询失败」变成「系统内部错误」。
        if (err instanceof com.rag.like.exception.LikeExceptions.Rpc business) {
            return CodeError.newCodeMsg(business.getBizCode(), business.getMsg());
        }
        Status status = extractStatus(err);
        if (status == null) {
            // Go: status.FromError 的 ok == false 分支 —— 注意是 5001，不是 1015
            return CodeError.newCode(ErrCodes.ERROR_SERVER_COMMON);
        }
        int bizCode = GrpcErrFactory.bizCodeFromError(err);
        String description = status.getDescription();
        return CodeError.newCodeMsg(bizCode, description == null ? "" : description);
    }

    /**
     * 判定「是不是 gRPC status 错误」—— 对应 Go {@code status.FromError(err)} 的
     * 第二个返回值 {@code ok}。
     *
     * <p>Go 侧 {@code status.FromError} 对<b>非</b> status 错误返回
     * {@code ok == false}；grpc-java 没有等价的「ok」，
     * 所以用类型判定：{@link StatusException}（server 端抛）与
     * {@link StatusRuntimeException}（<b>blocking stub 实际抛的是这个</b>）都算 status 错误，
     * 其它一律不算。
     */
    private static Status extractStatus(Throwable err) {
        if (err instanceof StatusException statusException) {
            return statusException.getStatus();
        }
        if (err instanceof StatusRuntimeException statusRuntimeException) {
            return statusRuntimeException.getStatus();
        }
        return null;
    }

    // ==================================================================================
    // 以下为 B 侧 GoStyleTargetIds.java 并入本类后的内容（映射表第 115 行 · GAP-4）
    // ==================================================================================

    /**
     * 把 DTO 里可能含 {@code null} 的 {@code target_ids} 还原成 Go 会发出的形态。
     *
     * <h2>为什么必须专门处理</h2>
     *
     * <p>protobuf 的 {@code addAllTargetIds(...)} 对 {@code null} 元素是<b>硬失败</b>：
     * {@code NullPointerException: Element at index 0 is null.}。
     * 若把 DTO 里的 {@code null} 原样透传过去，异常会被 api 层异常处理器兜成
     * {@code 200 + {"code":5001}}，<b>下游 RPC 根本没发出</b> —— 而 Go 侧是<b>正常发出</b>的。
     * 方向是「本该成功的读变成 5001」，且被 NPE 兜底掩盖，排查成本高（DRIFT-LIKEAPI-013）。
     *
     * <h2>Go 的真实语义（消费点：{@code core/mapping/unmarshaler.go:156-183}）</h2>
     *
     * <table border="1">
     *   <caption>逐条</caption>
     *   <tr><th>输入</th><th>Go 结果</th><th>后果</th></tr>
     *   <tr><td>{@code 缺席}</td><td>slice 为 nil</td><td>空 target_ids</td></tr>
     *   <tr><td>{@code []}</td><td>空 slice</td><td>空 target_ids</td></tr>
     *   <tr><td>{@code [null]}</td><td><b>slice 保持 nil</b>（{@code valid} 标志从未置位 ⇒ {@code value.Set} 不执行）</td>
     *       <td>空 target_ids ⇒ like-rpc 回 2001</td></tr>
     *   <tr><td>{@code ["x", null, "y"]}</td><td><b>{@code ["x", "", "y"]}</b> —— {@code conv} 长度仍是 3，
     *       null 位留成零值空串（{@code unmarshaler.go:158-160} 的 {@code continue}）</td>
     *       <td><b>不是过滤</b>，Go 不会缩短数组</td></tr>
     * </table>
     *
     * <p>⚠️ 最后一行最容易做错：把 {@code null} 过滤掉会把长度 3 缩成 2，
     * 于是 <b>下一页的 limit 边界与 offset 全部错位</b> —— 这是分页语义，不是长度洁癖。
     *
     * <p><b>⚠️ 不要在这里顺手实现 DRIFT-LIKEAPI-012</b>：
     * 012 是另一条独立漂移：{@code target_ids} 里放 <b>JSON 数字</b>时 Go <b>接受</b>
     * （{@code fillSliceValue} 的 {@code case fmt.Stringer} 命中 {@code json.Number}），
     * 而 RAG-V1 的 {@code LogicalType.Textual ← Integer = Fail} 把它拒了。
     * 那是<b>解析层</b>（DTO）的责任，<b>不是</b>本方法的责任 ——
     * 数字若已被解析层拒掉，这里根本看不到。
     *
     * @param ids DTO 的原始列表，可能为 {@code null}、为空、或含 {@code null} 元素
     * @return <b>永不</b>返回 {@code null}，也<b>永不</b>含 {@code null} 元素（protobuf 安全）
     */
    public static List<String> normalizeTargetIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        // 全 null ⇒ Go 侧 slice 保持 nil ⇒ 等价于空列表（会打出 2001）
        boolean allNull = true;
        for (String id : ids) {
            if (id != null) {
                allNull = false;
                break;
            }
        }
        if (allNull) {
            return List.of();
        }

        // 部分 null ⇒ 该位变空串，**长度不变**（Go 的 continue 不是过滤）
        List<String> normalized = new ArrayList<>(ids.size());
        for (String id : ids) {
            normalized.add(id == null ? "" : id);
        }
        return normalized;
    }
}
