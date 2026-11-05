package com.rag.hot.wiring;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import jakarta.annotation.PreDestroy;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * <p><b>进程启动后<b>显式注册到 Nacos</b></b> —— 注册的是 <b>gRPC 端口</b>，<b>不是</b> web 容器端口。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/bootstrap/NacosRegistration.java}
 * （1-222 行；类声明与 bean 名 {@code :49-50}；常量 {@code :54-58}；字段 {@code :60-79}；
 * 构造器 {@code :81-99}；{@code register} {@code :102-128}；{@code markHealth} {@code :165-174}；
 * {@code deregister} {@code :182-196}；{@code resolveLocalIp} {@code :199-221}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li>包名 {@code com.rag.hot.hot.rpc.bootstrap} → {@code com.rag.hot.wiring}（D7 包压平）；</li>
 *         <li><b>group 从配置读</b>（B 侧写死 {@code DEFAULT_GROUP}）⇒ 默认值零变化
 *             （本仓 message / users 域同款修法 · B 侧 {@code DRIFT-G1-001}）；</li>
 *         <li><b>日志走 slf4j</b>（A8：本仓统一 {@code GoStyleLogger}，本类是技术日志 ⇒ slf4j）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>注册端口仍取 {@link GrpcServerProperties#getPort()}（= gRPC 业务口）；</li>
 *         <li>服务名仍为 {@code "hot-rpc"}（<b>L1</b>：跨域寻址契约，勿改）；</li>
 *         <li><b>注册失败 ⇒ health 报 NOT_SERVING</b>（<b>不崩进程</b>）逐条保留；</li>
 *         <li><b>只登记整体健康状态</b>（{@code SERVICE_NAME_ALL_SERVICES} = {@code ""}）逐条保留。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>🔴 硬要求：注册的是 gRPC 端口、不是 web 容器端口</b>
 * <p>B 侧语义等价论证：B 侧的 go-zero {@code zrpc.MustNewServer} 注册的是 <b>业务 gRPC 端口</b>
 * （B 侧 {@code etc/hot.yaml} 的 {@code ListenOn: 0.0.0.0:8083}）⇒ ⇒ 注册到 Nacos 的<b>必须</b>是 gRPC 端口。
 * <p>⚠️ <b>本仓的差异（部署面 · D1 合并的必然结果）</b>：本仓合并成单进程后<b>确实带 web 容器</b>
 * （HTTP 8895 承载唯一的 {@code GET /hot/v1/articles}）⇒ ⇒ Spring Cloud 的
 * {@code NacosAutoServiceRegistration}（监听 {@code WebServerInitializedEvent}）
 * 会自动注册一个 <b>web 端口</b>的实例 ⇒ ⇒ 必须
 * <b>关掉自动注册</b>（{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 只留本类的<b>显式 gRPC 端口注册</b> ⇒ ⇒ 与 B 侧语义一致。
 *
 * <p><b>⚠️ Bean 名必须显式指定</b>（B 侧 2026-10-22 修，{@code DRIFT-MSG-004}）：
 * {@code spring-cloud-starter-alibaba-nacos-discovery} 的
 * {@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为 {@code nacosRegistration} 的 Bean</b>。
 * 若本类用裸 {@code @Component}，默认 Bean 名恰好也是 {@code nacosRegistration} ⇒ 两者撞名 ⇒
 * <b>Spring Boot 2.1+ 默认 {@code allow-bean-definition-overriding=false}</b>
 * ⇒ 上下文直接起不来。
 *
 * <h2>注册失败 ⇒ 健康检查报 NOT_SERVING</h2>
 * <p>B 侧注册 etcd 失败会 <b>{@code panic} 退出进程</b> ⇒ 探针连不上。
 * C 侧刻意<b>不崩进程</b>，但必须让<b>探针能看到失败</b>，否则就成了「注册不上却一切正常」的假活。
 * ⇒ 走 gRPC 标准 health service（与 B 侧同构）。
 *
 * <p><b>语义等价论证（health service）</b>：两侧用的都是 <b>grpc 标准 health service</b>
 * （B 侧 = {@code google.golang.org/grpc/health.Server}；
 * C 侧 = {@code io.grpc.protobuf.services.HealthStatusManager}，由 devh starter 的
 * {@code GrpcHealthServiceAutoConfiguration} 提供），
 * 且都<b>只登记整体状态</b>（service 名 = {@code ""}）：
 * <ul>
 *   <li>B 侧 {@code health.NewServer()} 只在构造时放入 {@code statusMap[""] = SERVING}，
 *       {@code Resume()} 也只改这一条 ⇒ 查任何<b>具体</b>服务名一律 {@code NOT_FOUND}；</li>
 *   <li>C 侧 {@code HealthServiceImpl} 的构造同样是 {@code statusMap[""] = SERVING}，
 *       且 {@code check} 是<b>精确匹配</b>（查不到 ⇒ {@code NOT_FOUND}，<b>不回落</b>到整体状态）。</li>
 * </ul>
 * ⇒ 本方法<b>只</b>设置 {@code SERVICE_NAME_ALL_SERVICES}（{@code ""}），
 * <b>刻意不</b>额外登记 {@code "hot-rpc"} 这个具体名 —— 否则会造出 B 侧没有的
 * 「查具体服务名能拿到状态」这一行为。
 *
 * <p><b>已知差异（有意偏离，已签字）</b>：B 侧的失败表现是<b>进程退出</b>（连接都断），
 * C 侧是<b>进程存活但健康检查报 NOT_SERVING</b>（连接仍在、gRPC 方法仍可调）。
 * ⇒ <b>可观察结果一致</b>（探针都失败），<b>机制不同</b>（连接失败 vs 状态失败）。
 */
@Component("hotRpcNacosRegistration") // ← 显式命名，避开 NacosServiceRegistryAutoConfiguration 的同名 Bean
public class NacosRegistration {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    /** 对应 B 侧 yaml {@code Etcd.Key: hot.rpc}。 */
    private static final String SERVICE_NAME = "hot-rpc";

    /** Spring Cloud Alibaba Nacos 默认 group（B 侧写死的值 = 本类的默认值）。 */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;

    /**
     * 注册用的 group —— <b>从配置读</b>，不写死。
     *
     * <p>默认值 = {@code DEFAULT_GROUP}（= B 侧写死的那个值）⇒ <b>默认行为零变化</b>。
     */
    private final String groupName;

    private final String localIp;

    /**
     * Nacos server 端口 —— <b>B 侧同样只赋值、不读取</b>（逐字保留，不做顺手清理）。
     */
    private final int nacosServerPort;

    /**
     * gRPC 标准健康检查的状态管理器（由 devh starter 的
     * {@code GrpcHealthServiceAutoConfiguration} 提供）。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入：若那个 bean 不存在
     * （条件注解未命中），直接注入会让<b>上下文根本起不来</b>（把一个可选开关变成硬依赖）；
     * 用 Provider 则降级为「注册失败无法被探针观测到」，并打 ERROR 留痕。
     */
    private final ObjectProvider<HealthStatusManager> healthStatusManagerProvider;

    /** 本进程的服务注册是否成功（测试观测用）。 */
    private volatile boolean registrationHealthy;

    private volatile Instance registeredInstance;

    /**
     * 构造器。
     *
     * @param nacosServiceManager       Nacos 客户端
     * @param grpcServerProperties      devh grpc server 配置（含 port=8083）
     * @param healthStatusManagerProvider gRPC 健康状态管理器（可缺省）
     * @param nacosServerAddr           Nacos server 地址（默认 127.0.0.1:8848）
     * @param groupName                 注册用的 group（读配置 · 默认 DEFAULT_GROUP）
     */
    public NacosRegistration(NacosServiceManager nacosServiceManager,
                             GrpcServerProperties grpcServerProperties,
                             ObjectProvider<HealthStatusManager> healthStatusManagerProvider,
                             @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
                             String nacosServerAddr,
                             @Value("${spring.cloud.nacos.discovery.group:" + DEFAULT_GROUP + "}")
                             String groupName) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.healthStatusManagerProvider = healthStatusManagerProvider;
        this.groupName = groupName;
        String[] parts = nacosServerAddr.split(":");
        this.nacosServerPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 8848;
        String ip;
        try {
            ip = resolveLocalIp();
        } catch (UnknownHostException e) {
            ip = InetAddress.getLoopbackAddress().getHostAddress();
            log.warn("resolveLocalIp failed, fallback to loopback: {}", ip);
        }
        this.localIp = ip;
    }

    /**
     * <p><b>启动完成后注册到 Nacos</b>（对应 B 侧 {@code zrpc} 在 {@code Start()} 后立刻注册）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            Instance instance = new Instance();
            instance.setIp(localIp);
            instance.setPort(grpcServerProperties.getPort());
            instance.setServiceName(SERVICE_NAME);
            instance.setHealthy(true);
            instance.setWeight(1.0);
            instance.setEphemeral(true);

            nacosServiceManager.getNamingService()
                    .registerInstance(SERVICE_NAME, groupName, instance);
            this.registeredInstance = instance;
            markHealth(ServingStatus.SERVING);
            log.info("NacosRegistration registered: service={} group={} ip={} port={} nacosPort={}",
                    SERVICE_NAME, groupName, localIp, grpcServerProperties.getPort(), nacosServerPort);
        } catch (NacosException | RuntimeException e) {
            // ⚠️ catch RuntimeException 是**有出处的**，不是图省事：
            //   B 侧 registerEtcd() 返回的是 error —— **任何**注册错误都导致 panic 退出，
            //   不只是「etcd 连不上」这一种。
            //   ⇒ C 侧对应地必须是「任何注册失败 ⇒ NOT_SERVING」，否则漏掉一类失败就变成假活。
            //   只 catch NacosException 会漏掉 NPE 这类运行时失败。
            log.error("NacosRegistration.register failed for {}: {}", SERVICE_NAME, e.getMessage(), e);
            markHealth(ServingStatus.NOT_SERVING);
        }
    }

    /**
     * <p><b>把「服务注册是否成功」写进 gRPC 标准健康检查</b>。
     *
     * @param status {@code SERVING} / {@code NOT_SERVING}
     */
    private void markHealth(ServingStatus status) {
        this.registrationHealthy = status == ServingStatus.SERVING;
        HealthStatusManager manager = healthStatusManagerProvider.getIfAvailable();
        if (manager == null) {
            log.error("HealthStatusManager bean 缺失 ⇒ 注册状态无法被 gRPC 探针观测到"
                    + "（grpc.server.health-service-enabled 被关掉了？）");
            return;
        }
        manager.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, status);
    }

    /**
     * 本进程的服务注册是否成功（测试观测用）。
     *
     * @return 是否注册成功
     */
    boolean registrationHealthy() {
        return registrationHealthy;
    }

    /**
     * <p><b>进程退出时反注册</b>，避免残留 stale 节点。
     */
    @PreDestroy
    public void deregister() {
        if (registeredInstance == null) {
            return;
        }
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, groupName,
                            registeredInstance.getIp(), registeredInstance.getPort());
            log.info("NacosRegistration deregistered: service={} ip={} port={}",
                    SERVICE_NAME, registeredInstance.getIp(), registeredInstance.getPort());
        } catch (NacosException e) {
            log.warn("NacosRegistration.deregister failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * <p><b>取第一个非 loopback 的 IPv4</b>（对应 B 侧的 {@code net.InterfaceAddrs()}）。
     *
     * @return 本机可对外访问的 IPv4 地址
     * @throws UnknownHostException 本机地址无法解析
     */
    private static String resolveLocalIp() throws UnknownHostException {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface iface = ifaces.nextElement();
                    if (iface.isLoopback() || !iface.isUp() || iface.isPointToPoint()) {
                        continue;
                    }
                    Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ignore) {
            // 落到 InetAddress.getLocalHost()
        }
        return InetAddress.getLocalHost().getHostAddress();
    }
}
