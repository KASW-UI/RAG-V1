package com.rag.common.observability;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>HTTP 观测中间件</b> —— 观测标记 ＋ <b>超时 503</b> ＋ <b>客户端断连 499</b> ＋ <b>worker 池饱和 503</b>。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java:1105}
 * <pre>
 * func TimeoutHandler(timeout time.Duration) func(http.HandlerFunc) http.HandlerFunc {
 *     return func(next http.HandlerFunc) http.HandlerFunc {
 *         return func(w http.ResponseWriter, r *http.Request) {
 *             ctx, cancel := context.WithTimeout(r.Context(), timeout)
 *             defer cancel()
 *             done := make(chan struct{})
 *             go func() { next(w, r.WithContext(ctx)); close(done) }()
 *             select {
 *             case &lt;-done:
 *             case &lt;-ctx.Done():
 *                 statusCode := http.StatusServiceUnavailable
 *                 if errors.Is(ctx.Err(), context.Canceled) { statusCode = httpClientClosedRequest }  // 499
 *                 MarkTimeout(ctx, ctx.Err(), duration, httpAttrs(serviceName, req, statusCode)...)
 *                 http.Error(w, httpTimeoutBody, statusCode)
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java}
 * 整段抄（L2 业务语义层逐条保留 · D13）＋
 * <b>写入载体由「手写 OTel span」改为「Micrometer Observation + OTel 双载体」</b>（L4 · D4 / D-03）＋
 * <b>静态工厂改为 Spring Bean</b>（L4 · D3 starter 化）。
 *
 * <h2>三条「拒绝形状」的区别（<b>可观察</b>，不可合并）</h2>
 * <table border="1">
 *   <caption>三条拒绝路径的响应形状</caption>
 *   <tr><th>路径</th><th>状态码</th><th>body</th><th>Content-Type</th></tr>
 *   <tr><td>服务端超时</td><td>503</td><td>{@code "Request Timeout\n"}（15 字节）</td><td>{@code text/plain; charset=utf-8} ＋ {@code nosniff}</td></tr>
 *   <tr><td>客户端断连</td><td>499</td><td>同上</td><td>同上</td></tr>
 *   <tr><td>worker 池饱和</td><td>503</td><td><b>无</b></td><td><b>无</b></td></tr>
 * </table>
 * ⇒ ⚠️ **池饱和刻意与超时不同**：容量不足是「现在别来」，超时是「来不及了」⇒ 客户端策略不同。
 * ⇒ 池饱和的<b>裸 503</b>刻意对齐 B 的 {@code rest/handler/maxconnshandler.go}（无 body、无 Content-Type）。
 *
 * <h2>⚠️ worker 线程与容器线程的分工</h2>
 * <ul>
 *   <li><b>worker 线程</b>（{@code obs-http-worker}，最多 {@link #MAX_HTTP_WORKERS} 个）跑业务；</li>
 *   <li><b>容器线程</b>精确等到点（{@code CountDownLatch.await} 切片），
 *       并在等待期间<b>主动探测客户端断连</b>（{@link ClientDisconnectProbe}）；</li>
 *   <li>超时 / 断连 ⇒ 容器线程<b>先标记、再中断</b> ⇒ 业务抢不到响应（见
 *       {@link ObservabilityResponseWriter#markTimedOut()} 的注释）。</li>
 * </ul>
 */
public class HttpMiddleware implements Filter {

    private static final Logger log = LoggerFactory.getLogger(HttpMiddleware.class);

    /**
     * 客户端断连的状态码（nginx 风格约定）。
     *
     * <p>B 出处：{@code httpClientClosedRequest = 499}。
     */
    public static final int HTTP_CLIENT_CLOSED_REQUEST = 499;

    /**
     * HTTP 超时时 body 内容（B 的 {@code httpTimeoutBody}）。
     */
    public static final String HTTP_TIMEOUT_BODY = "Request Timeout";

    /**
     * worker 线程数上界。
     *
     * <p>⚠️ <b>不是 {@code newCachedThreadPool}（无上限）</b>：cached pool 的线程数按
     * 「到达率 × 下游耗时」<b>无界增长</b> ⇒ ⇒ 必须有上界。
     * <p>⚠️ <b>必须仍是 {@link SynchronousQueue}</b>：与 cached pool 同一形状 ⇒ 池满时
     * {@link ThreadPoolExecutor} 直接<b>拒绝</b>（抛 {@link RejectedExecutionException}）
     * ⇒ ⇒ 调用点转成<b>裸 503</b>（容量拒绝）⇒ 这正是「池饱和 503」这条路径的来源。
     */
    public static final int MAX_HTTP_WORKERS = 512;

    /**
     * Tomcat 的响应输出缓冲默认大小（{@code org.apache.coyote.ExpandBuffer} 的 {@code defaultBufferSize}）。
     *
     * <p>用途：判断「响应体能不能整块装进缓冲」⇒ 决定要不要由我们先设 {@code Content-Length}。
     */
    private static final int TOMCAT_RESPONSE_BUFFER_SIZE = 8192;

    /** 解析响应信封用的 mapper（**不**复用 Boot 的那个 —— 它可能带命名策略等配置）。 */
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Servlet Filter 专用 worker 线程池。
     *
     * <p>⚠️ <b>进程级静态</b> ⇒ 与 {@link ObservabilityRuntime} 的两个池同款处置：
     * <b>被终止后按需重建</b>（否则 surefire 一个模块多个上下文 ⇒ 前一个关闭后
     * {@code submit} 被拒 ⇒ 每个请求都变成 503）。
     */
    private static final AtomicReference<ThreadPoolExecutor> HTTP_EXECUTOR =
            new AtomicReference<>(newHttpExecutor());

    private final ObjectProvider<ObservationRegistry> registryProvider;
    private final ObservabilityProperties properties;
    private final String serviceName;

    /**
     * 🔴 <b>测试接缝</b>：断连探针可注入。
     *
     * <p>默认实现 = {@link ClientDisconnectProbe#probe}；单测注入「恒返回 DISCONNECTED」的探针
     * ⇒ 才能在**不启真 Tomcat** 的前提下覆盖「探针判定断连 ⇒ 499」这条分支。
     *
     * <p>⚠️ **这条接缝只为可测性存在，不改默认行为**（生产路径恒走
     * {@link ClientDisconnectProbe#probe}）。
     */
    private final DisconnectProbe probe;

    /**
     * 断连探针的函数形态（便于单测注入）。
     */
    @FunctionalInterface
    public interface DisconnectProbe {
        ClientDisconnectProbe.State probe(HttpServletRequest request, HttpServletResponse response);
    }

    public HttpMiddleware(ObjectProvider<ObservationRegistry> registryProvider,
                          ObservabilityProperties properties,
                          String serviceName) {
        this(registryProvider, properties, serviceName, ClientDisconnectProbe::probe);
    }

    /** 带探针接缝的构造器（**仅供单测**）。 */
    HttpMiddleware(ObjectProvider<ObservationRegistry> registryProvider,
                   ObservabilityProperties properties,
                   String serviceName,
                   DisconnectProbe probe) {
        this.registryProvider = registryProvider;
        this.properties = properties;
        this.serviceName = (serviceName == null || serviceName.isEmpty()) ? "unknown-service" : serviceName;
        this.probe = probe == null ? ClientDisconnectProbe::probe : probe;
    }

    private static ThreadPoolExecutor newHttpExecutor() {
        return new ThreadPoolExecutor(
                0,
                MAX_HTTP_WORKERS,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread t = new Thread(runnable, "obs-http-worker");
                    t.setDaemon(true);
                    return t;
                },
                // ⚠️ 满了就**拒绝**（抛 RejectedExecutionException）⇒ 调用点转成裸 503
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 取当前的 worker 池；<b>若上一轮 {@link #shutdown()} 已把它终止 ⇒ 按需重建一份新的</b>。
     */
    static ThreadPoolExecutor httpExecutor() {
        while (true) {
            ThreadPoolExecutor current = HTTP_EXECUTOR.get();
            if (!current.isShutdown()) {
                return current;
            }
            if (HTTP_EXECUTOR.compareAndSet(current, newHttpExecutor())) {
                return HTTP_EXECUTOR.get();
            }
        }
    }

    /**
     * 关闭 worker 池（Spring 容器关闭时由 {@code @PreDestroy} 调用）。
     *
     * <p><b>幂等</b>：重复调用安全（{@code shutdownNow} 重复 OK）。
     * <p>⚠️ 本类只在 <b>servlet 进程</b>注册 ⇒ ⇒ 纯 gRPC 进程<b>不会加载它</b> ⇒ 零 servlet 风险。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        HTTP_EXECUTOR.get().shutdownNow();
    }

    // ── Filter ──────────────────────────────────────────────────────────

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        ObservationRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            // 没有 ObservationRegistry ⇒ 观测能力整体缺席 ⇒ 放行（不静默拒绝流量）
            chain.doFilter(req, resp);
            return;
        }
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;
        Duration timeout = properties == null ? null : properties.getHttpTimeout();

        if (shouldSkipHTTPTimeout(httpReq) || timeout == null || timeout.compareTo(Duration.ZERO) <= 0) {
            runInline(registry, httpReq, httpResp, chain);
            return;
        }
        runWithTimeout(registry, httpReq, httpResp, chain, timeout);
    }

    // ── 内层预算（B 侧 gRPC 中间件那一层）· 入向 deadline 载体 ─────────────

    /**
     * 内层预算的<b>绝对</b>到期时刻（单调钟纳秒）。
     *
     * <p><b>为什么算的是「绝对时刻」而不是「剩余时长」</b>：
     * 业务跑在 worker 线程上，而 worker 是<b>排队后</b>才拿到线程的
     * ⇒ 若在 worker 里按「完整预算」绑定，排队耗掉的时间就白给了
     * ⇒ ⇒ 必须按「请求开始的时刻 + 预算」算一个绝对时刻，在 worker 里绑定同一个时刻。
     *
     * <p>⚠️ <b>为什么用 {@link DeadlineState#bindAbsoluteNanos(long)} 而不是
     * {@code bindRemainingNanos}</b>：前者绑的是<b>绝对时刻</b> ⇒ 与出向载体
     * （{@link GoStyleRequestDeadline}）可以指向同一刻（两载体的契约，见
     * {@link DeadlineState} 的类头）；后者是「从绑定瞬间起算」⇒ 会把排队时间补回去。
     *
     * @return 绝对到期时刻；{@link Long#MAX_VALUE} = <b>无内层预算</b>（⇒ 不绑）
     */
    private long innerDeadlineNanos(long startNanos) {
        Duration inner = properties == null ? null : properties.resolveInnerBudget();
        if (inner == null || inner.isZero() || inner.isNegative()) {
            return Long.MAX_VALUE;
        }
        long nanos;
        try {
            nanos = inner.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (nanos <= 0L || nanos > Long.MAX_VALUE - startNanos) {
            return Long.MAX_VALUE;
        }
        return startNanos + nanos;
    }

    // ── 路径一：无超时 ⇒ 同线程跑 ───────────────────────────────────────

    private void runInline(ObservationRegistry registry,
                            HttpServletRequest httpReq,
                            HttpServletResponse httpResp,
                            FilterChain chain) throws IOException, ServletException {
        ObservabilityResponseWriter rec = new ObservabilityResponseWriter(httpResp);
        Observation observation = startObservation(registry, httpReq);
        long startNanos = System.nanoTime();
        final Throwable[] failure = new Throwable[1];
        // 🔴 **入向 deadline 载体也要绑**：本路径只是「没有 HTTP 预算」，
        //    「内层预算」（B 侧 gRPC 中间件那一层）仍然存在 ⇒ DB 仍要被它兜住。
        long innerDeadline = innerDeadlineNanos(startNanos);
        try {
            if (innerDeadline == Long.MAX_VALUE) {
                chain.doFilter(httpReq, rec);
            } else {
                try (DeadlineState.Scope scope = DeadlineState.bindAbsoluteNanos(innerDeadline)) {
                    chain.doFilter(httpReq, rec);
                }
            }
        } catch (Throwable t) {
            failure[0] = t;
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        markHttpResult(observation, rec.statusCode(), rec.bodyBytes(), duration, failure[0]);
        flushCaptured(httpResp, rec, httpReq, duration);
        stopObservation(observation);
        rethrow(failure[0]);
    }

    // ── 路径二：有超时 ⇒ 独立线程跑业务，容器线程等 ───────────────────────

    private void runWithTimeout(ObservationRegistry registry,
                                HttpServletRequest httpReq,
                                HttpServletResponse httpResp,
                                FilterChain chain,
                                Duration timeout) throws IOException, ServletException {
        ObservabilityResponseWriter rec = new ObservabilityResponseWriter(httpResp);
        Observation observation = startObservation(registry, httpReq);
        final Throwable[] panicHolder = new Throwable[1];
        final CountDownLatch doneLatch = new CountDownLatch(1);
        long startNanos = System.nanoTime();

        Future<?> workerFuture;
        // 🔴 **内层预算的绝对时刻在「提交 worker 之前」算好** ⇒ worker 排队的时间也算在内
        //    （否则排队耗掉的 1.5s 不计入 ⇒ 业务实际可用预算会超过 B 侧的 2s）。
        final long innerDeadline = innerDeadlineNanos(startNanos);
        try {
            workerFuture = httpExecutor().submit(() -> {
                try {
                    // ⚠️ **必须在 worker 线程上绑定**（容器线程的 ThreadLocal 对 worker 不可见）
                    //    —— 与 {@link GoStyleRequestDeadline} 的类头「必须在 worker 线程上 set」同款纪律。
                    //    ⇒ 绑上之后 {@code DeadlineQueryTimeoutInjector} 才能给每条 SQL 设 query timeout
                    //    ⇒ PG 在内层预算到点时收到 CancelRequest ⇒ 事务回滚（**副作用不落库**）。
                    if (innerDeadline == Long.MAX_VALUE) {
                        chain.doFilter(httpReq, rec);
                    } else {
                        try (DeadlineState.Scope scope = DeadlineState.bindAbsoluteNanos(innerDeadline)) {
                            chain.doFilter(httpReq, rec);
                        }
                    }
                } catch (Throwable t) {
                    panicHolder[0] = t;
                } finally {
                    doneLatch.countDown();
                }
            });
        } catch (RejectedExecutionException saturated) {
            // ── worker 池饱和 ⇒ 容量拒绝 ──
            // 形状刻意对齐 B 的 rest/handler/maxconnshandler.go：裸 503，**无 body、无 Content-Type**。
            log.warn("http worker pool saturated (max={}), shedding request {}",
                    MAX_HTTP_WORKERS, httpReq.getRequestURI());
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            markTimeout(observation, new java.util.concurrent.TimeoutException("http worker pool saturated"),
                    duration);
            httpResp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            stopObservation(observation);
            return;
        }

        // 容器线程等 latch 到点即返回：精度对齐 B 的 select{<-ctx.Done()}
        boolean workerDone;
        boolean clientDisconnected = false;
        boolean probeBudgetLogged = false;
        try {
            final long waitStartNanos = System.nanoTime();
            final long budgetNanos = timeout.toNanos();
            workerDone = false;
            while (true) {
                long remainingNanos = budgetNanos - (System.nanoTime() - waitStartNanos);
                if (remainingNanos <= 0L) {
                    break;
                }
                long sliceMs = Math.max(1L, Math.min(
                        ClientDisconnectProbe.probeIntervalMillis(),
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                workerDone = doneLatch.await(sliceMs, TimeUnit.MILLISECONDS);
                if (workerDone) {
                    break;
                }
                // 预算用尽 ⇒ 落到下面的超时分支（**不缩短也不延长**总窗口）
                if (budgetNanos - (System.nanoTime() - waitStartNanos) <= 0L) {
                    break;
                }
                ClientDisconnectProbe.State probeState = probe.probe(httpReq, httpResp);
                if (probeState == ClientDisconnectProbe.State.DISCONNECTED) {
                    clientDisconnected = true;
                    break;
                }
                if (probeState == ClientDisconnectProbe.State.BUDGET_EXHAUSTED) {
                    // 本请求的 1xx 行数预算已用尽 ⇒ 不再写探针字节
                    // ⇒ **等待语义完全不变**（仍等到 worker 完成或超时 ⇒ 总窗口不变）
                    // ⇒ 唯一代价：超长请求里客户端中途断连**不再被提前发现**
                    if (!probeBudgetLogged) {
                        probeBudgetLogged = true;
                        log.info("[HTTP] 1xx 探针行数预算用尽（cap={}）⇒ 本请求不再写探针字节",
                                ClientDisconnectProbe.maxProbeLines());
                    }
                    continue;
                }
                // ALIVE / UNAVAILABLE ⇒ 什么都不做，继续等（fail-open）
            }
        } catch (InterruptedException ie) {
            // 容器关闭 / 请求被容器取消：B 侧对应 ctx.Err() == context.Canceled
            Thread.currentThread().interrupt();
            // 🔴 **先标记、后中断**（同超时分支的理由）：否则业务可能在标记落地前就 commit 了响应
            rec.markClientCancelled();
            workerFuture.cancel(true);
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            markTimeout(observation, new java.util.concurrent.TimeoutException(
                    "client cancelled: " + ie.getMessage()), duration);
            rec.writeTimeoutResponseQuietly(HTTP_CLIENT_CLOSED_REQUEST, httpReq);
            stopObservation(observation);
            return;
        }

        if (clientDisconnected) {
            // ===== 探针判定「客户端已断连」⇒ 对齐 B 的 ctx.Err()==context.Canceled 分支 =====
            // 🔴 **先标记、后中断** —— recordClientDisconnected 也会标记，但它在中断**之后**
            // ⇒ 业务可能在标记落地前就 commit 了响应 ⇒ 499 响应被抢走。
            rec.markClientCancelled();
            workerFuture.cancel(true);
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            recordClientDisconnected(observation, httpReq, duration);
            rec.writeTimeoutResponseQuietly(HTTP_CLIENT_CLOSED_REQUEST, httpReq);
            stopObservation(observation);
            return;
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        Throwable panic = panicHolder[0];
        if (panic != null) {
            // 区分「客户端断连」（499）与「系统 panic」（500）
            boolean clientCancelled = isClientCancelledException(panic);
            int statusCode = clientCancelled ? HTTP_CLIENT_CLOSED_REQUEST : 500;
            if (clientCancelled) {
                rec.markClientCancelled();
                markTimeout(observation, new java.util.concurrent.TimeoutException(
                        "client cancelled: " + panic.getMessage()), duration);
                // 499 **真的写进响应**（B 的 `http.Error(w, body, 499)` ⇒ 客户端真的收到 499）
                rec.writeTimeoutResponseQuietly(statusCode, httpReq);
                stopObservation(observation);
                return;
            }
            markSystemError(observation, new RuntimeException(panic.getMessage(), panic), duration);
            stopObservation(observation);
            // M12 修复：B 侧版 observability/http.go:75 是 `panic(p)` 原样重抛，
            // 交给 B 侧的 recovery 中间件渲染 500 ⇒ 这里按原类型重抛（不改异常类型）。
            rethrow(panic);
            return;
        }
        if (!workerDone) {
            // 对齐 B 侧版 ctx.Done() == DeadlineExceeded → 503
            // 🔴 **先标记、后中断**（同 B 侧注释）：业务可能在标记落地前就把响应 commit 掉
            rec.markTimedOut();
            workerFuture.cancel(true);
            markTimeout(observation, new java.util.concurrent.TimeoutException("http timeout"), duration);
            // ⚠️ 走 rec ⇒ **与 worker 线程对底层的写互斥**
            rec.writeTimeoutResponseQuietly(HttpServletResponse.SC_SERVICE_UNAVAILABLE, httpReq);
            stopObservation(observation);
            return;
        }
        markHttpResult(observation, rec.statusCode(), rec.bodyBytes(), duration, null);
        flushCaptured(httpResp, rec, httpReq, duration);
        stopObservation(observation);
    }

    // ── 观测 ────────────────────────────────────────────────────────────

    /** HTTP 侧的观测名。 */
    public static final String HTTP_OBSERVATION_NAME = "http.server.requests";

    private Observation startObservation(ObservationRegistry registry, HttpServletRequest httpReq) {
        Observation observation = Observation.createNotStarted(HTTP_OBSERVATION_NAME, registry)
                .lowCardinalityKeyValue("service.name", serviceName)
                .lowCardinalityKeyValue("http.route", httpReq.getRequestURI())
                .lowCardinalityKeyValue("http.method", httpReq.getMethod());
        observation.start();
        return observation;
    }

    private void stopObservation(Observation observation) {
        if (observation == null) {
            return;
        }
        try {
            observation.stop();
        } catch (RuntimeException ignore) {
            // ⚠️ 观测收尾失败**不得**影响业务响应
        }
    }

    /**
     * 判定并写入（照抄 RAG {@code markHttpResult} 的判定顺序）：
     * <pre>
     *   ① 有异常                     ⇒ system
     *   ② 信封 code != 0 &amp;&amp; != 200 ⇒ business   ← 业务码优先于状态码
     *   ③ 否则 statusCode &gt;= 400      ⇒ system
     *   ④ 否则                          ⇒ 正常（只写 duration / slow）
     * </pre>
     */
    private void markHttpResult(Observation observation, int statusCode, byte[] body,
                                Duration duration, Throwable failure) {
        if (observation == null) {
            return;
        }
        Observation.Context ctx = observation.getContext();
        observation.lowCardinalityKeyValue("http.status_code", String.valueOf(statusCode));
        Envelope envelope = parseEnvelope(body);
        if (failure != null) {
            // ⚠️ 异常 ⇒ 容器会渲染成 5xx ⇒ 按 system error 记（B 侧 panic 分支同理）
            ObservabilityCustomizer.markSystemError(ctx, failure, duration);
        } else if (envelope != null
                && envelope.code != 0
                && envelope.code != ObservabilityCustomizer.SUCCESS_BIZ_CODE) {
            ObservabilityCustomizer.markBusinessError(ctx, envelope.code, envelope.msg, duration);
        } else if (statusCode >= 400) {
            ObservabilityCustomizer.markSystemError(
                    ctx, new RuntimeException("http status " + statusCode), duration);
        } else {
            ObservabilityCustomizer.markDurationAndSlow(ctx, duration, slowThreshold());
        }
    }

    private void markTimeout(Observation observation, Throwable err, Duration duration) {
        if (observation == null) {
            return;
        }
        ObservabilityCustomizer.markTimeout(observation.getContext(), err, duration);
    }

    private void markSystemError(Observation observation, Throwable err, Duration duration) {
        if (observation == null) {
            return;
        }
        ObservabilityCustomizer.markSystemError(observation.getContext(), err, duration);
    }

    /**
     * 记录「客户端已断连」（<b>499</b>）—— 对齐 B 的 {@code ctx.Err() == context.Canceled} 分支。
     *
     * <p><b>唯一的 499 记录入口</b>：① {@link #flushCaptured} 写 socket 失败（老路径）；
     * ② {@code doFilter} 里「主动探测」判定断连 ⇒ 两处走同一套代码，
     * 避免「同一个语义两套实现」在后续演进里漂移。
     */
    private void recordClientDisconnected(Observation observation,
                                          HttpServletRequest httpReq,
                                          Duration duration) {
        markTimeout(observation, new java.util.concurrent.TimeoutException("context canceled"), duration);
        // ⚠️ 为什么还要打一条日志：B 侧 499 之所以「可见」是因为 B 侧 的 access log 会打
        //   `[HTTP] 499 - GET /x - ip - duration`；本仓**没有 access log**
        // ⇒ 若只写 span，没接 trace 后端时这条事件依然不可见 ⇒ 必须留一条日志。
        log.warn("[HTTP] {} - {} {} - {} - duration {}ms",
                HTTP_CLIENT_CLOSED_REQUEST,
                httpReq.getMethod(),
                httpReq.getRequestURI(),
                remoteAddrOf(httpReq),
                duration.toMillis());
    }

    /** 慢阈值：配置优先 ⇒ 空则回退 env 口径（与 B 的三级优先级一致）。 */
    private Duration slowThreshold() {
        Duration configured = properties == null ? null : properties.getSlowThreshold();
        return configured != null ? configured : ObservabilityCustomizer.slowThreshold();
    }

    // ── 响应收尾 ────────────────────────────────────────────────────────

    /**
     * 把捕获的响应写给客户端；<b>写失败且判定为「客户端已断连」⇒ 记 499</b>（不再向上抛）。
     */
    private void flushCaptured(HttpServletResponse httpResp,
                               ObservabilityResponseWriter rec,
                               HttpServletRequest httpReq,
                               Duration duration) throws IOException {
        try {
            // ⚠️⚠️ **保形**：必须先把 Content-Length 定下来，否则下面那次 flush 会让响应
            //   退化成 `Transfer-Encoding: chunked` ⇒ 与 B 的 net/http（算 Content-Length）不一致。
            byte[] captured = rec.bodyBytes();
            if (captured.length <= TOMCAT_RESPONSE_BUFFER_SIZE
                    && httpResp.getHeader("Content-Length") == null
                    && statusAllowsContentLength(httpResp.getStatus())) {
                httpResp.setContentLength(captured.length);
            }
            rec.flushTo(httpResp);
            // ⚠️⚠️ **必须显式 flush**，否则断连根本观测不到：
            //   Tomcat 的 OutputBuffer 默认缓冲 8192 字节 ⇒ 小 body 写进去只进了缓冲区，
            //   **socket 一个字节都没发** ⇒ 客户端已 RST 也照样「写成功」⇒ 不抛异常
            //   ⇒ 499 分支永远进不去。
            httpResp.getOutputStream().flush();
        } catch (IOException io) {
            if (!isClientCancelledException(io)) {
                // 非「客户端断连」的 IO 失败（例如后端存储坏了）⇒ 原样上抛，保持原有行为。
                throw io;
            }
            // ===== 客户端已断连 ⇒ 记 499（对齐 B 的 ctx.Canceled 分支）=====
            rec.markClientCancelled();
        }
    }

    /**
     * D-11 修复：对齐 B 侧版 {@code http.Error(w, httpTimeoutBody, statusCode)}。
     *
     * <p>B 的 {@code http.Error} 做三件事：① {@code WriteHeader(status)}；
     * ② {@code Header().Set("Content-Type", "text/plain; charset=utf-8")}；
     * ③ 写 {@code body + "\n"}。
     * <p>⚠️ 另加 {@code X-Content-Type-Options: nosniff}（B 侧 1.11 起的 {@code http.Error} 行为）。
     * <p>⚠️ 已知<b>不可修</b>的残差：{@code Content-Type} 里分号后的<b>空格</b> ——
     * Tomcat 的 {@code setContentType} 会把 Content-Type 解析成 MediaType 再序列化，序列化时吃掉空格
     * ⇒ 发出 {@code text/plain;charset=utf-8} ⇒ 该空格在 Tomcat 上无任何写法能产出 ⇒ 归「框架限制」。
     */
    static void writeTimeoutResponse(HttpServletResponse resp, int statusCode) throws IOException {
        resp.setStatus(statusCode);
        resp.setContentType("text/plain; charset=utf-8");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        // 🔴 **显式定 Content-Length** —— B 的 `http.Error` → `fmt.Fprintln(w, error)` 写进 net/http 的缓冲
        // ⇒ 收尾时 net/http 按缓冲内容算长度 ⇒ 线上是 `Content-Length: 15`。
        //   而这里用 `getWriter().write(...)` + `flush()` ⇒ flush 即提交 ⇒ Tomcat 已经没机会算长度
        // ⇒ ⇒ 显式写死长度（body 固定是 "Request Timeout\n" = **16** 字节）。
        // ⚠️ **RAG 侧的注释写的是「15 字节」，那是笔误**（"Request Timeout" 15 字符 + '\n' 1 字节 = 16）
        // ⇒ ⇒ RAG 的**代码**是对的（`HTTP_TIMEOUT_BODY.length() + 1`）⇒ 本仓照代码不照注释。
        resp.setContentLength(HTTP_TIMEOUT_BODY.length() + 1);
        resp.getWriter().write(HTTP_TIMEOUT_BODY);
        resp.getWriter().write('\n');
        resp.getWriter().flush();
    }

    /**
     * 写超时 / 断连响应；<b>写失败（客户端已走）不向上抛</b>。
     *
     * <p>B 的 {@code fmt.Fprintln} <b>忽略写错误</b> ⇒ B 侧 在「客户端已断连」时只是「写不进去」，
     * <b>不会</b>把异常抛给框架 ⇒ ⇒ 这里吞掉写异常（记一条日志）。
     */
    static void writeTimeoutResponseQuietly(HttpServletResponse resp, int statusCode,
                                            HttpServletRequest req) {
        try {
            writeTimeoutResponse(resp, statusCode);
        } catch (IOException io) {
            if (isClientCancelledException(io)) {
                log.warn("[HTTP] {} - {} {} - {} - client closed before response was written",
                        statusCode,
                        req == null ? "-" : req.getMethod(),
                        req == null ? "-" : req.getRequestURI(),
                        remoteAddrOf(req));
            } else {
                log.warn("[HTTP] {} - failed to write response: {}", statusCode, io.toString());
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    /**
     * 对应 B 侧版 {@code func shouldSkipHTTPTimeout(r *http.Request) bool}。
     */
    private static boolean shouldSkipHTTPTimeout(HttpServletRequest req) {
        if (req == null) {
            return false;
        }
        String upgrade = req.getHeader("Upgrade");
        if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
            return true;
        }
        String accept = req.getHeader("Accept");
        return accept != null && accept.equalsIgnoreCase("text/event-stream");
    }

    /**
     * 判断异常是否是「客户端断开连接」语义（对齐 B 侧版 {@code ctx.Err() == context.Canceled}）。
     */
    private static boolean isClientCancelledException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof EOFException) {
                return true;
            }
            String className = cur.getClass().getName();
            if (className.contains("ClientAbortException") || className.contains("ClientAbort")) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 状态码是否允许带 {@code Content-Length}（204 / 304 不允许）。 */
    private static boolean statusAllowsContentLength(int status) {
        return status >= 200 && status != 204 && status != 304;
    }

    /** 客户端 IP（不含端口 —— Servlet API 只暴露 IP）。 */
    private static String remoteAddrOf(HttpServletRequest req) {
        if (req == null) {
            return "-";
        }
        String addr = req.getRemoteAddr();
        return addr == null || addr.isEmpty() ? "-" : addr;
    }

    /**
     * 按原类型重抛（保持容器看到的异常类型不变 —— B 侧是原样 {@code panic(p)}）。
     */
    private static void rethrow(Throwable t) throws IOException, ServletException {
        if (t == null) {
            return;
        }
        if (t instanceof IOException io) {
            throw io;
        }
        if (t instanceof ServletException se) {
            throw se;
        }
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new ServletException(t);
    }

    /**
     * B 的 HTTP 信封（只有 {@code code} / {@code msg} 两个字段被读取）。
     *
     * <p>⚠️ <b>字段名大小写敏感</b>（{@code code} / {@code msg}）⇒ Jackson 默认敏感 ✔。
     */
    static final class Envelope {
        public int code;
        public String msg;
    }

    /**
     * 解析响应信封。
     *
     * <p>⚠️ <b>「不存在」的判定</b>：{@code code == 0 && msg 为空} ⇒ 视作<b>没有信封</b>
     * ⇒ ⇒ 走「status ≥ 400 ⇒ system」的分支。
     * <p>⚠️ <b>解析失败一律当无信封</b>（B 侧 {@code json.Unmarshal} 出错也是 nil）。
     */
    static Envelope parseEnvelope(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Envelope envelope = ENVELOPE_MAPPER.readValue(body, Envelope.class);
            if (envelope == null) {
                return null;
            }
            if (envelope.code == 0 && (envelope.msg == null || envelope.msg.isEmpty())) {
                return null;
            }
            return envelope;
        } catch (Exception e) {
            return null;
        }
    }

    /** 测试用：当前 worker 池是否处于终止态。 */
    static boolean poolTerminated() {
        return HTTP_EXECUTOR.get().isShutdown();
    }

    /** 测试用：worker 池当前的最大线程数。 */
    static int maxWorkers() {
        return HTTP_EXECUTOR.get().getMaximumPoolSize();
    }

    /** 仅供记录起始时刻用（避免各处散落 {@code System.nanoTime()}）。 */
    static Instant now() {
        return Instant.now();
    }
}
