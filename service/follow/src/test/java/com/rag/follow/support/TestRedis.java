package com.rag.follow.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * <p><b>测试用真 Redis（进程内单例容器）</b> —— follow 域<b>唯一</b>的 Redis 用途是
 * JWT 黑名单闸门（{@code FollowBlacklistInterceptor}）。
 *
 * <h2>⚠️ 为什么必须是真 Redis（不能 mock {@code StringRedisTemplate}）</h2>
 * <p>本域要钉的 L1 契约之一是<b>黑名单命中那条 401 的<b>逐字节</b>形状</b>
 * （{@code {"code":5004,"msg":"unauthorized"}} ＋ {@code Content-Type: application/json;charset=utf-8}）
 * ⇒ 那条路径由拦截器<b>自己写响应并 return false</b> ⇒ 必须真的走一次「Redis 里确实有这个 key」
 * 的查询 ⇒ ⇒ 真 Redis 容器（{@code redis:7-alpine}）。
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
