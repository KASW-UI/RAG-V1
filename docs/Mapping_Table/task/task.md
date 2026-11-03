# Mapping_Table/task/task.md — task 域两方文件名对照表

> **本表目的**：把 **B 列**（RAG 仓，判定基准）→ **C 列**（RAG-V1，Spring 惯用目标形态）两棵树里 **task 域**的文件名逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 路径 | 角色 |
> |---|---|---|---|
> | **B** | `RAG` | `service/task/` | **判定基准**（唯一真理；api/rpc 分离） |
> | **C** | `RAG-V1` | `service/task/` | Spring 惯用目标形态（api/rpc 合并为 `task-service` 单 app，D1） |
>
> **格式契约**：[`README.MD`](../README.MD) · **章节骨架**照抄 RAG 那份 `docs/MappingTable/users.md`。
>
> 📌 **2026-11-02 · D16**：判定基准改为「**B 侧是唯一真理**」⇒ **原第三列已物理删除**（见 [`README.MD`](../README.MD)）⇒ 本表由**三方**改为**两方**（B/C）。

---

## 〇、三条读表纪律（本表专用）

1. ⚠️ **B 列是唯一判定基准**（RAG 仓）。本表回答「RAG 的哪个文件对应 RAG-V1 的哪个文件」；
   **B 侧自身的漂移**（含 B 侧与第三方的既有差异）由 RAG 的 `docs/DRIFT/task/` 台账与
   本仓 `docs/Intentional_Deviations/` 负责 ⇒ **继承并登记，不回溯**。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 RAG 的 `docs/DRIFT/task/` 台账的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责**。RAG-V1 的每个 Java 文件目前都只有 `package` + 空类型 + TODO javadoc
   ⇒ **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

> 📌 **映射类型只有 4 种**（不发明新词），描述 **B → C** 的文件数关系 ——
> `1:1`（一个 B 文件 → 一个 C 文件）· `1:N`（一个 B 文件 → N 个 C 文件）· `N:1`（N 个 B 文件 → 一个 C 文件）· `C 新增`（B 侧无对应物，是 RAG-V1 形态本身需要的新文件）。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| B (RAG，判定基准) | C (RAG-V1, Spring 目标) | 映射类型 | 备注 |
|---|---|---|
| `service/task/task-api/` | `service/task/`（`service/task/src/main/java/com/rag/task/advice + service/task/src/main/java/com/rag/task/config + service/task/src/main/java/com/rag/task/controller + service/task/src/main/java/com/rag/task/dto + service/task/src/main/java/com/rag/task/service` 子树） | 1:N | C 把 api 侧并进单 app；B 侧的 `api/{advice,client,config,controller,dto,service}` 六个角色包被 C 的 `@ConfigurationProperties` / `@RestController` / `@Service` / 构造注入取代（`client/` 因 D2 域内直连而消失） |
| `service/task/task-rpc/` | `service/task/`（`service/task/src/main/java/com/rag/task/wiring + service/task/src/main/java/com/rag/task/config + service/task/src/main/java/com/rag/task/domain/entity + service/task/src/main/java/com/rag/task/domain/service + service/task/src/main/java/com/rag/task/grpc + service/task/src/main/java/com/rag/task/mapper + service/task/src/main/java/com/rag/task/messaging + service/task/src/main/java/com/rag/task/service` 子树） | 1:N | 同上；B 侧 `rpc/{bootstrap,config,domain,mapper,mq,service}` 六个角色包收敛为 C 的 `rpc/{config,domain,mapper,service,wiring,messaging}` |
| `task-api/src/main/resources/application.yml` | `service/task/src/main/resources/application.yml` | N:1 | api + rpc 两个 yml 合成 1 份；**B 侧已是 Spring 标准键**（`server.port` / `grpc.server.port` / `spring.datasource` / `task.*`），C 侧合并进同一份并改挂 `spring.cloud.nacos.discovery` |
| `task-rpc/src/main/resources/application.yml` | 同上（同一份 yml） | N:1 | 同上 |
| `service/task/task-proto/src/main/proto/task.proto` | `proto/src/main/proto/task.proto` | 1:1 | `package task` 两侧相同；**仅 option 块不同** —— B 侧 `option java_package = "com.rag.task.task.proto"` → C 侧 `option java_package = "com.rag.proto.task"`（`java_multiple_files` 两侧都有） |
| **无对应物** | **无对应物** | 1:1 | ⚠️ `task.logs.raw` 的**原始日志由外部 filebeat 打进**（**B 仓与本仓都没有该配置文件** —— 它是外部进程的配置，不是业务代码）⇒ **两侧 Java 侧都无对应物**（不是「待补」） |
| **无对应物**（由 `task-proto` 的 protoc 插件生成） | **无对应物**（由根级 `proto/` 模块的 protoc 插件生成） | 1:1 | 生成物，不手写、不逐行对照 |
| **无对应物**（`task-proto` 模块产出） | **无对应物**（根级 `proto/` 模块产出） | 1:1 | 生成物 |
| — | **无 contract 模块 / 无 `contract` 包** —— 本域无错误码空间；`.proto` 集中在根级 `proto/src/main/proto/task.proto` | 1:1 | ⚠️ **刻意为空** —— 详见 §1.3 |
| — | `service/task/`（**40 个 `.java`**，**2026-11-03 全部落地 · 零空壳**） | C 新增 | C 的实体模块 |

