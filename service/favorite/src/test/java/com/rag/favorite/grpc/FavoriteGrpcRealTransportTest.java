package com.rag.favorite.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag.favorite.contract.ErrCodes;
import com.rag.favorite.contract.GrpcErrorMapper;
import com.rag.proto.favorite.CreateFavoriteFolderReq;
import com.rag.proto.favorite.FavoriteServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 🔴🔴 favorite 域**自身 gRPC 面**的**真传输**测试 —— 补上「Bean 存在 ≠ 机制生效」的那一层。
 *
 * <h2>为什么必须有这一条（不能只断言「Bean 在容器里」）</h2>
 * <p>本域的 {@code grpc/FavoriteGrpcService} 若只被断言成「Bean 存在」⇒ 只能证明
 * **容器里有这个对象** ⇒ 证不了：
 * <ul>
 *   <li>devh 的 gRPC server <b>真的把它注册进了 service registry</b>；</li>
 *   <li>请求真的走完了<b>真 TCP + 真 HTTP/2 + 真 protobuf 编解码</b>；</li>
 *   <li>本域<b>特有</b>的错误契约（<b>description 文案反查业务码</b>）在<b>线路形态</b>上仍然成立。</li>
 * </ul>
 * ⇒ 这正是 AGENTS.md §3.3 的「四类静默失效」之一 ⇒ ⇒ 必须**真触发**。
 *
 * <h2>本类验的到底是什么</h2>
 * <p>🔴 **favorite 域的 gRPC 错误契约靠「description 文案反查」**（<b>不是</b> trailer）
 * ⇒ ⇒ 业务码（5201 / 5202 / 5203 / 5301-5304 / 5101-5105 等非标准码）
 * <b>只能</b>从 {@code status.description} 里反查出来 ⇒ ⇒
 * ⇒ 「description 在线路形态上有没有被保住」是本域**最容易被静默破坏**的一条
 * ⇒ ⇒ 本类专门盯它。
 *
 * <h2>为什么全部用<b>短路</b>用例（不碰 DB）</h2>
 * <p>本类要验的是「<b>线路形态</b>」⇒ ⇒ 用领域层<b>第一条就短路</b>的分支：
 * {@code userId <= 0}（⇒ 5003）与「folder 名为空」（⇒ 5202）
 * ⇒ ⇒ 这两条在 B 侧 Go 里也是<b>先于</b>任何 DB 访问判定的
 * （B 侧 {@code create_favorite_folder_logic.go:51-57} ⇒ {@code :59-66}）
 * ⇒ ⇒ 与 B 侧**同序** ⇒ ⇒ 本类**不需要**真 PG ⇒ ⇒ 测试轻且确定。
 *
 * <p>⚠️ <b>B 出处</b>：
 * <ul>
 *   <li>B 侧 rpc 侧入口：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteServiceGrpcImpl.java}
 *       （C 侧落点 = {@code com.rag.favorite.grpc.FavoriteGrpcService}）</li>
 *   <li>B 侧错误契约：{@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/GrpcErrorMapper.java}
 *       （C 侧落点 = {@code com.rag.favorite.contract.GrpcErrorMapper}）</li>
 *   <li>Go 侧短路序：{@code service/favorite/rpc/internal/logic/create_favorite_folder_logic.go:51-66}</li>
 * </ul>
 */
@SpringBootTest(
        classes = {com.rag.favorite.FavoriteServiceApplication.class,
                com.rag.favorite.support.NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:65535",
                "grpc.client.user-rpc.negotiation-type=plaintext",
                "grpc.client.article-rpc.address=static://127.0.0.1:65535",
                "grpc.client.article-rpc.negotiation-type=plaintext",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 🔴 C 侧 FavoriteApiProperties 的密钥守卫是 fail-closed 的（缺值 ⇒ 启动失败）
                "favorite-api.user-auth.access-secret=test-only-favorite-jwt-secret-at-least-32-bytes",
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/first_db",
                "spring.datasource.username=admin",
                "spring.datasource.password=password",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.flyway.enabled=false",
                "favorite.fact.dispatch.enabled=false",
                "favorite.fact.authority.enabled=false",
                "favorite.fact.legacy-preflight.enabled=false"
        })
class FavoriteGrpcRealTransportTest {

    /**
     * gRPC server 端口 —— <b>不用 {@code port=0}</b>。
     *
     * <p>⚠️ {@code port=0} 让 OS 从<b>临时端口段</b>里挑 ⇒ 那是 <b>Docker 随机发布端口</b>用的同一段
     * ⇒ ⇒ 别的会话的容器可能先占住 ⇒ ⇒ 「偶尔红、换个时间又绿」的抖动。
     * ⇒ ⇒ 改成**自己挑一个确定归我们的端口**（专用端口段 + bind 自检）。
     */
    private static final int GRPC_PORT = com.rag.favorite.support.RealTomcatPort.pick();

