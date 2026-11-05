--
-- V7__create_comment_fact_stream.sql
-- 对应 Go 版 migrations/002_comment_dc_wire.sql:8-11 的 comment_fact_stream
-- ＋ Go service/comment/rpc/internal/model/domain_fact.go:36-40 的 CommentFactStream
--
-- 只有两列，但它是**事实流版本号的唯一权威**。appendCommentFact（domain_fact.go:115-120）
-- 每次发事实前执行：
--
--   INSERT INTO comment_fact_stream (aggregate_id, version) VALUES (?, 1)
--   ON CONFLICT (aggregate_id) DO UPDATE SET version = comment_fact_stream.version + 1
--   RETURNING aggregate_id, version
--
-- 原子递增并取回新版本。⚠️ 注意 `version = comment_fact_stream.version + 1`
-- 里的**表名限定前缀**：PG 的 ON CONFLICT DO UPDATE 里必须用表名限定才能引用既有行值
-- （写成裸 `version + 1` 会报「column reference is ambiguous」）。
-- Java 侧逐字保留，见 CommentFactStreamMapper#bumpVersion。
--
-- ⚠️ 这条 SQL 是 Go 侧 appendCommentFact 手写的裸 SQL：
--    ON CONFLICT + 自引用递增 + RETURNING 三者叠加，MyBatis-Plus 的任何 Wrapper 都表达不了，
--    必须用 @Select 注解原生 SQL（PG 协议层 executeQuery 对返回行数的语句一视同仁，
--    INSERT…RETURNING 同样能映射成结果集）。本模块的 CommentFactStreamContractTest
--    会在真 PG 上验证这条路径。

CREATE TABLE IF NOT EXISTS comment_fact_stream (
    aggregate_id VARCHAR(128) PRIMARY KEY,   -- Go: AggregateID string `column:aggregate_id;primaryKey;type:varchar(128)`
    version      BIGINT NOT NULL             -- Go: Version int64 `not null`
        -- Go 002:10 的行内 CHECK 逐字照抄。
        -- ⚠️ 语义约束不是性能约束：version 要进 JSON 信封（delivery_envelope.aggregate_version），
        --    上界 2^53-1 是 JSON 安全整数上限，超出会被下游 JSON 解析器静默丢精度。
        --    版本列上界为 2^53-1 = 9007199254740991。
        CONSTRAINT comment_fact_stream_version_range CHECK (version BETWEEN 1 AND 9007199254740991)
);