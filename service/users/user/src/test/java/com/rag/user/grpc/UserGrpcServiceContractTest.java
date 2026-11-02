package com.rag.user.grpc;

import com.rag.proto.user.CreateUserReq;
import com.rag.proto.user.CreateUserResp;
import com.rag.proto.user.DeleteUserReq;
import com.rag.proto.user.DeleteUserResp;
import com.rag.proto.user.GetAvatarHistoryReq;
import com.rag.proto.user.GetAvatarHistoryResp;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.LoginReq;
import com.rag.proto.user.LoginResp;
import com.rag.proto.user.LogoutReq;
import com.rag.proto.user.LogoutResp;
import com.rag.proto.user.SelectAvatarReq;
import com.rag.proto.user.SelectAvatarResp;
import com.rag.proto.user.UpdateUserReq;
import com.rag.proto.user.UpdateUserResp;
import com.rag.proto.user.UploadAvatarReq;
import com.rag.proto.user.UploadAvatarResp;
import com.rag.proto.user.UserInfo;
import com.rag.proto.user.UserServiceGrpc;
import com.rag.user.domain.service.UserDomainService;
import com.rag.user.support.TestPostgres;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>UserService gRPC 契约（真 gRPC server · 真 proto 编解码 · 真 starter 分发）</b>。
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
 * <h2>本类守的 3 条</h2>
 * <ol>
 *   <li><b>9 个方法全部可被 starter 分发</b>（逐个真调一次 ⇒ 客户端拿到响应）。</li>
 *   <li><b>🔴 {@code StatusRuntimeException} 原样透传</b>：code 与 description 都保留
 *       （B 侧 {@code UserServiceGrpcImpl} 的第一层 catch）。</li>
 *   <li><b>🔴 未映射异常收敛成 INTERNAL</b>，description = {@code "类名: 消息"}
 *       （B 侧 {@code DRIFT-02}：缺这层 ⇒ grpc-java 会发 UNKNOWN ⇒ 调用方拿到的码退化）。</li>
 * </ol>
 *
 * <h2>⚠️ 唯一被替身的边界</h2>
 * <p>领域服务（{@link UserDomainService}）用 mock —— 它是<b>被测对象之外的边界</b>
 * （与 B 侧把 {@code UserRpcClient} 替掉同型）。<b>领域服务自身的行为</b>由本域 HTTP 契约测试
 * （真 PG + 真 bcrypt）覆盖 ⇒ 两者合起来是完整证据链。
 */
@SpringBootTest(properties = "grpc.server.in-process-name=user-grpc-contract-test")
@ActiveProfiles("test")
class UserGrpcServiceContractTest {

    /**
     * 必须与 {@code @SpringBootTest(properties=...)} 里的 {@code grpc.server.in-process-name} 一致。
     *
     * <p>⚠️ <b>名字必须 JVM 内唯一</b>：gRPC 的 in-process server 名字是<b>全局注册</b>的
     * ⇒ 同名第二个会报 {@code IOException: name already registered} ⇒ ⇒ 本域只有这一个测试类
     * 起 in-process server ⇒ 名字带类名后缀 ⇒ 不与其它上下文撞。
     */
    private static final String IN_PROCESS_NAME = "user-grpc-contract-test";

    private static ManagedChannel channel;
    private static UserServiceGrpc.UserServiceBlockingStub stub;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @MockitoBean
    private UserDomainService userDomainService;

    @Autowired
    private UserGrpcService userGrpcService;

    @BeforeAll
    static void openChannel() {
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).usePlaintext().build();
        stub = UserServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    // ============== 1. 9 个方法全部可被 starter 分发 ==============

