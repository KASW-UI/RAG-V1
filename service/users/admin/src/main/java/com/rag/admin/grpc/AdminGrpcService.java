package com.rag.admin.grpc;

import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.UserEntity;
import com.rag.admin.domain.service.AdminDomainService;
import com.rag.admin.service.AdminHelpers;
import com.rag.proto.admin.AdminInfo;
import com.rag.proto.admin.AdminServiceGrpc;
import com.rag.proto.admin.BanUserReq;
import com.rag.proto.admin.BanUserResp;
import com.rag.proto.admin.CreateAdminReq;
import com.rag.proto.admin.CreateAdminResp;
import com.rag.proto.admin.CreateInviteCodeReq;
import com.rag.proto.admin.CreateInviteCodeResp;
import com.rag.proto.admin.DeleteUserReq;
import com.rag.proto.admin.DeleteUserResp;
import com.rag.proto.admin.GetSelfReq;
import com.rag.proto.admin.GetSelfResp;
import com.rag.proto.admin.GetUserListReq;
import com.rag.proto.admin.GetUserListResp;
import com.rag.proto.admin.GetUserReq;
import com.rag.proto.admin.GetUserResp;
import com.rag.proto.admin.LoginReq;
import com.rag.proto.admin.LoginResp;
import com.rag.proto.admin.LogoutReq;
import com.rag.proto.admin.LogoutResp;
import com.rag.proto.admin.ResetUserPasswordReq;
import com.rag.proto.admin.ResetUserPasswordResp;
import com.rag.proto.admin.UnBanUserReq;
import com.rag.proto.admin.UnBanUserResp;
import com.rag.proto.admin.UpdateSelfReq;
import com.rag.proto.admin.UpdateSelfResp;
import com.rag.proto.admin.UpdateUserReq;
import com.rag.proto.admin.UpdateUserResp;
import com.rag.proto.admin.UserInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>职责</b>：admin 域 13 个 gRPC RPC 的<b>thin wrapper</b> —— proto req → {@link AdminDomainService} → proto resp。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>纯机械</b>改动 ——
 *       ① 包名 {@code com.rag.users.admin.rpc.service} → {@code com.rag.admin.grpc}（D7）；
 *       ② 类名 {@code AdminServiceGrpcImpl} → {@code AdminGrpcService}（映射表 admin.md §0.5 已批准的改名）；
 *       ③ proto 包名 {@code com.rag.users.admin.proto} → {@code com.rag.proto.admin}（D7：10 个 {@code .proto}
 *       集中在根级 {@code proto/} 模块，{@code java_package} 属表达层）；
 *       ④ 实体包 {@code rpc.domain} → {@code domain.entity}、helper {@code rpc.service.AdminHelpers} →
 *       {@code service.AdminHelpers}（D7）。
 *       <b>方法体、13 个 RPC 的顺序、每个 RPC 的 proto 字段装配规则逐条照抄</b>
 *       （含 {@code updateUserResp} <b>不</b> set success、{@code getUserResp} <b>不</b> set found、
 *       {@code logoutResp} <b>不</b> set success 这三处 proto3 默认值语义）。</li>
 *   <li><b>语义还一样吗</b>：一样。gRPC 面（method 名 / message 名 / 字段号 / status code + description）
 *       全部逐条未动；{@link AdminDomainService} 抛出的 {@link StatusRuntimeException} 仍由
 *       {@link #invoke} <b>原样透传</b>（保留 code + description），其它异常仍兜底成 {@code Status.INTERNAL}
 *       —— ⇒ <b>gRPC wire 面与 B 侧逐字节等价</b>（本仓决策记录 D16（判定基准 = B 侧）：status code + description 是 L1 面，
 *       不得改写成 {@code BusinessException}）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminServiceGrpcImpl.java}
 *       （13 个 RPC 方法 + 2 个 entity→proto helper + {@code invoke} 通用转发，逐条照抄）。</li>
 * </ol>
 *
 * <p><b>⚠️ 与 D2 域内直连的关系</b>：C 侧 api 与 rpc <b>合并成一个进程</b>（D1 + D7）⇒
 * HTTP 层的 {@code AdminApiService} <b>不再</b>经 gRPC stub 调本类，而是<b>直接注入</b>
 * {@link AdminDomainService}（同进程、同事务上下文）。
 * ⇒ ⇒ <b>本类仍是 gRPC 出口</b>（对外 RPC 契约面），只是<b>不再被自己的 HTTP 层经网络调用</b>。
 *
 * <p><b>⚠️ 三处 proto3 默认值（不得"顺手补 true"）</b>：
 * <ul>
 *   <li>{@code logout}：{@code LogoutResp.success} <b>不</b> set ⇒ proto3 默认 {@code false}
 *       （B 出处：B 侧 rpc 层同样不设）；</li>
 *   <li>{@code updateUser}：{@code UpdateUserResp.success} <b>不</b> set ⇒ {@code false}
 *       （B 出处：B 侧 {@code updateuserlogic.go:105-113} 只 set 了 {@code User}）；</li>
 *   <li>{@code getUser}：{@code GetUserResp.found} <b>不</b> set ⇒ {@code false}
 *       （B 出处：B 侧 rpc 层 {@code getuserlogic.go:42-50} 只 return {@code User}）。
 *       ⇒ HTTP 面仍然是 {@code found=true}，因为 <b>api 层</b>显式置 true（见 {@code AdminApiService#getUser}）。</li>
 * </ul>
 */
@GrpcService
public class AdminGrpcService extends AdminServiceGrpc.AdminServiceImplBase {

    private final AdminDomainService adminDomainService;

    public AdminGrpcService(AdminDomainService adminDomainService) {
        this.adminDomainService = adminDomainService;
    }

    // ====================== 1. Login ======================

    @Override
    public void login(LoginReq request, StreamObserver<LoginResp> responseObserver) {
        invoke(() -> {
            long uid = adminDomainService.login(request.getUsername(), request.getPassword());
            return LoginResp.newBuilder().setUid(uid).build();
        }, responseObserver);
    }

    // ====================== 2. Logout ======================

    @Override
    public void logout(LogoutReq request, StreamObserver<LogoutResp> responseObserver) {
        invoke(() -> {
            adminDomainService.logout(request.getToken());
            return LogoutResp.newBuilder().build();   // Success 字段保持 proto3 默认 false（1:1 跟随 B 侧）
        }, responseObserver);
    }

    // ====================== 3. GetSelf ======================

    @Override
    public void getSelf(GetSelfReq request, StreamObserver<GetSelfResp> responseObserver) {
        invoke(() -> {
            AdminEntity admin = adminDomainService.getSelf(request.getUid());
            return GetSelfResp.newBuilder().setAdmin(toProtoAdmin(admin)).build();
        }, responseObserver);
    }

    // ====================== 4. UpdateSelf ======================

    @Override
    public void updateSelf(UpdateSelfReq request, StreamObserver<UpdateSelfResp> responseObserver) {
        invoke(() -> {
            AdminEntity admin = adminDomainService.updateSelf(
                    request.getUid(),
                    request.getUsername(),
                    request.getPassword(),
                    request.getEmail(),
                    request.getExtraInfoMap() == null || request.getExtraInfoMap().isEmpty()
                            ? null
                            : new HashMap<>(request.getExtraInfoMap())
            );
            return UpdateSelfResp.newBuilder()
                    .setSuccess(true)
                    .setAdmin(toProtoAdmin(admin))
                    .build();
        }, responseObserver);
    }

    // ====================== 5. CreateAdmin ======================

    @Override
    public void createAdmin(CreateAdminReq request, StreamObserver<CreateAdminResp> responseObserver) {
        invoke(() -> {
            long uid = adminDomainService.createAdmin(
                    request.getUsername(),
                    request.getPassword(),
                    request.getEmail(),
                    request.getExtraInfoMap() == null || request.getExtraInfoMap().isEmpty()
                            ? null
                            : new HashMap<>(request.getExtraInfoMap()),
                    request.getInviteCode()
            );
            return CreateAdminResp.newBuilder().setUid(uid).build();
        }, responseObserver);
    }

    // ====================== 6. CreateInviteCode ======================

    @Override
    public void createInviteCode(CreateInviteCodeReq request, StreamObserver<CreateInviteCodeResp> responseObserver) {
        invoke(() -> {
            AdminDomainService.InviteCodeResult result = adminDomainService.createInviteCode(request.getOperatorId());
            return CreateInviteCodeResp.newBuilder()
                    .setInviteCode(result.code())
                    .setExpiresAt(result.expiresAt())
                    .build();
        }, responseObserver);
    }

    // ====================== 7. BanUser ======================

    @Override
    public void banUser(BanUserReq request, StreamObserver<BanUserResp> responseObserver) {
        invoke(() -> {
            adminDomainService.banUser(request.getUid(), request.getOperatorId());
            return BanUserResp.newBuilder().setSuccess(true).build();
        }, responseObserver);
    }

    // ====================== 8. UnBanUser ======================

    @Override
    public void unBanUser(UnBanUserReq request, StreamObserver<UnBanUserResp> responseObserver) {
        invoke(() -> {
            adminDomainService.unBanUser(request.getUid(), request.getOperatorId());
            return UnBanUserResp.newBuilder().setSuccess(true).build();
        }, responseObserver);
    }

    // ====================== 9. GetUser ======================

    @Override
    public void getUser(GetUserReq request, StreamObserver<GetUserResp> responseObserver) {
        invoke(() -> {
            UserEntity user = adminDomainService.getUser(request.getUid());
            // DRIFT-002 说明：此处<b>不</b>设 found 是对的 —— B 侧的 rpc 层
            // getuserlogic.go:42-50 确实只 return &pb.GetUserResp{User: ...}，没设 pb 的 found。
            // 但 B 侧的 <b>api 层</b> getuserlogic.go:59 构造 types.GetUserResp 时写死了 Found: true，
            // 那才是客户端最终看到的值。admin-api 的 AdminGetUserService 已据此显式置 true；
            // 这里保持 false 只描述"rpc 层行为"，不要据此认为端到端 found 是 false。
            return GetUserResp.newBuilder().setUser(toProtoUser(user)).build();
        }, responseObserver);
    }

    // ====================== 10. GetUserList ======================

    @Override
    public void getUserList(GetUserListReq request, StreamObserver<GetUserListResp> responseObserver) {
        invoke(() -> {
            AdminDomainService.UserListResult result = adminDomainService.getUserList(
                    request.getPage(),
                    request.getPageSize(),
                    request.getKeyword()
            );
            GetUserListResp.Builder builder = GetUserListResp.newBuilder().setTotal(result.total());
            for (UserEntity u : result.users()) {
                builder.addList(toProtoUser(u));
            }
            return builder.build();
        }, responseObserver);
    }

    // ====================== 11. UpdateUser ======================

    @Override
    public void updateUser(UpdateUserReq request, StreamObserver<UpdateUserResp> responseObserver) {
        invoke(() -> {
            UserEntity user = adminDomainService.updateUser(
                    request.getUid(),
                    request.getOperatorId(),
                    request.getUsername(),
                    request.getPassword(),
                    request.getEmail(),
                    request.getExtraInfoMap() == null || request.getExtraInfoMap().isEmpty()
                            ? null
                            : new HashMap<>(request.getExtraInfoMap())
            );
            // 🔴 2026-10-23：去掉 setSuccess(true)，对齐 B 侧（DRIFT-ARPC-002）。
            // B 出处：service/user/admin/rpc/internal/logic/updateuserlogic.go:105-113
            //     return &pb.UpdateUserResp{
            //         User: &pb.UserInfo{ … },
            //     }, nil
            // B 侧 **只 set 了 User**，没有 set Success ⇒ proto3 bool 的默认值 false
            // ⇒ 线上序列化时该字段缺席，客户端读到 **false**。
            // RAG 原先 setSuccess(true) ⇒ 线上是 varint 1，客户端读到 true。
            // ⇒ 这是 **wire 层可观察差异**（不是「业务上无所谓的默认值」）。
            //
            // ⚠️ **不要透传，也不要在此补 true**：本层必须与 B 侧的 rpc 层逐字一致。
            //   admin-api 那层 B 侧 本来就硬编码 true
            //   （service/user/admin/api/internal/logic/admin/updateuserlogic.go:68），
            //   所以 HTTP 面两侧不变，仍是 {"success":true} —— 与本条无关、也不受影响。
            // 守门：AdminRpcGoWireParityTest#updateUserRespSuccessMatchesGoWire。
            return UpdateUserResp.newBuilder().setUser(toProtoUser(user)).build();
        }, responseObserver);
    }

    // ====================== 12. DeleteUser ======================

    @Override
    public void deleteUser(DeleteUserReq request, StreamObserver<DeleteUserResp> responseObserver) {
        invoke(() -> {
            adminDomainService.deleteUser(request.getUid(), request.getOperatorId());
            return DeleteUserResp.newBuilder().setSuccess(true).build();
        }, responseObserver);
    }

    // ====================== 13. ResetUserPassword ======================

    @Override
    public void resetUserPassword(ResetUserPasswordReq request, StreamObserver<ResetUserPasswordResp> responseObserver) {
        invoke(() -> {
            adminDomainService.resetUserPassword(request.getUid(), request.getOperatorId());
            return ResetUserPasswordResp.newBuilder().setSuccess(true).build();
        }, responseObserver);
    }

    // ====================== 内部 helper：entity → proto ======================

    /**
     * AdminEntity → AdminInfo proto
     * 注意：email 是 nullable（{@code *string}），用 AdminHelpers.stringValue 转成空字符串。
     * extra_info map 空时也要 putAllExtraInfo({}) —— B 侧如此。
     */
    private static AdminInfo toProtoAdmin(AdminEntity admin) {
        AdminInfo.Builder b = AdminInfo.newBuilder()
                .setUid(admin.getUid() == null ? 0L : admin.getUid())
                .setUsername(admin.getUsername() == null ? "" : admin.getUsername())
                .setEmail(AdminHelpers.stringValue(admin.getEmail()));
        Map<String, String> extra = admin.getExtraInfo();
        if (extra != null && !extra.isEmpty()) {
            b.putAllExtraInfo(extra);
        } else {
            b.putAllExtraInfo(new HashMap<>());
        }
        return b.build();
    }

    /**
     * UserEntity → UserInfo proto
     * 注意：admin-rpc 不 setScore（proto3 默认 0，1:1 跟随 B 侧）。
     * email 非 null（User 表定义），但用 null-safe 兜底。
     */
    private static UserInfo toProtoUser(UserEntity user) {
        UserInfo.Builder b = UserInfo.newBuilder()
                .setUid(user.getUid() == null ? 0L : user.getUid())
                .setUsername(user.getUsername() == null ? "" : user.getUsername())
                .setEmail(user.getEmail() == null ? "" : user.getEmail())
                .setStatus(user.getStatus() == null ? 0L : user.getStatus());
        Map<String, String> extra = user.getExtraInfo();
        if (extra != null && !extra.isEmpty()) {
            b.putAllExtraInfo(extra);
        } else {
            b.putAllExtraInfo(new HashMap<>());
        }
        return b.build();
    }

    /**
     * 通用转发：成功 obs.onNext + onCompleted；失败 obs.onError。
     * 任何 {@link StatusRuntimeException} 原样透传（保留 code + description），
     * 其它异常兜底成 INTERNAL（与 B 侧统一行为对齐）。
     */
    private static <Req, Resp> void invoke(
            java.util.function.Supplier<Resp> logic,
            StreamObserver<Resp> obs) {
        try {
            Resp resp = logic.get();
            obs.onNext(resp);
            obs.onCompleted();
        } catch (StatusRuntimeException e) {
            obs.onError(e);
        } catch (Throwable t) {
            obs.onError(Status.INTERNAL
                    .withDescription(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .asRuntimeException());
        }
    }
}