> ⚠️ **task 域没有 `-common`**：B 侧 `service/task/pom.xml` 的 `<modules>` 只有 3 项（`task-api`/`task-rpc`/`task-proto`，**无 `task-common`**），C 侧 `service/task/pom.xml` 只有 `task-service` 一项（proto 走根级 `proto/` 模块） ⇒ **两侧一致，不是遗漏**。

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `task/api/`（`TaskApiApplication`） | `com.rag.task`（`TaskServiceApplication.java`） | 入口类合并（D1）|
| `task/api/advice/` | `advice/` | 直接压平（`GlobalExceptionHandler` → `TaskApiExceptionHandler`；⚠️ **B 侧无 ResponseAdvice**）|
| `task/api/client/` | **—（D2 域内直连 ⇒ 删除）** | 域内自我 gRPC 往返取消 |
| `task/api/config/` | `config/` | 与 rpc 侧合并（含 `GoStyleRawBodyArgumentProcessor`）|
| `task/api/controller/` | `controller/` | 直接压平 |
| `task/api/dto/` | `dto/task/` | 直接压平后**按业务对象拆 1 个子包**（D11） |
| `task/api/service/` | `service/` | 与 rpc 侧合并 |
| **—（B 侧无 `task-common`）** | **—（C 侧无 `contract/` 包）** | ⚠️ **两侧一致**：B 侧 task **无错误码空间** ⇒ 域内不建 `contract/`（不是缺漏）|
| `task/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `task/rpc/config/` | `config/` + `messaging/` | Kafka topic 落到 `messaging/`（`TaskKafkaTopics`）|
| `task/rpc/domain/` | `domain/entity/` | 直接压平（`UserLikeCountEntity` 与 `TaskProgressEntity` **同包**）|
| `task/rpc/mapper/` | `mapper/` | 直接压平 |
| `task/rpc/mq/`（11 类） | `messaging/`（**12 类 · 2026-11-03 全部落地**）| ✅ **消费侧已接住**：`TaskUserLikeProgressSink` / `TaskArticleLikeProgressSink`（消费 + 1s 定时 flush）· `TaskUserCountConsumer` / `TaskArticleCountConsumer` / `TaskRawDiagnosticConsumer` · `TaskRawLogRouter` / `TaskCountStore` / `TaskLogEvent` / `TaskArticleProgressEvent` / `TaskGoCompatibleJson` / `TaskTraceAttributes`（**B 侧包级静态类 → C 侧独立类**，见 §1.2 #R-19）⇒ **全仓 `@KafkaListener` 在 task 域命中 6 处** |
| `task/rpc/service/` | `service/` + `domain/service/` + `messaging/` | 领域服务另出 `domain/service/`；`TaskRewardPublisher` 落 `messaging/` |
| `task/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `TaskServiceGrpcImpl` → `TaskGrpcService` |
| `task-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.task`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 api 侧（`advice + config + controller + dto + service`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `task-api/.../TaskApiApplication.java` | `service/task/src/main/java/com/rag/task/TaskServiceApplication.java` | 1:N | C 把 api 的 main 与 rpc 的 main **合并成唯一入口**（D1）；`@SpringBootApplication` + `@EnableDiscoveryClient`；**C 的这一个类同时承载 HTTP 端口与 gRPC 端口** |
| `task-api/src/main/resources/application.yml`（**无 `Properties` 类**） | `service/task/src/main/java/com/rag/task/config/TaskRewardProperties.java` + `application.yml` | 1:N | C 显式建 `@ConfigurationProperties` 类（**B 侧 `task-api` 没有任何 `Properties` 类**，配置全在 yml）。⚠️ B 侧 `Auth` 段**零消费点**（`grep` 全 `api/` 只命中结构体与 yaml 4 行）⇒ **不迁**；`TaskRpc` → `grpc.client.*`、`Telemetry` → `otel.*`<br>⚠️ **2026-11-03 改名**：原 `TaskApiProperties.java`（prefix `task.api`）在 api+rpc 合并后**零消费点（哑弹）** ⇒ 改名为 **`TaskRewardProperties.java`**（prefix `task.reward`，**键名逐字不变**）—— 它是本域唯一「**B 侧有配置 ＋ C 侧有真实消费点 ＋ B 侧无 Properties 类**」的块 |
| `task-api/.../controller/TaskController.java` | `service/task/src/main/java/com/rag/task/controller/TaskController.java` | 1:1 | ⚠️ **前缀是 `/taskcenter/v1`，不是 `/task/v1`**；**B 侧无任何 JWT 依赖、无 `interceptor/` 包** ⇒ 本域端点**零鉴权** |
| `task-api/.../controller/TaskController.java` + `config/TaskApiRawBodyConfig.java` + `config/GoStyleRawBodyArgumentProcessor.java` + `config/TaskApiJsonBodyConfig.java` | `service/task/src/main/java/com/rag/task/controller/TaskController.java` + `service/task/src/main/java/com/rag/task/config/TaskApiRawBodyConfig.java` + `service/task/src/main/java/com/rag/task/config/GoStyleRawBodyArgumentProcessor.java` + `service/task/src/main/java/com/rag/task/config/TaskApiJsonBodyConfig.java` | 1:N | C 的三件 raw-body 配置**都在**（因为 B 侧不看 `Content-Type` ⇒ 必须换掉 Spring 默认的 `RequestResponseBodyMethodProcessor`）；⚠️ **不得注册 `common/src/main/java/com/rag/common/web/JsonBodyConverter`**（它复刻 `httpx.Parse` 的 `withJsonBody` 判据，会**丢弃**非 JSON body） |
| `task-api/.../service/TaskGetService.java` | `service/task/src/main/java/com/rag/task/service/TaskApiService.java` + `service/task/src/main/java/com/rag/task/service/TaskApiSupport.java` | 1:N | C 按「用例内聚」判据拆成 Service + Support；⚠️ B 侧 `out := make([]types.TaskInfo, 0, len(...))` ⇒ **空列表输出 `[]` 绝不输出 `null`** |
| `task-api/.../dto/GetTaskReq.java` + `dto/GetTaskResp.java` + `dto/TaskInfo.java` | `service/task/src/main/java/com/rag/task/dto/task/GetTaskReq.java` + `service/task/src/main/java/com/rag/task/dto/task/GetTaskResp.java` + `service/task/src/main/java/com/rag/task/dto/task/TaskInfo.java` | 1:N | C 改 **immutable record**（D5）；JSON key 契约不变（`id` / `tasks` / `name`/`desc`/`taskId`/`completionProgress`/`requiredProgress`） |
| `task-api/.../client/TaskRpcClient.java`（`@GrpcClient`） | **无对应文件** | 1:1 | ⚠️ api+rpc 合并后**域内 gRPC 调用不再跨进程** ⇒ C 的 `api/` 下**没有 `client/` 包**；`TaskApiService` 直落 `rpc/service/TaskDomainService` |
| `task-api/.../advice/GlobalExceptionHandler.java` | `service/task/src/main/java/com/rag/task/advice/TaskApiExceptionHandler.java` | 1:1 | ⚠️ B 侧 `GlobalExceptionHandler` **没有** 200 + `{code,msg}` 信封 ⇒ 失败响应**不是** `{code,msg}` 信封，是裸 text/plain（真实 HTTP 状态码 400 / 413 / 404 / 405 / 500） |
| **无对应物**（B 侧无 ResponseAdvice） | `service/task/src/main/java/com/rag/task/advice/TaskApiResponseAdvice.java` | C 新增 | ✅ **职责已定（2026-11-03）**：**保留类，`beforeBodyWrite` 原样返回 body** ⇒ 与「B 侧没有这个类」**逐字节相同**（成功响应 = `{"tasks":[…]}`，**无信封**）。⚠️ **不做**「加 `{code,msg,data}` 信封」—— 那会**新增一层 wire 差异**；真证据 = `TaskApiWireContractRealTomcatTest`（13 例，断言成功体**无信封**） |
| `service/common/.../spring/HttpMiddlewareFilter.java` | `common/src/main/java/com/rag/common/observability/`（`ObservabilityProperties` + `ObservabilityCustomizer`） | 1:1 | C 的 `common/` 是**真 starter**（`common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java`），不再是普通 jar |
| 同上 | `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` | 1:1 | — |
| `application.yml` 的 `observability.http-timeout: 3s` | `application.yml`（**已补** `observability.http-timeout: 3s` ＋ `observability.rpc-timeout: 2s`） | 1:1 | ⚠️ B 侧**显式写在 yaml 里**（`observability.http-timeout: 3s`，不是隐式默认）⇒ Java 侧必须显式写出来 |
| `common/protection/GoStyleMaxBytesFilter` | `common/src/main/java/com/rag/common/protection/MaxBytesFilter.java` | 1:1 | B 侧同样由 `common/protection/GoStyleMaxBytesFilter` 承担 ⇒ 413 裸状态码、无 body |

