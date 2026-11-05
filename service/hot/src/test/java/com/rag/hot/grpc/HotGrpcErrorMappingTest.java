package com.rag.hot.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.hot.service.HotRankingQueryService;
import com.rag.hot.service.InteractionStubService;
import com.rag.proto.hot.CommentArticleRequest;
import com.rag.proto.hot.GetHotArticlesRequest;
import com.rag.proto.hot.GetHotArticlesResponse;
import com.rag.proto.hot.HotArticleItem;
import com.rag.proto.hot.HotServiceGrpc;
import com.rag.proto.hot.LikeArticleRequest;
import com.rag.proto.hot.LikeArticleResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <p><b>hot 域 gRPC 边界的「错误码映射」契约测试</b> —— 本轮补上的<b>真实缺口</b>。
 *
 * <h2>🔴 为什么这一份必须有（B 侧有、C 侧此前没有）</h2>
 * <p>B 侧 hot-rpc 带一份 {@code HotServiceGrpcImplErrorMappingTest}（5 例）⇒ 锁死
 * 「<b>Redis 失败 ⇒ UNKNOWN（不是 INTERNAL）</b>」这条最容易写错的映射。
 * <p>⇒ 而 C 侧此前的 {@link HotGrpcServiceRealGrpcTest} 只钉了<b>方法契约</b>
 * （3 个 UNIMPLEMENTED ＋ 1 个 success=true ＋ TopK 边界）⇒
 * ⇒ <b>「裸异常 ⇒ UNKNOWN」这条映射在 C 侧没有任何用例</b> ⇒
 * ⇒ 若有人照抄 message 域的模板把它改成 INTERNAL，<b>现有用例全绿</b>（⇒ 假绿）。
 * <p>⇒ 本类把这条缺口补上 ⇒ <b>期望值沿用 B 侧</b>（AGENTS.md §1.0c「测试资产单独一档」）。
 *
 * <h2>⚠️ 本类与 B 侧那份的<b>三处</b>差异（L4 表达层 · 不改期望值）</h2>
 * <ol>
 *   <li><b>断言面从「observer 捕获」升级为「真进程内 gRPC 传输层」</b> ——
 *       B 侧直接调 {@code impl.getHotArticles(req, observer)} 再读捕获的 observer；
 *       本类把 {@link HotGrpcService} 注册进<b>真进程内 gRPC server</b>，
 *       用<b>真客户端 stub</b> 发请求 ⇒ <b>status code 与 description 必须真的穿过
 *       protobuf / status / trailers 那一层</b>才算读数 ⇒ 断言的是<b>客户端看见的字节面</b>，
 *       不是「服务端内部算出了什么」。
 *       ⚠️ 同款做法见 {@link HotGrpcServiceRealGrpcTest}（同款理由：devh 的
 *       {@code NettyGrpcServerFactory#getPort()} 在 {@code grpc.server.port=0} 时返回
 *       <b>配置值 0</b> ⇒ 拿不到真实 TCP 口 ⇒ 进程内是本仓既定的取端口方式）。</li>
 *   <li><b>依赖用「可切换的替身」而不是 Mockito mock</b> ——
 *       B 侧用 {@code mock(HotRankingQueryService.class)}；
 *       本类用一个<b>继承</b> {@link HotRankingQueryService} 的替身（只覆写
 *       {@code getHotArticles}）⇒ ⇒ ① 故障注入点与生产调用点<b>同一个方法</b>
 *       （不会被 mock 的桩 API 形状骗过）② 状态切换是<b>同步</b>的、无代理层 ⇒
 *       不会踩「Mockito 对自调用/final 方法失效」那类静默失效。</li>
 *   <li><b>多一条「零调用点」静态守卫</b>（见下方「🔴 为什么还要加静态守卫」）——
 *       B 侧那份没有 ⇒ 它是 C 侧本轮为关闭 <b>F-05 / C-04</b> 专门加的。</li>
 * </ol>
 *
 * <h2>🔴 为什么还要加静态守卫（关 F-05 / C-04 的第二半）</h2>
 * <p>本域的 {@code contract/GrpcErrorMapper}（业务码 ⇄ gRPC status 双向转换）是一个
 * <b>B 侧根本没有对应物</b>的类（映射表 hot.md §1.1 已登记）⇒ C 侧<b>刻意不建调用点</b>。
 * <p>⇒ 「零调用点」这件事<b>本身</b>是行为等价的前提 ⇒ ⇒ 它必须<b>可执行</b>，
 * 不能只写在 javadoc 里 ⇒ ⇒ 本类直接扫 {@code src/main/java}：
 * <b>除声明自身外，hot 域主代码不得出现任何 {@code GrpcErrorMapper.<方法>} 调用</b>。
 * <p>⚠️ <b>扫不到源目录 ⇒ 直接失败</b>（不许 skip、不许静默通过）——
 * 判据找不到输入时必须<b>红</b>，否则就是「空转的恒绿判据」。
 *
 * <h2>B 出处（逐条 · 期望值沿用 B 侧）</h2>
 * <pre>
 *   B 侧 service/hot/hot-rpc/src/test/java/com/rag/hot/hot/rpc/service/impl/
 *        HotServiceGrpcImplErrorMappingTest.java
 *          redisFailureMapsToUnknownNotInternal        :41-57   ⇒ 例 ①
 *          statusRuntimeExceptionIsForwardedAsIs        :59-70   ⇒ 例 ②
 *          commentArticleSucceeds                       :72-82   ⇒ 例 ③
 *          normalPathCompletesWithItems                 :84-100  ⇒ 例 ④
 *          errorIsStatusRuntimeException                :124-135 ⇒ 例 ⑤
 *
 *   B 侧 service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/service/impl/
 *        HotServiceGrpcImpl.java
 *          invoke(...)（统一异常转发）                 :109-123 ⇒ 映射口径的源头
 *          类注释「错误码映射（L1，不可照抄 message-rpc 的模板）」:28-45
 * </pre>
 *
 * <h2>⚠️ 期望值**沿用 B 侧**（D13 · 不「就地改善」）</h2>
 * <p>B 侧的兜底是 {@code codes.Unknown}（grpc-go 的 {@code status.FromError} 对非 status error
 * 返回 {@code ok=false} ⇒ 落 Unknown），<b>不是</b> {@code INTERNAL}
 * ⇒ C 侧必须同样落 <b>UNKNOWN</b> ⇒ 写成 INTERNAL 就是<b>行为变更</b>（不是「更规范」）。
 */
class HotGrpcErrorMappingTest {

    /** 进程内 gRPC 的名字（= devh 的 {@code in-process:<name>} 地址里的 name）。 */
    private static final String IN_PROCESS_NAME = "hot-grpc-error-mapping-test";

    /** 进程内 gRPC server（只起一次）。 */
    private static Server server;

    /** 进程内 channel（只建一次）。 */
    private static ManagedChannel channel;

    /** 可切换的排行榜替身（故障注入点 = 生产调用点）。 */
    private static final ToggleableRankingQuery RANKING = new ToggleableRankingQuery();

    /** 真客户端 stub。 */
    private static HotServiceGrpc.HotServiceBlockingStub stub;

    @BeforeAll
    static void startServer() throws IOException {
        server = InProcessServerBuilder.forName(IN_PROCESS_NAME)
                .addService(new HotGrpcService(RANKING, new InteractionStubService()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).directExecutor().build();
        stub = HotServiceGrpc.newBlockingStub(channel);
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

    @BeforeEach
    void resetFault() {
        RANKING.failure = null;
        RANKING.items = new ArrayList<>();
    }

    // ==================================================================
    // ① 裸异常 ⇒ UNKNOWN（B 侧例① + 例⑤）
    // ==================================================================

    @Test
    @DisplayName("Redis 失败 ⇒ UNKNOWN（Go 裸 error 的落地码），不是 INTERNAL")
    void redisFailureMapsToUnknownNotInternal() {
        RANKING.failure = new QueryTimeoutException("Redis ZRevRangeWithScores failed: timeout");

        StatusRuntimeException error = assertThrowsStatusRuntime(() -> stub.getHotArticles(
                GetHotArticlesRequest.newBuilder().setTopK(20).build()));

        assertThat(error.getStatus().getCode())
                .as("Go 的裸 error 经 status.FromError 落到 UNKNOWN；写成 INTERNAL 就是行为变更")
                .isEqualTo(Status.Code.UNKNOWN);
        assertThat(error.getStatus().getCode())
                .as("绝不能是 INTERNAL（message-rpc 的模板会把它带偏）")
                .isNotEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("错误对象确实是 StatusRuntimeException，可被 grpc 框架直接回传")
    void errorIsStatusRuntimeException() {
        RANKING.failure = new IllegalStateException("boom");

        StatusRuntimeException error = assertThrowsStatusRuntime(() -> stub.getHotArticles(
                GetHotArticlesRequest.newBuilder().setTopK(20).build()));

        assertThat(error).isInstanceOf(StatusRuntimeException.class);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
    }

    @Test
    @DisplayName("裸异常的 description 是**原始错误文本**，不是业务码文案（本域 gRPC 出口无业务码反查）")
    void bareErrorDescriptionIsRawErrorTextNotBusinessCode() {
        RANKING.failure = new QueryTimeoutException("Redis ZRevRangeWithScores failed: timeout");

        StatusRuntimeException error = assertThrowsStatusRuntime(() -> stub.getHotArticles(
                GetHotArticlesRequest.newBuilder().setTopK(20).build()));

        assertThat(error.getStatus().getDescription())
                .as("B 侧：codes.Unknown + description 就是原始错误文本")
                .isEqualTo("Redis ZRevRangeWithScores failed: timeout");
        assertThat(error.getStatus().getDescription())
                .as("description 不得是本域的业务码文案（那意味着错误路径上多了一次业务码反查）")
                .isNotEqualTo("internal server error");
    }

    // ==================================================================
    // ② 显式 StatusRuntimeException 原样转发（B 侧例②）
    // ==================================================================

    @Test
    @DisplayName("显式 StatusRuntimeException 原样转发（like ⇒ UNIMPLEMENTED ＋ 文案逐字）")
    void statusRuntimeExceptionIsForwardedAsIs() {
        StatusRuntimeException error = assertThrowsStatusRuntime(() -> stub.likeArticle(
                LikeArticleRequest.newBuilder().setArticleId("a1").build()));

        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(error.getStatus().getDescription())
                .as("文案必须与 B 侧逐字相同（它是 wire 契约的一部分）")
                .isEqualTo("LikeArticle not implemented yet");
    }

    // ==================================================================
    // ③ commentArticle 成功（B 侧例③）
    // ==================================================================

    @Test
    @DisplayName("commentArticle ⇒ success=true（不对称的那个）")
    void commentArticleSucceeds() {
        var response = stub.commentArticle(CommentArticleRequest.newBuilder()
                .setArticleId("a1").build());

        assertThat(response.getSuccess()).isTrue();
    }

    // ==================================================================
    // ④ 正常路径（B 侧例④）
    // ==================================================================

    @Test
    @DisplayName("正常路径：响应 items 原样下发")
    void normalPathCompletesWithItems() {
        RANKING.items.add(HotArticleItem.newBuilder().setArticleId("a1").setHotScore(42).build());

        GetHotArticlesResponse response = stub.getHotArticles(
                GetHotArticlesRequest.newBuilder().setTopK(20).build());

        assertThat(response.getItemsCount()).isEqualTo(1);
        assertThat(response.getItems(0).getArticleId()).isEqualTo("a1");
        assertThat(response.getItems(0).getHotScore()).isEqualTo(42);
    }

    // ==================================================================
    // ⑤ 🔴 静态守卫：hot 域主代码**零**业务码反查调用点（F-05 / C-04 的不变式）
    // ==================================================================

    @Test
    @DisplayName("🔴 静态守卫：hot 域主代码零 GrpcErrorMapper 调用点（零调用点本身是可观察前提）")
    void hotDomainHasNoBusinessCodeReverseLookupCallSite() {
        Path moduleDir = locateModuleDir();
        Path mainJava = moduleDir.resolve("src").resolve("main").resolve("java");
        Path mapperFile = mainJava.resolve("com").resolve("rag").resolve("hot")
                .resolve("contract").resolve("GrpcErrorMapper.java");

        assertThat(Files.isRegularFile(mapperFile))
                .as("连被守卫的类都不存在 ⇒ 判据空转（必须红，不许静默通过）")
                .isTrue();

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).as("主代码目录必须非空（否则扫了个空目录）").isNotEmpty();
            for (Path file : javaFiles) {
                String source = stripComments(readString(file));
                Matcher matcher = INVOCATION_PATTERN.matcher(source);
                while (matcher.find()) {
                    hits.add(moduleDir.relativize(file) + " ⇒ " + matcher.group().trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(hits)
                .as("hot 域主代码不得调用 GrpcErrorMapper（B 侧无对应物 ⇒ 调用即行为变更）")
                .isEmpty();
    }

    /** 「调用点」的形状：类名 + 点 + 方法名首字母（javadoc 的 {@code {@link X}} 不带点 ⇒ 不会被误判）。 */
    private static final Pattern INVOCATION_PATTERN =
            Pattern.compile("GrpcErrorMapper\\s*\\.\\s*[A-Za-z_]");

    /**
     * 定位模块目录（含 {@code src/main/java}）—— 从工作目录向上找。
     *
     * <p>⚠️ <b>找不到就抛错</b>（不许返回 {@code null} 让上层「跳过」）——
     * 判据找不到输入时必须<b>红</b>，否则是恒绿空转。
     *
     * @return 模块目录
     */
    private static Path locateModuleDir() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++) {
            Path candidate = current.resolve("src").resolve("main").resolve("java")
                    .resolve("com").resolve("rag").resolve("hot");
            if (Files.isDirectory(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("定位不到本模块的 src/main/java（工作目录=" + Path.of("").toAbsolutePath()
                + "）⇒ 静态守卫无法执行 ⇒ 判据空转 ⇒ 必须失败");
    }

    /**
     * 读文件内容。
     *
     * @param path 文件路径
     * @return 文件文本
     */
    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 粗粒度地剥掉注释（块注释 + 行注释）—— 注释里提到类名不算调用点。
     *
     * @param source 源码文本
     * @return 去掉注释后的文本
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /**
     * 断言「调真客户端 ⇒ 抛 {@link StatusRuntimeException}」。
     *
     * @param call 调用动作
     * @return 抛出的异常
     */
    private static StatusRuntimeException assertThrowsStatusRuntime(Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException e) {
            return e;
        }
        throw new AssertionError("期望抛 StatusRuntimeException，但没有抛");
    }

    /**
     * <b>可切换的排行榜替身</b> —— 故障注入点 = 生产调用点。
     *
     * <p>⚠️ 只覆写 {@code getHotArticles}（生产 gRPC 面与 HTTP 面都调它）⇒
     * 故障注入落在<b>真实调用点</b>上，而不是落在某个被 mock 出来的 API 形状上。
     */
    private static final class ToggleableRankingQuery extends HotRankingQueryService {

        /** 非 {@code null} 时抛它（故障注入）。 */
        RuntimeException failure;

        /** 正常路径返回的条目。 */
        List<HotArticleItem> items = new ArrayList<>();

        ToggleableRankingQuery() {
            // 父类的 redis 依赖在本替身里用不到（getHotArticles 被完全覆写）
            super(null);
        }

        @Override
        public List<HotArticleItem> getHotArticles(int topK) {
            if (failure != null) {
                throw failure;
            }
            return items;
        }
    }
}
