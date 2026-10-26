# Mapping_Table/users/_shared.md — users 域共享层（三方文件名对照）

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标形态）三棵树的 **users 域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 该域路径 | 角色 |
> |---|---|---|---|
> | **A（Go 真理之源）** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | `service/user/`（**单数**） | 冻结真源，唯一行为判定基准 |
> | **B（RAG，Go 形态版）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/`（**复数**） | Go 部署拓扑（api/rpc 分离）的 Spring 复刻 |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/`（**复数**） | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（那份是 Sea-Java 镜像 ↔ RAG；本份是 Go ↔ RAG ↔ RAG-V1）。
>
> ⚠️ **本文件是 users 域的「域级共享层」** —— 原单文件 `users/users.md`（515 行）按子域拆成四份：
> 本文件（域级共享）+ [`users-common.md`](./users-common.md)（公共库）+ [`user/user.md`](./user/user.md)（user 子域）+ [`admin/admin.md`](./admin/admin.md)（admin 子域）。
>
> **拆分规则**：§1 包结构对照按子域各归一份（§1.1 → user · §1.2 → admin · §1.3 → users-common）；
> §2~§6 的表按「**整行归属**」拆 —— 跨子域的行留在本文件、子域专属的行归到该子域；
> §7 的逐模块实测表按子域各归一份（合计行留在本文件）；§7.4 缺口清单 F-1~F-8 与 §8 总结留在本文件。
> ⇒ **每行只出现在一处，不复制**（否则覆盖度会重复计数）。

### 导航

| 文档 | 装什么 | Go 出处 |
|---|---|---|
| **[`_shared.md`](./_shared.md)（本文件）** | 域级共享层：§0 读表须知 · §1 目录形态决策锁 · §2 持久层差异 · §3 HTTP·DI 差异 · §4 公共模块对照 · §5 决策锁（汇总）· §6 跨模块全局约定 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结 | `service/user/`（全域） |
| [`users-common.md`](./users-common.md) | users-common 公共库 | Go `service/user/common/` |
| [`user/user.md`](./user/user.md) | user 子域 | Go `service/user/user/` |
| [`admin/admin.md`](./admin/admin.md) | admin 子域 | Go `service/user/admin/` |

---

## 〇、读表须知

### 0.1 三条不可违反的纪律（照抄 [`README.MD`](../README.MD) §七）

1. ⚠️ **B 列不是 Go 的替身** —— RAG 在 `users` 域已被判定 `NOT EQUIVALENT`（见 `RAG/docs/DRIFT/users/`）。
   **一切行为判断以 A 列 Go 源码为准**；本表只回答「文件名 / 职责的对应关系」。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**不下**「已对齐 / 未对齐」结论；行为状态看 `RAG/docs/DRIFT/users/` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 `.java` 目前只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

### 0.2 本表的边界

- 本表覆盖 **users 一个域、两个子域**（`user` / `admin`）+ 域内公共库 `users-common`。
  ⇒ **`users-common` 是公共库，不是第三个域。**
