package com.rag.comment.client;

import com.rag.proto.message.MessageServiceGrpc;
import com.rag.proto.message.NotificationKind;
import com.rag.proto.message.SendNotificationReq;
import com.rag.proto.message.SenderRole;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <p><b>message-rpc 客户端</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/client/MessageRpcClient.java:1-123}
 * （B 侧的语义来源注释写的是 Go 端
 * {@code service/comment/rpc/internal/svc/service_context.go:36} 的
 * {@code MessageRpc: messageservice.NewMessageService(zrpc.MustNewClient(c.MessageRpc))}）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.client} 移到 {@code com.rag.comment.client}（D7 · D10）；</li>
 *       <li>pb 包名 {@code com.rag.message.message.proto} → {@code com.rag.proto.message}
 *           （本仓根级 {@code proto/} 模块的 {@code java_package} 约定）；</li>
 *       <li>域内私有副本 {@code GoStyleClientCallTimeout} <b>不重建</b> ——
 *           出向 deadline 由共享层的 {@code OutboundDeadlineInterceptor}
 *           （devh 的 {@code @GrpcGlobalClientInterceptor}）统一施加，本域<b>只配参数</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。寻址名 {@code message-rpc}、10 个字段取值、
 *       「通知发不出去只记日志、不影响消费」的失败语义逐字未改。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../client/MessageRpcClient.java:1-123}。</li>
 * </ol>
 *
 * <h2>全模块只有一个调用点</h2>
 * <p>B 侧自记：{@code mqs/audit_consumer.go:101} 的
 * {@code SendNotification}（评论通过审核后通知 up 主 / 父评论作者）。
 * 因此本类也只暴露一个方法 —— 不为了「通用客户端」而泛化。
 *
 * <h2>⚠️ 失败语义：吞掉，不影响消费</h2>
 * <p>B 侧 {@code audit_consumer.go:117-119}：
 * <pre>
 * if err != nil {
 *     l.Errorf("send comment notification failed, comment %d: %v", msg.CommentId, err)
 * }
 * </pre>
 * 通知发不出去<b>只记日志</b>。此时评论已经落库、缓存已失效，
 * 若把错误抛出去会让 Kafka 消息重投 ⇒ <b>重复落库 + 重复通知</b>。
 * ⇒ 本方法返回 {@code boolean}，调用方<b>不</b>据此抛错。
 *
 * <p><b>用 {@code BlockingStub}</b>：B 侧的调用是同步阻塞的，
 * 换成 AsyncStub 会变成 fire-and-forget，改变「消费耗时包含 message-rpc 往返」这一可观察行为。
 *
 * <p>⚠️ 枚举常量名：proto 里是 {@code ARTICLE_COMMENT} / {@code USER}
 * （不是 Go 生成物的 {@code NotificationKind_ARTICLE_COMMENT}），
 * Java 生成器会把枚举值名原样保留、并从类型名上去掉前缀。
 */
@Component
public class MessageRpcClient {

    private static final Logger LOG = LoggerFactory.getLogger(MessageRpcClient.class);

    /**
     * 对应 Go {@code zrpc.MustNewClient(c.MessageRpc)} 的寻址方式：
     * {@code MessageRpc.Etcd.Key: message.rpc} ⇒ 服务发现名 {@code message-rpc}。
     */
    @GrpcClient("message-rpc")
    private MessageServiceGrpc.MessageServiceBlockingStub stub;

