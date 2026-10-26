# Mapping_Table/like/like.md — Sea-RideTheWind_golang ↔ RAG ↔ RAG-V1 对照表（like 域）

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标架构）三棵树里 **like 域**的文件逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 域路径 | 角色 |
> |---|---|---|---|
> | **A** | `Sea-RideTheWind_golang` | `service/like/` | **真理之源**（冻结，只读） |
> | **B** | `RAG` | `service/like/` | Go 部署形态版（api/rpc 分离） |
> | **C** | `RAG-V1` | `service/like/` | 本仓目标形态（api/rpc 合并，D1） |
>
> **格式契约**：[`README.MD`](../README.MD) · **章节骨架**照 [`RAG/docs/MappingTable/users.md`](../../../../../java&golang/RAG/docs/MappingTable/users.md) · **A↔B 依据**：[`RAG/docs/FileTree/like/{api,rpc}.md`](../../../../../java&golang/RAG/docs/FileTree/like/rpc.md)

**三条读表纪律（不可违反）**

1. ⚠️ **B 列不是 Go 的替身** —— RAG 的 like 域 **api 侧 9 MISMATCH**（全仓最高）、**rpc 侧 4 MISMATCH + 2 OBS**（见 `RAG/docs/DRIFT/like/`）⇒ **一切行为判断以 A 列 Go 源码为准**，本表只用于定位文件。
2. ⚠️ **本表不下「已对齐 / 未对齐」结论** —— 只回答「文件名 / 职责的对应关系」。行为对齐状态看 `docs/DRIFT/<域>/<组件>.md`。
3. ⚠️ **C 列目前全是空壳** —— 每个 `.java` 只有 package + 空类型 + TODO javadoc ⇒ **C 列写的���「这个位置该放什么职责」，不是「已经有什么」**。

**路径书写约定**

| 列 | 基准目录 | 形态 |
|---|---|---|
| A | Go 仓库根 | `service/like/api/internal/handler/routes.go` |
| B | `RAG/service/like/` | `like-api/src/main/java/com/rag/like/like/api/controller/LikeController.java` |
| C | `RAG-V1/service/like/` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |

**规模基线（实测）**

| 列 | like 域主代码文件数 | 备注 |
|---|---|---|
| A | **51** 个非测试 `.go`（其中 2 个是 protoc 生成物 `rpc/pb/*.pb.go`） | 另有 4 个 `_test.go`（不在本表范围）+ 5 个非 `.go` 配置/脚本（3 个 `.sql` + 2 个 `.yaml`） |
| B | **81** 个 `.java`（不含测试） | `like-api` 35 + `like-common` 4 + `like-rpc` 42；`like-proto` 只有 `.proto`，0 个 `.java` |
| C | **67** 个 `.java`（不含测试） | `like-service` 一个模块：域内 `contract/` 包 7 + 其余 60 |

> ⚠️ **三列不是 1:1 数量关系** —— C 比 B 少 14 个文件，主要来自：① **api/rpc 合并**（D1）⇒ B 的 `like-api` + `like-rpc` 两套包收敛成一套；② **`like-common` → 域内 `contract/` 包**（错误契约 + Kafka payload；proto 集中到根级 `proto/`）；③ **共享能力上收 `common/` starter**（D3）⇒ B 的 `GoStyle*` 反序列化器 / `JsonbTypeHandler` / `MybatisPlusOrderingBridgeAutoConfiguration` 等本地副本消失；④ **生态优先**（D4）。
> ⇒ **映射类型里出现大量 `N:1` 与 `C 新增` 是正常的，不是缺陷。**

---

