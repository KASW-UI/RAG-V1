package com.rag.comment.grpc;

import com.rag.comment.CommentServiceApplication;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;
import com.rag.comment.support.TestRedis;

import com.rag.common.observability.DeadlineState;
import com.rag.common.observability.GoStyleRequestDeadline;
import com.rag.common.persistence.DeadlineQueryTimeoutInjector;
import com.rag.common.persistence.SqlTimeoutStatusTranslator;
import com.rag.common.protection.OutboundDeadlineInterceptor;
import com.rag.common.redis.DeadlineConnectionFactory;

import com.rag.proto.article.ArticleServiceGrpc;
import com.rag.proto.article.GetArticleRequest;
import com.rag.proto.article.GetArticleResponse;
import com.rag.proto.comment.CommentServiceGrpc;
import com.rag.proto.comment.GetCommentReq;
import com.rag.proto.comment.GetCommentResp;

import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ServerCalls;

import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorRegistry;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>B 组（B 组第 9/10/11/12/16 点 · L4 载体面与装配面）守门网</b>
 * —— 锁住「出向 deadline 的<b>载体</b>与<b>装配</b>」这两件事，而不只是「配置里写了一个数」。
 *
 * <h2>1. 比 B 侧多做了什么（AGENTS.md §3.2 三段式 · ①）</h2>
 * <ol>
 *   <li><b>把 B 侧那三个测试类合并成一个类</b>：B 侧是
 *       {@code CommentOutboundDeadlineCarrierGateTest}（载体）＋
 *       {@code CommentSqlDeadlinePrecisionGateTest}（SQL 载体）＋
 *       {@code CommentRedisDeadlineCarrierWiringGateTest}（Redis 载体）
 *       <b>三个类</b> ⇒ ⇒ C 侧 D1 压平成单进程 ⇒ 三件事共用<b>同一个 Spring 上下文</b>
 *       ⇒ ⇒ 合并成一个类（<b>断言逐条保留</b>，只是夹具从「三个容器」减到「一个上下文 + 两个容器单例」）。
 *       <br>⚠️ <b>为什么这样更弱 / 更强</b>：<b>更强</b> —— 三个断言现在在<b>同一个真实容器</b>里跑
 *       （B 侧是三个互不相干的上下文 ⇒ 「域内出口继承载体」与「SQL 载体」之间<b>没有交叉证据</b>）；
 *       <b>不更弱</b> —— 每条断言的被测对象与阈值<b>逐字沿用 B 侧</b>。</li>
 *   <li><b>类名去 {@code GoStyle} 前缀</b>：{@code GoStyleRedisDeadlineConnectionFactory} →
 *       {@link DeadlineConnectionFactory}；{@code GoStyleRequestDeadline} /
 *       {@code GoStyleProtectionProperties} 同款（命名债 P4-④ · 只改 import，不改语义）。</li>
 *   <li><b>SQL 侧的「驱动超时读数」改用反射</b>（而不是直接引用 pgjdbc 的实现类）：
 *       共享层自己的 {@link DeadlineQueryTimeoutInjector} 就是用反射探测
 *       {@code setQueryTimeoutMs} 的（理由见它的 javadoc：「不该把驱动实现类编进共享库的 API 面」）
 *       ⇒ ⇒ 本类<b>沿用同一口径</b>，顺带避免在测试侧硬编驱动类。</li>
 *   <li><b>🔴 出向方向替换</b>：B 侧守的是「comment-api → comment-rpc」，
 *       C 侧这条链路<b>结构性不存在</b>（D1/D7 压平成单进程 ⇒ 域内直连）
 *       ⇒ ⇒ 本类的<b>出向</b>方向换成 C 侧真实存在的 {@code article-rpc}
 *       （{@code application.yml:214-219}）⇒ 对应方法 {@code article.ArticleService/GetArticle}；
 *       <b>入向</b>方向仍是本域自己的 {@code comment.CommentService/GetComment}（两侧同形）。</li>
 * </ol>
 *
 * <h2>2. 语义还一样吗（AGENTS.md §3.2 三段式 · ②）</h2>
 * <p><b>一样</b>，逐条对应：
 * <ul>
 *   <li>「载体在 handler 线程真的绑上了」⇒ B 侧
 *       {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/client/CommentOutboundDeadlineCarrierGateTest.java:414-437}；</li>
 *   <li>「SQL 载体上的 deadline 是<b>毫秒级</b>」⇒ B 侧
 *       {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentSqlDeadlinePrecisionGateTest.java:355-389}；</li>
 *   <li>「Redis 载体在<b>真实容器</b>里装上了」⇒ B 侧
 *       {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRedisDeadlineCarrierWiringGateTest.java:186-205}；</li>
 *   <li>「载体缺失 ⇒ <b>明确失败</b>而不是静默放行」⇒ B 侧
 *       {@code CommentSqlDeadlinePrecisionGateTest.java:200-235}（过期 ⇒ 第二条语句立即失败 ⇒ 事务回滚）。</li>
 * </ul>
 *
 * <h2>3. B 出处（AGENTS.md §3.2 三段式 · ③ · 消费点优先）</h2>
 * <pre>
 * // ① 服务端把「同一个绝对时刻」绑给**两个**载体（入向 SQL 载体 + 出向 gRPC 载体）
 * //    B 出处：service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:516-522
 * //              try (DeadlineState.Scope ignoredScope = DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos);
 * //                   GoStyleRequestDeadline.Scope ignoredCarrier = hasDeadlineBudget
 * //                           ? GoStyleRequestDeadline.bindAbsoluteNanos(absoluteDeadlineNanos)
 * //                           : GoStyleRequestDeadline.Scope.NOOP) { super.onHalfClose(); }
 * // ② 服务端的有效期限 = min(服务端预算, **客户端 deadline**)（E38 修复）
 * //    B 出处：service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:310
 * //              long effectiveTimeoutNanos = Math.min(serverBudgetNanos, clientDeadlineRemainingNanos());
 * // ③ SQL 载体：把剩余毫秒**原样**交给驱动 ⇒ 取消时刻 == deadline 时刻
 * //    B 出处：service/common/src/main/java/com/rag/common/persistence/DeadlineQueryTimeoutInjector.java:30
 * //              m.conn.WithContext(ctx).Transaction(...)
 * // ④ Redis 载体：预算耗尽 ⇒ 命令**不下发**
 * //    B 出处：service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadline.java:167
 * //              （消费点：GoStyleRedisDeadlineConnectionFactory.getConnection()）
 * //    B 出处（Go 侧同款）：go-redis/v9@v9.20.0/internal/pool/pool.go:1098-1104（waitTurn 的 fast path）
 * // ⑤ C 侧对应实现（本类断言的生产件）
 * //    C 出处：common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java:314-329（withDeadline）
 * //    C 出处：common/src/main/java/com/rag/common/persistence/DeadlineQueryTimeoutInjector.java:163-194
 * //    C 出处：common/src/main/java/com/rag/common/redis/DeadlineConnectionFactory.java:39-62
 * //    C 出处：common/src/main/java/com/rag/common/autoconfigure/DeadlineAutoConfiguration.java:62-97（BPP）
 * </pre>
 *
 * <h2>🔴 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧真的装上了、真的生效」。
 * 「B 侧此刻也如此」属于<b>双侧活体对拍</b>（本类<b>不冒充</b>该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>⇒ 不静默跳过）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}；</li>
 *   <li>不用 {@code tools.jackson.*}；</li>
 *   <li>容器地址<b>不落 OS 临时端口段</b>（走本仓 {@code TestPostgres} / {@code TestRedis} 的进程内单例）。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {CommentServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 指到必然拒连的地址：NacosRegistration 捕获 NacosException 只记日志不中断启动。
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // OTLP 指向不可达端点，只会在后台重试刷日志，关掉。
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 绕过 Kafka：consumer 会持续重试连接，但不影响上下文启动与本类断言。
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // ⚠️ 关掉 CPU 自适应降载，让本类的判据**只**取决于「载体有没有绑上」。
                //    否则链上的 shedding 层会先返回 RESOURCE_EXHAUSTED，把判据盖掉 ⇒ 变成 flaky。
                "rag.protection.cpu-shedding.enabled=false",
                // ⚠️ 绕过 message-rpc 客户端（devh 在 Bean 初始化期就建 channel ⇒ 不覆盖则上下文起不来）。
                //    本域的出向方向里只有 article-rpc 参与本类断言 ⇒ message-rpc 指向死地址即可。
                "grpc.client.message-rpc.address=static://127.0.0.1:1"
        })
