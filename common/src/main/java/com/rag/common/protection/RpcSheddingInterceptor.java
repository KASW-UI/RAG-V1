package com.rag.common.protection;

import com.rag.common.observability.RpcStatMetrics;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * <b>gRPC 服务端 CPU 自适应降载</b> —— 对齐 B 侧 的 {@code UnarySheddingInterceptor}。
 *
 * <h3>B 出处（消费点，不是声明点）</h3>
 * <pre>
 *   zrpc/server.go:160-162
 *       if c.CpuThreshold > 0 {
 *           shedder := load.NewAdaptiveShedder(load.WithCpuThreshold(c.CpuThreshold))
 *           svr.AddUnaryInterceptors(serverinterceptors.UnarySheddingInterceptor(shedder, metrics))
 *       }
 *       ⇒ **每个 server 一个全局 shedder**，不是 per-route
 *
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:22-46
 *       sheddingStat.IncrementTotal()
 *       promise, err = shedder.Allow()
 *       if err != nil {
 *           metrics.AddDrop()
 *           sheddingStat.IncrementDrop()
 *           err = status.Error(codes.ResourceExhausted, err.Error())   // ← ① wire 契约
 *           return
 *       }
 *       defer func() {
 *           if errors.Is(err, context.DeadlineExceeded) {
 *               promise.Fail()                                          // ← ② fail 判据
 *           } else {
 *               sheddingStat.IncrementPass()
 *               promise.Pass()                                          // ← ③ pass（内部测 RT）
 *           }
 *       }()
 *       return handler(ctx, req)
 *
 *   core/load/adaptiveshedder.go:34
 *       var ErrServiceOverloaded = errors.New("service overloaded")     // ← ① 的文案
 * </pre>
 *
 * <h3>🔴 ① 拒绝<b>必须</b>走 {@code call.close(...)}，不能吞掉</h3>
 * <p>B 侧 shedding 位于熔断器<b>内层</b>（链序 {@code Breaker → Shedding}），
 * 它的 {@code RESOURCE_EXHAUSTED} 是<b>作为返回值冒泡到熔断器</b>的
 * ⇒ 熔断器会把它记成失败（{@code RESOURCE_EXHAUSTED} 在 B 的 6 码失败名单里）
 * ⇒ <b>持续降载最终会把熔断器打开</b>。
 * <p>若本类把拒绝吞掉（不经过 {@code call.close}），{@link GoStyleRpcBreakerInterceptor}
 * 就<b>观察不到</b> ⇒ 熔断永远不会因降载而打开 ⇒ <b>与 B 侧 行为不同</b>。
 * ⇒ 本类的拒绝一律走 {@code call.close(...)}，让错误自然冒泡到外层。
 *
 * <h3>🔴 ② {@code fail()} 的判据是「<b>ctx 超时</b>」，不是「任何 DEADLINE_EXCEEDED」</h3>
 * <p>B 侧 判的是 {@code errors.Is(err, context.DeadlineExceeded)} ——
 * 即<b>客户端 deadline 过期</b>；若是服务端<b>自己</b>返回 {@code status.Error(DEADLINE_EXCEEDED, …)}，
 * B 侧 判的是 {@code promise.Pass()}（<b>不是</b> Fail）。
 * <p>⇒ Java 侧用 {@link ServerCall#getDeadline()} 的 {@link Deadline#isExpired()} 来区分这两者：
 * <ul>
 *   <li>客户端 deadline 过期 ⇒ deadline 已到 ⇒ {@code fail()} ✔ 与 B 侧 一致</li>
 *   <li>服务端自己返回 DEADLINE_EXCEEDED 但客户端 deadline 未到 ⇒ {@code pass()} ✔ 与 B 侧 一致</li>
 * </ul>
 * ⚠️ <b>不能用 {@code Context.current().isCancelled()} 代替</b>：它对「客户端主动取消」也为 true，
 * 而 B 的名单里<b>没有</b> {@code context.Canceled} ⇒ 客户端取消在 B 侧是 {@code Pass()}。
 *
 * <h3>⚠️ 与 REST 侧（{@link GoStyleCpuSheddingFilter}）的两处刻意不同</h3>
 * <ol>
 *   <li><b>{@code promise.Fail()} 的判据</b>：REST 侧看「响应码 == 503」
 *       （{@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcSheddingInterceptor.java:73:45}），zrpc 侧看「ctx 超时」。<b>B 侧两者就是不同</b>。</li>
 *   <li><b>拒绝的 wire 形态</b>：REST 侧是 HTTP 503 无 body；zrpc 侧是
 *       {@code RESOURCE_EXHAUSTED} + {@code "service overloaded"}。</li>
 * </ol>
 */
public final class RpcSheddingInterceptor implements ServerInterceptor, RpcLayer {

    /** B 侧 {@code core/load/adaptiveshedder.go:34} {@code ErrServiceOverloaded} 的文案（wire 可见）。 */
    public static final String SERVICE_OVERLOADED_DESCRIPTION = "service overloaded";

    /**
     * <b>全局一个</b> shedder（B 侧: {@code zrpc/server.go:161} 每 server 一个，
     * 与 REST 侧 {@code rest/engine.go:52-56} 同构）—— <b>不是</b> per-route。
     */
    private final AdaptiveShedder shedder;

    private final boolean enabled;

    /**
     * 降载统计（B 侧 {@code core/load/sheddingstat.go}）—— 进程级单例。
     *
     * <p>B 侧是包级变量 {@code var sheddingStat *load.SheddingStat} + {@code ensureSheddingStat()}，
     * <b>进程内一个</b>（不是 per-route、也不是 per-interceptor）⇒ 本类用
     * {@link SheddingStat#getInstance()} 取同一个。
     */
    private final SheddingStat stat;

    /**
     * <b>纳秒时钟源</b>（默认 {@link System#nanoTime()}）——<b>测试接缝</b>。
     *
     * <p>🔴 <b>为什么需要它</b>（2026-10-28 · G4）：
     * 本类原先把 RT 测量写成内联的一行 {@code long rtMs = (System.nanoTime() - startNanos) / 1_000_000L;}
     * ⇒ ⇒ **整型截断** ⇒ ⇒ 被调方的 {@code Math.ceil} 成了空操作 ⇒ ⇒ **系统性比 B 侧 少记约 1ms**
     * ⇒ ⇒ 而<b>调用点</b>这一截断<b>从来没有被任何断言覆盖</b>（{@code AdaptiveShedderTest}
     * 只在 {@code pass(double)} 那一层断言过 ceil ⇒ ⇒ 「有守门」≠「这一处被守住」）。
     *
     * <p>⇒ ⇒ 把时钟做成可注入 ⇒ ⇒ 测试能把 RT <b>钉死成 1.5ms</b> ⇒ ⇒
     * <b>调用点</b>的语义就能被确定性断言（B 侧: {@code math.Ceil(1.5) = 2}）：
     * <ul>
     *   <li>不截断 ⇒ 被调方记 <b>2</b> ✔</li>
     *   <li>整型截断 ⇒ 被调方记 <b>1</b> ✘ ⇒ ⇒ 守卫必红</li>
     * </ul>
     * ⇒ ⇒ **只测 {@code measuredRtMs} 这个 helper 是不够的**（helper 绿、调用点照样能截断）
     * ⇒ ⇒ ⇒ **两处都要有网**（与 tools/check-ledger-selfcheck.py 那条「两处契约不一致」同族）。
     */
    private final LongSupplier nanoTime;

    public RpcSheddingInterceptor(AdaptiveShedder shedder, boolean enabled) {
        this(shedder, enabled, SheddingStat.getInstance(), System::nanoTime);
        // B 侧: sheddinginterceptor.go:23-25 —— ensureSheddingStat() 就在拦截器**构造时**被调用
        //   （NewSheddingStat 内部 `go st.run()` 起那个每分钟上报的 goroutine）
        // ⇒ 只有「降载真的被装配」的进程才会有这条上报线程
        //   （B 侧同理：UnarySheddingInterceptor 只在 CpuThreshold > 0 时才被构造）。
        this.stat.startReporter();
    }

    /** 测试注入用（让断言能拿到具体的 stat 实例）。 */
    RpcSheddingInterceptor(AdaptiveShedder shedder, boolean enabled, SheddingStat stat) {
        this(shedder, enabled, stat, System::nanoTime);
    }

    /** 测试注入用（把纳秒时钟换成可控源 ⇒ 让 <b>调用点</b>的 RT 语义可被确定性断言）。 */
    RpcSheddingInterceptor(AdaptiveShedder shedder, boolean enabled,
            SheddingStat stat, LongSupplier nanoTime) {
        this.shedder = shedder;
        this.enabled = enabled;
        this.stat = stat;
        this.nanoTime = nanoTime;
    }

    /** 排障用：本次降载统计。 */
    SheddingStat stat() {
        return stat;
    }

    /**
     * <b>RT 测量</b>（B 侧: {@code adaptiveshedder.go:285}）。
     *
     * <pre>
     * rt := float64(timex.Since(p.start)) / float64(time.Millisecond)
     * p.shedder.rtCounter.Add(int64(math.Ceil(rt)))
     * </pre>
     *
     * <p>⇒ <b>浮点毫秒</b>（保留小数）⇒ 再由被调方的 {@code Math.ceil} <b>向上取整</b>、<b>无下限</b>。
     *
     * <p>🔴 **单抽成方法不是为了好看，是为了让它【有网】**：本方法原先是内联的一行
     * {@code long rtMs = (System.nanoTime() - startNanos) / 1_000_000L;}
     * ⇒ ⇒ **整型截断** ⇒ ⇒ 被调方的 {@code Math.ceil} 成了空操作 ⇒ ⇒ **系统比 B 侧 少记约 1ms**
     * ⇒ ⇒ 而 {@code AdaptiveShedderTest} 只在 {@code pass(double)} 这一层断言过 ceil
     * ⇒ ⇒ **调用点这一截断从来没有被任何断言覆盖** ⇒ ⇒ 「半修」长期不可见。
     *
     * <p>⇒ ⇒ 抽出来之后，守门可以**确定性地**断言「小数部分没被截断」（见
     * {@code RpcSheddingInterceptorTest#rtMeasurementKeepsFractionalMilliseconds}）。
     */
    double measuredRtMs(long startNanos) {
        return (nanoTime.getAsLong() - startNanos) / 1_000_000D;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        if (!enabled) {
            return next.startCall(call, headers);
        }

        final long startNanos = nanoTime.getAsLong();

        // B 侧: sheddinginterceptor.go:31 —— `sheddingStat.IncrementTotal()` 在 `Allow()` **之前**
        // ⚠️ **2026-10-28 · G4 订正**：本行原注释写「每个进入本拦截器的请求都先 +1（在 Allow 之前）」，
        //   **那是错的** —— 在途数 **+1 发生在 `Allow()` 内部、且在判降载之后**
        //   （B 侧: adaptiveshedder.go:127-133 ⇒ `if as.shouldDrop() { return nil, ErrServiceOverloaded }`
        //     ⇒ 之后才 `as.addFlying(1)`）⇒ ⇒ **被降载拒绝的请求【不计入在途】**。
        //   ⇒ ⇒ 本仓 `AdaptiveShedder#allow()` 与 B 侧 **逐字一致**（先判降载、后 +1）⇒ **不是漂移**。
        //   ⇒ ⇒ 但这条注释会误导读代码的人去「先 +1」⇒ ⇒ 订正之（§3.2：留痕必须准确）。
        stat.incrementTotal();

        // B 侧: sheddinginterceptor.go:36-45 的 defer —— 调用结束后结算 promise
        final AtomicBoolean settled = new AtomicBoolean(false);
        final AtomicBoolean rejected = new AtomicBoolean(false);

        ServerCall<ReqT, RespT> watched = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                if (settled.compareAndSet(false, true)) {
                    // ⚠️ 拒绝路径**不结算 promise**：B 的 defer 是在 Allow() 成功**之后**才注册的
                    //    （sheddinginterceptor.go:24-31 先 Allow，:36 才 defer）⇒ 拒绝时既不 Pass 也不 Fail。
                    if (!rejected.get()) {
                        if (isCtxDeadlineExpired(status.getCode())) {
                            shedder.fail();                 // B 侧: promise.Fail()
                        } else {
                            // B 侧: adaptiveshedder.go:284-287（promise.Pass）
                            //   rt := float64(timex.Since(p.start)) / float64(time.Millisecond)
                            //   p.shedder.rtCounter.Add(int64(math.Ceil(rt)))
                            // ⇒ **浮点毫秒 + 向上取整，无下限**（B 的 Add(0) 合法：Count +1、Sum +0）。
                            //
                            // 🔴 **2026-10-28 · G4 修（原值是「整型毫秒截断」）**：
                            //   本行原先是 `long rtMs = (System.nanoTime() - startNanos) / 1_000_000L;`
                            //   ⇒ ⇒ **先截断成整数**，再交给 `shedder.pass(rtMs)` ⇒ ⇒ 里面的
                            //   `Math.ceil(rtMs)` 因此成了**空操作** ⇒ ⇒ **B 的「浮点 + ceil」语义被这一行抵消掉**
                            //   ⇒ ⇒ **系统性比 B 侧 少记约 1ms**（RT=0.2ms：B 侧 记 1、RAG 记 0；RT=1.5ms：B 侧 记 2、RAG 记 1）
                            //   ⇒ ⇒ `minRt()` 偏小 ⇒ `maxFlight = maxPass * minRt * 0.01` 偏小
                            //   ⇒ ⇒ **在途上限偏低 ⇒ RAG 比 B 侧 更早降载**（AGENTS.md §3.1：行为差异默认判 RAG 错）。
                            //   ⇒ ⇒ 与 `AdaptiveShedder#pass(double)` 的 javadoc 是**两处配套**：
                            //         「整型截断」在**调用点**被修掉，才轮到**被调方**的 `ceil` 真正生效 ⇒
                            //         只修被调方 = **半修**（与 tools/check-ledger-selfcheck.py 那条「两处契约不一致」同族）。
                            double rtMs = measuredRtMs(startNanos);
                            shedder.pass(rtMs);             // B 侧: promise.Pass()（内部测 RT · 浮点毫秒 + ceil）
                            // B 侧: sheddinginterceptor.go:37 —— IncrementPass() 与 promise.Pass() **同一支**
                            //    （ctx 超时那一支只 Fail，**不**计 pass）
                            stat.incrementPass();
                        }
                    }
                }
                super.close(status, trailers);
            }
        };

        // B 侧: sheddinginterceptor.go:24-31 —— Allow() 失败即拒
        if (!shedder.allow()) {
            rejected.set(true);
            // B 侧: sheddinginterceptor.go:28-29 —— metrics.AddDrop() + sheddingStat.IncrementDrop()
            stat.incrementDrop();
            // 🔴 B 侧同一处还有 `metrics.AddDrop()`（stat.Metrics 的 **drop 计数**）
            //    ⇒ ⇒ 本仓必须一并喂给 Stat 聚合器，否则每分钟那行报告里的
            //    **drops 恒为 0**（B 侧: core/stat/metrics.go:178 report.Drops = drops）。
            //    ⚠️ drops 与 qps/avg **互斥**：drop 样本不进 tasks、不进 duration（B 侧: metrics.go:107-118）。
            RpcStatMetrics.getInstance().addDrop();
            // 🔴🔴 必须关 **watched**（本层包装过的那个），**不能关原始 call** ——
            //    关原始 call ⇒ 外层 breaker 的 close 观察点**不会被触发**
            //    ⇒ breaker 看不到这次降载 ⇒ 永远不会因降载而打开 ⇒ **与 B 侧 行为不同**。
            //    （本轮第一版写的是 call.close(...)，被 GoStyleRpcProtectionChainTest 当场抓到。）
            watched.close(Status.RESOURCE_EXHAUSTED.withDescription(SERVICE_OVERLOADED_DESCRIPTION),
                    new Metadata());
            // handler 不执行（B 侧: Allow() 失败时 handler(ctx, req) 从未被调用）
            return new ServerCall.Listener<ReqT>() {
            };
        }

        return next.startCall(watched, headers);
    }

    /**
     * 本次调用是否属于「ctx 超时」—— B 侧判 {@code errors.Is(err, context.DeadlineExceeded)}。
     *
     * <p>⚠️ <b>刻意只看 close 的 status 码</b>：B 侧若服务端<b>自己</b>返回
     * {@code status.Error(DEADLINE_EXCEEDED, …)}，判的是 {@code promise.Pass()}（不是 Fail）。
     * <p>⇒ 本仓能等价的前提是：<b>message-rpc 的业务代码一次都没有显式使用
     * {@code DEADLINE_EXCEEDED}</b>（实测；唯一来源是 {@code SqlTimeoutStatusTranslator}
     * 把 PG 的 57014 翻成它，那是<b>真超时</b>，两侧都判 Fail，一致）。
     * <p>⚠️ 若将来某个 handler 开始自己造 {@code DEADLINE_EXCEEDED} status，
     * 这里会与 B 侧 分岔（B 侧 判 Pass、RAG 判 Fail）⇒ 那时要改判据。
     */
    private static boolean isCtxDeadlineExpired(Status.Code closeCode) {
        return closeCode == Status.Code.DEADLINE_EXCEEDED;
    }

    /** 排障用：当前在途请求数。 */
    long flying() {
        return shedder.flying();
    }

    /** 链上的层名（供守门断言链序）。 */
    @Override
    public String layerName() {
        return "shedding";
    }
}
