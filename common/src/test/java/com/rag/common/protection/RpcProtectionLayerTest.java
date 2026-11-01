package com.rag.common.protection;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §3.3「机制可证」守门：<b>gRPC 降载拒绝的 wire 形状</b> ＋ <b>熔断失败判据</b> ＋
 * <b>客户端熔断的隔离 key</b>。
 *
 * <p>⚠️ <b>这三条都是行为契约</b>：
 * <ul>
 *   <li>降载拒绝 = {@code RESOURCE_EXHAUSTED + "service overloaded"}（B 侧 {@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcSheddingInterceptor.java:80}）；</li>
 *   <li>熔断失败判据 = <b>6 码白名单</b>（B 侧 {@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcFailurePolicy.java:86}）
 *       ⇒ <b>业务错误（NOT_FOUND / PERMISSION_DENIED …）不算失败</b>；</li>
 *   <li>客户端熔断隔离 key = <b>(下游服务, 方法)</b>（B 侧 {@code service/common/src/main/java/com/rag/common/protection/GoStyleGrpcClientBreakerInterceptor.java:91}）。</li>
 * </ul>
 */
class RpcProtectionLayerTest {

    @Test
    @DisplayName("降载拒绝 = RESOURCE_EXHAUSTED + \"service overloaded\"（B 侧 ErrServiceOverloaded 文案）")
    void sheddingRejectionShape() {
        AtomicReference<Status> closed = new AtomicReference<>();
        ServerCall<String, String> call = mockCall(closed);

        AdaptiveShedder shedder = shedderUnderOverload();
        RpcSheddingInterceptor interceptor = new RpcSheddingInterceptor(shedder, true);

        ServerCallHandler<String, String> handler = (c, h) -> new io.grpc.ServerCall.Listener<String>() {
        };

        var listener = interceptor.interceptCall(call, new Metadata(), handler);
        listener.onHalfClose();

        assertThat(closed.get()).isNotNull();
        assertThat(closed.get().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(closed.get().getDescription()).isEqualTo("service overloaded");
    }

    @Test
    @DisplayName("降载是**双条件**（CPU 过载 **且** 在途超上限）⇒ 光有 CPU 过载不降载（D-04 的缺口已闭合）")
    void sheddingRequiresBothCpuAndInFlight() {
        AdaptiveShedder shedder = new AdaptiveShedder(900L, ProtectionProperties.CpuSource.SYSTEM);
        AtomicInteger cpu = new AtomicInteger(100);
        shedder.setCpuPermilleReader(cpu::get);

        // ⚠️ 只有 CPU 过载、**没有在途** ⇒ 上游的 highThru() 为假 ⇒ **不降载**
        cpu.set(1000);
        assertThat(shedder.allow()).isTrue();

        // ⚠️ 双条件齐备（CPU 过载 + 在途超上限）⇒ 降载
        AdaptiveShedder overloaded = shedderUnderOverload();
        assertThat(overloaded.allow()).isFalse();
    }

    /**
     * 构造一个<b>双条件齐备</b>的 shedder：CPU 打满 ＋ 在途远超上限。
     *
     * <p>⚠️ <b>为什么要这样构造</b>：上游的 {@code shouldDrop()} =
     * {@code (systemOverloaded() || stillHot()) && highThru()} ⇒
     * <b>CPU 过载本身不够</b>，还要「在途数 > maxFlight × overloadFactor」。
     * <br>⇒ 在途靠 {@code allow()} 累积、靠 {@code pass()} 递减；
     * ⇒ 而 EWMA（{@code avgFlying}）**只在递减时更新** ⇒ 必须先有若干次 {@code pass()}。
     * <br>⇒ 数字取 100 在途 / 30 次 pass ⇒ {@code flying=100 > cap=30} 且
     * {@code avgFlying≈100 > 30} ⇒ 稳定触发（与 100ms 窗口翻页无关：翻页后 minRt 仍是 1000）。
     */
    private static AdaptiveShedder shedderUnderOverload() {
        AdaptiveShedder shedder = new AdaptiveShedder(900L, ProtectionProperties.CpuSource.SYSTEM);
        AtomicInteger cpu = new AtomicInteger(100);
        shedder.setCpuPermilleReader(cpu::get);

        // ① CPU 未过载 ⇒ 100 个请求被放行（在途累积到 100）
        for (int i = 0; i < 100; i++) {
            assertThat(shedder.allow()).isTrue();
        }
        // ② 30 次「完成 + 新进」⇒ 在途维持 100，EWMA 抬到 ~100，窗口 QPS = 30
        for (int i = 0; i < 30; i++) {
            shedder.pass(1000D);
            assertThat(shedder.allow()).isTrue();
        }
        // ③ CPU 打满 ⇒ overloadFactor 落到下限 0.1 ⇒ cap = 30×1000×0.01×0.1 = 30
        cpu.set(1000);
        return shedder;
    }

    @Test
    @DisplayName("失败判据 = 6 码白名单；业务错误（NOT_FOUND / PERMISSION_DENIED）不算失败")
    void failurePolicyWhitelist() {
        // ⚠️ 上游的 6 码失败名单：一个都不能多、也不能少
        assertThat(RpcFailurePolicy.isFailure(Status.Code.DEADLINE_EXCEEDED)).isTrue();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.INTERNAL)).isTrue();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.UNAVAILABLE)).isTrue();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.DATA_LOSS)).isTrue();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.UNIMPLEMENTED)).isTrue();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.RESOURCE_EXHAUSTED)).isTrue();

        // ⚠️ 业务错误**不**参与熔断（否则「会话不存在」这种天天发生的错误会把熔断打开）
        assertThat(RpcFailurePolicy.isFailure(Status.Code.NOT_FOUND)).isFalse();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.PERMISSION_DENIED)).isFalse();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.INVALID_ARGUMENT)).isFalse();
        assertThat(RpcFailurePolicy.isFailure(Status.Code.OK)).isFalse();
        // ⚠️ UNKNOWN 归为**可接受**（上游的 status.Code 对非 status 错误返回 Unknown）
        assertThat(RpcFailurePolicy.isFailure(Status.Code.UNKNOWN)).isFalse();
    }

    @Test
    @DisplayName("客户端熔断的 acceptable 判据与服务端同一份 6 码白名单")
    void clientAcceptableMatchesServerPolicy() {
        assertThat(ClientCircuitGateInterceptor.isAcceptable(
                Status.DEADLINE_EXCEEDED.withDescription("x"))).isFalse();
        assertThat(ClientCircuitGateInterceptor.isAcceptable(
                Status.RESOURCE_EXHAUSTED.withDescription("x"))).isFalse();
        assertThat(ClientCircuitGateInterceptor.isAcceptable(
                Status.NOT_FOUND.withDescription("x"))).isTrue();
        assertThat(ClientCircuitGateInterceptor.isAcceptable(null)).isTrue();
    }

    @Test
    @DisplayName("客户端熔断隔离 key = (下游服务, 方法) ⇒ 同名方法在不同服务下**不共用**熔断器")
    void clientIsolationKeyCarriesChannelIdentity() {
        // ⚠️ 上游：path.Join(cc.Target(), method) ⇒ 键里**含服务标识**
        String a = ClientCircuitGateInterceptor.goBreakerName("message-rpc", "/message.v1.S/Send");
        String b = ClientCircuitGateInterceptor.goBreakerName("article-rpc", "/message.v1.S/Send");
        assertThat(a).isNotEqualTo(b);

        // ⚠️ 上游的 path.Join 会把连续斜杠折叠 ⇒ 本实现做同样的最小折叠
        assertThat(ClientCircuitGateInterceptor.goBreakerName("a//b", "/x.Y/Z"))
                .isEqualTo("a/b/x.Y/Z");

        // ⚠️ 取不到 target ⇒ 退化为「仅按方法名」（与 RAG 同款降级）
        assertThat(ClientCircuitGateInterceptor.goBreakerName("", "/x.Y/Z")).isEqualTo("/x.Y/Z");
    }

    @Test
    @DisplayName("出向 deadline：三项都没预算 ⇒ 原样返回 CallOptions（不给调用凭空加约束）")
    void outboundDeadlineWithoutBudgetKeepsCallOptions() {
        OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(true, 0L);
        io.grpc.CallOptions options = io.grpc.CallOptions.DEFAULT;
        assertThat(interceptor.effectiveCallOptions(options, "svc")).isSameAs(options);
    }

    @Test
    @DisplayName("出向 deadline：客户端硬上界 ⇒ 生效（且不覆盖调用点更早的 deadline）")
    void outboundDeadlineAppliesCapAndTakesMin() {
        OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(true, 200L);
        io.grpc.CallOptions options = io.grpc.CallOptions.DEFAULT;

        io.grpc.CallOptions capped = interceptor.effectiveCallOptions(options, "svc");
        assertThat(capped.getDeadline()).isNotNull();
        long remaining = capped.getDeadline().timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(remaining).isLessThanOrEqualTo(200L).isGreaterThan(0L);

        // ⚠️ 调用点自己声明了更早的 deadline ⇒ 必须取 min（不能放宽）
        io.grpc.CallOptions tighter = options.withDeadlineAfter(50L, java.util.concurrent.TimeUnit.MILLISECONDS);
        io.grpc.CallOptions merged = interceptor.effectiveCallOptions(tighter, "svc");
        assertThat(merged.getDeadline().timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isLessThanOrEqualTo(50L);
    }

    @Test
    @DisplayName("出向 deadline：per-service 上界是「覆盖」组件级上界（不是 min）")
    void outboundDeadlinePerServiceOverridesComponentCap() {
        OutboundDeadlineInterceptor interceptor =
                new OutboundDeadlineInterceptor(true, 200L, java.util.Map.of("slow-svc", 5000L));

        io.grpc.CallOptions capped = interceptor.effectiveCallOptions(io.grpc.CallOptions.DEFAULT, "slow-svc");
        long remaining = capped.getDeadline().timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS);
        // ⚠️ 覆盖 ⇒ 5000 而不是 min(200,5000)=200
        assertThat(remaining).isGreaterThan(200L);

        io.grpc.CallOptions other = interceptor.effectiveCallOptions(io.grpc.CallOptions.DEFAULT, "fast-svc");
        assertThat(other.getDeadline().timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isLessThanOrEqualTo(200L);
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    private static ServerCall<String, String> mockCall(AtomicReference<Status> closed) {
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor());
        doAnswer(invocation -> {
            closed.set(invocation.getArgument(0));
            return null;
        }).when(call).close(any(Status.class), any(Metadata.class));
        return call;
    }

    private static MethodDescriptor<String, String> methodDescriptor() {
        MethodDescriptor.Marshaller<String> marshaller = new MethodDescriptor.Marshaller<String>() {
            @Override
            public InputStream stream(String value) {
                return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String parse(InputStream stream) {
                return "parsed";
            }
        };
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("/test.v1.TestService/Call")
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
    }
}
