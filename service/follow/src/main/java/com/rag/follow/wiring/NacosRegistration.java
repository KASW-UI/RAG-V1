package com.rag.follow.wiring;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PreDestroy;
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
 * <p><b>follow 域 gRPC 实例注册到 Nacos</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/bootstrap/NacosRegistration.java}
 * （B 侧 193 行）。
 *
 * <h2>⚠️ L4 idiomatization：包名（D7 包压平）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th><th>依据</th></tr>
 *   <tr><td>{@code com.rag.follow.follow.rpc.bootstrap.NacosRegistration}</td>
 *       <td>{@code com.rag.follow.wiring.NacosRegistration}</td>
 *       <td>映射表 {@code docs/Mapping_Table/follow/follow.md} 第 141 行</td></tr>
 * </table>
 *
 * <h2>注册名的 L1 论证</h2>
 * <p>B 侧的注册名就是 {@code "follow-rpc"}（B 侧硬编码），它是<b>活的</b>：
 * <ul>
 *   <li><b>跨域寻址面</b>：{@code follow-rpc} 是 gRPC 服务发现名，
 *       与客户端的 {@code grpc.client.follow-rpc.address: discovery://follow-rpc} 对应；</li>
 *   <li>B 侧的注册键（{@code follow.rpc}）经 D1 合并进程后统一为 {@code follow-rpc}
 *       ⇒ 注册名<b>逐字沿用 B 侧</b>，不因合并而改成别的名字。</li>
 * </ul>
 *
 * <h2>为什么显式注册而不依赖自动注册</h2>
 * <p>后者默认依赖 Spring Web；本服务带 web 容器，但 B 侧是<b>显式注册</b>的实现
 * （B 侧自述：自动注册会把 web 容器启起来 / 走纯手动注册路径）⇒ 照搬。
 * <ol>
 *   <li>{@code ApplicationReadyEvent} 后注册 —— 与 B 侧「server Start 后立即注册」同序；</li>
 *   <li>容器关闭时反注册（{@code @PreDestroy}），防止重启残留 stale 节点。</li>
 * </ol>
 *
 * <h2>⚠️ Bean 名必须显式指定</h2>
 * <p>{@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为 {@code nacosRegistration} 的 Bean</b>。
 * 若本类用裸 {@code @Component}，默认 Bean 名恰好相同 ⇒ 撞名 ⇒ Spring Boot 默认
 * {@code allow-bean-definition-overriding=false} ⇒ <b>上下文直接起不来</b>。
 * ⇒ 本类沿用 B 侧的显式名 {@code followRpcNacosRegistration}。
 *
 * <h2>⚠️ group 必须读配置</h2>
 * <p>B 侧自述的漂移修复（DRIFT-G1-001）：group 若写死成 {@code DEFAULT_GROUP}，
 * 则 {@code NACOS_GROUP} 环境变量对 follow 域<b>完全无效</b>
 * ⇒ 而部署侧的 yml 写的是 {@code group: ${NACOS_GROUP:DEFAULT_GROUP}}（<b>读配置</b>）
 * ⇒ ⇒ 部署一旦设了不同的 group ⇒ <b>注册到 A 组、调用方去 B 组找</b> ⇒ 恒「找不到服务端」。
 * ⇒ ⇒ group 从配置读，<b>默认值零变化</b>。
 *
 * <h2>⚠️ 本类只在 {@code ApplicationReadyEvent} 时才注册</h2>
 * <p>与 B 侧一致（不是在 Bean 构造时就注册）。
 * 若提前到构造期注册，会出现「注册成功但端口还没 listen」的窗口，
 * 让调用方在这个窗口里拿到一个连不上的地址。
 */
@Component("followRpcNacosRegistration")
public class NacosRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(NacosRegistration.class);

    /** 对应 B 侧：注册键 {@code follow.rpc} ⇒ 服务发现按这个名注册。 */
    private static final String SERVICE_NAME = "follow-rpc";

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;
    private final String localIp;
    private final int nacosServerPort;

    /**
     * 注册用的 group —— <b>必须读配置</b>（见类注释「group 必须读配置」）。
     */
    private final String groupName;

    /** 当前已注册实例句柄（防止重复注册 / 用于 deregister）。 */
    private volatile Instance registeredInstance;

    public NacosRegistration(NacosServiceManager nacosServiceManager,
                             GrpcServerProperties grpcServerProperties,
                             @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
                             String nacosServerAddr,
                             @Value("${spring.cloud.nacos.discovery.group:DEFAULT_GROUP}")
                             String groupName) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.groupName = groupName;
        // nacos server 地址可能是 "host" 或 "host:port"
        String[] parts = nacosServerAddr.split(":");
        this.nacosServerPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 8848;
        String ip;
        try {
            ip = resolveLocalIp();
        } catch (UnknownHostException e) {
            ip = InetAddress.getLoopbackAddress().getHostAddress();
            LOG.warn("resolveLocalIp failed, fallback to loopback: {}", ip);
        }
        this.localIp = ip;
    }

    /** 对应 B 侧：服务启动后由框架触发的注册。 */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            Instance instance = new Instance();
            instance.setIp(localIp);
            instance.setPort(grpcServerProperties.getPort());
            instance.setServiceName(SERVICE_NAME);
            // Nacos 2.x Instance 不含 groupName 字段：group 走 registerInstance(serviceName, group, instance) 第 2 个参数
            instance.setHealthy(true);
            instance.setWeight(1.0);
            instance.setEphemeral(true);

            nacosServiceManager.getNamingService()
                    .registerInstance(SERVICE_NAME, groupName, instance);
            this.registeredInstance = instance;
            LOG.info("NacosRegistration registered: service={} group={} ip={} port={} nacosPort={}",
                    SERVICE_NAME, groupName, localIp, grpcServerProperties.getPort(), nacosServerPort);
        } catch (NacosException e) {
            // ⚠️ 注册失败只记日志、不让进程退出 —— 与 B 侧一致
            //    （B 侧注册失败会打日志，server 本身照常 Start）。
            //    这让本服务仍可被静态配置直连，只是不在服务发现里出现。
            LOG.error("NacosRegistration.register failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /** 容器关闭时反注册 —— 防止进程重启残留 stale 节点。 */
    @PreDestroy
    public void deregister() {
        if (registeredInstance == null) {
            return;
        }
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, groupName,
                            registeredInstance.getIp(), registeredInstance.getPort());
            LOG.info("NacosRegistration deregistered: service={} group={} ip={} port={}",
                    SERVICE_NAME, groupName,
                    registeredInstance.getIp(), registeredInstance.getPort());
        } catch (NacosException e) {
            LOG.warn("NacosRegistration.deregister failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * 对应 B 侧的注册用本地 IP 解析：优先取第一个非 loopback 网卡的 IPv4。
     *
     * <p>B 侧用 {@code net.InterfaceAddrs()}，C 侧用 {@link NetworkInterface} 遍历。
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
