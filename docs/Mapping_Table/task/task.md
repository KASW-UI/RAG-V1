# Mapping_Table/task/task.md — task 域三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标架构）
> 三棵树里 **task 域**的文件名逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> **A 列**：`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/task/…`（真源，冻结，只读）
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/task/…`（Go 部署形态版；api/rpc 分离）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/service/task/…`（本仓；api/rpc 合并为 `task-service` 单 app）
>
> **格式契约**：[`README.MD`](../README.MD) · **章节骨架**照抄 RAG 那份 `docs/MappingTable/users.md`（列名换成上表三列）。

---

## 〇、三条读表纪律（本表专用）

1. ⚠️ **B 列不是 Go 的替身**。RAG 的 `users`/`like`/`message`/`common`/`article` 五域已被判定 `NOT EQUIVALENT`。
   **一切行为判断以 A 列的 Go 源码为准**；本表出现 B 列时，B 列只表示「RAG 是这么组织的」。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 RAG 的 `docs/DRIFT/task/` 台账的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责**。RAG-V1 的每个 Java 文件目前都只有 `package` + 空类型 + TODO javadoc
   ⇒ **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

> 📌 **映射类型只有 4 种**（不发明新词）：`1:1` · `1:N` · `N:1` · `C 新增`。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| A (Go 真理之源) | B (RAG, Go 形态版) | C (RAG-V1, Spring 目标) | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/task/api/`（`task.go` + `internal/` 共 7 个 `.go`） | `service/task/task-api/` | `service/task/`（`service/task/src/main/java/com/rag/task/advice + service/task/src/main/java/com/rag/task/config + service/task/src/main/java/com/rag/task/controller + service/task/src/main/java/com/rag/task/dto + service/task/src/main/java/com/rag/task/service` 子树） | 1:N | C 把 api 侧并进单 app；Go 的 `internal/{config,handler,logic,svc,types}` 五层被 Spring 的 `@ConfigurationProperties` / `@RestController` / `@Service` / 构造注入取代 |
| `service/task/rpc/`（`task.go` + `internal/` 共 18 个 `.go`） | `service/task/task-rpc/` | `service/task/`（`service/task/src/main/java/com/rag/task/bootstrap + service/task/src/main/java/com/rag/task/config + service/task/src/main/java/com/rag/task/domain/entity + service/task/src/main/java/com/rag/task/domain/service + service/task/src/main/java/com/rag/task/grpc + service/task/src/main/java/com/rag/task/mapper + service/task/src/main/java/com/rag/task/messaging + service/task/src/main/java/com/rag/task/service` 子树） | 1:N | 同上；Go 的 `internal/{config,svc,server,logic,Model,Init,sink,reward}` 八层收敛为 `rpc/{config,domain,mapper,service,bootstrap}` |
| `service/task/api/etc/task.yaml`（17 行） | `task-api/src/main/resources/application.yml` | `service/task/src/main/resources/application.yml` | N:1 | api + rpc 两个 yml 合成 1 份；**键名从 `Name:` / `Port:` / `TaskRpc.Target:` 改成 Spring 标准键** |
| `service/task/rpc/etc/task.yaml`（53 行） | `task-rpc/src/main/resources/application.yml` | 同上（同一份 yml） | N:1 | 同上 |
| `proto/task.proto`（**仓库根**，不在 `service/task/` 下） | `service/task/task-proto/src/main/proto/task.proto` | `proto/src/main/proto/task.proto` | 1:1 | `package task` 两侧相同；`option go_package="."` → `java_package` + `java_multiple_files`（仅 option 块） |
| `service/task/filebeat.yml` | **无对应物** | **无对应物** | 1:1 | ⚠️ Go 侧用 filebeat 把原始日志打进 `task.logs.raw` topic（`filebeat.yml:8-20` `decode_json_fields` + `output.kafka{topic:"task.logs.raw", key:"%{[user_id]}"}`）⇒ **它是一个外部进程的配置，Java 侧无对应物** |
| `service/task/rpc/taskservice/task_service.go`（goctl 生成桩） | **无对应物**（由 `task-proto` 的 protoc 插件生成） | **无对应物**（由根级 `proto/` 模块的 protoc 插件生成） | 1:1 | 生成物，不手写、不逐行对照 |
| `service/task/rpc/pb/task.pb.go` + `task_grpc.pb.go`（protoc 生成物） | **无对应物**（`task-proto` 模块产出） | **无对应物**（根级 `proto/` 模块产出） | 1:1 | 生成物 |
| — | — | **无 contract 模块 / 无 `contract` 包** —— 本域无错误码空间；`.proto` 集中在根级 `proto/src/main/proto/task.proto` | 1:1 | ⚠️ **刻意为空** —— 详见 §1.3 |
| — | — | `service/task/`（**29 个 `.java`**，全部空壳） | C 新增 | C 的实体模块 |

> ⚠️ **task 域没有 `-common`**：Go 侧 `service/task/` 全树**无 `common/` 目录**，B 侧 `service/task/pom.xml` 的 `<modules>` 只有 3 项（`task-api`/`task-rpc`/`task-proto`），C 侧 `service/task/pom.xml` 只有 `task-service` 一项（proto 走根级 `proto/` 模块） ⇒ **三方一致，不是遗漏**。

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `task/api/`（`TaskApiApplication`） | `com.rag.task`（`TaskServiceApplication.java`） | 入口类合并（D1）|
| `task/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `TaskApiResponseAdvice`）|
| `task/api/client/` | **—（D2 域内直连 ⇒ 删除）** | 域内自我 gRPC 往返取消 |
| `task/api/config/` | `config/` | 与 rpc 侧合并（含 `GoStyleRawBodyArgumentProcessor`）|
| `task/api/controller/` | `controller/` | 直接压平 |
| `task/api/dto/` | `dto/` | 直接压平（3 个同名类全落此）|
| `task/api/service/` | `service/` | 与 rpc 侧合并 |
| **—（B 侧无 `task-common`）** | **—（C 侧无 `contract/` 包）** | ⚠️ **三方一致**：Go 侧 task **无错误码空间** ⇒ 域内不建 `contract/`（不是缺漏）|
| `task/rpc/bootstrap/` | `bootstrap/` | 直接压平 |
| `task/rpc/config/` | `config/` + `messaging/` | Kafka topic 落到 `messaging/`（`TaskKafkaTopics`）|
| `task/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分（`UserLikeCountEntity` 在 Go 侧位于 `internal/sink/`）|
| `task/rpc/mapper/` | `mapper/` | 直接压平 |
| `task/rpc/mq/`（11 类） | `messaging/`（**仅 2 类**）| ⚠️ **消费侧整体缺失**：`TaskKafkaTopics` + `TaskRewardPublisher` 已落位，但 **`TaskUserCountConsumer` / `TaskArticleCountConsumer` 等 9 个消费者无落点**，全仓 `@KafkaListener` **零命中** |
| `task/rpc/service/` | `service/` + `domain/service/` + `messaging/` | 领域服务另出 `domain/service/`；`TaskRewardPublisher` 落 `messaging/` |
| `task/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `TaskServiceGrpcImpl` → `TaskGrpcService` |
| `task-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.task`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 api 侧（`advice + config + controller + dto + service`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/task.go`（37 行 `main()`：conf 加载 → 关原生超时 → `rest.MustNewServer` → `server.Use(observability.NewHTTPMiddleware)` → `svc.NewServiceContext` → `handler.RegisterHandlers` → `server.Start()`） | `task-api/.../TaskApiApplication.java` | `service/task/src/main/java/com/rag/task/TaskServiceApplication.java` | 1:N | C 把 api 的 main 与 rpc 的 main **合并成唯一入口**（D1）；`@SpringBootApplication` + `@EnableDiscoveryClient`；**C 的这一个类同时承载 HTTP 端口与 gRPC 端口** |
| `api/internal/config/config.go`（18 行：`rest.RestConf` + `TaskRpc zrpc.RpcClientConf` + `Auth{AccessSecret, AccessExpire}`） | `task-api/src/main/resources/application.yml`（**无 `Properties` 类**） | `service/task/src/main/java/com/rag/task/config/TaskApiProperties.java` + `application.yml` | 1:N | C 显式建 `@ConfigurationProperties` 类。⚠️ Go 的 `Auth` 段**零消费点**（`grep` 全 `api/` 只命中结构体与 yaml 4 行）⇒ **不迁**；`TaskRpc` → `grpc.client.*`、`Telemetry` → `otel.*` |
| `api/internal/handler/routes.go`（26 行，**唯一**路由：`POST /task/get` + `rest.WithPrefix("/taskcenter/v1")`） | `task-api/.../controller/TaskController.java` | `service/task/src/main/java/com/rag/task/controller/TaskController.java` | 1:1 | ⚠️ **前缀是 `/taskcenter/v1`，不是 `/task/v1`**；**全文件只有 `WithPrefix`，没有 `rest.WithJwt`** ⇒ 本域端点**零鉴权** |
| `api/internal/handler/task/gettaskhandler.go`（34 行：**裸 `json.NewDecoder` + `UseNumber()`，不检查 `Content-Type`**、不套 `io.LimitReader`） | `task-api/.../controller/TaskController.java` + `config/TaskApiRawBodyConfig.java` + `config/GoStyleRawBodyArgumentProcessor.java` + `config/TaskApiJsonBodyConfig.java` | `service/task/src/main/java/com/rag/task/controller/TaskController.java` + `service/task/src/main/java/com/rag/task/config/TaskApiRawBodyConfig.java` + `service/task/src/main/java/com/rag/task/config/GoStyleRawBodyArgumentProcessor.java` + `service/task/src/main/java/com/rag/task/config/TaskApiJsonBodyConfig.java` | 1:N | C 的三件 raw-body 配置**都在**（因为 Go 不看 `Content-Type` ⇒ 必须换掉 Spring 默认的 `RequestResponseBodyMethodProcessor`）；⚠️ **不得注册 `common/src/main/java/com/rag/common/web/JsonBodyConverter`**（它复刻 `httpx.Parse` 的 `withJsonBody` 判据，会**丢弃**非 JSON body） |
| `api/internal/logic/task/gettasklogic.go`（56 行：**纯转发 + 字段投影**，uid 解析 → gRPC → proto→DTO 5 字段） | `task-api/.../service/TaskGetService.java` | `service/task/src/main/java/com/rag/task/service/TaskApiService.java` + `service/task/src/main/java/com/rag/task/service/TaskApiSupport.java` | 1:N | C 按「用例内聚」判据拆成 Service + Support；⚠️ Go 的 `out := make([]types.TaskInfo, 0, len(...))` ⇒ **空列表输出 `[]` 绝不输出 `null`** |
| `api/internal/types/types.go`（66 行：`UserIDParam` 自定义 `UnmarshalJSON` + `Int64()` + `GetTaskReq`/`GetTaskResp`/`TaskInfo`） | `task-api/.../dto/GetTaskReq.java` + `dto/GetTaskResp.java` + `dto/TaskInfo.java` | `service/task/src/main/java/com/rag/task/dto/GetTaskReq.java` + `service/task/src/main/java/com/rag/task/dto/GetTaskResp.java` + `service/task/src/main/java/com/rag/task/dto/TaskInfo.java` | 1:N | C 改 **immutable record**（D5）；JSON key 契约不变（`id` / `tasks` / `name`/`desc`/`taskId`/`completionProgress`/`requiredProgress`） |
| `api/internal/svc/servicecontext.go`（33 行：**唯一**依赖 = `TaskCli` gRPC client；无 Redis / 无 DB / 无 Kafka） | `task-api/.../client/TaskRpcClient.java`（`@GrpcClient`） | **无对应文件** | 1:1 | ⚠️ api+rpc 合并后**域内 gRPC 调用不再跨进程** ⇒ C 的 `api/` 下**没有 `client/` 包**；`TaskApiService` 直落 `rpc/service/TaskDomainService` |
| go-zero `httpx.ErrorCtx`（`errorHandler == nil` 分支 ⇒ `http.Error` ⇒ **text/plain + nosniff + 尾 `\n`**） | `task-api/.../advice/GlobalExceptionHandler.java` | `service/task/src/main/java/com/rag/task/advice/TaskApiExceptionHandler.java` | 1:1 | ⚠️ Go 侧 `api/task.go` **没有** `httpx.SetErrorHandler` ⇒ 失败响应**不是** `{code,msg}` 信封，是裸 text/plain |
| go-zero `httpx.OkJsonCtx`（`okHandler == nil` ⇒ **成功响应无信封**） | （B 侧无 ResponseAdvice） | `service/task/src/main/java/com/rag/task/advice/TaskApiResponseAdvice.java` | C 新增 | ⚠️ **职责待拍板**：Go 侧 `api/task.go` **没有** `httpx.SetOkHandler` ⇒ 成功体就是 `{"tasks":[...]}`。C 有这个位置，但它的职责（是否加 `{code,msg,data}` 信封）必须回 A 列确认后再填 |
| `service/common/observability/http.go`（`DisableNativeRestTimeout` + `NewHTTPMiddleware`，api 侧**唯一**共享依赖） | `service/common/.../spring/HttpMiddlewareFilter.java` | `common/src/main/java/com/rag/common/observability/`（`ObservabilityProperties` + `ObservabilityCustomizer`） | 1:1 | C 的 `common/` 是**真 starter**（`common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java`），不再是普通 jar |
| `service/common/observability/observability.go`（`SlowThreshold()`） | 同上 | `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` | 1:1 | — |
| `rest/config.go:55` tag 默认 `Timeout=3000` | `application.yml` 的 `observability.http-timeout: 3s` | `application.yml`（待补 `observability.http-timeout: 3s`） | 1:1 | ⚠️ 值来自 go-zero **struct tag 默认**，yaml 里**没有** `Timeout` 键 ⇒ Java 侧必须显式写出来 |
|`rest/config.go:53,21` tag 默认 `MaxBytes=1 MiB`|`common/protection/GoStyleMaxBytesFilter`|`common/src/main/java/com/rag/common/protection/MaxBytesFilter.java`|1:1|同样来自 tag 默认 ⇒ 413 裸状态码、无 body|

### 1.2 rpc 侧（`bootstrap + config + domain/entity + domain/service + grpc + mapper + messaging + service`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/task.go`（49 行 `main()`：`conf.MustLoad` → `DisableNativeRpcTimeout` → `svc.NewServiceContext` → **4 个 `go Init.Start*` goroutine** → `zrpc.MustNewServer` → `AddUnaryInterceptors` → `s.Start()`） | `task-rpc/.../TaskRpcApplication.java` | `service/task/src/main/java/com/rag/task/TaskServiceApplication.java`（与 api 合并） | 1:N | C 的入口**只剩 1 个**；Go 的 `//go Init.StartInitReward(ctx)`（`:35`，**已注释**）⇒ reward worker 永不启动 |
| `rpc/internal/config/config.go`（44 行：`zrpc.RpcServerConf` + `PointsRpc`/`UserRpc`（**死配置**）+ `Kafka{…}` + `LikeRedis{Addr,Pass,DB,ConsumerName}` + `Postgres{…}`） | `task-rpc/src/main/resources/application.yml` + `config/TaskKafkaTopics.java` + `config/TaskSinkProperties.java` | `service/task/src/main/java/com/rag/task/messaging/TaskKafkaTopics.java` + `service/task/src/main/java/com/rag/task/config/TaskSinkProperties.java` + `application.yml` | 1:N | ⚠️ Go 的 `PointsRpc`/`UserRpc` 在 `service_context.go` 五处被注释 ⇒ **不建 `client/` 包**；`LikeRedis` 段名是历史遗留（本服务里没有 like） |
| `rpc/internal/svc/service_context.go`（58 行：`redis.NewClient` + `gorm.Open` + `panic` ×3 + `SetMaxOpenConns(50)` + `SetMaxIdleConns(50)` + `SetConnMaxLifetime(time.Duration(30))` + **`AutoMigrate(2 表)`**） | `task-rpc/.../bootstrap/TaskSchemaGate.java` + `domain/` + `mapper/` + `service/` | `service/task/src/main/java/com/rag/task/bootstrap/TaskSchemaGate.java` + `service/task/src/main/java/com/rag/task/domain/entity` + `service/task/src/main/java/com/rag/task/mapper` + `service/task/src/main/java/com/rag/task/domain/service + service/task/src/main/java/com/rag/task/messaging + service/task/src/main/java/com/rag/task/service` | 1:N | Redis / PG 客户端由 Boot 装配；`AutoMigrate` 的「表与进程同生共死」语义 ⇒ C 的 `TaskSchemaGate`（启动时校验两表存在） |
| `rpc/internal/Model/taskModel.go`（51 行：`TaskDef` 结构体（**gorm tag 整段被注释**，`:6-14`）✅ 活定义 `:15-20` + `AllTaskDefs` 字面量 `:22-25` + `TaskProgress` `:28-41` + `UserLikeCount` `:43-50`） | `task-rpc/.../service/TaskDefs.java` + `domain/TaskProgressEntity.java` + `domain/UserLikeCountEntity.java` | `service/task/src/main/java/com/rag/task/service/TaskDefs.java` + `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` + `service/task/src/main/java/com/rag/task/domain/entity/UserLikeCountEntity.java` | 1:N | ⚠️ **配置载体差异**：Go 的 `TaskDef` 的 GORM tag 被注释掉 ⇒ **全仓不存在 `task_def` 表**，任务定义是**硬编码常量**（`AllTaskDefs` 两条字面量）⇒ C 的 `TaskDefs` 是**常量表**，不是 Entity、不是 Mapper |
| `rpc/internal/logic/get_task_logic.go`（140 行：`uid<=0` → `codes.InvalidArgument` + `"user_id invalid"`；Redis `HGETALL task:progress:{uid}` → miss 回源 PG 5 列 → 回填缓存 → 与 `AllTaskDefs` 合并） | `task-rpc/.../service/TaskDomainService.java` | `service/task/src/main/java/com/rag/task/domain/service/TaskDomainService.java` | 1:1 | 本域**唯一**的 gRPC 用例 |
| `rpc/internal/server/task_service_server.go`（29 行：1 个 RPC → logic 的 thin wrapper） | `task-rpc/.../service/impl/TaskServiceGrpcImpl.java` | `service/task/src/main/java/com/rag/task/grpc/TaskGrpcService.java` | 1:1 | `@GrpcService` |
| `rpc/internal/sink/like_sink.go`（423 行：点赞进度 sink。含 **`type UserLikeCount`**（`:17-25`，表 `user_like_count`）✅ **不在 `internal/model/`** + `UserTaskProgress` `:36-42` + 常量 `taskID=1902 / target=5` `:31-34` + `Consume` max-wins `:81-108` + 1s `loop` + `flushOnce` + `lazyInitTaskIfNeeded` + `flushRedis` + `updateDoingTaskProgress` + `completeLikeGT5` + `markTaskDoneInDB` + `flushPostgres` + `upsertChunk`） | `task-rpc/.../mq/UserLikeProgressSink.java` + `service/TaskProgressWriter.java` + `service/TaskRedisKeys.java` + `service/TaskRewardPublisher.java` + `service/RedisPipelines.java` + `domain/UserLikeCountEntity.java` + `domain/TaskProgressEntity.java` + `mapper/UserLikeCountMapper.java` | `service/task/src/main/java/com/rag/task/service/TaskProgressWriter.java` + `service/task/src/main/java/com/rag/task/service/TaskRedisKeys.java` + `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` + `service/task/src/main/java/com/rag/task/service/RedisPipelines.java` + `service/task/src/main/java/com/rag/task/domain/entity/UserLikeCountEntity.java` + `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` + `service/task/src/main/java/com/rag/task/mapper/UserLikeCountMapper.java` | 1:N | ⚠️ **C 列尚无 Kafka 消费侧**（无 `mq/` 包、无 `@KafkaListener`）⇒ sink 的「消费 + 定时 flush」职责**当前无 C 落点**；其余 7 个职责位（C 已有）已就位 |
| `rpc/internal/sink/article_sink.go`（491 行：文章获赞进度 sink。`ArticleLikeTaskProgress` `:33-49`（`ArticleID` 与 3 个时间列**被注释掉**）+ 常量 `articleTaskID=190 / articleTarget=3 / articleInitTTL=24h / articleDoneTTL=120d` `:18-31` + `markReconCandidates` **死代码** `:195-228` + `FlushPostgres` **整段注释** `:458-460`） | `task-rpc/.../mq/ArticleLikeProgressSink.java` + 上列同一批共享类 | `service/task/src/main/java/com/rag/task/domain/service + service/task/src/main/java/com/rag/task/messaging + service/task/src/main/java/com/rag/task/service`（`TaskProgressWriter` / `TaskRedisKeys` / `TaskRewardPublisher` / `RedisPipelines`）+ `service/task/src/main/java/com/rag/task/domain/entity` + `service/task/src/main/java/com/rag/task/mapper` | 1:N | ⚠️ 同上：**C 列无文章路 sink 消费侧**；⚠️ Go 的 `article_sink.go:447` 奖励分值是 **5** 而 target 是 **3**（`reward.NewEvent(..., 5)`）⇒ 这个数值差要落在 `TaskRewardPublisher` 里 |
| `rpc/internal/Init/initUserFilter.go`（59 行：`sink.NewUserLikeSinkConsumer` + `kq.MustNewQueue{Topic: OutUserTopic, Group: GroupFilterUser, Consumers:1, Offset:"latest", Processors:1}` + `proc.AddShutdownListener`） | `task-rpc/.../mq/UserLikeProgressSink.java`（`@KafkaListener` + `@Scheduled`） | **无对应文件** | 1:1 | ⚠️ **Go 侧 `internal/Init/` 不是独立进程** —— 是 `rpc/task.go:31-35` 里 `go Init.StartTaskKafkaRaw(ctx)` 等起的**进程内 goroutine**（`docs/第二轮深挖-契约盘点.md:316`）⇒ C 侧落点是容器内的 listener，不是新 app |
| `rpc/internal/Init/initArticleFilter.go`（50 行：同构，Topic=OutArticleTopic、**Group=GroupKafkaFilter**（不是 `GroupFilterArticle`）） | `task-rpc/.../mq/ArticleLikeProgressSink.java` | **无对应文件** | 1:1 | 同上 |
| `rpc/internal/Init/initKafkaRaw.go`（44 行：`PrintConsumer.Consume` **直接 `return nil`**（纯 no-op 探针）+ group `task.rpc.consumer31` 加入 `task.logs.raw`） | `task-rpc/.../mq/TaskRawDiagnosticConsumer.java` | **无对应文件** | 1:1 | 「什么都不做但不能删」（有真实 consumer group 与外部依赖） |
| `rpc/internal/Init/initGoKa.go`（227 行：3 个 goka processor —— `process` 路由 `:60-70` / `processUserCount` `:72-82` / `processArticleCount` `:84-107`；`LogEvent` 7 字段 `:23-32`；`TaskArticleEvent` `:34-37`；`DLQEvent` **零使用** `:39-43`；`topicDLQ`/`countTable` **零消费点** `:56-57`） | `task-rpc/.../mq/TaskRawLogRouter.java` + `mq/TaskUserCountConsumer.java` + `mq/TaskArticleCountConsumer.java` + `mq/TaskCountStore.java` + `mq/TaskLogEvent.java` + `mq/TaskArticleProgressEvent.java` + `mq/GoJson.java` | **无对应文件** | 1:1 | ⚠️ **C 列无对应物**；`goka.Persist(new(codec.Int64))` 的「按 group 分目录的本地 KV 表（RocksDB）」在 Java 侧**没有等价物**（既不在 PG 也不在 Redis）⇒ 这是本域最大的**选型缺口**，需拍板 |
| `rpc/internal/Init/initReward.go`（21 行：**全文件被 `/* */` 注释**） | **无对应物** | **无对应物** | 1:1 | Go 侧死代码 ⇒ **不迁移**（不是「待补」） |
| `rpc/internal/reward/type.go`（31 行：`StreamKey="stream:task:reward"`、`GroupName="task-reward-group"`、`RedisEvent` + `NewEvent` ⇒ **`RewardID = uid + taskID`（算术加法，不是拼接）**） | `task-rpc/.../service/TaskRewardPublisher.java` | `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` | 1:1 | 幂等 ID 的加法语义**原样保留**（含天然碰撞） |
| `rpc/internal/reducer/producer.go` → `rpc/internal/reward/producer.go`（31 行：`rdb.XAdd(StreamKey, {reward_id, uid, task_id, ts, **score**})`） | `task-rpc/.../service/TaskRewardPublisher.java` | 同上 | 1:1 | ⚠️ 写入字段名是 `score`，Go 侧消费端读的是 `add_score` —— 不匹配，但消费端是死代码 ⇒ 不可观察 |
| `rpc/internal/reward/worker.go`（123 行：**全文件被注释**） | **无对应物** | **无对应物** | 1:1 | Go 侧死代码 ⇒ 不迁移；⇒ `stream:task:reward` **无消费者组**（无界增长） |
| `rpc/taskservice/task_service.go` + `rpc/pb/*.pb.go` | **无对应物**（protoc 生成） | **无对应物**（protoc 生成） | 1:1 | 生成物 |

