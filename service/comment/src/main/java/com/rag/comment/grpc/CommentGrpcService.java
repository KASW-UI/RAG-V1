package com.rag.comment.grpc;

import com.rag.comment.contract.ErrCodes;
import com.rag.comment.contract.ErrMsg;
import com.rag.comment.contract.GrpcErrFactory;
import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.exception.CommentExceptions;
import com.rag.proto.comment.CommentServiceGrpc;
import com.rag.proto.comment.CreateCommentReq;
import com.rag.proto.comment.CreateCommentResp;
import com.rag.proto.comment.DeleteCommentReq;
import com.rag.proto.comment.DeleteCommentResp;
import com.rag.proto.comment.GetCommentReq;
import com.rag.proto.comment.GetCommentResp;
import com.rag.proto.comment.LikeCommentReq;
import com.rag.proto.comment.LikeCommentResp;
import com.rag.proto.comment.ManageCommentReq;
import com.rag.proto.comment.ManageCommentResp;
import com.rag.proto.comment.ReportCommentReq;
import com.rag.proto.comment.ReportCommentResp;
import com.rag.proto.comment.UpdateSubjectStateReq;
import com.rag.proto.comment.UpdateSubjectStateResp;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * <p><b>comment 域 gRPC 服务实现</b> —— 对应 B 侧
 * {@code comment-rpc/…/rpc/service/impl/CommentServiceGrpcImpl.java:27-131}。
 *
 * <p><b>⚠️ L4 落点变化（D7 · 包压平 + 类改名）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.service.impl.CommentServiceGrpcImpl}
 * ⇒ C 侧 {@code com.rag.comment.grpc.CommentGrpcService}
 * （映射表 §0.5：「{@code comment/rpc/service/impl/} → {@code grpc/}」＋「类改名
 * {@code CommentServiceGrpcImpl} → {@code CommentGrpcService}」）。
 *
 * <p><b>⚠️ proto 包的落点（D7 · proto 集中化）</b>：
 * B 侧 pb 包名 {@code com.rag.comment.comment.proto}
 * ⇒ C 侧 {@code com.rag.proto.comment}（根级 {@code proto} 模块，pb 包名按 D7 归一）。
 * ⚠️ proto 的 {@code package} 声明两侧<b>都是</b> {@code comment;}
 * ⇒ gRPC 方法全名两侧都是 {@code /comment.CommentService/*}
 * （见已签字偏离 {@code DEV-COMMENT-PROTO-001}）。
 *
 * <h2>🔴 错误出口的三条分支，方向反了就错</h2>
 * <ol>
 *   <li><b>业务异常</b>（{@link CommentExceptions.Rpc}）⇒ 还原成 B 侧的
 *       {@code status.Error(codes.Code(bizCode), msg)}
 *       ⇒ 走 {@link GrpcErrFactory#grpcError(int, String)}（业务码进自定义 trailer）。</li>
 *   <li><b>gRPC 状态异常</b> ⇒ <b>原样重抛</b>（不二次包装）。</li>
 *   <li><b>其它 RuntimeException</b> ⇒ 兜底也必须走 {@link GrpcErrFactory}（<b>不是</b>裸
 *       {@code Status.INTERNAL}）：B 侧 comment-api 从 trailer 取业务码、从 description 取原文
 *       ⇒ 裸 {@code Status.INTERNAL} 没有 trailer ⇒ 那边只能落到默认码，
 *       envelope.code 就与 B 侧的 5001 不一致了。</li>
 * </ol>
 *
 * <h3>⚠️ 必须用 {@code getMsg()}（原始文案）而不是 {@code getMessage()}</h3>
 * <p>{@code getMessage()} 是 {@code "ErrCode:X, Errmsg:Y"} 的包装格式（给日志看）
 * ⇒ 拿它当 msg 传下去 ⇒ description 双重包装 ⇒ 客户端看到的 msg 与 B 侧不一致。
 */
@GrpcService
@Component
public class CommentGrpcService extends CommentServiceGrpc.CommentServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(CommentGrpcService.class);

    private final CommentDomainService domainService;

    public CommentGrpcService(CommentDomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void createComment(CreateCommentReq request, StreamObserver<CreateCommentResp> responseObserver) {
        invoke(() -> domainService.createComment(request), responseObserver);
    }

    @Override
    public void getComment(GetCommentReq request, StreamObserver<GetCommentResp> responseObserver) {
        invoke(() -> domainService.getComment(request), responseObserver);
    }

    @Override
    public void deleteComment(DeleteCommentReq request, StreamObserver<DeleteCommentResp> responseObserver) {
        invoke(() -> domainService.deleteComment(request), responseObserver);
    }

    @Override
    public void likeComment(LikeCommentReq request, StreamObserver<LikeCommentResp> responseObserver) {
        invoke(() -> domainService.likeComment(request), responseObserver);
    }

    @Override
    public void reportComment(ReportCommentReq request, StreamObserver<ReportCommentResp> responseObserver) {
        invoke(() -> domainService.reportComment(request), responseObserver);
    }

    @Override
    public void manageComment(ManageCommentReq request, StreamObserver<ManageCommentResp> responseObserver) {
        invoke(() -> domainService.manageComment(request), responseObserver);
    }

    @Override
    public void updateSubjectState(UpdateSubjectStateReq request,
                                   StreamObserver<UpdateSubjectStateResp> responseObserver) {
        invoke(() -> domainService.updateSubjectState(request), responseObserver);
    }

    /**
     * 统一的 gRPC 出口 —— 与 B 侧逐字一致。
     *
     * @param handler  业务调用
     * @param observer 响应观察者
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (CommentExceptions.Rpc business) {
            // 带业务码的领域异常 ⇒ 还原成 B 侧的 status.Error(codes.Code(bizCode), msg)
            // ⚠️ 必须用 getMsg()（原始文案）而不是 getMessage()（ErrCode:X, Errmsg:Y 的包装格式），
            //    后者会导致 description 变成双重包装，客户端看到的 msg 与 B 侧不一致。
            observer.onError(GrpcErrFactory.grpcError(business.getBizCode(), business.getMsg()));
        } catch (StatusRuntimeException sre) {
            observer.onError(sre);
        } catch (RuntimeException e) {
            LOG.error("comment-rpc handler failed", e);
            // ⚠️ 兜底也必须走 GrpcErrFactory（而不是裸 Status.INTERNAL）：
            //    comment-api 侧从 trailer 取业务码、从 description 取原文。
            //    裸 Status.INTERNAL 没有 trailer ⇒ 那边只能落到默认码，
            //    envelope.code 就与 B 侧的 5001 不一致了。
            observer.onError(GrpcErrFactory.grpcError(
                    ErrCodes.ERROR_SERVER_COMMON,
                    ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON)));
        }
    }
}
