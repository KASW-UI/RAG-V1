# hot 域迁移审核报告（独立复审 · 只审不改）

> **性质**：按 `.agents/verification.md`（§十三 **只审不改**）执行的**独立迁移审核**，产出为只读结论。
> **本报告不修改任何 B/C 源码、POM、配置、Mapping_Table、File_Tree**；全部发现只登记证据与修复建议。
> **与既有台账的关系**：`verification-hot-service-2026-11-03.md` 是**自证**（实施方自己核）；本报告是**他证**（以 B 侧源码为唯一基准的复审）。
> ⚠️ **基准纪律**（AGENTS.md §3.1）：C 侧注释 / 旧台账 / Mapping_Table **一律不作基准**；以 **B 侧源码**为准。

---

## 11.1 审核范围

| 项 | 内容 |
|---|---|
| **B（基准）** | `/Users/pr/Documents/java&golang/RAG/service/hot/` —— `hot-api`（17 类）+ `hot-common`（2 类）+ `hot-rpc`（15 类）+ `hot-proto`（1 proto）= **35 个主源文件**；另引用 `service/common/`（共享层，B 侧 hot 实际消费到的部分）|
| **C（受审）** | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/hot/service/hot/`（**31 个主源类** + `application.yml` + `pom.xml`）；`common/`（只读）、根级 `proto/`（只读）|
| **Boot 版本** | B = **4.0.8** · C = **3.5.11**（D14 回退）|
| **关键依赖版本** | devh `grpc-*-spring-boot-starter` **3.1.0.RELEASE**（两侧同）· grpc **1.63.0**（C 根 pom 统一管）· Spring Cloud **2025.0.3** · SCA **2025.0.0.0** · spring-cloud-commons **4.1.0** · Jackson **2.19.4** · Tomcat **10.1.52** |
| **Mapping_Table** | `docs/Mapping_Table/hot/hot.md`（**只作定位依据**，不作证据）|
| **File_Tree** | `docs/File_Tree/hot.md`（只作结构约束）|
| **Git 版本** | B = `cc432808`（2026-11-02）· C = `align/hot` @ **`91a5fdc`** |
| **本次方法** | ① 3 路独立复审（mq 面 / 编排面+HeavyKeeper / 装配+配置面）+ 主审逐点复核；② **剥注释机械 diff**（去 javadoc/import 顺序后逐行）；③ **配置键扁平化 diff**（101 键）；④ **字节码级**验证关键机制（`javap` SCA / spring-cloud-commons）；⑤ **运行证据**（§11.4-2、§11.4-3 两条探针，classpath 取自 C 侧 fat jar）|

---

## 11.2 B → C 责任承接表

> 覆盖本次审核范围内**全部 35 个 B 侧主源职责**（§11.2 要求：不能只提供示例）。
> 状态口径按 §十：PASS / FAIL / NOT_VERIFIED / FRAMEWORK_DIFFERENCE / INTENTIONALLY_CHANGED / NOT_IMPLEMENTED / BLOCKED。

### (a) `hot-api`（B 侧 HTTP 进程）

| # | B 源文件（`service/hot/hot-api/.../`） | B 源职责 | C 承接文件（`service/hot/src/main/java/com/rag/hot/`） | C 承接方法 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|---|
| 1 | `api/HotApiApplication.java` | api 进程入口 + 组件扫描 + discovery | `HotServiceApplication.java` | `main` / 类注解 | N:1（D1 合并）| **INTENTIONALLY_CHANGED** | D1；`@EnableDiscoveryClient`/`@EnableScheduling`/`@ConfigurationPropertiesScan` 齐；`@Import` 清空（D3）|
| 2 | `api/advice/GlobalExceptionHandler.java` | 业务异常/框架错误的 wire 形状 | `advice/HotApiExceptionHandler.java` | `onBusinessException` / `onLimitExceeded` / `onNotFound` / `onMethodNotSupported` | 1:1 | **PASS** | 恒 200 + 单层信封；`getBizMsg()` 取纯文案；404/405 不包信封；**共享层闸门异常已单列分支** |
| 3 | `api/advice/GlobalResponseAdvice.java` | 响应信封包装 | `advice/HotApiResponseAdvice.java` | `beforeBodyWrite` | 1:1 | **PASS** | 只包一层；不重包 |
| 4 | `api/client/ArticleRpcClient.java` | article-rpc 出向调用 + 出向 deadline | `client/ArticleRpcClient.java` | `getArticle` / `stubWithDeadline` | 1:1（**键位改**）| **INTENTIONALLY_CHANGED** | F-14：B 的 `rag.protection.outbound-deadline.cap-millis` → C 的 `hot-api.article-rpc.timeout`；**有效值同为 2000ms**，且 C 侧共享层 cap 刻意未配 ⇒ 单一上界来源 |
| 5 | `api/client/GoStyleGrpcCallDeadline.java` | 把请求剩余预算挂到 stub | `client/ArticleRpcClient.java#stubWithDeadline` | 同名职责并入 | **N:1（并入 #4）** | **INTENTIONALLY_CHANGED** | 三分支逐条同构（`<0` 不挂 / `≤0` 1ns / 正常挂 min(cap,预算)）|
| 6 | `api/client/HotRpcClient.java` | hot-rpc 出向 gRPC 客户端 | —— | —— | **删除（D2 域内直连）** | **INTENTIONALLY_CHANGED** | D2 已批准；连带后果 = 域内方向丢 2s 上界 ⇒ **F-02**（已签字，实测失败形状非确定）|
| 7 | `api/config/HotHydrationExecutorConfiguration.java` | 补全线程池（core 64 / queue 64 / CallerRuns） | `config/HotHydrationExecutorConfiguration.java` | `hotHydrationExecutor` | 1:1 | **PASS** | 五项设置逐条一致；C 侧另建 `applicationTaskExecutor`（B 侧由 Boot 自动装配）|
| 8 | `api/controller/HotArticleController.java` | `GET /hot/v1/articles` 入口 + form 解析调用 | `controller/HotArticleController.java` | `getHotArticles` | 1:1 | **PASS** | path/method 不变；薄适配层（值域校验在 logic 层）|
| 9 | `api/dto/HotArticleCard.java` | 13 分量卡片 DTO | `dto/article/HotArticleItem.java` | record | 1:1 | **PASS** | 13 键名 + **顺序**逐条一致；`hot_score` 用 `long` |
| 10 | `api/dto/HotArticlesReq.java` | 分页入参 | `dto/article/HotArticlesReq.java` | record | 1:1 | **PASS** | —— |
| 11 | `api/dto/HotArticlesResp.java` | 分页出参 | `dto/article/HotArticlesResp.java` | record | 1:1 | **PASS** | `{items,total,page,page_size,scope}` |
| 12 | `api/exception/HotCodeError.java` | 业务异常（带 Go `Error()` 原文 + 纯文案） | `contract/CodeError.java` | —— | 1:1 | **PASS** | 纯文案与原文分离 |
| 13 | `api/form/GoFormValues.java` | form 解析 5 条规则 + **2048 上限** | `service/HotApiSupport.java`（`raw`/`parseInt32`/`firstFormValue`/`requireParsableJsonBody`）**+ 共享层 `ValueLimitInterceptor`** | 见下 | **1:N + 能力上收** | **PASS（可观察形状）/ FRAMEWORK_DIFFERENCE（覆盖面）** | 3 条解析规则逐条同构；2048 闸门**运行证据**见 §11.4-2；覆盖面差（query+body → query）**对 hot 不可达**（唯一端点 GET+query）|
| 14 | `api/metrics/HotApiMetrics.java` | HTTP 面指标（3 个计数器 + 标签） | `metrics/HotApiMetrics.java` | 5 个 observe 方法 | 1:1 | **PASS** | 指标名 / 5 个 reason / 2 个 callee 逐字节一致；**不对称（hot-rpc=fatal vs article-rpc=skip）完整保留** |
| 15 | `api/service/HotArticleHydrator.java` | 并发补全（窗口 8）+ 4 条 skip 分支 | `service/HotApiService.java` | `hydrate` + `fetchOne` | **N:1（并入 #16）** | **PASS** | 先 acquire 再 submit；结果落**输入下标**；异常 best-effort 不外抛 |
| 16 | `api/service/HotArticleQueryService.java` | 编排主干（6 步）+ 预算传播 | `service/HotApiService.java` | `getHotArticles` | **N:1（并入 #15/#13）** | **PASS / INTENTIONALLY_CHANGED** | 6 步顺序 + `success` 置位点 + 绝对 deadline 口径逐条一致；**TopK 取数无预算** = F-02 |
| 17 | `api/service/HotRankPaginator.java` | 分页数值契约 + 稠密跨页 rank | `service/HotApiSupport.java` | `normalize`/`paginate`/`assignRanks`/`slice` | **N:1（并入 #13）** | **PASS** | 阈值 1/20/50/100 逐条一致；**int32 乘法溢出 ⇒ 空页**保留；rank 过滤后稠密、跨页连续 |

