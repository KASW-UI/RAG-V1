package com.rag.common.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>非 gRPC 路径的业务调用真的带超时控制 + 观测分类</b>。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code RpcInterceptor.handle(...)} 整段抄（L2 · D13）。
 *
 * <p>⚠️ <b>判定顺序本身就是行为</b>（照抄 RAG 的 {@code markRpcResult}）：
 * <pre>
 *   ① err == null            ⇒ 正常（只写 duration / slow）
 *   ② isTimeoutError(err)     ⇒ timeout
 *   ③ 业务码集合（7 个）        ⇒ business
 *   ④ 其余                    ⇒ system
 * </pre>
 */
class RpcHandleRunnerTest {

    @Test
    @DisplayName("无超时 ⇒ 同线程跑完，返回响应")
    void noTimeoutRunsInline() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        RpcResult<String> result = RpcHandleRunner.handle(
                registry, "svc/Method", "req", null, Duration.ofMillis(500),
                req -> "resp-" + req);

        assertThat(result.response()).isEqualTo("resp-req");
        assertThat(result.error()).isNull();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind")).isNull();
    }

    @Test
    @DisplayName("业务异常 ⇒ system 分类（未知异常不是业务码）")
    void businessExceptionBecomesSystemError() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        RpcResult<String> result = RpcHandleRunner.handle(
                registry, "svc/Method", "req", Duration.ofSeconds(5), Duration.ofMillis(500),
                req -> { throw new IllegalStateException("boom"); });

        assertThat(result.response()).isNull();
        assertThat(result.error()).isInstanceOf(IllegalStateException.class);
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind").getValue())
                .isEqualTo("system");
    }

    @Test
    @DisplayName("业务码异常（NOT_FOUND）⇒ business 分类 + 写 biz_code")
    void businessCodeBecomesBusinessError() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        RpcResult<String> result = RpcHandleRunner.handle(
                registry, "svc/Method", "req", Duration.ofSeconds(5), Duration.ofMillis(500),
                req -> { throw Status.NOT_FOUND.withDescription("no such user")
                        .asRuntimeException(); });

        assertThat(result.error()).isInstanceOf(StatusRuntimeException.class);
        Observation.Context ctx = captured.get();
        assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("business");
        assertThat(ctx.getHighCardinalityKeyValue("sea.biz_code").getValue())
                .isEqualTo(String.valueOf(Status.Code.NOT_FOUND.value()));
        assertThat(ctx.getHighCardinalityKeyValue("sea.biz_msg").getValue())
                .isEqualTo("rpc error: code = NOT_FOUND desc = no such user");
    }

    @Test
    @DisplayName("超时 ⇒ 返回 DEADLINE_EXCEEDED（desc = context deadline exceeded）且打断 worker")
    void timeoutReturnsDeadlineExceededAndInterruptsWorker() throws Exception {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        RpcResult<String> result = RpcHandleRunner.handle(
                registry, "svc/Slow", "req", Duration.ofMillis(150), Duration.ofMillis(500),
                req -> {
                    entered.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        throw e;
                    }
                    return "never";
                });

        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.response()).isNull();
        assertThat(result.error()).isInstanceOf(StatusRuntimeException.class);
        Status status = Status.fromThrowable(result.error());
        assertThat(status.getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        assertThat(status.getDescription()).isEqualTo("context deadline exceeded");

        // ⚠️ 判据：worker 真的被 interrupt 了（cancel(true) 的效果）
        assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();

        Observation.Context ctx = captured.get();
        assertThat(ctx.getLowCardinalityKeyValue("sea.error.kind").getValue()).isEqualTo("timeout");
        assertThat(ctx.getLowCardinalityKeyValue("sea.timeout").getValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("慢调用 ⇒ 写 sea.slow（严格大于阈值）")
    void slowCallIsMarked() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        RpcHandleRunner.handle(registry, "svc/Slow", "req", null, Duration.ofMillis(1),
                req -> {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                    }
                    return "ok";
                });

        assertThat(captured.get().getLowCardinalityKeyValue("sea.slow").getValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("registry = null ⇒ 不产出观测，但返回值与超时语义不变")
    void nullRegistryStillRuns() {
        RpcResult<String> result = RpcHandleRunner.handle(
                null, "svc/Method", "req", Duration.ofSeconds(5), Duration.ofMillis(500),
                req -> "resp");

        assertThat(result.response()).isEqualTo("resp");
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("handler 抛 RuntimeException ⇒ 被转成 RpcResult.error（不外抛）")
    void runtimeExceptionIsCaptured() {
        RpcResult<String> result = RpcHandleRunner.handle(
                null, "svc/Method", "req", null, null,
                req -> { throw new RuntimeException("nope"); });

        assertThat(result.error()).isInstanceOf(RuntimeException.class);
        assertThat(result.error()).hasMessage("nope");
    }

    @Test
    @DisplayName("Error（不是 Exception）⇒ 同样被捕获成 error（worker 内部兜底）")
    void errorIsCaptured() {
        RpcResult<String> result = RpcHandleRunner.handle(
                null, "svc/Method", "req", Duration.ofSeconds(5), null,
                req -> { throw new StackOverflowError("deep"); });

        assertThat(result.error()).isNotNull();
    }

    @Test
    @DisplayName("null slowThreshold ⇒ 回落 500ms 默认值（照抄 RAG）")
    void nullSlowThresholdFallsBackToDefault() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        RpcHandleRunner.handle(registry, "svc/Method", "req", null, null, req -> "ok");

        // 耗时必然 < 500ms ⇒ 不慢
        assertThat(captured.get().getLowCardinalityKeyValue("sea.slow")).isNull();
    }

    @Test
    @DisplayName("观测名与 contextual name = tracer 名 / span 名")
    void observationNamingIsPreserved() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        AtomicReference<String> nameRef = new AtomicReference<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                captured.set(context);
                nameRef.set(context.getName());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });

        RpcHandleRunner.handle(registry, "svc/Method", "req", null, null, req -> "ok");

        assertThat(nameRef.get()).isEqualTo(RpcHandleRunner.HANDLER_OBSERVATION_NAME);
        assertThat(captured.get().getLowCardinalityKeyValue("rpc.method").getValue())
                .isEqualTo("svc/Method");
        assertThat(captured.get().getLowCardinalityKeyValue("rpc.system").getValue())
                .isEqualTo("grpc");
    }

    @Test
    @DisplayName("null handler ⇒ 内联路径把它当业务异常捕获（NPE 进 error，不外抛）")
    void nullHandlerIsCapturedAsError() {
        RpcResult<String> result = RpcHandleRunner.handle(
                null, "svc/Method", "req", null, null, null);

        assertThat(result.error()).isInstanceOf(NullPointerException.class);
        assertThat(result.response()).isNull();
    }

    private static ObservationRegistry registry(AtomicReference<Observation.Context> captured) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                captured.set(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }
}
