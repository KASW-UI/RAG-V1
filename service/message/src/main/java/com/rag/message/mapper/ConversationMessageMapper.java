package com.rag.message.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.message.domain.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>{@code message_conversation_message} 表 Mapper 接口</b>（MyBatis-Plus）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/mapper/ConversationMessageMapper.java}
 * （B 侧类 javadoc {@code :16-22} · {@code listByConversation :26-41} ·
 * {@code countUnreadByRecipient :43-54} · {@code markReadByConversationAndRecipient :56-72} ·
 * {@code markAllReadByRecipient :74-90} · {@code countUnreadByRecipientGlobal :92-102} ·
 * {@code listByConversationPage :104-141} · {@code insertMsg :143-167}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.mapper} →
 *       {@code com.rag.message.mapper}，D7 包压平）＋ 实体 import 换成
 *       {@code com.rag.message.domain.entity.ConversationMessageEntity}。</li>
 *   <li><b>语义还一样吗</b>：一样 —— <b>每一条 SQL、每一个参数绑定名、每一个 WHERE 条件、
 *       每一个 ORDER BY 键都逐字照抄</b>。
 *       ⚠️ 特别地：{@code #{read}}（不是 {@code #{isRead}}）与
 *       {@code ConversationMessageEntity::isRead} 的 lambda 写法<b>保持原样</b> ——
 *       前者走 OGNL 的 JavaBean 规则、后者的修法在<b>实体侧</b>（字段 {@code read}），
 *       两套解析规则必须分别满足（B 侧 {@code :143-157} 的机理说明照抄）。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>框架职责</b>：{@code @Mapper} ＋ {@code extends BaseMapper<ConversationMessageEntity>}。
 *
 * <p><b>idx_message_conversation_time</b>（conversation_id, created_at）覆盖
 * GetConversationMessages 的 ORDER BY created_at。
 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {

    /**
     * 对应 Go 端 GetConversationMessages：按 conversation_id 拉历史消息，
     * {@code beforeMessageId > 0} 时再加 "id &lt; ?" 条件做 cursor 分页。
     */
    default List<ConversationMessageEntity> listByConversation(long conversationId,
                                                              long beforeMessageId,
                                                              int limit) {
        LambdaQueryWrapper<ConversationMessageEntity> w = new LambdaQueryWrapper<>();
        w.eq(ConversationMessageEntity::getConversationId, conversationId);
        if (beforeMessageId > 0L) {
            w.lt(ConversationMessageEntity::getId, beforeMessageId);
        }
        w.orderByAsc(ConversationMessageEntity::getId);
        w.last("LIMIT " + limit);
        return selectList(w);
    }

    /**
     * 对应 Go 端 GetUnreadSummary 内的 "unread_messages count"：operator 作为 recipient
     * 的所有 conversation_message 的未读总数。
     */
    @Select("""
            SELECT COUNT(*) FROM message_conversation_message
             WHERE recipient_id   = #{recipientId}
               AND recipient_role = #{recipientRole}
               AND is_read        = FALSE
            """)
    long countUnreadByRecipient(@Param("recipientId") long recipientId,
                               @Param("recipientRole") int recipientRole);

    /**
     * 对应 Go 端 markConversationReadTx 内的 UPDATE：
     * UPDATE message_conversation_message SET is_read=true, read_at=?
     *  WHERE conversation_id = ? AND recipient_id = ? AND recipient_role = ? AND is_read = false
     */
    @Update("""
            UPDATE message_conversation_message
                SET is_read = TRUE, read_at = #{readAt}
              WHERE conversation_id = #{conversationId}
                AND recipient_id    = #{recipientId}
                AND recipient_role  = #{recipientRole}
                AND is_read         = FALSE
            """)
    int markReadByConversationAndRecipient(@Param("conversationId") long conversationId,
                                            @Param("recipientId") long recipientId,
                                            @Param("recipientRole") int recipientRole,
                                            @Param("readAt") OffsetDateTime readAt);

    /**
     * 对应 Go 端 markAllConversationsReadTx 内的 UPDATE：
     * UPDATE ... SET is_read=true, read_at=? WHERE recipient_id=? AND recipient_role=? AND is_read=false。
     */
    default int markAllReadByRecipient(long recipientId, int recipientRole, OffsetDateTime readAt) {
        LambdaUpdateWrapper<ConversationMessageEntity> w = new LambdaUpdateWrapper<>();
        w.eq(ConversationMessageEntity::getRecipientId, recipientId)
                .eq(ConversationMessageEntity::getRecipientRole, recipientRole)
                .eq(ConversationMessageEntity::isRead, false)
                // ⚠️ 形如 `E::isRead` 的方法引用保持原样 —— DRIFT-MSG-V-017 的修法在**实体侧**
                // （字段 isRead → read + @TableField("is_read")），不在这里改名。
                // 改名的诱惑很大，但那样就偏离了 NotificationEntity 的既有形态，
                // 而两份实体同款才是可读性来源。
                .set(ConversationMessageEntity::isRead, true)
                .set(ConversationMessageEntity::getReadAt, readAt);
        return update(null, w);
    }

    /**
     * 对应 Go 端 ListConversations 的 unreadTotal：operator 作为 recipient 的所有未读消息数。
     */
    @Select("""
            SELECT COUNT(*) FROM message_conversation_message
             WHERE recipient_id   = #{recipientId}
               AND recipient_role = #{recipientRole}
               AND is_read        = FALSE
            """)
    long countUnreadByRecipientGlobal(@Param("recipientId") long recipientId,
                                      @Param("recipientRole") int recipientRole);

    /**
     * 对应 Go 端 GetConversationMessages 用 offset + limit 分页：
     * 与 {@link #listByConversation} 不同在于这是 "用 offset 不带 cursor"。
     *
     * <p><b>⚠️ DRIFT-MSG-V-004（2026-10-22 修）：排序键必须是 {@code created_at}，不是 {@code id}</b>
     * <pre>
     * Go   message.go:159-174
     *      db := Model(&amp;ConversationMessage{}).Where("conversation_id = ?", conversationID).
     *           Order("created_at asc")                       ← created_at
     * RAG  修复前
     *      w.orderByAsc(ConversationMessageEntity::getId)     ← 错用了 id
     * </pre>
     * 两者<b>看起来等价</b>（id 是雪花、时间单调），实际不等价：雪花 id 在<b>同一毫秒内</b>
     * 按 (机器号, 序列号) 排序，与 {@code created_at} 的并列顺序不必一致。
     * ⇒ {@code created_at} 并列时两侧返回顺序不同，offset 分页的翻页结果也不同。
     *
     * <p><b>一个交叉证据</b>：DDL {@code V3__create_message_conversation_message.sql:20-23}
     * 建的是 {@code idx_message_conversation_time (conversation_id, created_at)}，
     * 注释写着「用于 GetConversationMessages 的 ORDER BY created_at ASC」——
     * <b>索引与注释都指向 created_at，只有代码用了 id</b>。这本身就是漂移的可查痕迹。
     *
     * <p><b>刻意不加 id 作为 tie-breaker</b>：加了确实能让分页更稳（Go 侧
     * {@code created_at} 并列时顺序不确定，offset 分页会漏/重），
     * 但那是<b>新增 Go 不存在的行为</b>，属于 AGENTS.md §3.2 禁止的「顺手改好」。
     * Go 的不确定性照原样保留，等 Go 侧先改再对齐。
     */
    default List<ConversationMessageEntity> listByConversationPage(long conversationId,
                                                                  int offset, int limit) {
        LambdaQueryWrapper<ConversationMessageEntity> w = new LambdaQueryWrapper<>();
        w.eq(ConversationMessageEntity::getConversationId, conversationId);
        w.orderByAsc(ConversationMessageEntity::getCreatedAt);
        // DRIFT-MSG-V-014：Go 出处 internal/model/message.go:164-168（同上两处）。
        String tail = GoStylePagination.limitOffset(limit, offset);
        if (!tail.isEmpty()) {
            w.last(tail);
        }
        return selectList(w);
    }

    /**
     * 对应 Go 端 createConversationWithMessageTx / createOrSendMessageTx 的 message INSERT。
     *
     * <p>🔴 {@code #{read}} 不是笔误（2026-10-23，DRIFT-MSG-V-017 的连带修复）：
     * 实体字段已从 {@code isRead} 改名为 {@code read}，而<b>手写 {@code @Insert} 的
     * {@code #{…}} 走 OGNL，按 JavaBeans 规则解析属性名</b>
     * —— {@code isRead()} 这个 getter 对应的是属性 {@code read}，属性 {@code isRead} 并不存在。
     * 保留 {@code #{isRead}} 的后果是运行期抛
     * {@code ReflectionException: There is no getter for property named 'isRead'}，
     * 且<b>编译与启动都发现不了</b>。
     *
     * <p>⇒ 与 {@link #markAllReadByRecipient} 对照：那条走 MyBatis-Plus 的 lambda，
     * 由 {@code TableInfo.propertyMap} 反查属性名；这条走手写 OGNL，
     * <b>两套解析规则，必须分别验证</b> —— 改一处不保证另一处还对。
     */
    @Insert("""
            INSERT INTO message_conversation_message (
              id, conversation_id, sender_id, sender_role, recipient_id, recipient_role,
              content, is_read, read_at, created_at
            ) VALUES (
              #{id}, #{conversationId}, #{senderId}, #{senderRole}, #{recipientId}, #{recipientRole},
              #{content}, #{read}, #{readAt}, #{createdAt}
            )
            """)
    int insertMsg(com.rag.message.domain.entity.ConversationMessageEntity msg);
}
