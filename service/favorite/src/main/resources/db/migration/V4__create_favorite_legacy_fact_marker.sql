--
-- ============================================================================
-- RAG-V1 · favorite 域 · Flyway 迁移脚本 V4__create_favorite_legacy_fact_marker.sql
--
-- C 侧落点: service/favorite/src/main/resources/db/migration/V4__create_favorite_legacy_fact_marker.sql
-- B 侧出处: /Users/pr/Documents/java&golang/RAG/service/favorite/favorite-rpc/
--             src/main/resources/db/migration/V4__create_favorite_legacy_fact_marker.sql:1-75
--
-- 本文件建: favorite_legacy_fact_marker 表（8 个 CHECK）+ 不可变触发器
--           favorite_legacy_marker_immutable
--           🔴 **L1 资产**：本表缺失 ⇒ 错误码静默从 FailedPrecondition/5304
--              变成 Internal/5008，且**只有历史数据才暴露** ⇒ 必须逐字搬全。
--
-- 搬运方式: **逐字搬运，未改 SQL 文本** —— 除本文件头注释外，本文件与 B 侧同名文件
--           **逐字节一致**：表名 / 列名 / 列类型 / 长度 / NOT NULL / DEFAULT /
--           UNIQUE / CHECK / 索引 / 触发器 / 函数体 / 全部原有注释，均照抄未改。
-- 基准依据: AGENTS.md §三（L1 契约层以 B 侧为准 · D16）·
--           docs/Mapping_Table/favorite/favorite.md §2.1（4 张表逐表对照）
--
-- ⚠️ 幂等性: 保留 B 侧 `CREATE TABLE IF NOT EXISTS` / `DROP TRIGGER IF EXISTS` +
--           `CREATE OR REPLACE FUNCTION` 等幂等写法，未改写成裸 `CREATE TABLE`
--           （B 侧是 C 侧 DDL 事实源）。
-- ⚠️ 账本:   依赖 `spring.flyway.table=flyway_schema_history_favorite` +
--           `baseline-on-migrate: true` + `baseline-version: 0`
--           （B 侧出处: favorite-rpc/src/main/resources/application.yml:104-129）。
--           ⇒ C 侧 `service/favorite/src/main/resources/application.yml` 当前**无**
--             `spring.flyway` 段 ⇒ 属**待拍板项**（见本次交付报告），本文件未改 yml。
-- ⚠️ 文件名: `V4__*` 编号是**已发布的迁移历史**（Flyway 按版本号排序），
--           原样保留，不重排、不改名（否则 checksum mismatch ⇒ 启动失败）。
-- ============================================================================
--
-- V4__create_favorite_legacy_fact_marker.sql
-- 对应 Go 侧 DDL 事实源之二：004_favorite_legacy_fact_marker.sql（**逐字搬运**）
--   Sea-RideTheWind_golang/service/favorite/rpc/internal/model/004_favorite_legacy_fact_marker.sql:1-27
--
-- ⚠️⚠️ 这张表**只由本文件创建**，Go 的 AutoMigrate 完全不覆盖它
--   （db.go:33 只 AutoMigrate 三个实体，FavoriteLegacyFactMarker 不在其中）。
--   漏建的后果**不是**「少个功能」，而是**错误码变了**：
--     未建表时，favoriteAssertionSchema（favorite_fact.go:220-226）走 legacy 分支
--     会拿到 PG 的 `relation "favorite_legacy_fact_marker" does not exist`，
--     那个错误**不是** gorm.ErrRecordNotFound ⇒ 走 :224-225 返回普通 DB 错误
--     ⇒ DeleteFavoriteLogic 的 errors.Is(ErrFavoriteFactMigrationBlocked) 为假
--     ⇒ 输出 **Internal/5008**，而设计意图是 **FailedPrecondition/5304**。
--   ⚠️ 但只有「该 favorite 没有 v1 断言」的行会触发（:214-215 先查 v1），
--     新建收藏有 v1 断言 ⇒ 不受影响。**这让它极难在联调里发现。**
--
-- 迁移脚本刻意**不自动插入任何标记**（Go 004 的注释）：
--   "This migration creates NO markers automatically: absence of an assertion
--    cannot distinguish legitimate old data from a modern write failure.
--    The migration owner inserts reviewed snapshots only."
CREATE TABLE IF NOT EXISTS favorite_legacy_fact_marker (
    favorite_id           bigint       PRIMARY KEY,
    user_id               bigint       NOT NULL,
    folder_id             bigint       NOT NULL,
    target_type           text         NOT NULL,
    target_id             text         NOT NULL,
    target_revision       varchar(96),
    source_snapshot_sha256 char(64)    NOT NULL,
    approval_ref          text         NOT NULL,
    approved_at           timestamptz  NOT NULL DEFAULT now(),

    -- ↓↓↓ Go 004:9-15 的 5 个单列 CHECK + 1 个复合 CHECK，逐字搬运 ↓↓↓
    CONSTRAINT favorite_legacy_marker_favorite_id_positive CHECK (favorite_id > 0),
    CONSTRAINT favorite_legacy_marker_user_id_positive     CHECK (user_id > 0),
    CONSTRAINT favorite_legacy_marker_folder_id_positive   CHECK (folder_id > 0),
    CONSTRAINT favorite_legacy_marker_target_type_nonempty CHECK (target_type <> ''),
    CONSTRAINT favorite_legacy_marker_target_id_nonempty   CHECK (target_id <> ''),
    -- 复合 CHECK：target_revision 一旦非空，就必须是一个 article 的非空修订号。
    -- 语义与 favorite_fact.go:138 的应用层断言逐字同构：
    --   (item.TargetRevision != nil && (item.TargetType != "article" || *item.TargetRevision == ""))
    --     ⇒ invalid favorite fact
    CONSTRAINT favorite_legacy_marker_revision_scope CHECK (
        target_revision IS NULL OR (target_type = 'article' AND target_revision <> '')
    ),

    -- Go 004:12 内联在列定义里的正则 CHECK：
    --   source_snapshot_sha256 char(64) NOT NULL CHECK (source_snapshot_sha256 ~ '^[a-f0-9]{64}$')
    -- 与 favoriteAssertionSchema 的 favoriteReceiptHash.MatchString 是同一条正则
    --   （favorite_delivery.go:20），两处独立表达同一条约束，缺一不可。
    CONSTRAINT favorite_legacy_marker_snapshot_hash_format
        CHECK (source_snapshot_sha256 ~ '^[a-f0-9]{64}$'),

    -- Go 004:14 内联的 CHECK (approval_ref <> '')
    CONSTRAINT favorite_legacy_marker_approval_ref_nonempty CHECK (approval_ref <> '')
);

-- 🔴 不可变触发器（Go 004:18-27）：marker 在**批准之后不可改、不可删**。
--   为什么比 outbox 的更严（这里连 DELETE 都禁）：
--     marker 是「这条历史数据确实是合法的 pre-Outbox 旧行」的唯一人工凭证。
--     一旦可删，迁移负责人就能事后给自己开的后门抹掉证据，
--     favoriteAssertionSchema 的 legacy 分支就退化成一个可绕过的开关。
--
-- ⚠️ 函数体与触发器形式**逐字照抄 Go**（BEFORE UPDATE OR DELETE / FOR EACH ROW，
--   非 constraint trigger）：改形式会让 RAISE 的时机（行改写前 vs 提交时）不同。
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