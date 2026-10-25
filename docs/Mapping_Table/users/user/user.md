# Mapping_Table/users/user/user.md — user 子域三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标形态）三棵树的 **users 域 user 子域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 该子域路径 | 角色 |
> |---|---|---|---|
> | **A（Go 真理之源）** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | `service/user/user/`（**单数**） | 冻结真源，唯一行为判定基准 |
> | **B（RAG，Go 形态版）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/user/`（**复数**） | Go 部署拓扑（api/rpc 分离）的 Spring 复刻 |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/user/`（**复数**） | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（那份是 Sea-Java 镜像 ↔ RAG；本份是 Go ↔ RAG ↔ RAG-V1）。
> **域级共享层**：[`../_shared.md`](../_shared.md) —— §0 读表须知（完整版）· §1 目录形态决策锁 · §2~§6 跨子域对照 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结。

### 导航

| 文档 | 装什么 | Go 出处 |
|---|---|---|
| [`../_shared.md`](../_shared.md) | 域级共享层 | `service/user/`（全域） |
| [`../users-common.md`](../users-common.md) | users-common 公共库 | Go `service/user/common/` |
| **本文件** | **user 子域** | Go `service/user/user/` |
| [`../admin/admin.md`](../admin/admin.md) | admin 子域 | Go `service/user/admin/` |

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
| `users/user/api/`（`UserApiApplication`） | `service/users/user/` → `com.rag.user`（`UserServiceApplication.java`） | 入口类合并（D1）|
| `users/user/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `UserApiResponseAdvice`）|
| `users/user/api/client/`（`UserRpcClient`） | **—（D2 域内直连 ⇒ 删除）** | 域内自我 gRPC 往返取消 |
| `users/user/api/config/` | `config/` | 与 rpc 侧合并 |
| `users/user/api/controller/` | `controller/` | 直接压平 |
| `users/user/api/dto/` | `dto/` | 直接压平（17 个同名类全落此）|
| `users/user/api/interceptor/` | `interceptor/` | 直接压平 |
| `users/user/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `users/user/api/service/` | `service/` | 与 rpc 侧合并 |
| `users/user/rpc/bootstrap/` | `bootstrap/` | 直接压平 |
| `users/user/rpc/config/` | `config/` | 合并 |
| `users/user/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `users/user/rpc/exception/` | `exception/` | 直接压平 |
| `users/user/rpc/mapper/` | `mapper/` | 直接压平 |
| `users/user/rpc/metrics/` | `metrics/` | 合并 |
| `users/user/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `users/user/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `UserServiceGrpcImpl` → `UserGrpcService` |
| **—（B 侧 errmsg 在 `users-common`）** | `contract/`（`ErrCodes` + `ErrMsg`） | user 子域**私有**错误契约 |
| `users/user-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.user`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 user 子域（Go `service/user/user/`）

#### 1.1.1 user-rpc 侧（Go `bootstrap + config + domain/entity + domain/service + exception + grpc + mapper + metrics`，23 个非测试 `.go`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/user/rpc/user.go` | `user-rpc/.../UserRpcApplication.java` | `service/users/user/src/main/java/com/rag/user/UserServiceApplication.java` + `service/users/user/src/main/java/com/rag/user/bootstrap/NacosRegistration.java` + `service/users/user/src/main/java/com/rag/user/bootstrap/SnowflakeBootstrap.java` | 1:N | 进程装配（flag → config → svc → server.Run）拆成「入口 + 注册 + ID 生成器初始化」三个 Spring Bean；**C 的入口与 user-api 共用**（D1） |
| `service/user/user/rpc/userservice/userservice.go` | （无独立文件） | （无独立文件） | 1:1 | go-zero 生成的 gRPC service 接口；C 由 `@GrpcService` + 生成的 `UserServiceGrpc.UserServiceImplBase` 承接（**生成物在根级 `proto/` 模块**，pb 包名 `com.rag.proto.user`） |
| `service/user/user/rpc/internal/config/config.go` | `user-rpc/.../config/UserRpcProperties.java` | `service/users/user/src/main/java/com/rag/user/config/UserRpcProperties.java` | 1:1 | yaml struct → `@ConfigurationProperties`；字段语义 1:1 |
| `service/user/user/rpc/internal/svc/servicecontext.go` | （无对应物） | （无对应物） | 1:1 | Go 侧无对应物（原因）：纯依赖持有，无业务方法 ⇒ Spring DI 吸收（ABSORB）；B/C 均**不生成** `ServiceContext.java` |
| `service/user/user/rpc/internal/server/userserviceserver.go` | `user-rpc/.../service/impl/UserServiceGrpcImpl.java` | `service/users/user/src/main/java/com/rag/user/grpc/UserGrpcService.java` | 1:1 | 9 个 RPC 方法分发；分发由 starter 完成，方法体（proto↔领域转换 + 指标）需显式写 |
| `service/user/user/rpc/internal/logic/loginlogic.go` | `user-rpc/.../service/UserDomainService.java` | `service/users/user/src/main/java/com/rag/user/domain/service/UserDomainService.java` | N:1 | 9 个 logic 合 1（与下 8 行同归一个 C 文件） |
| `service/user/user/rpc/internal/logic/registerlogic.go` | 同上 | 同上 | N:1 | 同上（含注册事务边界） |
| `service/user/user/rpc/internal/logic/logoutlogic.go` | 同上 | 同上 | N:1 | 同上（JWT blacklist + TTL） |
| `service/user/user/rpc/internal/logic/getuserlogic.go` | 同上 | 同上 | N:1 | 同上 |
| `service/user/user/rpc/internal/logic/updateuserlogic.go` | 同上 + `user-rpc/.../mapper/UserMapper.java` | 同上 + `service/users/user/src/main/java/com/rag/user/mapper/UserMapper.java` | N:1 | 业务归 Service，jsonb/text 绑参细节归 Mapper |
| `service/user/user/rpc/internal/logic/deleteuserlogic.go` | 同上 | 同上 | N:1 | 同上 |
| `service/user/user/rpc/internal/logic/selectavatarlogic.go` | 同上 + `user-rpc/.../service/AvatarDomainService.java` | 同上 + `service/users/user/src/main/java/com/rag/user/domain/service/AvatarDomainService.java` | N:1 | 同上；**多步原子写**（user.avatar + history insert + 旧 history 置 `is_current=false`） |
| `service/user/user/rpc/internal/logic/uploadavatarlogic.go` | 同上 | 同上 | N:1 | 同上 |
| `service/user/user/rpc/internal/logic/getavatarhistorylogic.go` | 同上 + `AvatarDomainService.java` | 同上 + `AvatarDomainService.java` | N:1 | 同上；投影部分归 `AvatarDomainService` |
| `service/user/user/rpc/internal/logic/avatarhelpers.go` | `user-rpc/.../service/AvatarDomainService.java` | `service/users/user/src/main/java/com/rag/user/domain/service/AvatarDomainService.java` | 1:1 | 归一化规则（URL `TrimSpace`、空 contentType → `image/png`、historyId `TrimSpace`）的**权威实现** |
| `service/user/user/rpc/internal/model/data_model.go` | `user-rpc/.../domain/UserEntity.java` + `UserAvatarHistoryEntity.java` | `service/users/user/src/main/java/com/rag/user/domain/entity/UserEntity.java` + `UserAvatarHistoryEntity.java` | 1:N | GORM struct + `TableName()` → `@TableName` 实体（2 个 struct → 2 个 Entity） |
| `service/user/user/rpc/internal/model/user.go` | `user-rpc/.../mapper/UserMapper.java` + `UserAvatarHistoryMapper.java` | `service/users/user/src/main/java/com/rag/user/mapper/UserMapper.java` + `UserAvatarHistoryMapper.java` | 1:N | 查询方法 → Mapper（按实体拆，HUMAN_ONLY.MD 决策） |
| `service/user/user/rpc/internal/model/db.go` | `user-rpc/.../bootstrap/UserSchemaBootstrap.java` + `users-common/.../ddl/SchemaInitializer.java` | `service/users/user/src/main/java/com/rag/user/bootstrap/UserSchemaBootstrap.java` | 1:1 | `AutoMigrate` → 幂等 DDL 重放；⚠️ **C 侧 `db/migration/` 仅 `.gitkeep`，且 `common/` 无 `SchemaInitializer`** ⇒ 执行器与脚本在 C 侧**无落点**（见 §7.4 F-3） |
| `service/user/user/rpc/internal/model/vars.go` | `user-rpc/.../exception/UserExceptions.java` | `service/users/user/src/main/java/com/rag/user/exception/UserExceptions.java` | 1:1 | 哨兵错误（`ErrorNotFound` / `ErrorAvatarHistoryNotFound`）→ 按业务含义聚合的异常嵌套类 |
| `service/user/user/rpc/internal/metrics/metrics.go` | `user-rpc/.../metrics/UserRpcMetrics.java` | `service/users/user/src/main/java/com/rag/user/metrics/UserRpcMetrics.java` | 1:1 | 指标完整名 L1：`user_rpc_register_total`、`user_rpc_login_total` |
| `service/user/user/rpc/pb/user.pb.go` | `user-proto/src/main/proto/users.proto`（生成物） | `proto/src/main/proto/users.proto`（生成物，pb 包名 `com.rag.proto.user`） | 1:1 | proto 生成物；字段号 L1，一个字节都不动 |
| `service/user/user/rpc/pb/user_grpc.pb.go` | 同上 | 同上 | 1:1 | gRPC stub 生成物 |
| `service/user/user/rpc/pb/points.pb.go` | （无对应物） | （无对应物） | 1:1 | Go 侧无对应物（原因）：误置于 `user/rpc/pb` 的 points 生成物，`service/user/` 内**零引用**；points 域不在 users 域范围 ⇒ B/C 均不复制 |

