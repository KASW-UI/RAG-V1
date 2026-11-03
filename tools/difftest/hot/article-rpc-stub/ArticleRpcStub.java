package com.rag.hot.parity;

import com.rag.proto.article.Article;
import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

/**
 * <b>article-rpc 的桩</b> —— 双侧活体对拍专用的第三方锚。
 *
 * <h2>为什么必须自建一个 article-rpc 桩</h2>
 * <p>hot 域的 HTTP 面要产出字节，必须有 article-rpc 可打：
 * <pre>
 *   B 侧 hot-api ──gRPC(discovery:///article-rpc)──▶ article-rpc
 *   C 侧 hot-service ──gRPC(discovery:///article-rpc)──▶ article-rpc
 * </pre>
 * <p>本机 Sea 栈**没有** article-rpc 容器（只有 user / admin / message 的 api+rpc）
 * ⇒ ⇒ 必须自建。
 *
 * <h2>🔴 为什么「同一个桩进程服务两侧」是关键</h2>
 * <p>对拍要回答的问题是「<b>hot 域自己的实现</b>在两侧是否等价」。
 * <p>若两侧各连一个**不同的** article-rpc，则两侧返回的 article 投影只要有任意差异
 * （哪怕是随机的 create_time、或多一个 secondary_tag），
 * 热榜卡片就会不同 ⇒ <b>差异被归因到 hot 域头上</b> ⇒ <b>假漂移</b>。
 * <p>⇒ ⇒ 同一个进程、同一份数据、注册进<b>两个 Nacos 分组</b>（B 侧 DEFAULT_GROUP / C 侧 E5C）
 * ⇒ ⇒ 两侧拿到的 article 投影<b>逐字节相同</b>
 * ⇒ ⇒ 任何差异都只能来自 hot 域自身。
 *
 * <h2>为什么桩的返回值是「按 article_id 决定」的</h2>
 * <p>hot 域的补全逻辑有<b>四条 skip 分支</b>（B 侧 {@code HotArticleHydrator:127-154}）：
 * <table border="1">
 *   <caption>四条 skip 分支与本桩的触发方式</caption>
 *   <tr><th>分支</th><th>触发条件</th><th>本桩的触发方式</th></tr>
 *   <tr><td>① empty_article_id</td><td>hot 条目的 article_id 为空</td>
 *       <td>ZSet 里播一个<b>空成员</b>（与桩无关）</td></tr>
 *   <tr><td>② article_rpc_error</td><td>RPC 报错</td>
 *       <td>article_id 含 {@code rpcerr} ⇒ 桩返回 gRPC 错误</td></tr>
 *   <tr><td>③ article_missing</td><td>响应里没有 article</td>
 *       <td>article_id 含 {@code missing} ⇒ 桩返回空响应</td></tr>
 *   <tr><td>④ article_not_published</td><td>status != PUBLISHED</td>
 *       <td>article_id 含 {@code draft} ⇒ 桩返回 status=DRAFT</td></tr>
 * </table>
 * <p>⇒ 四条分支都能在<b>同一次对拍</b>里被触发 ⇒ 两侧的 best-effort 语义逐条可比。
 *
 * <h2>为什么 article_id 会被「回显」到 Article.id</h2>
 * <p>hot 域卡片的 {@code article_id} 取的是 <b>article 侧的 {@code article.getId()}</b>
 * （B 侧 {@code getHotArticlesLogic.go:157}），<b>不是</b> hot 条目的 id
 * ⇒ ⇒ 桩把请求里的 id 回显成 {@code Article.id}
 * ⇒ ⇒ 卡片里的 id 仍是被测的那个 id（两侧一致），
 * 同时这条「取 article 侧的 id」这条契约也一并被验到。
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp &lt;grpc + proto jars&gt; com.rag.hot.parity.ArticleRpcStub &lt;port&gt;
 * </pre>
 * 启动后向 stdout 打一行 {@code STUB_READY port=<port>} ⇒ 脚本据此判断「桩已就绪」。
 */
public final class ArticleRpcStub {

    /** 桩的固定 create_time / update_time（毫秒）—— 两侧必须完全一致 ⇒ 固定值而非墙钟。 */
    static final long FIXED_TIME_MILLIS = 1735787045000L;

    private ArticleRpcStub() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: ArticleRpcStub <port>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);

        io.grpc.Server server = NettyServerBuilder.forPort(port)
                .addService(new ArticleServiceGrpc.ArticleServiceImplBase() {
                    @Override
                    public void getArticle(GetArticleRequest request,
                                           StreamObserver<GetArticleResponse> observer) {
                        String id = request.getArticleId();

                        // 分支 ②：RPC 报错 ⇒ hot 侧判 best-effort skip（article_rpc_error）
                        if (id.contains("rpcerr")) {
                            observer.onError(Status.INTERNAL
                                    .withDescription("probe forced article rpc error")
                                    .asRuntimeException());
                            return;
                        }

                        // 分支 ③：响应里没有 article ⇒ hot 侧判 skip（article_missing）
                        if (id.contains("missing")) {
                            observer.onNext(GetArticleResponse.getDefaultInstance());
                            observer.onCompleted();
                            return;
                        }

                        Article.Builder article = Article.newBuilder()
                                .setId(id)
                                .setTitle("probe-title")
                                .setBrief("probe-brief")
                                .setMarkdownContent("probe-markdown")
                                .setCoverImageUrl("https://example.invalid/cover.png")
                                .setManualTypeTag("probe-manual-tag")
                                .setAuthorId("probe-author")
                                .setCreateTime(FIXED_TIME_MILLIS)
                                .setUpdateTime(FIXED_TIME_MILLIS)
                                .setStatus(id.contains("draft")
                                        ? ArticleStatus.DRAFT
                                        : ArticleStatus.PUBLISHED)
                                .setViewCount(7)
                                .setLikeCount(11)
                                .setCommentCount(13)
                                .setShareCount(17)
                                .putExtInfo("probe", "1");

                        // secondary_tags 非空的分支：Go 侧 proto3 repeated 无元素 ⇒ JSON 输出 null；
                        // 有元素 ⇒ 输出数组。两侧都靠这个 id 触发同一形态。
                        if (id.contains("tags")) {
                            article.addSecondaryTags("alpha");
                            article.addSecondaryTags("beta");
                        }

                        observer.onNext(GetArticleResponse.newBuilder()
                                .setArticle(article)
                                .build());
                        observer.onCompleted();
                    }
                })
                .build()
                .start();

        System.out.println("STUB_READY port=" + port);
        System.out.flush();
        server.awaitTermination();
    }
}
