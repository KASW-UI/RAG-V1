package com.rag.admin.wiring;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosAutoServiceRegistration;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.admin.AdminServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>V-3 闭合守卫：对着「真 Nacos」逐字断言 admin 域的注册结果</b>。
 *
 * <h2>本类补的是哪一条缺口</h2>
 * <p>域内既有的 Nacos 断言（{@code AdminServiceApplicationTests}）全部走
 * {@code NacosStubConfiguration} 桩 ⇒ <b>不触网</b> ⇒ 它验的是「<b>本进程调了桩几次</b>、参数是什么」，
 * <b>不是</b>「Nacos 上真的有了这个实例」。
 * <p>⇒ ⇒ 台账 §7.3 的 V-3「对真 Nacos 的注册结果未逐字断言」此前一直是 🟡 NOT_VERIFIED ⇒ <b>本类闭合它</b>。
 *
 * <h2>🔴 断言必须读「服务端注册表」，不能只读客户端视图</h2>
 * <p>本类第一版用 {@code NamingService#selectInstances(...)} 取实例列表 ⇒ <b>变异检验没能把它打红</b>
 * （把 yml 的 {@code register-enabled} 改成 {@code true} 后，SCA 的自动注册<b>明明发生了</b>
 * —— 日志里能看到第二条 {@code nacos registry, DEFAULT_GROUP admin-rpc <ip>:<webPort>} ——
 * 但客户端查询只看见 <b>1 个</b>实例 ⇒ ⇒ <b>那条判据没有牙齿</b>）。
 * <p>⇒ ⇒ 本版改为<b>直接读 Nacos 服务端的 open API</b>（{@code /nacos/v1/ns/instance/list}）
 * ⇒ ⇒ 拿的是<b>服务端的注册表</b>（每个实例一条记录）⇒ ⇒ 变异检验可以精确变红。
 * <p>📌 客户端视图与服务端视图的差异**本身就是一条要记住的教训**：客户端查询会受
 * 健康状态 / 本地缓存 / 订阅快照影响 ⇒ ⇒ <b>「Nacos 上到底有几个实例」只能问服务端</b>。
 *
 * <h2>🔴 本类为什么必须显式注入 {@code spring.cloud.nacos.discovery.service=admin-rpc}</h2>
 * <p>这是本类<b>最关键的一处设计</b>，不注入的话<b>断言没有牙齿（假绿）</b>：
 * <ol>
 *   <li>SCA 的自动注册取的服务名是
 *       {@code NacosRegistration#getServiceId()} → {@code NacosDiscoveryProperties#getService()}
 *       ⇒ 即 {@code spring.cloud.nacos.discovery.service} 键；
 *       <b>而 admin 域的 {@code application.yml} 并没有这个键</b> ⇒ 取到 {@code null}。</li>
 *   <li>{@code NacosServiceRegistry#register(Registration)} 的<b>消费点</b>（SCA 2025.0.0.0 字节码实测）：
 *       <pre>
 *   0: registration.getServiceId()
 *   6: StringUtils.isEmpty(...)   → ifeq 23
 *  12: log.warn("No service to register for nacos client...")
 *  22: return                    ← 服务名为空 ⇒ 直接返回，**什么都不注册**
 *       </pre>
 *       ⇒ ⇒ <b>不注入的话，自动注册压根不会发生</b>（与服务名有关，<b>与 {@code register-enabled} 无关</b>）
 *       ⇒ ⇒ 任何「web 端口没被注册」的断言都<b>恒绿 = 假绿</b>。</li>
 *   <li>⇒ ⇒ 本类显式注入 {@code service=admin-rpc}（<b>= 域内代码在 yml 缺省时用的默认值</b>，
 *       <b>生产语义零变化</b>）⇒ 把自动注册链路「武装」起来
 *       ⇒ ⇒ 此时唯一的拦截者就是 yml 里的 {@code register-enabled: false}
 *       ⇒ ⇒ <b>若有人删掉那一行</b> ⇒ 服务端注册表里会多出 <b>web 端口</b>那条实例
 *       ⇒ ⇒ <b>本类「恰好 1 个实例」断言立刻变红</b>（实测见台账 §7.3.1 的变异记录）。</li>
 * </ol>
 *
 * <h2>「web 端口」是怎么被自动注册取到的（字节码实测）</h2>
 * <pre>
 * NacosRegistration#构造器 → 存 context
 * NacosRegistration#getPort() → ManagementServerPortUtils.getPort(beanFactory)   ← **web 端口**
 *                                → nacosDiscoveryProperties.setPort(webPort)
 * </pre>
 * ⇒ ⇒ 这正是 B 侧 admin-rpc yml:119「<b>【本模块的注册是手写的】… ⇒ 自动注册必须关掉</b>」要防的那件事。
 *
 * <h2>🔴 端口偏移：为什么不能用随机端口就完事</h2>
 * <p>nacos-client <b>按「http 口 + 偏移」推 gRPC 口</b>，偏移默认 1000：
 * <pre>
 * GrpcClient:  RpcClient$ServerInfo.getServerPort() + rpcPortOffset()   → gRPC 口
 * GrpcSdkClient#rpcPortOffset():  System.getProperty("nacos.server.grpc.port.offset", 默认 1000)
 * </pre>
 * ⇒ ⇒ Testcontainers 的随机映射端口<b>不保证</b>两端口相差 1000 ⇒ ⇒ 本类显式把
 * {@code nacos.server.grpc.port.offset} 设成「映射后 gRPC 口 − 映射后 http 口」
 * ⇒ ⇒ <b>随机端口可用</b>（不占用宿主机固定端口 ⇒ 不会与本机已跑的 Nacos 撞车）。
 *
 * <h2>镜像版本口径</h2>
 * <p>本机可用镜像：{@code nacos/nacos-server:v2.5.1}（3.x 镜像拉取失败：blob 下载 EOF）。
 * ⇒ <b>已实测 nacos-client 3.0.3 ↔ server 2.5.1 兼容</b>（注册 + 回查均成功）⇒ 本类可用。
 *
 * <h2>B 出处（判定基准）</h2>
 * <ul>
 *   <li>B 出处：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/NacosRegistration.java:88}
 *       ⇒ {@code String serviceName = "admin-rpc";}（注册名 + gRPC 端口 + group 读配置 + {@code @PreDestroy} 反注册）；</li>
 *   <li>B 出处：{@code service/users/admin/admin-rpc/src/main/resources/application.yml:119}
 *       ⇒ {@code register-enabled: false}（逐字照抄 ⇒ 见本仓 admin 的 {@code application.yml} Nacos 段）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AdminServiceApplication.class},
        properties = {
                "grpc.server.address=127.0.0.1",
                // 🔴 见类头「为什么必须显式注入」：把 SCA 自动注册链路「武装」起来，
                //    让 `register-enabled: false` 这条 yml 开关**有牙齿**（否则断言恒绿 = 假绿）。
                //    取值 = 域内代码在 yml 缺省时的默认值 = B 侧逐字值 ⇒ 生产语义零变化。
                "spring.cloud.nacos.discovery.service=admin-rpc"
        })
