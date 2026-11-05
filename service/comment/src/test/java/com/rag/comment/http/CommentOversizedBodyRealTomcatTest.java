package com.rag.comment.http;

import com.rag.comment.CommentServiceApplication;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>🔴 <b>超限 body ⇒ 413（压过 401）</b>的守门（真 Tomcat ＋ 真 HTTP 线路）。
 *
 * <h3>守的是什么</h3>
 * <p>B 侧（go-zero）的链序：<b>MaxBytes 是 native middleware，排在 Authorize 之外</b>
 * ⇒ ⇒ 往受保护端点发一个超限 body 的<b>无 token</b> 请求，B 侧返回 <b>413</b> 而不是 401。
 * ⇒ ⇒ C 侧必须同形：<b>413 优先于 401</b>。
 *
 * <h3>🔴 为什么必须走真 Tomcat（不能用 MockMvc）</h3>
 * <p>「413 压过 401」这条语义的<b>承载结构</b>是 Servlet 的 Filter / HandlerInterceptor 分层：
 * <ul>
 *   <li>共享层的 {@code MaxBytesFilter} 是 {@code jakarta.servlet.Filter}
 *       ⇒ 它在 {@code DispatcherServlet} <b>外面</b>；</li>
 *   <li>本域的 {@code CommentJwtAuthInterceptor} 是 {@code HandlerInterceptor}
 *       ⇒ 它在 {@code DispatcherServlet} <b>里面</b>。</li>
 * </ul>
 * ⇒ ⇒ 两者谁先跑，<b>由容器结构决定</b>，与两者的 {@code @Order} 数值<b>无关</b>
 * ⇒ ⇒ MockMvc 也会走 Filter 链，但它<b>不产生真实的状态行与响应头</b>
 * ⇒ ⇒ 「裸 413（无 body、无 Content-Type）」这个形状<b>必须在真 HTTP 响应上验</b>
 * （AGENTS.md §八「HTTP 断言走真 Tomcat」）。
 *
 * <h3>🔴 为什么必须带一条<b>负控</b>（正常体 ⇒ 401）</h3>
 * <p>只断言「超限 ⇒ 413」的话，本类有可能因为「这个端点不管怎样都返回 413」而绿
 * ⇒ ⇒ 负控（<b>正常体 ＋ 无 token ⇒ 必须是 401</b>）证明 413 是<b>由体大小决定</b>的。
 *
 * <h3>三段式（AGENTS.md §3.2）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>断言形态从 MockMvc 换成真 Tomcat</b>：B 侧那条用
 *             {@code mockMvc.perform(...).andExpect(status().isPayloadTooLarge())}，
 *             C 侧改成<b>真 HTTP 请求</b> ⇒ 顺带把「裸 413（<b>空 body</b>）」这条
 *             B 侧用 {@code content().string("")} 验过的形状钉在真实响应上。</li>
 *         <li><b>多了一条「带无效 token 也是 413」</b>（B 侧只验了「无 token」）；
 *             加了「正常体 ⇒ 401」的<b>负控</b>；
 *             加了一条<b>生产 yml 的上限值</b>断言（{@code rag.protection.max-bytes = 1048576}），
 *             用来钉住「本类那个 1 KiB 覆盖只是测试专用」。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 期望值（超限 ⇒ 413 ＋ 空 body；无 token ＋ 正常体 ⇒ 401）
 *       逐条来自 B 侧；C 侧<b>没有</b>引入任何新的行为判断。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code service/comment/comment-api/src/test/java/com/rag/comment/comment/api/CommentApiJwtVerificationScopeTest.java:229-240}
 *             （B 侧「413 压过 401」那条断言的逐条 oracle）</li>
 *         <li>{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/auth/GoStyleJwtAuthFilter.java:79-89}
 *             （B 侧「MaxBytes 在 Authorize 之外 ⇒ 413 压过 401」的链序论证）</li>
 *         <li>go-zero v1.10.2 {@code rest/engine.go:163-165}（MaxBytes 是 native middleware）</li>
 *         <li>共享层 C 侧落点：{@code common/src/main/java/com/rag/common/protection/MaxBytesFilter.java:111-131}
 *             （<b>消费点</b>：{@code doFilter} 里 {@code getContentLengthLong() > maxBytes ⇒ setStatus(413)}）</li>
 *         <li>C 侧生产配置：{@code service/comment/src/main/resources/application.yml:386}
 *             （{@code rag.protection.max-bytes: 1048576}）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>🔴 端口取法（为什么不用 {@code port=0}）</h2>
 * <p>{@code port=0} 会让 OS 从<b>临时端口段</b>（macOS 49152–65535）里挑，而那正是
 * <b>Docker 随机发布容器端口用的同一段</b> ⇒ ⇒ 别的会话的容器先占住 ⇒ EADDRINUSE
 * ⇒ ⇒ 表现为「偶尔红、换个时间又绿」的抖动。
 * ⇒ ⇒ 本类走<b>本域专用段 22600–22699</b>（global 段表预分配给 comment 域）里的一个空闲口。
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>（本类<b>不</b>冒充该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 MockMvc（走真 Tomcat）；</li>
 *   <li>不用 {@code @EnabledIfDockerAvailable}；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        classes = {CommentServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // 绕过 Nacos（注册与发现都不做）
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 绕过 Kafka（consumer 会持续重试连接，但不影响上下文启动与本类断言）
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // 🔴 测试专用覆盖：把体上限压到 1 KiB。
                //    ⚠️ **只影响本测试上下文**（@SpringBootTest 的 properties 是上下文级的），
                //    **不改生产默认值**（生产值 = 1048576，由下面那条用例钉住）。
                //    B 侧同款做法：comment-api/…/CommentApiJwtVerificationScopeTest.java:86
                //    （B 侧注：Go 侧 comment-api 没有 MaxBytes，走 rest/config.go:53 的 default=1048576
                //    ⇒ B 侧那条用例只证明 RAG 侧的不变式成立）。
                "rag.protection.max-bytes=1024",
                // 🔴 关掉 CPU 自适应降载：降载命中时 Filter 走 `response.setStatus(503)` 并直接 return
                //    ⇒ **裸 503、无 body** ⇒ 会把本类要验的「413 / 401」判据抢先盖掉
                //    ⇒ ⇒ 机器一忙就**红在错的地方**。这是测试专用覆盖，不改生产默认值。
                "rag.protection.cpu-shedding.enabled=false"
        })
