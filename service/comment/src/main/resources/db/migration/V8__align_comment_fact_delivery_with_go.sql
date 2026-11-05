--
-- V8__align_comment_fact_delivery_with_go.sql
-- 对应 Go 版 migrations/003_comment_fact_delivery.sql（7 列 + 复合索引 + plpgsql 触发器）
--
-- Go 003 的头部注释（:1-2）：
--   "A local accepted status means only that DataCenter durably returned
--    a matching technical receipt."
-- ⇒ delivery_status = 已接受 只意味着「DC 回执与技术收据匹配」，不代表业务被 DC 接受。
--   这 7 列就是那份技术证据的落盘位置。
--
-- ---------------------------------------------------------------------------
-- 逐项差异与依据（与 Go 003 的字面差异都写在这里，不留「照抄」二字）
-- ---------------------------------------------------------------------------

-- ===== ① 7 个投递元数据列（Go 003:3-10）=====
--
-- ⚠️ **列类型分歧**：Go 003 的字面写的是
--      `delivery_status smallint` / `retry_count integer` / `technical_offset bigint`
--    但那不是最终类型 —— 003 的三条都是 `ADD COLUMN IF NOT EXISTS`，
--    而 db.go:15 的 AutoMigrate **已经先建好了这些列** ⇒ **整段 ALTER 是空操作**。
--    真实类型由 GORM 决定：domain_fact.go:22-23 的 int32 无 type tag ⇒
--      gorm/schema/field.go   int32 ⇒ field.Size = 32
--      driver/postgres.go     size <= 32 ⇒ "integer"
--    ⇒ **integer**。
--    （同样的推导与纠错记录见 like-rpc/V6__align_like_domain_fact_outbox_with_go.sql，
--      那里把上一轮「int32 落 bigint」的误判也一并改正了。）
--    ⇒ 本脚本取 **integer**，既不等于 003 的 smallint，也不等于 bigint。
ALTER TABLE comment_domain_fact_outbox
    ADD COLUMN IF NOT EXISTS delivery_status      INTEGER      NOT NULL DEFAULT 0,   -- 0待投递 1已接受 2失败 3阻塞
    ADD COLUMN IF NOT EXISTS retry_count          INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS technical_receipt_id VARCHAR(128) NOT NULL DEFAULT '',   -- Go 003:6
    ADD COLUMN IF NOT EXISTS technical_input_hash CHAR(64)     NOT NULL DEFAULT '',   -- Go 003:7
    ADD COLUMN IF NOT EXISTS technical_offset     BIGINT       NOT NULL DEFAULT 0,    -- Go 003:8
    ADD COLUMN IF NOT EXISTS technical_received_at TIMESTAMPTZ,                        -- Go 003:9（可空）
    ADD COLUMN IF NOT EXISTS delivered_at          TIMESTAMPTZ;                         -- Go 003:10（可空）

-- ===== ② 投递复合索引（Go 003:12-13）=====
--
-- 精确对应 DispatchFactOnce 的取件查询（fact_delivery.go:64-70）：
--   WHERE delivery_status IN (0,2) AND delivery_envelope IS NOT NULL
--     AND NOT EXISTS(前序版本未 Accepted)
--   ORDER BY created_at, event_id
--   … FOR UPDATE SKIP LOCKED LIMIT 1
--
-- ⚠️ 另有 Go domain_fact.go:22 的 `index` tag 打在 DeliveryStatus 上（单列）。
--    两者都保留：单列那条对应 tag，复合那条对应 003 的实际取件路径。
CREATE INDEX IF NOT EXISTS idx_comment_fact_delivery
    ON comment_domain_fact_outbox (delivery_status, created_at, event_id);

CREATE INDEX IF NOT EXISTS idx_comment_domain_fact_outbox_delivery_status
    ON comment_domain_fact_outbox (delivery_status);

-- ===== ③ 信封不可变触发器（Go 003:15-32）=====
--
-- 语义：「投递元数据可变，来源身份与事务冻结的事件规格不可变」。
-- DispatchFactOnce 只 UPDATE delivery_status / retry_count / technical_* / delivered_at，
-- **没有一列落在不可变清单里** ⇒ 正常投递路径永远不触发它；
-- 它拦的是「有人事后改写已冻结的事实」这一类事故。
--
-- ⚠️ 函数体与触发器形式**逐字照抄 Go**（BEFORE UPDATE / FOR EACH ROW，不是 constraint trigger）：
--    改形式会让 RAISE 的时机（行改写前 vs 提交时）不同 —— Go 是「改写前就拒」，
--    constraint trigger 是「提交时才拒」⇒ 错误类型与事务失败点都会变。
--
-- ⚠️ Go 侧 DROP TRIGGER IF EXISTS 之后再 CREATE，本脚本保留同样两步：
--    Flyway 每个版本只跑一次，但保留可重放性且与 Go 一致。
CREATE OR REPLACE FUNCTION reject_comment_fact_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
       OR NEW.fact_version IS DISTINCT FROM OLD.fact_version
       OR NEW.delivery_envelope IS DISTINCT FROM OLD.delivery_envelope THEN
        RAISE EXCEPTION 'comment fact envelope is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS comment_fact_identity_immutable ON comment_domain_fact_outbox;
CREATE TRIGGER comment_fact_identity_immutable
BEFORE UPDATE ON comment_domain_fact_outbox
FOR EACH ROW EXECUTE FUNCTION reject_comment_fact_identity_change();