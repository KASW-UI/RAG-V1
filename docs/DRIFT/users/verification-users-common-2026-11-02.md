# `users-common` 迁移审核报告 —— RAG → RAG-V1

> **由 `align/users-common` 工作树产出** · **基线 commit `b973d61`**（本地 `main`）· 实现 commit `3fb594b`
> **审核协议**：`.agents/verification.md`（八阶段：范围 → 承接清单 → 行为审核 → 框架差异 → 合并拆分 → 对拍 → 状态 → 报告）。
> **审核日期**：2026-11-02 · **审核对象**：`service/users/common/`（artifactId `users-common`，包 `com.rag.users.common`）+ 其在根 `common/` 的 5 个上提落点。
> **行为基线**：**B 侧（RAG）** —— 本仓 D13 的「L2/L4 以 RAG 为基准」口径在本次审核中<b>只以 B 为对照</b>（用户 2026-11-02 指令：「不用管 golang，只看 b 侧」）。
> **状态口径**（§十）：`PASS` / `FAIL` / `NOT_IMPLEMENTED` / `NOT_VERIFIED` / `FRAMEWORK_DIFFERENCE` / `BLOCKED` / `INTENTIONALLY_CHANGED`。
> ⚠️ **四种非 PASS 状态一律不计入 PASS**（§十）。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` 按 `docs/Worktree.md` §三·1 **不碰**（合并后由主会话统一更新）。

---

## 11.1 审核范围

### 两侧坐标

| 项 | B（迁移源 / 行为基线） | C（迁移目标 / 被审对象） |
|---|---|---|
| 仓库 | `/Users/pr/Documents/java&golang/RAG` | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-common` |
| 模块路径 | `service/users/users-common/` | `service/users/common/`（artifactId **`users-common`**） |
| 包 | `com.rag.users.common.{cryptx,ddl,errmsg,http,jwt}` | `com.rag.users.common`（**单层压平**，D7） |
| 源文件数 | **11 个类 + 4 份 SQL** | **5 个类 + 4 份 SQL**（本仓另在根 `common/` 承接 5 个类 · 删除 4 个类） |
| 域外同源副本 | `users/user/user-api/.../IdentityResolver.java` ＋ `users/admin/admin-api/.../IdentityResolver.java`（**B 的两份重复**） | `service/users/common/.../IdentityResolver.java`（**C 侧 1 份**） |
| git commit | 冻结只读（未动） | 基线 `b973d61` · 实现 `3fb594b` |

### 实际解析版本（**读 classpath 取值，不是 pom 声明值**）

| 组件 | B | C | 差异影响 |
|---|---|---|---|
| Spring Boot | **4.0.8** | **3.5.11** | 框架差异逐条见 §11.4 |
| Spring Framework / spring-jdbc | 7.0.x | **6.2.16** | `ScriptUtils` 包名与语义未变（§11.4-③） |
| Servlet API | 6.1.x（Jakarta） | **jakarta.servlet-api 6.0.0** | `HttpServletRequest` 契约同构 |
| Jackson | **3.1.5**（`tools.jackson`） | **2.19.4**（`com.fasterxml`） | D14 已定口径；`Coercion` 的 6 项能力逐项 API 同构 |
| jjwt | 0.12.6 | **0.12.6** | 无差异 |
| Tomcat | 11.x | 10.1.x | 本模块无 servlet 容器 ⇒ 不适用 |
| JUnit / Testcontainers | — | 5.12.2 / 1.21.4 · PG 驱动 42.7.10 | 测试基础设施 |
| Java | 21 | 21 | 无差异 |

### 本次使用的资料

| 资料 | 用途 | 核对日期 |
|---|---|---|
| `docs/Mapping_Table/users/users-common.md` | B→C 落点对照（**抄写路线图**） | 2026-11-02 |
| `docs/Mapping_Table/users/user/user.md` · `users/_shared.md` | IdentityResolver 落点（F-1）· 错误契约拆分口径 | 2026-11-02 |
| `docs/File_Tree/users/common.md` | C 侧目标结构约束 | 2026-11-02（本次已重生成） |
| `docs/决策记录.md`（D1–D14）· `docs/L3-降级清单.md`（D-01） | 已批准决策与降级登记 | 2026-11-02 |
| `RAG/docs/DRIFT/users/`（**只读**） | B 侧既有问题（避免把 B 的老问题误报成 C 的迁移问题） | 2026-11-02 |

⚠️ **映射表与当前代码的差异**（按 §2.1「记录差异，不要擅自修改映射表」）⇒ 见 §11.5 **D-06**。

---

## 11.2 B → C 责任承接表

> 口径：**覆盖 B 侧 users-common 模块的全部源职责**（10 类 + 4 SQL）＋ B 的两份 `IdentityResolver` 副本 ＋ 5 个上提落点 ＋ 4 个被删除类。
> **实现状态** = 实现是否存在；**验证状态** = 有无运行证据（§十：NOT_VERIFIED 不计入 PASS）。

