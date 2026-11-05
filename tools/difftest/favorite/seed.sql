-- ============================================================================
-- gRPC 契约双侧活体对拍 · 共用种子 · favorite 域
-- ----------------------------------------------------------------------------
-- 同一份 seed 同时灌进两侧的库 ⇒ 两侧读到**同一批行** ⇒ 响应字节可比。
--
-- 🔴 为什么时间列要写死成带时区偏移的常量：
--   favorite_folder.create_time / update_time 与 favorite_item.create_time 都是
--   timestamptz，两侧读出后都按 UnixMilli() 上线
--   （Go helper.go:69/83；RAG FavoriteDomainService 同语义）。
--   timestamptz 存的是**绝对时刻** ⇒ 只要 INSERT 的字面量带明确偏移，
--   两侧无论用什么 session TimeZone 读，得到的 epoch milli 都相同。
--   ⚠️ 若写成不带偏移的 '2026-01-02 03:04:05'，则依赖**会话时区**
--      （Go DSN 带 TimeZone=Asia/Shanghai，RAG JDBC URL 也带）
--      ⇒ 一旦某侧漏配就整体平移 ⇒ 直接改变 L1 返回值。
--
-- 🔴 为什么 favorite_item.target_revision 一律 NULL：
--   Go 的 data_model.go:25-26 写死了「Nil means the source had no provable
--   published revision … In particular, do not backfill legacy rows」；
--   RAG 侧 V2 建表注释同款（且 RAG 侧多一条 CHECK 约束）。
--   ⇒ 用 NULL 表达「没有可证修订」，绝不用 '' 冒充。
--
-- 🔴 为什么**不写 `id` 列**（2026-10-25 第十六轮实测踩到）：
--   favorite_folder.id / favorite_item.id 都是**自增主键**（Go 侧 INSERT 走
--   `RETURNING "id"`，RAG 侧 mapper 也不带 id ⇒ 两边都靠序列）。
--   ⚠️ 若种子里**显式**写 id（1/2/3），PostgreSQL **不会**推进那条序列
--      ⇒ 序列仍停在 1 ⇒ 种子灌完后第一次 INSERT 拿到的还是 id=1
--      ⇒ 直接撞 `duplicate key value violates unique constraint "favorite_folder_pkey"`。
--   ⇒ 而这个症状**离根因很远**：表现为「创建收藏夹报 5203 名称已存在」，
--      真因却是序列没推进 ⇒ 读日志会误判成「重名检查误报」。
--   ⇒ 所以：**只写业务列，让序列自己走**（空表 ⇒ 依然拿到 1/2/3，值不变）。
--
-- 数据布局（刻意让三个用例维度都能被覆盖）：
--   · user 1001 名下 2 个收藏夹（9001 / 9002）⇒ 列表用例有内容
--   · user 2002 名下 1 个收藏夹（9003）⇒ 归属校验用例有「别人的夹」
--   · 9001 下 2 条收藏、9003 下 1 条收藏
--   · target_type 同时覆盖 post / article（article 的 target_revision 留 NULL）
-- ============================================================================

INSERT INTO favorite_folder (folder_id, user_id, name, create_time, update_time) VALUES
    (9001, 1001, 'Alpha', TIMESTAMPTZ '2026-01-02 03:04:05.678+08', TIMESTAMPTZ '2026-01-02 03:04:05.678+08'),
    (9002, 1001, 'Beta',  TIMESTAMPTZ '2026-01-02 03:04:06.789+08', TIMESTAMPTZ '2026-01-02 03:04:06.789+08'),
    (9003, 2002, 'Gamma', TIMESTAMPTZ '2026-01-02 03:04:07.890+08', TIMESTAMPTZ '2026-01-02 03:04:07.890+08');

INSERT INTO favorite_item (favorite_id, folder_id, user_id, target_id, target_type, target_revision, title, cover, create_time) VALUES
    (7001, 9001, 1001, 't-1', 'post',    NULL, 'T1', 'c1', TIMESTAMPTZ '2026-01-02 03:05:05.000+08'),
    (7002, 9001, 1001, 't-2', 'article', NULL, 'T2', 'c2', TIMESTAMPTZ '2026-01-02 03:05:06.000+08'),
    (7003, 9003, 2002, 't-3', 'post',    NULL, 'T3', 'c3', TIMESTAMPTZ '2026-01-02 03:05:07.000+08');
