package com.rag.common.observability;

import com.rag.common.protection.RpcLayer;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p><b>Prometheus 层</b> —— 对齐 B 侧 的 {@code UnaryPrometheusInterceptor}：
 * 每个 unary 请求记一条<b>耗时分布</b> ＋ 一条<b>状态码计数</b>。
 *
 * <p>RAG 出处：{@code service/common/observability/GoStylePrometheusInterceptor.java}
 * ＋ {@code GoStylePrometheusMetrics.java} ＋ {@code GoStylePrometheusSink.java}
 * ＋ {@code MicrometerPrometheusSink.java} ＋ {@code GoStylePrometheusMeterRegistryBridge.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>B 出处（消费点，不是声明点）</b>：
 * <pre>
 *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStylePrometheusInterceptor.java:19:154-156
 *       if c.Middlewares.Prometheus {
 *           svr.AddUnaryInterceptors(serverinterceptors.UnaryPrometheusInterceptor)
 *       }
 *       └─ 默认值：zrpc/internal/config.go:24  Prometheus bool `json:",default=true"`
 *
 *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStylePrometheusInterceptor.java:25:35-42
 *       func UnaryPrometheusInterceptor(ctx context.Context, req any,
 *           info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
 *           startTime := timex.Now()
 *           resp, err := handler(ctx, req)
 *           metricServerReqDur.Observe(timex.Since(startTime).Milliseconds(), info.FullMethod)
 *           metricServerReqCodeTotal.Inc(info.FullMethod, strconv.Itoa(int(status.Code(err))))
 *           return resp, err
 *       }
 *
 *   同文件 :13-31 —— 两个指标的<b>定义</b>（名字 / label / buckets）：
 *       Histogram: rpc_server_requests_duration_ms   label: method
 *                  buckets = 1,2,5,10,25,50,100,250,500,1000,2000,5000
 *       Counter  : rpc_server_requests_code_total    label: method + code
 * </pre>
 *
 * <p><b>🔴 本层在链上的<b>位置</b>＝ Stat 之内、Breaker / Shedding 之外</b>
 * B 的注册序是 {@code Trace → Recover → Stat → Prometheus → Breaker → Shedding → Timeout}
 * ⇒ ⇒ <b>被降载 / 熔断拒绝的请求也会被 Prometheus 层记一笔</b>
 * （B 的 shedding 层是「返回 error」⇒ Prometheus 层的后置代码照样执行 ⇒
 * 记到 {@code code=8(RESOURCE_EXHAUSTED)} / {@code code=14(UNAVAILABLE)}）。
 * ⇒ ⇒ 本层必须装在 breaker / shedding 的<b>外层</b>，否则这两类请求在指标里<b>整个消失</b>。
 *
 * <p><b>🔴 panic 时<b>不</b>记（这是 B 的行为，本类照抄）</b>
 * B 的 Prometheus 层是「<b>先调 handler、再记</b>」的直线代码 ⇒ handler panic 时，
 * 后面的两行<b>根本不会执行</b> ⇒ ⇒ <b>panic 的请求在 Prometheus 里没有任何样本</b>
 * （但 {@link RpcStatInterceptor} 仍然会记一条耗时样本 —— B 的 Stat 层用 {@code defer}）。
 * ⇒ ⇒ 本类用「{@code onHalfClose} 是否抛出」来区分这两条路径 ⇒ 与 B 侧 同形。
 *
 * <p><b>⚠️ 耗时口径</b>
 * B 侧 观测的是 {@code timex.Since(startTime).Milliseconds()} —— <b>整型毫秒（截断）</b>。
 * 本类同样<b>先截断成毫秒</b>再观测 ⇒ 桶归属与 B 侧 逐桶一致。
 *
 * <p><b>⚠️ D4 生态吸收（L4 idiomatization）：不复制 RAG 的「接缝接口 + 静态 holder」</b>
 * <pre>
 *   RAG 的形态：GoStylePrometheusSink（接口） + MicrometerPrometheusSink（实现）
 *             + GoStylePrometheusMetrics（**静态 holder**）+ GoStylePrometheusMeterRegistryBridge（装配点）
 *   ⇒ 拦截器在**构造期**拿不到 registry（链是静态构造的）⇒ 只能走「静态 holder + 每次请求取」
 *   ⇒ ⇒ 静态可变状态 ⇒ 多上下文会互相覆盖 ⇒ 靠 destroy() 手工清理。
 * </pre>
 * ⇒ C 侧改为 <b>构造器注入 {@link MeterRegistry}</b>（本拦截器在 C 侧是 {@code @Bean} ⇒ Spring 直接注入）
 * ⇒ ⇒ <b>零静态状态</b>、<b>零接缝接口</b>、<b>不需要 destroy 钩子</b>。
 * ⇒ ⚠️ **代价（已登记 L3）**：<b>指标名与桶布局不等价</b> ——
 * ① B 的 Histogram {@code rpc_server_requests_duration_ms} 有<b>显式 12 个桶</b>
 * （1,2,5,10,25,50,100,250,500,1000,2000,5000 ms），
 * Micrometer 的 {@link DistributionSummary} 默认是 <b>summary（分位数）</b>，桶边界由生态决定；
 * ② B 的 Counter {@code rpc_server_requests_code_total} 在 Micrometer 里
 * <b>不能</b>直接叫这个名字（Prometheus 命名约定会给 Counter 自动加 {@code _total} 后缀 ⇒ 变 {@code ..._total_total}）
 * ⇒ ⇒ 本类把 Meter 名取为 {@code rpc_server_requests_code} ⇒ 导出后正是 {@code rpc_server_requests_code_total}。
 *
 * <p><b>🔴 开关语义（V16 收口 · 2026-11-05）：关掉 ⇒ <b>一条指标都不写</b></b>
 * <p><b>① 比 B 侧多做了什么</b>：B 侧 {@code GoStyleRpcProtectionChain.build} 用
 * {@code if (props.getPrometheus().isEnabled())} <b>决定这一层是否被拼进链</b>（B: {@code :228}）
 * ⇒ ⇒ 开关关掉时 B 侧根本不构造这一层 ⇒ ⇒ <b>一条指标都不写</b>。
 * 本仓把这件事做成 <b>「层仍在链上，但开关关掉时一条都不写」</b>。
 * <p><b>② 语义还一样吗</b>：<b>一样</b> —— B 侧这一层<b>只做两件事</b>
 * （{@code metricServerReqDur.Observe(...)} ＋ {@code metricServerReqCodeTotal.Inc(...)}）
 * ⇒ ⇒ 开关关掉 ⇒ 两件都不发生 ⇒ ⇒ 与「不在链上」逐项等价。
 * ⚠️ <b>诚实边界</b>：<b>链的组成</b>两侧不同（B = 层不在链上；C = 层在但空转）
 * ⇒ ⇒ 只影响内部断言（层名列表），不影响任何客户端可观测行为（无指标写入）。
 * <p><b>③ B 出处</b>：{@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcProtectionChain.java:228}
 * （消费点）＋ {@code zrpc/internal/config.go:24}（{@code Prometheus bool `json:",default=true"`}）。
 */
public final class RpcPrometheusInterceptor implements ServerInterceptor, RpcLayer {

    /** B 的耗时指标名（{@code rpc_server_requests_duration_ms}）。 */
    public static final String DURATION_METRIC = "rpc_server_requests_duration_ms";

    /**
     * B 的状态码指标名在 Micrometer 里的 <b>Meter 名</b>。
     *
     * <p>⚠️ <b>不能直接叫 {@code rpc_server_requests_code_total}</b>：
     * Micrometer 的 Prometheus 命名约定会给 Counter 自动追加 {@code _total} 后缀
     * ⇒ 那样导出后是 {@code rpc_server_requests_code_total_total} ⇒ <b>多一层</b>。
     * ⇒ Meter 名取 {@code rpc_server_requests_code} ⇒ 导出后正好是 B 侧的
     * {@code rpc_server_requests_code_total}。
     */
    public static final String CODE_METRIC = "rpc_server_requests_code";

    /** 指标标签：方法全名。 */
    private static final String TAG_METHOD = "method";

    /** 指标标签：gRPC 状态码数值。 */
    private static final String TAG_CODE = "code";

    /**
     * 写入面；{@code null} = <b>没有 registry ⇒ 一条都不写</b>
     * （B 侧：{@code prometheus.Enabled()==false}）。
     */
    private final MeterRegistry registry;

    /**
     * 本层是否启用（B 侧 = {@code rag.protection.prometheus.enabled}）。
     *
     * <p>⚠️ <b>与「没有 registry」是两根独立的轴</b>：开关是<b>配置意图</b>，
     * registry 是<b>有没有地方可写</b> ⇒ ⇒ 两根轴任一为「否」⇒ 本层一条都不写。
     */
    private final boolean enabled;

    /** 可实例化：它是一个无状态的拦截器，由共享层的装配点直接 new。 */
    public RpcPrometheusInterceptor(MeterRegistry registry) {
        this(registry, true);
    }

    /**
     * 按开关构造（生产路径用这个：{@code rag.protection.prometheus.enabled} 决定本层装不装）。
     *
     * @param registry 写入面；{@code null} ⇒ 一条都不写
     * @param enabled  {@code false} ⇒ 本层<b>一条都不写</b>
     */
    public RpcPrometheusInterceptor(MeterRegistry registry, boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // ⚠️ B 侧: core/metric/metric.go:14-20 —— prometheus 未启用 ⇒ **整次写入被丢弃**。
        //    本仓的同形语义 = 「没有 registry ⇒ 本层整段跳过」。
        //
        // 🔴 **开关关掉 ⇒ 本层一条都不写**（V16 收口 · 2026-11-05）：
        //   B 侧: GoStyleRpcProtectionChain.java:228 ——
        //     if props.getPrometheus().isEnabled() { chain = prometheusThen(chain); }
        //   ⇒ ⇒ 开关关掉时 B 侧这一层**根本不在链上** ⇒ ⇒ **一条指标都不写**。
        //   ⇒ ⇒ 本仓的形态是「层仍在链上，但开关关掉时不写」⇒ ⇒ **行为等价**。
        if (!enabled || registry == null) {
            return next.startCall(call, headers);
        }

        final long startNanos = System.nanoTime();
        final String method = call.getMethodDescriptor().getFullMethodName();
        final long[] handlerDoneNanos = new long[1];
        final AtomicBoolean recorded = new AtomicBoolean(false);
        final AtomicBoolean panicked = new AtomicBoolean(false);

        ServerCall<ReqT, RespT> watched = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                if (recorded.compareAndSet(false, true) && !panicked.get()) {
                    long end = handlerDoneNanos[0] > 0L ? handlerDoneNanos[0] : System.nanoTime();
                    long millis = Math.max(0L, end - startNanos) / 1_000_000L;
                    observeDuration(method, millis);
                    // ⚠️ B 侧: status.Code(err) —— nil ⇒ codes.OK(0)；非 status 错误 ⇒ codes.Unknown(2)。
                    //    本仓取「close 时的状态码」⇒ 与 B 的「handler 返回的 error」在正常路径上同值。
                    incrementCode(method, status.getCode().value());
                }
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> listener;
        try {
            listener = next.startCall(watched, headers);
        } catch (RuntimeException | Error e) {
            // ⚠️ B 的 Prometheus 层：handler **在 startCall 这一步**就 panic 时，
            //    后置的两行 Observe/Inc 同样不会执行 ⇒ 也不记。
            panicked.set(true);
            throw e;
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (RuntimeException | Error e) {
                    // ⚠️ B 的 Prometheus 层在 handler panic 时**不记**（后置代码被跳过）⇒ 标记后原样抛出。
                    panicked.set(true);
                    throw e;
                }
                handlerDoneNanos[0] = System.nanoTime();
            }
        };
    }

    /** 记一条耗时样本（单位：毫秒；与 B 的整型毫秒口径一致）。 */
    void observeDuration(String method, long millis) {
        if (!enabled || registry == null) {
            return;
        }
        DistributionSummary.builder(DURATION_METRIC)
                .tag(TAG_METHOD, method)
                .register(registry)
                .record(millis);
    }

    /** 记一条状态码计数。 */
    void incrementCode(String method, int code) {
        if (!enabled || registry == null) {
            return;
        }
        Counter.builder(CODE_METRIC)
                .tag(TAG_METHOD, method)
                .tag(TAG_CODE, Integer.toString(code))
                .register(registry)
                .increment();
    }

    /** 链上的层名（供守门断言链序）。 */
    @Override
    public String layerName() {
        return "prometheus";
    }
}
