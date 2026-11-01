# Intentional_Deviations / comment —— 已签字偏离

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-10-26**（逐行核对源文件，非估算）· **依据基准 2026-11-02**：**B 侧为唯一真理**（原始逐条来源下沉到 B 侧自有台账）

---

## DEV-COMMENT-PROTO-001 · proto `package` 由 `pb` 改为 `comment`（L1 · 已签字）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **已签字**（用户明确表示「是我改的」） |
| **分域** | comment 域（`comment.proto`） |
| **偏离类型** | **有意偏离**（L1 · wire 契约层） |
| **提出 / 签字** | 用户（2026-10-26 会话中直接确认） |

### 偏离内容

`.proto` 文件顶部的 **proto 包声明**（决定 gRPC 完整方法路径）：

| | **原始包名**（遗留） | **B 侧（RAG 仓）** | **RAG-V1（C）** |
|---|---|---|---|
| **proto `package`** | `package pb;` | `package comment;` | `package comment;` |
| **gRPC service 全名** | `pb.CommentService` | `comment.CommentService` | `comment.CommentService` |
| **完整方法路径** | `/pb.CommentService/CreateComment` | `/comment.CommentService/CreateComment` | `/comment.CommentService/CreateComment` |

⇒ ⚠️ **基准切换后的定性**：**B 侧自己已做过这次归一并已签字**（编号 `SD-2026-10-23-04`）⇒ **C 侧逐字承接 ⇒ C ≡ B** ⇒ 本条登记的是「**继承并承接 B 侧已签字的归一决策**」，**不是 C 侧自创的偏离**。

### B 侧出处（逐行核对）

- B 侧 `service/comment/comment-proto/src/main/proto/comment.proto:3` —— `package comment;`
- B 侧 `ci/contracts/proto/comment.proto:1-6` —— 头部注明「proto `package` 由原始来源的 `pb` 归一为 `comment`（**已签字**）」＋ 裁定编号 `SD-2026-10-23-04` ＋「原始 package 行原文：`package pb;`」
- B 侧台账 `docs/Intentional_Deviations/comment/proto.md` —— `SD-2026-10-23-04`（**已签字**）
- B 侧生成物 `service/comment/comment-proto/target/generated-sources/protobuf/grpc-java/com/rag/comment/comment/proto/CommentServiceGrpc.java:15` —— `SERVICE_NAME = "comment.CommentService"`（7 个方法全是 `/comment.CommentService/*`）

### RAG-V1 落点

- `proto/src/main/proto/comment.proto:3` —— `package comment;`（2026-10-26 D7 集中化后；旧路径 `service/comment/contract/src/main/proto/comment.proto` **已随 `comment-contract` 模块删除**）
- 生成物实测：`proto/target/generated-sources/protobuf/grpc-java/com/rag/proto/comment/CommentServiceGrpc.java` —— `SERVICE_NAME = "comment.CommentService"`

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **gRPC 方法路径** | ⛔ **改变** —— `/pb.CommentService/*` → `/comment.CommentService/*`（7 个方法 · 相对原始遗留包名） |
| **proto 消息全名** | ⛔ **改变** —— `pb.GetCommentReq` → `comment.GetCommentReq` |
| **`google.protobuf.Any` type URL** | ✅ **无影响** —— B 侧全仓**不使用** `anypb` / `proto.Any`（实测全仓扫描 `service/` 为空） |
| **message 名 / 字段名 / 字段号 / 类型 / 枚举值** | ✅ **零改动** —— 144 个 message/enum 中 comment 域的部分逐字保留 |
| **跨域调用** | ✅ **内部自洽** —— RAG-V1 侧 10 个域的 pb 全部由**同一套 proto** 生成 ⇒ 客户端 stub 与服务端注册的 service 全名天然一致 |

### 偏离理由（**B 侧已签字归一的理由** · B 侧台账 `SD-2026-10-23-04`）

与 [`DEV-LIKE-PROTO-001`](../like/_signed-deviations.md) 同款：B 侧（RAG 仓）把 `comment.proto` 的 `package pb;` 归一为 `package comment;` —— 判定为**原始来源生成时的历史遗留包名**（与 `like.proto` 同款），并非业务语义；B 侧已签字（2026-10-23），C 侧**原样承接**。

RAG-V1 统一改为域名后：

