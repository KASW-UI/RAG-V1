package com.rag.message.wiring;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * <p><b>职责</b>：message 域进程启动后<b>显式注册到 Nacos</b> —— 注册的是
 * <b>gRPC 端口</b>，<b>不是</b> web 容器端口。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/bootstrap/NacosRegistration.java}
 * （1-197 行；类声明与 bean 名 {@code :78-79}；常量 {@code :84-87}；字段 {@code :89-98}；
 * 构造器 {@code :105-121}；{@code register} {@code :128-148}；{@code deregister} {@code :153-167}；
 * {@code resolveLocalIp} {@code :174-196}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.message.message.rpc.bootstrap} →
 *       {@code com.rag.message.wiring}，D7 包压平）＋ <b>类名沿用</b>（{@code NacosRegistration}）。
 *       <b>方法体逐字照抄</b>（注册参数、反注册参数、{@code resolveLocalIp} 的非 loopback 规则、
 *       {@code @PreDestroy} 时机、bean 显式命名）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>注册端口仍取 {@link GrpcServerProperties#getPort()}（= gRPC 业务口），
 *             <b>不是</b> web 观测口 ⇒ 与 B 侧 {@code zrpc.MustNewServer} 注册 ListenOn 的语义一致；</li>
 *         <li>group <b>从配置读</b>（B 侧 {@code DRIFT-G1-001} 修法 · 默认值零变化 = {@code DEFAULT_GROUP}）
 *             ⇒ 不再写死（见下方 group 字段的完整论证）；</li>
 *         <li>服务名仍为 {@code "message-rpc"}（B 侧逐字照抄，见下方 L1 论证）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>🔴 硬要求：注册的是 gRPC 端口、不是 web 容器端口</b>
 * <p>B 侧语义等价论证（原文照抄）：Go 侧的 devserver 观测口是<b>独立端口</b>
 * （{@code service/message/rpc/etc/message.yaml:19-27} ⇒ {@code Port: 16067}），
 * 而 go-zero 的 {@code zrpc.MustNewServer} 注册的是 <b>业务 gRPC 端口</b>
 * （{@code service/message/rpc/etc/message.yaml} 的 {@code ListenOn: 0.0.0.0:8082}）
 * ⇒ ⇒ <b>两侧都没有业务 HTTP 路由</b> ⇒ ⇒ ⇒ 注册到 Nacos 的<b>必须</b>是 gRPC 端口
 * ⇒ ⇒ ⇒ <b>手动注册路径的必要性与结论不变</b>。
 * <p>⚠️ <b>本仓的差异（部署面 · 已在 D1 登记）</b>：本仓合并成单进程后<b>确实带 web 容器</b>
 * （HTTP 8883 承载 13 个业务端点）⇒ ⇒ Spring Cloud 的
 * {@code NacosAutoServiceRegistration}（监听 {@code WebServerInitializedEvent}）
 * 会自动注册一个 <b>web 端口</b>的实例 ⇒ ⇒ 必须
 * <b>关掉自动注册</b>（{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 只留本类的<b>显式 gRPC 端口注册</b> ⇒ ⇒ 与 B 侧语义一致。
 *
 * <p><b>⚠️ 服务名 {@code "message-rpc"} 是 L1，勿改</b>：
 * B 侧的 <b>like 域</b>通过 {@code discovery:///message-rpc} 调用本域
 * （B 出处：{@code service/like/like-rpc/src/main/resources/application.yml:328-329}）
 * ⇒ ⇒ 该名字是<b>跨域寻址契约</b> ⇒ ⇒ D1 合并进程只改了「进程拓扑」，
 * <b>没有</b>改「注册名」⇒ ⇒ 逐字保留 {@code "message-rpc"}。
 * （⚠️ 本仓 {@code spring.application.name} 是 {@code message-service}
 * —— 那是应用标识，与「对外注册名」是<b>两件事</b>。）
 *
 * <p><b>group 从 {@code spring.cloud.nacos.discovery.group} 读</b>（默认 {@code DEFAULT_GROUP}
 * = B 侧写死的那个值 ⇒ <b>默认行为零变化</b>）—— 理由见下方 group 字段。
 *
 * <p><b>⚠️ Bean 名必须显式指定</b>（B 侧 2026-10-22 修，DRIFT-MSG-004）：
 * {@code spring-cloud-starter-alibaba-nacos-discovery} 的
 * {@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为
 * {@code nacosRegistration} 的 Bean</b>。若本类用裸 {@code @Component}，
 * 默认 Bean 名恰好也是 {@code nacosRegistration} ⇒ 两者撞名 ⇒
 * <b>Spring Boot 2.1+ 默认 {@code allow-bean-definition-overriding=false}</b>
 * ⇒ 上下文直接起不来。
 *
 * <p><b>✅ yml 侧已配齐（2026-11-02 · 本域 yml owner 补）</b>：
 * 本仓 {@code service/message/src/main/resources/application.yml} 的 Nacos 段现有
 * {@code server-addr: ${NACOS_ADDR:nacos}:${NACOS_PORT:8848}} ·
 * {@code namespace: ${NACOS_NAMESPACE:sea-rtw}} · {@code group: ${NACOS_GROUP:USER_GROUP}} ·
 * {@code register-enabled: false} 四项 ⇒ ⇒ 本类读得到 group，且自动注册已关掉。
 * <p>⚠️ <b>namespace / group 的取值与 B 侧不同</b>（B 侧 message-rpc = {@code public} /
 * {@code DEFAULT_GROUP}）⇒ 那是<b>部署面</b>的对齐（etcd 无 namespace 概念 ⇒ B 侧那两个值
 * 不承载行为）⇒ 登记为 message 域签字偏离，详见
 * {@code docs/DRIFT/message/verification-message-service-2026-11-02.md}。
 */
@Component("messageRpcNacosRegistration") // ← 显式命名，避开 NacosServiceRegistryAutoConfiguration 的同名 Bean
public class NacosRegistration {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    /** 对应 Go yaml：Key: message.rpc → 服务发现 / OTEL 都按这个名注册。 */
    private static final String SERVICE_NAME = "message-rpc";

    /** Spring Cloud Alibaba Nacos 默认 group（B 侧逐字照抄）—— **本类不再直接用它注册**（见下方 group 字段）。 */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 注册用的 group —— **从配置读**，不写死。
     *
     * <p><b>🆕 B 侧 {@code DRIFT-G1-001}（B 侧台账 · {@code docs/DRIFT/follow/vs-common.md:40}）</b>：
     * 「follow-rpc 的 group 写死」被 B 侧自己立为 🔴 缺陷并已修，修法是
     * <b>group 从 {@code spring.cloud.nacos.discovery.group} 读、默认值零变化</b>。
     * <p><b>为什么这条也适用于本类</b>：message-rpc 的 {@code NacosRegistration} 与
     * follow-rpc 是<b>同一个形状</b>（group 写死成 {@code DEFAULT_GROUP}）⇒
     * {@code NACOS_GROUP} 环境变量对本域<b>完全无效</b> ⇒ 而本仓 yml 里写的是
     * {@code group: ${NACOS_GROUP:USER_GROUP}}（<b>读配置</b>）⇒ ⇒ 部署一旦设
     * {@code NACOS_GROUP=别的} ⇒ <b>注册到 A 组、调用方去 B 组找</b> ⇒ 恒「No servers found」。
     * <p><b>默认值零变化</b>：不设环境变量时解析结果仍是 {@code DEFAULT_GROUP}
     * （= B 侧写死的那个值）⇒ <b>默认行为与 B 侧逐字相同</b>。
     * <p><b>与本仓已落地域一致</b>：{@code service/users/{user,admin}} 的
     * {@code NacosRegistration} 用的是同款写法
     * （{@code @Value("${spring.cloud.nacos.discovery.group:USER_GROUP}")}）。
     */
    private final String groupName;

    private final NacosServiceManager nacosServiceManager;

    private final GrpcServerProperties grpcServerProperties;

    /** 对应 Go etcd 注册的 { ip, port }。 */
    private final String localIp;

    /**
     * Nacos server 端口 —— <b>B 侧同样只赋值、不读取</b>（逐字保留，不做顺手清理）。
     *
     * <p>⚠️ 保留它的理由：本类是<b>逐字照抄</b>，删字段属于「顺手清理」⇒ 会让 review 时
     * 对照 B 侧源码多出一处无谓的 diff。
     */
    private final int nacosServerPort;

    /** 当前已注册实例句柄（防止重复注册 / 用于 deregister）。 */
    private volatile Instance registeredInstance;

    /**
     * 构造器 —— B 侧逐字照抄。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:105-121}。
     *
     * @param nacosServiceManager Spring Cloud Alibaba Nacos 客户端 Bean
     * @param grpcServerProperties devh grpc server 配置（含 port=8084）
     * @param nacosServerAddr     Nacos server 地址（默认 127.0.0.1:8848）
     * @param groupName           注册用的 group（读配置 · B 侧 DRIFT-G1-001 修法 · 默认 DEFAULT_GROUP）
     */
    public NacosRegistration(NacosServiceManager nacosServiceManager,
                             GrpcServerProperties grpcServerProperties,
                             @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}") String nacosServerAddr,
                             @Value("${spring.cloud.nacos.discovery.group:" + DEFAULT_GROUP + "}") String groupName) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.groupName = groupName;
        // 解析 nacos server 端口：可能是 "host" 或 "host:port"
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
     * Spring 启动完成后注册到 Nacos。
     *
     * <p>对应 Go 端 {@code s.Start()} 后立刻被 zrpc 调用 EtcdRegister。
     *
     * <p><b>⚠️ 注册端口 = gRPC 端口</b>（{@link GrpcServerProperties#getPort()}），
     * <b>不是</b> web 容器端口 —— 见类注释的硬要求。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:128-148}。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            Instance instance = new Instance();
            instance.setIp(localIp);
            instance.setPort(grpcServerProperties.getPort());
            instance.setServiceName(SERVICE_NAME);
            // Nacos 2.x Instance 不含 groupName 字段：group 走 registerInstance(serviceName, groupName, instance) 第 2 个参数。
            instance.setHealthy(true);
            instance.setWeight(1.0);
            instance.setEphemeral(true);

            nacosServiceManager.getNamingService()
                    .registerInstance(SERVICE_NAME, groupName, instance);
            this.registeredInstance = instance;
            log.info("NacosRegistration registered: service={} group={} ip={} port={}",
                    SERVICE_NAME, groupName, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            log.error("NacosRegistration.register failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * Spring 容器关闭时反注册 —— 防止进程重启残留 stale 节点。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:153-167}。
     */
    @jakarta.annotation.PreDestroy
    public void deregister() {
        if (registeredInstance == null) {
            return;
        }
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, groupName,
                            registeredInstance.getIp(), registeredInstance.getPort());
            log.info("NacosRegistration deregistered: service={} group={} ip={} port={}",
                    SERVICE_NAME, groupName, registeredInstance.getIp(), registeredInstance.getPort());
        } catch (NacosException e) {
            log.warn("NacosRegistration.deregister failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * 对应 Go 端 resolveLocalIp：优先取第一个非 loopback 网卡的 IPv4。
     *
     * <p>Go 用 {@code net.InterfaceAddrs()}，Java 用 {@link NetworkInterface} 遍历 enumerate。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:174-196}。
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
        } catch (java.net.SocketException ignore) {
            // fallthrough 到 InetAddress.getLocalHost
        }
        return InetAddress.getLocalHost().getHostAddress();
    }
}
