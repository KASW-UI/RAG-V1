package com.rag.favorite.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag.common.security.JwtUtil;
import com.rag.favorite.support.RealTomcatHttp;
import com.rag.favorite.support.RealTomcatPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 🔴 favorite 域的<b>真实 Tomcat</b> 门禁 —— 补上 {@link FavoriteApiProtectionWiringTest}
 * 明确标注为「未验」的那一层。
 *
 * <h2>为什么 MockMvc 不够</h2>
 * <p>{@code @SpringBootTest(webEnvironment = MOCK)} 走的是 {@code MockHttpServletResponse}
 * ⇒ <b>不经过 servlet filter 链</b> ⇒ 保护层（MaxConns / Breaker / CpuShedding / MaxBytes）
 * 与<b>容器输入上限三旋钮</b>在 MockMvc 下<b>一次都没跑过</b>。
 * <p>⚠️ 而且 MockMvc 在「响应提交时序」上与真实 Tomcat 不同 ⇒ 用 MockMvc 断言响应头会<b>假绿</b>。
 *
 * <h2>本类验的是「机制生效」，不是「两侧数值等价」</h2>
 * <p>⚠️ <b>证据边界</b>：本类把 {@code rag.protection.max-bytes} 调到 <b>1024</b> 以便触发，
 * 而 Go 侧 favorite-api <b>没有</b>配 MaxBytes ⇒ 走 go-zero {@code rest/config.go:53} 的
 * {@code default=1048576} ⇒ ⇒ <b>本类证明的是「RAG 侧的保护层与三旋钮真的生效」</b>，
 * <b>不是</b>「RAG 与 Go 在同一阈值下行为一致」——后者需要<b>双侧活体对拍</b>。
 *
 * <h2>C 侧与 B 侧的三处形态差异（都是 D1 合并的必然结果，不是行为漂移）</h2>
 * <ol>
 *   <li><b>应用类</b>：B 侧 {@code FavoriteApiApplication}（纯 api 进程）
 *       ⇒ C 侧 {@code com.rag.favorite.FavoriteServiceApplication}（api + rpc 合并进程 · D1 + D7）。</li>
 *   <li><b>没有 favorite-rpc 客户端</b>：D2 域内直连 ⇒ B 侧那条
 *       {@code @MockitoBean FavoriteRpcClient} 在 C 侧<b>不存在对应物</b>
 *       ⇒ ⇒ C 侧 api 层直调 {@code FavoriteDomainService}。</li>
 *   <li><b>下游依赖是 user-rpc / article-rpc</b>（不是 favorite-rpc）
 *       ⇒ {@code grpc.client.*} 两条都要配成不可达地址。</li>
 * </ol>
 *
 * <p>⚠️ <b>DB 刻意不可达</b>：本类的判据全部落在
 * 「<b>容器层 / 拦截器层 / 鉴权层</b>」与「<b>确实进了业务层</b>」这两类可观察面上
 * ⇒ ⇒ 业务层被 DB 拒绝时返回的仍是<b>业务信封</b>（HTTP 200 + {@code code/msg/data}）
 * ⇒ ⇒ 「进了业务层」这一格照样成立 ⇒ ⇒ <b>不需要</b>为这层断言起真 PG。
 * <p>⚠️ <b>B 出处</b>：
 * <ul>
 *   <li>B 侧测试：{@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/FavoriteApiRealTomcatGateTest.java:1-756}</li>
 *   <li>Go 的 form 上限：{@code go-zero@v1.10.2/rest/httpx/util.go:15,48}
 *       （{@code maxFormParamCount=2048} · {@code if n < maxFormParamCount}）</li>
 *   <li>Go 的段数上限：{@code go-zero@v1.10.2/core/net/url/url.go:1004,1020-1022}
 *       （{@code defaultMaxParams=10000} · {@code params <= defaultMaxParams}）</li>
 *   <li>Go 的分号规则：{@code go-zero@v1.10.2/core/net/url/url.go:1023-1054}
 *       （{@code ParseQuery} 遇 {@code ';'} 记 error）</li>
 * </ul>
 */