## 0. 顶层模块对照

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/like/api/`（进程 P1，HTTP 入口） | `like-api/` | `service/like/src/main/java/com/rag/like/`（`com.rag.like.{controller,advice,interceptor,dto,config,metrics,service}`） | 1:N | **D1 + 包压平**：api 与 rpc 合并为单 app，并按技术角色压平为单层包树（HTTP 边界包 `controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service`） |
| `service/like/rpc/`（进程 P2，gRPC 入口） | `like-rpc/` | `service/like/src/main/java/com/rag/like/`（`com.rag.like.{grpc,service,domain/entity,domain/service,messaging,mapper,config,client,wiring,metrics,exception}`） | 1:N | 同上；**`rpc.*` 包名随包压平取消**（服务端能力包 `grpc` / `service` / `domain` / `messaging` / `mapper` / `config` / `client` / `wiring` / `metrics` / `exception`） |
| `service/like/rpc/internal/mq/`（进程 P3，Kafka 消费 + 发件箱中继） | `like-rpc/src/main/java/com/rag/like/like/rpc/mq/` | `service/like/src/main/java/com/rag/like/domain/service + service/like/src/main/java/com/rag/like/messaging + service/like/src/main/java/com/rag/like/service` + `service/like/src/main/java/com/rag/like/wiring` | 1:N | 进程 P3 被拆散到 `service/`（业务）与 `wiring/`（装配）；**消费入口与发件箱中继无落点 ⇒ 见 §1.4 的 GAP-1 / GAP-2** |
| `service/like/common/`（`errmsg`，api/rpc/mq 三方共用） | `like-common/` | `service/like/src/main/java/com/rag/like/contract/` | 1:N | **改名**：库名体现「契约」；装入**错误契约 + 3 个 Kafka payload**（L1）；⚠️ **proto 已随 D7 集中到根级 `proto/` 模块**，不再在本包内 |
| `proto/like.proto`（Go 仓库根） | `like-proto/src/main/proto/like.proto` | `proto/src/main/proto/like.proto` | 1:1 | **L1 契约**：字段号 / message 名 / 枚举值 100% 一致；差异仅 `package pb→like` + `option` 块 + 缩进 |
| `service/like/rpc/cmd/fact-dispatch/`（进程 P4，独立可执行） | `like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeFactDispatchScheduler.java` | `service/like/src/main/java/com/rag/like/wiring/LikeFactDispatchScheduler.java` | 1:1 | ★ **独立进程 → 域内组件**；落点由 B 的 `mq/` 变为 C 的 `wiring/` |
| `service/like/rpc/cmd/fact-authority/`（进程 P5，独立可执行） | `like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeAuthorityEndpointConfig.java` | `service/like/src/main/java/com/rag/like/wiring/LikeAuthorityEndpointConfig.java` | 1:1 | ★ **独立进程 → 域内组件**；落点同上 |
| `service/like/rpc/likeservice/likeservice.go`（goctl 生成桩）<br>`service/like/rpc/pb/like.pb.go`<br>`service/like/rpc/pb/like_grpc.pb.go` | `like-proto/`（protoc 生成，0 手写 `.java`） | `proto/`（**根级 proto 模块**产出，pb 包名 `com.rag.proto.like`） | 1:1 | **纯生成物，不迁移**；两侧都由 `.proto` 生成，Java 侧不再需要手写 client 桩 |

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

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `like/api/`（`LikeApiApplication`） | `com.rag.like`（`LikeServiceApplication.java`） | 入口类合并（D1）|
| `like/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `LikeApiResponseAdvice`）|
| `like/api/client/`（`LikeRpcClient`） | **—（D2 域内直连 ⇒ 删除）** | 域内自我 gRPC 往返取消 |
| `like/api/config/` | `config/` | 与 rpc 侧合并 |
| `like/api/controller/` | `controller/` | 直接压平 |
| `like/api/dto/` | `dto/` | 直接压平（15 个同名类全落此）|
| `like/api/interceptor/` | `interceptor/` | 直接压平 |
| `like/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `like/api/service/` | `service/` | 与 rpc 侧合并 |
| `like/common/errmsg/` | `contract/` | 错误码 + 事件 DTO（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrFactory` / `KafkaLikeMsg` / `ArticleHotEvent` / `TaskArticleProgressMsg`）|
| `like/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `like/rpc/client/` | `client/` | 与 api 侧合并 |
| `like/rpc/config/` | `config/` + `messaging/` | Kafka topic 落到 `messaging/`（`LikeKafkaTopics`）|
| `like/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分（5 个全是 `*Entity`）|
| `like/rpc/exception/` | `exception/` | 直接压平 |
| `like/rpc/mapper/` | `mapper/` | 直接压平 |
| `like/rpc/metrics/` | `metrics/` | 合并 |
| `like/rpc/mq/`（5 类） | `wiring/`（2 类） | `LikeAuthorityEndpointConfig` + `LikeFactDispatchScheduler`；**`LikeUpdateKafkaListener` + `LikeHotEventOutboxRelay` 无落点**（GAP-1 / GAP-2） |
| `like/rpc/service/` | `service/` + `domain/service/` + `messaging/` | 领域服务另出 `domain/service/`；`LikeTaskProgressPublisher` 落 `messaging/` |
| `like/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `LikeServiceGrpcImpl` → `LikeGrpcService` |
| `like-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.like`）| **集中化**（D7）；proto `package` 由 `pb` 改为 `like`（**L1 签字偏离** `DEV-LIKE-PROTO-001`）|

## 1. 包结构对照

### 1.1 契约（域内 `contract/` 包 + 根级 `proto/` 模块）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/like/common/errmsg/errmsg.go`（`CodeError` 结构） | `like-common/src/main/java/com/rag/like/like/common/errmsg/CodeError.java` | `service/like/src/main/java/com/rag/like/contract/CodeError.java` | 1:1 | 根包由 `com.rag.like.like.common.errmsg` → `com.rag.like.contract`；`CodeError{Code,Msg}` 字段逐字保留 |
| `service/like/common/errmsg/errmsg.go`（`const` 块 30 个错误码） | `like-common/.../errmsg/LikeErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | **改名**；⚠️ 错误码空间**逐域隔离**，不可跨域统一（RAG-V1 AGENTS §1.1b） |
| `service/like/common/errmsg/errmsg.go`（`codeMsg` 表 + `GetErrMsg`） | `like-common/.../errmsg/LikeErrorMessages.java` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | **改名**；`GetErrMsg` 查不到时回退 `codeMsg[Error]`（"FAIL"），**不是空串** |
| `service/like/common/errmsg/errmsg.go`（`NewGrpcErr`） | `like-common/.../errmsg/LikeGrpcError.java` | `service/like/src/main/java/com/rag/like/contract/GrpcErrFactory.java` | 1:1 | **改名**；语义不变：`status.Error(codes.Code(code), msg)` —— 把 like 业务码塞进 gRPC status code 字段 |
| `service/like/rpc/internal/types/types.go`（`KafkaLikeMsg`） | `like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeEventContracts.java`（内嵌 record） | `service/like/src/main/java/com/rag/like/contract/KafkaLikeMsg.java` | 1:N | B 的 1 个文件装 3 个 payload ⇒ C 拆 3 个文件；**L1 外部契约**（灰度期与 Go like-mq 共消费同一 topic，字段名逐字相同） |
| `service/like/rpc/internal/types/types.go`（`ArticleHotEvent`） | 同上（`LikeEventContracts.java` 内嵌） | `service/like/src/main/java/com/rag/like/contract/ArticleHotEvent.java` | 1:N | ⚠️ 该消息的 `user_id` 在 Go 侧是 **`string`**（不是 int64），P2 用 `fmt.Sprintf("%d", …)` 构造 |
| `service/like/rpc/internal/types/types.go`（`TaskArticleProgressMsg`） | 同上（`LikeEventContracts.java` 内嵌） | `service/like/src/main/java/com/rag/like/contract/TaskArticleProgressMsg.java` | 1:N | `user_id` 亦为 `string` |
| `proto/like.proto` | `like-proto/src/main/proto/like.proto` | `proto/src/main/proto/like.proto` | 1:1 | **L1 一个字节都不改**；C 侧由 `protobuf-maven-plugin` 生成 `LikeServiceGrpc` + 9 个 message + `ActionType` 枚举 |

### 1.2 API 侧（HTTP 边界）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/like/api/likecenter.go`（进程装配：`rest.Server` + `httpx.SetOkHandler` / `SetErrorHandler` + `svc.NewServiceContext`） | `like-api/src/main/java/com/rag/like/like/api/LikeApiApplication.java` | `service/like/src/main/java/com/rag/like/LikeServiceApplication.java` | 1:N | **D1**：api 入口与 rpc 入口合并为 1 个 `@SpringBootApplication`；`DisableNativeRestTimeout` 由 Spring 侧无对应默认超时 ⇒ 语义自动满足 |
| `service/like/api/likecenter.go:41-47`（`SetOkHandler`：`code=200 / msg="OK" / data=v`） | `like-api/.../advice/GlobalResponseAdvice.java` | `service/like/src/main/java/com/rag/like/advice/LikeApiResponseAdvice.java` | 1:N | **改名 + 逐域隔离**；只包一层（`body instanceof ApiResponse` 放行） |
| `service/like/api/likecenter.go:48-61`（`SetErrorHandler`：两分支都返回 HTTP 200） | `like-api/.../advice/GlobalExceptionHandler.java` | `service/like/src/main/java/com/rag/like/advice/LikeApiExceptionHandler.java` | 1:N | **改名**；非 `CodeError` 异常 → 5001 兜底；HTTP 状态码恒 200 是 L1 |
| `service/like/api/internal/config/config.go`<br>`service/like/api/etc/likecenter.yaml` | `like-api/.../config/LikeApiProperties.java` | `service/like/src/main/java/com/rag/like/config/LikeApiProperties.java` | 1:1 | `@ConfigurationProperties`；⚠️ **C 的 `service/like/src/main/resources/application.yml` 当前没有 `like-api:` 段** ⇒ 配置键待补（见 §3） |
| `service/like/api/internal/handler/routes.go`（`rest.WithPrefix("/like/v1")` + `rest.WithJwt(...)` 的绑定） | `like-api/.../config/WebMvcConfig.java` | `service/like/src/main/java/com/rag/like/config/WebMvcConfig.java` | 1:1 | 注册 interceptor 到 `/like/v1/**`；**public 路径集为空集**（Go 6/6 全 JWT） |
| `service/like/api/internal/handler/routes.go`（6 条 POST 路由）<br>`service/like/api/internal/handler/like/getlikecounthandler.go`<br>`service/like/api/internal/handler/like/getlikestatehandler.go`<br>`service/like/api/internal/handler/like/gettargetlikerlisthandler.go`<br>`service/like/api/internal/handler/like/getuserlikelisthandler.go`<br>`service/like/api/internal/handler/like/getusertotallikehandler.go`<br>`service/like/api/internal/handler/like/likeactionhandler.go` | `like-api/.../controller/LikeController.java`（6 个 `@PostMapping`） | `service/like/src/main/java/com/rag/like/controller/LikeController.java` | 1:N | 6 个同质薄 handler（parse→logic→OkJson）合 1 个 Controller；**路径前缀 `/like/v1` 与 6 个 method 逐字保留（L1）** |
| `service/like/api/internal/logic/like/getlikecountlogic.go`<br>`service/like/api/internal/logic/like/getlikestatelogic.go`<br>`service/like/api/internal/logic/like/gettargetlikerlistlogic.go`<br>`service/like/api/internal/logic/like/getuserlikelistlogic.go`<br>`service/like/api/internal/logic/like/getusertotallikelogic.go`<br>`service/like/api/internal/logic/like/likeactionlogic.go` | `like-api/.../service/LikeCountService.java`<br>`.../LikeStateService.java`<br>`.../TargetLikerListService.java`<br>`.../UserLikeListService.java`<br>`.../UserTotalLikeService.java`<br>`.../LikeActionService.java` | `service/like/src/main/java/com/rag/like/service/LikeApiService.java` | N:1 | **用例内聚**（D5 判据 5 条全中）；C 侧 6 个用例合 1 个 Service |
| `service/like/api/internal/logic/like/*.go`（4 个 logic 共享的 uid 提取模板 + reject 指标） | `like-api/.../service/LikeApiSupport.java` | `service/like/src/main/java/com/rag/like/service/LikeApiSupport.java` | 1:1 | uid 从 `ctx.Value("userId").(json.Number)` 取；reject 指标的 `route` 标签 Go 侧**硬编码为 `/like/likeaction`**（4 处全同，含 3 个非该路径的端点）⇒ 照抄 |
| `service/like/api/internal/logic/like/getlikecountlogic.go` + `gettargetlikerlistlogic.go`（`target_ids` 的 null 元素语义） | `like-api/.../service/GoStyleTargetIds.java` | `service/like/src/main/java/com/rag/like/service/LikeApiSupport.java`（**无独立文件**） | 1:N | ⚠️ C 侧**没有同名文件**；该职责须并入 `LikeApiSupport`（protobuf `addAllTargetIds` 对 null 元素硬失败） |
| `service/like/api/internal/types/types.go`（6 组 Req/Resp + 3 个 item = 15 个 DTO） | `like-api/.../dto/GetLikeCountReq.java`<br>`.../GetLikeCountResp.java`<br>`.../GetLikeStateReq.java`<br>`.../GetLikeStateResp.java`<br>`.../GetTargetLikerListReq.java`<br>`.../GetTargetLikerListResp.java`<br>`.../GetUserLikeListReq.java`<br>`.../GetUserLikeListResp.java`<br>`.../GetUserTotalLikeReq.java`<br>`.../GetUserTotalLikeResp.java`<br>`.../LikeActionReq.java`<br>`.../LikeActionResp.java`<br>`.../LikeCountItem.java`<br>`.../LikeItem.java`<br>`.../LikeRecordItem.java` | `service/like/src/main/java/com/rag/like/dto/GetLikeCountReq.java`<br>`service/like/src/main/java/com/rag/like/dto/GetLikeCountResp.java`<br>`service/like/src/main/java/com/rag/like/dto/GetLikeStateReq.java`<br>`service/like/src/main/java/com/rag/like/dto/GetLikeStateResp.java`<br>`service/like/src/main/java/com/rag/like/dto/GetTargetLikerListReq.java`<br>`service/like/src/main/java/com/rag/like/dto/GetTargetLikerListResp.java`<br>`service/like/src/main/java/com/rag/like/dto/GetUserLikeListReq.java`<br>`service/like/src/main/java/com/rag/like/dto/GetUserLikeListResp.java`<br>`service/like/src/main/java/com/rag/like/dto/GetUserTotalLikeReq.java`<br>`service/like/src/main/java/com/rag/like/dto/GetUserTotalLikeResp.java`<br>`service/like/src/main/java/com/rag/like/dto/LikeActionReq.java`<br>`service/like/src/main/java/com/rag/like/dto/LikeActionResp.java`<br>`service/like/src/main/java/com/rag/like/dto/LikeCountItem.java`<br>`service/like/src/main/java/com/rag/like/dto/LikeItem.java`<br>`service/like/src/main/java/com/rag/like/dto/LikeRecordItem.java` | 1:N | D5：全部 immutable `record`；**JSON tag 逐字保留**（`target_type` / `target_id` / `action_type` / `author_id` / `weight` / `client_ip` / `promotion_id` / `counts` / `states` / `list` / `is_end` / `next_cursor` / `total_like_count`） |
| `service/like/api/internal/types/types.go`（`action_type,options=1\|2\|3\|4` + `author_id,string`） | `like-api/.../dto/LikeActionReq.java` + `like-api/.../config/LikeGoStyleCoercion.java` | `service/like/src/main/java/com/rag/like/dto/LikeActionReq.java`（强转规则上收 `common/src/main/java/com/rag/common/web/Coercion.java`） | 1:N | go-zero 的 `options` 校验在 `httpx.Parse` 阶段生效；`author_id` 在 JSON 里是**字符串形式的 int64** |
| `service/like/api/internal/types/types.go`（`float64` 字段强转语义） | `like-api/.../dto/GoStyleFloat64Deserializer.java` | `common/src/main/java/com/rag/common/web/Float64Deserializer.java` | 1:N | **D3 上收 common starter** ⇒ C 侧 like 域本地副本为 0 |
| `service/like/api/internal/types/types.go`（`[]string` 字段强转语义） | `like-api/.../dto/GoStyleRawStringListDeserializer.java` | `common/src/main/java/com/rag/common/web/RawStringListDeserializer.java` | 1:N | 同上 |
| `service/like/api/internal/metrics/metrics.go`（`like_api_reject_total{route,reason}`） | `like-api/.../metrics/LikeApiMetrics.java` | `service/like/src/main/java/com/rag/like/metrics/LikeApiMetrics.java` | 1:1 | 指标名 `like_api_reject_total` 与标签 `{route,reason}` 逐字照抄；⚠️ Go 侧 `InitMetrics()` 全仓零调用点 ⇒ 实际未注册，C 侧 Micrometer 会自动注册 ⇒ **观测面差异，需在 §3 声明** |
| `service/like/api/internal/svc/servicecontext.go`（持有 Config + 构造 `likeservice` client） | `like-api/.../config/LikeApiProperties.java` + `like-api/.../client/LikeRpcClient.java` | `service/like/src/main/java/com/rag/like/config/LikeApiProperties.java`（**`service/like/src/main/java/com/rag/like/{advice,config,controller,dto,interceptor,metrics,service}/client/` 因 D2 清空**） | 1:N | ServiceContext 被 Spring DI 吸收；B 还要 `LikeRpcClient` 走自我 gRPC，C 因**域内直连**（D2）不再需要 |
| `service/like/api/internal/handler/routes.go`（`rest.WithJwt` ⇒ go-zero 内置 JWT 中间件，写 ctx `userId`） | `like-api/.../interceptor/LikeJwtAuthInterceptor.java` | `service/like/src/main/java/com/rag/like/interceptor/LikeJwtAuthInterceptor.java` | 1:1 | ⚠️ Go 侧 like-api **没有** `CheckBlacklistMiddleware` ⇒ C 侧若沿用 blacklist 逻辑 = **新增行为** |

