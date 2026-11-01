# Mapping_Table/users/_shared.md — users 域共享层（两方文件名对照）

> **本表目的**：把 **RAG**（B 列，**唯一真理之源**）→ **RAG-V1**（C 列，Spring 惯用目标形态）两棵树的 **users 域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 该域路径 | 角色 |
> |---|---|---|---|
> | **B（RAG，唯一真理之源）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/` | **唯一行为判定基准**（D16 · 2026-11-02 用户定） |
> | **C（RAG-V1，本仓目标）** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/users/` | Spring 惯用目标形态（api/rpc 合并为一域一 app） |
>
> **格式契约**：[`README.MD`](../README.MD) —— 5 节模板 + 4 个映射类型词 + 覆盖度自查法。
> **同域前身**：`RAG/docs/MappingTable/users.md`（更早一版的两方镜像对照）。
>
> ⚠️ **本文件是 users 域的「域级共享层」** —— 原单文件 `users/users.md`（515 行）按子域拆成四份：
> 本文件（域级共享）+ [`users-common.md`](./users-common.md)（公共库）+ [`user/user.md`](./user/user.md)（user 子域）+ [`admin/admin.md`](./admin/admin.md)（admin 子域）。
>
> **拆分规则**：§1 包结构对照按子域各归一份（§1.1 → user · §1.2 → admin · §1.3 → users-common）；
> §2~§6 的表按「**整行归属**」拆 —— 跨子域的行留在本文件、子域专属的行归到该子域；
> §7 的逐模块实测表按子域各归一份（合计行留在本文件）；§7.3 缺口清单 F-1~F-8 与 §8 总结留在本文件。
> ⇒ **每行只出现在一处，不复制**（否则覆盖度会重复计数）。

### 导航

| 文档 | 装什么 | B 侧出处 |
|---|---|---|
| **[`_shared.md`](./_shared.md)（本文件）** | 域级共享层：§0 读表须知 · §1 目录形态决策锁 · §2 持久层差异 · §3 HTTP·DI 差异 · §4 公共模块对照 · §5 决策锁（汇总）· §6 跨模块全局约定 · §7 域级汇总 + 缺口清单 F-1~F-8 · §8 总结 | `service/users/`（全域） |
| [`users-common.md`](./users-common.md) | users-common 公共库 | `service/users/users-common/` |
| [`user/user.md`](./user/user.md) | user 子域 | `service/users/user/` |
| [`admin/admin.md`](./admin/admin.md) | admin 子域 | `service/users/admin/` |

---

## 〇、读表须知

### 0.1 三条不可违反的纪律（照抄 [`README.MD`](../README.MD) §七）

1. ⚠️ **B 列是唯一行为判定基准**（D16 · 2026-11-02 用户定）—— RAG 侧 `users` 域自身已被判定 `NOT EQUIVALENT`（见 `RAG/docs/DRIFT/users/`）
   ⇒ **该差异一律继承，不回溯更早的历史版本**；本表只回答「文件名 / 职责的对应关系」。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**不下**「已对齐 / 未对齐」结论；行为状态看 `RAG/docs/DRIFT/users/` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 `.java` 目前只有 package + 空类型 + TODO javadoc。
   **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

### 0.2 本表的边界

- 本表覆盖 **users 一个域、两个子域**（`user` / `admin`）+ 域内公共库 `users-common`。
  ⇒ **`users-common` 是公共库，不是第三个域。**
