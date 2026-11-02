package com.rag.admin.grpc;

import com.rag.admin.AdminServiceApplication;
import com.rag.admin.http.NacosStubConfiguration;
import com.rag.proto.admin.AdminServiceGrpc;
import com.rag.proto.admin.LogoutReq;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.rag.admin.grpc.AdminRpcLogoutClientVisibleGrpcStatusTest.GracefulDisconnectProxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 **admin-rpc 的 B 组反例：断连策略 = {@code DEFAULT} ⇒ 客户端看到 1015（DEADLINE_EXCEEDED）而不是 INTERNAL**
 * （2026-10-28 · G1 lane）
 *
 * <h2>B 出处（本类的行为基线）</h2>
 * <p><b>绝对路径</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcLogoutClientVisibleGrpcStatusDefaultBehaviorTest.java}
 * <pre>
 *   B: RAG  service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcLogoutClientVisibleGrpcStatusDefaultBehaviorTest.java
 *      :120-128  唯一变量：断连策略 = DEFAULT 的 test 侧 customizer（遮蔽共享层那份 REJECT_COMMANDS）
 *      :130-178  B 组主用例（前置断言「遮蔽真的发生了」→ 对照组 → 制造断连 → 实验组）
 *      :180-186  端口挑选（原为 support/RealGrpcPort）
 * </pre>
 *
 * <p>🔴 <b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13）</b> ——
 * 客户端可见的 gRPC code / description / 耗时区间三条断言<b>一字未改</b>。
 *
 * <h2>为什么要有 B 组</h2>
 * <p>{@code AdminRpcLogoutClientVisibleGrpcStatusTest}（A 组）证明了「admin-rpc 客户端看到的是
 * {@code INTERNAL}「注销失败,Redis写入异常」，与 Go 腿逐字同形」。
 * <p><b>但 A 组单独存在时证明不了「是那条机制决定的」</b> ⇒ ⇒ 本类提供<b>同场 B 组</b>：
 * <b>唯一变量 = 断连策略</b>（{@code REJECT_COMMANDS} → {@code DEFAULT}）⇒ ⇒ 归因强度才成立。
 *
 * <h2>怎么做到「唯一变量」</h2>
 * <p>共享层 {@code com.rag.common.redis.ClientAutoConfiguration} 的 bean 带 {@code @ConditionalOnMissingBean}
 * ⇒ ⇒ <b>域侧一旦已定义同类型的 bean，共享层那份根本不会创建</b>（是「遮蔽」不是「叠加」）
 * ⇒ ⇒ 本类用一个 <b>test 侧</b>的 {@code @TestConfiguration} 提供一份值设成 {@code DEFAULT} 的 customizer
 * ⇒ ⇒ 其余全部不变（同款代理 / 同款故障形态 / 同款测点 / 同款 server 与客户端）。
 *
 * <h2>⚠️ 这个 B 组不会踩 G4 的源扫守门</h2>
 * <p>该守门扫的是各模块的 <b>{@code src/main/java}</b> 那一层 ⇒ ⇒ <b>{@code src/test/java} 不在扫描面</b>
 * ⇒ ⇒ 守门在我域仍绿。
 *
 * <h2>⚠️ 本类的「变红能力」是内建的</h2>
 * <p>把下面那个 bean 的 {@code @Bean} 摘掉 ⇒ ⇒ 共享层那份 {@code REJECT_COMMANDS} 接管
 * ⇒ ⇒ 客户端会看到 INTERNAL ⇒ ⇒ <b>本类必红</b> ⇒ ⇒ 不需要额外的变异注入就能证明它不是恒真。
 *
 * <h2>⚠️ 移植说明（C 侧 ↔ B 侧的差异，逐条登记）</h2>
 * <ol>
 *   <li><b>包名 / pb 包名</b>（D7）：{@code com.rag.users.admin.rpc} → {@code com.rag.admin.grpc}；
 *       {@code com.rag.users.admin.proto.*} → {@code com.rag.proto.admin.*}。</li>
 *   <li><b>启动类</b>：B 侧 {@code AdminRpcApplication} → C 侧 {@code AdminServiceApplication}（D1）
 *       ＋ 本模块既有的顶层 {@link NacosStubConfiguration}（<b>不触网</b>）。</li>
 *   <li><b>🔴 {@code LettuceClientOptionsBuilderCustomizer} 的包名变了</b>（<b>Boot 大版本差异</b>）：
 *       B 侧（Boot 4）= {@code org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer}
 *       ⇒ C 侧（Boot 3.5.11）= {@code org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer}
 *       （<b>实测确认</b>：{@code spring-boot-autoconfigure-3.5.11.jar} 里就是
 *       {@code org/springframework/boot/autoconfigure/data/redis/LettuceClientOptionsBuilderCustomizer.class}）
 *       ⇒ <b>仅包名变化，类型与语义逐条未动</b>。</li>
 *   <li><b>共享层那一层的类名</b>：B 侧 {@code GoStyleRedisClientAutoConfiguration}
 *       → C 侧 {@code com.rag.common.redis.ClientAutoConfiguration}（<b>只去了 Redis 前缀</b>，
 *       见该类 javadoc「落点差异」段）⇒ <b>遮蔽机制逐字同款</b>
 *       （{@code @ConditionalOnMissingBean} + 域侧副本优先）。</li>
 *   <li><b>⛔ 删掉 {@code GoStyleHostPortPinning.pinContainerPort}</b> ——
 *       <b>C 侧不存在该辅助类</b> ⇒ 容器用 Testcontainers 的随机映射端口。</li>
 *   <li><b>{@code support/RealGrpcPort}</b> —— <b>C 侧不存在该辅助类</b>
 *       ⇒ 本移植按 admin 域既有范式（{@code AdminServiceApplicationTests}）用
 *       {@code new ServerSocket(0)} 取真实空闲端口；
 *       <b>B 侧原注记（macOS 临时段与 Docker 发布口同段 ⇒ 静默假红的风险）如实留档</b>
 *       在 {@link #pickFreePort()} 的注释里。</li>
 *   <li><b>{@code GracefulDisconnectProxy}</b> —— B 侧它住在
 *       {@code AdminRpcRedisDisconnectedFastFailTest}（<b>不在本批移植范围内</b>）
 *       ⇒ 本移植把它<b>逐字照搬</b>进 {@code AdminRpcLogoutClientVisibleGrpcStatusTest}
 *       ⇒ ⇒ A 组与 B 组<b>仍然共用同一份实现</b>
 *       （B 侧「同一次 A/B 对照内部必须是同一份实现」这条纪律不破）。</li>
 *   <li><b>{@code ADMIN_JWT_SECRET}</b>：C 侧 {@code admin-api.admin-auth.access-secret} /
 *       {@code admin-rpc.admin-auth.access-secret} 是<b>无默认值的必填项</b>
 *       （B 侧 P0 #8 修法）⇒ 上下文启动必须注入 ⇒ 用 {@code @DynamicPropertySource} 注入
 *       （同本模块 {@code AdminServiceApplicationTests}）。</li>
 * </ol>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                AdminServiceApplication.class,
                NacosStubConfiguration.class,
                AdminRpcLogoutClientVisibleGrpcStatusDefaultBehaviorTest.DefaultDisconnectedBehaviorConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN"
        })
