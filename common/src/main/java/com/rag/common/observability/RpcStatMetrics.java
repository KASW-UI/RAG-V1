package com.rag.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B 侧 {@code core/stat} 的 Java 复刻 —— <b>进程内 RPC 耗时聚合器</b> ＋
 * <b>每分钟一行的统计日志</b>（qps / avg / median / 90th / 99th / 99.9th）。
 *
 * <h3>B 出处（消费点，不是声明点）</h3>
 * <pre>
 *   core/stat/metrics.go:59-70
 *       func NewMetrics(name string) *Metrics {
 *           container := &amp;metricsContainer{name: name, pid: os.Getpid()}
 *           return &amp;Metrics{executor: executors.NewPeriodicalExecutor(logInterval, container), container: container}
 *       }
 *
 *   core/stat/metrics.go:14      logInterval = time.Minute          ← **报告周期 = 60s**
 *   core/stat/metrics.go:73-75   Add(task)      ⇒ executor.Add(task)
 *   core/stat/metrics.go:78-82   AddDrop()      ⇒ executor.Add(Task{Drop: true})
 *   core/stat/metrics.go:107-118 AddTask(v)     ⇒ drops++ 或 tasks=append(tasks, task) + duration += task.Duration
 *   core/stat/metrics.go:120-181 Execute(v)     ⇒ 聚合成 StatReport
 *   core/stat/metrics.go:183-196 RemoveAll()    ⇒ 取出并清零（tasks/duration/drops）
 *   core/stat/metrics.go:207-215 log(report)    ⇒ logx.Statf("(%s) - qps: %.1f/s, drops: %d, avg time: %.1fms, ...")
 *
 *   zrpc/server.go:37            metrics := stat.NewMetrics(c.ListenOn)   ← **每个 rpc server 一个**
 *   zrpc/server.go:52            metrics.SetName(c.Name)
 *   zrpc/internal/serverinterceptors/statinterceptor.go:54-56
 *       metrics.Add(stat.Task{Duration: duration})                        ← **唯一的样本来源**
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:28-29
 *       metrics.AddDrop()                                                 ← **drop 的唯一来源**
 *
 *   core/executors/periodicalexecutor.go:118-148  每 logInterval 一轮 ⇒ Flush() ⇒ RemoveAll() + Execute()
 * </pre>
 *
 * <h3>🔴 三个必须 1:1 照抄的聚合怪癖（照抄它们才是对齐；「顺手改成直觉语义」= 漂移）</h3>
 * <ol>
 *   <li><b>avg 先整除再除</b>（{@code metrics.go:135}）：
 *       {@code report.Average = float32(duration/time.Millisecond) / float32(size)}
 *       ⇒ {@code duration/time.Millisecond} 是 <b>B 的整型除法</b>（纳秒先截断成毫秒）
 *       ⇒ 再除以样本数 ⇒ ⇒ <b>总毫秒被截断后再平均</b>（不是「先平均再截断」）。
 *       例：3 个样本各 0.9ms、总 2.7ms ⇒ 总毫秒 = 2 ⇒ avg = 2/3 = 0.67ms（不是 0.9ms）。</li>
 *   <li><b>median 实为「上偏分位数」</b>（{@code metrics.go:137-141}）：
 *       {@code fiftyPercent := size >> 1; top50pTasks := topK(tasks, fiftyPercent); medianTask := top50pTasks[0]}
 *       ⇒ {@code topK} 取<b>最大的 k 个</b>并放在<b>小顶堆</b>里 ⇒ {@code [0]} = 这 k 个里<b>最慢的那个</b>
 *       ⇒ ⇒ 所谓 median 其实是<b>第 ⌊n/2⌋ 大的样本</b>（偏大），<b>不是</b>教科书的中位数。</li>
 *   <li><b>小样本退化成最大值</b>（{@code metrics.go:157-176} ＋ {@code getTopDuration :198-205}）：
 *       {@code fiftyPercent == 0}（样本数 = 1）⇒ median/90th/99th/99.9th <b>全等于最大值</b>；
 *       {@code tenPercent == 0}（样本数 2~5）⇒ 90th/99th/99.9th <b>全等于 top50p 的最大值</b>。</li>
 * </ol>
 *
 * <h3>⚠️ drops 与 qps/avg <b>互斥</b>（不是「也计入分母」）</h3>
 * <p>B 的 {@code AddTask} 对 drop 样本<b>只</b>加 {@code drops}，<b>不</b>进 {@code tasks}、<b>不</b>进
 * {@code duration} ⇒ ⇒ 被降载丢弃的请求<b>不参与</b> qps / avg / 分位数。
 *
 * <h3>⚠️ 样本容器<b>无上限</b>（与 B 侧 同构）</h3>
 * <p>B 的 {@code metricsContainer.tasks} 是<b>裸 slice</b>：一个周期内的请求数 = 样本数，
 * <b>没有分桶、没有滑动窗口、没有上限</b> ⇒ ⇒ 高 QPS 下内存随 QPS×周期线性增长。
 * 本类<b>刻意照抄</b>（§3.1：行为差异默认判 RAG 错 ⇒ 这里不能"顺手优化"）。
 *
 * <h3>与 Prometheus 那套<b>完全解耦</b>（B 侧 就是解耦的）</h3>
 * <p>B 的 {@code core/stat} <b>不依赖</b> prometheus；{@code core/metric} 也<b>不依赖</b> stat。
 * ⇒ ⇒ 本类<b>不</b>把聚合结果刷进 Prometheus，{@link RpcPrometheusMetrics} 也<b>不</b>读本类的数据。
 */
