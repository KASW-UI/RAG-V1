package com.rag.like.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import com.rag.like.LikeServiceApplication;
import com.rag.like.support.LikeTestSupport;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.PrometheusTestRegistryConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>like 域 HTTP 层的「线上形状」逐条断言（真 Tomcat · 真 PG · 真 Redis）</b>。
 *
 * <h2>🔴 为什么必须走真 Tomcat（不能用 MockMvc）</h2>
 * <p>本类断言的<b>全部</b>都是「线上字节」层面的东西：
 * <ul>
 *   <li>HTTP 状态码（200 / 401 / 404 / 405 / 415）；</li>
 *   <li><b>401 的 body 必须是<b>空</b></b>（不是 {@code {"code":...}}）；</li>
 *   <li>Content-Type（envelope 是 {@code application/json}；404 是 {@code text/plain; charset=utf-8}
 *       ＋ {@code X-Content-Type-Options: nosniff}）；</li>
 *   <li>框架级错误（404 / 405）的 body <b>不是</b> envelope。</li>
 * </ul>
 * ⇒ ⇒ MockMvc **证不了**这些（它不经过容器 ⇒ 不产生真实的状态行与响应头）
 * ⇒ ⇒ 断言必须打在真 HTTP 响应上。
 *
 * <h2>🔴 本类钉的是「形状」而不是「业务码全表」</h2>
 * <p>业务码全表（29 条）由 {@code contract/LikeErrorContractTest} 钉；
 * 本类只钉 <b>HTTP 层的出口形状</b> ＋ 3 个跨层不变量。
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {LikeServiceApplication.class, NacosStubConfiguration.class,
                PrometheusTestRegistryConfiguration.class})
@ActiveProfiles("test")
class LikeHttpWireContractRealTomcatTest {

    /** 6 个端点逐个列出（B 出处：like 域路由表 6/6 全 POST）。 */
    private static final String[] ENDPOINTS = {
            "/like/v1/getlikecount",
            "/like/v1/getlikestate",
            "/like/v1/gettargetlikerlist",
            "/like/v1/getuserlikelist",
            "/like/v1/getusertotallike",
            "/like/v1/likeaction"};

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(8_100_000_000_000L);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    /** 真 Prometheus registry（供指标名断言用；见 {@link #scrapeText()} 的说明）。 */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<PrometheusMeterRegistry> prometheusRegistry;

    // ==================================================================
    // 夹具
    // ==================================================================

