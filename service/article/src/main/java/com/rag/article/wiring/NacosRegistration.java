package com.rag.article.wiring;

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
 * <p><b>职责</b>：article 域进程启动后<b>显式注册到 Nacos</b> —— 注册的是
 * <b>gRPC 端口</b>，<b>不是</b> web 容器端口。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/bootstrap/NacosRegistration.java}
 * （类声明与 bean 名 {@code :36-37} · 常量 {@code :41-45} · 字段与构造器 {@code :47-65} ·
 * {@code register} {@code :80-98} · {@code deregister} {@code :101-111} ·
 * {@code resolveLocalIp} {@code :114-136}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.article.article.rpc.bootstrap} →
 *       {@code com.rag.article.wiring}，D7 包压平）＋ <b>group 改为从配置读</b>
 *       （<b>默认值零变化</b>，见下）＋ <b>本段 javadoc</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>注册端口仍取 {@link GrpcServerProperties#getPort()}（= gRPC 业务口），
 *             <b>不是</b> web 端口 ⇒ 与 B 侧 {@code zrpc.MustNewServer} 注册 {@code ListenOn} 的语义一致；</li>
 *         <li>group <b>从配置读</b>、默认值仍是 {@code DEFAULT_GROUP}
 *             （= B 侧写死的那个值）⇒ <b>默认行为零变化</b>；</li>
 *         <li>服务名仍为 {@code "article-rpc"}（B 侧逐字照抄，见下方 L1 论证）。</li>
 *         <li>注册失败<b>只记日志、不阻止启动</b>（与 B 侧同款刻意偏离）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>🔴 硬要求：注册的是 gRPC 端口、不是 web 容器端口</b>
 * <p>B 侧的 {@code zrpc.MustNewServer} 注册的是 <b>业务 gRPC 端口</b>
 * （{@code service/article/rpc/etc/article.yaml} 的 {@code ListenOn: 0.0.0.0:9001}）
 * ⇒ ⇒ 自动注册一旦生效，Nacos 里会出现「article-rpc → web 端口」这条<b>错端口</b>实例
 * ⇒ ⇒ 调用方按 Nacos 给出的地址连过来 ⇒ 连到一个只提供 HTTP 的端口 ⇒ <b>gRPC 链路直接断</b>。
 * ⇒ ⇒ <b>必须</b>关掉自动注册（{@code spring.cloud.nacos.discovery.register-enabled: false}，
 * 见 {@code application.yml}），只留本类的<b>显式 gRPC 端口注册</b>。
 *
 * <p><b>⚠️ 服务名 {@code "article-rpc"} 是 L1，勿改</b>：
 * B 侧的 <b>comment / favorite / hot</b> 三个域通过 {@code discovery:///article-rpc}
 * 调用本域（B 出处：{@code service/comment/comment-rpc/src/main/resources/application.yml:250-251} ·
 * {@code service/favorite/favorite-rpc/src/main/resources/application.yml:279-282} ·
 * {@code service/hot/hot-api/src/main/resources/application.yml:16-19}）
 * ⇒ ⇒ 该名字是<b>跨域寻址契约</b> ⇒ ⇒ D1 合并进程只改了「进程拓扑」，
 * <b>没有</b>改「注册名」⇒ ⇒ 逐字保留 {@code "article-rpc"}。
 * （⚠️ 本仓 {@code spring.application.name} 是 {@code article-service}
 * —— 那是应用标识，与「对外注册名」是<b>两件事</b>。）
 *
 * <p><b>group 从 {@code spring.cloud.nacos.discovery.group} 读</b>（默认 {@code DEFAULT_GROUP}
 * = B 侧写死的那个值 ⇒ <b>默认行为零变化</b>）—— 与本仓已落地域
 * {@code service/message/.../wiring/NacosRegistration.java} 同款写法
 * （B 侧台账 {@code DRIFT-G1-001} 的修法：group 写死会让 {@code NACOS_GROUP} 环境变量完全无效）。
 *
 * <p><b>⚠️ Bean 名必须显式指定</b>（B 侧 2026-10-22 修，DRIFT-MSG-004）：
 * {@code spring-cloud-starter-alibaba-nacos-discovery} 的
 * {@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为
 * {@code nacosRegistration} 的 Bean</b>。若本类用裸 {@code @Component}，
 * 默认 Bean 名恰好也是 {@code nacosRegistration} ⇒ 两者撞名 ⇒
 * <b>Spring Boot 2.1+ 默认 {@code allow-bean-definition-overriding=false}</b>
 * ⇒ 上下文直接起不来。
 */
@Component("articleRpcNacosRegistration") // ← 显式命名，避开 NacosServiceRegistryAutoConfiguration 的同名 Bean
public class NacosRegistration {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    /** 对应 Go yaml {@code Etcd.Key: article.rpc} —— **跨域寻址契约（L1）**。 */
    private static final String SERVICE_NAME = "article-rpc";

    /** Spring Cloud Alibaba Nacos 默认 group（B 侧逐字照抄）—— 本类**不直接**用它注册（见下方 group 字段）。 */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /** 注册用的 group —— **从配置读**，不写死（默认值 = B 侧写死的那个值 ⇒ 零行为变化）。 */
    private final String groupName;

    private final NacosServiceManager nacosServiceManager;

    private final GrpcServerProperties grpcServerProperties;

    /** 对应 Go etcd 注册的 { ip, port }。 */
    private final String localIp;

    /**
     * 构造器 —— B 侧逐字照抄（另加 group 的配置读取）。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:51-65}。
     *
     * @param nacosServiceManager Spring Cloud Alibaba Nacos 客户端 Bean
     * @param grpcServerProperties devh grpc server 配置（含 port=9001）
     * @param nacosServerAddr     Nacos server 地址（默认 127.0.0.1:8848）
     * @param groupName           注册用的 group（读配置 · 默认 DEFAULT_GROUP）
     */
    public NacosRegistration(NacosServiceManager nacosServiceManager,
                             GrpcServerProperties grpcServerProperties,
                             @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
                             String nacosServerAddr,
                             @Value("${spring.cloud.nacos.discovery.group:" + DEFAULT_GROUP + "}")
                             String groupName) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.groupName = groupName;
        // ⚠️ nacosServerAddr 在 B 侧同样只作为构造参数出现、不被读取（逐字保留，不做顺手清理）。
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
     * <p>对应 Go 侧 {@code s.Start()} 之后 zrpc 立即 EtcdRegister 的时序：
     * 监听 {@link ApplicationReadyEvent} 而非 {@code ContextRefreshedEvent}，
     * 确保 gRPC server 已经真正起来。
     *
     * <p>⚠️ <b>注册失败只记日志、不阻止启动</b>（与 like-rpc 同款）：
     * Go 侧 etcd 注册失败会让 {@code MustNewServer} panic；
     * Java 单 JVM 里 gRPC 已经在监听，为一次注册失败杀掉整个进程，
     * 会让「Nacos 抖动 = 全站文章服务不可用」——比 Go 更糟。
     * 这是<b>刻意记录在案的偏离</b>。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:80-98}。
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
            log.info("NacosRegistration registered: service={} group={} ip={} port={}",
                    SERVICE_NAME, groupName, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            log.error("NacosRegistration.register failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * 容器关闭时反注册，防止进程重启残留 stale 节点。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:101-111}。
     */
    @jakarta.annotation.PreDestroy
    public void deregister() {
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, groupName, localIp, grpcServerProperties.getPort());
            log.info("NacosRegistration deregistered: service={} ip={} port={}",
                    SERVICE_NAME, localIp, grpcServerProperties.getPort());
        } catch (NacosException e) {
            log.warn("NacosRegistration.deregister failed for {}: {}", SERVICE_NAME, e.getErrMsg(), e);
        }
    }

    /**
     * 对应 Go 侧取第一个非 loopback IPv4；Java 用 {@link NetworkInterface} 遍历实现。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/NacosRegistration.java:114-136}。
     *
     * @return 本机可被其它实例访问到的 IPv4 地址
     * @throws UnknownHostException 连回环都拿不到时
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
