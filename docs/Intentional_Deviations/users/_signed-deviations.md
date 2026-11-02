# Intentional_Deviations / users-user —— 已签字偏离

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-02**（逐行核对源文件，非估算）· **依据基准 2026-11-02**：**B 侧为唯一真理**（D16）
>
> **本文件归属**：`service/users/user/`（`user-service`）子域。
> ⚠️ **只写自己域**：admin 子域与 `users-common` 的偏离不在本文件（各有其主）。
> ⚠️ **不碰 `_INDEX.md`**（AGENTS.md §五.2：三个 `_INDEX.md` 合并后统一更新）。
>
> **产出坐标**：`align/users-user` 工作树（`/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user`）· 基线 commit `07ff14a`。

---

## DEV-USER-001 · HTTP DTO 类名 `RegisterReq/RegisterResp` → `CreateUserReq/CreateUserResp`（L4 · 表达层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（D16 下「L1 以 B 侧为准」不覆盖**类名**，只覆盖 wire） |
| **分域** | user 域（`service/users/user/`） |
| **偏离类型** | **有意偏离**（L4 · Java 符号名层） |
| **提出 / 签字** | user 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

B 侧的 **HTTP 层** DTO 叫 `RegisterReq` / `RegisterResp`；C 侧叫 `CreateUserReq` / `CreateUserResp`。

| | B 侧（RAG 仓） | RAG-V1（C） |
|---|---|---|
| HTTP DTO | `RegisterReq` / `RegisterResp` | `CreateUserReq` / `CreateUserResp` |
| proto message | `CreateUserReq` / `CreateUserResp` | `CreateUserReq` / `CreateUserResp`（**不变**） |

### 为什么是偏离（而不是等价）

- **wire 不变**：HTTP 的 JSON 键名仍是 `username` / `password` / `email` / `extra_info`；
  proto 的 message 名与字段号**一个字节都没动** ⇒ ⇒ **外部可观察行为完全一致**。
- **Java 符号名变了**：C 侧同名的 DTO 与 proto message **同名**（`CreateUserReq`）
  ⇒ ⇒ 出现「两个同名不同义的类型」⇒ ⇒ 必须靠 import 区分
  （本域的写法：`com.rag.proto.user.CreateUserReq` 显式全限定，HTTP DTO 用
  `com.rag.user.dto.user.CreateUserReq`）。

### B 侧出处

