package com.rag.task.wiring;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <p><b>task 域启动后注册到 Nacos</b> —— 对应 Go 端 {@code service/task/rpc/task.go:37} 里
 * {@code zrpc.MustNewServer(c.RpcServerConf, …)} 的隐式 etcd 注册。</p>
 *
 * <p>Go {@code etc/task.yaml} 的 {@code Name: task.rpc} + {@code Etcd.Key: task.rpc}
 * ⇒ 服务发现名统一为 {@code task-rpc}。</p>
 *
 * <h2>🔴 Bean 名必须显式指定（DRIFT-MSG-004 同款）</h2>
 * <p>{@code NacosServiceRegistryAutoConfiguration} <b>也会注册一个名为
 * {@code nacosRegistration} 的 Bean</b>。若本类用裸 {@code @Component}，
 * 默认 Bean 名恰好相同 ⇒ Spring Boot 默认
 * {@code allow-bean-definition-overriding=false} ⇒ <b>上下文直接起不来</b>。
 * ⇒ 本类用 {@code @Component("taskRpcNacosRegistration")}（与 like / follow 域同款，
 * 跨域 bean 名也不冲突）。</p>
 *
 * <h2>⚠️ 注册失败<b>不让进程退出</b></h2>
 * <p>Go 侧 go-zero 的 etcd 注册失败会 panic，但那是另一个失败面；
 * C 侧选择「注册失败只告警」，避免配置中心抖动导致业务进程批量重启。
 * 本进程仍能提供 gRPC 服务（只是<b>没有人能发现它</b>）。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>落点</b>：{@code bootstrap/} → {@code wiring/}（D10）；</li>
 *         <li><b>Bean 名</b>：显式为 {@code taskRpcNacosRegistration}（B 侧已是显式，<b>值不变</b>）；</li>
 *         <li><b>补注销</b>（{@link #deregister()}）：B 侧<b>没有</b>注销逻辑
 *             ⇒ C 侧在 {@link PreDestroy} 时注销，避免容器重启后在 Nacos 里留下僵尸实例。
 *             <b>这是纯运维卫生</b>：注册/注销成对，不改变任何业务语义
 *             （B 侧容器被杀时实例同样会消失，只是 Nacos 要等心跳超时）。</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 服务名 {@code task-rpc}、
 *       {@code Instance} 的 5 个属性、注册时机（{@link ApplicationReadyEvent}）、
 *       注册失败只记日志、出口 IP 解析的判定条件，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/bootstrap/NacosRegistration.java:34-40（类声明 + bean 名 + 服务名）
 * B 出处：…/bootstrap/NacosRegistration.java:49-61（构造：依赖 + 出口 IP 解析）
 * B 出处：…/bootstrap/NacosRegistration.java:75-94（register：ApplicationReadyEvent + 失败只记日志）
 * B 出处：…/bootstrap/NacosRegistration.java:105-127（resolveLocalIp）
 *       </pre></li>
 * </ol>
 */
@Component("taskRpcNacosRegistration")
public class NacosRegistration {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    /** 对应 Go yaml {@code Etcd.Key: task.rpc}。 */
    private static final String SERVICE_NAME = "task-rpc";

    private final NacosServiceManager nacosServiceManager;
    private final GrpcServerProperties grpcServerProperties;
    private final String defaultGroup;

    /** 对应 Go etcd 注册的 { ip, port }。 */
    private final String localIp;

    private volatile Instance registeredInstance;

    public NacosRegistration(NacosServiceManager nacosServiceManager,
                             GrpcServerProperties grpcServerProperties,
                             @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
                             String nacosServerAddr,
                             @Value("${spring.cloud.nacos.discovery.group:DEFAULT_GROUP}")
                             String defaultGroup) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.defaultGroup = defaultGroup;
        this.localIp = resolveLocalIp();
        log.info("task-rpc nacos registration target: addr={} service={} group={} ip={} port={}",
                nacosServerAddr, SERVICE_NAME, defaultGroup, this.localIp, grpcServerProperties.getPort());
    }

    /**
     * Spring 启动完成后注册。
     *
     * <p>对应 Go 侧 {@code s.Start()} 后由 zrpc 立即 EtcdRegister 的时序：
     * 监听 {@link ApplicationReadyEvent} 而不是 {@code ContextRefreshedEvent}，
     * 确保 gRPC server 与 Kafka listener 都已真正起来。</p>
     *
     * <p>⚠️ <b>注册失败不阻止启动</b>：Nacos 不可用时本进程仍能提供 gRPC 服务
     * （只是<b>没有人能发现它</b>）。</p>
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
                    .registerInstance(SERVICE_NAME, defaultGroup, instance);
            this.registeredInstance = instance;
            log.info("task-rpc registered to nacos: {}:{} (group={})",
                    localIp, grpcServerProperties.getPort(), defaultGroup);
        } catch (Exception e) {
            log.error("task-rpc nacos registration failed (process keeps serving gRPC, "
                    + "but no caller can discover it): {}", e.getMessage(), e);
        }
    }

    /**
     * 容器关闭前注销 —— <b>B 侧没有这一步</b>（见类注释「补注销」）。
     *
     * <p>只在本进程<b>确实注册过</b>时才注销 ⇒ 启动期注册失败不会在这里抛二次异常。</p>
     */
    @PreDestroy
    public void deregister() {
        Instance instance = this.registeredInstance;
        if (instance == null) {
            return;
        }
        try {
            nacosServiceManager.getNamingService()
                    .deregisterInstance(SERVICE_NAME, defaultGroup, instance.getIp(), instance.getPort());
            log.info("task-rpc deregistered from nacos: {}:{} (group={})",
                    instance.getIp(), instance.getPort(), defaultGroup);
        } catch (NacosException e) {
            log.warn("task-rpc nacos deregistration failed: {}", e.getErrMsg(), e);
        }
    }

    /**
     * 解析本机出口 IP —— 与 like-rpc / message-rpc 同款实现。
     *
     * <p>⚠️ {@code NetworkInterface.getNetworkInterfaces()} / {@code getInetAddresses()}
     * 声明的是 {@code SocketException}（checked），不是 {@code UnknownHostException}。
     * 这里一并 catch：解析不出来就回落到 {@link InetAddress#getLoopbackAddress()}，
     * 而不是让构造器抛异常 —— 构造器抛异常会导致<b>上下文起不来</b>，
     * 而「拿不到出口 IP」不该阻止进程启动（只是注册出去的地址不理想）。</p>
     */
    private static String resolveLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    // 跳过 IPv6（Go 侧注册的是 v4 地址；含 ':' 即 v6）
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            // NetworkInterface 相关 API 抛的是 SocketException，这里统一回落
            return InetAddress.getLoopbackAddress().getHostAddress();
        }
    }
}
