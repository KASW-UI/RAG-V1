package com.rag.common.observability;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §3.3「机制可证」守门：<b>gRPC handler panic ⇒ {@code INTERNAL + "panic: "}</b>。
 *
 * <p>⚠️ <b>这是 L1 wire 契约</b>（Go {@code status.Errorf(codes.Internal, "panic: %v", r)}）
 * ⇒ ⇒ <b>客户端看得见这个字符串</b> ⇒ ⇒ 改了它就与 Go 分叉 ⇒ ⇒ 必须有守门。
 *
 * <p><b>Go 出处</b>：
 * <pre>
 *   zrpc/internal/serverinterceptors/recoverinterceptor.go:24-42
 *       func UnaryRecoverInterceptor(ctx, req, _, handler) (resp any, err error) {
 *           defer handleCrash(func(r any) { err = toPanicError(ctx, r) })
 *           return handler(ctx, req)
 *       }
 *       func toPanicError(ctx context.Context, r any) error {
 *           logc.Errorf(ctx, "%+v\n\n%s", r, debug.Stack())
 *           return status.Errorf(codes.Internal, "panic: %v", r)
 *       }
 * </pre>
 */
class RpcRecoverInterceptorTest {

    private final RpcRecoverInterceptor interceptor = new RpcRecoverInterceptor();

    @Test
    @DisplayName("handler 在 onHalfClose 里 panic ⇒ close(INTERNAL + \"panic: <msg>\")")
    void panicInOnHalfCloseBecomesInternalWithPanicPrefix() {
        AtomicReference<Status> closed = new AtomicReference<>();
        ServerCall<String, String> call = mockCall(closed);

        ServerCallHandler<String, String> handler = (c, h) -> new io.grpc.ServerCall.Listener<String>() {
            @Override
            public void onHalfClose() {
                throw new IllegalStateException("boom");
            }
        };

        var listener = interceptor.interceptCall(call, new Metadata(), handler);
        listener.onHalfClose();

        assertThat(closed.get()).isNotNull();
        assertThat(closed.get().getCode()).isEqualTo(Status.Code.INTERNAL);
        // ⚠️ **wire 可见的前缀**：Go 是 "panic: %v" ⇒ 逐字对齐
        assertThat(closed.get().getDescription()).isEqualTo("panic: boom");
    }

    @Test
    @DisplayName("panic 发生在 startCall ⇒ 同样 close(INTERNAL + \"panic: \")，且不抛出去")
    void panicInStartCallBecomesInternal() {
        AtomicReference<Status> closed = new AtomicReference<>();
        ServerCall<String, String> call = mockCall(closed);

        ServerCallHandler<String, String> handler = (c, h) -> {
            throw new IllegalStateException("start-boom");
        };

        var listener = interceptor.interceptCall(call, new Metadata(), handler);

        assertThat(closed.get()).isNotNull();
        assertThat(closed.get().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(closed.get().getDescription()).isEqualTo("panic: start-boom");
        // ⚠️ 兜底的语义：**不把 panic 抛给 grpc 框架**（否则框架会再 close 一次 ⇒ IllegalStateException）
        assertThat(listener).isNotNull();
    }

    @Test
    @DisplayName("没有 panic ⇒ 不 close（正常路径不被干预）")
    void normalPathDoesNotClose() {
        AtomicReference<Status> closed = new AtomicReference<>();
        ServerCall<String, String> call = mockCall(closed);

        ServerCallHandler<String, String> handler = (c, h) -> new io.grpc.ServerCall.Listener<String>() {
        };

        var listener = interceptor.interceptCall(call, new Metadata(), handler);
        listener.onHalfClose();

        assertThat(closed.get()).isNull();
    }

    @Test
    @DisplayName("只有一次 close（重复 panic 不会二次 close ⇒ 不会 IllegalStateException）")
    void closesOnlyOnce() {
        AtomicReference<Status> closed = new AtomicReference<>();
        AtomicReference<Integer> closeCount = new AtomicReference<>(0);
        ServerCall<String, String> call = mockCall(closed, closeCount);

        ServerCallHandler<String, String> handler = (c, h) -> new io.grpc.ServerCall.Listener<String>() {
            @Override
            public void onHalfClose() {
                throw new IllegalStateException("boom");
            }
        };

        var listener = interceptor.interceptCall(call, new Metadata(), handler);
        listener.onHalfClose();
        listener.onHalfClose();

        assertThat(closeCount.get()).isEqualTo(1);
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    private static ServerCall<String, String> mockCall(AtomicReference<Status> closed) {
        return mockCall(closed, new AtomicReference<>(0));
    }

    private static ServerCall<String, String> mockCall(AtomicReference<Status> closed,
                                                        AtomicReference<Integer> closeCount) {
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor());
        doAnswer(invocation -> {
            Status status = invocation.getArgument(0);
            closed.set(status);
            closeCount.set(closeCount.get() + 1);
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
