package com.rag.article.grpc;

import com.rag.article.ArticleServiceApplication;
import com.rag.article.client.MessageRpcClient;
import com.rag.article.support.TestPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>🔴 U-2 的<b>反例</b>：对端<b>不在</b>注册表里时，这条链路必须<b>真的断</b>
 * —— 既不能落库，也不能返回 true。</b>
 *
 * <h2>为什么必须有这个反例（不是「多测一个」而已）</h2>
 * <p>正例（{@link ArticleToMessageRpcCrossDomainTest}）绿了，有两种可能：
 * <ol>
 *   <li><b>链路真的通了</b>（对端收到并落库）；</li>
 *   <li><b>断言本身是空的</b>（比如查错了库、查错了 uid、或者「有行」本来就是恒真的）。</li>
 * </ol>
 * <p>⇒ 本类把<b>唯一的变量</b>换成「对端不在」：
 * <ul>
 *   <li>同一个生产类 {@link MessageRpcClient}；</li>
 *   <li>同一个生产地址 {@code discovery:///message-rpc}；</li>
 *   <li>同一个真 Nacos、同一个 namespace（{@code public}）；</li>
 *   <li><b>只换一个 group</b> —— {@link CrossDomainLinkSupport#ABSENT_GROUP}，
 *       那个组里<b>刻意没有</b> {@code message-rpc} 的实例。</li>
 * </ul>
 * ⇒ ⇒ 如果这条链路是<b>真的</b>在走服务发现，那么对端不在 ⇒ 调用必然失败 ⇒ 必然没有落库行。
 * <p>⇒ 本类绿 ⇒ 反过来证明正例的落库断言<b>不是恒真</b>。
 *
 * <h2>「真触发的是什么」</h2>
 * <p>真启动生产入口 {@link ArticleServiceApplication}（全量 main 代码），
 * 真 Nacos（{@code 127.0.0.1:49848}），真去拨一个<b>注册表里没有</b>的服务名。
 * <p>⇒ 触发的是 devh 的「<b>找不到实例</b>」这条真实路径：
 * {@code DiscoveryClientNameResolver#resolveInternal} ⇒ 实例列表为空 ⇒
 * {@code onError(Status.UNAVAILABLE "No servers found for message-rpc")}
 * ⇒ gRPC 调用失败 ⇒ 生产类按 B 侧语义<b>吞掉异常、返回 false</b>。
 *
 * <h2>B 出处（判据 ↔ B 侧出处 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>失败语义：<b>吞异常 ＋ 返 false</b>，<b>不影响</b>发布结果</td>
 *       <td>{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/MessageRpcClient.java:96}
 *           （catch StatusRuntimeException ⇒ log.warn ＋ return false）</td></tr>
 *   <tr><td>「对端不在 ⇒ 没有任何副作用」（对端侧）</td>
 *       <td>{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageDomainService.java:467}
 *           —— 对端<b>根本没被调到</b> ⇒ 那张表<b>一行都不该多</b></td></tr>
 * </table>
 *
 * <h2>怎么才能证伪（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code MessageRpcClient} 的 catch 去掉（让它把异常抛出去）⇒ 本类的
 *       {@code assertFalse(returned)} 会以「异常」形式红 ⇒ 证明本类<b>真的</b>触到了失败路径；</li>
 *   <li>把 {@code grpc.client.message-rpc.address} 改成一个<b>别的</b>能通的服务名
 *       ⇒ 本类会<b>绿</b>（因为有实例）⇒ 说明本类的前提（隔离组为空）是关键变量；</li>
 *   <li>把「隔离组为空」这个前提去掉（误用共享组）⇒ 对端会被找到 ⇒ 本类立刻红
 *       ⇒ 说明<b>分组隔离</b>这件事是被真的用上了，不是摆设。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么打上 {@code @Tag("external-env")}</h2>
 * <p>与正例同因：本类依赖<b>宿主上已经在跑的</b>真 Nacos（{@code 127.0.0.1:49848}）
 * ＋ 真对端 message-rpc（{@code 127.0.0.1:8086}）＋ 真对端 PG（{@code 127.0.0.1:49432}）
 * ⇒ <b>不是自包含</b> ⇒ 没有这套容器的机器上必然红（§五.8 规则 ③ 不许用
 * {@code @EnabledIfDockerAvailable} 变绿）⇒ 按规则 ④ 用<b>类别</b>从默认回归排除
 * （配置在 {@code service/article/pom.xml} 的 {@code <excludedGroups>}）。
 * ⇒ 显式跑法：{@code mvn -o -pl service/article -am test -Pexternal-env}
 * （<b>不要</b>用 {@code -Dgroups=external-env} —— 实测 {@code Tests run: 0}，原因见正例 javadoc）
 * ⇒ 读数与台账见正例的 javadoc 与台账 §11.6.4.5。
 */
@Tag("external-env")
@SpringBootTest(
        classes = ArticleServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // ---------- 真 Nacos ----------
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:49848",
                "spring.cloud.nacos.discovery.namespace=public",
                // 🔴 与正例**唯一的差别**：这个组里**故意没有** message-rpc 的实例
                "spring.cloud.nacos.discovery.group=U2_ARTICLE_LINK_ABSENT",
                // ---------- gRPC server 固定端口（与正例不同，避免两个上下文抢同一个端口）----------
                "grpc.server.port=19012",
                // ---------- Kafka 桩 ----------
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.producer.properties.max.block.ms=2000",
                "article-rpc.kq-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.kq-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-result-consumer-conf.brokers=127.0.0.1:1",
                // ---------- MinIO 快速失败 ----------
                "article-rpc.minio.endpoint=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false"
        })
class MessageRpcAbsentPeerCrossDomainTest {

    /** 反例专用 uid（905 号段，与正例不重叠）。 */
    private static final long UID_ABSENT = 905000003L;

    @Autowired
    private MessageRpcClient messageRpcClient;

    /**
     * ⚠️ <b>刻意不覆盖 {@code grpc.client.message-rpc.address}</b> ——
     * 用<b>生产 yml 的原值</b> {@code discovery:///message-rpc}。
     * ⇒ 与正例相比，<b>唯一的变量就是 Nacos 那个 group</b>。
     * <p>🔴 <b>第一句是「同一个 JVM 里跑两个上下文」必须做的动作</b>：
     * 把 gRPC <b>全局</b>注册表里<b>别的上下文</b>注册的服务发现 provider 注销掉
     * ⇒ 本上下文（空组）的 provider 成为唯一生效者。
     * ⇒ 否则本类会拿到<b>正例上下文</b>的 DiscoveryClient（那个组里有镜像实例）
     * ⇒ 对端「反而能找到」⇒ 反例的前提（对端不在）当场失效。
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
    }

    /**
     * 前置：这个隔离组里<b>必须没有</b> {@code message-rpc} 的实例。
     */
    @BeforeAll
    static void peerMustBeAbsentFromThisGroup() {
        List<Map<String, Object>> instances = CrossDomainLinkSupport
                .instances(CrossDomainLinkSupport.PEER_SERVICE_NAME, CrossDomainLinkSupport.ABSENT_GROUP);
        assertTrue(instances.isEmpty(),
                "🔴 前置失败：隔离组 " + CrossDomainLinkSupport.ABSENT_GROUP
                        + " 里必须**没有** message-rpc 的实例（否则本类测的就不是「对端不在」）；实际 = " + instances);
        // 清掉自己那个 uid 的历史残留（只删自己的 uid ⇒ 不碰对端库里的其它数据）
        CrossDomainLinkSupport.deletePeerNotifications(UID_ABSENT);
    }

    @Test
    @Timeout(120)
    @DisplayName("🔴 U-2 反例：对端不在注册表 ⇒ **不落库** ＋ 生产客户端返回 false"
            + "（证明正例的落库断言不是恒真）")
    void absentPeerMeansNoRowAndFalseReturn() {
        assertEquals(0, CrossDomainLinkSupport.peerNotificationRows(UID_ABSENT).size(),
                "🔴 前置失败：收件人 uid=" + UID_ABSENT + " 在对端库里必须一行都没有");

        // ---- 动作：同一个生产类、同一个生产地址，只是服务发现里查无此服务 ----
        boolean returned = messageRpcClient.sendArticlePublished(
                UID_ABSENT, "author-905000003", "article-905000003", "对端不在时的标题");

        // ---- 断言 ①：生产客户端必须按 B 侧语义吞异常并返回 false ----
        assertFalse(returned,
                "🔴 对端不在 ⇒ 生产客户端必须返回 false（B 侧 MessageRpcClient:96 的吞异常语义）");

        // ---- 断言 ②：对端库里**必须一行都没有**（对端压根没被调到）----
        assertEquals(0, CrossDomainLinkSupport.peerNotificationRows(UID_ABSENT).size(),
                "🔴 对端不在 ⇒ 对端库里绝不能出现通知行");
    }
}
