package com.rag.follow.client;

import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.UserServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

/**
 * <p><b>user-rpc gRPC 客户端门面</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/client/UserRpcClient.java}
 * （B 侧 109 行）。
 *
 * <h2>⚠️ L4 表达层差异</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th><th>依据</th></tr>
 *   <tr><td>{@code com.rag.follow.follow.rpc.client.UserRpcClient}</td>
 *       <td>{@code com.rag.follow.client.UserRpcClient}</td>
 *       <td>映射表 {@code docs/Mapping_Table/follow/follow.md} 第 141 / 245 行</td></tr>
 *   <tr><td>{@code com.rag.users.user.proto.*}（B 侧 pb 包名）</td>
 *       <td>{@code com.rag.proto.user.*}</td>
 *       <td>D7：proto 集中到根级 {@code proto/} 模块，pb 包名 {@code com.rag.proto.<域>}</td></tr>
 * </table>
 * ⇒ <b>pb 包名是唯一差异</b>：{@code users.proto} 的 message / 字段号 / 字段名
 * 与 B 侧逐条一致 ⇒ ⇒ <b>wire 层完全一致</b>。
 *
 * <h2>本模块与 user 域的全部交互只有这一个方法</h2>
 * <p>Follow / Block / GetFollowList / GetBlockList / GetFollowerList / GetRecommendations
 * 六个用例都会调它（经 {@code FollowUserExistenceService#ensureUserExists}），
 * <b>Unfollow 与 Unblock 不调</b>（那两个用例只有校验 + 删除）。
 *
 * <h2>⚠️ 本 facade 不吞异常、不做降级</h2>
 * <p>follow 域对「用户不存在」有<b>明确的业务分支</b>（5101），
 * 对「下游挂了」也有<b>明确的业务分支</b>（5001），两者必须在调用点区分开；
 * 一旦在这里 catch 掉，调用点就再也分不出来。
 * ⇒ {@link StatusRuntimeException} 原样抛出，由
 * {@code FollowUserExistenceService} 按 B 侧的<b>四分支</b>映射成业务码
 * （① NOT_FOUND → 5101 · ② DEADLINE_EXCEEDED → **原样透传** ⇒ api 层反查落 **1015**
 * · ③ 其余下游故障 → 5001 · ④ found=false → 5101）。
 *
 * <h2>⚠️ 配置约束：yml 必须写 {@code negotiation-type: plaintext}</h2>
 * <p>devh 的 gRPC 客户端<b>默认走 TLS</b>，而上游 user-rpc 是<b>明文</b> gRPC
 * ⇒ 不显式写 {@code grpc.client.user-rpc.negotiation-type: plaintext} 就会<b>静默发 TLS ClientHello</b>
 * ⇒ 表现为「连不上 / 握手失败」，而错误信息完全不指向根因。
 * ⇒ 本仓守门断言 <b>A4</b> 就是钉这一条：{@code @GrpcClient("X")} ⟷
 * 同模块 yml 的 {@code grpc.client.X.negotiation-type: plaintext}。
 * ⚠️ <b>yml 由另一条 lane 写</b>，本类只负责把这条约束写进 javadoc（不能替它改 yml）。
 *
 * <h2>⚠️ 出向超时由共享层承担，本类不私设上界</h2>
 * <p>B 侧的客户端超时默认值是 <b>2000ms</b>（B 侧 {@code UserRpcClient.java:76} 的
 * {@code CLIENT_TIMEOUT_MILLIS} 与其 javadoc 引用的 Go 侧 zrpc 客户端默认 {@code 2000}）。
 * C 侧的上界由共享层的出向 deadline 拦截器承担（装在 devh channel 上 ⇒ 与调用点无关），
 * 本域只配参数 ⇒ ⇒ <b>本常量只是「B 侧默认值的文档锚点」，不参与任何行为</b>
 * ⇒ ⇒ <b>不得</b>在调用点自己套 {@code CallOptions.withDeadlineAfter(...)}
 * （那会把「可 env 覆盖」架空成写死的 2000ms）。
 */
@Component
public class UserRpcClient {

    /**
     * <b>B 侧客户端超时的默认值锚点 = 2000ms</b>（B 侧 {@code UserRpcClient.java:76}）。
     *
     * <p>⚠️ <b>本常量不参与任何行为</b>（见类注释「出向超时由共享层承担」），
     * 真实上界由共享层的出向 deadline 拦截器 + 本域配置项持有。
     */
    public static final long CLIENT_TIMEOUT_MILLIS = 2000L;

