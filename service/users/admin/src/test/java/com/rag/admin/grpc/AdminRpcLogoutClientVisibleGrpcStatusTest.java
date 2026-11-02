package com.rag.admin.grpc;

import com.rag.admin.AdminServiceApplication;
import com.rag.admin.http.NacosStubConfiguration;
import com.rag.proto.admin.AdminServiceGrpc;
import com.rag.proto.admin.LogoutReq;
import com.rag.proto.admin.LogoutResp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 **admin-rpc 的 A 组：Redis 断连 ⇒ 客户端可见的 gRPC status 必须是 INTERNAL「注销失败,Redis写入异常」**
 * （2026-10-28 · G1 lane）
 *
 * <h2>B 出处（本类的行为基线）</h2>
 * <p><b>绝对路径</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcLogoutClientVisibleGrpcStatusTest.java}
 * <pre>
 *   B: RAG  service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcLogoutClientVisibleGrpcStatusTest.java
 *      :119-164  A 组主用例（对照组 → 制造断连 → 实验组）
 *      :166-172  端口挑选（原为 support/RealGrpcPort）
 *   B: RAG  service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcRedisDisconnectedFastFailTest.java
 *      :339-443  GracefulDisconnectProxy（本类的 FIN 代理实现）
 * </pre>
 *
 * <p>🔴 <b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13）</b> ——
 * 客户端可见的 gRPC code / description / 耗时预算三条断言<b>一字未改</b>。
 *
 * <h2>为什么 admin 域要单独做一遍</h2>
 * <p>{@code DRIFT-ARPC-013} 与 {@code DRIFT-USERRPC-V-014} 是**同机制、不同域** ⇒ ⇒
 * 而 <b>admin 与 user 的真源 logout 是【两份独立实现】</b>（黑名单前缀不同 · 各自的 `AccessExpire`）
 * ⇒ ⇒ **user 域的结论不能外推到 admin 域** ⇒ ⇒ admin 侧必须自己跑一遍（这也是 §48.1 给 admin 单独做
 * {@code probe-admin} 的同一条理由）。
 *
 * <h2>本类与 user 域那份的关系</h2>
 * <p>形状同款（真 gRPC server ＋ 真客户端 ＋ FIN 代理），但**落在不同模块** ⇒ ⇒
 * <b>各用自己的模块内那份代理实现</b>（admin 模块用本类里的 {@link GracefulDisconnectProxy}）
 * ⇒ ⇒ **同一次 A/B 对照内部必须是同一份实现**（这条纪律见 users/vs-common.md §50.2 ④）。
 *
 * <h2>⚠️ admin-rpc 的 gRPC 端口在 yaml 里是 8081</h2>
 * <p>本机 8081/8889/8082 <b>有别人的进程</b> ⇒ ⇒ 必须覆盖 ⇒ ⇒ 本类用<b>运行时挑的空闲口</b>
 * ⇒ ⇒ 挑不到 ⇒ <b>上下文起不来 ⇒ 大声失败</b>。
 *
 * <h2>判据</h2>
 * <ol>
 *   <li>对照组（Redis 可达）：logout 成功，且黑名单**真的写进 Redis**（key = {@code admin:jwt_blacklist:<token>}）
 *       ⇒ ⇒ 否则后面的「失败」可能只是「根本没连上」这种假失败；</li>
 *   <li>实验组（FIN 断开）：客户端 status 必须 = {@code INTERNAL} ＋ 「注销失败,Redis写入异常」
 *       ⇒ ⇒ <b>与 Go 腿逐字同形</b>（Go 腿实测：{@code grpc_code=Internal grpc_message="注销失败,Redis写入异常"}）；</li>
 *   <li>耗时必须 ≪ 2s rpc 预算 ⇒ ⇒ 证明「业务异常先到、不是超时先到」。</li>
 * </ol>
 *
 * <p>⚠️ <b>不要断言 {@code success == true}</b>：Go 真源
 * {@code service/user/admin/rpc/internal/logic/logoutlogic.go} 同样是 {@code return &pb.LogoutResp{}, nil}
 * ⇒ ⇒ {@code success} 恒为 proto3 默认 <b>false</b> ⇒ ⇒ RAG 侧不 setSuccess <b>与 Go 逐字同形</b>
 * ⇒ ⇒ <b>改成 true 反而制造漂移</b>。
 *
 * <h2>⚠️ 移植说明（C 侧 ↔ B 侧的差异，逐条登记）</h2>
 * <ol>
 *   <li><b>包名 / pb 包名</b>（D7）：{@code com.rag.users.admin.rpc} → {@code com.rag.admin.grpc}；
 *       {@code com.rag.users.admin.proto.*} → {@code com.rag.proto.admin.*}。</li>
 *   <li><b>启动类</b>：B 侧 {@code AdminRpcApplication} → C 侧 {@code AdminServiceApplication}（D1）
 *       ＋ 本模块既有的顶层 {@link NacosStubConfiguration}（<b>不触网</b>）。</li>
 *   <li><b>⛔ 删掉 {@code GoStyleHostPortPinning.pinContainerPort}</b> ——
 *       <b>C 侧不存在该辅助类</b> ⇒ 容器用 Testcontainers 的随机映射端口。</li>
 *   <li><b>{@code support/RealGrpcPort}</b> —— <b>C 侧不存在该辅助类</b>
 *       ⇒ 本移植按 admin 域既有范式（{@code AdminServiceApplicationTests}）用
 *       {@code new ServerSocket(0)} 取真实空闲端口；
 *       <b>B 侧原注记（macOS 临时段与 Docker 发布口同段 ⇒ 静默假红的风险）如实留档</b>
 *       在 {@link #pickFreePort()} 的注释里。</li>
 *   <li><b>{@code GracefulDisconnectProxy}</b> —— B 侧它住在
 *       {@code AdminRpcRedisDisconnectedFastFailTest}（<b>不在本批移植范围内</b>）
 *       ⇒ 本移植把它<b>逐字照搬</b>成本类的嵌套类
 *       ⇒ ⇒ A 组与 B 组（{@code AdminRpcLogoutClientVisibleGrpcStatusDefaultBehaviorTest}）
 *       <b>仍然共用同一份实现</b>（B 侧「同一次 A/B 对照内部必须是同一份实现」这条纪律不破）。</li>
 *   <li><b>{@code ADMIN_JWT_SECRET}</b>：C 侧 {@code admin-api.admin-auth.access-secret} /
 *       {@code admin-rpc.admin-auth.access-secret} 是<b>无默认值的必填项</b>
 *       （B 侧 P0 #8 修法）⇒ 上下文启动必须注入 ⇒ 用 {@code @DynamicPropertySource} 注入
 *       （同本模块 {@code AdminServiceApplicationTests}）。</li>
 * </ol>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {AdminServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN"
        })
