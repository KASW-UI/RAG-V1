package com.rag.message.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.message.domain.entity.NotificationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p><b>{@code message_notification} 表 Mapper 接口</b>（MyBatis-Plus）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/mapper/NotificationMapper.java}
 * （B 侧类 javadoc {@code :12-19} · {@code listByRecipient :23-55} · {@code countByRecipient :57-92} ·
 * {@code countUnreadByRecipient :94-104} · {@code markRead :106-141} ·
 * {@code markAllReadByRecipient :143-164} · {@code insertBatch :166-222}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.mapper} →
 *       {@code com.rag.message.mapper}，D7 包压平）＋ 实体 import 换成
 *       {@code com.rag.message.domain.entity.NotificationEntity} ＋
 *       {@code insertBatch} 里的 typeHandler 全限定名换成
 *       {@code com.rag.common.persistence.JsonbMapTypeHandler}
 *       （<b>D-11 已收口 · 2026-11-03</b>：原先是本域副本
 *       {@code com.rag.message.mapper.JsonbMapTypeHandler}，类名不变、只换落点）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— <b>每一条 SQL、每一个参数绑定名、每一个 WHERE 条件、
 *       每一个 ORDER BY 键、每一个计数口径都逐字照抄</b>。
 *       ⚠️ 特别地：{@code #{item.read}}（不是 {@code #{item.isRead}}）保持原样
 *       —— 手写 OGNL 按 JavaBean 规则取属性名，而 {@code isRead()} 这个 getter 对应的属性是
 *       {@code read}（B 侧 {@code :166-202} 的机理说明照抄）。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>框架职责</b>：{@code @Mapper} ＋ {@code extends BaseMapper<NotificationEntity>}；
 * BaseMapper 的默认方法（insert/selectById/updateById/deleteById）与自定义业务方法签名共存；
 * 事务在 DomainService 上编排，不在本接口上。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    /**
     * 对应 Go 端 ListNotifications：分页拉某 recipient 的通知列表，按 kind / is_read 过滤。
     *
     * @param recipientId    对应 Go req.UserRecipientId
     * @param role           对应 Go req.UserRole（pb.SenderRole int 形式）；UNSPECIFIED(0) 视为不过滤
     * @param kind           对应 Go req.Kind（pb.NotificationKind int 形式）；0 视为不过滤
     * @param unreadOnly     对应 Go req.UnreadOnly
     * @param limit          LIMIT 子句大小
     * @param offset         OFFSET 子句
     * @return 按 created_at DESC 排序
     */
    default List<NotificationEntity> listByRecipient(long recipientId, int role, int kind,
                                                     boolean unreadOnly, int limit, int offset) {
        LambdaQueryWrapper<NotificationEntity> w = new LambdaQueryWrapper<>();
        w.eq(NotificationEntity::getRecipientId, recipientId);
        if (role > 0) {
            w.eq(NotificationEntity::getSenderRole, role);
        }
        if (kind > 0) {
            w.eq(NotificationEntity::getKind, kind);
        }
        if (unreadOnly) {
            w.eq(NotificationEntity::isRead, false);
        }
        w.orderByDesc(NotificationEntity::getCreatedAt);
        // DRIFT-MSG-V-014：Go（internal/model/message.go:59-64）对 limit/offset 各自判 > 0，
        // 负值是**跳过该子句**而不是拼进 SQL（否则 PG 报 OFFSET must not be negative）。
        String tail = GoStylePagination.limitOffset(limit, offset);
        if (!tail.isEmpty()) {
            w.last(tail);
        }
        return selectList(w);
    }

    /**
     * 对应 Go 端 {@code CountNotifications(ctx, recipientID, unreadOnly)}（{@code message.go:29-37}）
     * 与 {@code ListNotifications} 里的 {@code total} 计数（{@code message.go:40-48}）：
     * <pre>
     * query := Model(&amp;Notification{}).Where("recipient_id = ?", recipientID)
     * if unreadOnly { query = query.Where("is_read = ?", false) }   // ← 过滤先加
     * var total int64
     * query.Count(&amp;total)                                         // ← total 复用同一个 query
     * </pre>
     *
     * <p><b>⚠️ DRIFT-MSG-V-001（2026-10-22 修）</b>：Go 侧的 {@code total} 与
     * {@code items} <b>共用同一个带过滤的 query</b>，所以 {@code unread_only=true} 时
     * {@code total} 只数未读。修复前本接口的调用点写的是
     * {@code selectCount(QueryWrapper.eq("recipient_id", uid))}（<b>无 is_read</b>），
     * 而列表走 {@code listByRecipient(..., unreadOnly, ...)}（<b>有 is_read</b>）——
     * 于是 {@code unread_only=true} 时 {@code total} 恒等于全部条数，
     * 前端分页器按它算总页数会多翻出空页。
     *
     * <p><b>为什么这里不能"顺手简化"</b>：Go 端「total 跟随 unread_only」不是笔误而是
     * 真实语义。§6.7.5 纪律 A 由此而来：
     * <b>分页接口的 total 与 items 必须成对断言</b>，否则 {@code total} 是无人区。
     */
    default long countByRecipient(long recipientId, int role, int kind, boolean unreadOnly) {
        LambdaQueryWrapper<NotificationEntity> w = new LambdaQueryWrapper<>();
        w.eq(NotificationEntity::getRecipientId, recipientId);
        if (role > 0) {
            w.eq(NotificationEntity::getSenderRole, role);
        }
        if (kind > 0) {
            w.eq(NotificationEntity::getKind, kind);
        }
        if (unreadOnly) {
            w.eq(NotificationEntity::isRead, false);
        }
        return selectCount(w);
    }

    /**
     * 对应 Go 端 CountNotifications(unreadOnly=true)：recipient 未读通知总数。
     * 用 idx_notification_recipient_read 复合索引命中。
     *
     * <p>委托给 {@link #countByRecipient} 而不是各写一份 wrapper：这两个方法的差别
     * <b>只有 unreadOnly 这一个参数</b>，各写一份就等于给 §6.7 的 DRIFT-MSG-V-001
     * 留一个"下次改一处忘了另一处"的口子。
     */
    default long countUnreadByRecipient(long recipientId, int role, int kind) {
        return countByRecipient(recipientId, role, kind, true);
    }

    /**
     * 对应 Go 端 {@code MessageModel.MarkNotificationRead}（{@code message.go:72-86}）：
     * <pre>
     * res := conn.Model(&amp;Notification{}).
     *      Where("id = ? AND recipient_id = ?", notificationID, recipientID).
     *      Updates(map[string]any{"is_read": true, "read_at": &amp;now})
     * if res.Error != nil { return res.Error }
     * if res.RowsAffected == 0 { return ErrRecordNotFound }
     * </pre>
     *
     * <p><b>修复前</b>：DomainService 先 {@code selectById} 做归属校验、再 {@code updateById}，
     * 拆成 SELECT + UPDATE 两条语句；且 {@code updateById} 生成的是 {@code WHERE id = ?}，
     * <b>不含 {@code recipient_id} 归属条件</b>。归属判断与写入之间存在竞态窗口
     * （TOCTOU），且「更新不到行」被静默当成成功。
     *
     * <p><b>修复后</b>：归属条件直接进 UPDATE 的 WHERE，与 Go 同构 —— 单条语句、原子、
     * 天然免疫竞态；返回受影响行数供上层对齐 Go 的 {@code RowsAffected == 0 → ErrRecordNotFound}。
     *
     * <p><b>为什么不用 updateById(patch)</b>：{@code updateById} 只能按主键更新，
     * 无法附加 {@code recipient_id} 条件（§3.3 记录的
     * 「{@code LambdaUpdateWrapper.set()} 丢字段类型信息」同源问题）。
     */
    default int markRead(long id, long recipientId, java.time.OffsetDateTime readAt) {
        NotificationEntity patch = new NotificationEntity();
        patch.setRead(true);
        patch.setReadAt(readAt);
        // 用 LambdaUpdateWrapper 而非 update(entity, wrapper)：前者由 MP 逐字段生成
        // 带类型绑定的 SET 子句，不会踩 §3.3 记录的 typeHandler 丢失问题。
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NotificationEntity> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(NotificationEntity::getId, id)
         .eq(NotificationEntity::getRecipientId, recipientId)
         .set(NotificationEntity::isRead, true)
         .set(NotificationEntity::getReadAt, readAt);
        return update(null, w);
    }

    /**
     * 对应 Go 端 markAllNotificationsReadTx 内的 UPDATE：
     * UPDATE ... SET is_read=true, read_at=? WHERE recipient_id=? AND is_read=false。
     * 返回受影响行数。
     */
    default int markAllReadByRecipient(long recipientId, int role, int kind,
                                       java.time.OffsetDateTime readAt) {
        // 用 wrapper + 手写 SQL（MyBatis-Plus 的 updateById 不支持条件 UPDATE）
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NotificationEntity> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(NotificationEntity::getRecipientId, recipientId)
                .eq(NotificationEntity::isRead, false);
        if (role > 0) {
            w.eq(NotificationEntity::getSenderRole, role);
        }
        if (kind > 0) {
            w.eq(NotificationEntity::getKind, kind);
        }
        w.set(NotificationEntity::isRead, true);
        w.set(NotificationEntity::getReadAt, readAt);
        return update(null, w);
    }

    /**
     * 对应 Go 端 MessageModel.CreateNotifications（{@code message.go:22-27}）：
     * 批量 INSERT notification 行；由 {@code MessageAtomicService#insertNotificationsTx}
     * 按 {@code INSERT_BATCH_SIZE} 切片后逐片调用（DRIFT-MSG-V-002）。
     *
     * <p>用 foreach + 多行 VALUES 把多条 INSERT 合并成一条 SQL（性能优于逐条 insert）。
     *
     * <p>🔴 <b>DRIFT-MSG-V-011（2026-10-22 修）：{@code #{item.isRead}} 让整个发通知功能从未生效</b>
     * <pre>
     * 修复前：#{item.isRead}
     * 现在  ：#{item.read}
     * </pre>
     * 实体字段叫 {@code read}（不是 {@code isRead}），getter 是 {@code isRead()}。
     * MyBatis 的 {@code Reflector} 按 JavaBean 约定把 {@code isRead()} 归到属性
     * <b>{@code read}</b>，于是 {@code #{item.isRead}} 找不到属性，运行期抛：
     * <pre>
     * org.apache.ibatis.reflection.ReflectionException:
     *   There is no getter for property named 'isRead' in 'class ...NotificationEntity'
     * </pre>
     *
     * <p><b>为什么这个字段名是 {@code read} 而不是 {@code isRead}</b>：
     * 见 {@link NotificationEntity} 的类注释 —— MyBatis-Plus 的 {@code LambdaXxxWrapper}
     * 解析 {@code Entity::method} 时按同样的约定取属性名，字段若叫 {@code isRead}
     * 会在<b>查询/更新</b>路径上抛 {@code can not find lambda cache for this property [read]}。
     * 两边约定一致，所以手写 SQL 里也必须写 {@code #{item.read}}。
     *
     * <p><b>为什么此前没被发现</b>（§3.3 同款形态）：
     * <ul>
     *   <li>{@code insertBatch} 是<b>通知写入的唯一生产路径</b>
     *       （{@code grep notificationMapper.insert(} 在 main 下零命中），</li>
     *   <li>而 {@code sendNotification} / {@code SendNotification}
     *       在 {@code message-rpc} 的 21 条测试里<b>零覆盖</b>，
     *       {@code tools/difftest} 的 12 档也只覆盖错误码、不覆盖这条 happy path，</li>
     *   <li>⇒ <b>admin 的定向通知与广播通知在 RAG 端从未成功过一次</b>。
     *       21 条全绿的测试与一条从未执行过的写路径，可以并存。</li>
     * </ul>
     */
    @Insert({
            "<script>",
            "INSERT INTO message_notification (",
            "  id, recipient_id, sender_id, sender_role, kind, title, content,",
            "  is_read, read_at, extra, created_at, updated_at",
            ") VALUES ",
            "<foreach collection='items' item='item' separator=','>",
            "  (#{item.id}, #{item.recipientId}, #{item.senderId}, #{item.senderRole},",
            "   #{item.kind}, #{item.title}, #{item.content},",
            // ⚠️ 属性名是 read（getter isRead()），不是 isRead —— 见本方法 javadoc
            "   #{item.read}, #{item.readAt},",
            // ⚠️ 不能用 MP 的 JacksonTypeHandler：它 setString(...) ⇒ pgjdbc 按 VARCHAR 发送
            //    ⇒ Postgres 拒收（column extra is of type jsonb but expression is of type
            //    character varying）。见 JsonbMapTypeHandler 类注释（DRIFT-MSG-V-013）。
            "   #{item.extra,typeHandler=com.rag.common.persistence.JsonbMapTypeHandler},",
            "   #{item.createdAt}, #{item.updatedAt})",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("items") List<NotificationEntity> items);
}
