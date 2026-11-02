package com.rag.message.domain.service;

import com.rag.message.domain.entity.ConversationEntity;
import com.rag.message.domain.entity.ConversationMessageEntity;
import com.rag.message.domain.entity.NotificationEntity;
import com.rag.message.mapper.ConversationMapper;
import com.rag.message.mapper.ConversationMessageMapper;
import com.rag.message.mapper.NotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>职责</b>：message 域<b>多步写的事务边界</b> —— 6 个 {@code @Transactional} 方法。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/MessageAtomicService.java}
 * （B 侧 = RAG 仓，唯一行为判定基准 · D16）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.service} → {@code domain.service}（D7 包压平）；
 *       其余（类名、方法集合、事务注解、分批常量、调用顺序）<b>逐条未动</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 事务边界、写入顺序、批量切片大小、
 *       「先 UPDATE 会话再 INSERT 消息」等次序语义<b>全部保留</b>。</li>
 *   <li><b>B 出处</b>：同上；本类每个方法都能在 B 侧同名文件里逐字找到。</li>
 * </ol>
 *
 * <p><b>🔴 为什么必须是独立 Bean（不是 DomainService 的私有方法）</b>：
 * Spring 的 {@code @Transactional} 靠 <b>AOP 代理</b>生效，代理<b>只拦截从容器外部进来的调用</b>；
 * 同一类内部用 {@code this.} 自调用<b>不经代理</b> ⇒ {@code @Transactional} <b>静默失效</b>
 * （代码看着有事务、运行时是 auto-commit、<b>不报错</b>）。
 * <br>⚠️ B 侧正是踩过这个坑：6 个事务方法原本挂在 {@code MessageDomainService} 上并由同类
 * {@code doXxx(...)} 通过 {@code this.} 调用 ⇒ <b>6 处事务全部退化成 auto-commit</b>
 * （B 侧台账 {@code DRIFT-MSG-001} · 2026-10-22 修）⇒ 本类在 B 侧已被独立成 Bean。
 * <br>⇒ C 侧保持独立 Bean，{@code MessageDomainService} 必须<b>注入</b>调用，禁止 {@code this.} 自调用。
 *
 * <p><b>断言层要求</b>：必须有「事务真的生效」的守门测试（真 PostgreSQL），
 * 不能只看 {@code @Transactional} 注解存在（AGENTS.md §3.3.3「Bean 存在 ≠ 机制生效」）。
 */
@Service
public class MessageAtomicService {

    /**
     * 批量插入的切片大小。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code INSERT_BATCH_SIZE}）。
     *
     * <p>⚠️ <b>为什么必须分批，不能一条 foreach 插到底</b>（B 侧 {@code DRIFT-MSG-V-002} · 2026-10-22 修）：
     * <pre>
     * 修复前：@Insert("&lt;foreach ...&gt;") 一条多行 INSERT，12 列/行，一次绑全部
     *         recipients × 12 个参数
     * 现在  ：按 INSERT_BATCH_SIZE 切片，逐片调 insertBatch，全部仍在同一事务内
     * </pre>
     * Postgres 协议对<b>单条 SQL 的绑定参数个数</b>有 65535 的硬上限
     * ⇒ {@code floor(65535/12) = 5461}。
     * <b>recipients ≥ 5462 时整条 SQL 直接失败、事务回滚、一条通知都发不出去。</b>
     * 而广播模式下 {@code collectBroadcastRecipients} 会收集<b>全量用户</b>，
     * 这是一个必然被撞上的规模悬崖（Go 侧分批 200 则无此上限）。
     *
     * <p><b>原子性没有被这次修复削弱</b>：切片全部发生在这一个 {@code @Transactional}
     * 方法内部，任一片失败仍会回滚全部 —— 与 Go 的
     * {@code CreateInBatches(items, 200)} 语义一致。
     */
    private static final int INSERT_BATCH_SIZE = 200;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final NotificationMapper notificationMapper;

    public MessageAtomicService(ConversationMapper conversationMapper,
                                ConversationMessageMapper messageMapper,
                                NotificationMapper notificationMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.notificationMapper = notificationMapper;
    }

    // ==================================================================
    // 1. createConversationWithMessageTx
    // ==================================================================

