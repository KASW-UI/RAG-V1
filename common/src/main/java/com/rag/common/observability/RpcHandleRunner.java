package com.rag.common.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <b>非 gRPC Server 场景下的业务调用入口</b> —— 跑一次 handler，带超时控制与观测标记。
 *
 * <p><b>Go 出处</b>：Go 侧的等价物是 {@code context.WithTimeout(ctx, timeout)} 包裹 handler 调用：
 * <pre>
 * Go: timeoutCtx, cancel := context.WithTimeout(ctx, timeout)
 *     defer cancel()
 *     resp, err := handler(timeoutCtx, req)
 * </pre>
 * ⇒ ⇒ Go 的语义 = <b>「独立执行 + 到点取消」</b>，本类照此实现。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java}
 * 的 {@code public static <ReqT, RespT> RpcResult<RespT> handle(...)} 整段抄（L2 · D13）
 * ＋ <b>写入载体由「手写 OTel span」改为「Micrometer Observation + OTel 双载体」</b>（L4 · D4 / D-03）
 * ＋ <b>从 {@code RpcInterceptor} 拆成独立类</b>（L4 · D11 包压平）。
 *
 * <h2>⚠️ 与 gRPC 拦截器路径的<b>关键差异</b>（必须理解，否则会误用）</h2>
 * <p>本路径**有**「真正打断业务线程」的能力（{@code future.cancel(true)} ⇒ interrupt），
 * 而 gRPC 拦截器路径**没有**（grpc-java 的 {@code ServerCall#close()} 无法取消已在跑的 handler）
 * ⇒ ⇒ 业务 handler 若要感知超时，本路径可以 catch {@link InterruptedException}，
 * gRPC 路径只能靠 {@link DeadlineState#isExpired()} 主动自查。
 *
 * <h2>⚠️ 观测标记的判定顺序（照抄 RAG，顺序本身就是行为）</h2>
 * <pre>
 *   ① err == null                                  ⇒ 正常（只写 duration / slow）
 *   ② isTimeoutError(err)                          ⇒ **timeout**
 *   ③ 业务码集合（7 个）                             ⇒ **business**
 *   ④ 其余                                         ⇒ **system**
 * </pre>
 *
 * <p><b>⚠️ 超时分支返回的错误对象</b>：RAG 返回
 * {@code StatusRuntimeException(DEADLINE_EXCEEDED, desc="context deadline exceeded")}，
 * 而<b>写进遥测的</b>是另一个 {@code TimeoutException}（其 message 是 Go 的 status 文本）
 * ⇒ ⇒ 本类保留这个「<b>返回值与遥测文案不是同一个对象</b>」的细节
 * （RAG 的 DRIFT-010 修复留下的口径）。
 */
public final class RpcHandleRunner {

    /**
     * Go 真源 {@code service/common/observability/rpc.go:69} 在超时时给客户端的
     * <b>status description</b>：
     *
     * <pre>
     * Go: err := status.Error(codes.DeadlineExceeded, timeoutCtx.Err().Error())
     *     // timeoutCtx.Err() == context.DeadlineExceeded ⇒ .Error() == "context deadline exceeded"
     * </pre>
     */
    private static final String GO_DEADLINE_EXCEEDED_DESCRIPTION = "context deadline exceeded";

    /** 本路径的观测名（对应 RAG 侧的 OTel tracer 名 {@code rpc-handler}）。 */
    public static final String HANDLER_OBSERVATION_NAME = "rpc.handler";

    private RpcHandleRunner() {
    }

    /**
     * 跑一次业务调用（带超时控制与观测标记）。
     *
     * @param registry      观测注册表（可为 {@code null} ⇒ <b>不产出观测</b>，但超时语义不变）
     * @param method        方法名（观测的 contextual name）
     * @param req           请求
     * @param timeout       超时预算（{@code null} 或 {@code <= 0} ⇒ <b>不设超时</b>，直接同线程跑）
     * @param slowThreshold 慢调用阈值（{@code null} 或 {@code <= 0} ⇒ 回落
     *                       {@link ObservabilityCustomizer#DEFAULT_SLOW_THRESHOLD}）
     * @param handler       业务处理器
     * @return 结果（响应 + 错误，二者必有其一为 {@code null}）
     */
    public static <ReqT, RespT> RpcResult<RespT> handle(
            ObservationRegistry registry,
            String method,
            ReqT req,
            Duration timeout,
            Duration slowThreshold,
            RpcCallHandler<ReqT, RespT> handler) {

        final Duration finalSlow = (slowThreshold == null || slowThreshold.compareTo(Duration.ZERO) <= 0)
                ? ObservabilityCustomizer.DEFAULT_SLOW_THRESHOLD
                : slowThreshold;

        Observation observation = startObservation(registry, method);
        Observation.Context ctx = observation == null ? null : observation.getContext();
        long startNanos = System.nanoTime();

        try {
            if (timeout == null || timeout.compareTo(Duration.ZERO) <= 0) {
                // ── 无超时：同线程跑 ──────────────────────────────────────────
                RespT resp;
                Throwable err;
                try {
                    resp = handler.handle(req);
                    err = null;
                } catch (Throwable t) {
                    resp = null;
                    err = t;
                }
                markResult(ctx, method, err, elapsed(startNanos), finalSlow);
                return new RpcResult<>(resp, err);
            }

            // ── 有超时：独立线程跑业务，主线程等结果或超时 ──────────────────
            long deadlineNanos = saturatingToNanos(timeout);
            Object[] respHolder = new Object[1];
            Throwable[] errHolder = new Throwable[1];

            Future<?> workerFuture = ObservabilityRuntime.handleExecutor().submit(() -> {
                // 把 deadline bind 到当前 worker 线程，
                // 业务 catch InterruptedException 后通过 DeadlineState.isExpired() 感知
                try (DeadlineState.Scope scope = DeadlineState.bindRemainingNanos(deadlineNanos)) {
                    respHolder[0] = handler.handle(req);
                } catch (Throwable e) {
                    errHolder[0] = e;
                }
            });

            try {
                // 用**纳秒** ⇒ 亚毫秒的 timeout 不会被截成 0ms
                workerFuture.get(deadlineNanos, TimeUnit.NANOSECONDS);
                Duration duration = elapsed(startNanos);
                if (errHolder[0] != null) {
                    markResult(ctx, method, errHolder[0], duration, finalSlow);
                    return new RpcResult<>(null, errHolder[0]);
                }
                markResult(ctx, method, null, duration, finalSlow);
                @SuppressWarnings("unchecked")
                RespT resp = (RespT) respHolder[0];
                return new RpcResult<>(resp, null);
            } catch (TimeoutException te) {
                // 超时未完成：cancel(true) 触发业务线程 interrupt
                workerFuture.cancel(true);
                Duration duration = elapsed(startNanos);
                Status st = Status.DEADLINE_EXCEEDED.withDescription(GO_DEADLINE_EXCEEDED_DESCRIPTION);
                StatusRuntimeException dee = new StatusRuntimeException(st);
                // 遥测文案按 Go 的 status error 格式拼
                markTimeout(ctx, method, st.getCode().name(),
                        new TimeoutException(ObservabilityCustomizer.goStyleStatusMessage(
                                Status.Code.DEADLINE_EXCEEDED.name(), GO_DEADLINE_EXCEEDED_DESCRIPTION)),
                        duration);
                return new RpcResult<>(null, dee);
            } catch (InterruptedException ie) {
                // 调用线程被 interrupt（业务侧 cancel / Spring 容器关闭）
                Thread.currentThread().interrupt();
                workerFuture.cancel(true);
                markResult(ctx, method, ie, elapsed(startNanos), finalSlow);
                return new RpcResult<>(null, ie);
            } catch (ExecutionException ee) {
                // worker 内部抛未捕获异常（不应发生——handler 应自行 catch）；兜底标记为 systemError
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                markResult(ctx, method, cause, elapsed(startNanos), finalSlow);
                return new RpcResult<>(null,
                        cause instanceof Exception ? (Exception) cause : new RuntimeException(cause));
            }
        } finally {
            stopObservation(observation);
        }
    }

    // ── 内部：观测 ────────────────────────────────────────────────────────

    private static Observation startObservation(ObservationRegistry registry, String method) {
        if (registry == null) {
            return null;
        }
        Observation observation = Observation.createNotStarted(HANDLER_OBSERVATION_NAME, registry)
                .contextualName(method)
                .lowCardinalityKeyValue("rpc.system", "grpc")
                .lowCardinalityKeyValue("rpc.method", method);
        observation.start();
        return observation;
    }

    private static void stopObservation(Observation observation) {
        if (observation == null) {
            return;
        }
        try {
            observation.stop();
        } catch (RuntimeException ignore) {
            // ⚠️ 观测收尾失败**不得**影响业务结果
        }
    }

    // ── 内部：判定与写入 ──────────────────────────────────────────────────

    /**
     * 照抄 RAG 的 {@code markRpcResult}：OK ⇒ 正常；超时 ⇒ timeout；业务码 ⇒ business；其余 ⇒ system。
     */
    private static void markResult(Observation.Context ctx, String method, Throwable err,
                                   Duration duration, Duration slowThreshold) {
        String codeName = codeNameOf(err);
        putRpcAttrs(ctx, method, codeName);
        if (err == null) {
            ObservabilityCustomizer.markDurationAndSlow(ctx, duration, slowThreshold);
            return;
        }
        if (ObservabilityCustomizer.isTimeoutError(err)) {
            markTimeout(ctx, method, codeName, err, duration);
            return;
        }
        if (ObservabilityCustomizer.isBusinessGrpcCode(codeName)) {
            ObservabilityCustomizer.markBusinessError(ctx, codeValueOf(err),
                    ObservabilityCustomizer.goStyleStatusMessage(codeName, descriptionOf(err)),
                    duration);
            return;
        }
        ObservabilityCustomizer.markSystemError(ctx, err, duration);
    }

    /**
     * 超时分支的写入（<b>不传原始异常</b> ⇒ 用 Go 的 status 文本构造的 {@link TimeoutException}）。
     */
    private static void markTimeout(Observation.Context ctx, String method, String codeName,
                                    Throwable timeoutErr, Duration duration) {
        putRpcAttrs(ctx, method, codeName);
        ObservabilityCustomizer.markTimeout(ctx, timeoutErr, duration);
    }

    private static void putRpcAttrs(Observation.Context ctx, String method, String codeName) {
        if (ctx == null) {
            return;
        }
        ctx.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("rpc.system", "grpc"));
        ctx.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("rpc.method", method));
        ctx.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of(
                "rpc.grpc.status_code", String.valueOf(codeValueOf(codeName))));
    }

    /**
     * 从异常里取 gRPC 码名：{@code Status.fromThrowable(err)} 拿得到就用它的码，
     * 拿不到 ⇒ {@code UNKNOWN}（照抄 RAG 的 {@code markRpcResult} 口径）。
     */
    private static String codeNameOf(Throwable err) {
        if (err == null) {
            return Status.Code.OK.name();
        }
        Status st = Status.fromThrowable(err);
        if (st != null && st.getCode() != Status.Code.OK) {
            return st.getCode().name();
        }
        return Status.Code.UNKNOWN.name();
    }

    /** 从异常里取 gRPC 码的<b>数值</b>（业务码标记用）。 */
    private static int codeValueOf(Throwable err) {
        if (err == null) {
            return Status.Code.OK.value();
        }
        Status st = Status.fromThrowable(err);
        if (st != null && st.getCode() != Status.Code.OK) {
            return st.getCode().value();
        }
        return Status.Code.UNKNOWN.value();
    }

    /** 从异常里取 gRPC 码的<b>数值</b>（按码名）。 */
    private static int codeValueOf(String codeName) {
        return Status.Code.valueOf(codeName).value();
    }

    private static String descriptionOf(Throwable err) {
        Status st = Status.fromThrowable(err);
        return st == null ? null : st.getDescription();
    }

    // ── 内部：时长 ────────────────────────────────────────────────────────

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /** {@code Duration.toNanos()} 在超大 duration 上会抛 {@code ArithmeticException} ⇒ 这里饱和处理。 */
    private static long saturatingToNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
