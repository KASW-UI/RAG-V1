package com.rag.common.observability;

import com.rag.common.protection.RpcLayer;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC 服务端 <b>panic 恢复</b>拦截器 —— 对齐 B 侧 的 {@code UnaryRecoverInterceptor}。
 *
 * <h3>B 出处（消费点，不是声明点）</h3>
 * <pre>
 *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStyleRecoverInterceptor.java:21:148-150
 *       if c.Middlewares.Recover { svr.AddUnaryInterceptors(UnaryRecoverInterceptor) }
 *       └─ 默认值：zrpc/internal/config.go:22  Recover bool `json:",default=true"`
 *          ⇒ etc/message.yaml 未配 ⇒ 生效
 *
 *   zrpc/internal/serverinterceptors/recoverinterceptor.go:24-42
 *       func UnaryRecoverInterceptor(ctx, req, _, handler) (resp any, err error) {
 *           defer handleCrash(func(r any) { err = toPanicError(ctx, r) })
 *           return handler(ctx, req)
 *       }
 *       func toPanicError(ctx context.Context, r any) error {
 *           logc.Errorf(ctx, "%+v\n\n%s", r, debug.Stack())      // ← 记日志 + 栈
 *           return status.Errorf(codes.Internal, "panic: %v", r) // ← INTERNAL + "panic: "
 *       }
 * </pre>
 *
 * <h3>为什么必须包到 {@code Listener} 上，而不只是 {@code startCall}</h3>
 * <p>grpc-java 的执行模型：<b>handler 方法不是在 {@code interceptCall} 里执行的，
 * 而是在 {@code Listener#onHalfClose()} 里执行的</b>
 * （B 侧 {@code RpcInterceptor} 的注释已记录过这一点，它正是因此去包 {@code onHalfClose}；
 *  C 侧 {@code RpcInterceptor} 已按 D11 拆成多个类，gRPC 服务端拦截器 =
 *  {@link com.rag.common.autoconfigure.ObservabilityAutoConfiguration} 里的
 *  {@code GrpcObservationInterceptor}）。
 * ⇒ 只包 {@code next.startCall(...)} 的话，<b>用户 handler 里的 panic 一条都兜不住</b>，
 * 而这恰好是 recover 要处理的唯一场景。
 * <br>⇒ 本类三处都包：{@code startCall} / {@code onMessage} / {@code onHalfClose} / {@code onReady}。
 *
 * <h3>⚠️ {@code onComplete} / {@code onCancel} 刻意不兜</h3>
 * <p>那两个回调是<b>清理路径</b>（状态已经定了），B 的 {@code defer recover} 也管不到它们；
 * 更重要的是：此时 {@code ServerCall} 多半已经 close，再 {@code close} 一次会抛
 * {@code IllegalStateException}，把一个「已恢复的 panic」变成「第二个 panic」。
 *
 * <h3>装配顺序（由调用方保证，见各 rpc 模块的 {@code GrpcServerConfigurer}）</h3>
 * <p>grpc 拦截器<b>由外到内</b>执行。B 侧的注册序（{@code service/common/src/main/java/com/rag/common/observability/GoStyleRecoverInterceptor.java:51:144-163} +
 * {@code zrpc/internal/server.go:58} 是 append 到尾部 ⇒ 先注册的最外层）是：
 * <pre>
 *   Trace → Recover → Stat → Prometheus → Breaker → Shedding → Timeout → observability
 * </pre>
 * ⇒ <b>Recover 必须在 Breaker / Shedding 的外层</b>，否则它们内部的 panic 会漏出去；
 * 且 Recover 在业务观测拦截器（超时/慢调用）之<b>外</b>，与 B 侧 一致。
 *
 * <h3>可观察契约（wire 可见）</h3>
 * <ul>
 *   <li>{@code Status.Code == INTERNAL}</li>
 *   <li>{@code description} 以 {@code "panic: "} 开头（B 的 {@code %v} 语义）</li>
 *   <li><b>进程存活</b>，能继续服务下一个请求</li>
 *   <li>本地日志里有一条带<b>栈</b>的 ERROR（B 侧 {@code debug.Stack()}）</li>
 * </ul>
 */
public final class RpcRecoverInterceptor implements ServerInterceptor, RpcLayer {

    private static final Logger LOG = LoggerFactory.getLogger(RpcRecoverInterceptor.class);

    /** B 的 {@code status.Errorf(codes.Internal, "panic: %v", r)} 里的字面前缀（wire 可见）。 */
    public static final String PANIC_DESCRIPTION_PREFIX = "panic: ";

    /** 可实例化：它是一个无状态的拦截器，由各 rpc 模块的 {@code GrpcServerConfigurer} 直接 new。 */
    public RpcRecoverInterceptor() {
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // 每次调用一个守卫（**不跨调用共享**）：
        // listener 的多个回调（onMessage / onHalfClose / onReady）可能先后抛出，
        // 重复 close 会抛 IllegalStateException（grpc-java 的 ServerCallImpl.close 是 checkState(!closeCalled)）。
        // ⚠️ 早期版本用「静态 WeakHashMap<ServerCall, AtomicBoolean>」做这件事 ——
        // 那会在本模块（被 10 个 rpc + 10 个 api = 20 个组件依赖的共享模块）里留下一份**静态可变状态**，
        // 且 Collections.synchronizedMap 的 computeIfAbsent **并不原子**（WeakHashMap 未覆写它）
        // ⇒ 并发下可能两个线程各拿到一个 guard、双双放行 ⇒ 重复 close。
        // ⇒ 改为**每次 interceptCall 一个局部 AtomicBoolean**，由匿名类捕获 ⇒ 无共享状态、无泄漏、无竞态。
        final AtomicBoolean closed = new AtomicBoolean(false);

        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(call, headers);
        } catch (Throwable t) {
            closeAsPanic(call, t, closed);
            return new ServerCall.Listener<ReqT>() {
                // 已 close：吞掉后续所有回调，避免二次异常
            };
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {

            @Override
            public void onMessage(ReqT message) {
                try {
                    super.onMessage(message);
                } catch (Throwable t) {
                    closeAsPanic(call, t, closed);
                }
            }

            /** 🔴 unary handler 的真正入口 —— 不包这里等于没做 recover。 */
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (Throwable t) {
                    closeAsPanic(call, t, closed);
                }
            }

            @Override
            public void onReady() {
                try {
                    super.onReady();
                } catch (Throwable t) {
                    closeAsPanic(call, t, closed);
                }
            }
        };
    }

    /**
     * 按 B 的 {@code toPanicError} 处置：<b>先记带栈的日志</b>，再以
     * {@code INTERNAL + "panic: …"} 关闭这次调用。
     *
     * <p>用 {@link AtomicBoolean} 保证<b>只 close 一次</b>。
     */
    private static void closeAsPanic(ServerCall<?, ?> call, Throwable t, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.error("gRPC handler panic: {}\n{}", t.toString(), stackTraceOf(t));
        call.close(Status.INTERNAL
                .withDescription(PANIC_DESCRIPTION_PREFIX + renderAsGoPercentV(t))
                .withCause(t), new Metadata());
    }

    /**
     * 复刻 B 侧 {@code %v} 对 panic 值的格式化：
     * <ul>
     *   <li>B 的 {@code panic("boom")} 打印 {@code boom}（不是类型名）</li>
     *   <li>Java 的 {@code throw new RuntimeException("boom")} 若直接 {@code toString()}
     *       会变成 {@code java.lang.RuntimeException: boom} —— 多出类型名，与 B 侧 不一致</li>
     * </ul>
     * ⇒ 有 message 就用 message，没有才回退 {@code toString()}。
     */
    private static String renderAsGoPercentV(Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        return (msg != null && !msg.isEmpty()) ? msg : t.toString();
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    /** 链上的层名（供守门断言链序）。 */
    @Override
    public String layerName() {
        return "recover";
    }
}