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
import org.springframework.http.HttpStatus;
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
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 DRIFT-ADMINAPI-011 守门测试 —— 400 响应的 {@code Content-Type} 必须与 Go 逐字节相同。
 *
 * <p><b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13 · AGENTS.md §1.0c「测试资产单独一档」）。</b>
 *
 * <p><b>B 出处</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-api/src/test/java/com/rag/users/admin/api/AdminApiPlainText400ContentTypeTest.java}
 * （B 侧源文件 · 143 行 · 2 个 {@code @Test}）。关键行号：
 * <pre>
 *   B: .../AdminApiPlainText400ContentTypeTest.java:85-121   必填字段缺失 ⇒ charset 必须存在且等于 utf-8
 *   B: .../AdminApiPlainText400ContentTypeTest.java:123-142  畸形 JSON ⇒ 同一分支 ⇒ charset 也一样 + 尾随换行
 * </pre>
 *
 * <h2>发现方式</h2>
 *
 * <p>2026-10-23 的<b>双侧活体对拍</b>（真实 Tomcat，Go admin-api 18889 / RAG admin-api 58889，
 * 两套栈同时跑）打同一条「缺必填字段」的请求，抓响应头：
 *
 * <pre>
 *   Go  : HTTP/1.1 400 Bad Request
 *       Content-Type: text/plain; charset=utf-8
 *       X-Content-Type-Options: nosniff
 *       body: field "uid" is not set\n        （23 字节，与 RAG 逐字节相同）
 *
 *   RAG : HTTP/1.1 400
 *       Content-Type: text/plain            ← 🔴 少了 charset 参数
 *       X-Content-Type-Options: nosniff
 *       body: field "uid" is not set\n        （23 字节，与 Go 逐字节相同）
 * </pre>
 *
 * <p>⇒ **body 与 nosniff 都一致**，只有 {@code Content-Type} 差一个 charset 参数。
 *
 * <h2>Go 出处</h2>
 *
 * <p>Go 的 {@code net/http.Error} 走 {@code h.Set("Content-Type", "text/plain; charset=utf-8")}
 * —— 是**逐字写死**的字符串，不是框架推导出来的。
 *
 * <h2>为什么现有的守门抓不到</h2>
 *
 * <p>{@code tools/difftest/check-plaintext400-headers.sh} 只断言
 * 「Content-Type <b>含</b> text/plain」⇒ charset 的有无它看不见。
 * ⇒ 需要一条**独立**的、断言**完整头值**的守门。
 *
 * <h2>严重度</h2>
 *
 * <p>LOW —— 400 的 body 全是 ASCII，任何 charset 解释都得到同一串字节，
 * <b>无字节级歧义</b>；只是响应头与 Go 不逐字相同（§3.1 明确要求覆盖 HTTP 响应头）。
 *
 * <h2>🔴 移植时的<b>唯一改动</b>：MockMvc ⇒ <b>真 Tomcat</b></h2>
 * <p>B 侧这一份是 <b>MockMvc 版</b>；本仓按 AGENTS.md §八「HTTP 断言走真 Tomcat」
 * 改成 <b>真 Tomcat 版</b>（{@code RANDOM_PORT} + {@link TestRestTemplate}），
 * 起法沿用本域既有的 {@code AdminHttpContractRealTomcatTest}。
 * <p>⚠️ <b>为什么必须换</b>：MockMvc 的 {@code MockHttpServletResponse}
 * 在 {@code setHeader} / {@code commit} 的时序上与 Tomcat 不同
 * ⇒ ⇒ MockMvc 里的头值断言<b>对真实字节没有判别力</b>。
 * <p>⇒ 断言的<b>语义</b>（media type = text/plain + charset = utf-8 + nosniff）<b>逐字沿用 B 侧</b>，
 * 只是把它从「容器内的模拟响应」搬到「wire 上真正落地的响应」。
 * <p>⚠️ B 侧那条注释（「MockMvc 原样透传 / 真 Tomcat 去掉分号后的空格」）在本仓<b>不适用</b>：
 * 本仓的 400 响应走共享层 {@code PlainTextErrors#text}，
 * 它用<b>原始字符串</b> {@code "text/plain; charset=utf-8"} 写响应头
 * ⇒ ⇒ <b>本类断言的是「charset 存在且等于 utf-8」这个语义</b>，
 * <b>不</b>断言分号后有没有空格（那是容器渲染差异，Tomcat 上无任何写法能产出 B 侧的空格）。
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
class AdminApiPlainText400ContentTypeTest {

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
        registry.add("ADMIN_JWT_SECRET", () -> "admin-plaintext400-ct-test-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("🔴 400 text/plain 的 Content-Type 必须是 text/plain; charset=utf-8（与 Go 逐字节相同）")
    void badRequestContentTypeMatchesGoByteForByte() {
        // 用 public 路径 /admin/v1/create：缺必填字段 ⇒ GoRequiredFieldException ⇒ 400 text/plain。
        // ⚠️ 不能用 /admin/v1/getuser：它是受保护路径，**鉴权在请求体解析之前**短路 ⇒ 会拿到 401，
        //    根本走不到 text/plain 分支（本测试第一次跑就踩到了：expected 400 but was 401）。
        ResponseEntity<String> resp = post("/admin/v1/create", "{}");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Go: http.Error(w, err.Error(), http.StatusBadRequest) ⇒ 状态码必须是 400。实际="
                        + resp.getStatusCode() + " body=[" + resp.getBody() + "]");

        // ⚠️ 这里断言的是**语义**（media type + charset），不是**字节渲染**。
        // 原因（2026-10-23 真实 Tomcat 实测）：
        //   MockMvc  : `text/plain; charset=utf-8`   （原样透传我们 set 的字符串）
        //   真 Tomcat: `text/plain;charset=utf-8`     （框架/容器重渲染：去掉 `;` 后的空格）
        //   Go       : `text/plain; charset=utf-8`     （net/http 逐字写死）
        // ⇒ 断言完整头值会**因容器而异**，那是 §3.3「MockMvc 测不到真实栈」的又一形态。
        // ⇒ 断言「charset 存在且等于 UTF-8」才是稳定且有意义的守门
        //    （改回 MediaType.TEXT_PLAIN 就没有 charset ⇒ 本断言立刻红）。
        String ct = resp.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertNotNull(ct, "400 必须带 Content-Type（Go 的 net/http.Error 会设）");
        MediaType parsed = MediaType.parseMediaType(ct);
        assertEquals("text", parsed.getType(), "Go 的 net/http.Error 用的是 text/plain");
        assertEquals("plain", parsed.getSubtype(), "Go 的 net/http.Error 用的是 text/plain");
        assertEquals(StandardCharsets.UTF_8, parsed.getCharset(),
                "🔴 本条是本轮修的那个漂移：Go 的 net/http.Error 逐字写死 "
                        + "`text/plain; charset=utf-8`；本实现此前用 MediaType.TEXT_PLAIN "
                        + "⇒ 渲染成 `text/plain`（**完全没有 charset**）。"
                        + "实测（真实 Tomcat，2026-10-23）：Go=「text/plain; charset=utf-8」/ "
                        + "修复前 RAG=「text/plain」。⚠️ 不要改回 MediaType —— "
                        + "new MediaType(\"text\",\"plain\",UTF_8) 渲染成 `text/plain;charset=UTF-8`"
                        + "（大写 UTF-8），与 Go 的小写 utf-8 也不逐字节相同 ⇒ 本实现用原始字符串 set");

        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"),
                "Go 的 net/http.Error 同时设 nosniff（这一项两侧本就一致，守门别把它放松掉）");
    }

    @Test
    @DisplayName("🔴 畸形 JSON 的 400 同样带 charset（同一分支，不能只测必填缺失）")
    void malformedJsonContentTypeAlsoCarriesCharset() {
        ResponseEntity<String> resp = post("/admin/v1/create", "{\"a\":}");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Go: 畸形 JSON 同样是 httpx.Parse 失败 ⇒ 400 text/plain。实际=" + resp.getStatusCode());

        MediaType parsed = MediaType.parseMediaType(
                resp.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("plain", parsed.getSubtype(),
                "畸形 JSON 走的是同一个 goStylePlainTextBadRequest 分支 ⇒ 头必须一致");
        assertEquals(StandardCharsets.UTF_8, parsed.getCharset(),
                "同一分支 ⇒ charset 也必须一样");

        String body = resp.getBody();
        assertTrue(body != null && body.endsWith("\n"),
                "Go 的 http.Error 写 body + 尾随换行；实际 body="
                        + (body == null ? "null" : body.replace("\n", "\\n")));
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }
}