### 1.3 RPC 侧 —— 领域层

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/like/rpc/like.go`（P1 入口：gRPC server + 关原生超时 + unary interceptor） | `like-rpc/src/main/java/com/rag/like/like/rpc/LikeRpcApplication.java` | `service/like/src/main/java/com/rag/like/LikeServiceApplication.java` | 1:N | **D1**：与 api 入口合并；⚠️ `DisableNativeRpcTimeout` 只在 `RpcServerInterceptorFactory` **真的被注册**时才成立（C 侧由 common starter 的 auto-config 承担） |
| `service/like/rpc/etc/like.yaml`<br>`service/like/rpc/internal/config/config.go` | `like-rpc/.../config/LikeRpcProperties.java` | `service/like/src/main/java/com/rag/like/config/LikeRpcProperties.java` | 1:1 | ⚠️ **C 的 `application.yml` 当前没有 `like:` 段** ⇒ `cache-ttl` / `list-ttl` / kafka topic / `fact.dispatch-enabled` / `authority.*` 等键待补（见 §3） |
| `service/like/rpc/internal/server/likeserviceserver.go`（6 个 RPC → logic 的 thin wrapper） | `like-rpc/.../service/impl/LikeServiceGrpcImpl.java` | `service/like/src/main/java/com/rag/like/grpc/LikeGrpcService.java` | 1:1 | `@GrpcService`；6 个方法签名与 proto 逐条一致 |
| `service/like/rpc/internal/logic/getlikecountlogic.go`<br>`service/like/rpc/internal/logic/getlikestatelogic.go`<br>`service/like/rpc/internal/logic/gettargetlikerlistlogic.go`<br>`service/like/rpc/internal/logic/getuserlikelistlogic.go`<br>`service/like/rpc/internal/logic/getusertotallikelogic.go`<br>`service/like/rpc/internal/logic/likeactionlogic.go` | `like-rpc/.../service/LikeDomainService.java`（6 方法合 1） | `service/like/src/main/java/com/rag/like/domain/service/LikeDomainService.java` | N:1 | 6 个 RPC 用例合 1；HTTP 与 gRPC **共享同一份业务逻辑**（D2 的直接结果） |
| `service/like/rpc/internal/logic/likeactionlogic.go`（68 行 Lua + 事务边界） | `like-rpc/.../service/LikeActionScript.java` | `service/like/src/main/java/com/rag/like/service/LikeActionScript.java` | 1:1 | **事务边界独立成类**（§5.4 修正：事务边界是独立 L3 关注点，不能合并进用例类） |
| `service/like/rpc/internal/logic/likeactionlogic.go`（自写雪花游标 `(unixMilli<<22)\|(nodeId<<12)\|seq`） | `like-rpc/.../service/LikeCursorIdGenerator.java` | `service/like/src/main/java/com/rag/like/service/LikeCursorIdGenerator.java` | 1:1 | ⚠️ **禁止复用 `common/src/main/java/com/rag/common/snowflake/Snowflake.java`**（减过 epoch，`score>>22` 还原出的时间戳差 15 年） |
| `service/like/rpc/internal/logic/getlikecountlogic.go` + `getlikestatelogic.go` + `getuserlikelistlogic.go` + `gettargetlikerlistlogic.go` + `getusertotallikelogic.go`（Redis key 模式） | `like-rpc/.../service/LikeRedisKeys.java` | `service/like/src/main/java/com/rag/like/service/LikeRedisKeys.java` | 1:1 | 5 类 key：`like_count:{type}` / `like_state:{type}:{id}` / `user_like_list:{uid}:{type}` / `target_liker_list:{type}:{id}` / `user_total_like:{uid}` |
| `service/like/rpc/internal/logic/likeactionlogic.go`（Kafka push key 构造） | `like-rpc/.../service/LikeKafkaPushKey.java` | `service/like/src/main/java/com/rag/like/service/LikeKafkaPushKey.java` | 1:1 | — |
| `service/like/rpc/internal/logic/likeactionlogic.go`（推 `TaskArticleProgressMsg` 给 task 域） | `like-rpc/.../service/LikeTaskProgressPublisher.java` | `service/like/src/main/java/com/rag/like/messaging/LikeTaskProgressPublisher.java` | 1:1 | 跨域事件，走 Kafka `task.logs.filtered.Article` |
| `service/like/rpc/internal/svc/servicecontext.go`（Redis + GORM + 3 个 kq Pusher + LikeModel + MessageRpc） | `like-rpc/.../client/MessageRpcClient.java` + `.../config/LikeKafkaTopics.java` + `.../mapper/*` + `.../service/LikeKafkaPushKey.java` | `service/like/src/main/java/com/rag/like/client/MessageRpcClient.java` + `service/like/src/main/java/com/rag/like/messaging/LikeKafkaTopics.java` + `service/like/src/main/java/com/rag/like/mapper/*` + `service/like/src/main/java/com/rag/like/service/LikeKafkaPushKey.java` | 1:N | ServiceContext 被 Spring DI 吸收；⚠️ Go 侧 `KafkaPusher`（`like_update_topic`）这份配置**从未被读取** ⇒ 不实现 |
| `service/like/rpc/internal/metrics/metrics.go`（`like-rpc_action_total` + `like_rpc_query_cache_total`） | `like-rpc/.../metrics/LikeRpcMetrics.java` | `service/like/src/main/java/com/rag/like/metrics/LikeRpcMetrics.java` | 1:1 | ⚠️ **`like-rpc_action_total` 用了连字符**（Go `Subsystem: "like-rpc"`，全仓唯一）⇒ Prometheus 正则不允许 ⇒ **该指标不会出现在 `/metrics`** ⇒ **照抄，不修正** |
| `service/like/rpc/internal/model/data_model.go`（`LikeRecord` / `LikeConsumeInbox` / `LikeOutboxEvent`） | `like-rpc/.../domain/LikeRecordEntity.java` + `.../LikeConsumeInboxEntity.java` + `.../LikeOutboxEventEntity.java` | `service/like/src/main/java/com/rag/like/domain/entity/LikeRecordEntity.java` + `service/like/src/main/java/com/rag/like/domain/entity/LikeConsumeInboxEntity.java` + `service/like/src/main/java/com/rag/like/domain/entity/LikeOutboxEventEntity.java` | 1:1 | 表名逐字：`like_record` / `like_consume_inbox` / `like_outbox_event`；索引 `uk_user_target(UserID,TargetType,TargetID)` / `idx_user_list` / `idx_target_list` / `idx_author` / `uk_event_key` / `idx_topic_consumer` |
| `service/like/rpc/internal/model/domain_fact.go`（`LikeDomainFactOutbox` + `LikeFactStream` + `appendLikeFact`） | `like-rpc/.../domain/LikeDomainFactOutboxEntity.java` + `.../LikeFactStreamEntity.java` + `.../service/LikeDomainFactService.java` | `service/like/src/main/java/com/rag/like/domain/entity/LikeDomainFactOutboxEntity.java` + `service/like/src/main/java/com/rag/like/domain/entity/LikeFactStreamEntity.java` + `service/like/src/main/java/com/rag/like/domain/service/LikeDomainFactService.java` | 1:N | 表名 `like_domain_fact_outbox` / `like_fact_stream`；`appendLikeFact` 负责版本分配 + 双信封 |
| `service/like/rpc/internal/model/db.go`（`InitDB` + `AutoMigrate` 5 实体 + `log.Fatalln`） | `like-rpc/.../bootstrap/LikeSchemaGate.java` + `.../mapper/LikeRecordMapper.java` + `.../mapper/LikeConsumeInboxMapper.java` + `.../mapper/LikeDomainFactOutboxMapper.java` + `.../mapper/LikeFactStreamMapper.java` + `.../mapper/LikeOutboxEventMapper.java` | `service/like/src/main/java/com/rag/like/wiring/LikeSchemaGate.java` + `service/like/src/main/java/com/rag/like/mapper/LikeRecordMapper.java` + `.../LikeConsumeInboxMapper.java` + `.../LikeDomainFactOutboxMapper.java` + `.../LikeFactStreamMapper.java` + `.../LikeOutboxEventMapper.java` | 1:N | Go 是「表与进程同生共死」；Java 侧改用迁移脚本 ⇒ 引入 Go 没有的新失败模式 ⇒ B 用启动闸兜住；C 的迁移机制**待定案**（见 §2） |
| `service/like/rpc/internal/model/like.go`（`LikeRecordModel` 8 方法 + `ProcessLikeMessageBatch` + `applyLikeMessage`） | `like-rpc/.../mapper/LikeRecordMapper.java` + `.../service/LikeDomainService.java` + `.../service/LikeMessageIngestService.java` | `service/like/src/main/java/com/rag/like/mapper/LikeRecordMapper.java` + `service/like/src/main/java/com/rag/like/domain/service/LikeDomainService.java` + `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java` | 1:N | `ProcessLikeMessageBatch` 的「inbox + record + fact **单事务提交**」是 L3 语义，必须整段保留 |
| `service/like/rpc/internal/model/like_consume_inbox.go`（`FindByMsgID` / `CreateTx` / `MarkDoneTx`） | `like-rpc/.../domain/LikeConsumeInboxEntity.java` + `.../mapper/LikeConsumeInboxMapper.java` | `service/like/src/main/java/com/rag/like/domain/entity/LikeConsumeInboxEntity.java` + `service/like/src/main/java/com/rag/like/mapper/LikeConsumeInboxMapper.java` | 1:N | `MsgId` 是主键；`PayloadHash` 是消息语义指纹 |
| `service/like/rpc/internal/model/like_outbox.go`（`FetchPending(retry<3)` / `MarkSent` / `MarkFailed`） | `like-rpc/.../domain/LikeOutboxEventEntity.java` + `.../mapper/LikeOutboxEventMapper.java` + `.../mq/LikeHotEventOutboxRelay.java` | `service/like/src/main/java/com/rag/like/domain/entity/LikeOutboxEventEntity.java` + `service/like/src/main/java/com/rag/like/mapper/LikeOutboxEventMapper.java` + **—（C 无对应文件）** | 1:N | ⚠️ **GAP-2**：C 侧缺 `like_outbox_event` → `article-hot-events` 的发件箱中继 |
| `service/like/rpc/internal/model/like_message_hash.go`（7 字段 canonical JSON → sha256 hex） | `like-rpc/.../service/LikeMessageDigest.java` + `.../service/GoCompatibleJson.java` | `service/like/src/main/java/com/rag/like/service/LikeMessageDigest.java` + `service/like/src/main/java/com/rag/like/service/GoCompatibleJson.java` | 1:N | ⚠️ canonical JSON 必须是 **RFC 8785 JCS**；Jackson 的 `SORT_PROPERTIES_ALPHABETICALLY` **不等价** ⇒ 指纹不一致 ⇒ 幂等失效（RAG-V1 AGENTS G-2） |
| `service/like/common/errmsg/errmsg.go`（`NewErrCode` / `NewErrCodeMsg`） | `like-rpc/.../exception/LikeExceptions.java` | `service/like/src/main/java/com/rag/like/exception/LikeExceptions.java` | 1:1 | 业务异常工厂；错误码空间仍取自 `like/contract/ErrCodes` |
| `service/like/rpc/like.go`（`zrpc.MustNewServer` → etcd 注册 `like.rpc`） | `like-rpc/.../bootstrap/NacosRegistration.java` | `service/like/src/main/java/com/rag/like/wiring/NacosRegistration.java` | 1:1 | Go = etcd；Java = Nacos ⇒ **注册面是表达层差异，不是行为差异** |

### 1.4 RPC 侧 —— 事实流水线与 `cmd/fact-*` 合并落点

> ⚠️ **本节是 like 域最容易漏的一段** —— Go 侧 `service/like/` 有 **5 个 `main`**（api / rpc / mq / fact-dispatch / fact-authority），RAG 把后两个合并成域内组件（`mq/` 包），RAG-V1 **继续合并**但把落点从 `mq/` 挪到 `wiring`。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/like/rpc/cmd/fact-dispatch/main.go`（P4：env 校验 + `DCEventSender` + `RunDispatcher(ctx, model, sender, logger, once)`，**零 gRPC 注册**） | `like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeFactDispatchScheduler.java` | `service/like/src/main/java/com/rag/like/wiring/LikeFactDispatchScheduler.java` | 1:1 | ★ **独立进程 → 域内组件**（D1 不设例外，依据 `RAG/docs/FileTree/like/rpc.md:84-97`）。⚠️ **落点变了**：B 在 `mq/` 包，C 在 `wiring/` 包 |
| `service/like/rpc/cmd/fact-authority/main.go`（P5：env 校验 + `NewAuthorityHandler` + `net.Listen`，**零 gRPC 注册**，只有 1 个 Servlet） | `like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeAuthorityEndpointConfig.java` | `service/like/src/main/java/com/rag/like/wiring/LikeAuthorityEndpointConfig.java` | 1:1 | ★ **独立进程 → 域内组件**；落点同上。⚠️ Go 侧是**独立 HTTP 端口** ⇒ 合并后端口归属是待拍板项（RAG-V1 决策记录 **P6-b**） |
| `service/like/rpc/cmd/fact-dispatch/main.go` + `service/like/rpc/cmd/fact-authority/main.go`（`ProcessMetadata{Service:"rtw-like-fact-dispatch"/"rtw-like-fact-authority"}` + 进程级 logger 身份） | `like-rpc/src/main/java/com/rag/like/like/rpc/bootstrap/LikeFactProcessLogging.java` | `service/like/src/main/java/com/rag/like/wiring/LikeFactProcessLogging.java` | 1:1 | Go 侧 like 域共 **4 个进程级 logger 身份**（`like.rpc` / `like-mq` / `rtw-like-fact-dispatch` / `rtw-like-fact-authority`；api 侧另有 `likecenter`）⇒ 合并后必须保住 4 份独立 logger 名，**不得用 SLF4J 全局 MDC**（会跨线程插值） |
| `service/like/rpc/internal/model/fact_delivery.go`（`validLikeDelivery`：载荷规范化哈希比对 + 前序屏障） | `like-rpc/.../service/LikeDeliveryValidator.java` | `service/like/src/main/java/com/rag/like/service/LikeDeliveryValidator.java` | 1:1 | payload 列必须**以原始字符串存取**才能做字节级比对 |
| `service/like/rpc/internal/model/fact_delivery.go`（`DispatchFactOnce`：`FOR UPDATE SKIP LOCKED` + `NOT EXISTS predecessor`） | `like-rpc/.../service/LikeFactDispatchService.java` | `service/like/src/main/java/com/rag/like/service/LikeFactDispatchService.java` | 1:1 | 并发语义（多副本安全）逐条保留 |
| `service/like/rpc/internal/model/fact_authority.go`（`AuthoritativeFact` 链路重放校验 + `validLikeTransition`） | `like-rpc/.../service/LikeAuthorityLookupService.java` | `service/like/src/main/java/com/rag/like/service/LikeAuthorityLookupService.java` | 1:1 | — |
| `service/like/rpc/internal/mq/mq.go`（kq consumer 注册 + 1s `OutboxRelayService` ticker，batch=100） | `like-rpc/.../mq/LikeUpdateKafkaListener.java` + `.../mq/LikeHotEventOutboxRelay.java` | **—（C 无对应文件）** | 1:N | ⚠️ **GAP-1**：C 侧**没有 Kafka 消费入口类**；`service/like/pom.xml` 已引 `spring-boot-kafka` ⇒ 能力在、落点无。⚠️ **GAP-2**：C 侧**没有 `like_outbox_event` 发件箱中继** |
| `service/like/rpc/internal/mq/internal/mqs/likemq.go`（`Consume`：解析 → 校验 → 建 outbox → **单事务提交 inbox + record + fact**） | `like-rpc/.../mq/LikeUpdateKafkaListener.java`（入口层）+ `.../service/LikeMessageIngestService.java`（业务层） | `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java`（**业务层**） | 1:N | ⚠️ C 侧承载了**业务部分**，但「消费入口 / ack 契约 / 失败即丢弃不重投」这层**无落点** ⇒ 须在实现阶段补（Go 侧 `ForceCommit=true` ⇒ 失败也提交位点） |
| `service/like/rpc/internal/mq/internal/mqs/like_outbox_sender.go`（`SendPending`：逐条推送 `article-hot-events`，单条失败不中断批次） | `like-rpc/.../mq/LikeHotEventOutboxRelay.java` | **—（C 无对应文件）** | 1:1 | ⚠️ **GAP-2** |
| `service/like/rpc/internal/mq/internal/config/config.go`<br>`service/like/rpc/internal/mq/etc/mq.yaml` | `like-rpc/.../config/LikeKafkaTopics.java` + `.../config/LikeRpcProperties.java` | `service/like/src/main/java/com/rag/like/messaging/LikeKafkaTopics.java` + `service/like/src/main/java/com/rag/like/config/LikeRpcProperties.java` | 1:N | group `like-update-group` / offset `first` / consumers `8` / topic `like_promotion_topic`；⚠️ **生产与消费同一个 topic 是 Go 的现状，不是笔误** |
| `service/like/rpc/internal/mq/internal/metrics/metrics.go`（`like_mq_consume_total{action,result}`） | `like-rpc/.../metrics/LikeMqMetrics.java` | `service/like/src/main/java/com/rag/like/metrics/LikeMqMetrics.java` | 1:1 | 指标名与标签逐字照抄 |
| `service/like/rpc/internal/mq/internal/svc/servicecontext.go`（GORM + 3 个 model + HotEventPusher） | `like-rpc/.../mapper/*` + `.../mq/LikeHotEventOutboxRelay.java` + `.../service/LikeMessageIngestService.java` | `service/like/src/main/java/com/rag/like/mapper/*` + `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java` | 1:N | ⚠️ HotEventPusher 侧对应 GAP-2 |

### 1.5 包结构决策锁

**合并 api/rpc 为单 app（D1）9 处同时改**：
1. 删 `like-api` / `like-rpc` 两个模块，新增 `like-service` 一个模块（`pom.xml` + `application.yml`）；
2. `com.rag.like.api.*` / `com.rag.like.rpc.*` 两层包 → **取消二分**，压平为单层技术角色包树（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `wiring` / `exception`）；
3. 10 个 gRPC 实现类改名（`LikeServiceGrpcImpl` → `LikeGrpcService` 等）⇒ 落点从 `rpc/service/impl/` 上收到 `grpc/`；
4. `LikeApiApplication` + `LikeRpcApplication` 合 1 个 `LikeServiceApplication`；
5. `like-api/src/main/resources/application.yml` + `like-rpc/src/main/resources/application.yml` 合 1 份（两套 prefix `like-api.*` / `like.*` 共存）；
6. `like-api` 的 `client/LikeRpcClient` 删除（域内直连，D2），只保留跨域的 `client/MessageRpcClient.java`；
7. `like-proto` 与 `like-common` 合为 `like-contract`（proto + 错误契约 + 3 个 Kafka payload）；
8. `like-common` 的 4 个类改包名并改名（`LikeErrorCode→ErrCodes` / `LikeErrorMessages→ErrMsg` / `LikeGrpcError→GrpcErrFactory`）；
9. 端口：Go 侧 api 18887 + rpc 8082 ⇒ C 侧 `LIKE_HTTP_PORT` + `LIKE_GRPC_PORT` 两端口并存于同一进程。

**为什么换**：Spring Cloud 的部署单元是「可部署服务」，不是「api 层 / rpc 层」；go-zero 的 api/rpc 分离源于 `.api` 与 `.proto` 两条 codegen 路径，是**语言产物**不是架构原则。

**`cmd/fact-*` 独立进程 → 域内组件 6 处同时改**：
1. `rpc/cmd/fact-dispatch/main.go` → `wiring/LikeFactDispatchScheduler.java`（**落点由 B 的 `mq/` 改为 `wiring`**）；
2. `rpc/cmd/fact-authority/main.go` → `wiring/LikeAuthorityEndpointConfig.java`（同上）；
3. 两个 main 的 `ProcessMetadata` + logger 身份 → `wiring/LikeFactProcessLogging.java`；
4. `RunDispatcher` / `NewAuthorityHandler` 本身**不重写** ⇒ 复用 `common/src/main/java/com/rag/common/communityfact/{Dispatcher,AuthorityHandler}`；
5. `-once` flag → 配置项 `like.fact.dispatch.once`（**P6-a 待拍板**）；
6. 独立 HTTP 端口 → 并入主 app 端口（**P6-b 待拍板**）。

**为什么换**：Go 侧两个 cmd **零 gRPC 注册** ⇒ 没有强类型契约需要独立进程承载；`Dispatcher` / `AuthorityHandler` 本就是设计给宿主进程用的纯库。Go 注释 *"keeps retry cadence outside the business RPC process"* 是**架构意图而非技术约束**。

**api 侧 6 个 logic → 1 个 `LikeApiService`（用例内聚）3 处同时改**：
1. 删 6 个 `*Service.java`，新建 1 个 `service/LikeApiService.java`；
2. `controller/LikeController.java` 的 6 个调用点改为调同一个 Service 的 6 个方法；
3. 共享的 uid 提取 / reject 指标 / gRPC 错误映射下沉到 `service/LikeApiSupport.java`。

**为什么换**：5 条判据全中（同一 `@RequestMapping` 聚合 / 一致变化原因 / 无独立生命周期 / 无独立契约 / 合并后职责不模糊）；Go 侧 6 个 logic 文件是 goctl 生成模板的产物，不是职责边界。

---

## 2. 持久层差异

| 维度 | A Go（GORM） | B RAG（MyBatis-Plus） | C RAG-V1（MyBatis-Plus） | 备注 |
|---|---|---|---|---|
| ORM | `gorm.io/gorm` + `gorm:"..."` struct tag | `@TableName` + `@TableId(type=IdType.ASSIGN_ID)` + `@TableField` | 同 B | ⚠️ GORM tag → MP 注解**不等价**，逐项对照见 `RAG-V1/docs/GORM-MP-语义对照.md` |
| 实体定义 | `internal/model/data_model.go`（3 实体）+ `domain_fact.go`（2 实体） | `domain/` 5 个 `*Entity.java` | `service/like/src/main/java/com/rag/like/domain/entity` 5 个 `*Entity.java` | 表名 5 张：`like_record` / `like_consume_inbox` / `like_outbox_event` / `like_domain_fact_outbox` / `like_fact_stream`（**like 域独占**） |
| 数据访问 | `LikeRecordModel` 接口 8 方法 + GORM 链式条件 | `Mapper extends BaseMapper<E>` + `LambdaQueryWrapper` / XML | 同 B | ⚠️ 查询条件必须逐条保留；**写路径形态**（`updateById(entity)` vs `update(null, wrapper)`）决定修法 |
| `jsonb` 列 | `like_domain_fact_outbox.payload` / `delivery_envelope`（002 迁移） | `mapper/JsonbTypeHandler.java`（**域内本地副本**） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（**D3 上收**） | ⚠️ 必须用 `String` 原样存取（字节级哈希比对）；不能用 MP 自带 `JacksonTypeHandler`（它按目标 Java 类型推断列类型） |
| Schema 演进 | `db.AutoMigrate(&LikeRecord{}, …)`（`db.go:15`），失败 `log.Fatalln` | **Flyway** `V1__create_like_record.sql` … `V6__align_like_domain_fact_outbox_with_go.sql` + 独立账本表 `flyway_schema_history_like` + `wiring/LikeSchemaGate.java` 启动闸 | `service/like/src/main/resources/db/migration/` **仅 `.gitkeep`（空）** | ⚠️ **C 的迁移机制尚未定案**：Flyway 还是幂等重放（`SchemaInitializer`）？迁移脚本尚未落地 ⇒ **待拍板** |
| 迁移脚本 | `internal/model/migrations/001_like_domain_fact_outbox.sql` / `002_like_dc_wire.sql` / `003_like_fact_delivery.sql`（3 个，含 `NOT VALID CHECK` + 不可变触发器） | `resources/db/migration/V1..V6`（6 个） | **空** | ⚠️ C 侧需补：5 张表的 DDL + 002/003 的 `NOT VALID CHECK` + 不可变触发器 |
| DDL 脚本写法约束 | — | — | — | ⚠️ RAG-V1 AGENTS R6：`db/*.sql` **只能单条语句**（`ScriptUtils` 按 `;` 切分，**不支持 `$$`**）；`timestamptz` 转换须用 `SET TIME ZONE 'UTC'; … RESET TIME ZONE;` 包裹 |
| 事务边界 | `ProcessLikeMessageBatch`：**inbox + record + fact 单事务提交**（`likemq.go`） | `service/LikeMessageIngestService` | `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java` + `service/like/src/main/java/com/rag/like/service/LikeActionScript.java` | ⚠️ 事务边界是独立 L3 关注点 ⇒ **独立成类**，不并进用例类 |
| 行锁 / 幂等 | `FOR UPDATE SKIP LOCKED` + `NOT EXISTS predecessor`（`fact_delivery.go:63-69`）；`uk_user_target`；`uk_event_key`；`uk_like_fact_stream` | 同语义（MP + 手写 SQL） | 待实现 | ⚠️ 幂等键 `last_operation_id` 单调性判定依赖 Kafka 消息 `msg_id`（十进制雪花字符串） |
| 游标编码 | `(unixMilli << 22) \| (nodeId << 12) \| seq`，读侧 `score >> 22` 取**裸 Unix 毫秒** | `service/LikeCursorIdGenerator.java`（域内自实现） | `service/like/src/main/java/com/rag/like/service/LikeCursorIdGenerator.java` | ⚠️ **禁止复用 `common/src/main/java/com/rag/common/snowflake/Snowflake.java`**（减过 epoch，还原差 15 年） |
| Redis（无 ORM 对应） | `go-zero/core/stores/redis`；`ZrevrangebyscoreWithScoresAndLimit` | Spring Data Redis（Lettuce）+ `RedisCallback` 直发 | 同 B | ⚠️ `ZrevrangebyscoreWithScoresAndLimit`（带 min/max/limit）**不是标准 `ZSetOperations` 方法** |
| 连接池 | `MaxIdle=10 / MaxOpen=100`（`servicecontext.go:34-35`） | `hikari.maximum-pool-size: 100 / minimum-idle: 10` | `service/like/src/main/resources/application.yml` **当前未配 hikari** | ⚠️ 合并后 P3 的 dispatch 事务**持有行锁跨网络 IO**，与 gRPC 请求共池 ⇒ 故障域耦合 |

### 2.1 持久层决策锁

**持久层 GORM → MyBatis-Plus 6 处同时改**：
1. `pom.xml` 加 `mybatis-plus-spring-boot3-starter` + `postgresql`；
2. 5 个 Go struct → 5 个 `@TableName` Entity（表名逐字保留）；
3. `LikeRecordModel` 接口 8 方法 → 5 个 `Mapper extends BaseMapper` + XML/注解 SQL；
4. `jsonb` 列挂 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`；
5. `db.AutoMigrate` → 显式迁移脚本 + 启动闸（`wiring/LikeSchemaGate.java`）；
6. 游标 ID 生成器**域内自实现**，不接 `common/Snowflake`。

**为什么换**：GORM tag 语义与 MP 注解不等价（软删除 / 零值 / 批量更新差异），必须逐项对照而不是照抄注解；显式迁移可管 schema 历史。

**Schema 演进机制定案（Flyway vs 幂等重放）待拍板**（影响 5 处）：
1. `service/like/pom.xml` 是否加 `spring-boot-flyway` + `flyway-database-postgresql`；
2. `spring.flyway.table` 是否用独立账本表名（`flyway_schema_history_like`，避免与 `article` 的默认表撞号）；
3. `resources/db/migration/` 补 5 张表的 DDL（拆成单条语句，禁用 `$$`）；
4. `wiring/LikeSchemaGate.java` 的检查口径（Flyway 模式下是「账本 + 表存在」，幂等重放模式下是「表存在」）；
5. `002`/`003` 迁移里的 `NOT VALID CHECK` 与不可变触发器如何落到目标方言。

**为什么换**：Go 侧 `AutoMigrate` 无状态、5 张表互不冲突；换成有状态账本后，同库多模块会撞号（`flyway_schema_history` 是 schema 级账本，一个 schema 只能有一本）。⇒ 这是**必须拍板**的一项，不是实现细节。

---

## 3. HTTP / DI 差异

| 维度 | A Go（go-zero） | B RAG（Spring，api/rpc 分离） | C RAG-V1（Spring，合并） | 备注 |
|---|---|---|---|---|
| 路由注册 | `rest.AddRoutes` + `rest.WithPrefix("/like/v1")` | `@RequestMapping("/like/v1")` + 6 个 `@PostMapping` | 同 B | **L1**：前缀与 6 个路径逐字保留 |
| 鉴权绑定 | `rest.WithJwt(secret)`（6/6 全覆盖） | `WebMvcConfig.addInterceptors` → `/like/v1/**` | 同 B | **public 路径集为空集**；Go 侧 like-api **无** blacklist 中间件 |
| Handler / Controller | 6 个 `*handler.go` 薄壳（parse → logic → OkJson） | 1 个 `LikeController`（6 个 `@PostMapping`） | 同 B | 6 handler 逐字同质 ⇒ 合 1 |
| 中间层 | api 6 logic → gRPC → rpc 6 logic | api 6 `*Service` → `LikeRpcClient`（自我 gRPC）→ rpc `LikeDomainService` | api `LikeApiService` → **直连** `service/like/src/main/java/com/rag/like/domain/service/LikeDomainService.java` | **D2**：同进程自我 gRPC 是无意义的序列化开销 |
| 错误表达（HTTP） | `httpx.ErrorCtx` / `httpx.OkJsonCtx`；HTTP 状态码**恒 200**，业务失败只体现在 body 的 `code` | `GlobalResponseAdvice` + `GlobalExceptionHandler`（`BusinessException(code,msg)`） | `LikeApiResponseAdvice` + `LikeApiExceptionHandler` + `common/src/main/java/com/rag/common/contract/ApiResponse.java` | ⚠️ `code=200/msg="OK"` 与 `5001` 兜底是 L1；信封**不能换成 `ProblemDetail`** |
| 错误表达（gRPC） | `status.Error(codes.Code(code), msg)` —— **like 业务码塞进 gRPC status code 字段** | `like-common/LikeGrpcError` | `like/contract/GrpcErrFactory` + `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | ⚠️ like 的错误码空间是 **2001 / 5001 / …**，**不是 gRPC 标准码** ⇒ 合并后必须原样保留 |
| 配置绑定 | `etc/likecenter.yaml` + `etc/like.yaml` + `etc/mq.yaml`（3 份） | `LikeApiProperties` + `LikeRpcProperties` + `LikeKafkaTopics` | 同（`service/like/src/main/java/com/rag/like/config` + `service/like/src/main/java/com/rag/like/config + service/like/src/main/java/com/rag/like/messaging`） | ⚠️ **C 的 `application.yml` 当前既无 `like-api:` 段也无 `like:` 段** ⇒ 配置键待补（见 §3.1） |
| 服务发现 | etcd（`like.rpc` / `message.rpc`） | Nacos + `discovery://` | 同 B | 注册面是表达层差异 |
| gRPC 传输安全 | go-zero zrpc 明文 | devh `@GrpcClient` **必须显式 `negotiation-type: plaintext`** | 同 B（C 的 yml 已写） | ⚠️ devh 默认 TLS ⇒ 不写会静默变 TLS |
| 超时 | `rest.RestConf.Timeout` 默认 **3000ms**（struct tag 默认值，yaml 里一个字都没写） | `observability.http-timeout: 3s` | `service/like/src/main/resources/application.yml` **当前未配** | ⚠️ 「yaml 里没写」≠「没开」（AGENTS §3.3.1） |
| 容器输入上限 | `MaxConns=10000` / `MaxBytes=1048576` / header 上限 1MiB+4KiB | Tomcat 三旋钮（`relaxed-query-chars` / `max-parameter-count: 2048` / `max-http-request-header-size: 1028KB`） | `service/like/src/main/resources/application.yml` **当前未配** | ⚠️ 漏配 1 个即「有的域 200、有的域 400」 |
| **进程拓扑** | **5 个进程**：api(18887) / rpc(8082) / mq(9100) / fact-dispatch / fact-authority | **2 个进程**：`like-api`(18887) + `like-rpc`(8082，含 mq + 2 个 cmd 的组件化) | **1 个进程**：`like-service`（HTTP `8896` + gRPC `9005`） | ★ **D1**；C 的 `fact-dispatch` / `fact-authority` **作为域内组件**，落点 `wiring/` |
| 进程级 logger 身份 | 4 个独立 `service` 值（`like.rpc` / `like-mq` / `rtw-like-fact-dispatch` / `rtw-like-fact-authority`） | 同 JVM 内多份独立 appender | 同 B | ⚠️ **不得用 SLF4J 全局 MDC**（跨线程插值） |
| 观测端点 | 各自 `DevServer` 端口（9099 / 9098 / 9100）的 `/metrics` + `/healthz` | Actuator（`management.endpoints.web.exposure`） | Actuator（`health,info,prometheus`） | ⚠️ Go 侧 `DevServer` 段在 `Config` 结构体里**没有对应字段** ⇒ 配置面有、行为面存疑 |

### 3.1 HTTP / DI 决策锁

**域内直连（D2）4 处同时改**：
1. 删 `like-api/.../client/LikeRpcClient.java` ⇒ `{advice,config,controller,dto,interceptor,metrics,service}/client/` 目录归零；
2. `service/LikeApiService.java` 直接 `@Autowired` `domain/service/LikeDomainService.java`；
3. `api` 侧的「gRPC code → 业务码」映射层删除 ⇒ 改为直接抛 `BusinessException(code,msg)`；
4. gRPC 边界统一由 `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration` 转 `StatusRuntimeException`。

**为什么换**：同进程内调 gRPC 是无意义的序列化开销与人为故障注入点；合并后只有一份业务逻辑 ⇒ 同一份逻辑要能同时产出 HTTP 与 gRPC 两种错误表达。

**配置键补齐（`like-api.*` + `like.*`）3 处同时改**：
1. `service/like/src/main/resources/application.yml` 加 `like-api.user-auth.{access-secret,access-expire}`（Go `likecenter.yaml:6-7`）；
2. 加 `like.redis.{cache-ttl,list-ttl}` + `like.kafka.*`（5 个 topic + 2 个 pusher 开关）+ `like.fact.*` + `like.authority.*`；
3. 加 `observability.http-timeout: 3s` + `server.tomcat.*` 三旋钮。

**为什么换**：Go 侧的默认值大量藏在 struct tag 里（`CacheTTL` / `Timeout` / `MaxConns` / `MaxBytes`），yaml 里看不到；**配置键不补齐 ⇒ 行为默认值就丢了**。

**错误表达统一（一个业务异常 + 两个边界适配器）3 处同时改**：
1. 业务层统一抛 `BusinessException(code,msg)`（`common/src/main/java/com/rag/common/exception/BusinessException.java`）；
2. HTTP 边界由 `advice/LikeApiExceptionHandler.java` 转 `{code,msg,data:null}` + **HTTP 200**；
3. gRPC 边界由 `RagGrpcExceptionMapper` 转 `StatusRuntimeException.withCode(code)`。

**为什么换**：Go 侧同一份业务错误在 api / rpc 两处各写一遍（api 把 gRPC status 映射回业务码，rpc 抛原始 status）；合并后只有一份逻辑 ⇒ 必须有单点。

---

## 4. 公共模块对照

> ⚠️ **边界**：`common` 域问「common 自己对不对」，`global` 问「**别人**因 common 而对不对」。本节只写 **like 域与 `common/` 的接线点**。
> like 域的 **communityfact 事件协议本体**（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `Dispatcher` / `AuthorityHandler` / `ProcessLogger`）归 `common.md`，本表**只写 like 侧的接线**。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/common/communityfact/wire.go`（信封 + 校验 + DC sender） | `service/common/.../communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/`（`Wire` / `Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `CanonicalHash` / `DeliveryStatus`） | 1:N | like 域**只接线不重写**；`likeFact` payload（19 字段）是 like 特有 ⇒ 属域内 `contract/` 包 |
| `service/common/communityfact/process.go`（`RunDispatcher`：批次 16 / 1s 节奏 / 10s deadline / `-once`） | `service/common/.../communityfact/Dispatcher.java` | `common/src/main/java/com/rag/common/communityfact/Dispatcher.java` | 1:1 | **like 侧接线点** = `wiring/LikeFactDispatchScheduler.java` |
| `service/common/communityfact/logging.go`（`ProcessMetadata` + 进程级 OBS-r3 JSON 日志） | `service/common/.../communityfact/ProcessLogger.java` | `common/src/main/java/com/rag/common/communityfact/ProcessLogger.java` | 1:1 | **like 侧接线点** = `wiring/LikeFactProcessLogging.java`（4 个进程级 logger 身份） |
| `service/common/communityfact/authority.go`（`NewAuthorityHandler` → `http.Handler`） | `service/common/.../communityfact/AuthorityHandler.java` + `spring/AuthorityHandlerFactory.java` | `common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java` + `common/src/main/java/com/rag/common/autoconfigure/WebAutoConfiguration.java` | 1:N | **like 侧接线点** = `wiring/LikeAuthorityEndpointConfig.java`（Servlet 注册） |
| `service/like/common/errmsg/errmsg.go`（**Go 的 `service/common/` 里没有 errmsg 包**；错误码是逐域自持） | 各域自持 `*ErrorCode.java`（`users-common` / `article-common` / `like-common`，**`service/common` 不提供通用错误码**） | 各域 `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java`（**common 同样不提供**） | 1:N | ⚠️ **like 用自己的错误码空间**（`service/like/src/main/java/com/rag/like/contract/ErrCodes.java`），**不从 common 取**（逐域隔离，AGENTS §1.1b） |
| `service/common/response/response.go`（信封 `{code,msg,data}`） | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不可用 `ProblemDetail` 替换** |
| go-zero 内置 `rest.WithJwt`（**Go 的 `service/common/` 里没有 jwt 包**） | `service/users/users-common/.../jwt/JwtUtil.java`（RAG 把 JWT 放 `users-common`，**不在 `service/common`**） | `common/src/main/java/com/rag/common/security/JwtUtil.java` | 1:N | claim 名 `userId`；Go 侧写入 ctx 的值类型是 `json.Number`（⇒ api 侧 `Int64()` 失败要走 1006 分支） |
| `service/common/logger/logger.go`（logx 封装） | `service/common/.../logger/Logger.java` | `common/src/main/java/com/rag/common/logger/GoStyleLogger.java`（实现 `org.slf4j.Logger`） | 1:1 | `call_chain` 字段格式是行为契约 |
| `service/common/snowflake/snowflake.go` | `service/common/.../snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` | 1:1 | ⚠️ **like 游标禁止复用**（epoch 不一致，见 §2） |
| `service/common/observability/`（http / mq / rpc 三条链） | `service/common/.../observability/` | `common/src/main/java/com/rag/common/observability/` + `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | 1:N | span 名称/属性需逐条对拍（L3 降级项 D-03） |
| go-zero `rest` 内置熔断 / CPU 降载 / `MaxConns` / `MaxBytes`（**Go 的 `service/common/` 里没有 protection 包**） | `service/common/.../protection/`（**17 类**手写复刻） | `common/src/main/java/com/rag/common/protection/`（**2 类**：`CpuThresholdHealthIndicator` + `MaxBytesFilter`）+ `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` | N:1 | **D4 生态优先**：熔断 → resilience4j；降级项见 `docs/L3-降级清单.md` |
| `service/common/protection/` 之外的 MyBatis-Plus 桥 | `like-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java`（**域内副本**） | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`（**全仓唯一一份**） | 1:N | **D3**：消除 9 份副本；A6 断言：副本数 = 0 |
| — | `like-rpc/.../mapper/JsonbTypeHandler.java`（域内副本） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | 1:N | **D3 上收** |
| — | `like-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | **—（C 无对应文件）** | C 新增 | ⚠️ Boot 4 把 Jackson 拆成独立模块且默认给 **Jackson 3**；B 有 6 处构造注入 Jackson 2 ⇒ C 需确认是否仍需 Jackson 2 `ObjectMapper` Bean |
| — | `like-api/.../config/LikeGoStyleCoercion.java`（共享规则 + like 域补强 2 条） | `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:N | ⚠️ **like 域那 2 条补强是否还在**，需逐条核对后补齐（属域内职责，不得随上收丢失） |

### 4.1 公共模块决策锁

**communityfact 接线落到 `wiring` 3 处同时改**：
1. `wiring/LikeFactDispatchScheduler.java` 调 `common/src/main/java/com/rag/common/communityfact/Dispatcher`（1s / 批次 16 / 10s deadline）；
2. `wiring/LikeAuthorityEndpointConfig.java` 注册 `common/src/main/java/com/rag/common/communityfact/AuthorityHandler` 的 Servlet（路径 `/internal/v1/community/facts`）；
3. `wiring/LikeFactProcessLogging.java` 建 4 份独立进程级 logger（**不用 MDC**）。

**为什么换**：协议本体是 comment·favorite·like 三域共用 ⇒ 必须住 `common`；like 侧只保留「接线」，不得重写协议。

**共享能力上收 `common` starter 4 处同时改**：
1. 删 `like-rpc/.../mapper/JsonbTypeHandler.java` ⇒ 用 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`；
2. 删 `like-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` ⇒ 用 `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`；
3. 删 `like-api/.../dto/GoStyleFloat64Deserializer.java` + `GoStyleRawStringListDeserializer.java` ⇒ 用 `common/src/main/java/com/rag/common/web/` 同名类；
4. 删 `like-api/.../config/LikeGoStyleCoercion.java` ⇒ 用 `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java`，**但 like 域的 2 条补强必须逐条保留**。

**为什么换**：`common` 是 starter ⇒ 能力只放一份，消费方零 `@Import`（A5 断言：清单数 = 0）。

---

## 5. 决策锁（全域汇总）

> 各节的局部决策锁见 §1.5 / §2.1 / §3.1 / §4.1；本节是**跨节联动**的总表 —— 复刻 like 域到下一个域时按这张表改，不漏。

| # | 决策 | 影响处数 | 同步改什么 | 为什么换 |
|---|---|---|---|---|
| **DL-1** | **api/rpc 合并为单 app**（D1） | 9 | 模块划分 / 包名去重复段 / 入口类 / 两份 yml 合一份 / 删 `api/client` / `like-common`→域内 `contract/` 包 / 错误契约类改名 / 两端口并存 | Spring Cloud 的部署单元是「可部署服务」，不是「api 层 / rpc 层」；go-zero 的 api/rpc 分离是 **codegen 语言产物** |
| **DL-2** | **域内调用直连**（D2） | 4 | 删 `api/client` / 直连注入 / 删 gRPC→业务码映射层 / 统一异常映射 | 同进程自我 gRPC = 无意义的序列化开销 + 人为故障注入点 |
| **DL-3** | **`cmd/fact-*` 独立进程 → 域内组件** | 6 | 2 个 cmd → `wiring/` 2 个类 + 1 个日志身份类 + 复用 `common/communityfact` + `-once` 降级为配置项 + 端口并入 | 两个 cmd **零 gRPC 注册** ⇒ 无强类型契约需要独立进程；Go 注释是**架构意图**而非技术约束 |
| **DL-4** | **api 侧 6 logic → 1 个 `LikeApiService`**（用例内聚） | 3 | 删 6 个 `*Service` / 改 Controller 调用点 / 下沉共享逻辑到 `LikeApiSupport` | 5 条判据全中；Go 的 6 个 logic 文件是 goctl 生成模板产物 |
| **DL-5** | **rpc 侧 6 logic → 1 个 `LikeDomainService`，但 13 个协作者保留独立** | 15 | 6 个 logic 合 1；协作者（`LikeRedisKeys` / `GoCompatibleJson` / `LikeActionScript` / `LikeCursorIdGenerator` / `LikeMessageDigest` / `LikeDeliveryValidator` / `LikeAuthorityLookupService` / `LikeDomainFactService` / `LikeFactDispatchService` / `LikeMessageIngestService` / `LikeKafkaPushKey` / `LikeTaskProgressPublisher`）**一个都不合并** | §5.4 修正：**协作者不是用例**（各有独立变化原因）；**事务边界类必须能被单独审视** |
| **DL-6** | **持久层 GORM → MyBatis-Plus** | 6 | pom / 5 个 Entity / 5 个 Mapper / jsonb handler / Schema 演进 / 游标自实现 | GORM tag 与 MP 注解**语义不等价**；显式迁移可管 schema 历史 |
| **DL-7** | **Schema 演进机制定案**（Flyway vs 幂等重放） | 5 | pom / 账本表名 / 迁移脚本 / 启动闸口径 / CHECK + 触发器方言 | ⏳ **待拍板**：`flyway_schema_history` 是 schema 级账本，同库多模块会撞号 |
| **DL-8** | **统一错误模型**（1 业务异常 + 2 边界适配器） | 3 | 业务层抛 `BusinessException` / HTTP 边界 Advice / gRPC 边界 mapper | Go 侧同一份错误在 api / rpc 各写一遍；合并后只有一份逻辑 |
| **DL-9** | **配置键补齐**（`like-api.*` + `like.*` + 容器三旋钮） | 3 | `like-api.*` / `like.*` / `observability` + `server.tomcat.*` | Go 的默认值大量藏在 struct tag 里 ⇒ 「yaml 里没写」≠「没开」 |
| **DL-10** | **共享能力上收 `common` starter**（D3） | 4 | jsonb handler / MP 桥 / 2 个反序列化器 / Jackson 强转规则 | 共享层只放一份，消费方零 `@Import` |
| **DL-11** | **communityfact 只接线不重写** | 3 | Dispatcher / AuthorityHandler / ProcessLogger 三个接线点 | 协议本体三域共用 ⇒ 住 `common` |
| **DL-12** | **DTO 改 immutable record + Bean Validation**（D5） | 3 | 15 个 DTO 改 `record` / 必填校验改 `@NotNull` / JSON tag 逐字保留 | Java 生态的数据契约形态；⚠️ 错误文案从 go-zero 改为 Bean Validation 文案 = **已登记的 L3 降级 D-01** |
| **DL-13** | **指标名与标签逐字照抄** | 3 | `like_api_reject_total` / `like-rpc_action_total` / `like_rpc_query_cache_total` / `like_mq_consume_total` | 指标名是**业务定义**；⚠️ `like-rpc_action_total` 的连字符是 Go 侧真实行为 ⇒ **照抄，不修正** |

---

## 6. 跨模块全局约定

> 以下每项的**值**都以 **A 列 Go 源码**为准（逐条核对，附出处）。

| 项 | 值（like 域） | A 列出处 |
|---|---|---|
| **proto 字段号 / message 名 / 枚举值** | 1 个 `enum ActionType`（`ACTION_UNSPECIFIED=0` / `ACTION_LIKE=1` / `ACTION_UNLIKE=2` / `ACTION_DISLIKE=3` / `ACTION_UNDISLIKE=4`）+ 9 个 message（`LikeActionReq` / `LikeActionResp` / `GetLikeCountReq` / `GetLikeCountResp` / `LikeCountItem` / `GetLikeStateReq` / `GetLikeStateResp` / `GetUserLikeListReq` / `GetUserLikeListResp` / `LikeRecordItem` / `GetUserTotalLikeReq` / `GetUserTotalLikeResp` / `GetTargetLikerListReq` / `GetTargetLikerListResp` / `LikeItem`）+ `service LikeService` 6 个 RPC | `proto/like.proto`（Go 仓库根）—— C 侧 `proto/src/main/proto/like.proto` 差异仅 `package pb→like` + `option` 块 + 缩进 |
| **错误码（数值空间）** | 30 个常量：`200` / `500` / `1015` / `5001`–`5012` / `1001`–`1011` / `2001` / `2002` / `3001`；**属于 like 域自己的空间，不是 gRPC 标准码** | `service/like/common/errmsg/errmsg.go:37-67` |
| **错误文案** | `codeMsg` 表 30 条；`GetErrMsg` 查不到时回退 `codeMsg[Error]` = `"FAIL"`（**不是空串**） | `service/like/common/errmsg/errmsg.go:69-99` |
| **gRPC 错误承载** | `status.Error(codes.Code(code), msg)` —— 业务码被塞进 gRPC status code 字段 | `service/like/common/errmsg/errmsg.go:33-35` |
| **JWT claims** | `userId`（Go 侧写 ctx 的值类型是 `json.Number`）；secret `ae0536f9-6450-4606-8e13-5a19ed505da0`（36 字节 UUID），`AccessExpire=31536000` | `service/like/api/internal/handler/routes.go:49` + `service/like/api/etc/likecenter.yaml:6-7` |
| **表名** | `like_record` / `like_consume_inbox` / `like_outbox_event` / `like_domain_fact_outbox` / `like_fact_stream`（**like 域独占 5 张**） | `service/like/rpc/internal/model/data_model.go` + `domain_fact.go:31,40` |
| **HTTP path** | `POST /like/v1/getlikecount` · `/getlikestate` · `/gettargetlikerlist` · `/getuserlikelist` · `/getusertotallike` · `/likeaction`（6/6 全 JWT，**public 集为空**）；端口 `18887` | `service/like/api/internal/handler/routes.go:19-50` + `etc/likecenter.yaml:3` |
| **gRPC 监听** | `0.0.0.0:8082`，服务名 `like.rpc` | `service/like/rpc/etc/like.yaml:2-5` |
| **Kafka topic** | `like_promotion_topic`（**生产与消费同一个 topic**；consumer group `like-update-group`，offset `first`，concurrency `8`）· `like_update_topic`（`KafkaPusher`，**配置从未被读取 ⇒ 不实现**）· `task.logs.filtered.User` · `task.logs.filtered.Article` · `article-hot-events` | `rpc/etc/like.yaml`（`KafkaConf.Topic` / `KafkaPusher.Topic` / `TaskUserPusherConf.Topic` / `TaskArticlePusherConf.Topic`）+ `rpc/internal/mq/etc/mq.yaml`（`Kafka.Topic` / `HotEventPusherConf.Topic`） |
| **指标名** | `like_api_reject_total{route,reason}` · `like-rpc_action_total{target_type,action,result}` · `like_rpc_query_cache_total{target_type,result}` · `like_mq_consume_total{action,result}` | `api/internal/metrics/metrics.go` + `rpc/internal/metrics/metrics.go` + `rpc/internal/mq/internal/metrics/metrics.go` |
| **⚠️ 指标已知缺陷（照抄不修正）** | `like-rpc_action_total` 用**连字符** ⇒ Prometheus 指标名正则不允许 ⇒ **该指标不会出现在 `/metrics`** | `rpc/internal/metrics/metrics.go`（`Subsystem: "like-rpc"`，全仓唯一用连字符的） |
| **Redis key 模式** | `like_count:{target_type}` · `like_state:{target_type}:{target_id}` · `user_like_list:{user_id}:{target_type}` · `target_liker_list:{target_type}:{target_id}` · `user_total_like:{user_id}` | `rpc/internal/logic/{getlikecountlogic,getlikestatelogic,getuserlikelistlogic,gettargetlikerlistlogic,getusertotallikelogic}.go` |
| **游标编码** | `(unixMilli << 22) \| (nodeId << 12) \| seq`；读侧 `score >> 22` 取**裸 Unix 毫秒** | `rpc/internal/logic/likeactionlogic.go`（自写雪花） |
| **Kafka payload 字段** | `KafkaLikeMsg{msg_id,user_id,target_type,target_id,author_id,state,is_first,created_at}` · `ArticleHotEvent{article_id,type,user_id(**string**),timestamp,is_first}` · `TaskArticleProgressMsg{user_id(**string**),cur}` | `service/like/rpc/internal/types/types.go` |
| **HTTP JSON tag** | `target_type` / `target_id` / `target_ids` / `action_type` / `author_id` / `weight` / `client_ip` / `promotion_id` / `counts` / `states` / `list` / `is_end` / `next_cursor` / `total_like_count` / `like_count` / `dislike_count` / `success` / `message` | `service/like/api/internal/types/types.go` |
| **Go 侧「配了但没消费」的键** | `RocksDB.*`（整套 KV 从未打开）· `Redis.HotspotTTL` · `Redis.BatchSize` · `Postgres.SyncBatchSize/SyncInterval` · `HotSpot.*` · `AntiBrush.*` · `KafkaPusher` · `DevServer`（`Config` 结构体无对应字段） | `service/like/rpc/etc/like.yaml` + `api/etc/likecenter.yaml` ⇒ **C 侧不得「补全」这些行为**（那是新增行为） |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏（硬要求）

**自查命令**
```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/like" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

**结果：67 / 67 = 100%**

| 模块 | C 列 `.java` 数 | 本表覆盖 | 覆盖率 |
|---|---|---|---|
| `service/like/src/main/java/com/rag/like/contract/` | 7 | 7 | 100% |
| `service/like/src/main/java/com/rag/like/`（技术角色包，含 `advice` 2 / `wiring` 5 / `client` 1 / `config` 3 / `controller` 1 / `domain/entity` 5 / `domain/service` 2 / `dto` 15 / `exception` 1 / `grpc` 1 / `interceptor` 1 / `mapper` 5 / `messaging` 2 / `metrics` 3 / `service` 12） | 59 | 59 | 100% |
| `service/like/src/main/java/com/rag/like/LikeServiceApplication.java` | 1 | 1 | 100% |
| **合计** | **67** | **67** | **100%** |

**逐条清单（basename，去重后 67 个）**

`ArticleHotEvent` · `CodeError` · `ErrCodes` · `ErrMsg` · `GrpcErrFactory` · `KafkaLikeMsg` · `TaskArticleProgressMsg` · `LikeServiceApplication` · `LikeApiExceptionHandler` · `LikeApiResponseAdvice` · `LikeApiProperties` · `WebMvcConfig` · `LikeController` · `GetLikeCountReq` · `GetLikeCountResp` · `GetLikeStateReq` · `GetLikeStateResp` · `GetTargetLikerListReq` · `GetTargetLikerListResp` · `GetUserLikeListReq` · `GetUserLikeListResp` · `GetUserTotalLikeReq` · `GetUserTotalLikeResp` · `LikeActionReq` · `LikeActionResp` · `LikeCountItem` · `LikeItem` · `LikeRecordItem` · `LikeJwtAuthInterceptor` · `LikeApiMetrics` · `LikeApiService` · `LikeApiSupport` · `LikeAuthorityEndpointConfig` · `LikeFactDispatchScheduler` · `LikeFactProcessLogging` · `LikeSchemaGate` · `NacosRegistration` · `MessageRpcClient` · `LikeKafkaTopics` · `LikeRpcProperties` · `LikeConsumeInboxEntity` · `LikeDomainFactOutboxEntity` · `LikeFactStreamEntity` · `LikeOutboxEventEntity` · `LikeRecordEntity` · `LikeExceptions` · `LikeConsumeInboxMapper` · `LikeDomainFactOutboxMapper` · `LikeFactStreamMapper` · `LikeOutboxEventMapper` · `LikeRecordMapper` · `LikeMqMetrics` · `LikeRpcMetrics` · `GoCompatibleJson` · `LikeActionScript` · `LikeAuthorityLookupService` · `LikeCursorIdGenerator` · `LikeDeliveryValidator` · `LikeDomainFactService` · `LikeDomainService` · `LikeFactDispatchService` · `LikeKafkaPushKey` · `LikeMessageDigest` · `LikeMessageIngestService` · `LikeRedisKeys` · `LikeTaskProgressPublisher` · `LikeGrpcService`

### 7.2 A 列不遗漏职责（硬要求）

**A 列非测试 `.go` 共 51 个**（含 2 个 protoc 生成物）⇒ **51 / 51 = 100%** 逐条落点：

| # | A 列 Go 文件 | C 列落点 |
|---|---|---|
| 1 | `api/likecenter.go` | `LikeServiceApplication` + `service/like/src/main/java/com/rag/like/advice/LikeApiResponseAdvice.java` + `service/like/src/main/java/com/rag/like/advice/LikeApiExceptionHandler.java` |
| 2 | `api/internal/config/config.go` | `service/like/src/main/java/com/rag/like/config/LikeApiProperties.java` |
| 3 | `api/internal/handler/routes.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` + `service/like/src/main/java/com/rag/like/config/WebMvcConfig.java` + `service/like/src/main/java/com/rag/like/interceptor/LikeJwtAuthInterceptor.java` |
| 4 | `api/internal/handler/like/getlikecounthandler.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |
| 5 | `api/internal/handler/like/getlikestatehandler.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |
| 6 | `api/internal/handler/like/gettargetlikerlisthandler.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |
| 7 | `api/internal/handler/like/getuserlikelisthandler.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |
| 8 | `api/internal/handler/like/getusertotallikehandler.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |
| 9 | `api/internal/handler/like/likeactionhandler.go` | `service/like/src/main/java/com/rag/like/controller/LikeController.java` |
| 10 | `api/internal/logic/like/getlikecountlogic.go` | `service/like/src/main/java/com/rag/like/service/LikeApiService.java` + `service/like/src/main/java/com/rag/like/service/LikeApiSupport.java` |
| 11 | `api/internal/logic/like/getlikestatelogic.go` | 同上 |
| 12 | `api/internal/logic/like/gettargetlikerlistlogic.go` | 同上 |
| 13 | `api/internal/logic/like/getuserlikelistlogic.go` | 同上 |
| 14 | `api/internal/logic/like/getusertotallikelogic.go` | 同上 |
| 15 | `api/internal/logic/like/likeactionlogic.go` | 同上 |
| 16 | `api/internal/metrics/metrics.go` | `service/like/src/main/java/com/rag/like/metrics/LikeApiMetrics.java` |
| 17 | `api/internal/svc/servicecontext.go` | `service/like/src/main/java/com/rag/like/config/LikeApiProperties.java`（+ D2：`service/like/src/main/java/com/rag/like/{advice,config,controller,dto,interceptor,metrics,service}/client/` 清空） |
| 18 | `api/internal/types/types.go` | `service/like/src/main/java/com/rag/like/dto/*`（15 个）+ `service/like/src/main/java/com/rag/like/service/LikeApiSupport.java` |
| 19 | `common/errmsg/errmsg.go` | `like/contract/{CodeError,ErrCodes,ErrMsg,GrpcErrFactory}.java` + `service/like/src/main/java/com/rag/like/exception/LikeExceptions.java` |
| 20 | `rpc/like.go` | `LikeServiceApplication.java` + `service/like/src/main/java/com/rag/like/wiring/NacosRegistration.java` |
| 21 | `rpc/likeservice/likeservice.go` | 根级 `proto/` 模块（protoc 生成 `LikeServiceGrpc`，**无手写文件**） |
| 22 | `rpc/pb/like.pb.go` | 根级 `proto/` 模块（protoc 生成，**无手写文件**） |
| 23 | `rpc/pb/like_grpc.pb.go` | 根级 `proto/` 模块（protoc 生成，**无手写文件**） |
| 24 | `rpc/internal/config/config.go` | `service/like/src/main/java/com/rag/like/config/LikeRpcProperties.java` |
| 25 | `rpc/internal/logic/getlikecountlogic.go` | `service/like/src/main/java/com/rag/like/domain/service/LikeDomainService.java` + `service/like/src/main/java/com/rag/like/service/LikeRedisKeys.java` |
| 26 | `rpc/internal/logic/getlikestatelogic.go` | 同上 |
| 27 | `rpc/internal/logic/gettargetlikerlistlogic.go` | 同上 |
| 28 | `rpc/internal/logic/getuserlikelistlogic.go` | 同上 |
| 29 | `rpc/internal/logic/getusertotallikelogic.go` | 同上 |
| 30 | `rpc/internal/logic/likeactionlogic.go` | `service/like/src/main/java/com/rag/like/domain/service/LikeDomainService.java` + `LikeActionScript` + `LikeCursorIdGenerator` + `LikeRedisKeys` + `LikeKafkaPushKey` + `LikeTaskProgressPublisher` |
| 31 | `rpc/internal/metrics/metrics.go` | `service/like/src/main/java/com/rag/like/metrics/LikeRpcMetrics.java` |
| 32 | `rpc/internal/model/db.go` | `service/like/src/main/java/com/rag/like/wiring/LikeSchemaGate.java` + `service/like/src/main/java/com/rag/like/mapper/*` |
| 33 | `rpc/internal/model/data_model.go` | `service/like/src/main/java/com/rag/like/domain/entity/{LikeRecordEntity,LikeConsumeInboxEntity,LikeOutboxEventEntity}.java` |
| 34 | `rpc/internal/model/domain_fact.go` | `service/like/src/main/java/com/rag/like/domain/entity/{LikeDomainFactOutboxEntity,LikeFactStreamEntity}.java` + `service/like/src/main/java/com/rag/like/domain/service/LikeDomainFactService.java` |
| 35 | `rpc/internal/model/fact_authority.go` | `service/like/src/main/java/com/rag/like/service/LikeAuthorityLookupService.java` |
| 36 | `rpc/internal/model/fact_delivery.go` | `service/like/src/main/java/com/rag/like/service/LikeDeliveryValidator.java` + `service/like/src/main/java/com/rag/like/service/LikeFactDispatchService.java` |
| 37 | `rpc/internal/model/like.go` | `service/like/src/main/java/com/rag/like/mapper/LikeRecordMapper.java` + `service/like/src/main/java/com/rag/like/domain/service/LikeDomainService.java` + `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java` |
| 38 | `rpc/internal/model/like_consume_inbox.go` | `service/like/src/main/java/com/rag/like/domain/entity/LikeConsumeInboxEntity.java` + `service/like/src/main/java/com/rag/like/mapper/LikeConsumeInboxMapper.java` |
| 39 | `rpc/internal/model/like_message_hash.go` | `service/like/src/main/java/com/rag/like/service/LikeMessageDigest.java` + `service/like/src/main/java/com/rag/like/service/GoCompatibleJson.java` |
| 40 | `rpc/internal/model/like_outbox.go` | `service/like/src/main/java/com/rag/like/domain/entity/LikeOutboxEventEntity.java` + `service/like/src/main/java/com/rag/like/mapper/LikeOutboxEventMapper.java` + ⚠️ **GAP-2** |
| 41 | `rpc/internal/server/likeserviceserver.go` | `service/like/src/main/java/com/rag/like/grpc/LikeGrpcService.java` |
| 42 | `rpc/internal/svc/servicecontext.go` | `service/like/src/main/java/com/rag/like/client/MessageRpcClient.java` + `service/like/src/main/java/com/rag/like/messaging/LikeKafkaTopics.java` + `service/like/src/main/java/com/rag/like/mapper/*` + `service/like/src/main/java/com/rag/like/service/LikeKafkaPushKey.java` |
| 43 | `rpc/internal/types/types.go` | `like/contract/{KafkaLikeMsg,ArticleHotEvent,TaskArticleProgressMsg}.java` |
| 44 | `rpc/cmd/fact-dispatch/main.go` | `service/like/src/main/java/com/rag/like/wiring/LikeFactDispatchScheduler.java` + `service/like/src/main/java/com/rag/like/wiring/LikeFactProcessLogging.java` |
| 45 | `rpc/cmd/fact-authority/main.go` | `service/like/src/main/java/com/rag/like/wiring/LikeAuthorityEndpointConfig.java` + `service/like/src/main/java/com/rag/like/wiring/LikeFactProcessLogging.java` |
| 46 | `rpc/internal/mq/mq.go` | ⚠️ **GAP-1** + **GAP-2**（C 无消费入口类、无发件箱中继） |
| 47 | `rpc/internal/mq/internal/config/config.go` | `service/like/src/main/java/com/rag/like/messaging/LikeKafkaTopics.java` + `service/like/src/main/java/com/rag/like/config/LikeRpcProperties.java` |
| 48 | `rpc/internal/mq/internal/metrics/metrics.go` | `service/like/src/main/java/com/rag/like/metrics/LikeMqMetrics.java` |
| 49 | `rpc/internal/mq/internal/mqs/likemq.go` | `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java`（业务层）+ ⚠️ **GAP-1**（消费入口层无落点） |
| 50 | `rpc/internal/mq/internal/mqs/like_outbox_sender.go` | ⚠️ **GAP-2**（C 无对应文件） |
| 51 | `rpc/internal/mq/internal/svc/servicecontext.go` | `service/like/src/main/java/com/rag/like/mapper/*` + `service/like/src/main/java/com/rag/like/service/LikeMessageIngestService.java` |

**非 `.go` 的 A 列资产**（一并登记）

| A 列文件 | C 列落点 |
|---|---|
| `api/etc/likecenter.yaml` | `service/like/src/main/resources/application.yml` 的 `like-api.*` 段（**待补**） |
| `rpc/etc/like.yaml` | `service/like/src/main/resources/application.yml` 的 `like.*` 段（**待补**） |
| `rpc/internal/mq/etc/mq.yaml` | 同上（`like.kafka.*`） |
| `rpc/internal/model/migrations/001_like_domain_fact_outbox.sql` | `service/like/src/main/resources/db/migration/`（**目录已留，脚本待补**） |
| `rpc/internal/model/migrations/002_like_dc_wire.sql` | 同上 |
| `rpc/internal/model/migrations/003_like_fact_delivery.sql` | 同上 |

### 7.3 映射类型分布

| 映射类型 | 行数 | 主要出现在 |
|---|---|---|
| `1:1` | 38 | 契约模块类名对应、Entity / Mapper 对应、13 个 rpc 协作者类对应、metrics / config / proto 对应 |
| `1:N` | 37 | 顶层模块合并、6 handler → 1 Controller、Go struct → 5 Entity、ServiceContext → 多个 Bean、上收 common |
| `N:1` | 3 | api 6 logic → 1 `LikeApiService`；rpc 6 logic → 1 `LikeDomainService`；go-zero `rest` 内置保护层 4 项 → 1 个 `common/protection` |
| `C 新增` | 1 | Jackson 2 `ObjectMapper` Bean（C 无对应文件，B 有 ⇒ 见 GAP-3） |
| **合计** | **79** | 每行均有映射类型，无空缺；无 `N:M` / `1:1:1` 等自造词 |

### 7.4 三列之间「职责对不上」的地方（待补清单）

| # | 缺口 | A 列出处 | B 列 | C 列 | 影响 |
|---|---|---|---|---|---|
| **GAP-1** | **Kafka 消费入口（listener / ack 契约 / 失败即丢弃不重投）无落点** | `rpc/internal/mq/mq.go` + `rpc/internal/mq/internal/mqs/likemq.go` | `mq/LikeUpdateKafkaListener.java` | **无** | `service/like/pom.xml` 已引 `spring-boot-kafka` ⇒ **能力在、落点无** ⇒ 实现阶段会整个漏掉，且**没有编译错误或测试红灯**（与 RAG `article-rpc` 2 颗哑弹同款形态） |
| **GAP-2** | **`like_outbox_event` → `article-hot-events` 发件箱中继无落点** | `rpc/internal/mq/internal/mqs/like_outbox_sender.go` + `rpc/internal/mq/mq.go`（1s ticker） | `mq/LikeHotEventOutboxRelay.java` | **无** | 同上；且 `like_outbox_event` 表已在 C 的 DDL 待补清单里 |
| **GAP-3** | **Jackson 2 `ObjectMapper` Bean 无落点** | —（Go 侧无对应物） | `like-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | **无** | Boot 4 把 Jackson 拆成独立模块且默认给 Jackson 3 ⇒ 若 C 侧有代码构造注入 Jackson 2 的 `ObjectMapper`，将**启动即失败** |
| **GAP-4** | **api 侧 `target_ids` 的 null 元素语义无独立文件** | `api/internal/logic/like/getlikecountlogic.go` + `gettargetlikerlistlogic.go` | `api/service/GoStyleTargetIds.java` | 须并入 `service/like/src/main/java/com/rag/like/service/LikeApiSupport.java` | protobuf `addAllTargetIds` 对 null 元素**硬失败**；漏掉 ⇒ 行为漂移 |
| **GAP-5** | **`like:` / `like-api:` 配置段整个缺失** | `api/etc/likecenter.yaml` + `rpc/etc/like.yaml` + `rpc/internal/mq/etc/mq.yaml` | `like-api/src/main/resources/application.yml` + `like-rpc/src/main/resources/application.yml` | `service/like/src/main/resources/application.yml` **两段都没有** | Go 的默认值大量藏在 struct tag 里 ⇒ 漏配即行为丢失（超时 / TTL / topic / 熔断 / 容器三旋钮） |
| **GAP-6** | **DDL 迁移脚本为空** | `rpc/internal/model/migrations/001..003.sql` | `like-rpc/src/main/resources/db/migration/V1..V6` | `service/like/src/main/resources/db/migration/` **仅 `.gitkeep`** | 且**迁移机制本身未拍板**（Flyway vs 幂等重放，DL-7） |
| **GAP-7** | **like 域的 2 条 Jackson 强转补强是否还在，未核对** | `api/internal/types/types.go`（`action_type,options` / `author_id,string`） | `like-api/.../config/LikeGoStyleCoercion.java`（共享规则 + like 补强 2 条） | 上收到 `common/src/main/java/com/rag/common/web/Coercion.java` | 上收时**域内补强不能丢**；丢了 ⇒ `author_id` 的字符串语义与 `options` 校验时机漂移 |
| **GAP-8** | **`like_api_reject_total` 的「Go 侧未注册」与 C 侧自动注册的差异未声明** | `api/internal/metrics/metrics.go`（`InitMetrics()` 全仓零调用点） | `api/metrics/LikeApiMetrics.java` | `service/like/src/main/java/com/rag/like/metrics/LikeApiMetrics.java` | Go 侧指标**抓不到**；Micrometer 会自动注册 ⇒ **观测面新增**（多出一个 Go 没有的指标）⇒ 需在 `docs/Intentional_Deviations/like/` 登记或按 Go 现状保持未暴露 |

---

## 8. 一句话总结

`Sea-RideTheWind_golang` = 5 个进程（api / rpc / mq / fact-dispatch / fact-authority）+ GORM `AutoMigrate` + go-zero `rest` + `kq` 自研 Kafka；
`RAG` = 2 个进程（like-api / like-rpc，4 个 Go `main` 合并进单 JVM）+ MyBatis-Plus + Flyway + `mq/` 包承载 fact-* 组件；
`RAG-V1` = 1 个进程（like-service）+ MyBatis-Plus + **迁移机制待拍板** + `wiring` 承载 fact-* 组件 + `common/` starter 承接共享能力。
⇒ **proto 字段号 / 错误码 / JWT claims / 表名 / HTTP path / Kafka topic / 指标名 全部 1:1 复刻 Go**；⚠️ **`like-rpc_action_total` 的连字符照抄不修正**；⚠️ **C 列的 67 个文件目前全是空壳**，本表写的是「该放什么职责」。