### 1.3 契约 —— ⚠️ task 域**无错误契约**（proto 在根级 `proto/` 模块）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `proto/task.proto`（25 行：`package task`；`Task{name=1,desc=2,task_id=3,completion_progress=4,required_progress=5}`；`GetTaskReq{user_id=1}`；`GetTaskResp{repeated Task task=1}`；`service TaskService{rpc GetTask}`） | `service/task/task-proto/src/main/proto/task.proto` | `proto/src/main/proto/task.proto` | 1:1 | 两侧 `package task` 完全相同 ⇒ **零偏离**；仅 option 块不同（`go_package="."` → `java_package="com.rag.task.contract.proto"` + `java_multiple_files=true`）；字段号 / 字段名 / 类型 5+1+1 逐条一致 |
| **Go 侧 `service/task/` 全树无 `common/` 目录 ⇒ 无错误码空间**（唯一的错误表达是 `get_task_logic.go:44` 的 `status.Error(codes.InvalidArgument, "user_id invalid")` —— **gRPC 标准码 3**，不是业务码） | **B 侧无 `task-common` 模块**（`service/task/pom.xml` 的 `<modules>` 只有 `task-api`/`task-rpc`/`task-proto` 三项） | **C 侧无 `contract` 模块 / 无 `contract` 包（没有任何 `.java`）**；`.proto` 集中在根级 `proto/src/main/proto/task.proto` | 1:1 | ⚠️⚠️ **Go 侧 task 没有错误码空间（无 common 包）⇒ C 列该模块无 Java 类（刻意）** —— **不是缺漏**。若将来 task 域要引入业务码，参照 `service/like/src/main/java/com/rag/like/contract/` 的 `CodeError`/`ErrCodes`/`ErrMsg`/`GrpcErrFactory` 四件套新建即可；**在此之前不建** |

