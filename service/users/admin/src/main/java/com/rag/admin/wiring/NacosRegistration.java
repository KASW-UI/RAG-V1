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
 *         <li>注册的服务名 <b>逐字沿用 B 侧</b> {@code "admin-rpc"}
 *             （<b>2026-11-02 修正</b>：原先改成了 {@code "admin-service"}，理由是
 *             「D1 合并后的进程身份，与 {@code spring.application.name} 一致」——
 *             ⚠️ <b>该前提已被 message 域推翻</b>：message 的 gRPC client 按 B 侧逐字寻址
 *             {@code discovery:///admin-rpc} ⇒ 注册名必须叫 {@code admin-rpc} 才寻址得到；
 *             且兄弟域 user 就是这个形状（app name {@code user-service} ＋ 注册名 {@code user-rpc}）
 *             ⇒ 详见 {@link #SERVICE_NAME} 的 L1 论证）；</li>
 *         <li>日志前缀 {@code admin-rpc} → {@code admin-service}（<b>纯日志</b>，无行为面）。</li>
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

    /**
     * 🆕 2026-11-02 · <b>Nacos 注册名 = B 侧 rpc 进程名，逐字沿用</b>。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/NacosRegistration.java:88}
     * —— B 侧 {@code String serviceName = "admin-rpc";}（<b>硬编码</b>）。
     *
     * <p><b>为什么必须逐字沿用</b>（L1 · 跨域寻址面）：
     * <ul>
     *   <li>B 侧 message 域的 gRPC client 按 <b>etcd key {@code admin.rpc}</b> 寻址 admin-rpc ⇒
     *       本仓换成 Nacos discovery 后，对应的服务名就是 {@code admin-rpc}
     *       （message 域的 {@code application.yml} 逐字写 {@code address: discovery:///admin-rpc}）；</li>
     *   <li>注册名若不叫 {@code admin-rpc} ⇒ **寻址落空** ⇒ 跨域 gRPC 链路断（业务码塌 1015）；</li>
     *   <li>⚠️ 与 {@code spring.application.name}（= {@code admin-service}）<b>刻意不同</b>：
     *       前者是<b>进程名</b>（本仓 artifactId 惯例，同时是 Prometheus 的 {@code application} 标签），
     *       后者是<b>服务发现名</b>（必须与 B 侧寻址键逐字一致）⇒ <b>兄弟域 user 就是这个形状</b>
     *       （{@code spring.application.name=user-service} ＋ Nacos 注册名 {@code user-rpc}）。</li>
     * </ul>
     *
     * <p>📌 **本条是一次「前提已变」的修正**：本类原先把注册名改成 {@code admin-service}，
     * 理由是「D1 合并后的进程身份，与 {@code spring.application.name} 一致；
     * 且本仓 D2 之后<b>没有</b>任何消费方按这个名字寻址 ⇒ 纯运维面」——
     * ⚠️ 后半个前提<b>已被 message 域推翻</b>（它按 B 侧逐字寻址 {@code admin-rpc}）。
     */
    private static final String SERVICE_NAME = "admin-rpc";

    /**
     * 🆕 2026-11-02 · <b>注册名可覆盖，但默认值逐字等于 B 侧</b>。
     *
     * <p><b>取值来源</b>：{@code spring.cloud.nacos.discovery.service}
     * （**Spring Cloud Alibaba 的标准键** —— 语义恰好就是「注册到 Nacos 的服务名」）
     * ⇒ <b>未配置时回落 {@link #SERVICE_NAME} = {@code "admin-rpc"}</b>（B 侧逐字值）。
     *
     * <p><b>为什么默认值不能改</b>（L1 · 跨域寻址面）：
     * message 域的 gRPC client 按 <b>B 侧逐字</b>寻址 {@code discovery:///admin-rpc}
     * ⇒ 注册名一旦不是 {@code admin-rpc} ⇒ <b>寻址落空</b> ⇒ 跨域链路断（业务码塌 1015）。
     *
     * <p><b>为什么要留这个覆盖口</b>（不是「行为面」的灵活性，是**测试环境的隔离需求**）：
     * E-4 双侧活体对拍环境里，**B 侧与 C 侧共用同一个 Nacos 实例**
     * （B 侧 {@code admin-rpc} ＋ C 侧同时注册）⇒ 若两侧同名 ⇒ **同服务名下两个实例**
     * ⇒ 消费方按服务名发现时可能拿到**另一侧的实例**（端口不同）⇒ **对拍打到错误进程 ⇒ 假绿/假红**。
     * ⇒ ⇒ 对拍环境把 C 侧覆盖成另一个名字即可**错开**，而**默认部署形态零变化**
     * （与 {@link #groupName} 的 DRIFT-G1-001 同款处理：**读配置 · 默认零变化**）。
     *
     * <p>⚠️ <b>不得把这个键当成「改名开关」随意用</b> —— 改了它就等于改了
     * <b>跨域寻址键</b>（L1）⇒ 需走决策流程。
     */
    private final String serviceName;

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
                             String groupName,
                             @Value("${spring.cloud.nacos.discovery.service:admin-rpc}")
                             String serviceName) {
        this.nacosServiceManager = nacosServiceManager;
        this.grpcServerProperties = grpcServerProperties;
        this.groupName = groupName;
        // ⚠️ 空值兜底：属性被显式配成空串/空白时，**不得**注册出一个空服务名
        //    ⇒ 回落到 B 侧逐字值（与「默认值零变化」同一条纪律）。
        this.serviceName = (serviceName == null || serviceName.isBlank())
                ? SERVICE_NAME
                : serviceName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        // 🔴 2026-11-02 修正（前提已变）：注册名**回到 B 侧逐字值 `admin-rpc`**。
        //    原先写死 `admin-service` 的理由是「D1 合并后的进程身份，与 spring.application.name 一致，
        //    且本仓 D2 之后没有任何消费方按这个名字寻址 ⇒ 纯运维面」——
        //    ⚠️ **该前提已不成立**：message 域的 gRPC client 按 B 侧逐字寻址 `discovery:///admin-rpc`
        //    （B 侧 zrpc 的 etcd key 是 `admin.rpc`）⇒ 注册名仍叫 `admin-service` ⇒ **寻址落空**
        //    ⇒ 跨域 gRPC 链路断（业务码塌 1015）。
        //    ⇒ 且它同时偏离 B 侧（`admin-rpc`）与兄弟域形状（user 域：`spring.application.name=user-service`
        //    但 **Nacos 注册名 = `user-rpc` 逐字沿用 B 侧** ⇒ 两者本来就该分叉）。
        //    ⇒ 本仓约定（user 域已确立）：**Nacos 注册名 = B 侧 rpc 进程名（逐字）**；
        //      **spring.application.name = 本仓 artifactId 惯例（`admin-service`）** ⇒ 后者不动。
        //    ⇒ 🆕 2026-11-02：改为**读配置**（`spring.cloud.nacos.discovery.service`）
        //      ⇒ **默认值仍逐字等于 B 侧的 `admin-rpc`**（不配置时行为零变化）
        //      ⇒ 覆盖口只服务于「E-4 双侧对拍环境里 B/C 共用一个 Nacos ⇒ 必须错开服务名」的隔离需求。
        String serviceName = this.serviceName;
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
