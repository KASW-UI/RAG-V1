package com.rag.comment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * <p><b>测试用真 Redis（进程内单例容器）</b> —— comment 域的 Redis 用途是
 * <b>评论区的读穿缓存</b>（{@code comment:subject:*} / {@code comment:content:*} /
 * {@code comment:index:*} 三族 key，见 {@code service/CommentRedisKeys}）。
 *
 * <h2>⚠️ 为什么必须是真 Redis（不能 mock {@code StringRedisTemplate}）</h2>
 * <p>comment 域的缓存读路径上有一条<b>刻意的不对称</b>（B 侧
 * {@code comment_subject_cache.go:16-132}，C 侧 {@code service/CommentCacheService}）：
 * <ul>
 *   <li><b>Redis 读失败</b> ⇒ <b>降级成 cache miss</b>（回源 DB，<b>不是</b>致命），
 *       只打一个 {@code comment_redis_error_total{module="comment_redis",type="query"}} 指标；</li>
 *   <li><b>回填失败</b> ⇒ <b>显式忽略</b>（Go 侧是 {@code _ = c.SetSubjectCache(...)}）
 *       ⇒ Redis 整个挂掉时<b>读路径照常返回 DB 回源的结果</b>，不是 500。</li>
 * </ul>
 * ⇒ ⇒ 这条不对称<b>只能靠真 Redis</b> 触发（用 mock 制造不出「连接被拒」与「key 不存在」的区别）。
 * <p>⇒ 而且 {@code CommentRedisKeys} 的 5 个 key 构造器<b>是 L1 契约</b>（B 侧逐字照抄）
 * ⇒ ⇒ 必须能在真 Redis 上观测到。
 *
 * <h2>⚠️ 本域还有一条<b>与 like 域方向相反</b>的 Redis 语义</h2>
 * <p>like 域是「<b>Redis 写失败 ⇒ 致命</b>」（写路径不容忍丢计数）；
 * comment 域是「<b>Redis 写失败 ⇒ 只 warn，不影响业务结果</b>」
 * （B 侧 {@code comment_subject_cache.go:59} 的 {@code _ = …}）
 * ⇒ ⇒ 两个域的 Redis 失败期望<b>不同</b>，测试不可跨域照抄断言。
 *
 * <h2>⚠️ fail-fast：容器起不来就<b>直接失败</b></h2>
 * <p>本类的 {@code static { REDIS.start(); }} 是 fail-fast 的载体：
 * 容器拉不起来 ⇒ static 块抛异常 ⇒ {@code ExceptionInInitializerError}
 * ⇒ ⇒ 测试类加载即失败（而不是「跳过」）。
 * <p>📌 <b>禁止 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ ⇒ 容器不可用时**直接失败**，不静默跳过（跳过 = 假绿）。
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比参照实现（like 域同款）多做了什么</b>：<b>无</b> ——
 *       镜像 tag / 暴露端口 / 注入的两个键 / 单例与 fail-fast 语义<b>逐条与 like 域一致</b>。
 *       只有<b>类 javadoc 里的「用途说明」</b>按 comment 域实际情况改写
 *       （like 域缓存的是点赞计数，本域缓存的是评论区读穿缓存 ⇒ key 族与失败语义都不同）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 本类只提供「一个连得上的真 Redis」，
 *       不改变被测代码的任何行为；它<b>不</b>改主 yml 的 Redis 配置
 *       （连接超时 5s / pool 开启 / max-wait 4s 仍由主 yml 决定）。</li>
 *   <li><b>B 出处</b>：缓存语义来自 B 侧
 *       {@code service/comment/rpc/internal/cache/keys.go:5-20}（key 族）
 *       ＋ {@code comment_subject_cache.go:16-132}（读失败降级 / 回填失败忽略）；
 *       容器形态参照本仓 like 域 {@code service/like/src/test/java/com/rag/like/support/TestRedis.java}。</li>
 * </ol>
 */
public final class TestRedis {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        // ⚠️ fail-fast：容器起不来 ⇒ static 块抛异常 ⇒ ExceptionInInitializerError ⇒ 测试直接失败。
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
