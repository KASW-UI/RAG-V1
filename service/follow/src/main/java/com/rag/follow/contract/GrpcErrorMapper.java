package com.rag.follow.contract;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>follow 域 gRPC 错误双向转换（L1 契约层 · 域内私有）</b> —— 对应 B 侧
 * {@code service/follow/follow-common/src/main/java/com/rag/follow/follow/common/errmsg/FollowGrpcError.java}
 * 的 {@code bizCodeByMsg} / {@code grpcError} / {@code bizCodeFromError} 三段。
 *
 * <h2>本域的 wire 形态：业务码藏在 description 文案里</h2>
 *
 * <p>B 侧发出错误时<b>不</b>把业务码写进 {@code grpc-status}，而是：
 * <pre>
 * func GRPCError(grpcCode codes.Code, bizCode int) error {
 *     return status.Error(grpcCode, GetErrMsg(bizCode))   // 只有「标准 gRPC 码 + 业务文案」
 * }
 * </pre>
 * ⇒ wire 上业务码靠<b>文案反查</b>还原。
 *
 * <p><b>⚠️ 文案反查不是可选优化，而是本域唯一的还原途径</b>：
 * follow-rpc 实际发出的 4 种 gRPC 码<b>全部落在</b> {@link #mapStatusCodeToBizCode} 的
 * switch 白名单内（{@code Internal}×多、{@code NotFound}、{@code InvalidArgument}、
 * {@code PermissionDenied}），但白名单只能还原出<b>粗粒度</b>的码：
 * <table border="1">
 *   <caption>B 侧真实调用点：switch 兜底会给出错误的码</caption>
 *   <tr><th>调用点</th><th>gRPC 码</th><th>业务码</th><th>仅靠 switch 兜底得到</th></tr>
 *   <tr><td>rpc 参数校验</td><td>INVALID_ARGUMENT</td><td>5003</td><td>5003 ✅</td></tr>
 *   <tr><td>自关系校验</td><td>INVALID_ARGUMENT</td><td>5201 自关系</td><td>5003 ❌</td></tr>
 *   <tr><td>已拉黑</td><td>PERMISSION_DENIED</td><td>5202 已拉黑</td><td>5005 ❌</td></tr>
 *   <tr><td>用户不存在</td><td>NOT_FOUND</td><td>5101 用户不存在</td><td>5002 ❌</td></tr>
 *   <tr><td>下游其它故障</td><td>INTERNAL</td><td>5001</td><td>5001 ✅</td></tr>
 *   <tr><td>各 logic 的 DB 分支</td><td>INTERNAL</td><td>5007/5008</td><td>5001 ❌</td></tr>
 * </table>
 * 标 ❌ 的 5 类场景里，{@link #BIZ_CODE_BY_MSG} 是<b>唯一</b>能把 5201/5202/5101/5007/5008
 * 还原出来的途径。删掉这张表或跳过文案反查 ⇒ api 层返回给客户端的业务码会全部退化。
 *
 * <p><b>本域不需要自定义 trailer</b>：follow-rpc 实际发出的 4 种 gRPC 码
 * <b>全部是 grpc-java 认识的 17 个标准码之一</b> ⇒ 且 description 文案 grpc-java
 * 是<b>逐字节保真</b>的 ⇒ 加一个 B 侧不存在的 wire 元素属于新增行为。
 *
 * <p><b>框架职责划分</b>：
 * <ul>
 *   <li><b>Spring 不参与</b>：本组件是 gRPC 错误契约，与 HTTP 无关
 *       ⇒ Spring 的 {@code ResponseStatusException} / {@code ProblemDetail} 都是 HTTP 错误模型，
 *       不承载 gRPC 码，套用反而会把业务码降级成 HTTP status（破坏 L1）。</li>
 *   <li><b>grpc-java 承担传输</b>：{@link Status} / {@link StatusException} 由
 *       {@code io.grpc:grpc-stub}（传递依赖 {@code grpc-api}）提供。</li>
 *   <li><b>业务码 ↔ 文案的映射表由本类持有</b>：grpc-java 不认识 follow 的 16 个业务码，
 *       也没有任何「按 description 反查业务码」的机制 ⇒ 这部分必须自定义。</li>
 * </ul>
 *
 * <p><b>⚠️ 表达层差异（L4 · 语义等价）</b>：B 侧是「工具类 + 静态方法」，
 * C 侧沿用同一形态（{@code final class} + 私有构造器）⇒ <b>零回改</b>。
 */
public final class GrpcErrorMapper {

    /**
     * msg → code 反查表，对应 B 侧的
     * <pre>
     * var bizCodeByMsg = func() map[string]int {
     *     lookup := make(map[string]int, len(codeMsg))
     *     for code, msg := range codeMsg {
     *         if code == Success || code == Error { continue }
     *         if _, exists := lookup[msg]; !exists { lookup[msg] = code }
     *     }
     *     return lookup
     * }()
     * </pre>
     *
     * <p>对应行为：
     * <ul>
     *   <li>排除 {@link ErrCodes#SUCCESS} / {@link ErrCodes#ERROR} ——
     *       它们的文案（{@code "OK"} / {@code "FAIL"}）过于泛化，纳入反查会误命中；</li>
     *   <li>first-write-wins 去重（B 侧用 {@code if _, exists := lookup[msg]; !exists}），
     *       这里用 {@link Map#putIfAbsent} 表达同一语义。⚠️ B 侧遍历 map 顺序随机，
     *       所以「一旦出现重复文案，反查结果就随机」；当前 16 条文案两两互不相同，
     *       该分支实际不可达。</li>
     * </ul>
     */
    private static final Map<String, Integer> BIZ_CODE_BY_MSG;

    static {
        Map<String, Integer> reverse = new HashMap<>();
        for (Map.Entry<Integer, String> entry : ErrMsg.MSG_BY_CODE.entrySet()) {
            int code = entry.getKey();
            if (code == ErrCodes.SUCCESS || code == ErrCodes.ERROR) {
                continue;
            }
            reverse.putIfAbsent(entry.getValue(), code);
        }
        BIZ_CODE_BY_MSG = Collections.unmodifiableMap(reverse);
    }

    private GrpcErrorMapper() {
        // 工具类，禁止 new
    }

    /**
     * 对应 B 侧 {@code status.Error(grpcCode, GetErrMsg(bizCode))}：
     * 以给定的 <b>标准</b> gRPC 码构造 status，description 放业务码查表得到的文案。
     *
     * <p><b>⚠️ 调用方必须交给 {@code responseObserver.onError(...)}，不能 {@code throw}</b>：
     * grpc-java 在 {@code UnaryServerCallListener.onHalfClose} 里调用业务方法，
     * 在那里 {@code throw} 会让异常逃出流监听回调，被当成<b>传输层内部错误</b>，
     * description 被替换成 {@code "Application error processing RPC"} —— <b>文案当场全丢</b>，
     * 而文案正是本域业务码的载体（丢了就再也还原不出业务码，见类注释的表格）。
     *
     * <p><b>不设默认超时 / 重试</b>：B 侧 {@code GRPCError} 也不带，
     * 超时与熔断由 client/server interceptor 承担。
     *
     * @param grpcCode 标准 gRPC 码
     * @param bizCode  follow 域业务码
     * @return 带业务文案 description 的 checked 异常
     */
    public static StatusException grpcError(Status.Code grpcCode, int bizCode) {
        return Status.fromCode(grpcCode)
                .withDescription(ErrMsg.getMsg(bizCode))
                .asException();
    }

    /** 对应 B 侧未导出的 {@code func bizCodeFromMessage(msg string) (int, bool)}。 */
    private static Integer bizCodeFromMessage(String msg) {
        return BIZ_CODE_BY_MSG.get(msg);
    }

    /**
     * 对应 B 侧 {@code func BizCodeFromError(err error) int}：
     * 从任意 error 里还原 follow 业务码。
     *
     * <p><b>判定顺序即语义（顺序不可调换）</b>：
     * <ol>
     *   <li>{@code err == null} → {@link ErrCodes#SUCCESS}（200）；</li>
     *   <li><b>不是 gRPC status</b> → 先看顶层是不是 {@link CodeError}，
     *       是则取它的 {@code code}；否则 {@link ErrCodes#CODE_SERVER_BUSY}（1015）。
     *       ⚠️ B 侧这里用的是<b>直接类型断言</b> {@code err.(*CodeError)}，
     *       <b>不</b>沿 cause 链 —— 见 {@link #resolveStatus} 的说明，两处行为刻意不对称；</li>
     *   <li><b>是 gRPC status</b> → 先按 description <b>反查文案</b>，命中即返回；</li>
     *   <li>反查未命中 → 按标准 gRPC 码类别兜底映射，见 {@link #mapStatusCodeToBizCode}；</li>
     *   <li>兜底也未命中（{@code default}）→ {@link ErrCodes#CODE_SERVER_BUSY}（1015）。</li>
     * </ol>
     *
     * @param err 任意异常（可为 null）
     * @return follow 域业务码
     */
    public static int bizCodeFromError(Throwable err) {
        if (err == null) {
            return ErrCodes.SUCCESS;
        }

        ResolvedStatus resolved = resolveStatus(err);
        if (resolved == null) {
            // B: status.FromError 返回 ok=false
            // ⚠️ 直接类型断言，只判顶层，不走 getCause() 链
            if (err instanceof CodeError codeError) {
                return codeError.getCode();
            }
            return ErrCodes.CODE_SERVER_BUSY;
        }

        Integer fromMsg = bizCodeFromMessage(resolved.message());
        if (fromMsg != null) {
            return fromMsg;
        }

        return mapStatusCodeToBizCode(resolved.status().getCode());
    }

    /**
     * 对应 B 侧 {@code BizCodeFromError} 末尾的 {@code switch st.Code()}。
     *
     * <p>白名单<b>恰好 6 个</b>：{@code InvalidArgument} / {@code Unauthenticated} /
     * {@code PermissionDenied} / {@code AlreadyExists} / {@code NotFound} / {@code Internal}，
     * 其余（含 {@code Unavailable}、{@code DeadlineExceeded}、{@code Canceled}）一律走
     * {@code default} → 1015。
     *
     * ⚠️ <b>不要为了「显得合理」往白名单里加码</b>：B 侧没有这些映射，
     * 加上会让降级场景从 1015 变成别的码 —— 那是新增行为，不是对齐。
     * 反之也不要把白名单<b>缩小</b>：这 6 个是 B 侧写死的。
     */
    private static int mapStatusCodeToBizCode(Status.Code grpcCode) {
        if (grpcCode == null) {
            return ErrCodes.CODE_SERVER_BUSY;
        }
        switch (grpcCode) {
            case INVALID_ARGUMENT:
                return ErrCodes.ERROR_INVALID_PARAM;
            case UNAUTHENTICATED:
                return ErrCodes.ERROR_UNAUTHORIZED;
            case PERMISSION_DENIED:
                return ErrCodes.ERROR_FORBIDDEN;
            case ALREADY_EXISTS:
                return ErrCodes.ERROR_ALREADY_EXIST;
            case NOT_FOUND:
                return ErrCodes.ERROR_NOT_FOUND;
            case INTERNAL:
                return ErrCodes.ERROR_SERVER_COMMON;
            default:
                return ErrCodes.CODE_SERVER_BUSY;
        }
    }

    /**
     * 对应 grpc-go 的 {@code status.FromError}（<b>两段式</b>判定）。
     *
     * <p>B 侧原实现的关键结构是<b>两级</b>的，且第二级会<b>改写</b> message：
     * <pre>
     * func FromError(err error) (s *Status, ok bool) {
     *     if err == nil { return nil, true }
     *     type grpcstatus interface{ GRPCStatus() *Status }
     *     if gs, ok := err.(grpcstatus); ok {            // ① 顶层
     *         return gs.GRPCStatus(), true              //    message 原样
     *     }
     *     var gs grpcstatus
     *     if errors.As(err, &amp;gs) {                    // ② 沿 cause 链
     *         p := gs.GRPCStatus().Proto()
     *         p.Message = err.Error()                   // ⚠️ message 被【外层】err.Error() 覆盖
     *         return status.FromProto(p), true
     *     }
     *     return New(codes.Unknown, err.Error()), false
     * }
     * </pre>
     *
     * <p><b>⚠️ 为什么不能直接用 grpc-java 的 {@code Status.fromThrowable}</b>：
     * 它会沿 cause 链找到 status 并<b>原样保留 description</b>，而 B 侧在这条路径上把
     * description 覆盖成了外层 error 的 {@code Error()} 文本。
     * 两者对「被业务代码包了一层的 status error」会给出<b>不同的业务码</b>
     * ⇒ 必须按 B 侧的两段式手写，不能借用 {@code Status.fromThrowable}。
     *
     * <p><b>本域实际路径</b>：api 层唯一把 RPC 错误喂进来的地方是 {@code codeFromRPCError}，
     * 而 {@code rpcErr} 是 protoc 生成的 stub 直接返回的 {@code *status.Error}
     * ⇒ 真实运行里恒走第①级。本类仍完整实现第②级，
     * 是为了在 api 层将来引入包装时<b>行为不发生漂移</b>。
     */
    private static ResolvedStatus resolveStatus(Throwable err) {
        // ① 顶层直接命中：message 原样保留（B 侧 err.(grpcstatus) 分支）
        Status top = statusOf(err);
        if (top != null) {
            return new ResolvedStatus(top, top.getDescription());
        }

        // ② 沿 cause 链命中：message 被【外层】err.Error() 覆盖（B 侧 errors.As 分支）
        Throwable nested = err.getCause();
        // 上限防御：Throwable 允许构造出 cause 环，B 侧的 errors.As 有自己的终止条件
        for (int depth = 0; nested != null && depth < 64; depth++) {
            Status found = statusOf(nested);
            if (found != null) {
                return new ResolvedStatus(found, err.getMessage());
            }
            Throwable next = nested.getCause();
            if (next == nested) {
                break;
            }
            nested = next;
        }
        return null;
    }

    /** 对应 B 侧 {@code type grpcstatus interface{ GRPCStatus() *Status }} 的 {@code instanceof} 判定。 */
    private static Status statusOf(Throwable err) {
        if (err instanceof StatusException statusException) {
            return statusException.getStatus();
        }
        if (err instanceof StatusRuntimeException statusRuntimeException) {
            return statusRuntimeException.getStatus();
        }
        return null;
    }

    /**
     * 解析结果：{@code status} 用于取 gRPC 码，{@code message} 用于文案反查。
     *
     * <p>两者必须分开：第②级里 B 侧的 message 已被外层 {@code err.Error()} 覆盖，
     * 与 {@code status.getDescription()} 不再是同一个值。
     */
    private record ResolvedStatus(Status status, String message) {
    }
}
