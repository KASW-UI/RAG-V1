package com.rag.common.autoconfigure;

import com.rag.common.observability.DeadlineState;
import com.rag.common.observability.ObservabilityProperties;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>DRIFT-COMMON-V17 · PARITY-006 根因的可判别守门</b>：
 * gRPC 服务端路径上，<b>入向 {@code DeadlineState} 必须绑在「真正跑 handler 的线程」上</b>。
 *
 * <h2>为什么这条是 PARITY-006 的根因</h2>
 * <pre>
 *   B 侧 RpcInterceptor.newUnaryServerInterceptor 的 onHalfClose：
 *       try (DeadlineState.Scope s = DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos); …) {
 *           super.onHalfClose();          // ← handler 在这里面跑
 *       }
 *   ⇒ ⇒ handler 线程上有了预算 ⇒ ⇒ DeadlineQueryTimeoutInjector 给每条 SQL 设 setQueryTimeout
 *   ⇒ ⇒ 预算到点 PG 收到 CancelRequest ⇒ ⇒ 事务回滚 ⇒ ⇒ **在途写不落库**
 * </pre>
 *
 * <p>⚠️ <b>C 侧此前整段丢失</b>（{@code ObservabilityAutoConfiguration.GrpcObservationInterceptor}
 * <b>只重写了 {@code onCancel()}</b>，没有 {@code onHalfClose()}）⇒ ⇒ handler 线程上
 * {@code DeadlineState} 恒空 ⇒ ⇒ SQL 不设 query timeout ⇒ ⇒ <b>客户端取消了、服务端照样提交</b>。
 *
 * <h2>⚠️ 判据为什么必须落在「handler 体内」</h2>
 * <p>要证的是「<b>绑定发生在那一个跑 handler 的线程上</b>」，不是「代码里写了 bind」。
 * ⇒ ⇒ 断言点必须<b>在 handler 执行期内</b>读 {@code DeadlineState.remainingMillis()}：
 * <ul>
 *   <li><b>绑定生效</b> ⇒ 拿到<b>有限值</b>（≈ 预算毫秒数）；</li>
 *   <li><b>绑定没生效</b> ⇒ 拿到 {@link Long#MAX_VALUE}（无期限哨兵）⇒ ⇒ <b>断言必红</b>。</li>
 * </ul>
 * ⇒ ⇒ <b>禁止</b>只断言「某处调用了 bind」⇒ 那种断言在绑定<b>绑错线程</b>时照样绿（不可判别）。
 *
 * <h2>⚠️ 为什么第二条用例要「故意不装 ObservationRegistry」</h2>
 * <p>绑定<b>必须与「装没装 Micrometer registry」无关</b> ⇒ 否则「没装观测的进程」就又没有 deadline
 * ⇒ ⇒ 与 B 侧不符（B 侧无条件绑）⇒ ⇒ 本类专门覆盖 {@code interceptCall} 的<b>早退分支</b>。
 */
class GrpcDeadlineBindingTest {

    /** handler 观察到的剩余预算（毫秒）；{@link Long#MAX_VALUE} = 未绑定。 */
    private final AtomicLong observedRemainingMillis = new AtomicLong(Long.MIN_VALUE);

    /** handler 执行线程的名字（用来证明「不是调用方线程」）。 */
    private final AtomicReference<String> handlerThread = new AtomicReference<>();

    /** handler 执行期 {@code isExpired()} 的读数。 */
    private final AtomicReference<Boolean> handlerExpired = new AtomicReference<>();

    /** handler 真的跑过的信号。 */
    private final CountDownLatch handlerRan = new CountDownLatch(1);

    // ── ① 拦截器级：绑定 / 不绑（可判别的双向对照）───────────────────────

    @Test
    @DisplayName("🔴 gRPC：handler 执行期线程上必须绑着入向 deadline"
            + "（剩余预算是【有限值】而不是 Long.MAX_VALUE —— 修前恒为 MAX ⇒ 本用例必红）")
    void handlerThreadSeesInboundDeadline() {
        ObservabilityProperties properties = propertiesWithRpcTimeout(Duration.ofSeconds(2));
        // ⚠️ **故意不装 ObservationRegistry** ⇒ 走 interceptCall 的早退分支
        //    ⇒ 证「绑定与装没装观测 registry 无关」。
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        ServerCall.Listener<String> listener = interceptor.interceptCall(
                new FakeCall(), new Metadata(), recordingHandler());
        listener.onHalfClose();

        assertThat(observedRemainingMillis.get())
                .as("绑定生效 ⇒ handler 线程上 remainingMillis() 是有限值（≈ 2s 预算）"
                        + "；绑定没生效 ⇒ 恒为 Long.MAX_VALUE（无期限哨兵）⇒ 断言必须能区分这两者")
                .isBetween(1L, 2_000L);
        assertThat(handlerExpired.get())
                .as("预算刚开始 ⇒ isExpired() 必须是 false")
                .isFalse();
    }

    @Test
    @DisplayName("🔴 gRPC：没有 rpc-timeout ⇒ 【不绑】（保持原样 ⇒ handler 看到 Long.MAX_VALUE）")
    void noBudgetMeansNoBinding() {
        ObservabilityProperties properties = new ObservabilityProperties();
        assertThat(properties.getRpcTimeout())
                .as("本用例的前提：rpc-timeout 未配 ⇒ 默认 null ⇒ 无预算")
                .isNull();
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        ServerCall.Listener<String> listener = interceptor.interceptCall(
                new FakeCall(), new Metadata(), recordingHandler());
        listener.onHalfClose();

        assertThat(observedRemainingMillis.get())
                .as("无预算 ⇒ 不绑 ⇒ handler 线程上 remainingMillis() 恒为 Long.MAX_VALUE"
                        + "（与 B 侧「无预算时不绑」同款）")
                .isEqualTo(Long.MAX_VALUE);
    }

    // ── ② 真 gRPC server 级：证 grpc-java 的执行模型 ─────────────────────

    @Test
    @DisplayName("🔴 gRPC：真 server 上 handler 真的看得到绑定"
            + "（证 grpc-java 确实在 Listener#onHalfClose() 里跑 handler ⇒ 绑在那儿才对）")
    void realServerHandlerSeesDeadline() throws Exception {
        ObservabilityProperties properties = propertiesWithRpcTimeout(Duration.ofSeconds(2));
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
                .addService(echoService(recordingHandlerBody()))
                .intercept(interceptor)
                .build()
                .start();
        io.grpc.ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        try {
            String callerThread = Thread.currentThread().getName();
            ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, "ping");

            assertThat(handlerRan.await(5, TimeUnit.SECONDS))
                    .as("handler 必须真的被调用过")
                    .isTrue();
            assertThat(observedRemainingMillis.get())
                    .as("真 server 上 handler 也必须看到有限预算")
                    .isBetween(1L, 2_000L);
            assertThat(handlerThread.get())
                    .as("handler 必须跑在【服务端线程】上，而不是调用方线程"
                            + "（否则「绑在 handler 线程上」这句话就没被证到）")
                    .isNotEqualTo(callerThread);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ── 装配辅助 ──────────────────────────────────────────────────────────

    private static ObservabilityProperties propertiesWithRpcTimeout(Duration rpcTimeout) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setRpcTimeout(rpcTimeout);
        return properties;
    }

    /** 一个「取不到 registry」的 provider ⇒ 走 {@code interceptCall} 的早退分支。 */
    private static ObjectProvider<ObservationRegistry> emptyRegistryProvider() {
        return new DefaultListableBeanFactory().getBeanProvider(ObservationRegistry.class);
    }

    /** 一个「在 onHalfClose 里读载体」的 handler —— 这就是断言的采样点。 */
    private ServerCallHandler<String, String> recordingHandler() {
        return (call, headers) -> new ServerCall.Listener<String>() {
            @Override
            public void onHalfClose() {
                sample();
                handlerRan.countDown();
            }
        };
    }

    private ServerCalls.UnaryMethod<String, String> recordingHandlerBody() {
        return (request, responseObserver) -> {
            sample();
            handlerRan.countDown();
            responseObserver.onNext("ok");
            responseObserver.onCompleted();
        };
    }

    private void sample() {
        observedRemainingMillis.set(DeadlineState.remainingMillis());
        handlerExpired.set(DeadlineState.isExpired());
        handlerThread.set(Thread.currentThread().getName());
    }

    private static ServerServiceDefinition echoService(
            ServerCalls.UnaryMethod<String, String> body) {
        return ServerServiceDefinition.builder("test.Echo")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall(body))
                .build();
    }

    // ── 方法描述符 + 极简 marshaller ──────────────────────────────────────

    private static final MethodDescriptor<String, String> METHOD =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Echo/Echo")
                    .setRequestMarshaller(new StringMarshaller())
                    .setResponseMarshaller(new StringMarshaller())
                    .build();

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

    /** 假的 {@link ServerCall} —— 只需要满足抽象方法；本用例不碰真实传输。 */
    private static final class FakeCall extends ServerCall<String, String> {
        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return METHOD;
        }
    }
}