### (b) `hot-common`（B 侧错误码表）

| # | B 源文件 | B 源职责 | C 承接文件 | 承接方法 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|---|
| 18 | `hot-common/.../errmsg/HotErrorCode.java` | 11 个错误码枚举 | `contract/ErrCodes.java` | 枚举 | 1:1 | **PASS** | 11 条**含顺序**逐条一致 |
| 19 | `hot-common/.../errmsg/HotErrorMessages.java` | 11 条文案 | `contract/ErrMsg.java` | `getMsg` | 1:1 | **PASS** | 11 条逐字一致；5001 = `"internal server error"` |

### (c) `hot-rpc`（B 侧 gRPC + MQ 进程）

| # | B 源文件（`service/hot/hot-rpc/.../`） | B 源职责 | C 承接文件 | 承接方法 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|---|
| 20 | `rpc/HotRpcApplication.java` | rpc 进程入口 + `@Import`×4 + gRPC 拦截器链 | `HotServiceApplication.java` | 类注解 | **N:1（D1）** | **INTENTIONALLY_CHANGED** | `@Import` 清空（D3）；gRPC 拦截器链由共享层 `ProtectionAutoConfiguration#seaGrpcServerConfigurer` 承担（`@ConditionalOnMissingBean` ⇒ 不会挂两条链）|
| 21 | `rpc/bootstrap/NacosRegistration.java` | 手写 Nacos 注册（注册 **gRPC 端口**）| `wiring/NacosRegistration.java` | `register`/`deregister` | 1:1 | **PASS / INTENTIONALLY_CHANGED** | 注册名 `hot-rpc`、注册 gRPC 端口、失败⇒`NOT_SERVING` 不崩、时机一致；**namespace `public`→`sea-rtw`** = F-01（部署面）；`register-enabled=false` 经**字节码**验证真生效 |
| 22 | `rpc/config/HotKafkaConfig.java` | Kafka 容器级语义（零重试 + 分区回调）| `config/HotKafkaConfig.java` | `hotKafkaListenerContainerFactory`/`zeroRetryErrorHandler`/`rebalanceListener` | 1:1 | **PASS** | `FixedBackOff(0,0)` + `setAckAfterHandle(true)` 逐字一致；**Boot 4→3.5 包名回改**（`org.springframework.boot.kafka.autoconfigure` → `org.springframework.boot.autoconfigure.kafka`，已核 class 存在）|
| 23 | `rpc/config/HotRpcProperties.java` | `hot-rpc.*` 配置（14 键）| `config/HotRpcProperties.java` | —— | 1:1 | **PASS** | **14/14 键位 + 14/14 默认值**一致；`Weight.code` 死字段保留 |
| 24 | `rpc/config/Jackson2ObjectMapperConfiguration.java` | Boot 4 Jackson 补丁 | `common/.../web/Coercion.java`（共享层）| `applyTo` | **能力上收** | **FRAMEWORK_DIFFERENCE** | `FAIL_ON_UNKNOWN` 等价；**少 1 条** `Boolean{Integer}→Fail`；多关 `ACCEPT_FLOAT_AS_INT`。⇒ **对 hot 不可触发**（载荷仅 2 个 String 字段 + HTTP 面无 JSON body）|
| 25 | `rpc/domain/HeavyKeeper.java` | 计数草图（uint32 回绕 + 概率让位 + TopK）| `domain/model/HeavyKeeper.java` | —— | 1:1 | **PASS** | 剥注释 diff 仅差 import；**156 行代码逐字节相同**；回绕/无符号比较/取模全部保留 |
| 26 | `rpc/metrics/HotRpcMetrics.java` | rpc 面指标 | `metrics/HotRpcMetrics.java` | —— | 1:1 | **PASS** | 指标名 + 标签逐条一致 |
| 27 | `rpc/mq/ArticleHotEvent.java` | Kafka 载荷（2 字段）| `contract/ArticleHotEvent.java` | record | 1:1 | **PASS** | `article_id`/`type` + `ignoreUnknown=true` 逐条等价（仅全限定名→import，字节码相同）|
| 28 | `rpc/mq/HotEventConsumer.java` | 消息入口（校验→计数→registry.add→触发快照）| `messaging/HotEventConsumer.java` | `onMessage`/`consumeMessage`/`onPartitionsAssigned`/`onPartitionsRevoked` | 1:1 | **PASS** | 「永不外抛」第一语义、坏消息 3 类判定**顺序**、先计数后查分区 逐条一致；trace 载体 OTel `Context`→Micrometer `ObservationRegistry`（D4，**对 drop 语义/位点/重试零影响**）|
| 29 | `rpc/mq/HotMergeScheduler.java` | 分布式锁 + 跨分片求和合并（3 个 Lua）| `messaging/HotMergeScheduler.java` | `periodicMerge`/`acquireLockWithWatchDog`/`watchDog`/`releaseLock`/`mergePartitions` | 1:1 | **PASS** | 3 个 Lua **逐字节一致**（含锁 key 用字面量的怪癖）；4 个 Redis key + TTL 未统一；抢不到锁整轮跳过；**照抄 B 侧两处已知缺陷**（Lua 报错被误报成 lock lost / 读阶段错误被忽略）|
| 30 | `rpc/mq/HotPartitionSketchRegistry.java` | 分区→sketch 注册表 | `messaging/HotPartitionSketchRegistry.java` | `assign`/`detach`/`detachWithinWriteLock` | 1:1 | **PASS** | 剥注释 diff **完全 IDENTICAL**；锁内取引用 / 锁外做 Redis I/O 逐条一致 |
| 31 | `rpc/mq/HotRedisSnapshotStore.java` | 快照存储（4 段 Lua + 30s 周期）| `messaging/HotRedisSnapshotStore.java` | `flush`/`flushByPartition`/`loadInto`/`periodicSync` | 1:1 | **PASS** | Lua 逐字节一致；`EXPIRE 300` 硬编码保留；失败即计数不抛 |
| 32 | `rpc/service/HotRankingQueryService.java` | 排行查询（TopK 归一 + Redis 读）| `service/HotRankingQueryService.java` | `getHotArticles` | 1:1 | **PASS** | `k≤0→20` / `k>100→100` 逐条一致 |
| 33 | `rpc/service/InteractionStubService.java` | 互动 stub（coin/comment/like/share）| `service/InteractionStubService.java` | —— | 1:1 | **PASS** | 剥注释 diff 仅差包名 |
| 34 | `rpc/service/impl/HotServiceGrpcImpl.java` | gRPC 服务端（6 个方法）| `grpc/HotGrpcService.java` | 同名 6 方法 | 1:1 | **PASS** | 剥注释 diff 仅差包名 + `LOG`→`log`；**去 `@Component` 是等价的**（devh `@GrpcService` 本身即 `@Component`）|
| 35 | `hot-proto/src/main/proto/hot.proto` | gRPC 契约 | `proto/src/main/proto/hot.proto` | —— | 1:1 | **PASS** | 逐条一致，唯一差异 = `java_package`（表达层）|