- B 侧 `service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/RegisterReq.java`
- B 侧 `service/users/user/user-api/src/main/java/com/rag/users/user/api/dto/RegisterResp.java`
- B 侧 proto `service/users/user/user-proto/src/main/proto/users.proto` —— message 名是 `CreateUserReq` / `CreateUserResp`

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/dto/user/CreateUserReq.java`
- `service/users/user/src/main/java/com/rag/user/dto/user/CreateUserResp.java`
- 映射表登记：`docs/Mapping_Table/users/user/user.md` §1.1.2（「B 的 `RegisterReq`/`RegisterResp` 在 C 改名 `CreateUserReq`/`CreateUserResp`（回到 proto 侧的 `CreateUserReq` 名）」）⇒ **同时闭合 `_shared.md` §7.3 的 F-5**

### 语义等价论证

| 项 | B | C | 判定 |
|---|---|---|---|
| HTTP JSON 键 | `username` / `password` / `email` / `extra_info` | 同 | ✅ 逐字一致 |
| 必填字段 | `username` / `password` / `email` | 同（`@NotNull`） | ✅ 一致（D-01 降级只换文案） |
| proto message 名 + 字段号 | `CreateUserReq` 等 | 同 | ✅ **逐字节一致**（已 diff 验证） |

---

## DEV-USER-002 · 必填校验：go-zero 校验器 → Bean Validation（L3 降级 · 引用 D-01）

| 项 | 内容 |
|---|---|
| **状态** | ✅ **已登记**（不在本文件裁决）—— 权威登记在 [`docs/L3-降级清单.md`](../../L3-降级清单.md) **D-01**（决策 D5） |
| **分域** | user 域（全部 19 个 DTO） |
| **偏离类型** | **L3 降级**（状态码与错误结构不变，**只有文案**变） |

### 偏离内容（D-01 的三条）

1. **错误文案**：B = `field "username" is not set`（go-zero 校验器）⇒ C = Hibernate Validator 文案；
2. **map 字段的必填判定**：B 的 `extra_info` 三态（缺席不报 / 显式 null 报）**在 record 形态下不可表达**
   ⇒ C 侧**不加任何约束** ⇒ 缺席合法 ✔，显式 null 也放行 ✘；
3. **标量空串合法性**：B 只判 `null` ⇒ **空串合法** ⇒ C 侧用 `@NotNull`（**不是** `@NotBlank`）⇒ 等价。

### 为什么这里只做「引用登记」

⇒ 该降级**已在 `docs/L3-降级清单.md` D-01 登记并经用户批准**（决策 D5）⇒ ⇒ 本文件**不重复裁决**，
只做指针 + 本域的落地对照。

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/dto/**`（19 个 record，`@NotNull` / 无额外约束）
- `service/users/user/src/main/java/com/rag/user/advice/UserApiExceptionHandler.java`
  —— `MethodArgumentNotValidException` → `PlainTextErrors.badRequest(msg)`（**400 + text/plain + nosniff + 尾 `\n`**）
  ⇒ ⇒ **状态码与错误结构逐条不变**，只有文案换来源。

### 运行证据

`service/users/user/src/test/java/com/rag/user/api/UserApiHttpContractTest.java`
—— `missingRequiredFieldIsPlainText400`：**400 ＋ `text/plain` ＋ `X-Content-Type-Options: nosniff`**。

### 🔴 2026-11-03 双侧活体对拍后的**边界收窄**（偏离只剩「文案形态」这一项）

对拍（`tools/difftest/users-user/run.sh`）实测发现：本条降级**原先还盖住了一个更严重的问题** ——
C 侧「报哪个字段」**不确定**（Hibernate Validator 的违规集合是 `HashSet` ⇒ `findFirst()` 随 hash 变
⇒ 同一输入 6 次出 3 种文案），而 B 侧 `GoStyleRequired` 是**按固定序逐个判、遇第一个缺失就抛**
⇒ **报同一个字段**。

⇒ **已修**（`DRIFT-USER-PARITY-004`）：改为**按 DTO record 声明序取第一条违规**
⇒ C 侧各 record 的声明序**恰好与 B 的判定序逐个一致** ⇒ ⇒ **现在两侧报同一个字段**。

⇒ ⇒ **本条偏离的边界收窄为**：
| 维度 | 状态 |
|---|---|
| 状态码 400 | ✅ 与 B 一致 |
| 错误结构（text/plain ＋ nosniff ＋ 尾换行） | ✅ 与 B 一致 |
| **报哪个字段**（声明序第一个违规） | ✅ **2026-11-03 起与 B 一致** |
| **文案形态**（`field "x" is not set` ↔ `x 不能为null`） | 🟡 **仍是本条偏离**（D-01 降级，未变） |

---

## DEV-USER-003 · 鉴权失败的响应体统一成完整信封（L4 · 表达层；B 侧自身不一致）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | user 域 |
| **偏离类型** | **有意偏离**（L4 · 响应体形状） |

### 偏离内容

B 侧有两处鉴权短路（缺 Bearer / 签名错 / 过期、黑名单命中）⇒ 都是 **401**，
但**两处的 body 形状不一致** ⇒ C 侧**统一成完整信封** `{code,msg,data}`。

| | B 侧 | RAG-V1（C） |
|---|---|---|
| HTTP 状态码 | **401** | **401**（不变） |
| body | 两处**形状不一致** | **统一 `{code,msg,data}`**（`code = 1006`） |

