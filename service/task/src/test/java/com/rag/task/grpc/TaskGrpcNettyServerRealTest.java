package com.rag.task.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rag.proto.task.GetTaskReq;
import com.rag.proto.task.GetTaskResp;
import com.rag.proto.task.Task;
import com.rag.proto.task.TaskServiceGrpc;
import com.rag.task.TaskServiceApplication;
import com.rag.task.support.GrpcServerPortCapture;
import com.rag.task.support.NacosStubConfiguration;
import com.rag.task.support.TestPostgres;
import com.rag.task.support.TestRedis;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.devh.boot.grpc.server.serverfactory.GrpcServerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>task 域 gRPC 的「真 netty 传输层」收发用例</b>
 * （真 TCP 端口 ＋ 真 netty server ＋ 真 netty client ＋ 真 proto 编解码 ＋ 真 PG/Redis）。</p>
 *
 * <h2>🔴 补的是哪一条缺口</h2>
 * <p>台账 §七·7-1 的 <b>U-3</b>：「<b>gRPC wire 层零覆盖</b>（无真 netty server 收发用例）」
 * —— 传输层 / HTTP2 帧 / trailer 线上序列化**全零覆盖**。
 * <p>本域此前的 gRPC 覆盖只有<b>装配层</b>：{@code TaskKafkaListenerAssemblyTest} 验的是
 * Kafka listener；{@code TaskServiceApplicationTests} 验的是<b>上下文起来</b>。
 * ⇒ 两者都<b>没有客户端真连过</b> ⇒ 「proto 一致 ≠ 消费方式已验」（AGENTS §3.4 效力边界）。</p>
 *
 * <h2>🔴🔴 客户端<b>必须</b>用 {@code forAddress(SocketAddress)} 形式（不是 {@code forAddress(String,int)}）</h2>
 * <p>devh 的客户端 starter 会把一个 {@code DiscoveryClientResolverFactory} 注册进
 * <b>全局</b> {@link io.grpc.NameResolverRegistry}（源码注释原文：<i>"More important than DNS"</i>
 * ⇒ priority 6 &gt; DNS 的 5）⇒ <b>整个 JVM 里</b>「不带 scheme 的 gRPC 目标」全部被劫持
 * ⇒ {@code forAddress("127.0.0.1", port)} 的目标会被拼成 {@code discovery:///127.0.0.1:port}
 * ⇒ 去 **Nacos** 查一个叫「127.0.0.1」的服务 ⇒ <b>零实例</b>
 * ⇒ 客户端立刻报 <b>{@code UNAVAILABLE: No servers found for 127.0.0.1:port}</b>。
 * <p>⚠️ <b>这个报错极具迷惑性</b>：它长得像「服务端没起来 / 端口不对」，
 * 实际是<b>客户端名字解析被劫持</b> ⇒ 排查方向会被完全带偏。
 * <p>⇒ ⇒ <b>本类用 {@code forAddress(new InetSocketAddress(host, port))}</b>：
 * 该重载<b>不经过名字解析</b> ⇒ <b>免疫劫持</b>。
 * <p>📌 <b>与 like 域同款</b>（{@code LikeGrpcNettyServerRealTest} 的同类踩坑记录）。</p>
 *
 * <h2>⚠️ 端口用 {@code 0}（随机）而不是钉死</h2>
 * <p>devh 的 gRPC server 是 {@code SmartLifecycle} ⇒ 上下文一启动就 bind 端口
 * ⇒ 固定端口在「多个上下文同时存活」时必然 {@code BindException}
 * ⇒ ⇒ 用 {@code grpc.server.port=0} 让 OS 分配 ⇒ 再用 {@link GrpcServerPortCapture}
 * 取<b>实际</b>端口（<b>不是</b> {@code event.getPort()} —— 那在 port=0 时返回 0）。</p>
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <p>B 侧是<b>两个进程</b>（{@code task-api} ＋ {@code task-rpc}）⇒ gRPC 只在
 * {@code task-rpc} 进程里 ⇒ <b>没有</b>「一个上下文里 HTTP 与 gRPC 同时活」这件事。
 * <p>C 侧按 D1 合并为<b>单一进程</b> ⇒ 本类因此能在<b>同一个上下文</b>里
 * 「起 HTTP ＋ 起 gRPC ＋ 真 client 连真 server」。
 * ⚠️ 这是<b>拓扑产物</b>（D1/D7），<b>不是</b> B 侧行为的等价物
 * ⇒ 本类<b>不产出</b>任何「与 B 侧行为一致」的结论。</p>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>被断言的那些值本身</b>全部逐字取自 B 侧源码（见下方 B 出处表）⇒
 * <b>断言的是 B 侧的契约</b>；<b>测试形态</b>（同进程起 gRPC）是 C 侧独有的拓扑。
 * ⇒ 二者<b>分开记账</b>：断言内容 = B 侧契约；启动形态 = D1 产物。</p>
 *
 * <h2>③ B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>服务全名 {@code task.TaskService/GetTask}（<b>L1 契约</b>）</td>
 *       <td>{@code service/task/task-proto/src/main/proto/task.proto:3}（{@code package task;}）
 *           ＋ {@code :23-24}（{@code service TaskService} / {@code rpc GetTask}）</td></tr>
 *   <tr><td>两条任务定义（190/3 · 1902/5 ＋ 中文名与描述）</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskDefs.java:82-84}</td></tr>
 *   <tr><td>{@code user_id <= 0} ⇒ {@code INVALID_ARGUMENT} ＋ {@code "user_id invalid"}</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskDomainService.java:91-93}</td></tr>
 *   <tr><td>{@code StatusRuntimeException} <b>必须透传</b>（不能被抹成 UNKNOWN）</td>
 *       <td>{@code service/task/task-rpc/.../service/impl/TaskServiceGrpcImpl.java:76-90}
 *           （{@code invoke} 模板的两条错误出口）</td></tr>
 *   <tr><td>Redis 读 key {@code task:progress:<uid>} ＋ 值格式 {@code status|progress|target}</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskRedisKeys.java:69}
 *           ＋ {@code …/service/TaskDomainService.java:207-211}（{@code packProgress}）</td></tr>
 *   <tr><td>PG 回源表 {@code task_progress}（复合主键 {@code user_id} + {@code task_id}）</td>
 *       <td>{@code service/task/task-rpc/src/main/resources/db/migration/V1__create_task_progress.sql:23}</td></tr>
 * </table>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的 netty gRPC server 真起来了、真能收发、
 * 错误出口的形状与 B 侧源码记录的期望一致」。
 * <p>「<b>B 侧此刻真的产出同样形状</b>」属于<b>双侧活体对拍</b>（{@code tools/difftest/task/}
 * —— 但那一条目前只覆盖 <b>HTTP 面</b>）⇒ ⇒ <b>本类不冒充该证据</b>。</p>
 */