---

## 11.3 多文件合并和拆分专项报告

| 专项 | 内容 | 判定 |
|---|---|---|
| **N:1 合并** | ① B 3 类逻辑层（`HotArticleQueryService`+`HotArticleHydrator`+`HotRankPaginator`）→ C **2 类**（`HotApiService` 编排 + `HotApiSupport` 辅助）：`HotApiSupport` 内**分段承载**（「输入解释规则」段 + 「分页数值契约」段），17 条职责逐条有落点。<br>② B `GoFormValues` → C `HotApiSupport`（3 条解析）+ 共享层（2048 闸门）= **能力上收**。<br>③ B `GoStyleGrpcCallDeadline` → C `ArticleRpcClient` 私有方法。 | **PASS**（逐条职责有落点，无遗漏分支/异常路径）|
| **1:N 拆分** | B 2 进程入口 → C **1 入口**（D1）；B `hot-common` 2 类 → C `contract/` 2 类（D11 包压平）；B proto → 根级 `proto/` 模块（D7）。 | **INTENTIONALLY_CHANGED**（D1/D7/D11 已拍板）|
| **独立进程合并** | B `hot-api`（HTTP 18897）+ `hot-rpc`（gRPC 8083）→ C **单进程**（HTTP 8895 + gRPC 8083）。合并带来 3 处连带面：① `rag.side` 取 api 侧形态；② `spring.application.name` 单值 `hot-service`；③ actuator 暴露集取**并集/交集**取舍（见 A-03）。 | **INTENTIONALLY_CHANGED**（D1）；连带面 A-02/A-03 已登记为新发现 |
| **共享能力上收** | `Jackson2ObjectMapperConfiguration`（Boot 4 补丁）、form 值闸门、gRPC 服务端拦截器链、`@Import` 清单 → 共享层 starter（`AutoConfiguration.imports` 14 条）。 | **PASS**（上收本身正确；2 条共享层与 B 侧 hot-rpc 的规则集差异对 hot **不可触发**）|
| **Spring 接管** | gRPC service 注册（devh `@GrpcService`）、Kafka 容器（`ConcurrentKafkaListenerContainerFactoryConfigurer`）、Jackson mapper（`JsonBodyConverterAutoConfiguration`）、executor（`ThreadPoolTaskExecutorBuilder`）。 | **PASS**（接管后机制**经运行/字节码验证真生效**，非「Bean 存在」推定）|
| **职责缺失** | **无**。35 个 B 侧主源职责**全部有承接落点**（§11.2 逐项）。 | **PASS** |

---

## 11.4 框架版本差异报告

