-- ============================================================
-- 迁移归属（DRIFT-NEW-015，2026-10-21）
--
-- 本文件原是 admin/admin-rpc 的 V1__create_admin.sql，重编号为 **V3**。
-- 全部 users 域 DDL 收归 users-common 的这一个目录，原因：
--   admin-rpc **也读写 users_user 表**（BanUser / DeleteUser / ResetUserPassword /
--   UpdateUser 业务），所以它与 user-rpc **必须连同一个库**；
--   而 flyway_schema_history 挂在 schema 上、一个 schema 只能有一本账。
--   两个模块各维护一套 classpath:db/migration 时 V1/V2 **版本号相同、内容不同**，
--   共用库必然出现（实测见 AGENTS.md §6.2.5 DRIFT-NEW-015）。
--   ⇒ 单一账本 + 单一脚本集，谁先启动都正确。
--
--   Go 出处：admin 表对应 Go service/user/admin/rpc/internal/model/data_model.go 的
--   Admin struct。⚠️ **表名两侧不同（不是笔误）**：Go 的 TableName() 返回 **admins**
--   （data_model.go:16-18），本脚本建的是 **admin_admin** ⇒ users 域统一的
--   「表名加前缀」约定（同族的还有 users → users_user、admin_invites → admin_invite）。
--   ⚠️ 原注释写「GORM 默认表名 admin_admin」是**错的**：GORM 默认名是 admins，
--   admin_admin 是 RAG 侧按域约定起的名。两侧**物理上不共用 schema**。
--
-- 全局编号（**不要在没有同步改全部脚本的情况下插号**，改版本号等于改 checksum）：
--   V1  create_user                  users_user                    （原 user-rpc 的 V1）
--   V2  create_user_avatar_history   users_user_avatar_history     （原 user-rpc 的 V2）
--   V3  create_admin                 admin_admin                   （原 admin-rpc 的 V1）
--   V4  create_admin_invite          admin_invite                  （原 admin-rpc 的 V2）
--
-- ⚠️ 原 admin-rpc 的 V3__create_user.sql 已删除：它与 V1 建的是同一张表，
--   连 DDL 都是逐字节相同的副本（原文注释自己写着「用 IF NOT EXISTS 保证幂等：
--   user-rpc 启动时已经建过表，admin-rpc 启动时本 SQL 跳过」），
--   唯一作用就是把账本版本号顶高，让后续迁移更难插号。
-- ============================================================
-- V1__create_admin.sql
--
-- 对应 Go 端 internal/model/data_model.go 的 Admin struct（Go 的 TableName() 返回 admins）。
-- admin-rpc admin 表的 DDL：admin_admin（⚠️ 两侧表名不同，见文件头说明）
--
-- 列结构：
--   id          BIGSERIAL PRIMARY KEY (Go 端用 Snowflake 生成的 uid，但这里保留 id 自增；
--                                       uid 来自 SnowflakeBean.nextId()，作为业务主键)
--   uid         BIGINT NOT NULL UNIQUE
--   username    TEXT UNIQUE
--   password    TEXT                              -- bcrypt 哈希（PasswordUtil.encrypt）
--   email       TEXT UNIQUE                       -- nullable（1:1 保留 Go 端 *string 语义）
--   extra_info  TEXT                               -- ⚠️ Go 侧是 text，见下方 DRIFT-ARPC-004①
--   create_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
--   update_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP

-- ⚠️ 2026-10-22（E-3 根因 R3）：字符串列从 VARCHAR(255) 改成 **TEXT**。
--   原注释里的「与 @Column 注解严格对齐」正是**漂移的来源**：Go 的 GORM AutoMigrate
--   给 Go `string` 字段生成的是 `text`（无上限），不是 varchar(255)。
--   `ALTER ... TYPE text` 对已存在的列恒成功且无需 USING 子句，可反复重放。

