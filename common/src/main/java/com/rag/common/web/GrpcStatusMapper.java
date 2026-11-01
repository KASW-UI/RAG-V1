package com.rag.common.web;

import io.grpc.Status;
import org.springframework.http.HttpStatus;

/**
 * Go {@code rest/internal/errcode/grpc.go} 的两条函数的 Java 等价物 ——
 * {@code CodeFromGrpcError}（{@code grpc.go:12-42}）与 {@code IsGrpcError}（{@code grpc.go:45-55}）。
 *
 * <h2>Go 出处与控制流（逐字读过消费点，非推断）</h2>
 * <pre>
 * // go-zero v1.10.2 · rest/httpx/responses.go:142-159
 * func doHandleError(w http.ResponseWriter, err error, handler func(error) (int, any), …) {
 *     if handler == nil {                       // ← article-api **没有**注册 error handler
 *         if len(fns) > 0 { … }
 *         else if errcode.IsGrpcError(err) {    // ← ① 先问「是不是 gRPC 错误」
 *             // don't unwrap error and get status.Message(),
 *             // it hides the rpc error headers.
 *             http.Error(w, err.Error(), errcode.CodeFromGrpcError(err))   // ← ② 按 gRPC 码映射
 *         } else {
 *             http.Error(w, err.Error(), http.StatusBadRequest)            // ← ③ 非 gRPC ⇒ 恒 400
 *         }
 *         return
 *     }
 *     …
 * }
 * </pre>
 *
 * <h2>⚠️ 为什么本类存在（AGENTS.md §3.1「不要拿另一个域的实测结论当基准」）</h2>
 * <p>message-api / like-api / hot-api <b>都注册了</b> {@code httpx.SetErrorHandler}，
 * 失败走「200 + 信封」；article-api <b>没有注册</b>（{@code service/article/api/article.go} 全文 42 行），
 * 失败走 {@code httpx.ErrorCtx} ⇒ <b>裸 HTTP 状态码 + text/plain</b>。
 * <p>⇒ 照搬 message-api 的「失败一律 200 信封」是漂移；反过来，
 * 照搬「article 没有 error handler ⇒ 一律 400」<b>也是漂移</b> ——
 * 因为 {@code doHandleError} 里 {@code IsGrpcError} 那条分支<b>排在</b> 400 分支<b>前面</b>。
 * <pre>
 *   IsGrpcError(err) 的判据（grpc.go:45-55）：
 *       _, ok := err.(interface{ GRPCStatus() *status.Status })
 *   ⇒ grpc-go 的 *status.Error 实现了它 ⇒ 走 ② 分支
 *   ⇒ 而 breaker 打开时返回的 breaker.ErrServiceUnavailable = errors.New("circuit breaker is open")
 *      是**普通 error** ⇒ 走 ③ 分支 ⇒ 400
 * </pre>
 *
 * <h2>⚠️ body 文案也要对齐（这是 wire 层可见的）</h2>
 * <p>Go 的 {@code http.Error(w, err.Error(), …)} 写的是 {@code err.Error()}，
 * 而 grpc-go 的 {@code *status.Error.Error()}（{@code internal/status/status.go:211-212}）
 * 转发到 {@code Status.String()}（同文件 {@code :201-203}）：
 * <pre>
 *   fmt.Sprintf("rpc error: code = %s desc = %s", s.Code(), s.Message())
 * </pre>
 * <p>⇒ Go 的 body 形如 {@code rpc error: code = Unavailable desc = minio down}。
 * ⚠️ Java 侧 {@code StatusRuntimeException.getMessage()} 给的是
 * {@code UNAVAILABLE: minio down}（{@code Status.toString()}）—— <b>字面不同</b>。
 * 本类的 {@link #goErrorMessage} 负责把它复刻成 Go 的形状。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/article/article-api/src/main/java/com/rag/article/article/api/http/GoStyleGrpcStatusMapper.java}
 * §1.11 第 249 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson 3 / Boot 4 专有写法 ⇒ 零回改）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在 <b>article-api</b> 模块 ⇒ RAG-V1 归位到全局 {@code common/web/}
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 * <p><b>⚠️ 职责重叠（映射表 §7「三列职责对不上 #2」· <b>待拍板</b>）</b>：
 * 本类与 {@code common/autoconfigure/GrpcExceptionMapperAutoConfiguration} <b>都做</b>
 * 「gRPC code → 业务码」映射 ⇒ ⇒ 装配顺序不同 ⇒ 同一异常可能映射出<b>两个不同 code</b>
 * ⇒ ⇒ §7 #2 的建议是「<b>删 {@code web/GrpcStatusMapper.java}</b>，统一由 auto-config 的 mapper 承担」
 * ⇒ ⇒ ⚠️ 但那是<b>待拍板项</b> ⇒ ⇒ 本轮<b>先按映射表 §1.11 落位</b>，<b>不擅自删除</b>。
 */