### 11.4-1 框架机制差异（8 项）

| # | B 机制（Boot 4.0.8）| C 机制（Boot 3.5.11）| 具体变化 | 影响的执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 最终结论 |
|---|---|---|---|---|---|---|---|---|
| FW-1 | `org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer` | `org.springframework.boot.autoconfigure.kafka.…` | Boot 4 拆模块 ⇒ 包名不同 | Kafka 容器创建 | **否** | 直接回改包名 | `unzip -l spring-boot-autoconfigure-3.5.11.jar` 核到 class 存在 + 域级测试 77 例绿 | **PASS** |
| FW-2 | 手写 OTel span（`GlobalOpenTelemetry.getTracer`）| Micrometer `Observation` | 载体替换 | MQ 消费观测 / HTTP 出向观测 | **否**（MQ 面：drop 语义、位点、重试均不依赖载体）| 共享层 `MqConsumer` 双签名 | 两份 `MqConsumer` 逐字节比对 + 错误传递链核到 `markSystemError` | **PASS** |
| FW-3 | `Jackson2ObjectMapperConfiguration`（域内 Boot 4 补丁）| 共享层 `Coercion` | 少 1 条 `Boolean{Integer}→Fail`；多关 `ACCEPT_FLOAT_AS_INT` | JSON 反序列化强转 | **否**（hot 载荷仅 2 个 String 字段；HTTP 面无 JSON body ⇒ 无可触发输入）| 能力上收 | 逐条读 `Coercion.applyTo` + 枚举 hot 全部反序列化入口 | **FRAMEWORK_DIFFERENCE**（对 hot 无可观察影响）|
| FW-4 | B 侧域内 `requireFormValueCountWithinLimit`（数 `getParameterMap()` = **query+body**）| 共享层 `GetFormValuesFilter`（只数 **query**）| 覆盖面收窄 | form 值闸门 | **否**（hot 唯一端点 `GET /hot/v1/articles`；全仓无端点收 urlencoded body ⇒ 不可达）| 能力上收 | 读消费点 + 判据逐条对照 + **运行探针**（§11.4-2）| **FRAMEWORK_DIFFERENCE**（形状一致，覆盖面有界）|
| FW-5 | Tomcat `maxParameterCount`：yml 写 2048，**运行期被共享层 customizer 覆盖为 10001** | yml 不写，运行期同为 10001 | 配置层不同、运行期同 | 容器参数上限 | **否** | — | 读两侧 customizer + `@Order(LOWEST_PRECEDENCE)` ⇒ 必然晚于 Boot 的 order=0 | **PASS** |
| FW-6 | `register-enabled=false` 的效果靠注释断言 | 同键同值 | —— | Nacos 自动注册 | **否** | —— | **字节码级**：`NacosAutoServiceRegistration.isEnabled()` → `AbstractAutoServiceRegistration.start()` 的 `if (!isEnabled()) return;` ⇒ web 端口实例永不上线 | **PASS** |
| FW-7 | gRPC 出向上界 = 共享层 `cap-millis`（2000）+ 域内 `GoStyleGrpcCallDeadline` 传播预算 | 域侧 `hot-api.article-rpc.timeout`（2000）自算 min | **键位不同**、有效值同 | article-rpc 出向 | **否** | 承接为域侧参数，共享层 cap 刻意留空 ⇒ 单一上界来源 | 三前提逐条核：① C 侧 timeout=2000 ② C 侧 `outbound-deadline` **未配置** ③ B 侧 cap=2000；且 C 侧唯一出向方向只有 article-rpc ⇒ 覆盖完整 | **INTENTIONALLY_CHANGED**（F-14 已签字）|
| FW-8 | `observability.http-timeout: 3s` / `rpc-timeout: 2s` | 同键同值（合并后两键都在）| —— | HTTP 预算 / gRPC 预算 | **否** | —— | 逐键对照 | **PASS** |

### 11.4-2 运行证据①：共享层 form 值闸门在 hot 域的真实形状

> 补齐复审标记的 `NOT_VERIFIED`。探针 classpath **取自 C 侧 fat jar**（`BOOT-INF/classes` + `BOOT-INF/lib/*.jar`）⇒ 与运行期完全一致（探针放在 `/tmp`，**不进仓库**）。

| 断言 | 读数 | 判定 |
|---|---|---|
| ① `rag.http.go-form-limit.mode: envelope` ⇒ 解析为 ENVELOPE | `mode = ENVELOPE` | **PASS** |
| ② **2048** 个非空 form 值 ⇒ 闸门**放行** | `preHandle=true` | **PASS** |
| ③ **2049** 个非空 form 值 ⇒ 闸门**触发** | `msg=too many form values, error: a0=v0&a1=v1&…` | **PASS** |
| ④ hot 域兜底渲染 | `status=200 code=5001 msg=internal server error data=null` | **PASS** ⇒ **与 B 侧形状逐条一致**（B：域内 `parseFailure` → `BusinessException(5001,"internal server error")` → 同信封）|
| ⑤ **3000** 个**空**值 ⇒ 放行（空值不计数） | `preHandle=true` | **PASS**（B 侧 `net/url` 同规则）|
| ⑥ **10001** 段 ⇒ 段数上限先判 | `msg=number of URL query parameters exceeded limit` | **PASS** |

⇒ **结论**：DEV-HOT 表中「共享层上收 ⇒ 可观察形状零影响」的签字结论**成立**。
⇒ ⚠️ **但 C 侧 hot 测试无一条覆盖该闸门**（`grep 2048/MAX_FORM_PARAM_COUNT/go-form-limit` 在 `service/hot/src/test` 下**零命中**）⇒ **覆盖缺口 G-8**。

### 11.4-3 运行证据②：hot 域 OTel exporter 的装配状态

| 场景 | 读数（`SdkTracerProvider` 的 `activeSpanProcessor`） | 判定 |
|---|---|---|
| A. `endpoint = null`（**= hot 域当前状态**：yml 无任何 otel 键）| `NoopSpanProcessor{}` ⇒ **span 无处可去** | **确认 A-01** |
| B. `endpoint = http://127.0.0.1:4317`（= 补上 otel 段之后）| `BatchSpanProcessor{spanExporter=OtlpGrpcSpanExporter{endpoint=http://127.0.0.1:4317,…}}` ⇒ 真导出 | **对照成立** |

