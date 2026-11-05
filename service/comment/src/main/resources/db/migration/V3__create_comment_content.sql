--
-- V3__create_comment_content.sql
-- 对应 Go 版 service/comment/rpc/internal/model/data_model.go:42-53 的 CommentContent
--
-- 「评论正文的宽表拆分」：comment_index 存统计（点赞数/回复数/状态），comment_content 存正文。
-- 二者靠 comment_id ↔ id 一一对应，GetComment 分两次批量读再**按下标配对**
-- （get_comment_logic.go:122-143）。
--
-- ⚠️ meta 是 **jsonb**（Go tag 就是 `type:jsonb`），Java 侧挂 JsonbTypeHandler。
--    写入前不校验 JSON 合法性 —— Go 侧也不校验（normalizeCommentMeta 只保证是合法 JSON，
--    见 comment.go:222-240）。
-- ⚠️ comment_id 同样**不自增**：它是 CommentIndex.Id（雪花 ID），不是新序列。

CREATE TABLE IF NOT EXISTS comment_content (
    comment_id  BIGINT      PRIMARY KEY,        -- Go: CommentId int64 `primaryKey;autoIncrement:false`
    content     TEXT        NOT NULL,           -- Go: Content   string `type:text;not null` 评论正文
    meta        JSONB,                          -- Go: Meta      string `type:jsonb` 扩展内容（可空）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- Go: CreatedAt time.Time `autoCreateTime`
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP    -- Go: UpdatedAt time.Time `autoUpdateTime`
);