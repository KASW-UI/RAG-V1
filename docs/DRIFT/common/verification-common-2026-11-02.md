# `common/` 迁移审核报告 —— RAG → RAG-V1

> **审核协议**：`.agents/verification.md`（八阶段：范围 → 承接清单 → 行为审核 → 框架差异 → 合并拆分 → 对拍 → 状态 → 报告）。
> **审核日期**：2026-11-02 · **审核对象**：`common/`（starter，**只审核不改代码**）· **本次未修改任何源码 / POM / 配置 / Mapping_Table / File_Tree**。
> **状态口径**（§十）：`PASS` / `FAIL` / `NOT_IMPLEMENTED` / `NOT_VERIFIED` / `FRAMEWORK_DIFFERENCE` / `BLOCKED` / `INTENTIONALLY_CHANGED`。
> ⚠️ **四种非 PASS 状态一律不计入 PASS**（§十）。

---

## 11.1 审核范围

### 两侧坐标

| 项 | B（迁移源） | C（迁移目标） |
|---|---|---|
| 仓库 | `/Users/pr/Documents/java&golang/RAG` | `/Users/pr/Documents/GitHub/RAG-V1` |
| 模块路径 | `service/common/` | `common/`（**根级 starter**） |
| 包名 | `com.rag.common.*` | `com.rag.common.*` |
| 主代码类 | **88**（12 个包） | **71**（12 个包） |
| 测试 | **93 类 / 488 例**（离线实测全绿） | **14 类 / 96 例** |
| Spring Boot | **4.0.8** | **3.5.11** |
| Spring Framework | 7.x | **6.2.x** |
| Jackson | **3**（`tools.jackson.*`） | **2**（`com.fasterxml.*`，2.19.4） |
| Tomcat | 11.0.24 | **10.1** |
| gRPC | devh starter 3.1.0.RELEASE（锁 grpc 1.63.0） | 同 |
| Cloud | — | Spring Cloud 2025.0.3 · SCA 2025.0.0.0（nacos-client 3.0.3） |
| Resilience4j | **无**（手写 googlebreaker 移植） | `resilience4j-spring-boot3` |
| Micrometer Prometheus | 1.16.7 | 有依赖，**但无 sink 实现** |
| Git commit | `Sea-RideTheWind_java` 工作区 | `dcf1e19` |

### 使用的既有资料

| 资料 | 路径 | 本次用途 |
|---|---|---|
| Mapping_Table | [`../../Mapping_Table/common.md`](../../Mapping_Table/common.md) | B→C 定位依据（**仅定位，不作实现证据**） |
| File_Tree | [`../../File_Tree/common.md`](../../File_Tree/common.md) | C 目标结构约束 |
| 决策记录 | [`../../决策记录.md`](../../决策记录.md) | D1~D14（判定哪些差异是「有意」） |
| L3 降级清单 | [`../../L3-降级清单.md`](../../L3-降级清单.md) | D-01~D-05（已登记降级） |
| 意图偏离 | [`../../Intentional_Deviations/_INDEX.md`](../../Intentional_Deviations/_INDEX.md) | 已签字偏离 |

### B 侧逐包类数（`service/common/src/main/java/com/rag/common/`）

`communityfact` 4 · `exception` 2 · `http` 13 · `logger` 6 · `observability` 19 · `persistence` 3 · `protection` 24 · `redis` 5 · `response` 1 · `snowflake` 1 · `spring` 9 · `web` 1 = **88**

### 范围边界（明确排除）

- **各域 `service/<域>/`**：本次不审（域内 520 个主类，其中 54 个非空壳 = `contract/` 25 + `metrics/` 12 + `controller/` 11 + `wiring/` 4 + `users/common/` 2，**仍是骨架**）。
- **B 侧 `service/users/users-common/` 的 `jwt/`+`cryptx/` 3 类**：被 C 侧上收到 `common/security/`（属 §8.4 跨域上收），本次**只登记承接状态**，不逐条审实现。
- **`proto/` 模块**：不审（本次只审 `common/`）。

---

## 11.2 B → C 责任承接表

> **覆盖口径**：B 侧 **88/88 个主类**逐类有承接结果，无「其余类似」跳过。
> **证据列**：`源码级` = 读 C 实际源码确认；`对拍` = 有真触发测试；`静态` = 仅源码比对（**不算 PASS 之外的加强**）。

### (1) `communityfact/`（B 4 → C 13）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `Wire.java`（事件协议 4 类型 + 严格解码 + JCS 指纹 + DC sender + 回执校验 + subject 解析） | L1 wire 契约 + 幂等指纹 | `communityfact/Wire.java` + `Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `DeliveryStatus` / `CanonicalHash` / `EventSender` / `EventDispatcher` / `ProcessMetadata` | 1:N | **PASS** | 源码级：**23 个 JSON tag 与 Go 真源 `wire.go` 逐字一致**；`predecessor_event_id` 的 Go `,omitempty` ≡ C `@JsonInclude(NON_EMPTY)`；`DeliveryStatus` 0/1/2/3；endpoint `/v1/events`；`Idempotency-Key`/`Authorization`/`Content-Type` 三头逐字；body 上限 64 KiB；`"trailing JSON"` 文案 |
| `Dispatcher.java`（投递循环：批次 16 / 超时 10s / 节奏 1s / 3 线程名 / 2 错误码） | 事件投递节奏控制 | `communityfact/EventDispatcher.java`（554 行）+ `Dispatcher.java`（34 行 SPI） | 1:N | **PASS** | 源码级：线程名 `communityfact-dispatcher` / `communityfact-dispatch` / `communityfact-dispatcher-shutdown` 逐字一致；批次 `for (int i = 0; i < 16; i++)`；`future.get(10, TimeUnit.SECONDS)`；`stopSignal.get(1, TimeUnit.SECONDS)`；错误码 `INVALID_FROZEN_ENVELOPE` / `DELIVERY_ATTEMPT_FAILED`（**只认类型不认文案**，M16 修复保留） |
| `ProcessLogger.java`（进程 JSON 日志信封 + 本地 OTel 引导） | 结构化日志信封 | `communityfact/ProcessLogger.java`（244 行）+ `ProcessMetadata.java` | 1:1 | **PASS** | 源码级：JSON 键序 11 项逐字；`log_source="application"`；时间戳 `yyyy-MM-dd'T'HH:mm:ss` + 毫秒去尾零 + 毫秒为 0 时不出现小数点；`version` 须 40 位小写 hex；environment 四值 |
| `AuthorityHandler.java`（内部查询端点） | 跨服务事实查询 | `communityfact/AuthorityHandler.java`（289 行）+ `WebAutoConfiguration.registerAuthorityHandler(...)` | 1:1 | **PASS** | 源码级：路径 `/internal/v1/community/facts` 逐字；token 长度 ≥32；`Authorization` 须恰好 1 个且 `Bearer ` 前缀；`producer`/`event_id` 各恰好 1 个（重复 ⇒ **404** 不是 400）；恒定时间比较 `MessageDigest.isEqual` |

### (2) `exception/`（B 2 → C 2）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `BusinessException.java` | 业务失败异常（int 码 + 文案） | `exception/BusinessException.java` | 1:1 | **PASS** | 源码级 |
| `AuthenticationException.java` | 鉴权专用异常（**靠类型区分 401**，不靠 code） | `exception/AuthenticationException.java` | 1:1 | **PASS** | 源码级：继承关系保留 ⇒ 域 handler 捕获顺序不变 |

