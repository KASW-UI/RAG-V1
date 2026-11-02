package com.rag.user.service;

import com.rag.common.exception.BusinessException;
import com.rag.common.security.JwtUtil;
import com.rag.user.config.UserApiProperties;
import com.rag.user.contract.ErrCodes;
import com.rag.user.contract.ErrMsg;
import com.rag.user.domain.service.UserDomainService;
import com.rag.user.dto.avatar.AvatarHistoryItem;
import com.rag.user.dto.avatar.GetAvatarHistoryResp;
import com.rag.user.dto.avatar.SelectAvatarResp;
import com.rag.user.dto.avatar.UploadAvatarResp;
import com.rag.user.dto.auth.LoginResp;
import com.rag.user.dto.auth.LogoutResp;
import com.rag.user.dto.user.CreateUserResp;
import com.rag.user.dto.user.DeleteUserResp;
import com.rag.user.dto.user.GetUserResp;
import com.rag.user.dto.user.UpdateUserResp;
import com.rag.user.dto.user.UserInfo;
import com.rag.user.metrics.UserApiMetrics;
import com.rag.users.common.IdentityResolver;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p><b>user 域 HTTP 层用例集</b> —— 把 B 侧 9 个 api Service 合并到一个类。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/service/}
 * 下的 9 个类（{@code UserLoginService} · {@code UserRegisterService} · {@code UserGetService} ·
 * {@code UserUpdateService} · {@code UserDeleteService} · {@code UserLogoutService} ·
 * {@code AvatarUploadService} · {@code AvatarSelectService} · {@code AvatarHistoryService}）
 * —— 9 个方法体<b>一对一</b>照抄。
 *
 * <h2>⚠️ L4 落点变化：域内直连（决策 D2）</h2>
 * <p>B 侧 api 进程经 gRPC 调 rpc 进程（{@code UserRpcClient}）；C 侧按 <b>D1 + D7</b> 把 api + rpc
 * <b>合并成一个进程</b> ⇒ <b>域内调用直连</b> ⇒ <b>不</b>再有 {@code api/client/} 包。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li><b>错误码映射逐条保留</b>：B 侧 api 层捕获 gRPC 的 {@code StatusRuntimeException} 后映射成业务码；
 *       C 侧的领域服务<b>仍然抛同一类型的</b> {@code StatusRuntimeException}（io.grpc）
 *       ⇒ ⇒ <b>catch 块与映射表逐字保留</b>，不需要任何改写。</li>
 *   <li><b>「gRPC code → 业务码」的通用映射仍在 advice 层</b>（{@code UserApiExceptionHandler#handleRPC}）
 *       ⇒ 与 B 侧同一处 ⇒ 不产生第二份映射。</li>
 *   <li><b>⚠️ 唯一不可达的一支</b>：B 侧 api 层捕获「客户端熔断打开」（{@code GoStyleCircuitOpenException}）
 *       并递增 {@code loginRpcError} / {@code registerRpcError}；
 *       C 侧 api → 领域是<b>同进程直连</b> ⇒ <b>没有客户端熔断</b> ⇒ 该分支不可达
 *       ⇒ <b>不保留</b>（保留一个永远不命中的 catch = §3.4.1 D-2「零消费者的代码」），
 *       <b>登记为本域的 L4 后果</b>。</li>
 * </ul>
 */
@Service
public class UserApiService {

    private static final Logger log = LoggerFactory.getLogger(UserApiService.class);

    private final UserDomainService userDomainService;
    private final UserApiProperties properties;
    private final UserApiMetrics metrics;
    private final UserApiSupport support;

    public UserApiService(UserDomainService userDomainService,
                          UserApiProperties properties,
                          UserApiMetrics metrics,
                          UserApiSupport support) {
        this.userDomainService = userDomainService;
        this.properties = properties;
        this.metrics = metrics;
        this.support = support;
    }

    // ============== 1. login（public） ==============

    /**
     * 对应 B 侧 {@code UserLoginService#login}。
     *
     * <p><b>L2 业务语义（6 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. 领域层 login(LoginReq{Username, Password})
     *      → gRPC codes.Internal → ErrorServerCommon (5001) + loginRpcError
     *      → 其它（默认）        → CodeServerBusy    (1015) + loginRpcError
     *
     *   2. resp.Status == 1（密码错或用户不存在）→ ErrorLoginWrong (1002) + loginWrongPassword
     *
     *   3. resp.Status == 2（用户封禁）          → ErrorUserBanned (1011) + loginUserBanned
     *
     *   4. 签发 JWT 抛异常                        → ErrorTokenRuntime (1006) + loginTokenError
     *
     *   5. 成功 → loginSuccess + 返回 {token}
     * </pre>
     */
    public LoginResp login(com.rag.user.dto.auth.LoginReq req, HttpServletRequest httpRequest) {
        com.rag.proto.user.LoginReq rpcReq = com.rag.proto.user.LoginReq.newBuilder()
                .setUsername(req.username() == null ? "" : req.username())
                .setPassword(req.password() == null ? "" : req.password())
                .build();

        com.rag.proto.user.LoginResp rpcResp;
        try {
            rpcResp = userDomainService.login(rpcReq);
        } catch (StatusRuntimeException e) {
            metrics.getLoginRpcError().increment();
            log.error("login RPC error, username={}", req.username(), e);
            // 1:1 对齐 B 侧 LoginLogic 的 switch (e.getStatus().getCode())：
            //   codes.Internal → ErrorServerCommon (5001)
            //   其它（default）→ CodeServerBusy    (1015)
            io.grpc.Status.Code code = e.getStatus().getCode();
            if (code == io.grpc.Status.Code.INTERNAL) {
                throw new BusinessException(
                        ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(
                    ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        // rpcResp.Status == 1 → 密码错或用户不存在（rpc 不区分）
        if (rpcResp.getStatus() == 1L) {
            metrics.getLoginWrongPassword().increment();
            log.info("login failed : wrong password,username : {}", req.username());
            throw new BusinessException(ErrCodes.ERROR_LOGIN_WRONG, ErrMsg.getMsg(ErrCodes.ERROR_LOGIN_WRONG));
        }

        // rpcResp.Status == 2 → 用户封禁（DB status=1）
        if (rpcResp.getStatus() == 2L) {
            metrics.getLoginUserBanned().increment();
            log.info("login failed : user banned,username : {}", req.username());
            throw new BusinessException(ErrCodes.ERROR_USER_BANNED, ErrMsg.getMsg(ErrCodes.ERROR_USER_BANNED));
        }

        // 签发 JWT —— 与 Go jwt.GetToken(secret, now, expire, uid) 1:1
        long nowSec = System.currentTimeMillis() / 1000L;
        long expireSec = properties.getUserAuth().getAccessExpire();
        String accessSecret = properties.getUserAuth().getAccessSecret();
        String token;
        try {
            token = JwtUtil.getToken(accessSecret, nowSec, expireSec, rpcResp.getUid());
        } catch (Throwable t) {
            metrics.getLoginTokenError().increment();
            log.error("generate token failed : {}", t.getMessage(), t);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        metrics.getLoginSuccess().increment();
        log.info("login success,username : {}", req.username());

        return new LoginResp(token);
    }

    // ============== 2. register（public） ==============

    /**
     * 对应 B 侧 {@code UserRegisterService#register}。
     *
     * <p><b>L2 业务语义（3 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. 领域层 register(CreateUserReq{Username, Password, Email, ExtraInfo})
     *      → gRPC codes.Internal      → ErrorServerCommon (5001) + registerRpcError
     *      → gRPC codes.AlreadyExists → ErrorUserExist    (1001) + registerRpcError
     *      → 其它（默认）             → CodeServerBusy    (1015) + registerRpcError
     *
     *   2. 成功 → registerSuccess + 返回 {uid}
     * </pre>
     *
     * <p>⚠️ B 侧的 gRPC code → 业务码映射<b>不在本类</b>，而是<b>原样上抛</b>交给 advice 的通用映射
     * （{@code handleRPC}）⇒ C 侧逐条保留「原样上抛」这一写法。
     */
    public CreateUserResp register(com.rag.user.dto.user.CreateUserReq req, HttpServletRequest httpRequest) {
        com.rag.proto.user.CreateUserReq rpcReq = com.rag.proto.user.CreateUserReq.newBuilder()
                .setUsername(req.username() == null ? "" : req.username())
                .setPassword(req.password() == null ? "" : req.password())
                .setEmail(req.email() == null ? "" : req.email())
                .putAllExtraInfo(req.extraInfo() == null ? Map.of() : req.extraInfo())
                .build();

        com.rag.proto.user.CreateUserResp rpcResp;
        try {
            rpcResp = userDomainService.register(rpcReq);
        } catch (StatusRuntimeException e) {
            metrics.getRegisterRpcError().increment();
            log.error("register RPC error, username={}", req.username(), e);
            // 顶层 advice 按 gRPC code 映射业务码（INTERNAL→5001, ALREADY_EXISTS→1001, default→1015）
            throw e;
        }

        metrics.getRegisterSuccess().increment();

        return new CreateUserResp(rpcResp.getUid());
    }

    // ============== 3. getUser（JWT） ==============

    /**
     * 对应 B 侧 {@code UserGetService#getUser}。
     *
     * <p><b>L2 业务语义（8 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. 身份判定（claim → 存在性 → UID 一致 → status presence → status 活跃）：
     *      ErrInvalidClaim        → ErrorTokenRuntime    (1006)
     *      ErrUserNotFound        → ErrorUserNotExist    (1003)
     *      ErrUserInactive        → ErrorUserBanned      (1011)
     *      ErrStatusUnavailable   → CodeServerBusy        (1015)
     *      ErrIdentityMismatch    → ErrorServerCommon    (5001)
     *
     *   2. gRPC 异常：
     *      codes.NotFound  → ErrorUserNotExist    (1003)
     *      codes.Internal  → ErrorServerCommon    (5001)
     *      default         → CodeServerBusy       (1015)
     *
     *   3. 成功 → 返回 {user, found: true}（user 只填 uid/username/email/extraInfo 4 字段，score 不填）
     * </pre>
     *
     * <p><b>⚠️ L4 落点差异（D2 直连 ⇒ 少一次跨进程往返，判定链不变）</b>：
     * B 侧 {@code IdentityResolver.resolveUser(request, userRpc)} <b>内部</b>做那次 RPC 并返回完整 user；
     * C 侧合并为单进程 ⇒ 改为：
     * <ol>
     *   <li>{@code IdentityResolver.claimedUID(request)}（同 B 的第 1 步）；</li>
     *   <li><b>一次</b>领域层 {@code getUser} 取回完整用户（B 侧也是一次查询，语义等价）；</li>
     *   <li>{@code IdentityResolver.verifyUser(uid, record)} 做第 3~6 步判定
     *       —— <b>判定顺序与 B 的 resolveUser 完全一致</b>，且是共享层为此专门暴露的方法
     *       （{@code users-common} lane 的 U-30）。</li>
     * </ol>
     * ⇒ ⇒ 判定链 6 步、错误类型、错误码<b>逐条一致</b>；只是<b>取数通道</b>从 gRPC 变成同进程直连。
     */
    public GetUserResp getUser(com.rag.user.dto.user.GetUserReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            uid = IdentityResolver.claimedUID(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            log.error("getUser ErrInvalidClaim", e);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        com.rag.proto.user.GetUserResp rpcResp;
        try {
            rpcResp = userDomainService.getUser(com.rag.proto.user.GetUserReq.newBuilder().setUid(uid).build());
        } catch (StatusRuntimeException e) {
            log.error("getUser RPC error", e);
            // 原样上抛 ⇒ advice 的通用映射（NOT_FOUND→1003 / INTERNAL→5001 / default→1015）
            throw e;
        }

        // 身份判定链的第 3~6 步（存在性 → UID 一致 → status presence → status 活跃）
        com.rag.proto.user.UserInfo protoUser = rpcResp.getUser();
        IdentityResolver.UserStatusRecord record = new IdentityResolver.UserStatusRecord(
                rpcResp.getFound(),
                protoUser == null ? 0L : protoUser.getUid(),
                (protoUser == null || !protoUser.hasStatus()) ? null : protoUser.getStatus());
        try {
            IdentityResolver.verifyUser(uid, record);
        } catch (IdentityResolver.ErrUserNotFoundException e) {
            log.error("getUser ErrUserNotFound", e);
            throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST, ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
        } catch (IdentityResolver.ErrUserInactiveException e) {
            log.error("getUser ErrUserInactive", e);
            throw new BusinessException(ErrCodes.ERROR_USER_BANNED, ErrMsg.getMsg(ErrCodes.ERROR_USER_BANNED));
        } catch (IdentityResolver.ErrStatusUnavailableException e) {
            log.error("getUser ErrStatusUnavailable", e);
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY, ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        } catch (IdentityResolver.ErrIdentityMismatchException e) {
            log.error("getUser ErrIdentityMismatch", e);
            throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON, ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
        }

        // 成功：只复制 Uid/Username/Email/ExtraInfo 4 字段（与 B 端一致，score 不透传）
        UserInfo apiUser = support.fromProto(protoUser);

        return new GetUserResp(apiUser, true);
    }

    // ============== 4. updateUser（JWT） ==============

    /**
     * 对应 B 侧 {@code UserUpdateService#updateUser}。
     *
     * <p><b>L2 业务语义（4 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. claimedUIDAllowNonPositive(request) → uid
     *      → 失败 → ErrorTokenRuntime (1006)
     *      ⚠️ 这里**不**做 uid&lt;=0 判定（B 侧 updateuserlogic.go:36-46 没有这个判定）
     *
     *   2. 领域层 updateUser(UpdateUserReq{Uid, Username?, Password?, Email?, ExtraInfo?})
     *      → gRPC codes.Internal      → ErrorServerCommon (5001)
     *      → gRPC codes.AlreadyExists → ErrorUserExist    (1001)
     *      → gRPC codes.NotFound      → ErrorUserNotExist (1003)
     *      → default                  → CodeServerBusy    (1015)
     *
     *   3. 成功 → 返回 {user}（4 字段）
     * </pre>
     */
    public UpdateUserResp updateUser(com.rag.user.dto.user.UpdateUserReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            // 🔴 用 claimedUIDAllowNonPositive，**不**做 uid<=0 判定
            //   ⇒ uid=0 / 负数照常进领域层（最终由 NOT_FOUND 收敛成 1003）。
            uid = IdentityResolver.claimedUIDAllowNonPositive(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            log.error("updateUser ErrInvalidClaim", e);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        com.rag.proto.user.UpdateUserReq.Builder builder = com.rag.proto.user.UpdateUserReq.newBuilder().setUid(uid);
        if (req.username() != null) {
            builder.setUsername(req.username());
        }
        if (req.password() != null) {
            builder.setPassword(req.password());
        }
        if (req.email() != null) {
            builder.setEmail(req.email());
        }
        if (req.extraInfo() != null) {
            builder.putAllExtraInfo(req.extraInfo());
        }

        com.rag.proto.user.UpdateUserResp rpcResp;
        try {
            rpcResp = userDomainService.updateUser(builder.build());
        } catch (StatusRuntimeException e) {
            log.error("updateUser RPC error", e);
            throw e;
        }

        UserInfo apiUser = support.fromProto(rpcResp.getUser());

        return new UpdateUserResp(apiUser);
    }

    // ============== 5. deleteUser（JWT） ==============

    /**
     * 对应 B 侧 {@code UserDeleteService#deleteUser}。
     *
     * <p><b>L2 业务语义（4 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. claimedUIDAllowNonPositive(request) → uid（**不**做 uid&lt;=0 判定）
     *      → 失败 → ErrorTokenRuntime (1006)
     *
     *   2. 领域层 deleteUser(DeleteUserReq{Uid})
     *      → 失败 或 resp.Success == false：
     *         gRPC codes.NotFound  → ErrorUserNotExist (1003)
     *         gRPC codes.Internal  → ErrorServerCommon (5001)
     *         default              → CodeServerBusy    (1015)
     *
     *   3. 成功 → 返回 {success: true}
     * </pre>
     */
    public DeleteUserResp deleteUser(com.rag.user.dto.user.DeleteUserReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            // 🔴 用 claimedUIDAllowNonPositive，**不**做 uid<=0 判定
            uid = IdentityResolver.claimedUIDAllowNonPositive(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            log.error("deleteUser ErrInvalidClaim", e);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        com.rag.proto.user.DeleteUserResp rpcResp;
        try {
            rpcResp = userDomainService.deleteUser(com.rag.proto.user.DeleteUserReq.newBuilder().setUid(uid).build());
        } catch (StatusRuntimeException e) {
            log.error("deleteUser RPC error", e);
            throw e;
        }

        // B 侧同时检查 err != nil && rpcResp.Success == false
        if (!rpcResp.getSuccess()) {
            // resp.Success == false 但没有 gRPC error —— B 侧走 default 分支
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY, ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        return new DeleteUserResp(true);
    }

    // ============== 6. logout（JWT） ==============

    /**
     * 对应 B 侧 {@code UserLogoutService#logout}。
     *
     * <p><b>L2 业务语义（4 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. 从 request attribute "jwt_token" 取 token（拦截器在黑名单校验后写入）
     *      → 取不到 → ErrorTokenNotExist (1004)
     *
     *   2. 领域层 logout(LogoutReq{Token})
     *      → gRPC codes.Internal → ErrorServerCommon (5001)
     *      → 其它（默认）         → CodeServerBusy   (1015)
     *
     *   3. 成功 → 返回 {success: true}
     * </pre>
     *
     * <p>🔴 B 侧 {@code DRIFT-USERAPI-003 已修}：<b>刻意不</b>复用 advice 的通用 gRPC 映射
     * （那个通用映射会把 INVALID_ARGUMENT 映成 500，而 B 侧的 logout switch 里根本没有这一格）
     * ⇒ ⇒ 本类的 catch <b>显式写出</b> B 的两条分支。
     */
    public LogoutResp logout(com.rag.user.dto.auth.LogoutReq req, HttpServletRequest httpRequest) {
        // 对应 Go: l.ctx.Value("jwt_token")
        Object tokenAttr = httpRequest.getAttribute("jwt_token");
        if (tokenAttr == null) {
            log.error("Token不存在");
            throw new BusinessException(ErrCodes.ERROR_TOKEN_NOT_EXIST, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_NOT_EXIST));
        }
        String token = (String) tokenAttr;

        try {
            userDomainService.logout(com.rag.proto.user.LogoutReq.newBuilder().setToken(token).build());
        } catch (StatusRuntimeException e) {
            log.error("logout RPC error", e);
            // B 侧 logoutlogic.go:45-54：只区分 Internal（5001）与「其它一律 1015」。
            if (e.getStatus().getCode() == io.grpc.Status.Code.INTERNAL) {
                throw new BusinessException(
                        ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(
                    ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        return new LogoutResp(true);
    }

    // ============== 7. uploadAvatar（JWT） ==============

    /**
     * 对应 B 侧 {@code AvatarUploadService#uploadAvatar}。
     *
     * <p><b>L2 业务语义（5 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. claimedUID(request) → uid（**拒绝** uid&lt;=0）
     *      → 失败 → ErrorTokenRuntime (1006)
     *
     *   2. avatar_url 空字符串检查（TrimSpace == ""）→ Error (500)
     *
     *   3. avatar_url trim 后传给领域层（与 Go 一致）
     *
     *   4. 领域层 uploadAvatar(UploadAvatarReq{Uid, AvatarUrl, ContentType?, SizeBytes?})
     *      → codes.InvalidArgument → Error           (500)
     *      → codes.NotFound        → ErrorUserNotExist (1003)
     *      → codes.Internal        → ErrorServerCommon (5001)
     *      → default               → CodeServerBusy   (1015)
     *
     *   5. 成功 → 返回 {avatarUrl, history}
     * </pre>
     */
    public UploadAvatarResp uploadAvatar(com.rag.user.dto.avatar.UploadAvatarReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            uid = IdentityResolver.claimedUID(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            log.error("uploadAvatar ErrInvalidClaim", e);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        // Go: strings.TrimSpace(req.AvatarUrl) == "" 检查
        String avatarUrl = support.normalizeAvatarUrl(req.avatarUrl());
        if (avatarUrl.isEmpty()) {
            log.error("avatar_url is empty");
            throw new BusinessException(ErrCodes.ERROR, ErrMsg.getMsg(ErrCodes.ERROR));
        }

        com.rag.proto.user.UploadAvatarReq.Builder builder = com.rag.proto.user.UploadAvatarReq.newBuilder()
                .setUid(uid)
                .setAvatarUrl(avatarUrl);
        if (req.contentType() != null) {
            builder.setContentType(req.contentType());
        }
        if (req.sizeBytes() != null) {
            builder.setSizeBytes(req.sizeBytes());
        }

        com.rag.proto.user.UploadAvatarResp rpcResp;
        try {
            rpcResp = userDomainService.uploadAvatar(builder.build());
        } catch (StatusRuntimeException e) {
            log.error("uploadAvatar RPC error", e);
            throw e;
        }

        return new UploadAvatarResp(
                rpcResp.getAvatarUrl(),
                support.fromProto(rpcResp.getHistory()));
    }

    // ============== 8. selectAvatar（JWT） ==============

    /**
     * 对应 B 侧 {@code AvatarSelectService#selectAvatar}。
     *
     * <p><b>L2 业务语义（5 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. claimedUID(request) → uid（**拒绝** uid&lt;=0）
     *      → 失败 → ErrorTokenRuntime (1006)
     *
     *   2. history_id 空字符串检查（TrimSpace == ""）→ Error (500)
     *
     *   3. 领域层 selectAvatar(SelectAvatarReq{Uid, HistoryId})
     *      → codes.InvalidArgument → Error           (500)
     *      → codes.NotFound        → Error           (500)   ← 注意：这里是 Error 不是 ErrorUserNotExist
     *      → codes.Internal        → ErrorServerCommon (5001)
     *      → default               → CodeServerBusy   (1015)
     *
     *   4. 成功 → 返回 {avatarUrl, history}
     * </pre>
     *
     * <p>🔴 B 侧 {@code DRIFT-003 修复}：<b>不能</b>直接 rethrow（通用映射会把 NOT_FOUND 映成 1003
     * 「用户不存在」）⇒ B 侧刻意把「头像历史不存在」(500) 与「用户不存在」(1003) <b>区分开</b>
     * ⇒ ⇒ 本类的 catch <b>显式写出</b> B 的四条分支。
     */
    public SelectAvatarResp selectAvatar(com.rag.user.dto.avatar.SelectAvatarReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            uid = IdentityResolver.claimedUID(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            log.error("selectAvatar ErrInvalidClaim", e);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        // Go: strings.TrimSpace(req.HistoryId) == "" 检查
        String historyId = support.normalizeHistoryId(req.historyId());
        if (historyId.isEmpty()) {
            log.error("history_id is empty");
            throw new BusinessException(ErrCodes.ERROR, ErrMsg.getMsg(ErrCodes.ERROR));
        }

        com.rag.proto.user.SelectAvatarResp rpcResp;
        try {
            rpcResp = userDomainService.selectAvatar(
                    com.rag.proto.user.SelectAvatarReq.newBuilder().setUid(uid).setHistoryId(historyId).build());
        } catch (StatusRuntimeException e) {
            log.error("selectAvatar RPC error", e);
            // B 侧 selectavatarlogic.go:54-61 的 switch：
            //   case codes.InvalidArgument: return nil, errmsg.Error             // 500
            //   case codes.NotFound:        return nil, errmsg.Error             // 500
            //   case codes.Internal:        return nil, errmsg.ErrorServerCommon // 5001
            //   default:                    return nil, errmsg.CodeServerBusy    // 1015
            // NOT_FOUND 的差异不是「看起来更合理」的改写，而是**抹掉了 Go 刻意保留的区分**：
            // 「头像历史不存在」(500) 与「用户不存在」(1003) 被合并后，
            // 客户端可以据此枚举某个 uid 是否存在 ⇒ 必须显式保留。
            io.grpc.Status.Code code = e.getStatus().getCode();
            if (code == io.grpc.Status.Code.NOT_FOUND
                    || code == io.grpc.Status.Code.INVALID_ARGUMENT) {
                throw new BusinessException(ErrCodes.ERROR, ErrMsg.getMsg(ErrCodes.ERROR));
            }
            throw e;
        }

        return new SelectAvatarResp(
                rpcResp.getAvatarUrl(),
                support.fromProto(rpcResp.getHistory()));
    }

    // ============== 9. getAvatarHistory（JWT） ==============

    /**
     * 对应 B 侧 {@code AvatarHistoryService#getAvatarHistory}。
     *
     * <p><b>L2 业务语义（4 状态机，1:1 复刻）</b>：
     * <pre>
     *   1. claimedUID(request) → uid（**拒绝** uid&lt;=0）
     *      → 失败 → ErrorTokenRuntime (1006)
     *
     *   2. 领域层 getAvatarHistory(GetAvatarHistoryReq{Uid, Limit: 30})
     *
     *   3. 失败：
     *         codes.NotFound  → ErrorUserNotExist (1003)
     *         codes.Internal  → ErrorServerCommon (5001)
     *         default         → CodeServerBusy    (1015)
     *
     *   4. 成功 → 返回 {list}
     * </pre>
     *
     * <p>⚠️ <b>limit 硬编码 30</b>（与 Go 端一致，B 侧 DRIFT-016 决策：1:1 不配置化）。
     */
    public GetAvatarHistoryResp getAvatarHistory(HttpServletRequest httpRequest) {
        /** 与 Go 端硬编码一致：Limit = 30（B 侧 DRIFT-016 决策，1:1 不改）。 */
        final int limit = 30;

        long uid;
        try {
            uid = IdentityResolver.claimedUID(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            log.error("getAvatarHistory ErrInvalidClaim", e);
            throw new BusinessException(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
        }

        com.rag.proto.user.GetAvatarHistoryResp rpcResp;
        try {
            rpcResp = userDomainService.getAvatarHistory(
                    com.rag.proto.user.GetAvatarHistoryReq.newBuilder().setUid(uid).setLimit(limit).build());
        } catch (StatusRuntimeException e) {
            log.error("getAvatarHistory RPC error", e);
            throw e;
        }

        List<AvatarHistoryItem> list = new ArrayList<>(rpcResp.getListCount());
        for (com.rag.proto.user.AvatarHistoryItem item : rpcResp.getListList()) {
            list.add(support.fromProto(item));
        }

        return new GetAvatarHistoryResp(list);
    }
}