@Testcontainers
class AdminNacosRealRegistrationTest {

    /** B 侧逐字注册名（{@code NacosRegistration.java:88}）。 */
    private static final String SERVICE_NAME = "admin-rpc";

    /** 客户端推 gRPC 口用的系统属性名（nacos-client 3.0.3 字节码实测）。 */
    private static final String GRPC_PORT_OFFSET_PROPERTY = "nacos.server.grpc.port.offset";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nacos_probe_db")
            .withUsername("admin")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * <p><b>真 Nacos</b>（standalone 模式 + 关闭鉴权）。
     *
     * <p>⚠️ <b>本容器用「静态块手动 start」而不是 {@code @Container}</b> —— 因为
     * {@code nacos.server.grpc.port.offset} 必须在<b>任何客户端被创建之前</b>设好
     * ⇒ ⇒ 静态初始化是最早的确定时机（{@code @Container} 与 {@code @DynamicPropertySource}
     * 的相对顺序由扩展注册序决定，不宜依赖）。
     */
    static final GenericContainer<?> NACOS = new GenericContainer<>(
            DockerImageName.parse("nacos/nacos-server:v2.5.1"))
            .withEnv("MODE", "standalone")
            .withEnv("NACOS_AUTH_ENABLE", "false")
            // ⚠️ 堆压到 384m：Docker Desktop 的 VM 内存上限只有 ~7.65 GiB，
            //    而**多个 lane 会同时跑 kafka / nacos / pg / redis 容器**
            //    ⇒ 实测一次本容器被 Docker 以 **exit 255** 杀掉（同一时刻别的 lane 的容器也一起 255）
            //    ⇒ ⇒ 本容器尽量少占内存，给同机其它容器留余量。
            .withEnv("JVM_XMS", "128m")
            .withEnv("JVM_XMX", "384m")
            .withEnv("JVM_XMN", "96m")
            .withExposedPorts(8848, 9848)
            // 🔴 为什么用「等日志」而不是「等 HTTP」：Nacos 启动途中 Tomcat 已监听但上下文未就绪
            //    ⇒ 探活请求会被 **reset**（实测 SocketException: Connection reset）
            //    ⇒ HttpWaitStrategy 在 1.21.4 里把这类 IOException 直接抛出 ⇒ 容器启动被判失败
            //    ⇒ 改等 Nacos 自己宣告启动完成的那行日志（2.x / 3.x 都有这句），再补一次容错探活。
            .waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1)
                    // ⚠️ 8 分钟（不是 4）：实测本机**空闲**时 Nacos 起来约 90–150s，
                    //    但**同机有别的 lane 在跑 kafka/pg/redis 容器**时，Spring 上下文初始化
                    //    会从 71s 拖到 4 分钟以上（实测一次「等日志超时」）⇒ ⇒ 超时窗口按最坏情况给。
                    .withStartupTimeout(Duration.ofMinutes(8)));

    /** 客户端推 gRPC 口用的偏移值（映射后 gRPC 口 − 映射后 http 口）。 */
    private static final int GRPC_PORT_OFFSET;

    /** 覆盖前的系统属性值（{@code @AfterAll} 还原 ⇒ 不污染同 JVM 的其它用例）。 */
    private static final String PREVIOUS_OFFSET;

    /**
     * <p>gRPC server 的端口 —— 取一个<b>真实空闲端口</b>（不是 0）。
     *
     * <p>为什么不能是 0：断言「注册的是 <b>gRPC 端口</b>、<b>不是 web 端口</b>」需要两个可区分的端口值
     * ⇒ 若 gRPC 端口是 0（= 随机），注册出去的端口就是 0 ⇒ 「是不是 web 端口」这条判据会失效
     * ⇒ ⇒ <b>假绿</b>（与 {@code AdminServiceApplicationTests} 的同一处理由）。
     */
    private static final int GRPC_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            GRPC_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
        NACOS.start();
        int httpPort = NACOS.getMappedPort(8848);
        int grpcPort = NACOS.getMappedPort(9848);
        GRPC_PORT_OFFSET = grpcPort - httpPort;
        PREVIOUS_OFFSET = System.getProperty(GRPC_PORT_OFFSET_PROPERTY);
        System.setProperty(GRPC_PORT_OFFSET_PROPERTY, String.valueOf(GRPC_PORT_OFFSET));
        awaitNacosReady(httpPort);
    }

    /**
     * <p>容错探活 —— 日志说「启动完成」之后，HTTP 口可能还要一小会儿才稳定
     * ⇒ 期间连接被 reset 属正常 ⇒ <b>必须吞掉 IOException 继续重试</b>（不能让它冒泡成启动失败）。
     *
     * @param httpPort 映射后的 http 端口
     */
    private static void awaitNacosReady(int httpPort) {
        String url = "http://" + NACOS.getHost() + ":" + httpPort + "/nacos/v1/console/health/readiness";
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                int code = connection.getResponseCode();
                connection.disconnect();
                if (code == 200) {
                    return;
                }
            } catch (IOException e) {
                last = e;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Nacos 就绪时被中断", e);
            }
        }
        throw new IllegalStateException("Nacos 在 90s 内没就绪（最后异常：" + last + "）", last);
    }

    /**
     * <p><b>独立的验证客户端</b> —— 与应用内那个 {@code NacosServiceManager} <b>不是同一个实例</b>
     * ⇒ ⇒ 它问的是<b>服务端</b>的注册表状态，而不是客户端的本地内存视图。
     */
    private static NamingService verificationClient;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    /**
     * <p>SCA 的<b>自动注册</b> bean —— 用来做<b>运行期</b>判定（见 {@code #webPortIsNeverRegistered}）。
     */
    @Autowired
    private NacosAutoServiceRegistration nacosAutoServiceRegistration;

    @LocalServerPort
    private int webPort;

    @BeforeAll
    static void createVerificationClient() throws Exception {
        Properties props = new Properties();
        props.put("serverAddr", NACOS.getHost() + ":" + NACOS.getMappedPort(8848));
        // ⚠️ namespace 必须与<b>应用侧</b>一致（两侧同一个命名空间才谈得上「同一个服务」）
        //    ⇒ 按<b>应用 yml 的同一套解析规则</b>取：${NACOS_NAMESPACE:public}
        props.put("namespace", System.getenv().getOrDefault("NACOS_NAMESPACE", "public"));
        verificationClient = NacosFactory.createNamingService(props);
    }

    @AfterAll
    static void tearDown() {
        if (PREVIOUS_OFFSET == null) {
            System.clearProperty(GRPC_PORT_OFFSET_PROPERTY);
        } else {
            System.setProperty(GRPC_PORT_OFFSET_PROPERTY, PREVIOUS_OFFSET);
        }
        NACOS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.nacos.discovery.server-addr",
                () -> NACOS.getHost() + ":" + NACOS.getMappedPort(8848));
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ADMIN_JWT_SECRET", () -> "admin-nacos-probe-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
    }

    /**
     * <p>取应用真实生效的 group（来自 <b>真实 application.yml</b>，本测试<b>没有</b>覆盖这个键）。
     */
    private String group() {
        return nacosDiscoveryProperties.getGroup();
    }

    /**
     * <p>🔴 <b>服务端注册表</b>（权威视图）—— 走 Nacos 的 open API，
     * <b>不经客户端缓存</b> ⇒ ⇒ 「Nacos 上到底有几个实例」只能这样问。
     *
     * @param serviceName 服务名
     * @param groupName   组名
     * @return 服务端注册表里的实例条目（每条含 ip / port / healthy / serviceName）
     */
    private List<Map<String, Object>> serverSideInstances(String serviceName, String groupName)
            throws IOException {
        List<Map<String, Object>> hosts = openApiHosts(serviceName, groupName, null);
        if (hosts.isEmpty()) {
            // ⚠️ 命名空间写法：public 命名空间在服务端等价于「默认命名空间」（id 为空串）
            //    ⇒ 两种写法都试一次（服务端只认其中一种），避免把「写法不对」误判成「没注册」
            hosts = openApiHosts(serviceName, groupName, "public");
        }
        return hosts;
    }

    private List<Map<String, Object>> openApiHosts(String serviceName, String groupName, String namespaceId)
            throws IOException {
        StringBuilder url = new StringBuilder("http://" + NACOS.getHost() + ":" + NACOS.getMappedPort(8848)
                + "/nacos/v1/ns/instance/list?serviceName=" + serviceName
                + "&groupName=" + groupName
                + "&healthyOnly=false");
        if (namespaceId != null) {
            url.append("&namespaceId=").append(namespaceId);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        String body;
        try (InputStream in = connection.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
        JsonNode hosts = new ObjectMapper().readTree(body).path("hosts");
        List<Map<String, Object>> result = new ArrayList<>();
        hosts.forEach(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> item.put(entry.getKey(), plain(entry.getValue())));
            result.add(item);
        });
        return result;
    }

    /**
     * <p>把 Jackson 的节点值转成<b>朴素 Java 类型</b>（数字 ⇒ {@link Number} · 布尔 ⇒ {@link Boolean} · 其余 ⇒ 文本）。
     *
     * <p>⚠️ <b>为什么必须转</b>：Jackson 2 的 {@code IntNode} <b>不是</b> {@link Number} 的子类
     * （它 extends {@code JsonNode}）⇒ 直接 {@code (Number) node} 会抛 {@code ClassCastException}
     * ⇒ ⇒ 本类第一版就是这么写错的（变异跑时炸在类型转换上，而不是炸在断言上）。
     */
    private static Object plain(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    /**
     * <p>注册是<b>最终一致</b>的（服务端要落库 + 建索引）⇒ 允许一个很短的等待窗口；
     * <b>但一旦出现，端口 / 数量 / 服务名的断言一律不放水</b>。
     *
     * <p>🔴 <b>为什么查到非空之后还要「静置」再读一次</b>：
     * <b>两次注册不是同时发生的</b>（实测日志：SCA 自动注册先、域内手动注册后，间隔 ~0.5s）
     * ⇒ ⇒ 若「第一次读到非空就断言」，就可能只看到<b>先落地的那一个</b>
     * ⇒ ⇒ 「恰好 1 个实例」这条判据会有<b>竞态窗口</b>（可能假绿）
     * ⇒ ⇒ 这里在读到非空之后**静置 2s 再读一次**，用第二次的读数做断言。
     */
    private List<Map<String, Object>> awaitServerInstances(String serviceName, String groupName)
            throws IOException, InterruptedException {
        List<Map<String, Object>> instances = List.of();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            instances = serverSideInstances(serviceName, groupName);
            if (!instances.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        if (!instances.isEmpty()) {
            Thread.sleep(2000);
            instances = serverSideInstances(serviceName, groupName);
        }
        return instances;
    }

    @Test
    @DisplayName("真 Nacos：服务名 == admin-rpc（B 侧逐字）· group == 配置值 · 端口 == gRPC 端口（不是 web 端口）· IP 非 loopback")
    void realNacosHoldsExactlyOneGrpcInstance() throws Exception {
        String group = group();

        // ① 生效的 group 必须来自真实 yml（全仓统一决策：public / DEFAULT_GROUP）
        assertEquals("DEFAULT_GROUP", group,
                "生效的 group 必须来自真实 application.yml（2026-11-09 全仓统一为 DEFAULT_GROUP；"
                        + "B 侧本域写的是 sea-rtw / USER_GROUP ⇒ 偏离已登记）");

        // ② 服务端注册表上确实有这个服务，且恰好 1 个实例
        List<Map<String, Object>> instances = awaitServerInstances(SERVICE_NAME, group);
        assertEquals(1, instances.size(),
                "Nacos 服务端注册表上 " + group + "/" + SERVICE_NAME + " 必须恰好 1 个实例；"
                        + "多于 1 个 ⇒ 自动注册把 web 端口也注册进来了（register-enabled 没生效）；"
                        + "实际=" + instances);

        Map<String, Object> registered = instances.get(0);

        // ③ 注册的是 gRPC 端口
        assertEquals(GRPC_PORT, ((Number) registered.get("port")).intValue(),
                "注册到 Nacos 的必须是 gRPC 端口（B 侧 admin-rpc 用 GrpcServerProperties.getPort()）");
        assertNotEquals(webPort, ((Number) registered.get("port")).intValue(),
                "web 端口绝不能被注册 —— 否则消费方按名字寻址会连到错端口，gRPC 链路断（业务码塌 1015）");

        // ④ 服务名必须逐字等于 B 侧的 admin-rpc（message 域按 discovery:///admin-rpc 寻址）
        //    ⚠️ **Nacos 服务端返回的 instance.serviceName 是「组限定名」**（`group@@service`）——
        //    实测返回 `DEFAULT_GROUP@@admin-rpc` ⇒ ⇒ **不能**直接拿它跟 `admin-rpc` 等值比
        //    （本轮首跑就是在这里红的：expected <admin-rpc> but was <DEFAULT_GROUP@@admin-rpc>）
        //    ⇒ 正确的比法是「组限定名 == 配置 group ++ "@@" ++ 注册名」。
        assertEquals(group + "@@" + SERVICE_NAME, registered.get("serviceName"),
                "注册名必须是 B 侧逐字值 admin-rpc，且落在配置的 group 下"
                        + "（Nacos 返回的是组限定名 group@@service）");

        // ⑤ IP 必须是非 loopback（DRIFT-NEW-012：InetAddress.getLocalHost() 在 macOS 上常返回 127.0.0.1）
        assertNotEquals("127.0.0.1", registered.get("ip"),
                "注册 IP 必须是非 loopback 的 IPv4（容器/宿主都访问不到 loopback）");

        // ⑥ 阴性对照：换一个 group 必须查不到 ⇒ 证明上面查到的确实是「这个 group 下的那个服务」
        assertTrue(serverSideInstances(SERVICE_NAME, "WRONG_GROUP_FOR_PROBE").isEmpty(),
                "换一个 group 查不到实例 ⇒ 说明 ② 的命中确实来自 group 判定");
    }

    @Test
    @DisplayName("真 Nacos：web 端口从未被注册（register-enabled=false 在运行期真的挡住了自动注册）")
    void webPortIsNeverRegistered() throws Exception {
        String group = group();

        // 🔴 **主判据（运行期判定 · 变异检验能变红的那条）**：
        //    SCA 的 `AbstractAutoServiceRegistration#start()` 里
        //    `if (!isEnabled()) { return; }` ⇒ ⇒ `register-enabled: false` ⇒ **start() 直接返回**
        //    ⇒ ⇒ `running` 这个 AtomicBoolean **恒为 false** ⇒ ⇒ `isRunning()` == false
        //    ⇒ ⇒ 这是「自动注册**没有发生**」的**运行期**证据（不是「配置项写着 false」的静态读数）
        //    ⇒ ⇒ 变异（把 yml 改成 true）⇒ start() 会真的去注册 ⇒ `isRunning()` == true ⇒ **本断言变红**
        //    ⚠️ **为什么不能只靠「服务端有几个实例」**：实测同一 IP 下不同端口的两个实例，
        //    服务端注册表**未必**两条都在（⇒ 该判据在变异态下可能假绿）⇒ ⇒ 机制判据 + 结果判据**两条都要**。
        assertFalse(nacosAutoServiceRegistration.isRunning(),
                "SCA 自动注册必须没跑起来：register-enabled=false ⇒ start() 直接 return ⇒ running 恒为 false"
                        + "（若本条为 true ⇒ 说明 yml 的 register-enabled 没生效 ⇒ web 端口会被注册上去）");

        // 同名服务下有且只有 gRPC 端口那一个实例 ⇒ web 端口没有混进来
        List<Map<String, Object>> instances = awaitServerInstances(SERVICE_NAME, group);
        assertEquals(1, instances.size(),
                "同名服务下出现多于 1 个实例 ⇒ 自动注册（web 端口）发生了 ⇒ register-enabled=false 没生效");
        assertEquals(GRPC_PORT, ((Number) instances.get(0).get("port")).intValue(),
                "唯一那个实例必须是 gRPC 端口的那个");

        // 且健康 ⇒ 注册不是「挂着但不可用」
        assertEquals(Boolean.TRUE, instances.get(0).get("healthy"),
                "注册上去的实例必须是 healthy（否则消费方按名字寻址会拿到不可用实例）");

        // 阴性对照：应用名（spring.application.name = admin-service）下必须 0 实例
        //  ⚠️ 注意：本次测试注入了 discovery.service=admin-rpc ⇒ SCA 自动注册（若发生）会注册到
        //    admin-rpc 名下，而**不是** admin-service ⇒ 这条断言是**补充**而非主判据；
        //    主判据是上面的「恰好 1 个实例 + 端口 == gRPC 端口」。
        assertTrue(serverSideInstances("admin-service", group).isEmpty(),
                "spring.application.name 名下不得有实例（应用只应注册 admin-rpc 这一个服务名）");
    }
}