---

## 11.5 语义漂移清单

> §13「只审不改」⇒ 以下**只登记证据与建议**，**不修**。
> **D-01 ~ D-04 = 本次新发现（此前无任何台账覆盖）**；D-05 ~ D-07 = 文档/覆盖缺口，非行为漂移。

### D-01 🔴【中】OTLP 追踪导出**整体缺失** —— hot 域不装任何 exporter

| 项 | 内容 |
|---|---|
| **Drift ID** | `D-01`（新） |
| **问题位置** | C 侧 `service/hot/src/main/resources/application.yml` —— **全文件无任何 `otel` 键**（grep 零命中）|
| **B 文件和方法** | `service/hot/hot-api/src/main/resources/application.yml:209-218`（`otel.exporter.otlp.endpoint` / `protocol: grpc` / `otel.resource.attributes.service.name: hot-api` / `otel.traces.sampler: always_on`）；`service/hot/hot-rpc/src/main/resources/application.yml:335-337`（`management.otlp.tracing.endpoint`）|
| **C 文件和方法** | `service/hot/src/main/resources/application.yml`（**无 otel 段**）；消费点 `common/.../observability/GoStyleOtelConfig.java:126`（`normalizeEndpoint(null)` ⇒ null）→ `common/.../observability/GoStyleOtelAgent.java:195`（`if (config.endpoint() != null)` ⇒ **不装 exporter**，照抄 B 的 `if len(c.Endpoint) > 0`）|
| **B 行为** | 启动时装全局 `TracerProvider` + `BatchSpanProcessor` ⇒ span 真的导出到 jaeger |
| **C 行为** | 装配了 provider，但 `activeSpanProcessor = NoopSpanProcessor{}` ⇒ **hot 域的 span 一条都导不出去**；跨域链路在 Jaeger 侧断链 |
| **差异原因** | D1 合并时把 B 侧两份 yml 的 otel 段**整体漏掉**（不是 D1/D2 的既定后果）|
| **运行证据** | §11.4-3（探针 A/B 对照）|
| **影响范围** | **可观测面**（业务语义零影响）；`common/web/TraceparentFilter` 写出的 traceparent 响应头也随之缺失 |
| **严重程度** | 🟡 中 |
| **修复建议** | 在 hot 的 `application.yml` 补 B 侧同款 4 键（端点 `http://${OTEL_ADDR:jaeger}:${OTLP_PORT:4317}` / `protocol: grpc` / `otel.resource.attributes.service.name`（D1 后只能填一个值 ⇒ 建议 `hot-service` 并登记为 D1 连带偏离）/ `otel.traces.sampler: always_on`）+ `management.otlp.tracing.endpoint`。**注意**：这不是 hot 独有 —— `comment` / `favorite` / `message` / `task` 四域**同样零 otel 段** ⇒ 建议**由 `global` 收口域统一处置**，否则 5 个域各补各的必然再漂 |
| **验证方法** | 补段后重跑 §11.4-3 探针（`activeSpanProcessor` 应为 `BatchSpanProcessor`）+ 真起服务看 Jaeger 收到 hot 的 span |

### D-02 🟡【低-中】`/actuator/health` 暴露面比 B 侧大

| 项 | 内容 |
|---|---|
| **Drift ID** | `D-02`（新） |
| **问题位置** | C 侧 `service/hot/src/main/resources/application.yml:289-291` |
| **B 文件和方法** | `hot-api/.../application.yml:186-191`（**不配** ⇒ Boot 默认 `never`）；`hot-rpc/.../application.yml:316-318`（`when_authorized`）|
| **C 文件和方法** | `management.endpoint.health.show-details: always` |
| **B 行为 / C 行为** | B 两侧**都不是** `always`；C 侧是 ⇒ 响应体多出 components / 磁盘空间 / 外部化详情（该端点**无鉴权**）|
| **差异原因** | D1 合并时取值偏向宽松侧 |
| **影响范围** | 运维信息暴露面（业务零影响）|
| **严重程度** | 🟡 低-中 |
| **修复建议** | 取两侧较严者 `when_authorized`；若刻意保留 `always` ⇒ 登记为签字偏离 |
| **验证方法** | 起服务 `curl /actuator/health` 比对响应体 |

### D-03 🟢【低】Actuator 暴露集少了 `metrics`

| 项 | 内容 |
|---|---|
| **Drift ID** | `D-03`（新） |
| **问题位置** | C 侧 `service/hot/src/main/resources/application.yml:284-288` |
| **B 文件和方法** | `hot-rpc/.../application.yml:307-315`（`health,info,prometheus,metrics`）；`hot-api` 侧为 `health,info,prometheus` |
| **C 文件和方法** | `health,info,prometheus` |
| **B 行为 / C 行为** | B 侧 hot-rpc 的 `/actuator/metrics` = 200；C 侧 = **404** |
| **差异原因** | D1 合并取了 api 侧（交集）而非并集 |
| **严重程度** | 🟢 低（业务零影响）|
| **修复建议** | 取**并集**补 `metrics`；或登记为 D1 合并的有意收敛 |
| **验证方法** | `curl -o /dev/null -w '%{http_code}' /actuator/metrics` |

### D-04 🟢【低】gRPC 端口的**环境变量契约断裂**

| 项 | 内容 |
|---|---|
| **Drift ID** | `D-04`（新） |
| **问题位置** | C 侧 `service/hot/src/main/resources/application.yml:160` |
| **B 文件和方法** | `hot-rpc/.../application.yml:223` → `${HOT_RPC_GRPC_PORT:8083}` |
| **C 文件和方法** | `${HOT_GRPC_PORT:8083}` |
| **B 行为 / C 行为** | 数值同为 8083；但按 B 侧 env 契约部署（`HOT_RPC_GRPC_PORT`）⇒ **C 侧该变量静默失效**、回落默认 8083 ⇒ 若集群内 8083 已占用 ⇒ 启动冲突 |
| **差异原因** | D1 合并时改了 env 变量名 |
| **严重程度** | 🟢 低（配置契约面，非运行时行为）|
| **修复建议** | 保留原名 `${HOT_RPC_GRPC_PORT:${HOT_GRPC_PORT:8083}}` 双名兼容；**并同步核对仓库根的 env 契约文件**（本次未核，见 §十一·未覆盖）|
| **验证方法** | 带 `HOT_RPC_GRPC_PORT=18083` 起服务 ⇒ 读实际绑定端口 |