    /**
     * 出向 stub —— <b>deadline 由共享层的全局客户端拦截器统一施加</b>。
     *
     * <p>🔴 <b>B 侧出处（行为层）</b>：B 侧 {@code zrpc.MustNewClient(c.MessageRpc)} 的客户端
     * <b>无条件</b>装 {@code TimeoutInterceptor}
     * （{@code zrpc/internal/client.go:113-117} ＋ {@code zrpc/internal/config.go:15} 的
     * {@code default=true}）⇒ 每次调用都有 <b>2000ms</b> 上限
     * （{@code zrpc/config.go:31} 的 {@code json:",default=2000"}；本域 B 侧
     * {@code etc/comment.yaml} 的 {@code MessageRpc:} 段<b>没写</b> {@code Timeout} ⇒ 走这个默认值）。
     *
     * <p>🔴 <b>为什么本类不再自己套 deadline</b>：B 侧的客户端超时是<b>拦截器级别</b>的
     * ⇒ ⇒ C 侧同形也应是拦截器级 ⇒ ⇒ 改由共享层的
     * {@code OutboundDeadlineInterceptor}（devh 的 {@code @GrpcGlobalClientInterceptor}
     * ⇒ 装在<b>每个</b>出向 devh 调用上 ⇒ 连<b>裸 stub</b> 都过链）统一施加，
     * 本域<b>只配参数</b>：{@code rag.protection.outbound-deadline.cap-millis=2000}
     * （见本组件 {@code application.yml}；共享层默认 {@code capMillis=0} ⇒ <b>不配 = 不启用</b>）。
     *
     * <p>⚠️ <b>本出口跑在 Kafka consumer 线程</b>上
     * （调用点是 {@code messaging/CommentAuditListener} 的 {@code notifyComment}，
     * 而该类的 {@code onMessage} 是 {@code @KafkaListener}）
     * ⇒ 共享层 {@code RpcInterceptor.onHalfClose} 绑的 {@code GoStyleRequestDeadline} 载体
     * <b>在本线程不可见</b> ⇒ ⇒ 出向只剩 <b>2s 客户端封顶</b>
     * ⇒ ⇒ 与 B 侧<b>同形</b>：B 侧的 {@code service/comment/rpc/internal/mqs/audit_consumer.go:101}
     * 传的 ctx 是 <b>Kafka 消费 ctx（无 deadline）</b> ⇒ B 侧这条出口同样只有 2s 封顶
     * ⇒ ⇒ <b>本出口不是漂移</b>。
     *
     * <p>⚠️ <b>域内私有副本 {@code GoStyleClientCallTimeout} 不重建</b>：
     * <b>副本数 = 漂移面</b> ⇒ ⇒ 共享层守门 {@code GoStyleClientCallTimeoutSingleSourceGateTest}
     * 的终态口径 = 「域侧 0 份，只配参数」。
     */
    private MessageServiceGrpc.MessageServiceBlockingStub messageRpc() {
        return stub;
    }

    /**
     * 对应 B 侧 {@code notifyComment} 的完整 {@code SendNotification} 调用
     * （B 侧 {@code MessageRpcClient.java:84-122}）。
     *
     * @param recipientId 收件人（up 主或父评论作者）
     * @param kind        {@code ARTICLE_COMMENT} 或 {@code COMMENT_REPLY}
     * @return true = RPC 正常返回；false = 失败（已记日志，调用方<b>不应</b>因此报错）
     */
    public boolean sendNotification(long recipientId, long senderId,
                                    NotificationKind kind, String title, String content,
                                    String targetType, String targetId,
                                    long commentId, long rootId, long parentId) {
        try {
            messageRpc().sendNotification(SendNotificationReq.newBuilder()
                    // B 侧: RecipientIds: []int64{recipientID}
                    .addRecipientIds(recipientId)
                    // B 侧: Broadcast: false —— 只通知这一个人。
                    // ⚠️ 这条若被误改成 true，message-rpc 会**误判成全量广播**
                    //    （DRIFT-MSGAPI-005 就是这么出事的），不可省略。
                    .setBroadcast(false)
                    .setSenderId(senderId)
                    // B 侧: SenderRole: messagepb.SenderRole_USER
                    .setSenderRole(SenderRole.USER)
                    .setKind(kind)
                    .setTitle(title)
                    .setContent(content)
                    .putExtra("target_type", targetType)
                    .putExtra("target_id", targetId)
                    // B 侧用 fmt.Sprintf("%d", …) 把数值转字符串放进 map<string,string>
                    // ⇒ Java 侧必须转成 String，不能直接 putExtra(long)
                    .putExtra("comment_id", String.valueOf(commentId))
                    .putExtra("root_id", String.valueOf(rootId))
                    .putExtra("parent_id", String.valueOf(parentId))
                    .build());
            return true;
        } catch (StatusRuntimeException e) {
            LOG.error("send comment notification failed, comment {}: {}", commentId, e.getStatus(), e);
            return false;
        }
    }
}
