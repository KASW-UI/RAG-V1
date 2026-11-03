# `like` 域 ↔ `common/` 共享层 —— 跨层对账台账

> **由 `align/like` 工作树产出** · **基线 commit `116ce63`**（本地 `main`）· 分支 `align/like`
> **核对日期**：2026-11-03
> **写权**：✅ 可写**本域** `service/like/**` ＋ `docs/DRIFT/like/**`；
> 🔴 **`common/**` 只读**（唯一写者 = 合并收口执行者 —— AGENTS.md §五.3 规则 3：「`common/**` 只有收口任务能写」）。
> **本文件正文的「整合者」一律按「合并收口执行者」理解**（`docs/Worktree.md` §1e）。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` / `docs/Intentional_Deviations/_INDEX.md` /
> `docs/Pitfalls/_INDEX.md` / `AGENTS.md` **一律不碰**（AGENTS.md §五.3 规则 3）。
> **配套文件**：[`verification-like-2026-11-03.md`](verification-like-2026-11-03.md)（本域八阶段审核报告）

> 📌 **lane 语义**：本文件是「**本域 ↔ 共享机制层 `common/`**」的跨层对账
> —— ⚠️ **不是**「本域自己的 `contract/` 包」（域内错误契约，见配套报告 §二·2.2）。

---

## 0. lane 身份

| 项 | 值 |
|---|---|
| 本域 | `service/like/`（artifactId **`like-service`** · 包 `com.rag.like`） |
| 共享层 | `common/`（C 侧包名 `com.rag.common`） |
| 工作树 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/like` |
| 分支 / 基点 | `align/like` / `116ce63`（已 fast-forward 合入 `main`） |
| 本轮改动 | **全部在工作树内、未提交**（`git status` 见配套报告 §一·1.4） |
| 本轮核对日期 | **2026-11-03** |

---

## 1. 本域**消费**的共享层能力（26 个类 · **全部已实现 · 零空壳**）

> **核法**：`grep -rhoE 'import com\.rag\.common\.[A-Za-z0-9_.]+' service/like/src/{main,test}/java` ⇒ 去重 ⇒ **26** 个。
> ⇒ 逐个确认**不是空壳**（`grep -c 'TODO：职责待补'` 全为 **0**）⇒ **零「上收了没实现」**。
> 📌 **消费面 17 个文件**（`service/like/src/main/java/`）：`advice/LikeApiExceptionHandler` · `advice/LikeApiResponseAdvice` ·
> `config/WebMvcConfig` · `domain/service/LikeDomainService` · `dto/action/LikeActionReq` ·
> `dto/count/GetLikeCountReq` · `dto/state/GetLikeStateReq` · `interceptor/LikeJwtAuthInterceptor` ·
> `messaging/LikeTaskProgressPublisher` · `messaging/LikeUpdateKafkaListener` ·
> `service/LikeAuthorityLookupService` · `service/LikeDeliveryValidator` · `service/LikeFactDispatchService` ·
> `service/LikeMessageIngestService` · `wiring/LikeAuthorityEndpointConfig` ·
> `wiring/LikeFactDispatchScheduler` · `wiring/LikeFactProcessLogging`。

### 1.1 `communityfact/` —— 事件协议本体（13 个类 · **只接线不重写**）

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 状态 |
|---|---|---|---|---|---|
| 1 | `communityfact/Wire.java` | 210 | `LikeDeliveryValidator`（wire 形态解析） | B 侧 `service/common/.../communityfact/Wire.java` **上收** | ✅ 非空壳 |
| 2 | `communityfact/Event.java` | 40 | `LikeFactDispatchService` / `LikeDomainFactService` | 事件载体（L1 wire 类型） | ✅ |
| 3 | `communityfact/TechnicalReceipt.java` | 27 | `LikeDeliveryValidator` / `LikeFactDispatchService` | 技术回执（L1 wire 类型） | ✅ |
| 4 | `communityfact/SubjectRef.java` | 41 | `LikeDeliveryValidator` / `LikeFactDispatchService` | 主体引用（L1 wire 类型） | ✅ |
| 5 | `communityfact/AuthorityFact.java` | 34 | `LikeAuthorityLookupService` | 权威事实（L1 wire 类型） | ✅ |
| 6 | `communityfact/CanonicalHash.java` | 66 | `LikeMessageDigest`（**JCS 指纹**） | 规范化哈希 —— ⚠️ 幂等命门（G-2） | ✅ |
| 7 | `communityfact/DeliveryStatus.java` | 43 | `LikeDeliveryValidator` | 投递状态枚举 | ✅ |
| 8 | `communityfact/Dispatcher.java` | 34 | `LikeFactDispatchService` | 单条事实投递（B 侧 `like-rpc/.../service/LikeFactDispatchService.java` 的**并发语义**在此） | ✅ |
| 9 | `communityfact/EventDispatcher.java` | 553 | `wiring/LikeFactDispatchScheduler`（`runAsync`） | 异步派发（1s / 批次 16 / 10s deadline） | ✅ |
| 10 | `communityfact/EventSender.java` | 171 | `wiring/LikeFactDispatchScheduler`（`DCEventSender.create`） | 事件发送（B 侧 `Wire.Sender` / `Wire.DCEventSender` 的 1:N 拆分） | ✅ |
| 11 | `communityfact/ProcessLogger.java` | 244 | `wiring/LikeFactProcessLogging` | 进程级独立 logger（**不用 SLF4J 全局 MDC**） | ✅ |
| 12 | `communityfact/ProcessMetadata.java` | 68 | 同上（`validate()` 硬校验 version 为 40 位小写 hex） | 进程身份三元组 | ✅ |
| 13 | `communityfact/AuthorityHandler.java` | 289 | `wiring/LikeAuthorityEndpointConfig`（`AuthorityHandler.create`） | 权威读 Servlet（B 侧 `like-rpc/.../service/LikeAuthorityLookupService.java` 的**暴露形态**在此） | ✅ |