public final class GrpcStatusMapper {

    private GrpcStatusMapper() {
    }

    /**
     * Go {@code errcode.CodeFromGrpcError}（{@code grpc.go:12-42}）的逐条等价物。
     *
     * <p>⚠️ 注意 Go 的 {@code switch} <b>没有 default 分支</b>，
     * 落到 {@code return http.StatusInternalServerError}（{@code grpc.go:41}）。
     * ⇒ <b>未知码落 500，不是 400</b>。
     *
     * @param code gRPC 状态码；{@code null} 视作 Go 的 {@code codes.Unknown}（⇒ 500）
     */
    public static HttpStatus httpStatusOf(Status.Code code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case OK -> HttpStatus.OK;
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CANCELLED -> HttpStatus.REQUEST_TIMEOUT;
            case ALREADY_EXISTS, ABORTED -> HttpStatus.CONFLICT;
            case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL, DATA_LOSS, UNKNOWN -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UNIMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
        };
    }

    /**
     * Go {@code codes.Code.String()}（grpc-go {@code codes/code_string.go:31+}）的规范名。
     *
     * <p>⚠️ Go 用的是 <b>CamelCase</b>（{@code Canceled} / {@code DeadlineExceeded}），
     * 而 Java {@code Status.Code} 的枚举名是 <b>SCREAMING_SNAKE</b>
     * （{@code CANCELLED} / {@code DEADLINE_EXCEEDED}）——
     * 直接用 {@code code.name()} 会得到 <b>字面不同</b>的 body。
     */
    public static String goCodeName(Status.Code code) {
        if (code == null) {
            return "Unknown";
        }
        return switch (code) {
            case OK -> "OK";
            case CANCELLED -> "Canceled";
            case UNKNOWN -> "Unknown";
            case INVALID_ARGUMENT -> "InvalidArgument";
            case DEADLINE_EXCEEDED -> "DeadlineExceeded";
            case NOT_FOUND -> "NotFound";
            case ALREADY_EXISTS -> "AlreadyExists";
            case PERMISSION_DENIED -> "PermissionDenied";
            case RESOURCE_EXHAUSTED -> "ResourceExhausted";
            case FAILED_PRECONDITION -> "FailedPrecondition";
            case ABORTED -> "Aborted";
            case OUT_OF_RANGE -> "OutOfRange";
            case UNIMPLEMENTED -> "Unimplemented";
            case INTERNAL -> "Internal";
            case UNAVAILABLE -> "Unavailable";
            case DATA_LOSS -> "DataLoss";
            case UNAUTHENTICATED -> "Unauthenticated";
        };
    }

    /**
     * Go {@code err.Error()} 在 gRPC 状态错误下的<b>逐字</b>形状。
     *
     * <p>grpc-go {@code internal/status/status.go:201-203}：
     * <pre>fmt.Sprintf("rpc error: code = %s desc = %s", s.Code(), s.Message())</pre>
     *
     * @param description 状态描述（可为空 ⇒ Go 的 {@code desc = } 后面留空）
     */
    public static String goErrorMessage(Status.Code code, String description) {
        return "rpc error: code = " + goCodeName(code) + " desc = "
                + (description == null ? "" : description);
    }
}
