package com.rag.article.grpc;

import com.rag.article.ArticleServiceApplication;
import com.rag.article.client.MessageRpcClient;
import com.rag.article.support.TestPostgres;
import com.rag.proto.message.MessageServiceGrpc;
import com.rag.proto.message.UnreadSummaryReq;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>🔴 U-2 · 跨域 gRPC 链路（article → message-rpc）—— 真 Nacos + 真对端，实测闭合</b>。
 *
 * <h2>这个缺口是什么（台账出处）</h2>
 * <p>{@code docs/DRIFT/article/verification-article-service-2026-11-03.md} §11.6.5 <b>U-2</b>：
 * <blockquote>
 * 「<b>跨域 gRPC 链路</b>（article → message-rpc）—— 承接行只管「有没有实现」，不管「对端在不在」。
 * 怎么才能闭合：<b>真 Nacos + 真 message-rpc</b>。」
 * </blockquote>
 * ⇒ 本类就是那个「怎么才能闭合」的答案：<b>真 Nacos</b>（不是 {@code support/NacosStubConfiguration}
 * 那个空操作 mock）＋ <b>真 message-rpc 容器</b>（{@code sea/message-rpc:latest}，
 * 宿主 8086 = 容器 8086 = 对端的 {@code MESSAGE_RPC_PORT}）。
 *
 * <h2>「真触发的是什么」</h2>
 * <p>本类启动的<b>是生产入口</b> {@link ArticleServiceApplication}（全量 main 代码，一个字没改），
 * 被测组件是<b>生产类</b> {@link MessageRpcClient}。
 * <p>一次调用真正穿过的链路：
 * <pre>
 *   MessageRpcClient#sendArticlePublished            (生产类 · main 代码)
 *     └─ stub.sendNotification(req)                  (BlockingStub)
 *          └─ devh NameResolver: discovery:///message-rpc
 *               └─ Spring Cloud DiscoveryClient.getInstances("message-rpc")
 *                    └─ NacosServiceDiscovery#getInstances         (真 Nacos · 真 HTTP)
 *                         └─ nacos-client selectInstances(service, group=隔离组, namespace=public)
 *                              └─ 返回 127.0.0.1:8086              (对端的 Docker 发布端口)
 *                                   └─ 真 gRPC over HTTP/2 → 对端容器里的 gRPC server
 *                                        └─ MessageRpcServiceImpl#sendNotification   (对端进程)
 *                                             └─ MessageDomainService#doSendNotification
 *                                                  └─ insertNotificationsTx → 对端自己的 PG 落库
 * </pre>
 * ⇒ <b>断言不看返回值，只看对端自己的库 + 对端自己的 Prometheus 计数。</b>
 *
 * <h2>🔴 为什么「返回 true」不算证据</h2>
 * <p>{@code MessageRpcClient#sendArticlePublished} 的实现是
 * <b>吞掉所有 {@code StatusRuntimeException} 并返回 false</b>（B 侧同款：Go 侧只记日志不 panic）。
 * ⇒ 反过来，<b>返回 true 只说明「gRPC 没抛异常」</b>，
 * <b>不说明对端真的处理了这条消息</b>（对端完全可能在业务层吞掉）。
 * ⇒ 本类因此把证据锚在<b>对端自己写的库行</b>上（对端进程的 INSERT，不是测试写的）。
 *
 * <h2>B 出处（判据 ↔ B 侧出处 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>出向调用的唯一入口 ＋ 请求字段</td>
 *       <td>{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/MessageRpcClient.java:64}
 *           （方法）· {@code :73}（req 构造）</td></tr>
 *   <tr><td>失败语义（吞异常 ＋ 返 false）</td>
 *       <td>同上 {@code :96}（catch StatusRuntimeException）</td></tr>
 *   <tr><td>对端注册名 = {@code message-rpc}（跨域寻址契约）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/bootstrap/NacosRegistration.java:84}
 *           · {@code :141}（registerInstance）</td></tr>
 *   <tr><td>对端收到之后<b>落库</b>（= 本测试的「下游证据」）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageDomainService.java:459}
 *           （构造通知行）· {@code :467}（{@code insertNotificationsTx}）</td></tr>
 *   <tr><td>对端<b>自己的</b> RPC 计数（= 本测试的「下游可观测读数」）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/metrics/MessageMetrics.java:45}
 *           （指标名）· {@code :77}（observeRpc）· {@code :144}（observeNotification）</td></tr>
 * </table>
 *
 * <h2>怎么才能证伪（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code grpc.client.message-rpc.address} 从 {@code discovery:///message-rpc}
 *       改成写死的地址 ⇒ 本类<b>照样绿</b>（所以本类额外用
 *       {@link MessageRpcAbsentPeerCrossDomainTest} 反例证明「服务发现真的在链路上」）；</li>
 *   <li>把对端容器的 8086 停掉 ⇒ 对端不落库 ⇒ 用例 ① 的<b>落库断言</b>立刻红；</li>
 *   <li>把 Nacos 地址改错（{@code 127.0.0.1:1}）⇒ 上下文起不来（{@code fail-fast}）⇒ 整类红；</li>
 *   <li>把对端的 {@code insertNotificationsTx} 改成 no-op ⇒ 对端库没有行 ⇒ 用例 ① 立刻红
 *       （<b>这正是本类要抓的「假绿」</b>：返回值仍是 true）；</li>
 *   <li>把 {@code MessageRpcClient} 的 {@code extra} 键名改掉 ⇒ 用例 ① 的
 *       {@code extra_article_id / extra_author_id} 断言立刻红。</li>
 * </ul>
 *
 * <h2>⚠️ 数据隔离纪律</h2>
 * <p>本类用 <b>905 号 uid 段</b>（本仓约定「9xx_000_000 + 序号」，每个测试类一个号段；
 * 已被占用的段：901/902/903/904）。
 * <p>⚠️ 本类<b>每轮开头</b>会删掉<b>自己那两个 uid</b>（905000001 / 905000002）在对端库里的残留
 * ⇒ 这样「基线必须为空」这条前置断言<b>可重复</b>成立（本轮落下的行就是本轮造出来的证据）。
 * ⚠️ <b>只删自己的 uid</b> ⇒ 绝不碰对端库里的其它数据。
 *
 * <h2>⚠️ 为什么这个类<b>不</b>复用 {@code support/ArticleRealPgTestBase}</h2>
 * <p>那个基类把 Nacos 指向 {@code 127.0.0.1:1}、把 gRPC client 指向 {@code static://127.0.0.1:1}、
 * 把 gRPC server 端口设成 {@code 0} —— <b>那正是「假绿」的配方</b>。
 * ⇒ ⇒ 本类<b>另起一套属性</b>（不改动 {@code support/} 下任何文件 —— 那是别人的写权）。
 *
 * <h2>⚠️ 为什么打上 {@code @Tag("external-env")}</h2>
 * <p>本类**不是自包含**的：它要连<b>宿主上已经在跑的</b>三件外部设施 ——
 * 真 Nacos（{@code 127.0.0.1:49848} · 容器 {@code Sea-nacos}）、
 * 真对端 message-rpc（{@code 127.0.0.1:8086} · 容器 {@code Sea-message-rpc}）、
 * 真对端 PG（{@code 127.0.0.1:49432} · 容器 {@code Sea-postgres}）。
 * <p>⇒ 三个后果：
 * <ol>
 *   <li><b>没有这套容器的机器上，本类必然红</b>（不是「跳过」）
 *       ⇒ 按 AGENTS.md §五.8 规则 ③ <b>不许</b>用 {@code @EnabledIfDockerAvailable} 把它变绿
 *       ⇒ 只能按规则 ④ 用<b>类别</b>把它从默认回归里排除；</li>
 *   <li><b>它会写真实数据</b>：对端 {@code sea_user.message_notification} 会多 3 行
 *       （905 号段，被测代码自己写的证据行）⇒ 不该由别人的回归顺手触发；</li>
 *   <li><b>它要独占 Nacos 注册表</b> ⇒ 与其它域的并行回归会互相干扰。</li>
 * </ol>
 * ⇒ ⇒ 故打类级 {@code @Tag("external-env")} ＋ 在<b>本域自己的</b> {@code service/article/pom.xml}
 * 里配 {@code <excludedGroups>}</code>（默认排除 · 属性 {@code article.test.excludedGroups}）。
 * ⇒ ⇒ <b>显式跑法</b>（见台账 §11.6.4.5）：
 * <pre>{@code mvn -o -pl service/article -am test -Pexternal-env}</pre>
 * ⇒ 读数（2026-11-03 实测，两遍连跑均绿）：<b>{@code Tests run: 3, Failures: 0, Errors: 0, Skipped: 0}</b>
 * ⇒ ⚠️ <b>默认回归口径</b>：{@code mvn clean verify} 的汇总行<b>不含</b>本类的 3 例
 * ⇒ 台账必须把这条口径写明，否则「跑绿」会被读成「跨域链路也验过了」。
 * ⇒ ⚠️ <b>不要用 {@code -Dgroups=external-env}</b>：surefire 的 {@code groups} 与
 * {@code excludedGroups} 是**两个独立过滤器**（命中 = include ∩ exclude）
 * ⇒ 排除项写死时该命令跑出 {@code Tests run: 0}（2026-11-03 实测）⇒ 只能用 profile。
 */
@Tag("external-env")
@SpringBootTest(
        classes = ArticleServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // ---------- 真 Nacos（不是 support/NacosStubConfiguration 那个空操作 mock）----------
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:49848",
                "spring.cloud.nacos.discovery.namespace=public",
                // ⚠️ 隔离组（不是共享的 DEFAULT_GROUP）—— 见 CrossDomainLinkSupport 的分组隔离说明
                "spring.cloud.nacos.discovery.group=U2_ARTICLE_LINK",
                // ---------- gRPC server 固定端口 ----------
                // ⚠️ 必须是固定端口：生产注册到 Nacos 的是 gRPC 端口（不是 web 端口），
                //    端口若为 0 ⇒ 注册进去的是随机临时端口 ⇒ 对端拨不到。
                "grpc.server.port=19011",
                // ---------- Kafka 桩 ----------
                // ⚠️ ArticleKafkaChannelValidator 在 @PostConstruct 比对「四个通道的 brokers」与
                //    「全局 bootstrap-servers」，不一致就启动失败 ⇒ 只改全局会直接起不来。
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // ⚠️ 必须给 producer 封顶：broker 不可达时 KafkaTemplate.send() 会阻塞到
                //    max.block.ms（默认 60s）⇒ outbox relay 的定时任务会挂住。
                "spring.kafka.producer.properties.max.block.ms=2000",
                "article-rpc.kq-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.kq-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-result-consumer-conf.brokers=127.0.0.1:1",
                // ---------- MinIO 快速失败（容器未起，B 侧语义是「失败只 warn 不 panic」）----------
                "article-rpc.minio.endpoint=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false"
        })
class ArticleToMessageRpcCrossDomainTest {

    /** 用例 ① 的收件人 uid（905 号段）。 */
    private static final long UID_ONE = 905000001L;

    /** 用例 ② 的收件人 uid（905 号段，与 ① 不重叠）。 */
    private static final long UID_TWO = 905000002L;

    /** 通道就绪探针用的 uid（只读方法，不落库；905 号段）。 */
    private static final long PROBE_UID = 905000009L;

    @Autowired
    private MessageRpcClient messageRpcClient;

    /**
     * 应用<b>自己</b>的 {@code DiscoveryClient} —— devh 的解析器就是问它要地址的
     * （{@code DiscoveryClientNameResolver#discoverServers} → {@code client.getInstances(name)}）。
     */
    @Autowired
    private org.springframework.cloud.client.discovery.DiscoveryClient discoveryClient;

    /**
     * 生产用的那个 gRPC stub —— 从 {@link MessageRpcClient} 的私有字段里反射读出来。
     *
     * <p>⚠️ <b>为什么反射</b>：devh 的 {@code GrpcClientBeanPostProcessor} 把 stub 注册成
     * <b>带限定符</b>的 bean（{@code @Qualifier("grpcClient.message-rpc.…")}）
     * ⇒ 按类型 {@code @Autowired} 注入<b>匹配不到</b>（实测：
     * {@code No qualifying bean of type 'MessageServiceGrpc$MessageServiceBlockingStub' available}）。
     * <p>⇒ 反射读的是<b>生产那个 bean 自己持有的 stub</b>（{@code MessageRpcClient#stub}）
     * ⇒ 断言的仍然是<b>生产通道</b>，不是另建一条。
     * <p>（同款反射读法在本仓已有先例：{@code assembly/ArticleGrpcChainAssemblyTest} 反射读
     * {@code GrpcServerLifecycle#server}。）
     */
    private MessageServiceGrpc.MessageServiceBlockingStub productionStub() {
        Field stubField = ReflectionUtils.findField(MessageRpcClient.class, "stub");
        assertNotNull(stubField, "🔴 MessageRpcClient 必须持有 stub 字段（生产代码被改坏了？）");
        ReflectionUtils.makeAccessible(stubField);
        MessageServiceGrpc.MessageServiceBlockingStub stub =
                (MessageServiceGrpc.MessageServiceBlockingStub) ReflectionUtils.getField(stubField, messageRpcClient);
        assertNotNull(stub, "🔴 反射读到的 stub 不能为 null");
        return stub;
    }

    /**
     * 注入<b>必须</b>由外部提供的属性。
     *
     * <p>⚠️ 裸占位符（{@code application.yml:35} / {@code :221} / {@code :305}）缺了就起不来。
     * <p>⚠️ <b>这里刻意不覆盖 {@code grpc.client.message-rpc.address}</b> ——
     * 让它用<b>生产 yml 里的原值</b> {@code discovery:///message-rpc}
     * ＋ {@code negotiation-type: plaintext} ⇒ <b>被测的那段配置本身就是生产值</b>。
     * <p>🔴 <b>第一句是「同一个 JVM 里跑两个上下文」必须做的动作</b>：
     * 把 gRPC <b>全局</b>注册表里<b>别的上下文</b>注册的服务发现 provider 注销掉
     * ⇒ 本上下文的 provider 成为唯一生效者（否则本类的 channel 会去问
     * <b>另一个上下文的 DiscoveryClient</b>（另一个 group）⇒ 永远
     * {@code No servers found for message-rpc}）。
     * ⇒ 根因链与源码出处见
     * {@link CrossDomainLinkSupport#unregisterGlobalDiscoveryResolverProviders()}。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 🔴 必须在**本上下文的 channel 创建之前**（@DynamicPropertySource 早于 context refresh）
        CrossDomainLinkSupport.unregisterGlobalDiscoveryResolverProviders();
        TestPostgres.register(registry);
        registry.add("POSTGRES_PASSWORD", () -> "password");
        registry.add("USER_JWT_SECRET", () -> "test-only-article-jwt-secret-at-least-32-bytes-long");
        // 安全网：@BeforeAll 已经发布过；这里再调一次是幂等的（已在就什么都不做）。
        // 之所以要有这道：@DynamicPropertySource 在**上下文 refresh 之前**被调用，
        // ⇒ 万一 @BeforeAll 因为别的原因没跑到，镜像实例也一定在上下文建 channel 之前就位。
        CrossDomainLinkSupport.publishPeerMirror(CrossDomainLinkSupport.LINK_GROUP);
    }

    /**
     * 前置：<b>对端真的在 Nacos 里</b>（这正是 U-2 说的「对端在不在」），并把对端发布进隔离组。
     */
    @BeforeAll
    static void peerMustBePresentInNacos() {
        // ① 对端自己注册的那条（共享组，本测试只读、不改）
        List<Map<String, Object>> peerOwn = CrossDomainLinkSupport
                .instances(CrossDomainLinkSupport.PEER_SERVICE_NAME, CrossDomainLinkSupport.PEER_OWN_GROUP);
        assertFalse(peerOwn.isEmpty(),
                "🔴 前置失败：真 message-rpc 必须已经注册在 Nacos 的 "
                        + CrossDomainLinkSupport.PEER_OWN_GROUP + " 组里"
                        + "（对端不在 ⇒ 这条链路本来就跑不通，不是代码问题）");
        assertTrue(peerOwn.stream().anyMatch(h -> Boolean.TRUE.equals(h.get("healthy"))),
                "🔴 前置失败：真 message-rpc 的实例必须是 healthy；实际 = " + peerOwn);

        // ② ⚠️ **镜像实例的发布只做一次**（在 @DynamicPropertySource 里）⇒ 这里**不再**发布。
        //    实测：同一个 SDK 客户端对同一实例**二次 registerInstance** 之后，
        //    应用侧的 DiscoveryClient 能看到实例、但 devh 解析器一直拿不到（详见报告的「踩坑」一节）。

        // ③ 清掉**自己那两个 uid** 在对端库里的上一轮残留 ⇒ 保证「基线为空」这条前置可重复成立
        //    ⚠️ 只删自己的 uid（905000001 / 905000002）⇒ 绝不碰对端库里的其它数据。
        CrossDomainLinkSupport.deletePeerNotifications(UID_ONE);
        CrossDomainLinkSupport.deletePeerNotifications(UID_TWO);
    }

    /**
     * 收尾：把镜像实例从隔离组撤销（<b>共享组一个字都没动过</b>）。
     */
    @AfterAll
    static void revokeMirror() {
        CrossDomainLinkSupport.revokePeerMirror(CrossDomainLinkSupport.LINK_GROUP);
    }

    // ==================================================================
    // ① 核心：对端真的收到了（落库行 ＋ 对端自己的计数）
    // ==================================================================

    @Test
    @Timeout(120)
    @DisplayName("🔴 U-2 跨域链路：article 真的把通知发给了真 message-rpc，"
            + "**对端自己的库里出现了这一行**，且对端自己的 RPC 计数 +1")
    void peerReallyReceivesTheNotificationAndPersistsIt() {
        // ---- 前置：基线必须为空（否则「多出来的一行」可能不是本次调用造成的）----
        assertEquals(0, CrossDomainLinkSupport.peerNotificationRows(UID_ONE).size(),
                "🔴 前置失败：收件人 uid=" + UID_ONE + " 在对端库里必须一行都没有");
        double counterBefore =
                CrossDomainLinkSupport.peerCounter("message_rpc_request_total", "method=\"SendNotification\"");

        // ---- 前置：🔴 等**应用自己的**服务发现真的看得到对端 ----
        // ⚠️ 这是实测踩到的启动竞态：devh 的解析器在 channel 创建那一刻就 resolve 一次，
        //    那时 Nacos 客户端可能还没连上 / 实例还没进本地缓存 ⇒ 报 "No servers found" ⇒
        //    第一次调用必然失败。⇒ 必须等「应用自己的 DiscoveryClient 看得见」再发调用。
        awaitDiscoverySeesPeer();
        awaitChannelReady();

        // ---- 动作：走**生产类** MessageRpcClient ----
        String authorId = "author-905000001";
        String articleId = "article-905000001";
        String title = "跨域链路实测标题";
        boolean returned = messageRpcClient.sendArticlePublished(UID_ONE, authorId, articleId, title);

        // ---- 断言 ①：RPC 层没抛异常（**必要但不充分**）----
        assertTrue(returned,
                "🔴 生产客户端返回 false ⇒ gRPC 调用失败（详见日志里 send article published notification failed）");

        // ---- 断言 ②：🔴 **真正的证据** —— 对端自己的库里有这一行 ----
        List<Map<String, Object>> rows = awaitPeerRow(UID_ONE);
        Map<String, Object> row = rows.get(0);

        assertEquals(UID_ONE, num(row, "recipient_id"),
                "对端落库的收件人必须是被通知的人");
        assertEquals(0L, num(row, "sender_id"),
                "系统消息：sender_id 必须是 0（B 侧 MessageRpcClient:108「Go: SenderId: 0」）");
        assertEquals(3, num(row, "sender_role"),
                "SenderRole.SYSTEM = 3（B 侧 MessageRpcClient:110）");
        assertEquals(5, num(row, "kind"),
                "NotificationKind.ARTICLE_PUBLISHED = 5（B 侧 MessageRpcClient:113）");
        assertEquals("文章发布成功", row.get("title"),
                "通知标题必须逐字一致（B 侧 MessageRpcClient:116）");
        assertEquals("《" + title + "》已经完成审核并成功入库。", row.get("content"),
                "通知正文必须逐字一致（B 侧 MessageRpcClient:99-101 的 TrimSpace 分支）");
        assertEquals(articleId, row.get("extra_article_id"),
                "extra.article_id 必须原样送达（B 侧 MessageRpcClient:119）");
        assertEquals(authorId, row.get("extra_author_id"),
                "🔴 extra.author_id 必须是**原串**（不是 String.valueOf(recipientId)）"
                        + "（B 侧 MessageRpcClient:120-121 的显式要求）");
        assertEquals(Boolean.FALSE, row.get("is_read"),
                "新落的通知必须是未读");

        // ---- 断言 ③：对端**自己的**可观测读数也变了（下游自己的计数器）----
        double counterAfter =
                CrossDomainLinkSupport.peerCounter("message_rpc_request_total", "method=\"SendNotification\"");
        assertTrue(counterAfter > counterBefore,
                "🔴 对端自己的 message_rpc_request_total{method=\"SendNotification\"} 必须 +1；"
                        + "before=" + counterBefore + " after=" + counterAfter);
        double kindCounter = CrossDomainLinkSupport
                .peerCounter("message_rpc_notification_action_total", "kind=\"article_published\"");
        assertTrue(kindCounter >= 1.0,
                "🔴 对端自己的 message_rpc_notification_action_total{kind=\"article_published\"} 必须 ≥ 1；实际 = "
                        + kindCounter);
    }

    // ==================================================================
    // ② 链路是活的：连续两次 ⇒ 对端收到两条**互不相同**的通知
    // ==================================================================

    @Test
    @Timeout(120)
    @DisplayName("🔴 U-2 跨域链路：连续两次调用 ⇒ 对端收到**两条互不相同**的通知"
            + "（证明不是缓存 / 重放同一个响应）")
    void twoConsecutiveCallsProduceTwoDistinctNotificationsOnThePeer() {
        assertEquals(0, CrossDomainLinkSupport.peerNotificationRows(UID_TWO).size(),
                "🔴 前置失败：收件人 uid=" + UID_TWO + " 在对端库里必须一行都没有");
        awaitDiscoverySeesPeer();
        awaitChannelReady();

        String articleA = "article-905000002-a";
        String articleB = "article-905000002-b";
        assertTrue(messageRpcClient.sendArticlePublished(UID_TWO, "author-905000002", articleA, "标题 A"));
        assertTrue(messageRpcClient.sendArticlePublished(UID_TWO, "author-905000002", articleB, "标题 B"));

        List<Map<String, Object>> rows = awaitPeerRows(UID_TWO, 2);
        assertEquals(2, rows.size(),
                "🔴 对端必须收到两条通知（两次调用 ⇒ 两行）；实际 = " + rows.size());
        List<Object> articleIds = rows.stream().map(r -> r.get("extra_article_id")).toList();
        assertTrue(articleIds.contains(articleA) && articleIds.contains(articleB),
                "🔴 两条通知必须携带各自不同的 article_id；实际 = " + articleIds);
        assertEquals("《标题 A》已经完成审核并成功入库。", rows.get(0).get("content"),
                "第一条的正文必须逐字一致");
        assertEquals("《标题 B》已经完成审核并成功入库。", rows.get(1).get("content"),
                "第二条的正文必须逐字一致");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 等<b>应用自己的</b> {@code DiscoveryClient} 看得见 {@code message-rpc} 的实例。
     *
     * <p>⚠️ <b>为什么必须等</b>（实测踩到的启动竞态）：
     * devh 的 {@code DiscoveryClientNameResolver} 在 channel 创建那一刻就 resolve 一次
     * （{@code start()} → {@code resolve()}）；那一刻 Nacos 客户端可能还没建连、
     * 实例也可能还没进本地缓存 ⇒ 它会 {@code onError(UNAVAILABLE "No servers found")}
     * ⇒ <b>第一次调用必然失败</b>（日志实测：{@code err=UNAVAILABLE: No servers found for message-rpc}）。
     * <p>⇒ ⇒ 必须等「应用自己的 DiscoveryClient 看得见实例」再发调用。
     * <p>⚠️ 注意：<b>注册表里有</b> ≠ <b>应用看得见</b> ⇒ 这里问的是应用自己的那个
     * {@code DiscoveryClient}（正是 devh 解析器问的那一个）。
     */
    private void awaitDiscoverySeesPeer() {
        CrossDomainLinkSupport.awaitTrue(
                () -> !discoveryClient.getInstances(CrossDomainLinkSupport.PEER_SERVICE_NAME).isEmpty(),
                Duration.ofSeconds(30),
                "应用自己的 DiscoveryClient 能看到 message-rpc 的实例（devh 解析器问的就是它）");
        // 顺带把「服务发现给出的地址」钉住：必须是宿主可达的那条
        assertTrue(discoveryClient.getInstances(CrossDomainLinkSupport.PEER_SERVICE_NAME).stream()
                        .anyMatch(i -> CrossDomainLinkSupport.PEER_GRPC_HOST.equals(i.getHost())
                                && i.getPort() == CrossDomainLinkSupport.PEER_GRPC_PORT),
                "🔴 服务发现给出的地址必须是 " + CrossDomainLinkSupport.PEER_GRPC_HOST
                        + ":" + CrossDomainLinkSupport.PEER_GRPC_PORT + "（对端的 Docker 发布端口）；实际 = "
                        + discoveryClient.getInstances(CrossDomainLinkSupport.PEER_SERVICE_NAME));
    }

    /**
     * 等生产 channel 变成 <b>READY</b>（= 服务发现解析出来的地址<b>真的连上了</b>对端）。
     *
     * <p>⚠️ <b>为什么需要这一步（实测踩到的第二个启动竞态）</b>：
     * <pre>
     *   18:10:54.821  镜像实例 message-rpc 注册进隔离组（成功）
     *   18:10:55.211  Nacos gRPC 命名连接建立
     *   18:10:55.244  devh 解析器**第一次** resolve ⇒ "No servers found for message-rpc"
     * </pre>
     * ⇒ 根因：nacos-client 的<b>首次</b> {@code selectInstances} 走的是<b>本地缓存</b>
     * （此刻还是空的）⇒ 解析器拿到空列表 ⇒ channel 进 TRANSIENT_FAILURE
     * ⇒ 第一次调用必然 {@code UNAVAILABLE}。
     * <p>⇒ 之后 grpc-java 会按指数退避<b>重试名字解析</b>，Nacos 客户端的缓存补上实例后就能连上
     * ⇒ ⇒ 这里等 channel 变 READY 再发调用。
     * <p>⚠️ <b>这不是「把断言放宽」</b>：channel 变 READY 的含义是
     * 「<b>服务发现给出的地址真的建起了 HTTP/2 连接</b>」⇒ 仍然是对端的真进程。
     * <p>⚠️ 生产上这个竞态不存在：真实通知是 Kafka 消费者在<b>运行期</b>发的，
     * 那时 channel 早已 READY。
     */
    private void awaitChannelReady() {
        MessageServiceGrpc.MessageServiceBlockingStub stub = productionStub();
        // 探针用**只读**方法 getUnreadSummary（对端只查库、不写任何东西）
        // ⇒ 「探针成功」= 「生产通道真的通到了对端」，
        //    而「探针抛 UNAVAILABLE」= 「通道还没解析好」（名字解析失败 ⇒ TRANSIENT_FAILURE）。
        CrossDomainLinkSupport.awaitTrue(
                () -> probeTransportReady(stub),
                Duration.ofSeconds(90),
                "生产 gRPC 通道 ready（只读探针 getUnreadSummary 能通到对端）");
    }

    /**
     * 传输层是否已经通到对端。
     *
     * <p>⚠️ 判据：<b>只有传输层错误</b>（UNAVAILABLE / DEADLINE_EXCEEDED）才算「还没通」。
     * <p>⚠️ 对端返回的<b>业务</b>错误（比如参数非法）说明<b>请求已经送到对端</b>了
     * ⇒ 那也算「通了」⇒ 否则会被误判成「一直没通」。
     */
    private static boolean probeTransportReady(MessageServiceGrpc.MessageServiceBlockingStub stub) {
        try {
            stub.getUnreadSummary(UnreadSummaryReq.newBuilder().setUserId(PROBE_UID).build());
            return true;
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            return code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED;
        } catch (RuntimeException e) {
            // ServiceBusyException（熔断打开）等非 status 异常 ⇒ 传输层判定不了 ⇒ 继续等
            return false;
        }
    }

    /** 等对端库里出现**至少 1 行**（对端是同步 INSERT，但网络 + 提交有延迟）。 */
    private static List<Map<String, Object>> awaitPeerRow(long recipientId) {
        AtomicReference<List<Map<String, Object>>> box = new AtomicReference<>(List.of());
        CrossDomainLinkSupport.awaitTrue(
                () -> {
                    box.set(CrossDomainLinkSupport.peerNotificationRows(recipientId));
                    return !box.get().isEmpty();
                },
                Duration.ofSeconds(30),
                "对端库里出现 recipient_id=" + recipientId + " 的通知行");
        return box.get();
    }

    /** 等对端库里出现**至少 n 行**。 */
    private static List<Map<String, Object>> awaitPeerRows(long recipientId, int expected) {
        AtomicReference<List<Map<String, Object>>> box = new AtomicReference<>(List.of());
        CrossDomainLinkSupport.awaitTrue(
                () -> {
                    box.set(CrossDomainLinkSupport.peerNotificationRows(recipientId));
                    return box.get().size() >= expected;
                },
                Duration.ofSeconds(30),
                "对端库里出现 " + expected + " 行 recipient_id=" + recipientId + " 的通知行");
        return box.get();
    }

    private static long num(Map<String, Object> row, String column) {
        Object v = row.get(column);
        assertNotNull(v, "对端落库的行里必须有列 " + column + "；实际列 = " + row.keySet());
        return ((Number) v).longValue();
    }
}
