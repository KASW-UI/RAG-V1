package com.rag.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>观测层的两个进程级池真的能被关闭钩子回收，且关掉后能重建</b>。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code RpcInterceptor} 的 {@code TIMEOUT_SCHEDULER} /
 * {@code HANDLE_EXECUTOR} 两段（含「终止后按需重建」的 CAS 自旋语义）
 * ＋ {@code ObservabilityLifecycle} 的关闭钩子（L2 · D13）。
 */
class ObservabilityRuntimeTest {

    @Test
    @DisplayName("关闭钩子 ⇒ 两个池都终止")
    void shutdownTerminatesBothPools() {
        // 先确保两个池都是活的（可能已被别的测试关过 ⇒ 这里会重建）
        assertThat(ObservabilityRuntime.timeoutScheduler().isShutdown()).isFalse();
        assertThat(ObservabilityRuntime.handleExecutor().isShutdown()).isFalse();

        new ObservabilityLifecycle().shutdown();

        assertThat(ObservabilityRuntime.poolsTerminated()).isTrue();
    }

    @Test
    @DisplayName("池被终止后 ⇒ 下一次使用按需重建（否则后续请求会被 RejectedExecutionException 打死）")
    void poolsAreRebuiltAfterShutdown() {
        new ObservabilityLifecycle().shutdown();
        assertThat(ObservabilityRuntime.poolsTerminated()).isTrue();

        ScheduledExecutorService rebuilt = ObservabilityRuntime.timeoutScheduler();
        assertThat(rebuilt.isShutdown()).isFalse();
        assertThat(ObservabilityRuntime.handleExecutor().isShutdown()).isFalse();

        // 复原（避免影响其它测试）
        new ObservabilityLifecycle().shutdown();
    }

    @Test
    @DisplayName("关闭是幂等的（重复调用安全）")
    void shutdownIsIdempotent() {
        new ObservabilityLifecycle().shutdown();
        new ObservabilityLifecycle().shutdown();
        assertThat(ObservabilityRuntime.poolsTerminated()).isTrue();
        new ObservabilityLifecycle().shutdown();
    }

    @Test
    @DisplayName("线程名固定为 obs-rpc-timeout / obs-rpc-worker（与 RAG 同名，便于线上抓栈）")
    void threadNamesMatchRag() throws Exception {
        String timeoutThread = ObservabilityRuntime.timeoutScheduler()
                .submit(() -> Thread.currentThread().getName()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        String workerThread = ObservabilityRuntime.handleExecutor()
                .submit(() -> Thread.currentThread().getName()).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(timeoutThread).isEqualTo("obs-rpc-timeout");
        assertThat(workerThread).isEqualTo("obs-rpc-worker");
    }
}
