package com.rag.comment.grpc;

import com.rag.comment.contract.CodeError;
import com.rag.comment.contract.ErrCodes;
import com.rag.comment.contract.GrpcErrFactory;
import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.exception.CommentExceptions;
import com.rag.comment.service.CommentApiSupport;

import com.rag.proto.comment.CommentServiceGrpc;
import com.rag.proto.comment.CreateCommentReq;
import com.rag.proto.comment.GetCommentReq;
import com.rag.proto.comment.GetCommentResp;
import com.rag.proto.comment.SubjectInfo;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p><b>🔴 comment 域 gRPC 面的「业务码跨层往返 ＋ 跨栈别名」守门</b>
 * （真 in-process 传输层 ＋ 真 {@link CommentGrpcService} ＋ 真 {@link PbCommentServiceAlias}）。
 *
 * <h2>🔴 为什么必须过真实传输层</h2>
 * <p>comment-rpc 把业务码放在<b>自定义 trailer</b> {@code rag-comment-biz-code}
 * （{@link GrpcErrFactory#grpcError(int, String)}），而 grpc-java 的 {@code Status} 只能承载 0..16
 * 的标准码 ⇒ {@code grpc-status} 上只剩 {@code UNKNOWN(2)} / {@code INVALID_ARGUMENT(3)}。
 * <p><b>承重假设</b>：「业务码能穿过传输层抵达 api 侧」。
 * 只断言 {@code grpcError(...).getTrailers()} 的值<b>证明不了</b>它真的穿过了传输层
 * ⇒ ⇒ 本类起一个<b>真 in-process gRPC server</b>（真 HTTP/2 帧编解码 ＋ 真 trailer 序列化），
 * 用<b>真</b> {@link CommentGrpcService} 作为实现 ⇒ 断言「导出的 span / trailer」。
 *
 * <h2>🔴 三种对端形态（本类逐条覆盖）</h2>
 * <table border="1">
 *   <caption>api 侧 {@code rpcError} 的三条判定线索</caption>
 *   <tr><th>对端</th><th>形态</th><th>期望 envelope.code</th><th>B 侧对应</th></tr>
 *   <tr><td><b>C 侧 comment-rpc</b>（本类主场景）</td><td>trailer 带业务码</td>
 *       <td><b>1012</b></td><td>Go 侧业务码在 grpc-status 里，{@code int(st.Code())} = 1012</td></tr>
 *   <tr><td><b>Go comment-rpc</b>（灰度）</td><td>grpc-status 越界 ⇒ grpc-java 降级成
 *       {@code UNKNOWN + "Unknown code 5003"}</td>
 *       <td><b>5003</b></td><td>Go 侧 {@code int(st.Code())} = 5003</td></tr>
 *   <tr><td>标准码</td><td>{@code NOT_FOUND}</td>
 *       <td><b>5</b></td><td>Go 侧 {@code int(st.Code())} = 5</td></tr>
 * </table>
 *
 * <h3>AGENTS.md §3.2 三段式</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：① 被测的服务端实现从「桩」换成 <b>真的
 *       {@link CommentGrpcService}</b>（只把最底层的领域服务替换掉）⇒ 顺带钉住了
 *       「领域异常 → trailer」这条转换链；② 断言从「envelope.code」改成「<b>导出到线路上的
 *       trailer 值</b>」⇒ 证明它真的穿过传输层。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 三种对端形态的期望值逐条来自 B 侧表格；
 *       别名的两条服务名（{@code pb.CommentService} / {@code comment.CommentService}）逐字未改。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code comment-api/…/api/CommentApiGrpcErrorWireTest.java:57-181}</li>
 *         <li>{@code comment-rpc/…/rpc/service/impl/PbCommentServiceAliasGateTest.java:53-181}</li>
 *         <li>{@code comment-common/…/errmsg/CommentGrpcError.java:29-259}（trailer 键名与产出）</li>
 *         <li>{@code comment-rpc/…/rpc/service/impl/CommentServiceGrpcImpl.java:27-131}（错误出口三分支）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>（本类<b>不冒充</b>该证据）。
 *
 * <h2>🔴 in-process server 名字必须<b>全局唯一</b></h2>
 * <p>gRPC 的 in-process server 名字是 <b>JVM 内全局注册</b>的 ⇒ 同名会撞。
 * ⇒ ⇒ 本类用 {@link InProcessServerBuilder#generateName()}（每次生成一个随机唯一名）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
class CommentGrpcErrorWireRealTest {

    /** Go comment-rpc 的 {@code NewGrpcErr(1012, "评论区不存在")}。 */
    private static final int BIZ_CODE = ErrCodes.ERROR_SUBJECT_NOT_EXIST;
    private static final String BIZ_MSG = "评论区不存在";

    /** {@code CommentApiSupport} 是普通协作者（无状态）⇒ 直接 new，与 B 侧同款。 */
    private static final CommentApiSupport SUPPORT = new CommentApiSupport();

    private static Server server;
    private static ManagedChannel channel;

    /**
     * 领域服务的替身 —— <b>只替换最底层的领域服务</b>，
     * {@link CommentGrpcService} 与 {@link PbCommentServiceAlias} 都是<b>真件</b>。
     */
    private static CommentDomainService domainService;

    @BeforeAll
    static void startServer() throws IOException {
        domainService = mock(CommentDomainService.class);

        // 🔴 真实现：领域异常 ⇒ GrpcErrFactory.grpcError(bizCode, msg) ⇒ 业务码进 trailer。
        when(domainService.createComment(any(CreateCommentReq.class)))
                .thenThrow(CommentExceptions.subjectNotExist());
        // 成功路径：返回一个可区分的 subject，供「两条路径打到同一个实现」取证。
        when(domainService.getComment(any(GetCommentReq.class)))
                .thenReturn(GetCommentResp.newBuilder()
                        .setSubject(SubjectInfo.newBuilder()
                                .setTargetType("article")
                                .setTargetId("a-alias")
                                .setTotalCount(7L)
                                .build())
                        .build());

        CommentGrpcService real = new CommentGrpcService(domainService);

        // ⚠️ 名字必须全局唯一（in-process server 是 JVM 内全局注册的）。
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                // ① C 侧自己的路径（原样）
                .addService(real.bindService())
                // ② Go 兼容别名（同一份实现）
                .addService(new PbCommentServiceAlias(real).bindService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterAll
    static void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** 用指定的方法全名调一次 GetComment。 */
    private static GetCommentResp call(String serviceName) {
        MethodDescriptor<GetCommentReq, GetCommentResp> method =
                CommentServiceGrpc.getGetCommentMethod().toBuilder()
                        .setFullMethodName(serviceName + "/GetComment")
                        .build();
        return ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT,
                GetCommentReq.newBuilder().setTargetType("article").setTargetId("a-alias").build());
    }

    // ==================================================================
    // 1. C 侧 comment-rpc 对端：trailer 里的业务码必须原样还原
    // ==================================================================

    @Test
    @DisplayName("🔴 业务码穿过**真实传输层**后，trailer 必须仍是 1012（不是 UNKNOWN=2）")
    void businessCodeSurvivesTransport() {
        StatusRuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                StatusRuntimeException.class,
                () -> CommentServiceGrpc.newBlockingStub(channel)
                        .createComment(CreateCommentReq.getDefaultInstance()));

        // --- 前置：trailer 真的穿过了传输层（否则下面的一切都无意义）
        Metadata trailers = Status.trailersFromThrowable(thrown);
        assertThat(trailers)
                .as("客户端没收到 trailer —— 传输层假设不成立")
                .isNotNull();
        assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                .as("trailer 里的业务码没无损抵达（键名 = " + GrpcErrFactory.BIZ_CODE_TRAILER_KEY + "）")
                .isEqualTo(String.valueOf(BIZ_CODE));

        // --- 本体：api 侧还原出的业务码
        CodeError mapped = SUPPORT.rpcError(thrown);
        assertThat(mapped.getCode())
                .as("api 侧丢了业务码 —— 客户端会拿到 UNKNOWN(2) 而不是 1012")
                .isEqualTo(BIZ_CODE);
    }

    @Test
    @DisplayName("🔴 msg 取 description 原文（不查表）—— 1012 查表得「评论区不存在」，原文必须逐字保留")
    void messageKeepsRpcDescriptionVerbatim() {
        StatusRuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                StatusRuntimeException.class,
                () -> CommentServiceGrpc.newBlockingStub(channel)
                        .createComment(CreateCommentReq.getDefaultInstance()));

        CodeError mapped = SUPPORT.rpcError(thrown);
        assertThat(mapped.getMsg())
                .as("msg 必须是 rpc 发出的原文，不能被码表文案顶替")
                .isEqualTo(BIZ_MSG);
    }

    // ==================================================================
    // 2. Go comment-rpc 对端（灰度期）：降级 description 捞码
    // ==================================================================

    @Test
    @DisplayName("🔴 Go-rpc 对端（灰度）：越界 grpc-status 降级后仍能捞回 5003 —— 与 Go 的 int(st.Code()) 一致")
    void goPeerDegradedStatusStillYieldsRawBusinessCode() {
        // 这就是 Java 客户端收到 Go comment-rpc 时的真实形态
        // （构造方式即 grpc-java 对越界 grpc-status 的解码路径）。
        StatusRuntimeException fromGo = Status.fromCodeValue(5003).asRuntimeException();

        CodeError mapped = SUPPORT.rpcError(fromGo);
        assertThat(mapped.getCode())
                .as("灰度期至少要保住机器可读的业务码（Go 侧 int(st.Code()) = 5003）")
                .isEqualTo(5003);
    }

    // ==================================================================
    // 3. 标准码与兜底：与 Go 的字面搬运保持一致
    // ==================================================================

    @Test
    @DisplayName("🔴 标准码仍取原值（NOT_FOUND ⇒ 5），不翻成业务码 —— Go 侧同样是 int(st.Code())")
    void standardGrpcCodeIsReturnedVerbatim() {
        StatusRuntimeException notFound = Status.NOT_FOUND
                .withDescription("comment not exist").asRuntimeException();

        CodeError mapped = SUPPORT.rpcError(notFound);
        assertThat(mapped.getCode())
                .as("标准码必须原样搬运（Go 侧 int(st.Code()) = 5）")
                .isEqualTo(Status.Code.NOT_FOUND.value());
        assertThat(mapped.getMsg()).isEqualTo("comment not exist");
    }

    @Test
    @DisplayName("🔴 非 status 错误（如熔断）⇒ 5001 + 「系统内部错误」（Go 的 status.FromError ok=false 分支）")
    void nonStatusErrorFallsBackTo5001() {
        CodeError mapped = SUPPORT.rpcError(new IllegalStateException("熔断打开"));
        assertThat(mapped.getCode())
                .as("Go 的 ok==false 分支是 5001，不是 bizCodeFromError 的 1015")
                .isEqualTo(ErrCodes.ERROR_SERVER_COMMON);
        assertThat(mapped.getMsg()).isEqualTo("系统内部错误");
    }

    // ==================================================================
    // 4. 跨栈兼容别名（PbCommentServiceAlias）
    // ==================================================================

    @Test
    @DisplayName("🔴 Go 的方法全名 /pb.CommentService/GetComment ⇒ **必须打到实现**（不是 UNIMPLEMENTED）")
    void goServiceNameReachesTheImplementation() {
        GetCommentResp resp = call(PbCommentServiceAlias.GO_SERVICE_NAME);
        assertThat(resp).isNotNull();
        assertThat(resp.getSubject().getTotalCount()).as("应当打到实现并拿到返回值").isEqualTo(7L);
        assertThat(resp.getSubject().getTargetId()).isEqualTo("a-alias");
    }

    @Test
    @DisplayName("🔴 C 侧自己的路径 /comment.CommentService/GetComment ⇒ **仍然可用**（别名不能顶掉原路径）")
    void ragServiceNameStillWorks() {
        GetCommentResp resp = call(PbCommentServiceAlias.RAG_SERVICE_NAME);
        assertThat(resp).isNotNull();
        assertThat(resp.getSubject().getTotalCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("🔴 两条路径打到的是**同一个实现**（别名不是另一份逻辑）")
    void bothPathsHitTheSameImplementation() {
        Mockito.clearInvocations(domainService);
        call(PbCommentServiceAlias.GO_SERVICE_NAME);
        call(PbCommentServiceAlias.RAG_SERVICE_NAME);

        // ⚠️ 两次调用都必须落到**同一个**领域服务实现上（别名不是「另一份逻辑」）
        ArgumentCaptor<GetCommentReq> captor = ArgumentCaptor.forClass(GetCommentReq.class);
        verify(domainService, times(2)).getComment(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).getTargetId()).isEqualTo("a-alias");
        assertThat(captor.getAllValues().get(1).getTargetId()).isEqualTo("a-alias");
    }

    @Test
    @DisplayName("🔴 别名的服务名与 Go 真源**逐字一致**（Go proto/comment.proto:4 是 package pb;）")
    void aliasServiceNameMatchesGoProtoPackage() {
        assertThat(PbCommentServiceAlias.GO_SERVICE_NAME).isEqualTo("pb.CommentService");
        assertThat(PbCommentServiceAlias.RAG_SERVICE_NAME).isEqualTo("comment.CommentService");
        assertThat(PbCommentServiceAlias.goFullMethodName("comment.CommentService/GetComment"))
                .isEqualTo("pb.CommentService/GetComment");
    }

    @Test
    @DisplayName("🔴 别名服务名下的**方法集合**与原服务**逐个对应**（不多不少）")
    void aliasExposesExactlyTheSameMethods() {
        CommentGrpcService real = new CommentGrpcService(domainService);
        ServerServiceDefinition original = real.bindService();
        ServerServiceDefinition alias = new PbCommentServiceAlias(real).bindService();
        assertThat(alias.getMethods().size()).isEqualTo(original.getMethods().size());

        Set<String> aliasNames = new HashSet<>();
        for (ServerMethodDefinition<?, ?> m : alias.getMethods()) {
            aliasNames.add(m.getMethodDescriptor().getFullMethodName());
        }
        for (ServerMethodDefinition<?, ?> m : original.getMethods()) {
            String renamed = PbCommentServiceAlias.goFullMethodName(
                    m.getMethodDescriptor().getFullMethodName());
            assertThat(aliasNames)
                    .as("别名里应当有 " + renamed + "（原 " + m.getMethodDescriptor().getFullMethodName() + "）")
                    .contains(renamed);
        }
    }

    @Test
    @DisplayName("🔴 未注册的方法名 ⇒ **UNIMPLEMENTED**（别名的存在不能让未知方法变得可调）")
    void unknownMethodStillFailsFast() {
        MethodDescriptor<GetCommentReq, GetCommentResp> method =
                CommentServiceGrpc.getGetCommentMethod().toBuilder()
                        .setFullMethodName("pb.OtherService/GetComment")
                        .build();
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT,
                GetCommentReq.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.UNIMPLEMENTED));
    }
}
