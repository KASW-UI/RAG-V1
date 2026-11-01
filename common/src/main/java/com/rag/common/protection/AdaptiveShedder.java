package com.rag.common.protection;

import java.lang.management.OperatingSystemMXBean;
import java.util.function.IntSupplier;

/**
 * Go {@code core/load/adaptiveshedder.go} 的 Java 复刻 —— CPU 自适应降载的<b>判据</b>部分。
 *
 * <p><b>⚠️ 先说清一件事：Go 侧这不是「CPU 超阈值就 503」</b>。
 * 很多「简化移植」会写成 {@code if cpu > 90 then reject}，那是<b>另一个行为</b>：
 * Go 的 {@code shouldDrop()}（{@code adaptiveshedder.go:208-225}）需要
 * <b>同时</b>满足三个条件才丢请求：
 * <ol>
 *   <li>{@code systemOverloaded() || stillHot()}：CPU ≥ 阈值，<b>或</b>刚降过载仍在冷却期；</li>
 *   <li>{@code highThru()}：<b>在途请求数</b>超过一个用 QPS × RT 算出来的动态上限。</li>
 * </ol>
 * 单看 CPU 会在「CPU 高但在途很少」时误丢请求（此时服务其实扛得住），
 * 也会在「CPU 尚可但在途堆积」时该丢不丢。本类按 Go 原条件实现。
 *
 * <p><b>常量（{@code adaptiveshedder.go:18-30}，逐个照抄）</b>：
 * <table border="1">
 *   <caption>降载常量</caption>
 *   <tr><th>Go 常量</th><th>值</th><th>Java 对应</th></tr>
 *   <tr><td>{@code defaultBuckets}</td><td>50</td><td>{@link #BUCKETS}</td></tr>
 *   <tr><td>{@code defaultWindow}</td><td>5s</td><td>⇒ 桶宽 100ms</td></tr>
 *   <tr><td>{@code defaultCpuThreshold}</td><td>900（千分比）</td><td>来自配置</td></tr>
 *   <tr><td>{@code defaultMinRt}</td><td>1000.0（ms）</td><td>{@link #DEFAULT_MIN_RT_MS}</td></tr>
 *   <tr><td>{@code flyingBeta}</td><td>0.9</td><td>{@link #FLYING_BETA}</td></tr>
 *   <tr><td>{@code coolOffDuration}</td><td>1s</td><td>{@link #COOL_OFF_MS}</td></tr>
 *   <tr><td>{@code cpuMax}</td><td>1000</td><td>{@link #CPU_MAX}</td></tr>
 *   <tr><td>{@code overloadFactorLowerBound}</td><td>0.1</td><td>{@link #OVERLOAD_FACTOR_LOWER_BOUND}</td></tr>
 * </table>
 *
 * <p><b>⚠️ 与 Go 的一处刻意的表达层差异：CPU 采样源</b>：
 * Go 的 {@code systemOverloadChecker}（{@code adaptiveshedder.go:41-43}）读
 * {@code stat.CpuUsage()}，Linux 下来自 {@code /proc/stat}，是<b>整机千分比</b>。
 * Java 侧用 {@link OperatingSystemMXBean}：
 * <ul>
 *   <li>{@code getSystemCpuLoad()}（默认）—— 同样是整机口径，<b>与 Go 对齐</b>；</li>
 *   <li>{@code getProcessCpuLoad()} —— 仅本 JVM，偏离 Go，需显式配置。</li>
 * </ul>
 * 二者在某些容器里都可能返回 <b>{@code -1}（不可用）</b>。
 * 本类的处理是 <b>fail-open：-1 视作「未过载」，不丢任何请求</b>，
 * 与 Go 侧 {@code systemOverloaded()} 的首个分支（{@code !systemOverloadChecker → false}）
 * 语义一致 —— <b>宁可少降载，不可在读不到指标时误杀全部流量</b>。
 */
public final class AdaptiveShedder {

