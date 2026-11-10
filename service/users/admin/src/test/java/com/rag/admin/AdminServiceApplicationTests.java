package com.rag.admin;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosAutoServiceRegistration;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.http.NacosStubConfiguration;
import com.rag.admin.mapper.AdminMapper;
import com.rag.common.security.PasswordUtil;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * <p>admin 域「真启动」契约测试 —— 进程<b>起得来</b>这件事本身就是契约。
 *
 * <p><b>为什么必须存在这个类</b>：D1 把 B 侧的两个进程（admin-api / admin-rpc）合成一个
 * ⇒ 一个进程里同时要起 <b>Tomcat</b> + <b>gRPC server</b> + <b>建表</b> + <b>种子账号</b>
 * + <b>Redis 黑名单</b> + <b>Nacos 注册</b> ⇒ ⇒ <b>任一环节配错，进程就起不来</b>。
 * <p>⇒ 本类断言的<b>不是</b>业务逻辑（那是 {@code AdminDomainServiceRealPgTest} 与
 * {@code AdminHttpContractRealTomcatTest} 的职责），而是<b>启动期契约</b>：
 * <ol>
 *   <li><b>DDL 真建表</b>：4 张表都在（{@code users_user} / {@code users_user_avatar_history}
 *       / {@code admin_admin} / {@code admin_invite}）—— 走的是共享层
 *       {@code SchemaInitializer} 的幂等重放，不是把 SQL 抄一遍。</li>
 *   <li><b>默认 admin 账号的密码来源</b>：必须是<b>硬编码常量 {@code admin}</b>，
 *       <b>不是</b> {@code admin-rpc.system.default-password}（= {@code 123456}）
 *       —— 这是 B 侧 {@code DRIFT-001} 的修法（B 侧 seeder 用包内常量，从不读配置）。
 *       ⚠️ 这条一旦漂移，全新环境的默认管理员口令就变了 ⇒ <b>安全面</b>。</li>
 *   <li><b>gRPC server 与 Tomcat 同进程</b>：devh 的 {@link GrpcServerLifecycle}
 *       {@code isRunning()} 为 true ⇒ D1 的「合并进程」在运行时是真的。</li>
 *   <li><b>Nacos 注册面</b>：自动注册（web 端口）必须<b>没发生</b>，只有域内显式的
 *       gRPC 端口注册发生过 ⇒ 见 {@code #nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered}。</li>
 * </ol>
 *
 * <h2>🔴 这个类是被一次真实缺陷逼出来的</h2>
 * <p>2026-11-02 写 HTTP 契约测试时，{@code application.yml} 里的
 * {@code grpc.client.negotiation-type: plaintext} 让<b>上下文启动直接失败</b>
 * （devh 的 {@code grpc.client.<X>} 是<b>通道名 map</b>，不是属性名）
 * ⇒ 而此前<b>一个 {@code @SpringBootTest} 上下文测试都没有</b> ⇒ 这个缺陷一直藏着。
 * ⇒ ⇒ 本类就是那道防线。
 *
 * <p><b>测试环境摘依赖</b>：Nacos 用 {@link NacosStubConfiguration} 桩（不连网络）·
 * PG / Redis 走 Testcontainers 真容器 · gRPC 走随机端口（不与本机其它域撞端口）。
 *
 * <h2>⚠️ 为什么<b>不</b>关掉 {@code spring.cloud.nacos.discovery.enabled}</h2>
 * <p>关掉它 ⇒ {@code NacosDiscoveryAutoConfiguration#nacosProperties()}（{@code NacosDiscoveryProperties}
 * 的唯一 bean 定义处）整个不生效 ⇒ {@code NacosDiscoveryProperties} bean <b>不存在</b>
 * ⇒ ① {@code NacosServiceRegistryAutoConfiguration#nacosServiceRegistry(...)} 缺参数 ⇒ <b>上下文起不来</b>
 * ⇒ ② 也<b>读不到</b> {@code register-enabled} 的值 ⇒ 无法断言。
 * <p>⇒ 改为<b>保留</b> discovery 开关、用 {@link NacosStubConfiguration} 的桩接管
 * {@code NacosServiceManager}（bean 名错开 + {@code @Primary}）⇒ 既不触网，又能真触发地观察注册行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AdminServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "grpc.server.address=127.0.0.1"
        })
@Testcontainers
class AdminServiceApplicationTests {

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
     * <p>为什么要真实端口：断言「注册的是 <b>gRPC 端口</b>、<b>不是</b> web 端口」需要
     * 两个可区分的端口值 ⇒ 若 gRPC 端口配 0（= 随机），注册出去的端口就是 0 ⇒
     * 「是不是 web 端口」这条判据会失效（0 ≠ 随机端口，恒成立 ⇒ 假绿）。
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
        registry.add("ADMIN_JWT_SECRET", () -> "admin-boot-test-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
    }

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminMapper adminMapper;

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private NacosStubConfiguration.NacosNamingServiceSpy namingServiceSpy;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    /**
     * <b>{@code spring.application.name} 的真值</b>（值来自真实 {@code application.yml}）。
     * <p>用于断言「<b>进程名</b>（{@code admin-service}）与<b>Nacos 注册名</b>（{@code admin-rpc}）刻意分叉」——
     * 两者是<b>不同轴</b>：前者是 Prometheus 的 {@code application} 标签，后者是<b>服务发现名</b>
     * （必须与 B 侧寻址键逐字一致 ⇒ message 域按 {@code discovery:///admin-rpc} 寻址）。
     */
    @org.springframework.beans.factory.annotation.Value("${spring.application.name}")
    private String springApplicationName;

    @Autowired
    private NacosAutoServiceRegistration nacosAutoServiceRegistration;

    @LocalServerPort
    private int webPort;

    @Test
    @DisplayName("启动期：4 张表建出来了（DDL 走共享层 SchemaInitializer 的幂等重放）")
    void schemaInitializerCreatedAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN "
                        + "('users_user','users_user_avatar_history','admin_admin','admin_invite')",
                String.class);
        assertEquals(4, tables.size(), "4 张表都应存在，实际=" + tables);
    }

    @Test
    @DisplayName("启动期：默认 admin 账号被种下，且密码是硬编码的 admin（不是 default-password=123456）")
    void defaultAdminIsSeededWithHardcodedPassword() {
        AdminEntity admin = adminMapper.selectOne(
                new LambdaQueryWrapper<AdminEntity>().eq(AdminEntity::getUsername, "admin"));
        assertNotNull(admin, "默认 admin 账号必须被 AdminSeederBootstrap 种下");
        assertTrue(PasswordUtil.check("admin", admin.getPassword()),
                "默认 admin 的密码必须是硬编码常量 admin（B 侧 DRIFT-001 的修法）");
        assertTrue(!PasswordUtil.check("123456", admin.getPassword()),
                "默认 admin 的密码不得来自 admin-rpc.system.default-password（那是重置普通用户密码用的）");
    }

    @Test
    @DisplayName("启动期：gRPC server 与 Tomcat 同进程且真在跑（D1 合并进程）")
    void grpcServerIsRunningInTheSameProcess() {
        assertNotNull(grpcServerLifecycle, "devh 的 GrpcServerLifecycle bean 必须存在");
        assertTrue(grpcServerLifecycle.isRunning(),
                "gRPC server 必须已启动（D1：一个进程同时起 Tomcat 与 gRPC）");
    }

    /**
     * <p><b>🔴 Nacos 注册面</b>：自动注册必须<b>没发生</b>，只有域内显式的 gRPC 端口注册发生过。
     *
     * <p><b>B 出处</b>：B 侧 {@code service/users/admin/admin-rpc/src/main/resources/application.yml:119}
     * 的 {@code register-enabled: false}（注释原文：「【本模块的注册是手写的】…
     * ⇒ <b>自动注册必须关掉</b>，否则<b>同一服务名下会有两个端口的实例</b>」）。
     *
     * <p><b>消费点</b>（SCA 2025.0.0.0）：{@code NacosAutoServiceRegistration.java:71/83/97}
     * ⇒ {@code if (!nacosDiscoveryProperties.isRegisterEnabled()) { return; }} ⇒ <b>运行期</b>判定。
     *
     * <p><b>本断言为什么不是假绿</b>（三条缺一不可）：
     * <ol>
     *   <li>自动注册的 <b>bean 存在</b>（{@link NacosAutoServiceRegistration} 被注入成功）
     *       ⇒ 证明「链路是活的」，不是靠把整条 discovery 开关关掉来蒙混过关；</li>
     *   <li>{@code NacosDiscoveryProperties#isRegisterEnabled()} 为 false
     *       ⇒ 证明值来自<b>真实 application.yml</b>（本测试<b>没有</b>用 {@code properties=}
     *           覆盖它 ⇒ 只可能是 yml 给的）；</li>
     *   <li>桩 {@link NamingService} 上 <b>只被注册了 1 次</b>，且那次的端口
     *       <b>等于 gRPC 端口</b>、<b>不等于 web 端口</b>
     *       ⇒ ⇒ 域内那次显式注册<b>确实发生了</b>，且<b>注册的是 gRPC 端口</b>。</li>
     * </ol>
     *
     * <h2>🔴 2026-11-10 订正：<b>本条原注释里的「反证」不成立</b>（原文已删）</h2>
     * <p>原文写的是「若 yml 少了 {@code register-enabled} 那一行 ⇒ {@code registerEnabled} 回落默认
     * {@code true} ⇒ 自动注册会<b>再注册一次 web 端口</b> ⇒ 本条断言会红」。
     * <p>⚠️ <b>该反证经字节码实测是错的</b>（SCA 2025.0.0.0）：
     * <pre>
     * NacosRegistration#getServiceId() → NacosDiscoveryProperties#getService()
     *      ⇒ 读的就是 {@code spring.cloud.nacos.discovery.service} 键
     *      ⇒ 而 admin 的 application.yml <b>没有这个键</b> ⇒ null
     *
     * NacosServiceRegistry#register(Registration)：
     *   0: registration.getServiceId()
     *   6: StringUtils.isEmpty(...)  → ifeq 23
     *  12: log.warn("No service to register for nacos client...")
     *  22: return                   ← 服务名为空 ⇒ <b>直接返回，什么都不注册</b>
     * </pre>
     * ⇒ ⇒ <b>今天 admin 域的自动注册是「哑火」的</b>（与服务名为空有关，<b>与
     * {@code register-enabled} 无关</b>）⇒ ⇒ 「只被注册了 1 次」这条断言<b>并不</b>能证明
     * {@code register-enabled} 起了作用 ⇒ ⇒ <b>它今天是一条「弱断言」</b>。
     * <p>⇒ ⇒ <b>这条开关的真判据已移到</b> {@link com.rag.admin.wiring.AdminNacosRealRegistrationTest}：
     * 它对着<b>真 Nacos</b>，并<b>显式注入</b> {@code spring.cloud.nacos.discovery.service=admin-rpc}
     * 把自动注册链路「武装」起来 ⇒ ⇒ 此时唯一的拦截者就是 {@code register-enabled: false}
     * ⇒ ⇒ 删掉那一行 ⇒ <b>真 Nacos 上会出现第 2 个实例（web 端口）</b> ⇒ 判据变红（有牙齿）。
     * <p>📌 本条断言<b>仍然保留</b>：它验的是「域内那次显式注册<b>只发生一次</b>、且端口是 gRPC 端口」
     * —— 这件事本身有效（不是假绿），只是<b>不能</b>用它证明 {@code register-enabled}。
     */
    @Test
    @DisplayName("启动期：Nacos 自动注册被关掉，注册到 Nacos 的只有 gRPC 端口（web 端口从未被注册）")
    void nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered() throws NacosException {
        // ① 自动注册链路是「活」的
        assertNotNull(nacosAutoServiceRegistration,
                "NacosAutoServiceRegistration bean 必须存在（否则本断言会因「整条链被关掉」而假绿）");

        // ② 值来自真实 application.yml（本测试未覆盖该键）
        assertFalse(nacosDiscoveryProperties.isRegisterEnabled(),
                "spring.cloud.nacos.discovery.register-enabled 必须是 false（B 侧 admin-rpc yml:119）");

        // ③ 桩上只该有 1 次注册，且是 gRPC 端口
        ArgumentCaptor<Instance> captor = ArgumentCaptor.forClass(Instance.class);
        NamingService namingService = namingServiceSpy.namingService();
        verify(namingService, times(1)).registerInstance(anyString(), anyString(), captor.capture());

        Instance registered = captor.getValue();
        assertEquals(GRPC_PORT, registered.getPort(),
                "注册到 Nacos 的必须是 gRPC 端口（B 侧 admin-rpc 用 GrpcServerProperties.getPort()）");
        assertNotEquals(webPort, registered.getPort(),
                "web 端口绝不能被注册 —— 否则同名服务下会出现两个端口的实例，gRPC 链路断（业务码塌 1015）");

        // 🔴 ④ L1：注册名必须逐字等于 B 侧的 `admin-rpc`（2026-11-02 修正项）
        //    B 出处：admin-rpc/.../bootstrap/NacosRegistration.java:88 ⇒ String serviceName = "admin-rpc";
        //    ⚠️ 本条曾经是 `admin-service`（理由「D1 合并后的进程身份」）⇒ 而 message 域的 gRPC client
        //    按 B 侧逐字寻址 `discovery:///admin-rpc` ⇒ 注册名不叫 admin-rpc ⇒ **寻址落空** ⇒ 链路断。
        //    ⇒ 用断言把它钉死，防止再漂回去。
        assertEquals("admin-rpc", registered.getServiceName(),
                "Nacos 注册名必须是 B 侧逐字值 `admin-rpc`（message 域按 `discovery:///admin-rpc` 寻址；"
                        + "兄弟域 user 同款：app name=user-service ＋ 注册名=user-rpc）");
        // ⚠️ 与 spring.application.name 刻意不同：后者是**进程名**（Prometheus 的 application 标签），
        //    前者是**服务发现名**（必须与 B 侧寻址键逐字一致）⇒ 两者分叉是设计，不是漂移。
        assertEquals("admin-service", springApplicationName,
                "spring.application.name 走本仓 artifactId 惯例（admin-service），"
                        + "与 Nacos 注册名（admin-rpc）刻意不同 —— 两者是不同轴");
    }
}