    @DynamicPropertySource
    static void grpcPort(DynamicPropertyRegistry registry) {
        registry.add("grpc.server.port", () -> String.valueOf(GRPC_PORT));
    }

    /**
     * 打开一条**真 TCP** 通道到本测试的 gRPC server（devh 的 netty-shaded）。
     *
     * <p>🔴🔴 **必须用 {@code forAddress(SocketAddress)}**（不是 {@code forAddress(String,int)}）
     * —— 2026-11-04 实测踩到的坑，**like 域同款**（本仓已有先例，见
     * {@code service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java:79-99}）：
     * <ol>
     *   <li>{@code forAddress(host, port)} 产生的 target 是**裸 authority**
     *       （{@code "127.0.0.1:<port>"}，**没有 scheme**）
     *       ⇒ grpc 改用**全局默认 scheme** 去解析；</li>
     *   <li>全局默认 scheme = **优先级最高的** provider 的 scheme
     *       ⇒ grpc 自带 DNS = 5、devh {@code DiscoveryClientResolverFactory} = **6**
     *       （devh 源码注释原文：{@code return 6; // More important than DNS}）；</li>
     *   <li>devh 的 {@code NameResolverRegistration} 在**上下文创建期**注册进**全局** registry
     *       ⇒ ⇒ **只要本 JVM 里创建过任何 Spring 上下文**，默认 scheme 就永久变 {@code "discovery"}；</li>
     * </ol>
     * ⇒ ⇒ 本机字面地址被当成**服务名**去查 ⇒ {@code No servers found for 127.0.0.1:<port>}
     * ⇒ ⇒ 通道 {@code UNAVAILABLE} ⇒ ⇒ **看起来像「gRPC server 没起来」，其实 server 明明在监听**
     * （日志里就有 {@code gRPC Server started, listening on address: 0.0.0.0, port: ...}）。
     * <p>⇒ ⇒ {@code SocketAddress} 重载**不经过名字解析** ⇒ ⇒ 免疫该劫持
     * ⇒ ⇒ 通道直连本测试的 server ⇒ **真 TCP + 真 HTTP/2 + 真 protobuf**。
     * <p>📌 <b>本类的 {@code @SpringBootTest} 上下文本身就会注册 devh 的 provider</b>
     * ⇒ ⇒ 本类里那条「默认 scheme 被抢走」的环境事实断言（见类末尾）**在本上下文里恒成立**
     * ⇒ ⇒ 它是「为什么必须用 SocketAddress」的<b>可执行证据</b>。
     */
    private static ManagedChannel channel() {
        return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", GRPC_PORT))
                .usePlaintext()
                .build();
    }

