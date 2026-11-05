package com.rag.comment.grpc;

import com.rag.comment.CommentServiceApplication;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;

import com.rag.common.observability.DeadlineState;
import com.rag.proto.comment.CommentServiceGrpc;
import com.rag.proto.comment.GetCommentReq;
import com.rag.proto.comment.GetCommentResp;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ServerCalls;

import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p><b>🔴 运行期守门</b>：comment 域的 gRPC 业务观测拦截器<b>真的</b>挂到了 server 上，
 * 且<b>服务端超时真的打断了「正在执行的处理」</b>。
 *
 * <h2>🔴 为什么这条必须是「运行期」的</h2>
 * <p>按 AGENTS.md §3.3，<b>纯 mock 单测不算「机制生效」的证据</b>：
 * 它证明的是「链的拼装代码写成了这样」，<b>证明不了</b>
 * 「{@code server.intercept(...)} 真的被 grpc-java 的 server 采纳并作用在<b>真实调用</b>上」。
 * <p>⇒ ⇒ 本类起一个<b>真 TCP gRPC server</b>，用容器里那个 {@link GrpcServerConfigurer} 装配它，
 * 注册一个「处理函数一直不返回」的服务（用 latch 阻塞，<b>不是</b> {@code sleep}），
 * 然后用 5 秒的客户端 deadline 去调：
 * <ul>
 *   <li>若服务端超时生效 ⇒ <b>约 300ms</b> 就返回 {@code DEADLINE_EXCEEDED}（远早于客户端的 5 秒）；</li>
 *   <li>若 interceptor 没挂上 ⇒ 要等满 5 秒才由<b>客户端</b> deadline 超时返回。</li>
 * </ul>
 * 两者返回的 status 码<b>都是</b> {@code DEADLINE_EXCEEDED}，因此必须再断言<b>耗时</b>才能区分。
 *
 * <h2>🔴 三条判别式断言缺一不可</h2>
 * <p>grpc-java <b>不会</b>向<b>裸</b> {@link ServerCallHandler} 派发 {@code onHalfClose}
 * ⇒ 用裸 handler 注册慢服务时，「处理函数一直不返回」这件事<b>从未发生</b>
 * ⇒ 客户端照样拿到（自己的）{@code DEADLINE_EXCEEDED} ⇒ 只断言 status 与耗时的用例<b>照样是绿的</b>
 * ⇒ ⇒ 因此本类必须同时满足：
 * <ol>
 *   <li>用 {@link ServerCalls#asyncUnaryCall}（标准派发入口）注册慢服务 ⇒ handler 真的会被调用；</li>
 *   <li>断言「<b>handler 确实被调用过</b>」；</li>
 *   <li>断言「<b>取消发生时它仍在跑</b>」（不是处理早就结束了才收到取消）。</li>
 * </ol>
 *
 * <h2>🔴 为什么用 latch 而不是 {@code sleep}</h2>
 * <p>{@code Thread.sleep(10_000)} 方案下，「取消发生时它还在跑」这条断言<b>根本写不出来</b>
 * （等到断言时它早跑完了）⇒ 必须用 latch ⇒ 测试在 {@code finally} 里<b>立刻</b>放行。
 *
 * <h2>🔴 客户端<b>必须</b>用 {@code forAddress(SocketAddress)} 形式</h2>
 * <p>devh 的 {@code DiscoveryClientResolverFactory} 会把默认名字解析 scheme 抢成 {@code discovery}
 * ⇒ 用 {@code forAddress(String,int)} 的客户端会被劫持到 Nacos ⇒ 报
 * 「No servers found for 127.0.0.1:port」这种像「服务端没起来」的错。
 * ⇒ ⇒ 本类用 {@code forAddress(new InetSocketAddress(host, port))}：该重载<b>不经过名字解析</b>。
 *
 * <h3>AGENTS.md §3.2 三段式</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：① 端口用 {@code forPort(0)} 让 OS 分配（B 侧用本域专用段，
 *       因为它还要给容器留段位；C 侧本类自己起 server ⇒ 不与容器抢段）；
 *       ② 客户端用 SocketAddress 重载（绕过 devh 的 discovery scheme 劫持）；
 *       ③ 拦截器来源从「域内自写的 {@code rpcInterceptorConfigurer}」改成
 *       <b>共享层的 {@code seaGrpcServerConfigurer}</b>（D1 合并后域内不再写第二条链）。
 *       ⇒ 断言的<b>被测语义完全相同</b>：链真的挂上了没有。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 「服务端超时打断正在执行的 handler」＋「deadline 传播到
 *       handler 线程」这两条判据逐条照抄 B 侧；timeout 的生产值（2s）另有一条断言钉住。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code comment-rpc/…/rpc/CommentRpcGrpcInterceptorWiringTest.java:131-347}</li>
 *         <li>{@code service/comment/rpc/comment.go:33}（{@code DisableNativeRpcTimeout} ⇒ 走 go-zero
 *             {@code zrpc/config.go} 的 {@code default=2000}）＋ {@code :54}
 *             （{@code AddUnaryInterceptors(observability.NewUnaryServerInterceptor(...))}）</li>
 *         <li>{@code common/…/observability/DeadlineState.java:206-209}（入向载体）</li>
 *         <li>{@code common/…/autoconfigure/ProtectionAutoConfiguration.java:230-243}（唯一挂载点 A3）</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的链真的挂上了、超时真的生效」。
 * 「B 侧此刻也如此」属于<b>双侧活体对拍</b>（本类<b>不冒充</b>该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
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
                // 绕过 Redis：Lettuce 连接懒建，不真正连。
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                // ⚠️ 压到 300ms 让「服务端超时」与「客户端 5s deadline」的差距足够大、好区分。
                //    生产值 2s 由 productionTimeoutMatchesGoZeroDefault 单独断言。
                "observability.rpc-timeout=300ms",
                // ⚠️ 关掉 CPU 自适应降载，让本类的判据**只**取决于「业务观测拦截器有没有挂上」。
                //    否则链上的 shedding 层会先返回 RESOURCE_EXHAUSTED，把判据盖掉 ⇒ 变成 flaky。
                "rag.protection.cpu-shedding.enabled=false"
        })
