-- ============================================================
-- 迁移归属（DRIFT-NEW-015，2026-10-21）
--
-- 本文件原是 admin/admin-rpc 的 V2__create_admin_invite.sql，重编号为 **V4**。
-- 收归 users-common 单一迁移目录的原因见同目录 V3__create_admin.sql 的头部说明。
--
-- Go 出处：对应 Go service/user/admin/rpc/internal/model/data_model.go 的
-- AdminInvite struct。⚠️ **表名两侧不同（不是笔误）**：Go 的 TableName() 返回
-- **admin_invites**（data_model.go:31-33），本脚本建的是 **admin_invite**
-- ⇒ users 域统一的「表名加前缀」约定（同族的还有 admins → admin_admin、
-- users → users_user）。⚠️ 原注释写「GORM 默认表名 admin_invite」是**错的**：
-- GORM 默认名是 admin_invites，admin_invite 是 RAG 侧按域约定起的名。
--
-- ⚠️ CreateAdmin 的 4 步事务里对这张表做 W1 行级悲观锁（SELECT ... FOR UPDATE），
--    所以 code 列上的唯一约束与索引都不能退化。
-- ============================================================
-- V2__create_admin_invite.sql
--
-- 对应 Go 端 internal/model/data_model.go 的 AdminInvite struct（Go 的 TableName() 返回 admin_invites）。
-- admin-rpc invite 表的 DDL：admin_invite（⚠️ 两侧表名不同，见文件头说明）
--
-- 列结构：
--   id           BIGSERIAL PRIMARY KEY
--   code         TEXT NOT NULL UNIQUE             -- 前缀 "ADM-" + 8 字节 hex（大写）
--   inviter_uid  BIGINT NOT NULL                  -- 创建邀请码的 admin uid
--   expires_at   TIMESTAMPTZ NOT NULL
--   used_by_uid  BIGINT                           -- nullable，使用该 invite 的新 admin uid
--   used_at      TIMESTAMPTZ                      -- nullable，使用时间
--   create_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
--   update_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP

-- ⚠️ 2026-10-22（E-3 根因 R3）：字符串列从 VARCHAR(255) 改成 **TEXT**。
--   原注释里的「与 @Column 注解严格对齐」正是**漂移的来源**：Go 的 GORM AutoMigrate
--   给 Go `string` 字段生成的是 `text`（无上限），不是 varchar(255)。
--   `ALTER ... TYPE text` 对已存在的列恒成功且无需 USING 子句，可反复重放。

CREATE TABLE IF NOT EXISTS admin_invite (
    id           BIGSERIAL PRIMARY KEY,
    code         TEXT NOT NULL UNIQUE,
    inviter_uid  BIGINT NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_by_uid  BIGINT,
    used_at      TIMESTAMPTZ,
    create_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 索引：业务逻辑按 code 查（CreateAdmin 4 步事务 W1 悲观锁）
CREATE INDEX IF NOT EXISTS idx_admin_invite_code ON admin_invite (code);

-- R3 自愈：把历史上按 VARCHAR(255) 建出来的列就地放宽成 TEXT（幂等，可反复重放）
ALTER TABLE admin_invite ALTER COLUMN code TYPE text;

-- ============================================================================
-- 2026-10-23（DRIFT-ARPC-004 ②，用户 22:0x 拍板「DDL 与实体一起改」）：4 个时间列 → TIMESTAMPTZ
-- ============================================================================
--
-- Go 出处：service/user/admin/rpc/internal/model/data_model.go:24,26,27,28
--   ExpiresAt / UsedAt / CreateTime / UpdateTime 全是 time.Time，**无 type tag**
--   ⇒ gorm.io/driver/postgres@v1.6.0/postgres.go:251-256 → "timestamptz"
--
-- **四列里只有 expires_at 会咬人**，逐列论证（其余三列改了是纯 DDL 对齐，零行为变化）：
--
--   expires_at —— 🔴 真的可观察
--     消费点（两侧结构同构，但比较的**语义**不同）：
--       Go  createadminlogic.go:64-68  now := time.Now()
--                                     if invite.UsedAt != nil || !invite.ExpiresAt.After(now)
--       RAG AdminCreateAtomicService:124-127（同构，LocalDateTime 版）
--     Go 的 invite.ExpiresAt 来自 timestamptz ⇒ **带 location 的绝对时刻**，
--     `.After(now)` 比的是**绝对时刻**。
--     RAG 原先的 LocalDateTime 来自 timestamp ⇒ 是**墙钟**，
--     `.isAfter(now)` 比的是**墙钟字符串**。
--     ⇒ 单实例同时区下两者恰好等价（写读都在同一个 JVM 默认时区里自洽），
--        **跨时区多实例部署时会分叉**：
--        实例 A（Asia/Shanghai）创建邀请码，存下上海墙钟 12:00；
--        实例 B（UTC）消费时 now=04:00 ⇒ `04:00.isAfter(12:00)` 为 false
--        ⇒ **邀请码刚生成就被判为已过期**。Go 侧不会。
--     配套改动：实体 LocalDateTime ⇒ OffsetDateTime（否则列改了、实体没改，
--     等于把「列类型不一致」换成「值算错」，比现状更糟 —— 01_create_user.sql:74-76 同款警告）。
--
--   used_at / create_time / update_time —— ⚠️ 不可观察
--     · admin.proto 里**没有**这三个字段（全 13 个 rpc message 逐条核对过）；
--     · used_at 在 RAG 侧只做 `!= null` 判定（AdminCreateAtomicService:125），
--       不参与大小比较 ⇒ 带不带偏移量结果相同。
--     ⇒ 改它们是**纯 DDL 对齐**，零可观察差异，但必须一起改：
--       同一张表里一半 timestamptz 一半 timestamp 才是真正的病灶。
--
-- ⚠️ 幂等性：**不能直接 ALTER**（同 03_create_admin.sql:80-94 与 01_create_user.sql:80-94
--   已实测踩过）—— `timestamptz AT TIME ZONE 'UTC'` 对已经是 timestamptz 的列不恒等，
--   会话时区非 UTC 时**每启动一次整列偏移一次且永不收敛**。
--   `DO $$ … $$` 条件守卫也**实测不可行**：SchemaInitializer 用 spring-jdbc ScriptUtils
--   按 `;` 切句，不支持 PG 美元引号（常量池无 `$$`）⇒ DDL 语法错 ⇒ 两个 rpc 都起不来。
--   ⇒ 沿用「把会话 TZ 钉成 UTC 再转，转完 RESET」：首迁与重放两条路径都恒等。
SET TIME ZONE 'UTC';

ALTER TABLE admin_invite ALTER COLUMN expires_at  TYPE TIMESTAMPTZ USING expires_at  AT TIME ZONE 'UTC';
ALTER TABLE admin_invite ALTER COLUMN used_at     TYPE TIMESTAMPTZ USING used_at     AT TIME ZONE 'UTC';
ALTER TABLE admin_invite ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE admin_invite ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';

RESET TIME ZONE;