### 语义等价论证

- **状态码不变**（401）⇒ 客户端的「是否已登录」判定不变；
- **业务码不变**（1006 = `ERROR_TOKEN_RUNTIME`）⇒ 客户端按 body 判业务成败的路径不变；
- ⇒ ⇒ **只有 body 的字段集合**从「B 侧两处互不一致」变成「一致」⇒
  这是**把 B 侧自身的不一致收敛**，**不是**改变 B 侧行为。

### B 侧出处

- B 侧 `service/users/user/user-api/src/main/java/com/rag/users/user/api/advice/GlobalExceptionHandler.java:76-81`

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/advice/UserApiExceptionHandler.java`（`handleAuthentication`）

### 运行证据

`UserApiHttpContractTest#protectedPathWithoutTokenIs401` ⇒ **HTTP 401 ＋ `code = 1006` ＋ `data` 字段存在**。

---

## DEV-USER-004 · Nacos 注册项：B 侧 2 个 → C 侧 1 个（L1 · 服务发现面）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（依据 = 映射表**已锁决策**，非本 lane 自创） |
| **分域** | user 域（`wiring/NacosRegistration.java` + `application.yml`） |
| **偏离类型** | **有意偏离**（L1 · 注册面） |

### 偏离内容

| | B 侧（RAG 仓） | RAG-V1（C） |
|---|---|---|
| 注册项 ① | `user-api`（HTTP 端口）—— Spring Cloud **自动注册** | **不存在** |
| 注册项 ② | `user-rpc`（gRPC 端口）—— `NacosRegistration` **显式注册** | `user-rpc`（gRPC 端口）—— 显式注册（**逐字沿用**） |
| 自动注册 | 开着 | **关掉**（`spring.cloud.nacos.discovery.register-enabled: false`） |

⇒ 全进程**恰好 1 个** Nacos 注册项。

### 依据（**不是**本 lane 自创）

映射表 `_shared.md` §1.3「换进程拓扑 7 处同时改」第 **7** 条（**已锁决策**）：

> **Nacos 注册项：2 个/子域 → 1 个/子域**

article 域的同款表述：「Nacos 注册项从 2 个（`article-api` / `article-rpc`）减为 1 个（`article-service`）」。

### 为什么**保留** `user-rpc` 这个名字（而不是改成合并后的 `user-service`）

AGENTS.md §五.5：**注册名是 L1** ⇒ **以 B 侧为准**。且 `user-rpc` 在 B 侧是**活的**：

1. **运维探针**：B 侧 `docker-compose.yaml:80` 的 nacos 健康检查查的就是 `serviceName=user-rpc`；
2. **跨域寻址**：B 侧 `service/users/admin/admin-api/src/main/resources/application.yml:128` 写着
   `grpc.client.user-rpc.address: discovery:///user-rpc`
   ⇒ ⚠️ 但 B 侧 `AdminRpcClient.java:85-86` 自述该配置**没有对应注入点**（死配置）⇒ 目前无活消费者。

⇒ ⇒ **保留零成本**（不改任何活行为），**改掉**会让运维探针与将来的跨域客户端失配。

### 为什么关掉自动注册**不损失活行为**