    /**
     * 新建会话 + 首条消息，<b>一个事务</b>。
     *
     * <p><b>Go 出处逐字</b>（{@code internal/model/message.go:196-231} 同款形态）：
     * <pre>
     * func (m *MessageModel) CreateConversationWithMessageTx(ctx, conversation, message) error {
     *     return m.conn.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
     *         if err := tx.Create(conversation).Error; err != nil { return err }
     *         message.ConversationID = conversation.ID     // ← 事务内写回
     *         return tx.Create(message).Error
     *     })
     * }
     * </pre>
     *
     * <p><b>DRIFT-MSG-002 的修复点就在下面那一行 {@code msg.setConversationId}</b>：
     * 修复前 {@code doSendChatMessage} 构造 {@code messageItem} 时设了 id / senderId /
     * senderRole / recipientId / recipientRole / content / read / createdAt，
     * <b>唯独漏了 conversationId</b>；而实体里它是 primitive {@code long}（默认 0），
     * {@code insertMsg} 直接绑 {@code #{conversationId}} ⇒ <b>所有消息都写成
     * conversation_id = 0</b> ⇒ 查不出历史消息、unread_count 恒 0、标记已读更新 0 行。
     * 全仓 5 处 {@code setConversationId} 命中<b>全是 protobuf builder</b>，掩盖了这个缺口。
     *
     * <p>把赋值放在事务内而不是构造点，是为了与 Go 逐行对齐：Go 也是在闭包里写回的。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code createConversationWithMessageTx}）。
     */
    @Transactional
    public void createConversationWithMessageTx(ConversationEntity conv,
                                                ConversationMessageEntity msg) {
        conversationMapper.insertConv(conv);
        msg.setConversationId(conv.getId());
        messageMapper.insertMsg(msg);
    }

    // ==================================================================
    // 2. createOrSendMessageTx
    // ==================================================================

    /**
     * 已有会话内 append 一条消息 + 更新会话 last_message_* / status / pending_sender_*，<b>一个事务</b>。
     *
     * <p><b>Go 出处逐字</b>（{@code internal/model/message.go:196-231}）：
     * <pre>
     * tx.Model(&amp;Conversation{}).Where("id = ?", conversation.ID).Updates(map[string]any{
     *     "status": statusAfterSend, "pending_sender_role": …, "pending_sender_id": …,
     *     "last_message_id": message.ID, "last_message_preview": preview,
     *     "last_message_at": now, "updated_at": now,
     * })                                          // ← 先 UPDATE 会话
     * message.ConversationID = conversation.ID      // ← 事务内写回
     * return tx.Create(message).Error               // ← 再 INSERT 消息
     * </pre>
     *
     * <p>7 个更新字段与 Go 的 map 键逐条一致。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code createOrSendMessageTx}）。
     */
    @Transactional
    public void createOrSendMessageTx(ConversationEntity conv,
                                      ConversationMessageEntity msg,
                                      int newStatus, int pendingSenderRole, long pendingSenderId,
                                      String lastMessagePreview) {
        conversationMapper.updateLastMessage(
                conv.getId(), msg.getId(), lastMessagePreview, msg.getCreatedAt(),
                newStatus, pendingSenderRole, pendingSenderId);
        msg.setConversationId(conv.getId());
        messageMapper.insertMsg(msg);
    }

    // ==================================================================
    // 3. insertNotificationsTx
    // ==================================================================

    /**
     * 批量插入通知行，<b>一个事务</b>。
     *
     * <p><b>Go 出处</b>：{@code CreateNotifications}（{@code internal/model/message.go:22-27}）：
     * <pre>
     * func (m *MessageModel) CreateNotifications(ctx context.Context, items []*Notification) error {
     *     if len(items) == 0 { return nil }
     *     return m.conn.WithContext(ctx).CreateInBatches(items, 200).Error
     * }
     * </pre>
     * GORM 的 {@code CreateInBatches} 自带事务，所以 Go 侧虽没显式 {@code Transaction}，
     * 批量写同样是原子的（要么全成功要么全回滚）。
     *
     * <p>⚠️ <b>分批的必要性</b>见类级 {@link #INSERT_BATCH_SIZE} 的说明
     * （PG 单条 SQL 绑定参数 65535 上限 ⇒ 一条多行 INSERT 会在 recipients ≥ 5462 时整条失败）。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code insertNotificationsTx}）。
     */
    @Transactional
    public void insertNotificationsTx(List<NotificationEntity> batch) {
        // Go: CreateInBatches(items, 200) 对空切片是 no-op，这里同样直接返回。
        for (int from = 0; from < batch.size(); from += INSERT_BATCH_SIZE) {
            int to = Math.min(from + INSERT_BATCH_SIZE, batch.size());
            notificationMapper.insertBatch(batch.subList(from, to));
        }
    }

    // ==================================================================
    // 4. markConversationReadTx
    // ==================================================================

