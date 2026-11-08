package com.rag.user.wiring;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PreDestroy;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * <p><b>显式注册 user 域的 gRPC 实例到 Nacos</b>（服务名 {@code user-rpc}）。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/bootstrap/NacosRegistration.java
 * </pre>
 *
 * <h2>⚠️ 为什么注册名是 {@code user-rpc} 而不是合并后的 app 名（AGENTS.md §五.5：注册名是 L1）</h2>
 * <p>B 侧<b>显式</b>注册的服务名就是 {@code "user-rpc"}（硬编码），且它是<b>活的</b>：
 * <ul>
 *   <li><b>运维探针</b>：B 侧 {@code docker-compose.yaml:80} 的 nacos 健康检查查的是
 *       {@code serviceName=user-rpc} ⇒ 该名字必须存在，否则容器起不来；</li>
 *   <li><b>跨域 gRPC 寻址</b>：B 侧 {@code service/users/admin/admin-api/.../application.yml:128}
 *       写着 {@code grpc.client.user-rpc.address: discovery:///user-rpc}
 *       ⇒ 「{@code user-rpc} 这个 Nacos 服务名」是跨域寻址面。<br/>
 *       ⚠️ 但 B 侧 {@code AdminRpcClient.java:85-86} 自述：该配置<b>没有对应的注入点</b>
 *       （admin-api 里无 {@code @GrpcClient("user-rpc")}）⇒ <b>目前是死配置</b>
 *       ⇒ ⇒ 保留该名字是<b>零成本</b>（不改任何活行为），而改掉它会让运维探针与将来的跨域客户端失配。</li>
 * </ul>
 * ⇒ ⇒ <b>注册名逐字沿用 B 侧的 {@code "user-rpc"}</b>，不因 D1 合并而改成 {@code user-service}。
 *
 * <h2>⚠️ 为什么只注册 <b>1 个</b>（而不是合并进程的两个端口各一个）</h2>
 * <p>映射表锁决策（{@code docs/Mapping_Table/users/_shared.md} §1.3「换进程拓扑 7 处同时改」第 7 条）：
 * <b>「Nacos 注册项：2 个/子域 → 1 个/子域」</b>
 * （article 域的同款表述：「Nacos 注册项从 2 个（{@code article-api} / {@code article-rpc}）减为 1 个」）。
 * <p>⇒ B 侧 user 子域实际有 <b>2 个</b> Nacos 条目：
 * <ol>
 *   <li>{@code user-api}（HTTP 端口）—— 由 Spring Cloud 的自动注册产生（api 进程有 web 容器）；</li>
 *   <li>{@code user-rpc}（gRPC 端口）—— 由<b>本类</b>显式注册。</li>
 * </ol>
 * ⇒ C 侧合并成单进程后，<b>本类只保留 gRPC 端这一个显式条目</b>；
 * <b>并关闭 Spring Cloud 的自动注册</b>（{@code spring.cloud.nacos.discovery.register-enabled: false}
 * ⇒ 避免再冒出一个 {@code user-service}@HTTP 端口 条目）⇒ 全进程<b>恰好 1 个</b> Nacos 注册项。
 * <p>⚠️ <b>关掉自动注册不损失任何活行为</b>：B 侧 {@code user-api} 这个条目<b>在全仓没有任何消费者</b>
 * —— 全仓 grep 无 {@code discovery://user-api}；HTTP 客户端是<b>直连端口</b>（前端不走服务发现）。
 * ⇒ 登记为签字偏离（见 {@code docs/Intentional_Deviations/users/}）。
 *
 * <h2>语义等价论证（逐条）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>{@code @Component("userRpcNacosRegistration")}（<b>bean 名必须显式</b>）</td>
 *       <td>逐字一致。⚠️ 原因（B 侧 DRIFT-NEW-013）：{@code NacosServiceRegistryAutoConfiguration} 里有个
 *           {@code @Bean nacosRegistration()} ⇒ 本类若用类名默认推导 bean 名（{@code nacosRegistration}）
 *           ⇒ <b>撞名</b> ⇒ {@code BeanDefinitionOverrideException} ⇒ <b>整个上下文启动失败</b>。</td></tr>
 *   <tr><td>构造注入 {@code NacosServiceManager} + {@code GrpcServerProperties}
 *           + {@code @Value("${spring.cloud.nacos.discovery.group:USER_GROUP}")}</td>
 *       <td>逐字一致</td></tr>
 *   <tr><td>{@code @EventListener(ApplicationReadyEvent.class) register()}</td><td>逐字一致</td></tr>
 *   <tr><td>{@code serviceName = "user-rpc"}</td><td>逐字一致（见上节 L1 论证）</td></tr>
 *   <tr><td>{@code port = grpcServerProperties.getPort()}</td>
 *       <td>逐字一致（<b>同一属性来源</b>）⇒ C 侧取合并进程内的 gRPC 端口
 *           （{@code grpc.server.port} = {@code ${USER_GRPC_PORT:8081}}）。</td></tr>
 *   <tr><td>instance：{@code ip / port / healthy=true / weight=1.0 / ephemeral=true / serviceName / clusterName="DEFAULT"}</td>
 *       <td>逐字一致</td></tr>
 *   <tr><td>{@code namingService.registerInstance(serviceName, groupName, instance)}</td><td>逐字一致</td></tr>
 *   <tr><td>{@code @PreDestroy deregister()}</td><td>逐字一致</td></tr>
 *   <tr><td>{@code resolveLocalIp()}：取非 loopback 的 IPv4</td><td>逐字一致</td></tr>
 * </table>
 *
 * <h2>⚠️ group 必须读配置（B 侧 DRIFT-G1-001）</h2>
 * <p>group 若写死成 {@code "USER_GROUP"}，则 {@code NACOS_GROUP} 环境变量对 user 域<b>完全无效</b>
 * ⇒ 而本仓 yml 里写的是 {@code group: ${NACOS_GROUP:USER_GROUP}}（<b>读配置</b>）
 * ⇒ ⇒ 部署一旦设 {@code NACOS_GROUP=别的} ⇒ <b>注册到 A 组、调用方去 B 组找</b> ⇒ 恒「No servers found」。
 * ⇒ ⇒ group <b>从配置读</b>，<b>默认值零变化</b>（不设环境变量时解析结果与 B 侧逐字相同）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>{@code @EventListener(ApplicationReadyEvent)} ⇒ 容器就绪后才注册（此时端口已真正绑定）；</li>
 *   <li>{@code @PreDestroy} ⇒ 优雅停机时反注册，避免 Nacos 里留僵尸实例。</li>
 * </ul>
 */
@Component("userRpcNacosRegistration")
public class NacosRegistration {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    /**
     * 注册到 Nacos 的服务名 —— <b>L1 契约</b>，逐字沿用 B 侧（见类注释的 L1 论证）。
     */
    private static final String SERVICE_NAME = "user-rpc";

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;

    /**
     * 🆕 B 侧 DRIFT-G1-001（2026-10-27 · G1 lane · 真启动 A/B 实测）：<b>注册用的 group 必须读配置</b>。
     *
     * <p><b>B 出处</b>：{@code service/user/user/rpc/etc/user.yaml:47}
     * {@code Etcd: { Key: user.rpc }} —— Go 侧的服务发现<b>键</b>是<b>配置项</b>。
     *
     * <p><b>修前</b>：group <b>写死</b>成 {@code "USER_GROUP"} ⇒ {@code NACOS_GROUP} 环境变量对 user 域
     * <b>完全无效</b> ⇒ 而 yml 写的是 {@code group: ${NACOS_GROUP:USER_GROUP}}（<b>读配置</b>）
     * ⇒ ⇒ 部署一旦设 {@code NACOS_GROUP=别的} ⇒ <b>rpc 注册到 A 组、api 去 B 组找</b>
     * ⇒ ⇒ 恒「No servers found ⇒ 所有业务码塌成 1015」。
     *
     * <p><b>修后</b>：group 从 {@code spring.cloud.nacos.discovery.group} 读
     * ⇒ <b>默认值零变化</b>（B 侧当时的 yml 里写的就是 {@code ${NACOS_GROUP:USER_GROUP}}）
 *     ⇒ 🔴 **2026-11-09（`global` 收口域 · 全仓统一决策 · 用户拍板）**：本域 yml 的 group 默认值
 *     已从 {@code ${NACOS_GROUP:USER_GROUP}} 改为 {@code ${NACOS_GROUP:DEFAULT_GROUP}}
 *     ⇒ ⇒ **本行的「yml 里写的就是 …」已不成立**（yml 现在写的是 {@code DEFAULT_GROUP}）⇒
 *     ⇒ **代码里的兜底常量 {@code USER_GROUP} 逐字保留**（= **B 侧
 *     {@code NacosRegistration.java:79} 的逐字值** ⇒ §3.1「与 B 侧逐字对照」）
 *     ⇒ ⇒ 但它**已成死分支**（yml 永远提供该属性 ⇒ `@Value` 的默认值取不到）⇒
 *     ⇒ **实际生效的 group = yml 的 {@code DEFAULT_GROUP}** ⇒
 *     ⇒ **该偏离已登记**（签字偏离 · **部署面 · 非行为面**）⇒ 见
 *     {@code docs/Intentional_Deviations/users/_signed-deviations.md}。
     * ⇒ 不设环境变量时解析结果与修前<b>逐字相同</b>（仍是 {@code USER_GROUP}）。
     */
    private final String groupName;

    private Instance registeredInstance;
    private String registeredServiceName;
    private String registeredGroupName;

    public NacosRegistration(NacosServiceManager nacosServiceManager,
                             GrpcServerProperties grpcServerProperties,
                             @Value("${spring.cloud.nacos.discovery.group:USER_GROUP}")
                             String groupName) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.groupName = groupName;
    }

    /**
     * 容器就绪 ⇒ 显式注册本实例到 Nacos。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        String serviceName = SERVICE_NAME;
        String group = this.groupName;
        int port = grpcServerProperties.getPort();

        try {
            Instance instance = new Instance();
            instance.setIp(resolveLocalIp());
            instance.setPort(port);
            instance.setHealthy(true);
            instance.setWeight(1.0);
            instance.setEphemeral(true);
            instance.setServiceName(serviceName);
            instance.setClusterName("DEFAULT");

            NamingService namingService = nacosServiceManager.getNamingService();
            namingService.registerInstance(serviceName, group, instance);

            this.registeredInstance = instance;
            this.registeredServiceName = serviceName;
            this.registeredGroupName = group;

            log.info("nacos registry, {} {} {}:{} register finished",
                    group, serviceName, instance.getIp(), port);
        } catch (NacosException | UnknownHostException e) {
            log.error("user-rpc nacos register FAILED service={} group={} port={}",
                    serviceName, group, port, e);
        }
    }

    /**
     * 优雅停机 ⇒ 反注册，避免 Nacos 里留僵尸实例。
     */
    @PreDestroy
    public void deregister() {
        if (registeredInstance == null) {
            return;
        }
        try {
            NamingService namingService = nacosServiceManager.getNamingService();
            namingService.deregisterInstance(
                    registeredServiceName, registeredGroupName, registeredInstance);
            log.info("nacos registry, {} {} deregister finished",
                    registeredGroupName, registeredServiceName);
        } catch (NacosException e) {
            log.warn("user-rpc nacos deregister FAILED", e);
        }
    }

    /**
     * 解析本机非 loopback 的 IPv4 地址。
     *
     * <p>B 侧 DRIFT-NEW-012：{@code InetAddress.getLocalHost()} 在 macOS 上经常返回 127.0.0.1（loopback），
     * docker 容器跨 host 网络访问不到 host loopback。要走非 loopback 的 IPv4（en0/en1 NIC）。
     */
    private static String resolveLocalIp() throws UnknownHostException {
        InetAddress candidate = null;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || ni.isPointToPoint() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) {
                        if (candidate == null) {
                            candidate = a;
                        }
                    }
                }
            }
        } catch (SocketException ignore) {
            // 退回 InetAddress.getLocalHost
        }
        return candidate != null ? candidate.getHostAddress() : InetAddress.getLocalHost().getHostAddress();
    }
}