> 📌 **「域内无 common」的四条可验证依据**（与 RAG 的 `docs/FileTree/task/api.md` §12.7 末同源）：
> ① Go 侧 `service/task/` 全树零个 `common/` 目录；② `grep -rn "errmsg" service/task/` **零命中**；
> ③ 错误表达由类型承载 —— rpc 侧 `StatusRuntimeException`、api 侧 `TaskApiExceptionHandler`；
> ④ 三方（Go / B / C）**都不建** `task-common` ⇒ 一致，不是遗漏。

### 1.4 「Go 侧 `internal/sink/` 必须纳入 A 列扫描范围」

> ⚠️ **本域最容易漏的一处**：`UserLikeCount`（表 `user_like_count`）在 Go 侧**位于 `internal/sink/like_sink.go`**，
> **不在 `internal/model/`**。按目录名盘点会误判为「Go 侧 task 域无此实体」——
> RAG-V1 的 `docs/第二轮深挖-契约盘点.md` §五（T-5）已为此**翻案**，C 列 `domain/entity/UserLikeCountEntity.java`
> 的 javadoc 里已写入该位置说明。⇒ **本表把 `internal/sink/` 全量纳入 A 列。**

---

## 2. 持久层差异

| 维度 | A (Go 真理之源) | B (RAG, Go 形态版) | C (RAG-V1, Spring 目标) | 备注 |
|---|---|---|---|---|
| ORM | GORM：`struct` + `gorm:"column:…;primaryKey"` tag + `TableName()` | MyBatis-Plus：`@TableName` + `@TableId` + `Mapper extends BaseMapper<E>` | 同 B，落 `service/task/src/main/java/com/rag/task/domain/entity` + `service/task/src/main/java/com/rag/task/mapper` | — |
| **同一张表的结构体重复** | ⚠️ `task_progress` 有 **3 份**几乎重复的定义：`Model.TaskProgress`（`taskModel.go:28-37`）、`sink.UserTaskProgress`（`like_sink.go:36-42`）、`sink.ArticleLikeTaskProgress`（`article_sink.go:33-45`） | 收敛为 **1** 个 `TaskProgressEntity` | 收敛为 **1** 个 `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` | 三者差异只有 4 处（`ArticleID` 被注释 / 3 个时间列被注释 / `TableName()` 位置），Java 侧必须收敛 |
| `user_like_count.user_id` 类型 | **`string`**（`taskModel.go:44` / `like_sink.go:18`） | `String` | `String` | ⚠️ 与 `task_progress.user_id`（`int64`）**类型不同** |
| 建表机制 | `gdb.AutoMigrate(&TaskProgress{}, &UserLikeCount{})`（`service_context.go:47`）⇒ **幂等自愈**，缺表自动建 | Flyway `V1__create_task_progress.sql` + `V2__create_user_like_count.sql` | `src/main/resources/db/migration/` **只有 `.gitkeep`** ⇒ ⚠️ **待补** | Go 的 `AutoMigrate` 由 GORM 生成 DDL ⇒ Java 侧的列类型 / 索引 / 默认值要按 GORM 映射**反推** |
| Flyway 账本表名 | — | `spring.flyway.table: flyway_schema_history_task`（**独立账本**，避免与 message/like/article 同库撞号） | `application.yml` **无 `flyway` 段** ⇒ ⚠️ **待补** | 同库 `first_db` / `public` schema，`flyway_schema_history` 是 schema 级账本 |
| MyBatis-Plus 主键策略 | 复合主键 `(user_id, task_id)` 由业务给值 | `id-type: INPUT`（**不用雪花**） | `application.yml` **未写 `id-type`** ⇒ ⚠️ **待补** | 本域无 `snowflake.GetID()` 调用（Go 侧 grep 零命中） |
| jsonb / typeHandler | 本域两表**无 jsonb 列** | 不需要 | 不需要 | — |
| 连接池 | `SetMaxOpenConns(50)` / `SetMaxIdleConns(50)` / ⚠️ `SetConnMaxLifetime(time.Duration(30))` —— **Go 侧漏了 `* time.Minute`** ⇒ 实际是 **30 纳秒** | Hikari `maximum-pool-size: 50` / `minimum-idle: 50` / `max-lifetime: 1800000`（B 侧 yml 已自标【有意偏离 · 不做 1:1】：Hikari 硬拒 `< 30s`，照抄都做不到） | `application.yml` **无 `hikari` 段** ⇒ ⚠️ **待补** | ⚠️ Go 的 DSN 带 `TimeZone=Asia/Shanghai`（`task.yaml:50`）⇒ Java 侧连接串**必须保留同一时区参数** |
| 事务边界 | sink 的 upsert 是**逐条** `Create` + `OnConflict{Columns, DoUpdates}`，**无显式事务** | 同（逐条） | 同 | ⭐ DB 级不变量在 SQL 里：`GREATEST`（单调）+ `CASE WHEN status='done' THEN status`（done 不可回退）+ `done_at = COALESCE(done_at, now())`（只写一次） |
| **`TaskDef` 配置载体** | ⚠️ `TaskDef` 的 **GORM tag 整段被注释**（`taskModel.go:6-14`）⇒ **不存在 `task_def` 表**；活定义是 `:15-20` 的无 tag 结构体 + `:22-25` 的 `AllTaskDefs` **两条字面量** | `service/TaskDefs.java`（常量表） | `service/task/src/main/java/com/rag/task/service/TaskDefs.java`（常量表） | **不是 Entity、不是 Mapper、不是表** ⇒ 落点是「常量/配置载体」 |
| 常量重复 | ⚠️ **三处**硬编码同一件事：`AllTaskDefs`（`taskModel.go:22-25`）、`taskID=1902 / target=5`（`like_sink.go:31-34`）、`articleTaskID=190 / articleTarget=3`（`article_sink.go:20-21`） | `TaskDefs` 收口 | `service/task/src/main/java/com/rag/task/service/TaskDefs.java` 收口 | C 侧落点与 B 同 |
| 雪花 ID | 不用 | 不用 | 不用 | — |