#### 1.1.2 user-api 侧（Go `advice + config + controller + dto + interceptor + metrics + service`，27 个非测试 `.go`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/user/api/usercenter.go` | `user-api/.../UserApiApplication.java` | `service/users/user/src/main/java/com/rag/user/UserServiceApplication.java`（与 user-rpc **共用**） | N:1 | D1：api 与 rpc 合并为单一 app ⇒ **C 侧两个入口收敛成 1 个** |
| `service/user/user/api/internal/config/config.go` | `user-api/.../config/UserApiProperties.java` | `service/users/user/src/main/java/com/rag/user/config/UserApiProperties.java` | 1:1 | HTTP 层配置绑定 |
| `service/user/user/api/internal/handler/routes.go` | `user-api/.../controller/UserController.java` + `user-api/.../config/WebMvcConfig.java` | `service/users/user/src/main/java/com/rag/user/controller/UserController.java` + `service/users/user/src/main/java/com/rag/user/config/WebMvcConfig.java` | 1:N | go-zero `AddRoutes` → `@RequestMapping` + `@PostMapping`；`rest.WithJwt` / `rest.WithMiddlewares` → `WebMvcConfig` 注册 interceptor |
| `service/user/user/api/internal/handler/user/loginhandler.go` | `user-api/.../service/UserLoginService.java` | `service/users/user/src/main/java/com/rag/user/service/UserApiService.java` | N:1 | handler + logic 合并；C 侧 9 个用例合 1 个 `UserApiService` |
| `.../handler/user/registerhandler.go` | `user-api/.../service/UserRegisterService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/getuserhandler.go` | `user-api/.../service/UserGetService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/updateuserhandler.go` | `user-api/.../service/UserUpdateService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/deleteuserhandler.go` | `user-api/.../service/UserDeleteService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/logouthandler.go` | `user-api/.../service/UserLogoutService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/uploadavatarhandler.go` | `user-api/.../service/AvatarUploadService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/selectavatarhandler.go` | `user-api/.../service/AvatarSelectService.java` | 同上 | N:1 | 同上 |
| `.../handler/user/getavatarhistoryhandler.go` | `user-api/.../service/AvatarHistoryService.java` | 同上 | N:1 | 同上 |
| `service/user/user/api/internal/logic/user/loginlogic.go` | `user-api/.../service/UserLoginService.java` | `service/users/user/src/main/java/com/rag/user/service/UserApiService.java` | N:1 | Go 的「handler → logic」两层在 B 合并为 1 个 Service，在 C 再合并为 1 个 `UserApiService` |
| `.../logic/user/registerlogic.go` | `user-api/.../service/UserRegisterService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/getuserlogic.go` | `user-api/.../service/UserGetService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/updateuserlogic.go` | `user-api/.../service/UserUpdateService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/deleteuserlogic.go` | `user-api/.../service/UserDeleteService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/logoutlogic.go` | `user-api/.../service/UserLogoutService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/uploadavatarlogic.go` | `user-api/.../service/AvatarUploadService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/selectavatarlogic.go` | `user-api/.../service/AvatarSelectService.java` | 同上 | N:1 | 同上 |
| `.../logic/user/getavatarhistorylogic.go` | `user-api/.../service/AvatarHistoryService.java` | 同上 | N:1 | 同上 |
| `service/user/user/api/internal/logic/user/avatarhelpers.go` | `user-api/.../service/UserApiSupport.java` | `service/users/user/src/main/java/com/rag/user/service/UserApiSupport.java` | 1:1 | api 侧**镜像**（rpc 侧 `AvatarDomainService` 才是权威实现）；另含 uid 解析与 proto→DTO 转换 |
| `service/user/user/api/internal/types/types.go` | `user-api/.../dto/`：**`AvatarHistoryItem.java`**、**`CreateUserReq.java`**、**`CreateUserResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetAvatarHistoryResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`RegisterReq.java`**、**`RegisterResp.java`**、**`SelectAvatarReq.java`**、**`SelectAvatarResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UploadAvatarReq.java`**、**`UploadAvatarResp.java`**、**`UserInfo.java`**（B 侧 `dto/` 共 19 个文件） | `service/users/user/src/main/java/com/rag/user/dto`：**`AvatarHistoryItem.java`**、**`CreateUserReq.java`**、**`CreateUserResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetAvatarHistoryResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`SelectAvatarReq.java`**、**`SelectAvatarResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UploadAvatarReq.java`**、**`UploadAvatarResp.java`**、**`UserInfo.java`**（**19** 个） | 1:N | Go `.api` 的 **19** 个 type → 19 个 immutable record（D5）；B 的 `RegisterReq`/`RegisterResp` 在 C 改名 `CreateUserReq`/`CreateUserResp`（回到 Go `.api` 名）；⚠️ Go 的 `AvatarHistoryResp` 在 B/C 是 `GetAvatarHistoryResp`（取 proto 名）⇒ 见 §7.4 F-5 |
| `service/user/user/api/internal/middleware/checkblacklistmiddleware.go` | `user-api/.../interceptor/JwtAuthInterceptor.java` | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthInterceptor.java` | 1:1 | go-zero `rest.WithJwt` + blacklist 中间件 → `HandlerInterceptor`（能访问 `HandlerMethod`） |
| `service/user/user/api/internal/identity/resolve.go` | `user-api/.../service/IdentityResolver.java` | **（无对应物）** | 1:1 | ⚠️ Go 侧无对应物（原因见备注）：C 侧**无承载文件** —— 5 个 sentinel 错误 + `AuthorityID` / `PlatformTenantID` 在 C 侧**没有落点**（见 §7.4 F-1） |
| `service/user/user/api/internal/metrics/metrics.go` | `user-api/.../metrics/UserApiMetrics.java` | `service/users/user/src/main/java/com/rag/user/metrics/UserApiMetrics.java` | 1:1 | 指标完整名 L1：`user_api_intercept_total` |
| `service/user/user/api/internal/svc/servicecontext.go` | `user-api/.../client/UserRpcClient.java` + `user-api/.../config/UserApiProperties.java` + `user-api/.../config/WebMvcConfig.java` | `service/users/user/src/main/java/com/rag/user/config/UserApiProperties.java` + `service/users/user/src/main/java/com/rag/user/config/WebMvcConfig.java` | 1:N | D2：域内调用直连 ⇒ **C 侧无 `api/client/`**（全仓 `api/client` 目录 0 个） |

#### 1.1.3 user 子域的跨进程共享件：identity（Go `service/user/user/identity/`，1 个文件）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/user/user/identity/resolve.go` | `user-api/.../service/IdentityResolver.java` + `admin-api/.../service/IdentityResolver.java`（**两份重复**） | **（无对应物）** | 1:1 | ⚠️ Go 侧无对应物（原因见备注）：Go 的**权威**身份解析器（`ClaimedUID` → `GetUser` → status presence 检查 → `SubjectRef`；`AuthorityID="rtw.identity"` / `PlatformTenantID="platform"`；5 个 sentinel 错误）在 C 侧**无任何文件承载**（见 §7.4 F-1） |

