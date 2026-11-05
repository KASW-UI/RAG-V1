--
-- ============================================================================
-- RAG-V1 · favorite 域 · Flyway 迁移脚本 V1__create_favorite_folder.sql
--
-- C 侧落点: service/favorite/src/main/resources/db/migration/V1__create_favorite_folder.sql
-- B 侧出处: /Users/pr/Documents/java&golang/RAG/service/favorite/favorite-rpc/
--             src/main/resources/db/migration/V1__create_favorite_folder.sql:1-43
--
-- 本文件建: favorite_folder 表 + 3 个索引
--           (uk_favorite_folder_folder_id / uk_user_folder_name / idx_favorite_folder_user_id)
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
-- ⚠️ 文件名: `V1__*` 编号是**已发布的迁移历史**（Flyway 按版本号排序），
--           原样保留，不重排、不改名（否则 checksum mismatch ⇒ 启动失败）。
-- ============================================================================
--
-- V1__create_favorite_folder.sql
-- 对应 Go 侧 DDL 事实源之一：gorm AutoMigrate 的 FavoriteFolder 实体
--   Sea-RideTheWind_golang/service/favorite/rpc/internal/model/data_model.go:5-16
--   由 internal/model/db.go:33  db.AutoMigrate(&FavoriteFolder{}, &FavoriteItem{}, &FavoriteFactOutbox{}) 产出
--
-- 列类型依据不是 struct tag 的字面，而是 GORM 的类型推断链（AGENTS.md §3.3.1：证据要指向消费点）：
--   ① gorm.io/driver/postgres 对 Go 的 uint64 / int64 → bigint
--   ② tag 里的 type:varchar(N) 逐字照抄
--   ③ time.Time → timestamptz（PG driver 默认）
--   ④ 未标 not null 的 string（Name 标了，Id 走自增）按 GORM 惯例
--
-- ⚠️ 本表**不含** CHECK / 触发器（Go 侧也没有）；favorite 域的两个不可变触发器在 V5 / V4。
CREATE TABLE IF NOT EXISTS favorite_folder (
    -- Go: Id uint64 `gorm:"primaryKey"` —— 自增代理键，业务从不使用
    id           bigserial   PRIMARY KEY,
    -- Go: FolderId int64 `gorm:"column:folder_id;uniqueIndex;not null"`
    folder_id    bigint      NOT NULL,
    -- Go: UserId int64 `gorm:"column:user_id;not null;index;uniqueIndex:uk_user_folder_name"`
    user_id      bigint      NOT NULL,
    -- Go: Name string `gorm:"column:name;type:varchar(100);not null;uniqueIndex:uk_user_folder_name"`
    name         varchar(100) NOT NULL,
    -- Go: CreateTime time.Time `gorm:"column:create_time;autoCreateTime"`
    create_time  timestamptz,
    -- Go: UpdateTime time.Time `gorm:"column:update_time;autoUpdateTime"`
    update_time  timestamptz
);

-- 🔴 uk_user_folder_name 是 L2 业务语义，不是性能索引：
--   CreateFavoriteFolderLogic（create_favorite_folder_logic.go:75-87）先查重再报 AlreadyExists/5203，
--   InsertFolder 的 23505 也被翻译成同一个码（:105-109）。
--   删掉这个约束 ⇒ 并发下两个同名收藏夹都能建出来，重名保护失效。
CREATE UNIQUE INDEX IF NOT EXISTS uk_favorite_folder_folder_id
    ON favorite_folder (folder_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_folder_name
    ON favorite_folder (user_id, name);

CREATE INDEX IF NOT EXISTS idx_favorite_folder_user_id
    ON favorite_folder (user_id);

-- ⚠️ GORM 的命名：column 上的 uniqueIndex 生成 idx_<table>_<column>；
--   复合索引名直接取 tag 里的名字。保持与 Go AutoMigrate 产物同名，便于两侧对拍。