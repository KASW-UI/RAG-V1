package com.rag.common.observability;

import com.rag.common.autoconfigure.ProtectionAutoConfiguration;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>DRIFT-COMMON-V16 的守门</b>：Stat / Prometheus 两个开关必须真的接到层上
 * —— 关掉 ⇒ 这一层<b>等于没装</b>（B 侧：它根本不拼进链）。
 *
 * <p>即 {@code rag.protection.stat.enabled} / {@code rag.protection.prometheus.enabled}
 * 这两个开关<b>必须真的接到层上</b>。
 *
 * <h2>为什么这条要守</h2>
 * <p>B 侧 {@code GoStyleRpcProtectionChain.build} 用两个开关<b>决定这两层是否被拼进链</b>：
 * <pre>
 *   B: GoStyleRpcProtectionChain.java:228   if (props.getPrometheus().isEnabled()) { chain = prometheusThen(chain); }
 *   B: GoStyleRpcProtectionChain.java:235   if (props.getStat().isEnabled())       { chain = statThen(chain); }
 *   └─ 开关关掉 ⇒ 这一层**根本不被构造** ⇒ ⇒ Stat 层：无样本、**也没有那条每 60s 的统计日志线程**
 *      （该线程的唯一来源 = GoStyleStatInterceptor.java:82 ⇒ 拦截器不被构造 ⇒ 线程也不存在）
 *      ⇒ Prometheus 层：**一条指标都不写**
 * </pre>
 * <p>⚠️ <b>C 侧修前</b>：两个 Bean <b>恒在</b>、且<b>不读这两个开关</b> ⇒
 * ⇒ Stat 层<b>照记样本</b>、那条 60s 日志<b>照打</b>；Prometheus 层<b>照写指标</b>
 * ⇒ ⇒ 开关一旦被置 {@code false}，两侧行为立刻分叉（<b>现行配置下两侧默认值同为 true
 * ⇒ 尚未分叉 ⇒ 这是「接线缺口」而非现行行为漂移</b>）。
 *
 * <h2>⚠️ 为什么判据落在「真 gRPC 调用 + 真 Spring 装配」上</h2>
 * <ul>
 *   <li><b>真 Spring 装配</b>：{@code ApplicationContextRunner} + 真属性绑定
 *       ⇒ 证的是「<b>配置键真的流到了层里</b>」，不是「代码里写了 if」；</li>
 *   <li><b>真 gRPC 调用</b>：真的起一个 in-process server、真的发一次 unary 调用
 *       ⇒ 证的是「<b>层在真执行期真的什么都不做</b>」，不是「构造器接受了这个参数」。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么这个类放在 {@code com.rag.common.observability} 包</h2>
 * <p>Stat 层的读数取自<b>进程级单例</b> {@link RpcStatMetrics} ⇒ 必须先把它重置成干净状态
 * ⇒ 而重置口 {@code RpcStatMetrics.resetInstanceForTest()} 是 <b>package-private</b>
 * （<b>B 侧同款</b>：{@code GoStyleStatMetrics.resetInstanceForTest()} 同样是包私有 ⇒ 不为测试放宽可见性）
 * ⇒ ⇒ 本类必须与它同包。
 *
 * <h2>两条「对照维」为什么必须有</h2>
 * <p>「关掉就不记」这种断言，<b>在「层压根没跑起来」时也是绿的</b> ⇒ ⇒ 必须同时钉住
 * <b>开关默认开着时确实会记 / 会写</b> ⇒ ⇒ 否则守门是<b>恒真</b>的。
 */
class ProtectionLayerSwitchWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtectionAutoConfiguration.class));

    @BeforeEach
    void resetProcessStatSingleton() {
        // ⚠️ Stat 层读的是**进程级单例** ⇒ 先重置 ⇒ 读数干净、且不撞别的测试留下的样本。
        //    （本仓 `common/` 下没有别的测试碰 RpcStatMetrics ⇒ 重置不会影响它们。）
        RpcStatMetrics.resetInstanceForTest();
    }

    // ── ① Stat 层 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 stat.enabled=false ⇒ Stat 层【不记样本】且【不起那条 60s 上报线程】"
            + "（B 侧：GoStyleRpcProtectionChain.java:235 ⇒ 这一层根本不在链上 ⇒ 既无样本、也无那行日志）")
    void statDisabledIsFullyInert() throws Exception {
        runner.withPropertyValues("rag.protection.stat.enabled=false").run(context -> {
            ServerInterceptor stat = context.getBean(RpcStatInterceptor.class);

            callOnce(stat);

            assertThat(RpcStatMetrics.getInstance().pendingSampleCount())
                    .as("开关关掉 ⇒ 一条样本都不能记（B 侧：层不在链上 ⇒ 不记）")
                    .isZero();
            assertThat(RpcStatMetrics.getInstance().reporterStarted())
                    .as("开关关掉 ⇒ 那条每 60s 一行的统计日志线程【也不能起】"
                            + "（B 侧那行日志不该出现 ⇒ 否则 C 会比 B 多打一行 ⇒ 这正是修法里"
                            + "「连线程一起不起」的那一半）")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("对照组：stat 开关默认 true ⇒ 同样那次调用【记一条样本】且上报线程已起"
            + "（否则「关掉就不记」是恒真断言）")
    void statEnabledByDefaultRecordsSample() throws Exception {
        runner.run(context -> {
            ServerInterceptor stat = context.getBean(RpcStatInterceptor.class);

            callOnce(stat);

            assertThat(awaitSampleCount(1L))
                    .as("对照组：开关默认开着 ⇒ 必须真的记一条样本")
                    .isEqualTo(1L);
            assertThat(RpcStatMetrics.getInstance().reporterStarted())
                    .as("对照组：开关默认开着 ⇒ 那条 60s 上报线程必须已经起了")
                    .isTrue();
        });
    }

    // ── ② Prometheus 层 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 prometheus.enabled=false ⇒ Prometheus 层【一条指标都不写】"
            + "（B 侧：GoStyleRpcProtectionChain.java:228 ⇒ 这一层根本不在链上 ⇒ 不写）")
    void prometheusDisabledWritesNoMeter() throws Exception {
        runner.withPropertyValues("rag.protection.prometheus.enabled=false")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    ServerInterceptor prometheus = context.getBean(RpcPrometheusInterceptor.class);

                    callOnce(prometheus);

                    // ⚠️ 负向断言要给一点时间：真开着的话，写入发生在 server close 那一刻。
                    Thread.sleep(300L);
                    assertThat(registry.getMeters())
                            .as("开关关掉 ⇒ registry 里必须【一个 meter 都没有】")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("对照组：prometheus 开关默认 true ⇒ 同样那次调用【写了耗时分布 + 状态码计数】两个 meter"
            + "（否则「关掉就不写」是恒真断言）")
    void prometheusEnabledByDefaultWritesMeters() throws Exception {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            ServerInterceptor prometheus = context.getBean(RpcPrometheusInterceptor.class);

            callOnce(prometheus);

            Set<String> names = awaitMeterNames(registry,
                    Set.of(RpcPrometheusInterceptor.DURATION_METRIC, RpcPrometheusInterceptor.CODE_METRIC));
            assertThat(names)
                    .as("对照组：开关默认开着 ⇒ 必须真的写出这两个 meter")
                    .containsExactlyInAnyOrder(RpcPrometheusInterceptor.DURATION_METRIC,
                            RpcPrometheusInterceptor.CODE_METRIC);
        });
    }

    // ── 真 gRPC 通路 ───────────────────────────────────────────────────────────

    /**
     * 起一个真的 in-process gRPC server，挂上给定的拦截器，<b>真的发一次 unary 调用</b>。
     *
     * <p>⚠️ 为什么不拿 Mockito 顶替：判据要证的是「层在<b>真执行期</b>真的什么都不做」
     * ⇒ ⇒ 必须走 grpc-java 的真执行模型（server 侧 close 那一刻才记样本 / 才写指标）。
     */
    private static void callOnce(ServerInterceptor interceptor) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
                .addService(echoService())
                .intercept(interceptor)
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        try {
            ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, "ping");
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ServerServiceDefinition echoService() {
        return ServerServiceDefinition.builder("test.Echo")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    responseObserver.onNext("ok");
                    responseObserver.onCompleted();
                }))
                .build();
    }

    // ── 等待读数（避免「写发生在 server close 那一刻」的时序竞态）─────────────────

    /** 等到样本数到达期望值（超时返回最后读数 ⇒ 断言负责报错）。 */
    private static long awaitSampleCount(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        long seen = RpcStatMetrics.getInstance().pendingSampleCount();
        while (seen != expected && System.nanoTime() < deadline) {
            Thread.sleep(20L);
            seen = RpcStatMetrics.getInstance().pendingSampleCount();
        }
        return seen;
    }

    /** 等到期望的 meter 名都出现（超时返回当时读到的名字集合 ⇒ 断言负责报错）。 */
    private static Set<String> awaitMeterNames(MeterRegistry registry, Set<String> expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Set<String> names = meterNames(registry);
        while (!names.containsAll(expected) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
            names = meterNames(registry);
        }
        return names;
    }

    private static Set<String> meterNames(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(m -> m.getId().getName())
                .collect(Collectors.toSet());
    }

    // ── 方法描述符 + 极简 marshaller ────────────────────────────────────────────

    private static final MethodDescriptor<String, String> METHOD =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Echo/Echo")
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
