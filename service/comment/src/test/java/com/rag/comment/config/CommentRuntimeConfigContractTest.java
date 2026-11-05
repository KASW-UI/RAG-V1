package com.rag.comment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.grpc.NameResolver;
import io.grpc.SynchronizationContext;
import io.lettuce.core.ClientOptions;
import net.devh.boot.grpc.client.nameresolver.DiscoveryClientResolverFactory;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * 🔴 <b>C 组（L4 配置面）守门</b> —— comment 域的 <b>Redis 连接池 / Redis 超时 / gRPC 出向地址 /
 * 打包产物一致性</b> 共 10 个 B 侧守门点，逐条落到 <b>真装配</b>或 <b>真文件</b>上。
 *
 * <h2>守的是什么漂移（逐条对应 B 侧 oracle）</h2>
 * <ol>
 *   <li><b>Redis 连接池</b>（①②③④⑤⑥）——
 *       形态上「yml 配了 {@code lettuce.pool.enabled: true}」<b>不等于</b>「池真的开着、参数真的对」
 *       ⇒ 本类起 <b>真</b> {@link ApplicationContextRunner} ＋ Boot <b>真</b>的
 *       {@link RedisAutoConfiguration} ⇒ 拿<b>真的</b> {@link LettuceConnectionFactory}
 *       ⇒ 断言读的是<b>消费点</b>（§3.3.1）。</li>
 *   <li><b>Redis 超时</b>（⑦⑧）—— 连接超时与命令超时在 Boot 3 起<b>拆成两个属性</b>
 *       ⇒ 不分别配就会落回 Lettuce 的默认值（连接 10s / 命令 3s）。</li>
 *   <li><b>gRPC 出向地址</b>（⑨）—— 生产地址必须是 <b>三斜杠</b> {@code discovery:///&lt;name&gt;}
 *       ⇒ 两斜杠写法在 devh 的 URI 校验处直接抛 {@link IllegalArgumentException}
 *       ⇒ <b>整个服务起不来</b>（真 fat jar 实测，B 侧原文）。</li>
 *   <li><b>打包产物一致性</b>（⑩）—— {@code target/classes/application.yml} 与源码树
 *       <b>逐字节</b>一致 ⇒ 否则「本类断言的其实是上一次的旧产物」。</li>
 * </ol>
 *
 * <h2>L4 差异（AGENTS §3.2 三段式）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>自动配置类名换了</b>：B 侧（Boot 4.0.8）用
 *             {@code org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration}；
 *             C 侧（Boot 3.5.11）该类<b>不存在</b>，对应类是
 *             {@code org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration}
 *             （包路径相同、<b>类名不同</b>：Boot 4 才把它拆到独立 artifact 并改名）。</li>
 *         <li><b>池参数的读法更直接</b>：B 侧只能靠 Boot 4 的
 *             {@code LettuceConnectionConfiguration$PoolBuilderFactory.getPoolConfig} 反推；
 *             C 侧 Boot 3.5.11 的同名方法<b>就在源码里</b>
 *             （{@code LettuceConnectionConfiguration.java:250-262}）
 *             ⇒ 本类直接断言「Boot 装配出来的那个对象」，<b>不绕</b>。</li>
 *         <li><b>⑩ 从「比对 client 地址表」升级为「整文件逐字节比对」</b>：
 *             B 侧 {@code CommentRpcProductionAddressTest.java:165-177} 只比对
 *             {@code grpc.client.*.address} 这一小撮 ⇒ 别的段（redis / grpc.server / datasource）
 *             在产物里过期<b>测不到</b>；C 侧改成 <b>整文件</b> 逐字节一致 ⇒ <b>只增不减</b>的加固。</li>
 *         <li><b>⑩ 缺失时<b>失败</b>而非跳过</b>：B 侧用
 *             {@code Assumptions.assumeTrue} 跳过「本次没跑过构建」的情形；
 *             C 侧改为 <b>直接失败</b> ⇒ 避免「守门被静默跳过 ⇒ 漂移无声通过」。</li>
 *         <li><b>模块 yml 的定位方式换掉</b>：B 侧靠路径含 {@code "comment-rpc"}（{@code …:226-239}）；
 *             C 侧模块目录就叫 {@code comment} ⇒ 「路径含 comment」会命中一堆祖先目录
 *             ⇒ 改为<b>要求 pom 的 artifactId 是 {@code comment-service}</b> ⇒ 定位唯一。</li>
 *         <li><b>断言粒度拆细</b>：B 侧把池参数 ③④ 合并成一个用例；C 侧拆成 4 个用例
 *             ⇒ 红了能一眼看出是哪一项 ⇒ <b>纯粒度</b>，不改任何期望值。</li>
 *       </ul>
 *   </li>
 *   <li><b>语义还一样吗</b>：<b>一样</b>。①—⑩ 的<b>期望值、判据、失败信息口径全部照抄 B 侧</b>；
 *       上面 5 条差异都属于 <b>表达层</b>（类名 / 读法 / 粒度 / 定位方式 / 缺失时的处置），
 *       <b>没有一条改变运行时行为</b>，也没有一条放宽判据
 *       （唯一「变严」的两条：⑩ 整文件逐字节 ＋ 缺失即失败）。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>B 侧 {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/config/CommentRpcRedisPoolContractTest.java:1-173}（①②③④⑤⑥）</li>
 *         <li>B 侧 {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/config/CommentRpcRedisTimeoutContractTest.java:1-94}（⑦⑧）</li>
 *         <li>B 侧 {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcProductionAddressTest.java:1-240}（⑨⑩）</li>
 *         <li>期望值的<b>上游真源</b>（Go 侧，本域自己的口径）：
 *             {@code service/comment/rpc/internal/cache/redis.go:17-19}
 *             ＋ {@code go-redis v9.20.0 options.go:218-222 / 224-228 / 230-235 / 338-340 / 350-351 / 364-379 / 380-386}
 *             ＋ {@code go-redis v9.20.0 internal/pool/pool.go:1113}</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>⚠️ 证据强度：<b>L3</b>（静态实测 ＋ 真容器装配断言），<b>不是双侧活体对拍</b></h2>
 * <p>Go 侧是<b>源码级</b>取证（{@code go-redis v9.20.0} 的默认值逐行读过），
 * <b>没有</b>同时跑的 Go 侧探针 ⇒ 按本仓口径只写 L3。
 *
 * <h2>⚠️ 本类<b>不</b>需要 Docker</h2>
 * <p>Redis 侧只<b>装配</b>（{@code LettuceConnectionFactory} 的连接是<b>懒</b>建的
 * ⇒ 不断连 Redis）· gRPC 侧只<b>解析 URI</b>（不建通道）· 产物侧只<b>读字节</b>
 * ⇒ ⇒ 无 Docker 的 CI 也能跑。
 */
class CommentRuntimeConfigContractTest {

    // ==================================================================
    // 期望值（常量：每一条都写明出处）
    // ==================================================================

    /**
     * Go 的 {@code MinIdleConns} —— go-redis v9.20.0 {@code options.go:218-222}
     * <b>无 default 赋值</b> ⇒ {@code 0}。
     *
     * <p>⚠️ 本域 Go 侧<b>直连 go-redis</b>（{@code cache/redis.go:17-19} 只设了 {@code Addr}）
     * ⇒ <b>不是</b> go-zero 的 {@code idleConns = 8}（那是 like / favorite 域的口径）
     * ⇒ <b>写成 4 或 8 都是对 Go 的偏离</b>。
     */
    private static final int GO_MIN_IDLE_CONNS = 0;

    /**
     * Go 的 {@code PoolTimeout} —— go-redis v9.20.0 {@code options.go:380-386}：
     * {@code PoolTimeout = ReadTimeout + 1s}；本域 ReadTimeout = 3s（{@code options.go:364-371}）⇒ 4s。
     *
     * <p>消费点：{@code go-redis v9.20.0 internal/pool/pool.go:1113}
     * {@code p.semaphore.Acquire(ctx, p.cfg.PoolTimeout, ErrPoolTimeout)}。
     */
    private static final Duration GO_POOL_TIMEOUT = Duration.ofSeconds(4);

    /** 池最大连接数（本仓取值；Go 侧是 {@code 10 × 核数}，机器相关 ⇒ 数量级对齐）。 */
    private static final int RAG_MAX_ACTIVE = 64;

    /** 池最大空闲连接数（本仓取值；Go 侧 {@code MaxIdleConns} 未设 ⇒ {@code options.go:224-228} 无 default ⇒ 0 = 无上限）。 */
    private static final int RAG_MAX_IDLE = 16;

    /** Go 的 {@code DialTimeout} —— go-redis v9.20.0 {@code options.go:338-340} ⇒ {@code 5s}。 */
    private static final Duration GO_DIAL_TIMEOUT = Duration.ofSeconds(5);

    /** Go 的 {@code ReadTimeout} / {@code WriteTimeout} —— go-redis v9.20.0 {@code options.go:364-379} ⇒ {@code 3s}。 */
    private static final Duration GO_COMMAND_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 本模块生产 yml 里<b>全部</b> grpc client ⇒ 期望地址（三斜杠）。
     *
     * <p>key = Nacos 上的服务名（= yml 里的 client key）；value = 期望的生产地址。
     *
     * <p>Go 出处：{@code service/comment/rpc/etc/comment.yaml:42-46}
     * （{@code MessageRpc.Etcd.Key: message.rpc}）与 {@code …:48-52}
     * （{@code ArticleRpc.Etcd.Key: article.rpc}）—— Go 用 etcd、RAG 用 Nacos，
     * 但两侧都是「按<b>服务名</b>解析出目标地址」⇒ 行为同构。
     */
    private static final Map<String, String> EXPECTED_CLIENTS = Map.of(
            "message-rpc", "discovery:///message-rpc",
            "article-rpc", "discovery:///article-rpc");

    /** 本模块的 artifactId —— 用于在源码树里<b>唯一地</b>定位本模块自己的 application.yml。 */
    private static final String MODULE_ARTIFACT_ID = "comment-service";

    // ==================================================================
    // ① Redis 连接池必须启用
    // ==================================================================

    @Test
    @DisplayName("① Redis 连接池必须启用（真装配出 LettucePoolingClientConfiguration；"
            + "B 侧 CommentRpcRedisPoolContractTest.java:85-94）")
    void redisConnectionPoolMustBeEnabled() {
        LettuceConnectionFactory factory = lettuceConnectionFactory();

        assertThat(factory.getClientConfiguration())
                .as("🔴 Lettuce 连接池必须**启用**：Go 侧 go-redis 默认就有池"
                        + "（service/comment/rpc/internal/cache/redis.go:17-19 只设了 Addr ⇒ PoolSize 未设 ⇒ "
                        + "go-redis v9.20.0 options.go:350-351 ⇒ PoolSize = 10 × GOMAXPROCS）"
                        + " ⇒ 未启用时 Lettuce 走**共享单连接** ⇒ 并发下命令串行排队 ⇒ 与 Go 差一整个池形态。"
                        + "⚠️ 启用但**参数错**是另一类漂移 ⇒ 由 ②③④⑤ 覆盖。"
                        + "判据（消费点）：C 侧 Boot 3.5.11 LettuceConnectionConfiguration.java:144-149 "
                        + "`createBuilder(Pool)` ⇒ `isPoolEnabled(pool)` 命中才走 PoolBuilderFactory")
                .isInstanceOf(LettucePoolingClientConfiguration.class);
    }

    // ==================================================================
    // ②③④ 池参数
    // ==================================================================

    @Test
    @DisplayName("② 池 maxTotal 必须 = 64（B 侧 CommentRpcRedisPoolContractTest.java:101-106）")
    void redisPoolMaxTotalMustBe64() {
        assertThat(poolConfig().getMaxTotal())
                .as("🔴 连接池最大连接数必须 = 64（消费点：C 侧 Boot 3.5.11 "
                        + "LettuceConnectionConfiguration.java:252 `config.setMaxTotal(properties.getMaxActive())`）"
                        + " ⇒ Go 侧是 `PoolSize = 10 × 核数`（机器相关 ⇒ **数量级**对齐）"
                        + " ⇒ 数值差异属**性能面**。"
                        + "⚠️ Go 的 `MaxActiveConns` 未设 ⇒ options.go:230-235 ⇒ Go 侧**无总量硬上限**")
                .isEqualTo(RAG_MAX_ACTIVE);
    }

    @Test
    @DisplayName("③ 池 maxIdle 必须 = 16（B 侧 CommentRpcRedisPoolContractTest.java:108-113）")
    void redisPoolMaxIdleMustBe16() {
        assertThat(poolConfig().getMaxIdle())
                .as("连接池最大空闲连接数必须 = 16（消费点：C 侧 Boot 3.5.11 "
                        + "LettuceConnectionConfiguration.java:253 `config.setMaxIdle(properties.getMaxIdle())`）"
                        + " ⇒ Go 侧 `MaxIdleConns` 未设 ⇒ options.go:224-228 无 default ⇒ 0"
                        + " ⇒ 消费点 internal/pool/pool.go:1257 ⇒ **0 = 无上限**"
                        + " ⇒ 本项 16 是**表达层**取值（**比 Go 更严**，只影响空闲连接回收，不影响命令结果）")
                .isEqualTo(RAG_MAX_IDLE);
    }

    @Test
    @DisplayName("④ 池 minIdle 必须 = 0（与 Go 的 MinIdleConns 精确对齐，不是 4 也不是 8；"
            + "B 侧 CommentRpcRedisPoolContractTest.java:115-122）")
    void redisPoolMinIdleMustBeZero() {
        assertThat(poolConfig().getMinIdle())
                .as("🔴 连接池最小空闲连接数必须 = 0（消费点：C 侧 Boot 3.5.11 "
                        + "LettuceConnectionConfiguration.java:254 `config.setMinIdle(properties.getMinIdle())`）"
                        + " ⇒ Go 侧 `MinIdleConns` **未设** ⇒ go-redis@v9.20.0/options.go:218-222 **无 default**"
                        + " ⇒ 保持 **0** ⇒ 本项是**精确对齐**。"
                        + "⚠️ **本域 Go 走 go-redis 直连**（cache/redis.go:17-19 只设 Addr）"
                        + " ⇒ **不是** go-zero 的 `idleConns = 8`（那是 like / favorite 域的口径）"
                        + " ⇒ **写成 4 或 8 都是对 Go 的偏离**")
                .isEqualTo(GO_MIN_IDLE_CONNS);
    }

    // ==================================================================
    // ⑤ 池耗尽的等待上限
    // ==================================================================

    @Test
    @DisplayName("⑤ 池耗尽的等待上限必须是 4s（对齐 Go 的 PoolTimeout；不配 = 无限等；"
            + "B 侧 CommentRpcRedisPoolContractTest.java:125-136）")
    void poolExhaustionWaitMustBeBounded() {
        assertThat(poolConfig().getMaxWaitDuration())
                .as("🔴 池耗尽的等待上限必须 = 4s（Go 的 `PoolTimeout = ReadTimeout + 1s`，ReadTimeout = 3s ⇒ 4s；"
                        + "go-redis@v9.20.0/internal/pool/pool.go:1113 "
                        + "`p.semaphore.Acquire(ctx, p.cfg.PoolTimeout, ErrPoolTimeout)`）。"
                        + "不配 `max-wait` ⇒ 落 commons-pool2 2.12.1 的 `DEFAULT_MAX_WAIT = -1`"
                        + " ⇒ `GenericObjectPool:306` ⇒ **无限等**，且 3s 命令超时尚未起算"
                        + " ⇒ ⇒ **比 Go 更糟**（Go 4s 失败）。"
                        + "消费点：C 侧 Boot 3.5.11 LettuceConnectionConfiguration.java:258-260 "
                        + "`if (properties.getMaxWait() != null) config.setMaxWait(properties.getMaxWait())`")
                .isEqualTo(GO_POOL_TIMEOUT);
    }

    // ==================================================================
    // ⑥ 阳性对照（证明 ⑤ 有识别能力）
    // ==================================================================

    @Test
    @DisplayName("⑥ 阳性对照：commons-pool2 自己的默认 maxWait 是 -1（证明 ⑤ 有识别能力；"
            + "B 侧 CommentRpcRedisPoolContractTest.java:138-145）")
    void commonsPoolDefaultMaxWaitIsNegativeOne() {
        assertThat(new GenericObjectPoolConfig<Object>().getMaxWaitDuration())
                .as("★ 阳性对照：commons-pool2 2.12.1 的**默认** maxWait 是 -1（= 无限等）"
                        + " ⇒ 若断言 ⑤ 恒真，说明它没在区分「配了 4s」与「没配」")
                .isEqualTo(Duration.ofMillis(-1L));
    }

    // ==================================================================
    // ⑦⑧ Redis 超时
    // ==================================================================

    @Test
    @DisplayName("⑦ Redis TCP 连接超时必须 = 5s（Go 的 DialTimeout；"
            + "B 侧 CommentRpcRedisTimeoutContractTest.java:71-93）")
    void redisTcpConnectTimeoutMustBeFiveSeconds() {
        LettuceConnectionFactory factory = lettuceConnectionFactory();

        assertThat(clientOptions(factory).getSocketOptions().getConnectTimeout())
                .as("Redis 的 **TCP 连接**超时必须 = Go 的 DialTimeout(5s)"
                        + "（go-redis v9.20.0 options.go:338-340：`if opt.DialTimeout == 0 { opt.DialTimeout = 5 * time.Second }`）；"
                        + "Boot 3.5 的 `spring.data.redis.timeout` **不再**兼管它"
                        + "（消费点：C 侧 Boot 3.5.11 LettuceConnectionConfiguration.java:196-199 "
                        + "`getProperties().getConnectTimeout()` 非空才 `builder.socketOptions(...)`），"
                        + "不写 connect-timeout 就会落回 Lettuce 默认的 10s ⇒ **漂移 2 倍**")
                .isEqualTo(GO_DIAL_TIMEOUT);
    }

    @Test
    @DisplayName("⑧ Redis 命令超时必须 = 3s（Go 的 Read/WriteTimeout；"
            + "B 侧 CommentRpcRedisTimeoutContractTest.java:89-91）")
    void redisCommandTimeoutMustBeThreeSeconds() {
        LettuceConnectionFactory factory = lettuceConnectionFactory();

        assertThat(factory.getClientConfiguration().getCommandTimeout())
                .as("Redis 的**命令**超时必须 = Go 的 Read/WriteTimeout(3s)"
                        + "（go-redis v9.20.0 options.go:364-371：ReadTimeout 0 ⇒ 3s；"
                        + ":372-379：WriteTimeout 0 ⇒ = ReadTimeout）"
                        + "；消费点：C 侧 Boot 3.5.11 LettuceConnectionConfiguration.java:155-156 "
                        + "`builder.commandTimeout(getProperties().getTimeout())`")
                .isEqualTo(GO_COMMAND_TIMEOUT);
    }

    // ==================================================================
    // ⑨ gRPC 出向地址（三斜杠 ＋ 两斜杠必须被拒绝）
    // ==================================================================

    @Test
    @DisplayName("⑨-a 生产 yml 里的 grpc client 集合必须与守门清单一致（新增 client 必须同步进守门；"
            + "B 侧 CommentRpcProductionAddressTest.java:99-105）")
    void productionDeclaresExactlyTheGuardedClients() throws IOException {
        assertThat(productionClientAddresses().keySet())
                .as("生产 yml 里的 grpc client 集合与守门清单不一致 —— "
                        + "新增 client 时必须同步在本测试里加一条，否则它**永远不被检查**。")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CLIENTS.keySet());
    }

    @ParameterizedTest(name = "grpc.client.{0}.address 必须是三斜杠")
    @MethodSource("productionClientNames")
    @DisplayName("⑨-b 生产 application.yml 的 grpc client 地址必须是 discovery:///<service-name>（三斜杠）；"
            + "B 侧 CommentRpcProductionAddressTest.java:107-118")
    void productionAddressUsesThreeSlashes(String client) throws IOException {
        String address = productionClientAddresses().get(client);

        assertThat(address)
                .as("生产 gRPC client 地址必须是 " + EXPECTED_CLIENTS.get(client) + "。"
                        + "两斜杠（discovery://" + client + "）会让 devh 判 uri.getPath() 长度为 0 ⇒ "
                        + "启动期抛 IllegalArgumentException ⇒ 整个服务起不来；"
                        + "而现有测试都把地址覆盖成 static://，所以这条永远测不到。")
                .isEqualTo(EXPECTED_CLIENTS.get(client));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("productionClientNames")
    @DisplayName("⑨-c 生产地址必须能被 devh 真正的消费点接受（两斜杠写法必须被拒绝）；"
            + "B 侧 CommentRpcProductionAddressTest.java:120-151")
    void devhNameResolverAcceptsProductionAddressAndRejectsTwoSlash(String client) throws IOException {
        DiscoveryClientResolverFactory factory =
                new DiscoveryClientResolverFactory(mock(DiscoveryClient.class));
        NameResolver.Args args = nameResolverArgs();

        String production = productionClientAddresses().get(client);

        assertThat(factory.newNameResolver(URI.create(production), args))
                .as("生产地址 " + production + " 必须能被 devh 的 DiscoveryClientResolverFactory 接受"
                        + "（消费点：devh 3.1.0.RELEASE DiscoveryClientResolverFactory.java:76-87 —— "
                        + "取 uri.getPath()，要求非 null、长度>1、以 '/' 开头）")
                .isNotNull();

        assertThatThrownBy(() -> factory.newNameResolver(URI.create("discovery://" + client), args))
                .as("两斜杠写法必须被 devh 拒绝 —— 这条断言失败说明本守门测试已经恒真，失去意义")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incorrectly formatted target uri");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("productionClientNames")
    @DisplayName("⑨-d 生产地址解析出的服务名必须与服务发现名一致；"
            + "B 侧 CommentRpcProductionAddressTest.java:153-163")
    void productionAddressResolvesToExpectedServiceName(String client) throws IOException {
        String path = URI.create(productionClientAddresses().get(client)).getPath();

        assertThat(path)
                .as("devh 会把 path 去掉首斜杠当服务名（消费点：devh 3.1.0.RELEASE "
                        + "DiscoveryClientResolverFactory.java:84 `serviceName.substring(1)`）"
                        + " ⇒ 必须等于 Nacos 上的服务名 " + client
                        + "（Go 侧对应 etcd 键 message.rpc / article.rpc，"
                        + "service/comment/rpc/etc/comment.yaml:42-46 与 :48-52）")
                .isEqualTo("/" + client);
    }

    // ==================================================================
    // ⑩ 打包产物与源码树逐字节一致
    // ==================================================================

    @Test
    @DisplayName("⑩ target/classes 里打包的 application.yml 必须与源码树逐字节一致；"
            + "B 侧 CommentRpcProductionAddressTest.java:165-177")
    void packagedApplicationYmlMustBeByteIdenticalToSourceTree() throws IOException {
        Path source = locateModuleYml();
        Path packaged = locateModuleDir()
                .resolve("target").resolve("classes").resolve("application.yml");

        assertThat(Files.exists(packaged))
                .as("target/classes/application.yml 不存在（" + packaged + "）—— "
                        + "本次构建没跑过 resources 阶段 ⇒ 本类的产物一致性结论**不可信**。"
                        + "⚠️ 这里**故意不跳过**（B 侧用的是 Assumptions.assumeTrue 跳过）："
                        + "跳过 = 守门被静默失效 ⇒ 漂移无声通过。")
                .isTrue();

        byte[] sourceBytes = Files.readAllBytes(source);
        byte[] packagedBytes = Files.readAllBytes(packaged);

        assertThat(Arrays.equals(sourceBytes, packagedBytes))
                .as("target/classes/application.yml 与源码树**不是逐字节一致**"
                        + "（源码 " + sourceBytes.length + " 字节 / 打包 " + packagedBytes.length + " 字节）"
                        + " ⇒ 说明上一次构建产物是旧的，本类的断言结论不可信")
                .isTrue();
    }

    // ==================================================================
    // 取 Redis 真装配（读**消费点**，不是读 yml）
    // ==================================================================

    /**
     * 真装配出来的 {@link LettuceConnectionFactory}。
     *
     * <p>走的是<b>整条真实链路</b>：
     * <ol>
     *   <li>{@link ConfigDataApplicationContextInitializer} ⇒ 加载本模块<b>真实的</b>
     *       {@code application.yml}（含 {@code spring.data.redis.lettuce.pool.*}）；</li>
     *   <li>Boot <b>真实的</b> {@link RedisAutoConfiguration}
     *       ⇒ 装配出<b>真的</b> {@link LettuceConnectionFactory}；</li>
     *   <li>连接是<b>懒</b>建的（Boot 不开 eagerInitialization）⇒ <b>不需要 Redis</b>。</li>
     * </ol>
     *
     * <p>⚠️ 只注册 Redis 自动配置（不注册 DataSource）⇒ yml 里
     * {@code spring.datasource.password: ${POSTGRES_PASSWORD}} 这个<b>无默认值</b>的占位符
     * <b>不会被解析</b> ⇒ 上下文能正常起来。
     */
    private LettuceConnectionFactory lettuceConnectionFactory() {
        AtomicReference<LettuceConnectionFactory> holder = new AtomicReference<>();

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .run(context -> {
                    assertThat(context)
                            .as("Redis 自动配置必须装配成功（Boot 3.5.11 的 "
                                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration）")
                            .hasNotFailed();
                    holder.set(context.getBean(LettuceConnectionFactory.class));
                });

        LettuceConnectionFactory factory = holder.get();
        assertThat(factory)
                .as("LettuceConnectionFactory 必须取到（ApplicationContextRunner 未执行断言 ⇒ 上下文没起来）")
                .isNotNull();
        return factory;
    }

    /** 真装配出来的池配置（**读消费点**：Boot 装配出来的 {@link LettucePoolingClientConfiguration}）。 */
    private LettucePoolingClientConfiguration poolingClientConfiguration() {
        LettuceConnectionFactory factory = lettuceConnectionFactory();

        assertThat(factory.getClientConfiguration())
                .as("🔴 Lettuce 连接池必须已启用（否则读不到池参数 ⇒ 断言 ②③④⑤ 无法成立）"
                        + " ⇒ 未启用时装配出的是普通 `LettuceClientConfiguration`（共享单连接）")
                .isInstanceOf(LettucePoolingClientConfiguration.class);

        return (LettucePoolingClientConfiguration) factory.getClientConfiguration();
    }

    /** 池参数（**读消费点**：Boot 装配出来的 {@link GenericObjectPoolConfig}）。 */
    private GenericObjectPoolConfig<?> poolConfig() {
        return poolingClientConfiguration().getPoolConfig();
    }

    /** Lettuce 的 {@link ClientOptions}（**读消费点**：{@code getClientOptions()}）。 */
    private ClientOptions clientOptions(LettuceConnectionFactory factory) {
        return factory.getClientConfiguration().getClientOptions()
                .orElseThrow(() -> new AssertionError("LettuceClientConfiguration 没有 ClientOptions"));
    }

    // ==================================================================
    // 取生产配置（读**文件**，不经过 @SpringBootTest(properties=…) 覆盖）
    // ==================================================================

    /** 参数化用例的数据源：<b>yml 里实际存在的全部</b> grpc client 名。 */
    static Stream<String> productionClientNames() throws IOException {
        return productionClientAddresses().keySet().stream();
    }

    /** 读<b>生产</b> application.yml 里的全部 {@code grpc.client.*.address}。 */
    private static Map<String, String> productionClientAddresses() throws IOException {
        return readClientAddresses(locateModuleYml());
    }

    private static Map<String, String> readClientAddresses(Path yml) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new FileSystemResource(yml));
        for (PropertySource<?> source : sources) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith("grpc.client.") || !name.endsWith(".address")) {
                    continue;
                }
                Object value = source.getProperty(name);
                if (value == null) {
                    continue;
                }
                String client = name.substring("grpc.client.".length(), name.length() - ".address".length());
                out.put(client, value.toString());
            }
        }
        assertThat(out)
                .as("application.yml 里没有 grpc.client.*.address —— 生产 client 无地址，服务起不来：" + yml)
                .isNotEmpty();
        return out;
    }

    /** devh 的 {@link NameResolver.Args}（与 B 侧逐字段一致）。 */
    private static NameResolver.Args nameResolverArgs() {
        return NameResolver.Args.newBuilder()
                .setDefaultPort(50051)
                .setProxyDetector(target -> null)
                .setSynchronizationContext(new SynchronizationContext((d, e) -> { }))
                // ServiceConfigParser 是**抽象类**（不是函数接口），必须匿名子类
                .setServiceConfigParser(new NameResolver.ServiceConfigParser() {
                    @Override
                    public NameResolver.ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
                        return null;
                    }
                })
                .build();
    }

    // ==================================================================
    // 定位本模块的 application.yml
    // ==================================================================

    /**
     * 向上找本模块的 {@code src/main/resources/application.yml}。
     *
     * <p>Surefire 的工作目录默认是模块目录（{@code ${basedir}}），但从仓库根跑 {@code mvn -pl} 时也可能不同
     * ⇒ 逐级向上找。
     *
     * <p>⚠️ <b>与 B 侧的差别</b>：B 侧靠「路径里含 {@code comment-rpc}」来排除别的模块；
     * C 侧模块目录就叫 {@code comment} ⇒ 「路径含 comment」会命中一堆祖先目录
     * ⇒ 改为<b>要求 pom 的 artifactId 是 {@code comment-service}</b> ⇒ 定位唯一。
     */
    private static Path locateModuleYml() {
        return locateModuleDir().resolve("src").resolve("main").resolve("resources").resolve("application.yml");
    }

    /** 本模块根目录（由 pom 的 artifactId 唯一确定）。 */
    private static Path locateModuleDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10 && dir != null; i++) {
            Path candidate = dir.resolve("src").resolve("main").resolve("resources").resolve("application.yml");
            Path pom = dir.resolve("pom.xml");
            if (Files.exists(candidate) && Files.exists(pom) && isThisModule(pom)) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "找不到 artifactId=" + MODULE_ARTIFACT_ID + " 的模块根目录（工作目录："
                        + Paths.get("").toAbsolutePath() + "）");
    }

    /** 该 pom 是不是本模块（看 artifactId，而不是看路径字符串）。 */
    private static boolean isThisModule(Path pom) {
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            return text.contains("<artifactId>" + MODULE_ARTIFACT_ID + "</artifactId>");
        } catch (IOException e) {
            return false;
        }
    }
}
