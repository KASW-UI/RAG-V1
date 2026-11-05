--
-- V1__create_task_progress.sql
-- 对应 Go 端 service/task/rpc/internal/Model/taskModel.go: TaskProgress
-- 由 service_context.go:47 的 `gdb.AutoMigrate(&Model.TaskProgress{}, &Model.UserLikeCount{})` 建出。
--
-- ⚠️ 逐列类型按 GORM 的「无 size tag ⇒ 取最宽类型」规则对齐，不要按直觉改成 integer / varchar：
--   · int64  ⇒ BIGINT（GORM 对无 type tag 的整数一律 bigint）
--   · string ⇒ TEXT（GORM 对无 size tag 的字符串一律 text）
--   · time.Time / *time.Time ⇒ TIMESTAMPTZ（GORM 在 PG 上把 time.Time 映射成 timestamptz，
--     不是 timestamp）—— 这条直接决定非 UTC 时区下的 Instant 换算。
--   · 没有一个字段带 `not null` tag ⇒ 除主键外全部可空。
--
-- 本表是 task 域两张表里**唯一被写路径与读路径共用**的一张：
--   写：like_sink.go / article_sink.go 的 lazyInit（DO NOTHING）、
--       updateDoingTaskProgress（CASE + GREATEST）、markTaskDoneInDB（done + COALESCE）
--   读：get_task_logic.go:53-57 的 GetTask 回源查询
-- ⇒ (user_id, task_id) 复合主键不是形式主义：三条 upsert 的 ON CONFLICT 全部依赖它。
--
-- ⚠️ Go 的 TaskDef 表**没有**被 AutoMigrate（它是 model/taskModel.go:22-25 的代码常量），
--   因此本目录不应出现 task_def 表 —— 任务定义在 Java 侧同样是代码常量，见 TaskDefs。
--

CREATE TABLE IF NOT EXISTS task_progress (
    -- Go: UserID int64 `gorm:"primary_key;column:user_id"`
    user_id     BIGINT       NOT NULL,
    -- Go: TaskID int64 `gorm:"primary_key;column:task_id"`
    task_id     BIGINT       NOT NULL,
    -- Go: Status string `gorm:"column:status"`；取值 doing / done（无 CHECK，见文件末尾说明）
    status      TEXT,
    -- Go: Progress int64 `gorm:"column:progress"`
    progress    BIGINT,
    -- Go: Target int64 `gorm:"column:target"`
    target      BIGINT,
    -- Go: DoneAt *time.Time `gorm:"column:done_at"` —— 指针，可空
    done_at     TIMESTAMPTZ,
    -- Go: CreatedAt time.Time `gorm:"column:created_at;autoCreateTime"`
    created_at  TIMESTAMPTZ,
    -- Go: UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`
    updated_at  TIMESTAMPTZ,
    CONSTRAINT pk_task_progress PRIMARY KEY (user_id, task_id)
);

-- 不额外加 CHECK 约束：GORM AutoMigrate 不会为无 tag 的列生成 CHECK，
-- 加了就变成 Java 侧独有的约束 —— 那会让 Go 侧原本能写入的行在 Java 侧被拒，
-- 属于**新增行为**，而不是等价迁移。同理不加索引：Go 侧只有
-- `WHERE user_id = ?` 这一条查询，复合主键的左前缀已经覆盖。