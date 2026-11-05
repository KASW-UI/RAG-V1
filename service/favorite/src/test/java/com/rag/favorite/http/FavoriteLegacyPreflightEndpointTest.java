package com.rag.favorite.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.favorite.service.FavoriteLegacyPreflightService;
import com.rag.favorite.service.FavoriteLegacyPreflightService.Report;
import com.rag.favorite.support.RealTomcatHttp;
import com.rag.favorite.support.RealTomcatPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 **legacy 只读预检的 Actuator 端点** —— 真 Tomcat 上的**可观测契约**。
 *
 * <h2>本类验的是「端点的 HTTP 面」，不是报表算法</h2>
 * <ul>
 *   <li><b>报表算法</b>（SQL + 逐字段判定 + clear 四条判据）⇒ 由<b>真 PG</b> 的
 *       {@code mapper/FavoriteLegacyPreflightParityTest} 覆盖。</li>
 *   <li><b>本类</b> ⇒ 端点的三件可观察事：
 *       ① <b>暴露形态</b>（两个开关都开 ⇒ 端点注册 ⇒ 路径可达）；
 *       ② <b>替代信号</b>（Go 的退出码 ⇒ C 侧的 HTTP 状态码：200 / 409 / 500）；
 *       ③ <b>响应体形状</b>（字段名与顺序与 Go 的 JSON 逐字一致）。</li>
 * </ul>
 *
 * <h2>🔴 为什么服务层用 mock 而不是真 PG</h2>
 * <p>本类的判据<b>全部落在 HTTP 面</b> ⇒ 真 PG 只会让这个类变慢、变脆
 * ⇒ ⇒ 服务层 mock 成「返回指定的报表」⇒ ⇒ <b>判据只取决于被测的那一层</b>。
 * <p>⚠️ <b>反之</b>：mock 也意味着「服务层没跑过」⇒ ⇒ 那一层必须由真 PG 的那条测试兜住
 * ⇒ ⇒ 两类测试<b>缺一不可</b>（AGENTS.md §3.3.3：不能只跑本仓探针）。
 *
 * <h2>🔴🔴 最关键的一条：<b>健康检查不会探活它</b></h2>
 * <p>预检是「跑一次只读报表」⇒ <b>被探活就等于被反复触发</b> ⇒ 必须能证明它<b>不在</b>探活路径上。
 * <p>⇒ 本类用<b>真触发</b>证明：打 {@code /actuator/health} ⇒ 200 ⇒
 * <b>断言 mock 的 {@code preflight()} 一次都没被调用</b>
 * ⇒ ⇒ 「健康检查不触发预检」不是靠注释声明，是靠<b>运行证据</b>。
 * （结构上的理由：Actuator 的健康组配的是 <b>HealthIndicator 名</b>，不是端点 id。）
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一类测试</b>（B 侧<b>没有</b> preflight 的任何测试
 *       —— 全仓 grep {@code FavoriteLegacyPreflightRunner} 无测试引用）
 *       ⇒ ⇒ 本类是<b>C 侧新增的证据</b>，不是移植。</li>
 *   <li><b>语义还一样吗</b>：不适用（本类不判定 B/C 等价，只钉 C 侧自己的契约）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/
 *       FavoriteLegacyPreflightRunner.java:107-155}（触发 + 退出码语义）；
 *       Go：{@code service/favorite/rpc/cmd/fact-legacy-preflight/main.go:18-54}。</li>
 * </ol>
 */
@SpringBootTest(
        classes = {com.rag.favorite.FavoriteServiceApplication.class,
                com.rag.favorite.support.NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:65535",
                "grpc.client.user-rpc.negotiation-type=plaintext",
                "grpc.client.article-rpc.address=static://127.0.0.1:65535",
                "grpc.client.article-rpc.negotiation-type=plaintext",
                "grpc.server.port=0",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                "observability.http-timeout=30s",
                // 与 FavoriteApiRealTomcatGateTest 同款：让判据不被 CPU 自适应降载抢先盖掉
                "rag.protection.cpu-shedding.enabled=false",
                "favorite-api.user-auth.access-secret=test-only-favorite-jwt-secret-at-least-32-bytes",
                // DB 不可达：本类只验**端点的 HTTP 面** ⇒ 服务层用 mock ⇒ 不会碰库
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/first_db",
                "spring.datasource.username=admin",
                "spring.datasource.password=password",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.flyway.enabled=false",
                // 🔴🔴 **两个开关都要开**（少一个都打不开）：
                //   ① favorite.fact.legacy-preflight.enabled=true ⇒ 端点 bean 才被创建
                //   ② management.endpoint.favorite-legacy-preflight.enabled=true
                //      ⇒ 因为 application.yml 写了 management.endpoints.enabled-by-default: false
                "favorite.fact.legacy-preflight.enabled=true",
                "management.endpoint.favorite-legacy-preflight.enabled=true"
        })
