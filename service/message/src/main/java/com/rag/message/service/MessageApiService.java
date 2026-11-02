package com.rag.message.service;

// ⚠️⚠️ **同名类型消歧（刻意为之，勿"顺手统一"）**：
//   `MarkConversationReadReq` / `MarkNotificationReq` 这两个名字在 **dto 层**与 **proto 层**各有一个：
//     · dto  = `com.rag.message.dto.read.MarkConversationReadReq` / `com.rag.message.dto.notification.MarkNotificationReq`
//              （HTTP 层契约 · immutable record · 本类的**方法入参**）
//     · proto = `com.rag.proto.message.MarkConversationReadReq` / `com.rag.proto.message.MarkNotificationReq`
//              （gRPC 层契约 · 本类的 **builder**）
//   Java 不允许 import 两个同名类型 ⇒ 本文件**只 import proto 版**（保持 builder 写法与其它 10 个
//   proto 入参一致），**dto 版在方法签名里写全限定名**。
//   ⇒ 判据：看到 `xxx.newBuilder()` ⇒ proto；看到方法签名里的参数类型 ⇒ dto。
//   ⚠️ 反例（曾真实发生）：两个都 import ⇒ `ambiguous import` ⇒ **整个模块编译不过**。
import com.rag.common.exception.BusinessException;
import com.rag.message.contract.ErrCodes;
import com.rag.message.contract.ErrMsg;
import com.rag.message.domain.service.MessageDomainService;
import com.rag.message.dto.chat.AdminSendChatReq;
import com.rag.message.dto.chat.SendChatResp;
import com.rag.message.dto.chat.UserSendChatReq;
import com.rag.message.dto.common.EmptyReq;
import com.rag.message.dto.common.PageReq;
import com.rag.message.dto.conversation.ConversationListResp;
import com.rag.message.dto.conversation.ConversationMessagesReq;
import com.rag.message.dto.conversation.ConversationMessagesResp;
import com.rag.message.dto.notification.AdminSendNotificationReq;
import com.rag.message.dto.notification.AdminSendNotificationResp;
import com.rag.message.dto.notification.NotificationListResp;
import com.rag.message.dto.read.UnreadSummaryResp;
import com.rag.message.metrics.MessageApiMetrics;
import com.rag.proto.message.ConversationListReq;
import com.rag.proto.message.ConversationMessageListReq;
import com.rag.proto.message.MarkAllNotificationsReq;
import com.rag.proto.message.MarkConversationReadReq;
import com.rag.proto.message.MarkNotificationReq;
import com.rag.proto.message.NotificationListReq;
import com.rag.proto.message.SenderRole;
import com.rag.proto.message.SendChatMessageReq;
import com.rag.proto.message.SendNotificationReq;
import com.rag.proto.message.UnreadSummaryReq;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * <p><b>职责</b>：message 域 <b>HTTP 层 13 个用例</b>的编排 —— 身份解析 → 硬编码 role →
 * 调领域层 → 投影 DTO → 埋点。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/service/} 下
 * <b>13 个 {@code *Service} 类 + {@code AbstractMessageApiService}</b>（共 14 个文件）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>14 个类合并为 1 个</b>（映射表 §1.2 决策锁「13 个 logic ⇒ 1 个 Service」）。
 *       另外三处<b>纯机械</b>改写：① 包名 {@code api.service} → {@code service}（D7）；
 *       ② proto 包名 → {@code com.rag.proto.message}（D7）；
 *       ③ <b>api→rpc 由 gRPC 往返改直连</b>（D2）：B 侧
 *       {@code AbstractMessageApiService} 经 {@code MessageRpcClient} 发 gRPC，
 *       C 侧<b>直接注入</b> {@link MessageDomainService} ⇒ 域内自我 gRPC 往返消失。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 13 个用例的<b>路由常量</b>、<b>reject reason</b>、
 *       <b>硬编码 role</b>（user 组恒 {@code SenderRole.USER}、admin 组恒 {@code SenderRole.ADMIN}）、
 *       <b>参数归一</b>、<b>埋点时序</b>（成功失败都记、失败记 reject）逐条未动
 *       ⇒ <b>行为零差异</b>（除 D2 明确去掉的那一次进程内序列化往返）。</li>
 *   <li><b>B 出处</b>：见每个方法上的行号指针。</li>
 * </ol>
 *
 * <p><b>为什么合并（而不是留 13 个类）</b>：B 侧 13 个 Service 是 goctl 的代码生成产物，
 * 每个用例一个类、只差一个 route 常量和一个 role 常量；保留 13 份会让「埋点时序」这种
 * 行为相关语义出现 13 个可漂移点（AGENTS.md §三 的 11 处回归正是这类重复的产物）。
 * <br>⚠️ <b>但业务规则密集的协作者不合并</b>：{@link MessageRpcSupport}（常量表 + 算法片段）、
 * {@code MessageAtomicService}（事务边界类）、{@code GoStylePagination}（分页子句口径）
 * <b>保持独立</b>（映射表 §1.3 决策锁）。
 *
 * <p><b>⚠️ role 是硬编码的，不是从 JWT 推导的</b>（L1 事实）：
 * user 组的 8 个端点恒以 {@code SenderRole.USER} 发起；admin 组的 5 个端点恒以
 * {@code SenderRole.ADMIN} 发起，且 admin 发消息的 {@code RecipientRole} 恒为 {@code USER}。
 *
 * <p><b>埋点时序（与 Go 逐条对齐）</b>：
 * <pre>
 * Go:
 *   started := time.Now()
 *   const route = "/message/v1/conversations/list"
 *   defer func() { metrics.ObserveRequest(route, started, err) }()   // 成功失败都记
 *   uid, err := shared.UserIDFromContext(l.ctx)
 *   if err != nil { metrics.ObserveReject(route, "user_id_missing"); return nil, err }
 *
 * Java（Template Method）:
 *   invoke(route, () -&gt; { uid = userId(request); metrics.observeReject(...); ... })
 *   finally 里 metrics.observeRequest(route, failed, elapsed)
 * </pre>
 *
 * <p><b>关键点</b>：{@code request_total} 的 {@code result} 标签取自
 * 「logic 是否以错误收场」，与 Go 判的是<b>同一个东西</b>（Go 判命名返回值 {@code err != nil}）。
 * 因此返回业务失败码也算 {@code fail}，成功才是 {@code success}。
 * {@code try/finally} 保证抛异常时也会记账 —— 与 Go 的 {@code defer} 等价。
 */
