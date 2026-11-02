package com.rag.message.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.message.domain.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * <p><b>{@code message_conversation} 表 Mapper 接口</b>（MyBatis-Plus）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/mapper/ConversationMapper.java}
 * （B 侧类 javadoc {@code :16-25} · {@code findByIdOpt :29-35} · {@code findByPair :37-52} ·
 * {@code listByParticipant :54-87} · {@code countUnreadMessagesByConversation :89-105} ·
 * {@code updateLastMessage :107-125} · {@code countByParticipant :127-137} · {@code insertConv :139-157}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.mapper} →
 *       {@code com.rag.message.mapper}，D7 包压平）＋ 实体 import 换成
 *       {@code com.rag.message.domain.entity.ConversationEntity}。</li>
 *   <li><b>语义还一样吗</b>：一样 —— <b>每一条 SQL、每一个参数绑定名、每一个 WHERE 条件、
 *       每一个 ORDER BY 键、每一个计数口径都逐字照抄</b>（SQL 里的列名 / 排序键 / 计数口径都是<b>行为</b>）。
 *       ⚠️ 未做任何「优化」或「顺手修正」。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>框架职责</b>：{@code @Mapper} ＋ {@code extends BaseMapper<ConversationEntity>}
 * ⇒ MP 的通用 CRUD（{@code selectById} / {@code selectList} / {@code selectCount} / {@code update}）
 * 与本接口的显式 {@code @Select} / {@code @Insert} 共存；事务边界在 DomainService 上编排，
 * 本接口只暴露「单条 SQL 的原子操作」。
 *
 * <p><b>uk_conversation_pair 联合唯一索引</b>（p1_role, p1_id, p2_role, p2_id）保证同一对
 * 规范化参与者只能有一个会话。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * 对应 Go 端 findConversationById：SELECT WHERE id=?。
     * 返回 Optional（与 Go 的 sql.ErrNoRows → Optional.empty 行为对齐）。
     */
    default Optional<ConversationEntity> findByIdOpt(long id) {
        return Optional.ofNullable(selectById(id));
    }

    /**
     * 对应 Go 端 findConversationByParticipants：用 uk_conversation_pair 命中规范化 (p1, p2)。
     * 调用方已用 canonicalParticipants 规范化顺序。
     */
    @Select("""
            SELECT * FROM message_conversation
             WHERE participant1_role = #{p1Role}
               AND participant1_id   = #{p1Id}
               AND participant2_role = #{p2Role}
               AND participant2_id   = #{p2Id}
             LIMIT 1
            """)
    Optional<ConversationEntity> findByPair(@Param("p1Role") int p1Role,
                                             @Param("p1Id") long p1Id,
                                             @Param("p2Role") int p2Role,
                                             @Param("p2Id") long p2Id);

    /**
     * 对应 Go 端 ListConversations：分页拉某 operator 作为 p1 OR p2 的会话，
     * 按 {@code last_message_at DESC, updated_at DESC}。
     * 用 idx_conversation_p1 / idx_conversation_p2 两侧索引命中。
     *
     * <p><b>⚠️ DRIFT-MSG-V-005（2026-10-22 修）：次级排序键 {@code updated_at} 曾被漏掉</b>
     * <pre>
     * Go   message.go:114-138
     *      db := query.Order("last_message_at desc").Order("updated_at desc")   ← 两个键
     * RAG  修复前
     *      w.orderByDesc(ConversationEntity::getLastMessageAt)                 ← 只有一个键
     * </pre>
     * {@code last_message_at} 并列时（批量发消息即产生并列）Go 侧由 {@code updated_at}
     * 决定顺序；RAG 侧顺序不确定 ⇒ <b>同一份数据连续两次调用可能返回不同顺序</b>，
     * 翻页时列表项会跳动或重复。
     *
     * <p><b>顺序不可颠倒</b>：主键必须仍是 {@code last_message_at}，
     * 次键才是 {@code updated_at} —— 颠倒会改变「最新会话排最前」这个主语义。
     */
    default List<ConversationEntity> listByParticipant(int role, long id, int limit, int offset) {
        LambdaQueryWrapper<ConversationEntity> w = new LambdaQueryWrapper<>();
        w.and(q -> q.and(qq -> qq.eq(ConversationEntity::getParticipant1Role, role)
                                .eq(ConversationEntity::getParticipant1Id, id))
                     .or(qq -> qq.eq(ConversationEntity::getParticipant2Role, role)
                                 .eq(ConversationEntity::getParticipant2Id, id)));
        w.orderByDesc(ConversationEntity::getLastMessageAt);
        w.orderByDesc(ConversationEntity::getUpdatedAt);
        // DRIFT-MSG-V-014：Go 出处 internal/model/message.go:127-131（同 NotificationMapper）。
        String tail = GoStylePagination.limitOffset(limit, offset);
        if (!tail.isEmpty()) {
            w.last(tail);
        }
        return selectList(w);
    }

    /**
     * 对应 Go 端 CountUnreadMessagesByConversation：统计某会话中
     * operator 作为 recipient 的未读消息数。用 idx_message_recipient_read 命中。
     *
     * <p>这里实际是聚合 message_conversation_message 表，SQL 跨表；但
     * 接口放在 ConversationMapper 上与 Go 端 data_model.go 的"消息相关"方法归位一致。
     */
    @Select("""
            SELECT COUNT(*) FROM message_conversation_message
             WHERE conversation_id = #{conversationId}
               AND recipient_id    = #{recipientId}
               AND recipient_role  = #{recipientRole}
               AND is_read         = FALSE
            """)
    long countUnreadMessagesByConversation(@Param("conversationId") long conversationId,
                                           @Param("recipientId") long recipientId,
                                           @Param("recipientRole") int recipientRole);

    /**
     * 对应 Go 端 createOrSendMessageTx 内的 conversation UPDATE：
     * 更新 last_message_* + status + pending_sender_*。
     * 用 wrapper + 手写条件 UPDATE。
     */
    default int updateLastMessage(long conversationId, long lastMessageId,
                                  String lastMessagePreview, OffsetDateTime lastMessageAt,
                                  int newStatus, int pendingSenderRole, long pendingSenderId) {
        LambdaUpdateWrapper<ConversationEntity> w = new LambdaUpdateWrapper<>();
        w.eq(ConversationEntity::getId, conversationId)
                .set(ConversationEntity::getLastMessageId, lastMessageId)
                .set(ConversationEntity::getLastMessagePreview, lastMessagePreview)
                .set(ConversationEntity::getLastMessageAt, lastMessageAt)
                .set(ConversationEntity::getStatus, newStatus)
                .set(ConversationEntity::getPendingSenderRole, pendingSenderRole)
                .set(ConversationEntity::getPendingSenderId, pendingSenderId)
                .set(ConversationEntity::getUpdatedAt, lastMessageAt);
        return update(null, w);
    }

    /**
     * 对应 Go 端 ListConversations 内的 total 计数：统计 operator 作为 p1 OR p2 的会话总数。
     */
    default long countByParticipant(int role, long id) {
        LambdaQueryWrapper<ConversationEntity> w = new LambdaQueryWrapper<>();
        w.and(q -> q.and(qq -> qq.eq(ConversationEntity::getParticipant1Role, role)
                                .eq(ConversationEntity::getParticipant1Id, id))
                     .or(qq -> qq.eq(ConversationEntity::getParticipant2Role, role)
                                 .eq(ConversationEntity::getParticipant2Id, id)));
        return selectCount(w);
    }

    /**
     * 对应 Go 端 createConversationWithMessageTx 的 conversation INSERT。
     */
    @Insert("""
            INSERT INTO message_conversation (
              id, participant1_role, participant1_id, participant2_role, participant2_id,
              status, pending_sender_role, pending_sender_id,
              created_by_role, created_by_id,
              last_message_id, last_message_preview, last_message_at,
              created_at, updated_at
            ) VALUES (
              #{id}, #{participant1Role}, #{participant1Id}, #{participant2Role}, #{participant2Id},
              #{status}, #{pendingSenderRole}, #{pendingSenderId},
              #{createdByRole}, #{createdById},
              #{lastMessageId}, #{lastMessagePreview}, #{lastMessageAt},
              #{createdAt}, #{updatedAt}
            )
            """)
    int insertConv(com.rag.message.domain.entity.ConversationEntity conv);
}
