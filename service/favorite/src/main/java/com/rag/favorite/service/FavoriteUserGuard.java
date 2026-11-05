package com.rag.favorite.service;

import com.rag.common.logger.LogOption;
import com.rag.favorite.client.UserRpcClient;
import com.rag.favorite.config.FavoriteRpcLabels;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.favorite.metrics.FavoriteRpcMetrics;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.UserInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <p><b>跨域身份校验</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteUserGuard.java}
 * （逐条照抄）。
 *
 * <p>对应 Go {@code service/favorite/rpc/internal/logic/helper.go:92-124}
 * 的 {@code ensureUserExists}。
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.service.FavoriteUserGuard}
 * ⇒ C 侧 {@code com.rag.favorite.service.FavoriteUserGuard}。
 *
 * <p><b>被 2 个 logic 调用</b>：{@code CreateFavoriteFolderLogic}（:68-73）与
 * {@code CreateFavoriteLogic}（:72-73）。其余 5 个用例<b>不做</b>用户校验 ——
 * 它们依赖「收藏夹归属」间接约束了用户身份（folder.UserId != userId ⇒ PermissionDenied）。
 *
 * <p><b>为什么独立成 Bean</b>：这五条分支各自决定返回哪个 gRPC code 与业务码，
 * 是一条<b>跨域</b>（favorite → user）且<b>可独立测试</b>的规则；
 * 与本域 7 个用例的业务逻辑无关。
 *
 * <h2>五条分支逐条对照（B 侧出处行号）</h2>
 * <table border="1">
 *   <caption>ensureUserExists 的五条出口</caption>
 *   <tr><th>条件</th><th>行号</th><th>输出</th></tr>
 *   <tr><td>{@code GetUser} 报错且 {@code st.Code()==NotFound}</td><td>:103-106</td>
 *       <td>{@code NotFound} + 5101</td></tr>
 *   <tr><td>{@code GetUser} 报错（其他 code）</td><td>:107</td>
 *       <td>{@code Internal} + 5001</td></tr>
 *   <tr><td>{@code resp == nil || !resp.Found || resp.User == nil}</td><td>:109-112</td>
 *       <td>{@code NotFound} + 5101</td></tr>
 *   <tr><td>🔴 {@code resp.User.Uid != userID || resp.User.Status == nil}</td><td>:113-116</td>
 *       <td>{@code <b>Unavailable</b>} + 5001</td></tr>
 *   <tr><td>🔴 {@code resp.User.GetStatus() != 0}</td><td>:117-120</td>
 *       <td>{@code PermissionDenied} + 5005</td></tr>
 * </table>
 *
 * <p>🔴 <b>第 4 条刻意用 {@code Unavailable} 而不是 {@code Internal}</b>：
 * 它表达的是「下游返回了我无法解释的响应」⇒ <b>可重试</b>；
 * 客户端按 gRPC code 决定重试策略，改成 Internal 会让重试策略整体改变。
 */
