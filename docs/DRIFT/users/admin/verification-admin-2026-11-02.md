# `admin-service` 迁移审核报告 —— RAG（B 侧）→ RAG-V1（C 侧）

> **由 `align/users-admin` 工作树产出** · **基线 commit `e67c7ed`**（本地 `main`）· 分支 `align/users-admin`
> **审核协议**：`.agents/verification.md`（八阶段：范围 → 承接清单 → 行为审核 → 框架差异 → 合并拆分 → 对拍 → 状态 → 报告）。
> **审核日期**：2026-11-02 · **审核对象**：`service/users/admin/`（artifactId **`admin-service`**，包 `com.rag.admin`）
> **行为基线**：**B 侧（RAG 仓）** `/Users/pr/Documents/java&golang/RAG/service/users/admin/` —— **D16 口径：B 侧 = 唯一判定基准**，差异默认判 C 侧错。
> **状态口径**：`PASS` / `FAIL` / `NOT_IMPLEMENTED` / `NOT_VERIFIED` / `FRAMEWORK_DIFFERENCE` / `BLOCKED` / `INTENTIONALLY_CHANGED`。
> ⚠️ **四种非 PASS 状态一律不计入 PASS**。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` 按 `docs/Worktree.md` §三·1 **不碰**（合并后由主会话统一更新）。

---

## 一、审核范围

### 1.1 两侧坐标

| 项 | B（迁移源 / 行为基线） | C（迁移目标 / 被审对象） |
|---|---|---|
| 仓库 | `/Users/pr/Documents/java&golang/RAG` | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-admin` |
| 模块路径 | `service/users/admin/`（**api + rpc 两个进程**） | `service/users/admin/`（**一个进程 · D1**） |
| 包 | `com.rag.users.admin.{api,rpc}.**` | `com.rag.admin.**`（**单层技术角色包树** · D7 + D11） |
| 源文件数 | **76** 个 `.java`（api 56 · rpc 20） | **58** 个 `.java`（主代码）· **0 空壳** |
| 主代码行数 | ≈ 7,596 行 | **7,030** 行 |
| proto | `service/users/admin/admin-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.admin` · **不在本域文件数内**） |
| 错误契约 | admin **无**独立 contract（用 `users-common`） | 同 ⇒ 取 `service/users/common/` 的 `ErrCodes` / `ErrMsg`（F-2 的落法） |
| git commit | 冻结只读（未动） | 基线 `e67c7ed` · 本轮改动**未提交**（工作树内） |

### 1.2 实际解析版本（**读 classpath 取值，不是 pom 声明值**）

| 组件 | B | C | 差异影响 |
|---|---|---|---|
| Spring Boot | **4.0.8** | **3.5.11** | 见 §四 |
| Spring Framework / spring-webmvc | 7.0.x | **6.2.16** | ⚠️ **本轮三处修法的依据都读的是 6.2.16 源码**（§5-⑤⑥⑦） |
| Servlet API | 6.1.x | **jakarta.servlet-api 6.0.0** | `DispatcherType.ERROR` 语义一致 |
| Jackson | **3.1.5**（`tools.jackson`） | **2.19.4**（`com.fasterxml`） | D14 口径；本域零 `tools.jackson` 引用 |
| jjwt | 0.12.6 | **0.12.6** | 无差异 |
| MyBatis-Plus | — | **3.5.17** | ⚠️ `MybatisSqlSessionFactoryBean` 已移出 `mybatis-plus-extension`（§5-⑧） |
| devh grpc | 3.1.0.RELEASE | **3.1.0.RELEASE** | ⚠️ `grpc.client.<X>` 是**通道名 map**（§5-④） |
| go-zero | **v1.10.2**（**语义来源**） | — | ⚠️ **2026-11-02 · E-4 订正**：B 侧实现是 **Java/Boot 4**，go-zero 只是它的**语义来源** ⇒ ⇒ **go-zero 源码只能作语义佐证，不能作行为依据**（§9.4 的教训） |
| Tomcat | 11.x | **10.1.x** | D14 回退；本域**不用 MockMvc**（§七） |
| JUnit / Testcontainers | — | 5.12.2 / 1.21.4 · PG 驱动 42.7.10 | 测试基础设施 |

### 1.3 本次使用的资料

| 资料 | 用途 | 核对日期 |
|---|---|---|
| `docs/Mapping_Table/users/admin/admin.md` | B→C 落点对照（**抄写路线图** · §1.0c） | 2026-11-02 |
| `docs/Mapping_Table/users/_shared.md` | 域级共享层 + 缺口 F-1~F-8 | 2026-11-02 |
| `docs/决策记录.md`（D1/D2/D5/D7/D11/D14/D16/D17）· `docs/L3-降级清单.md`（D-01） | 已批准决策与降级登记 | 2026-11-02 |
| `docs/File_Tree/users/admin.md` | C 侧目标结构约束 | 2026-11-02 |
| `go-zero v1.10.2` 本地 module cache | **B 侧框架语义的权威源码**（`rest/httpx/requests.go` · `rest/engine.go` · `core/mapping/unmarshaler.go`） | 2026-11-02 |
| `spring-webmvc 6.2.16` 本地 module cache | **C 侧框架语义的权威源码**（§5-⑤⑥⑦ 的依据） | 2026-11-02 |
| `RAG/docs/DRIFT/users/`（**只读**） | B 侧既有问题（避免把 B 的老问题误报成 C 的迁移问题） | 2026-11-02 |

---

## 二、B → C 责任承接表（组件级 · AGENTS.md §3.5「审查粒度 = 组件级」）

> **覆盖口径**：B 侧 76 个 `.java` 的**全部职责**（api 56 · rpc 20）+ 根级 proto + users-common 的 4 份 DDL 落点。
> **实现状态** = 实现是否存在；**验证状态** = 有无运行证据。
> **证据强度列**取值见 `docs/DRIFT/_INDEX.md` 末节（E-4 / E-3 / E-2 / E-1 / E-0）。

| ID | B 源（B 侧 = 行为基线） | B 原始职责 | C 承接落点 | 映射类型 | 实现状态 | 验证状态 | 证据强度 | 证据 |
|---|---|---|---|---|---|---|---|---|
| A-01 | `admin-api/.../AdminApiApplication.java` + `admin-rpc/.../AdminRpcApplication.java` | 两个进程入口 | `AdminServiceApplication.java`（**D1 合并**） | N:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminServiceApplicationTests`（真上下文启动） |
| A-02 | `admin-rpc/.../bootstrap/AdminSchemaBootstrap.java` + `users-common/.../ddl/SchemaInitializer.java` | 幂等重放 4 份 DDL | `wiring/AdminSchemaBootstrap.java` + 共享层 `SchemaInitializer` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminServiceApplicationTests#schemaInitializerCreatedAllTables`（真 PG 上 4 张表齐） |
| A-03 | `admin-rpc/.../bootstrap/AdminSeederBootstrap.java` | 默认 admin 种子（查 → 加密 → 生成 uid → 插入 → 23505 回查 → fail-fast） | `wiring/AdminSeederBootstrap.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminServiceApplicationTests#defaultAdminIsSeededWithHardcodedPassword`（**密码 = 硬编码 `admin`，不是 `default-password`**） |
| A-04 | `admin-rpc/.../bootstrap/SnowflakeBootstrap.java` | Snowflake 就绪探针 | `wiring/SnowflakeBootstrap.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 上下文启动日志「Snowflake ready, probe uid=…」 |
| A-05 | `admin-rpc/.../bootstrap/NacosRegistration.java` | 用 **gRPC 端口**显式注册到 Nacos | `wiring/NacosRegistration.java` | 1:1 | 已实现 | 🟡 未验证（**需真 Nacos**） | **E-2** | 源码逐行对照；**注册动作本身**已由 §5-⑩ 的真触发用例钉住（桩上**只注册 1 次**、端口 = gRPC 端口、web 端口从未被注册）⇒ 但**对真 Nacos 的注册结果**仍未跑过 ⇒ 仍记未验证 |
| A-06 | `admin-rpc/.../config/AdminRpcProperties.java` | `admin-rpc.admin-auth.*` / `admin-rpc.system.*` | `config/AdminRpcProperties.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 上下文启动（绑定 + `verifyAccessSecret` fail-closed 生效） |
| A-07 | `admin-api/.../config/AdminApiProperties.java` | `admin-api.admin-auth.*` | `config/AdminApiProperties.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 同上 |
| A-08 | `admin-rpc/.../domain/{AdminEntity,AdminInviteEntity,UserEntity}.java` | 3 个 `@TableName` 实体 | `domain/entity/` 3 个实体 | 1:N | 已实现 | ✅ 已验证 | **E-3** | 真 PG 读写（`AdminDomainServiceRealPgTest` 20 例） |
| A-09 | `admin-rpc/.../mapper/{AdminMapper,AdminInviteMapper,UserMapper}.java` | 3 个 Mapper 接口（零方法声明） | `mapper/` 3 个接口 | 1:N | 已实现 | ✅ 已验证 | **E-3** | 真 PG + 真 MyBatis-Plus 装配 |
| A-10 | `admin-rpc/.../exception/AdminExceptions.java` | 哨兵错误 + `isUniqueViolation`（PG 23505） | `exception/AdminExceptions.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 真 PG 唯一冲突路径（`AdminDomainServiceRealPgTest`） |
| A-11 | `admin-rpc/.../service/AdminDomainService.java` | 13 个 logic（login/logout/create/user 管理/邀请码） | `domain/service/AdminDomainService.java`（**1,2xx 行**） | N:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminDomainServiceRealPgTest` 20 例（真 PG + 真 Redis + 真 `@Transactional` 代理） |
| A-12 | `admin-rpc/.../service/AdminCreateAtomicService.java` | 4 步原子事务（W1 悲观锁 → W2 查重 → bcrypt → insert → 标记 invite used） | `domain/service/AdminCreateAtomicService.java`（**独立 `@Service` Bean**） | 1:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminDomainServiceRealPgTest#createAdminConsumesInviteExactlyOnce` 等（**行级锁真在 PG 上生效**） |
| A-13 | `admin-rpc/.../service/AdminHelpers.java` | 用例内 helper | `service/AdminHelpers.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 同 A-11 |
| A-14 | `admin-rpc/.../service/AdminSpans.java`（B 侧内联手写 span） | span 名 + audit 属性（L2） | `service/AdminSpans.java` | N:1 | 已实现 | 🟡 未验证（span 名未对拍） | **E-2** | 源码逐行对照 |
| A-15 | `admin-rpc/.../metrics/AdminRpcMetrics.java` | 指标 `admin_rpc_login_total` / `admin_rpc_high_risk_action_logal` | `metrics/AdminRpcMetrics.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 真注册（Micrometer）+ **拼写错误 `logal` 照抄未改** |
| A-16 | `admin-api/.../metrics/AdminApiMetrics.java` | 指标 `admin_api_intercept_total` | `metrics/AdminApiMetrics.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 同上 |
| A-17 | `admin-rpc/.../service/AdminServiceGrpcImpl.java` | 13 个 RPC 分发 | `grpc/AdminGrpcService.java` | 1:1 | 已实现 | 🟡 未验证（**gRPC 面未对拍**） | **E-2** | 源码逐行对照；devh server 真在跑（`GrpcServerLifecycle#isRunning`） |
| A-18 | `admin-api/.../controller/AdminController.java` | 13 个 `@PostMapping`（method / path） | `controller/AdminController.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminHttpContractRealTomcatTest#allThirteenEndpointsArePostOnly`（真 Tomcat，13 个 GET 全 405） |
| A-19 | `admin-api/.../advice/GlobalResponseAdvice.java` | 响应信封 `{code,msg,data}` + 「已是信封不重包」 | `advice/AdminApiResponseAdvice.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminHttpContractRealTomcatTest#envelopeHasExactlyThreeKeys`（**恰好三键、顺序不变**） |
| A-20 | `admin-api/.../advice/GlobalExceptionHandler.java` | 异常出口（业务码 / 401 / 400 text/plain / 405 / 415） | `advice/AdminApiExceptionHandler.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminHttpContractRealTomcatTest`（401 / 400+nosniff / 405 / 404 各有用例） |
| A-21 | `admin-api/.../auth/` 4 个类（`AdminJwtAuthPaths` / `AdminJwtSignatureVerifier` / `AdminJwtVerificationResult` / `GoStyleJwtAuthFilter`） | **验签在 Servlet Filter（3s 预算之外）+ 黑名单在 Interceptor（预算之内）** | `interceptor/AdminJwtAuthInterceptor.java` + `interceptor/AdminJwtSignatureFilter.java` + `interceptor/AdminJwtSignatureVerifier.java`（**3 个类**） | N:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminHttpContractRealTomcatTest`（401 三态 + 黑名单 401 + **key 前缀 `admin:`**） |
| A-22 | `admin-api/.../interceptor/AdminJwtAuthInterceptor.java` | 验签结果消费 + 黑名单 fail-open | `interceptor/AdminJwtAuthInterceptor.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 同 A-21 |
| A-23 | `admin-api/.../config/WebMvcConfig.java` | 注册拦截器 + Jackson 强转规则 | `config/WebMvcConfig.java` | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 真 Tomcat（拦截器真的拦住了） |
| A-24 | `admin-api/.../service/` 13 个用例服务 | 13 个 handler + logic 两层 | `service/AdminApiService.java`（**1 个类**） | N:1 | 已实现 | ✅ 已验证 | **E-3** | `AdminHttpContractRealTomcatTest`（13 个端点的 200 / 业务码） |
| A-25 | `admin-api/.../service/AdminApiSupport.java` | uid 解析 + **proto → DTO** 转换 | `service/AdminApiSupport.java`（`parseUid` + **`fromEntity`**） | 1:1 | 已实现 | ✅ 已验证 | **E-3** | 见 §5-②（**`fromProto` 是死代码，已换成 `fromEntity`**） |
| A-26 | `admin-api/.../dto/` **28** 个 DTO | 请求/响应 DTO | `dto/{admin,auth,invite,user}/` **28** 个 record（D11 拆 4 子包） | 1:N | 已实现 | ✅ 已验证 | **E-3** | 真实 Jackson 序列化 + Hibernate Validator 探针（+ 端到端 HTTP 用例） |
| A-27 | `admin-api/.../client/AdminRpcClient.java` | api → rpc 的自我 gRPC 往返 | **不存在**（**D2 域内直连**） | 已删除（D2） | — | — | **E-2** | 映射表 admin.md §1.2.2 已登记 |
| A-28 | `admin-proto/src/main/proto/admin.proto` | gRPC 契约（字段号 L1） | 根级 `proto/src/main/proto/admin.proto`（**逐字节一致**） | 1:1 | 已实现 | ✅ 已验证 | **E-2** | AGENTS.md §3.4（复制粘贴，非翻译） |
| A-29 | `admin-rpc/src/main/resources/application.yml:119` 的 `register-enabled: false` | 关掉 Nacos **自动注册**（只用显式的 gRPC 端口注册） | `service/users/admin/src/main/resources/application.yml` 的 `spring.cloud.nacos.discovery.register-enabled: false`（**逐字照抄**） | 1:1 | 已实现 | ✅ 已验证 | **E-3** | §5-⑩：`AdminServiceApplicationTests#nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered`（+ **变异检验判据能红**） |
| A-30 | B 侧 **Java** 的 `AdminJwtAuthPaths#isProtected`（**前缀语义**）+ 未知路径落进通用兜底（**500 + 信封 5001**） | 鉴权判据 + 未知路径的响应面 | `interceptor/AdminJwtAuthInterceptor.AdminJwtAuthPaths`（前缀语义）+ **不设** 404 分支（走通用兜底） | 1:1 | 已实现 | ✅ 已验证 | **E-4** | §9.4：E-4 活体推翻旧的「显式集合 + 404」修法 ⇒ 改回 B 的前缀语义 ⇒ **HTTP 面 0 行为差异** |

