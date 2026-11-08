package com.rag.common.autoconfigure;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>诊断锚点</b>：把「<b>测试形态下 {@code /actuator/prometheus} 404</b>」的<b>真因</b>钉死。
 *
 * <h2>⚠️ 这条测试<b>断言的是「缺失」</b> —— 它守的是<b>诊断结论</b>，不是产品需求</h2>
 * <p>Boot 3.5.11 的 {@code spring-boot-test-autoconfigure} 里
 * {@code ObservabilityContextCustomizerFactory} 的 javadoc 明写
 * 「<b>globally disables metrics export and tracing in tests</b>」
 * ⇒ ⇒ 它给测试上下文注入 {@code management.defaults.metrics.export.enabled=false}
 * （property source 名 = {@code test}）
 * ⇒ ⇒ {@code PrometheusMetricsExportAutoConfiguration} 的
 * {@code @ConditionalOnEnabledMetricsExport} 不匹配 ⇒ <b>没有</b> {@code PrometheusMeterRegistry}
 * ⇒ ⇒ {@code /actuator/prometheus} <b>404</b>。
 *
 * <h2>🔴 为什么必须有这条锚点（这条坑已经让人误判过一次）</h2>
 * <p>此前 follow 域台账把这个 404 记成「<b>生产形态下 Prometheus registry 没装上</b>」的漂移，
 * 根因写作「<b>Boot 装配顺序</b>：simpleMeterRegistry 先注册」。
 * <br>⇒ ⚠️ 那个根因<b>已被实测证伪</b>（Boot 3.5.11 的 {@code MetricsAutoConfiguration}
 * <b>没有</b> {@code simpleMeterRegistry} bean；且无参 {@code @ConditionalOnMissingBean}
 * 按<b>返回类型</b>推断 ⇒ {@code SimpleMeterRegistry} 挡不住 Prometheus）。
 * <br>⇒ ⇒ 真正的真因是<b>「这段代码跑在测试形态里」</b>，跟本仓的 Prometheus 装配无关。
 * <br>⇒ ⇒ 本类把这件事<b>锁成可执行证据</b> ⇒ 下一个人看到 404 时，
 * <b>先跑这条锚点</b>（红 ⇒ 是 Boot 测试框架行为，去加
 * {@code @AutoConfigureObservability(metrics = true)} 或改走生产形态启动），
 * <b>而不是</b>再去怀疑本仓的装配。
 *
 * <h2>⚠️ 效力边界（诚实）</h2>
 * <ul>
 *   <li>本类<b>只</b>证「<b>测试形态</b>里导出器被关掉」；
 *       「<b>生产形态</b>里它是通的」由 {@code PrometheusScrapeEndpointRealTomcatTest} 证
 *       ⇒ ⇒ 两条合起来才是完整结论；</li>
 *   <li>本类<b>不涉及 B 侧</b>：B 侧 go-zero 的 Prometheus 是<b>独立端口</b>的
 *       {@code /metrics} ⇒ 本仓映射到 Actuator 是<b>表达层映射</b>（各域 yml 已留痕）。</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PrometheusTestContextExportDisabledAnchorTest.AnchorApplication.class,
        properties = {
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.endpoint.prometheus.enabled=true"})
class PrometheusTestContextExportDisabledAnchorTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    @DisplayName("锚点：@SpringBootTest 形态下 Boot 把 metrics export 关掉了 ⇒ 这才是 404 的真因")
    void bootTestFrameworkDisablesMetricsExport() {
        Environment environment = applicationContext.getEnvironment();

        // ① Boot 测试框架注入的属性确实存在，且确实是 false
        assertThat(environment.getProperty("management.defaults.metrics.export.enabled"))
                .as("Boot 的 ObservabilityContextCustomizerFactory 应往测试上下文注入 "
                        + "management.defaults.metrics.export.enabled=false")
                .isEqualTo("false");

        // ② 由此，Prometheus registry **没有**被装上（这就是 /actuator/prometheus 404 的原因）
        assertThat(applicationContext.getBeanNamesForType(PrometheusMeterRegistry.class))
                .as("测试形态下不应有 PrometheusMeterRegistry ⇒ 这解释了此前的 404 假象")
                .isEmpty();
    }

    /**
     * 最小可跑应用 —— 只为让 Actuator 的 web 端点真的挂上来。
     *
     * <p>⚠️ <b>故意不加 {@code @AutoConfigureObservability}</b>：加了它，Boot 会把
     * {@code management.defaults.metrics.export.enabled} 重新置回 {@code true}
 * ⇒ ⇒ 那就测不到「Boot 测试框架默认关掉导出器」这件事了 ⇒ ⇒ 本类<b>不能</b>加它。
     */
    @SpringBootApplication(
            excludeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
    static class AnchorApplication {
    }
}