public final class RpcStatMetrics {

    /** B 侧 {@code zrpc/internal/serverinterceptors/sheddinginterceptor.go:15} {@code const serviceType = "rpc"}（降载统计的默认名）。 */
    public static final String DEFAULT_NAME = "rpc";

    /** B 侧 {@code core/stat/metrics.go:14} {@code logInterval = time.Minute} ⇒ 报告周期 60 秒。 */
    public static final long LOG_INTERVAL_SECONDS = 60L;

    private static final Logger LOG = LoggerFactory.getLogger(RpcStatMetrics.class);

    private static final long NANOS_PER_MILLI = 1_000_000L;

    // ── 容器（B 侧: core/stat/metrics.go:98-105 metricsContainer）────────────────────────

    /** B 侧 {@code metricsContainer.name}。 */
    private volatile String name;

    /** B 侧 {@code metricsContainer.pid}（{@code os.Getpid()}）。 */
    private final long pid;

    /** B 侧 {@code metricsContainer.tasks}（裸 slice ⇒ 本类用可增长的 long[]）。 */
    private long[] tasks = new long[64];

    /** 已用掉的样本格数（B 的 slice 长度）。 */
    private int taskCount;

    /** B 侧 {@code metricsContainer.duration}（纳秒累计）。 */
    private long durationNanos;

    /** B 侧 {@code metricsContainer.drops}。 */
    private int drops;

    private final Object lock = new Object();

    public RpcStatMetrics(String name) {
        this.name = (name == null || name.isEmpty()) ? DEFAULT_NAME : name;
        this.pid = ProcessHandle.current().pid();
    }

    // ── 写入口（B 侧: metrics.go:73-89）──────────────────────────────────────────────────

    /**
     * B 侧 {@code Metrics.Add(Task{Duration})} —— 记一个<b>耗时样本</b>。
     *
     * @param durationNanos 耗时（纳秒）；B 的 {@code stat.Task.Duration} 同为 {@code time.Duration}。
     */
    public void add(long sampleNanos) {
        synchronized (lock) {
            if (tasks.length == taskCount) {
                long[] grown = new long[tasks.length * 2];
                System.arraycopy(tasks, 0, grown, 0, taskCount);
                tasks = grown;
            }
            tasks[taskCount++] = sampleNanos;
            durationNanos += sampleNanos;
        }
    }

    /** B 侧 {@code Metrics.AddDrop()} —— 记一次<b>被丢弃</b>（唯一来源 = 降载层）。 */
    public void addDrop() {
        synchronized (lock) {
            drops++;
        }
    }

