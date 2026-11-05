package com.rag.comment.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.CommentServiceApplication;
import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.exception.CommentExceptions;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;
import com.rag.common.security.JwtUtil;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigInteger;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p><b>comment 域 HTTP 出口的「线上形状」逐条断言（真 Tomcat · 真拦截链 · 真 advice）</b>。
 *
 * <h2>🔴 为什么必须走真 Tomcat（不能用 MockMvc）</h2>
 * <p>本类断言的<b>全部</b>都是「线上字节」层面的东西：
 * <ul>
 *   <li>HTTP 状态码（200 / 401 / 404 / 405）；</li>
 *   <li><b>401 的 body 必须是<b>空</b></b>（不是 {@code {"code":...}}）；</li>
 *   <li>Content-Type（envelope 是 {@code application/json}；404 是 {@code text/plain;charset=utf-8}
 *       ＋ {@code X-Content-Type-Options: nosniff}）；</li>
 *   <li>框架级错误（404 / 405）的 body <b>不是</b> envelope。</li>
 * </ul>
 * ⇒ ⇒ MockMvc <b>证不了</b>这些（它不经过容器 ⇒ 不产生真实的状态行与响应头）
 * ⇒ ⇒ 断言必须打在真 HTTP 响应上（AGENTS.md §八「HTTP 断言走真 Tomcat」）。
 *
 * <h2>🔴 为什么只替换「下游领域服务」这一个 bean</h2>
 * <p>B 侧的原守门测试（{@code CommentApiContractTest} / {@code CommentApiBehaviorTest}）替换的是
 * {@code CommentRpcClient}（gRPC 客户端）⇒ 被测语义全在 api 进程内：路由 → 拦截器 → 解析层 →
 * Service → advice → 序列化。
 * <p>C 侧 D2 之后<b>不再经过自我 gRPC</b>（api 直接调 {@link CommentDomainService}）
 * ⇒ ⇒ 对应的替换点就是 {@code CommentDomainService}。
 * <p>⚠️ <b>被替换的只有下游</b>：Tomcat、{@code CommentJwtAuthInterceptor}、{@code @Valid} 校验、
 * {@code CommentApiResponseAdvice} / {@code CommentApiExceptionHandler} <b>全是真件</b>
 * ⇒ ⇒ 本类断言的是「HTTP 层的真实行为」，不是「mock 的行为」。
 *
 * <h2>🔴 本类的核心不变量：<b>两个端点的错误契约不一样</b></h2>
 * <p>同一个下游业务码（1012 评论区不存在）：
 * <ul>
 *   <li>{@code /comment/v1/comment/create} ⇒ <b>HTTP 200 + {code:1012}</b>（原值透传）；</li>
 *   <li>{@code /comment/v1/comment/list} ⇒ <b>HTTP 200 + {code:5001}</b>（B 侧裸 {@code return nil, err}
 *       ⇒ 落进兜底分支）。</li>
 * </ul>
 * ⇒ ⇒ 「照抄统一模板」把 list 也改成 1012 ⇒ 就是破坏 B 侧行为。
 *
 * <h3>AGENTS.md §3.2 三段式</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：① 断言打在<b>真 Tomcat</b> 上（B 侧用 MockMvc，
 *       证不了响应头与状态行）；② 下游替换点从「gRPC 客户端」换成「领域服务」
 *       （D2 域内直连的必然结果，<b>不新增被测语义</b>）；③ 补了「list 端点不做身份绑定」
 *       这条<b>鉴权不对称</b>的显式断言（B 侧只在 javadoc 里写了，没写测试）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 全部期望值逐条来自 B 侧源码 / B 侧测试
 *       （枚举见各用例的 {@code B 出处} 注释）；本类<b>没有</b>引入任何新的行为判断。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code comment-api/src/test/java/com/rag/comment/comment/api/CommentApiContractTest.java:103-236}</li>
 *         <li>{@code comment-api/src/test/java/com/rag/comment/comment/api/CommentApiBehaviorTest.java:103-407}</li>
 *         <li>{@code comment-api/…/api/service/GetCommentService.java:76-139}（list 的四处差异）</li>
 *         <li>{@code comment-api/…/api/service/DeleteCommentService.java:38-40}（success 是字面量 true）</li>
 *         <li>{@code comment-api/…/api/service/GetCommentService.java:129-134}（owner_id 恒 0）</li>
 *         <li>{@code comment-api/…/api/service/GetCommentService.java:167-171}（children 恒 []）</li>
 *         <li>{@code comment-api/…/api/advice/CommentGlobalExceptionHandler.java:66-294}（8 类异常的形状）</li>
 *         <li>{@code comment-api/…/api/advice/CommentGlobalResponseAdvice.java:17-49}（成功信封）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>（本类<b>不冒充</b>该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 MockMvc（走真 Tomcat）；</li>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>，不静默跳过）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CommentServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class CommentHttpWireContractRealTomcatTest {

    /** 7 条端点逐个列出（B 出处：B 侧 {@code CommentController.java:96,104,112,126,133,141,149}）。 */
    private static final String[] ENDPOINTS = {
            "/comment/v1/comment/create",
            "/comment/v1/comment/delete",
            "/comment/v1/comment/like",
            "/comment/v1/comment/list",
            "/comment/v1/comment/manage",
            "/comment/v1/comment/report",
            "/comment/v1/subject/state"};

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(9_100_000_000_000L);

    /** {@code ErrorServerCommon} = 5001（B 侧 {@code errmsg.go:41}）。 */
    private static final int ERROR_SERVER_COMMON = 5001;

    /** {@code ErrorTokenRuntime} = 1006（B 侧 {@code errmsg.go:56}）。 */
    private static final int ERROR_TOKEN_RUNTIME = 1006;

    /** {@code ErrorSubjectNotExist} = 1012（B 侧 {@code errmsg.go:58}）。 */
    private static final int ERROR_SUBJECT_NOT_EXIST = 1012;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        // ⚠️ Redis：Lettuce 是**懒连接** ⇒ 指向一个必然拒连的本机端口即可
        //    （B 侧原测试同样这么做：{@code spring.data.redis.host=127.0.0.1} + {@code port=1}）。
        //    ⇒ 缓存读失败会「打点后回源 DB」（B 侧 {@code comment_subject_cache.go:31-35}），
        //    **不是**致命 ⇒ 不影响本类的任何断言。
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Environment env;

    /**
     * 🔴 <b>下游领域服务被替换</b>（对应 B 侧替换 {@code CommentRpcClient}）。
     *
     * <p>⚠️ 被替换的<b>只有</b>下游；HTTP 链路上的每一环都是真件。
     */
    @MockitoBean
    private CommentDomainService domainService;

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 取验签密钥 —— <b>从 Spring 的 {@link Environment} 读</b>，不写死常量。
     *
     * <p>⚠️ <b>为什么</b>：密钥必须与 {@code src/test/resources/application-test.properties} 里的
     * {@code comment-api.user-auth.access-secret} <b>完全一致</b>，否则验签必失败（401），
     * 而且失败原因会被误读成「鉴权层坏了」。
     * ⇒ ⇒ 从环境里读 ⇒ 密钥改一次，本类自动跟随 ⇒ 不会漂。
     */
    private String secret() {
        String s = env.getProperty("comment-api.user-auth.access-secret");
        if (s == null || s.isBlank()) {
            s = env.getProperty("USER_JWT_SECRET");
        }
        assertThat(s)
                .as("测试上下文里必须能读到 comment-api.user-auth.access-secret"
                        + "（application-test.properties 提供的）")
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

    /** 正常 token：数字 {@code userId} claim（走共享层的 {@code JwtUtil}，HS256）。 */
    private String token(long uid) {
        return JwtUtil.getToken(secret(), System.currentTimeMillis() / 1000L, 3600L, uid);
    }

    private String bearer(long uid) {
        return "Bearer " + token(uid);
    }

    /** 不带 {@code userId} claim 的 token（验签通过，但身份缺失）。 */
    private String tokenWithoutUserIdClaim() {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("scope", "user")
                .signWith(Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(secret())))
                .compact();
    }

    /** {@code userId} claim 是<b>任意原始对象</b>的 token（B 出处：B 侧 {@code CommentApiBehaviorTest#tokenWithRawClaim}）。 */
    private String tokenWithRawClaim(Object rawUserId) {
        return Jwts.builder()
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .claim("userId", rawUserId)
                .signWith(Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(secret())))
                .compact();
    }

    /** 用<b>别的</b>密钥签的 token（⇒ 验签失败 ⇒ 401 空 body）。 */
    private String tokenSignedWithWrongSecret(long uid) {
        return JwtUtil.getToken("00000000-0000-0000-0000-000000000000",
                System.currentTimeMillis() / 1000L, 3600L, uid);
    }

    /** 合法 body（6 个必填字段齐全），供「鉴权通过后继续往下走」的用例复用。 */
    private static final String CREATE_BODY = """
            {"target_type":"article","target_id":"BV1","root_id":0,
             "parent_id":0,"content":"hi","meta":""}""";

    private static final String LIST_BODY = """
            {"target_type":"article","target_id":"BV1","sort_type":0,
             "root_id":0,"page":1,"page_size":20}""";

    private static final String DELETE_BODY = """
            {"comment_id":9,"target_type":"article","target_id":"BV1"}""";

    // ==================================================================
    // 1. 鉴权：401 + 空 body（B 出处：B 侧 {@code authhandler.go:106 unauthorized()}）
    // ==================================================================

    @Test
    @DisplayName("🔴 没有 Authorization 头 ⇒ HTTP 401 + **空 body**（不是 1006、不是 envelope）")
    void missingAuthorizationHeaderIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json(CREATE_BODY), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody())
                .as("B 侧 unauthorized() 只 WriteHeader(401)、**不写任何字节** ⇒ body 必须为空")
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
        verify(domainService, never()).createComment(any());
    }

    @Test
    @DisplayName("🔴 签名错的 token ⇒ HTTP 401 + 空 body（验签失败不区分原因）")
    void wrongSignatureTokenIs401WithEmptyBody() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json(CREATE_BODY, "Authorization", "Bearer " + tokenSignedWithWrongSecret(42L)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
        verify(domainService, never()).createComment(any());
    }

    @ParameterizedTest(name = "{0} 没有 token ⇒ 401")
    @ValueSource(strings = {
            "/comment/v1/comment/create",
            "/comment/v1/comment/delete",
            "/comment/v1/comment/like",
            "/comment/v1/comment/list",
            "/comment/v1/comment/manage",
            "/comment/v1/comment/report",
            "/comment/v1/subject/state"})
    @DisplayName("🔴 7 条端点**全部**要求 JWT（B 侧只有一段 AddRoutes，public 路径集是空集）")
    void everyEndpointRequiresJwt(String path) {
        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.POST, json("{}"), String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧的 public 路径集是**空集** ⇒ 7/7 端点全要 JWT（%s）", path)
                .isEqualTo(401);
        assertThat(resp.getBody()).satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    // ==================================================================
    // 2. 路由表：404 / 405 是**框架形状**，不是 envelope
    // ==================================================================

    @Test
    @DisplayName("🔴 未注册路径 ⇒ 404 + text/plain + nosniff（**不是** envelope、**不是** 401）")
    void unknownPathIsNotFound() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/nope", HttpMethod.POST,
                json("{}"), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getHeaders().getFirst("X-Content-Type-Options"))
                .as("B 侧 404 带 nosniff（Go 的 net/http.Error）")
                .isEqualTo("nosniff");
        // ⚠️ **逐字节比**（去空格后）：真 Tomcat 会把 Content-Type 归一化
        //    ⇒ 线上是 `text/plain;charset=utf-8`（分号后无空格）。
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString().replace(" ", ""))
                .isEqualTo("text/plain;charset=utf-8");
        assertThat(resp.getBody())
                .as("B 侧 net/http.Error 的 body = \"404 page not found\\n\"（带一个换行）")
                .isEqualTo("404 page not found\n");
    }

    @Test
    @DisplayName("🔴 /comment/v1/comment/get **不存在**（B 侧的 path 是 /comment/list，不是按方法名推的）")
    void commentGetPathIsNotRegistered() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/get", HttpMethod.POST,
                json("{}", "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("🔴 GET 打 POST-only 端点 ⇒ 405 + Allow 头（7 条路由**全**是 POST）")
    void getOnPostOnlyEndpointIs405() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.GET,
                json("{}", "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧 7 条 @PostMapping ⇒ GET 必须 405")
                .isEqualTo(405);
        assertThat(resp.getHeaders().getFirst("Allow"))
                .as("405 必须带 Allow 头（Go 的 patrouter 行为）")
                .contains("POST");
    }

    // ==================================================================
    // 3. 解析 / 校验失败 ⇒ HTTP 200 + {code:5001}（**不是** 400、**不是** 2001）
    // ==================================================================

    @Test
    @DisplayName("🔴 必填字段缺席 ⇒ HTTP 200 + {code:5001, msg:\"系统内部错误\"}（不是 400）")
    void missingRequiredFieldYields5001() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json("{\"target_type\":\"article\"}", "Authorization", bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("B 侧的必填校验失败发生在**解析层** ⇒ 兜底码 5001（不是业务层的 2001）")
                .isEqualTo(ERROR_SERVER_COMMON);
        assertThat(body.get("msg").asText()).isEqualTo("系统内部错误");
        assertThat(body.get("data").isNull())
                .as("data 键必须存在且为 null（B 侧 Response 三个字段都没有 omitempty）")
                .isTrue();
        verify(domainService, never()).createComment(any());
    }

    @Test
    @DisplayName("🔴 非法 JSON ⇒ HTTP 200 + {code:5001}（不是 400）")
    void malformedJsonYields5001() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json("{not-json", "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value())
                .as("B 侧 comment-api 注册了 httpx.SetErrorHandler ⇒ 解析失败也是 200 + envelope")
                .isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt()).isEqualTo(ERROR_SERVER_COMMON);
        verify(domainService, never()).createComment(any());
    }

    @Test
    @DisplayName("🔴 JSON 字符串进整数字段 ⇒ 200 + {code:5001}（Go 侧 typeKind != valueKind ⇒ 拒绝）")
    void stringIntoInt64FieldYields5001() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json("""
                        {"target_type":"article","target_id":"BV1","root_id":"123",
                         "parent_id":0,"content":"hi","meta":""}""",
                        "Authorization", bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("B 出处：B 侧 CommentGoStyleCoercion —— Integer ← String = Fail")
                .isEqualTo(ERROR_SERVER_COMMON);
    }

    // ==================================================================
    // 4. 身份：claim 缺失 ⇒ 200 + {code:1006}（**不是** 401）
    // ==================================================================

    @Test
    @DisplayName("🔴 token 无 userId claim ⇒ HTTP 200 + {code:1006, msg:\"TOKEN已过期\"}（绝不 401）")
    void missingUserIdClaimYields1006() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json(CREATE_BODY, "Authorization", "Bearer " + tokenWithoutUserIdClaim()),
                String.class);

        assertThat(resp.getStatusCode().value())
                .as("验签成功但 claim 取不到 uid ⇒ **业务层**判 1006 ⇒ HTTP 仍 200")
                .isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(ERROR_TOKEN_RUNTIME);
        assertThat(body.get("msg").asText()).isEqualTo("TOKEN已过期");
        verify(domainService, never()).createComment(any());
    }

    @Test
    @DisplayName("🔴 浮点 userId claim（1.5）⇒ 200 + {code:1006}（不得静默截断成 1）")
    void floatUserIdClaimYields1006() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/delete", HttpMethod.POST,
                json(DELETE_BODY, "Authorization", "Bearer " + tokenWithRawClaim(1.5d)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("Go 的 json.Number.Int64() 对 1.5 失败 ⇒ 1006；截断成 1 就是越权风险")
                .isEqualTo(ERROR_TOKEN_RUNTIME);
    }

    @Test
    @DisplayName("🔴 越界整数 userId claim ⇒ 200 + {code:1006}（不得回绕成负数）")
    void outOfRangeUserIdClaimYields1006() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/delete", HttpMethod.POST,
                json(DELETE_BODY, "Authorization",
                        "Bearer " + tokenWithRawClaim(new BigInteger("9223372036854775808"))),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("9223372036854775808 > Long.MAX_VALUE ⇒ Go 的 ParseInt 溢出 ⇒ 1006")
                .isEqualTo(ERROR_TOKEN_RUNTIME);
    }

    @Test
    @DisplayName("🔴 字符串 userId claim 走 ParseInt：合法数字**放行**（comment 域与 like 域相反）")
    void stringUserIdClaimFollowsGoParseInt() {
        long uid = UID_SEQ.incrementAndGet();
        when(domainService.deleteComment(any()))
                .thenReturn(com.rag.proto.comment.DeleteCommentResp.newBuilder()
                        .setSubjectTotalCount(1L).build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/delete", HttpMethod.POST,
                json(DELETE_BODY, "Authorization", "Bearer " + tokenWithRawClaim(String.valueOf(uid))),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt())
                .as("B 出处：B 侧 comment 域的 extractUserID 对 string claim 走 strconv.ParseInt"
                        + " ⇒ \"31337\" 解析成功 ⇒ **不**是 1006")
                .isNotEqualTo(ERROR_TOKEN_RUNTIME);

        ArgumentCaptor<com.rag.proto.comment.DeleteCommentReq> captor =
                ArgumentCaptor.forClass(com.rag.proto.comment.DeleteCommentReq.class);
        verify(domainService).deleteComment(captor.capture());
        assertThat(captor.getValue().getUserId())
                .as("字符串 claim 必须是精确解析，不能截断")
                .isEqualTo(uid);
    }

    @Test
    @DisplayName("🔴 非法字符串 userId claim ⇒ 200 + {code:1006}（ParseInt 失败）")
    void nonNumericStringUserIdClaimYields1006() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/delete", HttpMethod.POST,
                json(DELETE_BODY, "Authorization", "Bearer " + tokenWithRawClaim("not-a-number")),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt()).isEqualTo(ERROR_TOKEN_RUNTIME);
    }

    // ==================================================================
    // 5. 🔴 鉴权不对称：list 端点**不做**身份绑定
    // ==================================================================

    @Test
    @DisplayName("🔴 /comment/v1/comment/list **不做**身份绑定 ⇒ 缺 userId claim 时**不**返回 1006")
    void listEndpointDoesNotBindIdentity() {
        // B 出处：B 侧 GetCommentService 全篇**没有** support.userId(request) 调用
        //         （B 侧 {@code GetCommentService.java:76-139}）⇒ 该端点不取 uid。
        when(domainService.getComment(any()))
                .thenReturn(com.rag.proto.comment.GetCommentResp.getDefaultInstance());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json(LIST_BODY, "Authorization", "Bearer " + tokenWithoutUserIdClaim()),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("其余 6 个端点缺 claim 会返回 1006；list 端点**不**取 uid ⇒ 不是 1006")
                .isNotEqualTo(ERROR_TOKEN_RUNTIME);
        assertThat(body.get("code").asInt()).isEqualTo(200);
    }

    // ==================================================================
    // 6. 成功信封与出参映射
    // ==================================================================

    @Test
    @DisplayName("🔴 成功出口 = {code:200, msg:\"OK\", data:{...}}（Content-Type 是 application/json）")
    void successShapeIsEnvelope() {
        when(domainService.createComment(any()))
                .thenReturn(com.rag.proto.comment.CreateCommentResp.newBuilder()
                        .setId(42L)
                        .setCreatedAt("2026-10-23T00:00:00Z")
                        .setSubjectCount(7L)
                        .build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json(CREATE_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("application/json");

        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(200);
        assertThat(body.get("msg").asText()).isEqualTo("OK");
        assertThat(body.get("data").get("id").asLong()).isEqualTo(42L);
        assertThat(body.get("data").get("created_at").asText()).isEqualTo("2026-10-23T00:00:00Z");
        assertThat(body.get("data").get("subject_count").asLong())
                .as("B 出处：B 侧 CreateCommentService 原样映射 rpcResp.SubjectCount"
                        + "（**恒 0** 是 **rpc 层**的常量，见 B 侧 create_comment_logic.go:139；"
                        + "api 层只做透传）")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("🔴 subject.owner_id **恒为 0** —— 即使下游返回 999（B 侧丢弃了 proto 的 owner_id）")
    void subjectOwnerIdIsAlwaysZero() {
        when(domainService.getComment(any()))
                .thenReturn(com.rag.proto.comment.GetCommentResp.newBuilder()
                        .setSubject(com.rag.proto.comment.SubjectInfo.newBuilder()
                                .setTargetType("article")
                                .setTargetId("BV1")
                                .setTotalCount(11L)
                                .setRootCount(5L)
                                .setOwnerId(999L)          // ← B 侧这个值会被丢弃
                                .build())
                        .build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json(LIST_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(200);
        assertThat(body.get("data").get("subject").get("total_count").asLong()).isEqualTo(11L);
        assertThat(body.get("data").get("subject").get("owner_id").asLong())
                .as("B 出处：B 侧 GetCommentService —— Go 写的是字面量 OwnerId: 0")
                .isZero();
    }

    @Test
    @DisplayName("🔴 空结果时 comment 恒为 []，且每层 children 恒为 []（绝不是 null）")
    void emptyCollectionsAreNeverNull() {
        when(domainService.getComment(any()))
                .thenReturn(com.rag.proto.comment.GetCommentResp.newBuilder()
                        .addComment(com.rag.proto.comment.CommentItem.newBuilder()
                                .setId(1L).setContent("root").build())
                        .build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json(LIST_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = parse(resp.getBody()).get("data");
        assertThat(data.get("comment").isArray()).isTrue();
        assertThat(data.get("comment").size()).isEqualTo(1);
        assertThat(data.get("comment").get(0).get("children").isArray())
                .as("B 出处：B 侧 GetCommentService#toCommentItem —— 每层都先建空切片")
                .isTrue();
        assertThat(data.get("comment").get(0).get("children").size()).isZero();
    }

    @Test
    @DisplayName("🔴 下游没返回 subject 时输出全零对象，不是 null、也不被省略")
    void absentSubjectIsZeroObjectNotNull() {
        when(domainService.getComment(any()))
                .thenReturn(com.rag.proto.comment.GetCommentResp.getDefaultInstance());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json(LIST_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = parse(resp.getBody()).get("data");
        assertThat(data.get("subject").isObject())
                .as("proto3 的 message 字段无 null 形态 ⇒ 没返回时是一个全零对象")
                .isTrue();
        assertThat(data.get("subject").get("owner_id").asLong()).isZero();
        assertThat(data.get("comment").size()).isZero();
    }

    @Test
    @DisplayName("🔴 DeleteComment 恒返回 success=true，即使下游说 false（B 侧是**字面量**）")
    void deleteSuccessIsAlwaysTrue() {
        when(domainService.deleteComment(any()))
                .thenReturn(com.rag.proto.comment.DeleteCommentResp.newBuilder()
                        .setSuccess(false)          // ← B 侧不读这个值
                        .setSubjectTotalCount(3L)
                        .build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/delete", HttpMethod.POST,
                json(DELETE_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = parse(resp.getBody()).get("data");
        assertThat(data.get("success").asBoolean())
                .as("B 出处：B 侧 DeleteCommentService —— resp.setSuccess(true) 是**字面量**")
                .isTrue();
        assertThat(data.get("subject_total_count").asLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("🔴 user_id 取自 token claim 并下发；body 里的同名字段**不能**覆盖操作人")
    void userIdComesFromTokenNotBody() {
        long uid = UID_SEQ.incrementAndGet();
        when(domainService.deleteComment(any()))
                .thenReturn(com.rag.proto.comment.DeleteCommentResp.newBuilder()
                        .setSubjectTotalCount(1L).build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/delete", HttpMethod.POST,
                json("""
                        {"comment_id":9,"target_type":"article","target_id":"BV1","user_id":666}""",
                        "Authorization", bearer(uid)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<com.rag.proto.comment.DeleteCommentReq> captor =
                ArgumentCaptor.forClass(com.rag.proto.comment.DeleteCommentReq.class);
        verify(domainService).deleteComment(captor.capture());
        assertThat(captor.getValue().getUserId())
                .as("操作人必须是 token 里的身份，不能被 body 里的同名字段覆盖")
                .isEqualTo(uid);
    }

    @Test
    @DisplayName("🔴 action_type=99 原样透传给下游（用 forNumber 会被静默改写成 0）")
    void unknownEnumValueIsPassedThrough() {
        when(domainService.manageComment(any()))
                .thenReturn(com.rag.proto.comment.ManageCommentResp.newBuilder()
                        .setSuccess(true).build());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/manage", HttpMethod.POST,
                json("""
                        {"comment_id":9,"target_type":"article","target_id":"BV1","action_type":99}""",
                        "Authorization", bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(resp.getBody()).get("code").asInt()).isEqualTo(200);

        ArgumentCaptor<com.rag.proto.comment.ManageCommentReq> captor =
                ArgumentCaptor.forClass(com.rag.proto.comment.ManageCommentReq.class);
        verify(domainService).manageComment(captor.capture());
        assertThat(captor.getValue().getActionTypeValue())
                .as("未知枚举值必须原样透传，不能退化成 0(UNKNOWN)")
                .isEqualTo(99);
    }

    @Test
    @DisplayName("🔴 list 的五处入参兜底：page=0→1、pageSize=100→50、sortType=5→0、rootId=-1→0")
    void paginationFallbacksAreApplied() {
        when(domainService.getComment(any()))
                .thenReturn(com.rag.proto.comment.GetCommentResp.getDefaultInstance());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json("""
                        {"target_type":"article","target_id":"BV1","sort_type":5,
                         "root_id":-1,"page":0,"page_size":100}""",
                        "Authorization", bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<com.rag.proto.comment.GetCommentReq> captor =
                ArgumentCaptor.forClass(com.rag.proto.comment.GetCommentReq.class);
        verify(domainService).getComment(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.getPage()).as("page<=0 兜底成 1").isEqualTo(1L);
        assertThat(sent.getPageSize()).as("pageSize>50 截断到 50").isEqualTo(50L);
        assertThat(sent.getSortType()).as("sortType ∉ {0,1} 归 0").isZero();
        assertThat(sent.getRootId()).as("rootId<0 归 0").isZero();
    }

    @Test
    @DisplayName("🔴 校验只判空、**不回写** trim 结果（带空格的 target_type 原样下发）")
    void targetTypeIsNotTrimmedBeforeRpc() {
        when(domainService.getComment(any()))
                .thenReturn(com.rag.proto.comment.GetCommentResp.getDefaultInstance());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json("""
                        {"target_type":" article ","target_id":"BV1","sort_type":1,
                         "root_id":0,"page":2,"page_size":10}""",
                        "Authorization", bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<com.rag.proto.comment.GetCommentReq> captor =
                ArgumentCaptor.forClass(com.rag.proto.comment.GetCommentReq.class);
        verify(domainService).getComment(captor.capture());
        assertThat(captor.getValue().getTargetType())
                .as("B 侧只用 TrimSpace 判空，没有 trim 后的赋值 ⇒ 原值下发")
                .isEqualTo(" article ");
    }

    // ==================================================================
    // 7. 🔴 业务码跨层往返：两个端点的错误契约**不一样**
    // ==================================================================

    @Test
    @DisplayName("🔴 create 的下游业务码 ⇒ 原值透传（{code:1012, msg:\"评论区不存在\"}）")
    void createPassesBusinessCodeVerbatim() {
        when(domainService.createComment(any()))
                .thenThrow(CommentExceptions.subjectNotExist());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/create", HttpMethod.POST,
                json(CREATE_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("B 出处：B 侧 createcommentlogic 的 status.FromError 分支 ⇒ 原值透传")
                .isEqualTo(ERROR_SUBJECT_NOT_EXIST);
        assertThat(body.get("msg").asText())
                .as("msg 必须是下游发来的**原文**，不能被码表文案顶替")
                .isEqualTo("评论区不存在");
    }

    @Test
    @DisplayName("🔴 list 的下游失败 ⇒ 兜底成 {code:5001, msg:\"系统内部错误\"}（**与 create 不同**）")
    void listDownstreamFailureFallsBackTo5001() {
        // ⚠️ **同一个**下游业务码（1012）—— 与上一条形成对照。
        //    B 出处：B 侧 getcommentlogic 是**裸** return nil, err（没有 code/msg 转换）
        //          ⇒ 落进 Go 的兜底分支 ⇒ 客户端拿到 5001。
        when(domainService.getComment(any()))
                .thenThrow(CommentExceptions.subjectNotExist());

        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json(LIST_BODY, "Authorization", bearer(UID_SEQ.incrementAndGet())), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("照抄「统一模板」把这里改成 1012 就会破坏 Go 行为")
                .isEqualTo(ERROR_SERVER_COMMON);
        assertThat(body.get("msg").asText()).isEqualTo("系统内部错误");
    }

    @Test
    @DisplayName("🔴 list 的入参校验失败 ⇒ {code:5001}（不是 InvalidArgument=3、也拿不到报错文案）")
    void blankTargetTypeFallsBackTo5001() {
        ResponseEntity<String> resp = rest.exchange("/comment/v1/comment/list", HttpMethod.POST,
                json("""
                        {"target_type":"   ","target_id":"BV1","sort_type":0,
                         "root_id":0,"page":1,"page_size":20}""",
                        "Authorization", bearer(UID_SEQ.incrementAndGet())),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("B 出处：B 侧 GetCommentService 抛的是裸 gRPC status error"
                        + " ⇒ 不是 *CodeError ⇒ 落进兜底 ⇒ 5001")
                .isEqualTo(ERROR_SERVER_COMMON);
        assertThat(body.get("msg").asText()).isEqualTo("系统内部错误");
    }
}