@Service
public class MessageApiService {

    /** Go: {@code ObserveReject(route, "user_id_missing")} —— 8 个 user 端点。 */
    static final String REASON_USER_ID_MISSING = "user_id_missing";
    /** Go: {@code ObserveReject(route, "admin_id_missing")} —— 5 个 admin 端点。 */
    static final String REASON_ADMIN_ID_MISSING = "admin_id_missing";
    /** Go: {@code ObserveReject(route, "target_missing")} —— 仅 SendAdminNotification。 */
    static final String REASON_TARGET_MISSING = "target_missing";

    // ---- 8 个 user 端点的 route 常量（B 侧各 case service 的 ROUTE 字段）----
    private static final String ROUTE_CONVERSATIONS_LIST = "/message/v1/conversations/list";
    private static final String ROUTE_CONVERSATIONS_MESSAGES = "/message/v1/conversations/messages";
    private static final String ROUTE_CONVERSATIONS_READ = "/message/v1/conversations/read";
    private static final String ROUTE_CONVERSATIONS_SEND = "/message/v1/conversations/send";
    private static final String ROUTE_NOTIFICATIONS_LIST = "/message/v1/notifications/list";
    private static final String ROUTE_NOTIFICATIONS_READ = "/message/v1/notifications/read";
    private static final String ROUTE_NOTIFICATIONS_READALL = "/message/v1/notifications/readall";
    private static final String ROUTE_UNREAD = "/message/v1/unread";

