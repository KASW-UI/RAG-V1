package com.rag.favorite.wiring;

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
 * <p><b> favorite 域注册到 Nacos</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteNacosRegistration.java}
 * （逐条照抄 · L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <p>对应 Go 端 {@code service/favorite/rpc/favorite.go:42} 里
 * {@code zrpc.MustNewServer(c.RpcServerConf, …)} 的隐式 etcd 注册。
 *
 * <p>Go {@code etc/favorite.yaml:32-35} 的 {@code Etcd.Key: favorite.rpc}
 * ⇒ 服务发现名统一为 {@code favorite-rpc}（与 {@code etc/favorite.yaml:1} 的
 * {@code Name: favorite.rpc} 一致）。
 *
 * <p>显式手写注册而不依赖 {@code spring-cloud-starter-alibaba-nacos-discovery} 自动注册，
 * 原因与 message-rpc / like-rpc 同款：自动注册依赖 Spring Web，
 * 纯 gRPC 进程强行用会把 web 容器拉起来（本服务只应监听 gRPC 8082）。
 *
 * <p><b>⚠️ Bean 名必须显式指定（DRIFT-MSG-004）</b>：
 * {@code spring-cloud-starter-alibaba-nacos-discovery} 的
 * {@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为
 * {@code nacosRegistration} 的 Bean</b>。若本类用裸 {@code @Component}，
 * 默认 Bean 名恰好相同 ⇒ Spring Boot 默认
 * {@code allow-bean-definition-overriding=false} ⇒ <b>上下文直接起不来</b>。
 *
 * <p>⚠️ <b>注册失败不退出进程</b>（与 message-rpc / like-rpc 同款，是刻意的偏离）：
 * Go 的 {@code MustNewServer} 在 Etcd 注册失败时会 panic，但单 JVM 里 gRPC 已经起来了 ——
 * 为注册失败杀掉整个服务，会让「Nacos 抖动 = 全站点收藏不可用」，比 Go 更糟。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.favorite.favorite.rpc.bootstrap} 压平为
 *           {@code com.rag.favorite.wiring}（D7 包压平 + D10 {@code bootstrap} → {@code wiring}）；</li>
 *       <li>{@code @jakarta.annotation.PreDestroy} 的 import 由全限定名改为正常 import
 *           （Boot 3.5 ⇒ 仍是 {@code jakarta.*}，<b>不是</b> {@code javax.*}）；</li>
 *       <li>构造器里那个 B 侧<b>收下但从不使用</b>的 {@code nacosServerAddr} 参数，
 *           本类<b>原样保留</b>（见下「⚠️ 为什么保留一个没用到的参数」），
 *           <b>没有</b>像 like 域那样拿它去解析端口再打进日志 ——
 *           那会让日志内容与 B 侧不同（多一个字段）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。服务名 {@code favorite-rpc}、group {@code DEFAULT_GROUP}、
 *       实例参数（ip / port / healthy / weight=1.0 / ephemeral）、注册时机
 *       （{@link ApplicationReadyEvent} 而非 {@code ContextRefreshedEvent}）、
 *       「注册失败只记 ERROR 不退出」、「关闭时反注册」逐条未改。</li>
 *   <li><b>B 出处</b>：
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteNacosRegistration.java:42-140}
 *       （类声明 :42-43 · 常量 :48 / :51 · 字段与构造器 :53-71 ·
 *       {@code register} :80-99 · {@code deregister} :102-112 · {@code resolveLocalIp} :118-140）。</li>
 * </ol>
 *
 * <h2>⚠️ group 为什么<b>硬编码</b> {@code DEFAULT_GROUP}</h2>
 * <p>B 侧的 {@code application.yml:139} 暴露了 {@code spring.cloud.nacos.discovery.group}
 * （{@code ${NACOS_GROUP:DEFAULT_GROUP}}），但 B 侧的注册类<b>不读它</b>、硬编码
 * {@code DEFAULT_GROUP}。
 * <p>⇒ 本类<b>照抄硬编码</b>（D16：L1 契约层以 B 侧为准；「顺手改成读配置」会让
 * C 侧在 {@code NACOS_GROUP ≠ DEFAULT_GROUP} 时与 B 侧注册到<b>不同的 group</b>
 * ⇒ 服务发现行为不一致 ⇒ 那不是 idiomatization，是行为漂移）。
 * <p>⚠️ like / follow 域是<b>读配置</b>的（那两侧的 B 侧本就是骨架占位，没有可比对的 oracle）
 * ⇒ 三域形态不一致这件事<b>已登记</b>，不在本类「顺手统一」的范围内。
 */
@Component("favoriteRpcNacosRegistration")
public class FavoriteNacosRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteNacosRegistration.class);

    /** 对应 Go {@code etc/favorite.yaml:35} {@code Etcd.Key: favorite.rpc}。 */
    private static final String SERVICE_NAME = "favorite-rpc";

    /** Spring Cloud Alibaba Nacos 默认 group。 */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;
    private final String localIp;

    /**
     * ⚠️ <b>为什么保留一个没用到的参数</b>：B 侧的构造器签名里就有它（且同样不使用）
     * ⇒ 删掉会改变<b>构造器的注入契约</b>（虽然 Spring 按类型注入，
     * 但形状逐条一致更利于双侧对照）。
     */
    public FavoriteNacosRegistration(NacosServiceManager nacosServiceManager,
                                     GrpcServerProperties grpcServerProperties,
                                     @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
                                     String nacosServerAddr) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
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
     * Spring 启动完成后注册。
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
            instance.setHealthy(true);
            instance.setWeight(1.0);
            instance.setEphemeral(true);

            nacosServiceManager.getNamingService()
                    .registerInstance(SERVICE_NAME, DEFAULT_GROUP, instance);
            LOG.info("FavoriteNacosRegistration registered: service={} group={} ip={} port={}",
                    SERVICE_NAME, DEFAULT_GROUP, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            LOG.error("FavoriteNacosRegistration.register failed for {}: {}",
                    SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /** 容器关闭时反注册，防止进程重启残留 stale 节点。 */
    @PreDestroy
    public void deregister() {
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, DEFAULT_GROUP, localIp, grpcServerProperties.getPort());
            LOG.info("FavoriteNacosRegistration deregistered: service={} ip={} port={}",
                    SERVICE_NAME, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            LOG.warn("FavoriteNacosRegistration.deregister failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * 对应 Go 侧 {@code net.InterfaceAddrs()} 取第一个非 loopback IPv4；
     * Java 用 {@link NetworkInterface} 遍历实现。
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
            // 落到 InetAddress.getLocalHost
        }
        return InetAddress.getLocalHost().getHostAddress();
    }
}
