package com.rag.hot.messaging;

import com.rag.hot.support.HotRealStackTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        // ⚠️ 分数必须**给高**：本用例用「成员在不在合并结果里」来断言「哪几片参与了合并」，
        //    而合并结果会按分数降序截断到 100 条 ⇒ 若这两条的分数落在截断边缘，
        //    它们可能被切掉 ⇒ ⇒ 用例会**假红**（红的原因不是「参与片数不对」）。
        //    （变异验证实测：把合并排序改成升序时本用例会随之误红。）
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0), "in0", 100.0d);
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(3), "in3", 100.0d);

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

    // ==================================================================
    // ③ 截断层：>100 条 ⇒ 只留分数最高的 100 条
    // ==================================================================

    /**
     * 🔴 <b>截断层守门</b>：合并结果 &gt;100 条时，<b>只留分数最高的 100 条</b>。
     *
     * <h2>为什么必须有这条</h2>
     * <p>{@code MERGED_LIMIT = 100} 与「按分数降序排序」这两处此前<b>都没有任何测试覆盖</b>：
     * 既有用例的分片条目最多 4 条 ⇒ <b>永远走不到截断分支</b> ⇒
     * 这两处<b>改错了也没人叫</b>。
     *
     * <h2>⚠️ 这两处必须一起测</h2>
     * <p>它们在 &gt;100 条时<b>耦合</b>：排序方向若被改反（升序），截断会留下
     * <b>分数最低</b>的 100 条，而「条数」断言（100 vs 101）<b>抓不到</b>这种错误
     * ⇒ ⇒ 必须断言<b>留存集合的边界</b>（最低分 / 最高分）。
     *
     * <h2>B 出处</h2>
     * <p>B 侧 {@code .../mq/HotRedisLuaGateTest.java:428-450}
     * （B 侧注释自陈「既有用例最多 4 条 ⇒ 永远走不到截断分支 ⇒ 排序方向若被改反，断言会假绿」）
     * ⇒ 本用例为 1:1 移植。</p>
     */
    @Test
    @DisplayName("合并：>100 条 ⇒ 只留分数最高的 100 条（降序截断，不是升序）")
    void mergeTruncatesToThe100HighestScores() {
        // 150 条，分数 1..150，全放 p0（避免跨分片求和干扰边界断言）
        for (int i = 1; i <= 150; i++) {
            redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(0),
                    String.format("a%03d", i), (double) i);
        }

        scheduler.periodicMerge();

        Set<ZSetOperations.TypedTuple<String>> merged =
                redis.opsForZSet().reverseRangeWithScores(
                        HotRedisSnapshotStore.HOT_ARTICLES_KEY, 0, -1);
        assertNotNull(merged);

        assertEquals(100, merged.size(), "合并结果必须截断到 100 条");

        // ★ 关键判定：留存的必须是**分数最高**的那批（150 条里取 51..150）
        //   只断言「条数 = 100」是不够的 —— 排序方向被改反时条数仍是 100，
        //   但留下的是**分数最低**的 1..100 ⇒ 必须断言边界分数
        double minScore = merged.stream()
                .mapToDouble(ZSetOperations.TypedTuple::getScore).min().orElseThrow();
        double maxScore = merged.stream()
                .mapToDouble(ZSetOperations.TypedTuple::getScore).max().orElseThrow();
        assertEquals(51d, minScore, "留存的最低分必须是 51（= 150-100+1）⇒ 留的是最高的一批");
        assertEquals(150d, maxScore, "留存的最高分必须是 150");
    }

    // ==================================================================
    // ④ 看门狗续期 Lua（compare-and-EXPIRE）
    // ==================================================================

    /**
     * 🔴 <b>看门狗续期脚本的直接判据</b>。
     *
     * <h2>为什么必须有这条</h2>
     * <p>锁 TTL 是 10s，而看门狗每 3s 续期一次 ⇒ ⇒ <b>续期脚本一旦写错，锁会在合并中途过期</b>
     * ⇒ ⇒ 两个实例同时认为自己持锁 ⇒ ⇒ <b>并发合并 ⇒ 大 key 被交叉覆盖</b>。
     * <p>此前 C 侧只测了抢锁 / 放锁的 CAS，<b>续期脚本本身零覆盖</b>。</p>
     *
     * <h2>B 出处</h2>
     * <p>B 侧 {@code .../mq/HotRedisLuaGateTest.java:176-193}
     * （「锁值匹配 ⇒ 续期成功；不匹配 ⇒ 返回 0 且不动锁」）⇒ 本用例为 1:1 移植。</p>
     */
    @Test
    @DisplayName("看门狗续期：锁值匹配 ⇒ 续期成功；不匹配 ⇒ 返回 0 且不动锁")
    void renewScriptOnlyRenewsWhenLockValueMatches() {
        String lock = HotMergeScheduler.MERGE_LOCK_KEY;
        redis.opsForValue().set(lock, "instance-A", Duration.ofSeconds(10));

        Long mismatch = redis.execute(HotMergeScheduler.RENEW_SCRIPT, List.of(lock),
                "instance-B", String.valueOf(HotMergeScheduler.LOCK_TTL_SECONDS));
        assertEquals(0L, mismatch, "compare-and-EXPIRE：值不匹配 ⇒ 返回 0");
        assertEquals("instance-A", redis.opsForValue().get(lock));

        Long matched = redis.execute(HotMergeScheduler.RENEW_SCRIPT, List.of(lock),
                "instance-A", String.valueOf(HotMergeScheduler.LOCK_TTL_SECONDS));
        assertEquals(1L, matched, "compare-and-EXPIRE：值匹配 ⇒ 返回 EXPIRE 的结果 1");
        assertEquals("instance-A", redis.opsForValue().get(lock));

        Long ttl = redis.getExpire(lock, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= HotMergeScheduler.LOCK_TTL_SECONDS,
                "续期后的 TTL 必须仍是 10s（实测 " + ttl + "）");
    }
}
