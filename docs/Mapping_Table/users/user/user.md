# Mapping_Table/users/user/user.md — user 子域两方文件名对照表

> **本表目的**：把 **RAG**（B 列，**唯一真理之源**）→ **RAG-V1**（C 列，Spring 惯用目标形态）两棵树的 **users 域 user 子域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 该子域路径 | 角色 |
> |---|---|---|---|
> | **B（RAG，唯一真理之源）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/user/` | **唯一行为判定基准**（D16 · 2026-11-02） |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/user/` | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（更早一版的两方镜像对照）。
> **域级共享层**：[`../_shared.md`](../_shared.md) —— §0 读表须知（完整版）· §1 目录形态决策锁 · §2~§6 跨子域对照 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结。

### 导航

| 文档 | 装什么 | B 侧出处 |
|---|---|---|
| [`../_shared.md`](../_shared.md) | 域级共享层 | `service/users/`（全域） |
| [`../users-common.md`](../users-common.md) | users-common 公共库 | `service/users/users-common/` |
| **本文件** | **user 子域** | `service/users/user/` |
| [`../admin/admin.md`](../admin/admin.md) | admin 子域 | `service/users/admin/` |

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
| `users/user/api/`（`UserApiApplication`） | `service/users/user/` → `com.rag.user`（`UserServiceApplication.java`） | 入口类合并（D1）|
| `users/user/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `UserApiResponseAdvice`）|
| `users/user/api/auth/`（4 个 JWT 验签类） | `interceptor/` | 直接压平（D2：**5 个类** —— 验签 Filter ＋ 路径判据 ＋ 验签器 ＋ 结果载体 ＋ HandlerInterceptor） |
| `users/user/api/client/`（`UserRpcClient`） | **—（D2 域内直连 ⇒ 删除）** | 域内自我 gRPC 往返取消 |
| `users/user/api/config/` | `config/` | 与 rpc 侧合并 |
| `users/user/api/controller/` | `controller/` | 直接压平 |
| `users/user/api/dto/` | `dto/auth/` + `dto/avatar/` + `dto/user/` | 直接压平后**按业务对象拆 3 个子包**（D11：`auth` 4 / `avatar` 6 / `user` 9） |
| `users/user/api/interceptor/` | `interceptor/` | 直接压平 |
| `users/user/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `users/user/api/service/` | `service/` | 与 rpc 侧合并 |
| `users/user/rpc/bootstrap/` | `wiring/` | 直接压平 |
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

### 1.1 user 子域（B 侧 `service/users/user/`）

