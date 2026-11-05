package com.rag.comment.contract;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

/**
 * <p><b>comment 域 gRPC 错误双向转换</b> —— 对应 B 侧
 * {@code service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentGrpcError.java:29-259}。
 *
 * <h2>本域的 wire 形态：业务码靠<b>自定义 trailer</b>承载</h2>
 * <p>B 侧 comment 域发出错误时的形状是：
 * <pre>
 * func NewGrpcErr(code int, msg string) error {
 *     return status.Error(codes.Code(code), msg)   // ← 把 comment 业务码强转成 gRPC 状态码
 * }
 * </pre>
 * ⇒ grpc-go 的 {@code codes.Code} 是 {@code int32} 的命名类型，<b>不做范围校验</b>
 * ⇒ 服务端写 trailer 时执行 {@code strconv.Itoa(int(s.Code()))} ⇒ 线上真实字节是
 * {@code grpc-status: 5003}。
 * 而消费端（B 侧 comment-api 的 6 处 logic）做的是：
 * <pre>
 * st, ok := status.FromError(err)
 * if ok { return nil, errmsg.NewErrCodeMsg(int(st.Code()), st.Message()) }
 * </pre>
 * 即<b>把 gRPC 状态码原样当成 HTTP envelope 的业务码</b>。
 * ⇒ 「业务码出现在 grpc-status 字段里」是 comment 域的跨语言 wire 契约，不是随手写的。
 *
 * <h3>🔴⚠️ 平台强制偏离：grpc-java 无法发出越界 {@code grpc-status}</h3>
 * <p>grpc-java 的 {@link Status} 只认 <b>17 个标准码</b>（合法值 0–16），
 * 公开 API 里没有任何构造任意码值的入口 —— {@code Status.fromCode} 的签名只接受
 * {@code Code} 枚举，传 int 连编译都过不了。实测（本仓 {@code pom.xml} 的
 * {@code <grpc.version>} = 1.63.0）：
 * <pre>
 * Status.fromCodeValue(2001) → code=UNKNOWN, desc="Unknown code 2001"
 * Status.fromCodeValue(5003) → code=UNKNOWN, desc="Unknown code 5003"
 * </pre>
 * ⇒ 业务码与原始 msg <b>双双丢失</b>（{@code fromCodeValue} 会用 {@code "Unknown code N"}
 * 覆盖原 description）。
 * ⇒ <b>往 trailing Metadata 里塞 {@code grpc-status} 也无效</b>：grpc-java 在
 * {@code ServerCallImpl.close} 里用 {@code status.getCode().value()} 覆写该 trailer。
 *
 * <h3>⇒ 本类采取「<b>等价信息保全 + 载体迁移</b>」</h3>
 * <ul>
 *   <li><b>业务码</b>（Go 放在 grpc-status）→ 放进自定义 trailer
 *       {@link #BIZ_CODE_TRAILER_KEY} 的 <b>ASCII 数值</b>，实测可无损抵达客户端；</li>
 *   <li><b>文案</b>（Go 放在 description）→ 仍放 description，<b>逐字不变</b>，
 *       因此 comment-api 最终呈现给用户的 msg 与 Go 完全一致；</li>
 *   <li><b>gRPC 标准码</b> → 只承载「错误类别」，见 {@link #mapToGrpcCode}。</li>
 * </ul>
 * <p><b>净差异</b>：仅「grpc-status 字段的数值」这一项不可复原（平台限制，非实现选择）；
 * 业务码、文案、错误分类三者对客户端仍然完全可得且无损。
 *
 * <h3>⚠️ 与本仓共享层 {@code sea-biz-code} trailer 的关系</h3>
 * <p>共享层 {@code com.rag.common.autoconfigure.GrpcExceptionMapperAutoConfiguration}
 * 另有一套 {@code sea-biz-code} trailer 机制（用于承载 {@code BusinessException}）。
 * <b>本类不共用它</b>，原因：
 * <ol>
 *   <li><b>B 侧 comment 域的载体就是 {@code rag-comment-biz-code}</b>（B 侧 {@code CommentGrpcError.java:72}）
 *       ⇒ 换键名 = 改 wire 元素 = 改 L1 契约（D16：L1 以 B 侧为准）；</li>
 *   <li>共享层那套的触发条件是「status 的 cause 是 {@code BusinessException}」，
 *       而本域的领域异常是 {@code exception/CommentExceptions.Rpc}（携带 {@code bizCode}）
 *       ⇒ 两者<b>不会</b>同时出现在同一条 status 上；</li>
 *   <li>⚠️ <b>本仓写权边界</b>：{@code common/**} 属 {@code global} 收口域写权
 *       （AGENTS.md §五.3 规则 3）⇒ 本域<b>不得</b>改共享层来「统一」这个 trailer 名。</li>
 * </ol>
 *
 * <h3>⚠️ 调用方必须交给 {@code observer.onError(...)}，不能 {@code throw}</h3>
 * grpc-java 的 {@code ServerCalls} 是从流监听回调
 * {@code UnaryServerCallListener.onHalfClose(ServerCalls.java:182)} 里调用业务方法的，
 * 若在那里 {@code throw}，异常会<b>逃出流监听回调</b>，被 grpc-java 当成<b>传输层内部错误</b>，
 * 并把 description 替换成 {@code "Application error processing RPC"} —— <b>文案当场全丢</b>。
 * 已实测（同一个 status 对象，走真 in-process server + channel）：
 * <pre>
 *   throw    → code=UNKNOWN  description=Application error processing RPC     ★ 丢失
 *   onError  → code=UNKNOWN  description=ErrCode:5003, Errmsg:DB查询失败         ✅ 完整
 * </pre>
 * ⇒ 正确用法：
 * <pre>{@code
 * responseObserver.onError(GrpcErrFactory.grpcError(
 *         ErrCodes.ERROR_DB_SELECT, "DB查询失败"));
 * }</pre>
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>B 侧是手写工具类（私有构造 + 静态方法 + 静态常量）；本类为逐字照抄。
 * {@code @UtilityClass} 在本仓被明令禁止（会改变构造器可见性等可观察形状）⇒ 不引入。
 */