- RAG-V1 的**顶层 `common/`（starter）在仓库根，不在 `service/` 下** ⇒ 其文件清单由 **`Mapping_Table/common.md`** 负责。
  本表只在 [§4 公共模块对照](#4-公共模块对照) 点名 users 相关的件并说明归属，**不把 common 的文件全列进本表**。
- `.proto` / `.sql` 不是 `.java`，但它们是 L1/L2 契约载体 ⇒ 在 §1 随所属模块一并列出。
- **B 侧与 C 侧都是 `service/users/`（复数）** ⇒ 两方路径同构；差别在**包树形状**（B 侧是 `api/` + `rpc/` 两棵，C 侧已由 D7 压平为单层技术角色包树）⇒ 见 §0.5。

### 0.3 映射类型与「B 侧无对应物」的写法

| 映射类型 | 判据 |
|---|---|
| `1:1` | 1 个 B 文件 → 1 个 C 文件；**或** B 有对应物、C 侧以另一形态（改名 / 归位）落位（原因写在备注） |
| `1:N` | 1 个 B 文件 → N 个 C 文件 |
| `N:1` | N 个 B 文件 → 1 个 C 文件 |
| `C 新增` | B 侧无对应物，是 RAG-V1 形态本身需要的新文件 |

> **本域 `C 新增` = 0 行**（依据见 [§7.2](#72-映射类型分布)）：users 域的每个 C 文件都能在 B 列找到前身
> ⇒ RAG-V1 在本域是**重组**（合并 api/rpc、拆分错误契约、共享层上提），**不是新增能力**。

### 0.4 B/C 规模基线（实测）

| 侧 | 计量口径 | 数量 |
|---|---|---|
| B（RAG） | `service/users/**/*.java`，排除 `src/test/`、`target/` | **145** |
| C（RAG-V1） | `service/users/**/*.java`，排除 `src/test/`、`target/` | **104**（86 个唯一文件名） |

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
| `users/users-common/`（`com.rag.users.common.{cryptx,ddl,errmsg,http,jwt}`） | `service/users/common/`（artifactId `users-common`）<br>包 `com.rag.users.common` **仅剩 `ErrCodes` + `ErrMsg` + `IdentityResolver`** | 公共库保留；**`jwt` / `cryptx` / `http` / `ddl` 四个子包已上收 `common/`**（D3）|
| `users/user/api/` | `service/users/user/` → `com.rag.user`（`UserServiceApplication.java`） | 入口类合并（D1）|
| `users/user/api/{advice,auth,config,controller,dto,interceptor,metrics,service}` | `advice/` / `config/` / `controller/` / `dto/` / `interceptor/` / `metrics/` / `service/` | 直接压平（`auth/` 4 个 JWT 验签类收进 `interceptor/`，D2）|
| `users/user/rpc/{bootstrap,config,domain,exception,mapper,metrics,service}` | `wiring/` / `config/` / `domain/entity/` / `exception/` / `mapper/` / `metrics/` / `service/` + `domain/service/` | 直接压平；领域服务另出 `domain/service/` |
| `users/user/rpc/service/impl/` | `service/users/user/…/grpc/` | 压平 + 类改名 `UserServiceGrpcImpl` → `UserGrpcService` |
| `users/user/`（无 `users-common` 内的 errmsg 副本） | `service/users/user/…/contract/`（`ErrCodes` + `ErrMsg`） | user 子域**私有**错误契约 |
| `users/user-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.user`）| **集中化**（D7）|
| `users/admin/api/` | `service/users/admin/` → `com.rag.admin`（`AdminServiceApplication.java`） | 入口类合并（D1）|
| `users/admin/api/{advice,auth,config,controller,dto,interceptor,metrics,service}` | `advice/` / `config/` / `controller/` / `dto/` / `interceptor/` / `metrics/` / `service/` | 直接压平（`auth/` 4 个 JWT 验签类收进 `interceptor/`，D2）|
| `users/admin/rpc/{bootstrap,config,domain,exception,mapper,metrics,service}` | `wiring/` / `config/` / `domain/entity/` / `exception/` / `mapper/` / `metrics/` / `service/` + `domain/service/` | 直接压平 |
| `users/admin/rpc/service/`（**RAG 侧 admin 没有 `impl` 子包**） | `service/users/admin/src/main/java/com/rag/admin/grpc/` | RAG 侧 admin 的 `rpc/service/` 下**没有 `impl` 子包**；C 侧 gRPC 实现由 `AdminGrpcService` 单独承载 |
| **—（admin 无 contract 模块）** | **—（admin 侧无 `contract/` 包）** | ⚠️ admin 若要引用错误契约，取公共库 `service/users/common/` 那份（F-2）|
| `users/admin-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.admin`）| **集中化**（D7）|

## 1. 包结构对照（域级）

> ⚠️ **§1（包结构对照）已按子域拆到三份子文档** —— 本节只留**跨子域**的目录形态决策锁与导航。

| 子域 | 文档 | B 侧出处 |
|---|---|---|
| user 子域 | [`user/user.md`](./user/user.md) §1 | `service/users/user/` |
| admin 子域 | [`admin/admin.md`](./admin/admin.md) §1 | `service/users/admin/` |
| users-common 公共库 | [`users-common.md`](./users-common.md) §1 | `service/users/users-common/` |

### 1.1 §1.1~1.3 的决策锁

**换目录形态（api+rpc 合并 + 契约拆分 + 共享层上提）8 处同时改**：
1. `service/users/user/user-api/` + `user-rpc/` 两个模块 → `service/users/user/` 单模块（pom 合一、`@SpringBootApplication` 合一）
2. `service/users/admin/admin-api/` + `admin-rpc/` 两个模块 → `service/users/admin/` 单模块
3. `service/users/user/user-proto/` → 根级 `proto/`（+ `admin-proto` → 根级 `proto/`）；错误契约 → `service/users/user/src/main/java/com/rag/user/contract/`
4. B 的 `users-common/errmsg/ErrorCode.java`（1 个类）→ C 的 `users/common/ErrCodes.java` + `ErrMsg.java` + `service/users/user/src/main/java/com/rag/user/contract/ErrCodes.java` + `ErrMsg.java`（4 个类）
5. B 的 `users-common/jwt/` + `cryptx/` → C 的 `common/src/main/java/com/rag/common/security/`（上提到仓库根，starter 化）
6. B 的 `users-common/http/GoStyle*`（4 个校验器）→ 删除，改 Bean Validation（D5）
7. B 的 `users-common/ddl/SchemaInitializer.java` + 各 rpc 的 `*SchemaBootstrap.java` → C 只保留 `*SchemaBootstrap.java`，**执行器与脚本缺失**（待补）
8. B 侧的 MP 自动配置排序桥（**曾有 9 份域内副本，现已收进共享层 `service/common/` 的 1 份**）→ C 的 1 份 `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`

**为什么换**：Spring Cloud 的部署单元是「可部署服务」而不是「api 层 / rpc 层」（D1）；共享能力的标准形态是 starter 而不是「普通 jar + 每个消费者手写 `@Import`」（D3）；Java 生态的数据契约形态是 immutable record + Bean Validation（D5）。
⇒ **代价**：C 侧 B 的 145 个 `.java` 收敛成 104 个，且**「有对应」不等于「行为等价」** —— 本表只记文件名与职责的对应关系。

---

## 2. 持久层差异

| 维度 | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|
| ORM | MyBatis-Plus | MyBatis-Plus（`mybatis-plus-spring-boot3-starter`） | B/C 同构，表达式层差异不产生行为分叉 |
| 实体 | `@TableName` + `@TableId(type=ASSIGN_ID)` Entity | 同 B | — |
| 数据访问 | `Mapper extends BaseMapper<Entity>` + `LambdaQueryWrapper` | 同 B | 按实体拆 Mapper（B/C 同构） |
| **表名** | `users_user`、`users_user_avatar_history`、`admin_admin`、`admin_invite` | 同 B（`@TableName`） | ⚠️ **表名是 L1 级可见面**（客户端直查 / 报表 / 运维脚本都依赖）—— 见 §7.3 F-4；本表只记职责对应，不判行为 |
| Schema 演进 | `SchemaInitializer` 幂等重放 `db/01..04_*.sql`（2026-10-21 起弃用 Flyway） | `*SchemaBootstrap` + `db/migration/`（**仅 `.gitkeep`**） | ⚠️ C 侧脚本与执行器缺失（F-3） |
| 事务 | `@Transactional`（独立 Bean，规避 self-invocation） | 同 B | `selectAvatar` / `SelectAvatarHistory` / `CreateAdmin` 是多步原子写 ⇒ **必须独立 Bean** |
| 雪花 ID | `common/spring/SnowflakeBean` + 各 rpc 的 `SnowflakeBootstrap` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` + 各 service 的 `SnowflakeBootstrap` | epoch / 位段是业务契约（like 域游标依赖 `>>22`） |
| jsonb / text 列 | `JsonTextTypeHandler`（user-rpc，text）+ `JsonbTypeHandler`（admin-rpc，jsonb） | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` + `JsonbTypeHandler.java` | ⚠️ `LambdaUpdateWrapper.set(Map)` 会丢 TypeHandler ⇒ 需 `setSql("CAST(... AS jsonb)")` 或走实体更新 |

> ⚠️ **本表已按子域拆分** —— **admin 子域专属的 2 行**（行级悲观锁 / 唯一约束判定）见 [`admin/admin.md`](./admin/admin.md) §2；
> 其余 8 行为跨子域行（同一行同时写 user 与 admin），完整保留在本文件。

**决策锁**

**换持久层命名空间（B 的 Entity/Mapper 位置 → C 的技术角色包）6 处同时改**：
1. B 的 `rpc/domain/*Entity.java` → C 的 `domain/entity/*Entity.java`（`@TableName` + 列名逐条对齐）
2. B 的 `rpc/mapper/*Mapper.java` → C 的 `mapper/*Mapper.java`（按实体拆，位置归位）
3. B 的 `rpc/bootstrap/*SchemaBootstrap.java` → C 的 `wiring/*SchemaBootstrap.java`（幂等 DDL 重放）
4. B 的 `rpc/exception/*Exceptions.java` → C 的 `exception/*Exceptions.java`（按业务含义聚合）
5. B 的 `AdminExceptions.isUniqueViolation`（PG `23505`）→ C 的 `exception/AdminExceptions` 内的静态判定（同构）
6. B 的 `rpc/mapper/Json*TypeHandler.java` → C 的 `common/src/main/java/com/rag/common/persistence/Json*TypeHandler.java`（上提共享层，D3）

**为什么换**：C 侧统一到 MyBatis-Plus 的 `LambdaQueryWrapper` + `@TableName` 表达，且不依赖运行期隐式 DDL；
⇒ **代价**：表名沿用 B 侧的 `users_user`（等），列名/可空性/索引必须逐列对拍才能判定行为是否一致 —— **本表不判**。

---

## 3. HTTP / DI 差异

| 维度 | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|
| 路由注册 | `@RequestMapping("/user/v1")` + `@PostMapping("/{op}")` | 同 B | admin 同理（`/admin/v1`）⇒ **B/C 路径前缀一致** |
| HTTP method | **全 POST**（users 域签字偏离） | 同 B | ⚠️ C 的 `UserController` / `AdminController` javadoc 里写的是 `PUT` / `GET` 混合 ⇒ 与 B 不一致（F-6） |
| 端点数 | user **9**；admin **13** | javadoc 写 user 9 / **admin 12** | C 的 `AdminController` javadoc 少列 `updateuser`（F-7） |
| Handler / Controller | 1 个 `UserController` / `AdminController`，9 / 13 个 `@PostMapping` | 同 B | — |
| Logic 中间层 | **已删**（Controller/Service 直调） | 同 B | B/C 形态一致 |
| 用例聚合 | 9 / 13 个 `*Service` | **各合 1**（`UserApiService` / `AdminApiService`） | D2 + 「用例内聚」判据 |
| 错误表达 | `throw BusinessException(code,msg)` + `advice/GlobalExceptionHandler` | 同 B，改名 `UserApiExceptionHandler` / `AdminApiExceptionHandler` | **统一错误模型**：业务层抛 `BusinessException`，HTTP 边界与 gRPC 边界各一个适配器 |
| 响应信封 | `@RestControllerAdvice` + `ResponseBodyAdvice` 自动包 `{code,msg,data}` | 同 B，改名 `*ApiResponseAdvice` | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不能用 `ProblemDetail` 替换** |
| JWT 校验 | `JwtAuthInterceptor` / `AdminJwtAuthInterceptor` | 同 B | Filter 是 Servlet 层；Interceptor 是 Spring MVC Handler 层 |
| public / JWT 划分 | user：public = `login` + `register`；admin：public = `create` + `login` | 同（`WebMvcConfig` 白名单） | L1 |
| 配置绑定 | `@ConfigurationProperties` 类 | 同 B | C 侧 `UserApiProperties` / `UserRpcProperties` / `AdminApiProperties` / `AdminRpcProperties` |
| ServiceContext | **已删**（Spring Bean） | 同 B | — |
| 进程拓扑 | api 进程 + rpc 进程（**2 进程 / 子域**） | **1 进程 / 子域** | D1：B 的 4 个 app 收敛成 2 个；**「rpc 进程不可用」这一故障模式消失** |
| gRPC 客户端（域内） | `api/client/UserRpcClient.java` / `AdminRpcClient.java` | **无 `service/users/user/src/main/java/com/rag/user/{advice,config,controller,dto,interceptor,metrics,service}/client/`** | D2：域内调用直连注入领域服务 Bean |
| gRPC 客户端（跨域） | `service/common` 的 `RpcInterceptor` 等 | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` + `service/users/user/src/main/java/com/rag/user/{wiring,config,domain/entity,domain/service,exception,grpc,mapper,metrics}/client/` | D6：users 域**无跨域调用** ⇒ C 侧 users 无 `rpc/client/` |
| gRPC 明文 | `grpc.client.*.negotiation-type: plaintext` | 同 B（`application.yml` 已写） | B 侧默认 TLS ⇒ **必须显式写 plaintext**，否则静默变 TLS |
| HTTP 超时 | `observability.http-timeout: 3s`（B 自研 observability 中间件的配置）+ `service/common/.../observability/GoStyleRequestDeadline.java` | `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` + `GoStyleRequestDeadline.java`（**两个都是空壳**），且两个 `application.yml` **无任何 timeout 配置** | ⚠️ 超时机制在 C 侧**未落点**（见 §7.3 F-8） |

> ⚠️ **本表的 17 行全部是跨子域行**（每行同时写 user 与 admin）⇒ 完整保留在本文件；
> 两个子域文档的 §3 为指针。

**决策锁**

**换进程拓扑（api+rpc 分离 → 合并为一域一 app）7 处同时改**：
1. `service/users/user/user-api/pom.xml` + `user-rpc/pom.xml` → `service/users/user/pom.xml`（依赖合并）
2. `service/users/admin/admin-api/pom.xml` + `admin-rpc/pom.xml` → `service/users/admin/pom.xml`
3. `UserApiApplication.java` + `UserRpcApplication.java` → `UserServiceApplication.java`（**1 个** `@SpringBootApplication`）
4. `AdminApiApplication.java` + `AdminRpcApplication.java` → `AdminServiceApplication.java`
5. `{advice,config,controller,dto,interceptor,metrics,service}/client/*RpcClient.java` → 删（域内直连；跨域才留 `{wiring,config,domain/entity,domain/service,exception,grpc,mapper,metrics}/client/`）
6. `application.yml` 两份 → 一份（HTTP 端口 + gRPC 端口同时在一个进程）
7. Nacos 注册项：2 个/子域 → 1 个/子域

**为什么换**：Spring Cloud 的部署单元是「可部署服务」；B 侧的 api/rpc 分离源于两条 codegen 路径，是**语言产物**不是架构原则。
⇒ **代价**：域的独立扩容能力消失；且**合并后同一份业务逻辑要能同时产出 HTTP 与 gRPC 两种错误表达** ⇒ 引入统一错误模型。

**换错误表达（异常出口统一）4 处同时改**：
1. 业务层统一抛 `BusinessException(code,msg)`（B 侧已是此形态 ⇒ C 侧沿用）
2. `advice/GlobalExceptionHandler` → `advice/UserApiExceptionHandler` + `AdminApiExceptionHandler`（各域一份）
3. gRPC 边界由 `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration` 转 `StatusRuntimeException`
4. 业务层不再需要「gRPC code → 业务码」这一层映射（业务码直达 HTTP 与 gRPC 两个边界）

**为什么换**：异常流是 Java 惯用形态，且能让 HTTP / gRPC 两个边界共享同一份业务错误定义。

---

## 4. 公共模块对照

> ⚠️ **RAG-V1 的 `common/` 在仓库根，不在 `service/` 下** ⇒ 下表**只点名与 users 域相关的件并说明归属**，
> 完整清单由 **[`Mapping_Table/common.md`](../common.md)** 负责。

| 能力 | B RAG 位置 | C RAG-V1 位置 | 归属说明 |
|---|---|---|---|
| 统一响应信封 | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | **全局归属** ⇒ `common.md` 负责 |
| 业务异常 | `service/common/.../exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | **全局归属** ⇒ `common.md` 负责 |
| 认证异常 | `service/common/.../exception/AuthenticationException.java` | `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | **全局归属** ⇒ `common.md` 负责 |
| 雪花 ID | `service/common/.../snowflake/Snowflake.java` + `spring/SnowflakeBean.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | **全局归属** ⇒ `common.md` 负责 |
| MP 自动配置排序桥 | `service/common/.../persistence/MybatisPlusOrderingBridgeAutoConfiguration.java`（**1 份**） | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`（**1 份**） | **全局归属**（D3）⇒ `common.md` 负责 |
| jsonb / text TypeHandler | 3 份（user-rpc ×1 + admin-rpc ×2） | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` + `JsonbTypeHandler.java` | **全局归属**（D3）⇒ `common.md` 负责 |
| 社区事实协议（`communityfact`） | `service/common/.../communityfact/` | `common/src/main/java/com/rag/common/communityfact/` | **全局归属**；users 域**不涉及**（comment/favorite/like 三域共享）⇒ `common.md` 负责 |
| HTTP 超时 / 请求 deadline | `service/common/.../observability/GoStyleRequestDeadline.java` + `spring/ObservabilityProperties.java`（`observability.http-timeout: 3s`） | `common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java` + `ObservabilityProperties.java`（**空壳**） | **全局归属**；⚠️ C 侧两个 `application.yml` 无 timeout 配置 ⇒ **未落点**（F-8）⇒ `common.md` 负责 |

> ⚠️ **本表已按子域拆分** —— **users-common 私有件的 7 行**（错误码/文案 · JWT 签发验签 · `Bearer` 前缀提取 · 密码散列 · JSON 强转 / `string→int64` · 必填校验 · DDL 幂等重放执行器）见 [`users-common.md`](./users-common.md) §4；
> 其余 8 行为全局归属 / 跨子域行，完整保留在本文件。

**决策锁**

> ⚠️ **本节的决策锁（换共享层归属 4 处同时改）见 [`users-common.md`](./users-common.md) §4** —— 那 4 处改的全是 `users-common` 的文件，故与该表同处一份文档。

---

## 5. 决策锁（汇总）

> 每条决策的完整依据与代价见 [`../决策记录.md`](../../决策记录.md)（D1–D9 已定 / P1–P6 待拍板）。
> 本节只列**影响 users 域文件树**的决策，及其在本表中的落点。

| 决策 | 内容 | 在 users 域的落点 | 对本表的影响 |
|---|---|---|---|
| **D1** | api 与 rpc 合并为一个 app（一域一进程） | `service/users/user/`（45 个 `.java`）+ `service/users/admin/`（56 个） | **C 列大量 `N:1` 的主因**：B 的 4 个 app 收敛成 2 个 |
| **D2** | 域内调用直连，不再自我 gRPC 往返 | 两侧均**无** `api/client/` | B 的 `UserRpcClient` / `AdminRpcClient` 在 C 侧消失 |
| **D3** | `service/common` 升级为真 starter | 仓库根 `common/`（7 个 auto-config） | B 的 `users-common` 里 4 个文件上提到 `common/`；MP bridge 收成 1 份 |
| **D4** | 生态优先，手写只补生态缺口 | — | users 域无手写熔断/日志/校验器 ⇒ 本表无相关行 |
| **D5** | DTO 改 immutable record + Bean Validation | `api/dto/*` 共 47 个 record（user 19 + admin 28） | B 的 `GoStyleRequired*` 4 个校验器在 C 侧删除 ⇒ **L3 降级 D-01** |
| **D6** | 跨域调用仍走 gRPC | users 域**无跨域调用** | C 侧 users 无 `rpc/client/` |
| **P1** | 错误文案保真度（待拍板） | `users/common/ErrMsg.java` + `service/users/user/src/main/java/com/rag/user/contract/ErrMsg.java` | 决定 C 侧 `ErrMsg` 的文案是否逐字照抄 B 侧 `ErrorCode.getMsg(int)` |
| **P4** | `rpc.*` 包是否改名（待拍板） | `rpc/` 包下的 21 个文件（user 14 + admin 14 中的 rpc 部分） | 改名会波及本表 C 列全部 `rpc/` 路径 ⇒ **本表不动** |

**为什么换（总述）**：B 侧的 api/rpc 分离、自研中间件链、共享能力以「普通 jar + 每个消费者手写 `@Import`」交付，都是**部署 / 装配形态**的产物；
RAG-V1 用 Spring 生态的原生形态（`@RestControllerAdvice` / Bean Validation / starter / Micrometer）重新表达同一份行为 ⇒ **文件名与职责必然重组，但 L1 契约（proto 字段号 / 错误码 / JWT claims / 表名 / HTTP path）不动**。

---

## 6. 跨模块全局约定（值逐条核对 B 侧源码）

> **本节的值全部来自 B 列（RAG 侧）源码**，不来自 C 列，也不来自 RAG-V1 的文档。

| 项 | 值 | B 侧出处（`file:line` 级可 grep） |
|---|---|---|
| **proto 字段号** | `users.proto`：`UserInfo{uid=1,score=2,username=3,email=4,extra_info=5,status=6(optional)}`；`GetUserReq{uid=1}` / `GetUserResp{user=1,found=2}`；`CreateUserReq{username=1,password=2,email=3,extra_info=4}` / `CreateUserResp{uid=1}`；`LoginReq{username=1,password=2}` / `LoginResp{uid=1,status=2}`；`LogoutReq{token=1}` / `LogoutResp{success=1}`；`UpdateUserReq{uid=1,username=2,password=3,email=4,extra_info=5}` / `UpdateUserResp{user=1}`；`AvatarHistoryItem{id=1,avatar_url=2,content_type=3,size_bytes=4,is_current=5,create_time=6}`；`UploadAvatarReq{uid=1,avatar_url=2,content_type=3,size_bytes=4}` / `UploadAvatarResp{avatar_url=1,history=2}`；`GetAvatarHistoryReq{uid=1,limit=2}` / `GetAvatarHistoryResp{list=1}`；`SelectAvatarReq{uid=1,history_id=2}` / `SelectAvatarResp{avatar_url=1,history=2}`；`DeleteUserReq{uid=1}` / `DeleteUserResp{success=1}`（共 **20 个 message**）。`admin.proto`：`AdminInfo` / `UserInfo` / 13 组 req/resp | `service/users/user/user-proto/src/main/proto/users.proto`（C 侧 `proto/src/main/proto/users.proto` 逐字节复制）＋ `service/users/admin/admin-proto/src/main/proto/admin.proto` |
| **gRPC 方法** | user **9** 个（`GetUser` / `Register` / `Login` / `Logout` / `UpdateUser` / `UploadAvatar` / `GetAvatarHistory` / `SelectAvatar` / `DeleteUser`）；admin **13** 个（`GetUser` / `GetUserList` / `GetSelf` / `CreateAdmin` / `CreateInviteCode` / `Login` / `Logout` / `DeleteUser` / `ResetUserPassword` / `UpdateSelf` / `UpdateUser` / `BanUser` / `UnBanUser`） | `users.proto` 的 `service UserService` ＋ `admin.proto` 的 `service AdminService` |
| **错误码取值** | 通用段 `5001`–`5010`（`ERROR_SERVER_COMMON`…`ERROR_DELAY_MSG`）+ `1015`（`CODE_SERVER_BUSY`）+ `200`（`SUCCESS`）/ `500`（`ERROR`）；用户段 `1001`–`1012`、`1016`–`1018`；积分段 `3001`–`3003` | `service/users/users-common/.../errmsg/ErrorCode.java`（enum 常量） |
| **错误文案** | **中文**：`"服务繁忙"` / `"系统内部错误"` / `"用户名已存在"` / `"用户不存在"` / `"权限不足"` / `"管理员邀请码错误"` / `"积分余量不足"` 等 | 同上（enum 常量自带 `msg`） |
| **错误文案承载方式** | B 侧 `ErrorCode` 是 **enum**，**码值与文案同体** ⇒ `getMsg(int)` 命中即返回该常量文案，未命中 fallback 到 `ERROR`（`"FAIL"`）。⚠️ **与旧版的差异**：旧版把码值与文案分成两张表，`1017`（`ERROR_REQUEST_PARAM`）落在码值表但**不在文案表** ⇒ 取文案时 fallback 到 `"FAIL"`；B 侧改成 enum 后**每个码值自带文案** ⇒ `getMsg(1017)` 返回 `"请求参数错误"` | 同上（`getMsg(int)` / `of(int)`） |
| **JWT claims** | `exp`（= iat + seconds）、`iat`、`userId`（**int64**）；签名算法 **HS256**（JCA 算法名钉死 `HmacSHA256`，避免 `signWith(key)` 单参重载随密钥长度漂移） | `service/users/users-common/.../jwt/JwtUtil.java`（`getToken`） |
| **JWT secret（分域两套）** | user/api 用 yml `user-api.user-auth.access-secret`（env `USER_JWT_SECRET`）；admin/api 用 yml `admin-api.admin-auth.access-secret`（env `ADMIN_JWT_SECRET`） | `service/users/user/user-api/src/main/resources/application.yml` ＋ `.../config/UserApiProperties.java`；`service/users/admin/admin-api/src/main/resources/application.yml` ＋ `.../config/AdminApiProperties.java` |
| **表名** | `users_user`、`users_user_avatar_history`、`admin_admin`、`admin_invite` | `service/users/users-common/src/main/resources/db/0{1..4}_*.sql` ＋ 各 `*Entity.java` 的 `@TableName` |
| **HTTP path** | user：`/user/v1/{register,login,logout,get,update,delete,avatar/select,avatar,avatar/history}`（9 个）；admin：`/admin/v1/{login,create,logout,getself,update,updateuser,delete,resetuserpassword,banuser,unbanuser,getuser,getuserlist,invite/create}`（13 个） | `service/users/user/user-api/.../controller/UserController.java` ＋ `service/users/admin/admin-api/.../controller/AdminController.java` |
| **HTTP method** | user / admin **全 POST** | 同上（`@PostMapping`） |
| **public vs JWT 划分** | user：public = `login` + `register`；admin：public = `create` + `login` | `.../interceptor/JwtAuthInterceptor.java` ＋ `.../AdminJwtAuthInterceptor.java`（public 路径直接放行） |
| **指标完整名** | `user_rpc_register_total`、`user_rpc_login_total`、`user_api_intercept_total`、`admin_rpc_login_total`、`admin_rpc_high_risk_action_logal`（**`logal` 是拼写错误，照抄**） | 各 `*Metrics.java`（Micrometer `Counter.builder`） |
| **Kafka topic** | **无** —— `service/users/` 内**零** Kafka 生产/消费（`grep -rl kafka` 只命中 `src/test/` 下的测试文件） | `service/users/` 全目录 grep `kafka` |

> ⚠️ **本表已按子域拆分** —— **user 子域专属的 3 行**（JWT claim 的唯一来源 / 身份常量 / 列约束（users））见 [`user/user.md`](./user/user.md) §6；
> **admin 子域专属的 2 行**（列约束（admins / admin_invites）/ 默认管理员种子）见 [`admin/admin.md`](./admin/admin.md) §6。

---

## 7. 覆盖度自查（域级汇总）

### 7.1 C 列覆盖率 —— **104 / 104 = 100%**

自查命令（`RAG-V1` 仓根）：

```bash
find "service/users" -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
  | xargs -n1 basename | sort -u
```

**逐模块实测与核对结果**（逐模块表已按子域拆到三份子文档，此处只留合计行）：

| C 模块 | `.java` 数 | 出现在本表 C 列的位置 | 覆盖 |
|---|---|---|---|
| **合计** | **104** | — | ✅ **104/104** |

> ⚠️ **逐模块实测表的落点**：user 子域 2 行见 [`user/user.md`](./user/user.md) §7.1 · admin 子域 2 行见 [`admin/admin.md`](./admin/admin.md) §7.1 · users-common 1 行见 [`users-common.md`](./users-common.md) §7.1。

> **唯一文件名数 86**（`WebMvcConfig.java` 出现 2 次、`LoginReq/Resp`、`LogoutReq/Resp`、`GetUserReq/Resp`、`DeleteUserReq/Resp`、`UpdateUserReq/Resp`、`UserInfo` 等在两个子域各有一份）。
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
> 📌 **口径说明**（勿把两侧数字当成必须相等）：`fs_c.txt` 是**文件系统侧唯一文件名 86**，
> `doc_c.txt` 是**文档里出现过的唯一文件名 164** —— 文档含 B/C 两列，同一个 `.java` 名会重复出现。
> `comm -23` 判的是**单向包含**（fs 的 86 个是否都在文档里），不是**数量相等**。
> ⇒ **86 ⊆ 164 ⇒ 输出为空 ⇒ 零遗漏。**

**B 列（RAG 侧 `service/users/`）覆盖率 —— 145 / 145 = 100%**（抄写路线图 §1.0c 的 B↔C 依据）

> B 侧 `service/users/` 实测 **145** 个 `.java`（`user-api` 43 · `user-rpc` 15 · `admin-api` 56 · `admin-rpc` 20 · `users-common` 11）
> ⇒ 四份文档的 B 列合起来 **145/145 全覆盖**，零遗漏 ⇒ **抄写路线图不会漏掉任何 B 侧类**。
> 逐子域分账见 user.md §7.1（58/58）· admin.md §7.1（76/76）· users-common.md §7.1（11/11）。
> 复算命令（`RAG-V1` 仓根）：
> ```bash
> python3 tools/check-mapping-coverage.py users   # ⇒ users 域 B 列 145/145
> ```

### 7.2 映射类型分布

> 统计口径：§1 全部映射表的**类型列**（`B | C | 映射类型 | 备注` = 4 列 ⇒ 类型是第 3 格）；不含 §2/§3/§4 的差异对照表。

| 映射类型 | 行数 | 说明 |
|---|---|---|
| `1:1` | **43** | 单文件对单文件（多数是「B 改名 / C 归位 / C 补 DTO」）；user 17 + admin 17 + users-common 9 |
| `1:N` | **15** | B 的单文件拆成多个 C 文件 —— 逐条实测：装配拆分 **2** 行（user-rpc / admin-rpc 各 1 行）· Entity/Mapper 拆分 **4** 行（user 与 admin 各 1 组）· 路由拆分 **2** 行（Controller + `WebMvcConfig`）· DTO 拆分 **2** 行（user **19** 个 + admin **28** 个 immutable record）· `ErrorCode.java` → 错误契约 2 类 **1** 行 · JWT 验签拆分 **2** 行（user / admin 各 1 行）· `AuthFilter` → 拦截器 **2** 行 = **15** |
| `N:1` | **71** | B 的多文件合进 1 个 C 文件（**D1/D2 的主因**：user-api 9+9、admin-api 13+13、user-rpc 9、admin-rpc 13 个 logic → 各 1 个 Service）|
| `C 新增` | **0** | users 域每个 C 文件都能在 B 列找到前身 ⇒ RAG-V1 在本域是**重组**而非新增能力 |
| **合计** | **129** | — |

> 统计命令（可复跑）：
> ```bash
> cat docs/Mapping_Table/users/users-common.md \
>     docs/Mapping_Table/users/user/user.md \
>     docs/Mapping_Table/users/admin/admin.md \
>   | awk '/^## 1\. 包结构对照/{f=1} /^## 2\. 持久层差异/{f=0} f' \
>   | awk -F'|' '/^\|/{t=$4; gsub(/^ +| +$/,"",t); if(t=="1:1"||t=="1:N"||t=="N:1"||t=="C 新增") print t}' \
>   | sort | uniq -c
> # ⇒ 43 1:1 / 15 1:N / 71 N:1
> ```
> ⚠️ **旧自报数（`1:1` 43 / `1:N` 15 / `N:1` 69 / 合计 127）与表内实际不符** —— 本次按上表实测值订正为 **71 N:1 / 合计 129**；删列不改行数，变的只是统计口径（原命令取第 5 格，删除旧基准列后类型列已前移到第 4 格）。
>
> ⚠️ **逐子域分布见** [`user/user.md`](./user/user.md) §7.2 · [`admin/admin.md`](./admin/admin.md) §7.2 · [`users-common.md`](./users-common.md) §7.2。

### 7.3 B ↔ C 之间「职责对不上」的地方（⚠️ 需处理）

> 以下 8 条是本表在两方对照中**实际发现**的职责缺口 / 不一致。**它们不是行为结论**，只是「文件名 / 职责落点」层面的问题。

| # | 发现 | 影响面 | 建议落点 |
|---|---|---|---|
| **F-1** | ✅ **已关闭（2026-11-01）—— 身份解析器在 C 侧已有承载**。B 侧 `user-api/.../service/IdentityResolver.java` 与 `admin-api/.../service/IdentityResolver.java`（**各一份，共两份重复**；权威解析器：`ClaimedUID` → `GetUser` → status presence 检查 → `SubjectRef`；`AuthorityID="rtw.identity"` / `PlatformTenantID="platform"`；5 个 sentinel 错误 `ErrInvalidClaim` / `ErrUserNotFound` / `ErrIdentityMismatch` / `ErrUserInactive` / `ErrStatusUnavailable`）⇒ C 侧落 **`service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java`（只 1 份，上收到域级共享层）** ⇒ **B 侧两份重复在 C 侧被消解**（用户 2026-11-01 定；`users-common` 已被 user / admin 依赖 ⇒ **不动 pom**） | L1（`AuthorityID` / `PlatformTenantID` / `SubjectRef` 形状）+ L2（5 个错误的判定顺序） | ✅ 落点已定并落地：域级共享层 `service/users/common/` ⇒ user / admin 两子域**共用**同一份权威实现，**不复制两份** |
| **F-2** | 🟠 **错误契约在两个子域间仍然不对称（2026-10-26 D7 后收敛为「admin 无 `contract/` 包」）**。C 侧 `service/users/user/src/main/java/com/rag/user/contract/` 有 `ErrCodes.java` + `ErrMsg.java`；**admin 侧根本没有 `contract/` 包**（`admin-contract` 模块已随 D7 删除）。而 B 侧 `service/users/users-common/.../errmsg/ErrorCode.java` 是 **user / admin 四方共用**的一份；RAG-V1 把它落在**公共库** `service/users/common/src/main/java/com/rag/users/common/`（`ErrCodes.java` + `ErrMsg.java`，artifactId `users-common`） | 若 admin 域要独立引用错误契约，须显式取公共库那份（`users-common` 是 admin 的 pom 依赖，**已接上**）；但 admin 源码**当前零引用** `ErrCodes` / `ErrMsg` | 明确 admin 域的错误码取「公共库 `users-common` 那份」还是「user 子域私有那份」；取前者则**不要**在 admin 侧复制一份（逐域隔离 ≠ 逐子域复制）。⚠️ **时点标注**：本条原文写的是「`admin/contract/` 一份都没有（该模块只有 `admin.proto`）」—— 那是 D7 之前的状态 |
| **F-3** | 🔴 **幂等 DDL 重放机制在 C 侧无执行器、无脚本**。B 侧有 `users-common/.../ddl/SchemaInitializer.java` + `db/01..04_*.sql`（`01_create_user.sql` / `02_create_user_avatar_history.sql` / `03_create_admin.sql` / `04_create_admin_invite.sql`）；C 侧 `common/` **无 `SchemaInitializer`**，两个 `*-service` 的 `resources/db/migration/` **只有 `.gitkeep`** ⇒ `UserSchemaBootstrap` / `AdminSchemaBootstrap` 无处可重放 | B 侧 `SchemaInitializer` 的**无版本账本幂等重放**是 L2 语义（谁先启动都行） | C 侧需补执行器（建议归 `common/src/main/java/com/rag/common/persistence/`）+ 4 个幂等 SQL 脚本（`CREATE TABLE IF NOT EXISTS`） |
| **F-4** | 🟡 **表名：B 侧与更早一版的历史命名不同**。B 侧用 `users_user` / `users_user_avatar_history` / `admin_admin` / `admin_invite`；更早一版的历史命名是 `users` / `user_avatar_history` / `admins` / `admin_invites`（**随旧基准移除，不再作数**） | 表名是 L1 级可见面（客户端直查、报表、运维脚本都依赖） | 本表**不判**行为；**D16 下 B 是基准** ⇒ C 侧沿用 B 的 4 张表名。若 RAG-V1 要改表名，需**单独留档**并走决策流程（否则下一棒会把它当成「本来就这样」） |
| **F-5** | 🟡 **C 的 HTTP DTO 命名与 B 侧不一致（user 1 处 / admin 1 处）**。① user 侧：B 的 `dto/` 用 `RegisterReq`/`RegisterResp`，C 改成了 `CreateUserReq`/`CreateUserResp`（B 的 proto 侧两者都叫 `CreateUser*`）；② admin 侧：B 的 `dto/` 与 proto 侧都叫 `CreateInviteCodeReq`/`CreateInviteCodeResp`，C 改成了 `CreateInviteReq`/`CreateInviteResp` | DTO 名 = HTTP 契约的一部分 | **D16 下 B 是基准** ⇒ C 侧**要么改回 B 的名，要么显式登记为签字偏离**（当前两侧都未登记） |
| **F-6** | 🟡 **C 的 `UserController` / `AdminController` javadoc 里的 method 与端点清单，与 B 侧不一致**。javadoc 写「B 侧 method 分布：DELETE, GET, POST, PUT」并列出 `PUT /user/v1/updateuser`、`GET /user/v1/avatarhistory`、`GET /admin/v1/getself` 等；而 B 侧 `UserController` / `AdminController` **全是 `@PostMapping`**，且 B 的实际路径是 `/user/v1/get`、`/user/v1/update`、`/user/v1/delete`、`/user/v1/avatar`、`/user/v1/avatar/select`、`/user/v1/avatar/history` 与 `/admin/v1/updateuser` 等 —— 与 C 的 javadoc 清单**逐条不同**；⚠️ C 的 javadoc 首行还写「B 侧 为 `/usercenter/v1/user/*`」，而 B 侧实测是 `/user/v1/*` ⇒ **javadoc 自述的「B 出处」本身也是错的** | 这两条 javadoc 是 RAG-V1 内部的**自相矛盾**：同一仓的 `File_Tree/users/user/user-service.md` 写「method 是签字偏离：旧版 Get2·Post6·Delete1 ⇒ 本仓库统一 POST」 | 以 **B 侧 `UserController` / `AdminController`** 为准修正 javadoc；否则下一棒填实现时会把 `PUT` / `GET` 和那套路径当成契约写进去 |
| **F-7** | 🟡 **C 的 `AdminController` javadoc 少列一个端点**。写「端点数：12」，B 侧实际是 **13** 个（漏了 `updateuser`） | 漏项 = 少一个端点的契约 | 补 `POST /admin/v1/updateuser`（B 侧实测路径） |
| **F-8** | 🟡 **HTTP 超时机制在 C 侧未落点**。B 侧两侧都显式配了 `observability.http-timeout: 3s` + `service/common/.../observability/GoStyleRequestDeadline.java`；**C 侧 `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` 与 `GoStyleRequestDeadline.java` 两个都是空壳，且两个 `application.yml` 无任何 timeout 配置** | 超时是 L3；B 的实测差异（旧版 fail-open 200 / B 侧 503）正是超时 + 断连行为共同作用的结果 | 实现阶段需在 `common/src/main/java/com/rag/common/observability/` 落 deadline 执行器并在两个 `application.yml` 显式配 3000ms，同时保留 `RedisDisconnectedBehaviorConfig` 的 fail-open / fail-fast 语义核对 |

---

## 8. 一句话总结

**B 列 RAG**（`service/users/`，145 个 `.java`）= **唯一真理之源**（api/rpc 分离的部署形态）；
**C 列 RAG-V1**（`service/users/`，104 个 `.java`）= Spring 惯用目标形态（一域一 app）。

⇒ **C 列 104/104 覆盖（86 个唯一文件名零遗漏）、B 列 145/145 覆盖、映射类型 1:1 ×43 / 1:N ×15 / N:1 ×71 / C 新增 ×0（共 129 行）。**
⇒ **文件名与职责已逐条对应；行为是否一致，一律以 B 列（RAG 侧）源码为准，状态看 `RAG/docs/DRIFT/users/`。**
⇒ **本表发现的 8 条职责缺口（F-1 ~ F-8）** 里，**F-3（DDL 重放无执行器/脚本）** 与 **F-6（Controller javadoc 与 B 侧 method / 路径不一致）** 是硬缺口，填实现前应先补。
