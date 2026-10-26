# Mapping_Table/users/admin/admin.md — admin 子域三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标形态）三棵树的 **users 域 admin 子域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 该子域路径 | 角色 |
> |---|---|---|---|
| **A（Go 真理之源）** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | `service/user/admin/`（**单数**） | 冻结真源，唯一行为判定基准 |
> | **B（RAG，Go 形态版）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/admin/`（**复数**） | Go 部署拓扑（api/rpc 分离）的 Spring 复刻 |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/admin/`（**复数**） | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（那份是 Sea-Java 镜像 ↔ RAG；本份是 Go ↔ RAG ↔ RAG-V1）。
> **域级共享层**：[`../_shared.md`](../_shared.md) —— §0 读表须知（完整版）· §1 目录形态决策锁 · §2~§6 跨子域对照 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结。

### 导航

| 文档 | 装什么 | Go 出处 |
|---|---|---|
| [`../_shared.md`](../_shared.md) | 域级共享层 | `service/user/`（全域） |
| [`../users-common.md`](../users-common.md) | users-common 公共库 | Go `service/user/common/` |
| [`../user/user.md`](../user/user.md) | user 子域 | Go `service/user/user/` |
| **本文件** | **admin 子域** | Go `service/user/admin/` |

---

## 〇、读表须知（精简）

### 0.1 三条不可违反的纪律（照抄 [`README.MD`](../../README.MD) §七）

1. ⚠️ **B 列不是 Go 的替身** —— RAG 在 `users` 域已被判定 `NOT EQUIVALENT`（见 `RAG/docs/DRIFT/users/`）。
   **一切行为判断以 A 列 Go 源码为准**；本表只回答「文件名 / 职责的对应关系」。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**不下**「已对齐 / 未对齐」结论；行为状态看 `RAG/docs/DRIFT/users/` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 `.java` 目前只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