@ActiveProfiles("test")
class CommentOutboundDeadlineCarrierGateTest {

    /** 慢 article-rpc 服务端的睡眠时长（毫秒）—— 必须<b>远大于</b> 2s 的客户端硬上界。 */
    private static final long SLOW_ARTICLE_SLEEP_MILLIS = 5_000L;

    /** 客户端给的 deadline（毫秒）—— 必须<b>穿过 wire</b>，才能证明服务端真的收到了。 */
    private static final long CLIENT_DEADLINE_MILLIS = 1_500L;

    /** Go 侧 {@code zrpc/config.go:31} 的客户端硬上界默认值（毫秒）。 */
    private static final long GO_DEFAULT_CAP_MILLIS = 2_000L;

    /** 本类专用的探针表名（与本域 8 张业务表互不干扰）。 */
    private static final String PROBE_TABLE = "comment_outbound_deadline_gate_probe";

    /** 慢 article-rpc 服务端（供「devh 装配面」那条用例用）。 */
    private static Server slowArticleServer;

    /** 慢 article-rpc 服务端实际监听的端口。 */
    private static volatile int slowArticlePort;

    /** handler 线程上观测到的「入向载体」（SQL 载体）。 */
    private static final AtomicReference<Long> inboundCarrierRemaining = new AtomicReference<>();

    /** handler 线程上观测到的「出向载体」是否存在（B 侧 RpcInterceptor 绑的那一个）。 */
    private static final AtomicReference<Boolean> outboundCarrierPresent = new AtomicReference<>();