    @Test
    @DisplayName("🔴 真传输：devh 的 gRPC server 真在监听，且本域的 service 真注册进了 registry")
    void grpcServerIsReallyListeningAndRegistered() {
        ManagedChannel channel = channel();
        try {
            // ⚠️ 不做「连得上就算」的弱断言 ⇒ 直接**调一次真实方法**：
            //    调到方法体才有资格说「service 注册上了」；
            //    连不上则这里就是 UNAVAILABLE（不是本用例要断言的错误码）⇒ 读数会立刻不同。
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> FavoriteServiceGrpc.newBlockingStub(channel)
                            .createFavoriteFolder(CreateFavoriteFolderReq.newBuilder()
                                    .setUserId(0L)
                                    .setName("probe")
                                    .build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode(),
                    "能调到方法体 ⇒ devh 的 service registry 里确实有本域的 service；"
                            + "若没注册，gRPC 会回 UNIMPLEMENTED ⇒ 实际=" + e.getStatus());
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    @DisplayName("🔴 真传输：userId<=0 ⇒ INVALID_ARGUMENT，且 description 能反查出业务码 5003")
    void invalidUserIdKeepsBizCodeRecoverableOverTheWire() {
        ManagedChannel channel = channel();
        try {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> FavoriteServiceGrpc.newBlockingStub(channel)
                            .createFavoriteFolder(CreateFavoriteFolderReq.newBuilder()
                                    .setUserId(0L)
                                    .setName("probe")
                                    .build()));

            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode(),
                    "B 侧 Go：userId <= 0 ⇒ InvalidArgument（create_favorite_folder_logic.go:51-57）");
            // 🔴 **本类最关键的一条**：业务码只能从 description 反查 ⇒ 线路形态上必须保得住
            assertEquals(ErrCodes.ERROR_INVALID_PARAM, GrpcErrorMapper.bizCodeFromError(e),
                    "从线路回来的异常里必须能反查出 5003 ⇒ 说明 description 的文案在真传输后仍然对得上"
                            + "（若被包装成 ErrCode:X 形态，反查会失败 ⇒ 落到 1015）");
            assertNotNull(e.getStatus().getDescription(),
                    "description 必须存在（favorite 域的 gRPC 错误契约靠它反查业务码）");
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    @DisplayName("🔴 真传输：folder 名为空 ⇒ 5202（description 反查，与 5003 区分得开）")
    void emptyFolderNameKeepsItsOwnBizCodeOverTheWire() {
        ManagedChannel channel = channel();
        try {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> FavoriteServiceGrpc.newBlockingStub(channel)
                            .createFavoriteFolder(CreateFavoriteFolderReq.newBuilder()
                                    .setUserId(7L)
                                    .setName("")
                                    .build()));

            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode(),
                    "B 侧 Go：normalizeFolderName(name) == \"\" ⇒ InvalidArgument"
                            + "（create_favorite_folder_logic.go:59-66）");
            assertEquals(ErrCodes.ERROR_FAVORITE_FOLDER_NAME_EMPTY, GrpcErrorMapper.bizCodeFromError(e),
                    "必须是 5202（不是 5003）⇒ 说明两个分支的 description 确实不同、且各自可反查");
            assertTrue(ErrCodes.ERROR_FAVORITE_FOLDER_NAME_EMPTY != ErrCodes.ERROR_INVALID_PARAM,
                    "两个码必须不同（否则这条断言没有分辨力）");
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    @DisplayName("🔴 真传输：错误映射是稳定的（同一请求两次 ⇒ 同一个 status 与业务码）")
    void errorMappingIsStableAcrossRepeatedCalls() {
        ManagedChannel channel = channel();
        try {
            FavoriteServiceGrpc.FavoriteServiceBlockingStub stub =
                    FavoriteServiceGrpc.newBlockingStub(channel);
            CreateFavoriteFolderReq req = CreateFavoriteFolderReq.newBuilder()
                    .setUserId(0L)
                    .setName("probe")
                    .build();

            StatusRuntimeException first = assertThrows(StatusRuntimeException.class,
                    () -> stub.createFavoriteFolder(req));
            StatusRuntimeException second = assertThrows(StatusRuntimeException.class,
                    () -> stub.createFavoriteFolder(req));

            assertEquals(first.getStatus().getCode(), second.getStatus().getCode(),
                    "两次调用的 status 必须一致 ⇒ 映射不是随机的");
            assertEquals(GrpcErrorMapper.bizCodeFromError(first), GrpcErrorMapper.bizCodeFromError(second),
                    "两次调用反查出的业务码必须一致");
        } finally {
            channel.shutdownNow();
        }
    }

    /**
     * 📌 <b>环境事实记录</b>：devh 的 discovery provider 把 grpc 的<b>默认名字解析 scheme</b> 抢走了。
     *
     * <p>⇒ 这是「<b>为什么客户端必须用 {@code forAddress(SocketAddress)}</b>」的<b>可执行证据</b>
     * ⇒ ⇒ 本仓先例：{@code service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java:304-316}。
     *
     * <p>⚠️ <b>若本断言变红</b> ⇒ 说明 devh / SCA 的注册行为变了
     * ⇒ ⇒ 复核「用 SocketAddress 重载」是否仍必要（无害，但结论要更新）。
     */
    @Test
    @DisplayName("📌 环境事实：devh 的 discovery provider 把 grpc 的默认名字解析 scheme 抢走了")
    void defaultNameResolverSchemeIsHijackedByDevh() {
        String defaultScheme = NameResolverRegistry.getDefaultRegistry().getDefaultScheme();

        assertTrue(!"dns".equals(defaultScheme),
                "本上下文里 grpc 的默认名字解析 scheme 已被 devh 的 DiscoveryClientResolverFactory "
                        + "抢走（priority 6 > DNS 5）⇒ 用 forAddress(String,int) 的客户端会被劫持到 "
                        + "服务发现 ⇒ 报『No servers found for <host>:<port>』这种像『服务端没起来』的错。"
                        + "⚠️ 若本断言变红，说明 devh/SCA 的注册行为变了 ⇒ 复核 forAddress(SocketAddress) "
                        + "是否仍必要（无害，但结论要更新）");
    }
}