### D-05 🟡【低-中】C 侧 javadoc 对 F-02 后果的描述**不准确**（两路独立复审均发现）

| 项 | 内容 |
|---|---|
| **Drift ID** | `D-05`（新 · 文档级）|
| **问题位置** | `service/hot/src/main/java/com/rag/hot/service/HotApiService.java:80-84` |
| **问题** | 注释写「少了一层 2s per-call 上界 ⇒ **只剩 HTTP 层的请求预算兜底**」—— **HTTP 预算并不约束该 Redis 调用**（它只约束整体 HTTP 响应）|
| **实际行为（已实测）** | 台账 §九·M5：Redis 卡死时 **B = `HTTP/1.1 200` + 5001 信封 @2011ms**；**C = `HTTP/1.1 503` + `text/plain` + `Request Timeout\n` @3014ms**，且形状**非确定**（三候选，含混血形态 ⇒ 共享层 F-15）|
| **影响** | 不改行为，但会让 reviewer 误以为失败仍是业务信封 ⇒ 客户端按信封解会拿到 `503 + text/plain` ⇒ **信封契约被打断** |
| **严重程度** | 🟡 低-中（文档准确性）|
| **修复建议** | 按 §13 **不改**；建议 hot lane 后续把措辞改成与 M5 实测读数一致（明确写出「域内查询方向**无上界** ⇒ 失败形状非确定」）|
| **验证方法** | 文档评审即可 |

### D-06 🟢【低】两处 javadoc 与实际代码/台账口径不一致

| 项 | 内容 |
|---|---|
| **Drift ID** | `D-06`（新 · 文档级）|
| **D-06a** | `HotApiSupport.java:65-72` 称共享层判据与 B 侧「**不完全相同**」；台账 F-03 称「**判据口径与 B 侧一致**」。**以代码为准**：共享层判据（10000 段 → 2048 非空值 → B 侧逐字文案）**与 B 侧一致**；残留的「10001 段时 body 取 2048 那条（顺序反了）」是**共享层**既有差异，非 hot 引入。⇒ 两侧说法需统一。|
| **D-06b** | `messaging/HotPartitionSketchRegistry.java:168-171` 的 javadoc 表述「把 B 侧死锁请回来」**混淆了 B 侧 Java 与 Go 真源**：B 侧 `detachWithinWriteLock`（持写锁做 Redis I/O）**两侧都是零调用点死代码**，且 Java `ReentrantReadWriteLock` 的 write→read 降级是官方支持 ⇒ **不构成死锁**；真正的形态是「持写锁做 Redis I/O ⇒ 阻塞读者」。|
| **严重程度** | 🟢 低（不影响行为）|
| **修复建议** | 按 §13 不改；建议后续 hot lane 按**代码事实**修订措辞（AGENTS.md §3.1 的反向要求：注释也要对齐代码）|

### D-07 🟡【中】验证**覆盖缺口**清单（实现正确，但缺断言守护 ⇒ 静默回归无守卫）

> 均为「**B 侧有、C 侧无**」的判据。实现层本身已 PASS（§11.2）—— 这里是**守护网缺口**，不是行为漂移。

| ID | 缺口 | B 侧证据 | C 侧现状 | 风险 | 严重程度 | 建议 |
|---|---|---|---|---|---|---|
| **G-1** | 「撤销落盘在写锁之外」的**并发判据** | `HotRevokeFlushOutsideLockTest`（latch 卡住 flush，断言读锁仍可用）+ `HotPartitionSketchRegistryDeadlockTest`（6 例）| C 侧 mq 测试**零** revoke 用例 | 若有人把 `flush` 挪回锁内，C 侧**无任何测试会红** | 🟡 中 | 移植 B 侧那两条 |
| **G-2** | 合并 **>100 条的截断方向** | `HotRedisLuaGateTest:428-450`（B 侧注释自陈「既有用例最多 4 条 ⇒ 永远走不到截断分支 ⇒ 排序方向若被改反，断言会假绿」）| C 侧最大只造 3 条 ⇒ **截断分支零覆盖** | 降序若被改反 ⇒ 合并保留**分数最低**的 100 条，C 侧全绿 | 🟡 中 | 补一条 >100 条用例 |
| **G-3** | **看门狗续期 Lua**（`RENEW_SCRIPT`）直接判据 | `HotRedisLuaGateTest:176-193`（值匹配⇒续期成功；不匹配⇒返 0 且不动锁）| C 侧**零**覆盖（只测了 acquire/release CAS）| 锁 TTL 10s < 续期间隔 3s×N ⇒ 续期写错 ⇒ 合并中途锁过期 ⇒ **并发合并 ⇒ 大 key 交叉覆盖** | 🟡 中 | 补一条续期脚本判据 |
| **G-4** | 「分区未持有仍计数」+「`ignoreUnknown` 多字段」 | `HotEventConsumerDropTest:144,170` | C 侧真 Kafka 测试只覆盖未知 type / 坏 payload | ①「先计数后查分区」次序若被改 ⇒ 指标语义变；② `ignoreUnknown` 若被去掉 ⇒ **生产端 4 字段消息全被 drop ⇒ 热榜永不更新** | 🟡 中 | 补两条 |
| **G-5** | `RoundRobinAssignor` **真落到 consumer** | `HotKafkaConsumerGateTest:297-322`（2 消费者，断言 `[0,2]/[1]` 而非 Range 的 `[0,1]/[2]`，带 Range 对照组证明判据有牙齿）| C 侧 yml 配了但**无断言** | §3.3.1「配置里没写 ≠ 没开」；若被 Boot 3.5 忽略 ⇒ 退回 Range ⇒ **分区归属改变 ⇒ hkMap 布局与热榜分布改变** | 🟡 中 | 补一条同款判据 |
| **G-6** | 容器 **error handler 装配层**断言 | `HotKafkaConsumerGateTest:328-368`（反射读 `noRetries/maxAttempts/ackAfterHandle`）| C 侧靠**行为**断言（投递次数=1）⇒ 实质风险低 | Spring Kafka 内部结构变化时行为断言解读成本高 | 🟢 低 | 可选 |
| **G-7** | **共享层 2048 闸门**在 hot 域的守护用例 | —— | C 侧测试**零命中**（本次已补运行证据，但**仓库内无守护测试**）| 共享层若改判据口径 ⇒ hot 侧无红灯 | 🟡 中 | 补一条端到端（真 Tomcat 打 2049 个 query）|
| **G-8** | `otel` 段的守护用例 | —— | 无（且当前**无配置**）| 与 D-01 同源 | 🟢 低 | 随 D-01 一并补 |

