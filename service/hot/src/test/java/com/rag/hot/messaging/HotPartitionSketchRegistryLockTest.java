package com.rag.hot.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.hot.config.HotRpcProperties;
import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.metrics.HotRpcMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * <p><b>分区生命周期 + 「Redis 落盘发生在写锁之外」</b>的守门测试。</p>
 *
 * <h2>🔴 为什么这组判据必须有</h2>
 * <p>B 侧有两份测试专门守这一块，而 C 侧<b>此前一份都没有</b> ⇒
 * 「将来有人把落盘挪回锁内」在 C 侧<b>没有任何测试会红</b>（B 侧会红）
 * ⇒ 这是<b>守护网缺口</b>，不是行为漂移（C 侧实现本身与 B 侧逐字节相同）。</p>
 *
 * <h2>为什么「会不会卡住」不能当判据</h2>
 * <p>Go 的 {@code sync.RWMutex} <b>不可重入</b> ⇒ 持写锁再取读锁<b>必然死锁</b>；
 * 而 Java 的 {@code ReentrantReadWriteLock} <b>允许</b>写锁持有者再取读锁
 * ⇒ ⇒ 即便把落盘挪回锁内，<b>也不会卡住</b> ⇒ 「会不会卡住」无法判定这件事有没有发生。</p>
 *
 * <p><b>真正可观察的差别是「锁的持有时长」</b>：若落盘在写锁内，Redis 一慢就会把
 * <b>所有读者</b>（消费路径取 sketch、{@code periodicSync} 的取分区循环）一起阻塞。
 * ⇒ 本类用一个被 latch 卡住的 {@code flush} 制造「慢 Redis」，断言读者<b>仍能立即返回</b>
 * ⇒ ⇒ 落盘一旦挪回锁内，第 7 条用例会超时失败。</p>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-rpc/src/test/java/com/rag/hot/hot/rpc/mq/
 *       HotPartitionSketchRegistryDeadlockTest.java}（6 例，1:1 移植）；</li>
 *   <li>B 侧同包 {@code HotRevokeFlushOutsideLockTest.java}（1 例，1:1 移植）；</li>
 *   <li>B 侧主代码 {@code .../mq/HotPartitionSketchRegistry.java}
 *       （{@code assign} / {@code detach} / {@code detachWithinWriteLock}）；</li>
 *   <li>B 侧主代码 {@code .../mq/HotEventConsumer.java}（{@code onPartitionsRevoked}
 *       ⇒ 锁外落盘）。</li>
 * </ul>
 */
class HotPartitionSketchRegistryLockTest {

    private static final String TOPIC = "article-hot-events";

    private HotRpcProperties props;
    private HotPartitionSketchRegistry registry;

    @BeforeEach
    void setUp() {
        props = new HotRpcProperties();
        props.getKafka().setTopic(TOPIC);
        registry = new HotPartitionSketchRegistry(props);
    }

    // ==================================================================
    // ① 分区生命周期（B 侧 HotPartitionSketchRegistryDeadlockTest 1:1 移植）
    // ==================================================================

