package com.rag.like.support;

import com.rag.proto.message.MessageServiceGrpc;
import com.rag.proto.message.SendNotificationReq;
import com.rag.proto.message.SendNotificationResp;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p><b>假 message-rpc 的<b>真 netty server</b></b> —— 给 like 域测「跨域通知」用。
 *
 * <h2>🔴 为什么需要它</h2>
 * <p>like 域承接 B 侧 {@code client/MessageRpcClient.java}（承接表 **R-11**），
 * 它是 like 域<b>唯一</b>的出向跨域调用点（{@code notifyArticleLiked}）。
 * ⇒ 此前这条路径<b>零活体</b>：对拍探针为了让两侧耗时可比，刻意用 {@code author_id="0"}
 * ⇒ ⇒ 通知分支根本没进（台账 §六·6.3 的第 ② 项）。
 *
 * <h2>🔴 为什么是「假 server」而不是「连真 message 域」</h2>
 * <p>本域的测试**一律自足**（PG/Redis 用 Testcontainers 自起）⇒ 不依赖任何外部 Sea 栈
 * ⇒ ⇒ 若去连 B 侧的 {@code Sea-message-rpc}，like 域的回归就会**跨 lane 耦合**
 * （那个容器被别的 lane 重启/改代码 ⇒ 本域回归变红 ⇒ 红灯含义失真）。
 * ⇒ ⇒ 这里起一个<b>本域自带的</b>真 netty server 作为「message 域的替身」：
 * <ul>
 *   <li>✅ 覆盖的是 <b>like 域自己的行为</b>：<b>真的发出去了吗、发出去的每个字段对吗</b>
 *       ⇒ 逐字段断言（<b>期望值取自 B 侧 {@code MessageRpcClient} 的逐字内容</b>）；</li>
 *   <li>✅ 失败语义可测：假 server <b>故意报错</b> ⇒ 断言 {@code likeAction} 的返回值
 *       <b>不受影响</b>（Go：{@code logger.LogBusinessErr} ⇒ 只记日志）；</li>
 *   <li>⚠️ <b>不覆盖</b>「B 侧 message 域收到之后做了什么」—— 那是 message 域的职责，
 *       不在 like 域审核范围内（⇒ 不冒充跨域对拍证据）。</li>
 * </ul>
 *
 * <h2>🔴 端口为什么钉死成 {@value #PORT}</h2>
 * <p>{@code @SpringBootTest} 的上下文在测试方法<b>之前</b>就创建，而 devh 的 gRPC 客户端
 * <b>启动即连</b> ⇒ ⇒ 假 server 必须<b>先于</b>上下文就绪 ⇒ ⇒ 端口必须<b>提前可知</b>
 * ⇒ ⇒ 钉死端口（{@value #PORT}，在 like 探针端口段 18211-18219 之外，避免撞段）。
 * <p>⚠️ 假 server 在<b>类加载时</b>就起（静态初始化块）⇒ ⇒ 「类加载」必须早于
 * 「Spring 上下文创建」⇒ 测试类用静态块显式调 {@link #ensureStarted()} 触发。
 *
 * <h2>⚠️ 寻址：靠 Spring Cloud 的 {@code SimpleDiscoveryClient}</h2>
 * <p>{@code @GrpcClient("message-rpc")} 走 devh 的 discovery ⇒ devh 用的是
 * Spring Cloud 的 {@code DiscoveryClient} bean ⇒ ⇒ 用
 * {@code spring.cloud.discovery.client.simple.instances.message-rpc[0].host/port}
 * 把 {@code message-rpc} 指到 {@code 127.0.0.1:18221} ⇒ <b>不碰 Nacos</b>。
 * <p>⚠️ {@code NacosStubConfiguration} 只打桩了 {@code NacosServiceManager#getNamingService()}，
 * <b>没有</b>打桩 {@code DiscoveryClient} ⇒ ⇒ 两条路互不干扰。
 */
public final class FakeMessageRpcServer {

    /** 假 server 的端口（固定 ⇒ 必须早于 Spring 上下文可知）。 */
    public static final int PORT = 18221;

    /** 收到的请求队列（测试按顺序 poll）。 */
    private static final BlockingQueue<SendNotificationReq> RECEIVED = new LinkedBlockingQueue<>();

    /** 是否让下一次调用<b>故意失败</b>（用于测「失败不影响点赞结果」）。 */
    private static final AtomicBoolean SHOULD_FAIL = new AtomicBoolean(false);

    private static volatile Server server;


    private FakeMessageRpcServer() {
    }

    /**
     * <b>启动</b>假 server（<b>幂等</b>）。
     *
     * <p>🔴 <b>为什么必须在 {@code @BeforeAll} 里起，而不能在静态块里</b>：
     * surefire 的 <b>类扫描阶段</b>会加载测试类 ⇒ ⇒ <b>静态块的副作用会在扫描期发生</b>
     * ⇒ ⇒ 若扫描期抛异常 ⇒ 该类被<b>静默丢弃</b>（<b>不报错、不执行、也不计入用例数</b>）
     * ⇒ ⇒ 表现为「同一命令两次全量回归，一次 like=110、一次 like=106」
     * ⇒ ⇒ **「不执行」比「失败」更危险**（连红灯都不给你）。
     * <p>⇒ ⇒ 挪到 {@code @BeforeAll} ⇒ 扫描期<b>无副作用</b> ⇒ 必定被发现；
     * ⇒ 且若端口被占 ⇒ 异常发生在<b>测试期</b> ⇒ 变成<b>可见的 ERROR</b>。
     */
    public static synchronized void start() {
        if (server != null && !server.isShutdown()) {
            return;
        }
        try {
            server = NettyServerBuilder.forPort(PORT)
                    .addService(new FakeService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "假 message-rpc server 起不来（port=" + PORT + "）: " + e.getMessage(), e);
        }
    }

    /** server 是否在跑（供断言/诊断用）。 */
    public static boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /** 关掉 server（测试类结束时调）。 */
    public static synchronized void shutdown() {
        Server s = server;
        if (s != null) {
            s.shutdownNow();
        }
        RECEIVED.clear();
    }

    /** 让下一次 {@code sendNotification} <b>故意失败</b>。 */
    public static void failNextCalls(boolean fail) {
        SHOULD_FAIL.set(fail);
    }

    /** 取一条收到的请求（最多等 {@code timeoutMs} 毫秒；取不到返回 {@code null}）。 */
    public static SendNotificationReq take(long timeoutMs) throws InterruptedException {
        return RECEIVED.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 当前已收到的请求数（用于断言「一条不多」）。 */
    public static int receivedCount() {
        return RECEIVED.size();
    }

    /** 丢弃已收到的请求（每个用例开始前调用，避免上一例的残留）。 */
    public static void reset() {
        RECEIVED.clear();
        SHOULD_FAIL.set(false);
    }

    /** 假实现 —— 只实现 like 域唯一用到的那一个方法。 */
    private static final class FakeService extends MessageServiceGrpc.MessageServiceImplBase {

        @Override
        public void sendNotification(SendNotificationReq request,
                                     StreamObserver<SendNotificationResp> responseObserver) {
            if (SHOULD_FAIL.get()) {
                responseObserver.onError(io.grpc.Status.INTERNAL
                        .withDescription("fake message-rpc failure").asRuntimeException());
                return;
            }
            RECEIVED.add(request);
            responseObserver.onNext(SendNotificationResp.newBuilder()
                    .setCode(200)
                    .setMsg("OK")
                    .setAffected(1)
                    .build());
            responseObserver.onCompleted();
        }
    }

    /** 便于断言时取状态码常量。 */
    public static Status okStatus() {
        return Status.OK;
    }
}