- RAG-V1 的**顶层 `common/`（starter）在仓库根，不在 `service/` 下** ⇒ 其文件清单由 **`Mapping_Table/common.md`** 负责。
  本表只在 [§4 公共模块对照](#4-公共模块对照) 点名 users 相关的件并说明归属，**不把 common 的文件全列进本表**。
- `.proto` / `.sql` 不是 `.java`，但它们是 L1/L2 契约载体 ⇒ 在 §1 随所属模块一并列出。
- **Go 侧是 `service/user/`（单数），RAG 与 RAG-V1 都是 `service/users/`（复数）**
  ⇒ **A→B/C 是一次重命名**，下表所有路径都已按此换算。

### 0.3 映射类型与「Go 侧无对应物」的写法

| 映射类型 | 判据 |
|---|---|
| `1:1` | 1 个 A 文件 → 1 个 B 文件 → 1 个 C 文件；**或** A 无、B 有、C 有（此时 A 列写「—（Go 侧无对应物）」，原因写在备注） |
| `1:N` | 1 个 A 文件 → N 个 C 文件（B 通常也是 1 个或也是 N 个） |
| `N:1` | N 个 A 文件 → 1 个 C 文件（B 通常已经是 1 个） |
| `C 新增` | A / B 都没有对应物，是 RAG-V1 形态本身需要的新文件 |

> **本域 `C 新增` = 0 行**（依据见 [§7.3](#73-映射类型分布)）：users 域的每个 C 文件都能在 B 列找到前身
> ⇒ RAG-V1 在本域是**重组**（合并 api/rpc、拆分错误契约、共享层上提），**不是新增能力**。

### 0.4 A/B/C 规模基线（实测）

| 侧 | 计量口径 | 数量 |
|---|---|---|
| A（Go） | `service/user/**/*.go`，排除 `_test.go` | **114** |
| B（RAG） | `service/users/**/*.java`，排除 `src/test/`、`target/` | **141** |
| C（RAG-V1） | `service/users/**/*.java`，排除 `src/test/`、`target/` | **103**（85 个唯一文件名） |

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

> ⚠️ **users 域是「一个域、两个子域」** —— RAG 侧 B 列有 `user` / `admin` **两棵 api+rpc 树**，
> RAG-V1 侧是两个**独立 jar 模块**（`service/users/user/` = `user-service`，`service/users/admin/` = `admin-service`），
> 外加一个**公共库**（`service/users/common/` = `users-common`）。⇒ 下面的包级对照按**三个模块**分别列。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `users/users-common/`（`com.rag.users.common.{cryptx,ddl,errmsg,http,jwt}`） | `service/users/common/`（artifactId `users-common`）<br>包 `com.rag.users.common` **仅剩 `ErrCodes` + `ErrMsg`** | 公共库保留；**`jwt` / `cryptx` / `http` / `ddl` 四个子包已上收 `common/`**（D3）|
| `users/user/api/` | `service/users/user/` → `com.rag.user`（`UserServiceApplication.java`） | 入口类合并（D1）|
| `users/user/api/{advice,config,controller,dto,interceptor,metrics,service}` | `advice/` / `config/` / `controller/` / `dto/` / `interceptor/` / `metrics/` / `service/` | 直接压平 |
| `users/user/rpc/{bootstrap,config,domain,exception,mapper,metrics,service}` | `bootstrap/` / `config/` / `domain/entity/` / `exception/` / `mapper/` / `metrics/` / `service/` + `domain/service/` | 直接压平；领域服务另出 `domain/service/` |
| `users/user/rpc/service/impl/` | `service/users/user/…/grpc/` | 压平 + 类改名 `UserServiceGrpcImpl` → `UserGrpcService` |
| `users/user/`（无 `users-common` 内的 errmsg 副本） | `service/users/user/…/contract/`（`ErrCodes` + `ErrMsg`） | user 子域**私有**错误契约 |
| `users/user-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.user`）| **集中化**（D7）|
| `users/admin/api/` | `service/users/admin/` → `com.rag.admin`（`AdminServiceApplication.java`） | 入口类合并（D1）|
| `users/admin/api/{advice,config,controller,dto,interceptor,metrics,service}` | `advice/` / `config/` / `controller/` / `dto/` / `interceptor/` / `metrics/` / `service/` | 直接压平 |
| `users/admin/rpc/{bootstrap,config,domain,exception,mapper,metrics,service}` | `bootstrap/` / `config/` / `domain/entity/` / `exception/` / `mapper/` / `metrics/` / `service/` + `domain/service/` | 直接压平 |
| `users/admin/rpc/service/`（**RAG 侧 admin 没有 `impl` 子包**） | `service/users/admin/src/main/java/com/rag/admin/grpc/` | RAG 侧 admin 的 `rpc/service/` 下**没有 `impl` 子包**；C 侧 gRPC 实现由 `AdminGrpcService` 单独承载 |
| **—（admin 无 contract 模块）** | **—（admin 侧无 `contract/` 包）** | ⚠️ admin 若要引用错误契约，取公共库 `service/users/common/` 那份（F-2）|
| `users/admin-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.admin`）| **集中化**（D7）|

## 1. 包结构对照（域级）

> ⚠️ **§1（包结构对照）已按子域拆到三份子文档** —— 本节只留**跨子域**的目录形态决策锁与导航。

| 子域 | 文档 | Go 出处 |
|---|---|---|
| user 子域 | [`user/user.md`](./user/user.md) §1 | Go `service/user/user/` |
| admin 子域 | [`admin/admin.md`](./admin/admin.md) §1 | Go `service/user/admin/` |
| users-common 公共库 | [`users-common.md`](./users-common.md) §1 | Go `service/user/common/` |

### 1.1 §1.1.1~1.3 的决策锁

**换目录形态（api+rpc 合并 + 契约拆分 + 共享层上提）8 处同时改**：
1. `service/users/user/user-api/` + `user-rpc/` 两个模块 → `service/users/user/` 单模块（pom 合一、`@SpringBootApplication` 合一）
2. `service/users/admin/admin-api/` + `admin-rpc/` 两个模块 → `service/users/admin/` 单模块
3. `service/users/user/user-proto/` → 根级 `proto/`（+ `admin-proto` → 根级 `proto/`）；错误契约 → `service/users/user/src/main/java/com/rag/user/contract/`
4. B 的 `users-common/errmsg/ErrorCode.java`（1 个类）→ C 的 `users/common/ErrCodes.java` + `ErrMsg.java` + `service/users/user/src/main/java/com/rag/user/contract/ErrCodes.java` + `ErrMsg.java`（4 个类）
5. B 的 `users-common/jwt/` + `cryptx/` → C 的 `common/src/main/java/com/rag/common/security/`（上提到仓库根，starter 化）
6. B 的 `users-common/http/GoStyle*`（4 个校验器）→ 删除，改 Bean Validation（D5）
7. B 的 `users-common/ddl/SchemaInitializer.java` + 各 rpc 的 `*SchemaBootstrap.java` → C 只保留 `*SchemaBootstrap.java`，**执行器与脚本缺失**（待补）
8. B 的 9 份 `MybatisPlusOrderingBridgeAutoConfiguration` → C 的 1 份 `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`

**为什么换**：Spring Cloud 的部署单元是「可部署服务」而不是「api 层 / rpc 层」（D1）；共享能力的标准形态是 starter 而不是「普通 jar + 每个消费者手写 `@Import`」（D3）；Java 生态的数据契约形态是 immutable record + Bean Validation（D5）。
⇒ **代价**：C 侧 B 的 141 个 `.java` 收敛成 103 个，且**「有对应」不等于「行为等价」** —— 本表只记文件名与职责的对应关系。

---

## 2. 持久层差异

| 维度 | A Go（真源） | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| ORM | GORM（`gorm.io/driver/postgres`） | MyBatis-Plus 3.5.14 | MyBatis-Plus（`mybatis-plus-spring-boot3-starter`） | 表达式层差异，行为判断仍以 A 为准 |
| 实体 | GORM struct + struct tag | `@TableName` + `@TableId(type=ASSIGN_ID)` Entity | 同 B | — |
| 数据访问 | `*Model` 上的方法（`FindOneByUid` / `Insert` / `Updates` …） | `Mapper extends BaseMapper<Entity>` + `LambdaQueryWrapper` | 同 B | 按实体拆 Mapper（B/C 同构） |
| **表名** | `users`、`user_avatar_history`、`admins`、`admin_invites`（GORM `TableName()`） | `users_user`、`users_user_avatar_history`、`admin_admin`、`admin_invite` | 同 B（`@TableName`） | ⚠️ **A↔B/C 表名不同** —— 见 §7.4 F-4；本表只记职责对应，不判行为 |
| Schema 演进 | `db.AutoMigrate(&User{}, &UserAvatarHistory{})`（**无状态、幂等**） | `SchemaInitializer` 幂等重放 `db/01..04_*.sql`（2026-10-21 起弃用 Flyway） | `*SchemaBootstrap` + `db/migration/`（**仅 `.gitkeep`**） | ⚠️ C 侧脚本与执行器缺失（F-3） |
| 事务 | `m.conn.Transaction(func(tx *gorm.DB) error {...})` 闭包 | `@Transactional`（独立 Bean，规避 self-invocation） | 同 B | `selectAvatar` / `SelectAvatarHistory` / `CreateAdmin` 是多步原子写 ⇒ **必须独立 Bean** |
| 雪花 ID | `snowflake.GetID()`（`service/common/snowflake`） | `common/spring/SnowflakeBean` + 各 rpc 的 `SnowflakeBootstrap` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` + 各 service 的 `SnowflakeBootstrap` | epoch / 位段是业务契约（like 域游标依赖 `>>22`） |
| jsonb / text 列 | `gorm:"column:extra_info;serializer:json"` | `JsonTextTypeHandler`（user-rpc，text）+ `JsonbTypeHandler`（admin-rpc，jsonb） | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` + `JsonbTypeHandler.java` | ⚠️ `LambdaUpdateWrapper.set(Map)` 会丢 TypeHandler ⇒ 需 `setSql("CAST(... AS jsonb)")` 或走实体更新 |

> ⚠️ **本表已按子域拆分** —— **admin 子域专属的 2 行**（行级悲观锁 / 唯一约束判定）见 [`admin/admin.md`](./admin/admin.md) §2；
> 其余 8 行为跨子域行（同一行同时写 user 与 admin），完整保留在本文件。

**决策锁**

**换持久层命名空间（GORM model → MyBatis-Plus Entity/Mapper）6 处同时改**：
1. `model/data_model.go` 的 struct → `domain/*Entity.java`（`@TableName` + 列名逐条对齐）
2. `model/user.go` / `model/admin.go` 的查询方法 → `mapper/*Mapper.java`（按实体拆）
3. `model/db.go` 的 `AutoMigrate` → `bootstrap/*SchemaBootstrap.java`（幂等 DDL 重放）
4. `model/vars.go` 的哨兵错误 → `exception/*Exceptions.java`（按业务含义聚合）
5. `model/helper.go` 的 `IsUniqueViolation` → `AdminMapper` 内的 PG 错误码判定
6. `serializer:json` 的 jsonb/text 映射 → `common/src/main/java/com/rag/common/persistence/Json*TypeHandler.java`

**为什么换**：MyBatis-Plus 的 `LambdaQueryWrapper` 比 GORM 的链式条件更直观，且不依赖运行期隐式 DDL；
⇒ **代价**：表名从 Go 的 `users` 变成 `users_user`（等），列名/可空性/索引必须逐列对拍才能判定行为是否一致 —— **本表不判**。

---

## 3. HTTP / DI 差异

| 维度 | A Go（真源） | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| 路由注册 | `server.AddRoutes(..., rest.WithPrefix("/usercenter/v1"))` | `@RequestMapping("/user/v1")` + `@PostMapping("/{op}")` | 同 B | ⚠️ **前缀是签字偏离**（Go `/usercenter/v1` ⇒ C `/user/v1`）；admin 同理（Go `/admincenter/v1` ⇒ C `/admin/v1`） |
| HTTP method | user `Get2·Post6·Delete1`；admin `Get3·Post9·Delete1` | **全 POST**（users 域签字偏离） | 同 B | C 的 `UserController` / `AdminController` javadoc 里写的是 `PUT` / `GET` 混合 ⇒ 与 A、B 都不一致（F-6） |
| 端点数 | user **9**；admin **13** | user 9；admin 13 | javadoc 写 user 9 / **admin 12** | C 的 `AdminController` javadoc 少列 `updateuser`（F-7） |
| Handler / Controller | 每个 case 一个 `*handler.go` | 1 个 `UserController` / `AdminController`，9 / 13 个 `@PostMapping` | 同 B | — |
| Logic 中间层 | handler → logic → model | **已删**（Controller/Service 直调） | 同 B | B/C 形态一致 |
| 用例聚合 | 9 / 13 个 logic | 9 / 13 个 `*Service` | **各合 1**（`UserApiService` / `AdminApiService`） | D2 + 「用例内聚」判据 |
| 错误表达 | `LogicResult.ok(data)` / `LogicResult.fail(code,msg)` 双返回 | `throw BusinessException(code,msg)` + `advice/GlobalExceptionHandler` | 同 B，改名 `UserApiExceptionHandler` / `AdminApiExceptionHandler` | **统一错误模型**：业务层抛 `BusinessException`，HTTP 边界与 gRPC 边界各一个适配器 |
| 响应信封 | `httpx.OkJson(w, &response.Response{...})` 手动包 | `@RestControllerAdvice` + `ResponseBodyAdvice` 自动包 `{code,msg,data}` | 同 B，改名 `*ApiResponseAdvice` | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不能用 `ProblemDetail` 替换** |
| JWT 校验 | `rest.WithJwt(secret)`（go-zero 内置） | `JwtAuthInterceptor` / `AdminJwtAuthInterceptor` | 同 B | Filter 是 Servlet 层；Interceptor 是 Spring MVC Handler 层 |
| public / JWT 划分 | user：public = `login` + `register`；admin：public = `create` + `login` | 同 | 同（`WebMvcConfig` 白名单） | L1 |
| 配置绑定 | yaml struct（`internal/config/config.go`） | `@ConfigurationProperties` 类 | 同 B | C 侧 `UserApiProperties` / `UserRpcProperties` / `AdminApiProperties` / `AdminRpcProperties` |
| ServiceContext | `svc.ServiceContext` 持有 model / cache / rpc client | **已删**（Spring Bean） | 同 B | — |
| 进程拓扑 | api 进程 + rpc 进程（**2 进程 / 子域**） | api + rpc（**2 进程 / 子域**） | **1 进程 / 子域** | D1：Go/RAG 20 进程 → RAG-V1 10 进程；**「rpc 进程不可用」这一故障模式消失** |
| gRPC 客户端（域内） | `svcCtx.UserRpc` / `svcCtx.AdminRpc` | `api/client/UserRpcClient.java` / `AdminRpcClient.java` | **无 `service/users/user/src/main/java/com/rag/user/{advice,config,controller,dto,interceptor,metrics,service}/client/`** | D2：域内调用直连注入领域服务 Bean |
| gRPC 客户端（跨域） | `svcCtx.ArticleRpc` 等 | `service/common` 的 `RpcInterceptor` 等 | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` + `service/users/user/src/main/java/com/rag/user/{bootstrap,config,domain/entity,domain/service,exception,grpc,mapper,metrics}/client/` | D6：users 域**无跨域调用** ⇒ C 侧 users 无 `rpc/client/` |
| gRPC 明文 | `zrpc.MustNewServer`（明文） | `grpc.client.*.negotiation-type: plaintext` | 同 B（`application.yml` 已写） | devh 默认 TLS ⇒ **必须显式写 plaintext**，否则静默变 TLS |
| HTTP 超时 | go-zero `rest/config.go:55` `Timeout` `json:",default=3000"` ⇒ **3000ms**（`DisableNativeRestTimeout` 已关掉原生超时中间件，超时只有 `NewHTTPMiddleware` 一个执行者） | `observability.http-timeout: 3s`（B 自研 observability 中间件的配置）+ `service/common/.../observability/GoStyleRequestDeadline.java` | `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` + `GoStyleRequestDeadline.java`（**两个都是空壳**），且两个 `application.yml` **无任何 timeout 配置** | ⚠️ 超时机制在 C 侧**未落点**（见 §7.4 F-8） |

> ⚠️ **本表的 17 行全部是跨子域行**（每行同时写 user 与 admin）⇒ 完整保留在本文件；
> 两个子域文档的 §3 为指针。

**决策锁**

**换进程拓扑（api+rpc 分离 → 合并为一域一 app）7 处同时改**：
1. `service/users/user/user-api/pom.xml` + `user-rpc/pom.xml` → `service/users/user/pom.xml`（依赖合并）
2. `service/users/admin/admin-api/pom.xml` + `admin-rpc/pom.xml` → `service/users/admin/pom.xml`
3. `UserApiApplication.java` + `UserRpcApplication.java` → `UserServiceApplication.java`（**1 个** `@SpringBootApplication`）
4. `AdminApiApplication.java` + `AdminRpcApplication.java` → `AdminServiceApplication.java`
5. `{advice,config,controller,dto,interceptor,metrics,service}/client/*RpcClient.java` → 删（域内直连；跨域才留 `{bootstrap,config,domain/entity,domain/service,exception,grpc,mapper,metrics}/client/`）
6. `application.yml` 两份 → 一份（HTTP 端口 + gRPC 端口同时在一个进程）
7. Nacos 注册项：2 个/子域 → 1 个/子域

**为什么换**：Spring Cloud 的部署单元是「可部署服务」；go-zero 的 api/rpc 分离源于 `.api` 与 `.proto` 两条 codegen 路径，是**语言产物**不是架构原则。
⇒ **代价**：域的独立扩容能力消失；且**合并后同一份业务逻辑要能同时产出 HTTP 与 gRPC 两种错误表达** ⇒ 引入统一错误模型。

**换错误表达（双返回 → throw + Advice）4 处同时改**：
1. 删 `LogicResult.ok/fail` 双返回写法
2. 业务层改抛 `BusinessException(code,msg)`
3. `advice/GlobalExceptionHandler` → `advice/UserApiExceptionHandler` + `AdminApiExceptionHandler`（各域一份）
4. gRPC 边界由 `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration` 转 `StatusRuntimeException`

**为什么换**：`LogicResult` 是 go-zero 的语言产物；异常流是 Java 惯用形态，且能让 HTTP / gRPC 两个边界共享同一份业务错误定义。

---

## 4. 公共模块对照

> ⚠️ **RAG-V1 的 `common/` 在仓库根，不在 `service/` 下** ⇒ 下表**只点名与 users 域相关的件并说明归属**，
> 完整清单由 **[`Mapping_Table/common.md`](../common.md)** 负责。

| 能力 | A Go 出处 | B RAG 位置 | C RAG-V1 位置 | 归属说明 |
|---|---|---|---|---|
| 统一响应信封 | `service/common/response/response.go` | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | **全局归属** ⇒ `common.md` 负责 |
| 业务异常 | —（Go 用 `errmsg.NewGrpcErr(code,msg)`） | `service/common/.../exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | **全局归属** ⇒ `common.md` 负责 |
| 认证异常 | — | `service/common/.../exception/AuthenticationException.java` | `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | **全局归属** ⇒ `common.md` 负责 |
| 雪花 ID | `service/common/snowflake/snowflake.go` | `service/common/.../snowflake/Snowflake.java` + `spring/SnowflakeBean.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | **全局归属** ⇒ `common.md` 负责 |
| MP 自动配置排序桥（Boot 4） | — | 9 份 `MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`（**1 份**） | **全局归属**（D3）⇒ `common.md` 负责 |
| jsonb / text TypeHandler | —（GORM `serializer:json`） | 3 份（user-rpc ×1 + admin-rpc ×2） | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` + `JsonbTypeHandler.java` | **全局归属**（D3）⇒ `common.md` 负责 |
| 社区事实协议（`communityfact`） | `service/common/communityfact/` | `service/common/.../communityfact/` | `common/src/main/java/com/rag/common/communityfact/` | **全局归属**；users 域**不涉及**（comment/favorite/like 三域共享）⇒ `common.md` 负责 |
| HTTP 超时 / 请求 deadline | go-zero `rest/config.go:55` `Timeout` default 3000ms + `NewHTTPMiddleware` | `service/common/.../observability/GoStyleRequestDeadline.java` + `ObservabilityProperties.java`（`observability.http-timeout: 3s`） | `common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java` + `ObservabilityProperties.java`（**空壳**） | **全局归属**；⚠️ C 侧两个 `application.yml` 无 timeout 配置 ⇒ **未落点**（F-8）⇒ `common.md` 负责 |

> ⚠️ **本表已按子域拆分** —— **users-common 私有件的 7 行**（错误码/文案 · JWT 签发验签 · `Bearer` 前缀提取 · 密码散列 · go-zero 风格 JSON 强转 / `string→int64` · go-zero 风格必填校验 · DDL 幂等重放执行器）见 [`users-common.md`](./users-common.md) §4；
> 其余 8 行为全局归属 / 跨子域行，完整保留在本文件。

**决策锁**

> ⚠️ **本节的决策锁（换共享层归属 4 处同时改）见 [`users-common.md`](./users-common.md) §4** —— 那 4 处改的全是 `users-common` 的文件，故与该表同处一份文档。

---

## 5. 决策锁（汇总）

> 每条决策的完整依据与代价见 [`../决策记录.md`](../../决策记录.md)（D1–D6 已定 / P1–P6 待拍板）。
> 本节只列**影响 users 域文件树**的决策，及其在本表中的落点。

| 决策 | 内容 | 在 users 域的落点 | 对本表的影响 |
|---|---|---|---|
| **D1** | api 与 rpc 合并为一个 app（一域一进程） | `service/users/user/`（43 个 `.java`）+ `service/users/admin/`（56 个） | **C 列大量 `N:1` 的主因**：B 的 4 个 app 收敛成 2 个 |
| **D2** | 域内调用直连，不再自我 gRPC 往返 | 两侧均**无** `api/client/` | B 的 `UserRpcClient` / `AdminRpcClient` 在 C 侧消失 |
| **D3** | `service/common` 升级为真 starter | 仓库根 `common/`（7 个 auto-config） | B 的 `users-common` 里 4 个文件上提到 `common/`；9 份 MP bridge 收成 1 份 |
| **D4** | 生态优先，手写只补生态缺口 | — | users 域无手写熔断/日志/校验器 ⇒ 本表无相关行 |
| **D5** | DTO 改 immutable record + Bean Validation | `api/dto/*` 共 47 个 record（user 19 + admin 28） | B 的 `GoStyleRequired*` 4 个校验器在 C 侧删除 ⇒ **L3 降级 D-01** |
| **D6** | 跨域调用仍走 gRPC | users 域**无跨域调用** | C 侧 users 无 `rpc/client/` |
| **P1** | 错误文案保真度（待拍板） | `users/common/ErrMsg.java` + `service/users/user/src/main/java/com/rag/user/contract/ErrMsg.java` | 决定 `GetErrMsg(code)` 的文案是否逐字照抄 Go |
| **P4** | `rpc.*` 包是否改名（待拍板） | `rpc/` 包下的 21 个文件（user 14 + admin 14 中的 rpc 部分） | 改名会波及本表 C 列全部 `rpc/` 路径 ⇒ **本表不动** |

**为什么换（总述）**：Go 的 api/rpc 分离、go-zero 的内置中间件链、双返回错误模型，都是**语言产物**；
RAG-V1 用 Spring 生态的原生形态（`@RestControllerAdvice` / Bean Validation / starter / Micrometer）重新表达同一份行为 ⇒ **文件名与职责必然重组，但 L1 契约（proto 字段号 / 错误码 / JWT claims / 表名 / HTTP path）不动**。

---

## 6. 跨模块全局约定（值逐条核对 A 列 Go 源码）

> **本节的值全部来自 A 列 Go 源码**，不来自 B 列，也不来自 RAG-V1 的文档。

| 项 | 值 | A 列出处（`file:line` 级可 grep） |
|---|---|---|
| **proto 字段号** | `user.proto`：`UserInfo{uid=1,score=2,username=3,email=4,extra_info=5,status=6(optional)}`；`LoginReq{username=1,password=2}`；`LoginResp{uid=1,status=2}`；`UploadAvatarReq{uid=1,avatar_url=2,content_type=3,size_bytes=4}`；`AvatarHistoryItem{id=1,avatar_url=2,content_type=3,size_bytes=4,is_current=5,create_time=6}`；`GetAvatarHistoryReq{uid=1,limit=2}`。`admin.proto`：`AdminInfo` / `UserInfo` / 13 个 req/resp | `proto/user.proto`、`proto/admin.proto`（C 侧 `proto/src/main/proto/users.proto`、`proto/src/main/proto/admin.proto` 逐字复制） |
| **gRPC 方法** | user **9** 个（`GetUser` / `Register` / `Login` / `Logout` / `UpdateUser` / `UploadAvatar` / `GetAvatarHistory` / `SelectAvatar` / `DeleteUser`）；admin **13** 个（`GetUser` / `GetUserList` / `GetSelf` / `CreateAdmin` / `CreateInviteCode` / `Login` / `Logout` / `DeleteUser` / `ResetUserPassword` / `UpdateSelf` / `UpdateUser` / `BanUser` / `UnBanUser`） | `proto/user.proto` `service UserService`、`proto/admin.proto` `service AdminService` |
| **错误码取值** | 通用段 `5001`–`5010`（`ErrorServerCommon`…`ErrorDelayMsg`）+ `1015`（`CodeServerBusy`）+ `200`（`Success`）/ `500`（`Error`）；用户段 `1001`–`1012`、`1016`–`1018`；积分段 `3001`–`3003` | `service/user/common/errmsg/errmsg.go`（`const` 块） |
| **错误文案** | **中文**：`"服务繁忙"` / `"系统内部错误"` / `"用户名已存在"` / `"用户不存在"` / `"权限不足"` / `"管理员邀请码错误"` / `"积分余量不足"` 等 | 同上 `codeMsg` map |
| **错误文案缺口** | ⚠️ `ErrorRequestParam = 1017` **在 `const` 块里但不在 `codeMsg` map 里** ⇒ `GetErrMsg(1017)` 返回 `"FAIL"`（`codeMsg[Error]`） | 同上（`GetErrMsg` 的 fallback） |
| **JWT claims** | `exp`（= iat + seconds）、`iat`、`userId`（**int64**）；签名算法 **HS256** | `service/user/common/jwt/jwt.go`（`GetToken`） |
| **JWT secret（分域两套）** | user/api 用 `Config.UserAuth.AccessSecret`；admin/api 用 `Config.AdminAuth.AccessSecret` | `service/user/user/api/internal/handler/routes.go`（`rest.WithJwt`）、`service/user/admin/api/internal/handler/routes.go` |
| **表名** | `users`（user + admin 共用）、`user_avatar_history`、`admins`、`admin_invites` | `service/user/user/rpc/internal/model/data_model.go`（`TableName()`）、`service/user/admin/rpc/internal/model/data_model.go` |
| **HTTP path** | user：`/usercenter/v1/user/{login,register,delete,get,avatar/history,logout,avatar,avatar/select,update}`；admin：`/admincenter/v1/admin/{create,login,banuser,delete,invite/create,getself,getuser,getuserlist,logout,resetuserpassword,unbanuser,update,updateuser}` | `service/user/user/api/internal/handler/routes.go`、`service/user/admin/api/internal/handler/routes.go` |
| **HTTP method** | user `Get2·Post6·Delete1`（9）；admin `Get3·Post9·Delete1`（13） | 同上（`http.MethodGet` / `http.MethodPost` / `http.MethodDelete`） |
| **public vs JWT 划分** | user：public = `login` + `register`（第一组 `AddRoutes` 无 `WithJwt`）；admin：public = `create` + `login` | 同上 |
| **指标完整名** | `user_rpc_register_total`、`user_rpc_login_total`、`user_api_intercept_total`、`admin_rpc_login_total`、`admin_rpc_high_risk_action_logal`（**`logal` 是拼写错误，照抄**） | `service/user/{user/rpc,user/api,admin/rpc,admin/api}/internal/metrics/metrics.go` |
| **Kafka topic** | **无** —— `service/user/` 内**零** Kafka 生产/消费（只有 `errmsg.go` 里两个错误码常量 `ErrorKafkaPush` / `ErrorDelayMsg` 与文本） | `service/user/` 全目录 grep `kafka` 仅命中 `errmsg.go` |

> ⚠️ **本表已按子域拆分** —— **user 子域专属的 3 行**（JWT claim 的唯一来源 / 身份常量 / 列约束（users））见 [`user/user.md`](./user/user.md) §6；
> **admin 子域专属的 2 行**（列约束（admins / admin_invites）/ 默认管理员种子）见 [`admin/admin.md`](./admin/admin.md) §6。

---

## 7. 覆盖度自查（域级汇总）

### 7.1 C 列覆盖率 —— **103 / 103 = 100%**

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**（逐模块表已按子域拆到三份子文档，此处只留合计行）：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| **合计** | **103** | — | ✅ **103/103** |

> ⚠️ **逐模块实测表的落点**：user 子域 2 行见 [`user/user.md`](./user/user.md) §7.1 · admin 子域 2 行见 [`admin/admin.md`](./admin/admin.md) §7.1 · users-common 1 行见 [`users-common.md`](./users-common.md) §7.1。

> **唯一文件名数 85**（`WebMvcConfig.java` 出现 2 次、`LoginReq/Resp`、`LogoutReq/Resp`、`GetUserReq/Resp`、`DeleteUserReq/Resp`、`UpdateUserReq/Resp`、`UserInfo` 等在两个子域各有一份）。
> 逐条比对命令（**已实跑，输出为空 ⇒ 零遗漏**）：
> ```bash
> # 本表 C 列出现过的文件名（去重）—— 四份文档合起来
> cat docs/Mapping_Table/users/_shared.md \
>     docs/Mapping_Table/users/users-common.md \
>     docs/Mapping_Table/users/user/user.md \
>     docs/Mapping_Table/users/admin/admin.md \
>   | grep -oE '[A-Za-z0-9]+\.java' | sort -u > /tmp/doc_c.txt
> # 实际 C 列文件
> find service/users -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
>   | xargs -n1 basename | sort -u > /tmp/fs_c.txt
> comm -23 /tmp/fs_c.txt /tmp/doc_c.txt   # ⇒ 实测输出为空
> ```
>
> 📌 **口径说明**（勿把两侧数字当成必须相等）：`fs_c.txt` 是**文件系统侧唯一文件名 85**，
> `doc_c.txt` 是**文档里出现过的唯一文件名 151** —— 文档含 A/B/C 三列，同一个 `.java` 名会重复出现。
> `comm -23` 判的是**单向包含**（fs 的 85 个是否都在文档里），不是**数量相等**。
> ⇒ **85 ⊆ 151 ⇒ 输出为空 ⇒ 零遗漏。**

### 7.2 A 列覆盖率 —— **114 / 114 = 100%**

| Go 子目录 | 非测试 `.go` 数 | 本表覆盖位置 | 覆盖 |
|---|---|---|---|
| **合计** | **114** | — | ✅ **114/114** |

> ⚠️ **逐目录实测表的落点**：user 子域 3 行见 [`user/user.md`](./user/user.md) §7.2 · admin 子域 2 行见 [`admin/admin.md`](./admin/admin.md) §7.2 · users-common 1 行见 [`users-common.md`](./users-common.md) §7.2。

> 自查命令（**已实跑，输出为空 ⇒ 零遗漏**）：
> ```bash
> find "/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/user" \
>   -name '*.go' -not -name '*_test.go' | wc -l   # ⇒ 114
>
> # 逐条比对（四份文档合起来的 A 列文件名 vs Go 侧实际）
> find .../service/user -name '*.go' -not -name '*_test.go' | xargs -n1 basename | sort -u > /tmp/fs_a.txt
> cat docs/Mapping_Table/users/_shared.md \
>     docs/Mapping_Table/users/users-common.md \
>     docs/Mapping_Table/users/user/user.md \
>     docs/Mapping_Table/users/admin/admin.md \
>   | grep -oE '[A-Za-z0-9_]+\.pb\.go|[a-z0-9_]+\.go' | sort -u > /tmp/doc_a.txt
> comm -23 /tmp/fs_a.txt /tmp/doc_a.txt   # ⇒ 实测输出为空
> ```
> 📌 **同上口径**：`fs_a.txt` = Go 侧唯一文件名 **67**（Go 侧同名文件跨目录也存在，如 `helper.go`），
> `doc_a.txt` = 文档侧唯一文件名 **71** ⇒ **67 ⊆ 71 ⇒ 零遗漏**；Go 侧**总 `.go` 数 114**（去重前）才是 §7.2 的分母。
>
> **另有 12 行 A 列为「—（Go 侧无对应物：…）」**（admin 子域 5 行 —— `1:1` × 3 + `1:N` × 2；
> users-common 公共库 7 行 —— 全部 `1:1`），类型分布 = **1:1 × 10 + 1:N × 2**，每行都在备注里写明了原因。
> ⚠️ **本表拆分后的落点**：admin 子域补充行见 [`admin/admin.md`](./admin/admin.md) §1 · users-common 见 [`users-common.md`](./users-common.md) §1。
>
> **另有 6 行 A 列是具体 Go 文件、但 B/C 侧无对应物**（脚本数出，逐条如下）——
> `svc/servicecontext.go` ×**1**（仅 `user/rpc` 那行；`user/api` 与 `admin/api` 的 `svc/servicecontext.go` **在 C 侧都有对应物**，
> `admin/rpc` 的被 `AdminSeederBootstrap` 承接）· `pb/points.pb.go` ×1（Go 侧误置的 points 生成物）·
> `userservice.go` / `adminservice.go` ×2（go-zero 生成的 gRPC 接口，「无独立文件」）·
> `identity/resolve.go` ×2（api 侧 + 跨进程共享件，**C 侧无任何承载** ⇒ 见 §7.4 F-1）。
> 每行备注都写明了「Go 侧无对应物（原因）」。

### 7.3 映射类型分布

> 统计口径：§1 全部映射表的**第 4 列**（不含 §2/§3/§4 的差异对照表）。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **43** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」）；其中 **10 行** A 列为「Go 侧无对应物」（B 引入的横切件） |
| `1:N` | **15** | Go 单文件拆成多个 C 文件 —— 逐条实测：装配拆分 **2** 行（`user.go` / `admin.go`）· Entity/Mapper 拆分 **4** 行（user 与 admin 各 1 组）· 路由拆分 **2** 行（`routes.go` → Controller + `WebMvcConfig`）· `types.go` → DTO 拆分 **2** 行（user **19** 个 + admin **28** 个 immutable record）· `errmsg.go` → 4 个错误契约类 **1** 行 · api 侧 `svc/servicecontext.go` 拆分 **2** 行 · Go 侧无对应物（`@RestControllerAdvice` 响应信封 / 异常出口）**2** 行 = **15** |
| `N:1` | **69** | Go 多文件合进 1 个 C 文件（**D1/D2 的主因**：user-api 9+9、admin-api 13+13、user-rpc 9、admin-rpc 13 个 logic → 各 1 个 Service） |
| `C 新增` | **0** | users 域每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本域是**重组**而非新增能力 |
| **合计** | **127** | — |

> 统计命令（可复跑）：
> ```bash
> cat docs/Mapping_Table/users/users-common.md \
>     docs/Mapping_Table/users/user/user.md \
>     docs/Mapping_Table/users/admin/admin.md \
>   | awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' \
>   | awk -F'|' '/^\|/{t=$5; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 43 1:1 / 15 1:N / 69 N:1
> ```
>
> ⚠️ **逐子域分布见** [`user/user.md`](./user/user.md) §7.3 · [`admin/admin.md`](./admin/admin.md) §7.3 · [`users-common.md`](./users-common.md) §7.3。

### 7.4 三列之间「职责对不上」的地方（⚠️ 需处理）

> 以下 8 条是本表在三方对照中**实际发现**的职责缺口 / 不一致。**它们不是行为结论**，只是「文件名 / 职责落点」层面的问题。

| # | 发现 | 影响面 | 建议落点 |
|---|---|---|---|
| **F-1** | 🔴 **Go 的身份解析器在 C 侧无承载**。`service/user/user/identity/resolve.go`（权威解析器：`ClaimedUID` → `GetUser` → status presence 检查 → `SubjectRef`；`AuthorityID="rtw.identity"` / `PlatformTenantID="platform"`；5 个 sentinel 错误 `ErrInvalidClaim` / `ErrUserNotFound` / `ErrIdentityMismatch` / `ErrUserInactive` / `ErrStatusUnavailable`）在 B 侧有 `IdentityResolver.java`（user-api 与 admin-api **各一份，共两份重复**），在 **C 侧零文件**（全仓 grep `IdentityResolver` / `rtw.identity` / `AuthorityID` 在 `service/users/**` 下**零命中**） | L1（`AuthorityID` / `PlatformTenantID` / `SubjectRef` 形状）+ L2（5 个错误的判定顺序） | C 侧需补一个身份解析落点（建议：user 域一份权威实现 + admin 域复用，**不要像 B 那样复制两份**）；落点应在 `user-service` 的 `api/service/` 或 `rpc/service/` |
| **F-2** | 🟠 **错误契约在两个子域间仍然不对称（2026-10-26 D7 后收敛为「admin 无 `contract/` 包」）**。C 侧 `service/users/user/src/main/java/com/rag/user/contract/` 有 `ErrCodes.java` + `ErrMsg.java`；**admin 侧根本没有 `contract/` 包**（`admin-contract` 模块已随 D7 删除）。而 Go 侧 `service/user/common/errmsg/errmsg.go` 是 **user / admin 四方共用**的一份；RAG-V1 把它落在**公共库** `service/users/common/src/main/java/com/rag/users/common/`（`ErrCodes.java` + `ErrMsg.java`，artifactId `users-common`） | 若 admin 域要独立引用错误契约，须显式取公共库那份（`users-common` 是 admin 的 pom 依赖，**已接上**）；但 admin 源码**当前零引用** `ErrCodes` / `ErrMsg` | 明确 admin 域的错误码取「公共库 `users-common` 那份」还是「user 子域私有那份」；取前者则**不要**在 admin 侧复制一份（逐域隔离 ≠ 逐子域复制）。⚠️ **时点标注**：本条原文写的是「`admin/contract/` 一份都没有（该模块只有 `admin.proto`）」—— 那是 D7 之前的状态 |
| **F-3** | 🔴 **幂等 DDL 重放机制在 C 侧无执行器、无脚本**。B 侧有 `users-common/.../ddl/SchemaInitializer.java` + `db/01..04_*.sql`；C 侧 `common/` **无 `SchemaInitializer`**，两个 `*-service` 的 `resources/db/migration/` **只有 `.gitkeep`** ⇒ `UserSchemaBootstrap` / `AdminSchemaBootstrap` 无处可重放 | Go 的 `AutoMigrate` 是**无状态幂等**的（L2 语义：谁先启动都行） | C 侧需补执行器（建议归 `common/src/main/java/com/rag/common/persistence/`）+ 4 个幂等 SQL 脚本（`CREATE TABLE IF NOT EXISTS`） |
| **F-4** | 🟡 **表名在 A 与 B/C 之间不同**。Go 的 GORM `TableName()` 是 `users` / `user_avatar_history` / `admins` / `admin_invites`；B/C 用 `users_user` / `users_user_avatar_history` / `admin_admin` / `admin_invite` | 表名是 L1 级可见面（客户端直查、报表、运维脚本都依赖） | 本表**不判**行为；该差异的现状与证据看 `RAG/docs/DRIFT/users/`。若 RAG-V1 要保留 B 的表名，需在 `users-common/ErrMsg` 之外**单独留档**（否则下一棒会把它当成"Go 就长这样"） |
| **F-5** | 🟡 **C 的 HTTP DTO 命名与 Go `.api` 有 1 处不一致（user）/ 1 处改进（admin）**。① user 侧：Go `.api` 是 `AvatarHistoryResp`，C 是 `GetAvatarHistoryResp`（取了 proto 的名）；**同时** B 的 `RegisterReq/RegisterResp` 在 C 改成了 `CreateUserReq/CreateUserResp`（**这个改对了**，Go `.api` 就是 `CreateUserReq`）。② admin 侧：Go `.api` 是 `CreateInviteReq/Resp`，C 也是 `CreateInviteReq/Resp`（**改对了**），而 B 是 `CreateInviteCodeReq/Resp`（取了 proto 的名） | DTO 名 = HTTP 契约的一部分 | user 侧的 `GetAvatarHistoryResp` 与 Go `.api` 的 `AvatarHistoryResp` 不一致，需确认取哪个为准并统一 |
| **F-6** | 🟡 **C 的 `UserController` / `AdminController` javadoc 里的 method 与端点清单，与 A、B 都不一致**。javadoc 写「Go 侧 method 分布：DELETE, GET, POST, PUT」并列出 `PUT /user/v1/updateuser`、`GET /user/v1/avatarhistory`、`GET /admin/v1/getself` 等；而 Go 源码（`routes.go`）**users 域零个 PUT**，user 是 `Get2·Post6·Delete1`、admin 是 `Get3·Post9·Delete1`；B 的两个 Controller 也**全是 `@PostMapping`** | 这两条 javadoc 是 RAG-V1 内部的**自相矛盾**：同一仓的 `File_Tree/users/user/user-service.md` 写「method 是签字偏离：Go Get2·Post6·Delete1 ⇒ 本仓库统一 POST」 | 以 A 列 `routes.go` 为准修正 javadoc；否则下一棒填实现时会把 `PUT` / `GET` 当成契约写进去 |
| **F-7** | 🟡 **C 的 `AdminController` javadoc 少列一个端点**。写「端点数：12」，实际 Go `.api` / `routes.go` 是 **13** 个（漏了 `updateuser`） | 漏项 = 少一个端点的契约 | 补 `POST /admin/v1/updateuser`（Go：`/admincenter/v1/admin/updateuser`） |
| **F-8** | 🟡 **HTTP 超时机制在 C 侧未落点**。Go 的 go-zero `rest/config.go:55` `Timeout` `json:",default=3000"` ⇒ **3000ms**（且 `DisableNativeRestTimeout` 已关掉原生超时中间件，超时只有 `NewHTTPMiddleware` 一个执行者）；B 侧两侧都显式配了 `observability.http-timeout: 3s` + `service/common/.../observability/GoStyleRequestDeadline.java`；**C 侧 `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` 与 `GoStyleRequestDeadline.java` 两个都是空壳，且两个 `application.yml` 无任何 timeout 配置** | 超时是 L3；B 的实测差异（Go fail-open 200 / RAG 503）正是超时 + 断连行为共同作用的结果 | 实现阶段需在 `common/src/main/java/com/rag/common/observability/` 落 deadline 执行器并在两个 `application.yml` 显式配 3000ms，同时保留 `RedisDisconnectedBehaviorConfig` 的 fail-open 语义核对 |

---

## 8. 一句话总结

**A 列 Go**（`service/user/`，114 个非测试 `.go`）= 单一真源；
**B 列 RAG**（`service/users/`，141 个 `.java`）= Go 部署拓扑（api/rpc 分离）的 Spring 复刻；
**C 列 RAG-V1**（`service/users/`，103 个 `.java`）= Spring 惯用目标形态（一域一 app）。

⇒ **C 列 103/103 覆盖（85 个唯一文件名零遗漏）、A 列 114/114 覆盖（67 个唯一文件名零遗漏）、映射类型 1:1 ×43 / 1:N ×15 / N:1 ×69 / C 新增 ×0（共 127 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 A 列 Go 源码为准，状态看 `RAG/docs/DRIFT/users/`。**
⇒ **本表发现的 8 条职责缺口（F-1 ~ F-8）** 里，**F-1（身份解析器无落点）** 与 **F-3（DDL 重放无执行器/脚本）** 是硬缺口，填实现前应先补。