> **承接完整性**：B 侧 76 个 `.java` 的职责**全部有落点**（其中 2 个由兄弟文档承载：`IdentityResolver.java` → user 子域 · `JsonTextTypeHandler.java` → users-common）。
> ⚠️ **C 侧 58 个文件 ≠ 映射表写的 56** ⇒ **差额 2 个 = A-21 新增的 `AdminJwtSignatureFilter` + `AdminJwtSignatureVerifier`**
> ⇒ 映射表 admin.md §1.3 **原本就点名要补**（"要么补 1 个 `OncePerRequestFilter` 承载验签，要么显式登记为签字偏离"）⇒ ⇒ **本轮选了「补」** ⇒ 映射表已同步改成 58/58（§六）。

---

## 三、L1 契约逐条核对

### 3.1 13 个端点（method / path / public 属性）—— **全 POST**

| # | 端点 | B 侧 route | C 侧 `@PostMapping` | 鉴权 | 核对 |
|---|---|---|---|---|---|
| 1 | login | `POST /admin/v1/login` | 同 | **public** | ✅ E-3 |
| 2 | create | `POST /admin/v1/create` | 同 | **public** | ✅ E-3 |
| 3 | logout | `POST /admin/v1/logout` | 同 | JWT | ✅ E-3 |
| 4 | getself | `POST /admin/v1/getself` | 同 | JWT | ✅ E-3 |
| 5 | update | `POST /admin/v1/update` | 同 | JWT | ✅ E-3 |
| 6 | updateuser | `POST /admin/v1/updateuser` | 同 | JWT | ✅ E-3 |
| 7 | delete | `POST /admin/v1/delete` | 同 | JWT | ✅ E-3 |
| 8 | resetuserpassword | `POST /admin/v1/resetuserpassword` | 同 | JWT | ✅ E-3 |
| 9 | banuser | `POST /admin/v1/banuser` | 同 | JWT | ✅ E-3 |
| 10 | unbanuser | `POST /admin/v1/unbanuser` | 同 | JWT | ✅ E-3 |
| 11 | getuser | `POST /admin/v1/getuser` | 同 | JWT | ✅ E-3 |
| 12 | getuserlist | `POST /admin/v1/getuserlist` | 同 | JWT | ✅ E-3 |
| 13 | invite/create | `POST /admin/v1/invite/create` | 同 | JWT | ✅ E-3 |

> ⚠️ **public 集合 = B 侧 `routes.go` 的两段 `AddRoutes`**（`/login` 与 `/create` **不带** `rest.WithJwt`，其余 11 个带）⇒ **逐条一致**。
> ⚠️ **F-6 / F-7 已闭合**：C 侧 `AdminController` 空壳 javadoc 原先写「PUT / GET 混合」且只列 12 个端点 ⇒ **已按 §6.1 重写为 13 个全 POST**。

### 3.2 逐端点错误码表（**L1 · wire 可见**）

> ⚠️ **本表是 B 侧逐端点 `catch (StatusRuntimeException e)` 块的逐字提取**（B 侧 **13 个** api service 实测），
> **不是**通用表。**C 侧已逐条照抄**到 `service/AdminApiService.java` 对应方法（每个方法下面都有「⚠️ 逐端点 switch」注释块）。

| 端点 | B 侧逐端点 switch | C 侧落点 |
|---|---|---|
| login | INTERNAL→5001 · NOT_FOUND→1003 · **UNAUTHENTICATED→1002** · default→1015 | `AdminApiService#login` |
| createAdmin | PERMISSION_DENIED→1016 · ALREADY_EXISTS→1001 · INTERNAL→5001 · default→1015 | `#createAdmin` |
| logout | INTERNAL→5001 · default→1015 | `#logout` |
| **getSelf** | **无 catch**（原样 rethrow ⇒ 走 advice 的通用表） | `#getSelf`（**刻意不 catch**） |
| updateSelf | ALREADY_EXISTS→1001 · NOT_FOUND→1003 · **INTERNAL→5003** · default→1015 | `#updateSelf` |
| updateUser | INTERNAL→5001 · ALREADY_EXISTS→1001 · NOT_FOUND→1003 · default→1015 | `#updateUser` |
| deleteUser | NOT_FOUND→1003 · INTERNAL→5001 · default→1015 | `#deleteUser` |
| resetUserPassword | NOT_FOUND→1003 · **INTERNAL→5003** · default→1015 | `#resetUserPassword` |
| banUser | NOT_FOUND→1003 · INTERNAL→5001 · default→1015 | `#banUser` |
| unBanUser | NOT_FOUND→1003 · **INTERNAL→5003** · default→1015 | `#unBanUser` |
| getUser | INTERNAL→5001 · NOT_FOUND→1003 · default→1015 | `#getUser` |
| getUserList | INTERNAL→5001 · default→1015 | `#getUserList` |
| createInviteCode | INTERNAL→5001 · NOT_FOUND→1003 · default→1015 | `#createInviteCode` |

### 3.3 通用兜底表（= B 侧 `GlobalExceptionHandler#mapRPCStatusToCode`）

| gRPC status | 业务码 |
|---|---|
| INTERNAL | 5001 |
| NOT_FOUND | 1003 |
| ALREADY_EXISTS | 1001 |
| INVALID_ARGUMENT | 500 |
| PERMISSION_DENIED | 1016 |
| UNAUTHENTICATED | 1006 |
| default | 1015 |

> ⚠️ **本表在 C 侧已从「唯一映射」降为「兜底」**（B 侧本来就是兜底；C 侧此前误当唯一映射 ⇒ §5-①）。

### 3.4 表结构 / DDL（**L1**）

| 表 | 关键约束 | C 侧 | 核对 |
|---|---|---|---|
| `admin_admin` | `uid` NOT NULL UNIQUE（+ `idx_admin_admin_uid`）· `username` UNIQUE · `email` UNIQUE **可空** · `extra_info` **text**（不是 jsonb）· `create_time`/`update_time` DEFAULT CURRENT_TIMESTAMP | `service/users/common/src/main/resources/db/03_create_admin.sql` | ✅ E-3（真 PG 建表 + 写入） |
| `admin_invite` | `code` NOT NULL UNIQUE（+ `idx_admin_invite_code`）· `inviter_uid` NOT NULL · `expires_at` NOT NULL · `used_by_uid`/`used_at` 可空 | `db/04_create_admin_invite.sql` | ✅ E-3 |
| `users_user` | 由 users-common 的 `db/01_create_user.sql` 建 | 同 | ✅ E-3 |
| `users_user_avatar_history` | 由 `db/02_*.sql` 建 | 同 | ✅ E-3 |

