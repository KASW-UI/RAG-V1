package com.rag.favorite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.StringValue;
import com.rag.common.observability.ObservabilityProperties;
import com.rag.common.protection.OutboundDeadlineInterceptor;
import com.rag.common.protection.ProtectionProperties;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 🔴🔴 **回归守卫：出向 gRPC 客户端硬上界（{@code rag.protection.outbound-deadline}）**
 *
 * <p><b>为什么需要这条用例</b> —— 2026-11-04 实测抓到的<b>真漂移</b>：
 * C 侧 favorite 的 {@code application.yml} <b>整块缺了 {@code rag:}</b> ⇒ ⇒ 共享层
 * {@code ProtectionProperties.OutboundDeadline#capMillis} 落回出厂默认
 * {@code 0}（= <b>不启用</b>）⇒ ⇒ 出向 gRPC 调用<b>没有任何 deadline</b>
 * ⇒ ⇒ 而 B 侧的 zrpc 客户端是<b>无条件</b>装 {@code TimeoutInterceptor}（2000ms）
 * ⇒ ⇒ <b>C 侧比 B 侧松到无穷</b>。
 * <p>实测症状（真 fat jar + 真 Nacos + 真 Redis + 真 PG）：
 * <pre>
 *   修复前：POST /favorite/v1/folder/create → curl 10s 超时（客户端先放弃）
 *   修复后：同一请求 → total=2.508546s → {"code":5001,"msg":"系统内部错误","data":null}
 * </pre>
 * ⇒ ⇒ 这是 AGENTS.md §3.3.1 说的那类坑：**「配置里没写」≠「没开」** ⇒
 * ⇒ 只看注解名 / 只看依赖表都判不出来 ⇒ ⇒ 只能靠<b>真触发</b>把它钉住。
 *
 * <p><b>⚠️ 本类刻意不 mock 拦截器</b>：断言的是
 * 「<b>生产 yaml → 属性绑定 → 共享层拦截器 → 真正的 {@link CallOptions}</b>」这条
 * <b>完整消费链</b>的产出，而不是「配置项的值是多少」。
 * ⇒ ⇒ 有人把 yaml 里那行删掉 ⇒ 本类第 2 条立刻红。
 *
 * <p><b>B 出处</b>：
 * <ul>
 *   <li>B 侧 api 侧：{@code service/favorite/favorite-api/src/main/resources/application.yml:194-197}
 *       （{@code rag.protection.outbound-deadline.cap-millis: ${FAVORITE_API_OUTBOUND_DEADLINE_CAP_MILLIS:2000}}）</li>
 *   <li>B 侧 rpc 侧：{@code service/favorite/favorite-rpc/src/main/resources/application.yml:219-221}
 *       （{@code rag.protection.outbound-deadline.cap-millis: 2000}）</li>
 *   <li>Go 侧（消费点，非声明点）：{@code go-zero@v1.10.2/zrpc/config.go:31}
 *       （{@code Timeout int64 \`json:",default=2000"\`}）+
 *       {@code go-zero@v1.10.2/zrpc/internal/client.go:113-117}（<b>无条件</b>装 TimeoutInterceptor）</li>
 * </ul>
 *
 * @see com.rag.common.protection.OutboundDeadlineInterceptor
 */
class FavoriteOutboundDeadlineContractTest {

    /** 生产 yaml 在 classpath 上的位置（= {@code src/main/resources/application.yml}）。 */
    private static final String PRODUCTION_YAML = "application.yml";

    /** B 侧 zrpc 客户端的硬上界（毫秒）。 */
    private static final long B_SIDE_ZRPC_CAP_MILLIS = 2000L;

    /**
     * 真调用允许的总耗时上界（毫秒）—— 给 2s 上界留出 3 倍余量。
     *
     * <p>⚠️ **不设下界**：慢机器上 2s 上界也可能花 2.5s 才返回 ⇒
     * ⇒ 断言只管「有没有被切断」与「没有无限挂起」，不管「多快被切断」。
     */
    private static final long REAL_CALL_TIMEOUT_MILLIS = 6_000L;

    // ────────────────────────── 1. 生产 yaml → 拦截器 → 真正的 CallOptions ──────────────────────────

    @Test
    @DisplayName("生产 yaml 的 rag.protection.outbound-deadline.cap-millis 真能变成出向调用的 deadline")
    void productionYamlCapIsAppliedToRealCallOptions() {
        ProtectionProperties props = bindProduction("rag.protection", ProtectionProperties.class);
        assertNotNull(props, "生产 yaml 里应当存在 rag.protection 段");
        assertEquals(B_SIDE_ZRPC_CAP_MILLIS, props.getOutboundDeadline().getCapMillis(),
                "生产 yaml 的 rag.protection.outbound-deadline.cap-millis 应当是 2000（= B 侧 zrpc 默认）");

        OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(props);
        AtomicReference<CallOptions> captured = new AtomicReference<>();
        long before = System.nanoTime();

        interceptor.interceptCall(someMethod(), CallOptions.DEFAULT, capturingChannel(captured));

        CallOptions effective = captured.get();
        assertNotNull(effective, "拦截器应当把算好的 CallOptions 交给下游 Channel");
        assertNotNull(effective.getDeadline(), "出向调用应当带 deadline（cap-millis=2000 ⇒ 必然有）");

        long budgetMillis = (effective.getDeadline().timeRemaining(TimeUnit.MILLISECONDS)
                + (System.nanoTime() - before) / 1_000_000L);
        assertTrue(budgetMillis > 0 && budgetMillis <= B_SIDE_ZRPC_CAP_MILLIS + 500L,
                "deadline 预算应当在 2000ms 附近（实测 " + budgetMillis + "ms）⇒ "
                        + "说明出向调用被 2s 硬上界覆盖，而不是没配/没生效");
    }

    // ────────────────────────── 2. 反向对照：删掉那行会怎样（证明本类有牙齿） ──────────────────────────

    @Test
    @DisplayName("反向对照：cap-millis=0（= 删掉生产 yaml 那行）时出向调用【没有】deadline")
    void zeroCapLeavesCallUnbounded() {
        // ⚠️ 这条是**反向对照**，不是被测行为：它证明「删掉 yaml 里那行」与「保留那行」
        //    在**可观察面**上完全不同 ⇒ ⇒ 第 1 条才不是恒绿。
        OutboundDeadlineInterceptor withoutCap = new OutboundDeadlineInterceptor(true, 0L);
        AtomicReference<CallOptions> captured = new AtomicReference<>();

        withoutCap.interceptCall(someMethod(), CallOptions.DEFAULT, capturingChannel(captured));

        assertNull(captured.get().getDeadline(),
                "cap-millis=0 时必须【没有】deadline（= 出向零期限）⇒ "
                        + "这正是修复前 favorite 的实际状态 ⇒ 删掉 yaml 那行 ⇒ 第 1 条会红");
    }

    // ────────────────────────── 3. 真传输：真 TCP + 黑洞服务端 ⇒ 被硬上界切断 ──────────────────────────

    @Test
    @DisplayName("真传输：下游永不回话时，出向调用被 2s 硬上界切断（而不是无限挂起）")
    void realCallAgainstBlackHoleServerIsCutOffByTheCap() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MethodDescriptor<StringValue, StringValue> blackHoleMethod = blackHoleMethod();
        Server server = NettyServerBuilder.forPort(0)
                .addService(io.grpc.ServerServiceDefinition.builder("blackhole.Service")
                        .addMethod(blackHoleMethod,
                                io.grpc.stub.ServerCalls.asyncUnaryCall(
                                        (StringValue request, io.grpc.stub.StreamObserver<StringValue> responseObserver) -> {
                                            try {
                                                // 🔴 永不响应 ⇒ 模拟「下游可达但不应答」
                                                release.await(30, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        }))
                        .build())
                .build()
                .start();
        try {
            int port = server.getPort();
            // 🔴🔴 **必须用 SocketAddress 重载**（不是 forAddress(String,int)）——
            //   2026-11-04 全量 verify 实测抓出（单独跑绿 / 全量跑红 ⇒ **红绿取决于执行顺序**）：
            //   · `forAddress(host, port)` 产生的 target 是**裸 authority**（"127.0.0.1:port"，
            //     **没有 scheme**）⇒ grpc 改用**全局默认 scheme** 去解析
            //   · 全局默认 scheme = **优先级最高的** provider 的 scheme
            //     ⇒ grpc DNS = 5、devh `DiscoveryClientResolverFactory` = **6**
            //       （devh 源码注释原文：`return 6; // More important than DNS`）
            //   · devh 的 `NameResolverRegistration` 在**上下文创建期**注册进**全局** registry
            //     ⇒ ⇒ **只要本 JVM 里创建过任何 Spring 上下文**，默认 scheme 就永久变 "discovery"
            //   ⇒ ⇒ 本机字面地址被当成**服务名**去查 ⇒ "No servers found for 127.0.0.1:<port>"
            //   ⇒ ⇒ 通道 UNAVAILABLE ⇒ ⇒ **server 明明在监听却连不上**
            //   ⇒ ⇒ 症状与「出向硬上界没生效」一模一样 ⇒ **极易误判成真回归**。
            //   ⇒ ⇒ SocketAddress 重载**不经过名字解析**（like 域同款，immune）
            //   ⇒ ⇒ 逐层源码出处 ⇒ like 域 `LikeGrpcNettyServerRealTest.java:79-99`。
            io.grpc.ManagedChannel channel = NettyChannelBuilder
                    .forAddress(new java.net.InetSocketAddress("127.0.0.1", port))
                    .usePlaintext()
                    .build();
            try {
                OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(
                        bindProduction("rag.protection", ProtectionProperties.class));

                // 🔴🔴 **必须把拦截器装到 channel 上**（2026-11-04 实测踩到的坑）：
                //   只 new 出拦截器而**不** `ClientInterceptors.intercept(channel, interceptor)`
                //   ⇒ ⇒ 拦截器**根本没进调用链** ⇒ ⇒ 出向调用**没有任何 deadline**
                //   ⇒ ⇒ 真调用**永久挂起**（不是超时，是挂死）
                //   ⇒ ⇒ 症状与「配置没生效」一模一样 ⇒ **极难自查**。
                // ⇒ 这也正是生产链路的样子：共享层靠 `@GrpcGlobalClientInterceptor` 把它装到
                //    **每一个**出向 devh 调用上 ⇒ 少了那一步 ⇒ 整条保护失效。
                io.grpc.Channel intercepted =
                        io.grpc.ClientInterceptors.intercept(channel, interceptor);

                long start = System.nanoTime();
                StatusRuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                        StatusRuntimeException.class,
                        () -> io.grpc.stub.ClientCalls.blockingUnaryCall(
                                intercepted,
                                blackHoleMethod,
                                CallOptions.DEFAULT,
                                StringValue.getDefaultInstance()));
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

                assertEquals(Status.Code.DEADLINE_EXCEEDED, thrown.getStatus().getCode(),
                        "应当是 DEADLINE_EXCEEDED（被硬上界切断）而不是别的错误；"
                                + "若拿到的是 UNAVAILABLE（且伴随 "
                                + "'No servers found for 127.0.0.1:<port>'）⇒ "
                                + "那是**全局 name resolver 被 devh 接管**（不是硬上界失效）⇒ "
                                + "检查本通道有没有改成 forAddress(SocketAddress) 形式");
                assertTrue(elapsedMillis <= REAL_CALL_TIMEOUT_MILLIS,
                        "调用应当在 " + REAL_CALL_TIMEOUT_MILLIS + "ms 内返回（实测 " + elapsedMillis
                                + "ms）⇒ 无限挂起说明硬上界没生效");
            } finally {
                channel.shutdownNow();
            }
        } finally {
            release.countDown();
            server.shutdownNow();
            server.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ────────────────────────── 4. 同一次生产 yaml 里另外三个被补齐的配置面 ──────────────────────────

    @Test
    @DisplayName("生产 yaml 的 observability 两个超时预算都在（缺任一 ⇒ 慢请求不会被切断）")
    void productionYamlCarriesBothObservabilityBudgets() {
        ObservabilityProperties obs = bindProduction("observability", ObservabilityProperties.class);
        assertNotNull(obs, "生产 yaml 里应当存在 observability 段");
        assertEquals(Duration.ofSeconds(3), obs.getHttpTimeout(),
                "observability.http-timeout 应当是 3s（= B 侧 api 的 go-zero rest 默认 3000ms）");
        assertEquals(Duration.ofSeconds(2), obs.getRpcTimeout(),
                "observability.rpc-timeout 应当是 2s（= B 侧 rpc 的 go-zero zrpc 默认 2000ms）");
        assertNotNull(obs.resolveInnerBudget(), "两个预算都在 ⇒ 内层预算可解析");
    }

    @Test
    @DisplayName("生产 yaml 带 management.metrics.tags.application（B 侧有测试守着这个标签）")
    void productionYamlCarriesPrometheusApplicationTag() {
        assertEquals("favorite-service",
                rawProductionValue("management.metrics.tags.application"),
                "management.metrics.tags.application 应当存在（B 侧 favorite-rpc 的 "
                        + "FavoriteRpcPrometheusCarrierGateTest:232-233 断言抓取结果里含该标签）");
    }

    @Test
    @DisplayName("生产 yaml 带 otel.service.name 与 otel.resource.attributes.service.name")
    void productionYamlCarriesOtelServiceName() {
        assertEquals("favorite-service", rawProductionValue("otel.service.name"),
                "otel.service.name 应当存在（共享层 GoStyleOtelConfig 读它）");
        assertEquals("favorite-service", rawProductionValue("otel.resource.attributes.service.name"),
                "otel.resource.attributes.service.name 应当存在（共享层两套拼法都认）");
    }

    @Test
    @DisplayName("生产 yaml 的 favorite 段保留 B 侧那三个 env 占位符（否则 env 绑定整层失效）")
    void productionYamlKeepsBsideEnvBindings() {
        // ⚠️ 这里读的是**原始**（未做占位符解析的）yaml 值 ⇒ 才拿得到 `${DC_PLATFORM_EVENT_URL:}` 这串字面量。
        //    若走 Environment.getProperty(...) ⇒ 占位符会被解析掉（env 未设 ⇒ 得到空串）⇒ 断言会误判。
        assertEquals("${DC_PLATFORM_EVENT_URL:}", rawProductionValue("favorite.fact.dispatch.dc-endpoint"),
                "B 侧 rpc yaml:299 的 dc-endpoint env 占位符必须保留 ⇒ 否则该 env 在 C 侧完全无效");
        assertEquals("${DC_PLATFORM_SERVICE_TOKEN:}", rawProductionValue("favorite.fact.dispatch.dc-token"),
                "B 侧 rpc yaml:300 的 dc-token env 占位符必须保留");
        assertEquals("${FAVORITE_AUTHORITY_TOKEN:}", rawProductionValue("favorite.fact.authority.token"),
                "B 侧 rpc yaml:321 的 authority.token env 占位符必须保留");
    }

    // ────────────────────────── 工具 ──────────────────────────

    /**
     * 读生产 yaml 里的**原始**字符串（不做占位符解析）。
     *
     * <p>⚠️ 必须走 {@link PropertySource#getProperty} 而**不是** {@code Environment.getProperty}：
     * 后者会把 {@code ${...}} 占位符**解析掉** ⇒ 拿到的就是解析后的值（env 未设 ⇒ 空串）
     * ⇒ ⇒ 本类要断言的正是「占位符**在不在**」⇒ ⇒ 只能读原始值。
     */
    private static String rawProductionValue(String key) {
        for (PropertySource<?> ps : productionPropertySources()) {
            Object raw = ps.getProperty(key);
            if (raw != null) {
                return String.valueOf(raw);
            }
        }
        return null;
    }

    /** 读**生产** {@code application.yml}（classpath 上的 {@code target/classes/application.yml}）。 */
    private static MutablePropertySources productionPropertySources() {
        MutablePropertySources mps = new MutablePropertySources();
        List<PropertySource<?>> loaded;
        try {
            loaded = new YamlPropertySourceLoader().load(PRODUCTION_YAML, new ClassPathResource(PRODUCTION_YAML));
        } catch (IOException e) {
            throw new IllegalStateException("读不到生产 application.yml", e);
        }
        assertTrue(!loaded.isEmpty(), "生产 application.yml 应当能被 YamlPropertySourceLoader 读到");
        loaded.forEach(mps::addLast);
        return mps;
    }

    /**
     * 用生产 yaml 绑定一个 {@code @ConfigurationProperties} 类。
     *
     * <p>⚠️ <b>必须带占位符解析器</b>：生产 yaml 里写的是
     * {@code cap-millis: ${FAVORITE_OUTBOUND_DEADLINE_CAP_MILLIS:2000}}
     * ⇒ 不解析占位符 ⇒ {@code Binder} 拿到的就是字面量 {@code "${...:2000}"}
     * ⇒ ⇒ 绑到 {@code long} 上直接抛「Failed to bind … to long」。
     */
    private static <T> T bindProduction(String prefix, Class<T> type) {
        MutablePropertySources mps = productionPropertySources();
        org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
        mps.forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.from(env.getPropertySources()),
                        new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(
                                env.getPropertySources()))
                .bind(prefix, type)
                .orElse(null);
    }

    /** 一个假的服务方法描述符（只需要服务全名 + 类型）。 */
    private static MethodDescriptor<StringValue, StringValue> someMethod() {
        return MethodDescriptor.<StringValue, StringValue>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("user.UserService/GetUser")
                .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(StringValue.getDefaultInstance()))
                .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(StringValue.getDefaultInstance()))
                .build();
    }

    /** 黑洞服务端的方法描述符（与服务名 {@code blackhole.Service} 对应）。 */
    private static MethodDescriptor<StringValue, StringValue> blackHoleMethod() {
        return MethodDescriptor.<StringValue, StringValue>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("blackhole.Service/Probe")
                .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(StringValue.getDefaultInstance()))
                .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(StringValue.getDefaultInstance()))
                .build();
    }

    /** 记录拦截器交给它的 {@link CallOptions} 的假 {@link Channel}。 */
    private static Channel capturingChannel(AtomicReference<CallOptions> captured) {
        return new Channel() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                    MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
                captured.set(callOptions);
                return new ClientCall<ReqT, RespT>() {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        // 断言只看 CallOptions ⇒ 这里不发任何请求
                    }

                    @Override
                    public void request(int numMessages) {
                    }

                    @Override
                    public void cancel(String message, Throwable cause) {
                    }

                    @Override
                    public void halfClose() {
                    }

                    @Override
                    public void sendMessage(ReqT message) {
                    }
                };
            }

            @Override
            public String authority() {
                return "fake";
            }
        };
    }
}