1. **10 个域的 package 命名规则一致**（域名前缀），消除 `pb` 这种无语义残留；
2. **RAG-V1 内部自洽** —— 全部 pb 同源生成，不存在「客户端 stub 与服务端注册名不一致」的断链；
3. 原始的 `package pb;` 与 `option go_package = "./pb";` 是同一处遗留的两个表现，RAG-V1 删 `go_package` 时保留 `package` 会造成「一半照搬、一半翻译」的不一致状态。

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖 proto `package` 声明**，**不覆盖** `option java_package`（L4 表达层，由 `DEV-COMMENT-PROTO-002` 承载）。
- **不得反向「修回」** `package pb;`。
- 本条**不豁免** L1 的其它部分：message 名 / 字段号 / 枚举值 / 错误码值 / HTTP path / Kafka topic **仍须逐字对齐 B 侧**。

---

## DEV-COMMENT-PROTO-002 · `option java_package` 随 proto 集中化而重定位（L4 · 待签字）

| 项 | 内容 |
|---|---|
| **状态** | ✅ **已落地，待确认**（2026-10-26 · D7 —— 改动已实施，等签字确认） |
| **分域** | comment 域（`comment.proto`） |
| **偏离类型** | **表述差异**（L4 · 表达层） |

### 偏离内容

| | **B 侧（RAG 仓）** | **RAG-V1（C）** |
|---|---|---|
| **Maven 模块** | `service/comment/comment-proto` | `proto`（artifactId `proto`）—— **已落地** |
| **java_package** | `com.rag.comment.comment.proto` | `com.rag.proto.comment` |
| *（C 侧集中化前的旧值）* | — | `com.rag.comment.contract.proto`（`service/comment/contract` · **已删除**） |

⇒ ⚠️ **B ≠ C**：B 侧的包根是**按模块名派生**（`comment-proto` ⇒ `com.rag.comment.comment.proto`），C 侧集中化后包根是 **`com.rag.proto`**（模块坐标 `com.rag:proto`）⇒ ⇒ 属 **D7 proto 集中化的机械后果**，**不是抄错**。

### 理由

- **包路径必须反映模块坐标** —— 模块是 `com.rag:proto`，包根即 `com.rag.proto`，`<域>` 作为子包。
- 原 `com.rag.comment.contract.proto` 里的 `contract` 段指向**已删除的 `*-contract` 模块**（D7 · 2026-10-26）⇒ 集中化后成为悬空指称。
- **wire 契约零影响** —— `java_package` 不参与方法路径与消息全名的推导（那由 proto `package` 决定，见 DEV-COMMENT-PROTO-001）。

### ⚠️ 硬约束：必须按域分子包

全仓 **12 个 message/enum 名跨 proto 重复**（`admin` vs `users` 重叠 8 个、`follow` vs `message` 重叠 `BaseResp`）
⇒ `java_multiple_files = true` 下同名会生成同名 Java 类 ⇒ **必须按域分子包**，不可压平为 `com.rag.proto`。

### 证据

- 跨 proto 重名清单：`proto/src/main/proto/*.proto` 全量扫描（144 个 message/enum 名，冲突 12 个）。
- 包名实测：集中化后 `proto/target/generated-sources/protobuf/java/com/rag/proto/comment/*.java` 的 `package` 声明。
- B 侧实测：`service/comment/comment-proto/src/main/proto/comment.proto:5` —— `option java_package = "com.rag.comment.comment.proto";`

---

## 引用

- 决策台账 → [`../../决策记录.md`](../../决策记录.md)（**D1 · D7**）
- 推导依据 → [`../../完全spring化的文件树.md`](../../完全spring化的文件树.md) §5.1 · §5.2
- 两方对照 → [`../../Mapping_Table/_INDEX.md`](../../Mapping_Table/_INDEX.md)
- 漂移档 → [`../../DRIFT/_INDEX.md`](../../DRIFT/_INDEX.md)
- 降级档 → [`../../L3-降级清单.md`](../../L3-降级清单.md)
- **B 侧台账（原始逐条来源下沉处）** → `/Users/pr/Documents/java&golang/RAG` 的 `docs/Intentional_Deviations/comment/proto.md`（`SD-2026-10-23-04`）· `ci/contracts/proto/comment.proto`（契约基准头）
