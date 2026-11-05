package com.rag.comment.wiring;

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
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/bootstrap/NacosRegistration.java:1-135}
 * （B 侧的语义来源注释写的是 Go 端 {@code service/comment/rpc/comment.go:47-53} 里
 * {@code zrpc.MustNewServer(c.RpcServerConf, …)} 的隐式 etcd 注册）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.bootstrap} 移到 {@code com.rag.comment.wiring}（D7 · D10）；</li>
 *       <li><b>group 改为从配置读</b>（{@code spring.cloud.nacos.discovery.group}，默认值仍是
 *           {@code DEFAULT_GROUP}）—— B 侧是<b>硬编码</b> {@code DEFAULT_GROUP}
 *           （{@code NacosRegistration.java:45}），而 B 侧自己的 yml
 *           （{@code comment-rpc/.../application.yml:220}）<b>又写了</b> {@code group: ${NACOS_GROUP:DEFAULT_GROUP}}
 *           ⇒ ⇒ B 侧那个键<b>没有消费点</b>（§3.3：「写了也可能没接」）⇒
 *           C 侧改为读配置 ⇒ 默认配置下<b>生效值逐字相同</b>，而该键不再是无消费点的死配置；</li>
 *       <li><b>反注册加了一道「未注册成功就不反注册」的守卫</b>
 *           （B 侧 {@code :99-109} 无条件发起反注册）⇒ 注册失败时不再多打一条无意义的 warn 日志。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。服务名 {@code comment-rpc}、注册参数（ip / port / healthy / weight / ephemeral）、
 *       「注册失败只记日志不让进程退出」、监听 {@link ApplicationReadyEvent} 的时序、
 *       本机 IP 解析规则逐字未改。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../bootstrap/NacosRegistration.java:1-135}。</li>
 * </ol>
 *
 * <h2>⚠️ Bean 名必须显式指定（DRIFT-MSG-004）</h2>
 * <p>{@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为
 * {@code nacosRegistration} 的 Bean</b>。若本类用裸 {@code @Component}，
 * 默认 Bean 名恰好相同 ⇒ Spring Boot 默认 {@code allow-bean-definition-overriding=false}
 * ⇒ <b>上下文直接起不来</b>。⇒ 本类沿用 B 侧的显式 Bean 名 {@code commentRpcNacosRegistration}。
 *
 * <h2>⚠️ 为什么不依赖 nacos-discovery 的自动注册</h2>
 * <p>B 侧注释自记：自动注册依赖 Spring Web，纯 gRPC 进程强行用会把 web 容器拉起来。
 * ⇒ 本类<b>手写注册</b>，注册的是 <b>gRPC 端口</b>（{@code GrpcServerProperties#getPort()}），
 * 而非 web 容器端口。
 */
@Component("commentRpcNacosRegistration")
public class NacosRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(NacosRegistration.class);

    /**
     * 服务发现名 —— 对应 B 侧 {@code NacosRegistration.java:42} 的字面量 {@code "comment-rpc"}
     * （其注释写的是 Go yaml 的 {@code Etcd.Key: comment.rpc} ⇒ 服务发现名统一为 {@code comment-rpc}）。
     */
    private static final String SERVICE_NAME = "comment-rpc";

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

    /**
     * Spring 启动完成后注册 —— 对应 B 侧 {@code NacosRegistration.java:67-96}。
     *
     * <p>对应 Go 侧 {@code s.Start()} 后由 zrpc 立即 EtcdRegister 的时序：
     * 监听 {@link ApplicationReadyEvent} 而不是 {@code ContextRefreshedEvent}，
     * 确保 gRPC server 已经真正起来。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            Instance instance = new Instance();
            instance.setIp(localIp);
            instance.setPort(grpcServerProperties.getPort());
            instance.setServiceName(SERVICE_NAME);
            // Nacos 2.x 的 Instance 不含 groupName 字段：group 走 registerInstance(serviceName, group, instance) 的第 2 个参数
            instance.setHealthy(true);
            instance.setWeight(1.0);
            instance.setEphemeral(true);

            nacosServiceManager.getNamingService()
                    .registerInstance(SERVICE_NAME, groupName, instance);
            this.registeredInstance = instance;
            LOG.info("NacosRegistration registered: service={} group={} ip={} port={} nacosPort={}",
                    SERVICE_NAME, groupName, localIp, grpcServerProperties.getPort(), nacosServerPort);
        } catch (NacosException e) {
            // ⚠️ 注册失败只记日志、不让进程退出 —— 与 B 侧一致。
            // B 侧自记的理由：Go 侧 etcd 注册失败会让 MustNewServer panic ⇒ 进程退出；
            // 这里选择记日志继续启动（Nacos 可能只是暂时不可用）。
            // ⚠️ 这是刻意的偏离：单 JVM 里 gRPC 已经起来了，为注册失败把整个服务杀掉
            //    会让「Nacos 抖动 = 全站点评论不可用」，比 Go 更糟。
            LOG.error("NacosRegistration.register failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * 容器关闭时反注册，防止进程重启残留 stale 节点 —— 对应 B 侧 {@code NacosRegistration.java:98-109}。
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
            LOG.info("NacosRegistration deregistered: service={} ip={} port={}",
                    SERVICE_NAME, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            LOG.warn("NacosRegistration.deregister failed: {}", e.getErrMsg(), e);
        }
    }

    /**
     * 取本机第一个非 loopback 的 IPv4 —— 对应 B 侧 {@code NacosRegistration.java:111-134}。
     *
     * <p>⚠️ 跳过 {@code isLoopback} / {@code !isUp} / {@code isPointToPoint} 的网卡，
     * 并跳过含 {@code ':'} 的地址（IPv6）—— 逐条照抄 B 侧。
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
            // 枚举网卡失败 ⇒ 落到 InetAddress.getLocalHost()
        }
        return InetAddress.getLocalHost().getHostAddress();
    }
}