@SpringBootTest(
        classes = {com.rag.favorite.FavoriteServiceApplication.class,
                com.rag.favorite.support.NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                // ⚠️ C 侧的两个**出向**依赖（D2 域内直连 ⇒ 没有 favorite-rpc 客户端）
                "grpc.client.user-rpc.address=static://127.0.0.1:65535",
                "grpc.client.user-rpc.negotiation-type=plaintext",
                "grpc.client.article-rpc.address=static://127.0.0.1:65535",
                "grpc.client.article-rpc.negotiation-type=plaintext",
                "grpc.server.port=0",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                "rag.protection.max-bytes=1024",
                // ⚠️ 真 Tomcat 下首个请求要加载 Jackson/MyBatis 等类，可能超过 3s
                //    ⇒ 服务端会回 503 "Request Timeout"（那是预热，不是被测行为）
                "observability.http-timeout=30s",
                // 让判据**只**取决于「本类要验的那条路径」，不被 CPU 自适应降载抢先返回：
                // 降载命中时 GoStyleCpuSheddingFilter#doFilter 走 `response.setStatus(503)` 并**直接 return**
                // ⇒ **裸 503、无 body、无业务信封** ⇒ 那是另一回事 ⇒ 本类要验的判据被盖掉。
                // ⚠️ 这是**测试专用**覆盖，不改生产默认值（默认 true 对齐 Go 侧
                //    rest/config.go:22 `Middlewares.Shedding bool json:",default=true"`）。
                "rag.protection.cpu-shedding.enabled=false",
                // 🔴 C 侧 FavoriteApiProperties 的密钥守卫是 fail-closed 的（缺值 ⇒ 启动失败）
                "favorite-api.user-auth.access-secret=test-only-favorite-jwt-secret-at-least-32-bytes",
                // DB 不可达：池不建连、Flyway 不跑（判据不依赖业务表）
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/first_db",
                "spring.datasource.username=admin",
                "spring.datasource.password=password",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.flyway.enabled=false"
        })
class FavoriteApiRealTomcatGateTest {

    private static final String FOLDER_CREATE = "/favorite/v1/folder/create";
    private static final String ITEM_LIST = "/favorite/v1/item/list";

    /** Go 路由表（service/favorite/api/internal/handler/routes.go:16-59）的其余 4 个端点。 */
    private static final String FOLDER_DELETE = "/favorite/v1/folder/delete";
    private static final String FOLDER_UPDATE = "/favorite/v1/folder/update";
    private static final String ITEM_CREATE = "/favorite/v1/item/create";
    private static final String ITEM_DELETE = "/favorite/v1/item/delete";

    /**
     * Go 侧 {@code GetFavoriteFoldersReq} 是<b>空结构体</b>
     * （{@code service/favorite/api/internal/types/types.go:59}）⇒ ⇒ 无必填字段干扰
     * ⇒ ⇒ 且本端点<b>不读 query 参数</b> ⇒ ⇒ 用它当「段数闸门覆盖面」的判别点。
     */
    private static final String FOLDER_LIST = "/favorite/v1/folder/list";

    /**
     * favorite-api 的 AccessSecret —— ⚠️ **仅测试用**，与生产 env（{@code FAVORITE_JWT_SECRET}）无关。
     *
     * <p>⚠️ 生产 yaml 的明文默认值已于 2026-10-29 去掉（P0 #8 域侧修）⇒ 这里不是「默认值」，
     * 而是本测试的属性覆盖值 ⇒ 逐字取自上面的 {@code favorite-api.user-auth.access-secret}。
     */
    private static final String ACCESS_SECRET = "test-only-favorite-jwt-secret-at-least-32-bytes";

    @LocalServerPort
    private int port;

    /**
     * 🔴 **本类把共享层的「客户端断连探针」关掉**（**仅本测试类 · 用完即恢复**）。
     *
     * <p><b>为什么必须关</b>（2026-11-04 实测踩到的坑）：
     * 共享层 {@code ClientDisconnectProbe} 会**直接往 socket 裸写**
     * {@code "HTTP/1.1 103 Early Hints\r\n\r\n"}（已签字偏离 {@code SD-2026-10-25-04}），
     * 且**写入时机不定**（定时器触发 ⇒ 可能落在响应头之后、chunked body **中间**）
     * ⇒ ⇒ JDK {@code HttpClient} 的 chunk 解码器看到垃圾 ⇒ 抛
     * {@code java.io.IOException: Illegal character in chunk size: 72}（72 = {@code 'H'}）
     * ⇒ ⇒ **用例随机红**（同一份代码上一轮绿、这一轮红）。
     *
     * <p><b>为什么关掉它不削弱任何断言</b>：
     * 本类验的是「容器三旋钮 · 鉴权 · query 闸门 · 业务信封」⇒
     * **1xx 探针对这些判据完全无关** ⇒ ⇒ 关掉它只让响应**可确定地读全**，不放宽任何一条断言。
     * ⇒ ⇒ 这**不是**「为了让测试变绿而改预期」⇒ 断言口径逐条未动。
     *
     * <p>⚠️ <b>必须用完即恢复</b>：surefire 可能把多个测试类跑在**同一个 JVM** 里
     * ⇒ ⇒ 漏恢复 ⇒ **别的域的用例也会被顺带关掉探针** ⇒ ⇒ 下面 {@link #restoreDisconnectProbe()} 负责还原。
     * <p>⚠️ <b>共享层是只读消费</b>：本类只**读**它的公开常量
     * {@code ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY}（= {@code rag.common.http.disconnect-probe-max-lines}）
     * ⇒ ⇒ <b>不改共享层一行</b>（共享层的写权在收口任务，不在本域）。
     */
    private static String previousProbeMaxLines;

