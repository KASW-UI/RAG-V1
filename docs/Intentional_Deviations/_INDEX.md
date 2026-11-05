# Intentional_Deviations 索引 —— 有意偏离档

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。

## 三类不可混谈

| 类别 | 含义 |
|---|---|
| **有意偏离** | 主动选择与 **B 侧** 不同（须签字） |
| **框架限制** | 想对齐但语言/框架做不到 |
| **表述差异** | 语义已对齐、仅字面不同 |

## 档位结构

```
docs/Intentional_Deviations/
├── _INDEX.md
└── <域>/
    ├── _signed-deviations.md   # 已签字
    └── <主题>.md               # 按议题分档
```

## 登记规则

1. **登记的前提必须自己先逐行核对** —— 「两侧确实不同」要自己确认，不把错误前提推给别人签字。
2. **登记完就躺着** —— 登记本身是交付物，不要打包推给用户逐条拍板。
3. **条目 ID 规范**：`<域>-<组件>-<序号>`，如 `DEV-MSG-RPC-002`。
4. **⚠️ L3 降级项不在本档** —— 降级是「主动放弃某一层语义」，走 `../L3-降级清单.md`；本档记「两侧确实不同」。

## 当前条目

> ⚠️ **口径**：下表的**条目数与状态分布是机器复算的**（逐文件扫 `## DEV-*` 标题 ⇒ 抽取每条的 `| **状态** |` 行）
> ⇒ **核对日期 2026-11-05**（合并 hot / task / comment / favorite 四域实现后重算；**此前本表自报 6 条 ⇒ 实为 47 条**）。
> ⇒ **逐条明细以各域文件为权威副本**（下表「文件」列可直达）。

| 域 | 条目数 | 文件 | 状态分布（机器复算 · 2026-11-05） |
|---|---|---|---|
| `global`（共享层 `common/`） | **1** | [common-bearer-token-extractor.md](global/common-bearer-token-extractor.md) | 候选建议签 1 |
| `article` | **2** | [content-audit-fail-loud.md](article/content-audit-fail-loud.md) · [http-404-extra-vary-headers.md](article/http-404-extra-vary-headers.md) | 候选建议签 2 |
| `like` | **2** | [_signed-deviations.md](like/_signed-deviations.md) | 已签字 1 · 已落地待确认 1 |
| `comment` | **2** | [_signed-deviations.md](comment/_signed-deviations.md) | 已签字 1 · 已落地待确认 1 |
| `favorite` | **12** | [_signed-deviations.md](favorite/_signed-deviations.md) | 已签字 9 · C 侧专属 3 |
| `follow` | **8** | [_signed-deviations.md](follow/_signed-deviations.md) | 候选建议签 7 · **已撤销 1**（`DEV-FOLLOW-007` · 登记前提被运行时证据证伪） |
| `hot` | **6** | [_signed-deviations.md](hot/_signed-deviations.md) | 已登记 4 · 已修复 1 · 已落地待确认 1 |
| `users`（`users-user` 子域） | **10** | [_signed-deviations.md](users/_signed-deviations.md) | 候选建议签 8 · 已登记 1 · 全仓统一决策 1 |
| `admin` | **4** | [_signed-deviations.md](admin/_signed-deviations.md) | 候选建议签 3 · 状态栏未写 1 |
| `message` · `task` | **0** | — | — |
| **合计** | **47** | — | 候选建议签 20 · 已签字 11 · 已登记 5 · 已落地待确认 3 · C 侧专属 3 · 已修复 1 · 已撤销 1 · 全仓统一决策 1 · 状态栏未写 2 |

### 已登记条目一览

> ⚠️ **本表只逐条列出「proto 集中化 / 共享层」这 6 条** —— 它们是**跨域**条目 ⇒ 需在索引层**逐条可见**
> ⇒ ⇒ **其余 41 条按域归口**（**各域文件是权威副本**）⇒ 逐条状态见上表「文件」列。