### (3) `http/`（B 13 → C `web/` + `autoconfigure/`）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `GoStylePathNormalizingAutoConfiguration.java` | 路径归一 Filter 的唯一 Bean 定义点 | `autoconfigure/WebAutoConfiguration.java` 的 `@Bean` | N:1 | **PASS** | 源码级 |
| `GoStylePathNormalizingFilter.java` | 按 Go `path.Clean` 归一路径 | `web/PathNormalizingFilter.java` `@Order(+2)` | 1:1 | **PASS** | 源码级：槽位 +2 一致；`goPathClean` 对齐 `path.Clean`（`/` ⇒ `/`、`..` ⇒ `..`）；**不短路** |
| `GoStyleJsonContentTypeAdvice.java` | JSON 响应补 `charset=utf-8` | `web/JsonContentTypeAdvice.java` + `WebAutoConfiguration` 的 `@Bean` | 1:1 | **PASS** | 源码级：写 `application/json;charset=utf-8`（无空格小写） |
| `GoStyleJsonBodyConverter.java` | Go `httpx.ParseJsonBody` 的「何时才读 body」判据 + 8 MiB 上限 | `web/JsonBodyConverter.java` | 1:1 | **PASS** | 源码级：`GO_MAX_BODY_LEN = 8*1024*1024`；`goReadsBody` 判据字面子串匹配（`Application/JSON` 大写 ⇒ 丢弃） |
| `GoStyleJsonBodyConverterConfig.java` | 把 converter 插到 Boot Jackson **之前** | `web/JsonBodyConverterConfig.java` + `autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:N | **PASS** | 对拍：Spring 6.2 无 `JacksonJsonHttpMessageConverter` ⇒ 改判 `MappingJackson2HttpMessageConverter`；插入点断言在 `JsonBodyConverterAutoConfigurationTest`（7 例） |
| `GoStyleTraceparentFilter.java` | 响应头写 W3C `traceparent` | `web/TraceparentFilter.java` `@Order(+3)` | 1:1 | **FRAMEWORK_DIFFERENCE** | 源码级：槽位 +3 一致；canonical 头名（大写 T）一致；⚠️ **值来源不同** —— B 从 `GlobalOpenTelemetry` 取 server span，C 侧 OTel 直写因 `micrometer-tracing-bridge-otel` 离线不可得而是 no-op（已登记 D-03）⇒ 头可能缺失 |
| `GoStyleTomcatParameterLimitCustomizer.java` | Tomcat 参数上限抬到 10001 | `autoconfigure/WebAutoConfiguration.java`（`maxParameterCount=10001`） | N:1 | **PASS** | 源码级 + 守门：Boot 3.5 包名 `org.springframework.boot.web.embedded.tomcat.*` 已回改 |
| `GoStyleRouterErrorResponses.java` | go-zero 404/405 字节级复刻 | `web/RouterErrorResponses.java` | 1:1 | **PASS** | 源码级：404 body 19 字节 `404 page not found\n` + `text/plain; charset=utf-8` + `X-Content-Type-Options: nosniff`；405 `Allow` 用 `", "` 连接**不排序** + 空 body **无 Content-Type** |
| `GoStyleFormValues.java` | Go 口径 query 解析 + form 值上限 | `web/GetFormValuesFilter.java` + `web/PlainTextErrors.java` | N:1 | **PASS** | 源码级：段数上限 10000 / 非空值上限 2048；三条错误文案逐字（`number of URL query parameters exceeded limit` / `invalid URL escape "%zz"` / `invalid semicolon separator in query`）；400 + `text/plain; charset=utf-8` + nosniff + body 尾 `\n` |
| `GoStyleFormLimitMode.java` | 闸门取值域（off/false/raw/envelope） | `web/LimitMode.java` | 1:1 | **PASS** | 源码级：`OFF`/`RAW`/`ENVELOPE` 三值；`"false"` ≡ OFF（YAML 布尔兜底）保留；非法值启动失败 |
| `GoStyleFormLimitExceededException.java` | ENVELOPE 模式抛给域兜底 handler | `web/LimitExceededException.java` | 1:1 | **PASS** | 源码级：仍继承 `RuntimeException`（**不**继承 `BusinessException`）⇒ 落域 `@ExceptionHandler(Exception.class)` |
| `GoStyleFormValueLimitInterceptor.java` | 按 mode 决定拒绝形状 | `web/ValueLimitInterceptor.java` | 1:1 | **PASS** | 源码级：OFF 直放 / RAW 写 400 并 `return false` / ENVELOPE 抛异常 |
| `GoStyleFormLimitWebMvcConfig.java` | 把 value-limit 拦截器挂进 MVC 链 | `autoconfigure/WebAutoConfiguration.java`（`addInterceptors`） | N:1 | **PASS** | 源码级：`HIGHEST_PRECEDENCE` ⇒ 先于域鉴权 |

### (4) `logger/`（B 6 → C 4）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `Logger.java`（业务日志门面：3 类 message + KV 集合 + call_chain） | 结构化日志 | `logger/GoStyleLogger.java`（997 行） | 1:1 | **PASS** | 对拍：`GoStyleLoggerTest`（5 例）；源码级：3 个 message 名（`business_error`/`business_info`/`fatal_error`）+ 各自 KV 序列 + **空串 ⇒ 字段整体消失** + `event_id`/`trace_id` 的 `"unknown"` 兜底 + `call_chain` 的 `" -> "` 分隔与 32 帧上限 + `image_base64` 截断正则 |
| `FatalError.java` | 致命错误类型（不被 `@ExceptionHandler(RuntimeException)` 吞） | `logger/FatalError.java` | 1:1 | **PASS** | 源码级 |
| `LogOption.java` | 选项函数接口 | `logger/LogOption.java` | 1:1 | **PASS** | 源码级 |
| `LogOptions.java` | 可变载体（userId / articleId） | `logger/LogOptions.java` | 1:1 | **PASS** | 源码级：选项集合**仍只有 2 个** |
| `WithUserID.java` | user_id 选项具名类 | `logger/LogOption.java` 的静态工厂 `withUserId` | N:1 | **PASS** | 源码级：行为一致（**选项集合未增**，L1 日志契约不破） |
| `WithArticleID.java` | article_id 选项具名类 | `logger/LogOption.java` 的静态工厂 `withArticleId` | N:1 | **PASS** | 源码级：同上 |

### (5) `observability/`（B 19 → C 6）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `ObservabilityConstants.java` | 常量表 | `observability/ObservabilityCustomizer.java`（常量块） | N:1 | **PASS** | 源码级：`SUCCESS_BIZ_CODE=200` / `DEFAULT_SLOW_THRESHOLD=500ms` / 7 个 span key / 3 个 error kind / 2 个 env 兜底名 **逐项一致** |
| `Observability.java` | span 打标门面（业务/系统/超时/慢） | `observability/ObservabilityCustomizer.java` | N:1 | **PASS** | 对拍（`ObservabilityAutoConfigurationTest` 5 例）+ 源码级：slow **严格 `>`**；`code==200` ⇒ 退化为 success；判定顺序 **业务码优先于状态码** |
| `HttpMiddleware.java`（58 KB：HTTP 观测 + 超时 + 断连 + 512 worker 池） | Go `rest.Middleware` 等价物 | `autoconfigure/ObservabilityAutoConfiguration.java` 的嵌套 `HttpObservationFilter` | N:1 | **NOT_IMPLEMENTED**（部分） | 源码级：**span 属性 4 个 + 判定顺序已承接**；🔴 **未承接**：worker 线程池（512）/ HTTP 超时 503 + body `Request Timeout\n` / 客户端断连 **499** ⇒ C 侧全仓 grep `499|EOFException|ClientAbort` **零命中** ⇒ 见 **DRIFT-COMMON-V04** |
| `GoStyleClientDisconnectProbe.java`（Tomcat 反射写 103 探针） | 客户端断连主动暴露 | `observability/ClientDisconnectProbe.java` | 1:1 | **NOT_IMPLEMENTED** | 源码级：C 侧**类体为空**（仅 javadoc）⇒ 见 **DRIFT-COMMON-V06** |
| `GoStyleRequestDeadline.java` | HTTP 超时预算 ThreadLocal 载体 | `observability/GoStyleRequestDeadline.java` | 1:1 | **PASS**（登记项） | 源码级：保留 `GoStyle*` 名（K-03 语义标记）；`applyTo` 语义保留 |
| `GoStyleRecoverInterceptor.java`（gRPC panic → INTERNAL + `panic: `） | gRPC panic 恢复 | — | — | **NOT_IMPLEMENTED** | 见 **DRIFT-COMMON-V03** |
| `GoStyleStatInterceptor.java`（每请求耗时样本） | gRPC 耗时采样 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleStatMetrics.java`（进程内 RPC 聚合器 + 每 60s 统计日志） | Go `core/stat` | — | — | **NOT_IMPLEMENTED** | 同上；⚠️ 含 3 个**刻意的 Go 对齐怪癖**（avg 先整除 / median = 第 n>>1 大 / 小样本退化成最大值） |
| `GoStylePrometheusInterceptor.java` | gRPC 耗时直方图 + 状态码计数 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStylePrometheusMetrics.java` | Prometheus 写入面静态 holder | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStylePrometheusSink.java` | 写入面接缝接口 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `MicrometerPrometheusSink.java`（`rpc_server_requests_duration_ms` 12 桶 + `rpc_server_requests_code`） | Micrometer 实现 | — | — | **NOT_IMPLEMENTED** | 同上；C 侧 grep `rpc_server_requests` **零命中** |
| `GoStylePrometheusMeterRegistryBridge.java` | sink 装配点 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleRpcMetricsConfiguration.java` | RPC 指标面装配（stat 名字） | `autoconfigure/ObservabilityAutoConfiguration.java` | N:1 | **NOT_VERIFIED** | 源码级：auto-config 已承接该职责，但**其产出的两个 meter 无实现** ⇒ 装配点是空的 |
| `GoStyleOtelAutoConfiguration.java`（BFPP + 全局 OTel 安装） | 全局 OTel 装配 | `autoconfigure/ObservabilityAutoConfiguration.java` | N:1 | **FRAMEWORK_DIFFERENCE** | 源码级：C 侧无 `GlobalOpenTelemetry.set`、无 exporter、无 sampler 映射；⚠️ `micrometer-tracing-bridge-otel` 离线不可得 ⇒ `Span.current()` 是 no-op（**D-03 已登记**） |
| `GoStyleOtelConfig.java`（Go `Telemetry` 配置投影） | 配置载体 | `observability/ObservabilityProperties.java` | 1:1 | **PASS**（部分） | 源码级：3 个 Duration 字段 + env 兜底名一致；⚠️ B 的 `protocol`/`sampler`/`endpoint` 三项**未承接**（C 侧只留 slow/httpTimeout/rpcTimeout） |
| `GoStyleOtelAgent.java`（SDK + OTLP exporter + 传播器 + shutdown hook） | 全局 tracer provider | — | — | **NOT_IMPLEMENTED** | 同 D-03 |
| `MqConsumer.java`（MQ 消费端 trace 包装） | Go `TraceConsumer` | — | — | **NOT_IMPLEMENTED** | C 侧 grep `MqConsumer\|SpanKind.CONSUMER` 仅命中 javadoc |
| `RpcInterceptor.java`（gRPC 最内层业务观测 + rpc-timeout + slow） | gRPC 业务观测 | `autoconfigure/ObservabilityAutoConfiguration.java` 的嵌套 `GrpcObservationInterceptor` | N:1 | **PASS**（部分） | 源码级：7 业务码集合 / `DEADLINE_EXCEEDED` 优先 / `rpc-timeout` 调度器 关 `DEADLINE_EXCEEDED`；⚠️ B 的 `HANDLE_EXECUTOR` / `TIMEOUT_SCHEDULER` 两个池的生命周期类（`ObservabilityLifecycle`）**未承接** |

### (6) `persistence/`（B 3 → C 4）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `DeadlineQueryTimeoutInjector.java` | 按 deadline 给每条 SQL 设 query timeout | `persistence/DeadlineQueryTimeoutInjector.java`（321 行） | 1:1 | **FAIL** | 源码级：实现完整（`StatementHandler.prepare` 拦截 + `Wrapper.unwrap` 剥池代理 ≤5 跳 + `57014` + 毫秒精度/秒级回落）；🔴 **但全仓零注册点** ⇒ 链路不生效 ⇒ 见 **DRIFT-COMMON-V02** |
| `SqlTimeoutStatusTranslator.java` | `57014` → gRPC `DEADLINE_EXCEEDED` | `persistence/SqlTimeoutStatusTranslator.java`（154 行） | 1:1 | **FAIL** | 同上（实现完整、零注册）⇒ **DRIFT-COMMON-V02** |
| `MybatisPlusOrderingBridgeAutoConfiguration.java` | 自动配置排序边（**无行为面**） | `autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 1:1 | **PASS** | 源码级：Boot 3.5 包名 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` **已回改**（B 侧是 Boot 4 的 `org.springframework.boot.jdbc.autoconfigure.*`）⇒ 排序边生效 |
| （C 侧新增）`JsonTextTypeHandler` / `JsonbTypeHandler` | — | `persistence/` 2 类 | C 新增 | **NOT_VERIFIED** | B 侧对应物在域内 `article-api`（上收），本次不在范围内 |

### (7) `protection/`（B 24 → C 5）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `GoStyleProtectionProperties.java`（Go struct tag 默认值显式化，12 项） | 配置聚合点 | `protection/ProtectionProperties.java` | 1:1 | **PASS** | 源码级：prefix `rag.protection` + **12 项默认值逐条一致**（maxConns 10000 / maxBytes 1048576 / cpu 900‰ SYSTEM / breaker on / outbound on + cap 0 / redis-deadline on / stat on / prometheus on） |
| `GoStyleProtectionConfiguration.java` | properties 注册 + client 配置 `@Import` | `autoconfigure/ProtectionAutoConfiguration.java`（`@EnableConfigurationProperties`） | N:1 | **PASS** | 源码级 |
| `GoStyleBreakerRouteTemplateAutoConfiguration.java` | 注册路由模板解析器 | `protection/RouteTemplateSource.java` + `RouteResolution.java` | 1:N | **NOT_IMPLEMENTED** | 源码级：C 侧两个类**类体为空且全仓零引用**；🔴 批 3 的 `RouteIsolationFilter` **绕过**它们直接读 `RequestMappingHandlerMapping` ⇒ **职责断裂** ⇒ **DRIFT-COMMON-V06** |
| `GoStyleBreaker.java`（googlebreaker 逐行复刻：滚动窗口 + 概率 dropRatio + force-pass） | 熔断算法 | `autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `RouteIsolationFilter`（resilience4j 承载） | N:1 | **INTENTIONALLY_CHANGED** | 源码级：D-02 已登记「阈值参数不等价」；🔴 **但实测差异比登记的更大** —— B 是**概率性拒绝 + 无三态**，C 是 resilience4j **计数窗口 + 打开/半开/关闭** ⇒ **拒绝语义类型都不同** ⇒ **DRIFT-COMMON-V09** |
| `GoStyleBreakerFilter.java` | HTTP 按路由熔断 | 同上 | N:1 | **PASS**（判据）/ **FAIL**（回落语义） | 源码级：隔离 key `METHOD://<模板>` ⇒ `GET:///article/{id}`（**3 斜杠**）与 Go 逐字一致；成败判据 `<500` 成功、**下游 200+业务码不触发** 与 Go 逐字一致；🔴 **回落语义相反** ⇒ **DRIFT-COMMON-V05** |
| `GoStyleMaxConnsFilter.java`（全局信号量闸门） | 并发连接上限 | —（改为 Tomcat `server.tomcat.max-connections`） | N:1 | **NOT_IMPLEMENTED** | 源码级：yml 搬运已叫停 ⇒ **L-5 待办**；⚠️ B 侧全局闸门**本身已是签字偏离**（`SD-2026-10-25-01`，Go 是 per-route）⇒ C 若照抄需**重新登记** |
| `GoStyleCpuSheddingFilter.java` | CPU 自适应降载 | `autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `CpuSheddingFilter` + `protection/CpuThresholdHealthIndicator.java` | N:1 | **INTENTIONALLY_CHANGED** | 源码级：D-04 已登记「只有 CPU 一半判据」；⚠️ 另有一处**未登记**缺口：每分钟降载统计日志行 ⇒ **DRIFT-COMMON-V10** |
| `GoStyleCpuReader.java`（CPU 千分比采样） | CPU 读数 | `protection/CpuThresholdHealthIndicator.java` | N:1 | **PASS**（fail-open） | 源码级：`UNAVAILABLE=-1` 哨兵 + fail-open 保留 |
| `GoStyleMaxBytesFilter.java` | Content-Length 上限 | `protection/MaxBytesFilter.java` | 1:1 | **PASS** | 源码级：裸 **413** + 空 body + **无 Content-Type**；只判 `Content-Length`（chunked ⇒ 放行）；`n<=0` no-op；槽位 `+8` |
| `GoStyleAdaptiveShedder.java`（降载双条件判据） | QPS×RT 动态上限 | —（只有 CPU 一半） | N:1 | **NOT_IMPLEMENTED** | D-04 已登记（L3） |
| `GoStyleSheddingStat.java`（三计数 + 每分钟日志） | 降载统计 | — | — | **NOT_IMPLEMENTED** | **DRIFT-COMMON-V10** |
| `GoStyleRouteTemplateSource.java` | 「能解析路由模板」窄接口 | `protection/RouteTemplateSource.java`（空类） | 1:1 | **NOT_IMPLEMENTED** | **DRIFT-COMMON-V06** |
| `GoStyleRouteTemplateResolver.java`（三态解析 + 只认 `RequestMappingHandlerMapping`） | 模板解析 | —（`RouteIsolationFilter` 内联实现） | N:1 | **PASS**（行为）/ **FAIL**（职责断裂） | 源码级：三态语义在 C 侧被简化为「拿到 / 拿不到」⇒ **DRIFT-COMMON-V05 + V06** |
| `GoStyleRouteResolution.java` | 三态值对象 | `protection/RouteResolution.java`（空类） | 1:1 | **NOT_IMPLEMENTED** | **DRIFT-COMMON-V06** |
| `GoStyleStatusCapturingResponse.java` | 只观测状态码的 wrapper | `autoconfigure/ObservabilityAutoConfiguration.java` 内 `ContentCachingResponseWrapper` | N:1 | **PASS** | 源码级：隐式 200 初值 + `<500` 判据等价 |
| `GoStyleRpcSheddingInterceptor.java` | gRPC 服务端降载 | — | — | **NOT_IMPLEMENTED** | **DRIFT-COMMON-V03** |
| `GoStyleRpcBreakerInterceptor.java` | gRPC 服务端熔断 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleRpcFailurePolicy.java`（6 码失败白名单） | gRPC 熔断判据 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleCircuitOpenException.java`（熔断打开 ⇒ 映射 1015/`service busy`） | 熔断异常类型 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleGrpcClientBreakerInterceptor.java`（client 侧按「服务+方法」熔断） | 客户端熔断 | — | — | **NOT_IMPLEMENTED** | 同上；隔离 key 格式 `message-rpc/message.v1.MessageService/SendChat` |
| `GoStyleGrpcClientBreakerConfiguration.java` | client 熔断装配 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleOutboundDeadlineInterceptor.java`（出向 deadline min 传播 + per-service 覆盖） | 出向预算传播 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleOutboundDeadlineConfiguration.java` | 出向 deadline 装配 | — | — | **NOT_IMPLEMENTED** | 同上 |
| `GoStyleRpcProtectionChain.java`（链序唯一定义点：recover→stat→prometheus→breaker→shedding→business） | gRPC 链序 | `autoconfigure/ProtectionAutoConfiguration.java` 的 `compose(List)` | N:1 | **FAIL** | 源码级：C 侧链**只有 1 层**（业务观测）；B 的 **5 层全缺** ⇒ **DRIFT-COMMON-V03** |

