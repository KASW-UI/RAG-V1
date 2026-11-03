# task 域迁移审核报告（RAG Boot 4 → RAG-V1 Boot 3.5）

> **由 `align/task` 工作树产出** · **基线 commit `0a35865`**（本地 `main`）· 分支 `align/task`
> **核对日期**：2026-11-03
> **审核依据**：`.agents/verification.md`（八阶段 + 十四问）
> **配套文件**：[`vs-common.md`](vs-common.md)（本域 ↔ 共享层 `common/` 跨层对账）
> **写权**：本报告只写 `docs/DRIFT/task/**`；`docs/DRIFT/_INDEX.md` · `docs/Intentional_Deviations/_INDEX.md` ·
> `docs/Pitfalls/_INDEX.md` · `AGENTS.md` · `common/**` **一律不碰**（AGENTS.md §五.3 规则 3）
> ⚠️ **本轮同时改了 `docs/Mapping_Table/task/task.md` ＋ `docs/Mapping_Table/_INDEX.md` 的 task 行 ＋ `docs/File_Tree/task.md`**
> —— 前两者是**本域域文档**（article lane 既有惯例：只改自己域的行），后者由 `tools/gen-file-tree.py` 按实际文件系统生成。

---

## 一、审核范围

### 1.1 两个项目的角色

