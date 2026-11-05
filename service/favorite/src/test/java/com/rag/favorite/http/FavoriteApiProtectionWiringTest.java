package com.rag.favorite.http;

import com.rag.common.protection.ClientCircuitGateInterceptor;
import com.rag.common.protection.MaxBytesFilter;
import com.rag.common.protection.MaxConnsFilter;
import com.rag.common.protection.ProtectionProperties;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证明 favorite 域的**请求保护层真的装配上了**，而不是「配置里写了 / 类上标了注解」。
 *
 * <p><b>为什么这条不能省</b>：
 * 共享层的出向熔断拦截器曾有「client 熔断<b>尚未接入</b>」这种断言 ——
 * 那是一条 {@code Go: …} 形态的断言，而 AGENTS.md §3.3.2 明确指出
 * 「以 Go: … 措辞出现的断言本身就是高危项」，必须用**运行证据**判定，不能靠推断。
 * <p>Go 侧的对应事实（真源）：
 * <ul>
 *   <li>{@code zrpc/internal/config.go:16}
 *       {@code Breaker bool json:",default=true"}；</li>
 *   <li>{@code zrpc/internal/client.go:112-113}
 *       {@code if c.middlewares.Breaker { interceptors = append(interceptors, clientinterceptors.BreakerInterceptor) }}；</li>
 *   <li>{@code rest/config.go:15-21} 的六个中间件开关（Breaker / Shedding / MaxConns / MaxBytes …）
 *       全部 {@code default=true}；</li>
 *   <li>{@code rest/config.go:52-56}：{@code MaxConns=10000}、{@code MaxBytes=1048576}、
 *       {@code CpuThreshold=900}。</li>
 * </ul>
 *
 * <p><b>验证路径</b>：读 starter 自己的 {@code GlobalClientInterceptorRegistry}
 * （它由 {@code GrpcClientAutoConfiguration#nettyGrpcChannelFactory} 消费），
 * 断言本拦截器确实在它收集到的列表里 —— 即 channel factory 会把它装进 channel。
 * 只断言「Bean 存在」是不够的：那只能证明容器里有这个对象，
 * 证明不了 channel factory 会不会消费它（AGENTS.md §3.3 的四类静默失效）。
 *
 * <p><b>⚠️ 本类的证据边界</b>：服务端四层 filter（MaxConns / Breaker / CpuShedding / MaxBytes）
 * 在此断言的是「<b>Bean 存在，且四个 filter 的<b>相对</b>次序与 Go 的链序一致</b>」，
 * <b>不是</b>「真 Tomcat 的 filter chain 里真的跑了它」——
 * ⇒ 「filter 真在 chain 里生效」由本域的<b>真 Tomcat 门禁</b>
 * （{@code http/FavoriteApiRealTomcatGateTest}）端到端覆盖。
 *
 * <p><b>⚠️ 为什么断言「相对链序」而不是「{@code @Order} 的绝对值」</b>：
 * <ul>
 *   <li>Go 的链序真源是 <b>go-zero v1.10.2</b> {@code rest/engine.go:130-168}
 *       （{@code buildChainWithNativeMiddlewares}）：
 *       Trace({@code :135}) → Log({@code :140}) → Prometheus({@code :143}) →
 *       <b>MaxConns({@code :146}) → Breaker({@code :149}) → Shedding({@code :152})</b> →
 *       Timeout → Recover → Metrics → <b>MaxBytes({@code :164})</b>。
 *       ⇒ 四个保护中间件的<b>相对次序</b> = MaxConns → Breaker → Shedding → MaxBytes。</li>
 *   <li>而这四个 filter 的 {@code @Order} <b>绝对值属于共享层</b>
 *       （{@code common/} 的写权在 {@code global} 域）
 *       ⇒ 本类只锁「Go 侧真正承载语义的那部分」（相对次序），
 *       <b>不锁共享层的排版细节</b>（绝对数值）——
 *       绝对数值一旦被别的域<b>合法</b>调整，本域守门网就会<b>假红</b>。</li>
 * </ul>
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>包名</b>压平（D7）：{@code com.rag.favorite.favorite.api} ⇒ {@code com.rag.favorite.http}。</li>
 *         <li><b>共享层类名改写</b>（D3 starter 化 + D7）：
 *             {@code GoStyleProtectionProperties} ⇒ {@link ProtectionProperties}；
 *             {@code GoStyleGrpcClientBreakerInterceptor} ⇒ {@link ClientCircuitGateInterceptor}；
 *             {@code GoStyleMaxConnsFilter} ⇒ {@link MaxConnsFilter}；
 *             {@code GoStyleMaxBytesFilter} ⇒ {@link MaxBytesFilter}；
 *             {@code GoStyleBreakerFilter} ⇒ 共享层 {@code ProtectionAutoConfiguration.RouteIsolationFilter}；
 *             {@code GoStyleCpuSheddingFilter} ⇒ 共享层 {@code ProtectionAutoConfiguration.CpuSheddingFilter}。</li>
 *         <li>🔴 <b>熔断 / 降载两个 filter 的类型是共享层的<b>包级可见嵌套类</b></b>
 *             ⇒ ⇒ 本测试类在 {@code com.rag.favorite.http} 包里<b>无法 import 它们</b>
 *             ⇒ ⇒ 改为<b>按 bean 名取</b>（{@code ctx.getBean("cpuSheddingFilter")}），
 *             并<b>按 {@code @Bean} 方法上的 {@code @Order}</b> 取它们的序值
 *             （见 {@link #orderOf(String, Class)}）。</li>
 *         <li><b>不再有 {@code FavoriteRpcClient}</b>：C 侧 D2 取消域内自我 gRPC ⇒
 *             B 侧那个 {@code @MockitoBean FavoriteRpcClient} <b>整体删除</b>
 *             （C 侧 favorite 域<b>没有</b>这个类型 ⇒ 保留它会编译不过）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 六条断言的判据（registry 收录 / 默认值逐条 / 四个 Bean 存在 /
 *       三段相对次序）逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/FavoriteApiProtectionWiringTest.java:19-185}。</li>
 * </ol>
 */
@SpringBootTest(
        classes = {com.rag.favorite.FavoriteServiceApplication.class,
                com.rag.favorite.support.NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:65535",
                "grpc.client.article-rpc.address=static://127.0.0.1:65535",
                "grpc.client.user-rpc.negotiation-type=plaintext",
                "grpc.client.article-rpc.negotiation-type=plaintext",
                "grpc.server.port=0",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 🔴 C 侧 FavoriteApiProperties 的密钥守卫是 fail-closed 的（缺值 ⇒ 启动失败）
                "favorite-api.user-auth.access-secret=test-only-favorite-jwt-secret-at-least-32-bytes",
                // DB 不可达：本类只验「保护层装配」，不碰业务表 ⇒ 池不建连、Flyway 不跑
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/first_db",
                "spring.datasource.username=admin",
                "spring.datasource.password=password",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.flyway.enabled=false"
        })
class FavoriteApiProtectionWiringTest {

    @Autowired
    private GlobalClientInterceptorRegistry registry;

    @Autowired
    private ClientCircuitGateInterceptor interceptor;

    @Autowired
    private ProtectionProperties props;

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private MaxConnsFilter maxConnsFilter;

    @Autowired
    private MaxBytesFilter maxBytesFilter;

    /**
     * 🔴 Nacos 桩 —— 见 {@link com.rag.favorite.support.NacosStubConfiguration} 的类注释。
     *
     * <p>⚠️ <b>本类刻意用 {@code @TestConfiguration} 桩，而不是 {@code @MockitoBean} + {@code @BeforeEach}</b>：
     * 域内 {@code FavoriteNacosRegistration#register()} 挂在 {@code ApplicationReadyEvent} 上
     * ⇒ ⇒ 该事件**早于**任何 {@code @BeforeEach} ⇒ ⇒ 后打桩**来不及**
     * ⇒ ⇒ {@code getNamingService()} 此刻是 Mockito 的默认 **null** ⇒ ⇒ NPE ⇒ ⇒ 上下文起不来。
     */

    @Test
    @DisplayName("client 熔断拦截器在 starter 的 GlobalClientInterceptorRegistry 中 ⇒ channel factory 会装配它")
    void clientBreakerInterceptorIsRegisteredWithTheStarter() {
        assertTrue(registry.getClientInterceptors().contains(interceptor),
                "拦截器必须在 starter 收集到的 ClientInterceptor 列表里；"
                        + "否则它只是个 Bean，永远不会进入下游服务的调用链，"
                        + "本模块就退化成「Go 有熔断、RAG 没有」的漂移状态。"
                        + "实际列表=" + registry.getClientInterceptors());
    }

    @Test
    @DisplayName("保护层默认开关与 Go 的 default=true 逐条一致（Breaker / Shedding / MaxConns / MaxBytes）")
    void protectionDefaultsMatchGo() {
        assertTrue(props.getBreaker().isEnabled(),
                "Go rest/config.go:16 Breaker default=true ⇒ 熔断必须默认开启");
        assertTrue(props.getCpuShedding().isEnabled(),
                "Go rest/config.go:17 Shedding default=true ⇒ CPU 降载必须默认开启");
        assertEquals(10000, props.getMaxConns(),
                "Go rest/config.go:52 MaxConns default=10000");
        assertEquals(1048576L, props.getMaxBytes(),
                "Go rest/config.go:53 MaxBytes default=1048576（1 MiB）");
        assertEquals(900L, props.getCpuShedding().getCpuThreshold(),
                "Go rest/config.go:56 CpuThreshold default=900");
    }

    @Test
    @DisplayName("服务端四层 filter 都装配成 Bean")
    void serverSideProtectionFiltersAreBeans() {
        assertNotNull(maxConnsFilter, "MaxConns filter 必须是 Bean");
        assertNotNull(breakerFilter(), "Breaker filter 必须是 Bean");
        assertNotNull(cpuSheddingFilter(), "CpuShedding filter 必须是 Bean");
        assertNotNull(maxBytesFilter, "MaxBytes filter 必须是 Bean");
    }

    @Test
    @DisplayName("Go 链序：MaxConns 在 Breaker 之前（go-zero v1.10.2 rest/engine.go:146 → :149）")
    void maxConnsRunsBeforeBreaker() {
        int maxConns = orderOf("maxConnsFilter", MaxConnsFilter.class);
        int breaker = orderOf("routeIsolationFilter", null);
        assertTrue(maxConns < breaker,
                "Go 的链序要求 MaxConns 先于 Breaker"
                        + "（go-zero v1.10.2 rest/engine.go:146 chn.Append(MaxConnsHandler) → :149 BreakerHandler）；"
                        + "实际 MaxConns=" + maxConns + " Breaker=" + breaker);
    }

    @Test
    @DisplayName("Go 链序：Breaker 在 CpuShedding 之前（go-zero v1.10.2 rest/engine.go:149 → :152）")
    void breakerRunsBeforeCpuShedding() {
        int breaker = orderOf("routeIsolationFilter", null);
        int shedding = orderOf("cpuSheddingFilter", null);
        assertTrue(breaker < shedding,
                "Go 的链序要求 Breaker 先于 CpuShedding"
                        + "（go-zero v1.10.2 rest/engine.go:149 chn.Append(BreakerHandler) → :152 SheddingHandler）；"
                        + "实际 Breaker=" + breaker + " CpuShedding=" + shedding);
    }

    @Test
    @DisplayName("Go 链序：CpuShedding 在 MaxBytes 之前（go-zero v1.10.2 rest/engine.go:152 → :164）")
    void cpuSheddingRunsBeforeMaxBytes() {
        int shedding = orderOf("cpuSheddingFilter", null);
        int maxBytes = orderOf("maxBytesFilter", MaxBytesFilter.class);
        assertTrue(shedding < maxBytes,
                "Go 的链序要求 CpuShedding 先于 MaxBytes"
                        + "（go-zero v1.10.2 rest/engine.go:152 chn.Append(SheddingHandler) → :164 MaxBytesHandler）；"
                        + "实际 CpuShedding=" + shedding + " MaxBytes=" + maxBytes);
    }

    // ── 共享层的包级可见嵌套 filter：按 bean 名取 ────────────────────────────

    /** 共享层 {@code ProtectionAutoConfiguration.RouteIsolationFilter}（HTTP 熔断 filter）。 */
    private Object breakerFilter() {
        return ctx.getBean("routeIsolationFilter");
    }

    /** 共享层 {@code ProtectionAutoConfiguration.CpuSheddingFilter}（CPU 自适应降载 filter）。 */
    private Object cpuSheddingFilter() {
        return ctx.getBean("cpuSheddingFilter");
    }

    /**
     * 取一个 filter 的<b>声明序值</b>。
     *
     * <p>🔴 <b>为什么分两路</b>：C 侧共享层的四个保护 filter 里，
     * {@code MaxConnsFilter} / {@code MaxBytesFilter} 把 {@code @Order} 写在<b>类</b>上，
     * 而 {@code RouteIsolationFilter} / {@code CpuSheddingFilter}（包级可见嵌套类）
     * 把 {@code @Order} 写在<b>{@code @Bean} 方法</b>上
     * ⇒ ⇒ 必须两路都查，否则后两个会退化成 {@code LOWEST_PRECEDENCE} ⇒ 相对次序断言假红。
     *
     * @param beanName   filter 的 bean 名
     * @param filterType filter 的类型（可空 ⇒ 说明它是包级可见嵌套类 ⇒ 走 {@code @Bean} 方法那一路）
     * @return 序值
     */
    private int orderOf(String beanName, Class<?> filterType) {
        Object bean = ctx.getBean(beanName);
        if (filterType != null) {
            Order order = AnnotationUtils.findAnnotation(filterType, Order.class);
            if (order != null) {
                return order.value();
            }
        }
        // 嵌套类那一路：读共享层 auto-config 里对应 @Bean 方法上的 @Order
        for (java.lang.reflect.Method method : com.rag.common.autoconfigure.ProtectionAutoConfiguration.class
                .getDeclaredMethods()) {
            if (!method.getName().equals(beanName)) {
                continue;
            }
            Order order = AnnotationUtils.findAnnotation(method, Order.class);
            if (order != null) {
                return order.value();
            }
        }
        // 最后兜底：类上的 @Order（嵌套类也可能有）
        Order order = AnnotationUtils.findAnnotation(bean.getClass(), Order.class);
        return order == null ? Ordered.LOWEST_PRECEDENCE : order.value();
    }
}
