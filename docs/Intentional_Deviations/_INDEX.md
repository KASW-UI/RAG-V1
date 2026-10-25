# Intentional_Deviations 索引 —— 有意偏离档

> **记什么**：明知与 Go 不同、**经签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。

## 三类不可混谈

| 类别 | 含义 |
|---|---|
| **有意偏离** | 主动选择与 Go 不同（须签字） |
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

| 域 | 条目数 | 备注 |
|---|---|---|
| `common` | 0 | D1–D6 的结构性决策见 `../决策记录.md`，不进本档 |
| `like` | **2** | [DEV-LIKE-PROTO-001](like/_signed-deviations.md) · [DEV-LIKE-PROTO-002](like/_signed-deviations.md) |
| `comment` | **2** | [DEV-COMMENT-PROTO-001](comment/_signed-deviations.md) · [DEV-COMMENT-PROTO-002](comment/_signed-deviations.md) |
| 其余 8 域 | 0 | — |

### 已登记条目一览

| 条目 ID | 域 | 层 | 状态 | 一句话 |
|---|---|---|---|---|
| `DEV-LIKE-PROTO-001` | like | **L1** | 🏷 **已签字** | proto `package`：`pb` → `like`（gRPC 方法路径 `/pb.LikeService/*` → `/like.LikeService/*`） |
| `DEV-COMMENT-PROTO-001` | comment | **L1** | 🏷 **已签字** | proto `package`：`pb` → `comment`（gRPC 方法路径 `/pb.CommentService/*` → `/comment.CommentService/*`） |
| `DEV-LIKE-PROTO-002` | like | L4 | ✅ **已落地，待确认** | `java_package`：`com.rag.like.contract.proto` → `com.rag.proto.like` |
| `DEV-COMMENT-PROTO-002` | comment | L4 | ✅ **已落地，待确认** | `java_package`：`com.rag.comment.contract.proto` → `com.rag.proto.comment` |

> ⚠️ **002 两条是 proto 集中化的机械后果**，不是独立设计选择 —— 它们随 `proto/` 模块的落地自动生效。
> ✅ **2026-10-26 · D7：改动已落地** —— 10 个 proto 的 `option java_package` 全部改为 `com.rag.proto.<域>`（**实测**：根级 `proto/src/main/proto/*.proto` 的 option 块 + 生成物 `proto/target/generated-sources/protobuf/java/com/rag/proto/<域>/` 的 `package` 声明）。
> ⇒ 状态从「候选，建议签」更新为「**已落地，待确认**」⇒ **只差签字这一步**。

### 待办

- ⬜ **002 两条待签字确认** —— 改动已实施（`java_package` 实测为 `com.rag.proto.<域>`），只需使用者确认登记内容与实际一致。
- ⬜ **其余 8 域的 `java_package` 登记** —— 集中化是全 10 域动作（D7 已全部改完），其余 8 域的同类条目**待登记**（本档现只登记了 like / comment 两域）。
