--
-- V3__create_like_outbox_event.sql
-- 对应 Go 版 service/like/rpc/internal/model/data_model.go: LikeOutboxEvent
--
-- 「首次点赞」事件的本地事务发件箱：MQ 消费侧在**同一个 DB 事务内**写入本表，
-- 由 like-mq 进程的 outbox relay 每秒扫一次 status IN (0,2) AND retry_count < 3
-- 推进 HotEventPusher（topic=article-hot-events）。
--
-- ⚠️ payload 列类型是 **json**，不是 jsonb。
--    Go tag 是 `type:json`（like_domain_fact_outbox 才是 jsonb），两者不可混用：
--    json 保留输入原文，jsonb 会重排/去重键。虽然 ArticleHotEvent 序列化结果一致，
--    但列类型属于 L1 schema 契约，灰度期两侧读写的是同一张表。
--

CREATE TABLE IF NOT EXISTS like_outbox_event (
    event_id     VARCHAR(64)  PRIMARY KEY,   -- Go: EventID string `primaryKey;type:varchar(64)`
    event_key    VARCHAR(128) NOT NULL,      -- Go: EventKey string `uniqueIndex:uk_event_key`
    event_type   VARCHAR(64)  NOT NULL,      -- Go: EventType string `index`
    aggregate_id VARCHAR(64)  NOT NULL,      -- Go: AggregateID string `index`
    payload      JSON         NOT NULL,      -- Go: Payload string `type:json;not null`
    status       SMALLINT     NOT NULL DEFAULT 0,   -- Go: Status int32 `type:smallint;default:0`（0待发送 1已发送 2发送失败）
    retry_count  INTEGER      NOT NULL DEFAULT 0,   -- Go: RetryCount int32 `type:int;default:0`
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

-- Go: `uniqueIndex:uk_event_key` —— 事件业务幂等键（形如 first_like:{userId}:{type}:{id}）
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_key
    ON like_outbox_event (event_key);

CREATE INDEX IF NOT EXISTS idx_like_outbox_event_event_type
    ON like_outbox_event (event_type);

CREATE INDEX IF NOT EXISTS idx_like_outbox_event_aggregate_id
    ON like_outbox_event (aggregate_id);

CREATE INDEX IF NOT EXISTS idx_like_outbox_event_status
    ON like_outbox_event (status);
-- ---------------------------------------------------------------------------
-- 📌 C 侧落点（B 出处 · AGENTS.md §3.2 三段式）：
--    ① **比 B 侧多做了什么**：无。本文件**逐字节照抄** B 侧
--       service/like/like-rpc/src/main/resources/db/migration/V3__create_like_outbox_event.sql
--       ⇒ 表名 / 列型 / NOT NULL / 可空性 / 索引名 / 约束名 逐条一致。
--    ② **语义还一样吗**：一样。schema 是 L1 契约 —— 两侧连**同一个 PG 库、同一个 public schema**
--       ⇒ DDL 必须逐字一致，否则灰度期读写的是**结构不同的同一张表**。
--    ③ **B 出处**：Go `service/like/rpc/internal/model/data_model.go: LikeOutboxEvent`。
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
