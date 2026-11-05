--
-- V6__create_comment_domain_fact_outbox.sql
-- 对应 Go 版 migrations/001_comment_domain_fact_outbox.sql:3-8（基础 4 列）
--   ＋ migrations/002_comment_dc_wire.sql:3-17（3 列 / 2 索引 / NOT VALID CHECK）
--
-- ⚠️ 为什么拆成 V6/V7 两个脚本而 Go 是 001/002：
--    Go 的 001 建基础表、002 再 ALTER 加 3 列，因为 Go 侧这两步分属「切换前 / 切换后」两个发布。
--    RAG 是**全新建库**，把最终形态一次性建出来等价且更少一次 ALTER。
--    列类型与约束逐条照抄，分列理由见下方注释。
--
-- 与 like_domain_fact_outbox 的区别（别把两域的表混谈）：
--   本表承载 comment 域的 **community.comment.\{created,deleted,interaction\}** 三类事实，
--   append-only、事务内冻结信封、事务内分配 DC 版本号。
--   它与 article / hot / like 域的事实表**没有共享键**，只是共用 common 的 Wire 契约。

CREATE TABLE IF NOT EXISTS comment_domain_fact_outbox (
    event_id      VARCHAR(128) PRIMARY KEY,   -- Go: EventID string `column:event_id;primaryKey;type:varchar(128)`
    event_type    VARCHAR(64)  NOT NULL,      -- Go: EventType string `type:varchar(64);not null`
    payload       JSONB        NOT NULL,      -- Go: Payload   string `type:jsonb;not null`
    -- ↓↓↓ 以下 3 列来自 Go migrations/002_comment_dc_wire.sql:3-6 ↓↓↓
    aggregate_id    VARCHAR(128),             -- Go: AggregateID *string `type:varchar(128)`
    fact_version    BIGINT,                   -- Go: FactVersion *int64
    delivery_envelope JSONB,                  -- Go: DeliveryEnvelope *string `type:jsonb`（冻结信封）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP  -- Go: CreatedAt time.Time `not null;default:CURRENT_TIMESTAMP`
);

-- Go: `uniqueIndex:uk_comment_fact_stream`（AggregateID 与 FactVersion 两列都带 tag ⇒ 复合唯一索引）
-- 语义作用：同一条评论事实流内版本号唯一；appendCommentFact 用
--   INSERT INTO comment_fact_stream … ON CONFLICT DO UPDATE … RETURNING
-- 原子取号，依赖的就是这个唯一键仲裁。
CREATE UNIQUE INDEX IF NOT EXISTS uk_comment_fact_stream
    ON comment_domain_fact_outbox (aggregate_id, fact_version);

-- Go: `CREATE INDEX idx_comment_legacy_fact_aggregate
--        ON comment_domain_fact_outbox ((payload->>'aggregate_id'))
--        WHERE delivery_envelope IS NULL;`
--
-- ⚠️ 这是**部分索引**：只覆盖「有 aggregate_id 但没有冻结信封」的历史行。
--    appendCommentFact:107-114 的 legacy 守卫正是靠它对应的那条查询
--    （`WHERE payload->>'aggregate_id' = ? AND delivery_envelope IS NULL` COUNT）
--    把「该 aggregate 存在未版本化遗留事实」变成一个可判定的错误而不是静默续号。
CREATE INDEX IF NOT EXISTS idx_comment_legacy_fact_aggregate
    ON comment_domain_fact_outbox ((payload->>'aggregate_id'))
    WHERE delivery_envelope IS NULL;

-- Go: `CHECK (aggregate_id IS NOT NULL AND fact_version IS NOT NULL
--            AND delivery_envelope IS NOT NULL) NOT VALID`
--
-- 语义（Go 002:19-20 注释）：存量行放行（留给显式对账），但**新写入必须三件套齐全** ⇒
-- 「一个旧消费者不能再悄悄造出一条无版本事实」。
-- NOT VALID 逐字照抄：它对 INSERT/UPDATE 照常生效，只是不回扫存量行。
--
-- ⚠️ Go 把这段包在 DO $$ … $$ 里做「不存在才加」的幂等保护。本脚本是全新建库，
--    列与表都由本脚本自己刚建出来 ⇒ 不存在重名约束，直接 ADD 即可，语义等价。
--    （若把本脚本重放到已有库，用 IF NOT EXISTS 建表后约束名仍可能撞车 —— 那种场景
--      Go 的 DO 块才必要；本项目 like-rpc 的 V6 已实测 Flyway 能跑 DO 块，
--      若将来要改成可重放，这里再加回守卫。）
--
-- 安全性已核对：CommentDomainFactService#append 写入时
-- aggregateId / factVersion / envelopeJson **三者都非 null**（Go comment.go:137-141 同），
-- 该约束不会打脸生产写路径。
ALTER TABLE comment_domain_fact_outbox
    ADD CONSTRAINT comment_new_fact_has_dc_wire
    CHECK (aggregate_id IS NOT NULL AND fact_version IS NOT NULL AND delivery_envelope IS NOT NULL) NOT VALID;