package com.rag.follow.service;

import com.rag.follow.client.UserRpcClient;
import com.rag.follow.contract.ErrCodes;
import com.rag.proto.user.GetUserResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;

/**
 * <p><b>用户存在性校验</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/service/FollowUserExistenceService.java}
 * （B 侧 102 行）。
 *
 * <h2>⚠️ 为什么独立成 Bean 而不是塞进领域服务</h2>
 * <p>B 侧它是 helper 里的一个包级函数，被 6 个用例调用。
 * 它<b>唯一的区别</b>是依赖 {@code UserRpcClient}；其余部分（校验 / 响应工厂 / 推荐算法纯函数）
 * 都是无依赖纯逻辑，放在 {@link FollowRpcSupport}。
 * 拆成两个类是为了让「依赖 gRPC 下游」与「纯函数」在类型层面分开：
 * 这样 {@link FollowRpcSupport} 可以被纯单测覆盖，不需要 mock stub。
 * ⇒ 拆分只改依赖归属，<b>不改任何可观察行为</b>。
 *
 * <h2>⚠️ 本类的四分支映射是 follow 域最容易被「顺手改好」的地方</h2>
 * <p>B 侧原文（B 侧 {@code FollowUserExistenceService.java:70-102}）：
 * <pre>
 * resp, err := svcCtx.UserRpc.GetUser(ctx, &amp;userservice.GetUserReq{Uid: userID})
 * if err != nil {
 *     st, ok := status.FromError(err)
 *     if ok &amp;&amp; st.Code() == codes.NotFound {
 *         return GRPCError(codes.NotFound, ErrorUserNotFound)     // ① 5101
 *     }
 *     return GRPCError(codes.Internal, ErrorServerCommon)         // ② 5001
 * }
 * if resp == nil || !resp.Found || resp.User == nil {
 *     return GRPCError(codes.NotFound, ErrorUserNotFound)         // ③ 5101
 * }
 * return nil
 * </pre>
 *
 * <table border="1">
 *   <caption>四条分支的判定条件与产出</caption>
 *   <tr><th>分支</th><th>条件</th><th>gRPC 码</th><th>业务码</th></tr>
 *   <tr><td>①</td><td>下游明确返回 {@code NOT_FOUND}</td><td>NOT_FOUND</td><td>5101 user not found</td></tr>
 *   <tr><td>②</td><td>下游<b>超时</b>（{@code DEADLINE_EXCEEDED}）⇒ <b>原样透传</b></td>
 *       <td>DEADLINE_EXCEEDED</td><td><b>1015 service busy</b>（api 层反查的默认码）</td></tr>
 *   <tr><td>③</td><td>下游其它错误（{@code UNAVAILABLE} / {@code INTERNAL} / {@code UNKNOWN} /
 *       {@code CANCELLED}）</td><td>INTERNAL</td><td>5001 internal server error</td></tr>
 *   <tr><td>④</td><td>下游成功但 {@code found=false} 或 {@code user} 为空</td><td>NOT_FOUND</td><td>5101 user not found</td></tr>
 * </table>
 *
 * <p><b>分支③是「非超时的下游故障」的唯一出口</b> ——
 * {@code UNAVAILABLE}（user-rpc 没起来）、{@code INTERNAL}、{@code UNKNOWN}、{@code CANCELLED}
 * 全部被折叠成 {@code INTERNAL + "internal server error"}，客户端看不到区别。
 * <b>不要</b>「改进」成把下游码透传出去：那会让下游拿到的业务码从稳定的 5001
 * 变成随下游故障形态漂移的值。
 *
 * <h3>🔴 为什么唯独 {@code DEADLINE_EXCEEDED} 不折叠（L4 idiomatization · 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧把超时也折叠进分支②（5001）；
 *       C 侧<b>单独放过 DEADLINE_EXCEEDED</b>，让它原样上抛。</li>
 *   <li><b>语义还一样吗</b>：<b>一样</b> —— 因为 B 侧那层折叠<b>在 B 侧从来不可观测</b>：
 *       <ul>
 *         <li><b>gRPC 层</b>：服务端 2s 超时调度器先关 call ⇒ 客户端拿到
 *             {@code DEADLINE_EXCEEDED}（<b>不是</b>被折叠后的 INTERNAL）；</li>
 *         <li><b>HTTP 层</b>：api→rpc 那一跳的 2s <b>客户端</b>预算先到 ⇒ api 层拿到
 *             {@code DEADLINE_EXCEEDED} ⇒ 反查落到默认码 <b>1015 service busy</b>。</li>
 *       </ul>
 *       ⇒ ⇒ B 侧对客户端的承诺是 <b>1015</b>，不是 5001。
 *       <br>⚠️ C 侧合并进程（D1 + D7）后<b>没有 api→rpc 那一跳</b> ⇒ 若照抄折叠，
 *       那层折叠会<b>第一次变得可观测</b> ⇒ HTTP 层拿到 5001 ⇒ 与 B 侧<b>逐字节不同</b>
 *       （E-4 双侧活体对拍场景轮实测：修复前 C=5001 ×3 轮 · B=1015 ×3 轮）。
 *       ⇒ ⇒ 唯一能保持 B 侧可观测行为的做法 = <b>不折叠</b>（本类的分支②）。</li>
 *   <li><b>B 出处</b>：B 侧 {@code FollowUserExistenceService.java:70-102}（三分支原文）
 *       ＋ B 侧 {@code FollowRpcClient}（api→rpc 的 2s 客户端预算先到 ⇒ 1015）
 *       ＋ B 侧 {@code RpcInterceptor}（服务端 2s 超时调度器 ⇒ gRPC 层 DEADLINE_EXCEEDED）
 *       ＋ E-4 实测（{@code docs/DRIFT/follow/verification-follow-2026-11-02.md} §八·六）。</li>
 * </ol>
 *
 * <h2>⚠️ L4 表达层差异</h2>
 * <ul>
 *   <li><b>包名</b>：B 侧 {@code ...follow.rpc.service} ⇒ C 侧 {@code com.rag.follow.service}（D7 压平）。</li>
 *   <li><b>错误码落点</b>：B 侧 {@code FollowErrorCode.ERROR_USER_NOT_FOUND.getCode()} 等枚举访问
 *       ⇒ C 侧 {@link ErrCodes} 常量（码值逐条一致，见 {@code contract/ErrCodes} 的 javadoc）。</li>
 *   <li><b>错误构造</b>：B 侧直接 {@code FollowGrpcError.grpcError(...)} 抛 checked 的 {@code StatusException}
 *       ⇒ C 侧经 {@link FollowRpcSupport#rpcError} 转成 unchecked 的
 *       {@link StatusRuntimeException}（理由见该方法的 javadoc：checked 版本会被
 *       {@code catch (RuntimeException e)} 漏掉 ⇒ 指标把失败记成 success）。</li>
 * </ul>
 */