### 1.2 rpc 侧（`wiring + config + domain/entity + domain/service + grpc + mapper + messaging + service`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `task-rpc/.../TaskRpcApplication.java` | `service/task/src/main/java/com/rag/task/TaskServiceApplication.java`（与 api 合并） | 1:N | C 的入口**只剩 1 个**；**B 侧 `task-rpc` 也没有 reward worker**（只有 `TaskRewardPublisher` 这个生产端，worker 从未启动）⇒ reward 消费侧永不启动 |
| `task-rpc/.../bootstrap/NacosRegistration.java` | `service/task/src/main/java/com/rag/task/wiring/NacosRegistration.java` | 1:1 | 启动后显式注册到 Nacos（服务名 `task-rpc`），**不依赖自动注册**（自动注册依赖 Spring Web，纯 gRPC 进程会被拉起 web 容器）。⚠️ **Bean 名必须显式指定** —— `NacosServiceRegistryAutoConfiguration` 也会注册一个名为 `nacosRegistration` 的 Bean，裸 `@Component` 会撞名 ⇒ Boot 默认 `allow-bean-definition-overriding=false` ⇒ **上下文直接起不来** |
| `task-rpc/src/main/resources/application.yml` + `config/TaskKafkaTopics.java` + `config/TaskSinkProperties.java` | `service/task/src/main/java/com/rag/task/messaging/TaskKafkaTopics.java` + `service/task/src/main/java/com/rag/task/config/TaskSinkProperties.java` + `application.yml` | 1:N | ⚠️ **B 侧 `task-rpc` 也没有 `client/` 包**（`PointsRpc`/`UserRpc` 从未落地）⇒ **不建 `client/` 包**；`LikeRedis` 段名是历史遗留（本服务里没有 like） |
| `task-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:N | B 侧显式提供 **Jackson 2** 的 `ObjectMapper` Bean（关 `FAIL_ON_UNKNOWN_PROPERTIES` + `LogicalType.Textual` 强转 `Fail`）—— B 侧需要它是因为 Boot 4 默认装配的是 Jackson 3。⚠️ **D14：全项目 = Jackson 2 + Boot 3.5** ⇒ C 侧 Boot 自动装配的 `ObjectMapper` **本身就是 Jackson 2** ⇒ 等价物落在**根级 `common/`** 的 `Coercion`（`applyTo`）+ `JsonBodyConverterAutoConfiguration`（`Jackson2ObjectMapperBuilderCustomizer`），**不在域内建同名类** |
| `task-rpc/.../bootstrap/TaskSchemaGate.java` + `domain/` + `mapper/` + `service/` | `service/task/src/main/java/com/rag/task/wiring/TaskSchemaGate.java` + `service/task/src/main/java/com/rag/task/domain/entity` + `service/task/src/main/java/com/rag/task/mapper` + `service/task/src/main/java/com/rag/task/domain/service + service/task/src/main/java/com/rag/task/messaging + service/task/src/main/java/com/rag/task/service` | 1:N | Redis / PG 客户端由 Boot 装配；**B 侧 `TaskSchemaGate` 的「表与进程同生共死」语义** ⇒ C 侧沿用 `TaskSchemaGate`（启动时校验两表存在） |
| `task-rpc/.../service/TaskDefs.java` + `domain/TaskProgressEntity.java` + `domain/UserLikeCountEntity.java` + `mapper/TaskProgressMapper.java` + `mapper/UserLikeCountMapper.java` | `service/task/src/main/java/com/rag/task/service/TaskDefs.java` + `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` + `service/task/src/main/java/com/rag/task/domain/entity/UserLikeCountEntity.java` + `service/task/src/main/java/com/rag/task/mapper/TaskProgressMapper.java` + `service/task/src/main/java/com/rag/task/mapper/UserLikeCountMapper.java` | 1:N | ⚠️ **配置载体差异**：**B 侧 `TaskDefs` 是常量表**（`AllTaskDefs` 两条字面量）⇒ **B/C 两侧都不存在 `task_def` 表** ⇒ C 的 `TaskDefs` 同样是**常量表**，不是 Entity、不是 Mapper |
| `task-rpc/.../service/TaskDomainService.java` | `service/task/src/main/java/com/rag/task/domain/service/TaskDomainService.java` | 1:1 | 本域**唯一**的 gRPC 用例 |
| `task-rpc/.../service/impl/TaskServiceGrpcImpl.java` | `service/task/src/main/java/com/rag/task/grpc/TaskGrpcService.java` | 1:1 | `@GrpcService` |
| `task-rpc/.../mq/UserLikeProgressSink.java` + `service/TaskProgressWriter.java` + `service/TaskRedisKeys.java` + `service/TaskRewardPublisher.java` + `service/RedisPipelines.java` + `domain/UserLikeCountEntity.java` + `domain/TaskProgressEntity.java` + `mapper/UserLikeCountMapper.java` | `service/task/src/main/java/com/rag/task/messaging/TaskUserLikeProgressSink.java` + `service/task/src/main/java/com/rag/task/service/TaskProgressWriter.java` + `service/task/src/main/java/com/rag/task/service/TaskRedisKeys.java` + `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` + `service/task/src/main/java/com/rag/task/service/RedisPipelines.java` + `service/task/src/main/java/com/rag/task/domain/entity/UserLikeCountEntity.java` + `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` + `service/task/src/main/java/com/rag/task/mapper/UserLikeCountMapper.java` | 1:N | ✅ **已落地（2026-11-01）**：C 侧新建 `messaging/` 包 ⇒ sink 的「消费 + 定时 flush」职责**有了载体**（`@KafkaListener` + `@Scheduled`）⇒ **8 个职责位全部就位**（原缺口关闭） |
| `task-rpc/.../mq/ArticleLikeProgressSink.java` + 上列同一批共享类 | `service/task/src/main/java/com/rag/task/messaging/TaskArticleLikeProgressSink.java` + `service/task/src/main/java/com/rag/task/service/TaskProgressWriter.java` + `service/task/src/main/java/com/rag/task/service/TaskRedisKeys.java` + `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` + `service/task/src/main/java/com/rag/task/service/RedisPipelines.java` + `service/task/src/main/java/com/rag/task/domain/entity` + `service/task/src/main/java/com/rag/task/mapper` | 1:N | ✅ **已落地（2026-11-01）**：文章路 sink 消费侧**有了载体**（同 `messaging/` 包）；⚠️ **B 侧 `ArticleLikeProgressSink` 的奖励分值是 5 而 target 是 3**（`rewardPublisher.enqueue(..., 5L)`）⇒ 这个数值差要落在 C 的 `TaskRewardPublisher` 里 |
| `task-rpc/.../mq/UserLikeProgressSink.java`（`@KafkaListener` + `@Scheduled`） | `service/task/src/main/java/com/rag/task/messaging/TaskUserLikeProgressSink.java` | 1:1 | ✅ **已落地（2026-11-01）**：⚠️ **B 侧 `mq/` 的消费者不是独立进程** —— 是 `task-rpc` 这一个 Spring Boot 进程里的 `@KafkaListener` 容器（`docs/第二轮深挖-契约盘点.md:316`）⇒ C 侧落点是容器内的 listener，不是新 app |
| `task-rpc/.../mq/ArticleLikeProgressSink.java` | `service/task/src/main/java/com/rag/task/messaging/TaskArticleLikeProgressSink.java` | 1:1 | ✅ **已落地（2026-11-01）**；同上游（**Group=GroupKafkaFilter**，不是 `GroupFilterArticle`） |
| `task-rpc/.../mq/TaskRawDiagnosticConsumer.java` | `service/task/src/main/java/com/rag/task/messaging/TaskRawDiagnosticConsumer.java` | 1:1 | ✅ **已落地（2026-11-01）**：「什么都不做但不能删」（有真实 consumer group 与外部依赖） |
| `task-rpc/.../mq/TaskTraceAttributes.java` | `service/task/src/main/java/com/rag/task/messaging/TaskTraceAttributes.java`（**独立类**）＋ `TaskUserLikeProgressSink.java` ＋ `TaskArticleLikeProgressSink.java` | 1:N | 两个 sink 的 **OTEL span 属性**（`messaging.system=kafka` / `messaging.destination` / `messaging.operation=consume\|flush` / `messaging.message.key` / `batch.size`）。⚠️ B 侧是**包级静态工具类**（`final class` + 私有构造）；**C 侧建了同名独立类**（`public final class`）⇒ **可见性放宽、行为零变化**（4 个 attribute 的名字 / 取值 / 写入顺序逐条照抄）；⚠️ `messaging.destination` 传的是逻辑名 `user_like_sink` / `article_like_sink`，**不是 topic 名** |
| `task-rpc/.../mq/TaskRawLogRouter.java` + `mq/TaskUserCountConsumer.java` + `mq/TaskArticleCountConsumer.java` + `mq/TaskCountStore.java` + `mq/TaskLogEvent.java` + `mq/TaskArticleProgressEvent.java` + `mq/GoJson.java` | `service/task/src/main/java/com/rag/task/messaging/TaskRawLogRouter.java` + `service/task/src/main/java/com/rag/task/messaging/TaskUserCountConsumer.java` + `service/task/src/main/java/com/rag/task/messaging/TaskArticleCountConsumer.java` + `service/task/src/main/java/com/rag/task/messaging/TaskCountStore.java` + `service/task/src/main/java/com/rag/task/messaging/TaskLogEvent.java` + `service/task/src/main/java/com/rag/task/messaging/TaskArticleProgressEvent.java` + `service/task/src/main/java/com/rag/task/messaging/TaskGoCompatibleJson.java` | 1:1 | ✅ **已落地（2026-11-01）**：C 侧新建 `messaging/` 包落这 7 类（B 侧 `GoJson` → C 侧 **`TaskGoCompatibleJson`**，**去掉原前缀**）；⚠️ B 侧 `TaskCountStore` 已用 **Redis `INCR` + group 命名空间**（`redis.opsForValue().increment(keyOf(group, key))`）落地「按 group 分目录的本地 KV 表」的等价物 ⇒ **选型已由 B 侧定案**（不再是待拍板项），C 侧照抄该落法 |
| **无对应物** | **无对应物** | 1:1 | **B 侧同样没有这个文件** ⇒ **不迁移**（不是「待补」） |
| `task-rpc/.../service/TaskRewardPublisher.java` | `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` | 1:1 | 幂等 ID 的加法语义**原样保留**（含天然碰撞） |
| `task-rpc/.../service/TaskRewardPublisher.java` | 同上 | 1:1 | ⚠️ 写入字段名是 `score`，而**消费端读的是 `add_score`** —— 不匹配，但消费端是死代码 ⇒ 不可观察 |
| **无对应物** | **无对应物** | 1:1 | **B 侧无消费端** ⇒ 不迁移；⇒ `stream:task:reward` **无消费者组**（无界增长） |
| **无对应物**（protoc 生成） | **无对应物**（protoc 生成） | 1:1 | 生成物 |

### 1.3 契约 —— ⚠️ task 域**无错误契约**（proto 在根级 `proto/` 模块）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `service/task/task-proto/src/main/proto/task.proto` | `proto/src/main/proto/task.proto` | 1:1 | 两侧 `package task` 完全相同 ⇒ **零偏离**；仅 option 块不同（B 侧 `java_package="com.rag.task.task.proto"` → C 侧 `java_package="com.rag.proto.task"`；`java_multiple_files=true` 两侧都有）；字段号 / 字段名 / 类型 5+1+1 逐条一致 |
| **B 侧无 `task-common` 模块**（`service/task/pom.xml` 的 `<modules>` 只有 `task-api`/`task-rpc`/`task-proto` 三项） | **C 侧无 `contract` 模块 / 无 `contract` 包（没有任何 `.java`）**；`.proto` 集中在根级 `proto/src/main/proto/task.proto` | 1:1 | ⚠️⚠️ **B 侧 task 没有错误码空间（无 common 包）⇒ C 列该模块无 Java 类（刻意）** —— **不是缺漏**。若将来 task 域要引入业务码，参照 `service/like/src/main/java/com/rag/like/contract/` 的 `CodeError`/`ErrCodes`/`ErrMsg`/`GrpcErrFactory` 四件套新建即可；**在此之前不建** |

> 📌 **「域内无 common」的四条可验证依据**（与 RAG 的 `docs/FileTree/task/api.md` §12.7 末同源）：
> ① B 侧 `service/task/` 全树零个 `common/` 目录、pom 的 `<modules>` 无 `task-common`；② `grep -rn "errmsg" service/task/` **零命中**；
> ③ 错误表达由类型承载 —— rpc 侧 `StatusRuntimeException`、api 侧 `TaskApiExceptionHandler`；
> ④ **B / C 两侧都不建** `task-common` ⇒ 一致，不是遗漏。

### 1.4 「`UserLikeCount` 实体的落位（B 侧与 `TaskProgressEntity` 同包 ⇒ 不会漏）」

> 📌 **本域最容易漏的一处**：`UserLikeCount`（表 `user_like_count`）在 **B 侧 `task-rpc/.../rpc/domain/`**，
> 与 `TaskProgressEntity` **同包**（不是独立目录）⇒ 按目录名盘点也不会漏。
> RAG-V1 的 `docs/第二轮深挖-契约盘点.md` §五（T-5）已为此**翻案**，
> C 列 `domain/entity/UserLikeCountEntity.java` 的 javadoc 里已写入该位置说明。

---

## 2. 持久层差异

| 维度 | B (RAG，判定基准) | C (RAG-V1，Spring 目标) | 备注 |
|---|---|---|---|
| ORM | MyBatis-Plus：`@TableName` + `@TableId` + `Mapper extends BaseMapper<E>` | 同 B，落 `service/task/src/main/java/com/rag/task/domain/entity` + `service/task/src/main/java/com/rag/task/mapper` | — |
| **同一张表的结构体重复** | 已收敛为 **1** 个 `TaskProgressEntity` | 收敛为 **1** 个 `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` | 两张表（`task_progress` / `user_like_count`）各 1 个 Entity；历史的多份重复结构体已在 B 侧消解 |
| `user_like_count.user_id` 类型 | `String`（`UserLikeCountEntity.userId`） | `String` | ⚠️ 与 `task_progress.user_id`（`Long`）**类型不同** |
| 建表机制 | Flyway `V1__create_task_progress.sql` + `V2__create_user_like_count.sql` | `src/main/resources/db/migration/` **V1 + V2 已落地（2026-11-03）** | ✅ **逐字复制**（`diff -r` 与 B 侧 `task-rpc/src/main/resources/db/migration/` **零差异**）⇒ B 侧列类型 / 索引 / 默认值由迁移脚本固定 |
| Flyway 账本表名 | `spring.flyway.table: flyway_schema_history_task`（**独立账本**，避免与 message/like/article 同库撞号） | `application.yml` **已补 `spring.flyway` 段**（`table: flyway_schema_history_task` ＋ `baseline-on-migrate: true` ＋ `baseline-version: 0`） | ✅ 与 B 侧逐字一致 ⇒ 同库 `first_db` / `public` schema 下**不撞号**；真证据 = `TaskServiceApplicationTests`（断言账本里有 V1/V2 且 `success=true`） |
| MyBatis-Plus 主键策略 | `id-type: INPUT`（**不用雪花**，复合主键 `(user_id, task_id)` 由业务给值） | `application.yml` **已补 `id-type: INPUT`** | ✅ 与 B 侧一致 ⇒ 本域无雪花 ID 调用 |
| jsonb / typeHandler | 本域两表**无 jsonb 列** | 不需要 | — |
| 连接池 | Hikari `maximum-pool-size: 50` / `minimum-idle: 50` / `max-lifetime: 1800000`（B 侧 yml 已自标【有意偏离 · 不做 1:1】：Hikari 硬拒 `< 30s`，照抄都做不到） | `application.yml` **已补 `hikari` 段**（同三个旋钮 ＋ **保留 `TimeZone=Asia/Shanghai` 连接串参数**） | ⚠️ B 侧连接串带 `TimeZone=Asia/Shanghai` ⇒ C 侧**已保留同一时区参数** |
| 事务边界 | sink 的 upsert 是**逐条** `Create` + `OnConflict{Columns, DoUpdates}`，**无显式事务** | 同（逐条） | ⭐ DB 级不变量在 SQL 里：`GREATEST`（单调）+ `CASE WHEN status='done' THEN status`（done 不可回退）+ `done_at = COALESCE(done_at, now())`（只写一次） |
| **`TaskDef` 配置载体** | `service/TaskDefs.java`（常量表；**不存在 `task_def` 表**） | `service/task/src/main/java/com/rag/task/service/TaskDefs.java`（常量表） | **不是 Entity、不是 Mapper、不是表** ⇒ 落点是「常量/配置载体」 |
| 常量重复 | `TaskDefs` 收口（`ALL_TASK_DEFS` 两条字面量） | `service/task/src/main/java/com/rag/task/service/TaskDefs.java` 收口 | C 侧落点与 B 同 |
| 雪花 ID | 不用 | 不用 | — |

---

## 3. HTTP / DI 差异

### 3.1 HTTP 层

| 维度 | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|
| 路由注册 | `@RequestMapping("/taskcenter/v1")` + `@PostMapping("/task/get")` | 同 B（`service/task/src/main/java/com/rag/task/controller/TaskController.java`） | ⚠️ **前缀不是 `/task/v1`**（L1 契约，与其它域的 `/user/v1`、`/message/v1` 全不同） |
| 端点数 / method | **1** 个：`POST` | 1 个 `@PostMapping` | — |
| **鉴权** | **无 `interceptor/` 包**（B 侧 `task-api` 零 JWT 依赖）；`Auth` 段是**死配置** | `service/task/src/main/java/com/rag/task/config/WebMvcConfig.java`（**已改写为否定式声明**：零拦截器 ＋ 零 CORS） | ✅ **职责已对齐（2026-11-03）**：本域**不建** `*JwtAuthInterceptor`、**不建** `interceptor/` 包 ⇒ 端点**零鉴权**；真证据 = 真 Tomcat **GET → 405**（若装了拦截器则是 401） |
| 身份来源 | 请求体 `{"id": …}`（`GetTaskReq.id`，接受 string \| number）⇒ **水平越权面**（任何调用方可查任意 uid） | 同（`service/task/src/main/java/com/rag/task/dto/task/GetTaskReq.java`） | B 侧既定行为，本表按原样记录 |
| 成功响应形状 | Controller 直接返回（**不包信封**）⇒ **`{"tasks":[…]}`，无 `{code,msg,data}`** | `service/task/src/main/java/com/rag/task/advice/TaskApiResponseAdvice.java`（**原样透传**，不加信封） | ✅ **职责已定（2026-11-03）** ⇒ 与 B 侧**逐字节相同**（真 Tomcat 断言成功体无信封） |
| 失败响应形状 | `advice/GlobalExceptionHandler` ⇒ **text/plain + `X-Content-Type-Options: nosniff` + 尾 `\n`**；状态码 400 / 413 / 404 / 405 / 500 各有来源 | `service/task/src/main/java/com/rag/task/advice/TaskApiExceptionHandler.java` + `common/src/main/java/com/rag/common/web/PlainTextErrors.java` / `RouterErrorResponses.java` | ⚠️ **B 侧不是 200 + `{code,msg}` 信封** |
| 请求体读取 | `TaskApiRawBodyConfig` + `GoStyleRawBodyArgumentProcessor` + `TaskApiJsonBodyConfig` ⇒ **不看 `Content-Type`** | 同 B（三件都在） | ⚠️ **不得注册 `common/src/main/java/com/rag/common/web/JsonBodyConverter`**（会丢弃非 JSON body ⇒ 新增行为） |
| 未知字段 | 默认 Jackson 行为（B 侧未开 `FAIL_ON_UNKNOWN_PROPERTIES`） | 同 | — |
|请求体上限|`common/protection/GoStyleMaxBytesFilter`（`MaxBytes = 1 MiB`）⇒ 413 裸状态码|`common/src/main/java/com/rag/common/protection/MaxBytesFilter.java`|—|
| 请求超时 | `observability.http-timeout: 3s`（**显式写在 yaml 里**） | `application.yml`（**已补**） | ⚠️ **B 侧是显式配置，不是隐式默认** |

### 3.2 DI 与进程拓扑

| 维度 | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|
| **进程拓扑** | **两个进程**：`task-api`（HTTP `18886`）+ `task-rpc`（gRPC `9005`） | **一个进程** `task-service`（HTTP `8890` + gRPC `9006`） | D1：api/rpc 合并（⚠️ **端口值两侧不同** —— 端口属 L1，须在 DRIFT 台账登记） |
| 依赖持有 | Spring 容器构造注入（**无 `ServiceContext` 类**） | 同 B | — |
| 域内 gRPC 调用 | `@GrpcClient("task-rpc")` → `TaskRpcClient`（跨进程） | **域内直调** `service/task/src/main/java/com/rag/task/domain/service/TaskDomainService.java` ⇒ **无 `service/task/src/main/java/com/rag/task/{advice,config,controller,dto,service}/client/` 包** | ⚠️ C 的 `grpc-client-spring-boot-starter` 依赖目前**没有使用方**（本域唯一的下游是本域自己） |
| 服务发现 | Nacos（`task-api` / `task-rpc` 各自注册） | Nacos（`application.yml` 有 `spring.cloud.nacos.discovery`） | — |
| **后台任务落点** | `@KafkaListener` 容器 + `@Scheduled`（**B 侧 `mq/` 的消费者都在 `task-rpc` 这一个进程内**） | ✅ **已关闭（2026-11-03）** —— C 侧 `messaging/` 包 **12 类全部落地**（**6 个 `@KafkaListener` 真进 registry**） | ⚠️ **不是独立进程** ⇒ C 侧落点是**容器内的 listener**，**不是新 app**（D1 对本域成立，无例外） |
| gRPC 服务端拦截器 | `common/spring/RpcServerInterceptorFactory` + **显式** `GrpcServerConfigurer.intercept(...)` | ✅ **已由共享层承担** —— `common/autoconfigure/ProtectionAutoConfiguration.java:231-240` 的 `seaGrpcServerConfigurer` 调 `server.intercept(...)` | ✅ **守门 A3 PASS**（`check-arch-invariants.py`：「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」）⇒ **域内不写 `rpcInterceptorConfigurer`** |
| gRPC 超时 | `observability.rpc-timeout: 2s` | `application.yml`（**已补**） | — |
| 配置绑定 | 无 `Properties` 类，全在 `application.yml` | `service/task/src/main/java/com/rag/task/config/TaskRewardProperties.java`（prefix `task.reward`）<br>＋ `messaging/TaskKafkaTopics.java`（prefix `task.kafka`）<br>＋ `config/TaskSinkProperties.java`（prefix `task.definitions`） | ✅ **已按配置块拆成 3 个 `*Properties`**（`docs/第二轮深挖-契约盘点.md` §4.3 的要求）⇒ 原 `TaskApiProperties`（prefix `task.api`）在合并后**零消费点** ⇒ **已改名**为 `TaskRewardProperties`（键名逐字不变） |
| 死配置 | `Auth`（api 侧）—— **零消费点** | 不迁 | — |

---

## 4. 公共模块对照

| 项 | B (RAG) | C (RAG-V1) | 映射类型 | 备注 |
|---|---|---|---|---|
| **域内 common 包** | ❌ **无** `task-common` 模块 ⇒ **无错误码空间** | ❌ **无** ⇒ 域内**无 `contract/` 包**（**无 Java 类**）；`.proto` 在根级 `proto/` 模块 | 1:1 | ⚠️ **两侧一致，是刻意为空，不是缺漏** |
| 全局共享层形态 | `service/common/`（**普通 jar**，被各域 `compile` 依赖） | `common/`（**真 starter**，`common/src/main/java/com/rag/common/autoconfigure/` 7 个 auto-config） | 1:N | D3 |
| 错误码 / 信封 | ❌ 无 | `common/src/main/java/com/rag/common/contract/ApiResponse.java` + `common/src/main/java/com/rag/common/exception/BusinessException.java` | C 新增 | ⚠️ **本域目前不需要业务码**（只有 gRPC 标准码 3 + 3 条裸 error 文案）⇒ 若 `TaskApiResponseAdvice` 真的要加信封，需先回 B 侧确认 |
|text/plain 错误形状|`common/http/GoStylePlainTextErrors.java`|`common/src/main/java/com/rag/common/web/PlainTextErrors.java`|1:1|—|
|路由 404 / 405 形状|`common/http/GoStyleRouterErrorResponses.java`|`common/src/main/java/com/rag/common/web/RouterErrorResponses.java`|1:1|—|
| gRPC status → HTTP 映射 | `common/errcode/CodeFromGrpcError`（**15 分支**） | `common/src/main/java/com/rag/common/web/GrpcStatusMapper.java` | 1:1 | ⚠️ **不是** users 域那套 5 分支业务码映射 |
| `,string` int64 解析原语 | `users/users-common/.../http/GoStyleStringInt64.java`（**跨域复用**） | `common/src/main/java/com/rag/common/web/StringInt64.java`（**提到全局 common**） | 1:1 | C 侧已归入 `common/` ⇒ **不再有跨域模块依赖** |
| HTTP span / 超时 | `common/spring/HttpMiddlewareFilter.java` | `common/src/main/java/com/rag/common/observability/`（`ObservabilityProperties` + `ObservabilityCustomizer`）+ `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | 1:1 | — |
|请求体上限|`common/protection/GoStyleMaxBytesFilter.java`|`common/src/main/java/com/rag/common/protection/MaxBytesFilter.java`|1:1|—|
| Kafka topic 单点定义 | `task-rpc/.../config/TaskKafkaTopics.java`（`in-topic` / `raw-user-topic` / `raw-article-topic` / `out-user-topic` / `out-article-topic` 五个键） | `service/task/src/main/java/com/rag/task/messaging/TaskKafkaTopics.java` | 1:1 | ⚠️ `task.logs.filtered.User` / `task.logs.filtered.Article` **同时被 like 域生产** ⇒ 跨服务 L1 契约 |
| MQ 载荷契约 | `task-rpc/.../mq/TaskArticleProgressEvent.java` | `service/task/src/main/java/com/rag/task/messaging/TaskArticleProgressEvent.java`（like 域侧有 `service/like/src/main/java/com/rag/like/contract/TaskArticleProgressMsg.java`） | 1:1 | ✅ **已落地（2026-11-01）**：C 侧 task 域**补上了这份契约类型** ⇒ **跨域 MQ 载荷在 C 侧仍是两份**（task 域 `TaskArticleProgressEvent` + like 域 `TaskArticleProgressMsg`）⇒ ⚠️ 两份的**字段名与 topic 必须逐字对齐**（L1 契约层） |

