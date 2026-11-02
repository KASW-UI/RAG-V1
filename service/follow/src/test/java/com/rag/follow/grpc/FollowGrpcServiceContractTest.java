package com.rag.follow.grpc;

import com.rag.follow.FollowServiceApplication;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.GrpcErrorMapper;
import com.rag.follow.domain.service.FollowDomainService;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;

import com.rag.proto.follow.BaseResp;
import com.rag.proto.follow.FollowServiceGrpc;
import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.RelationReq;
import com.rag.proto.follow.UserListResp;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>follow 域 8 个 gRPC 用例的契约（真 gRPC server · 真 proto 编解码 · 真 PG）</b>。
 *
 * <h2>⚠️ 为什么用 in-process 而不是 TCP 端口</h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 起 TCP 端口会互相撞。
 * devh 的 {@code grpc.server.port=-1} ⇒ {@code ConditionalOnInterprocessServer} 不匹配
 * ⇒ <b>netty server 不创建</b>；配合 {@code grpc.server.in-process-name} ⇒ 只起 in-process server。
 * <p>⚠️ <b>in-process 仍然是「真 gRPC server」</b>：starter 的 {@code GrpcServiceDiscoverer} 扫描
 * {@code @GrpcService} → 注册 service definition → <b>真实分发</b> + <b>真实拦截器链</b>
 * （共享层 {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 挂的那条）
 * ⇒ ⇒ 本类验的是<b>框架机制真的生效</b>，不是「自己 new 一个对象自己调自己」。
 *
 * <h2>本类钉的 4 条</h2>
 * <ol>
 *   <li><b>8 个方法全部可被 starter 分发</b>（逐个真调一次 ⇒ 客户端拿到响应）。</li>
 *   <li><b>🔴 错误出口必须走 {@code onError}</b>：客户端拿到 {@link StatusRuntimeException}
 *       而不是「一个空响应」或「一个 5001 信封」。</li>
 *   <li><b>🔴 description 必须保留业务文案</b>：它是业务码的载体（丢了就还原不回去）。</li>
 *   <li><b>🔴 {@code bizCodeFromError} 的<b>文案反查优先于</b> gRPC 码 switch</b>：
 *       「PERMISSION_DENIED + description={@code blocked relation exists}」⇒ 反查出
 *       <b>5202</b>（而不是 switch 兜底的 5005）。</li>
 * </ol>
 */
@SpringBootTest(classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // ⚠️ 必须与下面的 IN_PROCESS_NAME 一致；名字 JVM 内全局唯一
                "grpc.server.in-process-name=follow-grpc-contract-test"
        })
@ActiveProfiles("test")
class FollowGrpcServiceContractTest {

    private static final String IN_PROCESS_NAME = "follow-grpc-contract-test";

    private static final AtomicLong UID_SEQ = new AtomicLong(8_400_000_000_000L);

    private static ManagedChannel channel;
    private static FollowServiceGrpc.FollowServiceBlockingStub stub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    /**
     * spy（不是 mock）：默认走<b>真</b>领域逻辑 ⇒ 本类的 8 个用例仍是端到端真触发；
     * 只有「未映射异常 ⇒ INTERNAL」那一条需要临时换掉领域层的行为。
     */
    @MockitoSpyBean
    private FollowDomainService domainService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void openChannel() {
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).usePlaintext().build();
        stub = FollowServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    // ==================================================================
    // 1. 8 个方法全部可被 starter 分发
    // ==================================================================

    @Test
    @DisplayName("🔴 8 个 RPC 方法全部可被 starter 分发（真 client → 真 server → 真方法体 → 真 PG）")
    void allEightMethodsAreDispatched() {
        long me = UID_SEQ.incrementAndGet();
        long target = UID_SEQ.incrementAndGet();
        RelationReq relation = RelationReq.newBuilder().setUserId(me).setTargetId(target).build();
        ListReq list = ListReq.newBuilder().setUserId(me).setOffset(0).setLimit(0).build();

        BaseResp follow = stub.follow(relation);
        assertThat(follow.getCode()).isEqualTo(ErrCodes.SUCCESS);
        assertThat(follow.getMsg()).isEqualTo("OK");

        BaseResp unfollow = stub.unfollow(relation);
        assertThat(unfollow.getCode()).isEqualTo(ErrCodes.SUCCESS);

        BaseResp block = stub.block(relation);
        assertThat(block.getCode()).isEqualTo(ErrCodes.SUCCESS);

        BaseResp unblock = stub.unblock(relation);
        assertThat(unblock.getCode()).isEqualTo(ErrCodes.SUCCESS);

        UserListResp followList = stub.getFollowList(list);
        assertThat(followList.getCode()).isEqualTo(ErrCodes.SUCCESS);

        UserListResp blockList = stub.getBlockList(list);
        assertThat(blockList.getCode()).isEqualTo(ErrCodes.SUCCESS);

        UserListResp followerList = stub.getFollowerList(list);
        assertThat(followerList.getCode()).isEqualTo(ErrCodes.SUCCESS);

        RecommendResp recommendations = stub.getRecommendations(list);
        assertThat(recommendations.getCode()).isEqualTo(ErrCodes.SUCCESS);
    }

    // ==================================================================
    // 2. 错误出口：onError + description 保留业务文案
    // ==================================================================