@Component
public class FavoriteUserGuard {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteUserGuard.class);

    private final UserRpcClient userRpcClient;
    private final FavoriteRpcMetrics metrics;

    public FavoriteUserGuard(UserRpcClient userRpcClient, FavoriteRpcMetrics metrics) {
        this.userRpcClient = userRpcClient;
        this.metrics = metrics;
    }

    /**
     * 对应 Go {@code ensureUserExists(ctx, svcCtx, userID)}（helper.go:92-124）。
     *
     * <p>正常返回 = 用户存在且 {@code status == 0}（active）；
     * 任何其它情况都抛 {@link FavoriteExceptions.Rpc}，<b>绝不返回 null</b>
     * —— B 侧是「返回 error」，Java 侧用异常表达同一语义。
     *
     * @param userId 待校验的用户 ID（调用方已保证 {@code > 0}）
     * @param logOptions 传给业务日志的选项（B 侧各 logic 的 {@code userLogOption(...)}）
     */
    public void ensureExists(long userId, LogOption... logOptions) {
        // 🔴 B 侧出处（rpc/internal/logic/helper.go:92-124）：
        //     depCtx, span := otel.Tracer("favorite-rpc").Start(ctx, "Dependency.User.GetUser")
        //     defer span.End()
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID))
        //     resp, err := svcCtx.UserRpc.GetUser(depCtx, &userservice.GetUserReq{Uid: userID})
        //     if err != nil {
        //         span.RecordError(err)          ← **只有这一处**记错（helper.go:100）
        //         ...
        //     }
        //     if resp == nil || !resp.Found || resp.User == nil { ... }   ← :109-111 **不记**
        //     if resp.User.Uid != userID || resp.User.Status == nil { ... } ← :113-115 **不记**
        //     if resp.User.GetStatus() != 0 { ... }                        ← :117-119 **不记**
        //
        // ⇒ 用 FavoriteRpcMetrics.tracedDependency 的两段式表达同一个时序：
        //    「下游调用失败 ⇒ 记错」/「调用后的业务判定失败 ⇒ 不记」。
        FavoriteRpcMetrics.tracedDependency(
                FavoriteRpcMetrics.DEPENDENCY_USER_GET_USER,
                span -> span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId),
                // B 侧: helper.go:98 —— svcCtx.UserRpc.GetUser(depCtx, &userservice.GetUserReq{Uid: userID})
                () -> userRpcClient.userRpc().getUser(GetUserReq.newBuilder().setUid(userId).build()),
                // B 侧: helper.go:100-107 —— **这里**是唯一记错的地方（记的是原始 gRPC 错误）
                e -> onDownstreamFailure(userId, e),
                // B 侧: helper.go:109-122 —— 三条业务性失败分支 + 成功分支，**都不记错**
                resp -> {
                    verifyIdentity(userId, resp, logOptions);
                    return null;
                });
    }

    /**
     * B 侧 {@code helper.go:100-107} 的错误分支 —— <b>唯一</b>会 {@code span.RecordError(err)} 的地方。
     *
     * <p>⚠️ <b>顺序必须与 B 侧一致</b>：B 侧是 {@code span.RecordError(err)} → {@code metrics.ObserveOp(...resultFail)}
     * → 再按 status 分支 ⇒ 本方法里 metrics 的观察发生在「翻译成业务码」之前。
     * <br>（{@code FavoriteRpcMetrics.tracedDependency} 已经在那之前完成了 {@code recordException}。）
     */
    private RuntimeException onDownstreamFailure(long userId, Throwable e) {
        if (!(e instanceof StatusRuntimeException sre)) {
            // ⚠️ 原实现只 catch StatusRuntimeException ⇒ 其它异常原样上抛（不被翻译成 5001）。
            //    这里逐字保留该行为，**不**在本次落码里顺手「改成 B 侧的 status.FromError 语义」。
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e);
        }
        metrics.observeOp(FavoriteRpcLabels.MODULE_DEPENDENCY,
                FavoriteRpcLabels.ACTION_USER_GET, FavoriteRpcLabels.RESULT_FAIL);
        if (sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
            return FavoriteExceptions.userNotFound();
        }
        LOG.error("user-rpc GetUser failed: user_id={} code={} message={}",
                userId, sre.getStatus().getCode(), sre.getStatus().getDescription());
        // 🔴 **保留 cause**（B 侧 helper.go:100-107 把原始 gRPC error 交回上层）
        //    ⇒ ⇒ api 面才能判出「这是 DEADLINE_EXCEEDED」⇒ ⇒ 落 1015（对齐 B 侧）
        //    ⇒ ⇒ 不保留 ⇒ status 被领域层吃掉 ⇒ api 面无从区分超时与其它失败。
        return FavoriteExceptions.internalServerCommon(e);
    }

    /**
     * B 侧 {@code helper.go:109-122} 的业务判定 —— <b>三条失败分支都不记错</b>。
     *
     * <p>⚠️ 这三条分支在 B 侧<b>没有</b> {@code span.RecordError} ⇒ 本方法抛出的异常
     * <b>不会</b>出现在 span 的事件列表里（只有 status 保持 UNSET）。
     */
    private void verifyIdentity(long userId, GetUserResp resp, LogOption... logOptions) {
        // B 侧: service/favorite/rpc/internal/logic/helper.go:109-112 —— resp == nil || !resp.Found || resp.User == nil
        if (resp == null || !resp.getFound() || !resp.hasUser()) {
            metrics.observeOp(FavoriteRpcLabels.MODULE_DEPENDENCY,
                    FavoriteRpcLabels.ACTION_USER_GET, FavoriteRpcLabels.RESULT_FAIL);
            throw FavoriteExceptions.userNotFound();
        }

        UserInfo user = resp.getUser();

        // 🔴 B 侧: service/favorite/rpc/internal/logic/helper.go:113-116
        //   if resp.User.Uid != userID || resp.User.Status == nil {
        //       return favoritecommon.GRPCError(codes.Unavailable, favoritecommon.ErrorServerCommon)
        //   }
        // 两点都要逐字保留：
        //   ① uid 不匹配 = 下游串了请求/数据，不可解释；
        //   ② Status == nil 是 **presence** 检查 —— users.proto:12 声明的是
        //      `optional int64 status = 6`，proto3 的 optional 允许「字段缺失」与「值为 0」区分开。
        //      ⚠️ Java 侧必须用 hasStatus()，不能用 getStatus() != 0 ——
        //      后者会把「下游没给 status」当成「status = 0（active）」⇒ **直接放行**，
        //      而 B 侧给的是 Unavailable/5001（helper.go:113-116）⇒ **方向相反的漂移**。
        //      🔴 **实测锚点**（2026-10-25 第十六轮变异验证，不是推断）：
        //        把上面那行改成 `user.getStatus() != 0L` ⇒ 双侧活体对拍的
        //        `s05_create_folder_user_status_absent` 转红 —— B 侧 `UNAVAILABLE｜系统内部错误`，
        //        C 侧 **`code=OK` 并真的建出了收藏夹**（fail-open，不是 5005）。
        if (user.getUid() != userId || !user.hasStatus()) {
            metrics.observeOp(FavoriteRpcLabels.MODULE_DEPENDENCY,
                    FavoriteRpcLabels.ACTION_USER_GET, FavoriteRpcLabels.RESULT_FAIL);
            LOG.warn("user-rpc returned an unusable identity: user_id={} returned_uid={} has_status={}",
                    userId, user.getUid(), user.hasStatus(), logOptions);
            throw FavoriteExceptions.unavailableServerCommon();
        }

        // B 侧: service/favorite/rpc/internal/logic/helper.go:117-120 —— resp.User.GetStatus() != 0
        if (user.getStatus() != 0L) {
            metrics.observeOp(FavoriteRpcLabels.MODULE_DEPENDENCY,
                    FavoriteRpcLabels.ACTION_USER_GET, FavoriteRpcLabels.RESULT_FAIL);
            LOG.warn("user is not active: user_id={} status={}", userId, user.getStatus(), logOptions);
            throw FavoriteExceptions.forbidden();
        }

        // B 侧: service/favorite/rpc/internal/logic/helper.go:122 —— metrics.ObserveOp("dependency", "user_get", resultSuccess)
        metrics.observeOp(FavoriteRpcLabels.MODULE_DEPENDENCY,
                FavoriteRpcLabels.ACTION_USER_GET, FavoriteRpcLabels.RESULT_SUCCESS);
    }
}