### 1.2 `web/` —— Jackson / HTTP 形态（5 个类）

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 状态 |
|---|---|---|---|---|---|
| 14 | `web/Coercion.java` | 179 | `config/WebMvcConfig#likeGoStyleCoercionCustomizer` | B 侧 `users-common/.../http/GoStyleCoercion.java` 的**共享部分**（Textual ← 数字/布尔 = Fail；`ACCEPT_FLOAT_AS_INT=false`） | ✅ |
| 15 | `web/Float64Deserializer.java` | 113 | `dto/action/LikeActionReq`（`weight`） | B 侧 `like-api/.../dto/GoStyleFloat64Deserializer.java` **上收** | ✅ |
| 16 | `web/RawStringListDeserializer.java` | 141 | `dto/count/GetLikeCountReq` + `dto/state/GetLikeStateReq`（`target_ids`） | B 侧 `like-api/.../dto/GoStyleRawStringListDeserializer.java` **上收** | ✅ |
| 17 | `web/RouterErrorResponses.java` | 152 | `advice/LikeApiExceptionHandler`（404 / 405） | B 侧 `common/http/GoStyleRouterErrorResponses.java` **上收** | ✅ |
| 18 | `web/StringInt64.java` | 117 | `dto/action/LikeActionReq`（`author_id`） | B 侧 `like-api/.../dto/GoStyleStringInt64.java` **上收** | ✅ |

### 1.3 其余共享层（8 个类）

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 状态 |
|---|---|---|---|---|---|
| 19 | `contract/ApiResponse.java` | 67 | `advice/LikeApiResponseAdvice` / `advice/LikeApiExceptionHandler` | HTTP 信封载体（`{code,msg,data}`）**上收** | ✅ |
| 20 | `exception/AuthenticationException.java` | 55 | `interceptor/LikeJwtAuthInterceptor`（抛）＋ `advice/LikeApiExceptionHandler`（接） | 认证异常 | ✅ |
| 21 | `security/JwtUtil.java` | 111 | `interceptor/LikeJwtAuthInterceptor`（`padToHmacKeySize`） | JWT 工具 | ✅ |
| 22 | `security/BearerTokenExtractor.java` | 105 | 同上（`tokenForSignatureVerification`） | B 侧 golang-jwt 的 `stripBearerPrefixFromTokenString` 口径 | ✅ |
| 23 | `logger/FatalError.java` | 47 | `advice/LikeApiExceptionHandler`（原样抛出，不进信封） | 致命错误 | ✅ |
| 24 | `logger/LoggerFactoryBean.java` | 98 | `wiring/LikeFactProcessLogging` | Go 风格 logger 工厂 | ✅ |
| 25 | `observability/MqConsumer.java` | 163 | `messaging/LikeUpdateKafkaListener`（消费埋点 / ack 契约） | MQ 消费侧观测 | ✅ |
| 26 | `snowflake/Snowflake.java` | 446 | **本域不消费业务 id**（游标另见 §2） | 雪花 ID | ✅ |

> ⚠️ **`common/snowflake/Snowflake.java` 是唯一「本域 import 了但语义上刻意不复用」的类** ——
> 映射表 §2 硬约束：like 的游标 ID **必须域内自实现**（`common` 版本减过 epoch，读侧 `score >> 22` 还原出的时间戳**差 15 年**）
> ⇒ 本域用 `service/LikeCursorIdGenerator.java`（`(unixMilli << 22) | (nodeId << 12) | seq`）。

---

## 2. 本域**向共享层上收**的清单（**8 项 · 零遗漏**）

