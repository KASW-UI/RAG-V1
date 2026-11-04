# `task` 域 ↔ `common/` 共享层 —— 跨层对账台账

> **由 `align/task` 工作树产出** · **基线 commit `0a35865`**（本地 `main`）· 分支 `align/task`
> **核对日期**：2026-11-03
> **写权**：✅ 可写**本域** `service/task/**` ＋ `docs/DRIFT/task/**`；
> 🔴 **`common/**` 只读**（唯一写者 = 合并收口执行者 —— AGENTS.md §五.3 规则 3：「`common/**` 只有收口任务能写」）。
> **本文件正文的「整合者」一律按「合并收口执行者」理解**（`docs/Worktree.md` §1e）。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` / `docs/Intentional_Deviations/_INDEX.md` /
> `docs/Pitfalls/_INDEX.md` / `AGENTS.md` **一律不碰**（AGENTS.md §五.3 规则 3）。
> **配套文件**：[`verification-task-service-2026-11-03.md`](verification-task-service-2026-11-03.md)（本域八阶段审核报告）

> 📌 **lane 语义**：本文件是「**本域 ↔ 共享机制层 `common/`**」的跨层对账
> —— ⚠️ **不是**「本域自己的 `contract/` 包」（**task 域刻意没有错误契约包** —— B 侧 `task` **无错误码空间**，见 §1.3 与 §3·A4/A6）。

---

## 0. lane 身份

| 项 | 值 |
|---|---|
| 本域 | `service/task/`（artifactId **`task-service`** · 包 `com.rag.task`） |
| 共享层 | `common/`（C 侧包名 `com.rag.common`） |
| 工作树 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/task` |
| 分支 / 基点 | `align/task` / `0a35865`（本地 `main`） |
| 本轮改动 | **已提交 `ef4fb5d`**（分支 `align/task` · 基线 `0a35865`）—— 61 个文件 · ＋9074 / −210 行<br>· **2026-11-04 补记提交 `c1086dc`**（U-1 消费面真跑 ＋ U-2 双侧活体对拍 ＋ fat jar 启动缺陷修复）—— **17 个文件 · ＋1831 / −56 行**<br>· **2026-11-04 补记提交 `52c272d`**（U-3 gRPC wire 真 netty 收发 ＋ M-6 变异）—— **8 个文件 · ＋511 / −26 行** |
| 本轮核对日期 | **2026-11-03** |
| 本轮对 `common/**` 的改动 | **零**（只读） |

---

## 1. 本域**消费**的共享层能力（**5 个类 · 全部已实现 · 零空壳**）

> **核法**：`grep -rhoE 'import com\.rag\.common\.[A-Za-z0-9_.]+' service/task/src/{main,test}/java` ⇒ 去重 ⇒ **5** 个。
> ⇒ 逐个确认**不是空壳**（`grep -rc 'TODO：职责待补'` 全为 **0**）⇒ **零「上收了没实现」**。
> 📌 **消费面 3 个文件**（`service/task/src/main/java/`）：`advice/TaskApiExceptionHandler` · `messaging/TaskUserLikeProgressSink` · `messaging/TaskArticleLikeProgressSink`。

### 1.1 `web/` —— HTTP / gRPC 形态（**4 个类**）

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 状态 |
|---|---|---|---|---|---|
| 1-1 | `common/web/GrpcStatusMapper.java` | 161 | `advice/TaskApiExceptionHandler.java:138`（`httpStatusOf`）＋ `:142`（`goErrorMessage`） | B 侧 `advice/GlobalExceptionHandler.java` 的**私有** `httpStatusOf`（`:101-137`）＋ `goCodeName`（`:160-186`）＋ `goStatusText`（`:159-162`） | ✅ **机器比对 17/17 逐条一致**（见 §1.4） |
| 1-2 | `common/web/PlainTextErrors.java` | 104 | `advice/TaskApiExceptionHandler.java:141,165,195,213,267`（`text` / `badRequest`） | B 侧 `GlobalExceptionHandler.java:305-311` 的 `http.Error` 逐字等价物（`text/plain; charset=utf-8` ＋ 尾 `\n`） | ✅ |
| 1-3 | `common/web/RouterErrorResponses.java` | 152 | `advice/TaskApiExceptionHandler.java:227`（`notFound`）＋ `:242`（`methodNotAllowed`） | B 侧 `GlobalExceptionHandler.java:254-258`（404）＋ `:267-274`（405） | ✅ **真 Tomcat 逐字节断言**（`TaskApiWireContractRealTomcatTest`） |
| 1-4 | `common/protection/ServiceBusyException.java` | 36 | `advice/TaskApiExceptionHandler.java:162`（`@ExceptionHandler`） | B 侧 `GlobalExceptionHandler.java:197-201`（熔断打开 ⇒ **400** ＋ `"circuit breaker is open"`） | ✅ **文案逐字相同** |