> 📌 **完整版读表须知见 [`../_shared.md` §〇](../_shared.md#〇读表须知)** —— 另含 §0.2 本表的边界 · §0.3 映射类型的 4 种写法 · §0.4 A/B/C 规模基线。

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
| `users/admin/api/client/` | `client/` | 直接压平 |
| `users/admin/api/config/` | `config/` | 与 rpc 侧合并 |
| `users/admin/api/controller/` | `controller/` | 直接压平 |
| `users/admin/api/dto/` | `dto/` | 直接压平（26 个同名类全落此）|
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

### 1.2 admin 子域（Go `service/user/admin/`）

#### 1.2.1 admin-rpc 侧（Go `bootstrap + config + domain/entity + domain/service + exception + grpc + mapper + metrics + service`，27 个非测试 `.go`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/admin/rpc/admin.go` | `admin-rpc/.../AdminRpcApplication.java` | `service/users/admin/src/main/java/com/rag/admin/AdminServiceApplication.java` + `service/users/admin/src/main/java/com/rag/admin/wiring/NacosRegistration.java` + `service/users/admin/src/main/java/com/rag/admin/wiring/SnowflakeBootstrap.java` | 1:N | 进程装配拆成入口 + 注册 + ID 生成器初始化；**C 的入口与 admin-api 共用**（D1） |
| `service/user/admin/rpc/adminservice/adminservice.go` | （无独立文件） | （无独立文件） | 1:1 | go-zero 生成的 gRPC service 接口 ⇒ `@GrpcService` + 生成的 `AdminServiceGrpc.AdminServiceImplBase` 承接 |
| `service/user/admin/rpc/internal/config/config.go` | `admin-rpc/.../config/AdminRpcProperties.java` | `service/users/admin/src/main/java/com/rag/admin/config/AdminRpcProperties.java` | 1:1 | |
| `service/user/admin/rpc/internal/svc/servicecontext.go` | `admin-rpc/.../bootstrap/AdminSeederBootstrap.java`（`ensureDefaultAdmin` 部分） | `service/users/admin/src/main/java/com/rag/admin/wiring/AdminSeederBootstrap.java` | 1:1 | ⚠️ Go 侧无对应物（原因）：`ServiceContext` 整体被 Spring DI 吸收，**但其中的 `ensureDefaultAdmin`（默认 `admin`/`admin` 种子 + `IsUniqueViolation` 兜底 + `logx.Must` fail-fast）是业务方法 ⇒ 必须独立承载**，故拆到 `AdminSeederBootstrap`（B/C 同构） |
| `service/user/admin/rpc/internal/server/adminserviceserver.go` | `admin-rpc/.../service/AdminServiceGrpcImpl.java` | `service/users/admin/src/main/java/com/rag/admin/grpc/AdminGrpcService.java` | 1:1 | 13 个 RPC 方法分发 |
| `service/user/admin/rpc/internal/logic/loginlogic.go` | `admin-rpc/.../service/AdminDomainService.java` | `service/users/admin/src/main/java/com/rag/admin/domain/service/AdminDomainService.java` | N:1 | 13 个 logic 合 1（与下 12 行同归一个 C 文件） |
| `.../logic/createadminlogic.go` | 同上 + `admin-rpc/.../service/AdminCreateAtomicService.java` | 同上 + `service/users/admin/src/main/java/com/rag/admin/domain/service/AdminCreateAtomicService.java` | N:1 | 4 步事务（建 admin + 消耗邀请码 + 更新 `used_by_uid`/`used_at` + 唯一约束兜底）必须落在**独立 `@Service` Bean**（self-invocation 风险） |
| `.../logic/createinvitecodelogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/getuserlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/getuserlistlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/getselflogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/logoutlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/deleteuserlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/resetuserpasswordlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/banuserlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/unbanuserlogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/updateselflogic.go` | 同上 | 同上 | N:1 | |
| `.../logic/updateuserlogic.go` | 同上 | 同上 | N:1 | |
| `service/user/admin/rpc/internal/logic/helper.go` | `admin-rpc/.../service/AdminHelpers.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminHelpers.java` | 1:1 | admin-rpc 的用例内 helper |
| `service/user/admin/rpc/internal/model/admin.go` | `admin-rpc/.../mapper/AdminMapper.java` + `AdminInviteMapper.java` + `UserMapper.java` | `service/users/admin/src/main/java/com/rag/admin/mapper/AdminMapper.java` + `AdminInviteMapper.java` + `UserMapper.java` | 1:N | `AdminModel` 的查询/更新方法 → 3 个 Mapper（按实体拆） |
| `service/user/admin/rpc/internal/model/data_model.go` | `admin-rpc/.../domain/AdminEntity.java` + `AdminInviteEntity.java` + `UserEntity.java` | `service/users/admin/src/main/java/com/rag/admin/domain/entity/AdminEntity.java` + `AdminInviteEntity.java` + `UserEntity.java` | 1:N | 3 个 GORM struct → 3 个 `@TableName` 实体 |
| `service/user/admin/rpc/internal/model/db.go` | `admin-rpc/.../bootstrap/AdminSchemaBootstrap.java` + `users-common/.../ddl/SchemaInitializer.java` | `service/users/admin/src/main/java/com/rag/admin/wiring/AdminSchemaBootstrap.java` | 1:1 | `AutoMigrate(&Admin{}, &AdminInvite{}, &User{})` → 幂等 DDL 重放；⚠️ 与 user-rpc 同一个缺口（C 侧 `db/migration/` 空、`common/` 无 `SchemaInitializer`） |
| `service/user/admin/rpc/internal/model/helper.go` | `admin-rpc/.../mapper/AdminMapper.java`（`IsUniqueViolation` 判定） | `service/users/admin/src/main/java/com/rag/admin/mapper/AdminMapper.java` | 1:1 | PG `23505` 唯一约束判定（`clause.Locking{Strength:"UPDATE"}` 行级悲观锁同在 `AdminMapper`） |
| `service/user/admin/rpc/internal/model/vars.go` | `admin-rpc/.../exception/AdminExceptions.java` | `service/users/admin/src/main/java/com/rag/admin/exception/AdminExceptions.java` | 1:1 | `ErrorNotFound` → 业务异常聚合类 |
| `service/user/admin/rpc/internal/metrics/metrics.go` | `admin-rpc/.../metrics/AdminRpcMetrics.java` | `service/users/admin/src/main/java/com/rag/admin/metrics/AdminRpcMetrics.java` | 1:1 | 指标完整名 L1：`admin_rpc_login_total`、`admin_rpc_high_risk_action_logal`（**拼写错误照抄，不得修正**） |
| `service/user/admin/rpc/pb/admin.pb.go` | `admin-proto/src/main/proto/admin.proto`（生成物） | `proto/src/main/proto/admin.proto`（生成物，pb 包名 `com.rag.proto.admin`） | 1:1 | proto 生成物；字段号 L1 |
| `service/user/admin/rpc/pb/admin_grpc.pb.go` | 同上 | 同上 | 1:1 | gRPC stub 生成物 |

> **补充行（承接 Go logic 内联手写的 span）**

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/admin/rpc/internal/logic/*.go`（**其中 8 个**：`loginlogic` / `createadminlogic` / `createinvitecodelogic` / `updateuserlogic` / `banuserlogic` / `unbanuserlogic` / `deleteuserlogic` / `resetuserpasswordlogic`；这 8 个已在上面 13 行中列出） | `admin-rpc/.../service/AdminSpans.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminSpans.java` | N:1 | Go 的 8 个 logic 在方法开头**内联手写** `tracer.Start(ctx, "Action-Admin-…")` + `audit.*` 属性 ⇒ B/C 抽成独立 `AdminSpans`（span 名与属性是 L2，不得改） |

#### 1.2.2 admin-api 侧（Go `advice + config + controller + dto + interceptor + metrics + service`，33 个非测试 `.go`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/admin/api/admincenter.go` | `admin-api/.../AdminApiApplication.java` | `service/users/admin/src/main/java/com/rag/admin/AdminServiceApplication.java`（与 admin-rpc **共用**） | N:1 | D1 合并 |
| `service/user/admin/api/internal/config/config.go` | `admin-api/.../config/AdminApiProperties.java` | `service/users/admin/src/main/java/com/rag/admin/config/AdminApiProperties.java` | 1:1 | |
| `service/user/admin/api/internal/handler/routes.go` | `admin-api/.../controller/AdminController.java` + `admin-api/.../config/WebMvcConfig.java` | `service/users/admin/src/main/java/com/rag/admin/controller/AdminController.java` + `service/users/admin/src/main/java/com/rag/admin/config/WebMvcConfig.java` | 1:N | 同 user-api；路由前缀 Go `/admincenter/v1` ⇒ C `/admin/v1`（签字偏离） |
| `service/user/admin/api/internal/handler/admin/createhandler.go` | `admin-api/.../service/AdminCreateService.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminApiService.java` | N:1 | handler + logic 合并；C 侧 13 个用例合 1 个 `AdminApiService` |
| `.../handler/admin/loginhandler.go` | `admin-api/.../service/AdminLoginService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/logouthandler.go` | `admin-api/.../service/AdminLogoutService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/getselfhandler.go` | `admin-api/.../service/AdminGetSelfService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/getuserhandler.go` | `admin-api/.../service/AdminGetUserService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/getuserlisthandler.go` | `admin-api/.../service/AdminListUserService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/updatehandler.go` | `admin-api/.../service/AdminUpdateSelfService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/updateuserhandler.go` | `admin-api/.../service/AdminUpdateUserService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/deletehandler.go` | `admin-api/.../service/AdminDeleteUserService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/resetuserpasswordhandler.go` | `admin-api/.../service/AdminResetUserPasswordService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/banuserhandler.go` | `admin-api/.../service/AdminBanUserService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/unbanuserhandler.go` | `admin-api/.../service/AdminUnBanUserService.java` | 同上 | N:1 | 同上 |
| `.../handler/admin/createinvitehandler.go` | `admin-api/.../service/AdminCreateInviteCodeService.java` | 同上 | N:1 | 同上 |
| `service/user/admin/api/internal/logic/admin/createlogic.go` | `admin-api/.../service/AdminCreateService.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminApiService.java` | N:1 | Go 的「handler → logic」两层在 B 合并为 1 个 Service，在 C 再合并为 1 个 `AdminApiService` |
| `.../logic/admin/loginlogic.go` | `admin-api/.../service/AdminLoginService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/logoutlogic.go` | `admin-api/.../service/AdminLogoutService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/getselflogic.go` | `admin-api/.../service/AdminGetSelfService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/getuserlogic.go` | `admin-api/.../service/AdminGetUserService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/getuserlistlogic.go` | `admin-api/.../service/AdminListUserService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/updatelogic.go` | `admin-api/.../service/AdminUpdateSelfService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/updateuserlogic.go` | `admin-api/.../service/AdminUpdateUserService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/deletelogic.go` | `admin-api/.../service/AdminDeleteUserService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/resetuserpasswordlogic.go` | `admin-api/.../service/AdminResetUserPasswordService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/banuserlogic.go` | `admin-api/.../service/AdminBanUserService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/unbanuserlogic.go` | `admin-api/.../service/AdminUnBanUserService.java` | 同上 | N:1 | 同上 |
| `.../logic/admin/createinvitelogic.go` | `admin-api/.../service/AdminCreateInviteCodeService.java` | 同上 | N:1 | 同上 |
| `service/user/admin/api/internal/types/types.go` | `admin-api/.../dto/`：**`AdminInfo.java`**、**`BanUserReq.java`**、**`BanUserResp.java`**、**`CreateAdminReq.java`**、**`CreateAdminResp.java`**、**`CreateInviteCodeReq.java`**、**`CreateInviteCodeResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetSelfReq.java`**、**`GetSelfResp.java`**、**`GetUserListReq.java`**、**`GetUserListResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`ResetUserPasswordReq.java`**、**`ResetUserPasswordResp.java`**、**`UnBanUserReq.java`**、**`UnBanUserResp.java`**、**`UpdateSelfReq.java`**、**`UpdateSelfResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UserInfo.java`**（**28** 个） | `service/users/admin/src/main/java/com/rag/admin/dto`：**`AdminInfo.java`**、**`BanUserReq.java`**、**`BanUserResp.java`**、**`CreateAdminReq.java`**、**`CreateAdminResp.java`**、**`CreateInviteReq.java`**、**`CreateInviteResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetSelfReq.java`**、**`GetSelfResp.java`**、**`GetUserListReq.java`**、**`GetUserListResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`ResetUserPasswordReq.java`**、**`ResetUserPasswordResp.java`**、**`UnBanUserReq.java`**、**`UnBanUserResp.java`**、**`UpdateSelfReq.java`**、**`UpdateSelfResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UserInfo.java`**（**28** 个） | 1:N | Go `.api` 的 **28** 个 type → 28 个 immutable record（D5）；⚠️ C 把 B 的 `CreateInviteCodeReq/Resp` 改名 `CreateInviteReq/Resp`（**回到 Go `.api` 名**，与 proto 侧的 `CreateInviteCode*` 并存）⇒ 见 §7.4 F-5 |
| `service/user/admin/api/internal/middleware/checkblacklistmiddleware.go` | `admin-api/.../interceptor/AdminJwtAuthInterceptor.java` | `service/users/admin/src/main/java/com/rag/admin/interceptor/AdminJwtAuthInterceptor.java` | 1:1 | blacklist key 前缀与 user 侧区分（`admin:jwt_blacklist:` vs `user:jwt_blacklist:`） |
| `service/user/admin/api/internal/metrics/metrics.go` | `admin-api/.../metrics/AdminApiMetrics.java` | `service/users/admin/src/main/java/com/rag/admin/metrics/AdminApiMetrics.java` | 1:1 | 指标完整名 L1：`admin_api_intercept_total` |
| `service/user/admin/api/internal/svc/servicecontext.go` | `admin-api/.../client/AdminRpcClient.java` + `admin-api/.../config/AdminApiProperties.java` + `admin-api/.../config/WebMvcConfig.java` | `service/users/admin/src/main/java/com/rag/admin/config/AdminApiProperties.java` + `service/users/admin/src/main/java/com/rag/admin/config/WebMvcConfig.java` | 1:N | D2 域内直连 ⇒ C 侧无 `api/client/` |

> **补充行（B 引入、C 保留的用户侧/api 侧横切件）**

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| —（Go 侧无对应物：go-zero redis 客户端内置断连行为） | `user-api/.../config/RedisDisconnectedBehaviorConfig.java` | `service/users/user/src/main/java/com/rag/user/config/RedisDisconnectedBehaviorConfig.java` | 1:1 | Go 侧无对应物（原因）：B 为修 RAG 漂移（`DRIFT-USERAPI-009②`）引入的 **Lettuce 断连行为**定制（Go 侧 fail-open / RAG 侧 503）；C 原样保留 ⇒ **该差异在 C 侧仍然存在** |
| —（Go 侧无对应物：Go 侧靠 `httpx.OkJson` 手动包信封 + `LogicResult` 双返回） | `user-api/.../advice/GlobalResponseAdvice.java` + `admin-api/.../advice/GlobalResponseAdvice.java` | `service/users/user/src/main/java/com/rag/user/advice/UserApiResponseAdvice.java` + `service/users/admin/src/main/java/com/rag/admin/advice/AdminApiResponseAdvice.java` | 1:N | Go 侧无对应物（原因）：`@RestControllerAdvice` + `ResponseBodyAdvice` 把任意返回值包成 `{code,msg,data}`（统一信封是 L1） |
| —（Go 侧无对应物） | `user-api/.../advice/GlobalExceptionHandler.java` + `admin-api/.../advice/GlobalExceptionHandler.java` | `service/users/user/src/main/java/com/rag/user/advice/UserApiExceptionHandler.java` + `service/users/admin/src/main/java/com/rag/admin/advice/AdminApiExceptionHandler.java` | 1:N | Go 侧无对应物（原因）：`@RestControllerAdvice` 异常出口；**D2 合并后不再需要「gRPC code → 业务码」这一层映射**（业务层直接抛 `BusinessException`） |
| —（Go 侧无对应物：admin-api 无独立 helper 文件，helper 分散在 13 个 logic 里） | `admin-api/.../service/AdminApiSupport.java` | `service/users/admin/src/main/java/com/rag/admin/service/AdminApiSupport.java` | 1:1 | Go 侧无对应物（原因）：uid 解析（`parseUid`）+ proto→DTO 转换（`AdminInfo` / `UserInfo`）的聚合点；对应 user 侧的 `UserApiSupport` |
| —（Go 侧无对应物：MP 3.5.14 的 `@AutoConfigureAfter` 指向 Boot 3 的包名） | `user-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` + `admin-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 1:1 | Go 侧无对应物（原因）：**Boot 4 专用的 MP 自动配置排序桥**（Boot 4 挪了 `DataSourceAutoConfiguration` 的包 ⇒ MP 排序引用悬空 ⇒ `SqlSessionFactory` 不产出）。D3：RAG 的 **9 份副本**收进 starter 的 **1 份**；**归属见 `common.md`** |

---

## 2. 持久层差异（本子域相关行）

| 维度 | A Go（真源） | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| 行级悲观锁 | `tx.Clauses(clause.Locking{Strength:"UPDATE"})` | `AdminMapper` 内 `@Select("... FOR UPDATE")` | 同 B | 邀请码核销路径 |
| 唯一约束判定 | `model/helper.go` 的 `IsUniqueViolation`（PG `23505`） | `AdminMapper` 内 | 同 B | 默认 admin 种子创建的幂等兜底 |

> ⚠️ 原 §2 表的 10 行中，**本子域专属的是上面 2 行**（行级悲观锁 / 唯一约束判定），其余 8 行为跨子域行
> ⇒ **本子域的其余相关行见 [`../_shared.md` §2 持久层差异](../_shared.md#2-持久层差异)**（含该节的决策锁「换持久层命名空间 6 处同时改」）。

---

## 3. HTTP / DI 差异（本子域相关行）

> ⚠️ 原 §3 表的 **17 行全部是跨子域行**（每行同时写 user 与 admin）
> ⇒ **本子域相关行见 [`../_shared.md` §3 HTTP / DI 差异](../_shared.md#3-http--di-差异)**（含该节的决策锁「换进程拓扑 7 处同时改」+「换错误表达 4 处同时改」）。

---

## 4. 公共模块对照（与本子域相关行）

> ⚠️ 原 §4 表的 15 行中，**admin 子域专属行为 0 行** ——
> `users-common` 私有件的 7 行见 [`../users-common.md` §4](../users-common.md#4-公共模块对照)，其余 8 行为全局归属 / 跨子域行（其中「jsonb / text TypeHandler」一行点名本子域的 `admin-rpc` 侧 2 份副本）。
> ⇒ **本子域相关行见 [`../_shared.md` §4 公共模块对照](../_shared.md#4-公共模块对照)**。

---

## 5. 决策锁（与本子域相关）

本子域涉及的决策：**D1**（api 与 rpc 合并为一个 app ⇒ `service/users/admin/`）· **D2**（域内调用直连，不再自我 gRPC 往返）· **D5**（DTO 改 immutable record + Bean Validation）· **P4**（`rpc.*` 包是否改名，待拍板）。

> ⚠️ **决策的完整内容、落点与代价见 [`../_shared.md` §5 决策锁（汇总）](../_shared.md#5-决策锁汇总)** —— 本节只列本子域相关的决策编号，**不复制表格行**（避免重复计数）。
> 本子域的**目录形态**决策锁（换目录形态 8 处同时改）见 [`../_shared.md` §1](../_shared.md#1-包结构对照域级)。
> ⚠️ **本子域另有一条待决缺口**：F-2（admin 侧**无 `contract/` 包** —— D7 删除了 `admin-contract` 模块；admin 若要引用错误契约，应取公共库 `service/users/common/src/main/java/com/rag/users/common/` 那份）⇒ 见 [`../_shared.md` §7.4](../_shared.md#74-三列之间职责对不上的地方⚠-需处理)。

---

## 6. 跨模块全局约定（与本子域相关行）

> **本节的值全部来自 A 列 Go 源码**，不来自 B 列，也不来自 RAG-V1 的文档。

| 项 | 值 | A 列出处（`file:line` 级可 grep） |
|---|---|---|
| **列约束（admins / admin_invites）** | `admins.email` **可空**（`*string`）；`admin_invites.code` uniqueIndex+not null、`inviter_uid` index+not null、`expires_at` index+not null、`used_by_uid`/`used_at` 可空 | `service/user/admin/rpc/internal/model/data_model.go` |
| **默认管理员种子** | `admin` / `admin`（`defaultAdminUsername` / `defaultAdminPassword`），`snowflake.GetID()` 生成 uid，插入失败遇 PG `23505` 时回查一次 | `service/user/admin/rpc/internal/svc/servicecontext.go`（`ensureDefaultAdmin`） |

> ⚠️ **本表已按子域拆分** —— 其余 16 行为跨子域行 ⇒ 见 [`../_shared.md` §6 跨模块全局约定](../_shared.md#6-跨模块全局约定值逐条核对-a-列-go-源码)。

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
| `service/users/admin/src/main/java/com/rag/admin/` | 56 | §1.2.1（admin-rpc 侧）+ §1.2.2（admin-api 侧）+ §1.2 补充行（`AdminApiResponseAdvice` / `AdminApiExceptionHandler` / `AdminApiSupport`） | ✅ 56/56 |
| **合计** | **56** | — | ✅ **56/56** |

> ⚠️ **F-2 落点**：[`../_shared.md` §7.4](../_shared.md#74-三列之间职责对不上的地方⚠-需处理)。

### 7.2 A 列覆盖率 —— **60 / 60 = 100%**（admin 子域）

| Go 子目录 | 非测试 `.go` 数 | 本表覆盖位置 | 覆盖 |
|---|---|---|---|
| `service/user/admin/api/` | 33 | §1.2.2 | ✅ 33/33 |
| `service/user/admin/rpc/` | 27 | §1.2.1 | ✅ 27/27 |
| **合计** | **60** | — | ✅ **60/60** |

### 7.3 映射类型分布（admin 子域）

> 统计口径：§1 全部映射表的**第 4 列**（不含 §2/§3/§4 的差异对照表）。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **17** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」）；其中 **3 行** A 列为「Go 侧无对应物」（B 引入的横切件） |
| `1:N` | **8** | Go 单文件拆成多个 C 文件（其中 **2 行** A 列为「Go 侧无对应物」） |
| `N:1` | **41** | Go 多文件合进 1 个 C 文件（**D1/D2 的主因**：admin-api 13+13、admin-rpc 13 个 logic → 各 1 个 Service） |
| `C 新增` | **0** | 本子域每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本子域是**重组**而非新增能力 |
| **合计** | **66** | — |

> 统计命令（可复跑）：
> ```bash
> awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' docs/Mapping_Table/users/admin/admin.md \
>   | awk -F'|' '/^\|/{t=$5; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 17 1:1 / 8 1:N / 41 N:1
> ```

---

## 8. 一句话总结（admin 子域）

**A 列 Go**（`service/user/admin/`，60 个非测试 `.go`）= 单一真源；
**B 列 RAG**（`service/users/admin/`，api/rpc 分离的 Go 部署形态复刻）；
**C 列 RAG-V1**（`service/users/admin/`，56 个 `.java`）= Spring 惯用目标形态（一域一 app）。

⇒ **admin 子域 C 列 56/56 覆盖、A 列 60/60 覆盖、映射类型 1:1 ×17 / 1:N ×8 / N:1 ×41 / C 新增 ×0（共 66 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 A 列 Go 源码为准，状态看 `RAG/docs/DRIFT/users/`。**