| 上收项 | B 侧原位（各域私有副本） | C 侧落点 | 本域消费方 | 结论 |
|---|---|---|---|---|
| `,string` 整数解析 | `like-api/.../dto/GoStyleStringInt64.java` | `common/web/StringInt64.java` | `dto/action/LikeActionReq` | ✅ **域内零副本** |
| `Float64` 强转反序列化器 | `like-api/.../dto/GoStyleFloat64Deserializer.java` | `common/web/Float64Deserializer.java` | `dto/action/LikeActionReq` | ✅ **域内零副本** |
| 原始字符串数组反序列化器 | `like-api/.../dto/GoStyleRawStringListDeserializer.java` | `common/web/RawStringListDeserializer.java` | 2 个 DTO | ✅ **域内零副本** |
| HTTP 信封载体 | `common/response/Response.java` | `common/contract/ApiResponse.java` | 2 个 advice | ✅ **域内零副本** |
| 路由级 404 / 405 响应 | `common/http/GoStyleRouterErrorResponses.java` | `common/web/RouterErrorResponses.java` | 1 个 advice | ✅ **域内零副本** |
| `jsonb ↔ String` 类型处理器 | `like-rpc/.../mapper/JsonbTypeHandler.java` | `common/persistence/JsonbTypeHandler.java` | 2 个实体（`payload` / `delivery_envelope` 列） | ✅ **域内零副本** |
| MP ordering bridge | `like-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 全域 | ✅ **域内零副本**（守门 A6：全仓只 1 份） |
| Jackson 强转规则 | `like-api/.../config/LikeGoStyleCoercion.java` | `common/web/Coercion.java`（共享 2 条）**＋** 域内 2 条补强留在 `config/WebMvcConfig.java` | 1 个 MVC customizer | ✅ **域内补强未丢**（见 §2.1） |

### 2.1 🔴 **like 域的 2 条 Jackson 强转补强 —— 上收后**逐条核对**（映射表 §7.3 GAP-7）

> **核法**：读 B 侧 `like-api/.../config/LikeGoStyleCoercion.java` 的 `applyTo` ⇒ 逐条对 C 侧 `config/WebMvcConfig.java:77-98`。

| # | B 侧规则 | B 出处 | C 侧落点 | 状态 |
|---|---|---|---|---|
| 1 | 共享 2 条（`Textual ← 数字/布尔 = Fail`；`ACCEPT_FLOAT_AS_INT=false`） | `LikeGoStyleCoercion.applyTo` 第 1 行（`GoStyleCoercion.applyTo(builder)`） | `WebMvcConfig.java:85`（`Coercion.applyTo(mapper)`） | ✅ |
| 2 | **补强 1**：`LogicalType.Integer ← String = Fail` | `LikeGoStyleCoercion.java`（`withCoercionConfig(LogicalType.Integer, …Fail)`） | `WebMvcConfig.java:87-89` | ✅ |
| 3 | **补强 2**：`LogicalType.Float ← String = Fail` | `LikeGoStyleCoercion.java`（`withCoercionConfig(LogicalType.Float, …Fail)`） | `WebMvcConfig.java:91-93` | ✅ |
| 4 | `FAIL_ON_TRAILING_TOKENS` 关闭 | `LikeGoStyleCoercion.java`（`builder.disable(…)`） | `WebMvcConfig.java:95-96` | ✅ |

> ⚠️ **L4 适配（Boot 3.5 约束 · AGENTS §3.2 三段式写在 `WebMvcConfig` 类注释里）**：
> Spring 6.2 的 `Jackson2ObjectMapperBuilder` **没有** `withCoercionConfig`（javap 实测只有 `featuresToDisable` / `featuresToEnable` / `modulesToInstall`）
> ⇒ **builder 路线在 Boot 3.5 上走不通** ⇒ 改走 `Jackson2ObjectMapperBuilder#postConfigurer(Consumer<ObjectMapper>)`
> ⇒ 规则施加在 **Boot 自己造的 mapper** 上（**不 new 一个 mapper 顶掉**，否则各域 `spring.jackson.*` 绑定全部失效）。
> 📌 **B 侧是 Jackson 3（`tools.jackson.*`）· C 侧是 Jackson 2（D14）** ⇒ `FAIL_ON_TRAILING_TOKENS` 的**默认值相反**
> （Jackson 3 默认 true / Jackson 2 默认 false）⇒ C 侧**显式 disable**（幂等，语义与 B 侧一致）。

### 2.2 🔴 **待收口**（**属 `common/` 写权 · 不在本 lane**）

| 项 | 现象 | 为什么现在不能收 | 收口建议 |
|---|---|---|---|
| **like 域的 2 条 Jackson 强转补强** | B 侧是 `like-api/.../config/LikeGoStyleCoercion.java`（**域内私有副本**）⇒ C 侧仍留在 `config/WebMvcConfig.java`（域内） | 这 2 条是 **like 域特有**（B 侧注释明写「不能改共享规则，会连带把 user-api / admin-api 的已验证行为改坏」）⇒ **共享层不该收**；它本就该留域内 | ❌ **不是待收口项** —— 留在域内是**正确落点**（映射表 §4.1「共享能力上收 4 处」的 4 条**不含**这 2 条补强） |

> ✅ **本域「上收 8 项」清单已逐条核对，域内零副本；唯一留在域内的 Jackson 补强是 B 侧本就域私有的 2 条规则。**

---

## 3. 9 条机制面轴 · 本域侧逐轴结论

> **轴的定义**沿用 B 侧 `docs/DRIFT/message/vs-common.md` 的口径（超时 / deadline · gRPC 拦截器链 · HTTP 保护链 ·
> 错误码 / envelope · 端口治理 · errmsg 契约 · proto 契约 · DDL 时间列 / 类型 · trace / otel）。
> ⚠️ **「✅ 一致」的含义 = 在该证据强度下一致**，**不是**已做双侧活体对拍（配套报告 §七·4 的诚实边界）。

