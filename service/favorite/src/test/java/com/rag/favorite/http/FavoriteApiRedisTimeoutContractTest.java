package com.rag.favorite.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * 🔴 Redis **超时口径**守门（favorite 域）—— TCP 连接超时必须 = Go 的 DialTimeout **5s**。
 *
 * <h2>守的是什么漂移</h2>
 * <p>Go 侧 favorite-api 的黑名单中间件要读 Redis（{@code checkblacklist_middleware.go:37}
 * 的 {@code m.Redis.ExistsCtx(...)}）⇒ 连接建立阶段的超时**可观察**。
 *
 * <p><b>Go 出处（消费点，不是声明点）</b>：
 * <pre>
 *   service/favorite/api/internal/svc/service_context.go:20
 *       redisDB := redis.MustNewRedis(c.BizRedis)
 *     → go-zero core/stores/redis/redisclientmanager.go:32-40
 *       red.NewClient(&amp;red.Options{Addr, Username, Password, DB, MaxRetries, MinIdleConns, TLSConfig})
 *       ⇒ **没有设置 DialTimeout**
 *     → go-redis v9.20.0 options.go:338-340
 *       if opt.DialTimeout == 0 { opt.DialTimeout = 5 * time.Second }
 *       ⇒ **DialTimeout = 5s**
 *       （ReadTimeout/WriteTimeout 同理走 options.go:364-379 ⇒ 3s）
 * </pre>
 *
 * <h2>为什么 Spring 侧「不写就错」</h2>
 * <ul>
 *   <li>Boot 的 {@code LettuceConnectionConfiguration#createClientOptions}：
 *       <b>只有</b> {@code spring.data.redis.connect-timeout} 非空时才调
 *       {@code SocketOptions.Builder.connectTimeout(...)}；<b>不写 ⇒ 整段不设</b>
 *       ⇒ 落回 Lettuce 的 {@code SocketOptions.DEFAULT_CONNECT_TIMEOUT} = <b>10s</b>。</li>
 *   <li>⚠️ {@code spring.data.redis.timeout}（命令超时）<b>不再</b>兼管连接超时 ——
 *       那是 Boot 2/3 的行为（Boot 4 起拆成两个属性）。⇒ 两个超时必须分别配。</li>
 *   <li>⇒ 不写 {@code connect-timeout} ⇒ 连接超时 <b>10s vs Go 5s</b> ⇒ <b>漂移 2 倍</b>
 *       （Redis 不可达时要多等一倍时间才失败）。</li>
 * </ul>
 *
 * <h2>为什么本守门不是「断言 yml 里有这个键」</h2>
 * <p>本守门走的是<b>整条真实链路</b>：
 * <ol>
 *   <li>{@link ConfigDataApplicationContextInitializer} ⇒ 加载本模块**真实的** {@code application.yml}；</li>
 *   <li>Boot <b>真实的</b> {@link RedisAutoConfiguration}（它 {@code @Import} 了
 *       {@code LettuceConnectionConfiguration}）⇒ 装配出<b>真的</b> {@link LettuceConnectionFactory}；</li>
 *   <li>断言读的是<b>消费点</b>：{@code LettuceClientConfiguration.getClientOptions()}
 *       → {@code ClientOptions.getSocketOptions().getConnectTimeout()}，
 *       而不是「yml 文本里有没有这个键」—— 后者挡不住「属性名写错 ⇒ 静默用默认值」。</li>
 * </ol>
 * <p>⚠️ <b>共享层不参与</b>：共享层的 {@code ClientAutoConfiguration}
 * 只设 {@code disconnectedBehavior(REJECT_COMMANDS)}、<b>不碰</b> {@code socketOptions}
 * ⇒ 连接超时完全由本域 yml 决定 ⇒ 本守门不引入共享层那一半（避免与共享层自己的守门重复）。
 *
 * <p><b>证据强度</b>：L3 静态实测（go-redis v9.20.0 源码）＋ 本守门的<b>真容器</b>装配断言
 * （L4 的「属性真落到 Lettuce」这一层）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>包名</b>由 {@code com.rag.favorite.favorite.api.config}
 *       压平为 {@code com.rag.favorite.http}（D7）；<br>
 *       ⚠️ <b>Boot 4 → Boot 3.5 的包名回改</b>（D14 工具链）：B 侧
 *       {@code org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration}
 *       ⇒ <b>C 侧 Boot 3.5 里根本没有这个类</b>，对应物是
 *       {@code org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration}
 *       （它 {@code @Import} 了 {@code LettuceConnectionConfiguration}）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 断言的两个数（5s / 3s）、消费的判据位置
 *       （{@code ClientOptions#getSocketOptions#getConnectTimeout}）、
 *       以及「读消费点而不是读 yml 文本」这条纪律逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/config/FavoriteApiRedisTimeoutContractTest.java:16-96}。</li>
 * </ol>
 */
class FavoriteApiRedisTimeoutContractTest {

    /** Go 的 DialTimeout（go-redis v9.20.0 options.go:338-340）。 */
    private static final Duration GO_DIAL_TIMEOUT = Duration.ofSeconds(5);

    /** Go 的 ReadTimeout / WriteTimeout（go-redis v9.20.0 options.go:364-379）。 */
    private static final Duration GO_COMMAND_TIMEOUT = Duration.ofSeconds(3);

    @Test
    @DisplayName("🔴 Redis 连接超时 = Go 的 DialTimeout(5s)，命令超时 = Go 的 Read/WriteTimeout(3s)")
    void redisConnectAndCommandTimeoutsMatchGoSide() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    ClientOptions clientOptions = factory.getClientConfiguration().getClientOptions()
                            .orElseThrow(() -> new AssertionError("LettuceClientConfiguration 没有 ClientOptions"));

                    assertThat(clientOptions.getSocketOptions().getConnectTimeout())
                            .as("Redis 的 **TCP 连接**超时必须 = Go 的 DialTimeout(5s)；"
                                    + "Boot 的 spring.data.redis.timeout **不再**兼管它，"
                                    + "不写 connect-timeout 就会落回 Lettuce 默认的 10s")
                            .isEqualTo(GO_DIAL_TIMEOUT);

                    assertThat(factory.getClientConfiguration().getCommandTimeout())
                            .as("Redis 的**命令**超时必须 = Go 的 Read/WriteTimeout(3s)")
                            .isEqualTo(GO_COMMAND_TIMEOUT);
                });
    }
}
