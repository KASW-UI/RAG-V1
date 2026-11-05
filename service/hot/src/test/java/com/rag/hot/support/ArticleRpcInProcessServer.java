package com.rag.hot.support;

import com.rag.proto.article.Article;
import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import io.grpc.Server;
import io.grpc.inprocess.InProcessServerBuilder;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>测试用「真 article-rpc」（进程内 gRPC server）</b> —— 让 hot 域的 HTTP 端点
 * 走一条<b>真的 gRPC 调用</b>，而不是把 client mock 掉。
 *
 * <h2>⚠️ 为什么用进程内 gRPC 而不是 mock {@code ArticleRpcClient}</h2>
 * <p>hot 域的 HTTP 端点有一条<b>跨域 gRPC</b> 的边界（{@code GET /hot/v1/articles}
 * ⇒ hot-rpc（域内直连）⇒ article-rpc（跨域））。
 * <p>若把 {@code ArticleRpcClient} mock 掉 ⇒ 被 mock 掉的正好是<b>「补全的 4 条 skip 分支」
 * 与「fatal / best-effort 不对称」这两组判据的输入源</b> ⇒ ⇒ 那两组判据就没有真触发点。
 * <p>⇒ 本类起一个<b>真 gRPC server</b>（{@code grpc-inprocess} 传输）
 * ⇒ devh 的 client 走 {@code in-process:<name>} 地址 ⇒ <b>序列化 / 状态码 / 异常映射全部真跑</b>。
 *
 * <h2>⚠️ 进程内传输的地址写法</h2>
 * <p>devh 3.1.0.RELEASE 的 {@code InProcessOrAlternativeChannelFactory} 只对
 * <b>{@code in-process} 这个 scheme</b>走进程内 channel（其余 scheme 走 netty）；
 * 且它取的是 {@code address.getSchemeSpecificPart()} 作为 in-process 名字
 * ⇒ ⇒ {@code in-process:article-rpc-test} ⇒ 名字 = {@code article-rpc-test}
 * ⇒ ⇒ server 必须用<b>同一个名字</b>注册（{@code InProcessServerBuilder.forName(name)}）。
 *
 * <h2>⚠️ 为什么 server 在<b>静态块</b>里起</h2>
 * <p>devh 的 {@code @GrpcClient} stub 在 <b>Bean 初始化期</b>就会建 channel
 * ⇒ server 必须<b>先于</b> Spring 上下文就绪 ⇒ 静态块最早。
 *
 * <p>⚠️ 本类只服务于<b>测试</b>：它<b>不</b>改变生产代码路径。
 */
public final class ArticleRpcInProcessServer {

    /** 进程内 gRPC 的名字（= devh 的 {@code in-process:<name>} 地址里的 name）。 */
    public static final String IN_PROCESS_NAME = "article-rpc-test";

    /**
     * 特例文章 id —— 服务端对它返回一个<b>没有 article 字段</b>的响应
     * ⇒ 命中 hot 域补全的「article_missing」skip 分支。
     */
    public static final String EMPTY_ARTICLE_ID = "900200004";

    /**
     * 「文章 id → 状态」的登记表 —— 测试用它控制「这篇是否 PUBLISHED」。
     *
     * <p>⚠️ 键是<b>文章 id</b>；值是 {@link ArticleStatus}。
     */
    private static final Map<String, ArticleStatus> ARTICLES = new ConcurrentHashMap<>();

    private static final Server SERVER;

    static {
        try {
            ARTICLES.put("900200001", ArticleStatus.PUBLISHED);
            ARTICLES.put("900200002", ArticleStatus.DRAFT);
            ARTICLES.put("900200003", ArticleStatus.PUBLISHED);
            ARTICLES.put("900200006", ArticleStatus.PUBLISHED);
            SERVER = InProcessServerBuilder.forName(IN_PROCESS_NAME)
                    .addService(new ArticleService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start in-process article-rpc", e);
        }
    }

    private ArticleRpcInProcessServer() {
    }

    /**
     * 让类被加载 ⇒ 静态块里的 server 就绪。
     *
     * @param articleId 文章 id（仅用于日志可读性）
     */
    public static void ensureStarted(String articleId) {
        // 触发类加载即可
        assert articleId != null;
    }

    /**
     * 设定某篇文章的公开状态。
     *
     * @param articleId 文章 id
     * @param status    状态（{@link ArticleStatus#PUBLISHED} 等）
     */
    public static void putArticle(String articleId, ArticleStatus status) {
        ARTICLES.put(articleId, status);
    }

    /**
     * 清空登记表（每个用例前调用，避免用例之间串数据）。
     */
    public static void reset() {
        ARTICLES.clear();
    }

    /**
     * 恢复默认的三篇种子文章。
     */
    public static void resetToSeed() {
        reset();
        ARTICLES.put("900200001", ArticleStatus.PUBLISHED);
        ARTICLES.put("900200002", ArticleStatus.DRAFT);
        ARTICLES.put("900200003", ArticleStatus.PUBLISHED);
        ARTICLES.put("900200006", ArticleStatus.PUBLISHED);
    }

    /**
     * 真正实现 {@code GetArticle} 的服务 —— <b>只实现这一个方法</b>，
     * 其余方法用 devh 生成的默认实现（{@code UNIMPLEMENTED}）。
     */
    private static final class ArticleService extends ArticleServiceGrpc.ArticleServiceImplBase {

        @Override
        public void getArticle(GetArticleRequest request,
                               io.grpc.stub.StreamObserver<GetArticleResponse> responseObserver) {
            String id = request.getArticleId();
            if (EMPTY_ARTICLE_ID.equals(id)) {
                // 特例：返回一个**没有 article 字段**的响应 ⇒ 命中 hot 域补全的
                // 「article_missing」skip 分支（articleResp 非空但 article 缺席）。
                responseObserver.onNext(GetArticleResponse.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }
            ArticleStatus status = ARTICLES.get(id);
            if (status == null) {
                // B 侧的 article-rpc 对「文章不存在」返回 NOT_FOUND
                responseObserver.onError(io.grpc.Status.NOT_FOUND
                        .withDescription("article not found")
                        .asRuntimeException());
                return;
            }
            Article article = Article.newBuilder()
                    .setId(id)
                    .setTitle("title-" + id)
                    .setBrief("brief-" + id)
                    .setMarkdownContent("content-" + id)
                    .setCoverImageUrl("http://cdn.invalid/" + id + ".png")
                    .setAuthorId("900200100")
                    .setStatus(status)
                    .setCreateTime(1_700_000_000L)
                    .setViewCount(7)
                    .setLikeCount(3)
                    .setCommentCount(2)
                    .setManualTypeTag("http-tag")
                    .build();
            responseObserver.onNext(GetArticleResponse.newBuilder().setArticle(article).build());
            responseObserver.onCompleted();
        }
    }

    /**
     * 关闭 server（JVM 退出钩子；测试期间不调用）。
     *
     * @throws InterruptedException 中断
     */
    public static void shutdown() {
        SERVER.shutdownNow();
        try {
            SERVER.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
