-- =============================================================================
-- V4 · 2026-10-23 · message_conversation 的唯一性对象：从「约束」改成「索引」
-- =============================================================================
-- 为什么要这条迁移（本轮由**双侧活体 DDL 对拍**得出，不是静态推断）：
--
--   Go 侧跑一次 AutoMigrate（真源同版本 gorm.io/driver/postgres v1.6.0）后查 pg_indexes：
--     CREATE UNIQUE INDEX uk_conversation_pair ON public.message_conversation
--       USING btree (participant1_role, participant1_id, participant2_role, participant2_id)
--   查 pg_constraint：**没有** uk_conversation_pair 这一行
--   ⇒ Go 的 `uniqueIndex:uk_conversation_pair` tag 建出来的是**唯一索引**，不是表约束。
--
--   RAG 的 V2 用 ALTER TABLE … ADD CONSTRAINT uk_conversation_pair UNIQUE (…) 建的是
--   **唯一约束**（pg_constraint 里有 contype=u 那一行）。
--
--   两者都保证「同一对参与者只允许一个会话」，但**对象类型不同**：
--   `\d` 输出、依赖该名字的工具、以及未来任何「重建索引」式运维操作都会看出差别。
--
-- 顺带关掉 UNRESOLVED-MSG-005（真源侧长期「未验证」的那条）：
--   真源是 GORM AutoMigrate（**每次启动都校验/补建**），
--   而 Flyway 是**一次性迁移**。在「表已被 GORM 建过」的库上，
--   V2 的 `ALTER TABLE … ADD CONSTRAINT` 会因对象已存在而失败。
--   ⚠️ 本轮实测：PostgreSQL 16 **不支持** `ALTER TABLE … ADD CONSTRAINT IF NOT EXISTS`
--      （真容器上执行报 `syntax error at or near "NOT"`）
--      ⇒ 「给 V2 加个 IF NOT EXISTS」这条路是**死的**，不能那样修。
--   而 `CREATE UNIQUE INDEX IF NOT EXISTS` 是 PostgreSQL 支持的 ⇒ 本条迁移用它。
--
-- 幂等性：先 DROP CONSTRAINT IF EXISTS（PostgreSQL 支持）再 CREATE UNIQUE INDEX IF NOT EXISTS。
-- 对「V2 从未跑过的库」DROP 是空操作，对「V2 跑过的库」则是真正的类型纠正。
-- -----------------------------------------------------------------------------

ALTER TABLE message_conversation DROP CONSTRAINT IF EXISTS uk_conversation_pair;

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_pair
    ON message_conversation (participant1_role, participant1_id, participant2_role, participant2_id);