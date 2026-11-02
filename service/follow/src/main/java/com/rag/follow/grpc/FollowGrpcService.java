package com.rag.follow.grpc;

import com.rag.follow.contract.ErrCodes;
import com.rag.follow.domain.service.FollowDomainService;
import com.rag.follow.service.FollowRpcSupport;
import com.rag.proto.follow.BaseResp;
import com.rag.proto.follow.FollowServiceGrpc;
import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.RelationReq;
import com.rag.proto.follow.UserListResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <p><b>gRPC 服务入口</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/service/impl/FollowServiceGrpcImpl.java}
 * （B 侧 137 行）。
 *
 * <h2>⚠️ L4 idiomatization：类名与包名（D7 包压平 + 映射表 §0.5）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th><th>依据</th></tr>
 *   <tr><td>{@code com.rag.follow.follow.rpc.service.impl.FollowServiceGrpcImpl}</td>
 *       <td>{@code com.rag.follow.grpc.FollowGrpcService}</td>
 *       <td>映射表 {@code docs/Mapping_Table/follow/follow.md} 第 70 / 142 行
 *           ——「{@code follow/rpc/service/impl/} → {@code grpc/}（压平 + 类改名）」</td></tr>
 *   <tr><td>{@code com.rag.follow.follow.proto.*}（B 侧 pb 包名）</td>
 *       <td>{@code com.rag.proto.follow.*}</td>
 *       <td>D7：proto 集中到根级 {@code proto/} 模块，pb 包名 {@code com.rag.proto.<域>}</td></tr>
 *   <tr><td>{@code @GrpcService} + {@code @Component} 两个注解</td>
 *       <td>只有 {@code @GrpcService}</td>
 *       <td>devh 的 {@code @GrpcService} <b>本身</b>就带 {@code @Service} + {@code @Bean}
 *           （{@code net.devh.boot.grpc.server.service.GrpcService} 源码）
 *           ⇒ 它已经是容器 Bean，再叠一个 {@code @Component} 是<b>重复声明</b>
 *           ⇒ ⇒ 去掉它<b>不改变任何可观察行为</b>。
 *           同一仓的 {@code com.rag.user.grpc.UserGrpcService} 也是只用 {@code @GrpcService}。</td></tr>
 * </table>
 * ⇒ <b>pb 包名与注解是仅有的两处差异</b>：{@code follow.proto} 的 message / 字段号 / 字段名
 * 与 B 侧逐条一致 ⇒ ⇒ <b>wire 层完全一致</b>。
 *
 * <h2>职责</h2>
 * <p>8 个方法，每个方法只有两行（构造用例 + 转发），
 * <b>server 层没有任何业务逻辑</b>。业务全在 {@link FollowDomainService}。
 * 本类唯一职责是<b>把业务层抛出的 gRPC 状态原样写上 wire</b>。
 *
 * <h2>⚠️ 错误出口：必须 {@code onError(...)}，不能让它逃逸</h2>
 * <p>grpc-java 在 {@code UnaryServerCallListener.onHalfClose} 里调用业务方法。
 * 若在那里 {@code throw}，异常会逃出流监听回调，被当成<b>传输层内部错误</b>，
 * description 被替换成 {@code "Application error processing RPC"}
 * —— <b>业务文案当场全丢</b>。
 *
 * <p>而 follow 域的业务码<b>唯一的载体就是 description 文案</b>：
 * {@code GrpcErrorMapper} 把 bizCode 翻译成英文文案写进 description，
 * 调用方再靠文案反查还原 ⇒ description 一丢，就只剩粗粒度的兜底码。
 * ⇒ <b>{@code onError} 不可省</b>。
 *
 * <h2>⚠️ 兜底必须是 5001 而不是「把异常原文发给客户端」</h2>
 * <p>原生异常消息会泄漏 SQL / 连接串等内部信息。兜底路径把任何非业务异常
 * （含 NPE、序列化失败、DB 驱动异常未在业务层转换的漏网之鱼）
 * 折叠成 {@code INTERNAL + "internal server error"}。
 * 取 5001 而不是 1015，是因为 5001 有专门的业务文案，且是调用方 switch 兜底能落到的那个码。
 */
@GrpcService
public class FollowGrpcService extends FollowServiceGrpc.FollowServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(FollowGrpcService.class);

    private final FollowDomainService domainService;

    public FollowGrpcService(FollowDomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void follow(RelationReq request, StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domainService.follow(request), responseObserver);
    }

    @Override
    public void unfollow(RelationReq request, StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domainService.unfollow(request), responseObserver);
    }

    @Override
    public void block(RelationReq request, StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domainService.block(request), responseObserver);
    }

    @Override
    public void unblock(RelationReq request, StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domainService.unblock(request), responseObserver);
    }

    @Override
    public void getFollowList(ListReq request, StreamObserver<UserListResp> responseObserver) {
        invoke(() -> domainService.getFollowList(request), responseObserver);
    }

    @Override
    public void getBlockList(ListReq request, StreamObserver<UserListResp> responseObserver) {
        invoke(() -> domainService.getBlockList(request), responseObserver);
    }

    @Override
    public void getFollowerList(ListReq request, StreamObserver<UserListResp> responseObserver) {
        invoke(() -> domainService.getFollowerList(request), responseObserver);
    }

    @Override
    public void getRecommendations(ListReq request, StreamObserver<RecommendResp> responseObserver) {
        invoke(() -> domainService.getRecommendations(request), responseObserver);
    }

    /**
     * 统一出口模板 —— 对应 B 侧 {@code FollowServiceGrpcImpl.java:124-137}。
     *
     * <p><b>两层 catch 的顺序不可调换</b>：
     * <ol>
     *   <li>{@link StatusRuntimeException} —— <b>业务错误的正常载体</b>。
     *       领域层经 {@code FollowRpcSupport.rpcError(...)} 构造，它持有的
     *       {@code Status} 与 B 侧的错误构造产出<b>逐字段相同</b>
     *       （见该方法的 javadoc：为什么不直接抛 checked 的 {@code StatusException}）。
     *       这里<b>原样上 wire</b>，不再包一层 —— 二次包装会破坏 description，
     *       而 description 正是 follow 域业务码的唯一载体。</li>
     *   <li>{@link RuntimeException} —— 兜底 5001，绝不把异常原文发给客户端
     *       （原生消息会泄漏 SQL / 连接串）。</li>
     * </ol>
     *
     * <p>⚠️ <b>兜底没有单独 catch {@code StatusException}</b>：领域层已全部转成
     * unchecked 形式，checked 的那一个不可能到这里。若将来新增代码直接抛 checked 版本，
     * 编译期就会拦下（无法塞进 {@link Supplier}），而不是静默走兜底丢掉业务码。
     *
     * <p>⚠️ 兜底的错误构造：B 侧写的是
     * {@code FollowGrpcError.grpcError(INTERNAL, ERROR_SERVER_COMMON).getStatus().asRuntimeException()}；
     * C 侧改走 {@link FollowRpcSupport#rpcError} —— 两者是<b>逐行相同</b>的两步
     * （向 {@code GrpcErrorMapper} 取带 description 的 {@code Status} + {@code asRuntimeException()}），
     * 且 C 侧的「业务错误唯一构造入口」就是它 ⇒ 语义一致。
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (StatusRuntimeException business) {
            observer.onError(business);
        } catch (RuntimeException e) {
            LOG.error("follow-rpc handler failed", e);
            observer.onError(FollowRpcSupport.rpcError(
                    Status.Code.INTERNAL, ErrCodes.ERROR_SERVER_COMMON));
        }
    }
}
