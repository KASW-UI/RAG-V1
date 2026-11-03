package com.rag.hot.messaging;

import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.support.HotRealStackTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>分片快照 + Lua 脚本的真 Redis 测试</b>。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotRedisSnapshotStore.java}
 * （Lua 脚本 {@code :82-89} · {@code flush} {@code :130-183} · {@code loadInto} {@code :202-231}）。
 *
 * <p><b>⚠️ 为什么必须真 Redis</b>：本类验的是 <b>Lua 脚本的真实语义</b>
 * （{@code EXISTS} 守卫查的是 <b>KEYS[1]=shadow</b> 而不是目标分片 key、
 * {@code RENAME} 的原子改名、{@code EXPIRE 300} 的<b>硬编码</b> TTL）
 * ⇒ ⇒ mock 掉 {@code StringRedisTemplate} 只能证明「字符串被解析了」，证不了脚本对
 * ⇒ ⇒ <b>假绿</b>（AGENTS.md §3.3）。
 */
class HotRedisSnapshotStoreRealRedisTest extends HotRealStackTestBase {

    private static final int PARTITION = 0;

    @Autowired
    private HotRedisSnapshotStore store;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        redis.delete(HotRedisSnapshotStore.partitionKey(PARTITION));
        redis.delete(HotRedisSnapshotStore.partitionShadowKey(PARTITION));
    }

    // ==================================================================
    // ① flush：写分片 + 改名 + TTL
    // ==================================================================

    @Test
    @DisplayName("flush：分片 key 落盘，TTL 必须是**硬编码的 300**（不是 Interaction.TTL）")
    void flushWritesPartitionWithHardcodedTtl() {
        HeavyKeeper hk = new HeavyKeeper(1000, 5, 0.9, 100);
        hk.add("a1", 3);
        hk.add("a2", 1);

        long successBefore = redisSyncCount("success");
        store.flush(PARTITION, hk);

        String partitionKey = HotRedisSnapshotStore.partitionKey(PARTITION);
        assertNotNull(redis.opsForZSet().score(partitionKey, "a1"), "分片 key 必须落盘");
        assertEquals(3.0d, redis.opsForZSet().score(partitionKey, "a1"));
        assertEquals(1.0d, redis.opsForZSet().score(partitionKey, "a2"));

        // ⚠️ 300 是**写死在 Lua 里**的（B 侧 hot_handler.go:232）
        //    ⇒ 不是 Interaction.TTL(3600) ⇒ 这两个过期时间**不可统一**。
        Long ttl = redis.getExpire(partitionKey);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300, "分片 TTL 必须是 300（实测 " + ttl + "）");

        // 改名后 shadow 必须消失（Lua 的 RENAME 是原子的）
        assertFalse(redis.hasKey(HotRedisSnapshotStore.partitionShadowKey(PARTITION)),
                "shadow 必须已被改名掉");

        assertEquals(successBefore + 1, redisSyncCount("success"));
    }

    @Test
    @DisplayName("flush：**首次快照不会被丢**（EXISTS 守卫查的是 KEYS[1]=shadow）")
    void firstSnapshotIsNotLost() {
        HeavyKeeper hk = new HeavyKeeper(1000, 5, 0.9, 100);
        hk.add("first", 7);

        store.flush(PARTITION, hk);

        // ⚠️ 守卫若被改成检查 KEYS[2]（目标分片 key）⇒ 首次 flush 返回 0 ⇒
        //    分片 key 根本不会出现 ⇒ 本断言立刻转红 ⇒ 这条判据**有牙齿**。
        assertTrue(redis.hasKey(HotRedisSnapshotStore.partitionKey(PARTITION)),
                "首次快照必须落盘（守卫查的是 shadow，不是目标 key）");
        assertEquals(7.0d, redis.opsForZSet().score(
                HotRedisSnapshotStore.partitionKey(PARTITION), "first"));
    }

    @Test
    @DisplayName("flush：TopK 为空 ⇒ **不写**（旧 key 保持陈旧），且不打 success")
    void flushWithEmptySketchDoesNotWrite() {
        HeavyKeeper hk = new HeavyKeeper(1000, 5, 0.9, 100);
        // 不 add 任何 key ⇒ TopK 为空

        long successBefore = redisSyncCount("success");
        store.flush(PARTITION, hk);

        assertFalse(redis.hasKey(HotRedisSnapshotStore.partitionKey(PARTITION)),
                "TopK 为空时不得写分片 key");
        assertEquals(successBefore, redisSyncCount("success"));
    }

    @Test
    @DisplayName("flush：sketch 为 null ⇒ 直接返回（不抛）")
    void flushWithNullSketchIsNoop() {
        store.flush(PARTITION, null);
        assertFalse(redis.hasKey(HotRedisSnapshotStore.partitionKey(PARTITION)));
    }

    // ==================================================================
    // ② loadInto：回灌
    // ==================================================================

    @Test
    @DisplayName("loadInto：从分片 key 回灌进 sketch（权重 = 合并后的累计分数）")
    void loadIntoRestoresSketch() {
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(PARTITION), "r1", 9.0d);
        redis.opsForZSet().add(HotRedisSnapshotStore.partitionKey(PARTITION), "r2", 4.0d);

        HeavyKeeper hk = new HeavyKeeper(1000, 5, 0.9, 100);
        store.loadInto(PARTITION, hk);

        java.util.List<HeavyKeeper.Item> top = hk.topK();
        assertEquals(2, top.size());
        HeavyKeeper.Item first = top.get(0);
        HeavyKeeper.Item second = top.get(1);
        assertEquals("r1", first.key());
        assertEquals(9, first.count());
        assertEquals("r2", second.key());
        assertEquals(4, second.count());
    }

    @Test
    @DisplayName("loadInto：key 不存在 ⇒ 不抛、sketch 保持空")
    void loadIntoOnMissingKeyIsNoop() {
        HeavyKeeper hk = new HeavyKeeper(1000, 5, 0.9, 100);
        store.loadInto(PARTITION, hk);
        assertEquals(0, hk.topKSize());
    }

    // ==================================================================
    // ③ key 名
    // ==================================================================

    @Test
    @DisplayName("Redis keyspace 的 4 个名字必须逐字一致")
    void keyspaceNamesArePinned() {
        assertEquals("hot:articles", HotRedisSnapshotStore.HOT_ARTICLES_KEY);
        assertEquals("hot:articles:shadow", HotRedisSnapshotStore.MERGE_SHADOW_KEY);
        assertEquals("hot:articles:p0", HotRedisSnapshotStore.partitionKey(0));
        assertEquals("hot:articles:p3:shadow", HotRedisSnapshotStore.partitionShadowKey(3));
        assertEquals(300, HotRedisSnapshotStore.PARTITION_TTL_SECONDS);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 读某个 status 的 Redis 同步计数。
     *
     * @param status {@code success} / {@code fail}
     * @return 计数
     */
    private long redisSyncCount(String status) {
        var counter = meterRegistry.find("article_hot_redis_sync_total").tag("status", status).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    /**
     * 保留：断言 null 的读数路径（防止把 null 判据删掉）。
     */
    @Test
    @DisplayName("flush：分区不存在时不得凭空造 key")
    void flushDoesNotCreateOtherPartitions() {
        HeavyKeeper hk = new HeavyKeeper(1000, 5, 0.9, 100);
        hk.add("x", 1);
        store.flush(7, hk);

        assertTrue(redis.hasKey(HotRedisSnapshotStore.partitionKey(7)));
        assertNull(redis.opsForZSet().score(HotRedisSnapshotStore.partitionKey(0), "x"),
                "别的分区不得被写");
    }
}
