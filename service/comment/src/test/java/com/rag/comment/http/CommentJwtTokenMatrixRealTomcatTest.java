package com.rag.comment.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.CommentServiceApplication;
import com.rag.comment.config.CommentApiProperties;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>🔴 comment 域 JWT 接受矩阵的守门网（真 Tomcat · 真线路）</b> —— 锁住 comment-api 的
 * token 接受 / 拒绝行为与 B 侧真源一致。
 *
 * <h2>🔴 为什么必须过<b>真线路</b>而不是直接调拦截器</h2>
 * <p>B 侧的矩阵测试（{@code CommentJwtTokenMatrixGateTest}）直接 new 一个拦截器、用
 * {@code MockHttpServletRequest} 打进去。
 * <p>⇒ ⇒ 那样<b>证不了</b>「真 Tomcat + 真过滤器链 + 真 advice」下 401 的**响应形状**
 * （空 body、不是 {@code {"code":...}}）⇒ ⇒ 本类把同一张矩阵搬到<b>真 HTTP 线路</b>上打，
 * 每一条同时断言「鉴权层的判定」与「落到线上的形状」。
 *
 * <h2>🔴 矩阵的判据出处（B 侧）</h2>
 * <pre>
 *   golang-jwt/jwt/v4@v4.5.2
 *     map_claims.go:124-141  MapClaims.Valid() → VerifyExpiresAt / VerifyIssuedAt / VerifyNotBefore
 *     map_claims.go:37-43    VerifyExpiresAt：exp 缺失 ⇒ !req ⇒ 有效
 *     map_claims.go:63-85    VerifyIssuedAt：iat 缺失 ⇒ 有效；是数字 ⇒ now >= iat
 *     claims.go:250-255      verifyIat：now >= iat（**无时钟偏移容忍**）
 *     hmac.go:49-74         SigningMethodHMAC.Verify：**不限制 alg** ⇒ HS256/384/512 都能验
 *     map_claims.go:118-121  'none' 由 Parse 显式拒绝（"signature type is not allowed"）
 *   go-zero@v1.10.2
 *     rest/token/tokenparser.go:81-84  doParseToken → request.ParseFromRequest
 *     rest/token/tokenparser.go:122-124 newParser() = jwt.NewParser(jwt.WithJSONNumber())
 *                                  ⇒ **没有** WithValidMethods ⇒ 不限制 alg
 * </pre>
 *
 * <h2>🔴 HS384 / HS512 这两条：断言的是 <b>C 侧当前的行为</b>，不是 Go 的</h2>
 * <p>jjwt 的 {@code verifyWith(key)} 把 key <b>绑死</b>在一个算法上
 * （{@code Keys.hmacShaKeyFor} 对 32 字节 ⇒ HmacSHA256）
 * ⇒ token 头写 HS384/HS512 时直接抛异常 ⇒ 被拒。
 * <p>⇒ ⇒ 这两条是<b>已知差异</b>（B 侧已登记为签字偏离 CAND-07，<b>RAG 更严的一侧</b>）
 * ⇒ ⇒ 本类<b>保持现状</b>；若将来决定对齐 Go（放宽），<b>这两条断言必须同步改</b>，
 * 否则测试会「因为错误的原因而绿」。
 *
 * <h3>AGENTS.md §3.2 三段式</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：① 矩阵从「直接调拦截器」搬到<b>真 Tomcat 线路</b>上
 *       （⇒ 顺带钉住 401 的<b>空 body</b> 形状 —— B 侧那份测不出来）；② 补了
 *       <b>fail-closed 密钥守卫</b>的容器级证据（B 侧 {@code CommentApiPropertiesSecretGuardTest}
 *       的 8 条压缩成 4 条关键路径）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 矩阵的 11 种形态逐条对应 B 侧表格；
 *       密钥守卫的两条拒绝条件（空 / 未解析占位符）与 B 侧逐字一致。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code comment-api/…/api/interceptor/CommentJwtTokenMatrixGateTest.java:84-312}</li>
 *         <li>{@code comment-api/…/api/config/CommentApiPropertiesSecretGuardTest.java:51-341}</li>
 *         <li>{@code comment-api/…/api/auth/CommentJwtSignatureVerifier.java:82-116}</li>
 *         <li>{@code comment-api/…/api/interceptor/CommentJwtAuthInterceptor.java:142}</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>🧬 变异实测</h2>
 * <p>把验签器里的 {@code iat} 校验整段删掉 ⇒ {@link #issuedAtInTheFutureIsRejected()} 变红
 * ⇒ 守门网不是假绿。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 MockMvc（走真 Tomcat）；</li>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CommentServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class CommentJwtTokenMatrixRealTomcatTest {

    /** {@code ErrorTokenRuntime} = 1006（B 侧 {@code errmsg.go:56}）。 */
    private static final int ERROR_TOKEN_RUNTIME = 1006;

    /** {@code ErrorSubjectNotExist} = 1012（B 侧 {@code errmsg.go:58}）—— 探针的正常结局。 */
    private static final int ERROR_SUBJECT_NOT_EXIST = 1012;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Environment env;

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 取验签密钥 —— <b>从 Spring 的 {@link Environment} 读</b>（与
     * {@code src/test/resources/application-test.properties} 保持同源 ⇒ 不会漂）。
     */
    private String secret() {
        String s = env.getProperty("comment-api.user-auth.access-secret");
        if (s == null || s.isBlank()) {
            s = env.getProperty("USER_JWT_SECRET");
        }
        assertThat(s)
                .as("测试上下文里必须能读到 comment-api.user-auth.access-secret")
                .isNotBlank();
        return s;
    }

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

    /**
     * 手工拼 token —— 不用 jjwt 的 builder，是为了能指定<b>任意 HMAC 算法</b>
     * （jjwt 的 {@code signWith} 要求 key 长度匹配算法，造不出与 Go 字节一致的 HS384/HS512）。
     *
     * <p>⚠️ 字节构造与 B 侧探针<b>同款</b>（B 侧 {@code CommentJwtTokenMatrixGateTest#token}）。
     */
    private static String token(String secret, String alg, Map<String, Object> claims) {
        String header = "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}";
        StringBuilder payload = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : claims.entrySet()) {
            if (!first) {
                payload.append(',');
            }
            first = false;
            payload.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) {
                payload.append(v);
            } else {
                payload.append('"').append(v).append('"');
            }
        }
        payload.append('}');
        String signingString = b64(header.getBytes(StandardCharsets.UTF_8))
                + "." + b64(payload.toString().getBytes(StandardCharsets.UTF_8));
        if ("none".equals(alg)) {
            return signingString + ".";
        }
        String macAlg = "HS256".equals(alg) ? "HmacSHA256"
                : "HS384".equals(alg) ? "HmacSHA384" : "HmacSHA512";
        try {
            Mac mac = Mac.getInstance(macAlg);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlg));
            return signingString + "." + b64(mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static Map<String, Object> claims(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static long nowSec() {
        return Instant.now().getEpochSecond();
    }

    /**
     * 探针 body —— <b>刻意用 {@code target_type="post"}</b>：
     * B 侧的 article 回落只认 {@code targetType == "article"}（B 侧 {@code subject_fallback.go:13}）
     * ⇒ 用别的类型 ⇒ 回落立即返回 null ⇒ <b>不发任何出向 gRPC</b> ⇒ 探针快且确定。
     */
    private static final String PROBE_BODY = """
            {"target_type":"post","target_id":"jwt-matrix-probe","root_id":0,
             "parent_id":0,"content":"hi","meta":""}""";

    /** 打一次探针。 */
    private ResponseEntity<String> probe(String token) {
        return rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json(PROBE_BODY, "Authorization", "Bearer " + token), String.class);
    }

    /** 断言：鉴权层<b>放行</b>（⇒ HTTP 200，不是 401）。 */
    private JsonNode assertAcceptedByAuthLayer(String token, String why) {
        ResponseEntity<String> resp = probe(token);
        assertThat(resp.getStatusCode().value())
                .as("鉴权层应当**放行**（%s）", why)
                .isEqualTo(200);
        return parse(resp.getBody());
    }

    /** 断言：鉴权层<b>拒绝</b>（⇒ HTTP 401 + **空 body**）。 */
    private void assertRejectedByAuthLayer(String token, String why) {
        ResponseEntity<String> resp = probe(token);
        assertThat(resp.getStatusCode().value())
                .as("鉴权层应当**拒绝**（%s）", why)
                .isEqualTo(401);
        assertThat(resp.getBody())
                .as("B 侧 unauthorized() 只 WriteHeader(401)、不写任何字节 ⇒ body 必须为空")
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    // ==================================================================
    // 1. 与 Go 一致的 8 条
    // ==================================================================

    @Test
    @DisplayName("✅ HS256 + exp + userId ⇒ 鉴权层**放行**（与 Go 一致）")
    void hs256NormalIsAccepted() {
        JsonNode body = assertAcceptedByAuthLayer(
                token(secret(), "HS256", claims("userId", 1001, "exp", nowSec() + 3600, "iat", nowSec())),
                "标准形态");
        assertThat(body.get("code").asInt())
                .as("放行之后 ⇒ 业务层跑到了下游 ⇒ 探针的正常结局是 1012（评论区不存在）")
                .isEqualTo(ERROR_SUBJECT_NOT_EXIST);
    }

    @Test
    @DisplayName("✅ HS256 + **缺 exp** ⇒ 鉴权层**放行**（Go map_claims.go:37-43：exp 缺失 ⇒ !req ⇒ 有效）")
    void missingExpIsAccepted() {
        assertAcceptedByAuthLayer(
                token(secret(), "HS256", claims("userId", 1001, "iat", nowSec())),
                "exp 缺失");
    }

    @Test
    @DisplayName("✅ HS256 + **iat 在未来** ⇒ 鉴权层**拒绝**（Go claims.go:250-255：now >= iat）")
    void issuedAtInTheFutureIsRejected() {
        assertRejectedByAuthLayer(
                token(secret(), "HS256",
                        claims("userId", 1001, "exp", nowSec() + 3600, "iat", nowSec() + 3600)),
                "iat 在未来");
    }

    @Test
    @DisplayName("✅ HS256 + nbf 在未来 ⇒ 鉴权层**拒绝**（与 Go 一致）")
    void notBeforeInTheFutureIsRejected() {
        assertRejectedByAuthLayer(
                token(secret(), "HS256",
                        claims("userId", 1001, "exp", nowSec() + 3600, "iat", nowSec(), "nbf", nowSec() + 3600)),
                "nbf 在未来");
    }

    @Test
    @DisplayName("✅ HS256 + 已过期 ⇒ 鉴权层**拒绝**（与 Go 一致）")
    void expiredIsRejected() {
        assertRejectedByAuthLayer(
                token(secret(), "HS256",
                        claims("userId", 1001, "exp", nowSec() - 3600, "iat", nowSec() - 7200)),
                "已过期");
    }

    @Test
    @DisplayName("✅ alg=none ⇒ 鉴权层**拒绝**（Go：'none' signature type is not allowed）")
    void noneAlgorithmIsRejected() {
        assertRejectedByAuthLayer(
                token(secret(), "none", claims("userId", 1001, "exp", nowSec() + 3600)),
                "alg=none");
    }

    @Test
    @DisplayName("✅ HS256 + 签名错 ⇒ 鉴权层**拒绝**（与 Go 一致）")
    void badSignatureIsRejected() {
        String t = token(secret(), "HS256", claims("userId", 1001, "exp", nowSec() + 3600));
        // ⚠️ 必须改**签名段中部**：base64 末位有 2 bit 是填充位，改末位可能解码出同样的字节。
        String[] parts = t.split("\\.");
        char mid = parts[2].charAt(parts[2].length() / 2);
        parts[2] = parts[2].substring(0, parts[2].length() / 2)
                + (mid == 'A' ? 'B' : 'A')
                + parts[2].substring(parts[2].length() / 2 + 1);
        assertRejectedByAuthLayer(String.join(".", parts), "签名错");
    }

    @Test
    @DisplayName("✅ userId claim 是字符串 / 小数 ⇒ 鉴权层**放行**（后续由业务层判 1006）")
    void nonNumericUserIdPassesAuthLayer() {
        // ⚠️ **小数** claim：jjwt 解成 Double ⇒ 业务层判 1006（不截断）。
        JsonNode decimal = assertAcceptedByAuthLayer(
                token(secret(), "HS256", claims("userId", 1.5d, "exp", nowSec() + 3600)),
                "小数 claim");
        assertThat(decimal.get("code").asInt())
                .as("小数 claim ⇒ 业务层判 1006（Go 的 json.Number.Int64() 对 1.5 失败）")
                .isEqualTo(ERROR_TOKEN_RUNTIME);

        // ⚠️ **字符串** claim：comment 域**接受**可解析为 int64 的字符串（与 like 域相反）。
        JsonNode numericString = assertAcceptedByAuthLayer(
                token(secret(), "HS256", claims("userId", "1001", "exp", nowSec() + 3600)),
                "数字字符串 claim");
        assertThat(numericString.get("code").asInt())
                .as("数字字符串 claim ⇒ comment 域**放行**（strconv.ParseInt 成功）")
                .isNotEqualTo(ERROR_TOKEN_RUNTIME);
    }

    // ==================================================================
    // 2. 与 Go 不同的 2 条：已登记为签字偏离（CAND-07）
    // ==================================================================

    @Test
    @DisplayName("🔴 HS384 ⇒ C 侧**拒绝**（Go 接受）—— 已登记为签字偏离 CAND-07")
    void hs384IsRejected() {
        assertRejectedByAuthLayer(
                token(secret(), "HS384", claims("userId", 1001, "exp", nowSec() + 3600, "iat", nowSec())),
                "HS384 —— jjwt 把 key 绑死在 HmacSHA256（Keys.hmacShaKeyFor 对 32 字节）");
    }

    @Test
    @DisplayName("🔴 HS512 ⇒ C 侧**拒绝**（Go 接受）—— 已登记为签字偏离 CAND-07")
    void hs512IsRejected() {
        assertRejectedByAuthLayer(
                token(secret(), "HS512", claims("userId", 1001, "exp", nowSec() + 3600, "iat", nowSec())),
                "HS512 —— 同上");
    }

    // ==================================================================
    // 3. 🔴 fail-closed 密钥守卫（B 出处：B 侧 CommentApiPropertiesSecretGuardTest）
    // ==================================================================

    @Test
    @DisplayName("🔴 空签名密钥 ⇒ 上下文**起不来**（fail-closed 生效，不是「无脑拒绝启动」）")
    void blankSecretFailsTheContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("comment-api.user-auth.access-secret=")
                .run(context -> assertThat(context).hasFailed());

        new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("🔴 绑到**未解析的占位符字面量** ⇒ 上下文起不来（公开可知的「密钥」⇒ 必须挡住）")
    void unresolvedPlaceholderLiteralFailsTheContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("comment-api.user-auth.access-secret=${USER_JWT_SECRET}")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("✅ 正常密钥 ⇒ 上下文正常起来（对照组：证明守卫没有误伤）")
    void properSecretStartsTheContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("comment-api.user-auth.access-secret=" + secret())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CommentApiProperties.class)
                            .getUserAuth().getAccessSecret()).isEqualTo(secret());
                });
    }

    @Test
    @DisplayName("🔴🔴 主 application.yml 的 access-secret 必须真的绑到 UserAuth 嵌套字段（读**真 yaml**）")
    void realYamlBindsSecretIntoNestedUserAuth() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        Resource yaml = new ClassPathResource("application.yml");
                        new YamlPropertySourceLoader().load("main-yaml", yaml)
                                .forEach(ps -> context.getEnvironment().getPropertySources().addLast(ps));
                    } catch (IOException e) {
                        throw new IllegalStateException("读不到主 application.yml（src/main/resources）", e);
                    }
                })
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("USER_JWT_SECRET=" + secret())
                .run(context -> {
                    // ⚠️ 绑定落空时，verifyAccessSecret() 会在 @PostConstruct 抛 ⇒ 上下文起不来。
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CommentApiProperties.class)
                            .getUserAuth().getAccessSecret()).isEqualTo(secret());
                });
    }

    /** 让 {@link CommentApiProperties} 进入真实 Spring 生命周期（{@code @PostConstruct} 才会被调）。 */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(CommentApiProperties.class)
    static class PropsConfig {
    }
}
