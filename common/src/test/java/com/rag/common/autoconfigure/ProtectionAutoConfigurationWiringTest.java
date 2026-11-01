package com.rag.common.autoconfigure;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerBuilder;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * §3.3「机制可证」守门：<b>gRPC 拦截链真的挂到了 server 上</b>（A3 的<b>运行时</b>证据）。
 *
 * <p>⚠️ <b>为什么「Bean 存在」不算证据</b>：
 * devh starter 的三个 server factory 方法<b>只</b>注入 {@code List<GrpcServerConfigurer>}
 * ⇒ ⇒ 一个 {@code ServerInterceptor} Bean 即使在容器里，<b>starter 也不会用它</b>
 * ⇒ ⇒ 链上什么都不会发生，且<b>零报错</b>。
 * ⇒ ⇒ 本测试<b>真的调</b> {@code Configurer.configure(builder)} 并断言 {@code intercept(...)} 被调用了。
 */
class ProtectionAutoConfigurationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtectionAutoConfiguration.class));

    @Test
    @DisplayName("A3：容器里的 GrpcServerConfigurer 真的调了 server.intercept(...)")
    void grpcServerConfigurerReallyIntercepts() {
        runner
                // ⚠️ 链上要有东西 ⇒ 才会 intercept ⇒ 挂载点才有意义
                // ⚠️ 用**匿名类**而不是 lambda：ServerInterceptor#interceptCall 是**泛型方法**
                //    ⇒ javac 推断不出函数描述符（实测报「函数描述符无效」）
                .withBean(ServerInterceptor.class, () -> new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        return next.startCall(call, headers);
                    }
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(GrpcServerConfigurer.class);
                    GrpcServerConfigurer configurer = context.getBean(GrpcServerConfigurer.class);

                    ServerBuilder<?> builder = mock(ServerBuilder.class);
                    configurer.accept(builder);

                    // ⚠️ 「真的调了 intercept」—— 不是「容器里有个 Bean」
                    verify(builder).intercept(any(ServerInterceptor.class));
                });
    }

    @Test
    @DisplayName("A3：拦截链为空时**不**调 intercept（不装一条空链）")
    void emptyChainDoesNotIntercept() {
        runner.run(context -> {
            GrpcServerConfigurer configurer = context.getBean(GrpcServerConfigurer.class);
            ServerBuilder<?> builder = mock(ServerBuilder.class);
            configurer.accept(builder);
            // 上下文里没有任何 ServerInterceptor Bean ⇒ 不该调 intercept
            verify(builder, org.mockito.Mockito.never()).intercept(any(ServerInterceptor.class));
        });
    }

    @Test
    @DisplayName("compose 保持「列表第 0 个在最外层」的顺序（不押 grpc 的 reverse-order 细节）")
    void composeKeepsOuterToInnerOrder() {
        List<String> calls = new ArrayList<>();
        ServerInterceptor first = recording(calls, "first");
        ServerInterceptor second = recording(calls, "second");
        ServerInterceptor third = recording(calls, "third");

        ServerInterceptor chain = ProtectionAutoConfiguration.compose(List.of(first, second, third));
        assertThat(chain).isNotNull();

        chain.interceptCall(mockCall(), new Metadata(), noopHandler());

        assertThat(calls).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("compose 的空入参返回 null（挂载点据此跳过 intercept）")
    void composeOfEmptyIsNull() {
        assertThat(ProtectionAutoConfiguration.compose(List.of())).isNull();
        assertThat(ProtectionAutoConfiguration.compose(null)).isNull();
    }

    @Test
    @DisplayName("compose 只有一层时原样返回该拦截器（不多包一层）")
    void composeOfSingleReturnsSame() {
        ServerInterceptor only = recording(new ArrayList<>(), "only");
        assertThat(ProtectionAutoConfiguration.compose(List.of(only))).isSameAs(only);
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    private static ServerInterceptor recording(List<String> calls, String name) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                calls.add(name);
                return next.startCall(call, headers);
            }
        };
    }

    private static ServerCallHandler<Object, Object> noopHandler() {
        return new ServerCallHandler<Object, Object>() {
            @Override
            public ServerCall.Listener<Object> startCall(ServerCall<Object, Object> call, Metadata headers) {
                return new ServerCall.Listener<Object>() {
                };
            }
        };
    }

    private static ServerCall<Object, Object> mockCall() {
        return mock(ServerCall.class);
    }
}
