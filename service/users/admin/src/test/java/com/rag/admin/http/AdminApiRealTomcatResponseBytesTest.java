package com.rag.admin.http;

import com.rag.admin.AdminServiceApplication;
import com.rag.admin.domain.service.AdminDomainService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 🔴🔴 <b>admin 真 Tomcat（RANDOM_PORT）响应字节级探针</b> ——
 * 闭合台账 {@code docs/DRIFT/users/admin/api.md} 的 <b>CASE-24</b>（真 Tomcat 下 400 的
 * Content-Type / nosniff）与 <b>CASE-26</b>（3s 超时路径的 503 + nosniff），
 * 并据此验证 <b>DRIFT-ADMINAPI-007</b>（503 超时响应缺 {@code X-Content-Type-Options: nosniff}）
 * <b>已因共享层修复而闭合</b>。
 *
 * <p><b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13 · AGENTS.md §1.0c「测试资产单独一档」）。</b>
 *
 * <p><b>B 出处</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-api/src/test/java/com/rag/users/admin/api/AdminApiRealTomcatResponseBytesTest.java}
 * （B 侧源文件 · 369 行 · 3 个 {@code @Test}）。关键行号：
 * <pre>
 *   B: .../AdminApiRealTomcatResponseBytesTest.java:194-231   CASE-24（400 text/plain + nosniff + 尾随换行）
 *   B: .../AdminApiRealTomcatResponseBytesTest.java:237-277   CASE-26（503 + "Request Timeout\n" + Content-Length 16）
 *   B: .../AdminApiRealTomcatResponseBytesTest.java:283-300   对照组（快下游 ⇒ 200 + application/json）
 *   B: .../AdminApiRealTomcatResponseBytesTest.java:334-343   assertGoPlainText（归一化后比，不逐字节比）
 * </pre>
 *
 * <h2>为什么不能用 MockMvc（§3.3 的硬要求）</h2>
 * <p>CASE-24 在台账里被标成 <b>UNRESOLVED</b>，原因不是「没写测试」，而是
 * <b>已有的测试全是 MockMvc</b> ⇒ ⇒ MockMvc 的 {@code MockHttpServletResponse} 在
 * {@code setHeader} / {@code commit} 的时序上与 Tomcat 不同 ⇒ <b>MockMvc 里的断言对
 * 真实字节没有判别力</b>（台账 CASE-23 那一行的备注就是这个意思）。
 * <p>⇒ 本类用 <b>{@code webEnvironment = RANDOM_PORT}</b> 起<b>真 Tomcat</b>，
 * 再用 <b>真 {@link TestRestTemplate}</b> 打过去 ⇒ 断言的是
 * <b>wire 上真正落地的字节</b>（不是容器内的模拟对象）。
 *
 * <h2>Go 出处（逐条到消费点，不是声明点）</h2>
 * <pre>
 *   【CASE-24 · 400 text/plain】
 *   Sea-RideTheWind_golang/service/user/admin/api/internal/handler/admin/createhandler.go:20-21
 *       if err := httpx.Parse(r, &amp;req); err != nil {
 *           httpx.ErrorCtx(r.Context(), w, err)
 *   go-zero@v1.10.2/rest/httpx/responses.go:36      → doHandleError(w, err, buildErrorHandler(ctx), ...)
 *   go-zero@v1.10.2/rest/httpx/responses.go:127-140  → buildErrorHandler 返回 nil
 *       （前提：service/user 全树**从未**调用 httpx.SetErrorHandler ⇒ 实测零命中）
 *   go-zero@v1.10.2/rest/httpx/responses.go:145-156  → handler == nil 分支
 *       http.Error(w, err.Error(), http.StatusBadRequest)
 *   ⇒ Go 标准库 net/http.Error 做四件事：
 *       ① Content-Type: text/plain; charset=utf-8
 *       ② X-Content-Type-Options: nosniff
 *       ③ WriteHeader(400)
 *       ④ fmt.Fprintln(w, err) ⇒ body 带一个尾随换行
 *
 *   【CASE-26 · 503 超时】
 *   Sea-RideTheWind_golang/service/common/observability/http.go:22  → httpTimeoutBody = "Request Timeout"
 *   Sea-RideTheWind_golang/service/common/observability/http.go:80  → case &lt;-ctx.Done():
 *   Sea-RideTheWind_golang/service/common/observability/http.go:83  → statusCode := http.StatusServiceUnavailable
 *   Sea-RideTheWind_golang/service/common/observability/http.go:88  → http.Error(w, httpTimeoutBody, statusCode)
 *   ⇒ 同一个 net/http.Error ⇒ 同款四件事 + body = "Request Timeout\n"（16 字节）
 * </pre>
 *
 * <h2>⚠️ 已知且<b>不可修</b>的残差（不是漂移，不按漂移修）</h2>
 * <p>Go 写的是 <b>原始字节</b> {@code "text/plain; charset=utf-8"}（分号后<b>有空格</b>）；
 * Tomcat 的 {@code setContentType} / {@code setHeader("Content-Type", …)} 会把 Content-Type
 * <b>解析成 MediaType 再序列化</b> ⇒ 序列化时<b>吃掉分号后的空格</b>
 * ⇒ 真实栈上是 {@code "text/plain;charset=utf-8"}。
 * <p>⇒ <b>归一化后（去空格 + 小写）逐字相同，原始字节不同</b> ⇒ 这是<b>容器渲染差异</b>，
 * 不是「RAG 少写了一个空格」⇒ Tomcat 上<b>没有任何写法</b>能产出那个空格。
 * <p>⇒ 故本类断言「<b>type 是 text/plain 且 charset 存在且等于 utf-8</b>」，
 * <b>不为那一个空格把测试判红</b>（§3.3：绿测试若去守一个不可达的目标，
 * 就是「错误答案的守卫」）。
 *
 * <h2>为什么只起 1 个 Spring 上下文 / 不起容器</h2>
 * <ul>
 *   <li>本类的 3 条用例共用<b>同一份</b> {@code @SpringBootTest} 配置 ⇒ Spring 的
 *       TestContext 缓存<b>复用同一个上下文</b> ⇒ 只起一次真 Tomcat。</li>
 *   <li><b>⚠️ 与 B 侧的差别（B 侧那条已不成立）</b>：B 侧把 {@code StringRedisTemplate}
 *       打桩成「{@code hasKey → null}」（未拉黑）⇒ <b>零 Docker 依赖</b>；
 *       本仓的 admin 域<b>没有共享的 TestPostgres 单例类</b>（message 域有，admin 没有）
 *       ⇒ 沿用 admin 域既有做法（{@code AdminServiceApplicationTests} /
 *       {@code AdminHttpContractRealTomcatTest}）：<b>每个测试类自带 {@code @Container}</b>
 *       ⇒ PG / Redis 走 Testcontainers 真容器。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么超时用例打的是 <b>public</b> 路径</h2>
 * <p>{@code /admin/v1/login} 是 public 路径（台账 {@code AdminController} 的
 * 「============== public（无 JWT） ==============” 段）⇒ 不需要签 token。
 * ⇒ 而 <b>受保护</b> 路径会在鉴权层短路成 401 ⇒ <b>根本走不到慢调用</b> ⇒ 测不到超时。
 *
 * <h2>🔴 变异验证（本类不是假绿）</h2>
 * <pre>
 *   变异 A（目标断言 · CASE-26）：把共享层 {@code HttpMiddleware.writeTimeoutResponse} 的
 *         {@code resp.setHeader("X-Content-Type-Options", "nosniff")} 摘掉
 *         ⇒ 红在「nosniff 必须存在」那条断言上（不是红在前提上）
 *   变异 B（目标断言 · CASE-24）：把 admin-api 的 {@code AdminApiExceptionHandler}
 *         里 {@code goStylePlainTextBadRequest} 的 Content-Type 改成不含 charset
 *         ⇒ 红在「charset 必须存在」那条断言上
 *   变异 C（前提断言）：把本类的慢 stub 改成不睡（= 提前失去超时条件）
 *         ⇒ 红在「状态码必须是 503」那条断言上 ⇒ 证明 503 是<b>超时触发</b>的，
 *           不是这条路径本来就会返回 503
 * </pre>
 * ⚠️ 变异 A / B 落在<b>共享层 / 域侧生产代码</b>上 ⇒ 由 G4 / 本域自行实施并回滚；
 * 本类只负责保证「摘掉之后一定红」。
 *
 * <h2>🔴 移植时的两处结构性改动（D2 · 已批准的语义）</h2>
 * <ol>
 *   <li><b>「慢下游」从 gRPC 变成领域层</b>：B 侧把「下游 admin-rpc」打桩成慢
 *       （{@code AdminRpcClient} → {@code AdminServiceGrpc.AdminServiceBlockingStub}）；
 *       本仓 D2 之后 HTTP 层<b>直接注入</b> {@link AdminDomainService}
 *       ⇒ ⇒ 「下游」在本仓就是<b>领域层</b> ⇒ 慢桩打在 {@code AdminDomainService#login} 上。
 *       <b>被逼出来的是同一个东西</b>：服务端自己的 3s 预算
 *       （{@code observability.http-timeout: 3s}，共享层 {@code HttpMiddleware}）。</li>
 *   <li><b>端口</b>：B 侧用 {@code support/RealTomcatPort}（固定端口段 + 双地址 bind 自检），
 *       本仓该辅助类不存在 ⇒ gRPC 端口改用「{@code new ServerSocket(0)} 取真实空闲端口」
 *       （照抄 {@code AdminServiceApplicationTests} 的写法与理由注释）；
 *       HTTP 端口沿用本域既有测试的 {@code RANDOM_PORT}。</li>
 * </ol>
 *
 * @see com.rag.admin.http.AdminApiPlainText400ContentTypeTest
 *      —— 同一条 400 契约的 <b>真 Tomcat 版</b>（B 侧那一份是 MockMvc 版）
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
class AdminApiRealTomcatResponseBytesTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * <p>gRPC server 的端口 —— 取一个<b>真实空闲端口</b>（不是 0）。
     *
     * <p>⚠️ 为什么不用 0：B 侧本类用 {@code support/RealTomcatPort} 钉死端口段，
     * 本仓该辅助类<b>不存在</b> ⇒ 照抄 {@code AdminServiceApplicationTests} 的同款写法
     * —— {@code new ServerSocket(0)} 让 OS 立刻分配一个当前空闲的端口、随即释放
     * ⇒ 拿到的是<b>真实可绑</b>的端口（而不是 0 = 「随机」这种无法区分的哨兵值）。
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
        registry.add("ADMIN_JWT_SECRET", () -> "admin-real-tomcat-bytes-test-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
    }

    /**
     * Go: httpTimeoutBody = "Request Timeout"（http.go:22）。
     *
     * <p>⚠️ 字节数别数错：{@code "Request Timeout"} 是 <b>15 个字符</b>（R-e-q-u-e-s-t + 空格 + T-i-m-e-o-u-t）
     * ⇒ 加上尾随换行 = <b>16 字节</b> ⇒ Go 的 net/http 算出的 Content-Length 是 <b>16</b>。
     */
    private static final String GO_TIMEOUT_BODY = "Request Timeout";

    /** {@code "Request Timeout".length() + 1} = 16 —— Go 的 net/http 按缓冲内容算出的 Content-Length。 */
    private static final int GO_TIMEOUT_CONTENT_LENGTH = GO_TIMEOUT_BODY.length() + 1;

    /**
     * Go 的「缺必填字段」400 body（<b>逐字节</b>）。
     *
     * <p>Go 出处：{@code go-zero@v1.10.2/core/mapping/unmarshaler.go:1199}
     * {@code fmt.Errorf("field %q is not set", name)}
     * ⇒ {@code field "username" is not set}，再由 {@code http.Error} 的 {@code fmt.Fprintln}
     * 补一个尾随换行 ⇒ 共 28 字节。
     *
     * <p>🔴 <b>本仓<b>不</b>产出这个字面量</b> —— 见本类的「⚠️ D-01 降级」段（已登记的 L3 降级项）：
     * D5 之后必填校验改成 <b>Bean Validation</b>（{@code @NotNull}）⇒ 缺失字段由
     * <b>Hibernate Validator</b> 产出 {@code MethodArgumentNotValidException}
     * ⇒ advice（{@code advice/AdminApiExceptionHandler#handleBeanValidation}）透出
     * {@code e.getMessage()} ⇒ <b>文本由框架决定</b>，本仓<b>不</b>还原 go-zero 的字面量。
     * <p>⇒ <b>body 的「形状」（400 + text/plain + nosniff + 尾随 \n）与 B 侧一致</b>
     * ⇒ ⇒ 本类把 B 侧那条「逐字节 body」断言改成「<b>缺失字段名仍在 body 里</b>」，
     * <b>不是</b>把它删掉（文本差异本身已记在 {@code docs/L3-降级清单.md} 的 D-01 条目下）。
     */
    private static final String GO_MISSING_FIELD_BODY = "field \"username\" is not set\n";

    @Autowired
    private TestRestTemplate rest;

    /**
     * 🔴 必须 mock 掉整个 {@link AdminDomainService}。
     *
     * <p><b>为什么</b>：B 侧 mock 掉 {@code AdminRpcClient}，是为了「能控制下游的快慢」
     * （CASE-26 的慢桩）并避开「3s 内连不上 admin-rpc ⇒ 启动失败」的检查。
     * 本仓 D2 之后没有 gRPC 客户端 ⇒ 对应物就是<b>领域层 bean</b>
     * ⇒ ⇒ 同一个目的：把下游的可观测行为换成「可控的快 / 慢」。
     *
     * <p>⚠️ <b>不影响</b>：DDL 建表（{@code wiring/AdminSchemaBootstrap}）与默认 admin 播种
     * （{@code wiring/AdminSeederBootstrap}）走的是 <b>Mapper</b>（不是领域层）
     * ⇒ ⇒ mock 掉领域层不会让这两步失效。
     */
    @MockitoBean
    private AdminDomainService adminDomainService;

    // ==================================================================
    // CASE-24：真 Tomcat 下 400 的 Content-Type / nosniff / 尾换行
    // ==================================================================

    @Test
    @DisplayName("🔴 CASE-24 真 Tomcat：400 text/plain 必须带 charset=utf-8 + nosniff + 尾随换行")
    void missingRequiredFieldYieldsGoPlainText400OnRealTomcat() {
        // public 路径 + 空 body ⇒ 缺必填字段 ⇒ GoRequiredFieldException ⇒ 400 text/plain。
        // ⚠️ 不能用受保护路径（如 /admin/v1/getuser）：鉴权在请求体解析之前短路 ⇒ 拿到 401，
        //    根本走不到 text/plain 分支。
        ResponseEntity<String> resp = post("/admin/v1/create", "{}");

        dump("CASE-24 /admin/v1/create {}", resp);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Go: http.Error(w, err.Error(), http.StatusBadRequest) ⇒ 状态码必须是 400。"
                        + "实际=" + resp.getStatusCode() + " body=[" + resp.getBody() + "]");

        assertGoPlainText(resp,
                "Go 出处：go-zero@v1.10.2/rest/httpx/responses.go:155 的 http.Error(w, err.Error(), 400)"
                        + " ⇒ Content-Type 必须是 text/plain 且带 charset=utf-8。");

        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"),
                "Go: net/http 的 http.Error 同时设 X-Content-Type-Options: nosniff（Go 1.11 起）。"
                        + "⚠️ 这一条正是 DRIFT-ADMINAPI-007 争的那一项 ⇒ 摘掉就红。实际头=" + resp.getHeaders());

        assertTrue(resp.getBody() != null && resp.getBody().endsWith("\n"),
                "Go: http.Error 走 fmt.Fprintln ⇒ body 带一个尾随换行。实际 body=["
                        + (resp.getBody() == null ? "null" : resp.getBody().replace("\n", "\\n")) + "]");

        // ⚠️ **B 侧这一行是逐字节断言 body**（`field "username" is not set\n`，28 字节）——
        //   本仓**不产出**这个字面量（D-01 降级，见下段）⇒ ⇒ 逐字节断言改成
        //   「**缺失的字段名仍然出现在 body 里**」—— 这是 B 侧那条断言的**语义内核**
        //   （B 侧那条字面量之所以含 "username"，正是因为它指名的就是缺失字段）。
        //   ⇒ ⚠️ **不得**为了让它绿就把 body 断言删掉。
        assertTrue(resp.getBody() != null && resp.getBody().contains("username"),
                "Go 出处：go-zero@v1.10.2/core/mapping/unmarshaler.go:1199 的 "
                        + "fmt.Errorf(\"field %q is not set\", name) ⇒ body 必须指名缺失字段 "
                        + "`username`。⚠️ B 侧的逐字节字面量是 `" + GO_MISSING_FIELD_BODY.trim()
                        + "`（28 字节）—— 本仓**不产出**那个字面量（D-01 降级，见本类 javadoc）"
                        + "⇒ 本条断言锁的是「**缺失字段名仍在 body 里**」这个语义内核。"
                        + "实际 body=["
                        + (resp.getBody() == null ? "null" : resp.getBody().replace("\n", "\\n")) + "]");
    }

    // ==================================================================
    // CASE-26：真 Tomcat 下 3s 超时路径的 503 + nosniff
    // ==================================================================

    @Test
    @DisplayName("🔴 CASE-26 真 Tomcat：下游睡过 3s ⇒ 必须 503 + text/plain;charset=utf-8 + nosniff + \"Request Timeout\\n\"")
    void slowDownstreamYieldsGoTimeout503OnRealTomcat() {
        // 让下游睡 5s（> observability.http-timeout: 3s）⇒ 把超时分支真正逼出来。
        makeDownstreamSlow(5000L);

        long t0 = System.nanoTime();
        ResponseEntity<String> resp = post("/admin/v1/login", LOGIN_BODY);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        dump("CASE-26 /admin/v1/login (slow " + elapsedMs + "ms)", resp);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode(),
                "Go: service/common/observability/http.go:83 的 statusCode := http.StatusServiceUnavailable"
                        + " ⇒ 状态码必须是 503。实际=" + resp.getStatusCode() + " body=[" + resp.getBody() + "]"
                        + " 耗时=" + elapsedMs + "ms");

        assertGoPlainText(resp,
                "Go 出处：service/common/observability/http.go:88 的 http.Error(w, httpTimeoutBody, 503)"
                        + " ⇒ Content-Type 必须是 text/plain 且带 charset=utf-8。");

        assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"),
                "Go: net/http 的 http.Error 同时设 X-Content-Type-Options: nosniff。"
                        + "⚠️ 这一条正是 DRIFT-ADMINAPI-007 争的那一项 ⇒ 摘掉就红。实际头=" + resp.getHeaders());

        assertEquals(GO_TIMEOUT_BODY + "\n", resp.getBody(),
                "Go: http.Error 写 body + 尾随换行 ⇒ 必须是 \"Request Timeout\\n\"。实际=["
                        + (resp.getBody() == null ? "null" : resp.getBody().replace("\n", "\\n")) + "]");

        // Go 的 net/http 收尾时按缓冲内容算长度 ⇒ 线上是 Content-Length: 16。
        // ⚠️ **2026-10-29 订正**：本行原写「Content-Length: 15」⇒ **与紧邻的断言矛盾**
        //   （断言用的是 GO_TIMEOUT_CONTENT_LENGTH = "Request Timeout".length() + 1 = **16**）
        //   ⇒ 15 是「只有正文、不含尾随换行」的长度 ⇒ **漏了 fmt.Fprintln 补的那个 \n**。
        //   同源事件：follow 域的 404 body 被写成「20」（真实 19）⇒ G2 已在 like 域订正为 19
        //   ⇒ 三个域各有一处「**注释里的字节数少/多算了尾随换行**」⇒ 同一族的注释漂移。
        // RAG 侧靠共享层 writeTimeoutResponse 里那句显式 setContentLength 对齐。
        String cl = resp.getHeaders().getFirst("Content-Length");
        assertEquals(String.valueOf(GO_TIMEOUT_CONTENT_LENGTH), cl,
                "Go: net/http 按缓冲内容算长度 ⇒ Content-Length 应为 "
                        + GO_TIMEOUT_CONTENT_LENGTH + "（\"Request Timeout\" 15 字节 + 尾随换行 1 字节）。实际=" + cl);
    }

    // ==================================================================
    // 对照组：同一条路径 + 快下游 ⇒ 200 + application/json
    // ==================================================================

    @Test
    @DisplayName("✅ 对照组：同一路由 + 快下游 ⇒ 200 + application/json（证明 503 是超时触发的，不是别的短路）")
    void fastDownstreamStillReturns200JsonEnvelope() {
        makeDownstreamSlow(0L);

        ResponseEntity<String> resp = post("/admin/v1/login", LOGIN_BODY);

        dump("对照组 /admin/v1/login (fast)", resp);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "同一条路径、同一个 stub，只是不睡 ⇒ 必须正常返回；否则上一例证明不了「是超时触发的」。"
                        + "实际=" + resp.getStatusCode() + " body=[" + resp.getBody() + "]");

        MediaType ct = resp.getHeaders().getContentType();
        assertNotNull(ct, "正常路径必须带 Content-Type（envelope 是 JSON）");
        assertTrue(ct.isCompatibleWith(MediaType.APPLICATION_JSON),
                "对照组证明「text/plain」是<b>分支特有</b>的，不是所有响应都那样。实际=" + ct);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** login 的合法 body（username / password 都是必填 ⇒ 不能缺）。 */
    private static final String LOGIN_BODY = "{\"username\":\"probe-admin\",\"password\":\"pw123456\"}";

    /**
     * 把下游领域层的 {@code login} 打桩成「睡 {@code millis} 毫秒」。
     *
     * <p>⚠️ 为什么必须是<b>「慢服务端」</b>而不是断连：
     * 本类要逼出的是 <b>服务端自己的 3s 预算</b>（{@code observability.http-timeout: 3s}），
     * 与客户端连不连得上无关 ⇒ ⇒ 用「慢」而不是「断」。
     *
     * <p>🔴 <b>D2 的对应关系</b>：B 侧这条桩打在 gRPC stub 上（本仓没有 gRPC 客户端）
     * ⇒ ⇒ 本仓打在<b>领域层</b>上 —— <b>被逼出来的是同一个 3s 预算</b>。
     */
    private void makeDownstreamSlow(long millis) {
        when(adminDomainService.login(anyString(), anyString())).thenAnswer(inv -> {
            if (millis > 0L) {
                Thread.sleep(millis);
            }
            // 与真实领域层同款：登录成功 ⇒ 返回 uid（非 null，避免 api 层拆箱 NPE）
            return Long.valueOf(1L);
        });
    }

    /**
     * 断言「Go 的 {@code net/http.Error} 形状」——**归一化后**比，不逐字节比。
     *
     * <p>归一化 = 去空格 + 小写 ⇒ 只吃掉「Tomcat 重渲染会吃掉分号后空格」这一个已知差异。
     * ⚠️ charset 的<b>有无</b>仍然必须判出来（那才是 DRIFT-ADMINAPI-011 争的那一项）。
     */
    private static void assertGoPlainText(ResponseEntity<String> resp, String why) {
        String ct = resp.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertNotNull(ct, why + " 实际：Content-Type 缺失。头=" + resp.getHeaders());
        String normalized = ct.replace(" ", "").toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("text/plain"),
                why + " 实际 Content-Type=[" + ct + "]");
        assertTrue(normalized.contains("charset=utf-8"),
                why + " 实际 Content-Type=[" + ct + "]"
                        + " ⚠️ charset 必须存在（Go 的 net/http.Error 逐字写死 text/plain; charset=utf-8）。");
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    /** 把真实字节打到 stdout —— 台账要引的就是这份输出（AGENTS.md §3.3：结论要有运行证据）。 */
    private static void dump(String label, ResponseEntity<String> resp) {
        System.out.println("======== admin 真 Tomcat 字节级取证 ========");
        System.out.println("case            = " + label);
        System.out.println("status          = " + resp.getStatusCode());
        System.out.println("content-type    = " + resp.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        System.out.println("nosniff         = " + resp.getHeaders().getFirst("X-Content-Type-Options"));
        System.out.println("content-length  = " + resp.getHeaders().getFirst("Content-Length"));
        System.out.println("body            = [" + (resp.getBody() == null ? "null" : resp.getBody().replace("\n", "\\n")) + "]");
        System.out.println("=========================================");
    }
}
