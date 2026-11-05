package com.rag.hot.client;

import com.rag.hot.config.HotApiProperties;
import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import io.grpc.stub.AbstractStub;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * <p><b>article-rpc 的 gRPC 客户端</b> —— hot 域唯一的<b>跨域</b>依赖。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/client/ArticleRpcClient.java}
 * （1-97 行；类声明 {@code :57-58}；两个常量 {@code :61} / {@code :64}；
 * stub 字段 {@code :66-67}；{@code getPublicArticle} {@code :78-86}；
 * 两个测试钩子 {@code :89-96}）
 * ＋ {@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/client/GoStyleGrpcCallDeadline.java}
 * （1-85 行；{@code apply} {@code :72-84}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.hot.hot.api.client} → {@code com.rag.hot.client}（D7 包压平）；</li>
 *         <li><b>proto 包名</b>：{@code com.rag.article.article.proto.*} → {@code com.rag.proto.article.*}（D7）；</li>
 *         <li><b>不建独立的 deadline 类</b>：B 侧的 {@code GoStyleGrpcCallDeadline}（一个只做
 *             「把请求剩余预算挂到 stub 上」的 final class）⇒ 本仓<b>不建</b>
 *             ⇒ 它的职责落到<b>本类的一个私有方法</b> + {@link HotApiProperties}
 *             （映射表 {@code docs/Mapping_Table/hot/hot.md} §1.2 第 8 行已拍板
 *             「落进配置，无独立类」）；</li>
 *         <li><b>上界数值的落点</b>：B 侧的 2s 来自共享层的
 *             {@code rag.protection.outbound-deadline.cap-millis: 2000}（B 侧 hot-api yml）
 *             ⇒ 本仓改由 {@link HotApiProperties#getArticleRpc()} 承载
 *             （见下方「⚠️ 上界来源」小节）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>两个 flag</b>（{@code PUBLIC_ONLY=true} / {@code INCR_VIEW=false}）
 *             在<b>唯一出口</b>硬编码 ⇒ 调用点无法传错 ⇒ 与 B 侧逐字一致；</li>
 *         <li><b>deadline 口径</b>：{@code min(2000ms, 请求剩余预算)} ⇒ 与 B 侧
 *             「共享层 cap 2000 与请求预算取 min」<b>完全同值</b>；</li>
 *         <li><b>不吞异常</b>：失败向上抛 ⇒ 由 {@code HotApiService} 判成 best-effort skip
 *             （B 侧同一形态）。</li>
 *       </ul>
 *       ⚠️ <b>一处 L1 差异（配置键位）</b>：B 侧的 2s 写在
 *       {@code rag.protection.outbound-deadline.cap-millis}（共享层参数），
 *       本仓写在 {@code hot-api.article-rpc.timeout}（域侧参数）⇒
 *       <b>有效值同为 2000ms</b>，但<b>配置键位不同</b> ⇒ 登记在 hot 域 DRIFT 台账。
 *       ⚠️ <b>为什么不同时配共享层的 cap</b>：那会造出<b>第二个上界来源</b>
 *       ⇒ 「cap 单一来源」纪律 ⇒ ⇒ 本仓<b>只</b>保留域侧这一处，
 *       共享层的 {@code cap-millis} 保持<b>未配置</b>（出厂默认 0 = 不启用）。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 这两个 flag 是有副作用的，不是「风格参数」</b>：
 * <ul>
 *   <li>{@code incr_view=true} 会让 article-rpc 给文章 {@code +1} 阅读量 ——
 *       热门列表每被拉一次就有 N 篇文章阅读量 +1 ⇒ B 侧明确传 false，C 侧必须同样。</li>
 *   <li>{@code public_only=true} 决定取的是<b>「当前可读的已发布版本」（frozen revision）</b>
 *       而不是可变的源行 ⇒ 源行处于 REVIEWING 准备改稿时，公开投影仍是 PUBLISHED，
 *       文章<b>不会</b>被 {@code status != PUBLISHED} 这条过滤误杀。</li>
 * </ul>
 */
@Component
public class ArticleRpcClient {

    /** B 侧 {@code PublicOnly: true} —— 见类注释。 */
    public static final boolean PUBLIC_ONLY = true;

    /** B 侧 {@code IncrView: false} —— 见类注释。 */
    public static final boolean INCR_VIEW = false;

    @GrpcClient("article-rpc")
    private ArticleServiceGrpc.ArticleServiceBlockingStub articleServiceBlockingStub;

    private final HotApiProperties properties;

    /**
     * 构造器注入。
     *
     * @param properties hot 域的 HTTP 层配置（承载出向 deadline 上界）
     */
    public ArticleRpcClient(HotApiProperties properties) {
        this.properties = properties;
    }

    /**
     * <p><b>取文章「公开只读」投影</b> —— 对应 B 侧 {@code buildHotArticleItem} 的那次
     * {@code ArticleRpc.GetArticle}。
     *
     * @param articleId        文章 ID
     * @param remainingBudgetMs 请求剩余预算；{@code -1} 表示无 HTTP 超时预算
     * @return 下游响应；<b>不</b>吞异常 —— 这一层失败是 <b>skip 该条</b>（best-effort），
     *         由调用方捕获并转成 skip
     */
    public GetArticleResponse getPublicArticle(String articleId, long remainingBudgetMs) {
        return stubWithDeadline(articleServiceBlockingStub, remainingBudgetMs)
                .getArticle(GetArticleRequest.newBuilder()
                        .setArticleId(articleId)
                        .setIncrView(INCR_VIEW)
                        .setPublicOnly(PUBLIC_ONLY)
                        // B 侧未设置 RequesterId ⇒ proto3 零值 ""（不携带）
                        .build());
    }

    /**
     * <p><b>把「请求剩余预算」套到 stub 上</b>（<b>传播 + 加上界，不新建独立类</b>）。
     *
     * <p>⚠️ <b>返回值不可缓存到字段</b>：{@code withDeadlineAfter} 每次返回新实例，
     * 缓存会把「第一次调用时的预算」固化下来，之后每个请求都带着过期 deadline。
     *
     * <p>⚠️ <b>预算已耗尽时给 1ns</b>（而不是原样返回 stub）：
     * B 侧 ctx 一旦到期，下游调用<b>立即失败</b>；
     * 原样返回等于发起一个「没有 deadline 的慢调用」⇒ 那正是要修的漂移。
     *
     * @param stub              原始 stub
     * @param remainingBudgetMs 请求剩余预算（毫秒）；{@code -1} 表示<b>没有</b> HTTP 超时预算
     * @return 带 deadline 的<b>新</b> stub
     */
    <T extends AbstractStub<T>> T stubWithDeadline(T stub, long remainingBudgetMs) {
        long cap = properties.getArticleRpc().getTimeout();
        if (remainingBudgetMs < 0L) {
            // ⚠️ 无请求预算 ⇒ **不挂请求侧的 deadline**，只剩客户端上界
            //   ⇒ ⇒ 此时出向调用的上界 = cap（2000ms）
            if (cap <= 0L) {
                return stub;
            }
            return stub.withDeadlineAfter(cap, TimeUnit.MILLISECONDS);
        }
        if (remainingBudgetMs <= 0L) {
            // 预算已耗尽 ⇒ B 侧 ctx 已到期，下游调用**立即失败**
            return stub.withDeadlineAfter(1L, TimeUnit.NANOSECONDS);
        }
        long effective = (cap > 0L) ? Math.min(cap, remainingBudgetMs) : remainingBudgetMs;
        return stub.withDeadlineAfter(effective, TimeUnit.MILLISECONDS);
    }

    /** 仅供测试注入捕获型 stub（package-private，非生产 API）。 */
    ArticleServiceGrpc.ArticleServiceBlockingStub rawStub() {
        return articleServiceBlockingStub;
    }

    /** 仅供测试注入捕获型 stub（package-private，非生产 API）。 */
    void rawStubField(ArticleServiceGrpc.ArticleServiceBlockingStub stub) {
        this.articleServiceBlockingStub = stub;
    }
}
