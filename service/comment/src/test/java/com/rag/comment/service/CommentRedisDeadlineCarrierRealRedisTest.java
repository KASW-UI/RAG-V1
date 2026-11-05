package com.rag.comment.service;

import com.rag.common.observability.GoStyleRequestDeadline;
import com.rag.common.redis.DeadlineConnectionFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>🔴 <b>Redis 期限载体（deadline carrier）在真 Redis 上的活体判据</b>：
 * 载体已耗尽 ⇒ <b>写命令根本没下发</b>；载体充足 ⇒ <b>写得进</b>（负控）；
 * 事务绑定路径（{@code redis.execute(SessionCallback)}）下 ⇒ <b>内层</b>写命令<b>也</b>被拦。
 *
 * <h3>守的是什么</h3>
 * <p>B 侧那条用例守的是「共享层那个 Redis 期限载体，在<b>本域的真实容器</b>里到底装上了没有」
 * ⇒ ⇒ 具体是两条<b>活体</b>判据：
 * <ol>
 *   <li><b>耗尽 ⇒ 不下发</b>：绑一个<b>已过期</b>的载体 ⇒ {@code set} 必须抛
 *       {@link QueryTimeoutException} ⇒ ⇒ 且<b>读回来还是基线值</b>
 *       ⇒ ⇒ <b>观测量是「值有没有变」，不是靠异常类型推断</b>
 *       （B 侧原话：命令<b>根本没下发</b>，不是发出去再报错）；</li>
 *   <li><b>事务绑定路径也拦得住</b>：{@code redis.execute(SessionCallback)} ⇒ 连接被<b>事务绑定</b>
 *       ⇒ ⇒ <b>不再走</b>「建连之前」那道检查 ⇒ ⇒ 只有<b>每命令一次</b>的检查能兜住。</li>
 * </ol>
 *
 * <h3>🔴 C 侧为什么必须落在「真 Redis 连接」上</h3>
 * <p>C 侧 comment 域用的是<b>标准 {@link StringRedisTemplate}</b>
 * （{@code service/comment/src/main/java/com/rag/comment/service/CommentCacheService.java:115} 注入它）
 * ⇒ ⇒ 「载体耗尽」这件事必须发生在<b>真连接</b>上才作数
 * ⇒ ⇒ 本类起<b>真 Redis 容器</b>（不绑固定宿主端口 ⇒ 不与其他会话抢口），
 * 并用 Boot 的 {@code RedisAutoConfiguration} ＋ 共享层的 {@code DeadlineAutoConfiguration}
 * 装配出<b>生产同款</b>的 {@code StringRedisTemplate}。
 *
 * <h3>⚠️ 与共享层已有覆盖的分工</h3>
 * <p>共享层 {@code common/src/test/java/com/rag/common/redis/RedisAutoConfigurationActivationTest:68-103}
 * 已覆盖「包装版 factory ＋ 幂等不二次包装」<b>两条</b>
 * （= 台账里的 {@code COVERED_BY_EQUIVALENT}）⇒ ⇒ <b>本类不重复写那两条</b>
 * ⇒ ⇒ 本类只补<b>上面两条活体判据</b>。
 * <p>⚠️ 但「装配面」的前置条件（{@code StringRedisTemplate} 的 factory <b>确实是</b>包装版）
 * 在本类里仍会<b>顺手验一次</b> —— 因为它是这两条活体判据<b>成立的前提</b>
 * ⇒ ⇒ 若前置不成立，E-a 的「拦住了」可能只是「压根没走 Redis」⇒ ⇒ 必须当场钉住。
 *
 * <h3>三段式（AGENTS.md §3.2）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>落点从「域内自写的 {@code @Import} 清单」改成「共享层的自动配置」</b>
 *             （D1 合并 + D3 starter 化）⇒ ⇒ B 侧那条「Application 必须 {@code @Import}
 *             保护配置」的用例在 C 侧是 <b>N_A_TO_C（刻意不存在）</b> ⇒ ⇒ <b>不移植它</b>。</li>
 *         <li><b>「装配面」那条断言降级为前置条件</b>（共享层已覆盖）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 期望值（耗尽 ⇒ 抛 {@link QueryTimeoutException} ＋ 值不变；
 *       充足 ⇒ 写得进；事务绑定路径 ⇒ 内层写也被拦）逐条来自 B 侧。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRedisDeadlineCarrierWiringGateTest.java:209-244}
 *             （E-a：耗尽不写 ＋ 充足写进的负控）</li>
 *         <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRedisDeadlineCarrierWiringGateTest.java:246-278}
 *             （E-b：事务绑定路径）</li>
 *         <li>共享层落点：{@code common/src/main/java/com/rag/common/redis/DeadlineConnectionFactory.java:48-66}
 *             （<b>消费点</b>：{@code getConnection()} 里的建连前检查）＋
 *             {@code common/src/main/java/com/rag/common/redis/DeadlineConnection.java:64-73}
 *             （<b>消费点</b>：每命令一次的检查）＋
 *             {@code common/src/main/java/com/rag/common/redis/Deadline.java:167-180}
 *             （{@code assertNotExhausted} 抛 {@link QueryTimeoutException}）</li>
 *         <li>Go 侧：{@code service/comment/rpc/internal/cache/comment_subject_cache.go:76-103}
 *             （ctx 一路传到 Redis 命令）＋ go-redis v9.20.0
 *             {@code internal/pool/pool.go:1098-1104}（{@code waitTurn} 的 fast path 先查
 *             {@code ctx.Done()} ⇒ <b>命令根本不下发</b>）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>，不静默跳过）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
class CommentRedisDeadlineCarrierRealRedisTest {