    /** B 侧 {@code Metrics.SetName(name)}。 */
    public void setName(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
    }

    /** 当前服务名（B 侧 {@code metricsContainer.name}）。 */
    public String name() {
        return name;
    }

    /** 当前进程号（B 侧 {@code metricsContainer.pid}）。 */
    public long pid() {
        return pid;
    }

    // ── 聚合（B 侧: metrics.go:120-181 Execute）────────────────────────────────────────

    /**
     * 取走当前周期的全部样本并清零（B 侧 {@code metrics.go:183-196 RemoveAll}）。
     *
     * <p>⚠️ B 的顺序是「<b>先换出、再聚合</b>」⇒ 聚合期间新到的请求落进<b>下一个</b>周期。
     * 本类照抄（在同一把锁里换出 ⇒ 不会与 {@link #add} 交错）。
     */
    Samples removeAll() {
        synchronized (lock) {
            Samples samples = new Samples(tasks, taskCount, durationNanos, drops);
            tasks = new long[64];
            taskCount = 0;
            durationNanos = 0;
            drops = 0;
            return samples;
        }
    }

    /**
     * 一个周期内的样本（B 的 {@code tasksDurationPair}）。
     *
     * @param tasks     耗时样本（纳秒）
     * @param size      样本个数
     * @param duration  耗时合计（纳秒）
     * @param drops     被丢弃次数
     */
    record Samples(long[] tasks, int size, long duration, int drops) {
    }

    /**
     * 聚合（B 侧 {@code metrics.go:120-181 Execute}）—— <b>逐行照抄</b>，含三个怪癖。
     *
     * @param samples 一个周期的样本
     */
    StatReport execute(Samples samples) {
        long[] tasks = samples.tasks();
        int size = samples.size();
        long duration = samples.duration();
        int drops = samples.drops();

        // B 侧: metrics.go:130 —— ReqsPerSecond = float32(size) / float32(logInterval/time.Second)
        float reqsPerSecond = (float) size / (float) LOG_INTERVAL_SECONDS;

        float average = 0F;
        float median = 0F;
        float top90th = 0F;
        float top99th = 0F;
        float top99p9th = 0F;

        if (size > 0) {
            // B 侧: metrics.go:135 —— ⚠️ 先整除（纳秒→毫秒）再除样本数
            average = (float) (duration / NANOS_PER_MILLI) / (float) size;

            int fiftyPercent = size >> 1;
            if (fiftyPercent > 0) {
                // B 侧: metrics.go:139-141 —— ⚠️ topK 取「最大的 k 个」放小顶堆 ⇒ [0] = 其中最慢的
                long[] top50pTasks = topK(tasks, size, fiftyPercent);
                median = nanosToMillis(top50pTasks[0]);

                int tenPercent = fiftyPercent / 5;
                if (tenPercent > 0) {
                    long[] top10pTasks = topK(top50pTasks, top50pTasks.length, tenPercent);
                    top90th = nanosToMillis(top10pTasks[0]);

                    int onePercent = tenPercent / 10;
                    if (onePercent > 0) {
                        long[] top1pTasks = topK(top10pTasks, top10pTasks.length, onePercent);
                        top99th = nanosToMillis(top1pTasks[0]);

                        int pointOnePercent = onePercent / 10;
                        if (pointOnePercent > 0) {
                            long[] topPointOneTasks = topK(top1pTasks, top1pTasks.length, pointOnePercent);
                            top99p9th = nanosToMillis(topPointOneTasks[0]);
                        } else {
                            top99p9th = topDurationMillis(top1pTasks);
                        }
                    } else {
                        float mostDuration = topDurationMillis(top10pTasks);
                        top99th = mostDuration;
                        top99p9th = mostDuration;
                    }
                } else {
                    float mostDuration = topDurationMillis(top50pTasks);
                    top90th = mostDuration;
                    top99th = mostDuration;
                    top99p9th = mostDuration;
                }
            } else {
                float mostDuration = topDurationMillis(tasks, size);
                median = mostDuration;
                top90th = mostDuration;
                top99th = mostDuration;
                top99p9th = mostDuration;
            }
        }

        return new StatReport(name, System.currentTimeMillis() / 1000L, pid, drops,
                reqsPerSecond, average, median, top90th, top99th, top99p9th);
    }

