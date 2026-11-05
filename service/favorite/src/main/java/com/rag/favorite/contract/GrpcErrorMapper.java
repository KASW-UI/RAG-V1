package com.rag.favorite.contract;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>favorite 域 gRPC 错误契约</b> —— <b>业务码 ⇄ gRPC status 的<b>互逆往返</b></b>。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/FavoriteGrpcError.java:70-243}
 *
 * <h2>⚠️ 与 like 域的根本差异：<b>业务码靠「文案反查」还原</b></h2>
 * <ul>
 *   <li><b>like 域</b>把业务码塞进 <b>trailer</b>（{@code rag-like-biz-code}）⇒ 还原靠读 trailer，
 *       精确无损；</li>
 *   <li><b>favorite 域</b><b>没有</b> trailer 机制 ——
 *       {@code grpcError} 写的 description 是<b>业务文案</b>（不是码数字），
 *       <b>业务码在 gRPC status 的 code 字段里是「丢失」的</b>
 *       ⇒ {@code bizCodeFromError} <b>只能靠文案反查</b>。</li>
 * </ul>
 * ⇒ 🔴 <b>这是本域错误契约的形状所在</b>：本类<b>刻意不引入自定义 trailer</b> ——
 * 加一个 B 侧不存在的 wire 元素属于<b>新增行为</b>，且会让两侧的 wire 形态不再对称
 * （B 侧 {@code FavoriteGrpcError} 的类 javadoc 明确写了这一点）。
 *
 * <h2>⚠️ 反查顺序：<b>先 message、后 gRPC 标准码</b></h2>
 * <p>标准码 fallback 白名单<b>恰好 6 个</b>：
 * {@code InvalidArgument→5003} / {@code Unauthenticated→5004} / {@code PermissionDenied→5005} /
 * {@code AlreadyExists→5006} / {@code NotFound→5002} / {@code Internal→5001} / {@code default→1015}。
 * <p>⚠️ <b>不要因为「{@code FailedPrecondition} 看着该映射」就加进白名单</b>：
 * B 侧没有这条映射，加上会让本域最常见的「收藏暂不可撤回」在<b>文案丢失</b>时
 * 从 1015 变成 5304 —— 那是<b>新增行为</b>，不是对齐。
 *
 * <h2>⚠️ 反查表构建<b>必须 {@code putIfAbsent}</b></h2>
 * <p>B 侧用 {@code if _, exists := lookup[msg]; !exists { lookup[msg] = code } }（first-write-wins）
 * ⇒ C 侧用 {@link Map#putIfAbsent} 表达同一语义。
 * ⚠️ 当前 24 条文案两两互不相同（由 {@code FavoriteErrorMessagesContractTest#messagesArePairwiseDistinct} 钉死），
 * 该去重分支实际不可达，但<b>语义必须保留</b>。
 *
 * <h2>⚠️ 与 like 域的第二个关键差异：<b>禁止为「躲开熔断失败码」而改写 grpc 码</b></h2>
 * <p>B 侧 favorite 域<b>本来就大量发</b> {@code Internal} 与 {@code Unavailable}
 * ⇒ B 侧<b>确实</b>把这些计入依赖失败统计。
 * ⇒ {@link #grpcError} <b>原样透传</b>调用方给的 gRPC 码；
 * 若在此处把它们改写成 {@code UNKNOWN}，就会让 favorite 的 DB 故障不再计入熔断，
 * 那<b>是新增行为，不是对齐</b>。
 *
 * <h2>框架职责划分</h2>
 * <ul>
 *   <li><b>Spring 不参与</b>：本组件是 gRPC 错误契约，与 HTTP 无关；</li>
 *   <li><b>grpc-java 承担传输</b>：{@link Status} / {@link StatusException} 由
 *       {@code io.grpc:grpc-stub} 提供，server runtime 负责把它写上 wire；</li>
 *   <li><b>业务码 ↔ 文案的映射表由本类持有</b>：grpc-java 不认识 favorite 的 24 个业务码，
 *       也没有任何「按 description 反查业务码」的机制 ⇒ 这部分必须自定义。</li>
 *   <li><b>本类不定义业务异常类型</b>：B 侧 favorite 域<b>没有</b> {@code CodeError} 结构体
 *       ⇒ Java 侧也不该凭空造一个 ——
 *       否则 {@code bizCodeFromError} 会多出一条 B 侧不存在的分支。</li>
 * </ul>
 */
public final class GrpcErrorMapper {

    private GrpcErrorMapper() {
        // 工具类，禁止 new
    }

    /**
     * 对应 B 侧 {@code status.Error(codes.Code(code), msg)}：
     * 以给定的<b>标准</b> gRPC 码构造 status，description 放业务码查表得到的文案。
     *
     * <p><b>⚠️ 调用方必须交给 {@code responseObserver.onError(...)}，不能 {@code throw}</b>：
     * grpc-java 在 {@code UnaryServerCallListener.onHalfClose} 里调用业务方法，
     * 在那里 {@code throw} 会让异常逃出流监听回调，被当成<b>传输层内部错误</b>，
     * description 被替换成 {@code "Application error processing RPC"} —— <b>文案当场全丢</b>，
     * 而文案正是本域业务码的载体（丢了就再也还原不出业务码）。
     *
     * <p>用法：
     * <pre>{@code
     * responseObserver.onError(GrpcErrorMapper.grpcError(
     *         Status.Code.INTERNAL, ErrCodes.ERROR_DB_SELECT));
     * }</pre>
     *
     * @param grpcCode 标准 gRPC 码（<b>原样透传</b>，不做任何改写）
     * @param bizCode  favorite 业务码（description 取 {@link ErrMsg#getMsg(int)} 的文案）
     * @return 可直接交给 {@code onError} 的 {@link StatusException}
     */
    public static StatusException grpcError(Status.Code grpcCode, int bizCode) {
        return Status.fromCode(grpcCode)
                .withDescription(ErrMsg.getMsg(bizCode))
                .asException();
    }

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
     *   <li>first-write-wins 去重 ⇒ {@link Map#putIfAbsent}。</li>
     * </ul>
     *
     * <p><b>这张表是本组件的核心而非附属</b>：本域唯一能让「非标准码」往返的办法就是文案反查。
     * 最硬的证据是 {@link ErrCodes#ERROR_FAVORITE_HISTORY_BLOCKED} = 5304 ——
     * {@code FAILED_PRECONDITION} 不在 B 侧 {@code BizCodeFromError} 的 switch 白名单里，
     * 还原 5304 <b>只能</b>靠这张表。
     */
    private static final Map<String, Integer> BIZ_CODE_BY_MSG;

    static {
        Map<String, Integer> reverse = new HashMap<>();
        for (Map.Entry<Integer, String> entry : ErrMsg.msgByCode().entrySet()) {
            int code = entry.getKey();
            if (code == ErrCodes.SUCCESS || code == ErrCodes.ERROR) {
                continue;
            }
            reverse.putIfAbsent(entry.getValue(), code);
        }
        BIZ_CODE_BY_MSG = Collections.unmodifiableMap(reverse);
    }

    /**
     * 对应 B 侧 {@code func bizCodeFromMessage(msg string) (int, bool)}（未导出）。
     *
     * <p>本类内私有：B 侧也没有导出它，唯一调用点是 {@link #bizCodeFromError}。
     */
    private static Integer bizCodeFromMessage(String msg) {
        return BIZ_CODE_BY_MSG.get(msg);
    }

    /**
     * 对应 B 侧 {@code func BizCodeFromError(err error) int}：从任意 error 里还原 favorite 业务码。
     *
     * <p><b>判定顺序即语义（顺序不可调换）</b>：
     * <ol>
     *   <li>{@code err == null} → {@link ErrCodes#SUCCESS}（200）——防御性取值；</li>
     *   <li><b>不是 gRPC status</b> → {@link ErrCodes#CODE_SERVER_BUSY}（1015）。
     *       B 侧 {@code status.FromError(err)} 对普通 error（如 DB error）返回 {@code ok=false}；
     *       favorite-rpc 大量把裸 DB error 传进来 ⇒ 这条分支是热路径；</li>
     *   <li><b>是 gRPC status</b> → 先按 description <b>反查文案</b>，命中即返回
     *       （这是还原 5201/5202/5203/5301-5304/5101-5105 等非标准业务码的唯一途径）；</li>
     *   <li>反查未命中 → 按标准 gRPC 码类别兜底映射，见 {@link #mapStatusCodeToBizCode}；</li>
     *   <li>兜底也未命中（{@code default}）→ {@link ErrCodes#CODE_SERVER_BUSY}（1015）。</li>
     * </ol>
     *
     * @param err 任意异常（可空）
     * @return favorite 业务码
     */
    public static int bizCodeFromError(Throwable err) {
        if (err == null) {
            return ErrCodes.SUCCESS;
        }

        Status status = extractStatus(err);
        if (status == null) {
            // B 侧: status.FromError 返回 ok=false ⇒ return CodeServerBusy
            return ErrCodes.CODE_SERVER_BUSY;
        }

        Integer fromMsg = bizCodeFromMessage(status.getDescription());
        if (fromMsg != null) {
            return fromMsg;
        }

        return mapStatusCodeToBizCode(status.getCode());
    }

    /**
     * 对应 B 侧 {@code BizCodeFromError} 末尾的 {@code switch st.Code()}。
     *
     * <p>白名单<b>恰好 6 个</b>，其余（含本域常用的 {@code FailedPrecondition} 与 {@code Unavailable}）
     * 一律走 {@code default} → 1015。
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
     * 同时识别 server 端 {@link StatusException} 与 client 端 {@link StatusRuntimeException}。
     *
     * <p><b>⚠️ 这里刻意<b>不</b>沿 cause 链向上找</b>：B 侧的 {@code status.FromError(err)}
     * 只检查<b>顶层</b> error 是否实现 {@code GRPCStatus()}，不遍历 cause 链；
     * 而 grpc-java 的 {@code Status.fromThrowable} 会沿 cause 链回溯。
     * 若直接用 {@code Status.fromThrowable}，一个被业务代码包了一层的
     * {@code StatusException} 在 Java 侧会还原出业务码、而在 B 侧会落 1015 ——
     * 两侧分叉。故此处按 B 侧的单层语义手写判定。
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
}