### (8) `redis/`（B 5 → C 4）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `GoStyleRedisClientAutoConfiguration.java`（Lettuce 断连 ⇒ `REJECT_COMMANDS`） | 断连行为收口 | `redis/ClientAutoConfiguration.java`（146 行） | 1:1 | **FAIL** | 源码级：实现完整、Boot 3.5 包名 `org.springframework.boot.autoconfigure.data.redis.*` **已回改**；🔴 **不在 `AutoConfiguration.imports` + 全仓零引用 + 域侧无 `@Import` + 无 `@ComponentScan("com.rag.common")`** ⇒ **永不加载** ⇒ **DRIFT-COMMON-V01** |
| `GoStyleRedisDeadlineAutoConfiguration.java`（`BeanPostProcessor` 换 `RedisTemplate` 的 factory） | 请求预算感知 | `redis/DeadlineAutoConfiguration.java`（98 行） | 1:1 | **FAIL** | 同上 ⇒ **DRIFT-COMMON-V01** |
| `GoStyleRedisDeadlineConnectionFactory.java`（建连前查预算） | 连接工厂装饰器 | `redis/DeadlineConnectionFactory.java`（99 行） | 1:1 | **NOT_VERIFIED** | 源码级：实现完整；🔴 因上游 auto-config 未加载 ⇒ 整条链不生效 ⇒ 连带 **DRIFT-COMMON-V01** |
| `GoStyleRedisDeadlineConnection.java`（每命令查预算，JDK 动态代理） | 命令级预算检查 | `redis/DeadlineConnection.java`（121 行） | 1:1 | **NOT_VERIFIED** | 同上 |
| `GoStyleRedisDeadline.java`（预算耗尽判定） | 静态工具 | `redis/Deadline.java`（180 行） | 1:1 | **NOT_VERIFIED** | 源码级：实现完整；无载体 ⇒ `remainingNanos()=-1` + `isExhausted()=false` 的语义保留 |

### (9) `response/` · `snowflake/` · `web/`（B 3 → C 2）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `response/Response.java`（`{code,msg,data}` 信封） | 响应信封 | `contract/ApiResponse.java` | 1:1 | **PASS** | 对拍（`ApiResponseTest` 4 例）+ 源码级：键序 `code→msg→data`；**`data:null` 保留**（类级明确禁 `@JsonInclude(NON_NULL)`）⇒ 线格式不变 |
| `snowflake/Snowflake.java`（41/10/12 位段 + 锚点+单调时钟） | 分布式 ID | `snowflake/Snowflake.java` | 1:1 | **PASS** | 对拍（`SnowflakeTest` 6 例）+ 源码级：`EPOCH=1288834974657` / `WORKER_ID_BITS=10` / `SEQUENCE_BITS=12` / `TIMESTAMP_SHIFT=22` / `WORKER_ID_SHIFT=12` **逐项一致**；**时钟回拨免疫**（`anchorEpochNanos` + `monotonicNanos`）保留 |
| `web/ConditionalOnApiSide.java`（`rag.side=api` 条件注解） | api/rpc 分边 | **无对应文件** | — | **INTENTIONALLY_CHANGED** | 映射表 §1.12 已判定：D1 合并 api/rpc ⇒ C 侧不存在「api 侧」维度 ⇒ **不是缺口** |

### (10) `spring/`（B 9 → C 7 个 auto-config）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `spring/HttpMiddlewareFilter.java` | 把 HTTP 中间件挂进 Filter 链 | `autoconfigure/ObservabilityAutoConfiguration.java` 内嵌 `HttpObservationFilter` | N:1 | **NOT_IMPLEMENTED**（部分） | 槽位对齐（+7 语义位）；🔴 499/503/worker 池缺失 ⇒ **DRIFT-COMMON-V04** |
| `spring/ObservabilityProperties.java` | `observability.*` 配置 | `observability/ObservabilityProperties.java` | 1:1 | **PASS** | 源码级：3 个 Duration + env 兜底名一致 |
| `spring/ObservabilityLifecycle.java`（关 `obs-rpc-timeout` / `obs-rpc-worker` 两池） | gRPC 侧池生命周期 | `autoconfigure/ObservabilityAutoConfiguration.java` 的静态 `TIMEOUT_SCHEDULER` | N:1 | **NOT_IMPLEMENTED** | 源码级：C 侧只有 1 个调度器，`obs-rpc-worker` 池无对应物；关闭钩子未实现 |
| `spring/HttpPoolLifecycle.java` | 关 `obs-http-worker` 池 | —（C 侧无 worker 池） | 1:1 | **INTENTIONALLY_CHANGED** | 映射表 §1.12：D4 生态吸收（Tomcat 自管连接池）⇒ **零 Java 代码**，**不是缺口** |
| `spring/RpcServerInterceptorFactory.java`（把 gRPC 拦截器暴露为 Bean） | gRPC 观测 Bean | `autoconfigure/ObservabilityAutoConfiguration.java` 内嵌 `GrpcObservationInterceptor` | N:1 | **PASS** | 对拍（`ObservabilityAutoConfigurationTest` 5 例）；⚠️ **与 B 的关键差异**：B 侧此 Bean **挂不上链**（devh starter 只认 `GrpcServerConfigurer` ⇒ 静默失效）；C 侧 `ProtectionAutoConfiguration` **真调 `server.intercept(...)`** ⇒ **C 优于 B** |
| `spring/LoggerFactoryBean.java` | `Logger` 的 Spring 适配 | `autoconfigure/`（无对应；`GoStyleLogger` 由域侧注入） | N:1 | **NOT_IMPLEMENTED** | 源码级：共享层未提供 `Logger` Bean 工厂 ⇒ 各域需自行装配（域尚为空壳，暂不显现） |
| `spring/SnowflakeBean.java` + `spring/SnowflakeProperties.java` | workerId 解析 + 注入 | `autoconfigure/SnowflakeAutoConfiguration.java` | N:1 | **PASS** | 源码级：`@ConditionalOnMissingBean Snowflake` + 构造器 `public`；⚠️ **落点差异**：B 是域内 7/10 `@Import`，C 是共享层 1 份 ⇒ 需确认无域内副本（当前 0 份） |
| `spring/AuthorityHandlerFactory.java` | 内部端点注册 | `autoconfigure/WebAutoConfiguration.registerAuthorityHandler(...)` | 1:1 | **PASS** | 源码级：`addUrlMappings("/internal/v1/community/facts")` |

