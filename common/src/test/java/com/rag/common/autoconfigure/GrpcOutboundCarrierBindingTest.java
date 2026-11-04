package com.rag.common.autoconfigure;

import com.rag.common.observability.DeadlineState;
import com.rag.common.observability.GoStyleRequestDeadline;
import com.rag.common.observability.ObservabilityProperties;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
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
 * 🔴 <b>DRIFT-COMMON-V20 · D-15 的可判别守门</b>：
 * gRPC 服务端路径上，<b>出向载体 {@code GoStyleRequestDeadline} 必须绑在「真正跑 handler 的线程」上</b>，
 * 且<b>有效期限 = min(服务端预算, 客户端 deadline 剩余)</b>。
 *
 * <h2>这条修的是什么</h2>
 * <pre>
 *   B 侧 RpcInterceptor.newUnaryServerInterceptor：
 *       long serverBudgetNanos = (timeout != null &amp;&amp; timeout &gt; 0) ? toNanosSaturated(timeout) : Long.MAX_VALUE;
 *       long effectiveTimeoutNanos = Math.min(serverBudgetNanos, clientDeadlineRemainingNanos());   // ← :304
 *       long absoluteDeadlineNanos = saturatingAddNanos(monotonicNanos, effectiveTimeoutNanos);
 *       ...
 *       onHalfClose:
 *           try (DeadlineState.Scope      s = DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos);
 *                GoStyleRequestDeadline.Scope c = GoStyleRequestDeadline.bindAbsoluteNanos(absoluteDeadlineNanos)) {
 *               super.onHalfClose();
 *           }
 *   ⇒ ⇒ **两个载体绑同一个绝对时刻** ⇒ ⇒ **有效期限取 min** ⇒ 客户端 deadline 参与服务端期限
 * </pre>
 *
 * <p>⚠️ <b>C 侧修前只绑了入向载体</b>（{@code DeadlineState}）⇒ ⇒
 * ① 出向载体在生产里<b>从不绑</b>（全仓 {@code src/main} 里 {@code GoStyleRequestDeadline.bindAbsoluteNanos}
 * 零调用点）⇒ ⇒ {@code common/protection/OutboundDeadlineInterceptor:258} 与
 * {@code common/redis/Deadline:114} 两个消费点<b>恒读到 -1</b> ⇒ ⇒ 出向 gRPC 无上界、Redis 期限闸门形同虚设；
 * ② 客户端 deadline <b>完全不参与</b> ⇒ ⇒ 服务端只按自己的 {@code rpc-timeout} 走。
 *
 * <h2>为什么断言点必须落在「handler 体内」</h2>
 * <p>要证的是「<b>绑定发生在那一个跑 handler 的线程上</b>」，不是「代码里写了 bind」。
 * ⇒ ⇒ 断言点必须<b>在 handler 执行期内</b>读载体 ⇒ ⇒ 禁止只断言「某处调用了 bind」。
 *
 * <h2>为什么用<b>真 socket</b>而不是 in-process</h2>
 * <p>客户端 deadline 要到服务端，必须走真实传输层（{@code grpc-timeout} 头 ⇒ 服务端 ctx 派生 deadline）
 * ⇒ ⇒ in-process 传输的 deadline 传播路径与真实 HTTP/2 不同 ⇒ ⇒ 本类用
 * {@link NettyServerBuilder} 起<b>真 socket</b> server ⇒ ⇒ 验的是「真 wire 上的 deadline 传播」。
 */
class GrpcOutboundCarrierBindingTest {

    /** handler 观察到的<b>出向载体</b>剩余纳秒；{@code -1} = 未绑定（修前的形状）。 */
    private final AtomicLong observedOutboundRemainingNanos = new AtomicLong(Long.MIN_VALUE);

    /** handler 观察到的<b>入向载体</b>剩余毫秒；{@link Long#MAX_VALUE} = 未绑定。 */
    private final AtomicLong observedInboundRemainingMillis = new AtomicLong(Long.MIN_VALUE);

    /** handler 执行线程的名字（证明「不是调用方线程」）。 */
    private final AtomicReference<String> handlerThread = new AtomicReference<>();

    /** handler 真的跑过的信号。 */
    private final CountDownLatch handlerRan = new CountDownLatch(1);

    // ── ① 真 socket + 客户端带更短 deadline ⇒ 有效期限取 min ───────────────

    @Test
    @DisplayName("🔴 gRPC：出向载体必须在 handler 线程真绑上，且有效期限 = min(服务端预算, 客户端 deadline)"
            + "（修前：出向载体恒 -1、入向取服务端全额 ⇒ 本用例必红）")
    void outboundCarrierIsBoundAndEffectiveBudgetIsTheMin() throws Exception {
        ObservabilityProperties properties = propertiesWithRpcTimeout(Duration.ofSeconds(5));
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        try (Fixture fixture = startServer(interceptor)) {
            ClientCalls.blockingUnaryCall(fixture.channel, METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(400, TimeUnit.MILLISECONDS), "ping");

            assertThat(handlerRan.await(5, TimeUnit.SECONDS))
                    .as("handler 必须真的被调用过")
                    .isTrue();

            long outboundNanos = observedOutboundRemainingNanos.get();
            assertThat(outboundNanos)
                    .as("🔴 出向载体 GoStyleRequestDeadline 必须在 handler 线程上绑着"
                            + "（修前恒为 -1 = 载体缺席 ⇒ 断言必红）")
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(400_000_000L);

            long inboundMillis = observedInboundRemainingMillis.get();
            assertThat(inboundMillis)
                    .as("🔴 入向载体的剩余必须也是【客户端给的】那个（≤400ms）"
                            + "，而不是服务端自己的 5s ⇒ 证明有效期限 = min(服务端, 客户端)"
                            + "（修前是 ~5000ms ⇒ 断言必红）")
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(400L);
        }
    }

