package com.rag.hot.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p><b>带衰减的 Top-K sketch</b>：FNV 指纹 + {@code depth × width} 桶表 + 最小堆。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/domain/HeavyKeeper.java}
 * （1-306 行；类声明 {@code :43}；四个默认常量 {@code :46-49}；
 * {@code Item} {@code :54-55}；{@code HeapItem} {@code :57-58}；
 * 比较器 {@code :67-68}；字段 {@code :70-86}；两个构造器 {@code :89-111}；
 * {@code add} {@code :119-157}；{@code topK} {@code :165-177}；{@code topKSize} {@code :180-187}；
 * {@code updateTopK} {@code :190-217}；{@code rebuildHeap} {@code :220-227}；
 * {@code fingerprint} {@code :230-239}；{@code hash} {@code :278-290}；
 * {@code unsignedToDouble} {@code :293-295}；{@code countToScore} {@code :298-300}；
 * {@code scoreToCount} {@code :303-305}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.hot.hot.rpc.domain} →
 *       {@code com.rag.hot.domain.model}，D7 包压平 + 目标树把「领域模型」归到
 *       {@code domain/model/}）＋ <b>类名沿用</b>。方法体<b>逐字照抄</b>
 *       （含四个默认常量、比较器、两个构造器、{@code add} 的三分支、
 *       {@code updateTopK} 的三段、{@code rebuildHeap}、两个哈希函数、
 *       三个无符号换算方法）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>三条必须精确复刻的数值语义</b>逐条保留（见下方「三条必须精确复刻的语义」）；</li>
 *         <li><b>无 Lombok</b>：本类有<b>可变状态</b>（桶表 + Top-K 容器）与<b>并发写</b>
 *             ⇒ Lombok 的 {@code @Data}/{@code @Getter} 会把可变状态暴露成 public 访问器
 *             ⇒ 属于 D18 明令禁止的「DTO 层之外的 Lombok」里最危险的一类
 *             ⇒ <b>刻意手写</b>（本仓的 Lombok 规范只允许在<b>纯数据载体</b>上使用）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>为什么必须逐行迁移而不是换成 Redis ZADD</h2>
 * ZADD 是<b>精确计数</b>（无衰减、无有损），会让停止更新的老文章永久霸榜。
 * 本算法是<b>有损概率 sketch</b>：桶冲突时按 {@code decay^count} 概率让位。
 * 换成精确计数 = 热榜语义完全改变，属行为变更。
 *
 * <h2>三条必须精确复刻的语义</h2>
 * <ol>
 *   <li><b>{@code uint32} 回绕</b>：B 侧的 {@code bucket.count} 是 {@code uint32}，
 *       {@code b.count += weight} 溢出后按模 2^32 回绕。Java 用 {@code int} 存储
 *       （两补码位模式与 {@code uint32} 逐位相同），<b>加/减同样自然回绕</b>；
 *       但<b>比较必须显式走无符号</b> —— {@code Integer.compareUnsigned} /
 *       {@code Integer.toUnsignedLong}。若直接写 {@code count <= weight}，
 *       在 count ≥ 2^31 时结果与 B 侧相反。详见 {@link #unsignedToDouble(int)}。</li>
 *   <li><b>随机分支不可断言精确值</b>：{@code rand.Float64()} 属概率算法。
 *       B 侧自带的 {@code heavykeeper_test.go} 也只断言相对顺序，不断言精确计数；
 *       本实现的测试沿用同一断言强度。</li>
 *   <li><b>同 count 之间的顺序不稳定</b>：B 侧用 {@code sort.Slice}（非稳定排序）
 *       且输入来自 map 遍历序。本实现同样不承诺并列项次序。</li>
 * </ol>
 *
 * <h2>已知成本</h2>
 * {@link #updateTopK} 在 key 已在 Top-K 中时走 {@link #rebuildHeap}，
 * 是 <b>O(k) 全量重建</b>（B 侧同款）。TopK=100 时是微秒级；
 * 扩到万级应改成 {@code key→heapIndex} + {@code heap.fix}（B 侧注释自陈）。
 */
public class HeavyKeeper {

    /** B 侧 {@code heavykeeper.Config} 的默认值（{@code DefaultConfig()}），对应 {@code etc/hot*.yaml}。 */
    public static final int DEFAULT_WIDTH = 1000;
    public static final int DEFAULT_DEPTH = 5;
    public static final double DEFAULT_DECAY = 0.9;
    public static final int DEFAULT_TOP_K = 100;

    /**
     * Top-K 条目。{@code count} 是 B 侧的 {@code uint32}，Java 用 {@code int} 存同一批位。
     *
     * @param key   条目键（文章 ID）
     * @param count 桶计数（无符号语义）
     */
    public record Item(String key, int count) {
    }

    /**
     * 最小堆内部条目。
     *
     * @param key   条目键
     * @param count 桶计数（无符号语义）
     */
    private record HeapItem(String key, int count) {
    }

    /**
     * 最小堆比较器：按 count <b>无符号</b> 升序。
     *
     * <p>B 侧是 {@code func (h minHeap) Less(i, j int) bool { return h[i].count < h[j].count }}，
     * 其中 {@code count} 是 {@code uint32} ⇒ 无符号比较。Java 的 {@code int < int} 是有符号，
     * 会与 B 侧在 count ≥ 2^31 时分岔。
     */
    private static final Comparator<HeapItem> BY_COUNT_UNSIGNED =
            (a, b) -> Integer.compareUnsigned(a.count, b.count);

    private final int width;
    private final int depth;
    private final double decay;
    private final int k;

    /** {@code fingerprints[row][col]}，对应 B 侧的 {@code table[row][col].fingerprint}（uint32）。 */
    private final int[][] fingerprints;
    /** {@code counts[row][col]}，对应 B 侧的 {@code table[row][col].count}（uint32）。 */
    private final int[][] counts;

    /** 对应 B 侧的 {@code topKMap map[string]uint32}。 */
    private final Map<String, Integer> topKMap = new HashMap<>();
    /** 对应 B 侧的 {@code topKHeap *minHeap}。 */
    private final PriorityQueue<HeapItem> topKHeap = new PriorityQueue<>(BY_COUNT_UNSIGNED);

    /** 对应 B 侧 {@code hk.mu sync.Mutex}：全局串行化 sketch 内部状态。 */
    private final ReentrantLock mu = new ReentrantLock();

    /** 对应 B 侧 {@code heavykeeper.DefaultConfig()}。 */
    public HeavyKeeper() {
        this(DEFAULT_WIDTH, DEFAULT_DEPTH, DEFAULT_DECAY, DEFAULT_TOP_K);
    }

    /**
     * 对应 B 侧 {@code heavykeeper.New(cfg)}。
     *
     * @param width 桶数，B 侧 yaml {@code HeavyKeeper.Width}（1000）
     * @param depth 行数/哈希函数个数，B 侧 yaml {@code HeavyKeeper.Depth}（5）
     * @param decay 冲突时的衰减概率基数，B 侧 yaml {@code HeavyKeeper.Decay}（0.9）
     * @param topK  保留条目数，B 侧 yaml {@code HeavyKeeper.TopK}（100）
     */
    public HeavyKeeper(int width, int depth, double decay, int topK) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive, got " + width);
        }
        this.width = width;
        this.depth = depth;
        this.decay = decay;
        this.k = topK;
        this.fingerprints = new int[depth][width];
        this.counts = new int[depth][width];
    }

    /**
     * 对应 B 侧 {@code func (hk *HeavyKeeper) Add(key string, weight uint32)}。
     *
     * <p>三分支：空桶占位 / 指纹相同累加 / 冲突按 {@code decay^count} 概率让位。
     * 随后取 {@code maxCount}（只统计指纹匹配行）更新 Top-K。
     *
     * @param key    条目键
     * @param weight 权重
     */
    public void add(String key, int weight) {
        mu.lock();
        try {
            int fp = fingerprint(key);
            int maxCount = 0;

            for (int row = 0; row < depth; row++) {
                int col = hash(key, row);

                if (counts[row][col] == 0) {
                    // 空桶：占用
                    fingerprints[row][col] = fp;
                    counts[row][col] = weight;
                } else if (fingerprints[row][col] == fp) {
                    // 指纹匹配：累加权重。int 两补码回绕 == B 侧 uint32 回绕（语义保留，见类注释）
                    counts[row][col] += weight;
                } else {
                    // 冲突：以概率 decay^count 衰减
                    double decayProb = Math.pow(decay, unsignedToDouble(counts[row][col]));
                    if (ThreadLocalRandom.current().nextDouble() < decayProb) {
                        if (Integer.compareUnsigned(counts[row][col], weight) <= 0) {
                            counts[row][col] = weight;
                            fingerprints[row][col] = fp;
                        } else {
                            counts[row][col] -= weight;
                        }
                    }
                }

                if (fingerprints[row][col] == fp && Integer.compareUnsigned(counts[row][col], maxCount) > 0) {
                    maxCount = counts[row][col];
                }
            }

            updateTopK(key, maxCount);
        } finally {
            mu.unlock();
        }
    }

    /**
     * 对应 B 侧 {@code func (hk *HeavyKeeper) TopK() []Item}：按 count 降序。
     *
     * <p>⚠️ B 侧用 {@code sort.Slice}（非稳定）且输入来自 map 遍历序，
     * <b>同 count 之间次序本就不确定</b>；本实现同样不承诺并列项次序。
     *
     * @return Top-K 条目列表（降序）
     */
    public List<Item> topK() {
        mu.lock();
        try {
            List<Item> items = new ArrayList<>(topKMap.size());
            for (Map.Entry<String, Integer> e : topKMap.entrySet()) {
                items.add(new Item(e.getKey(), e.getValue()));
            }
            items.sort((a, b) -> Integer.compareUnsigned(b.count, a.count));
            return items;
        } finally {
            mu.unlock();
        }
    }

    /**
     * 当前 Top-K 条目数（测试与断言用；B 侧无对应方法，仅暴露观测点）。
     *
     * @return Top-K 条目数
     */
    public int topKSize() {
        mu.lock();
        try {
            return topKMap.size();
        } finally {
            mu.unlock();
        }
    }

    /**
     * 对应 B 侧 {@code updateTopK}。调用方必须已持 {@link #mu}。
     *
     * @param key   条目键
     * @param count 桶计数
     */
    private void updateTopK(String key, int count) {
        if (count == 0) {
            return;
        }

        // 已在 Top-K 中：更新 count
        if (topKMap.containsKey(key)) {
            topKMap.put(key, count);
            rebuildHeap();
            return;
        }

        // Top-K 未满：直接加入
        if (topKMap.size() < k) {
            topKMap.put(key, count);
            topKHeap.offer(new HeapItem(key, count));
            return;
        }

        // Top-K 已满：与堆顶比较
        HeapItem minItem = topKHeap.peek();
        if (minItem != null && Integer.compareUnsigned(count, minItem.count) > 0) {
            topKMap.remove(minItem.key);
            topKHeap.poll();
            topKMap.put(key, count);
            topKHeap.offer(new HeapItem(key, count));
        }
    }

    /** 对应 B 侧 {@code rebuildHeap}：全量重建 O(k)。 */
    private void rebuildHeap() {
        PriorityQueue<HeapItem> rebuilt = new PriorityQueue<>(Math.max(1, topKMap.size()), BY_COUNT_UNSIGNED);
        for (Map.Entry<String, Integer> e : topKMap.entrySet()) {
            rebuilt.offer(new HeapItem(e.getKey(), e.getValue()));
        }
        topKHeap.clear();
        topKHeap.addAll(rebuilt);
    }

    /**
     * 对应 B 侧 {@code fingerprint}：FNV-1a 32。
     *
     * @param key 条目键
     * @return 32 位指纹（{@code int} 存 B 侧 {@code uint32} 的位模式）
     */
    private int fingerprint(String key) {
        final int prime = 16777619;
        // FNV-1a 32 offset basis = 2166136261 = 0x811C9DC5，超过 int 上限，只能用 hex 字面量
        int h = 0x811C9DC5;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= prime; // int 溢出回绕 == B 侧 uint32 回绕
        }
        return h;
    }

    /**
     * 对应 B 侧 {@code hash}：FNV-1a 64 依次写入 {@code key} 的字节与一个 seed 字节，再 {@code % width}。
     *
     * <p><b>B 出处</b>：B 侧 {@code heavykeeper.go:185-190}
     * <pre>{@code
     * func (hk *HeavyKeeper) hash(key string, seed int) int {
     *     h := fnv.New64a()
     *     h.Write([]byte(key))
     *     h.Write([]byte{byte(seed)})
     *     return int(h.Sum64() % uint64(hk.width))   // ← uint64 取模
     * }
     * }</pre>
     *
     * <h3>为什么必须用 {@link Long#remainderUnsigned(long, long)}</h3>
     * B 侧的 {@code h.Sum64()} 是<b>无符号</b> 64 位，取模结果等于「对 2^64 取余」。
     * Java 的 {@code long} 是有符号的：FNV-64 结果最高位为 1 时 {@code h < 0}
     * ⇒ 有符号取模算出来的是 {@code Euclidean(-a, width)}，
     * 而 B 侧要的是 {@code Euclidean(2^64 - a, width)}，两者相差 {@code 2^64 mod width}
     * （width=1000 → <b>616</b>，width=7 → <b>2</b>、<b>永不为 0</b>）。
     *
     * <p>⚠️ <b>两条踩过的错路</b>，别再走：
     * <ol>
     *   <li>{@code h & 0xFFFFFFFFFFFFFFFFL}：该字面量的位模式<b>全为 1</b>（即 {@code -1L}），
     *       掩码等于什么都没做 ⇒ 负数取模得负下标 ⇒ {@code ArrayIndexOutOfBounds}。</li>
     *   <li>{@code Math.floorMod(h, width)}：同样作用在<b>有符号</b> {@code h} 上，
     *       实测结果与「有符号取模 + 补正」<b>完全相同（仍然错）</b>。</li>
     * </ol>
     * ⇒ 唯一正确的是 {@link Long#remainderUnsigned(long, long)}（正除数下即无符号余数）。
     *
     * <p><b>可验证形式</b>：B 侧 2026-10-23 双侧活体对拍 —— Go 侧用真源的<b>逐字节副本</b>
     * + 最小导出探针，Java 侧直接调用<b>生产类</b>本方法（反射）：
     * 生产口径 {@code width=1000 / depth=5 / topK=100}、405 个 key（含空串、中文、emoji、超长串）
     * ⇒ 修正前 <b>173/405 个 key 的列号与 B 侧不同、Top-100 榜单集合不一致</b>；
     * 修正后 <b>2025/2025 个列号逐条一致、Top-100 集合完全一致</b>。
     *
     * @param key  条目键
     * @param seed 行号（作为哈希种子）
     * @return 列号
     */
    private int hash(String key, int seed) {
        final long prime = 1099511628211L;
        // FNV-1a 64 offset basis = 14695981039346656037 = 0xCBF29CE484222325，超过 long 有符号上限
        long h = 0xCBF29CE484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFFL);
            h *= prime;
        }
        h ^= ((byte) seed) & 0xFFL;
        h *= prime;
        // B 侧是 uint64 取模 ⇒ Java 必须走无符号余数（width 恒为正）。
        return (int) Long.remainderUnsigned(h, (long) width);
    }

    /**
     * B 侧 {@code float64(uint32)} 的等价：无符号数值，不是 int 的符号值。
     *
     * @param uint32 无符号 32 位值（{@code int} 的位模式）
     * @return 无符号数值
     */
    private static double unsignedToDouble(int uint32) {
        return (double) Integer.toUnsignedLong(uint32);
    }

    /**
     * {@link Item#count()} 到 ZSET score 的换算，等价 B 侧 {@code float64(item.Count)}。
     *
     * @param uint32 无符号 32 位值
     * @return ZSET score
     */
    public static double countToScore(int uint32) {
        return (double) Integer.toUnsignedLong(uint32);
    }

    /**
     * ZSET score（Redis float64）到 {@code uint32} 的换算，等价 B 侧 {@code uint32(z.Score)}。
     *
     * @param score ZSET score
     * @return 无符号 32 位值
     */
    public static int scoreToCount(double score) {
        return (int) (long) score;
    }
}