@Service
public class FollowUserExistenceService {

    private final UserRpcClient userRpcClient;

    public FollowUserExistenceService(UserRpcClient userRpcClient) {
        this.userRpcClient = userRpcClient;
    }

    /**
     * 对应 B 侧 {@code FollowUserExistenceService.java:70-102}：确认 {@code userId} 在 user 域存在。
     *
     * <p>⚠️ <b>四条分支的判定顺序与条件不可改</b>（见类注释的表格）。
     *
     * <p>⚠️ 分支④必须用 {@code hasUser()} 而不是 {@code getUser() != null}：
     * proto3 的 message 字段「未设置」时 {@code getUser()} 返回<b>默认实例（非 null）</b>，
     * 用它判空会把「空用户」当成「用户存在」⇒ 产出错误的成功。
     *
     * <p>⚠️ 分支②（DEADLINE_EXCEEDED 透传）与分支③（其余故障折叠成 5001）
     * <b>只差一个 gRPC 码</b> ⇒ 但产出的业务码差很远（1015 vs 5001）
     * ⇒ ⇒ 改这一处之前先读类注释的「为什么唯独 DEADLINE_EXCEEDED 不折叠」。
     *
     * @throws StatusRuntimeException 四条失败分支之一（5101 / 1015 / 5001），
     *         由 {@code FollowGrpcService} 转成 {@code onError}
     */
    public void ensureUserExists(long userId) {
        GetUserResp resp;
        try {
            resp = userRpcClient.getUser(userId);
        } catch (StatusRuntimeException sre) {
            // 分支①：下游**明确**回 NOT_FOUND ⇒ 与「用户不存在」同义 ⇒ 5101。
            //   ⚠️ 这条分支是活的：user-rpc 对不存在的 uid 就回 NOT_FOUND
            //      （B 侧 users 域的 UserDomainService 同款）。
            //      少了它，「用户不存在」会被报成 5001 —— 下游反查后拿到的是
            //      "internal server error"，而 B 侧拿到 "user not found"。
            if (UserRpcClient.isNotFound(sre)) {
                throw FollowRpcSupport.rpcError(Status.Code.NOT_FOUND, ErrCodes.ERROR_USER_NOT_FOUND);
            }
            // 分支②（2026-11-02 E-4 新增）：下游**超时** ⇒ DEADLINE_EXCEEDED **原样透传**。
            //   ⚠️ 为什么不能折叠：见 UserRpcClient#isDeadlineExceeded 的类注释 ——
            //      B 侧那层折叠**在 B 侧不可观测**（api→rpc 那一跳的 2s 客户端预算先到
            //      ⇒ 客户端看到 DEADLINE_EXCEEDED ⇒ 反查落默认码 **1015 service busy**）。
            //      C 侧合并进程后**没有那一跳** ⇒ 折叠会第一次变得可观测 ⇒ 变成 5001
            //      ⇒ 与 B 侧逐字节不同（E-4 场景轮实测：C=5001 ×3 轮 · B=1015 ×3 轮）。
            //   ⇒ 透传后：HTTP 层反查命中 gRPC 标准码兜底 ⇒ DEADLINE_EXCEEDED ⇒ **1015** ✔。
            //   ⇒ 而 gRPC 层无变化：服务端 2s 超时调度器先关 call ⇒ 客户端仍是 DEADLINE_EXCEEDED。
            if (UserRpcClient.isDeadlineExceeded(sre)) {
                throw sre;
            }
            // 分支③：其余一切下游故障（UNAVAILABLE / INTERNAL /
            //         UNKNOWN / CANCELLED）统一折叠成 5001，客户端不区分。
            //   ⚠️ 这条路径**不**附带 userId 日志选项 —— B 侧同样只是返回 error，
            //      由调用方（用例层）统一打日志。保持一致。
            //   ⚠️ 「下游挂了」仍折叠成 5001（E-4 场景轮实测两侧一致：连接被拒 ⇒ 都是 5001）。
            throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_SERVER_COMMON);
        }

        // 分支④：B 侧的 `resp == nil` 在 C 侧由 protobuf 的 has 语义覆盖 ——
        // getFound() 为 false 或 hasUser() 为 false 都归到这里，产出与 B 侧完全相同的 5101。
        if (!resp.getFound() || !resp.hasUser()) {
            throw FollowRpcSupport.rpcError(Status.Code.NOT_FOUND, ErrCodes.ERROR_USER_NOT_FOUND);
        }
    }
}
