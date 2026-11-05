--
-- ============================================================================
-- RAG-V1 · favorite 域 · Flyway 迁移脚本 V5__add_favorite_fact_delivery.sql
--
-- C 侧落点: service/favorite/src/main/resources/db/migration/V5__add_favorite_fact_delivery.sql
-- B 侧出处: /Users/pr/Documents/java&golang/RAG/service/favorite/favorite-rpc/
--             src/main/resources/db/migration/V5__add_favorite_fact_delivery.sql:1-58
--
-- 本文件做: favorite_fact_outbox 的 5 个回执列
--           (technical_receipt_id / technical_input_hash / technical_offset /
--            technical_received_at / delivered_at)
--           + 不可变触发器 favorite_fact_identity_immutable
--
-- 搬运方式: **逐字搬运，未改 SQL 文本** —— 除本文件头注释外，本文件与 B 侧同名文件
--           **逐字节一致**：表名 / 列名 / 列类型 / 长度 / NOT NULL / DEFAULT /
--           UNIQUE / CHECK / 索引 / 触发器 / 函数体 / 全部原有注释，均照抄未改。
-- 基准依据: AGENTS.md §三（L1 契约层以 B 侧为准 · D16）·
--           docs/Mapping_Table/favorite/favorite.md §2.1（4 张表逐表对照）
--
-- ⚠️ 幂等性: 保留 B 侧 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` /
--           `DROP TRIGGER IF EXISTS` + `CREATE OR REPLACE FUNCTION` 等幂等写法
--           （B 侧是 C 侧 DDL 事实源）。
-- ⚠️ 账本:   依赖 `spring.flyway.table=flyway_schema_history_favorite` +
--           `baseline-on-migrate: true` + `baseline-version: 0`
--           （B 侧出处: favorite-rpc/src/main/resources/application.yml:104-129）。
--           ⇒ C 侧 `service/favorite/src/main/resources/application.yml` 当前**无**
--             `spring.flyway` 段 ⇒ 属**待拍板项**（见本次交付报告），本文件未改 yml。
-- ⚠️ 文件名: `V5__*` 编号是**已发布的迁移历史**（Flyway 按版本号排序），
--           原样保留，不重排、不改名（否则 checksum mismatch ⇒ 启动失败）。
-- ============================================================================
--
-- V5__add_favorite_fact_delivery.sql
-- 对应 Go 侧 DDL 事实源之二：002_favorite_fact_delivery.sql（**逐字搬运**）
--   Sea-RideTheWind_golang/service/favorite/rpc/internal/model/002_favorite_fact_delivery.sql:1-25
--
-- ⚠️⚠️ 文件头的触发器是 AutoMigrate **绝不创建**的东西（AutoMigrate 只管列）。
--   缺它的后果是**纯静默降级**，不报错、不告警：
--     「冻结信封不可变」从数据库硬约束退化成应用层约定，
--     而 favorite 的整条事实链路的全部安全性都建立在这条不变量上
--     ——冻结信封一旦可改，DC 的 input_hash 就不再能证明「投出去的与冻结的是同一份」。
--   ⇒ FavoriteSchemaGate 在启动时专门校验这个触发器存在。
ALTER TABLE favorite_fact_outbox
    ADD COLUMN IF NOT EXISTS technical_receipt_id  varchar(128),
    ADD COLUMN IF NOT EXISTS technical_input_hash  char(64),
    ADD COLUMN IF NOT EXISTS technical_offset      bigint       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS technical_received_at timestamptz,
    ADD COLUMN IF NOT EXISTS delivered_at          timestamptz;

-- ⚠️ 列语义（逐条对应 Go favorite_fact.go:67-71 的 struct tag）：
--   technical_receipt_id   varchar(128)  ← DC 回执的 receipt_id；Go 侧是 string（无 not null）
--   technical_input_hash   char(64)      ← JCS+SHA256 hex，对 frozen payload 原始字节算（favorite_authority.go:179-186）
--   technical_offset       bigint NOT NULL DEFAULT 0  ← DC 的 offset，≥1 才算已接受（favorite_delivery.go:121）
--   technical_received_at  timestamptz   ← DC 回执时间
--   delivered_at           timestamptz   ← 本地结算时间
--
-- ⚠️ 这 5 列在 Go 侧的 ALTER 里是 IF NOT EXISTS，而 AutoMigrate 已经建好了它们
--   ⇒ 在 Go 侧整条 ALTER 其实是空操作。RAG 侧用 IF NOT EXISTS 保持同样语义：
--   V3 已建其中 0 列（V3 只搬 001），所以这里是真正的首次添加。
--
-- 🔴 不可变触发器（Go 002:10-25）：
--   「投递元数据可变（status / retry_count / technical_* / delivered_at），
--     来源身份与事务冻结的 EventSpec 不可变（event_id / favorite_id /
--     aggregate_version / payload）」。
--   恰好与 Go 的 4 列清单逐字相同 —— 不多不少：
--   FavoriteFactDispatchService 的三次 UPDATE 只写投递元数据列
--   （markBlocked / markFailed / markAccepted），**没有一列落在下面的清单里**
--   ⇒ 不会被误伤。
--
-- ⚠️ 函数体与触发器形式**逐字照抄 Go**（BEFORE UPDATE / FOR EACH ROW，非 constraint trigger）：
--   Go 是「行改写前就拒」，constraint trigger 是「提交时才拒」
--   ⇒ 错误类型与事务失败点都会变。
CREATE OR REPLACE FUNCTION reject_favorite_fact_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.favorite_id IS DISTINCT FROM OLD.favorite_id
       OR NEW.aggregate_version IS DISTINCT FROM OLD.aggregate_version
       OR NEW.payload IS DISTINCT FROM OLD.payload THEN
        RAISE EXCEPTION 'favorite fact envelope is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS favorite_fact_identity_immutable ON favorite_fact_outbox;
CREATE TRIGGER favorite_fact_identity_immutable
BEFORE UPDATE ON favorite_fact_outbox
FOR EACH ROW EXECUTE FUNCTION reject_favorite_fact_identity_change();