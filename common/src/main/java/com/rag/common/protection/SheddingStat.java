package com.rag.common.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B 侧 {@code core/load/sheddingstat.go} 的 Java 复刻 —— <b>降载的三个计数</b> +
 * <b>每分钟一行的统计日志</b>。
 *
 * <h3>B 出处（消费点，不是声明点）</h3>
 * <pre>
 *   core/load/sheddingstat.go:37-49
 *       func (s *SheddingStat) IncrementTotal() { atomic.AddInt64(&amp;s.total, 1) }
 *       func (s *SheddingStat) IncrementPass()  { atomic.AddInt64(&amp;s.pass,  1) }
 *       func (s *SheddingStat) IncrementDrop()  { atomic.AddInt64(&amp;s.drop,  1) }
 *
 *   core/load/sheddingstat.go:51-68  loop()
 *       st := s.reset()
 *       c := stat.CpuUsage()
 *       if st.Drop == 0 {
 *           logx.Statf("(%s) shedding_stat [1m], cpu: %d, total: %d, pass: %d, drop: %d", ...)
 *       } else {
 *           logx.Statf("(%s) shedding_stat_drop [1m], cpu: %d, total: %d, pass: %d, drop: %d", ...)
 *       }
 *
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:17-20
 *       var sheddingStat *load.SheddingStat           // ← **包级单例**（进程内一个）
 *       var lock sync.Mutex
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:51-57
 *       func ensureSheddingStat() { ... }              // 配 sync.Mutex 懒初始化
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:28
 *       sheddingStat.IncrementTotal()                  // 每个请求都 +1
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:33
 *       sheddingStat.IncrementDrop()                   // 被降载丢弃时 +1
 *   zrpc/internal/serverinterceptors/sheddinginterceptor.go:42
 *       sheddingStat.IncrementPass()                  // 正常通过时 +1
 * </pre>
 *
 * <h3>⚠️ 一条必须先纠正的认知：B 侧这些计数<b>不在 Prometheus 里</b></h3>
 * <p>本类落地前，台账 {@code DRIFT-MSG-V-025} 写的是「B 侧可以在 {@code /metrics} 里看到
 * 「这个 rpc 降载丢了多少」「熔断拒了多少」」—— <b>这句是错的</b>，实测 B 侧 源码：
 * <ul>
 *   <li><b>降载的三个计数</b>：只在 <b>每分钟一行的日志</b>里出现
 *       （{@code logx.Statf("(%s) shedding_stat [1m], …")}），
 *       <b>没有</b>对应的 prometheus 指标；</li>
 *   <li><b>熔断的计数</b>（{@code core/breaker/googlebreaker.go:121-130} 的 {@code markDrop}/{@code markFailure}）
 *       写进 {@code b.stat}（{@code collection.RollingWindow}）——
 *       那是<b>熔断算法自己的状态</b>（算 dropRatio 用），<b>根本没有对外暴露</b>。</li>
 * </ul>
 * ⇒ 所以本类的做法是 <b>1:1 照 B 侧</b>：
 * <b>三个计数 + 每分钟一行同名同格式的日志</b>。
 * ⇒ <b>刻意不引入 Micrometer</b>：B 侧没有 Prometheus 指标，
 * 本仓若凭空加一个 {@code rag_protection_shedding_total} 那是<b>发明</b>一个 B 侧 没有的能力，
 * 与「B 侧 是唯一基准」相悖（§3.2）。
 * ⇒ 也因此<b>不需要给 {@code service/common} 加 micrometer 依赖</b>
 * （那是 20 个组件共享的依赖树，改它属于跨域影响面）。
 *
 * <h3>⚠️ 为什么日志里没有「熔断拒了多少」</h3>
 * <p>因为 B 侧也没有。熔断的拒绝数只在 {@code b.stat} 这个滚动窗口里，
 * 用于下一轮算 {@code dropRatio}；<b>没有任何一行日志或指标把它暴露出来</b>。
 * ⇒ 本仓也<b>不暴露</b>。想看熔断是否在工作，只能看它**开路后请求开始失败**这个可观察结果。
 */
public final class SheddingStat {

    /** B 侧 {@code zrpc/internal/serverinterceptors/sheddinginterceptor.go:15} {@code const serviceType = "rpc"}。 */
    public static final String SERVICE_TYPE = "rpc";

    private static final Logger LOG = LoggerFactory.getLogger(SheddingStat.class);

    /** 统计周期：B 侧 {@code core/stat/metrics.go:14} {@code logInterval = time.Minute}。 */
    private static final long REPORT_INTERVAL_MINUTES = 1L;

    private final String name;

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong pass = new AtomicLong();
    private final AtomicLong drop = new AtomicLong();

    public SheddingStat(String name) {
        this.name = name;
    }

