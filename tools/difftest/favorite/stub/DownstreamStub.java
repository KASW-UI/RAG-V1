package stub;

import com.rag.proto.article.Article;
import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.UserInfo;
import com.rag.proto.user.UserServiceGrpc;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.Server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 🔴 **favorite 场景轮的「假下游」** —— 一个**真 gRPC 服务端**，同时提供
 * {@code user-rpc} 与 {@code article-rpc} 两个 favorite 真正用到的服务。
 *
 * <h2>为什么需要它</h2>
 * <p>场景轮要覆盖 <b>create 成功路径</b> · <b>超时</b> · <b>部分成功</b> ⇒ ⇒ 都要求下游<b>在场</b>。
 * <p>而现有 32 case 的 HTTP 矩阵**故意**让 user-rpc / article-rpc <b>缺席</b>
 * （{@code static://127.0.0.1:1}）⇒ ⇒ 走的是「下游不可用」那条契约分支（与 Go 侧同款）
 * ⇒ ⇒ <b>不能</b>把假下游混进那 32 case ⇒ ⇒ 场景轮必须<b>另起一轮</b>（两侧重启 ⇒ 换地址）。
 *
 * <h2>为什么两个下游放<b>同一个</b>进程</h2>
 * <p>① 两侧的 favorite 都用<b>同一个</b>下游 ⇒ ⇒ <b>一个桩服务两侧</b> ⇒ ⇒ 两侧走<b>同形</b>路径
 * ⇒ ⇒ 比出来的差异才是<b>行为差异</b>（而不是「两侧各自连到不同下游」造成的假差异）；
 * <p>② 只需<b>一个</b>模式控制文件 ⇒ ⇒ 故障注入是对称的、可逆的。
 *
 * <h2>🔴 两个下游各自必须满足的判据（B 侧出处）</h2>
 * <ul>
 *   <li><b>user-rpc</b>（{@code FavoriteUserGuard} 五条分支）：
 *       <ul>
 *         <li>{@code found=true} 且 {@code user != null} ⇒ 否则落 <b>5101</b>；</li>
 *         <li>{@code user.uid == 请求的 uid} ⇒ 否则落 <b>5001</b>（下游返回了无法解释的响应）；</li>
 *         <li>🔴 <b>{@code user.status} 必须有 presence</b>（{@code optional int64}）
 *             ⇒ 否则落 <b>5001</b> ⇒ ⇒ <b>必须显式 set（哪怕值是 0）</b>；</li>
 *         <li>{@code user.status != 0} ⇒ 落 <b>5005</b>。</li>
 *       </ul></li>
 *   <li><b>article-rpc</b>（{@code FavoriteArticleSnapshotResolver}）：
 *       <ul>
 *         <li>{@code article.id == 请求的 article_id}（否则 <b>5001</b>）；</li>
 *         <li>{@code article.status == PUBLISHED}（否则 <b>5001</b>）；</li>
 *         <li>有 {@code ext_info["published_revision_id"]} ⇒ 它必须形如 {@code "<id>:r<数字>"}
 *             且数字非零（否则 <b>5001</b>）；</li>
 *         <li>没有修订号 ⇒ 必须有 {@code ext_info["publication_gap"]="legacy_revision_missing"}。</li>
 *       </ul>
 *       ⇒ ⇒ 本桩返回 {@code "<id>:r1"} ⇒ 走「有修订号」那条分支（覆盖面更大）。</li>
 * </ul>
 *
 * <h2>模式热切换</h2>
 * <p>模式写在<b>控制文件</b>里（{@code STUB_MODE_FILE}），<b>每次请求都重读一次</b>
 * ⇒ ⇒ 编排脚本可以<b>不重启 stub</b>就切换行为（正常 / 慢 / 不存在）⇒ ⇒ 故障注入是对称的、可逆的。
 */
public final class DownstreamStub {

    /** 控制文件的默认值；编排脚本通过环境变量覆盖。 */
    private static final String DEFAULT_MODE_FILE = "/tmp/favorite-downstream-stub.mode";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(envOrDefault("STUB_PORT", "0"));
        Path modeFile = Paths.get(envOrDefault("STUB_MODE_FILE", DEFAULT_MODE_FILE));

        UserServiceGrpc.UserServiceImplBase userService = new UserServiceGrpc.UserServiceImplBase() {
            @Override
            public void getUser(GetUserReq request, io.grpc.stub.StreamObserver<GetUserResp> responseObserver) {
                Map<String, String> mode = readMode(modeFile);
                String kind = mode.getOrDefault("mode", "normal");
                long slowMs = parseLong(mode.get("slow_ms"), 0L);
                long uid = request.getUid();

                System.out.println("[stub] GetUser uid=" + uid + " mode=" + kind + " slow_ms=" + slowMs);
                System.out.flush();

                if ("slow".equals(kind) && slowMs > 0) {
                    sleep(slowMs);
                }
                if ("notfound".equals(kind)) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("user not found")
                            .asRuntimeException());
                    return;
                }
                GetUserResp resp = GetUserResp.newBuilder()
                        .setFound(true)
                        .setUser(UserInfo.newBuilder()
                                .setUid(uid)
                                .setStatus(0L)
                                .setUsername("scenario-user-" + uid)
                                .build())
                        .build();
                responseObserver.onNext(resp);
                responseObserver.onCompleted();
            }
        };

        ArticleServiceGrpc.ArticleServiceImplBase articleService = new ArticleServiceGrpc.ArticleServiceImplBase() {
            @Override
            public void getArticle(GetArticleRequest request,
                                   io.grpc.stub.StreamObserver<GetArticleResponse> responseObserver) {
                Map<String, String> mode = readMode(modeFile);
                String kind = mode.getOrDefault("mode", "normal");
                long slowMs = parseLong(mode.get("slow_ms"), 0L);
                String articleId = request.getArticleId();

                System.out.println("[stub] GetArticle id=" + articleId + " mode=" + kind + " slow_ms=" + slowMs);
                System.out.flush();

                if ("slow".equals(kind) && slowMs > 0) {
                    sleep(slowMs);
                }
                if ("notfound".equals(kind)) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("article not found")
                            .asRuntimeException());
                    return;
                }
                Article article = Article.newBuilder()
                        .setId(articleId)
                        .setTitle("Scenario Article " + articleId)
                        .setCoverImageUrl("https://example.invalid/cover/" + articleId)
                        .setStatus(ArticleStatus.PUBLISHED)
                        // 🔴 修订号形如 "<articleId>:r1" ⇒ 命中「有修订号」那条校验分支
                        .putExtInfo("published_revision_id", articleId + ":r1")
                        .build();
                responseObserver.onNext(GetArticleResponse.newBuilder().setArticle(article).build());
                responseObserver.onCompleted();
            }
        };

        // ⚠️ grpc 1.63 **已移除** `usePlaintext()`（1.60 起 deprecated → 删除）
        //   ⇒ ⇒ 明文用 `forPort(port, InsecureServerCredentials.create())` 显式声明
        //   ⇒ ⇒ 与两侧 favorite 的 `negotiation-type: plaintext` 对齐。
        Server server = NettyServerBuilder.forPort(port, io.grpc.InsecureServerCredentials.create())
                .addService(userService)
                .addService(articleService)
                .executor(Executors.newFixedThreadPool(8))
                .build()
                .start();

        System.out.println("[stub] 下游桩已启动 port=" + server.getPort()
                + "（user-rpc + article-rpc） mode_file=" + modeFile);
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdownNow();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        server.awaitTermination();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 读控制文件；文件不存在 ⇒ 退回 normal 模式（**不是**报错 ⇒ 编排脚本可以先起 stub 再写模式）。 */
    private static Map<String, String> readMode(Path file) {
        Map<String, String> out = new HashMap<>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    int eq = s.indexOf('=');
                    if (eq > 0) {
                        out.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[stub] 读模式文件失败（按 normal 处理）: " + e);
        }
        out.putIfAbsent("mode", "normal");
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private DownstreamStub() {
    }
}
