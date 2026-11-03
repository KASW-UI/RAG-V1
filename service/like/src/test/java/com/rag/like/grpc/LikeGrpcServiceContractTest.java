package com.rag.like.grpc;

import com.rag.like.LikeServiceApplication;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.GrpcErrFactory;
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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>like 域 6 个 gRPC 用例的契约（真 gRPC server · 真 proto 编解码 · 真 PG · 真 Redis）</b>。
 *
 * <h2>⚠️ 为什么用 in-process 而不是 TCP 端口</h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 起 TCP 端口会互相撞。
 * devh 的 {@code grpc.server.port=-1} ⇒ {@code ConditionalOnInterprocessServer} 不匹配
 * ⇒ <b>netty server 不创建</b>；配合 {@code grpc.server.in-process-name} ⇒ 只起 in-process server。
 * <p>⚠️ <b>in-process 仍然是「真 gRPC server」</b>：starter 的 {@code GrpcServiceDiscoverer} 扫描
 * {@code @GrpcService} → 注册 service definition → <b>真实分发</b> + <b>真实拦截器链</b>
 * ⇒ ⇒ 本类验的是<b>框架机制真的生效</b>，不是「自己 new 一个对象自己调自己」。
 *
 * <h2>本类钉的 4 条</h2>
 * <ol>
 *   <li><b>6 个方法全部可被 starter 分发</b>（逐个真调一次 ⇒ 客户端拿到响应）。</li>
 *   <li><b>🔴 错误出口必须走 {@code onError}</b>：客户端拿到 {@link StatusRuntimeException}
 *       而不是「一个空响应」。</li>
 *   <li><b>🔴 description 必须保留业务文案</b>：它是业务码的载体（丢了就还原不回去）。</li>
 *   <li><b>🔴 trailer {@code rag-like-biz-code} 必须带业务码</b>（客户端据此还原码值）。</li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的 gRPC 形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // ⚠️ 必须与下面的 IN_PROCESS_NAME 一致；名字 JVM 内全局唯一
                "grpc.server.in-process-name=like-grpc-contract-test"
        })
@ActiveProfiles("test")
class LikeGrpcServiceContractTest {

    private static final String IN_PROCESS_NAME = "like-grpc-contract-test";

    private static final AtomicLong SEQ = new AtomicLong(8_500_000_000_000L);

    private static ManagedChannel channel;
    private static LikeServiceGrpc.LikeServiceBlockingStub stub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @BeforeAll
    static void openChannel() {
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).usePlaintext().build();
        stub = LikeServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    // ==================================================================
    // 1. 6 个方法全部可被 starter 分发
    // ==================================================================

    @Test
    @DisplayName("🔴 6 个 RPC 方法全部可被 starter 分发（真 client → 真 server → 真方法体 → 真 PG/Redis）")
    void allSixMethodsAreDispatched() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-grpc-" + uid;
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

        // ⚠️ likeAction 会写 Redis + 推 Kafka（无 broker ⇒ 异步失败、不改 HTTP/gRPC 结果）
        LikeActionResp action = stub.likeAction(LikeActionReq.newBuilder()
                .setTargetType(targetType).setTargetId(targetId).setUserId(uid)
                .setAuthorId(uid + 1).setActionType(ActionType.ACTION_LIKE).build());
        assertThat(action).as("likeAction 必须真的返回").isNotNull();
    }

    // ==================================================================
    // 2~4. 错误出口：onError + description + trailer
    // ==================================================================

    @Test
    @DisplayName("🔴 业务错误 ⇒ onError（不是空响应），description 带业务文案，trailer 带业务码")
    void businessErrorGoesThroughOnErrorWithCodeAndMessage() {
        long uid = SEQ.incrementAndGet();

        // 空 target_ids ⇒ 领域层抛 inputWrong ⇒ 业务码 2001 + 文案「输入需要查询的ID为空」
        assertThatThrownBy(() -> stub.getLikeCount(GetLikeCountReq.newBuilder()
                .setTargetType("article-grpc-err-" + uid).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getDescription())
                            .as("description 必须保留业务文案（它是业务码的载体）")
                            .isEqualTo("输入需要查询的ID为空");
                    Metadata trailers = Status.trailersFromThrowable(e);
                    assertThat(trailers).isNotNull();
                    assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                            .as("trailer 必须带业务码（客户端据此还原码值）")
                            .isEqualTo(String.valueOf(ErrCodes.ERROR_INPUT_WRONG));
                });
    }

    @Test
    @DisplayName("🔴 gRPC status 码：2001 ⇒ INVALID_ARGUMENT；其余业务码 ⇒ UNKNOWN")
    void grpcStatusCodeMapping() {
        long uid = SEQ.incrementAndGet();

        assertThatThrownBy(() -> stub.getLikeCount(GetLikeCountReq.newBuilder()
                .setTargetType("article-grpc-status-" + uid).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .as("2001 必须映射成 INVALID_ARGUMENT（逐条照抄 B 侧 LikeGrpcError）")
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("🔴 业务码可从 trailer 原样还原（客户端拿到的就是 2001，不是兜底 1015）")
    void bizCodeIsRecoverableFromTrailer() {
        long uid = SEQ.incrementAndGet();

        assertThatThrownBy(() -> stub.getLikeCount(GetLikeCountReq.newBuilder()
                .setTargetType("article-grpc-recover-" + uid).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(
                        GrpcErrFactory.bizCodeFromError((Throwable) t))
                        .as("业务码必须能原样还原成 2001")
                        .isEqualTo(ErrCodes.ERROR_INPUT_WRONG));
    }
}
