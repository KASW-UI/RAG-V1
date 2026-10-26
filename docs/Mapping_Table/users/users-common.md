# Mapping_Table/users/users-common.md — users-common 公共库三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标形态）三棵树的 **users 域公共库 `users-common`** 文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 该库路径 | 角色 |
> |---|---|---|---|
> | **A（Go 真理之源）** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | `service/user/common/`（**单数**） | 冻结真源，唯一行为判定基准 |
> | **B（RAG，Go 形态版）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/users-common/`（**复数**） | Go 部署拓扑（api/rpc 分离）的 Spring 复刻 |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/common/`（**短名**）+ 仓库根 `common/`（D3 上提） | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（那份是 Sea-Java 镜像 ↔ RAG；本份是 Go ↔ RAG ↔ RAG-V1）。
> **域级共享层**：[`_shared.md`](./_shared.md) —— §0 读表须知（完整版）· §1 目录形态决策锁 · §2~§6 跨子域对照 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结。

### 导航

| 文档 | 装什么 | Go 出处 |
|---|---|---|
| [`_shared.md`](./_shared.md) | 域级共享层 | `service/user/`（全域） |
| **本文件** | **users-common 公共库** | Go `service/user/common/` |
| [`user/user.md`](./user/user.md) | user 子域 | Go `service/user/user/` |
| [`admin/admin.md`](./admin/admin.md) | admin 子域 | Go `service/user/admin/` |

---

## 〇、读表须知（精简）

### 0.1 三条不可违反的纪律（照抄 [`README.MD`](../README.MD) §七）

1. ⚠️ **B 列不是 Go 的替身** —— RAG 在 `users` 域已被判定 `NOT EQUIVALENT`（见 `RAG/docs/DRIFT/users/`）。
   **一切行为判断以 A 列 Go 源码为准**；本表只回答「文件名 / 职责的对应关系」。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**不下**「已对齐 / 未对齐」结论；行为状态看 `RAG/docs/DRIFT/users/` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 `.java` 目前只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