---

## 11.6 完成度统计

> **不**用文件数/代码行数代表完成度（§11.6）。
> 统计口径：**B 侧 35 个主源职责**（§11.2 逐项）为分母。

| 分类 | 计数 | 说明 |
|---|---|---|
| **已实现且已验证（PASS）** | **26** | §11.2 中标 PASS 的职责行（含 MQ 面 6 类、编排/HeavyKeeper/指标/错误码/proto/DTO 等）|
| **已实现但未验证（NOT_VERIFIED）** | **0** | 本轮把复审标记的 2 处 NOT_VERIFIED（2048 闸门、otel 装配）**用运行证据补齐**；其余全部有源码级 + 运行级证据 |
| **尚未实现（NOT_IMPLEMENTED）** | **0** | 35/35 职责均有承接落点 |
| **明确存在漂移（FAIL）** | **4** | D-01（otel 缺失，中）· D-02（health show-details，低-中）· D-03（actuator 缺 metrics，低）· D-04（env 变量名，低）—— **全部在配置面**，业务语义零影响 |
| **框架差异待验证（FRAMEWORK_DIFFERENCE）** | **2** | FW-3（共享层 Jackson 少 1 条 coercion）· FW-4（2048 闸门覆盖面 query+body → query）—— **均已证明对 hot 无可观察影响** |
| **已批准的主动变更（INTENTIONALLY_CHANGED）** | **9** | D1 合并（入口/端口/service.name/`rag.side`/actuator 取舍）· D2 域内直连（删 `grpc.client.hot-rpc` 5 键、域内方向丢 2s 上界 = F-02）· F-14（cap-millis 键位搬迁）· F-01（Nacos namespace）· 共享层上收（Jackson/闸门/拦截器链/`@Import`）· OTel→Micrometer 载体 |
| **被外部条件阻塞（BLOCKED）** | **0** | —— |
| **文档/覆盖缺口（非行为漂移，单列）** | **3 + 8** | D-05（javadoc 不准）· D-06（两处 javadoc 口径）· D-07 的 G-1~G-8（8 条守护网缺口）|
| **流程面（本 lane 不得碰）** | **3** | `docs/DRIFT/_INDEX.md` 的 hot 行仍「⬜ 未建立」+ `docs/Intentional_Deviations/_INDEX.md` + `docs/Pitfalls/_INDEX.md` 未并入 ⇒ 归主会话合并 |

---

## 十一·未覆盖 / 无法核对的点

| # | 项 | 原因 | 影响 |
|---|---|---|---|
| 1 | **B 侧 Go 真源**（`hot_handler.go` / `heavykeeper.go` / `hotcenter.yaml`）| Go 源码**不在 B 仓内**（B 仓只有 Java 移植 + `tools/difftest/conf/go/hot*.yaml` 配置副本）⇒ 本次基准是「**B 侧 Java ↔ C 侧 Java**」；**B 侧 Java 自身是否忠实于 Go** 不在本次范围 | 若要闭合，需再核 Go 源 |
| 2 | 仓库根的 **env 契约文件**（`.env.example` / docker-compose）是否同步改了 `HOT_RPC_GRPC_PORT`→`HOT_GRPC_PORT` | 本次只核 `service/hot/application.yml` | D-04 的实际影响面待定 |
| 3 | **Nacos 里到底注册了几个实例** / **OTLP exporter 是否真没装**（进程级） | 已用**字节码 + 探针**在机制层证到位，但未起真进程看 Nacos 控制台 | 不影响判定 |
| 4 | `spring.task.scheduling.pool.size=2`（30s 快照与 10s 合并不排队）的**实测线程数** | 未起进程读容器内 bean | 两侧 yml 同键同值 ⇒ 等价性成立 |
| 5 | `applicationTaskExecutor` 的**实测池参数** | 同上 | C 侧用 Boot 的 `ThreadPoolTaskExecutorBuilder` ⇒ 与 B 侧 Boot 自动装配同默认语义 |
| 6 | B 侧 `HotRedisLuaGateTest` / `HotPartitionSketchRegistryDeadlockTest` 只读了 `@DisplayName` 清单，未逐行读全文 | 用于判定覆盖缺口（G-1/G-2/G-3）| 缺口判定成立，细节可由 hot lane 补测时复核 |
| 7 | **heavykeeper 冲突概率分支的精确值** | `ThreadLocalRandom` 抽样 ⇒ **原理上不可断言**（B 侧自身测试亦不断言精确计数）| 已把确定性读数与随机读数分开 |

---

## 十二·§十四 必答 12 问

