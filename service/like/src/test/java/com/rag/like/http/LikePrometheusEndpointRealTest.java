package com.rag.like.http;

import com.rag.like.LikeServiceApplication;
import com.rag.like.support.LikeTestSupport;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h2>🔴 {@code /actuator/prometheus} 在<b>生产形态上下文</b>里的可用性</h2>
 *
 * <h3>⚠️ 本类推翻了仓里一条<b>从未被验证</b>的断言</h3>
 * <p>{@code LikeHttpWireContractRealTomcatTest#scrapeText} 的 javadoc 里写着：
 * <blockquote>
 * 「生产上下文里 Boot 3.5 的 {@code SimpleMetricsExportAutoConfiguration} 会先注册
 * {@code simpleMeterRegistry} ⇒ Boot 的 Prometheus registry 被跳过
 * ⇒ 「生产上下文没有 Prometheus registry ⇒ {@code /actuator/prometheus} 不可用」
 * —— 那是<b>跨域共性</b>（共享层 / 装配顺序）⇒ 归 {@code global} 收口域」
 * </blockquote>
 * ⇒ ⚠️ 这句话<b>两处都错</b>，本类用运行期证据把它逐条拆开：
 *
 * <table border="1">
 *   <caption>原断言 vs 实测</caption>
 *   <tr><th>原断言</th><th>实测</th></tr>
 *   <tr>
 *     <td>「{@code SimpleMetricsExportAutoConfiguration} 会<b>先</b>注册 ⇒ Prometheus 被跳过」</td>
 *     <td>❌ <b>机制就错</b>：Boot 3.5.11 的 {@code PrometheusMetricsExportAutoConfiguration}
 *         上写着 {@code @AutoConfiguration(before = {CompositeMeterRegistryAutoConfiguration,
 *         SimpleMetricsExportAutoConfiguration})} ⇒ <b>Prometheus 在前</b> ⇒ simple 根本不会顶掉它</td>
 *   </tr>
 *   <tr>
 *     <td>「生产上下文没有 Prometheus registry ⇒ 端点 404」</td>
 *     <td>❌ <b>把测试上下文的特性当成了生产特性</b>：404 的真因是 Boot 的
 *         {@code spring-boot-test-autoconfigure} 里的
 *         {@code ObservabilityContextCustomizerFactory} —— 它的 javadoc 明写
 *         「<b>globally disables metrics export and tracing in tests</b>」
 *         ⇒ 它给测试上下文塞了
 *         {@code management.defaults.metrics.export.enabled=false}
 *         ⇒ ⇒ <b>生产形态（真启动的 app）里 registry 与端点都在</b></td>
 *   </tr>
 * </table>
 *
 * <h3>⇒ 修法：{@code @AutoConfigureObservability(metrics = true, tracing = false)}</h3>
 * <p>Boot 提供的<b>官方开关</b>（{@code spring-boot-test-autoconfigure}）⇒
 * <b>只</b>把测试上下文的 metrics export 打开 ⇒
 * ① {@link PrometheusMeterRegistry} bean 真的被建出来；
 * ② {@code /actuator/prometheus} 返回 <b>200</b> ＋ Prometheus 文本；
 * ③ <b>生产代码零改动</b>（这是测试上下文层的开关）。
 * ⚠️ {@code tracing = false} 保持不变 ⇒ 不会因为打开 observability 而让 OTLP exporter
 * 真去连 {@code 127.0.0.1:1}（测试里那是有意指向关闭端口的）。
 *
 * <h3>🔴 指标断言必须<b>真触发</b>，不能只「注册」</h3>
 * <p>{@code like_api_reject_total} 是带标签的 CounterVec ⇒ <b>没有子计数器就不出现在 scrape 文本里</b>
 * ⇒ ⇒ 本类用<b>真流量</b>触发它：签一个 {@code userId} claim 是<b>字符串</b>的合法 token
 * ⇒ 验签通过、但业务层取 uid 失败 ⇒ HTTP 200 + {@code {code:1006}}
 * ⇒ 同时 {@code LikeApiSupport} 调 {@code incApiReject(route, reason)} 递增计数器
 * ⇒ ⇒ scrape 文本里必须出现 {@code like_api_reject_total{route="/like/likeaction",reason="token_invalid"}}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {LikeServiceApplication.class, NacosStubConfiguration.class})