### (11) 跨域上收（B `users-common` → C `common/security/`）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `users-common/jwt/JwtUtil.java`（HS256 签发/验签 + secret 补齐 ≥32 字节） | JWT | `security/JwtUtil.java` | 1:1 | **NOT_IMPLEMENTED** | 源码级：**空壳**（仅 TODO javadoc）⇒ **DRIFT-COMMON-V07** |
| `users-common/jwt/BearerTokenExtractor.java` | Bearer 头提取 | `security/BearerTokenExtractor.java` | 1:1 | **NOT_IMPLEMENTED** | 同上 |
| `users-common/cryptx/PasswordUtil.java` | 口令 | `security/PasswordUtil.java` | 1:1 | **NOT_IMPLEMENTED** | 同上 |

---

## 11.3 多文件合并和拆分专项报告

### 11.3.1 N:1 合并（B 多 → C 1）

| 合并点 | B 源文件 | C 承接 | 逐项核对结论 |
|---|---|---|---|
| **HTTP 保护链** | `GoStyleBreakerFilter` + `GoStyleCpuSheddingFilter` + `GoStyleMaxBytesFilter` + `GoStyleMaxConnsFilter` + `GoStyleStatusCapturingResponse` + `GoStyleRouteTemplateResolver` + `GoStyleRouteResolution` + `GoStyleRouteTemplateSource` + `GoStyleProtectionConfiguration` + `GoStyleBreakerRouteTemplateAutoConfiguration` | `autoconfigure/ProtectionAutoConfiguration.java`（+ `protection/` 5 类） | ⚠️ **职责断裂**：B 的 4 层 Filter（MaxConns/Breaker/Shedding/MaxBytes）在 C 侧只剩 **3 层**（MaxConns 缺失）；模板解析被内联 ⇒ `RouteTemplateSource`/`RouteResolution` 成死代码；**唯一承接完整的是 MaxBytes 与 CPU 读数** |
| **gRPC 链** | `GoStyleRpcProtectionChain` + `GoStyleRecoverInterceptor` + `GoStyleStatInterceptor` + `GoStyleStatMetrics` + `GoStylePrometheusInterceptor` + `GoStylePrometheusMetrics` + `GoStylePrometheusSink` + `MicrometerPrometheusSink` + `GoStylePrometheusMeterRegistryBridge` + `GoStyleRpcBreakerInterceptor` + `GoStyleRpcFailurePolicy` + `GoStyleRpcSheddingInterceptor` + `GoStyleSheddingStat` | `autoconfigure/ProtectionAutoConfiguration.java` 的 `compose(List)` + `ObservabilityAutoConfiguration` 的 `GrpcObservationInterceptor` | 🔴 **5 层全缺**（recover/stat/prometheus/breaker/shedding）⇒ **DRIFT-COMMON-V03**。✅ **但合并本身做得对**：`compose(List)` **显式嵌套**（列表第 0 个最外层）+ 只调一次 `server.intercept(...)` ⇒ 不押「最后 add 的最外层」这个易记反的 API 事实 ⇒ **比 B 的 10 处分散挂载更健壮** |
| **社区事实类型** | `Wire.java`（1 文件含 4 wire 类型 + 校验 + sender + 投递循环） | 13 个 `communityfact/` 类 | ✅ **拆分无断裂**：调用链完整（`EventDispatcher` → `EventSender` → `Wire.strictDecode` / `CanonicalHash`）、数据完整传递、异常正确传播（`IOException("trailing JSON")` 保留）、无重复执行、无遗漏执行。⚠️ 初始化/销毁时机未变（无 `@PostConstruct`/`@PreDestroy`） |
| **雪花** | `Snowflake` + `SnowflakeBean` + `SnowflakeProperties` | `snowflake/Snowflake.java` + `autoconfigure/SnowflakeAutoConfiguration.java` | ✅ 无断裂：状态唯一（`STATE` 静态）、workerId first-wins、`nextId()` 前的初始化顺序保留 |
| **Redis** | `GoStyleRedisDeadline*` 4 类 + `GoStyleRedisClientAutoConfiguration` | `redis/` 4 类 | 🔴 **拆分无断裂但整体未接线** ⇒ **DRIFT-COMMON-V01** |
| **日志选项** | `WithUserID` + `WithArticleID` | `logger/LogOption.java` 的 2 个静态工厂 | ✅ 行为一致、**L1 日志契约未破**（选项集合仍 2 个） |
| **Jackson 反序列化器** | B 侧散落 `article-api` / `like-api` / `message-api` / `users-common` | `web/` 5 个 deserializer + `Coercion` | ✅ 域内**零副本**（实测 11 类各 0 份）⇒ 无重复注册风险 |

### 11.3.2 1:N 拆分（C 多承接 1 个 B）

| B 源 | C 承接 | 调用链完整性 | 数据传递 | 异常传播 | 事务范围 | 重复执行 | 遗漏执行 | 初始化/销毁时机 |
|---|---|---|---|---|---|---|---|---|
| `Wire.java` | 13 类 | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `Logger.java` | `GoStyleLogger` + `LogOption` + `LogOptions` + `FatalError` | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `GoStyleProtectionProperties` | `ProtectionProperties`（独立成类，L-15） | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `RpcInterceptor.DeadlineState`（嵌套） | `observability/DeadlineState.java`（提升顶层，L-16） | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `GoStyleOtelConfig` | `ObservabilityProperties` | ⚠️ **3 项丢失**（`protocol`/`sampler`/`endpoint`） | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |

### 11.3.3 共享能力上收（§8.4）

| 上收项 | 原消费方（B） | C 侧覆盖情况 | 结论 |
|---|---|---|---|
| 11 个 Jackson/HTTP 工具类 | 分散在 `article-api`/`like-api`/`message-api`/`users-common` | 统一落 `common/web/`，**域内零副本** | ✅ **无覆盖遗漏** |
| JWT/口令 3 类 | `users-common` | 落 `common/security/`，**但全是空壳** | 🔴 **上收了没实现** ⇒ **DRIFT-COMMON-V07** |
| gRPC 拦截链挂载点 | **B 侧 10 个 rpc 模块各自 1 处** | C 侧共享层 **1 份** + 域内 0 重复 | ✅ **C 优于 B**（B 的 message/user/admin 三域曾静默失效；现已全部覆盖） |
| MP 排序桥 | B 侧 9 份域内副本 | C 侧共享层 1 份 | ✅ 无重复（守门 A6 PASS） |
| `AutoConfiguration.imports` 登记面 | B 侧 8 行 | C 侧 **7 行** | 🔴 **少 2 行 = 2 个 redis auto-config** ⇒ **DRIFT-COMMON-V01** |
| `commonfact` 消费方 | B 侧 like/favorite/comment 三域 | C 侧**尚无消费方**（域为空壳） | ⬜ 待域实现时核 |

### 11.3.4 独立进程合并（§8.5）

B 侧 `service/common` **不是独立可执行进程**（是被 10 个 rpc + 10 个 api 应用的 `scanBasePackages` / `@Import` 消费的无状态 jar）⇒ **本节不适用**。C 侧 starter 化是同一形态的等价演进。

---

## 11.4 框架版本差异报告

