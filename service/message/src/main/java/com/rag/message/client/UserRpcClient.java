package com.rag.message.client;

import com.rag.proto.message.SenderRole;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.UserServiceGrpc;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：<b>跨域</b> gRPC 客户端 —— 调 <b>user 域</b>的 {@code UserService.GetUser}，
 * 取对端的 {@code (username, email)}。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/client/UserRpcClient.java}
 * （1-62 行；类声明 {@code :21-22}；字段与构造器 {@code :26-30}；
 * {@code getProfile} {@code :37-53}；{@code loadProfile} {@code :56-61}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li>包名 {@code com.rag.message.message.rpc.client} → {@code com.rag.message.client}（D7 包压平）；</li>
 *         <li>proto 包名 {@code com.rag.users.user.proto.*} → {@code com.rag.proto.user.*}
 *             （D7：proto 集中到根级 {@code proto/} 模块，pb 包名 = {@code com.rag.proto.<域>}）。</li>
 *       </ul>
 *       <b>方法体逐字照抄</b>（{@code getProfile} 的兜底三分支 + {@code loadProfile} 的组别分流）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>兜底字符串逐字未动</b>：{@code "User " + id}（uid ≤ 0 / 查不到 / 抛错 三种情形
 *             都是它）；</li>
 *         <li><b>失败不阻塞业务</b>：profile 只用于展示，失败时返回兜底而不是上抛
 *             （B 侧 {@code :43-52} 的两个 catch 逐条照抄）；</li>
 *         <li><b>组别分流</b>：{@code loadProfile(USER, id)} 走 user 通道，
 *             其它组别返回 {@code "Unknown " + id}（B 侧 {@code :56-61}）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ L1：gRPC 客户端名一字不改</b>：{@code @GrpcClient("user-rpc")} 的
 * {@code "user-rpc"} 是<b>客户端 id</b>，与 {@code application.yml} 的
 * {@code grpc.client.user-rpc.address} 一一对应（devh 的 name resolver 从这里取目标地址）⇒
 * ⇒ 该 id 属 L1，<b>不得</b>改成 {@code "user-service"} 之类。
 *
 * <p><b>⚠️ 地址必须是三斜杠</b>（B 侧 yml 的同款硬要求）：devh 3.1.0 的
 * {@code DiscoveryClientResolverFactory#newNameResolver} 要求 path 非空且以 {@code "/"} 开头
 * ⇒ {@code discovery://user-rpc}（两斜杠）会抛
 * {@code IllegalArgumentException: Incorrectly formatted target uri} ⇒
 * ⇒ 必须是 {@code discovery:///user-rpc}。
 */
@Component
public class UserRpcClient {

    private static final Logger log = LoggerFactory.getLogger(UserRpcClient.class);

    private final UserServiceGrpc.UserServiceBlockingStub userStub;

    /**
     * 构造器注入 gRPC stub —— devh starter 按 {@code @GrpcClient} 的值组装。
     *
     * <p><b>B 出处</b>：{@code .../client/UserRpcClient.java:26-30}。
     *
     * @param userStub {@code user-rpc} 客户端的阻塞 stub
     */
    public UserRpcClient(@GrpcClient("user-rpc") UserServiceGrpc.UserServiceBlockingStub userStub) {
        this.userStub = userStub;
    }

    /**
     * 拉 user profile 的 {@code (username, email)}。
     *
     * <p>失败 / 找不到时返回 {@code ["User {id}", ""]} 兜底，与 B 侧 1:1
     * （profile 仅用于展示，失败不阻塞业务）。
     *
     * <p><b>B 出处</b>：{@code .../client/UserRpcClient.java:37-53}。
     *
     * @param uid 用户 id
     * @return {@code [username, email]}；任何失败都退化成 {@code ["User {uid}", ""]}
     */
    public String[] getProfile(long uid) {
        if (uid <= 0) {
            return new String[]{"User " + uid, ""};
        }
        try {
            var resp = userStub.getUser(GetUserReq.newBuilder().setUid(uid).build());
            if (resp != null && resp.getFound() && resp.getUser() != null) {
                return new String[]{resp.getUser().getUsername(), resp.getUser().getEmail()};
            }
            return new String[]{"User " + uid, ""};
        } catch (StatusRuntimeException sre) {
            return new String[]{"User " + uid, ""};
        } catch (RuntimeException e) {
            log.warn("getUser({}) failed: {}", uid, e.getMessage());
            return new String[]{"User " + uid, ""};
        }
    }

    /**
     * 复用 facade 入口（与 B 侧 {@code loadProfile(role, id)} 签名一致）。
     *
     * <p><b>B 出处</b>：{@code .../client/UserRpcClient.java:56-61}。
     *
     * @param role 对端角色（{@code USER} 走本通道；其它组别返回 {@code "Unknown {id}"}）
     * @param id   对端 id
     * @return {@code [username, email]}；任何失败都退化成兜底串
     */
    public String[] loadProfile(SenderRole role, long id) {
        if (role == SenderRole.USER) {
            return getProfile(id);
        }
        return new String[]{"Unknown " + id, ""};
    }
}
