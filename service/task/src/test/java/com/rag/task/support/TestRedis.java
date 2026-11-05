package com.rag.task.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * <p><b>测试用真 Redis（进程内单例容器）</b>。</p>
 *
 * <h2>⚠️ 为什么必须是真 Redis（不能 mock {@code StringRedisTemplate}）</h2>
 * <p>本域的 Redis 语义有<b>三处只能靠真 Redis 触发</b>：
 * <ul>
 *   <li><b>SETNX 的 TTL 闸门</b>：{@code SETNX task:init:<uid>:1902 "1" EX 90d} ——
 *       mock 造不出「第一次 true / 第二次 false」这个由服务端原子性保证的行为；</li>
 *   <li><b>pipeline 的结果形状</b>：{@code RedisPipelines#toBooleans} 依赖
 *       「pipeline（非事务）模式下每条结果是<b>单个 Boolean</b>」——
 *       mock 返回的 List 形状与真 Redis 不同 ⇒ 会掩盖「误用 MULTI/EXEC」这类假绿；</li>
 *   <li><b>读失败 ⇒ 回源 DB</b>：{@code TaskDomainService#readProgressCache} 吞掉异常后回源，
 *       这条「降级」路径必须靠「连接被拒」触发，mock 造不出。</li>
 * </ul>
 *
 * <p>📌 <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ ⇒ 容器不可用时<b>直接失败</b>。</p>
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