---

## 3. HTTP / DI 差异

### 3.1 HTTP 层

| 维度 | A (Go) | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|---|
| 路由注册 | `server.AddRoutes([…], rest.WithPrefix("/taskcenter/v1"))` | `@RequestMapping("/taskcenter/v1")` + `@PostMapping("/get")` | 同 B（`service/task/src/main/java/com/rag/task/controller/TaskController.java`） | ⚠️ **前缀不是 `/task/v1`**（L1 契约，与其它域的 `/user/v1`、`/message/v1` 全不同） |
| 端点数 / method | **1** 个：`POST` | 1 个 `@PostMapping` | 1 个 | — |
| **鉴权** | ⚠️ `routes.go:16-25` **只有 `WithPrefix`，没有 `rest.WithJwt`** ⇒ **零鉴权**；`config.Auth`（`config.go:14-17`）+ `task.yaml:9-11` 是**死配置** | **无 `interceptor/` 包** | ⚠️ 有 `service/task/src/main/java/com/rag/task/config/WebMvcConfig.java`，其 javadoc 写「注册 JwtAuthInterceptor + public 路径白名单」 | ⚠️ **职责对不上**：本域**不该有** `*JwtAuthInterceptor`（`docs/第二轮深挖-契约盘点.md` §三 T-4 已明确「hot / task 全部端点免鉴权」）⇒ 该 javadoc 需改写 |
| 身份来源 | 请求体 `{"id": …}`（`types.go:52-54` `json:"id"`）⇒ **水平越权面**（任何调用方可查任意 uid） | 同 | 同（`service/task/src/main/java/com/rag/task/dto/GetTaskReq.java`） | Go 既定行为，本表按原样记录 |
| 成功响应形状 | ⚠️ `api/task.go` **无 `httpx.SetOkHandler`** ⇒ `OkJsonCtx` 直写原始对象 ⇒ **`{"tasks":[…]}`，无 `{code,msg,data}`** | Controller 直接返回（**不包信封**） | ⚠️ 有 `service/task/src/main/java/com/rag/task/advice/TaskApiResponseAdvice.java` | ⚠️ **职责待拍板**（同 §1.1） |
| 失败响应形状 | ⚠️ **无 `httpx.SetErrorHandler`** ⇒ `http.Error` ⇒ **text/plain + `X-Content-Type-Options: nosniff` + 尾 `\n`**；状态码 400 / 413 / 404 / 405 / 500 各有来源 | `advice/GlobalExceptionHandler` | `service/task/src/main/java/com/rag/task/advice/TaskApiExceptionHandler.java` + `common/src/main/java/com/rag/common/web/PlainTextErrors.java` / `GoStyleRouterErrorResponses.java` | — |
| 请求体读取 | **裸 `json.NewDecoder(r.Body)` + `UseNumber()`** ⇒ **不看 `Content-Type`** | `TaskApiRawBodyConfig` + `GoStyleRawBodyArgumentProcessor` + `TaskApiJsonBodyConfig` | 同 B（三件都在） | ⚠️ **不得注册 `common/src/main/java/com/rag/common/web/JsonBodyConverter`**（会丢弃非 JSON body ⇒ 新增行为） |
| 未知字段 | `encoding/json` 默认**静默忽略** | 默认 Jackson 行为 | 同 | — |
|请求体上限|`rest/config.go:53` tag 默认 `MaxBytes = 1 MiB` ⇒ 413 裸状态码|`common/protection/GoStyleMaxBytesFilter`|`common/src/main/java/com/rag/common/protection/MaxBytesFilter.java`|—|
| 请求超时 | `rest/config.go:55` tag 默认 `Timeout = 3000` ⇒ 3s（499 + `"Request Timeout"`） | `observability.http-timeout: 3s` | `application.yml`（待补） | ⚠️ 值来自 **tag 默认**，yaml 里没有 |

