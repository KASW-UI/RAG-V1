--
-- V1__create_like_record.sql
-- 对应 Go 版 service/like/rpc/internal/model/data_model.go: LikeRecord
-- 由 GORM AutoMigrate 依据 struct tag 生成，逐列对齐。
--
-- ⚠️ 三处最容易抄错的地方（都已在下面就地注明）：
--   1. State 是 int32 + `type:smallint` ⇒ SMALLINT；而 ID/UserID/AuthorID/LastOperationID
--      没有 type tag，GORM 对「无 size 的整数」一律取 **bigint**，不是 integer。
--   2. 所有时间列是 timestamptz（GORM 的 time.Time 在 PG 上的默认类型），不是 timestamp。
--      这条直接关系到 JVM 非 UTC 时区时的时间戳偏移。
--   3. delete_at 可空 + 带索引（GORM DeletedAt 软删除）。
--

CREATE TABLE IF NOT EXISTS like_record (
    id                 BIGSERIAL    PRIMARY KEY,   -- Go: ID int64 `primaryKey;autoIncrement`
    user_id            BIGINT       NOT NULL,      -- Go: UserID int64 `type:bigint;not null`
    target_type        VARCHAR(32)  NOT NULL,      -- Go: TargetType string `type:varchar(32);not null`
    target_id          VARCHAR(64)  NOT NULL,      -- Go: TargetID string `type:varchar(64);not null`
    author_id          BIGINT       NOT NULL,      -- Go: AuthorID int64 `type:bigint;not null`
    state              SMALLINT     NOT NULL DEFAULT 0,  -- Go: State int32 `type:smallint;default:0`
    last_operation_id  BIGINT       NOT NULL DEFAULT 0,  -- Go: LastOperationID int64 `type:bigint;default:0`
    created_at         TIMESTAMPTZ  NOT NULL,      -- Go: CreatedAt time.Time `autoCreateTime;not null`
    updated_at         TIMESTAMPTZ  NOT NULL,      -- Go: UpdatedAt time.Time `autoUpdateTime;not null`
    delete_at          TIMESTAMPTZ                 -- Go: DeleteAt gorm.DeletedAt（软删除，可空）
);

-- Go: `uniqueIndex:uk_user_target,priority:1|2|3`
-- 语义作用：保证 (点赞者, 目标类型, 目标ID) 唯一 —— MQ 消费侧的 ON CONFLICT DO NOTHING 幂等
-- 与 applyLikeMessage 里的 SELECT ... FOR UPDATE 行锁都建立在这个唯一索引上。
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_target
    ON like_record (user_id, target_type, target_id);

-- Go: `index:idx_user_list,priority:1|2`
-- 覆盖 GetUserLikeList 的 WHERE user_id=? AND target_type=? AND state=1 ORDER BY id DESC
CREATE INDEX IF NOT EXISTS idx_user_list
    ON like_record (user_id, updated_at);

-- Go: `index:idx_target_list,priority:1|2`
-- 覆盖 GetTargetLikerList 的 WHERE target_type=? AND target_id=? AND state=1 ORDER BY id DESC
CREATE INDEX IF NOT EXISTS idx_target_list
    ON like_record (target_type, target_id);

-- Go: `index:idx_author`
-- 覆盖 GetTotalLikeCount 的 WHERE author_id=? AND state=1
CREATE INDEX IF NOT EXISTS idx_author
    ON like_record (author_id);

-- Go: `DeleteAt gorm.DeletedAt \`gorm:"index"\``
CREATE INDEX IF NOT EXISTS idx_like_record_deleted_at
    ON like_record (delete_at);

-- 不额外加 CHECK 约束：GORM AutoMigrate 不会为无 tag 的数值列生成 CHECK，
-- 加了就变成 Java 侧独有的约束（可能让 Go 侧原本能写入的行在 Java 侧被拒，属于新增行为）。
-- ---------------------------------------------------------------------------
-- 📌 C 侧落点（B 出处 · AGENTS.md §3.2 三段式）：
--    ① **比 B 侧多做了什么**：无。本文件**逐字节照抄** B 侧
--       service/like/like-rpc/src/main/resources/db/migration/V1__create_like_record.sql
--       ⇒ 表名 / 列型 / NOT NULL / 可空性 / 索引名 / 约束名 逐条一致。
--    ② **语义还一样吗**：一样。schema 是 L1 契约 —— 两侧连**同一个 PG 库、同一个 public schema**
--       ⇒ DDL 必须逐字一致，否则灰度期读写的是**结构不同的同一张表**。
--    ③ **B 出处**：Go `service/like/rpc/internal/model/data_model.go: LikeRecord`（GORM AutoMigrate 的产物）。
--
-- ⚠️ **执行机制 = Flyway**（C 侧已定）：`spring.flyway.table=flyway_schema_history_like`
--    （**独立账本**：AGENTS.md §六 —— `flyway_schema_history` 是 **schema 级**账本，一个 schema 只能有一本
--    ⇒ 与其它域共用默认账本 ⇒ 版本号撞车（like_record vs follow_relation …）且内容不同
--    ⇒ 后启动的域把自己的迁移判成「已应用」而**静默跳过**）。
--    ⇒ `spring:` 下的 4 项必须齐：`enabled` / `baseline-on-migrate: true` /
--      `baseline-version: 0` / `table: flyway_schema_history_like`；
--    ⚠️ 放在**顶层** ⇒ 键名变成 `flyway.*` ⇒ Spring Boot **读不到** ⇒ 4 项**静默失效**。
--    ⚠️ `baseline-version` 必须钉 **0**：本模块与其它域连同一个库 ⇒ 先启动的域已把 public schema 弄非空
--    ⇒ 本模块启动时看到「非空 schema + 没有自己的账本」⇒ 因 baseline-on-migrate=true ⇒
--      **默认在 V1 处打 baseline 标记**（默认 baseline-version=1）⇒ **V1 的 SQL 从未执行**
--      ⇒ `like_record` 表永远建不出来 ⇒ 后续依赖它的迁移报 `relation does not exist` ⇒ 起不来。
--      钉 0 ⇒ baseline 打在 version 0 ⇒ V1 之后全部迁移照常执行。
--    ⚠️ 「全新空库 + 单进程」的测试**测不出**这个坑（schema 空 ⇒ baseline 根本不触发）
--    ⇒ 必须显式把 schema 弄成非空才暴露（守门见 `docs/DRIFT/like/rpc.md` 的 Flyway 段）。
-- ---------------------------------------------------------------------------