    @Test
    @Timeout(10)
    @DisplayName("assign → detach 全程不死锁，且 detach 原子摘除分区")
    void assignThenDetachDoesNotDeadlock() {
        registry.assign(List.of(0, 1, 2));
        assertThat(registry.size()).isEqualTo(3);

        Map<Integer, HeavyKeeper> detached = registry.detach(List.of(1));

        assertThat(detached).containsOnlyKeys(1);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.heavyKeeperFor(1)).isNull();
    }

    @Test
    @Timeout(10)
    @DisplayName("持写锁的同时取 sketch 引用不会像 Go 那样永久阻塞（write→read 降级是官方支持的）")
    void heavyKeeperLookupInsideDetachFlowDoesNotBlock() {
        registry.assign(List.of(0));

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            Map<Integer, HeavyKeeper> detached = registry.detach(List.of(0));
            acquired.set(detached.containsKey(0));
            done.countDown();
        });
        worker.start();

        assertThatCode(() -> {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("detach 未在 5s 内完成 —— 疑似引入 Go 侧的锁升级死锁");
            }
        }).doesNotThrowAnyException();
        assertThat(acquired).isTrue();

        // 撤销之后所有读者仍能立即拿到锁（Go 侧此刻会被永久卡死）
        assertThat(registry.heavyKeeperFor(0)).isNull();
        assertThat(registry.partitionIds()).isEmpty();
    }

    @Test
    @Timeout(10)
    @DisplayName("detach 不持有写锁：并发读者不会被阻塞（落盘发生在锁外）")
    void readersAreNotBlockedAfterDetach() {
        registry.assign(List.of(0, 1));

        registry.detach(List.of(0));

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            registry.partitionIds();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("assign 幂等：已存在的分区不会被重建（防止重复回灌）")
    void assignIsIdempotentPerPartition() {
        Map<Integer, HeavyKeeper> first = registry.assign(List.of(0, 1));
        assertThat(first).containsOnlyKeys(0, 1);

        Map<Integer, HeavyKeeper> second = registry.assign(List.of(0, 1));
        assertThat(second).as("已存在的分区不应重新建 sketch").isEmpty();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("detach 不存在的分区是安全的空操作（对应 Go 的 delete 语义）")
    void detachUnknownPartitionIsNoop() {
        registry.assign(List.of(0));

        assertThat(registry.detach(List.of(99))).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("sketch 按配置的四参数创建（width/depth/decay/topK）")
    void newSketchUsesConfiguredParameters() {
        props.getHeavyKeeper().setWidth(64);
        props.getHeavyKeeper().setDepth(3);
        props.getHeavyKeeper().setDecay(0.5);
        props.getHeavyKeeper().setTopK(7);

        Map<Integer, HeavyKeeper> created = registry.assign(List.of(0));

        HeavyKeeper hk = created.get(0);
        assertThat(hk).isNotNull();
        for (int i = 0; i < 20; i++) {
            hk.add("k" + i, 1);
        }
        assertThat(hk.topKSize()).isLessThanOrEqualTo(7);
    }

    // ==================================================================
    // ② 「落盘在写锁之外」（B 侧 HotRevokeFlushOutsideLockTest 1:1 移植）
    // ==================================================================

    @Test
    @Timeout(20)
    @DisplayName("flush 阻塞期间读锁仍然可用 ⇒ 落盘确实在写锁之外")
    void readersAreNotBlockedWhileRevokeFlushes() throws Exception {
        HotRpcMetrics metrics = new HotRpcMetrics(new SimpleMeterRegistry());

        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);

        // 模拟「慢 Redis」：flush 阻塞在 latch 上
        HotRedisSnapshotStore slowStore = Mockito.mock(HotRedisSnapshotStore.class);
        doAnswer(inv -> {
            flushStarted.countDown();
            releaseFlush.await(10, TimeUnit.SECONDS);
            return null;
        }).when(slowStore).flush(anyInt(), any(HeavyKeeper.class));

        HotEventConsumer consumer = new HotEventConsumer(
                new ObjectMapper(), props, metrics, registry, slowStore, Runnable::run,
                ObservationRegistry.NOOP);
        consumer.onPartitionsAssigned(List.of(0));
        assertThat(registry.size()).isEqualTo(1);

        Thread revoke = new Thread(() -> consumer.onPartitionsRevoked(List.of(0)));
        revoke.start();

        assertThat(flushStarted.await(10, TimeUnit.SECONDS))
                .as("撤销线程应已进入 flush").isTrue();

        // flush 仍被卡住 —— 此时若写锁未释放，所有读锁调用都会阻塞
        long startNanos = System.nanoTime();
        List<Integer> ids = registry.partitionIds();
        HeavyKeeper hk = registry.heavyKeeperFor(0);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(elapsed)
                .as("落盘在写锁内 ⇒ 读者被 Redis 延迟阻塞")
                .isLessThan(Duration.ofSeconds(2));
        assertThat(ids).isEmpty();
        assertThat(hk).isNull();

        releaseFlush.countDown();
        revoke.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(revoke.isAlive()).isFalse();
    }
}
