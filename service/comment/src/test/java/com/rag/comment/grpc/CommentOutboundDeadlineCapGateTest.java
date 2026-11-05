package com.rag.comment.grpc;

import com.rag.common.observability.GoStyleRequestDeadline;
import com.rag.common.protection.OutboundDeadlineInterceptor;
import com.rag.common.protection.ProtectionProperties;
import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>B 组（L4 装配面 · 出向 2s deadline 硬上界）守门网</b> —— 锁住「本域出向 gRPC 调用的 2 秒
 * 客户端硬上界」，并证明<b>它真的在出向调用上生效</b>（不是「配置里写了一个数」）。
 *
 * <h2>1. 比 B 侧多做了什么（AGENTS.md §3.2 三段式 · ①）</h2>
 * <ol>
 *   <li><b>🔴 出向方向做了替换：B 侧守的是「comment-api → comment-rpc」，C 侧这条链路
 *       <b>结构性不存在</b></b>（D1/D7：api + rpc 压平成单进程 ⇒ 域内直连 ⇒ C 侧
 *       {@code service/comment/src/main/resources/application.yml:220-222} 明确写
 *       「<b>本域没有 comment-rpc 客户端</b>」）
 *       ⇒ ⇒ 本类把「出向方向」换成 C 侧<b>真实存在</b>的两个出向方向之一：
 *       <b>{@code article-rpc}</b>（{@code application.yml:214-219}
 *       {@code grpc.client.article-rpc.address: discovery:///article-rpc}）
 *       ⇒ 对应的<b>真实 gRPC 服务方法名</b> = {@code article.ArticleService/GetArticle}
 *       （proto：{@code proto/src/main/proto/article.proto:3} {@code package article;}
 *       {@code :127-129} {@code service ArticleService} / {@code rpc GetArticle}）
 *       ⇒ ⇒ <b>判据的语义没变</b>：「出向调用被 2s 上界掐断」，只是<b>下游服务名换了</b>
 *       （拦截器是 <b>channel 级 / 全局级</b>的 ⇒ 对<b>任何</b>出向方向都生效 ⇒ 方向替换不削弱判据）。</li>
 *   <li><b>类名去 {@code GoStyle} 前缀</b>：{@code GoStyleOutboundDeadlineInterceptor} →
 *       {@link OutboundDeadlineInterceptor}；{@code GoStyleProtectionProperties} →
 *       {@link ProtectionProperties}（D11 包压平 · 命名债 P4-④ 同款处置 ⇒ <b>只改 import，不改语义</b>）。</li>
 *   <li><b>新增「阳性对照」用例</b>（cap=0 ⇒ 不被 2s 掐断）：B 侧那两个测试文件里<b>没有</b>这条
 *       ⇒ 它是本组补充的判据（点 13）⇒ 用来证明「上界确实是<b>机制</b>决定的」，
 *       而不是「反正都会超时」。</li>
 *   <li><b>新增「载体缺失 ⇒ 明确失败」用例</b>（点 12）：把 B 侧
 *       {@code CommentOutboundDeadlineCarrierGateTest#noCarrierStillGetsTwoSecondClientCap}
 *       的判据（出向上界<b>必须</b>存在且 ≈2s）搬进来，并<b>再加一层行为面</b>
 *       （真调用以 {@code DEADLINE_EXCEEDED} 收场 ⇒ 不是「静默无 deadline」）。</li>
 * </ol>
 *
 * <h2>2. 语义还一样吗（AGENTS.md §3.2 三段式 · ②）</h2>
 * <p><b>一样。</b>本类断言的每一个<b>被测语义</b>都逐条来自 B 侧：
 * <ul>
 *   <li>「出向上界 = 2s」⇒ B 侧 {@code comment-api/src/test/java/com/rag/comment/comment/api/client/CommentApiRpcDeadlineGateTest.java:226-259}（配置面）
 *       ＋ {@code :263-347}（行为面）；</li>
 *   <li>「域侧不得再出现第二份客户端硬上界」⇒ 同文件 {@code :351-377}；</li>
 *   <li>「无载体时仍套 2s 封顶」⇒ B 侧 {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/client/CommentOutboundDeadlineCarrierGateTest.java:536-557}；</li>
 *   <li>「出向按<b>纳秒</b>取预算（取 min，不是覆盖）」⇒ 同文件 {@code :486-530}。</li>
 * </ul>
 * ⇒ <b>唯一的表达层差异</b>是<b>包名 / 类名 / 出向方向</b>三项 ⇒ 判据强度<b>不减</b>。
 *
 * <h2>3. B 出处（AGENTS.md §3.2 三段式 · ③ · 消费点优先）</h2>
 * <pre>
 * // ① 客户端硬上界的默认值（2000ms）—— Go 侧 struct tag 的默认值
 * //    B 出处：go-zero@v1.10.2/zrpc/config.go:31   Timeout int64 `json:",default=2000"`
 * // ② 客户端超时中间件默认开、且【无条件】装在每个 zrpc client 上
 * //    B 出处：go-zero@v1.10.2/zrpc/internal/config.go:15  Timeout bool `json:",default=true"`
 * //    B 出处：go-zero@v1.10.2/zrpc/internal/client.go:113-117  interceptors = append(interceptors, clientinterceptors.TimeoutInterceptor(timeout))
 * // ③ 本域 B 侧 yaml 的 MessageRpc: / ArticleRpc: 两段都没写 Timeout ⇒ 走 ①② 的默认值
 * //    B 出处：service/comment/comment-rpc/etc/comment.yaml
 * // ④ 客户端上界 = min(父 ctx 的 deadline, 客户端上界) ⇒ 取更早者，绝不覆盖
 * //    B 出处：go-zero@v1.10.2/zrpc/internal/clientinterceptors/timeoutinterceptor.go:25
 * //              ctx, cancel := context.WithTimeout(ctx, t)
 * // ⑤ HTTP 预算 3s（go-zero rest/config.go 的 Timeout default=3000）
 * //    B 出处：service/comment/comment-api/src/main/resources/application.yml:140-142
 * // ⑥ C 侧的等价实现（域侧只配参数，机制在共享层）
 * //    C 出处：common/src/main/java/com/rag/common/protection/OutboundDeadlineInterceptor.java:251-292
 * //              （effectiveCallOptions：三项取 min）
 * //    C 出处：service/comment/src/main/resources/application.yml:393-409
 * //              （rag.protection.outbound-deadline.cap-millis = ${COMMENT_OUTBOUND_DEADLINE_CAP_MILLIS:2000}）
 * </pre>
 *
 * <h2>🔴 为什么断言「行为」而不是「CallOptions 上挂了 deadline」</h2>
 * <p>本仓自己的纪律（AGENTS.md §3.3）：「断言 stub 上挂了 CallOptions」是断言<b>配置</b>，
 * 不是断言<b>行为</b>。
 * ⇒ ⇒ 本类起一个<b>真 in-process gRPC server</b>，服务端方法<b>故意睡过 deadline</b>，
 * 观察客户端调用<b>以什么结局收场</b>（结局码 + 耗时 + 服务端有没有把调用跑完）。
 * ⇒ ⇒ 同时保留一组「CallOptions 层面」的断言（用于<b>分维度</b>判别：继承 / 封顶 / 纳秒精度），
 * 但它们<b>只作辅证</b>，主判据是行为面。
 *
 * <h2>🔴 三个行为用例各自能区分什么（判别设计）</h2>
 * <table border="1">
 *   <caption>「有 2s 上界」与「没有 2s 上界」的判别设计</caption>
 *   <tr><th>用例</th><th>HTTP 预算</th><th>服务端睡眠</th><th>有 2s 上界</th><th>退化成「只继承预算」</th></tr>
 *   <tr><td>③</td><td>无</td><td>2.5s</td><td><b>DEADLINE_EXCEEDED</b></td><td><b>OK</b>（无 deadline ⇒ 调用成功）</td></tr>
 *   <tr><td>④</td><td>30s</td><td>2.5s</td><td><b>DEADLINE_EXCEEDED</b></td><td><b>OK</b>（30s 预算够用）</td></tr>
 *   <tr><td>⑤</td><td>3s（生产形态）</td><td>5s</td><td><b>DEADLINE_EXCEEDED</b> 且 <b>≈2s</b></td>
 *       <td>DEADLINE_EXCEEDED 但 <b>≈3s</b> ⇒ 靠<b>耗时</b>区分</td></tr>
 * </table>
 * <p>⚠️ 用例 ③④ 的判别靠<b>结局码不同</b>（DEADLINE_EXCEEDED vs OK）⇒ 不依赖计时，稳定。
 * <p>⚠️ 用例 ⑤ 与生产形态完全一致（HTTP 预算 3s），但两边的结局码<b>相同</b>
 * ⇒ 只能靠耗时区分（2s 上限 vs 3s 预算）⇒ 阈值取 2.6s，两侧各留 0.4s 以上余量。
 *
 * <h2>🧬 变异实测说明（点 14 · 判据不是恒真）</h2>
 * <p>本类<b>不是</b>「反正都会超时」的守卫 —— 下列三处改动<b>任一</b>都会让本类<b>转红</b>：
 * <ol>
 *   <li><b>把共享层的 cap 写死成 0</b>（= 不启用）⇒ 用例 ③④⑤⑫ 全部拿到 <b>OK</b>（服务端睡完才返回）
 *       ⇒ <b>结局码断言</b>与<b>耗时断言</b>同时红；</li>
 *   <li><b>把本域 yml 的 {@code cap-millis} 行删掉</b> ⇒ cap 回落共享层默认 0 ⇒ 同上 ⇒ 红
 *       （⇒ 这正是「键名写错就是【配了不生效】」的守门点）；</li>
 *   <li><b>把域 yml 的 env 旋钮名改掉</b>（例如改成别的 env 名）⇒ 用例 ⑦ 的「收紧」观察不到
 *       ⇒ 用例 ⑦ 红（它断言的是「env 覆盖真的收紧了上界」⇒ <b>证明域 yml 的那个键真被读到</b>）。</li>
 * </ol>
 * ⇒ 另外，用例 ⑬（cap=0 ⇒ <b>不</b>被掐断）是<b>阳性对照</b>：它保证「红」不是因为「反正都会超时」。
 *
 * <h2>⚠️ 证据边界（AGENTS.md §3.3.3）</h2>
 * <p>本类跑的是 <b>C 侧单侧</b>：断言「C 侧的链真的挂上了、超时真的生效」。
 * 「B 侧此刻也如此」属于<b>双侧活体对拍</b>（本类<b>不冒充</b>该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}；</li>
 *   <li>不用 {@code tools.jackson.*}（本类不碰 JSON）；</li>
 *   <li>本类<b>不依赖 Docker</b>（in-process gRPC + 纯文件读取 ⇒ 无容器 ⇒ 无 skipped）。</li>
 * </ul>
 */
class CommentOutboundDeadlineCapGateTest {

    /**
     * 本域的 env 覆盖旋钮（与本域 {@code application.yml} 里的占位符同名）。
     *
     * <p>⚠️ <b>为什么不是 B 侧那个名字</b>：B 侧 comment-api 与 comment-rpc <b>两份</b> yml 写的是
     * <b>两个不同的</b> env 名（comment-api 写 {@code COMMENT_API_OUTBOUND_DEADLINE_CAP_MILLIS}、
     * comment-rpc 写的是字面量 {@code 2000}）
     * ⇒ D1 压平成单进程后<b>只能留一个</b> ⇒ C 侧取域级名 {@code COMMENT_OUTBOUND_DEADLINE_CAP_MILLIS}
     * （见 {@code application.yml:406-408} 的说明）⇒ <b>生效值 2000 与两侧都一致</b>。
     */
    private static final String ENV_CAP = "COMMENT_OUTBOUND_DEADLINE_CAP_MILLIS";

    /** Go 侧 {@code zrpc/config.go:31} 的默认值。 */
    private static final long GO_DEFAULT_CAP_MILLIS = 2_000L;

    /** env 收紧后的取值 —— 必须<b>小于</b> Go 默认 2000 ⇒ 才能分辨「键被读到」与「键没被读到」。 */
    private static final long TIGHTENED_CAP_MILLIS = 1_000L;

    /**
     * 第二条用例里手动绑定的剩余预算：<b>10.9ms</b>（刻意取非整毫秒 ⇒ 亚毫秒部分 = 0.9ms）。
     *
     * <p>⚠️ <b>为什么是 10.9ms 而不是 1.9ms</b>：亚毫秒部分要<b>足够大</b>才能与测量开销拉开距离
     * ⇒ ⇒ 旧实现（读 {@code remainingMillis()}）会把 10.9ms 砍成 <b>10ms</b> ⇒ 精度损失 ≈ 0.9ms
     * ⇒ ⇒ 与 0.4ms 的判据拉开了一个数量级 ⇒ ⇒ 判据稳。
     */
    private static final long KNOWN_BUDGET_NANOS = 10_900_000L;

    /**
     * 精度损失的判据上界（纳秒）：<b>0.4ms</b>。
     *
     * <p>⚠️ <b>为什么测「相对损失」而不是「绝对剩余值」</b>：本机实测「两次 {@code nanoTime()}
     * 调用之间」的机器开销约 <b>0.3–0.5ms</b>（类加载 + JIT）⇒ ⇒ 绝对剩余值里混着这笔开销
     * ⇒ ⇒ 用绝对值当判据会<b>时红时绿</b>（假信号）⇒ ⇒ 必须拿「载体当下的剩余」当基线，
     * 只测「出向比它窄了多少」。
     */
    private static final long PRECISION_LOSS_TOLERANCE_NANOS = 400_000L;

    /** 出向方向的<b>下游 gRPC 服务全名</b>（= proto 的 {@code package.Service}）。 */
    private static final String OUTBOUND_SERVICE_NAME = "article.ArticleService";

    private static Server server;
    private static ManagedChannel channel;

    /**
     * 共享层的出向 deadline 拦截器（<b>生产同款类</b> ＋ <b>域 yml 绑出来的参数</b>）。
     *
     * <p>⚠️ <b>为什么只 new 一次</b>（而不是每次调用现 new）：
     * <ul>
     *   <li>域 yml 的<b>磁盘读 + Binder 绑定</b>要花几毫秒 ⇒ ⇒ 若放进「纳秒精度」那条用例的
     *       测量作用域里，<b>预算会在读文件的过程中就耗尽</b>（10.9ms 的预算 vs 几毫秒的文件读）
     *       ⇒ ⇒ 判据会变成随机红 ⇒ ⇒ 必须在测量作用域<b>之外</b>准备好；</li>
     *   <li>行为面那条用例同理 ⇒ ⇒ <b>stub 必须在计时开始前造好</b>（见各用例）。</li>
     * </ul>
     * ⇒ 造法仍与生产一致：读<b>本域磁盘上那份</b> {@code application.yml} ⇒ 绑出
     * {@link ProtectionProperties} ⇒ 交给共享层的 {@link OutboundDeadlineInterceptor}
     * ⇒ ⇒ 「域侧只配参数、机制在共享层」这条纪律在测试里也成立。
     */
    private static OutboundDeadlineInterceptor sharedInterceptor;

    /**
     * <b>每次调用一个令牌</b> —— 用来把「上一个用例残留的服务端 handler」与本用例隔开。
     *
     * <p>⚠️ <b>为什么不能用「类级共享的完成标志」</b>：服务端是 <b>{@code @BeforeAll} 静态</b>的
     * ⇒ ⇒ 上一个用例的 handler 可能<b>还在睡</b>（本类的服务端睡眠有 2.5s / 5s 两种）
     * ⇒ ⇒ 它醒来后会把共享标志置真 ⇒ ⇒ <b>污染下一个用例</b>的「服务端不应把调用跑完」断言。
     * ⇒ ⇒ 本类用「令牌」把跨用例的残留隔开（B 侧 {@code CommentApiRpcDeadlineGateTest:110-124}
     * 踩过并修掉了这个坑 ⇒ 逐字继承该设计）。
     */
    private static final AtomicLong callToken = new AtomicLong();

    /** 本用例的令牌（每个用例开始时更新）。 */
    private static volatile long currentToken;

    /** 服务端是否真的把<b>本用例</b>的调用跑完了（用来证明「没被 deadline 打断」）。 */
    private static final AtomicBoolean serverCompleted = new AtomicBoolean(false);

    /** 服务端的睡眠时长 —— 可按用例调整。 */
    private static volatile long serverSleepMillis = 2_500L;

    @BeforeAll
    static void startServer() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                // ⚠️ **不能** directExecutor()：那会让服务端 handler **跑在客户端线程上**
                // ⇒ 服务端的 Thread.sleep 会把客户端线程一起堵住 ⇒
                //    deadline 虽然准时触发了（结局码仍是 DEADLINE_EXCEEDED），
                //    但客户端**要等服务端睡完才返回** ⇒ 测不出「2s 就掐断」。
                // ⇒ 服务端跑在自己的线程池上（grpc-java 的 InProcessTransport 把
                //    serverStreamListener.halfClosed() 投到**服务端**的 SynchronizationContext 上），
                //    客户端才能在 2s 处**立即**返回。
                .addService(ServerServiceDefinitionBuilderHolder.service())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        // ⚠️ 共享层的出向拦截器：**用本域 yml 绑出来的那个值**（2000ms）构造
        //    ⇒ ⇒ 「域侧只配参数」这条纪律在测试里也成立（不是另写一份算术）。
        sharedInterceptor = new OutboundDeadlineInterceptor(domainProperties(null));
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void beginCase() {
        // ⚠️ 推进令牌 ⇒ ⇒ 上一个用例残留的服务端 handler 醒来后**不能**再置本用例的标志
        currentToken = callToken.incrementAndGet();
        serverCompleted.set(false);
    }

    @AfterEach
    void clearDeadline() {
        // 测试之间绝不能残留 deadline —— 残留会把下一个用例变成假绿/假红
        GoStyleRequestDeadline.clear();
        currentToken = callToken.incrementAndGet();
        serverCompleted.set(false);
    }

    // ====================================================================================
    // 装置
    // ====================================================================================

    /**
     * 服务端定义（<b>article-rpc 方向</b> = C 侧真实存在的出向方向）。
     *
     * <p>⚠️ <b>为什么只给 service name</b>（不给 {@code getServiceDescriptor()}）：
     * 带 descriptor 构造 ⇒ grpc-java 会要求「descriptor 里的每个方法都必须被绑定」
     * ⇒ 而本类只关心 deadline ⇒ 只绑 {@code GetArticle} 一个方法
     * （与 B 侧 {@code CommentOutboundDeadlineCarrierGateTest:244-246} 同款处理）。
     *
     * <p>⚠️ <b>为什么方向换成了 article-rpc</b>：见类 javadoc「1. 比 B 侧多做了什么」①
     * —— B 侧的 comment-api → comment-rpc 链路在 C 侧结构性不存在。
     */
    private static final class ServerServiceDefinitionBuilderHolder {

        private ServerServiceDefinitionBuilderHolder() {
        }

        static io.grpc.ServerServiceDefinition service() {
            return io.grpc.ServerServiceDefinition
                    .builder(ArticleServiceGrpc.getServiceDescriptor().getName())
                    .addMethod(
                            ArticleServiceGrpc.getGetArticleMethod(),
                            io.grpc.stub.ServerCalls.asyncUnaryCall((GetArticleRequest req,
                                                                       io.grpc.stub.StreamObserver<GetArticleResponse> obs) -> {
                                // ⚠️ 进入 handler 时取一次令牌 ⇒ ⇒ 「上一个用例残留的 handler」
                                //    醒来后不会污染本用例的断言
                                long myToken = currentToken;
                                try {
                                    Thread.sleep(serverSleepMillis);
                                    if (myToken == currentToken) {
                                        serverCompleted.set(true);
                                    }
                                    obs.onNext(GetArticleResponse.getDefaultInstance());
                                    obs.onCompleted();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    obs.onError(new StatusRuntimeException(Status.CANCELLED
                                            .withDescription("server interrupted")));
                                } catch (RuntimeException e) {
                                    obs.onError(e);
                                }
                            }))
                    .build();
        }
    }

    /**
     * 造一个<b>装了生产拦截器</b>的出向 stub。
     *
     * <p>⚠️ <b>与 devh 同一条装配动作</b>：devh 的 {@code AbstractChannelFactory.createChannel}
     * 最后一行是 {@code ClientInterceptors.interceptForward(channel, interceptors)}
     * ⇒ ⇒ 全局拦截器是装在 <b>channel</b> 上的 ⇒ ⇒ 「裸 stub」也经过它。
     * <p>⚠️ <b>拦截器读的是【域 yml 绑出来的属性】</b> ⇒ ⇒ 这条链同时证明
     * 「域 yml 的那个键真被读到」＋「共享层真在出向调用上生效」。
     */
    private static ArticleServiceGrpc.ArticleServiceBlockingStub client(Map<String, Object> envOverrides) {
        Channel capped = ClientInterceptors.intercept(channel,
                new OutboundDeadlineInterceptor(domainProperties(envOverrides)));
        return ArticleServiceGrpc.newBlockingStub(capped);
    }

    /**
     * 用<b>指定 cap</b> 造一个装了生产拦截器的出向 stub（用于「阳性对照」：cap=0 = 不启用）。
     *
     * @param capMillis 客户端硬上界（毫秒）；{@code 0} = 不启用
     */
    private static ArticleServiceGrpc.ArticleServiceBlockingStub clientWithCap(long capMillis) {
        ProtectionProperties props = new ProtectionProperties();
        props.getOutboundDeadline().setCapMillis(capMillis);
        Channel capped = ClientInterceptors.intercept(channel, new OutboundDeadlineInterceptor(props));
        return ArticleServiceGrpc.newBlockingStub(capped);
    }

    /** 真发一次调用，返回观察到的状态码；成功则返回 {@link Status.Code#OK}。 */
    private static Status.Code call(ArticleServiceGrpc.ArticleServiceBlockingStub stub) {
        try {
            stub.getArticle(GetArticleRequest.newBuilder().setArticleId("a-diff").build());
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    // ====================================================================================
    // ① 配置面（点 1 · 点 2）
    // ====================================================================================

    @Test
    @DisplayName("① 域 yml 必须声明 rag.protection.outbound-deadline.cap-millis，且是 env 可覆盖形态"
            + "（Go 出处：zrpc/config.go:31 Timeout int64 `json:\",default=2000\"`）")
    void domainYmlDeclaresEnvOverridableCap() {
        String raw = rawYamlValue("rag.protection.outbound-deadline.cap-millis");

        assertNotNull(raw,
                "🔴 本域 application.yml 必须配 rag.protection.outbound-deadline.cap-millis "
                        + "⇒ ⇒ 共享层 OutboundDeadlineInterceptor 的 cap 取默认值 0 ＝ 不启用"
                        + "⇒ ⇒ 出向 gRPC 调用【没有客户端硬上界】（Go 侧恒有 2000ms）"
                        + "⇒ ⇒ 域侧那份自带的封顶逻辑已删除 ⇒ ⇒ 这半就等于没做。");
        assertTrue(raw.startsWith("${") && raw.endsWith("}"),
                "🔴 cap-millis 必须是 ${ENV:默认} 形态（实测 = " + raw + "）⇒ ⇒ 否则【不可 env 覆盖】");
        assertTrue(raw.contains(ENV_CAP),
                "🔴 占位符里必须点名本域的 env 旋钮 " + ENV_CAP + "（实测 = " + raw + "）"
                        + "⇒ ⇒ 各域独立可覆盖（Go 侧 RpcClientConf.Timeout 本身就是域级字段）。");
        assertTrue(raw.endsWith(":" + GO_DEFAULT_CAP_MILLIS + "}"),
                "🔴 占位符的默认值必须是 Go 的 zrpc/config.go:31 默认值 " + GO_DEFAULT_CAP_MILLIS
                        + "ms（实测 = " + raw + "）⇒ ⇒ 本域 Go 侧 etc/comment.yaml 的 "
                        + "MessageRpc: / ArticleRpc: 两段无显式 Timeout ⇒ ⇒ 恒 2000ms。");
    }

    @Test
    @DisplayName("② 域 yml 绑出的 cap 必须 == Go 默认 2000ms，且传播开关仍开"
            + "（Go 出处：zrpc/internal/config.go:15 Timeout bool `json:\",default=true\"`）")
    void capBindsToGoDefault() {
        ProtectionProperties props = domainProperties(null);

        assertEquals(GO_DEFAULT_CAP_MILLIS, props.getOutboundDeadline().getCapMillis(),
                "🔴 域 yml 的 rag.protection.outbound-deadline.cap-millis 必须真的绑定到"
                        + " ProtectionProperties.OutboundDeadline#capMillis"
                        + "⇒ ⇒ 键名写错就是【配了不生效】（AGENTS.md §3.3.1）。");
        assertTrue(props.getOutboundDeadline().isEnabled(),
                "🔴 出向 deadline 传播开关默认开（Go 侧 zrpc/internal/config.go:15 的 Timeout 默认 true）"
                        + "⇒ ⇒ 本域不得把它关掉。");
        assertTrue(props.getOutboundDeadline().getCapMillisByService().isEmpty(),
                "🔴 本域<b>不配</b>按下游服务全名的覆盖表 ⇒ ⇒ 组件级 2000ms 对<b>所有</b>出向方向"
                        + "（含本类的 article-rpc 方向）一律生效 ⇒ ⇒ 若这里非空 ⇒ 本类的行为面断言"
                        + "就不再代表「组件级 2000ms」⇒ ⇒ 必须重读本域 yml 的 cap-millis-by-service 段。");
        assertEquals(OUTBOUND_SERVICE_NAME, ArticleServiceGrpc.getGetArticleMethod().getServiceName(),
                "🔴 本类的出向方向必须是 C 侧**真实存在**的 article-rpc 方向"
                        + "（proto 出处：proto/src/main/proto/article.proto:3 `package article;`"
                        + " ＋ :127 `service ArticleService`）"
                        + "⇒ ⇒ 若 proto 改名 ⇒ 本类的方向替换需同步更新（否则会静默测错方向）。");
    }

    // ====================================================================================
    // ② 行为面（点 3 · 点 4 · 点 5 · 点 6 · 点 7）
    // ====================================================================================

    @Test
    @DisplayName("③ Go zrpc/config.go:31 default=2000 —— **无 HTTP 预算**时下游调用也必须有 2s 上限")
    void noHttpBudgetStillGetsTwoSecondCap() {
        serverSleepMillis = 2_500L;
        assertEquals(-1L, GoStyleRequestDeadline.remainingMillis(), "本用例的前提：当前线程没有 HTTP 预算");

        // ⚠️ **stub 必须在计时开始前造好**（它要读磁盘上的 yml + 绑属性 ⇒ 几毫秒）
        //    ⇒ ⇒ 否则「耗时」里混着文件读 ⇒ 阈值判据会 flaky。
        ArticleServiceGrpc.ArticleServiceBlockingStub stub = client(null);
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code,
                "Go 的 timeout 中间件是**永远装着**的（zrpc/internal/config.go:15 default=true）"
                        + "⇒ 即使没有 HTTP 预算，下游调用也必须在 2s 处被掐断。"
                        + "若观察到 OK，说明这次调用根本没有 deadline。");
        assertTrue(elapsedMillis < 2_400L,
                "必须由 **2s 上界**掐断（不是服务端 2.5s 后自己返回）⇒ 实测 " + elapsedMillis + "ms");
        assertTrue(!serverCompleted.get(), "服务端不应把调用跑完 —— 跑完说明客户端没掐断");
    }

    @Test
    @DisplayName("④ Go http.go:53 的 3s 预算 vs zrpc 的 2s 上限 ⇒ **2s 生效**（上限小于预算）")
    void twoSecondCapWinsOverLongerHttpBudget() {
        serverSleepMillis = 2_500L;
        GoStyleRequestDeadline.begin(System.nanoTime() + TimeUnit.SECONDS.toNanos(30));

        // ⚠️ **stub 必须在计时开始前造好**（它要读磁盘上的 yml + 绑属性 ⇒ 几毫秒）
        //    ⇒ ⇒ 否则「耗时」里混着文件读 ⇒ 阈值判据会 flaky。
        ArticleServiceGrpc.ArticleServiceBlockingStub stub = client(null);
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code,
                "HTTP 预算 30s，但 Go 的下游上限是 2s ⇒ 必须在 2s 处 DEADLINE_EXCEEDED。"
                        + "若观察到 OK，说明 2s 上界没生效。");
        assertTrue(elapsedMillis < 2_400L,
                "必须由 **2s 上界**掐断 ⇒ 实测 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("⑤ 生产形态（HTTP 预算 3s + 上界 2s）⇒ **2s 处**掐断，不是 3s")
    void productionShapeCapsAtTwoSecondsNotThree() {
        serverSleepMillis = 5_000L;
        GoStyleRequestDeadline.begin(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));

        // ⚠️ **stub 必须在计时开始前造好**（它要读磁盘上的 yml + 绑属性 ⇒ 几毫秒）
        //    ⇒ ⇒ 否则「耗时」里混着文件读 ⇒ 阈值判据会 flaky。
        ArticleServiceGrpc.ArticleServiceBlockingStub stub = client(null);
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code, "两边的结局码相同，只能靠耗时区分");
        assertTrue(elapsedMillis < 2_600L,
                "必须在 **2s** 处掐断；若实测接近 3000ms，说明用的是 HTTP 的 3s 预算、"
                        + "Go 的 2s 上限没生效 ⇒ 实测 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("⑥ 预算已耗尽 ⇒ **立刻**失败（< 500ms），不是「没有 deadline」")
    void exhaustedBudgetMeansImmediateNotAbsent() {
        serverSleepMillis = 5_000L;
        GoStyleRequestDeadline.begin(System.nanoTime() - 1L);   // 已经过去
        assertEquals(0L, GoStyleRequestDeadline.remainingMillis());

        // ⚠️ **stub 必须在计时开始前造好**（它要读磁盘上的 yml + 绑属性 ⇒ 几毫秒）
        //    ⇒ ⇒ 否则「耗时」里混着文件读 ⇒ 阈值判据会 flaky。
        ArticleServiceGrpc.ArticleServiceBlockingStub stub = client(null);
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code);
        assertTrue(elapsedMillis < 500L,
                "预算耗尽后必须立刻失败；等满服务端睡眠时间说明根本没有 deadline ⇒ 实测 "
                        + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("⑦ env 把 cap 覆盖【到比 2000 更小】⇒ 出向上界必须跟着收紧 ⇒ **证明域 yml 的这个键真被读到**")
    void envOverrideTightensTheOutboundCap() {
        serverSleepMillis = 5_000L;

        // ⚠️ 同上：stub 先造好（env 覆盖要重新绑属性 ⇒ 也有文件读）⇒ 再开始计时。
        ArticleServiceGrpc.ArticleServiceBlockingStub stub =
                client(Map.of(ENV_CAP, String.valueOf(TIGHTENED_CAP_MILLIS)));
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code, "失败码必须是 DEADLINE_EXCEEDED（= deadline 到点）");
        assertTrue(elapsedMillis < TIGHTENED_CAP_MILLIS + 700L,
                "🔴 env 把 cap 覆盖成 " + TIGHTENED_CAP_MILLIS + "ms ⇒ 出向必须在它附近收口"
                        + "（实测耗时 = " + elapsedMillis + "ms）"
                        + "⇒ ⇒ 若这里等到了 5s 左右 ⇒ 说明【域 yml 的这个键根本没被读到】"
                        + "（cap 仍是默认 0 ＝ 不启用）⇒ ⇒ 键名写错就是【配了不生效】。");
    }

    // ====================================================================================
    // ③ 形态面（点 8）
    // ====================================================================================

    @Test
    @DisplayName("⑧ 本域 withDeadlineAfter 的【代码行】必须为 0 ⇒ 不得再出现域侧自带的上界"
            + "（B 出处：CommentApiRpcDeadlineGateTest:351-377）")
    void domainSideMustNotReintroduceAHardcodedOutboundCap() throws Exception {
        Map<String, Integer> hits = new LinkedHashMap<>();

        try (var walk = Files.walk(moduleRoot().resolve("src").resolve("main").resolve("java"))) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                for (String raw : Files.readString(p, StandardCharsets.UTF_8).split("\\R")) {
                    String line = raw.strip();
                    // ⚠️ 只认【代码行】：javadoc / 行注释里提到 withDeadlineAfter 不算
                    if (line.isEmpty() || line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    if (line.contains("withDeadlineAfter")) {
                        hits.merge(p.getFileName().toString(), 1, Integer::sum);
                    }
                }
            }
        }

        assertEquals(Map.of(), hits,
                "🔴 本域 withDeadlineAfter 的【代码行】分布 = " + hits
                        + "，而已登记的基线 = {}（域侧那一份已于本轮删除）"
                        + "⇒ ⇒ 出向【客户端硬上界】的合法来源"
                        + " = 共享层的 OutboundDeadlineInterceptor（域侧只配 cap-millis）"
                        + "⇒ ⇒ 副本数 = 漂移面 ⇒ ⇒ 这条判据是**点位级棘轮**：只能变紧，不能变松。");
    }

    // ====================================================================================
    // ③ 载体面：载体缺失 ⇒ 明确失败（点 12）
    // ====================================================================================

    @Test
    @DisplayName("⑫ 线程上没有载体 ⇒ 出向**仍套 2s 客户端封顶**（不是「静默无 deadline」）⇒ 且以 DEADLINE_EXCEEDED 明确失败"
            + "（B 出处：CommentOutboundDeadlineCarrierGateTest:536-557）")
    void noCarrierStillGetsTwoSecondClientCapAndFailsExplicitly() {
        assertEquals(-1L, GoStyleRequestDeadline.remainingMillis(),
                "本用例的前提：当前线程**没有**载体（否则测的就不是「无预算」这条路径）");
        assertEquals(-1L, GoStyleRequestDeadline.remainingNanos(),
                "本用例的前提：当前线程**没有**载体（纳秒版同样必须缺席）");

        // ---- ① CallOptions 层面：出向上界必须**存在**且 ≈ 2s（B 侧同款判据）
        Deadline deadline = sharedOutboundDeadline();
        assertNotNull(deadline,
                "🔴 没有请求预算时**也必须**有 deadline（否则出向调用完全没有上限 ⇒ 与 Go 相反）");
        long remaining = deadline.timeRemaining(TimeUnit.NANOSECONDS);
        long twoSeconds = TimeUnit.MILLISECONDS.toNanos(GO_DEFAULT_CAP_MILLIS);
        assertTrue(remaining <= twoSeconds,
                "出向上界必须是 2s 封顶（" + twoSeconds + " ns）");
        assertTrue(remaining > twoSeconds - 100_000_000L,
                "出向上界必须贴近 2s（不能是别的数）");

        // ---- ② 行为面：真调用必须**明确失败**，而不是「静默无 deadline」地跑满服务端睡眠
        serverSleepMillis = 5_000L;
        // ⚠️ **stub 必须在计时开始前造好**（它要读磁盘上的 yml + 绑属性 ⇒ 几毫秒）
        //    ⇒ ⇒ 否则「耗时」里混着文件读 ⇒ 阈值判据会 flaky。
        ArticleServiceGrpc.ArticleServiceBlockingStub stub = client(null);
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code,
                "载体缺失 ⇒ 出向仍套 2s 客户端封顶 ⇒ 必须以 DEADLINE_EXCEEDED 明确失败"
                        + "（若观察到 OK ⇒ 说明出向**完全没有**上限 ⇒ 与 Go 的拦截器级 TimeoutInterceptor 相反）");
        assertTrue(elapsedMillis < 2_400L,
                "必须在 2s 处失败 ⇒ 实测 " + elapsedMillis + "ms");
    }

    // ====================================================================================
    // ④ 阳性对照（点 13）
    // ====================================================================================

    @Test
    @DisplayName("⑬ 阳性对照：cap 配成 0（= 不启用）⇒ 出向**不会**被 2s 掐断（证明上界是机制决定的）")
    void capDisabledMeansNoTwoSecondCut() {
        serverSleepMillis = 2_500L;

        // ---- ① CallOptions 层面：cap=0 且无载体 ⇒ **不得**凭空加出 deadline
        Deadline deadline = sharedOutboundDeadline(0L);
        assertNull(deadline,
                "🔴 cap=0（不启用）且线程上没有载体 ⇒ 出向**不得**有 deadline"
                        + "（B 侧 zrpc 的 WithTimeout 只在 c.Timeout > 0 时才加）"
                        + "⇒ ⇒ 若这里有 deadline ⇒ 共享层给调用凭空加了约束 ⇒ 比 B 侧 更严。");

        // ---- ② 行为面：真调用必须**跑满服务端睡眠**并成功返回（而不是 2s 处被掐断）
        ArticleServiceGrpc.ArticleServiceBlockingStub stub = clientWithCap(0L);
        long start = System.nanoTime();
        Status.Code code = call(stub);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.OK, code,
                "cap=0 ⇒ 没有客户端硬上界 ⇒ 调用应当**成功**（服务端睡完即返回）"
                        + "⇒ ⇒ 若这里拿到 DEADLINE_EXCEEDED ⇒ 说明「2s 上界」不是由 cap 决定的"
                        + "⇒ ⇒ 本组的行为面断言就失去判别力。");
        assertTrue(elapsedMillis >= 2_000L,
                "cap=0 ⇒ 调用必须真的等到服务端把 2.5s 睡完（实测 " + elapsedMillis + "ms）"
                        + "⇒ ⇒ 若 2s 就返回 ⇒ 说明有别的机制在掐（那就不是「cap 决定」）。");
    }

    // ====================================================================================
    // ④ 传播面：请求剩余预算 → 出向 CallOptions（点 15）
    // ====================================================================================

    @Test
    @DisplayName("⑮ 出向 deadline 的**传播**（请求剩余预算 → 出向 CallOptions）确实生效，且按**纳秒**取预算"
            + "（B 出处：CommentOutboundDeadlineCarrierGateTest:486-530）")
    void outboundCarrierBudgetIsPropagatedIntoCallOptions() {
        long carrierNow;
        long outbound;
        long budgetNanos = KNOWN_BUDGET_NANOS;

        // 🔴🔴 **必须先热身**：整条路径（bindAbsoluteNanos → 拦截器读载体 →
        //    Deadline.after → timeRemaining）第一次跑要付
        //    **类加载 + JIT** 的钱（本机实测 ≈ 0.15ms）⇒ ⇒ 那笔钱会**混进 loss**
        //    ⇒ ⇒ 不热身就会读到「loss ≈ 0.15ms」而误判成精度损失。
        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() + budgetNanos)) {
            sharedOutboundDeadline();
        }

        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() + budgetNanos)) {
            // 🔴 **基线**：此刻载体自己还剩多少（在同一线程、同一载体上直接读）。
            carrierNow = GoStyleRequestDeadline.remainingNanos();

            Deadline deadline = sharedOutboundDeadline();
            outbound = deadline == null ? -1L : deadline.timeRemaining(TimeUnit.NANOSECONDS);
        }

        assertTrue(outbound > 0L,
                "出向必须拿到 deadline（无 deadline = 出向完全没上限 ⇒ 与 Go 相反）");
        assertTrue(outbound <= carrierNow,
                "出向上界**不得比载体预算更宽**（Go 侧是 min(父 ctx 剩余, 2s)）");

        long loss = carrierNow - outbound;
        assertTrue(loss < PRECISION_LOSS_TOLERANCE_NANOS,
                "🔴 出向上界相对载体预算的**精度损失**必须 < " + PRECISION_LOSS_TOLERANCE_NANOS
                        + " ns（0.4ms）；预算 " + budgetNanos + " ns（10.9ms）的亚毫秒部分若被毫秒截断"
                        + " ⇒ 损失 ≈ 900000 ns ⇒ 旧实现（读 remainingMillis）会转红");
    }

    @Test
    @DisplayName("⑮ 入向预算**短于** 2s ⇒ 出向上界继承入向剩余（不是无脑套 2s）"
            + "（B 出处：CommentOutboundDeadlineCarrierGateTest:465-480）")
    void outboundInheritsInboundBudgetWhenItIsShorter() {
        long inboundNanos = 1_500_000_000L;   // 1.5s < 2s

        long outbound;
        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() + inboundNanos)) {
            Deadline deadline = sharedOutboundDeadline();
            outbound = deadline == null ? -1L : deadline.timeRemaining(TimeUnit.NANOSECONDS);
        }

        assertTrue(outbound > 0L, "出向必须拿到 deadline");
        assertTrue(outbound <= inboundNanos,
                "🔴 域内出向出口的上界必须**继承**了载体预算（不是无脑套 2s）");
        assertTrue(outbound < TimeUnit.MILLISECONDS.toNanos(GO_DEFAULT_CAP_MILLIS),
                "🔴 入向预算只有 1500ms ⇒ 出向上界必须**明显小于 2s**（否则就是「无脑套 2s」）");
    }

    @Test
    @DisplayName("⑮ 入向预算**长于** 2s ⇒ 出向上界被压到 2s（客户端封顶真的生效，不是原样返回）"
            + "（B 出处：CommentOutboundDeadlineCarrierGateTest:439-463）")
    void outboundIsCappedAtTwoSecondsWhenInboundBudgetIsLonger() {
        long inboundNanos = 5_000_000_000L;   // 5s > 2s

        long outbound;
        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() + inboundNanos)) {
            Deadline deadline = sharedOutboundDeadline();
            outbound = deadline == null ? -1L : deadline.timeRemaining(TimeUnit.NANOSECONDS);
        }

        long twoSeconds = TimeUnit.MILLISECONDS.toNanos(GO_DEFAULT_CAP_MILLIS);
        assertTrue(outbound <= twoSeconds,
                "🔴 入向预算（5000ms）> 2s ⇒ 出向上界必须是 **2s 客户端封顶**"
                        + "（若等于入向预算 ⇒ 说明「根本没压 2s」）");
        assertTrue(outbound > twoSeconds - 100_000_000L,
                "出向上界必须贴近 2s（不能是别的数）");
    }

    // ====================================================================================
    // 装置（续）
    // ====================================================================================

    /**
     * 取「<b>共享层拦截器</b>算出来的那个出向 deadline」—— 生产同款。
     *
     * <p>⚠️ <b>为什么用假 channel 而不是真发一次</b>：拦截器在 {@code interceptCall} 里就把
     * deadline 算好并交给 {@code next.newCall(...)} ⇒ ⇒ 只要 {@code next} 是「只记不真发」，
     * 就能取到<b>真正会落到 wire 的那个</b> {@code CallOptions}
     * ⇒ ⇒ 既不用起第二个服务、也不会自递归。
     *
     * <p>⚠️ <b>为什么用 {@code article.ArticleService/GetArticle} 这个方向</b>：见类 javadoc ①
     * —— B 侧的 comment-api → comment-rpc 链路在 C 侧结构性不存在 ⇒ 换成 C 侧真实出向方向。
     */
    private static Deadline sharedOutboundDeadline() {
        // ⚠️ 用**域 yml 绑出来的**属性造出来的那个**生产同款拦截器实例**
        //    （在 `@BeforeAll` 里造好 ⇒ 测量作用域里**没有文件读**）
        //    ⇒ ⇒ 「域侧只配参数、机制在共享层」这条纪律在测试里也成立。
        return captureOutboundDeadline(sharedInterceptor);
    }

    /**
     * 同上，但用<b>指定的 cap</b> 造拦截器（用于「阳性对照」：cap=0 = 不启用）。
     *
     * @param capMillis 客户端硬上界（毫秒）；{@code 0} = 不启用
     */
    private static Deadline sharedOutboundDeadline(long capMillis) {
        ProtectionProperties props = new ProtectionProperties();
        props.getOutboundDeadline().setCapMillis(capMillis);
        return captureOutboundDeadline(new OutboundDeadlineInterceptor(props));
    }

    /** 取拦截器落到 {@code next.newCall(...)} 的那份 {@code CallOptions} 上的 deadline。 */
    private static Deadline captureOutboundDeadline(OutboundDeadlineInterceptor interceptor) {
        RecordingChannel recorder = new RecordingChannel();
        interceptor.interceptCall(
                ArticleServiceGrpc.getGetArticleMethod(), CallOptions.DEFAULT, recorder);
        CallOptions captured = recorder.captured();
        return captured == null ? null : captured.getDeadline();
    }

    /**
     * 只记 {@code CallOptions} 的假 channel（取拦截器产物用）。
     *
     * <p>⚠️ {@code Channel#newCall} 的 {@code next} 语义：拦截器算完 deadline 后
     * 调 {@code next.newCall(method, effectiveOptions)} ⇒ ⇒ 这里收到的就是
     * <b>真正会落到 wire 的那份 options</b>。
     */
    private static final class RecordingChannel extends Channel {

        private final AtomicReference<CallOptions> captured = new AtomicReference<>();

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
            captured.set(callOptions);
            return new ClientCall<ReqT, RespT>() {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    // 本守门只取 CallOptions ⇒ 不真发 ⇒ 什么都不做
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(String message, Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(ReqT message) {
                }
            };
        }

        @Override
        public String authority() {
            return "recording-channel";
        }

        CallOptions captured() {
            return captured.get();
        }
    }

    /**
     * 用本域<b>自己的</b> {@code application.yml}（<b>磁盘上的那份</b>）绑出
     * {@link ProtectionProperties}。
     *
     * @param envOverrides 模拟的 OS 环境变量（{@code null} ＝ 不覆盖）
     */
    private static ProtectionProperties domainProperties(Map<String, Object> envOverrides) {
        ConfigurableEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            for (PropertySource<?> ps : loader.load("domain-application-yml",
                    new FileSystemResource(domainYamlPath()))) {
                env.getPropertySources().addLast(ps);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读不到本域的 application.yml（" + domainYamlPath() + "）", e);
        }
        if (envOverrides != null && !envOverrides.isEmpty()) {
            // ⚠️ addFirst ⇒ 模拟 OS 环境变量的优先级（env 高于 yml）
            env.getPropertySources().addFirst(new MapPropertySource("simulated-os-env", envOverrides));
        }
        ConfigurationPropertySources.attach(env);
        Binder binder = new Binder(ConfigurationPropertySources.get(env),
                new PropertySourcesPlaceholdersResolver(env));
        return binder.bind("rag.protection", ProtectionProperties.class)
                .orElseGet(ProtectionProperties::new);
    }

    /** 本域 {@code application.yml} 里某个键的<b>未解析</b>原值（占位符仍在）。 */
    private static String rawYamlValue(String key) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("domain-application-yml", new FileSystemResource(domainYamlPath()));
            for (PropertySource<?> ps : sources) {
                Object v = ps.getProperty(key);
                if (v != null) {
                    return String.valueOf(v);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读不到本域的 application.yml（" + domainYamlPath() + "）", e);
        }
        return null;
    }

    /** 本域 {@code application.yml} 的<b>磁盘路径</b>。 */
    private static Path domainYamlPath() {
        return moduleRoot().resolve("src").resolve("main").resolve("resources").resolve("application.yml");
    }

    /**
     * 定位本模块的源码根（含 {@code src/main/java}）—— <b>不依赖 surefire 的工作目录</b>。
     */
    private static Path moduleRoot() {
        try {
            Path loc = Path.of(CommentOutboundDeadlineCapGateTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path p = Files.isDirectory(loc) ? loc : loc.getParent();
            if (p != null && "target".equals(p.getFileName().toString())) {
                p = p.getParent();
            }
            if (p != null && Files.isDirectory(p.resolve("src").resolve("main").resolve("java"))) {
                return p;
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        Path cur = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && cur != null; up++) {
            if (Files.isDirectory(cur.resolve("src").resolve("main").resolve("java"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("定位不到本模块的源码根（src/main/java）"
                + "⇒ ⇒ 形态面判据失效（判据自己的问题，不是被测物的问题）");
    }
}