B 侧的 `user-api` 条目**全仓无消费者**：全仓 grep 无 `discovery://user-api`；HTTP 客户端是**直连端口**（前端不走服务发现）⇒ ⇒ 关掉它**不改变任何可观察行为**。

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/wiring/NacosRegistration.java`
  —— 服务名**硬编码为 `user-rpc`**（B 出处逐字照抄）；group 从 `spring.cloud.nacos.discovery.group` 读
  （B 侧 `DRIFT-G1-001`：写死 ⇒ `NACOS_GROUP` 环境变量失效）；端口取 `grpcServerProperties.getPort()`。
- `service/users/user/src/main/resources/application.yml` —— `spring.cloud.nacos.discovery.register-enabled: false`

### 运行证据

`service/users/user/src/test/java/com/rag/user/wiring/UserNacosRegistrationWiringTest.java`（6 例全绿）：

- `registrationUsesBServiceName` ⇒ 注册调用的第 1 个实参**必须**是 `user-rpc`；
- `registrationUsesGrpcPort` ⇒ 第 3 个实参（`Instance`）的端口 = **gRPC** 端口（8081）；
- `registrationUsesConfiguredGroup` / `defaultGroupIsUnchanged` ⇒ group 来自配置、默认值零变化；
- `autoRegistrationIsDisabled` ⇒ 读 `application.yml` 断言 `register-enabled = false`。

---

## DEV-USER-005 · 入口类的 `@Import` 清单 → 共享层 auto-config（D3 · starter 化）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（依据 = 决策 **D3**，全仓统一形态） |
| **分域** | user 域（`UserServiceApplication.java`） |
| **偏离类型** | **有意偏离**（L4 · 装配形态） |

### 偏离内容

B 侧 `UserRpcApplication` 用 `@Import` 逐个拉 **9 个**共享件；C 侧**零 `@Import`**，全部由 `common/` 的 auto-config 提供。

| B 侧 `@Import` 项 | C 侧落点（auto-config） |
|---|---|
| `SnowflakeBean` + `SnowflakeProperties` | `SnowflakeAutoConfiguration` |
| `LoggerFactoryBean` | `LoggerAutoConfiguration` |
| `RpcServerInterceptorFactory` | `ObservabilityAutoConfiguration#grpcObservationInterceptor` |
| `ObservabilityProperties` | `ObservabilityAutoConfiguration`（`@EnableConfigurationProperties`） |
| `ObservabilityLifecycle` | `ObservabilityAutoConfiguration#observabilityLifecycle` |
| `DeadlineQueryTimeoutInjector` + `SqlTimeoutStatusTranslator` | `PersistenceAutoConfiguration` |
| `GoStyleProtectionConfiguration` | `ProtectionAutoConfiguration` |
| （gRPC 拦截链挂载点） | `ProtectionAutoConfiguration#seaGrpcServerConfigurer`（**域内 0 重复**） |

### 语义等价论证

- **能力不缺**：9 项能力**逐项**有 C 侧落点（表格右列）⇒ ⇒ 装配形态变了，**能力集合没变**；
- **gRPC 拦截链**：B 侧每个 rpc 模块自己写 `@Bean rpcInterceptorConfigurer(...)`（因为 devh starter
  **只认 `GrpcServerConfigurer`**，漏配就**静默失效**）⇒ C 侧由共享层**唯一挂载**
  ⇒ ⇒ 链上仍是 recover → breaker → shedding → 业务观测这 4 层（由共享层的装配层测试保证）；
- ⚠️ **不能**改成「域内再写一份」⇒ 会挂 2 条链 ⇒ span 重复 / timeout 叠加 ⇒ A3 判 FAIL。

### B 侧出处

- B 侧 `service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/UserRpcApplication.java`（`@Import` 清单 + `rpcInterceptorConfigurer`）

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/UserServiceApplication.java`

---

## DEV-USER-006 · 错误契约 `contract/ErrCodes`/`ErrMsg` = **转发视图**（非副本）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | user 域 |
| **偏离类型** | **有意偏离**（L4 · 代码组织） |

### 偏离内容

C 侧 `com.rag.user.contract.ErrCodes` / `ErrMsg` **不持有码表**，只做**别名转发**到 `com.rag.users.common.ErrCodes` / `ErrMsg`（域内私有视图，只暴露 user 子域用到的 16 个码）。

⇒ ⇒ **零副本、零漂移面**：码值与文案的真源**只有一份**（`users-common`）。

### 语义等价论证

- 码值与文案**逐条来自同一份真源** ⇒ ⇒ 不会出现「user 侧改了、admin 侧没改」的分裂；
- 域内引用面**不变**（仍是 `com.rag.user.contract.ErrCodes`）⇒ ⇒ 域内代码读起来仍是「域私有契约」；
- ⇒ ⇒ **L1（码值 ＋ 文案）零漂移**。

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/contract/ErrCodes.java`
- `service/users/user/src/main/java/com/rag/user/contract/ErrMsg.java`
- 真源：`service/users/common/src/main/java/com/rag/users/common/{ErrCodes,ErrMsg}.java`（**users-common lane 落地**）

