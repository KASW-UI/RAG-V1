package com.rag.like.service;

import com.rag.like.LikeServiceApplication;
import com.rag.like.domain.service.LikeDomainService;
import com.rag.like.support.FakeMessageRpcServer;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import com.rag.proto.like.ActionType;
import com.rag.proto.like.LikeActionReq;
import com.rag.proto.like.LikeActionResp;
import com.rag.proto.message.NotificationKind;
import com.rag.proto.message.SendNotificationReq;
import com.rag.proto.message.SenderRole;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>like 域 → message 域的跨域通知：<b>真链路</b>守门</b>。
 *
 * <h2>🔴 为什么这一面之前是零活体</h2>
 * <p>like 域承接 B 侧 {@code client/MessageRpcClient.java}（承接表 **R-11**），
 * 它是 like 域<b>唯一</b>的出向跨域调用点 —— {@code notifyArticleLiked}（点赞成功后通知作者）。
 * <p>⇒ 此前所有活体读数都刻意用 {@code author_id = "0"}（对拍探针要两侧耗时可比）
 * ⇒ ⇒ <b>通知分支从未被真触发</b> ⇒ 台账 §六·6.3 的第 ② 项「{@code notifyArticleLiked} 分支零覆盖」。
 *
 * <h2>🔴 触发条件（B 侧逐字）：三个条件<b>同时</b>成立才发通知</h2>
 * <pre>
 * likeOnArticle(= targetType 是 article 且 action=LIKE 且非幂等)
 *   &amp;&amp; request.getAuthorId() &gt; 0
 *   &amp;&amp; request.getUserId() != request.getAuthorId()
 * </pre>
 * ⇒ ⇒ 本类**逐条**把三个条件拆开测（正例 ＋ 三个反例）⇒ 证明不是「碰巧发了一次」。
 *
 * <h2>🔴 期望值的来源：<b>B 侧源码</b>（不是 C 侧实现）</h2>
 * <p>本类断言的每一个字段值都取自 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/client/MessageRpcClient.java:63-82}
 * （逐字内容见各断言的注释）⇒ ⇒ 断言的是「<b>C 侧抄出来的请求</b> == B 侧会发的请求」，
 * 且**经过真 gRPC 线路送达**（⇒ 比源码级对照强：证的是「真的发出去的内容」）。
 *
 * <h2>⚠️ 证据边界（AGENTS §3.3.3）</h2>
 * <p>本类跑的是 <b>C 侧单侧</b>：对端是<b>本域自带的假 message-rpc</b>（真 netty ＋ 真 proto 编解码）。
 * <p>⇒ ⇒ 它<b>不</b>证明「B 侧 message 域此刻真的收到了」⇒ 那需要连 B 侧的
 * {@code Sea-message-rpc}（<b>跨 lane 依赖</b>，不进本域回归）⇒ 本类<b>不冒充</b>该证据。
 * <p>⇒ ⇒ 本类的命题是：「<b>like 域这条跨域调用，真的按 B 侧的规格把请求发出去了</b>」。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // 🔴 把 discovery 的 "message-rpc" 指到本地假 server ⇒ 不碰 Nacos
                "spring.cloud.discovery.client.simple.instances.message-rpc[0].host=127.0.0.1",
                "spring.cloud.discovery.client.simple.instances.message-rpc[0].port=18221"
        })
@ActiveProfiles("test")
class LikeMessageRpcCrossDomainRealTest {

    private static final long AUTHOR_ID = 9_000_001L;

    private static final long USER_ID = 9_000_002L;

    private static final long AWAIT_MS = 15_000L;


    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private LikeDomainService domainService;

    @BeforeEach
    void resetFakeServer() {
        FakeMessageRpcServer.reset();
    }

    /**
     * 🔴 在 {@code @BeforeAll} 里起假 server（<b>不是静态块</b>）。
     *
     * <p>⚠️ <b>为什么不能放静态块</b>：surefire 的类扫描阶段会加载测试类 ⇒ 静态块的副作用
     * 会在扫描期发生 ⇒ 扫描期一旦出问题 ⇒ 该类被<b>静默丢弃</b>（不报错、不执行、不计数）
     * ⇒ ⇒ 实测「同命令两次全量回归：一次 like=110、一次 like=106」
     * ⇒ ⇒ 挪到这里 ⇒ 扫描期<b>无副作用</b> ⇒ 必定被发现；起不来时变成<b>可见的 ERROR</b>。
     * <p>📌 端口必须**提前可知**（devh 的 discovery 要指向它）⇒ ⇒ 固定 {@code 18221}
     * ⇒ 全局配置见 {@code application-test.properties}。
     */
    @BeforeAll
    static void startFakeServer() {
        FakeMessageRpcServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        FakeMessageRpcServer.shutdown();
    }

    // ==================================================================
    // ① 正例：三个条件都成立 ⇒ 通知真的发出去，且**逐字段**等于 B 侧的规格
    // ==================================================================

