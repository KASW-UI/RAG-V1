package com.rag.common.observability;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>观测层的进程级线程池持有者</b>（本仓对 RAG {@code RpcInterceptor} 里那两个
 * {@code static} 池的<b>表达层收口</b>）。
 *
 * <p><b>B 出处</b>：B 侧<b>没有</b>对应物 —— B 的观测层不跑线程池
 * （goroutine 由 B 侧的 zrpc 拦截器直接起）⇒ ⇒ 本类是 <b>纯表达层补齐</b>
 * ⇒ ⇒ <b>无 B 侧 对应物</b>，登记在 {@code docs/L3-降级清单.md} 的 D-03 同族。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java}
 * 的 {@code TIMEOUT_SCHEDULER} / {@code HANDLE_EXECUTOR} 两段（含「池被终止后按需重建」
 * 的 CAS 自旋语义）整段抄（L2 · D13）。
 *
 * <h2>为什么把池从 {@code ObservabilityAutoConfiguration} 里搬出来</h2>
 * <p>RAG 侧两个池是 {@code RpcInterceptor} 的 {@code private static} 字段，
 * 由 {@code ObservabilityLifecycle}（在 {@code com.rag.common.spring} 包）通过
 * {@code RpcInterceptor.shutdown()} 关闭。
 * <p>本仓的对应物<b>不在同一个包</b>（本仓的 auto-config 在 {@code com.rag.common.autoconfigure}，
 * 生命周期 Bean 在 {@code com.rag.common.observability}）⇒ ⇒ {@code private static}
 * 字段<b>跨包不可见</b> ⇒ ⇒ 必须有一个<b>公开的持有者</b>。
 * <p>⇒ 本类就是这个持有者：<b>池的创建 / 取用 / 关闭三个动作全部收口在这里</b>，
 * 任何调用方都<b>不再各自持有池</b>（否则就会出现「关了一份、另一份还在跑」的经典漏关）。
 *
 * <h2>🔴 池被终止后<b>按需重建</b>（这一条是 RAG 侧踩出来的坑，必须保留）</h2>
 * <p>RAG 侧的 javadoc 记着：两个池都是<b>进程级静态</b>，而 {@code shutdown()} 会
 * <b>永久终止</b>它们 ⇒ ⇒ 同一个 JVM 里只要存在<b>第二个 Spring 上下文</b>
 * （surefire 一个模块一个 JVM ⇒ 模块内多个上下文），
 * 前一个上下文关闭后本池就变成「已终止」⇒ 后续请求的 {@code schedule(...)} 被拒
 * （{@code RejectedExecutionException}）⇒ 拦截器返回 {@code INTERNAL} 而不是
 * {@code DEADLINE_EXCEEDED}（实测症状：{@code ScheduledThreadPoolExecutor[Terminated]}）。
 * ⇒ ⇒ 改为：<b>池被终止后，下一次使用按需重建</b>。
 * <ul>
 *   <li><b>生产</b>：单上下文 ⇒ shutdown 之后没有下一次使用 ⇒ <b>池保持关闭</b> ⇒ 关闭钩子的意图不变；</li>
 *   <li>线程是 <b>daemon</b> ⇒ 即便重建也不阻止 JVM 退出。</li>
 * </ul>
 *
 * <p><b>本类零 Spring 依赖</b> ⇒ 纯 gRPC 进程与纯 servlet 进程都能加载它。
 */
public final class ObservabilityRuntime {

    private static final AtomicReference<ScheduledExecutorService> TIMEOUT_SCHEDULER =
            new AtomicReference<>(newTimeoutScheduler());

    private static final AtomicReference<ExecutorService> HANDLE_EXECUTOR =
            new AtomicReference<>(newHandleExecutor());

    private ObservabilityRuntime() {
    }

    /**
     * gRPC 超时用的调度器（线程名 {@code obs-rpc-timeout}）。
     *
     * <p>池大小 <b>2</b>（照抄 RAG）⇒ 与「同时到点的超时任务」并发度相称。
     */
    private static ScheduledExecutorService newTimeoutScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "obs-rpc-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * {@link RpcHandleRunner#handle} 专用的 worker 池（线程名 {@code obs-rpc-worker}）。
     *
     * <p><b>cached pool</b>（照抄 RAG）⇒ 业务并发多少就起多少线程。
     */
    private static ExecutorService newHandleExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "obs-rpc-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 取当前的超时调度器；<b>若上一轮 {@link #shutdown()} 已把它终止 ⇒ 按需重建一份新的</b>。
     *
     * <p>CAS + 自旋：并发重建时只会有一个胜出，其余线程直接用胜出的那份 ⇒ 不会漏建、也不会建多份。
     */
    public static ScheduledExecutorService timeoutScheduler() {
        while (true) {
            ScheduledExecutorService current = TIMEOUT_SCHEDULER.get();
            if (!current.isShutdown()) {
                return current;
            }
            if (TIMEOUT_SCHEDULER.compareAndSet(current, newTimeoutScheduler())) {
                return TIMEOUT_SCHEDULER.get();
            }
        }
    }

    /**
     * 取当前的 worker 池；<b>若上一轮 {@link #shutdown()} 已把它终止 ⇒ 按需重建一份新的</b>。
     */
    public static ExecutorService handleExecutor() {
        while (true) {
            ExecutorService current = HANDLE_EXECUTOR.get();
            if (!current.isShutdown()) {
                return current;
            }
            if (HANDLE_EXECUTOR.compareAndSet(current, newHandleExecutor())) {
                return HANDLE_EXECUTOR.get();
            }
        }
    }

    /**
     * 关闭两个池（Spring 容器关闭时由 {@link ObservabilityLifecycle} 调用）。
     *
     * <p><b>幂等</b>：重复调用安全（{@code shutdownNow} 重复 OK）。
     *
     * <p>⚠️ <b>不吞异常</b>：RAG 侧同样不吞 ⇒ 关闭失败要能暴露出来
     * （RAG 的 {@code ObservabilityLifecycle} 曾因第一句抛 {@code NoClassDefFoundError}
     * 而导致第二句根本执行不到 ⇒ 那个坑在本仓<b>结构上不存在</b>：
     * 本类<b>零 servlet 依赖</b>，两个池的关闭互不牵连）。
     */
    public static void shutdown() {
        TIMEOUT_SCHEDULER.get().shutdownNow();
        HANDLE_EXECUTOR.get().shutdownNow();
    }

    /** 测试用：当前两个池是否都处于终止态。 */
    static boolean poolsTerminated() {
        return TIMEOUT_SCHEDULER.get().isShutdown() && HANDLE_EXECUTOR.get().isShutdown();
    }
}