| # | 机制 | B（Boot 4.0.8） | C（Boot 3.5.11） | 具体变化 | 影响的执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| F-1 | Jackson 主版本 | Jackson 3（`tools.jackson.*`） | Jackson 2（`com.fasterxml.*` 2.19.4） | API 包名全换；coercion API 形态不同 | 请求体反序列化 + 响应序列化 | **可能**（数字/字符串强转、空值、未知字段、尾随 token） | `web/Coercion` 走 `Jackson2ObjectMapperBuilder#postConfigurer` → `ObjectMapper#coercionConfigFor`（**K-01，不替换 Boot 的 ObjectMapper**） | `CoercionTest`(10) + `JsonBodyConverterTest`(14) + `Float64DeserializerTest`(9) + `MapStringValueDeserializerTest`(7) + `RawStringListDeserializerTest`(12) = **52 例** | **已适配** |
| F-2 | Spring 转换器基类 | `JacksonJsonHttpMessageConverter`（Spring 7 独有） | **Spring 6.2 无此类** ⇒ 改判 `MappingJackson2HttpMessageConverter` | 覆写点签名从 `ResolvableType` 版改 `Type` 版 | 请求体读取 | 否（分支顺序保证 Generic 版即被调用） | `JsonBodyConverter` 的 `canRead`/`canWrite` 改 Type 版三参 | `JsonBodyConverterAutoConfigurationTest`(7) | **已适配（K-02）** |
| F-3 | `Jackson2ObjectMapperBuilder` coercion | Spring 7 有 coercion API | Spring 6.2 **无** | 只能走 `postConfigurer` | Boot `ObjectMapper` 的强转配置 | 否（同一 `MutableCoercionConfig` 语义） | 同 F-1 | 同 F-1 | **已适配（K-01）** |
| F-4 | `LettuceClientOptionsBuilderCustomizer` 包名 | `org.springframework.boot.data.redis.autoconfigure.*` | `org.springframework.boot.autoconfigure.data.redis.*` | `@ConditionalOnClass` 判定依据变了 | Redis 客户端选项定制 | **会**（不改 ⇒ 整份自动配置**静默不生效**） | ✅ **已回改**（`redis/ClientAutoConfiguration.java:13`） | 源码级：包名正确；⚠️ **但该类不在 imports ⇒ 回改了也不加载** ⇒ 被 **DRIFT-COMMON-V01** 掩盖 | **已回改，但当前无效** |
| F-5 | `DataSourceAutoConfiguration` 包名 | `org.springframework.boot.jdbc.autoconfigure.*` | `org.springframework.boot.autoconfigure.jdbc.*` | 排序边的字符串目标变了 | MP 自动配置求值顺序 | 否（不回改 ⇒ **排序边静默失效**，字符串不解析、不报错） | ✅ **已回改** | 源码级：`autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | **已适配** |
| F-6 | `AutoConfiguration.imports` 登记面 | 8 行 | **7 行** | **少 2 行** | starter 能力装载 | **会**（2 个 redis auto-config 永不加载） | ❌ **未适配** | 源码级：diff 两份清单 | 🔴 **DRIFT-COMMON-V01** |
| F-7 | Tomcat | 11.0.24 | **10.1** | 内部类/反射链可能变化 | `GoStyleClientDisconnectProbe` 的 socket 反射写 | **会**（B 的取证全部基于 Tomcat 11） | ⚠️ C 侧该类**尚未实现** ⇒ 暂不适用 | — | **BLOCKED**（实现时须在 10.1 重测） |
| F-8 | devh grpc starter | 3.1.0.RELEASE（grpc 1.63.0） | **同** | 无 | gRPC 拦截链挂载 | 否 | `ProtectionAutoConfiguration` 声明 `GrpcServerConfigurer` 并真调 `server.intercept(...)` | `ProtectionAutoConfigurationWiringTest`(5) | **已适配（A3 PASS）** |
| F-9 | `ResponseBodyAdvice` 收集机制 | Spring 7 | Spring 6.2：`RequestMappingHandlerAdapter#setResponseBodyAdvice` **无 `@Autowired`** ⇒ 不会自动收集 | `ResponseBodyAdvice` bean 需靠 `@RestControllerAdvice` 进 advice 链 | JSON 响应 Content-Type 补 charset | **会**（漏收集 ⇒ Content-Type 无 charset） | `JsonContentTypeAdvice` 同时挂 `@RestControllerAdvice` **且**由 `WebAutoConfiguration` 的 `@Bean` 显式注册 | 源码级：`WebAutoConfiguration.java:115-118` | **已适配** |
| F-10 | `SpringBoot4-Jackson3` 专属 artifact | `spring-boot-data-redis` / `spring-boot-tomcat` | **不存在** | 依赖坐标需换 | Redis / Tomcat 装配 | 否 | C 用 `spring-boot-starter-data-redis` / starter 自带 tomcat | 源码级：`common/pom.xml` | **已适配（D14）** |
| F-11 | Kafka 自动配置模块 | Boot 4 拆成 `spring-boot-kafka` | Boot 3.5 在 `spring-boot-autoconfigure` 内 | **Boot 3.5 无此风险** | `@KafkaListener` 消费 | 否 | C 用 `org.springframework.kafka:spring-kafka`（**零排除**） | `common/pom.xml` + AGENTS.md §3.3.3 订正 | **已适配（D14）** |
| F-12 | OTel / tracing | `GlobalOpenTelemetry` + OTLP exporter + `parentBased(traceIdRatio)` | C 侧**无** `GlobalOpenTelemetry.set`；`micrometer-tracing-bridge-otel` **离线不可得** ⇒ `Span.current()` 是 no-op | span 直写被丢弃 | 观测埋点 / `traceparent` 响应头 | **会**（span 属性丢失；`Traceparent` 头可能缺失） | 缺 bridge ⇒ OTel 直写无效 | 源码级 + `ObservabilityAutoConfigurationTest`(5) 只验证 Observation 侧 | **FRAMEWORK_DIFFERENCE（D-03 已登记）** |
| F-13 | Resilience4j | **B 无此依赖**（手写 googlebreaker 移植） | `resilience4j-spring-boot3` | 熔断实现换实现 | HTTP 熔断 | **会**（拒绝语义类型都不同） | D4 生态优先 | `RouteIsolationFilterTest`(4) 只覆盖 key + 判据 | **INTENTIONALLY_CHANGED（D-02 已登记，但差异比登记更大 ⇒ DRIFT-COMMON-V09）** |
| F-14 | Actuator | B 侧 `service/common` **无** actuator 依赖 | C 侧有 `spring-boot-starter-actuator` | 降载观测面换载体 | CPU 降载 | 否 | `CpuThresholdHealthIndicator` | 源码级 | **已适配（D4）** |
| F-15 | `@ConfigurationProperties` 注册 | B 侧 `GoStyleProtectionConfiguration` 用 `@EnableConfigurationProperties`（靠扫描） | C 侧 `ProtectionAutoConfiguration` 用 `@EnableConfigurationProperties(ProtectionProperties.class)`（靠 imports） | 注册面不同 | 保护层配置 | 否 | D3 starter 化 | `ProtectionProperties` 12 项默认值逐条核对一致 | **已适配** |

---

## 11.5 语义漂移清单

> **状态**：🔴 = 必须在填域实现前处置；🟠 = 登记后放行；⚪ = 已登记沿用。
> **纪律**（§13）：本报告**只记录证据与修复建议**，**未改任何代码**。

### 🔴 DRIFT-COMMON-V01｜`redis/` 两个自动配置不在 `AutoConfiguration.imports` ⇒ 两项能力静默失效

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/redis/ClientAutoConfiguration.java`、`redis/DeadlineAutoConfiguration.java` |
| **B 源** | `service/common/redis/GoStyleRedisClientAutoConfiguration.java`（imports **第 5 行**）· `GoStyleRedisDeadlineAutoConfiguration.java`（imports **第 6 行**） |
| **B 行为** | ① Lettuce 断连 ⇒ `ClientOptions.DisconnectedBehavior.REJECT_COMMANDS`（对齐 Go「Redis 失败 ⇒ 尽快失败 ⇒ 上层 fail-open」）；② `BeanPostProcessor` 把各 `RedisTemplate` 的 connection factory 换成预算感知包装版 ⇒ **请求预算耗尽 ⇒ 不再下发 Redis 命令** |
| **C 行为** | 两类**实现完整**（146 行 / 98 行，Boot 3.5 包名已回改），但：① **不在** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；② **全仓零引用**（grep 除自身外 0 命中）；③ 域侧**无 `@Import`**；④ 域应用**无 `@ComponentScan("com.rag.common")`** ⇒ **`@AutoConfiguration` 永不加载 ⇒ 静默失效、零报错** |
| **差异原因** | 批次 1 落地时把类放进了 `redis/` 包，但只把 `autoconfigure/` 目录下的 7 个类登记进了 imports |
| **影响范围** | **全部 10 个域**的 Redis 访问：断连拒单语义丢失（fail-fast → 默认 `REJECT_COMMANDS` 之外的行为）、请求预算耗尽后仍下发 Redis 命令 ⇒ **超时预算失效、级联放大** |
| **严重程度** | **🔴 严重**（静默失效 + 10 域受影响 + 与 Go 语义直接相反） |
| **修复建议** | ① 把两行加进 `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；② **同时把守门 `AutoConfigurationImportsTest` 的扫描范围从 `autoconfigure/` 扩到全模块**（否则同类问题会再犯）—— 当前该测试只扫 `autoconfigure/` 目录 ⇒ **这就是本次漏检的原因** |
| **验证方法** | 新增「启动一个带 `RedisTemplate` 的上下文 ⇒ `template.getConnectionFactory()` 是 `DeadlineConnectionFactory`」的真触发测试；断言 `LettuceClientOptionsBuilderCustomizer` Bean 存在且 `DisconnectedBehavior == REJECT_COMMANDS` |

### 🔴 DRIFT-COMMON-V02｜SQL 超时链路两个 MyBatis 拦截器零注册 ⇒ 整条链不生效

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/persistence/DeadlineQueryTimeoutInjector.java`（321 行）、`SqlTimeoutStatusTranslator.java`（154 行） |
| **B 源** | 同名两文件；B 侧**不在 imports**，而是由 **5 个 rpc 应用的 `@Bean`** 逐个注册（`FollowRpcApplication:90` / `MessageRpcApplication:85` / `LikeRpcApplication:94` / `ArticleRpcApplication:88` / `UserRpcApplication:67`） |
| **B 行为** | ① `StatementHandler.prepare` 拦截 ⇒ 按线程 `DeadlineState` 设 `setQueryTimeoutMs` ⇒ **取消时刻 == deadline 时刻**（对齐 Go 的 ctx-aware 查询）；② `Executor` 拦截 ⇒ pg `57014` ⇒ gRPC `DEADLINE_EXCEEDED` + description `"sql statement canceled at rpc deadline"` ⇒ 域侧落业务码 **1015**（而非 DB 故障 5001）。**两者必须成对** |
| **C 行为** | 两个类**实现完整**（含 `Wrapper.unwrap` 剥 HikariCP 池代理 ≤5 跳、毫秒精度 → 秒级向上取整回落、cause 链遍历），但**全仓零注册点**（不在 imports、不在任何 `@Bean`、MP 自动配置不会自动收 `Interceptor`）⇒ **SQL 不带超时、57014 不翻译** |
| **差异原因** | B 侧的注册义务在**域内**（域已实现）；C 侧域还是空壳 ⇒ 注册点无人写；而共享层**没有**承接这个注册义务（starter 化后应由 auto-config 承担） |
| **影响范围** | 全部走 DB 的域：SQL 超时**不生效**（长查询不被取消）、pg 取消被当 DB 故障上报（5001 而非 1015） |
| **严重程度** | **🔴 严重**（两处行为同时丢失 + 超时预算链断） |
| **修复建议** | 在 `common/autoconfigure/` 新增（或并入 `MybatisPlusOrderingBridgeAutoConfiguration`）一个 auto-config，用 `@Bean` 产出两个 `Interceptor`（MP 会自动 `setPlugins`）；**不要**放回域内（starter 化后域内注册会回到 B 的老路） |
| **验证方法** | 真触发测试：带 `DataSource` 的上下文里断言 `SqlSessionFactory` 的 `plugins` 含这两个拦截器；再补一个「deadline 已过 ⇒ `SQLTimeoutException` + SQLState `57014`」的行为测试 |

### 🔴 DRIFT-COMMON-V03｜gRPC 服务端保护链 5 层全缺（recover / stat / prometheus / breaker / shedding）

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 的 `compose(List)` |
| **B 源** | `service/common/protection/GoStyleRpcProtectionChain.java`（链序唯一定义点）+ `GoStyleRecoverInterceptor` / `GoStyleStatInterceptor` / `GoStyleStatMetrics` / `GoStylePrometheusInterceptor` / `GoStylePrometheusMetrics` / `GoStylePrometheusSink` / `MicrometerPrometheusSink` / `GoStylePrometheusMeterRegistryBridge` / `GoStyleRpcBreakerInterceptor` / `GoStyleRpcFailurePolicy` / `GoStyleRpcSheddingInterceptor` / `GoStyleSheddingStat` |
| **B 行为** | 链序（外→内）**recover → stat → prometheus → breaker → shedding → business(观测)**；① **recover**：`catch(Throwable)` ⇒ `Status.INTERNAL` + description 前缀 `"panic: "`（**wire 可见**）；② **stat**：每 60s 一行聚合日志；③ **prometheus**：两个 meter（`rpc_server_requests_duration_ms{method}` 12 桶 DistributionSummary + `rpc_server_requests_code{method,code}`）；④ **breaker**：隔离 key = `getFullMethodName()`（`/pkg.Svc/Method`）+ **6 码失败白名单**（`DEADLINE_EXCEEDED`/`INTERNAL`/`UNAVAILABLE`/`DATA_LOSS`/`UNIMPLEMENTED`/`RESOURCE_EXHAUSTED`）；⑤ **shedding**：`RESOURCE_EXHAUSTED` + description `"service overloaded"` |
| **C 行为** | 链上**只有 1 层**（业务观测 `GrpcObservationInterceptor`）⇒ panic 不被 recover（客户端拿到 grpc-java 默认的 `UNKNOWN` + **description 为 null**）⇒ 与 Go 的 `codes.Unknown` + `err.Error()` **不同**；无熔断、无降载、无 stat、无 prometheus |
| **差异原因** | 批 3 只搬了 HTTP 侧的 D4 生态重写，gRPC 侧的保护/观测层未搬（已知「gRPC 侧 recover / breaker / shedding 拦截器未搬」）⇒ **但从未被登记进 L3 降级清单** |
| **影响范围** | 全部 10 个域的 gRPC 入口：panic 语义漂移、无熔断保护、无降载保护、无 RPC 指标面、无 RPC 统计日志 |
| **严重程度** | **🔴 严重**（panic 的 description 是 wire 级差异；保护能力整体缺席） |
| **修复建议** | ① **先把这条登记进 `docs/L3-降级清单.md`**（当前是「未登记的缺口」）；② 实现时按 B 的链序在 `compose(List)` 里**显式**列出 5 层（`compose` 已是显式嵌套 ⇒ 加层不需改机制）；③ 熔断沿用 resilience4j，但**成败判据必须换成 6 码白名单**（HTTP 的 `<500` 判据不能套到 gRPC）；④ recover 的 `"panic: "` 前缀是 **L1 wire 契约** ⇒ 必须保留 |
| **验证方法** | ① 造一个抛 `RuntimeException("boom")` 的 handler ⇒ 断言客户端收到 `INTERNAL` 且 description **以 `"panic: "` 开头**；② 断言 `compose(props).allLayerNames()` = `[recover, stat, prometheus, breaker, shedding]` |

### 🔴 DRIFT-COMMON-V04｜HTTP 超时 / 客户端断连中间件缺失（503 + 499）

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` |
| **B 源** | `service/common/observability/HttpMiddleware.java`（58 KB）+ `spring/HttpMiddlewareFilter.java` |
| **B 行为** | ① 静态 worker 池（`MAX_HTTP_WORKERS=512`，线程名 `obs-http-worker`）上跑业务；② `observability.http-timeout` 超时 ⇒ **503** + body `Request Timeout\n`（Content-Length 16）+ `sea.error.kind=timeout`；③ 客户端断连（`EOFException` / 类名含 `ClientAbort`）⇒ **499**（不是 500）；④ 异常从 worker 逃出 ⇒ **原样重抛**（`ServletException`/`IOException`/`RuntimeException`/`Error` 各按原类型）不转信封 |
| **C 行为** | 全仓 grep `499` / `EOFException` / `ClientAbort` / `SC_CLIENT_CLOSED` **零命中** ⇒ **无超时、无断连、无 worker 池、无异常重抛语义** ⇒ 业务直接在容器线程跑 |
| **差异原因** | Spring 无 499 概念；HTTP 超时中间件整块未搬（已知「HTTP 超时中间件（503 / 499）未实现 ⇒ 登记为 L3」⇒ **已登记**） |
| **影响范围** | HTTP 入口：慢请求不超时、客户端断连记 500 而非 499、异常传播路径不同 |
| **严重程度** | **🔴 严重**（但**已登记**为 L3 ⇒ 本条是「确认登记」而非新增） |
| **修复建议** | 确认 L3 登记条目在 `docs/L3-降级清单.md` 里可检索；实现时 499 需自造（Tomcat 无此状态码 ⇒ 需确认容器是否允许自定义 4xx） |
| **验证方法** | 真 Tomcat：`observability.http-timeout=100ms` + 慢端点 ⇒ 断言 503 + body `Request Timeout\n`；断连场景需真实 socket |