    private static final int BUCKETS = 50;
    private static final long BUCKET_DURATION_MS = 100L;      // defaultWindow 5s / 50
    private static final long WINDOW_MS = 5_000L;
    /** {@code windowScale = 1s / bucketDuration / 1000}（adaptiveshedder.go:115） ⇒ 0.01 */
    private static final double WINDOW_SCALE =
            1_000D / BUCKET_DURATION_MS / 1_000D;
    private static final double DEFAULT_MIN_RT_MS = 1_000D;   // time.Second / time.Millisecond
    private static final double FLYING_BETA = 0.9D;
    private static final long COOL_OFF_MS = 1_000L;
    private static final double CPU_MAX = 1_000D;              // millicpu
    private static final double OVERLOAD_FACTOR_LOWER_BOUND = 0.1D;

    private final long cpuThresholdPermille;
    private final ProtectionProperties.CpuSource cpuSource;

    private final Object lock = new Object();

    private long flying;
    private double avgFlying;
    private boolean droppedRecently;
    private long overloadTimeMs;

    /** 注入式 CPU 读数（千分比，-1 表示不可用）；测试可覆盖为确定性值。 */
    private volatile IntSupplier cpuPermilleReader;

    public AdaptiveShedder(long cpuThresholdPermille, ProtectionProperties.CpuSource cpuSource) {
        this.cpuThresholdPermille = cpuThresholdPermille;
        this.cpuSource = cpuSource;
    }


    // ⚠️ **2026-11-02 批次 H · 可见性放宽（L4 · D11 包压平 · 记留痕）**
    //
    // 本类原是 B 侧 {@code GoStyleAdaptiveShedder} 的**逐字抄**（同包 ⇒ 方法全是 package-private）。
    // 但 RAG-V1 的 **D11 包压平**把「REST 侧的降载 filter」放进了
    // {@code com.rag.common.autoconfigure}（与 13 个 auto-config 同包）
    // ⇒ ⇒ **跨包调不到 package-private 方法** ⇒ ⇒ REST 侧只能退化到「只看 CPU 指示器」
    // ⇒ ⇒ **丢掉了 B 侧 REST 侧本有的「双条件」判据**（CPU 过载 **且** 在途超动态上限）
    // ⇒ ⇒ 属**真实漂移**（本仓比 B 更早丢请求）⇒ ⇒ 批次 H 把这 5 个方法放宽为 {@code public}。
    //
    // ⚠️ **只放宽可见性，不改任何一行逻辑** ⇒ 逐字对齐 B 侧仍成立。
    // ⚠️ **为什么不用「把 filter 挪回 protection 包」**：那会新增顶层类文件 ⇒ 动 File_Tree 与
    //     10 个域模块的装配面 ⇒ 代价远大于可见性放宽（且 D11 已定「域内单层技术角色包树」）。

    /**
     * 注入式 CPU 读数（覆盖用）—— 批次 H 起为 {@code public}（见类内「可见性放宽」注记）。
     */
    public void setCpuPermilleReader(IntSupplier reader) {
        this.cpuPermilleReader = reader;
    }

    /**
     * {@code Allow()} 的判据（{@code adaptiveshedder.go:126-136}）：
     * {@code shouldDrop()} 为真 ⇒ 拒绝；否则在途 +1 并返回一个可回调的 promise。
     *
     * @return {@code true} = 允许（调用方随后必须调 {@link #pass} 或 {@link #fail}）
     */
    public boolean allow() {
        if (shouldDrop()) {
            synchronized (lock) {
                droppedRecently = true;
            }
            return false;
        }
        addFlying(1);
        return true;
    }

