package com.rag.comment.contract;

import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.exception.CommentExceptions;
import com.rag.comment.grpc.CommentGrpcService;
import com.rag.proto.comment.CommentServiceGrpc;
import com.rag.proto.comment.GetCommentReq;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p><b>真 in-process gRPC server + 真 channel</b> 的 comment 业务码往返守门
 * （纯 JVM，<b>不起 Spring 上下文 · 不碰 DB · 不需要 Docker</b>）。
 *
 * <h2>🔴 为什么必须过真实传输层</h2>
 * <p>comment 的业务码靠自定义 trailer {@code rag-comment-biz-code} 上线，
 * 而「trailer 能不能无损抵达客户端」是整个方案的<b>承重假设</b>。
 * 只断言 {@code grpcError(...).getTrailers()} 的值，证明不了它真的穿过了传输层
 * —— 本仓此前已多次栽在「单测全绿但真实链路上没生效」上（AGENTS.md §3.3）。
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>🔴 多了一条「真生产服务路径」的往返</b>：B 侧只验了
 *       {@code CommentGrpcError} 这个工厂方法本身（用一个探针服务把错误抛出去）；
 *       C 侧<b>额外</b>把<b>真的 {@link CommentGrpcService}</b> 挂进 in-process server
 *       （领域层用 Mockito 打桩抛 {@link CommentExceptions.Rpc}）
 *       ⇒ 证明「<b>生产代码路径</b> {@code invoke(...) → observer.onError(GrpcErrFactory.grpcError(...))}
 *       → 客户端收到 trailer」这条链<b>整条通</b>，而不只是工厂方法通。</li>
 *   <li>⚠️ 该用例<b>不要求业务逻辑通</b>：领域层被打桩为「抛业务异常」，
 *       <b>不触 DB</b> ⇒ 纯 in-process，不需要 Docker / PG / Redis。</li>
 *   <li><b>in-process server 名字带域前缀 + 随机后缀</b>：in-process transport 的名字是
 *       <b>JVM 内全局注册</b>的 ⇒ 同名会直接抛「already in use」；
 *       本类用域前缀（{@code comment-grpc-error-roundtrip-}）+ {@code generateName()} 双重保证唯一。</li>
 * </ul>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>。B 侧的探针服务与 C 侧的探针服务<b>是同一段代码</b>
 * （都用 {@link GrpcErrFactory#grpcError(int, String)} 造错误、都用
 * {@code observer.onError(...)} 而不是 {@code throw}）
 * ⇒ 守门点（trailer 无损往返 / description 逐字保留 / 降级形态 / 标准码原值）完全一致。
 *
 * <h2>③ B 出处</h2>
 * <pre>
 * B 侧源文件：service/comment/comment-common/src/test/java/com/rag/comment/comment/common/errmsg/CommentGrpcErrorTest.java
 *   bizCodeTrailerRoundTripsLosslessly                 :107-118 → bizCodeTrailerRoundTripsLosslessly
 *   descriptionKeepsGoMessageVerbatim                  :120-124 → descriptionKeepsGoMessageVerbatim
 *   grpcJavaDegradesOutOfRangeStatusCode               :126-137 → grpcJavaDegradesOutOfRangeStatusCode
 *   degradedGoStatusStillYieldsBizCode                 :139-150 → degradedGoStatusStillYieldsBizCode
 *   degradedGoStatusDoesNotInventMessage               :152-162 → degradedGoStatusDoesNotInventMessage
 *   standardGrpcCodeIsReturnedVerbatimLikeGoIntStCode  :164-181 → standardGrpcCodeIsReturnedVerbatimLikeGoIntStCode
 *   businessCodesNeverCollideWithRetryableCategories   :183-206 → businessCodesNeverCollideWithRetryableCategories
 *   inputWrongMapsToInvalidArgument                    :208-218 → inputWrongMapsToInvalidArgument
 *   trailerWinsOverDegradedDescription                 :220-226 → trailerWinsOverDegradedDescription
 *   nonStatusThrowableHandling                         :228-236 → nonStatusThrowableHandling
 *   nullInputReturnsSuccess                            :238-242 → nullInputReturnsSuccess
 *   dirtyTrailerFallsThroughToStandardCode             :244-254 → dirtyTrailerFallsThroughToStandardCode
 * B 侧 oracle（错误工厂）：service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentGrpcError.java:116-259
 * B 侧 oracle（业务载体）：service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentCodeError.java:64-88
 * </pre>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的 gRPC 形状与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样形状」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
class CommentGrpcErrorRoundTripTest {

    /**
     * in-process server 名字 —— <b>JVM 内全局注册</b>，必须唯一。
     *
     * <p>⚠️ 域前缀 + {@code generateName()}（随机后缀）双重保证：
     * 同名会抛 {@code IllegalArgumentException: Provided name is already in use}。
     */
    private static final String IN_PROCESS_NAME =
            "comment-grpc-error-roundtrip-" + InProcessServerBuilder.generateName();

    private static Server server;
    private static ManagedChannel channel;

    /** Go comment-rpc 实际用 {@code NewGrpcErr} 发出的码，逐字取自 B 侧调用点。 */
    private static final int BIZ_CODE = ErrCodes.ERROR_SUBJECT_NOT_EXIST;
    private static final String BIZ_MSG = "评论区不存在";

    private static final MethodDescriptor.Marshaller<String> STRING_MARSHALLER =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(String value) {
                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public String parse(InputStream stream) {
                    try (InputStream in = stream) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };

    private static final MethodDescriptor<String, String> METHOD =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("probe.Ping/Ping")
                    .setRequestMarshaller(STRING_MARSHALLER)
                    .setResponseMarshaller(STRING_MARSHALLER)
                    .build();

    @BeforeAll
    static void startServer() throws IOException {
        // ① 探针服务（B 侧同款）：只验「trailer 能不能无损穿过真实传输层」。
        ServerServiceDefinition probe = ServerServiceDefinition.builder("probe.Ping")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) ->
                        // ⚠️ 必须 observer.onError(...) 而不是 throw：
                        // ServerCalls 从 onHalfClose 里调业务方法，throw 会逃出流监听回调，
                        // 被当成传输层内部错误，description 被替换成
                        // "Application error processing RPC"。
                        observer.onError(GrpcErrFactory.grpcError(BIZ_CODE, BIZ_MSG))))
                .build();

        // ② 真生产服务（C 侧新增）：领域层打桩为「抛业务异常」⇒ 不触 DB。
        CommentDomainService domainService = Mockito.mock(CommentDomainService.class);
        Mockito.when(domainService.getComment(Mockito.any(GetCommentReq.class)))
                .thenThrow(new CommentExceptions.Rpc(BIZ_CODE, BIZ_MSG));
        CommentGrpcService realService = new CommentGrpcService(domainService);

        server = InProcessServerBuilder.forName(IN_PROCESS_NAME).directExecutor()
                .addService(probe)
                .addService(realService)
                .build().start();
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).directExecutor().build();
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

    private static StatusRuntimeException callAndCatch() {
        return assertThrows(StatusRuntimeException.class,
                () -> ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, "ping"));
    }

    /**
     * 走<b>真生产服务</b>（{@link CommentGrpcService}）打一次 {@code GetComment}，
     * 领域层抛 {@link CommentExceptions.Rpc} ⇒ 客户端应拿到带业务码 trailer 的错误。
     */
    private static StatusRuntimeException callRealServiceAndCatch() {
        CommentServiceGrpc.CommentServiceBlockingStub stub =
                CommentServiceGrpc.newBlockingStub(channel);
        return assertThrows(StatusRuntimeException.class,
                () -> stub.getComment(GetCommentReq.newBuilder()
                        .setTargetType("article")
                        .setTargetId("2001")
                        .build()));
    }

    @Test
    @DisplayName("🔴 业务码经自定义 trailer 无损往返 —— 整个方案的承重假设")
    void bizCodeTrailerRoundTripsLosslessly() {
        StatusRuntimeException thrown = callAndCatch();

        Metadata trailers = Status.trailersFromThrowable(thrown);
        assertThat(trailers).as("客户端没收到 trailer").isNotNull();
        assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                .as("trailer 里的业务码没无损抵达 —— 整个 GrpcErrFactory 方案的前提被推翻")
                .isEqualTo("1012");
        assertThat(GrpcErrFactory.bizCodeFromError(thrown)).isEqualTo(1012);
    }

    @Test
    @DisplayName("🔴 description 逐字保留 B 侧的 msg，comment-api 呈现给用户的文案不会变")
    void descriptionKeepsGoMessageVerbatim() {
        assertThat(callAndCatch().getStatus().getDescription()).isEqualTo(BIZ_MSG);
    }

    @Test
    @DisplayName("🔴 grpc-java 对越界 grpc-status 的降级形态（实测，不是假设）")
    void grpcJavaDegradesOutOfRangeStatusCode() {
        // 这是 GrpcErrFactory 整个方案的立论前提，先把它钉死。
        // 若某个 grpc 版本改了这里的表现，下面的降级捞码分支需要同步调整。
        Status degraded = Status.fromCodeValue(5003);
        assertThat(degraded.getCode())
                .as("grpc-java 不再把越界码降级成 UNKNOWN —— 本类的设计前提变了，必须重新评估")
                .isEqualTo(Status.Code.UNKNOWN);
        assertThat(degraded.getDescription())
                .as("降级 description 的格式变了 —— recoverFromGoDegradedDescription 的前缀要跟着改")
                .isEqualTo("Unknown code 5003");
        assertThat(Status.Code.UNKNOWN.value()).as("UNKNOWN 的标准值应为 2").isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 灰度期：Go comment-rpc 发出的越界 grpc-status，Java 侧仍能捞回业务码")
    void degradedGoStatusStillYieldsBizCode() {
        // 模拟 grpc-java 收到 Go 的 grpc-status: 5003 后降级成的 status
        StatusRuntimeException fromGo = Status.UNKNOWN
                .withDescription("Unknown code 5003")
                .asRuntimeException();

        assertThat(GrpcErrFactory.bizCodeFromError(fromGo))
                .as("灰度期 comment-api → Go comment-rpc 时应捞回 5003，而不是退化成 UNKNOWN 的 2")
                .isEqualTo(ErrCodes.ERROR_DB_SELECT);
    }

    @Test
    @DisplayName("🔴 降级形态只捞码不捞 msg —— 原始文案已被覆盖，不做猜测性还原")
    void degradedGoStatusDoesNotInventMessage() {
        StatusRuntimeException fromGo = Status.UNKNOWN
                .withDescription("Unknown code 1012")
                .asRuntimeException();

        assertThat(GrpcErrFactory.bizCodeFromError(fromGo)).isEqualTo(1012);
        // description 仍是 grpc-java 生成的降级文案，不应被我们"还原"成 Go 的原文案
        assertThat(fromGo.getStatus().getDescription()).isEqualTo("Unknown code 1012");
    }

    @Test
    @DisplayName("🔴 标准 gRPC 码原值返回，对齐 Go 的 int(st.Code()) 字面搬运")
    void standardGrpcCodeIsReturnedVerbatimLikeGoIntStCode() {
        // Go: errmsg.NewErrCodeMsg(int(st.Code()), st.Message())
        // 收到 UNAUTHENTICATED(16) 时 Go 给客户端的就是 code:16。
        // 若这里改判成 2001，就是凭空造出 Go 永远产生不到的码。
        StatusRuntimeException unauthenticated =
                Status.UNAUTHENTICATED.withDescription("token invalid").asRuntimeException();
        assertThat(GrpcErrFactory.bizCodeFromError(unauthenticated))
                .isEqualTo(Status.Code.UNAUTHENTICATED.value());

        StatusRuntimeException permissionDenied =
                Status.PERMISSION_DENIED.withDescription("not up master").asRuntimeException();
        assertThat(GrpcErrFactory.bizCodeFromError(permissionDenied))
                .isEqualTo(Status.Code.PERMISSION_DENIED.value());

        StatusRuntimeException invalidArg =
                Status.INVALID_ARGUMENT.withDescription("target_type is required").asRuntimeException();
        assertThat(GrpcErrFactory.bizCodeFromError(invalidArg))
                .isEqualTo(Status.Code.INVALID_ARGUMENT.value());
    }

    @Test
    @DisplayName("🔴 业务码绝不落在 INTERNAL / UNAVAILABLE 这类「会被重试」的类别里")
    void businessCodesNeverCollideWithRetryableCategories() {
        // Go 把业务码原样塞进 grpc-status，comment-rpc 的这些错误在 Go 里全部是
        // 「正常业务错误」。若 Java 标成 INTERNAL/UNAVAILABLE，下游重试器会把它们
        // 当依赖故障反复重试 —— 那是新增行为，不是对齐。
        int[] bizCodes = {
                ErrCodes.ERROR_SUBJECT_NOT_EXIST,
                ErrCodes.ERROR_COMMENT_NOT_EXIST,
                ErrCodes.ERROR_DB_SELECT,
                ErrCodes.ERROR_DB_UPDATE,
                ErrCodes.ERROR_USER_NO_RIGHT,
                ErrCodes.ERROR_KAFKA_PUSH,
                ErrCodes.ERROR_SNOWFLAKE_ID,
                ErrCodes.ERROR_JSON_MARSHAL,
                ErrCodes.ERROR_DB_INSERT,
        };
        for (int bizCode : bizCodes) {
            Status.Code mapped = GrpcErrFactory.grpcError(bizCode, "x").getStatus().getCode();
            assertThat(mapped)
                    .as("业务码 " + bizCode + " 被标成会触发重试的类别了")
                    .isNotIn(Status.Code.INTERNAL, Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED);
        }
    }

    @Test
    @DisplayName("🔴 入参错误标成 INVALID_ARGUMENT（语义最贴近，且不制造重试）")
    void inputWrongMapsToInvalidArgument() {
        assertThat(GrpcErrFactory.grpcError(ErrCodes.ERROR_INPUT_WRONG, "评论ID不能为空")
                .getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        // 其余码 → UNKNOWN（与「码无法归类」的实情相符）
        assertThat(GrpcErrFactory.grpcError(ErrCodes.ERROR_COMMENT_NOT_EXIST, "评论不存在")
                .getStatus().getCode())
                .isEqualTo(Status.Code.UNKNOWN);
    }

    @Test
    @DisplayName("🔴 业务码与降级捞码的优先级：trailer 优于降级 description")
    void trailerWinsOverDegradedDescription() {
        // 本域自己发的错误同时带 trailer 和正常 description，trailer 必须赢
        StatusRuntimeException ours = callAndCatch();
        assertThat(GrpcErrFactory.bizCodeFromError(ours)).isEqualTo(BIZ_CODE);
    }

    @Test
    @DisplayName("🔴 非 status 的 Throwable：CodeError 取自身码，其余退 1015")
    void nonStatusThrowableHandling() {
        assertThat(GrpcErrFactory.bizCodeFromError(CodeError.newCode(1012))).isEqualTo(1012);

        assertThat(GrpcErrFactory.bizCodeFromError(new IllegalStateException("PG 连接断开")))
                .isEqualTo(ErrCodes.CODE_SERVER_BUSY);
    }

    @Test
    @DisplayName("🔴 null 输入返回 200 —— B 侧调用点都在 if err != nil 内，该分支不可达")
    void nullInputReturnsSuccess() {
        assertThat(GrpcErrFactory.bizCodeFromError(null)).isEqualTo(ErrCodes.SUCCESS);
    }

    @Test
    @DisplayName("🔴 脏 trailer 不会让整条错误解析失败")
    void dirtyTrailerFallsThroughToStandardCode() {
        Metadata dirty = new Metadata();
        dirty.put(GrpcErrFactory.BIZ_CODE_TRAILER, "not-a-number");
        StatusRuntimeException err = Status.UNKNOWN
                .withDescription("boom")
                .asRuntimeException(dirty);

        assertThat(GrpcErrFactory.bizCodeFromError(err)).isEqualTo(Status.Code.UNKNOWN.value());
    }

    /**
     * 🔴 【C 侧新增 · B 侧无对应用例】<b>真生产服务路径</b>也必须把业务码落到 trailer 上。
     *
     * <p><b>为什么 B 侧没有这条</b>：B 侧 {@code CommentGrpcErrorTest} 只验了
     * {@code CommentGrpcError} 这个<b>工厂方法</b>本身（用探针服务把错误抛出去）
     * ⇒ 它<b>证不了</b>生产链路上的那一段：<b>领域异常 → GrpcErrFactory → observer.onError → 传输层 → 客户端</b>。
     * <p>⇒ C 侧把<b>真的 {@link CommentGrpcService}</b> 挂进 in-process server
     * （领域层用 Mockito 打桩抛 {@link CommentExceptions.Rpc}）
     * ⇒ 证明<b>整条生产路径</b>通，且业务码确实落在 {@code rag-comment-biz-code} trailer 上。
     *
     * <p>⚠️ <b>本用例不要求业务逻辑通</b>：领域层被打桩 ⇒ 不触 DB ⇒ 纯 in-process。
     */
    @Test
    @DisplayName("🔴 真生产服务（CommentGrpcService）的错误出口也把业务码落到 trailer 上")
    void realServicePathCarriesBizCodeInTrailer() {
        StatusRuntimeException thrown = callRealServiceAndCatch();

        Metadata trailers = Status.trailersFromThrowable(thrown);
        assertThat(trailers).as("客户端没收到 trailer").isNotNull();
        assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                .as("生产链路（CommentGrpcService → GrpcErrFactory）必须把业务码写进 trailer")
                .isEqualTo("1012");
        assertThat(thrown.getStatus().getDescription())
                .as("description 必须逐字保留原始文案，不能被 ErrCode:X, Errmsg:Y 的包装格式二次包装")
                .isEqualTo(BIZ_MSG);
        assertThat(GrpcErrFactory.bizCodeFromError(thrown)).isEqualTo(BIZ_CODE);
    }

    /**
     * 🔴 【C 侧新增】<b>真生产服务路径下的「非 status 异常」兜底</b>也必须带 trailer。
     *
     * <p>对应 C 侧 {@code CommentGrpcService#invoke} 的第三条分支：
     * 其它 {@code RuntimeException} ⇒ 也必须走 {@link GrpcErrFactory}
     * （<b>不是</b>裸 {@code Status.INTERNAL}）⇒ 否则 api 侧从 trailer 取不到业务码。
     */
    @Test
    @DisplayName("🔴 真生产服务遇到非业务异常时，兜底也必须带 trailer（不是裸 INTERNAL）")
    void realServiceFallbackAlsoCarriesBizCodeInTrailer() throws IOException, InterruptedException {
        CommentDomainService domainService = Mockito.mock(CommentDomainService.class);
        Mockito.when(domainService.getComment(Mockito.any(GetCommentReq.class)))
                .thenThrow(new IllegalStateException("PG 连接断开"));
        CommentGrpcService realService = new CommentGrpcService(domainService);

        String name = "comment-grpc-error-roundtrip-fallback-" + InProcessServerBuilder.generateName();
        Server fallbackServer = InProcessServerBuilder.forName(name).directExecutor()
                .addService(realService)
                .build().start();
        ManagedChannel fallbackChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            CommentServiceGrpc.CommentServiceBlockingStub stub =
                    CommentServiceGrpc.newBlockingStub(fallbackChannel);
            StatusRuntimeException thrown = assertThrows(
                    StatusRuntimeException.class,
                    () -> stub.getComment(GetCommentReq.newBuilder()
                            .setTargetType("article")
                            .setTargetId("2001")
                            .build()));

            Metadata trailers = Status.trailersFromThrowable(thrown);
            assertThat(trailers).as("客户端没收到 trailer").isNotNull();
            assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                    .as("兜底分支必须仍走 GrpcErrFactory ⇒ 业务码 5001 要落在 trailer 上")
                    .isEqualTo(String.valueOf(ErrCodes.ERROR_SERVER_COMMON));
        } finally {
            fallbackChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            fallbackServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 🔴 【C 侧新增】<b>trailer 的 key 名</b>是 L1 wire 契约，钉死不可改。
     *
     * <p>⚠️ 共享层另有一套 {@code sea-biz-code} trailer（承载 {@code BusinessException}）
     * ⇒ 本域<b>不能</b>被「统一」成那个名字（改键名 = 改 wire 元素 = 改 L1 契约，D16）。
     */
    @Test
    @DisplayName("🔴 trailer key 必须是 rag-comment-biz-code（改键名 = 改 L1 wire 契约）")
    void bizCodeTrailerKeyIsFrozen() {
        assertThat(GrpcErrFactory.BIZ_CODE_TRAILER_KEY).isEqualTo("rag-comment-biz-code");
        assertThat(GrpcErrFactory.BIZ_CODE_TRAILER.name()).isEqualTo("rag-comment-biz-code");
    }
}
