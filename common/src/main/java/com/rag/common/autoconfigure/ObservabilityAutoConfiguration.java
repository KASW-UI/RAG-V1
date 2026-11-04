package com.rag.common.autoconfigure;

import com.rag.common.observability.DeadlineState;
import com.rag.common.observability.GoStyleRequestDeadline;
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
 * <p><b>⚠️ HTTP 侧的固定属性（逐条照抄 B 侧）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <pre>
 *   RAG HttpMiddleware#httpAttrs（照抄 B 侧 service/common/observability/http.go）
 *       service.name     = 服务名（空 ⇒ "unknown-service"）
 *       http.route       = r.getRequestURI()        ← ⚠️ **原始 URI**，**不是**路由模板
 *       http.method      = r.getMethod()
 *       http.status_code = 最终状态码（缺省 200）
 * </pre>
 * ⇒ ⇒ ⚠️ <b>与 Spring Boot 原生 HTTP 观测不同</b>：Boot 的 {@code http.route} 用的是
 * <b>匹配到的路由模板</b> ⇒ ⇒ 本类<b>自己写原始 URI</b>，不交给 Boot 默认值。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ gRPC 侧的固定属性与业务码集合（逐条照抄 B 侧 / RAG）</b>
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

    // ⚠️ B 的业务码集合（gRPC 侧「算 business 错误」的那 7 个码）**不在这里**：
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
     * ⇒ ⇒ 用<b>自研</b>的 {@link com.rag.common.observability.ObservabilityResponseWriter}
     * 缓存一份（读完之后原样写回）。
     * <p>🔴 <b>刻意不用 Spring 的 {@code ContentCachingResponseWrapper}</b>：
     * 它的 {@code flushBuffer()} 会<b>透传</b>到底层响应 ⇒ worker 线程（业务）能抢先提交响应
     * ⇒ 两个写者 ⇒ 状态码被抢 + Tomcat 的 {@code MimeHeaders} 被写坏 ⇒ NPE
     * ⇒ ⇒ 详见 {@link com.rag.common.observability.ObservabilityResponseWriter} 的类注释。
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
            // 🔴 **入向 deadline 的绝对时刻要在「早退之前」算**（B 出处 · `RpcInterceptor.java:516-522`）：
            // B 侧把 deadline 绑在**入口处算好的同一个绝对时刻**上 ⇒ ⇒ 排队 / 前置耗时被计入
            // ⇒ ⇒ 预算从「调用开始」起算，**不是**从「handler 开始跑」起算。
            // ⚠️ **必须早于 registry 早退**：绑 deadline 与「装没装 Micrometer registry」**无关**
            // ⇒ ⇒ 否则「没装观测的进程」就又没有 deadline ⇒ ⇒ 与 B 侧不符。
            //
            // 🔴 **有效期限 = min(服务端预算, 客户端 deadline 剩余)**（B 出处 · `RpcInterceptor.java:304`
            //   `long effectiveTimeoutNanos = Math.min(serverBudgetNanos, clientDeadlineRemainingNanos());`）
            //   ⇒ 与 Go 的 `context.WithTimeout(ctx, timeout)` 同语义（父 deadline 更早时**继承父的**）
            //   ⇒ ⇒ **绝对时刻在入口算一次**，两个载体共用 ⇒ 不会出现「SQL 已过期但出向还活着」。
            final long startNanos = System.nanoTime();
            final long effectiveTimeoutNanos = effectiveTimeoutNanos();
            final long absoluteDeadlineNanos = saturatingAddNanos(startNanos, effectiveTimeoutNanos);

            ObservationRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                return withDeadline(next.startCall(call, headers), absoluteDeadlineNanos);
            }
            String method = call.getMethodDescriptor().getFullMethodName();
            Duration timeout = properties == null ? null : properties.getRpcTimeout();

            Observation observation = Observation.createNotStarted(GRPC_OBSERVATION_NAME, registry)
                    .contextualName(method)
                    .lowCardinalityKeyValue("rpc.system", "grpc")
                    .lowCardinalityKeyValue("rpc.method", method);
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

            // ⚠️ 超时：B 的 rpc 超时由 zrpc 的 timeout 中间件执行 ⇒ 到点 ⇒ DEADLINE_EXCEEDED
            // 🔴 **延时用 `effectiveTimeoutNanos`（min 后的）而不是服务端自己的 timeout**
            //   （B 出处 · `RpcInterceptor.java:339-340`）⇒ ⇒ 与 Go 的 `context.WithTimeout(ctx, timeout)` 同语义
            //   ⇒ ⚠️ 若客户端 deadline 更短 ⇒ 服务端**跟着客户端**的节奏放弃（两侧同时死）
            //   ⇒ ⚠️ 若客户端 deadline 已过期 ⇒ effective=0 ⇒ 调度器**立即** fire（同 B 侧）
            ScheduledFuture<?> timeoutTask = scheduleTimeout(wrapped, timeout, effectiveTimeoutNanos);

            ServerCall.Listener<ReqT> listener = next.startCall(wrapped, headers);
            // ⚠️ 用**匿名**的 SimpleForwardingServerCallListener（它本身是 final ⇒ 不能被继承）
            ServerCall.Listener<ReqT> observed =
                    new io.grpc.ForwardingServerCallListener
                            .SimpleForwardingServerCallListener<ReqT>(listener) {
                @Override
                public void onCancel() {
                    super.onCancel();
                    cancelQuietly(timeoutTask);
                    finish(observation, Status.CANCELLED,
                            Duration.ofNanos(System.nanoTime() - startNanos), finalized);
                }
            };
            // 🔴 **绑定层在观测层的【外面】** ⇒ ⇒ 它的 scope 包住整个 handler 执行期
            //（含观测层的收尾）⇒ ⇒ 与 B 侧 `RpcInterceptor.java:516-522` 的嵌套次序同形。
            return withDeadline(observed, absoluteDeadlineNanos);
        }

        /**
         * 🔴 **把 deadline 绑到「真正跑 handler 的线程」上**（DRIFT-COMMON-V17 · PARITY-006 根因
         * ＋ **DRIFT-COMMON-V20 · D-15 出向载体**）。
         *
         * <p><b>比 B 侧多做了什么</b>：B 侧 {@code RpcInterceptor.newUnaryServerInterceptor} 把
         * 「超时调度」＋「span」＋「两个 deadline 载体」都塞在一个拦截器里；C 侧因 D11 包压平被拆成
         * {@code ObservabilityAutoConfiguration.GrpcObservationInterceptor}（观测 ＋ 超时调度）
         * ＋ 本方法（<b>绑两个载体</b>）。
         *
         * <p><b>语义还一样吗</b>：<b>一样</b>，且这是<b>向 B 靠拢</b> —— B 侧
         * {@code RpcInterceptor.java:516-522} 在 {@code onHalfClose} 里
         * {@code try (DeadlineState.Scope ignoredScope = DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos); …) { super.onHalfClose(); }}
         * ⇒ 本方法<b>逐字同形</b>（同一个绝对时刻、同样包住 {@code super.onHalfClose()}）。
         * <br>🔴 **2026-11-03 · D-15 收口**：此前本方法<b>只绑入向载体</b> {@code DeadlineState}
         * （B 侧同时绑的<b>出向</b>载体 {@code GoStyleRequestDeadline} 被判「另案」⇒ 一直拖着）
         * ⇒ ⇒ 现<b>两个载体绑同一个绝对时刻</b>，与 B 侧同形。
         *
         * <p><b>B 出处</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:516-522}
         * （{@code newUnaryServerInterceptor} 的 {@code onHalfClose}；L2 业务语义层以 RAG 为基准整段抄 · D13）。
         *
         * <p><b>为什么绑在 {@code onHalfClose} 而不是 {@code startCall}</b>：grpc-java 的执行模型是
         * 「{@code startCall} 返回 listener、<b>handler 真正在 {@code Listener#onHalfClose()} 里执行</b>」
         * ⇒ ⇒ 绑在 {@code startCall} 上 ⇒ handler 线程上看<b>不到</b> ThreadLocal。
         *
         * <p><b>可观察后果（这才是 PARITY-006 的修复点）</b>：handler 线程上有了预算 ⇒
         * {@code DeadlineQueryTimeoutInjector} 给每条 SQL 设 {@code setQueryTimeout}
         * ⇒ ⇒ 预算到点 PG 收到 <b>CancelRequest</b> ⇒ SQLState {@code 57014} ⇒ <b>事务回滚</b>
         * ⇒ ⇒ <b>客户端取消 / 服务端超时后，那条写不再落库</b>（修前 = 落库 ⇒ 副作用已发生）。
         *
         * <p><b>🔴 两个载体各自的消费点（绑了才生效）</b>：
         * <ul>
         *   <li><b>入向 {@code DeadlineState}</b> ⇒ {@code common/persistence/DeadlineQueryTimeoutInjector}
         *       给每条 SQL 设 query timeout ⇒ 在途写回滚；</li>
         *   <li><b>出向 {@code GoStyleRequestDeadline}</b> ⇒ ① {@code common/protection/OutboundDeadlineInterceptor:258}
         *       读剩余预算给<b>出向 gRPC</b> 设上界；② {@code common/redis/Deadline:114} 读剩余预算做
         *       <b>Redis 期限闸门</b>（预算耗尽 ⇒ 下发前就失败）。
         *       ⚠️ <b>修前这两个消费点在生产里恒读 {@code -1}（载体缺席）⇒ 期限闸门形同虚设</b>。</li>
         * </ul>
         *
         * <p><b>诚实边界</b>：① <b>无预算不绑</b>（服务端预算与客户端 deadline <b>都</b>没有 ⇒
         * 恒 {@link Long#MAX_VALUE} ⇒ 保持原样 ⇒ 与 B 侧「无预算时不绑出向载体」同款）；
         * ② 作用域随 {@code onHalfClose} 返回而结束
         * ⇒ ⇒ <b>异步 handler</b>（{@code onHalfClose} 返回后才继续跑）<b>不在作用域内</b>
         * ⇒ ⇒ 与 B 侧同款（本仓 10 个域的 proto 全是 unary ⇒ handler 在 {@code onHalfClose} 内同步跑完）。
         */
        private static <ReqT> ServerCall.Listener<ReqT> withDeadline(
                ServerCall.Listener<ReqT> delegate, long absoluteDeadlineNanos) {
            if (absoluteDeadlineNanos == Long.MAX_VALUE) {
                return delegate;
            }
            return new io.grpc.ForwardingServerCallListener
                    .SimpleForwardingServerCallListener<ReqT>(delegate) {
                @Override
                public void onHalfClose() {
                    // 🔴 **两个载体绑【同一个绝对时刻】**（B 出处 · `RpcInterceptor.java:516-522`）
                    //   ⇒ 二者指向**同一时刻** ⇒ 不会出现「SQL 已过期但出向还活着」
                    //   ⇒ ⚠️ 绑在**真正跑 handler 的线程**上（grpc-java 在 `onHalfClose` 里跑 handler）
                    try (DeadlineState.Scope scope =
                                 DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos);
                         GoStyleRequestDeadline.Scope carrier =
                                 GoStyleRequestDeadline.bindAbsoluteNanos(absoluteDeadlineNanos)) {
                        super.onHalfClose();
                    }
                }
            };
        }

        /**
         * gRPC 服务端的<b>有效期限</b> = {@code min(服务端预算, 客户端 deadline 剩余)}。
         *
         * <p>🔴 <b>为什么必须是 min（B 出处 · {@code RpcInterceptor.java:304}）</b>：
         * <pre>
         *   B 出处：service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:302-304
         *       long serverBudgetNanos = (timeout != null &amp;&amp; timeout.compareTo(Duration.ZERO) &gt; 0)
         *               ? toNanosSaturated(timeout) : Long.MAX_VALUE;
         *       long effectiveTimeoutNanos = Math.min(serverBudgetNanos, clientDeadlineRemainingNanos());
         * </pre>
         * ⇒ 与 Go 的 {@code context.WithTimeout(ctx, timeout)} <b>同语义</b>
         * （父 deadline 更早时 Go 直接继承父的）⇒ ⇒ **客户端给的更短 deadline 参与服务端有效期限**。
         *
         * <p>⚠️ <b>为什么服务端预算用 {@code rpc-timeout} 而不是 {@code resolveInnerBudget()}</b>：
         * 后者是 <b>HTTP 路径</b>的口径（= {@code min(http-timeout, rpc-timeout)}，因为 D2 域内直连把
         * DB 搬进了 HTTP 边界 ⇒ 内层预算必须<b>严格早于</b> HTTP 的 3s）；而<b>本方法在 gRPC 边界上</b>
         * ⇒ ⇒ 该边界上**没有 HTTP 预算** ⇒ ⇒ 预算就是 gRPC 自己的 {@code rpc-timeout}
         * （B 侧 {@code zrpc.RpcServerConf.Timeout = 2s}）⇒ ⇒ 与 {@code scheduleTimeout} 的超时**同一刻**。
         *
         * <p>⚠️ <b>「服务端没配 {@code rpc-timeout}」时客户端 deadline 独立生效</b>
         * ⇒ ⇒ 与 B 侧<b>代码</b>同形（{@code serverBudgetNanos = Long.MAX_VALUE} ⇒ min 之后就是客户端那个）。
         * ⚠️ <b>这条不是边角</b>：本仓 **comment / favorite / task / hot 四域的 {@code application.yml}
         * 没配 {@code observability.rpc-timeout}</b>（实测）⇒ ⇒ 这四域的客户端 deadline 是
         * <b>唯一</b>的预算来源 ⇒ ⇒ 不实现 min ⇒ 这四域的客户端 deadline **完全不参与** ⇒ ⇒ 与 B 侧漂移。
         * <br>⚠️ <b>B 侧那段注释与代码不一致</b>：注释写「{@code timeout<=0} 时保持旧行为（不引入客户端 deadline）」
         * ⇒ 但 B 侧<b>代码</b>是 {@code Math.min(serverBudgetNanos, clientDeadlineRemainingNanos())}
         * ⇒ ⇒ <b>以代码为准</b>（AGENTS.md §3.1：「不要拿代码注释当对照基准」）⇒ ⇒ 本仓照抄<b>代码</b>。
         *
         * <p>⚠️ <b>返回 {@link Long#MAX_VALUE} = 「无预算」</b> ⇒ 调用方据此<b>不绑任何载体</b>
         * （与 B 侧「Go 侧 ctx 此时没有 deadline ⇒ 出向载体不能绑」同款）。
         */
        private long effectiveTimeoutNanos() {
            long serverBudgetNanos = serverBudgetNanos();
            long clientRemainingNanos = clientDeadlineRemainingNanos();
            return Math.min(serverBudgetNanos, clientRemainingNanos);
        }

        /**
         * 服务端自己的 gRPC 预算（纳秒）；<b>没配 / 非正 / 溢出 ⇒ {@link Long#MAX_VALUE}</b>。
         *
         * <p>B 出处：{@code RpcInterceptor.java:302-303}（{@code toNanosSaturated(timeout)}）。
         */
        private long serverBudgetNanos() {
            Duration budget = properties == null ? null : properties.getRpcTimeout();
            if (budget == null || budget.isZero() || budget.isNegative()) {
                return Long.MAX_VALUE;
            }
            try {
                long nanos = budget.toNanos();
                return nanos <= 0L ? Long.MAX_VALUE : nanos;
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        /**
         * 客户端给的 deadline 的<b>剩余纳秒</b>；<b>客户端没带 deadline ⇒ {@link Long#MAX_VALUE}</b>。
         *
         * <p>🔴 <b>必须取纳秒</b>：取毫秒会把 grpc-java 内部的纳秒精度截到毫秒
         * ⇒ 客户端 deadline 的亚毫秒部分被丢弃（B 侧 ctx 是纳秒精度）⇒ 对拍会出现「差不到 1ms」的
         * 不可解释差异。
         *
         * <p>⚠️ <b>必须用全限定名</b>：本类的 import 里<b>没有</b> OTel 的 {@code io.opentelemetry.context.Context}
         * ⇒ 但为与 B 侧同款（其 {@code RpcInterceptor} import 了 OTel 的 Context）⇒ 一律写全限定名。
         *
         * <p>⚠️ <b>已过期 ⇒ 返回 0（不是负数）</b>：B 侧同款（{@code remaining > 0 ? remaining : 0L}）
         * ⇒ ⇒ effective = 0 ⇒ 绝对时刻 = 入口时刻 ⇒ 载体绑上即「已耗尽」
         * ⇒ ⇒ 调度器立即 fire（同 Go 的 {@code timeoutCtx.Done()} 已就绪 ⇒ select 立即走超时分支）。
         *
         * <p>B 出处：{@code RpcInterceptor.java:751-769}（{@code clientDeadlineRemainingNanos}）。
         */
        private static long clientDeadlineRemainingNanos() {
            try {
                io.grpc.Deadline deadline = io.grpc.Context.current().getDeadline();
                if (deadline == null) {
                    return Long.MAX_VALUE;
                }
                long remaining = deadline.timeRemaining(TimeUnit.NANOSECONDS);
                return remaining > 0 ? remaining : 0L;
            } catch (RuntimeException e) {
                return Long.MAX_VALUE;
            }
        }

        /**
         * 饱和加法：{@code base + delta} 溢出 ⇒ 返回 {@link Long#MAX_VALUE}（「几乎无限」）。
         *
         * <p>⚠️ <b>为什么不用 {@code base + delta}</b>：溢出后会变成<b>负数</b>
         * ⇒ 会被读成「已过期」⇒ 请求会被立刻判死（与 {@code DeadlineState} 里的饱和处理一致）。
         *
         * <p>B 出处：{@code RpcInterceptor.java:775-784}（{@code saturatingAddNanos}）。
         */
        private static long saturatingAddNanos(long base, long delta) {
            if (delta == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            try {
                return Math.addExact(base, delta);
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
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
                    // ⚠️ 观测收尾失败**不得**影响业务响应（B 侧同理：观测层不参与业务判定）
                }
            }
        }

        /**
         * 注册超时调度器：<b>延时用 {@code effectiveTimeoutNanos}（min 后的）而不是服务端自己的 timeout</b>。
         *
         * <p>🔴 <b>为什么延时用 min 后的值</b>（B 出处 · {@code RpcInterceptor.java:339-340}）：
         * 与 Go 的 {@code context.WithTimeout(ctx, timeout)} 同语义 ⇒ 客户端 deadline 更短时
         * ⇒ 服务端<b>跟着客户端</b>的节奏放弃 ⇒ ⇒ 两侧同时死（Go 侧 {@code ctx} 随客户端一起到期）。
         * <br>⚠️ <b>注册条件仍是「服务端预算为正」</b>（同 B 侧）：服务端没配 {@code rpc-timeout} 时
         * ⇒ <b>不注册</b>调度器（服务端不主动断连）⇒ 但<b>客户端 deadline 仍参与载体绑定</b>
         * ⇒ ⇒ 出向/入向的<b>剩余预算</b>按客户端的来 ⇒ 与 B 侧代码同形。
         *
         * @param timeout               服务端自己的 {@code rpc-timeout}（<b>只用于判「要不要注册」</b>）
         * @param effectiveTimeoutNanos 实际延时（= {@code min(服务端预算, 客户端 deadline 剩余)}）
         */
        private ScheduledFuture<?> scheduleTimeout(
                ServerCall<?, ?> call, Duration timeout, long effectiveTimeoutNanos) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                return null;
            }
            return timeoutScheduler().schedule(
                    () -> call.close(Status.DEADLINE_EXCEEDED
                            .withDescription("rpc deadline exceeded"), new Metadata()),
                    effectiveTimeoutNanos, TimeUnit.NANOSECONDS);
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
     * <p>⚠️ 静态单例 ⇒ 全进程一份（与 B 的观测层进程级静态池同形）。
     * <p>⚠️ <b>2026-11-02（批次 E）：本池已上收到 {@link ObservabilityRuntime}</b>
     * ⇒ 因为它要与 {@code obs-rpc-worker} 池<b>一起</b>被关闭钩子回收
     * ⇒ ⇒ 而关闭钩子（{@code ObservabilityLifecycle}）在本仓的 {@code observability} 包
     * ⇒ ⇒ 跨包 ⇒ ⇒ 池必须有一个<b>公开的持有者</b>。
     */
    private static ScheduledExecutorService timeoutScheduler() {
        return ObservabilityRuntime.timeoutScheduler();
    }

    /**
     * B 侧 {@code status.Error(...).Error()} 的文本形状。
     *
     * <p>⚠️ <b>已知残留差异</b>：gRPC Java 的 {@code Code#name()} 返回全大写（{@code NOT_FOUND}），
     * B 的 {@code codes.Code.String()} 返回驼峰（{@code NotFound}）⇒
     * <b>语言常量表示不同，不做臆造映射</b>（照抄 RAG 的同一处取舍）。
     * <p>⚠️ <b>2026-11-02（批次 E）</b>：实现已上收到
     * {@link ObservabilityCustomizer#goStyleStatusMessage(String, String)}
     * （{@code RpcHandleRunner} 也要用同一份拼法）⇒ 本方法只做一次转调。
     */
    private static String goStyleStatusMessage(Status status) {
        return ObservabilityCustomizer.goStyleStatusMessage(status.getCode().name(),
                status.getDescription());
    }

    /** 超时异常：沿 B 的语义用实际 status 拼，而不是硬编码文案。 */
    private static Throwable timeoutError(Status status) {
        return new java.util.concurrent.TimeoutException(goStyleStatusMessage(status));
    }

}
