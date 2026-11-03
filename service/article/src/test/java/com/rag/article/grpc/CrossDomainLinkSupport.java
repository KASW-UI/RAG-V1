package com.rag.article.grpc;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import net.devh.boot.grpc.client.nameresolver.DiscoveryClientResolverFactory;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <p><b>「真 Nacos + 真 message-rpc」跨域链路的测试期支撑工具</b>（<b>不是测试类</b>）。
 *
 * <h2>它解决的是什么问题</h2>
 * <p>本仓 {@code service/article/src/main/java/com/rag/article/client/MessageRpcClient.java} 的
 * 出向通道走的是 <b>真服务发现</b>：{@code @GrpcClient("message-rpc")} ＋
 * {@code grpc.client.message-rpc.address = discovery:///message-rpc}
 * ⇒ 地址由 <b>Nacos 注册表</b>在运行期给出 ⇒
 * 「对端在不在」这件事<b>只能靠实测回答</b>，靠读代码 / 读注解都答不了。
 * <p>⇒ 本类提供三件事：
 * <ol>
 *   <li><b>把对端发布进一个隔离的 Nacos 组</b>（{@link #publishPeerMirror}），
 *       让宿主 JVM 能通过服务发现拿到<b>可拨通的</b>地址；</li>
 *   <li><b>读对端自己的读数</b>：对端库的落库行（{@link #peerNotificationRows}）
 *       ＋ 对端自己的 Prometheus 计数（{@link #peerCounter}）；</li>
 *   <li><b>轮询等待</b>（{@link #awaitTrue}）——「端口已监听」≠「服务已注册」，
 *       必须<b>轮询注册表</b>等实例真的出现。</li>
 * </ol>
 *
 * <h2>🔴 为什么需要「镜像实例」而不是直接用对端自己注册的那条</h2>
 * <p>对端容器（{@code sea/message-rpc:latest}）在 Nacos 里注册的是
 * <b>它自己的容器 IP</b>：{@code 172.19.0.9:8086}（namespace=public / group=DEFAULT_GROUP）。
 * <p>但本机跑的是 <b>Docker Desktop for Mac</b> ⇒ <b>容器 IP 在宿主上不可路由</b>
 * （实测：{@code nc -z 172.19.0.9 8086} → <b>不通</b>；{@code nc -z 127.0.0.1 8086} → <b>通</b>）。
 * <p>⇒ 宿主上的 JVM <b>只能</b>通过 Docker 的<b>发布端口</b> {@code 127.0.0.1:8086} 触达对端
 * （{@code docker ps} 实测：{@code 0.0.0.0:8086->8086/tcp} ⇒ 宿主 8086 == 容器 8086 == 对端的
 * {@code MESSAGE_RPC_PORT} ⇒ <b>同一个进程</b>）。
 * <p>⇒ 所以本类在<b>隔离组</b>里发布一条<b>宿主可达</b>的镜像实例（{@code 127.0.0.1:8086}），
 * 让服务发现给出的地址<b>真的拨得通</b>。
 * <p>⚠️ <b>镜像的只是「地址」，不是「对端」</b>：gRPC 字节、服务发现机制、对端业务代码、
 * 对端自己的数据库写入，<b>全都是真的</b>（见
 * {@code ArticleToMessageRpcCrossDomainTest} 里对端库落库行 + 对端自己的 Prometheus 计数的断言）。
 *
 * <h2>🔴 服务发现的分组隔离（AGENTS.md §五.3 + 本次任务硬约束 ⑥）</h2>
 * <p>对端自己注册在 {@code public / DEFAULT_GROUP} —— 那是<b>全站共享</b>的组，
 * 里面有 message-api / user-rpc / admin-rpc 等真实流量。
 * <p>⇒ 本测试<b>绝不</b>往那个组里塞任何东西（塞进去会让别的服务拨到自己 ⇒ 静默地错）。
 * <p>⇒ 本测试另开两个组：
 * <ul>
 *   <li>{@link #LINK_GROUP} —— 放镜像实例（正例用）；</li>
 *   <li>{@link #ABSENT_GROUP} —— <b>故意为空</b>（反例用，模拟「对端不在」）。</li>
 * </ul>
 * <p>⇒ C 侧进程的<b>自身注册</b>（{@code article-rpc}）也落在这两个隔离组里 ⇒
 * 与共享组彻底不串。
 *
 * <h2>⚠️ 本类<b>只读</b>对端状态，不改对端任何业务数据</h2>
 * <p>唯一会<b>写</b>对端库的动作，是<b>被测代码自己</b>通过 gRPC 触发的
 * （{@code MessageRpcClient#sendArticlePublished} ⇒ 对端 {@code insertNotificationsTx}）。
 * ⇒ 测试只<b>读</b>那些行。
 *
 * <h2>B 出处（判据 ↔ B 侧出处）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>出向调用的<b>唯一</b>入口 ＋ 失败语义（吞异常返 false）</td>
 *       <td>{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/MessageRpcClient.java:64}
 *           （方法）· {@code :96}（catch StatusRuntimeException ⇒ 返 false）</td></tr>
 *   <tr><td>对端<b>注册名</b> = {@code message-rpc}（跨域寻址契约）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/bootstrap/NacosRegistration.java:84}
 *           （{@code SERVICE_NAME}）· {@code :87}（{@code DEFAULT_GROUP}）· {@code :141}（registerInstance）</td></tr>
 *   <tr><td>对端<b>收到</b>之后应当<b>落库</b>（本测试的「下游证据」）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageDomainService.java:467}
 *           （{@code insertNotificationsTx}）· {@code :459}（构造通知行）</td></tr>
 *   <tr><td>对端<b>自己的</b>可观测读数（RPC 计数）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/metrics/MessageMetrics.java:45}
 *           （{@code message_rpc_request_total}）· {@code :77}（observeRpc）</td></tr>
 * </table>
 */
final class CrossDomainLinkSupport {

    // ==================================================================
    // 环境事实（2026-11-03 实测 · 全部来自 docker ps / docker inspect）
    // ==================================================================

    /** 真 Nacos 的 HTTP 口（容器宿主 49848 → 容器 8848）。 */
    static final String NACOS_BASE = "http://127.0.0.1:49848";

    /** 真 Nacos 的主机（SDK 客户端用）。 */
    static final String NACOS_HOST = "127.0.0.1";

    /** 真 Nacos 的端口（SDK 客户端用；gRPC 命名口 = 该端口 + 1000 = 50848，宿主已发布）。 */
    static final int NACOS_PORT = 49848;

    /** 对端自己注册的 namespace（{@code Sea-message-rpc} 的 {@code NACOS_NAMESPACE=public}）。 */
    static final String NAMESPACE = "public";

    /** 对端自己注册的 group（{@code NACOS_GROUP=DEFAULT_GROUP}）—— <b>共享组，本测试只读</b>。 */
    static final String PEER_OWN_GROUP = "DEFAULT_GROUP";

    /** 对端对外的注册名（L1 跨域寻址契约）。 */
    static final String PEER_SERVICE_NAME = "message-rpc";

    /** 本测试专用的隔离组 —— 镜像实例放这里（正例）。 */
    static final String LINK_GROUP = "U2_ARTICLE_LINK";

    /** 本测试专用的隔离组 —— <b>故意为空</b>（反例：模拟「对端不在」）。 */
    static final String ABSENT_GROUP = "U2_ARTICLE_LINK_ABSENT";

    /** 宿主可达的对端地址 = Docker 发布端口 {@code 0.0.0.0:8086->8086} 的宿主侧。 */
    static final String PEER_GRPC_HOST = "127.0.0.1";

    /** 对端的 gRPC 端口（容器内 8086 = 宿主 8086，同一个进程）。 */
    static final int PEER_GRPC_PORT = 8086;

    /** 对端的管理端口（宿主 16067 → 容器 16067）—— 读它自己的 Prometheus。 */
    static final int PEER_MANAGEMENT_PORT = 16067;

    /** 对端自己的库（{@code Sea-postgres} 宿主 49432 → 容器 5432，库名 {@code sea_user}）。 */
    static final String PEER_DB_URL = "jdbc:postgresql://127.0.0.1:49432/sea_user";

    /** 对端库的账号（{@code Sea-message-rpc} 的 {@code POSTGRES_USER=sea}）。 */
    static final String PEER_DB_USER = "sea";

    /**
     * 对端库的口令 —— 取自 {@code Sea-message-rpc} 容器的 {@code POSTGRES_PASSWORD}。
     *
     * <p>⚠️ <b>这是本地开发容器的口令</b>（不是生产密钥）⇒ 允许写死，但允许用环境变量覆盖：
     * {@code U2_PEER_DB_PASSWORD}。容器若重建并换了口令，本测试需同步（或设该环境变量）。
     */
    static final String PEER_DB_PASSWORD =
            System.getenv().getOrDefault("U2_PEER_DB_PASSWORD", "RYqdOdx2EvPWw1sQ0_RvXWVrDxav0phB");

    /** 对端把通知写进的那张表（B 侧 {@code NotificationEntity} 的 {@code @TableName}）。 */
    static final String PEER_NOTIFICATION_TABLE = "message_notification";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 发布镜像实例用的<b>真 Nacos 客户端</b>（SDK 自带心跳 ⇒ 实例不会被 Nacos 摘掉）。
     *
     * <p>⚠️ <b>必须持有引用</b>：{@code NacosFactory.createNamingService} 返回的客户端内部有心跳线程，
     * 一旦被回收，心跳就停 ⇒ 临时实例 15s 后被清理 ⇒ 解析时 {@code No servers found}。
     */
    private static volatile NamingService mirrorNamingService;

    private CrossDomainLinkSupport() {
    }

    // ==================================================================
    // ① Nacos：发布 / 撤销 / 查询
    // ==================================================================

    /**
     * 把对端<b>发布</b>进隔离组（用<b>真 Nacos 客户端 SDK</b>，与对端容器同款 API）。
     *
     * <p>⚠️ <b>为什么不用 Nacos 的 HTTP open API 注册</b>（实测踩过的坑）：
     * <pre>
     *   POST /nacos/v1/ns/instance?...&ephemeral=true   ← 实测：约 30~35 秒后被 Nacos 摘掉
     * </pre>
     * ⇒ 原因：<b>HTTP 注册的临时实例没有心跳</b>（心跳由客户端 SDK 的 beat 任务发），
     * 而 Nacos 的 {@code ClientBeatCheckTask} 按 {@code instanceHeartBeatTimeOut=15s} 清理
     * 「没有心跳的临时实例」⇒ ⇒ 注册表里看着有、实际早就没了 ⇒ 解析时 {@code No servers found}。
     * <p>⇒ 改用 <b>SDK 的 {@code NamingService#registerInstance}</b>（= 对端容器
     * {@code NacosRegistration#register} 用的同一个 API，B 出处
     * {@code service/message/message-rpc/.../bootstrap/NacosRegistration.java:141}）
     * ⇒ SDK 自己的 beat 任务会持续续约 ⇒ 实例在整个测试期间稳定存在。
     *
     * @param group 目标组（隔离组，<b>不得</b>用共享组）
     * @return 是否发布成功
     */
    static synchronized boolean publishPeerMirror(String group) {
        try {
            if (mirrorNamingService == null) {
                Properties props = new Properties();
                props.put(PropertyKeyConst.SERVER_ADDR, NACOS_HOST + ":" + NACOS_PORT);
                props.put(PropertyKeyConst.NAMESPACE, NAMESPACE);
                // ⚠️ 必须持有引用 ⇒ SDK 的心跳线程才不会被回收（否则实例照样会被摘掉）
                mirrorNamingService = NacosFactory.createNamingService(props);
            }
            Instance instance = new Instance();
            instance.setIp(PEER_GRPC_HOST);
            instance.setPort(PEER_GRPC_PORT);
            instance.setEphemeral(true);
            instance.setHealthy(true);
            instance.setEnabled(true);
            instance.setWeight(1.0);
            instance.setServiceName(PEER_SERVICE_NAME);
            instance.setClusterName("DEFAULT");
            mirrorNamingService.registerInstance(PEER_SERVICE_NAME, group, instance);
            return true;
        } catch (NacosException e) {
            throw new IllegalStateException("发布镜像实例到 Nacos 失败：group=" + group, e);
        }
    }

    /**
     * 把镜像实例从隔离组<b>撤销</b>（并关掉 SDK 客户端）。
     *
     * @param group 目标组
     * @return 是否撤销成功
     */
    static synchronized boolean revokePeerMirror(String group) {
        if (mirrorNamingService == null) {
            return true;
        }
        try {
            mirrorNamingService.deregisterInstance(
                    PEER_SERVICE_NAME, group, PEER_GRPC_HOST, PEER_GRPC_PORT);
            return true;
        } catch (NacosException e) {
            throw new IllegalStateException("撤销镜像实例失败：group=" + group, e);
        } finally {
            try {
                mirrorNamingService.shutDown();
            } catch (NacosException e) {
                // 关不掉也不影响测试结论（实例 15s 后自动过期）⇒ 只记不抛
                System.err.println("[U2] 关闭 Nacos 客户端失败（实例会自动过期）：" + e.getMessage());
            }
            mirrorNamingService = null;
        }
    }

    /**
     * 读 Nacos 注册表里某个服务在某组下的实例列表。
     *
     * @param serviceName 服务名
     * @param group       组
     * @return 实例列表（每个元素含 ip / port / healthy / ephemeral 等）
     */
    static List<Map<String, Object>> instances(String serviceName, String group) {
        String query = "serviceName=" + enc(serviceName)
                + "&groupName=" + enc(group)
                + "&namespaceId=" + enc(NAMESPACE);
        HttpResponse<String> resp = get(NACOS_BASE + "/nacos/v1/ns/instance/list?" + query);
        if (resp.statusCode() != 200 || resp.body() == null) {
            return List.of();
        }
        try {
            JsonNode hosts = MAPPER.readTree(resp.body()).path("hosts");
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode host : hosts) {
                Map<String, Object> one = new LinkedHashMap<>();
                host.fields().forEachRemaining(e -> one.put(e.getKey(), toPlain(e.getValue())));
                out.add(one);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("解析 Nacos 实例列表失败：" + resp.body(), e);
        }
    }

    // ==================================================================
    // ② 对端自己的读数
    // ==================================================================

    /**
     * 读对端自己的 Prometheus 计数。
     *
     * <p>⚠️ Micrometer 的 counter 是<b>懒创建</b>的 ⇒ 指标行<b>不存在</b>就等于「一次都没发生过」
     * ⇒ 这里返回 {@code 0.0}（而不是当成读数失败）。
     *
     * @param metricName    指标名（如 {@code message_rpc_request_total}）
     * @param labelFragment 标签片段（如 {@code method="SendNotification"}）
     * @return 计数（不存在 ⇒ 0.0；读不到端点 ⇒ -1.0）
     */
    static double peerCounter(String metricName, String labelFragment) {
        HttpResponse<String> resp =
                get("http://127.0.0.1:" + PEER_MANAGEMENT_PORT + "/actuator/prometheus");
        if (resp.statusCode() != 200 || resp.body() == null) {
            return -1.0;
        }
        for (String line : resp.body().split("\n")) {
            if (line.isBlank() || line.startsWith("#") || !line.startsWith(metricName)) {
                continue;
            }
            if (labelFragment != null && !line.contains(labelFragment)) {
                continue;
            }
            int idx = line.lastIndexOf(' ');
            if (idx < 0) {
                continue;
            }
            try {
                return Double.parseDouble(line.substring(idx + 1).trim());
            } catch (NumberFormatException ignored) {
                // 不是样本行（理论上不会发生）⇒ 继续找下一行
            }
        }
        return 0.0;
    }

    /**
     * 读对端库里某个收件人的通知行（<b>对端自己写的行</b>）。
     *
     * @param recipientId 收件人 uid
     * @return 通知行（按 id 升序）
     */
    static List<Map<String, Object>> peerNotificationRows(long recipientId) {
        String sql = "SELECT id, recipient_id, sender_id, sender_role, kind, title, content, is_read,"
                + " extra->>'article_id' AS extra_article_id,"
                + " extra->>'author_id'  AS extra_author_id,"
                + " created_at"
                + " FROM " + PEER_NOTIFICATION_TABLE
                + " WHERE recipient_id = ?"
                + " ORDER BY id";
        try (Connection conn = DriverManager.getConnection(PEER_DB_URL, PEER_DB_USER, PEER_DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, recipientId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读对端库失败（" + PEER_DB_URL + "）", e);
        }
    }

    /**
     * 删掉<b>某个收件人</b>在对端库里的通知行（只删自己那个 uid 段）。
     *
     * <p>⚠️ <b>为什么需要它</b>：本测试要断言「对端库里<b>原本一行都没有</b>，调用之后<b>多出来的那一行</b>
     * 就是本次调用造成的」⇒ 上一轮跑完留下的行会让这条前置断言失败 ⇒ 测试<b>不可重复</b>。
     * <p>⚠️ <b>只删自己的 uid</b>（905 号段）⇒ 绝不碰对端库里的其它数据。
     *
     * @param recipientId 收件人 uid
     * @return 删掉的行数
     */
    static int deletePeerNotifications(long recipientId) {
        String sql = "DELETE FROM " + PEER_NOTIFICATION_TABLE + " WHERE recipient_id = ?";
        try (Connection conn = DriverManager.getConnection(PEER_DB_URL, PEER_DB_USER, PEER_DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, recipientId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理对端库失败（" + PEER_DB_URL + "）", e);
        }
    }

    // ==================================================================
    // ③ 轮询等待
    // ==================================================================

    /**
     * 轮询等待某个条件成立。
     *
     * <p>⚠️ <b>为什么必须轮询注册表</b>：「端口已监听」≠「服务已注册」
     * ⇒ 容器起来到实例出现在注册表之间有<b>竞态窗口</b> ⇒ 必须等。
     *
     * @param condition 条件
     * @param timeout   超时
     * @param what      条件描述（失败时进报错文案）
     */
    static void awaitTrue(BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断：" + what, e);
            }
        }
        fail("等待超时（" + timeout + "）：" + what);
    }

    // ==================================================================
    // ④ gRPC 全局注册表（同一 JVM 里跑两个 Spring 上下文时的必要动作）
    // ==================================================================

    /**
     * 🔴 把 devh 注册到 <b>gRPC 全局</b> {@code NameResolverRegistry} 里的
     * <b>服务发现 provider 全部注销掉</b>。
     *
     * <h2>为什么必须做这件事（实测根因 · 全部带出处）</h2>
     * <p><b>现象</b>：两个测试类在<b>同一个 JVM</b> 里各起一个 Spring 上下文、都用
     * {@code discovery:///message-rpc} ⇒ <b>后</b>起的那个上下文的 channel 一直
     * {@code err=UNAVAILABLE: No servers found for message-rpc}（实测 2 分钟不恢复），
     * 而它<b>自己的</b> {@code DiscoveryClient} 明明看得见实例 ⇒ 单独跑就绿。
     *
     * <h3>根因链（逐条源码级）</h3>
     * <ol>
     *   <li>devh 把服务发现 provider 注册到 <b>gRPC 全局单例</b>：
     *       {@code net/devh/boot/grpc/client/autoconfigure/GrpcClientAutoConfiguration.java:124}
     *       ⇒ {@code nameResolverRegistration.register(NameResolverRegistry.getDefaultRegistry())}；</li>
     *   <li>{@code io.grpc.NameResolverRegistry}（grpc-api <b>1.63.0</b>）里
     *       {@code allProviders} 是 {@code LinkedHashSet<NameResolverProvider>}（<b>按实例</b>去重、
     *       <b>保持插入顺序</b>）⇒ <b>两个上下文的 provider 都能注册成功</b>；</li>
     *   <li>{@code refreshProviders()} 对<b>同一个 scheme</b> 只保留<b>一个</b>生效 provider，
     *       规则是「<b>priority 相同则先注册者胜出</b>」（字节码：{@code if_icmpge} 跳过相等者）
     *       ⇒ ��机里<b>第一个</b>创建的上下文的 provider 全局生效；</li>
     *   <li>⇒ <b>第二个</b>上下文的 {@code discovery:///message-rpc} channel 拿到的是
     *       <b>第一个上下文的 DiscoveryClient</b>（<b>另一个 group</b>）
     *       ⇒ 它去问一个<b>空的</b>组 ⇒ 永远解析不到实例。</li>
     * </ol>
     * <p>⇒ 这正好解释了「单跑绿、同跑红」和「应用自己的 DiscoveryClient 看得见、但解析器看不见」。
     *
     * <h3>为什么这样修（而不是改生产代码 / 不是测试放宽）</h3>
     * <ul>
     *   <li>被测的<b>配置一个字没改</b>：两个测试类都仍用生产 yml 的
     *       {@code discovery:///message-rpc} ＋ 生产地址
     *       ⇒ 修的是「测试进程里的框架全局状态」，<b>不是</b>被测行为；</li>
     *   <li>只在<b>自己上下文的 channel 创建之前</b>（{@code @DynamicPropertySource}
     *       ⇒ 早于 context refresh）注销 ⇒ <b>本上下文</b>随后注册的 provider 成为唯一生效者
     *       ⇒ 解析器问到的就是<b>本上下文的</b> DiscoveryClient；</li>
     *   <li>已经建好的 channel <b>不受影响</b>：它持有的是 provider 创建出来的
     *       {@code NameResolver} <b>实例</b>，与全局注册表再无关系；</li>
     *   <li>devh 自己的 javadoc 也承认这点：
     *       {@code GrpcClientAutoConfiguration.java:114}
     *       「This is mostly required for tests/when running multiple application contexts within the same JVM」。</li>
     * </ul>
     *
     * <h3>影响面（已核对）</h3>
     * <p>本仓 article 模块里<b>只有</b>本测试包的两个类用 {@code discovery://}；
     * 其余测试类（{@code support/ArticleRealPgTestBase} · {@code AssemblyTestBase} ·
     * {@code HttpRealTomcatTestBase} · {@code KafkaTestBase} ·
     * {@code ArticlePublicListSnapshotRealPgTest} · {@code ArticleSchemaGateFailFastTest}）
     * 都把 {@code grpc.client.message-rpc.address} 覆盖成 {@code static://127.0.0.1:1}
     * ⇒ <b>不受影响</b>（gRPC 自带的 DNS provider 也不动）。
     */
    static void unregisterGlobalDiscoveryResolverProviders() {
        NameResolverRegistry registry = NameResolverRegistry.getDefaultRegistry();
        List<NameResolverProvider> toRemove = new ArrayList<>();

        // 反射读 allProviders（private final LinkedHashSet）⇒ 拿到「全部」provider，
        // 而不是只有「当前生效的那一个」（后者被注销后，另一个会立刻顶上来，仍然是别人的上下文）。
        Field field = ReflectionUtils.findField(NameResolverRegistry.class, "allProviders");
        if (field != null) {
            ReflectionUtils.makeAccessible(field);
            Object providers = ReflectionUtils.getField(field, registry);
            if (providers instanceof Collection<?> collection) {
                for (Object provider : collection) {
                    if (provider instanceof DiscoveryClientResolverFactory) {
                        toRemove.add((NameResolverProvider) provider);
                    }
                }
            }
        }
        // 兜底：字段名变了就退回「注销当前生效的那个」。
        if (toRemove.isEmpty()) {
            NameResolverProvider effective = registry.getProviderForScheme(
                    DiscoveryClientResolverFactory.DISCOVERY_SCHEME);
            if (effective instanceof DiscoveryClientResolverFactory) {
                toRemove.add(effective);
            }
        }
        for (NameResolverProvider provider : toRemove) {
            registry.deregister(provider);
        }
        System.out.println("[U2] 已从 gRPC 全局 NameResolverRegistry 注销 discovery provider × "
                + toRemove.size() + "（让本上下文的 provider 成为唯一生效者）");
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static Object toPlain(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(java.net.URI.create(url)).GET()
                .timeout(Duration.ofSeconds(10)).build());
    }

    /**
     * 发一次 HTTP 请求。
     *
     * <p>⚠️ 网络不可达 / 超时在这里<b>直接抛</b>（不静默吞成空结果）——
     * 「连不上 Nacos」与「Nacos 说没有实例」是<b>两件事</b>，混起来就成了假绿。
     */
    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP 请求失败：" + request.uri() + " ⇒ " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 请求被中断：" + request.uri(), e);
        }
    }
}
