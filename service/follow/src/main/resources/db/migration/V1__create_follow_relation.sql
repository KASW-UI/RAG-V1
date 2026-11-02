--
-- V1__create_follow_relation.sql
-- 对应 Go 版 service/follow/rpc/internal/model/data_model.go: FollowRelation
-- 替代 Go 侧 db.go:42 的 db.AutoMigrate(&FollowRelation{})，列 / 索引 / 约束逐条对齐 GORM 产物。
--
-- Go struct（data_model.go:5-15）：
--   ID         uint64    `gorm:"primaryKey"`
--   UserId     int64     `gorm:"column:user_id;not null;index;uniqueIndex:uk_follow_pair"`
--   TargetId   int64     `gorm:"column:target_id;not null;index;uniqueIndex:uk_follow_pair"`
--   CreateTime time.Time `gorm:"column:create_time;autoCreateTime"`
--   UpdateTime time.Time `gorm:"column:update_time;autoUpdateTime"`
--   func (FollowRelation) TableName() string { return "follow_relation" }
--

CREATE TABLE IF NOT EXISTS follow_relation (
    -- GORM：整数主键默认 AUTOINCREMENT ⇒ bigserial，插入时由 PG 序列分配。
    --     Go struct 上没有显式 autoIncrement tag，但 GORM 对整数 PK 默认开启，
    --     所以这里必须是 serial 族；若写成 BIGINT 不带序列，Go 侧 CreateFollow 会插入 id=0。
    id          BIGSERIAL   PRIMARY KEY,

    -- GORM：int64 → bigint；`not null` ⇒ NOT NULL
    user_id     BIGINT      NOT NULL,

    -- 同上
    target_id   BIGINT      NOT NULL,

    -- GORM postgres：Go time.Time → timestamptz（不是 timestamp）。
    --     ⚠️ 这里用 TIMESTAMP 会让 Java 侧读出的墙上时间与 Go 差一个时区偏移，
    --     而 create_time 是 ORDER BY 的排序键（见 ListFollowTargets 的 create_time desc）。
    --     两处都没有 not null tag ⇒ 保持可空。
    create_time TIMESTAMPTZ,
    update_time TIMESTAMPTZ
);

-- 对应 Go 端 `gorm:"...;index"`（单列索引）：GORM 命名规则为 idx_<表名>_<列名>。
CREATE INDEX IF NOT EXISTS idx_follow_relation_user_id
    ON follow_relation (user_id);

CREATE INDEX IF NOT EXISTS idx_follow_relation_target_id
    ON follow_relation (target_id);

-- 对应 Go 端 `gorm:"...;uniqueIndex:uk_follow_pair"`。
-- ⚠️ 这条**不是**优化，是 CreateFollow 幂等性的物理载体：
--    logic Follow 路径的 INSERT 走 ON CONFLICT (user_id, target_id) DO NOTHING
--    （见 FollowRelationMapper#insertIgnoreConflict），冲突目标就是这条约束。
--    删掉它 ⇒ ON CONFLICT 报错 ⇒ 重复关注从「静默成功」变成「5008 database update failed」。
CREATE UNIQUE INDEX IF NOT EXISTS uk_follow_pair
    ON follow_relation (user_id, target_id);

-- ---------------------------------------------------------------------------
-- 📌 C 侧落点（B 出处 · AGENTS.md §3.2）：
--    本文件**逐字照抄** B 侧
--    service/follow/follow-rpc/src/main/resources/db/migration/V1__create_follow_relation.sql
--    ⇒ 表名 / 列型 / NOT NULL / 可空性 / 单列索引名 / 复合唯一索引名 逐条一致。
--    ⚠️ 本文件与「Flyway 账本表名」无关（那是 `spring.flyway.table` 的配置问题，
--    follow 域**尚未拍板** —— 见 AGENTS.md §六「其余 7 域待拍板」）。
-- ---------------------------------------------------------------------------