| 轴 | 机制 | 本域侧的落点 / 调用 | 结论 | 证据强度 | 核对日期 |
|---|---|---|---|---|---|
| **A1** | 超时 / deadline | 本域唯一的出向 gRPC client = `client/MessageRpcClient.java`（`@GrpcClient("message-rpc")` + **BlockingStub** + **不设显式 deadline**）<br>⇒ **B 侧同款无 deadline**（`like-rpc/.../client/MessageRpcClient.java:52-85` 全文无 deadline/timeout）<br>⇒ ⇒ **两侧一致**（**继承 B 侧形态，不是 C 侧引入的**）<br>📌 共享层的 `GoStyleRequestDeadline` / `Deadline` 在本域**无消费点** | ✅ **一致**（两侧都无显式 deadline） | **L1 源码逐条对照**（两侧 client 主体同形：`@GrpcClient("message-rpc")` + `BlockingStub` + `stub.sendNotification(req.build())`） | 2026-11-03 |
| **A2** | gRPC 拦截器链 | 共享层 `common/autoconfigure/ProtectionAutoConfiguration.java` ⇒ `server.intercept(chain)`（`seaGrpcServerConfigurer`）⇒ **对全部域生效**，本域**零重复**<br>⇒ 守门 **A3 PASS**（「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」）<br>⇒ 本域的 `grpc/LikeGrpcService.java` 是 `@GrpcService`，**6 个方法** | ✅ **一致** | **L2 装配层守门**（A3 PASS）＋ 共享层 234 例绿 | 2026-11-03 |
| **A3** | HTTP 保护链 | 共享层 `ProtectionAutoConfiguration` ⇒ `MaxBytesFilter` ＋ `HttpMiddleware`（3s 预算）⇒ 本域**零重复**<br>⇒ 🔴 **本轮新补**：`application.yml` 曾**完全没有 `observability` 段** ⇒ `ObservabilityProperties` 的两个 timeout 缺省即 `Duration.ZERO` ⇒ **3s HTTP 预算与 2s gRPC 超时都不存在**<br>⇒ 已补 `observability.http-timeout: 3s` ＋ `rpc-timeout: 2s`（`application.yml:540-542`）<br>⇒ ⚠️ **本域仍有的差异**：**验签发生的预算位置**（**计时口径**，**不是次数**）——<br>　　**B 侧正常路径** = Servlet Filter 验签 **1 次**（预算**外**）＋ interceptor **消费**结果（`LikeJwtAuthInterceptor.java:101-109` 路径 1）⇒ 验签耗时**不计入** 3s；<br>　　**C 侧** = 只有 `HandlerInterceptor`，就地验签 **1 次**（`interceptor/LikeJwtAuthInterceptor.java:133`）⇒ 验签耗时**计入** 3s；<br>　　⇒ ⇒ **两侧都是 1 次验签**（次数**无差异**）⇒ 差异面 = **验签耗时算不算进 3s 预算** ⇒ **响应形状逐条相同** ⇒ 见配套报告 §六·6-6 | 🟡 **部分一致**（保护链齐备；**验签计时口径未闭环**） | **L2 装配层**（A3 PASS ＋ 运行期断言两个 timeout 真绑上）＋ **L1 源码**（B 侧 `like-api/.../auth/LikeJwtAuthFilter.java:140-156` ＋ `.../interceptor/LikeJwtAuthInterceptor.java:101-125`） | 2026-11-03 |
| **A4** | 错误码 / envelope | **HTTP 信封** = 共享层 `common/contract/ApiResponse`（`{code,msg,data}` 三键）<br>**业务错误契约** = **域内** `contract/ErrCodes` + `ErrMsg`（**29 码 / 29 条中文文案**）⇒ **逐域隔离，不共享**<br>⇒ 失败路径 **HTTP 200 + 信封**（唯一例外：401 + **空 body**）<br>⇒ 本域 `advice/LikeApiExceptionHandler` 有 **9 个 `@ExceptionHandler`** 分支 | ✅ **一致** | **L1 源码逐条对照**（**机器比对：29/29 码值一致 ＋ 29/29 文案逐字节一致**）＋ **L2 装配层**（真启动测试 `LikeErrorContractTest` 12 例） | 2026-11-03 |
| **A5** | 端口治理 | HTTP **8896**（`LIKE_HTTP_PORT`）＋ gRPC **9005**（`LIKE_GRPC_PORT`）—— D1 合并产物<br>⇒ **注册到 Nacos 的是 gRPC 端口**（`GrpcServerProperties.getPort()`），**不是** web 端口<br>⇒ ⚠️ `register-enabled: false` **必须开**（否则同名服务名下两个端口的实例 ⇒ gRPC 链路断）<br>⇒ `application.yml:179-180` 显式写 `namespace: public` / `group: DEFAULT_GROUP`（与注册侧一致） | ✅ **一致**（端口值本身是 D1 的**已签字**偏离） | **L2 装配层**（真启动断言：桩上**只注册 1 次**、端口 = gRPC 端口 ≠ web 端口） | 2026-11-03 |
| **A6** | errmsg 契约 | **域内** `contract/`：**29 码** + **29 条文案**（中文）+ `CodeError` + `GrpcErrFactory`（含 **cause 链解包**）<br>⇒ ⚠️ **B 侧 `LikeErrorCode` 的类注释明写「共 29 个。设计文档与骨架注释写的『30 个』是笔误」**<br>⇒ ⇒ **C 侧 29 个是对的**；但**映射表 §6 仍写「30 个常量」** ⇒ **映射表自报数与两侧代码都不一致**（见 §6·6-4） | ✅ **一致**（**逐域隔离** · 不与其他域的中文文案统一） | **L1 源码逐条对照**（机器比对 29/29 ＋ 29/29）＋ `LikeErrorContractTest` **12 例** | 2026-11-03 |
| **A7** | proto 契约 | 根级 `proto/`（D7 集中化）· pb 包名 `com.rag.proto.like` ⇒ **不在本域文件数内**<br>⇒ 本域的 6 个 gRPC 方法入参出参**就是 proto** ⇒ 领域层与 gRPC 层**零中转**<br>⇒ **逐字节 diff**：两侧仅 `option java_package` 一行不同（`com.rag.like.like.proto` → `com.rag.proto.like`）⇒ **其余全部逐字节一致**（message 名 / 字段号 / 字段名 / 类型 / 枚举值 / service 方法） | ✅ **一致** | **L1 逐字节 diff**（`diff` 只输出 `java_package` 一行）＋ `LikeGrpcServiceContractTest` **4 例**（in-process 真 gRPC） | 2026-11-03 |
| **A8** | DDL 时间列 / 类型 | **V1..V6 逐字节复制**（**机器校验：6/6 的 C 侧文件内容以 B 侧文件为前缀** · `prefix=OK` ×6）⇒ 含 **V6 `DO $$ … $$` 不可变触发器** ＋ `NOT VALID CHECK` ＋ 版本范围 CHECK<br>⇒ **真 PG 16** 容器实测（**H2 认不了 `jsonb` / `TIMESTAMPTZ` ⇒ H2 上「跑绿」是假绿**）<br>⇒ **独立账本** `flyway_schema_history_like` ＋ `baseline-on-migrate: true` ＋ `baseline-version: 0`（**必须放在 `spring:` 下**）<br>⇒ 启动闸 `wiring/LikeSchemaGate` 与 B 侧同形（`@EventListener(ApplicationReadyEvent)` + 5 张表 `information_schema` 检查） | ✅ **一致**（含时间列类型与独立账本） | **L3 真 PG 活体**（`LikeFlywayBaselineRealPgTest` 4 例）＋ **L1 字节对照**（prefix ×6） | 2026-11-03 |
| **A9** | trace / otel | 共享层 `common/observability/`（`GoStyleOtelAgent` / `ObservabilityLifecycle` / `RpcStatInterceptor` …）⇒ 本域**零重复**<br>⇒ `otel.service.name: like-service` ＋ `otel.resource.attributes.service.name` **两套拼法都写**（值一致）<br>⇒ sampler 取 api 侧 `always_on`<br>⇒ ⚠️ **B 侧 rpc 侧曾加过 `application=like-rpc` 标签，2026-10-30 与 Go 对照后移除** ⇒ C 侧**刻意不加** `tags.application` | ✅ **一致**（本域零重复；埋点落点与 B 侧逐条相等） | **L1 源码逐条对照** ＋ **L2 配置面**（`application.yml:502-521`） | 2026-11-03 |