### 附带结论（给 admin lane 的先例）

⇒ **F-2 的答案**：admin 侧若要引用错误契约 ⇒ **取公共库那份**（`com.rag.users.common.ErrCodes`），
**不要复制** ⇒ 本条即是可抄的先例。

---

## DEV-USER-007 · gRPC pb 包名 `com.rag.users.user.proto` → `com.rag.proto.user`（D7 · 集中化）

| 项 | 内容 |
|---|---|
| **状态** | ✅ **全仓统一决策**（D7 · 2026-10-26）—— 本条只做本域落点登记 |
| **分域** | user 域（`proto/` + `grpc/UserGrpcService.java`） |
| **偏离类型** | **L1 · 表达层**（proto 的 `package` 未改 ⇒ **gRPC 方法全路径未变**） |

### 偏离内容

| | B 侧 | RAG-V1（C） |
|---|---|---|
| pb 包名 | `com.rag.users.user.proto` | `com.rag.proto.user` |
| proto `package` | `package user;` | `package user;`（**未改**） |
| gRPC 全路径 | `/user.UserService/Login` | `/user.UserService/Login`（**未变**） |

### 语义等价论证

⇒ **`users.proto` 的 message 名 / 字段号 / 字段名 / 枚举值 10/10 逐条一致**（已 diff 验证）
⇒ ⇒ **只有 `java_package` 与一行注释不同** ⇒ ⇒ **wire 层完全一致**
⇒ ⇒ 「复制忠实 ≠ 消费方式已验」这条边界（AGENTS.md §3.4）仍成立：pb 包名换了之后，
**Java 侧的 import 全部要跟着改** ⇒ 本域 18 个 proto 类型引用已逐个改到位。

### RAG-V1 落点

- `proto/src/main/proto/users.proto`（根级 `proto/` 模块）
- `service/users/user/src/main/java/com/rag/user/grpc/UserGrpcService.java`（`extends com.rag.proto.user.UserServiceGrpc.UserServiceImplBase`）

---

## DEV-USER-008 · D2 域内直连 ⇒ B 侧 api 层的「客户端熔断打开」分支不可达

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | user 域（`service/UserApiService.java`） |
| **偏离类型** | **有意偏离**（L4 后果 · D2） |

### 偏离内容

B 侧 api 层走 gRPC 调本域 rpc ⇒ 客户端熔断器打开时会抛一个特定异常 ⇒ api 层有专门的
`loginRpcError` / `registerRpcError` 分支兜它。
⇒ C 侧 **D2（域内调用直连）** ⇒ 同进程内**没有 gRPC 客户端** ⇒ ⇒ **该分支不可达** ⇒ ⇒ **不保留**。

### 语义等价论证

- B 侧的该分支**只在「客户端熔断打开」时**触发 ⇒ 而 C 侧同进程直连**不存在客户端熔断**这一层
  ⇒ ⇒ 该分支的前提**永不成立** ⇒ ⇒ 保留它就是**死代码**（而且会误导 review 以为熔断语义还在）；
- ⇒ ⇒ **可观察行为无差**：C 侧该分支原本也不会被触发。

### RAG-V1 落点

- `service/users/user/src/main/java/com/rag/user/service/UserApiService.java`
  —— `login` / `register` 的 `catch (StatusRuntimeException e)` 里**只保留** B 侧对
  `codes.Internal` / default 的映射（5001 / 1015），**未**保留熔断分支。