@ActiveProfiles("test")
class CommentOversizedBodyRealTomcatTest {

    /** 本域专用端口段的起点（global 段表：comment 域 = 22600–22699）。 */
    private static final int PORT_BASE = 22600;

    /** 本类占用的 HTTP 端口（在专用段里挑一个空闲口）。 */
    private static final int HTTP_PORT = pickFreePort(PORT_BASE);

    /** 受保护端点（B 出处：B 侧 {@code CommentController.java:96} 起的 7 条之一）。 */
    private static final String COMMENT_CREATE = "/comment/v1/comment/create";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        // ⚠️ gRPC server **不起 TCP 端口**（-1 ⇒ in-process）⇒ 不与本类的 HTTP 端口抢口。
        registry.add("grpc.server.port", () -> -1);
        registry.add("server.port", () -> HTTP_PORT);
        // ⚠️ Redis：Lettuce 是**懒连接** ⇒ 指向一个必然拒连的本机端口即可（缓存读失败不致命）。
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
    }

    @Autowired
    private TestRestTemplate rest;

    // ====================================================================================
    // ① 超限 body ⇒ 413（压过 401）
    // ====================================================================================

    @Test
    @DisplayName("🔴 超限 body + **无 token** ⇒ 413（不是 401）⇒「413 压过 401」没被搬家搬坏")
    void oversizedBodyWithoutTokenReturns413() {
        ResponseEntity<String> resp = post(oversizedJson(), null);

        assertThat(resp.getStatusCode().value())
                .as("超限 body ⇒ 413（MaxBytes 在鉴权之外 ⇒ 压过 401）")
                .isEqualTo(413);
        assertThat(resp.getBody())
                .as("B 侧是裸 `w.WriteHeader(413)` ⇒ 不写任何字节 ⇒ body 必须为空"
                        + "（不是业务信封、不是 text/html 错误页）")
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
        assertThat(resp.getHeaders().getContentType())
                .as("裸 413 ⇒ **不设** Content-Type（B 侧只调 WriteHeader(413)，"
                        + "没有 httpx.Error ⇒ 没有 envelope、也没有 text/html）")
                .isNull();
    }

    @Test
    @DisplayName("🔴 超限 body + **无效 token** ⇒ 仍然是 413（不是 401）⇒ 413 压过的是「鉴权失败」这一整条路")
    void oversizedBodyWithInvalidTokenStillReturns413() {
        ResponseEntity<String> resp = post(oversizedJson(), "Bearer not-a-jwt");

        assertThat(resp.getStatusCode().value())
                .as("token 无效也一样是 413 ⇒ ⇒ 证明拦在 DispatcherServlet 外面的那一层（Filter）先跑了")
                .isEqualTo(413);
        assertThat(resp.getBody())
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    // ====================================================================================
    // ② 负控：正常体 ⇒ 401（证明 413 是由「体大小」决定的）
    // ====================================================================================

    @Test
    @DisplayName("✅ 负控：正常大小 body + 无 token ⇒ 401（不是 413）⇒ 413 是由体大小决定的")
    void normalBodyWithoutTokenReturns401() {
        ResponseEntity<String> resp = post(normalJson(), null);

        assertThat(resp.getStatusCode().value())
                .as("体没超限 ⇒ 请求走到鉴权层 ⇒ 无 token ⇒ 401 空 body"
                        + "（⇒ 证明上一条的红是「体超限」而不是「这个端点一律 413」）")
                .isEqualTo(401);
        assertThat(resp.getBody())
                .as("401 的 body 也是空的（B 侧 unauthorized() 只 WriteHeader(401)）")
                .satisfies(b -> assertThat(b == null || b.isEmpty()).isTrue());
    }

    // ====================================================================================
    // ③ 生产配置：体上限必须是 go-zero 的默认值
    // ====================================================================================

    @Test
    @DisplayName("🔴 生产 application.yml 的 rag.protection.max-bytes 必须是 1048576（go-zero rest/config.go 的 default）")
    void productionMaxBytesMatchesGoZeroDefault() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml 必须随 jar 打包").isNotNull();
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yml)
                    .as("⚠️ B 侧 comment-api/…/application.yml:145 没写 MaxBytes ⇒ 生效值来自"
                            + " go-zero v1.10.2 rest/config.go:53 的 `default=1048576`；"
                            + "C 侧必须显式写出同一个值（AGENTS.md §3.3.1：「配置里没写」≠「没开」）")
                    .contains("max-bytes: 1048576");
        }
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /**
     * 造一个<b>超过上限</b>、但仍是合法 JSON 的体。
     *
     * <p>⚠️ 必须<b>合法 JSON</b>：否则 413 与「JSON 解析失败」两条路会混在一起，
     * 判据就分不清是「体太大」还是「体坏了」。B 侧同款做法
     * （{@code CommentApiJwtVerificationScopeTest#oversizedJson}）。
     */
    private static String oversizedJson() {
        StringBuilder sb = new StringBuilder("{\"content\":\"");
        while (sb.length() < 4096) {
            sb.append('a');
        }
        return sb.append("\"}").toString();
    }

    /** 正常大小的合法 JSON 体（远小于本类的 1 KiB 测试上限）。 */
    private static String normalJson() {
        return "{\"content\":\"c\"}";
    }

    private ResponseEntity<String> post(String body, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        return rest.exchange(COMMENT_CREATE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 在本域专用端口段里挑一个<b>空闲</b>端口。
     *
     * <p>⚠️ 先 bind 再 close ⇒ 拿到的是一个「刚才能 bind」的端口 ⇒ 与「已被占用」的端口区分开。
     */
    private static int pickFreePort(int base) {
        for (int p = base; p <= base + 99; p++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", p));
                return p;
            } catch (IOException ignored) {
                // 已被占用 ⇒ 试下一个
            }
        }
        throw new IllegalStateException("本域专用端口段（22600–22699）内没有空闲端口");
    }
}