    @Test
    @DisplayName("🔴 9 个 RPC 方法全部可被 starter 分发（真 client → 真 server → 真方法体）")
    void allNineMethodsAreDispatched() {
        Mockito.reset(userDomainService);

        // --- login ---
        Mockito.when(userDomainService.login(Mockito.any())).thenReturn(
                LoginResp.newBuilder().setUid(1L).setStatus(0L).build());
        LoginResp loginResp = stub.login(LoginReq.newBuilder().setUsername("u").setPassword("p").build());
        assertThat(loginResp.getUid()).isEqualTo(1L);
        assertThat(loginResp.getStatus()).isEqualTo(0L);
        Mockito.verify(userDomainService).login(Mockito.argThat(
                r -> "u".equals(r.getUsername()) && "p".equals(r.getPassword())));

        // --- register ---
        Mockito.when(userDomainService.register(Mockito.any())).thenReturn(
                CreateUserResp.newBuilder().setUid(2L).build());
        assertThat(stub.register(CreateUserReq.newBuilder().setUsername("u2").build()).getUid()).isEqualTo(2L);

        // --- logout ---
        Mockito.when(userDomainService.logout(Mockito.any())).thenReturn(
                LogoutResp.newBuilder().setSuccess(true).build());
        assertThat(stub.logout(LogoutReq.newBuilder().setToken("tk").build()).getSuccess()).isTrue();

        // --- getUser ---
        Mockito.when(userDomainService.getUser(Mockito.any())).thenReturn(
                GetUserResp.newBuilder().setFound(true)
                        .setUser(UserInfo.newBuilder().setUid(3L).setUsername("u3")).build());
        GetUserResp getUserResp = stub.getUser(GetUserReq.newBuilder().setUid(3L).build());
        assertThat(getUserResp.getFound()).isTrue();
        assertThat(getUserResp.getUser().getUsername()).isEqualTo("u3");

        // --- updateUser ---
        Mockito.when(userDomainService.updateUser(Mockito.any())).thenReturn(
                UpdateUserResp.newBuilder().setUser(UserInfo.newBuilder().setUid(4L)).build());
        assertThat(stub.updateUser(UpdateUserReq.newBuilder().setUid(4L).build()).getUser().getUid()).isEqualTo(4L);

        // --- deleteUser ---
        Mockito.when(userDomainService.deleteUser(Mockito.any())).thenReturn(
                DeleteUserResp.newBuilder().setSuccess(true).build());
        assertThat(stub.deleteUser(DeleteUserReq.newBuilder().setUid(5L).build()).getSuccess()).isTrue();

        // --- selectAvatar ---
        Mockito.when(userDomainService.selectAvatar(Mockito.any())).thenReturn(
                SelectAvatarResp.newBuilder().setAvatarUrl("http://x/a.png").build());
        assertThat(stub.selectAvatar(SelectAvatarReq.newBuilder().setUid(6L).setHistoryId("h").build())
                .getAvatarUrl()).isEqualTo("http://x/a.png");

        // --- uploadAvatar ---
        Mockito.when(userDomainService.uploadAvatar(Mockito.any())).thenReturn(
                UploadAvatarResp.newBuilder().setAvatarUrl("http://x/b.png").build());
        assertThat(stub.uploadAvatar(UploadAvatarReq.newBuilder().setUid(7L).setAvatarUrl("http://x/b.png").build())
                .getAvatarUrl()).isEqualTo("http://x/b.png");

        // --- getAvatarHistory ---
        Mockito.when(userDomainService.getAvatarHistory(Mockito.any())).thenReturn(
                GetAvatarHistoryResp.newBuilder().build());
        assertThat(stub.getAvatarHistory(GetAvatarHistoryReq.newBuilder().setUid(8L).setLimit(30).build())
                .getListCount()).isZero();
    }

    // ============== 2. StatusRuntimeException 原样透传 ==============

    @Test
    @DisplayName("🔴 StatusRuntimeException 原样透传：code 与 description 都保留（不被改写成 INTERNAL）")
    void statusRuntimeExceptionIsPassedThrough() {
        Mockito.reset(userDomainService);
        Mockito.when(userDomainService.getUser(Mockito.any())).thenThrow(
                Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException());

        assertThatThrownBy(() -> stub.getUser(GetUserReq.newBuilder().setUid(404L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(e.getStatus().getDescription()).isEqualTo("用户不存在");
                });
    }

    // ============== 3. 未映射异常 ⇒ INTERNAL ==============

    @Test
    @DisplayName("🔴 未映射异常收敛成 INTERNAL，description = \"类名: 消息\"（B 侧 DRIFT-02）")
    void unmappedThrowableConvergesToInternal() {
        Mockito.reset(userDomainService);
        Mockito.when(userDomainService.login(Mockito.any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> stub.login(LoginReq.newBuilder().setUsername("u").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    StatusRuntimeException e = (StatusRuntimeException) t;
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(e.getStatus().getDescription()).isEqualTo("IllegalStateException: boom");
                });
    }

    @Test
    @DisplayName("🔴 UserGrpcService bean 就在容器里（@GrpcService 被 starter 发现）")
    void grpcServiceBeanIsPresent() {
        assertThat(userGrpcService).isNotNull();
    }
}