    private final UserServiceGrpc.UserServiceBlockingStub userStub;

    public UserRpcClient(@GrpcClient("user-rpc") UserServiceGrpc.UserServiceBlockingStub userStub) {
        this.userStub = userStub;
    }

    /**
     * 对应 B 侧 {@code UserRpcClient.java:88-91} 的 {@code GetUser} 出向调用。
     *
     * <p>⚠️ <b>BlockingStub 会阻塞调用线程</b> ⇒ 必须带 deadline
     * （C 侧由共享层的出向 deadline 拦截器兜底 ⇒ 它装在 devh channel 上 ⇒ 与调用点无关）；
     * 服务端的整体超时由入站 server interceptor 施加。
     *
     * @throws StatusRuntimeException 下游返回任意 gRPC 错误时原样抛出（不转换）
     */
    public GetUserResp getUser(long uid) {
        return userStub
                .getUser(GetUserReq.newBuilder().setUid(uid).build());
    }

    /**
     * 对应 B 侧 {@code UserRpcClient.java:106-109}：
     * 四分支判定里唯一的「看 status 码」动作，抽出来便于测试断言。
     *
     * <p>只有下游<b>明确</b>回 {@code NOT_FOUND} 才算「用户不存在」；
     * 其余（UNAVAILABLE / DEADLINE_EXCEEDED / INTERNAL / UNKNOWN / CANCELLED）
     * 一律不是 —— 那是「下游故障」，两者在调用点映射成<b>不同</b>的业务码。
     *
     * @param err 调用 {@link #getUser} 抛出的异常
     * @return {@code true} 表示下游明确回的是 {@code NOT_FOUND}
     */
    public static boolean isNotFound(StatusRuntimeException err) {
        Status status = err.getStatus();
        return status != null && status.getCode() == Status.Code.NOT_FOUND;
    }

    /**
     * 对应 B 侧「出向 deadline 到了」的那条错误 —— 供
     * {@code FollowUserExistenceService} 把它与「其它下游故障」**区分开**。
     *
     * <h2>⚠️ 为什么这个区分是必需的（2026-11-02 E-4 双侧活体对拍实测）</h2>
     * <p>B 侧 {@code FollowUserExistenceService} 把**一切**非 NOT_FOUND 的下游故障
     * 都折叠成 {@code INTERNAL + "internal server error"}（5001）。
     * <b>但 B 侧那层折叠在 B 侧从来不可观测</b> —— 两层各自先到期：
     * <table border="1">
     *   <caption>B 侧「下游挂死」时每一层实际看到什么</caption>
     *   <tr><th>层</th><th>先到期的东西</th><th>客户端实际看到</th></tr>
     *   <tr><td>gRPC 层（follow-rpc）</td>
     *       <td>服务端的 2s 超时调度器（{@code RpcInterceptor}）先关 call</td>
     *       <td>{@code DEADLINE_EXCEEDED}</td></tr>
     *   <tr><td>HTTP 层（follow-api）</td>
     *       <td>api→rpc 那一跳的 2s <b>客户端</b>预算先到</td>
     *       <td>{@code DEADLINE_EXCEEDED} ⇒ 反查落到默认码 <b>1015</b></td></tr>
     * </table>
     * ⇒ ⇒ B 侧对客户端的<b>承诺</b>是 <b>1015 service busy</b>，不是 5001。
     *
     * <p>⚠️ <b>C 侧合并进程（D1 + D7）后没有 api→rpc 那一跳</b>
     * ⇒ ⇒ 若照抄「折叠成 INTERNAL」，那层折叠就会<b>第一次变得可观测</b>
     * ⇒ ⇒ HTTP 层会拿到 5001 ⇒ <b>与 B 侧逐字节不同</b>（实测：E-4 场景轮 3/3 轮 C=5001 · B=1015）。
     * ⇒ ⇒ 因此 {@code FollowUserExistenceService} 必须<b>单独放过 DEADLINE_EXCEEDED</b>。
     *
     * @param err 调用 {@link #getUser} 抛出的异常
     * @return {@code true} 表示下游是<b>超时</b>（DEADLINE_EXCEEDED）
     */
    public static boolean isDeadlineExceeded(StatusRuntimeException err) {
        Status status = err.getStatus();
        return status != null && status.getCode() == Status.Code.DEADLINE_EXCEEDED;
    }
}
