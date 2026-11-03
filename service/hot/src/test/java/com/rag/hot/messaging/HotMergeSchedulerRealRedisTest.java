package com.rag.hot.messaging;

import com.rag.hot.support.HotRealStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>合并调度器（分布式锁 + 跨分片求和 + 3 段 Lua）的真 Redis 测试</b>。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotMergeScheduler.java}
 * （锁协议 {@code :285-336} · 合并 {@code :356-415} · 三个 Lua 脚本 {@code :78-106}）。
 *
 * <p><b>⚠️ 为什么必须真 Redis</b>：三个 Lua 脚本（续期 / 释放 / 改名）都是
 * <b>CAS 语义</b>（{@code GET lock == instanceID} 才动作）
 * ⇒ ⇒ mock 掉就验不到「锁被别家持有时合并必须中止」这条<b>分布式正确性</b>判据。
 */
class HotMergeSchedulerRealRedisTest extends HotRealStackTestBase {

    @Autowired
    private HotMergeScheduler scheduler;

    @BeforeEach
    void clean() {
        redis.delete(HotMergeScheduler.MERGE_LOCK_KEY);
        redis.delete(HotRedisSnapshotStore.HOT_ARTICLES_KEY);
        redis.delete(HotRedisSnapshotStore.MERGE_SHADOW_KEY);
        for (int i = 0; i < 3; i++) {
            redis.delete(HotRedisSnapshotStore.partitionKey(i));
            redis.delete(HotRedisSnapshotStore.partitionShadowKey(i));
        }
    }

    // ==================================================================
    // ① 锁协议
    // ==================================================================

    @Test
    @DisplayName("锁：抢到 ⇒ hot:merge:lock 存在、value = instanceID、TTL = 10s")
    void acquireLockSetsKeyWithTtl() {
        assertTrue(scheduler.acquireLockWithWatchDog());

        String value = redis.opsForValue().get(HotMergeScheduler.MERGE_LOCK_KEY);
        assertNotNull(value);
        assertEquals(scheduler.instanceId(), value);
        Long ttl = redis.getExpire(HotMergeScheduler.MERGE_LOCK_KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 10, "锁 TTL 必须是 10s（实测 " + ttl + "）");

        scheduler.releaseLock();
    }

    @Test
    @DisplayName("锁：同一实例重复抢 ⇒ 第二次抢不到（抢不到就跳过本轮，不排队）")
    void secondAcquireFails() {
        assertTrue(scheduler.acquireLockWithWatchDog());
        assertFalse(scheduler.acquireLockWithWatchDog(),
                "锁已被自己持有 ⇒ 第二次抢不到");
        scheduler.releaseLock();
    }

    @Test
    @DisplayName("锁：释放 ⇒ key 消失（compare-and-DEL 的 CAS 生效）")
    void releaseRemovesLock() {
        scheduler.acquireLockWithWatchDog();
        assertTrue(redis.hasKey(HotMergeScheduler.MERGE_LOCK_KEY));

        scheduler.releaseLock();

        assertFalse(redis.hasKey(HotMergeScheduler.MERGE_LOCK_KEY), "释放后锁 key 必须消失");
        assertFalse(scheduler.lockHeld());
    }

    @Test
    @DisplayName("锁：释放是 CAS —— 锁的 value 不是自己时**不得**删掉别家的锁")
    void releaseDoesNotStealForeignLock() {
        redis.opsForValue().set(HotMergeScheduler.MERGE_LOCK_KEY, "someone-else");

        scheduler.releaseLock();

        assertEquals("someone-else", redis.opsForValue().get(HotMergeScheduler.MERGE_LOCK_KEY),
                "CAS 释放不得删掉别家的锁");
    }

    // ==================================================================
    // ② 合并
    // ==================================================================

    @Test
    @DisplayName("合并：三个分片按 member 求和 ⇒ hot:articles，TTL = Interaction.TTL(3600)")
    void mergeSumsAcrossPartitions() {
        // p0: a=1, b=10
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0), "a", 1.0d);
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0), "b", 10.0d);
        // p1: a=2, c=5
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(1), "a", 2.0d);
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(1), "c", 5.0d);
        // p2: a=3
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(2), "a", 3.0d);

        scheduler.periodicMerge();

        String merged = HotRedisSnapshotStore.HOT_ARTICLES_KEY;
        assertTrue(redis.hasKey(merged), "合并结果必须落到 hot:articles");
        // a = 1 + 2 + 3 = 6
        assertEquals(6.0d, redis.opsForZSet().score(merged, "a"));
        assertEquals(10.0d, redis.opsForZSet().score(merged, "b"));
        assertEquals(5.0d, redis.opsForZSet().score(merged, "c"));

        // 大 key 的 TTL = Interaction.TTL（3600），**不是**分片的 300
        Long ttl = redis.getExpire(merged);
        assertNotNull(ttl);
        assertTrue(ttl > 300 && ttl <= 3600, "大 key TTL 必须是 3600（实测 " + ttl + "）");

        // shadow 必须被改名掉
        assertFalse(redis.hasKey(HotRedisSnapshotStore.MERGE_SHADOW_KEY));

        // 合并完必须释放锁
        assertFalse(redis.hasKey(HotMergeScheduler.MERGE_LOCK_KEY));
    }

    @Test
    @DisplayName("合并：结果为空 ⇒ **连 shadow 都不写**")
    void mergeWithNoDataWritesNothing() {
        scheduler.periodicMerge();

        assertFalse(redis.hasKey(HotRedisSnapshotStore.HOT_ARTICLES_KEY));
        assertFalse(redis.hasKey(HotRedisSnapshotStore.MERGE_SHADOW_KEY));
    }

    @Test
    @DisplayName("合并：锁被别家持有 ⇒ 改名脚本的 CAS 失败 ⇒ hot:articles 不被写")
    void mergeAbortsWhenLockIsHeldByAnother() {
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0), "a", 1.0d);
        // 抢占失败 ⇒ periodicMerge 直接 return（不排队）
        redis.opsForValue().set(HotMergeScheduler.MERGE_LOCK_KEY, "someone-else");

        scheduler.periodicMerge();

        assertFalse(redis.hasKey(HotRedisSnapshotStore.HOT_ARTICLES_KEY),
                "抢不到锁 ⇒ 本轮合并必须整体跳过");
        // 不得动别家的锁
        assertEquals("someone-else", redis.opsForValue().get(HotMergeScheduler.MERGE_LOCK_KEY));
    }

    @Test
    @DisplayName("合并：只读前 N 个分片（PartitionCount=3），第 4 片不参与")
    void mergeReadsOnlyConfiguredPartitionCount() {
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0), "in0", 1.0d);
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(3), "in3", 1.0d);

        scheduler.periodicMerge();

        Set<String> members = redis.opsForZSet().range(
                HotRedisSnapshotStore.HOT_ARTICLES_KEY, 0, -1);
        assertNotNull(members);
        assertTrue(members.contains("in0"), "前 3 片必须参与合并");
        assertFalse(members.contains("in3"), "第 4 片不在 PartitionCount 内 ⇒ 不参与");
    }

    @Test
    @DisplayName("合并：同 member 在多片出现 ⇒ 分数**相加**（不是取 max）")
    void mergeAddsScoresOfSameMember() {
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0), "dup", 2.0d);
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(1), "dup", 3.0d);

        scheduler.periodicMerge();

        assertEquals(5.0d, redis.opsForZSet().score(HotRedisSnapshotStore.HOT_ARTICLES_KEY, "dup"));
    }
}
