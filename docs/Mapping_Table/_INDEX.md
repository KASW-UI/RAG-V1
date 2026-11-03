# Mapping_Table 索引 —— RAG（B 侧）↔ RAG-V1 两方文件名对照

> **本目录目的**：把两棵树的文件名与职责**逐条对应**起来 ——
> **B 列 RAG（真理之源 · api/rpc 分离）** → **C 列 RAG-V1（Spring 惯用目标）**。
> 填 RAG-V1 实现时，从 C 列反查 B 列 ⇒ **去 B 侧源码逐字对照**。
>
> 📌 **格式契约见 [`README.MD`](README.MD)** —— 章节骨架、表头、映射类型词表、覆盖度自查方法。
> 📌 **格式样例**：[`/Users/pr/Documents/java&golang/RAG/docs/MappingTable/users.md`](file:///Users/pr/Documents/java&golang/RAG/docs/MappingTable/users.md)（RAG 那份的同域前身）。
>
> **核对日期 2026-11-02** · 守门脚本 `tools/check-mapping-coverage.py`
>
> 📌 **D16 · 2026-11-02**：判定基准改为「**B 侧（RAG 仓）是唯一真理**」⇒
> **原第三列已物理删除**（D16 落地时）⇒ 本表由**三方**改为**两方**（B/C）。

---

## 一、三列语义

| 列 | 仓库 | 角色 | 主代码文件数 |
|---|---|---|---|
| **B** | `RAG` | **真理之源**（只读）· api/rpc 分离 | **752** 个 `.java` |
| **C** | `RAG-V1` | **Spring 惯用目标形态**（api/rpc 合并 + **D7 包压平**） | **624** 个 `.java` |

> ⚠️ **两列数量不成 1:1** —— C 比 B 少 **128** 个文件，来自 ① api/rpc 合并（D1）② `service/common` → `common` starter（D3）③ 生态替代（D4）。
> ⇒ **大量 `N:1` 与 `C 新增` 是正常的，不是缺陷。**

---

## 一·五、目录布局（**按域分文件夹**，与 `docs/File_Tree/` 同构）

```
Mapping_Table/
├── _INDEX.md                  ← 本文件（汇总索引 + 缺口清单）
├── README.MD                  ← 格式契约
├── common.md                  ← 全局共享层（不属于任何域 ⇒ 留在根）
├── users/                     ← ⭐ users 域：一个域、两个子域 ⇒ 拆四份
│   ├── _shared.md             ←   域级共享叙事（持久层 / HTTP·DI / 公共模块 / 决策锁 / 全局约定 / 缺口清单）
│   ├── users-common.md        ←   公共库
│   ├── user/user.md           ←   user 子域
│   └── admin/admin.md         ←   admin 子域
├── article/article.md
├── comment/comment.md
├── favorite/favorite.md
├── follow/follow.md
├── hot/hot.md
├── like/like.md
├── message/message.md
└── task/task.md
```

**规则**：
- `common.md` 留根（它是**全局**层，不属于任何域）。
- **一域一文件夹**，文档即 `<域>/<域>.md`。
- ⚠️ **一个域可以有多份文档** —— 当该域有**子域**或**公共库**时，拆成
  `<域>/_shared.md`（域级共享）+ `<域>/<公共库>.md` + `<域>/<子域>/<子域>.md`。
  ⇒ 判据与 RAG 的 `docs/DRIFT/users/`（`_shared.md` + `common.md` + `user/` + `admin/`）、
  RAG-V1 自己的 `docs/File_Tree/users/`（`users-common.md` + `user/` + `admin/`）**完全一致**。
- ⇒ 四档文档的目录规则统一 ⇒ 从 `docs/README.md` 一眼能看出每个域在四个维度上的全貌。

---

## 二、覆盖度（**由守门脚本从文件系统反查，不采信文档自报数字**）

```bash
python3 tools/check-mapping-coverage.py     # 全部域；EXIT=0 才算通过
```

| 域 | 文档 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |
|---|---|---:|---|---|---:|
| **users** | [`_shared.md`](users/_shared.md) · [users-common.md](users/users-common.md) · [user/user.md](users/user/user.md) · [admin/admin.md](users/admin/admin.md) | 1,158 | **111 / 111 = 100%** | **145 / 145 = 100%** | 134 |
| **common** | [common.md](common.md) | 921 | **109 / 109 = 100%** | **88 / 88 = 100%** | 135 |
| **article** | [article/article.md](article/article.md) | 488 | **63 / 63 = 100%** | **83 / 83 = 100%** | 76 |
| **comment** | [comment/comment.md](comment/comment.md) | 481 | **69 / 69 = 100%** | **83 / 83 = 100%** | 84 |
| **favorite** | [favorite/favorite.md](favorite/favorite.md) | 519 | **61 / 61 = 100%** | **75 / 75 = 100%** | 88 |
| **follow** | [follow/follow.md](follow/follow.md) | 517 | **37 / 37 = 100%** | **51 / 51 = 100%** | 116 |
| **hot** | [hot/hot.md](hot/hot.md) | 519 | **31 / 31 = 100%** | **34 / 34 = 100%** | 56 |
| **like** | [like/like.md](like/like.md) | 451 | **69 / 69 = 100%** | **83 / 83 = 100%** | 81 |
| **message** | [message/message.md](message/message.md) | 533 | **53 / 53 = 100%** | **71 / 71 = 100%** | 69 |
| **task** | [task/task.md](task/task.md) | 394 | **39 / 39 = 100%** | **39 / 39 = 100%** | 56 |
| **合计** |  | **5,967** | **637 / 637 = 100%** | **752 / 752 = 100%** | 891 |

> 📌 **users 域拆分的守恒性**（2026-10-25 拆分后实测）：
> 拆分前单文件 515 行 / 127 映射行 ⇒ 拆分后四份合计 **1,011 行 / 127 映射行**（行数增加是**导航与分节标题**带来的，非内容膨胀）。
> 机器比对：**原 222 个文件名 token → 四份命中 222 个、漏 0 个**；**129 映射行跨文件重复签名 0**（每行只出现一处）。
> ⇒ **内容零丢失、零重复。**
>
> ⚠️ **数「映射行」不能按固定列下标取** —— `follow` / `like` / `task` 三域存在**比标准表多一列**的表（映射类型落在第 5/6/7 个 `|` 之后而非第 4 个）。
> ⇒ 计数必须**扫全行找类型词**，固定取 `parts[4]` 会**漏算 60 行**（follow **−47**、like **−2**、task **−11** ⇒ 实测 845 行会被数成 787 行）。

### 映射类型分布（881 条映射行）

| 类型 | 条数 | 占比 | 含义 |
|---|---:|---:|---|
| `1:1` | 505 | 57% |
| `1:N` | 174 | 20% |
| `N:1` | 191 | 22% |
| `C 新增` | 11 | 1% |

### B 列覆盖（RAG 侧 · 2026-11-01 实测）

| 项 | 值 | 口径 |
|---|---:|---|
| RAG 侧业务 `.java` | **752** | 排除 `tools/`（语义探针 / 对拍 runner —— 非业务代码）、`src/test/`、`target/` |
| B 列已覆盖 | **748** | 精确类名 **663** + 通配 **2**（`Message<Case>Service` / `MessageAdmin<Case>Service`）+ 目录形态 **65**（如 `like-rpc/…/mapper/`） |
| **B 列未覆盖** | **0** | 剩 4 条全部登记在白名单（见 §六·五）⇒ **不是漏，是已登记的例外**；⚠️ **2026-11-01 补录 68 个类**（common 35 + 各域 auth/Spans 33）后归零 |
| B 列引用的类在 RAG 磁盘不存在 | **0** | B1 正向判据：文档没引用不存在的 RAG 类 |

> 📌 **B 列此前长期无机器守卫** —— A / C 两列都有，B 列没有；而 §1.0c 已把 **B↔C 对应定为「逐类抄写路线图」**
> ⇒ **B 列漏一个类 = 抄写时漏一个类**。2026-10-26 补上**双向对账**（详见 `tools/README.md` §B 列守卫）。

### B↔C 行级对应：可解析度（2026-11-01 实测）

主表 **789 行** —— 按「B / C 两侧各自是什么形态」分类：

| 形态 | 行数 | 占比 | 机器判定 |
|---|---:|---:|---|
| 双侧都有具体路径 | 614 | 77.8% | ✅ 直接读出 |
| C 侧明确否定（`无对应文件` / `无对应物` / `—`） | 38 | 4.8% | ✅ 读得出**否定** |
| 两侧都明确否定 | 14 | 1.8% | ✅ 读得出**否定** |
| B 侧明确否定（C 新增） | 9 | 1.1% | ✅ 读得出 |
| **指代（「同上」/「同左」）** | **88** | **11.2%** | ⚠️ 需**向上消解** |
| 纯描述文字（模块 / 进程 / 集合 / 内部类） | 26 | 3.3% | ⚠️ 非文件级对照 |

> 📌 **「同上」是确定性指代** —— B / C 各自独立**向上**取最近一个非「同上」的值，零歧义。
> ⇒ **消解后双侧可判定 763/789 = 96.7%**；剩 26 行全是**非文件级对照**（见 §六·六），**没有一行是「该给路径却没给」的真缺口**。
> ⇒ ⚠️ **不必展开「同上」** —— 消解即可读出；展开只是让人类 review 少翻几行，代价是 admin/user 域重复 8~24 遍长路径。
> ⇒ **「同上」的成因不是随手写**：N:1 类型里 43% 用「同上」（1:1 仅 1%）⇒ **D1/D7 合并越激进 → 同 C 落点共享行越多 → 「同上」越多**。



---

## 三、🔴 两方对照暴露的**落点缺口**（90 条 · 2026-11-01 机器复算）

> **这是本目录最有价值的产出。** 两份文档逐条对照后发现的「**B 有职责、C 侧无落点**」或「**C 侧 javadoc 与 B 事实冲突**」。
> ⚠️ **这些不是行为对齐结论** —— 只是「文件名 / 职责落点」层面的问题，需由各域接手者回 B 列逐条确认后再动代码。
> 逐条见各域文档的 §缺口 小节。
> ⚠️ **本节总数的计数口径** = 各域文档「§缺口 / §「职责对不上」」小节的**表格数据行总数**
> （与 `tools/check-mapping-coverage.py` 第 ④ 重校验**同一取数规则**）⇒ **2026-11-01 复算 = 90**。
> ⚠️ 此前写的「76」是 **2026-10-26 的自报数，已过期**（此后各域小节逐条增补；§三 高优先级表里**另有 2 条已于 2026-11-01 销账**，见下）。
> ⇒ 已在 `tools/check-mapping-coverage.py` 加守恒断言：**小节标题自报的条数必须等于表内实际行数**（P-08 同病，不再靠人复算）。

### 🔴 高优先级（10 条）

| 域 | 缺口 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |
|---|---|---:|---|---|---:|
| **users** | ✅ **幂等 DDL 重放执行器 + 4 份幂等脚本已在 C 侧落地（2026-11-02 · users-common lane）**—— B 侧的 `users/users-common/.../ddl/SchemaInitializer.java` + `db/01..04_*.sql` ⇒ C 侧落 `service/users/common/.../SchemaInitializer.java` + `service/users/common/src/main/resources/db/01..04_*.sql` ⇒ **F-3 已闭合**；⚠️ **执行器的调用点**仍在 users-user / users-admin 两条 lane | 1,158 | **111 / 111 = 100%** | **145 / 145 = 100%** | 134 |
| **task** | **`goka.Persist`（RocksDB 本地表）无生态等价物** ⇒ 存储后端需拍板（PG 表 / Redis `HINCRBY` / 专用 KV） | 394 | **39 / 39 = 100%** | **39 / 39 = 100%** | 56 |
| **task** | **`WebMvcConfig` 的 javadoc 与本域事实冲突** —— javadoc 写「注册 JwtAuthInterceptor」，但 **B 侧 `task-api` 零 JWT 依赖、无 `interceptor/` 包** ⇒ 本域**零鉴权** | 394 | **39 / 39 = 100%** | **39 / 39 = 100%** | 56 |
| **task** | **`TaskApiResponseAdvice` 的职责未定** —— **B 侧 `task-api` 没有 ResponseAdvice** ⇒ 成功响应**无信封** | 394 | **39 / 39 = 100%** | **39 / 39 = 100%** | 56 |
| **common** | **`communityfact` 的「三域共享」** ⇒ ✅ **前提已变（D16 + 2026-11-02 实测）**：B 侧 `import com.rag.common.communityfact.*` 的主代码文件数 = **comment 4 / like 4 / favorite 10** ⇒ **B 侧已是三域共享**（favorite 走共享层 `Wire` / `Dispatcher` / `ProcessLogger`）⇒ C 侧按三域共享设计 = 照抄 B 侧；⚠️ **「favorite 域的接线是否已走共享层」归 `favorite.md` 核** | 921 | **109 / 109 = 100%** | **88 / 88 = 100%** | 135 |
| **favorite** | **错误文案串味** —— 🔴 **`ErrorNotFound(5002)` = 英文 `not found`**（B 侧 `favorite-common/…/common/errmsg/FavoriteErrorMessages.java:52`，**本域唯一英文**）+ `Success`="OK" / `Error`="FAIL"（`:48`） | 519 | **61 / 61 = 100%** | **75 / 75 = 100%** | 88 |
| **comment** | `CommentController` javadoc 与 **B 侧** 端点清单不一致（2 处） | 481 | **69 / 69 = 100%** | **83 / 83 = 100%** | 84 |
| **comment** | `CommentKafkaMsg` 与 `service/comment/src/main/java/com/rag/comment/messaging/CommentAuditMessage.java` 的载荷字段归属未锁定 | 481 | **69 / 69 = 100%** | **83 / 83 = 100%** | 84 |
| **comment** | `wiring/` 下的**装配落点已齐**（2026-10-25 补齐 `CommentFactProcessLogging`）但类内容仍为空壳；`db/migration/` 目录内容仍为空 | 481 | **69 / 69 = 100%** | **83 / 83 = 100%** | 84 |
| **article** | ✅ **2026-11-03 · C 侧 6 处职责缺口（G-1~G-6）已全部闭合 ＋ 补齐 1 处真实缺失（CORS · `ArticleCorsFilter`）** —— ① `messaging/` 3 类（审核消费者 / outbox relay / 同步结果消费者）② `client/ArticleObjectStorageClient`（MinIO put/get）③ `db/migration/` V8/V9/V10 **逐字节照抄** B 侧 ④ `contract/ArticleSyncEvent` 承载 `ArticleReviewMessage` / `ArticleSyncResult` ⑤ `interceptor/ArticleJwtAuthFilter` 承载 Servlet Filter 段验签（**`DRIFT-USERAPI-009②` 在本域闭环**）⑥ `web/ArticleStringFields` + `web/ArticleQueryValues` + `mapper/JsonbTypeHandler` 三份**域内副本**（待收口）| 488 | **63 / 63 = 100%** | **83 / 83 = 100%** | 76 |
| **follow** | 原 `api/` vs `rpc/` 职责边界（D7 已压平，边界问题转为「单层包内的归属」）、`FollowApiSupport` 归属、`WebMvcConfig`/`FollowController` javadoc 与事实冲突 | 517 | **37 / 37 = 100%** | **51 / 51 = 100%** | 116 |

> ✅ **2026-11-01 销账 2 条**（本轮补空壳 + 修 C 列）：① **users · B 侧的身份解析器在 C 侧无承载** ⇒ 已落 `service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java`（**只 1 份**，上收到域级共享层）；② **task · Kafka 消费侧整体缺失** ⇒ 已落 `service/task/src/main/java/com/rag/task/messaging/`（10 类：2 个 sink + 3 个 consumer + router + store + 2 个事件 DTO + `TaskGoCompatibleJson`）。
> ⇒ 两行**从本表删除**，但**仍留在各域 §「职责对不上」小节**里并标 ✅ ⇒ §三 总数（90）不变。
>
> ✅ **2026-11-02 销账 1 条**（users-common lane 填实现）：③ **users · 幂等 DDL 重放机制在 C 侧无执行器、无脚本** ⇒ 已落 `service/users/common/src/main/java/com/rag/users/common/SchemaInitializer.java`（幂等重放执行器）＋ `service/users/common/src/main/resources/db/01..04_*.sql`（4 份幂等 DDL 脚本）⇒ **F-3 闭合**。
> ⇒ 该行**从本表删除**，**仍留在** [`users/_shared.md`](users/_shared.md) §7.4 里并标 ✅ ⇒ §三 总数（90）不变；⚠️ **剩余跨 lane 项 = 执行器的调用点**（users-user / users-admin 两条 lane，见域级台账 `docs/DRIFT/users/verification-users-common-2026-11-02.md` 的 D-10）。

### 🟠 中优先级（要点）

| 域 | 缺口 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |
|---|---|---:|---|---|---:|
| **users** | **错误契约在两个子域间不对称**（**F-2 · 2026-10-26 D7 后由 🔴 降级为 🟠**）—— C 的 `service/users/user/src/main/java/com/rag/user/contract/` 有 `ErrCodes`+`ErrMsg`，但 **admin 侧无 `contract/` 包**（`admin-contract` 模块已随 D7 删除）；公共库 `service/users/common/src/main/java/com/rag/users/common/` 那份 admin 尚未引用（但 pom 依赖已接上） | 1,158 | **111 / 111 = 100%** | **145 / 145 = 100%** | 134 |
| **task** | 持久层空位（`db/migration/` 只有 `.gitkeep`、yml 无 `flyway`/`hikari`/`id-type`）· RAG-V1 `common/` 无 `RpcServerInterceptorFactory`（**上游**的 unary interceptor 无落点）· MQ 载荷契约 C 侧只剩 like 域那一份 · `grpc-client-spring-boot-starter` 本域无使用方 · **Kafka 消费侧仍整体缺失**（`messaging/` 只有 `TaskKafkaTopics` + `TaskRewardPublisher`，B 侧 11 个 `mq/*.java` 里的 9 个消费者无落点，全仓 `@KafkaListener` **零命中**） | 394 | **39 / 39 = 100%** | **39 / 39 = 100%** | 56 |
| **like** | Kafka 消费入口（listener / ack 契约 / 失败即丢弃不重投）无落点 · `like_outbox_event → article-hot-events` 发件箱中继无落点 · Jackson 2 `ObjectMapper` Bean 无落点 · `like` / `like-api` 配置段整个缺失 · DDL 迁移脚本为空 | 451 | **69 / 69 = 100%** | **83 / 83 = 100%** | 81 |
| **message** | ✅ **6 条落点缺口全部关闭（2026-11-02 · 本域实现轮）** —— `controller/`（两个 Controller）· `interceptor/`（两组 JWT）· `metrics/`（共享基座）· `wiring/`（Nacos 手写注册）· `service/`（边界已定）· `contract/`（域内错误契约）⇒ 逐条见 [`message/message.md`](message/message.md) §7.3 #1~#6<br>🟠 **余 3 条（不属本域写权）**：① **跨域注册名不一致** —— 本域 gRPC client 寻址 `discovery:///admin-rpc`（B 侧逐字），而已落地的 `service/users/admin` 注册名是 **`admin-service`** ⇒ 寻址落空 ⇒ **归 users/admin lane**；② `JsonbMapTypeHandler`（jsonb↔Map）**应上收到 `common/`**（共享层现只有 jsonb↔String 与 text↔Map 两份，类型都不兼容）⇒ **归收口任务**；③ **§1.6 缺口**：B 侧 `DRIFT-MESSAGEAPI-009②`（验签耗时应**在 3s 预算外**）在 C 侧**未闭环**（C 侧无独立 Servlet Filter ⇒ 验签与 401 合在 `HandlerInterceptor` 内）⇒ **继承 B 侧漂移 · 登记为签字偏离** | 533 | **53 / 53 = 100%** | **71 / 71 = 100%** | 69 |
| **favorite** | `POST /favorite/v1/folder/create` 端点缺失 · public 路径集 · preflight 触发入口 · DDL 迁移脚本 · `JsonbTypeHandler` / MP 排序桥的归属 · `GetFavoritesByFolderReq.FolderId` 的取参位置 · 域内 gRPC client · 端口号 | 519 | **61 / 61 = 100%** | **75 / 75 = 100%** | 88 |
| **common** | `GrpcStatusMapper` 与 `GrpcExceptionMapperAutoConfiguration` **职责重叠** · 设计文档 §5.1 列了 `contract/ErrorCode.java` 但**实际树里没有** · 雪花 ID 有**两个落点** · `JsonBodyConverterConfig` 是「注册点」还是「配置类」 · **gRPC 拦截链挂载点缺位**（D9 ⇒ 落 `common/autoconfigure/ProtectionAutoConfiguration`，共享层 1 份） | 921 | **109 / 109 = 100%** | **88 / 88 = 100%** | 135 |
| **follow** | `db/migration/` 为空 · `application.yml` 缺段 · `*Properties` 聚合边界 · `FollowBlacklistInterceptor` + `FollowJwtAuthInterceptor` · `GoStyleLimitOffset` | 517 | **37 / 37 = 100%** | **51 / 51 = 100%** | 116 |

### 🟡 低优先级（要点）

| 域 | 缺口 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |
|---|---|---:|---|---|---:|
| **users** | **表名：B 侧与更早一版的历史命名不同**（B 侧 `users_user` / `users_user_avatar_history` / `admin_admin` / `admin_invite`；更早一版是 `users` / `user_avatar_history` / `admins` / `admin_invites` —— **随旧基准移除，不再作数**）· HTTP DTO 命名 1 处不一致 · `UserController`/`AdminController` javadoc 的 method 分布与 **B 侧** 不一致 · `AdminController` javadoc 少列 1 个端点（12 vs 13）· HTTP 超时机制在 C 侧未落点（**上游** `default=3000`） | 1,158 | **111 / 111 = 100%** | **145 / 145 = 100%** | 134 |
| **task** | `TaskApiProperties` 的聚合边界未定 | 394 | **39 / 39 = 100%** | **39 / 39 = 100%** | 56 |
| **like** | `target_ids` 的 null 元素语义无独立文件（B 侧 `like-api/.../service/GoStyleTargetIds.java` ⇒ 须并入 C 侧 `LikeApiSupport.java`）· 2 条 Jackson 强转补强是否还在未核对 · ✅ **`like_api_reject_total` 的注册差异已消解**（B 侧用 Micrometer `Counter.builder` 注册 ⇒ **两侧同样自动注册** ⇒ 无差异可声明） | 451 | **69 / 69 = 100%** | **83 / 83 = 100%** | 81 |

---

## 四、三条读表纪律（每份域文档开头都重复了这条）

1. ⚠️ **B 侧是唯一真理，但它自身有已登记的差异** —— B 侧在 users/like/message/common/article 五域已被判定 `NOT EQUIVALENT`
   （组件级清单见 B 仓 `docs/DRIFT/_cross/component-coverage-matrix.md`）。
   **一切行为判断以 B 侧代码为准**；B 侧与第三方的既有差异 ⇒ **继承并登记为签字偏离**（`docs/Intentional_Deviations/`），**不回溯**。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表只回答「**文件名 / 职责的对应关系**」，
   **不回答「行为是否一致」**。行为对齐状态看 `docs/DRIFT/<域>/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 Java 文件目前都只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么」，不是「已经有什么」。**

---

## 五、目录分工（与 RAG 的 `docs/MappingTable` 一致）

| 节 | 记什么 | 为什么 |
|---|---|---|
| 0.5. **B → C 结构映射速查表** | RAG 的哪个包 → RAG-V1 的哪个包（**实测**） | 一眼看出打平/合并/改名后的落点 |
| 1. 包结构对照 | B / C 两列 + 映射类型 + 备注 | 翻某个文件时一眼找到两方对应 |
| 2. 持久层差异 | ORM / Mapper / Schema 演进 / 事务 / 雪花 ID | RAG-V1 与 RAG 的持久层差异 |
| 3. HTTP / DI 差异 | 路由 / Controller / Service 分层 / 错误表达 / 配置 / 进程拓扑 | RAG-V1 与 RAG 的装配差异 |
| 4. 公共模块对照 | 共享件归属与边界 | 跨域共享的代码在哪 |
| 5. 决策锁 | 「**N 处同时改**」+「**为什么换**」 | 复刻到下一个域时按清单改，不漏 |
| 6. 跨模块全局约定 | proto 字段号 / 错误码 / JWT claims / 表名 / HTTP path / Kafka topic / 指标名 | 这几项所有域共享，不会因改某域而漂 |
| 7. 覆盖度自查 | C / B 列逐文件覆盖 + 映射类型分布 + 缺口清单 | 本表的可信度自证 |

---

## 六、守门脚本

| 脚本 | 作用 | 何时跑 |
|---|---|---|
| `tools/check-mapping-coverage.py` | **八重校验**：① **从文件系统反查**每份文档的 **B / C 列覆盖率**（**不采信文档自报数字**）② **`_INDEX.md` 自报数字对账**（本表的行数 / 覆盖 / 映射行 vs 实测值）③ 映射行计数（扫全行找类型词）④ 小节标题自报条数 == 表内实际行数 ⑤ **B 列双向对账**（B1 正向 + B2 反向）⑥ **B↔C 指代消解后可判定性**（「同上」消解后每行都要读得出）⑦ **主表行形状**（列数必须为 4）⑧ **合计行对账** | 改完任何一份域文档后；**提交前必跑**（EXIT=0 才算通过） |

> ⚠️ **脚本按文件计数，不按文件名去重** —— users 域的 `user/` 与 `admin/` 两个子域下存在同名文件
> （如各自的 `UserController.java`）⇒ 去重会漏计 18 个。
> ⚠️ **D16 后豁免口径变了**：A 列判据（`go_files` / A 列覆盖率 / A 列免责措辞）**已全部移除** ⇒
> 现在只守 **B / C 两列**；B 列的「无对应物」豁免同样靠免责措辞匹配（`无对应物` / `刻意为空` / `不迁` / …）。
>
> 📌 **自报数字为什么会漂**（2026-10-25 实测）：`favorite` 域文档被 worker 改完后行数从 447 变 473，
> 而本表仍写 447 ⇒ **索引里的数字是「写完那一刻的快照」，不是事实**。
> ⇒ 现已由脚本第 ② 项做**机器对账**（10 个域 × 4 项 = 40 个数字），漂了就 `EXIT=1`。
> 负向测试已做：故意注入 2 处漂移 ⇒ 脚本如实报出并 `EXIT=1` ⇒ **这不是不会失败的假守卫**。

### 六·五、B 列已知例外（4 条 · 白名单）

> **B 列守卫（⑤）的例外登记** —— 下列 RAG 侧**真实存在**的类，B 列没有对应条目。
> 逐条核对后登记，**每条带原因 + 核对日期**；脚本里同名常量 `B_COLUMN_WHITELIST`。
> ⚠️ **白名单不是免责声明，是待办清单** —— 脚本会报「已失效条目」（该类后来被 B 列覆盖了却还挂着）⇒ **防白名单腐化**。

| # | RAG 侧类 | 域 | 性质 | 原因 | 核对日期 |
|---|---|---|---|---|---|
| 1 | `ArticleGoStyleCoercion` | article | ⚪ **有意不记** | RAG 域内局部副本 ⇒ C 侧由 `common/web/Coercion` 共享承担 | 2026-10-26 |
| 2 | `ArticleGoStyleCorsFilter` | article | ⚪ **有意不记** | RAG 域内局部副本 ⇒ C 侧由 `common/web/` 过滤器承担 | 2026-10-26 |
| 3 | `GoStyleRpcSheddingInterceptor` | common | ⚪ **有意不记** | RAG `service/common/protection/` 17 类之一 ⇒ C 侧 `protection/` 仅 2 类（生态吸收 D4） | 2026-10-26 |
| 4 | `TaskTraceAttributes` | task | ⚪ **有意不记** | RAG 侧 task 的 trace 属性载体 ⇒ C 侧无对应物 | 2026-10-26 |

> 📌 **原 13 条中的 9 条已补进 B 列**（2026-10-26）—— 补的是「**漏记**」不是「改写」
> （B 列是 RAG 侧**真实类名**）：`ArticleExceptions` · `MessageExceptions` · `ConversationEntity` ·
> `ConversationMessageEntity` · `NotificationEntity` · `LikeCursorIdGenerator` ·
> `LikeDomainFactOutboxMapper` · `LikeFactStreamMapper` · `TaskProgressMapper`。
> ⇒ 留下的 4 条**全部是「有意不记」**（C 侧确无对应物）。
>
> ⚠️ **补这 9 条时发现并修掉了两个根因**（不是文档笔误，是**机制缺陷**）：
> ① **单元格里的 `|` 未转义** ⇒ markdown 把它当列分隔符 ⇒ 整行被切成 6/7 列 ⇒ **B 列静默丢失**；
> ② **花括号 / 通配写法**（`{A,B,C}.java` · `.../mapper/*`）⇒ 机器认不出具体类名。
> ⇒ 现已由守门脚本第 ⑥ 重校验 + 「主表行形状」检查常态化拦截。

### 六·六、非文件级对照登记

> 第 ⑥ 重校验（指代消解）的例外登记 —— 消解后仍读不出「某个 `.java` 的落点」的行。
> 逐条核对后确认：**它们全部是「非文件级」对照或明确写了「无」** ⇒ **语义正确，不是缺口**。
> 共 26 条，**逐条清单在 `tools/check-mapping-coverage.py` 的 `NON_FILE_LEVEL` 常量里**（单一事实源；含形态、原因、核对日期）。

| 形态 | 条数 | 含义 |
|---|---:|---|
| 明确「无」 | 9 | B/C 侧明写「无独立文件」/「无此 DTO」/「D5 已删」等 ⇒ **机器读得出否定** |
| 模块 / 包级 | 6 | `like-api/` · `service/common/` · 进程拓扑等 ⇒ 对照的是**模块**不是文件 |
| 成员 / 方法级 | 5 | B 侧内部 struct / 包级函数 / 静态 map / enum ⇒ 对照的是**成员**不是文件 |
| proto / 生成物级 | 2 | `*.pb.go` / `*-proto/` ⇒ 由 `.proto` 生成，**不逐个手写类** |
| 进程级 | 1 | `2 个进程 → 1 个进程` |
| 配置级 | 1 | etcd → Nacos 服务发现 |
| 清单级 | 1 | B 侧各 rpc 模块 `@Import` 清单（10 份）→ C 侧 0 份 |
| 集合级 | 1 | B 侧 8 个 logic 合计 → C 侧 2 个 Service |

> ⚠️ **登记制**：新出现的不可判定行**必须**登记进 `NON_FILE_LEVEL`，否则第 ⑥ 重校验报红；
> 已登记但后来变得可判定的行 ⇒ 报「登记已失效」⇒ **防登记腐化**。
> 📌 **键 = `(文档相对路径, B 列归一化文本[:60])`** —— **D16 后用 B 列（RAG 侧）做键**（A 列已物理删除）⇒ ⚠️ B 列会随实现推进变化 ⇒ 键**不再稳定** ⇒ 登记条目须定期复核。



---

## 七、本目录的完整性自查（链接可达性）

> 与 RAG 的 `tools/drift-move.py verify` 同款思路：**校验必须由机器做，不能靠人眼扫。**

```bash
python3 - <<'EOF'
import re, pathlib
b = pathlib.Path('/Users/pr/Documents/GitHub/RAG-V1/docs/Mapping_Table')
bad = 0
for f in sorted(b.rglob('*.md')):          # ← rglob：文档在域子目录里
    for m in re.finditer(r'\]\(([^)]+)\)', f.read_text(encoding='utf-8')):
        t = m.group(1)
        if t.startswith(('http', 'file:')): continue
        p = (f.parent / t.split('#')[0]).resolve()      # ← 剥离 #anchor
        if not p.exists():
            print(f'❌ {f.relative_to(b)} → {t}'); bad += 1
print(f'断链 {bad}')
EOF
```

⚠️ **链接检查的两个容易踩的点**：
① 检查链接时必须**剥离 `#anchor`** —— 否则 `#43-落地的缺口` 会被当成文件名的一部分，
报出一个**并不存在**的断链（假阳性）。
② 必须用 **`rglob`** 扫子目录 —— 文档在 `<域>/` 里，`glob('*.md')` 只会看到根目录的 3 个文件。

> ⚠️ **第三个坑（在数「映射行」时踩到）** —— **不能按固定列下标取类型词**。
> `follow` / `like` / `task` 三域存在**比标准表多一列**的表（类型词落在第 5/6/7 个 `|` 之后），
> 固定取 `parts[4]` 会**漏算 60 行**（811 → 751）。
> 且**备注列允许为空**（admin 子域 13 行是 `| A | 同上 | 同上 | 类型 | |` ⇒ 非空格只有 4 个），
> 阈值取 5 又会再漏 13 行。
> ⇒ 正确判据：**扫全行找类型词 + 非空格 ≥ 4 + 类型词下标 ≥ 3**（已实现在 `check-mapping-coverage.py` 的 `count_map_rows`）。