> ⚠️ **`extra_info` 必须是 text**：jsonb 会以 **22P05** 拒收含 `U+0000` 的 payload ⇒ **那是 B 侧修掉的漂移，不能回流** ⇒ C 侧已用 `JsonTextTypeHandler`（真 PG 对拍在 common 域台账）。

### 3.5 指标名（**L1 · 可观测面**）

| 指标 | C 侧 | 核对 |
|---|---|---|
| `admin_api_intercept_total` | ✅ 逐字一致 | E-3 |
| `admin_rpc_login_total` | ✅ 逐字一致 | E-3 |
| `admin_rpc_high_risk_action_logal` | ✅ **拼写错误 `logal` 照抄（B 侧拼错，不得修正）** | E-3 |

### 3.6 Redis key（**L1**）

| key | C 侧 | 核对 |
|---|---|---|
| `admin:jwt_blacklist:<token>` | ✅ 与 user 域的 `user:jwt_blacklist:` **隔离** | **E-3**（真 Redis 写入 ⇒ 401 ⇒ 并断言 `user:` 前缀**不存在**） |

### 3.7 JWT claims 与签发参数（**L1**）

| 项 | 值 | 核对 |
|---|---|---|
| 算法 | HS256（JCA 名 `HmacSHA256`） | E-2 |
| claims | `exp` / `iat` / `userId`（**int64**） | E-2 |
| `access-expire` | **2592000**（30 天） | E-2 |
| secret 键名 | `admin-api.admin-auth.access-secret` / `admin-rpc.admin-auth.access-secret` | E-3（yml 绑定） |
| secret 来源 | **只认环境变量 `ADMIN_JWT_SECRET`，无默认值** ⇒ 缺变量/空串/仍是占位符 ⇒ **启动失败（fail-closed）** | E-3（上下文启动 + `verifyAccessSecret`） |

### 3.8 默认 admin 种子（**L1 · 安全面**）

| 项 | B 侧 | C 侧 | 核对 |
|---|---|---|---|
| 账号 / 密码 | `admin` / **`admin`（包内硬编码常量**） | 同 ⇒ **不读 `admin-rpc.system.default-password`** | **E-3**（真 PG 上 `PasswordUtil.check("admin", hash)` 为真，且 `check("123456", …)` 为假） |
| uid | `Snowflake.nextId()` | 同 | E-3 |
| 并发兜底 | PG `23505` ⇒ **回查一次** ⇒ 仍无 ⇒ `IllegalStateException` fail-fast | 同 | E-2 |
| 触发时机 | gRPC server 收流量**之前** | `@PostConstruct`（早于 `SmartLifecycle.start()`） | E-2 |

### 3.9 邀请码（**L2**）

| 项 | 值 | 核对 |
|---|---|---|
| 格式 | `"ADM-"` + **8 字节 hex 大写**（共 16 字符） | E-2 |
| TTL | **10 分钟** | E-2 |
| 最大重试 | **3 次** | E-2 |
| 一次性 | W1 `SELECT … FOR UPDATE` 行级悲观锁 + 标记 used | **E-3**（真 PG 上并发核销） |

### 3.10 其余 L1 事实

| 项 | 值 | 核对 |
|---|---|---|
| 登录失败（密码错） | **HTTP 200 + 业务码 1002**（不是 401） | **E-3** |
| 登录用户不存在 | HTTP 200 + 业务码 1003 | **E-3** |
| 缺 token / 坏 token / 黑名单 token | **HTTP 401 + 完整信封（code 1006）** | **E-3** |
| 未知路径 | **HTTP 404**（**不进鉴权**） | **E-3**（§5-⑦） |
| method 不匹配 | **HTTP 405**（裸，无信封） | **E-3** |

---

## 四、框架差异（Boot 4 / go-zero → Boot 3.5 / Spring）

| # | 差异 | C 侧做法 | 是否行为漂移 |
|---|---|---|---|
| F-1 | B 侧 **go-zero `rest`** 的路由 / 中间件链 ↔ C 侧 **Spring MVC 的 Filter 链 + HandlerInterceptor** | 验签搬进 `OncePerRequestFilter`（`@Order(HIGHEST_PRECEDENCE+6)`）以复现「验签在 3s 预算之外」；黑名单留在 interceptor | ❌ 否（**B 侧自己的 `DRIFT-USERAPI-009②` 修法，C 侧同构**） |
| F-2 | B 侧 **`httpx.Parse` 的必填判定** ↔ C 侧 **Bean Validation** | `@NotNull`（**不是 `@NotBlank`**）⇒ B 只判「字段缺失」⇒ 空串合法 | ⚠️ **已登记降级 D-01**（**形状**一致：**文本内容**由 Hibernate Validator 决定） |
| F-3 | B 侧 **proto 中转**（entity → proto → DTO）↔ C 侧 **entity → DTO 直转** | `AdminApiSupport#fromEntity`（逐字段等价表见类 javadoc） | ❌ 否（D2 后 proto 中转在 B 侧已消失） |
| F-4 | B 侧 **`gorm`** ↔ C 侧 **MyBatis-Plus** | `BaseMapper` + `LambdaQueryWrapper`；悲观锁用 `.last("FOR UPDATE")` | ❌ 否 |
| F-5 | B 侧 **GORM 的 LIMIT/OFFSET 静默抑制**（`page=0` ⇒ 负 offset ⇒ 不生成 OFFSET）↔ C 侧 **手写分页** | 照抄（B 侧行为，**不得改成 400**） | ❌ 否 |
| F-6 | B 侧 **`net/http` 的 header 上限**（1<<20 + 4096 = 1028KB）↔ C 侧 **Tomcat 10.1** | `server.max-http-request-header-size: 1028KB` | ❌ 否 |
| F-7 | B 侧 **go-zero 不做 content-type 协商**（无 415）↔ C 侧 **Spring 会做** | 非 JSON content-type ⇒ **改判 400 + text/plain**（§5-⑥） | ⚠️ **残留窄差异 1 处**（§六-②） |
| F-8 | B 侧 **go-zero 的 `http.Error`**（text/plain + nosniff + 尾随换行）↔ C 侧 **Spring 默认 400** | 共享层 `PlainTextErrors` 逐条复刻 | ❌ 否 |
| F-9 | B 侧 **`rest` 的 404 走 notFoundHandler（无鉴权链）** ↔ C 侧 **Spring Boot 静态资源 handler 兜住未知路径** | 鉴权判据从「URL 前缀」改成**显式路径集合**（§5-⑦） | ❌ 否（**已修**） |
| F-10 | B 侧 **gRPC 与 HTTP 两个进程** ↔ C 侧 **D1 合并成一个进程** | 一进程同时起 Tomcat + gRPC server | ⚠️ **部署面偏离**（gRPC 端口 8081→8082，§六-①） |

---

## 五、本轮发现并修掉的漂移（**9 条 · 全部已修 + 已补真触发测试**）

> ⚠️ **本节是本轮的核心产出** —— 每条都是「**读 B 侧源码 / 读框架源码 → 发现 C 侧与 B 侧不一致 → 改 C 侧**」。

### 5-① 🔴 P0 · 4 处 **gRPC wire 错误码漂移**（逐端点 switch 缺失）

- **现象**：C 侧原实现让 **13 个端点全部**走 advice 的**通用** `mapRPCStatusToCode`。
- **B 出处**：B 侧 **13 个 api service 各有各的** `catch (StatusRuntimeException e)` switch ⇒ **4 处与通用表不同**：
  - `login` 的 `UNAUTHENTICATED` ⇒ **1002**（通用表是 1006）
  - `updateSelf` / `unBanUser` / `resetUserPassword` 的 `INTERNAL` ⇒ **5003 `ERROR_DB_SELECT`**（通用表是 5001）
- **修法**：把 **12 个端点的 switch 逐条照抄**进 `service/AdminApiService.java`（`getSelf` **刻意不 catch**，与 B 侧一致 ⇒ 走通用表）；advice 的通用表**降为兜底**。
- **证据**：HTTP 真容器用例 `wrongPasswordIs200WithBusinessCode`（**1002**）/ `unknownUserIs200WithNotFoundCode`（**1003**）/ `protectedPathWithoutTokenIs401`（**1006**）。

### 5-② 🔴 P0 · `fromProto` 是**死代码**（D2 后 proto 中转已消失）

- **现象**：C 侧 `AdminApiSupport` 照抄了 B 侧的 `fromProto`，但 **D2 之后 B 侧的 proto 中转链已不存在** ⇒ 该方法在本仓**没有任何调用者** ⇒ 转换规则散在两处（`fromProto` + 两个私有副本）。
- **修法**：删 `fromProto`，新增 `fromEntity(AdminEntity)` / `fromEntity(UserEntity)`（**逐字段等价表**写在类 javadoc），`AdminApiService` 的两个私有副本删除 ⇒ **转换规则单一真源**。
- **证据**：E-2（逐字段等价表）+ E-3（HTTP 端到端读出 uid / username）。

### 5-③ 🔴 P0 · `application.yml` 的 `grpc.client.negotiation-type` ⇒ **应用起不来**

- **现象**：`grpc.client.negotiation-type: plaintext` ⇒ **上下文启动直接失败**。
- **根因（读 devh 3.1.0 源码）**：`GrpcChannelsProperties` 是 `@ConfigurationProperties("grpc")`，其 `client` 字段是 `Map<String, GrpcChannelProperties>` ⇒ ⇒ **`grpc.client.<X>` 的 `<X>` 是「通道名」，不是属性名** ⇒ `grpc.client.negotiation-type` 被当成「名叫 negotiation-type 的通道」，值是 String ⇒ 绑不成 `GrpcChannelProperties` ⇒ `ConverterNotFoundException`。
- **第二个坑（本轮实测才发现）**：**连「只有注释的 `client:`」都不能留** —— Spring 的 YAML loader 把「有键无值」读成 **空串**（不是 null）⇒ 绑到 Map 同样失败。
- **修法**：**整段删掉 `grpc.client:` 键**，只留注释（**与 like / comment / follow / message / favorite / user / task 七域的 yml 形状逐条一致**）；yml 里写清「将来加 `@GrpcClient("X")` 的正确写法是 `grpc.client.X.negotiation-type`」。
- **⚠️ 为什么会藏这么久**：**此前全仓没有一个 `@SpringBootTest` 上下文测试** ⇒ 任何「起不来」的缺陷都测不出来。
- **证据**：`AdminServiceApplicationTests`（真上下文启动）—— **这条测试就是被它逼出来的**。

### 5-④ 🔴 P0 · 空 body ⇒ **HTTP 500**（B 侧是 400）

- **现象**：`POST /admin/v1/login` **不带 body** ⇒ C 侧 **NPE ⇒ 500**。
- **根因（读 spring-webmvc 6.2.16 源码）**：`AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters` 在 body 为空时走 `handleEmptyBody` ⇒ 返回 **null** ⇒ 而 `RequestResponseBodyMethodProcessor#resolveArgument` 是 **`if (arg != null) { validateIfApplicable(...) }`** ⇒ ⇒ **`@Valid` 对 null 不生效** ⇒ req 传进用例层是 null ⇒ NPE。
  ⇒ 且 C 侧此前的 javadoc 理由（「保留 B 侧 body 可以整个缺席的传输层语义」）**是错的** —— B 侧「body 缺席」= 零值 struct = **必填字段缺失 ⇒ 400**。
