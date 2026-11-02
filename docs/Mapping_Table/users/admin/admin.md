# Mapping_Table/users/admin/admin.md — admin 子域两方文件名对照表

> **本表目的**：把 **RAG**（B 列，**唯一真理之源**）→ **RAG-V1**（C 列，Spring 惯用目标形态）两棵树的 **users 域 admin 子域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 该子域路径 | 角色 |
> |---|---|---|---|
> | **B（RAG，唯一真理之源）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/admin/` | **唯一行为判定基准**（D16 · 2026-11-02） |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/admin/` | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（更早一版的两方镜像对照）。
> **域级共享层**：[`../_shared.md`](../_shared.md) —— §0 读表须知（完整版）· §1 目录形态决策锁 · §2~§6 跨子域对照 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结。

### 导航

| 文档 | 装什么 | B 侧出处 |
|---|---|---|
| [`../_shared.md`](../_shared.md) | 域级共享层 | `service/users/`（全域） |
| [`../users-common.md`](../users-common.md) | users-common 公共库 | `service/users/users-common/` |
| [`../user/user.md`](../user/user.md) | user 子域 | `service/users/user/` |
| **本文件** | **admin 子域** | `service/users/admin/` |

---

## 〇、读表须知（精简）

### 0.1 三条不可违反的纪律（照抄 [`README.MD`](../../README.MD) §七）

1. ⚠️ **B 列是唯一行为判定基准**（D16 · 2026-11-02 用户定）—— RAG 侧 `users` 域自身已被判定 `NOT EQUIVALENT`（见 `RAG/docs/DRIFT/users/`）
   ⇒ **该差异一律继承，不回溯更早的历史版本**；本表只回答「文件名 / 职责的对应关系」。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**不下**「已对齐 / 未对齐」结论；行为状态看 `RAG/docs/DRIFT/users/` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 `.java` 目前只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