### 3.2 DI 与进程拓扑

| 维度 | A (Go) | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|---|
| **进程拓扑** | **两个进程**：`api`（`0.0.0.0:18886`）+ `rpc`（`0.0.0.0:9005`） | **两个进程**：`task-api` + `task-rpc` | **一个进程** `task-service`（HTTP `8890` + gRPC `9006`） | D1：api/rpc 合并 |
| 依赖持有 | `svc.ServiceContext` 静态持有（`Config` + `Rdb` + `Gdb` / `TaskCli`） | Spring 容器构造注入（`ServiceContext` 消失） | 同 B | — |
| 域内 gRPC 调用 | `svcCtx.TaskCli.GetTask(...)`（跨进程） | `@GrpcClient("task-rpc")` → `TaskRpcClient`（跨进程） | **域内直调** `service/task/src/main/java/com/rag/task/domain/service/TaskDomainService.java` ⇒ **无 `service/task/src/main/java/com/rag/task/{advice,config,controller,dto,service}/client/` 包** | ⚠️ C 的 `grpc-client-spring-boot-starter` 依赖目前**没有使用方**（本域唯一的下游是本域自己） |
| 服务发现 | api 侧 `Target: 127.0.0.1:9005`（**硬编码 localhost，无 Etcd**）；rpc 侧 `Etcd.Key: task.rpc` | Nacos | Nacos（`application.yml` 有 `spring.cloud.nacos.discovery`） | — |
| **后台任务落点** | ⚠️ `rpc/task.go:31-34` 起 **4 个进程内 goroutine**：`StartTaskKafkaRaw` / `StartTaskGoKa` / `StartTaskKafkaUserFilter` / `StartTaskKafkaArticleFilter`；`:35` 的 `StartInitReward` **被注释** | `@KafkaListener` 容器 + `@Scheduled` | ⚠️ **C 列无对应类**（无 `mq/` 包、无 `@KafkaListener`、无 `@Scheduled`） | ⚠️ **`internal/Init/` 不是独立进程** —— 是同一进程内的 goroutine ⇒ C 侧落点是**容器内的 listener**，**不是新 app**（D1 对本域成立，无例外） |
| gRPC 服务端拦截器 | `s.AddUnaryInterceptors(observability.NewUnaryServerInterceptor(rpcTimeout, SlowThreshold()))` | `common/spring/RpcServerInterceptorFactory` + **显式** `GrpcServerConfigurer.intercept(...)` | ⚠️ RAG-V1 的 `common/` **无** `RpcServerInterceptorFactory` ⇒ **待补** | ⚠️ `@Component` ≠ 自动注册；必须显式 `intercept()` 接线，否则**静默失效** |
| gRPC 超时 | `DisableNativeRpcTimeout` ⇒ `rpcTimeout` 取 go-zero `RpcServerConf.Timeout` 默认 **2000ms**（`task.yaml` 无 `Timeout` 键） | `observability.rpc-timeout: 2s` | `application.yml`（待补） | — |
| 配置绑定 | `conf.MustLoad` → `config.Config` 结构体 | 无 `Properties` 类，全在 `application.yml` | `service/task/src/main/java/com/rag/task/config/TaskApiProperties.java`（`@ConfigurationProperties`） | RAG-V1 的 [`docs/第二轮深挖-契约盘点.md` §4.3](../../第二轮深挖-契约盘点.md#43-落地的缺口) 要求「按 Go 的 `Config` 块拆成多个 `*Properties`」⇒ 本域待细化 |
| 死配置 | `PointsRpc`/`UserRpc`（rpc 侧）、`Auth`（api 侧）—— **零消费点** | 不迁 | 不迁 | — |

---

## 4. 公共模块对照

| 项 | A (Go) | B (RAG) | C (RAG-V1) | 映射类型 | 备注 |
|---|---|---|---|---|---|
| **域内 common 包** | ❌ **无** `service/task/common/` ⇒ **无错误码空间** | ❌ **无** `task-common` 模块 | ❌ **无** ⇒ 域内**无 `contract/` 包**（**无 Java 类**）；`.proto` 在根级 `proto/` 模块 | 1:1 | ⚠️ **三方一致，是刻意为空，不是缺漏** |
| 全局共享层形态 | `service/common/`（**普通 Go 包**） | `service/common/`（**普通 jar**，被各域 `compile` 依赖） | `common/`（**真 starter**，`common/src/main/java/com/rag/common/autoconfigure/` 7 个 auto-config） | 1:N | D3 |
| 错误码 / 信封 | ❌ 无 | ❌ 无 | `common/src/main/java/com/rag/common/contract/ApiResponse.java` + `common/src/main/java/com/rag/common/exception/BusinessException.java` | C 新增 | ⚠️ **本域目前不需要业务码**（Go 只有 gRPC 标准码 3 + 3 条裸 error 文案）⇒ 若 `TaskApiResponseAdvice` 真的要加信封，需先回 A 列确认 |
|text/plain 错误形状|go-zero `http.Error` 内建|`common/http/GoStylePlainTextErrors.java`|`common/src/main/java/com/rag/common/web/PlainTextErrors.java`|1:1|—|
|路由 404 / 405 形状|go-zero router 自己写|`common/http/GoStyleRouterErrorResponses.java`|`common/src/main/java/com/rag/common/web/RouterErrorResponses.java`|1:1|—|
| gRPC status → HTTP 映射 | `service/common/errcode/grpc.go:12-42`（**15 分支**） | `common/errcode/CodeFromGrpcError` | `common/src/main/java/com/rag/common/web/GrpcStatusMapper.java` | 1:1 | ⚠️ **不是** users 域那套 5 分支业务码映射 |
| `,string` int64 解析原语 | `api/internal/types/types.go:38-50`（`UserIDParam.Int64()`） | `users/users-common/.../http/GoStyleStringInt64.java`（**跨域复用**） | `common/src/main/java/com/rag/common/web/StringInt64.java`（**提到全局 common**） | 1:1 | C 侧已归入 `common/` ⇒ **不再有跨域模块依赖** |
| HTTP span / 超时 | `service/common/observability/http.go` | `common/spring/HttpMiddlewareFilter.java` | `common/src/main/java/com/rag/common/observability/`（`ObservabilityProperties` + `ObservabilityCustomizer`）+ `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | 1:1 | — |
|请求体上限|`rest.RestConf` tag 默认|`common/protection/GoStyleMaxBytesFilter.java`|`common/src/main/java/com/rag/common/protection/MaxBytesFilter.java`|1:1|—|
| Kafka topic 单点定义 | `rpc/etc/task.yaml` 的 `Kafka.*` 段 | `task-rpc/.../config/TaskKafkaTopics.java` | `service/task/src/main/java/com/rag/task/messaging/TaskKafkaTopics.java` | 1:1 | ⚠️ `task.logs.filtered.User` / `task.logs.filtered.Article` **同时被 like 域生产** ⇒ 跨服务 L1 契约 |
| MQ 载荷契约 | `internal/Init/initGoKa.go:34-37` `TaskArticleEvent` + `internal/sink/article_sink.go:71-74` `articleVal` | `task-rpc/.../mq/TaskArticleProgressEvent.java` | **无对应文件**（like 域侧有 `service/like/src/main/java/com/rag/like/contract/TaskArticleProgressMsg.java`） | 1:1 | ⚠️ C 侧 task 域**没有**这个契约类型（只有 like 域有）⇒ **跨域 MQ 载荷在 C 侧只有一份** |

---

## 5. 决策锁

**合并 api + rpc 为单 app（D1）6 处同时改**：
1. `service/task/task-api/` 的 11 个 `.java` + `service/task/task-rpc/` 的 29 个 `.java`（合计 40）⇒ 合并进 `service/task/`（C 列现有 **29** 个 `.java`）；
2. 两个 `Application.java`（`TaskApiApplication` / `TaskRpcApplication`）⇒ **合成 1 个** `TaskServiceApplication`（`@SpringBootApplication` + `@EnableDiscoveryClient`）；
3. 两个 `application.yml` ⇒ **合成 1 份**（端口：HTTP `8890` + gRPC `9006`）；
4. api → rpc 的调用从「跨进程 `@GrpcClient`」改为「**域内直调** `domain/service/TaskDomainService.java`」⇒ **删 `{advice,config,controller,dto,service}/client/` 包**；
5. `pom.xml` 里 `grpc-server-spring-boot-starter` 与 `grpc-client-spring-boot-starter` **同时**保留（后者本域暂无使用方）；
6. `internal/Init/` 的 4 个 goroutine 的落点从「独立 `Init` 包」改为「**容器内的 listener / scheduler**」⇒ **不新增 app**。

**为什么换**：D1 = 一域一 app。Go 侧的 `internal/Init/*` **不是独立进程**（是 `rpc/task.go:31-35` 的进程内 goroutine）⇒ 合并后不需要额外容器；代价是 api 与 rpc 共享同一份配置与同一个进程生命周期。

**改「配置载体」的落点（`TaskDef` ⇒ 常量表）3 处同时改**：
1. `domain/entity` 下**不建** `TaskDefEntity`（Go 侧 `task_def` 表不存在，GORM tag 被注释掉）；
2. **不建** `TaskDefMapper`；
3. `service/TaskDefs.java` 作为**纯常量/配置载体**存在（`AllTaskDefs` 两条字面量 + 三处硬编码常量的收口点）。

**为什么换**：Go 侧的 `TaskDef` 是一段**带注释掉的 GORM tag 的死结构体** + 一份**字面量切片**；把死结构体翻成 Entity 会凭空造出一张 Go 侧不存在的表。

**task 域**不建** `contract/` 包（刻意为空）2 处同时改**：
1. 根级 `proto/` 模块提供 `task.proto`（pb 包名 `com.rag.proto.task`）⇒ 域模块**不再自带** proto 源文件；
2. `service/task/src/main/java/com/rag/task/` 下**不建** `contract/` 子包 ⇒ **不建** `CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`（**Go 侧无错误码空间**）。

> 📌 **时点标注（2026-10-26）** —— 本小节原文写的是「建 `task-contract` 模块但不放任何 Java 类」。
> **该形态已被 D7 取代**：10 个 `*-contract` 子模块**全部删除**（proto 集中到根级 `proto/`，错误契约落进域模块的 `contract/` 包）。
> ⇒ task 域因为 **Go 侧根本没有错误码空间**，所以连域内 `contract/` 包都没有 —— **这不是缺漏，是三方一致的刻意为空**。

**为什么换**：proto 是 L1 外部契约，跨服务共享 ⇒ 集中到根级 `proto/` 模块；但错误码空间 Go 侧**根本不存在** ⇒ 建了就是凭空引入业务码，与 Go 不一致。

**保留「裸 JSON 读 body」原语 3 处同时改**：
1. `config/TaskApiRawBodyConfig.java` —— 把 Spring 默认的 `RequestResponseBodyMethodProcessor` 换掉；
2. `config/GoStyleRawBodyArgumentProcessor.java` —— 新造 `HttpInputMessage` 的那一环（body 在转换器之前就被换掉了 ⇒ 改转换器没用）；
3. `config/TaskApiJsonBodyConfig.java` —— 让 `@RequestBody` 无视 `Content-Type`。

**为什么换**：Go 侧是裸 `encoding/json`，**没有媒体类型协商**；不换掉处理器就会新增「非 JSON body 被丢弃 / 415」这类 Go 侧不存在的行为。

**不建 `*JwtAuthInterceptor`（本域零鉴权）2 处同时改**：
1. `advice + config + controller + dto + service` 下**不建** `interceptor/` 包；
2. `config/WebMvcConfig.java` 的职责**不写**「注册 JwtAuthInterceptor + public 路径白名单」—— 本域没有 public/protected 之分。

**为什么换**：`routes.go:16-25` 只有 `rest.WithPrefix`，没有 `rest.WithJwt`；`config.Auth` 是零消费点的死配置 ⇒ 加鉴权 = **新增行为**。

---

## 6. 跨模块全局约定（值逐条核对 A 列 Go 源码）

| 项 | 值 | 来源（A 列 Go 源码） |
|---|---|---|
| **proto 字段号** | `Task{name=1, desc=2, task_id=3, completion_progress=4, required_progress=5}`；`GetTaskReq{user_id=1}`；`GetTaskResp{repeated Task task=1}` | `proto/task.proto:6-21` |
| **proto package / service** | `package task`；`service TaskService { rpc GetTask(GetTaskReq) returns (GetTaskResp) }` ⇒ 方法路径 `/task.TaskService/GetTask` | `proto/task.proto:3, 22-24` |
| **错误码** | ⚠️ **无业务码空间**。唯一业务错误 = **gRPC 标准码 3** `codes.InvalidArgument` + 文案 `"user_id invalid"` | `rpc/internal/logic/get_task_logic.go:43-45` |
| **错误文案（裸 error）** | `"id must be a string or integer"`（`types.go:35`）、`"user_id invalid"`（`types.go:41` 与 `:46`，**两处同文案**） | `api/internal/types/types.go:35, 41, 46` |
| **JWT claims** | ⚠️ **不适用** —— 本域零鉴权（`routes.go` 无 `rest.WithJwt`），无 `userId` / `status` claim | `api/internal/handler/routes.go:15-25` |
| **表名** | `task_progress`（复合主键 `user_id` + `task_id`）、`user_like_count`（`user_id` 是 **string**）；⚠️ **无 `task_def` 表** | `rpc/internal/Model/taskModel.go:39-41, 49-51`；`rpc/internal/sink/like_sink.go:23-25` |
| **HTTP path** | `POST /taskcenter/v1/task/get`（**`taskcenter` 不是 `task`**） | `api/internal/handler/routes.go:19-24` |
| **HTTP 请求体键** | `{"id": <string \| number>}`（`UserIDParam` 两种形态都接受，`json:"id"`） | `api/internal/types/types.go:52-54, 14-36` |
| **HTTP 响应键** | `{"tasks":[{name, desc, taskId, completionProgress, requiredProgress}]}`，**无信封**，空列表输出 `[]` | `api/internal/types/types.go:56-66`；`api/internal/logic/task/gettasklogic.go:43` |
| **Kafka topic（5 个）** | `task.logs.raw`（`InTopic`）、`task.logs.raw.User`（`RawUserTopic`）、`task.logs.raw.Article`（`RawArticleTopic`）、`task.logs.filtered.User`（`OutUserTopic`）、`task.logs.filtered.Article`（`OutArticleTopic`） | `rpc/etc/task.yaml:17-21` |
| **Kafka consumer group（7 个）** | `task.rpc.consumer31`（`GroupKafkaRaw`，**在用**）、`task.rpc.consumer41`（`GroupGoKa`，**在用**）、`task.rpc.consumer61`（`GroupGoKaUser`，**在用**）、`task.rpc.consumer71`（`GroupGoKaArticle`，**在用**）、`task.rpc.consumer81`（`GroupFilterUser`，**在用**）、`task.rpc.consumer101`（`GroupKafkaFilter`，**在用**）、`task.rpc.consumer91`（`GroupFilterArticle`，⚠️ **零使用** —— `initArticleFilter.go:30` 用的是 `GroupKafkaFilter`） | `rpc/etc/task.yaml:23-32`；`rpc/internal/Init/initArticleFilter.go:30` |
| **Redis key（10 个）** | `task:progress:{uid}`（读路径 key）、`task:progress:{uid}:1902`、`task:progress:190:{uid}:{articleID}`、`task:init:{uid}:1902`（90d）、`task:inits:190:{uid}:{articleID}`（24h）、`task:done:{uid}:1902`（90d）、`task:done:190:{uid}`（120d）、`like:total:{uid}`（90d）、`article:like:cnt:{articleID}`（120d）、`stream:task:reward` | `rpc/internal/logic/get_task_logic.go:106-108`；`rpc/internal/sink/like_sink.go:186, 214, 284, 333, 338`；`rpc/internal/sink/article_sink.go:255, 295, 368, 427, 436`；`rpc/internal/reward/type.go:9` |
| **Redis value 编码** | `"status\|progress\|target"`（`packProgress`，`\|` 分隔，**三段**，不是三段就全零） | `rpc/internal/logic/get_task_logic.go:114-129` |
| **Redis Stream** | `stream:task:reward`，字段 `{reward_id, uid, task_id, ts, **score**}`；消费组 `task-reward-group`（**只存在于死代码**） | `rpc/internal/reward/type.go:9-10`；`rpc/internal/reward/producer.go:20-29` |
| **指标名** | ⚠️ **本域零指标** —— Go 侧 `api/` + `rpc/` 全树**无任何 metric 调用** | `grep` 零命中（`api/` 与 `rpc/` 全树） |
| **服务名 / 端口** | `task.rpc`（Etcd key）@ `0.0.0.0:9005`；`task-api` @ `0.0.0.0:18886` | `rpc/etc/task.yaml:1-6`；`api/etc/task.yaml:1-3` |
| **任务定义（2 条）** | `{190, "文章获赞任务", "有一篇文章点赞大于3", 3}`、`{1902, "点赞任务", "给文章点赞5次", 5}` | `rpc/internal/Model/taskModel.go:22-25` |

---

## 7. 覆盖度自查

> **自查命令**（在对应域目录下跑）：
> ```bash
> # C 列覆盖率
> find "/Users/pr/Documents/GitHub/RAG-V1/service/task" -name '*.java' \
>   -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
> # A 列覆盖率
> find "/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/task" \
>   -name '*.go' -not -name '*_test.go' | sort
> ```

### 7.1 C 列零遗漏（29 / 29 = **100%**）

| # | C 列 `.java` | 出现在本表 |
|---|---|---|
| 1 | `TaskServiceApplication.java` | §0 / §1.1 / §1.2 |
| 2 | `service/task/src/main/java/com/rag/task/advice/TaskApiExceptionHandler.java` | §1.1 |
| 3 | `service/task/src/main/java/com/rag/task/advice/TaskApiResponseAdvice.java` | §1.1（⚠️ 职责待拍板） |
| 4 | `service/task/src/main/java/com/rag/task/config/GoStyleRawBodyArgumentProcessor.java` | §1.1 / §5 |
| 5 | `service/task/src/main/java/com/rag/task/config/TaskApiJsonBodyConfig.java` | §1.1 / §5 |
| 6 | `service/task/src/main/java/com/rag/task/config/TaskApiProperties.java` | §1.1 |
| 7 | `service/task/src/main/java/com/rag/task/config/TaskApiRawBodyConfig.java` | §1.1 / §5 |
| 8 | `service/task/src/main/java/com/rag/task/config/WebMvcConfig.java` | §1.1 / §3.1 / §5（⚠️ 职责对不上） |
| 9 | `service/task/src/main/java/com/rag/task/controller/TaskController.java` | §1.1 |
| 10 | `service/task/src/main/java/com/rag/task/dto/GetTaskReq.java` | §1.1 |
| 11 | `service/task/src/main/java/com/rag/task/dto/GetTaskResp.java` | §1.1 |
| 12 | `service/task/src/main/java/com/rag/task/dto/TaskInfo.java` | §1.1 |
| 13 | `service/task/src/main/java/com/rag/task/service/TaskApiService.java` | §1.1 |
| 14 | `service/task/src/main/java/com/rag/task/service/TaskApiSupport.java` | §1.1 |
| 15 | `service/task/src/main/java/com/rag/task/bootstrap/NacosRegistration.java` | §1.2（服务发现注册位） |
| 16 | `service/task/src/main/java/com/rag/task/bootstrap/TaskSchemaGate.java` | §1.2 / §2 |
| 17 | `service/task/src/main/java/com/rag/task/messaging/TaskKafkaTopics.java` | §1.2 / §4 |
| 18 | `service/task/src/main/java/com/rag/task/config/TaskSinkProperties.java` | §1.2 |
| 19 | `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` | §1.2 / §2 |
| 20 | `service/task/src/main/java/com/rag/task/domain/entity/UserLikeCountEntity.java` | §1.2 / §1.4 / §2 |
| 21 | `service/task/src/main/java/com/rag/task/mapper/TaskProgressMapper.java` | §1.2 |
| 22 | `service/task/src/main/java/com/rag/task/mapper/UserLikeCountMapper.java` | §1.2 |
| 23 | `service/task/src/main/java/com/rag/task/service/RedisPipelines.java` | §1.2 |
| 24 | `service/task/src/main/java/com/rag/task/service/TaskDefs.java` | §1.2 / §2 / §5 |
| 25 | `service/task/src/main/java/com/rag/task/domain/service/TaskDomainService.java` | §1.2 |
| 26 | `service/task/src/main/java/com/rag/task/service/TaskProgressWriter.java` | §1.2 |
| 27 | `service/task/src/main/java/com/rag/task/service/TaskRedisKeys.java` | §1.2 |
| 28 | `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` | §1.2 |
| 29 | `service/task/src/main/java/com/rag/task/grpc/TaskGrpcService.java` | §1.2 |

> ✅ **C 列 29 个 `.java` 全部出现，覆盖率 29/29 = 100%**（task 域**零 Java 契约类**，故不在 C 列计数内 —— 见 §1.3）。
> 📌 **实测命令输出**：`find service/task -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u | wc -l` ⇒ **29**。

### 7.2 A 列不遗漏职责（26 / 26 = **100%**）

| # | A 列 `.go` | 出现在本表 / 无对应物（原因） |
|---|---|---|
| 1 | `api/internal/config/config.go` | §1.1 |
| 2 | `api/internal/handler/routes.go` | §1.1 / §3.1 |
| 3 | `api/internal/handler/task/gettaskhandler.go` | §1.1 |
| 4 | `api/internal/logic/task/gettasklogic.go` | §1.1 |
| 5 | `api/internal/svc/servicecontext.go` | §1.1 |
| 6 | `api/internal/types/types.go` | §1.1 |
| 7 | `api/task.go` | §1.1 / §0 |
| 8 | `rpc/internal/Init/initArticleFilter.go` | §1.2 |
| 9 | `rpc/internal/Init/initGoKa.go` | §1.2 |
| 10 | `rpc/internal/Init/initKafkaRaw.go` | §1.2 |
| 11 | `rpc/internal/Init/initReward.go` | §1.2 —— **无对应物（Go 侧全文件注释，死代码）** |
| 12 | `rpc/internal/Init/initUserFilter.go` | §1.2 |
| 13 | `rpc/internal/Model/taskModel.go` | §1.2 / §2 |
| 14 | `rpc/internal/config/config.go` | §1.2 |
| 15 | `rpc/internal/logic/get_task_logic.go` | §1.2 |
| 16 | `rpc/internal/reward/producer.go` | §1.2 |
| 17 | `rpc/internal/reward/type.go` | §1.2 |
| 18 | `rpc/internal/reward/worker.go` | §1.2 —— **无对应物（Go 侧全文件注释，死代码）** |
| 19 | `rpc/internal/server/task_service_server.go` | §1.2 |
| 20 | `rpc/internal/sink/article_sink.go` | §1.2 / §1.4 |
| 21 | `rpc/internal/sink/like_sink.go` | §1.2 / §1.4 |
| 22 | `rpc/internal/svc/service_context.go` | §1.2 |
| 23 | `rpc/pb/task.pb.go` | §0 —— **无对应物（protoc 生成物）** |
| 24 | `rpc/pb/task_grpc.pb.go` | §0 —— **无对应物（protoc 生成物）** |
| 25 | `rpc/task.go` | §1.2 / §0 / §3.2 |
| 26 | `rpc/taskservice/task_service.go` | §0 —— **无对应物（goctl 生成桩）** |
| 27 | `api/etc/task.yaml` | §0 / §3.2 |
| 28 | `rpc/etc/task.yaml` | §0 / §3.2 / §6 |
| 29 | `service/task/filebeat.yml` | §0 —— **无对应物（外部进程 filebeat 的配置）** |
| 30 | `proto/task.proto`（仓库根） | §0 / §1.3 / §6 |

> ✅ **A 列 26 个非测试 `.go` 全部出现（含 `internal/sink/` 两份），覆盖率 26/26 = 100%**；
> 另有 3 个非 `.go` 资源文件（`api/etc/task.yaml`、`rpc/etc/task.yaml`、`filebeat.yml`）与 1 个仓库根 `proto/task.proto`，也逐条列出（表中 #27~#30）。
> 📌 **实测命令输出**：`find service/task -name '*.go' -not -name '*_test.go' | wc -l` ⇒ **26**。

### 7.3 映射类型分布

> 📌 **统计口径**：只统计**带映射类型列的表格行**（§0 / §1.1 / §1.2 / §1.3 / §4），
> §2 / §3 / §6 / §7 是**维度对照表与约定表**，按契约不设映射类型列。

| 映射类型 | 行数 | 占比 | 分布（§0 / §1.1 / §1.2 / §1.3 / §4） |
|---|---|---|---|
| `1:1` | **34** | 64.2% | 5 / 7 / 11 / 2 / 9 |
| `1:N` | **14** | 26.4% | 2 / 5 / 6 / 0 / 1 |
| `N:1` | **2** | 3.8% | 2 / 0 / 0 / 0 / 0 |
| `C 新增` | **3** | 5.7% | 1 / 1 / 0 / 0 / 1 |
| **合计** | **53** | **100%** | 10 / 13 / 17 / 2 / 11 |

> ⚠️ **`N:1` 只有 2 处**（两个 yaml 合成 1 份 `application.yml`）——
> **本域的合并发生在「模块 → 模块」层面，不发生在「文件 → 文件」层面**；
> C 列的 **29** 个 `.java` 与 A 列的 **26** 个 `.go` 数量接近，但**结构完全不同**：
> Go 的 rpc 侧 8 层 `internal/` + api 侧 5 层 `internal/` 被压成 C 的 **10 个子包**
> （`api.{advice,config,controller,dto,service}` + `rpc.{bootstrap,config,domain,mapper,service}`），
> 而 Go 的 `internal/sink/` + `internal/Init/` 两个大包的 **Kafka 消费侧** 在 C 列**整体缺失**（见下）。

### 7.4 ⛔ 三列之间「职责对不上」清单（本表不判对错，只标出**落点缺口**）

| # | 缺口 | 涉及 | 说明 |
|---|---|---|---|
| **1** | 🔴 **Kafka 消费侧整体缺失** | A：`internal/sink/{like,article}_sink.go`、`internal/Init/init{User,Article}Filter.go`、`initKafkaRaw.go`、`initGoKa.go` | B 侧有 **11 个** `mq/*.java`；**C 侧无 `mq/` 包、无 `@KafkaListener`、无 `@Scheduled`**（全仓 `grep -rln "KafkaListener" service/` **零命中**）⇒ **sink 的「消费 + 1s 定时 flush」职责当前无 C 落点** |
| **2** | 🔴 **`goka.Persist` 的存储后端无等价物** | A：`initGoKa.go:161-227`（`goka.Persist(new(codec.Int64))`） | Go 用的是**按 group 分目录的本地 KV 表（RocksDB）**，既不在 PG 也不在 Redis、**无 TTL** ⇒ Java 侧必须显式选型（PG 表 / Redis `HINCRBY` / 专用 KV）⇒ **选型缺口，需拍板** |
| **3** | 🔴 **`WebMvcConfig` 的 javadoc 与本域事实冲突** | C：`api/config/WebMvcConfig.java` | javadoc 写「注册 JwtAuthInterceptor + public 路径白名单」，但 Go 侧 `routes.go:16-25` **无 `rest.WithJwt`** ⇒ 本域**零鉴权** ⇒ 该类的职责描述需改写（否则会照抄出别的域的鉴权层） |
| **4** | 🔴 **`TaskApiResponseAdvice` 的职责未定** | C：`api/advice/TaskApiResponseAdvice.java` | Go 侧 `api/task.go` **无 `httpx.SetOkHandler`** ⇒ 成功响应**无信封**；C 有这个位置但「是否加 `{code,msg,data}`」必须回 A 列确认后再填（**填错 = 新增一层 wire 差异**） |
| **5** | 🟠 **持久层落地位缺口** | C：`db/migration/`（**只有 `.gitkeep`**）、`application.yml`（无 `flyway` 段 / 无 `hikari` 段 / 无 `id-type: INPUT`） | B 侧已有 `V1__create_task_progress.sql` + `V2__create_user_like_count.sql` + `flyway_schema_history_task` + Hikari 50/50 ⇒ C 侧**这些位都还空着** |
| **6** | 🟠 **gRPC 服务端拦截器无 C 落点** | A：`rpc/task.go:44` `observability.NewUnaryServerInterceptor` | RAG-V1 的 `common/` **无** `RpcServerInterceptorFactory` ⇒ 需新建（且必须显式 `GrpcServerConfigurer.intercept(...)` 接线，否则**静默失效**） |
| **7** | 🟠 **MQ 载荷契约在 C 侧只有一份** | A：`initGoKa.go:34-37` + `article_sink.go:71-74` | C 侧 `service/like/src/main/java/com/rag/like/contract/TaskArticleProgressMsg.java` 有；**`task/` 侧没有** ⇒ 跨域 MQ 载荷在 C 侧只有 like 域那一份（形状一致，但归属只剩一方） |
| **8** | 🟠 **`grpc-client-spring-boot-starter` 无使用方** | C：`service/task/pom.xml` | api/rpc 合并后本域**没有跨域 gRPC 下游** ⇒ 该依赖目前无调用点 |
| **9** | 🟡 **`api/config/TaskApiProperties` 的聚合边界未定** | C：`api/config/TaskApiProperties.java` | RAG-V1 [`docs/第二轮深挖-契约盘点.md` §4.3](../../第二轮深挖-契约盘点.md#43-落地的缺口) 要求「按 Go 的 `Config` 块拆成多个 `*Properties`」；Go 的 api 侧 `Config` = `RestConf` + `TaskRpc` + `Auth`（`Auth` 是死配置不迁）⇒ 本域实际只有 1 个块 ⇒ 「聚合」还是「拆分」需拍板 |

---

## 8. 一句话总结

**A 列 Go** = 两个进程（api 18886 / rpc 9005）+ 8 层 `internal/` + 3 份重复的 `task_progress` 结构体 + `internal/sink/` 与 `internal/Init/` 两个大包 + **零鉴权 + 零业务码 + 零指标**；
**B 列 RAG** = 同名 Java 包 + MyBatis-Plus + Flyway（独立账本）+ 全 Spring Bean + `mq/` 包 10 个类承载 Kafka 侧；
**C 列 RAG-V1** = **单 app**（HTTP 8890 + gRPC 9006）+ **10 个子包** + **29 个空壳** + **无 `mq/` 包** + task 域**刻意无错误契约类**。
⇒ **文件名与职责逐条对应；行为以 A 列 Go 源码为准。**
⇒ ⚠️ **C 列最大的两个缺口是 Kafka 消费侧（`mq/`）与 `goka.Persist` 的存储选型** —— 本表只标出落点，不预设方案。
