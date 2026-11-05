--
-- ============================================================================
-- RAG-V1 · favorite 域 · Flyway 迁移脚本 V2__create_favorite_item.sql
--
-- C 侧落点: service/favorite/src/main/resources/db/migration/V2__create_favorite_item.sql
-- B 侧出处: /Users/pr/Documents/java&golang/RAG/service/favorite/favorite-rpc/
--             src/main/resources/db/migration/V2__create_favorite_item.sql:1-72
--
-- 本文件建: favorite_item 表 + 6 个索引
--           (含唯一键 uk_folder_target(folder_id, target_id, target_type))
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
-- ⚠️ 文件名: `V2__*` 编号是**已发布的迁移历史**（Flyway 按版本号排序），
--           原样保留，不重排、不改名（否则 checksum mismatch ⇒ 启动失败）。
-- ============================================================================
--
-- V2__create_favorite_item.sql
-- 对应 Go 侧 DDL 事实源之一：AutoMigrate 的 FavoriteItem 实体（data_model.go:18-35）
-- **并把 Go 的 003_favorite_target_revision.sql 合并进来**（003 只有 1 条 ADD COLUMN，
--   与建表合并后语义完全等价，且避免对一张刚建的表做 ALTER）。
--
-- 🔴 本表有三个「不能照直觉填」的列，逐条说明：
--
--  ① target_id 是 **varchar(255)**，不是 bigint。
--     Go 侧 db.go:43-63 还专门有一段 migrateFavoriteTargetIDToString 去修「已有 bigint 列的旧库」。
--     RAG 侧是新建库 ⇒ 直接建 varchar(255)，**不需要**那段一次性修复。
--     ⚠️ 若这里建成 bigint，article 雪花 ID（> 2^53）会直接溢出。
--
--  ② target_revision **可空，且绝不回填**。
--     Go data_model.go:25-26 的注释是硬约束：
--       "Nil means the source had no provable published revision when this favorite
--        was created. In particular, do not backfill legacy rows."
--     一个旧的 title/status/当前指针**无法**重建收藏当时观察到的修订 ⇒ 回填就是造假。
--     DDL 的复合 CHECK（见 V6）把这条规则钉进数据库。
--
--  ③ **没有 update_time**。
--     FavoriteItem 只有 create_time（data_model.go:30）。误加一列会让
--     "收藏条目不可变" 这条约定在 schema 层消失。
CREATE TABLE IF NOT EXISTS favorite_item (
    id         bigserial    PRIMARY KEY,
    -- Go: FavoriteId int64 `gorm:"column:favorite_id;uniqueIndex;not null"`
    favorite_id bigint      NOT NULL,
    -- Go: FolderId int64 `gorm:"column:folder_id;not null;index;uniqueIndex:uk_folder_target"`
    folder_id  bigint       NOT NULL,
    -- Go: UserId int64 `gorm:"column:user_id;not null;index"`
    user_id    bigint       NOT NULL,
    -- Go: TargetId string `gorm:"column:target_id;type:varchar(255);not null;index;uniqueIndex:uk_folder_target"`
    target_id  varchar(255) NOT NULL,
    -- Go: TargetType string `gorm:"column:target_type;type:varchar(50);not null;index;uniqueIndex:uk_folder_target"`
    target_type varchar(50) NOT NULL,
    -- Go: TargetRevision *string `gorm:"column:target_revision;type:varchar(96)"`
    --   ← 合并 Go 003_favorite_target_revision.sql 的 ADD COLUMN
    target_revision varchar(96),
    -- Go: Title string `gorm:"column:title;type:varchar(255)"` —— 未标 not null ⇒ 可空
    title      varchar(255),
    -- Go: Cover string `gorm:"column:cover;type:varchar(500)"`
    cover      varchar(500),
    -- Go: CreateTime time.Time `gorm:"column:create_time;autoCreateTime"`
    create_time timestamptz
);

-- 🔴 uk_folder_target 是「同收藏夹内同一目标只能收藏一次」的**唯一物理保证**。
--   create_favorite_logic.go:122-134 先查重（命中 ⇒ AlreadyExists/5302），
--   InsertFavorite 的 23505 也被翻译成同一个码（:157-161）。
--   ⚠️ 注意 target_type 在库里存的是**已小写化**的值：
--      helper.go:60-62 normalizeTargetType = strings.ToLower(strings.TrimSpace(...))
--      ⇒ 唯一键建在规范化之后的值上，"Article" 与 "article" 是同一条。
CREATE UNIQUE INDEX IF NOT EXISTS uk_favorite_item_favorite_id
    ON favorite_item (favorite_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_folder_target
    ON favorite_item (folder_id, target_id, target_type);

CREATE INDEX IF NOT EXISTS idx_favorite_item_folder_id
    ON favorite_item (folder_id);

CREATE INDEX IF NOT EXISTS idx_favorite_item_user_id
    ON favorite_item (user_id);

CREATE INDEX IF NOT EXISTS idx_favorite_item_target_id
    ON favorite_item (target_id);

CREATE INDEX IF NOT EXISTS idx_favorite_item_target_type
    ON favorite_item (target_type);

-- ⚠️ favorite_item **没有** update_time（见文件头 ③）。
--   删除收藏条目走 DeleteFavoriteByFavoriteId（物理删）+ v2 retract 事实，不走软删。