---

## 4. 域侧能自修的（**本域直接修**，不甩给收口）

| # | 项 | 状态 | 证据 |
|---|---|---|---|
| 4-1 | **`@JsonDeserialize` 只写在 record 组件上 ⇒ 依赖「同名继承」这一实现细节**（3 个 DTO：`GetLikeCountReq` / `GetLikeStateReq` / `LikeActionReq`） | ✅ **已修**（注解同时加在**工厂参数**上 ＋ 组件上也留）<br>⚠️ **本轮自查更正**：先前那句「组件上的注解被工厂绕过」**已被区分性实验证伪**（同名时**会被继承**）⇒ 真值见 `dto/LikeJsonCreatorAnnotationScopeTest`（5 个 fixture）⇒ 3 个 DTO 的 javadoc 已改写（AGENTS §3.3.2：错误的机制断言比没有断言更危险） | 配套报告 §四·4.E ＋ §五·5.1 |
| 4-2 | **`LikeAuthorityEndpointConfig` 的 `@ConditionalOnProperty(prefix = "like.fact.authority")` 绑不上 `LikeRpcProperties.Authority`（前缀实为 `like.authority`）** ⇒ 该 Bean 永远不装配 | ✅ **已修**（prefix 改为 `like.authority`） | 配套报告 §五·5.1 |
| 4-3 | **入口类 `@EnableConfigurationProperties` 漏注册 `LikeKafkaTopics`**（D7 包压平后漏）⇒ **应用起不来**（topic bean 缺失） | ✅ **已修** | 配套报告 §五·5.1 |
| 4-4 | **`application.yml` 缺 `like:` / `like-api:` / `observability` / `server.tomcat.*` / `spring.flyway` 五段**（映射表 GAP-5 + DL-9）⇒ **默认行为全部丢失**（TTL / topic / 3s 预算 / 容器三旋钮 / 迁移账本） | ✅ **已修**（B 侧 api + rpc 两份 yml 合并为一份，逐段标 B 出处） | 配套报告 §五·5.1 |
| 4-5 | **`resources/db/migration/` 仅 `.gitkeep`**（映射表 GAP-6）⇒ **5 张表永远建不出来** | ✅ **已修**（V1..V6 逐字节复制 + C 侧落点脚注） | 配套报告 §五·5.1 |
| 4-6 | **Flyway 迁移脚本的 C 侧脚注丢了 `--` 前缀** ⇒ 第 56 行变裸 SQL ⇒ `syntax error at or near "service"` ⇒ **启动失败** | ✅ **已修**（脚注生成器补 `--` 前缀；真 PG 测试证明） | 配套报告 §五·5.1 |
| 4-7 | **`like-rpc_action_total`（连字符）不符合 Prometheus 指标名正则** ⇒ 该指标不会出现在 `/metrics`（映射表 GAP-8） | ✅ **已修**（按 B 侧写**下划线**版 `like_rpc_action_total` / `like_rpc_query_cache_total` / `like_mq_consume_total` / `like_api_reject_total`） | 配套报告 §五·5.1 |
| 4-8 | **`spring.kafka.producer.acks` 未显式配**（B 侧 `like-rpc/application.yml:207` 显式 `acks: 1`，其注释第 206 行明写「acks=1 本身保留」）⇒ 生效值虽与 Kafka 3.x 客户端默认相同，但那是**依赖默认值**而非显式声明 | ✅ **已修**（显式补 `acks: 1`，零行为差） | 配套报告 §五·5.1 |
| 4-9 | **404 的 Content-Type 断言只到 `startsWith("text/plain")`** ⇒ 未锁住字符集与分隔符；共享层 `RouterErrorResponses` 的 javadoc 引的是 **Tomcat 11.0.24** 的字节码，而 C 侧实际跑 **10.1.52** ⇒ 推导链与运行版本不匹配 | ✅ **已修**（本域断言收紧为「去空格后逐字节 = `text/plain;charset=utf-8`」＋ body 精确等于 `"404 page not found\n"` ⇒ **真 Tomcat 实测通过**）<br>⚠️ 共享层 javadoc 的版本引用属 `common/` 写权 ⇒ **只登记**（§5·5-6） | 配套报告 §五·5.1 ＋ §4.C |