class FavoriteLegacyPreflightEndpointTest {

    private static final String PREFLIGHT_PATH = "/actuator/favorite-legacy-preflight";
    private static final String HEALTH_PATH = "/actuator/health";

    @LocalServerPort
    private int port;

    @MockitoBean
    private FavoriteLegacyPreflightService preflightService;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", RealTomcatPort::pick);
    }

    // ══════════════════════ ① clear ⇒ 200 ══════════════════════

    /**
     * Go {@code cmd/fact-legacy-preflight/main.go:50} 的 {@code os.Exit(2)} 那一支的对面：
     * 报告 clear ⇒ Go 退出码 <b>0</b> ⇒ C 侧 <b>HTTP 200</b>。
     */
    @Test
    @DisplayName("clear ⇒ HTTP 200 + 响应体字段名与顺序与 Go 的 JSON 逐字一致")
    void clearReportReturns200WithGoShapedBody() throws Exception {
        Mockito.when(preflightService.preflight())
                .thenReturn(new Report(FavoriteLegacyPreflightService.CONTRACT, 0L, 0L, 0L, 0L));

        HttpResponse<String> resp = get(PREFLIGHT_PATH);

        assertEquals(200, resp.statusCode(), "clear ⇒ 必须是 200（Go 的退出码 0 的替代信号）");
        // 🔴 **响应体被本域的全局 @RestControllerAdvice 包成了业务信封**
        //    ⇒ 报表在 `data` 里 ⇒ ⇒ 断言的是 **data 里的那份报表**。
        //    ⇒ 为什么信封会作用到 Actuator 端点：Actuator 的 WebMvcEndpointHandlerMapping
        //    产出的**也是 HandlerMethod** ⇒ ⇒ 走同一条 ResponseBodyAdvice 链。
        //    ⇒ 这不是本端点引入的 ⇒ 是 D1 合并进程（api 面带信封）的必然结果 ⇒ 已登记。
        Map<String, Object> envelope = objectMapper.readValue(resp.body(), Map.class);
        assertEquals(List.of("code", "msg", "data"), List.copyOf(envelope.keySet()),
                "外层必须是本域的业务信封（与 B 侧 api 面的 GlobalResponseAdvice 同形）");
        assertEquals(200, ((Number) envelope.get("code")).intValue());
        assertEquals("OK", envelope.get("msg"));
        // 🔴 **内层那份报表必须与 Go 的 FavoriteLegacyPreflightReport 逐字一致**
        //    （字段名 + 字段顺序 + 取值；Go 侧 json.Encoder 输出就是这个形状）
        //    ⇒ 用 Jackson 把 data 重新序列化一次 ⇒ LinkedHashMap 保序 ⇒ 拿到确切文本
        String report = objectMapper.writeValueAsString(envelope.get("data"));
        assertEquals("{\"contract\":\"rtw.favorite.legacy-preflight.v1\",\"missing_assert\":0,"
                        + "\"approved_legacy\":0,\"blocked\":0,\"orphan_retracts\":0}",
                report,
                "内层报表必须与 Go 的 JSON 逐字一致（字段名 + 顺序 + 取值）");
    }

    // ══════════════════════ ② 未裁决 ⇒ 409 ══════════════════════

    /**
     * Go {@code cmd/fact-legacy-preflight/main.go:45-51}：报告不 clear ⇒ {@code os.Exit(2)}
     * ⇒ C 侧的替代信号 = <b>HTTP 409</b>（请求本身没问题，但当前数据状态与发布闸门冲突）。
     */
    @Test
    @DisplayName("未裁决（missing_assert != approved_legacy）⇒ HTTP 409 + 同一份报表体")
    void notClearReportReturns409() throws Exception {
        Mockito.when(preflightService.preflight())
                .thenReturn(new Report(FavoriteLegacyPreflightService.CONTRACT, 3L, 1L, 2L, 0L));

        HttpResponse<String> resp = get(PREFLIGHT_PATH);

        assertEquals(409, resp.statusCode(),
                "未裁决 ⇒ 必须是 409（Go 的退出码 2 的替代信号）⇒ CI 用 curl -f 就能判门禁");
        Map<String, Object> envelope = objectMapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) envelope.get("data");
        assertEquals(3L, ((Number) body.get("missing_assert")).longValue());
        assertEquals(1L, ((Number) body.get("approved_legacy")).longValue());
        assertEquals(2L, ((Number) body.get("blocked")).longValue());
    }

    // ══════════════════════ ③ 未知端点 ⇒ 404（对照） ══════════════════════

    /**
     * 🔴 <b>对照组</b>：未注册的端点 id 也是 404
     * ⇒ ⇒ 「404」这个状态码本身<b>不能</b>区分「端点没开」与「端点不存在」
     * ⇒ ⇒ 必须靠「① 同一个路径在开关打开时是 200」来证明路径确实存在。
     */
    @Test
    @DisplayName("未注册的端点 id ⇒ 404（对照组：证明 404 不是本端点独有的形态）")
    void unknownEndpointReturns404() throws Exception {
        HttpResponse<String> resp = get("/actuator/definitely-not-an-endpoint");
        assertEquals(404, resp.statusCode());
    }

    // ══════════════════════ ④ 🔴 健康检查不探活它 ══════════════════════

    /**
     * 🔴🔴 <b>本类最关键的一条</b>：打健康检查 ⇒ 预检<b>一次都没被触发</b>。
     *
     * <p>为什么必须验：预检是「跑一次只读报表」⇒ <b>被探活就等于被反复触发</b>
     * ⇒ ⇒ 若它在探活路径上，每次健康检查都会扫一遍全表 ⇒ ⇒ 运维闸门变成负载源。
     * <p>⇒ 断言 mock 的 {@code preflight()} <b>调用次数为 0</b> ⇒ ⇒ 这是<b>运行证据</b>，
     * 不是「注释里说它不在探活路径上」。
     */
    @Test
    @DisplayName("🔴 健康检查不会触发预检（mock 调用次数 = 0）")
    void healthCheckDoesNotTriggerPreflight() throws Exception {
        Mockito.reset(preflightService);

        HttpResponse<String> resp = get(HEALTH_PATH);

        // ⚠️ 本上下文里 **DB 不可达**（jdbc:postgresql://127.0.0.1:1）且 Redis 主机名解析不了
        //    ⇒ Actuator 的 db / redis 健康指标必然 DOWN ⇒ 健康端点返回 **503**（不是 200）
        //    ⇒ ⇒ 这里只断言「健康端点**有响应**」，**真正的判据是下面那一句**：
        //    ⇒ ⇒ 「预检一次都没被触发」。
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 503,
                "健康端点必须有响应（200 或 503）；实际=" + resp.statusCode());
        Mockito.verify(preflightService, Mockito.never()).preflight();
    }

    // ══════════════════════ ⑤ 🔴 Actuator 响应被业务信封包裹（守护偏离登记）══════

    /**
     * 🔴🔴 **本域的全局 {@code @RestControllerAdvice} 也作用在 Actuator 端点上**
     * ⇒ ⇒ 连<b>健康端点</b>的 JSON body 都被包成了业务信封。
     *
     * <p>为什么这是「已知且已登记」而不是「要修的缺陷」：
     * <ul>
     *   <li>机制：Actuator 的 {@code WebMvcEndpointHandlerMapping} 产出的
     *       <b>也是 HandlerMethod</b> ⇒ ⇒ 走同一条 {@code ResponseBodyAdvice} 链；</li>
     *   <li><b>B 侧的 api 进程也这样</b>（它的 {@code GlobalResponseAdvice} 同样是全局的）
     *       ⇒ ⇒ <b>C ≡ B（api 面）</b> ⇒ ⇒ 不是漂移；</li>
     *   <li>但 B 侧的 <b>rpc 进程</b>没有这个 advice ⇒ ⇒ D1 合并后 C 的管理面
     *       与 B 的 rpc 面不同 ⇒ ⇒ 已登记为签字偏离（{@code DEV-FAV-RPC-009}）。</li>
     * </ul>
     *
     * <p>⚠️ <b>可观察后果</b>：健康端点在本上下文里因 DB/Redis 不可达而 DOWN
     * ⇒ HTTP 状态是 <b>503</b>，而 body 的信封里 {@code code} 却是 <b>200</b>
     * ⇒ ⇒ <b>探针必须读 HTTP 状态，不能读 body 里的 code</b>。
     *
     * <p>🔴 <b>这条测试的作用是「守护」</b>：哪天有人把 advice 的作用域收窄
     * （让 Actuator 变回裸响应），这条会红 ⇒ ⇒ 逼他同步更新偏离登记 ⇒
     * ⇒ 不会出现「行为变了但台账没变」。
     */
    @Test
    @DisplayName("🔴 Actuator 的 JSON 响应（含健康端点）也被本域的业务信封包裹（D1 合并的已知形态）")
    void actuatorJsonResponsesAreWrappedByTheDomainEnvelope() throws Exception {
        HttpResponse<String> resp = get(HEALTH_PATH);

        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 503,
                "健康端点必须有响应；实际=" + resp.statusCode());
        Map<String, Object> envelope = objectMapper.readValue(resp.body(), Map.class);
        assertEquals(List.of("code", "msg", "data"), List.copyOf(envelope.keySet()),
                "健康端点的 body 也必须是业务信封 ⇒ 探针只能读 HTTP 状态");
    }

    // ══════════════════════ 辅助 ══════════════════════

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = RealTomcatHttp.send(req);
        assertTrue(resp.statusCode() > 0, "拿不到状态码 ⇒ 请求没打到本测试的 Tomcat");
        return resp;
    }
}
