# Mapping_Table/users/users-common.md — users-common 公共库两方文件名对照表

> **本表目的**：把 **RAG**（B 列，**唯一真理之源**）→ **RAG-V1**（C 列，Spring 惯用目标形态）两棵树的 **users 域公共库 `users-common`** 文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 该库路径 | 角色 |
> |---|---|---|---|
> | **B（RAG，唯一真理之源）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/users-common/` | **唯一行为判定基准**（D16 · 2026-11-02） |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/common/`（**短名**）+ 仓库根 `common/`（D3 上提） | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（更早一版的两方镜像对照）。
> **域级共享层**：[`_shared.md`](./_shared.md) —— §0 读表须知（完整版）· §1 目录形态决策锁 · §2~§6 跨子域对照 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结。

### 导航

| 文档 | 装什么 | B 侧出处 |
|---|---|---|
| [`_shared.md`](./_shared.md) | 域级共享层 | `service/users/`（全域） |
| **本文件** | **users-common 公共库** | `service/users/users-common/` |
| [`user/user.md`](user/user.md) | user 子域 | `service/users/user/` |
| [`admin/admin.md`](admin/admin.md) | admin 子域 | `service/users/admin/` |

---

## 〇、读表须知（精简）

### 0.1 三条不可违反的纪律（照抄 [`README.MD`](../README.MD) §七）

1. ⚠️ **B 列是唯一行为判定基准**（D16 · 2026-11-02 用户定）—— RAG 侧 `users` 域自身已被判定 `NOT EQUIVALENT`（见 `RAG/docs/DRIFT/users/`）
   ⇒ **该差异一律继承，不回溯更早的历史版本**；本表只回答「文件名 / 职责的对应关系」。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**不下**「已对齐 / 未对齐」结论；行为状态看 `RAG/docs/DRIFT/users/` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 `.java` 目前只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