### 1.2 `observability/` —— MQ 消费观测（**1 个类**）

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 状态 |
|---|---|---|---|---|---|
| 1-5 | `common/observability/MqConsumer.java` | 163 | `messaging/TaskUserLikeProgressSink.java:175,268` ＋ `messaging/TaskArticleLikeProgressSink.java:178,268`（`traceConsumer(ObservationRegistry, tracerName, spanName, attrs, fn)`） | B 侧 `mq/UserLikeProgressSink.java` / `mq/ArticleLikeProgressSink.java` 的 `MqConsumer.traceConsumer(Context.rootContext(), …)`（**Go 侧 `Context` ⇒ C 侧 `ObservationRegistry`**） | ✅ **API 形态已改写**（Go 的 `Context` 无 Java 等价物 ⇒ 用 Micrometer `ObservationRegistry`）⇒ **span 属性名字与取值逐条照抄**（`messaging/TaskTraceAttributes.java`） |

### 1.3 本域**刻意不消费**的共享层能力（**登记以防后人误加**）

| 共享层能力 | 为什么本域**不**用 | 依据 |
|---|---|---|
| `common/web/JsonBodyConverter` ＋ `JsonBodyConverterAutoConfiguration` | 🔴 **必须让共享层退让** —— B 侧 `TaskApiJsonBodyConfig.java:42-52` **明说**「必须排除共享层 `GoStyleJsonBodyConverterConfig`」⇒ C 侧用**同名 bean** 让它退让（§3·A3） | `config/TaskApiJsonBodyConfig.java`（`@Configuration("seaJsonBodyConverterConfigurer")`） |
| `common/contract/ApiResponse`（`{code,msg,data}` 信封） | 🔴 **B 侧 `task-api` 没有 ResponseAdvice** ⇒ 成功响应**无信封** ⇒ 加信封 = **新增一层 wire 差异** | `advice/TaskApiResponseAdvice.java`（**原样透传**） |
| `common/web/StringInt64`（`,string` int64 解析） | 本域的 `id` 解析在 **DTO 层**（`GetTaskReq#setId(Object)`）⇒ 走 **Jackson 强转**而非共享层的 `,string` 原语 | `dto/task/GetTaskReq.java` |
| `common/observability/` 的 HTTP 中间件 / RPC 拦截器 | 本域**零重复** ⇒ 共享层**统一承担**（§3·A2 / §3·A3） | 守门 **A3 PASS** |
| `common/communityfact/`（事件协议 13 类） | 本域**不生产也不消费** `communityfact` 事件 ⇒ **零消费点** | `grep` 零命中 |

### 1.4 🔴 **机器比对**：gRPC status → HTTP 的两张映射表

> **核法**（可复跑）：把 B 侧 `GlobalExceptionHandler#httpStatusOf` / `#goCodeName` 与共享层
> `GrpcStatusMapper#httpStatusOf` / `#goCodeName` 的 `switch` **分别按两种语法**（冒号 fall-through ＋ 箭头表达式）
> 解析成 `{码 → 值}`，再逐条比对。

| 映射表 | B 侧分支数 | C 侧（共享层）分支数 | 逐条比对 |
|---|---|---|---|
| `httpStatusOf`（gRPC 码 → HTTP 状态码） | **17** | **17** | ✅ **逐条一致** |
| `goCodeName`（gRPC 码 → grpc-go CamelCase 码名） | **17** | **17** | ✅ **逐条一致** |

> 📌 **两条补充**：
> ① **B 侧有 `default` 分支**（`GlobalExceptionHandler.java:131-134`）⇒ 未知码落 **500**；
>    共享层的 javadoc 写「B 的 switch 没有 default 分支」（指 Go 侧 `grpc.go:41`）⇒ **两侧未知码都落 500 ⇒ 行为等价**。
> ② `goErrorMessage` 的格式串与 null 处理（`desc = ` 后留空）**逐字相同**：
>    B 侧 `"rpc error: code = " + goCodeName(...) + " desc = " + (desc == null ? "" : desc)`
>    ≡ 共享层 `"rpc error: code = " + goCodeName(code) + " desc = " + (description == null ? "" : description)`。
> ⇒ **结论**：本域**没有**在共享层之外重复实现 gRPC 错误映射 ⇒ **零重复、零分叉**。

---

## 2. 本域**向共享层上收**的清单（**2 项 · 零遗漏**）