    /** 真 Redis 容器（<b>不绑固定宿主端口</b> ⇒ 用容器映射口 ⇒ 不与任何会话抢口）。 */
    private static GenericContainer<?> redis;

    /** 本类的两个探针 key（分开的 ⇒ 两条用例互不干扰）。 */
    private static final String KEY_PLAIN = "comment-deadline-carrier-plain";
    private static final String KEY_TX = "comment-deadline-carrier-tx";

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    /**
     * 装配「生产同款」的 {@link StringRedisTemplate}：
     * Boot 的 Redis 自动配置（真连接 ＋ 真池）＋ 共享层的期限自动配置（把 factory 换成预算感知版）。
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisAutoConfiguration.class,
                        com.rag.common.redis.DeadlineAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379),
                        // ⚠️ 与本域 application.yml:60-61 的 `timeout: 3s` 同值（Go 侧 go-redis ReadTimeout = 3s）
                        "spring.data.redis.timeout=3s");
    }

    // ====================================================================================
    // E-a：载体耗尽 ⇒ 写命令根本没下发；载体充足 ⇒ 写得进（负控）
    // ====================================================================================

    @Test
    @DisplayName("🔴 E-a：载体已耗尽 ⇒ 写命令**根本没下发**（读回来还是基线值）"
            + " ＋ 载体充足 ⇒ 写得进（负控）")
    void exhaustedCarrierBlocksTheWriteWhileAliveCarrierWrites() {
        runner().run(context -> {
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

            // ---- 前置条件：装配面（共享层的 BPP 必须真的把 factory 换掉了）
            //      ⇒ 若前置不成立，下面「拦住了」可能只是「压根没走 Redis」。
            assertThat(template.getConnectionFactory())
                    .as("🔴 前置条件：共享层的 BeanPostProcessor 必须**真的**把 StringRedisTemplate 的"
                            + " connection factory 换成预算感知版"
                            + " ⇒ ⇒ 否则本域所有经 RedisTemplate 的缓存读写**根本没有 deadline 闸门**")
                    .isInstanceOf(DeadlineConnectionFactory.class);

            String baseline = "baseline";
            template.opsForValue().set(KEY_PLAIN, baseline);

            // ---- ① 载体已耗尽 ⇒ 必须拦下，且**值没变**
            try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                    System.nanoTime() - 1_000_000L)) {
                assertThatThrownBy(() -> template.opsForValue().set(KEY_PLAIN, "overwritten"))
                        .as("载体已耗尽 ⇒ 必须抛 QueryTimeoutException"
                                + "（共享层 `Deadline.assertNotExhausted` 在**取连接**这一步拦）")
                        .isInstanceOf(QueryTimeoutException.class);
            }

            assertThat(template.opsForValue().get(KEY_PLAIN))
                    .as("🔴 关键观测量：**读回来还是基线值** ⇒ ⇒ 命令**根本没下发**"
                            + "（不是发出去再报错）⇒ ⇒ 与 Go 侧「到点即止」同形")
                    .isEqualTo(baseline);

            // ---- ② 负控：载体充足 ⇒ 同一个 key 必须写得进
            //    ⇒ 否则第 ① 条可能只是「Redis 挂了 / 键名错了 / 序列化坏了」
            try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                    System.nanoTime() + 5_000_000_000L)) {
                template.opsForValue().set(KEY_PLAIN, "written");
            }

            assertThat(template.opsForValue().get(KEY_PLAIN))
                    .as("负控：载体充足时必须**写得进** ⇒ ⇒ 第 ① 条的红是「闸门生效」而不是「环境坏了」")
                    .isEqualTo("written");
        });
    }

    // ====================================================================================
    // E-b：事务绑定路径（redis.execute(SessionCallback)）⇒ 内层写命令也被拦
    // ====================================================================================

    @Test
    @DisplayName("🔴 E-b：事务绑定路径（redis.execute(SessionCallback)）：载体耗尽 ⇒ 内层写命令**也**被拦（值不得变）")
    void exhaustedCarrierAlsoBlocksSessionCallbackWrites() {
        runner().run(context -> {
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

            String baseline = "baseline";
            template.opsForValue().set(KEY_TX, baseline);

            // 🔴 为什么必须单独测这一条（不是「重复测一遍」）：
            //   `redis.execute(SessionCallback)` ⇒ 连接被**事务绑定** ⇒ 复用同一条连接
            //   ⇒ ⇒ **不再走** `getConnection()` 里那道「建连之前」的检查
            //   ⇒ ⇒ 只有**每命令一次**的检查（`DeadlineConnection`）能兜住
            //   ⇒ ⇒ 而本域 `CommentCacheService.java:506` / `:606`
            //       **两条写路径正是这个形态** ⇒ ⇒ 不测这一条 = 台账那句「写路径不再照写不误」
            //       **少一半证据**。
            try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                    System.nanoTime() - 1_000_000L)) {
                assertThatThrownBy(() -> template.execute(new SessionCallback<Object>() {
                    @Override
                    @SuppressWarnings("rawtypes")
                    public Object execute(RedisOperations operations) {
                        operations.opsForValue().set(KEY_TX, "overwritten");
                        return null;
                    }
                }))
                        .as("事务绑定路径**不走**「建连之前」那道检查 ⇒ ⇒ 必须靠**每命令一次**的检查兜住"
                                + " ⇒ ⇒ 与 Go 侧「每条命令都过一次 waitTurn」同形")
                        .isInstanceOf(QueryTimeoutException.class);
            }

            assertThat(template.opsForValue().get(KEY_TX))
                    .as("🔴 关键观测量：值**没变** ⇒ ⇒ 事务绑定路径下的内层写命令**根本没下发**")
                    .isEqualTo(baseline);
        });
    }
}