    /** B 侧 {@code metrics.go:141} {@code float32(d.Duration) / float32(time.Millisecond)}。 */
    private static float nanosToMillis(long nanos) {
        return (float) nanos / (float) NANOS_PER_MILLI;
    }

    /** B 侧 {@code metrics.go:198-205 getTopDuration}：{@code topK(tasks, 1)[0]} = 最大值。 */
    private static float topDurationMillis(long[] tasks) {
        return topDurationMillis(tasks, tasks.length);
    }

    /**
     * 同上，但显式给出<b>有效样本数</b>。
     *
     * <p>⚠️ 原始样本数组的<b>容量</b>可能大于样本数（B 的 slice 同款：{@code c.tasks} 的 cap 可大于 len）
     * ⇒ ⇒ 必须按<b>有效长度</b>取，否则会把扩容留下的 0 当样本（0 不会改变最大值，但会改变 topK 的 k 语义）。
     */
    private static float topDurationMillis(long[] tasks, int size) {
        if (size < 1) {
            return 0F;
        }
        return nanosToMillis(topK(tasks, size, 1)[0]);
    }

    /**
     * B 侧 {@code core/stat/topk.go:31-44 topK} —— 取<b>最大的 k 个</b>，放在<b>小顶堆</b>里。
     *
     * <p>⚠️ 返回数组的 {@code [0]} = 这 k 个里<b>最小</b>的那个 ⇒ 即「第 k 大」。
     * <pre>
     *   for _, each := range all {
     *       if h.Len() &lt; k { heap.Push(h, each) }
     *       else if (*h)[0].Duration &lt; each.Duration { heap.Pop(h); heap.Push(h, each) }
     *   }
     * </pre>
     */
    static long[] topK(long[] all, int allSize, int k) {
        long[] heap = new long[Math.max(k, 1)];
        int size = 0;
        for (int i = 0; i < allSize; i++) {
            long each = all[i];
            if (size < k) {
                heap[size++] = each;
                siftUp(heap, size - 1);
            } else if (heap[0] < each) {
                heap[0] = each;
                siftDown(heap, size);
            }
        }
        return heap;
    }

