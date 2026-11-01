package com.rag.common.autoconfigure;

import com.rag.common.exception.BusinessException;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>D2 / D5「统一错误模型」的 gRPC 边界适配器</b> ——
 * {@link BusinessException}（业务码 + 业务文案）与 gRPC status 之间的<b>双向</b>转换。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ B 出处（这是 wire 可见的 L1 契约，必须逐条对齐）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <pre>
 *   Sea service/&lt;域&gt;/common/errmsg/errmsg.go:30-35
 *       func NewGrpcErr(code int, msg string) error {
 *           return status.Error(codes.Code(code), msg)      // ← 业务码当 gRPC status code
 *       }
 *
 *   Sea service/&lt;域&gt;/rpc/internal/logic/*.go（rpc 层<b>真的</b>用它）
 *       return nil, errmsg.NewGrpcErr(errmsg.ErrorInputWrong, "输入需要查询的ID为空")
 *
 *   Sea service/&lt;域&gt;/api/internal/logic/*.go（api 层<b>反读</b>它）
 *       return nil, errmsg.NewErrCodeMsg(int(st.Code()), st.Message())
 *          //                        ↑ gRPC status code = 业务码    ↑ description = 业务文案
 * </pre>
 * ⇒ ⇒ <b>B 侧 gRPC 边界的完整形状</b>：
 * <ul>
 *   <li><b>status code（数值）</b> = <b>SEA 业务码</b>（如 1001 / 5001 / 1004）；</li>
 *   <li><b>description</b> = <b>业务文案</b>（原样，无任何包装）。</li>
 * </ul>
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L3 降级（🔴 必须登记）：grpc-java <b>承载不了任意数值 status code</b></b>
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>实测依据</b>（javap 反编译 {@code io.grpc.Status#fromCodeValue(int)}，
 * grpc-api 1.63.0）：未知数值码 ⇒ <b>回落 {@code UNKNOWN}</b>（并挂一个
 * {@code "Unknown code"} cause）⇒ ⇒ <b>只有 17 个标准码</b>能原样过线。
 * <br>⇒ B 的 {@code codes.Code(1001)} ⇒ Java 侧只能落到 {@code UNKNOWN}（数值 2）。
 * <br>⇒ ⇒ <b>业务码必须另找载体</b> ⇒ 本类用 <b>trailer 元数据</b>承载：
 * <pre>
 *   trailer key = "sea-biz-code"   value = 十进制业务码
 * </pre>
 * ⇒ ⇒ <b>可观察差异（两条，都要登记）</b>：
 * <ol>
 *   <li><b>status code 数值</b>：B 侧 = 业务码（如 1001）· 本仓 = {@code UNKNOWN}(2) ⇒ <b>L3</b>；</li>
 *   <li><b>多一个 trailer</b>（{@code sea-biz-code}）⇒ B 侧没有 ⇒ <b>L3</b>。</li>
 * </ol>
 * ⇒ ⇒ <b>description 逐字一致</b>（都只是业务文案，无包装）⇒ 那一半<b>不是</b>降级。
 *
 * <p><b>⚠️ 为什么不用「把业务码塞进 description」</b>：B 侧 rpc 层的 description
 * 就是<b>纯业务文案</b>（{@code status.Error(codes.Code(code), msg)}）⇒
 * 若塞进 {@code "ErrCode:%d, Errmsg:%s"} ⇒ description 与 B 侧 <b>不同</b> ⇒
 * 反而多出一处 wire 差异 ⇒ ⇒ 业务码走 trailer、description 保持原样。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ HTTP 边界适配器为什么<b>不在</b>本类</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>HTTP 侧要产出的东西是「业务码 + 文案 ⇒ <b>HTTP 状态码 + 信封</b>」，
 * 而<b>业务码空间是逐域隔离的</b>（AGENTS.md §5.2 R1 / 决策锁 L-11）
 * ⇒ <b>HTTP 状态码的判定规则必须留在各域</b>（域内 {@code advice/} 包）
 * ⇒ ⇒ 共享层只提供<b>信封形状</b>（{@code common/contract/ApiResponse}，L1 · K-06）。
 * <br>⇒ ⚠️ 抽取共享基座会踩 AGENTS.md §八「未统一化 —— 错误码值 / 文案 / 前缀<b>逐域隔离</b>」。
 *
 * <p><b>抄写基准</b>：B 侧 {@code service/&lt;域&gt;/common/errmsg/errmsg.go} 的
 * {@code NewGrpcErr} / {@code NewErrCodeMsg}（<b>L1 契约层以 B 侧 为准</b> · D13）
 * ＋ Java 侧的载体改造（<b>L4 · D4</b>）⇒ 登记为 L3 降级。
 */
@AutoConfiguration
@ConditionalOnClass({ServerInterceptor.class, ClientInterceptor.class})
public class GrpcExceptionMapperAutoConfiguration {

    /**
     * 承载业务码的 trailer 键（gRPC 元数据键必须是<b>小写</b> ASCII）。
     *
     * <p>⚠️ 这是<b>本仓新增</b>的元数据（B 侧无）⇒ 属 L3 降级的载体（见类注释）。
     */
    public static final String BIZ_CODE_TRAILER_KEY = "sea-biz-code";