| # | 上收内容 | B 侧位置 | C 侧落点 | 是否已实现 | 状态 |
|---|---|---|---|---|---|
| 2-1 | **Jackson 2 的 `ObjectMapper` 强转配置** | `task-rpc/.../config/Jackson2ObjectMapperConfiguration.java`（**显式** `@Bean`，关 `FAIL_ON_UNKNOWN_PROPERTIES` ＋ `LogicalType.Textual` 强转 `Fail`） | 🔴 **不建域内同名类** ⇒ 落共享层 `common/web/Coercion.java`（`applyTo`）＋ `common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（`Jackson2ObjectMapperBuilderCustomizer`） | ✅ | ✅ **两侧等价**<br>⚠️ **为什么必须不建**：Boot 3.5 的 MVC mapper **本来就是 Jackson 2**（D14）⇒ 域内再 `@Bean` 一个 `ObjectMapper` 会**顶掉 Boot 的那个** ⇒ 影响**全域的 JSON 行为**。<br>⇒ 本域三条 JSON 路径（`readTree` 手工判定 × 2 ＋ 序列化 × 1）逐条核过，**都不受 coercion 配置影响** |
| 2-2 | **gRPC 服务端拦截器链的装配点** | `task-rpc/.../TaskRpcApplication.java:130-139`（**显式** `rpcInterceptorConfigurer`，因为 devh starter **不会**自动注册用户提供的 `ServerInterceptor`） | 共享层 `common/autoconfigure/ProtectionAutoConfiguration.java:231-240` 的 `seaGrpcServerConfigurer`（**真调** `server.intercept(...)`） | ✅ | ✅ **已由共享层承担**<br>⇒ **域内零重复** ⇒ **守门 A3 PASS**：「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」 |

> 📌 **本域**没有**其它**上收项 —— B 侧的 `common/http/GoStylePlainTextErrors` / `GoStyleRouterErrorResponses` /
> `common/errcode/CodeFromGrpcError` 在 C 侧**早已是共享层**（`common/web/`），本域**直接消费、零拷贝**。

---

## 3. 9 条机制面轴 · 本域侧逐轴结论

> **轴的定义**沿用 B 侧 `docs/DRIFT/message/vs-common.md` 的口径（超时 / deadline · gRPC 拦截器链 · HTTP 保护链 ·
> 错误码 / envelope · 端口治理 · errmsg 契约 · proto 契约 · DDL 时间列 / 类型 · trace / otel）。
> ⚠️ **「✅ 一致」的含义 = 在该证据强度下一致**，**不是**已做双侧活体对拍。
> 📌 **2026-11-04 补记**：本域**已建** `tools/difftest/task/`（双侧活体对拍，**E-4 强度**）⇒ 见 §6·6-2。
> ✅ **口径更新（2026-11-04）**：**HTTP 面**（M1/M2/M3/M4）**与 gRPC 面**（**M5 gRPC 字节面**）**都已是 E-4** ⇒ ⇒ task 域的 **HTTP 契约**与 **gRPC wire 契约**两条面**都有双侧证据** ⇒ ⇒ **U-6 已闭合**。

| 轴 | 机制 | 本域侧的落点 / 调用 | 结论 | 证据强度 | 核对日期 |
|---|---|---|---|---|---|
| **A1** | 超时 / deadline | `application.yml:389-391` **显式** `observability.http-timeout: 3s` ＋ `observability.rpc-timeout: 2s`（**照抄 B 侧两份 yml**）<br>⇒ 本域**无出向 gRPC client**（D2 域内直调 ⇒ `client/` 包不存在）⇒ **无 client deadline 可比**<br>📌 共享层的 `GoStyleRequestDeadline` / `Deadline` 在本域**无消费点** | ✅ **一致**（**显式声明**，不是隐式默认） | **L1 配置面对照**（B 侧 `task-api/application.yml:134-135` ＋ `task-rpc/application.yml:316-317`） | 2026-11-03 |
| **A2** | gRPC 拦截器链 | 共享层 `ProtectionAutoConfiguration` ⇒ `server.intercept(chain)`（`seaGrpcServerConfigurer`）⇒ **对全部域生效**，本域**零重复**<br>⇒ 守门 **A3 PASS**<br>⇒ 本域 `grpc/TaskGrpcService.java` 是 `@GrpcService`，**1 个方法**（`getTask`） | ✅ **一致** | **L2 装配层守门**（A3 PASS）＋ **L1 源码**（B 侧 `TaskRpcApplication.java:130-139`）<br>✅ **传输层已活体（2026-11-04 补记）**：`TaskGrpcNettyServerRealTest`（**6 例**）⇒ 真 netty server + 真 netty client<br>⇒ ⚠️ **但只到 E-3（C 侧单侧）** ⇒ **gRPC 面尚未双侧对拍** ⇒ **已登记为 U-6**（配套报告 §七·7-1） | 2026-11-03 |
| **A3** | HTTP 保护链 | 共享层 `ProtectionAutoConfiguration` ⇒ `MaxBytesFilter`（1 MiB ⇒ 413 裸状态码）＋ `HttpMiddleware`（3s 预算）⇒ 本域**零重复**<br>⇒ `application.yml:348-362`：`rag.http.go-form-limit.mode: "off"`（**引号必须写** —— YAML 1.1 会把裸 `off` 解析成布尔 `false`）＋ `rag.protection.max-conns: 10000` / `max-bytes: 1048576` / `cpu-shedding.enabled: true`<br>⇒ ⚠️ **本域的「关闸门」是照抄 B 侧**：`task-api/application.yml:155-270` 的 `rag.protection` 段**同样**把 `go-form-limit.mode` 设为 `"off"`（本域控制器只有 `@RequestBody`，**没有** `@RequestParam` ⇒ go-zero 的 `maxFormParamCount=2048` 不可达 ⇒ **开了就是行为漂移**） | ✅ **一致**（**逐键照抄**） | **L1 配置面对照**（B 侧 `task-api/application.yml:155-270`）＋ **L2 装配层**（守门 A3 / A5） | 2026-11-03 |
| **A4** | 错误码 / envelope | 🔴 **本域无业务码空间** —— B 侧 `service/task/` 全树**零** `common/` 目录、pom 的 `<modules>` **无 `task-common`**、`grep -rn "errmsg" service/task/` **零命中**<br>⇒ **HTTP 信封 = 没有**（成功体 = `{"tasks":[…]}`；失败体 = **裸 text/plain** ＋ 真实 HTTP 状态码 400 / 404 / 405 / 413 / 415 / 500）<br>⇒ `advice/TaskApiResponseAdvice` **原样透传**（不加信封）<br>⇒ `advice/TaskApiExceptionHandler` 有 **7 个 `@ExceptionHandler`** 分支<br>⇒ 唯一业务错误 = **gRPC 标准码 3** `codes.InvalidArgument` ＋ 文案 `"user_id invalid"` | ✅ **一致**（**两侧都没有信封、都没有业务码空间**） | **L1 源码逐条对照**（B 侧 `TaskApiApplication.java` 零 `contract/`）＋ **L3 真 Tomcat**（`TaskApiWireContractRealTomcatTest` 13 例：成功体无信封 · 失败体裸 text/plain ＋ 尾 `\n`） | 2026-11-03 |
| **A5** | 端口治理 | HTTP **8890**（`TASK_HTTP_PORT`）＋ gRPC **9006** —— **D1 合并产物**<br>⇒ **注册到 Nacos 的是 gRPC 端口**（`GrpcServerProperties.getPort()`），**不是** web 端口<br>⇒ ⚠️ `register-enabled: false`（**必须开** —— 否则同名服务名下两个端口的实例 ⇒ gRPC 链路断）<br>⇒ **端口值本身是已签字偏离**（**DRIFT-TASK-002**） | ✅ **一致**（端口值是 D1 的**已签字**偏离） | **L2 装配层**（`NacosStubConfiguration` 桩上**只注册 1 次**、端口 = gRPC 端口 ≠ web 端口）＋ **L1 配置面对照** | 2026-11-03 |
| **A6** | errmsg 契约 | 🔴 **不适用** —— 本域**没有** `contract/` 包（**不是缺漏**，是**两侧一致的刻意为空**）<br>⇒ 域内**零业务码 ＋ 零中文文案表**<br>⇒ 仅有的两条文案是**英文裸串**：<br>　　`"id must be a string or integer"`（`GetTaskReq.java:74`）· `"user_id invalid"`（`GetTaskReq.java:71`） | ✅ **一致**（**两侧都无错误码空间** ⇒ **不与其他域统一**） | **L1 源码逐条对照**（B 侧 `GetTaskReq.java:71, 74` ≡ C 侧 `dto/task/GetTaskReq.java`）<br>⚠️ **两条文案的触发点互斥**（B 侧 `GetTaskReq.java:42-44` 明写「状态码相同、文案不同 ⇒ 仍是可观察漂移」）⇒ 已在 `GetTaskReqNormalizationTest` 10 例 ＋ 变异 **M-2** 钉住 | 2026-11-03 |
| **A7** | proto 契约 | 根级 `proto/`（D7 集中化）· pb 包名 `com.rag.proto.task` ⇒ **不在本域文件数内**<br>⇒ 本域的 **1 个** gRPC 方法入参出参**就是 proto** ⇒ 领域层与 gRPC 层**零中转**<br>⇒ **逐字节 diff**：两侧仅 `option java_package` 一行不同（`com.rag.task.task.proto` → `com.rag.proto.task`）⇒ **其余全部逐字节一致**（message 名 / 字段号 / 字段名 / 类型 / service 方法） | ✅ **一致** | **L1 逐字节 diff**（`diff` 只输出 `java_package` 一行）<br>✅ **消费方式已验（2026-11-04 补记）**：`TaskGrpcNettyServerRealTest`（**6 例**）⇒ **真 netty 线路**上<br>　· **服务全名 `task.TaskService/GetTask` 逐字未变**（⇒ proto `package task;` 没被 D7 压掉 ⇒ **wire 契约成立**）<br>　· **真 proto 编解码** ⇒ 两条任务定义逐字段回来 · Redis/PG 两条读路径 · 错误出口 `INVALID_ARGUMENT` + `"user_id invalid"`<br>⇒ ⚠️ **但只到 E-3（C 侧单侧）** ⇒ **gRPC 面尚未双侧对拍** ⇒ **已登记为 U-6**（配套报告 §七·7-1） | 2026-11-03 |
| **A8** | DDL 时间列 / 类型 | **V1 + V2 逐字节复制**（`diff -r` 与 B 侧 `task-rpc/src/main/resources/db/migration/` **零差异**）<br>⇒ 两张表：`task_progress`（复合主键 `user_id` BIGINT ＋ `task_id`）· `user_like_count`（`user_id` **TEXT** —— ⚠️ 与前者**类型不同**，**继承 B 侧** ⇒ **DRIFT-TASK-012**）<br>⇒ **独立账本** `flyway_schema_history_task` ＋ `baseline-on-migrate: true` ＋ `baseline-version: 0`（**必须放在 `spring:` 下**）<br>⇒ 启动闸 `wiring/TaskSchemaGate` 与 B 侧同形（`@EventListener(ApplicationReadyEvent)` ＋ 2 张表 `information_schema` 检查）<br>⇒ ⚠️ **真 PG 16 容器**（**H2 认不了 `jsonb` / `TIMESTAMPTZ` ⇒ H2 上「跑绿」是假绿**） | ✅ **一致**（含列类型与独立账本） | **L3 真 PG 活体**（`TaskProgressMapperRealPgTest` 9 例 ＋ `TaskServiceApplicationTests` 的 Flyway 账本断言）＋ **L1 字节对照**（`diff -r` 零差异） | 2026-11-03 |
| **A9** | trace / otel | 共享层 `common/observability/`（`MqConsumer` / `GoStyleOtelAgent` / `ObservabilityLifecycle` …）⇒ 本域**零重复**<br>⇒ `otel.service.name: task-service`（**一套拼法**）· sampler `parentbased_always_on` · exporter `jaeger:4317`<br>⇒ 本域的 **MQ span 属性**落在 `messaging/TaskTraceAttributes.java`：`messaging.system=kafka` / `messaging.destination`（**逻辑名 `user_like_sink` / `article_like_sink`，不是 topic 名**）/ `messaging.operation=consume\|flush` / `messaging.message.key` / `batch.size` | ✅ **一致**（本域零重复；埋点落点与 B 侧逐条相等） | **L1 源码逐条对照**（B 侧 `mq/TaskTraceAttributes.java` ≡ C 侧同名类）＋ **L2 配置面**（`application.yml:396-407`） | 2026-11-03 |

---

## 4. 域侧能自修的（**本域直接修**，不甩给收口）

| # | 项 | 状态 | 证据 |
|---|---|---|---|
| 4-1 | 🔴 **共享层 JSON 转换器的退让机制** —— C 侧靠**同名 bean**（`@Configuration("seaJsonBodyConverterConfigurer")`）让共享层 `JsonBodyConverterAutoConfiguration:91-92` 的 `@ConditionalOnMissingBean(name=…)` 退让 | ✅ **已修（实现期）** ＋ **实测生效**：真 Tomcat「带 `Content-Type: text/plain` 也 200」<br>⚠️ **不得改用 `spring.autoconfigure.exclude`**（会连带关掉 `seaCoercionMapperCustomizer`）⇒ auto-config 在用户 `@Configuration` **之后**注册 ⇒ 条件生效 | 配套报告 §四·4.B-1 ＋ 变异 **M-1** |
| 4-2 | 🔴 **`TaskApiJsonBodyConfig` 覆写了 `canRead(ResolvableType, …)`** —— Spring 6.2.16 的 `MappingJackson2HttpMessageConverter` **不在** `SmartHttpMessageConverter` 分支（`javap` 实测）⇒ **该覆写永不被调用** | ✅ **已修**：改覆写 `Type` 版三参 ＋ 2 参（`canRead(Type,Class,MediaType)` / `canRead(Class,MediaType)` / `canWrite(Type,Class,MediaType)` / `canWrite(Class,MediaType)`），删 `ResolvableType` import、加 `java.lang.reflect.Type` import | 配套报告 §四·4.E-1 |
| 4-3 | **`TaskApiJsonBodyConfig` 的 javadoc 把共享层的让位机制说成「canRead 抢先」** | ✅ **已改注释**（如实描述「同名 bean 让位」） | 配套报告 §四·4.E-2 |
| 4-4 | **`TaskApiProperties`（prefix `task.api`）在 api+rpc 合并后零消费点（哑弹类）** | ✅ **已改名** `TaskRewardProperties`（prefix `task.reward`，**键名逐字不变**）—— 它是本域唯一「**B 侧有配置 ＋ C 侧有真实消费点 ＋ B 侧无 Properties 类**」的块 | 配套报告 §五·5.2 #4 |
| 4-5 | **`TaskApiResponseAdvice` 的职责「待拍板」** | ✅ **已定**：**原样透传、不加信封**（真 Tomcat 断言成功体无信封） | 配套报告 §五·5.2 #5 |
| 4-6 | **`WebMvcConfig` 的 javadoc 写「注册 JwtAuthInterceptor」** | ✅ **已改写为否定式声明**（零拦截器 ＋ 零 CORS）⇒ 真 Tomcat **GET → 405**（若装了拦截器则是 401） | 配套报告 §五·5.2 #6 |
| 4-7 | **`TaskTraceAttributes` 的可见性**（B 侧包级私有 ⇒ C 侧需跨类调用） | ✅ **提为独立 `public final class`**（**可见性放宽、行为零变化**） | 配套报告 §五·5.2 #7 |
| 4-8 | 🔴 **一条「假覆盖」用例** —— 「计数器 INCR 跨 key 隔离」自己手拼 key（`ragv1:count:…`），**没调用** `TaskCountStore` ⇒ 变异 M-4 第一轮**仍绿** | ✅ **已修**：判据迁到 `messaging/TaskCountStoreRealRedisTest`（3 例，**直接用生产 bean** ＋ `TaskCountStore#keyOf` 钉住字面形态）⇒ M-4 复跑**精确转红** | 配套报告 §八·8.2 #11 |
| 4-9 | 🔴 **`service/task/pom.xml` 把 `io.grpc:grpc-inprocess` 声明成 `test` scope** ⇒ 🔴 **C 侧 fat jar 启动即挂**（`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder` —— devh 的 `GrpcClientAutoConfiguration#shadedNettyGrpcChannelFactory` **运行期**就要这个类）⇒ 而 **52 例测试全绿**（测试类路径带这个类）⇒ **「mvn test 全绿」证不了「fat jar 起得来」** | ✅ **已修**（2026-11-04 · **双侧活体对拍实跑时暴露**）：改为 **compile（默认）scope** ＋ 补完整成因注释<br>⚠️ **本域测试一条都没用到 `io.grpc.inprocess`**（`grep` 零命中）⇒ 原声明**无消费点**<br>⇒ **B 出处**：B 侧两个 fat jar **都含** `BOOT-INF/lib/grpc-inprocess-1.63.0.jar` ⇒ B 的**运行时**类路径带这个类 ⇒ C 必须同口径<br>⇒ **与本仓既有修法同款**：`service/users/user/pom.xml`（2026-11-03 同型踩坑）· `service/article/pom.xml` · `service/follow/pom.xml` · `service/like/pom.xml` | 配套报告 §五·5.2 #9 |