    private static HttpEntity<String> json(String body, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.set(headers[i], headers[i + 1]);
        }
        return new HttpEntity<>(body, h);
    }

    private JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("响应体不是合法 JSON: [" + raw + "]", e);
        }
    }

    // ==================================================================
    // 1. 路由契约：6/6 全是 POST
    // ==================================================================

    @ParameterizedTest(name = "{0} 只接受 POST")
    @ValueSource(strings = {
            "/like/v1/getlikecount",
            "/like/v1/getlikestate",
            "/like/v1/gettargetlikerlist",
            "/like/v1/getuserlikelist",
            "/like/v1/getusertotallike",
            "/like/v1/likeaction"})
    @DisplayName("🔴 6 个端点全不接受 GET（405 + Allow 头 + 空 body）")
    void endpointsArePostOnly(String path) {
        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.GET,
                json("{}"), String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧路由表 6/6 全是 POST ⇒ GET 必须 405")
                .isEqualTo(405);
        assertThat(resp.getHeaders().getFirst("Allow"))
                .as("405 必须带 Allow 头（Go 的 patrouter 行为）")
                .contains("POST");
    }

    @Test
    @DisplayName("🔴 未注册路径 ⇒ 404 + text/plain + nosniff（不是 envelope、不是 401）")
    void unknownPathIsNotFound() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/nope", HttpMethod.POST,
                json("{}"), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getHeaders().getFirst("X-Content-Type-Options"))
                .as("B 侧 404 带 nosniff")
                .isEqualTo("nosniff");
        assertThat(resp.getHeaders().getContentType())
                .as("B 侧 404 是 text/plain；charset=utf-8（不是 envelope 的 application/json）")
                .isNotNull();
        // ⚠️ **逐字节比**（去空格后）：真 Tomcat 会把 Content-Type 归一化 ⇒ 线上是
        //    `text/plain;charset=utf-8`（**分号后无空格**）。
        //    ⇒ 去空格后比 ⇒ 既锁住「类型 + 字符集」两个语义位，又不受 Tomcat 10.1 / 11
        //    在分隔符空格上的渲染差异影响（那只是表现层差异，按 RFC 9110 语义相同）。
        assertThat(resp.getHeaders().getContentType().toString().replace(" ", ""))
                .isEqualTo("text/plain;charset=utf-8");
        assertThat(resp.getBody())
                .as("B 侧 net/http.Error 的 body = \"404 page not found\\n\"（带一个换行）")
                .isEqualTo("404 page not found\n");
    }

    // ==================================================================
    // 2. 鉴权：401 + 空 body
    // ==================================================================

    @Test
    @DisplayName("🔴 没有 Authorization 头 ⇒ 401 + **空 body**（不是 1006、不是 envelope）")
    void missingAuthorizationHeaderIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}"), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody())
                .as("B 侧 unauthorized() 只 WriteHeader(401)、**不写任何字节** ⇒ body 必须为空")
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    @Test
    @DisplayName("🔴 签名错的 token ⇒ 401 + 空 body")
    void wrongSignatureIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.tokenSignedWithWrongSecret(42L)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody())
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    @Test
    @DisplayName("🔴 过期的 token ⇒ 401 + 空 body（验签失败与签名错不区分原因）")
    void expiredTokenIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.expiredToken(42L)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody())
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    @Test
    @DisplayName("🔴 Bearer 前缀大小写不敏感（bearer xxx 应当被接受）")
    void bearerPrefixIsCaseInsensitive() {
        long uid = UID_SEQ.incrementAndGet();
        String token = LikeTestSupport.token(uid);

        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", "bearer " + token),
                String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧 go-zero 的 stripBearerPrefixFromTokenString **大小写不敏感**")
                .isEqualTo(200);
    }

    // ==================================================================
    // 3. claim 解析失败 ⇒ 200 + {code:1006}（不是 401）
    // ==================================================================

    @Test
    @DisplayName("🔴 userId claim 是字符串 ⇒ HTTP 200 + {code:1006, msg:\"TOKEN已过期\"}")
    void stringUserIdClaimYields1006Not401() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikestate", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.tokenWithStringUserIdClaim("123")),
                String.class);

        assertThat(resp.getStatusCode().value())
                .as("验签成功但 claim 不是整型 ⇒ 业务层判 1006 ⇒ **HTTP 仍 200**（不是 401）")
                .isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(1006);
        assertThat(body.get("msg").asText()).isEqualTo("TOKEN已过期");
        assertThat(body.get("data").isNull())
                .as("data 键必须存在且为 null（B 侧 Response 三个字段都没有 omitempty）")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 userId claim 是小数 ⇒ HTTP 200 + {code:1006}（不静默截断成合法 uid）")
    void decimalUserIdClaimYields1006() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikestate", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.tokenWithDecimalUserIdClaim(1.5d)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("Go 的 json.Number.Int64() 对 1.5 失败 ⇒ 1006；截断成 1 就是越权风险")
                .isEqualTo(1006);
    }

    @Test
    @DisplayName("🔴 没有 userId claim ⇒ HTTP 200 + {code:1006}")
    void missingUserIdClaimYields1006() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikestate", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.tokenWithoutUserIdClaim()),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt()).isEqualTo(1006);
    }

    // ==================================================================
    // 4. 公开读端点也**要**签名（只是不要 uid）
    // ==================================================================

    @Test
    @DisplayName("🔴 公开读端点（getlikecount）没有 token ⇒ 同样 401（\"公开\"只指不要 uid）")
    void publicReadStillRequiresSignature() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}"), String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧 rest.WithJwt 挂在**整张路由表**上 ⇒ 6/6 全要 JWT")
                .isEqualTo(401);
    }

    // ==================================================================
    // 5. 解析 / 校验失败 ⇒ 200 + {code:5001}
    // ==================================================================

    @Test
    @DisplayName("🔴 非法 JSON ⇒ HTTP 200 + {code:5001, msg:\"系统内部错误\"}（不是 400）")
    void malformedJsonYields5001() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{not-json", "Authorization", LikeTestSupport.bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧 like api 注册了 httpx.SetErrorHandler ⇒ 解析失败也是 200 + envelope")
                .isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(5001);
        assertThat(body.get("msg").asText()).isEqualTo("系统内部错误");
    }

    @Test
    @DisplayName("🔴 必填字段缺失 ⇒ HTTP 200 + {code:5001}（不是 400、不是 2001）")
    void missingRequiredFieldYields5001() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("B 侧的必填校验失败 ⇒ 走错误处理器 ⇒ 200 + 5001")
                .isEqualTo(5001);
    }

    @Test
    @DisplayName("🔴 空 body ⇒ HTTP 200 + {code:5001}（5 个有必填字段的端点）")
    void emptyBodyYields5001() {
        for (String path : new String[]{"/like/v1/getlikecount", "/like/v1/getlikestate",
                "/like/v1/gettargetlikerlist", "/like/v1/getuserlikelist", "/like/v1/likeaction"}) {
            ResponseEntity<String> resp = rest.exchange(path, HttpMethod.POST,
                    json("{}", "Authorization", LikeTestSupport.bearer(UID_SEQ.incrementAndGet())),
                    String.class);

            assertThat(resp.getStatusCode().value()).as("%s 的 HTTP 状态码", path).isEqualTo(200);
            assertThat(parse(resp.getBody()).get("code").asInt())
                    .as("%s 的业务码（缺必填字段 ⇒ 5001）", path)
                    .isEqualTo(5001);
        }
    }

    @Test
    @DisplayName("🔴 getusertotallike 的 body 可缺省（B 侧该 DTO 无必填字段）⇒ 走业务而不是 5001")
    void getUserTotalLikeAcceptsAbsentBody() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getusertotallike", HttpMethod.POST,
                json("", "Authorization", LikeTestSupport.bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("空 body ⇒ 零值 DTO ⇒ 继续往下走（B 侧的 GetUserTotalLikeReq 无必填字段）")
                .isNotEqualTo(5001);
    }

    // ==================================================================
    // 6. 业务码：target_ids 为空 ⇒ 2001
    // ==================================================================

    @Test
    @DisplayName("🔴 target_ids 为空数组 ⇒ HTTP 200 + {code:2001, msg:\"输入需要查询的ID为空\"}")
    void emptyTargetIdsYields2001() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[]}",
                        "Authorization", LikeTestSupport.bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("Go：if len(in.TargetIds) == 0 { return nil, NewGrpcErr(ErrorInputWrong, ...) }")
                .isEqualTo(2001);
        assertThat(body.get("msg").asText())
                .as("msg 是**自定义文案**，不是码表里的「输入有误」⇒ 证明走的是 newCodeMsg 分支")
                .isEqualTo("输入需要查询的ID为空");
    }

    @Test
    @DisplayName("🔴 target_ids 全是 null ⇒ 与空数组同结局（2001）")
    void allNullTargetIdsYields2001() {
        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[null,null]}",
                        "Authorization", LikeTestSupport.bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("Go：slice 保持 nil ⇒ 等价于空 target_ids ⇒ 2001")
                .isEqualTo(2001);
    }

    // ==================================================================
    // 7. 成功出口的形状
    // ==================================================================

    @Test
    @DisplayName("🔴 成功出口 = {code:200, msg:\"OK\", data:{...}}（信封三键与顺序）")
    void successShapeIsEnvelope() {
        long uid = UID_SEQ.incrementAndGet();
        // 用唯一的 target_type ⇒ 与其它测试类的数据不冲突
        String targetType = "article-" + uid;

        ResponseEntity<String> resp = rest.exchange("/like/v1/getlikecount", HttpMethod.POST,
                json("{\"target_type\":\"" + targetType + "\",\"target_ids\":[\"t-1\"]}",
                        "Authorization", LikeTestSupport.bearer(uid)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType())
                .as("envelope 的 Content-Type 必须是 application/json")
                .isNotNull();
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("application/json");

        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(200);
        assertThat(body.get("msg").asText()).isEqualTo("OK");
        assertThat(body.get("data")).isNotNull();
        assertThat(body.get("data").get("counts").get("t-1").get("like_count").asLong())
                .as("空库 ⇒ 计数为 0")
                .isZero();
        assertThat(body.get("data").get("counts").get("t-1").get("dislike_count").asLong()).isZero();
    }

    // ==================================================================
    // 8. 指标名（GAP-8：连字符 vs 下划线）
    // ==================================================================

    @Test
    @DisplayName("🔴 指标名必须是下划线版 like_api_reject_total（连字符版不会出现在 scrape 里）")
    void metricNameUsesUnderscores() {
        // 触发一次 reject（claim 解析失败）⇒ 指标被真正观察到
        rest.exchange("/like/v1/getlikestate", HttpMethod.POST,
                json("{\"target_type\":\"article\",\"target_ids\":[\"a-1\"]}",
                        "Authorization", LikeTestSupport.tokenWithStringUserIdClaim("123")),
                String.class);

        String scrape = scrapeText();

        assertThat(scrape)
                .as("like_api_reject_total 必须真的出现在 Prometheus 文本里")
                .contains("like_api_reject_total");
        assertThat(scrape)
                .as("连字符版不符合 Prometheus 指标名正则 ⇒ 永远不会出现")
                .doesNotContain("like-rpc_action_total")
                .doesNotContain("like-api_reject_total");
    }

    /**
     * 取 Prometheus 文本。
     *
     * <h3>⚠️ 为什么必须回退到 registry 直取（不能只打 actuator 端点）</h3>
     * <p>本测试注入了 {@code support/PrometheusTestRegistryConfiguration}（一个真的
     * {@code PrometheusMeterRegistry} bean）⇒ ⇒ Boot 的
     * {@code PrometheusMetricsExportAutoConfiguration}（<b>含其嵌套的
     * {@code PrometheusScrapeEndpointConfiguration}</b>）因
     * {@code @ConditionalOnMissingBean} <b>整段被跳过</b>
     * ⇒ ⇒ {@code PrometheusScrapeEndpoint} bean 不存在 ⇒ {@code /actuator/prometheus} **404**。
     * <p>⇒ ⇒ 断言必须打在 <b>registry.scrape()</b> 的文本上（那才是「Prometheus 文本格式」的真身）。
     *
     * <h3>🔴 ⚠️ 本段 javadoc 先前那句「生产上下文没有 Prometheus registry ⇒ 端点 404」<b>已被证伪</b></h3>
     * <p>原说法与实测（见 {@code LikePrometheusEndpointRealTest}，3 例真跑）对照：
     * <table border="1">
     *   <caption>原断言 vs 实测</caption>
     *   <tr><th>原断言</th><th>实测</th></tr>
     *   <tr>
     *     <td>「{@code SimpleMetricsExportAutoConfiguration} 会<b>先</b>注册 ⇒ Prometheus 被跳过」</td>
     *     <td>❌ <b>机制就错</b>：Boot 3.5.11 的
     *         {@code PrometheusMetricsExportAutoConfiguration} 标着
     *         {@code @AutoConfiguration(before = {CompositeMeterRegistryAutoConfiguration,
     *         SimpleMetricsExportAutoConfiguration})} ⇒ <b>Prometheus 在前</b></td>
     *   </tr>
     *   <tr>
     *     <td>「生产上下文没有 Prometheus registry ⇒ 端点 404」</td>
     *     <td>❌ <b>把测试上下文的特性当成了生产特性</b>：真因是 Boot 的
     *         {@code spring-boot-test-autoconfigure} 里
     *         {@code ObservabilityContextCustomizerFactory}（javadoc 明写
     *         「globally disables metrics export and tracing <b>in tests</b>」）
     *         ⇒ 它给<b>测试</b>上下文塞了
     *         {@code management.defaults.metrics.export.enabled=false}
     *         ⇒ ⇒ <b>生产形态下 registry 与端点都在</b></td>
     *   </tr>
     * </table>
     * ⇒ ⇒ <b>「生产上下文没有 Prometheus registry」这条已撤销</b>（它曾被登记为
     * 「跨域共性 ⇒ 归 global 收口域」—— <b>那是假阳性</b>，不需要收口）。
     * ⇒ ⇒ <b>本类的 404 仍然成立</b>，但原因不同：它来自
     * {@code PrometheusTestRegistryConfiguration} 注入的<b>手工</b> registry
     * ⇒ Boot 的自动配置整段让位 ⇒ <b>没有</b> {@code PrometheusScrapeEndpoint} bean
     * ⇒ ⇒ 故本类仍走 {@code registry.scrape()} 回退路径。
     * <p>⚠️ 与 follow 域的既有口径<b>同款</b>（{@code FollowHttpBusinessCodeRealTomcatTest#scrapeText}
     * 同样「优先 actuator、回退 registry」）—— 但 follow 域那份的<b>归因</b>（「跨域共性」）
     * 同样是上面的错判 ⇒ <b>建议 follow lane 用同款实验复核一次</b>（属 follow lane 的事，
     * 本 lane 不改）。
     */
    private String scrapeText() {
        try {
            ResponseEntity<String> resp = rest.getForEntity("/actuator/prometheus", String.class);
            if (resp.getStatusCode().value() == 200 && resp.getBody() != null) {
                return resp.getBody();
            }
        } catch (RuntimeException ignored) {
            // 回退到 registry 直取
        }
        PrometheusMeterRegistry registry = prometheusRegistry.getIfAvailable();
        assertThat(registry)
                .as("既拿不到 /actuator/prometheus 的 200，也找不到 PrometheusMeterRegistry bean "
                        + "⇒ 本域的指标根本没有以 Prometheus 文本暴露")
                .isNotNull();
        return registry.scrape();
    }
}
