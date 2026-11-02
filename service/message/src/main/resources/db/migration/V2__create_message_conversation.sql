--
-- V2__create_message_conversation.sql
-- 对应 Go 版 service/message/rpc/internal/model/data_model.go: Conversation
-- 表结构与 Go 端 Conversation struct @gorm tag 1:1（含 uk_conversation_pair 联合唯一索引）
--

CREATE TABLE IF NOT EXISTS message_conversation (
    id                     BIGINT       PRIMARY KEY,
    participant1_role      INTEGER      NOT NULL,
    participant1_id        BIGINT       NOT NULL,
    participant2_role      INTEGER      NOT NULL,
    participant2_id        BIGINT       NOT NULL,
    status                 INTEGER      NOT NULL DEFAULT 1,
    pending_sender_role    INTEGER      NOT NULL DEFAULT 0,
    pending_sender_id      BIGINT       NOT NULL DEFAULT 0,
    created_by_role        INTEGER      NOT NULL,
    created_by_id          BIGINT       NOT NULL,
    last_message_id        BIGINT       NOT NULL DEFAULT 0,
    last_message_preview   VARCHAR(255) NOT NULL DEFAULT '',
    last_message_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 对应 Go 端 Conversation 上的 @gorm:"uniqueIndex:uk_conversation_pair,priority:1|2|3|4"
-- 复合唯一索引保证同一对规范化参与者只能有一个会话
ALTER TABLE message_conversation
    ADD CONSTRAINT uk_conversation_pair
    UNIQUE (participant1_role, participant1_id, participant2_role, participant2_id);

-- 对应 Go 端 Conversation 上的 @gorm:"index:idx_conversation_p1,priority:1|2"
-- 复合索引 (p1_role, p1_id) 用于 ListConversations 的 OR 条件一侧
CREATE INDEX IF NOT EXISTS idx_conversation_p1
    ON message_conversation (participant1_role, participant1_id);

-- 对应 Go 端 Conversation 上的 @gorm:"index:idx_conversation_p2,priority:1|2"
-- 复合索引 (p2_role, p2_id) 用于 ListConversations 的 OR 条件另一侧
CREATE INDEX IF NOT EXISTS idx_conversation_p2
    ON message_conversation (participant2_role, participant2_id);

-- 对应 Go 端 Conversation 上的 @gorm:"index"（last_message_at 单列）
-- 用于 ORDER BY last_message_at DESC
CREATE INDEX IF NOT EXISTS idx_conversation_last_message_at
    ON message_conversation (last_message_at);

-- 对应 Go 端 Conversation 上的 @gorm:"index"（updated_at 单列）
-- 用于 ORDER BY updated_at DESC
CREATE INDEX IF NOT EXISTS idx_conversation_updated_at
    ON message_conversation (updated_at);