    /**
     * 标记某会话内「以我为 recipient」的未读消息为已读，一个事务。
     *
     * <p><b>Go 出处</b>：{@code MarkConversationRead}（{@code internal/model/message.go:176-184}）：
     * <pre>
     * return conn.Model(&amp;ConversationMessage{}).
     *     Where("conversation_id = ? AND recipient_role = ? AND recipient_id = ? AND is_read = ?",
     *           conversationID, role, id, false).
     *     Updates(map[string]any{"is_read": true, "read_at": &amp;now}).Error
     * </pre>
     * <b>单条 UPDATE，不触碰 conversation 表。</b>
     *
     * <p><b>⚠️ DRIFT-MSG-V-006（2026-10-22 修）：删掉了多出来的那次写</b>
     * <pre>
     * 修复前：本方法除消息表外还调 conversationMapper.touchUpdatedAt(convId, readAt)
     *        —— 即<b>一个「标记已读」的写操作会顺带改写会话的 updated_at</b>
     * </pre>
     * 删除理由<b>不是</b>「它看起来多余」，而是它<b>当初存在的理由已经不存在了</b>：
     * 修复前它被写成「Java 侧排序缺 updated_at 次级键（DRIFT-MSG-V-005），此处先补写入」——
     * 而 DRIFT-MSG-V-005 已在本轮修好（{@code listByParticipant} 补回
     * {@code orderByDesc(updatedAt)}），补偿对象消失了，补偿本身就成了纯副作用。
     *
     * <p><b>被删掉的可观察影响</b>（这正是要删它的原因）：
     * <ol>
     *   <li>{@code ConversationItem.updated_at} 两端取值不同；</li>
     *   <li>Go 的 {@code ListConversations} 次级排序键 {@code updated_at DESC}
     *       会被「读操作」改动 —— 两端的<b>读副作用集不同</b>。</li>
     * </ol>
     *
     * <p>⚠️ 顺带清掉编号撞车：本文件此前把这条标为「DRIFT-MSG-006」，
     * 而 AGENTS.md §6.6 的 DRIFT-MSG-006 是「缺 spring-boot-flyway」——两个不同缺陷共用一个 ID；
     * 同一段注释还引用了<b>本文件里不存在的</b> {@code DRIFT-MSG-008}。
     * 权威台账是 AGENTS.md §0 + §6.6 + §6.7，不是代码注释。见 §6.7.0。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code markConversationReadTx}）。
     */
    @Transactional
    public void markConversationReadTx(long conversationId, long recipientId, int recipientRole,
                                       OffsetDateTime readAt) {
        messageMapper.markReadByConversationAndRecipient(
                conversationId, recipientId, recipientRole, readAt);
    }

    // ==================================================================
    // 5. markNotificationReadTx
    // ==================================================================

    /**
     * 标记单条通知为已读，<b>单条 UPDATE</b>，归属条件直接进 WHERE。
     *
     * <p><b>Go 出处</b>：{@code MarkNotificationRead}（{@code internal/model/message.go:72-84}）：
     * <pre>
     * res := conn.Model(&amp;Notification{}).
     *      Where("id = ? AND recipient_id = ?", notificationID, recipientID).
     *      Updates(map[string]any{"is_read": true, "read_at": &amp;now})
     * if res.RowsAffected == 0 { return ErrRecordNotFound }
     * </pre>
     *
     * @return 受影响行数；0 = 不存在或不归属该 recipient，由上层映射成 NOT_FOUND(5203)。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code markNotificationReadTx}）。
     */
    @Transactional
    public int markNotificationReadTx(long id, long recipientId, OffsetDateTime readAt) {
        return notificationMapper.markRead(id, recipientId, readAt);
    }

    // ==================================================================
    // 6. markAllNotificationsReadTx
    // ==================================================================

    /**
     * 标记某 recipient 的全部未读通知为已读。
     *
     * <p><b>Go 出处</b>：{@code MarkAllNotificationsRead}
     * （{@code internal/model/message.go:86-91}）：
     * {@code Where("recipient_id = ? AND is_read = ?", recipientID, false)}。
     *
     * <p>{@code senderRole} / {@code kind} 传 0 = 不按这两个维度过滤（Go 侧本就没有该维度）。
     *
     * <p><b>B 出处</b>：{@code .../rpc/service/MessageAtomicService.java}（{@code markAllNotificationsReadTx}）。
     */
    @Transactional
    public void markAllNotificationsReadTx(long recipientId, int senderRole, int kind,
                                           OffsetDateTime readAt) {
        notificationMapper.markAllReadByRecipient(recipientId, senderRole, kind, readAt);
    }
}