    /**
     * {@code promise.Pass()}（{@code adaptiveshedder.go:284-289}）：
     * 在途 -1，并把本次 RT 与 QPS 记进窗口。
     *
     * <p>⚠️ <b>只有 Pass 才记 RT 与 QPS</b>，Fail 只减在途。照搬这一点很关键：
     * 若让 Fail 也记 QPS，降载期的 QPS 会被「被丢的请求」抬高，
     * 进而抬高 {@code maxPass} → 抬高飞行上限 → <b>越降载越不降</b>，正好抵消降载效果。
     *
     * <p><b>⚠️ RT 必须是「浮点毫秒 + 向上取整」，不能是「整型毫秒」</b>
     * （Go {@code adaptiveshedder.go:285-287}）：
     * <pre>
     *   rt := float64(timex.Since(p.start)) / float64(time.Millisecond)
     *   p.shedder.rtCounter.Add(int64(math.Ceil(rt)))
     * </pre>
     * Go 是 <b>float64 毫秒</b> 再 {@code math.Ceil} ⇒ <b>向上</b>取整。
     * <p>⚠️ <b>本类此前是「整型毫秒截断 + 至少记 1」</b>
     * （{@code long rtMs = nanos / 1_000_000L} ⇒ 截断；再 {@code Math.max(1L, ceil(rtMs))}）
     * ⇒ <b>系统性比 Go 少记约 1ms</b> ⇒ {@code minRt()} 偏小 ⇒
     * {@code maxFlight = maxPass * minRt * 0.01} 偏小 ⇒
     * <b>在途上限系统性偏低 ⇒ RAG 比 Go 更早降载</b>。
     * <p>⇒ 现按 Go 逐字实现：<b>浮点毫秒 + ceil，无下限</b>
     * （Go 的 {@code Add(0)} 是合法的：{@code Count} 照样 +1，只是 {@code Sum} 不加）。
     */
    public void pass(double rtMs) {
        addFlying(-1);
        synchronized (lock) {
            rollTo(System.currentTimeMillis());
            rtSlots[currentSlot][0] += (long) Math.ceil(rtMs);
            rtSlots[currentSlot][1] += 1L;
            passSlots[currentSlot] += 1L;
        }
    }

    /** {@code promise.Fail()}（{@code adaptiveshedder.go:280-282}）：只减在途。 */
    public void fail() {
        addFlying(-1);
    }

    /**
     * {@code addFlying()}（{@code adaptiveshedder.go:139-152}）：
     * {@code avgFlying = avgFlying*beta + flying*(1-beta)}，<b>只在 delta<0 时更新</b>。
     *
     * <p>Go 注释解释了为什么要有这个滞后：<i>when the flying requests increase rapidly,
     * avgFlying increase slower, accept more requests; when the flying requests drop rapidly,
     * avgFlying drop slower, accept fewer requests</i>。
     */
    private void addFlying(long delta) {
        synchronized (lock) {
            this.flying += delta;
            if (delta < 0) {
                this.avgFlying = this.avgFlying * FLYING_BETA + this.flying * (1 - FLYING_BETA);
            }
        }
    }

    /** {@code shouldDrop()}（{@code adaptiveshedder.go:208-225}）。 */
    boolean shouldDrop() {
        if (!(systemOverloaded() || stillHot())) {
            return false;
        }
        return highThru();
    }

    /** {@code systemOverloaded()}（{@code adaptiveshedder.go:245-252}）。 */
    boolean systemOverloaded() {
        int cpu = readCpuPermille();
        if (cpu < 0) {
            // fail-open：读不到 ⇒ 视作未过载（Go 侧等价于 systemOverloadChecker 返回 false）
            return false;
        }
        if (cpu < cpuThresholdPermille) {
            return false;
        }
        synchronized (lock) {
            this.overloadTimeMs = System.currentTimeMillis();
        }
        return true;
    }

    /** {@code stillHot()}（{@code adaptiveshedder.go:237-242}）：冷却期内仍算过载。 */
    boolean stillHot() {
        synchronized (lock) {
            if (!this.droppedRecently) {
                return false;
            }
            if (this.overloadTimeMs == 0L) {
                return false;
            }
            if (System.currentTimeMillis() - this.overloadTimeMs < COOL_OFF_MS) {
                return true;
            }
            this.droppedRecently = false;
            return false;
        }
    }

    /**
     * {@code highThru()}（{@code adaptiveshedder.go:154-160}）：
     * <b>avgFlying 与 flying 都</b>超过 {@code maxFlight * overloadFactor} 才算「真的过载」。
     */
    boolean highThru() {
        double cap = maxFlight() * overloadFactor();
        synchronized (lock) {
            return this.avgFlying > cap && this.flying > cap;
        }
    }