---

## 5. 需转派**「合并收口执行者」**的（**本域只登记：现象 + 证据 + 建议修法**）

| # | 项 | 现象 | 证据 | 建议修法 | 严重度 |
|---|---|---|---|---|---|
| 5-1 | **`docs/DRIFT/_INDEX.md` 未收录本域台账** | 本域台账已写好（2 份），但 `_INDEX.md` 的 `task` 行仍是「⬜ 未建立」 | 配套报告 §七·7.2 **G-2** | 🔴 **本 lane 不碰**（`docs/Worktree.md` §三·1）⇒ 由收口执行者统一更新 | 🟢 流程 |
| 5-2 | **`docs/Intentional_Deviations/` 未登记 task 域的 3 条主动变更** | DRIFT-TASK-002（端口 18886/9005 → 8890/9006）· DRIFT-TASK-003（ack mode BATCH → RECORD）· **DRIFT-TASK-016**（缺 body 报文内嵌的**类全限定名** ⇒ **D7/D11 包压平导致** · 2026-11-04 双侧对拍新登记） | 配套报告 §五 | 🔴 **`docs/Intentional_Deviations/_INDEX.md` 禁写** ⇒ 本域**只登记** ⇒ 由收口执行者建 `docs/Intentional_Deviations/task/*.md` | 🟢 流程 |
| 5-3 | 🔴 **共享层 `JsonBodyConverterAutoConfiguration` 的 bean 名是「隐式契约」** | 该 auto-config 用 `@ConditionalOnMissingBean(name="seaJsonBodyConverterConfigurer")` ⇒ **task 域靠「同名 bean」让它退让**（`config/TaskApiJsonBodyConfig.java` 的 `@Configuration("seaJsonBodyConverterConfigurer")`）⇒ 🔴 **共享层若改 bean 名 ⇒ task 域静默失效**（请求体变 400）<br>　　⇒ 🔴 **M-1 变异已实测坐实**：改掉域内的 bean 名 ⇒ `TaskApiWireContractRealTomcatTest#nonJsonContentTypeIsStillParsed` **立刻转红**（400）⇒ 对照用例仍绿 ⇒ 还原后回绿 | `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java:91-92` ＋ `common/src/main/java/com/rag/common/web/JsonBodyConverter.java` | 共享层把该 bean 名**提为常量**并写进 javadoc（或改成「域内可显式排除」的接口）⇒ 本域的 `TaskApiWireContractRealTomcatTest` 就是这条耦合的**唯一防线**（已写进测试类 javadoc）＋ **变异 M-1** 已证明该用例对这条耦合**敏感** | 🟡 **P2**（**静默失效面**） |
| 5-4 | **`docs/Mapping_Table/_INDEX.md` 的「映射类型」图例表形状坏** | 表头 4 列（`类型/条数/占比/含义`），**4 个数据行只有 3 列** ⇒ GFM 渲染缺第 4 列；`git show HEAD:docs/Mapping_Table/_INDEX.md` 证明是**基线既有**（非本轮引入） | 配套报告 §七·7.2 **G-1** | 补齐 4 个数据行的「含义」列 | 🟢 低（文档面） |
| 5-5 | **`docs/File_Tree/proto.md` 比实际文件树多画了一个已不存在的空目录**（`proto/src/test/java/`） | 生成器重跑时该目录已不存在 ⇒ 该文件**陈旧一代** | 配套报告 §七·7-1 **U-5** | 🔴 **`docs/File_Tree/proto.md` 不是本域文件** ⇒ 本域**只登记** ⇒ 主会话跑一次 `python3 tools/gen-file-tree.py` 即可刷新 | 🟢 低（文档面） |

