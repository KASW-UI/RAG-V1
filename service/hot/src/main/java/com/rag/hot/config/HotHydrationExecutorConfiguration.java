package com.rag.hot.config;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * <p><b>补全线程池</b> —— 为 hot 域的<b>并发补全</b>提供「有界的平台线程」，
 * 以承接 B 侧 100 个 goroutine + 容量 8 信号量的并发结构。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/config/HotHydrationExecutorConfiguration.java}
 * （1-93 行；类声明 {@code :53-54}；三个常量 {@code :65} / {@code :72} / {@code :77}；
 * {@code hotHydrationExecutor} {@code :79-92}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.hot.hot.api.config} →
 *       {@code com.rag.hot.config}，D7 包压平）＋ <b>类名沿用</b>。
 *       <b>方法体逐字照抄</b>：三个常量（64 / 64 / {@code hot-hydrate-}）、
 *       {@link ThreadPoolTaskExecutor} 的五项设置、{@link ThreadPoolExecutor.CallerRunsPolicy}。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>池容量 64 + 队列 64 + 拒绝策略 {@code CallerRunsPolicy} <b>逐条未动</b>；</li>
 *         <li>线程名前缀只影响线程 dump 的可读性，<b>不改变任何业务判定</b>；</li>
 *         <li>{@code setWaitForTasksToCompleteOnShutdown(true)} 与 {@code initialize()} 逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>为什么需要一个池（B 侧是裸 goroutine）</h2>
 * B 侧的 {@code go func(){...}()} 零成本，Java 没有等价物：要么每个 item 一个平台线程
 * （100 个线程/请求，不可接受），要么落到一个有界池上并用信号量卡住「在途数」。
 * <p>Spring 的 {@link ThreadPoolTaskExecutor} 正是这个角色：
 * <b>自带生命周期</b>（{@code @Bean} 的 {@code destroyMethod} 会在容器关闭时
 * {@code shutdown()}，不用手写 {@code Runtime.addShutdownHook}）。
 *
 * <h2>池子为什么是 64 而不是 8</h2>
 * <b>并发窗口 8 是「每个请求」的</b>，由 {@code HotApiService} 里的
 * {@code Semaphore(8)} 施加；池的容量必须容纳<b>多个并发请求</b>各自的 8 个在途任务，
 * 否则一个请求会把池占满、其它请求排队（而 B 侧它们是各自独立的 8）。
 * <p>⚠️ <b>64 这个数是 B 侧实测定的，不是拍的</b>（B 侧 {@code DRIFT-HOTAPI-028}）：
 * 旧值 <b>32</b> = 「4 个并发请求 × 8」⇒ 在 <b>8 并发</b>下，合计在途被池压到 32
 * ⇒ 实测 <b>p99 1.76×</b>、p50 1.95× ⇒ 改成 <b>64 = 8 并发 × 8 窗口</b>
 * ⇒ 8 并发档位下<b>每请求都拿回完整的 8 在途</b> ⇒ 与 B 侧一致。
 *
 * <p><b>⚠️ 与 B 侧的残余差异（已签字）</b>：
 * B 侧<b>没有跨请求的总上限</b> —— 容量 8 的信号量是<b>每个请求各建一个</b>，
 * N 个并发请求 ⇒ N×8 个同时在途的下游调用，<b>无全局封顶</b>。
 * 本类是一个<b>全局</b>池 ⇒ N 超过 8 时，合计在途仍被 64（+ 队列 64）封顶。
 * ⇒ 这是「<b>C 侧比 B 侧更保守 / 更保护下游</b>」的一侧 ⇒ 登记为 hot 域签字偏离
 * （<b>不是缺陷，是决策</b>）⇒ 影响：并发 ≤ 8 时**完全等价**；并发 &gt; 8 时 C 侧排队、B 侧不排。
 *
 * <h2>为什么队列要<b>有界</b></h2>
 * {@code queueCapacity} 无界时，一个慢下游 + 大窗口会把请求堆成任务队列，
 * 队列里的任务还在<b>持有</b>已经过期的超时预算上下文，且没有任何背压。
 * 有界 + {@code CallerRunsPolicy}：池与队列都满时，<b>提交线程自己跑这个任务</b>
 * （即请求线程），把背压直接传回发起方 —— 效果是「请求变慢」，而不是「静默堆积」。
 */
@Configuration(proxyBeanMethods = false)
public class HotHydrationExecutorConfiguration {

    /**
     * <b>8 个并发请求 × 每个请求 8 个在途补全</b>。
     *
     * <p>⚠️ 旧值 <b>32</b>（= 4 请求 × 8）会把 <b>8 并发</b>时的每请求窗口压到 4
     * ⇒ 实测 p99 1.76×（B 侧 {@code DRIFT-HOTAPI-028}）。
     *
     * <p>⚠️ <b>不要再往下调</b>：池容量一旦小于「并发请求数 × 8」，
     * 就会把<b>每个请求自己的</b>窗口缩小 —— 那不是「限流」，是<b>偷偷改了单请求语义</b>。
     */
    private static final int POOL_SIZE = 64;

