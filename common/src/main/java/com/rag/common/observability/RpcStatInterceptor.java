package com.rag.common.observability;

import com.rag.common.protection.RpcLayer;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * <b>Stat 层</b> —— 对齐 B 侧 的 {@code UnaryStatInterceptor}：
 * 每个 unary 请求的<b>耗时</b>进 {@link RpcStatMetrics}（进程内聚合器 ⇒ 每分钟一行统计日志）。
 *
 * <h3>B 出处（消费点，不是声明点）</h3>
 * <pre>
 *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStyleStatInterceptor.java:16:151-153
 *       if c.Middlewares.Stat {
 *           svr.AddUnaryInterceptors(serverinterceptors.UnaryStatInterceptor(metrics, c.Middlewares.StatConf))
 *       }
 *       └─ 默认值：zrpc/internal/config.go:23  Stat bool `json:",default=true"`
 *
 *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStyleStatInterceptor.java:22:45-63
 *       func UnaryStatInterceptor(metrics *stat.Metrics, conf StatConf) grpc.UnaryServerInterceptor {
 *           return func(ctx context.Context, req any, info *grpc.UnaryServerInfo,
 *               handler grpc.UnaryHandler) (resp any, err error) {
 *               startTime := timex.Now()
 *               defer func() {
 *                   duration := timex.Since(startTime)
 *                   metrics.Add(stat.Task{Duration: duration})      // ← **唯一的样本来源**
 *                   logDuration(ctx, info.FullMethod, req, duration, ...)
 *               }()
 *               return handler(ctx, req)
 *           }
 *       }
 * </pre>
 *
 * <h3>🔴 本层在链上的<b>位置</b>＝ Recover 之内、Breaker / Shedding 之外</h3>
 * <p>B 的注册序（{@code zrpc/server.go:144-167}，append 到尾部 ⇒ <b>先注册的最外层</b>）是：
 * <pre>
 *   Trace → Recover → **Stat** → **Prometheus** → Breaker → Shedding → Timeout → handler
 * </pre>
 * ⇒ ⇒ <b>Stat 层量到的耗时含降载 / 熔断的开销</b>，而且<b>被降载拒绝的请求也会进样本</b>
 * （B 的 shedding 层是「返回 error」而不是「不走 Stat 层」⇒ Stat 的 defer 照样跑）。
 * ⇒ ⇒ 本层必须装在<b> breaker / shedding 的外层</b>，否则量到的口径与 B 侧 不同。
 *
 * <h3>⚠️ 本层<b>刻意只做「喂样本」这一件事</b></h3>
 * <p>B 的 Stat 拦截器还兼做 {@code logDuration}（每个请求打全量 body 的 <b>Info</b> 分支 ＋ 慢调用分支）。
 * 本仓的慢调用日志<b>已经</b>在 gRPC 服务端拦截器里（最内层；B 侧对应 {@code RpcInterceptor}，
 * C 侧 = {@link com.rag.common.autoconfigure.ObservabilityAutoConfiguration} 里的
 * {@code GrpcObservationInterceptor}），而
 * <b>Info 分支（每请求打全量 body）本仓刻意不落</b>（全局台账 {@code G4-15c} 的第一项，仍未闭合）
 * ⇒ ⇒ 本层<b>不重复实现</b>日志分支，只负责把耗时喂给聚合器。
 *
 * <h3>⚠️ 耗时口径：B 侧 在「handler 返回」时取，本类在「调用结束」时取</h3>
 * <p>B 的 {@code defer} 在 {@code handler(ctx, req)} <b>返回时</b>执行 ⇒ 耗时<b>不含</b>响应写回。
 * 本类用「{@code onHalfClose} 结束时刻」近似同一口径（grpc-java 的 handler 就在 {@code onHalfClose} 里跑），
 * 若该请求<b>没走</b> {@code onHalfClose}（降载 / 熔断拒绝路径）⇒ 退回「close 时刻」。
 * ⇒ ⇒ 这是<b>毫秒级口径差</b>（写回耗时），已在本层台账登记为诚实边界。
 *
 * <h3>panic 时的行为：<b>照 B 侧</b></h3>
 * <p>B 的 Stat 层用 {@code defer} ⇒ <b>panic 也会记样本</b>（defer 在栈展开时执行）。
 * 本类的记录点同样在「调用结束」⇒ ⇒ panic（被外层 {@link RpcRecoverInterceptor} 兜住后 close）
 * 也会记一条样本 ⇒ 与 B 侧 同形。
 *
 * <h3>🔴 开关语义（V16 收口 · 2026-11-05）：关掉 ⇒ <b>层等于没装</b></h3>
 * <p><b>① 比 B 侧多做了什么</b>：B 侧 {@code GoStyleRpcProtectionChain.build} 用
 * {@code if (props.getStat().isEnabled())} <b>决定这一层是否被拼进链</b>（B: {@code :235}）
 * ⇒ ⇒ 开关关掉时 B 侧<b>根本不构造</b> {@code GoStyleStatInterceptor}
 * ⇒ ⇒ 既<b>不记样本</b>，也<b>不起那条每 60s 的统计日志线程</b>（该线程只由
 * {@code GoStyleStatInterceptor.java:82} 带起来 ⇒ 是它的唯一来源）。
 * 本仓把这件事做成 <b>「层仍在链上，但开关关掉时完全不做事」</b>（透传 ＋ 不起线程）。
 * <p><b>② 语义还一样吗</b>：<b>一样</b> —— 可观测面上三件事全部对齐：
 * <b>无样本</b>（B 不在链上 ⇒ 不记；C 透传 ⇒ 不记）、<b>无那条 60s 日志</b>
 * （B 无线程；C 开关关掉时不启动线程）、<b>降载 drop 计数口径不变</b>
 * （B 的 {@code GoStyleStatMetrics.getInstance().addDrop()} 与 C 的
 * {@code RpcStatMetrics.getInstance().addDrop()} 同款：都只增计数、都没有上报线程去打印它
 * ⇒ ⇒ 两边都「计了但看不见」）。
 * ⚠️ <b>诚实边界</b>：<b>链的组成</b>两侧不同（B = 层不在链上；C = 层在但透传）
 * ⇒ ⇒ 只影响内部断言（层名列表），不影响任何客户端可观测行为。
 * <p><b>③ B 出处</b>：{@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcProtectionChain.java:235}
 * （消费点）＋ {@code service/common/src/main/java/com/rag/common/observability/GoStyleStatInterceptor.java:82}
 * （上报线程的唯一来源）＋ {@code zrpc/internal/config.go:23}（{@code Stat bool `json:",default=true"`}）。
 */