---

## 5. 决策锁

**合并 api + rpc 为单 app（D1）6 处同时改**：
1. `service/task/task-api/` 的 11 个 `.java` + `service/task/task-rpc/` 的 28 个 `.java`（合计 39，2026-11-03 实测）⇒ 合并进 `service/task/`（C 列现有 **40** 个 `.java` —— 39 个 B 侧类 **＋** `messaging/TaskTraceAttributes.java`（B 侧包级静态类 → C 侧独立类）；⚠️ **2026-11-01 的 39 是空壳期数字**，已过时）；
2. 两个 `Application.java`（`TaskApiApplication` / `TaskRpcApplication`）⇒ **合成 1 个** `TaskServiceApplication`（`@SpringBootApplication` + `@EnableDiscoveryClient`）；
3. 两个 `application.yml` ⇒ **合成 1 份**（端口：HTTP `8890` + gRPC `9006`）；
4. api → rpc 的调用从「跨进程 `@GrpcClient`」改为「**域内直调** `domain/service/TaskDomainService.java`」⇒ **删 `{advice,config,controller,dto,service}/client/` 包**；
5. `pom.xml` 里 `grpc-server-spring-boot-starter` 与 `grpc-client-spring-boot-starter` **同时**保留（后者本域暂无使用方）；
6. B 侧 `mq/` 的 Kafka 消费入口落点从「`task-rpc` 进程内的 listener」改为「**C 侧合并后的同一容器内 listener / scheduler**」⇒ **不新增 app**。

