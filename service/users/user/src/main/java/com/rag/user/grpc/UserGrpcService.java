package com.rag.user.grpc;

import com.rag.proto.user.CreateUserReq;
import com.rag.proto.user.CreateUserResp;
import com.rag.proto.user.DeleteUserReq;
import com.rag.proto.user.DeleteUserResp;
import com.rag.proto.user.GetAvatarHistoryReq;
import com.rag.proto.user.GetAvatarHistoryResp;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.LoginReq;
import com.rag.proto.user.LoginResp;
import com.rag.proto.user.LogoutReq;
import com.rag.proto.user.LogoutResp;
import com.rag.proto.user.SelectAvatarReq;
import com.rag.proto.user.SelectAvatarResp;
import com.rag.proto.user.UpdateUserReq;
import com.rag.proto.user.UpdateUserResp;
import com.rag.proto.user.UploadAvatarReq;
import com.rag.proto.user.UploadAvatarResp;
import com.rag.proto.user.UserServiceGrpc;
import com.rag.user.domain.service.UserDomainService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p><b>UserService gRPC 实现</b> —— 9 个 RPC 方法的 thin wrapper。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/service/impl/UserServiceGrpcImpl.java
 * </pre>
 *
 * <h2>⚠️ L4 idiomatization：类名与包名（D7 包压平 + 映射表 §0.5）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th><th>依据</th></tr>
 *   <tr><td>{@code com.rag.users.user.rpc.service.impl.UserServiceGrpcImpl}</td>
 *       <td>{@code com.rag.user.grpc.UserGrpcService}</td>
 *       <td>映射表 §0.5「{@code users/user/rpc/service/impl/} → {@code grpc/}（压平 + 类改名
 *           {@code UserServiceGrpcImpl} → {@code UserGrpcService}）」</td></tr>
 *   <tr><td>{@code com.rag.users.user.proto.*}（B 侧 pb 包名）</td>
 *       <td>{@code com.rag.proto.user.*}</td>
 *       <td>D7：proto 集中到根级 {@code proto/} 模块，pb 包名 {@code com.rag.proto.<域>}</td></tr>
 * </table>
 * ⇒ <b>pb 包名是唯一差异</b>：{@code users.proto} 的 message / 字段号 / 字段名 / 枚举值
 * 与 B 侧<b>逐条一致</b>（已 diff 验证）⇒ ⇒ <b>wire 层完全一致</b>。
 *
 * <h2>语义等价论证（逐条）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>{@code @GrpcService}</td>
 *       <td>逐字一致（devh starter 扫到 {@code @GrpcService} ⇒ 自动注册进 gRPC server）</td></tr>
 *   <tr><td>{@code extends UserServiceGrpc.UserServiceImplBase}</td>
 *       <td>逐字一致（生成物在根级 {@code proto/} 模块）</td></tr>
 *   <tr><td>9 个方法各 3 段：<code>try { resp = svc.xxx(req); onNext(resp); onCompleted(); }</code>
 *           ＋ {@code catch (StatusRuntimeException e) → log.error + onError(e)}
 *           ＋ {@code catch (Throwable t) → onError(Status.INTERNAL.withDescription(类名 + ": " + 消息))}</td>
 *       <td>逐字一致（9 个方法<b>逐个</b>照抄，含各自的 log 文案与方法名）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>两层 catch 都不能省</b>：
 * <ul>
 *   <li><b>第一层</b>（{@code StatusRuntimeException}）：领域层已把
 *       {@code NotFound / AvatarHistoryNotFound / AlreadyExists / DEADLINE_EXCEEDED} 等
 *       映射成 {@code Status} ⇒ 原样 {@code onError} ⇒ <b>保留 code + description</b>
 *       （调用方拿得到准确的状态码与描述）；</li>
 *   <li><b>第二层</b>（{@code Throwable}）：兜底收敛成 {@code INTERNAL}，description = {@code "类名: 消息"}。
 *       缺这一层时 grpc-java 会把未映射异常当成 {@code UNKNOWN} 发给 client
 *       ⇒ 调用方拿到的状态码从 {@code INTERNAL} 退化成 {@code UNKNOWN}（B 侧 DRIFT-02）。</li>
 * </ul>
 *
 * <h2>框架职责</h2>
 * <ul>
 *   <li>{@code @GrpcService} ⇒ 被 devh starter 的 {@code GrpcServiceDiscoverer} 扫到 ⇒ 自动起 gRPC server；</li>
 *   <li>方法分发由 starter 完成 ⇒ 本类<b>只写方法体</b>；</li>
 *   <li>grpc-java 机制约束（一个 service 一个实现类）—— <b>非</b>主动合并。</li>
 * </ul>
 *
 * <p>⚠️ <b>指标不在本类</b>：{@code user_rpc_login_total} / {@code user_rpc_register_total}
 * 由领域层（{@link UserDomainService} → {@code UserRpcMetrics}）记录，位置与 B 侧一致。
 */
@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(UserGrpcService.class);

    private final UserDomainService userDomainService;

    public UserGrpcService(UserDomainService userDomainService) {
        this.userDomainService = userDomainService;
    }

    @Override
    public void login(LoginReq request, StreamObserver<LoginResp> responseObserver) {
        try {
            LoginResp resp = userDomainService.login(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("login RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("login RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void register(CreateUserReq request, StreamObserver<CreateUserResp> responseObserver) {
        try {
            CreateUserResp resp = userDomainService.register(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("register RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("register RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void logout(LogoutReq request, StreamObserver<LogoutResp> responseObserver) {
        try {
            LogoutResp resp = userDomainService.logout(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("logout RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("logout RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getUser(GetUserReq request, StreamObserver<GetUserResp> responseObserver) {
        try {
            GetUserResp resp = userDomainService.getUser(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("getUser RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("getUser RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void updateUser(UpdateUserReq request, StreamObserver<UpdateUserResp> responseObserver) {
        try {
            UpdateUserResp resp = userDomainService.updateUser(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("updateUser RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("updateUser RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void deleteUser(DeleteUserReq request, StreamObserver<DeleteUserResp> responseObserver) {
        try {
            DeleteUserResp resp = userDomainService.deleteUser(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("deleteUser RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("deleteUser RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void selectAvatar(SelectAvatarReq request, StreamObserver<SelectAvatarResp> responseObserver) {
        try {
            SelectAvatarResp resp = userDomainService.selectAvatar(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("selectAvatar RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("selectAvatar RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void uploadAvatar(UploadAvatarReq request, StreamObserver<UploadAvatarResp> responseObserver) {
        try {
            UploadAvatarResp resp = userDomainService.uploadAvatar(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("uploadAvatar RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("uploadAvatar RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getAvatarHistory(GetAvatarHistoryReq request, StreamObserver<GetAvatarHistoryResp> responseObserver) {
        try {
            GetAvatarHistoryResp resp = userDomainService.getAvatarHistory(request);
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            log.error("getAvatarHistory RPC failed: {}", e.getStatus(), e);
            responseObserver.onError(e);
        } catch (Throwable t) {
            log.error("getAvatarHistory RPC failed with unmapped throwable", t);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }
}