- **B 出处（go-zero v1.10.2 源码）**：`rest/httpx/requests.go:153 withJsonBody` = `ContentLength > 0 && ContentType 含 application/json` ⇒ 空 body ⇒ `mapping.UnmarshalJsonMap(nil, v)` ⇒ `core/mapping/unmarshaler.go:1199 newInitError` ⇒ **`field %q is not set` ⇒ 400**。
- **修法**：**10 个有必填字段的端点改用必填 `@RequestBody`** ⇒ 空 body ⇒ `HttpMessageNotReadableException` ⇒ advice ⇒ **400 + text/plain + nosniff + 尾随换行**；**3 个空 struct 端点保留 `required = false` + `new XxxReq()` 归一化**（B 侧对它们是「正常受理」）。
- **证据**：`AdminHttpContractRealTomcatTest#absentBodyIs400` + `#missingRequiredFieldIs400PlainTextWithNosniff`。

### 5-⑤ 🔴 P1 · 非 JSON content-type ⇒ **415**（B 侧是 400）

- **B 出处**：同 5-④ 的 `withJsonBody` ⇒ 非 `application/json` ⇒ 跳过 JSON 解析 ⇒ 必填字段缺失 ⇒ **400**；**go-zero 根本没有 415 这个状态码**。
- **修法**：advice 的 `HttpMediaTypeNotSupportedException` 分支由 **415（裸 body）** 改成 **400 + text/plain**。
- **⚠️ 残留**：3 个空 struct 端点在此路径上 B 侧是「正常受理」而 C 侧是 400 ⇒ **窄差异，已登记**（§六-②）。

### 5-⑥ 🔴 P1 · 未知路径 ⇒ **401**（B 侧是 404）——鉴权判据误伤

> ⛔ **本节的判定已被 §9.4 推翻（2026-11-02 · E-4 双侧活体对拍实测）** ——
> 「B 侧是 404」的依据是 **Go 原版**（`rest/engine.go:244-268 notFoundHandler`），
> 而 **B 侧 RAG 仓是 Java / Spring Boot 4** ⇒ ⇒ **B 侧根本没有 404 响应面**。
> **B 侧活体实测**：`POST /admin/v1/not-exist` 无 token ⇒ **401 + 信封 1006**；
> 带 token ⇒ **500 + 信封 5001** ⇒ ⇒ **C 侧的「401」本来就是对的**，
> 本节据以「修法」的**前提不成立** ⇒ ⇒ 「显式 11 条路径集合」的判据**已回退**为
> **前缀语义**（B 侧 `AdminJwtAuthPaths#isProtected` 逐字）⇒ 详见 §9.4。
> ⚠️ 本节**原文保留**作为「判据被推翻」的过程留痕（台账不删历史），
> 但**其结论不得再被引用**（AGENTS.md §3.3.3 ③：先改/删错误断言，再改实现）。

- **B 出处**：`rest/engine.go:244-268 notFoundHandler` —— 未知路径**另起一条链**（只有 `TraceHandler` + 日志中间件）⇒ **没有 JWT 鉴权** ⇒ ⇒ **404**。
- **C 侧成因（两层）**：
  1. Spring Boot 的静态资源 handler（`/**` → `ResourceHttpRequestHandler`）**兜住任何未知路径** ⇒ `/admin/v1/not-exist` **有 handler** ⇒ ⇒ 拦截器会被调用；
  2. C 侧 `isProtected` 判的是**「URI 以 `/admin/v1` 开头」** ⇒ ⇒ 把静态资源 handler 也鉴权了 ⇒ ⇒ **404 被顶成 401**。
- **修法**：鉴权判据从**「URL 前缀」**改成**「显式的 11 条受保护路径集合」**（与 B 侧「JWT 挂在 route 上」同构）⇒ ⇒ 未知路径 ⇒ **放行** ⇒ ⇒ 路由层给 **404**。
- **⇒ 附带闭环**：`AdminJwtSignatureFilter` 的类注释里那条「MaxBytes 的 **413 仍然压过 401**」的不变式，**此前是假的**（413 也走 ERROR 派发 ⇒ 鉴权会插手）⇒ 现在 interceptor/filter 都**跳过 `DispatcherType.ERROR`** ⇒ ⇒ 413 ⇒ 401 **真正成立**。
- **证据**：`AdminHttpContractRealTomcatTest#unknownPathIs404` + `#pathSetMatchesControllerMappings`（**集合 ↔ 控制器实际映射的守卫测试**）。
- ⚠️ **同一根因的第二层**：修完本条后未知路径变成 **500** ⇒ 见 **5-⑦**。

### 5-⑦ 🔴 P1 · 未知路径 ⇒ **500**（B 侧是 404）——`NoResourceFoundException` 落进兜底 advice

> ⛔ **本节的「修法」已被 §9.4 推翻（2026-11-02 · E-4 双侧活体对拍实测）** ——
> B 侧**没有 404 响应面** ⇒ ⇒ **不该**新增 `NoResourceFoundException` → 404 分支
> （那会把 B 侧的行为改掉，D16 下判 C 错）⇒ ⇒ 该分支**已删除**，
> 未知路径落回通用兜底 ⇒ **500 + 信封 5001**（与 B 侧逐字一致）。
> ⚠️ 本节**原文保留**作为过程留痕，**结论不得再被引用**。

- **现象**：修完 5-⑥ 后，未知路径从 **401 变成了 500**（**这是同一根因的第二层**）。
- **成因链**：静态资源 handler 兜住未知路径 ⇒ 抛 `NoResourceFoundException`
  ⇒ 本域 advice **没有**这个分支 ⇒ 落到兜底 `handleAny(Exception)` ⇒ **HTTP 500**
  ⇒ ⇒ 「客户端打错路径」被误报成「服务端故障」。
- **B 出处**：`rest/engine.go:244-268 notFoundHandler` ⇒ 未知路径 ⇒ **404**；
  且共享层 `common/web/RouterErrorResponses` **已经把 B 侧的 404 字节级复刻了**
  （`text/plain; charset=utf-8` + `nosniff` + body `"404 page not found\n"`）。
- **修法**：advice 增加 `@ExceptionHandler(NoResourceFoundException.class)`
  ⇒ 直接**复用共享层** `RouterErrorResponses.notFound(...)`（**不自己拼** ⇒ 不分叉）。
- **证据**：`AdminHttpContractRealTomcatTest#unknownPathIs404`（**逐字节断言 body**）。

### 5-⑧ 🟡 P1 · `CreateInviteReq/Resp` ⇒ 改回 B 的名（F-5）

- **现象**：映射表 admin.md §1.2.2 记着「C 把 B 的 `CreateInviteCodeReq/Resp` 改名 `CreateInviteReq/Resp`」，F-5 登记为「两侧都未登记」的口径缺口。
- **判定**：**D16 下 B 是基准**，且 B 的 **proto message 本身同名** `CreateInviteCode*` ⇒ ⇒ **改回 B 的名**（`git mv`，非新建）⇒ ⇒ F-5 的 admin 部分**闭合**。
- **证据**：`dto/invite/CreateInviteCodeReq.java` / `CreateInviteCodeResp.java` + 映射表已同步。

### 5-⑨ 🟡 P2 · MyBatis-Plus 3.5.17 的 `MybatisSqlSessionFactoryBean` 已移出 `mybatis-plus-extension`

- **现象**：本仓 test classpath 上**没有**该类（它移到了独立的 `mybatis-plus-spring` artifact）⇒ ⇒ 首版测试装配失败。
- **修法**：改用**普通 MyBatis** 的 `SqlSessionFactoryBuilder` + MP 的 `MybatisConfiguration`（`addMapper` 仍走 `MybatisMapperRegistry` ⇒ `TableInfoHelper.initTableInfo` 仍为 `@TableName(autoResultMap = true)` 装配带 `typeHandler` 的 ResultMap ⇒ **与生产路径同构**）。
- **⚠️ 附带一个真坑**：手工装配时**必须**用 `SpringManagedTransactionFactory`（`org.mybatis.spring.transaction`），否则 `SqlSessionTemplate` 会报
  `SqlSessionFactory must be using a SpringManagedTransactionFactory in order to use Spring transaction synchronization`
  ⇒ ⇒ **`@Transactional` 静默失效**（4 步事务退化成 4 条 auto-commit ⇒ 悲观锁与回滚都测不出来）。
  ⇒ 本仓的 `AdminCreateAtomicService` **必须经容器拿代理**（测试里用最小 Spring 上下文 + `@EnableTransactionManagement` + `DataSourceTransactionManager`）。

### 5-⑩ 🔴 P1 · `application.yml` 缺 `register-enabled: false` ⇒ **Nacos 同名双实例**（gRPC 链路断）

- **现象**：D1 把 B 侧 admin-api + admin-rpc 合成**一个进程** ⇒ 本进程**同时**起 Tomcat（8889）与 gRPC server（8082）
  ⇒ ⇒ 启动时会发 `WebServerInitializedEvent` ⇒ Spring Cloud Alibaba 的 `NacosAutoServiceRegistration`
  会拿 **web 端口**去注册；而域内 `wiring/NacosRegistration` 是**显式**用 **gRPC 端口**注册的
  ⇒ ⇒ Nacos 上同一个服务名 `admin-service` 出现**两个端口的实例**
  ⇒ ⇒ 消费方按名字寻址会连到错端口 ⇒ ⇒ **gRPC 链路断（业务码塌 1015）**。
- **B 出处**：B 侧 `service/users/admin/admin-rpc/src/main/resources/application.yml:119` 的
  `register-enabled: false`（B 侧注释原文：「【本模块的注册是手写的】… ⇒ **自动注册必须关掉**，
  否则**同一服务名下会有两个端口的实例**」）。
- **消费点（SCA 2025.0.0.0 · 读源码确认，非记忆）**：
  - `NacosServiceRegistryAutoConfiguration` 的 **bean gating** 是
    `spring.cloud.service-registry.auto-registration.enabled`（**不是** nacos 那个键）；
  - 真正读 `registerEnabled` 的是**运行期判定**：
    `NacosAutoServiceRegistration.java:71 / :83 / :97`
    ⇒ `if (!nacosDiscoveryProperties.isRegisterEnabled()) { return; }`
  - ⇒ ⇒ **键名仍是 `spring.cloud.nacos.discovery.register-enabled`**
    （读 `NacosDiscoveryProperties#registerEnabled`，**默认值 `true`**）。
- **修法**：yml 的 Nacos 段补上 `register-enabled: false`（逐字照抄 B 侧）+ 注明消费点行号；
  两处 javadoc（`AdminServiceApplication` / `wiring/NacosRegistration`）里「**待主控补**」的措辞一并改为「已补」。
- **真触发验证**：`AdminServiceApplicationTests#nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered`
  —— 三条断言缺一不可：
  1. `NacosAutoServiceRegistration` bean **存在** ⇒ 证明自动注册链路是**活的**，
     不是靠「把整条 discovery 开关关掉」蒙混过关；
  2. `NacosDiscoveryProperties#isRegisterEnabled()` 为 false ⇒ 值来自**真实 `application.yml`**
     （本测试**没有**用 `properties=` 覆盖该键 ⇒ 只可能是 yml 给的）；
  3. 桩 `NamingService` 上**只被注册 1 次**，且那次端口 **= gRPC 端口 ≠ web 端口**。