### 🔴 DRIFT-COMMON-V05｜HTTP 熔断「模板取不到」的回落语义与 B 相反

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 的 `RouteIsolationFilter.isolationKeyOf` / `routeTemplateOf` |
| **B 源** | `service/common/protection/GoStyleBreakerFilter.java`（`resolveRoute` + `routeKey`）· `GoStyleRouteTemplateResolver.java`（三态） |
| **B 行为** | 三态：`TEMPLATE` ⇒ 按模板分桶；**`NOT_MATCHED`（404/405）⇒ 完全跳过 breaker**（对齐 Go）；**`UNKNOWN`（mapping 列表为空 / 抛异常 / handler 非 `HandlerMethod` / 拿不到 pattern）⇒ 回落按 raw URI 分桶**（`GET:///article/1`）⇒ **熔断保护仍生效**（B 的注释明写「绝不静默关掉熔断」） |
| **C 行为** | 两态：`isolationKeyOf` 返回 `null` ⇒ **`chain.doFilter` 放行** ⇒ **熔断完全不生效**。⚠️ **C 的 javadoc 自称「绝不静默关掉熔断保护」，与实现相反** |
| **差异原因** | C 侧把 B 的三态压成「拿到 / 拿不到」，把 `UNKNOWN` 误并入「放行」 |
| **影响范围** | 路由模板解析失败的所有场景（容器初始化早期、`HandlerMapping` 非 `RequestMappingHandlerMapping`、handler 非 `HandlerMethod`）⇒ **熔断静默消失** |
| **严重程度** | **🔴 严重**（保护能力的静默消失 + 注释与实现自相矛盾） |
| **修复建议** | 恢复三态：`NOT_MATCHED` ⇒ 跳过 breaker；`UNKNOWN` ⇒ **回落 raw URI 分桶**（与 B 一致）。⚠️ 修正 javadoc 那句自相矛盾的表述 |
| **验证方法** | 构造一个 `RequestMappingHandlerMapping.getHandler` 抛异常 ⇒ 断言请求仍被 breaker 计数（而非直接放行） |

### 🔴 DRIFT-COMMON-V06｜三个「窄接口」是空类且零引用；模板解析职责断裂

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/observability/ClientDisconnectProbe.java` · `common/src/main/java/com/rag/common/protection/RouteResolution.java` · `common/src/main/java/com/rag/common/protection/RouteTemplateSource.java` |
| **B 源** | `GoStyleClientDisconnectProbe.java`（探针，4 态 + 反射链）· `GoStyleRouteResolution.java`（三态 record）· `GoStyleRouteTemplateSource.java`（`@FunctionalInterface` 窄接口） |
| **B 行为** | 三个类**各有实质内容**：探针写 `HTTP/1.1 103 Early Hints\r\n\r\n` 探针（4 态 `ALIVE`/`DISCONNECTED`/`UNAVAILABLE`/`BUDGET_EXHAUSTED`）；三态 record（`Kind{TEMPLATE,NOT_MATCHED,UNKNOWN}`）；窄接口让 Filter 不直接依赖 spring-webmvc |
| **C 行为** | 三个类**类体为空**（只有 javadoc）⇒ **全仓零引用**（grep 除自身外 0 命中）⇒ **死代码**。且 `RouteIsolationFilter` **绕过** `RouteTemplateSource` 直接注入 `RequestMappingHandlerMapping` ⇒ **映射表 §1.12 声称的「路由模板解析不能省这条前提已被载体兑现」不成立** |
| **差异原因** | 批 1 建了接口占位，批 3 的 D4 重写走了内联实现 ⇒ 接口未被消费 |
| **影响范围** | ① `ClientDisconnectProbe` 能力缺席（与 **DRIFT-COMMON-V04** 同源）；② `RouteTemplateSource`/`RouteResolution` 是死代码 ⇒ 映射表 §1.12 登记表与实际不符；③ 与 **DRIFT-COMMON-V05** 叠加（正因为绕过了窄接口，三态能力一并丢失） |
| **严重程度** | **🔴 严重**（登记表与实现不符 + 职责断裂） |
| **修复建议** | ① 修 **DRIFT-COMMON-V05** 时让 `RouteIsolationFilter` **经由** `RouteTemplateSource` 抽象取模板 ⇒ 一举恢复三态与窄接口的职责；② `RouteResolution` 落成三态值对象；③ `ClientDisconnectProbe` 要么实现（**注意 F-7：Tomcat 11→10.1 的反射链未验证**），要么**从 `common/` 移除并在映射表登记为「D4 生态吸收 / 暂不实现」** —— 现在这个「空类 + 映射表标 ✅ 已落地」的中间态最糟 |
| **验证方法** | 修完后：断言 `RouteTemplateSource` 有 1 个实现 Bean；断言三态可区分 |

### 🟠 DRIFT-COMMON-V07｜空壳类实际 6 类，文档口径 3 类；`common/security/` 3 类上收了未实现

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/security/{JwtUtil,BearerTokenExtractor,PasswordUtil}.java`（TODO 空壳）+ 上文的 3 个窄接口 |
| **B 源** | `service/users/users-common/jwt/JwtUtil.java`（HS256 + secret 补齐 ≥32 字节）· `BearerTokenExtractor.java` · `cryptx/PasswordUtil.java` |
| **B 行为** | `JwtUtil.getToken(secretKey, ...)` 用 `SecretKeySpec(padToHmacKeySize(secretKey), "HmacSHA256")` + `SignatureAlgorithm.HS256`；secret 补齐到 ≥32 字节；**注意 `jjwt` 的 `SignatureAlgorithm` 是 0.x API**（0.12+ 改为 `Jwts.SIG.HS256`）⇒ C 侧实现时须确认版本 |
| **C 行为** | 3 类**全是空壳**（仅 TODO javadoc）⇒ **上收了但未实现**。⚠️ `common/pom.xml` 已引入 `jjwt-api`/`jjwt-impl`/`jjwt-jackson` ⇒ **依赖已到位、代码未写** |
| **差异原因** | 上收（§8.4）只搬了位置，未搬实现（与 `common/` 整体「68/71 已落地」的口径冲突） |
| **影响范围** | `AGENTS.md` §0 / §2.3 与 `docs/README.md` 的「**71 类中 68 类已落地 · 3 类空壳**」⇒ **实测是 65 已落地 · 6 空壳**（空壳 = security 3 + 窄接口 3）；JWT 能力缺席 ⇒ 10 个域的鉴权都无载体 |
| **严重程度** | **🟠 中**（能力缺席是已知的「域尚未实现」，但**文档口径错误**会误导后续判断） |
| **修复建议** | ① **改口径**：`AGENTS.md` §0/§2.3、`docs/README.md` 状态速查、`docs/Mapping_Table/common.md` §1.1~§1.11 的落点数 ⇒ 统一改成「**71 类中 65 已落地 · 6 空壳**」；② `ClientDisconnectProbe`/`RouteResolution`/`RouteTemplateSource` 若短期不实现 ⇒ 在映射表把 ✅ 改成 ⬜（§2.1 要求「映射表有记录、C 没实现」必须登记）；③ JWT 3 类随域实现推进 |
| **验证方法** | 口径核对用 `find common/src/main -name '*.java' -exec sh -c '[ $(wc -l < "$1") -le 12 ] && echo "$1"' _ {} \;` 逐条列（**当前 6 条**） |

### 🟠 DRIFT-COMMON-V08｜映射表 §7.4 #6 的 B 列实况已过时