    /** {@code maxFlight()}（{@code adaptiveshedder.go:162-170}）：Little 定律 QPS×RT，下界 1。 */
    double maxFlight() {
        return Math.max(maxPass() * minRt() * WINDOW_SCALE, 1D);
    }

    /** {@code maxPass()}（{@code adaptiveshedder.go:172-180}）：窗口内<b>单桶最大</b> QPS。 */
    long maxPass() {
        synchronized (lock) {
            rollTo(System.currentTimeMillis());
            long result = 1L;
            for (int i : completedSlots()) {
                if (passSlots[i] > result) {
                    result = passSlots[i];
                }
            }
            return result;
        }
    }

    /**
     * {@code minRt()}（{@code adaptiveshedder.go:182-196}）：
     * 窗口内<b>单桶最小平均 RT</b>，无数据时取 {@code defaultMinRt = 1000ms}。
     *
     * <p>Go 注释明说这个缺省值是 <i>a reasonable large value to avoid dropping requests</i>
     * ⇒ 起步阶段 {@code maxFlight} 很大 ⇒ <b>不会误丢</b>。这个「冷启动不误杀」的性质必须保住。
     */
    double minRt() {
        synchronized (lock) {
            rollTo(System.currentTimeMillis());
            double result = DEFAULT_MIN_RT_MS;
            for (int i : completedSlots()) {
                if (rtSlots[i][1] <= 0) {
                    continue;
                }
                double avg = Math.round((double) rtSlots[i][0] / (double) rtSlots[i][1]);
                if (avg < result) {
                    result = avg;
                }
            }
            return result;
        }
    }

    /**
     * {@code overloadFactor()}（{@code adaptiveshedder.go:202-206}）：
     * {@code clamp((1000 - cpu)/(1000 - threshold), 0.1, 1)}。
     *
     * <p>CPU 恰好等于阈值时 factor=1（不削减上限）；越往上越低，但<b>保底 0.1</b> ——
     * Go 注释：<i>at least accept 10% of acceptable requests, even cpu is highly overloaded</i>。
     * ⇒ <b>即使 CPU 打满，也永远留 10% 的通过率</b>，这是防雪崩的底线。
     */
    double overloadFactor() {
        int cpu = readCpuPermille();
        if (cpu < 0) {
            return 1D;
        }
        double span = CPU_MAX - cpuThresholdPermille;
        if (span <= 0D) {
            return OVERLOAD_FACTOR_LOWER_BOUND;
        }
        double factor = (CPU_MAX - cpu) / span;
        return Math.max(OVERLOAD_FACTOR_LOWER_BOUND, Math.min(1D, factor));
    }

    private int readCpuPermille() {
        IntSupplier injected = this.cpuPermilleReader;
        if (injected != null) {
            return injected.getAsInt();
        }
        // ⚠️ 采样实现已抽到 CpuThresholdHealthIndicator（与 SheddingStat 的统计日志共用同一份口径）。
        //    保持「SYSTEM 默认 = 整机千分比（Go 的 stat.CpuUsage()）」与「读不到 ⇒ -1 ⇒ fail-open」。
        return switch (cpuSource) {
            case PROCESS -> CpuThresholdHealthIndicator.processCpuPermille();
            case SYSTEM -> CpuThresholdHealthIndicator.systemCpuPermille();
        };
    }

    // ── 窗口推进：等价 Go 的 RollingWindow + IgnoreCurrentBucket ──────────────────

    /**
     * 把两个窗口按时间片切成 {@link #BUCKETS} 个槽；槽内计数与 {@link GoStyleBreaker}
     * 用同一套「按时间片寻址 + 过期清零」的写法，避免出队式推进的边界歧义。
     */
    private final long[][] rtSlots = new long[BUCKETS][2];   // [0]=RT 之和, [1]=请求数
    private final long[] passSlots = new long[BUCKETS];      // 单桶 QPS
    private final long[] slotStartMs = new long[BUCKETS];