@Testcontainers
class AdminRpcLogoutClientVisibleGrpcStatusDefaultBehaviorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("admin_rpc_it")
                    .withUsername("rag")
                    .withPassword("rag");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    /** 与本模块的 A 组<b>共用同一份</b>代理实现 ⇒ ⇒ 故障形态必然同款。 */
    static final GracefulDisconnectProxy PROXY = GracefulDisconnectProxy.create(() -> {
        try {
            return REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Redis 容器还没起来", e);
        }
    });

    static final int GRPC_PORT = pickFreePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("grpc.server.port", () -> String.valueOf(GRPC_PORT));
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", () -> "127.0.0.1");
        r.add("spring.data.redis.port", PROXY::port);
        r.add("spring.data.redis.password", () -> "");
        r.add("ADMIN_JWT_SECRET", () -> "admin-logout-b-group-test-secret-0123456789");
    }

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Autowired
    private ApplicationContext applicationContext;

    /** <b>唯一变量</b>：断连策略 = {@code DEFAULT}（遮蔽共享层那份 {@code REJECT_COMMANDS}）。 */
    @TestConfiguration
    static class DefaultDisconnectedBehaviorConfig {

        @Bean
        LettuceClientOptionsBuilderCustomizer defaultDisconnectedBehaviorCustomizer() {
            return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.DEFAULT);
        }
    }

    @Test
    @DisplayName("🔴 admin-rpc B 组：断连策略=DEFAULT ⇒ 命令被排队 ⇒ 2s rpc 预算先到 ⇒ 客户端看到 DEADLINE_EXCEEDED(1015)")
    void logoutClientSeesDeadlineExceededWhenCommandsAreQueued() throws Exception {
        // ── ① 前置：确认「遮蔽真的发生了」──
        Map<String, LettuceClientOptionsBuilderCustomizer> customizers =
                applicationContext.getBeansOfType(LettuceClientOptionsBuilderCustomizer.class);
        assertEquals(1, customizers.size(),
                "容器里必须恰好一份 LettuceClientOptionsBuilderCustomizer ⇒ 实测 " + customizers.keySet());
        ClientOptions options = connectionFactory.getClientConfiguration().getClientOptions().orElse(null);
        assertEquals(ClientOptions.DisconnectedBehavior.DEFAULT,
                options == null ? null : options.getDisconnectedBehavior(),
                "断连行为必须是 DEFAULT（这才是本组的唯一变量）");

        ManagedChannel channel = ManagedChannelBuilder
                .forTarget("dns:///127.0.0.1:" + GRPC_PORT)
                .usePlaintext()
                .build();
        try {
            AdminServiceGrpc.AdminServiceBlockingStub stub = AdminServiceGrpc.newBlockingStub(channel);

            // ── ② 对照组：先建立连接并成功写一次 ──
            //    ⚠️ 必需：漏掉 ⇒ Lettuce 从未建连 ⇒ 断连后第一条命令是「连接被拒」（~15ms 快失败）
            //    ⇒ ⇒ 测的是「连不上」而不是「连上后被断开」⇒ ⇒ `disconnectedBehavior` 根本不起作用。
            stub.logout(LogoutReq.newBuilder().setToken("g1-probe-b-reachable").build());

            // ── ③ 制造断连（FIN 形态，与 A 组同款代理）──
            PROXY.disconnectGracefully();

            // ── ④ 实验组 ──
            long t0 = System.nanoTime();
            StatusRuntimeException err = assertThrows(StatusRuntimeException.class,
                    () -> stub.logout(LogoutReq.newBuilder().setToken("g1-probe-default-behavior").build()));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            System.out.printf("[B 组 · admin-rpc · DEFAULT 断连行为] code=%s description=%s elapsedMs=%d%n",
                    err.getStatus().getCode(), err.getStatus().getDescription(), elapsedMs);

            assertEquals(Status.Code.DEADLINE_EXCEEDED, err.getStatus().getCode(),
                    "DEFAULT 断连行为下命令被排队 ⇒ 撞上 2s rpc 预算 ⇒ 客户端必须是 DEADLINE_EXCEEDED(1015)"
                            + " ⇒ 实测 code=" + err.getStatus().getCode()
                            + " description=" + err.getStatus().getDescription()
                            + " elapsedMs=" + elapsedMs);
            assertTrue(elapsedMs >= 1500L,
                    "DEFAULT 下命令被排队 ⇒ 必然等到 rpc 预算（2s）才返回 ⇒ 实测 " + elapsedMs + "ms");
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 取一个真实空闲端口。
     *
     * <p>⚠️ <b>B 侧原注记（C 侧一并留档）</b>：B 侧原用 {@code new ServerSocket(0)}，
     * 后来改成 {@code support/RealGrpcPort.pick()} —— 因为 macOS 的 {@code ServerSocket(0)} 是
     * <b>OS 盲选口</b>（临时段 49152–65535）⇒ 与 Docker 随机发布口<b>同段</b>
     * ⇒ 「挑到没人监听的口」这个前提<b>直接失效</b>
     * ⇒ 邻居占 127.0.0.1:P 时我们的通配绑定照样成功、lsof 两个监听者并存
     * ⇒ 客户端被更具体的那个抢走 ⇒ <b>静默假红</b>。
     * <b>C 侧无 {@code RealGrpcPort} 辅助类</b> ⇒ 本移植按 admin 域既有范式
     * （{@code AdminServiceApplicationTests}）回到 {@code new ServerSocket(0)}，
     * 并把 B 侧这条已知风险<b>如实留档</b>在此。
     */
    private static int pickFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
    }
}

// ⚠️ **必须显式写 dns:/// scheme**：Nacos discovery 在全局 NameResolverRegistry 里
//    注册了一个高优先级 provider，会把任何无 scheme 的 target 当作 serviceId 去 Nacos 拉实例。
//    本测试要的是**直连**的裸 channel，显式 scheme 即可绕开服务发现。
//    （2026-10-31 · G1 lane · P2-T04 转派单：原为 `.forAddress("127.0.0.1", GRPC_PORT)`
//      ⇒ 无 scheme ⇒ 会被 devh 的 discovery resolver 接管 ⇒ 把 127.0.0.1:P 当服务名去 Nacos 查
//      ⇒ UNAVAILABLE ⇒ 表现为「单跑绿、同跑红」。本段追加在**文件末尾** ⇒ 零行位移。）
