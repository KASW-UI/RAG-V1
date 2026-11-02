package com.rag.follow.support;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>测试上下文里注入「真 Prometheus registry」</b> —— 让 Micrometer 真正吐出
 * <b>Prometheus 文本格式</b>（{@code _bucket{le="..."}} 那些）。
 *
 * <h2>⚠️ 为什么需要它（一个实测到的现状）</h2>
 * <p>在 <b>生产形态</b>的上下文里，{@code @SpringBootTest} 只能看到一个
 * {@code io.micrometer.core.instrument.simple.SimpleMeterRegistry}
 * （bean 名 {@code simpleMeterRegistry}）⇒ <b>没有</b> {@code PrometheusMeterRegistry}。
 * <p>根因（Boot 3.5 的装配顺序）：{@code SimpleMetricsExportAutoConfiguration#simpleMeterRegistry}
 * 与 {@code PrometheusMetricsExportAutoConfiguration#prometheusMeterRegistry} 都是
 * {@code @ConditionalOnMissingBean}，而 Prometheus 那个带
 * {@code @AutoConfigureAfter({MetricsAutoConfiguration, SimpleMetricsExportAutoConfiguration})}
 * ⇒ <b>simple 先注册</b> ⇒ Prometheus 那个被跳过。
 * <p>⇒ 本类用「<b>测试作用域</b>的 {@code @TestConfiguration}</b>」补一个
 * {@link PrometheusMeterRegistry}：
 * <ul>
 *   <li>测试配置在 auto-configuration <b>之前</b>注册 ⇒ Boot 的
 *       {@code prometheusMeterRegistry}（{@code @ConditionalOnMissingBean}）被跳过；</li>
 *   <li>Boot 的 {@code simpleMeterRegistry}（同样 {@code @ConditionalOnMissingBean}）也被跳过
 *       ⇒ 上下文里<b>只有一个</b> registry，注入点不会歧义。</li>
 * </ul>
 *
 * <h2>⚠️ 这<b>不</b>掩盖主代码的桶配置</h2>
 * <p>被测的是 {@code FollowRpcMetrics#observeRpc} 里那串
 * {@code serviceLevelObjectives(...)} —— 它决定 Timer 的<b>桶边界</b>；
 * registry 类型只决定「用什么文本格式把边界吐出来」。
 * ⇒ 本类注入 Prometheus registry ⇒ 拿到的 {@code le="..."} 就是主代码那串 SLO 边界的真实渲染。
 *
 * <p>⚠️ 「生产上下文没有 Prometheus registry ⇒ {@code /actuator/prometheus} 不可用」
 * 这条<b>属于主代码/配置层的现状</b>，已写入交付报告（不在本测试里固化成断言），
 * 避免把一个疑似缺陷钉成「期望行为」。
 */
@TestConfiguration(proxyBeanMethods = false)
public class PrometheusTestRegistryConfiguration {

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