| 项 | 内容 |
|---|---|
| **位置** | `docs/Mapping_Table/common.md` §7.4 第 6 行 |
| **映射表原文** | 「B：RAG 侧 10 个 rpc 模块**只有 6 个**声明了 `GrpcServerConfigurer`（like/comment/follow/favorite/task/hot）⇒ **message / user / admin 三个域的拦截链静默失效**」 |
| **实测** | `grep -rln "GrpcServerConfigurer" service/*/*/src/main` ⇒ **10/10 全部有**（article / comment / favorite / follow / hot / like / message / task / users-admin / users-user）⇒ **RAG 后来补齐了**，该登记已过时 |
| **影响** | 该条的「影响」结论（3 个域静默失效）**不再成立** ⇒ C 侧「共享层 1 份覆盖 10 域」相对 B 的优势，从「修复 3 个域的缺口」降级为「消除 10 处分散挂载」⇒ **结论方向不变但论据须改** |
| **严重程度** | **🟠 中**（历史资料与实况脱节；`verification.md` §2.1 明确要求「映射表中的历史路径必须检查是否仍然有效」） |
| **修复建议** | 映射表**本次未改**（§13 禁止修改 Mapping_Table）⇒ **仅在此登记**，由映射表 owner 更新 |
| **验证方法** | `grep -rln "GrpcServerConfigurer" --include=*.java service/ \| grep -v test` |

### 🟠 DRIFT-COMMON-V09｜HTTP 熔断实现换生态 ⇒ 拒绝语义**类型**都不同（比 D-02 登记的更重）

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `RouteIsolationFilter`（resilience4j） |
| **B 源** | `service/common/protection/GoStyleBreaker.java`（go-zero googlebreaker 的**逐行复刻**） |
| **B 行为** | **概率性拒绝 + 无三态**：40 桶 × 250ms（窗口 10s，但 `history()` **跳过当前桶** ⇒ 实际 39 桶 ≈ **9.75s**）；`dropRatio = (total-5-weightedAccepts)/(total+1)`；`rand() < dropRatio` ⇒ 拒绝；**`FORCE_PASS_DURATION_MS=1000`** ⇒ 最长拒绝 1s 后**强制放行一个探测请求**（保底）；`markDrop()` 计入 total 但不计入 accepts |
| **C 行为** | resilience4j `CircuitBreaker`：滑动窗口**计数** + **打开/半开/关闭三态** + `tryAcquirePermission()` |
| **差异原因** | D4 生态优先（Go 的熔断是框架内置，RAG 手写复刻是补差距） |
| **影响范围** | HTTP 熔断：拒绝**概率性 vs 计数性**、**有无三态**、**有无 force-pass 保底**、**窗口 9.75s vs resilience4j 默认 100s 计数窗口** |
| **严重程度** | **🟠 中**（**D-02 已登记「阈值参数不等价」，但实测差异不止参数 —— 语义类型都不同** ⇒ 需把 D-02 的表述扩写） |
| **修复建议** | ① 扩写 `docs/L3-降级清单.md` **D-02** 条目：把「阈值参数不等价」改成「**拒绝机制类型不等价**（概率性+保底放行 vs 计数+三态）」；② 显式配置 resilience4j 的窗口/阈值，使「连续 N 次失败即开」与 Go 的行为差距**可测量**；③ 对拍脚本**显式排除**熔断差异（映射表 §1.13 已写这条纪律 ⇒ 照做） |
| **验证方法** | 固定随机源的 B 侧行为（`random=0.0` ⇒ 必拒绝）对照 C 侧同场景 ⇒ 记录**具体差距**，不追求一致 |

### 🟠 DRIFT-COMMON-V10｜降载统计日志缺席（CPU 判据那一半已登记 D-04，但日志那半没登记）

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/protection/CpuThresholdHealthIndicator.java` |
| **B 源** | `service/common/protection/GoStyleSheddingStat.java`（降载三计数 + 每分钟一行日志）· `service/common/observability/GoStyleStatMetrics.java`（RPC 耗时聚合 + 每 60s 一行日志） |
| **B 行为** | ① 降载日志行逐字：`"(%s) %s [1m], cpu: %d, total: %d, pass: %d, drop: %d"`，tag = `drop==0 ? "shedding_stat" : "shedding_stat_drop"`，**先 reset 再打**；② RPC 统计日志行逐字：`"(name) - qps: %.1f/s, drops: %d, avg time: %.1fms, med: %.1fms, 90th: %.1fms, 99th: %.1fms, 99.9th: %.1fms"`，线程名 `go-style-stat-metrics`，且含 **3 个刻意的 Go 对齐怪癖**（avg 先整除再平均 / median = 第 `size>>1` 大 / 样本数 1 时 median/90/99/99.9 全等于最大值） |
| **C 行为** | 两个统计面**都没有** ⇒ 每分钟的降载统计行与 RPC 统计行**不产生** |
| **差异原因** | 「每分钟一行日志」是 Go `core/load/sheddingstat.go` 与 `core/stat` 的**行为输出**（不是可观测性装饰）⇒ 抄写时只搬了判据没搬统计面 |
| **影响范围** | 运维可观察面：降载次数与 RPC 耗时分位数**无日志可查** ⇒ 线上无法判断是否触发过降载 |
| **严重程度** | **🟠 中**（⚠️ **D-04 只登记了「CPU 判据那一半」，日志这半从未登记** ⇒ 属未登记缺口） |
| **修复建议** | 登记进 `docs/L3-降级清单.md`（**新条目**，不要挂 D-04 —— D-04 是判据缺口，本条是统计面缺口）；实现时**逐字**对齐日志格式串与线程名（`go-style-shedding-stat` / `go-style-stat-metrics`）；⚠️ **那 3 个聚合怪癖是刻意的 Go 对齐，不是 bug** ⇒ 「顺手改成直觉语义」= 漂移 |
| **验证方法** | 日志捕获测试：断言行格式逐字 + `drop` 从 0 变 1 后 tag 切换 + 报告后计数归零 |

### ⚪ DRIFT-COMMON-V11｜`common/` 内 `communityfact/` 的**域归属**未裁决（AGENTS.md §2.3 已挂 🔴）

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/communityfact/`（13 类，其中 6 个**带域归属**的行为类：`Dispatcher`/`EventDispatcher`/`EventSender`/`ProcessLogger`/`ProcessMetadata`/`AuthorityHandler`） |
| **B 源** | 同名 4 类在 `service/common/communityfact/`，**但消费方只有 like + comment 两域**（favorite 在 Go 侧是**平行实现**、不 import 共享层） |
| **差异原因** | C 侧按「三域共享」设计 ⇒ 会改变 favorite 的 wire 行为（Go 的 favorite 走自己的 sender）⇒ **属 L1/L2 变更，不是形态调整** |
| **严重程度** | **⚪ 已登记**（映射表 §7.4 #1 + `AGENTS.md` §2.3「🔴 待裁决」⇒ **本次不重复提请**） |
| **修复建议** | 等映射表 owner 拍板；favorite 的 `fact_delivery` 行为在拍板前**不要**接共享层 |
| **验证方法** | 拍板后逐条对照 Go 的 favorite sender |

### ⚪ DRIFT-COMMON-V12｜`GrpcStatusMapper` 与 `GrpcExceptionMapperAutoConfiguration` 职责重叠（映射表 §7.4 #2）

C 侧同时存在 `web/GrpcStatusMapper.java` 与 `autoconfigure/GrpcExceptionMapperAutoConfiguration.java` ⇒ 两处都做「gRPC code → 业务码」⇒ 装配顺序不同 ⇒ 同一异常可能映射出**两个不同 code**。
**状态**：**⚪ 已登记**（映射表 §7.4 #2「建议删 `web/GrpcStatusMapper.java`」）⇒ 本次只落不删（AGENTS.md 明令「不擅自删类」）。

### ⚪ DRIFT-COMMON-V13｜gRPC 业务码承载方式变更（**D-05 已登记**）

Go = **status code 本身就是业务码** + description 业务文案；grpc-java 的 `Status.fromCodeValue(int)` 对未知码回落 `UNKNOWN` ⇒ **承载不了任意数值业务码** ⇒ C 侧走 **trailer `sea-biz-code`**。
**状态**：**⚪ 已登记**（`docs/L3-降级清单.md` **D-05**）⇒ 本次实测确认该判断成立（`GrpcExceptionMapperAutoConfigurationTest` 5 例覆盖 trailer 业务码与 description 逐字）。

---

## 11.6 完成度统计

> **口径**：按**职责**统计，**不按文件数或行数**（§11.6 明令）。

### 按状态（88 个 B 源类的承接结果）

| 状态 | 数量 | 说明 |
|---|---|---|
| **PASS** | **46** | 职责已完整承接 + 行为一致 + 有证据（源码级/对拍） |
| **INTENTIONALLY_CHANGED** | **4** | 有明确批准依据（D3 starter 化 / D4 生态吸收 / D1 合并 api-rpc / D2+L-13+L-15+L-16 落点决策） |
| **NOT_IMPLEMENTED** | **19** | C 尚未实现对应职责（gRPC 保护链 5 层 + HTTP 超时/断连 + stat/shedding 统计面 + prometheus sink + OTel agent + MQ trace + 客户端熔断/deadline + 3 个窄接口 + JWT 3 类 + MaxConns + 降载双条件） |
| **NOT_VERIFIED** | **7** | 实现存在但无充分证据（Redis 2 个下游类因上游未加载而无法验证；`JsonbTypeHandler`/`JsonTextTypeHandler` 跨域上收未审；gRPC 指标装配点为空；`GoStyleOtelConfig` 3 项丢失） |
| **FRAMEWORK_DIFFERENCE** | **2** | `TraceparentFilter`（Tomcat/OTel 链路）、`GoStyleOtelAutoConfiguration`（D-03 已登记） |
| **FAIL** | **5** | redis 两项静默失效（V01）+ SQL 超时链未挂载（V02）+ gRPC 链缺 5 层（V03）+ 熔断回落语义相反（V05） |
| **BLOCKED** | **0** | 无（Tomcat 10.1 反射链一项因 C 侧未实现而**不适用**，实现时才会 BLOCKED） |
| **合计** | **83**（+ 5 条「1:N 承接但部分未实现」按主职责归类） | 覆盖 B 侧 **88/88** 个主类 |

### 按「能力面」看（更能反映真实完成度）