    /** B 侧 {@code IncrementTotal()}：每个进入降载拦截器的请求都 +1。 */
    public void incrementTotal() {
        total.incrementAndGet();
    }

    /** B 侧 {@code IncrementPass()}：正常通过（<b>不含</b> ctx 超时那一支）。 */
    public void incrementPass() {
        pass.incrementAndGet();
    }

    /** B 侧 {@code IncrementDrop()}：被降载丢弃。 */
    public void incrementDrop() {
        drop.incrementAndGet();
    }

    /** 排障用：当前累计值（<b>不</b>清零）。 */
    public Snapshot snapshot() {
        return new Snapshot(total.get(), pass.get(), drop.get());
    }

    /**
     * B 侧 {@code loop()} 的那段 {@code logx.Statf} —— <b>逐字照抄</b>。
     *
     * <p>⚠️ B 侧 用 {@code %d} 打的是<b>整数千分比</b>（{@code stat.CpuUsage()} 返回 0..1000）。
     */
    String formatLine(int cpuPermille) {
        Snapshot s = snapshot();
        String tag = (s.drop() == 0L) ? "shedding_stat" : "shedding_stat_drop";
        return String.format("(%s) %s [1m], cpu: %d, total: %d, pass: %d, drop: %d",
                name, tag, cpuPermille, s.total(), s.pass(), s.drop());
    }

    /** B 侧 {@code reset()}：取当前值并清零。 */
    private Snapshot reset() {
        Snapshot s = snapshot();
        total.set(0L);
        pass.set(0L);
        drop.set(0L);
        return s;
    }

    /**
     * 不可变快照。
     *
     * @param total 进入降载判据的请求总数
     * @param pass  通过的请求数（ctx 超时那一支<b>不</b>计入，与 B 的 defer 一致）
     * @param drop  被降载丢弃的请求数
     */
    public record Snapshot(long total, long pass, long drop) {
    }

    // ── 进程级单例 + 每分钟上报（B 侧: sheddinginterceptor.go:17-20 + sheddingstat.go:30-35）────────

    private static volatile SheddingStat instance;

    private ScheduledExecutorService scheduler;

    /**
     * 进程级单例（B 的 {@code var sheddingStat *load.SheddingStat} + {@code ensureSheddingStat()}）。
     *
     * <p>⚠️ B 侧 用包级变量 + {@code sync.Mutex} 懒初始化；本类用 {@code volatile} + 双重检查，
     * 语义等价（幂等，且并发下只会有一个实例被发布）。
     */
    public static SheddingStat getInstance() {
        SheddingStat local = instance;
        if (local == null) {
            synchronized (SheddingStat.class) {
                local = instance;
                if (local == null) {
                    local = new SheddingStat(SERVICE_TYPE);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 启动每分钟的上报任务（B 侧 {@code NewSheddingStat} 里的 {@code go st.run()}）。
     *
     * <p>⚠️ <b>幂等</b>：重复调用不会起第二个线程。
     * ⚠️ <b>守护线程</b>：不阻止 JVM 退出（B 侧 那边是 goroutine，进程退出即消失）。
     */
    public synchronized void startReporter() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "go-style-shedding-stat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::reportOnce,
                REPORT_INTERVAL_MINUTES, REPORT_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 跑一次上报：打一行日志并清零（B 侧 {@code loop()} 的 {@code st := s.reset()} 语义）。
     *
     * <p>⚠️ B 的顺序是「<b>先 reset 再打</b>」—— 它拿的是 reset 返回的快照，
     * 而 reset 本身把计数清零 ⇒ 日志里的数是<b>刚过去这一分钟</b>的数。
     * 本类照抄：**先取快照并清零，再打那一行**。
     */
    void reportOnce() {
        try {
            Snapshot s = reset();
            int cpu = CpuThresholdHealthIndicator.systemCpuPermille();
            String tag = (s.drop() == 0L) ? "shedding_stat" : "shedding_stat_drop";
            LOG.info("({}) {} [1m], cpu: {}, total: {}, pass: {}, drop: {}",
                    name, tag, cpu, s.total(), s.pass(), s.drop());
        } catch (RuntimeException e) {
            // ⚠️ 统计线程绝不能因为一次异常而死掉（B 侧 那边是 goroutine，panic 会直接崩进程 ——
            //    这里刻意选择「吞掉异常继续跑」，因为统计失败**不应该**影响业务流量）。
            LOG.warn("shedding stat 上报失败（已忽略）", e);
        }
    }

    /** 仅测试用：重置进程级单例。 */
    static void resetInstanceForTest() {
        synchronized (SheddingStat.class) {
            if (instance != null && instance.scheduler != null) {
                instance.scheduler.shutdownNow();
            }
            instance = null;
        }
    }
}
