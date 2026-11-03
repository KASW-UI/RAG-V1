package com.rag.like.wiring;

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
 * <p><b>Nacos 服务注册</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/bootstrap/NacosRegistration.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code bootstrap/} 移到 {@code wiring/}（D10：{@code bootstrap} → {@code wiring}）；</li>
 *       <li>bean 名固定为 {@code likeRpcNacosRegistration}（与 follow 域的
 *           {@code followRpcNacosRegistration} 同款 ⇒ 跨域 bean 名不冲突）；</li>
 *       <li>group 从 {@code spring.cloud.nacos.discovery.group} 读取
 *           （B 侧硬编码 {@code DEFAULT_GROUP}）—— 与 follow 域同款。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。服务名 {@code like-rpc}、注册参数、注册失败只记日志不让进程退出、
 *       注销逻辑逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 注册失败<b>不让进程退出</b></h2>
 * <p>B 侧注册失败会打日志，server 本身照常 Start ⇒ 这让本服务仍可被静态配置直连，
 * 只是不在服务发现里出现。照抄。
 */
@Component("likeRpcNacosRegistration")
public class NacosRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(NacosRegistration.class);

    private static final String SERVICE_NAME = "like-rpc";

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;
    private final String localIp;
    private final int nacosServerPort;
    private final String groupName;
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
            LOG.error("NacosRegistration.register failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    @PreDestroy
    public void deregister() {
        if (registeredInstance == null) {
            return;
        }
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, groupName,
                            registeredInstance.getIp(), registeredInstance.getPort());
            LOG.info("NacosRegistration deregistered: service={} ip={} port={}",
                    SERVICE_NAME, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            LOG.warn("NacosRegistration.deregister failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

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
            // 枚举网卡失败 ⇒ 退回 InetAddress.getLocalHost()
        }
        return InetAddress.getLocalHost().getHostAddress();
    }
}