    private static void siftUp(long[] heap, int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (heap[parent] <= heap[index]) {
                return;
            }
            long tmp = heap[parent];
            heap[parent] = heap[index];
            heap[index] = tmp;
            index = parent;
        }
    }

    private static void siftDown(long[] heap, int size) {
        int index = 0;
        while (true) {
            int left = 2 * index + 1;
            if (left >= size) {
                return;
            }
            int smallest = left;
            int right = left + 1;
            if (right < size && heap[right] < heap[left]) {
                smallest = right;
            }
            if (heap[index] <= heap[smallest]) {
                return;
            }
            long tmp = heap[smallest];
            heap[smallest] = heap[index];
            heap[index] = tmp;
            index = smallest;
        }
    }

    // ── 报告与日志（B 侧: metrics.go:207-215）───────────────────────────────────────────

    /**
     * B 侧 {@code core/stat/metrics.go:27-38 StatReport}。
     *
     * @param name          服务名
     * @param timestamp     Unix 秒（B 侧 {@code time.Now().Unix()}）
     * @param pid           进程号
     * @param reqsPerSecond 每秒请求数（= 样本数 / 60）
     * @param drops         被丢弃次数（<b>不计入</b> qps/avg/分位数）
     * @param average       平均耗时（ms）
     * @param median        「上偏」中位数（ms）
     * @param top90th       90 分位（ms）
     * @param top99th       99 分位（ms）
     * @param top99p9th     99.9 分位（ms）
     */
    public record StatReport(String name, long timestamp, long pid, int drops,
                             float reqsPerSecond, float average, float median,
                             float top90th, float top99th, float top99p9th) {
    }

    /**
     * B 侧 {@code metrics.go:207-215 log(report)} 的那一行 —— <b>逐字照抄</b>。
     *
     * <p>⚠️ <b>诚实边界</b>：B 的 {@code %.1f} 用 B 的 <b>unbiased</b> 取整（平局取偶），
     * Java 的 {@link String#format} 用 {@link java.math.RoundingMode#HALF_UP}（平局远离零）
     * ⇒ ⇒ 当某个值<b>恰好落在 .x5 平局</b>时，最后一位可能差 1。
     * ⇒ 这是<b>格式化器差异</b>，不是聚合语义差异（聚合值本身逐位相同）。
     */
    static String formatReport(StatReport report) {
        return String.format(
                "(%s) - qps: %.1f/s, drops: %d, avg time: %.1fms, med: %.1fms, "
                        + "90th: %.1fms, 99th: %.1fms, 99.9th: %.1fms",
                report.name(), report.reqsPerSecond(), report.drops(), report.average(),
                report.median(), report.top90th(), report.top99th(), report.top99p9th());
    }

    // ── 进程级单例 + 每分钟上报（B 侧: zrpc/server.go:37 + core/executors）────────────────

    private static volatile RpcStatMetrics instance;

    private ScheduledExecutorService scheduler;

    /** 进程级单例（B 侧：每个 rpc server 一个 {@code stat.Metrics} ⇒ 本仓每个 rpc 进程一个）。 */
    public static RpcStatMetrics getInstance() {
        RpcStatMetrics local = instance;
        if (local == null) {
            synchronized (RpcStatMetrics.class) {
                local = instance;
                if (local == null) {
                    local = new RpcStatMetrics(DEFAULT_NAME);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 启动每分钟的上报任务（B 侧 {@code core/executors/periodicalexecutor.go:118-148}）。
     *
     * <p>⚠️ <b>幂等</b>：重复调用不会起第二个线程（与 {@link SheddingStat#startReporter()} 同款写法）。
     * <p>⚠️ <b>守护线程</b>：不阻止 JVM 退出（B 侧 那边是 goroutine，进程退出即消失）。
     */
    public synchronized void startReporter() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "go-style-stat-metrics");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::reportOnce,
                LOG_INTERVAL_SECONDS, LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 跑一次上报：换出样本 → 聚合 → 打一行日志（B 的 {@code Flush()} 语义）。
     *
     * <p>⚠️ B 的 {@code logx.Statf} 写的是<b>统计日志通道</b>；本仓没有独立的 stat 通道，
     * 与 {@link SheddingStat} 同款 ⇒ 走普通 INFO 通道，<b>格式逐字照抄</b>。
     */
    void reportOnce() {
        try {
            StatReport report = execute(removeAll());
            LOG.info("{}", formatReport(report));
        } catch (RuntimeException e) {
            // ⚠️ 统计线程绝不能因为一次异常而死掉（统计失败不应该影响业务流量）。
            LOG.warn("stat metrics 上报失败（已忽略）", e);
        }
    }

    /** 仅测试用：重置进程级单例。 */
    static void resetInstanceForTest() {
        synchronized (RpcStatMetrics.class) {
            if (instance != null && instance.scheduler != null) {
                instance.scheduler.shutdownNow();
            }
            instance = null;
        }
    }

    /** 仅测试用：当前周期的样本数与 drop 数（不消费）。 */
    public long pendingSampleCount() {
        synchronized (lock) {
            return taskCount;
        }
    }

    /** 仅测试用：当前累计的 drop 数（不消费）。 */
    public int pendingDrops() {
        synchronized (lock) {
            return drops;
        }
    }

    /** 仅测试用：累计耗时（纳秒，不消费）。 */
    public long pendingDurationNanos() {
        synchronized (lock) {
            return durationNanos;
        }
    }

    /** 仅测试用：注入一个确定的耗时样本（等价 {@link #add(long)}）。 */
    void addForTest(long durationNanos) {
        add(durationNanos);
    }
}
