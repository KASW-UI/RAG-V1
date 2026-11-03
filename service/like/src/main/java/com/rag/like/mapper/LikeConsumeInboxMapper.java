package com.rag.like.mapper;

import com.rag.like.domain.entity.LikeConsumeInboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p><b>消费收件箱数据访问</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mapper/LikeConsumeInboxMapper.java:20-79}
 * 的全部 SQL（逐条照抄）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.mapper.LikeConsumeInboxMapper}
 * ⇒ C 侧 {@code com.rag.like.mapper.LikeConsumeInboxMapper}。
 *
 * <p><b>幂等判定全在这三条 SQL 上</b>：
 * <ol>
 *   <li>{@link #insertInboxIfAbsent} 返回 0 ⇒ 该 msg_id 之前已落库 ⇒ 可能有并发/重投；</li>
 *   <li>{@link #selectInboxForUpdate} 加 {@code FOR UPDATE} 拿到既有行
 *       （B 侧用 {@code clause.Locking{Strength:"UPDATE"}}）；</li>
 *   <li>比对 {@code payload_hash} 判重、读 {@code status} 判是否已完成 —— 见 {@code service/LikeMessageIngestService}。</li>
 * </ol>
 */
@Mapper
public interface LikeConsumeInboxMapper {

    /**
     * 对应 B 侧 {@code tx.Clauses(clause.OnConflict{DoNothing: true}).Create(p.Inbox)}。
     *
     * <p><b>返回 0 不是错误</b>，它表示「这条 msg_id 已经落过库」，是幂等分支的入口信号。
     */
    @Insert("""
            INSERT INTO like_consume_inbox (msg_id, payload_hash, topic, consumer, status, created_at, updated_at)
            VALUES (#{msgId}, #{payloadHash}, #{topic}, #{consumer}, 0, now(), now())
            ON CONFLICT (msg_id) DO NOTHING
            """)
    int insertInboxIfAbsent(@Param("msgId") String msgId,
                            @Param("payloadHash") String payloadHash,
                            @Param("topic") String topic,
                            @Param("consumer") String consumer);

    /**
     * 对应 B 侧 {@code tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("msg_id = ?", …).First(&prior)}。
     *
     * @return 既有行；{@link #insertInboxIfAbsent} 返回 0 却查不到 ⇒ 数据被外部删除，属异常，由调用方报错
     */
    @Select("""
            SELECT msg_id, payload_hash, topic, consumer, status, created_at, updated_at
            FROM like_consume_inbox
            WHERE msg_id = #{msgId}
            FOR UPDATE
            """)
    LikeConsumeInboxEntity selectInboxForUpdate(@Param("msgId") String msgId);

    /**
     * 对应 B 侧 {@code markLikeMessageDone}：
     * {@code tx.Model(&LikeConsumeInbox{}).Where("msg_id = ?", msgID).Update("status", 1)}。
     *
     * <p>只在「本条消息的全部副作用（record 状态变更 + 事实流写入）都已成功」后调用，
     * 与 record/fact 的更新在<b>同一事务</b>内。
     */
    @Update("UPDATE like_consume_inbox SET status = 1, updated_at = now() WHERE msg_id = #{msgId}")
    int markDone(@Param("msgId") String msgId);
}
