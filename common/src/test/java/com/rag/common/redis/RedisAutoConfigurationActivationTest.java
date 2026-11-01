package com.rag.common.redis;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>Redis 侧两个自动配置真的被激活</b>（DRIFT-COMMON-V01 的修法验证）。
 *
 * <p>⚠️ <b>为什么必须有这条测试</b>：
 * 这两个类在批 1 就<b>实现完整</b>（{@code ClientAutoConfiguration} 146 行 /
 * {@code DeadlineAutoConfiguration} 98 行，Boot 3.5 的包名也已回改）
 * ⇒ 但它们<b>不在</b> {@code AutoConfiguration.imports} ⇒ <b>永不加载</b>
 * ⇒ ⇒ Lettuce 断连拒单（Go 的 fail-fast 语义）与「请求预算耗尽 ⇒ 不下发 Redis 命令」
 * <b>双双静默失效</b>，且<b>编译期不报错、启动期不报错</b>。
 * <br>⇒ ⇒ 修法 = 登记进 imports 清单；本测试断言登记之后<b>行为真的生效</b>。
 *
 * <p>⚠️ <b>「Bean 存在」不等于「行为生效」</b> ⇒ 本测试不止断言 Bean 存在，
 * 还<b>真的调</b> customizer 并断言断连行为值。
 *
 * <p>⚠️ <b>本测试与「注册」守门是分工的，不要混淆</b>：
 * <ul>
 *   <li>本测试用 {@code ApplicationContextRunner.withConfiguration(AutoConfigurations.of(...))}
 *       ⇒ 它<b>显式</b>注册这两个类 ⇒ 证明的是「<b>一旦被激活，行为是对的</b>」；</li>
 *   <li>「<b>它们有没有被激活</b>」由
 *       {@code AutoConfigurationImportsTest#everyAutoConfigurationHasAnActivationPath} 负责
 *       ⇒ 那是<b>源码级</b>的不变式（每个 {@code @AutoConfiguration} 类都必须有激活路径）。</li>
 * </ul>
 * <br>⇒ ⇒ <b>变异实测</b>（2026-11-02）：把这两行从 imports 清单里摘掉 ⇒
 * 本测试<b>仍然绿</b>（它显式注册了类）⇒ <b>但激活路径不变式立刻变红</b>
 * ⇒ ⇒ 两者合起来才覆盖「注册 + 行为」两半 ⇒ <b>缺一不可</b>。
 */
class RedisAutoConfigurationActivationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ClientAutoConfiguration.class,
                    DeadlineAutoConfiguration.class));

    @Test
    @DisplayName("🔴 V01：Lettuce 断连 customizer 真的在容器里，且真的把断连行为设成 REJECT_COMMANDS")
    void lettuceCustomizerIsActivatedAndSetsRejectCommands() {
        runner.run(context -> {
            assertThat(context).hasBean("goStyleRedisClientOptionsCustomizer");

            LettuceClientOptionsBuilderCustomizer customizer =
                    context.getBean(LettuceClientOptionsBuilderCustomizer.class);
            // ⚠️ 真的调一次 customizer ⇒ 断言的是**行为**，不是「有个 Bean」
            // ⚠️ Boot 3.5 的 customize(...) 返回 void（就地改 Builder）⇒ 要自己 build()
            ClientOptions.Builder builder = ClientOptions.builder();
            customizer.customize(builder);
            ClientOptions options = builder.build();

            assertThat(options.getDisconnectedBehavior())
                    .as("Go 侧「Redis 失败 ⇒ 尽快失败 ⇒ 上层 fail-open」⇒ 断连即拒单")
                    .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        });
    }

    @Test
    @DisplayName("🔴 V01：RedisTemplate 的 connection factory 真的被换成预算感知包装版")
    void redisTemplateGetsDeadlineAwareConnectionFactory() {
        runner.withBean("redisTemplate", RedisTemplate.class, () -> {
                    RedisTemplate<Object, Object> template = new RedisTemplate<>();
                    // ⚠️ 只造 factory、不建连接 ⇒ 不碰网络
                    template.setConnectionFactory(new LettuceConnectionFactory("localhost", 6379));
                    return template;
                })
                .run(context -> {
                    RedisTemplate<?, ?> template = context.getBean("redisTemplate", RedisTemplate.class);
                    RedisConnectionFactory factory = template.getConnectionFactory();

                    assertThat(factory)
                            .as("BPP 没生效 ⇒ 预算耗尽后仍会下发 Redis 命令（与 Go 相反）")
                            .isInstanceOf(DeadlineConnectionFactory.class);
                });
    }

    @Test
    @DisplayName("🔴 V01：已经是包装版的 factory 不被二次包装（幂等）")
    void alreadyWrappedFactoryIsNotWrappedTwice() {
        runner.withBean("redisTemplate", RedisTemplate.class, () -> {
                    RedisTemplate<Object, Object> template = new RedisTemplate<>();
                    template.setConnectionFactory(
                            new DeadlineConnectionFactory(new LettuceConnectionFactory("localhost", 6379), true));
                    return template;
                })
                .run(context -> {
                    RedisTemplate<?, ?> template = context.getBean("redisTemplate", RedisTemplate.class);
                    assertThat(template.getConnectionFactory()).isInstanceOf(DeadlineConnectionFactory.class);
                    assertThat(((DeadlineConnectionFactory) template.getConnectionFactory()).delegate())
                            .as("二次包装会让 delegate 变成包装版 ⇒ 预算检查套娃")
                            .isNotInstanceOf(DeadlineConnectionFactory.class);
                });
    }
}