    // ---- 5 个 admin 端点的 route 常量 ----
    private static final String ROUTE_ADMIN_CONVERSATIONS_LIST = "/message/v1/admin/conversations/list";
    private static final String ROUTE_ADMIN_CONVERSATIONS_MESSAGES = "/message/v1/admin/conversations/messages";
    private static final String ROUTE_ADMIN_CONVERSATIONS_READ = "/message/v1/admin/conversations/read";
    private static final String ROUTE_ADMIN_CONVERSATIONS_SEND = "/message/v1/admin/conversations/send";
    private static final String ROUTE_ADMIN_NOTIFICATIONS_SEND = "/message/v1/admin/notifications/send";

    private final MessageDomainService domainService;
    private final MessageApiSupport support;
    private final MessageApiMetrics metrics;

    public MessageApiService(MessageDomainService domainService,
                             MessageApiSupport support,
                             MessageApiMetrics metrics) {
        this.domainService = domainService;
        this.support = support;
        this.metrics = metrics;
    }

    // ==================================================================
    // 模板方法 —— 埋点 + 身份解析（B 侧 AbstractMessageApiService 的公共骨架）
    // ==================================================================

    /**
     * 埋点 + 异常传播的模板方法 —— 对应 Go 每个 logic 头部/尾部的样板。
     *
     * <p>异常<b>不在</b>这里捕获：让 gRPC / 业务异常继续往上抛到
     * {@code MessageApiExceptionHandler}，那里负责把 code/msg 映射成 Go 的 envelope。
     * 这里只负责 finally 里的 {@code observeRequest}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/AbstractMessageApiService.java:159-172}。
     */
    private <T> T invoke(String route, Supplier<T> body) {
        long startedAt = System.nanoTime();
        boolean failed = true;
        try {
            T result = body.get();
            failed = false;
            return result;
        } finally {
            metrics.observeRequest(route, failed, System.nanoTime() - startedAt);
        }
    }

    /**
     * 取身份 + 失败时记 reject —— 对应 Go 13 个 logic 里<b>逐字相同</b>的这段：
     * <pre>
     *   uid, err := shared.UserIDFromContext(l.ctx)
     *   if err != nil {
     *       metrics.ObserveReject(route, "user_id_missing")   // admin 端点是 "admin_id_missing"
     *       return nil, err
     *   }
     * </pre>
     *
     * <p><b>顺序不可颠倒</b>：身份解析永远在参数校验之前。
     * Go 的 {@code SendAdminNotification} 也是先 {@code UserIDFromContext}
     * 再判 {@code target_id <= 0} —— 所以「无 token 的请求打 admin 端点」
     * 得到的是 5004（unauthorized）而不是 5003（invalid parameter）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/AbstractMessageApiService.java:187-195}。
     */
    private long requireIdentity(String route, String rejectReason, HttpServletRequest request) {
        try {
            return support.userId(request);
        } catch (BusinessException e) {
            metrics.observeReject(route, rejectReason);
            throw e;
        }
    }

    /**
     * 便捷取值：{@code SenderRole} 的原始数值。
     *
     * <p>Go 侧写的是枚举常量（{@code pb.SenderRole_USER}），Java protobuf 的枚举对象
     * 需要 {@code getNumber()} 才等价；用 {@code setOperatorRoleValue(int)} 还能顺带
     * 支持 Go 的 proto3 开放枚举（未知值不会被 Java 的 {@code forNumber} 变成 null）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/AbstractMessageApiService.java:200-203}。
     */
    private static int roleValue(SenderRole role) {
        return role.getNumber();
    }

    // ==================================================================
    // user 组 —— 8 个端点
    // ==================================================================

