--
-- V1__create_message_notification.sql
-- 对应 Go 版 service/message/rpc/internal/model/data_model.go: Notification
-- 表结构与 Go 端 Notification struct @gorm tag 1:1
--

CREATE TABLE IF NOT EXISTS message_notification (
    id              BIGINT       PRIMARY KEY,
    recipient_id    BIGINT       NOT NULL,
    sender_id       BIGINT       NOT NULL,
    sender_role     INTEGER      NOT NULL,
    kind            INTEGER      NOT NULL,
    title           VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMP,
    extra           JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 对应 Go 端 Notification 上的 @gorm:"index:idx_notification_recipient_time,priority:1|2"
-- 复合索引 (recipient_id, created_at) 覆盖 ListNotifications 的 ORDER BY created_at desc
CREATE INDEX IF NOT EXISTS idx_notification_recipient_time
    ON message_notification (recipient_id, created_at);

-- 复合索引 (recipient_id, is_read) 覆盖 CountNotifications(unreadOnly=true)
--   + ListNotifications(unreadOnly=true) + 其内部未读计数 + MarkAllNotificationsRead
--   （Go 侧 service/message/rpc/internal/model/message.go:30,32,40,42,52,89 —— 4 处过滤形状
--     全部落在本复合索引的前缀上）
--
-- ⚠️⚠️ **与 Go 侧「不一致」是已签字的有意偏离**，别顺手改回单列：
--   签字记录 ⇒ docs/Intentional_Deviations/message/rpc.md §SD-2026-10-23-06
--     （⚠️ 该编号在 3 个域各有含义，这里指的是 **message 域**那一条）
--   Go 侧 service/message/rpc/internal/model/data_model.go:32,38,41
--       RecipientID ... index:idx_notification_recipient_time,priority:1
--       IsRead      ... index:idx_notification_recipient_read,priority:2
--   ⇒ is_read 声明的复合索引**只有 priority:2、没有 priority:1**，
--     而 recipient_id 的 priority:1 被写到了**另一个索引名**上
--   ⇒ GORM 于是只建出**单列**索引 (is_read) —— 那是**声明笔误的产物**，不是设计意图
--   ⇒ 本索引保留复合 (recipient_id, is_read)（= 本意），已签字认账
--   ⇒ 依据（真 PG 活体双侧对读）：msg_ddl_pg/go_db vs rag_db 的 pg_indexes
CREATE INDEX IF NOT EXISTS idx_notification_recipient_read
    ON message_notification (recipient_id, is_read);
