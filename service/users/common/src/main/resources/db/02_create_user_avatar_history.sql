-- V2__create_user_avatar_history.sql
--
-- 对应 Go: service/user/model/user.go 的 UserAvatarHistory struct
-- 表名: users_user_avatar_history（HUMAN_ONLY.MD 2026-10-22 修改后的版本）
--
-- 列结构：
--   id           BIGSERIAL PRIMARY KEY
--   history_id   TEXT NOT NULL UNIQUE           -- 服务端 Snowflake 生成的字符串 ID（D-013）
--   uid          BIGINT NOT NULL                  -- 索引（Go gorm:"column:uid;index;not null"）
--   avatar_url   TEXT NOT NULL
--   content_type TEXT
--   size_bytes   BIGINT
--   is_current   BOOLEAN NOT NULL DEFAULT FALSE   -- 索引（Go 端 gorm:"column:is_current;index;not null;default:false"）
--   create_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
--   update_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP

-- ⚠️ 2026-10-22（E-3 根因 R3）：字符串列从 VARCHAR(255) 改成 **TEXT**。
--   原注释里的「与 @Column 注解严格对齐」正是**漂移的来源**：Go 的 GORM AutoMigrate
--   给 Go `string` 字段生成的是 `text`（无上限），不是 varchar(255)。
--   `ALTER ... TYPE text` 对已存在的列恒成功且无需 USING 子句，可反复重放。

CREATE TABLE IF NOT EXISTS users_user_avatar_history (
    id           BIGSERIAL PRIMARY KEY,
    history_id   TEXT NOT NULL UNIQUE,
    uid          BIGINT NOT NULL,
    avatar_url   TEXT NOT NULL,
    content_type TEXT,
    size_bytes   BIGINT,
    is_current   BOOLEAN NOT NULL DEFAULT FALSE,
    create_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 索引：业务逻辑按 uid 查 + 按 (uid, is_current) 找当前头像
CREATE INDEX IF NOT EXISTS idx_users_user_avatar_history_uid
    ON users_user_avatar_history (uid);
CREATE INDEX IF NOT EXISTS idx_users_user_avatar_history_uid_is_current
    ON users_user_avatar_history (uid, is_current);

-- R3 自愈：把历史上按 VARCHAR(255) 建出来的列就地放宽成 TEXT（幂等，可反复重放）
ALTER TABLE users_user_avatar_history ALTER COLUMN history_id   TYPE text;
ALTER TABLE users_user_avatar_history ALTER COLUMN avatar_url   TYPE text;
ALTER TABLE users_user_avatar_history ALTER COLUMN content_type TYPE text;

-- ---------------------------------------------------------------------------
-- 2026-10-23（DRIFT-USERRPC-V-004 的**可观察半边**）：时间列改成 TIMESTAMPTZ
--
-- 本表是 users 域**唯一 wire 可见**的时间列载体：
--   proto `AvatarHistoryItem.create_time`（users.proto:76，string）直接来自本表的
--   create_time ⇒ 渲染形态是**客户端可见字符串**，不是纯 DB 层差异。
--
-- Go 出处（声明点 + 消费点）：
--   声明点  service/user/user/rpc/internal/model/data_model.go:30-31
--           `CreateTime time.Time `gorm:"column:create_time;autoCreateTime"``，**无 type tag**
--   消费点  gorm.io/driver/postgres@v1.6.0/postgres.go:251-256
--           case schema.Time: if field.Precision > 0 { … } ; return "timestamptz"
--           ⇒ 无 precision 一律 **timestamptz**
--   渲染点  service/user/user/rpc/internal/logic/avatarhelpers.go:16-19
--           `if !item.CreateTime.IsZero() { createTime = item.CreateTime.Format(time.RFC3339) }`
--
-- ⚠️⚠️ **2026-10-23 实测更正了旧台账的一条错误归因**（别再照抄旧说法）：
--   旧台账写「DSN 钉 TimeZone=Asia/Shanghai ⇒ Go 渲染带偏移」。
--   **实测不成立**：把 Go **进程**的 TZ 改成 UTC（DSN 的 TimeZone=Asia/Shanghai 原样不动），
--   Go 渲染的 create_time 立刻变成 `…Z`（双侧活体对拍，2026-10-23 21:02）。
--   ⇒ 决定偏移量的是**进程本地时区**（Go 的 `time.Local`），**不是** PG 会话时区。
--   源码依据：pgx v5.10.0 `pgtype/timestamptz.go:270-276`
--       tim := time.Unix(…); if plan.location != nil { tim = tim.In(plan.location) }
--       —— `TimestamptzCodec.ScanLocation` 默认 nil（:133-137）⇒ 落回 `time.Local`；
--       且 pgx 走**二进制**解码，PG 会话时区压根不参与。
--   ⇒ 对 RAG 侧的推论：pgjdbc 同样把 timestamptz 解到 **JVM 默认时区**，
--   所以只要两侧进程时区相同，两侧渲染就逐字同形 ⇒ **RAG 侧不要在 JDBC URL 上钉 TimeZone**，
--   钉了反而会让 RAG 依赖配置、Go 依赖进程环境，两者在配置≠环境时分叉。
--
-- ⚠️ **必须与「实体 LocalDateTime ⇒ OffsetDateTime」在同一个提交里改**：
--   timestamptz 列配 LocalDateTime 实体 ⇒ JVM 默认时区参与换算，
--   把「列类型不一致」换成「值算错」——比现状更糟。
--
-- ⚠️ **USING 子句不能省**（PG 的 timestamp → timestamptz 不是隐式转换），
--   且**必须先把会话时区钉成 UTC**：
--     `timestamptz AT TIME ZONE 'UTC'` 对**已经是 timestamptz** 的列并不恒等
--     （会话时区非 UTC 时，每启动一次整列时刻就偏移一次，且永不收敛）。
--   ⇒ 下面三条把会话 TZ 显式钉成 UTC 再转，转完 RESET 还原 ⇒ 两条路径都恒等。
--   （与 01_create_user.sql 的同款写法；**不要**改成 `DO $$ … $$` 条件块 ——
--     SchemaInitializer 用 spring-jdbc ScriptUtils 切分语句，不支持 PG 美元引号。）
SET TIME ZONE 'UTC';

ALTER TABLE users_user_avatar_history ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE users_user_avatar_history ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';

RESET TIME ZONE;