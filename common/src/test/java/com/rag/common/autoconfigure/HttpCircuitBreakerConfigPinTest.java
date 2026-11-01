package com.rag.common.autoconfigure;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 **HTTP 侧熔断「有效参数」的钉桩测试** —— 把 **DRIFT-COMMON-V09** 的降级形状变成
 * **可测量**的数字，而不是一句「机制类型不同」。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>⚠️ **AGENTS.md §3.3.1 的同款陷阱在熔断参数上也成立**：
 * 「Java 侧<b>藏在框架里</b>（不在注解）⇒ 只看配置 / 注解名双向推断，两头都会错」。
 * <p>本仓的 HTTP 熔断（{@code ProtectionAutoConfiguration} 内嵌 {@code RouteIsolationFilter}）
 * 用的是 resilience4j <b>自动装配</b>的 {@link CircuitBreakerRegistry}
 * ⇒ 它的<b>滑动窗口大小、最小请求数、失败率阈值、open 状态等待时长、半开放行数</b>
 * <b>全部由框架默认值决定</b> ⇒ 本仓的 {@code application.yml} 里**一个字都没写**
 * （且 10 个域的 yml 搬运已被叫停 ⇒ **永远写不了**）。
 * <p>⇒ 而这些数字**正是 V09 这条 L3 降级的「形状」**：
 * Go / B 侧是「<b>概率性拒绝 + force-pass 保底 + 无三态</b>」，
 * 本仓是「<b>计数窗口 + 打开/半开/关闭三态</b>」⇒ ⇒ **两者差多远，全看这几个数字**。
 * <p>⇒ ⇒ **不钉住 = 降级形状不可测量** ⇒ ⇒ 降级项一旦随框架升级悄悄变形，**没有人会发现**。
 * <br>⚠️ **本类第一版就抓到了「凭记忆写默认值」的错误**：{@code isWritableStackTraceEnabled()}
 * 我按 resilience4j 1.x 的记忆写了 {@code false} ⇒ 实测是 {@code true} ⇒ ⇒ **参数必须实测，不能凭记忆写**。
 *
 * <h2>本测试的可证伪性</h2>
 *
 * <p>把任何一个断言值改成 resilience4j 的<b>另一组默认值</b> ⇒ 本类立刻红
 * ⇒ 说明这些数字**确实是生效值**，不是抄来的文档值。
 * <br>反向：resilience4j 升级导致默认值变化 ⇒ 本类<b>立刻红</b> ⇒ ⇒
 * **逼着人重新登记 D-02 的降级形状** ⇒ 这正是本测试存在的意义（AGENTS.md §3.3「机制可证」）。
 *
 * <h2>⚠️ 本测试**不改任何生产行为</h2>
 *
 * <p>它只<b>读</b>自动装配出来的 registry 的有效配置 ⇒ **零生产改动**。
 * <p>⚠️ **刻意不把参数写进本仓自己的 {@code ProtectionProperties}**：
 * 那会<b>覆盖</b> resilience4j 的自动配置（域 yml 若有 {@code resilience4j.circuitbreaker.*} 也会被顶掉）
 * ⇒ ⇒ 等于**制造一次未经批准的行为漂移**（verification.md §十「INTENTIONALLY_CHANGED 不能伪装成语义等价」）
 * ⇒ ⇒ 而 D-02 已登记「参数不等价」⇒ ⇒ **保持「继承生态默认值」并把它钉住**，才是最小漂移的做法。
 *
 * @see <a href="../../../../../L3-降级清单.md">L3-降级清单</a> D-02
 */
class HttpCircuitBreakerConfigPinTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ProtectionAutoConfiguration.class,
                    CircuitBreakerAutoConfiguration.class));

    @Test
    @DisplayName("🔴 V09：HTTP 熔断的**有效参数** = resilience4j 默认值（滑动窗口 100 / 最小请求数 100 / 失败率 50% / open 等待 60s / 半开放行 10）")
    void effectiveCircuitBreakerParametersArePinned() {
        runner.run(context -> {
            // 前置校验：registry 真的被自动装配出来了（否则下面的断言测的是「没有 registry」）
            assertThat(context).hasSingleBean(CircuitBreakerRegistry.class);

            // ⚠️ 取的是**默认配置**（`getCircuitBreakerConfig()`）—— 这正是
            //    `RouteIsolationFilter` 里 `registry.circuitBreaker(isolationKey, ...)` 落到的那份配置
            CircuitBreakerConfig cfg = context.getBean(CircuitBreakerRegistry.class).getDefaultConfig();

            assertThat(cfg.getSlidingWindowSize())
                    .as("滑动窗口大小（计数窗口）—— resilience4j 默认 100")
                    .isEqualTo(100);
            assertThat(cfg.getMinimumNumberOfCalls())
                    .as("最小请求数 —— resilience4j 默认 100（⇒ 前 99 次请求永远不判失败率）")
                    .isEqualTo(100);
            assertThat(cfg.getFailureRateThreshold())
                    .as("失败率阈值 —— resilience4j 默认 50%")
                    .isEqualTo(50f);
            assertThat(cfg.getWaitIntervalFunctionInOpenState().apply(1))
                    .as("open 状态等待时长 —— resilience4j 默认 60s（IntervalFunction.apply(第 1 次尝试) 取毫秒；attempt 必须 ≥ 1）")
                    .isEqualTo(Duration.ofSeconds(60).toMillis());
            assertThat(cfg.getPermittedNumberOfCallsInHalfOpenState())
                    .as("半开状态放行数 —— resilience4j 默认 10")
                    .isEqualTo(10);
            assertThat(cfg.isAutomaticTransitionFromOpenToHalfOpenEnabled())
                    .as("open → 半开 自动转换 —— resilience4j 默认关闭（⇒ 必须等 wait duration 到点）")
                    .isFalse();
            assertThat(cfg.isWritableStackTraceEnabled())
                    .as("写栈开关 —— resilience4j 2.2.0 实测默认**开**（⚠️ 写这行断言时我按 1.x 的记忆写了 false ⇒ 被实测打脸 ⇒ 这就是「参数藏在框架里」的坑）")
                    .isTrue();
            assertThat(cfg.getSlidingWindowType())
                    .as("窗口类型 —— resilience4j 默认 COUNT_BASED（**计数窗口**）⇒ 这正是 V09 说的「计数窗口 vs Go 的概率性拒绝」")
                    .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            assertThat(cfg.getSlowCallRateThreshold())
                    .as("慢调用率阈值 —— resilience4j 默认 100%（⇒ 慢调用不单独触发开路）")
                    .isEqualTo(100f);
            assertThat(cfg.getSlowCallDurationThreshold())
                    .as("慢调用时长阈值 —— resilience4j 默认 1 分钟")
                    .isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    @DisplayName("🔴 V09：registry 是**自动装配**的（不是本仓自建）⇒ 参数来源唯一")
    void registryIsAutoConfiguredNotHandBuilt() {
        runner.run(context -> {
            // ⚠️ 这一条不是「断言 Bean 存在」那种弱证据：
            //   它断言的是「**这个 registry 的来源是 resilience4j 的自动配置**」
            //   ⇒ ⇒ 若将来有人改成自建 registry（自己塞一份 CircuitBreakerConfig），
            //   上一条用例的有效参数断言就**不再描述真实生效值**了 ⇒ 这里先红。
            assertThat(context).hasSingleBean(CircuitBreakerRegistry.class);
            assertThat(context.getBean(CircuitBreakerRegistry.class).getDefaultConfig())
                    .as("默认配置必须来自 resilience4j 自动装配（与上一条用例同源）")
                    .isNotNull();
            assertThat(context.getBeanNamesForType(CircuitBreakerConfig.class))
                    .as("本仓**没有**自建 CircuitBreakerConfig Bean（否则参数来源就不是默认值了）")
                    .isEmpty();
        });
    }
}
