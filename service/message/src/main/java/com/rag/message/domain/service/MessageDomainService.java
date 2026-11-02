package com.rag.message.domain.service;

import com.rag.common.snowflake.Snowflake;
import com.rag.message.client.AdminRpcClient;
import com.rag.message.client.UserRpcClient;
import com.rag.message.config.MessageProperties;
import com.rag.message.contract.ErrCodes;
import com.rag.message.contract.ErrMsg;
import com.rag.message.domain.entity.ConversationEntity;
import com.rag.message.domain.entity.ConversationMessageEntity;
import com.rag.message.domain.entity.NotificationEntity;
import com.rag.message.mapper.ConversationMapper;
import com.rag.message.mapper.ConversationMessageMapper;
import com.rag.message.mapper.NotificationMapper;
import com.rag.message.metrics.MessageRpcMetrics;
import com.rag.message.service.MessageRpcSupport;
import com.rag.proto.message.BaseResp;
import com.rag.proto.message.ChatMessageItem;
import com.rag.proto.message.ConversationItem;
import com.rag.proto.message.ConversationListReq;
import com.rag.proto.message.ConversationListResp;
import com.rag.proto.message.ConversationMessageListReq;
import com.rag.proto.message.ConversationMessageListResp;
import com.rag.proto.message.ConversationStatus;
import com.rag.proto.message.MarkAllNotificationsReq;
import com.rag.proto.message.MarkConversationReadReq;
import com.rag.proto.message.MarkNotificationReq;
import com.rag.proto.message.NotificationItem;
import com.rag.proto.message.NotificationListReq;
import com.rag.proto.message.NotificationListResp;
import com.rag.proto.message.SenderRole;
import com.rag.proto.message.SendChatMessageReq;
import com.rag.proto.message.SendChatMessageResp;
import com.rag.proto.message.SendNotificationReq;
import com.rag.proto.message.SendNotificationResp;
import com.rag.proto.message.UnreadSummaryReq;
import com.rag.proto.message.UnreadSummaryResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <p><b>职责</b>：message 域 <b>9 个领域用例</b> —— HTTP 与 gRPC <b>共享同一份</b>业务规则。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageDomainService.java}
 * （B 侧 = RAG 仓，唯一行为判定基准 · D16）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>纯机械</b>改写 ——
 *       ① 包名 {@code rpc.service} → {@code domain.service}（D7 包压平）；
 *       ② proto 包名 {@code com.rag.message.message.proto} → {@code com.rag.proto.message}（D7）；
 *       ③ 错误契约引用 {@code MessageErrorCode}/{@code MessageErrorMessages} →
 *       {@link ErrCodes}/{@link ErrMsg}（D7）；
 *       ④ <b>删掉两个未被使用的注入</b>：B 侧构造器里的 {@code LoggerFactoryBean logger} 与
 *       {@code Tracer tracer}（B 侧只赋值、<b>方法体里一次都没用</b> ⇒ 零可观察行为）
 *       ⇒ 保留它们只会让本类多两条无用依赖。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 9 个用例的<b>校验次序</b>、<b>短路点</b>、
 *       <b>埋点 action/type</b>、<b>事务调用</b>、<b>状态机</b>逐条未动 ⇒ <b>行为零差异</b>。</li>
 *   <li><b>B 出处</b>：同上；本类每个方法都能在 B 侧同名文件里逐字找到。</li>
 * </ol>
 *
 * <p><b>为什么本类是「领域层」而不是「rpc 层」</b>（D1 + D2）：
 * B 侧 api 与 rpc 是<b>两个进程</b>，api 经 gRPC 调 rpc；C 侧合并成<b>一个进程</b>
 * ⇒ api 层<b>直连</b>调用本类（{@code service/MessageApiService.java}），
 * gRPC 入口也调本类（{@code grpc/MessageGrpcService.java}）⇒ <b>HTTP 与 gRPC 共用同一份业务用例</b>。
 *
 * <p><b>⚠️ 事务方法不在本类</b>：6 个多步写的事务边界在
 * {@link MessageAtomicService}（<b>独立 Bean</b>）⇒ 必须<b>注入调用</b>，
 * 禁止 {@code this.} 自调用（自调用会让 {@code @Transactional} 静默失效）。
 *
 * <p><b>9 个用例与 B 侧 Go logic 的对应</b>（B 出处：{@code .../rpc/internal/logic/}）：
 * <pre>
 * sendchatmessagelogic           → sendChatMessage()
 * sendnotificationlogic          → sendNotification()
 * listconversationslogic         → listConversations()
 * getconversationmessageslogic   → getConversationMessages()
 * markconversationreadlogic      → markConversationRead()
 * listnotificationslogic         → listNotifications()
 * marknotificationreadlogic      → markNotificationRead()
 * markallnotificationsreadlogic  → markAllNotificationsRead()
 * getunreadsummarylogic          → getUnreadSummary()
 * </pre>
 */
@Service
public class MessageDomainService {

    private final NotificationMapper notificationMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final UserRpcClient userRpcClient;
    private final AdminRpcClient adminRpcClient;
    private final MessageProperties properties;
    private final MessageRpcMetrics metrics;
    private final MessageAtomicService atomicService;