> 📌 **完整版读表须知见 [`_shared.md` §〇](./_shared.md#〇读表须知)** —— 另含 §0.2 本表的边界 · §0.3 映射类型的 4 种写法 · §0.4 B/C 规模基线。

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

### 1.3 users-common 公共库（B 侧 `service/users/users-common/`，11 个 `.java`）

> ⚠️ **`users-common` 是域内公共库，不是第三个域**（`AGENTS.md` §2.0 命名约定第 2 条）。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `users-common/.../errmsg/ErrorCode.java`（**1 个类装全部**） | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:N | B 侧的 `enum ErrorCode`（**码值与文案同体**）+ `getMsg(int)` → **C 侧拆成 2 个类**（码值集 `ErrCodes` / 文案映射 `ErrMsg`）。⚠️ **admin 侧无 `contract/` 包**（`admin-contract` 模块已随 D7 删除）⇒ admin 若要引用错误契约，取**本公共库这份**（admin 的 pom 已依赖 `users-common`）⇒ 子域不对称见 §7.3 F-2 |
| `users-common/.../jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | 1:1 | D3：共享层上提到仓库根 `common/` starter（`article-api` / `comment-api` / `favorite-api` / `follow-api` / `like-api` / `message-api` 也用 ⇒ 真正跨域共用）；`common/` 不在 `service/` 下 ⇒ **归属见 `common.md`** |
| `users-common/.../cryptx/PasswordUtil.java` | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | 1:1 | 同上 |
| `users-common/.../jwt/BearerTokenExtractor.java` | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | 1:1 | B 侧的 `Bearer ` 前缀提取器；C 归位到 `common/src/main/java/com/rag/common/security/` ⇒ **归属见 `common.md`** |
| `users-common/.../http/GoStyleRequired.java` + `GoStyleRequiredFields.java` + `GoStyleRequest.java` + `GoRequiredFieldException.java` | **（D5 已删）** | 1:1 | D5 决定删除手写校验器，改用 **Bean Validation** ⇒ **L3 降级**：错误文案从旧校验器的 `field "uid" is not set` 变为 Bean Validation 文案 |
| `users-common/.../http/GoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` | 1:1 | Jackson `CoercionConfig` 原生机制，C 仅归位（类名去掉 `GoStyle` 前缀）⇒ **归属见 `common.md`** |
| `users-common/.../http/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | 1:1 | uid 序列化为字符串（避免精度丢失）⇒ **归属见 `common.md`** |
| `users-common/.../ddl/SchemaInitializer.java` | **（无对应物）** | 1:1 | ⚠️ C 侧 `common/` **无 `SchemaInitializer`**，且两个 `*-service` 的 `db/migration/` 只有 `.gitkeep` ⇒ **幂等 DDL 重放机制在 C 侧无执行器、无脚本**（见 §7.3 F-3） |
| `user-rpc/.../mapper/JsonTextTypeHandler.java` + `admin-rpc/.../mapper/JsonTextTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` | 1:1 | `extra_info`（**text** 列）↔ `Map<String,String>` 的 TypeHandler；D3 收进 starter ⇒ **归属见 `common.md`** |
| `admin-rpc/.../mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | 1:1 | PG `jsonb` 列的 TypeHandler（MP 自带 `JacksonTypeHandler` 会把值绑成 `varchar` ⇒ jsonb 拒收）⇒ **归属见 `common.md`** |

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

| 能力 | B RAG 位置 | C RAG-V1 位置 | 归属说明 |
|---|---|---|---|
| 错误码 / 文案 | `users-common/.../errmsg/ErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `ErrMsg.java` + `service/users/user/src/main/java/com/rag/user/contract/ErrCodes.java` + `ErrMsg.java` | **域内归属**（users 域私有）⇒ 本表 §1.3 负责；**不得上提到 `common/`**（各域码值/文案/语言不同） |
| JWT 签发 / 验签 | `users-common/.../jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | **全局归属**（`article-api` / `comment-api` / `favorite-api` / `follow-api` / `like-api` / `message-api` 也用）⇒ `common.md` 负责 |
| `Bearer ` 前缀提取 | `users-common/.../jwt/BearerTokenExtractor.java` | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | **全局归属** ⇒ `common.md` 负责 |
| 密码散列 | `users-common/.../cryptx/PasswordUtil.java` | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | **全局归属** ⇒ `common.md` 负责 |
| JSON 强转 / `string→int64` | `users-common/.../http/GoStyleCoercion.java` / `GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/Coercion.java` / `GoStyleStringInt64.java` | **全局归属** ⇒ `common.md` 负责 |
| 必填校验（B 侧 `GoStyleRequired*` 一族） | `users-common/.../http/GoStyleRequired*.java` 等 4 个 | **已删**（D5）⇒ 改由 Bean Validation 承担 | 降级项登记在 `L3-降级清单.md` D-01 |
| DDL 幂等重放执行器 | `users-common/.../ddl/SchemaInitializer.java` | **缺失**（C 侧 `common/` 无、`db/migration/` 空） | ⚠️ **待补**（F-3） |

**决策锁**

**换共享层归属（域内 `users-common` → 全局 `common/` starter）4 处同时改**：
1. `users-common/.../jwt/JwtUtil.java` → `common/.../security/JwtUtil.java`
2. `users-common/.../cryptx/PasswordUtil.java` → `common/.../security/PasswordUtil.java`
3. `users-common/.../jwt/BearerTokenExtractor.java` → `common/.../security/BearerTokenExtractor.java`
4. `users-common/.../http/GoStyleCoercion.java` / `GoStyleStringInt64.java` → `common/.../web/` 同名类

**为什么换**：D3 —— Spring 生态里共享能力层的标准形态是 starter（`AutoConfiguration.imports`），不是「普通 jar + 每个消费者手写 `@Import`」；
而 B 侧的 `users-common/.../jwt` 被 `article-api` / `comment-api` / `favorite-api` / `follow-api` / `like-api` / `message-api` 同时使用 ⇒ 确属**跨域共用且无域归属**（AGENTS.md §1.4 R1 的准入条件）。
⇒ **代价**：改 `common/` 会自动把并发降级为串行（16 个组件依赖它）⇒ **本域不得单方面改 `common/`**。

---

## 5. 决策锁（与本库相关）

本库涉及的决策：**D3**（`service/common` 升级为真 starter ⇒ 本库 4 个文件上提到仓库根 `common/`）· **D5**（DTO 改 immutable record + Bean Validation ⇒ 本库 `GoStyleRequired*` 4 个校验器删除）· **P1**（错误文案保真度，待拍板 ⇒ 决定 C 侧 `ErrMsg` 的文案是否逐字照抄 B 侧 `ErrorCode.getMsg(int)`）。

> ⚠️ **决策的完整内容、落点与代价见 [`_shared.md` §5 决策锁（汇总）](./_shared.md#5-决策锁汇总)** —— 本节只列本库相关的决策编号，**不复制表格行**（避免重复计数）。
> 本库的**目录形态**决策锁（换目录形态 8 处同时改）见 [`_shared.md` §1](./_shared.md#1-包结构对照域级)。

---

## 6. 跨模块全局约定（与本库相关行）

> ⚠️ 原 §6 表的 **18 行中，users-common 专属行为 0 行** —— 错误码取值 / 错误文案 / 错误文案缺口 / JWT claims 这 4 行的 B 侧出处是本库的文件（`users-common/.../errmsg/ErrorCode.java`、`users-common/.../jwt/JwtUtil.java`），
> 但**取值是 user 与 admin 共用的跨子域约定** ⇒ 完整保留在域级文件。
> ⇒ **本库相关行见 [`_shared.md` §6 跨模块全局约定](./_shared.md#6-跨模块全局约定值逐条核对-b-侧源码)**。

---

## 7. 覆盖度自查（本库）

### 7.1 C 列覆盖率 —— **3 / 3 = 100%**（users-common）

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users/common" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| `service/users/common/src/main/java/com/rag/users/common/` | 3 | §1.3 第 1 行（`ErrCodes.java` / `ErrMsg.java`）+ `IdentityResolver.java`（**跨子域共享件**，映射行落在 [`user/user.md` §1.1.3](./user/user.md)） | ✅ 3/3 |
| **合计** | **3** | — | ✅ **3/3** |

> ⚠️ **本库在 C 侧另有 5 个文件上提到仓库根 `common/`**（`JwtUtil` / `PasswordUtil` / `BearerTokenExtractor` / `Coercion` / `StringInt64`）⇒ 归属见 [`../common.md`](../common.md)，不计入本表的 3/3。

**B 列（RAG 侧 `service/users/users-common/`）覆盖率 —— 11 / 11 = 100%**（抄写路线图 §1.0c 的 B↔C 依据）

> B 侧 `service/users/users-common/` 实测 **11** 个 `.java`（`cryptx` 1 · `ddl` 1 · `errmsg` 1 · `http` 6 · `jwt` 2）⇒ 本表 §1.3 的 B 列 **11/11 全覆盖**，零遗漏。
> 复算命令（`RAG-V1` 仓根）：
> ```bash
> python3 tools/check-mapping-coverage.py users   # ⇒ users 域 B 列 145/145
> ```

### 7.2 映射类型分布（users-common）

> 统计口径：§1 全部映射表的**类型列**（`B | C | 映射类型 | 备注` = 4 列 ⇒ 类型是第 3 格）；不含 §2/§3/§4 的差异对照表。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **9** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」）；其中 **7 行**是 B 侧引入的横切件（C 侧原样保留或归位到共享层） |
| `1:N` | **1** | B 的 1 个文件拆成多个 C 文件（`ErrorCode.java` → `ErrCodes` + `ErrMsg`） |
| `N:1` | **0** | — |
| `C 新增` | **0** | 本库每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本库是**重组**而非新增能力 |
| **合计** | **10** | — |

> 统计命令（可复跑）：
> ```bash
> awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' docs/Mapping_Table/users/users-common.md \
>   | awk -F'|' '/^\|/{t=$4; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 9 1:1 / 1 1:N
> ```
> ⚠️ **旧自报数（`1:1` 9 / `1:N` 1 / `N:1` 0 / `C 新增` 0 / 合计 10）与本表一致** —— 本次仅把统计口径从「第 4 列」改为「类型列（第 3 格）」，行数未变（删列不改行数）。

---

## 8. 一句话总结（users-common 公共库）

**B 列 RAG**（`service/users/users-common/`，11 个 `.java`）= **唯一真理之源**（D16 · 2026-11-02）；
**C 列 RAG-V1**（`service/users/common/`，3 个 `.java`）= Spring 惯用目标形态（另有 5 个文件上提到仓库根 `common/`）。

⇒ **users-common C 列 3/3 覆盖、B 列 11/11 覆盖、映射类型 1:1 ×9 / 1:N ×1 / N:1 ×0 / C 新增 ×0（共 10 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 B 列（RAG 侧）源码为准，状态看 `RAG/docs/DRIFT/users/`。**
