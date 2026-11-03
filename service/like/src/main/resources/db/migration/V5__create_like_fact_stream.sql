--
-- V5__create_like_fact_stream.sql
-- 对应 Go 版 service/like/rpc/internal/model/domain_fact.go: LikeFactStream
--
-- 只有两列，但它是**事实流版本号的唯一权威**：appendLikeFact 每次发事实前
--   INSERT INTO like_fact_stream (aggregate_id, version) VALUES (?, 1)
--   ON CONFLICT (aggregate_id) DO UPDATE SET version = like_fact_stream.version + 1
--   RETURNING aggregate_id, version
-- 原子递增并取回新版本。
--
-- ⚠️ 这条 SQL 是 Go 侧 appendLikeFact 手写的裸 SQL，MyBatis-Plus 的任何 Wrapper 都表达不了
--    （RETURNING + ON CONFLICT + 自引用递增），必须用 @Select 注解原生 SQL。
--    见 LikeDomainFactMapper#bumpFactStreamVersion。
--

CREATE TABLE IF NOT EXISTS like_fact_stream (
    aggregate_id  VARCHAR(128) PRIMARY KEY,  -- Go: AggregateID string `column:aggregate_id;primaryKey;type:varchar(128)`
    version       BIGINT        NOT NULL     -- Go: Version int64
);
-- ---------------------------------------------------------------------------
-- 📌 C 侧落点（B 出处 · AGENTS.md §3.2 三段式）：
--    ① **比 B 侧多做了什么**：无。本文件**逐字节照抄** B 侧
--       service/like/like-rpc/src/main/resources/db/migration/V5__create_like_fact_stream.sql
--       ⇒ 表名 / 列型 / NOT NULL / 可空性 / 索引名 / 约束名 逐条一致。
--    ② **语义还一样吗**：一样。schema 是 L1 契约 —— 两侧连**同一个 PG 库、同一个 public schema**
--       ⇒ DDL 必须逐字一致，否则灰度期读写的是**结构不同的同一张表**。
--    ③ **B 出处**：Go `service/like/rpc/internal/model/domain_fact.go: LikeFactStream`。
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