**为什么换**：D1 = 一域一 app。B 侧的 `mq/` 消费者**不是独立进程**（是 `task-rpc` 这一个 Spring Boot 进程里的 `@KafkaListener` 容器）⇒ 合并后不需要额外容器；代价是 api 与 rpc 共享同一份配置与同一个进程生命周期。

**改「配置载体」的落点（`TaskDef` ⇒ 常量表）3 处同时改**：
1. `domain/entity` 下**不建** `TaskDefEntity`（**B 侧 `task_def` 表不存在** —— `TaskDefs` 是常量表）；
2. **不建** `TaskDefMapper`；
3. `service/TaskDefs.java` 作为**纯常量/配置载体**存在（`ALL_TASK_DEFS` 两条字面量 + 两个 sink 硬编码常量的收口点）。

**为什么换**：B 侧的 `TaskDef` 是**常量表**（不是 Entity、也不是表）；把它翻成 Entity 会凭空造出一张 B 侧不存在的表。

**task 域**不建** `contract/` 包（刻意为空）2 处同时改**：
1. 根级 `proto/` 模块提供 `task.proto`（pb 包名 `com.rag.proto.task`）⇒ 域模块**不再自带** proto 源文件；
2. `service/task/src/main/java/com/rag/task/` 下**不建** `contract/` 子包 ⇒ **不建** `CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`（**B 侧无错误码空间**）。