public final class RpcStatInterceptor implements ServerInterceptor, RpcLayer {

    private final RpcStatMetrics metrics;

    /**
     * 本层是否启用（B 侧 = {@code rag.protection.stat.enabled}）。
     *
     * <p>⚠️ <b>关掉时必须「什么都不做」——包括不起那条每 60s 的上报线程</b>：
     * B 侧那条线程是 <b>由 Stat 拦截器被构造</b> 这件事带起来的
     * （{@code GoStyleStatInterceptor.java:82}），而拦截器只在开关为真时才被构造
     * （{@code GoStyleRpcProtectionChain.java:235}）⇒ ⇒ 开关关掉 ⇒ <b>既没有样本、也没有那行日志</b>。
     */
    private final boolean enabled;

    public RpcStatInterceptor() {
        this(RpcStatMetrics.getInstance(), true);
    }

    /**
     * 用指定的聚合器构造（<b>测试接缝</b>：让断言能拿到一个干净的聚合器，而不是进程级单例）。
     *
     * <p>⚠️ 生产路径用 {@link #RpcStatInterceptor()}（进程级单例 ＋ 幂等上报线程）。
     */
    public RpcStatInterceptor(RpcStatMetrics metrics) {
        this(metrics, true);
    }

    /**
     * 按开关构造（生产路径用这个：{@code rag.protection.stat.enabled} 决定本层装不装）。
     *
     * @param enabled {@code false} ⇒ 本层<b>完全不做事</b>（不记样本 ＋ 不起上报线程）
     */
    public RpcStatInterceptor(boolean enabled) {
        this(RpcStatMetrics.getInstance(), enabled);
    }

    /**
     * 按开关构造（聚合器 + 开关全指定）。
     *
     * @param metrics 聚合器
     * @param enabled {@code false} ⇒ 本层<b>完全不做事</b>（不记样本 ＋ 不起上报线程）
     */
    public RpcStatInterceptor(RpcStatMetrics metrics, boolean enabled) {
        this.metrics = metrics;
        this.enabled = enabled;
        // B 侧: zrpc/server.go:37 —— metrics := stat.NewMetrics(c.ListenOn)
        //   ⇒ **每个 rpc server 一个** ⇒ 只有「真的装了 Stat 层」的进程才会有这条上报线程
        //   （B 侧同理：Stat 拦截器只在 c.Middlewares.Stat 为真时才被构造）。
        // ⚠️ 与 SheddingStat.startReporter() 同款：幂等 + 守护线程。
        //
        // 🔴 **开关关掉时必须连线程一起不起**（V16 收口 · 2026-11-05）：
        //   B 侧那条「每 60s 一行统计日志」的线程**只**由本构造器带起来
        //   （B: GoStyleStatInterceptor.java:82 是 GoStyleStatMetrics.startReporter() 的**唯一**调用点）
        //   ⇒ ⇒ 开关关掉 ⇒ B 侧**既不记样本、也没有那行日志**
        //   ⇒ ⇒ 本类照抄：enabled=false ⇒ 不起线程 ⇒ 否则 C 会多打一行 B 侧不打的日志。
        if (enabled) {
            metrics.startReporter();
        }
    }