| 条目 ID | 域 | 层 | 状态 | 一句话 |
|---|---|---|---|---|
| `DEV-LIKE-PROTO-001` | like | **L1** | 🏷 **已签字** | proto `package`：`pb` → `like`（gRPC 方法路径 `/pb.LikeService/*` → `/like.LikeService/*`） |
| `DEV-COMMENT-PROTO-001` | comment | **L1** | 🏷 **已签字** | proto `package`：`pb` → `comment`（gRPC 方法路径 `/pb.CommentService/*` → `/comment.CommentService/*`） |
| `DEV-LIKE-PROTO-002` | like | L4 | ✅ **已落地，待确认** | `java_package`：`com.rag.like.contract.proto` → `com.rag.proto.like` |
| `DEV-COMMENT-PROTO-002` | comment | L4 | ✅ **已落地，待确认** | `java_package`：`com.rag.comment.contract.proto` → `com.rag.proto.comment` |
| `DEV-ARTICLE-AUDIT-001` | article | **L2** | 🏷 **候选，建议签** | 内容安全 / 图片广告检测**无对端**（`security` 域范围外）⇒ 审核链路**恒 fail-loud**（B 侧 `UnavailableContentSecurityClient` 恒抛 ⇒ fail-loud 语义保留） |
| `DEV-GLOBAL-SEC-001` | global（共享层） | **L1** | 🏷 **候选，建议签** | `BearerTokenExtractor` 前缀剥离判定：C 侧 `> 6` vs B 侧 `> 7`（**D16 基准切换后新增** —— 原本「按上游改」的差异变成 C≠B 的实差异） |

> ⚠️ **002 两条是 proto 集中化的机械后果**，不是独立设计选择 —— 它们随 `proto/` 模块的落地自动生效。
> ✅ **2026-10-26 · D7：改动已落地** —— 10 个 proto 的 `option java_package` 全部改为 `com.rag.proto.<域>`（**实测**：根级 `proto/src/main/proto/*.proto` 的 option 块 + 生成物 `proto/target/generated-sources/protobuf/java/com/rag/proto/<域>/` 的 `package` 声明）。
> ⇒ 状态从「候选，建议签」更新为「**已落地，待确认**」⇒ **只差签字这一步**。

### 待办

- 🏷 **`DEV-ARTICLE-AUDIT-001` 建议签** —— 「两侧确实不同」的前提已逐行核对（B 侧 `UnavailableContentSecurityClient` 恒抛 + `docs/DRIFT/article/rpc.md` §12.1「`S-5` 改判范围外」· B 侧确实**没有** `security` 域对端）；登记本身是交付物，**不打包推给用户逐条拍板**。
- ⬜ **002 两条待签字确认** —— 改动已实施（`java_package` 实测为 `com.rag.proto.<域>`），只需使用者确认登记内容与实际一致。
- ⬜ **其余 8 域的 `java_package` 登记** —— 集中化是全 10 域动作（D7 已全部改完），**proto 包名类**条目现登记了 **like / comment / users** 三域（`DEV-LIKE-PROTO-00{1,2}` · `DEV-COMMENT-PROTO-00{1,2}` · `DEV-USER-007`）⇒ ⇒ **其余 7 域的同类条目待登记**（2026-11-05 机器复算）。
- 🏷 **`DEV-GLOBAL-SEC-001` 建议签** —— 两侧实现已逐行核对（`BearerTokenExtractor.java` B 侧 `:75` vs C 侧 `:81`）⇒ **两侧确实不同**；⚠️ **B 侧自己的 javadoc 与 B 侧自己的代码不一致**（注释写 `> 6`、代码是 `> 7`）⇒ 属 B 侧移植偏差 ⇒ C 侧保持 `> 6`（上游口径）**不是移植错误** ⇒ 但**改回 `> 7` 属安全语义收紧**，不由迁移任务顺手决定 ⇒ 登记待确认。

> ✅ **2026-11-05（`global` 收口域 · 合并工作树轮）· 本索引重算** —— 合并 hot / task / comment / favorite 四域实现后，
> **各域的签字偏离台账随之进入主干** ⇒ ⇒ **本索引的「当前条目」表此前自报 6 条 ⇒ 机器复算实为 47 条**（`favorite` 12 · `users` 10 · `follow` 8 · `hot` 6 · `admin` 4 · `article` 2 · `comment` 2 · `like` 2 · `global` 1 · `message`/`task` 0）
> ⇒ ⇒ **已按实测重写**（口径 = 逐文件扫 `## DEV-*` 标题 ⇒ 抽 `| **状态** |` 行 ⇒ 逐条计状态分布）⇒ ⇒ **未新增 / 未删除任何一条偏离条目**（**只改索引**）
> ⇒ ⇒ ⚠️ **登记类事项**按纪律「**登记完就躺着**」⇒ **不打包推给用户逐条拍板**。

