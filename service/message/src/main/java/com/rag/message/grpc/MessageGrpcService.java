package com.rag.message.grpc;

import com.rag.message.domain.service.MessageDomainService;
import com.rag.proto.message.BaseResp;
import com.rag.proto.message.ConversationListReq;
import com.rag.proto.message.ConversationListResp;
import com.rag.proto.message.ConversationMessageListReq;
import com.rag.proto.message.ConversationMessageListResp;
import com.rag.proto.message.MarkAllNotificationsReq;
import com.rag.proto.message.MarkConversationReadReq;
import com.rag.proto.message.MarkNotificationReq;
import com.rag.proto.message.MessageServiceGrpc;
import com.rag.proto.message.NotificationListReq;
import com.rag.proto.message.NotificationListResp;
import com.rag.proto.message.SendChatMessageReq;
import com.rag.proto.message.SendChatMessageResp;
import com.rag.proto.message.SendNotificationReq;
import com.rag.proto.message.SendNotificationResp;
import com.rag.proto.message.UnreadSummaryReq;
import com.rag.proto.message.UnreadSummaryResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <p><b>职责</b>：message 域的 <b>gRPC 服务端</b> —— 9 个 RPC 方法的 <b>薄转发</b>。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/impl/MessageRpcServiceImpl.java}
 * （1-169 行；类声明 {@code :61-62}；构造器 {@code :68-70}；9 个 override {@code :72-115}；
 * {@code invoke} 模板 {@code :124-168}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>类名</b> {@code MessageRpcServiceImpl} → {@code MessageGrpcService}
 *             ＋ <b>包名</b> {@code ...rpc.service.impl} → {@code com.rag.message.grpc}
 *             （映射表 message.md §0.5「{@code message/rpc/service/impl/} → {@code grpc/}
 *             压平 + 类改名」已批准）；</li>
 *         <li><b>proto 包名</b> {@code com.rag.message.message.proto.*} → {@code com.rag.proto.message.*}
 *             ⇒ 生成的基类也从 {@code com.rag.message.message.proto.MessageServiceGrpc.MessageServiceImplBase}
 *             变成 {@link com.rag.proto.message.MessageServiceGrpc.MessageServiceImplBase}（D7）；</li>
 *         <li><b>依赖的领域服务换落点</b>：B 侧注入 {@code ...rpc.service.MessageDomainService}
 *             ⇒ 本仓注入 {@link com.rag.message.domain.service.MessageDomainService}（D7 包压平）。
 *             ⚠️ <b>方法签名逐条未动</b>：B 侧的领域服务就是「入参出参都是 proto」
 *             （B 侧 {@code MessageDomainService} 的 9 个方法签名与 proto 一一对应）⇒
 *             ⇒ 本仓的领域服务<b>同样</b>是 proto 进 proto 出 ⇒ ⇒ 转发是<b>纯转发</b>，
 *             <b>中间没有 DTO / 领域模型的转换</b>（转换发生在领域层内部）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>兜底 catch 逐字照抄</b>：{@link StatusRuntimeException} 原样 {@code onError}；
 *             其它 {@link RuntimeException} ⇒ {@code Status.UNKNOWN.withCause(e)}
 *             （<b>不是 INTERNAL</b>，见 {@link #invoke} 的长注释）；</li>
 *         <li><b>成功路径</b>：{@code onNext(resp)} + {@code onCompleted()}；</li>
 *         <li><b>9 个方法名与顺序</b>逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <p><b>9 个 RPC method</b>（与 proto 里的 9 个一一对应）：
 * <ul>
 *   <li>通知域：{@code sendNotification} / {@code listNotifications} /
 *       {@code markNotificationRead} / {@code markAllNotificationsRead} /
 *       {@code getUnreadSummary}</li>
 *   <li>会话域：{@code listConversations} / {@code getConversationMessages} /
 *       {@code sendChatMessage} / {@code markConversationRead}</li>
 * </ul>
 *
 * <p><b>⚠️ 指标埋点的落点：{@code observeRpc} 在<b>领域层</b>，不在本类</b>
 * （B 侧逐字对照）：
 * <ul>
 *   <li>B 侧的 {@code .../rpc/service/impl/MessageRpcServiceImpl.java}（1-169 行）
 *       <b>没有任何指标调用</b>（逐行核对结论）；</li>
 *   <li>B 侧的 {@code .../rpc/service/MessageDomainService.java} 在 9 个方法各自的
 *       {@code finally} 里调 {@code metrics.observeRpc("SendChatMessage", start, err)} 等
 *       （B 侧行号 {@code :176} / {@code :419} / {@code :495} / {@code :628} / {@code :713} /
 *       {@code :807} / {@code :880} / {@code :940} / {@code :982}）；</li>
 *   <li>本仓的 {@link com.rag.message.domain.service.MessageDomainService} <b>逐条照抄</b>了
 *       那 9 处埋点（同名 method label：{@code SendChatMessage} / {@code SendNotification} /
 *       {@code ListConversations} / {@code GetConversationMessages} / {@code MarkConversationRead} /
 *       {@code ListNotifications} / {@code MarkNotificationRead} /
 *       {@code MarkAllNotificationsRead} / {@code GetUnreadSummary}）。</li>
 * </ul>
 * ⇒ ⇒ <b>本类刻意<b>不</b>再记一次</b>：若在 gRPC 层再记一遍，
 * {@code message_rpc_request_total} 与 {@code message_rpc_request_duration_seconds}
 * 就会<b>翻倍</b>（领域层已记一次）⇒ ⇒ 与 B 侧「每次业务调用恰好记一次」不一致
 * ⇒ ⇒ <b>埋点没有丢，只是落点与 B 侧一致（在领域层）</b>。
 *
 * <p><b>框架职责</b>：grpc-java 框架机制约束（一个 service 一个实现类）—— 非主动合并；
 * {@link GrpcService} 让 devh grpc-server-spring-boot-starter 自动注册到 gRPC server。
 *
 * <p><b>DI 风格</b>：{@link MessageDomainService} 通过 Spring DI 注入；领域服务是单例 Bean，
 * 方法本身 stateless，<b>不需要每次请求 new</b>（B 侧 Go 端用
 * {@code logic.NewXxxLogic(ctx, s.svcCtx)}）。
 */
@GrpcService
public class MessageGrpcService extends MessageServiceGrpc.MessageServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(MessageGrpcService.class);

    private final MessageDomainService domain;

    /**
     * 构造器注入 —— B 侧逐字照抄。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:68-70}。
     *
     * @param domain 领域服务（proto 进 proto 出）
     */
    public MessageGrpcService(MessageDomainService domain) {
        this.domain = domain;
    }

    /**
     * 发送私信 —— B 侧 {@code sendchatmessagelogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:72-75}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void sendChatMessage(SendChatMessageReq request,
                                StreamObserver<SendChatMessageResp> responseObserver) {
        invoke(() -> domain.sendChatMessage(request), responseObserver);
    }

    /**
     * 发送通知 —— B 侧 {@code sendnotificationlogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:77-80}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void sendNotification(SendNotificationReq request,
                                 StreamObserver<SendNotificationResp> responseObserver) {
        invoke(() -> domain.sendNotification(request), responseObserver);
    }

    /**
     * 会话列表 —— B 侧 {@code listconversationslogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:82-85}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void listConversations(ConversationListReq request,
                                  StreamObserver<ConversationListResp> responseObserver) {
        invoke(() -> domain.listConversations(request), responseObserver);
    }

    /**
     * 会话消息列表 —— B 侧 {@code getconversationmessageslogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:87-90}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void getConversationMessages(ConversationMessageListReq request,
                                       StreamObserver<ConversationMessageListResp> responseObserver) {
        invoke(() -> domain.getConversationMessages(request), responseObserver);
    }

    /**
     * 标记会话已读 —— B 侧 {@code markconversationreadlogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:92-95}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void markConversationRead(MarkConversationReadReq request,
                                     StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domain.markConversationRead(request), responseObserver);
    }

    /**
     * 通知列表 —— B 侧 {@code listnotificationslogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:97-100}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void listNotifications(NotificationListReq request,
                                  StreamObserver<NotificationListResp> responseObserver) {
        invoke(() -> domain.listNotifications(request), responseObserver);
    }

    /**
     * 标记单条通知已读 —— B 侧 {@code marknotificationreadlogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:102-105}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void markNotificationRead(MarkNotificationReq request,
                                     StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domain.markNotificationRead(request), responseObserver);
    }

    /**
     * 标记全部通知已读 —— B 侧 {@code markallnotificationsreadlogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:107-110}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void markAllNotificationsRead(MarkAllNotificationsReq request,
                                         StreamObserver<BaseResp> responseObserver) {
        invoke(() -> domain.markAllNotificationsRead(request), responseObserver);
    }

    /**
     * 未读汇总 —— B 侧 {@code getunreadsummarylogic}。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:112-115}。
     *
     * @param request          请求（proto）
     * @param responseObserver 响应观察者
     */
    @Override
    public void getUnreadSummary(UnreadSummaryReq request,
                                 StreamObserver<UnreadSummaryResp> responseObserver) {
        invoke(() -> domain.getUnreadSummary(request), responseObserver);
    }

    /**
     * 对应 B 侧 follow-rpc / user-rpc 的 invoke 模板：把领域服务调用包成 try/catch
     * 转发给 {@link StreamObserver}。
     *
     * <p>关键：领域服务已经把业务异常用
     * {@code MessageRpcSupport#grpcRuntimeError} 包成 {@link StatusRuntimeException}，
     * 这里直接 catch 并 {@code responseObserver.onError(...)}；
     * 其它 {@link RuntimeException} 兜底为 <b>UNKNOWN</b>（2026-10-24 由 INTERNAL 改来，见下方长注释）。
     *
     * <p><b>B 出处</b>：{@code .../service/impl/MessageRpcServiceImpl.java:124-168}（逐字照抄）。
     *
     * @param handler 领域服务调用
     * @param observer 响应观察者
     * @param <Resp>   响应类型
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (StatusRuntimeException sre) {
            // 领域服务已经把业务错误包成 gRPC status error，直接转发
            observer.onError(sre);
        } catch (RuntimeException e) {
            log.error("message-rpc handler failed", e);
            // ⚠️⚠️ **兜底码必须是 UNKNOWN，不是 INTERNAL**（DRIFT-MSG-V-024，2026-10-24 修）
            //
            // 【Go 行为（消费点，不是声明点）】
            //   ① message-rpc 的业务代码在 DB / 未知异常路径上**原样返回普通 error**
            //      （service/message/rpc/internal/logic/**：**共 19 处** ObserveDBError 之后
            //       `return nil, err`，不包 status）⇒ grpc-go v1.81.1 的
            //       `processUnaryRPC` 走 `status.FromContextError(appErr)` ⇒
            //       **codes.Unknown + 原始错误文本**。
            //   ② go-zero 的熔断判据 `codes.Acceptable(err)` 只对
            //      {DEADLINE_EXCEEDED, INTERNAL, UNAVAILABLE, DATA_LOSS, UNIMPLEMENTED,
            //       RESOURCE_EXHAUSTED} 这 6 个码判「失败」
            //      （zrpc/internal/codes/accept.go:9-17）
            //      ⇒ **UNKNOWN 不在名单里 ⇒ Go 的 DB 故障不触发熔断**。
            //
            // 【改之前的 RAG 行为】本方法把 RuntimeException 统一转成
            //   `Status.INTERNAL + "internal server error"` ⇒
            //   ① 客户端看到的 status 码与 Go 不同（INTERNAL vs UNKNOWN）；
            //   ② **INTERNAL 在那 6 码名单里 ⇒ RAG 的熔断器会把 DB 故障记成失败**
            //      ⇒ 同一个 DB 故障，两侧的开路决策分岔（RAG 灵敏得多）。
            //
            // 【为什么 description 留空（null），而不是抄 Go 的原始错误文本】
            //   Go 的 description 是**原始错误文本**（如 `pq: relation "x" does not exist`），
            //   而 api 侧的 `shared.RPCError` 会把 description **原文**塞进 HTTP envelope 的 msg
            //   ⇒ Go 侧客户端能直接看到 SQL 错误文本（**信息泄露**）。
            //   本仓 `DRIFT-MSG-V-003` 已就这一点**签字保留 RAG 侧**：
            //   envelope 只回码表文案，不回显原始错误文本。
            //   ⇒ 这里若把原始文本抄过来，等于在**签字之外**又引入一次泄露。
            //   ⇒ description 留空 ⇒ api 侧 `bizCodeFromError` 走
            //      「反查未命中 ⇒ 按 status code class 兜底」这一支
            //      （Go 侧对非 status 错误走的是**同一支**：`status.FromError` 返回 ok=false
            //        ⇒ 直接 `CodeServerBusy`）⇒ **业务码 1015 与 Go 一致**，
            //      且 envelope 的 msg 由码表给出（"service busy"），**不泄露原始文本**。
            observer.onError(new StatusRuntimeException(Status.UNKNOWN.withCause(e)));
        }
    }
}
