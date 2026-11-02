package com.rag.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.admin.AdminServiceApplication;
import com.rag.admin.controller.AdminController;
import com.rag.admin.interceptor.AdminJwtAuthInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>admin 域 HTTP 契约测试 —— 真 Tomcat · 真 PG · 真 Redis</b>。
 *
 * <p><b>为什么不用 MockMvc</b>（AGENTS.md §八「HTTP 断言走真 Tomcat」）：
 * MockMvc 走的是 {@code MockHttpServletResponse}，<b>不经过真实容器</b> ⇒
 * {@code Content-Type} 的<b>最终渲染</b>、{@code nosniff} 头、状态行、{@code text/plain}
 * 的 charset 归一化<b>全部测不到</b> ⇒ ⇒ 恰恰是本类要钉的那几件事。
 * <p>⇒ 本类用 {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} + {@link TestRestTemplate}
 * ⇒ 每次请求都<b>真的过一遍 Tomcat</b>（DispatcherServlet → Filter 链 → HandlerInterceptor
 * → Controller → ResponseBodyAdvice → advice → 容器写回）。
 *
 * <p><b>B 出处（本类钉住的 L1 契约全部来自 B 侧源码，不是推断）</b>：
 * <pre>
 *   B: service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/controller/AdminController.java
 *      —— 13 个 {@code @PostMapping}：method / path 逐条照抄
 *   B: .../api/advice/GlobalResponseAdvice.java
 *      —— 信封 {@code {code, msg, data}}；code=200 / msg="OK"；「已是信封不重包」
 *   B: .../api/advice/GlobalExceptionHandler.java
 *      —— 解析层失败 ⇒ {@code http.Error(w, err.Error(), 400)}
 *         ⇒ Content-Type: text/plain; charset=utf-8 + X-Content-Type-Options: nosniff + 尾随 \n
 *   B: .../api/internal/handler/routes.go
 *      —— public = /login + /create（不带 JWT），其余 11 个带 JWT
 *   B: .../api/internal/middleware/checkblacklistmiddleware.go
 *      —— 黑名单 key 前缀 {@code admin:jwt_blacklist:}
 * </pre>
 *
 * <h2>本类钉住什么</h2>
 * <ol>
 *   <li><b>13 个端点全是 POST</b>：GET 一律 405（且 405 发生在<b>鉴权之前</b> ⇒ 与 B 侧
 *       「路由层先判 method」一致）。</li>
 *   <li><b>public 放行</b>：{@code /login}、{@code /create} 无 token <b>不是</b> 401。</li>
 *   <li><b>protected 401</b>：缺 token / 坏 token / 黑名单 token 三种都 401 + 完整信封。</li>
 *   <li><b>200 信封</b>：成功响应的 body <b>恰好三键</b> {@code code/msg/data}，且
 *       {@code data:null} 时<b>键仍然存在</b>（不许 {@code @JsonInclude(NON_NULL)}）。</li>
 *   <li><b>400 + text/plain + nosniff + 尾随 \n</b>：必填字段缺失时的<b>形状</b>
 *       （<b>文本内容</b>属已登记的 D-01 降级 ⇒ 本类<b>不断言</b>文本）。</li>
 *   <li><b>黑名单 key 前缀是 {@code admin:} 域</b>（不得与 user 域的 {@code user:} 混用）。</li>
 * </ol>
 *
 * <h2>⚠️ 测试环境怎么把外部依赖摘掉</h2>
 * <ul>
 *   <li><b>Nacos</b>：{@code spring.cloud.nacos.discovery.enabled=false} ⇒ Nacos 自动配置整体不生效
 *       ⇒ 本类自带一个 {@code NacosServiceManager} 桩（{@link NacosStubConfiguration}）
 *       ⇒ 域内 {@code wiring/NacosRegistration} 的构造依赖被满足，但<b>不会真去注册</b>
 *       （桩的 {@code NamingService} 是 mock ⇒ 注册调用是空操作）。</li>
 *   <li><b>gRPC 端口</b>：{@code grpc.server.port=0} ⇒ 随机端口 ⇒ 不与本机其它域撞端口。</li>
 *   <li><b>PG / Redis</b>：Testcontainers 真容器 ⇒ DDL、{@code SELECT ... FOR UPDATE}、
 *       黑名单读写全部走真中间件。</li>
 *   <li><b>JWT 密钥</b>：{@code ADMIN_JWT_SECRET} 由 {@link DynamicPropertySource} 注入 ⇒
 *       yml 里的 {@code ${ADMIN_JWT_SECRET}} 占位符被真解析（不是绕过校验的硬编码）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AdminServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // Nacos 整体不生效 ⇒ 自动配置不注册 NacosServiceManager ⇒ 由 NacosStubConfiguration 补上
                "spring.cloud.nacos.discovery.enabled=false",
                // 服务注册自动注册关掉（否则需要真的 ServiceRegistry + Nacos）
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // gRPC 随机端口 ⇒ 不与本机其它域撞端口（D1 合并后 admin 的 gRPC 口是 8082）
                "grpc.server.port=0",
                "grpc.server.address=127.0.0.1"
        })