> 📌 **完整版读表须知见 [`_shared.md` §〇](./_shared.md#〇读表须知)** —— 另含 §0.2 本表的边界 · §0.3 映射类型的 4 种写法 · §0.4 A/B/C 规模基线。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `users/users-common/`（模块） | `service/users/common/`（artifactId **`users-common`**）| 公共库保留（**目录 ≠ artifactId**：目录叫 `common`）|
| `users/users-common/…/errmsg/`（`ErrorCode.java`，1 个类装全部） | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `…/ErrMsg.java` | **1 → 2**（码值集 / 文案映射）|
| `users/users-common/…/jwt/`（`JwtUtil` / `BearerTokenExtractor`） | `common/src/main/java/com/rag/common/security/`（同名类）| **上收根级 `common/`**（D3）|
| `users/users-common/…/cryptx/`（`PasswordUtil`） | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | **上收根级 `common/`**（D3）|
| `users/users-common/…/http/`（`GoStyleCoercion` / `GoStyleStringInt64` / `GoStyleRequired`） | `common/src/main/java/com/rag/common/web/`（同名类）| **上收根级 `common/`**（D3）；`GoStyleRequired*` 一族已被 D5 删除 |
| `users/users-common/…/ddl/`（`SchemaInitializer`） | **—（C 侧无落点）** | ⚠️ 幂等 DDL 重放执行器仍缺（F-3）|
| `users/users-common/`（proto） | 根级 `proto/`（pb 包名 `com.rag.proto.user`）| **集中化**（D7）|

## 1. 包结构对照

### 1.3 users-common 公共库（Go `service/user/common/`，3 个文件 + B 引入件）

> ⚠️ **`users-common` 是域内公共库，不是第三个域**（`AGENTS.md` §2.0 命名约定第 2 条）。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/common/errmsg/errmsg.go` | `users-common/.../errmsg/ErrorCode.java`（**1 个类装全部**） | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:N | Go 的 `const` 块 + `codeMsg` map + `GetErrMsg(code)` → **C 侧拆成 2 个类**（码值集 / 文案映射）。⚠️ **admin 侧无 `contract/` 包**（`admin-contract` 模块已随 D7 删除）⇒ admin 若要引用错误契约，取**本公共库这份**（admin 的 pom 已依赖 `users-common`）⇒ 子域不对称见 §7.4 F-2 |
| `service/user/common/jwt/jwt.go` | `users-common/.../jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | 1:1 | D3：共享层上提到仓库根 `common/` starter（`article/rpc` + `knowledge/api` 也用 ⇒ 真正跨域共用）；`common/` 不在 `service/` 下 ⇒ **归属见 `common.md`** |
| `service/user/common/cryptx/password.go` | `users-common/.../cryptx/PasswordUtil.java` | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | 1:1 | 同上 |
| —（Go 侧无对应物：Go 的 `rest.WithJwt` 内部解析 `Authorization` 头） | `users-common/.../jwt/BearerTokenExtractor.java` | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | 1:1 | Go 侧无对应物（原因）：B 引入的 `Bearer ` 前缀提取器；C 归位到 `common/src/main/java/com/rag/common/security/` ⇒ **归属见 `common.md`** |
| —（Go 侧无对应物：go-zero `httpx.Parse` 内置必填校验） | `users-common/.../http/GoStyleRequired.java` + `GoStyleRequiredFields.java` + `GoStyleRequest.java` + `GoRequiredFieldException.java` | **（D5 已删）** | 1:1 | Go 侧无对应物（原因）：D5 决定删除手写校验器，改用 **Bean Validation** ⇒ **L3 降级**：错误文案从 go-zero 的 `field "uid" is not set` 变为 Bean Validation 文案 |
| —（Go 侧无对应物：go-zero `mapping` 的类型强转判定） | `users-common/.../http/GoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` | 1:1 | Go 侧无对应物（原因）：Jackson `CoercionConfig` 原生机制，C 仅归位 ⇒ **归属见 `common.md`** |
| —（Go 侧无对应物） | `users-common/.../http/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | 1:1 | Go 侧无对应物（原因）：uid 序列化为字符串（避免精度丢失）⇒ **归属见 `common.md`** |
| —（Go 侧无对应物：Go 用 GORM `AutoMigrate`，无状态幂等） | `users-common/.../ddl/SchemaInitializer.java` | **（无对应物）** | 1:1 | Go 侧无对应物（原因）：⚠️ C 侧 `common/` **无 `SchemaInitializer`**，且两个 `*-service` 的 `db/migration/` 只有 `.gitkeep` ⇒ **幂等 DDL 重放机制在 C 侧无执行器、无脚本**（见 §7.4 F-3） |
| —（Go 侧无对应物：Go 的 `serializer:json` 由 GORM 提供） | `user-rpc/.../mapper/JsonTextTypeHandler.java` + `admin-rpc/.../mapper/JsonTextTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` | 1:1 | Go 侧无对应物（原因）：`extra_info`（**text** 列）↔ `Map<String,String>` 的 TypeHandler；D3 收进 starter ⇒ **归属见 `common.md`** |
| —（Go 侧无对应物） | `admin-rpc/.../mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | 1:1 | Go 侧无对应物（原因）：PG `jsonb` 列的 TypeHandler（MP 自带 `JacksonTypeHandler` 会把值绑成 `varchar` ⇒ jsonb 拒收）⇒ **归属见 `common.md`** |

---

## 2. 持久层差异（本库相关行）

> ⚠️ 原 §2 表的 **10 行中，users-common 专属行为 0 行**（行级悲观锁 / 唯一约束判定 2 行属 admin 子域，其余 8 行为跨子域行）
> ⇒ **本库相关行见 [`_shared.md` §2 持久层差异](./_shared.md#2-持久层差异)**。
> ⚠️ **本库在持久层上的落点**（`SchemaInitializer` 执行器）在 §4 公共模块对照里 ⇒ 见 §4。

---

## 3. HTTP / DI 差异（本库相关行）

> ⚠️ 原 §3 表的 **17 行全部是跨子域行**（每行同时写 user 与 admin）
> ⇒ **本库相关行见 [`_shared.md` §3 HTTP / DI 差异](./_shared.md#3-http--di-差异)**。

---

## 4. 公共模块对照（与本库相关行）

> ⚠️ RAG-V1 的 `common/` 在仓库根，不在 `service/` 下 ⇒ 下表**只点名与 users 域相关的件并说明归属**，
> 完整清单由 **[`Mapping_Table/common.md`](../common.md)** 负责。

| 能力 | A Go 出处 | B RAG 位置 | C RAG-V1 位置 | 归属说明 |
|---|---|---|---|---|
| 错误码 / 文案 | `service/user/common/errmsg/errmsg.go` | `users-common/.../errmsg/ErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `ErrMsg.java` + `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `ErrMsg.java` | **域内归属**（users 域私有）⇒ 本表 §1.3 负责；**不得上提到 `common/`**（各域码值/文案/语言不同） |
| JWT 签发 / 验签 | `service/user/common/jwt/jwt.go` | `users-common/.../jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | **全局归属**（`article/rpc` + `knowledge/api` 也用）⇒ `common.md` 负责 |
| `Bearer ` 前缀提取 | —（go-zero 内置） | `users-common/.../jwt/BearerTokenExtractor.java` | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | **全局归属** ⇒ `common.md` 负责 |
| 密码散列 | `service/user/common/cryptx/password.go` | `users-common/.../cryptx/PasswordUtil.java` | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | **全局归属** ⇒ `common.md` 负责 |
| go-zero 风格 JSON 强转 / `string→int64` | —（go-zero `mapping`） | `users-common/.../http/GoStyleCoercion.java` / `GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/Coercion.java` / `GoStyleStringInt64.java` | **全局归属** ⇒ `common.md` 负责 |
| go-zero 风格必填校验 | —（go-zero `httpx.Parse`） | `users-common/.../http/GoStyleRequired*.java` 等 4 个 | **已删**（D5）⇒ 改由 Bean Validation 承担 | 降级项登记在 `L3-降级清单.md` D-01 |
| DDL 幂等重放执行器 | `model/db.go` 的 `AutoMigrate` | `users-common/.../ddl/SchemaInitializer.java` | **缺失**（C 侧 `common/` 无、`db/migration/` 空） | ⚠️ **待补**（F-3） |

**决策锁**

**换共享层归属（域内 `users-common` → 全局 `common/` starter）4 处同时改**：
1. `users-common/.../jwt/JwtUtil.java` → `common/.../security/JwtUtil.java`
2. `users-common/.../cryptx/PasswordUtil.java` → `common/.../security/PasswordUtil.java`
3. `users-common/.../jwt/BearerTokenExtractor.java` → `common/.../security/BearerTokenExtractor.java`
4. `users-common/.../http/GoStyleCoercion.java` / `GoStyleStringInt64.java` → `common/.../web/` 同名类

**为什么换**：D3 —— Spring 生态里共享能力层的标准形态是 starter（`AutoConfiguration.imports`），不是「普通 jar + 每个消费者手写 `@Import`」；
而 `user/common/jwt` 被 `article/rpc` + `knowledge/api` 同时使用 ⇒ 确属**跨域共用且无域归属**（AGENTS.md §1.4 R1 的准入条件）。
⇒ **代价**：改 `common/` 会自动把并发降级为串行（16 个组件依赖它）⇒ **本域不得单方面改 `common/`**。

---

## 5. 决策锁（与本库相关）

本库涉及的决策：**D3**（`service/common` 升级为真 starter ⇒ 本库 4 个文件上提到仓库根 `common/`）· **D5**（DTO 改 immutable record + Bean Validation ⇒ 本库 `GoStyleRequired*` 4 个校验器删除）· **P1**（错误文案保真度，待拍板 ⇒ 决定 `GetErrMsg(code)` 的文案是否逐字照抄 Go）。

> ⚠️ **决策的完整内容、落点与代价见 [`_shared.md` §5 决策锁（汇总）](./_shared.md#5-决策锁汇总)** —— 本节只列本库相关的决策编号，**不复制表格行**（避免重复计数）。
> 本库的**目录形态**决策锁（换目录形态 8 处同时改）见 [`_shared.md` §1](./_shared.md#1-包结构对照域级)。

---

## 6. 跨模块全局约定（与本库相关行）

> ⚠️ 原 §6 表的 **18 行中，users-common 专属行为 0 行** —— 错误码取值 / 错误文案 / 错误文案缺口 / JWT claims 这 4 行的 A 列出处是本库的 Go 文件（`service/user/common/errmsg/errmsg.go`、`service/user/common/jwt/jwt.go`），
> 但**取值是 user 与 admin 共用的跨子域约定** ⇒ 完整保留在域级文件。
> ⇒ **本库相关行见 [`_shared.md` §6 跨模块全局约定](./_shared.md#6-跨模块全局约定值逐条核对-a-列-go-源码)**。

---

## 7. 覆盖度自查（本库）

### 7.1 C 列覆盖率 —— **2 / 2 = 100%**（users-common）

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users/users-common" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| `service/users/common/src/main/java/com/rag/users/common/` | 2 | §1.3 第 1 行（`ErrCodes.java` / `ErrMsg.java`） | ✅ 2/2 |
| **合计** | **2** | — | ✅ **2/2** |

> ⚠️ **本库在 C 侧另有 5 个文件上提到仓库根 `common/`**（`JwtUtil` / `PasswordUtil` / `BearerTokenExtractor` / `Coercion` / `StringInt64`）⇒ 归属见 [`../common.md`](../common.md)，不计入本表的 2/2。

### 7.2 A 列覆盖率 —— **3 / 3 = 100%**（users-common）

| Go 子目录 | 非测试 `.go` 数 | 本表覆盖位置 | 覆盖 |
|---|---|---|---|
| `service/user/common/` | 3 | §1.3 第 1–3 行 | ✅ 3/3 |
| **合计** | **3** | — | ✅ **3/3** |

### 7.3 映射类型分布（users-common）

> 统计口径：§1 全部映射表的**第 4 列**（不含 §2/§3/§4 的差异对照表）。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **9** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」）；其中 **7 行** A 列为「Go 侧无对应物」（B 引入的横切件） |
| `1:N` | **1** | Go 单文件拆成多个 C 文件 |
| `N:1` | **0** | — |
| `C 新增` | **0** | 本库每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本库是**重组**而非新增能力 |
| **合计** | **10** | — |

> 统计命令（可复跑）：
> ```bash
> awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' docs/Mapping_Table/users/users-common.md \
>   | awk -F'|' '/^\|/{t=$5; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 9 1:1 / 1 1:N
> ```

---

## 8. 一句话总结（users-common 公共库）

**A 列 Go**（`service/user/common/`，3 个非测试 `.go`）= 单一真源；
**B 列 RAG**（`service/users/users-common/`）= Go 部署拓扑的 Spring 复刻；
**C 列 RAG-V1**（`service/users/common/`，2 个 `.java`）= Spring 惯用目标形态（另有 5 个文件上提到仓库根 `common/`）。

⇒ **users-common C 列 2/2 覆盖、A 列 3/3 覆盖、映射类型 1:1 ×9 / 1:N ×1 / N:1 ×0 / C 新增 ×0（共 10 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 A 列 Go 源码为准，状态看 `RAG/docs/DRIFT/users/`。**