    private static final Metadata.Key<String> BIZ_CODE_TRAILER =
            Metadata.Key.of(BIZ_CODE_TRAILER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    /**
     * <b>服务端</b>边界：把 handler 抛出的 {@link BusinessException} 映射成 B 侧 形状的 gRPC status。
     *
     * <p><b>⚠️ 为什么需要它（grpc-java 与 B 的真实差异）</b>：
     * <ol>
     *   <li>grpc-java 的 {@code ServerCallStreamObserverImpl#onError(Throwable)}
     *       ⇒ {@code Status.fromThrowable(t)} ⇒ 对<b>非</b> {@code StatusException} 的异常
     *       ⇒ {@code UNKNOWN.withCause(t)} ⇒ <b>description 为 null</b>、cause 挂在 status 上；</li>
     *   <li>B 侧 {@code status.Error(codes.Code(code), msg)} ⇒ <b>description = msg</b>。</li>
     * </ol>
     * ⇒ ⇒ 差异 = <b>description 丢失</b> + <b>status code 不可承载</b>（见类注释的 L3）。
     * <br>⇒ ⇒ 本拦截器把 close 时的 status <b>改写</b>回 B 的形状。
     *
     * <p><b>⚠️ 改写的判据是「status 的 cause 是 BusinessException」</b>，
     * <b>不是</b>「code == INTERNAL」之类的猜测 ⇒ ⇒ 其它异常路径<b>一字不动</b>。
     */
    @Bean
    @ConditionalOnMissingBean(name = "seaGrpcBusinessExceptionServerInterceptor")
    public ServerInterceptor seaGrpcBusinessExceptionServerInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                ServerCall<ReqT, RespT> wrapped =
                        new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                            @Override
                            public void close(Status status, Metadata trailers) {
                                BusinessException business = businessExceptionOf(status);
                                if (business == null) {
                                    super.close(status, trailers);
                                    return;
                                }
                                Metadata out = trailers == null ? new Metadata() : trailers;
                                out.put(BIZ_CODE_TRAILER, String.valueOf(business.getCode()));
                                // ⚠️ description = **业务文案原样**（与 B 的 status.Error(codes.Code(code), msg) 一致）
                                super.close(Status.UNKNOWN.withDescription(messageOf(business)).withCause(business), out);
                            }
                        };
                return next.startCall(wrapped, headers);
            }
        };
    }

    /**
     * <b>客户端</b>边界：把 trailer 里的业务码<b>还原</b>成 {@link BusinessException}。
     *
     * <p>⚠️ <b>为什么客户端也需要它</b>：跨域调用（D6）时，调用方拿到的 status code 恒为
     * {@code UNKNOWN}（grpc-java 承载不了业务码）⇒ 若不还原，调用方<b>永远拿不到业务码</b>
     * ⇒ ⇒ 跨域的错误处理就退化成「只有文案」⇒ 与 B 的
     * {@code NewErrCodeMsg(int(st.Code()), st.Message())} 不等价。
     *
     * <p><b>还原方式</b>：把业务码包成一个 {@link BusinessException} 挂到 status 的 <b>cause</b> 上
     * ⇒ 调用方用 {@link #toBusinessException(StatusRuntimeException)} 取回
     * ⇒ ⇒ <b>不改 description</b>（wire 上的文案逐字不动）。
     */
    @Bean
    @ConditionalOnMissingBean(name = "seaGrpcBusinessExceptionClientInterceptor")
    public ClientInterceptor seaGrpcBusinessExceptionClientInterceptor() {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    CallOptions callOptions,
                    Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        // ⚠️ **客户端侧没有 close(status, trailers)**（javap 实测：grpc-api 1.63.0 的
                        //    `ForwardingClientCall` 只有 halfClose / cancel / request / start）
                        // ⇒ ⇒ 状态码与 trailer 只出现在 **`Listener#onClose(status, trailers)`** 里
                        // ⇒ ⇒ 在这里做还原。
                        super.start(new ForwardingClientCallListener
                                .SimpleForwardingClientCallListener<RespT>(responseListener) {
                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                String raw = trailers == null ? null : trailers.get(BIZ_CODE_TRAILER);
                                if (raw == null) {
                                    super.onClose(status, trailers);
                                    return;
                                }
                                int code;
                                try {
                                    code = Integer.parseInt(raw.trim());
                                } catch (NumberFormatException e) {
                                    super.onClose(status, trailers);
                                    return;
                                }
                                BusinessException business =
                                        new BusinessException(code, status.getDescription());
                                super.onClose(status.withCause(business), trailers);
                            }
                        }, headers);
                    }
                };
            }
        };
    }

    /**
     * 把客户端拿到的 gRPC 异常还原成 {@link BusinessException}（业务码 + 文案）。
     *
     * <p><b>优先级</b>：cause 里的 {@link BusinessException}（本仓的客户端拦截器挂的）
     * ⇒ 优先；否则按 B 的 {@code NewErrCodeMsg(int(st.Code()), st.Message())} 口径
     * 用 status 的 code + description 兜底。
     *
     * @param e 客户端收到的异常
     * @return 还原出的业务异常；{@code e} 为 {@code null} 时返回 {@code null}
     */
    public static BusinessException toBusinessException(StatusRuntimeException e) {
        if (e == null) {
            return null;
        }
        BusinessException carried = businessExceptionOf(e.getStatus());
        if (carried != null) {
            return carried;
        }
        Status status = e.getStatus();
        return new BusinessException(status.getCode().value(), status.getDescription());
    }

    /** status 的 cause（或 cause 链）里有没有 {@link BusinessException}。 */
    private static BusinessException businessExceptionOf(Status status) {
        if (status == null) {
            return null;
        }
        Throwable cause = status.getCause();
        int guard = 0;
        while (cause != null && guard++ < 16) {
            if (cause instanceof BusinessException business) {
                return business;
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }
        return null;
    }

    /** B 侧 {@code err.Error()} 的取值口径：{@code getMessage()} 原样，{@code null} 归一为 {@code ""}。 */
    private static String messageOf(BusinessException business) {
        String msg = business.getMessage();
        return msg != null ? msg : "";
    }
}