    @Test
    @DisplayName("🔴 错误出口必须 onError：客户端拿到 StatusRuntimeException（不是空响应、不是 5001 信封）")
    void errorPathGoesThroughOnError() {
        long me = UID_SEQ.incrementAndGet();
        RelationReq selfRelation = RelationReq.newBuilder().setUserId(me).setTargetId(me).build();

        assertThatThrownBy(() -> stub.follow(selfRelation))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription())
                            .as("description 必须保留业务文案（文案是业务码的载体）")
                            .isEqualTo("self relation is not allowed");
                });
    }

    @Test
    @DisplayName("🔴 拉黑闸门 ⇒ PERMISSION_DENIED + description=\"blocked relation exists\"")
    void blockedRelationProducesPermissionDeniedWithBusinessDescription() {
        long me = UID_SEQ.incrementAndGet();
        long target = UID_SEQ.incrementAndGet();
        insertBlock(target, me);   // 对方拉黑我 ⇒ 双向命中

        assertThatThrownBy(() -> stub.follow(RelationReq.newBuilder()
                .setUserId(me).setTargetId(target).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(e.getStatus().getDescription()).isEqualTo("blocked relation exists");
                });
    }

    // ==================================================================
    // 3. 🔴 反查优先级：文案反查优先于 gRPC 码 switch
    // ==================================================================

    @Test
    @DisplayName("🔴🔴 bizCodeFromError 的**文案反查**优先于 gRPC 码 switch：真 gRPC 的 5202 被反查回 5202（不是 5005）")
    void descriptionReverseLookupWinsOverGrpcCodeSwitchOnRealGrpcCall() {
        long me = UID_SEQ.incrementAndGet();
        long target = UID_SEQ.incrementAndGet();
        insertBlock(target, me);

        // 真 gRPC 调用 ⇒ 客户端真的收到 PERMISSION_DENIED + "blocked relation exists"
        StatusRuntimeException thrown = null;
        try {
            stub.follow(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());
        } catch (StatusRuntimeException e) {
            thrown = e;
        }
        assertThat(thrown).as("必须真的失败").isNotNull();
        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);

        // 🔴 关键断言：反查出来的必须是 **5202**。
        //   若实现把「gRPC 码 switch」放在「文案反查」之前 ⇒ 这里会得到 5005（PERMISSION_DENIED 的兜底）
        //   ⇒ 与 B 侧（BizCodeFromError 先查 bizCodeByMsg）不符。
        assertThat(GrpcErrorMapper.bizCodeFromError(thrown))
                .as("文案反查命中 ⇒ 5202；退化成 5005 就说明反查被 gRPC 码 switch 盖住了")
                .isEqualTo(ErrCodes.ERROR_RELATION_BLOCKED);
        assertThat(GrpcErrorMapper.bizCodeFromError(thrown)).isNotEqualTo(ErrCodes.ERROR_FORBIDDEN);
    }

    // ==================================================================
    // 4. 未映射异常 ⇒ INTERNAL
    // ==================================================================

    @Test
    @DisplayName("🔴 未映射异常 ⇒ onError(INTERNAL + \"internal server error\")（不被改写成 UNKNOWN）")
    void unmappedThrowableConvergesToInternal() {
        Mockito.doThrow(new IllegalStateException("boom"))
                .when(domainService).follow(Mockito.any());

        assertThatThrownBy(() -> stub.follow(RelationReq.newBuilder().setUserId(1L).setTargetId(2L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(e.getStatus().getDescription()).isEqualTo("internal server error");
                });
    }

    // ==================================================================
    // 5. P0-2 在 wire 上的形态
    // ==================================================================

    @Test
    @DisplayName("🔴 P0-2：空 user_ids 在 **protobuf wire 上字段消失**（⇒ api 层反序列化得 nil ⇒ JSON null）")
    void emptyUserIdsFieldIsAbsentOnTheWire() {
        long me = UID_SEQ.incrementAndGet();

        UserListResp resp = stub.getFollowList(ListReq.newBuilder().setUserId(me).setOffset(0).setLimit(0).build());

        assertThat(resp.getUserIdsCount()).isZero();
        // ⚠️ 逐字节看 wire：repeated 字段为空 ⇒ **整个字段不上 wire**
        //   ⇒ api 进程反序列化得到 nil ⇒ encoding/json 输出 "user_ids":null（不是 []）
        List<Integer> wireFields = wireFieldNumbers(resp.toByteArray());
        assertThat(wireFields).contains(1, 2);   // code / msg 在
        assertThat(wireFields)
                .as("user_ids（field number = 3）必须**不在** wire 上（proto3 repeated 为空 ⇒ 字段消失）")
                .doesNotContain(3);
    }

    /**
     * 极简 protobuf wire 解析：只取「出现了哪些 field number」。
     *
     * <p>⚠️ 为什么不直接 {@code toString()} / 十六进制 contains：那是子串匹配，
     * 会被其它字段的字节内容误命中 ⇒ 假绿/假红都可能。
     */
    private static List<Integer> wireFieldNumbers(byte[] bytes) {
        List<Integer> fields = new ArrayList<>();
        int i = 0;
        while (i < bytes.length) {
            int tag = bytes[i++] & 0xFF;
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;
            fields.add(fieldNumber);
            switch (wireType) {
                case 0: { // varint
                    while ((bytes[i++] & 0xFF) >= 0x80) {
                        // 继续读
                    }
                    break;
                }
                case 2: { // length-delimited
                    int len = 0;
                    int shift = 0;
                    while (true) {
                        int b = bytes[i++] & 0xFF;
                        len |= (b & 0x7F) << shift;
                        shift += 7;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                    }
                    i += len;
                    break;
                }
                default:
                    throw new IllegalStateException("不支持的 wire type: " + wireType);
            }
        }
        return fields;
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private void insertBlock(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO block_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?)",
                userId, targetId, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }
}
