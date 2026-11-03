package com.rag.user.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.security.JwtUtil;
import com.rag.user.interceptor.JwtAuthFilter;
import com.rag.user.support.TestPostgres;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>user 域 HTTP 契约（真 Tomcat · 真 PostgreSQL · 真 JWT）</b>。
 *
 * <h2>⚠️ 为什么必须是「真 Tomcat」而不是 MockMvc</h2>
 * <p>AGENTS.md §八 检查清单：「<b>HTTP 断言走真 Tomcat</b> —— 断言 {@code Content-Type} /
 * {@code nosniff} / 状态行时<b>不用 MockMvc</b>」。
 * <p>更根本的原因：B 侧的 {@code DRIFT-FRAMEWORK-001} 就是「{@code HttpMiddleware} 的
 * {@code setStatus} 没 propagate 到 underlying ⇒ 把全部 401 / 5003 静默降级成 200」——
 * <b>MockMvc standalone 测不出来</b>，因为它不挂真实 filter 链。
 * ⇒ 本类用 {@code webEnvironment = RANDOM_PORT} + 真 {@code TestRestTemplate} ⇒
 * <b>filter 链 / 拦截器 / advice / 序列化全部是真的</b>。
 *
 * <h2>本类守的 6 条（逐条对应一个 L1 契约或一个「可能悄悄坏掉」的机制）</h2>
 * <ol>
 *   <li><b>L1 · 路由与 method</b>：9 个端点全 POST ⇒ {@code GET /user/v1/login} ⇒ <b>405 裸状态码</b>。</li>
 *   <li><b>L1 · public 路径集合</b>：{@code /user/v1/login} 与 {@code /user/v1/register} <b>不挂 JWT</b>
 *       ⇒ 无 Authorization 也走到业务层（拿到业务码，不是 401）。</li>
 *   <li><b>L1 · 信封形状</b>：{@code {code,msg,data}} 三字段恒在（{@code data} 为 null 时也在）。</li>
 *   <li><b>鉴权层失败 ⇒ HTTP 401</b>（不是 200 + 业务码）⇒ 且 body 仍是完整信封。</li>
 *   <li><b>400 的形状</b>：Bean Validation 失败 ⇒ <b>text/plain ＋ nosniff</b>（D-01 降级，只换文案）。</li>
 *   <li><b>🔴 JWT 验签真的在 Filter 里做</b>（B 侧 {@code DRIFT-USERAPI-009②}）：探针 filter 读
 *       {@link JwtAuthFilter#VERIFICATION_SOURCE_ATTRIBUTE} ⇒ 必须是 {@code "filter"}
 *       ⇒ <b>不是</b> interceptor 的兜底就地验签（{@code "inline"}）。</li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>本类跑的是 <b>C 侧单侧</b>：断言的是「C 侧产出的形状与 B 侧台账里记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>的范畴（见本域 DRIFT 台账的证据强度列），
 * 本类**不冒充**该证据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(UserApiHttpContractTest.JwtVerificationProbeConfiguration.class)
class UserApiHttpContractTest {

    /** 与 {@code src/test/resources/application-test.properties} 里的密钥<b>必须一致</b>。 */
    private static final String SECRET = "ae0536f9-6450-4606-8e13-5a19ed505da0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    // ============== 1. L1 · 路由与 method ==============

    @Test
    @DisplayName("🔴 L1：9 个端点全 POST ⇒ GET /user/v1/login ⇒ 405（裸状态码 · 无 body）")
    void getOnPostOnlyEndpointIs405() {
        ResponseEntity<String> resp = rest.getForEntity("/user/v1/login", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(405);
        assertThat(resp.getBody()).isNullOrEmpty();
    }

    @Test
    @DisplayName("🔴 L1：不在 /user/v1/** 下的未知路径 ⇒ 500 + 信封（B 侧兜底 handler 吃掉 404）")
    void unknownPathOutsideUserPrefixIs500Envelope() throws Exception {
        // ⚠️ **不是 404** —— B 侧的兜底 `@ExceptionHandler(Exception.class) handleAny`
        //   （B: `GlobalExceptionHandler.java:333-345`）会把 Spring 的 404 异常
        //   （`NoResourceFoundException` / `NoHandlerFoundException`）一并兜住
        //   ⇒ 返回 **HTTP 500 + {code:5001,msg,data}**。
        //   ⇒ C 侧的 `UserApiExceptionHandler#handleAny` 与 B 侧**逐字同构**
        //   ⇒ ⇒ 这里断言 500 ＋ 信封，**不是** 404。
        //   ⚠️ 这是 **B 侧自身**的语义（标准 Spring 语义本该是 404）⇒ 按 D16 **继承**，不回溯修改。
        ResponseEntity<String> resp = rest.postForEntity("/not-exist", "{}", String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        JsonNode body = MAPPER.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(5001);
        assertThat(body.has("msg")).isTrue();
    }

    @Test
    @DisplayName("🔴 L1：/user/v1/** 下的未知路径 ⇒ 401（拦截器先跑）—— 与 B 侧同形，非 404")
    void unknownPathInsideUserPrefixIs401() {
        // ⚠️ **为什么是 401 而不是 404**（这是本域最容易写错的一条）：
        //   B 侧 `WebMvcConfig` 用 `registry.addInterceptor(jwtAuthInterceptor).addPathPatterns("/user/v1/**")`
        //   ⇒ 拦截器挂在**前缀**上 ⇒ Spring 的静态资源 handler（`/**`）把未映射路径兜成「有 handler」
        //   ⇒ ⇒ 拦截器**先于** 404 触发 ⇒ 缺 token ⇒ 401。
        //   ⇒ C 侧 `WebMvcConfig` 用**同一前缀**注册 ⇒ 行为与 B 侧逐字一致。
        //   ⚠️ B 侧 Go 端对未注册路径给 404（go-zero 按路由注册挂中间件）⇒ 那是 **B 侧自身**的差异
        //   （D16：B 侧为唯一 oracle ⇒ C 侧继承 B 侧 Java 的形态，不回溯 Go）。
        ResponseEntity<String> resp = postJson("/user/v1/not-exist", "{}");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ============== 2. L1 · public 路径集合 ==============

    @Test
    @DisplayName("🔴 L1：public 路径（login）不挂 JWT ⇒ 无 Authorization 也走到业务层（不是 401）")
    void publicLoginPathDoesNotRequireJwt() throws Exception {
        ResponseEntity<String> resp = postJson("/user/v1/login",
                "{\"username\":\"ghost-user-never-exists\",\"password\":\"whatever\"}");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.getBody());
        // 查无此人 ⇒ 业务层给 1002（用户名或密码错 · 与 B 侧同码）
        assertThat(body.get("code").asInt()).isEqualTo(1002);
    }

    // ============== 3. L1 · 信封形状 ==============

    @Test
    @DisplayName("🔴 L1：响应信封恒为 {code,msg,data} 三字段（data 为 null 时也在）")
    void envelopeAlwaysHasThreeFields() throws Exception {
        ResponseEntity<String> resp = postJson("/user/v1/login",
                "{\"username\":\"ghost-user-never-exists\",\"password\":\"whatever\"}");

        JsonNode body = MAPPER.readTree(resp.getBody());
        assertThat(body.has("code")).isTrue();
        assertThat(body.has("msg")).isTrue();
        assertThat(body.has("data")).isTrue();
        assertThat(body.size()).isEqualTo(3);
    }

    // ============== 4. 鉴权层失败 ⇒ 401 ==============

    @Test
    @DisplayName("🔴 protected 路径缺 Authorization ⇒ HTTP 401（不是 200 + 业务码）")
    void protectedPathWithoutTokenIs401() throws Exception {
        ResponseEntity<String> resp = postJson("/user/v1/get", "{}");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        JsonNode body = MAPPER.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(1006);
        assertThat(body.has("data")).isTrue();
    }

    @Test
    @DisplayName("🔴 protected 路径带坏签名 ⇒ HTTP 401")
    void protectedPathWithGarbageTokenIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer not-a-jwt");

        ResponseEntity<String> resp = rest.exchange("/user/v1/get", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ============== 5. 400 的形状 ==============

    @Test
    @DisplayName("🔴 必填字段缺失 ⇒ 400 + text/plain + nosniff（D-01 降级：只换文案，不换结构）")
    void missingRequiredFieldIsPlainText400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.exchange("/user/v1/login", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString())
                .startsWith("text/plain");
        assertThat(resp.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("🔴 400 的「报哪个字段」是确定的，且与 B 侧 GoStyleRequired 判定序一致（2026-11-03 对拍抓出）")
    void validationViolationSelectionIsDeterministicAndMatchesB() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // ① login：声明序 = username, password ⇒ 多个违规并存时必须**稳定**报 username
        //    （原实现用 getFieldErrors().findFirst() ⇒ Hibernate Validator 的违规集合是 HashSet
        //      ⇒ 顺序随 hash 变 ⇒ 实测 6 次里出 3 种不同文案）
        String first = null;
        for (int i = 0; i < 8; i++) {
            ResponseEntity<String> resp = rest.exchange("/user/v1/login", HttpMethod.POST,
                    new HttpEntity<>("{}", headers), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            String body = resp.getBody();
            assertThat(body).isNotNull();
            if (first == null) {
                first = body;
            } else {
                assertThat(body)
                        .as("同一输入的 400 响应体必须逐次相同（确定性）")
                        .isEqualTo(first);
            }
        }
        // ② 报的是**声明序里的第一个字段** = username（与 B 侧 LoginReq 的判定序一致）
        assertThat(first).startsWith("username ");

        // ③ register：声明序 = username, password, email ⇒ 同样稳定报 username
        String regFirst = null;
        for (int i = 0; i < 8; i++) {
            ResponseEntity<String> resp = rest.exchange("/user/v1/register", HttpMethod.POST,
                    new HttpEntity<>("{}", headers), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            String body = resp.getBody();
            assertThat(body).isNotNull();
            if (regFirst == null) {
                regFirst = body;
            } else {
                assertThat(body).as("register 的 400 响应体必须逐次相同").isEqualTo(regFirst);
            }
        }
        assertThat(regFirst).startsWith("username ");
    }

    // ============== 6. 🔴 JWT 验签真的在 Filter 里（DRIFT-USERAPI-009②） ==============

    @Test
    @DisplayName("🔴 验签来源 = filter（不是 interceptor 的兜底就地验签）⇒ B 侧 009② 闭环")
    void jwtVerificationHappensInFilter() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer whatever-not-verified");

        ResponseEntity<String> resp = rest.exchange("/user/v1/get", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(resp.getHeaders().getFirst(JwtVerificationProbeFilter.HEADER))
                .as("interceptor 必须消费到 Filter 的验签结果；落到 inline ⇒ 验签退回 3s 预算之内")
                .isEqualTo(JwtAuthFilter.SOURCE_FILTER);
    }

    // ============== 7. 端到端 happy path（真 PG 落库 + 真 bcrypt + 真 JWT 签发） ==============

    @Test
    @DisplayName("🔴 签名合法但用户不存在 ⇒ 不是 401，而是 200 + 业务码 1003（鉴权层与业务层分界）")
    void validSignatureButUnknownUserIsBusinessError() throws Exception {
        String token = signToken(999_999_999L);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<String> resp = rest.exchange("/user/v1/get", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(1003);
    }

    @Test
    @DisplayName("端到端：register → login → get（真 PG + 真 bcrypt + 真 JWT）")
    void registerLoginGetHappyPath() throws Exception {
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pw-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";

        // ---- register（public） ----
        ResponseEntity<String> regResp = postJson("/user/v1/register",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"email\":\"" + email + "\"}");
        assertThat(regResp.getStatusCode().value()).isEqualTo(200);
        JsonNode regBody = MAPPER.readTree(regResp.getBody());
        assertThat(regBody.get("code").asInt()).isEqualTo(200);
        assertThat(regBody.get("data").get("uid").asText()).isNotEmpty();

        // ---- login（public）⇒ 拿到 token ----
        ResponseEntity<String> loginResp = postJson("/user/v1/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        JsonNode loginBody = MAPPER.readTree(loginResp.getBody());
        assertThat(loginBody.get("code").asInt()).isEqualTo(200);
        String token = loginBody.get("data").get("token").asText();
        assertThat(token).isNotEmpty();

        // ---- get（protected）⇒ 用刚签发的 token ----
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<String> getResp = rest.exchange("/user/v1/get", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        assertThat(getResp.getStatusCode().value()).isEqualTo(200);
        JsonNode getBody = MAPPER.readTree(getResp.getBody());
        assertThat(getBody.get("code").asInt()).isEqualTo(200);
        assertThat(getBody.get("data").get("found").asBoolean()).isTrue();
        assertThat(getBody.get("data").get("user").get("username").asText()).isEqualTo(username);
        // uid 在 wire 上是**字符串**（B 侧同口径）
        assertThat(getBody.get("data").get("user").get("uid").isTextual()).isTrue();
    }

    // ============== helpers ==============

    private ResponseEntity<String> postJson(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    /**
     * <p><b>验签来源探针</b> —— 把 request attribute {@code verificationSource} 抄进响应头。
     *
     * <p>⚠️ <b>为什么需要它</b>：{@code JwtAuthInterceptor} 在拿不到 Filter 的结果时会<b>兜底就地验签</b>
     * （并把来源标成 {@code inline}）⇒ ⇒ 「验签到底发生在 Filter 里还是 Interceptor 里」
     * <b>从外部看不出来</b> ⇒ ⇒ 必须有一个<b>运行期探针</b>把来源读出来。
     *
     * <p>⚠️ <b>顺序</b>：本探针排在共享层的 {@code HttpMiddleware}(+7) / {@code MaxBytes}(+8) /
     * {@code JsonBodyLimit}(+9) <b>之后</b> ⇒ ⇒ 它在 {@code JwtAuthFilter}(+6) 之后 ⇒
     * ⇒ 读到的 attribute 一定已经是 Filter / Interceptor 写好的值。
     */
    @TestConfiguration
    static class JwtVerificationProbeConfiguration {

        @org.springframework.context.annotation.Bean
        FilterRegistrationBean<JwtVerificationProbeFilter> jwtVerificationProbeFilter() {
            FilterRegistrationBean<JwtVerificationProbeFilter> registration =
                    new FilterRegistrationBean<>(new JwtVerificationProbeFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    static class JwtVerificationProbeFilter implements Filter {

        static final String HEADER = "X-Jwt-Verification-Source";

        @Override
        public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) resp;
            Object source = request.getAttribute(JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE);
            if (source != null) {
                response.setHeader(HEADER, String.valueOf(source));
            }
            chain.doFilter(req, resp);
        }
    }

    /**
     * 本类<b>不需要</b> JWT 签发辅助（happy path 用的是登录接口真签发的 token），
     * 但保留一个显式的签发入口，便于将来加「指定 uid 的 token」用例。
     */
    static String signToken(long uid) {
        long nowSec = System.currentTimeMillis() / 1000L;
        return JwtUtil.getToken(SECRET, nowSec, 3600L, uid);
    }
}
