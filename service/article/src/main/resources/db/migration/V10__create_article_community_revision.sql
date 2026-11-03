--
-- V10__create_article_community_revision.sql
-- 逐字对应 Go 端 service/article/rpc/internal/model/002_community_revision.sql
-- （WS02-B：community 发布权威表，不是第二张知识库发布表）。
--
-- ⚠️ 本文件**必须整段照抄**，包括 CHECK 约束、唯一索引与不可变触发器。
-- 三者都参与可观察行为：
--   1. article_revision 的 BEFORE UPDATE OR DELETE 触发器 → "已发布 revision 不可变"这条
--      **唯一的强制手段**。Go 侧的 TestArticlePublishedRevisionsStaleResultAndRetraction
--      直接断言 tampered 更新必须失败（community_article_test.go:224-227）。
--      没有它，"已发布字节不可变"就退化成一句注释。
--   2. article_domain_outbox 的 UNIQUE(aggregate_id, aggregate_version) → H03 事件顺序保证。
--   3. article_publication.state 的 CHECK(state IN ('published','retracted'))。
--

CREATE TABLE IF NOT EXISTS article_revision (
    article_id      VARCHAR(32)  NOT NULL,
    revision        BIGINT       NOT NULL CHECK (revision > 0),
    revision_id     VARCHAR(96)  NOT NULL UNIQUE,
    author_id       VARCHAR(32)  NOT NULL,
    source_object   TEXT         NOT NULL,
    content_sha256  CHAR(64)     NOT NULL,
    title           VARCHAR(255) NOT NULL,
    brief           VARCHAR(512),
    cover_image_url VARCHAR(255),
    manual_type_tag VARCHAR(64),
    secondary_tags  JSONB,
    markdown        TEXT         NOT NULL,
    published_at    TIMESTAMPTZ  NOT NULL,
    sync_event_id   VARCHAR(64)  NOT NULL UNIQUE,
    PRIMARY KEY (article_id, revision)
);

CREATE TABLE IF NOT EXISTS article_publication (
    article_id        VARCHAR(32) PRIMARY KEY,
    current_revision  VARCHAR(96),
    pointer_version   BIGINT      NOT NULL CHECK (pointer_version > 0),
    state             VARCHAR(16) NOT NULL CHECK (state IN ('published', 'retracted')),
    last_event_id     VARCHAR(128) NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS article_domain_outbox (
    event_id          VARCHAR(128) PRIMARY KEY,
    aggregate_id      VARCHAR(32)  NOT NULL,
    aggregate_version BIGINT       NOT NULL CHECK (aggregate_version > 0),
    event_type        VARCHAR(64)  NOT NULL,
    payload           JSONB        NOT NULL,
    status            SMALLINT     NOT NULL DEFAULT 0,
    retry_count       INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (aggregate_id, aggregate_version)
);

CREATE INDEX IF NOT EXISTS article_domain_outbox_pending
    ON article_domain_outbox (status, created_at) WHERE status IN (0, 2);

-- ===== 不可变触发器 =====
CREATE OR REPLACE FUNCTION reject_article_revision_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'article revision is immutable';
END;
$$;

DROP TRIGGER IF EXISTS article_revision_immutable ON article_revision;
CREATE TRIGGER article_revision_immutable
    BEFORE UPDATE OR DELETE ON article_revision
    FOR EACH ROW EXECUTE FUNCTION reject_article_revision_mutation();

COMMENT ON TABLE article_revision IS '不可变的已批准社区文章版本；article_revision_immutable 触发器强制其不可变';
COMMENT ON TABLE article_publication IS 'source 域指针：新审核在自身结果被接受前不改变它；retract 推进 pointer_version';
COMMENT ON TABLE article_domain_outbox IS 'H03 域事件发件箱；UNIQUE(aggregate_id,aggregate_version) 保证事件顺序';