    /** 当前时间片的起始毫秒；{@link #currentSlot} 指向该片对应的槽。 */
    private long currentBucketStartMs = -1L;
    private int currentSlot = -1;

    private int slotIndexOf(long startMs) {
        return (int) Math.floorMod(startMs / BUCKET_DURATION_MS, (long) BUCKETS);
    }

    /** 定位（或初始化）当前时间片的槽；返回该片起始毫秒。 */
    private long rollTo(long now) {
        long start = Math.floorDiv(now, BUCKET_DURATION_MS) * BUCKET_DURATION_MS;
        int idx = slotIndexOf(start);
        if (currentBucketStartMs == start) {
            return start;
        }
        if (slotStartMs[idx] != start) {
            // 该槽属于更早的时间片（或从未用过）⇒ 清零复用
            passSlots[idx] = 0L;
            rtSlots[idx][0] = 0L;
            rtSlots[idx][1] = 0L;
            slotStartMs[idx] = start;
        }
        currentBucketStartMs = start;
        currentSlot = idx;
        return start;
    }

    /**
     * 收集窗口内<b>已完成</b>桶的槽下标（跳过当前半桶与陈旧槽）。
     *
     * <p>⚠️ 对应 Go {@code collection/rollingwindow.go:70-72} 的 {@code IgnoreCurrentBucket}
     * （注释：<i>ignore the current bucket, because of partial data</i>），
     * 以及它把陈旧槽视为 0 的行为。
     *
     * <p><b>必须持有 {@link #lock}</b>（调用方均已持有）。
     */
    private int[] completedSlots() {
        long nowBucket = Math.floorDiv(System.currentTimeMillis(), BUCKET_DURATION_MS);
        int[] out = new int[BUCKETS];
        int n = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (i == currentSlot) {
                continue;                                   // ignore current
            }
            if (slotStartMs[i] == 0L
                    || nowBucket - Math.floorDiv(slotStartMs[i], BUCKET_DURATION_MS) >= BUCKETS) {
                continue;                                   // 陈旧槽，计 0
            }
            out[n++] = i;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** 排障用：当前在途数。 */
    public long flying() {
        synchronized (lock) {
            return flying;
        }
    }

    /** 排障用：窗口长度（ms）。 */
    static long windowMs() {
        return WINDOW_MS;
    }

    /**
     * <b>测试接缝</b>：当前时间片内记录的 RT 之和 / 请求数。
     *
     * <p>对应 Go {@code adaptiveshedder.go:287} 的
     * {@code p.shedder.rtCounter.Add(int64(math.Ceil(rt)))}
     * 落在<b>当前桶</b>里的 {@code Sum} 与 {@code Count}。
     *
     * <p><b>为什么需要这个接缝</b>：{@link #minRt()} 只统计<b>已完成</b>的桶
     * （Go 的 {@code IgnoreCurrentBucket} 同语义），
     * 所以「<b>刚</b>记进去的 RT」<b>当场读不出来</b> ——
     * 断言它要么靠 sleep 等桶翻页（CI 上可能不稳：机器被挂起 >5s 就会因「陈旧桶被跳过」而假红），
     * 要么就只能经 {@code minRt()} 间接反推 ——
     * 而<b>恰恰是「0ms 记 0 还是记 1」这种差别会被反推吃掉</b>
     * （{@code minRt} 取的是桶内<b>平均</b>，单个样本的差别会被平均掉）。
     *
     * <p>⇒ 本接缝让「RT 统计口径」可以被<b>逐条</b>断言，而不是只能看最终效果。
     */
    long recordedRtSum() {
        synchronized (lock) {
            return currentSlot < 0 ? 0L : rtSlots[currentSlot][0];
        }
    }

    /** 测试接缝：当前时间片内记录的请求数（对应 Go 当前桶的 {@code Count}）。 */
    long recordedRtCount() {
        synchronized (lock) {
            return currentSlot < 0 ? 0L : rtSlots[currentSlot][1];
        }
    }
}