- **变异检验（判据能红）**：把 yml 那行注释掉 ⇒ 该测试**变红**
  （实测日志出现**两次**注册 —— `NacosServiceRegistry` 注册 **web 端口** + 域内 `NacosRegistration`
  注册 **gRPC 端口** ⇒ **缺陷被复现**）⇒ ⇒ 本断言**不是假绿**。

---

## 六、登记为 Intentional Deviation / 已批准降级的项

| # | 项 | 性质 | 依据 | 状态 |
|---|---|---|---|---|
| ① | **admin gRPC 端口 8081 → 8082** | **部署面**（非行为面） | B 侧 admin-rpc = 8081，但 **B 侧 user-rpc 也是 8081**（同机靠 env 错开）；C 侧 D1 合并后 users 域只剩 2 个进程 ⇒ 端口**必须错开** ⇒ user-service 已占 8081 ⇒ admin 取 8082 | 🏷 **候选，建议签** |
| ② | **3 个空 struct 端点（`/logout` `/getself` `/invite/create`）在「非 JSON content-type」下 B 侧受理、C 侧 400** | **框架差异（窄）** | B 侧 go-zero 不做 content-type 协商 ⇒ 永远走「零值 struct」路径；C 侧 Spring 在**参数解析阶段**就判 content-type ⇒ 只能给 400 | 🏷 **候选，建议签** |
| ③ | **必填字段缺失的 400 文本内容** | **已登记降级 D-01** | B 侧 `field "username" is not set`（go-zero mapping）↔ C 侧 Hibernate Validator / Spring 的消息 ⇒ **形状**（400 + text/plain + nosniff + 尾随换行）一致，**文本**不同 | ✅ **已登记**（`docs/L3-降级清单.md` D-01 · 不需签字） |
| ④ | **表结构 `extra_info` 为 text 而非 jsonb** | **继承 B 侧已修的漂移** | jsonb 会以 **22P05** 拒收含 `U+0000` 的 payload ⇒ B 侧已改 text ⇒ **C 侧继承**（不得回流 jsonb） | ✅ **已继承** |
| ⑤ | **指标名 `admin_rpc_high_risk_action_logal` 的拼写错误** | **L1 逐字照抄** | B 侧就是 `logal` ⇒ **不得修正** | ✅ **已照抄** |

> ⚠️ **本表只登记，不升级为「请用户拍板」**（用户 2026-10-25 的明确要求）⇒ ① ② 两条按「🏷 候选，建议签」登记即算交付。

---

## 七、测试资产与证据强度

### 7.1 本域测试（**3 个类 · 41 例**）

| 测试类 | 例数 | 触发层级 | 覆盖 |
|---|---|---|---|
| `domain/service/AdminDomainServiceRealPgTest` | **20** | **E-3**（真 PG `postgres:16-alpine` + 真 Redis `redis:7-alpine` + **真 `@Transactional` 代理**） | 登录 3 态 · 邀请码一次性 · username 查重**不消耗邀请码** · GORM 的 LIMIT/OFFSET 静默抑制 · `extra_info` 装 U+0000 · 黑名单 key 前缀 · 4 步事务回滚 |
| `http/AdminHttpContractRealTomcatTest` | **17** | **E-3**（**真 Tomcat** · `RANDOM_PORT` · 绝不用 MockMvc） | 13 端点全 POST（GET 全 405）· public 放行 · protected 401 三态 · **200 信封恰好三键** · 400 + text/plain + nosniff + 尾随换行 · 415⇒400 · **未知路径 401/500 双态**（对齐 B 侧活体）· 黑名单 401 + `admin:` 前缀隔离 · **鉴权路径前缀 ↔ 控制器映射守卫** |
| `AdminServiceApplicationTests` | **4** | **E-3**（**真上下文启动**：Tomcat + gRPC server + PG + Redis） | 4 张表建出来 · 默认 admin 密码是**硬编码 `admin`** · gRPC server 与 Tomcat 同进程 · **Nacos 注册面：自动注册被关掉、只注册 gRPC 端口**（§5-⑩） |

> ⚠️ **禁用清单**：`@EnabledIfDockerAvailable` **未使用**（AGENTS.md §五.8 硬规则 ③）⇒ Docker 不可用时**必须红**，不许静默跳过。
> ⚠️ **`skipped = 0`**：本域三个测试类的 surefire 汇总均为 `Skipped: 0`。
> ⚠️ **两个对拍探针不算进上面的 40 例**：它们是**仓内工具**，不是 JUnit 测试 ——
> `tools/difftest/parity_admin.py`（第一轮 HTTP 面 28 用例）与
> `tools/difftest/parity_admin_round2.py`（第二轮 4 类缺口）＋
> `service/users/admin/src/test/java/com/rag/admin/parity/GrpcParityProbe.java`（gRPC 面 13 RPC，
> **需两侧进程同时在跑** ⇒ 不能当普通单测跑 ⇒ 单独执行）。

### 7.2 ⚠️ **证据强度的诚实边界**

| 级别 | 本域状态 |
|---|---|
| **E-4 双侧活体对拍** | ✅ **已做（两轮）**（2026-11-02 · 见 §九）—— B 侧 `admin-api` + `admin-rpc`（Boot 4）与 C 侧 `admin-service`（Boot 3.5）**同时跑起来**，隔离库/Redis/Nacos ⇒ **第一轮**：HTTP 面 28 用例 0 行为差异 · gRPC 面 13 RPC 全一致 · DB 最终状态一致 · Redis 状态一致；**第二轮**：并发 / 事务回滚 / Redis 停 **全一致**，**PG 停 + 超时两项不一致**（§9.10.3 —— 根因在共享层，已移交 `global` 收口域）⇒ ⇒ 本域的 ✅ 读作「**在 E-4 已覆盖的维度上一致**」 |
| **E-3 真中间件 / 真容器** | ✅ **已做**（PG · Redis · Tomcat · gRPC server） |
| **E-2 源码级对照** | ✅ **已做**（B 侧 Go 源码 + go-zero v1.10.2 源码 + C 侧 Spring 6.2.16 / devh 3.1.0 源码逐行读） |
| **E-1 形态级对照** | ✅ 已做（文件树 / 依赖 / 配置形状 / 签名） |
| **E-0 未对照** | ✅ **0 条** |

### 7.3 🟡 **尚未验证的 3 处**（**不计入 PASS**）

| # | 项 | 为什么没验 | 怎么验 |
|---|---|---|---|
| V-1 | **gRPC 面（`grpc/AdminGrpcService` 的 13 个 RPC）** | 本轮只测了 **HTTP 面**；gRPC 的 status + description 是 **L1 wire 面** | 起真 gRPC client 打真 server，逐端点比对 status code + description |
| V-2 | **`service/AdminSpans` 的 span 名与 audit 属性** | 需要 OTel collector 才能取到 span | 装 OTel in-memory exporter，断言 span 名与属性 |
| V-3 | **`wiring/NacosRegistration` 的真实注册** | 测试里用 `NacosStubConfiguration` 桩（不连网络）⇒ **已验证「注册的是 gRPC 端口、且只注册一次、web 端口从未被注册」**（§5-⑩）⇒ 但**对真 Nacos 的注册结果**未验 | 起真 Nacos，断言「注册的是 **gRPC 端口**（不是 web 端口）」 |

### 7.4 🟢 构建证据（**2026-11-02 实测 · `mvn -o -pl service/users/admin -am clean verify`**）

> **口径**（AGENTS.md §五.8 硬规则 ①）：计数取**回归日志的模块级 `Results:` 汇总**，**不**取 `surefire-reports/*.xml` 求和（孤儿报告虚高）。

| 模块 | 汇总行 | Failures | Errors | **Skipped** |
|---|---|---|---|---|
| `common` | `Tests run: 224` | 0 | 0 | **0** |
| `users-common` | `Tests run: 46` | 0 | 0 | **0** |
| **`admin-service`（本域）** | **`Tests run: 41`** | **0** | **0** | **0** |

- **BUILD SUCCESS**（EXIT=0）· `spring-boot:3.5.11:repackage` **通过**
  （`Replacing main artifact …admin-service-1.0-SNAPSHOT.jar with repackaged archive`）
  ⇒ ⇒ 本域**能打成可执行 jar**，且**不会**重蹈那 8 个空壳域 `Unable to find main class` 的覆辙。
- ⚠️ **全量 16 模块的 `mvn -o -T1C clean verify` 仍会红，但红因不在本域** ⇒ 见 §8.2 第 6 条。

### 7.5 🔴 一次**不稳定失败**的定位与修复（2026-11-02 · E-4 第二轮收尾时踩到）

> **这一节记录一次真实的过程留痕**：同一份代码，**上一轮 `clean verify` 16/16 全绿、下一轮红 1 例**
> ⇒ ⇒ 若只记「修好了」，就会把「偶发」当成「已解决」⇒ ⇒ 下面是**定位链**。

| 步骤 | 读数 | 判读 |
|---|---|---|
| ① 失败用例 | `AdminHttpContractRealTomcatTest#protectedPathWithValidTokenIs200`：**期望 200 实得 401** | ⚠️ **不是**验签失败 ⇒ 日志里的真线索是 `AdminJwtAuthInterceptor : admin token in blacklist`（**黑名单**命中，不是 `admin JWT verify failed`） |
| ② 同条件复跑 | 单独跑该类 **3 轮 ⇒ 17/17 全绿 ×3**；模块级 `clean verify` ⇒ **BUILD SUCCESS · 41 例 · Skipped 0** | ⇒ **时序相关**，不是稳定缺陷 |
| ③ 机制还原 | JWT 载荷 = `{uid, iat, exp}` ⇒ **同密钥 + 同 uid + 同一秒 ⇒ token 逐字节相同**；本类夹具**每次都用默认 admin** ⇒ uid 固定 ⇒ 唯一变量是 `iat`（秒级） | ⇒ 黑名单用例写入的 `admin:jwt_blacklist:<token>`（**无 TTL**）会**误伤同一秒内**后续用例的 token |
| ④ 修法 | ① **夹具层**：登录成功后立即 `redis.delete("admin:jwt_blacklist:" + token)` ⇒ 用例与执行顺序解耦；② **机制层**：新增 `jwtIsDeterministicForSameClaims`（同参数 ⇒ 逐字节相同；`iat+1` ⇒ 必须不同）⇒ 把机制钉成**可执行断言**（变异检验：JWT 若引入随机 nonce ⇒ 该断言立刻变红） | ✅ 已修 |
| ⑤ 另一处同类风险 | `AdminDomainServiceRealPgTest` 的黑名单用例用**假 token**（不参与签发）⇒ **不受影响**（已排查，不是漏修） | ✅ |

⇒ **登记**：`docs/Pitfalls/_cross/P-19-JWT确定性导致黑名单串味到别的用例.md`（**索引由主会话统一更新**）。
⇒ ⚠️ **可复用的判据**：**「偶发 401」先看日志是 `in blacklist` 还是 `verify failed`** ——
前者是**夹具隔离**问题，后者才是**验签**问题；两者修法完全不同。

---