---

## DEV-USER-009 · `config/RedisDisconnectedBehaviorConfig.java` 域内副本删除（D3 · 已上移共享层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（**零行为差**：B 侧已删并上移） |
| **分域** | user 域 |
| **偏离类型** | **文件级删除**（能力上移到共享层） |

### 偏离内容

B 侧**已把域内 5 份 `RedisDisconnectedBehaviorConfig` 副本全删**并收进共享层
（`service/common/.../redis/GoStyleRedisClientAutoConfiguration`）
⇒ ⇒ C 侧**照抄**：域内副本**删除**，由共享层 `common/redis/ClientAutoConfiguration` 承担。

### 语义等价论证

- **能力没丢**：Lettuce 的 `disconnectedBehavior(REJECT_COMMANDS)` 由共享层 auto-config 施加
  ⇒ ⇒ 与 B 侧**同一份配置**；
- ⇒ ⇒ **零行为差**。

### RAG-V1 落点

- 共享层：`common/src/main/java/com/rag/common/redis/ClientAutoConfiguration.java`（**非本域写权面**）
- 本域：域内副本**已删**（`git status` 可见 `D service/users/user/.../config/RedisDisconnectedBehaviorConfig.java`）

---

## 附：本域的**非偏离**但值得记一笔的继承项

| 项 | 说明 |
|---|---|
| 未知路径 ⇒ **500 + 信封**（不是 404） | **B 侧自身**的行为：B 的兜底 `@ExceptionHandler(Exception.class) handleAny`（`GlobalExceptionHandler.java:333-345`）把 Spring 的 404 异常一并兜住 ⇒ C 侧的 `handleAny` 与之**逐字同构** ⇒ ⇒ 按 D16 **继承**，不回溯修改。运行证据：`UserApiHttpContractTest#unknownPathOutsideUserPrefixIs500Envelope` |
| `/user/v1/**` 下的未知路径 ⇒ **401**（不是 404） | **B 侧同形**：B 的 `WebMvcConfig` 用 `addPathPatterns("/user/v1/**")` 注册拦截器 ⇒ Spring 的静态资源 handler 把未映射路径兜成「有 handler」⇒ 拦截器先于 404 触发。⚠️ B 侧 **Go** 端对未注册路径给 404 ⇒ 那是 B 侧**自身**的差异 ⇒ C 侧继承 B 侧 Java 的形态。运行证据：`UserApiHttpContractTest#unknownPathInsideUserPrefixIs401` |
| 405 = **裸状态码**（无 `Allow` 头、无 body） | 与 B 侧 Java 版**逐字一致** ⇒ **不是偏离** |
| `extra_info` 是 **text**（不是 jsonb） | 与 B 侧 DDL 一致 ⇒ **不是偏离** |
| 事务边界：Go 的 `m.conn.WithContext(ctx).Transaction(...)` → Spring **`@Transactional`**（挂在 mapper 接口的 `default` 方法上） | **L4 表达层改写，非行为偏离**：B 侧把「4 步 / 5 步原子」包在**同一个 DB 事务**里（D-005 / D-015）⇒ C 侧用 Spring 事务表达**同一件事** ⇒ 提交时机与回滚边界**逐条一致**。⚠️ 但这是本仓**最易静默失效**的形态（mapper 是 MyBatis 的 JDK 代理 ⇒ 三处前提任一不成立，注解就是装饰品且**零报错**）⇒ ⇒ 已配**真 UNIQUE 约束冲突**的运行证据 + **变异实测**。B 出处与语义等价论证见 `UserAvatarHistoryMapper` 的类 javadoc；证据见 `docs/DRIFT/users/verification-users-user-2026-11-02.md` §11.7 / §11.8；坑见 `docs/Pitfalls/users/_user-domain-pitfalls.md` P-USER-06 |