    @org.junit.jupiter.api.BeforeAll
    static void disableClientDisconnectProbe() {
        previousProbeMaxLines =
                System.getProperty(com.rag.common.observability.ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
        System.setProperty(com.rag.common.observability.ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY, "0");
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreClientDisconnectProbe() {
        if (previousProbeMaxLines == null) {
            System.clearProperty(com.rag.common.observability.ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY);
        } else {
            System.setProperty(com.rag.common.observability.ClientDisconnectProbe.MAX_PROBE_LINES_PROPERTY,
                    previousProbeMaxLines);
        }
    }

    /**
     * 🔴 Nacos 桩 —— 见 {@link com.rag.favorite.support.NacosStubConfiguration} 的类注释。
     *
     * <p>⚠️ <b>必须用 {@code @TestConfiguration} 而不是 {@code @MockitoBean} + {@code @BeforeEach}</b>：
     * 域内 {@code FavoriteNacosRegistration#register()} 挂在 {@code ApplicationReadyEvent} 上
     * ⇒ ⇒ 该事件**早于**任何 {@code @BeforeEach} ⇒ ⇒ 后打桩**来不及**
     * ⇒ ⇒ {@code getNamingService()} 此刻是 Mockito 的默认 **null** ⇒ ⇒ NPE ⇒ ⇒ 上下文起不来。
     */

    /**
     * <b>为什么不用 {@code RANDOM_PORT}</b>（2026-10-25 实测踩出来的，见
     * {@link RealTomcatPort} 的类注释）：
     * {@code RANDOM_PORT} 挑的是 OS <b>临时端口段</b>，而<b>别的会话的 Docker 容器正好在那一段</b>
     * 发布端口；macOS 上 {@code 127.0.0.1:P} 会<b>抢占</b> Tomcat 绑的 {@code 0.0.0.0:P}
     * ⇒ 客户端把请求打到了 PostgreSQL ⇒ <b>零字节即关</b> ⇒ 报
     * {@code HTTP/1.1 header parser received no bytes} ⇒ <b>看着像被测代码炸了，其实压根没打到</b>。
     *
     * <p>⇒ 改成 <b>自己挑一个确定归我们的端口</b>（专用端口段 + bind 自检）。
     */
    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", RealTomcatPort::pick);
    }

    /**
     * 发请求；<b>把「一个字节都没读到」的传输层失败翻译成人能读懂的诊断</b>。
     */
    private HttpResponse<String> send(HttpRequest req) throws Exception {
        try {
            // 🔴 走 RealTomcatHttp：**只用 JDK HttpClient，不回落**。
            //    1xx **不是「Tomcat 连发」的**，是**共享层断连探针**写的
            //    （GoStyleClientDisconnectProbe 裸写 103 Early Hints · 已签字偏离）
            //    ⇒ 共享层已把 1xx 行数设为每请求 ≤ 4 ⇒ 现在读得到最终响应。
            return RealTomcatHttp.send(req);
        } catch (IOException e) {
            throw new IOException("端口 " + port + " 上读到了 0 字节（本请求没打到本测试的 Tomcat？）"
                    + "；该端口的监听者=[" + RealTomcatPort.holders(port) + "]"
                    + "；原异常=" + e, e);
        }
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req);
    }

