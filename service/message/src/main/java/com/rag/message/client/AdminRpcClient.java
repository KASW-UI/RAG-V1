package com.rag.message.client;

import com.rag.message.exception.MessageExceptions;
import com.rag.proto.admin.AdminServiceGrpc;
import com.rag.proto.admin.GetSelfReq;
import com.rag.proto.admin.GetUserListReq;
import com.rag.proto.message.SenderRole;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * <p><b>职责</b>：<b>跨域</b> gRPC 客户端 —— 调 <b>admin 域</b>的
 * {@code AdminService.GetSelf} / {@code AdminService.GetUserList}，
 * 提供「admin peer profile」与「广播通知收件人列表」。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/client/AdminRpcClient.java}
 * （1-123 行；类声明 {@code :35-36}；字段与构造器 {@code :40-44}；
 * {@code loadProfile} {@code :50-69}；{@code collectBroadcastRecipients} {@code :84-118}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li>包名 {@code com.rag.message.message.rpc.client} → {@code com.rag.message.client}（D7 包压平）；</li>
 *         <li>proto 包名 {@code com.rag.users.admin.proto.*} → {@code com.rag.proto.admin.*}
 *             （D7：proto 集中到根级 {@code proto/} 模块）；</li>
 *         <li><b>删掉 B 侧那个「抑制 unused warning」的假字段</b>
 *             （B 侧 {@code :120-122} 的 {@code UNUSED_KEEP_REFERENCE}）——
 *             它的唯一作用是让 B 侧那个没被使用的 {@code MessageErrorCode} import 不报警
 *             ⇒ 本仓<b>根本不 import 错误码类</b>（本类不需要）⇒ 该字段与它的 import 一起消失
 *             ⇒ ⇒ <b>零可观察行为</b>（既不读也不写任何状态）。</li>
 *       </ul>
 *       <b>方法体逐字照抄</b>（{@code loadProfile} 的组别分流 + 两个兜底分支；
 *       {@code collectBroadcastRecipients} 的 5 条分页规则）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>兜底串 {@code "Admin " + id} / {@code "Unknown " + id} 逐字未动；</li>
 *         <li><b>广播收件人的 5 条规则逐条未动</b>：{@code pageSize <= 0} 兜底 200 ·
 *             从 page=1 开始逐页拉 · 某页空 ⇒ 停止 · 页内累积 uid 去重（{@link LinkedHashSet}）
 *             · 当前页返回数 &lt; pageSize ⇒ 最后一页；</li>
 *         <li><b>RPC 抛错 ⇒ 向上抛</b> {@link MessageExceptions.DbSelectException}
 *             （由领域层 {@code observeDbError} + 统一映射）—— 与 B 侧逐字一致。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ L1：gRPC 客户端名一字不改</b>：{@code @GrpcClient("admin-rpc")} 的
 * {@code "admin-rpc"} 是<b>客户端 id</b>，与 {@code application.yml} 的
 * {@code grpc.client.admin-rpc.address} 一一对应 ⇒ 属 L1，不得改写。
 *
 * <p><b>⚠️ 地址必须是三斜杠</b>：{@code discovery:///admin-rpc}（B 侧 yml 的同款硬要求）。
 */
@Component
public class AdminRpcClient {

    private static final Logger log = LoggerFactory.getLogger(AdminRpcClient.class);

    private final AdminServiceGrpc.AdminServiceBlockingStub adminStub;

    /**
     * 构造器注入 gRPC stub —— devh starter 按 {@code @GrpcClient} 的值组装。
     *
     * <p><b>B 出处</b>：{@code .../client/AdminRpcClient.java:40-44}。
     *
     * @param adminStub {@code admin-rpc} 客户端的阻塞 stub
     */
    public AdminRpcClient(@GrpcClient("admin-rpc") AdminServiceGrpc.AdminServiceBlockingStub adminStub) {
        this.adminStub = adminStub;
    }

    /**
     * 拉 admin peer profile。
     *
     * <p>失败 / 找不到 → 兜底 {@code ["Admin {id}", ""]}。
     *
     * <p><b>B 出处</b>：{@code .../client/AdminRpcClient.java:50-69}。
     *
     * @param role 对端角色（{@code ADMIN} 走本通道；其它组别返回 {@code "Unknown {id}"}）
     * @param id   对端 id
     * @return {@code [username, email]}；任何失败都退化成兜底串
     */
    public String[] loadProfile(SenderRole role, long id) {
        if (role != SenderRole.ADMIN) {
            return new String[]{"Unknown " + id, ""};
        }
        if (id <= 0) {
            return new String[]{"Admin " + id, ""};
        }
        try {
            var resp = adminStub.getSelf(GetSelfReq.newBuilder().setUid(id).build());
            if (resp != null && resp.getAdmin() != null) {
                return new String[]{resp.getAdmin().getUsername(), resp.getAdmin().getEmail()};
            }
            return new String[]{"Admin " + id, ""};
        } catch (StatusRuntimeException sre) {
            return new String[]{"Admin " + id, ""};
        } catch (RuntimeException e) {
            log.warn("getSelf({}) failed: {}", id, e.getMessage());
            return new String[]{"Admin " + id, ""};
        }
    }

    /**
     * 通过 {@code admin.GetUserList} 分页拉所有 user_id，作为广播通知的 recipients。
     *
     * <p>行为 1:1（B 侧 {@code :84-118}）：
     * <ul>
     *   <li>pageSize 由调用方传入（领域层从 {@code message.broadcast.page-size} 取）；≤0 兜底 200</li>
     *   <li>从 page=1 开始逐页拉；某页空 → 停止</li>
     *   <li>page 内累积 uid，去重（Go 端 dedupeRecipients 在 ServiceContext 中；Java 端用 {@link LinkedHashSet}）</li>
     *   <li>当前页返回数 &lt; pageSize → 最后一页，停止</li>
     *   <li>RPC 抛错 → 向上抛 {@link MessageExceptions.DbSelectException}
     *       （由领域层 observeDbError + 统一映射为 INTERNAL + ErrorServerCommon）</li>
     * </ul>
     *
     * @param pageSize 每页条数（≤0 ⇒ 200）
     * @return 去重后的收件人 id 列表
     * @throws MessageExceptions.DbSelectException RPC 失败
     */
    public List<Long> collectBroadcastRecipients(long pageSize) {
        if (pageSize <= 0) {
            pageSize = 200L;
        }
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        long page = 1L;
        try {
            while (true) {
                var resp = adminStub.getUserList(
                        GetUserListReq.newBuilder()
                                .setPage(page)
                                .setPageSize(pageSize)
                                .build());
                if (resp == null || resp.getListList().isEmpty()) {
                    break;
                }
                for (var item : resp.getListList()) {
                    if (item != null && item.getUid() > 0L) {
                        recipients.add(item.getUid());
                    }
                }
                if (resp.getListList().size() < pageSize) {
                    break;
                }
                page++;
            }
        } catch (StatusRuntimeException sre) {
            throw new MessageExceptions.DbSelectException(
                    "broadcast user list failed: page=" + page, sre);
        } catch (RuntimeException e) {
            throw new MessageExceptions.DbSelectException(
                    "broadcast user list failed: page=" + page, e);
        }
        return new ArrayList<>(recipients);
    }
}
