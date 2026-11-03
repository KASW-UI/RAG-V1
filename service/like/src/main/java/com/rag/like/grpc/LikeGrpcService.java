package com.rag.like.grpc;

import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.ErrMsg;
import com.rag.like.contract.GrpcErrFactory;
import com.rag.like.domain.service.LikeDomainService;
import com.rag.like.exception.LikeExceptions;
import com.rag.proto.like.GetLikeCountReq;
import com.rag.proto.like.GetLikeCountResp;
import com.rag.proto.like.GetLikeStateReq;
import com.rag.proto.like.GetLikeStateResp;
import com.rag.proto.like.GetTargetLikerListReq;
import com.rag.proto.like.GetTargetLikerListResp;
import com.rag.proto.like.GetUserLikeListReq;
import com.rag.proto.like.GetUserLikeListResp;
import com.rag.proto.like.GetUserTotalLikeReq;
import com.rag.proto.like.GetUserTotalLikeResp;
import com.rag.proto.like.LikeActionReq;
import com.rag.proto.like.LikeActionResp;
import com.rag.proto.like.LikeServiceGrpc;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <p><b>gRPC 服务入口</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/impl/LikeServiceGrpcImpl.java}
 * （B 侧 123 行）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>类名由 {@code LikeServiceGrpcImpl} 改为 {@code LikeGrpcService}、
 *           落点由 {@code rpc/service/impl/} 改为 {@code grpc/}（D7 + 映射表第 142 行）；</li>
 *       <li>pb 包名由 {@code com.rag.like.like.proto} 改为 {@code com.rag.proto.like}（D7）；</li>
 *       <li>B 侧叠了 {@code @GrpcService} + {@code @Component} 两个注解 ⇒ C 侧只用 {@code @GrpcService}
 *           （devh 的 {@code @GrpcService} <b>本身</b>就带 {@code @Service} + {@code @Bean}
 *           ⇒ 再叠一个 {@code @Component} 是<b>重复声明</b>，去掉不改变任何可观察行为）；</li>
 *       <li>错误工厂由 B 侧的 {@code LikeGrpcError} 改为域内 {@link GrpcErrFactory}（1:N 拆分）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。6 个方法每个只有「构造用例 + 转发」两行，
 *       <b>server 层没有任何业务逻辑</b>；三类错误出口（业务异常 / 已有 status / 兜底）逐条一致。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 错误出口：必须 {@code onError(...)}，不能让它逃逸</h2>
 * <p>grpc-java 在 {@code UnaryServerCallListener.onHalfClose} 里调用业务方法。
 * 若在那里 {@code throw}，异常会逃出流监听回调，被当成<b>传输层内部错误</b>，
 * description 被替换成 {@code "Application error processing RPC"}
 * —— <b>业务文案当场全丢</b>。
 *
 * <p>而 like 域的业务码载体就是 description 文案 + {@code rag-like-biz-code} trailer
 * ⇒ description 一丢，就只剩粗粒度的兜底码。
 * ⇒ <b>{@code onError} 不可省</b>。
 *
 * <h2>⚠️ 兜底必须是 5001 而不是「把异常原文发给客户端」</h2>
 * <p>原生异常消息会泄漏 SQL / 连接串等内部信息。兜底路径把任何非业务异常
 * （含 NPE、序列化失败、DB 驱动异常未在业务层转换的漏网之鱼）
 * 折叠成 {@code 5001 + "系统内部错误"}。
 */
@GrpcService
public class LikeGrpcService extends LikeServiceGrpc.LikeServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(LikeGrpcService.class);

    private final LikeDomainService domainService;

    public LikeGrpcService(LikeDomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void likeAction(LikeActionReq request, StreamObserver<LikeActionResp> responseObserver) {
        invoke(() -> domainService.likeAction(request), responseObserver);
    }

    @Override
    public void getLikeCount(GetLikeCountReq request, StreamObserver<GetLikeCountResp> responseObserver) {
        invoke(() -> domainService.getLikeCount(request), responseObserver);
    }

    @Override
    public void getLikeState(GetLikeStateReq request, StreamObserver<GetLikeStateResp> responseObserver) {
        invoke(() -> domainService.getLikeState(request), responseObserver);
    }

    @Override
    public void getUserLikeList(GetUserLikeListReq request, StreamObserver<GetUserLikeListResp> responseObserver) {
        invoke(() -> domainService.getUserLikeList(request), responseObserver);
    }

    @Override
    public void getTargetLikerList(GetTargetLikerListReq request,
                                   StreamObserver<GetTargetLikerListResp> responseObserver) {
        invoke(() -> domainService.getTargetLikerList(request), responseObserver);
    }

    @Override
    public void getUserTotalLike(GetUserTotalLikeReq request,
                                 StreamObserver<GetUserTotalLikeResp> responseObserver) {
        invoke(() -> domainService.getUserTotalLike(request), responseObserver);
    }

    /**
     * B 侧 {@code invoke} 的逐字等价物：三条错误出口，顺序不可调换。
     *
     * <ol>
     *   <li><b>业务异常</b>（{@link LikeExceptions.Rpc}）⇒ {@code onError(grpcError(bizCode, msg))}
     *       —— 业务码进 trailer、原文案进 description；</li>
     *   <li><b>已是 status 的异常</b> ⇒ 原样 {@code onError}（不二次包装）；</li>
     *   <li><b>其它异常</b> ⇒ 折叠成 5001 + "系统内部错误"。</li>
     * </ol>
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (LikeExceptions.Rpc business) {
            observer.onError(GrpcErrFactory.grpcError(business.getBizCode(), business.getMsg()));
        } catch (StatusRuntimeException sre) {
            observer.onError(sre);
        } catch (RuntimeException e) {
            LOG.error("like-rpc handler failed", e);
            observer.onError(GrpcErrFactory.grpcError(
                    ErrCodes.ERROR_SERVER_COMMON,
                    ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON)));
        }
    }
}