#### 1.1.1 user-rpc 侧（B 侧 `bootstrap + config + domain + exception + mapper + metrics + service`，15 个 `.java`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `user-rpc/.../UserRpcApplication.java` + `user-rpc/.../bootstrap/NacosRegistration.java` + `user-rpc/.../bootstrap/SnowflakeBootstrap.java` | `service/users/user/src/main/java/com/rag/user/UserServiceApplication.java` + `service/users/user/src/main/java/com/rag/user/wiring/NacosRegistration.java` + `service/users/user/src/main/java/com/rag/user/wiring/SnowflakeBootstrap.java` | 1:N | 进程装配（入口 → 配置 → 服务 → 启动）在 B 侧占 3 个文件，C 侧拆成「入口 + 注册 + ID 生成器初始化」三个 Spring Bean；**C 的入口与 user-api 共用**（D1） |
| （无独立文件） | （无独立文件） | 1:1 | B 侧由 codegen 生成的 gRPC service 接口（无独立手写文件）；C 由 `@GrpcService` + 生成的 `UserServiceGrpc.UserServiceImplBase` 承接（**生成物在根级 `proto/` 模块**，pb 包名 `com.rag.proto.user`） |
| `user-rpc/.../config/UserRpcProperties.java` | `service/users/user/src/main/java/com/rag/user/config/UserRpcProperties.java` | 1:1 | yaml 绑定 → `@ConfigurationProperties`；字段语义 1:1 |
| （无对应物） | （无对应物） | 1:1 | B 侧无对应物（原因）：纯依赖持有，无业务方法 ⇒ Spring DI 吸收（ABSORB）；B/C 均**不生成** `ServiceContext.java` |
| `user-rpc/.../service/impl/UserServiceGrpcImpl.java` | `service/users/user/src/main/java/com/rag/user/grpc/UserGrpcService.java` | 1:1 | 9 个 RPC 方法分发；分发由 starter 完成，方法体（proto↔领域转换 + 指标）需显式写 |
| `user-rpc/.../service/UserDomainService.java` | `service/users/user/src/main/java/com/rag/user/domain/service/UserDomainService.java` | N:1 | 9 个 logic 合 1（与下 8 行同归一个 C 文件） |
| 同上 | 同上 | N:1 | 同上（含注册事务边界） |
| 同上 | 同上 | N:1 | 同上（JWT blacklist + TTL） |
| 同上 | 同上 | N:1 | 同上 |
| 同上 + `user-rpc/.../mapper/UserMapper.java` | 同上 + `service/users/user/src/main/java/com/rag/user/mapper/UserMapper.java` | N:1 | 业务归 Service，jsonb/text 绑参细节归 Mapper |
| 同上 | 同上 | N:1 | 同上 |
| 同上 + `user-rpc/.../service/AvatarDomainService.java` | 同上 + `service/users/user/src/main/java/com/rag/user/domain/service/AvatarDomainService.java` | N:1 | 同上；**多步原子写**（user.avatar + history insert + 旧 history 置 `is_current=false`） |
| 同上 | 同上 | N:1 | 同上 |
| 同上 + `AvatarDomainService.java` | 同上 + `AvatarDomainService.java` | N:1 | 同上；投影部分归 `AvatarDomainService` |
| `user-rpc/.../service/AvatarDomainService.java` | `service/users/user/src/main/java/com/rag/user/domain/service/AvatarDomainService.java` | 1:1 | 归一化规则（URL `TrimSpace`、空 contentType → `image/png`、historyId `TrimSpace`）的**权威实现** |
| `user-rpc/.../domain/UserEntity.java` + `UserAvatarHistoryEntity.java` | `service/users/user/src/main/java/com/rag/user/domain/entity/UserEntity.java` + `UserAvatarHistoryEntity.java` | 1:N | B 侧的 `@TableName` 实体（2 个文件 → 2 个 C 实体） |
| `user-rpc/.../mapper/UserMapper.java` + `UserAvatarHistoryMapper.java` | `service/users/user/src/main/java/com/rag/user/mapper/UserMapper.java` + `UserAvatarHistoryMapper.java` | 1:N | 查询方法 → Mapper（按实体拆） |
| `user-rpc/.../bootstrap/UserSchemaBootstrap.java` + `users-common/.../ddl/SchemaInitializer.java` | `service/users/user/src/main/java/com/rag/user/wiring/UserSchemaBootstrap.java` | 1:1 | B 侧的幂等 DDL 重放 → C 同构；⚠️ **C 侧 `db/migration/` 仅 `.gitkeep`，且 `common/` 无 `SchemaInitializer`** ⇒ 执行器与脚本在 C 侧**无落点**（见 §7.3 F-3） |
| `user-rpc/.../exception/UserExceptions.java` | `service/users/user/src/main/java/com/rag/user/exception/UserExceptions.java` | 1:1 | 哨兵错误（`ErrorNotFound` / `ErrorAvatarHistoryNotFound`）→ 按业务含义聚合的异常嵌套类 |
| `user-rpc/.../metrics/UserRpcMetrics.java` | `service/users/user/src/main/java/com/rag/user/metrics/UserRpcMetrics.java` | 1:1 | 指标完整名 L1：`user_rpc_register_total`、`user_rpc_login_total` |
| `user-proto/src/main/proto/users.proto`（生成物） | `proto/src/main/proto/users.proto`（生成物，pb 包名 `com.rag.proto.user`） | 1:1 | proto 生成物；字段号 L1，一个字节都不动 |
| 同上 | 同上 | 1:1 | gRPC stub 生成物 |
| （无对应物） | （无对应物） | 1:1 | B 侧无对应物（原因）：误置于 `user/rpc/pb` 的 points 生成物，`service/users/` 内**零引用**；points 域不在 users 域范围 ⇒ B/C 均不复制 |