---

## 5. 需转派**「合并收口执行者」**的（**本域只登记：现象 + 证据 + 建议修法**）

| # | 项 | 现象 | 证据 | 建议修法 | 严重度 |
|---|---|---|---|---|---|
| 5-1 | **`docs/DRIFT/_INDEX.md` 未收录本域台账** | 本域台账已写好，但 `_INDEX.md` 本 lane **不碰** | 配套报告 §八·8.2 | 由收口执行者统一更新 | 🟢 流程 |
| 5-2 | **映射表 §6「错误码（数值空间）」自报「30 个常量」**，但**它自己列出的值域恰好是 29 个**，且 **B 侧 `LikeErrorCode` 类注释明写「共 29 个……写的『30 个』是笔误」**，C 侧代码也是 **29** | ⇒ **映射表自报数与两侧代码都不一致**（不影响运行行为，但会让复核人以为少了一个码） | `docs/Mapping_Table/like/like.md:373` vs B 侧 `like-common/.../errmsg/LikeErrorCode.java:15-16` vs C 侧 `contract/ErrCodes.java`（29 个） | 由收口执行者把映射表的自报数改为 **29**（**本 lane 不改映射表** —— AGENTS §2.3「禁止修改 Mapping_Table」） | 🟡 P2 |
| 5-3 | ~~**`/actuator/prometheus` 在生产形态上下文 404**~~ | ⚠️ **本轮已撤销（假阳性）**：原判断「Boot 的 `PrometheusMetricsExportAutoConfiguration` 整段被 `@ConditionalOnMissingBean` 跳过 ⇒ 不是 like 域的问题 ⇒ 归 `global` 收口域」—— **两条都错**：① 机制错（Boot 3.5.11 的 `PrometheusMetricsExportAutoConfiguration` 带 `@AutoConfiguration(before = {Composite..., Simple...})` ⇒ **Prometheus 在前**）；② 把**测试上下文**的特性当成了生产特性。<br>⇒ **真因** = Boot `ObservabilityContextCustomizerFactory`（其 javadoc 明写「globally disables metrics export and tracing in tests」）⇒ **生产形态可用**（`PrometheusMeterRegistry` 存在 ＋ 端点 200 ＋ 真流量打出 `like_api_reject_total{...}`）⇒ **收口域不需要动** | 配套报告 **§四·4.F** ＋ `service/like/src/test/java/com/rag/like/http/LikePrometheusEndpointRealTest.java`（3 例，全绿） | ✅ **撤销该收口项**；生产代码**零改动**（测试侧加 `@AutoConfigureObservability(metrics = true, tracing = false)`）<br>📌 **跨域提醒**：`docs/DRIFT/follow/verification-follow-2026-11-02.md:327-330` 是**同款错判** ⇒ **属 follow lane** ⇒ 本 lane 只登记，建议 follow 复核 | 🟢 无（**已撤销**） |
| 5-4 | **`like.fact.process.{environment,instance-id,version}` 三元组是 C 侧新增表达层** | B 侧 `LikeFactProcessLogging` 是**骨架占位**（只写「计划建 3 份 ProcessLogger」）；C 侧落地时必须给 `ProcessMetadata#validate()` 提供 **40 位小写 hex** 的 version ⇒ **C 侧自造了一个** | `service/like/src/main/java/com/rag/like/wiring/LikeFactProcessLogging.java:65` | 登记为**签字偏离**（表达层新增，不改业务语义）；**本 lane 不改** `docs/Intentional_Deviations/_INDEX.md` | 🟢 流程 |
| 5-5 | **B 侧 `LikeApiMetrics.InitMetrics()` 从未被调用** | ⇒ B 侧 `like_api_reject_total` **不会出现在 `/metrics`**；C 侧指标**会**出现 ⇒ **C 侧多了一个可见指标** | B 侧 `like-api/.../metrics/LikeApiMetrics.java`（`InitMetrics` 无调用点）vs C 侧 `metrics/LikeApiMetrics.java:88` | 登记为**签字偏离**（指标可见性面）；**本 lane 不改**索引 | 🟢 流程 |
| 5-6 | **B 侧 api/rpc 两进程的独立 9098 观测口在 D1 合并后不再单独存在** | B 侧 like-rpc 的 Actuator 独立端口在 C 侧并入主 app 端口 | `docs/Mapping_Table/like/like.md:270`（端口行） | 已随 D1 记录；**本 lane 不重复登记** | 🟢 流程 |
| 5-7 | **共享层 `common/web/RouterErrorResponses.java` 的 javadoc 引的是 Tomcat 11.0.24 的字节码推导 Content-Type 归一化**（`;charset=` 分号后**无空格**），而 C 侧实际跑 **Tomcat 10.1.52** | ⇒ 推导链与运行版本**不匹配**（当前值正确，但证据链引错版本）<br>⚠️ 属 `common/` 写权 ⇒ 本域**只登记** | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java:103-125` · C 侧运行版本见 `spring-boot-dependencies-3.5.11.pom:210`（`<tomcat.version>10.1.52`） | 收口域改引 Tomcat 10.1.52 的对应字节码（或直接以真 Tomcat 实测为准）⇒ 本域已用**真 Tomcat 逐字节断言**独立兜住 | 🟢 低 |

---

## 6. 未验证项（本轮明确留下的缺口，**不写成已闭环**）

| # | 项 | 为什么未验 | 什么时候能验 |
|---|---|---|---|
| 6-1 | ~~**双侧活体对拍**（本域 ↔ B 侧同条件）~~ | — | ✅ **本轮已闭合**：`tools/difftest/like/`（13 阶段）⇒ **C == B == 冻结的 Go 字节**（8 条 HTTP 三方逐字节 ＋ 6 条 gRPC wire 双边逐字节），**两轮独立复跑读数一致**，退出码 **0** ⇒ 配套报告 §八 |
| 6-2 | ~~**Kafka 消费路径（`LikeUpdateKafkaListener`）零覆盖**~~ | — | ✅ **本轮已闭合**：起 Sea-kafka `apache/kafka:3.7.2`（宿主口 `127.0.0.1:49992`）⇒ `LikeKafkaThreeFaceRealTest` **消费面真跑**（真 broker ＋ 真 `@KafkaListener` ⇒ inbox/record/fact/outbox **四处落库**）⇒ 配套报告 §四·4.I<br>⚠️ **旧归因写反了**：「`auto-startup=false` 让 listener 没起」是**错的** —— 那键**没有消费点** ⇒ **6-11** |
| 6-3 | ~~**`like_outbox_event → article-hot-events` 中继（`LikeHotEventOutboxRelay`）零覆盖**~~ | — | ✅ **本轮已闭合**：真 `@Scheduled` 中继（间隔压到 300ms）⇒ 待发行事件真的推到 `article-hot-events`，payload **5 字段 ＋ 字段序**逐个钉住 ⇒ 配套报告 §四·4.I |
| 6-4 | **映射表 §6 的错误码自报数（30）与两侧代码（29）不一致** | ⇒ **映射表与当前仓库实际状态不一致** ⇒ 按 AGENTS §2.3「**记录差异，不要擅自修改映射表**」⇒ 本 lane **只登记**（§5·5-2） | 收口执行者修正映射表自报数 |
| 6-5 | **`like.authority.*` 键名是 C 侧修正后的名字**（B 侧无对应键 —— B 侧 `LikeAuthorityEndpointConfig` 是**骨架占位**） | ⇒ 无 B 侧真值可比 ⇒ 属**表达层自选**，不是可对拍项 | 需 B 侧补全该配置后才可对齐 |
| 6-6 | **验签的 3s 预算边界（验签**计时口径**面）** | C 侧验签在 **HandlerInterceptor**（预算**内**，验签耗时**计入** 3s）；B 侧正常路径在 **Servlet Filter**（预算**外**，验签耗时**不计入** 3s）⇒ **两侧都是 1 次验签**（**次数无差异**）⇒ 差异只在「验签耗时算不算进预算」⇒ 响应形状逐条相同 | 要对齐需新增独立 Servlet Filter（承载验签）⇒ 属**形态改动** ⇒ 需走决策流程 |
| 6-7 | ~~**变异证据（判据有牙齿）**~~ | — | ✅ **本轮已闭合**：`tools/difftest/like/mutate-parity.sh`（M1~M6，每条都要求「目标用例红 **且** 对照用例仍绿」＋ 启动前残留自检）⇒ 配套报告 §八·8.4 |
| 6-8 | ~~**netty gRPC server 真启动**~~ | — | ✅ **本轮已闭合**（配套报告 §四·4.H） |
| 6-9 | ~~**Flyway 运行时版本（覆盖 Boot BOM 11.7.2）**~~ | — | ✅ **本轮已由「间接」升级为「显式」**（配套报告 §四·4.G） |
| 6-10 | 🔴 **C 侧 `grpc-inprocess` 声明成 `test` scope ⇒ 真 fat jar 起不来**（**DRIFT-LIKE-014**） | **生产形态完全不可用**（`NoClassDefFoundError` ⇒ `MessageRpcClient` Bean 创建失败 ⇒ 服务起不来）⇒ 而 **102/102 测试全绿**（**当时的读数**；本轮加完 Kafka 三面后为 106/106）⇒ AGENTS §3.3.3「跑绿 ≠ 本来就绿」 | ✅ **已修**（`service/like/pom.xml` 去掉 `test` scope ＋ 补 B 侧 `like-rpc/pom.xml:313-344` 同款根因注释）⇒ **活体证据**：真 fat jar 起成功（Tomcat ＋ gRPC server 都起、无 `NoClassDefFoundError`）⇒ 配套报告 §五·5.1 #14 |

| 6-11 | 🔧 **`spring.kafka.listener.auto-startup=false` 是**死配置**（**DRIFT-LIKE-016**） | 应用在 `LikeServiceApplication#kafkaListenerContainerFactory` 里**自己 `new`** 了 `ConcurrentKafkaListenerContainerFactory` ⇒ Boot 的 configurer **不参与** ⇒ `spring.kafka.listener.*` **一整族键无消费点** ⇒ `autoStartup` 一直是框架默认 **true** ⇒ **listener 其实一直都在起** ⇒ ⇒ 台账旧结论「listener 没起 ⇒ 消费面零覆盖」的**归因写反了**（真因 = **无 broker 可连**）⇒ ⇒ **一条错误归因会把下一个人引去改一个不存在的开关** | ✅ **已修**（删死键 ＋ 注释改为如实描述；**不影响生产行为** —— 生产 `application.yml` 无此键）＋ 补**区分性实验**（`mutate-kafka-3face.sh` M1：listener 一停 ⇒ 消费面/推送面/幂等面立刻转红 ⇒ **反证该键从未生效过**）⇒ 配套报告 §四·4.I ＋ §八·8.6 |
| 6-12 | ~~**`notifyArticleLiked` 跨域通知面零覆盖**~~ | — | ✅ **本轮已闭合**：本域自带**真 netty 假 message-rpc** ＋ Spring Cloud `SimpleDiscoveryClient` 寻址 ⇒ 通知**真的发出去**且**逐字段** == B 侧 `MessageRpcClient.java:63-82` ＋ 失败语义不误伤点赞结果；**变异验证**（只有正例红 ＋ 还原后回绿）⇒ 配套报告 §四·4.J<br>⚠️ **不冒充**「B 侧 message 域此刻真的收到了」（那属跨 lane 依赖） |
---

