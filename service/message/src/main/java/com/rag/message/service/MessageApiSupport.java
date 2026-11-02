package com.rag.message.service;

import com.rag.common.exception.BusinessException;
import com.rag.message.contract.ErrCodes;
import com.rag.message.contract.ErrMsg;
import com.rag.message.contract.GrpcErrorMapper;
import com.rag.message.dto.chat.SendChatResp;
import com.rag.message.dto.conversation.ChatMessageItem;
import com.rag.message.dto.conversation.ConversationItem;
import com.rag.message.dto.conversation.ConversationListResp;
import com.rag.message.dto.conversation.ConversationMessagesResp;
import com.rag.message.dto.notification.AdminSendNotificationResp;
import com.rag.message.dto.notification.NotificationItem;
import com.rag.message.dto.notification.NotificationListResp;
import com.rag.message.dto.read.UnreadSummaryResp;
import com.rag.message.interceptor.AbstractMessageJwtAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * <p><b>职责</b>：message 域 <b>HTTP 层的共享行为</b> —— 身份解析、错误映射、proto → DTO 投影。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/service/MessageApiSupport.java}
 * （B 侧 = RAG 仓，唯一行为判定基准 · D16）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>纯机械</b>改写 ——
 *       ① 包名 {@code api.service} → {@code service}（D7 包压平）；
 *       ② proto 包名 → {@code com.rag.proto.message}（D7）；
 *       ③ 错误契约引用 → {@link ErrCodes}/{@link ErrMsg}/{@link GrpcErrorMapper}（D7）；
 *       ④ <b>DTO 由可变 POJO 改 immutable record</b>（D5 + A9）⇒ 6 个投影方法从
 *       「new + setter 逐字段赋值」改为「<b>record 规范构造器</b>一次成形」，
 *       <b>字段集合与赋值顺序逐条照抄 B 侧</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 身份判定的三条规则、错误码的取法与回退、
 *       每个 DTO 字段的取值口径<b>逐条未动</b> ⇒ <b>行为零差异</b>。</li>
 *   <li><b>B 出处</b>：同上；本类每个方法都能在 B 侧同名文件里逐字找到。</li>
 * </ol>
 *
 * <p><b>B 侧 helper.go → 本类的逐条对应</b>：
 * <table border="1">
 *   <caption>helper.go → 本类</caption>
 *   <tr><th>Go</th><th>Java</th></tr>
 *   <tr><td>{@code UserIDFromContext}</td><td>{@link #userId}</td></tr>
 *   <tr><td>{@code RPCError}</td><td>{@link #rpcError}</td></tr>
 *   <tr><td>{@code ToNotificationListResp}</td><td>{@link #toNotificationListResp}</td></tr>
 *   <tr><td>{@code ToUnreadSummaryResp}</td><td>{@link #toUnreadSummaryResp}</td></tr>
 *   <tr><td>{@code ToConversationListResp}</td><td>{@link #toConversationListResp}</td></tr>
 *   <tr><td>{@code ToConversationMessagesResp}</td><td>{@link #toConversationMessagesResp}</td></tr>
 *   <tr><td>{@code ToSendChatResp}</td><td>{@link #toSendChatResp}</td></tr>
 *   <tr><td>{@code AdminNotificationKind}</td><td>{@link #adminNotificationKind}</td></tr>
 *   <tr><td>{@code InvalidContextError}</td><td><b>不迁</b>：Go 侧全树 grep 零调用方</td></tr>
 * </table>
 *
 * <p><b>关于 {@code InvalidContextError}</b>：Go 端定义了
 * {@code fmt.Errorf("%s not found in context", label)}，但
 * {@code api/internal/logic/**} 13 个 logic 里<b>没有任何一处调用</b>（grep 零命中）。
 * 按「零消费者的代码不能算已对齐」的既有处置（users 域 DEAD-CODE-001 先例），
 * 本次<b>不创建</b>对应物 —— 建一个没人调用的方法等于给后人第二个入口。
 */
@Component
public class MessageApiSupport {

    /**
     * 对应 Go {@code shared.UserIDFromContext}：
     * <pre>
     * func UserIDFromContext(ctx context.Context) (int64, error) {
     *     userID, ok := ctx.Value("userId").(json.Number)
     *     if !ok { return 0, NewErrCode(ErrorUnauthorized) }
     *     uid, err := userID.Int64()
     *     if err != nil { return 0, NewErrCode(ErrorUnauthorized) }
     *     return uid, nil
     * }
     * </pre>
     *
     * <h3>三条必须逐条复刻的规则</h3>
     * <ol>
     *   <li><b>claim 不存在 ⇒ 5004</b>（不是 401）。鉴权层已经验过签名，
     *       能走到这里说明 token 有效；Go 把它归成「业务上的未鉴权」，
     *       输出 <b>HTTP 200 + {code:5004}</b>。若改成 401 就与 Go 不同。</li>
     *   <li><b>claim 不是数字 ⇒ 5004</b>：Go 的类型断言目标是 {@code json.Number}，
     *       字符串 claim（{@code "userId":"abc"}）直接落 {@code !ok} 分支。</li>
     *   <li><b>数字不是整数 ⇒ 5004</b>：Go 的 {@code json.Number.Int64()}
     *       内部是 {@code strconv.ParseInt(s,10,64)}，对 {@code "1.5"} / {@code "1e2"}
     *       一律失败。这里因此要求 <b>整型</b>（Byte/Short/Integer/Long/BigInteger），
     *       {@code Double}/{@code BigDecimal} 一律拒 —— 否则 RAG 会把 Go 判 5004 的输入
     *       静默截断成合法 uid，那是实打实的越权风险。</li>
     * </ol>
     *
     * <p>注意本方法<b>不</b>拒绝 {@code uid <= 0}：Go 侧同样不拒绝，
     * {@code uid=0} 会原样透传给 message-rpc，由 rpc 的存在性校验判 5101/5102。
     * api 层提前拒会把业务码换成 5004。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:85-119}。
     *
     * @param request 当前 HTTP 请求（拦截器已把 claim 放进 request attribute）
     * @return 用户 uid
     * @throws BusinessException claim 缺失 / 非整型 ⇒ 5004（{@code ERROR_UNAUTHORIZED}）
     */
    public long userId(HttpServletRequest request) {
        Object claim = request.getAttribute(AbstractMessageJwtAuthInterceptor.CLAIM_ATTRIBUTE);
        if (claim instanceof Byte b) {
            return b.longValue();
        }
        if (claim instanceof Short s) {
            return s.longValue();
        }
        if (claim instanceof Integer i) {
            return i.longValue();
        }
        if (claim instanceof Long l) {
            return l;
        }
        if (claim instanceof java.math.BigInteger bi && bi.bitLength() < 64) {
            return bi.longValue();
        }
        throw new BusinessException(
                ErrCodes.ERROR_UNAUTHORIZED.getCode(),
                ErrMsg.getMsg(ErrCodes.ERROR_UNAUTHORIZED.getCode()));
    }

    /**
     * 对应 Go {@code shared.RPCError}：
     * <pre>
     * func RPCError(err error) error {
     *     if err == nil { return nil }
     *     code := messagecommon.BizCodeFromError(err)
     *     if st, ok := status.FromError(err); ok &amp;&amp; st.Message() != "" {
     *         return messagecommon.NewErrCodeMsg(code, st.Message())
     *     }
     *     return messagecommon.NewErrCode(code)
     * }
     * </pre>
     *
     * <p><b>两条语义要分开看</b>：
     * <ul>
     *   <li><b>code</b> 由 {@link GrpcErrorMapper#bizCodeFromError} 决定 ——
     *       先按 gRPC status 的 description 反查业务码表，查不到再按 gRPC code class 兜底。</li>
     *   <li><b>msg</b> 优先用 gRPC 的 description <b>原文</b>；只有在拿不到非空 description 时
     *       才退回按 code 查表。Go 侧 message-rpc 把业务码藏在 description 里
     *       （{@code GRPCError(codes.X, bizCode)} → {@code status.Error(X, GetErrMsg(bizCode))}），
     *       所以「有非空 description」是常态，msg 就是那张表里的英文文案。</li>
     * </ul>
     *
     * <p>Go 的 {@code status.FromError} 对非 status 错误返回 {@code ok=false}，
     * 此时走 {@code NewErrCode(code)}（msg 查表）。本方法用
     * {@link #statusDescription} 返回 {@code null} 来表达同一件事。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:150-171}。
     *
     * @param err 领域层抛出的异常（gRPC status 异常或裸 RuntimeException）
     * @return 业务异常（HTTP 层由 advice 渲染成 {@code {code,msg,data}} 信封）
     */
    public BusinessException rpcError(Throwable err) {
        int code = GrpcErrorMapper.bizCodeFromError(err);
        String description = statusDescription(err);
        if (description != null && !description.isEmpty()) {
            return new BusinessException(code, description);
        }
        return new BusinessException(code, ErrMsg.getMsg(code));
    }

    /**
     * 取 gRPC status 的 description；不是 status 异常时返回 {@code null}。
     *
     * <p>对应 Go {@code status.FromError(err)} 的 {@code ok} 判定与 {@code st.Message()}。
     *
     * <p>🔴 <b>2026-10-23：这里原先是自己写的 {@code instanceof} 双分支，已删除。</b>
     * 原因：Go 侧的 {@code status.FromError}（grpc-go v1.81.1）里有 {@code errors.As} ——
     * <b>会沿 cause 链解包</b>；本方法只认「最外层那个异常本身是不是 status」，
     * 于是 {@code CompletionException}/{@code @Async} 包一层的场景，
     * {@link #rpcError} 拿到的是 {@code 1015} 而 Go 拿到的是按 code class 映射的码。
     * ⇒ 现在统一委派给 {@link GrpcErrorMapper#statusMessage(Throwable)}，
     * <b>全仓只保留一份实现</b>（两份实现迟早分叉，而分叉的症状是「码对、msg 错」，
     * 端到端测试几乎看不出来）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:184-186}。
     */
    private static String statusDescription(Throwable err) {
        return GrpcErrorMapper.statusMessage(err);
    }

    /**
     * 对应 Go {@code shared.AdminNotificationKind}：
     * <pre>
     * func AdminNotificationKind(broadcast bool, raw int32) pb.NotificationKind {
     *     if raw &gt; 0 { return pb.NotificationKind(raw) }
     *     if broadcast { return pb.NotificationKind_ADMIN_BROADCAST }
     *     return pb.NotificationKind_ADMIN_NOTICE
     * }
     * </pre>
     *
     * <p><b>三条规则</b>：{@code raw > 0} 原值优先（客户端可指定 ARTICLE_COMMENT 等）；
     * 否则 broadcast ⇒ {@code ADMIN_BROADCAST}(1)，非广播 ⇒ {@code ADMIN_NOTICE}(2)。
     * {@code raw < 0} 与 {@code raw == 0} 一样落到推断分支（Go 的判断是 {@code raw > 0}，
     * 不是 {@code raw != 0}）。
     *
     * <p><b>返回原始 int 而不是 proto 枚举对象</b>：{@code raw > 0} 可能是
     * protobuf 尚未定义的值（如 99），Java 的 {@code NotificationKind.forNumber(99)}
     * 会返回 {@code null}，而 Go 的 proto3 开放枚举允许未知值。
     * 因此 Service 侧必须用 {@code setKindValue(int)} 而不是 {@code setKind(enum)}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:196-207}。
     *
     * @param broadcast 是否广播
     * @param raw       客户端显式指定的 kind（原始数值）
     * @return 生效的 kind 数值
     */
    public int adminNotificationKind(boolean broadcast, int raw) {
        if (raw > 0) {
            return raw;
        }
        if (broadcast) {
            return com.rag.proto.message.NotificationKind.ADMIN_BROADCAST.getNumber();
        }
        return com.rag.proto.message.NotificationKind.ADMIN_NOTICE.getNumber();
    }

    /**
     * 对应 Go 侧的 recipient id 组装（非广播单播 / 广播全量）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:209-216}。
     *
     * @param broadcast 是否广播（广播时返回空列表，由 rpc 侧拉全量用户）
     * @param targetId  单播目标
     * @return recipient id 列表
     */
    public List<Long> recipientIds(boolean broadcast, Long targetId) {
        if (broadcast) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(1);
        ids.add(targetId == null ? 0L : targetId);
        return ids;
    }

    // ============== proto → DTO 映射（对应 shared/helper.go 的 5 个 To*Resp） ==============

    /**
     * 对应 Go {@code shared.ToNotificationListResp}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:221-243}。
     */
    public NotificationListResp toNotificationListResp(
            com.rag.proto.message.NotificationListResp in) {
        List<NotificationItem> items = new ArrayList<>(in.getItemsCount());
        for (com.rag.proto.message.NotificationItem item : in.getItemsList()) {
            items.add(new NotificationItem(
                    item.getId(),
                    item.getRecipientId(),
                    item.getSenderId(),
                    // Go: int32(item.SenderRole) —— 取原始数值，不是枚举名
                    item.getSenderRoleValue(),
                    item.getKindValue(),
                    item.getTitle(),
                    item.getContent(),
                    item.getIsRead(),
                    item.getCreatedAt(),
                    // Go: Extra: item.Extra —— nil map 会序列化成 "extra":null，这里同样不排除 null
                    item.getExtraCount() == 0 ? null : new LinkedHashMap<>(item.getExtraMap())));
        }
        return new NotificationListResp(items, in.getTotal(), in.getUnreadCount());
    }

    /**
     * 对应 Go {@code shared.ToUnreadSummaryResp}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:246-253}。
     */
    public UnreadSummaryResp toUnreadSummaryResp(
            com.rag.proto.message.UnreadSummaryResp in) {
        return new UnreadSummaryResp(
                in.getNotificationUnread(),
                in.getConversationUnread(),
                in.getTotalUnread());
    }

    /**
     * 对应 Go {@code shared.ToConversationListResp}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:256-277}。
     */
    public ConversationListResp toConversationListResp(
            com.rag.proto.message.ConversationListResp in) {
        List<ConversationItem> items = new ArrayList<>(in.getItemsCount());
        for (com.rag.proto.message.ConversationItem item : in.getItemsList()) {
            items.add(new ConversationItem(
                    item.getConversationId(),
                    item.getPeerId(),
                    item.getPeerRoleValue(),
                    item.getPeerName(),
                    item.getPeerEmail(),
                    item.getStatusValue(),
                    item.getUnreadCount(),
                    item.getLatestMessage(),
                    item.getLatestMessageAt(),
                    item.getUpdatedAt(),
                    item.getCanSend()));
        }
        return new ConversationListResp(items, in.getTotal(), in.getUnreadCount());
    }

    /**
     * 对应 Go {@code shared.ToConversationMessagesResp}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:280-291}。
     */
    public ConversationMessagesResp toConversationMessagesResp(
            com.rag.proto.message.ConversationMessageListResp in) {
        List<ChatMessageItem> items = new ArrayList<>(in.getItemsCount());
        for (com.rag.proto.message.ChatMessageItem item : in.getItemsList()) {
            items.add(toChatMessageItem(item));
        }
        return new ConversationMessagesResp(
                items,
                in.getStatusValue(),
                in.getCanSend(),
                in.getPendingSenderId(),
                in.getPendingSenderRoleValue());
    }

    /**
     * 对应 Go {@code shared.ToSendChatResp}。
     *
     * <p><b>{@code message} 不会被置 null</b>：Go 的写法是
     * <pre>
     *   message := types.ChatMessageItem{}
     *   if in.Message != nil { message = ... }
     * </pre>
     * 即 proto 消息缺失时用<b>全零值</b>占位。Java 侧 DTO 的 {@code message}
     * 字段已默认 new，一个 proto 的 {@code hasMessage()} 为 false 时保留零值对象。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:304-313}。
     */
    public SendChatResp toSendChatResp(com.rag.proto.message.SendChatMessageResp in) {
        ChatMessageItem message = in.hasMessage()
                ? toChatMessageItem(in.getMessage())
                : ChatMessageItem.zero();
        return new SendChatResp(
                in.getConversationId(),
                message,
                in.getStatusValue(),
                in.getCanSend());
    }

    /**
     * proto ChatMessageItem → DTO（两个 To*Resp 共用，Go 侧也是同一段字段拷贝）。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:316-329}。
     */
    private ChatMessageItem toChatMessageItem(com.rag.proto.message.ChatMessageItem in) {
        return new ChatMessageItem(
                in.getId(),
                in.getConversationId(),
                in.getSenderId(),
                in.getSenderRoleValue(),
                in.getRecipientId(),
                in.getRecipientRoleValue(),
                in.getContent(),
                in.getIsRead(),
                in.getCreatedAt());
    }

    /**
     * 「proto 消息缺席」时的<b>全零值占位</b>（对应 Go 的 {@code types.ChatMessageItem{}}）。
     *
     * <p>⚠️ <b>为什么不能传 {@code null}</b>：B 侧的 DTO 字段有默认 {@code new ChatMessageItem()}
     * ⇒ 序列化出来是一组零值，而不是 {@code null} ⇒ 换成 record 后必须<b>显式构造零值对象</b>，
     * 否则 JSON 形态从「零值对象」变成 {@code null} ⇒ wire 漂移。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:296-303}（Go 侧写法）。
     */
    private static ChatMessageItem zeroChatMessageItem() {
        return new ChatMessageItem(0L, 0L, 0L, 0, 0L, 0, "", false, 0L);
    }

    /**
     * 对应 Go {@code return &types.AdminSendNotificationResp{Affected: rpcResp.Affected}, nil}。
     *
     * <p><b>B 出处</b>：{@code .../api/service/MessageApiSupport.java:332-337}。
     */
    public AdminSendNotificationResp toAdminSendNotificationResp(
            com.rag.proto.message.SendNotificationResp in) {
        return new AdminSendNotificationResp(in.getAffected());
    }
}
