package com.rag.favorite.client;

import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

/**
 * <p><b>article-rpc gRPC 客户端</b> —— 持有 {@code ArticleServiceBlockingStub}。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/client/ArticleRpcClient.java}
 *
 * <p><b>Go 出处</b>：{@code service/favorite/rpc/internal/svc/service_context.go:38}
 * <pre>
 * ArticleRpc: articleservice.NewArticleService(zrpc.MustNewClient(c.ArticleRpc))
 * </pre>
 * 对应 {@code etc/favorite.yaml:43-47} 的 {@code Etcd.Key: article.rpc} ⇒ 服务发现名 {@code article-rpc}。
 *
 * <p><b>L4 落点变化（D7 包压平 + D7 proto 集中）</b>：
 * <ul>
 *   <li>B 侧 {@code com.rag.favorite.favorite.rpc.client.ArticleRpcClient}
 *       ⇒ C 侧 {@code com.rag.favorite.client.ArticleRpcClient}；</li>
 *   <li>pb 包名 B 侧 {@code com.rag.article.article.proto} ⇒ C 侧 {@code com.rag.proto.article}（D7）。</li>
 * </ul>
 *
 * <p><b>唯一消费者</b>是 {@code FavoriteArticleSnapshotResolver}
 * （B 侧的 {@code logic/helper.go:126-178}）—— favorite 最核心业务规则
 * 「公开修订冻结」的取值来源。
 *
 * <h2>🔴 请求的三个字段全是 L1，不可改</h2>
 * <pre>
 * // logic/helper.go:133-137
 * resp, err := svcCtx.ArticleRpc.GetArticle(depCtx, &amp;articleservice.GetArticleRequest{
 *     ArticleId:  normalizedTargetID,
 *     IncrView:   false,
 *     PublicOnly: true,
 * })
 * </pre>
 * <ul>
 *   <li>{@code ArticleId} —— 已经过 {@code strings.TrimSpace}（helper.go:130）</li>
 *   <li>{@code IncrView = false} —— <b>收藏不得增加文章阅读量</b>。
 *       改成 true 会污染 article 侧的 view_count，且是<b>不可逆</b>的。</li>
 *   <li>{@code PublicOnly = true} —— 只读公开投影；为 false 会让收藏到未发布/草稿文章。</li>
 *   <li>{@code RequesterId} —— B 侧<b>不设</b>（零值空串）。favorite 不做作者读取，
 *       所以也不该设 ⇒ 保持未设置。</li>
 * </ul>
 */
@Component
public class ArticleRpcClient {

    @GrpcClient("article-rpc")
    private ArticleServiceGrpc.ArticleServiceBlockingStub articleServiceBlockingStub;

    /**
     * 出向 stub —— <b>deadline 由共享层的全局客户端拦截器统一施加</b>（2026-10-31 · G3 第十一轮）。
     *
     * <p>🔴 <b>B 侧出处（行为层）</b>：B 侧 {@code zrpc.MustNewClient(c.ArticleRpc)} 的客户端
     * <b>无条件</b>装 {@code TimeoutInterceptor}（{@code zrpc/internal/client.go:113-117}
     * ＋ {@code zrpc/internal/config.go:15} 的 {@code default=true}）⇒ <b>2000ms</b> 上限
     * （{@code zrpc/config.go:31} 的 {@code json:",default=2000"}；B 侧
     * {@code etc/favorite.yaml} 的 {@code ArticleRpc:} 段未配 {@code Timeout} ⇒ 走这个默认值）。
     *
     * <p>🔴 <b>为什么本类不再自己套 deadline</b>：B 侧的客户端超时是<b>拦截器级别</b>的
     * ⇒ ⇒ C 侧同形也应是拦截器级 ⇒ ⇒ 改由共享层的
     * {@code GoStyleOutboundDeadlineInterceptor}（devh 的 {@code @GrpcGlobalClientInterceptor}
     * ⇒ 装在<b>每个</b>出向 devh 调用上 ⇒ 连<b>裸 stub</b> 都过链）统一施加，
     * 本域<b>只配参数</b>：{@code rag.protection.outbound-deadline.cap-millis=2000}
     * （见本组件 {@code application.yml}）。
     *
     * <p>⚠️ <b>域内私有副本 {@code GoStyleClientCallTimeout} 已删除</b>：
     * <b>副本数 = 漂移面</b> ⇒ ⇒ 共享层守门 {@code GoStyleClientCallTimeoutSingleSourceGateTest}
     * 的终态口径 = 「域侧 0 份，只配参数」。
     */
    public ArticleServiceGrpc.ArticleServiceBlockingStub articleRpc() {
        return articleServiceBlockingStub;
    }

    /**
     * 「按公开投影读取一篇文章」—— 对应 B 侧 {@code helper.go:133-137} 的那次调用。
     *
     * <p>把三个 L1 字段固化在<b>这一处</b>，是为了让「收藏涨阅读量」这种事故
     * 变成一个只能改一行的地方，而不是散落在调用点的三个 boolean。
     *
     * @param trimmedArticleId <b>已经过 TrimSpace</b> 的文章 ID
     */
    public GetArticleResponse getPublicArticle(String trimmedArticleId) {
        GetArticleRequest request = GetArticleRequest.newBuilder()
                .setArticleId(trimmedArticleId)
                .setIncrView(false)     // L1：收藏不得增加阅读量
                .setPublicOnly(true)    // L1：只读公开（已发布）投影
                // requesterId 刻意不设：B 侧零值空串，favorite 不做作者读取
                .build();
        return articleRpc().getArticle(request);
    }
}
