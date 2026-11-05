package com.rag.hot.grpc;

import com.rag.hot.service.HotRankingQueryService;
import com.rag.hot.service.InteractionStubService;
import com.rag.proto.hot.CoinArticleRequest;
import com.rag.proto.hot.CoinArticleResponse;
import com.rag.proto.hot.CommentArticleRequest;
import com.rag.proto.hot.CommentArticleResponse;
import com.rag.proto.hot.GetHotArticlesRequest;
import com.rag.proto.hot.GetHotArticlesResponse;
import com.rag.proto.hot.HotArticleItem;
import com.rag.proto.hot.HotServiceGrpc;
import com.rag.proto.hot.LikeArticleRequest;
import com.rag.proto.hot.LikeArticleResponse;
import com.rag.proto.hot.ShareArticleRequest;
import com.rag.proto.hot.ShareArticleResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p><b>hot 域的 gRPC 服务端</b> —— 5 个方法的<b>薄转发</b>。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/service/impl/HotServiceGrpcImpl.java}
 * （1-124 行；类声明 {@code :46-48}；构造器 {@code :55-58}；
 * {@code getHotArticles} {@code :60-67}；{@code likeArticle} {@code :69-75}；
 * {@code commentArticle} {@code :77-82}；{@code coinArticle} {@code :84-92}；
 * {@code shareArticle} {@code :94-98}；{@code invoke} 模板 {@code :109-123}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>类名</b> {@code HotServiceGrpcImpl} → {@code HotGrpcService}
 *             ＋ <b>包名</b> {@code ...rpc.service.impl} → {@code com.rag.hot.grpc}
 *             （映射表 {@code docs/Mapping_Table/hot/hot.md} §1.3 的「压平 + 类改名」口径，
 *             与 message 域的 {@code MessageGrpcService} 同款）；</li>
 *         <li><b>proto 包名</b> {@code com.rag.hot.hot.proto.*} → {@code com.rag.proto.hot.*}（D7）；</li>
 *         <li><b>去掉冗余的 {@code @Component}</b>：devh 的 {@link GrpcService} <b>本身</b>就是
 *             一个 {@code @Component}（元注解）⇒ B 侧两个注解叠着写<b>等价于一个</b>
 *             ⇒ 去掉不改变任何装配结果（与 message 域的 {@code MessageGrpcService} 同款写法）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>5 个方法名与顺序</b>逐条未动；</li>
 *         <li><b>兜底 catch</b>：{@link StatusRuntimeException} 原样 {@code onError}；
 *             其它 {@link RuntimeException} ⇒ {@link Status#UNKNOWN}
 *             （<b>不是 INTERNAL</b>，见 {@link #invoke} 的长注释）；</li>
 *         <li><b>互动桩的不对称</b>逐条保留：3 个 UNIMPLEMENTED + {@code commentArticle} 返回
 *             {@code success=true}（见 {@link InteractionStubService}）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <h2>⚠️ 错误码映射（L1，不可照抄 message-rpc 的模板）</h2>
 * B 侧的 gRPC 方法返回两种 error，映射结果<b>不同</b>：
 * <pre>
 *   ① 明确的 status.Error(codes.Unimplemented, ...)
 *      → go-zero interceptor 原样传递 ⇒ codes.UNIMPLEMENTED
 *   ② 裸 error（GetHotArticles 里 Redis 非 Nil 错误：return nil, err）
 *      → grpc-go 的 status.FromError(err) 对非 status error 返回 ok=false
 *        ⇒ codes.Unknown，description 就是原始错误文本
 * </pre>
 * ⇒ 因此本类<b>不能</b>把兜底分支统一成 {@code INTERNAL}：B 侧这里落到的是 <b>UNKNOWN</b>。
 * 一律兜底成 INTERNAL 会让「Redis 挂了」在客户端表现为 INTERNAL，与 B 侧不一致
 * （两者的可重试语义判断也不同）。
 */
@GrpcService
public class HotGrpcService extends HotServiceGrpc.HotServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(HotGrpcService.class);

    private final HotRankingQueryService rankingQuery;
    private final InteractionStubService interactionStub;

    /**
     * 构造器注入 —— B 侧逐字照抄。
     *
     * @param rankingQuery   排行榜查询（读 Redis 的合并结果）
     * @param interactionStub 互动桩（4 个既定行为）
     */
    public HotGrpcService(HotRankingQueryService rankingQuery, InteractionStubService interactionStub) {
        this.rankingQuery = rankingQuery;
        this.interactionStub = interactionStub;
    }

    /**
     * 取热门文章 —— B 侧 {@code :60-67}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void getHotArticles(GetHotArticlesRequest request,
                               StreamObserver<GetHotArticlesResponse> responseObserver) {
        invoke(() -> {
            List<HotArticleItem> items = rankingQuery.getHotArticles(request.getTopK());
            return GetHotArticlesResponse.newBuilder().addAllItems(items).build();
        }, responseObserver);
    }

    /**
     * 点赞 —— B 侧 {@code :69-75}，<b>固定 UNIMPLEMENTED</b>。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void likeArticle(LikeArticleRequest request,
                            StreamObserver<LikeArticleResponse> responseObserver) {
        invoke(() -> {
            throw interactionStub.likeArticle();
        }, responseObserver);
    }

    /**
     * 评论 —— B 侧 {@code :77-82}，<b>唯一返回 success=true 的方法</b>。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void commentArticle(CommentArticleRequest request,
                               StreamObserver<CommentArticleResponse> responseObserver) {
        invoke(interactionStub::commentArticle, responseObserver);
    }

    /**
     * 投币 —— B 侧 {@code :84-92}，<b>固定 UNIMPLEMENTED</b>。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void coinArticle(CoinArticleRequest request,
                            StreamObserver<CoinArticleResponse> responseObserver) {
        invoke(() -> {
            throw interactionStub.coinArticle();
        }, responseObserver);
    }

    /**
     * 分享 —— B 侧 {@code :94-98}，<b>固定 UNIMPLEMENTED</b>。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void shareArticle(ShareArticleRequest request,
                             StreamObserver<ShareArticleResponse> responseObserver) {
        invoke(() -> {
            throw interactionStub.shareArticle();
        }, responseObserver);
    }

    /**
     * <p><b>统一异常转发</b>。
     *
     * <ul>
     *   <li>{@link StatusRuntimeException}：service 层已显式构造好状态（含 3 个 UNIMPLEMENTED）⇒ 原样转发。</li>
     *   <li>其它 {@link RuntimeException}：对应 B 侧的<b>裸 error</b> ⇒ 映射为
     *       {@link Status#UNKNOWN}（<b>不是</b> INTERNAL，理由见类注释）。</li>
     * </ul>
     *
     * @param handler 业务调用
     * @param observer 响应观察者
     * @param <Resp>   响应类型
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (StatusRuntimeException sre) {
            observer.onError(sre);
        } catch (RuntimeException e) {
            log.error("[Hot] unhandled error: {}", e.getMessage(), e);
            observer.onError(Status.UNKNOWN
                    .withDescription(e.getMessage() == null ? e.getClass().getName() : e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}
