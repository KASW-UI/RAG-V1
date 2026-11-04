package com.rag.hot.messaging;

import com.rag.hot.config.HotRpcProperties;
import com.rag.hot.domain.model.HeavyKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * <p><b>{@code partitionId → HeavyKeeper} 的分区注册表</b> —— 对应 B 侧的 {@code hkMap}
 * 及其分区生命周期（{@code OnPartitionsAssigned} / {@code OnPartitionsRevoked}）。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotPartitionSketchRegistry.java}
 * （1-191 行；类声明 {@code :55-56}；字段 {@code :60-65}；构造器 {@code :67-69}；
 * {@code newHeavyKeeper} {@code :72-75}；{@code assign} {@code :86-103}；
 * {@code detach} {@code :113-128}；{@code detachWithinWriteLock} {@code :136-152}；
 * {@code heavyKeeperFor} {@code :154-161}；{@code partitionIds} {@code :164-171}；
 * {@code size} {@code :174-181}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯命名 / 定位</b>改动 ——
 *       <ul>
 *         <li>包名 {@code com.rag.hot.hot.rpc.mq} → {@code com.rag.hot.messaging}（D7 包压平）；</li>
 *         <li>{@code HeavyKeeper} 的 import 由 {@code com.rag.hot.hot.rpc.domain}
 *             → {@code com.rag.hot.domain.model}（D7）；</li>
 *         <li>{@code HotRpcProperties} 的 import 由 {@code com.rag.hot.hot.rpc.config}
 *             → {@code com.rag.hot.config}（D7）。</li>
 *       </ul>
 *       <b>方法体逐字照抄</b>（含 {@code detachWithinWriteLock} 这个 B 侧<b>零调用点</b>的方法，
 *       见下方专门说明）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>不变量<b>逐条未动</b>：每个当前持有的分区<b>恰好</b>一个 sketch；
 *             重复 assign 不回灌；revoke 后不再持有；</li>
 *         <li><b>锁的用法</b>逐条未动（锁内取引用 / 锁外做 Redis I/O）；</li>
 *         <li>「先注册进 map，再回灌」的次序逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 与 <b>Go 真源</b>的一处有意偏离：撤销路径不做「持写锁再取读锁」</h2>
 *
 * <p>⚠️ <b>必须区分两个「B 侧」</b>（否则这段注释会被读反）：
 * <ul>
 *   <li><b>Go 真源</b>（{@code service/hot/rpc/internal/mqs/hot_handler.go}）：撤销路径是
 *       「持写锁 → 调 {@code syncToRedis}（内含 {@code RLock}）」⇒ <b>必然自死锁</b>；</li>
 *   <li><b>B 侧 Java</b>（{@code service/hot/hot-rpc/.../mq/HotPartitionSketchRegistry.java}）：
 *       <b>已经是</b>「锁内取引用 → 锁外落盘」的形态 ⇒ ⇒ <b>本实现与 B 侧 Java 逐字一致</b>
 *       ⇒ ⇒ 下面的偏离是<b>相对 Go 真源</b>的，不是相对 B 侧 Java 的。</li>
 * </ul>
 *
 * Go 真源的撤销路径是：
 * <pre>{@code
 * func (h *HotHandler) OnPartitionsRevoked(partitions []int32) {
 *     h.hkMu.Lock()            // 持写锁
 *     defer h.hkMu.Unlock()
 *     h.syncToRedis(ctx, pid)  // → h.hkMu.RLock()  ← 死锁
 *     delete(h.hkMap, pid)
 * }
 * }</pre>
 * B 侧的 {@code sync.RWMutex} <b>不可重入</b>：持写锁时 {@code readerCount} 为负，
 * 此时同 goroutine 调 {@code RLock} 会永久阻塞；{@code defer Unlock} 因此永不执行。
 * ⇒ <b>B 侧第一次 rebalance 撤销分区就必然死锁</b>，{@code delete} 永不执行，
 * 分区快照<b>从未真正落盘过</b>。
 * 对照：{@code OnPartitionsAssigned} 持写锁调用的 {@code loadFromRedis} <b>不取任何锁</b>，
 * 所以只有撤销这一侧有问题。
 *
 * <p><b>为什么不能「照抄」这个行为</b>：Java 的 {@link ReentrantReadWriteLock} <b>允许</b>
 * 写锁持有者再取读锁（官方支持的 write→read 降级）。若直接 1:1 直译，
 * B 侧的死锁会<b>悄悄变成能正常跑</b>，两套实现行为不一致且没有任何告警。
 * 而刻意在 Java 里写死锁，既不可能也让 L2 语义（rebalance 落盘）彻底落空。
 * ⇒ 本实现采用 B 侧另外两个调用点（{@code periodicSync} / {@code consumeMessage}）
 * 已经用过的正确形态：<b>锁内取引用 → 锁外做 Redis I/O</b>。
 *
 * <p>具体做法：{@link #detach(Collection)} 在写锁内把 sketch <b>原子移除并返回引用</b>，
 * 由调用方在<b>锁外</b>落盘。这样既消除了自死锁，也顺带保证「撤销后不再有新事件进入该 sketch」。
 *
 * <h2>另一处收敛（B 侧既有形态）</h2>
 * B 侧的 {@code OnPartitionsAssigned} 在<b>持写锁期间</b>做 {@code loadFromRedis} 的 Redis I/O，
 * 会阻塞所有 {@code RLock} 读者。本实现的 {@link #assign(Collection)} 只在锁内建 sketch，
 * 回灌由调用方在锁外完成 —— 这是 B 侧另外两个调用点已有的形态，<b>不改变业务可观察结果</b>。
 */
@Component
public class HotPartitionSketchRegistry {

    private static final Logger log = LoggerFactory.getLogger(HotPartitionSketchRegistry.class);

    private final Map<Integer, HeavyKeeper> hkMap = new LinkedHashMap<>();

    /** 对应 B 侧的 {@code hkMu sync.RWMutex}。 */
    private final ReentrantReadWriteLock hkMu = new ReentrantReadWriteLock();

    private final HotRpcProperties props;

    /**
     * 构造器注入。
     *
     * @param props hot 域的 rpc 段配置（提供 sketch 的算法参数）
     */
    public HotPartitionSketchRegistry(HotRpcProperties props) {
        this.props = props;
    }

    /** 按 B 侧 {@code etc/hot*.yaml} 的 {@code HeavyKeeper} 四参数新建 sketch。 */
    private HeavyKeeper newHeavyKeeper() {
        HotRpcProperties.HeavyKeeper cfg = props.getHeavyKeeper();
        return new HeavyKeeper(cfg.getWidth(), cfg.getDepth(), cfg.getDecay(), cfg.getTopK());
    }

    /**
     * <p><b>对应 B 侧 {@code OnPartitionsAssigned} 的「建 sketch」部分。</b>
     *
     * <p>返回<b>本次新建</b>的 {@code partitionId → sketch}，供调用方在锁外做
     * {@link HotRedisSnapshotStore#loadInto} 回灌。已存在的分区被跳过
     * （B 侧 {@code :118-120} 的 {@code continue}，防止重复回灌）。
     *
     * <p>注意顺序与 B 侧一致：<b>先注册进 map，再回灌</b> ⇒ 回灌期间到达的事件仍会进同一个 sketch。
     *
     * @param partitions 获得的分区号
     * @return 本次新建的分区 → sketch
     */
    public Map<Integer, HeavyKeeper> assign(Collection<Integer> partitions) {
        Map<Integer, HeavyKeeper> created = new LinkedHashMap<>();
        hkMu.writeLock().lock();
        try {
            for (Integer pid : partitions) {
                if (hkMap.containsKey(pid)) {
                    continue;
                }
                HeavyKeeper hk = newHeavyKeeper();
                hkMap.put(pid, hk);
                created.put(pid, hk);
                log.info("[Hot] assigned partition {}", pid);
            }
        } finally {
            hkMu.writeLock().unlock();
        }
        return created;
    }

    /**
     * <p><b>对应 B 侧 {@code OnPartitionsRevoked} 的「落盘 + 丢弃」部分。</b>
     *
     * <p><b>写锁内只做「取出引用 + 从 map 移除」，落盘由调用方在锁外完成</b>
     * —— 理由与偏差论证见类注释。
     *
     * @param partitions 撤销的分区号
     * @return 被摘下的分区 → sketch，调用方须在锁外依次落盘
     */
    public Map<Integer, HeavyKeeper> detach(Collection<Integer> partitions) {
        Map<Integer, HeavyKeeper> detached = new LinkedHashMap<>();
        hkMu.writeLock().lock();
        try {
            for (Integer pid : partitions) {
                HeavyKeeper hk = hkMap.remove(pid);
                if (hk != null) {
                    detached.put(pid, hk);
                }
                log.info("[Hot] revoked partition {}", pid);
            }
        } finally {
            hkMu.writeLock().unlock();
        }
        return detached;
    }

    /**
     * <p><b>⚠️ 零调用点的方法</b>（B 侧 Java 全仓 grep：只有它自己的声明命中；
     * 本仓同样<b>只有声明命中</b> ⇒ ⇒ <b>两侧都不调用它</b>）。
     *
     * <p>本仓<b>逐字保留</b>它（1:1 抄写），<b>但刻意<b>不</b>调用</b>。
     *
     * <h2>⚠️ 它<b>不构成死锁</b>（措辞订正 · 2026-11-05）</h2>
     * <p>类注释里论证的「持写锁再取读锁 ⇒ 必然死锁」是<b>Go 真源</b>的形态
     * （{@code sync.RWMutex} 不可重入）；
     * 而 Java 的 {@link ReentrantReadWriteLock} <b>允许</b>写锁持有者再取读锁
     * （官方支持的 write→read 降级）⇒ ⇒ <b>本方法在 Java 里不会死锁</b>
     * ⇒ ⇒ 它的真实代价是<b>另一件事</b>：<b>持写锁做 Redis I/O ⇒ 阻塞所有读者</b>
     * （消费路径取 sketch、{@code periodicSync} 的取分区循环都会被 Redis 延迟拖住）。
     *
     * <p>⇒ 保留它的理由只有一个：让「B 侧 Java 有这个方法」这件事可对照（D16）。
     * ⇒ <b>要落盘请用 {@link #detach(Collection)}</b>（锁内取引用 → 锁外做 Redis I/O）。
     * ⇒ 该约束已由 {@code com.rag.hot.messaging.HotPartitionSketchRegistryLockTest}
     * 的「flush 阻塞期间读锁仍然可用」一条钉住。
     *
     * @param partitions 撤销的分区号
     * @param store      Redis 快照存储
     * @return 被摘下的分区 → sketch
     */
    public Map<Integer, HeavyKeeper> detachWithinWriteLock(Collection<Integer> partitions,
                                                           HotRedisSnapshotStore store) {
        Map<Integer, HeavyKeeper> detached = new LinkedHashMap<>();
        hkMu.writeLock().lock();
        try {
            for (Integer pid : partitions) {
                HeavyKeeper hk = hkMap.remove(pid);
                if (hk != null) {
                    detached.put(pid, hk);
                    store.flush(pid, hk);
                }
            }
        } finally {
            hkMu.writeLock().unlock();
        }
        return detached;
    }

    /**
     * <p><b>取分区 sketch</b>，对应 B 侧消费路径
     * {@code hkMu.RLock(); hk := hkMap[pid]; hkMu.RUnlock()}。
     *
     * @param partitionId 分区号
     * @return sketch 引用；分区不在 map 时返回 {@code null}（B 侧即「静默 return nil」）
     */
    public HeavyKeeper heavyKeeperFor(int partitionId) {
        hkMu.readLock().lock();
        try {
            return hkMap.get(partitionId);
        } finally {
            hkMu.readLock().unlock();
        }
    }

    /**
     * <b>当前持有的分区快照</b>，对应 B 侧 {@code periodicSync} 的取 key 循环。
     *
     * @return 分区号列表
     */
    public List<Integer> partitionIds() {
        hkMu.readLock().lock();
        try {
            return new ArrayList<>(hkMap.keySet());
        } finally {
            hkMu.readLock().unlock();
        }
    }

    /**
     * <b>当前持有的分区数</b>（测试观测用）。
     *
     * @return 分区数
     */
    public int size() {
        hkMu.readLock().lock();
        try {
            return hkMap.size();
        } finally {
            hkMu.readLock().unlock();
        }
    }
}