@SpringBootTest(
        classes = {TaskServiceApplication.class, NacosStubConfiguration.class,
                GrpcServerPortCapture.class},
        properties = {
                // ── Nacos 不触网（与 TaskRealInfraTestBase 同款）──
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "logging.level.com.alibaba.nacos=WARN",
                // ── 追踪不外发 ──
                "management.otlp.tracing.export.enabled=false",
                // 🔴 0 = 随机可用端口（devh 的 GrpcServerProperties#port 文档语义）
                //    ⇒ 多个上下文同时存活时不会撞端口
                "grpc.server.port=0",
                // ⚠️ 只绑回环，不绑 0.0.0.0（测试进程不外露）
                "grpc.server.address=127.0.0.1",
                // ── Kafka 不可达：本类只验 gRPC 传输层，不验端到端消费 ──
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.producer.properties.max.block.ms=2000",
                // ── 关掉 CPU 自适应降载（否则机器一忙就抢先返回裸 503，把判据盖掉）──
                "rag.protection.cpu-shedding.enabled=false"
        })
class TaskGrpcNettyServerRealTest {

    /** uid 序号（避开其它测试类用的固定 uid ⇒ 不互相污染）。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(9_100_000_000L);

    /** Redis 读路径的 key（B 侧 {@code TaskRedisKeys#userProgressCache}）。 */
    private static final String PROGRESS_KEY_PREFIX = "task:progress:";

    private ManagedChannel channel;

    private TaskServiceGrpc.TaskServiceBlockingStub stub;

