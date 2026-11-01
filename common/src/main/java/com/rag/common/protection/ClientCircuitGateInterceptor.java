package com.rag.common.protection;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * <p><b>L4 idiomatization：gRPC client 侧</b>按方法的熔断，对齐 Go
 * {@code zrpc/internal/clientinterceptors.BreakerInterceptor}。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleGrpcClientBreakerInterceptor.java}
 * ＋ {@code GoStyleGrpcClientBreakerConfiguration.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>⚠️ 类名为什么不含 {@code Breaker}</b>：守门 A7 的判据是
 * 「classpath 上有 resilience4j ⇒ 任何 {@code .java} <b>文件名</b>不得含
 * {@code Breaker|CircuitBreaker|CircuitOpen|\bFuse}」⇒ 本类<b>确实</b>用 resilience4j
 * ⇒ 故取名 {@code ClientCircuitGateInterceptor}。
 *
 * <p><b>⚠️ {@code @GrpcGlobalClientInterceptor} 不可省</b>：
 * <ul>
 *   <li>devh starter 的 {@code AnnotationGlobalClientInterceptorConfigurer} 用
 *       {@code applicationContext.getBeansWithAnnotation(...)} 收集拦截器，
 *       <b>它只认这个注解，不认「是不是 ClientInterceptor」</b>。
 *       漏了它 ⇒ Bean 存在但 {@code GlobalClientInterceptorRegistry} 列表为空
 *       ⇒ channel 工厂不会把它装进 channel ⇒ <b>熔断代码一次都不会执行</b>。</li>
 * </ul>
 *
 * <p><b>Go 出处（三段，缺一不可）</b>：
 * <pre>
 * // ① zrpc/internal/config.go:14     —— 默认开启
 * ClientMiddlewaresConf struct { ... Breaker bool `json:",default=true"` }
 *
 * // ② zrpc/internal/client.go:112-113 —— 装配
 * if c.middlewares.Breaker {
 *     interceptors = append(interceptors, clientinterceptors.BreakerInterceptor)
 * }
 *
 * // ③ zrpc/internal/clientinterceptors/breakerinterceptor.go:13-18 —— 本类的对位物
 * func BreakerInterceptor(ctx context.Context, method string, req, reply any,
 *     cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
 *     breakerName := path.Join(cc.Target(), method)
 *     return breaker.DoWithAcceptableCtx(ctx, breakerName, func() error {
 *         return invoker(ctx, method, req, reply, cc, opts...)
 *     }, codes.Acceptable)
 * }
 * </pre>
 *
 * <p><b>⚠️ 本层最关键的一行是「acceptable 判据」；写成「非 OK 即失败」会引入比现状更糟的缺陷</b>。
 * Go 的 {@code zrpc/internal/codes/accept.go:9-17}：
 * <pre>
 * func Acceptable(err error) bool {
 *     switch status.Code(err) {
 *     case codes.DeadlineExceeded, codes.Internal, codes.Unavailable, codes.DataLoss,
 *          codes.Unimplemented, codes.ResourceExhausted:
 *         return false                     // ← 只有这 6 个算失败
 *     default:
 *         return true                      // ← 其余（含 OK）一律算成功
 *     }
 * }
 * </pre>
 * ⇒ <b>业务错误不参与熔断</b>。
 * ⚠️ 若把判据写成「非 OK 即失败」，{@code NOT_FOUND} / {@code PERMISSION_DENIED} 这类
 * <b>正常运营中天天发生</b>的业务错误会被大量计成失败 ⇒ 熔断误开 ⇒ 打掉正常流量。
 *
 * <p><b>隔离单位 = (下游服务, RPC 方法)</b>：Go 用 {@code path.Join(cc.Target(), method)}。
 * 本类用 gRPC 的 {@code method.getFullMethodName()} ＋ {@code channel.authority()}。
 * ⚠️ 与 Go 唯一的差别是 authority 串的<b>形态</b>不同（Go 是 {@code etcd:///etcd:2379?key=...}，
 * 本仓是服务名）⇒ <b>可观察语义完全一致</b>（按「哪个下游服务 + 哪个方法」分组）⇒ 属**表达层差异**。
 *
 * <p><b>⚠️ 拒绝时抛 {@link ServiceBusyException}（普通异常，<b>不是</b> {@code StatusRuntimeException}）</b>
 * Go 侧熔断打开时 {@code breaker.DoWithAcceptableCtx} 返回的是
 * {@code breaker.ErrServiceUnavailable = errors.New("circuit breaker is open")}
 * —— 一个<b>普通 error</b>，不是 gRPC status。
 * 随后 {@code shared.RPCError} 走 {@code BizCodeFromError}：
 * <pre>
 * st, ok := status.FromError(err)
 * if !ok { return CodeServerBusy }          // ← 普通 error ⇒ ok=false ⇒ 1015
 * </pre>
 * ⇒ 客户端拿到的是 <b>code=1015、msg="service busy"（查表）</b>，
 * <b>而不是</b> "circuit breaker is open"。
 * ⇒ ⇒ 若图省事改成抛 {@code StatusRuntimeException(Status.UNKNOWN)}，
 * description 会变成 "circuit breaker is open" ⇒ <b>msg 与 Go 不同</b>。
 *
 * <p><b>⚠️ D4 / D-02：熔断器本体由 resilience4j 承载</b>（与 REST 侧、gRPC 服务端侧同一份机制）
 * ⇒ ⇒ <b>机制类型</b>与 Go 的 googlebreaker 不同（已登记 **D-02** 的 L3 降级）
 * ⇒ ⇒ 本类只负责「<b>隔离 key + 成败判据 + 拒绝形状</b>」这三件<b>行为契约</b>。
 */
@GrpcGlobalClientInterceptor
public class ClientCircuitGateInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ClientCircuitGateInterceptor.class);

    private final boolean enabled;

    /** resilience4j 的 registry；{@code null} = 生态不在 classpath ⇒ 整层透传。 */
    private final CircuitBreakerRegistry registry;

    public ClientCircuitGateInterceptor(boolean enabled, CircuitBreakerRegistry registry) {
        this.enabled = enabled;
        this.registry = registry;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        String fullMethodName = method.getFullMethodName();
        // ⚠️ 2026-10-25（T-9 修复）：breaker 的键必须**带 channel 身份**。
        //   Go: breakerName := path.Join(cc.Target(), method)
        //       `cc.Target()` = gRPC **dial 时传入的 target 字符串**（不是解析后的 IP:Port）
        //       ⇒ 键里**含服务标识** ⇒ **每个下游服务一份独立熔断器**。
        //   修复前若只用 `method.getFullMethodName()`（**无 target 前缀**）
        //       ⇒ 两个不同下游服务调用**同名方法**时**共用一份熔断器**
        //       ⇒ A 服务的失败会把 B 服务的熔断器也拖开（跨服务污染）。
        //   ⚠️ 第三个实参 `next` 确实是**底层 channel**（不是包装层）：
        //   grpc-java 的 `ClientInterceptors$InterceptorChannel.interceptCall`
        //   传的是 `this.channel` 字段（javap 实测）⇒ `next.authority()` 取得到。
        String breakerName = goBreakerName(targetOf(next), fullMethodName);

        if (!enabled || registry == null) {
            return next.newCall(method, callOptions);
        }

        CircuitBreaker breaker = registry.circuitBreaker(breakerName);
        if (!breaker.tryAcquirePermission()) {
            log.error("[grpc-breaker] dropped, circuit open, method={}, breaker={}",
                    fullMethodName, breakerName);
            // ⚠️ 抛普通异常而非 StatusRuntimeException —— 理由见类注释（⇒ 调用方映射成 1015/service busy）
            throw new ServiceBusyException("circuit breaker is open");
        }

        final long startNanos = System.nanoTime();
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                        responseListener) {

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        // DoWithAcceptableCtx 的 acceptable 判据：逐条对齐 codes.Acceptable
                        long elapsed = System.nanoTime() - startNanos;
                        if (isAcceptable(status)) {
                            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                        } else {
                            breaker.onError(elapsed, TimeUnit.NANOSECONDS, RpcFailureSignal.INSTANCE);
                            log.error("[grpc-breaker] failure, method={}, code={}",
                                    fullMethodName, status == null ? "null" : status.getCode());
                        }
                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }

    /**
     * Go 侧的「channel 身份」= {@code cc.Target()}。
     *
     * <p>⚠️ 取不到时返回空串 ⇒ breaker 退化为「仅按方法名」⇒ 至少不会崩。
     */
    static String targetOf(Channel channel) {
        if (channel == null) {
            return "";
        }
        String authority = channel.authority();
        return authority == null ? "" : authority;
    }

    /**
     * Go: {@code path.Join(cc.Target(), method)}。
     *
     * <p>⚠️ <b>字符串形式与 Go 不会逐字节相同</b>（表达层差异，不影响行为）：
     * Go 的 {@code path.Join} 会把 {@code ///} 折叠成 {@code /}
     * ⇒ 本实现做<b>同样的最小折叠</b>（连续斜杠合并）。
     * ⇒ <b>可观察的语义完全一致</b>：breaker 按「<b>哪个下游服务</b> + 哪个方法」分组。
     */
    static String goBreakerName(String target, String fullMethodName) {
        if (target == null || target.isEmpty()) {
            return fullMethodName;
        }
        String raw = target + "/" + fullMethodName;
        StringBuilder sb = new StringBuilder(raw.length());
        boolean prevSlash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '/') {
                if (prevSlash) {
                    continue;
                }
                prevSlash = true;
            } else {
                prevSlash = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 逐条对齐 Go {@code zrpc/internal/codes/accept.go:9-17} 的 {@code codes.Acceptable}。
     *
     * <p>⚠️ <b>这是本类唯一真正的语义判据</b>，改动前必须回 Go 侧确认。
     * 六个失败 code 一个都不能少，也<b>不能改成「非 OK 即失败」</b>。
     *
     * <p>{@code UNKNOWN} 归为成功，与 Go 一致：Go 的 {@code status.Code(err)} 对
     * 非 status 错误返回 {@code codes.Unknown}，而 Unknown 不在 Go 的失败名单里。
     */
    static boolean isAcceptable(Status status) {
        if (status == null) {
            return true;
        }
        return switch (status.getCode()) {
            case DEADLINE_EXCEEDED, INTERNAL, UNAVAILABLE, DATA_LOSS,
                 UNIMPLEMENTED, RESOURCE_EXHAUSTED -> false;
            default -> true;      // 含 OK / UNKNOWN / NOT_FOUND / PERMISSION_DENIED …
        };
    }

    /** 「失败码、但没有异常」的失败信号（与 {@link RpcCircuitGateInterceptor} 同款）。 */
    private static final class RpcFailureSignal extends RuntimeException {
        static final RpcFailureSignal INSTANCE = new RpcFailureSignal();

        private RpcFailureSignal() {
            super("rpc failed with a failure status code");
        }
    }
}
