package com.rag.hot.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <p><b>测试用真 Redis（进程内单例容器）</b> —— hot 域的 Lua 脚本、ZSET 读写、
 * TTL 断言都必须跑在<b>真 Redis</b> 上。
 *
 * <h2>⚠️ 为什么必须跑真 Redis（不能用 mock 顶替）</h2>
 * <p>本域的三个 Lua 脚本（分片改名 / 合并改名 / 锁续期 / 锁释放）<b>只有在真 Redis 上
 * 才能被执行</b>：mock 掉 {@code StringRedisTemplate} ⇒ 脚本内容、{@code EXISTS} 守卫、
 * {@code RENAME} 语义、{@code EXPIRE 300} 这些<b>可观察事实一个都验不到</b>
 * ⇒ ⇒ 「跑绿」只能证明「字符串被解析了」，<b>不能证明脚本真的对</b> ⇒ <b>假绿</b>（§3.3）。
 *
 * <h2>⚠️ 为什么用 {@link GenericContainer} 而不是 {@code RedisContainer}</h2>
 * <p>{@code org.testcontainers:testcontainers-redis}（{@code RedisContainer}）在本仓的
 * pom 里<b>没有声明</b> ⇒ ⇒ 用核心模块的 {@link GenericContainer} + 本机已缓存的
 * {@code redis:latest} 镜像 ⇒ <b>不新增依赖、不联网拉镜像</b>。
 *
 * <h2>为什么是「单例」而不是每个测试类一个容器</h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 每个类各起一个容器 ⇒ 慢 3 倍且机器上同时跑多个 Redis。
 * ⇒ ⇒ 单例：<b>整个 JVM 起一次</b>，各测试类共用同一个实例。
 * <p>⚠️ <b>共用实例 ⇒ 数据必须互不冲突</b> ⇒ 各测试类用<b>独占的 key 前缀</b>或
 * 在 {@code @BeforeEach} 里清理自己用到的 key。
 *
 * <p>📌 <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ ⇒ 容器不可用时**直接失败**，而不是静默跳过（跳过 = 假绿）。
 */
public final class TestRedis {

    /** 容器镜像 —— 与本机缓存一致（离线可用）。 */
    private static final DockerImageName IMAGE = DockerImageName.parse("redis:latest");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(IMAGE)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    static {
        REDIS.start();
    }

    private TestRedis() {
    }

    /**
     * 把容器连接信息注入 Spring 的 {@code spring.data.redis.*}。
     *
     * @param registry Spring 的动态属性注册表
     */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    /**
     * 容器的主机名（供测试里直接建连接用）。
     *
     * @return 主机名
     */
    public static String host() {
        return REDIS.getHost();
    }

    /**
     * 容器映射出来的端口（供测试里直接建连接用）。
     *
     * @return 端口
     */
    public static int port() {
        return REDIS.getMappedPort(6379);
    }
}