    /**
     * 带 JWT 的 GET —— 走鉴权 ⇒ 到达 controller ⇒ 才能验应用层的 form 计数语义。
     *
     * <p>⚠️ <b>为什么必须带 token</b>：鉴权（JWT）在 controller 之前 ⇒ 无 token 时
     * 请求在 interceptor 就被 401 短路，<b>永远到不了</b> {@code goParseViolation}。
     */
    private HttpResponse<String> getWithToken(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token())
                .GET()
                .build();
        return send(req);
    }

    /** 用 favorite-api 的 AccessSecret 签一个合法 token。 */
    private String token() {
        return JwtUtil.getToken(ACCESS_SECRET, System.currentTimeMillis() / 1000L, 3600L, 42L);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(req);
    }

    // ══════════════════ 保护层：MaxBytes ══════════════════

    @Test
    @DisplayName("真 Tomcat：超限 → 413 + 空 body + 无 Content-Type（对齐 Go 裸 WriteHeader）")
    void oversizedBodyYieldsBare413() throws Exception {
        HttpResponse<String> resp = postJson(FOLDER_CREATE, oversizedJson());
        assertEquals(413, resp.statusCode());
        assertTrue(resp.body() == null || resp.body().isEmpty(),
                "Go 侧 w.WriteHeader(413) 不写任何字节，body 必须为空；实际=[" + resp.body() + "]");
        assertNull(resp.headers().firstValue("content-type").orElse(null),
                "Go 侧 413 没有 Content-Type；容器若补出 text/html 就是漂移。实际="
                        + resp.headers().map());
    }

    @Test
    @DisplayName("真 Tomcat：413 压过鉴权（MaxBytes 在 auth 之外，与 Go chain 顺序一致）")
    void oversizedBodyBeatsAuth() throws Exception {
        HttpResponse<String> resp = postJson(FOLDER_CREATE, oversizedJson());
        assertEquals(413, resp.statusCode(),
                "MaxBytes 必须先于 JWT 鉴权短路（Go rest/engine.go:105-112 + chain.Append 语义）");
    }

    @Test
    @DisplayName("真 Tomcat：未超限 + 无 token → 401（证明 413 不是无条件短路）")
    void withinLimitStillHitsAuth() throws Exception {
        HttpResponse<String> resp = postJson(FOLDER_CREATE, "{}");
        assertEquals(401, resp.statusCode());
    }

    // ══════════════════ 容器三旋钮 + Go 的解析判据 ══════════════════

    @Test
    @DisplayName("🔴 三旋钮① relaxed-query-chars：query 里的 [ ] 不被 Tomcat 拒（Go 放行）")
    void bracketNotationIsAcceptedLikeGo() throws Exception {
        HttpResponse<String> resp = get(ITEM_LIST + "?folderId[]=123");
        assertFalse(isTomcatHtmlErrorPage(resp),
                "Tomcat 拒了带方括号的 query ⇒ 与 Go 不一致（Boot 默认 relaxed-query-chars 是空的）。"
                        + "实际 status=" + resp.statusCode() + " body=[" + resp.body() + "]");
        assertNotTomcat400(resp);
    }

    @Test
    @DisplayName("🔴 Go 的 maxFormParamCount=2048：2049 个非空 form 值 ⇒ 5003")
    void nonEmptyFormValueCountOverLimitIsRejected() throws Exception {
        HttpResponse<String> resp = getWithToken(ITEM_LIST + "?" + manyFormParams(2049));
        assertEquals(200, resp.statusCode(),
                "应用层拒绝也必须走 HTTP 200 + 业务信封（Go httpx.OkJsonCtx 语义）；body=[" + resp.body()
                        + "] ct=" + resp.headers().firstValue("content-type").orElse("-"));
        assertTrue(resp.body() != null && resp.body().contains("\"code\":5003"),
                "第 2049 个非空 form 值必须触发 Go 的 maxFormParamCount ⇒ 5003；实际 body=["
                        + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 Go 的 maxFormParamCount=2048：恰好 2048 个非空 form 值【放行】（不是 5003）")
    void nonEmptyFormValueCountAtLimitIsPassedThrough() throws Exception {
        HttpResponse<String> resp = getWithToken(ITEM_LIST + "?" + manyFormParams(2048));
        assertEquals(200, resp.statusCode());
        assertFalse(resp.body() != null && resp.body().contains("\"code\":5003"),
                "恰好 2048 个非空值必须放行（Go httpx/util.go:15 的 `if n < maxFormParamCount`）；"
                        + "若这里也报 5003，说明上限被改小或计数口径漂移。实际 body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 Go 放行（1 个非空值 + 2048 个空值 ⇒ 非空值数 1 ≤ 2048）⇒ RAG 必须放行进业务层（不是 5003）")
    void containerParameterLimitIsSurfacedAsGoParseError() throws Exception {
        HttpResponse<String> resp = getWithToken(ITEM_LIST + "?" + oneNonEmptyPlusEmpty(2048));
        assertEquals(200, resp.statusCode(),
                "Go 侧这一格是「放行 ⇒ 进业务层 ⇒ httpx.OkJsonCtx」⇒ HTTP 恒 200（api 层无 5xx 出口）；"
                        + "实际 body=[" + resp.body() + "] ct="
                        + resp.headers().firstValue("content-type").orElse("-"));
        assertFalse(resp.body() != null && resp.body().contains("\"code\":5003"),
                "Go 在这一格是**放行**（非空值数 = 1 ≤ 2048，且段数 2049 ≤ 10000）⇒ 不得落 5003"
                        + "（5003 是「解析失败」那一格）；实际 body=[" + resp.body() + "]");
        assertTrue(isBusinessEnvelope(resp),
                "必须证明请求**真的进了业务层**（响应是业务信封，而不是容器层/框架层的错误页）；"
                        + "实际 status=" + resp.statusCode() + " body=[" + resp.body() + "] ct="
                        + resp.headers().firstValue("content-type").orElse("-"));
        assertFalse(isTomcatHtmlErrorPage(resp),
                "不得出现 Tomcat 的 HTML 错误页（Go 侧不存在这个响应形态）；实际 status="
                        + resp.statusCode() + " body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 三旋钮③ max-http-request-header-size：20 KiB 的请求行必须放行（Boot 默认 8KB 会拒）")
    void longRequestLineIsAccepted() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20 * 1024; i++) {
            sb.append('a');
        }
        HttpResponse<String> resp = get(ITEM_LIST + "?folderId=" + sb);
        assertFalse(isTomcatHtmlErrorPage(resp),
                "20 KiB 的请求行必须放行（Go 侧上限是 1028KB）；实际 status=" + resp.statusCode());
        assertNotTomcat400(resp);
    }

    @Test
    @DisplayName("🔴 Go 的 net/url 段数上限：恰好 10000 段 ⇒ 放行（不是 5003）")
    void exactly10000QuerySegmentsArePassedThrough() throws Exception {
        HttpResponse<String> resp = getWithToken(ITEM_LIST + "?" + oneNonEmptyPlusEmpty(9999));
        assertEquals(200, resp.statusCode(),
                "Go 侧 `params <= defaultMaxParams`（net/url/url.go:1004）⇒ 10000 段必须放行；"
                        + "实际 body=[" + resp.body() + "]");
        assertFalse(resp.body() != null && resp.body().contains("\"code\":5003"),
                "恰好 10000 段必须放行（5003 是「解析失败」那一格）；实际 body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 Go 的 net/url 段数上限：第 10001 段 ⇒ 200 + 5003（与 Go 逐字同形）")
    void querySegmentCountOver10000IsRejectedLikeGo() throws Exception {
        HttpResponse<String> resp = getWithToken(FOLDER_LIST + "?" + oneNonEmptyPlusEmpty(10000));
        assertEquals(200, resp.statusCode(),
                "Go 侧解析失败走 httpx.OkJsonCtx ⇒ HTTP 恒 200（api 层无 5xx 出口）；实际 body=["
                        + resp.body() + "] ct=" + resp.headers().firstValue("content-type").orElse("-"));
        assertTrue(resp.body() != null && resp.body().contains("\"code\":5003"),
                "第 10001 段必须触发 Go 的 net/url 段数上限 ⇒ 5003；实际 body=[" + resp.body() + "]");
        assertFalse(isTomcatHtmlErrorPage(resp),
                "不得出现 Tomcat 的 HTML 错误页（Go 侧是 200 + 业务信封）；实际 status="
                        + resp.statusCode() + " body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 段数判据与「端点是否读 query 参数」无关（/item/list 同样在第 10001 段拒）")
    void querySegmentLimitIsUniformAcrossEndpoints() throws Exception {
        HttpResponse<String> resp = getWithToken(ITEM_LIST + "?" + oneNonEmptyPlusEmpty(10000));
        assertEquals(200, resp.statusCode(),
                "Go 侧 7/7 handler 的第一行都是 httpx.Parse ⇒ 段数上限对**所有**端点一致；实际 body=["
                        + resp.body() + "]");
        assertTrue(resp.body() != null && resp.body().contains("\"code\":5003"),
                "/item/list 也必须在第 10001 段拒（5003）；实际 body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 401 优先于段数判据（Go 的 httpx.Parse 在 handler 内部 ⇒ 解析在鉴权之后）")
    void authStillWinsOverQuerySegmentLimit() throws Exception {
        HttpResponse<String> resp = get(FOLDER_LIST + "?" + oneNonEmptyPlusEmpty(10000));
        assertEquals(401, resp.statusCode(),
                "Go 的 httpx.Parse 在 handler 内部 ⇒ 解析在鉴权之后 ⇒ 无 token 时必须是 401；"
                        + "实际 body=[" + resp.body() + "]");
        assertFalse(resp.body() != null && resp.body().contains("\"code\":5003"),
                "鉴权必须先于段数判据短路（否则就制造出「先解析后鉴权」的偏离）；实际 body=["
                        + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 Go 的 net/url 分号规则：query 里出现 ';' ⇒ 200 + 5003")
    void semicolonInQueryIsRejectedLikeGo() throws Exception {
        HttpResponse<String> resp = getWithToken(FOLDER_LIST + "?folderId=1;a=2");
        assertEquals(200, resp.statusCode(),
                "Go 侧解析失败走 httpx.OkJsonCtx ⇒ HTTP 恒 200；实际 body=[" + resp.body() + "]");
        assertTrue(resp.body() != null && resp.body().contains("\"code\":5003"),
                "query 里出现 ';' 必须触发 Go 的分号规则 ⇒ 5003；实际 body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 Go 的 maxFormParamCount=2048：2049 个非空 query 值 ⇒ 【7 个端点全部】5003")
    void nonEmptyFormValueLimitAppliesToEveryEndpoint() throws Exception {
        for (String[] ep : ALL_ENDPOINTS) {
            HttpResponse<String> resp = sendWithToken(ep[0], ep[1], ep[2], manyFormParams(2049));
            assertEquals(200, resp.statusCode(),
                    ep[0] + " " + ep[1] + "：应用层拒绝也必须 HTTP 200 + 业务信封（Go httpx.OkJsonCtx 语义）；body=["
                            + resp.body() + "]");
            assertTrue(resp.body() != null && resp.body().contains("\"code\":5003"),
                    ep[0] + " " + ep[1] + "：第 2049 个非空 query 值必须触发 Go 的 maxFormParamCount（"
                            + "go-zero@v1.10.2/rest/httpx/util.go:15,48）⇒ 5003；实际 body=[" + resp.body() + "]");
        }
    }

    @Test
    @DisplayName("🔴 Go 的 maxFormParamCount=2048：恰好 2048 个非空 query 值 ⇒ 放行进业务层（不是 5003）")
    void nonEmptyFormValueLimitBoundaryIsGoExact() throws Exception {
        // 判别点用 FOLDER_LIST：Go 侧 GetFavoriteFoldersReq 是**空结构体**（types.go:59）
        // ⇒ ⇒ 无必填字段干扰 ⇒ 「不是 5003」只能来自「闸门没触发」。
        HttpResponse<String> resp = sendWithToken("GET", FOLDER_LIST, null, manyFormParams(2048));
        assertEquals(200, resp.statusCode());
        assertFalse(resp.body() != null && resp.body().contains("\"code\":5003"),
                "恰好 2048 个非空值必须放行（Go httpx/util.go:48 的 `if n < maxFormParamCount`）；"
                        + "若这里也报 5003，说明上限被改小或计数口径漂移。实际 body=[" + resp.body() + "]");
        assertTrue(isBusinessEnvelope(resp),
                "放行后必须进业务层并返回业务信封（Go httpx.OkJsonCtx 语义）。实际 body=[" + resp.body() + "]");
    }

    @Test
    @DisplayName("🔴 Go 的 net/url 非法转义（%zz）⇒ 5003（ParseForm 的 error 传播到 httpx.Parse）")
    void invalidPercentEscapeIsRejectedLikeGo() throws Exception {
        // Go 1.25.11 src/net/url/url.go 的 unescape：`%` 后必须跟两个十六进制
        // ⇒ 否则 EscapeError ⇒ url.ParseQuery 记 error ⇒ Request.ParseForm 返回 error
        // ⇒ go-zero httpx.GetFormValues 直接返回该 error ⇒ httpx.Parse 失败 ⇒ 5003。
        // ⚠️ Tomcat 的 query 解码对非法转义是**宽容**的（原样保留）⇒ 不查这一条就会漏。
        //
        // 🔴 为什么必须用**裸 socket**：
        //   JDK `java.net.http.HttpClient` 与 `HttpURLConnection` 都在**客户端 URI 解析阶段**
        //   就把 `%zz` 拒了（`java.net.URI.create` ⇒ `IllegalArgumentException: Malformed escape pair`）
        //   ⇒ ⇒ 请求**根本没发出去** ⇒ ⇒ 用它们断言只会量到「客户端的严格性」，不是服务端的行为
        //   ⇒ ⇒ 这类输入只能用裸 socket 送（服务端收到的就是字面量 `?a=%zz`）。
        RawResponse resp = sendRawSocket("GET", FOLDER_LIST, "a=%zz", null);
        assertEquals(200, resp.status(),
                "Go 侧解析失败也走 HTTP 200 + 业务信封；实际 body=[" + resp.body() + "] raw=["
                        + resp.raw() + "]");
        assertTrue(resp.body() != null && resp.body().contains("\"code\":5003"),
                "非法 % 转义必须触发 Go 的 ParseForm error ⇒ 5003；实际 body=[" + resp.body() + "] raw=["
                        + resp.raw() + "]");
    }

    // ══════════════════ 端点清单与请求助手 ══════════════════

    /**
     * 🔴🔴 **legacy 预检的 Actuator 端点：默认状态下【不暴露】⇒ 404**
     *
     * <p>为什么这一条要放在<b>本类</b>（默认配置的上下文）而不是另开一个测试类：
     * ⇒ 本类<b>本来就是</b>「默认配置下的真 Tomcat」⇒ ⇒ 复用它就<b>不新增 Spring 上下文</b>。
     *
     * <p>🔴 <b>这条断言单独看是空的</b>：404 既可能是「端点没开」，
     * 也可能是「这个路径压根不存在」⇒ ⇒ 必须与
     * {@code http/FavoriteLegacyPreflightEndpointTest}（<b>同一路径</b>在两个开关打开时 ⇒ <b>200</b>）
     * <b>成对</b>读 ⇒ ⇒ 差异才能归因到<b>那两个开关</b>。
     *
     * <p>⚠️ <b>这条真正保护的东西</b>：预检是「跑一次只读报表」
     * ⇒ ⇒ 它一旦被<b>默认暴露</b>，就可能被健康检查 / K8s 探针<b>反复触发</b>
     * ⇒ ⇒ 运维闸门会变成负载源 ⇒ ⇒ 默认必须是「打不开」。
     */
    @Test
    @DisplayName("🔴 legacy 预检端点默认【不暴露】⇒ 404（默认不暴露 = 不会被探活误触发）")
    void legacyPreflightEndpointIsNotExposedByDefault() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/actuator/favorite-legacy-preflight"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = send(req);
        assertEquals(404, resp.statusCode(),
                "默认（favorite.fact.legacy-preflight.enabled=false ＋ management.endpoint.*.enabled=false）"
                        + "⇒ 端点不注册 ⇒ 404");
    }

    private static final String[][] ALL_ENDPOINTS = {
            {"POST", FOLDER_CREATE, "{\"name\":\"g3-probe\"}"},
            {"DELETE", FOLDER_DELETE, "{\"folderId\":\"9001\"}"},
            {"GET", FOLDER_LIST, null},
            {"POST", FOLDER_UPDATE, "{\"folderId\":\"9001\",\"name\":\"g3-probe\"}"},
            {"POST", ITEM_CREATE, "{\"folderId\":\"9001\",\"targetId\":\"t-1\",\"targetType\":\"post\"}"},
            {"DELETE", ITEM_DELETE, "{\"favoriteId\":\"7001\"}"},
            {"GET", ITEM_LIST, null},
    };

    private HttpResponse<String> sendWithToken(String method, String path, String body, String query)
            throws Exception {
        String url = "http://127.0.0.1:" + port + path + ((query == null || query.isEmpty()) ? "" : "?" + query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token());
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return send(builder.build());
    }

    /**
     * 裸 socket 送请求 —— 用于那些<b>JDK 客户端在 URI 解析阶段就会拒</b>的输入
     * （本类里是 {@code %zz} 非法转义）。
     */
    private RawResponse sendRawSocket(String method, String path, String query, String body) throws Exception {
        String target = path + ((query == null || query.isEmpty()) ? "" : "?" + query);
        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        req.append("Host: 127.0.0.1:").append(port).append("\r\n");
        req.append("Authorization: Bearer ").append(token()).append("\r\n");
        if (body != null) {
            req.append("Content-Type: application/json\r\n");
            req.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        }
        req.append("Connection: close\r\n\r\n");
        if (body != null) {
            req.append(body);
        }
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(30000);
            sock.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            sock.getOutputStream().flush();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = sock.getInputStream().read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return RawResponse.parse(bos.toString(StandardCharsets.ISO_8859_1));
        }
    }

    /**
     * 裸 socket 的响应 —— <b>只取最后一个状态行</b>（共享层的断连探针会先发 1xx），
     * 并按 {@code Content-Length} / chunked 解出 body。
     */
    private record RawResponse(int status, String body, String raw) {
        static RawResponse parse(String raw) {
            int status = -1;
            int lastStatusAt = -1;
            int idx = 0;
            while (true) {
                int at = raw.indexOf("HTTP/1.1 ", idx);
                if (at < 0) {
                    break;
                }
                int codeEnd = raw.indexOf(' ', at + 9);
                if (codeEnd > 0) {
                    try {
                        status = Integer.parseInt(raw.substring(at + 9, codeEnd).trim());
                        lastStatusAt = at;
                    } catch (NumberFormatException ignored) {
                        // 不是合法状态行 ⇒ 继续找下一个
                    }
                }
                idx = at + 9;
            }
            if (lastStatusAt < 0) {
                // 没有状态行 ⇒ 整个 raw 都当 body（便于断言时看到真实内容）
                return new RawResponse(status, raw, raw);
            }
            int headerEnd = raw.indexOf("\r\n\r\n", lastStatusAt);
            if (headerEnd < 0) {
                return new RawResponse(status, "", raw);
            }
            String headers = raw.substring(lastStatusAt, headerEnd);
            String bodyRaw = raw.substring(headerEnd + 4);
            if (headers.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) {
                bodyRaw = dechunk(bodyRaw);
            }
            // 先按 ISO-8859-1 逐字节取回，再按 UTF-8 解码（中文文案）
            String body = new String(bodyRaw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return new RawResponse(status, body, raw);
        }

        private static String dechunk(String s) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                int lineEnd = s.indexOf("\r\n", i);
                if (lineEnd < 0) {
                    break;
                }
                String sizeLine = s.substring(i, lineEnd).trim();
                int semi = sizeLine.indexOf(';');
                if (semi >= 0) {
                    sizeLine = sizeLine.substring(0, semi).trim();
                }
                int size;
                try {
                    size = Integer.parseInt(sizeLine, 16);
                } catch (NumberFormatException e) {
                    break;
                }
                i = lineEnd + 2;
                if (size == 0) {
                    break;
                }
                if (i + size > s.length()) {
                    out.append(s, i, s.length());
                    break;
                }
                out.append(s, i, i + size);
                i += size + 2;
            }
            return out.toString();
        }
    }

    // ══════════════════ 辅助 ══════════════════

    private static void assertNotTomcat400(HttpResponse<String> resp) {
        assertFalse(resp.statusCode() == 400 && isTomcatHtmlErrorPage(resp),
                "出现了 Tomcat 的 400 HTML 错误页（Go 侧不会产生这个响应）");
    }

    private static boolean isTomcatHtmlErrorPage(HttpResponse<String> resp) {
        String contentType = resp.headers().firstValue("content-type").orElse("");
        String body = resp.body() == null ? "" : resp.body();
        return contentType.contains("text/html")
                && (body.contains("<html") || body.contains("Apache Tomcat"));
    }

    private static boolean isBusinessEnvelope(HttpResponse<String> resp) {
        String contentType = resp.headers().firstValue("content-type").orElse("");
        String body = resp.body() == null ? "" : resp.body();
        return contentType.contains("application/json")
                && body.contains("\"code\"")
                && body.contains("\"msg\"");
    }

    private static String oversizedJson() {
        StringBuilder sb = new StringBuilder("{\"name\":\"");
        while (sb.length() < 4096) {
            sb.append('a');
        }
        return sb.append("\"}").toString();
    }

    /**
     * 造 N 个<b>非空</b> form 值（同一个 key {@code folderId}）。
     *
     * <p>🔴🔴 <b>为什么取值从 1 开始、而不是 B 侧那样的 0</b>（2026-11-04 实测踩到的坑）：
     * <ul>
     *   <li>B 侧用 {@code folderId=0..N-1} ⇒ <b>第一个值是 0</b>；</li>
     *   <li>B 侧那个测试有 {@code @MockitoBean FavoriteRpcClient} ⇒ <b>下游客户端是 mock</b>
     *       ⇒ 请求**根本到不了 rpc 层** ⇒ {@code folderId=0} 没人判 ⇒ 所以 B 侧那个
     *       「恰好 2048 个非空值放行 ⇒ 不是 5003」的断言在 B 侧成立；</li>
     *   <li>C 侧是 <b>D2 域内直连</b>（<b>没有</b> favorite-rpc 客户端）⇒ 请求**真的进了领域层**
     *       ⇒ 而领域层对 {@code folderId <= 0} 的判定与 B 侧**逐字一致**
     *       （B 侧 {@code FavoriteDomainService.java:756-759}「{@code userId <= 0 || folderId <= 0}
     *       ⇒ {@code invalidParam()}」= **5003**；C 侧 {@code FavoriteDomainService.java:429-432} 同款）
     *       ⇒ ⇒ 于是 {@code folderId=0} 也会得到 **5003** ⇒ ⇒ 「不是 5003」这一格在 C 侧**不成立</b>。</li>
     * </ul>
     * ⇒ ⇒ 所以 C 侧把取值改成 <b>从 1 开始</b>（全为正数 ⇒ 领域层不判 5003）
     * ⇒ ⇒ 于是这一格重新只测<b>「非空值计数闸门」</b>本身：
     * <b>2048 放行 / 2049 拒</b> ⇒ ⇒ 断言的<b>被测对象没变</b>（仍是 Go 的
     * {@code maxFormParamCount=2048}），只是<b>去掉了与本判据无关的干扰项</b>。
     * <p>⚠️ <b>这不是「为了让测试变绿而改预期」</b>：B 侧与 C 侧对 {@code folderId=0}
     * 的**领域层行为完全一致**（都返回 5003）⇒ ⇒ <b>两侧行为无漂移</b>；
     * 变的只是 B 侧那个测试<b>能不能走到领域层</b>（被 mock 挡住了）。
     */
    private static String manyFormParams(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append("folderId=").append(i + 1);
        }
        return sb.toString();
    }

    private static String oneNonEmptyPlusEmpty(int emptyCount) {
        StringBuilder sb = new StringBuilder("folderId=7");
        for (int i = 0; i < emptyCount; i++) {
            sb.append("&folderId=");
        }
        return sb.toString();
    }
}