> 📌 **时点标注（2026-10-26）** —— 本小节原文写的是「建 `task-contract` 模块但不放任何 Java 类」。
> **该形态已被 D7 取代**：10 个 `*-contract` 子模块**全部删除**（proto 集中到根级 `proto/`，错误契约落进域模块的 `contract/` 包）。
> ⇒ task 域因为 **B 侧根本没有错误码空间**，所以连域内 `contract/` 包都没有 —— **这不是缺漏，是两侧一致的刻意为空**。

**为什么换**：proto 是 L1 外部契约，跨服务共享 ⇒ 集中到根级 `proto/` 模块；但错误码空间 B 侧**根本不存在** ⇒ 建了就是凭空引入业务码，与 B 侧不一致。

**保留「裸 JSON 读 body」原语 3 处同时改**：
1. `config/TaskApiRawBodyConfig.java` —— 把 Spring 默认的 `RequestResponseBodyMethodProcessor` 换掉；
2. `config/GoStyleRawBodyArgumentProcessor.java` —— 新造 `HttpInputMessage` 的那一环（body 在转换器之前就被换掉了 ⇒ 改转换器没用）；
3. `config/TaskApiJsonBodyConfig.java` —— 让 `@RequestBody` 无视 `Content-Type`。

**为什么换**：B 侧是裸 JSON 读取，**没有媒体类型协商**；不换掉处理器就会新增「非 JSON body 被丢弃 / 415」这类 B 侧不存在的行为。

**不建 `*JwtAuthInterceptor`（本域零鉴权）2 处同时改**：
1. `advice + config + controller + dto + service` 下**不建** `interceptor/` 包；
2. `config/WebMvcConfig.java` 的职责**不写**「注册 JwtAuthInterceptor + public 路径白名单」—— 本域没有 public/protected 之分。

**为什么换**：B 侧 `task-api` **零鉴权**（无 `interceptor/` 包、无 JWT 依赖）；`Auth` 是零消费点的死配置 ⇒ 加鉴权 = **新增行为**。

---

## 6. 跨模块全局约定（值逐条核对 **B 侧源码**）