CREATE TABLE IF NOT EXISTS admin_admin (
    id          BIGSERIAL PRIMARY KEY,
    uid         BIGINT NOT NULL UNIQUE,
    username    TEXT UNIQUE,
    password    TEXT,
    email       TEXT UNIQUE,
    extra_info  TEXT,
    create_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 业务层大部分 findOneByXxx 都按 uid 查，所以单列索引
CREATE INDEX IF NOT EXISTS idx_admin_admin_uid ON admin_admin (uid);

-- R3 自愈：把历史上按 VARCHAR(255) 建出来的列就地放宽成 TEXT（幂等，可反复重放）
ALTER TABLE admin_admin ALTER COLUMN username TYPE text;
ALTER TABLE admin_admin ALTER COLUMN password TYPE text;
ALTER TABLE admin_admin ALTER COLUMN email    TYPE text;

-- ============================================================================
-- 2026-10-23（DRIFT-ARPC-004 ①②，用户 2026-10-23 22:0x 拍板「DDL 与实体一起改」）
-- ============================================================================
--
-- ① extra_info：jsonb → text
--
-- Go 出处（声明点 + 消费点，不是「json 看着像 text」的推测）：
--   声明点  service/user/admin/rpc/internal/model/data_model.go:11
--           ExtraInfo map[string]string `gorm:"column:extra_info;serializer:json"`
--   消费点  gorm.io/gorm@v1.31.1/schema/field.go —— 字段带 SERIALIZER tag 时
--           field.DataType 被置为 String，列类型按 **字符串** 建；
--           serializer/json 的 Value() 返回 json.Marshal 的 **string**。
--           ⇒ Go 的列是 **text**，存的是 JSON 文本，PG 从不做 jsonb 校验。
--
-- 为什么必须改（不是「类型洁癖」，有活体对拍证据）：
--   jsonb 列会**校验并拒绝**某些 json.Marshal 能产出的合法 JSON。
--   实测（2026-10-23 双侧活体对拍，同一份请求体）：
--     Go  → HTTP 200 {code:200}，行已落库，extra_info = {"k":" "}
--     RAG → HTTP 200 {code:5001}，**无行**，PG 报 22P05
--     （⚠️ 2026-10-25 真 PG 16 实测订正：是 22P05 json_bad_unicode_escape，不是 22P02）
--            `ERROR: unsupported Unicode escape sequence`
--   根因：`encoding/json` 把 U+0000 转义成 6 个 ASCII 字符 `\u0000`，
--   而 jsonb 的 unicode escape 校验**拒绝 \u0000**；text 列照单全收。
--   ⇒ 两侧可观察行为不同（Go 成功 / RAG 失败），是真实漂移。
--
-- ⚠️ **只改 admin_admin，不改 users_user**：
--   users_user.extra_info 的 DDL 归属 users/user 域（01_create_user.sql:41），
--   本 worker 无权改。admin-rpc 里另一处 CAST jsonb
--   （AdminDomainService.updateUser 写的是 **users_user**）必须保持原样，
--   改错一张表会破坏 user 域。跨域问题见 users/admin/rpc.md 本轮 §8。
--
-- 幂等性：`jsonb::text` 取规范化 JSON 文本，`text::text` 是恒等 ⇒ 可反复重放。
--   与下面时间列不同，这里**不涉及会话时区**，不需要 SET TIME ZONE。
ALTER TABLE admin_admin ALTER COLUMN extra_info TYPE text USING extra_info::text;

-- ② create_time / update_time：timestamp → timestamptz
--
-- Go 出处：data_model.go:12-13 的 time.Time **无 type tag**
--   ⇒ gorm.io/driver/postgres@v1.6.0/postgres.go:251-256
--     case schema.Time: … return "timestamptz"
--
-- ⚠️ 为什么**不能照抄上面 R3 三条**那样直接 ALTER（01_create_user.sql:80-94 已实测踩过）：
--   `timestamptz AT TIME ZONE 'UTC'` 对**已经是 timestamptz** 的列并不恒等：
--     timestamptz --AT TIME ZONE 'UTC'--> timestamp(UTC 墙钟)
--                  --按会话 TimeZone 铸回--> timestamptz(**可能被平移**)
--   会话时区非 UTC 时，**每启动一次整列时刻就偏移一次，且永不收敛**。
--   SchemaInitializer 每次启动都原样重放本文件，所以这个坑会持续累积。
--
--   ❌ `DO $$ … $$` + information_schema 条件守卫：**实测不可行**。
--      SchemaInitializer 用 spring-jdbc ScriptUtils 切分语句（分隔符 `;`），
--      而 spring-jdbc 7.0.9 的 ScriptUtils **不支持 PG 美元引号**
--      （反编译确认常量池无 `$$`）⇒ 块内分号照样被切断，DDL 语法错，
--      applyAll 抛异常 ⇒ **user-rpc 与 admin-rpc 双双起不来**。
--
--   ✅ 下面把会话 TZ 显式钉成 UTC 再转，转完 RESET ⇒ 两条路径都恒等：
--        首迁  timestamp 08:00 --AT TIME ZONE 'UTC'--> 08:00+00（按 UTC 解释）
--        重放  08:00+00     --AT TIME ZONE 'UTC'--> 08:00 --(TZ=UTC 铸回)--> 08:00+00（恒等）
SET TIME ZONE 'UTC';

ALTER TABLE admin_admin ALTER COLUMN create_time TYPE TIMESTAMPTZ USING create_time AT TIME ZONE 'UTC';
ALTER TABLE admin_admin ALTER COLUMN update_time TYPE TIMESTAMPTZ USING update_time AT TIME ZONE 'UTC';

RESET TIME ZONE;
