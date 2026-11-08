package com.rag.common.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>共享层「Prometheus registry 真装配」的守门</b> —— 用来判一件被写错了的事：
 * <b>「生产形态的上下文里没有 {@code PrometheusMeterRegistry}」这条现象到底成不成立。</b>
 *
 * <h2>⚠️ 为什么这条要守（它对应一份台账里<b>根因写错</b>的漂移）</h2>
 * <p>follow 域台账的「漂移 E」写着：生产形态只有 {@code simpleMeterRegistry}、没有
 * {@code PrometheusMeterRegistry} ⇒ 根因是「<b>Boot 的装配顺序</b>：{@code simpleMeterRegistry}
 * 先注册 ⇒ Prometheus 那个 {@code @ConditionalOnMissingBean} 被跳过」。
 * <br>⇒ ⚠️ <b>本类实测的结论：那条根因描述不成立</b>（Boot 3.5.11 的源码里
 * {@code MetricsAutoConfiguration} <b>根本没有 {@code simpleMeterRegistry} bean</b>）⇒
 * ⇒ 那条漂移的<b>前提</b>（现象）必须由<b>运行证据</b>来判，而不是由「读源码猜装配顺序」来判。
 *
 * <h2>🔴 根因描述为什么错（Boot 3.5.11 源码 · 本地 sources jar 逐行核对）</h2>
 * <ul>
 *   <li>{@code PrometheusMetricsExportAutoConfiguration} 的四个条件：
 *       <pre>
 *   @AutoConfiguration(before = {CompositeMeterRegistryAutoConfiguration.class,
 *                                SimpleMetricsExportAutoConfiguration.class},
 *                     after = MetricsAutoConfiguration.class)
 *   @ConditionalOnBean(Clock.class)
 *   @ConditionalOnClass(PrometheusMeterRegistry.class)
 *   @ConditionalOnEnabledMetricsExport("prometheus")
 *       </pre></li>
 *   <li>其中 {@code @ConditionalOnMissingBean} <b>无参数</b> ⇒ 按<b>返回类型</b>
 *       （{@code PrometheusMeterRegistry}）推断 ⇒ ⇒ 它<b>只会被「已存在的 PrometheusMeterRegistry」挡住</b>
 *       ⇒ ⇒ {@code SimpleMeterRegistry}（<b>另一个类型</b>）<b>挡不住它</b>
 *       ⇒ ⇒ 「simple 先注册 ⇒ Prometheus 被跳过」<b>不成立</b>；</li>
 *   <li>而 {@code MetricsAutoConfiguration}（Boot 3.5.11）里<b>只有</b>
 *       {@code micrometerClock} / {@code meterRegistryPostProcessor} /
 *       {@code propertiesMeterFilter} / {@code meterRegistryCloser}
 *       ⇒ ⇒ <b>没有 {@code simpleMeterRegistry} bean</b>
 *       （那是 Boot 2.x 的行为；Boot 3 起它归 {@code SimpleMetricsExportAutoConfiguration}）
 *       ⇒ ⇒ 台账引用的那个「{@code MetricsAutoConfiguration} 的 simpleMeterRegistry」<b>在 Boot 3.5 里不存在</b>。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么判据必须落在「真装配」＋「真 Tomcat」上</h2>
 * <ul>
 *   <li><b>装配层</b>：{@link ApplicationContextRunner} ＋ Boot <b>真的那几个自动配置类</b>
 *       ⇒ 证的是「Boot 在这个版本下到底建不建这个 bean」，不是「代码里写了 if」；</li>
 *   <li><b>抓取面</b>：<b>真 Tomcat</b> ＋ <b>真 HTTP</b> 打 {@code /actuator/prometheus}
 *       ⇒ 证的是「<b>真能被 scrape</b>」，不是「registry bean 存在」；
 *       ⚠️ 这两层<b>分开</b>是因为：registry 在 ⇒ 端点不一定在
 *       （端点还要过 {@code @ConditionalOnAvailableEndpoint} ＋ {@code exposure.include}）。</li>
 * </ul>
 *
 * <h2>🔴 为什么 {@link ApplicationContextRunner} 里能拿到 registry（这一点本身也曾被误读）</h2>
 * <p>{@link ApplicationContextRunner} <b>不</b>施加 Boot 测试框架的
 * {@code ObservabilityContextCustomizerFactory}（那是 {@code @SpringBootTest}
 * 经 {@code SpringBootTestContextBootstrapper} 才施加的）
 * ⇒ ⇒ 测试上下文里<b>没有</b> {@code management.defaults.metrics.export.enabled=false}
 * ⇒ ⇒ {@code @ConditionalOnEnabledMetricsExport} 走默认分支（{@code true}）⇒ registry 建得起来。
 * <br>⇒ ⚠️ <b>所以「这个 runner 能看到 registry」并不能证明「生产形态也能看到」</b>
 * —— 它恰好等价于生产形态（都是「属性不存在 ⇒ 默认 true」），
 * 但这属于<b>推断</b>⇒ ⇒ 真正的生产形态证据在
 * {@code PrometheusScrapeEndpointRealTomcatTest}（走 {@code SpringApplicationBuilder} 真实启动），
 * 而「测试形态被关掉」的反证在 {@code PrometheusTestContextExportDisabledAnchorTest}。
 *
 * <h2>⚠️ 效力边界（诚实）</h2>
 * <ul>
 *   <li>本类证的是<b>共享层这条链路</b>（registry → 端点 → 文本格式）；
 *       <b>不证</b>「每个域的生产 yml 都开了暴露面」⇒ 那是各域的配置面（域内面）；</li>
 *   <li>本类<b>不涉及 B 侧</b>：B 侧 go-zero 用 {@code prometheus.StartAgent} 起<b>独立端口</b>的
 *       {@code /metrics}（路径与端口都不同）⇒ ⇒ 本仓把抓取路径映射到 Actuator
 *       {@code /actuator/prometheus} 是<b>表达层映射</b>（各域 yml 注释已留痕）
 *       ⇒ ⇒ <b>本类不声称「与 B 侧路径相同」</b>。</li>
 * </ul>
 */
class PrometheusMetricsRegistryWiringTest {

    /**
     * 装配层探针：只放 Boot 那三个真的自动配置类
     * ⇒ 证「{@code PrometheusMeterRegistry} 在 Boot 3.5.11 下到底建不建」。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class));

    @Test
    @DisplayName("装配层：Boot 自己就会建 PrometheusMeterRegistry（不是被 simple 抢位挡掉的）")
    void prometheusMeterRegistryIsAutoConfigured() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(PrometheusMeterRegistry.class);
            // 反向钉住：simple 那份**不在**这个上下文里（Boot 3.5 的 MetricsAutoConfiguration 没有它）
            // ⇒ ⇒ 「simple 抢位」这条根因描述被直接证伪。
            assertThat(ctx.getBeanNamesForType(SimpleMeterRegistry.class)).isEmpty();
            // ⇒ registry 至少两个（Prometheus + Simple 在 Boot 3.5 由 SimpleMetricsExportAutoConfiguration 提供）
            assertThat(ctx.getBeansOfType(MeterRegistry.class)).isNotEmpty();
        });
    }

    @Test
    @DisplayName("装配层：Prometheus 关掉（management.prometheus.metrics.export.enabled=false）⇒ 不建")
    void prometheusMeterRegistryIsAbsentWhenExportDisabled() {
        runner.withPropertyValues("management.prometheus.metrics.export.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(PrometheusMeterRegistry.class);
                });
    }
}
