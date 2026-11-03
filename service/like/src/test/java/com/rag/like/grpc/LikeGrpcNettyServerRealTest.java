package com.rag.like.grpc;

import com.rag.like.LikeServiceApplication;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.GrpcErrFactory;
import com.rag.like.support.GrpcServerPortCapture;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import com.rag.proto.like.ActionType;
import com.rag.proto.like.GetLikeCountReq;
import com.rag.proto.like.GetLikeCountResp;
import com.rag.proto.like.GetLikeStateReq;
import com.rag.proto.like.GetLikeStateResp;
import com.rag.proto.like.GetTargetLikerListReq;
import com.rag.proto.like.GetTargetLikerListResp;
import com.rag.proto.like.GetUserLikeListReq;
import com.rag.proto.like.GetUserLikeListResp;
import com.rag.proto.like.GetUserTotalLikeReq;
import com.rag.proto.like.GetUserTotalLikeResp;
import com.rag.proto.like.LikeActionReq;
import com.rag.proto.like.LikeActionResp;
import com.rag.proto.like.LikeServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import net.devh.boot.grpc.server.serverfactory.GrpcServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>like 域 gRPC server 的「真 netty 传输层」启动守门</b>
 * （真 TCP 端口 ＋ 真 netty server ＋ 真 netty client ＋ 真 proto 编解码 ＋ 真 PG/Redis）。
 *
 * <h2>🔴 为什么还要再写一个（{@code LikeGrpcServiceContractTest} 已经覆盖了 gRPC）</h2>
 * <p>因为那份测试用的是 <b>in-process 传输</b>（{@code grpc.server.port=-1} ⇒
 * {@code ConditionalOnInterprocessServer} 不匹配 ⇒ netty server <b>根本没被创建</b>）。
 * <p>⇒ <b>in-process 验不到的东西</b>（本类逐条补上）：
 * <ol>
 *   <li><b>netty server 是否真的被 starter 创建</b>（{@code ShadedNettyGrpcServerFactory}
 *       ＋ {@code NettyServerBuilder}）—— 这是<b>框架机制</b>，不是业务代码；</li>
 *   <li><b>是否真的 bind 了一个 TCP 端口</b>（能被裸 socket 连上）；</li>
 *   <li><b>HTTP/2 帧编解码 + trailer 的线上序列化</b>：trailer 里的业务码
 *       （{@code rag-like-biz-code}）在 in-process 下是「直接传对象」，
 *       在真线上必须经过 <b>HTTP/2 trailer 头</b>的编码 ⇒ 能测出「trailer 没真发出去」这类缺陷；</li>
 *   <li><b>description 文案</b>在真线上必须完整保留（它是业务码的载体）。</li>
 * </ol>
 *
 * <h2>🔴🔴 客户端<b>必须</b>用 {@code forAddress(SocketAddress)} 形式（不是 {@code forAddress(String,int)}）</h2>
 * <p><b>这是本类踩到的最深的一个坑，值得单列</b>：
 * <p>① devh 的 {@code DiscoveryClientResolverFactory}（客户端 starter）在
 * <b>{@code priority() = 6}</b>（源码注释原文：<i>"More important than DNS"</i>）
 * ⇒ 它<b>注册进全局</b> {@link NameResolverRegistry} 后，
 * {@code NameResolverRegistry#getDefaultScheme()} 变成 <b>{@code discovery}</b>
 * （DNS provider 只有 5）⇒ <b>整个 JVM 里</b>「不带 scheme 的 gRPC 目标」全部被劫持。
 * <p>② devh 是通过 {@code GrpcClientAutoConfiguration} 里
 * {@code nameResolverRegistration.register(NameResolverRegistry.getDefaultRegistry())}
 * 做这件事的 ⇒ <b>只要 Spring 上下文里有 devh 客户端 starter + 一个 DiscoveryClient bean</b>
 * ⇒ 劫持就成立（<b>与 discovery 是否 enabled 无关</b>）。
 * <p>③ 于是 {@code NettyChannelBuilder.forAddress("127.0.0.1", 64059)}
 * 的目标被拼成 {@code discovery:///127.0.0.1:64059}
 * ⇒ 去 <b>Nacos</b> 查一个叫「127.0.0.1」的服务 ⇒ <b>零实例</b>
 * ⇒ 客户端立刻报 <b>{@code UNAVAILABLE: No servers found for 127.0.0.1:64059}</b>。
 * <p>⚠️ <b>这个报错极具迷惑性</b>：它长得像「服务端没起来 / 端口不对」，
 * 实际是<b>客户端名字解析被劫持</b> ⇒ 排查方向会被完全带偏。
 * <p>⇒ ⇒ <b>本类用 {@code forAddress(new InetSocketAddress(host, port))}</b>：
 * 该重载<b>不经过名字解析</b>（实测「Name resolution delay = 0」）⇒ <b>免疫劫持</b>。
 * <p>📌 <b>对后续对拍探针的约束</b>：{@code tools/difftest/like/} 的 gRPC 线探针
 * <b>必须</b>用 SocketAddress 形式（或显式 {@code dns:///} scheme）。
 *
 * <h2>⚠️ 端口用 {@code 0}（随机）而不是钉死</h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 钉死端口会互相撞。
 * <p>⇒ ⇒ 用 {@code grpc.server.port=0} 让 OS 分配 ⇒ 再用
 * {@link GrpcServerPortCapture}（监听 {@code GrpcServerStartedEvent}）
 * 取<b>实际</b>端口。
 * <p>⚠️ <b>不能读 {@code event.getPort()}</b> —— devh 在 port=0 时把配置的 0 原样放进事件
 * （见该类 javadoc）⇒ 真正的端口只能取 {@code event.getServer().getPort()}。
 *
 * <h2>⚠️ 本类的证据边界（AGENTS §3.3.3）
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的 netty gRPC server 真起来了、真能收发、
 * 错误出口的形状与 B 侧源码记录的期望一致」。
 * <p>「<b>B 侧此刻真的产出同样形状</b>」属于<b>双侧活体对拍</b>（{@code tools/difftest/like/}）
 * ⇒ <b>本类不冒充该证据</b>。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class,
        GrpcServerPortCapture.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // 🔴 0 = 随机可用端口（devh 的 GrpcServerProperties#port 文档语义）
                //    ⇒ 覆盖 application-test.properties 里的 -1（那是 in-process 专用档）
                "grpc.server.port=0",
                // ⚠️ 只绑回环，不绑 0.0.0.0（测试进程不外露）
                "grpc.server.address=127.0.0.1"
        })
@ActiveProfiles("test")
class LikeGrpcNettyServerRealTest {

    private static final AtomicLong SEQ = new AtomicLong(8_600_000_000_000L);

    private ManagedChannel channel;

    private LikeServiceGrpc.LikeServiceBlockingStub stub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    /**
     * ⚠️ 为什么建 channel 放 {@code @BeforeEach} 而不是 {@code @BeforeAll}：
     * Spring 的上下文是在 <b>测试实例创建</b>（{@code TestInstancePostProcessor}）时才加载的
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
        stub = LikeServiceGrpc.newBlockingStub(channel);
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
    // 1. netty server 真起来：真 TCP 监听 ＋ 真 netty 实现
    // ==================================================================

    @Autowired
    private GrpcServerFactory grpcServerFactory;

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

    @Test
    @DisplayName("🔴 客户端走的传输层是 netty（classpath 上唯一的 ManagedChannelProvider 就是 netty 那个）")
    void clientTransportIsNetty() {
        // ⚠️ ManagedChannelRegistry#providers() / #provider() 都是包级私有 ⇒ 只能从
        //    「classpath 上登记了哪些 ManagedChannelProvider」这一层取证。
        Set<String> declared = new LinkedHashSet<>();
        try {
            Enumeration<URL> urls = getClass().getClassLoader()
                    .getResources("META-INF/services/io.grpc.ManagedChannelProvider");
            while (urls.hasMoreElements()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(declared::add);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("读取 META-INF/services/io.grpc.ManagedChannelProvider 失败", e);
        }

        assertThat(declared)
                .as("classpath 上登记的 ManagedChannelProvider 必须只有 netty 那个 ⇒ 默认传输层是 netty")
                .contains("io.grpc.netty.shaded.io.grpc.netty.NettyChannelProvider")
                .noneMatch(name -> name.toLowerCase().contains("inprocess"));
    }

    // ==================================================================
    // 2. 6 个 RPC 方法全部经真线路分发
    // ==================================================================

    @Test
    @DisplayName("🔴 6 个 RPC 方法全部经真 netty 线路分发（真 client → 真 server → 真方法体 → 真 PG/Redis）")
    void allSixMethodsAreDispatchedOverNetty() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-netty-" + uid;
        String targetId = "t-" + uid;

        GetLikeCountResp count = stub.getLikeCount(GetLikeCountReq.newBuilder()
                .setTargetType(targetType).addTargetIds(targetId).build());
        assertThat(count.getCountsMap()).as("getLikeCount 必须真的返回").isNotNull();

        GetLikeStateResp state = stub.getLikeState(GetLikeStateReq.newBuilder()
                .setTargetType(targetType).setUserId(uid).addTargetIds(targetId).build());
        assertThat(state.getStatesMap()).as("getLikeState 必须真的返回").isNotNull();

        GetUserLikeListResp userList = stub.getUserLikeList(GetUserLikeListReq.newBuilder()
                .setTargetType(targetType).setUserId(uid).setCursor(0L).setPageSize(10).build());
        assertThat(userList.getListList()).as("getUserLikeList 必须真的返回").isNotNull();

        GetTargetLikerListResp targetList = stub.getTargetLikerList(GetTargetLikerListReq.newBuilder()
                .setTargetType(targetType).setTargetId(targetId).setCursor(0L).setPageSize(10).build());
        assertThat(targetList.getListList()).as("getTargetLikerList 必须真的返回").isNotNull();

        GetUserTotalLikeResp total = stub.getUserTotalLike(
                GetUserTotalLikeReq.newBuilder().setUserId(uid).build());
        assertThat(total.getTotalLikeCount()).as("getUserTotalLike 必须真的返回").isGreaterThanOrEqualTo(0L);

        // ⚠️ likeAction 会写 Redis + 推 Kafka（无 broker ⇒ 异步失败、不改 gRPC 结果）
        LikeActionResp action = stub.likeAction(LikeActionReq.newBuilder()
                .setTargetType(targetType).setTargetId(targetId).setUserId(uid)
                .setAuthorId(uid + 1).setActionType(ActionType.ACTION_LIKE).build());
        assertThat(action).as("likeAction 必须真的返回").isNotNull();
    }

    // ==================================================================
    // 3. 错误出口：description + trailer 必须活过 HTTP/2 线上序列化
    // ==================================================================

    @Test
    @DisplayName("🔴 业务错误经真线路后：description 文案与 rag-like-biz-code trailer 都要完整活下来")
    void businessErrorSurvivesTheWire() {
        long uid = SEQ.incrementAndGet();

        // 空 target_ids ⇒ 领域层抛 inputWrong ⇒ 业务码 2001 + 文案「输入需要查询的ID为空」
        assertThatThrownBy(() -> stub.getLikeCount(GetLikeCountReq.newBuilder()
                .setTargetType("article-netty-err-" + uid).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode())
                            .as("2001 必须映射成 INVALID_ARGUMENT（逐条照抄 B 侧 LikeGrpcError）")
                            .isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription())
                            .as("description 必须保留业务文案（它是业务码的载体）")
                            .isEqualTo("输入需要查询的ID为空");
                    Metadata trailers = Status.trailersFromThrowable(e);
                    assertThat(trailers).isNotNull();
                    assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                            .as("trailer 必须带业务码（客户端据此还原码值）—— "
                                    + "真线路下 trailer 要过 HTTP/2 trailer 头编码")
                            .isEqualTo(String.valueOf(ErrCodes.ERROR_INPUT_WRONG));
                });
    }

    // ==================================================================
    // 4. 环境事实记录：默认名字解析 scheme 被 devh 抢走（为什么客户端必须用 SocketAddress）
    // ==================================================================

    @Test
    @DisplayName("📌 环境事实：devh 的 discovery provider 把 grpc 的默认名字解析 scheme 抢走了")
    void defaultNameResolverSchemeIsHijackedByDevh() {
        String defaultScheme = NameResolverRegistry.getDefaultRegistry().getDefaultScheme();

        assertThat(defaultScheme)
                .as("本上下文里 grpc 的默认名字解析 scheme 已被 devh 的 DiscoveryClientResolverFactory "
                        + "抢走（priority 6 > DNS 5）⇒ 用 forAddress(String,int) 的客户端会被劫持到 "
                        + "Nacos ⇒ 报『No servers found for <host>:<port>』这种像『服务端没起来』的错。"
                        + "⚠️ 若本断言变红，说明 devh/SCA 的注册行为变了 ⇒ 复核 forAddress(SocketAddress) "
                        + "是否仍必要（无害，但结论要更新）")
                .isNotEqualTo("dns");
    }
}