| # | 问题 | 答案 |
|---|---|---|
| **1** | C 是否完整承接 B 的全部实际职责？ | **是**。B 侧 **35 个主源职责全部有承接落点**（§11.2 逐项，31/31 C 侧文件齐）；**无 NOT_IMPLEMENTED**。合并未造成职责/分支/异常路径遗漏 |
| **2** | Mapping_Table 的 RAG → RAG-V1 映射是否与当前真实代码一致？ | **是**。机械核对：Mapping_Table 列出的 **30 个 C 侧全路径**全部存在；实际 31 个文件中缺的第 31 个（`HotApiSupport.java`）在 Mapping_Table 第 126/224/418 行以短名记录 ⇒ **31/31 覆盖，0 条失效路径**（映射表只作定位依据，未当证据用）|
| **3** | File_Tree 规定的目标结构是否正确落地？ | **是**。`docs/File_Tree/hot.md`（由 `gen-file-tree.py` 按实际文件系统生成）逐条对应真实目录；`HotApiSupport` + `HotApiSupportContractTest` + `HotGrpcErrorMappingTest` 均在位 |
| **4** | 多文件合并是否造成职责、分支或异常路径遗漏？ | **否**。两处 N:1（B 3 类逻辑层→C 2 类；`GoFormValues`→C 辅助类+共享层）**逐条职责有落点**；4 条 skip 分支、3 类坏消息判定顺序、fatal-vs-best-effort 不对称、404/405 不包信封 **全部保留** |
| **5** | 文件拆分是否造成调用链、数据传递或事务边界断裂？ | **否**。本域无事务边界（B 侧亦无）；hot-rpc 的 6 个 gRPC 方法拆分后调用链完整；`HeavyKeeper`/`HotPartitionSketchRegistry` 剥注释 diff **逐字节相同** |
| **6** | API + RPC 合并是否改变原有入口和执行行为？ | **入口变了**（2 进程 → 1 进程，HTTP 8895 / gRPC 8083，**D1 已批准**）；**执行行为未变**（两侧 `@EnableDiscoveryClient`/`@EnableScheduling`/`@Import` 语义齐备）。⚠️ 连带面 4 项已登记（D-01~D-04，其中 D-01 是**漏配**，非 D1 语义必然）|
| **7** | Common Starter 上收是否遗漏原有消费方？ | **否**。共享层 `AutoConfiguration.imports` 14 条齐全；`Jackson2ObjectMapperConfiguration`/form 闸门/gRPC 拦截器链 3 处上收点**全部有对应 auto-config 承接**；**未发现遗漏消费方**。⚠️ 但上收后共享层与 B 侧 hot-rpc 的 mapper 规则集有 1 条差异（FW-3），对 hot 不可触发 |
| **8** | Spring Boot 4 → 3.5 是否引入业务可观察行为变化？ | **未发现**。8 项框架差异逐条核到消费点（FW-1~FW-8）⇒ **FW-1/2/5/6/8 = PASS**（等价）、FW-7 = INTENTIONALLY_CHANGED（键位不同、值同）、FW-3/4 = FRAMEWORK_DIFFERENCE 但**对 hot 无可观察影响** |
| **9** | Tomcat、Jackson、Spring Framework 等底层版本变化是否产生实际漂移？ | **Tomcat**：yml 层不同（2048 vs 不写）、**运行期同为 10001** ⇒ 无漂移。**Jackson**：少 1 条 coercion 规则 ⇒ 对 hot **不可触发**（无可触发输入）。**Spring Framework**：devh configurer 包名回改已核 class 存在；`register-enabled=false` 已字节码验证生效。⇒ **无实际漂移** |
| **10** | 哪些差异已经通过证据证明安全？ | ① **FW-3/FW-4**（共享层规则集与闸门覆盖面差）—— 对 hot 无可触发输入；② **2048 闸门上收** —— §11.4-2 **运行证据 6 条全 PASS**，拒绝形状 `200 + {code:5001,…}` 与 B 侧逐条一致；③ **otel 装配链** —— §11.4-3 探针确认机制本身正确（缺配置而非缺装配）；④ **D-02/D-03/D-04** —— 影响面限于运维查询面与部署 env 契约，**业务语义零影响** |
| **11** | 哪些差异仍然无法确认？ | ① **B 侧 Java 对 Go 真源的忠实度**（Go 源码不在 B 仓内）；② **D-04 的仓库根 env 契约面**；③ 进程级事实（Nacos 实例数、调度池实测线程数、`applicationTaskExecutor` 实测参数）；④ **HeavyKeeper 概率分支的精确值**（原理上不可断言）。⇒ 均为 **NOT 行为漂移**，但前两项闭合需要 B 侧 Go 源与仓库根 env 文件 |
| **12** | 当前 C 是否具备可靠的行为对拍条件？ | **是，且已用过**。E-4 双侧活体对拍脚本（`tools/difftest/hot/run.sh`）第四轮 **EXIT=0**（真差异 0 · 已知现象 1 · 端口零残留）；域级 **77 例测试全绿**；本次另补 **2 条一次性运行探针**（classpath 取自 fat jar）。⚠️ 缺口：**G-1~G-8 的 8 条守护网**尚未补 ⇒ 对拍覆盖的是「当前实现」，不是「回归防护」 |

---

## 十三·给 hot lane 的收口清单（本报告**只建议、不执行**）

| 优先级 | 事项 | 归属 | 类型 |
|---|---|---|---|
| **P0** | **补 otel 段**（D-01）| 建议交 **`global` 收口域**统一处置（`comment`/`favorite`/`message`/`task` **同样零 otel 段** ⇒ 共 5 域）⇒ 域内只写 `service/hot/src/main/resources/application.yml`，**共享层不碰** | 配置补齐 |
| **P1** | 补 **G-1~G-5 + G-7** 六条守护网用例（并发/截断/续期/计数次序/分配策略/2048 闸门）| hot 域（`service/hot/src/test/`）| 测试补齐 |
| **P1** | 修 **D-05 / D-06** 三处 javadoc 措辞（`HotApiService:80-84`、`HotApiSupport:65-72`、`HotPartitionSketchRegistry:168-171`）| hot 域 | 文档订正 |
| **P2** | **D-02 / D-03 / D-04** 三项配置面取舍：`health.show-details` 取严、`actuator` 取并集、`env` 变量名双名兼容 | hot 域 | 配置取舍 |
| **P2** | 三个 `_INDEX.md` 并入主干（`docs/DRIFT/_INDEX.md` hot 行 · `docs/Intentional_Deviations/_INDEX.md` +hot 6 条 · `docs/Pitfalls/_INDEX.md` +hot 9 条）| **主会话**（共享面，本 lane 不得写）| 流程 |
| **P2** | **F-15** 交 `global` 收口域（共享层 `common/observability/` 的「HTTP 预算 × 业务失败」竞态 ⇒ 混血响应）| `global` | 共享层缺陷 |
| **待决** | **F-02** 是否恢复域内方向 2s 上界（恢复后可**同时**消掉时延差与混血形状）| 用户拍板（D2 既定决策的例外）| 决策 |
| **待决** | **F-11** 命名债统一清理 | 用户拍板 | 决策 |
