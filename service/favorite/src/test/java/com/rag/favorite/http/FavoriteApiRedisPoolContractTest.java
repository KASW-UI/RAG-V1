package com.rag.favorite.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * 🔴 Redis **连接池形态**守门（favorite 域）—— 必须与 Go 侧的 go-zero node 客户端同形，且**不得无限等**。
 *
 * <h2>守的是什么漂移</h2>
 * <p>本组件原状是 <b>无池</b>：{@code application.yml} 没有 {@code spring.data.redis.lettuce.pool} 段、
 * {@code pom.xml} 没有 {@code commons-pool2} ⇒ Boot 的判据
 * {@code DataRedisConnectionConfiguration#isPoolEnabled}
 * {@code enabled != null ? enabled : COMMONS_POOL2_AVAILABLE} ⇒ <b>池不启用</b>
 * ⇒ Lettuce 走 <b>共享单连接</b> ⇒ 并发下命令互相串行排队 ⇒ 可能撞 3s 命令超时。
 * 而 Go 侧 <b>有池</b> ⇒ 这是<b>形态漂移</b>（不是数值漂移）。
 *
 * <h2>Go 出处（消费点，不是声明点）</h2>
 * <pre>
 *   service/favorite/api/internal/svc/service_context.go:20
 *       redisDB := redis.MustNewRedis(c.BizRedis)
 *     → c.BizRedis 是 redis.RedisConf（internal/config/config.go:16），Type: node（etc/favorite.yaml:16）
 *     → go-zero core/stores/redis/redis.go:109  MustNewRedis
 *     → :122  NewRedis  →  :139  newRedis（Type: NodeType）
 *     → :2723  case NodeType: return getClient(r)
 *     → go-zero core/stores/redis/redisclientmanager.go:32-40
 *         red.NewClient(&amp;red.Options{Addr, Username, Password, DB, MaxRetries: maxRetries,
 *                                       MinIdleConns: idleConns, TLSConfig})
 *         ⇒ :15  idleConns = 8  ⇒ ⇒ MinIdleConns = 8
 *         ⇒ PoolSize 未设 ⇒ go-redis v9.20.0/options.go:350-351 = 10 × GOMAXPROCS ⇒ **Go 侧有池**
 * </pre>
 *
 * <h2>🔴 本域与 hot-rpc / comment-rpc <b>不同族</b>：{@code min-idle} 必须是 8，不是 0</h2>
 * <p>hot-rpc 与 comment-rpc 的 Go 走 <b>go-redis 直连</b>（{@code redis.NewClient(&redis.Options{...})}）
 * ⇒ {@code MinIdleConns} <b>未设</b> ⇒ {@code go-redis@v9.20.0/options.go:218-222} <b>无 default</b> ⇒ 0
 * ⇒ 那两个域取 {@code min-idle: 0} 是<b>精确对齐</b>。
 * <p>而<b>本域</b>的 Go 走 <b>go-zero</b>（{@code MustNewRedis}）⇒ {@code idleConns = 8}
 * ⇒ ⇒ 本域必须取 {@code min-idle: 8} ⇒ ⇒ <b>照抄别域的 0 = 变成对 Go 的偏离</b>。
 * <p>⚠️ <b>不要拿「现有 RAG 代码」当对照基准</b>（AGENTS.md §3.1）—— 抄同族的 0 就是抄错。
 *
 * <h2>为什么本守门断言「真装配出来的池参数」而不是「yml 里有这几行」</h2>
 * <p>AGENTS.md §3.3：<b>配置存在 ≠ 机制生效</b>。本守门走的是<b>整条真实链路</b>：
 * <ol>
 *   <li>{@link ConfigDataApplicationContextInitializer} ⇒ 加载本模块<b>真实的</b> {@code application.yml}；</li>
 *   <li>Boot <b>真实的</b> {@link RedisAutoConfiguration} ⇒ 装配出<b>真的</b>
 *       {@link LettuceConnectionFactory}；</li>
 *   <li>断言读的是<b>消费点</b>：Boot 真正的装配点是
 *       {@code LettuceConnectionConfiguration$PoolBuilderFactory.getPoolConfig}
 *       —— {@code setMaxTotal(getMaxActive())} · {@code setMaxIdle(getMaxIdle())} ·
 *       {@code setMinIdle(getMinIdle())} · {@code setMaxWait(getMaxWait())}；</li>
 *   <li>⇒ 断言的对象是 <b>Boot 装配出来的那份 {@link GenericObjectPoolConfig}</b>
 *       （{@code LettucePoolingClientConfiguration#getPoolConfig()}）。</li>
 * </ol>
 * <p>⚠️ <b>本守门不读 yml 文本</b> ⇒ 属性名写错 / 层级写错 / 键名拼错 ⇒ 一律红。
 *
 * <h2>🔴 为什么 {@code max-wait} 必须配（这不是可选优化）</h2>
 * <p>Go 侧池耗尽时<b>有上限</b>：
 * <pre>
 *   go-redis@v9.20.0/options.go:380-386
 *       if opt.PoolTimeout == 0 { if opt.ReadTimeout &gt; 0 { opt.PoolTimeout = opt.ReadTimeout + time.Second } … }
 *     ⇒ ReadTimeout = 3s ⇒ **PoolTimeout = 4s**
 *   go-redis@v9.20.0/internal/pool/pool.go:1113
 *       err := p.semaphore.Acquire(ctx, p.cfg.PoolTimeout, ErrPoolTimeout)
 *     ⇒ 池耗尽 ⇒ 4s 后报 ErrPoolTimeout（"redis: connection pool timeout"）
 * </pre>
 * <p>而 RAG 侧<b>不配</b> {@code max-wait} ⇒ 落 commons-pool2 自己的默认
 * {@code BaseObjectPoolConfig DEFAULT_MAX_WAIT = Duration.ofMillis(-1L)}
 * ⇒ 消费点 {@code GenericObjectPool.java:306}
 * {@code p = negativeDuration ? idleObjects.takeFirst() : idleObjects.pollFirst(remainingWaitDuration)}
 * ⇒ <b>阻塞到有人还连接为止（无上限）</b>。
 * <p>🔴 <b>Lettuce 的 3s 命令超时救不了场</b>：{@code CommandExpiryWriter.write(command)} 才起算计时器，
 * 而<b>借连接发生在写命令之前</b>
 * ⇒ ⇒ 池耗尽时 3s 命令超时<b>根本没开始</b> ⇒ ⇒ <b>无限挂起</b>。
 * ⇒ ⇒ <b>光加池会引入比 Go 更糟的行为</b> ⇒ {@code max-wait: 4s} 是<b>必配项</b>。
 *
 * <h2>取值口径（逐项对着 Go 的库默认，不拿「现有 RAG 代码」当基准 · AGENTS.md §3.1）</h2>
 * <table border="1">
 *   <caption>Go（go-zero node 客户端，favorite 域）↔ Lettuce</caption>
 *   <tr><th>语义</th><th>Go 侧真值</th><th>Lettuce</th><th>本仓取值</th><th>对齐度</th></tr>
 *   <tr><td>有没有池</td><td>有（PoolSize = 10×核数）</td><td>enabled</td><td>true</td><td>✅ 形态对齐</td></tr>
 *   <tr><td>总量上限</td><td>无硬上限（MaxActiveConns 未设 ⇒ options.go:230-235）</td><td>maxTotal</td><td>64</td>
 *       <td>🟡 数量级对齐（Go 的 PoolSize 本机 100），性能面</td></tr>
 *   <tr><td>最少空闲</td><td><b>8</b>（go-zero <code>idleConns = 8</code> ⇒ MinIdleConns）</td><td>minIdle</td><td>8</td>
 *       <td>✅ 精确对齐（写成 0 = 抄了 hot-rpc 那族 ⇒ 变成对 Go 的偏离）</td></tr>
 *   <tr><td>最多空闲</td><td>0 ⇒ 无上限（options.go:224-228；消费点 pool.go:1257）</td><td>maxIdle</td><td>16</td>
 *       <td>🟡 表达层取值（比 Go 更严，只影响空闲连接回收，不影响命令结果）</td></tr>
 *   <tr><td>池耗尽等待</td><td>4s（PoolTimeout = ReadTimeout+1s）</td><td>maxWait</td><td>4s</td>
 *       <td>✅ 语义对齐（异常类型不同，耗时口径一致）</td></tr>
 * </table>
 *
 * <h2>与本模块另一条守门的分工</h2>
 * <p>{@code FavoriteApiRedisTimeoutContractTest} 守的是<b>超时轴</b>（connect 5s / command 3s），
 * 本类守的是<b>池形态轴</b> ⇒ <b>两条不同轴，不重复</b>。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>包名</b>压平（D7）；<br>
 *       ⚠️ <b>Boot 4 → Boot 3.5 的包名回改</b>（D14 工具链）：
 *       {@code org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration}
 *       ⇒ <b>C 侧 Boot 3.5 里根本没有这个类</b>，对应物是
 *       {@code org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration}
 *       （它 {@code @Import} 了 {@code LettuceConnectionConfiguration}）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 四条断言的判据与口径（池启用 / 64-16-8 / 4s / 阳性对照 -1）逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/config/FavoriteApiRedisPoolContractTest.java:18-241}。</li>
 * </ol>
 */
class FavoriteApiRedisPoolContractTest {

    /**
     * Go 的 {@code PoolTimeout}（go-redis v9.20.0 {@code options.go:380-386}：
     * {@code PoolTimeout = ReadTimeout + 1s}；本域 ReadTimeout = 3s）。
     */
    private static final Duration GO_POOL_TIMEOUT = Duration.ofSeconds(4);

    /**
     * Go 的 {@code MinIdleConns}（<b>go-zero</b> {@code core/stores/redis/redisclientmanager.go:15}
     * {@code idleConns = 8} ⇒ {@code :36} {@code MinIdleConns: idleConns}）。
     *
     * <p>⚠️ 本域的 Go 走 go-zero ⇒ **8**；hot-rpc / comment-rpc 的 Go 走 go-redis 直连 ⇒ 那个是 **0**。
     */
    private static final int GO_MIN_IDLE_CONNS = 8;

    /** 池最大连接数（本仓取值；Go 侧是 {@code 10 × 核数}，机器相关 ⇒ 数量级对齐）。 */
    private static final int RAG_MAX_ACTIVE = 64;

    /** 池最大空闲连接数（本仓取值；Go 侧 {@code MaxIdleConns} 未设 ⇒ 0 = 无上限）。 */
    private static final int RAG_MAX_IDLE = 16;

    @Test
    @DisplayName("① Redis 连接池必须**启用**（Go 侧 go-zero node 客户端默认有池；Boot 判据 = enabled ?? COMMONS_POOL2_AVAILABLE）")
    void redisPoolMustBeEnabled() {
        assertThat(poolConfiguration())
                .as("🔴 Lettuce 连接池必须**启用**（Go 侧走 go-zero 的 node 客户端 ⇒ 有池："
                        + "PoolSize = 10 × 核数、MinIdleConns = 8）。"
                        + "Boot 的判据是 `enabled != null ? enabled : COMMONS_POOL2_AVAILABLE`"
                        + " ⇒ 若 yml 的 `spring.data.redis.lettuce.pool.enabled` 缺失，"
                        + "且 classpath 里没有 commons-pool2 ⇒ 池不启用 ⇒ Lettuce 走**共享单连接**"
                        + " ⇒ 并发下命令串行排队 ⇒ 可能撞 3s 命令超时。"
                        + "⚠️ 启用但**参数错**是另一类漂移 ⇒ 由 ②③ 覆盖。"
                        + "⚠️ 另注：pom 抽掉 `commons-pool2` ⇒ **启动期直接失败**"
                        + "（`NoClassDefFoundError: org/apache/commons/pool2/impl/GenericObjectPoolConfig`）"
                        + "⇒ 那不是「静默变无池」，是**硬失败**。")
                .isNotNull();
    }

    @Test
    @DisplayName("② 池参数必须是 64 / 16 / 8（min-idle 与 go-zero 的 MinIdleConns=8 精确对齐）")
    void redisPoolParametersMatchGoBaseline() {
        GenericObjectPoolConfig<?> poolConfig = poolConfig();

        assertThat(poolConfig.getMaxTotal())
                .as("🔴 连接池最大连接数必须 = 64（Boot ⇒ `setMaxTotal(getMaxActive())`）"
                        + " ⇒ Go 侧 `PoolSize` 未设 ⇒ 落 go-redis v9.20.0/options.go:350-351 `= 10 × GOMAXPROCS`"
                        + "（本机 100，机器相关 ⇒ **数量级**对齐，非逐值对齐）"
                        + " ⇒ 数值差异属**性能面**。"
                        + "⚠️ 另注：Go 的 `MaxActiveConns` **未设** ⇒ `options.go:230-235`"
                        + "「When zero, there is no limit on the number of connections in the pool」"
                        + " ⇒ Go 侧**无总量硬上限** ⇒ 本项 64 是**表达层**的硬上限（残留差异同属性能面）")
                .isEqualTo(RAG_MAX_ACTIVE);

        assertThat(poolConfig.getMaxIdle())
                .as("连接池最大空闲连接数必须 = 16（Boot ⇒ `setMaxIdle(getMaxIdle())`）"
                        + " ⇒ Go 侧 `MaxIdleConns` **未设** ⇒ `go-redis@v9.20.0/options.go:224-228` 无 default ⇒ 0"
                        + " ⇒ 消费点 `internal/pool/pool.go:1257`"
                        + " `if p.cfg.MaxIdleConns == 0 || …` ⇒ **0 = 无上限**"
                        + " ⇒ 本项 16 是**表达层**取值：**比 Go 更严**（只影响空闲连接的保留/回收，"
                        + "**不影响命令结果**）")
                .isEqualTo(RAG_MAX_IDLE);

        assertThat(poolConfig.getMinIdle())
                .as("🔴 连接池最小空闲连接数必须 = 8（Boot ⇒ `setMinIdle(getMinIdle())`）"
                        + " ⇒ Go 侧走 **go-zero**：`core/stores/redis/redisclientmanager.go:15` `idleConns = 8`"
                        + " ⇒ `:36` `MinIdleConns: idleConns` ⇒ **8** ⇒ 本项是**精确对齐**。"
                        + "⚠️ **别照抄 hot-rpc / comment-rpc 的 0** —— 那两个域的 Go 走 **go-redis 直连**"
                        + "（`MinIdleConns` 未设 ⇒ 0）⇒ **同族的 0 在本域就是偏离**")
                .isEqualTo(GO_MIN_IDLE_CONNS);
    }

    @Test
    @DisplayName("③ 池耗尽的等待上限必须是 4s（对齐 Go 的 PoolTimeout；不配 = 无限等）")
    void poolExhaustionWaitMustBeBounded() {
        // 🔴 **本条是「光加池反而更糟」的那条腿**：不配 max-wait ⇒ commons-pool2 落自己的
        //    DEFAULT_MAX_WAIT = -1 ⇒ `GenericObjectPool:306` 走 `idleObjects.takeFirst()`
        //    ⇒ **阻塞到有人还连接为止** ⇒ 且 Lettuce 的 3s 命令超时此时**还没起算**
        //    （`CommandExpiryWriter.write()` 才起算，而借连接在写命令之前）
        //    ⇒ ⇒ **无限挂起**；Go 侧是 4s 后报 ErrPoolTimeout。
        assertThat(poolConfig().getMaxWaitDuration())
                .as("🔴 池耗尽的等待上限必须 = 4s（Go 的 `PoolTimeout = ReadTimeout + 1s`，"
                        + "ReadTimeout = 3s ⇒ 4s；`go-redis@v9.20.0/internal/pool/pool.go:1113` "
                        + "`p.semaphore.Acquire(ctx, p.cfg.PoolTimeout, ErrPoolTimeout)`）。"
                        + "不配 `max-wait` ⇒ 落 commons-pool2 的 `DEFAULT_MAX_WAIT = -1`"
                        + " ⇒ `GenericObjectPool:306` ⇒ **无限等**，且 3s 命令超时尚未起算"
                        + " ⇒ ⇒ **比 Go 更糟**（Go 4s 失败）")
                .isEqualTo(GO_POOL_TIMEOUT);
    }

    @Test
    @DisplayName("④ 阳性对照：commons-pool2 自己的默认 maxWait 是 -1（证明断言 ③ 有识别能力）")
    void commonsPoolDefaultMaxWaitIsNegativeOne() {
        // ★ 阳性对照：commons-pool2 2.12.1 `BaseObjectPoolConfig`
        //   `DEFAULT_MAX_WAIT_MILLIS = -1L` / `DEFAULT_MAX_WAIT = Duration.ofMillis(-1L)`
        // ⇒ 若断言 ③ 恒真，说明它没在区分「配了 4s」与「没配」
        assertThat(new GenericObjectPoolConfig().getMaxWaitDuration())
                .as("★ 阳性对照：commons-pool2 的**默认** maxWait 是 -1（= 无限等）"
                        + " ⇒ 若断言 ③ 恒真，说明它没在区分「配了 4s」与「没配」")
                .isEqualTo(Duration.ofMillis(-1L));
    }

    /** 池配置对象（**读消费点**：Boot 装配出来的 {@link LettucePoolingClientConfiguration}）。 */
    private LettucePoolingClientConfiguration poolConfiguration() {
        AtomicReference<LettucePoolingClientConfiguration> holder = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).as("Redis 自动配置必须装配成功").hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getClientConfiguration())
                            .as("🔴 Lettuce 连接池必须已启用（否则读不到池参数 ⇒ 断言 ②③ 无法成立）"
                                    + " ⇒ 未启用时装配出的是普通 `LettuceClientConfiguration`（共享单连接）")
                            .isInstanceOf(LettucePoolingClientConfiguration.class);
                    holder.set((LettucePoolingClientConfiguration) factory.getClientConfiguration());
                });
        LettucePoolingClientConfiguration configuration = holder.get();
        assertThat(configuration)
                .as("池配置必须取到（ApplicationContextRunner 未执行断言 ⇒ 上下文没起来）")
                .isNotNull();
        return configuration;
    }

    /** 池参数（**读消费点**：Boot 装配出来的 {@link GenericObjectPoolConfig}）。 */
    private GenericObjectPoolConfig<?> poolConfig() {
        return poolConfiguration().getPoolConfig();
    }
}