    @Test
    @DisplayName("🔴 正例：author_id>0 且 user_id≠author_id 且 target_type=article ⇒ 通知真发出且逐字段等于 B 侧规格")
    void notificationIsSentWithGoAlignedFields() throws Exception {
        String targetId = "9007199254740994";

        LikeActionResp resp = domainService.likeAction(LikeActionReq.newBuilder()
                .setTargetType("article")
                .setTargetId(targetId)
                .setUserId(USER_ID)
                .setAuthorId(AUTHOR_ID)
                .setActionType(ActionType.ACTION_LIKE)
                .build());
        assertThat(resp.getSuccess()).as("点赞本身必须成功").isTrue();

        SendNotificationReq req = FakeMessageRpcServer.take(AWAIT_MS);
        assertThat(req).as("跨域通知必须在 %dms 内真的发出去", AWAIT_MS).isNotNull();

        // ---- 以下每个期望值都逐字取自 B 侧 MessageRpcClient.java:63-82 ----
        assertThat(req.getRecipientIdsList())
                .as("B 侧: .addRecipientIds(recipientId) ⇒ 只通知作者本人")
                .containsExactly(AUTHOR_ID);
        assertThat(req.getBroadcast())
                .as("B 侧: .setBroadcast(false) —— 只通知作者本人")
                .isFalse();
        assertThat(req.getSenderId())
                .as("B 侧: .setSenderId(likerId)")
                .isEqualTo(USER_ID);
        assertThat(req.getSenderRole())
                .as("B 侧: .setSenderRole(SenderRole.USER)")
                .isEqualTo(SenderRole.USER);
        assertThat(req.getKind())
                .as("B 侧: .setKind(NotificationKind.ARTICLE_LIKED)")
                .isEqualTo(NotificationKind.ARTICLE_LIKED);
        assertThat(req.getTitle())
                .as("B 侧: .setTitle(\"文章收获新点赞\")")
                .isEqualTo("文章收获新点赞");
        assertThat(req.getContent())
                .as("B 侧: .setContent(String.format(\"用户 %d 点赞了你的文章。\", likerId))")
                .isEqualTo(String.format("用户 %d 点赞了你的文章。", USER_ID));
        assertThat(req.getExtraMap())
                .as("B 侧: putExtra 四个键，值全部是字符串")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "target_type", "article",
                        "target_id", targetId,
                        "liker_id", String.valueOf(USER_ID),
                        "author_id", String.valueOf(AUTHOR_ID)));
    }

    // ==================================================================
    // ② 反例：author_id = 0 ⇒ 不发（B 侧条件 `author_id > 0`）
    // ==================================================================

    @Test
    @DisplayName("🔴 反例：author_id=0 ⇒ 不得发出任何通知")
    void noNotificationWhenAuthorIdIsZero() throws Exception {
        domainService.likeAction(LikeActionReq.newBuilder()
                .setTargetType("article")
                .setTargetId("9007199254740995")
                .setUserId(USER_ID)
                .setAuthorId(0L)
                .setActionType(ActionType.ACTION_LIKE)
                .build());

        assertThat(FakeMessageRpcServer.take(2_000L))
                .as("author_id=0 ⇒ B 侧条件不成立 ⇒ 不得发通知")
                .isNull();
    }

    // ==================================================================
    // ③ 反例：user_id == author_id ⇒ 不发（自己点赞自己 ⇒ 不通知自己）
    // ==================================================================

    @Test
    @DisplayName("🔴 反例：user_id == author_id ⇒ 不得发出任何通知")
    void noNotificationWhenLikerIsAuthor() throws Exception {
        domainService.likeAction(LikeActionReq.newBuilder()
                .setTargetType("article")
                .setTargetId("9007199254740996")
                .setUserId(AUTHOR_ID)
                .setAuthorId(AUTHOR_ID)
                .setActionType(ActionType.ACTION_LIKE)
                .build());

        assertThat(FakeMessageRpcServer.take(2_000L))
                .as("user_id == author_id ⇒ B 侧条件不成立 ⇒ 不得发通知")
                .isNull();
    }

    // ==================================================================
    // ④ 失败语义：跨域失败**不得**影响点赞结果（Go：只记日志）
    // ==================================================================

    @Test
    @DisplayName("🔴 失败语义：跨域通知失败 ⇒ likeAction 仍返回 success=true（Go：只记日志不抛）")
    void crossDomainFailureDoesNotBreakLikeAction() {
        FakeMessageRpcServer.failNextCalls(true);

        LikeActionResp resp = domainService.likeAction(LikeActionReq.newBuilder()
                .setTargetType("article")
                .setTargetId("9007199254740997")
                .setUserId(USER_ID)
                .setAuthorId(AUTHOR_ID)
                .setActionType(ActionType.ACTION_LIKE)
                .build());

        assertThat(resp.getSuccess())
                .as("B 侧: notifyArticleLiked 失败只 logger.LogBusinessErr ⇒ 点赞结果不受影响")
                .isTrue();
        assertThat(resp.getMessage()).isEqualTo("action success");
    }
}
