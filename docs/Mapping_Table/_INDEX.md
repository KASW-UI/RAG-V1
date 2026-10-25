# Mapping_Table 索引 —— Go ↔ RAG ↔ RAG-V1 三方文件名对照

> **本目录目的**：把三棵树的文件名与职责**逐条对应**起来 ——
> **A 列 Go 真理之源** → **B 列 RAG（Go 部署形态版）** → **C 列 RAG-V1（Spring 惯用目标）**。
> 填 RAG-V1 实现时，从 C 列反查 A 列 ⇒ **去 Go 源码逐字对照**。
>
> 📌 **格式契约见 [`README.MD`](README.MD)** —— 章节骨架、表头、映射类型词表、覆盖度自查方法。
> 📌 **格式样例**：[`/Users/pr/Documents/java&golang/RAG/docs/MappingTable/users.md`](file:///Users/pr/Documents/java&golang/RAG/docs/MappingTable/users.md)（RAG 那份的同域前身）。
>
> **核对日期 2026-10-25** · 守门脚本 `tools/check-mapping-coverage.py`

---

## 一、三列语义

| 列 | 仓库 | 角色 | 主代码文件数 |
|---|---|---|---|
| **A** | `Sea-RideTheWind_golang` | **真理之源**（冻结，只读） | **482** 个 `.go` |
| **B** | `RAG` | Go 部署形态版（api/rpc 分离） | **695** 个 `.java` |
| **C** | `RAG-V1` | **Spring 惯用目标形态**（api/rpc 合并 + **D7 包压平**） | **549** 个 `.java` |

> ⚠️ **三列数量不成 1:1** —— C 比 B 少 146 个文件，来自 ① api/rpc 合并（D1）② `service/common` → `common` starter（D3）③ 生态替代（D4）。
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

| 域 | 文档 | 行数 | C 列覆盖 | A 列覆盖 | 映射行 |
|---|---|---:|---|---|---:|
| **users** | [_shared.md](users/_shared.md) · [users-common.md](users/users-common.md) · [user/user.md](users/user/user.md) · [admin/admin.md](users/admin/admin.md) | 410 + 200 + 249 + 274 = **1,133** | **103 / 103 = 100%** | **114 / 114 = 100%** | 127 |
| **common** | [common.md](common.md) | 649 | **56 / 56 = 100%** | **11 / 11 = 100%** | 80 |
| **article** | [article.md](article/article.md) | 475 | **51 / 51 = 100%** | **45 / 45 = 100%** | 69 |
| **comment** | [comment.md](comment/comment.md) | 472 | **69 / 69 = 100%** | **58 / 58 = 100%** | 82 |
| **favorite** | [favorite.md](favorite/favorite.md) | 508 | **60 / 60 = 100%** | **50 / 50 = 100%** | 84 |
| **follow** | [follow.md](follow/follow.md) | 548 | **37 / 37 = 100%** | **46 / 46 = 100%** | 114 |
| **hot** | [hot.md](hot/hot.md) | 530 | **26 / 26 = 100%** | **26 / 26 = 100%** | 56 |
| **like** | [like.md](like/like.md) | 501 | **67 / 67 = 100%** | **51 / 51 = 100%** | 79 |
| **message** | [message.md](message/message.md) | 565 | **51 / 51 = 100%** | **55 / 55 = 100%** | 68 |
| **task** | [task.md](task/task.md) | 408 | **29 / 29 = 100%** | **26 / 26 = 100%** | 53 |
| **合计** | **13 份**（10 域，users 域拆 4 份） | **5,789** | **549 / 549 = 100%** | **482 / 482 = 100%** | **812** |

> 📌 **users 域拆分的守恒性**（2026-10-25 拆分后实测）：
> 拆分前单文件 515 行 / 127 映射行 ⇒ 拆分后四份合计 **1,011 行 / 127 映射行**（行数增加是**导航与分节标题**带来的，非内容膨胀）。
> 机器比对：**原 222 个文件名 token → 四份命中 222 个、漏 0 个**；**127 映射行跨文件重复签名 0**（每行只出现一处）。
> ⇒ **内容零丢失、零重复。**
>
> ⚠️ **数「映射行」不能按固定列下标取** —— `follow` / `like` / `task` 三域存在**比标准表多一列**的表（映射类型落在第 5/6/7 个 `|` 之后而非第 4 个）。
> ⇒ 计数必须**扫全行找类型词**，固定取 `parts[4]` 会**漏算 60 行**（follow **−47**、like **−2**、task **−11** ⇒ 实测 811 行会被数成 751 行）。

### 映射类型分布（812 条映射行）

| 类型 | 条数 | 占比 | 含义 |
|---|---:|---:|---|
| `1:1` | 446 | 55% | 一一对应（多数是 DTO / Entity / Mapper 这类直译件） |
| `1:N` | 172 | 21% | 一个 Go 文件拆成多个 Java 文件（生态替代、职责细分） |
| `N:1` | 181 | 22% | 多个 Go 文件合进一个 Java 文件（**api/rpc 合并的主因**） |
| `C 新增` | 13 | 2% | Go / RAG 都没有，RAG-V1 形态自身需要（starter / auto-config 等） |

---

## 三、🔴 三方对照暴露的**落点缺口**（75 条）

> **这是本目录最有价值的产出。** 三份文档逐条对照后发现的「**Go 有职责、C 侧无落点**」或「**C 侧 javadoc 与 A/B 事实冲突**」。
> ⚠️ **这些不是行为对齐结论** —— 只是「文件名 / 职责落点」层面的问题，需由各域接手者回 A 列逐条确认后再动代码。
> 逐条见各域文档的 §缺口 小节。

### 🔴 高优先级（13 条）

| 域 | 缺口 |
|---|---|
| **users** | **Go 的身份解析器在 C 侧无承载**（`identity/resolve.go`：`ClaimedUID` → `GetUser` → status presence 检查 → `SubjectRef`） |
| **users** | **幂等 DDL 重放机制在 C 侧无执行器、无脚本**（B 侧有 `SchemaInitializer` + `db/01..04_*.sql`） |
| **task** | **Kafka 消费侧整体缺失** —— B 侧 11 个 `mq/*.java`，C 侧 `messaging/` 只有 2 个类（`TaskKafkaTopics` + `TaskRewardPublisher`）、**9 个消费者无落点**，全仓 `@KafkaListener` 零命中 ⇒ sink 的「消费 + 1s 定时 flush」无 C 落点 |
| **task** | **`goka.Persist`（RocksDB 本地表）无生态等价物** ⇒ 存储后端需拍板（PG 表 / Redis `HINCRBY` / 专用 KV） |
| **task** | **`WebMvcConfig` 的 javadoc 与本域事实冲突** —— javadoc 写「注册 JwtAuthInterceptor」，但 Go 侧 `routes.go:16-25` **无 `rest.WithJwt`** ⇒ 本域**零鉴权** |
| **task** | **`TaskApiResponseAdvice` 的职责未定** —— Go 侧 `api/task.go` **无 `httpx.SetOkHandler`** ⇒ 成功响应**无信封** |
| **common** | **`communityfact` 的「三域共享」在 Go 侧只有两域**（favorite 自己写了一套 `rpc/internal/authority/http.go` + `model.NewFavoriteDCEventSender`） |
| **favorite** | **错误文案串味** —— `ErrorNotFound(5002)` 变成了英文 `not found`，且 `Success="OK"` / `Error="FAIL"` |
| **comment** | `CommentController` javadoc 与 Go `.api` 端点清单不一致（2 处） |
| **comment** | `CommentKafkaMsg` 与 `service/comment/src/main/java/com/rag/comment/messaging/CommentAuditMessage.java` 的载荷字段归属未锁定 |
| **comment** | `bootstrap/` 下的**装配落点已齐**（2026-10-25 补齐 `CommentFactProcessLogging`）但类内容仍为空壳；`db/migration/` 目录内容仍为空 |
| **article** | C 列整体职责缺口（详见 §1.5） |
| **follow** | 原 `api/` vs `rpc/` 职责边界（D7 已压平，边界问题转为「单层包内的归属」）、`FollowApiSupport` 归属、`WebMvcConfig`/`FollowController` javadoc 与事实冲突 |

### 🟠 中优先级（要点）

| 域 | 缺口 |
|---|---|
| **users** | **错误契约在两个子域间不对称**（**F-2 · 2026-10-26 D7 后由 🔴 降级为 🟠**）—— C 的 `service/users/user/src/main/java/com/rag/user/contract/` 有 `ErrCodes`+`ErrMsg`，但 **admin 侧无 `contract/` 包**（`admin-contract` 模块已随 D7 删除）；公共库 `service/users/common/src/main/java/com/rag/users/common/` 那份 admin 尚未引用（但 pom 依赖已接上） |
| **task** | 持久层空位（`db/migration/` 只有 `.gitkeep`、yml 无 `flyway`/`hikari`/`id-type`）· RAG-V1 `common/` 无 `RpcServerInterceptorFactory`（Go 的 unary interceptor 无落点）· MQ 载荷契约 C 侧只剩 like 域那一份 · `grpc-client-spring-boot-starter` 本域无使用方 · **Kafka 消费侧仍整体缺失**（`messaging/` 只有 `TaskKafkaTopics` + `TaskRewardPublisher`，B 侧 11 个 `mq/*.java` 里的 9 个消费者无落点，全仓 `@KafkaListener` **零命中**）|
| **like** | Kafka 消费入口（listener / ack 契约 / 失败即丢弃不重投）无落点 · `like_outbox_event → article-hot-events` 发件箱中继无落点 · Jackson 2 `ObjectMapper` Bean 无落点 · `like` / `like-api` 配置段整个缺失 · DDL 迁移脚本为空 |
| **message** | `controller/`（两个 Controller）· `interceptor/`（两组 JWT）· `metrics/` · `bootstrap/` · `service/` · `service/message/src/main/java/com/rag/message/contract/` 均有职责缺口 |
| **favorite** | `POST /favorite/v1/folder/create` 端点缺失 · public 路径集 · preflight 触发入口 · DDL 迁移脚本 · `JsonbTypeHandler` / MP 排序桥的归属 · `GetFavoritesByFolderReq.FolderId` 的取参位置 · 域内 gRPC client · 端口号 |
| **common** | `GoStyleGrpcStatusMapper` 与 `RagGrpcExceptionMapperAutoConfiguration` **职责重叠** · 设计文档 §5.1 列了 `contract/ErrorCode.java` 但**实际树里没有** · 雪花 ID 有**两个落点** · `GoStyleJsonBodyConverterConfig` 是「注册点」还是「配置类」 |
| **follow** | `db/migration/` 为空 · `application.yml` 缺段 · `*Properties` 聚合边界 · `FollowBlacklistInterceptor` + `FollowJwtAuthInterceptor` · `GoStyleLimitOffset` |

### 🟡 低优先级（要点）

| 域 | 缺口 |
|---|---|
| **users** | 表名 A 与 B/C 不同（Go `users`/`admins`/`admin_invites` vs B/C `users_user`/`admin_admin`/`admin_invite`）· HTTP DTO 命名 1 处不一致 · `UserController`/`AdminController` javadoc 的 method 分布与 A/B 都不一致 · `AdminController` javadoc 少列 1 个端点（12 vs 13）· HTTP 超时机制在 C 侧未落点（Go `default=3000`） |
| **task** | `TaskApiProperties` 的聚合边界未定 |
| **like** | `target_ids` 的 null 元素语义无独立文件 · 2 条 Jackson 强转补强是否还在未核对 · `like_api_reject_total` 的「Go 侧未注册」与 C 侧自动注册差异未声明 |

---

## 四、三条读表纪律（每份域文档开头都重复了这条）

1. ⚠️ **B 列不是 Go 的替身** —— RAG 在 users/like/message/common/article 五域已被判定 `NOT EQUIVALENT`。
   **拿 B 列当 Go 的替身 = 把漂移固化进 RAG-V1。** 一切行为判断以 **A 列 Go 源码**为准。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表只回答「**文件名 / 职责的对应关系**」，
   **不回答「行为是否一致」**。行为对齐状态看 `docs/DRIFT/<域>/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 Java 文件目前都只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么」，不是「已经有什么」。**

---

## 五、目录分工（与 RAG 的 `docs/MappingTable` 一致）

| 节 | 记什么 | 为什么 |
|---|---|---|
| 0.5. **B → C 结构映射速查表** | RAG 的哪个包 → RAG-V1 的哪个包（**实测**） | 一眼看出打平/合并/改名后的落点 |
| 1. 包结构对照 | A / B / C 三列 + 映射类型 + 备注 | 翻某个文件时一眼找到三方对应 |
| 2. 持久层差异 | ORM / Mapper / Schema 演进 / 事务 / 雪花 ID | RAG-V1 与 RAG 的持久层差异 |
| 3. HTTP / DI 差异 | 路由 / Controller / Service 分层 / 错误表达 / 配置 / 进程拓扑 | RAG-V1 与 RAG 的装配差异 |
| 4. 公共模块对照 | 共享件归属与边界 | 跨域共享的代码在哪 |
| 5. 决策锁 | 「**N 处同时改**」+「**为什么换**」 | 复刻到下一个域时按清单改，不漏 |
| 6. 跨模块全局约定 | proto 字段号 / 错误码 / JWT claims / 表名 / HTTP path / Kafka topic / 指标名 | 这几项所有域共享，不会因改某域而漂 |
| 7. 覆盖度自查 | C / A 列逐文件覆盖 + 映射类型分布 + 缺口清单 | 本表的可信度自证 |

---

## 六、守门脚本

| 脚本 | 作用 | 何时跑 |
|---|---|---|
| `tools/check-mapping-coverage.py` | **三重校验**：① **从文件系统反查**每份文档的 C / A 列覆盖率（**不采信文档自报数字**）② **`_INDEX.md` 自报数字对账**（本表的行数 / 覆盖 / 映射行 vs 实测值）③ 映射行计数（扫全行找类型词） | 改完任何一份域文档后；**提交前必跑**（EXIT=0 才算通过） |

> ⚠️ **脚本按文件计数，不按文件名去重** —— users 域的 `user/` 与 `admin/` 两个子域下存在同名文件
> （如各自的 `UserController.java`）⇒ 去重会漏计 18 个。
> ⚠️ A 列的「无对应物」豁免靠免责措辞匹配（`无对应物` / `刻意为空` / `不迁` / …）⇒
> **文档里若写了理由，就不算缺失**；没写理由 ⇒ 报缺口。
>
> 📌 **自报数字为什么会漂**（2026-10-25 实测）：`favorite` 域文档被 worker 改完后行数从 447 变 473，
> 而本表仍写 447 ⇒ **索引里的数字是「写完那一刻的快照」，不是事实**。
> ⇒ 现已由脚本第 ② 项做**机器对账**（10 个域 × 4 项 = 40 个数字），漂了就 `EXIT=1`。
> 负向测试已做：故意注入 2 处漂移 ⇒ 脚本如实报出并 `EXIT=1` ⇒ **这不是不会失败的假守卫**。

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