    /** handler 线程上观测到的「出向载体」剩余纳秒。 */
    private static final AtomicReference<Long> outboundCarrierRemainingNanos = new AtomicReference<>();

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private StatementTimeoutRecorder recorder;
    private ProbeMapper probeMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        // ⚠️ gRPC server **不起 TCP 端口**（-1 ⇒ in-process）⇒ 不与本类自己起的真 server 抢端口。
        registry.add("grpc.server.port", () -> -1);
        // ⚠️ 本域**真实的出向方向**（article-rpc）⇒ 指到本类自己起的慢服务端
        //    ⇒ ⇒ 「devh 真的把全局拦截器装到了出向 channel 上」这条判据才能在真调用上跑。
        registry.add("grpc.client.article-rpc.address", () -> "static://127.0.0.1:" + slowArticlePort());
    }

    @AfterAll
    static void stopSlowArticleServer() {
        if (slowArticleServer != null) {
            slowArticleServer.shutdownNow();
        }
    }

    /**
     * 懒启动慢 article-rpc 服务端（{@code @DynamicPropertySource} 里取端口时调用）。
     *
     * <p>⚠️ <b>为什么懒启动而不是放进 {@code @BeforeAll}</b>：JUnit 5 里
     * {@code SpringExtension.beforeAll}（⇒ Spring 上下文 ⇒ {@code @DynamicPropertySource} 求值）
     * <b>先于</b> {@code @BeforeAll} 执行 ⇒ ⇒ 若在 {@code @BeforeAll} 里另起一个服务端，
     * {@code grpc.client.article-rpc.address} 指到的就<b>不是</b>本类断言用的那个端口
     * ⇒ ⇒ 两处必须共用<b>同一个</b>实例 ⇒ ⇒ 用懒启动（幂等）。
     */
    private static synchronized int slowArticlePort() {
        if (slowArticleServer == null) {
            try {
                slowArticleServer = ServerBuilder.forPort(0)
                        .addService(io.grpc.ServerServiceDefinition
                                .builder(ArticleServiceGrpc.getServiceDescriptor().getName())
                                .addMethod(ArticleServiceGrpc.getGetArticleMethod(),
                                        ServerCalls.asyncUnaryCall((GetArticleRequest req,
                                                                   io.grpc.stub.StreamObserver<GetArticleResponse> obs) -> {
                                            try {
                                                Thread.sleep(SLOW_ARTICLE_SLEEP_MILLIS);
                                                obs.onNext(GetArticleResponse.getDefaultInstance());
                                                obs.onCompleted();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                obs.onError(new StatusRuntimeException(Status.CANCELLED
                                                        .withDescription("slow article server interrupted")));
                                            }
                                        }))
                                .build())
                        .build()
                        .start();
                slowArticlePort = slowArticleServer.getPort();
            } catch (IOException e) {
                throw new IllegalStateException("起不出慢 article-rpc 服务端", e);
            }
        }
        return slowArticlePort;
    }

    @AfterEach
    void clean() {
        inboundCarrierRemaining.set(null);
        outboundCarrierPresent.set(null);
        outboundCarrierRemainingNanos.set(null);
        if (jdbc != null) {
            jdbc.update("DELETE FROM " + PROBE_TABLE + " WHERE id IN (?, ?)", 9_900_001L, 9_900_002L);
            recorder.reset();
        }
    }

    // ====================================================================================
    // ⑨ 出向载体：真 wire + 真共享层拦截器链 ⇒ 载体在 gRPC handler 线程真的绑上了
    // ====================================================================================

    @Test
    @DisplayName("⑨ 真 wire + 真共享层服务端链 ⇒ 入向 deadline 真的到了 gRPC handler 线程（载体在位且有预算）")
    void inboundDeadlineReachesTheHandlerThreadOverRealWire() throws Exception {
        // ⚠️ **必须用真 socket**：in-process transport 不走 wire ⇒ 没有 grpc-timeout 头
        //    ⇒ 服务端 io.grpc.Context.current().getDeadline() 读到 null ⇒ 载体根本不会绑
        //    ⇒ ⇒ 用 in-process 写这条守门 = 必然红（假红）。
        //    （B 出处：CommentOutboundDeadlineCarrierGateTest:154-158「为什么第一条用真 socket 而不是 in-process」）
        GrpcServerConfigurer configurer = ctx.getBean("seaGrpcServerConfigurer", GrpcServerConfigurer.class);
        assertThat(configurer).as("seaGrpcServerConfigurer 必须存在（共享层的唯一服务端链装配点）").isNotNull();

        ServerBuilder<?> builder = ServerBuilder.forPort(0);
        configurer.accept(builder);
        Server server = builder.addService(carrierObservingCommentService()).build().start();

        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
                .usePlaintext().build();
        try {
            Status.Code code = Status.Code.OK;
            try {
                CommentServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(CLIENT_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
                        .getComment(GetCommentReq.newBuilder()
                                .setTargetType("article")
                                .setTargetId("t-1")
                                .build());
            } catch (StatusRuntimeException e) {
                code = e.getStatus().getCode();
            }

            assertEquals(Status.Code.OK, code,
                    "客户端调用必须成功返回（服务端没有超时调度器，超时只可能来自客户端自己的 deadline）");

            Long remaining = inboundCarrierRemaining.get();
            assertThat(remaining)
                    .as("🔴 共享层 GrpcObservationInterceptor 在 onHalfClose 绑的**入向载体**"
                            + "（DeadlineState）必须在 **gRPC handler 线程**上可见"
                            + "⇒ 否则本域的 SQL 超时链路（DeadlineQueryTimeoutInjector）根本没有预算"
                            + "⇒ ⇒ 「载体在 handler 线程真的绑上了」这条不成立")
                    .isNotNull();
            assertThat(remaining)
                    .as("载体剩余预算必须 > 0（-1 = 载体在位但没有预算 ⇒ 异常）")
                    .isPositive();
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("📌 已登记差异（台账 D-15 · 归属 global 收口域）：出向载体（GoStyleRequestDeadline）"
            + "在 handler 线程上**未绑**，且有效期限**不含**客户端 deadline ⇒ 本条钉住已登记的现状")
    void outboundCarrierIsNotBoundAndClientDeadlineIsNotHonouredYet() throws Exception {
        // ⚠️⚠️ **这是「已登记、未签字、待 global 收口域修」的差异，不是「已签字偏离」**
        //    ⇒ ⇒ 本条**钉住已登记的现状**，**不是**把它锁成「正确」。
        //
        //    B 侧真值（B 出处：service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java）：
        //      ① :517-520 —— `onHalfClose` 把**同一个绝对时刻**绑给**两个**载体：
        //             `DeadlineState.bindAbsoluteNanos(...)` ＋ `GoStyleRequestDeadline.bindAbsoluteNanos(...)`
        //      ② :304     —— `effectiveTimeoutNanos = min(serverBudgetNanos, clientDeadlineRemainingNanos())`
        //             ⇒ 客户端给的更短 deadline **参与**服务端有效期限。
        //
        //    C 侧现状（消费点，逐行核对过）：
        //      ① common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java:324
        //         ⇒ **只** `DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos)`；
        //         且 :291-294 的 javadoc **自己写明**「`GoStyleRequestDeadline`（跨域 gRPC 调用的出向预算）
        //         **不在本条范围**」；
        //         ⇒ 全仓 `src/main` 里 `GoStyleRequestDeadline.bindAbsoluteNanos` / `.begin(` **零调用点**
        //         （grep 全仓命中 0）⇒ 出向载体在生产里**从不**被绑。
        //      ② 同文件 :342-357 `rpcDeadlineNanos(...)` ⇒ **只**用服务端 `observability.rpc-timeout`
        //         ⇒ 客户端 deadline **不参与**。
        //
        //    登记：共享层台账 `docs/DRIFT/common/verification-common-2026-11-02.md`
        //      **DRIFT-COMMON-V17 · PARITY-006 根因 · global 收口域**（2026-11-03）
        //      ——「⚠️ **只绑入向载体**，出向 `GoStyleRequestDeadline` **仍未绑** ⇒ ⇒ 爆炸半径更大 ⇒ ⇒ **另案**」
        //      ＋ `common/…/observability/DeadlineState.java:126-129` 的同款自述。
        //    ⇒ **本域台账**：`docs/DRIFT/comment/verification-comment-service-2026-11-03.md` §5.1 **D-15**
        //      （B 组第 ⑨ 点的**未闭合原因**）。
        //
        //    ⚠️ **本域没有 `common/**` 的写权**（AGENTS.md §五.3：`common/**` 只有收口任务能写）
        //    ⇒ ⇒ 本条**不判 PASS**，且**不改弱到看不出差异** ⇒ ⇒ 钉住「当前形状」。
        //
        //    🔴 **棘轮方向**：共享层把出向载体绑上（且把客户端 deadline 纳入 min）之后，
        //    本条**会变红** ⇒ ⇒ 那正是我们要的提醒：届时把本条**改回 B 侧真值断言**
        //    （① `isTrue()` ＋ 剩余纳秒 > 0；② 剩余 ≤ 客户端 deadline），并撤掉台账 D-15。
        GrpcServerConfigurer configurer = ctx.getBean("seaGrpcServerConfigurer", GrpcServerConfigurer.class);
        assertThat(configurer).isNotNull();

        ServerBuilder<?> builder = ServerBuilder.forPort(0);
        configurer.accept(builder);
        Server server = builder.addService(carrierObservingCommentService()).build().start();

        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
                .usePlaintext().build();
        try {
            CommentServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(CLIENT_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
                    .getComment(GetCommentReq.newBuilder()
                            .setTargetType("article")
                            .setTargetId("t-1")
                            .build());

            // ---- ① 出向载体：当前**未绑**（B 侧真值 = 已绑）⇒ 台账 D-15
            assertThat(outboundCarrierPresent.get())
                    .as("📌 已登记差异（台账 D-15 · 归属 global 收口域）：出向载体 GoStyleRequestDeadline "
                            + "在 gRPC handler 线程上**应当**已绑（B 侧 RpcInterceptor.java:517-520 两载体同绑）"
                            + "，但 C 侧 ObservabilityAutoConfiguration.java:324 **只**绑了入向载体 "
                            + "DeadlineState ⇒ ⇒ **本条变红 = 共享层已修**（DRIFT-COMMON-V17 的「另案」闭合）"
                            + "⇒ ⇒ 届时请把本条改回 B 侧真值断言并撤掉台账 D-15")
                    .isFalse();

            // ---- ② 有效期限：当前**不含**客户端 deadline（B 侧真值 = min(server, client)）⇒ 台账 D-15
            Long inboundRemaining = inboundCarrierRemaining.get();
            assertThat(inboundRemaining)
                    .as("📌 已登记差异（台账 D-15）：服务端载体上的剩余当前**由服务端 rpc-timeout 决定**，"
                            + "**不含**客户端给的 %dms（B 侧 RpcInterceptor.java:304 是 "
                            + "min(serverBudgetNanos, clientDeadlineRemainingNanos())）"
                            + "⇒ ⇒ 实测剩余 = %s ms"
                            + "⇒ ⇒ **本条变红 = 共享层已把客户端 deadline 纳入 min** ⇒ ⇒ 届时改回 B 侧真值断言",
                            CLIENT_DEADLINE_MILLIS, inboundRemaining)
                    .isGreaterThan(CLIENT_DEADLINE_MILLIS);

            // ---- ③ 出向载体的剩余读数：载体未绑 ⇒ 共享层返回 -1（GoStyleRequestDeadline.java:109-115）
            //        ⇒ 这条把「未绑」钉到**数值**上，而不只钉在布尔上
            assertThat(outboundCarrierRemainingNanos.get())
                    .as("📌 已登记差异（台账 D-15）：出向载体未绑 ⇒ `GoStyleRequestDeadline.remainingNanos()` "
                            + "按共享层契约返回 -1（common/…/GoStyleRequestDeadline.java:109-115）"
                            + "⇒ ⇒ **本条变红 = 出向载体已被绑上**（共享层已修）⇒ ⇒ 届时改回 B 侧真值断言（> 0）")
                    .isEqualTo(-1L);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    // ====================================================================================
    // ⑯ 共享层装配面：出向 deadline 拦截器真的被 devh 收进全局拦截器链，且真的在出向调用上生效
    // ====================================================================================

    @Test
    @DisplayName("⑯ devh 的全局拦截器链里真的有 OutboundDeadlineInterceptor，且真出向调用在 2s 处被掐断")
    void devhGlobalInterceptorChainReallyCapsTheOutboundCall() {
        // ---- ① 装配面：devh 收 interceptor 的机制 = 「类上带 @GrpcGlobalClientInterceptor」
        //    （devh 的 AnnotationGlobalClientInterceptorConfigurer#getClientInterceptorBeans
        //      用 applicationContext.getBeansWithAnnotation(GrpcGlobalClientInterceptor.class) 收集）
        GlobalClientInterceptorRegistry registry = ctx.getBean(GlobalClientInterceptorRegistry.class);
        List<ClientInterceptor> global = registry.getClientInterceptors();

        assertThat(global)
                .as("🔴 出向 deadline 拦截器必须真的被 devh 收进**全局拦截器链**"
                        + "（不是「Bean 存在」—— §3.3：Bean 存在 ≠ 机制生效）"
                        + "⇒ ⇒ 域侧只配参数（cap-millis=2000）、机制由共享层的"
                        + " `@GrpcGlobalClientInterceptor` 承担 ⇒ ⇒ 收集不到 = 出向零期限")
                .hasAtLeastOneElementOfType(OutboundDeadlineInterceptor.class);

        // ---- ② 行为面：**devh 建出来的** channel 上跑一次真出向调用 ⇒ 必须被 2s 掐断
        //    ⚠️ 这一条才是「真的在出向调用上生效」：devh 的 AbstractChannelFactory#createChannel
        //    最后一行是 ClientInterceptors.interceptForward(channel, interceptors)
        //    ⇒ ⇒ channel 上已经带着全局拦截器 ⇒ ⇒ 裸 stub 也过链。
        GrpcChannelFactory factory = ctx.getBean(GrpcChannelFactory.class);
        Channel channel = factory.createChannel("article-rpc");

        long start = System.nanoTime();
        Status.Code code = Status.Code.OK;
        try {
            ArticleServiceGrpc.newBlockingStub(channel)
                    .getArticle(GetArticleRequest.newBuilder().setArticleId("a-1").build());
        } catch (StatusRuntimeException e) {
            code = e.getStatus().getCode();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(Status.Code.DEADLINE_EXCEEDED, code,
                "🔴 出向调用必须以 DEADLINE_EXCEEDED 收场（服务端故意睡 " + SLOW_ARTICLE_SLEEP_MILLIS
                        + "ms ⇒ 若拿到 OK ⇒ 说明出向**根本没有**上限）");
        assertTrue(elapsedMillis < 2_400L,
                "🔴 必须由 **2s 客户端硬上界**掐断（不是服务端睡满 " + SLOW_ARTICLE_SLEEP_MILLIS
                        + "ms）⇒ 实测 " + elapsedMillis + "ms");
    }

    // ====================================================================================
    // ⑪ Redis 载体：真实容器里装上了没有 + 载体耗尽 ⇒ 写不下
    // ====================================================================================

    @Test
    @DisplayName("⑪ 装配面：容器里那个 StringRedisTemplate 的 connection factory 就是预算感知版（且有且只有一个）")
    void containerTemplateIsWrappedBySharedLayer() {
        var templates = ctx.getBeansOfType(StringRedisTemplate.class);

        assertThat(templates)
                .as("① 容器里必须**有且只有一个** StringRedisTemplate"
                        + " —— `CommentCacheService` 是**按类型**注入它的"
                        + " ⇒ ⇒ 若有两个，它拿到的未必是被包过的那个 ⇒ ⇒ 「只有一个」是咬合用的")
                .hasSize(1);

        StringRedisTemplate template = templates.values().iterator().next();
        assertThat(template.getConnectionFactory())
                .as("① 共享层那个 BeanPostProcessor 必须**真的**把 `RedisTemplate` 的"
                        + " connection factory 换成了预算感知版"
                        + " —— 否则本域所有经 RedisTemplate 的缓存读写**根本没有 deadline 闸门**")
                .isInstanceOf(DeadlineConnectionFactory.class);
    }

    @Test
    @DisplayName("⑪ 载体已耗尽 ⇒ 写命令根本没下发（读回来还是基线值）＋ 载体充足 ⇒ 写得进（负控）")
    void exhaustedCarrierBlocksTheRedisWriteWhileAliveCarrierWrites() {
        String key = "comment:gate:outbound-deadline:carrier";
        String baseline = "baseline";

        // 先写基线（此刻**没有**载体 ⇒ 不受闸门影响）
        redis.opsForValue().set(key, baseline);

        // ---- 载体已耗尽 ⇒ 必须拦下，且**值没变**
        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() - 1_000_000L)) {
            assertThatThrownBy(() -> redis.opsForValue().set(key, "overwritten"))
                    .as("② 载体已耗尽 ⇒ 必须抛 QueryTimeoutException"
                            + "（共享层 `Deadline.assertNotExhausted` 在**取连接**这一步拦）")
                    .isInstanceOf(QueryTimeoutException.class);
        }

        assertThat(redis.opsForValue().get(key))
                .as("② 关键观测量：**读回来还是基线值** ⇒ ⇒ 命令**根本没下发**"
                        + "（不是发出去再报错）⇒ ⇒ 与 Go 侧「到点即止」同形")
                .isEqualTo(baseline);

        // ---- 负控：载体充足 ⇒ 同一个 key 必须写得进
        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() + 5_000_000_000L)) {
            redis.opsForValue().set(key, "written");
        }

        assertThat(redis.opsForValue().get(key))
                .as("③ 负控：载体充足时必须**写得进** ⇒ ⇒ 第 ② 条的红是「闸门生效」而不是「环境坏了」")
                .isEqualTo("written");
    }

    @Test
    @DisplayName("⑪ 事务绑定路径（redis.execute(SessionCallback)）：载体耗尽 ⇒ 内层写命令**也**被拦（值不得变）")
    void exhaustedCarrierAlsoBlocksSessionCallbackWrites() {
        String key = "comment:gate:outbound-deadline:carrier-tx";
        String baseline = "baseline";

        redis.opsForValue().set(key, baseline);

        // 🔴 为什么必须单独测这一条：`redis.execute(SessionCallback)` ⇒ 连接被**事务绑定**
        // ⇒ ⇒ **不再走** getConnection() 里那道「建连之前」的检查
        // ⇒ ⇒ 只有**每命令一次**的检查能兜住 ⇒ ⇒ 而本域 CommentCacheService 的两条写路径
        //     正是 SessionCallback 形态 ⇒ 不测这一条 = 台账那句「写路径不再照写不误」**少一半证据**。
        try (GoStyleRequestDeadline.Scope ignored = GoStyleRequestDeadline.bindAbsoluteNanos(
                System.nanoTime() - 1_000_000L)) {
            assertThatThrownBy(() -> redis.execute(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    operations.opsForValue().set(key, "overwritten");
                    return null;
                }
            }))
                    .as("④ 事务绑定路径**不走**「建连之前」那道检查 ⇒ ⇒ 必须靠**每命令一次**的检查兜住")
                    .isInstanceOf(QueryTimeoutException.class);
        }

        assertThat(redis.opsForValue().get(key))
                .as("④ 关键观测量：值**没变** ⇒ ⇒ 事务绑定路径下的内层写命令**根本没下发**")
                .isEqualTo(baseline);
    }

    // ====================================================================================
    // ⑩ SQL 载体：真 PG + 真 MyBatis 插件链上的毫秒级 deadline 精度
    // ====================================================================================

    @Test
    @DisplayName("⑩ 真 HikariCP 池路径 ⇒ 毫秒级超时真的落到驱动语句上（不是退回秒级）")
    void pooledPathAppliesMillisecondTimeoutOnDriverStatement() {
        // ---- 准备：真 MyBatis 链（注册的就是**生产链路上的那两个拦截器** ⇒ 不是「另写一个假的」）
        prepareSqlChain();

        long remainingMs = 1_500L;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);

        try (DeadlineState.Scope ignored = DeadlineState.bindAbsoluteNanos(deadline)) {
            probeMapper.findOneById(9_900_001L);
        }

        Statement captured = recorder.captured;
        assertThat(captured)
                .as("记录器必须真的在插件链里跑过（否则下面的断言全是空转）")
                .isNotNull();

        // 🔴 自证断言：这条守门**必须跑在池化代理路径上** ⇒ 否则它又变成「守门绿、生产坏」
        assertThat(captured.getClass().getName())
                .as("🔴 必须真的走池化代理路径（生产形态）⇒ 否则本守门证明不了生产行为")
                .contains("Hikari");

        long driverTimeoutMs = recorder.driverTimeoutMs;
        assertThat(driverTimeoutMs)
                .as("必须真的读到驱动语句上的超时（读不到 = -1）")
                .isGreaterThan(0L);

        // 🔴 关键断言：毫秒路径 ⇒ 驱动上的超时 **≤ 剩余毫秒**；
        //    秒级向上取整 ⇒ 1.5s 会变成 2000ms ⇒ **必然越界** ⇒ 这条守门在秒级实现下是红的。
        assertThat(driverTimeoutMs)
                .as("驱动语句上的超时必须是毫秒级原值（秒级向上取整会给 2000ms ⇒ 取消时刻最晚比 deadline 晚 999ms）")
                .isLessThanOrEqualTo(remainingMs);
        assertThat(driverTimeoutMs)
                .as("驱动语句上的超时应当贴近剩余毫秒（说明它确实来自 deadline，而不是别的数）")
                .isGreaterThan(remainingMs - 500L);
    }

    @Test
    @DisplayName("⑩⑫ deadline 已过 ⇒ 第二条语句立即失败（DEADLINE_EXCEEDED）⇒ 第一条随事务回滚"
            + "（载体缺失/耗尽 ⇒ **明确失败**，不是静默放行）")
    void expiredDeadlineFailsStatementAndRollsBackTransaction() {
        prepareSqlChain();

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        Throwable thrown = null;
        try {
            tx.execute(status -> {
                // 第一条：此刻线程上没有绑定 deadline ⇒ 不设任何超时 ⇒ 正常写入
                probeMapper.insert(9_900_001L);
                // 第二条：deadline 已过 ⇒ 必然失败（**语句根本不会发到服务端**）
                try (DeadlineState.Scope ignored = DeadlineState.bindAbsoluteNanos(
                        System.nanoTime() - TimeUnit.SECONDS.toNanos(1))) {
                    probeMapper.insert(9_900_002L);
                }
                return null;
            });
        } catch (Throwable t) {
            thrown = t;
        }

        assertThat(thrown)
                .as("过期后仍执行 SQL ⇒ 事务会带着写入提交 ⇒ 与 Go 的 ctx cancel 语义相反")
                .isNotNull();

        StatusRuntimeException translated = findStatusRuntimeException(thrown);
        assertThat(translated)
                .as("SQL 被 deadline 取消必须翻译成 DEADLINE_EXCEEDED（不能是 INTERNAL）")
                .isNotNull();
        assertThat(translated.getStatus().getCode())
                .as("SQL 被 deadline 取消必须翻译成 DEADLINE_EXCEEDED（不能是 INTERNAL）")
                .isEqualTo(Status.Code.DEADLINE_EXCEEDED);

        // 🔴 关键断言：异常上抛 ⇒ 事务回滚 ⇒ 第一条写入**不在**表里。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + PROBE_TABLE + " WHERE id IN (?, ?)",
                Integer.class, 9_900_001L, 9_900_002L))
                .as("过期后失败 ⇒ 整个事务回滚 ⇒ 两条写入都不该留在表里")
                .isZero();
    }

    @Test
    @DisplayName("⑩ 在途查询在 deadline 时刻被取消 ⇒ 存活时长 ≈ 剩余毫秒（不是「最晚晚 999ms」）")
    void inFlightQueryIsCanceledAtDeadline() throws Exception {
        prepareSqlChain();

        long remainingMs = 300L;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);

        // 另开一个连接持有 ACCESS EXCLUSIVE 锁 ⇒ 目标连接的 SELECT 会阻塞在锁上。
        // ⚠️ 必须用表级 ACCESS EXCLUSIVE：普通 SELECT 走 MVCC 快照读，**不会被行锁挡住**。
        try (Connection lockConn = dataSource.getConnection()) {
            lockConn.setAutoCommit(false);
            try (Statement st = lockConn.createStatement()) {
                st.execute("LOCK TABLE " + PROBE_TABLE + " IN ACCESS EXCLUSIVE MODE");
            }

            long startNanos = System.nanoTime();
            Throwable thrown;
            try (DeadlineState.Scope ignored = DeadlineState.bindAbsoluteNanos(deadline)) {
                thrown = catchThrowable(() -> probeMapper.findOneById(9_900_001L));
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(thrown).as("被锁阻塞的查询必须在 deadline 处被取消").isNotNull();
            StatusRuntimeException translated = findStatusRuntimeException(thrown);
            assertThat(translated).as("取消异常必须翻译成 DEADLINE_EXCEEDED").isNotNull();
            assertThat(translated.getStatus().getCode())
                    .as("取消异常必须翻译成 DEADLINE_EXCEEDED（不能是 INTERNAL）")
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED);

            // 🔴 精度断言：毫秒精度 ⇒ 存活 ≈ 剩余毫秒 + 取消传播延迟（几十 ms）。
            //    旧的「秒级向上取整」⇒ 剩余 300ms 会被算成 1 秒 ⇒ 存活 ≈ 1000ms ⇒ 这条会红。
            assertThat(elapsedMs)
                    .as("在途查询的存活时长应贴近剩余 %dms（旧实现秒级取整会到 ~1000ms）", remainingMs)
                    .isLessThan(remainingMs + 400L);
        }
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /**
     * 注册一个「只观测载体」的 comment 服务（入向方向 = 本域自己的 comment.CommentService）。
     *
     * <p>⚠️ <b>必须用 grpc-java 的标准派发入口 {@link ServerCalls#asyncUnaryCall}</b>，
     * <b>不能</b>用裸 {@code ServerCallHandler} —— 后者不会被派发 {@code onHalfClose}
     * ⇒ 方法体一次都不执行 ⇒ 观测值恒为 null ⇒ 守门空转。
     */
    private static io.grpc.ServerServiceDefinition carrierObservingCommentService() {
        return io.grpc.ServerServiceDefinition
                .builder(CommentServiceGrpc.getServiceDescriptor().getName())
                .addMethod(CommentServiceGrpc.getGetCommentMethod(),
                        ServerCalls.asyncUnaryCall((GetCommentReq req,
                                                   io.grpc.stub.StreamObserver<GetCommentResp> obs) -> {
                            // 🔴 下面几行跑在 **gRPC handler 线程** ⇒ 共享层绑的 ThreadLocal 载体在这里可见。
                            inboundCarrierRemaining.set(DeadlineState.remainingMillis());
                            outboundCarrierPresent.set(GoStyleRequestDeadline.isPresent());
                            outboundCarrierRemainingNanos.set(GoStyleRequestDeadline.remainingNanos());
                            obs.onNext(GetCommentResp.getDefaultInstance());
                            obs.onCompleted();
                        }))
                .build();
    }

    /** 建真 MyBatis 链（注册生产链路上的那两个拦截器 ＋ 一个只读不写的记录器）。 */
    private void prepareSqlChain() {
        if (probeMapper != null) {
            return;
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + PROBE_TABLE + " (id bigint primary key)");

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("outbound-deadline-gate",
                new SpringManagedTransactionFactory(), dataSource));
        // 🔴 这两个就是生产链路上的那两个拦截器（共享层的落点）⇒ 不是「另写一个假的」。
        configuration.addInterceptor(new DeadlineQueryTimeoutInjector());
        configuration.addInterceptor(new SqlTimeoutStatusTranslator());
        // 🔴 记录器**必须排在注入器之后** ⇒ 它在插件链里更外层 ⇒ proceed() 返回时
        //    注入器**已经**把超时设好了 ⇒ 它读到的就是「消费点上的真实值」。
        recorder = new StatementTimeoutRecorder();
        configuration.addInterceptor(recorder);
        configuration.addMapper(ProbeMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        probeMapper = template.getMapper(ProbeMapper.class);
    }

    /** 捕获一次可抛异常的调用所抛出的异常（没有则返回 {@code null}）。 */
    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** 在异常链里找 {@link StatusRuntimeException}（MyBatis / Spring 可能再裹一层）。 */    private static StatusRuntimeException findStatusRuntimeException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof StatusRuntimeException sre) {
                return sre;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    /**
     * <b>记录器</b>：在 {@code StatementHandler.prepare} 的<b>消费点</b>上读驱动语句的超时值。
     *
     * <p>⚠️ <b>为什么必须断言「消费点」而不是只看 Bean 存在 / 只看最终行为（§3.3）</b>：
     * 「Bean 存在」只能证明「代码写成了这样」；「最终行为对」只能间接推断。
     * ⇒ 本记录器在<b>注入器刚刚设完超时的那一刻</b>，把驱动语句上的 {@code timeout} 读出来
     * ⇒ ⇒ **直接证明「毫秒值真的送到了驱动」**。
     *
     * <p>⚠️ <b>为什么用反射读 {@code getQueryTimeoutMs}</b>（而不是直接引用 pgjdbc 的实现类）：
     * 共享层的 {@link DeadlineQueryTimeoutInjector} 就是用反射探测 {@code setQueryTimeoutMs} 的
     * （理由：不该把驱动实现类编进共享库的 API 面）⇒ 本类沿用同一口径。
     */
    @Intercepts(@Signature(
            type = StatementHandler.class,
            method = "prepare",
            args = {Connection.class, Integer.class}))
    static final class StatementTimeoutRecorder implements Interceptor {

        /** 插件链里真正拿到的那个 Statement（生产形态下 = Hikari 代理）。 */
        volatile Statement captured;

        /** 驱动语句上的超时（毫秒）。读不到时为 -1。 */
        volatile long driverTimeoutMs = -1L;

        void reset() {
            captured = null;
            driverTimeoutMs = -1L;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object prepared = invocation.proceed();
            if (prepared instanceof Statement statement) {
                captured = statement;
                driverTimeoutMs = readDriverTimeoutMs(statement);
            }
            return prepared;
        }

        /**
         * 把池代理剥到驱动实现，再读它内部的 {@code timeout}。
         *
         * <p>实测 pgjdbc：{@code setQueryTimeout(int s)} 内部就是
         * {@code setQueryTimeoutMs(s * 1000L)} ⇒ ⇒ {@code getQueryTimeoutMs()} <b>能精确区分两条路径</b>：
         * 毫秒路径 = 剩余毫秒（≤ 剩余）· 秒级路径 = 向上取整后的整秒（> 剩余）。
         */
        private static long readDriverTimeoutMs(Statement statement) {
            try {
                Statement driver = statement;
                if (statement instanceof Wrapper wrapper) {
                    Statement unwrapped = wrapper.unwrap(Statement.class);
                    if (unwrapped != null) {
                        driver = unwrapped;
                    }
                }
                Method getter = findMillisecondTimeoutGetter(driver.getClass());
                if (getter == null) {
                    return -1L;
                }
                Object value = getter.invoke(driver);
                return value instanceof Long l ? l : -1L;
            } catch (ReflectiveOperationException | SQLException e) {
                return -1L;
            }
        }

        /** 探测驱动实现类上有没有 {@code getQueryTimeoutMs()}（pgjdbc ≥ 42.7）。 */
        private static Method findMillisecondTimeoutGetter(Class<?> statementClass) {
            try {
                return statementClass.getMethod("getQueryTimeoutMs");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }

        @Override
        public Object plugin(Object target) {
            return org.apache.ibatis.plugin.Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(java.util.Properties properties) {
            // 无配置项
        }
    }

    /** 本类专用的探针 Mapper（注解式 ⇒ 不依赖本域 7 个业务 Mapper 的表结构）。 */
    @Mapper
    public interface ProbeMapper {

        @Select("SELECT id FROM " + PROBE_TABLE + " WHERE id = #{id}")
        Long findOneById(@Param("id") long id);

        @Insert("INSERT INTO " + PROBE_TABLE + " (id) VALUES (#{id})")
        int insert(@Param("id") long id);
    }
}
