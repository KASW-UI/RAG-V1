package com.rag.comment.service;

import com.rag.common.autoconfigure.ProtectionAutoConfiguration;
import com.rag.common.protection.RpcProtectionChain;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p>🔴 <b>保护链「包装过的 call」</b>的守门：链上真的有 breaker / shedding / stat 的<b>包装层</b>。
 *
 * <h3>守的是什么</h3>
 * <p>B 侧那条用例的判据是<b>两段</b>：
 * <ol>
 *   <li><b>业务 interceptor 确实被调到</b>（不是「链把业务层吃掉了」）；</li>
 *   <li><b>业务 interceptor 拿到的 {@code call} 不是原始 {@code call}</b>
 *       ⇒ ⇒ 即<b>链上真的有包装层</b>（breaker / shedding 各自 {@code ForwardingServerCall} 包了一层），
 *       而不是「配置了但没生效」。</li>
 * </ol>
 * ⇒ ⇒ ⚠️ <b>断言不能是「同一个对象」</b>：链上有包装层时，业务 interceptor 收到的是<b>包装后的 call</b>
 * ⇒ ⇒ {@code same(call)} 必然不成立 ⇒ ⇒ 断言改成「业务 interceptor <b>确实被调到</b>」
 * ＋「拿到的 call <b>不是</b>原始 call」。
 *
 * <h3>🔴 C 侧的装配落点变了（D1 合并 + D3 starter 化）</h3>
 * <p>B 侧是<b>域内自己</b>写 {@code rpcInterceptorConfigurer}（{@code CommentRpcApplication.java:91-100}）
 * ⇒ ⇒ 「链有没有真的挂上」要<b>逐个组件</b>各写一遍（漏掉哪个，那个组件就<b>既没有 recover、
 * 也没有熔断、也没有降载</b>，而<b>不报任何错</b>）。
 * <p>C 侧 D1 把 api 与 rpc 合并成单进程 ⇒ 域内<b>不再</b>写这条 bean
 * ⇒ ⇒ 由<b>共享层的唯一挂载点</b>
 * {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer}
 * （<b>消费点</b>：{@code common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java:230-243}）
 * 承担 ⇒ ⇒ 本类断言的正是<b>那一个</b>挂载点。
 *
 * <h3>三段式（AGENTS.md §3.2）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>多断言了「链的层序」</b>（{@link RpcProtectionChain#layerNames}）⇒
 *             ⇒ 不只证明「有包装层」，还证明「包装层是<b>那五层</b>」（recover / stat / prometheus /
 *             breaker / shedding）⇒ ⇒ 「挂错了链」也会红。</li>
 *         <li><b>容器用 {@code ApplicationContextRunner} 而不是整个域的 {@code @SpringBootTest}</b>
 *             ⇒ ⇒ 断言的是<b>共享层挂载点本身</b>，不掺本域的 DB / Redis / Kafka ⇒ 快且确定。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 期望值（业务 interceptor 被调到 ＋ 拿到的是包装过的 call）
 *       逐条来自 B 侧那条用例；C 侧只是<b>换了一个挂载点</b>（域内自写 ⇒ 共享层唯一挂载点）。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentProtectionChainWiringTest.java:68-120}
 *             （B 侧那条用例的逐条 oracle）</li>
 *         <li>共享层落点：{@code common/src/main/java/com/rag/common/protection/RpcProtectionChain.java:70-108}
 *             （{@code compose} 与 {@code layerNames}）＋
 *             {@code common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java:230-243}
 *             （唯一挂载点）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（本类<b>不碰容器</b>）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
class CommentProtectionChainWiringTest {

    /** 业务 interceptor（Mockito mock）—— 它是链的<b>最内层</b>（没有 {@code @Order} ⇒ 最低优先级）。 */
    private final ServerInterceptor businessInterceptor = mock(ServerInterceptor.class);

    /** 真实链里 gRPC 全方法名（breaker 用它取隔离 key；B 侧 {@code breakerName := info.FullMethod}）。 */
    private static final String FULL_METHOD = "/comment.CommentService/CreateComment";

