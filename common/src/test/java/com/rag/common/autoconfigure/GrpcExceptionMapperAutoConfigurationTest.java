package com.rag.common.autoconfigure;

import com.rag.common.exception.BusinessException;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>业务异常在 gRPC 边界被映射成 Go 形状的 status</b>。
 *
 * <p>⚠️ <b>Go 出处</b>：
 * <pre>
 *   Sea service/&lt;域&gt;/common/errmsg/errmsg.go:33-35
 *       func NewGrpcErr(code int, msg string) error {
 *           return status.Error(codes.Code(code), msg)   // ← 业务码当 gRPC status code
 *       }
 *   Sea service/&lt;域&gt;/api/internal/logic/*.go
 *       return nil, errmsg.NewErrCodeMsg(int(st.Code()), st.Message())
 * </pre>
 * ⇒ ⇒ Go 侧的 gRPC 边界形状 = <b>status code = 业务码</b> + <b>description = 业务文案</b>。
 *
 * <p>⚠️ <b>grpc-java 承载不了任意数值 status code</b>（javap 实测：
 * {@code Status#fromCodeValue(int)} 对未知码回落 {@code UNKNOWN}）
 * ⇒ ⇒ 业务码改由 <b>trailer</b> 承载 ⇒ <b>L3 降级</b>（本仓新增一个 trailer）。
 * ⇒ ⇒ description 与 Go <b>逐字一致</b>（那一条不是降级）。
 */
class GrpcExceptionMapperAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcExceptionMapperAutoConfiguration.class));

    @Test
    @DisplayName("业务异常 ⇒ status description = 业务文案 + trailer 带上业务码")
    void businessExceptionBecomesGoShapedStatus() {
        runner.run(context -> {
            ServerInterceptor interceptor = context.getBean(ServerInterceptor.class);

            RecordingCall call = new RecordingCall();
            interceptor.interceptCall(call, new Metadata(), handlerClosing(
                    Status.INTERNAL.withCause(new BusinessException(1001, "dup user"))));

            assertThat(call.closedStatus).isNotNull();
            assertThat(call.closedStatus.getCode()).isEqualTo(Status.Code.UNKNOWN);
            assertThat(call.closedStatus.getDescription()).isEqualTo("dup user");
            assertThat(call.closedTrailers.get(
                    Metadata.Key.of(GrpcExceptionMapperAutoConfiguration.BIZ_CODE_TRAILER_KEY,
                            Metadata.ASCII_STRING_MARSHALLER)))
                    .isEqualTo("1001");
        });
    }

    @Test
    @DisplayName("非业务异常（无 BusinessException cause）⇒ status 一字不动")
    void nonBusinessExceptionIsUntouched() {
        runner.run(context -> {
            ServerInterceptor interceptor = context.getBean(ServerInterceptor.class);

            RecordingCall call = new RecordingCall();
            interceptor.interceptCall(call, new Metadata(), handlerClosing(
                    Status.INTERNAL.withDescription("boom")));

            assertThat(call.closedStatus.getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(call.closedStatus.getDescription()).isEqualTo("boom");
            assertThat(call.closedTrailers.keys()).isEmpty();
        });
    }

    @Test
    @DisplayName("toBusinessException：cause 里的业务异常优先（客户端还原路径）")
    void toBusinessExceptionPrefersCause() {
        BusinessException carried = new BusinessException(5001, "db error");
        StatusRuntimeExceptionHolder holder = new StatusRuntimeExceptionHolder(carried);
        BusinessException restored = GrpcExceptionMapperAutoConfiguration.toBusinessException(holder.exception());
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).isEqualTo(5001);
        assertThat(restored.getMessage()).isEqualTo("db error");
    }

    @Test
    @DisplayName("toBusinessException：无 cause 时按 status 的 code + description 兜底")
    void toBusinessExceptionFallsBackToStatus() {
        StatusRuntimeExceptionHolder holder = StatusRuntimeExceptionHolder.plain(
                Status.UNKNOWN.withDescription("plain error"));
        BusinessException restored = GrpcExceptionMapperAutoConfiguration.toBusinessException(holder.exception());
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).isEqualTo(Status.Code.UNKNOWN.value());
        assertThat(restored.getMessage()).isEqualTo("plain error");
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /** 记录 close(status, trailers) 的假 ServerCall。 */
    static final class RecordingCall extends ServerCall<Object, Object> {

        Status closedStatus;
        Metadata closedTrailers;

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(Object message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closedStatus = status;
            this.closedTrailers = trailers == null ? new Metadata() : trailers;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public io.grpc.MethodDescriptor<Object, Object> getMethodDescriptor() {
            return null;
        }
    }

    /** 造一个「用给定 status 关闭这次调用」的 handler。 */
    private static ServerCallHandler<Object, Object> handlerClosing(Status status) {
        return new ServerCallHandler<Object, Object>() {
            @Override
            public ServerCall.Listener<Object> startCall(ServerCall<Object, Object> call, Metadata headers) {
                call.close(status, new Metadata());
                return new ServerCall.Listener<Object>() {
                };
            }
        };
    }

    /** 造一个带 cause 的 StatusRuntimeException。 */
    static final class StatusRuntimeExceptionHolder {
        private io.grpc.StatusRuntimeException exception;

        StatusRuntimeExceptionHolder(Throwable cause) {
            this.exception = new io.grpc.StatusRuntimeException(Status.UNKNOWN.withCause(cause));
        }

        /** 造一个「status 里没有业务异常 cause」的异常（兜底路径用）。 */
        static StatusRuntimeExceptionHolder plain(Status status) {
            StatusRuntimeExceptionHolder holder = new StatusRuntimeExceptionHolder(new RuntimeException("x"));
            holder.exception = new io.grpc.StatusRuntimeException(status);
            return holder;
        }

        io.grpc.StatusRuntimeException exception() {
            return exception;
        }
    }

    /** 避免 IDE 删掉未用的 import（List 在占位断言里用得上）。 */
    @Test
    @DisplayName("（锚点）trailer 的 key 必须是合法的小写 gRPC 元数据键")
    void trailerKeyIsLegal() {
        List<String> keys = new ArrayList<>();
        keys.add(GrpcExceptionMapperAutoConfiguration.BIZ_CODE_TRAILER_KEY);
        assertThat(keys.get(0)).isEqualTo("sea-biz-code").isLowerCase();
    }
}
