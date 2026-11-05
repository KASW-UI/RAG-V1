package com.rag.favorite.grpc;

import com.rag.favorite.contract.ErrCodes;
import com.rag.favorite.contract.GrpcErrorMapper;
import com.rag.favorite.domain.service.FavoriteDomainService;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.proto.favorite.CreateFavoriteFolderReq;
import com.rag.proto.favorite.CreateFavoriteFolderResp;
import com.rag.proto.favorite.CreateFavoriteReq;
import com.rag.proto.favorite.CreateFavoriteResp;
import com.rag.proto.favorite.DeleteFavoriteFolderReq;
import com.rag.proto.favorite.DeleteFavoriteFolderResp;
import com.rag.proto.favorite.DeleteFavoriteReq;
import com.rag.proto.favorite.DeleteFavoriteResp;
import com.rag.proto.favorite.FavoriteServiceGrpc;
import com.rag.proto.favorite.GetFavoriteFoldersReq;
import com.rag.proto.favorite.GetFavoriteFoldersResp;
import com.rag.proto.favorite.GetFavoritesByFolderReq;
import com.rag.proto.favorite.GetFavoritesByFolderResp;
import com.rag.proto.favorite.UpdateFavoriteFolderNameReq;
import com.rag.proto.favorite.UpdateFavoriteFolderNameResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <p><b>gRPC 服务入口</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/impl/FavoriteServiceGrpcImpl.java:29-146}
 * （逐条照抄）。
 *
 * <p><b>Go 的 server 层零业务逻辑</b>，全部业务在 {@code internal/logic/*}。
 * 本类对应 Java 侧的 {@link FavoriteDomainService}，保持同样的分层。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>包名压平</b>：{@code com.rag.favorite.favorite.rpc.service.impl} ⇒
 *           {@code com.rag.favorite.grpc}（D7 · 域内技术角色包）。</li>
 *       <li><b>少一个 {@code @Component}</b>：devh 的 {@code @GrpcService}
 *           <b>本身带 {@code @Service} + {@code @Bean}</b>（devh 3.1.0 的
 *           {@code net/devh/boot/grpc/server/service/GrpcService.java:44-49}）
 *           ⇒ 再叠一个 {@code @Component} 是<b>重复声明</b>（like 域同款取舍）。</li>
 *       <li><b>pb 包名</b>：{@code com.rag.favorite.favorite.proto} ⇒
 *           {@code com.rag.proto.favorite}（D7 · proto 集中到根级模块）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 7 个方法、错误出口的三段 catch 顺序、兜底码逐条未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>🔴 错误出口：本域<b>唯一</b>的转换点</h2>
 *
 * <p>业务层抛 {@link FavoriteExceptions.Rpc}（<b>同时带 gRPC 码与业务码</b>），
 * 这里统一转成 {@link GrpcErrorMapper#grpcError(Status.Code, int)} 上线。
 *
 * <p>⚠️ <b>必须用 {@code responseObserver.onError(...)} 而不是让异常逃逸</b>：
 * grpc-java 在 {@code UnaryServerCallListener.onHalfClose} 里调用业务方法，
 * 在那里 {@code throw} 会让异常逃出流监听回调，被当成<b>传输层内部错误</b>，
 * description 被替换成 {@code "Application error processing RPC"} ——
 * <b>文案当场全丢</b>，而文案正是本域还原业务码的唯一载体
 * （见 {@link GrpcErrorMapper} 的类注释）。
 *
 * <p>🔴 <b>与 like 域的关键差异</b>：like 的 {@code LikeGrpcError.grpcError(bizCode, msg)}
 * 把业务码塞进 {@code grpc-status}；本域的
 * {@code FavoriteGrpcError.grpcError(Status.Code, bizCode)} 是
 * <b>标准 gRPC 码 + 查表文案</b>。因此这里<b>原样透传</b>业务层给的 gRPC 码，
 * 绝不为「躲开熔断失败码」改写成 {@code UNKNOWN} —— 本域 Go 侧本来就大量发
 * {@code Internal} 与 {@code Unavailable}，它们确实计入下游失败统计。
 */
@GrpcService
public class FavoriteGrpcService extends FavoriteServiceGrpc.FavoriteServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteGrpcService.class);

    private final FavoriteDomainService domainService;

    public FavoriteGrpcService(FavoriteDomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void createFavoriteFolder(CreateFavoriteFolderReq request,
                                      StreamObserver<CreateFavoriteFolderResp> responseObserver) {
        invoke(() -> domainService.createFavoriteFolder(request), responseObserver);
    }

    @Override
    public void createFavorite(CreateFavoriteReq request,
                               StreamObserver<CreateFavoriteResp> responseObserver) {
        invoke(() -> domainService.createFavorite(request), responseObserver);
    }

    @Override
    public void deleteFavoriteFolder(DeleteFavoriteFolderReq request,
                                     StreamObserver<DeleteFavoriteFolderResp> responseObserver) {
        invoke(() -> domainService.deleteFavoriteFolder(request), responseObserver);
    }

    @Override
    public void deleteFavorite(DeleteFavoriteReq request,
                               StreamObserver<DeleteFavoriteResp> responseObserver) {
        invoke(() -> domainService.deleteFavorite(request), responseObserver);
    }

    @Override
    public void updateFavoriteFolderName(UpdateFavoriteFolderNameReq request,
                                         StreamObserver<UpdateFavoriteFolderNameResp> responseObserver) {
        invoke(() -> domainService.updateFavoriteFolderName(request), responseObserver);
    }

    @Override
    public void getFavoriteFolders(GetFavoriteFoldersReq request,
                                   StreamObserver<GetFavoriteFoldersResp> responseObserver) {
        invoke(() -> domainService.getFavoriteFolders(request), responseObserver);
    }

    @Override
    public void getFavoritesByFolder(GetFavoritesByFolderReq request,
                                     StreamObserver<GetFavoritesByFolderResp> responseObserver) {
        invoke(() -> domainService.getFavoritesByFolder(request), responseObserver);
    }

    /**
     * 统一出口模板。
     *
     * <p>⚠️ <b>三段 catch 的顺序不可调换</b>：
     * {@code FavoriteExceptions.Rpc} 是 {@code RuntimeException} 的子类，
     * {@code StatusRuntimeException} 也是 ⇒ <b>业务异常必须排第一</b>，
     * 否则业务码会被兜底分支换成 5001。
     *
     * @param handler 业务调用；抛 {@link FavoriteExceptions.Rpc} 表示可解释的业务失败
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (FavoriteExceptions.Rpc business) {
            // ⚠️ 必须用 getMsg()（原始文案）而不是 getMessage()（ErrCode:X 包装格式），
            //    后者会导致 description 被二次包装，客户端看到的 msg 与 Go 不一致。
            observer.onError(GrpcErrorMapper.grpcError(
                    business.getGrpcCode(), business.getBizCode()));
        } catch (StatusRuntimeException sre) {
            // 领域层理论上不抛 gRPC 异常；放行以防将来引入。
            observer.onError(sre);
        } catch (RuntimeException e) {
            LOG.error("favorite-rpc handler failed", e);
            // ⚠️ 兜底必须是 5001 而不是「把异常原文发给客户端」：
            //    原生异常消息会泄漏 SQL / 连接串等内部信息。
            //    走 GrpcErrorMapper 保证 favorite-api 侧能从 description 反查出业务码。
            observer.onError(GrpcErrorMapper.grpcError(
                    Status.Code.INTERNAL,
                    ErrCodes.ERROR_SERVER_COMMON));
        }
    }
}
