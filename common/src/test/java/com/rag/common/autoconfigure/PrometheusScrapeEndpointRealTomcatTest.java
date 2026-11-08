package com.rag.common.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>共享层「Prometheus 抓取面」的守门</b> —— 真 Tomcat ＋ 真 HTTP 打
 * {@code /actuator/prometheus} ⇒ 证「<b>真能被 scrape</b>」，不是「registry bean 存在」。
 *
 * <h2>🔴🔴 为什么<b>不能</b>用 {@code @SpringBootTest} 来写这条探针（本类最关键的一点）</h2>
 * <p>Boot 3.5.11 的 {@code spring-boot-test-autoconfigure} 里有
 * {@code ObservabilityContextCustomizerFactory} —— 它的 javadoc 明写
 * 「<b>globally disables metrics export and tracing in tests</b>」
 * ⇒ ⇒ 它给<b>测试上下文</b>注入
 * {@code management.defaults.metrics.export.enabled=false}（property source 名 = {@code test}）
 * ⇒ ⇒ {@code PrometheusMetricsExportAutoConfiguration} 的
 * {@code @ConditionalOnEnabledMetricsExport} 不匹配 ⇒ 容器里<b>没有</b> Prometheus registry
 * ⇒ ⇒ {@code /actuator/prometheus} <b>404</b>。
 * <br>⇒ ⚠️ <b>这个 404 只在测试形态下出现</b>；生产形态（真启动的 app）里该属性<b>不存在</b>
 * ⇒ ⇒ 默认值 {@code true} ⇒ registry 与端点<b>都在</b>。
 * <br>⇒ ⇒ <b>用 {@code @SpringBootTest} 写这条探针 ⇒ 必然假红</b>（把 Boot 的测试框架行为
 * 误判成「本仓 Prometheus 没装好」）。
 * <p>⇒ ⇒ 本类改用 {@link SpringApplicationBuilder} <b>直接启动</b>（<b>不经过</b>
 * {@code SpringBootTestContextBootstrapper}）⇒ ⇒ 拿到的就是<b>生产形态</b>上下文。
 *
 * <h2>⚠️ 与 {@code PrometheusTestContextExportDisabledAnchorTest} 的分工</h2>
 * <ul>
 *   <li><b>本类</b>：证「<b>生产形态</b>里抓取面是通的」（正证）；</li>
 *   <li><b>那个类</b>：证「<b>测试形态</b>里 404 是 <b>Boot 测试框架</b>干的、不是本仓缺陷」（反证锚点）
 *       ⇒ ⇒ 两条合起来把「404 的真因」<b>锁死</b>，下一个人不必再重新诊断一遍。</li>
 * </ul>
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
class PrometheusScrapeEndpointRealTomcatTest {

    @Test
    @DisplayName("抓取面（生产形态启动）：/actuator/prometheus 返回 200 且是 Prometheus 文本")
    void prometheusEndpointIsScrapeableInProductionShape() throws Exception {
        // ⚠️ **不走 @SpringBootTest**：那样会踩到 ObservabilityContextCustomizerFactory
        //    （它给测试上下文塞 management.defaults.metrics.export.enabled=false）
        //    ⇒ 拿到的是「测试形态」⇒ 必然 404 ⇒ 假红。
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ScrapeApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "management.endpoints.web.exposure.include=health,info,prometheus")
                .run()) {
            int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();

            HttpURLConnection conn = (HttpURLConnection) URI
                    .create("http://127.0.0.1:" + port + "/actuator/prometheus")
                    .toURL().openConnection();
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            String body;
            try (InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                body = (in == null) ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            assertThat(status)
                    .as("生产形态（未经 Boot 测试框架定制器）下 /actuator/prometheus 必须 200；"
                            + "若为 404 ⇒ 先查 management.defaults.metrics.export.enabled 是否被测试框架置 false")
                    .isEqualTo(200);
            assertThat(body).contains("# HELP");
            assertThat(body).contains("# TYPE");
        }
    }

    /**
     * 最小可跑应用 —— 只为让 Actuator 的 web 端点真的挂上来。
     *
     * <p>⚠️ 用 {@code @SpringBootApplication} 而不是裸 {@code @Configuration}：
     * 需要 {@code @EnableAutoConfiguration} ⇒ 才会把
     * {@code PrometheusMetricsExportAutoConfiguration} 这条<b>真的自动配置链</b>装上
     * ⇒ ⇒ 探针测的是「Boot 装配」而不是「我们自己 new 了一个 registry」。
     *
     * <p>⚠️ 排除 {@code DataSourceAutoConfiguration}：本类不测持久层 ⇒ 上下文里不需要数据源
     * ⇒ 排除掉可避免「没有 DataSource ⇒ 启动失败」这种与被测语义无关的噪声。
     */
    @SpringBootApplication(
            excludeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
    static class ScrapeApplication {
    }
}
