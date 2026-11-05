--
-- ============================================================================
-- RAG-V1 · favorite 域 · Flyway 迁移脚本 V3__create_favorite_fact_outbox.sql
--
-- C 侧落点: service/favorite/src/main/resources/db/migration/V3__create_favorite_fact_outbox.sql
-- B 侧出处: /Users/pr/Documents/java&golang/RAG/service/favorite/favorite-rpc/
--             src/main/resources/db/migration/V3__create_favorite_fact_outbox.sql:1-63
--
-- 本文件建: favorite_fact_outbox 表
--           (CHECK aggregate_version IN (1,2) + UNIQUE uk_favorite_fact_version
--            + 部分索引 favorite_fact_outbox_pending + idx_favorite_fact_outbox_status)
--
-- 搬运方式: **逐字搬运，未改 SQL 文本** —— 除本文件头注释外，本文件与 B 侧同名文件
--           **逐字节一致**：表名 / 列名 / 列类型 / 长度 / NOT NULL / DEFAULT /
--           UNIQUE / CHECK / 索引 / 触发器 / 函数体 / 全部原有注释，均照抄未改。
-- 基准依据: AGENTS.md §三（L1 契约层以 B 侧为准 · D16）·
--           docs/Mapping_Table/favorite/favorite.md §2.1（4 张表逐表对照）
--
-- ⚠️ 幂等性: 保留 B 侧 `CREATE TABLE IF NOT EXISTS` / `CREATE ... INDEX IF NOT EXISTS`
--           等幂等写法，未改写成裸 `CREATE TABLE`（B 侧是 C 侧 DDL 事实源）。
-- ⚠️ 账本:   依赖 `spring.flyway.table=flyway_schema_history_favorite` +
--           `baseline-on-migrate: true` + `baseline-version: 0`
--           （B 侧出处: favorite-rpc/src/main/resources/application.yml:104-129）。
--           ⇒ C 侧 `service/favorite/src/main/resources/application.yml` 当前**无**
--             `spring.flyway` 段 ⇒ 属**待拍板项**（见本次交付报告），本文件未改 yml。
-- ⚠️ 文件名: `V3__*` 编号是**已发布的迁移历史**（Flyway 按版本号排序），
--           原样保留，不重排、不改名（否则 checksum mismatch ⇒ 启动失败）。
-- ============================================================================
--
-- V3__create_favorite_fact_outbox.sql
-- 对应 Go 侧 DDL 事实源之二：001_favorite_fact_outbox.sql（**逐字搬运，不是重设计**）
--   Sea-RideTheWind_golang/service/favorite/rpc/internal/model/001_favorite_fact_outbox.sql:1-15
--
-- ⚠️⚠️ 本文件是「AutoMigrate 覆盖不到的那一半」的起点。Go 侧 db.go:33 的
--   AutoMigrate(&FavoriteFolder{}, &FavoriteItem{}, &FavoriteFactOutbox{})
-- **不会**创建下面两项：
--   ① CHECK (aggregate_version IN (1,2))
--   ② 部分索引 favorite_fact_outbox_pending
-- 两者都只存在于这个手写 SQL 里。漏掉 ⇒ 静默降级（见文件末说明）。
--
-- 本次 V3 只建 001 的内容；002 的 5 个回执列 + 不可变触发器在 V5（保持与 Go 的编号一致）。
CREATE TABLE IF NOT EXISTS favorite_fact_outbox (
    -- Go: EventID string `gorm:"primaryKey;type:varchar(128)"`
    event_id          varchar(128) PRIMARY KEY,
    -- Go: FavoriteID int64 `gorm:"not null;uniqueIndex:uk_favorite_fact_version"`
    favorite_id       bigint      NOT NULL,
    -- Go: AggregateVersion int64 `gorm:"not null;uniqueIndex:uk_favorite_fact_version"`
    aggregate_version bigint      NOT NULL,
    -- Go: Payload string `gorm:"type:jsonb;not null"`
    --   ⚠️ jsonb 而非 json：favorite 从不按 JSON 表达式查这一列，但 DC 的 input_hash
    --   是对**原始字节**做的 JCS+SHA256（favorite_authority.go:179-186），
    --   jsonb 会在 PG 侧规范化存储，读回时已是 PG 的规范化形式 —— 两端都读同一列，所以自洽。
    payload           jsonb       NOT NULL,
    -- Go: Status int32 `gorm:"type:smallint;not null;default:0;index"`
    --   0=pending 1=sent 2=failed 3=blocked（favorite_fact.go:15-20，对齐 Wire.DeliveryStatus）
    status            smallint    NOT NULL DEFAULT 0,
    -- Go: RetryCount int32 `gorm:"not null;default:0"`
    retry_count       integer     NOT NULL DEFAULT 0,
    -- Go: CreatedAt time.Time `gorm:"autoCreateTime;not null"` ← 001 的 DEFAULT now()
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- Go: UpdatedAt time.Time `gorm:"autoUpdateTime;not null"` ← 001 的 DEFAULT now()
    updated_at        timestamptz NOT NULL DEFAULT now(),

    -- 🔴 ① CHECK：aggregate_version 只允许 1（assert）或 2（retract）。
    --    Go 侧逐字：CHECK (aggregate_version IN (1,2))。
    --    语义：FavoriteFactEnvelopeFactory 的前置断言里
    --      (version != 1 && version != 2) ⇒ invalid favorite fact（favorite_fact.go:137）
    --    应用层已经拦了一道；这个 CHECK 是**第二道**，防止任何绕过应用层的写入
    --    （人工 SQL / 未来的第二个写入方）造出 v3 这种无法解释的事实。
    CONSTRAINT favorite_fact_outbox_aggregate_version_range CHECK (aggregate_version IN (1, 2)),

    -- Go 001 的 UNIQUE (favorite_id, aggregate_version)
    -- 🔴 这是「一个收藏聚合的每个版本恰好一个冻结事实」的物理保证，
    --    也是 InsertFavorite 的 23505 会被翻译成 AlreadyExists/5302 的那一个约束。
    CONSTRAINT uk_favorite_fact_version UNIQUE (favorite_id, aggregate_version)
);

-- 🔴 ② 部分索引（Go 001:14-15）：
--      ON favorite_fact_outbox(status,created_at) WHERE status IN (0,2)
--    它精确服务于 DispatchFavoriteFactOnce 的取件查询（favorite_delivery.go:141-147）：
--      WHERE status IN (0,2) AND (aggregate_version=1 OR EXISTS(前驱已 status=1))
--        ORDER BY created_at, event_id ... FOR UPDATE SKIP LOCKED LIMIT 1
--    部分条件把「已接受 / 已阻塞」的行排除在索引外 ⇒ 该查询不会退化成全表扫，
--    且 SKIP LOCKED 在多副本下能各取各的。
CREATE INDEX IF NOT EXISTS favorite_fact_outbox_pending
    ON favorite_fact_outbox (status, created_at)
    WHERE status IN (0, 2);

-- Go struct 的 `index` tag（favorite_fact.go:65）对应的单列索引。
CREATE INDEX IF NOT EXISTS idx_favorite_fact_outbox_status
    ON favorite_fact_outbox (status);