    @Autowired
    private GrpcServerFactory grpcServerFactory;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        // ⚠️ 主 application.yml 里 `spring.datasource.password: ${POSTGRES_PASSWORD}` 是
        //    **裸占位符**（无默认值，fail-closed 纪律）⇒ 必须由外部注入，否则上下文起不来。
        registry.add("POSTGRES_PASSWORD", () -> "password");
    }

    /**
     * ⚠️ 为什么建 channel 放 {@code @BeforeEach} 而不是 {@code @BeforeAll}：
     * Spring 的上下文是在<b>测试实例创建</b>（{@code TestInstancePostProcessor}）时才加载的
     * ⇒ 而 JUnit 5 的 {@code @BeforeAll} 早于实例创建 ⇒ 那时端口还没捕获。
     */
    @BeforeEach
    void openNettyChannel() {
        int port = GrpcServerPortCapture.port();
        assertThat(port)
                .as("必须先拿到 netty server 的实际绑定端口（>0）—— "
                        + "拿到 0 说明读的是 event.getPort()（配置值）而不是 event.getServer().getPort()")
                .isGreaterThan(0);
        // 🔴 必须用 SocketAddress 重载：绕过名字解析 ⇒ 免疫 devh 的 discovery scheme 劫持
        channel = NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
                .usePlaintext()
                .build();
        stub = TaskServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void closeNettyChannel() {
        if (channel != null) {
            channel.shutdownNow();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
    }

    // ==================================================================
    // ① netty server 真起来：真 TCP 监听 ＋ 真 netty 实现
    // ==================================================================

    @Test
    @DisplayName("🔴 devh 真的选中了 netty server 工厂（不是 in-process），并 bind 了一个真 TCP 端口")
    void nettyServerIsReallyBoundToATcpPort() {
        assertThat(grpcServerFactory.getClass().getName())
                .as("server 工厂必须是 netty 的（ShadedNettyGrpcServerFactory）"
                        + "⇒ in-process 会是 InProcessGrpcServerFactory")
                .contains("Netty")
                .doesNotContain("InProcess");

        assertThat(GrpcServerPortCapture.port())
                .as("实际绑定端口必须 > 0（in-process server 的 getPort() 恒为 -1）")
                .isGreaterThan(0);

        // 🔴 真 socket 连一下 ⇒ 证明这是真的 TCP 监听（in-process 根本没有端口）
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", GrpcServerPortCapture.port()), 3000);
            assertThat(socket.isConnected())
                    .as("裸 TCP 连接必须成功 ⇒ 证明 netty server 真的在监听这个端口")
                    .isTrue();
        } catch (IOException e) {
            throw new AssertionError("裸 TCP 连接失败 ⇒ netty server 没有真的监听端口", e);
        }
    }

    // ==================================================================
    // ② L1 契约：服务全名逐字未变（proto package 仍是 `task`）
    // ==================================================================

    @Test
    @DisplayName("🔴 gRPC 服务全名逐字未变（task.TaskService/GetTask）—— proto package 没被 D7 压掉")
    void grpcFullMethodNameIsUnchanged() {
        assertThat(TaskServiceGrpc.getGetTaskMethod().getFullMethodName())
                .as("proto 的 package 仍是 `task`（D7 只改了 java_package）⇒ "
                        + "服务全名是 **wire 契约** ⇒ 跨域调用方按它寻址")
                .isEqualTo("task.TaskService/GetTask");
    }

    // ==================================================================
    // ③ 成功路径：真线路 ⇒ 两条任务定义逐字段回来
    // ==================================================================

    @Test
    @DisplayName("🔴 真 netty 线路调用 GetTask ⇒ 两条任务定义逐字段回来（真 proto 编解码）")
    void getTaskOverRealNettyReturnsTheTwoTaskDefinitions() {
        long uid = UID_SEQ.incrementAndGet();

        GetTaskResp resp = stub.getTask(GetTaskReq.newBuilder().setUserId(uid).build());

        assertThat(resp.getTaskCount())
                .as("必须真的经线路回来（不是本地 mock）")
                .isEqualTo(2);

        Task article = resp.getTask(0);
        assertThat(article.getName()).isEqualTo("文章获赞任务");
        assertThat(article.getDesc()).isEqualTo("有一篇文章点赞大于3");
        assertThat(article.getTaskId()).isEqualTo(190L);
        assertThat(article.getCompletionProgress()).isEqualTo(0L);
        assertThat(article.getRequiredProgress())
                .as("required_progress 取自任务定义（3），**不是** DB 的 target")
                .isEqualTo(3L);

        Task like = resp.getTask(1);
        assertThat(like.getName()).isEqualTo("点赞任务");
        assertThat(like.getDesc()).isEqualTo("给文章点赞5次");
        assertThat(like.getTaskId()).isEqualTo(1902L);
        assertThat(like.getCompletionProgress()).isEqualTo(0L);
        assertThat(like.getRequiredProgress()).isEqualTo(5L);
    }

    // ==================================================================
    // ④ 读路径：Redis 命中 / PG 回源（两条都经真线路）
    // ==================================================================

    @Test
    @DisplayName("🔴 Redis 命中 ⇒ 真线路拿回种子里的进度（190 → 2 · 1902 → 4）")
    void redisHitOverRealNettyReturnsSeededProgress() {
        long uid = UID_SEQ.incrementAndGet();
        seedRedisProgress(uid, "doing|2|3", "done|4|5");

        GetTaskResp resp = stub.getTask(GetTaskReq.newBuilder().setUserId(uid).build());

        assertThat(resp.getTaskCount()).isEqualTo(2);
        assertThat(resp.getTask(0).getCompletionProgress()).isEqualTo(2L);
        assertThat(resp.getTask(1).getCompletionProgress()).isEqualTo(4L);
    }

    @Test
    @DisplayName("🔴 Redis 未命中 ⇒ 回源 PG ⇒ 真线路拿回 DB 里的进度（190 → 7 · 1902 → 9）")
    void pgFallbackOverRealNettyReturnsDbProgress() {
        long uid = UID_SEQ.incrementAndGet();
        seedPgProgress(uid, "doing", 7L, 3L);
        seedPgProgress(uid, "done", 9L, 5L, 1902L);

        GetTaskResp resp = stub.getTask(GetTaskReq.newBuilder().setUserId(uid).build());

        assertThat(resp.getTaskCount()).isEqualTo(2);
        assertThat(resp.getTask(0).getCompletionProgress())
                .as("必须经 PG 回源拿回 DB 里的 7（≠ Redis 命中时的 2 ⇒ 两条路径确实不同）")
                .isEqualTo(7L);
        assertThat(resp.getTask(1).getCompletionProgress()).isEqualTo(9L);
    }

    // ==================================================================
    // ⑤ 错误出口：status + description + trailer 都要活过 HTTP/2 线上序列化
    // ==================================================================

    @Test
    @DisplayName("🔴 user_id=0 ⇒ 真线路回来 INVALID_ARGUMENT + 「user_id invalid」（trailer 非空）")
    void invalidUserIdSurvivesTheWireAsInvalidArgument() {
        long uid = 0L;

        assertThatThrownBy(() -> stub.getTask(GetTaskReq.newBuilder().setUserId(uid).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode())
                            .as("必须仍是 INVALID_ARGUMENT —— B 侧 TaskServiceGrpcImpl#invoke "
                                    + "把 StatusRuntimeException **透传**，不能被抹成 UNKNOWN；"
                                    + "且这**只可能**是服务端回传的码（客户端侧传输故障是 UNAVAILABLE）")
                            .isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription())
                            .as("description 是 L1 文案契约（逐字照抄 B 侧 TaskDomainService）"
                                    + "；且它**只可能**来自服务端 ⇒ 证明 grpc-message 真的过了线路")
                            .isEqualTo("user_id invalid");
                    Metadata trailers = e.getTrailers();
                    assertThat(trailers)
                            .as("错误必须活过 HTTP/2 **trailer** 的线上序列化 ⇒ 客户端拿得到 trailers"
                                    + "（实测：gRPC 的错误响应是「trailers-only」形态"
                                    + "（HEADERS 帧 + END_STREAM）⇒ grpc-java 把它作为 trailers 交给应用）")
                            .isNotNull();
                    assertThat(trailers.keys())
                            .as("trailer 帧里至少还留着服务端发来的 content-type 头"
                                    + "（grpc-status / grpc-message 两个**保留伪头**会被 grpc-java"
                                    + "**消费掉** ⇒ 它们不留在 trailers 里"
                                    + " ⇒ 文案只能从 Status#getDescription() 读）")
                            .contains("content-type");
                });
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 播种 Redis 进度缓存（B 侧 {@code TaskRedisKeys#userProgressCache} 的 key 与值格式）。 */
    private void seedRedisProgress(long uid, String articleValue, String likeValue) {
        Map<String, String> packed = new LinkedHashMap<>();
        packed.put("190", articleValue);
        packed.put("1902", likeValue);
        redis.opsForHash().putAll(PROGRESS_KEY_PREFIX + uid, packed);
        redis.expire(PROGRESS_KEY_PREFIX + uid, Duration.ofMinutes(15));
    }

    /** 播种 PG 一行 {@code task_progress}（复合主键 user_id + task_id）。 */
    private void seedPgProgress(long uid, String status, long progress, long target) {
        seedPgProgress(uid, status, progress, target, 190L);
    }

    private void seedPgProgress(long uid, String status, long progress, long target, long taskId) {
        jdbcTemplate.update(
                "INSERT INTO task_progress (user_id, task_id, status, progress, target) "
                        + "VALUES (?, ?, ?, ?, ?)",
                uid, taskId, status, progress, target);
    }
}
