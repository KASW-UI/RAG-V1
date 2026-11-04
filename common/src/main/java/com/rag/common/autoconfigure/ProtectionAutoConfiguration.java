package com.rag.common.autoconfigure;

import com.rag.common.observability.RpcPrometheusInterceptor;
import com.rag.common.observability.RpcRecoverInterceptor;
import com.rag.common.observability.RpcStatInterceptor;
import com.rag.common.protection.AdaptiveShedder;
import com.rag.common.protection.CpuThresholdHealthIndicator;
import com.rag.common.protection.MaxBytesFilter;
import com.rag.common.protection.MaxConnsFilter;
import com.rag.common.protection.ProtectionProperties;
import com.rag.common.protection.RouteResolution;
import com.rag.common.protection.RouteTemplateSource;
import com.rag.common.protection.RpcCircuitGateInterceptor;
import com.rag.common.protection.RpcProtectionChain;
import com.rag.common.protection.RpcSheddingInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>保护层的共享装配点</b>，同时是 <b>A3 断言点名的 gRPC 拦截链挂载点</b>。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 为什么这里是「唯一的 gRPC 挂载点」（A3 · 契约 B）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>实测依据</b>（javap，devh {@code grpc-server-spring-boot-starter 3.1.0.RELEASE}）：
 * <pre>
 *   net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer
 *       extends java.util.function.Consumer&lt;io.grpc.ServerBuilder&lt;?&gt;&gt;
 *   （三个 server factory 方法都只注入 List&lt;GrpcServerConfigurer&gt;，没有任何 interceptor 形参）
 * </pre>
 * ⇒ ⇒ <b>starter 只认 Configurer</b>；只产出一个 {@code ServerInterceptor} Bean
 * ⇒ <b>链上什么都不会发生</b>，而且<b>零报错</b>。
 * <br>⇒ ⇒ 本仓把挂载点收进共享层 <b>1 份</b>（域内 0 重复）⇒ 10 个域一次全挂上。
 *
 * <p><b>⚠️ 「链序」用代码结构写死，不押 API 细节</b>：
 * {@code io.grpc.ServerBuilder#intercept(ServerInterceptor)}（grpc-api 1.63.0 <b>只有单参重载</b>）
 * 多次调用时，<b>最后 add 的在最外层</b>
 * （实测：{@code ServerImplBuilder.interceptors} 是 append 到 List ⇒ {@code ServerImpl} 逐个
 * {@code interceptCallHandlerCreate} 包裹 ⇒ <b>越晚 add 的越外层</b>）。
 * <br>⇒ ⇒ 本类<b>只调一次</b> {@code server.intercept(...)}，链的嵌套由
 * {@link RpcProtectionChain#compose(List)} <b>显式</b>构造 ⇒ ⇒ 链序不依赖「谁更外层」这种容易记反的 API 细节。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L3 降级（D-02 / D-04）：两处生态缺口，本类只做到「能对拍」的那一半</b>
 * ══════════════════════════════════════════════════════════════════════
 * <ol>
 *   <li><b>D-02 熔断</b>：B 的 {@code rest/handler/breakerhandler.go} 用 googlebreaker
 *       （滑动窗口 / half-open 策略 / 错误率阈值全部自定义）⇒ resilience4j 的默认参数<b>不等价</b>
 *       ⇒ ⇒ <b>隔离单位</b>与<b>成败判据</b>逐字对齐（见下），<b>阈值参数不等价</b>（L3）。</li>
 *   <li><b>D-04 CPU 降载</b>：B 的 adaptive shedder 是「<b>CPU 过载</b> + <b>在途超动态上限</b>」
 *       <b>两个条件同时成立</b>才丢请求 ⇒ 生态无「在途超动态上限」的等价物
 *       ⇒ ⇒ 本类<b>只提供 CPU 这一半判据</b> ⇒ <b>比 B 侧 更激进</b> ⇒ <b>L3</b>，<b>不得反向"修回"</b>。</li>
 * </ol>
 *
 * <p><b>隔离 key（逐字对齐 B 侧）</b>：
 * <pre>
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:48
 *       chn = chn.Append(handler.BreakerHandler(route.Method, route.Path, metrics))
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:26
 *       brk := breaker.NewBreaker(breaker.WithName(strings.Join([]string{method, path}, "://")))
 * </pre>
 * ⇒ ⇒ 隔离 key = <b>{@code METHOD://<注册期路由模板>}</b>（<b>不是</b>原始 URI ⇒ 每路由一个实例）。
 * <br>⚠️ <b>路由模板的记法与 B 侧 不同</b>：B 侧 {@code route.Path} 是 goctl 生成的
 * {@code /article/:id}（path-to-regexp 风格）· Spring 侧是 {@code /article/{id}}
 * ⇒ ⇒ 隔离 key 的<b>字符串形态</b>两侧不同 ⇒ <b>L3（挂在 D-02 下）</b>。
 *
 * <p><b>成败判据（逐字对齐 B 侧）</b>：
 * <pre>
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:36-39
 *       defer func() {
 *           if cw.Code &lt; http.StatusInternalServerError { promise.Accept() }
 *           else { promise.Reject(...) }
 *       }()
 * </pre>
 * ⇒ ⇒ <b>失败 = HTTP 状态码 ≥ 500</b>；<b>下游 RPC 失败（HTTP 200 + 信封）不触发熔断</b>。
 *
 * <p><b>拒绝形状（逐字对齐 B 侧）</b>：
 * <pre>
 *   B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:32
 *       w.WriteHeader(http.StatusServiceUnavailable)     // ← 503，**无 body**
 * </pre>
 * ⇒ ⇒ <b>裸 503</b>：只 {@code setStatus}，<b>不</b> {@code sendError}
 * （后者会让容器补 {@code text/html} ⇒ 响应头比 B 侧 多一项）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/}
 * 下的 {@code GoStyleBreakerFilter} / {@code GoStyleCpuSheddingFilter} / {@code GoStyleMaxBytesFilter}
 * ＋ {@code GoStyleProtectionConfiguration}（L2 业务语义层以 RAG 为基准整段抄 · D13）
 * ＋ <b>熔断实现由「手写 googlebreaker 移植」改为「resilience4j」</b>（L4 · D4 / L-2 / D-02）。
 */
@AutoConfiguration
@EnableConfigurationProperties(ProtectionProperties.class)
public class ProtectionAutoConfiguration {

    // ── 体积上限 ──────────────────────────────────────────────────────────

    /**
     * 请求体大小上限 filter（B 侧 {@code rest/handler/maxbyteshandler.go}）。
     *
     * <p>⚠️ <b>条件注解必须写在 {@code @Bean} 方法上</b>：本类的
     * {@link MaxBytesFilter} 上的 {@code @ConditionalOnWebApplication} 只有在它被
     * <b>组件扫描</b>注册时才生效；用 {@code @Bean} 注册时<b>类级条件不被评估</b>
     * ⇒ ⇒ 条件必须落在方法上。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public MaxBytesFilter maxBytesFilter(ProtectionProperties properties) {
        return new MaxBytesFilter(properties);
    }

    // ── 并发连接上限 ──────────────────────────────────────────────────────

    /**
     * 并发连接数上限 filter（B 侧 {@code rest/handler/maxconnshandler.go}）。
     *
     * <p>⚠️ <b>为什么不能只靠 Tomcat 的 {@code max-connections} / {@code max-threads}</b>：
     * Tomcat 在超限时<b>排队</b>（连接排队 / 等线程空闲），B 侧 是<b>立刻 503</b>
     * ⇒ ⇒ 「变慢」与「被拒」是两种不同的可观察行为 ⇒ ⇒ 必须自己实现闸门
     * （详见 {@link MaxConnsFilter} 的类注释）。
     *
     * <p>⚠️ <b>条件注解必须写在 {@code @Bean} 方法上</b>（同 {@link #maxBytesFilter} 的理由：
     * 类级条件在 {@code @Bean} 注册路径下不被评估）。
     *
     * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/GoStyleMaxConnsFilter.java}
     * 整段抄（L2 · D13）＋ <b>装配形态回改</b>（L4 · D3 + D1）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public MaxConnsFilter maxConnsFilter(ProtectionProperties properties) {
        return new MaxConnsFilter(properties);
    }

    // ── CPU 降载的观测面 ──────────────────────────────────────────────────

    /**
     * CPU 降载的 Actuator 健康指标（D-04 的「CPU 那一半」判据）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CpuThresholdHealthIndicator cpuThresholdHealthIndicator(ProtectionProperties properties) {
        return new CpuThresholdHealthIndicator(properties);
    }

    /**
     * CPU 自适应降载 filter：<b>CPU 过载 <i>且</i> 在途超动态上限</b> ⇒ <b>裸 503</b>。
     *
     * <p>⚠️ <b>2026-11-02 批次 H：本 bean 此前只把 {@code CpuThresholdHealthIndicator} 灌进 filter
     * ⇒ filter 只判 CPU 一个条件 ⇒ 那是相对 B 侧的<b>真实漂移</b>（本仓比 B 更早丢请求）
     * ⇒ 现改为<b>自建一个 {@link AdaptiveShedder}</b> ⇒ 双条件判据回到 B 侧形态。</b>
     *
     * <p>⚠️ <b>刻意自建独立实例</b>（不与 gRPC 侧那个共用）：
     * B 侧每个 filter <b>自建</b> {@code GoStyleAdaptiveShedder}；B 侧 api / rpc 是<b>两个进程</b>
     * ⇒ 各有一份 shedder ⇒ ⇒ 本仓合并成单进程后<b>仍要两份</b>
     * ⇒ 否则 HTTP 在途数与 gRPC 在途数会<b>混在一个窗口里</b>（B 侧不会）⇒ 那才是新漂移。
     *
     * <p><b>拒绝形状</b>：B 侧 {@code rest/handler/sheddinghandler.go:33-35} ⇒ {@code w.WriteHeader(503)}
     * ⇒ 裸状态码、<b>空 body</b>、<b>无 Content-Type</b>（D-04 的硬要求）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Order(Ordered.HIGHEST_PRECEDENCE + 6)
    public CpuSheddingFilter cpuSheddingFilter(ProtectionProperties properties) {
        ProtectionProperties.CpuShedding cpu = properties.getCpuShedding();
        AdaptiveShedder shedder = new AdaptiveShedder(cpu.getCpuThreshold(), cpu.getCpuSource());
        return new CpuSheddingFilter(properties, shedder);
    }

    // ── 熔断 ──────────────────────────────────────────────────────────────

    /**
     * HTTP 熔断 filter：按 {@code METHOD://<路由模板>} 隔离，失败判据 = 状态码 ≥ 500。
     *
     * <p>⚠️ <b>熔断器实例由 resilience4j 的 {@link CircuitBreakerRegistry} 提供</b>
     * ⇒ 隔离 key 就是 <b>registry 的 name</b> ⇒ 「每路由一个实例」这条不变式由生态保证。
     * <br>⇒ ⚠️ registry <b>不</b>由本类创建：{@code resilience4j-spring-boot3} 自带
     * {@code CircuitBreakerRegistryAutoConfiguration} ⇒ <b>生态优先（D4）</b>，不重复造。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    public RouteIsolationFilter routeIsolationFilter(
            ProtectionProperties properties,
            ObjectProvider<CircuitBreakerRegistry> registryProvider,
            ObjectProvider<RouteTemplateSource> routeTemplateSource) {
        return new RouteIsolationFilter(properties, registryProvider, routeTemplateSource);
    }

    // ── gRPC 拦截链的挂载点（A3） ──────────────────────────────────────────

    /**
     * <b>gRPC 服务端拦截链的<b>唯一</b>挂载点</b>（A3 · 契约 B）。
     *
     * <p>⚠️ <b>为什么必须在这里、且只能有一份</b>：
     * <ul>
     *   <li>devh starter 只认 {@code GrpcServerConfigurer}（见类注释的 javap 证据）⇒
     *       没有它 ⇒ 拦截器 Bean 是「容器里没人用」的孤儿 ⇒ <b>静默失效</b>；</li>
     *   <li>域内再来一份 ⇒ 同一进程挂 2 条链 ⇒ span 重复 / timeout 叠加 ⇒ A3 判 FAIL。</li>
     * </ul>
     *
     * <p><b>链的构成</b>：容器里<b>全部</b> {@link ServerInterceptor} Bean
     * ⇒ 按 {@code @Order} / {@link Ordered} 排序 ⇒ <b>序号小的在最外层</b>
     * ⇒ ⇒ 与 B 的注册序语义一致（B 侧：<b>先注册的最外层</b>）。
     */
    @Bean
    @ConditionalOnClass(name = "net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer")
    @ConditionalOnMissingBean(name = "seaGrpcServerConfigurer")
    public net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer seaGrpcServerConfigurer(
            ObjectProvider<ServerInterceptor> interceptors) {
        return server -> {
            List<ServerInterceptor> ordered = interceptors.orderedStream().toList();
            ServerInterceptor chain = RpcProtectionChain.compose(ordered);
            if (chain != null) {
                // ⚠️ 只调一次：链的嵌套由 compose() 显式构造 ⇒ 不依赖「谁更外层」的 API 细节
                server.intercept(chain);
            }
        };
    }

    // ── gRPC 服务端保护链的 5 层（B 侧: recover → stat → prometheus → breaker → shedding） ──

    /**
     * 链序常量：<b>数值越小越靠外层</b>（Spring 的 {@code orderedStream()} 升序 ⇒ 第 0 个最外层）。
     *
     * <p>⚠️ <b>这组数值是「行为契约」，不是排版</b>（B 出处见 {@link RpcProtectionChain}）：
     * <ul>
     *   <li>{@code recover} 最外层 —— 否则内层的 panic 兜不住；</li>
     *   <li>{@code stat} 在 {@code prometheus} 外层 —— 量到的耗时含降载 / 熔断开销；</li>
     *   <li>{@code prometheus} 在 {@code breaker} / {@code shedding} 外层
     *       —— 否则被拒绝的请求在指标里<b>整个消失</b>；</li>
     *   <li>{@code breaker} 在 {@code shedding} 外层
     *       —— B 侧 shedding 的 {@code RESOURCE_EXHAUSTED} 冒泡上来被它记成失败。</li>
     * </ul>
     * ⇒ <b>业务观测层</b>（{@code ObservabilityAutoConfiguration.GrpcObservationInterceptor}）
     * 不带 {@code @Order} ⇒ 落在 {@code Ordered.LOWEST_PRECEDENCE} ⇒ <b>最内层</b> ✓
     * ⇒ ⇒ 链序 = {@code [recover, stat, prometheus, breaker, shedding, business]}。
     */
    public static final int RPC_LAYER_RECOVER = 0;
    public static final int RPC_LAYER_STAT = 1;
    public static final int RPC_LAYER_PROMETHEUS = 2;
    public static final int RPC_LAYER_BREAKER = 3;
    public static final int RPC_LAYER_SHEDDING = 4;

    /**
     * ① recover 层：gRPC handler panic ⇒ {@code INTERNAL + "panic: "}。
     *
     * <p>⚠️ <b>{@code "panic: "} 是 L1 wire 契约</b>（B 侧 {@code status.Errorf(codes.Internal, "panic: %v", r)}）
     * ⇒ ⇒ <b>不得改写</b>（改了就与 B 的客户端分叉）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.grpc.ServerInterceptor")
    @Order(RPC_LAYER_RECOVER)
    public RpcRecoverInterceptor rpcRecoverInterceptor() {
        return new RpcRecoverInterceptor();
    }

    /**
     * ② stat 层：每请求耗时样本（进程内 RPC 聚合器 + 每 60s 统计日志）。
     *
     * <p>🔴 <b>开关接线（V16 收口 · 2026-11-05）：本层读 {@code rag.protection.stat.enabled}</b>
     * <p><b>① 比 B 侧多做了什么</b>：B 侧 {@code GoStyleRpcProtectionChain.build} 用
     * {@code if (props.getStat().isEnabled())}（B: {@code :235}）<b>决定这一层是否被拼进链</b>
     * ⇒ ⇒ 开关关掉 ⇒ B 侧<b>不构造</b>这一层 ⇒ ⇒ 既无样本、也无那条 60s 的统计日志线程
     * （该线程只由 {@code GoStyleStatInterceptor.java:82} 带起来）。
     * 本仓<b>不删 Bean</b>（链的组成保持稳定）⇒ 改成 <b>把开关传进拦截器，由它决定「装不装」</b>
     * —— 与本类里 {@code breaker.enabled} / {@code cpu-shedding.enabled} 的既有惯例<b>同款</b>
     * （那两个开关同样是「Bean 恒在 ＋ 构造时传 enabled ＋ 关掉即透传」）。
     * <p><b>② 语义还一样吗</b>：<b>一样</b> —— 见 {@link RpcStatInterceptor} 类 javadoc 的「开关语义」段：
     * 可观测面三项全对齐（无样本 / 无那行 60s 日志 / 降载 drop 计数照旧「计了但看不见」）。
     * <p><b>③ B 出处</b>：{@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcProtectionChain.java:235}
     * ＋ {@code service/common/src/main/java/com/rag/common/observability/GoStyleStatInterceptor.java:82}
     * ＋ {@code zrpc/internal/config.go:23}（{@code Stat bool `json:",default=true"`}）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.grpc.ServerInterceptor")
    @Order(RPC_LAYER_STAT)
    public RpcStatInterceptor rpcStatInterceptor(ProtectionProperties properties) {
        return new RpcStatInterceptor(properties.getStat().isEnabled());
    }

    /**
     * ③ prometheus 层：耗时分布 + 状态码计数。
     *
     * <p>⚠️ <b>没有 registry ⇒ 本层整段跳过</b>（B 侧：{@code prometheus.Enabled()==false}）。
     *
     * <p>🔴 <b>开关接线（V16 收口 · 2026-11-05）：本层读 {@code rag.protection.prometheus.enabled}</b>
     * <p><b>① 比 B 侧多做了什么</b>：B 侧 {@code GoStyleRpcProtectionChain.build} 用
     * {@code if (props.getPrometheus().isEnabled())}（B: {@code :228}）<b>决定这一层是否被拼进链</b>
     * ⇒ ⇒ 开关关掉 ⇒ B 侧不构造这一层 ⇒ ⇒ <b>一条指标都不写</b>。
     * 本仓同样<b>不删 Bean</b> ⇒ 改成把开关传进拦截器（与 {@link RpcStatInterceptor} 同款）。
     * <p><b>② 语义还一样吗</b>：<b>一样</b> —— B 侧这一层只做「记耗时分布」＋「记状态码计数」两件事
     * ⇒ ⇒ 开关关掉 ⇒ 两件都不发生 ⇒ ⇒ 与「不在链上」逐项等价。
     * ⚠️ <b>两根轴要分清</b>：<b>开关</b>（配置意图）≠ <b>没有 registry</b>（没有地方可写）
     * ⇒ ⇒ 前者本轮补上，后者是既有语义，两根轴任一为「否」⇒ 本层一条都不写。
     * <p><b>③ B 出处</b>：{@code service/common/src/main/java/com/rag/common/protection/GoStyleRpcProtectionChain.java:228}
     * ＋ {@code zrpc/internal/config.go:24}（{@code Prometheus bool `json:",default=true"`}）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.grpc.ServerInterceptor")
    @Order(RPC_LAYER_PROMETHEUS)
    public RpcPrometheusInterceptor rpcPrometheusInterceptor(
            ProtectionProperties properties,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registryProvider) {
        return new RpcPrometheusInterceptor(registryProvider.getIfAvailable(),
                properties.getPrometheus().isEnabled());
    }

    /**
     * ④ breaker 层：gRPC 服务端熔断（隔离 key = gRPC 全方法名；失败判据 = 6 码白名单）。
     *
     * <p>⚠️ <b>拒绝形状是 wire 契约</b>：{@code UNAVAILABLE + "circuit breaker is open"}。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.grpc.ServerInterceptor")
    @Order(RPC_LAYER_BREAKER)
    public RpcCircuitGateInterceptor rpcCircuitGateInterceptor(
            ProtectionProperties properties,
            ObjectProvider<CircuitBreakerRegistry> registryProvider) {
        return new RpcCircuitGateInterceptor(
                properties.getBreaker().isEnabled(), registryProvider.getIfAvailable());
    }

    /**
     * ⑤ shedding 层：gRPC 服务端 CPU 自适应降载（拒绝 = {@code RESOURCE_EXHAUSTED + "service overloaded"}）。
     *
     * <p>⚠️ <b>拒绝必须走 {@code call.close(...)}</b> ⇒ 让错误冒泡到外层的 breaker 层
     * ⇒ ⇒ <b>持续降载最终会把熔断器打开</b>（B 侧同形）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.grpc.ServerInterceptor")
    @Order(RPC_LAYER_SHEDDING)
    public RpcSheddingInterceptor rpcSheddingInterceptor(ProtectionProperties properties) {
        ProtectionProperties.CpuShedding cpu = properties.getCpuShedding();
        boolean enabled = cpu.isEnabled() && cpu.getCpuThreshold() > 0L;
        AdaptiveShedder shedder = new AdaptiveShedder(cpu.getCpuThreshold(), cpu.getCpuSource());
        return new RpcSheddingInterceptor(shedder, enabled);
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    /**
     * CPU 自适应降载 filter（裸 503）。
     *
     * <p>⚠️ 拒绝形状与 B 侧 一致：<b>只 setStatus</b>（不 {@code sendError}、不写 body）
     * ⇒ ⇒ 无 body、无 Content-Type。
     *
     * <p><b>B 出处（声明点 + 消费点）</b>：B 侧 {@code service/common/src/main/java/com/rag/common/protection/GoStyleCpuSheddingFilter.java:20-34}
     * <pre>
     *   promise, err := shedder.Allow()
     *   if err != nil {
     *       metrics.AddDrop()
     *       w.WriteHeader(http.StatusServiceUnavailable)      // ← 503，无 body
     *       return
     *   }
     *   cw := response.NewWithCodeResponseWriter(w)
     *   defer func() {
     *       if cw.Code == http.StatusServiceUnavailable { promise.Fail() }
     *       else { sheddingStat.IncrementPass(); promise.Pass() }
     *   }()
     *   next.ServeHTTP(cw, r)
     * </pre>
     *
     * <p><b>抄写基准</b>：RAG {@code service/common/protection/GoStyleCpuSheddingFilter.java}
     * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
     *
     * <p><b>⚠️ 2026-11-02 批次 H：本类此前<b>只判 CPU</b>，那是真实漂移，现已按 B 侧修回</b>：
     * <ul>
     *   <li><b>漂移本体</b>：C 侧此前判据是 {@code indicator.isOverloaded()}（<b>只有 CPU 一个条件</b>）
     *       ⇒ ⇒ <b>比 B 更早丢请求</b>（B 侧 / B 是「CPU 过载 <b>且</b> 在途超动态上限」<b>两个条件同时成立</b>才丢）
     *       ⇒ ⇒ 台账此前把这条记成「D-04 已登记的 L3 降级」⇒ <b>但那是拿 B 侧 比 C，不是拿 B 比 C</b>
     *       ⇒ ⇒ B 侧 REST filter 本来就有完整自适应 shedder（{@code GoStyleAdaptiveShedder}）
     *       ⇒ ⇒ <b>C 丢了 B 已有的能力</b> ⇒ 按 verification.md §十 属 <b>FAIL</b>，不是已批准的降级。</li>
     *   <li><b>修法</b>：改用 {@link AdaptiveShedder}（本仓已有，是 B 侧 {@code GoStyleAdaptiveShedder} 的逐字抄）
     *       ⇒ <b>双条件判据 + pass/fail 记账 + RT 口径</b>三样一起回到 B 侧形态。</li>
     *   <li><b>L4 回改</b>：① {@code @Component} + {@code @ConditionalOnApiSide} → auto-config 的
     *       {@code @Bean} + {@code @ConditionalOnWebApplication(SERVLET)}（D3 / D1）；
     *       ② B 侧 {@code GoStyleStatusCapturingResponse}（包级私有 wrapper）→ <b>直接读 {@code response.getStatus()}</b>
     *       ⇒ <b>等价论证</b>：servlet 规范下应用未显式设码时 {@code getStatus()} 返回 200（= B 侧 wrapper 的
     *       {@code IMPLICIT_OK} 初值）；{@code sendError(sc)} 同样会反映到 {@code getStatus()}
     *       ⇒ ⇒ 两侧判据同值；③ B 侧 filter <b>自建</b> {@code GoStyleAdaptiveShedder}
     *       ⇒ C 侧也<b>自建独立实例</b>（不与 gRPC 侧共用）⇒ 对齐 B 的「每进程一份」
     *       （B 的 api / rpc 是两个进程 ⇒ 两份 shedder）。</li>
     *   <li><b>刻意保留</b>：B 侧 REST filter <b>没有</b>「cpuThreshold &gt; 0 才启用」的守卫
     *       （gRPC 侧 bean 有）⇒ 本类<b>逐字跟随 B</b> ⇒ 默认 {@code cpuThreshold = 900} ⇒ 该分支实际不可达。</li>
     * </ul>
     */
    static final class CpuSheddingFilter implements jakarta.servlet.Filter {

        private final boolean enabled;
        private final AdaptiveShedder shedder;

        CpuSheddingFilter(ProtectionProperties properties, AdaptiveShedder shedder) {
            this.enabled = properties.getCpuShedding().isEnabled();
            this.shedder = shedder;
        }

        @Override
        public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
                throws IOException, ServletException {
            if (!enabled) {
                chain.doFilter(req, resp);
                return;
            }
            HttpServletResponse response = (HttpServletResponse) resp;

            long startNanos = System.nanoTime();
            if (!shedder.allow()) {
                // sheddinghandler.go:29-35 —— 裸 503，无 body
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }

            try {
                chain.doFilter(req, response);
            } finally {
                // sheddinghandler.go:40-48 的 defer
                // ⚠️ 判据是「状态码 == 503」，**不是「>= 500」**（与 breaker 的 `< 500` 判据刻意不同，两处都照搬）
                if (response.getStatus() == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
                    shedder.fail();
                } else {
                    shedder.pass(elapsedRtMs(startNanos, System.nanoTime()));
                }
            }
        }

        /**
         * <b>RT 的计算口径</b> —— B 侧 {@code adaptiveshedder.go:285}：
         * <pre>
         * rt := float64(timex.Since(p.start)) / float64(time.Millisecond)
         * </pre>
         * ⇒ <b>float64 毫秒</b>，之后由 {@code math.Ceil} 向上取整（向上取整在
         * {@link AdaptiveShedder#pass(double)} 里做）。
         *
         * <p>⚠️ <b>必须是浮点除法</b>：若写成整型（{@code / 1_000_000L}）就是<b>截断</b>，
         * RT 会系统性少记最多 1ms ⇒ {@code minRt()} 偏小 ⇒
         * {@code maxFlight = maxPass * minRt * 0.01} 偏小 ⇒
         * <b>在途上限系统性偏低 ⇒ 本仓比 B 侧 更早降载</b>。
         *
         * <p>把口径提成<b>纯函数</b>后，「截断 vs 浮点」这一位差别就可以被<b>确定性</b>断言。
         */
        static double elapsedRtMs(long startNanos, long endNanos) {
            return (endNanos - startNanos) / 1_000_000.0D;
        }
    }

    /**
     * HTTP 熔断 filter（resilience4j 承载熔断器本身）。
     *
     * <p>⚠️ <b>本类不是「手写熔断器」</b>：熔断状态机、窗口、half-open 全部由
     * resilience4j 实现 ⇒ 本类只做三件事：
     * <ol>
     *   <li>算出<b>隔离 key</b>（{@code METHOD://<路由模板>}）；</li>
     *   <li>问 resilience4j「放不放行」（{@code tryAcquirePermission}）；</li>
     *   <li>把<b>最终状态码</b>回报给 resilience4j（≥500 = 失败）。</li>
     * </ol>
     */
    static final class RouteIsolationFilter implements jakarta.servlet.Filter {

        private final boolean enabled;
        private final ObjectProvider<CircuitBreakerRegistry> registryProvider;
        private final ObjectProvider<RouteTemplateSource> routeTemplateSource;

        RouteIsolationFilter(ProtectionProperties properties,
                             ObjectProvider<CircuitBreakerRegistry> registryProvider,
                             ObjectProvider<RouteTemplateSource> routeTemplateSource) {
            this.enabled = properties.getBreaker().isEnabled();
            this.registryProvider = registryProvider;
            this.routeTemplateSource = routeTemplateSource;
        }

        @Override
        public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) resp;

            if (!enabled) {
                chain.doFilter(request, response);
                return;
            }

            // ⚠️ 三态解析（V06 的职责断裂已修）：只有「确定未命中」才让本层完全不参与。
            RouteResolution resolution = resolveRoute(request);
            if (resolution.kind() == RouteResolution.Kind.NOT_MATCHED) {
                // B 出处：service/common/src/main/java/com/rag/common/protection/GoStyleBreakerFilter.java:177:244-268 —— 404 的链只有 Trace + Log，**没有 breaker**
                //          （patrouter.go:73 没匹配上 ⇒ handleNotFound ⇒ notFoundHandler）
                // ⇒ 判定「确定未命中」时，本层**完全不参与**（既不建 breaker，也不计数）
                chain.doFilter(request, response);
                return;
            }

            String isolationKey = isolationKeyOf(request, resolution);
            if (isolationKey == null) {
                // ⚠️ 只在 method 与 URI 都取不到时才走到这里（理论上不可达）
                chain.doFilter(request, response);
                return;
            }

            CircuitBreakerRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                // resilience4j 不在 classpath 上 ⇒ 熔断能力整体缺席 ⇒ 放行（不静默拒绝流量）
                chain.doFilter(request, response);
                return;
            }

            CircuitBreaker breaker = registry.circuitBreaker(isolationKey);
            if (!breaker.tryAcquirePermission()) {
                // ⚠️ 裸 503：只 setStatus（不 sendError、不写 body）⇒ 与 B 的 w.WriteHeader(503) 等价
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }

            long startNanos = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } catch (Throwable t) {
                breaker.onError(elapsed(startNanos), TimeUnit.NANOSECONDS, t);
                throw t;
            }
            int status = response.getStatus();
            if (status >= 500) {
                breaker.onError(elapsed(startNanos), TimeUnit.NANOSECONDS, ROUTE_FAILURE);
            } else {
                breaker.onSuccess(elapsed(startNanos), TimeUnit.NANOSECONDS);
            }
        }

        /**
         * 解析路由模板；<b>三态</b>里只有「确定未命中」会让调用方跳过 breaker。
         *
         * <p>⚠️ <b>解析不了（null / 抛异常）⇒ {@code UNKNOWN}</b>
         * ⇒ ⇒ 调用方<b>回落 raw URI 分桶</b>（熔断仍然生效）⇒ 绝不因为「解析不了」而静默关掉熔断保护。
         */
        private RouteResolution resolveRoute(HttpServletRequest request) {
            RouteTemplateSource source = routeTemplateSource.getIfAvailable();
            if (source == null) {
                return RouteResolution.UNKNOWN;
            }
            try {
                RouteResolution resolution = source.resolve(request);
                return resolution == null ? RouteResolution.UNKNOWN : resolution;
            } catch (RuntimeException e) {
                // 解析本身抛异常 ⇒ 判不出来 ⇒ 回落旧行为
                return RouteResolution.UNKNOWN;
            }
        }

        /**
         * 隔离 key = {@code METHOD://<注册期路由模板>}。
         *
         * <p>⚠️ <b>解析不出来时（{@code UNKNOWN}）回落 raw URI</b>（= 旧行为，不是新语义）：
         * RAG 侧 {@code GoStyleBreakerFilter#routeKey} 的写法是
         * <pre>
         *   if (resolution.kind() == Kind.TEMPLATE) { return method + "://" + resolution.template(); }
         *   return method + "://" + request.getRequestURI();
         * </pre>
         * ⇒ ⇒ <b>熔断能力在解析失败时依然生效</b>（只是隔离单位退化成「每 URI 一个」）。
         *
         * <p>⚠️ <b>⚠️ V05 的修法就在这一行</b>：修正前本方法在拿不到模板时返回 {@code null}
         * ⇒ 上游「放行、不熔断」⇒ ⇒ <b>熔断被静默关掉</b>（与 RAG 语义<b>相反</b>，
         * 且与本类 javadoc 自称的「绝不静默关掉熔断保护」<b>自相矛盾</b>）。
         * ⇒ 现改为回落 raw URI ⇒ 与 RAG 一致。
         */
        private String isolationKeyOf(HttpServletRequest request, RouteResolution resolution) {
            String method = request.getMethod();
            String prefix = (method == null ? "" : method) + "://";
            if (resolution.kind() == RouteResolution.Kind.TEMPLATE
                    && resolution.template() != null && !resolution.template().isEmpty()) {
                return prefix + resolution.template();
            }
            // ⚠️ UNKNOWN ⇒ 回落 raw URI 分桶（熔断仍生效）
            String uri = request.getRequestURI();
            return prefix + (uri == null ? "" : uri);
        }

        private static long elapsed(long startNanos) {
            return System.nanoTime() - startNanos;
        }
    }

    /**
     * 「状态码 ≥ 500 但没有异常」的失败信号。
     *
     * <p>⚠️ resilience4j 的 {@code recordException} 谓词默认为「全记」⇒ 本信号会被计入失败
     * ⇒ ⇒ 熔断器能感知「业务返回了 5xx」这件事（B 侧靠 {@code cw.Code >= 500} 判定，
     * <b>没有任何异常</b>）⇒ ⇒ 必须显式补一个信号，否则 B 侧的 5xx 失败在 Java 侧<b>永不计入</b>。
     */
    private static final RuntimeException ROUTE_FAILURE = new RouteFailure();

    /** 失败信号类型（私有嵌套，不产生文件 ⇒ 不影响 A12 的简单类名唯一性）。 */
    private static final class RouteFailure extends RuntimeException {
        private RouteFailure() {
            super("route returned status >= 500");
        }
    }
}
