--
-- ============================================================================
-- RAG-V1 · favorite 域 · Flyway 迁移脚本 V6__align_favorite_schema_with_go.sql
--
-- C 侧落点: service/favorite/src/main/resources/db/migration/V6__align_favorite_schema_with_go.sql
-- B 侧出处: /Users/pr/Documents/java&golang/RAG/service/favorite/favorite-rpc/
--             src/main/resources/db/migration/V6__align_favorite_schema_with_go.sql:1-165
--
-- 本文件做: 对 V1–V5 的**幂等补齐**（Go 侧有、RAG 侧容易漏的资产）
--           ① favorite_item.target_revision 列        ② favorite_item.target_id 类型收敛
--           ③ favorite_item target_revision 作用域 CHECK
--           ④ favorite_fact_outbox CHECK + 唯一键 + 部分索引 + 单列索引
--           ⑤ 2 个不可变触发器（favorite_fact_identity_immutable /
--              favorite_legacy_marker_immutable）     ⑥ legacy 标记表的 8 个 CHECK
--
-- 搬运方式: **逐字搬运，未改 SQL 文本** —— 除本文件头注释外，本文件与 B 侧同名文件
--           **逐字节一致**：表名 / 列名 / 列类型 / 长度 / NOT NULL / DEFAULT /
--           UNIQUE / CHECK / 索引 / 触发器 / 函数体 / 全部原有注释，均照抄未改。
-- 基准依据: AGENTS.md §三（L1 契约层以 B 侧为准 · D16）·
--           docs/Mapping_Table/favorite/favorite.md §2.1（4 张表逐表对照）
--
-- ⚠️ 幂等性: 保留 B 侧 `ADD COLUMN IF NOT EXISTS` / `DO $$` 守卫 /
--           `DROP TRIGGER IF EXISTS` + `CREATE OR REPLACE FUNCTION` 等幂等写法
--           （B 侧是 C 侧 DDL 事实源）。
-- ⚠️ 账本:   依赖 `spring.flyway.table=flyway_schema_history_favorite` +
--           `baseline-on-migrate: true` + `baseline-version: 0`
--           （B 侧出处: favorite-rpc/src/main/resources/application.yml:104-129）。
--           ⇒ C 侧 `service/favorite/src/main/resources/application.yml` 当前**无**
--             `spring.flyway` 段 ⇒ 属**待拍板项**（见本次交付报告），本文件未改 yml。
-- ⚠️ 文件名: `V6__*` 编号是**已发布的迁移历史**（Flyway 按版本号排序），
--           原样保留，不重排、不改名（否则 checksum mismatch ⇒ 启动失败）。
-- ============================================================================
--
-- V6__align_favorite_schema_with_go.sql
-- 先例：service/like/like-rpc/src/main/resources/db/migration/V6__align_like_domain_fact_outbox_with_go.sql
--       （AGENTS.md §0 like 行记载的 5 项补回：列类型 / CHECK / 触发器 / 部分索引）
--
-- ⚠️ 为什么是新文件而不是改 V1..V5：
--   favorite-rpc 仍用 Flyway（独立账本 flyway_schema_history_favorite，AGENTS.md §6.3 问题 4），
--   **改已发布的迁移会触发 checksum mismatch 而启动失败**。users 域虽已废弃 Flyway 改幂等重放，
--   但 favorite-rpc 没有 —— 两条机制不能混用。故只能追加版本。
--
-- ---------------------------------------------------------------------------
-- 本文件的职责：把「Go 侧有、RAG 侧容易漏」的资产**幂等补齐**。
-- 全部使用 IF NOT EXISTS / DO $$ 守卫 ⇒ 对已经正确的库是**全空操作**，
-- 对从 Go 侧迁库过来的库（AutoMigrate 产物，缺 CHECK/触发器）是**精确修补**。
-- ---------------------------------------------------------------------------

-- ===== ① favorite_item.target_revision（Go 003）=====
-- 合并进 V2 建表了；此处只为「从 Go 库迁过来」的库兜底。
ALTER TABLE favorite_item
    ADD COLUMN IF NOT EXISTS target_revision varchar(96);

-- 🔴 语义（data_model.go:25-26，逐字）：
--   "Nil means the source had no provable published revision when this favorite was created.
--    In particular, do not backfill legacy rows."
-- ⇒ **只加列，不回填**。本文件刻意不含任何 UPDATE。

