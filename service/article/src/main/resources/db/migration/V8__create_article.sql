--
-- V8__create_article.sql
-- 对应 Go 端 service/article/rpc/internal/model/article.sql + GORM AutoMigrate(&Article{})
-- 由 GORM struct tag 生成，逐列对齐。
--
-- ⚠️ 三处最容易抄错的地方：
--   1. 表名是 **单数** `article`（Go 的 NamingStrategy{SingularTable: true}）。
--      MyBatis-Plus 的默认策略也是下划线+复数以外的原文名，但这里必须用 @TableName 显式钉死。
--   2. secondary_tags / ext_info 是 **jsonb**（Go 自定义类型 StringArray / JSONMap，
--      tag 写死 `gorm:"type:jsonb"`），不是 text、不是 json。
--      ⚠️ 这是 AGENTS.md §0 DDL 台账里「2 处 text→jsonb」中不属于 users 的那一类，
--      本域 Go 侧本来就是 jsonb，Java 侧保持 jsonb（两侧一致，无漂移）。
--   3. created_at / updated_at / deleted_at 都是 **timestamptz**（Go time.Time 在 PG 上的默认类型），
--      不是 timestamp。JVM 非 UTC 时区时这三列的读写都会偏移一个时区差
--      （同 message 域 DRIFT-MSG-V-007 的成因），因此这里显式写 timestamptz。
--
-- ⚠️ GORM AutoMigrate 不会给这些列加 NOT NULL：Go 的 DDL 里除 id / title 外都是可空。
-- 下面照抄 article.sql 的可空性，不"顺手收紧"约束——收紧会让 Go 侧原本能写入的行在 Java 侧被拒，
-- 属于新增行为（AGENTS.md §15「不要擅自修复 Go 的行为」）。

CREATE TABLE IF NOT EXISTS article (
    id              VARCHAR(32)   NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    brief           VARCHAR(512),
    content         TEXT,
    cover_image_url VARCHAR(255),
    manual_type_tag VARCHAR(64),
    secondary_tags  JSONB,
    author_id       VARCHAR(32),
    status          SMALLINT      DEFAULT 0,
    view_count      INTEGER       DEFAULT 0,
    like_count      INTEGER       DEFAULT 0,
    comment_count   INTEGER       DEFAULT 0,
    share_count     INTEGER       DEFAULT 0,
    ext_info        JSONB,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    PRIMARY KEY (id)
);

-- Go: `ManualTypeTag string `gorm:"type:varchar(64);index"``
CREATE INDEX IF NOT EXISTS idx_article_manual_type_tag ON article (manual_type_tag);

-- Go: `AuthorID string `gorm:"type:varchar(32);index"``
CREATE INDEX IF NOT EXISTS idx_article_author_id ON article (author_id);

-- Go: `DeletedAt gorm.DeletedAt `gorm:"index"``（软删除列的索引）
CREATE INDEX IF NOT EXISTS idx_article_deleted_at ON article (deleted_at);

COMMENT ON TABLE article IS '文章主表';
COMMENT ON COLUMN article.id IS '文章ID (Snowflake)';
COMMENT ON COLUMN article.content IS 'Markdown正文';
COMMENT ON COLUMN article.secondary_tags IS '二级标签 (JSON Array)';
COMMENT ON COLUMN article.status IS '状态: 0-未指定, 1-草稿, 2-已发布, 3-审核中, 4-拒绝';
COMMENT ON COLUMN article.ext_info IS '扩展字段 (JSON Map)';

--
-- ⚠️ 不额外为 secondary_tags 建 GIN 索引。
-- Go 侧 ArticleRepo.List 的二级标签过滤是 `secondary_tags @> '["tag"]'`（model/article.go:146），
-- 但 Go 的 article.sql 从未建过对应 GIN 索引，002_community_revision.sql 也没建。
-- 补一个 GIN 索引属于「新增 Go 侧不存在的结构」——性能优化，不在本次迁移范围内
-- （AGENTS.md §20「不要顺手修无关性能优化」）。
--