package com.rag.hot.grpc;

import com.rag.hot.messaging.HotRedisSnapshotStore;
import com.rag.hot.support.HotKafkaTestBase;
import com.rag.proto.hot.CommentArticleRequest;
import com.rag.proto.hot.CoinArticleRequest;
import com.rag.proto.hot.GetHotArticlesRequest;
import com.rag.proto.hot.GetHotArticlesResponse;
import com.rag.proto.hot.HotArticleItem;
import com.rag.proto.hot.HotServiceGrpc;
import com.rag.proto.hot.LikeArticleRequest;
import com.rag.proto.hot.ShareArticleRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>hot 域 gRPC 面的「真 gRPC」测试</b> —— 把 5 个方法逐条钉死。
 *
 * <h2>🔴 为什么这一面<b>必须有</b>测试（此前是缺口）</h2>
 * <p>hot 域对外有<b>两个入口</b>：HTTP（{@code GET /hot/v1/articles}）与 gRPC（5 个方法）。
 * 本轮 HTTP 面有真 Tomcat 测试，**而 gRPC 面此前一条测试都没有** ⇒
 * ⇒ 「5 个方法都实现了」这件事<b>只由源码存在性保证</b>，<b>没有任何运行期证据</b>
 * ⇒ ⇒ 属于「<b>Bean 存在 ≠ 机制生效</b>」的同族缺口（§3.3）。
 *
 * <h2>⚠️ 本测的是<b>方法的契约</b>，不是「devh 的拦截链」</h2>
 * <p>本测试把<b>生产装配出来的</b> {@link HotGrpcService} bean 注册进一个<b>进程内 gRPC server</b>
 * ⇒ ⇒ 走的是<b>真的 protobuf 序列化 / 状态码 / description 传递</b>
 * ⇒ ⇒ 但<b>不经过</b> devh server 的拦截链（共享层的保护链另有其测试 ＋ A3 断言）。
 * <p>⚠️ 与 {@code ArticleRpcInProcessServer} 同款做法（同款理由：devh 的
 * {@code NettyGrpcServerFactory#getPort()} 在 {@code grpc.server.port=0} 时返回的是<b>配置值 0</b>
 * 而不是实际绑定口 ⇒ 拿不到真实 TCP 口）。
 *
 * <h2>本类钉死的 5 条语义（B 侧逐条照抄，<b>不许「统一风格」</b>）</h2>
 * <ol>
 *   <li>{@code likeArticle} ⇒ {@code UNIMPLEMENTED} ＋ 文案 {@code "LikeArticle not implemented yet"}；</li>
 *   <li>{@code coinArticle} ⇒ {@code UNIMPLEMENTED} ＋ 文案 {@code "CoinArticle not implemented yet"}；</li>
 *   <li>{@code shareArticle} ⇒ {@code UNIMPLEMENTED} ＋ 文案 {@code "ShareArticle not implemented yet"}；</li>
 *   <li><b>⚠️ {@code commentArticle} ⇒ {@code success=true}</b>（<b>唯一</b>不报 UNIMPLEMENTED 的那个 ⇒ <b>不对称是 B 侧既定行为</b>）；</li>
 *   <li>{@code getHotArticles} ⇒ {@code top_k ≤ 0 → 20} · {@code top_k > 100 → 100} ·
 *       key 不存在 ⇒ <b>空 items</b>（不冒泡）· {@code hot_score = uint32(score)} <b>向零截断</b>。</li>
 * </ol>
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/service/impl/HotServiceGrpcImpl.java}
 * （5 个方法 ＋ 统一异常转发）· {@code .../service/InteractionStubService.java}（3 个 UNIMPLEMENTED 文案 ＋
 * comment 的 {@code success=true}）· {@code .../service/HotRankingQueryService.java:43,46,55-59}（TopK 边界 ＋
 * {@code redis.Nil → 空 items} ＋ {@code uint32} 截断）。
 */
class HotGrpcServiceRealGrpcTest extends HotKafkaTestBase {

    /** 进程内 gRPC 的名字（= devh 的 {@code in-process:<name>} 地址里的 name）。 */
    private static final String IN_PROCESS_NAME = "hot-grpc-test";

    /** 进程内 gRPC server（只起一次）。 */
    private static Server server;

    /** 进程内 channel（只建一次）。 */
    private static ManagedChannel channel;

    /** 阻塞 stub。 */
    private static HotServiceGrpc.HotServiceBlockingStub stub;

    /** 生产装配出来的 gRPC 服务实现（**真 bean**，不是 new 出来的替身）。 */
    @Autowired
    private HotGrpcService hotGrpcService;