| ID | B 源文件/方法 | B 原始职责 | C 承接文件/方法 | 映射类型 | 实现状态 | 验证状态 | 证据 |
|---|---|---|---|---|---|---|---|
| U-01 | `errmsg/ErrorCode.java:8-38`（31 个枚举常量） | 码值 + 文案同体 | `ErrCodes.java`（码值集）＋ `ErrMsg.java`（文案映射） | 1:N | 已实现 | ✅ 已验证 | `ErrContractParityTest#everyCodeAndMessageMatchesB`（31 条逐条相等） |
| U-02 | `ErrorCode.java:58-60` `getCode()` | 取本枚举的码值 | `ErrCodes.*` 常量本身 | 表达层改写 | 已实现 | ✅ 已验证 | `ErrContractParityTest#constantsMatchBCodes`（31 条常量逐条） |
| U-03 | `ErrorCode.java:69-71` `getMsg()`（instance） | 取本枚举的文案 | `ErrMsg.getMsg(code)` | 1:N | 已实现 | ✅ 已验证 | `ErrContractParityTest#everyCodeAndMessageMatchesB` |
| U-04 | `ErrorCode.java:88-90` `of(int)` | 码 → 枚举；**未知码回落 `ERROR`** | `ErrMsg.getMsg` 的 `getOrDefault(code, ERROR)` | 1:1 语义 | 已实现 | ✅ 已验证 | `ErrContractParityTest#unknownCodeFallsBackToFail`（未知码 ⇒ `FAIL`，非 null/非异常） |
| U-05 | `ErrorCode.java:93-95` `getMsg(int)` | 码 → 文案 | `ErrMsg.getMsg(int)` | 1:1 | 已实现 | ✅ 已验证 | 同 U-01/U-04 |
| U-06 | `jwt/JwtUtil.java:13-30` `getToken` | HS256 签发（钉死 JCA 算法名 + 签名算法） | `common/security/JwtUtil.java` | 1:1（上收 D3） | 已实现（前序批次） | ✅ 已验证（前序） | 归一化 diff：仅 `SignatureAlgorithm.HS256` → `Jwts.SIG.HS256`（jjwt 0.12.6 API 适配，签名结果逐字节同） |
| U-07 | `jwt/JwtUtil.java:84-93` `padToHmacKeySize` | 密钥补齐到 ≥32 字节（补零） | `common/security/JwtUtil.java` 同款 | 1:1（上收） | 已实现（前序） | ✅ 已验证（前序） | 归一化 diff **完全一致** |
| U-08 | `jwt/BearerTokenExtractor.java:74-81` `tokenForSignatureVerification` | 验签取 token（`len>6` ＋ 大小写不敏感 ＋ 不匹配原样返回） | `common/security/BearerTokenExtractor.java` | 1:1（上收） | 已实现（前序） | ✅ 已验证（前序）· ⚠️ 已登记偏离 | 归一化 diff：判定条件 `length() > 7` → **`> 6`**（按上游的 `len(tok) > 6`）⇒ 见 §11.5 **D-08** |
| U-09 | `jwt/BearerTokenExtractor.java:95-99` `trimBearerPrefix` | 黑名单 key 取值（大小写**敏感**的 `TrimPrefix`） | 同上 | 1:1（上收） | 已实现（前序） | ✅ 已验证（前序） | 归一化 diff **完全一致** |
| U-10 | `cryptx/PasswordUtil.java:73-75` `encrypt` | bcrypt 散列（cost 10） | `common/security/PasswordUtil.java` | 1:1（上收） | 已实现（前序） | ✅ 已验证（前序） | 归一化 diff **完全一致** |
| U-11 | `PasswordUtil.java:83-85` `check` | 比对；**任何 bcrypt 侧异常等价于不匹配** | 同上 | 1:1（上收） | 已实现（前序） | ✅ 已验证（前序） | 同上 |
| U-12 | `PasswordUtil.java:46-71,99-105` `Outcome` ＋ `checkDetailed` | 三态可观测性（匹配/不匹配/hash 损坏） | 同上 | 1:1（上收） | 已实现（前序） | ✅ 已验证（前序） | 同上 |
| U-13 | `http/GoStyleCoercion.java` | go-zero 风格强转规则（数字/布尔→String 拒、浮点→Long 拒） | `common/web/Coercion.java` | 1:1（上收）+ Jackson 3→2 | 已实现（前序） | ✅ 已验证（前序） | 归一化 diff：6 项能力 API 同构；C 侧另加 `applyTo(ObjectMapper)` 重载（Spring 6.2 的 builder 无 coercion API ⇒ postConfigurer 路线） |
| U-14 | `http/GoStyleStringInt64.java` | JSON 标量 ⇒ 合法的 `,string` int64 | `common/web/StringInt64.java`（改名） | 1:1（上收）+ 改名 | 已实现（前序） | ✅ 已验证（前序） | 归一化 diff：**仅 javadoc 精简**（删掉了 B 侧引用已删类的 `@link`） |
| U-15 | `http/GoStyleRequired.java` ＋ `GoStyleRequiredFields.java` ＋ `GoStyleRequest.java` ＋ `GoRequiredFieldException.java` | 请求体必填校验（go-zero `httpx.Parse` 形状） | **C 侧无** ⇒ 改由 Bean Validation 承担 | 删除（**已批准降级**） | 已决策 | ✅ 已登记 | `docs/L3-降级清单.md` **D-01** ＋ `docs/决策记录.md` **D5**（D5 决定删除手写校验器）⇒ §11.5 **D-09** |
| U-16 | `ddl/SchemaInitializer.java:109-136` `applyAll` | DDL **幂等重放**（无版本账本）＋ PG advisory lock ＋ 失败即抛 | `service/users/common/SchemaInitializer.java` | 1:1 | 已实现 | ✅ 已验证（**真 PG**） | `UsersSchemaAndStatusLookupTest#applyAllIsIdempotent`（真 PG 连跑 **3 次**不抛） |
| U-17 | `SchemaInitializer.java:63-68` `SCRIPTS` | 4 份脚本的清单与执行顺序 | 同上 | 1:1 | 已实现 | ✅ 已验证 | `UsersSchemaAndStatusLookupTest#allFourTablesExist`（4 表全部建出 ＋ 脚本数 = 4） |
| U-18 | `SchemaInitializer.java:91` `ADVISORY_LOCK_KEY` | 并发建表的保护键（两服务必须同键） | 同上 | 1:1 | 已实现 | ✅ 已验证 | `UsersSchemaAndStatusLookupTest#advisoryLockKeyIsFrozen`（`8_675_309`） |
| U-19 | `resources/db/0{1,2,3,4}_*.sql` | 4 份 users 域 DDL（表结构 / 索引 / 幂等自愈 ALTER） | `service/users/common/src/main/resources/db/`（**同路径**） | 1:1 | 已实现 | ✅ 已验证（**逐字节**） | `cmp` 4/4 **逐字节一致** ＋ 真 PG 上列类型/索引断言（`extra_info=text`、`create_time=timestamptz`、`idx_users_user_uid` 等） |
| U-20 | user-api `IdentityResolver.java:101-114` `claimedUID` | 取**正整数** UID（3 条判定：null / 非 Long / `uid<=0`） | `IdentityResolver.claimedUID` | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest#claimedUidRejectsNullRequest/MissingAttribute/WrongType/NonPositive` |
| U-21 | `:160-171` `claimedUIDAllowNonPositive` | 取 UID，**不拒** `uid<=0` | `IdentityResolver.claimedUIDAllowNonPositive` | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest#claimedUidAllowNonPositiveKeepsNonPositive`（0 / −7 原样返回） |
| U-22 | `:176-217` `resolveUser`（6 步判定链） | 查用户 → 存在性 → UID 一致 → **status presence** → status 活跃 | `IdentityResolver.resolveSubjectRef` ＋ `UserStatusLookup` 端口 | 1:1 语义（**通道端口化**） | 已实现 | ✅ 已验证 | `IdentityResolverTest`（5 sentinel 各自用例 ＋ `checkOrderIsStable` 钉住判定顺序）＋ 真 PG 端到端 `endToEndAgainstRealPostgres` |
| U-23 | `:212-223` `resolveSubjectRef` | 产出三元组 `{authorityId, tenantId, subjectId}` | 同上 | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest#successfulResolutionProducesSubjectRef` |
| U-24 | `:57-89` 嵌套 `SubjectRef` | 3 字段 wire 类型（含 equals/hashCode） | `IdentityResolver.SubjectRef` | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest#subjectRefEquality` ＋ 字段断言；⚠️ **刻意不加 `@JsonProperty`**（见 §11.5 D-05） |
| U-25 | `:40-41` `AuthorityID` / `PlatformTenantID` | L1 冻结常量 | `AUTHORITY_ID` / `PLATFORM_TENANT_ID` | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest#frozenConstants` |
| U-26 | `:45-49` 5 个冻结错误消息 | sentinel 文案 | 同款私有常量 | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest`（消息断言） |
| U-27 | `:228-243` 5 个 sentinel 异常类 | 异常类型（上游 sentinel error 形状） | 同款嵌套异常类 | 1:1 | 已实现 | ✅ 已验证 | `IdentityResolverTest`（各异常类型断言） |
| U-28 | **admin-api `IdentityResolver.java`**（B 的第二份副本） | admin 版：不做 status 检查 ＋ `ErrAdminNotFound` ＋ 查 `getSelf` | C 侧**无第二份** ⇒ 「查哪张表 / 有无 status 列」下沉到 `UserStatusLookup` 端口 | N:1（**重复被消解**） | 已实现 | ⚠️ **已登记偏离** | 映射表 **F-1**（用户 2026-11-01 定：上收到域级共享层，只 1 份）⇒ 差异见 §11.5 **D-02** |
| U-29 | B 的 `user-rpc` / `admin-rpc` `bootstrap/*SchemaBootstrap`（`UserSchemaBootstrap.java:51` / `AdminSchemaBootstrap.java:55`） | 启动时调 `SchemaInitializer.applyAll` | **执行器已在本 lane 交付**；**调用点在 `users/user` · `users/admin` 两个组件**（本 lane 无写权） | 跨 lane 承接 | 执行器 ✅ / 调用点 ⬜（他 lane） | ⚠️ **未验证（他 lane 未审）** | 见 §11.5 **D-10**（登记为跨 lane 依赖，不计本组件缺陷） |
| U-30 | B 的 `resolveUser`（`user-api/.../IdentityResolver.java:176-217`）**对外返回面**（B 返回 proto `UserInfo`；`UserGetService.java:54` 在用） | 「解析身份」与「拿到用户数据」两件事，B 侧绑在一个方法里 | `IdentityResolver.resolveUser(request, lookup)`（返回已校验的 `UserStatusRecord`）＋ `verifyUser(uid, record)`（只做「取回之后」的判定） | 1:1（**返回形态改写**） | 已实现 | ✅ 已验证 | `IdentityResolverTest#resolveUserReturnsVerifiedRecord` / `#resolveUserSharesTheSameDecisionChain` / `#verifyUserChecksFetchedRecord`（3 例）—— ⚠️ **这一行是自查补上的**：初版只暴露了三元组 ⇒ 消费点拿不到用户 ⇒ 属**覆盖缺口**，已补 |
| U-31 | B 的 `http/GoStyleStringInt64.java`（上提到 `common/web/StringInt64.java`） | `,string` int64 的 8 分支判定 | `common/web/StringInt64.parse`（**上提落点，根 `common/`**） | 1:1（上提） | 已实现（前序） | ✅ **已验证（本轮闭合）** | `StringInt64ParityTest`（**8 例** · 接受 4 类 / 拒绝 4 类 / 异常消息形状）⇒ 闭合了独立审核判定的 **NOT_VERIFIED**（原状：全仓 0 个测试调用 ⇒ 按协议 §九 不得用静态相似度替代运行验证） |

### C 侧新增件（**B 侧没有 ⇒ 每件都要有职责依据**）

| ID | C 侧新增 | 职责依据 | 是否改变 B 的可观察行为 | 验证 |
|---|---|---|---|---|
| N-01 | `JdbcUserStatusLookup` | B 的 `IdentityResolver` 经 gRPC 查 user；C 按 D1+D7 **合并 api+rpc 为单进程** ⇒ 「自己调自己」无意义 ⇒ 改为直接查库。pom 注释亦已写明「`users-common` 要经 JDBC 读用户表做 status presence 检查」 | ❌ 不改变：判定链与 sentinel 逐条保留（U-22） | ✅ 真 PG：presence 三态 + 端到端 |
| N-02 | `UserStatusLookup` 端口 + `UserStatusRecord` | 把「查谁 / 有没有 status 列」从解析器里解耦（B 的 admin 副本差异正是这两点） | ❌ 不改变：仅换实现通道 | ✅ `IdentityResolverTest`（含「UID 错配 + status 非 0 ⇒ 先抛错配」） |
| N-03 | `ErrCodes.codes()` / `ErrMsg.all()` | B 的 `CODE_MAP` 由 `values()` 构建；C 无 enum ⇒ 需一份可枚举视图 | ❌ 不改变（只增不改） | ✅ `ErrContractParityTest#messageTableKeysEqualCodeSet` |
| N-04 | `SchemaInitializer.scripts()` / `advisoryLockKey()` | 自检 / 契约对拍用（B 无对应公开方法） | ❌ 不改变（只增不改） | ✅ `UsersSchemaAndStatusLookupTest` |
| N-05 | `IdentityResolver.CLAIM_ATTRIBUTE` | 「属性名」是拦截器 ↔ 解析器之间的契约；B 侧硬编码在两处 ⇒ 提成常量一处 | ❌ 不改变（值仍为 `"userId"`） | ✅ `IdentityResolverTest#frozenConstants` |

---

## 11.3 多文件合并 / 拆分专项报告（§八）

### 8.1 拆分（B 1 个类 → C 2 个类）

| 项 | 内容 |
|---|---|
| B 源 | `com.rag.users.common.errmsg.ErrorCode`（**1 个 enum**，码与文案同体） |
| C 承接 | `com.rag.users.common.ErrCodes`（码值集）＋ `com.rag.users.common.ErrMsg`（文案映射 ＋ `getMsg`） |
| 依据 | `docs/深挖修正记录.md` §三「常量 + `GetErrMsg` ⇒ `ErrCodes` + `ErrMsg`」；`docs/File_Tree/users/user.md:60-61`（同一形状，用于 user 子域） |
| 覆盖检查 | B 的 **4 个可执行面**（`getCode` / `getMsg` instance / `of(int)` / `getMsg(int)`）**逐条落位**（U-02~U-05）⇒ **无遗漏** |
| 分裂风险检查 | **文案只存在 `ErrMsg` 一处**、**码值只存在 `ErrCodes` 一处** ⇒ 不存在「同一条码在两个类各有一份文案」的隐性分叉；`ErrContractParityTest#messageTableKeysEqualCodeSet` 断言两集合相等 |
| 共享状态 | 无（C 侧两类均无状态；`ErrMsg` 的 map 在 static 块一次构建后 `unmodifiableMap`） |

### 8.2 合并（B 2 份副本 → C 1 份）

| 项 | 内容 |
|---|---|
| B 源 | `users/user/user-api/.../IdentityResolver.java` ＋ `users/admin/admin-api/.../IdentityResolver.java`（**两份重复**） |
| C 承接 | `service/users/common/.../IdentityResolver.java`（**1 份**） |
| 依据 | `docs/Mapping_Table/users/user/user.md:142` ＋ `users/_shared.md` §7.4 **F-1**（用户 2026-11-01 定） |
| **两份的 3 处差异如何承接** | ① admin 版不做 status 检查 ② `ErrAdminNotFoundException` vs `ErrUserNotFoundException` ③ 查 `getSelf` vs `getUser` ⇒ **三者全部下沉到 `UserStatusLookup` 端口**：admin 表无 status 列 ⇒ admin 侧适配器返回「present 且 0」⇒ 与 B 的 admin 版**可观察行为一致** |
| 覆盖检查 | B 的 user-api 版**全部**方法（`claimedUID` / `claimedUIDAllowNonPositive` / `resolveUser` / `resolveSubjectRef` / `SubjectRef` / 5 常量 / 5 消息 / 5 异常）**逐条落位**（U-20~U-27） |
| 职责断裂检查 | 调用链完整（claim → 查 → presence → 活跃 → 三元组，**6 步顺序不变**）；数据完整传递（uid 逐段透传）；异常正确传播（5 sentinel 原样抛）；**无事务边界**（纯读 + 只读查询）；无重复执行 / 遗漏执行（每步一次）；无初始化时机变化（纯静态工具） |
| ⚠️ **登记的偏离** | 判定链里的「查 user」通道由 **gRPC → JDBC** ⇒ 见 §11.5 **D-01**；admin 副本被消解 ⇒ **D-02** |

### 8.3 共享能力上收（B 的 5 个类 → 根 `common/`）

| B 类 | C 落点 | 结论 |
|---|---|---|
| `jwt/JwtUtil` | `common/security/JwtUtil` | 归一化 diff：仅 jjwt 0.12 API 适配 ⇒ 行为同 |
| `jwt/BearerTokenExtractor` | `common/security/BearerTokenExtractor` | 归一化 diff：**1 处判定条件**（B `>7` → C `>6`，按上游）⇒ 已登记偏离 **D-08**（前序批次已登记，非本轮引入） |
| `cryptx/PasswordUtil` | `common/security/PasswordUtil` | 归一化 diff **完全一致** |
| `http/GoStyleCoercion` | `common/web/Coercion` | Jackson 3→2 的 API 同构适配 ＋ 新增 postConfigurer 路线 |
| `http/GoStyleStringInt64` | `common/web/StringInt64` | 仅 javadoc 精简 |

⚠️ **上收是否遗漏原消费方**（§8.4）：C 侧根 `common/` 的 5 个类**不依赖 `users-common`** ⇒ users 域两个 app 靠**根 `common/`** 拿到它们 ⇒ **不存在「users-common 被删 ⇒ 消费方断链」**。
⚠️ **本模块是否仍需要 grpc-stub / protobuf-java**：**当前代码未使用**（B 的 gRPC 通道已换成端口）⇒ **遗留依赖**，登记 **D-11**（不在本轮清理，避免动既有 pom 契约）。

### 8.4 独立进程合并（B 的 api + rpc 两进程 → C 的单 app）

本组件内**无独立进程 / 无定时任务 / 无 MQ 监听** ⇒ 不适用。
⚠️ 但 B 的 `user-rpc` / `admin-rpc` 各带一个 `bootstrap` 类（U-29）⇒ C 侧对应 `wiring/UserSchemaBootstrap` / `wiring/AdminSchemaBootstrap`（**属另两个 lane**）⇒ 登记 **D-10**。

---

## 11.4 框架版本差异报告（§七 · 逐条）

| # | B 机制 / 版本 | C 机制 / 版本 | 具体变化 | 影响的执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 结论 |
|---|---|---|---|---|---|---|---|---|
| ① | Boot **4.0.8** | Boot **3.5.11** | 自动配置拆分差异（Boot 4 的 `spring-boot-kafka` 之类独立模块在 3.5 不存在） | 本组件**无自动配置类**、无 Kafka/Redis 客户端 ⇒ 不触及 | ❌ 否 | 不需要 | 构建 + 测试全绿 | 无差异 |
| ② | Jackson **3.1.5**（`tools.jackson`） | Jackson **2.19.4**（`com.fasterxml`） | `ObjectMapper`/`CoercionConfig` 包名与类型名（`MutableCoercionConfig`） | `Coercion` 的 6 项能力施加路径 | ❌ 否（能力逐项 API 同构） | D14 口径：全仓 Jackson 2 ＋ 零排除 | `common/web/Coercion.java` 的 javadoc 逐项对照表（§11.3 8.3） | 无差异 |
| ③ | spring-jdbc **7.0.x** `ScriptUtils` | spring-jdbc **6.2.16** `ScriptUtils` | 包名 `org.springframework.jdbc.datasource.init.ScriptUtils` **未变**；两版**均不支持 PG 美元引号 `$$`** | `SchemaInitializer` 的脚本切句与执行 | ❌ 否（脚本内容已无 `$$`） | 脚本逐字节照抄 ⇒ 天然兼容 | 真 PG 连跑 3 次 + 4 表建成（`UsersSchemaAndStatusLookupTest`） | 无差异 |
| ④ | jjwt **0.12.6** `SignatureAlgorithm.HS256` | jjwt **0.12.6** `Jwts.SIG.HS256` | 枚举 → `SecureDigestAlgorithm` 常量 | JWT 签发 | ❌ 否（HS256 未变） | 根 `common/security/JwtUtil` 前序已适配 | 归一化 diff（前序批次） | 无差异 |
| ⑤ | Servlet API 6.1（Jakarta） | **6.0.0** | `HttpServletRequest` 接口 | `IdentityResolver` 取 claim attribute | ❌ 否 | `provided` 作用域引入 | `IdentityResolverTest`（真 `MockHttpServletRequest`） | 无差异 |
| ⑥ | Tomcat 11 | Tomcat 10.1 | — | 本组件无容器 | — 不适用 | — | — | 不适用 |
| ⑦ | Flyway 10.22.0 | Flyway 11.14.1 | — | **users 域两侧都不用 Flyway**（B 已弃用，C 沿用） | ❌ 否 | 无账本幂等重放 | 真 PG 幂等断言 | 无差异 |

⚠️ **本组件不存在「Bean 存在 ≠ 机制生效」的静默面**：5 个类**全是静态工具 / POJO**，无 `@Component`、无自动配置、无 Bean 装配 ⇒ §3.3.3 的「必须真触发」要求由**直接调用**满足（每条判定都有用例直接打到）。

---

## 11.5 语义漂移清单（§十一·11.5）

> 状态口径与 §十 一致；**每条都带证据强度**（源码级对照 / 真 PG 运行证据）。

| Drift ID | 问题位置 | B 文件与方法 | C 文件与方法 | B 行为 | C 行为 | 差异原因 | 影响范围 | 严重程度 | 处置 | 验证方法 |
|---|---|---|---|---|---|---|---|---|---|---|
| **D-01** | 身份解析的「查 user」通道 | `user-api/.../IdentityResolver.java:176-217`（gRPC `getUser`） | `service/users/common/.../IdentityResolver.java`（`UserStatusLookup` 端口 ＋ `JdbcUserStatusLookup`） | api 进程经 gRPC 找 rpc 拿 status | 同进程直接查库 | **D1+D7 合并 api+rpc 后的必然结果**（进程合并 ⇒ 自调用无意义） | 用户/管理员鉴权链 | 🟡 **表达层（L4）** | **不修**（改回 gRPC 等于在单进程里绕一圈）⇒ 登记 | 判定链 6 步 + sentinel 类型 + 顺序**逐条断言**（`IdentityResolverTest`）＋ 真 PG 端到端 |
| **D-02** | B 的第二份 `IdentityResolver` 副本 | `admin-api/.../IdentityResolver.java`（无 status 检查 / `ErrAdminNotFound` / `getSelf`） | C 侧**无第二份** | admin 侧有独立的解析实现 | 单一实现 ＋ 端口适配 | 映射表 **F-1**（用户 2026-11-01 定：上收到域级共享层） | admin 子域鉴权链 | 🟡 **表达层 + 刻意消解重复** | **不修**；admin 侧适配器须返回「present 且 0」⇒ 与 B 的 admin 版行为一致 | `IdentityResolverTest`（含 admin 侧适配契约的注释约定） |
| **D-03** | sentinel 异常消息前缀 | `IdentityResolver.java:180`（`"user RPC is nil: "`） | `IdentityResolver.java`（`"user lookup is nil: "`） | 端口缺失时的异常消息提到 **RPC** | 提到 **lookup** | C 侧已无 RPC ⇒ 沿用旧文案会与代码矛盾 | **仅日志**（异常消息不进客户端信封：客户端看到的是 `ErrMsg.getMsg(code)`） | ⚪ **表达层（log-only）** | **不修**（登记） | `IdentityResolverTest#nullLookupIsIdentityMismatch`（断言含 B 侧冻结消息段） |
| **D-04** | `SubjectRef` 的 JSON 形态 | `IdentityResolver.java:57-89`（**无** Jackson 注解） | 同（**刻意不加** `@JsonProperty`） | 序列化形态 = getter 推导（`authorityId/tenantId/subjectId`） | 同 | 加注解会把形态换成 snake_case ⇒ **未被 B 验证过的 wire 变更（L1）** | 任何序列化该类型的新增调用点 | 🟠 **潜在 L1 风险（当前无调用点）** | **不加**（登记） | `IdentityResolverTest#subjectRefEquality` ＋ javadoc 警告 |
| **D-05** | 错误文案 `1017` / `1018` | `ErrorCode.java:34-35`（**B 有** `"请求参数错误"` / `"头像历史不存在"`） | `ErrMsg.java`（同款文案） | 码 1017/1018 有中文文案 | 同 | **B 侧与上游真源的差异**（上游的 `codeMsg` map 里没有这两条 ⇒ 上游侧回落 `"FAIL"`）⇒ **C 随 B** | 客户端可见文案（仅当这两个码被返回） | 🟡 **B 侧原生差异**（**不是 C 的迁移缺陷**） | **不修**（基线是 B）⇒ 登记，避免后人「按上游顺手改回」制造漂移 | `ErrContractParityTest#everyCodeAndMessageMatchesB`（冻结期望值） |
| **D-06** | 映射表与真实代码不一致 | — | `docs/Mapping_Table/users/users-common.md` §0.5 / §1.3 / §4（`ddl` 行写「**C 侧无落点**」「幂等 DDL 重放执行器仍缺」） | — | C 侧**已有** `SchemaInitializer` ＋ 4 份脚本 | 本轮实现落地 ⇒ 映射表的 C 列过期 | 文档准确性 | 🟡 文档 | **按 §2.1「记录差异，不擅自修改映射表」** ⇒ 在此登记，**留给主会话统一更新**（`docs/Mapping_Table/_INDEX.md` 归主会话） | 本台账 §11.2 U-16~U-19 即为实测落点 |
| **D-07** | 文件树的「状态」段 | — | `docs/File_Tree/users/common.md` §五「文件内容 ⬜ **全部待填**」 | — | 本模块**已填**（5 类 ＋ 4 脚本 ＋ 3 测试类） | 该状态行在 `tools/gen-file-tree.py:225-228` **硬编码**，对全部 14 份文档一律写「全部待填」 | 文档准确性 | 🟡 文档（**生成器级**，影响全仓 14 份） | **不改生成器**（跨域共享工具，且改动会波及其他 9 个域的文档）⇒ 登记 | `python3 tools/gen-file-tree.py`（本工作树限定）已重跑，`users/common.md` 的目录树与文件系统一致 |
| **D-08** | `BearerTokenExtractor` 判定条件 | `jwt/BearerTokenExtractor.java:74-77`（`length() > 7`） | `common/security/BearerTokenExtractor.java`（`length() > BEARER_PREFIX.length() - 1` ⇒ `> 6`） | 「Bearer 」(7 字符)**不剥** | 「Bearer 」**剥成空串** | C 侧按 **上游**的 `len(tok) > 6`（⚠️ **D16 后 L1 以 B 侧为准** ⇒ 该差异成为 C ≠ B 的实差异 ⇒ 已登记 **DEV-GLOBAL-SEC-001**）⇒ **C ≠ B** | 畸形 `Authorization` 头的验签输入 | 🟡 **已登记偏离**（前序批次，非本轮引入） | **不修**（前序已定）⇒ 登记 | 两侧都让该畸形头**验签失败** ⇒ 无安全洞；差别只在传给验签器的串 |
| **D-09** | 必填校验器被删除 | `http/GoStyleRequired*.java`（4 类） | **C 侧无** | 报 `field "uid" is not set` 形状的文案 | Bean Validation 文案 | **D5 已批准**（改用 Bean Validation）⇒ **L3 降级** | 请求体缺字段时的错误文案 | 🟡 **已批准降级**（`L3-降级清单.md` D-01；**P1 待拍板**只影响文案保真度口径，不影响本组件） | **不修** | 登记 |
| **D-09b** | ⚠️ **D-01 的「影响面」登记偏窄**（独立审核提出） | 同上（`GoStyleRequired.explicitNull:67-71` / `field:32-36`） | `docs/L3-降级清单.md` **D-01** | 三态语义（**缺席**不报 / **显式 null** 报 / **空串**合法）＋ **map/集合/数组永不计必填** | Bean Validation **无等价表达**（`@NotNull` 会把「缺席的 map」也拒 ⇒ 比 B 多拒一类合法输入） | D5 已批准删除，但 D-01 只写了「仅错误文案」⇒ **实际删除的行为语义不在登记范围** | 所有 users 域请求体校验 | 🟡 **中**（台账口径） | ✅ **已扩写**（2026-11-02）：`docs/L3-降级清单.md` D-01 加 dated note，影响面订正为「错误文案 ＋ map/集合的必填判定 ＋ 空串合法性」⇒ **降级结论不变（仍接受），只把登记写准** | 本行 ＋ L3 清单的 dated note |
| **D-10** | DDL 执行器的**调用点** | `user-rpc/.../UserSchemaBootstrap.java:51` · `admin-rpc/.../AdminSchemaBootstrap.java:55` | `service/users/user/.../wiring/UserSchemaBootstrap.java` · `service/users/admin/.../wiring/AdminSchemaBootstrap.java`（**他 lane，未实现**） | 启动即重放 DDL | **执行器已交付**；调用点空壳 | 写权在 `users-user` / `users-admin` 两条 lane | users 域启动时的建表 | 🟠 **跨 lane 依赖（未闭合）** | **登记 + 移交**（本 lane 无他域写权）⇒ 需两个 lane 各调 `SchemaInitializer.applyAll(conn, "<service>")` | 本组件侧已验：执行器 ＋ 脚本 ＋ 幂等 ＋ 4 表建成（真 PG） |
| **D-11** | 遗留依赖 | — | `service/users/common/pom.xml` | B 侧需 gRPC/JWT/bcrypt/Jackson | **C 侧代码已不用**（D3 上收到根 `common/` 后失去使用者） | 依赖面未随上收收敛 | 构建体积（无行为影响） | ⚪ 卫生问题 | ✅ **已清理**（2026-11-02）：删 `grpc-stub` · `protobuf-java` · `javax.annotation-api` · `jjwt-api` · `jjwt-jackson` · `jackson-databind` · `jackson-annotations` · `spring-security-crypto` 共 **8 条** ⇒ **逐条核实传递链完整**（jjwt/jackson/crypto/grpc 由根 `common` 提供 ⇒ 两个 app 各有 `com.rag:common` 依赖；protobuf 由根 `proto` 提供；`javax.annotation-api` 是 `provided` ⇒ 本就不传递）⇒ 本模块只留真需要的 **3 条**（`spring-jdbc` · `jakarta.servlet-api` · `slf4j-api`） | `grep` 归零 ＋ **全仓 `mvn -o -T1C clean verify`**：本模块 SUCCESS（失败项全是**空壳域**的 `repackage`，已用基线纯净副本同条件对照证为**主干自带**） |
| **D-12** | **admin 半边的身份语义无承接**（独立审核提出） | `admin-api/.../IdentityResolver.java:112-139`（**不做** status 检查） | `service/users/common/.../IdentityResolver.java` ＋ **尚不存在**的 admin 版 `UserStatusLookup` | admin 解析不做 status presence/活跃检查 | 唯一实现是 user 版（**必做** status 检查）⇒ admin 若直接复用，会把 B 允许的 admin **拒掉** | 副本消解时把 admin 差异「下沉给端口」，但**适配器的实现落在 admin lane** | admin 子域鉴权链 | 🟠 **中**（跨 lane） | **契约已在本 lane 钉死**：javadoc 写明「admin 适配器必须返回 present 且 status=0」＋ 反例警告 ＋ 2 条断言（`adminSideAdapterContract` / `adminAdapterMustNotReportAbsentStatus`）⇒ **剩下的是 admin lane 的实现** | 真 PG 端到端 ＋ 契约断言 |
| **D-13** | `StringInt64` 无运行证据（独立审核判 **NOT_VERIFIED**） | `http/GoStyleStringInt64.java:83-125` | `common/web/StringInt64.parse`（**上提落点**） | 8 分支判定 | 代码与 B **逐字节等价** | 上提时**测试资产未随迁** ⇒ 全仓 0 个测试调用 ⇒ 按协议 §九 不得以静态相似度替代运行验证 | 该类的行为证据等级 | 🟡 **低** | ✅ **已闭合**（2026-11-02）：新增 `StringInt64ParityTest`（**8 例**）落本模块（对根 `common` 用 **test 作用域**依赖；**根 `common/` 一字未改** ⇒ 不越权）⇒ **NOT_VERIFIED → 已验证** | `StringInt64ParityTest`（接受 4 类 / 拒绝 4 类 / 消息形状） |
| **D-14** | 错误契约在 C 有**两个潜在落点**（独立审核提出） | B 的 `ErrorCode` 是 user/admin **共用一份** | C：`com.rag.users.common.ErrCodes/ErrMsg`（**已填**）＋ `com.rag.user.contract.ErrCodes/ErrMsg`（**空壳**）＋ admin **无** `contract/` 包 | 一份共用 | 两个位置都能写同一份契约 | D7 删 `*-contract` 子模块后，`contract/` 包只落在 user 子域 | 下一棒填 user/admin 域时的落点选择 | 🟡 **低（结构陷阱）** | **登记**：users 域错误契约**只留公共库这份**；`com.rag.user.contract.ErrCodes/ErrMsg` 应删除或改为**委托** ⇒ **不得**再填一份副本（那会制造「同一规则的第二份实现」） | 映射表 **F-2** ＋ 本台账 U-01~U-05 |
| **D-15** | **继承自 B**（非 C 引入）：bcrypt 前缀接受面 | 上游 `x/crypto/bcrypt` 的 `decodeVersion` 用 `>` ⇒ 接受 `$0$`/`$1$`/`$2x$`；Spring 的 `BCRYPT_PATTERN` ⇒ 拒 | C 的 `PasswordUtil` 与 B **逐字节相同** ⇒ 同拒 | 上游接受 / B 拒 | 同 B | B 侧**已签字偏离** `SD-USERSCOMMON-001` | 口令比对 | 🟡 **低（继承 ＋ 台账缺口）** | **不修**（基线是 B）⚠️ 但 **C 仓 `docs/Intentional_Deviations/_INDEX.md` 未承接这条签字** ⇒ 且该类已上提到根 `common/` ⇒ **影响面从 users 域扩大到全 10 域** ⇒ 建议收口任务补登记 | B 侧台账 `RAG/docs/DRIFT/users/common.md` |
| **D-16** | **继承自 B**（非 C 引入）：DDL 与上游的 9 处实质差异 | 上游的 `AutoMigrate`：`users_user.status` **nullable=YES** · 8 个时间列**无** `DEFAULT CURRENT_TIMESTAMP` · 索引集不同 | C 的 4 份 SQL 与 B **逐字节相同** ⇒ 同 | 上游与 B 不同 | 同 B | B 侧已知（`UNRESOLVED-USERSCOMMON-004`） | DDL 契约 | 🟡 **低（继承）** | **不修**（基线是 B）⇒ 登记，防对拍脚本误判 | C 侧 4 份 SQL 与 B 的 md5 一致 ⇒ 差异集相同 |

---

## 11.6 完成度统计（§十一·11.6）

> **口径**：按 §11.2 表**逐行重数**（脚本重数：**承接行 31 条** ＝ 已实现且已验证 **28** ＋ 已批准主动变更 **1** ＋ 已登记偏离 **1** ＋ 跨 lane 未闭合 **1**），非手填；**不用文件数 / 代码行数代表完成度**。

| 档位 | 条数 | 说明（对应 §11.2 的行） |
|---|---|---|
| ✅ **已实现且已验证** | **28** | U-01~U-14（含 5 个上提落点的等价性核对）· U-16~U-19 · U-20~U-27 · **U-30**（`resolveUser` 对外返回面）· **U-31**（`StringInt64` 运行对拍） |
| ⚠️ 已实现但未验证 | **0** | —（U-31 已于本轮闭合） |
| ❌ 尚未实现 | **0** | 本组件内无（U-29 的**执行器已实现**） |
| 🔴 明确存在漂移（**C 引入**） | **0** | 无「C 引入」的**业务行为**漂移 |
| 🟡 框架差异待验证 | **0** | §11.4 的 7 条全部有验证证据或判定为不适用 |
| ⛔ 被外部条件阻塞 | **0** | 测试容器镜像 `postgres:16-alpine` **本地已有**；⚠️ `testcontainers/ryuk` 本机镜像源拉不动 ⇒ 跑 Testcontainers 需 `TESTCONTAINERS_RYUK_DISABLED=true`（**环境限制，非 C 侧缺陷**；C 侧代码不含该开关、**未**使用被禁的 `@EnabledIfDockerAvailable`） |
| 🏷 **已批准的主动变更** | **1** | **U-15**（D5 删除 4 个必填校验器 · L3 D-01） |
| 🟡 **已实现 + 已登记偏离**（行为已验证，差异已登记） | **1** | **U-28**（B 的 admin 副本被消解 · D-02）—— 另 U-08 的前序登记偏离（D-08）已计入上面 28 条内 |
| 📌 **跨 lane 未闭合**（**不计入本组件**） | **1** | **U-29**（DDL 执行器的**调用点**在 `users-user` / `users-admin` 两条 lane ⇒ 见 D-10） |
| 🟡 表达层 / 文档级 / 卫生项**登记差异** | **17** | D-01 · D-02 · D-03 · D-04 · D-05 · D-06 · D-07 · D-08 · D-09 · D-09b · D-10 · D-11 · D-12 · D-13 · D-14 · D-15 · D-16（其中 **D-09b / D-11 / D-13 已在本轮闭合**） |

**结论（§十四 · 逐条见 §11.7）**：`service/users/common/` 承接了 B 侧 `service/users/users-common/` 的**全部源职责**，**未发现 C 引入的业务行为漂移**；非 PASS 性质的差异**全部是已登记的表达层 / 文档级 / 跨 lane 项**，无一条阻塞本组件。
> ✅ **独立审核已跑**（见 §11.8）：结论 =「**组件内实现可判迁移完成**」；它提出的 **3 条本组件待办**（admin 适配器 / D5 降级登记范围 / `StringInt64` 未验证）**已全部闭合或降级为跨 lane 项**。

---

## 11.7 §十四 的 12 个问题 ＋ 最终审核标准（逐条回答）

| # | 问题 | 回答 |
|---|---|---|
| 1 | C 是否完整承接 B 的全部实际职责？ | **是**（本组件范围）：**11 类 ＋ 4 份 SQL** 逐条落位（§11.2 U-01~U-31）；B 的两份 `IdentityResolver` 副本**被消解为一份**且差异已下沉到端口（D-02）；⚠️ **2 处未承接均已定性并有归属**：① D5 必填校验族（**已批准删除**，但用户域 DTO 尚空壳 ⇒ 承接在 users-user lane，见 D-09/D-09b）② admin 侧适配器（**契约已钉死**，实现落在 admin lane，见 D-12） |
| 2 | Mapping_Table 的 RAG→RAG-V1 映射是否与真实代码一致？ | **基本一致，有 1 处过期**：`ddl` 行仍写「C 侧无落点」⇒ 记 **D-06**（按 §2.1 记录不改表） |
| 3 | File_Tree 规定的目标结构是否正确落地？ | **是**（目录树 + 职责注释均已重生成核对）；但 §五 的「文件内容 ⬜ 全部待填」是**生成器硬编码** ⇒ 记 **D-07** |
| 4 | 多文件合并是否造成职责 / 分支 / 异常路径遗漏？ | **否**：B 的 4 个可执行面（`getCode`/`getMsg`/`of`/`getMsg(int)`）逐条落位（8.1）；无遗漏分支（未知码回落、5 sentinel、判定顺序均有断言） |
| 5 | 文件拆分是否造成调用链 / 数据传递 / 事务边界断裂？ | **否**：判定链 6 步顺序不变、uid 逐段透传、异常原样传播、无事务边界、无重复/遗漏执行（8.2） |
| 6 | API + RPC 合并是否改变原有入口与执行行为？ | **是，但已登记且等价**：查 user 的通道由 gRPC 变 JDBC（D-01）⇒ 入口不变（HTTP 路径 / method 不在本组件）、判定链不变 |
| 7 | Common Starter 上收是否遗漏原有消费方？ | **否**：5 个上提落点均在根 `common/` 内、与 `users-common` **无依赖关系** ⇒ 消费方（users 两个 app）经根 `common/` 获取，链路完整（8.3） |
| 8 | Boot 4 → 3.5 是否引入业务可观察行为变化？ | **否**（§11.4 ①~⑦ 逐条有证据或判定不适用） |
| 9 | Tomcat / Jackson / Spring Framework 变化是否产生实际漂移？ | **否**：Jackson 3→2 的 6 项能力 API 同构；spring-jdbc 7→6 的 `ScriptUtils` 包名/语义未变且脚本无 `$$`；Tomcat 不适用 |
| 10 | 哪些差异已证明安全？ | D-01 · D-02 · D-03 · D-04 · D-05 · D-07 · D-08 · D-09 · D-09b · D-11 · D-12 · D-13 · D-14（各有对应证据列；其中 **D-09b / D-11 / D-13 已在本轮闭合**） |
| 11 | 哪些差异仍无法确认？ | **组件内无未确认项**（独立审核原先判的 `StringInt64` NOT_VERIFIED **已闭合**）；**剩余 2 条均在他 lane**：D-10（DDL 调用点）· D-12（admin 适配器实现）⇒ 属**他组件**的未审范围 |
| 12 | 当前 C 是否具备可靠的行为对拍条件？ | **组件内：是**（错误契约 31 条冻结期望值 ＋ 身份判定链 ＋ `StringInt64` 8 分支 ＋ DDL 在**真 PostgreSQL** 上；Testcontainers `postgres:16-alpine` 本地已有）⇒ **跨组件：否**（两个消费域仍是空壳 ⇒ 端到端对拍条件未就绪） |
| 13 | **最终审核标准**（协议 §十四 的最后一句） | **达标**：C 无需复制 B 的文件结构，但已完整承接 B 的全部实际职责与需保留的行为；框架实现不同处（Jackson 3→2 / gRPC→JDBC）均有等价论证与运行证据，**无未经批准的行为漂移** |

---

## 11.8 独立审核（按 `.agents/verification.md` 跑的第二遍）

> **为什么要第二遍**：本仓 §三·2 要求「每域做完**必过** `.agents/verification.md` 检测」⇒
> 除本 lane 自审外，另起**独立审核子代理**按同一协议重跑一遍（**只审不改**，全程只写 `/tmp` 副本）。
> **审核基线**：实现 commit `3fb594b`（审核途中本 lane 追加了 `2dc0e6d` ⇒ 审核方**两个基线都实测**，结论一致）。

### 独立审核的结论（原文摘要）

> 「**该组件（`service/users/common/` + 5 个上提落点）可判『迁移完成』**，但带 3 条本组件内待办、1 条跨 lane 待办
> —— 实现层**没有发现 C 引入的实质性行为漂移**；31 条错误契约、4 份 DDL、身份判定链、bcrypt/JWT/强转三条横切原语都逐条对上了 B。」

### 独立审核提出的待办 → 本轮处置

| 审核项 | 审核方的判定 | 本轮处置 | 现状 |
|---|---|---|---|
| **admin 半边身份语义无承接**（B 的 admin 版不做 status 检查） | **NOT_IMPLEMENTED**（本组件内唯一实质缺口） | 契约在本 lane 钉死：javadoc 写明「admin 适配器必须返回 present 且 status=0」＋ **反例警告** ＋ 2 条断言（`adminSideAdapterContract` / `adminAdapterMustNotReportAbsentStatus`） | ✅ **契约已闭合**；**实现**落在 admin lane（跨 lane 依赖，见 D-12） |
| **D5 降级登记范围窄于实际删除范围**（D-01 写「仅文案」，实际还含「缺席 vs 显式 null」「map/集合不计必填」） | **中**（降级登记不完整） | `docs/L3-降级清单.md` **D-01** 加 dated note，影响面订正 ⇒ 降级结论不变、登记写准 | ✅ **已闭合**（D-09b） |
| **`StringInt64` 无运行证据**（全仓 0 个测试调用 ⇒ 按协议 §九 不得以静态相似度替代） | **NOT_VERIFIED** | 新增 `StringInt64ParityTest`（**8 例**：接受 4 类 / 拒绝 4 类 / 消息形状）落本模块（对根 `common` **test 作用域**依赖；**根 `common/` 一字未改** ⇒ 不越权） | ✅ **已闭合**（D-13） |
| **admin sentinel 文案措辞统一成 user 版** | FAIL（低，仅日志） | 不修：B 侧两副本消解是**已批准决策**（映射表 F-1）⇒ 归「已登记偏离」而非漂移；且消息**不进 wire**（B 两侧 handler 的响应体用 `ErrorCode.XXX.getMsg()` 固定文案） | ✅ **已定性**（D-03） |
| **pom 残留 7 条未使用依赖** | 低（卫生） | 逐条核实传递链后**清理 8 条**（含 `javax.annotation-api`）⇒ 本模块只留真需要的 3 条 | ✅ **已闭合**（D-11） |
| **错误契约两个潜在落点**（公共库已填 vs `com.rag.user.contract.*` 空壳） | 低（结构陷阱） | 登记：users 域错误契约**只留公共库这份**，user 子域那份应删除或改委托 | 🟡 **已登记**（D-14）⇒ 属 users-user lane 的落点选择 |
| **DDL 执行器的调用点未实现** | 中（跨 lane） | 执行器已交付；调用点属 users-user / users-admin 两条 lane ⇒ 登记移交 | 🟠 **跨 lane 未闭合**（D-10） |
| **继承自 B 的两条**（bcrypt 前缀接受面 / DDL 与上游的 9 处差异） | 低（继承，非 C 引入） | 不修（基线是 B）⇒ 登记防对拍脚本误判 | 🟡 **已登记**（D-15 / D-16） |

### 变异实测汇总（**本 lane + 独立审核各做一轮，合计 14 次，全部变红**）

| # | 变异点 | 期望 | 实测 |
|---|---|---|---|
| M1 | `ErrMsg.getMsg` 未知码回落改 `null` | 红 | 🔴 红（`unknownCodeFallsBackToFail`） |
| M2 | `IdentityResolver` 活跃判定反向（`!=0` → `==0`） | 红 | 🔴 红（4 例，含端到端） |
| M3 | `SchemaInitializer` advisory lock 键改值 | 红 | 🔴 红（`advisoryLockKeyIsFrozen`） |
| M4 | `JdbcUserStatusLookup` 的「查无此人」判定破坏 | 红 | 🔴 红（2 例，含端到端） |
| M5 | `IdentityResolver.CLAIM_ATTRIBUTE` 改值 | 红 | 🔴 红（`frozenConstants`） |
| M6–M14 | 独立审核的 9 次（错误码值 / 回落 / status 判定 / 查无此人 / lock 键 / Bearer 判定 / 强转规则 / 密钥补零 / bcrypt catch 收窄） | 红 | 🔴 **9/9 红**（含「退回 B 形态」的 `>7` 变异） |

> ⚠️ **诚实备注（本 lane 自查时踩到）**：本 lane 的首轮变异里**有 2 次「没变红」** ⇒ 复查发现是
> **变异脚本的 `replace` 命中了 javadoc 里的同一串文本、没改到代码** ⇒ 属**变异无效**，不是测试假绿
> ⇒ 改用**精确锚点**（整行代码）重跑后如期变红。
> ⇒ 教训沿用仓内既有口径：**「变异没变红」先分清「断言没覆盖到」与「变异没生效」**（两者都会表现为「绿」）。

## 11.9 复审（非 PASS 项）的闭合处置（2026-11-02 · 第三轮）

> **背景**：第二轮独立审核（复审）判定 **VERDICT: PASS**，但另列 7 条非 PASS 项（N-01~N-07）⇒
> 其中 **N-01~N-04 落在本 lane 的写权内**，本轮**全部闭合**；
> **N-05 / N-06（跨 lane）· N-07（他 lane）** 保持登记状态，见 §11.5 与 §11.8。

| ID | 审核方的判定 | 本轮处置 | 现状 |
|---|---|---|---|
| **N-01** | D-01 的三处**权威字段**未随 dated note 同步 ⇒ D-01 登记**内部自相矛盾**（「把登记写准」只完成一半） | ① `docs/L3-降级清单.md` D-01 的「**影响面**」单元格：由「仅错误文案」扩写为「错误文案 ＋ map 字段的必填判定 ＋ 标量字段的空串合法性」（并标注适用范围 = users 域）；② 同文件 §四「当前状态」汇总表的 D-01 行：项名同步扩写；③ `docs/决策记录.md` D-05「**代价**」行：拆成 ①②③ 三条，与 D-01 对齐 ⇒ **降级结论（接受降级）一字未改** | ✅ **已闭合** |
| **N-02** | `IdentityResolver.java:307` 写「取回之后的 **3** 步校验」，但同段列了 **4** 项、`:314` 又说「第 3~6 步」⇒ 数字自相矛盾 | 改为「**4 步**校验」（与 `IdentityResolverTest.java:203` 的 `@DisplayName` 一致）⇒ **生产可执行代码零改动** | ✅ **已闭合** |
| **N-03** | D-01 dated note ① 把 `explicitNull` 三态语义**泛化到所有字段**；② 「B 侧 map/集合从不参与必填判定」作为**全局**断言不成立（like 域 `GetLikeStateReq.java:46` 反例） | 本 lane **回 B 源码逐条复核**（`GoStyleRequired.java:32-36` / `:67-71` · `GoStyleRequiredFields.java:13` / `:42-44` · users 域 `RegisterReq.java:56-61` · like 域 `GetLikeStateReq.java:46`）后收窄：① 改为「**限 map 字段**」并写明标量上 `@NotNull` 与 B **等价**（B 本来就拒「缺席」与「显式 null」）；② 改为「**限 users 域**」并写明「Map / Slice / Array 永不计必填」是 **上游 `core/mapping` 的判据**、**不是** B 全域行为；另**新增一条 dated note** 记录本次订正本身 | ✅ **已闭合** |
| **N-04** | `pom.xml`「protobuf 由根级 `proto` 提供」不准 —— 实际经根 `common` 的 `grpc-protobuf`(79) 传递；根级 `proto` 只出 pb 生成类 | 改写该条依赖说明：**protobuf-java 运行时的真实来源 = 根 `common` 的 `grpc-protobuf`(79)**；根级 `proto` 模块**只提供 pb 生成类**（`com.rag.proto.*`）、**不带 protobuf-java 运行时** ⇒ 结论（两个 app 传递链完整）**不变** | ✅ **已闭合** |

> ⚠️ **本轮附带修正（不在 N 列表内，但同源 —— 行号精度）**：
> `IdentityResolver.java` 引用 B 侧行号 `176-217`（**3 处**：`:104` / `:276` / `:343`）实际跨进了 B 的 `resolveSubjectRef`
> ⇒ 逐条改为 **`176-210`**（本 lane 已回 B 源码核对：`resolveUser` = `:176-210`、`resolveSubjectRef` = `:216-224`）；
> 另 `:122`（`198-209` → **`199-208`**）与 `:312`（`186-209` → **`192-208`**）两处区间同步收窄。
> ⇒ **本轮全部改动落在 javadoc / pom 注释 / 文档 ⇒ 生产可执行代码改动量 = 0 ⇒ 不触发行为漂移。**

### 本轮的运行证据

| 检查 | 结果 |
|---|---|
| `mvn -o -pl service/users/common -am test`（**ryuk 开启模式**，不带 `TESTCONTAINERS_RYUK_DISABLED`） | **46 例全绿 · failures=0 · errors=0 · skipped=0** · BUILD SUCCESS |
| ryuk 是否真启动 | ✅ `service/users/common/pom.xml` **无** ryuk 禁用配置（只有根 `common/pom.xml:451` 设了）⇒ 本模块跑的是 **ryuk 开启**模式；跑完 **ryuk 容器与 PG 容器零残留**（`docker ps -a` 按 `ancestor` 查 ⇒ 0） |
| `python3 tools/check-mapping-coverage.py` | EXIT=0 · C 624/624 · A 482/482 · B 未覆盖 0 |
| 生产可执行代码改动 | **0** |

---

## 11.10 映射表对齐 + 守门口径纠正（2026-11-02 · 第四轮）

> **为什么有这一轮**：本轮收尾时重跑守门 `check-mapping-coverage.py` ⇒ **EXIT=1**（C 列 625/626）
> ⇒ 缺口是「`users: C 列缺失 common/src/main/java/com/rag/users/common/JdbcUserStatusLookup.java`」。

### ⚠️ 先纠正一个**测错基准**的问题（上一轮汇报的「守门全绿」不成立）

| 项 | 内容 |
|---|---|
| **现象** | 上一轮汇报「`check-mapping-coverage.py` ⇒ EXIT=0 · C 624/624 · A 482/482」⇒ **量错了对象**。 |
| **根因** | `tools/check-mapping-coverage.py:26` 的 `ROOT` **硬编码** = `/Users/pr/Documents/GitHub/RAG-V1`（**主工作区**）⇒ 在工作树里直接跑它，量的是**主工作区**的状态（本轮实测：主工作区当时正有别的 lane 在改 `docs/Mapping_Table/README.MD`，跑出来是 `A 38/114` + `B 列 748 处问题` + `ZeroDivisionError`）⇒ **不是本 lane 的成绩，也不是本 lane 的锅**。 |
| **修法** | 与 `gen-file-tree.py` 同一套办法：`sed` 把 `ROOT` 改指向本工作树再跑；并对**基线纯净副本**（`git archive b973d61`）同条件对照 ⇒ 基线 EXIT=0（624/624），本工作树 EXIT=1（625/626）⇒ **红是本 lane 引入的**。 |
| **⇒ 教训** | 「守门脚本的 ROOT/输入基准必须显式指向被测树」—— 否则读到的是**别人的树**。本轮已写进台账，避免下一棒再踩。 |

### 处置：3 个文件（映射表与实际状态对齐）

| 文件 | 改动 | 依据 |
|---|---|---|
| `docs/Mapping_Table/users/users-common.md` | ① §0.5 速查表：`ddl/` 行由「C 侧无落点」改为**实际落点**（`SchemaInitializer.java` + `resources/db/01..04_*.sql`）；**新增 2 行**（`IdentityResolver` 的 **2 → 1** 消解 · `JdbcUserStatusLookup` 的 **C 新增**）② §1.3：`SchemaInitializer` 行的 C 列由「**（无对应物）**」改为实际落点；**新增 2 行**（`IdentityResolver` = `N:1` · `JdbcUserStatusLookup` = `C 新增`）③ §7.1 C 列覆盖率 **2/2 → 5/5**（并修 `find` 命令里的 D7 前的 stale 路径 `service/users/users-common` → `service/users/common`）④ §7.3 映射类型分布 **10 → 12**（`N:1` 1 条 · `C 新增` 1 条） | 本 lane 交付的落点必须进 B↔C 抄写路线图（AGENTS.md §1.0c） |
| `docs/Mapping_Table/users/_shared.md` | ① §7.1 域级 C 列覆盖率 **103/103 → 106/106**（user 45 + admin 56 + users-common 5 = 106）② §7.4 **F-3** 由 🔴 改为 **✅ 已关闭（2026-11-02）**（**行保留** ⇒ §三 总数 90 不变，同 2026-11-01 F-1 的销账做法）③ §2 持久层差异行、§8 总结里「C 侧脚本与执行器缺失」两处 stale 表述同步改写 | F-3 的前提（执行器 + 脚本）已由本 lane 交付 |
| `docs/Mapping_Table/_INDEX.md` | ① users 域级自报数字 ×4 处（行数 `410 + 204 + 259 + 284 = 1,157` · C 列覆盖 `106 / 106` · 映射行 `131`）② 合计行（行数 `6,146` · C `626 / 626` · 映射行 `883`）③ 🔴 高优先级表**销账 F-3 一行**（标题 11 条 → **10 条**）④ 销账说明追加第 3 条 | 守门第 ③④ 重校验要求域级自报数 = 文件系统实测数 |

> ⚠️ **写权说明（须让主会话知情）**：`docs/Mapping_Table/**` 与 `docs/决策记录.md` **不在本 lane 原声明的写权内**
> （原写权 = `service/users/common/` ＋ `docs/DRIFT/users/`）⇒ 本轮改动是**经用户本轮授权（「全收」）**做的，
> 目的是让守门回绿 + 让映射表不再宣称「C 侧无落点」。⇒ **合主干时请一并 review 这 3 个文件的 diff。**

### 本轮守门与测试结果

| 检查 | 结果 |
|---|---|
| `check-mapping-coverage.py`（ROOT 指向本工作树） | **EXIT=0** · C **626/626** · A **482/482** · B 未覆盖 **0** · 域级自报数对账 ✅ · 合计行对账 ✅ · 小节自报条数 ✅ · 主表行形状 ✅ · B 列双向对账 ✅（精确 663 · 通配 2 · 目录 65）· 指代消解 ✅（769→**771/797** = 96.7%） |
| 基线纯净副本同条件对照（`git archive b973d61`） | EXIT=0 · 624/624 ⇒ **本轮改动未引入新缺口**（红→绿 = 本 lane 补齐了登记） |
| `gen-file-tree.py` 重生成 | **零漂移**（仅 `proto.md` 的空目录假差异 ⇒ 已 `git checkout --` 回滚） |
| `mvn -o -pl service/users/common -am test` | **46 例全绿 · failures=0 · errors=0 · skipped=0**（ryuk 开启模式） |
| 生产可执行代码改动 | **0**（本轮全部是文档 / javadoc / pom 注释） |

### 本轮**未**闭合（仍待主会话 / 他 lane）

| 项 | 归属 |
|---|---|
| `docs/DRIFT/_INDEX.md` 的 users 行仍写「⬜ 未建立」 | 主会话（共享索引） |
| `docs/Mapping_Table/users/_shared.md` §7.4 **F-2**（admin 侧尚未引用公共库那份错误契约）仍开 | users-user / users-admin 两条 lane |
| B 的签字偏离 `SD-USERSCOMMON-001`（bcrypt 前缀接受面）在 C 仓 `Intentional_Deviations` 未承接 | 主会话（影响面已扩大到全 10 域） |
| F-3 的剩余跨 lane 项 = **DDL 执行器的调用点**（users-user / users-admin） | 本表 D-10，已在 §11.5 与映射表两处标注 |

---

## 附：本组件的运行证据（可复跑）

```bash
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/users-common

# 单元 + 真 PostgreSQL 集成（46 例 · skipped=0）
mvn -o -pl service/users/common -am test
# ✅ 2026-11-02：`testcontainers/ryuk:0.12.0` 已拉到本地 ⇒ **不再需要** TESTCONTAINERS_RYUK_DISABLED
#    （仅当 ryuk 镜像不可用时才加该环境变量；本模块用类级 @Container，ryuk 关闭时残留风险低）

# 三方对照守门（C 列 624/624 · A 列 482/482 · B 列未覆盖 0）
python3 tools/check-mapping-coverage.py

# 文件树重生成（⚠️ 脚本 ROOT 硬编码为主工作区 ⇒ 本工作树跑时须改 ROOT）
python3 tools/gen-file-tree.py
```

**测试构成**（`service/users/common/src/test/java/com/rag/users/common/`）

| 测试类 | 例数 | 覆盖 |
|---|---|---|
| `ErrContractParityTest` | 5 | 31 条码值 + 31 条文案**逐条**对拍 B · 文案表 key 集合 = 码值集合 · 未知码回落 · 中文文案保持 |
| `IdentityResolverTest` | 22 | `claimedUID` 4 类失败 · `claimedUIDAllowNonPositive` 不拒非正 · **5 个 sentinel** · **判定顺序**（4 组「同时违反多条」）· `resolveUser` 返回面 · `verifyUser` 复用点 · **admin 适配契约 2 例** · 三元组 · 冻结常量 · `SubjectRef` 等价 |
| `StringInt64ParityTest` | 8 | 上提到 `common/web/` 的 `StringInt64.parse` 运行对拍（接受 4 类 / 拒绝 4 类 / 异常消息形状）—— 闭合独立审核判定的 NOT_VERIFIED |
| `UsersSchemaAndStatusLookupTest` | 11 | **真 PG**：`applyAll` 连跑 **3 次**幂等 · 4 表建成 · 列类型（`text` / `timestamptz`）· 4 个索引 · `status` NOT NULL · presence 三态（0 / 非 0 / NULL）· 端到端（三元组 / inactive / not-found）· lock 键冻结 |
| **合计** | **46** | `skipped=0` |

### 全仓构建的同条件对照（⚠️ 必读）

| 树 | `mvn -o -T1C clean verify` 结果 |
|---|---|
| **本工作树**（含本 lane 全部改动） | `common` ✅ · `proto` ✅ · **`users-common` ✅ SUCCESS** · `article`/`comment`/`favorite`/`follow`/`hot`/`like`/`message`/`task` **FAILURE**（`repackage: Unable to find main class`）· `user-service`/`admin-service` SKIPPED |
| **基线纯净副本**（`git archive b973d61`，同条件同命令） | **完全相同的 8 个域 FAILURE** ⇒ **红是主干自带，不是本轮引入** |

⇒ 失败根因：那些域的 `*ServiceApplication.java` 仍是 `TODO：职责待补` 的空壳（**没有 `main` 方法**）⇒
`spring-boot-maven-plugin:repackage` 找不到主类 ⇒ **属那些 lane 的实现工作**（AGENTS.md §0：10 个业务域仍全为空壳）。