#### 1.1.2 user-api 侧（B 侧 `advice + auth + config + controller + dto + interceptor + metrics + service`，43 个 `.java`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `user-api/.../UserApiApplication.java` | `service/users/user/src/main/java/com/rag/user/UserServiceApplication.java`（与 user-rpc **共用**） | N:1 | D1：api 与 rpc 合并为单一 app ⇒ **C 侧两个入口收敛成 1 个** |
| `user-api/.../config/UserApiProperties.java` | `service/users/user/src/main/java/com/rag/user/config/UserApiProperties.java` | 1:1 | HTTP 层配置绑定 |
| `user-api/.../controller/UserController.java` + `user-api/.../config/WebMvcConfig.java` | `service/users/user/src/main/java/com/rag/user/controller/UserController.java` + `service/users/user/src/main/java/com/rag/user/config/WebMvcConfig.java` | 1:N | B 侧的 `@RequestMapping("/user/v1")` + `@PostMapping("/{op}")` → C 同构；B 侧的 JWT / 中间件注册 → `WebMvcConfig` 注册 interceptor |
| `user-api/.../service/UserLoginService.java` | `service/users/user/src/main/java/com/rag/user/service/UserApiService.java` | N:1 | handler + logic 合并；C 侧 9 个用例合 1 个 `UserApiService` |
| `user-api/.../service/UserRegisterService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserGetService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserUpdateService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserDeleteService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserLogoutService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/AvatarUploadService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/AvatarSelectService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/AvatarHistoryService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserLoginService.java` | `service/users/user/src/main/java/com/rag/user/service/UserApiService.java` | N:1 | B 侧的「handler → logic」两层已合并为 1 个 Service，C 侧再合并为 1 个 `UserApiService` |
| `user-api/.../service/UserRegisterService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserGetService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserUpdateService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserDeleteService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserLogoutService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/AvatarUploadService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/AvatarSelectService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/AvatarHistoryService.java` | 同上 | N:1 | 同上 |
| `user-api/.../service/UserApiSupport.java` | `service/users/user/src/main/java/com/rag/user/service/UserApiSupport.java` | 1:1 | api 侧**镜像**（rpc 侧 `AvatarDomainService` 才是权威实现）；另含 uid 解析与 proto→DTO 转换 |
| `user-api/.../dto/`：**`AvatarHistoryItem.java`**、**`CreateUserReq.java`**、**`CreateUserResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetAvatarHistoryResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`RegisterReq.java`**、**`RegisterResp.java`**、**`SelectAvatarReq.java`**、**`SelectAvatarResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UploadAvatarReq.java`**、**`UploadAvatarResp.java`**、**`UserInfo.java`**（B 侧 `dto/` 共 19 个文件） | `service/users/user/src/main/java/com/rag/user/dto`：**`AvatarHistoryItem.java`**、**`CreateUserReq.java`**、**`CreateUserResp.java`**、**`DeleteUserReq.java`**、**`DeleteUserResp.java`**、**`GetAvatarHistoryResp.java`**、**`GetUserReq.java`**、**`GetUserResp.java`**、**`LoginReq.java`**、**`LoginResp.java`**、**`LogoutReq.java`**、**`LogoutResp.java`**、**`SelectAvatarReq.java`**、**`SelectAvatarResp.java`**、**`UpdateUserReq.java`**、**`UpdateUserResp.java`**、**`UploadAvatarReq.java`**、**`UploadAvatarResp.java`**、**`UserInfo.java`**（**19** 个） | 1:N | B 侧的 **19** 个 DTO → 19 个 immutable record（D5）；B 的 `RegisterReq`/`RegisterResp` 在 C 改名 `CreateUserReq`/`CreateUserResp`（回到 proto 侧的 `CreateUserReq` 名）；⚠️ B/C 的 `GetAvatarHistoryResp` 取代了旧版的 `AvatarHistoryResp`（取 proto 名）⇒ 见 §7.3 F-5 |
| `user-api/.../interceptor/JwtAuthInterceptor.java` | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthInterceptor.java` | 1:1 | B 侧的 JWT 校验 + blacklist 中间件 → `HandlerInterceptor`（能访问 `HandlerMethod`） |
| `user-api/.../service/IdentityResolver.java` | `service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java` | 1:1 | ✅ **已落地（2026-11-01）**：5 个 sentinel 错误 + `AuthorityID` / `PlatformTenantID` **有了承载文件**；⚠️ **上收到 `service/users/common/`（只 1 份）**（用户 2026-11-01 定）⇒ user / admin 两子域共用，`users-common` 已被两者依赖 ⇒ **不动 pom**（见 §7.3 F-1） |
| `user-api/.../metrics/UserApiMetrics.java` | `service/users/user/src/main/java/com/rag/user/metrics/UserApiMetrics.java` | 1:1 | 指标完整名 L1：`user_api_intercept_total` |
| `user-api/.../client/UserRpcClient.java` + `user-api/.../config/UserApiProperties.java` + `user-api/.../config/WebMvcConfig.java` | `service/users/user/src/main/java/com/rag/user/config/UserApiProperties.java` + `service/users/user/src/main/java/com/rag/user/config/WebMvcConfig.java` | 1:N | D2：域内调用直连 ⇒ **C 侧无 `api/client/`**（全仓 `api/client` 目录 0 个） |