    /**
     * 允许的排队上限。{@code Semaphore(8)} 保证单请求在途 ≤ 8，
     * 所以队列里堆积的只会是<b>其它并发请求</b>的在途任务；
     * 取 64 ⇒ 池 + 队列共 128 个任务位 = 16 个并发请求 ⇒ <b>并发 ≤ 16 时不触发 CallerRuns</b>。
     */
    private static final int QUEUE_CAPACITY = 64;

    /**
     * 线程名前缀：B 侧这些 goroutine 无名，C 侧给个前缀才能在线程 dump 里认出来。
     */
    private static final String THREAD_NAME_PREFIX = "hot-hydrate-";

    /**
     * <p><b>补全线程池</b>（bean 名 {@code hotHydrationExecutor}）。
     *
     * <p>⚠️ <b>bean 名不可改</b>：{@code HotApiService} 用
     * {@code @Qualifier("hotHydrationExecutor")} 精确取它 ⇒ 改名会让注入失败。
     *
     * @return 有界线程池
     */
    @Bean("hotHydrationExecutor")
    public Executor hotHydrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        // 池与队列都满 ⇒ 由提交线程（= 发起请求的 worker）自己执行该任务，把背压传回调用方。
        // 不用 AbortPolicy（会抛 RejectedExecutionException，把 best-effort 的补全变成 500）。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * <p><b>补回 Boot 的 {@code applicationTaskExecutor}</b>（D1 合并的必然修补 · <b>真启动实测发现</b>）。
     *
     * <h2>⚠️ 为什么必须存在这个 bean</h2>
     * <p>Boot 3.5 的 {@code TaskExecutionAutoConfiguration} 里，{@code applicationTaskExecutor}
     * 的创建条件是 <b>{@code @ConditionalOnMissingBean(Executor.class)}</b>
     * （Boot 3.5.11 源码 {@code org.springframework.boot.autoconfigure.task.TaskExecutorConfigurations}
     * 的 {@code OnExecutorCondition}，{@code AnyNestedCondition} + {@code REGISTER_BEAN} 阶段）。
     * <p>⇒ 本类的 {@code hotHydrationExecutor} <b>就是一个 {@code Executor}</b>
     * ⇒ ⇒ Boot 的条件<b>不成立</b> ⇒ ⇒ <b>Boot 不会创建</b> {@code applicationTaskExecutor}。
     * <p>⇒ ⇒ 而 B 侧 hot-rpc 的三个类（{@code HotEventConsumer} / {@code HotRedisSnapshotStore} /
     * {@code HotMergeScheduler}）都是 {@code @Qualifier("applicationTaskExecutor")} 注入 ⇒
     * ⇒ 合并进程里<b>直接起不来</b>：
     * <pre>
     * UnsatisfiedDependencyException: HotEventConsumer → HotRedisSnapshotStore
     *   → No qualifying bean of type 'org.springframework.core.task.TaskExecutor' available:
     *     expected at least 1 bean which qualifies as autowire candidate.
     *     Dependency annotations: {@Qualifier("applicationTaskExecutor")}
     * </pre>
     * ⚠️ 这条<b>不是</b>「B 侧写错了」：B 侧 hot-api 与 hot-rpc 是<b>两个进程</b>
     * ⇒ 补全池在 api 进程、{@code applicationTaskExecutor} 在 rpc 进程 ⇒ 两者从不同框 ⇒ 互不干扰。
     * ⇒ ⇒ <b>合并后才碰撞</b> ⇒ 这是 D1 的必然结果，必须在本域补齐。
     *
     * <h2>⚠️ 为什么用 Boot 自己的 {@link ThreadPoolTaskExecutorBuilder}</h2>
     * <p>本 bean 的语义必须与「B 侧 hot-rpc 拿到的那个 {@code applicationTaskExecutor}」<b>完全一致</b>
     * ⇒ ⇒ 那是 <b>Boot 的默认</b> {@code ThreadPoolTaskExecutor}
     * （core 8 / queue 无界 / {@code spring.task.execution.*} 可调）
     * ⇒ ⇒ 用 Boot 的 {@link ThreadPoolTaskExecutorBuilder} 构建 ⇒ <b>逐条等价</b>
     * ⇒ ⇒ <b>不是</b>另造一个「看起来差不多」的池。
     *
     * <h2>⚠️ 为什么 bean 名就取 {@code applicationTaskExecutor}</h2>
     * <p>B 侧那三个类的 {@code @Qualifier("applicationTaskExecutor")} <b>逐字保留</b>
     * ⇒ ⇒ 消息面的三个类与 B 侧的注入写法<b>一字不差</b>（减少一处需要解释的差异）。
     * <p>⚠️ <b>不会与 Boot 撞名</b>：Boot 的那个 {@code applicationTaskExecutor}
     * 的创建条件是 {@code @ConditionalOnMissingBean(Executor.class)}
     * ⇒ 本 bean <b>本身就是</b> {@code Executor} ⇒ ⇒ 条件<b>永远不成立</b>
     * ⇒ ⇒ Boot <b>永远不</b>创建它 ⇒ ⇒ <b>不存在同名冲突</b>（哪怕有人删掉补全池也一样）。
     *
     * @param builder Boot 的执行器构建器（由 {@code TaskExecutionAutoConfiguration} 提供，
     *                <b>不受</b>上面那个条件约束 ⇒ 一定存在）
     * @return 与 B 侧同款的应用级执行器
     */
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }
}
