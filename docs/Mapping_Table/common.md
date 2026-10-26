# Mapping_Tree/common.md — `common`（全局共享层）三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标架构）
> 三棵树里**全局共享层**的文件逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> **A 列**：`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/common/`（真源，冻结，**11 个非测试 `.go`**）
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/common/`（**51 个主代码类**）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/common/`（**56 个主代码类**；`common` 在仓库根，不在 `service/` 下）
>
> **格式契约**：[`README.MD`](README.MD) · **本目录其他子文档**：`users.md` / `article.md` / … 同格式

---

## 读表前的三条纪律（务必先读）

1. ⚠️ **B 列（RAG）不是 Go 的替身。**
   RAG 的 `service/common` 在对齐审计中 **3 个可比包 `NOT EQUIVALENT`、27 条编号漂移未修 + 5 项未编号**
   （[`RAG/docs/DRIFT/common/alignment-2026-10-23.md`](https://github.com/) §十二·一；`communityfact` 单独 14 条见同文件 §五）。
   ⇒ **一切行为判断以 A 列 Go 源码为准**；B 列只作「前一形态的落点」参考。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 `docs/DRIFT/<域>/<组件>.md` 的**证据强度列**，不在本表。
3. ⚠️ **C 列全是空壳。** RAG-V1 的每个 `.java` 目前只有 `package` + 空类型 + TODO javadoc
   ⇒ **C 列的每一行都是「这个位置该放什么职责」，不是「已经有什么」。**

### 映射类型词表（固定 4 种，不发明新词）

| 词 | 含义 |
|---|---|
| **1:1** | 一个 A 文件 → 一个 B 文件 → 一个 C 文件 |
| **1:N** | 一个 A 文件拆成 N 个 C 文件（B 通常保持 1 个或也拆开） |
| **N:1** | 多个 A 文件合到 1 个 C 文件（B 通常已经是 1 个） |
| **C 新增** | A / B 都没有对应物，是 RAG-V1 形态本身需要的新文件 |

> ⚠️ **本表的类型判定约定**：类型以 **A→C** 为主链。
> 当 **A 侧无对应物**（Go 侧根本没有这个机制）时，类型**退化为 B→C**，并在备注里写明 `A 侧无对应物（原因）`。
> 这不是新词，仍在 4 种词表内，只是把主链切换成 B→C。

### B 列「越界吸收」清单（读表前要知道）

本表的 B 列主轴是 `RAG/service/common/`，但 C 列的 `common/` **吸收了 RAG 里不属于 `service/common/` 的文件**。
这些在表里都会用**模块前缀**标出来（如 `article-api/http/...`），列成一张速查：

| C 列归属 | 吸收自 RAG 的非 `service/common` 位置 | 数量 |
|---|---|---|
| `common/src/main/java/com/rag/common/web/` | `article-api`（4 http 异常 + 3 filter + 1 dto）、`users-common/http/`（2）、`like-api/dto/`（2）、`message-api/`（2）、`common/http/`（3） | 16 |
| `common/src/main/java/com/rag/common/security/` | `users-common/jwt/`（2）+ `users-common/cryptx/`（1） | 3 |
| `common/src/main/java/com/rag/common/persistence/` | 5 个 rpc 模块各一份 `JsonbTypeHandler` + 2 个 rpc 模块各一份 `JsonTextTypeHandler` | 4（合 7 份） |
| `common/src/main/java/com/rag/common/autoconfigure/` | 9 个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration` | 1（收编 9 份） |
| `common/src/main/java/com/rag/common/web/GrpcStatusMapper.java` | `article-api/http/GoStyleGrpcStatusMapper` | 1 |

> ⚠️ **这正是 RAG-V1 的一个关键动作**：RAG 的共享能力**散在 6 个模块**（`service/common` + `users-common` + 各域 api/rpc），
> RAG-V1 把它们**收敛成一个 starter**（D3）。
> ⇒ 这也是 RAG 的 **R1 硬规则**所指的风险面：改 `common/` = 自动从并发降级为串行。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| A (Go 真理之源) | B (RAG, Go 形态版) | C (RAG-V1, Spring 目标) | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/common/`（5 个包 / 11 个非测试 `.go`） | `service/common/`（10 个包 / 51 个主代码类） | `common/`（**仓库根**，11 个包 / 56 个主代码类） | 1:1 | 包**路径**变（`service/common` → `common`），职责集合变 |
| `service/common/communityfact/`（4 `.go`） | `service/common/communityfact/`（4 类） | `common/src/main/java/com/rag/common/communityfact/`（**13 类**） | 1:N | Go 的「单文件多类型」被拆成 1 类型 1 文件（L1 wire 类型必须逐字段保真） |
| `service/common/logger/`（1 `.go`） | `service/common/logger/`（6 类） | `common/src/main/java/com/rag/common/logger/`（4 类） | N:1 | B 的 `Logger` + `LoggerFactoryBean` 双轨 → C 单个 `GoStyleLogger implements slf4j.Logger` |
| `service/common/observability/`（4 `.go`） | `service/common/observability/`（7 类）+ `spring/`（2 类） | `common/src/main/java/com/rag/common/observability/`（3 类） | N:1 | Micrometer Observation 承担（D-03 降级） |
| `service/common/response/`（1 `.go`） | `service/common/response/`（1 类） | `common/src/main/java/com/rag/common/contract/`（1 类） | 1:1 | 类名 `Response` → `ApiResponse`，**信封形状 `{code,msg,data}` 是 L1**（K-06） |
| `service/common/snowflake/`（1 `.go`） | `service/common/snowflake/`（1 类）+ `spring/`（2 类） | `common/src/main/java/com/rag/common/snowflake/`（1 类）+ `autoconfigure/`（1 类） | 1:N | epoch / 位段是业务契约（like 域游标依赖 `>>22`） |
| **Go 侧无对应物** | `service/common/protection/`（**17 类**）+ `spring/`（3 类） | `common/src/main/java/com/rag/common/protection/`（**2 类**）+ `autoconfigure/`（2 类） | N:1 | go-zero 内置熔断/降载/限连接/限体积 ⇒ 生态吸收（D-04）；**gRPC 保护链的挂载点也在 `autoconfigure/`**（共享层 1 份 ⇒ 覆盖 10 个域，见 §7 #6） |
| **Go 侧无对应物** | `service/common/http/`（3 类）+ 各域 api/http、users-common/http | `common/src/main/java/com/rag/common/web/`（**16 类**） | N:1 | Go 语义等价物**必须手写**（「何时读 body」「错误文案形状」都不是框架能力） |
| **Go 侧无对应物** | `service/common/exception/`（2 类） | `common/src/main/java/com/rag/common/exception/`（2 类） | 1:1 | Go 用 `(resp, error)` 双返回 ⇒ 异常是 idiomatization |
| **Go 侧无对应物** | 各 rpc 模块 `@Import` 清单（**10 份**）+ 9 份重复的 MP ordering bridge | `common/src/main/java/com/rag/common/autoconfigure/`（**7 类**）+ `AutoConfiguration.imports` | C 新增 | **D3 的载体**：消费方零 `@Import` 清单 |
| **Go 侧无对应物**（Go 出处在 `service/user/common/`，不在本层） | `service/users/users-common/jwt/` + `cryptx/`（3 类） | `common/src/main/java/com/rag/common/security/`（3 类） | 1:1 | 从 users 域**提升为全局**（鉴权是安全边界，同一规则不该有第二份实现） |
| **Go 侧无对应物**（Go 出处在 `service/user/common/errmsg/`，不在本层） | `service/users/users-common/errmsg/ErrorCode.java` | **无** ⇒ 下沉到**各域模块内的 `contract/` 包**（`service/<域>/src/main/java/com/rag/<域>/contract/`；**D7 后不再是独立 `<域>-contract` 模块**） | N:1 | ⚠️ 见 §4 与 §7 的「三列职责对不上 #3」 |

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `service/common/`（模块） | `common/`（artifactId `common`）| **升级为真 starter**（D3：`AutoConfiguration.imports` 注册 7 个 `@AutoConfiguration`）|
| `service/common/…/communityfact/` | `common/src/main/java/com/rag/common/communityfact/` | 直接压平（13 个类）|
| `service/common/…/exception/` | `common/src/main/java/com/rag/common/exception/` | 直接压平 |
| `service/common/…/http/` | `common/src/main/java/com/rag/common/web/` | 压平 + **包改名 `http` → `web`**（`（无前缀）*` HTTP 兼容层归入 `web/`）|
| `service/common/…/logger/` | `common/src/main/java/com/rag/common/logger/` | 直接压平 |
| `service/common/…/observability/` | `common/src/main/java/com/rag/common/observability/` | 直接压平 |
| `service/common/…/persistence/` | `common/src/main/java/com/rag/common/persistence/` | 直接压平 |
| `service/common/…/protection/`（17 类） | `common/src/main/java/com/rag/common/protection/`（**2 类**）| ★ **D4 生态优先**：熔断 → resilience4j，降载/限体积各 1 个自研类 |
| `service/common/…/response/`（`Response`） | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 压平 + **改名**（信封是 L1）|
| `service/common/…/snowflake/` | `common/src/main/java/com/rag/common/snowflake/` | 直接压平 |
| `service/common/…/spring/` | `common/src/main/java/com/rag/common/autoconfigure/` | 压平 + **改名**（`@Configuration` → `@AutoConfiguration`，D3）|
| **—（B 侧无）** | `common/src/main/java/com/rag/common/contract/`（`ApiResponse`）| C 侧唯一的 `contract/` 包（**全局信封**，与各域错误契约无关）|

## 1. 包结构对照

### 1.1 `autoconfigure/` —— starter 的全部装配入口（C: 7 类）

> **D3 的载体。** 7 个 `@AutoConfiguration` 由
> `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册（7 行）。
> ⇒ **消费方零 `@Import` 清单**（RAG 侧有 10 份）。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| —（Go 侧无对应物：Go 无自动装配机制） | `spring/HttpMiddlewareFilter.java` + `spring/AuthorityHandlerFactory.java` | `common/src/main/java/com/rag/common/autoconfigure/WebAutoConfiguration.java` | N:1 | 注册 servlet filter + `ServletRegistrationBean`；⚠️ Boot 4 的 `addUrlMappings` API |
| —（Go 侧无对应物） | `http/GoStyleJsonBodyConverterConfig.java` + `users-common/http/GoStyleCoercion.java` | `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | N:1 | 收编 Jackson `CoercionConfig`（K-01，**已是原生机制**）+ `JsonBodyConverter` 注册（K-02） |
| —（Go 侧无对应物） | `spring/ObservabilityLifecycle.java` + `spring/ObservabilityProperties.java` + `observability/RpcInterceptor.java` + `observability/HttpMiddleware.java` + `observability/MqConsumer.java` + `observability/GoStyleRecoverInterceptor.java` + `spring/RpcServerInterceptorFactory.java` | `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | N:1 | ⚠️ **B 侧 `RpcServerInterceptorFactory` 曾经「Bean 在、没挂上」**（`DRIFT-COMMON-004`）⇒ C 侧必须断言真的进了 `server.intercept` |
| —（Go 侧无对应物：go-zero 内置熔断/降载/限连接/限体积） | `protection/`（17 类）+ `protection/GoStyleProtectionConfiguration.java` + `protection/GoStyleProtectionProperties.java` + `spring/RpcServerInterceptorFactory.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` | N:1 | resilience4j `CircuitBreaker` + Tomcat `max-connections` + 1 个 `MaxBytesFilter` + 1 个 CPU 指标（D-02 / D-04）**＋ `GrpcServerConfigurer`（gRPC 保护链的唯一挂载点，见 §7 #6 与设计文档 §5.5.2）** |
| —（Go 侧无对应物） | `spring/SnowflakeBean.java` + `spring/SnowflakeProperties.java` + `snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | N:1 | ⚠️ 与各域 `wiring/SnowflakeBootstrap.java`（3 个域有）**落点重复**，见 §7「职责对不上 #4」 |
| —（Go 侧无对应物） | 9 个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | N:1 | **消掉 9 份重复**（Boot 4 下 MP 自动配置排序桥）；断言 A6 |
| —（Go 侧无对应物：Go 在各域 `internal/logic` 里直接 `status.Error`） | `article-api/http/GoStyleGrpcStatusMapper.java` | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | 1:1 | D2/D5 的统一错误模型：`BusinessException(code,msg)` → gRPC 边界 / HTTP 边界两个适配器 |

### 1.2 `communityfact/` —— comment · favorite · like 三域共享的跨服务事件协议（C: 13 类）

> ⚠️ **三域归属（重要）**：`communityfact` 是 **comment / favorite / like 三域共享**的
> **L1 wire 协议**（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` 四个 wire 类型）。
> **本表只写共享层本体**；各域的接线（投递调度器 / authority 端点 / outbox 模型）**在各域表里**
> （`comment.md` / `favorite.md` / `like.md`），本表不重复。
>
> ⚠️ **但 Go 真源的实际消费方只有 like + comment** —— favorite 在 Go 侧**没有**引用
> `service/common/communityfact`，而是自己另写了一套（`service/favorite/rpc/internal/authority/http.go` +
> `service/favorite/rpc/internal/model` 的 `NewFavoriteDCEventSender`）。
> ⇒ 「三域共享」是 **RAG-V1 的设计意图**，不是 Go 的事实。见 §7「职责对不上 #1」。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `communityfact/wire.go`（`type Event struct`） | `communityfact/Wire.java`（嵌套 `Event`） | `common/src/main/java/com/rag/common/communityfact/Event.java` | 1:N | 9 字段；`aggregate_version` 是 `int64` **非指针**；`payload` 在 Go 侧是 `json.RawMessage`（**逐字输出**） |
| `communityfact/wire.go`（`type TechnicalReceipt struct`） | `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/TechnicalReceipt.java` | 1:N | |
| `communityfact/wire.go`（`type SubjectRef struct` + `ParseRTWSubject`） | `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/SubjectRef.java` | 1:N | ⚠️ 要求 `uid>0` **且** `FormatInt(uid,10)==id`（拒绝 `+1001` / `01001`） |
| `communityfact/wire.go`（`type AuthorityFact struct`） | `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/AuthorityFact.java` | 1:N | |
| `communityfact/wire.go`（`const DeliveryPending/Accepted/Failed/Blocked`） | `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/DeliveryStatus.java` | 1:N | 取值 0/1/2/3 |
| `communityfact/wire.go`（`CanonicalHash` + `ValidHash`） | `communityfact/Wire.java#canonicalHash/validHash` | `common/src/main/java/com/rag/common/communityfact/CanonicalHash.java` | 1:N | ⚠️ **RFC 8785 JCS 规范化后再 SHA-256**；Jackson 的 `SORT_PROPERTIES_ALPHABETICALLY` **不等价** ⇒ 指纹不一致会破坏幂等（AGENTS.md §1.1c **G-2**） |
| `communityfact/wire.go`（`StrictDecode` / `ValidateReceipt` / `Sender` / `DCEventSender` / 2 个 sentinel） | `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/Wire.java` | 1:1 | C 保留 `Wire` 作门面；`StrictDecode` = `FAIL_ON_UNKNOWN_PROPERTIES` + 拒尾随 JSON |
| `communityfact/wire.go`（`type Sender interface` + `DCEventSender`） | `communityfact/Wire.java`（内部类 `DCEventSender`） | `common/src/main/java/com/rag/common/communityfact/EventSender.java` | 1:N | 6 条 endpoint/token 校验 + 64KB body 限流 + 丢弃 body + `Idempotency-Key = event.eventId` |
| `communityfact/process.go`（`type Dispatcher interface`：`DispatchFactOnce`） | `communityfact/Dispatcher.java`（内部 `DispatcherImpl`） | `common/src/main/java/com/rag/common/communityfact/Dispatcher.java` | 1:1 | **域侧 store SPI** —— 由 comment / favorite / like 各域实现 |
| `communityfact/process.go`（`RunDispatcher` 排空循环 + `deliveryErrorCode`） | `communityfact/Dispatcher.java#run` | `common/src/main/java/com/rag/common/communityfact/EventDispatcher.java` | 1:N | 批次 16 / 1s ticker / 单次 10s deadline / `once` 模式；失败用**同一个 frozen event** 重试 |
| `communityfact/authority_http.go` | `communityfact/AuthorityHandler.java` + `spring/AuthorityHandlerFactory.java` | `common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java` | 1:1 | `GET /internal/v1/community/facts`；**404 vs 503 的区分是 fail-closed 设计**（authority 不可用不能伪装成「事实不存在」） |
| `communityfact/logging.go`（`type ProcessMetadata struct` + `Validate`） | `communityfact/ProcessLogger.java`（内部 `ProcessMetadata`） | `common/src/main/java/com/rag/common/communityfact/ProcessMetadata.java` | 1:N | `version` 匹配 `^[0-9a-f]{40}$`；`environment ∈ {local,test,staging,production}` |
| `communityfact/logging.go`（OBS-r3 envelope + trace handler + 本地 OTel） | `communityfact/ProcessLogger.java` | `common/src/main/java/com/rag/common/communityfact/ProcessLogger.java` | 1:1 | ⚠️ **不得使用全局 MDC**（三域合并成一个 JVM 后会跨线程插值）⇒ Go 的 `WithAttrs` 绑实例的语义必须保留。⚠️ **Go 的 `InstallFrameworkLogger`（框架日志并入 OBS-r3 + 键改名 `trace→trace_id` 等）两侧都缺**（`DRIFT-CF-017` 未修）⇒ C 侧不得把它当成「已实现」，也不得擅自补（补即新增行为） |

### 1.3 `contract/` —— 全局信封（C: 1 类）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `response/response.go`（`Response{code,msg,data}`） | `response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | ⚠️ **L1 契约**：字段名 / 顺序 / 无 `omitempty`（`data:null` 恒出现）必须逐字保留；**不可用 `ProblemDetail` 替换**（K-06） |

### 1.4 `exception/` —— 业务异常（C: 2 类）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| —（Go 侧无对应物：Go 用 `(resp, error)` 双返回） | `exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | 1:1 | 只接 `int` 业务码 + `String` 文案；**不耦合** Web / gRPC |
| —（Go 侧无对应物） | `exception/AuthenticationException.java` | `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | 1:1 | 与 `BusinessException` 只差一条：**HTTP 状态码是 401**（`DRIFT-004` 引入） |

### 1.5 `logger/` —— SLF4J 适配器（C: 4 类）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `logger/logger.go`（`Logger` + 3 个日志入口） | `logger/Logger.java` | `common/src/main/java/com/rag/common/logger/GoStyleLogger.java` | 1:1 | ⚠️ **待执行的改名**：设计已定「改名与归位」（`Logger` → SLF4J 适配器），**树里仍是 `GoStyleLogger`** ⇒ 归位时须同步改 import |
| `logger/logger.go`（`type LogOption func(*LogOptions)`） | `logger/LogOption.java` | `common/src/main/java/com/rag/common/logger/LogOption.java` | 1:1 | `@FunctionalInterface` |
| `logger/logger.go`（`type LogOptions struct`） | `logger/LogOptions.java` | `common/src/main/java/com/rag/common/logger/LogOptions.java` | 1:1 | `UserID` / `ArticleID`；空串 ⇒ **整字段不出现** |
| `logger/logger.go`（`LogFatal` → `os.Exit(1)`） | `logger/FatalError.java` + `Logger.doLogFatal` | `common/src/main/java/com/rag/common/logger/FatalError.java` | 1:1 | ⚠️ **B 侧 `FatalError` 是修复产物**（普通 `IllegalStateException` 会被 `@ExceptionHandler` 吞掉 ⇒ fatal 后进程继续服务） |
| `logger/logger.go`（`WithUserID` / `WithArticleID`） | `logger/WithUserID.java` + `logger/WithArticleID.java` | （并入 `common/src/main/java/com/rag/common/logger/LogOption.java` 的静态工厂 / lambda） | N:1 | C 无独立文件；`LogOption` 是 `@FunctionalInterface` ⇒ 调用方仍可写 lambda |
| `logger/logger.go`（`Init` 全局单例） | `spring/LoggerFactoryBean.java`（369 行，与 `Logger` **零行为差异**） | （并入 `common/src/main/java/com/rag/common/logger/GoStyleLogger.java`） | N:1 | B 侧「静态轨 + Bean 轨」双轨并存 ⇒ C 合并为单轨 |
| `logger/logger.go`（`getCallChain` + `isFiltered` 过滤框架帧） | `logger/Logger.java#getCallChain/isFiltered` | （并入 `common/src/main/java/com/rag/common/logger/GoStyleLogger.java`） | 1:1 | ⚠️ `call_chain` 是**行为契约**（SLF4J 无等价 ⇒ AGENTS.md §1.1c **G-4**）；过滤失效会让它变成不可读的栈转储 |

### 1.6 `observability/` —— Observation + Deadline（C: 3 类）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `observability/observability.go`（常量 + `Mark{Business,System,Timeout,DurationAndSlow}` + `IsTimeoutError`） | `observability/Observability.java` + `observability/ObservabilityConstants.java` | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | N:1 | Micrometer Observation + OTel bridge 承担 span 写入（D-03）；⚠️ `sea.slow=true` 与 `sea.biz_code` 是**业务定义的标记，必须保留** |
| `observability/observability.go`（`SlowThreshold` 读 env） | `observability/Observability.java#slowThreshold` + `spring/ObservabilityProperties.java` | `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` | 1:1 | ⚠️ Go 侧 `MarkBusinessError` 读的是 **env** 而不是 yml ⇒ 这条路径上 yml 不生效，**不能顺手修正** |
| `observability/http.go`（`rest.Middleware` 超时 + 全 buffer response writer） | `observability/HttpMiddleware.java` + `spring/HttpMiddlewareFilter.java` | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | N:1 | Micrometer `Observation` 承担；⚠️ Go 侧超时写 **499**（客户端取消）/ **503**（服务端超时）两分支，B 侧**漏了 499 写回**（`DRIFT-COMMON-011` 未修） |
| `observability/rpc.go`（`grpc.UnaryServerInterceptor` + 7 个业务 gRPC code 集） | `observability/RpcInterceptor.java` + `spring/RpcServerInterceptorFactory.java` + `observability/GoStyleRecoverInterceptor.java` | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | N:1 | 7 个业务 code 集合（`InvalidArgument/NotFound/AlreadyExists/PermissionDenied/Unauthenticated/FailedPrecondition/OutOfRange`）是行为语义 |
| `observability/mq.go`（`TraceConsumer` span 包装） | `observability/MqConsumer.java` | （并入 `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java`） | 1:1 | ⚠️ Go 侧**不含** ack / drop / 重试 / offset 提交 ⇒ C 侧同样不能加（加了就是新增行为） |
| —（Go 侧无对应物：Go 靠 `context.WithTimeout` 的 ctx-aware 取消） | `observability/GoStyleRequestDeadline.java` | `common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java` | 1:1 | ⚠️ **K-03 保留项**：Java 的 `Future.cancel(true)` 对阻塞 IO 无效 ⇒ 必须走服务端取消语义（`setQueryTimeout`） |

### 1.7 `persistence/` —— 超时注入 + JSON TypeHandler（C: 4 类）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| —（Go 侧无对应物：Go 靠 `context` + `database/sql` 的 ctx-aware 查询，无拦截器） | `persistence/DeadlineQueryTimeoutInjector.java` | `common/src/main/java/com/rag/common/persistence/DeadlineQueryTimeoutInjector.java` | 1:1 | 把当前 rpc 的 deadline 落到每条 SQL 的 `setQueryTimeout`；⚠️ **无 deadline 时不注入** |
| —（Go 侧无对应物） | `persistence/SqlTimeoutStatusTranslator.java` | `common/src/main/java/com/rag/common/persistence/SqlTimeoutStatusTranslator.java` | 1:1 | `SQLState=57014` → `DEADLINE_EXCEEDED`；⚠️ **必须拦 `Executor` 而非 `StatementHandler`**，且 `catch(SQLException)` 匹配不上 MyBatis 抛的 `InvocationTargetException` |
| —（Go 侧无对应物：Go 侧 JSONB/JSON 字段由 GORM 直接读写） | 5 份 `JsonbTypeHandler.java`（`like-rpc` / `comment-rpc` / `favorite-rpc` / `admin-rpc` / `article-rpc` 各一份） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | N:1 | **7 份副本合 1** |
| —（Go 侧无对应物） | 2 份 `JsonTextTypeHandler.java`（`user-rpc` / `admin-rpc` 各一份） | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` | N:1 | **副本合 1** |

### 1.8 `protection/` —— 请求保护链（C: 2 类）

> ⚠️ **B 侧 17 类 → C 侧 2 类**，是全表最大的形态变化。
> 根因：Go 侧这些能力**全是 go-zero 内置**（`rest/config.go` 的 `Middlewares.Breaker` / `CpuThreshold` / `MaxConns` / `MaxBytes`），
> RAG 只能手写复刻；RAG-V1 按 **D4 生态优先**把它们交回生态。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| —（Go 侧无对应物：go-zero `rest` 内置 `Middlewares.Breaker`） | `protection/GoStyleBreaker.java` + `GoStyleBreakerFilter.java` + `GoStyleCircuitOpenException.java` + `GoStyleStatusCapturingResponse.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（resilience4j `CircuitBreaker` + `CircuitBreakerRegistry`） | N:1 | ⚠️ **D-02 L3 降级**：失败判定窗口 / half-open 策略与 go-zero 不同；隔离 key 仍是 `METHOD://path`（**每路由一个实例**） |
| —（Go 侧无对应物：go-zero `zrpc` 内置 `UnaryBreakerInterceptor`） | `protection/GoStyleRpcBreakerInterceptor.java` + `GoStyleRpcFailurePolicy.java` + `GoStyleRpcProtectionChain.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（gRPC server interceptor 装配） | N:1 | ⚠️ **链序是行为语义**：Go 注册序 Trace → Recover → Stat → Prometheus → **Breaker** → Shedding → Timeout；失败判据是 `errors.Is` 深查 + 6 码白名单浅查 |
| —（Go 侧无对应物：go-zero `zrpc` 内置 client 侧 `BreakerInterceptor`） | `protection/GoStyleGrpcClientBreakerInterceptor.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（devh `@GrpcGlobalClientInterceptor`） | N:1 | ⚠️ `@GrpcGlobalClientInterceptor` + `@Component` **缺一即静默失效**（Bean 在、channel 里没有） |
| —（Go 侧无对应物：go-zero `rest` 内置 `CpuThreshold` 900‰） | `protection/GoStyleCpuReader.java` + `GoStyleAdaptiveShedder.java` + `GoStyleCpuSheddingFilter.java` + `GoStyleSheddingStat.java` | `common/src/main/java/com/rag/common/protection/CpuThresholdHealthIndicator.java` | N:1 | ⚠️ **生态无等价物**（AGENTS.md §1.1c **G-1**）⇒ 自研 1 类；**拒绝响应形状（裸状态码 + 空 body + 无 Content-Type）必须保留**（D-04） |
| —（Go 侧无对应物：go-zero `rest` 内置 `MaxBytes` 1048576） | `protection/GoStyleMaxBytesFilter.java` | `common/src/main/java/com/rag/common/protection/MaxBytesFilter.java` | 1:1 | 生态无等价 ⇒ 1 个 filter；⚠️ 判据是 `ContentLength > n` ⇒ 413 空 body |
| —（Go 侧无对应物：go-zero `rest` 内置 `MaxConns` 10000） | `protection/GoStyleMaxConnsFilter.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（Tomcat `server.tomcat.max-connections`） | 1:1 | **Tomcat 原生项 ⇒ 零 Java 代码**；⚠️ RAG 的「总参数个数 vs 非空值个数」口径差异（`DRIFT-HOTAPI-030`）**仍未决** |
| —（Go 侧无对应物） | `protection/GoStyleProtectionConfiguration.java` + `GoStyleProtectionProperties.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` | N:1 | `@EnableConfigurationProperties` 由 auto-config 接管 ⇒ 消费方零 `@Import`（B 侧曾因漏声明导致 23 条测试全红） |

### 1.9 `security/` —— JWT + 口令（C: 3 类）

> ⚠️ **A 侧无对应物 —— Go 出处不在 `service/common/`，而在 `service/user/common/`。**
> 即：**C 的 `common/src/main/java/com/rag/common/security/` 的 Go 出处是 Go 的 users 域共享包**（RAG-V1 有意把它提升为全局）。
> ⇒ 这三行**不计入 §7 的「A 列覆盖」**，但**计入 C 列覆盖**。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| —（A 侧无对应物；Go 出处 = `service/user/common/jwt/jwt.go`，**不在 `service/common/`**） | `users-common/jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | 1:1 | ⚠️ claim 名 `userId`（`Long`）+ `exp` + `iat`；签名算法必须**钉死 HS256**（jjwt 的 `Keys.hmacShaKeyFor` 会随密钥长度漂到 HS384/HS512） |
| —（A 侧无对应物；Go 出处 = `service/user/common/jwt/jwt.go` 的 `Authorization` 头解析） | `users-common/jwt/BearerTokenExtractor.java` | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | 1:1 | ⚠️ Go 侧**两套互不相同**的取法（验签 vs 黑名单 key）必须**各自保留**，合并即等于擅自修好 Go 的 bug |
| —（A 侧无对应物；Go 出处 = `service/user/common/cryptx/password.go`） | `users-common/cryptx/PasswordUtil.java` | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | 1:1 | ⚠️ Go 把「密码不匹配」与「hash 本身非法」**压成同一个 false** ⇒ 畸形 hash 不得抛异常 |

### 1.10 `snowflake/` —— 分布式 ID（C: 1 类）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `snowflake/snowflake.go`（41 时间 + 10 worker + 12 seq，epoch `1288834974657`） | `snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` | 1:1 | ⚠️ epoch / 位段是**业务契约**（like 域游标编码依赖 `>>22`） |
| `snowflake/snowflake.go`（`Init` / `GetID` / `generateNodeId`） | `spring/SnowflakeBean.java` + `spring/SnowflakeProperties.java` | `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | N:1 | `SNOWFLAKE_NODE_ID` 优先，parse 失败**继续走 hash 不报错**；否则 `sha256("SERVICE_NAME-PID")` 取前 2 字节大端 ⇒ `%1024` |

### 1.11 `web/` —— Go 语义的 HTTP 层（C: 16 类）

> ⚠️ **B 侧 `service/common/http/` 只有 3 类，C 侧 `web/` 有 16 类** ⇒ **13 类是从各域 api 模块归位过来的**。
> 根因（设计文档 §5.5）：Jackson 的 `CoercionConfig` 是原生机制，但**「何时才读请求体」与「错误文案 / 状态码形状」不是框架能力** ⇒ 必须手写。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
|—（Go 侧无对应物：go-zero `httpx.Parse` 内置）|`common/http/GoStyleJsonBodyConverter.java`|`common/src/main/java/com/rag/common/web/JsonBodyConverter.java`|1:1|⚠️ **K-02**：判据是 `ContentLength > 0` 且 Content-Type **字面**含 `application/json` ⇒ `application/vnd.api+json` 在 Go 侧**不**匹配|
|—（Go 侧无对应物）|`common/http/GoStyleJsonBodyConverterConfig.java`|`common/src/main/java/com/rag/common/web/JsonBodyConverterConfig.java`|1:1|注册点；⚠️ C 侧最终应并入 `JsonBodyConverterAutoConfiguration`（见 §7「职责对不上 #5」）|
|—（Go 侧无对应物：go-zero `httpx.Error` 内置）|`common/http/GoStyleRouterErrorResponses.java`|`common/src/main/java/com/rag/common/web/RouterErrorResponses.java`|1:1|400 + **text/plain** 的错误形状（D-01 只降级**文案**，形状不受影响）|
| —（Go 侧无对应物：go-zero `core/mapping` 内置） | `users-common/http/GoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` | 1:1 | ⚠️ **K-01**：Jackson 原生 `CoercionConfig` ⇒ **不是自研**；数字/布尔不进 String 字段 |
| —（Go 侧无对应物） | `users-common/http/GoStyleStringInt64.java` + `message-api/dto/GoStyleStringInt64.java`（**2 份**） | `common/src/main/java/com/rag/common/web/StringInt64.java` | N:1 | 副本合一 |
| —（Go 侧无对应物） | `like-api/dto/GoStyleFloat64Deserializer.java` | `common/src/main/java/com/rag/common/web/Float64Deserializer.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `like-api/dto/GoStyleRawStringListDeserializer.java` | `common/src/main/java/com/rag/common/web/RawStringListDeserializer.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `message-api/config/GoStyleMapStringValueDeserializer.java` | `common/src/main/java/com/rag/common/web/MapStringValueDeserializer.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `article-api/dto/GoStyleStringFields.java` | `common/src/main/java/com/rag/common/web/StringFields.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `article-api/http/GoStyleBadRequestException.java` | `common/src/main/java/com/rag/common/web/BadRequestException.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `article-api/http/GoStyleHttpErrorException.java` | `common/src/main/java/com/rag/common/web/HttpErrorException.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `article-api/http/GoStylePlainTextErrors.java` | `common/src/main/java/com/rag/common/web/PlainTextErrors.java` | 1:1 | 归位；⚠️ **真 Tomcat 下 400 的 `Content-Type` / `nosniff`**（MockMvc 测不到） |
| —（Go 侧无对应物） | `article-api/http/GoStyleGrpcStatusMapper.java` | `common/src/main/java/com/rag/common/web/GrpcStatusMapper.java` | 1:1 | ⚠️ **与 `GrpcExceptionMapperAutoConfiguration` 职责重叠**，见 §7「职责对不上 #2」 |
| —（Go 侧无对应物） | `article-api/config/GoStyleGetFormValuesFilter.java` | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` | 1:1 | 归位 |
| —（Go 侧无对应物） | `article-api/config/GoStyleJsonBodyLimitFilter.java` | `common/src/main/java/com/rag/common/web/JsonBodyLimitFilter.java` | 1:1 | 归位；⚠️ 与 `protection/MaxBytesFilter.java` **语义相邻但不同**，别合并 |
| —（Go 侧无对应物） | `article-api/config/GoStylePathNormalizingFilter.java` | `common/src/main/java/com/rag/common/web/PathNormalizingFilter.java` | 1:1 | 归位 |

### 1.12 §1 决策锁

**换 starter 装配形态（D3 · 19 处同时改）**：
1. 删 10 个 rpc 模块入口类上的 `@Import({...})` 清单（`LikeRpcApplication` / `CommentRpcApplication` / `FollowRpcApplication` / `MessageRpcApplication` / `FavoriteRpcApplication` / `AdminRpcApplication` / `UserRpcApplication` / `TaskRpcApplication` / `HotRpcApplication` / `ArticleRpcApplication`）；
2. 删 9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration`（`like-rpc` / `comment-rpc` / `follow-rpc` / `message-rpc` / `favorite-rpc` / `admin-rpc` / `user-rpc` / `task-rpc` / `article-rpc`），收编进 `MybatisPlusOrderingBridgeAutoConfiguration`；
3. `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 写全 7 行；
4. 7 个 `@AutoConfiguration` 全部加 `@ConditionalOnMissingBean`；
5. `common/pom.xml` 加 `spring-boot-autoconfigure` + `spring-boot-starter-web` + `spring-boot-starter-actuator` + `micrometer-registry-prometheus` + `spring-boot-starter-data-redis` + `spring-boot-kafka` + `mybatis-plus-spring-boot3-starter` + `resilience4j-spring-boot3` + `grpc-stub` + `jjwt-api/impl/jackson`；
6. 消费方 `pom.xml` 依赖 `common` 时**不得**再出现 `@Import`（D3 的连带效应：两者不能并存）；
7. 守门脚本加断言「A5：每个模块的 `@Import` 清单为空」+「A6：MP bridge 全仓只有一份」。

**为什么换**：Spring 生态里共享能力层的标准形态是 starter（`AutoConfiguration.imports`），不是「普通 jar + 每个消费者手写 `@Import` 清单」。
RAG 形态实测 10 种 `@Import` 写法、9 份 MP bridge 副本 —— 这就是「普通 jar 放不下 auto-config」的直接账单。

**换生态保护层（D4 · 15 处同时改）**：
1. 删 `protection/` 的 15 个手写类（保留 2 个：`CpuThresholdHealthIndicator` + `MaxBytesFilter`）；
2. `ProtectionAutoConfiguration` 引入 resilience4j `CircuitBreakerRegistry`，按 `METHOD://path` 建实例；
3. 熔断失败判据按 Go 的「深查 + 6 码白名单」重写为 resilience4j 的 `ignoreExceptions` / `recordException`；
4. `MaxConns` 从 `GoStyleMaxConnsFilter` 改为 Tomcat `server.tomcat.max-connections`；
5. `MaxBytes` 保留 1 个 `OncePerRequestFilter`；
6. CPU 降载保留 1 个 `CpuThresholdHealthIndicator`（读 `/proc/stat` 千分比，**不可用时 fail-open**）；
7. gRPC server 侧的 Breaker / Shedding 拦截器在 `ProtectionAutoConfiguration` 里装配，并**断言真的进了 `server.intercept`**；
8. gRPC client 侧的 Breaker 拦截器必须同时带 `@GrpcGlobalClientInterceptor` + `@Component`；
9. 把 D-02 / D-04 登记进 [`L3-降级清单.md`](../L3-降级清单.md)；
10. 对拍脚本**显式排除**熔断策略差异，否则全红 ⇒ 会被误判为回归 ⇒ 触发「修回」⇒ 降级被静默撤销。

**为什么换**：go-zero 的熔断 / 降载 / 限连接是**框架内置**（白送），RAG 手写复刻是「补框架差距」的成本，不是「翻译成本」；
能交给生态的都交出去（D4），**生态无等价物的只有 2 处**（G-1 CPU 降载、G-4 `call_chain`）⇒ 自研 2 类。

**换 communityfact 类型粒度（1:N · 13 处同时改）**：
1. `Wire.java` 里的 4 个嵌套 wire 类型拆成 `Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` 4 个文件；
2. `DeliveryStatus` 常量块拆成独立枚举文件；
3. `CanonicalHash` / `ValidHash` 拆成独立类（**JCS 依赖必须显式**）；
4. `Sender` 接口 + `DCEventSender` 拆成 `EventSender`；
5. `Dispatcher` 接口（域侧 SPI）留在 `Dispatcher.java`；
6. `RunDispatcher` 循环拆到 `EventDispatcher.java`；
7. `ProcessMetadata` 从 `ProcessLogger` 里拆出；
8. `Wire.java` 保留为门面（`StrictDecode` / `ValidateReceipt` / 2 个 sentinel）；
9. 4 个 wire 类型的 **JSON tag 逐个核对**（L1）；
10. `CanonicalHash` 的规范化顺序**先规范化再哈希**；
11. `ParseRTWSubject` 的字面量规范化校验保留；
12. `Event.payload` 在 Go 侧是 `json.RawMessage` ⇒ C 侧**逐字输出**（`DRIFT-CF-004` 未决）；
13. `communityfact` 的消费者接线**不在本层**（各域表）。

**为什么换**：Go 把 4 个 L1 wire 类型 + 校验 + sender 全塞进一个 `wire.go`；拆成 1 类型 1 文件后，
「哪个类型是 L1 wire 契约」一眼可见，域侧接线时不会漏掉字段号 / tag。

---

## 2. 持久层差异

> **本层的持久面很窄**：`common/` 本身**零 GORM 模型、零 SQL、零事务**。
> 它只提供 4 类**跨域复用的持久层配件**（超时注入 + 状态翻译 + 2 个 TypeHandler）+ 1 个 MP 自动配置。

| 维度 | A Go | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| **ORM** | GORM（`AutoMigrate` 建表，`service/*/rpc/internal/model/db.go`） | MyBatis-Plus `BaseMapper` + `@TableName` | MyBatis-Plus `BaseMapper` + `@TableName` | 表达层差异，L2 语义（查询条件 / 唯一约束 / 读改写语义）逐条保留 |
| **建表机制** | `db.AutoMigrate(&LikeRecord{}, ...)` —— 进程内幂等重放 | `*SchemaInitializer` 幂等重放（**Flyway 已弃用**） | `wiring/*SchemaGate.java` / `*SchemaBootstrap.java` 幂等重放；`resources/db/migration/` **只有 `.gitkeep`（无 Flyway 文件）** | ⚠️ C 侧**无 Flyway** ⇒ 不要再按「Flyway vs SchemaInitializer」写决策 |
| **MP 自动配置** | — | 9 个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration`（Boot 4 排序桥） | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`（**1 份**） | 断言 A6 |
| **JSON 列 typeHandler** | GORM 直接读写（`jsonb` / `json`） | `JsonbTypeHandler` × 5 份 + `JsonTextTypeHandler` × 2 份 | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` + `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java`（**各 1 份**） | ⚠️ RAG-V1 侧 `common/src/main/java/com/rag/common/persistence/` 的 2 个 TypeHandler 是**归位**，不是新增能力 |
| **SQL 超时** | `context.WithTimeout` + ctx-aware 查询（**天然**） | `DeadlineQueryTimeoutInjector`（`StatementHandler#prepare` 拦截）+ `SqlTimeoutStatusTranslator`（`Executor` 拦截） | 同 B（1:1 保留） | ⚠️ **K-03**：Java 不能取消阻塞 IO ⇒ 必须走 pgjdbc `CancelRequest`；且 `catch(SQLException)` **匹配不上** MyBatis 抛的 `InvocationTargetException` |
| **事务边界 / 行锁 / 幂等键** | 逐条保留 | 逐条保留 | 逐条保留（**K-05，不属本层**） | 本层**零 `@Transactional`** ⇒ 无 self-invocation 风险 |
| **雪花 ID** | `snowflake.GetID()`（41/10/12，epoch `1288834974657`） | `Snowflake` + `SnowflakeBean` + `SnowflakeProperties`（**静态轨 + Bean 轨双路径**） | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | ⚠️ **全进程只有一份生成状态**（B 侧 `bindBean` 静态委托有 ID 双计数器风险）；⚠️ **C 侧另有 3 个域级 `SnowflakeBootstrap`** ⇒ 落点重复（§7 #4） |

### 2.1 §2 决策锁

**换 typeHandler 归属（7 处同时改）**：
1. 删 `like-rpc/mapper/JsonbTypeHandler.java`；
2. 删 `comment-rpc/mapper/JsonbTypeHandler.java`；
3. 删 `favorite-rpc/mapper/JsonbTypeHandler.java`；
4. 删 `admin-rpc/mapper/JsonbTypeHandler.java`；
5. 删 `article-rpc/mapper/JsonbTypeHandler.java`；
6. 删 `user-rpc/mapper/JsonTextTypeHandler.java` + `admin-rpc/mapper/JsonTextTypeHandler.java`（合计 2 个文件，合计 7 个文件）；
7. 各域 `Mapper` 的 `@TableField(typeHandler = ...)` 改为引用 `com.rag.common.persistence.*`（**包名变了 ⇒ import 要全改**）。

**为什么换**：同一份 typeHandler 有 7 个副本 ⇒ 任何一处修 bug 都会漏另外 6 处（这正是 RAG 的 `DRIFT-010` 类问题的来源：
「同一规则的第二份实现本身就是缺陷来源」）。

**换雪花 ID 装配（4 处同时改）**：
1. `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` 声明唯一的 `Snowflake` Bean；
2. 删 3 个域级 `wiring/SnowflakeBootstrap.java`（`article` / `user` / `admin`），改由 starter 提供；
3. `worker-id` 配置从各域 `application.yml` 移到 starter 的配置前缀；
4. 加断言「容器内 `Snowflake` **有且仅有 1 个**」（双 Bean ⇒ ID 双计数器 ⇒ 撞号）。

**为什么换**：雪花 ID 的生成状态必须**全进程唯一**；B 侧的「静态轨 + Bean 轨」和 C 侧「starter + 域级 wiring」
都是两份落点 ⇒ 一旦同时生效就会出现两个独立计数器 ⇒ **ID 重复**。

---

## 3. HTTP / DI 差异

### 3.1 请求保护链（recover → breaker → shedding → 业务观测）

| 维度 | A Go | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| **HTTP 中间件形态** | go-zero `rest.Middleware`（`rest/handler/*handler.go`） | `jakarta.servlet.Filter`（`GoStyleBreakerFilter` / `GoStyleCpuSheddingFilter` / `GoStyleMaxBytesFilter` / `GoStyleMaxConnsFilter`） | resilience4j + Tomcat + `MaxBytesFilter` + Actuator 健康检查 | **链序是行为语义**：Go 的 `Middlewares` 顺序（Trace → Recover → Breaker → Shedding → Prom → Timeout …）必须逐条对拍（G-3） |
| **熔断** | go-zero 内置 `googlebreaker.go`（window 10s / buckets 40 / k 1.5） | `GoStyleBreaker`（逐行复刻） | resilience4j `CircuitBreaker` | ⚠️ **D-02 L3 降级**（P2 待拍板） |
| **CPU 降载** | go-zero 内置 `adaptiveshedder.go`（`shouldDrop` 需 **CPU 过载 + 在途超动态上限** 两个条件） | `GoStyleAdaptiveShedder` + `GoStyleCpuReader` | `CpuThresholdHealthIndicator` | ⚠️ **G-1 生态缺口**；⚠️ 单看 CPU 会误丢请求 |
| **限连接** | go-zero 内置 `MaxConns`（默认 10000，整 server 共享闸门） | `GoStyleMaxConnsFilter` | Tomcat `server.tomcat.max-connections` | ⚠️ `DRIFT-HOTAPI-030`（总参数个数 vs 非空值个数）**仍未决** |
| **限体积** | go-zero 内置 `MaxBytes`（默认 1048576） | `GoStyleMaxBytesFilter` | `common/src/main/java/com/rag/common/protection/MaxBytesFilter.java` | 413 空 body |
| **超时** | `context.WithTimeout`（可取消在途 IO） | `HttpMiddleware`（goroutine + select）+ `GoStyleRequestDeadline`（ThreadLocal） | `GoStyleRequestDeadline` + `DeadlineQueryTimeoutInjector` | ⚠️ **K-03**；⚠️ Go 的 **499（客户端取消）/ 503（服务端超时）** 两分支，B 侧漏 499 写回 |
| **Recover** | go-zero `rest` / `zrpc` 内置 panic 恢复 | `GoStyleRecoverInterceptor`（gRPC 侧） | `ObservabilityAutoConfiguration` 装配 | ⚠️ grpc-java 的 `interceptCall` **不在 handler 执行栈上** ⇒ 回调是清理路径不是捕获路径 |
| **业务观测** | `observability.go` 的 `Mark*` + `http.go` 的全 buffer writer | `HttpMiddleware` + `Observability` + `ObservabilityConstants` | Micrometer Observation + OTel bridge（`ObservabilityCustomizer`） | ⚠️ **D-03 L3 降级**；⚠️ 判定顺序「envelope 业务码 → status≥400 → 慢调用」**业务码优先** |

### 3.2 拦截器 / 错误响应 / 配置绑定 / starter 装配

| 维度 | A Go | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| **错误响应形状** | `httpx.Error` ⇒ **400 + text/plain** | `GoStyleRouterErrorResponses` + `GoStylePlainTextErrors` | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` + `common/src/main/java/com/rag/common/web/PlainTextErrors.java` | ⚠️ D-01 只降级**文案**，**状态码与结构不受影响**；⚠️ 真 Tomcat 下 400 的 `Content-Type` / `nosniff` **MockMvc 测不到**（R4/R5） |
| **错误表达模型** | `(resp, error)` 双返回 + `errmsg.NewGrpcErr(code,msg)` → `status.Error` | `BusinessException(code,msg)` + `AuthenticationException` | 同 B；边界由 `GrpcExceptionMapperAutoConfiguration` + 各域 `@RestControllerAdvice` 承担 | ⚠️ D2/D5：合并后**一份业务逻辑、两个边界适配器**；⚠️ `like` 域的 rpc 侧错误码空间是 like 自己的（**业务码塞进 gRPC status code 字段**），必须原样保留 |
|**鉴权**|`rest.WithJwt` + `CheckBlacklistMiddleware`（**两套不同的 token 取法**）|各域 `interceptor/JwtAuthInterceptor` + `common/security/BearerTokenExtractor`|`common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` + 各域 interceptor|⚠️ **「先验签、后黑名单」的顺序是行为契约**；⚠️ Go 的前缀比较是 `strings.ToUpper(tok[0:7]) == "BEARER "`|
| **配置绑定** | yaml + go-zero struct tag（**默认值藏在 tag 里**） | `@ConfigurationProperties`（41 个文件）+ `@Value` | `@ConfigurationProperties`（`ObservabilityProperties` 等）+ auto-config 的 `@EnableConfigurationProperties` | ⚠️ §3.3 纪律：「配置里没写」≠「没开」；判定必须读到**消费点** |
| **装配机制** | 手工 `new` + `sync.Once` + `ServiceContext` | **10 份 `@Import` 清单**（4~10 Bean 不等） | **`AutoConfiguration.imports`（7 行）** | ⚠️ **`@Import` 与 auto-config 不能并存**（D3 连带效应） |
| **启动期副作用** | `logger.Init` / `InstallLocalTracing` / `InstallFrameworkLogger`（进程级） | `spring/LoggerFactoryBean` + `spring/ObservabilityLifecycle` + `spring/SnowflakeBean` | `SnowflakeAutoConfiguration` + `ObservabilityAutoConfiguration` | ⚠️ Go 侧一个域有 **3 个进程级 logger 身份**（业务 RPC / fact-dispatch / fact-authority）⇒ 合并成 1 JVM 后**必须保住三份独立 logger 名与 appender** |
| **Servlet 注册** | `net.Listen` + `http.ServeMux`（`cmd/fact-authority` 独立进程） | `ServletRegistrationBean`（`AuthorityHandlerFactory`） | `WebAutoConfiguration` | ⚠️ P6-b：独立端口是否保留**待拍板** |

### 3.3 §3 决策锁

**换 DI 装配（`@Import` → auto-config · 19 处同时改）**：**同 §1.12 第一条**，此处不重复列举
（10 份 `@Import` 清单 + 9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration`）。

**换统一错误模型（D2/D5 · 6 处同时改）**：
1. 各域业务层统一抛 `BusinessException(code,msg)`；
2. gRPC 边界统一由 `GrpcExceptionMapperAutoConfiguration` 装配的 mapper 转 `StatusRuntimeException.withCode(code)`；
3. HTTP 边界统一由各域 `@RestControllerAdvice` 读 `BusinessException.code`；
4. `web/GrpcStatusMapper.java` 与 `GrpcExceptionMapperAutoConfiguration` **只留一处**（见 §7 #2）；
5. `like` 域的「业务码塞进 gRPC status code 字段」逐字保留（**不是 gRPC 标准码**）；
6. `AuthenticationException` ⇒ 401；`BusinessException` ⇒ 200 + `{code,msg}`（按 Go 的形状）。

**为什么换**：Go 侧同一份业务错误在 api / rpc 两处各写一遍（api 侧还要把 gRPC code 映射回业务码）；
合并成一个进程后只剩一份逻辑 ⇒ 两处映射必然变成一处。

**换观测链路（Micrometer Observation · 4 处同时改）**：
1. 删 `observability/HttpMiddleware.java` 的自研 span 写入，改由 `ObservationRegistry` 统一；
2. `ObservabilityCustomizer` 承载 `Mark*` 的**业务语义**（`sea.slow` / `sea.biz_code` / `sea.error.kind`）；
3. gRPC server / client / MQ consumer 三条链各注册一个 Observation；
4. D-03 登记进 `L3-降级清单.md`；**指标名与标签是业务定义，不降级**。

**为什么换**：go-zero 的 `zrpc` 内置了一条完整的 Trace → Recover → Stat → Prometheus → Breaker → Shedding → Timeout 链；
Micrometer 的观察模型与它**不是一对一映射**（D-03）⇒ 用生态拿标准化，但**业务定义的标记必须自己补**。

---

## 4. 公共模块对照

### 4.1 共享层的边界：什么归 `common/`，什么归 `<域>-common`

| 能力 | Go 侧归属 | B（RAG）归属 | C（RAG-V1）归属 | 判定理由 |
|---|---|---|---|---|
| **社区事实事件协议**（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact`） | `service/common/communityfact/`（**仅 like + comment 消费**） | `service/common/communityfact/` | `common/src/main/java/com/rag/common/communityfact/` | 跨服务 L1 wire 契约 ⇒ 全局 |
| **投递编排**（`RunDispatcher` 循环 / authority HTTP 端点） | `service/common/communityfact/` | 同左 | `common/src/main/java/com/rag/common/communityfact/` | 纯库（Go 侧由 `cmd/fact-*` 宿主进程调用）⇒ 全局 |
| **各域的 outbox 模型 / 投递调度器 / authority 端点装配** | `service/<域>/rpc/internal/model/` + `cmd/fact-*` | 各域 `rpc/` | 各域 `wiring/` + `service/` | **有域归属** ⇒ 归域，不归 `common/` |
| **业务日志门面** | `service/common/logger/` | `service/common/logger/` | `common/src/main/java/com/rag/common/logger/` | 全部 10 域共用 |
| **响应信封** | `service/common/response/` | `service/common/response/` | `common/src/main/java/com/rag/common/contract/` | L1 |
| **雪花 ID** | `service/common/snowflake/` | 同左 | `common/src/main/java/com/rag/common/snowflake/` | 全域共用 |
| **JWT / 口令** | `service/user/common/jwt/` + `cryptx/`（**users 域**） | `service/users/users-common/` | `common/src/main/java/com/rag/common/security/`（**提升为全局**） | 鉴权是安全边界 ⇒ 第二份实现即缺陷来源 |
| **错误码**（`ErrorCode`） | `service/user/common/errmsg/` + **每域 `service/<域>/common/errmsg.go`** | `users/common/errmsg/` + 各域 `exception/` | **各域模块内的 `contract/` 包**（`common/` 里**没有**） | ⚠️ **错误码是逐域隔离的**（RAG-V1 AGENTS.md §1.1b）⇒ **不能进全局 `common/`** |
| **HTTP 解析 / 错误形状** | go-zero 内置 | 散在 `service/common/http/` + 各域 api | `common/src/main/java/com/rag/common/web/` | 无域归属的 HTTP 语义 ⇒ 全局 |
| **请求保护（熔断 / 降载 / 限连接 / 限体积）** | go-zero 内置 | `service/common/protection/` | `common/src/main/java/com/rag/common/protection/` + starter | 无域归属 ⇒ 全局 |
| **DDL / schema 建表** | 各域 `rpc/internal/model/db.go` | 各域 `bootstrap/*SchemaInitializer` | 各域 `wiring/*SchemaGate` | **有域归属** ⇒ 归域 |
| **业务实体 / Mapper / 指标** | 各域 `rpc/internal/{model,metrics}` | 各域 `rpc/{domain,mapper,metrics}` | 各域 `rpc/{domain,mapper,metrics}` | 有域归属 |

> ⚠️ **RAG-V1 AGENTS.md §1.4 R1 硬规则**：`common/` 的准入 = **只放跨域共用且无域归属的能力**；每个子包要标归属域；
> 改 `common/` 必须**单写**并登记影响面。
> ⇒ RAG 侧 `service/common` 被 16 个组件依赖 ⇒ 凡修法落在这里的条目**自动从并发降级为串行**。

### 4.2 `communityfact` 的三域归属（专节）

| 域 | Go 侧 | RAG 侧 | RAG-V1 侧 | 本表覆盖 |
|---|---|---|---|---|
| `comment` | ✅ `cmd/fact-authority` + `cmd/fact-dispatch` + `internal/model/fact_*.go`（6 文件） | ✅ 5 文件 | 域内 `wiring/` + `service/` | **共享层本体** ✅ / 域内接线 ➡️ `comment.md` |
| `like` | ✅ `cmd/fact-authority` + `cmd/fact-dispatch` + `internal/model/fact_*.go`（6 文件） | 🔴 **0**（仅 javadoc 提及） | 域内 `wiring/` + `service/` | **共享层本体** ✅ / 域内接线 ➡️ `like.md` |
| `favorite` | 🔴 **0**（**自己另写了一套**：`rpc/internal/authority/http.go` + `model.NewFavoriteDCEventSender`） | 🔴 10（比 Go 多） | 域内 `wiring/` + `service/` | **共享层本体** ✅ / 域内接线 ➡️ `favorite.md` |

> ⚠️ **本表只写共享层本体**（`common/src/main/java/com/rag/common/communityfact/` 的 13 个类）；**各域的接线**（投递调度器、authority 端点、outbox 模型、
> 三份独立 logger 身份）在 `comment.md` / `favorite.md` / `like.md` 里，**本表不重复**。
>
> ⚠️ **「三域共享」是 RAG-V1 的设计意图，Go 的事实只有两域。** favorite 在 Go 侧是**平行实现**（自己的 authority + sender），
> 不是 `service/common/communityfact` 的消费者 ⇒ **是否把 favorite 收敛到共享层，是一个需要拍板的决定**（见 §7 #1）。

### 4.3 §4 决策锁

> 📌 **时点标注（2026-10-26 · D7）** —— 本小节原文的落点是「各域 `<域>-contract/` **子模块**」
> （锁 1 与锁 10）。**D7 删除了 10 个 `*-contract` 子模块** ⇒ 同一批类现在落在
> **域模块内的 `contract/` 包**（`service/<域>/src/main/java/com/rag/<域>/contract/`）。
> ⇒ **锁的意图（错误码逐域隔离）未变**，只是模块归属换了一层。

**换错误码归属（10 处同时改）**：
1. `service/<域>/<域>-contract/` 放该域的错误码契约（`ErrorCode` / 错误文案 / 语言）；
2. `common/` **不放** `ErrorCode`（⚠️ 与设计文档 §5.1 的 `contract/ErrorCode.java` 冲突，以**实际树**为准）；
3. 各域 `rpc/exception/*Exceptions.java` 只做该域的异常工厂；
4. 错误文案**逐域隔离**（不得统一）⇒ 对拍脚本必须**按域**取错误码表；
5. `like` 域的 rpc 侧错误码空间单独保留；
6. `message` / `article` / `task` / `hot` / `follow` 各自的错误码表各自独立；
7. `comment` / `favorite` / `like` 三域共享的**事件协议错误码**（`DELIVERY_ATTEMPT_FAILED` / `INVALID_FROZEN_ENVELOPE` / `SOURCE_READ_FAILED` / `RESPONSE_WRITE_FAILED` / `AUTHORITY_UNAVAILABLE`）**留在 `common/src/main/java/com/rag/common/communityfact/`**；
8. `users` 域双子域（user / admin）的错误码**分开**（admin 的码空间不与 user 混）；
9. `global` 收口域的跨域不变量登记在 `docs/DRIFT/global/`；
10. 对拍脚本的错误码来源从 `common/` 改为各域 `*-contract/`。

**为什么换**：错误码是 **L1 外部契约**且**逐域隔离** ⇒ 放进全局 `common/` 会被误当成「统一错误码空间」，
进而诱发「统一文案」的破坏性改动。

**换 JWT / 口令归属（4 处同时改）**：
1. `common/src/main/java/com/rag/common/security/JwtUtil.java` 提供签发 + 验签；
2. `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` 保留**两套** token 取法；
3. `common/src/main/java/com/rag/common/security/PasswordUtil.java` 保留「不匹配 = 非法 hash = false」的压平语义；
4. 删 `service/users/users-common/{jwt,cryptx}/`（3 个文件）⇒ 各域改为依赖 `common`。

**为什么换**：鉴权与口令是**安全边界**；同一规则的两份实现本身就是缺陷来源（RAG 的 `DRIFT-010` 就是这么来的）。

---

## 5. 决策锁（总表）

> 本节把 §1.12 / §2.1 / §3.3 / §4.3 的锁集中列一遍 —— **复刻到下一个域时按这张清单改，不漏**。

| # | 换什么 | N 处同时改 | 为什么换 | 关联决策 / 降级项 |
|---|---|---|---|---|
| L-1 | **共享层 → starter 装配** | **19**（10 份 `@Import` 清单 + 9 份 MP bridge 副本） | Spring 生态里共享能力层的标准形态是 starter，不是「普通 jar + 手写清单」 | **D3** · 断言 A5/A6 |
| L-2 | **手写保护层 → 生态** | **15**（`protection/` 17 类 → 2 类） | go-zero 内置能力是白送的；生态无等价物只有 2 处（G-1 / G-4） | **D4** · D-02 / D-04 · P2 |
| L-3 | **日志 → SLF4J 适配器** | **3**（`Logger`→`GoStyleLogger`、`LoggerFactoryBean` 合并、`With*` 合并） | 格式（`call_chain`）是行为契约，但**要接进 SLF4J 生态** ⇒ 自研 `implements org.slf4j.Logger` | **D4** · K-04 · G-4 · 断言 A8 |
| L-4 | **观测 → Micrometer Observation** | **4** | Micrometer 的观察模型与 go-zero 的中间件链不是一对一映射 | **D4** · **D-03** |
| L-5 | **限连接 → Tomcat 原生** | **2**（删 filter + 改 yml） | `MaxConns` 是 Tomcat 原生项 ⇒ 零 Java 代码 | **D4** · `DRIFT-HOTAPI-030` 仍未决 |
| L-6 | **CPU 降载 → 自研 1 类** | **1**（4 类 → 1 类） | resilience4j **无等价** ⇒ 不自研就没有这个能力 | **G-1** · **D-04** |
| L-7 | **communityfact 类型拆分** | **13** | 1 类型 1 文件后，「哪个是 L1 wire 契约」一眼可见 | — |
| L-8 | **TypeHandler 合流** | **7** | 7 份副本 ⇒ 修一处漏六处 | — |
| L-9 | **雪花 ID 单一落点** | **4** | 生成状态必须全进程唯一，否则 ID 重复 | — |
| L-10 | **统一错误模型** | **6** | 合并成一个进程后只剩一份业务逻辑 ⇒ 两处映射必然变成一处 | **D2 / D5** |
| L-11 | **错误码下沉到各域 `contract/` 包** | **10** | 错误码是 L1 且**逐域隔离** ⇒ 不能进全局 `common/` | §1.1b |
| L-12 | **JWT / 口令提为全局** | **4** | 鉴权是安全边界 ⇒ 第二份实现即缺陷来源 | — |
| L-13 | **HTTP 层归位到 `web/`** | **13**（从各域 api 归位） | Go 语义等价物**必须手写**（「何时读 body」「错误形状」不是框架能力） | K-01 / K-02 |
| L-14 | **观测 / 保护 / 雪花 / Jackson / gRPC mapper 的 auto-config 化** | **7**（7 个 `@AutoConfiguration`） | 消费方零 `@Import`；`@ConditionalOnMissingBean` 让消费方可覆盖 | **D3** |

> ⚠️ **三条执行前提**：
> ① **L-2 / L-4 / L-6 未完成对拍前不得判为完成**（D-02 明写：「未做对拍前，不得把 P2 判为 ③ 完成」）。
> ② **降级项不得反向修回**；对拍脚本必须**显式排除**降级项（否则全红 ⇒ 被误判为回归 ⇒ 触发「修回」⇒ 降级被静默撤销）。
> ③ **`@Import` 与 auto-config 不能并存** ⇒ L-1 与 L-14 必须**同一次提交**走完（否则留下半截装配）。

---

## 6. 跨模块全局约定

> 这几项**所有域共享**，改任何一域都不许漂移。
> ⚠️ **本表只列共享层承载的项**；proto 字段号 / 错误码取值 / 表名 / HTTP path / Kafka topic 的**逐域清单在各域表**。

| 项 | 值 / 规则 | 承载位置 | 备注 |
|---|---|---|---|
| **proto 字段号 / message 名** | 一个字节都不改；**根级 `proto/` 模块只放契约不放实现**（D7 后 10 域共用一个 proto 模块） | `proto/src/main/proto/*.proto` + 各域 `contract/` 包（错误契约 / 事件 DTO） | L1；本层**不承载 proto** |
| **错误码** | **逐域隔离**：码值 / 文案 / 语言**不可统一** | 各域 `contract/` 包（`ErrCodes` / `ErrMsg`）+ 各域 `exception/` 包 | ⚠️ `common/` 里**没有** `ErrorCode`（设计文档 §5.1 与实际树冲突，见 §7 #3） |
| **JWT claims** | `userId`（`Long`）+ `exp` + `iat`；签名算法 **HS256 钉死** | `common/src/main/java/com/rag/common/security/JwtUtil.java` | ⚠️ jjwt 的 `Keys.hmacShaKeyFor` 会随密钥长度漂到 HS384/HS512 ⇒ 必须显式指定 |
| **Authorization 头解析** | **两套互不相同**的取法：验签用 `tokenForSignatureVerification`，黑名单 key 用 `trimBearerPrefix`；顺序「先验签、后黑名单」 | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | ⚠️ 合并即等于擅自修好 Go 的 bug |
| **响应信封** | `{code, msg, data}` —— 字段名 / 顺序 / 无 `omitempty`（`data:null` 恒出现） | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | L1 · **K-06**（不可用 `ProblemDetail` 替换） |
| **HTTP 错误形状** | 400 + **text/plain**（含 `nosniff`） | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` + `GoStylePlainTextErrors.java` | ⚠️ D-01 只降级**文案**；⚠️ 必须**真 Tomcat** 验（MockMvc 测不到） |
| **HTTP path** | `/<域>/v1/{op}` POST 风格 | 各域 `api/controller/` | 本层不承载 |
| **表名** | `<域>_<实体>` 显式 `@TableName` | 各域 `rpc/domain/` | 本层不承载 |
| **Kafka topic** | 逐域定义 | 各域 `mq/` | 本层不承载；⚠️ Boot 4 必须引 `spring-boot-kafka`（只引 `spring-kafka` ⇒ `@KafkaListener` **静默不消费**） |
| **指标名 / 标签** | 逐字保留（如 `message_api_request_total{route,result}`） | 各域 `rpc/metrics/` + `api/metrics/` | ⚠️ **指标名是业务定义，不属 D-03 降级范围** |
| **span 属性** | `sea.slow` / `sea.biz_code` / `sea.error.kind` 属**业务定义标记**，必须保留 | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | 其余 span 名/属性属 **D-03** 降级 |
| **communityfact wire 字段** | `Event` 9 字段 / `DeliveryStatus` 0-3 / `SubjectRef` v2（`issuer` + `subject_id`，**无 realm / tenant**） | `common/src/main/java/com/rag/common/communityfact/` | L1；`CanonicalHash` 走 **RFC 8785 JCS**（**G-2**） |
| **幂等键** | `Idempotency-Key = event.eventId` | `common/src/main/java/com/rag/common/communityfact/EventSender.java` | L1 |
| **雪花 ID 位段** | 41 + 10 + 12，epoch `1288834974657` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` | like 域游标编码依赖 `>>22` |
| **SQL 超时语义** | `setQueryTimeout`（**不是** `socketTimeout`）；`SQLState=57014` ⇒ `DEADLINE_EXCEEDED` | `common/src/main/java/com/rag/common/persistence/` | K-03 · RAG R4 |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏（56/56 = 100%）

自查命令：

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/common" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

| 子包 | 实际 `.java` 数 | 出现在本表 C 列的数 | 缺口 |
|---|---|---|---|
| `autoconfigure/` | 7 | 7 | 0 |
| `communityfact/` | 13 | 13 | 0 |
| `contract/` | 1 | 1 | 0 |
| `exception/` | 2 | 2 | 0 |
| `logger/` | 4 | 4 | 0 |
| `observability/` | 3 | 3 | 0 |
| `persistence/` | 4 | 4 | 0 |
| `protection/` | 2 | 2 | 0 |
| `security/` | 3 | 3 | 0 |
| `snowflake/` | 1 | 1 | 0 |
| `web/` | 16 | 16 | 0 |
| **合计** | **56** | **56** | **0** |

> C 列逐条清单（按子包）：
> `autoconfigure/`：GrpcExceptionMapperAutoConfiguration · JsonBodyConverterAutoConfiguration · MybatisPlusOrderingBridgeAutoConfiguration ·
> ObservabilityAutoConfiguration · ProtectionAutoConfiguration · SnowflakeAutoConfiguration · WebAutoConfiguration
> `communityfact/`：AuthorityFact · AuthorityHandler · CanonicalHash · DeliveryStatus · Dispatcher · Event · EventDispatcher ·
> EventSender · ProcessLogger · ProcessMetadata · SubjectRef · TechnicalReceipt · Wire
> `contract/`：ApiResponse
> `exception/`：AuthenticationException · BusinessException
> `logger/`：FatalError · GoStyleLogger · LogOption · LogOptions
> `observability/`：GoStyleRequestDeadline · ObservabilityCustomizer · ObservabilityProperties
> `persistence/`：DeadlineQueryTimeoutInjector · JsonTextTypeHandler · JsonbTypeHandler · SqlTimeoutStatusTranslator
> `protection/`：CpuThresholdHealthIndicator · MaxBytesFilter
> `security/`：BearerTokenExtractor · JwtUtil · PasswordUtil
> `snowflake/`：Snowflake
> `web/`：GoStyleBadRequestException · GoStyleCoercion · GoStyleFloat64Deserializer · GoStyleGetFormValuesFilter ·
> GoStyleGrpcStatusMapper · GoStyleHttpErrorException · GoStyleJsonBodyConverter · GoStyleJsonBodyConverterConfig ·
> GoStyleJsonBodyLimitFilter · GoStyleMapStringValueDeserializer · GoStylePathNormalizingFilter · GoStylePlainTextErrors ·
> GoStyleRawStringListDeserializer · GoStyleRouterErrorResponses · GoStyleStringFields · GoStyleStringInt64

### 7.2 A 列不遗漏职责（11/11 = 100%）

| Go 文件（非测试） | 出现在本表 A 列 | 落点 |
|---|---|---|
| `communityfact/authority_http.go` | ✅ | §1.2（`AuthorityHandler.java`） |
| `communityfact/logging.go` | ✅ | §1.2（`ProcessMetadata.java` / `ProcessLogger.java`） |
| `communityfact/process.go` | ✅ | §1.2（`Dispatcher.java` / `EventDispatcher.java`） |
| `communityfact/wire.go` | ✅ | §1.2（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `DeliveryStatus` / `CanonicalHash` / `EventSender` / `Wire`） |
| `logger/logger.go` | ✅ | §1.5 |
| `observability/http.go` | ✅ | §1.6 |
| `observability/mq.go` | ✅ | §1.6 |
| `observability/observability.go` | ✅ | §1.6 |
| `observability/rpc.go` | ✅ | §1.6 |
| `response/response.go` | ✅ | §1.3 |
| `snowflake/snowflake.go` | ✅ | §1.10 |

> **Go 侧无对应物（原因）** 的能力，已在表内逐行标注：
> ① 熔断 / CPU 降载 / 限连接 / 限体积 —— **go-zero 内置**（§1.8）；
> ② HTTP 解析 / 强转 / 错误形状 —— **go-zero `httpx` / `core/mapping` 内置**（§1.11）；
> ③ 异常类型 —— Go 用 `(resp, error)` 双返回（§1.4）；
> ④ 自动装配 —— Go 无自动装配机制（§1.1）；
> ⑤ SQL 超时注入 / TypeHandler —— Go 靠 `context` + GORM，无拦截器（§1.7）；
> ⑥ JWT / 口令 —— **Go 出处在 `service/user/common/`，不属本层**（§1.9）。

### 7.3 映射类型分布

> **统计口径**：本表所有 5 列对照表（§0 + §1.1 – §1.11）里**填了映射类型的行** = **80 行**，全部落在 4 种合法词内。
> 统计脚本：`awk -F'|' '/^\|/ && NF==7 {print $5}' common.md | sort | uniq -c`。

| 映射类型 | 行数 | 主要集中在 |
|---|---|---|
| **1:1** | **43** | `web/` 15 个（13 个从各域 api 归位 + 2 个来自 `common/http`）、`logger/` 5 个、`communityfact/` 4 个、`security/` 3 个、`observability/` 3 个、`persistence/` 2 个、`exception/` 2 个、`protection/` 2 个、§0 顶层 4 个、`contract` / `snowflake` 各 1 个、`autoconfigure/` 1 个 |
| **1:N** | **11** | 全部集中在 `communityfact/` 的 wire 类型拆分（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `DeliveryStatus` / `CanonicalHash` / `EventSender` / `EventDispatcher` / `ProcessMetadata`）+ §0 顶层 2 个 |
| **N:1** | **25** | `autoconfigure/` 6 个、`protection/` 5 个、`observability/` 3 个、`logger/` 2 个、`persistence/` 2 个、§0 顶层 5 个、`web/` 1 个（`GoStyleStringInt64` 合 2 份） |
| **C 新增** | **1** | §0 顶层的 `autoconfigure/`（A 侧无源形态；其余 6 个 auto-config 因 B 侧有落点故记 `N:1`） |
| **合计** | **80** | — |

> ⚠️ **分布读法**：`N:1` 偏多（25 行，占 31%）**不是缺陷** —— 它正是 D3（starter 化）与 D4（生态替代）的直接账单：
> Go 的 11 个 `.go` 里，**6 个文件的能力在 Go 侧是「框架内置」**（熔断 / 降载 / 限连接 / 限体积 / HTTP 解析 / 错误形状），
> RAG 手写复刻（RAG 侧 51 类，其中 `protection/` 一个包就占 17 类），
> RAG-V1 再交回生态或合流 ⇒ **C 列 56 类里有相当一部分是「形态」而非「能力」的重新分配**。
>
> ⚠️ **反过来说**：`1:N` 只有 11 行且**全部在 `communityfact`** ⇒ **本层的形态变化集中在「合流 + 生态替代」，不在「拆细」**。
> 这与《完全spring化的文件树》§6.1 的结论一致：`common` 几乎没降（51 → 56），**收益不在文件数，在消除 `@Import` 清单与拿到生态红利**。

### 7.4 🔴 三列职责对不上的地方（6 条，均需在实现前定死）

> 这几条是本表**最需要人拍板**的部分 —— 它们不是文件缺漏，而是**三棵树对同一职责给了三个不同的落点**。

| # | 不一致 | 三列实况 | 影响 | 建议 |
|---|---|---|---|---|
| **1** | **`communityfact` 的「三域共享」在 Go 侧只有两域** | A：`service/common/communityfact` 的消费者只有 **like（6 文件）+ comment（6 文件）**；**favorite 在 Go 侧是平行实现**（`service/favorite/rpc/internal/authority/http.go` + `model.NewFavoriteDCEventSender`），**不 import** `service/common/communityfact`。B：favorite 反而有 **10 个**引用（比 Go 多）。C：`common/src/main/java/com/rag/common/communityfact/` 按三域共享设计 | C 把 favorite 收敛到共享层 ⇒ **会改变 favorite 的 wire 行为**（Go 的 favorite 走自己的 sender，共享层走 `communityfact` 的 `Sender`）⇒ 属 L1/L2 变更，**不是形态调整** | 🔴 **必须拍板**：favorite 是「收敛到共享层」还是「保留平行实现」？若收敛 ⇒ favorite 的 `fact_delivery` 行为要按 Go 逐条对照（`DRIFT-CF-004` 的 `payload` 序列化差异也会被带进来） |
| **2** | **`GrpcStatusMapper` 与 `GrpcExceptionMapperAutoConfiguration` 职责重叠** | B：`article-api/http/GoStyleGrpcStatusMapper.java`（1 份）。C：**同时**有 `web/GrpcStatusMapper.java` **和** `autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | 两处都会做「gRPC code → 业务码」映射 ⇒ 装配顺序不同 ⇒ 同一异常可能映射出**两个不同 code** | 🔴 二选一：**建议**删 `web/GrpcStatusMapper.java`，统一由 auto-config 的 mapper 承担（与 D2/D5 的「一份业务逻辑 + 两个边界适配器」一致） |
| **3** | **设计文档 §5.1 列了 `contract/ErrorCode.java`，实际树里没有** | 实际：`common/src/main/java/com/rag/common/contract/` **只有 `ApiResponse.java`**；全仓 **0 个 `ErrorCode*.java`**（错误码在各域模块的 `contract/` 包 —— `ErrCodes` / `ErrMsg`） | 文档与树不一致 ⇒ 按文档实现的人会去建一个**全局** `ErrorCode` ⇒ **违反「错误码逐域隔离」** | 🔴 **以实际树为准**，并修正 `docs/完全spring化的文件树.md` §5.1 / §5.5（§5.5 还把 `communityfact/` 估成 4 类、实际 13 类；`web/` 估成 17 类、实际 16 类） |
| **4** | **雪花 ID 有两个落点** | C：`common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` **和** 3 个域级 `wiring/SnowflakeBootstrap.java`（`article` / `user` / `admin`） | 两个 `Snowflake` Bean ⇒ **两个独立计数器** ⇒ ID 重复 | 🔴 删 3 个域级 `SnowflakeBootstrap`，只留 starter；加断言「容器内 `Snowflake` 有且仅有 1 个」 |
| **5** | **`JsonBodyConverterConfig` 是「注册点」还是「配置类」** | B：RAG 仓 `service/common/src/main/java/com/rag/common/http/GoStyleJsonBodyConverterConfig.java` 是一个独立 `@Configuration`。C：同时存在 `web/JsonBodyConverterConfig.java` **和** `autoconfigure/JsonBodyConverterAutoConfiguration.java` | 若两者都注册 converter ⇒ **同一个 `HttpMessageConverter` 注册两次** ⇒ 请求体被读两次 / 判据被覆盖 | 🟡 建议：`web/JsonBodyConverterConfig.java` **只保留配置常量**（判据字面量），注册动作交 `JsonBodyConverterAutoConfiguration` |
| **6** | **gRPC 拦截链的挂载点在 C 侧完全缺位**（2026-10-26 新发现） | Go：范围内 **10 个服务逐条显式** `s.AddUnaryInterceptors(observability.NewUnaryServerInterceptor(rpcTimeout, observability.SlowThreshold()))`（`service/{like,comment,follow,message,favorite,task,hot,article}/rpc/*.go` + `service/user/{user,admin}/rpc/*.go`；`security`/`points` 范围外）。B：RAG 侧 10 个 rpc 模块**只有 6 个**声明了 `GrpcServerConfigurer`（like/comment/follow/favorite/task/hot）⇒ **message / user / admin 三个域的拦截链静默失效**（RAG 自己的 javadoc 承认「锁死用例 `LikeGrpcInterceptorWiringTest` **全仓不存在**」）。C：**全树 0 个 `GrpcServerConfigurer` / `ServerInterceptor`** | devh starter 的三个工厂方法**全都只注入 `List<GrpcServerConfigurer>`** ⇒ 只产 `ServerInterceptor` Bean 而不产 Configurer ⇒ **超时 / deadline 传播 / gRPC span / `sea.slow` 标记全丢，且零报错** | 🔴 **落点 = `common/autoconfigure/ProtectionAutoConfiguration`**（**共享层 1 份**，D1 合并的收益：一域一进程 ⇒ 每进程 1 个 gRPC server ⇒ 1 个全局 Configurer 即覆盖 10 个域）＋ **域内 0 重复**（重复 ⇒ 链被挂 2 次 ⇒ span 重复 / timeout 叠加）。⇒ 依据 [`完全spring化的文件树.md`](../完全spring化的文件树.md) §5.5.2；断言改写见 [`架构符合性断言.md`](../架构符合性断言.md) A3 · 契约 B |

### 7.5 ⚠️ 待执行的改名（**不要当成已改**）

RAG-V1 的 `common/` 里**仍保留 18 个 `GoStyle*` 命名的类**（实测 `find common -name 'GoStyle*.java' | wc -l` = **18**）：

| 子包 | `GoStyle*` 类 |
|---|---|
| `logger/` | `GoStyleLogger` |
| `observability/` | `GoStyleRequestDeadline` |
| `web/` | `GoStyleBadRequestException` · `GoStyleCoercion` · `GoStyleFloat64Deserializer` · `GoStyleGetFormValuesFilter` · `GoStyleGrpcStatusMapper` · `GoStyleHttpErrorException` · `GoStyleJsonBodyConverter` · `GoStyleJsonBodyConverterConfig` · `GoStyleJsonBodyLimitFilter` · `GoStyleMapStringValueDeserializer` · `GoStylePathNormalizingFilter` · `GoStylePlainTextErrors` · `GoStyleRawStringListDeserializer` · `GoStyleRouterErrorResponses` · `GoStyleStringFields` · `GoStyleStringInt64` |

> ⚠️ **设计文档已定「改名与归位」，但树里还没执行** ⇒ **本表把它们按现状名记录**，并在备注里点明「待执行的改名」。
> ⇒ 改名时**必须同步改 import**（`common` 被 10 个域依赖 ⇒ 一次改名 = 10 个域的编译面）。
> ⚠️ **`GoStyle` 前缀本身不是缺陷** —— 它的含义是「为对齐 Go 语义而手写」，改名会**丢失这条信息**；
> 若要改名，应同时在包级 javadoc 里保留该语义标注（否则后人不会知道这些类为什么长这样）。

---

## 附：本表引用

| 来源 | 用途 |
|---|---|
| `Sea-RideTheWind_golang/service/common/`（11 个非测试 `.go`） | **A 列唯一真源**；逐文件读过 `wire.go` / `process.go` / `authority_http.go` / `logging.go` / `logger.go` / `observability.go` / `http.go` / `rpc.go` / `mq.go` / `response.go` / `snowflake.go` |
| `RAG/service/common/`（51 个主代码类） | **B 列落点**；逐文件核过文件名与包结构 |
| `RAG-V1/common/`（56 个主代码类） | **C 列清单**（实测 `find`，非估算） |
| `RAG/docs/FileTree/common.md` | A↔B 的现成职责映射（`§12.4` 职责→Java 映射表、`§12.7` 不通过项 #1–#12） |
| `RAG/docs/DRIFT/common/alignment-2026-10-23.md` | B 列的漂移事实（§三 日志 KV 丢失 · §四 communityfact 消费者不一致 · §五 14 条未修 · §十二 27 条编号 + 5 项未编号） |
| `RAG-V1/docs/完全spring化的文件树.md` | C 列的推导依据（§4.5 共享层映射表 · §5.5 目标结构 · §6 文件变化表） |
| `RAG-V1/docs/决策记录.md` | D1–D9 已定 / P1–P6 待拍板 |
| `RAG-V1/docs/L3-降级清单.md` | D-01 – D-04 降级项 · K-01 – K-06 保留项 |
| `RAG-V1/docs/File_Tree/common.md` | C 列的实际文件系统树（由 `tools/gen-file-tree.py` 生成） |
| `RAG-V1/AGENTS.md` | §1.1b L1 逐域隔离 · §1.1c 生态无等价物 G-1 – G-4 · §1.3 注解≠生效 · §1.4 R1 – R6 · §2.0 命名约定 |
