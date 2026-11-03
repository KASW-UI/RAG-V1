package com.rag.like.contract;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

/**
 * <p><b>like 域 gRPC 错误构造与业务码还原</b>（L1 契约层 · 域内私有）—— 对应 B 侧
 * {@code service/like/like-common/src/main/java/com/rag/like/like/common/errmsg/LikeGrpcError.java:40-258}。
 *
 * <h2>本域的 wire 形态：业务码靠<b>自定义 trailer</b>承载</h2>
 * <p>B 侧 like 域发出错误时的形状是：
 * <pre>
 * func NewGrpcErr(code int, msg string) error {
 *     return status.Error(codes.Code(code), msg)   // ← 把 like 业务码强转成 gRPC 状态码
 * }
 * </pre>
 * ⇒ grpc-go 的 {@code codes.Code} 是 {@code int32} 的<b>命名类型，不做范围校验</b>
 * ⇒ 服务端写 trailer 时执行 {@code strconv.Itoa(int(s.Code()))} ⇒ 线上真实字节是
 * {@code grpc-status: 5003}。
 *
 * <h3>🔴⚠️ 平台强制偏离：grpc-java 无法发出越界 {@code grpc-status}</h3>
 * <p>grpc-java 的 {@link Status} 只认 <b>17 个标准码</b>（{@code Status.Code.values().length == 17}，
 * 合法值 0–16），公开 API 里没有任何构造任意码值的入口 —— {@code Status.fromCode} 的签名
 * 只接受 {@code Code} 枚举，传 int 连编译都过不了。实测（grpc-java <b>1.63.0</b>，
 * 即本仓 {@code pom.xml} 的 {@code <grpc.version>}）：
 * <pre>
 * Status.fromCodeValue(5003) → code = UNKNOWN, value = 2, desc = "Unknown code 5003"
 * Status.fromCodeValue(2001) → code = UNKNOWN, value = 2, desc = "Unknown code 2001"
 * Status.fromCodeValue(5012) → code = UNKNOWN, value = 2, desc = "Unknown code 5012"
 * </pre>
 * ⇒ 业务码与原始 msg <b>双双丢失</b>（{@code fromCodeValue} 会用 {@code "Unknown code N"}
 * 覆盖原 description）。
 * ⇒ <b>往 trailing Metadata 里塞 {@code grpc-status} 也无效</b>：grpc-java 在
 * {@code ServerCallImpl.close} 里用 {@code status.getCode().value()} 覆写该 trailer。
 *
 * <h3>⇒ 本类采取「<b>等价信息保全 + 载体迁移</b>」</h3>
 * <ul>
 *   <li><b>业务码</b>（B 放在 grpc-status）→ 放进自定义 trailer
 *       {@link #BIZ_CODE_TRAILER_KEY} 的 <b>ASCII 数值</b>，实测可无损抵达客户端；</li>
 *   <li><b>文案</b>（B 放在 description）→ 仍放 description，<b>逐字不变</b>，
 *       因此客户端最终呈现给用户的 msg 与 B 完全一致；</li>
 *   <li><b>gRPC 标准码</b> → 只用于「错误类别」，且必须避开熔断失败名单，见 {@link #mapToGrpcCode}。</li>
 * </ul>
 * <p><b>净差异</b>：仅「grpc-status 字段的数值」这一项不可复原（平台限制，非实现选择）；
 * 业务码、文案、错误分类三者对客户端仍然完全可得且无损。
 *
 * <h3>⚠️ 与本仓共享层 {@code sea-biz-code} trailer 的关系</h3>
 * <p>共享层 {@code com.rag.common.autoconfigure.GrpcExceptionMapperAutoConfiguration}
 * 另有一套 {@code sea-biz-code} trailer 机制（用于承载 {@code BusinessException}）。
 * <b>本类不共用它</b>，原因：
 * <ol>
 *   <li><b>B 侧 like 域的载体就是 {@code rag-like-biz-code}</b>（B 侧 {@code LikeGrpcError.java:79}）
 *       ⇒ 换键名 = 改 wire 元素 = 改 L1 契约（D16：L1 以 B 侧为准）；</li>
 *   <li>共享层那套的触发条件是「status 的 cause 是 {@code BusinessException}」，
 *       而本域的领域异常是 {@code exception/LikeExceptions.Rpc}（携带 {@code bizCode}）
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
     * 自定义 trailer key，承载 like 业务码（B 侧该值位于 {@code grpc-status}）。
     *
     * <p>名字刻意加 {@code rag-} 前缀：gRPC 规范允许应用自定义 trailer，
     * 加前缀可避免与未来标准 trailer 撞名。
     *
     * <p>⚠️ <b>与 B 侧逐字一致</b>（B 侧 {@code LikeGrpcError.java:79}）⇒ 不可改名。
     */
    public static final String BIZ_CODE_TRAILER_KEY = "rag-like-biz-code";

    /** 上述 trailer 的强类型访问器。 */
    public static final Metadata.Key<String> BIZ_CODE_TRAILER =
            Metadata.Key.of(BIZ_CODE_TRAILER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    private GrpcErrFactory() {
        // 工具类，禁止 new
    }

    /**
     * like 业务码 → gRPC 标准码的<b>类别</b>映射。
     *
     * <h3>⚠️ 这里的选型直接决定熔断会不会误开</h3>
     * go-zero 客户端的 {@code codes.Acceptable} 只把下面 6 个码记为<b>失败</b>：
     * <pre>
     * DeadlineExceeded / Internal / Unavailable / DataLoss / Unimplemented / ResourceExhausted
     * </pre>
     * Go like-rpc 返回的是 {@code codes.Code(5003)} / {@code codes.Code(2001)} …，
     * <b>一个都不在那 6 个里</b> ⇒ 业务错误一律被记为 <b>Accept</b>，不参与熔断统计。
     *
     * <p>⇒ Java 侧选的标准码<b>必须同样不落在那 6 个里</b>，否则每次业务错误都会被计成
     * 依赖故障、熔断误开、打掉正常流量 —— 那是<b>新增行为</b>，不是对齐。
     *
     * <p>本映射：
     * <ul>
     *   <li>{@link ErrCodes#ERROR_INPUT_WRONG} → {@link Status.Code#INVALID_ARGUMENT}
     *       —— 语义最贴近，且不在失败名单里 ⇒ Accept，与 Go 一致；</li>
     *   <li>其余 → {@link Status.Code#UNKNOWN} —— <b>刻意不用 INTERNAL</b>：
     *       {@code INTERNAL} 正在失败名单内，用它会让「DB 查询失败 / Redis 更新失败」
     *       这类正常业务错误全部计成熔断失败。
     *       {@code UNKNOWN} 与 Go 的「码无法归类」实情相符，且不在失败名单内。</li>
     * </ul>
     */
    private static Status.Code mapToGrpcCode(int bizCode) {
        if (bizCode == ErrCodes.ERROR_INPUT_WRONG) {
            return Status.Code.INVALID_ARGUMENT;
        }
        return Status.Code.UNKNOWN;
    }

    /**
     * 对应 Go {@code status.Error(codes.Code(code), msg)}。
     *
     * <p>⚠️ <b>调用方必须交给 {@code observer.onError(...)}，不能 {@code throw}</b>（见类注释）。
     *
     * @param bizCode like 业务码
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
     * 对应 Go like-api 侧 {@code st, ok := status.FromError(err); int(st.Code())} 的<b>还原</b>方向：
     * 从任意 Throwable 里恢复 like 业务码。
     *
     * <p>判定优先级（顺序即语义）：
     * <ol>
     *   <li><b>trailer</b> —— 本域自己发出去的错误，无损、最权威；</li>
     *   <li><b>灰度期降级形态</b> —— grpc-java 对越界 {@code grpc-status} 生成的
     *       {@code "Unknown code 5003"} description ⇒ 从文案里尽力捞回码；</li>
     *   <li>{@link CodeError} —— 进程内直接抛出的业务异常；</li>
     *   <li><b>gRPC 标准码兜底</b> —— 收到别的域/框架发的错误时，按类别反推：
     *       {@code INVALID_ARGUMENT→2001}、{@code UNAUTHENTICATED→1009}、
     *       {@code PERMISSION_DENIED→1008}、其余→{@link ErrCodes#CODE_SERVER_BUSY}</li>
     * </ol>
     *
     * <p><b>灰度期说明</b>：若 like 调到<b>尚未迁移的 Go like-rpc</b>，Go 发的是
     * {@code grpc-status: 5003} 且无本类 trailer，grpc-java 会降级为
     * {@code UNKNOWN + "Unknown code 5003"}。此分支只能从 description 里<b>尽力捞回码</b>，
     * <b>msg 无法恢复</b>（已被上游覆盖），调用方应按
     * {@link ErrMsg#getMsg(int)} 用捞回的码补文案。
     *
     * <p>{@code null} 输入返回 {@link ErrCodes#SUCCESS}。这是<b>防御性取值</b>：
     * Go 侧对应调用点都在 {@code if err != nil} 之内，该分支实际不可达，
     * 因此不构成与 Go 的行为差异。
     *
     * @param err 任意异常（可为 null）
     * @return like 业务码
     */
    public static int bizCodeFromError(Throwable err) {
        if (err == null) {
            return ErrCodes.SUCCESS;
        }

        Status status = extractStatus(err);
        if (status != null) {
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
                        // 落到下面的标准码兜底，不让脏 trailer 让整条错误解析失败
                    }
                }
            }
            return mapGrpcCodeToBizCode(status.getCode());
        }

        if (err instanceof CodeError codeError) {
            return codeError.getCode();
        }

        return ErrCodes.CODE_SERVER_BUSY;
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

    /**
     * 标准 gRPC 码 → like 业务码的兜底反查。
     *
     * <p>与 {@link #mapToGrpcCode(int)} 不构成双射（like 的 29 个码只分两类），
     * 这里只保证「能确定类别时给出该类别的代表码」，不确定时给 1015（服务繁忙）。
     *
     * <p>注意 {@code UNKNOWN} 落在 default 分支：它同时也是本类自己发出的业务错误码，
     * 但那条路径<b>一定带 trailer</b>，会在此之前就被更精确地命中，不会走到这里。
     */
    private static int mapGrpcCodeToBizCode(Status.Code grpcCode) {
        if (grpcCode == null) {
            return ErrCodes.CODE_SERVER_BUSY;
        }
        switch (grpcCode) {
            case INVALID_ARGUMENT:
                return ErrCodes.ERROR_INPUT_WRONG;
            case UNAUTHENTICATED:
                return ErrCodes.ERROR_USER_NO_LOGIN;
            case PERMISSION_DENIED:
                return ErrCodes.ERROR_USER_NO_RIGHT;
            default:
                return ErrCodes.CODE_SERVER_BUSY;
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
}