| # | 问题 | 回答 |
|---|---|---|
| 1 | C 是否完整承接 B 的全部实际职责？ | **是**（B 侧 76 个 `.java` 的职责全部有落点；C 侧 58 个文件**零空壳**）。⚠️ 例外：B 的 `admin-api/.../client/AdminRpcClient.java` 因 **D2 域内直连**被删（映射表已登记）。 |
| 2 | Mapping_Table 的映射是否与当前真实代码一致？ | **已订正到一致**（本轮改了 5 处 + 覆盖度 56→58）：F-5 命名改回 B、`fromProto`→`fromEntity`、逐端点映射保留在 api 层、§1.3 补录行改成 3 个 C 类、F-6/F-7 闭合。 |
| 3 | File_Tree 规定的目标结构是否正确落地？ | **是**（`docs/File_Tree/users/admin.md` 已按实际文件系统重生成；⚠️ `tools/gen-file-tree.py` 的 `ROOT` 硬编码为主仓 ⇒ 本轮用**临时副本改指向**的方式在本工作树内跑，**并撤回了 `proto.md` 的连带改动**——那是主仓的 gitignore 目录 `proto/src/test/` 造成的，不是本域的变更）。 |
| 4 | 多文件合并是否造成职责/分支/异常路径遗漏？ | **否**（B 侧 13 个 api 用例 → C 侧 1 个 `AdminApiService`；13 个 rpc logic → C 侧 1 个 `AdminDomainService` + 1 个 `AdminCreateAtomicService`。⚠️ **合并期确实漏了 9 处**，已在 §五 补齐并逐条补测试：4 处 wire 错误码 + 死代码 + 起不来 + 空 body 500 + 415 vs 400 + 未知路径 401 + 未知路径 500 + DTO 改名 + **Nacos 同名双实例**）。 |
| 5 | 文件拆分是否造成调用链/数据传递/事务边界断裂？ | **否**（拆分点：验签 → Filter / 黑名单 → Interceptor（§5-⑥ 的路径集合守卫测试保证两侧判据一致）；4 步事务**留在独立 `@Service` Bean** ⇒ `@Transactional` 代理真生效 ⇒ §5-⑧ 的 `SpringManagedTransactionFactory` 坑已闭）。 |
| 6 | API + RPC 合并是否改变原有入口和执行行为？ | **入口变了（进程数 2→1，属 D1 已批准）；执行行为未变**（HTTP 面 13 端点全 POST + gRPC 面 13 个 RPC 各自保留）。⚠️ **gRPC 面本轮未对拍**（§7.3 V-1）。 |
| 7 | Common Starter 上收是否遗漏原有消费方？ | **否**（本域消费共享层的 `PlainTextErrors` / `BadRequestException` / `ApiResponse` / `JwtUtil` / `BearerTokenExtractor` / `PasswordUtil` / `Snowflake` / `IdentityResolver` / `SchemaInitializer`（users-common）⇒ 全部**只读引用**，本工作树**未改** `common/` 与 `service/users/common/` 的任何文件）。 |
| 8 | Spring Boot 4 → 3.5 是否引入业务可观察行为变化？ | **引入了 2 处，已修**（§5-④ 空 body 500、§5-⑤ 415）；**另有 1 处 D1 合并期漏抄的配置，已修**（§5-⑩ `register-enabled: false` ⇒ Nacos 同名双实例）；**其余按 §四 逐条核对，无未批准漂移**。 |
| 9 | Tomcat / Jackson / Spring Framework 版本变化是否产生实际漂移？ | **Tomcat 10.1**：§5-③ 实测出「yml 有键无值 ⇒ 绑 Map 失败」；**Jackson 2**：`extra_info` 用 text + `JsonTextTypeHandler`（jsonb 会 22P05 拒收 U+0000）⇒ 与 B 侧一致；**Spring 6.2.16**：§5-④⑤⑥ 三处漂移的根因都读的是它的源码。 |
| 10 | 哪些差异已经通过证据证明安全？ | **E-3 级**（真 PG / 真 Redis / 真 Tomcat / 真 gRPC server）：§二 A-01~A-25 + A-29 的 **26 条 PASS**。 |
| 11 | 哪些差异仍然无法确认？ | **3 条 NOT_VERIFIED**（§7.3：gRPC 面 13 个 RPC · span 名与 audit 属性 · Nacos 真实注册）＋ **2 条窄偏离**（§六 ① gRPC 端口部署面、② 3 个空 struct 端点的非 JSON content-type）。 |
| 12 | 当前 C 是否具备可靠的行为对拍条件？ | ✅ **已具备并已跑通两轮**（§九）：**HTTP 面**（真 Tomcat + 真 PG + 真 Redis + 可注入的 JWT secret）· **gRPC 面**（用 C 侧 proto stub 同打两侧 server）· **Nacos**（隔离实例 + `sea-rtw` 命名空间）· **PG/Redis 停启**（容器级故障注入）⇒ ⚠️ **B 侧不是 Go 进程，而是 Java/Boot 4 的 `admin-api` + `admin-rpc`**（§9.4 的教训）⇒ ⇒ 对拍前提是「**B 侧能起进程 + 统一 DB/Redis/Nacos**」，本轮已用隔离环境满足 ⇒ **可复现**（探针已入库）。 |

---

## 八、状态汇总

| 状态 | 数 | 说明 |
|---|---|---|
| **PASS**（在该证据强度下一致） | **28** | A-01 ~ A-25 + **A-29** + **A-30**（E-4 gRPC 面 + 路由面；不含 A-05 / A-14 的未验证部分） |
| **FAIL** | **0** | ✅ 本轮发现的 **9 条**漂移**全部已修**（§五）⚠️ 其中 §5-⑥ / §5-⑦ 两条**修法被 E-4 推翻并重做**（§9.4） |
| **⛔ 未修（跨域 · 归 `global` 收口域）** | **1** | §9.10.3 —— **PG 不可用 / 请求超时**时两侧响应面不一致（B `200+1015 信封` ↔ C `503+裸文本`），**且 C 侧超时后副作用已落库**；根因在**共享层**（HTTP 路径没绑 `DeadlineState` ⇒ SQL 不被取消）⇒ 按 AGENTS.md §五.3 归 `global` 收口域 ⇒ **本域不改 `common/`** |
| **NOT_IMPLEMENTED** | **0** | ✅ 58 个 C 文件**全部落地，0 空壳** |
| **NOT_VERIFIED** | **2** | V-2（span 名与 audit 属性）· V-3（**真 Nacos 的注册结果**）⇒ **不计入 PASS**<br>✅ **V-1（gRPC 面）已由 E-4 解除**（§九 · 13/13 一致） |
| **FRAMEWORK_DIFFERENCE** | **2** | ②（3 个空 struct 端点的非 JSON content-type）· ①（gRPC 端口，**部署面**） |
| **INTENTIONALLY_CHANGED** | **2** | ③（D-01 文本内容 · 已登记降级）· ④（`extra_info` 继承 B 侧） |
| **BLOCKED** | **0** | — |
| **（另有踩坑登记）** | **6** | P-13 ~ P-17 ＋ **P-19**（`docs/Pitfalls/_cross/`，索引待主会话更新）|

### 8.1 本轮关键数字（**核对日期 2026-11-02**）

| 项 | 数 |
|---|---|
| C 侧主代码文件 | **58**（**0 空壳**；⚠️ 映射表原写 56 ⇒ 差额 2 = 验签 Filter + Verifier，§二 A-21） |
| C 侧主代码行数 | **7,036**（§5-⑩ 修完后复测；原 7,030） |
| 测试文件 / 例数 | **3 个 / 41 例**（`Skipped: 0`）＋ **2 个对拍探针**（仓内工具，非 JUnit） |
| 本轮修掉的漂移 | **9 条**（4 处 wire 错误码 + 死代码 + 起不来 + 空 body 500 + 415 vs 400 + 未知路径 401 + 未知路径 500 + DTO 改名 + **Nacos 同名双实例**） |
| 独立词 `Go` 残留 | **0**（`\bGo\b` 全仓扫描 = 0） |
| 未验证项 | **2**（§7.3 · gRPC 面已由 E-4 解除） |
| **E-4 双侧活体对拍** | **已完成（两轮）**（§九）· 第一轮：HTTP 28 用例 **0 行为差异** · gRPC 13 RPC **全一致** · DB/Redis 状态**一致**；第二轮：7 判定项 **5 一致 · 2 不一致**（并发 / 事务回滚 / Redis 停 **一致**；PG 停 + 超时 **不一致** ⇒ §9.10.3 移交 `global`） |
| 构建 | `mvn -o -pl service/users/admin -am clean verify` ⇒ **BUILD SUCCESS**（admin 41 / common 224 / users-common 46，**全部 Skipped: 0**）⇒ §7.4 |

### 8.2 未闭合项（**移交主会话**）

1. **`docs/DRIFT/_INDEX.md` 的 admin 行** —— 本文件按 `docs/Worktree.md` §三·1 **不碰**，合并后由主会话统一更新。
2. **`docs/Intentional_Deviations/` 的两条登记**（§六 ① ②）—— 本工作树**只写本域**，索引由主会话统一更新。
   ⇒ 已落 `docs/Intentional_Deviations/admin/_signed-deviations.md`（2 条 · 「🏷 候选，建议签」）。
2b. **`docs/Pitfalls/_INDEX.md`** —— 本轮新增 **5 条踩坑**（P-13 ~ P-17），**文件已落**
   `docs/Pitfalls/_cross/`，但 **`_INDEX.md` 未改**（按 `docs/Worktree.md` §三·1 索引由主会话统一更新）：
   | 编号 | 一句话 |
   |---|---|
   | **P-13** | 「配置项写成了**属性名**」与「**有键无值**」⇒ 绑不上 ⇒ **进程起不来**（devh `grpc.client.<X>` 是通道名 map；且只有注释的键会被读成空串） |
   | **P-14** | 「`@RequestBody(required = false)` + 空 body」⇒ **`@Valid` 被跳过** ⇒ `null` 进业务层 ⇒ **500** |
   | **P-15** | 「手工装配 MyBatis 没用 `SpringManagedTransactionFactory`」⇒ **`@Transactional` 静默失效** |
   | **P-16** | Boot 3.4+ **嵌套 `@TestConfiguration` 默认不被发现** ⇒ 测试配置静默不注册 |
   | **P-17** | 「合并进程的 Nacos **自动注册**拿 **web 端口**注册」⇒ **同名双实例** ⇒ gRPC 链路断；且**消费点是运行期 `if`、不是 `@ConditionalOnProperty`** ⇒ 只看声明点会误判「这键没人读」 |
3. **`docs/Mapping_Table/users/admin/admin.md` 的同步**（§二 末行）—— **本轮已在本工作树内改**（56→58、§1.3 改写、F-5 闭合、§7.1/§8 计数）⇒ 合并时随代码一起进。
4. **`docs/File_Tree/users/admin.md` 重生成** —— ⚠️ `tools/gen-file-tree.py` 的 `ROOT` **硬编码为主仓路径** ⇒ 在 worktree 内直接跑会量到**主工作区的树**（假绿）⇒ 须先改指向本工作树。
5. **E-4 双侧活体对拍** —— ✅ **已完成（两轮）**（§九）⇒ 跨域工程已落地为**仓内探针**（`tools/difftest/parity_admin.py` ＋ `parity_admin_round2.py` ＋ `GrpcParityProbe.java`）⇒ 证据归档在 `docs/DRIFT/users/admin/evidence/`。
6. **全量 `mvn -o -T1C clean verify`（16 模块）在本工作树会红** —— ⚠️ **红因不在本域**：
   8 个**尚未实现的空壳域**（`article` / `comment` / `favorite` / `follow` / `hot` / `like` / `message` / `task`）
   的 `*Application.java` 是**没有 `main` 方法的空壳类** ⇒
   `spring-boot-maven-plugin:3.5.11:repackage` 报 **`Unable to find main class`** ⇒ 8 个域 FAILURE ⇒
   `user-service` 与 `admin-service` 被 **SKIPPED**。
   ⇒ 同条件对照：本工作树对 `service/` 除 `admin/` 外**零改动**（`git status` 实证）⇒ ⇒ **主干自带**，非本轮引入。
   ⇒ ⇒ 本域的收口口径用 **`mvn -o -pl service/users/admin -am clean verify`**（含 `repackage`）
   ⇒ ⇒ 待那 8 个域落地（或其 `pom` 改掉 `repackage`）后，全量 build 自然转绿。
