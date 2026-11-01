package com.rag.common.communityfact;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>对应 Go 版 {@code service/common/communityfact/process.go}（69 行）的
 * {@code func RunDispatcher(...)} ＋ {@code func deliveryErrorCode(err error) string}。
 *
 * <p>职责：dispatcher 循环把 frozen event 投递到 DC；批次 16，超时 10s，节奏 1s。
 *
 * <p><b>⚠️ 拆出后的落点差异</b>：RAG 侧 {@code RunDispatcher} 与 {@code deliveryErrorCode}
 * 都在 {@code Dispatcher.java} 里 ⇒ ⇒ RAG-V1 拆成本文件（映射表 §1.2 第 145 行 / 决策锁 L-7 第 6 条：
 * 「{@code RunDispatcher} 循环拆到 {@code EventDispatcher.java}」）
 * ⇒ ⇒ 调用点由 {@code Dispatcher.run(...)} 改为 {@code EventDispatcher.run(...)}
 * ⇒ ⇒ {@code DispatcherHandle} 随之落为本类的<b>嵌套</b>类 ⇒ **import 要同步改**。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>Go 版 time.Ticker + for-loop → Java {@code CompletableFuture.get(1s)} + stopSignal 让 1s 节奏可中断</li>
 *   <li>Go 版 ctx.Done → Java {@link DispatcherHandle#stop()} 让业务方主动停止</li>
 *   <li>Go 版 context.WithTimeout 10s + defer cancel() → Java {@code CompletableFuture.get(10s)} + {@code future.cancel(true)}（cancel(true) 给业务线程发 interrupt）</li>
 * </ul>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Dispatcher.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
public final class EventDispatcher {

    /**
     * 🔴🔴 <b>DRIFT-CF-018 修复：调用方先造句柄</b>（Go-faithful 的停止能力入口）。
     *
     * <p><b>Go 出处</b>：{@code service/comment/rpc/cmd/fact-dispatch/main.go:68}
     * （{@code ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)}）
     * ＋ {@code :69}（{@code defer stop()}）。
     * <p><b>语义等价论证</b>：Go 侧「停止 dispatcher」= 让<b>调用方自己</b>的 ctx 变成 done
     * ⇒ 停止能力<b>在 dispatcher 被调用之前</b>就已属于调用方
     * ⇒ Java 侧对应物 = 本工厂方法（调用方先拿到句柄，再交给 {@code run}）。
     * <p><b>为什么不能反过来</b>（旧形态为什么必然坏）：旧形态由 {@code run} 内部新建句柄、
     * 且<b>只在循环退出后</b>返回 ⇒ 阻塞形态（{@code once=false}）的调用方<b>永远拿不到句柄</b>
     * ⇒ {@code @PreDestroy} 停不下来 ⇒ 进程关不掉 ⇒ 这就是 DRIFT-CF-018。
     *
     * <p><b>用法</b>：
     * <pre>{@code
     * EventDispatcher.DispatcherHandle handle = EventDispatcher.newHandle();  // ← 对应 Go 的 ctx, stop := ...
     * // 容器关闭回调：
     * //   @PreDestroy void shutdown() { handle.stop(); }          // ← 对应 Go 的 signal handler
     * EventDispatcher.run(ctx, dispatcher, sender, logger, false, handle, false);
     * }</pre>
     */
    public static DispatcherHandle newHandle() {
        return DispatcherHandle.create();
    }

    /**
     * 默认配置版：<b>不</b>注册 JVM shutdown hook（Spring 化推荐路径：业务用 {@code @PreDestroy} 显式
     * 调 {@link EventDispatcher.DispatcherHandle#stop()}）。
     * <p>对应 Go 版 {@code func RunDispatcher(ctx context.Context, dispatcher Dispatcher, sender Sender, logger *slog.Logger, once bool) error}。
     *
     * <p><b>Spring 化变更</b>：原默认版本注册 JVM shutdown hook 在进程退出时兜底；但 JVM hook 在 Spring
     * 容器场景下不可控（与 SpringApplication 的 graceful shutdown 时序冲突，且在测试 / 反复创建场景下
     * 累积超限）。新版默认不注册，业务模块应：
     * <pre>{@code
     * // Spring Boot 业务模块：把 DispatcherHandle 装到 Spring 容器，@PreDestroy 触发 stop
     * @Component
     * class DispatcherLifecycle {
     *     private final DispatcherHandle handle;
     *     DispatcherLifecycle(DispatcherHandle handle) { this.handle = handle; }
     *     @PreDestroy void shutdown() { handle.stop(); }
     * }
     * }</pre>
     *
     * <p>🔴 <b>DRIFT-CF-018（2026-10-29 修）：本重载在 {@code once=false} 时<b>不可用</b>。</b>
     * <p><b>Go 出处</b>：{@code service/common/communityfact/process.go:20}
     * （{@code RunDispatcher} <b>只收 ctx、不返回任何句柄</b>）
     * ＋ {@code process.go:56-60}（{@code select { case <-ctx.Done(): return nil; case <-ticker.C: }}）
     * ＋ <b>调用方</b> {@code service/comment/rpc/cmd/fact-dispatch/main.go:68}
     * （{@code ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)}）
     * ＋ {@code :77}（{@code return communityfact.RunDispatcher(ctx, …)}）。
     * <p><b>语义等价论证</b>：Go 侧「停止能力」= <b>调用方持有的 ctx 的 cancel</b>
     * ⇒ 它在 {@code RunDispatcher} 被调用<b>之前</b>就已存在 ⇒ 调用方从那一刻起就能停。
     * ⇒ 而本重载返回的 {@link EventDispatcher.DispatcherHandle} 只在<b>循环退出之后</b>才交出
     * ⇒ ⇒ 阻塞版 {@code run(once=false)} 的调用方<b>永远拿不到句柄</b>
     * ⇒ ⇒ {@code @PreDestroy} 停不下来 ⇒ 这就是 DRIFT-CF-018。
     * <p><b>正确用法</b>（Go-faithful）：<b>调用方先造句柄</b>，再交给阻塞式 run：
     * <pre>{@code
     * EventDispatcher.DispatcherHandle handle = EventDispatcher.newHandle();   // ← 对应 Go 的 ctx, stop := signal.NotifyContext(...)
     * // ... 容器关闭回调里调 handle.stop()（对应 Go 的 signal handler）
     * EventDispatcher.run(ctx, dispatcher, sender, logger, false, handle, false);
     * }</pre>
     *
     * @deprecated 阻塞式重载在 {@code once=false} 时交不出句柄（DRIFT-CF-018）⇒
     *     请改用 {@link #run(Context, Dispatcher, EventSender, Logger, boolean, DispatcherHandle, boolean)}
     *     （调用方先造句柄）· Spring 容器内请用 {@link #runAsync}。
     */
    @Deprecated(forRemoval = true)
    public static DispatcherHandle run(Context ctx, Dispatcher dispatcher, EventSender sender,
                                       Logger logger, boolean once) {
        return run(ctx, dispatcher, sender, logger, once, false);
    }

    /**
     * 对应 Go 版 {@code func RunDispatcher(ctx context.Context, dispatcher Dispatcher, sender Sender, logger *slog.Logger, once bool) error}。
     *
     * <p><b>DRIFT-015 修复</b>：Go 版调用方可以 {@code cancel()} ctx 主动退出；Java 版返回 {@link EventDispatcher.DispatcherHandle}，
     * 调用方调 {@link EventDispatcher.DispatcherHandle#stop()} 立即中断循环（不等 JVM shutdown）。
     *
     * <p><b>DRIFT-008 修复 + Spring 化</b>：{@code registerShutdownHook} 参数控制是否注册 JVM shutdown hook：
     * <ul>
     *   <li>{@code false}（<b>默认</b>）：不注册 JVM hook；业务必须显式调 {@link EventDispatcher.DispatcherHandle#stop()}
     *       （推荐用 {@code @PreDestroy}）—— Spring 化首选路径，避免 hook 触发时序冲突与测试场景累积。</li>
     *   <li>{@code true}：JVM 退出时自动停止 dispatcher；适合不接 Spring 容器的独立进程。
     *       注意：JVM hook 触发时 Spring 容器已关闭，dispatcher 自身资源被清理即可。</li>
     * </ul>
     *
     * <p><b>DRIFT-008 二次修复</b>：单次 dispatch 10s 超时后不仅返回错误，还 {@code future.cancel(true)} 给业务线程发 interrupt，
     * 业务函数如果响应 {@link InterruptedException} 会立即停（对齐 Go 版 context.WithTimeout 的 cancel 语义）。
     *
     * <p>🔴 <b>DRIFT-CF-018（2026-10-29 修）：本重载在 {@code once=false} 时<b>不可用</b></b>，理由同
     * {@link #run(Context, Dispatcher, EventSender, Logger, boolean)} 的 javadoc。
     *
     * @deprecated 阻塞式重载在 {@code once=false} 时交不出句柄（DRIFT-CF-018）⇒
     *     请改用 {@link #run(Context, Dispatcher, EventSender, Logger, boolean, DispatcherHandle, boolean)}。
     */
    @Deprecated(forRemoval = true)
    public static DispatcherHandle run(Context ctx, Dispatcher dispatcher, EventSender sender,
                                       Logger logger, boolean once, boolean registerShutdownHook) {
        return run(ctx, dispatcher, sender, logger, once, registerShutdownHook, null, null);
    }

    /**
     * 🔴🔴 <b>DRIFT-CF-018 修复后的主入口</b>：阻塞式投递循环，<b>句柄由调用方持有</b>。
     *
     * <p><b>Go 出处（两层都要）</b>：
     * <ul>
     *   <li><b>被调方</b>：{@code service/common/communityfact/process.go:20}
     *       （{@code func RunDispatcher(ctx context.Context, …, once bool) error} —— <b>不返回句柄</b>）
     *       ＋ {@code process.go:56-60}（{@code case <-ctx.Done(): return nil} ⇒ 循环唯一的退出条件是 <b>ctx</b>）；</li>
     *   <li><b>调用方</b>：{@code service/comment/rpc/cmd/fact-dispatch/main.go:68}
     *       （{@code ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)}）
     *       ＋ {@code :69}（{@code defer stop()}）＋ {@code :77}
     *       （{@code return communityfact.RunDispatcher(ctx, …, once)}）。</li>
     * </ul>
     *
     * <p><b>语义等价论证（为什么「句柄归调用方」才是 Go 的形状）</b>：
     * <ol>
     *   <li>Go 侧停止 dispatcher 的<b>唯一</b>途径 = 让<b>调用方自己的</b> ctx 变成 done
     *       ⇒ 停止能力<b>从调用那一刻起就归调用方</b>，与 dispatcher 内部循环无关；</li>
     *   <li>Go 侧 {@code RunDispatcher} <b>不返回任何东西</b> ⇒ 「循环结束后返回一个句柄」这件事
     *       <b>在 Go 里没有对应物</b> ⇒ Java 侧那个「返回句柄」的重载是<b>无出处的 idiomatization</b>
     *       （AGENTS.md §3.2）⇒ 它在阻塞形态下<b>必然交不出句柄</b>（DRIFT-CF-018）；</li>
     *   <li>⇒ Java 侧对应物 = <b>调用方先 {@link #newHandle()} 造句柄</b>，再把它交给本方法
     *       ⇒ 调用方可以在<b>任意线程、任意时刻</b>（哪怕 {@code run} 还在阻塞）调 {@code handle.stop()}
     *       ⇒ 与 Go 的「signal handler 随时 cancel」<b>形状一致</b>。</li>
     * </ol>
     *
     * <p><b>⚠️ {@code handle} 必须在 {@code run} 返回<b>之前</b>就可用</b> ——
     * 这正是旧形态（{@code run} 返回句柄）做不到的事，也是本方法存在的全部理由。
     *
     * <p><b>幂等性</b>：{@code handle.stop()} 可重复调用（见 {@link EventDispatcher.DispatcherHandle#stop()}）。
     * 若在 {@code run} <b>启动前</b>就 {@code stop()} ⇒ 停止信号已置位 ⇒ 循环在第一个 1s 栅格处立即退出
     * （Go 侧等价：ctx 在调用前就已取消 ⇒ {@code select} 立即命中 {@code ctx.Done()}）。
     *
     * @param handle 调用方持有的句柄（{@code null} ⇒ 本方法内部新建，<b>但那样就退回了 DRIFT-CF-018 的老形态</b>）
     * @return 与 {@code handle} 同一个句柄（便于链式使用）
     */
    public static DispatcherHandle run(Context ctx, Dispatcher dispatcher, EventSender sender,
                                       Logger logger, boolean once, DispatcherHandle handle,
                                       boolean registerShutdownHook) {
        return run(ctx, dispatcher, sender, logger, once, registerShutdownHook, null, handle);
    }

    /**
     * 🔴🔴 Spring 化路径必须用这个：<b>非阻塞</b>启动投递循环。
     *
     * <p><b>为什么不能直接用 {@link #run}</b>：
     * {@code run} 是<b>阻塞式</b>的 —— 它在<b>调用线程</b>上跑整个 {@code while} 循环，
     * 直到 {@link EventDispatcher.DispatcherHandle#stop()} 被调用才返回。
     * 而业务域的调用点都在<b>生命周期回调</b>里：
     * <ul>
     *   <li>{@code @PostConstruct start()} ⇒ 阻塞的是 <b>Spring 上下文刷新线程</b>
     *       ⇒ 上下文永远刷新不完 ⇒ <b>整个服务起不来</b>；</li>
     *   <li>{@code @EventListener(ApplicationReadyEvent.class)} ⇒ 阻塞的是
     *       <b>{@code SpringApplication.run()} 的主线程</b> ⇒ 进程启动后主线程永久占用。</li>
     * </ul>
     * ⇒ 这不是「配置没开就没事」的问题：只要把开关打开，症状就是「服务起不来」，
     * 而这类缺陷<b>默认关闭时测不出来</b>（绿测试 + 起不来）。
     *
     * <p><b>本方法的行为</b>：
     * <ul>
     *   <li>循环跑在一条<b>守护线程</b>（{@code communityfact-dispatcher}）上；</li>
     *   <li><b>立即</b>返回 {@link EventDispatcher.DispatcherHandle}（句柄在进入循环<b>之前</b>就发布，
     *       所以调用方能立刻拿到它去 {@code stop()}）；</li>
     *   <li>{@code once = true} 时，那一轮也在后台跑完（Go 的 {@code -once} 是进程级一次性，
     *       单 JVM 里没有「进程退出」这个自然终点 ⇒ 以日志为准）。</li>
     * </ul>
     *
     * <p>⚠️ <b>{@link #run} 的阻塞语义刻意保持不变</b>：
     * 它对应 Go 的独立进程形态（进程主循环就是阻塞的），
     * 改它会波及所有仍在用 {@code run} 的调用方 ⇒ 语义差异由本方法承担。
     *
     * @throws IllegalStateException 循环线程启动失败（配置错误 / 启动超时）
     */
    public static DispatcherHandle runAsync(Context ctx, Dispatcher dispatcher, EventSender sender,
                                            Logger logger, boolean once, boolean registerShutdownHook) {
        CompletableFuture<DispatcherHandle> handleFuture = new CompletableFuture<>();
        Thread runner = new Thread(() -> {
            try {
                run(ctx, dispatcher, sender, logger, once, registerShutdownHook, handleFuture::complete, null);
            } catch (Throwable t) {
                // ⚠️ once 模式下 run 会把投递错误抛出来（Go 的 -once 非零退出）。
                //    单 JVM 里没有退出码 ⇒ 以日志为准（否则错误会被静默吞掉）。
                logger.atError()
                        .addKeyValue("event", "community.fact_delivery.failed")
                        .setCause(t)
                        .log("community fact dispatcher stopped with error");
                handleFuture.completeExceptionally(t);
            }
        }, "communityfact-dispatcher");
        runner.setDaemon(true);
        runner.start();
        try {
            return handleFuture.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            runner.interrupt();
            throw new IllegalStateException("community fact dispatcher failed to start", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runner.interrupt();
            throw new IllegalStateException("community fact dispatcher interrupted while starting", e);
        } catch (TimeoutException e) {
            runner.interrupt();
            throw new IllegalStateException("community fact dispatcher did not start within 10s", e);
        }
    }

    /**
     * 内部实现：{@code handlePublisher} 在<b>句柄建好之后、进入循环之前</b>被调用一次。
     *
     * <p>有了它，{@link #runAsync} 才能在循环开始跑之前就把句柄交出去 ——
     * 否则调用方拿到句柄时循环早就跑完了，「立刻能 stop」就做不到。
     *
     * <p>🔴 <b>DRIFT-CF-018（2026-10-29 修）</b>：{@code callerHandle} 非 {@code null} 时，
     * 本方法<b>复用调用方的句柄</b>（而不是自己新建一个）⇒ ⇒ 调用方从 {@code run} 被调用那一刻起
     * 就握着停止能力 ⇒ ⇒ 阻塞形态（{@code once=false}）下也能被 {@code @PreDestroy} 停掉。
     * <p>Go 出处：{@code service/common/communityfact/process.go:20}（不返回句柄）
     * ＋ {@code process.go:56-60}（退出条件只有 ctx）
     * ＋ {@code service/comment/rpc/cmd/fact-dispatch/main.go:68}（调用方先造 cancel）。
     */
    private static DispatcherHandle run(Context ctx, Dispatcher dispatcher, EventSender sender,
                                        Logger logger, boolean once, boolean registerShutdownHook,
                                        java.util.function.Consumer<DispatcherHandle> handlePublisher,
                                        DispatcherHandle callerHandle) {
        long started = System.nanoTime();
        if (dispatcher == null || sender == null || logger == null) {
            throw new IllegalArgumentException("community fact dispatcher process is not configured");
        }
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("sea.rtw.communityfact");

        // 单独线程池跑 dispatch；用 CompletableFuture.get(10s) 实现 10s deadline
        // 对应 Go 版 context.WithTimeout(attempt, 10*time.Second) + defer cancel()
        ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "communityfact-dispatch");
            t.setDaemon(true);
            return t;
        });

        // 对应 Go 版内层 dispatch 函数：真正包 10s deadline
        java.util.function.Supplier<DispatchAttempt> dispatch = () -> {
            // M14 修复：补 setParent(ctx)。
            // Go process.go:26 是 `otel.Tracer(...).Start(ctx, "community.fact_delivery.dispatch")`，
            // 第三参数即父 ctx —— 该 span 是调用方 span 的子节点。
            // 旧 Java 版 `tracer.spanBuilder(...).startSpan()` 不带 parent，
            // 于是每次投递都开出一条**根 span**，整条 dispatch 的 trace 拓扑与 Go 完全不同
            // （在 Jaeger 里表现为一批互不相连的孤立 span，且丢失与上游请求的因果链）。
            Span span = tracer.spanBuilder("community.fact_delivery.dispatch")
                    .setParent(ctx)
                    .startSpan();
            Context attemptCtx = ctx.with(span);
            CompletableFuture<Boolean> future;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return dispatcher.dispatchFactOnce(attemptCtx, sender, logger);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, dispatchExecutor);
            } catch (RejectedExecutionException rejected) {
                // 🔴 DRIFT-CF-018 修复带来的可达路径：调用方可能在 run 阻塞期间调 handle.stop()
                //    ⇒ stop() 会 shutdownNow() 执行器 ⇒ 此刻再提交任务会被拒绝。
                //    ⇒ 这里必须把它收敛成「一次失败的投递」，而不是让它裸奔出去打断整个 run()。
                //    Go 侧等价：ctx 被取消后 DispatchFactOnce 立刻失败，返回 (false, err)。
                span.end();
                return new DispatchAttempt(false, rejected);
            }
            try {
                boolean sent = future.get(10, TimeUnit.SECONDS);
                span.end();
                return new DispatchAttempt(sent, null);
            } catch (TimeoutException te) {
                // DRIFT-008 修复：cancel(true) 触发线程 interrupt，业务函数如果响应 InterruptedException 会立刻停
                future.cancel(true);
                span.end();
                return new DispatchAttempt(false, te);
            } catch (ExecutionException ee) {
                span.end();
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                return new DispatchAttempt(false, cause instanceof Exception ? (Exception) cause : new RuntimeException(cause));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                span.end();
                return new DispatchAttempt(false, ie);
            }
        };

        AtomicBoolean running = new AtomicBoolean(true);
        Thread runnerThread = Thread.currentThread();
        // 🔴 DRIFT-CF-018 修复：句柄归调用方（callerHandle != null）⇒ 调用方从 run 被调用起就握着停止能力。
        DispatcherHandle handle = callerHandle != null ? callerHandle : DispatcherHandle.create();
        // 绑定内部资源（执行器 + runner 线程）⇒ 句柄一交到调用方手上就是「完全可用」的。
        handle.bind(dispatchExecutor, runnerThread);
        // 🔴 DRIFT-CF-018：循环等的停止信号必须就是「句柄自己的」那个 ⇒
        //    否则 handle.stop() 唤醒不了循环（句柄与循环各持一份 signal ⇒ 停不下来）。
        CompletableFuture<Void> stopSignal = handle.stopSignal();
        // 句柄一建好就发布（进入循环之前）⇒ runAsync 的调用方能立刻拿到它去 stop()
        if (handlePublisher != null) {
            handlePublisher.accept(handle);
        }

        Thread shutdownHook = new Thread(() -> {
            running.set(false);
            stopSignal.complete(null);
        }, "communityfact-dispatcher-shutdown");
        // DRIFT-008 修复：shutdown hook 注册可选，避免测试 / 反复 RunDispatcher 场景下 hook 累积
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }

        try {
            if (once) {
                DispatchAttempt result = dispatch.get();
                if (result.error != null) {
                    throw new RuntimeException(result.error);
                }
                logger.atInfo()
                        .addKeyValue("event", "community.fact_delivery.once")
                        .addKeyValue("outcome", "succeeded")
                        // M15 修复：Go process.go:38 用 Microseconds()/1000（亚毫秒浮点），
                        // 旧 Java 版用 NANOSECONDS.toMillis()（整毫秒，截断亚毫秒部分）。
                        // 本文件另两处 duration_ms（AuthorityHandler / Dispatcher 内层）都已是浮点，
                        // 只有 once 这条是整数 —— 同一字段在同一个信封里出现两种精度。
                        .addKeyValue("duration_ms", elapsedMillisSince(started))
                        .addKeyValue("sent", result.sent)
                        .log("community fact delivery inspected");
                return handle;
            }

            // 对应 Go 版 ticker = time.NewTicker(time.Second) + 内层 16 次循环
            while (running.get() && !runnerThread.isInterrupted()) {
                for (int i = 0; i < 16; i++) {
                    if (!running.get() || runnerThread.isInterrupted()) {
                        break;
                    }
                    DispatchAttempt result = dispatch.get();
                    if (result.error != null) {
                        logger.atWarn()
                                .addKeyValue("event", "community.fact_delivery.retry")
                                .addKeyValue("outcome", "retryable")
                                .addKeyValue("error_code", deliveryErrorCode(result.error))
                                .addKeyValue("error_type", "delivery")
                                .log("community fact delivery retry scheduled");
                        break;
                    }
                    if (!result.sent) {
                        break;
                    }
                }
                // 对应 Go 版 select { case <-ctx.Done(): return nil; case <-ticker.C: }
                // 等待最多 1 秒，但 stopSignal.complete() 会立即唤醒（DRIFT-015 修复）
                try {
                    stopSignal.get(1, TimeUnit.SECONDS);
                    // stopSignal 被触发：退出循环
                    return handle;
                } catch (TimeoutException ie) {
                    // 正常 1 秒到，继续外层 while
                } catch (InterruptedException ie) {
                    runnerThread.interrupt();
                    return handle;
                } catch (ExecutionException ee) {
                    return handle;
                }
            }
            return handle;
        } finally {
            // DRIFT-008 修复：仅在注册了 hook 的情况下 remove
            if (registerShutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
            dispatchExecutor.shutdownNow();
        }
    }

    /**
     * DRIFT-015 修复：返回的 handle 让业务方可以主动 stop runner（替代依赖 JVM shutdown）。
     *
     * <p><b>Spring 化路径</b>：业务模块把 {@code DispatcherHandle} 注入到 Spring 容器（如 {@code @Component}
     * 持有字段），在 {@code @PreDestroy} / {@code DisposableBean.destroy()} 回调里调
     * {@link EventDispatcher.DispatcherHandle#stop()}，避免 JVM shutdown hook 在 SpringApplication 关闭链路里触发时序冲突。
     *
     * <p>🔴 <b>DRIFT-CF-018（2026-10-29 修）：句柄现在<b>由调用方持有</b>。</b>
     * <p><b>Go 出处</b>：{@code service/comment/rpc/cmd/fact-dispatch/main.go:68}
     * （{@code ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)}）
     * ＋ {@code :69}（{@code defer stop()}）
     * ＋ {@code service/common/communityfact/process.go:20}（{@code RunDispatcher} 不返回句柄）
     * ＋ {@code process.go:56-60}（{@code case <-ctx.Done(): return nil} ⇒ 退出条件只有 ctx）。
     * <p><b>语义等价论证</b>：Go 的停止能力（cancel）<b>由调用方在调用 dispatcher 之前创建</b>
     * ⇒ 调用方从那一刻起就能停 ⇒ Java 侧对应物 = {@link EventDispatcher#newHandle()} 造句柄后交给
     * {@link EventDispatcher#run(Context, Dispatcher, EventSender, Logger, boolean, DispatcherHandle, boolean)}。
     * <p>⚠️ <b>旧形态的问题</b>：句柄由 {@code run} 内部新建、且只在<b>循环退出后</b>交出
     * ⇒ 阻塞形态（{@code once=false}）的调用方<b>永远拿不到它</b> ⇒ {@code @PreDestroy} 停不下来。
     */
    public static final class DispatcherHandle {
        /** 停止信号：<b>句柄自己拥有</b> ⇒ 循环等的就是它（否则 handle.stop() 唤醒不了循环）。 */
        private final CompletableFuture<Void> stopSignal = new CompletableFuture<>();
        /** 内部资源（执行器 + runner 线程）：由 {@code run} 在启动时 {@link #bind} 进来。 */
        private volatile ExecutorService dispatchExecutor;
        private volatile Thread runnerThread;

        DispatcherHandle() {
        }

        /**
         * <b>调用方持有的句柄</b>（DRIFT-CF-018 修复后的入口）。
         *
         * <p>Go 出处：{@code service/comment/rpc/cmd/fact-dispatch/main.go:68}
         * （{@code ctx, stop := signal.NotifyContext(...)} ⇒ 调用方先造停止能力，再调 dispatcher）。
         */
        public static DispatcherHandle create() {
            return new DispatcherHandle();
        }

        /**
         * 绑定内部资源（由 {@code run} 调用）。<b>包级私有</b> —— 外部只能通过 {@code run} 拿到已绑定的句柄。
         *
         * <p>⚠️ 若调用方在 {@code run} 启动<b>之前</b>就 {@link EventDispatcher.DispatcherHandle#stop()} 了 ⇒ 停止信号已置位
         * ⇒ 此时<b>不</b>去 interrupt / shutdownNow（那时执行器还不存在，且 Go 侧等价语义是
         * 「仍然跑完第一批」—— {@code process.go:43-55} 的批次循环在 {@code select} 之前）
         * ⇒ 循环会在第一个 1s 栅格处看到信号已置位并退出。
         */
        void bind(ExecutorService dispatchExecutor, Thread runnerThread) {
            this.dispatchExecutor = dispatchExecutor;
            this.runnerThread = runnerThread;
        }

        /** 循环等待的停止信号（包级私有）。 */
        CompletableFuture<Void> stopSignal() {
            return stopSignal;
        }

        /**
         * 立即停止 runner 循环（不等 JVM shutdown）：
         * <ol>
         *   <li>触发 {@link #stopSignal} 让 runner 的 1s 轮询立即唤醒退出</li>
         *   <li>{@code interrupt()} 主 runner 线程（若处于阻塞 sleep / IO）</li>
         *   <li>{@code shutdownNow()} dispatch 线程池——若业务线程正在跑，会被 interrupt</li>
         * </ol>
         *
         * <p>对应 Go 版 {@code ctx, cancel := context.WithCancel(...) + cancel()}。
         *
         * <p><b>幂等</b>：重复调用安全（{@code stopSignal.complete} 第二次 no-op；{@code interrupt} 幂等；
         * {@code shutdownNow} 重复执行 OK）。
         *
         * <p><b>Spring 化</b>：推荐在 {@code @PreDestroy} 里调用，让 Spring 容器关闭与 dispatcher
         * 关闭时序一致，避免 JVM hook 触发时 Spring 容器已关闭导致的资源清理不一致。
         *
         * <p>🔴 <b>DRIFT-CF-018</b>：本方法在 {@code run} <b>还在阻塞</b>时调用也有效
         * —— 因为句柄由调用方持有（{@link EventDispatcher#newHandle()}），不是循环退出后才交出来的。
         */
        public void stop() {
            stopSignal.complete(null);
            Thread runner = runnerThread;
            if (runner != null) {
                runner.interrupt();
            }
            ExecutorService exec = dispatchExecutor;
            if (exec != null) {
                exec.shutdownNow();
            }
        }
    }

    /**
     * M15：亚毫秒精度的 elapsed 毫秒数，对齐 Go 的 {@code float64(time.Since(started).Microseconds())/1000}。
     */
    private static double elapsedMillisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    /**
     * 对应 Go 版 {@code func deliveryErrorCode(err error) string}：
     * 区分 frozen envelope 阻塞 vs 普通投递失败。
     *
     * <p><b>M16 修复</b>：旧实现沿 cause 链做「实例相同 <b>或</b> message 字符串相等」判定。
     * 字符串相等是多余的且有害 —— 任何业务异常只要文案撞上
     * "community fact frozen envelope blocked"，就会被误判为 envelope 阻塞，
     * 错误码从 {@code DELIVERY_ATTEMPT_FAILED} 变成 {@code INVALID_FROZEN_ENVELOPE}，
     * 上游据此做的重试/隔离决策随之出错。
     *
     * <p>现只认 {@link Wire.FrozenEnvelopeBlockedException} 类型（沿 cause 链），
     * 与 Go 的 {@code errors.Is(err, ErrFrozenEnvelopeBlocked)} 同构。
     */
    public static String deliveryErrorCode(Throwable err) {
        if (err == null) {
            return "DELIVERY_ATTEMPT_FAILED";
        }
        Throwable t = err;
        while (t != null) {
            if (t instanceof Wire.FrozenEnvelopeBlockedException) {
                return "INVALID_FROZEN_ENVELOPE";
            }
            Throwable next = t.getCause();
            if (next == t) break;
            t = next;
        }
        return "DELIVERY_ATTEMPT_FAILED";
    }

    private static final class DispatchAttempt {
        final boolean sent;
        final Throwable error;

        DispatchAttempt(boolean sent, Throwable error) {
            this.sent = sent;
            this.error = error;
        }
    }

    private EventDispatcher() {
    }
}