-- ===== ② favorite_item.target_id 类型收敛为 varchar(255)（Go db.go:43-63）=====
-- Go 侧有一段 migrateFavoriteTargetIDToString：探测 information_schema，
-- 若不是 character varying / text 就 ALTER ... TYPE varchar(255) USING target_id::varchar。
-- RAG 侧新建库由 V2 直接建成 varchar(255)；但从 Go 旧库迁过来的库可能仍是 bigint。
-- ⚠️ article 雪花 ID > 2^53，在 bigint 列上虽然放得下，但任何经过 JSON 数字的路径都会丢精度
--    （这正是 favorite 把 business ID 编码成十进制字符串的原因，见 FavoriteFactEnvelopeFactory）。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'favorite_item'
          AND column_name = 'target_id'
          AND data_type NOT IN ('character varying', 'text')
    ) THEN
        ALTER TABLE favorite_item
            ALTER COLUMN target_id TYPE varchar(255) USING target_id::varchar;
    END IF;
END $$;

-- ===== ③ favorite_item 的 target_revision 作用域 CHECK（Go favorite_fact.go:138）=====
-- Go 的 FavoriteLegacyFactMarker 表带这条复合 CHECK（004:15），
-- 但 **favorite_item 本体没有** —— 这里是**有意加强**：应用层在
-- FavoriteFactEnvelopeFactory.build 的前置断言里已经拦了同一条规则
--   (item.TargetRevision != nil && (item.TargetType != "article" || *item.TargetRevision == ""))
--     ⇒ invalid favorite fact
-- 把它提到数据库层，可以让绕过应用层的写入也拿不到非法的修订号。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'favorite_item_target_revision_scope'
    ) THEN
        ALTER TABLE favorite_item
            ADD CONSTRAINT favorite_item_target_revision_scope
            CHECK (target_revision IS NULL OR (target_type = 'article' AND target_revision <> ''));
    END IF;
END $$;

-- ===== ④ favorite_fact_outbox：CHECK + 部分索引 + 单列索引（Go 001）=====
-- AutoMigrate（db.go:33）建不出这三项；它们只存在于 Go 的手写 001。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'favorite_fact_outbox_aggregate_version_range'
    ) THEN
        ALTER TABLE favorite_fact_outbox
            ADD CONSTRAINT favorite_fact_outbox_aggregate_version_range
            CHECK (aggregate_version IN (1, 2));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_favorite_fact_version'
    ) THEN
        ALTER TABLE favorite_fact_outbox
            ADD CONSTRAINT uk_favorite_fact_version UNIQUE (favorite_id, aggregate_version);
    END IF;
END $$;

-- 服务于 DispatchFavoriteFactOnce 的取件查询（favorite_delivery.go:141-147）
CREATE INDEX IF NOT EXISTS favorite_fact_outbox_pending
    ON favorite_fact_outbox (status, created_at)
    WHERE status IN (0, 2);

CREATE INDEX IF NOT EXISTS idx_favorite_fact_outbox_status
    ON favorite_fact_outbox (status);

-- ===== ⑤ 两个不可变触发器（Go 002 / 004）=====
-- ⚠️ 函数体与触发器形式**逐字照抄 Go**（BEFORE UPDATE / FOR EACH ROW，非 constraint trigger）。
--   DROP TRIGGER IF EXISTS + CREATE 是幂等的：重跑本文件不会报错，也不会改触发器语义。
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

CREATE OR REPLACE FUNCTION reject_favorite_legacy_marker_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'favorite legacy fact marker is immutable';
END;
$$;

DROP TRIGGER IF EXISTS favorite_legacy_marker_immutable ON favorite_legacy_fact_marker;
CREATE TRIGGER favorite_legacy_marker_immutable
BEFORE UPDATE OR DELETE ON favorite_legacy_fact_marker
FOR EACH ROW EXECUTE FUNCTION reject_favorite_legacy_marker_change();

-- ===== ⑥ legacy 标记表的 5 个单列 CHECK（Go 004）=====
-- 同 ③ 的理由：AutoMigrate 不覆盖该表，这张表**完全**靠 V4/本文件的显式 SQL 存在。
-- 对从 Go 库迁过来、且当时已跑过 004 的库，这几项已存在 ⇒ 全空操作。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_favorite_id_positive') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_favorite_id_positive CHECK (favorite_id > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_user_id_positive') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_user_id_positive CHECK (user_id > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_folder_id_positive') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_folder_id_positive CHECK (folder_id > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_target_type_nonempty') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_target_type_nonempty CHECK (target_type <> '');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_target_id_nonempty') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_target_id_nonempty CHECK (target_id <> '');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_revision_scope') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_revision_scope
            CHECK (target_revision IS NULL OR (target_type = 'article' AND target_revision <> ''));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_snapshot_hash_format') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_snapshot_hash_format
            CHECK (source_snapshot_sha256 ~ '^[a-f0-9]{64}$');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorite_legacy_marker_approval_ref_nonempty') THEN
        ALTER TABLE favorite_legacy_fact_marker
            ADD CONSTRAINT favorite_legacy_marker_approval_ref_nonempty CHECK (approval_ref <> '');
    END IF;
END $$;