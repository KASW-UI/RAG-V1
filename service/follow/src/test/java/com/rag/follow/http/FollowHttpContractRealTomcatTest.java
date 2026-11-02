package com.rag.follow.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.follow.FollowServiceApplication;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.FollowTestSupport;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;
import com.rag.follow.support.TestRedis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>follow 域 HTTP 契约（真 Tomcat · 真 PostgreSQL · 真 Redis · 真 JWT）</b>。
 *
 * <h2>⚠️ 为什么必须用「真 Tomcat」而不是 MockMvc</h2>
 * <p>AGENTS.md §八 检查清单：「<b>HTTP 断言走真 Tomcat</b> —— 断言 {@code Content-Type} /
 * {@code nosniff} / 状态行时<b>不用 MockMvc</b>」。
 * <p>本类要钉的恰好全是 MockMvc 测不到的东西：
 * <ul>
 *   <li><b>黑名单那条 401 的逐字节 Content-Type</b>（{@code application/json;charset=utf-8}，
 *       {@code ;} 后<b>无空格</b>）—— MockMvc 不经过真实容器的 header 渲染；</li>
 *   <li><b>404 的 {@code text/plain; charset=utf-8} + {@code nosniff} + 尾随 \n</b>
 *       —— 共享层 {@code RouterErrorResponses} 的形状要过真容器才准；</li>
 *   <li><b>401 空 body</b> —— 「{@code ResponseBodyAdvice} 不许给空 body 造信封」这条规则
 *       要在真实 advice 链里才验得到。</li>
 * </ul>
 *
 * <h2>本类钉的 L1 契约（逐条对应 B 侧源码，不是推断）</h2>
 * <ol>
 *   <li><b>8 个端点全 POST</b>：{@code GET} ⇒ <b>405</b>（裸状态码 · 无 body · 无 Content-Type）。</li>
 *   <li><b>未注册路径 ⇒ 404</b>（<b>不是 401</b>）—— B 侧的 chain 只挂在匹配上的路由上
 *       ⇒ 路由匹配先于鉴权；C 侧靠两个拦截器的 {@code handler instanceof HandlerMethod} 判据实现。</li>
 *   <li><b>验签失败 ⇒ 401 + 空 body</b>（B 侧 {@code authhandler.go:106} 只写状态码）。</li>
 *   <li><b>黑名单命中 ⇒ 401 + 两字段 body</b>（<b>无 {@code data}</b>）＋ 逐字节 Content-Type
 *       （B 侧 {@code checkblacklistmiddleware.go:42-48}）。</li>
 *   <li><b>业务失败 ⇒ HTTP 200 + {@code {code,msg,data}}</b>（<b>不是 400</b>）
 *       —— B 侧注册了 {@code httpx.SetErrorHandler} ⇒ 一切业务错误压成 200。</li>
 *   <li><b>🔴 {@code {"offset":"10"}} ⇒ 200 + {code:5001}}</b>（<b>不是 400</b>）
 *       —— 本域独有的 Jackson 强转补强（{@code LogicalType.Integer + String → Fail}）。</li>
 *   <li><b>🔴 空 {@code user_ids} ⇒ {@code "user_ids":null}</b>（<b>不是 {@code []}</b>）。</li>
 *   <li><b>🔴 {@code target_id} 是 JSON 字符串、{@code user_ids} 是 JSON 数字</b>（形态不一致）。</li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>本类跑的是 <b>C 侧单侧</b>：断言的是「C 侧产出的形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>的范畴（本类<b>不冒充</b>该证据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // Nacos 整体不生效 ⇒ 自动配置不注册 NacosServiceManager ⇒ 由 NacosStubConfiguration 补上
                "spring.cloud.nacos.discovery.enabled=false",
                // 服务注册自动注册关掉（否则需要真的 ServiceRegistry + Nacos）
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // gRPC **不起 TCP 端口**（devh 的 ConditionalOnInterprocessServer 不匹配 ⇒ netty server 不创建）
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowHttpContractRealTomcatTest {

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(7_100_000_000_000L);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        // ⚠️ 出向的 user-rpc 客户端：指向「假 user-rpc」真 gRPC server（随机端口）
        //    ⇒ domain 层的存在性校验（5101）走真 gRPC，而不是被 mock 掉。
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

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

    /** 8 个端点（B 出处：follow 域 FollowController 的 8 个 {@code @PostMapping}）。 */
    private static final List<String> ALL_PATHS = List.of(
            "/follow/v1/follow", "/follow/v1/unfollow", "/follow/v1/block", "/follow/v1/unblock",
            "/follow/v1/follow/list", "/follow/v1/follower/list", "/follow/v1/block/list",
            "/follow/v1/recommendations");

    /** 预置一条关注关系（真 PG 直插 ⇒ 不经过领域层）。 */
    private void insertFollow(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO follow_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?)",
                userId, targetId, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }

    @BeforeEach
    void resetNotFoundMarkers() {
        // 本类不依赖「用户不存在」语义 ⇒ 清掉标记，保证下游一律回 found=true。
        FakeUserRpcServer.markFound(0L);
    }

    // ==================================================================
    // 1. L1 · 路由与 method
    // ==================================================================

    @Test
    @DisplayName("🔴 L1：8 个端点全是 POST ⇒ GET 一律 405（裸状态码 · 无 body · 无 Content-Type）")
    void allEightEndpointsArePostOnly() {
        for (String path : ALL_PATHS) {
            ResponseEntity<String> resp = rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(json("{}").getHeaders()), String.class);
            assertThat(resp.getStatusCode().value())
                    .as("GET %s 必须 405（B 侧 8 个 handler 全是 @PostMapping）", path)
                    .isEqualTo(405);
            assertThat(resp.getBody()).as("405 的 body 必须为空（B 侧只 WriteHeader）").isNullOrEmpty();
        }
    }

    @Test
    @DisplayName("🔴 L1：未注册路径 ⇒ 404（**不是 401**）+ text/plain + nosniff（路由匹配先于鉴权）")
    void unregisteredPathIs404Not401() {
        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/not-exist", json("{}"), String.class);

        // ⚠️ 关键：**不带 token 也必须是 404**（不是 401）
        //   ⇒ 证明「路由匹配先于鉴权」，且拦截器的 `handler instanceof HandlerMethod` 判据真的生效
        //   （未匹配路径的 handler 是 ResourceHttpRequestHandler ⇒ 不是 HandlerMethod ⇒ 放行）。
        assertThat(resp.getStatusCode().value())
                .as("未注册路径必须 404（B 侧 notFoundHandler 不经鉴权）")
                .isEqualTo(404);
        assertThat(resp.getHeaders().getFirst("Content-Type"))
                .as("404 必须是 text/plain")
                .startsWith("text/plain");
        assertThat(resp.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resp.getBody()).isEqualTo("404 page not found\n");
    }

    @Test
    @DisplayName("⚠️ 记录用：404 的 Content-Type 在真实容器上被归一化成「;charset=」（B 侧是「; charset=」）")
    void notFoundContentTypeIsNormalizedByTheContainer() {
        // B 侧 http.Error 写的是字面量 "text/plain; charset=utf-8"（**分号后有空格**）
        //   ⇒ B 出处：go-zero v1.10.2 net/http/http.go:2344
        // C 侧共享层 RouterErrorResponses.notFound 写的也是 "text/plain; charset=utf-8"
        //   ⇒ 但它经 Spring 的 ServletServerHttpResponse → Tomcat Response#setContentType
        //      ⇒ **Tomcat 把 "; charset=" 归一化成 ";charset="** ⇒ 线上字节与 B 侧差一个空格。
        // ⚠️ 责任边界：这条 404 是**共享层**（common/）的形状，不是 follow 域自己的
        //   ⇒ 按 AGENTS.md §五.3 归「global 收口域」⇒ 本域**只记录、不改**。
        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/not-exist", json("{}"), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getHeaders().getFirst("Content-Type"))
                .as("真实容器上的观测值（与 B 侧字面量差一个空格）")
                .isEqualTo("text/plain;charset=utf-8");
    }

    // ==================================================================
    // 2. 三种错误响应形状
    // ==================================================================

    @Test
    @DisplayName("🔴 形状①：缺 Authorization ⇒ HTTP 401 + **空 body**（无信封）")
    void missingAuthorizationIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list", json("{}"), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).as("验签失败的 body 必须为空（B 侧 authhandler.go:106 只写状态码）")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("🔴 形状①：签名错的 token ⇒ HTTP 401 + 空 body")
    void wrongSignatureIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", "Bearer " + FollowTestSupport.tokenSignedWithWrongSecret(1L)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("🔴 形状②：业务失败（查他人黑名单）⇒ HTTP 200 + {code:5005,msg:forbidden,data:null}")
    void forbiddenScopeIs200With5005Envelope() {
        long me = UID_SEQ.incrementAndGet();
        long other = UID_SEQ.incrementAndGet();

        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/block/list",
                json("{\"user_id\":" + other + "}", "Authorization", FollowTestSupport.bearer(me)),
                String.class);

        assertThat(resp.getStatusCode().value())
                .as("业务失败必须是 HTTP 200（B 侧 httpx.SetErrorHandler 把一切压成 200）")
                .isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(5005);
        assertThat(body.get("msg").asText()).isEqualTo("forbidden");
        assertThat(body.has("data")).as("5005 的 data 键必须在（值为 null）").isTrue();
        assertThat(body.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("🔴 形状③：黑名单命中 ⇒ HTTP 401 + {code:5004,msg:unauthorized}（**无 data**）+ 逐字节 Content-Type")
    void blacklistedTokenHasGoExactBody() {
        long me = UID_SEQ.incrementAndGet();
        String token = FollowTestSupport.token(me);

        // 前置：token 此刻可用（否则后面的 401 无法归因于黑名单）
        ResponseEntity<String> before = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", "Bearer " + token), String.class);
        assertThat(before.getStatusCode().value()).as("前置条件：token 本应可用").isEqualTo(200);

        // 写入黑名单 —— 前缀必须是 user:jwt_blacklist:
        //   （B 出处：checkblacklistmiddleware.go:39 的 fmt.Sprintf("user:jwt_blacklist:%s", token)）
        // ⚠️ 写错前缀的后果是「黑名单层永远不命中」，而且**零报错**（查的是一个不存在的 key）。
        redis.opsForValue().set("user:jwt_blacklist:" + token, "1");

        ResponseEntity<String> after = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", "Bearer " + token), String.class);

        // 🔴 逐字节断言：body 必须是 `{"code":5004,"msg":"unauthorized"}` —— **没有 data 字段**
        //   （B 侧那是手写字符串，不是 envelope 结构体）。
        //   若被 FollowApiResponseAdvice 包一层就变成三字段 ⇒ 这条断言当场红。
        assertThat(after.getStatusCode().value()).isEqualTo(401);
        assertThat(after.getBody()).isEqualTo("{\"code\":5004,\"msg\":\"unauthorized\"}");
        assertThat(after.getBody()).doesNotContain("\"data\"");
        // ⚠️ B 侧字面量里分号后**没有空格**
        assertThat(after.getHeaders().getFirst("Content-Type"))
                .as("黑名单 401 的 Content-Type 必须逐字节等于 B 侧字面量")
                .isEqualTo("application/json;charset=utf-8");
    }

    // ==================================================================
    // 3. P0-4：{"offset":"10"} 被拒
    // ==================================================================

    @Test
    @DisplayName("🔴 P0-4：{\"offset\":\"10\"} ⇒ HTTP 200 + {code:5001}（**不是 400**）")
    void stringOffsetIsRejectedWith200And5001() {
        long me = UID_SEQ.incrementAndGet();

        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{\"offset\":\"10\"}", "Authorization", FollowTestSupport.bearer(me)), String.class);

        // ⚠️ **不是 400** —— B 侧 follow api 注册了 httpx.SetErrorHandler ⇒ 一切业务错误恒 200
        //   （users 域是 400 ⇒ 两个域的解析失败形状**刻意不同**）
        assertThat(resp.getStatusCode().value())
                .as("follow 域的解析失败必须是 200（B 侧 SetErrorHandler），不是 users 域的 400")
                .isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(5001);
        assertThat(body.get("msg").asText()).isEqualTo("internal server error");
    }

    @Test
    @DisplayName("🔴 P0-4 同族：{\"limit\":\"5\"} 同样被拒；畸形 JSON 与缺 target_id 也都是 200 + 5001")
    void stringLimitAndMalformedBodyAlsoFoldInto200And5001() {
        long me = UID_SEQ.incrementAndGet();

        ResponseEntity<String> limit = rest.postForEntity("/follow/v1/follower/list",
                json("{\"limit\":\"5\"}", "Authorization", FollowTestSupport.bearer(me)), String.class);
        assertThat(limit.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(limit.getBody()).get("code").asInt()).isEqualTo(5001);

        ResponseEntity<String> malformed = rest.postForEntity("/follow/v1/follow",
                json("{not-json", "Authorization", FollowTestSupport.bearer(me)), String.class);
        assertThat(malformed.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(malformed.getBody()).get("code").asInt()).isEqualTo(5001);

        ResponseEntity<String> missingTarget = rest.postForEntity("/follow/v1/follow",
                json("{}", "Authorization", FollowTestSupport.bearer(me)), String.class);
        assertThat(missingTarget.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(missingTarget.getBody()).get("code").asInt()).isEqualTo(5001);
    }

    // ==================================================================
    // 4. P0-2 / P0-3：出参形态
    // ==================================================================

    @Test
    @DisplayName("🔴 P0-2：空关注列表 ⇒ body 必须是 {\"user_ids\":null}（**不是 []**）")
    void emptyUserIdsIsJsonNullNotEmptyArray() {
        long me = UID_SEQ.incrementAndGet();

        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", FollowTestSupport.bearer(me)), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(200);
        JsonNode data = body.get("data");
        assertThat(data.has("user_ids")).as("user_ids 键必须存在").isTrue();
        assertThat(data.get("user_ids").isNull())
                .as("空列表必须输出 null（B 侧：proto3 repeated 为空 ⇒ 字段不上 wire ⇒ 反序列化得 nil "
                        + "⇒ encoding/json 输出 null），不是 []")
                .isTrue();
        // ⚠️ 逐字节再钉一次：body 里必须是 "user_ids":null 这个字面量
        assertThat(resp.getBody()).contains("\"user_ids\":null");
        assertThat(resp.getBody()).doesNotContain("\"user_ids\":[]");
    }

    @Test
    @DisplayName("🔴 P0-3：target_id 是 JSON **字符串**、user_ids 是 JSON **数字**（形态不一致）")
    void targetIdIsStringButUserIdsAreNumbers() {
        long me = UID_SEQ.incrementAndGet();
        long friend = UID_SEQ.incrementAndGet();
        long stranger = UID_SEQ.incrementAndGet();

        insertFollow(me, friend);      // 我 → 朋友
        insertFollow(friend, stranger); // 朋友 → 陌生人（二度人脉）

        ResponseEntity<String> listResp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", FollowTestSupport.bearer(me)), String.class);
        assertThat(listResp.getStatusCode().value()).isEqualTo(200);
        // ⚠️ user_ids 是**数字**数组（B 侧 tag `json:"user_ids"` **无** `,string`）
        assertThat(listResp.getBody()).contains("\"user_ids\":[" + friend + "]");
        assertThat(listResp.getBody()).doesNotContain("\"" + friend + "\"");

        ResponseEntity<String> recResp = rest.postForEntity("/follow/v1/recommendations",
                json("{}", "Authorization", FollowTestSupport.bearer(me)), String.class);
        assertThat(recResp.getStatusCode().value()).isEqualTo(200);
        // ⚠️ target_id 是**字符串**（B 侧 tag 带 `,string`）
        assertThat(recResp.getBody()).contains("\"target_id\":\"" + stranger + "\"");
        assertThat(recResp.getBody()).doesNotContain("\"target_id\":" + stranger + ",");

        JsonNode recBody = parse(recResp.getBody());
        assertThat(recBody.get("data").get("users")).hasSize(1);
        assertThat(recBody.get("data").get("users").get(0).get("target_id").isTextual())
                .as("target_id 必须是 JSON 字符串").isTrue();
        assertThat(recBody.get("data").get("users").get(0).get("mutual_score").asInt()).isEqualTo(20);
    }

    // ==================================================================
    // 5. 身份解析的三种结局
    // ==================================================================

    @Test
    @DisplayName("🔴 无 userId claim ⇒ HTTP 200 + {code:5004}（**不是 401**：claim 取值判定属业务层）")
    void missingUserIdClaimIs200With5004() {
        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", "Bearer " + FollowTestSupport.tokenWithoutUserIdClaim()),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(5004);
        assertThat(body.get("msg").asText()).isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("🔴 字符串 userId claim 被接受（follow 域比 message 域宽）")
    void stringUserIdClaimIsAccepted() {
        long me = UID_SEQ.incrementAndGet();

        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization",
                        "Bearer " + FollowTestSupport.tokenWithStringUserIdClaim(String.valueOf(me))),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("🔴 浮点 userId claim ⇒ 200 + {code:5004}（不截断成合法 uid）")
    void floatingUserIdClaimIs5004() {
        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization",
                        "Bearer " + FollowTestSupport.tokenWithStringUserIdClaim("1.5")),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt()).isEqualTo(5004);
    }

    // ==================================================================
    // 6. 信封形状
    // ==================================================================

    @Test
    @DisplayName("🔴 成功响应：body 恰好三键 code/msg/data，且 data 为 null 时键仍在")
    void envelopeHasExactlyThreeKeys() {
        long me = UID_SEQ.incrementAndGet();

        ResponseEntity<String> resp = rest.postForEntity("/follow/v1/follow/list",
                json("{}", "Authorization", FollowTestSupport.bearer(me)), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getFirst("Content-Type"))
                .as("成功响应必须是 application/json;charset=utf-8（共享层 JsonContentTypeAdvice）")
                .isEqualTo("application/json;charset=utf-8");

        JsonNode body = parse(resp.getBody());
        List<String> fieldNames = new ArrayList<>();
        body.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).isEqualTo(List.of("code", "msg", "data"));
        assertThat(body.get("code").asInt()).isEqualTo(200);
        assertThat(body.get("msg").asText()).isEqualTo("OK");
    }
}