    /**
     * 会话列表（user）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageListConversationsService.java} +
     * {@code AbstractMessageApiService#listConversations}。
     */
    public ConversationListResp listConversations(PageReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_CONVERSATIONS_LIST, () -> {
            long uid = requireIdentity(ROUTE_CONVERSATIONS_LIST, REASON_USER_ID_MISSING, httpRequest);
            ConversationListReq.Builder builder = ConversationListReq.newBuilder()
                    .setOperatorId(uid)
                    .setOperatorRoleValue(roleValue(SenderRole.USER))
                    .setOffset(req.offset())
                    .setLimit(req.limit());
            try {
                return support.toConversationListResp(domainService.listConversations(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 会话消息列表（user）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageGetMessagesService.java} +
     * {@code AbstractMessageApiService#getConversationMessages}。
     */
    public ConversationMessagesResp getConversationMessages(ConversationMessagesReq req,
                                                             HttpServletRequest httpRequest) {
        return invoke(ROUTE_CONVERSATIONS_MESSAGES, () -> {
            long uid = requireIdentity(ROUTE_CONVERSATIONS_MESSAGES, REASON_USER_ID_MISSING, httpRequest);
            ConversationMessageListReq.Builder builder = ConversationMessageListReq.newBuilder()
                    .setOperatorId(uid)
                    .setOperatorRoleValue(roleValue(SenderRole.USER))
                    .setConversationId(req.conversationId())
                    .setOffset(req.offset())
                    .setLimit(req.limit());
            try {
                return support.toConversationMessagesResp(
                        domainService.getConversationMessages(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 标记会话已读（user）。
     *
     * <p><b>返回 {@code {}}</b>：Go 返回 {@code &types.EmptyReq{}, nil}，
     * 而不是 nil —— 见 {@code EmptyReq} 的类注释。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageMarkConversationReadService.java} +
     * {@code AbstractMessageApiService#markConversationRead}。
     */
    public EmptyReq markConversationRead(com.rag.message.dto.read.MarkConversationReadReq req,
                                         HttpServletRequest httpRequest) {
        return invoke(ROUTE_CONVERSATIONS_READ, () -> {
            long uid = requireIdentity(ROUTE_CONVERSATIONS_READ, REASON_USER_ID_MISSING, httpRequest);
            MarkConversationReadReq.Builder builder = MarkConversationReadReq.newBuilder()
                    .setOperatorId(uid)
                    .setOperatorRoleValue(roleValue(SenderRole.USER))
                    .setConversationId(req.conversationId());
            try {
                domainService.markConversationRead(builder.build());
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
            return new EmptyReq();
        });
    }

    /**
     * 用户发私信（user）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageSendUserChatService.java}。
     */
    public SendChatResp sendUserChatMessage(UserSendChatReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_CONVERSATIONS_SEND, () -> {
            long uid = requireIdentity(ROUTE_CONVERSATIONS_SEND, REASON_USER_ID_MISSING, httpRequest);
            SendChatMessageReq.Builder builder = SendChatMessageReq.newBuilder()
                    .setSenderId(uid)
                    .setSenderRoleValue(roleValue(SenderRole.USER))
                    .setRecipientRoleValue(roleValue(SenderRole.USER))
                    .setContent(req.content() == null ? "" : req.content());
            if (req.recipientId() != null) {
                builder.setRecipientId(req.recipientId());
            }
            if (req.conversationId() != null) {
                builder.setConversationId(req.conversationId());
            }
            try {
                return support.toSendChatResp(domainService.sendChatMessage(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 通知列表（user）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageListNotificationsService.java}。
     */
    public NotificationListResp listNotifications(PageReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_NOTIFICATIONS_LIST, () -> {
            long uid = requireIdentity(ROUTE_NOTIFICATIONS_LIST, REASON_USER_ID_MISSING, httpRequest);
            NotificationListReq.Builder builder = NotificationListReq.newBuilder()
                    .setUserId(uid)
                    .setOffset(req.offset())
                    .setLimit(req.limit())
                    .setUnreadOnly(req.unreadOnly());
            try {
                return support.toNotificationListResp(domainService.listNotifications(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 标记单条通知已读（user）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageMarkNotificationReadService.java}。
     */
    public EmptyReq markNotificationRead(com.rag.message.dto.notification.MarkNotificationReq req,
                                        HttpServletRequest httpRequest) {
        return invoke(ROUTE_NOTIFICATIONS_READ, () -> {
            long uid = requireIdentity(ROUTE_NOTIFICATIONS_READ, REASON_USER_ID_MISSING, httpRequest);
            MarkNotificationReq.Builder builder = MarkNotificationReq.newBuilder()
                    .setUserId(uid)
                    .setNotificationId(req.notificationId());
            try {
                domainService.markNotificationRead(builder.build());
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
            return new EmptyReq();
        });
    }

    /**
     * 标记全部通知已读（user）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageMarkAllNotificationsReadService.java}。
     */
    public EmptyReq markAllNotificationsRead(HttpServletRequest httpRequest) {
        return invoke(ROUTE_NOTIFICATIONS_READALL, () -> {
            long uid = requireIdentity(ROUTE_NOTIFICATIONS_READALL, REASON_USER_ID_MISSING, httpRequest);
            MarkAllNotificationsReq.Builder builder = MarkAllNotificationsReq.newBuilder()
                    .setUserId(uid);
            try {
                domainService.markAllNotificationsRead(builder.build());
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
            return new EmptyReq();
        });
    }

    /**
     * 未读汇总（user，唯一的 GET 端点）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageGetUnreadSummaryService.java}。
     */
    public UnreadSummaryResp getUnreadSummary(HttpServletRequest httpRequest) {
        return invoke(ROUTE_UNREAD, () -> {
            long uid = requireIdentity(ROUTE_UNREAD, REASON_USER_ID_MISSING, httpRequest);
            UnreadSummaryReq.Builder builder = UnreadSummaryReq.newBuilder()
                    .setUserId(uid);
            try {
                return support.toUnreadSummaryResp(domainService.getUnreadSummary(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    // ==================================================================
    // admin 组 —— 5 个端点
    // ==================================================================

    /**
     * 会话列表（admin）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageAdminListConversationsService.java}。
     */
    public ConversationListResp listAdminConversations(PageReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_ADMIN_CONVERSATIONS_LIST, () -> {
            long uid = requireIdentity(ROUTE_ADMIN_CONVERSATIONS_LIST, REASON_ADMIN_ID_MISSING, httpRequest);
            ConversationListReq.Builder builder = ConversationListReq.newBuilder()
                    .setOperatorId(uid)
                    .setOperatorRoleValue(roleValue(SenderRole.ADMIN))
                    .setOffset(req.offset())
                    .setLimit(req.limit());
            try {
                return support.toConversationListResp(domainService.listConversations(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 会话消息列表（admin）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageAdminGetMessagesService.java}。
     */
    public ConversationMessagesResp getAdminConversationMessages(ConversationMessagesReq req,
                                                                 HttpServletRequest httpRequest) {
        return invoke(ROUTE_ADMIN_CONVERSATIONS_MESSAGES, () -> {
            long uid = requireIdentity(ROUTE_ADMIN_CONVERSATIONS_MESSAGES, REASON_ADMIN_ID_MISSING, httpRequest);
            ConversationMessageListReq.Builder builder = ConversationMessageListReq.newBuilder()
                    .setOperatorId(uid)
                    .setOperatorRoleValue(roleValue(SenderRole.ADMIN))
                    .setConversationId(req.conversationId())
                    .setOffset(req.offset())
                    .setLimit(req.limit());
            try {
                return support.toConversationMessagesResp(
                        domainService.getConversationMessages(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 标记会话已读（admin）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageAdminMarkConversationReadService.java}。
     */
    public EmptyReq markAdminConversationRead(com.rag.message.dto.read.MarkConversationReadReq req,
                                              HttpServletRequest httpRequest) {
        return invoke(ROUTE_ADMIN_CONVERSATIONS_READ, () -> {
            long uid = requireIdentity(ROUTE_ADMIN_CONVERSATIONS_READ, REASON_ADMIN_ID_MISSING, httpRequest);
            MarkConversationReadReq.Builder builder = MarkConversationReadReq.newBuilder()
                    .setOperatorId(uid)
                    .setOperatorRoleValue(roleValue(SenderRole.ADMIN))
                    .setConversationId(req.conversationId());
            try {
                domainService.markConversationRead(builder.build());
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
            return new EmptyReq();
        });
    }

    /**
     * 管理员发私信（admin）。
     *
     * <p><b>⚠️ {@code RecipientRole} 恒为 {@code USER}</b>（L1 事实）——
     * admin 发消息的接收方永远是用户。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageAdminSendChatService.java}。
     */
    public SendChatResp sendAdminChatMessage(AdminSendChatReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_ADMIN_CONVERSATIONS_SEND, () -> {
            long uid = requireIdentity(ROUTE_ADMIN_CONVERSATIONS_SEND, REASON_ADMIN_ID_MISSING, httpRequest);
            SendChatMessageReq.Builder builder = SendChatMessageReq.newBuilder()
                    .setSenderId(uid)
                    .setSenderRoleValue(roleValue(SenderRole.ADMIN))
                    .setRecipientRoleValue(roleValue(SenderRole.USER))
                    .setRecipientId(req.recipientId())
                    .setContent(req.content() == null ? "" : req.content());
            if (req.conversationId() != null) {
                builder.setConversationId(req.conversationId());
            }
            try {
                return support.toSendChatResp(domainService.sendChatMessage(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }

    /**
     * 管理员发通知 / 广播（admin）。
     *
     * <p><b>⚠️ 校验顺序</b>：先解析身份，再判 {@code target_id} —— 与 Go 逐条一致
     * ⇒ 「无 token 的请求打 admin 端点」得到的是 5004（unauthorized）而不是 5003。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageAdminSendNotificationService.java}。
     */
    public AdminSendNotificationResp sendAdminNotification(AdminSendNotificationReq req,
                                                           HttpServletRequest httpRequest) {
        return invoke(ROUTE_ADMIN_NOTIFICATIONS_SEND, () -> {
            long uid = requireIdentity(ROUTE_ADMIN_NOTIFICATIONS_SEND, REASON_ADMIN_ID_MISSING, httpRequest);
            if (!req.broadcast() && (req.targetId() == null || req.targetId() <= 0)) {
                metrics.observeReject(ROUTE_ADMIN_NOTIFICATIONS_SEND, REASON_TARGET_MISSING);
                throw new BusinessException(
                        ErrCodes.ERROR_INVALID_PARAM.getCode(),
                        ErrMsg.getMsg(ErrCodes.ERROR_INVALID_PARAM.getCode()));
            }
            SendNotificationReq.Builder builder = SendNotificationReq.newBuilder()
                    .setBroadcast(req.broadcast())
                    .setSenderId(uid)
                    .setSenderRoleValue(roleValue(SenderRole.ADMIN))
                    .setKindValue(support.adminNotificationKind(req.broadcast(), req.kind()))
                    .setTitle(req.title() == null ? "" : req.title())
                    .setContent(req.content() == null ? "" : req.content())
                    .addAllRecipientIds(support.recipientIds(req.broadcast(), req.targetId()));
            if (req.extra() != null) {
                builder.putAllExtra(req.extra());
            }
            try {
                return support.toAdminSendNotificationResp(domainService.sendNotification(builder.build()));
            } catch (StatusRuntimeException e) {
                throw support.rpcError(e);
            }
        });
    }
}