| 项 | 值 | 来源（B 侧源码 `文件:行号`） |
|---|---|---|
| **proto 字段号** | `Task{name=1, desc=2, task_id=3, completion_progress=4, required_progress=5}`；`GetTaskReq{user_id=1}`；`GetTaskResp{repeated Task task=1}` | `service/task/task-proto/src/main/proto/task.proto:6-21` |
| **proto package / service** | `package task`；`service TaskService { rpc GetTask(GetTaskReq) returns (GetTaskResp) }` ⇒ 方法路径 `/task.TaskService/GetTask` | `service/task/task-proto/src/main/proto/task.proto:3, 22-24` |
| **错误码** | ⚠️ **无业务码空间**。唯一业务错误 = **gRPC 标准码 3** `codes.InvalidArgument` + 文案 `"user_id invalid"` | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskDomainService.java:91-93` |
| **错误文案（裸 error）** | `"id must be a string or integer"`、`"user_id invalid"`（**两处同文案**） | `service/task/task-api/src/main/java/com/rag/task/task/api/dto/GetTaskReq.java:71, 74` |
| **JWT claims** | ⚠️ **不适用** —— 本域零鉴权（B 侧 `task-api` 无 `interceptor/` 包、无 JWT 依赖），无 `userId` / `status` claim | `service/task/task-api/`（`find` 零命中 `interceptor/`） |
| **表名** | `task_progress`（复合主键 `user_id` + `task_id`）、`user_like_count`（`user_id` 是 **String**）；⚠️ **无 `task_def` 表** | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/domain/TaskProgressEntity.java` + `UserLikeCountEntity.java` 的 `@TableName` |
| **HTTP path** | `POST /taskcenter/v1/task/get`（**`taskcenter` 不是 `task`**） | `service/task/task-api/src/main/java/com/rag/task/task/api/controller/TaskController.java:60, 87` |
| **HTTP 请求体键** | `{"id": <string \| number>}`（两种形态都接受） | `service/task/task-api/src/main/java/com/rag/task/task/api/dto/GetTaskReq.java:82-101` |
| **HTTP 响应键** | `{"tasks":[{name, desc, taskId, completionProgress, requiredProgress}]}`，**无信封**，空列表输出 `[]` | `service/task/task-api/src/main/java/com/rag/task/task/api/dto/GetTaskResp.java` + `TaskInfo.java` |
| **Kafka topic（5 个）** | `task.logs.raw`（`in-topic`）、`task.logs.raw.User`（`raw-user-topic`）、`task.logs.raw.Article`（`raw-article-topic`）、`task.logs.filtered.User`（`out-user-topic`）、`task.logs.filtered.Article`（`out-article-topic`） | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/config/TaskKafkaTopics.java:42-54` |
| **Kafka consumer group（7 个）** | `task.rpc.consumer31`（`group-kafka-raw`，**在用**）、`task.rpc.consumer41`（`group-goka`，**在用**）、`task.rpc.consumer61`（`group-goka-user`，**在用**）、`task.rpc.consumer71`（`group-goka-article`，**在用**）、`task.rpc.consumer81`（`group-filter-user`，**在用**）、`task.rpc.consumer101`（`group-kafka-filter`，**在用**）、`task.rpc.consumer91`（`group-filter-article`，⚠️ **零使用** —— 文章路用的是 `group-kafka-filter`） | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/config/TaskKafkaTopics.java:57-75` |
| **Redis key（10 个）** | `task:progress:{uid}`（读路径 key）、`task:progress:{uid}:1902`、`task:progress:190:{uid}:{articleID}`、`task:init:{uid}:1902`（90d）、`task:inits:190:{uid}:{articleID}`（24h）、`task:done:{uid}:1902`（90d）、`task:done:190:{uid}`（120d）、`like:total:{uid}`（90d）、`article:like:cnt:{articleID}`（120d）、`stream:task:reward` | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskRedisKeys.java:69, 82, 93, 104, 114, 128, 138, 150, 158, 173` |
| **Redis value 编码** | `"status\|progress\|target"`（`\|` 分隔，**三段**，不是三段就全零） | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskDomainService.java:201-210` |
| **Redis Stream** | `stream:task:reward`，字段 `{reward_id, uid, task_id, ts, **score**}`；消费组 `task-reward-group`（**只存在于配置面** —— worker 从未启动） | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskRewardPublisher.java:66-77` + `service/task/task-rpc/src/main/resources/application.yml:300-306` |
| **指标名** | ⚠️ **本域零指标** —— B 侧 `task-api` + `task-rpc` 全树**无任何 metric 调用**（无 `metrics/` 包） | `find service/task -type d -name metrics` ⇒ 零命中 |
| **服务名 / 端口** | `task-api` @ `0.0.0.0:18886`；`task-rpc` @ `0.0.0.0:9005`（Nacos 服务名） | `service/task/task-api/src/main/resources/application.yml:18, 75` + `service/task/task-rpc/src/main/resources/application.yml:9, 225` |
| **任务定义（2 条）** | `{190, "文章获赞任务", "有一篇文章点赞大于3", 3}`、`{1902, "点赞任务", "给文章点赞5次", 5}` | `service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskDefs.java:82-84` |

---

## 7. 覆盖度自查

> **自查命令**（在对应域目录下跑）：
> ```bash
> # C 列覆盖率
> find "/Users/pr/Documents/GitHub/RAG-V1/service/task" -name '*.java' \
>   -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
> ```
> 📌 **B 列覆盖率**由 `tools/check-mapping-coverage.py` 机器核对（从 RAG 磁盘反查，不采信文档自报数）
> ⇒ `python3 tools/check-mapping-coverage.py task`。

### 7.1 C 列零遗漏（39 / 39 = **100%**）

| # | C 列 `.java` | 出现在本表 |
|---|---|---|
| 1 | `TaskServiceApplication.java` | §0 / §1.1 / §1.2|
| 2 | `service/task/src/main/java/com/rag/task/advice/TaskApiExceptionHandler.java` | §1.1|
| 3 | `service/task/src/main/java/com/rag/task/advice/TaskApiResponseAdvice.java` | §1.1（⚠️ 职责待拍板）|
| 4 | `service/task/src/main/java/com/rag/task/config/GoStyleRawBodyArgumentProcessor.java` | §1.1 / §5|
| 5 | `service/task/src/main/java/com/rag/task/config/TaskApiJsonBodyConfig.java` | §1.1 / §5|
| 6 | `service/task/src/main/java/com/rag/task/config/TaskRewardProperties.java` | §1.1 / §3.2（⚠️ 2026-11-03 由 `TaskApiProperties` 改名）|
| 7 | `service/task/src/main/java/com/rag/task/config/TaskApiRawBodyConfig.java` | §1.1 / §5|
| 8 | `service/task/src/main/java/com/rag/task/config/WebMvcConfig.java` | §1.1 / §3.1 / §5（⚠️ 职责对不上）|
| 9 | `service/task/src/main/java/com/rag/task/controller/TaskController.java` | §1.1|
| 10 | `service/task/src/main/java/com/rag/task/dto/task/GetTaskReq.java` | §1.1|
| 11 | `service/task/src/main/java/com/rag/task/dto/task/GetTaskResp.java` | §1.1|
| 12 | `service/task/src/main/java/com/rag/task/dto/task/TaskInfo.java` | §1.1|
| 13 | `service/task/src/main/java/com/rag/task/service/TaskApiService.java` | §1.1|
| 14 | `service/task/src/main/java/com/rag/task/service/TaskApiSupport.java` | §1.1|
| 15 | `service/task/src/main/java/com/rag/task/wiring/NacosRegistration.java` | §1.2（服务发现注册位）|
| 16 | `service/task/src/main/java/com/rag/task/wiring/TaskSchemaGate.java` | §1.2 / §2|
| 17 | `service/task/src/main/java/com/rag/task/messaging/TaskKafkaTopics.java` | §1.2 / §4|
| 18 | `service/task/src/main/java/com/rag/task/messaging/TaskGoCompatibleJson.java` | §1.2|
| 19 | `service/task/src/main/java/com/rag/task/messaging/TaskArticleProgressEvent.java` | §1.2|
| 20 | `service/task/src/main/java/com/rag/task/messaging/TaskLogEvent.java` | §1.2|
| 21 | `service/task/src/main/java/com/rag/task/messaging/TaskCountStore.java` | §1.2|
| 22 | `service/task/src/main/java/com/rag/task/messaging/TaskRawLogRouter.java` | §1.2|
| 23 | `service/task/src/main/java/com/rag/task/messaging/TaskRawDiagnosticConsumer.java` | §1.2|
| 24 | `service/task/src/main/java/com/rag/task/messaging/TaskArticleCountConsumer.java` | §1.2|
| 25 | `service/task/src/main/java/com/rag/task/messaging/TaskUserCountConsumer.java` | §1.2|
| 26 | `service/task/src/main/java/com/rag/task/messaging/TaskArticleLikeProgressSink.java` | §1.2|
| 27 | `service/task/src/main/java/com/rag/task/messaging/TaskUserLikeProgressSink.java` | §1.2|
| 28 | `service/task/src/main/java/com/rag/task/config/TaskSinkProperties.java` | §1.2|
| 29 | `service/task/src/main/java/com/rag/task/domain/entity/TaskProgressEntity.java` | §1.2 / §2|
| 30 | `service/task/src/main/java/com/rag/task/domain/entity/UserLikeCountEntity.java` | §1.2 / §1.4 / §2|
| 31 | `service/task/src/main/java/com/rag/task/mapper/TaskProgressMapper.java` | §1.2|
| 32 | `service/task/src/main/java/com/rag/task/mapper/UserLikeCountMapper.java` | §1.2|
| 33 | `service/task/src/main/java/com/rag/task/service/RedisPipelines.java` | §1.2|
| 34 | `service/task/src/main/java/com/rag/task/service/TaskDefs.java` | §1.2 / §2 / §5|
| 35 | `service/task/src/main/java/com/rag/task/domain/service/TaskDomainService.java` | §1.2|
| 36 | `service/task/src/main/java/com/rag/task/service/TaskProgressWriter.java` | §1.2|
| 37 | `service/task/src/main/java/com/rag/task/service/TaskRedisKeys.java` | §1.2|
| 38 | `service/task/src/main/java/com/rag/task/messaging/TaskRewardPublisher.java` | §1.2|
| 39 | `service/task/src/main/java/com/rag/task/grpc/TaskGrpcService.java` | §1.2 |
| 40 | `service/task/src/main/java/com/rag/task/messaging/TaskTraceAttributes.java` | §1.2（**C 新增的独立类** · B 侧是包级静态类）|

> ✅ **C 列 40 个 `.java` 全部出现，覆盖率 40/40 = 100%**（task 域**零 Java 契约类**，故不在 C 列计数内 —— 见 §1.3）。
> 📌 **实测命令输出**：`find service/task -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u | wc -l` ⇒ **40**（2026-11-03 实测）。
> 📌 **C 列 39 → 40 的由来**：① `TaskApiProperties.java` **改名**为 `TaskRewardProperties.java`（**数量不变**）；② **新增** `messaging/TaskTraceAttributes.java`（B 侧 `mq/TaskTraceAttributes.java` 是**包级静态类**，C 侧提为独立 `public final class` ⇒ **可见性放宽、行为零变化**）。
>
> 📌 **原 §7.2「第三列不遗漏职责（26 个源文件）」整段已随第三列移除**（D16 · 判定基准改为 B 侧）——
> 该清单列举的全是第三列的文件，B 侧无对应物 ⇒ **无独立价值，整段删除**（不留悬空引用）。
> ⇒ **B 列的逐类落位**改由 `tools/check-mapping-coverage.py` 机器核对（`python3 tools/check-mapping-coverage.py task`）。

### 7.2 映射类型分布

> 📌 **统计口径**：只统计**带映射类型列的表格行**（§0 / §1.1 / §1.2 / §1.3 / §4），
> §2 / §3 / §6 / §7 是**维度对照表与约定表**，按契约不设映射类型列。
> 📌 **重算依据**：D16 后表仍为 4 列（B / C / 映射类型 / 备注），**行数不变**；
> 本次仅在 §1.2 **新增 3 行**（补齐 B 列遗漏的 `NacosRegistration` / `Jackson2ObjectMapperConfiguration` / `TaskTraceAttributes`）⇒
> §1.2 由 17 行增至 20 行（`1:1` +1、`1:N` +2），其余各节行数与判定**逐行复核未变**。

| 映射类型 | 行数 | 占比 | 分布（§0 / §1.1 / §1.2 / §1.3 / §4） |
|---|---|---|---|
| `1:1` | **35** | 62.5% | 5 / 7 / 12 / 2 / 9 |
| `1:N` | **16** | 28.6% | 2 / 5 / 8 / 0 / 1 |
| `N:1` | **2** | 3.6% | 2 / 0 / 0 / 0 / 0 |
| `C 新增` | **3** | 5.4% | 1 / 1 / 0 / 0 / 1 |
| **合计** | **56** | **100%** | 10 / 13 / 20 / 2 / 11 |

> 📌 占比四舍五入到 0.1%（四舍五入后合计 100.1%）。

> ⚠️ **`N:1` 只有 2 处**（两个 yaml 合成 1 份 `application.yml`）——
> **本域的合并发生在「模块 → 模块」层面，不发生在「文件 → 文件」层面**；
> B 侧的 **39** 个 `.java`（`task-api` 11 + `task-rpc` 28，2026-11-01 实测）被压成 C 的 **39** 个 `.java`（api/rpc 合并后 `task-api` 的 11 个与 `task-rpc` 的 28 个同域共存，**数量接近但结构完全不同**）：
> B 的 `rpc/{bootstrap,config,domain,mapper,mq,service}` 六层 + `api/{advice,client,config,controller,dto,service}` 六层
> 收敛为 C 的 **10 个子包**
> （`api.{advice,config,controller,dto,service}` + `rpc.{wiring,config,domain,mapper,service,messaging}`），
> 而 B 的 `mq/` 包 **11 个 Kafka 消费侧类**在 C 侧**已由 `messaging/` 包 12 类接住**（见下）。

### 7.3 ⛔ 两侧之间「职责对不上」清单（本表不判对错，只标出**落点缺口**）

| # | 缺口 | 涉及 | 说明 |
|---|---|---|---|
| **1** | ✅ **已关闭（2026-11-03）—— Kafka 消费侧整体缺失** | B：`task-rpc/.../mq/`（**11 个** `mq/*.java`） | B 侧有 **11 个** `mq/*.java` ⇒ ✅ **C 侧 `messaging/` 包 12 类全部落地**：`TaskUserLikeProgressSink` / `TaskArticleLikeProgressSink`（消费 + 1s 定时 flush）· `TaskUserCountConsumer` / `TaskArticleCountConsumer` / `TaskRawDiagnosticConsumer` · `TaskRawLogRouter` / `TaskCountStore` / `TaskLogEvent` / `TaskArticleProgressEvent` / `TaskGoCompatibleJson` / `TaskTraceAttributes` ⇒ **6 个 `@KafkaListener` 真进 `KafkaListenerEndpointRegistry`**（真证据 = `TaskKafkaListenerAssemblyTest`） |
| **2** | ✅ **已关闭（2026-11-03）—— `TaskCountStore` 的存储后端** | B：`task-rpc/.../mq/TaskCountStore.java` | ⚠️ B 侧已用 **Redis `INCR` + group 命名空间**（`redis.opsForValue().increment(keyOf(group, key))`）落地「按 group 分目录的 KV 表」等价物 ⇒ **选型已由 B 侧定案**；✅ **C 侧已按 B 侧落法实现**（`messaging/TaskCountStore.java`：`COUNTER_KEY_PREFIX="task:goka:count:"` ＋ `increment` ＋ `keyOf(group, key)`）⇒ 真证据 = `TaskRedisPipelineRealRedisTest`（真 Redis 验 group 命名空间隔离） |
| **3** | ✅ **已关闭（2026-11-03）—— `WebMvcConfig` 的 javadoc 与本域事实冲突** | C：`config/WebMvcConfig.java` | 原 javadoc 写「注册 JwtAuthInterceptor + public 路径白名单」，但 **B 侧 `task-api` 零 JWT 依赖、无 `interceptor/` 包** ⇒ ✅ **C 侧已改写为否定式声明**（零拦截器 ＋ 零 CORS）；真证据 = 真 Tomcat **GET → 405**（若装了拦截器则是 401） |
| **4** | ✅ **已关闭（2026-11-03）—— `TaskApiResponseAdvice` 的职责** | C：`advice/TaskApiResponseAdvice.java` | **B 侧 `task-api` 没有 ResponseAdvice** ⇒ 成功响应**无信封**；✅ **C 侧保留该类但 `beforeBodyWrite` 原样返回 body** ⇒ 与「B 侧没有这个类」**逐字节相同**；真证据 = `TaskApiWireContractRealTomcatTest`（成功体无信封） |
| **5** | ✅ **已关闭（2026-11-03）—— 持久层落地位缺口** | C：`db/migration/`、`application.yml` | ✅ **V1 + V2 迁移脚本逐字复制**（`diff -r` 零差异）＋ **`spring.flyway` 段已补**（`table: flyway_schema_history_task` ＋ `baseline-on-migrate: true` ＋ `baseline-version: 0`）＋ **`hikari` 段已补**（50/50/1800000 ＋ `TimeZone=Asia/Shanghai`）＋ **`id-type: INPUT` 已补**；真证据 = `TaskServiceApplicationTests`（Flyway 账本有 V1/V2 且 `success=true` ＋ 两张表存在） |
| **6** | ✅ **已关闭（2026-11-03）—— gRPC 服务端拦截器无 C 落点** | B：`common/spring/RpcServerInterceptorFactory` + **显式** `GrpcServerConfigurer.intercept(...)` | ✅ **已由共享层承担**：`common/autoconfigure/ProtectionAutoConfiguration.java:231-240` 的 `seaGrpcServerConfigurer` 真调 `server.intercept(...)` ⇒ **域内不写 `rpcInterceptorConfigurer`**；真证据 = **守门 A3 PASS**（`check-arch-invariants.py`：「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」） |
| **7** | ✅ **已关闭（2026-11-03）—— MQ 载荷契约在 C 侧只有一份** | B：`task-rpc/.../mq/TaskArticleProgressEvent.java` | C 侧 `service/like/src/main/java/com/rag/like/contract/TaskArticleProgressMsg.java` 有；**`task/` 侧现已补上** `service/task/src/main/java/com/rag/task/messaging/TaskArticleProgressEvent.java` ⇒ **跨域 MQ 载荷在 C 侧是两份**（task 域 + like 域）⇒ ⚠️ 两份的**字段名与 topic 必须逐字对齐**（L1 契约层） |
| **8** | 🟠 **`grpc-client-spring-boot-starter` 无使用方** | C：`service/task/pom.xml` | api/rpc 合并后本域**没有跨域 gRPC 下游** ⇒ 该依赖目前无调用点（**与 B 侧同源** —— B 侧 `task-api` 也有 `@GrpcClient` 但只连自己）⇒ **保留不动**，登记为「可裁剪的冗余依赖」 |
| **9** | ✅ **已关闭（2026-11-03）—— `TaskApiProperties` 的聚合边界** | C：`config/TaskRewardProperties.java` | [`docs/第二轮深挖-契约盘点.md` §4.3](../../第二轮深挖-契约盘点.md#43-落地的缺口) 要求「按配置块拆成多个 `*Properties`」；✅ **C 侧已拆成 3 个**：`TaskRewardProperties`（prefix `task.reward`）· `TaskKafkaTopics`（prefix `task.kafka`）· `TaskSinkProperties`（prefix `task.definitions`）⇒ 原 `TaskApiProperties`（prefix `task.api`）在合并后**零消费点（哑弹）** ⇒ **已改名**为 `TaskRewardProperties`（**键名逐字不变**） |

---

## 8. 一句话总结

**B 列 RAG** = 两个进程（`task-api` HTTP 18886 / `task-rpc` gRPC 9005）+ MyBatis-Plus + Flyway（独立账本 `flyway_schema_history_task`）+ 全 Spring Bean + `mq/` 包 **11 个类**承载 Kafka 侧 + **零鉴权 + 零业务码 + 零指标**；
**C 列 RAG-V1** = **单 app**（HTTP 8890 + gRPC 9006）+ **10 个子包** + **40 个已落地类**（**零空壳**；含 `messaging/` 包 **12 类** —— Kafka 消费侧 6 个 `@KafkaListener` 已装配）+ task 域**刻意无错误契约类**。
⇒ **文件名与职责逐条对应；行为以 B 侧源码为准。**
⇒ ✅ **C 侧原列出的 5 处缺口（2026-11-02 口径）已全部闭合（2026-11-03）**：`TaskCountStore` 的 Redis `INCR` 落法已实现 · 持久层迁移脚本 / `flyway` 段 / `hikari` 段 / `id-type` 已补 · `common/` 的 gRPC 拦截链由 `ProtectionAutoConfiguration` 承担 ⇒ **仅余 1 条**（`grpc-client-spring-boot-starter` 无使用方 · **与 B 侧同源**，登记不删）。
⇒ 📌 **对齐证据**：[`docs/DRIFT/task/verification-task-service-2026-11-03.md`](../../DRIFT/task/verification-task-service-2026-11-03.md)（迁移审核报告）＋ [`docs/DRIFT/task/vs-common.md`](../../DRIFT/task/vs-common.md)（跨层对账）。