    public MessageDomainService(NotificationMapper notificationMapper,
                                ConversationMapper conversationMapper,
                                ConversationMessageMapper messageMapper,
                                UserRpcClient userRpcClient,
                                AdminRpcClient adminRpcClient,
                                MessageProperties properties,
                                MessageRpcMetrics metrics,
                                MessageAtomicService atomicService) {
        this.notificationMapper = notificationMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.userRpcClient = userRpcClient;
        this.adminRpcClient = adminRpcClient;
        this.properties = properties;
        this.metrics = metrics;
        // 2026-10-22 修 DRIFT-MSG-001：多步写事务必须经由**独立 Bean** 调用，
        // 否则同类 self-invocation 会让 @Transactional 静默失效。详见 MessageAtomicService 类注释。
        this.atomicService = atomicService;
    }

    // ============== 1. sendchatmessagelogic → sendChatMessage() ==============

    /**
     * 对应 Go 端 sendchatmessagelogic.go（业务最复杂）。
     *
     * <p>整体流程分支（与 Go 端 1:1）：
     * <ol>
     *   <li>{@code content trim + senderId>0 + validRole(senderRole)} → 否则 INVALID_ARGUMENT + ErrorInvalidParam</li>
     *   <li>若指定 {@code conversation_id>0}：
     *       <ul>
     *         <li>findConversationByID；不存在 → NOT_FOUND + ErrorConversationMiss</li>
     *         <li>isParticipant(sender) 检查；不是 → PERMISSION_DENIED + ErrorForbidden</li>
     *         <li>{@code peerOf(conv, sender)} → 取对端；找不到 → PERMISSION_DENIED + ErrorForbidden</li>
     *       </ul>
     *   </li>
     *   <li>否则（不指定 conversation_id）：
     *       <ul>
     *         <li>校验 recipient_id>0 && validRole(recipient_role) → 否则 INVALID_ARGUMENT + ErrorInvalidParam</li>
     *         <li>self-chat 检测（senderId==recipientId && senderRole==recipientRole）→ INVALID_ARGUMENT + ErrorSelfChat</li>
     *         <li>canonicalize 参与者 → 按规范化对找现有会话；不存在就准备新建</li>
     *       </ul>
     *   </li>
     *   <li>snowflake 分配 message_id；构造 ConversationMessage</li>
     *   <li>若 {@code conv==null}：
     *       <ul>
     *         <li>ADMIN→USER：直接 OPEN；要求 recipient_role == USER，否则 INVALID_ARGUMENT + ErrorUnsupportedRole</li>
     *         <li>USER→USER：PENDING；首条 ≤ 50 字符（runeLen）；
     *             非 ADMIN 角色创建 USER→非USER → PERMISSION_DENIED + ErrorAdminOnlyInit</li>
     *       </ul>
     *   </li>
     *   <li>否则（已有会话）：
     *       <ul>
     *         <li>处理 PENDING → OPEN 状态切换；如果当前 sender 就是 pending_sender，阻止（同方连发）
     *             → FAILED_PRECONDITION + ErrorChatPending</li>
     *         <li>UNSPECIFIED 状态当作 OPEN 处理</li>
     *       </ul>
     *   </li>
     *   <li>{@code createConversationWithMessageTx} 或 {@code createOrSendMessageTx} 提交事务</li>
     *   <li>返回 SendChatMessageResp，含 conversation_id / message / status / can_send</li>
     * </ol>
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:166-389}。
     */
    public SendChatMessageResp sendChatMessage(SendChatMessageReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doSendChatMessage(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("SendChatMessage", start, err);
                metrics.observeChat("send", err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private SendChatMessageResp doSendChatMessage(SendChatMessageReq in) {
        // ① validate
        String content = MessageRpcSupport.trimContent(in.getContent());
        if (in.getSenderId() <= 0L
                || content.isEmpty()
                || !MessageRpcSupport.validRole(in.getSenderRole())) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② 加载 conv + 推导 peer
        Optional<ConversationEntity> convOpt;
        SenderRole recipientRole;
        long recipientId;
        if (in.getConversationId() > 0L) {
            try {
                convOpt = conversationMapper.findByIdOpt(in.getConversationId());
            } catch (RuntimeException e) {
                metrics.observeDbError("chat", "find_conversation");
                throw e;
            }
            if (convOpt.isEmpty()) {
                throw new StatusRuntimeException(
                        Status.NOT_FOUND.withDescription(ErrMsg.getMsg(
                                ErrCodes.ERROR_CONVERSATION_MISS.getCode())));
            }
            ConversationEntity conv = convOpt.get();
            if (!MessageRpcSupport.isParticipant(conv, in.getSenderRole(), in.getSenderId())) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.PERMISSION_DENIED,
                        ErrCodes.ERROR_FORBIDDEN.getCode());
            }
            MessageRpcSupport.PeerResult peer = MessageRpcSupport.peerOf(conv,
                    in.getSenderRole(), in.getSenderId());
            if (!peer.found()) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.PERMISSION_DENIED,
                        ErrCodes.ERROR_FORBIDDEN.getCode());
            }
            recipientRole = peer.role();
            recipientId = peer.id();
        } else {
            recipientRole = in.getRecipientRole();
            recipientId = in.getRecipientId();
            if (recipientId <= 0L || !MessageRpcSupport.validRole(recipientRole)) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                        ErrCodes.ERROR_INVALID_PARAM.getCode());
            }
            if (recipientId == in.getSenderId() && recipientRole == in.getSenderRole()) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                        ErrCodes.ERROR_SELF_CHAT.getCode());
            }

            MessageRpcSupport.CanonicalPair canon = MessageRpcSupport.canonicalParticipants(
                    in.getSenderRole(), in.getSenderId(), recipientRole, recipientId);
            Optional<ConversationEntity> existing;
            try {
                existing = conversationMapper.findByPair(
                        canon.role1().getNumber(), canon.id1(),
                        canon.role2().getNumber(), canon.id2());
            } catch (RuntimeException e) {
                metrics.observeDbError("chat", "find_by_pair");
                throw e;
            }
            convOpt = existing;
        }

        // ③ 分配 message_id，构造 ConversationMessage
        long messageId = nextSnowflakeId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationMessageEntity messageItem = new ConversationMessageEntity();
        messageItem.setId(messageId);
        messageItem.setSenderId(in.getSenderId());
        messageItem.setSenderRole(in.getSenderRole().getNumber());
        messageItem.setRecipientId(recipientId);
        messageItem.setRecipientRole(recipientRole.getNumber());
        messageItem.setContent(content);
        messageItem.setRead(false);
        messageItem.setCreatedAt(now);

        // ④ conv == null 分支（新建会话）
        if (convOpt == null || convOpt.isEmpty()) {
            if (in.getSenderRole() == SenderRole.ADMIN) {
                if (recipientRole != SenderRole.USER) {
                    throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                            ErrCodes.ERROR_UNSUPPORTED_ROLE.getCode());
                }
                long conversationId = nextSnowflakeId();
                MessageRpcSupport.CanonicalPair canon = MessageRpcSupport.canonicalParticipants(
                        in.getSenderRole(), in.getSenderId(), recipientRole, recipientId);
                ConversationEntity conv = new ConversationEntity();
                conv.setId(conversationId);
                conv.setParticipant1Role(canon.role1().getNumber());
                conv.setParticipant1Id(canon.id1());
                conv.setParticipant2Role(canon.role2().getNumber());
                conv.setParticipant2Id(canon.id2());
                conv.setStatus(ConversationStatus.OPEN.getNumber());
                conv.setPendingSenderRole(SenderRole.SENDER_ROLE_UNSPECIFIED.getNumber());
                conv.setPendingSenderId(0L);
                conv.setCreatedByRole(in.getSenderRole().getNumber());
                conv.setCreatedById(in.getSenderId());
                conv.setLastMessageId(messageId);
                conv.setLastMessagePreview(MessageRpcSupport.trimPreview(content));
                conv.setLastMessageAt(now);
                conv.setCreatedAt(now);
                conv.setUpdatedAt(now);

                try {
                    atomicService.createConversationWithMessageTx(conv, messageItem);
                } catch (RuntimeException e) {
                    metrics.observeDbError("chat", "create_conversation");
                    throw e;
                }
                return SendChatMessageResp.newBuilder()
                        .setCode(ErrCodes.SUCCESS.getCode())
                        .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                        .setConversationId(conv.getId())
                        .setMessage(MessageRpcSupport.toMessageItem(messageItem))
                        .setStatus(ConversationStatus.OPEN)
                        .setCanSend(true)
                        .build();
            }

            if (in.getSenderRole() != SenderRole.USER || recipientRole != SenderRole.USER) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.PERMISSION_DENIED,
                        ErrCodes.ERROR_ADMIN_ONLY_INIT.getCode());
            }
            if (MessageRpcSupport.runeLen(content) > 50) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                        ErrCodes.ERROR_FIRST_MSG_TOO_LONG.getCode());
            }
            long conversationId = nextSnowflakeId();
            MessageRpcSupport.CanonicalPair canon = MessageRpcSupport.canonicalParticipants(
                    in.getSenderRole(), in.getSenderId(), recipientRole, recipientId);
            ConversationEntity conv = new ConversationEntity();
            conv.setId(conversationId);
            conv.setParticipant1Role(canon.role1().getNumber());
            conv.setParticipant1Id(canon.id1());
            conv.setParticipant2Role(canon.role2().getNumber());
            conv.setParticipant2Id(canon.id2());
            conv.setStatus(ConversationStatus.PENDING.getNumber());
            conv.setPendingSenderRole(in.getSenderRole().getNumber());
            conv.setPendingSenderId(in.getSenderId());
            conv.setCreatedByRole(in.getSenderRole().getNumber());
            conv.setCreatedById(in.getSenderId());
            conv.setLastMessageId(messageId);
            conv.setLastMessagePreview(MessageRpcSupport.trimPreview(content));
            conv.setLastMessageAt(now);
            conv.setCreatedAt(now);
            conv.setUpdatedAt(now);

            try {
                atomicService.createConversationWithMessageTx(conv, messageItem);
            } catch (RuntimeException e) {
                metrics.observeDbError("chat", "create_pending_conversation");
                throw e;
            }
            return SendChatMessageResp.newBuilder()
                    .setCode(ErrCodes.SUCCESS.getCode())
                    .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                    .setConversationId(conv.getId())
                    .setMessage(MessageRpcSupport.toMessageItem(messageItem))
                    .setStatus(ConversationStatus.PENDING)
                    .setCanSend(false)
                    .build();
        }

        // ⑤ conv 存在，append 一条新消息
        ConversationEntity conv = convOpt.get();
        ConversationStatus statusAfterSend = ConversationStatus.forNumber(conv.getStatus());
        SenderRole pendingSenderRole = SenderRole.forNumber(conv.getPendingSenderRole());
        long pendingSenderId = conv.getPendingSenderId();

        if (statusAfterSend == ConversationStatus.PENDING) {
            if (pendingSenderRole == in.getSenderRole()
                    && pendingSenderId == in.getSenderId()) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.FAILED_PRECONDITION,
                        ErrCodes.ERROR_CHAT_PENDING.getCode());
            }
            statusAfterSend = ConversationStatus.OPEN;
            pendingSenderRole = SenderRole.SENDER_ROLE_UNSPECIFIED;
            pendingSenderId = 0L;
        }
        if (statusAfterSend == ConversationStatus.CONVERSATION_STATUS_UNSPECIFIED) {
            statusAfterSend = ConversationStatus.OPEN;
        }

        try {
            atomicService.createOrSendMessageTx(conv, messageItem,
                    statusAfterSend.getNumber(),
                    pendingSenderRole.getNumber(),
                    pendingSenderId,
                    MessageRpcSupport.trimPreview(content));
        } catch (RuntimeException e) {
            metrics.observeDbError("chat", "append_message");
            throw e;
        }

        ChatMessageItem chatMsgItem = MessageRpcSupport.toMessageItem(messageItem);
        return SendChatMessageResp.newBuilder()
                .setCode(ErrCodes.SUCCESS.getCode())
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                .setConversationId(conv.getId())
                .setMessage(chatMsgItem)
                .setStatus(statusAfterSend)
                .setCanSend(statusAfterSend == ConversationStatus.OPEN)
                .build();
    }

    // ============== 2. sendnotificationlogic → sendNotification() ==============

    /**
     * 对应 Go 端 sendnotificationlogic.go：admin 域发系统通知 / 广播。
     *
     * <p>行为 1:1：
     * <ol>
     *   <li>{@code title / content trim} 后校验非空 → 空则 INVALID_ARGUMENT + ErrorInvalidParam</li>
     *   <li>{@code recipients = dedupeRecipients(in.RecipientIds)}</li>
     *   <li>若 {@code broadcast=true}：从 adminRpc 拉所有 user_id 覆盖之前的 recipients；
     *       RPC 失败 → INTERNAL + ErrorDbSelect（与 Go 端 metrics.ObserveDBError 类型对齐）</li>
     *   <li>recipients 为空 → INVALID_ARGUMENT + ErrorInvalidParam</li>
     *   <li>逐个用 {@link MessageRpcSupport#newNotificationRecord} 构造 Notification，ID 由 snowflake 分配</li>
     *   <li>{@code insertNotificationsTx} 批量插入；DB 失败 → ErrorDbUpdate</li>
     *   <li>返回 SendNotificationResp{code=200, msg="OK", affected=items.size()}</li>
     * </ol>
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:408-478}。
     */
    public SendNotificationResp sendNotification(SendNotificationReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        String kindLabel = MessageRpcSupport.notificationKindLabel(req.getKind());
        try {
            return doSendNotification(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("SendNotification", start, err);
                metrics.observeNotification(kindLabel, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private SendNotificationResp doSendNotification(SendNotificationReq in) {
        // ① validate
        String title = MessageRpcSupport.trimContent(in.getTitle());
        String content = MessageRpcSupport.trimContent(in.getContent());
        if (title.isEmpty() || content.isEmpty()) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② recipients
        List<Long> recipients;
        if (in.getBroadcast()) {
            try {
                recipients = adminRpcClient.collectBroadcastRecipients(
                        properties.getBroadcast().getPageSize());
            } catch (RuntimeException e) {
                metrics.observeDbError("notification", "broadcast_user_list");
                throw e;
            }
        } else {
            recipients = MessageRpcSupport.dedupeRecipients(in.getRecipientIdsList());
        }
        if (recipients.isEmpty()) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ③ 构造 records
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<NotificationEntity> items = new ArrayList<>(recipients.size());
        for (Long rid : recipients) {
            long snowflakeId = nextSnowflakeId();
            items.add(MessageRpcSupport.newNotificationRecord(
                    rid, in.getSenderId(), in.getSenderRole(),
                    in.getKind(), title, content, in.getExtraMap(),
                    now, snowflakeId));
        }

        // ④ 批量插入
        try {
            atomicService.insertNotificationsTx(items);
        } catch (RuntimeException e) {
            metrics.observeDbError("notification", "insert");
            throw e;
        }

        return SendNotificationResp.newBuilder()
                .setCode(ErrCodes.SUCCESS.getCode())
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                .setAffected(items.size())
                .build();
    }

    // ============== 3. listconversationslogic → listConversations() ==============

    /**
     * 对应 Go 端 listconversationslogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:485-611}。
     */
    public ConversationListResp listConversations(ConversationListReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doListConversations(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("ListConversations", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private ConversationListResp doListConversations(ConversationListReq in) {
        // ① validate
        if (in.getOperatorId() <= 0L || !MessageRpcSupport.validRole(in.getOperatorRole())) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② normalize limit
        int limit = MessageRpcSupport.normalizeLimit(
                in.getLimit(),
                properties.getList().getDefaultLimit(),
                properties.getList().getMaxLimit());

        // ③ query conversations
        List<ConversationEntity> items;
        long total;
        try {
            // 🔴 DRIFT-MSG-V-012（2026-10-22 修）：limit / offset 传反了。
            //    Mapper 签名是 listByParticipant(role, id, limit, offset)，
            //    而这里传的是 (…, in.getOffset(), limit) ⇒ 实参落到 (limit=offset, offset=limit)。
            //    结果 SQL 变成 "LIMIT <offset> OFFSET <limit>"，offset=0 时即 **LIMIT 0**
            //    ⇒ ListConversations **永远返回空列表**（会话列表页全空）。
            //    这与 DRIFT-MSG-002（conversation_id 恒 0）是同款形态：
            //    一个被写错的普通值，让整条功能路径静默失效、且不产生任何错误日志。
            items = conversationMapper.listByParticipant(
                    in.getOperatorRole().getNumber(), in.getOperatorId(),
                    limit, in.getOffset());
            total = conversationMapper.countByParticipant(
                    in.getOperatorRole().getNumber(), in.getOperatorId());
        } catch (RuntimeException e) {
            metrics.observeDbError("conversation", "list");
            throw e;
        }

        // ④ total unread
        long unreadTotal;
        try {
            unreadTotal = messageMapper.countUnreadByRecipientGlobal(
                    in.getOperatorId(), in.getOperatorRole().getNumber());
        } catch (RuntimeException e) {
            metrics.observeDbError("conversation", "count_unread");
            throw e;
        }

        // ⑤ build items
        // ⚠️⚠️ **DRIFT-MSG-V-018（2026-10-24 修）：顺序与埋点必须逐行照 Go 的 buildConversationItem**
        //    （Go 出处：service/message/rpc/internal/logic/helper.go:160-187）
        //
        //    Go 的顺序是 ① peerOf → ② count_unread → ③ loadProfile：
        //      helper.go:161-164  peerOf(...) !ok ⇒ status.Error(codes.PermissionDenied, GetErrMsg(ErrorForbidden))
        //                        ⇒ **直接 return，根本不会去查 count_unread**
        //      helper.go:166-170  CountUnreadMessagesByConversation(...) err != nil
        //                        ⇒ metrics.ObserveDBError("conversation", "count_unread")
        //                        ⇒ return nil, err（**原样返回裸 error**）
        //      helper.go:172      loadProfile(...)
        //
        //    【改之前 RAG 的两处偏差】
        //      a) **顺序反了**：先 count_unread 再 peerOf ⇒ peerOf 失败时**白查一次 DB**，
        //         而且那次查询的失败**不产生任何埋点**；
        //      b) **少了埋点**：count_unread 抛异常时**没有** observeDbError("conversation","count_unread")
        //         ⇒ `message_rpc_db_error_total{action="conversation",type="count_unread"}` 在这条路径上**永不增长**
        //         ⇒ 运维看板会误以为「从没发生过 count_unread 失败」。
        //
        //    【改后的可观察结果】
        //      · peerOf 失败 ⇒ 仍然 PERMISSION_DENIED + 5005（码不变，**只是不再白查一次 DB**）
        //      · count_unread 失败 ⇒ 埋点增长（Go 侧同样增长）⇒ 指标口径对齐
        //      · 异常原样上抛 ⇒ rpc 层按 V-024 的新口径转成 UNKNOWN + 裸 error 语义，与 Go 一致
        List<ConversationItem> itemDtos = new ArrayList<>(items.size());
        for (var conv : items) {
            // ① peerOf —— Go: helper.go:161-164（**先判**，失败即短路）
            MessageRpcSupport.PeerResult peer = MessageRpcSupport.peerOf(conv,
                    in.getOperatorRole(), in.getOperatorId());
            if (!peer.found()) {
                throw MessageRpcSupport.grpcRuntimeError(Status.Code.PERMISSION_DENIED,
                        ErrCodes.ERROR_FORBIDDEN.getCode());
            }

            // ② count_unread —— Go: helper.go:166-170（err ⇒ 埋点 ⇒ 原样上抛）
            long unread;
            try {
                unread = conversationMapper.countUnreadMessagesByConversation(
                        conv.getId(), in.getOperatorId(), in.getOperatorRole().getNumber());
            } catch (RuntimeException e) {
                metrics.observeDbError("conversation", "count_unread");
                throw e;
            }

            // ③ loadProfile —— Go: helper.go:172
            String[] profile;
            if (peer.role() == SenderRole.USER) {
                profile = userRpcClient.loadProfile(peer.role(), peer.id());
            } else {
                profile = adminRpcClient.loadProfile(peer.role(), peer.id());
            }
            // ⚠️ buildConversationItem 内部**还会再算一次 peerOf**（Go 那边是在同一个函数里算一次的）。
            //    这里是纯函数、幂等、无副作用 ⇒ 不影响结果；
            //    保留它是为了「即使调用方忘了先判 peerOf，helper 自己也守得住」——防回退用。
            itemDtos.add(MessageRpcSupport.buildConversationItem(conv,
                    in.getOperatorRole(), in.getOperatorId(),
                    unread, profile[0], profile[1]));
        }

        return ConversationListResp.newBuilder()
                .setCode(ErrCodes.SUCCESS.getCode())
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                .addAllItems(itemDtos)
                .setTotal(total)
                .setUnreadCount(unreadTotal)
                .build();
    }

    // ============== 4. getconversationmessageslogic → getConversationMessages() ==============

    /**
     * 对应 Go 端 getconversationmessageslogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:618-696}。
     */
    public ConversationMessageListResp getConversationMessages(ConversationMessageListReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doGetConversationMessages(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("GetConversationMessages", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private ConversationMessageListResp doGetConversationMessages(ConversationMessageListReq in) {
        // ① validate
        if (in.getOperatorId() <= 0L
                || in.getConversationId() <= 0L
                || !MessageRpcSupport.validRole(in.getOperatorRole())) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② find conversation
        Optional<ConversationEntity> convOpt;
        try {
            convOpt = conversationMapper.findByIdOpt(in.getConversationId());
        } catch (RuntimeException e) {
            metrics.observeDbError("conversation", "find");
            throw e;
        }
        if (convOpt.isEmpty()) {
            throw new StatusRuntimeException(
                    Status.NOT_FOUND.withDescription(
                            ErrMsg.getMsg(ErrCodes.ERROR_CONVERSATION_MISS.getCode())));
        }
        ConversationEntity conv = convOpt.get();

        // ③ permission
        if (!MessageRpcSupport.isParticipant(conv, in.getOperatorRole(), in.getOperatorId())) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.PERMISSION_DENIED,
                    ErrCodes.ERROR_FORBIDDEN.getCode());
        }

        // ④ normalize limit
        int limit = MessageRpcSupport.normalizeLimit(
                in.getLimit(),
                properties.getList().getDefaultLimit(),
                properties.getList().getMaxLimit());

        // ⑤ list messages
        List<ConversationMessageEntity> messages;
        try {
            messages = messageMapper.listByConversationPage(
                    in.getConversationId(), in.getOffset(), limit);
        } catch (RuntimeException e) {
            metrics.observeDbError("conversation", "list_messages");
            throw e;
        }

        List<ChatMessageItem> itemDtos = new ArrayList<>(messages.size());
        for (var m : messages) {
            itemDtos.add(MessageRpcSupport.toMessageItem(m));
        }

        return ConversationMessageListResp.newBuilder()
                .setCode(ErrCodes.SUCCESS.getCode())
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                .addAllItems(itemDtos)
                .setStatus(ConversationStatus.forNumber(conv.getStatus()))
                .setCanSend(MessageRpcSupport.canSend(conv, in.getOperatorRole(), in.getOperatorId()))
                .setPendingSenderId(conv.getPendingSenderId())
                .setPendingSenderRole(SenderRole.forNumber(conv.getPendingSenderRole()))
                .build();
    }

    // ============== 5. markconversationreadlogic → markConversationRead() ==============

    /**
     * 对应 Go 端 markconversationreadlogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:703-790}。
     */
    public BaseResp markConversationRead(MarkConversationReadReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doMarkConversationRead(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("MarkConversationRead", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private BaseResp doMarkConversationRead(MarkConversationReadReq in) {
        // ① validate
        if (in.getOperatorId() <= 0L
                || in.getConversationId() <= 0L
                || !MessageRpcSupport.validRole(in.getOperatorRole())) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② find conversation
        Optional<ConversationEntity> convOpt;
        try {
            convOpt = conversationMapper.findByIdOpt(in.getConversationId());
        } catch (RuntimeException e) {
            metrics.observeDbError("conversation", "find");
            throw e;
        }
        if (convOpt.isEmpty()) {
            throw new StatusRuntimeException(
                    Status.NOT_FOUND.withDescription(
                            ErrMsg.getMsg(ErrCodes.ERROR_CONVERSATION_MISS.getCode())));
        }
        ConversationEntity conv = convOpt.get();

        // ③ permission
        if (!MessageRpcSupport.isParticipant(conv, in.getOperatorRole(), in.getOperatorId())) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.PERMISSION_DENIED,
                    ErrCodes.ERROR_FORBIDDEN.getCode());
        }

        // ④ mark read
        // ⚠️⚠️ DB 失败必须**原样上抛**，不得改写成 INTERNAL + 5008（2026-10-28 阶段 1 修 · E59）
        //
        // 【Go 出处（消费点，不是声明点）】markconversationreadlogic.go:59-62
        //     if err = ...MarkConversationRead(...); err != nil {
        //         metrics.ObserveDBError("conversation", "mark_read")
        //         return nil, err              ← 裸 error，不包 status
        //     }
        // ⇒ grpc-go v1.81.1 的 processUnaryRPC 走 status.FromContextError(appErr)
        //   ⇒ codes.Unknown（+ 原始错误文本）。
        //
        // 【为什么不能改成 INTERNAL】go-zero v1.10.2 的熔断判据
        //   zrpc/internal/codes/accept.go:9-17 与本仓共享层
        //   GoStyleGrpcClientBreakerInterceptor.isAcceptable（:340-348）**完全同一份名单**：
        //     {DEADLINE_EXCEEDED, INTERNAL, UNAVAILABLE, DATA_LOSS, UNIMPLEMENTED, RESOURCE_EXHAUSTED}
        //     判「失败」；其余（含 UNKNOWN）判「成功」。
        //   ⇒ 同一个 DB 故障：Go 记「成功」（不开路），RAG 若给 INTERNAL 则记「失败」（开路）
        //   ⇒ **两侧开路决策分岔**。
        //
        // 【Go 的 5008 是「声明但从不用」】Go service/message/common/errmsg.go:39 定义了
        //   ErrorDbUpdate = 5008，但**全仓没有任何 logic 路径引用它**
        //   （5007 ErrorDbSelect 同款）⇒ Go 的 DB 失败从来不是 5008
        //   ⇒ 本仓这 3 处自创的 5008 用法属于**表达层漂移**，本轮删除。
        //
        // 【本方法内的自洽性】同一个方法的「② find conversation」路径（:731-736）
        //   早就是 `throw e;`（裸上抛）⇒ 修后两条 DB 路径语义一致。
        //
        // 【上抛后由谁接管】MessageGrpcService 的兜底 catch (RuntimeException e)
        //   ⇒ Status.UNKNOWN.withCause(e)（DRIFT-MSG-V-024，2026-10-24 已修）
        //   ⇒ 客户端看到 UNKNOWN、api 侧业务码 1015（Go 的 CodeServerBusy）⇒ 与 Go 一致。
        try {
            atomicService.markConversationReadTx(in.getConversationId(), in.getOperatorId(),
                    in.getOperatorRole().getNumber(), OffsetDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            metrics.observeDbError("conversation", "mark_read");
            throw e;
        }

        return MessageRpcSupport.successBaseResp();
    }

    // ============== 6. listnotificationslogic → listNotifications() ==============

    /**
     * 对应 Go 端 listnotificationslogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:797-863}。
     */
    public NotificationListResp listNotifications(NotificationListReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doListNotifications(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("ListNotifications", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private NotificationListResp doListNotifications(NotificationListReq in) {
        // ① validate
        if (in.getUserId() <= 0L) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② normalize limit
        int limit = MessageRpcSupport.normalizeLimit(
                in.getLimit(),
                properties.getList().getDefaultLimit(),
                properties.getList().getMaxLimit());

        // ③ query
        // ⚠️ DRIFT-MSG-V-001（2026-10-22 修）：total 必须与 items 共用同一个 unreadOnly 过滤。
        //    Go（message.go:40-48）先给 query 加 is_read=false 再 query.Count()，
        //    所以 unread_only=true 时 total 只数未读。修复前这里是
        //    selectCount(QueryWrapper.eq("recipient_id", uid)) —— 无 is_read，
        //    与 listByRecipient 的过滤不一致 ⇒ total 恒为全部条数 ⇒ 前端分页多翻空页。
        //    见 §6.7.2 与 §6.7.5 纪律 A「分页接口的 total 与 items 必须成对断言」。
        List<NotificationEntity> items;
        long total;
        long unread;
        try {
            items = notificationMapper.listByRecipient(
                    in.getUserId(), 0, 0, in.getUnreadOnly(),
                    limit, in.getOffset());
            total = notificationMapper.countByRecipient(
                    in.getUserId(), 0, 0, in.getUnreadOnly());
            unread = notificationMapper.countUnreadByRecipient(
                    in.getUserId(), 0, 0);
        } catch (RuntimeException e) {
            metrics.observeDbError("notification", "list");
            throw e;
        }

        // ④ DTO convert
        List<NotificationItem> itemDtos = new ArrayList<>(items.size());
        for (var n : items) {
            itemDtos.add(MessageRpcSupport.toNotificationItem(n));
        }

        return NotificationListResp.newBuilder()
                .setCode(ErrCodes.SUCCESS.getCode())
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                .addAllItems(itemDtos)
                .setTotal(total)
                .setUnreadCount(unread)
                .build();
    }

    // ============== 7. marknotificationreadlogic → markNotificationRead() ==============

    /**
     * 对应 Go 端 marknotificationreadlogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:870-923}。
     */
    public BaseResp markNotificationRead(MarkNotificationReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doMarkNotificationRead(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("MarkNotificationRead", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private BaseResp doMarkNotificationRead(MarkNotificationReq in) {
        // ① validate（Go 出处 marknotificationreadlogic.go:40-43，逐条一致）
        if (in.getUserId() <= 0L || in.getNotificationId() <= 0L) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ②③ 归属校验 + mark read 合并为<b>单条</b> UPDATE（Go 出处 message.go:72-86）：
        //     Where("id = ? AND recipient_id = ?") + RowsAffected == 0 → ErrRecordNotFound
        // 修复前这里是 selectById 校验 + updateById 写入两条语句（TOCTOU 竞态），
        // 且 updateById 生成的 WHERE 只有 id、不含 recipient_id。
        int affected;
        // ⚠️⚠️ DB 失败必须**原样上抛**，不得改写成 INTERNAL + 5008（2026-10-28 阶段 1 修 · E59）
        // 【Go 出处】marknotificationreadlogic.go:50-51
        //     metrics.ObserveDBError("notification", "mark_read"); return nil, err   ← 裸 error
        // 【为什么不能改成 INTERNAL】见本文件 doMarkConversationRead 的同名注释：
        //   INTERNAL 落在 Go codes.Acceptable（accept.go:9-17 = 本仓共享层 isAcceptable 同名单）
        //   的 6 码**失败**名单里，而 Go 的 UNKNOWN 不在 ⇒ 两侧开路决策会分岔。
        // 【Go 的 5008 是「声明但从不用」】见本文件 doMarkConversationRead 的同名注释。
        try {
            affected = atomicService.markNotificationReadTx(in.getNotificationId(), in.getUserId(),
                    OffsetDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            metrics.observeDbError("notification", "mark_read");
            throw e;
        }

        // Go: if res.RowsAffected == 0 { return ErrRecordNotFound } → logic 映射成
        //     codes.NotFound + ErrorNotificationMiss（marknotificationreadlogic.go:47-51）
        if (affected == 0) {
            throw new StatusRuntimeException(
                    Status.NOT_FOUND.withDescription(
                            ErrMsg.getMsg(ErrCodes.ERROR_NOTIFICATION_MISS.getCode())));
        }

        return MessageRpcSupport.successBaseResp();
    }

    // ============== 8. markallnotificationsreadlogic → markAllNotificationsRead() ==============

    /**
     * 对应 Go 端 markallnotificationsreadlogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:930-965}。
     */
    public BaseResp markAllNotificationsRead(MarkAllNotificationsReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doMarkAllNotificationsRead(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("MarkAllNotificationsRead", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private BaseResp doMarkAllNotificationsRead(MarkAllNotificationsReq in) {
        if (in.getUserId() <= 0L) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ⚠️⚠️ DB 失败必须**原样上抛**，不得改写成 INTERNAL + 5008（2026-10-28 阶段 1 修 · E59）
        // 【Go 出处】markallnotificationsreadlogic.go:42-44
        //     metrics.ObserveDBError("notification", "mark_all_read"); return nil, err   ← 裸 error
        // 【为什么不能改成 INTERNAL】见本文件 doMarkConversationRead 的同名注释。
        try {
            atomicService.markAllNotificationsReadTx(in.getUserId(), 0, 0,
                    OffsetDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            metrics.observeDbError("notification", "mark_all_read");
            throw e;
        }

        return MessageRpcSupport.successBaseResp();
    }

    // ============== 9. getunreadsummarylogic → getUnreadSummary() ==============

    /**
     * 对应 Go 端 getunreadsummarylogic.go。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:972-1022}。
     */
    public UnreadSummaryResp getUnreadSummary(UnreadSummaryReq req) {
        long start = System.nanoTime();
        Throwable err = null;
        try {
            return doGetUnreadSummary(req);
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            try {
                metrics.observeRpc("GetUnreadSummary", start, err);
            } catch (Throwable ignore) {
                // ignore
            }
        }
    }

    private UnreadSummaryResp doGetUnreadSummary(UnreadSummaryReq in) {
        // ① validate
        if (in.getUserId() <= 0L) {
            throw MessageRpcSupport.grpcRuntimeError(Status.Code.INVALID_ARGUMENT,
                    ErrCodes.ERROR_INVALID_PARAM.getCode());
        }

        // ② notification unread
        long notificationUnread;
        try {
            notificationUnread = notificationMapper.countUnreadByRecipient(in.getUserId(), 0, 0);
        } catch (RuntimeException e) {
            metrics.observeDbError("unread", "notification");
            throw e;
        }

        // ③ conversation unread
        long conversationUnread;
        try {
            conversationUnread = messageMapper.countUnreadByRecipientGlobal(
                    in.getUserId(), SenderRole.USER.getNumber());
        } catch (RuntimeException e) {
            metrics.observeDbError("unread", "conversation");
            throw e;
        }

        return UnreadSummaryResp.newBuilder()
                .setCode(ErrCodes.SUCCESS.getCode())
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS.getCode()))
                .setNotificationUnread(notificationUnread)
                .setConversationUnread(conversationUnread)
                .setTotalUnread(notificationUnread + conversationUnread)
                .build();
    }

    // ============== 辅助：Snowflake ID 生成 ==============

    /**
     * 雪花 ID —— 对应 Go 端 {@code snowflake.GetID()}。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageDomainService.java:1026-1028}
     * （B 侧同款：{@code com.rag.common.snowflake.Snowflake.nextId()}）。
     *
     * <p>⚠️ epoch / 位段是业务契约（AGENTS.md §4 共享层表）⇒ 复用共享层实现，**不另起炉灶**。
     */
    public long nextSnowflakeId() {
        return Snowflake.nextId();
    }
}
