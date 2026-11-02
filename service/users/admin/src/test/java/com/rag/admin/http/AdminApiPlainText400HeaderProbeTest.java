package com.rag.admin.http;

import com.rag.admin.AdminServiceApplication;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E-1 取证（2026-10-22）：定位「RAG 的 text/plain 400 丢了 Content-Type」的机制层成因。
 *
 * <p><b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13 · AGENTS.md §1.0c「测试资产单独一档」）。</b>
 *
 * <p><b>B 出处</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-api/src/test/java/com/rag/users/admin/api/AdminApiPlainText400HeaderProbeTest.java}
 * （B 侧源文件 · 2 个 {@code @Test}）。关键行号：
 * <pre>
 *   B: .../AdminApiPlainText400HeaderProbeTest.java:77-96    取证：畸形 JSON → 400 text/plain 时头是什么样
 *   B: .../AdminApiPlainText400HeaderProbeTest.java:108-123  取证对照：200 JSON 响应的 Content-Type 是否正常
 * </pre>
 *
 * <p><b>现象（curl 实测，两端同请求，逐字对照）</b>：
 * <pre>
 *   Go : HTTP/1.1 400 Bad Request
 *        Content-Type: text/plain; charset=utf-8
 *        X-Content-Type-Options: nosniff
 *        Content-Length: 95
 *
 *   RAG: HTTP/1.1 400                  &lt;-- 连 reason phrase 都没有
 *        （无 Content-Type）
 *        （无 X-Content-Type-Options）
 *        Transfer-Encoding: chunked
 *        Connection: close
 * </pre>
 * 正文是<b>对齐</b>的（DRIFT-006 对齐的正是正文），所以只比 body 的 difftest
 * 连续 4 轮全绿 —— 缺陷藏在<b>响应头</b>这一层，从来没被比对过。
 *
 * <p><b>本类的目的</b>：把「advice 根本没设 Content-Type」与
 * 「设了但被 HttpMiddleware 的 wrapper 吞掉」这两种可能<b>用证据区分开</b>，
 * 而不是靠静态推理下结论。
 *
 * <p><b>为什么用真 MVC + 真 filter</b>（§3.3）：{@code standaloneSetup} 不挂 filter，
 * 测不到 wrapper；这是 {@code AdminApiMvcPipelineTest} 当初栽过的坑
 * （见 §3.4.2「N-1 的二次坍塌」——standaloneSetup 全绿而真实管线出 HTTP 200）。
 *
 * <h2>🔴 移植时的<b>唯一改动</b>：MockMvc ⇒ <b>真 Tomcat</b></h2>
 * <p>B 侧这一份是 <b>MockMvc 版</b>；本仓按 AGENTS.md §八「HTTP 断言走真 Tomcat」
 * 改成 <b>真 Tomcat 版</b>（{@code RANDOM_PORT} + {@link TestRestTemplate}），
 * 起法沿用本域既有的 {@code AdminHttpContractRealTomcatTest}。
 * <p>⇒ 本类仍然是<b>「取证」类</b>：它把真实响应头打到 stdout，供台账引用；
 * 唯一的断言（B 侧那条 {@code assertEquals(400, …)}）逐字保留。
 *
 * <h2>⚠️ 本类的 2 条用例（B 侧也是 <b>2</b> 条）</h2>
 * <p>① 畸形 JSON → 400 text/plain（<b>断言状态码 = 400</b>）；
 * ② 对照组：200 JSON 路径的 Content-Type 是否正常（B 侧<b>不断言</b>，只取证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AdminServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // Nacos 整体不生效 ⇒ 自动配置不注册 NacosServiceManager ⇒ 由 NacosStubConfiguration 补上
                "spring.cloud.nacos.discovery.enabled=false",
                // 服务注册自动注册关掉（否则需要真的 ServiceRegistry + Nacos）
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.address=127.0.0.1"
        })
@Testcontainers
class AdminApiPlainText400HeaderProbeTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * gRPC server 的端口 —— 取一个<b>真实空闲端口</b>（不是 0）。
     *
     * <p>⚠️ 写法与理由照抄本域 {@code AdminServiceApplicationTests}：
     * {@code new ServerSocket(0)} 让 OS 立刻分配一个当前空闲的端口、随即释放
     * ⇒ 拿到的是<b>真实可绑</b>的端口。
     */
    static final int GRPC_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            GRPC_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // ⚠️ yml 里是 `${ADMIN_JWT_SECRET}`（**无默认值**）⇒ 必须由外部注入，
        //    否则 AdminApiProperties/AdminRpcProperties 的 @PostConstruct 会 fail-closed 启动失败。
        registry.add("ADMIN_JWT_SECRET", () -> "admin-plaintext400-probe-test-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("取证：畸形 JSON → 400 text/plain 时 Content-Type 是否真的丢了")
    void probeMalformedJson() {
        ResponseEntity<String> r = post("/admin/v1/create", "{bad json", "Bearer probe");

        System.out.println("======== E-1 取证输出 ========");
        System.out.println("status                 = " + r.getStatusCode());
        System.out.println("Content-Type           = " + r.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        System.out.println("X-Content-Type-Options = "
                + r.getHeaders().getFirst("X-Content-Type-Options"));
        System.out.println("body                   = ["
                + (r.getBody() == null ? "null" : r.getBody().replace("\n", "\\n")) + "]");
        System.out.println("=============================");

        assertEquals(400, r.getStatusCode().value());
    }

    /**
     * 对照组：走 200 的 JSON 路径，看 Content-Type 是否<b>正常</b>。
     *
     * <p>这一条是关键的鉴别器：
     * <ul>
     *   <li>若这里 Content-Type 正常、而上一条缺失 ⇒ 问题<b>只</b>出在 text/plain 分支
     *       （即 advice 的 header 或 wrapper 对 String body 的处理），不是全局性的；</li>
     *   <li>若这里也缺失 ⇒ wrapper 在<b>所有</b>响应上都吞 header，是更严重的普遍问题。</li>
     * </ul>
     */
    @Test
    @DisplayName("取证对照：200 JSON 响应的 Content-Type 是否正常（鉴别 wrapper 是否普遍吞 header）")
    void probeJsonOkPath() {
        // login 路径（用户不存在 → 200 + envelope JSON）
        ResponseEntity<String> r = post("/admin/v1/login",
                "{\"username\":\"probe-user\",\"password\":\"pw123456\"}", null);

        System.out.println("======== E-1 取证输出（对照组）========");
        System.out.println("status       = " + r.getStatusCode());
        System.out.println("Content-Type = " + r.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        System.out.println("body         = ["
                + (r.getBody() == null ? "null" : r.getBody().replace("\n", "\\n")) + "]");
        System.out.println("========================================");
    }

    private ResponseEntity<String> post(String path, String body, String authorization) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            h.set("Authorization", authorization);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }
}
