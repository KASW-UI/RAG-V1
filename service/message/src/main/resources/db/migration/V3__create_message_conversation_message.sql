--
-- V3__create_message_conversation_message.sql
-- 对应 Go 版 service/message/rpc/internal/model/data_model.go: ConversationMessage
-- 表结构与 Go 端 ConversationMessage struct @gorm tag 1:1
--

CREATE TABLE IF NOT EXISTS message_conversation_message (
    id                 BIGINT    PRIMARY KEY,
    conversation_id    BIGINT    NOT NULL,
    sender_id          BIGINT    NOT NULL,
    sender_role        INTEGER   NOT NULL,
    recipient_id       BIGINT    NOT NULL,
    recipient_role     INTEGER   NOT NULL,
    content            TEXT      NOT NULL,
    is_read            BOOLEAN   NOT NULL DEFAULT FALSE,
    read_at            TIMESTAMP,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 对应 Go 端 ConversationMessage 上的 @gorm:"index:idx_message_conversation_time,priority:1|2"
-- 复合索引 (conversation_id, created_at) 用于 GetConversationMessages 的 ORDER BY created_at ASC
CREATE INDEX IF NOT EXISTS idx_message_conversation_time
    ON message_conversation_message (conversation_id, created_at);

-- 对应 Go 端 ConversationMessage 上的 @gorm:"index:idx_message_recipient_read,priority:1|2|3"
-- 复合索引 (recipient_id, recipient_role, is_read) 用于：
--   - CountUnreadConversationMessages
--   - CountUnreadMessagesByConversation
--   - MarkConversationRead
CREATE INDEX IF NOT EXISTS idx_message_recipient_read
    ON message_conversation_message (recipient_id, recipient_role, is_read);