    /**
     * 🔴 <b>链上真的有包装层</b>：业务 interceptor 被调到，且拿到的是<b>包装过的 call</b>。
     *
     * <p>⚠️ <b>必须打桩 {@code getMethodDescriptor()}</b>：链上的 breaker 用它取隔离 key
     * ⇒ Mockito 默认返回 {@code null} ⇒ 链里抛 NPE
     * ⇒ ⇒ 而那个 NPE 会被<b>最外层的 recover 静默兜住</b>（关掉调用、返回 INTERNAL），
     * 现象是「业务 interceptor 零交互」—— 看起来像"链没生效"，真因却在链里面。
     */
    @Test
    @DisplayName("🔴 链上真的有 breaker/shedding 包装层（业务 interceptor 被调到，且拿到的是包装过的 call）")
    void chainReallyWrapsTheBusinessInterceptor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProtectionAutoConfiguration.class))
                // ⚠️ 关掉 CPU 降载：本用例要验的是「链上真的有包装层」，
                //    而降载一旦命中就会短路（handler 不执行）⇒ 断言会变得不确定。
                //    降载本身由共享层自己的用例单独覆盖。
                .withPropertyValues("rag.protection.cpu-shedding.enabled=false")
                // ⚠️ breaker 层没有 registry ⇒ **整层透传**（不包装 call）⇒ 必须提供一个
                //    ⇒ 否则「业务 interceptor 拿到的是不是包装过的 call」这条就<b>测不到 breaker</b>。
                .withBean("circuitBreakerRegistry", CircuitBreakerRegistry.class,
                        CircuitBreakerRegistry::ofDefaults)
                .withBean("businessServerInterceptor", ServerInterceptor.class,
                        () -> businessInterceptor)
                .run(context -> {
                    GrpcServerConfigurer configurer =
                            context.getBean("seaGrpcServerConfigurer", GrpcServerConfigurer.class);
                    assertThat(configurer)
                            .as("共享层的唯一挂载点必须在容器里（bean 名固定 seaGrpcServerConfigurer）")
                            .isNotNull();

                    ServerBuilder<?> builder = mock(ServerBuilder.class);
                    ArgumentCaptor<ServerInterceptor> captor = ArgumentCaptor.forClass(ServerInterceptor.class);

                    configurer.accept(builder);

                    verify(builder).intercept(captor.capture());
                    ServerInterceptor chained = captor.getValue();
                    assertThat(chained).as("必须真的把一条链交给 ServerBuilder").isNotNull();

                    // ---- ① 链的层序（读的是「已经构造出来的链」，不是注解上的 @Order 数字）
                    List<String> layers = RpcProtectionChain.layerNames(chained);
                    assertThat(layers)
                            .as("链的层序必须是 [recover, stat, prometheus, breaker, shedding]"
                                    + "（B 侧 zrpc/server.go:144-163 的注册序 ⇒ 先注册的最外层）")
                            .containsExactly("recover", "stat", "prometheus", "breaker", "shedding");

                    // ---- ② 真跑一遍被交给 builder 的那个拦截器
                    ServerCall.Listener<Object> listener = new ServerCall.Listener<>() {
                    };
                    // ⚠️ 必须打桩：Mockito 对返回类型 ServerCall.Listener 的默认答案是 null，
                    //    而 SimpleForwardingServerCallListener 的构造器 checkNotNull(delegate) ⇒ 不打桩会 NPE。
                    doReturn(listener).when(businessInterceptor).interceptCall(any(), any(), any());

                    ServerCall<Object, Object> call = mock(ServerCall.class);
                    ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
                    doReturn(listener).when(next).startCall(any(), any());
                    when(call.getMethodDescriptor()).thenReturn(MethodDescriptor.<Object, Object>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(FULL_METHOD)
                            .setRequestMarshaller(new NoopMarshaller())
                            .setResponseMarshaller(new NoopMarshaller())
                            .build());

                    chained.interceptCall(call, new Metadata(), next);

                    // ---- ③ 业务 interceptor 确实被调到，且拿到的是**包装过的** call
                    ArgumentCaptor<ServerCall<Object, Object>> callCaptor =
                            ArgumentCaptor.forClass(ServerCall.class);
                    verify(businessInterceptor).interceptCall(callCaptor.capture(), any(), any());

                    assertThat(callCaptor.getValue())
                            .as("链上有 breaker/shedding ⇒ 业务 interceptor 拿到的必须是包装过的 call。"
                                    + "若拿到的是原始 call，说明包装层没挂上 —— 即 breaker/shedding 形同虚设。")
                            .isNotSameAs(call);
                });
    }

    /**
     * 🔴 <b>反向对照</b>：<b>没有</b>任何包装层时，业务 interceptor 拿到的就是<b>原始 call</b>。
     *
     * <p>⚠️ <b>为什么必须有这一条</b>：上一条断言的是「拿到的是<b>不是</b>原始 call」
     * ⇒ ⇒ 如果 {@code isNotSameAs} 永远成立（例如 Mockito 的 captor 行为异常、
     * 或者链把 call 换成了别的什么），那上一条就<b>平凡通过</b>了
     * ⇒ ⇒ 这里用「只有一层（业务 interceptor 自己）」的链做对照 ⇒
     * ⇒ 证明「是不是同一个对象」这件事<b>真的会被包装层改变</b>。
     */
    @Test
    @DisplayName("✅ 反向对照：没有包装层时业务 interceptor 拿到的就是原始 call ⇒「被包装过」这条不是恒真")
    void withoutWrappingLayersTheBusinessInterceptorSeesTheOriginalCall() {
        ServerInterceptor chained = RpcProtectionChain.compose(List.of(businessInterceptor));
        assertThat(chained).as("只有一层时 compose 仍会返回那一个 interceptor").isNotNull();

        ServerCall.Listener<Object> listener = new ServerCall.Listener<>() {
        };
        doReturn(listener).when(businessInterceptor).interceptCall(any(), any(), any());

        ServerCall<Object, Object> call = mock(ServerCall.class);
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        doReturn(listener).when(next).startCall(any(), any());
        when(call.getMethodDescriptor()).thenReturn(MethodDescriptor.<Object, Object>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(FULL_METHOD)
                .setRequestMarshaller(new NoopMarshaller())
                .setResponseMarshaller(new NoopMarshaller())
                .build());

        chained.interceptCall(call, new Metadata(), next);

        ArgumentCaptor<ServerCall<Object, Object>> callCaptor =
                ArgumentCaptor.forClass(ServerCall.class);
        verify(businessInterceptor).interceptCall(callCaptor.capture(), any(), any());

        assertThat(callCaptor.getValue())
                .as("无包装层 ⇒ 业务 interceptor 必须拿到**原始** call")
                .isSameAs(call);
    }

    /** 最小可用的 marshaller（测试不真的序列化）。 */
    private static final class NoopMarshaller implements MethodDescriptor.Marshaller<Object> {
        @Override
        public InputStream stream(Object value) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public Object parse(InputStream stream) {
            return new Object();
        }
    }
}
