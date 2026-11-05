package com.rag.favorite.http;

import com.rag.common.exception.BusinessException;
import com.rag.favorite.domain.service.FavoriteDomainService;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.favorite.service.FavoriteApiSupport;
import com.rag.favorite.support.NacosStubConfiguration;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.UserServiceGrpc;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h2>🔴 真触发：api 面自持的出向上界到了 ⇒ 业务码落 <b>1015</b>（DRIFT-FAV-014 的修法）</h2>
 *
 * <p><b>这条测试盯的是什么</b>：
 * D2 域内直连把 B 侧「api → favorite-rpc」那一跳<b>取消</b>之后，B 侧 api 面
 * <b>自己那一跳的 2s 客户端 cap</b>也一起消失了 ⇒ ⇒ 慢下游时超时被<b>领域层</b>接住
 * ⇒ ⇒ 业务码从 B 侧的 <b>1015</b> 变成 C 侧的 <b>5001</b>。
 * ⇒ ⇒ 本域的修法 = <b>api 面自持一个同值（2000ms）上界</b>，超时落 <b>1015</b>
 * ⇒ ⇒ <b>复刻 B 侧 api→rpc 那一跳的 cap</b>（B 侧 {@code favorite-api/.../FavoriteRpcClient.java:108-115}
 * 自述「2s 先到 ⇒ api 返回 200 + 业务码 1015」）。
 *
 * <h2>🔴 为什么必须真 gRPC 传输（不是 mock）</h2>
 * <p>本机制依赖三件事同时成立，<b>mock 掉任何一件都验不到</b>：
 * <ol>
 *   <li><b>出向调用真的按 ThreadLocal 里的预算到期</b> —— 共享层
 *       {@code OutboundDeadlineInterceptor} 在<b>真实传输</b>上把剩余预算压进
 *       {@code CallOptions} ⇒ ⇒ 非 mock 才有；</li>
 *   <li><b>超时异常真的从领域层冒出来</b> —— {@code FavoriteUserGuard#onDownstreamFailure}
 *       把 DEADLINE_EXCEEDED 翻成 5001；</li>
 *   <li><b>api 面的预算起点真的比客户端 cap 更早</b> ⇒ ⇒ 恒定先到。</li>
 * </ol>
 * ⇒ ⇒ 本类起一个<b>真的 gRPC 服务器</b>（真 TCP + 真 HTTP/2 + 真 protobuf），
 * 它的 {@code GetUser} <b>睡 5 秒</b> ⇒ ⇒ 下游真的慢 ⇒ ⇒ 真的撞预算。
 *
 * <h2>🔴 三条断言，缺一不可</h2>
 * <table border="1">
 *   <caption>三条断言各自排掉一种「假绿」</caption>
 *   <tr><th>断言</th><th>期望</th><th>排掉什么</th></tr>
 *   <tr><td>① <b>api 面</b>（经 {@code invokeRpc}）</td><td><b>1015</b></td>
 *       <td>「根本没绑预算」⇒ 会落 5001 ⇒ 红</td></tr>
 *   <tr><td>② <b>gRPC 面</b>（直调领域层）</td><td><b>5001</b></td>
 *       <td>「把领域层映射也改成 1015」⇒ 会落 1015 ⇒ 红
 *       （<b>这条正是「不拿 gRPC 面换 api 面」的守门</b>）</td></tr>
 *   <tr><td>③ <b>非超时</b>（预算没到期就失败）</td><td><b>5001</b></td>
 *       <td>「1015 变成无条件兜底」⇒ 会落 1015 ⇒ 红</td></tr>
 * </table>
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一类测试</b> —— B 侧<b>没有任何</b>测「api 面的出向 cap
 *       先到 ⇒ 业务码 1015」的测试 ⇒ ⇒ 本类是 C 侧新增的证据面。</li>
 *   <li><b>语义还一样吗</b>：不适用（本类只断言 C 侧行为，不判定 B/C 等价；
 *       B/C 等价由 {@code tools/difftest/favorite/} 的场景轮 S3 承担）。</li>
 *   <li><b>B 出处</b>：被测契约 = B 侧 {@code favorite-api/.../FavoriteRpcClient.java:108-115}
 *       （oracle 自述）+ {@code favorite-api/.../application.yml:196-197}（api 面 cap = 2000）
 *       + {@code favorite-common/.../FavoriteErrorCode.java:47}（CODE_SERVER_BUSY = 1015）。</li>
 * </ol>
 */
@SpringBootTest(
        classes = {com.rag.favorite.FavoriteServiceApplication.class,
                NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.article-rpc.address=static://127.0.0.1:65535",
                "grpc.client.article-rpc.negotiation-type=plaintext",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 🔴 C 侧 FavoriteApiProperties 的密钥守卫是 fail-closed 的（缺值 ⇒ 启动失败）
                "favorite-api.user-auth.access-secret=test-only-favorite-jwt-secret-at-least-32-bytes",
                // ⚠️ 数据库**故意不可达**：慢下游那一支在**任何 DB 访问之前**就超时了
                //    （领域层第 3 步就是 user-rpc ⇒ 之后才是查重/插入）
                //    ⇒ ⇒ 本测试**不需要真 PG** ⇒ ⇒ 轻且确定。
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
                "favorite.fact.legacy-preflight.enabled=false",
                // 🔴 **钉死** api 面的出向上界（= B 侧 api→rpc 那一跳的 cap）
                "rag.protection.outbound-deadline.cap-millis=2000"
        })
class FavoriteApiHopDeadlineContractTest {

    /**
     * 慢下游服务器要睡多久 —— 必须<b>远大于</b>出向上界（2s），否则「慢」不成立。
     */
    private static final long SLOW_MS = 5_000L;

    /**
     * 慢下游 user-rpc 服务器的端口 —— 从本域<b>专用端口段</b>里挑（不用 {@code port=0}）：
     * {@code port=0} 会落进 OS 临时端口段（Docker 随机发布端口用的同一段）⇒ ⇒ 别的会话可能先占住
     * ⇒ ⇒ 「偶尔红、换个时间又绿」的抖动。
     */
    private static final int SLOW_USER_RPC_PORT = com.rag.favorite.support.RealTomcatPort.pick();

    /**
     * 慢下游 user-rpc 服务器（<b>真</b> gRPC server）。
     *
     * <p>⚠️ **必须起在 {@code @DynamicPropertySource} 之前** —— 客户端地址要在上下文创建时就知道端口。
     */
    private static final Server SLOW_USER_RPC_SERVER = startSlowUserRpcServer();

    /**
     * 本域自己的 gRPC server 端口（{@code webEnvironment=NONE} 时 devh 仍会起 gRPC server）。
     *
     * <p>🔴 <b>必须在慢下游服务器起完之后再挑</b> —— 否则两个 {@code pick()} 可能挑到同一个口
     * ⇒ ⇒ 第二个 bind 失败 ⇒ ⇒ 「端口冲突」又变成一个假红来源。
     */
    private static final int GRPC_PORT = com.rag.favorite.support.RealTomcatPort.pick();

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("grpc.server.port", () -> String.valueOf(GRPC_PORT));
        registry.add("grpc.client.user-rpc.address",
                () -> "static://127.0.0.1:" + SLOW_USER_RPC_PORT);
        registry.add("grpc.client.user-rpc.negotiation-type", () -> "plaintext");
    }

    @AfterAll
    static void stopSlowUserRpcServer() {
        if (SLOW_USER_RPC_SERVER != null) {
            SLOW_USER_RPC_SERVER.shutdownNow();
        }
    }

    @Autowired
    private FavoriteApiSupport support;

    @Autowired
    private FavoriteDomainService domainService;

    // ══════════════════════════ 断言 ══════════════════════════

    @Test
    @DisplayName("🔴 api 面：慢下游 ⇒ 本层上界先到 ⇒ 业务码 1015（对齐 B 侧 api→rpc 那一跳的 cap）")
    void apiFaceBudgetExpiryYieldsServerBusy() {
        com.rag.proto.favorite.CreateFavoriteFolderReq req =
                com.rag.proto.favorite.CreateFavoriteFolderReq.newBuilder()
                        .setUserId(1001L)
                        .setName("HopDeadline")
                        .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> support.invokeRpc(() -> domainService.createFavoriteFolder(req), 1001L, null));

        assertEquals(1015, ex.getCode(),
                "api 面的出向上界到了 ⇒ 业务码必须是 1015（CODE_SERVER_BUSY）"
                        + " ⇒ 与 B 侧 api→rpc 那一跳的 cap 同形");
    }

    @Test
    @DisplayName("🔴 反向对照：gRPC 面（直调领域层）⇒ 同一个慢下游仍是 5001（不许拿 gRPC 面换 api 面）")
    void grpcFaceKeepsServerCommon() {
        com.rag.proto.favorite.CreateFavoriteFolderReq req =
                com.rag.proto.favorite.CreateFavoriteFolderReq.newBuilder()
                        .setUserId(1001L)
                        .setName("HopDeadlineGrpcFace")
                        .build();

        Throwable thrown = assertThrows(Throwable.class,
                () -> domainService.createFavoriteFolder(req));

        assertEquals(5001, bizCodeOf(thrown),
                "gRPC 面（rpc 对 rpc）必须仍是 5001 —— B 侧 gRPC 面也是 5001"
                        + " ⇒ ⇒ 「改 domain 层映射」那条路是拿一个面换另一个面 ⇒ 不取");
    }

    @Test
    @DisplayName("🔴 非超时对照：预算没到期就失败 ⇒ 仍按领域层的码走（不许 1015 无条件兜底）")
    void nonTimeoutFailureKeepsItsOwnBizCode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> support.invokeRpc(() -> {
                    throw FavoriteExceptions.internalServerCommon();
                }, 1001L, null));

        assertEquals(5001, ex.getCode(),
                "本层预算**没**到期 ⇒ 必须走领域层自己的码（5001）"
                        + " ⇒ ⇒ 1015 分支必须是有条件的，不是无条件兜底");
    }

    @Test
    @DisplayName("🔴 api→领域层这一跳必须自带预算载体（出向调用按它到期 ⇒ 与 B 侧 api→rpc 那一跳同形）")
    void apiHopCarriesItsOwnBudgetCarrier() {
        long[] seen = new long[1];
        support.invokeRpc(() -> {
            seen[0] = com.rag.common.observability.GoStyleRequestDeadline.remainingNanos();
            return null;
        }, 1001L, null);

        assertTrue(seen[0] >= 0L,
                "api→领域层这一跳**没有**绑定预算载体 ⇒ 出向调用拿不到这一跳自己的预算"
                        + " ⇒ ⇒ 「api 面自持上界」这条就没了 ⇒ ⇒ B 侧 api→rpc 那一跳的 cap 无从复刻");
        assertTrue(seen[0] <= 2_000_000_000L,
                "本层预算应 <= 2000ms（= B 侧 api 面那一跳的 cap），实测 " + seen[0] + "ns");
    }

    @Test
    @DisplayName("📌 环境事实：慢下游服务器真的在监听（否则上面三条会假绿成「连不上」）")
    void slowServerIsReallyListening() {
        assertNotNull(SLOW_USER_RPC_SERVER);
        assertTrue(!SLOW_USER_RPC_SERVER.isShutdown(),
                "慢下游服务器已停 ⇒ 上面三条会退化成「连不上」⇒ 断言失去意义");
        assertTrue(SLOW_USER_RPC_SERVER.getPort() == SLOW_USER_RPC_PORT,
                "慢下游服务器端口对不上");
    }

    // ══════════════════════════ 工具 ══════════════════════════

    /** 从异常链里找出 {@code FavoriteExceptions.Rpc} 的业务码（找不到 ⇒ -1）。 */
    private static int bizCodeOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof FavoriteExceptions.Rpc business) {
                return business.getBizCode();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return -1;
    }

    /**
     * 起一个<b>真的</b>慢下游 user-rpc 服务器（真 TCP + 真 HTTP/2 + 真 protobuf）。
     *
     * <p>⚠️ grpc-java 1.63 起 {@code usePlaintext()} <b>已被移除</b> ⇒ ⇒ 用
     * {@code forPort(port, InsecureServerCredentials.create())}。
     */
    private static Server startSlowUserRpcServer() {
        try {
            return NettyServerBuilder
                    .forPort(SLOW_USER_RPC_PORT, io.grpc.InsecureServerCredentials.create())
                    .addService(new SlowUserService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(
                    "慢下游 user-rpc 服务器起不来: " + e.getMessage());
        }
    }

    /**
     * 慢下游的 {@code GetUser} —— <b>睡够久</b>，逼出向预算到期。
     *
     * <p>⚠️ 响应内容<b>无所谓</b>：预算先到 ⇒ 调用根本等不到这个响应。
     */
    private static final class SlowUserService extends UserServiceGrpc.UserServiceImplBase {

        @Override
        public void getUser(GetUserReq request, StreamObserver<GetUserResp> responseObserver) {
            try {
                TimeUnit.MILLISECONDS.sleep(SLOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responseObserver.onNext(GetUserResp.newBuilder().setFound(true).build());
            responseObserver.onCompleted();
        }
    }
}
