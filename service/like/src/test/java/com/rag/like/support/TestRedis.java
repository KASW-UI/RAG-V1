package com.rag.like.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * <p><b>测试用真 Redis（进程内单例容器）</b> —— like 域的 Redis 用途是
 * <b>点赞计数的读写缓存</b>（{@code like_count:{target_type}} 等 6 个 key）。
 *
 * <h2>⚠️ 为什么必须是真 Redis（不能 mock {@code StringRedisTemplate}）</h2>
 * <p>like 域的读路径有一条<b>刻意的不对称</b>：
 * <ul>
 *   <li><b>Redis 读失败</b> ⇒ <b>降级成 cache miss</b>（回源 DB，<b>不是</b>致命）；</li>
 *   <li><b>Redis 写失败</b> ⇒ <b>致命</b>（写路径不容忍丢计数）。</li>
 * </ul>
 * ⇒ ⇒ 这条不对称<b>只能靠真 Redis</b> 触发（用 mock 制造不出「连接被拒」与「key 不存在」的区别）。
 * <p>⇒ 而且 {@code LikeRedisKeys} 的 6 个 key <b>是 L1 契约</b>（B 侧逐字照抄）
 * ⇒ ⇒ 必须能在真 Redis 上观测到。
 *
 * <p>📌 <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ ⇒ 容器不可用时**直接失败**。
 */
public final class TestRedis {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private TestRedis() {
    }

    /**
     * 把容器连接信息注入 Spring 的 {@code spring.data.redis.*}。
     */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