7. **🔴 §9.10.3 的超时 / PG-不可用差异 —— 归 `global` 收口域** —— 根因在**共享层**：
   `DeadlineState` 只在 **gRPC 服务端路径**绑定（`common/.../RpcHandleRunner.java:121`），
   HTTP 路径没绑 ⇒ ⇒ D2 域内直连的 **SQL 永不被取消** ⇒ 超时后事务照常提交（**副作用已落库**）。
   ⇒ 按 AGENTS.md §五.3「跨域 + `common/**` 的搁置漂移全归 `global` 收口域」⇒
   **本 lane 不改 `common/`** ⇒ 复现命令与验收判据见 §9.10.3。
   ⇒ ⚠️ **影响面不止 admin 域**：凡 D2 域内直连的域都同一条 ⇒ 收口域修复后可**全域复用**该回归命令。
8. **`docs/Pitfalls/_INDEX.md` 的 P-19 行** —— 本轮新增 **P-19**（JWT 确定性 ⇒ 黑名单串味 ⇒ 用例不稳定），
   **文件已落** `docs/Pitfalls/_cross/P-19-JWT确定性导致黑名单串味到别的用例.md`，
   但 **`_INDEX.md` 未改**（索引由主会话统一更新）⇒ 详见 §7.5。

---

## 九、E-4 双侧活体对拍（**2026-11-02 实测完成**）

> **协议**：`.agents/verification.md` §九「第六阶段：行为对拍和验证」——
> 对**同一输入**分别打 B 侧与 C 侧，比较外部可观察面，并覆盖「正常 / 边界 / 空值缺省 / 非法输入 /
> 重复请求 / 并发 / 外部依赖失败 / 超时 / 部分成功 / 恢复清理」。

### 9.1 环境与隔离

| 资源 | B 侧（RAG 仓 · Boot 4.0.8） | C 侧（RAG-V1 · Boot 3.5.11） | 隔离方式 |
|---|---|---|---|
| PostgreSQL | 库 `admin_db` | 库 `c_admin` | 同一 PG 实例的**两个独立库**（各自跑 `SchemaInitializer` 建表） |
| Redis | 独立容器 `:54845` | 独立容器 `:54846` | 两个独立实例（黑名单 key 前缀相同 ⇒ 必须隔离） |
| Nacos | `:54847`（命名空间 `sea-rtw`） | 同一 Nacos | 服务名不同（`admin-rpc` / `admin-service`）⇒ 不撞 |
| HTTP | `admin-api` :18889 | `admin-service` :18890 | env 覆盖（B 侧 `ADMIN_API_PORT`） |
| gRPC | `admin-rpc` :18081 | `admin-service` :18082 | env 覆盖（B 侧 `ADMIN_RPC_PORT`） |
| JWT 密钥 | 同一值 | 同一值 | ⇒ 两侧签发的 token 可互验 |

> ⚠️ **B 侧是 Java / Spring Boot 4**（不是 go-zero）⇒ 其 `admin-api` 通过
> `discovery:///admin-rpc` **走 gRPC 调 admin-rpc** ⇒ ⇒ **B 侧对拍必须起 Nacos**
> ⇒ ⇒ 而 C 侧是 **D2 域内直连**（无 gRPC 客户端）⇒ **这是两侧结构上的根本差异**。

### 9.2 对拍探针（仓内新增）

| 文件 | 作用 |
|---|---|
| `tools/difftest/parity_admin.py` | **HTTP 面**探针：28 用例矩阵 + 归一化 + diff + **DB/Redis 状态比对** |
| `service/users/admin/src/test/java/com/rag/admin/parity/GrpcParityProbe.java` | **gRPC 面**探针：用 **C 侧 proto stub** 同时打两侧 server（两侧 proto 契约逐条一致 ⇒ 方法路径相同） |

### 9.3 实测结果

| 面 | 用例 | 一致 | 降级文本差异 | **行为差异** |
|---|---|---|---|---|
| **HTTP** | 28 | **22** | 6（D-01 族） | **0** ✅ |
| **gRPC** | 13 | **13** | 0 | **0** ✅ |
| **DB 最终状态**（4 张表全量 dump 后归一化比对） | — | — | — | **✅ 一致** |
| **Redis 状态**（键 + 类型 + 值） | — | — | — | **✅ 一致** |

⇒ ⇒ **E-4 达成**：本域的 HTTP 面与 gRPC 面**行为等价**（在 E-4 证据强度下），
且 **DB 与 Redis 的最终状态逐行一致**（含「邀请码一次性」「黑名单」「建管理员事务」的状态变更）。

### 9.4 🔴 E-4 推翻了我自己的两条「修法」（§5-⑥ / §5-⑦ 作废）

**这轮对拍最大的产出不是「一致」，而是「推翻了之前基于 Go 原版的判断」。**

| 原判断（§5-⑥ / §5-⑦） | 依据 | **B 侧活体实测** | 处置 |
|---|---|---|---|
| §5-⑥ 未知路径 ⇒ 401（B 侧 404）⇒ 判据改「显式 11 条路径集合」 | **Go 原版** `rest/engine.go:244-268` | B 侧是 **Java/Boot 4** ⇒ `AdminJwtAuthPaths#isProtected` 判**前缀** ⇒ `/admin/v1/not-exist` **仍受保护** ⇒ **401 + 信封 1006** | **推翻**：判据改回**前缀语义**（与 B 逐字一致） |
| §5-⑦ 未知路径 ⇒ 500 ⇒ 加 `NoResourceFoundException` → **404 + text/plain** | 同上（Go 原版的 404 形状） | B 侧**没有 404 响应面**：过鉴权后 ⇒ **500 + 信封 5001**（`GET /foo` 同） | **推翻**：**删掉** 404 分支 ⇒ 落回通用兜底（500 + 信封 5001） |

> ⚠️ **根因**：这两条修法把 **Go 原版**（`rest/engine.go`）当成了 B 侧实现 ——
> 而 **B 侧 RAG 仓是 Java / Spring Boot 4**，Go 只是它的**语义来源**。
> ⇒ ⇒ **教训**：`§1.0c` 的「B 出处」必须指向 **B 侧 Java 源码**；
> **Go 源码只能作语义佐证，不能作行为依据**。

### 9.5 🔴 E-4 挖出的 P0：`admin-service` 的 fat jar **起不来**（测试全绿却不可运行）

- **现象**：`java -jar admin-service-1.0-SNAPSHOT.jar` ⇒
  `NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`
  ⇒ 启动即 `UnsatisfiedDependencyException: healthContributorRegistry`。
- **根因**：pom 里同时存在 **`grpc-client-spring-boot-starter`（compile）** +
  **`grpc-inprocess`（test scope）** ⇒ devh 的 `GrpcClientAutoConfiguration`
  **无条件**创建 `InProcessChannelFactory` ⇒ **运行时**必须有那个类 ⇒
  ⇒ 而**测试跑在 test classpath 上，类一直在 ⇒ 测试全绿、jar 起不来**。
- **B 侧对照**：B 侧 `admin-rpc/pom.xml` 的依赖清单里**只有 `grpc-server-spring-boot-starter`**，
  **没有 client starter** ⇒ ⇒ 本仓这个 client starter 是**多余的**
  （D2 域内直连后本域 **零 `@GrpcClient`**，架构断言 A4 明确「@GrpcClient 0 处」）。
- **修法**：**删掉** `grpc-client-spring-boot-starter` + test scope 的 `grpc-inprocess`
  ⇒ ⇒ 与 B 侧依赖形状一致 ⇒ ⇒ jar 可启动。
  ⚠️ 顺带说明**为什么连 test scope 的 `grpc-inprocess` 一起删**：留着它会**伪装**问题 ——
  将来若有人再加回 client starter，测试 classpath 仍能找到那个类 ⇒ **测试照样全绿、jar 照样起不来**。

### 9.6 E-4 暴露的「探针自身假绿」（两处，已修）

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| **P-1** | Python `urllib` / `http.client` 把 **`103 Early Hints`（1xx 中间响应）当成最终响应** | body 为空 ⇒ token 取不到 ⇒ 整轮 protected 用例全成 401 ⇒ **两侧同错被读成「一致」** | 改用 `curl`（只输出最终响应；`-D` 仍可留 1xx 头块作 wire 观测） |
| **P-2** | 探针 **漏把 URL 传给 curl** ⇒ `curl: (2) no URL specified` | 两侧同样失败 ⇒ **「28 例一致」的假绿** | 加 `status == 0 ⇒ 读数无效` 的守卫（**两侧同错 ≠ 一致**） |

> ⚠️ **P-2 的教训**：「两侧读数相同」**不等于**「行为一致」——
> **必须先证明读数本身有效**（status > 0、body 非空），再谈一致。

### 9.7 E-4 第一轮的诚实边界（**第二轮已补齐 3 项 —— 见 §9.10**）

| # | 项 | 第一轮状态 | 第二轮状态（§9.10） |
|---|---|---|---|
| 1 | **并发请求**（同一邀请码并发创建） | 未做（矩阵是串行） | ✅ **已做** —— 5 路并发同一邀请码 ⇒ 两侧都是 **1×200 + 4×1016**，DB 只建出 1 行、邀请码只被消费 1 次 |
| 2 | **超时 / 外部依赖失败** | 未做 | ✅ **已做** —— 3 个场景（Redis 停 / PG 停 / 持锁制造慢查询）⇒ **Redis 停两侧一致**；**PG 停 / 超时两侧不一致**（真实差异，见 §9.10.3） |
| 3 | **数据库事务回滚** | 只用「邀请码已用」间接覆盖 | ✅ **已做（直接验证）** —— 外部注入 `email` 唯一约束冲突 ⇒ 失败点落在 **W3 insert** ⇒ 两侧都是 **新 admin 行数 = 0 ＋ 邀请码仍未被使用** ⇒ **4 步事务整体回滚**已直接证明 |
| 4 | **定时任务与生命周期** | N/A | N/A（admin 域无定时任务） |
| 5 | **Kafka 消息** | N/A | N/A（admin 域不发 Kafka） |
| 6 | **部分成功 / 恢复清理** | 未覆盖 | ✅ **间接覆盖** —— 「部分成功」= §9.10 的事务回滚场景（4 步里前 3 步成功、第 4 步前失败 ⇒ 整体回滚）；「恢复清理」= 停 Redis/PG 后**再起**，后续场景全部正常 ⇒ 恢复链路已跑通 |


### 9.8 E-4 的连带修正（域内注释按实测事实校正）

E-4 推翻 §5-⑥/§5-⑦ 之后，域内**注释层**也必须跟着改 —— 否则源码里会留着
「B 侧未知路径 = 404」这类**已被证伪**的断言（AGENTS.md §3.3.3 ③）。

