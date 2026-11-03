--
-- V2__create_like_consume_inbox.sql
-- 对应 Go 版 service/like/rpc/internal/model/data_model.go: LikeConsumeInbox
--
-- 这张表是**消费幂等的唯一权威**：MQ 每条消息先在这里落一行 status=0，
-- 整条业务写入（inbox / like_record / domain_fact）成功后才把 status 改成 1。
-- 重复投递时 ProcessLikeMessageBatch 靠 msg_id 主键冲突 + status 判定来决定是跳过还是重放。
--

CREATE TABLE IF NOT EXISTS like_consume_inbox (
    msg_id        VARCHAR(64)  PRIMARY KEY,   -- Go: MsgId string `primaryKey;type:varchar(64)`
    payload_hash  VARCHAR(64)  NOT NULL DEFAULT '',  -- Go: PayloadHash string `type:varchar(64);not null;default:''`
    topic         VARCHAR(64)  NOT NULL,      -- Go: Topic string `type:varchar(64);not null`
    consumer      VARCHAR(64)  NOT NULL,      -- Go: Consumer string `type:varchar(64);not null`
    status        SMALLINT     NOT NULL DEFAULT 0,   -- Go: Status int32 `type:smallint;not null;default:0`（0处理中 1已完成）
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- Go: `index:idx_topic_consumer`（Topic 与 Consumer 两列都带 tag ⇒ 复合索引）
CREATE INDEX IF NOT EXISTS idx_topic_consumer
    ON like_consume_inbox (topic, consumer);
-- ---------------------------------------------------------------------------
-- 📌 C 侧落点（B 出处 · AGENTS.md §3.2 三段式）：
--    ① **比 B 侧多做了什么**：无。本文件**逐字节照抄** B 侧
--       service/like/like-rpc/src/main/resources/db/migration/V2__create_like_consume_inbox.sql
--       ⇒ 表名 / 列型 / NOT NULL / 可空性 / 索引名 / 约束名 逐条一致。
--    ② **语义还一样吗**：一样。schema 是 L1 契约 —— 两侧连**同一个 PG 库、同一个 public schema**
--       ⇒ DDL 必须逐字一致，否则灰度期读写的是**结构不同的同一张表**。
--    ③ **B 出处**：Go `service/like/rpc/internal/model/data_model.go: LikeConsumeInbox`。
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
