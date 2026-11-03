package com.rag.hot.contract;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>hot 域 gRPC 错误转换工具</b> —— 业务码 ⇄ gRPC status 的双向转换 + status 提取。
 *
 * <p><b>⚠️ B 侧无对应实现</b>（映射表 hot.md §1.1 已实测登记：hot 域<b>零</b> gRPC 错误码反查调用点）⇒
 * 本类是 C 侧新建的「<b>B 侧原本不可达</b>」的映射器。
 * ⇒ ⇒ <b>本类的存在不改变任何可观察行为</b>（hot 域的 gRPC 出口按 B 侧
 * {@code HotServiceGrpcImpl} 的口径把裸异常映射成 {@code codes.Unknown}，
 * <b>不</b>走业务码反查）⇒ 与 B 侧 {@code HotApiMetrics#observeReject}（零触发点）同款地位：
 * <b>保留 API 以便对照，刻意不补调用点</b>。
 *
 * <p><b>⚠️ 反查算法是 L1</b>（映射表 hot.md §1.1 逐条给出）：
 * <ol>
 *   <li><b>先</b>按 message（gRPC status 的 description）反查业务码；</li>
 *   <li><b>查不到</b>才按 gRPC 标准码 fallback：
 *       {@code InvalidArgument→5003} / {@code Unauthenticated→5004} /
 *       {@code PermissionDenied→5005} / {@code AlreadyExists→5006} /
 *       {@code NotFound→5002} / {@code Internal→5001} / default → {@code 1015}。</li>
 * </ol>
 *
 * <p><b>B 出处（算法来源）</b>：{@code service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageGrpcError.java}
 * （B 侧同类实现；hot 域无对应物 ⇒ 本类按<b>本域码表</b>重建同一套算法）。
 * ⚠️ <b>反查表只在域内自洽</b> —— 各域文案不同 ⇒ <b>不可跨域复用</b>（AGENTS.md §八「未统一化」）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：本类<b>整体是 C 侧新建</b>（hot 域无 B 侧对应物）⇒
 *       形态沿用本仓 {@code message} 域同名类的既有写法（工具类 + 私有构造器 + 静态方法 + 静态初始化块）。</li>
 *   <li><b>语义还一样吗</b>：<b>不适用</b> —— B 侧 hot 域<b>没有</b>这个行为可比；
 *       本类<b>零调用点</b> ⇒ 对 hot 域的可观察行为<b>零影响</b>。</li>
 *   <li><b>B 出处</b>：算法形态取自 B 侧 message 域同名类（见上）；hot 域「无对应物」的事实见映射表 §1.1。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：工具类 + 静态初始化块 + 静态方法，
 * {@code @UtilityClass} 在本仓被明令禁止 ⇒ 不引入（A13 判据不适用）。
 */
public final class GrpcErrorMapper {

    private GrpcErrorMapper() {
        // 工具类，禁止 new
    }

    /**
     * 按业务码造一个 {@link CodeError} —— 按 {@link ErrMsg#getMsg(int)} 查表取文案。
     *
     * @param code 业务码
     * @return 携带该码值与对应文案的 {@link CodeError}
     */
    public static CodeError newCode(int code) {
        return new CodeError(code, ErrMsg.getMsg(code));
    }

    /**
     * 按业务码 + <b>指定文案</b>造一个 {@link CodeError}（<b>不查表</b>）。
     *
     * @param code 业务码
     * @param msg  业务文案（<b>原样使用</b>）
     * @return 携带该码值与该文案的 {@link CodeError}
     */
    public static CodeError newCodeMsg(int code, String msg) {
        return new CodeError(code, msg);
    }

    /**
     * 造一个 gRPC {@link StatusException}：gRPC code 用入参，业务码的<b>文案</b>塞进 status description。
     *
     * <p>这是 Go 服务之间约定俗成的「业务码藏在 gRPC status 文案里」的 wire 格式：
     * 客户端拿到 gRPC error 后调 {@link #bizCodeFromError(Throwable)}，先用 description 反查回业务码，
     * 反查失败再按 gRPC code class 兜底。
     *
     * @param grpcCode gRPC 标准码
     * @param bizCode  业务码（只用于查文案）
     * @return 携带 description 的 {@link StatusException}
     */
    public static StatusException grpcError(Status.Code grpcCode, int bizCode) {
        return Status.fromCode(grpcCode)
                .withDescription(ErrMsg.getMsg(bizCode))
                .asException();
    }

    /**
     * msg → code 反查表 —— 由 {@link ErrMsg#allMsgs()} 构建。
     *
     * <p><b>构建规则</b>：
     * <ul>
     *   <li>遍历全部 code → msg（本域 11 条）；</li>
     *   <li>排除 {@link ErrCodes#SUCCESS} / {@link ErrCodes#ERROR} —— 它们的文案
     *       （{@code "OK"} / {@code "FAIL"}）过于泛化，反查会乱套；</li>
     *   <li>first-write-wins 去重（{@code putIfAbsent}）—— 本域 11 条文案互不相同 ⇒ 实际是 1:1 反查；
     *       保留去重逻辑防止以后加重复项时反查行为漂移。</li>
     * </ul>
     */
    private static final Map<String, Integer> BIZ_CODE_BY_MSG;

    static {
        Map<String, Integer> reverse = new HashMap<>();
        for (Map.Entry<Integer, String> entry : ErrMsg.allMsgs().entrySet()) {
            int code = entry.getKey();
            if (code == ErrCodes.SUCCESS.getCode()
                    || code == ErrCodes.ERROR.getCode()) {
                continue;
            }
            reverse.putIfAbsent(entry.getValue(), code);
        }
        BIZ_CODE_BY_MSG = Collections.unmodifiableMap(reverse);
    }

    /**
     * 从 gRPC / 业务异常中<b>还原业务码</b>。
     *
     * <p><b>逐条行为</b>：
     * <ul>
     *   <li>{@code err == null} ⇒ {@link ErrCodes#SUCCESS}（200）；</li>
     *   <li>是 {@link CodeError} ⇒ <b>直接返回它的 {@code code}</b>（不看表、不看 gRPC code）；</li>
     *   <li>沿 cause 链找到 gRPC status ⇒ 先用 description 反查 {@code BIZ_CODE_BY_MSG}，
     *       查不到再按 gRPC code class 兜底映射（{@link #mapStatusCodeToBizCode(Status.Code)}）；</li>
     *   <li>其它任意 {@link Throwable} ⇒ {@link ErrCodes#CODE_SERVER_BUSY}（1015）。</li>
     * </ul>
     *
     * <p><b>⚠️ 解包路径的 description 覆盖</b>：沿 cause 链<b>解包</b>命中时，用<b>外层</b>异常文本
     * 覆盖 description（复刻 grpc-go {@code status.FromError} 的 {@code p.Message = err.Error()}）
     * ⇒ 此时按 description 反查业务码会<b>查不中</b> ⇒ 最终落到 gRPC code class 兜底。
     *
     * @param err 任意异常（可含 cause 链）
     * @return 业务码
     */
    public static int bizCodeFromError(Throwable err) {
        if (err == null) {
            return ErrCodes.SUCCESS.getCode();
        }

        // 顺序：**先判 CodeError，再判 status**（B 侧 message 域 MessageGrpcError.java:230-232）。
        if (err instanceof CodeError codeError) {
            return codeError.getCode();
        }

        FoundStatus found = extractStatus(err);
        if (found != null) {
            String description = found.wrapped() ? err.getMessage() : found.status().getDescription();
            Integer code = BIZ_CODE_BY_MSG.get(description);
            if (code != null) {
                return code;
            }
            return mapStatusCodeToBizCode(found.status().getCode());
        }

        return ErrCodes.CODE_SERVER_BUSY.getCode();
    }

    /**
     * gRPC code class → 业务码的兜底映射 —— <b>L1 契约</b>（映射表 hot.md §1.1 逐条给出）。
     *
     * <pre>
     *   INVALID_ARGUMENT  → 5003 ERROR_INVALID_PARAM
     *   UNAUTHENTICATED   → 5004 ERROR_UNAUTHORIZED
     *   PERMISSION_DENIED → 5005 ERROR_FORBIDDEN
     *   ALREADY_EXISTS    → 5006 ERROR_ALREADY_EXIST
     *   NOT_FOUND         → 5002 ERROR_NOT_FOUND
     *   INTERNAL          → 5001 ERROR_SERVER_COMMON
     *   default（其它）    → 1015 CODE_SERVER_BUSY
     * </pre>
     *
     * @param grpcCode gRPC 标准码
     * @return 业务码
     */
    private static int mapStatusCodeToBizCode(Status.Code grpcCode) {
        switch (grpcCode) {
            case INVALID_ARGUMENT:
                return ErrCodes.ERROR_INVALID_PARAM.getCode();
            case UNAUTHENTICATED:
                return ErrCodes.ERROR_UNAUTHORIZED.getCode();
            case PERMISSION_DENIED:
                return ErrCodes.ERROR_FORBIDDEN.getCode();
            case ALREADY_EXISTS:
                return ErrCodes.ERROR_ALREADY_EXIST.getCode();
            case NOT_FOUND:
                return ErrCodes.ERROR_NOT_FOUND.getCode();
            case INTERNAL:
                return ErrCodes.ERROR_SERVER_COMMON.getCode();
            default:
                return ErrCodes.CODE_SERVER_BUSY.getCode();
        }
    }

    /**
     * 取出 gRPC status —— <b>沿 cause 链向上找</b>。
     *
     * <p>⚠️ <b>不是只做 {@code instanceof}</b>：gRPC-Java 的异步 stub 会抛 {@code CompletionException}、
     * Spring 的 AOP 也常包一层 ⇒ 只做 instanceof 会直接落到兜底 1015，
     * 而解包后应按 gRPC code class 映射（NOT_FOUND ⇒ 5002 等）。
     *
     * @param err 任意异常
     * @return 命中的 status 载体；链上无 status 时返回 {@code null}
     */
    private static FoundStatus extractStatus(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof StatusException se) {
                return new FoundStatus(se.getStatus(), t != err);
            }
            if (t instanceof StatusRuntimeException sre) {
                return new FoundStatus(sre.getStatus(), t != err);
            }
            // 防环：自引用的 cause 会让上面的循环变成死循环
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /**
     * 「找到了 status」的返回载体。
     *
     * @param status  命中的 gRPC status
     * @param wrapped 是否沿 cause 链<b>解包</b>命中（{@code false} = 直接命中）
     */
    private record FoundStatus(Status status, boolean wrapped) {
    }
}
