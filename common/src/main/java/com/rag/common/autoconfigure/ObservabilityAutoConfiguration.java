package com.rag.common.autoconfigure;

import com.rag.common.observability.HttpMiddleware;
import com.rag.common.observability.ObservabilityCustomizer;
import com.rag.common.observability.ObservabilityLifecycle;
import com.rag.common.observability.ObservabilityRuntime;
import com.rag.common.protection.RpcLayer;
import com.rag.common.observability.ObservabilityProperties;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p><b>观测层的共享装配点</b> —— 把 {@link ObservabilityCustomizer}（业务标记的<b>唯一载体</b>）
 * 接进 <b>HTTP</b> 与 <b>gRPC</b> 两条真实链路。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ D4 生态重写（L-4 · D-03）：什么交给生态、什么必须自己写</b>
 * ══════════════════════════════════════════════════════════════════════
 * <table border="1">
 *   <tr><th>能力</th><th>归属</th><th>说明</th></tr>
 *   <tr><td>计时 / 指标名 / 百分位 / 上下文传播</td>
 *       <td>✅ <b>Micrometer {@link Observation}</b></td><td>生态原生，不再自写 OTel SDK 装配</td></tr>
 *   <tr><td><b>业务标记的键名与取值口径</b></td>
 *       <td>✅ {@link ObservabilityCustomizer}</td>
 *       <td>业务定义（{@code sea.slow} / {@code sea.biz_code} / …）⇒ <b>逐字保留</b>（D-03 保留项）</td></tr>
 *   <tr><td><b>错误分类的判定顺序</b></td>
 *       <td>✅ {@link ObservabilityCustomizer}</td>
 *       <td>「信封业务码优先于 status≥400」「gRPC 侧 DEADLINE_EXCEEDED 优先于业务码」</td></tr>
 * </table>
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ HTTP 侧的固定属性（逐条照抄 Go）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <pre>
 *   RAG HttpMiddleware#httpAttrs（照抄 Go service/common/observability/http.go）
 *       service.name     = 服务名（空 ⇒ "unknown-service"）
 *       http.route       = r.getRequestURI()        ← ⚠️ **原始 URI**，**不是**路由模板
 *       http.method      = r.getMethod()
 *       http.status_code = 最终状态码（缺省 200）
 * </pre>
 * ⇒ ⇒ ⚠️ <b>与 Spring Boot 原生 HTTP 观测不同</b>：Boot 的 {@code http.route} 用的是
 * <b>匹配到的路由模板</b> ⇒ ⇒ 本类<b>自己写原始 URI</b>，不交给 Boot 默认值。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ gRPC 侧的固定属性与业务码集合（逐条照抄 Go / RAG）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <pre>
 *   rpc.system         = "grpc"
 *   rpc.method         = 全方法名（io.grpc.MethodDescriptor#getFullMethodName）
 *   rpc.grpc.status_code = status.getCode().value()
 *   span 名            = 全方法名
 *
 *   业务码集合（RAG RpcInterceptor#isBusinessGrpcCode，7 个）：
 *       INVALID_ARGUMENT / NOT_FOUND / ALREADY_EXISTS / PERMISSION_DENIED
 *       / UNAUTHENTICATED / FAILED_PRECONDITION / OUT_OF_RANGE
 *   判定顺序：OK ⇒ 正常 · DEADLINE_EXCEEDED ⇒ timeout ⇒ 业务码 ⇒ business · 其余 ⇒ system
 * </pre>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/}
 * 下的 {@code HttpMiddleware} / {@code RpcInterceptor} / {@code Observability}
 * ＋ {@code spring/HttpMiddlewareFilter} / {@code spring/RpcServerInterceptorFactory}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）
 * ＋ <b>「手写 OTel span」改为「Micrometer Observation」</b>（L4 · D4 / L-4 / D-03）。
 */
