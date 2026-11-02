package com.rag.admin.service;

import com.rag.admin.config.AdminApiProperties;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.UserEntity;
import com.rag.admin.domain.service.AdminDomainService;
import com.rag.admin.dto.admin.AdminInfo;
import com.rag.admin.dto.admin.CreateAdminReq;
import com.rag.admin.dto.admin.CreateAdminResp;
import com.rag.admin.dto.admin.GetSelfReq;
import com.rag.admin.dto.admin.GetSelfResp;
import com.rag.admin.dto.admin.UpdateSelfReq;
import com.rag.admin.dto.admin.UpdateSelfResp;
import com.rag.admin.dto.auth.LoginReq;
import com.rag.admin.dto.auth.LoginResp;
import com.rag.admin.dto.auth.LogoutReq;
import com.rag.admin.dto.auth.LogoutResp;
import com.rag.admin.dto.invite.CreateInviteCodeReq;
import com.rag.admin.dto.invite.CreateInviteCodeResp;
import com.rag.admin.dto.user.BanUserReq;
import com.rag.admin.dto.user.BanUserResp;
import com.rag.admin.dto.user.DeleteUserReq;
import com.rag.admin.dto.user.DeleteUserResp;
import com.rag.admin.dto.user.GetUserListReq;
import com.rag.admin.dto.user.GetUserListResp;
import com.rag.admin.dto.user.GetUserReq;
import com.rag.admin.dto.user.GetUserResp;
import com.rag.admin.dto.user.ResetUserPasswordReq;
import com.rag.admin.dto.user.ResetUserPasswordResp;
import com.rag.admin.dto.user.UnBanUserReq;
import com.rag.admin.dto.user.UnBanUserResp;
import com.rag.admin.dto.user.UpdateUserReq;
import com.rag.admin.dto.user.UpdateUserResp;
import com.rag.admin.dto.user.UserInfo;
import com.rag.admin.metrics.AdminApiMetrics;
import com.rag.common.exception.BusinessException;
import com.rag.common.security.JwtUtil;
import com.rag.users.common.ErrCodes;
import com.rag.users.common.ErrMsg;
import com.rag.users.common.IdentityResolver;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：admin 域 HTTP 层的 <b>13 个用例</b>（登录 / 注销 / 查自己 / 改自己 / 建 admin / 建邀请码 /
 * 封禁 / 解封 / 查用户 / 用户列表 / 改用户 / 删用户 / 重置密码）—— 全部对外暴露为 HTTP DTO，
 * 内部<b>直接</b>调用 {@link AdminDomainService}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>三处结构性改动</b> ——
 *       <br>① <b>13 个 {@code Admin*Service} 合 1 个</b>（D2 + 用例内聚）：B 侧 13 个类各 1 个用例，
 *       本类 13 个公开方法，<b>方法体逐条照抄</b>（每个方法对应 B 侧的一个 {@code Admin*Service}）。
 *       <br>② <b>域内直连</b>（D2）：B 侧 api 层经 {@code AdminRpcClient} 打 gRPC 到 rpc 层；
 *       本类<b>直接注入</b> {@link AdminDomainService}（同进程、同事务上下文、<b>不走自我 gRPC 往返</b>）。
 *       <br>③ <b>proto 响应 → HTTP DTO 的转换链</b>：B 侧是「entity → proto（rpc 层）→ DTO（api 层）」；
 *       本类改为「entity → DTO 直转」，转换规则逐条照抄（见下方<b>逐字段等价表</b>）。
 *       <br>④ 另有两处纯机械改动：包名 {@code api.service} → {@code service}（D7）；
 *       {@code ErrorCode} enum → {@code ErrCodes}(int) + {@code ErrMsg.getMsg(code)}；
 *       {@code com.rag.users.common.jwt.JwtUtil} → {@code com.rag.common.security.JwtUtil}（共享层既有，签名逐条相同）。</li>
 *   <li><b>语义还一样吗</b>：<b>一样</b>。逐条论证见下方三节：
 *       <br>① <b>转换链等价</b>（逐字段表）；
 *       <br>② <b>错误出口等价</b>（gRPC status + description 原样透传，映射只发生在 HTTP 边界）；
 *       <br>③ <b>指标 / 日志等价</b>（16 个 api 指标与 B 侧的触发点一一对应）。
 *       <br>⚠️ <b>唯一的表达层差异</b>：B 侧每个用例类各有自己的 logger ⇒ 日志的 logger 名从
 *       {@code AdminLoginService} 等 13 个变成 {@code AdminApiService}（日志<b>内容</b>与<b>触发点</b>逐条未动）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/service/}
 *       下的 13 个 {@code Admin*Service}.java + {@code AdminApiSupport}.java + {@code AdminController}.java
 *       （逐个方法的对应关系见下方映射表）。</li>
 * </ol>
 *
 * <h2>🔴 错误出口模型（本仓决策记录 D16（判定基准 = B 侧） · §6.7 —— <b>本类不 catch 映射</b>）</h2>
 * <ul>
 *   <li>业务层（{@link AdminDomainService}）<b>逐字照抄 B 侧</b>：抛 {@link StatusRuntimeException}
 *       （{@code Status.NOT_FOUND.withDescription("用户不存在")} 等）⇒ <b>status code + description 是 gRPC
 *       wire 上可见的 L1 面</b>，不得改写成 {@code BusinessException}。</li>
 *   <li><b>HTTP 边界</b>（{@code advice/AdminApiExceptionHandler}，<b>非本类</b>）逐字照抄 B 侧的
 *       {@code mapRPCStatusToCode} 做 gRPC status → 业务码映射（映射表 admin.md §6 的 7 行表）。</li>
 *   <li>⇒ <b>本类不 catch {@link StatusRuntimeException} 做映射</b>；catch 块只做两件事：
 *       <b>① 打 L1 指标</b>（{@code admin_api_intercept_total} 的 {@code rpc_error} / {@code db_error} label）
 *       <b>② 打 error 日志</b> —— 然后<b>原样 rethrow</b>（status + description 不变）。
 *       <br>⚠️ <b>为什么必须留 catch</b>：B 侧那 16 个 api 指标的 {@code rpc_error} / {@code db_error}
 *       label <b>是在 catch 块里打的</b>；若本类完全不 catch，指标名还在（映射表 admin.md §6 · L1）但那个 label
 *       <b>永远不会出现</b> ⇒ 观测面漂移。而 HTTP 层的 advice 只看到异常、<b>不知道是哪个端点</b>
 *       ⇒ 打不出带 {@code path} label 的指标 ⇒ ⇒ 指标只能在<b>用例层</b>打。</li>
 *   <li>⇒ <b>身份 sentinel 同理：本类只抛、不映射</b>。共享层 {@link IdentityResolver} 的 3 个 sentinel
 *       （{@code ErrInvalidClaimException} / {@code ErrUserNotFoundException} /
 *       {@code ErrIdentityMismatchException}）由 {@code advice/AdminApiExceptionHandler} 逐条映射成
 *       1006 / 1003 / 5001 —— 与 B 侧 {@code GlobalExceptionHandler} 的 3 个 sentinel handler
 *       <b>业务码逐条相同</b> ⇒ HTTP 响应逐字节等价（都是 200 + envelope）。</li>
 *   <li>🔴 <b>2026-11-02 已修：逐端点 switch 已补进本类</b>（此前是「通用 mapRPCStatusToCode 取代」，
 *       造成 <b>4 处 wire 漂移</b>）。B 侧各用例类里那套<b>逐端点</b>的 status → 业务码 switch
 *       （{@code updateSelf} 的 {@code INTERNAL → 5003}、{@code unBanUser} / {@code resetUserPassword}
 *       的 {@code INTERNAL → 5003}、{@code login} 的 {@code UNAUTHENTICATED → 1002}）
 *       ⇒ 现在<b>逐条照抄</b>到本类对应方法里（每个方法下面都有「⚠️ 逐端点 switch」注释块）。
 *       ⇒ {@code advice/AdminApiExceptionHandler#mapRPCStatusToCode} 随之<b>降为兜底表</b>
 *       （承接 B 侧 {@code getSelf} 那种「原样 rethrow、不自己 catch」的端点）。
 *       ⇒ 详见本域台账 {@code docs/DRIFT/users/admin/verification-admin-2026-11-02.md} §5。</li>
 * </ul>
 *
 * <h2>🔴「entity → DTO 直转」的逐字段等价表（对照 B 侧的 entity → proto → DTO 链）</h2>
 * <p>B 侧链：{@code AdminEntity/UserEntity} →（rpc 层 {@code toProtoAdmin/toProtoUser}）→
 * {@code proto AdminInfo/UserInfo} →（api 层 {@code AdminApiSupport.fromProto}）→ DTO。
 * <table border="1">
 *   <tr><th>DTO 字段</th><th>B 侧取值链</th><th>本类直转</th></tr>
 *   <tr><td>{@code AdminInfo.uid}</td>
 *       <td>proto uid = {@code entity.uid == null ? 0 : uid} ⇒ {@code String.valueOf(...)}</td>
 *       <td>{@code String.valueOf(entity.getUid() == null ? 0L : entity.getUid())}</td></tr>
 *   <tr><td>{@code AdminInfo.username}</td><td>proto username = {@code entity.username == null ? "" : ...}</td>
 *       <td>{@code entity.getUsername() == null ? "" : entity.getUsername()}</td></tr>
 *   <tr><td>{@code AdminInfo.email}</td><td>proto email = {@code AdminHelpers.stringValue(entity.email)}（null → ""）</td>
 *       <td>{@code AdminHelpers.stringValue(entity.getEmail())}</td></tr>
 *   <tr><td>{@code AdminInfo.extra_info}</td><td>proto 空 map ⇒ {@code fromProto} 判 {@code count == 0} ⇒ null</td>
 *       <td>{@code null-or-empty ? null : new HashMap<>(...)}</td></tr>
 *   <tr><td>{@code UserInfo.uid}</td><td>同 AdminInfo.uid</td><td>同 AdminInfo.uid</td></tr>
 *   <tr><td>{@code UserInfo.status}</td><td>proto status = {@code entity.status == null ? 0 : ...}</td>
 *       <td>{@code entity.getStatus() == null ? 0L : entity.getStatus()}</td></tr>
 *   <tr><td>{@code UserInfo.username}</td><td>同 AdminInfo.username</td><td>同 AdminInfo.username</td></tr>
 *   <tr><td>{@code UserInfo.email}</td>
 *       <td>proto email = {@code entity.email == null ? "" : ...} ⇒ {@code fromProto} 判 {@code isEmpty()} ⇒ <b>null</b>
 *           （B 侧 {@code types.UserInfo.email} 带 {@code omitempty} ⇒ 空值时字段省略）</td>
 *       <td>{@code email.isEmpty() ? null : email}</td></tr>
 *   <tr><td>{@code UserInfo.extra_info}</td><td>同 AdminInfo.extra_info</td><td>同 AdminInfo.extra_info</td></tr>
 * </table>
 * <p>⚠️ <b>两处 email 的差别（不要顺手统一）</b>：{@code AdminInfo.email} <b>无条件输出</b>（空值也输出 {@code ""}）；
 * {@code UserInfo.email} <b>空串时整个字段省略</b>。B 侧 {@code AdminApiSupport.fromProto} 逐条如此，本类同。
 * <p>⚠️ <b>{@code uid} 序列化成字符串</b>（本仓决策记录 D16 D-C）：B 侧 getter 返回 {@code String} ⇒ JSON 是 {@code "uid":"123"}
 * 而非数字 ⇒ C 侧 DTO 的 uid 组件类型必须是 {@code String}。
 *
 * <h2>🔴 13 个用例 ↔ B 侧 13 个 {@code Admin*Service} 的逐条映射</h2>
 * <pre>
 *   login             ← AdminLoginService            （B: adminRpcClient.adminRpc().login）
 *   createAdmin       ← AdminCreateService           （B: adminRpcClient.adminRpc().createAdmin）
 *   logout            ← AdminLogoutService           （B: adminRpcClient.adminRpc().logout）
 *   getSelf           ← AdminGetSelfService          （B: IdentityResolver.resolveAdmin(request, adminRpc)）
 *   updateSelf        ← AdminUpdateSelfService       （B: adminRpcClient.adminRpc().updateSelf）
 *   updateUser        ← AdminUpdateUserService       （B: adminRpcClient.adminRpc().updateUser）
 *   deleteUser        ← AdminDeleteUserService       （B: adminRpcClient.adminRpc().deleteUser）
 *   resetUserPassword ← AdminResetUserPasswordService（B: adminRpcClient.adminRpc().resetUserPassword）
 *   banUser           ← AdminBanUserService          （B: adminRpcClient.adminRpc().banUser）
 *   unBanUser         ← AdminUnBanUserService        （B: adminRpcClient.adminRpc().unBanUser）
 *   getUser           ← AdminGetUserService          （B: adminRpcClient.adminRpc().getUser）
 *   getUserList       ← AdminListUserService         （B: adminRpcClient.adminRpc().getUserList）
 *   createInviteCode  ← AdminCreateInviteCodeService （B: adminRpcClient.adminRpc().createInviteCode）
 * </pre>
 *
 * <h2>⚠️ 构造器依赖（映射表 admin.md §0.5 的依赖行<b>不完整</b>，此处补齐并说明）</h2>
 * <ul>
 *   <li>{@link AdminDomainService} + {@link AdminApiSupport} —— 映射表 admin.md §0.5 指定；
 *       {@code AdminApiSupport.parseUid} 是 uid 字符串解析的唯一实现（6 个用例共用）。</li>
 *   <li>{@link AdminApiProperties} —— <b>补</b>：B 侧 {@code AdminLoginService} 用它取
 *       {@code admin-auth.access-secret} / {@code access-expire} 来签发 JWT；
 *       不注入 ⇒ {@code /login} <b>签不出 token</b>（端点功能缺失）。</li>
 *   <li>{@link AdminApiMetrics} —— <b>补</b>：B 侧 13 个用例类里有 7 个注入它，
 *       打 16 个 {@code admin_api_intercept_total} 指标（映射表 admin.md §6 · L1 指标名）；
 *       不注入 ⇒ 该指标<b>永远不出现</b>。</li>
 * </ul>
 *
 * <h2>🔴 跨域依赖（本类调用、但<b>不在本类</b>落地的两处契约）</h2>
 * <ul>
 *   <li><b>{@code jwt_token} request attribute</b>：{@code logout} 从 {@code httpRequest.getAttribute("jwt_token")}
 *       取 token（B 出处：{@code AdminLogoutService.java:39}）⇒ <b>拦截器必须写这个 attribute</b>
 *       （B 侧 {@code AdminJwtAuthInterceptor.java:182} 写 {@code userId} 与 {@code jwt_token} 两个）。
 *       取不到 ⇒ 1004 {@code ERROR_TOKEN_NOT_EXIST}（与 B 逐字一致）。</li>
 *   <li><b>{@code userId} request attribute</b>：11 个 JWT 用例的 operatorId / uid 来自
 *       {@link IdentityResolver#claimedUID(HttpServletRequest)}（共享层 {@code com.rag.users.common.IdentityResolver}，
 *       D16 下 B 侧 admin 侧那份副本已上收）。取不到 / 非正整数 ⇒ {@code ErrInvalidClaimException}
 *       ⇒ 本类映射成 1006（与 B 侧各用例类逐条一致）。</li>
 * </ul>
 */
@Service
public class AdminApiService {

    private static final Logger log = LoggerFactory.getLogger(AdminApiService.class);

    private final AdminDomainService adminDomainService;
    private final AdminApiSupport support;
    private final AdminApiProperties properties;
    private final AdminApiMetrics metrics;

    public AdminApiService(AdminDomainService adminDomainService,
                           AdminApiSupport support,
                           AdminApiProperties properties,
                           AdminApiMetrics metrics) {
        this.adminDomainService = adminDomainService;
        this.support = support;
        this.properties = properties;
        this.metrics = metrics;
    }

    // ====================== 1. login（B: AdminLoginService） ======================

    /**
     * admin login 用例 —— 4 状态机 + JWT 签发。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminLoginService.java:50-102}。
     *
     * <p>L2 业务语义（1:1 复刻）：
     * <ol>
     *   <li>调领域层 login（用户名 / 密码）→ gRPC NOT_FOUND（用户名不存在）/ UNAUTHENTICATED（密码错）
     *       / INTERNAL（DB 查询失败）⇒ <b>由 HTTP 边界统一映射</b>（本仓决策记录 D16（判定基准 = B 侧） / §6.7）；</li>
     *   <li>签发 JWT —— 与 B 侧 {@code jwt.GetToken(secret, now, expire, uid)} 1:1；</li>
     *   <li>JWT 签发失败 ⇒ <b>5001</b>（不是 1006「TOKEN已过期」—— 那是 token <b>校验</b>失败，语义完全不同）。</li>
     * </ol>
     */
    public LoginResp login(LoginReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            uid = adminDomainService.login(
                    req.username() == null ? "" : req.username(),
                    req.password() == null ? "" : req.password());
        } catch (StatusRuntimeException e) {
            metrics.getLoginRpcError().increment();
            log.error("admin login RPC error, username={}", req.username(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            if (code == Status.Code.UNAUTHENTICATED) {
                throw new BusinessException(ErrCodes.ERROR_LOGIN_WRONG,
                        ErrMsg.getMsg(ErrCodes.ERROR_LOGIN_WRONG));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        // 签发 JWT —— 与 B 侧 jwt.GetToken(secret, now, expire, uid) 1:1
        long nowSec = System.currentTimeMillis() / 1000L;
        long expireSec = properties.getAdminAuth().getAccessExpire();
        String accessSecret = properties.getAdminAuth().getAccessSecret();
        String token;
        try {
            token = JwtUtil.getToken(accessSecret, nowSec, expireSec, uid);
        } catch (Throwable t) {
            // 与 B 侧 AdminLoginService.java:88-94 1:1：JWT 签发失败属于服务端内部错误 → 5001，
            // 不是 1006「TOKEN已过期」（那是 token 校验失败，语义完全不同）。
            metrics.getLoginInternalError().increment();
            log.error("admin login generate token failed : {}", t.getMessage(), t);
            throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                    ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
        }

        metrics.getLoginSuccess().increment();
        log.info("admin login success, uid={} username={}", uid, req.username());

        return new LoginResp(token);
    }

    // ====================== 2. createAdmin（B: AdminCreateService） ======================

    /**
     * admin createAdmin 用例 —— 3 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminCreateService.java:43-74}。
     *
     * <p>L2 业务语义：领域层 createAdmin → gRPC PERMISSION_DENIED（邀请码错）/ ALREADY_EXISTS（用户名已存在）
     * / INTERNAL ⇒ 由 HTTP 边界统一映射；成功 ⇒ {@code CreateAdminResp{Uid}}。
     */
    public CreateAdminResp createAdmin(CreateAdminReq req, HttpServletRequest httpRequest) {
        long uid;
        try {
            uid = adminDomainService.createAdmin(
                    req.username() == null ? "" : req.username(),
                    req.password() == null ? "" : req.password(),
                    req.email() == null ? "" : req.email(),
                    // B 侧链路：api 层 putAllExtraInfo(null→{}) ⇒ proto 空 map ⇒ rpc 层判 isEmpty ⇒ 传 null
                    extraInfoOrNull(req.extraInfo()),
                    req.inviteCode() == null ? "" : req.inviteCode());
        } catch (StatusRuntimeException e) {
            log.error("admin createAdmin RPC error, username={}", req.username(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.PERMISSION_DENIED) {
                throw new BusinessException(ErrCodes.ERROR_ADMIN_INVITE_CODE_WRONG,
                        ErrMsg.getMsg(ErrCodes.ERROR_ADMIN_INVITE_CODE_WRONG));
            }
            if (code == Status.Code.ALREADY_EXISTS) {
                throw new BusinessException(ErrCodes.ERROR_USER_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_EXIST));
            }
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        // B 侧：CreateAdminResp.setUid(uid)（Long）⇒ getter 返回 uid.toString() ⇒ JSON 是字符串
        return new CreateAdminResp(String.valueOf(uid));
    }

    // ====================== 3. logout（B: AdminLogoutService） ======================

    /**
     * admin logout 用例 —— 4 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminLogoutService.java:38-62}。
     *
     * <p>L2 业务语义：
     * <ol>
     *   <li>从 request attribute {@code "jwt_token"} 取 token → 取不到 ⇒ 1004 {@code ERROR_TOKEN_NOT_EXIST}；</li>
     *   <li>调领域层 logout → gRPC 错 ⇒ 由 HTTP 边界统一映射；</li>
     *   <li>成功 ⇒ {@code LogoutResp{Success=true}}（B 侧 api 层<b>显式置 true</b>，不透传 rpc 的
     *       {@code success} —— rpc 层那个字段恒为 proto3 默认 false）。</li>
     * </ol>
     */
    public LogoutResp logout(LogoutReq req, HttpServletRequest httpRequest) {
        Object tokenAttr = httpRequest.getAttribute("jwt_token");
        if (tokenAttr == null) {
            log.error("Token不存在");
            throw new BusinessException(ErrCodes.ERROR_TOKEN_NOT_EXIST,
                    ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_NOT_EXIST));
        }
        String token = (String) tokenAttr;

        try {
            adminDomainService.logout(token);
        } catch (StatusRuntimeException e) {
            log.error("admin logout RPC error", e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        return new LogoutResp(true);
    }

    // ====================== 4. getSelf（B: AdminGetSelfService） ======================

    /**
     * admin getSelf 用例 —— 2 状态机（GetSelf admin proto 无 status 字段，所以没有 inactive 状态）。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminGetSelfService.java:39-60}。
     *
     * <p>L2 业务语义：
     * <ol>
     *   <li>claim uid → 非法 ⇒ 1006 {@code ERROR_TOKEN_RUNTIME}；</li>
     *   <li>领域层 getSelf → NOT_FOUND（admin 不存在）⇒ 由 HTTP 边界映射为 1003
     *       （B 侧是 {@code ErrAdminNotFoundException → 1003}，<b>同一个业务码</b>）；</li>
     *   <li>取回的 admin 与 claim 的 uid 不一致 ⇒ 5001 {@code ERROR_SERVER_COMMON}
     *       （B 侧 {@code ErrIdentityMismatchException → 5001}）；</li>
     *   <li>成功 ⇒ {@code GetSelfResp{Admin=AdminInfo}}。</li>
     * </ol>
     */
    public GetSelfResp getSelf(GetSelfReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006（与 B 侧同一个业务码）
        long uid = IdentityResolver.claimedUID(httpRequest);

        // 领域层 NOT_FOUND ⇒ 由 advice 映射成 1003（B 侧是 ErrAdminNotFoundException → 1003，同一个业务码）
        AdminEntity admin = adminDomainService.getSelf(uid);

        // B 侧 IdentityResolver.resolveAdmin 的身份错配判定（1:1 保留）。
        // B 出处：admin-api/.../service/IdentityResolver.java:135-137（admin.getUid() != uid ⇒ ErrIdentityMismatch）。
        if (admin.getUid() == null || admin.getUid() != uid) {
            throw new IdentityResolver.ErrIdentityMismatchException(
                    "authenticated admin identity mismatch, claimed_uid=" + uid);
        }

        return new GetSelfResp(support.fromEntity(admin));
    }

    // ====================== 5. updateSelf（B: AdminUpdateSelfService） ======================

    /**
     * admin updateSelf 用例 —— 5 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminUpdateSelfService.java:46-95}。
     *
     * <p>L2 业务语义：
     * <ol>
     *   <li>claim uid → 非法 ⇒ 1006；</li>
     *   <li>调领域层 updateSelf（uid + username? + password? + email? + extraInfo?）
     *       → gRPC 错 ⇒ 由 HTTP 边界统一映射；</li>
     *   <li>成功 ⇒ {@code UpdateSelfResp{Success=true, Admin=AdminInfo}} —— <b>必须显式 true</b>，
     *       不透传 rpc 的 {@code success}（proto3 默认 false 会把成功报成失败）。</li>
     * </ol>
     */
    public UpdateSelfResp updateSelf(UpdateSelfReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006
        long uid = IdentityResolver.claimedUID(httpRequest);

        AdminEntity admin;
        try {
            admin = adminDomainService.updateSelf(
                    uid,
                    req.username(),
                    req.password(),
                    req.email(),
                    extraInfoOrNull(req.extraInfo()));
        } catch (StatusRuntimeException e) {
            log.error("updateSelf RPC error", e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.ALREADY_EXISTS) {
                throw new BusinessException(ErrCodes.ERROR_USER_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_EXIST));
            }
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_DB_SELECT,
                        ErrMsg.getMsg(ErrCodes.ERROR_DB_SELECT));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        return new UpdateSelfResp(true, support.fromEntity(admin));
    }

    // ====================== 6. updateUser（B: AdminUpdateUserService） ======================

    /**
     * admin updateUser 用例 —— 5 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminUpdateUserService.java:50-105}。
     *
     * <p>L2 业务语义：
     * <ol>
     *   <li>claim uid → 非法 ⇒ 1006；</li>
     *   <li>领域层 updateUser（uid + operatorId + username? + password? + email? + extraInfo?）
     *       → gRPC 错 ⇒ 由 HTTP 边界统一映射；</li>
     *   <li>成功 ⇒ {@code updateSuccess} + {@code UpdateUserResp{Success=true, User=UserInfo}}。</li>
     * </ol>
     */
    public UpdateUserResp updateUser(UpdateUserReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006
        long operatorId = IdentityResolver.claimedUID(httpRequest);

        UserEntity user;
        try {
            user = adminDomainService.updateUser(
                    parseUid(req.uid()),
                    operatorId,
                    req.username(),
                    req.password(),
                    req.email(),
                    extraInfoOrNull(req.extraInfo()));
        } catch (StatusRuntimeException e) {
            metrics.getUpdateDbError().increment();
            log.error("updateUser RPC error, target_uid={}", req.uid(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            if (code == Status.Code.ALREADY_EXISTS) {
                throw new BusinessException(ErrCodes.ERROR_USER_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_EXIST));
            }
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        metrics.getUpdateSuccess().increment();
        log.info("updateUser success, operator_id={} target_uid={}", operatorId, req.uid());

        // B 侧：api 层硬编码 true（B 侧 api 层同样硬编码 true），不透传 rpc 的 success
        // （rpc 层那个字段按 B 侧 恒为 false）。
        return new UpdateUserResp(true, support.fromEntity(user));
    }

    // ====================== 7. deleteUser（B: AdminDeleteUserService） ======================

    /**
     * admin deleteUser 用例 —— 4 状态机（NOT_FOUND / INTERNAL / Success + claim uid）。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminDeleteUserService.java:34-72}。
     */
    public DeleteUserResp deleteUser(DeleteUserReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006
        long operatorId = IdentityResolver.claimedUID(httpRequest);

        try {
            adminDomainService.deleteUser(parseUid(req.uid()), operatorId);
        } catch (StatusRuntimeException e) {
            metrics.getDeleteRpcError().increment();
            log.error("deleteUser RPC error, target_uid={}", req.uid(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        metrics.getDeleteSuccess().increment();
        log.info("deleteUser success, operator_id={} target_uid={}", operatorId, req.uid());

        return new DeleteUserResp(true);
    }

    // ====================== 8. resetUserPassword（B: AdminResetUserPasswordService） ======================

    /**
     * admin resetUserPassword 用例 —— 5 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminResetUserPasswordService.java:34-75}。
     */
    public ResetUserPasswordResp resetUserPassword(ResetUserPasswordReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006
        long operatorId = IdentityResolver.claimedUID(httpRequest);

        try {
            adminDomainService.resetUserPassword(parseUid(req.uid()), operatorId);
        } catch (StatusRuntimeException e) {
            metrics.getResetRpcError().increment();
            log.error("resetUserPassword RPC error, target_uid={}", req.uid(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_DB_SELECT,
                        ErrMsg.getMsg(ErrCodes.ERROR_DB_SELECT));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        metrics.getResetSuccess().increment();
        log.info("resetUserPassword success, operator_id={} target_uid={}", operatorId, req.uid());

        return new ResetUserPasswordResp(true);
    }

    // ====================== 9. banUser（B: AdminBanUserService） ======================

    /**
     * admin banUser 用例 —— 3 状态机（NOT_FOUND / INTERNAL / Success）。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminBanUserService.java:34-72}。
     */
    public BanUserResp banUser(BanUserReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006
        long operatorId = IdentityResolver.claimedUID(httpRequest);

        try {
            adminDomainService.banUser(parseUid(req.uid()), operatorId);
        } catch (StatusRuntimeException e) {
            metrics.getBanRpcError().increment();
            log.error("banUser RPC error, target_uid={}", req.uid(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        metrics.getBanSuccess().increment();
        log.info("banUser success, operator_id={} target_uid={}", operatorId, req.uid());

        return new BanUserResp(true);
    }

    // ====================== 10. unBanUser（B: AdminUnBanUserService） ======================

    /**
     * admin unBanUser 用例 —— 3 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminUnBanUserService.java:34-75}。
     */
    public UnBanUserResp unBanUser(UnBanUserReq req, HttpServletRequest httpRequest) {
        // claim 非法 ⇒ ErrInvalidClaimException ⇒ 由 advice 映射成 1006
        long operatorId = IdentityResolver.claimedUID(httpRequest);

        try {
            adminDomainService.unBanUser(parseUid(req.uid()), operatorId);
        } catch (StatusRuntimeException e) {
            metrics.getUnbanRpcError().increment();
            log.error("unBanUser RPC error, target_uid={}", req.uid(), e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_DB_SELECT,
                        ErrMsg.getMsg(ErrCodes.ERROR_DB_SELECT));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        metrics.getUnbanSuccess().increment();
        log.info("unBanUser success, operator_id={} target_uid={}", operatorId, req.uid());

        return new UnBanUserResp(true);
    }

    // ====================== 11. getUser（B: AdminGetUserService） ======================

    /**
     * admin getUser 用例 —— 3 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminGetUserService.java:34-72}。
     *
     * <p>⚠️ <b>{@code found} 显式置 true</b>：B 侧的 <b>api 层</b>自己构造
     * {@code types.GetUserResp{User: ..., Found: true}}（B 侧的 rpc 层不设 found，proto3 默认 false）
     * ⇒ 本类同（<b>不</b>透传 rpc 的 found）。
     */
    public GetUserResp getUser(GetUserReq req, HttpServletRequest httpRequest) {
        long uid = parseUid(req.uid());

        UserEntity user;
        try {
            user = adminDomainService.getUser(uid);
        } catch (StatusRuntimeException e) {
            log.error("getUser RPC error, uid={}", uid, e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        return new GetUserResp(support.fromEntity(user), true);
    }

    // ====================== 12. getUserList（B: AdminListUserService） ======================

    /**
     * admin getUserList 用例 —— 2 状态机。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminListUserService.java:39-81}。
     *
     * <p>L2 业务语义：keyword 非空时按 username/email LIKE 查询，否则全量。
     *
     * <p>⚠️ <b>分页默认值</b>：B 侧的默认值来自 struct tag {@code form:"page,default=1"} /
     * {@code form:"page_size,default=20"} —— default <b>只在参数缺失时</b>生效 ⇒
     * 显式传 {@code page=0} 必须<b>原样透传</b>（让下游按 B 侧的语义算出负 OFFSET 报错），
     * <b>不能</b>在本层被悄悄兜底成第 1 页 ⇒ 判据是「null（未传）」而不是「0」。
     *
     * <p>⚠️ <b>keyword 原样透传，不 trim</b>（B 侧 {@code len(keyword) > 0}，不去空白）。
     *
     * <p>⚠️ <b>空结果时 list 置 null</b>（不是 {@code []}）：B 侧的 api 层用 {@code var list []types.UserInfo}，
     * 循环不执行时保持 nil ⇒ 序列化成 {@code "list": null}。
     */
    public GetUserListResp getUserList(GetUserListReq req, HttpServletRequest httpRequest) {
        // B 侧 tag: form:"page,default=1" / form:"page_size,default=20"
        // ⚠️ 默认值口径由 DTO 的 pageOrDefault / pageSizeOrDefault 独家持有（B 侧同款方法，逐字照抄）
        // ⇒ 本层不再另写一份常量（避免「同一规则的第二份实现」）。
        long page = req.pageOrDefault();
        long pageSize = req.pageSizeOrDefault();
        String keyword = req.keyword() == null ? "" : req.keyword();

        AdminDomainService.UserListResult result;
        try {
            result = adminDomainService.getUserList(page, pageSize, keyword);
        } catch (StatusRuntimeException e) {
            log.error("getUserList RPC error", e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        List<UserInfo> list = null;
        if (result.users() != null && !result.users().isEmpty()) {
            list = new ArrayList<>();
            for (UserEntity u : result.users()) {
                list.add(support.fromEntity(u));
            }
        }
        return new GetUserListResp(list, result.total());
    }

    // ====================== 13. createInviteCode（B: AdminCreateInviteCodeService） ======================

    /**
     * admin createInviteCode 用例 —— 3 状态机 + 3 metrics label（含 token_invalid）。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminCreateInviteCodeService.java:41-78}。
     */
    public CreateInviteCodeResp createInviteCode(CreateInviteCodeReq req, HttpServletRequest httpRequest) {
        long operatorId;
        try {
            operatorId = IdentityResolver.claimedUID(httpRequest);
        } catch (IdentityResolver.ErrInvalidClaimException e) {
            // ⚠️ 本端点必须 catch：{code token_invalid} 是 invite/create 独有的 label（B 侧 DRIFT-NEW-007）
            // ⇒ 指标只能在用例层打 ⇒ catch 后【原样 rethrow】（映射交给 advice ⇒ 1006）。
            metrics.getInviteTokenInvalid().increment();
            log.error("createInviteCode ErrInvalidClaim", e);
            throw e;
        }

        AdminDomainService.InviteCodeResult result;
        try {
            result = adminDomainService.createInviteCode(operatorId);
        } catch (StatusRuntimeException e) {
            metrics.getInviteRpcError().increment();
            log.error("createInviteCode RPC error, operator_id={}", operatorId, e);
            // ⚠️ **逐端点 switch（B 出处：对应 B 侧 api service 的 catch 块逐条照抄）**
            //    —— 不能用 advice 的通用 mapRPCStatusToCode：本端点的 INTERNAL 码与通用表**不同**。
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INTERNAL) {
                throw new BusinessException(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON));
            }
            if (code == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrCodes.ERROR_USER_NOT_EXIST,
                        ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST));
            }
            throw new BusinessException(ErrCodes.CODE_SERVER_BUSY,
                    ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
        }

        metrics.getInviteSuccess().increment();
        log.info("createInviteCode success, operator_id={}", operatorId);

        return new CreateInviteCodeResp(result.code(), result.expiresAt());
    }

    // ====================== 内部 helper ======================

    /**
     * uid 字段从 String 解析（JSON 序列化为字符串避免精度丢失）。
     *
     * <p>B 出处：{@code admin-api/.../service/AdminApiSupport.java:83-96}（6 个用例共用同一份实现）。
     *
     * <p>⚠️ 解析失败 / 为空 ⇒ 抛 1017 {@code ERROR_REQUEST_PARAM}（B 侧同款；
     * 但在 B 侧它<b>从 HTTP 入口不可达</b> —— 非数字 uid 在 Jackson 反序列化阶段就被拒（400））。
     */
    private long parseUid(String uidStr) {
        return support.parseUid(uidStr);
    }

    /**
     * extraInfo 的「空 ⇒ null」归一化。
     *
     * <p>B 侧的等价链：api 层把 {@code null} 变成<b>空 map</b> 装进 proto ⇒ rpc 层再判
     * {@code isEmpty() ? null : new HashMap<>(...)} ⇒ 领域层收到的永远是「null 或非空 map」。
     * ⇒ 本类直连领域层 ⇒ 必须在<b>同一层</b>做这个归一化，否则「空 map」会一路传下去
     * （领域层虽按 {@code extraInfo != null && !extraInfo.isEmpty()} 判定、行为相同，
     * 但归一化点应与 B 侧一致，避免留下第二种形态）。
     */
    private static Map<String, String> extraInfoOrNull(Map<String, String> extraInfo) {
        return (extraInfo == null || extraInfo.isEmpty()) ? null : new HashMap<>(extraInfo);
    }


}