@ActiveProfiles("test")
class CommentGrpcInterceptorWiringRealTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        // ⚠️ gRPC server **不起 TCP 端口**（-1 ⇒ in-process）⇒ 不与本类自己起的真 server 抢端口。
        //    in-process server 的名字由 devh 生成全局唯一名 ⇒ 多上下文并存不撞。
        registry.add("grpc.server.port", () -> -1);
    }

    @Autowired
    private ApplicationContext ctx;

    // ==================================================================
    // 1. 服务端超时真的被施加到 server 上
    // ==================================================================

    @Test
    @DisplayName("🔴 运行期：服务端超时由容器里的 GrpcServerConfigurer 真正施加到 server 上"
            + "（Go: comment.go:54 AddUnaryInterceptors）")
    void serverSideTimeoutIsActuallyEnforced() throws Exception {
        // ⚠️ 必须按 Bean 名取：devh starter 自己还注册了 compression/decompression 两个同类型 Bean，
        //    ctx.getBean(GrpcServerConfigurer.class) 会抛 NoUniqueBeanDefinitionException。
        GrpcServerConfigurer configurer = ctx.getBean("seaGrpcServerConfigurer", GrpcServerConfigurer.class);
        assertThat(configurer).as("seaGrpcServerConfigurer 必须产出 GrpcServerConfigurer Bean").isNotNull();

        ServerBuilder<?> builder = ServerBuilder.forPort(0);
        configurer.accept(builder);                  // ← 被测的那一行
        SlowHandlerProbe probe = new SlowHandlerProbe();
        Server server = builder.addService(slowCommentService(probe)).build().start();

        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
                .usePlaintext().build();
        try {
            var stub = CommentServiceGrpc.newBlockingStub(channel);
            long startNanos = System.nanoTime();
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                            .getComment(GetCommentReq.newBuilder()
                                    .setTargetType("article")
                                    .setTargetId("t-1")
                                    .setPage(1)
                                    .setPageSize(10)
                                    .build()),
                    "「处理函数一直不返回」的服务必须被服务端超时打断");
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(e.getStatus().getCode())
                    .as("实际 status=" + e.getStatus().getCode()
                            + " description=" + e.getStatus().getDescription())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
            assertThat(elapsedMillis)
                    .as("必须在服务端超时（300ms）附近返回，实际耗时 " + elapsedMillis
                            + "ms。若接近 5000ms，说明只有客户端 deadline 生效 —— "
                            + "即 server.intercept(...) 没挂上，超时/deadline 传播/gRPC span 已静默消失。")
                    .isLessThan(3_000L);

            // 🔴 下面这两条断言是「判别式」的那一维：
            //    只看「客户端拿到什么 status、耗时多久」—— 而这两件事在
            //    「handler 压根没被调用过」时**同样成立** ⇒ 守门是空的。
            assertThat(probe.entered.await(2, TimeUnit.SECONDS))
                    .as("handler 必须**真的被调用过**。若为 false，说明 grpc-java 根本没把 onHalfClose "
                            + "派发下去（裸 ServerCallHandler 就是这种情形）⇒ 本测试验的不是超时，而是空调用。")
                    .isTrue();
            assertThat(probe.completed.get())
                    .as("服务端超时取消发生时，handler 必须**仍在跑**（未完成）。"
                            + "若为 true，说明取消发生在处理完成之后 ⇒ 超时闸根本没作用在处理过程上。")
                    .isFalse();
        } finally {
            probe.release.countDown();               // 放行 handler，让它别挂在 30s 上
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    // ==================================================================
    // 2. deadline 真的传播到了 handler 线程
    // ==================================================================

    @Test
    @DisplayName("🔴 运行期：deadline 真的传播到了 handler 线程（onHalfClose 那一半）")
    void deadlineIsPropagatedToHandlerThread() throws Exception {
        GrpcServerConfigurer configurer = ctx.getBean("seaGrpcServerConfigurer", GrpcServerConfigurer.class);
        assertThat(configurer).isNotNull();

        // handler 内部观测到的「剩余预算」；Long.MAX_VALUE = 未设置（= 没接上）
        AtomicLong observedRemaining = new AtomicLong(Long.MAX_VALUE);
        AtomicReference<String> handlerThread = new AtomicReference<>("<未进入>");
        SlowHandlerProbe probe = new SlowHandlerProbe();

        ServerBuilder<?> builder = ServerBuilder.forPort(0);
        configurer.accept(builder);
        Server server = builder.addService(
                slowCommentService(probe, observedRemaining, handlerThread)).build().start();

        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
                .usePlaintext().build();
        try {
            var stub = CommentServiceGrpc.newBlockingStub(channel);
            assertThrows(StatusRuntimeException.class,
                    () -> stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                            .getComment(GetCommentReq.newBuilder()
                                    .setTargetType("article")
                                    .setTargetId("t-1")
                                    .build()),
                    "「处理函数一直不返回」的服务必须被服务端超时打断");

            assertThat(probe.entered.await(2, TimeUnit.SECONDS))
                    .as("handler 根本没被调用（线程 = " + handlerThread.get() + "）"
                            + "⇒ 本用例无法证明 deadline 传播；先确认 handler 入口本身可达")
                    .isTrue();

            long remaining = observedRemaining.get();
            assertThat(remaining)
                    .as("handler 线程（" + handlerThread.get() + "）上 DeadlineState 恒未设置 "
                            + "⇒ onHalfClose 从未执行 ⇒ deadline 没有传播到业务层 "
                            + "⇒ checkDeadline() 全是死代码，且 SQL 不会被注入 queryTimeout")
                    .isNotEqualTo(Long.MAX_VALUE);
            assertThat(remaining)
                    .as("handler 观测到的剩余预算应是服务端超时（300ms）之内，实际 = " + remaining + "ms")
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(300L);

            // 与上一条同源 —— 「deadline 传到了 handler 线程」只说明 onHalfClose 执行过，
            // 还要证明「取消发生时它**仍在跑**」。
            assertThat(probe.completed.get())
                    .as("取消发生时 handler 必须**仍在跑**（未完成）。")
                    .isFalse();
        } finally {
            probe.release.countDown();
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    // ==================================================================
    // 3. 配置：生产 application.yml 的 rpc-timeout 必须是 2s
    // ==================================================================

    @Test
    @DisplayName("🔴 配置：生产 application.yml 的 observability.rpc-timeout 必须是 2s"
            + "（Go: comment.go:33 未配 Timeout ⇒ go-zero zrpc/config.go 默认 2000ms）")
    void productionTimeoutMatchesGoZeroDefault() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml 必须随 jar 打包").isNotNull();
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yml)
                    .as("Go 侧 etc/comment.yaml 未配 Timeout ⇒ 走 go-zero zrpc/config.go 的 default=2000ms；"
                            + "Java 侧必须同样是 2s，否则超时语义与 Go 不一致")
                    .contains("rpc-timeout: 2s");
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 注册一个「处理函数一直不返回」的服务 —— 用来观察超时是否真的生效。
     *
     * <p>🔴 这里<b>必须</b>用 grpc-java 的标准派发入口 {@link ServerCalls#asyncUnaryCall}，
     * <b>不能</b>用裸 {@link ServerCallHandler} —— 后者不会被派发 {@code onHalfClose}
     * ⇒ 方法体一次都不执行 ⇒ 「处理函数一直不返回」从未发生 ⇒ 守门空转。
     */
    private static ServerServiceDefinition slowCommentService(SlowHandlerProbe probe) {
        return slowCommentService(probe, new AtomicLong(Long.MAX_VALUE), new AtomicReference<>("<未进入>"));
    }

    private static ServerServiceDefinition slowCommentService(SlowHandlerProbe probe,
                                                             AtomicLong observedRemaining,
                                                             AtomicReference<String> handlerThread) {
        ServerCallHandler<GetCommentReq, GetCommentResp> slowHandler =
                ServerCalls.asyncUnaryCall((req, observer) -> {
                    probe.entered.countDown();
                    handlerThread.set(Thread.currentThread().getName());
                    // ⚠️ 此刻正是观测拦截器的 onHalfClose 绑定 deadline 的作用域内，
                    //    所以这里读到的就是「服务端看到的剩余预算」。
                    observedRemaining.set(DeadlineState.remainingMillis());
                    try {
                        // 🔴 用 latch 而不是 sleep 10s ⇒
                        // ① 测试可以在 finally 里**立刻**放行，不必等满 10 秒；
                        // ② 可以**精确**观测「取消发生时它还在跑」。
                        probe.release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    probe.completed.set(true);
                    try {
                        observer.onNext(GetCommentResp.getDefaultInstance());
                        observer.onCompleted();
                    } catch (RuntimeException ignored) {
                        // 调用已被服务端超时关闭 ⇒ 这里再写就会抛。
                        // ⚠️ **不是**掩盖问题：本测试要断言的正是「关闭发生时它还在跑」这个时序。
                    }
                });
        // ⚠️ 自建一个只含 GetComment 这一个方法的 ServiceDescriptor：
        //    ① 必须用生成代码里的**同一个** getGetCommentMethod() 实例，否则报
        //       "Bound method ... not same instance as method in service descriptor"；
        //    ② 描述符里的每个方法都必须有绑定。
        io.grpc.ServiceDescriptor onlyGetComment = io.grpc.ServiceDescriptor
                .newBuilder(CommentServiceGrpc.getServiceDescriptor().getName())
                .addMethod(CommentServiceGrpc.getGetCommentMethod())
                .build();
        return ServerServiceDefinition.builder(onlyGetComment)
                .addMethod(CommentServiceGrpc.getGetCommentMethod(), slowHandler)
                .build();
    }

    /**
     * 「处理函数确实被调用过」+「取消发生时它仍在跑」这两个证据的载体。
     *
     * <p>⚠️ 这两个信号是「判别式」那一维：只看「客户端拿到什么 status、耗时多久」，
     * 而那两件事在「handler 压根没执行」时<b>同样成立</b> ⇒ 守门是空的。
     */
    private static final class SlowHandlerProbe {
        /** handler 真的被调用 ⇒ countDown。 */
        final CountDownLatch entered = new CountDownLatch(1);
        /** 测试收尾时放行 ⇒ 让 handler 立刻退出，不必等满 30s。 */
        final CountDownLatch release = new CountDownLatch(1);
        /** handler 跑完了（= 取消发生时它已经不在跑了）。 */
        final AtomicBoolean completed = new AtomicBoolean(false);
    }
}