    // ── ② 两个载体必须指向【同一个绝对时刻】 ──────────────────────────────

    @Test
    @DisplayName("🔴 gRPC：两个载体（入向 DeadlineState / 出向 GoStyleRequestDeadline）必须指向同一个绝对时刻")
    void bothCarriersPointToTheSameInstant() throws Exception {
        ObservabilityProperties properties = propertiesWithRpcTimeout(Duration.ofSeconds(5));
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        try (Fixture fixture = startServer(interceptor)) {
            ClientCalls.blockingUnaryCall(fixture.channel, METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2_000, TimeUnit.MILLISECONDS), "ping");

            assertThat(handlerRan.await(5, TimeUnit.SECONDS)).isTrue();

            long inboundMillis = observedInboundRemainingMillis.get();
            long outboundMillis = observedOutboundRemainingNanos.get() / 1_000_000L;
            assertThat(Math.abs(inboundMillis - outboundMillis))
                    .as("两个载体必须绑【同一个绝对时刻】⇒ 剩余量之差 ≤ 2ms"
                            + "（入向读的是毫秒版 ⇒ 允许 1ms 的截断误差）")
                    .isLessThanOrEqualTo(2L);
        }
    }

    // ── ③ 对照：客户端【不带】deadline ⇒ 行为不变（仍是服务端预算） ────────

    @Test
    @DisplayName("✅ 对照：客户端不带 deadline ⇒ 两个载体都取服务端预算（证明本修复不引入回归）")
    void withoutClientDeadlineTheServerBudgetIsUnchanged() throws Exception {
        ObservabilityProperties properties = propertiesWithRpcTimeout(Duration.ofSeconds(5));
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        try (Fixture fixture = startServer(interceptor)) {
            ClientCalls.blockingUnaryCall(fixture.channel, METHOD, CallOptions.DEFAULT, "ping");

            assertThat(handlerRan.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(observedInboundRemainingMillis.get())
                    .as("客户端没带 deadline ⇒ 入向载体仍是服务端自己的 5s 预算")
                    .isGreaterThan(4_000L)
                    .isLessThanOrEqualTo(5_000L);
            assertThat(observedOutboundRemainingNanos.get())
                    .as("客户端没带 deadline ⇒ 出向载体也应是服务端自己的 5s 预算（不是 -1）")
                    .isGreaterThan(4_000_000_000L)
                    .isLessThanOrEqualTo(5_000_000_000L);
        }
    }

    // ── ④ 边界：服务端没配 rpc-timeout ⇒ 客户端 deadline 独立生效（B 侧代码形状） ──

    @Test
    @DisplayName("🔴 gRPC：服务端没配 rpc-timeout ⇒ 客户端 deadline 独立生效（B 侧 RpcInterceptor:302-304 的 min 语义）")
    void clientDeadlineAppliesEvenWithoutServerBudget() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        assertThat(properties.getRpcTimeout())
                .as("本用例的前提：rpc-timeout 未配 ⇒ 服务端预算 = Long.MAX_VALUE")
                .isNull();
        ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(emptyRegistryProvider(), properties);

        try (Fixture fixture = startServer(interceptor)) {
            ClientCalls.blockingUnaryCall(fixture.channel, METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(400, TimeUnit.MILLISECONDS), "ping");

            assertThat(handlerRan.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(observedOutboundRemainingNanos.get())
                    .as("服务端无预算时，客户端 deadline 是唯一预算来源 ⇒ 出向载体仍须绑上")
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(400_000_000L);
            assertThat(observedInboundRemainingMillis.get())
                    .as("服务端无预算时，入向载体取客户端 deadline（≤400ms）")
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(400L);
        }
    }

    // ── 装配辅助 ──────────────────────────────────────────────────────────

    private static ObservabilityProperties propertiesWithRpcTimeout(Duration rpcTimeout) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setRpcTimeout(rpcTimeout);
        return properties;
    }

    /** 一个「取不到 registry」的 provider ⇒ 走 {@code interceptCall} 的早退分支（绑定与观测无关）。 */
    private static ObjectProvider<ObservationRegistry> emptyRegistryProvider() {
        return new DefaultListableBeanFactory().getBeanProvider(ObservationRegistry.class);
    }

    /** 起一个<b>真 socket</b> 的 gRPC server（客户端 deadline 走真实 wire 传播）。 */
    private Fixture startServer(ServerInterceptor interceptor) throws IOException {
        NettyServerBuilder builder = NettyServerBuilder.forPort(0)
                .addService(ServerServiceDefinition.builder("test.Outbound")
                        .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                            sample();
                            handlerRan.countDown();
                            responseObserver.onNext("ok");
                            responseObserver.onCompleted();
                        }))
                        .build())
                .intercept(interceptor);
        Server server = builder.build().start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext()
                .build();
        return new Fixture(server, channel);
    }

    private void sample() {
        // ⚠️ 采样必须发生在 handler 执行期（两个载体都已绑上的作用域内）
        observedInboundRemainingMillis.set(DeadlineState.remainingMillis());
        observedOutboundRemainingNanos.set(GoStyleRequestDeadline.remainingNanos());
        handlerThread.set(Thread.currentThread().getName());
    }

    /** 真 socket server + 客户端 channel 的组合体。 */
    private static final class Fixture implements AutoCloseable {
        private final Server server;
        private final ManagedChannel channel;

        private Fixture(Server server, ManagedChannel channel) {
            this.server = server;
            this.channel = channel;
        }

        @Override
        public void close() throws InterruptedException {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ── 方法描述符 + 极简 marshaller ──────────────────────────────────────

    private static final MethodDescriptor<String, String> METHOD =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Outbound/Outbound")
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
}
