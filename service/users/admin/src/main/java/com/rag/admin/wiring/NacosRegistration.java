package com.rag.admin.wiring;

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
 * <p><b>职责</b>：显式注册 admin 域的 gRPC 实例到 Nacos（用 <b>gRPC 端口</b>，不是 web 端口）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.rpc.bootstrap} →
 *       {@code com.rag.admin.wiring}，D7 包压平）＋ <b>两处字符串</b>：
 *       <ul>
 *         <li>注册的服务名 {@code "admin-rpc"} → {@code "admin-service"}
 *             （<b>D1 合并后的进程身份</b>，与 {@code spring.application.name} 一致；
 *             ⚠️ 本仓 D2 之后<b>没有</b>任何消费方按这个名字寻址 ⇒ 纯运维面）；</li>
 *         <li>日志前缀 {@code admin-rpc} → {@code admin-service}（纯日志）。</li>
 *       </ul>
 *       <b>方法体逐字照抄</b>（含 group 读配置、{@code resolveLocalIp} 的非 loopback 规则、
 *       {@code @PreDestroy} 的反注册）。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>注册端口仍取 {@link GrpcServerProperties#getPort()}（= gRPC 业务口），
 *             <b>不是</b> web 观测口 ⇒ 与 B 侧 {@code zrpc.MustNewServer} 注册 ListenOn 的语义一致；</li>
 *         <li>group 仍从 {@code spring.cloud.nacos.discovery.group} 读（不写死）⇒ 与 B 侧 DRIFT-G1-001 的修法一致；</li>
 *         <li>「自动注册必须关掉」这条前提<b>依然成立</b>（见下）⇒ ⇒ <b>需要</b>
 *             {@code spring.cloud.nacos.discovery.register-enabled: false}（B 侧 admin-rpc yml:119 有这一行，
 *             <b>本仓的 application.yml 已补上</b> —— 2026-11-02 实测补齐，见该文件 Nacos 段注释）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/NacosRegistration.java}。</li>
 * </ol>
 *
 * <p><b>DRIFT-NEW-011 根因</b>：admin-rpc 原先没有 web 容器 ⇒ Spring Cloud Commons 的
 * {@code NacosAutoServiceRegistration} 默认监听 {@code WebServerInitializedEvent} 触发自动注册 ——
 * 那时 admin-rpc 不发这个 event ⇒ 启动时不调 register。
 *
 * <p><b>🔴 2026-10-30 补载体后前提已变</b>：现在会发该 event ⇒ ⇒ 自动注册被触发，取的是
 * <b>web server 端口</b>（本仓 = HTTP 口 8889，非 gRPC 口 8082）
 * ⇒ ⇒ Nacos 出现错端口实例 ⇒ ⇒ gRPC 链路断（业务码塌 1015）⇒ ⇒ yml 必须写
 * {@code register-enabled: false}（详见 yml Nacos 段注释）。
 *
 * <p><b>✅ 2026-11-02 已补齐</b>：本仓 {@code application.yml} 的 Nacos 段已加上
 * {@code spring.cloud.nacos.discovery.register-enabled: false}（B 侧 admin-rpc yml:119 逐字照抄）
 * ⇒ ⇒ 自动注册链路的<b>运行期判定</b>（SCA 2025.0.0.0 {@code NacosAutoServiceRegistration:71/83/97}）
 * ⇒ ⇒ 只剩本类的<b>显式 gRPC 端口注册</b>生效 ⇒ 与 B 侧 admin-rpc 语义一致。
 * ⇒ ⇒ 真触发验证见 {@code AdminServiceApplicationTests#nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered}。
 *
 * <p>1:1 语义对齐 B 侧：B 侧 admin-rpc 启动时主动调
 * {@code NamingService.RegisterInstance(serviceName, groupName, instance)}。
 * 本类用同样路径：{@link NacosServiceManager#getNamingService()} 拿到
 * {@link NamingService}，显式 {@code registerInstance}。
 */
/**
 * bean 名必须显式错开：NacosServiceRegistryAutoConfiguration 里有个
 * {@code @Bean nacosRegistration()}，与本类的默认 bean 名（类名首字母小写）撞名 →
 * BeanDefinitionOverrideException → 应用启动失败。
 */
@Component("adminRpcNacosRegistration")
public class NacosRegistration {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;

    /**
     * 🆕 DRIFT-G1-001：<b>注册用的 group 必须读配置</b>。
     *
     * <p><b>B 出处</b>：{@code service/user/admin/rpc/etc/admin.yaml} 的 {@code Etcd.Key}
     * —— B 侧的服务发现键是<b>配置项</b>，部署时改 yaml 就换命名空间。
     *
     * <p><b>修后</b>：group 从 {@code spring.cloud.nacos.discovery.group} 读
     * （= 同一份 yml 里已有的那一行，<b>不需要新增配置项</b>）。
     * ⇒ <b>默认值零变化</b>：yml 里写的就是 {@code ${NACOS_GROUP:USER_GROUP}}
     * ⇒ 不设环境变量时解析结果与修前<b>逐字相同</b>。
     *
     * <p>⚠️ 同 user-rpc：<b>配置项早就存在（{@code application.yml}）只是代码不消费</b>
     * ⇒ 「配置项存在」≠「配置项被消费」。
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

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        // ⚠️ D1 合并后的进程身份：与 spring.application.name 一致（本仓已无独立的 admin-rpc 进程）
        String serviceName = "admin-service";
        String groupName = this.groupName;
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
            namingService.registerInstance(serviceName, groupName, instance);

            this.registeredInstance = instance;
            this.registeredServiceName = serviceName;
            this.registeredGroupName = groupName;

            log.info("nacos registry, {} {} {}:{} register finished",
                    groupName, serviceName, instance.getIp(), port);
        } catch (NacosException | UnknownHostException e) {
            log.error("admin-service nacos register FAILED service={} group={} port={}",
                    serviceName, groupName, port, e);
        }
    }

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
            log.warn("admin-service nacos deregister FAILED", e);
        }
    }

    /**
     * DRIFT-NEW-012：{@code InetAddress.getLocalHost()} 在 macOS 上经常返回 127.0.0.1（loopback），
     * docker 容器跨 host 网络访问不到 host loopback。要走非 loopback 的 IPv4（en0/en1 NIC）。
     */
    private static String resolveLocalIp() throws UnknownHostException {
        InetAddress candidate = null;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || ni.isPointToPoint() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) {
                        if (candidate == null) candidate = a;
                    }
                }
            }
        } catch (SocketException ignore) {
            // 退回 InetAddress.getLocalHost
        }
        return candidate != null ? candidate.getHostAddress() : InetAddress.getLocalHost().getHostAddress();
    }
}