> 📌 **完整版读表须知见 [`../_shared.md` §〇](../_shared.md#〇读表须知)** —— 另含 §0.2 本表的边界 · §0.3 映射类型的 4 种写法 · §0.4 B/C 规模基线。

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
| `users/admin/api/`（`AdminApiApplication`） | `service/users/admin/` → `com.rag.admin`（`AdminServiceApplication.java`） | 入口类合并（D1）|
| `users/admin/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `AdminApiResponseAdvice`）|
| `users/admin/api/auth/`（4 个 JWT 验签类） | `interceptor/` | 直接压平（D2：只留 1 个 `HandlerInterceptor`）|
| `users/admin/api/client/` | `client/` | 直接压平 |
| `users/admin/api/config/` | `config/` | 与 rpc 侧合并 |
| `users/admin/api/controller/` | `controller/` | 直接压平 |
| `users/admin/api/dto/` | `dto/admin/` + `dto/auth/` + `dto/invite/` + `dto/user/` | 直接压平后**按业务对象拆 4 个子包**（D11：`admin` 7 / `auth` 4 / `invite` 2 / `user` 15） |
| `users/admin/api/interceptor/` | `interceptor/` | 直接压平 |
| `users/admin/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `users/admin/api/service/` | `service/` | 与 rpc 侧合并 |
| `users/admin/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `users/admin/rpc/config/` | `config/` | 合并 |
| `users/admin/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `users/admin/rpc/exception/` | `exception/` | 直接压平 |
| `users/admin/rpc/mapper/` | `mapper/` | 直接压平 |
| `users/admin/rpc/metrics/` | `metrics/` | 合并 |
| `users/admin/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| **—（B 侧 admin 无 `service/impl/`）** | `grpc/`（`AdminGrpcService`） | RAG 侧 admin 的 gRPC 实现直接在 `rpc/service/`；C 侧独立成 `grpc/` 包 |
| **—（admin 无 contract 模块）** | **—（admin 侧无 `contract/` 包）** | ⚠️ admin 若要引用错误契约，取公共库 `service/users/common/` 那份（F-2）|
| `users/admin-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.admin`）| **集中化**（D7）|

## 1. 包结构对照

### 1.2 admin 子域（B 侧 `service/users/admin/`）

#### 1.2.1 admin-rpc 侧（B 侧 `bootstrap + config + domain + exception + mapper + metrics + service`，20 个 `.java`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `admin-rpc/.../AdminRpcApplication.java` + `admin-rpc/.../bootstrap/NacosRegistration.java` + `admin-rpc/.../bootstrap/SnowflakeBootstrap.java` | `service/users/admin/src/main/java/com/rag/admin/AdminServiceApplication.java` + `service/users/admin/src/main/java/com/rag/admin/wiring/NacosRegistration.java` + `service/users/admin/src/main/java/com/rag/admin/wiring/SnowflakeBootstrap.java` | 1:N | 进程装配（入口 → 配置 → 服务 → 启动）在 B 侧占 3 个文件，C 侧拆成「入口 + 注册 + ID 生成器初始化」三个 Spring Bean；**C 的入口与 admin-api 共用**（D1） |
| （无独立文件） | （无独立文件） | 1:1 | B 侧由 codegen 生成的 gRPC service 接口（无独立手写文件）⇒ `@GrpcService` + 生成的 `AdminServiceGrpc.AdminServiceImplBase` 承接 |
| `admin-rpc/.../config/AdminRpcProperties.java` | `service/users/admin/src/main/java/com/rag/admin/config/AdminRpcProperties.java` | 1:1 |  |
| `admin-rpc/.../bootstrap/AdminSeederBootstrap.java`（`ensureDefaultAdmin` 部分） | `service/users/admin/src/main/java/com/rag/admin/wiring/AdminSeederBootstrap.java` | 1:1 | B 侧 `ServiceContext` 整体被 Spring DI 吸收（**B/C 均不生成 `ServiceContext.java`**），**但 `ensureDefaultAdmin`（默认 `admin`/`admin` 种子 + 唯一约束兜底 + fail-fast）是业务方法 ⇒ 必须独立承载** ⇒ B/C 同构落在 `AdminSeederBootstrap` |
| `admin-rpc/.../service/AdminServiceGrpcImpl.java` | `service/users/admin/src/main/java/com/rag/admin/grpc/AdminGrpcService.java` | 1:1 | 13 个 RPC 方法分发 |
| `admin-rpc/.../service/AdminDomainService.java` | `service/users/admin/src/main/java/com/rag/admin/domain/service/AdminDomainService.java` | N:1 | 13 个 logic 合 1（与下 12 行同归一个 C 文件） |
| 同上 + `admin-rpc/.../service/AdminCreateAtomicService.java` | 同上 + `service/users/admin/src/main/java/com/rag/admin/domain/service/AdminCreateAtomicService.java` | N:1 | 4 步事务（建 admin + 消耗邀请码 + 更新 `used_by_uid`/`used_at` + 唯一约束兜底）必须落在**独立 `@Service` Bean**（self-invocation 风险）；⚠️ 行级悲观锁（`SELECT ... FOR UPDATE`）也在这一层编排 |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| 同上 | 同上 | N:1 |  |
| `admin-rpc/.../service/AdminHelpers.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminHelpers.java` | 1:1 | admin-rpc 的用例内 helper |
| `admin-rpc/.../mapper/AdminMapper.java` + `AdminInviteMapper.java` + `UserMapper.java` | `service/users/admin/src/main/java/com/rag/admin/mapper/AdminMapper.java` + `AdminInviteMapper.java` + `UserMapper.java` | 1:N | `AdminModel` 的查询/更新方法 → 3 个 Mapper（按实体拆） |
| `admin-rpc/.../domain/AdminEntity.java` + `AdminInviteEntity.java` + `UserEntity.java` | `service/users/admin/src/main/java/com/rag/admin/domain/entity/AdminEntity.java` + `AdminInviteEntity.java` + `UserEntity.java` | 1:N | B 侧的 3 个 `@TableName` 实体 → C 侧 3 个实体 |
| `admin-rpc/.../bootstrap/AdminSchemaBootstrap.java` + `users-common/.../ddl/SchemaInitializer.java` | `service/users/admin/src/main/java/com/rag/admin/wiring/AdminSchemaBootstrap.java` | 1:1 | B 侧幂等 DDL 重放 → C 同构；⚠️ 与 user-rpc 同一个缺口（C 侧 `db/migration/` 空、`common/` 无 `SchemaInitializer`） |
| `admin-rpc/.../mapper/AdminMapper.java`（`IsUniqueViolation` 判定） | `service/users/admin/src/main/java/com/rag/admin/exception/AdminExceptions.java` | 1:1 | PG `23505` 唯一约束判定（B 侧是 `AdminExceptions.isUniqueViolation(Throwable)` **静态方法**，被 `AdminSeederBootstrap` / `AdminDomainService` / `AdminCreateAtomicService` 三处调用；**不在 `AdminMapper` 内** ⇒ C 侧照此归位到 `exception/AdminExceptions`） |
| `admin-rpc/.../exception/AdminExceptions.java` | `service/users/admin/src/main/java/com/rag/admin/exception/AdminExceptions.java` | 1:1 | 哨兵错误（`ErrorNotFound`）→ 业务异常聚合类（**同时承载 `isUniqueViolation`**） |
| `admin-rpc/.../metrics/AdminRpcMetrics.java` | `service/users/admin/src/main/java/com/rag/admin/metrics/AdminRpcMetrics.java` | 1:1 | 指标完整名 L1：`admin_rpc_login_total`、`admin_rpc_high_risk_action_logal`（**拼写错误照抄，不得修正**） |
| `admin-proto/src/main/proto/admin.proto`（生成物） | `proto/src/main/proto/admin.proto`（生成物，pb 包名 `com.rag.proto.admin`） | 1:1 | proto 生成物；字段号 L1 |
| 同上 | 同上 | 1:1 | gRPC stub 生成物 |

> **补充行（承接 B 侧 logic 内联手写的 span）**

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `admin-rpc/.../service/AdminSpans.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminSpans.java` | N:1 | B 侧的 8 个 logic 在方法开头**内联手写** `tracer.Start(ctx, "Action-Admin-…")` + `audit.*` 属性 ⇒ B/C 抽成独立 `AdminSpans`（span 名与属性是 L2，不得改） |

#### 1.2.2 admin-api 侧（B 侧 `advice + auth + config + controller + dto + interceptor + metrics + service`，56 个 `.java`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `admin-api/.../AdminApiApplication.java` | `service/users/admin/src/main/java/com/rag/admin/AdminServiceApplication.java`（与 admin-rpc **共用**） | N:1 | D1 合并 |
| `admin-api/.../config/AdminApiProperties.java` | `service/users/admin/src/main/java/com/rag/admin/config/AdminApiProperties.java` | 1:1 |  |
| `admin-api/.../controller/AdminController.java` + `admin-api/.../config/WebMvcConfig.java` | `service/users/admin/src/main/java/com/rag/admin/controller/AdminController.java` + `service/users/admin/src/main/java/com/rag/admin/config/WebMvcConfig.java` | 1:N | 同 user-api；路由前缀 B `/admin/v1` ⇒ C `/admin/v1`（**一致**）；⚠️ C 的 javadoc 端点清单与 B 侧不一致（F-6 / F-7） |
| `admin-api/.../service/AdminCreateService.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminApiService.java` | N:1 | handler + logic 合并；C 侧 13 个用例合 1 个 `AdminApiService` |
| `admin-api/.../service/AdminLoginService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminLogoutService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminGetSelfService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminGetUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminListUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminUpdateSelfService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminUpdateUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminDeleteUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminResetUserPasswordService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminBanUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminUnBanUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminCreateInviteCodeService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminCreateService.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminApiService.java` | N:1 | B 侧的「handler → logic」两层已合并为 1 个 Service，C 侧再合并为 1 个 `AdminApiService` |
| `admin-api/.../service/AdminLoginService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminLogoutService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminGetSelfService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminGetUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminListUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminUpdateSelfService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminUpdateUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminDeleteUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminResetUserPasswordService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminBanUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminUnBanUserService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../service/AdminCreateInviteCodeService.java` | 同上 | N:1 | 同上 |
| `admin-api/.../dto/`：**`AdminInfo.java`**、**`BanUserReq.java`**、**`BanUserResp.java`**、**`CreateAdminReq.java`**、**`CreateAdminResp.java`**、**`CreateInviteCodeReq.java`**、**`CreateInviteCodeResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetSelfReq.java`**、**`GetSelfResp.java`**、**`GetUserListReq.java`**、**`GetUserListResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`ResetUserPasswordReq.java`**、**`ResetUserPasswordResp.java`**、**`UnBanUserReq.java`**、**`UnBanUserResp.java`**、**`UpdateSelfReq.java`**、**`UpdateSelfResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UserInfo.java`**（**28** 个） | `service/users/admin/src/main/java/com/rag/admin/dto`：**`AdminInfo.java`**、**`BanUserReq.java`**、**`BanUserResp.java`**、**`CreateAdminReq.java`**、**`CreateAdminResp.java`**、**`CreateInviteReq.java`**、**`CreateInviteResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetSelfReq.java`**、**`GetSelfResp.java`**、**`GetUserListReq.java`**、**`GetUserListResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`ResetUserPasswordReq.java`**、**`ResetUserPasswordResp.java`**、**`UnBanUserReq.java`**、**`UnBanUserResp.java`**、**`UpdateSelfReq.java`**、**`UpdateSelfResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UserInfo.java`**（**28** 个） | 1:N | B 侧的 **28** 个 DTO → 28 个 immutable record（D5）；⚠️ **C↔B 命名分叉**：C 把 B 的 `CreateInviteCodeReq/Resp` 改名 `CreateInviteReq/Resp`（B 的 `dto/` 侧与 proto 侧同名 `CreateInviteCode*`）⇒ 见 §7.3 F-5 |
| `admin-api/.../interceptor/AdminJwtAuthInterceptor.java` | `service/users/admin/src/main/java/com/rag/admin/interceptor/AdminJwtAuthInterceptor.java` | 1:1 | blacklist key 前缀与 user 侧区分（`admin:jwt_blacklist:` vs `user:jwt_blacklist:`） |
| `admin-api/.../metrics/AdminApiMetrics.java` | `service/users/admin/src/main/java/com/rag/admin/metrics/AdminApiMetrics.java` | 1:1 | 指标完整名 L1：`admin_api_intercept_total` |
| `admin-api/.../client/AdminRpcClient.java` + `admin-api/.../config/AdminApiProperties.java` + `admin-api/.../config/WebMvcConfig.java` | `service/users/admin/src/main/java/com/rag/admin/config/AdminApiProperties.java` + `service/users/admin/src/main/java/com/rag/admin/config/WebMvcConfig.java` | 1:N | D2 域内直连 ⇒ C 侧无 `api/client/` |

> **补充行（B 引入、C 保留的用户侧/api 侧横切件）**

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `service/common/.../redis/GoStyleRedisClientAutoConfiguration.java`（⚠️ 原 `user-api/.../config/RedisDisconnectedBehaviorConfig` 已被 RAG **删除并上移**进共享层，该类名在 RAG 侧已不存在） | `common/src/main/java/com/rag/common/redis/ClientAutoConfiguration.java`（**断连行为已收口进共享层**；域内副本 `service/users/user/src/main/java/com/rag/user/config/RedisDisconnectedBehaviorConfig.java` **已于 2026-11-02 · users-user lane 按 B 侧删除** ⇒ 域内无此文件） | 1:1 | B 为修自身漂移（**`DRIFT-USERAPI-014`**）引入的 **Lettuce 断连行为**定制（**旧行为 fail-open ⇒ B 侧改为 fail-fast 503**）；C 原样保留 ⇒ **该行为差异在 C 侧仍然存在**。⚠️ B 已把域内 5 份副本**全删**并收进共享层 ⇒ **C 侧也要走共享层**（落点见 `common.md` §1.12） |
| `user-api/.../advice/GlobalResponseAdvice.java` + `admin-api/.../advice/GlobalResponseAdvice.java` | `service/users/user/src/main/java/com/rag/user/advice/UserApiResponseAdvice.java` + `service/users/admin/src/main/java/com/rag/admin/advice/AdminApiResponseAdvice.java` | 1:N | `@RestControllerAdvice` + `ResponseBodyAdvice` 把任意返回值包成 `{code,msg,data}`（统一信封是 L1）⇒ B/C 同构，仅类名域内化 |
| `user-api/.../advice/GlobalExceptionHandler.java` + `admin-api/.../advice/GlobalExceptionHandler.java` | `service/users/user/src/main/java/com/rag/user/advice/UserApiExceptionHandler.java` + `service/users/admin/src/main/java/com/rag/admin/advice/AdminApiExceptionHandler.java` | 1:N | `@RestControllerAdvice` 异常出口；**D2 合并后不再需要「gRPC code → 业务码」这一层映射**（业务层直接抛 `BusinessException`） |
| `admin-api/.../service/AdminApiSupport.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminApiSupport.java` | 1:1 | uid 解析（`parseUid`）+ proto→DTO 转换（`AdminInfo` / `UserInfo`）的聚合点；对应 user 侧的 `UserApiSupport` ⇒ B/C 同构 |
| `service/common/.../persistence/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 1:1 | B 侧的 **MP 自动配置排序桥**（Boot 4 挪了 `DataSourceAutoConfiguration` 的包 ⇒ MP 排序引用悬空 ⇒ `SqlSessionFactory` 不产出）；B 侧已收进共享层 `service/common/`（**1 份**），C 侧同样 1 份 ⇒ **归属见 `common.md`** |

### 1.3 🔴 RAG 侧新增类补录（1 条 · 覆盖 4 个 `.java`）

> ⚠️ **本节是 2026-11-01 的增量**：RAG 在首版映射表之后新增了 `admin-api/…/auth/` 包（4 类）
> ⇒ 首版 §1.2.2 的 **B 列漏了这 4 个 `.java`** ⇒ 本节逐个落到 C 侧；
> 补齐后 `tools/check-mapping-coverage.py` 的 **B 列双向对账归零**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `admin-api/…/auth/AdminJwtAuthPaths.java` ＋ `admin-api/…/auth/AdminJwtSignatureVerifier.java` ＋ `admin-api/…/auth/AdminJwtVerificationResult.java` ＋ `admin-api/…/auth/GoStyleJwtAuthFilter.java` | `service/users/admin/src/main/java/com/rag/admin/interceptor/AdminJwtAuthInterceptor.java` | N:1 | ⚠️ RAG 把 JWT **验签**拆成两段（`DRIFT-USERAPI-009②` 的修法）：验签在 **Servlet Filter**（3s 预算**之外**）＋ 黑名单/响应在 **HandlerInterceptor**（预算**之内**）⇒ C 侧**只有 1 个 `HandlerInterceptor`** ⇒ 🔴 **C 侧缺口**：要么补 1 个 `OncePerRequestFilter` 承载验签，要么显式登记为签字偏离。⚠️ **受保护路由表 ＋ public 前缀集合是 L1**；⚠️ admin 的 secret 与 user 域**不同** ⇒ **不可复用 user 域拦截器** |

---

## 2. 持久层差异（本子域相关行）

| 维度 | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|
| 行级悲观锁 | `AdminCreateAtomicService.createAdminAtomic` 内编排 `AdminInviteMapper` 的 `LambdaQueryWrapper.last("FOR UPDATE")` | 同 B（`domain/service/AdminCreateAtomicService` + `mapper/AdminInviteMapper`） | 邀请码核销路径 |
| 唯一约束判定 | `AdminExceptions.isUniqueViolation(Throwable)`（PG SQLState `23505`） | 同 B（`exception/AdminExceptions` 的静态方法） | 默认 admin 种子创建的幂等兜底；⚠️ **B 侧不在 `AdminMapper` 内** |

> ⚠️ 原 §2 表的 10 行中，**本子域专属的是上面 2 行**（行级悲观锁 / 唯一约束判定），其余 8 行为跨子域行
> ⇒ **本子域的其余相关行见 [`../_shared.md` §2 持久层差异](../_shared.md#2-持久层差异)**（含该节的决策锁「换持久层命名空间 6 处同时改」）。

---

## 3. HTTP / DI 差异（本子域相关行）

> ⚠️ 原 §3 表的 **17 行全部是跨子域行**（每行同时写 user 与 admin）
> ⇒ **本子域相关行见 [`../_shared.md` §3 HTTP / DI 差异](../_shared.md#3-http--di-差异)**（含该节的决策锁「换进程拓扑 7 处同时改」+「换错误表达 4 处同时改」）。

---

## 4. 公共模块对照（与本子域相关行）

> ⚠️ 原 §4 表的 15 行中，**admin 子域专属行为 0 行** ——
> `users-common` 私有件的 7 行见 [`../users-common.md` §4](../users-common.md#4-公共模块对照与本库相关行)，其余 8 行为全局归属 / 跨子域行（其中「jsonb / text TypeHandler」一行点名本子域的 `admin-rpc` 侧 2 份副本）。
> ⇒ **本子域相关行见 [`../_shared.md` §4 公共模块对照](../_shared.md#4-公共模块对照)**。

---

## 5. 决策锁（与本子域相关）

本子域涉及的决策：**D1**（api 与 rpc 合并为一个 app ⇒ `service/users/admin/`）· **D2**（域内调用直连，不再自我 gRPC 往返）· **D5**（DTO 改 immutable record + Bean Validation）· **P4**（`rpc.*` 包是否改名，待拍板）。

> ⚠️ **决策的完整内容、落点与代价见 [`../_shared.md` §5 决策锁（汇总）](../_shared.md#5-决策锁汇总)** —— 本节只列本子域相关的决策编号，**不复制表格行**（避免重复计数）。
> 本子域的**目录形态**决策锁（换目录形态 8 处同时改）见 [`../_shared.md` §1](../_shared.md#1-包结构对照域级)。
> ⚠️ **本子域另有一条待决缺口**：F-2（admin 侧**无 `contract/` 包** —— D7 删除了 `admin-contract` 模块；admin 若要引用错误契约，应取公共库 `service/users/common/src/main/java/com/rag/users/common/` 那份）⇒ 见 [`../_shared.md` §7.3](../_shared.md#73-b--c-之间职责对不上的地方⚠-需处理)。

---

## 6. 跨模块全局约定（与本子域相关行）

> **本节的值全部来自 B 列（RAG 侧）源码**，不来自 C 列，也不来自 RAG-V1 的文档。

| 项 | 值 | B 侧出处（`file:line` 级可 grep） |
|---|---|---|
| **列约束（admins / admin_invites）** | `admin_admin`：`uid` NOT NULL UNIQUE（另有 `idx_admin_admin_uid` 索引）· `username` UNIQUE · `email` UNIQUE 但**可空**（保留指针语义）· `extra_info` TEXT · `create_time` / `update_time` DEFAULT CURRENT_TIMESTAMP；`admin_invite`：`code` NOT NULL UNIQUE（另有 `idx_admin_invite_code` 索引）· `inviter_uid` NOT NULL · `expires_at` NOT NULL · `used_by_uid` / `used_at` 可空 | `service/users/users-common/src/main/resources/db/03_create_admin.sql` ＋ `04_create_admin_invite.sql` |
| **默认管理员种子** | `admin` / `admin`（`DEFAULT_ADMIN_USERNAME` / `DEFAULT_ADMIN_PASSWORD`，**不从系统默认密码配置读取**），uid 由 `SnowflakeBean.nextId()` 生成，插入失败遇 PG `23505` 时**回查一次**确认（多实例并发启动兜底），回查仍找不到则抛 `IllegalStateException` fail-fast | `service/users/admin/admin-rpc/.../bootstrap/AdminSeederBootstrap.java`（`ensureDefaultAdmin`） |

> ⚠️ **本表已按子域拆分** —— 其余 16 行为跨子域行 ⇒ 见 [`../_shared.md` §6 跨模块全局约定](../_shared.md#6-跨模块全局约定值逐条核对-b-侧源码)。

---

## 7. 覆盖度自查（本子域）

### 7.1 C 列覆盖率 —— **56 / 56 = 100%**（admin 子域）

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users/admin" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| `proto/src/main/proto/admin.proto`（**无 `.java`**） | **0** | 本域无错误码空间 ⇒ 无 `contract` 包 | ✅ 0/0（**但见 F-2**） |
| `service/users/admin/src/main/java/com/rag/admin/` | 56 | §1.2.1（admin-rpc 侧）+ §1.2.2（admin-api 侧）+ §1.2.2 补充行（`AdminApiResponseAdvice` / `AdminApiExceptionHandler` / `AdminApiSupport`） | ✅ 56/56 |
| **合计** | **56** | — | ✅ **56/56** |

> ⚠️ **F-2 落点**：[`../_shared.md` §7.3](../_shared.md#73-b--c-之间职责对不上的地方⚠-需处理)。

**B 列（RAG 侧 `service/users/admin/`）覆盖率 —— 76 / 76 = 100%**（抄写路线图 §1.0c 的 B↔C 依据）

> B 侧 `service/users/admin/` 实测 **76** 个 `.java`（`admin-api` 56 · `admin-rpc` 20）⇒ 本表 B 列覆盖 **74/76**，
> 另 **2 个** 由兄弟文档承载：`IdentityResolver.java`（见 [`../user/user.md`](../user/user.md) §1.1.3）·
> `JsonTextTypeHandler.java`（见 [`../users-common.md`](../users-common.md) §1.3）⇒ **users 域 B 列合计 145/145，零遗漏**。
> 复算命令（`RAG-V1` 仓根）：
> ```bash
> python3 tools/check-mapping-coverage.py users   # ⇒ users 域 B 列 145/145
> ```

### 7.2 映射类型分布（admin 子域）

> 统计口径：§1 全部映射表的**类型列**（`B | C | 映射类型 | 备注` = 4 列 ⇒ 类型是第 3 格）；不含 §2/§3/§4 的差异对照表。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **17** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」） |
| `1:N` | **8** | B 的单文件拆成多个 C 文件 |
| `N:1` | **42** | B 的多文件合进 1 个 C 文件（**D1/D2 的主因**：admin-api 13+13、admin-rpc 13 个 logic → 各 1 个 Service） |
| `C 新增` | **0** | 本子域每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本子域是**重组**而非新增能力 |
| **合计** | **67** | — |

> 统计命令（可复跑）：
> ```bash
> awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' docs/Mapping_Table/users/admin/admin.md \
>   | awk -F'|' '/^\|/{t=$4; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 17 1:1 / 8 1:N / 42 N:1
> ```
> ⚠️ **旧自报数（`1:1` 17 / `1:N` 8 / `N:1` 41 / 合计 66）与表内实际不符** —— 本次按上表实测值订正为 **42 N:1 / 合计 67**；删列不改行数，变的只是统计口径（原命令取第 5 格，删除旧基准列后类型列已前移到第 4 格）。

---

## 8. 一句话总结（admin 子域）

**B 列 RAG**（`service/users/admin/`，76 个 `.java`）= **唯一真理之源**（api/rpc 分离的部署形态）；
**C 列 RAG-V1**（`service/users/admin/`，56 个 `.java`）= Spring 惯用目标形态（一域一 app）。

⇒ **admin 子域 C 列 56/56 覆盖、B 列 76/76 覆盖（2 个由兄弟文档承载）、映射类型 1:1 ×17 / 1:N ×8 / N:1 ×42 / C 新增 ×0（共 67 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 B 列（RAG 侧）源码为准，状态看 `RAG/docs/DRIFT/users/`。**