@AutoConfigureObservability(metrics = true, tracing = false)
@ActiveProfiles("test")
class LikePrometheusEndpointRealTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("🔴 生产形态上下文里存在 PrometheusMeterRegistry（原断言「没有」已被证伪）")
    void prometheusRegistryExistsInProductionShapedContext() {
        PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);

        assertThat(registry).as(
                "域内零特殊 bean 的上下文里必须能拿到 PrometheusMeterRegistry"
                        + " ⇒ Boot 的 PrometheusMetricsExportAutoConfiguration 真的生效了"
                        + " ⇒ 「simpleMeterRegistry 先注册 ⇒ Prometheus 被跳过」这个机制说法不成立").isNotNull();

        List<String> names = context.getBeansOfType(MeterRegistry.class).values().stream()
                .map(r -> r.getClass().getSimpleName())
                .sorted()
                .toList();
        assertThat(names).as("容器里的 MeterRegistry bean 清单").isNotEmpty();
    }

    @Test
    @DisplayName("🔴 /actuator/prometheus 返回 200 且吐 Prometheus 文本")
    void prometheusEndpointIsServed() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(resp.getStatusCode().value())
                .as("生产形态下 /actuator/prometheus 必须可用（exposure.include 含 prometheus）")
                .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody())
                .as("Prometheus 文本格式")
                .contains("# TYPE");
    }

    @Test
    @DisplayName("🔴 like_api_reject_total 真被真流量触发 ⇒ 出现在 scrape 文本里（含两个标签）")
    void likeApiRejectMetricAppearAfterRealTraffic() {
        // ── 真流量：合法签名、但 userId claim 是字符串 ⇒ 业务层取 uid 失败 ⇒ 1006 + reject 计数 ──
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(LikeTestSupport.tokenWithStringUserIdClaim("1001"));

        ResponseEntity<String> resp = rest.exchange("/like/v1/getuserlikelist",
                HttpMethod.POST, new HttpEntity<>("{\"target_type\":\"article\",\"cursor\":0,\"page_size\":10}", headers),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .as("字符串 userId claim ⇒ 200 + {code:1006}（不是 401）")
                .contains("\"code\":1006");

        // ── 现在 scrape：计数器有子项了 ⇒ 必须能吐出来 ──
        String scrape = rest.getForEntity("/actuator/prometheus", String.class).getBody();
        assertThat(scrape).isNotNull();
        assertThat(scrape)
                .as("like_api_reject_total 必须真的出现在 Prometheus 文本里（真流量已触发）")
                .contains("like_api_reject_total{");
        assertThat(scrape)
                .as("route 标签是 B 侧硬编码的 /like/likeaction（含 3 处与实际端点不符的错误值 ⇒ 照抄）")
                .contains("route=\"/like/likeaction\"")
                .contains("reason=\"token_invalid\"");
        assertThat(scrape)
                .as("⚠️ Micrometer 1.15.x ＋ **新** Prometheus client（1.x）⇒ Counter 名字渲染成 "
                        + "like_api_reject_total（**不再**多一个 _total）⇒ 与 B 侧（Boot 4.0.8 ⇒ 同代 Micrometer）同形")
                .doesNotContain("like_api_reject_total_total");
        assertThat(scrape)
                .as("连字符版不符合 Prometheus 指标名正则 ⇒ 永远不会出现（映射表 GAP-8）")
                .doesNotContain("like-rpc_action_total")
                .doesNotContain("like-api_reject_total");
    }
}