    /** 真 Redis（{@code getHotArticles} 的数据源）。 */
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() throws IOException {
        if (server == null) {
            server = InProcessServerBuilder.forName(IN_PROCESS_NAME)
                    .addService(hotGrpcService)
                    .build()
                    .start();
            channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).directExecutor().build();
            stub = HotServiceGrpc.newBlockingStub(channel);
        }
        // 清掉上一条用例留下的合并结果（**合并为空时直接返回、不会写 shadow** ⇒ 不会被调度器覆盖）
        redis.delete(HotRedisSnapshotStore.HOT_ARTICLES_KEY);
    }

    @AfterAll
    static void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    // ==================================================================
    // ① 4 个互动用例（3 UNIMPLEMENTED ＋ 1 success=true）
    // ==================================================================

    @Test
    @DisplayName("likeArticle ⇒ UNIMPLEMENTED ＋ 文案逐字为 B 侧那句")
    void likeArticleIsUnimplementedWithBsideText() {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> stub.likeArticle(LikeArticleRequest.newBuilder()
                        .setArticleId("900200001").setUserId("900200100").build()));
        assertEquals(Status.Code.UNIMPLEMENTED, error.getStatus().getCode());
        assertEquals("LikeArticle not implemented yet", error.getStatus().getDescription());
    }

    @Test
    @DisplayName("coinArticle ⇒ UNIMPLEMENTED ＋ 文案逐字为 B 侧那句")
    void coinArticleIsUnimplementedWithBsideText() {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> stub.coinArticle(CoinArticleRequest.newBuilder()
                        .setArticleId("900200001").setUserId("900200100").setCoinCount(1).build()));
        assertEquals(Status.Code.UNIMPLEMENTED, error.getStatus().getCode());
        assertEquals("CoinArticle not implemented yet", error.getStatus().getDescription());
    }

    @Test
    @DisplayName("shareArticle ⇒ UNIMPLEMENTED ＋ 文案逐字为 B 侧那句")
    void shareArticleIsUnimplementedWithBsideText() {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> stub.shareArticle(ShareArticleRequest.newBuilder()
                        .setArticleId("900200001").setUserId("900200100")
                        .setChannel("wechat").build()));
        assertEquals(Status.Code.UNIMPLEMENTED, error.getStatus().getCode());
        assertEquals("ShareArticle not implemented yet", error.getStatus().getDescription());
    }

    @Test
    @DisplayName("commentArticle ⇒ success=true（⚠️ 唯一不报 UNIMPLEMENTED 的那个 · 不对称是 B 侧既定行为）")
    void commentArticleReturnsSuccess() {
        var response = stub.commentArticle(CommentArticleRequest.newBuilder()
                .setArticleId("900200001").setUserId("900200100").build());
        assertNotNull(response);
        assertTrue(response.getSuccess(), "comment 必须返回 success=true（B 侧既定行为，不得「统一风格」改成 UNIMPLEMENTED）");
    }

    // ==================================================================
    // ② getHotArticles（TopK 边界 ＋ 空 key ＋ uint32 截断）
    // ==================================================================

    @Test
    @DisplayName("getHotArticles：key 不存在 ⇒ 空 items（不冒泡、不报错）")
    void getHotArticlesWithMissingKeyReturnsEmptyItems() {
        GetHotArticlesResponse response = stub.getHotArticles(
                GetHotArticlesRequest.newBuilder().setTopK(20).build());
        assertNotNull(response);
        assertEquals(0, response.getItemsCount(), "key 不存在时必须是空 items（B 侧 redis.Nil → 空，不得冒泡）");
    }

    @Test
    @DisplayName("getHotArticles：top_k ≤0 → 20；>100 → 100；显式值 → 该值（三个边界逐条钉死）")
    void getHotArticlesAppliesTopKBounds() {
        // 105 条 ⇒ 默认 20 / 上限 100 都能被观察到
        seed(105);

        assertEquals(20, countOf(topK(0)), "top_k = 0 ⇒ 默认 20（B 侧 defaultTopK）");
        assertEquals(20, countOf(topK(-1)), "top_k 为负 ⇒ 同样走默认 20（≤ 0 归一）");
        assertEquals(100, countOf(topK(200)), "top_k > 100 ⇒ 封顶 100（B 侧 maxTopK）");
        assertEquals(100, countOf(topK(1000)), "top_k 极大 ⇒ 仍然封顶 100");
        assertEquals(5, countOf(topK(5)), "显式 top_k ⇒ 该值");
    }

    @Test
    @DisplayName("getHotArticles：hot_score = uint32(score) 向零截断（不是四舍五入、不是 floor）")
    void getHotArticlesTruncatesScoreTowardZero() {
        // 分数 3.9 ⇒ 向零截断 ⇒ 3（若用 Math.round 会得 4）
        seedOne("score-trunc", 3.9d);
        // 分数 4294967295.0 = uint32 上限 ⇒ 原样（若用 int 强转会变 -1）
        seedOne("score-max", 4294967295.0d);

        GetHotArticlesResponse response = stub.getHotArticles(topK(20));

        assertEquals(2, response.getItemsCount());
        for (HotArticleItem item : response.getItemsList()) {
            if ("score-trunc".equals(item.getArticleId())) {
                assertEquals(3, item.getHotScore(), "3.9 必须向零截断成 3");
            } else if ("score-max".equals(item.getArticleId())) {
                assertEquals(4294967295L, Integer.toUnsignedLong(item.getHotScore()),
                        "uint32 上限必须原样（proto 字段是 uint32；Java 侧读回来是 int32 位模式 ⇒ 用 toUnsignedLong 还原）");
            } else {
                throw new AssertionError("意外条目：" + item.getArticleId());
            }
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一个 TopK 请求。
     *
     * @param topK top_k
     * @return 请求
     */
    private static GetHotArticlesRequest topK(int topK) {
        return GetHotArticlesRequest.newBuilder().setTopK(topK).build();
    }

    /**
     * 取回的条目数。
     *
     * @param request 请求
     * @return 条目数
     */
    private static int countOf(GetHotArticlesRequest request) {
        return stub.getHotArticles(request).getItemsCount();
    }

    /**
     * 往合并结果 key 里塞 n 条（分数 1..n ⇒ 降序可预期）。
     *
     * @param n 条数
     */
    private void seed(int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (int i = 0; i < n; i++) {
            tuples.add(ZSetOperations.TypedTuple.of("seed-" + i, (double) (n - i)));
        }
        redis.opsForZSet().add(HotRedisSnapshotStore.HOT_ARTICLES_KEY, tuples);
    }

    /**
     * 往合并结果 key 里塞 1 条（指定分数）。
     *
     * @param articleId 文章 id
     * @param score     分数
     */
    private void seedOne(String articleId, double score) {
        redis.opsForZSet().add(HotRedisSnapshotStore.HOT_ARTICLES_KEY, articleId, score);
    }
}