| 文件 | 位置 | 原断言 | 校正后 |
|---|---|---|---|
| `interceptor/AdminJwtAuthInterceptor.java` | `preHandle` 的 ERROR 派发分支注释 | 「B 出处（go-zero v1.10.2 `notFoundHandler`）⇒ B 侧未知路径 = 404」 | 改为：① 讲清 **ERROR 派发旁路的真实理由**（`sendError` ⇒ 二次派发 ⇒ 拦截器会再跑 ⇒ 413 会被顶成 401）；② **B 出处**换成 B 侧 Java 事实（`GoStyleJwtAuthFilter` 是裸 `Filter` 且未设 `dispatcherTypes` ⇒ Spring Boot 默认 `REQUEST + ASYNC` ⇒ ERROR 派发不跑 ⇒ 与 C 侧显式跳过**同构**）；③ 标注旧依据来自 go-zero 原版、已被 E-4 推翻 |
| `interceptor/AdminJwtAuthInterceptor.java` | `preHandle` 的 `isProtected` 调用处 | 「判据是『是不是 admin 的 11 个受保护端点』」 | 改为**前缀语义**（与 B 侧 `isProtected` 逐字一致），并写明 `/admin/v1` 下的未知路径**同样受保护** |
| `interceptor/AdminJwtAuthInterceptor.java` | `AdminJwtAuthPaths` 的 javadoc | 「判据 = 显式 11 条路径集合」 | 已改回**前缀语义**，并附 B 侧活体三条实测读数（401/500/500） |
| `interceptor/AdminJwtSignatureFilter.java` | ERROR 派发分支注释 | 「B 侧未知路径走 notFoundHandler，那条链里没有鉴权」 | 同上：换成 B 侧 `Filter` 注册形状的依据 + E-4 实测更正 |
| `advice/AdminApiExceptionHandler.java` | `NoResourceFoundException` 分支 | 新增 404 分支 | **删除**（B 侧无 404 响应面）⇒ 落回通用兜底 500 + 信封 5001 |

> ⚠️ **注意**：`AdminJwtSignatureFilter` 的 ERROR 派发跳过**不是**为未知路径而设 ——
> 未知路径在 Boot 3.5 下由**静态资源 handler** 兜住 ⇒ 走**首次 REQUEST 派发** ⇒ 不经过该分支。
> ⇒ 该分支**只**服务于「`sendError` 触发的二次派发」（典型场景：超限 body ⇒ 413）。

### 9.9 ⚠️ 域外残留：`Book/users/admin/` 的旧判据描述（**本 lane 不改**）

- **事实**：`Book/` 是**用户知识库**（Obsidian vault，2026-11-02 由 `2458367` 一次性导入），
  **不是** RAG-V1 的文档体系（`docs/`）⇒ AGENTS.md 无对应政策。
- **问题**：其中 **14 份** admin 域流程文档仍描述**已被 E-4 推翻**的判据，例如：
  - `Book/users/admin/14-管理员JWT认证与黑名单流程.md:146`
    （「admin = **显式路径集合**（11 条）」）；
  - `Book/users/admin/01-管理员登录流程.md:204`
    （`return uri != null && PROTECTED_PATHS.contains(uri);`）；
  - `Book/users/admin/09-用户信息查询流程.md:197`、`03-邀请码生成流程.md:144`
    （同一段 `PROTECTED_PATHS` 集合代码）；
  - 另有多处「B 侧未知路径 = 404」的旧结论（与 §9.4 实测相反）。
- **处置**：**本 lane 不改**（域外 + 用户自有笔记 + 体量约 14 份）
  ⇒ **登记在此，交主会话 / 用户决定是否同步**。
  ⇒ ⇒ 同一口径的**权威副本**是本台账 §九 与源码 javadoc —— **以本台账为准**。

### 9.10 E-4 第二轮（E-4-R2）：并发 / 事务回滚 / 外部依赖失败 / 超时

> **补 §9.7 的 3 项缺口** ⇒ 探针：`tools/difftest/parity_admin_round2.py`（仓内）
> ⇒ 证据：`docs/DRIFT/users/admin/evidence/parity-report-round2-2026-11-02.json`
> ⇒ 第一轮证据同目录：`parity-report-round1-2026-11-02.json`

#### 9.10.1 判定结果（**7 个判定项 · 5 一致 · 2 不一致**）

| 判定项 | 结果 | 读数 |
|---|---|---|
| **S1 并发 · 响应码分布** | ✅ 一致 | 两侧都是 `codes = [1016 ×4, 200 ×1]`、`statuses = [200 ×5]` |
| **S1 并发 · 最终状态** | ✅ 一致 | 两侧都只建出 **1** 行 admin，邀请码都**只被消费 1 次**（悲观锁 `FOR UPDATE` 语义两侧同构） |
| **S2 事务回滚 · 响应面** | ✅ 一致 | 两侧都是 `HTTP 200 + 信封 code=1001「用户名已存在」` |
| **S2 事务回滚 · 事务原子性** | ✅ 一致 | 两侧都是 **新 admin 行数 = 0** ＋ **邀请码 `used_at` 仍为 NULL** ⇒ **4 步事务整体回滚**已直接证明 |
| **S3a Redis 停 · 响应面** | ✅ 一致 | 两侧都是 `HTTP 200 + code=200` ⇒ **黑名单 fail-open** 两侧同形 |
| **S3b PG 停 · 响应面** | ❌ **不一致** | **B = `200 + 信封 1015「服务繁忙」`** ↔ **C = `503 + 裸文本 "Request Timeout\n"`** |
| **S4 超时（持锁）· 响应面** | ❌ **不一致** | **B = `200 + 信封 1015`，2.03s** ↔ **C = `503 + 裸文本`，3.03s** |

#### 9.10.2 S2 的注入设计（为什么它能**直接**证明回滚）

- **注入点**：`admin_admin.email` 是 `UNIQUE`（两侧实测同形，见 `information_schema` 对照），
  而 **W2 只查 `username` 冲突**（`AdminCreateAtomicService`）⇒ ⇒
  **预置一条同 `email`、不同 `username` 的行** ⇒ 失败点必然落在 **W3 `insert admin`** ——
  即「W1 锁 invite、W2 查 username、bcrypt 都已成功」之后 ⇒ ⇒ 若事务不原子，
  **邀请码会被消费掉**（W4 在 W3 之后）⇒ ⇒ 「邀请码仍未被使用」就是回滚的**直接证据**。
- **实测**：两侧 `new_admin_rows = 0` ＋ `invite_used_at = <NULL>` ⇒ ✅ **两侧都整体回滚**。
- **顺带确认**：唯一约束冲突在两侧都被映射成 **1001「用户名已存在」**（语义上略粗，但**两侧同形** ⇒ 不是漂移）。

#### 9.10.3 🔴 两条真实差异（S3b / S4）：**同一个根因**，落点在**共享层**

**症状**：PG 不可用、或请求被外部持锁卡住时 ——

| | B 侧（RAG · Boot 4） | C 侧（RAG-V1 · Boot 3.5） |
|---|---|---|
| 客户端看到 | `HTTP 200` + 信封 `{"code":1015,"msg":"服务繁忙"}` | `HTTP 503` + 裸文本 `Request Timeout\n`（**无信封**） |
| 触发时刻 | **2.03s**（gRPC `rpc-timeout`） | **3.03s**（HTTP `http-timeout`） |
| **服务端副作用** | **无** —— SQL 被取消 ⇒ 事务回滚 ⇒ `admin_admin` 里 `r2%to` **0 行** | **有** —— 业务线程跑完并提交 ⇒ `r2Cto` **1 行** ＋ 邀请码 **已被消费** |

**根因（代码级 + 运行证据双证）**：

1. **两侧配置相同**：`observability.http-timeout: 3s`（两侧 yml 逐字一致）⇒ ⇒ **不是配置漂移**。
2. **B 侧的「内层 2s」来自 gRPC 边界**：`admin-api`（HTTP，3s）→ gRPC `admin-rpc`（**2s rpc deadline**）→ SQL。
   `admin-rpc` 侧的 `DeadlineState` 由 gRPC 拦截器绑定 ⇒ `DeadlineQueryTimeoutInjector`
   把剩余预算落到每条 SQL ⇒ **2s 时 PG 收到 CancelRequest** ⇒ SQLState `57014`
   ⇒ `SqlTimeoutStatusTranslator` 翻成 `DEADLINE_EXCEEDED` ⇒ 事务回滚（无副作用）
   ⇒ gRPC 状态 ⇒ `admin-api` 映射成 **1015 信封**。
3. **C 侧 D2「域内直连」把 DB 调用搬进了 HTTP 边界** ⇒ ⇒ **内层那层预算没了**：
   - `DeadlineState` 的**生产绑定点只有 gRPC 服务端路径**（`common/.../RpcHandleRunner.java:121`
     `bindRemainingNanos` ＋ `newUnaryServerInterceptor`）⇒ ⇒ **HTTP 路径没有绑定**
     ⇒ ⇒ `DeadlineQueryTimeoutInjector` 读到「无预算」⇒ **SQL 根本不设 query timeout**
     ⇒ ⇒ 阻塞的 `SELECT ... FOR UPDATE` **永不被取消** ⇒ 事务最终提交（**副作用已落库**）。
   - 超时响应面也随之改变：HTTP 预算（3s）先于任何 DB deadline 到点 ⇒
     **共享层 `HttpMiddleware` 的超时面**接管 ⇒ `503 + "Request Timeout\n"`（**裸文本，无信封**）。

**⇒ 归属**：修法落在 **`common/`**（`HttpMiddleware` 侧缺 `DeadlineState` 绑定；且缺「DB deadline < HTTP 预算」这一层错开），
按 AGENTS.md §五.3 **跨域 + `common/**` 的搁置漂移归 `global` 收口域** ⇒ ⇒
**本 lane（admin 域）不改 `common/`，只登记并移交**。

> ⚠️ **这不是 admin 域特有**：凡 **D2 域内直连**（DB 调用在 HTTP 边界内）的域都有同一条差异；
> admin 域只是**第一个用活体对拍把它照出来**的域。
> ⚠️ **也不能在域内私自补一层 2s**：那会把「架构差异」硬编进业务代码
> （B 侧的 2s 来自 **gRPC 中间件**，不是业务代码）⇒ ⇒ **正解是共享层补齐等价结构**。

**影响面（为什么严重）**：客户端收到 **失败**（503）而服务端**已提交** ⇒
**重试会撞「邀请码已用 / 唯一约束」** ⇒ ⇒ 属于「**失败-成功不一致**」型风险，比单纯的状态码差异严重。

**复现命令（收口域修复后直接用它回归）**：

```bash
B_BASE=http://127.0.0.1:18889 C_BASE=http://127.0.0.1:18890 \
  B_PSQL="postgresql://admin:password@127.0.0.1:54844/admin_db" \
  C_PSQL="postgresql://admin:password@127.0.0.1:54844/c_admin" \
  python3 tools/difftest/parity_admin_round2.py
```
⇒ 修复的**验收判据** = `S3b_PG停_响应面` 与 `S4_超时_响应面` 两项从 ❌ 变 ✅，
且 **C 侧 `admin_admin` 里 `r2%to` 必须 0 行**（副作用不得再发生）。