| 标识 | 项目 | 路径 | 作用 |
|---|---|---|---|
| **B** | RAG | `/Users/pr/Documents/java&golang/RAG`（只读） | **唯一行为基准**（D16）· Spring Boot **4.0.8** 形态 |
| **C** | RAG-V1 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/task` | 被审对象 · Spring Boot **3.5.11** 形态 |

> ⚠️ **B 侧「Go 风格」的真相**（AGENTS §3.1 自举陷阱的防线）：B 侧 `task-api` / `task-rpc` **全是 Java/Spring**，
> 类名带 `GoStyle*` 前缀、javadoc 里引用 `*.go:行号` 的是**历史移植痕迹**，**B 仓里没有 Go 源码**。
> ⇒ 本报告的每一条基准都来自 **B 侧 Java 源码本身**，不是 B 侧 javadoc 的转述。

### 1.2 审核的模块与文件规模（**实测**，非旧报告数字）

| 项 | B 侧 | C 侧 | 备注 |
|---|---|---|---|
| 模块数 | **3**（`task-api` / `task-rpc` / `task-proto`） | **1**（`service/task`，artifactId **`task-service`**）+ 根级 `proto/` | **D1** api+rpc 合并 + **D7** proto 集中化 |
| 主代码 `.java` | **39**（api **11** + rpc **28**） | **40** | C 列 40/40 = **100%**；B 列 39/39 = **100%**（`check-mapping-coverage.py` 实测） |
| 测试 `.java` | **35** | **11**（**7 个测试类 ＋ 4 个 support**） | 测试资产**另算一档**（§1.0c）<br>⚠️ **7 个测试类**里有一个是**本轮因变异验证新增**的（`messaging/TaskCountStoreRealRedisTest`）⇒ 见 §八·8.2 #11 |
| 迁移脚本 | **2**（`V1__create_task_progress.sql` / `V2__create_user_like_count.sql`） | **2** | ✅ **逐字复制**（`diff -r` 零差异） |
| 配置文件 | `task-api/application.yml` ＋ `task-rpc/application.yml` | `service/task/src/main/resources/application.yml`（**1 份 · 25 KB**） | 两份合一 |
| 子包 | 12 个（`api.{advice,client,config,controller,dto,service}` + `rpc.{bootstrap,config,domain,mapper,mq,service,service/impl}`） | **10 个**（`advice` / `config` / `controller` / `domain.{entity,service}` / `dto/task` / `grpc` / `mapper` / `messaging` / `service` / `wiring`） | D7 包压平；`client/` 因 D2 域内直连而消失 |

> 📌 **C 列 40 > B 列 39 的唯一来源**：`messaging/TaskTraceAttributes.java` —— B 侧 `mq/TaskTraceAttributes.java`
> 是**包级静态类**（`final class` + 私有构造），C 侧提为**独立 `public final class`** ⇒ **可见性放宽、行为零变化**
> （4 个 attribute 的名字 / 取值 / `LinkedHashMap` 写入顺序逐条照抄）。

### 1.3 版本口径（**实际解析值**，非 POM 声明值）

| 组件 | B 侧 | C 侧 | 判定 |
|---|---|---|---|
| Spring Boot | **4.0.8**（`pom.xml:8-9` `spring-boot-starter-parent`） | **3.5.11** | ✅ D14 既定决策 |
| Spring Framework（webmvc） | 7.0.10 | **6.2.16** | ✅ 由 Boot 3.5.11 BOM 锁定 |
| Tomcat | 11.x | **10.1.52** | ✅ 本域触及的容器旋钮 10.1 全支持 |
| Jackson | **3.1.5**（`tools.jackson.*`） | **2.19.4**（`com.fasterxml.jackson.*`） | ✅ D14；**代码层 0 处 `tools.jackson` 引用** |
| Spring Data Redis | 4.x 线 | **3.5.9** | ✅ |
| Spring Kafka | Boot 4 的 `spring-boot-kafka` 模块 | **3.3.13**（`org.springframework.kafka:spring-kafka`） | ✅ D14：Boot 4 专有 artifact 在 3.5 **不存在** ⇒ 换成底层坐标 |
| Flyway | **10.22.0**（根 pom `flyway.version`） | **11.14.1** | ✅ 显式覆盖 BOM 的 11.7.2 |
| MyBatis-Plus | 3.5.17 | **3.5.17** | ✅ 同版本 |
| gRPC（net.devh / grpc-java） | 3.1.0 / 1.63.0 | **3.1.0 / 1.63.0** | ✅ 同版本 |
| Nacos client | 3.0.3 | **3.0.3** | ✅ |
| PostgreSQL driver | — | **42.7.10** | — |
| Testcontainers | — | **1.21.4** | 由 Boot BOM 管理 |

> ✅ **零 Boot 4 专有 artifact**（`spring-boot-kafka` / `spring-boot-flyway` / `spring-boot-data-redis` / `spring-boot-tomcat`）：
> 本地 `~/.m2` 实证这 4 个 artifact **只有 4.x** ⇒ 在 3.5 线**客观不存在** ⇒ C 侧不引是**必需**而非可选。
> ⚠️ **读数口径**：`mvn -pl service/task dependency:list`（**不加 `-am`**）会报 5 条
> `'dependencies.dependency.version' … is missing` ⇒ **那不是本域缺陷** —— 根因是本地 `~/.m2` 里
> `com.rag:common:1.0-SNAPSHOT` 的 **POM 是 11-02 的旧件**，单模块解析时读不到根 pom 的 `dependencyManagement`。
> ⇒ **本报告所有回归读数都取自 `mvn -o -pl service/task -am test`（带 `-am`，走 reactor）**。

### 1.4 使用的资料与代码版本

| 项 | 值 |
|---|---|
| Mapping_Table | [`docs/Mapping_Table/task/task.md`](../../Mapping_Table/task/task.md)（**397 行 · 56 行映射 · C 列 40/40 · B 列 39/39**） |
| File_Tree | [`docs/File_Tree/task.md`](../../File_Tree/task.md)（**本轮重跑生成器刷新**） |
| 决策记录 | [`docs/决策记录.md`](../../决策记录.md)（D1 / D2 / D3 / D5 / D7 / D11 / D13 / D14 / D16 / D17 / D18） |
| 基础 commit | 基线 `0a35865`（本地 `main`）· 本域实现提交 **`ef4fb5d`**（分支 `align/task`）（`git show --stat ef4fb5d`：**61 个文件 · ＋9074 / −210 行**） |
| 架构断言 | [`docs/架构符合性断言.md`](../../架构符合性断言.md)（A1–A13） |

### 1.5 本轮的验证读数（**全部真跑**）

| 读数 | 值 | 命令 |
|---|---|---|
| **task 域回归** | **52 例 · Failures 0 · Errors 0 · Skipped 0**（**含 M-4 判据修复后新增的 `TaskCountStoreRealRedisTest` 3 例**） | `mvn -o -pl service/task -am test` |
| common 回归 | **241 例 · Failures 0 · Errors 0 · Skipped 0** | 同上（`-am` 带上游） |
| **全仓合计** | **293 例 · 0/0/0 · Skipped 0 · BUILD SUCCESS**（52 ＋ 241） | 取回归日志的**模块级汇总**（AGENTS §五.8 规则 ①：<br>`[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0` / `… 241 …`） |
| 守门 ① | `check-mapping-coverage.py` ⇒ **EXIT=0**（**全域 C 列 643/643 · B 列 752/752 · B 列全局未覆盖 0**）<br>task 行 **C 40/40 · B 39/39 · 100% ✅** | `python3 tools/check-mapping-coverage.py` |
| 守门 ② | `check-arch-invariants.py` ⇒ **EXIT=0**（PASS 10 · FAIL 0 · N/A 2 · PENDING 1） | `python3 tools/difftest/check-arch-invariants.py` |
| 守门 ③ | `check-docs-vs-tree.py` ⇒ **EXIT=0** | `python3 tools/difftest/check-docs-vs-tree.py` |
| **真触发测试** | **6 个测试类 / 50 例**，含 **真 PostgreSQL（Testcontainers `postgres:16-alpine`）**、**真 Redis（`redis:7-alpine`）**、**真 Tomcat（随机端口裸 HTTP）** | 见 §二 各行「证据」列 |
| **变异验证** | **4 组 · 全部「变异红 / 还原绿」双跑**（详见 §八） | 见 §八·8.2 |

> 📌 **本轮回归数字的归因**：
> · **task 52 例** ＝ **7 个**测试类：wire 契约 13 ＋ DTO 归一 10 ＋ Kafka 装配 6 ＋ Mapper 真 PG 9 ＋ Redis 管线 **5** ＋ **计数器命名空间 3** ＋ 上下文自检 6。
> · **common 241 例** ＝ **基线数字**，本域**没有改 `common/**` 一行**（写权边界）。
> · ⇒ 两处数字都取自**同一条命令的模块级汇总行**，**不取** `surefire-reports/*.xml` 求和（AGENTS §五.8 规则 ①）。

---

## 二、B → C 责任承接

> 📌 **口径**：「承接表条目」= **B 侧职责级**（不是文件级）—— B 侧 **39** 个主类 **＋** 1 条 C 侧新增 = **40** 条。
> ⚠️ **B 侧 `TaskApiApplication` 的 `main` 与 `TaskRpcApplication` 的 `main` 合并成 1 个入口 ⇒ 那 2 条职责合并成 1 条承接**
> （映射类型标 `N:1`）—— 这是**本表唯一一处「B 侧 2 个文件 ⇒ C 侧 1 个文件」**，其余全部 1:1 或 1:N。

### 2.1 HTTP / API 侧（B 侧 `task-api` 11 类 + 1 条 C 新增 ⇒ 12 条）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|---|
| A-01 | `task-api/.../TaskApiApplication.java` | api 进程入口（`main` ＋ `SpringApplication.run`） | `TaskServiceApplication.java#main`（**与 rpc 侧入口合并**） | **N:1** | ✅ | **PASS** | `TaskServiceApplicationTests`（上下文真起） |
| A-02 | `task-api/.../advice/GlobalExceptionHandler.java` | 9 类异常 → 各自裸 text/plain 出口 | `advice/TaskApiExceptionHandler.java`（7 个 `@ExceptionHandler`） | 1:1 | ✅ | **PASS** | 真 Tomcat 逐条：400 / 404 / 405 / 415 / 500（`TaskApiWireContractRealTomcatTest`） |
| A-03 | 同上（`:86-95` gRPC 分支） | gRPC status → HTTP 状态码 | `TaskApiExceptionHandler#handleGrpcStatus` | 1:1 | ✅ | **PASS** | 15 分支映射照抄 B 侧 `:101-137`；共享层 `GrpcStatusMapper` 同源 |
| A-04 | 同上（`:290-294`） | **兜底 = 400，不是 500** | `TaskApiExceptionHandler#handleAny` | 1:1 | ✅ | **PASS** | 逐字照抄（异常不是 500） |
| A-05 | 同上（`:318-328`） | 沿 cause 链找 Go 原文 | `TaskApiExceptionHandler#goMessage` | 1:1 | ✅ | **PASS** | 同上 |
| A-06 | `task-api/.../config/TaskApiRawBodyConfig.java` | 换掉 Spring 默认 `RequestResponseBodyMethodProcessor` | `config/TaskApiRawBodyConfig.java` | 1:1 | ✅ | **PASS** | 真 Tomcat：`Content-Type: text/plain` 也 200 |
| A-07 | `task-api/.../config/GoStyleRawBodyArgumentProcessor.java` | 自造 `HttpInputMessage`（body 在转换器之前换掉） | `config/GoStyleRawBodyArgumentProcessor.java` | 1:1 | ✅ | **PASS** | 同上 |
| A-08 | `task-api/.../config/TaskApiJsonBodyConfig.java` | 让 `@RequestBody` **无视 `Content-Type`** | `config/TaskApiJsonBodyConfig.java`（`@Configuration("seaJsonBodyConverterConfigurer")`） | 1:1 | ✅ | **PASS** | 真 Tomcat：**不写 `Content-Type` 也是 200**（若共享层未退让则是 400） |
| A-09 | `task-api/.../controller/TaskController.java` | `POST /taskcenter/v1/task/get` | `controller/TaskController.java#getTask`（`:78` `@RequestMapping("/taskcenter/v1")` · `:103` `@PostMapping("/task/get")`） | 1:1 | ✅ | **PASS** | 前缀逐字比对（**`taskcenter` 不是 `task`**） |
| A-10 | `task-api/.../dto/GetTaskReq.java` | `id` 接受 string\|number；两条错误文案 | `dto/task/GetTaskReq.java`（**immutable record** · D5） | 1:1 | ✅ | **PASS** | `GetTaskReqNormalizationTest` 10 例 + wire 契约 13 例 |
| A-11 | `task-api/.../dto/GetTaskResp.java` ＋ `dto/TaskInfo.java` | 响应体 `{"tasks":[…]}`，**无信封**，空列表输出 `[]` | `dto/task/GetTaskResp.java` ＋ `dto/task/TaskInfo.java` | 1:1 | ✅ | **PASS** | 真 Tomcat 断言成功体**无信封** |
| A-12 | **无对应物**（B 侧 `task-api` **无任何 `Properties` 类**，配置全在 yml） | `task.reward` 配置块的显式载体 | `config/TaskRewardProperties.java`（prefix `task.reward`，**键名逐字不变**） | **C 新增** | ✅ | **PASS** | 绑定断言 + `task.reward.*` 真消费点（`TaskRewardPublisher`） |

### 2.2 RPC 侧 —— 领域层 / 持久层 / 消息面（B 侧 `task-rpc` 28 类 ⇒ 28 条）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|---|
| R-01 | `task-rpc/.../TaskRpcApplication.java` | rpc 进程入口 | `TaskServiceApplication.java`（与 A-01 合并） | **N:1** | ✅ | **PASS** | 同 A-01 |
| R-02 | 同上（`:185-193`） | 自建 `ConcurrentKafkaListenerContainerFactory` | `TaskServiceApplication.java#kafkaListenerContainerFactory` | 1:1 | ✅ | **PASS** | `TaskKafkaListenerAssemblyTest`（ack mode = **RECORD**） |
| R-03 | 同上（`:202-216`） | `noCommitErrorHandler`（不重试 / 不 seek / 不 ack / 不 commitRecovered） | `TaskServiceApplication.java#noCommitErrorHandler` | 1:1 | ✅ | **PASS** | 逐条照抄（`FixedBackOff(1000L, 1L)` ＋ 三个 `setXxx(false)`） |
| R-04 | `task-rpc/.../bootstrap/NacosRegistration.java` | 显式注册到 Nacos（服务名 `task-rpc`） | `wiring/NacosRegistration.java#register` / `#deregister` | 1:1 | ✅ | **PASS** | `register-enabled:false` ＋ 手写注册（Bean 名显式指定，防撞名） |
| R-05 | `task-rpc/.../bootstrap/TaskSchemaGate.java` | 启动闸（两张表存在性） | `wiring/TaskSchemaGate.java#verifySchema` | 1:1 | ✅ | **PASS** | `TaskServiceApplicationTests`（两表存在） |
| R-06 | `task-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | 显式 Jackson 2 `ObjectMapper`（关 `FAIL_ON_UNKNOWN_PROPERTIES` ＋ `LogicalType.Textual` 强转 `Fail`） | **不建域内同名类** ⇒ 落共享层 `common/web/Coercion.java` ＋ `JsonBodyConverterAutoConfiguration` | 1:N | ✅ | **PASS** | D14：Boot 3.5 的 MVC mapper **本身就是 Jackson 2** ⇒ 域内不重复定义（否则顶掉 Boot 的） |
| R-07 | `task-rpc/.../config/TaskKafkaTopics.java` | **5 个 topic ＋ 7 个 consumer group** | `messaging/TaskKafkaTopics.java` | 1:1 | ✅ | **PASS** | `TaskKafkaListenerAssemblyTest` 逐条比对 topic/group |
| R-08 | `task-rpc/.../config/TaskSinkProperties.java` | `task.definitions.*` 绑定（两条任务定义 ＋ sink 参数） | `config/TaskSinkProperties.java` | 1:1 | ✅ | **PASS** | 绑定断言 |
| R-09 | `task-rpc/.../domain/TaskProgressEntity.java` | `task_progress` 表实体（复合主键 `user_id`+`task_id`） | `domain/entity/TaskProgressEntity.java` | 1:1 | ✅ | **PASS** | `@TableName` 断言 + 真 PG |
| R-10 | `task-rpc/.../domain/UserLikeCountEntity.java` | `user_like_count` 表实体（**`user_id` 是 `String`**） | `domain/entity/UserLikeCountEntity.java` | 1:1 | ✅ | **PASS** | 同上（⚠️ 与 `task_progress.user_id`（`Long`）**类型不同** —— 继承 B 侧） |
| R-11 | `task-rpc/.../mapper/TaskProgressMapper.java` | 4 组 SQL（`GREATEST` / `CASE` / `COALESCE` / 常量覆盖） | `mapper/TaskProgressMapper.java` | 1:1 | ✅ | **PASS** | `TaskProgressMapperRealPgTest` 9 例（真 PG） |
| R-12 | `task-rpc/.../mapper/UserLikeCountMapper.java` | `user_like_count` 的 upsert | `mapper/UserLikeCountMapper.java` | 1:1 | ✅ | **PASS** | 同上 |
| R-13 | `task-rpc/.../mq/UserLikeProgressSink.java` | 用户路 sink（`@KafkaListener` + 1s `@Scheduled` flush + Redis pipeline + PG） | `messaging/TaskUserLikeProgressSink.java`（`#onMessage` / `#flushTick` / `#flushOnShutdown`） | 1:1 | ✅ | ⚠️ **PARTIAL** | 装配层已证（registry 断言）· **消费面真跑未做**（见 §七·7-1） |
| R-14 | `task-rpc/.../mq/ArticleLikeProgressSink.java` | 文章路 sink（同上） | `messaging/TaskArticleLikeProgressSink.java` | 1:1 | ✅ | ⚠️ **PARTIAL** | 同上 |
| R-15 | `task-rpc/.../mq/TaskUserCountConsumer.java` | 用户路计数消费者 | `messaging/TaskUserCountConsumer.java` | 1:1 | ✅ | ⚠️ **PARTIAL** | 装配层已证 · 消费面真跑未做 |
| R-16 | `task-rpc/.../mq/TaskArticleCountConsumer.java` | 文章路计数消费者 | `messaging/TaskArticleCountConsumer.java` | 1:1 | ✅ | ⚠️ **PARTIAL** | 同上 |
| R-17 | `task-rpc/.../mq/TaskRawDiagnosticConsumer.java` | 「什么都不做但不能删」的诊断消费者 | `messaging/TaskRawDiagnosticConsumer.java` | 1:1 | ✅ | ⚠️ **PARTIAL** | 同上 |
| R-18 | `task-rpc/.../mq/TaskRawLogRouter.java` | 原始日志路由（按 key 前缀分发） | `messaging/TaskRawLogRouter.java` | 1:1 | ✅ | ⚠️ **PARTIAL** | 同上 |
| R-19 | `task-rpc/.../mq/TaskTraceAttributes.java` | 两个 sink 的 OTEL span 属性（**包级静态类**） | `messaging/TaskTraceAttributes.java`（**独立 `public final class`**） | 1:1 | ✅ | **PASS** | 4 个 attribute 名字 / 取值 / 顺序逐条照抄 |
| R-20 | `task-rpc/.../mq/TaskCountStore.java` | 按 group 分目录的 KV 表（**Redis `INCR`**） | `messaging/TaskCountStore.java` | 1:1 | ✅ | **PASS** | `TaskRedisPipelineRealRedisTest`（真 Redis 验 group 命名空间隔离） |
| R-21 | `task-rpc/.../mq/TaskLogEvent.java` | 原始日志事件（**7 字段** ＋ 宽松解析） | `messaging/TaskLogEvent.java` | 1:1 | ✅ | **PASS** | 逐字段照抄 |
| R-22 | `task-rpc/.../mq/TaskArticleProgressEvent.java` | 文章进度事件（`@JsonProperty("user_id")`） | `messaging/TaskArticleProgressEvent.java` | 1:1 | ✅ | **PASS** | 同上 |
| R-23 | `task-rpc/.../mq/GoJson.java` | Go 兼容 JSON 解析（6 个宽松原语） | `messaging/TaskGoCompatibleJson.java`（**去掉 `Go` 前缀**） | 1:1 | ✅ | **PASS** | 逐方法照抄 |
| R-24 | `task-rpc/.../service/RedisPipelines.java` | Redis pipeline 封装 | `service/RedisPipelines.java` | 1:1 | ✅ | **PASS** | `TaskRedisPipelineRealRedisTest` 6 例（真 Redis） |
| R-25 | `task-rpc/.../service/TaskDefs.java` | **常量表**（`ALL_TASK_DEFS` 两条字面量，**无 `task_def` 表**） | `service/TaskDefs.java` | 1:1 | ✅ | **PASS** | 两条定义逐字比对（`190/3` ＋ `1902/5`） |
| R-26 | `task-rpc/.../service/TaskDomainService.java` | 唯一的 gRPC 用例（Redis 读 ＋ PG 回源） | `domain/service/TaskDomainService.java#getTask` | 1:1 | ✅ | **PASS** | Redis value 编码 `"status\|progress\|target"` 逐字照抄 |
| R-27 | `task-rpc/.../service/TaskProgressWriter.java` | 进度写库（`lazyInit` / `updateDoing` / `markDone`） | `service/TaskProgressWriter.java`（同名三方法） | 1:1 | ✅ | **PASS** | `TaskProgressMapperRealPgTest` 9 例 |
| R-28 | `task-rpc/.../service/TaskRedisKeys.java` | **10 个 Redis key ＋ TTL** | `service/TaskRedisKeys.java`（10 个静态方法） | 1:1 | ✅ | **PASS** | 10 个 key 模板逐条比对（B `:69,82,93,104,114,128,138,150,158,173`） |
| R-29 | `task-rpc/.../service/TaskRewardPublisher.java` | Redis Stream 生产端（`reward_id = uid + taskID` 加法） | `messaging/TaskRewardPublisher.java#enqueue` / `#streamKey` | 1:1 | ✅ | **PASS** | 加法语义**原样保留**（含天然碰撞） |
| R-30 | `task-rpc/.../service/impl/TaskServiceGrpcImpl.java` | gRPC 用例分发（invoke 模板 ＋ 两条错误出口） | `grpc/TaskGrpcService.java#getTask` | 1:1 | ✅ | **PASS** | 逐条照抄 |

### 2.3 承接表汇总（**机器复算**）

| 分类 | 条目数 | PASS | PARTIAL | INTENTIONALLY_CHANGED | NOT_VERIFIED | FAIL | NOT_IMPLEMENTED |
|---|---|---|---|---|---|---|---|
| HTTP / API 侧（A-01..A-12） | 12 | **12** | 0 | 0 | 0 | 0 | 0 |
| RPC 侧（R-01..R-30） | 30 | **24** | **6** | 0 | 0 | 0 | 0 |
| **合计** | **42** | **36** | **6** | **0** | **0** | **0** | **0** |

> 📌 **计数是机器复算的，不是手数的**（AGENTS §3.3 纪律）：
> ```bash
> python3 - <<'PY'
> import re, pathlib
> from collections import Counter
> t = pathlib.Path("docs/DRIFT/task/verification-task-service-2026-11-03.md").read_text(encoding="utf-8")
> sec = t.split("### 2.3 承接表汇总")[0]
> rows = re.findall(r'^\| ((?:A|R)-\d+) \|.*?\|\s*(?:✅|⚠️)?\s*\*\*(PASS|PARTIAL|INTENTIONALLY_CHANGED|NOT_VERIFIED|NOT_IMPLEMENTED|FAIL)\*\*', sec, re.M)
> print(len(rows), dict(Counter(s for _, s in rows)))
> PY
> ```
> ⚠️ **口径说明**：
> ① **42 条 ≠ B 侧 39 个文件** —— 因为 **R-01 与 A-01 是同一份「入口类职责」**（两个 `main` 合并成 1 个入口），
>    本表**把两个入口拆成两条**以免「合并降低审核覆盖率」（verification.md §3.2）；
>    反向地，**B 侧 `GlobalExceptionHandler` 一个类被拆成 A-02..A-05 四条职责**（异常分支是独立职责）。
>    ⇒ **B 侧 39 类 ⇒ 本表 42 条职责**，**覆盖不降反升**。
> ② **6 条 `PARTIAL`** ＝ R-13..R-18（**Kafka 消费面 6 个 listener**）⇒ **装配层已证**（真进 registry），
>    **消费面真跑未做**（无 broker）⇒ 见 §七·7-1。**不是**行为漂移，是**覆盖缺口**。
> ③ **0 条 `INTENTIONALLY_CHANGED`** —— 本域的**表达层差异全部落在 §四 / §五**（端口、ack mode 等），
>    **不在职责承接层**。⇒ **两表不相加**。

---

## 三、多文件合并 / 拆分 / 架构调整专项（verification.md §8）

### 3.1 全部 N:1 映射（**2 条**）

| # | B 侧多文件 | C 侧承接 | 逐项核对结论 |
|---|---|---|---|
| N-1 | `task-api/.../TaskApiApplication.java`（HTTP 进程入口）<br>＋ `task-rpc/.../TaskRpcApplication.java`（gRPC 进程入口） | `TaskServiceApplication.java`（**唯一入口**，`@SpringBootApplication` ＋ `@EnableDiscoveryClient`） | ✅ **两个 `main` 合并成 1 个**（D1）⇒ **HTTP 端口 8890 ＋ gRPC 端口 9006 在同一个进程里**。<br>⚠️ **合并带来的行为面**：B 侧 api 进程**只有 HTTP**、rpc 进程**只有 gRPC**；C 侧**同进程双协议** ⇒ **端口是唯一仍需登记的 L1 差异**（§五·DRIFT-TASK-002）。<br>⇒ 覆盖不降：两个入口的**全部职责**（`@EnableDiscoveryClient` / `@MapperScan` / `@EnableConfigurationProperties` / `kafkaListenerContainerFactory` / `noCommitErrorHandler`）**逐条落在同一个类里**（`TaskServiceApplicationTests` 6 例自检）。 |
| N-2 | `task-api/src/main/resources/application.yml`（**391 行**）<br>＋ `task-rpc/src/main/resources/application.yml`（**476 行**） | `service/task/src/main/resources/application.yml`（**1 份 · 25 KB**） | ✅ **两份合成一份**：<br>· `server.port: 8890`（B api `18886`）· `grpc.server.port: 9006`（B rpc `9005`）—— **端口差异登记在 §五**；<br>· `spring.flyway.table: flyway_schema_history_task` ＋ `baseline-on-migrate: true` ＋ `baseline-version: 0`（**独立账本 ⇒ 不撞号**）；<br>· `task.kafka.*`（5 topic ＋ 7 group）· `task.definitions.*`（两条任务定义 ＋ sink 参数）· `task.reward.*`；<br>· `observability.http-timeout: 3s` ＋ `observability.rpc-timeout: 2s`（**显式**，不是隐式默认）；<br>· `rag.http.go-form-limit.mode: "off"`（B 侧 api yml 的自标）。 |

### 3.2 全部 1:N 映射（**关键 4 条**；共 12 条）

| # | B 侧单文件 | C 侧承接 | 逐项核对结论 |
|---|---|---|---|
| 1-N-1 | `task-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | 共享层 `common/web/Coercion.java` ＋ `common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | ✅ **上收共享层**（D3）—— **不建域内同名类**。<br>⚠️ **为什么必须不建**：Boot 3.5 的 MVC mapper **本来就是 Jackson 2**；域内再 `@Bean` 一个 `ObjectMapper` 会**顶掉 Boot 的那个** ⇒ 影响**全域的 JSON 行为**。<br>⇒ 本域三条 JSON 路径（`readTree` 手工判定 × 2 ＋ 序列化 × 1）逐条核过，**都不受 coercion 配置影响**。 |
| 1-N-2 | `task-api/.../service/TaskGetService.java` | `service/TaskApiService.java#getTask` ＋ `service/TaskApiSupport.java`（`protoGetTaskReq` / `toTaskInfo` / `asGrpcStatus`） | ✅ **按「用例内聚」拆成 Service + Support**（B 侧一个类做 3 件事）。<br>⚠️ **拆分的红线**：**错误翻译模板必须共享** —— `TaskApiSupport.asGrpcStatus` **同时供 HTTP 路径与 gRPC 路径使用** ⇒ 否则同一 DB 故障会出现「B 500 / C 400」。<br>⇒ **空列表输出 `[]` 绝不输出 `null`**（B 侧 `out := make([]types.TaskInfo, 0, len(...))`）。 |
| 1-N-3 | `task-rpc/.../mq/UserLikeProgressSink.java` ＋ `ArticleLikeProgressSink.java`（2 个 sink） | `messaging/TaskUserLikeProgressSink.java` ＋ `messaging/TaskArticleLikeProgressSink.java` ＋ `service/TaskProgressWriter.java` ＋ `service/TaskRedisKeys.java` ＋ `service/RedisPipelines.java` ＋ `messaging/TaskRewardPublisher.java` | ✅ **两个 sink 共享的 5 个协作者被提出来**（B 侧也是这 5 个共享类）。<br>⚠️ **B 侧两份真相**：`TaskDefs` 的两条定义 与 sink 内部硬编码的常量 **不一致**（文章路奖励分值 **5** 而 target 是 **3**）⇒ **原样继承**（§五·DRIFT-TASK-006）。 |
| 1-N-4 | `task-rpc/.../mq/GoJson.java`（**6 个宽松 JSON 原语**） | `messaging/TaskGoCompatibleJson.java`（**去掉 `Go` 前缀**） | ✅ **1:1 照抄**，只是改名（`GoJson` 是 B 侧的**历史移植痕迹**，不是 Go 实现）。<br>⇒ 6 个方法逐个对照（B `:26-28 / :48-50 / :60-62 / :71-80 / :91-103 / :108-110 / :119-127`）。 |

### 3.3 API + RPC 合并（verification.md §8.3）

| 维度 | B 侧 | C 侧 | 结论 |
|---|---|---|---|
| 进程数 | **2**（`task-api` HTTP `18886` ＋ `task-rpc` gRPC `9005`） | **1**（`task-service` HTTP `8890` ＋ gRPC `9006`） | ✅ D1 裁决；**端口差异 = L1，登记在 §五·DRIFT-TASK-002** |
| 域内 gRPC 调用 | `@GrpcClient("task-rpc")` → `TaskRpcClient`（**跨进程**） | **域内直调** `domain/service/TaskDomainService` | ✅ D2 裁决 ⇒ **C 侧无 `client/` 包、无 `TaskRpcClient`**、**不配 `grpc.client.task-rpc.*`**（会指向自己） |
| 错误传播 | api 侧靠 `StatusRuntimeException` 跨进程 → `@ExceptionHandler` 转 HTTP | **同进程同链路** ⇒ `StatusRuntimeException` 仍从 gRPC 层抛出 | ✅ **同一套异常翻译模板共享**（`TaskApiSupport.asGrpcStatus`）⇒ **不会**出现「同一故障 B 500 / C 400」 |
| 后台任务落点 | `mq/` 的 11 个消费者**在 `task-rpc` 这一个进程内** | `messaging/` 的 12 个类**在同一个 `task-service` 进程内** | ✅ **不新增 app**（D1 对本域成立，无例外） |

### 3.4 Common Starter 共享能力上收（verification.md §8.4）

| B 侧能力 | C 侧落点 | 写权 |
|---|---|---|
| `common/spring/RpcServerInterceptorFactory` ＋ **显式** `GrpcServerConfigurer.intercept(...)` | **`common/autoconfigure/ProtectionAutoConfiguration.java:231-240`** 的 `seaGrpcServerConfigurer` | 🔴 **`common/**` 只读** ⇒ 本域**不写**，**直接用共享层已有的** ⇒ 守门 **A3 PASS** |
| `common/http/GoStylePlainTextErrors.java` | `common/web/PlainTextErrors.java` | 同上（已存在） |
| `common/http/GoStyleRouterErrorResponses.java` | `common/web/RouterErrorResponses.java` | 同上 |
| `common/errcode/CodeFromGrpcError`（**15 分支**） | `common/web/GrpcStatusMapper.java` | 同上 |
| `common/protection/GoStyleMaxBytesFilter.java` | `common/protection/MaxBytesFilter.java` | 同上 |
| `common/http/GoStyleStringInt64`（跨域复用） | `common/web/StringInt64.java` | 同上 |

> ✅ **本域消费共享层 5 个类**（`grep -rhoE 'import com\.rag\.common\.[A-Za-z0-9_.]+'` 去重）：
> `observability/MqConsumer` · `protection/ServiceBusyException` · `web/GrpcStatusMapper` · `web/PlainTextErrors` · `web/RouterErrorResponses`
> ⇒ 消费面 **3 个文件**（`advice/TaskApiExceptionHandler` · `messaging/TaskUserLikeProgressSink` · `messaging/TaskArticleLikeProgressSink`）。
> 详见 [`vs-common.md`](vs-common.md)。

### 3.5 独立进程合并（verification.md §8.5）

✅ **本域无独立进程合并**（B 侧 `mq/` 的消费者本来就**不是独立进程**，是 `task-rpc` 里的 `@KafkaListener` 容器）
⇒ D1 合并**不产生**额外的进程语义变化。

### 3.6 职责缺失（**逐条自查**）

| 检查项 | 结论 |
|---|---|
| B 侧 39 个主类是否都有 C 侧落点 | ✅ **39/39**（`check-mapping-coverage.py` B 列实测） |
| C 侧是否有 B 侧没有的职责 | ⚠️ **1 条**：A-12 `TaskRewardProperties`（B 侧**无 Properties 类**，配置全在 yml）—— **C 新增的表达层载体**，键名逐字不变 |
| D2 删除的 `TaskRpcClient` 是否造成职责缺失 | ✅ **否** —— 职责由 `domain/service/TaskDomainService` 承接 |
| B 侧 `task-api` 的 `Auth` 段 | ✅ **零消费点**（死配置）⇒ **不迁**（迁了就是新增职责） |
| B 侧 `task-rpc` 的 `LikeRedis` 段 | ✅ **本服务里没有 like** ⇒ 历史遗留，**不迁** |

---

## 四、框架版本差异报告（verification.md §7）

### 4.A 依赖体系

| 项 | B 侧 | C 侧 | 具体变化 | 影响执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 结论 |
|---|---|---|---|---|---|---|---|---|
| Boot 大版本 | 4.0.8 | 3.5.11 | 框架基线降级 | 全域 | ❌ 不影响（除已登记项） | D14 既定 | 全量回归 291 例 0/0/0 | ✅ |
| Kafka starter | `spring-boot-kafka`（Boot 4 专有模块） | `org.springframework.kafka:spring-kafka` 3.3.13 | artifact 坐标换 | Kafka 消费面 | ❌ 不影响 | D14 | `TaskKafkaListenerAssemblyTest`（6 listener 真进 registry） | ✅ |
| Flyway starter | `spring-boot-flyway`（Boot 4 专有模块） | `flyway-core` + `flyway-database-postgresql` 11.14.1 | artifact 坐标换 ＋ 版本升 | 迁移面 | ❌ 不影响 | D14 | `TaskServiceApplicationTests`（账本 V1/V2 success） | ✅ |
| Jackson | 3.1.5（`tools.jackson.*`） | 2.19.4（`com.fasterxml.jackson.*`） | 大版本换 | JSON 序列化面 | ❌ 不影响 | D14 | `GetTaskReqNormalizationTest` ＋ wire 契约测试 | ✅ **代码层 0 处 `tools.jackson`** |

### 4.B 自动配置与 Bean 装配（**「Bean 存在」≠「机制生效」**）

| # | 机制 | B 侧 | C 侧 | 具体变化 | 适配方式 | 验证证据 | 结论 |
|---|---|---|---|---|---|---|---|
| 4.B-1 | **共享层 JSON 转换器退让** | B 侧 `TaskApiJsonBodyConfig:42-52` **明说必须排除共享层 `GoStyleJsonBodyConverterConfig`** | C 侧共享层 `JsonBodyConverterAutoConfiguration:91-92` 用 `@ConditionalOnMissingBean(name="seaJsonBodyConverterConfigurer")` ⇒ 域内**同名 bean** 即可退让 | 用**同名 bean**（`@Configuration("seaJsonBodyConverterConfigurer")`）替代「排除 auto-config」 | ⚠️ **不用 `spring.autoconfigure.exclude`**（会连带关掉 `seaCoercionMapperCustomizer`） | **真 Tomcat**：带 `Content-Type: text/plain` 与**不带 Content-Type** 都返回 **200**<br>**变异 M-1**：改掉本域的 bean 名 ⇒ 目标用例**立刻转红**（400）⇒ **这条判据有牙齿** | ✅ **实测生效**（`TaskApiWireContractRealTomcatTest`）<br>🔴 **机制（M-1 实测坐实）**：共享层 `JsonBodyConverter.canRead` **无条件返回 `true`**（`common/web/JsonBodyConverter.java:450-460`）⇒ 它在转换器列表里**抢先**被选中；真正的丢弃发生在 **`read()` 内部**的 `JsonBodyConverterConfig.goReadsBody(contentType, contentLength)` 判据（`:285-291`）⇒ `text/plain` ⇒ **丢弃 body** ⇒ 400。<br>⇒ 所以「同名 bean 让位」的作用是让共享层那个 `WebMvcConfigurer` **根本不被创建** ⇒ 共享转换器**不注册** ⇒ 域内转换器独占读侧。<br>⚠️ **耦合仍在**：共享层若改 bean 名 ⇒ **静默失效**（400 取代 200）⇒ **这条用例 ＋ M-1 是该耦合的唯一防线** |
| 4.B-2 | **gRPC 服务端拦截器链** | B 侧 `TaskRpcApplication:130-139` **显式** `rpcInterceptorConfigurer`（因为 devh starter **不会**自动注册用户提供的 `ServerInterceptor`） | C 侧共享层 `ProtectionAutoConfiguration:231-240` 的 `seaGrpcServerConfigurer` **真调** `server.intercept(...)` ⇒ **域内不写** | **从「域内显式接线」变成「共享层统一接线」** | 域内**零重复** | **守门 A3 PASS**：「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」 | ✅ |
| 4.B-3 | **`@KafkaListener` 装配** | B 侧 `TaskRpcApplication:185-193` **自建** `ConcurrentKafkaListenerContainerFactory` ⇒ `spring.kafka.listener.*` 一整族键**无消费点** | C 侧**同样自建**（保持 B 侧同形）⇒ **同样** `spring.kafka.listener.*` **无消费点** | — | 保持 B 侧形态 | `TaskKafkaListenerAssemblyTest`（6 listener 真进 `KafkaListenerEndpointRegistry` · topic/group 逐条对齐） | ✅ **两侧同形**（§五·DRIFT-TASK-004 登记差异） |
| 4.B-4 | **`@MapperScan`** | B 侧 `TaskRpcApplication` 声明 | C 侧 `TaskServiceApplication` 声明 | 包名不同 | — | `TaskServiceApplicationTests` | ✅ |

### 4.C Web / Servlet / HTTP 行为

| # | 项 | B 侧 | C 侧 | 影响 | 是否影响业务行为 | 验证证据 |
|---|---|---|---|---|---|---|
| 4.C-1 | **裸 JSON 读 body（不看 `Content-Type`）** | 三件配置（`TaskApiRawBodyConfig` + `GoStyleRawBodyArgumentProcessor` + `TaskApiJsonBodyConfig`） | 三件都在（逐条照抄） | — | ❌ 不影响 | 真 Tomcat：200（不是 415/400） |
| 4.C-2 | **404 响应体** | `404 page not found\n`（text/plain） | 同（共享层 `RouterErrorResponses`） | — | ❌ 不影响 | 真 Tomcat 逐字节断言 |
| 4.C-3 | **405 响应** | 裸状态码 ＋ `Allow` 头 ＋ **无 body 无 Content-Type** | 同 | — | ❌ 不影响 | 真 Tomcat 逐条断言 |
| 4.C-4 | **Tomcat 10.1 vs 11** | 11.x | 10.1.52 | 容器默认头 | ⚠️ **可能**（`Vary` 之类的框架默认头随版本变） | ✅ **本域只触及 `max-http-request-header-size`**，不涉及 CORS ⇒ **无 `Vary` 差异面**（C 侧 `WebMvcConfig` **零 CORS** ⇒ 走不到 `DefaultCorsProcessor`） |

### 4.D 事务与数据库

| # | 项 | B 侧 | C 侧 | 影响 | 验证证据 |
|---|---|---|---|---|---|
| 4.D-1 | upsert 的 DB 级不变量 | `GREATEST`（单调）＋ `CASE WHEN status='done' THEN status`（done 不可回退）＋ `done_at = COALESCE(done_at, now())`（只写一次） | 逐字照抄 | — | `TaskProgressMapperRealPgTest` 9 例（**真 PG**） |
| 4.D-2 | 事务边界 | sink 的 upsert **逐条**、**无显式事务** | 同（逐条） | — | 同上 |
| 4.D-3 | **Flyway 账本** | `flyway_schema_history_task` ＋ `baseline-version: 0` | 逐字照抄 | — | `TaskServiceApplicationTests`（账本有 V1/V2 且 success ＋ 两表存在） |
| 4.D-4 | **Flyway 版本覆盖** | 10.22.0 | **11.14.1**（显式覆盖 BOM 的 11.7.2） | ⚠️ 迁移脚本的 SQL 方言兼容性 | ✅ 本域 2 个脚本（`CREATE TABLE` ＋ 索引 ＋ 注释）在 11.14.1 上跑通（真 PG 容器） |

### 4.E 🔴 **本轮自查改正**：两处**被证伪的机制断言**

> **触发**：写台账时逐条核对「B 出处」的三段式 javadoc，发现 C 侧**一处** javadoc 里的机制断言**在 Spring 6.2.16 下不成立**（⇒ 已改正**实现**）。
> 📌 **本节只登记「C 侧代码本身的缺陷」** —— 台账**自身**的断言错误另见 §八·8.2。

| # | 原断言 | 实际 | 处置 |
|---|---|---|---|
| 4.E-1 | `TaskApiJsonBodyConfig` 的 javadoc 写「覆写 `canRead(ResolvableType, MediaType)` 即可让转换器无条件生效」 | 🔴 **Spring 6.2.16 的 `MappingJackson2HttpMessageConverter` 不在 `SmartHttpMessageConverter` 分支**（`javap` 实测：`GenericHttpMessageConverter extends HttpMessageConverter`）⇒ **`canRead(ResolvableType,…)` 不是覆写点** ⇒ 该覆写**根本不会被调用** | ✅ **已改实现**：改为覆写 `Type` 版三参（`canRead(Type,Class,MediaType)` / `canWrite(Type,Class,MediaType)`）＋ 2 参版本（`canRead(Class,MediaType)` / `canWrite(Class,MediaType)`），并删掉 `ResolvableType` import、加 `java.lang.reflect.Type` import ⇒ **编译通过 ＋ 真 Tomcat 200** |

> 📌 **元教训**（AGENTS §3.3.2）：**以「实测证据」措辞写的断言，必须满足 §3.3.1 的三条** ——
> ① 读到**消费该配置/注解的那段控制流**（不是声明行）；② 有**运行证据**；③ 行号指向**消费点**。
> ⇒ 本轮这两处的断言**都只有「类上有覆写」**，**没有运行证据** ⇒ 判为高危并改正。

### 4.F 🔴 **Kafka ack mode：B 侧 javadoc 与 B 侧代码不一致**（**B 侧内部不一致，原样继承**）

| 侧 | 声明 | 代码 | 实际生效 |
|---|---|---|---|
| **B** | yaml `spring.kafka.listener.ack-mode: record` ＋ javadoc 声称 `AckMode.RECORD` | `TaskRpcApplication:185-193` **自建 factory 且未调 `setAckMode`** ⇒ Boot 的 configurer **不参与** | 🔴 **落 Boot 默认 `BATCH`** ⇒ yaml 的 `ack-mode: record` 是**死键** |
| **C** | 照抄 yaml 的 `ack-mode: record` | ✅ **显式 `setAckMode(RECORD)`** | `RECORD` |

> ⚠️ **为什么不照抄 B 侧的「实际行为」（BATCH）**：B 侧**自己**在两处声明要 `RECORD`（yaml + javadoc），
> 只是**接线漏了** ⇒ 这是**代码缺陷**而非契约 ⇒ 照抄 BATCH = **复制一个 bug**。
> ⇒ 按「**声明的契约优先于接线的偶然**」取 `RECORD`，并登记为**签字偏离**（§五·DRIFT-TASK-003）。
> ⇒ **可观察性论证**：在「失败记录 + 后续无消息」的窗口下，**BATCH 与 RECORD 均不提交该失败记录**
> （B 侧 `noCommitErrorHandler` 的 `setAckAfterHandle(false)` ＋ `setCommitRecovered(false)` 两侧一致）
> ⇒ **唯一差异 = 批内成功记录的提交时机**（BATCH 攒批提交 / RECORD 逐条提交）⇒ **影响面 = 崩溃时的重复消费窗口**，非业务语义。

### 4.G 🔴 **B 侧 `noCommitErrorHandler` 的 javadoc 与代码不一致**（**原样继承 + 登记**）

| 项 | B 侧 javadoc | B 侧代码 | C 侧 |
|---|---|---|---|
| 重试次数 | 「重试次数必须为 0」 | 🔴 `new FixedBackOff(1000L, 1L)` ⇒ **maxAttempts = 1 = 重试 1 次** | **照抄代码**（`FixedBackOff(1000L, 1L)`） |
| ack mode | 声称 `AckMode.RECORD` | **未设** | 见 §四·4.F |

> ⇒ 登记为 **DRIFT-TASK-007**（**B 侧内部不一致**，D16 ⇒ 不回溯第三方 ⇒ 原样继承并登记）。

### 4.H 🔴 **框架版本分叉面（Spring 6.2.16 vs 7.0.10）在本域的落点**

| 机制 | Spring 6.2.16（C 侧） | Spring 7.0.10（B 侧） | 本域是否触及 | 结论 |
|---|---|---|---|---|
| `DefaultCorsProcessor.processRequest` | `config == null` 时**先写 3 条 `Vary`** | `config == null` 时**一条都不写** | ❌ **不触及** —— C 侧 `WebMvcConfig` **零 CORS**（B 侧也零）⇒ 走不到该分支 | ✅ **无差异面**（与 article 域的 DRIFT-015 **不是同一件事**：article 域有 CORS filter ⇒ 404 多 2 条 `Vary`） |
| `MappingJackson2HttpMessageConverter` 的继承链 | `GenericHttpMessageConverter extends HttpMessageConverter`（**非** `SmartHttpMessageConverter`） | 链不同 ⇒ `canRead(ResolvableType,…)` 可能是覆写点 | ✅ **触及** ⇒ 已在 §四·4.E 改正 | ✅ **已修** |

> 📌 **「命中哪个 handler」会放大或缩小差异面**（article 域教训的复用）：
> 本域的 404 由 **静态资源处理器**命中、200/400/401 由 **`HandlerMethod`** 命中 ⇒
> ⇒ **本域的 404 响应体与 `Vary` 头**逐字节断言已进 `TaskApiWireContractRealTomcatTest`。

---

## 五、语义漂移清单（verification.md §11.5）

| Drift ID | 位置（B / C） | B 行为 | C 行为 | 原因 | 影响面 | 严重度 | 状态 |
|---|---|---|---|---|---|---|---|
| **DRIFT-TASK-001** | B `task-rpc/.../service/TaskRedisKeys.java:69`（写）<br>B `task-rpc/.../service/TaskDomainService.java`（读）<br>C `service/TaskRedisKeys.java:97`（写）＋ `domain/service/TaskDomainService.java`（读） | **写 `task:progress:{uid}`**（`userProgressCache`） | **写 `task:progress:{uid}`**；**读**却是 `task:progress:{uid}:{taskID}`（`likeTaskProgress`） | 🔴 **B 侧读写 key 不同** ⇒ 写入的值**读不回来** ⇒ 每次读都回源 DB | **读路径恒定走 DB**（性能面）· **业务结果正确**（DB 回源后仍拿到正确进度） | 🟡 **P2** | ✅ **原样继承并登记**（D16：不回溯第三方） |
| **DRIFT-TASK-002** | B `task-api/application.yml:18,75` ＋ `task-rpc/application.yml:9,225`<br>C `application.yml:216,236` | HTTP `18886` ＋ gRPC `9005`；Nacos 服务名 `task-api` / `task-rpc` | HTTP `8890` ＋ gRPC `9006`；服务名 `task-service` | **D1 合并裁决**（C 侧号段） | 端口 ＋ 服务注册面 | 🟢 **已签字** | **INTENTIONALLY_CHANGED**（端口属 L1，**必须登记**） |
| **DRIFT-TASK-003** | B `task-rpc/.../TaskRpcApplication.java:185-193`（未设 ack mode）<br>C `TaskServiceApplication.java#kafkaListenerContainerFactory` | 实际落 Boot 默认 **BATCH**（yaml 的 `ack-mode: record` 是**死键**） | **显式 `setAckMode(RECORD)`** | B 侧**自己**在 yaml + javadoc 两处声明要 `RECORD`，只是**接线漏了** ⇒ 代码缺陷而非契约 | 崩溃时**批内成功记录的重复消费窗口** | 🟡 **P2** | ✅ **INTENTIONALLY_CHANGED**（**声明的契约优先于接线的偶然**）⇒ §四·4.F |
| **DRIFT-TASK-004** | B `task-rpc/.../TaskRpcApplication.java:185-193`<br>C 同上 | 自建 factory ⇒ `spring.kafka.listener.*` 一整族键**无消费点** | **同样自建** ⇒ **同样无消费点** | **两侧同形**（不是漂移，是共同事实） | 配置面（`auto-startup` 等键**不生效**） | 🟢 无 | ✅ **两侧一致**（登记以防后人误改） |
| **DRIFT-TASK-005** | B `task-rpc/.../service/TaskRewardPublisher.java:66-77`（写 `score`）<br>C `messaging/TaskRewardPublisher.java#enqueue`（写 `score`） | 写入字段名 `score`，而**消费端读的是 `add_score`** | **同**（照抄） | B 侧**无消费端**（worker 从未启动）⇒ **不可观察** | 无（消费端是死代码） | 🟢 无 | ✅ **原样继承**（**不可观察**） |
| **DRIFT-TASK-006** | B `task-rpc/.../mq/ArticleLikeProgressSink.java`（`enqueue(..., 5L)`）<br>B `task-rpc/.../service/TaskDefs.java:82-84`（target `3`）<br>C `messaging/TaskArticleLikeProgressSink.java` ＋ `service/TaskDefs.java` | 文章路**奖励分值 5** 而 **target 3** ⇒ **两份真相** | **同**（照抄） | B 侧常量硬编码 与 `TaskDefs` 不一致 | 奖励分值面（**消费端是死代码** ⇒ 不可观察） | 🟢 无 | ✅ **原样继承**（**不可观察**） |
| **DRIFT-TASK-007** | B `task-rpc/.../TaskRpcApplication.java:202-216`<br>C `TaskServiceApplication.java#noCommitErrorHandler` | javadoc 说「重试 0 次」；代码是 `FixedBackOff(1000L, 1L)` ⇒ **重试 1 次** | **照抄代码**（`FixedBackOff(1000L, 1L)`） | **B 侧内部不一致**（注释 vs 代码） | 失败消息的重试面 | 🟢 无 | ✅ **原样继承**（D16 ⇒ 不回溯）⇒ §四·4.G |
| **DRIFT-TASK-008** | B `task-rpc/.../service/TaskRedisKeys.java:93` vs `:128`<br>C `service/TaskRedisKeys.java:121` vs `:156` | 用户路 init key = `task:init:{uid}:{taskID}`；文章路 init key = **`task:inits:`**（**多一个 s**） | **同**（照抄） | B 侧命名不一致 | key 空间面（**两侧都是 B 侧拼法** ⇒ 行为等价） | 🟢 无 | ✅ **原样继承** |
| **DRIFT-TASK-009** | B `task-rpc/.../service/TaskRedisKeys.java:104` vs `:150`<br>C `service/TaskRedisKeys.java:132` vs `:178` | 用户路 done key = `task:done:{uid}:{taskID}`；文章路 done key = `task:done:{taskID}:{uid}`（**段序不同**） | **同**（照抄） | B 侧段序不一致 | key 空间面 | 🟢 无 | ✅ **原样继承** |
| **DRIFT-TASK-010** | B `task-rpc/.../mq/UserLikeProgressSink.java`（用户路 HSET 检查）<br>B `task-rpc/.../mq/ArticleLikeProgressSink.java`（文章路忽略）<br>C `messaging/TaskUserLikeProgressSink.java` ＋ `TaskArticleLikeProgressSink.java` | 用户路 **检查** HSET 结果；文章路 **忽略** | **同**（照抄） | B 侧两条路实现不一致 | 用户路漏写会被发现；文章路漏写**静默** | 🟢 无 | ✅ **原样继承** |
| **DRIFT-TASK-011** | B `task-rpc/.../service/TaskRewardPublisher.java:44-45`（`@Value`）<br>C `messaging/TaskRewardPublisher.java#enqueue` | `reward_id = uid + taskID`（**加法** ⇒ 天然碰撞，如 `1`+`23` = `12`+`3`） | **同**（照抄） | B 侧语义如此 | 幂等 ID 面（**消费端是死代码** ⇒ 不可观察） | 🟢 无 | ✅ **原样继承** |
| **DRIFT-TASK-012** | B `task-rpc/.../domain/UserLikeCountEntity.java`（`user_id` 是 `String`）<br>B `task-rpc/.../domain/TaskProgressEntity.java`（`user_id` 是 `Long`）<br>C `domain/entity/UserLikeCountEntity.java` ＋ `TaskProgressEntity.java` | **同表不同类型**（`user_like_count.user_id` TEXT vs `task_progress.user_id` BIGINT） | **同**（照抄） | B 侧如此 ⇒ 跨表 join/比较需显式转换 | 类型面 | 🟢 无 | ✅ **原样继承** |
| **DRIFT-TASK-013** | B `task-rpc/.../mq/TaskCountStore.java`（`COUNTER_KEY_PREFIX="task:goka:count:"`）<br>C `messaging/TaskCountStore.java` | Redis `INCR` ＋ group 命名空间 | **同**（照抄） | B 侧已定案（**Go 的 `goka.Persist`（RocksDB）无生态等价物** ⇒ B 侧已用 Redis 落地） | 计数 key 空间 | 🟢 无 | ✅ **两侧同形** |
| **DRIFT-TASK-014** | B `task-api/.../advice/GlobalExceptionHandler.java:290-294`（兜底 **400**）<br>C `advice/TaskApiExceptionHandler.java#handleAny` | 兜底 = **400**（不是 500） | **同**（照抄） | Go `http.Error(w, err.Error(), http.StatusBadRequest)` | 未知异常的状态码 | 🟢 无 | ✅ **两侧同形**（⚠️ **反直觉但必须照抄**） |
| **DRIFT-TASK-015** | B `task-rpc/.../service/TaskRewardPublisher.java` + yml `task.reward.*`<br>C `messaging/TaskRewardPublisher.java` ＋ `config/TaskRewardProperties.java` | **只有生产端**，**worker 从未启动** ⇒ `stream:task:reward` **无界增长** | **同**（照抄） | B 侧如此 | Redis Stream 内存面 | 🟢 无 | ✅ **原样继承**（**B 侧既有问题，不是 C 侧引入**） |

### 5.1 逐条**修复建议 ＋ 验证方法**（verification.md §11.5 的两个必填字段）

> 📌 §五 的漂移表把「修复建议 ＋ 验证方法」折进了「状态」列（与 like / article 两域同形）；
> 为满足 §11.5 的**逐条必填**，本小节把这两列**单独展开**。
> ⚠️ **多数条目的建议是「不改」** —— 因为 **D16 规定 B 侧是唯一 oracle ⇒ B 侧自身的漂移必须继承、不回溯第三方**。

| Drift ID | 修复建议 | 验证方法（**已做 / 待做**） |
|---|---|---|
| **DRIFT-TASK-001** | 🔴 **不建议修**（B 侧既有缺陷）—— 若要修，**必须两侧同时改**（改 B 侧 `TaskRedisKeys` 的写侧 key ＋ C 侧同步）⇒ 属**回溯第三方**，D16 不允许 | ✅ **已做**：真 Redis 验 key 形态（`TaskCountStoreRealRedisTest` 3 例 ＋ `TaskRedisPipelineRealRedisTest` 5 例）<br>❌ **待做**：读路径恒回源 DB 的**性能影响**无基线数据 |
| **DRIFT-TASK-002** | ✅ **已签字**（D1 合并裁决）—— 端口是 C 侧号段的既定值，**不得**改回 B 侧 | ✅ **已做**：`application.yml:216,236` 的端口 ＋ `TaskServiceApplicationTests` 的上下文自检 |
| **DRIFT-TASK-003** | ✅ **已签字**（取 B 侧**声明**的契约，不取 B 侧**接线**的偶然）—— 若要改回 BATCH ⇒ **复制一个 B 侧自己的 bug** | ✅ **已做**：`TaskKafkaListenerAssemblyTest`（ack mode = RECORD 断言）<br>⚠️ **待做**：「批内成功记录的提交时机」差异**无字节级门禁** |
| **DRIFT-TASK-004** | ✅ **不改**（两侧同形）—— 若要「修」，得**两侧同时**用 Boot 的 `ConcurrentKafkaListenerContainerFactoryConfigurer` ⇒ 属**形态改动**，要走决策流程 | ✅ **已做**：守门 A5（`@Import` 清单为空）＋ `TaskKafkaListenerAssemblyTest`（6 listener 真进 registry） |
| **DRIFT-TASK-005** | 🔴 **不建议修**（消费端是死代码 ⇒ 不可观察）—— 修它要**先启动 reward worker**，那是**新增行为** | ✅ **已做**：写入侧字段名逐条对照（B 侧 `TaskRewardPublisher.java:66-77`）<br>❌ **待做**：消费端**不存在** ⇒ 无端到端验证对象 |
| **DRIFT-TASK-006** | 🔴 **不建议修**（B 侧两份真相；且消费端是死代码 ⇒ 不可观察）—— 修它 = 改 B 侧的数值 | ✅ **已做**：`TaskDefs` 两条定义逐字比对（`190/3` ＋ `1902/5`）＋ 文章路 `enqueue(..., 5L)` 的常量比对 |
| **DRIFT-TASK-007** | 🔴 **不建议改**（B 侧注释 vs 代码不一致；D16 ⇒ 原样继承）—— 若要改，**先回 B 侧**改注释 | ✅ **已做**：`FixedBackOff(1000L, 1L)` ＋ 三个 `setXxx(false)` 逐条对照 |
| **DRIFT-TASK-008** | 🔴 **不建议修**（B 侧命名不一致；两侧同形 ⇒ 行为等价） | ✅ **已做**：10 个 key 模板逐条比对（B 侧 `:69,82,93,104,114,128,138,150,158,173`） |
| **DRIFT-TASK-009** | 🔴 **不建议修**（同上） | ✅ **已做**：同上 |
| **DRIFT-TASK-010** | 🔴 **不建议修**（B 侧两条路实现不一致；改它 = 改 B 侧语义） | ✅ **已做**：两个 sink 的 HSET 处理逐条对照<br>❌ **待做**：**文章路漏写静默**这条后果**无端到端验证**（需真 Kafka） |
| **DRIFT-TASK-011** | 🔴 **不建议修**（B 侧加法语义；消费端是死代码 ⇒ 不可观察） | ✅ **已做**：`enqueue` 的 `reward_id` 拼法逐条对照 |
| **DRIFT-TASK-012** | 🔴 **不建议修**（B 侧两表类型不同；改它 = 改 B 侧 DDL ⇒ **L1 契约变更**） | ✅ **已做**：V1/V2 **逐字复制**（`diff -r` 零差异）＋ 真 PG 建表断言 |
| **DRIFT-TASK-013** | ✅ **不改**（B 侧已定案；C 侧照抄） | ✅ **已做**：`TaskCountStoreRealRedisTest` 3 例（**含变异 M-4**：去掉 group ⇒ 精确转红） |
| **DRIFT-TASK-014** | ✅ **不改**（B 侧就是 400；改回 500 = 行为漂移） | ✅ **已做**：真 Tomcat 的 400 断言（`TaskApiWireContractRealTomcatTest`） |
| **DRIFT-TASK-015** | 🔴 **不建议修**（B 侧既有缺陷：worker 从未启动 ⇒ Stream 无界增长）—— 修它 = **新增一个 worker**（新行为） | ✅ **已做**：`TaskRewardPublisher#enqueue` / `#streamKey` 逐条对照<br>❌ **待做**：**Stream 无界增长**这条后果**无监控/告警证据**（本域零指标） |

### 5.2 本轮**修掉**的缺陷（不是漂移，是 C 侧实现期抓出的问题）

| # | 缺陷 | 影响 | 状态 |
|---|---|---|---|
| 1 | `GetTaskReq` 的 compact 构造器写成 `normalize(raw)` | record 的隐式参数名就是组件名 `id` ⇒ **编译失败** | ✅ 已修（`id = normalize(id);`） |
| 2 | `TaskApiJsonBodyConfig` 覆写 `canRead(ResolvableType, …)` | Spring 6.2.16 下**不是覆写点** ⇒ 覆写**永不生效** | ✅ 已修（改 `Type` 版三参 ＋ 2 参）⇒ §四·4.E |
| 3 | `TaskApiJsonBodyConfig` 的 javadoc 把共享层的**让位机制**说成「canRead 抢先」 | 机制说反 ⇒ 后人改错地方 | ✅ 已改注释（§四·4.E-2） |
| 4 | `TaskApiProperties`（prefix `task.api`）在 api+rpc 合并后**零消费点** | **哑弹类**（有类无消费点） | ✅ 已改名 `TaskRewardProperties`（prefix `task.reward`，键名逐字不变）⇒ **唯一「B 有配置 ＋ C 有真消费点」** 的块 |
| 5 | `TaskApiResponseAdvice` 的职责「待拍板」 | 可能被填成「加 `{code,msg,data}` 信封」⇒ **新增一层 wire 差异** | ✅ 已定：**原样透传，不加信封**（真 Tomcat 断言成功体无信封） |
| 6 | `WebMvcConfig` 的 javadoc 写「注册 JwtAuthInterceptor」 | 会照抄出**别的域的鉴权层** ⇒ **新增行为** | ✅ 已改写为**否定式声明**（零拦截器 ＋ 零 CORS） |
| 7 | `TaskTraceAttributes` 的可见性 | B 侧包级私有 ⇒ C 侧需跨类调用 | ✅ 提为独立 `public final class`（**可见性放宽、行为零变化**） |
| 8 | `docs/Mapping_Table/task/task.md` 第 118 行的 `consume` + **两个反斜杠** + `flush` | GFM 里 `\\` 渲染成字面 `\` ⇒ 后面的竖线**不再是转义** ⇒ **该行渲染成 5 列**（表头 4 列）⇒ **表格形状坏** | ✅ 已修（改为**单个**反斜杠 ＋ 转义竖线）⇒ **全文件 GFM 形状复检通过**（唯一一处坏行） |

---

## 六、完成度统计（verification.md §11.6 · **不用文件数/行数代表完成度**）

### 6.1 维度一：职责承接（**42 条 · 机器复算**）

| 档位 | 数量 | 明细 |
|---|---|---|
| ✅ **已实现且已验证**（PASS） | **36** | 见 §二 各行「证据」列 |
| ⚠️ **已实现但未验证**（PARTIAL） | **6** | R-13..R-18（**Kafka 消费面 6 个 listener**）—— **装配层已证**（真进 registry），**消费面真跑未做**（无 broker）⇒ §七·7-1 |
| ❌ **尚未实现**（NOT_IMPLEMENTED） | **0** | — |
| 🔴 **明确存在漂移**（FAIL） | **0** | 无「C 侧少承接 B 侧职责」的情形 |
| 🏷 **已批准的主动变更**（INTENTIONALLY_CHANGED） | **0** | 本域的表达层差异全部落在 §四 / §五（端口、ack mode）⇒ **不在职责承接层** |
| **合计** | **42** | 与 §二·2.3 机器复算结果**一致** |

### 6.2 维度二：语义漂移清单（**15 条登记项 · 独立于上表**）

| 档位 | 数量 | 明细 |
|---|---|---|
| 🏷 **已批准的主动变更**（L1 契约层） | **2** | DRIFT-TASK-002（端口）· DRIFT-TASK-003（ack mode） |
| 🔴 **B 侧内部不一致**（原样继承） | **1** | DRIFT-TASK-007（`noCommitErrorHandler` 的注释说「重试 0 次」而代码是 1 次） |
| 🟡 **B 侧既有行为缺陷**（原样继承 · D16 不回溯） | **3** | DRIFT-TASK-001（读写 Redis key 不同）· DRIFT-TASK-008（`init` / `inits` 单复数）· DRIFT-TASK-009（done key 段序） |
| 🟡 **B 侧两份真相 / 类型不一致**（原样继承） | **4** | DRIFT-TASK-006（奖励分值 5 vs target 3）· DRIFT-TASK-010（用户路检查 HSET / 文章路忽略）· DRIFT-TASK-011（`reward_id` 加法）· DRIFT-TASK-012（`user_id` TEXT vs BIGINT） |
| 🟢 **不可观察**（消费端是死代码 ⇒ 无实际影响） | **2** | DRIFT-TASK-005（`score` vs `add_score`）· DRIFT-TASK-015（reward worker 从未启动） |
| 🟢 **两侧同形**（登记以防后人误改） | **3** | DRIFT-TASK-004（`spring.kafka.listener.*` 无消费点）· DRIFT-TASK-013（Redis `INCR` 替 `goka.Persist`）· DRIFT-TASK-014（兜底 400） |
| **登记项合计** | **15** | 2 ＋ 1 ＋ 3 ＋ 4 ＋ 2 ＋ 3 = **15** ⇒ 与 §五 的 `DRIFT-TASK-001` ~ `015` **逐条对齐、零漏项、零重复计数**（每条 Drift ID **只计一次**） |

> ⚠️ **两个维度的计数不相加** —— 6.1 数的是「职责承接」，6.2 数的是「差异」；
> 同一条目可以同时出现在两边。
> ⇒ **按 AGENTS §3.3 的纪律，汇总表只报「分档计数」，不报「总数」**（避免被读成「完成度 %」）。

### 6.3 ⚠️ **表 ≠ 命题**（AGENTS §3.3 纪律）

> **上表的「PASS 36」是**逐条承接表的计数，**不是**「行为与 B 侧完全等价」的证明。
> ⇒ **表外的剩余缺口**（**不得**因为表内没有 `NOT_IMPLEMENTED` 就当成已闭合）：
> ① 🔴 **Kafka 消费面 6 个 listener 的「消费面真跑」零覆盖** —— 装配层已证（真进 registry），但**没有真 broker** ⇒ **消息真被消费、真落库、真 flush** 这条链**从未被触发**（§七·7-1）；
> ② 🔴 **双侧活体对拍未做** —— 本域**没有** `tools/difftest/task/` ⇒ **没有 B↔C 逐字节对拍** ⇒ 「HTTP 面的 13 条 wire 契约」是**单侧断言**，**不是两侧比对**；
> ③ ⚠️ **gRPC wire 层只有单侧** —— `TaskGrpcService` 有实现、有装配，但**没有真 netty server 的收发用例** ⇒ 传输层零覆盖；
> ④ ⚠️ **Redis 的 flush 管线只覆盖了「SETNX 闸门 ＋ 结果形状」** —— 「真正 flush 一批到 PG」这条链**未真跑**（需真 Kafka）；<br>　　（**计数器命名空间隔离**已由 `TaskCountStoreRealRedisTest` 3 例单独钉住 —— ⚠️ 该判据在 M-4 变异里先**暴露为假覆盖**，已修，见 §八·8.2 #11）
> ⑤ ✅ **HTTP 面的关键契约已被真 Tomcat 钉住**（13 例）：`Content-Type: text/plain` 与不写 Content-Type 都 200 · 无信封 · `{"id":true}` → `id must be a string or integer\n` · `{"id":1.5}` / `{"id":1.0}` / 缺 id / `id<=0` → `user_id invalid\n` · TrimSpace · 未知字段忽略 · 空 body 400 · GET → 405 ＋ `Allow` ＋ 无 body ＋ 无 Content-Type · 404 → `404 page not found\n`；
> ⑥ ✅ **DB 级不变量已被真 PG 钉住**（9 例）：`DO NOTHING` / `GREATEST` / `CASE`（done 不回退）/ `COALESCE`（done_at 不刷新）/ `markDone` 常量覆盖。

---

## 七、遗留缺口与待办

### 7.1 本轮明确留下的缺口（**不写成已闭环**）

| # | 缺口 | 影响面 | 为什么本轮没做 | 怎么补 |
|---|---|---|---|---|
| **U-1** | 🔴 **Kafka 消费面 6 个 listener 的「消费面真跑」零覆盖** | R-13..R-18 六条 ⇒ 「消息真被消费 → 真落 Redis → 真 flush → 真落 PG」这条链**从未触发** | 本工作树**无 Kafka broker**（article / like 域用的是 Sea-kafka **EXTERNAL** 发布口 `127.0.0.1:49992`）⇒ 若去连**别的 lane 的** broker ⇒ 本域回归会**跨 lane 耦合**（红灯含义失真） | 起**本域自带**的 `apache/kafka:3.7.2` 容器（Testcontainers）＋ 真 `@KafkaListener` ＋ 真 `@Scheduled` flush ⇒ 补 `TaskKafkaConsumeRealTest`，并配**变异验证**（listener 一停 ⇒ 断言转红 ⇒ 证明「不是假绿」） |
| **U-2** | 🔴 **双侧活体对拍未做**（无 `tools/difftest/task/`） | 「B ↔ C 逐字节一致」目前**没有双侧证据**；§二 的 13 条 wire 断言是**单侧的** | 需要 B 侧真进程 ＋ 冻结的 Go 字节（本域 B 侧**无 Go 源码** ⇒ 只能做 **B ↔ C 双边逐字节**，**不是三方**） | 建 `tools/difftest/task/run.sh`：起 B 侧 `task-api`（`18886`）与 C 侧 `task-service`（`8890`）⇒ 对同一组请求**逐字节**比对状态码 / Content-Type / body ⇒ 再配 `mutate-parity.sh` |
| **U-3** | ⚠️ **gRPC wire 层零覆盖**（无真 netty server 收发用例） | 传输层 / HTTP2 帧 / trailer 线上序列化全零覆盖 | 本域 `grpc.server.port` 在测试里设 `0`（随机端口）⇒ **服务起得来**，但**没有客户端真连过** | 补一条「真 netty server ＋ 真 stub 调用 `GetTask`」的用例（参考 like 域 `LikeGrpcNettyServerRealTest`） |
| **U-4** | ⚠️ **Redis flush 管线只覆盖「闸门 ＋ 命名空间隔离」** | 「flush 一批到 PG」这条链未真跑 | 依赖 U-1（真 Kafka 投递） | 随 U-1 一并补 |
| **U-5** | 🟡 **`docs/File_Tree/proto.md` 基线陈旧**（非本域） | 该文件比实际文件树**多画了一个已不存在的空目录** `proto/src/test/java/` | 🔴 **`docs/File_Tree/proto.md` 不是本域文件** ⇒ **跨域共享面，本域不写** | 转派主会话：跑一次 `python3 tools/gen-file-tree.py`（ROOT 指向主工作区）即可刷新 |

### 7.2 转派**「合并收口执行者」**的（**本域只登记：现象 ＋ 证据 ＋ 建议修法**）

| # | 现象 | 证据 | 建议修法 |
|---|---|---|---|
| **G-1** | 🔴 **`docs/Mapping_Table/_INDEX.md` 的「映射类型」图例表形状坏** | `docs/Mapping_Table/_INDEX.md:92-97` —— 表头 4 列（`类型/条数/占比/含义`），**数据行只有 3 列**（`\| \`1:1\` \| 505 \| 57% \|`）⇒ **GFM 渲染缺第 4 列**；`git show HEAD:docs/Mapping_Table/_INDEX.md` 证明是**基线既有**（非本轮引入） | 补齐 4 个数据行的「含义」列（`1:1` = 一个 B 文件对一个 C 文件 · `1:N` = 一个 B 文件拆成 N 个 C 文件 · `N:1` = N 个 B 文件合成一个 C 文件 · `C 新增` = B 侧无对应物） |
| **G-2** | 🟡 **`docs/DRIFT/_INDEX.md` 的 `task` 行仍是「⬜ 未建立」** | `docs/DRIFT/_INDEX.md`（🔴 **所有工作树都不许碰** —— `docs/Worktree.md` §三·1） | 合主干后由主会话登记：加 `docs/DRIFT/task/`（2 份文件）＋ 状态改「✅ 已建立」 |
| **G-3** | 🟡 **`docs/Intentional_Deviations/` 未登记 task 域的 2 条主动变更** | DRIFT-TASK-002（端口）· DRIFT-TASK-003（ack mode） | 🔴 **`docs/Intentional_Deviations/_INDEX.md` 禁写** ⇒ 本域**只登记**，由主会话建 `docs/Intentional_Deviations/task/*.md` |
| **G-4** | 🟡 **共享层 `JsonBodyConverterAutoConfiguration` 的 bean 名是隐式契约** | `common/autoconfigure/JsonBodyConverterAutoConfiguration.java:91-92` 用 `@ConditionalOnMissingBean(name="seaJsonBodyConverterConfigurer")` ⇒ **task 域靠「同名 bean」让它退让**（`config/TaskApiJsonBodyConfig.java` 的 `@Configuration("seaJsonBodyConverterConfigurer")`）⇒ **共享层若改 bean 名 ⇒ task 域静默失效**（变回 400） | 共享层把该 bean 名**提为常量**并写进 javadoc（或改成「域内可显式排除」的接口）⇒ 本域的 `TaskApiWireContractRealTomcatTest` 就是这条耦合的**唯一防线**（已写进测试类 javadoc） |

---

## 八、自查与变异验证（2026-11-03 收口）

### 8.1 变异验证（**证明判据不是假绿**）

> 纪律：**每条变异都要求「目标断言转红 ＋ 对照断言仍绿」**，且**还原后回绿**。
> ⇒ 「跑绿」≠「本来就绿」—— 变异没变红 = **断言没覆盖到**（AGENTS §3.3）。
> 📌 **可复跑脚本**：`tools/difftest/task/mutate-task.py`（`python3 tools/difftest/task/mutate-task.py [M-1 …]`，可按 ID 单跑）。
> 📌 **2026-11-03 实测读数**：**M-1 ~ M-4 全部「目标红 ＋ 对照绿 ＋ 还原后回绿」** ⇒ 脚本 **EXIT=0**。

| # | 变异内容 | 目标断言应转红 | 对照断言应保持绿 | 结论 |
|---|---|---|---|---|
| M-1 | **把共享层 JSON 转换器的退让打断**（把域内 `@Configuration("seaJsonBodyConverterConfigurer")` 改名） | ✅ **实测转红**：`TaskApiWireContractRealTomcatTest.nonJsonContentTypeIsStillParsed:106`（**断言失败**，非编译失败） | ✅ **实测仍绿**：`jsonBodyReturnsBareResponse` | ✅ **还原后回绿**<br>⇒ **坐实了 §四·4.B-1 的机制**：共享层转换器 `canRead` 无条件 true ⇒ **抢先**被选中 ⇒ `read()` 内丢弃 body ⇒ 400 |
| M-2 | **把 `GetTaskReq` 的 `numberLiteral` 改成「浮点也在第一段就拒」**（丢掉「字面量保留 ⇒ 第二段 `ParseInt` 失败」这条判据） | ✅ **实测转红**：`GetTaskReqNormalizationTest.fractionalIdKeepsFractionalText:72`（失败信息 `problem: id must be a string or integer` —— **正是错误文案被换掉了**） | ✅ **实测仍绿**：`nonScalarIdIsRejectedAtUnmarshalTime` | ✅ **还原后回绿**<br>⇒ 证明两条文案**互斥**（B 侧 `GetTaskReq.java:42-44` 明写「状态码相同、文案不同 ⇒ 仍是可观察漂移」） |
| M-3 | **把 Mapper 的 `GREATEST` 改成普通赋值**（`progress = EXCLUDED.progress`） | ✅ **实测转红**：`TaskProgressMapperRealPgTest.progressNeverDecreases:96`（**断言失败**） | ✅ **实测仍绿**：`doneStatusIsNeverReverted` | ✅ **还原后回绿**<br>⇒ 证明 SQL 不变量**被真 PG 验到了**（且 `CASE WHEN status='done'` 与 `GREATEST` 是**两条独立**的不变量） |
| M-4 | **把 `TaskCountStore` 的 group 命名空间去掉**（`keyOf` 只拼 key） | 🔴 **第一轮：仍绿 ⇒ 断言没覆盖到**（根因见 §八·8.2 #11）<br>✅ **第二轮（修好判据后）：转红** —— `TaskCountStoreRealRedisTest.differentGroupsAreIsolated` | ✅ **实测仍绿**：`TaskCountStoreRealRedisTest.sameGroupAndKeyCountsUp`（同 group ⇒ 不受影响） | ✅ **还原后回绿**<br>⇒ 证明 Redis key 空间**被真 Redis 验到了**（且是**验在被测类上**，不是验在 Redis 自己身上） |

> 📌 **M-1 的意义**：M-1 直接把 §四·4.B-1 那个「**静默失效**」的耦合风险**变成了可检测项** ——
> 共享层哪天改了 bean 名，这条用例会立刻转红，而不是让 400 悄悄回来。
> ⇒ **且 M-1 纠正了本台账自己的一处错误断言**：本台账原先写「共享层转换器『canRead 抢先』的说法是错的，
> 真正起作用的是让位」—— **这句是错的** ⇒ **实测坐实的机制是**：共享层 `canRead` **确实**无条件 true
> （`common/web/JsonBodyConverter.java:450-460`）⇒ **确实**会抢先被选中；「让位」的作用是让它**根本不被注册**
> ⇒ 两者是**「为什么会被抢先」＋「怎么让它不注册」**的关系，**不是二选一**。详见 §八·8.2 #8。

### 8.2 本轮**自查改正**清单（**台账自身的质量**）

| # | 自查项 | 发现 | 处置 |
|---|---|---|---|
| 1 | **C 侧 javadoc 里的机制断言** | 2 处「以实测证据措辞」的断言在 Spring 6.2.16 下**不成立**（§四·4.E） | ✅ 已改正断言 **＋ 改正实现**（`Type` 版覆写） |
| 2 | **Mapping_Table 的 GFM 表格形状** | 第 118 行 `consume` + **两个反斜杠** + `flush` ⇒ 渲染成 5 列 | ✅ 已修为单反斜杠 ＋ 转义竖线 ⇒ **全文件形状复检通过** |
| 3 | **`docs/Mapping_Table/_INDEX.md` 的 task 行自报数** | 行数 394 → 397、C 列 39/39 → 40/40 | ✅ 已用 `tools/sync-mapping-index.py`（ROOT 指向本工作树）重算 ⇒ **只动 task 行**（article lane 既有惯例） |
| 4 | **`gen-file-tree.py` 的 `ROOT` 硬编码** | 脚本的 `ROOT = /Users/pr/Documents/GitHub/RAG-V1`（**主工作区**）⇒ 直接跑会**量错树** | ✅ 打补丁指向本工作树（断言替换**只命中 1 处** ＋ **回读生效值**）⇒ 跑完**只保留** `docs/File_Tree/task.md`（`proto.md` 的变动是**基线陈旧**，已还原 ⇒ 转派 G-5） |
| 5 | **守门脚本的 `ROOT` 硬编码** | `check-mapping-coverage.py` 的 `ROOT` 同样硬编码到主工作区 | ✅ 同上打补丁；`check-arch-invariants.py` 的 `ROOT` 由 `__file__` 推导 ⇒ **天然工作树安全** |
| 6 | **`dependency:list` 的 5 条 ERROR** | `'dependencies.dependency.version' … is missing` | ✅ **归因**：本地 `~/.m2` 里 `com.rag:common-1.0-SNAPSHOT.pom` 是 **11-02 的旧件** ⇒ 单模块（不加 `-am`）解析时读不到根 pom 的 `dependencyManagement` ⇒ **不是本域缺陷**（`dependency:list` 仍解析出 `testcontainers:postgresql:1.21.4` ⇒ 版本管理本身是好的） |
| 7 | **`TaskSinkProperties` 的 prefix 误写** | 本轮写 Mapping_Table 时误写成 `task.sink` | ✅ 已更正为 **`task.definitions`**（与 B 侧 `TaskSinkProperties.java:26` 一致） |
| 11 | 🔴 **一条「假覆盖」的用例**（M-4 变异暴露） | 「计数器 INCR 跨 key 隔离」那条用例**自己手拼了 key**（`"ragv1:count:" + group + ":" + key`）⇒ **根本没调用** `TaskCountStore` ⇒ ⇒ 去掉被测类里的 group 命名空间，**它仍然绿** ⇒ ⇒ **它验的是 Redis 自己，不是本仓代码**（且前缀字面量与真值 `task:goka:count:` 还不一致） | ✅ **已修**：① 该判据迁到**新测试类** `messaging/TaskCountStoreRealRedisTest`（3 例：同 group 累加 · **同 key 不同 group 隔离** · key 字面形态用 `TaskCountStore#keyOf` 钉住）；② 原测试类里那条**已移除**并留说明；③ M-4 复跑 ⇒ **目标转红 ＋ 对照仍绿 ＋ 还原回绿**<br>⇒ **元教训**：**用例名 ≠ 被测对象** ⇒ 变异没变红时，**先怀疑用例没碰被测代码**（不是先怀疑实现对） |
| 8 | 🔴 **本台账自己的一处错误断言（写台账时引入）** | 原 §四·4.E-2 写「共享层转换器『canRead 抢先』的说法是错的，真正起作用的是让位」—— 🔴 **这句本身是错的**：共享层 `JsonBodyConverter.canRead` **确实无条件返回 `true`**（`common/web/JsonBodyConverter.java:450-460`）⇒ **确实会抢先**；「让位」的作用是让它**根本不被注册** ⇒ 两者**不矛盾**。<br>⇒ 更严重的是：该行还声称「已改注释」—— 🔴 **那次注释改动从未发生**（C 侧 `TaskApiJsonBodyConfig.java:39-63` 的 javadoc **本来就是对的**）⇒ **台账凭空记了一次不存在的修正** | ✅ **已改正**：① 删除 §四·4.E-2 整行；② §四·4.B-1 改写为「实测坐实」的机制描述 ＋ M-1 读数；③ §八·8.1 的 M-1 行补上实测读数<br>⇒ **元教训**：**「台账里的『已修』也可能是假的** ⇒ 每条「已修」都要能指到**具体的文件:行号改动**，否则按「未发生」处理 |
| 9 | 🔴 **M-4 的第一版变异写法是「编译错误」⇒ 假红** | 变异把 `return …;` 改成 `return …; -- MUTATED` —— `--` 是 **SQL 注释**语法，**不是 Java** ⇒ 整个模块**编译失败** ⇒ **所有**用例都红（含对照用例）⇒ **「目标红」是假的**（红的原因是编译不过，不是断言不成立） | ✅ **已修**：变异改成合法 Java ＋ 对照用例换成与计数器无关的 `toBooleansWorksOnRealPipeline` ⇒ 复跑后「目标红 ＋ 对照绿 ＋ 还原绿」<br>⇒ **元教训**：**变异必须先确认「它编译得过」** ⇒ 否则「红」没有信息量 ⇒ **判据**：失败信息里必须出现**被测断言的行号**（本次 M-1/M-2/M-3 都出现了 `:106` / `:72` / `:96`） |
| 10 | **代码 javadoc 的悬空测试类引用** | `config/TaskApiJsonBodyConfig.java:63` 引用了**不存在**的 `TaskApiJsonBodyContentTypeAgnosticTest`（实际类是 `http/TaskApiWireContractRealTomcatTest`）⇒ 后人按图索骥会找不到 | ✅ **已修**（改为真实类名 ＋ 方法名 ＋ 指向 M-1 变异脚本） |

> 📌 **G-5（补记）**：`docs/File_Tree/proto.md` 的陈旧已在 §七·7-1 的 U-5 登记。

---

## 九、十四问速答（verification.md §十四）

| # | 问题 | 本域答案 |
|---|---|---|
| 1 | 迁移范围是否完整？ | ✅ B 侧 39/39 主类全覆盖；C 列 40/40（含 1 条 C 新增） |
| 2 | 有没有漏掉 B 侧的类？ | ✅ 无（机器核对 B 列 39/39） |
| 3 | 有没有**多写** C 侧的东西？ | ⚠️ **1 条**：`TaskRewardProperties`（B 侧无 Properties 类 ⇒ **键名逐字不变**、表达层载体） |
| 4 | L1 契约有没有被改？ | ⚠️ **2 条已登记**：端口（DRIFT-TASK-002）· ack mode（DRIFT-TASK-003）⇒ **其余 L1 逐条照抄** |
| 5 | 错误码 / 错误文案有没有变？ | ✅ 无（`"id must be a string or integer"` / `"user_id invalid"` 逐字照抄，且**两处触发点互斥**） |
| 6 | 表结构 / 索引 / 约束有没有变？ | ✅ 无（V1/V2 **逐字复制**，`diff -r` 零差异） |
| 7 | Redis key / TTL 有没有变？ | ✅ 无（10 个 key 逐条照抄）；⚠️ **B 侧自身的 key 不一致原样继承**（DRIFT-TASK-001/008/009） |
| 8 | Kafka topic / group 有没有变？ | ✅ 无（5 topic ＋ 7 group 逐条照抄；文章路 sink 用 `group-kafka-filter` = `task.rpc.consumer101`，**不是** `consumer91`） |
| 9 | 有没有新增/删除框架机制？ | ⚠️ **共享层 JSON 转换器让位**（4.B-1，**实测生效**）· **gRPC 拦截链由共享层统一承担**（4.B-2）；⇒ **无新增机制、无删除机制** |
| 10 | 有没有新增鉴权 / CORS / 信封？ | ✅ **全部没有**（零鉴权 · 零 CORS · 零信封）—— 全部靠**否定式声明 + 真 Tomcat 断言**钉住 |
| 11 | 「Bean 存在」有没有被当成「机制生效」？ | ✅ **本域 4 处新框架机制全部配了真触发测试**（共享层让位 · Kafka listener 装配 · gRPC 拦截链（守门 A3）· Jackson 2 强转） |
| 12 | 有没有用文件数/行数冒充完成度？ | ✅ 没有（§六 分档计数 ＋ §六·6.3 的「表 ≠ 命题」） |
| 13 | 缺口是不是被写成已闭环？ | ✅ 没有（§七·7-1 的 5 条缺口**逐条列明**，其中 U-1/U-2 是🔴） |
| 14 | 共享面有没有被误改？ | ✅ 没有（`common/**` **零改动** · `docs/DRIFT/_INDEX.md` / `docs/Intentional_Deviations/_INDEX.md` / `docs/Pitfalls/_INDEX.md` / `AGENTS.md` **零改动**）⇒ 改的只有**本域域文档**（`Mapping_Table/task/task.md` ＋ `_INDEX.md` 的 task 行 ＋ `File_Tree/task.md`） |

---

> 📌 **配套文件**：[`vs-common.md`](vs-common.md)（本域 ↔ 共享层 `common/` 跨层对账 · 5 个消费类 ＋ 4 条机制面轴）