    /** 排障用：本次使用的聚合器。 */
    RpcStatMetrics metrics() {
        return metrics;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        // 🔴 **开关关掉 ⇒ 本层完全不做事**（V16 收口 · 2026-11-05）
        //   B 侧: GoStyleRpcProtectionChain.java:235 ——
        //     if props.getStat().isEnabled() { chain = statThen(chain); }
        //   ⇒ ⇒ 开关关掉时，B 侧这一层**根本不在链上** ⇒ ⇒ **一条样本都不记**。
        //   ⇒ ⇒ 本仓的形态是「层仍在链上，但透传」⇒ ⇒ **行为等价**（可观测面：无样本、无那行 60s 日志）。
        //   ⚠️ 诚实边界：B 是「层不在链上」、本仓是「层在但透传」⇒ **链的组成**两者不同（层名列表）
        //   ⇒ ⇒ 但链组成只影响内部断言，不影响任何客户端可观测行为（无样本 / 无日志 / 无指标）。
        if (!enabled) {
            return next.startCall(call, headers);
        }

        final long startNanos = System.nanoTime();
        final long[] handlerDoneNanos = new long[1];
        // ⚠️ B 的 defer **只跑一次** ⇒ 本类也只记一次（grpc-java 的 close 可能被调两次：
        //    超时调度线程与业务 handler 各 close 一次 —— 见 RpcInterceptor 的 M6 修复说明）。
        final java.util.concurrent.atomic.AtomicBoolean recorded = new java.util.concurrent.atomic.AtomicBoolean(false);

        ServerCall<ReqT, RespT> watched = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(io.grpc.Status status, Metadata trailers) {
                if (recorded.compareAndSet(false, true)) {
                    long end = handlerDoneNanos[0] > 0L ? handlerDoneNanos[0] : System.nanoTime();
                    metrics.add(Math.max(0L, end - startNanos));
                }
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> listener;
        try {
            listener = next.startCall(watched, headers);
        } catch (RuntimeException | Error e) {
            // 🔴 B 侧: statinterceptor.go:52-56 —— 样本是写在 **defer** 里的
            //    ⇒ ⇒ **panic 时照样记一条**（defer 在栈展开时执行）。
            //    ⇒ 本仓若只在 close 时记 ⇒ panic 路径**一条都不记**（因为兜底的 recover 层
            //    关闭的是**它自己**那层包装 call，不是本层的 ⇒ 本层的 close 根本不会被调用）
            //    ⇒ ⇒ 与 B 侧 不一致 ⇒ ⇒ 必须在这里（panic 展开点）补记一次。
            recordOnce(recorded, metrics, startNanos, handlerDoneNanos[0]);
            throw e;
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (RuntimeException | Error e) {
                    // 🔴 同上：handler 自身 panic 时，B 的 defer 也会跑 ⇒ 照记一条。
                    recordOnce(recorded, metrics, startNanos, handlerDoneNanos[0]);
                    throw e;
                }
                // ⚠️ 记「handler 跑完的时刻」⇒ 时长口径贴近 B 的「handler 返回时」（不含响应写回）。
                handlerDoneNanos[0] = System.nanoTime();
            }
        };
    }

    /**
     * 记一条样本（<b>幂等</b>：一次请求只记一条）。
     *
     * @param recorded       「已记过」标记
     * @param metrics        聚合器
     * @param startNanos     起点
     * @param handlerDoneNanos handler 结束时刻（0 = 没走到那一步）
     */
    private static void recordOnce(java.util.concurrent.atomic.AtomicBoolean recorded,
            RpcStatMetrics metrics, long startNanos, long handlerDoneNanos) {
        if (recorded.compareAndSet(false, true)) {
            long end = handlerDoneNanos > 0L ? handlerDoneNanos : System.nanoTime();
            metrics.add(Math.max(0L, end - startNanos));
        }
    }

    /** 链上的层名（供守门断言链序）。 */
    @Override
    public String layerName() {
        return "stat";
    }
}