@AutoConfiguration
@ConditionalOnClass(Observation.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    /** HTTP 侧的观测名（**定义在 {@link HttpMiddleware}** —— 本仓的 HTTP 中间件是独立类）。 */
    public static final String HTTP_OBSERVATION_NAME = HttpMiddleware.HTTP_OBSERVATION_NAME;

    /** gRPC 侧的观测名。 */
    public static final String GRPC_OBSERVATION_NAME = "rpc.server.requests";

    // ⚠️ Go 的业务码集合（gRPC 侧「算 business 错误」的那 7 个码）**不在这里**：
    // 2026-11-02（批次 E）已上收到 {@link ObservabilityCustomizer#BUSINESS_GRPC_CODES}
    // ⇒ 因为 gRPC 拦截器与 {@code RpcHandleRunner}（非 gRPC 路径）**两处**都要用同一组码
    // ⇒ ⇒ 留在本类会变成第二份副本 ⇒ ⇒ 判定统一走
    // {@link ObservabilityCustomizer#isBusinessGrpcCode(String)}。

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /**
     * 容器关闭时回收观测层的两个进程级线程池（{@code obs-rpc-timeout} / {@code obs-rpc-worker}）。
     *
     * <p>⚠️ <b>为什么必须由共享层收口</b>：两个池都是<b>进程级静态</b>的
     * ⇒ ⇒ 各域各写一份 {@code @PreDestroy} 只是把同一个调用复制 N 份、漏一份就静默失效
     * ⇒ ⇒ 共享层一份 ⇒ 凡是引入本 starter 的进程都自动生效。
     *
     * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/spring/ObservabilityLifecycle.java}
     * （L2 · D13）＋ <b>从 {@code spring} 包搬到 {@code observability} 包 + 改由 auto-config 注册</b>
     * （L4 · D3 / D11）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservabilityLifecycle observabilityLifecycle() {
        return new ObservabilityLifecycle();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    /**
     * HTTP 观测 filter：计时 + 判错 + 打业务标记。
     *
     * <p>⚠️ <b>判错的输入是「响应信封 + 最终状态码」</b> ⇒ ⇒ 必须能拿到响应体
     * ⇒ ⇒ 用 Spring 的 {@link ContentCachingResponseWrapper} 缓存一份（读完之后
     * {@code copyBodyToResponse()} 原样写回）。
     *
     * <p>⚠️ <b>判定顺序（可观察）</b>：
     * <pre>
     *   ① 信封 code != 0 &amp;&amp; code != 200 ⇒ **business**   ← 业务码优先于状态码
     *   ② 否则 statusCode &gt;= 400             ⇒ **system**
     *   ③ 否则                                  ⇒ 正常（只写 duration / slow）
     * </pre>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Order(Ordered.HIGHEST_PRECEDENCE + 7)
    public HttpMiddleware httpMiddleware(
            ObjectProvider<ObservationRegistry> registryProvider,
            ObservabilityProperties properties,
            @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new HttpMiddleware(registryProvider, properties, serviceName);
    }

    // ── gRPC ──────────────────────────────────────────────────────────────

    /**
     * gRPC 观测拦截器：计时 + 判错 + 打业务标记 + 超时。
     *
     * <p>⚠️ <b>产出本 Bean 不足以让它生效</b>：devh starter 只把
     * {@code List<GrpcServerConfigurer>} 注入 server factory ⇒
     * <b>真正挂上链的是 {@code ProtectionAutoConfiguration} 的挂载点</b>（A3）
     * ⇒ ⇒ 本拦截器通过 {@code @Order} 决定自己在链上的位置。
     *
     * <p>🔴 <b>2026-11-02（V03 修法）· {@code @ConditionalOnMissingBean} 必须按「bean 名」判，
     * 不能按「返回类型」判</b>：
     * <pre>
     *   改前：@ConditionalOnMissingBean            // ← 无参 ⇒ 按**返回类型** ServerInterceptor 判
     *   改后：@ConditionalOnMissingBean(name = "grpcObservationInterceptor")
     * </pre>
     * ⇒ 原因：本方法的返回类型是<b>接口</b> {@code ServerInterceptor}
     * ⇒ ⇒ 无参写法会匹配「容器里<b>任意</b> {@code ServerInterceptor} 类型的 Bean」
     * ⇒ ⇒ V03 修法在 {@code ProtectionAutoConfiguration} 里新增了 5 个 {@code ServerInterceptor} Bean
     * ⇒ ⇒ 本 Bean 被 {@code @ConditionalOnMissingBean} 判成「已存在」⇒ <b>静默不注册</b>
     * ⇒ ⇒ <b>业务观测层从链上消失</b>（零报错）⇒ ⇒ 由
     * {@code ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo} 当场抓到。
     * <br>⇒ 教训：<b>「按接口/父类型判缺失」在「同类 Bean 会变多」的场景里是陷阱</b>
     * ⇒ ⇒ 本仓所有「返回类型是接口」的 Bean 一律按<b>名字</b>判缺失。
     */
    @Bean
    @ConditionalOnMissingBean(name = "grpcObservationInterceptor")
    public ServerInterceptor grpcObservationInterceptor(
            ObjectProvider<ObservationRegistry> registryProvider,
            ObservabilityProperties properties) {
        return new GrpcObservationInterceptor(registryProvider, properties);
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────


    /**
     * gRPC 观测拦截器（私有嵌套）。
     *
     * <p>⚠️ <b>⚠️ 线程模型 caveat</b>：Micrometer 的 {@link Observation#start()} 会
     * <b>打开一个线程本地 scope</b>，而 gRPC 的 {@code close()} 可能在<b>另一个线程</b>发生
     * （客户端取消 / 流式）⇒ ⇒ 本类假设 <b>unary 调用</b>（本仓 10 个域的 proto 全是 unary）
     * ⇒ close 与 start 同线程 ⇒ scope 正常闭合。
     * ⇒ ⇒ 流式调用接入前必须重新评估（本条已写入类注释，不留给下一个 agent 去踩）。
     */
    static final class GrpcObservationInterceptor implements ServerInterceptor, RpcLayer {

        private final ObjectProvider<ObservationRegistry> registryProvider;
        private final ObservabilityProperties properties;

        GrpcObservationInterceptor(ObjectProvider<ObservationRegistry> registryProvider,
                                   ObservabilityProperties properties) {
            this.registryProvider = registryProvider;
            this.properties = properties;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            ObservationRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                return next.startCall(call, headers);
            }
            String method = call.getMethodDescriptor().getFullMethodName();
            Duration timeout = properties == null ? null : properties.getRpcTimeout();

            Observation observation = Observation.createNotStarted(GRPC_OBSERVATION_NAME, registry)
                    .contextualName(method)
                    .lowCardinalityKeyValue("rpc.system", "grpc")
                    .lowCardinalityKeyValue("rpc.method", method);
            long startNanos = System.nanoTime();
            AtomicBoolean finalized = new AtomicBoolean(false);
            AtomicReference<Status> closedStatus = new AtomicReference<>();
            observation.start();

            ServerCall<ReqT, RespT> wrapped =
                    new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                        @Override
                        public void close(Status status, Metadata trailers) {
                            closedStatus.set(status);
                            super.close(status, trailers);
                            finish(observation, status, Duration.ofNanos(System.nanoTime() - startNanos), finalized);
                        }
                    };

            // ⚠️ 超时：Go 的 rpc 超时由 zrpc 的 timeout 中间件执行 ⇒ 到点 ⇒ DEADLINE_EXCEEDED
            ScheduledFuture<?> timeoutTask = scheduleTimeout(wrapped, timeout);

            ServerCall.Listener<ReqT> listener = next.startCall(wrapped, headers);
            // ⚠️ 用**匿名**的 SimpleForwardingServerCallListener（它本身是 final ⇒ 不能被继承）
            return new io.grpc.ForwardingServerCallListener
                    .SimpleForwardingServerCallListener<ReqT>(listener) {
                @Override
                public void onCancel() {
                    super.onCancel();
                    cancelQuietly(timeoutTask);
                    finish(observation, Status.CANCELLED,
                            Duration.ofNanos(System.nanoTime() - startNanos), finalized);
                }
            };
        }

        private void finish(Observation observation, Status status, Duration duration, AtomicBoolean finalized) {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            try {
                observation.lowCardinalityKeyValue("rpc.grpc.status_code",
                        String.valueOf(status.getCode().value()));
                Observation.Context ctx = observation.getContext();
                if (status.getCode() == Status.Code.OK) {
                    ObservabilityCustomizer.markDurationAndSlow(ctx, duration, slowThreshold());
                } else if (status.getCode() == Status.Code.DEADLINE_EXCEEDED) {
                    ObservabilityCustomizer.markTimeout(ctx, timeoutError(status), duration);
                } else if (ObservabilityCustomizer.isBusinessGrpcCode(status.getCode().name())) {
                    ObservabilityCustomizer.markBusinessError(ctx, status.getCode().value(),
                            goStyleStatusMessage(status), duration);
                } else {
                    ObservabilityCustomizer.markSystemError(
                            ctx, new RuntimeException(goStyleStatusMessage(status)), duration);
                }
            } finally {
                try {
                    observation.stop();
                } catch (RuntimeException ignore) {
                    // ⚠️ 观测收尾失败**不得**影响业务响应（Go 侧同理：观测层不参与业务判定）
                }
            }
        }

        private ScheduledFuture<?> scheduleTimeout(ServerCall<?, ?> call, Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                return null;
            }
            return timeoutScheduler().schedule(
                    () -> call.close(Status.DEADLINE_EXCEEDED
                            .withDescription("rpc deadline exceeded"), new Metadata()),
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private static void cancelQuietly(ScheduledFuture<?> task) {
            if (task != null) {
                task.cancel(false);
            }
        }

        private Duration slowThreshold() {
            Duration configured = properties == null ? null : properties.getSlowThreshold();
            return configured != null ? configured : ObservabilityCustomizer.slowThreshold();
        }

        /** 链上的层名（供守门断言链序）—— 本层是<b>最内层</b>的业务观测层。 */
        @Override
        public String layerName() {
            return "business";
        }
    }

    /**
     * gRPC 超时用的调度器（<b>守护线程</b> ⇒ 不阻止 JVM 退出）。
     *
     * <p>⚠️ 静态单例 ⇒ 全进程一份（与 Go 的观测层进程级静态池同形）。
     * <p>⚠️ <b>2026-11-02（批次 E）：本池已上收到 {@link ObservabilityRuntime}</b>
     * ⇒ 因为它要与 {@code obs-rpc-worker} 池<b>一起</b>被关闭钩子回收
     * ⇒ ⇒ 而关闭钩子（{@code ObservabilityLifecycle}）在本仓的 {@code observability} 包
     * ⇒ ⇒ 跨包 ⇒ ⇒ 池必须有一个<b>公开的持有者</b>。
     */
    private static ScheduledExecutorService timeoutScheduler() {
        return ObservabilityRuntime.timeoutScheduler();
    }

    /**
     * Go {@code status.Error(...).Error()} 的文本形状。
     *
     * <p>⚠️ <b>已知残留差异</b>：gRPC Java 的 {@code Code#name()} 返回全大写（{@code NOT_FOUND}），
     * Go 的 {@code codes.Code.String()} 返回驼峰（{@code NotFound}）⇒
     * <b>语言常量表示不同，不做臆造映射</b>（照抄 RAG 的同一处取舍）。
     * <p>⚠️ <b>2026-11-02（批次 E）</b>：实现已上收到
     * {@link ObservabilityCustomizer#goStyleStatusMessage(String, String)}
     * （{@code RpcHandleRunner} 也要用同一份拼法）⇒ 本方法只做一次转调。
     */
    private static String goStyleStatusMessage(Status status) {
        return ObservabilityCustomizer.goStyleStatusMessage(status.getCode().name(),
                status.getDescription());
    }

    /** 超时异常：沿 Go 的语义用实际 status 拼，而不是硬编码文案。 */
    private static Throwable timeoutError(Status status) {
        return new java.util.concurrent.TimeoutException(goStyleStatusMessage(status));
    }

}
