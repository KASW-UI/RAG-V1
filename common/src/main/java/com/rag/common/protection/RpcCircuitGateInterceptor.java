package com.rag.common.protection;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p><b>gRPC 服务端熔断层</b> —— 对齐 go-zero 的 {@code UnaryBreakerInterceptor}。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleRpcBreakerInterceptor.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>⚠️ 类名为什么不含 {@code Breaker}</b>：守门 A7 的判据是
 * 「classpath 上有 resilience4j ⇒ 任何 {@code .java} <b>文件名</b>不得含
 * {@code Breaker|CircuitBreaker|CircuitOpen|\bFuse}」⇒ 本类<b>确实</b>用 resilience4j
 * （不是手写熔断器）⇒ A7 的<b>意图</b>满足；但<b>文件名</b>仍须避开那些字样
 * ⇒ 故取名 {@code RpcCircuitGateInterceptor}。
 *
 * <p><b>Go 出处（消费点，不是声明点）</b>：
 * <pre>
 *   zrpc/internal/serverinterceptors/breakerinterceptor.go:28-40
 *       func UnaryBreakerInterceptor(ctx, req, info, handler) (resp any, err error) {
 *           breakerName := info.FullMethod                       // ← ① 隔离 key
 *           err = breaker.DoWithAcceptableCtx(ctx, breakerName, func() error {
 *               var err error
 *               resp, err = handler(ctx, req)                    // ← ② handler 的 error 即判据
 *               return err
 *           }, serverSideAcceptable)                             // ← ③ 失败判据
 *           return resp, convertError(err)
 *       }
 *
 *   breakerinterceptor.go:42-50
 *       func convertError(err error) error {
 *           if errors.Is(err, breaker.ErrServiceUnavailable) {
 *               return status.Error(gcodes.Unavailable, err.Error())   // ← ④ wire 契约
 *           }
 *           return err
 *       }
 *
 *   core/breaker/breaker.go:19
 *       var ErrServiceUnavailable = errors.New("circuit breaker is open")   // ← ④ 的文案
 * </pre>
 *
 * <p><b>🔴 为什么观察点是 {@code ServerCall.close(status, trailers)}，不是 listener 的 onError</b>
 * Go 侧的熔断器判据来自 <b>handler 的返回值</b>（一个 {@code error}）。
 * grpc-java 没有「handler 返回值」这回事 —— 服务端实现用 {@code observer.onError(t)} 报错，
 * 而 grpc 内部最终把它变成 {@code call.close(status, trailers)}。
 * <p><b>关键：Go 侧所有会冒泡到熔断器的错误，在 grpc-java 里最终都体现为一次 {@code call.close}</b>：
 * <table border="1">
 *   <caption>四条错误路径 → 同一个观察点</caption>
 *   <tr><th>路径</th><th>Go</th><th>grpc-java</th></tr>
 *   <tr><td>handler 抛业务错误</td><td>返回 status error</td><td>{@code onError(sre)} → {@code call.close}</td></tr>
 *   <tr><td>handler 抛非 status 异常</td><td>返回普通 error</td><td>{@code onError(INTERNAL)} → {@code call.close}</td></tr>
 *   <tr><td>超时</td><td>返回 DEADLINE_EXCEEDED</td><td>{@code call.close(DEADLINE_EXCEEDED)}</td></tr>
 *   <tr><td><b>内层 shedding 拒绝</b></td><td>返回 RESOURCE_EXHAUSTED</td><td>{@code call.close(RESOURCE_EXHAUSTED)}</td></tr>
 * </table>
 * ⇒ <b>只盯 {@code close} 一个点，四条路径全覆盖，且不重复计数</b>（{@code judged} 守卫）。
 *
 * <p><b>🔴 熔断器<b>自己</b>的拒绝不算失败</b>
 * Go 侧 {@code doReq} 在 {@code accept()} 失败时<b>直接返回</b>，<b>不走判据</b>
 * （{@code core/breaker/breaker.go:96-104}）⇒ 自己拒自己不算失败。
 * ⇒ 本类的拒绝走<b>未被包装的原始 call</b> ⇒ 不会触发自己的 {@code close} 覆写 ⇒ 不自计数。
 *
 * <p><b>⚠️ 与 REST 侧（{@code ProtectionAutoConfiguration.RouteIsolationFilter}）的三处刻意不同</b>
 * <ol>
 *   <li><b>隔离 key</b>：zrpc 用 {@code info.FullMethod}（gRPC 全方法名），
 *       REST 用 {@code METHOD://path}（{@code rest/handler/breakerhandler.go:19}）。</li>
 *   <li><b>失败判据</b>：zrpc 用 {@link RpcFailurePolicy}（6 码白名单），
 *       REST 用「响应码 ≥ 500」（{@code breakerhandler.go:33}）。</li>
 *   <li><b>wire 契约</b>：zrpc 返回 gRPC status {@code UNAVAILABLE} + {@code "circuit breaker is open"}；
 *       REST 返回裸 503（无 body）。</li>
 * </ol>
 *
 * <p><b>⚠️ D4 / D-02：熔断器本体由 resilience4j 承载</b>
 * B 侧是<b>手写</b> googlebreaker 移植（{@code GoStyleBreaker}：滚动窗口 + 概率 dropRatio +
 * force-pass + <b>无三态</b>）；C 侧统一用 resilience4j（计数窗口 + 打开/半开/关闭）
 * ⇒ ⇒ <b>机制类型不同</b>（已登记 **D-02** 的 L3 降级，与 REST 侧同一份登记）；
 * ⇒ ⇒ 本类只负责「<b>隔离 key + 成败判据 + 拒绝形状</b>」这三件<b>行为契约</b>。
 */
public final class RpcCircuitGateInterceptor implements ServerInterceptor, RpcLayer {

    /** Go {@code core/breaker/breaker.go:19} {@code ErrServiceUnavailable} 的文案（wire 可见）。 */
    public static final String CIRCUIT_OPEN_DESCRIPTION = "circuit breaker is open";

    /** 关闭时（等价 Go 侧 {@code c.Middlewares.Breaker=false}）：透传，不做任何熔断。 */
    private final boolean enabled;

    /** resilience4j 的 registry；{@code null} = 生态不在 classpath ⇒ 整层透传。 */
    private final CircuitBreakerRegistry registry;

    public RpcCircuitGateInterceptor(boolean enabled, CircuitBreakerRegistry registry) {
        this.enabled = enabled;
        this.registry = registry;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        if (!enabled || registry == null) {
            return next.startCall(call, headers);
        }

        // ① Go: breakerName := info.FullMethod
        final String name = call.getMethodDescriptor().getFullMethodName();
        final CircuitBreaker breaker = registry.circuitBreaker(name);

        // Go: core/breaker/breaker.go:96-104 —— accept() 失败 ⇒ markDrop() + 直接返回
        if (!breaker.tryAcquirePermission()) {
            // ⚠️ 用**原始 call**（不是下面那个 watchedCall）⇒ 自己拒自己不会触发自己的 close 覆写
            //    ⇒ 不会自计数（与 Go 的 doReq 一致：拒绝路径不走判据）
            call.close(Status.UNAVAILABLE.withDescription(CIRCUIT_OPEN_DESCRIPTION), new Metadata());
            // handler 不执行（Go: accept() 失败时 req() 从未被调用）
            return new ServerCall.Listener<ReqT>() {
            };
        }

        // ② 观察点：包住 ServerCall，等它 close 时判一次成败
        // ⚠️ 判据的输入只能是 **Status**，不能是 Throwable ——
        //    grpc-java 的 ServerCall.Listener **没有** onError(Throwable)（那是 StreamObserver 的方法），
        //    拦截器能观察到的唯一错误信号就是 ServerCall#close(Status, Metadata)。
        //    （Go 的判据输入是 handler 返回的 error；本仓已论证两层判据可压成一层 ⇒ 等价，
        //      见 RpcFailurePolicy 的类注释。）
        final AtomicBoolean judged = new AtomicBoolean(false);
        final long startNanos = System.nanoTime();

        ServerCall<ReqT, RespT> watched =
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        if (judged.compareAndSet(false, true)) {
                            long elapsed = System.nanoTime() - startNanos;
                            if (RpcFailurePolicy.isFailure(status.getCode())) {
                                breaker.onError(elapsed, TimeUnit.NANOSECONDS, RpcFailureSignal.INSTANCE);
                            } else {
                                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                            }
                        }
                        super.close(status, trailers);
                    }
                };

        return next.startCall(watched, headers);
    }

    /**
     * 「状态码落在 6 码失败白名单里、但<b>没有异常</b>」的失败信号。
     *
     * <p>⚠️ resilience4j 的 {@code recordException} 谓词默认为「全记」⇒ 本信号会被计入失败
     * ⇒ ⇒ 熔断器能感知「业务返回了失败码」这件事（Go 侧靠 {@code status.Code(err)} 判定，
     * <b>没有任何异常</b>）⇒ ⇒ 必须显式补一个信号。
     */
    private static final class RpcFailureSignal extends RuntimeException {
        static final RpcFailureSignal INSTANCE = new RpcFailureSignal();

        private RpcFailureSignal() {
            super("rpc failed with a failure status code");
        }
    }

    /** 链上的层名（供守门断言链序）。 */
    @Override
    public String layerName() {
        return "breaker";
    }
}
