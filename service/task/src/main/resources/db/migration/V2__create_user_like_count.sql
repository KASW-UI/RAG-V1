--
-- V2__create_user_like_count.sql
-- 对应 Go 端 service/task/rpc/internal/Model/taskModel.go: UserLikeCount
-- （model/taskModel.go:43-50 与 sink/like_sink.go:17-25 是同一张表的两个结构体定义）
-- 由 service_context.go:47 的 AutoMigrate 建出。
--
-- ⚠️ **user_id 在本表是 TEXT，在 task_progress 里是 BIGINT** —— 这是 Go 的现状，不是笔误：
--   · Model.UserLikeCount.UserID 是 string
--   · Model.TaskProgress.UserID 是 int64
--   两张表都由同一批 upsert 写入，但列类型不同。保持原样，不要"顺手统一"成 BIGINT：
--   改了会让 Go 侧写入的 user_id 在 Java 侧查询时类型不匹配，或反过来。
--
-- 写路径只有一处：like_sink.go 的 upsertChunk（flushPostgres 的批量 upsert），
--   语义是 GREATEST(旧值, 新值) —— 计数只增不减。
-- 读路径：**task-rpc 自己不读本表**，它只被数据侧消费。

CREATE TABLE IF NOT EXISTS user_like_count (
    -- Go: UserID string `gorm:"primary_key;column:user_id"` ⇒ TEXT（无 size tag）
    user_id     TEXT        NOT NULL,
    -- Go: LikeCount int64 `gorm:"column:like_count"`
    like_count  BIGINT,
    -- Go: UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`
    updated_at  TIMESTAMPTZ,
    CONSTRAINT pk_user_like_count PRIMARY KEY (user_id)
);

-- 同 V1：不加 CHECK、不加额外索引。
-- GREATEST 的幂等性依赖主键冲突，而不是唯一索引或约束 —— ON CONFLICT (user_id) 即可。