@Testcontainers
class AdminRpcLogoutClientVisibleGrpcStatusTest {

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

    /**
     * 与本模块的 B 组<b>共用同一份</b>代理实现（本类内的嵌套类）⇒ ⇒ 故障形态必然同款。
     *
     * <p>🔴 <b>B 出处</b>：{@code service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcRedisDisconnectedFastFailTest.java:339-443}
     * （B 侧是那个文件的嵌套类；本移植<b>逐字照搬</b>实现，只改了所在文件）。
     */
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
        r.add("ADMIN_JWT_SECRET", () -> "admin-logout-a-group-test-secret-0123456789");
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("🔴 admin-rpc：Redis 断连 ⇒ 客户端可见的 gRPC status 必须是 INTERNAL「注销失败,Redis写入异常」（与 Go 腿逐字同形）")
    void logoutClientSeesInternalWhenRedisDisconnected() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forTarget("dns:///127.0.0.1:" + GRPC_PORT)
                .usePlaintext()
                .build();
        try {
            AdminServiceGrpc.AdminServiceBlockingStub stub = AdminServiceGrpc.newBlockingStub(channel);

            // ── ① 对照组：先建立连接并成功写一次（否则后面测的是「连不上」而不是「连上后被断开」）──
            LogoutResp ok = stub.logout(LogoutReq.newBuilder().setToken("g1-probe-reachable").build());
            assertFalse(ok.getSuccess(),
                    "Go 真源 admin logoutlogic 返回 `&pb.LogoutResp{}` ⇒ success 恒为 proto3 默认 false"
                            + " ⇒ RAG 侧必须同样不设 ⇒ ⚠️ 谁改成 true 谁就制造了一条新漂移");
            String written = redisTemplate.opsForValue().get("admin:jwt_blacklist:g1-probe-reachable");
            assertEquals("banned", written,
                    "对照组必须证明黑名单真的写进 Redis 了（admin 域前缀 = admin:jwt_blacklist:）");

            // ── ② 制造断连（FIN 形态，与本模块 B 组同款代理）──
            PROXY.disconnectGracefully();

            // ── ③ 实验组：客户端可见的 status ──
            long t0 = System.nanoTime();
            StatusRuntimeException err = assertThrows(StatusRuntimeException.class,
                    () -> stub.logout(LogoutReq.newBuilder().setToken("g1-probe-disconnected").build()));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            System.out.printf("[A 组 · admin-rpc · REJECT_COMMANDS] code=%s description=%s elapsedMs=%d%n",
                    err.getStatus().getCode(), err.getStatus().getDescription(), elapsedMs);

            assertEquals(Status.Code.INTERNAL, err.getStatus().getCode(),
                    "Redis 断连时客户端必须看到 INTERNAL（与 Go 腿逐字同形）"
                            + " ⇒ 实测 code=" + err.getStatus().getCode()
                            + " description=" + err.getStatus().getDescription()
                            + " elapsedMs=" + elapsedMs
                            + " ⇒ ⚠️ 若实测是 DEADLINE_EXCEEDED/1015 ⇒ 2s 超时预算先到 ⇒ ⇒ 断连策略没生效");
            assertEquals("注销失败,Redis写入异常", err.getStatus().getDescription(),
                    "描述必须与 Go 腿逐字相同（Go 实测 grpc_message=\"注销失败,Redis写入异常\"）");
            assertTrue(elapsedMs < 2000L,
                    "失败调用必须在 rpc 预算（2000ms）内返回 ⇒ 实测 " + elapsedMs + "ms");
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

    /**
     * <b>FIN 形态的透传代理</b> —— 用来制造「客户端连上之后被断开」这个故障形态。
     *
     * <p>🔴 <b>B 出处（逐字照搬）</b>：
     * {@code service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcRedisDisconnectedFastFailTest.java:339-443}
     * （B 侧是那个文件的嵌套静态类 ⇒ 本移植<b>只改了所在文件</b>，实现与注释逐字未动）。
     */
    static final class GracefulDisconnectProxy implements AutoCloseable {

        private final ServerSocket listener;
        private final Supplier<String> upstream;
        private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
        private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "admin-redis-rpc-proxy");
            t.setDaemon(true);
            return t;
        });
        private volatile boolean closed;

        private GracefulDisconnectProxy(Supplier<String> upstream) throws IOException {
            this.upstream = upstream;
            this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        }

        static GracefulDisconnectProxy create(Supplier<String> upstream) {
            try {
                GracefulDisconnectProxy p = new GracefulDisconnectProxy(upstream);
                p.start();
                return p;
            } catch (IOException e) {
                throw new IllegalStateException("透传代理起不来", e);
            }
        }

        int port() {
            return listener.getLocalPort();
        }

        private void start() {
            pool.submit(() -> {
                while (!closed) {
                    try {
                        Socket client = listener.accept();
                        clients.add(client);
                        String[] hp = upstream.get().split(":");
                        Socket server = new Socket(hp[0], Integer.parseInt(hp[1]));
                        pool.submit(() -> pipe(client, server));
                        pool.submit(() -> pipe(server, client));
                    } catch (Exception e) {
                        if (!closed) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            });
        }

        private static void pipe(Socket from, Socket to) {
            byte[] buf = new byte[8192];
            try (Socket f = from) {
                int n;
                while ((n = f.getInputStream().read(buf)) > 0) {
                    to.getOutputStream().write(buf, 0, n);
                    to.getOutputStream().flush();
                }
            } catch (Exception ignored) {
                // 断连是预期内的
            } finally {
                try {
                    to.shutdownOutput();
                } catch (Exception ignored) {
                    // 已关闭
                }
            }
        }

        /**
         * 制造断连：<b>先关监听口</b>（⇒ 重连被 connection refused 挡掉）
         * ⇒ <b>再半关闭已建立的连接</b>（⇒ 客户端收到 FIN）。
         *
         * <p>⚠️ 用 {@code shutdownOutput()} 而不是 {@code close()}：
         * socket 上若还有未读数据，{@code close()} 会发 <b>RST</b> ⇒ 就退回成
         * 「Connection reset」那条不经过 {@code disconnectedBehavior} 的路径了。
         */
        void disconnectGracefully() {
            closed = true;
            try {
                listener.close();
            } catch (Exception ignored) {
                // 已关闭
            }
            for (Socket s : clients) {
                try {
                    s.shutdownOutput();
                } catch (Exception ignored) {
                    // 已关闭
                }
            }
            clients.clear();
        }

        @Override
        public void close() {
            disconnectGracefully();
            pool.shutdownNow();
        }
    }
}

// ⚠️ **必须显式写 dns:/// scheme**：Nacos discovery 在全局 NameResolverRegistry 里
//    注册了一个高优先级 provider，会把任何无 scheme 的 target 当作 serviceId 去 Nacos 拉实例。
//    本测试要的是**直连**的裸 channel，显式 scheme 即可绕开服务发现。
//    （2026-10-31 · G1 lane · P2-T04 转派单：原为 `.forAddress("127.0.0.1", GRPC_PORT)`
//      ⇒ 无 scheme ⇒ 会被 devh 的 discovery resolver 接管 ⇒ 把 127.0.0.1:P 当服务名去 Nacos 查
//      ⇒ UNAVAILABLE ⇒ 表现为「单跑绿、同跑红」。本段追加在**文件末尾** ⇒ 零行位移。）
