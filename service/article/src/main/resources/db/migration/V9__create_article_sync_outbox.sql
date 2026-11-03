--
-- V9__create_article_sync_outbox.sql
-- 对应 Go 端 service/article/rpc/internal/model/article_sync_outbox.go
-- （ArticleSyncOutboxEvent，由 GORM AutoMigrate 生成；Go 的 article.sql 未收录此表，
--   它只在 AutoMigrate(&ArticleSyncOutboxEvent{}) 里出现 —— model/db.go:55-60）。
--
-- 语义：审核通过后的「文章同步事件」发件箱。审核消费者在事务内写 pending 行，
-- ArticleSyncOutboxRelay 定时捞取推送 Kafka，失败则 status=2 + retry_count+1。
--

CREATE TABLE IF NOT EXISTS article_sync_outbox (
    event_id     VARCHAR(64)   NOT NULL,
    event_key    VARCHAR(128)  NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    aggregate_id VARCHAR(64)   NOT NULL,
    payload      JSONB         NOT NULL,
    status       SMALLINT      NOT NULL DEFAULT 0,
    retry_count  INTEGER       NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (event_id)
);

-- Go: `EventKey string `gorm:"...;uniqueIndex:uk_article_sync_outbox_event_key"``
-- 作用：article:op:eventId 三元组唯一，阻止同一逻辑事件重复入队。
CREATE UNIQUE INDEX IF NOT EXISTS uk_article_sync_outbox_event_key
    ON article_sync_outbox (event_key);

-- Go: `EventType string `gorm:"...;index"`` 与 `AggregateID string `gorm:"...;index"``
CREATE INDEX IF NOT EXISTS idx_article_sync_outbox_event_type
    ON article_sync_outbox (event_type);

CREATE INDEX IF NOT EXISTS idx_article_sync_outbox_aggregate_id
    ON article_sync_outbox (aggregate_id);

-- Go: `Status int32 `gorm:"smallint;not null;default:0;index"``
-- relay 的捞取条件是 status IN (0,2) AND retry_count < maxRetry（默认 10）。
CREATE INDEX IF NOT EXISTS idx_article_sync_outbox_status
    ON article_sync_outbox (status);

COMMENT ON COLUMN article_sync_outbox.status IS '0 待发送 / 1 已发送 / 2 发送失败';
COMMENT ON COLUMN article_sync_outbox.payload IS '冻结的 ArticleSyncEvent JSON（推送时不重新序列化）';