package com.rag.common.autoconfigure;

import com.rag.common.protection.RpcProtectionChain;
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
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * §3.3「机制可证」守门：<b>gRPC 拦截链真的挂到了 server 上</b>（A3 的<b>运行时</b>证据），
 * 且<b>链序真的是 B 侧的顺序</b>（V03 的核心契约）。
 *
 * <p>⚠️ <b>为什么「Bean 存在」不算证据</b>：
 * devh starter 的三个 server factory 方法<b>只</b>注入 {@code List<GrpcServerConfigurer>}
 * ⇒ ⇒ 一个 {@code ServerInterceptor} Bean 即使在容器里，<b>starter 也不会用它</b>
 * ⇒ ⇒ 链上什么都不会发生，且<b>零报错</b>。
 * ⇒ ⇒ 本测试<b>真的调</b> {@code Configurer.configure(builder)} 并断言 {@code intercept(...)} 被调用了。
 *
 * <p>⚠️ <b>为什么链序必须真的搭出来断言</b>：
 * B 侧的注册序 {@code Trace → Recover → Stat → Prometheus → Breaker → Shedding → Timeout}
 * 是<b>行为语义</b>（recover 必须最外层否则兜不住 panic；breaker 必须在 shedding 外层
 * 否则降载不会把熔断打开；prometheus 必须在 breaker/shedding 外层否则被拒的请求在指标里消失）
 * ⇒ ⇒ 「{@code @Order} 看着对」不算证据 ⇒ ⇒ 本测试把链<b>真的搭出来</b>、逐层读层名。
 */
class ProtectionAutoConfigurationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtectionAutoConfiguration.class));

    private final ApplicationContextRunner fullRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ProtectionAutoConfiguration.class, ObservabilityAutoConfiguration.class));

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
    @DisplayName("V03 链序：recover → stat → prometheus → breaker → shedding → business（B 侧注册序）")
    void chainLayerOrderMatchesGo() {
        fullRunner.run(context -> {
            ServerInterceptor chain = composeOf(context);
            assertThat(RpcProtectionChain.layerNames(chain)).containsExactly(
                    "recover", "stat", "prometheus", "breaker", "shedding", "business");
        });
    }

    @Test
    @DisplayName("V03：5 层保护拦截器都真的注册成了 Bean（不是「类在、没人用」）")
    void allProtectionLayersAreBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(
                    com.rag.common.observability.RpcRecoverInterceptor.class);
            assertThat(context).hasSingleBean(
                    com.rag.common.observability.RpcStatInterceptor.class);
            assertThat(context).hasSingleBean(
                    com.rag.common.observability.RpcPrometheusInterceptor.class);
            assertThat(context).hasSingleBean(
                    com.rag.common.protection.RpcCircuitGateInterceptor.class);
            assertThat(context).hasSingleBean(
                    com.rag.common.protection.RpcSheddingInterceptor.class);
        });
    }

    @Test
    @DisplayName("compose 保持「列表第 0 个在最外层」的顺序（不押 grpc 的 reverse-order 细节）")
    void composeKeepsOuterToInnerOrder() {
        List<String> calls = new ArrayList<>();
        ServerInterceptor first = recording(calls, "first");
        ServerInterceptor second = recording(calls, "second");
        ServerInterceptor third = recording(calls, "third");

        ServerInterceptor chain = RpcProtectionChain.compose(List.of(first, second, third));
        assertThat(chain).isNotNull();

        chain.interceptCall(mockCall(), new Metadata(), noopHandler());

        assertThat(calls).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("compose 的空入参返回 null（挂载点据此跳过 intercept）")
    void composeOfEmptyIsNull() {
        assertThat(RpcProtectionChain.compose(List.of())).isNull();
        assertThat(RpcProtectionChain.compose(null)).isNull();
    }

    @Test
    @DisplayName("compose 只有一层时原样返回该拦截器（不多包一层）")
    void composeOfSingleReturnsSame() {
        ServerInterceptor only = recording(new ArrayList<>(), "only");
        assertThat(RpcProtectionChain.compose(List.of(only))).isSameAs(only);
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    /** 用<b>挂载点同样的方式</b>把链搭出来（容器里全部 {@code ServerInterceptor} Bean，按序）。 */
    private static ServerInterceptor composeOf(ApplicationContext context) {
        List<ServerInterceptor> ordered = context.getBeanProvider(ServerInterceptor.class)
                .orderedStream()
                .toList();
        return RpcProtectionChain.compose(ordered);
    }

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
