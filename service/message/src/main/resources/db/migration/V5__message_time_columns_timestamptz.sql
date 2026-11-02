-- =============================================================================
-- V5 · 2026-10-23 · message 域 8 个时间列：TIMESTAMP ⇒ TIMESTAMPTZ（+ 可空性对齐）
-- =============================================================================
-- 对应台账：docs/DRIFT/message/rpc.md §7 `DRIFT-MSG-V-020`
-- 规格来源：**users 域同款处置**（docs/DRIFT/users/_shared.md:718-724「时间列 TIMESTAMP⇒TIMESTAMPTZ」）
--           ⇒ 本文件照抄那一轮的规格，不重新设计。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ① 为什么 Go 侧是 timestamptz（消费点，不是声明点）
-- ─────────────────────────────────────────────────────────────────────────────
--   gorm.io/driver/postgres@v1.6.0/postgres.go:251-255
--       case schema.Time:
--           if field.Precision > 0 { return fmt.Sprintf("timestamptz(%d)", field.Precision) }
--           return "timestamptz"
--   真源 go.mod:27 钉的就是 v1.6.0 ⇒ Go 的 time.Time 字段**全部**建成 timestamptz。
--   实测（双侧活体 dump，同一个 postgres:16-alpine）：
--       Go : message_notification.created_at | timestamp with time zone | 可空
--       RAG: message_notification.created_at | timestamp without time zone | NOT NULL
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ② 为什么**必须**用 AT TIME ZONE，不能裸 ALTER（users 域 2026-10-23 实测踩到）
-- ─────────────────────────────────────────────────────────────────────────────
--   PG 的 timestamp → timestamptz **不是恒等转换**，且 `x AT TIME ZONE 'X'` 对
--   **已经是 timestamptz** 的列也不恒等：
--       timestamptz --AT TIME ZONE 'X'--> timestamp(X 墙钟) --按会话 TZ 铸回--> timestamptz
--   会话时区 ≠ X 时，**每重放一次整列时刻就平移一次，且永不收敛**。
--   ⚠️ message-rpc 的迁移由 **Flyway** 执行，每个版本只跑一次 ⇒ 不会自然重放；
--      但 `SchemaInitializer` 那套「原样重放」的做法在本仓是既有模式，
--      一旦有人照抄 users 的 R3「自愈 ALTER」模式进本文件，没有 AT TIME ZONE 就会埋雷。
--   ⇒ 下面的写法**两条路径都恒等**，与 users 的 `01_create_user.sql:78-108` 同款：
--       首迁  timestamp 02:00 --AT TIME ZONE 'Asia/Shanghai'--> 02:00+08  （= 2026-10-22T18:00Z）
--       重放  02:00+08    --AT TIME ZONE 'Asia/Shanghai'--> 02:00  --(TZ=+08 铸回)--> 02:00+08  恒等
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ③ 为什么转换时区选 Asia/Shanghai（不是 users 用的 UTC）
-- ─────────────────────────────────────────────────────────────────────────────
--   转换语义 = 「把**当初写入时用的那个时区**下的墙钟，重新解释成瞬时」。
--   message 侧这个时区**只能是 Asia/Shanghai**，三条独立依据：
--     a. Go DSN 钉死它：service/message/rpc/internal/model/db.go:22 `TimeZone=Asia/Shanghai`；
--     b. RAG 写入侧用的是 `LocalDateTime.now()`（= JVM 本地墙钟），
--        而部署时区经用户 2026-10-22 确认就是 Asia/Shanghai
--        （见 MessageRpcSupportTimeZoneTest 的类注释与既有修复 DRIFT-MSG-V-007）；
--     c. 本轮 V-019 已把 JDBC URL 钉上 `options=-c TimeZone=Asia/Shanghai`，
--        ⇒ 新写入的数据在会话时区上与 Go 侧一致。
--   ⚠️ 若历史数据里混过**非 Asia/Shanghai 时区**写入的墙钟，那部分转换结果**必然错**。
--      本轮无法从库里区分（timestamp 不带时区，历史信息已不可恢复）——
--      已在台账登记为**不可逆的信息损失**，若生产库存在此情况需人工核对。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ④ 可空性对齐（GORM 没打 not null 的就是可空，别自作主张加 NOT NULL）
-- ─────────────────────────────────────────────────────────────────────────────
--   双侧实测：Go 的 created_at / updated_at **可空**（tag 里没有 not null），
--   只有 Conversation.LastMessageAt 带 `not null`。
--   ⇒ 下面 **5** 列 DROP NOT NULL（notification 的 created_at/updated_at、
--   conversation 的 created_at/updated_at、conversation_message 的 created_at）；
--   `last_message_at` 两侧都是 NOT NULL，保持不动。
--
-- ⚠️ **本文件不删 `DEFAULT CURRENT_TIMESTAMP`**（Go 侧无默认，属已知未对齐项）：
--   删除默认对「省略该列的 INSERT」才有意义，而两侧所有 INSERT 都**显式绑定**
--   这些列（见 ConversationMapper#insertConv 的 VALUES 列表）⇒ 默认永远不触发。
--   为一个不可观测的维度冒「有人在某条裸 SQL 路径上依赖了默认」的风险不划算。
--   该残差已登记在 DRIFT/message/rpc.md 的 V-020 节，不静默放过。
-- -----------------------------------------------------------------------------

SET TIME ZONE 'Asia/Shanghai';

-- message_notification
ALTER TABLE message_notification
    ALTER COLUMN read_at    TYPE TIMESTAMPTZ USING read_at    AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_notification
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_notification
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_notification ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE message_notification ALTER COLUMN updated_at DROP NOT NULL;

-- message_conversation（last_message_at 两侧都是 NOT NULL，只改类型）
ALTER TABLE message_conversation
    ALTER COLUMN last_message_at TYPE TIMESTAMPTZ USING last_message_at AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_conversation
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_conversation
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_conversation ALTER COLUMN created_at DROP NOT NULL;
ALTER TABLE message_conversation ALTER COLUMN updated_at DROP NOT NULL;

-- message_conversation_message
ALTER TABLE message_conversation_message
    ALTER COLUMN read_at    TYPE TIMESTAMPTZ USING read_at    AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_conversation_message
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';
ALTER TABLE message_conversation_message ALTER COLUMN created_at DROP NOT NULL;

RESET TIME ZONE;