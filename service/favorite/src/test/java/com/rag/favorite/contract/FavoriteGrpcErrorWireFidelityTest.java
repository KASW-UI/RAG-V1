package com.rag.favorite.contract;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 🔴 补上 favorite 域错误契约的最后一环：
 * <b>{@code grpcError} 的 description 在真实 gRPC 链路上是否逐字节保真</b>。
 *
 * <h2>为什么这条必须单独验</h2>
 * <p>本域的核心机制是「<b>业务码不在 {@code grpc-status} 里，而在 description 文案里</b>」：
 * {@code 5304}（收藏暂不可撤回）走 {@code FAILED_PRECONDITION}，
 * 而它<b>不在</b> B 侧 {@code BizCodeFromError} 的 switch 白名单里
 * ⇒ <b>只能</b>靠文案反查还原 ⇒ <b>description 一旦在传输中变形，业务码就永久退化成 1015</b>。
 *
 * <p>而双侧探针验的是「<b>构造出的 Status 对象</b>」，
 * <b>不是</b>「真 channel 上传输后的字节」⇒ 探针全绿**证明不了**这一环。
 *
 * <h2>本类验什么</h2>
 * <ol>
 *   <li>服务端 {@code onError(grpcError(...))} ⇒
 *       客户端收到的 {@code Status.getDescription()} 与
 *       {@code ErrMsg.getMsg(码)} <b>逐字节相同</b>；</li>
 *   <li>该 description 经 {@link GrpcErrorMapper#bizCodeFromError} <b>能还原回原业务码</b>
 *       （往返不断链）；</li>
 *   <li>标准 gRPC 码本身也在链路上保真（不被改写成 {@code UNKNOWN}）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么必须用 {@code onError(...)} 而不是 {@code throw}</h2>
 * <p>{@link GrpcErrorMapper#grpcError} 的 javadoc 已写明：在
 * {@code UnaryServerCallListener.onHalfClose} 里 {@code throw} 会让异常逃出流监听回调
 * ⇒ 被当成传输层内部错误 ⇒ description 被替换成 {@code "Application error processing RPC"}
 * ⇒ <b>文案当场全丢</b>。本类用 {@code onError}（正确姿势），
 * 并把这条纪律写进断言注释里 —— 它是 like 域踩过的坑。
 *
 * <h2>§3.2 三段式留痕</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>无</b>（逐条照搬 B 侧断言）。
 *       表达层差异仅三处类型名替换：
 *       {@code FavoriteErrorCode.ERROR_DB_SELECT.getCode()} ⇒ {@code ErrCodes.ERROR_DB_SELECT}；
 *       {@code FavoriteErrorMessages.getMsg(...)} ⇒ {@code ErrMsg.getMsg(...)}；
 *       {@code FavoriteGrpcError.grpcError / bizCodeFromError} ⇒ {@code GrpcErrorMapper.grpcError / bizCodeFromError}。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两边都用 grpc-java 的 {@code io.grpc} 传输层
 *       （本仓 {@code grpc.version} = 1.63.0 ＝ B 侧同版本）
 *       ⇒ 「description 是否逐字节穿过 channel」这一事实与实现无关，只与运行时版本有关。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-common/src/test/java/com/rag/favorite/favorite/common/errmsg/FavoriteGrpcErrorWireFidelityTest.java:60-181}
 *       ＋ B 侧被测源 {@code .../favorite/common/errmsg/FavoriteGrpcError.java:94-243}。</li>
 * </ol>
 */
class FavoriteGrpcErrorWireFidelityTest {

    private static final String SERVICE_NAME = "favorite.WireFidelityProbe";
    private static final MethodDescriptor<String, String> METHOD =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "BlowUp"))
                    .setRequestMarshaller(new StringMarshaller())
                    .setResponseMarshaller(new StringMarshaller())
                    .build();

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws IOException {
        String uniqueName = InProcessServerBuilder.generateName();
        ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) ->
                        // ⚠️ 必须 onError(...)：throw 会让 description 被替换成
                        //    "Application error processing RPC"（见类注释）。
                        observer.onError(GrpcErrorMapper.grpcError(
                                Status.Code.INTERNAL, ErrCodes.ERROR_DB_SELECT))))
                .build();
        server = InProcessServerBuilder.forName(uniqueName).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(uniqueName).directExecutor().build();
    }

    @AfterEach
    void stopServer() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("🔴 真 channel 上 description 逐字节保真（业务码的载体不能变形）")
    void descriptionSurvivesTheRealChannel() {
        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, "probe"));

        String expected = ErrMsg.getMsg(ErrCodes.ERROR_DB_SELECT);
        String actual = Status.fromThrowable(thrown).getDescription();

        assertNotNull(actual, "description 丢了 ⇒ 业务码无法还原（会退化成 1015）");
        assertEquals(expected, actual, "description 文案被改动");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8),
                "description 必须**逐字节**相同（不是「看起来一样」）");
    }

    @Test
    @DisplayName("🔴 真 channel 上业务码能往返（description → 业务码）")
    void businessCodeRoundTripsThroughTheRealChannel() {
        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, "probe"));

        int restored = GrpcErrorMapper.bizCodeFromError(thrown);

        assertEquals(ErrCodes.ERROR_DB_SELECT, restored,
                "真链路上的错误必须能还原回原业务码；还原失败 ⇒ 一律退化成 1015");
    }

    @Test
    @DisplayName("🔴 标准 gRPC 码在链路上保真（不被改写成 UNKNOWN）")
    void grpcCodeSurvivesTheRealChannel() {
        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, "probe"));

        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(thrown).getCode(),
                "gRPC 码必须原样送达（B 侧 status.Error(codes.Internal, msg) 的 codes 部分同理）");
    }

    @Test
    @DisplayName("🔴 5304（唯一只能靠文案反查还原的码）在真 channel 上也能还原")
    void theReverseLookupOnlyCodeSurvivesTheRealChannel() throws IOException {
        // 5304 走 FAILED_PRECONDITION —— 不在 B 侧的 switch 白名单里 ⇒ 反查是唯一出路。
        // 这里单独起一个服务实例，因为它要抛的是**另一个**码。
        String uniqueName = InProcessServerBuilder.generateName();
        ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) ->
                        observer.onError(GrpcErrorMapper.grpcError(
                                Status.Code.FAILED_PRECONDITION,
                                ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED))))
                .build();
        Server other = InProcessServerBuilder.forName(uniqueName).directExecutor().addService(service).build().start();
        ManagedChannel otherChannel = InProcessChannelBuilder.forName(uniqueName).directExecutor().build();
        try {
            StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                    ClientCalls.blockingUnaryCall(otherChannel, METHOD, CallOptions.DEFAULT, "probe"));

            assertEquals(ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED,
                    GrpcErrorMapper.bizCodeFromError(thrown),
                    "5304 只能靠 description 反查还原 ⇒ 这一条是整个反查机制在真链路上的存在性证明");
            assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(thrown).getCode());
        } finally {
            otherChannel.shutdownNow();
            other.shutdownNow();
        }
    }

    /**
     * 🔴 <b>变异实测（B 侧没有 · C 侧新增）</b> —— 证明「逐字节比对判据不是恒真」。
     *
     * <p>做法：把期望文案<b>改一个字</b>再比 ⇒ 必须不相等；
     * 再把「改成空的」也比一次 ⇒ 必须不相等。
     * ⇒ 证明 {@code assertArrayEquals} 这条判据<b>能红</b>，不是恒真（AGENTS.md §3.3.3）。
     */
    @Test
    @DisplayName("🔴 变异实测：文案改一个字 / 改空 ⇒ 逐字节比对必须失败（判据不是恒真）")
    void byteLevelJudgeIsNotTautological() {
        String expected = ErrMsg.getMsg(ErrCodes.ERROR_DB_SELECT);

        // ① 改一个字
        String mutatedOneChar = expected.replace("数据库查询失败", "数据库查詢失败");
        assertNotEqualsBytes(expected, mutatedOneChar,
                "改一个字后必须判为不等 ⇒ 否则逐字节比对是假门");

        // ② 改成空串
        assertNotEqualsBytes(expected, "",
                "改成空串后必须判为不等 ⇒ 否则逐字节比对是假门");

        // ③ 未改坏时必须判为相等（防止判据写成永远红）
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8),
                "未改坏时逐字节比对必须相等");
    }

    /** 把「逐字节相等」翻成「必须不相等」，用于变异实测。 */
    private static void assertNotEqualsBytes(String left, String right, String message) {
        boolean equal = java.util.Arrays.equals(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
        assertFalse(equal, message);
    }

    /** 最小字符串编解码器（探针只需要能传一个非空请求）。 */
    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