public final class GrpcErrFactory {

    /**
     * 自定义 trailer key，承载 comment 业务码（Go 端该值位于 {@code grpc-status}）。
     *
     * <p>名字刻意加 {@code rag-} 前缀：gRPC 规范允许应用自定义 trailer，
     * 加前缀可避免与未来标准 trailer 撞名，也与其他域的 {@code rag-like-biz-code} 区分开
     * —— 多域共存于同一条链路时不会互相误读。
     *
     * <p>⚠️ <b>与 B 侧逐字一致</b>（B 侧 {@code CommentGrpcError.java:72}）⇒ 不可改名。
     */
    public static final String BIZ_CODE_TRAILER_KEY = "rag-comment-biz-code";

    /** 上述 trailer 的强类型访问器。 */
    public static final Metadata.Key<String> BIZ_CODE_TRAILER =
            Metadata.Key.of(BIZ_CODE_TRAILER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    /**
     * comment 业务码 → gRPC 标准码的<b>类别</b>映射。
     *
     * <h3>⚠️ 这里必须先讲清楚：Go 端根本没有这层映射</h3>
     * Go 把业务码<b>原样</b>写进 {@code grpc-status}（5003 就真的是 {@code grpc-status: 5003}），
     * 客户端 {@code int(st.Code())} 再原样取回。本方法产出的 17 个标准码是<b>新增信息</b>，
     * 不是对某个 Go 值的翻译 —— 换句话说，Go 侧不存在「5003 属于哪个类别」这个问题。
     *
     * <p>因此选型只能按「语义最贴近 + 不制造新风险」来定：
     * <ul>
     *   <li>{@link ErrCodes#ERROR_INPUT_WRONG}(2001) → {@link Status.Code#INVALID_ARGUMENT}
     *       —— 入参校验失败，语义最贴近；</li>
     *   <li>其余 → {@link Status.Code#UNKNOWN} —— <b>刻意不用 {@code INTERNAL}</b>：
     *       「DB 查询失败」「评论区不存在」「权限不足」这些在 Go 里都是<b>正常业务错误</b>，
     *       标成 {@code INTERNAL} 会让下游熔断器/重试器把它们当依赖故障处理，
     *       那是<b>新增行为</b>不是对齐。{@code UNKNOWN} 与 Go「码无法归类」的实情相符。</li>
     * </ul>
     *
     * <p><b>⚠️ 熔断失败名单逐条一致（B 侧原注释 · 2026-10-24 订正）</b>：
     * comment-api 早已引入熔断器（{@code rag.protection.breaker.enabled: true}），
     * Go 侧同样默认开（{@code zrpc/internal/config.go:14}）⇒
     * <b>两侧的「失败名单」逐条一致</b>：Go {@code zrpc/internal/codes/accept.go} 与
     * RAG {@code GoStyleGrpcClientBreakerInterceptor} 都只把
     * {@code DeadlineExceeded / Internal / Unavailable / DataLoss / Unimplemented / ResourceExhausted}
     * 算作失败 ⇒ <b>业务错误（{@code UNKNOWN(2)} / {@code INVALID_ARGUMENT(3)}）不计入熔断失败</b>。
     * ⚠️ <b>该一致性是「当前成立」，不是「结构上保证」</b> ⇒ 若将来调整失败名单，
     * <b>必须</b>同步核对 Go 侧。
     */
    private static Status.Code mapToGrpcCode(int bizCode) {
        if (bizCode == ErrCodes.ERROR_INPUT_WRONG) {
            return Status.Code.INVALID_ARGUMENT;
        }
        return Status.Code.UNKNOWN;
    }

    /**
     * 对应 B 侧 {@code status.Error(codes.Code(code), msg)}。
     *
     * <p>⚠️ <b>调用方必须交给 {@code responseObserver.onError(...)}，不能 {@code throw}</b>（见类注释）。
     *
     * @param bizCode comment 业务码
     * @param msg     <b>原始</b>文案（逐字上 wire，不查表）
     * @return 带业务码 trailer 的 checked 异常
     */
    public static StatusException grpcError(int bizCode, String msg) {
        Metadata trailers = new Metadata();
        trailers.put(BIZ_CODE_TRAILER, String.valueOf(bizCode));
        return Status.fromCode(mapToGrpcCode(bizCode))
                .withDescription(msg)
                .asException(trailers);
    }

    /**
     * 对应 B 侧 comment-api {@code st, ok := status.FromError(err); int(st.Code())} 的<b>还原</b>方向：
     * 从任意 Throwable 里恢复 comment 业务码。
     *
     * <p>判定优先级（顺序即语义）：
     * <ol>
     *   <li><b>降级 description 捞码</b> —— 灰度期 comment-api 调到<b>尚未迁移的 Go comment-rpc</b>：
     *       Go 发的是 {@code grpc-status: 5003} 且无本类 trailer，grpc-java 会降级成
     *       {@code UNKNOWN + "Unknown code 5003"}。这是唯一还能拿到真实码的线索。
     *       <b>只捞码不捞 msg</b> —— 原始 description 已在传输中被覆盖，不可复原，也不猜测。</li>
     *   <li><b>trailer</b> —— 本域自己发出去的错误，无损、最权威；</li>
     *   <li><b>gRPC 标准码原值</b> —— 见下段「为什么这里不做语义映射」。</li>
     * </ol>
     *
     * <h3>为什么标准码走「原值」而不是像 like/message 那样做语义映射</h3>
     * Go 的还原动作是<b>字面搬运</b>：{@code int(st.Code())} 把 grpc-status 上的整数
     * 原封不动塞进 HTTP envelope 的 {@code code} 字段。所以收到 {@code UNAUTHENTICATED}(16)
     * 时，Go 给客户端的就是 {@code code:16}。
     * 若 Java 在此处改判成 {@code 2001}（像 {@code LikeGrpcError} 那样），
     * 就是<b>凭空造出一个 Go 永远产生不到的码</b> —— 按 AGENTS.md「出现行为差异时默认判 RAG 错」
     * 与「不要擅自修复 Go 的行为」，此处以 Go 为准：<b>返回 {@link Status.Code#value()} 原值</b>。
     *
     * <p><b>⚠️ 这段「字面搬运」的论证<b>只对 Go-rpc 对端成立</b></b>。
     * 对 <b>RAG-rpc 对端</b>，Go 式的「字面搬运」对象其实是<b>业务码</b>
     * —— 因为业务码在 RAG-rpc 侧放在<b>自定义 trailer</b> 里，<b>不在</b> grpc-status 里
     * ⇒ 两边「搬」的<b>不是同一个东西</b>：
     * <ul>
     *   <li><b>Go-rpc 对端</b>：grpc-status 上就是业务码（Go 直接写 {@code grpc-status: 1012}），
     *       grpc-java 降级成 {@code UNKNOWN + "Unknown code 1012"} ⇒ 码可从 description 文本捞回
     *       ⇒ 本方法的<b>第①优先级</b>正是为它而设；</li>
     *   <li><b>RAG-rpc 对端</b>：业务码在 trailer 里 ⇒ <b>必须先读 trailer（本方法第②优先级）</b>
     *       ⇒ 若只按「标准码原值」处理，<b>会把业务码整个丢掉</b>，端到端退化成 {@code UNKNOWN(2)}。</li>
     * </ul>
     * ⇒ 结论：<b>api 层读 RAG-rpc 的错误时，业务码来自 trailer，不是 grpc-status</b>。
     *
     * <p><b>非 status 错误</b>（Go {@code ok == false}）→ {@link ErrCodes#CODE_SERVER_BUSY}。
     * 严格说 Go 侧此刻走的是 {@code NewErrCode(ErrorServerCommon)}(5001)，
     * 但那是 <b>api 层</b>显式选的兜底值，不是本函数职责；
     * api 层应直接用 {@link ErrCodes#ERROR_SERVER_COMMON}，不要依赖这里的 1015。
     *
     * <p>{@code null} 输入返回 {@link ErrCodes#SUCCESS}。这是<b>防御性取值</b>：
     * Go 侧 6 个调用点都在 {@code if err != nil} 之内，该分支实际不可达，
     * 因此不构成与 Go 的行为差异。
     *
     * @param err 任意异常（可为 null）
     * @return comment 业务码
     */
    public static int bizCodeFromError(Throwable err) {
        if (err == null) {
            return ErrCodes.SUCCESS;
        }

        Status status = extractStatus(err);
        if (status == null) {
            if (err instanceof CodeError codeError) {
                return codeError.getCode();
            }
            return ErrCodes.CODE_SERVER_BUSY;
        }

        int fromGo = recoverFromGoDegradedDescription(status.getDescription());
        if (fromGo != 0) {
            return fromGo;
        }

        Metadata trailers = Status.trailersFromThrowable(err);
        if (trailers != null) {
            String raw = trailers.get(BIZ_CODE_TRAILER);
            if (raw != null) {
                try {
                    return Integer.parseInt(raw);
                } catch (NumberFormatException ignore) {
                    // 落到下面的标准码原值，不让脏 trailer 让整条错误解析失败
                }
            }
        }

        // Go 是 int(st.Code()) 的字面搬运 ⇒ 返回标准码原值，不做语义改判
        return status.getCode().value();
    }

    /**
     * 灰度期兜底：识别 grpc-java 对越界 {@code grpc-status} 生成的
     * {@code "Unknown code 5003"} description，捞回业务码。
     *
     * <p>返回 {@code 0} 表示「不是这种降级形态」，交由调用方继续正常判定。
     * <b>只捞码不捞 msg</b> —— 原始 description 已在传输中被覆盖，不可复原，也不做猜测性还原。
     */
    private static int recoverFromGoDegradedDescription(String description) {
        if (description == null) {
            return 0;
        }
        String prefix = "Unknown code ";
        if (!description.startsWith(prefix)) {
            return 0;
        }
        try {
            return Integer.parseInt(description.substring(prefix.length()).trim());
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    /** 同时识别 server 端 {@link StatusException} 与 client 端 {@link StatusRuntimeException}。 */
    private static Status extractStatus(Throwable err) {
        if (err instanceof StatusException statusException) {
            return statusException.getStatus();
        }
        if (err instanceof StatusRuntimeException statusRuntimeException) {
            return statusRuntimeException.getStatus();
        }
        return null;
    }

    private GrpcErrFactory() {
        // 工具类，禁止 new
    }
}