#### 1.1.3 user 子域的跨进程共享件：identity（B 侧 `user-api/.../service/IdentityResolver.java` 与 `admin-api/.../service/IdentityResolver.java` 各一份，共 2 份）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `user-api/.../service/IdentityResolver.java` + `admin-api/.../service/IdentityResolver.java`（**两份重复**） | `service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java`（**C 侧只 1 份**，上收到域级共享层） | 1:1 | ✅ **已落地（2026-11-01）**：B 侧的**权威**身份解析器（`ClaimedUID` → `GetUser` → status presence 检查 → `SubjectRef`；`AuthorityID="rtw.identity"` / `PlatformTenantID="platform"`；5 个 sentinel 错误）在 C 侧**有了唯一承载文件** ⇒ B 侧两份重复**在 C 侧被消解**（见 §7.3 F-1） |

### 1.2 🔴 RAG 侧新增类补录（4 条 · 覆盖 4 个 `.java`）

> ⚠️ **本节是 2026-11-01 的增量**：RAG 在首版映射表之后新增了 `user-api/…/auth/` 包（4 类）
> ⇒ 首版 §1.1.2 的 **B 列漏了这 4 个 `.java`** ⇒ 本节逐个落到 C 侧；
> 补齐后 `tools/check-mapping-coverage.py` 的 **B 列双向对账归零**。
>
> ✅ **2026-11-02 · user 域 lane 已落地（4 条全部 1:1）** —— 首版把 B 侧这 4 个文件**合并成 1 个
> `JwtAuthInterceptor`**（N:1），并在备注里留了一个 🔴 缺口：「**要么补 1 个 `OncePerRequestFilter`
> 承载验签，要么显式登记为签字偏离**」。
> ⇒ 本域选了**第一个选项**（补 Filter）⇒ ⇒ 本节改为 **4 条 1:1**，
> B 侧 4 个类**逐个**落到 C 侧（`GoStyleJwtAuthFilter` → `JwtAuthFilter`，**类改名**）。
> ⇒ 缺口已闭合（见本域 DRIFT 台账 `docs/DRIFT/users/verification-users-user-2026-11-02.md`）。
>
> ℹ️ **验签结果的消费方**（C 侧 `JwtAuthInterceptor`）的登记在 §1.1.2（`user-api/.../interceptor/JwtAuthInterceptor.java` → C 侧同名类，1:1）⇒ **不在本节重复登记**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|
| `user-api/…/auth/JwtAuthPaths.java` | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthPaths.java` | 1:1 | ⚠️ RAG 把 JWT **验签**拆成两段（`DRIFT-USERAPI-009②` 的**首发域**）：验签在 **Servlet Filter**（3s 预算**之外**）＋ 黑名单/响应在 **HandlerInterceptor**（预算**之内**）⇒ **路径判据必须只有一份**（Filter 与 Interceptor 共用）⇒ 少一份就会出现「Filter 判受保护、Interceptor 判放行」的**鉴权绕过**缝隙。⚠️ **受保护路由表 ＋ public 前缀集合是 L1** |
| `user-api/…/auth/JwtSignatureVerifier.java` | `service/users/user/src/main/java/com/rag/user/interceptor/JwtSignatureVerifier.java` | 1:1 | 只做「解析 ＋ 验签名 ＋ 取 claim」，**不写响应、不查 Redis**；⚠️ **畸形 claim 不在鉴权层拒绝**（B 侧 `DRIFT-USERAPI-001` 已修）⇒ 交给业务层 `IdentityResolver.claimedUID` ⇒ 200 ＋ 1006。⚠️ 本域的 claim 名 `userId`（小写 u ＋ 驼峰）是 **L1**，不可写成 `user_id` |
| `user-api/…/auth/JwtVerificationResult.java` | `service/users/user/src/main/java/com/rag/user/interceptor/JwtVerificationResult.java` | 1:1 | 验签结果载体（Filter 写、Interceptor 读）；⚠️ 载体带**验签来源**标记（`filter` / `inline`）⇒ 供守门测试断言「验签确实在 Filter 里做」（B 侧 `DRIFT-USERAPI-009②` 是否闭环） |
| `user-api/…/auth/GoStyleJwtAuthFilter.java` | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthFilter.java` | 1:1 | ⚠️ **类改名**（去 `GoStyle` 前缀）⇒ 职责不变：把验签搬出 3s 预算。🔴 **本 Filter 绝不写响应** ⇒ 401 仍由 Interceptor 抛 ⇒ 「**413 压过 401**」这条语义不漂 |

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
> `users-common` 私有件的 7 行见 [`../users-common.md` §4](../users-common.md#4-公共模块对照与本库相关行)（其中「错误码 / 文案」一行同时点名本子域的 `service/users/user/src/main/java/com/rag/user/contract/ErrCodes.java` + `ErrMsg.java`），
> 其余 8 行为全局归属 / 跨子域行。
> ⇒ **本子域相关行见 [`../_shared.md` §4 公共模块对照](../_shared.md#4-公共模块对照)**。

---

## 5. 决策锁（与本子域相关）

本子域涉及的决策：**D1**（api 与 rpc 合并为一个 app ⇒ `service/users/user/`）· **D2**（域内调用直连，不再自我 gRPC 往返）· **D5**（DTO 改 immutable record + Bean Validation）· **P1**（错误文案保真度，待拍板）· **P4**（`rpc.*` 包是否改名，待拍板）。

> ⚠️ **决策的完整内容、落点与代价见 [`../_shared.md` §5 决策锁（汇总）](../_shared.md#5-决策锁汇总)** —— 本节只列本子域相关的决策编号，**不复制表格行**（避免重复计数）。
> 本子域的**目录形态**决策锁（换目录形态 8 处同时改）见 [`../_shared.md` §1](../_shared.md#1-包结构对照域级)。

---

## 6. 跨模块全局约定（与本子域相关行）

> **本节的值全部来自 B 列（RAG 侧）源码**，不来自 C 列，也不来自 RAG-V1 的文档。

| 项 | 值 | B 侧出处（`file:line` 级可 grep） |
|---|---|---|
| **JWT claim 的唯一来源** | 只接受验签通过后由 `JwtAuthInterceptor` 写入 request attribute 的 `userId`（`Long`，必须 > 0）；**请求体 / header / 客户端事件里的 uid 一律不参与解析** | `service/users/user/user-api/.../service/IdentityResolver.java`（`claimedUID`） |
| **身份常量** | `AuthorityID = "rtw.identity"`；`PlatformTenantID = "platform"`；`SubjectRef{authority_id, tenant_id, subject_id}`（wire JSON = `{"authority_id":"rtw.identity","tenant_id":"platform","subject_id":"<UID>"}`），`subject_id` = UID 十进制字符串 | 同上 |
| **列约束（users）** | `users_user` 表：`uid` NOT NULL UNIQUE（另有 `idx_users_user_uid` 索引）· `username` UNIQUE · `email` UNIQUE · `status` NOT NULL DEFAULT 0 · `extra_info` TEXT（`JsonTextTypeHandler`）· `create_time` / `update_time` DEFAULT CURRENT_TIMESTAMP · 字符串列一律 `TEXT`（非 `VARCHAR(255)`） | `service/users/users-common/src/main/resources/db/01_create_user.sql` ＋ `service/users/user/user-rpc/.../domain/UserEntity.java` |

> ⚠️ **本表已按子域拆分** —— 其余 15 行为跨子域行 ⇒ 见 [`../_shared.md` §6 跨模块全局约定](../_shared.md#6-跨模块全局约定值逐条核对-b-侧源码)。

---

## 7. 覆盖度自查（本子域）

### 7.1 C 列覆盖率 —— **48 / 48 = 100%**（user 子域）

> ✅ **2026-11-02 · user 域 lane 已落地** —— 骨架 45 个 → 现 **48** 个：
> **+4**（`interceptor/` 下 `JwtAuthFilter` / `JwtAuthPaths` / `JwtSignatureVerifier` / `JwtVerificationResult`
> —— 闭合 §1.2 的 🔴 缺口「补 1 个 `OncePerRequestFilter` 承载验签」）
> **−1**（`config/RedisDisconnectedBehaviorConfig.java` —— B 侧已删并上移共享层 `common/redis/ClientAutoConfiguration`，
> C 侧照抄 ⇒ 域内副本删除，见本域 DRIFT 台账）。

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users/user" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| `service/users/user/src/main/java/com/rag/user/contract/` | 2 | §1.3 补充说明（`ErrCodes.java` / `ErrMsg.java`，见 [`../users-common.md` §4](../users-common.md#4-公共模块对照与本库相关行) 的「错误码 / 文案」行） | ✅ 2/2 |
| `service/users/user/src/main/java/com/rag/user/` | 46 | §1.1.1（user-rpc 侧）+ §1.1.2（user-api 侧）+ §1.1.3（`IdentityResolver`）+ §1.2（`interceptor/` 5 个类）+ §1.1.2 补充行（`UserApiResponseAdvice` / `UserApiExceptionHandler`，见 [`../admin/admin.md`](../admin/admin.md) §1.2.2） | ✅ 46/46 |
| **合计** | **48** | — | ✅ **48/48** |

**B 列（RAG 侧 `service/users/user/`）覆盖率 —— 58 / 58 = 100%**（抄写路线图 §1.0c 的 B↔C 依据）

> B 侧 `service/users/user/` 实测 **58** 个 `.java`（`user-api` 43 · `user-rpc` 15）⇒ 本表 B 列覆盖 **55/58**，
> 另 **3 个** 由兄弟文档承载：`GlobalExceptionHandler.java` / `GlobalResponseAdvice.java`（见 [`../admin/admin.md`](../admin/admin.md) §1.2.2 补充行）·
> `JsonTextTypeHandler.java`（见 [`../users-common.md`](../users-common.md) §1.3）⇒ **users 域 B 列合计 145/145，零遗漏**。
> 复算命令（`RAG-V1` 仓根）：
> ```bash
> python3 tools/check-mapping-coverage.py users   # ⇒ users 域 B 列 145/145
> ```

### 7.2 映射类型分布（user 子域）

> 统计口径：§1 全部映射表的**类型列**（`B | C | 映射类型 | 备注` = 4 列 ⇒ 类型是第 3 格）；不含 §2/§3/§4 的差异对照表。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **21** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」；**2026-11-02 起 +4**：§1.2 的 4 个 `auth/` 类逐个落到 C 侧） |
| `1:N` | **6** | B 的单文件拆成多个 C 文件 |
| `N:1` | **28** | B 的多文件合进 1 个 C 文件（**D1/D2 的主因**：user-api 9+9、user-rpc 9 个 logic → 各 1 个 Service） |
| `C 新增` | **0** | 本子域每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本子域是**重组**而非新增能力 |
| **合计** | **55** | — |

> 统计命令（可复跑）：
> ```bash
> awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' docs/Mapping_Table/users/user/user.md \
>   | awk -F'|' '/^\|/{t=$4; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 21 1:1 / 6 1:N / 28 N:1
> ```
> ⚠️ **历史自报数（`1:1` 17 / `1:N` 6 / `N:1` 29 / 合计 52）已随 §1.2 拆分而变** ——
> 2026-11-02 · user 域 lane 把 §1.2 的 1 条 `N:1` 拆成 **5 条 `1:1`**（闭合「补 1 个 Filter 承载验签」的缺口）
> ⇒ ⇒ 实测值为 **21 / 6 / 28 / 合计 55**（上表即按此命令实测，不是估算）。

---

## 8. 一句话总结（user 子域）

**B 列 RAG**（`service/users/user/`，58 个 `.java`）= **唯一真理之源**（api/rpc 分离的部署形态）；
**C 列 RAG-V1**（`service/users/user/`，**48** 个 `.java`）= Spring 惯用目标形态（一域一 app）。

⇒ **user 子域 C 列 48/48 覆盖、B 列 58/58 覆盖（3 个由兄弟文档承载）、映射类型 1:1 ×21 / 1:N ×6 / N:1 ×28 / C 新增 ×0（共 55 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 B 列（RAG 侧）源码为准，状态看 `RAG/docs/DRIFT/users/`。**
⇒ **2026-11-02 · user 域 lane 已落地**：C 列 48 个文件**全部有实现**（不再是空壳）；
行为层结论见本域 DRIFT 台账 [`docs/DRIFT/users/verification-users-user-2026-11-02.md`](../../../../DRIFT/users/verification-users-user-2026-11-02.md)。