---

## 6. 未验证项（本轮明确留下的缺口，**不写成已闭环**）

| # | 项 | 为什么未验 | 什么时候能验 |
|---|---|---|---|
| 6-1 | ✅ **Kafka 消费面 6 个 listener 的「消费面真跑」** | — | ✅ **2026-11-04 已闭合**：新增 `messaging/TaskKafkaConsumeRealTest`（**3 例** · 类级 `@Tag("external-env")`）⇒ **真 broker**（`apache/kafka:3.7.2` · Testcontainers）⇒ 真 `@KafkaListener` ⇒ 真 `@Scheduled` flush ⇒ **真 Redis + 真 PG 落库** ⇒ 且 **6 个 listener 位点都真提交**（含「什么都不做」的诊断消费者）<br>⇒ 读数：`mvn -o -pl service/task -am test -Pexternal-env` ⇒ **Tests run: 3, Failures: 0, Errors: 0, Skipped: 0**（22.39 s）<br>⇒ **变异 M-5**（停掉用户路 sink 的 listener）⇒ **目标红 ＋ 对照绿 ＋ 还原绿** ⇒ 配套报告 §七·7-1 **U-1** / §八·8.1<br>⚠️ **隔离**：5 个 topic **本次运行唯一名** ⇒ 零历史 / 零跨域污染 / 零重放；生产 topic **字面值**由 `TaskKafkaListenerAssemblyTest` 负责<br>⚠️ **默认回归不含这 3 例** ⇒ 用**属性 + profile**（`task.test.excludedGroups`）⇒ **不用** `@EnabledIfDockerAvailable` |
| 6-2 | ✅ **双侧活体对拍** | — | ✅ **2026-11-04 已闭合**：新增 `tools/difftest/task/`（`run.sh` ＋ `http_probe.py` ＋ `cases.txt` ＋ `expected.txt` ＋ `known-cases.txt` ＋ `known-readings.txt` ＋ `judge_report.py` ＋ `mutate-parity.sh` ＋ `README.md`）<br>⇒ 被测两侧：**B 侧 `task-api`(:18241) ＋ `task-rpc`(:18242)** 与 **C 侧 `task-service`(:18247)** ⇒ 两侧**各自独立**的 Redis / Postgres 容器（🔴 **必须物理隔离** ⇒ 否则后跑一侧命中前跑一侧的回填 ⇒ **PG 回源路径被吃掉 ⇒ 假绿**）<br>⇒ 四个面：**M1 HTTP 字节面**（状态行 / Content-Type / nosniff / Allow / body · **裸 socket** —— curl 不给状态行 reason phrase）＋ **M2 Redis 状态面** ＋ **M3 PG 状态面** ＋ **M4 冻结锚面**（`expected.txt` = **B 侧读数** ⇒ **oracle 是 B 侧**）<br>⇒ 读数：**21 条用例 · B↔C 逐字节一致 20 条 · 已登记的表达层差异 1 条 · 真差异 0 条** ⇒ **EXIT=0**；**非冻结复跑同样 EXIT=0**（⇒ 冻结锚稳定）<br>⇒ 判定强度 **E-4（双侧活体对拍）** ⇒ **不冒充「三方一致」**（本机无 Go 真源可活体）<br>⇒ ⚠️ **唯一一条字节差 = DRIFT-TASK-016**（Spring 缺 body 报文内嵌**类全限定名** ⇒ **D7/D11 包压平导致**）⇒ **不修** ⇒ 但**用白名单钉住形状**（差异形状变了 ⇒ 判失败；**B 侧漂移 ⇒ 判失败**）<br>⇒ 🔴 **探针第一次跑就抓到一条真缺陷**：C 侧 fat jar **启动即挂**（`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`）⇒ **52 例测试全绿但生产 jar 起不来** ⇒ **已修**（`grpc-inprocess` 改 compile scope）⇒ 配套报告 §五·5.2 #9 / §七·7-1 **U-2** |
| 6-3 | ✅ **gRPC wire 层真 netty 收发** | — | ✅ **2026-11-04 已闭合**：新增 `grpc/TaskGrpcNettyServerRealTest`（**6 例**）＋ `support/GrpcServerPortCapture.java`<br>⇒ 真 netty server（`grpc.server.port=0` ⇒ OS 分配 ⇒ 取 `event.getServer().getPort()` —— ⚠️ **不是** `event.getPort()`，那在 port=0 时返回 0）＋ 真 netty client（🔴 **必须** `forAddress(SocketAddress)` ⇒ 免疫 devh `DiscoveryClientResolverFactory` 的**全局劫持**）＋ **真 proto 编解码** ＋ **真 Redis / 真 PG**<br>⇒ 覆盖：服务全名 `task.TaskService/GetTask`（**L1 契约**）· 两条任务定义逐字段 · Redis 命中路径 · PG 回源路径 · 错误出口（`INVALID_ARGUMENT` ＋ `"user_id invalid"` ＋ **trailer 非空**）<br>⇒ 读数：**Tests run: 6, Failures: 0, Errors: 0, Skipped: 0**（13.65 s）⇒ **默认回归 52 → 58 例**<br>⇒ **变异 M-6**（把 `StatusRuntimeException` 透传抹成 UNKNOWN）⇒ **目标红 ＋ 对照绿 ＋ 还原绿** ⇒ 配套报告 §七·7-1 **U-3** / §八·8.1<br>⚠️ **判定强度 = E-3（C 侧单侧真触发）** ⇒ **不冒充 E-4**（**gRPC 面尚未双侧对拍** ⇒ 现有 `tools/difftest/task/` 只覆盖 HTTP 面 ⇒ **已登记为 U-6** · 配套报告 §七·7-1） |
| 6-4 | ✅ **Redis flush 管线** | — | ✅ **2026-11-04 已闭合（随 6-1）**：真 Kafka 投递 ⇒ 真消费 ⇒ 真 flush ⇒ **真落 PG**（`TaskKafkaConsumeRealTest` 的两条端到端用例）⇒ 配套报告 §七·7-1 **U-4** |
| 6-5 | ✅ **共享层 gRPC 错误映射的「共享层版本」已核** | — | ✅ **本轮已闭合**：§1.4 的**机器比对**（`httpStatusOf` 17/17 ＋ `goCodeName` 17/17 **逐条一致**） |
| 6-6 | ✅ **变异证据（判据有牙齿）** | — | ✅ **已闭合**：**M-1 ~ M-6** 六组（单元测试层）+ **M-P1 ~ M-P4** 四组（**对拍探针层**）⇒ 每组都要求「目标断言转红 ＋ 对照断言仍绿」＋ 还原后回绿 ⇒ **实测 EXIT=0**<br>⇒ 脚本：`tools/difftest/task/mutate-task.py`（M-1~M-6）· `tools/difftest/task/mutate-parity.sh`（M-P1~M-P3 · **HTTP 面**）· `tools/difftest/task/mutate-grpc-parity.sh`（**M-P4 · gRPC 面** · 多一条「HTTP 面看不见」判据）⇒ 配套报告 §八·8.1 / §八·8.1-b<br>⚠️ **M-4 第一轮曾「仍绿」** ⇒ 暴露出一条**假覆盖**用例（自己手拼 key、没调用被测类）⇒ **已修**（判据迁到 `messaging/TaskCountStoreRealRedisTest`）⇒ 复跑转红 ⇒ 配套报告 §八·8.2 #11 |