---

## 2. 持久层差异（本子域相关行）

> ⚠️ 原 §2 表的 **10 行中，user 子域专属行为 0 行**（行级悲观锁 / 唯一约束判定 2 行属 admin 子域，其余 8 行为跨子域行）
> ⇒ **本子域相关行见 [`../_shared.md` §2 持久层差异](../_shared.md#2-持久层差异)**。

---

## 3. HTTP / DI 差异（本子域相关行）

> ⚠️ 原 §3 表的 **17 行全部是跨子域行**（每行同时写 user 与 admin）
> ⇒ **本子域相关行见 [`../_shared.md` §3 HTTP / DI 差异](../_shared.md#3-http--di-差异)**。

---

## 4. 公共模块对照（与本子域相关行）

> ⚠️ 原 §4 表的 15 行中，**user 子域专属行为 0 行** ——
> `users-common` 私有件的 7 行见 [`../users-common.md` §4](../users-common.md#4-公共模块对照)（其中「错误码 / 文案」一行同时点名本子域的 `service/users/user/src/main/java/com/rag/user/contract/ErrCodes.java` + `ErrMsg.java`），
> 其余 8 行为全局归属 / 跨子域行。
> ⇒ **本子域相关行见 [`../_shared.md` §4 公共模块对照](../_shared.md#4-公共模块对照)**。

---

## 5. 决策锁（与本子域相关）

本子域涉及的决策：**D1**（api 与 rpc 合并为一个 app ⇒ `service/users/user/`）· **D2**（域内调用直连，不再自我 gRPC 往返）· **D5**（DTO 改 immutable record + Bean Validation）· **P1**（错误文案保真度，待拍板）· **P4**（`rpc.*` 包是否改名，待拍板）。

> ⚠️ **决策的完整内容、落点与代价见 [`../_shared.md` §5 决策锁（汇总）](../_shared.md#5-决策锁汇总)** —— 本节只列本子域相关的决策编号，**不复制表格行**（避免重复计数）。
> 本子域的**目录形态**决策锁（换目录形态 8 处同时改）见 [`../_shared.md` §1](../_shared.md#1-包结构对照域级)。

---

## 6. 跨模块全局约定（与本子域相关行）

> **本节的值全部来自 A 列 Go 源码**，不来自 B 列，也不来自 RAG-V1 的文档。

| 项 | 值 | A 列出处（`file:line` 级可 grep） |
|---|---|---|
| **JWT claim 的唯一来源** | 只接受 go-zero 已验签的 `ctx.Value("userId").(json.Number)`；**请求体 / header / 客户端事件里的 uid 一律不参与解析** | `service/user/user/identity/resolve.go`（`ClaimedUID`） |
| **身份常量** | `AuthorityID = "rtw.identity"`；`PlatformTenantID = "platform"`；`SubjectRef{authority_id,tenant_id,subject_id}`，`subject_id` = UID 十进制字符串 | 同上 |
| **列约束（users）** | `uid` uniqueIndex+not null；`username` unique；`email` unique；`status` default 0；`extra_info` `serializer:json`；`create_time`/`update_time` autoCreate/autoUpdate | `service/user/user/rpc/internal/model/data_model.go` |

> ⚠️ **本表已按子域拆分** —— 其余 15 行为跨子域行 ⇒ 见 [`../_shared.md` §6 跨模块全局约定](../_shared.md#6-跨模块全局约定值逐条核对-a-列-go-源码)。

---

## 7. 覆盖度自查（本子域）

### 7.1 C 列覆盖率 —— **45 / 45 = 100%**（user 子域）

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users/user" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| `service/users/user/src/main/java/com/rag/user/contract/` | 2 | §1.3 第 1 行（`ErrCodes.java` / `ErrMsg.java`，见 [`../users-common.md`](../users-common.md) §1） | ✅ 2/2 |
| `service/users/user/src/main/java/com/rag/user/` | 43 | §1.1.1（user-rpc 侧）+ §1.1.2（user-api 侧）+ §1.2 补充行（`UserApiResponseAdvice` / `UserApiExceptionHandler` / `RedisDisconnectedBehaviorConfig`，见 [`../admin/admin.md`](../admin/admin.md) §1） | ✅ 43/43 |
| **合计** | **45** | — | ✅ **45/45** |

### 7.2 A 列覆盖率 —— **51 / 51 = 100%**（user 子域）

| Go 子目录 | 非测试 `.go` 数 | 本表覆盖位置 | 覆盖 |
|---|---|---|---|
| `service/user/user/identity/` | 1 | §1.1.3 | ✅ 1/1 |
| `service/user/user/api/` | 27 | §1.1.2 | ✅ 27/27 |
| `service/user/user/rpc/` | 23 | §1.1.1 | ✅ 23/23 |
| **合计** | **51** | — | ✅ **51/51** |

### 7.3 映射类型分布（user 子域）

> 统计口径：§1 全部映射表的**第 4 列**（不含 §2/§3/§4 的差异对照表）。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **17** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」） |
| `1:N` | **6** | Go 单文件拆成多个 C 文件 |
| `N:1` | **28** | Go 多文件合进 1 个 C 文件（**D1/D2 的主因**：user-api 9+9、user-rpc 9 个 logic → 各 1 个 Service） |
| `C 新增` | **0** | 本子域每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本子域是**重组**而非新增能力 |
| **合计** | **51** | — |

> 统计命令（可复跑）：
> ```bash
> awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' docs/Mapping_Table/users/user/user.md \
>   | awk -F'|' '/^\|/{t=$5; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 17 1:1 / 6 1:N / 28 N:1
> ```

---

## 8. 一句话总结（user 子域）

**A 列 Go**（`service/user/user/`，51 个非测试 `.go`）= 单一真源；
**B 列 RAG**（`service/users/user/`，api/rpc 分离的 Go 部署形态复刻）；
**C 列 RAG-V1**（`service/users/user/`，45 个 `.java`）= Spring 惯用目标形态（一域一 app）。

⇒ **user 子域 C 列 45/45 覆盖、A 列 51/51 覆盖、映射类型 1:1 ×17 / 1:N ×6 / N:1 ×28 / C 新增 ×0（共 51 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 A 列 Go 源码为准，状态看 `RAG/docs/DRIFT/users/`。**