@Testcontainers
class AdminHttpContractRealTomcatTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // ⚠️ yml 里是 `${ADMIN_JWT_SECRET}`（**无默认值**）⇒ 必须由外部注入，
        //    否则 AdminApiProperties/AdminRpcProperties 的 @PostConstruct 会 fail-closed 启动失败。
        registry.add("ADMIN_JWT_SECRET", () -> "admin-http-contract-test-secret-0123456789");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * ⚠️ 必须点名 {@code requestMappingHandlerMapping} —— Actuator 的
     * {@code controllerEndpointHandlerMapping} <b>也是</b> {@code RequestMappingHandlerMapping}
     * ⇒ 按类型注入会命中 2 个 ⇒ 上下文起不来。
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 13 个端点（B 出处：AdminController 的 13 个 @PostMapping）。 */
    private static final List<String> ALL_PATHS = List.of(
            "/admin/v1/login", "/admin/v1/create", "/admin/v1/logout", "/admin/v1/getself",
            "/admin/v1/update", "/admin/v1/updateuser", "/admin/v1/delete",
            "/admin/v1/resetuserpassword", "/admin/v1/banuser", "/admin/v1/unbanuser",
            "/admin/v1/getuser", "/admin/v1/getuserlist", "/admin/v1/invite/create");

    // ============== 夹具 ==============

    private HttpEntity<String> json(String body, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.set(headers[i], headers[i + 1]);
        }
        return new HttpEntity<>(body, h);
    }

    /** 解析响应体；解析失败 = 断言失败（不该发生）。 */
    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("响应体不是合法 JSON: [" + raw + "]", e);
        }
    }

    /** 用默认 admin 账号登录，拿到 JWT。 */
    private String login() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/v1/login", json("{\"username\":\"admin\",\"password\":\"admin\"}"), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode data = parse(resp.getBody()).get("data");
        assertNotNull(data, "登录成功时 data 不得为 null");
        String token = data.get("token").asText();
        assertFalse(token.isEmpty(), "登录成功时 token 不得为空串");
        return token;
    }

    // ============== 0. 鉴权路径集合 ↔ 控制器实际映射（自维护的 L1 守卫） ==============

    @Test
    @DisplayName("鉴权路径集合 == AdminController 实际映射的 13 个端点（加端点忘了加集合 ⇒ 立刻红）")
    void pathSetMatchesControllerMappings() {
        Set<String> actual = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (AdminController.class.equals(method.getBeanType())) {
                actual.addAll(info.getPatternValues());
            }
        });

        Set<String> declared = new LinkedHashSet<>();
        declared.addAll(AdminJwtAuthInterceptor.AdminJwtAuthPaths.PUBLIC_PATHS);
        declared.addAll(AdminJwtAuthInterceptor.AdminJwtAuthPaths.PROTECTED_PATHS);

        assertEquals(13, actual.size(), "AdminController 应当恰好映射 13 个端点，实际=" + actual);
        assertEquals(declared, actual,
                "鉴权路径集合必须与控制器实际映射逐条一致（public 2 + protected 11）");
    }

    // ============== 1. 13 端点全 POST ==============

    @Test
    @DisplayName("13 个端点全是 POST：GET 一律 405（且 405 发生在鉴权之前）")
    void allThirteenEndpointsArePostOnly() {
        for (String path : ALL_PATHS) {
            ResponseEntity<String> resp = rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(json("{}").getHeaders()), String.class);
            assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode(),
                    "GET " + path + " 必须 405（B 侧 13 个 handler 全是 @PostMapping）");
        }
    }

    // ============== 2. public 放行 ==============

    @Test
    @DisplayName("public 路径（/login、/create）无 token 不返回 401")
    void publicPathsAreNot401() {
        // /login：正常账号 ⇒ 200 + 信封
        ResponseEntity<String> login = rest.postForEntity(
                "/admin/v1/login", json("{\"username\":\"admin\",\"password\":\"admin\"}"), String.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertTrue(login.getBody().startsWith("{\"code\":"), "信封必须是 code 打头：" + login.getBody());

        // /create：请求体缺必填 ⇒ 落 400（**不是 401**）⇒ 证明请求确实进了 handler
        ResponseEntity<String> create = rest.postForEntity(
                "/admin/v1/create", json("{}"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, create.getStatusCode(),
                "public 路径不该被鉴权拦下（401）；期望落到 Bean Validation 的 400");
    }

    // ============== 3. protected 401 ==============

    @Test
    @DisplayName("protected 路径：缺 Authorization 头 ⇒ 401 + 完整信封")
    void protectedPathWithoutTokenIs401() {
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/getself", json("{}"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        JsonNode body = parse(resp.getBody());
        assertEquals(1006, body.get("code").asInt(), "缺 token 的业务码必须是 1006（ERROR_TOKEN_RUNTIME）");
        assertTrue(body.has("data"), "401 也必须是完整信封（data 键不得消失）");
    }

    @Test
    @DisplayName("protected 路径：坏签名 token ⇒ 401")
    void protectedPathWithBrokenTokenIs401() {
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/getself",
                json("{}", "Authorization", "Bearer not-a-jwt"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals(1006, parse(resp.getBody()).get("code").asInt());
    }

    @Test
    @DisplayName("protected 路径：有效 token ⇒ 200 + 信封 + data 非空")
    void protectedPathWithValidTokenIs200() {
        String token = login();
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/getself",
                json("{}", "Authorization", "Bearer " + token), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = parse(resp.getBody());
        assertEquals(200, body.get("code").asInt());
        assertNotNull(body.get("data"), "getself 成功时 data 不得为 null");
        assertFalse(body.get("data").isNull(), "getself 成功时 data 不得是 null");
    }

    // ============== 4. 信封形状 ==============

    @Test
    @DisplayName("成功响应：body 恰好三键 code/msg/data，且 data 为 null 时键仍在")
    void envelopeHasExactlyThreeKeys() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/v1/login", json("{\"username\":\"admin\",\"password\":\"admin\"}"), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON),
                "成功响应必须是 JSON，实际=" + resp.getHeaders().getContentType());

        JsonNode body = parse(resp.getBody());
        List<String> fieldNames = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fieldNames::add);
        assertEquals(List.of("code", "msg", "data"), fieldNames,
                "信封必须恰好是 code/msg/data 三键且顺序不变（B 侧 Response 三字段）");
        assertEquals(200, body.get("code").asInt());
        assertNotNull(body.get("msg"), "msg 键不得缺失");
    }

    // ============== 5. 400 + text/plain + nosniff ==============

    @Test
    @DisplayName("必填字段缺失 ⇒ 400 + text/plain + nosniff + 尾随 \\n（形状对齐 B 侧 http.Error）")
    void missingRequiredFieldIs400PlainTextWithNosniff() {
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/login", json("{}"), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        MediaType ct = resp.getHeaders().getContentType();
        assertNotNull(ct, "400 响应必须带 Content-Type");
        assertTrue(ct.getType().equals("text") && ct.getSubtype().equals("plain"),
                "400 必须是 text/plain，实际=" + ct);
        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"),
                "B 侧 http.Error 会写 X-Content-Type-Options: nosniff");
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isEmpty(), "400 的 body 不得为空");
        assertTrue(resp.getBody().endsWith("\n"),
                "B 侧 http.Error 用 Fprintln ⇒ body 必须带尾随换行，实际=[" + resp.getBody() + "]");
        // 400 走的是裸文本，**不是**信封 ⇒ 不许出现 code/msg/data
        assertFalse(resp.getBody().contains("\"code\""), "400 不得被包成信封");
    }

    @Test
    @DisplayName("body 完全缺失 ⇒ 400（空 body 与 {} 同等对待）")
    void absentBodyIs400() {
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/login",
                new HttpEntity<>(null, json("{}").getHeaders()), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getHeaders().getContentType().getType().equals("text"),
                "空 body 的 400 也应是 text/plain");
    }

    // ============== 6. 415 ==============

    @Test
    @DisplayName("Content-Type 不是 JSON ⇒ 400 + text/plain（B 侧没有 415 这个状态码）")
    void nonJsonContentTypeIs400PlainText() {
        // B 出处：go-zero v1.10.2 rest/httpx/requests.go:153 withJsonBody =
        //   r.ContentLength > 0 && ContentType 含 application/json
        // ⇒ 非 JSON content-type ⇒ 跳过 JSON 解析 ⇒ 走 UnmarshalJsonMap(nil, v)
        // ⇒ 必填字段缺失 ⇒ 400 + text/plain ⇒ **B 侧不存在 415**
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/login",
                new HttpEntity<>("username=admin&password=admin", h), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "非 JSON content-type 必须与 B 侧一致地给 400（不是 Spring 默认的 415）");
        assertTrue(resp.getHeaders().getContentType().getType().equals("text"),
                "400 必须是 text/plain，实际=" + resp.getHeaders().getContentType());
        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"));
    }

    // ============== 7. 黑名单 ==============

    @Test
    @DisplayName("token 进黑名单 ⇒ 401；且黑名单 key 前缀是 admin:（不与 user 域混用）")
    void blacklistedTokenIs401AndUsesAdminScopedKey() {
        String token = login();

        // 先确认 token 此刻可用（否则后面 401 无法归因于黑名单）
        ResponseEntity<String> before = rest.postForEntity("/admin/v1/getself",
                json("{}", "Authorization", "Bearer " + token), String.class);
        assertEquals(HttpStatus.OK, before.getStatusCode(), "前置条件：token 本应可用");

        // 写入黑名单 —— 前缀必须是 admin:jwt_blacklist:
        redis.opsForValue().set("admin:jwt_blacklist:" + token, "1");

        ResponseEntity<String> after = rest.postForEntity("/admin/v1/getself",
                json("{}", "Authorization", "Bearer " + token), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, after.getStatusCode(), "黑名单内的 token 必须 401");

        assertTrue(Boolean.TRUE.equals(redis.hasKey("admin:jwt_blacklist:" + token)),
                "黑名单 key 必须落在 admin:jwt_blacklist: 前缀下");
        assertFalse(Boolean.TRUE.equals(redis.hasKey("user:jwt_blacklist:" + token)),
                "admin 域不得写 user 域前缀（两个域的黑名单必须隔离）");
    }

    // ============== 8. 路由面 ==============

    @Test
    @DisplayName("未知路径 ⇒ 404 + B 侧精确形状（text/plain + nosniff + 固定文案），且不进鉴权")
    void unknownPathIs404() {
        ResponseEntity<String> resp = rest.postForEntity("/admin/v1/not-exist", json("{}"), String.class);

        // B 出处：go-zero v1.10.2 rest/engine.go:244-268 notFoundHandler
        //   —— 未知路径另起一条链（链里**没有 JWT 鉴权**）⇒ 404（不是 401）
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "未知路径必须 404（B 侧 notFoundHandler 不经鉴权）；不是 401（鉴权误伤）也不是 500");
        // 形状：共享层 RouterErrorResponses 逐字复刻 B 侧 http.Error
        //   ⇒ Content-Type: text/plain; charset=utf-8 + X-Content-Type-Options: nosniff
        //   ⇒ body = "404 page not found\n"
        assertTrue(resp.getHeaders().getContentType().getType().equals("text"),
                "404 必须是 text/plain，实际=" + resp.getHeaders().getContentType());
        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("404 page not found\n", resp.getBody(), "404 body 必须逐字节等于 B 侧的形状");
    }

    @Test
    @DisplayName("登录失败（密码错）⇒ HTTP 200 + 业务码 1002（不是 401）")
    void wrongPasswordIs200WithBusinessCode() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/v1/login", json("{\"username\":\"admin\",\"password\":\"wrong\"}"), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "业务失败仍是 HTTP 200（go-zero 口径：成败由 body 的 code 表达）");
        assertEquals(1002, parse(resp.getBody()).get("code").asInt(),
                "密码错 ⇒ 1002（ERROR_LOGIN_WRONG，B 侧 login 端点专属映射，非通用表的 1006）");
    }

    @Test
    @DisplayName("登录用户不存在 ⇒ HTTP 200 + 业务码 1003")
    void unknownUserIs200WithNotFoundCode() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/v1/login", json("{\"username\":\"no-such-admin\",\"password\":\"x\"}"), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1003, parse(resp.getBody()).get("code").asInt());
    }

    @Test
    @DisplayName("data 为 null 时键仍在（不许 @JsonInclude(NON_NULL)）")
    void nullDataStillHasKey() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/v1/login", json("{\"username\":\"no-such-admin\",\"password\":\"x\"}"), String.class);
        JsonNode body = parse(resp.getBody());
        assertTrue(body.has("data"), "data 键必须存在（B 侧 Response 无 omitempty）");
        assertTrue(body.get("data").isNull(), "失败响应的 data 应为 null");
        assertNull(body.get("data").textValue(), "null 节点的 textValue 应为 null");
    }
}
