package com.rag.follow.support;

import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.UserInfo;
import com.rag.proto.user.UserServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p><b>「假 user-rpc」—— 真 gRPC server（随机 TCP 端口）</b>。
 *
 * <h2>为什么需要它</h2>
 * <p>follow 域的 {@code FollowUserExistenceService#ensureUserExists} 会<b>真的</b>调下游
 * {@code user-rpc} 的 {@code GetUser}（跨域 gRPC，D6）⇒
 * ① {@code follow} / {@code block} 两个<b>创建</b>用例先查「目标用户是否存在」（5101）；
 * ② 三个列表 + 推荐的 {@code ensureSelf} 查「<b>自己</b>是否存在」。
 * <p>⇒ ⇒ 只要不把这条链路替掉，<b>所有写路径与列表路径都跑不到 DB</b>。
 *
 * <h2>⚠️ 为什么是「真 gRPC server」而不是 mock 掉 {@code UserRpcClient}</h2>
 * <p>本域的 P0 断言里有几条<b>必须</b>穿过这条链路才有意义：
 * <ul>
 *   <li><b>5101（用户不存在）</b>：判据是「下游回 NOT_FOUND ⇒ 折叠成 5101」
 *       ⇒ 必须是真 gRPC 的 status 语义（mock 一个 {@code StatusRuntimeException} 是另一回事）；</li>
 *   <li><b>5202（已拉黑）</b>：要在真 PG 里预置拉黑行，再经真 gRPC 走完闸门。</li>
 * </ul>
 * ⇒ ⇒ 起一个<b>真的</b> gRPC server（{@code ServerBuilder.forPort(0)} ⇒ 随机端口 ⇒ 不撞端口），
 * 让 devh 的 client 用 {@code static://127.0.0.1:<port>} 直连。
 *
 * <h2>⚠️ 端口必须是「启动 Spring 上下文之前」就定下来</h2>
 * <p>{@code grpc.client.user-rpc.address} 由 devh 在建 bean 时读取 ⇒ 端口必须先确定
 * ⇒ 本类用<b>静态初始化块</b>起 server（类加载早于 {@code @DynamicPropertySource} 执行）
 * ⇒ 地址通过 supplier 提供 ⇒ 端口天然可用。
 *
 * <p>⚠️ 本类<b>只提供「存在性」这一维语义</b>（B 侧 users 域的 {@code GetUser} 也只回 found/user），
 * 不模拟 follow 域的任何规则 ⇒ 被测语义（关注/拉黑/列表/推荐的规则）全部留在被测对象里。
 */
public final class FakeUserRpcServer {

    /** 标成「不存在」的 uid 集合（⇒ 下游回 found=false ⇒ follow 域折叠成 5101）。 */
    private static final Set<Long> NOT_FOUND = ConcurrentHashMap.newKeySet();

    /**
     * 标成「下游超时」的 uid 集合（⇒ 下游回 {@code DEADLINE_EXCEEDED}）。
     *
     * <p>⚠️ **为什么用「服务端回 DEADLINE_EXCEEDED」而不是「让服务端挂住」**：
     * 真挂住要等**客户端**的 deadline 到期（生产里是共享层的出向 2s 预算），
     * 而单测里没有那层拦截器 ⇒ 只能靠服务端**主动**回一个同码的错误。
     * ⇒ 客户端侧拿到的 {@code StatusRuntimeException} 形态与生产**一致**
     * （code = DEADLINE_EXCEEDED · description = null）⇒ 领域层判定与生产同路。
     */
    private static final Set<Long> TIMEOUT = ConcurrentHashMap.newKeySet();

    private static final Server SERVER = start();

    private FakeUserRpcServer() {
    }

    private static Server start() {
        try {
            Server server = ServerBuilder.forPort(0)
                    .addService(new UserServiceGrpc.UserServiceImplBase() {
                        @Override
                        public void getUser(GetUserReq request, StreamObserver<GetUserResp> responseObserver) {
                            long uid = request.getUid();
                            if (TIMEOUT.contains(uid)) {
                                // ⚠️ 与生产同形：客户端拿到的 StatusRuntimeException
                                //    code = DEADLINE_EXCEEDED · description = null
                                responseObserver.onError(io.grpc.Status.DEADLINE_EXCEEDED
                                        .withDescription(null).asRuntimeException());
                                return;
                            }
                            GetUserResp.Builder builder = GetUserResp.newBuilder();
                            if (NOT_FOUND.contains(uid)) {
                                builder.setFound(false);
                            } else {
                                builder.setFound(true)
                                        .setUser(UserInfo.newBuilder()
                                                .setUid(uid)
                                                .setUsername("fake-user-" + uid)
                                                .build());
                            }
                            responseObserver.onNext(builder.build());
                            responseObserver.onCompleted();
                        }
                    })
                    .build()
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("假 user-rpc gRPC server 启动失败", e);
        }
    }

    /**
     * devh 客户端地址 —— {@code static://127.0.0.1:<随机端口>}。
     *
     * <p>⚠️ 用 {@code static://} 而不是 yml 里的 {@code discovery:///user-rpc}：
     * 后者要求容器里有一个可用的服务发现客户端（本域测试里 Nacos discovery 是关掉的）
     * ⇒ 会把「有没有连上下游」与「被测规则对不对」两件事搅在一起。
     */
    public static String address() {
        return "static://127.0.0.1:" + SERVER.getPort();
    }

    /** 把某个 uid 标成「下游查不到」。 */
    public static void markNotFound(long uid) {
        NOT_FOUND.add(uid);
    }

    /** 把某个 uid 标成「下游查得到」。 */
    public static void markFound(long uid) {
        NOT_FOUND.remove(uid);
    }

    /** 把某个 uid 标成「下游超时」（⇒ 下游回 DEADLINE_EXCEEDED）。 */
    public static void markTimeout(long uid) {
        TIMEOUT.add(uid);
    }

    /** 把某个 uid 从「下游超时」里摘掉。 */
    public static void markNotTimeout(long uid) {
        TIMEOUT.remove(uid);
    }

    public static int port() {
        return SERVER.getPort();
    }
}
