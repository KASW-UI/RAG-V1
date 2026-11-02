--
-- V2__create_block_relation.sql
-- 对应 Go 版 service/follow/rpc/internal/model/data_model.go: BlockRelation
-- 替代 Go 侧 db.go:42 的 db.AutoMigrate(&BlockRelation{})。
--
-- 与 follow_relation 结构同构，唯一差别是唯一索引名叫 uk_block_pair
-- （GORM 的 uniqueIndex 名字是按 struct 分别指定的，不是按表名推导）。
--

CREATE TABLE IF NOT EXISTS block_relation (
    -- 同 V1：整数 PK 默认 AUTOINCREMENT ⇒ bigserial
    id          BIGSERIAL   PRIMARY KEY,

    -- GORM：int64 → bigint；`not null` ⇒ NOT NULL
    user_id     BIGINT      NOT NULL,

    -- 同上
    target_id   BIGINT      NOT NULL,

    -- GORM postgres：Go time.Time → timestamptz；两处无 not null tag ⇒ 可空
    create_time TIMESTAMPTZ,
    update_time TIMESTAMPTZ
);

-- 对应 Go 端 `gorm:"...;index"`（单列索引）
CREATE INDEX IF NOT EXISTS idx_block_relation_user_id
    ON block_relation (user_id);

-- ⚠️ 这条索引**直接决定 ExistsAnyBlock 的计划形状**：
--    该查询是 `WHERE (user_id=? AND target_id=?) OR (user_id=? AND target_id=?)`
--    （model/follow.go:64-73），双向匹配 ⇒ 两条单列索引各命中一半，
--    比全表扫快几个数量级。它是 Follow / Block 的第一道闸门。
CREATE INDEX IF NOT EXISTS idx_block_relation_target_id
    ON block_relation (target_id);

-- 对应 Go 端 `gorm:"...;uniqueIndex:uk_block_pair"`。
-- ⚠️ 与 V1 同理：Block 路径的 INSERT 走 ON CONFLICT (user_id, target_id) DO NOTHING，
--    重复拉黑必须是「静默成功」而不是 5008。
--    另外 GetRecommendations 的 ListAllBlockTargets / ListBlockerUsers 分别按
--    user_id desc-create_time 与 target_id 查，本索引同时覆盖这两个方向的定位。
CREATE UNIQUE INDEX IF NOT EXISTS uk_block_pair
    ON block_relation (user_id, target_id);

-- ---------------------------------------------------------------------------
-- 📌 C 侧落点（B 出处 · AGENTS.md §3.2）：
--    本文件**逐字照抄** B 侧
--    service/follow/follow-rpc/src/main/resources/db/migration/V2__create_block_relation.sql
--    ⇒ 表名 / 列型 / NOT NULL / 可空性 / 单列索引名 / 复合唯一索引名 逐条一致。
--    ⚠️ 本文件与「Flyway 账本表名」无关（那是 `spring.flyway.table` 的配置问题，
--    follow 域**尚未拍板** —— 见 AGENTS.md §六「其余 7 域待拍板」）。
-- ---------------------------------------------------------------------------