| 能力面 | 状态 | 证据 |
|---|---|---|
| 社区事实协议（L1 wire + JCS 指纹 + 投递 + 端点） | 🟢 **已承接** | 23 个 JSON tag 与 Go 逐字一致；JCS **同库**（`org.erdtman.jcs`）⇒ 幂等指纹不会漂；投递节奏 3 线程名/批次 16/10s/1s 逐字一致 |
| 响应信封 + 错误形状（404/405/400/413/503） | 🟢 **已承接** | `text/plain; charset=utf-8` + nosniff + body 尾 `\n` 逐字一致；`data:null` 保留 |
| 雪花 ID | 🟢 **已承接** | 位段 41/10/12 + EPOCH 逐项一致；**时钟回拨免疫**保留 |
| 保护层配置 + 默认值 | 🟢 **已承接** | `rag.protection` 12 项默认值逐条一致 |
| Filter 槽位 | 🟢 **已承接** | +2/+3/+5/+6/+8 与 B 一致 |
| 观测常量与判定顺序 | 🟢 **已承接** | 7 key / 3 kind / 500ms / 严格 `>` / 业务码优先 / `http.route`=原始 URI |
| HTTP 熔断（key + 判据） | 🟡 **部分** | key 与判据一致；**回落语义相反**（V05）+ **机制类型不同**（V09） |
| HTTP 熔断（resilience4j 接线） | 🟢 **已承接** | `compose` 显式嵌套 + 单次 `server.intercept(...)` ⇒ 比 B 的 10 处分散更健壮 |
| CPU 降载 | 🟡 **部分** | CPU 一半（D-04 已登记）；**统计日志缺席**（V10 未登记） |
| MaxBytes | 🟢 **已承接** | 裸 413 + 空 body + 无 Content-Type + 只判 Content-Length |
| MaxConns | 🔴 **未实现** | L-5 待办（yml 搬运已叫停） |
| Redis 断连收口 | 🔴 **失效** | V01 |
| Redis 预算感知 | 🔴 **失效** | V01 |
| SQL 超时注入 + 57014 翻译 | 🔴 **失效** | V02 |
| gRPC 保护链（recover/stat/prometheus/breaker/shedding） | 🔴 **未实现** | V03（**未登记**） |
| gRPC 客户端熔断 + 出向 deadline | 🔴 **未实现** | V03 同源 |
| HTTP 超时（503）+ 断连（499） | 🔴 **未实现** | V04（**已登记** L3） |
| Prometheus 写入面（2 个 meter） | 🔴 **未实现** | V03 |
| RPC 统计面（每分钟日志） | 🔴 **未实现** | V03 + V10 |
| OTel 全局装配（provider/exporter/sampler/传播器） | 🔴 **未实现** | D-03 已登记 |
| MQ 消费端 trace | 🔴 **未实现** | V03 同源 |
| JWT / 口令 | 🔴 **空壳** | V07 |
| 客户端断连探针 | 🔴 **空壳** | V06 + F-7（Tomcat 10.1 未验证） |

### 测试覆盖对照

| 指标 | B（`service/common`） | C（`common/`） | 比率 |
|---|---|---|---|
| 测试类 | **93** | **14** | 15% |
| 测试例 | **488**（离线实测全绿） | **96** | 20% |
| 有测试的 B 能力面 | 12 个包全覆盖 | **7 个能力面**（信封/雪花/日志/Jackson 5 类/观测/熔断/gRPC 适配器） | — |

> ⚠️ **测试覆盖是本次最大的量化缺口**：B 侧 488 例里有 **392 例**在 C 侧**没有对应测试**（含 communityfact 41 例、protection 约 140 例、observability 约 130 例、redis 19 例、persistence 24 例、spring 34 例）。
> ⇒ C 侧目前的 96 例**证明了「已实现的部分是对的」**，**不能证明「没实现的部分不需要」**。

---

## §十四 最终必须回答的问题

| # | 问题 | 结论 |
|---|---|---|
| 1 | **C 是否完整承接 B 的全部实际职责？** | ❌ **否**。88 个 B 源类中 **19 个 NOT_IMPLEMENTED** + **5 个 FAIL**。缺的主要是：**gRPC 保护链 5 层**、**HTTP 超时/断连**、**Redis 两项**（实现了但没接线）、**SQL 超时链**（实现了但没接线）、**Prometheus 写入面**、**每分钟统计面**、**OTel 全局装配**、**MQ trace**、**JWT/口令** |
| 2 | **Mapping_Table 的 RAG→RAG-V1 映射是否与当前真实代码一致？** | ⚠️ **部分不一致**。§7.4 #6 的 B 列实况**已过时**（B 现为 10/10 都有 `GrpcServerConfigurer`）⇒ **DRIFT-COMMON-V08**；§1.12 把 `ClientDisconnectProbe`/`RouteResolution`/`RouteTemplateSource` 标为「✅ 已落地」但**实际是空类** ⇒ **DRIFT-COMMON-V06**；§1.11 的 `web/` 落点数与 §2.3 的「68 已落地」口径需按 65/6 校正 ⇒ **DRIFT-COMMON-V07** |
| 3 | **File_Tree 规定的目标结构是否正确落地？** | ✅ **基本落地**。`common/` 12 个包与 File_Tree 一致；`gen-file-tree.py` 幂等；守门 `check-docs-vs-tree.py` EXIT=0。⚠️ 唯一例外：**3 个空类占位**（窄接口）破坏了「结构已落地」的语义 |
| 4 | **多文件合并是否造成职责、分支或异常路径遗漏？** | ⚠️ **是**。① gRPC 链合并 ⇒ **5 层全缺**（V03）；② HTTP 保护链合并 ⇒ MaxConns 层缺 + **模板解析三态压成两态且回落语义相反**（V05）+ **窄接口被绕过**（V06）。✅ 社区事实 1→13 拆分**无断裂**（调用链/数据/异常/事务/重复/遗漏/时机七项全过） |
| 5 | **文件拆分是否造成调用链、数据传递或事务边界断裂？** | ✅ **否**（就已拆分的部分）。`Wire`→13 类、`Logger`→4 类、`Snowflake`→3 类、`GoStyleOtelConfig`→`ObservabilityProperties` 均链路完整。⚠️ 唯一异常：`GoStyleOtelConfig` 的 `protocol`/`sampler`/`endpoint` **3 项丢失** |
| 6 | **API + RPC 合并是否改变原有入口和执行行为？** | ⚠️ **本次范围外**（只审 `common/`）。但已取得一条相关证据：**gRPC 拦截链挂载点上收到共享层 1 份后，10 个域全覆盖**，而 B 侧是 10 处分散（且历史上曾有 3 域漏挂）⇒ **合并方向是改善，不是破坏**。⚠️ 但 **gRPC 链只挂 1 层** ⇒ 合并后的 gRPC 入口**保护能力比 B 弱**（V03） |
| 7 | **Common Starter 上收是否遗漏原有消费方？** | ⚠️ **部分是**。① 11 个 Jackson/HTTP 工具类上收后**域内零副本** ⇒ ✅ 无覆盖遗漏；② JWT/口令 3 类上收到 `common/security/` 但**全空壳** ⇒ 消费方无载体（V07）；③ `redis/` 2 个 auto-config 上收后**没进 imports** ⇒ **能力被上收但被搁浅**（V01）；④ `commonfact` 的 **favorite 域**上收会改变其 wire 行为 ⇒ **待裁决**（V11，映射表 §7.4 #1） |
| 8 | **Spring Boot 4 → 3.5 是否引入业务可观察行为变化？** | ⚠️ **是，且有 1 项未被察觉**。已适配 11 项（F-1~F-5、F-7~F-15）；**未察觉的 1 项 = F-6**：imports 登记面从 8 行变 7 行 ⇒ **2 个已实现的能力静默失效**（V01）。其余：**Jackson 3→2** 经 52 例对拍覆盖；**Spring 7→6.2** 的 converter 覆写点漂移编译期即红（不会静默） |
| 9 | **Tomcat / Jackson / Spring Framework 的底层版本变化是否产生实际漂移？** | ✅ **Jackson / Spring 已回改且有对拍**（F-1~F-3）。⚠️ **Tomcat 11→10.1 的实际漂移尚未验证** —— B 的 `GoStyleClientDisconnectProbe` 反射链取证全部基于 Tomcat 11 ⇒ C 侧实现该能力时**必须重测**（F-7，当前因未实现而不适用） |
| 10 | **哪些差异已经通过证据证明安全？** | ① communityfact 的 23 个 L1 JSON tag + `omitempty` 语义 + `DeliveryStatus` 取值；② JCS 同库 ⇒ 幂等指纹不漂；③ 响应信封 `{code,msg,data}` 与 `data:null` 保留；④ 404/405/400/413/503 的状态码 + 头 + body 字节；⑤ 雪花位段与时钟回拨免疫；⑥ `rag.protection` 12 项默认值；⑦ Filter 槽位；⑧ 观测 7 个 span key + 3 个 kind + 500ms + 严格 `>` + 判定顺序 + `http.route` 语义；⑨ HTTP 熔断隔离 key 与成败判据；⑩ 三处 Boot 4→3.5 包名回改；⑪ A3 的 gRPC 挂载点（有真触发测试）；⑫ A5/A6/A12 三条守门 |
| 11 | **哪些差异仍然无法确认？** | ① Redis 2 项的**运行期**行为（上游未加载 ⇒ 无法验证）；② SQL 超时链的运行期行为（同上）；③ OTel span 的**真实**落地（bridge 离线不可得）；④ `Traceparent` 响应头的**真实**取值（依赖 OTel）；⑤ Tomcat 10.1 下 `ClientDisconnectProbe` 反射链是否可用；⑥ 跨域上收的 `JsonbTypeHandler`/`JsonTextTypeHandler`（不在本次范围） |
| 12 | **当前 C 是否具备可靠的行为对拍条件？** | 🟡 **部分具备**。✅ 具备：B 侧 488 例可离线跑通（已实测）；C 侧 96 例可离线跑通；两侧共用同一套 JCS 库 ⇒ 指纹可比。⚠️ **不具备**：① 两侧**同 JVM 同输入**的对拍（Boot 4 + Boot 3.5 不能共载）⇒ 只能「B 的断言值 → C 的期望值」逐条搬运；② C 侧域为空壳 ⇒ **跨域的端到端对拍**（HTTP/gRPC/DB/Kafka）无法做；③ 真 Tomcat 对拍需补（AGENTS.md §八「HTTP 断言走真 Tomcat」尚未满足） |

---

## 附：本次审核的实测动作（可复现）

```bash
# B 侧（Boot 4.0.8）离线全量测试 —— 488 例全绿
cd "/Users/pr/Documents/java&golang/RAG" && mvn -o -pl service/common test
#   ⇒ Tests run: 488, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS

# C 侧（Boot 3.5.11）离线全量测试 —— 96 例全绿
cd /Users/pr/Documents/GitHub/RAG-V1 && mvn -o clean test
#   ⇒ common 模块 Tests run: 96, Failures: 0, Errors: 0, Skipped: 0 · 16/16 BUILD SUCCESS

# 🔴 V01 的判定命令（真·@AutoConfiguration 减 imports 清单）
grep -rn "^@AutoConfiguration" common/src/main --include=*.java   # 10 处
grep -v "^#" common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports   # 7 行
#   ⇒ 差集 = redis/ClientAutoConfiguration + redis/DeadlineAutoConfiguration（+ web/JsonContentTypeAdvice，
#      后者由 WebAutoConfiguration 的 @Bean 显式注册，故实际失效的是前两个）

# 🔴 V02 的判定命令
grep -rn "DeadlineQueryTimeoutInjector\|SqlTimeoutStatusTranslator" --include=*.java .   # 除自身外 0 命中

# 🔴 V06 的判定命令（三个空类）
for f in observability/ClientDisconnectProbe protection/RouteResolution protection/RouteTemplateSource; do
  echo "$f: $(wc -l < common/src/main/java/com/rag/common/$f.java) 行"; done   # 均为 9 行、类体为空
```

> ⚠️ **本次未执行**：修改任何源码 / POM / 配置 / Mapping_Table / File_Tree（§13 禁止）。
> ⚠️ **本次未覆盖**：各域 `service/<域>/`（10 个域的 520 个类）、`proto/` 模块、B 侧 `service/users/users-common/` 的其余部分。