## 7. 交付物清单（**本域侧**）

| 项 | 文件 | 状态 |
|---|---|---|
| 域级审核报告 | [`docs/DRIFT/like/verification-like-2026-11-03.md`](verification-like-2026-11-03.md) | ✅ 已写 |
| **本文件**（跨层对账） | `docs/DRIFT/like/vs-common.md` | ✅ 已写 |
| **双侧活体对拍探针** | [`tools/difftest/like/`](../../../../tools/difftest/like/)（`run.sh` 13 阶段 · `expected.txt` · `mint-jwt.py` · `grpc-wire/LikeWireProbe.java` · `mutate-parity.sh` · `README.md`） | ✅ **EXIT=0**（真差异 0 · 两轮复跑一致）· 变异 **EXIT=0**（M1~M6 全按预期转红） |
| **Kafka 三面变异脚本** | [`tools/difftest/like/mutate-kafka-3face.sh`](../../../../tools/difftest/like/mutate-kafka-3face.sh)（M1~M3 · 判据组合**互斥**） | ✅ **EXIT=0**（三条变异的红集合互不相同 ＋ 还原后复跑为绿 ＋ **零残留**） |
| 共享层能力消费面 | **26 个** `com.rag.common.*` 类 · **零空壳** | ✅ 已核 |
| 域内零副本核对 | `StringInt64` · `Float64Deserializer` · `RawStringListDeserializer` · `ApiResponse` · `RouterErrorResponses` · `JsonbTypeHandler` · `MybatisPlusOrderingBridgeAutoConfiguration` | ✅ 已核 |
| Jackson 强转补强保留 | 4 条逐条核对（映射表 GAP-7） | ✅ 已核 |
| 待收口登记 | 5-2（映射表自报数）· **5-3 已撤销（Prometheus 假阳性）**· 5-4 / 5-5（新增表达层 + 指标可见性） | ✅ 已登记 |
| 守门读数 | A1–A13 **PASS 10 · FAIL 0 · N/A 2 · PENDING 1**（PENDING 属 comment/favorite 空壳）· mapping **EXIT=0**（like 69/69 C · 83/83 B）· docs↔tree **EXIT=0** | ✅ 2026-11-03 |
| 回归读数 | 本域 **110 例 · 0/0/0**（13 个测试类，含 `LikeKafkaThreeFaceRealTest` 4 例 ＋ `LikeMessageRpcCrossDomainRealTest` 4 例）；全仓（含 common）**349 例 · 0/0/0 · Skipped 0** · **BUILD SUCCESS**（like 110 ＋ common 239；common 的 +5 来自**基线前进**，非本域改动） | ✅ 2026-11-03 |
