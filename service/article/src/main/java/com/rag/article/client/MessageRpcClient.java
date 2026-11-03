package com.rag.article.client;

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
 * <p><b>职责</b>：message 域的 gRPC 客户端 —— 文章首次发布成功后给作者发通知。
 * <b>全模块只有一个调用点</b>，因此本类也只暴露一个方法，不为了「通用客户端」而泛化。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/MessageRpcClient.java}
 * （类声明 {@code :44-45} · 字段 {@code :53-54} · {@code sendArticlePublished} {@code :64-102} ·
 * {@code messageRpc()} {@code :158-160}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.client} → {@code com.rag.article.client}（D7 包压平）；</li>
 *         <li><b>proto 包名</b>：{@code com.rag.message.message.proto.*} → {@code com.rag.proto.message.*}
 *             （D7：proto 集中到根级 {@code proto/} 模块，pb 包名 = {@code com.rag.proto.<域>}）；</li>
 *         <li><b>字段注入 → 构造器注入</b>：devh starter 两种都支持
 *             （{@code @GrpcClient} 可标在构造器参数上）；
 *             本仓已落地域（{@code service/message/.../client/UserRpcClient.java}）用的是构造器注入
 *             ⇒ ⇒ 与本仓形态统一。<b>注入的 stub 类型、id、寻址方式一字未改</b>。</li>
 *       </ul>
 *       ⇒ <b>方法体逐条照抄</b>（含通知文案、extra 两个键、吞异常的返回值语义）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>仍是 <b>BlockingStub</b>（Go 的 {@code SendNotification} 是同步阻塞调用，
 *             用 AsyncStub 会把调用变成 fire-and-forget，<b>改变可观察行为</b>）；</li>
 *         <li>仍是<b>吞掉所有异常并返回 false</b>（通知在事务提交之后发，
 *             此时文章<b>已经发布成功</b>；若通知失败让整个消费失败 ⇒ Kafka 重投
 *             ⇒ 重投会命中 {@code published_once} 守卫变成 no-op ⇒ 通知<b>永远补不上</b>）；</li>
 *         <li>{@code extra.author_id} 仍传<b>原串</b>（不是 {@code String.valueOf(recipientId)}）；</li>
 *         <li>标题判空仍用 {@code TrimSpace}（只有空白字符时也走无标题文案）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ L1：gRPC 客户端名一字不改</b>：{@code @GrpcClient("message-rpc")} 的
 * {@code "message-rpc"} 是<b>客户端 id</b>，与 {@code application.yml} 的
 * {@code grpc.client.message-rpc.address} 一一对应（devh 的 name resolver 从这里取目标地址）
 * ⇒ ⇒ 该 id 属 L1，<b>不得</b>改成 {@code "message-service"} 之类。
 *
 * <p><b>⚠️ 地址必须是三斜杠</b>（{@code discovery:///message-rpc}）：devh 3.1.0 的
 * {@code DiscoveryClientResolverFactory#newNameResolver} 要求 path 非空且以 {@code "/"} 开头
 * ⇒ {@code discovery://message-rpc}（两斜杠）会抛
 * {@code IllegalArgumentException: Incorrectly formatted target uri}
 * ⇒ ⇒ <b>这个检查发生在任何网络调用之前</b> ⇒ ⇒ 与 Nacos 在不在线无关。
 */
@Component
public class MessageRpcClient {

    private static final Logger log = LoggerFactory.getLogger(MessageRpcClient.class);

    private final MessageServiceGrpc.MessageServiceBlockingStub stub;

    /**
     * 构造器注入 gRPC stub —— devh starter 按 {@code @GrpcClient} 的值组装。
     *
     * <p><b>B 出处</b>：{@code .../client/MessageRpcClient.java:53-54}（字段）
     * ＋ {@code :158-160}（{@code messageRpc()} 取用）。
     *
     * @param stub {@code message-rpc} 客户端的阻塞 stub
     */
    public MessageRpcClient(@GrpcClient("message-rpc") MessageServiceGrpc.MessageServiceBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * 对应 Go {@code notifyArticlePublished} 的完整调用。
     *
     * <p><b>⚠️ 失败语义：吞掉异常，<b>不影响</b>发布结果</b>
     * <pre>
     * Go: _, err = l.svcCtx.MessageRpc.SendNotification(...)
     *     if err != nil {
     *         logger.LogBusinessErr(ctx, errmsg.ErrorServerCommon, ...)   // ← 只记日志
     *     }
     * </pre>
     *
     * @param recipientId 作者 uid（Go 侧从 {@code article.AuthorID} 解析出的 int64）
     * @param authorId    作者 ID <b>原串</b>（通知 extra 里要放它）
     * @param articleId   文章 ID
     * @param title       文章标题；<b>空白时</b>走 Go 的无标题文案分支
     * @return true = RPC 正常返回；false = 失败（已记日志，调用方<b>不应</b>因此报错）
     */
    public boolean sendArticlePublished(long recipientId, String authorId, String articleId, String title) {
        // Go: content := "你的文章已经完成审核并成功入库。"
        //      if strings.TrimSpace(title) != "" { content = fmt.Sprintf("《%s》已经完成审核并成功入库。", strings.TrimSpace(title)) }
        // ⚠️ 两处都用 TrimSpace(title)：标题只有空白字符时也走无标题文案。
        String content = "你的文章已经完成审核并成功入库。";
        if (title != null && !title.trim().isEmpty()) {
            content = "《" + title.trim() + "》已经完成审核并成功入库。";
        }

        SendNotificationReq.Builder req = SendNotificationReq.newBuilder()
                // Go: RecipientIds: []int64{uid}
                .addRecipientIds(recipientId)
                // Go: Broadcast: false
                .setBroadcast(false)
                // Go: SenderId: 0 —— 系统消息，无发送人
                .setSenderId(0)
                // Go: SenderRole: messagepb.SenderRole_SYSTEM
                //    生成物里的 Java 常量名是 SenderRole.SYSTEM（proto 值名 SYSTEM，不是 SENDER_ROLE_SYSTEM）
                .setSenderRole(SenderRole.SYSTEM)
                // Go: Kind: messagepb.NotificationKind_ARTICLE_PUBLISHED
                .setKind(NotificationKind.ARTICLE_PUBLISHED)
                // Go: Title: "文章发布成功"
                .setTitle("文章发布成功")
                .setContent(content)
                // Go: Extra: map[string]string{"article_id":…, "author_id":…}
                .putExtra("article_id", articleId)
                // ⚠️ 必须是原串（Go :207），非 String.valueOf(recipientId)
                .putExtra("author_id", authorId);

        try {
            stub.sendNotification(req.build());
            return true;
        } catch (StatusRuntimeException e) {
            // Go: logger.LogBusinessErr(..., errmsg.ErrorServerCommon, ...) —— 吞掉，不影响发布结果
            log.warn("send article published notification failed: article={} recipient={} err={}",
                    articleId, recipientId, e.getMessage());
            return false;
        }
    }
}