---

## 7. 交付物清单（**本域侧**）

| # | 文件 | 说明 |
|---|---|---|
| 7-1 | `service/task/src/main/java/com/rag/task/**`（**40 个主类 · 零空壳**） | B 侧 39 个主类 **＋** 1 条 C 新增（`TaskRewardProperties`） |
| 7-2 | `service/task/src/main/resources/application.yml`（1 份 · 25 KB） | B 侧两份 yml 合成 |
| 7-3 | `service/task/src/main/resources/db/migration/V1__create_task_progress.sql` ＋ `V2__create_user_like_count.sql` | **逐字复制**（`diff -r` 零差异） |
| 7-4 | `service/task/src/test/java/com/rag/task/**`（**9 个测试类 ＋ 5 个 support · 61 例**＝默认 58 ＋ 外部依赖面 3） | 真 PG ＋ 真 Redis ＋ 真 Tomcat ＋ **真 Kafka**（U-1）＋ **真 netty gRPC server/client**（U-3）＋ Kafka 装配层 ＋ **变异验证脚本**（`tools/difftest/task/mutate-task.py` · M-1~M-6） |
| 7-5 | `docs/DRIFT/task/verification-task-service-2026-11-03.md` | 本域八阶段迁移审核报告 |
| 7-6 | `docs/DRIFT/task/vs-common.md`（**本文件**） | 跨层对账 |
| 7-7 | `docs/Mapping_Table/task/task.md`（**401 行**） ＋ `docs/Mapping_Table/_INDEX.md` 的 **task 行** ＋ `docs/File_Tree/task.md` | 本域域文档（**只改 task 相关**） |
| 7-8 | 🔴 **`tools/difftest/task/`**（**15 个文件**） | **双侧活体对拍探针**（**E-4 强度 · HTTP 面 + gRPC 面**）：`run.sh`（编排 · 10 步）· `http_probe.py`（裸 socket 字节探针）· `cases.txt`（21 条 HTTP 用例）· `expected.txt`（**冻结锚 = B 侧读数**）· `known-cases.txt` ＋ `known-readings.txt`（**已登记的表达层差异** ＋ 其形状）· `grpc_probe/GrpcProbe.java`（**真 netty client**）· `grpc_probe.py`（gRPC 面驱动）· `grpc_cases.txt`（7 条 gRPC 用例 · **uid 与 HTTP 面物理错开**）· `grpc_expected.txt`（**gRPC 面的冻结锚**）· `grpc_known-cases.txt` ＋ `grpc_known-readings.txt`（**当前 0 条**）· `judge_report.py`（变异判定器 · **两面共用**）· `mutate-parity.sh`（**HTTP 面变异** M-P1~M-P3）· `mutate-grpc-parity.sh`（**gRPC 面变异** M-P4）· `README.md`（口径与用法） |
| 7-9 | `common/**` | 🔴 **零改动** |

> 📌 **本 lane 对共享面的改动合计 = 0** ⇒ 共享层的任何问题都**不是**本域引入的；
> §5 的 5 条转派项**全部是「现象 + 证据 + 建议修法」**，**本域一行都不改**。
