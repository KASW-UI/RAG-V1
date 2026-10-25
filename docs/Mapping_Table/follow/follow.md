# Mapping_Table/follow/follow.md — follow 域三方文件名对照表（A Go ↔ B RAG ↔ C RAG-V1）

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标架构）三棵树里
> **follow 域**的文件逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 该域路径 |
> |---|---|---|
> | **A (Go 真理之源)** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | `service/follow/` |
> | **B (RAG, Go 形态版)** | `/Users/pr/Documents/java&golang/RAG` | `service/follow/` |
> | **C (RAG-V1, 本仓目标)** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/follow/` |
>
> **规模基线（实测）**：A = **44 个手写 `.go`** + 2 个 protoc 生成 `.go` + 2 个 `.yaml` = **48 个文件**；
> B = **48 个 `.java`**（不含测试）；C = **37 个 `.java`**（不含测试）。
>
> ⚠️ **C 列目前全是空壳** —— 每个 `.java` 只有 `package` + 空类型 + TODO javadoc ⇒
> **C 列写的是「这个位置该放什么职责」，不是「已经有什么」。**
>
> ⚠️ **三条不可违反的纪律**：
> 1. **B 列不是 Go 的替身** —— 一切行为判断以 **A 列 Go 源码**为准（RAG 在 `users`/`like`/`message`/`common`/`article` 五域已被判 `NOT EQUIVALENT`）。
> 2. **本表只回答「文件名 / 职责对应关系」**，**不下「已对齐 / 未对齐」结论** —— 行为对齐状态看 `docs/DRIFT/<域>/<组件>.md` 的**证据强度列**。
> 3. 映射类型**只用** `1:1` / `1:N` / `N:1` / `C 新增` 四种，不发明新词。

---

## 0. 顶层模块对照

| A Go 文件 / 目录 | B RAG 文件 / 目录 | C RAG-V1 文件 / 目录 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/follow/api/`（25 个文件：24 `.go` + 1 `.yaml`） | `service/follow/follow-api/`（27 个 `.java`） | `service/follow/src/main/java/com/rag/follow/advice + service/follow/src/main/java/com/rag/follow/config + service/follow/src/main/java/com/rag/follow/controller + service/follow/src/main/java/com/rag/follow/dto + service/follow/src/main/java/com/rag/follow/interceptor + service/follow/src/main/java/com/rag/follow/metrics + service/follow/src/main/java/com/rag/follow/service`（17 个 `.java`） | 1:1 | **D1**：api 与 rpc 合并进同一 app ⇒ C 列的 `api/` 包只是**同一进程内的 HTTP 层**，不再是独立部署单元 |
| `service/follow/rpc/`（22 个文件：20 `.go` + 2 `pb/*.go` + 1 `.yaml`） | `service/follow/follow-rpc/`（17 个 `.java`） | `service/follow/src/main/java/com/rag/follow/bootstrap + service/follow/src/main/java/com/rag/follow/client + service/follow/src/main/java/com/rag/follow/config + service/follow/src/main/java/com/rag/follow/domain/entity + service/follow/src/main/java/com/rag/follow/domain/service + service/follow/src/main/java/com/rag/follow/grpc + service/follow/src/main/java/com/rag/follow/mapper + service/follow/src/main/java/com/rag/follow/metrics + service/follow/src/main/java/com/rag/follow/service`（15 个 `.java`） | 1:1 | 同上；C 列的 `rpc/` 包 = **服务端能力层**（命名债，见《完全spring化的文件树》§5.2） |
| `service/follow/common/`（1 个文件） | `service/follow/follow-common/`（4 个 `.java`） | `service/follow/src/main/java/com/rag/follow/contract/`（4 个 `.java`） | 1:1 | 模块改名 `follow-common` → 域内 `contract/` 包；包名 `com.rag.follow.follow.common.errmsg` → `com.rag.follow.contract` |
| `service/follow/rpc/pb/`（protoc 生成物，2 个 `.go`） | `service/follow/follow-proto/`（`src/main/proto/follow.proto`） | `proto/src/main/proto/follow.proto` | 1:1 | `option go_package` 删、`java_package` 改；**message 名 / 字段名 / 字段号 / 类型 / rpc 签名 1:1 不变** |
| —（Go 侧跨服务共享层 `service/common/`） | `service/common/`（51 个 `.java`，普通 jar） | `common/`（仓库根，47 个 `.java` + `autoconfigure/` 7 类，真 starter） | 1:1 | **D3**：共享层升级为 starter ⇒ 见 §4 |
| —（Go 侧 follow 域**无** proto 独立模块） | `service/follow/follow-proto/`（proto 源在模块内） | 根级 `proto/src/main/proto/follow.proto`（**D7 后 10 域共用一个 proto 模块**；域内 `contract/` 包只放错误契约与事件 DTO） | 1:1 | Go 的 proto 源文件在**仓库根** `proto/follow.proto`，不在 `service/follow/` 下 ⇒ 属于 A 列域外的引用，登记于 §6.1 |

> ⚠️ **Go 侧 follow 域没有 `cmd/` 独立进程** —— go-zero 的 follow 只有两个进程入口文件 `{advice,config,controller,dto,interceptor,metrics,service}/followcenter.go` 与 `{bootstrap,client,config,domain/entity,domain/service,grpc,mapper,metrics,service}/follow.go`（**不是** `cmd/` 布局）；RAG / RAG-V1 用 `follow-api` / `follow-rpc` / `follow-service` 模块表达 ⇒ **Go 侧无对应物，不补**（本条不占映射表行，故无映射类型）。

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
| `follow/api/`（`FollowApiApplication`） | `com.rag.follow`（`FollowServiceApplication.java`） | 入口类合并（D1）|
| `follow/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `FollowApiResponseAdvice`）|
| `follow/api/client/` | `client/` | 直接压平 |
| `follow/api/config/` | `config/` | 与 rpc 侧合并 |
| `follow/api/controller/` | `controller/` | 直接压平 |
| `follow/api/dto/` | `dto/` | 直接压平（6 个同名类全落此）|
| `follow/api/interceptor/` | `interceptor/` | 直接压平（2 个）|
| `follow/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `follow/api/service/` | `service/` | 与 rpc 侧合并 |
| `follow/common/errmsg/` | `contract/` | 错误码（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）|
| `follow/rpc/bootstrap/` | `bootstrap/` | 直接压平 |
| `follow/rpc/client/` | `client/` | 与 api 侧合并 |
| `follow/rpc/config/` | `config/` | 合并 |
| `follow/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `follow/rpc/mapper/` | `mapper/` | 直接压平（含 `GoStyleLimitOffset`）|
| `follow/rpc/metrics/` | `metrics/` | 合并 |
| `follow/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `follow/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `FollowServiceGrpcImpl` → `FollowGrpcService` |
| `follow-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.follow`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 契约层（域内 `common` / 错误码 / proto）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/follow/common/errmsg.go:10-31`（`CodeError` struct + `Error()` + `NewErrCode` + `NewErrCodeMsg`） | `follow-common/.../errmsg/FollowCodeError.java` | `service/follow/src/main/java/com/rag/follow/contract/CodeError.java` | 1:1 | `Code` / `Msg` **双访问器**（`BusinessException` 只有 `getCode()`/`getMessage()`，语义不同）；`Error()` 的 `"ErrCode:%d, Errmsg:%s"` 只进 Go 日志 ⇒ **不迁**（非可观察差异） |
| `service/follow/common/errmsg.go:33-53`（18 个常量） | `follow-common/.../errmsg/FollowErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | 码值逐字照抄：`200/500/1015/5001-5008/5101/5201-5204` |
| `service/follow/common/errmsg.go:55-74` + `:89-95`（`codeMsg` 表 + `GetErrMsg`） | `follow-common/.../errmsg/FollowErrorMessages.java` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | ⚠️ **文案是英文**（`"service busy"` / `"internal server error"` / `"record not found"` …）⇒ **逐域隔离，不可跨域统一**；未知码回退 `"FAIL"` 是 L1 |
| `service/follow/common/errmsg.go:76-87` + `:97-133`（`bizCodeByMsg` + `GRPCError` + `BizCodeFromError`） | `follow-common/.../errmsg/FollowGrpcError.java` | `service/follow/src/main/java/com/rag/follow/contract/GrpcErrorMapper.java` | 1:1 | 🔴 **反查顺序即语义**：先 `bizCodeByMsg[st.Message()]`（`:114`），查不到才按 gRPC 标准码 switch（`:118-132`）⇒ **不得调换** |
| `service/follow/rpc/pb/follow.pb.go` + `rpc/pb/follow_grpc.pb.go` | `follow-proto/`（protoc 生成物，11 message + `FollowServiceGrpc`） | 根级 `proto/` 模块（protoc 生成物，落包 `com.rag.proto.follow`） | 1:1 | **生成物**；⚠️ **D7 后 pb 包名 = `com.rag.proto.follow`**（原 `com.rag.follow.follow.proto` → `com.rag.follow.contract.proto` → `com.rag.proto.follow`；**仅落包位置变化，不改 wire**） |

### 1.2 API 层（进程装配 · 路由 · Handler · DTO · 中间件 · 指标）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/follow/api/followcenter.go`（进程装配） | `follow-api/.../FollowApiApplication.java` | `service/follow/src/main/java/com/rag/follow/FollowServiceApplication.java` | 1:1 | **D1** ⇒ C 只有**一个** `@SpringBootApplication`（api + rpc 合流） |
| `service/follow/api/followcenter.go:45-51`（`httpx.SetOkHandler`） | `follow-api/.../advice/GlobalResponseAdvice.java` | `service/follow/src/main/java/com/rag/follow/advice/FollowApiResponseAdvice.java` | 1:1 | 改名；成功信封 `{code:200,msg:"OK",data:...}` |
| `service/follow/api/followcenter.go:53-66`（`httpx.SetErrorHandler`） | `follow-api/.../advice/GlobalExceptionHandler.java` | `service/follow/src/main/java/com/rag/follow/advice/FollowApiExceptionHandler.java` | 1:1 | 改名；HTTP 状态码**恒 200**（除 401），成败只体现在 body 的 `code` |
| `service/follow/api/etc/followcenter.yaml` | `follow-api/src/main/resources/application.yml` | `service/follow/src/main/resources/application.yml` | 1:1 | C 的同一个 yml 同时承载 api 段（`server.port`）与 rpc 段（`grpc.server.port`） |
| `service/follow/api/internal/config/config.go`（`RestConf`+`UserAuth`+`FollowRpc`+`BizRedis`） | `follow-api/.../config/FollowApiProperties.java` | `service/follow/src/main/java/com/rag/follow/config/FollowApiProperties.java` | 1:1 | 只迁**有消费者**的段：`UserAuth` 进 Properties；`BizRedis` 是纯连接参数 ⇒ 进 `application.yml` 的 `spring.data.redis.*`；`FollowRpc` 是寻址 ⇒ 进 `grpc.client.*` |
| `service/follow/api/internal/svc/servicecontext.go:23`（Redis 连接） | `follow-api/.../interceptor/FollowBlacklistInterceptor.java`（注入 `StringRedisTemplate`） | `service/follow/src/main/java/com/rag/follow/interceptor/FollowBlacklistInterceptor.java` | 1:1 | Redis 只为黑名单存在 |
| `service/follow/api/internal/svc/servicecontext.go:27`（`FollowRpc` 客户端） | `follow-api/.../client/FollowRpcClient.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java`（**直连**） | 1:N | **D2**：域内 gRPC 往返消失 ⇒ **C 列无 `FollowRpcClient`** |
| `service/follow/api/internal/svc/servicecontext.go:28`（构造黑名单中间件） | `follow-api/.../interceptor/FollowBlacklistInterceptor.java`（`@Component`） | `service/follow/src/main/java/com/rag/follow/interceptor/FollowBlacklistInterceptor.java` | 1:1 | 构造交给 Spring 容器 |
| `service/follow/api/internal/handler/routes.go`（8 条路由 + 三层装饰器） | `follow-api/.../controller/FollowController.java` + `.../config/WebMvcConfig.java` | `service/follow/src/main/java/com/rag/follow/controller/FollowController.java` + `service/follow/src/main/java/com/rag/follow/config/WebMvcConfig.java` | 1:N | `rest.WithPrefix("/follow/v1")` → `@RequestMapping("/follow/v1")`；`rest.WithJwt` / `rest.WithMiddlewares` → `addInterceptors`（**注册顺序即执行顺序**） |
| `service/follow/api/internal/handler/follow/followhandler.go` | `follow-api/.../controller/FollowController.java` | `service/follow/src/main/java/com/rag/follow/controller/FollowController.java` | N:1 | 8 个 handler **逐字同质**（`Parse → logic → 信封`）⇒ 合 1 个 Controller |
| `service/follow/api/internal/handler/follow/unfollowhandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/handler/follow/blockhandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/handler/follow/unblockhandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/handler/follow/getfollowlisthandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/handler/follow/getfollowerlisthandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/handler/follow/getblocklisthandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/handler/follow/getrecommendationshandler.go` | 同上 | 同上 | N:1 | 同上 |
| `service/follow/api/internal/types/types.go:6-10`（`FollowListReq`） | `follow-api/.../dto/FollowListReq.java` | `service/follow/src/main/java/com/rag/follow/dto/FollowListReq.java` | 1:1 | 3 字段**全 `optional`**；`user_id` 带 `,string` |
| `service/follow/api/internal/types/types.go:12-14`（`FollowUserListResp`） | `follow-api/.../dto/FollowUserListResp.java` | `service/follow/src/main/java/com/rag/follow/dto/FollowUserListResp.java` | 1:1 | 🔴 `user_ids` **无** `,string` ⇒ JSON 是**数字数组** `[1234,5678]` |
| `service/follow/api/internal/types/types.go:16-18`（`RecommendListResp`） | `follow-api/.../dto/RecommendListResp.java` | `service/follow/src/main/java/com/rag/follow/dto/RecommendListResp.java` | 1:1 | 空列表必须序列化为 `[]` 而非 `null` |
| `service/follow/api/internal/types/types.go:20-23`（`RecommendUser`） | `follow-api/.../dto/RecommendUser.java` | `service/follow/src/main/java/com/rag/follow/dto/RecommendUser.java` | 1:1 | 🔴 `target_id` **有** `,string` ⇒ JSON 是**字符串** `"1234"`（与上一行的数字数组**形态不一致**，是本域最隐蔽的 L1 差异） |
| `service/follow/api/internal/types/types.go:25-27`（`RelationActionReq`） | `follow-api/.../dto/RelationActionReq.java` | `service/follow/src/main/java/com/rag/follow/dto/RelationActionReq.java` | 1:1 | 🔴 `target_id` **无 `optional` ⇒ 必填**；缺失 ⇒ HTTP 200 + `{"code":5001,...}` |
| `service/follow/api/internal/types/types.go:29-31`（`RelationActionResp`） | `follow-api/.../dto/RelationActionResp.java` | `service/follow/src/main/java/com/rag/follow/dto/RelationActionResp.java` | 1:1 | `success` **恒 true**（硬编码） |
| `service/follow/api/internal/types/types.go:8-9`（`offset`/`limit` 无 `,string` ⇒ 禁字符串入参） | `follow-api/.../config/FollowGoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/GoStyleCoercion.java` + `common/src/main/java/com/rag/common/autoconfigure/RagJacksonAutoConfiguration.java`（**无域内文件**） | 1:N | ⚠️ Go 拒 `{"offset":"10"}`，Jackson 3 默认收 ⇒ B 用域内 1 条补强；**C 无域内落点** ⇒ 见 §7 缺口 #2 |
| `service/follow/api/internal/middleware/checkblacklistmiddleware.go`（全文 55 行） | `follow-api/.../interceptor/FollowBlacklistInterceptor.java` | `service/follow/src/main/java/com/rag/follow/interceptor/FollowBlacklistInterceptor.java` | 1:1 | 🔴 **follow 域独有**（like / users 的 blacklist 复用 401 空 body，本域是 **401 + JSON body**）⇒ 必须**直写 response**，不得走异常链 |
| `service/follow/api/internal/handler/routes.go:62`（`rest.WithJwt`） | `follow-api/.../interceptor/FollowJwtAuthInterceptor.java` | `service/follow/src/main/java/com/rag/follow/interceptor/FollowJwtAuthInterceptor.java` | 1:1 | 401 **空 body**；claim **原始对象**写进 `userId` attribute（**不转 long**） |
| `service/follow/api/internal/metrics/metrics.go`（3 个指标） | `follow-api/.../metrics/FollowApiMetrics.java` | `service/follow/src/main/java/com/rag/follow/metrics/FollowApiMetrics.java` | 1:1 | 指标名 L1：`follow_api_request_total` / `follow_api_reject_total` / `follow_api_request_duration_seconds` |

### 1.3 API 层业务用例：Go 8 个 logic → B 8 个 Service → C **2 个 Service** ★

> **这是本域最需要单列的一段** —— C 列的 api 层用例**不合并成 1 个**，而是由「2 个带专属业务规则的读用例」把 8 个用例劈成 2 组。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/follow/api/internal/logic/follow/followlogic.go` | `follow-api/.../service/FollowService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowRelationService.java` | 1:1 | **写用例组**（4 个）；`route` 常量 `/follow/v1/follow` |
| `service/follow/api/internal/logic/follow/unfollowlogic.go` | `follow-api/.../service/UnfollowService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowRelationService.java` | N:1 | 合并进 `FollowRelationService.unfollow` |
| `service/follow/api/internal/logic/follow/blocklogic.go` | `follow-api/.../service/BlockService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowRelationService.java` | N:1 | 合并进 `FollowRelationService.block` |
| `service/follow/api/internal/logic/follow/unblocklogic.go` | `follow-api/.../service/UnblockService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowRelationService.java` | N:1 | 合并进 `FollowRelationService.unblock` |
| `service/follow/api/internal/logic/follow/getfollowlistlogic.go` | `follow-api/.../service/GetFollowListService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowQueryService.java` | 1:1 | **读用例组**（4 个）；**形状 B**：`resolveUserID` 后**无** `forbidden_scope` 检查 ⇒ 可查任意人的关注列表 |
| `service/follow/api/internal/logic/follow/getfollowerlistlogic.go` | `follow-api/.../service/GetFollowerListService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowQueryService.java` | N:1 | **形状 B**；合并后**必须用类 javadoc 显式标注「此处故意没有 scope 检查」** |
| `service/follow/api/internal/logic/follow/getblocklistlogic.go` | `follow-api/.../service/GetBlockListService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowQueryService.java` | N:1 | **形状 C**：`forbidden_scope` 检查（`ObserveReject(route,"forbidden_scope")` + 5005） |
| `service/follow/api/internal/logic/follow/getrecommendationslogic.go` | `follow-api/.../service/GetRecommendationsService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowQueryService.java` | N:1 | **形状 C** + 嵌套 message 投影（`:70-81`，跳过 nil 元素） |
| **8 个 logic 合计** | **8 个单端点 Service（另有 `FollowApiSupport` + `AbstractFollowApiService` 两个支撑类，共 10 个 `.java`）** | **2 个 Service（`FollowRelationService` 4 写 + `FollowQueryService` 4 读）** | **N:1** | ★ **C 的 api 层用例合并**：4 写 → 1 类、4 读 → 1 类。**拆分理由**：2 个读用例带专属业务规则（`forbidden_scope` 私密性约束 + proto→DTO 列表转换）⇒《完全spring化的文件树》§5.4 判据 ⑤「合并后职责不模糊」**失败** ⇒ 不合并成 1 个 |
| `service/follow/api/internal/logic/follow/helper.go`（4 个函数：`extractUserID` / `userLogOption` / `codeFromRPCError` / `resolveUserID`） | `follow-api/.../service/FollowApiSupport.java` + `follow-api/.../service/AbstractFollowApiService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowApiSupport.java` | 1:N | 🔴 `extractUserID` **接受 5 种类型**（`json.Number`/`string`/`int64`/`int`/`float64`）⇒ **不能照抄 like 域的实现**；B 把 8 个 logic 重复的 3 段样板收进基类，C 只有 2 个 Service ⇒ **基类被吸收** |

### 1.4 RPC 层（进程装配 · 分发 · model · logic · 指标）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/follow/rpc/follow.go`（进程装配） | `follow-rpc/.../FollowRpcApplication.java` | `service/follow/src/main/java/com/rag/follow/FollowServiceApplication.java` | 1:1 | **D1** ⇒ api / rpc 两个入口合并成一个 |
| `service/follow/rpc/etc/follow.yaml` | `follow-rpc/src/main/resources/application.yml` | `service/follow/src/main/resources/application.yml` | 1:1 | `List` 与 `Recommend` **两段配置必须分开**（3 个 list 与 1 个 recommend 取值不同） |
| `service/follow/rpc/internal/config/config.go`（`Postgres`+`UserRpc`+`List`+`Recommend`） | `follow-rpc/.../config/FollowRpcProperties.java` | `service/follow/src/main/java/com/rag/follow/config/FollowRpcProperties.java` | 1:1 | `List{DefaultLimit:20,MaxLimit:100}`；`Recommend{DefaultLimit:20,MaxLimit:50,MaxDepth:3,MaxFanout:50}` |
| `service/follow/rpc/internal/svc/servicecontext.go`（`InitDB` + `NewFollowModel` + `UserRpc`） | `follow-rpc/.../bootstrap/NacosRegistration.java` + `follow-rpc/.../client/UserRpcClient.java` + `follow-rpc/src/main/resources/application.yml` + `db/migration/V1,V2` | `service/follow/src/main/java/com/rag/follow/bootstrap/NacosRegistration.java` + `service/follow/src/main/java/com/rag/follow/client/UserRpcClient.java` + `service/follow/src/main/resources/application.yml` + `db/migration/`（**仅 `.gitkeep`**) | 1:N | `ServiceContext` 被 DI 吸收；⚠️ **C 的 DDL 落点缺失** ⇒ 见 §7 缺口 #1 |
| `service/follow/rpc/internal/server/followserviceserver.go`（8 个方法纯分发） | `follow-rpc/.../service/impl/FollowServiceGrpcImpl.java` | `service/follow/src/main/java/com/rag/follow/grpc/FollowGrpcService.java` | 1:1 | `@GrpcService` thin wrapper + 错误兜底 |
| `service/follow/rpc/followservice/followservice.go`（8 方法客户端接口 + 6 个类型别名） | （**无手写类**；用 `follow-proto` 生成的 `FollowServiceGrpc.FollowServiceBlockingStub`） | （**无手写类**；用根级 `proto/` 生成的 `FollowServiceGrpc`） | 1:1 | Go 侧它是**被 api 消费**的客户端；**D2** 后域内不再走网络 ⇒ 两侧都只剩生成物 |
| `service/follow/rpc/internal/metrics/metrics.go`（5 个指标） | `follow-rpc/.../metrics/FollowRpcMetrics.java` | `service/follow/src/main/java/com/rag/follow/metrics/FollowRpcMetrics.java` | 1:1 | 指标名 L1：`follow_rpc_request_total` / `follow_rpc_request_duration_seconds` / `follow_rpc_relation_action_total` / `follow_rpc_db_error_total` / `follow_rpc_recommendation_size`（**Gauge，`Set` 而非 `Inc`**） |
| `service/follow/rpc/internal/model/data_model.go`（2 个 GORM struct） | `follow-rpc/.../domain/FollowRelationEntity.java` + `.../domain/BlockRelationEntity.java` | `service/follow/src/main/java/com/rag/follow/domain/entity/FollowRelationEntity.java` + `service/follow/src/main/java/com/rag/follow/domain/entity/BlockRelationEntity.java` | 1:N | 🔴 主键是 **GORM 整数 PK 默认 AUTOINCREMENT ⇒ bigserial**，**不是雪花** ⇒ 必须显式 `IdType.AUTO`；`create_time`/`update_time` **只配 INSERT fill**（Go 侧无任何 UPDATE ⇒ 加 UPDATE fill 就是新增行为） |
| `service/follow/rpc/internal/model/follow.go`（10 个持久化方法） | `follow-rpc/.../mapper/FollowRelationMapper.java` + `.../mapper/BlockRelationMapper.java` + `.../mapper/GoStyleLimitOffset.java` | `service/follow/src/main/java/com/rag/follow/mapper/FollowRelationMapper.java` + `service/follow/src/main/java/com/rag/follow/mapper/BlockRelationMapper.java` + `service/follow/src/main/java/com/rag/follow/mapper/GoStyleLimitOffset.java` | 1:N | 10 方法 → 2 Mapper + 1 个 LIMIT/OFFSET 片段类；🔴 `existsAnyDirection` 是**双向 OR**（单向即实质漂移）；`ORDER BY create_time DESC` **无次键**（Go 行为，保留） |
| `service/follow/rpc/internal/model/db.go`（DSN + 池 10/100 + `AutoMigrate` + 2 处 `log.Fatalf`） | `follow-rpc/src/main/resources/application.yml`（Hikari + `TimeZone=Asia/Shanghai`）+ `db/migration/V1__create_follow_relation.sql` + `V2__create_block_relation.sql` | `service/follow/src/main/resources/application.yml`（datasource）+ `db/migration/`（**仅 `.gitkeep`**） | 1:N | ⚠️ **C 缺 Flyway 脚本 + 缺 `flyway-core` 依赖** ⇒ 两张表**无建表落点**；Go 的 fail-fast 语义（`log.Fatalf`）由「Flyway 迁移失败即启动失败」等价满足 ⇒ **不需要** `FollowSchemaGate` |
| `service/follow/rpc/internal/model/vars.go`（`ErrorNotFound`） | — | — | — | **Go 侧无对应物**：`ErrorNotFound` 实测**零调用点**，且与 `common/errmsg.go:39` 的 `ErrorNotFound(5002)` **同名不同物** ⇒ 不迁（B、C 两侧均无） |
| `service/follow/rpc/internal/logic/helper.go`（11 个符号：归一 + 3 个成功工厂 + `ensureUserExists` + BFS 打分工具） | `follow-rpc/.../service/FollowRpcSupport.java` + `follow-rpc/.../service/FollowUserExistenceService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowRpcSupport.java` + `service/follow/src/main/java/com/rag/follow/service/FollowUserExistenceService.java` | 1:N | 🔴 `ensureUserExists` **独立成 Bean**：含出网 gRPC 调用 + 独立错误映射（NotFound→5101 / 其它→5001），与纯归一规则不同源；🔴 Java protobuf 的 `getUser()` **永不返回 null** ⇒ 必须用 `hasUser()` |
| `service/follow/rpc/internal/logic/followlogic.go` | `follow-rpc/.../service/FollowDomainService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` | 1:1 | `validate → ensureUserExists(target) → ExistsAnyBlock → CreateFollow` |
| `service/follow/rpc/internal/logic/unfollowlogic.go` | `follow-rpc/.../service/FollowDomainService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` | N:1 | 8 个 rpc logic 合 1 个 `FollowDomainService`（8 方法） |
| `service/follow/rpc/internal/logic/blocklogic.go` | `follow-rpc/.../service/FollowDomainService.java` + `follow-rpc/.../service/FollowBlockTransactionService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` + `service/follow/src/main/java/com/rag/follow/service/FollowBlockTransactionService.java` | 1:N | 🔴 **事务边界必须独立成 Bean**：`@Transactional` 走代理才生效（自调用 ⇒ 事务静默失效 ⇒ 「插 block + 双向删 follow」变成两次独立提交） |
| `service/follow/rpc/internal/logic/unblocklogic.go` | `follow-rpc/.../service/FollowDomainService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` | N:1 | |
| `service/follow/rpc/internal/logic/getfollowlistlogic.go` | `follow-rpc/.../service/FollowDomainService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` | N:1 | |
| `service/follow/rpc/internal/logic/getfollowerlistlogic.go` | `follow-rpc/.../service/FollowDomainService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` | N:1 | |
| `service/follow/rpc/internal/logic/getblocklistlogic.go` | `follow-rpc/.../service/FollowDomainService.java` | `service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java` | N:1 | |
| `service/follow/rpc/internal/logic/getrecommendationslogic.go`（152 行：BFS + 打分 + 排序 + 分页 + 3 次提前返回） | `follow-rpc/.../service/FollowRecommendationService.java` | `service/follow/src/main/java/com/rag/follow/service/FollowRecommendationService.java` | 1:1 | 🔴 **独立变化原因**：调参（depth / fanout / weight）只动这一个类；`scoreWeight` / `clampRecommendDepth` / `clampFanout` 都在这里 |
| —（Go 侧无对应物） | `follow-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` + `follow-rpc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | `common/src/main/java/com/rag/common/autoconfigure/RagMybatisPlusAutoConfiguration.java` | 1:1 | **Go 侧无对应物（原因：Boot 4 的 MyBatis-Plus 自动配置排序 workaround，非 Go 语义）**；B 域内自带一份 ⇒ **D3** 后 C 收进全局 starter（9 份重复 ⇒ 1 份） |

### 1.5 决策锁（§1）

**换「api + rpc 两套包 → 一套包、一进程」（D1）6 处同时改**：
1. `follow-api/FollowApiApplication.java` 与 `follow-rpc/FollowRpcApplication.java` 合并为 `service/follow/src/main/java/com/rag/follow/FollowServiceApplication.java`（**一个** `@SpringBootApplication` + `@EnableDiscoveryClient`）。
2. `follow-api` 的 `client/FollowRpcClient` **删除**（D2：域内直连 `domain/service/FollowDomainService.java`）。
3. 两套 `application.yml` 合并为一个文件：`server.port`（HTTP）+ `grpc.server.port`（gRPC）两段并存。
4. `follow-common` 模块改名为 `follow-contract`，并把 proto 源移入 ⇒ **proto 与错误码契约同模块**。
5. 两套 `*Properties`（`FollowApiProperties` / `FollowRpcProperties`）保留为**两个类**（配置关注点不同），但同进程加载 ⇒ 两段前缀不得互相覆盖。
6. Nacos 注册项从 2 个（`followcenter` + `follow.rpc`）减为 1 个 ⇒ `NacosRegistration` 的注册名要重新定（Go 侧 rpc 注册名是 `follow.rpc`）。

**为什么换**：Spring Cloud 的部署单元是「可部署服务」；go-zero 的 api/rpc 分离源于 `.api` / `.proto` 两条 codegen 路径，是**语言产物**不是架构原则 ⇒ 合并后「rpc 进程不可用」这一故障模式消失。

**换「域内 gRPC 往返 → 进程内直连」（D2）4 处同时改**：
1. `follow-api` 的 8 个 Service 里 `FollowRpcClient` 的调用点改为直调 `domain/service/FollowDomainService.java`。
2. **删除** `follow-api/.../client/FollowRpcClient.java`。
3. api 层**不再需要**「gRPC code → 业务码」映射（`FollowApiSupport.rpcError` / `codeFromRPCError`）⇒ 该路径在 C 列**变成死代码**。
4. gRPC 侧错误仍需从 `BusinessException(code,msg)` 经 `common/src/main/java/com/rag/common/autoconfigure/RagGrpcExceptionMapperAutoConfiguration` 转 `StatusRuntimeException`（`code` 与 `msg` 逐字一致）。

**为什么换**：同进程内调 gRPC 是无意义的序列化开销与故障注入；合并后只有一份业务逻辑，因此业务层只抛一种异常，两个边界各自适配。

**换「8 个 api 用例 → 2 个 Service」2 处同时改**：
1. `follow-api/.../service/` 下 8 个单端点 Service 收成 2 个：`FollowRelationService`（4 写）+ `FollowQueryService`（4 读）。
2. `AbstractFollowApiService` 基类**吸收**：C 只有 2 个 Service，基类的「三段样板（`route` 常量 / 计时 / reject 埋点）」由 2 个类各自承担或下沉到 `FollowApiSupport`。

**为什么换**：`forbidden_scope` 这条专属业务规则只存在于 2 个读用例（`getblocklist` / `getrecommendations`），若 8 个用例合成 1 个类，这条规则会埋进大块（《完全spring化的文件树》§5.4 判据 ⑤ 失败）⇒ 劈成「4 纯转发 / 4 带规则」两组是最小改动。
⚠️ **代价**：8 个 `route` 指标字面量（`/follow/v1/follow` …）**不会因为合并而减少** ⇒ 每个方法仍须携带自己的 `route` 常量，否则指标标签会串。

---

## 2. 持久层差异

| 维度 | A Go（GORM） | B RAG（MyBatis-Plus + Flyway） | C RAG-V1（MyBatis-Plus，Spring 目标） | 映射类型 | 备注 |
|---|---|---|---|---|---|
| 实体 | `rpc/internal/model/data_model.go`（GORM struct，2 个） | `rpc/domain/{FollowRelationEntity,BlockRelationEntity}.java` | `service/follow/src/main/java/com/rag/follow/domain/entity/{FollowRelationEntity,BlockRelationEntity}.java` | 1:1 | `@TableName("follow_relation")` / `@TableName("block_relation")` |
| 数据访问 | `rpc/internal/model/follow.go`（10 个方法，链式调用） | `rpc/mapper/{FollowRelationMapper,BlockRelationMapper}.java`（`extends BaseMapper<E>` + 注解式原生 SQL） | 同左 | 1:1 | ⚠️ 全仓**零 XML mapper** ⇒ 注解式 |
| 主键策略 | `ID uint64 \`gorm:"primaryKey"\`` —— **无 `autoIncrement`、无雪花** ⇒ GORM 省略该列、DB 侧序列赋值 | `@TableId(type = IdType.AUTO)` + DDL `BIGSERIAL` | 待填（`@TableId` 注解未写） | 1:1 | 🔴 **必须显式 `IdType.AUTO`**：RAG 惯例 `mybatis-plus.global-config.db-config.id-type: ASSIGN_ID`（雪花）会引入 Go 侧不存在的 ID 生成器；**C 的 `application.yml` 目前也没有 `id-type` 段** ⇒ 见 §7 缺口 #3 |
| 时间戳 | `autoCreateTime` / `autoUpdateTime` | 只配 `FieldFill.INSERT`，**不配 UPDATE** | 同左 | 1:1 | ⚠️ Go 侧**零 UPDATE** ⇒ `update_time` 此后永不推进；加 UPDATE fill 就是**新增行为** |
| DDL 机制 | `rpc/internal/model/db.go:42` `db.AutoMigrate(&FollowRelation{}, &BlockRelation{})` | Flyway `V1__create_follow_relation.sql` + `V2__create_block_relation.sql`（幂等 `IF NOT EXISTS`） | `db/migration/` **仅 `.gitkeep`** + pom **无 `flyway-core`** | 1:N | ⚠️ **C 侧 DDL 无落点** ⇒ 两张表建不出来 |
| 账本隔离 | 不适用（GORM 无账本） | `spring.flyway.table: flyway_schema_history_follow`（独立表名） | 未配置 | 1:1 | 合并后每域一套迁移 ⇒ 冲突面变小，但表名隔离仍需保留 |
| DDL 类型 | GORM postgres：`int64 → bigint`、`time.Time → timestamptz`（**不是** `timestamp`） | `BIGINT` / `TIMESTAMPTZ`；`user_id`/`target_id` `NOT NULL`；`create_time`/`update_time` 可空 | 待填 | 1:1 | `TIMESTAMPTZ` 是 L1：`create_time` 是 `ORDER BY` 排序键 |
| 索引 | `gorm:"...;index"`（单列）+ `uniqueIndex:uk_follow_pair` / `uk_block_pair`（**复合唯一**） | `idx_follow_relation_user_id` / `idx_follow_relation_target_id` / `uk_follow_pair`（block 同构） | 待填 | 1:1 | 🔴 复合唯一索引**不是优化**：INSERT 走 `ON CONFLICT (user_id,target_id) DO NOTHING` ⇒ 删掉它 ⇒ 重复关注从「静默成功」变成 `5008 database update failed` |
| 分页 | `model/follow.go` 6 处 `if offset>0` / `if limit>0` **分段守卫** | `rpc/mapper/GoStyleLimitOffset.java`（**新建**，非复用 `message-rpc` 的 `GoStylePagination` —— 后者是包私有 `final class`） | `service/follow/src/main/java/com/rag/follow/mapper/GoStyleLimitOffset.java` | 1:1 | ⚠️ `normalizeListReq` 已在 service 层把 offset 夹到 ≥0、limit 夹到 ≥1 ⇒ mapper 层守卫几乎恒真 |
| 事务 | `blocklogic.go` 内 `CreateBlockAndCleanup` 单事务（GORM 默认） | `rpc/service/FollowBlockTransactionService.java`（`@Transactional`，**独立 Bean**） | `service/follow/src/main/java/com/rag/follow/service/FollowBlockTransactionService.java` | 1:1 | 🔴 独立 Bean 是 `@Transactional` 经代理生效的前提（自调用 ⇒ 静默失效） |
| 连接池 | `db.go:38-39` 池 10/100 | `application.yml` 的 `hikari`（同值） | `application.yml` 的 `spring.datasource.*` | 1:1 | |
| 会话时区 | `db.go:22` DSN 含 `TimeZone=Asia/Shanghai` | JDBC URL 显式带时区参数 | **未配置** | 1:1 | ⚠️ 不配 ⇒ JVM 默认时区 ⇒ `create_time` 字面量解释不同（与 `TIMESTAMP⇒TIMESTAMPTZ` 漂移同源） |
| schema fail-fast | `db.go:33,43` `log.Fatalf` | Flyway 迁移失败即启动失败 | 同左（前提是补上 Flyway） | 1:1 | ⇒ **不需要** `FollowSchemaGate`（follow 两张表无触发器 / 无 CHECK） |

### 2.1 决策锁（§2）

**换「GORM `AutoMigrate` → Flyway 版本化脚本」4 处同时改**：
1. `follow-rpc/pom.xml` 加 `flyway-core` + `flyway-database-postgresql`（版本由 BOM 管理，不写 `<version>`）。
2. `db/migration/` 下补 `V1__create_follow_relation.sql` + `V2__create_block_relation.sql`（逐列对齐 GORM 产物：`BIGSERIAL` PK / `BIGINT NOT NULL` / `TIMESTAMPTZ` 可空 / 2 个单列索引 / 1 个复合唯一索引）。
3. `application.yml` 配 `spring.flyway.table: flyway_schema_history_follow`（**独立账本表名**，避免与其它域同库同 schema 撞号）。
4. `application.yml` 的 JDBC URL 补 `?options=-c%20TimeZone%3DAsia%2FShanghai`（对齐 Go DSN 的 `TimeZone=Asia/Shanghai`）。

**为什么换**：GORM `AutoMigrate` 不可审计、不可回滚、不可多模块共存；Flyway 显式管 schema 历史，且「迁移失败即启动失败」天然等价于 Go 的 `log.Fatalf` fail-fast。

---

## 3. HTTP / DI 差异

| 维度 | A Go（go-zero） | B RAG | C RAG-V1（Spring 目标） | 映射类型 | 备注 |
|---|---|---|---|---|---|
| 路由注册 | `rest.WithPrefix("/follow/v1")` + 8 条 `server.AddRoutes`（`routes.go:16-64`） | `@RequestMapping("/follow/v1")` + 8 个 `@PostMapping` | 同左 | 1:1 | 8/8 全 POST；路径**逐字**保留 |
| Handler / Controller | 8 个 `handler/follow/*.go`（每个 31 行，逐字同质） | 1 个 `controller/FollowController.java`（8 个 `@PostMapping`） | 1 个 `service/follow/src/main/java/com/rag/follow/controller/FollowController.java` | N:1 | handler 不做任何校验 ⇒ 必填 / `,string` 语义全在反序列化层 |
| 中间件链 | 3 层装饰器：`WithMiddlewares(黑名单)` → `WithJwt` → `WithPrefix` | `WebMvcConfig.addInterceptors(JWT).addInterceptors(Blacklist)` | `service/follow/src/main/java/com/rag/follow/config/WebMvcConfig.java`（注册 2 个拦截器） | 1:1 | 🔴 **注册顺序即执行顺序**：JWT 必须在前 ⇒ 验签失败时黑名单**从不被查**（Go 也不为畸形 token 产生 Redis 读） |
| 鉴权实现 | `rest.WithJwt`（go-zero 内置） | `interceptor/FollowJwtAuthInterceptor.java`（`HandlerInterceptor`） | `service/follow/src/main/java/com/rag/follow/interceptor/FollowJwtAuthInterceptor.java` | 1:1 | 401 **空 body** |
| 黑名单实现 | `middleware/checkblacklistmiddleware.go`（55 行，Redis `ExistsCtx`） | `interceptor/FollowBlacklistInterceptor.java` | `service/follow/src/main/java/com/rag/follow/interceptor/FollowBlacklistInterceptor.java` | 1:1 | 🔴 **fail-open**（`err != nil` ⇒ 放行）；401 + `Content-Type: application/json;charset=utf-8` + body `{"code":5004,"msg":"unauthorized"}`（**无 `data` 字段**） |
| 两种 401 形态 | ① `rest.WithJwt` 失败 ⇒ **空 body**；② 黑名单命中 ⇒ **JSON body** | `GlobalExceptionHandler`（①） + 拦截器直写（②） | `service/follow/src/main/java/com/rag/follow/advice/FollowApiExceptionHandler.java`（①） + 拦截器直写（②） | 1:1 | 🔴 **必须分两条路**：若黑名单 401 也抛 `AuthenticationException` ⇒ 变成空 body ⇒ 丢 `Content-Type` 与 code/msg |
| Service 分层 | 8 个 `logic/follow/*.go`（goctl 产物） | 8 个单端点 Service + 1 基类 | 2 个 Service（`FollowRelationService` / `FollowQueryService`） | N:1 | ★ 见 §1.3 |
| 错误表达 | `httpx.Error` ⇒ **HTTP 200** + `{code,msg,data}` 信封；`httpx.OkJsonCtx` ⇒ `{code:200,msg:"OK",data}` | `throw BusinessException` + `advice/GlobalResponseAdvice` + `advice/GlobalExceptionHandler` | `service/follow/src/main/java/com/rag/follow/advice/FollowApiResponseAdvice.java` + `service/follow/src/main/java/com/rag/follow/advice/FollowApiExceptionHandler.java` | 1:1 | HTTP 状态码**恒 200**（401 除外）⇒ 成败只体现在 body 的 `code` |
| 身份解析 | `helper.go:13-33` `extractUserID`：**5 种类型全收**（`json.Number`/`string`/`int64`/`int`/`float64`） | `service/FollowApiSupport.userId`（逐条照抄 5 分支） | `service/follow/src/main/java/com/rag/follow/service/FollowApiSupport.java` | 1:1 | 🔴 **不能照抄 like 域**（like 只收 `json.Number`）⇒ 字符串 claim 与浮点 claim 在 follow 是**合法**输入 |
| 身份兜底 | `helper.go:43-48` `resolveUserID` = `requested > 0 ? requested : current` | `FollowApiSupport.resolveUserId` | `service/follow/src/main/java/com/rag/follow/service/FollowApiSupport.java` | 1:1 | 「不传就查自己」 |
| 配置绑定 | `internal/config/config.go`（4 段 struct tag，`json:",default=20"` 等） | `@ConfigurationProperties(prefix="follow-api"/"follow-rpc")` + nested 类 | `service/follow/src/main/java/com/rag/follow/config/FollowApiProperties.java` + `service/follow/src/main/java/com/rag/follow/config/FollowRpcProperties.java` | 1:1 | ⚠️ `RestConf` 的 9 个中间件开关**全部 `default=true`**（yaml 里一个字没写）⇒ 目标侧必须**显式写出**，不能依赖默认 |
| 进程装配 | `api/followcenter.go` + `rpc/follow.go`（2 个 main） | `FollowApiApplication` + `FollowRpcApplication`（2 个 app） | `FollowServiceApplication`（**1 个 app**） | 1:1 | **D1** |
| 跨域调用 | `svcCtx.UserRpc.GetUser`（rpc 侧）+ `svcCtx.FollowRpc.*`（api 侧调同域） | `rpc/client/UserRpcClient.java`（`@GrpcClient`）+ `api/client/FollowRpcClient.java`（同域） | `service/follow/src/main/java/com/rag/follow/client/UserRpcClient.java`（**只保留跨域**） | 1:N | **D2** ⇒ 同域 client 删除；**D6** ⇒ 跨域仍走 gRPC，proto 是 L1 |
| 熔断 / 降载 / 限连接 | go-zero `rest` 内置 9 个开关（默认全开） | `service/common/protection/`（17 个自研类） | `common/src/main/java/com/rag/common/autoconfigure/RagProtectionAutoConfiguration.java` + `common/src/main/java/com/rag/common/protection/`（2 类） | 1:N | **D4**：生态吸收 ⇒ L3 语义降级（熔断失败窗口 / half-open 策略不同） |
|请求体解析|`httpx.Parse`（go-zero mapping：必填 + `,string` + 类型强转）|`common/http/GoStyleStringInt64` / `GoStyleRequired` / `GoStyleCoercion` + 域内 `FollowGoStyleCoercion`|`common/src/main/java/com/rag/common/web/GoStyleStringInt64.java` / `GoStyleCoercion` + `common/src/main/java/com/rag/common/autoconfigure/RagJacksonAutoConfiguration.java`（**无域内补强**）|1:N|⚠️ C 的域内补强缺失 ⇒ 见 §7 缺口 #2|
| DTO 形态 | `types/types.go`（可变 struct，5 组） | `dto/*.java`（可变 POJO） | `service/follow/src/main/java/com/rag/follow/dto/*.java`（**immutable record**，D5） | 1:1 | ⚠️ 契约不变（L1），形态变；必填校验文案从 go-zero 改为 Bean Validation 文案 ⇒ **L3 降级** |
| 指标注册 | `metrics.InitMetrics()`（**真的被调用**：`followcenter.go:33` / `follow.go:28`） | Micrometer 声明即注册 | Micrometer 声明即注册 | 1:1 | ⇒ 自动注册是**对齐**而非增强（与 like 域「零调用」的结论相反） |

### 3.1 决策锁（§3）

**换「go-zero 路由 / 中间件 → Spring MVC 注解 + Interceptor」5 处同时改**：
1. `routes.go` 的 8 条 `AddRoutes` → `FollowController` 的 8 个 `@PostMapping`（类级 `@RequestMapping("/follow/v1")`）。
2. `rest.WithJwt` → `FollowJwtAuthInterceptor`（**注册在第 1 位**）。
3. `rest.WithMiddlewares(黑名单)` → `FollowBlacklistInterceptor`（**注册在第 2 位**）。
4. `rest.WithPrefix("/follow/v1")` → 类级 `@RequestMapping`。
5. **public 路径集为空** ⇒ **不加** `excludePathPatterns`（8/8 全量鉴权）。

**为什么换**：go-zero 代码注册路由 vs Spring 注解声明式路由；拦截器是 Spring MVC Handler 层，能访问 `HandlerMethod`，比 Servlet Filter 更贴合「路由级中间件」的语义。

**换「go-zero `httpx.Parse` → Bean Validation + Jackson 原生强转」4 处同时改**：
1. `RelationActionReq.target_id` 的必填性 → `@NotNull` + `GoStyleRequired` 双保险（**必填性是 L1**，文案是 L3 降级）。
2. `FollowListReq` 的 3 个 `optional` 字段 → 不加 `@NotNull`，缺失时取 Go 零值。
3. `,string` 字段（`user_id` / `target_id`）→ `GoStyleStringInt64` 解析（**接受 JSON 字符串与数字两者**）。
4. `offset` / `limit` 的「数字形态」判定 → **C 缺落点**（B 用 `FollowGoStyleCoercion` 补 1 条 `CoercionInputShape.String → Fail`）。

**为什么换**：Jackson 的 `CoercionConfig` 是原生机制（无需自研），但**「何时读 body」与「错误文案形状」不是** ⇒ 仍需保留 `GoStyleJsonBodyConverter` 与 `GoStyleRouterErrorResponses`。

**换「ServiceContext（static 全局）→ Spring Bean」3 处同时改**：
1. 删 `api/internal/svc/servicecontext.go` 与 `rpc/internal/svc/servicecontext.go` 的等价物（B 侧已无 `ServiceContext.java`）。
2. 每个 static 字段对应一个 `@Component` / `@Bean`（`NacosRegistration` / `UserRpcClient` / 两个 `*Properties` / 两个 `*Mapper`）。
3. 调用点从 `svcCtx.Xxx` 改为构造器注入。

**为什么换**：static 全局变量在测试和多进程时难处理；Spring Bean 单例 + 依赖注入更标准，且让 `@Transactional` 能经代理生效。

---

## 4. 公共模块对照

> ⚠️ **两个 `common` 不是同一个东西**：
> - **域内 `contract/` 包** = **follow 域专属**的码表库（错误码 + 文案 + gRPC 往返映射），与 `service/follow/common/errmsg.go` 对位。
> - **全局 `service/common` → `common/`** = **跨服务共享层**（信封 / 异常 / 日志 / 保护 / 持久化 / JWT），与 Go 侧 `service/common/` 对位。

| 职责 | A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|---|
| 域内错误码表 + 文案 + gRPC 往返 | `service/follow/common/errmsg.go`（1 个文件） | `service/follow/follow-common/.../errmsg/`（4 个类） | `service/follow/src/main/java/com/rag/follow/contract/`（4 个类） | 1:1 | 🔴 **逐域隔离**：follow 的文案是**英文**（`"service busy"`），与 article/comment/favorite/like/user 的**中文**文案不同 ⇒ **禁止**为「统一」而合并成共享基座 |
| 错误码 ↔ 文案同码不同义 | 同上 | 同上 | 同上 | 1:1 | 5002/5003/5004/5007 在 follow 与 users / like / article **语义部分相同、部分相反** ⇒ follow 必须自持码表 |
| 业务异常载体 | 无（Go 用 `*CodeError` 结构体 / `status.Error`） | `service/common/.../exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | 1:1 | `BusinessException` 只有 `getCode()` / `getMessage()` ⇒ **不能**当 `CodeError` 用（缺 `getMsg()`，且 msg 可与码表文案不同） |
| 认证异常载体 | 无（go-zero `rest.WithJwt` 直接写 401） | `service/common/.../exception/AuthenticationException.java` | `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | 1:1 | 401 **空 body** 的载体 |
| HTTP 信封 | `service/common/response/response.go` | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | 信封是 **L1**（客户端按 `{code,msg,data}` 解析）⇒ **不得**用 `ProblemDetail` 替换 |
| JWT 工具 | go-zero `rest.WithJwt` 内置 | `users/users-common/.../jwt/{JwtUtil,BearerTokenExtractor}.java` | `common/src/main/java/com/rag/common/security/{JwtUtil,BearerTokenExtractor}.java` | 1:1 | 从 users 域**提升为全局**；🔴 `trimBearerPrefix`（大小写敏感）**不含** `TrimSpace` ⇒ follow 黑名单需额外 `.trim()` |
| 字符串 int64 解析 | go-zero `,string` mapping | `users/users-common/.../http/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/GoStyleStringInt64.java` | 1:1 | 接受 JSON 字符串与数字两者 |
| Jackson 强转规则 | go-zero `mapping` 强转判定 | `users/users-common/.../http/GoStyleCoercion.java` + `follow-api/.../config/FollowGoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/GoStyleCoercion.java` + `common/src/main/java/com/rag/common/autoconfigure/RagJacksonAutoConfiguration.java` | 1:N | C **没有**域内补强类 ⇒ 缺口 #2 |
| 裸响应（404/405/415） | go-zero `rest` 内置 | `service/common/.../http/GoStyleRouterErrorResponses.java` | `common/src/main/java/com/rag/common/web/GoStyleRouterErrorResponses.java` | 1:1 | |
| 请求体读取判据 | go-zero `withJsonBody`（`ContentLength>0` + Content-Type 字面含 `application/json`） | `service/common/.../http/GoStyleJsonBodyConverter.java` | `common/src/main/java/com/rag/common/web/GoStyleJsonBodyConverter.java` | 1:1 | 2 行判定，非框架能力 |
| 日志适配 | `service/common/logger/logger.go` | `service/common/.../logger/{Logger,WithUserID}.java` | `common/src/main/java/com/rag/common/logger/{GoStyleLogger,LogOption,LogOptions,FatalError}.java` | 1:1 | 实现 SLF4J `Logger` 接口；`call_chain` 等字段是行为契约 |
| gRPC deadline | go-zero `zrpc` 内置超时 | `service/common/.../observability/GoStyleRequestDeadline.java` | `common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java` | 1:1 | |
| SQL 超时 / 取消 | go-zero `ctx cancel` | `service/common/.../persistence/{DeadlineQueryTimeoutInjector,SqlTimeoutStatusTranslator}.java` | `common/src/main/java/com/rag/common/persistence/`（同 2 类 + 2 个 typeHandler） | 1:1 | |
| 保护层（熔断/降载/限连接/限体积） | go-zero `rest` 内置 9 个开关 | `service/common/.../protection/`（17 个类） | `common/src/main/java/com/rag/common/protection/{CpuThresholdHealthIndicator,MaxBytesFilter}.java` + `common/src/main/java/com/rag/common/autoconfigure/RagProtectionAutoConfiguration.java` | N:1 | **D4**：17 → 2 类 + 1 个 auto-config ⇒ L3 语义降级 |
| MyBatis-Plus 装配桥 | 无（Go 用 GORM） | 每个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration`（follow-rpc 亦有） | `common/src/main/java/com/rag/common/autoconfigure/RagMybatisPlusAutoConfiguration.java`（1 份） | N:1 | **D3**：9 份重复 ⇒ 1 份；🔴 不建则 mapper 直接报 `Property 'sqlSessionFactory' …` |
| gRPC 异常映射 | 无（Go 两侧各写一遍） | 无（api 层自己 switch） | `common/src/main/java/com/rag/common/autoconfigure/RagGrpcExceptionMapperAutoConfiguration.java` | C 新增 | **D2** 的关键设计：业务层抛 `BusinessException(code,msg)`，gRPC 边界统一转 `StatusRuntimeException`（`code` + `msg` 逐字一致） |
| proto 契约 | `proto/follow.proto`（仓库根） | `service/follow/follow-proto/src/main/proto/follow.proto` | `proto/src/main/proto/follow.proto` | 1:1 | 8 个 message / 8 个 rpc / 全部字段号**逐条一致**；只改 `java_package` |

### 4.1 决策锁（§4）

**换「域内 `follow-common` → `follow-contract`（含 proto）」4 处同时改**：
1. 模块目录 `service/follow/follow-common/` → `service/follow/src/main/java/com/rag/follow/contract/`，`pom.xml` 的 `artifactId` 同步改。
2. 包名 `com.rag.follow.follow.common.errmsg` → `com.rag.follow.contract`（4 个类：`FollowCodeError`→`CodeError`、`FollowErrorCode`→`ErrCodes`、`FollowErrorMessages`→`ErrMsg`、`FollowGrpcError`→`GrpcErrorMapper`）。
3. `follow.proto` 移入该模块，`option java_package` 改 `com.rag.follow.contract.proto`；**`option go_package` 删**。
4. `service/follow/pom.xml` 依赖 `com.rag:follow-contract`（**替换** `follow-common` + `follow-proto` 两个依赖）。

**为什么换**：proto 是 L1 契约、错误码空间也是 L1 契约 ⇒ 两者放进同一个「契约模块」最不容易被误当成可复用件；且错误契约**逐域隔离**这条硬约束在包名上就能被看见。

**换「`service/common` → 仓库根 `common` 真 starter」5 处同时改**：
1. `common/` 移到**仓库根**（不在 `service/` 下），`pom.xml` 增 `<module>common</module>`。
2. 各域的 `@Import{RpcServerInterceptorFactory, ...}` 清单**全删** ⇒ 由 `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 承担。
3. 9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration` ⇒ 收成 `common/src/main/java/com/rag/common/autoconfigure/RagMybatisPlusAutoConfiguration.java` **1 份**。
4. `users-common` 的 `jwt/` → `common/src/main/java/com/rag/common/security/`；`http/` → `common/src/main/java/com/rag/common/web/`；`exception/` → `common/src/main/java/com/rag/common/exception/`。
5. `common/src/main/java/com/rag/common/contract/ApiResponse.java`（全局信封）与**域内** `service/follow/src/main/java/com/rag/follow/contract/CodeError.java`（域内码表）**分层**：信封是全局的，错误码空间是域内的。

**为什么换**：Spring 生态里共享能力层的标准形态是 starter，不是「普通 jar + 每个消费者手写 `@Import`」⇒ 消除 10 份 `@Import` 清单与 9 份重复桥。

---

## 5. 决策锁

> **本节是 §1.5 / §2.1 / §3.1 / §4.1 四处决策锁的索引 + 汇总清单** —— 复刻到下一个域时按这张清单逐条改，不漏。
> 每条决策锁的固定格式是「**换 XXX（N 处同时改）**」+「**为什么换**」。

### 5.1 决策锁汇总（照 §1.5 / §2.1 / §3.1 / §4.1 逐条汇总）

| # | 决策锁 | 改动处数 | 所在小节 | 为什么换（一句话） |
|---|---|---|---|---|
| L1 | 换「api + rpc 两套包 → 一套包、一进程」（D1） | 6 处 | §1.5 | Spring Cloud 的部署单元是「可部署服务」；go-zero 的 api/rpc 分离是 codegen 路径的产物，不是架构原则 |
| L2 | 换「域内 gRPC 往返 → 进程内直连」（D2） | 4 处 | §1.5 | 同进程内调 gRPC 是无意义的序列化开销与故障注入 |
| L3 | 换「8 个 api 用例 → 2 个 Service」 | 2 处 | §1.5 | 2 个读用例带专属业务规则（`forbidden_scope`）⇒ 判据 ⑤「合并后职责不模糊」失败 |
| L4 | 换「GORM `AutoMigrate` → Flyway 版本化脚本」 | 4 处 | §2.1 | `AutoMigrate` 不可审计 / 不可回滚 / 不可多模块共存；「迁移失败即启动失败」等价于 Go 的 `log.Fatalf` |
| L5 | 换「go-zero 路由 / 中间件 → Spring MVC 注解 + Interceptor」 | 5 处 | §3.1 | go-zero 代码注册路由 vs Spring 注解声明式路由；Interceptor 能访问 `HandlerMethod` |
| L6 | 换「go-zero `httpx.Parse` → Bean Validation + Jackson 原生强转」 | 4 处 | §3.1 | Jackson `CoercionConfig` 是原生机制，但「何时读 body」与「错误文案形状」不是 |
| L7 | 换「ServiceContext（static 全局）→ Spring Bean」 | 3 处 | §3.1 | static 全局变量在测试 / 多进程时难处理；Spring Bean 还能让 `@Transactional` 经代理生效 |
| L8 | 换「域内 `follow-common` → `follow-contract`（含 proto）」 | 4 处 | §4.1 | proto 与错误码空间都是 L1 契约 ⇒ 放同一个「契约模块」，且「逐域隔离」的约束在包名上可见 |
| L9 | 换「`service/common` → 仓库根 `common` 真 starter」 | 5 处 | §4.1 | 共享能力层的 Spring 标准形态是 starter，不是「普通 jar + 每个消费者手写 `@Import`」 |

> ⚠️ **follow 域专属的一条**：**错误文案是英文**（`"service busy"` / `"internal server error"` …）⇒
> **L8 的改名与 L9 的归位都不得顺手把 follow 的码表并进全局基座** —— follow 的 5002/5003/5004/5007 与
> users / like / article 的同号码**语义部分相同、部分相反**（`service/follow/common/errmsg.go:33-74` vs 各域 `errmsg.go`）。

---

## 6. 跨模块全局约定（值逐条核对 A 列 Go 源码）

### 6.1 跨域共享的 L1 约定（值逐条核对 A 列 Go 源码）

> ⚠️ **只登记 A 列 Go 源码里能逐字定位的值**；C 列的对应值一律**逐字照抄**，不得「顺手统一」。

| 项 | 值（逐条核对 A 列） | A 列出处 |
|---|---|---|
| proto 字段号 | `RelationReq{user_id=1, target_id=2}`；`BaseResp{code=1, msg=2}`；`ListReq{user_id=1, offset=2, limit=3}`；`UserListResp{code=1, msg=2, user_ids=3}`；`RecommendResp{code=1, msg=2, users=3}`；`RecommendResp.RecommendUser{target_id=1, mutual_score=2}` | `proto/follow.proto` |
| proto RPC 签名 | `Follow/Unfollow/Block/Unblock(RelationReq) → BaseResp`；`GetFollowList/GetBlockList/GetFollowerList(ListReq) → UserListResp`；`GetRecommendations(ListReq) → RecommendResp`（8 个，名字逐字） | `proto/follow.proto` |
| proto 包名 | `package follow;`（Go / RAG / RAG-V1 三侧一致） | `proto/follow.proto` |
| 错误码值 | `Success=200`、`Error=500`、`CodeServerBusy=1015`、`ErrorServerCommon=5001`、`ErrorNotFound=5002`、`ErrorInvalidParam=5003`、`ErrorUnauthorized=5004`、`ErrorForbidden=5005`、`ErrorAlreadyExist=5006`、`ErrorDbSelect=5007`、`ErrorDbUpdate=5008`、`ErrorUserNotFound=5101`、`ErrorSelfRelation=5201`、`ErrorRelationBlocked=5202`、`ErrorFollowNotFound=5203`、`ErrorBlockNotFound=5204` | `service/follow/common/errmsg.go:33-53` |
| 错误文案（**英文**） | `OK` / `FAIL` / `service busy` / `internal server error` / `record not found` / `invalid parameter` / `unauthorized` / `forbidden` / `already exists` / `database query failed` / `database update failed` / `user not found` / `self relation is not allowed` / `blocked relation exists` / `follow relation not found` / `block relation not found`；未知码回退 `"FAIL"` | `service/follow/common/errmsg.go:55-74`、`:89-95` |
| gRPC 反查顺序 | ① `bizCodeByMsg[st.Message()]` 命中 ⇒ 取该码；② 否则按标准码 switch：`InvalidArgument→5003` / `Unauthenticated→5004` / `PermissionDenied→5005` / `AlreadyExists→5006` / `NotFound→5002` / `Internal→5001` / default→`1015` | `service/follow/common/errmsg.go:114-133` |
| JWT claims | `userId`（ctx key；**5 种类型全收**：`json.Number` / `string` / `int64` / `int` / `float64`） | `service/follow/api/internal/logic/follow/helper.go:14-33` |
| 黑名单 key | `user:jwt_blacklist:{token}`（**跨域共享前缀**，不是 `follow:`）；token 取法 = `strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))`（**大小写敏感 + TrimSpace**） | `service/follow/api/internal/middleware/checkblacklistmiddleware.go:33,39` |
| 表名 | `follow_relation` / `block_relation` | `service/follow/rpc/internal/model/data_model.go:13-15, 25-27` |
| 唯一索引名 | `uk_follow_pair` / `uk_block_pair`（**复合**：`(user_id, target_id)`）；单列索引 `idx_<table>_user_id` / `idx_<table>_target_id` | `service/follow/rpc/internal/model/data_model.go:7-8, 19-20` |
| HTTP path | `POST /follow/v1/follow`、`/unfollow`、`/block`、`/unblock`、`/follow/list`、`/follower/list`、`/block/list`、`/recommendations`（8 条，前缀 `/follow/v1`） | `service/follow/api/internal/handler/routes.go:21-63` |
| public 路径集 | **空集**（8/8 挂 `rest.WithJwt`） | `service/follow/api/internal/handler/routes.go:62` |
| 401 body 形态 | ① JWT 失败 ⇒ **空 body**；② 黑名单命中 ⇒ `{"code":5004,"msg":"unauthorized"}` + `Content-Type: application/json;charset=utf-8` | `checkblacklistmiddleware.go:42-48` |
| HTTP 状态码 | 除 401 外**恒 200**；成败只体现在 body 的 `code` | `service/follow/api/followcenter.go:45-66` |
| api 指标名 | `follow_api_request_total{route,result}` / `follow_api_reject_total{route,reason}` / `follow_api_request_duration_seconds{route}` | `service/follow/api/internal/metrics/metrics.go:13-30` |
| api 指标 `route` 字面量 | `/follow/v1/follow`、`/follow/v1/unfollow`、`/follow/v1/block`、`/follow/v1/unblock`、`/follow/v1/follow/list`、`/follow/v1/follower/list`、`/follow/v1/block/list`、`/follow/v1/recommendations`（8/8） | 8 个 api logic 各 `:32` |
| api 指标 `reason` 字面量 | `user_id_missing`（8 个 logic 各 1 处）、`forbidden_scope`（**仅** `getblocklistlogic.go:47` 与 `getrecommendationslogic.go:47`） | `metrics.go:51-53` + 8 个 logic |
| rpc 指标名 | `follow_rpc_request_total{method,result}` / `follow_rpc_request_duration_seconds{method}` / `follow_rpc_relation_action_total{action,result}` / `follow_rpc_db_error_total{action,type}` / `follow_rpc_recommendation_size`（**Gauge，无 label**，`Set` 而非 `Inc`） | `service/follow/rpc/internal/metrics/metrics.go:13-42` |
| rpc 指标 `method` 字面量 | `Follow` / `Unfollow` / `Block` / `Unblock` / `GetFollowList` / `GetFollowerList` / `GetBlockList` / `GetRecommendations`（8/8，与 proto rpc 名逐字一致） | `service/follow/rpc/internal/metrics/metrics.go` + 8 个 rpc logic |
| rpc 指标 `action` 字面量 | `ObserveRelation`：`follow` / `unfollow` / `block` / `unblock`（**小写无下划线**）；`ObserveDBError`：**同一列里混用两种风格** —— 4 个写用例小写、4 个 list 用 snake_case（`get_follow_list` / `get_follower_list` / `get_block_list` / `get_recommendations`） | `rpc/internal/logic/*.go` 各 `:35` / `:50` |
| Kafka topic | **无**（follow 域零消息生产 / 消费） | `service/follow/` 全树无 Kafka import |
| 监听端口 | api `8891`（`followcenter.yaml:3`）、rpc `8082`（`follow.yaml:2`）、DevServer `16064` / `16065` | `service/follow/api/etc/followcenter.yaml`、`service/follow/rpc/etc/follow.yaml` |
| 列表配置 | `List{DefaultLimit:20, MaxLimit:100}`；`Recommend{DefaultLimit:20, MaxLimit:50, MaxDepth:3, MaxFanout:50}` | `service/follow/rpc/internal/config/config.go:14-24` |
| 打分权重 | `scoreWeight(maxDepth,depth)`：`maxDepth<2→3`；`depth<2→2`；`weight=(maxDepth-depth+1)*10`；`weight<10→10` ⇒ `MaxDepth=3` 时 depth2 每命中 **+20**、depth3 **+10** | `service/follow/rpc/internal/logic/helper.go:115-128` |
| 深度 / 扇出夹取 | `clampRecommendDepth`：`<2→3`、`>5→5`；`clampFanout`：`<=0→50`、`>200→200` | `service/follow/rpc/internal/logic/helper.go:130-148` |

---

### 6.2 三列之间**职责对不上**的地方（填实现时必须逐条消解）

> ⚠️ 本小节是**本域专属**的对不上登记（不属于跨模块约定），按章节骨架附在 §6 末尾 —— §7 覆盖度自查必须是最后一节。
> ⚠️ **只登记「文件名 / 职责对应不闭合」的位置**，**不下「已对齐 / 未对齐」结论**。

| # | 位置 | 现象 | 落点 |
|---|---|---|---|
| 1 | **C 列 `api/` 包 vs `rpc/` 包** | **用例层重复**：C 的 `api/service/{FollowRelationService,FollowQueryService}` 与 `rpc/service/FollowDomainService` **各自持有一套用例**（前者 8 个 HTTP 用例、后者 8 个 gRPC 用例）⇒ **同一份业务规则落两处** | D2 的正确形态是「**一份**业务用例 + 两个边界适配器」⇒ 应让 api 层 Service 薄到只做编排，规则唯一落在 `FollowDomainService`；否则 `forbidden_scope` / `resolveUserID` / `Success:true` 会双写 |
| 2 | **C 列 `api/service/FollowApiSupport.java`** | B 的 `FollowApiSupport` 承担 4 件事：`extractUserID`（5 类型）+ `resolveUserID` + **`rpcError`（gRPC→业务码）** + proto→DTO 投影。**D2 后 `rpcError` 变成死代码**（域内不再有 gRPC 往返） | C 填实现时应删掉 `rpcError` 分支；且 `rpcError` 依赖的「msg **查表**而非 status 原文」这一 follow 特性随之失效 ⇒ 需在《完全spring化的文件树》§5.3 的统一错误模型里重新对齐 |
| 3 | **C 列 `api/config/WebMvcConfig.java` 与 `api/controller/FollowController.java` 的 javadoc** | 两处 javadoc 都写着「**public 路径白名单**」/「**有 public 段**」⇒ 与 A 列 Go 源码**矛盾**：`routes.go:62` 对 8 条路由统一挂 `rest.WithJwt`，**public 集为空** | **C 列 javadoc 需修正**（否则照着填会凭空造出一个 public 段） |
| 4 | **B 列 `follow-api/.../config/FollowGoStyleCoercion.java`** | B 有、C 无。C 的 `common/src/main/java/com/rag/common/web/GoStyleCoercion` + `RagJacksonAutoConfiguration` 只有**全局**规则 ⇒ `offset`/`limit` 的「数字形态」判定在 C **无落点** ⇒ Go 拒 / Jackson 收（方向：**Go 拒、RAG 收**，多放行不会报错，最难发现） | 缺口 #2 |
| 5 | **C 列 `service/follow/src/main/resources/db/migration/`** | 只有 `.gitkeep`；`service/follow/pom.xml` **无 `flyway-core`** ⇒ 两张表**无建表落点**；`@TableId` 主键策略（`IdType.AUTO` vs 全局雪花惯例）、`TIMESTAMPTZ`、3 个索引 + 1 个复合唯一索引全部无处落 | 缺口 #1 |
| 6 | **C 列 `service/follow/src/main/resources/application.yml`** | 缺 4 项：① `mybatis-plus.global-config.db-config.id-type`（Go 是 bigserial **不是**雪花）；② JDBC 的 `TimeZone=Asia/Shanghai`；③ `observability.http-timeout: 3s`（Go `RestConf.Timeout` 默认 **3000ms**，yaml 里一个字没写 ⇒ 靠 struct tag 默认）；④ `grpc.client.user-rpc.negotiation-type: plaintext`（**注释已写明必须显式配**，但下面还是空的） | 缺口 #3 |
| 7 | **C 列 `api/config/FollowApiProperties.java` / `rpc/config/FollowRpcProperties.java`** | C 的 `application.yml` 里**没有** `follow-api.user-auth.*`（`AccessSecret` / `AccessExpire`）也没有 `follow-rpc.list.*` / `follow-rpc.recommend.*` ⇒ 两段配置**当前无处落**（Go 侧 `followcenter.yaml:10-12` / `follow.yaml:43-51`） | 缺口 #3 |
| 8 | **C 列 `api/interceptor/FollowBlacklistInterceptor.java` + `api/interceptor/FollowJwtAuthInterceptor.java`** | 两者**必须**独立（401 body 形态不同：空 body vs JSON body）。C 的 `WebMvcConfig` javadoc 只写「注册 JwtAuthInterceptor + public 路径白名单」，**没提黑名单拦截器** ⇒ 注册清单不完整 | 缺口 #3 |
| 9 | **C 列 `api/controller/FollowController.java`** | Go 的 8 个 handler **逐字同质**（每个 31 行），C 合 1 个 Controller 是对的；但 **8 个 `route` 指标字面量**必须逐条落在对应方法上 ⇒ 合并后每个方法仍需自带 `route` 常量 | 填实现时的硬约束（§5 已登记 8 个字面量） |
| 10 | **C 列 `api/service/{FollowRelationService,FollowQueryService}.java` 的拆分边界** | C 把 8 个用例劈成「4 写 / 4 读」，但**读用例组内部本身不对称**：`getFollowList` / `getFollowerList` **无** `forbidden_scope`，`getBlockList` / `getRecommendations` **有** ⇒ 同一个类里两个方法一条规则、两个方法另一条规则 ⇒ **必须靠 javadoc 逐条标注**，否则后人「顺手统一」即漂移 | 填实现时的硬约束（§1.3 已登记） |
| 11 | **C 列 `rpc/mapper/GoStyleLimitOffset.java`** | B 侧是**新建**（`message-rpc` 的 `GoStylePagination` 是包私有 `final class` ⇒ 跨模块不可见）⇒ C 若「统一到全局 `common/`」必须**新写一份**，不能直接引用 | 缺口 #4 |
| 12 | **C 列 `rpc/service/FollowDomainService.java` 的 8 个方法 vs `api/service/*` 的 8 个用例** | 两个类的**方法名集合完全相同**（Follow/Unfollow/Block/Unblock/GetFollowList/GetFollowerList/GetBlockList/GetRecommendations）⇒ 极易被误读成「重复实现」⇒ 需要在 javadoc 里写清「`FollowDomainService` 是唯一规则源，api 层 Service 是编排」 | 缺口 #5（与 #1 同源） |
| 13 | **端口** | Go：api `8891` / rpc `8082`。C：`server.port: 8891` + `grpc.server.port: 9004` ⇒ **gRPC 端口不一致**（9004 ≠ 8082） | 登记（端口属 L3，但本表只记录事实） |
| 14 | **域内 `contract/` 包的 4 个类名** | C 用了**域中立**的命名（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）⇒ 命名上不再自带域标识 ⇒ 「逐域隔离、不可跨域统一」这条硬约束**只存在于 javadoc 里，不在类名里** | 登记（`contract/` 包的包名 `com.rag.follow.contract` 仍是域内的） |

### 6.3 由 §6.2 派生的**缺口清单**（C 侧当前无处落的位置）

| 缺口 | 内容 | 涉及的上表行 |
|---|---|---|
| **缺口 #1** | **DDL 无落点** —— `service/follow/src/main/resources/db/migration/` 只有 `.gitkeep`，`service/follow/pom.xml` 无 `flyway-core` ⇒ `follow_relation` / `block_relation` 两张表建不出来；`@TableId` 主键策略（`IdType.AUTO`）、`TIMESTAMPTZ`、2 个单列索引 + 1 个复合唯一索引（`uk_follow_pair` / `uk_block_pair`）全部无处落 | #5 |
| **缺口 #2** | **域内 Jackson 强转补强无落点** —— B 有 `FollowGoStyleCoercion`（`offset`/`limit` 的 `CoercionInputShape.String → Fail`），C 只有全局 `GoStyleCoercion` ⇒ Go 拒 / Jackson 收 | #4 |
| **缺口 #3** | **配置项无落点** —— `application.yml` 缺 4 项（`id-type` / `TimeZone=Asia/Shanghai` / `observability.http-timeout=3s` / `grpc.client.user-rpc.negotiation-type=plaintext`）+ 2 段业务配置（`follow-api.user-auth.*` / `follow-rpc.list.*` + `follow-rpc.recommend.*`）+ **黑名单拦截器未进 `WebMvcConfig` 注册清单** | #6 · #7 · #8 |
| **缺口 #4** | **`GoStyleLimitOffset` 无全局等价物** —— `message-rpc` 的 `GoStylePagination` 是包私有 `final class` ⇒ 跨模块不可见；C 若要归位到 `common/` 必须**新写一份** | #11 |
| **缺口 #5** | **用例层双写** —— `api/service/*` 的 8 个 HTTP 用例与 `rpc/service/FollowDomainService` 的 8 个 gRPC 用例**方法名集合完全相同** ⇒ 业务规则可能落两处 | #1 · #12 |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏（37/37）

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/follow" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

| # | C 列文件 | 所在小节 |
|---|---|---|
| 1 | `CodeError.java` | §1.1 |
| 2 | `ErrCodes.java` | §1.1 |
| 3 | `ErrMsg.java` | §1.1 |
| 4 | `GrpcErrorMapper.java` | §1.1 |
| 5 | `FollowServiceApplication.java` | §1.2 / §1.4 |
| 6 | `FollowApiExceptionHandler.java` | §1.2 |
| 7 | `FollowApiResponseAdvice.java` | §1.2 |
| 8 | `FollowApiProperties.java` | §1.2 |
| 9 | `WebMvcConfig.java` | §1.2 |
| 10 | `FollowController.java` | §1.2 |
| 11 | `FollowListReq.java` | §1.2 |
| 12 | `FollowUserListResp.java` | §1.2 |
| 13 | `RecommendListResp.java` | §1.2 |
| 14 | `RecommendUser.java` | §1.2 |
| 15 | `RelationActionReq.java` | §1.2 |
| 16 | `RelationActionResp.java` | §1.2 |
| 17 | `FollowBlacklistInterceptor.java` | §1.2 |
| 18 | `FollowJwtAuthInterceptor.java` | §1.2 |
| 19 | `FollowApiMetrics.java` | §1.2 |
| 20 | `FollowApiSupport.java` | §1.3 |
| 21 | `FollowQueryService.java` | §1.3 |
| 22 | `FollowRelationService.java` | §1.3 |
| 23 | `NacosRegistration.java` | §1.4 |
| 24 | `UserRpcClient.java` | §1.4 |
| 25 | `FollowRpcProperties.java` | §1.4 |
| 26 | `BlockRelationEntity.java` | §1.4 |
| 27 | `FollowRelationEntity.java` | §1.4 |
| 28 | `BlockRelationMapper.java` | §1.4 |
| 29 | `FollowRelationMapper.java` | §1.4 |
| 30 | `GoStyleLimitOffset.java` | §1.4 |
| 31 | `FollowRpcMetrics.java` | §1.4 |
| 32 | `FollowBlockTransactionService.java` | §1.4 |
| 33 | `FollowDomainService.java` | §1.4 |
| 34 | `FollowRecommendationService.java` | §1.4 |
| 35 | `FollowRpcSupport.java` | §1.4 |
| 36 | `FollowUserExistenceService.java` | §1.4 |
| 37 | `FollowGrpcService.java` | §1.4 |

> **结果：37/37 = 100%**，逐条出现在 §1 的 C 列。**C 列 0 遗漏。**
> ⚠️ 排除项：`src/test/java/com/rag/follow/FollowServiceApplicationTests.java`（测试）、`target/`（构建产物）、`follow.proto`（proto 源，非 `.java`，登记于 §1.1）。

### 7.2 A 列不遗漏职责（44/44 手写 `.go` + 2 个生成 `.go` + 2 个 `.yaml`）

| A 列文件 | 所在小节 | 覆盖判定 |
|---|---|---|
| `api/followcenter.go` | §1.2（+ §1.2 的 2 行 advice） | ✅ |
| `api/etc/followcenter.yaml` | §1.2 | ✅ |
| `api/internal/config/config.go` | §1.2 | ✅ |
| `api/internal/handler/routes.go` | §1.2 | ✅ |
| `api/internal/handler/follow/followhandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/unfollowhandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/blockhandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/unblockhandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/getfollowlisthandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/getfollowerlisthandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/getblocklisthandler.go` | §1.2 | ✅ |
| `api/internal/handler/follow/getrecommendationshandler.go` | §1.2 | ✅ |
| `api/internal/logic/follow/followlogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/unfollowlogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/blocklogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/unblocklogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/getfollowlistlogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/getfollowerlistlogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/getblocklistlogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/getrecommendationslogic.go` | §1.3 | ✅ |
| `api/internal/logic/follow/helper.go` | §1.3 | ✅ |
| `api/internal/metrics/metrics.go` | §1.2 | ✅ |
| `api/internal/middleware/checkblacklistmiddleware.go` | §1.2 | ✅ |
| `api/internal/svc/servicecontext.go` | §1.2 | ✅ |
| `api/internal/types/types.go` | §1.2（6 行 DTO + 1 行 coercion） | ✅ |
| `common/errmsg.go` | §1.1（4 行） | ✅ |
| `rpc/follow.go` | §1.4 | ✅ |
| `rpc/etc/follow.yaml` | §1.4 | ✅ |
| `rpc/followservice/followservice.go` | §1.4 | ✅ |
| `rpc/internal/config/config.go` | §1.4 | ✅ |
| `rpc/internal/logic/followlogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/unfollowlogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/blocklogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/unblocklogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/getfollowlistlogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/getfollowerlistlogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/getblocklistlogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/getrecommendationslogic.go` | §1.4 | ✅ |
| `rpc/internal/logic/helper.go` | §1.4 | ✅ |
| `rpc/internal/metrics/metrics.go` | §1.4 | ✅ |
| `rpc/internal/model/data_model.go` | §1.4 | ✅ |
| `rpc/internal/model/db.go` | §1.4 | ✅ |
| `rpc/internal/model/follow.go` | §1.4 | ✅ |
| `rpc/internal/model/vars.go` | §1.4 | ✅ **「Go 侧无对应物（原因）」**：实测零调用点，且与 `errmsg.go` 的 `ErrorNotFound(5002)` 同名不同物 ⇒ 不迁 |
| `rpc/internal/server/followserviceserver.go` | §1.4 | ✅ |
| `rpc/internal/svc/servicecontext.go` | §1.4 | ✅ |
| `rpc/pb/follow.pb.go` | §1.1（与生成物合并一行） | ✅ |
| `rpc/pb/follow_grpc.pb.go` | §1.1（与生成物合并一行） | ✅ |

> **结果：48/48 = 100%**（44 手写 `.go` + 2 生成 `.go` + 2 `.yaml`），其中 **47 个有 C 列落点**、**1 个明确注明「Go 侧无对应物（原因）」**（`rpc/internal/model/vars.go`）。
> ⚠️ Go 侧 `service/follow/` **零 `_test.go` 文件**（实测 `find -name '*_test.go'` = 0）⇒ 无测试文件需要登记。

### 7.3 B 列可核（48 个 `.java`）

| B 列模块 | `.java` 数 | 本表出现的 B 列文件 |
|---|---|---|
| `follow-api` | 27 | `FollowApiApplication`、`GlobalExceptionHandler`、`GlobalResponseAdvice`、`FollowRpcClient`、`FollowApiProperties`、`FollowGoStyleCoercion`、`WebMvcConfig`、`FollowController`、`dto/`×6、`interceptor/`×2、`FollowApiMetrics`、`service/`×10（8 用例 + `FollowApiSupport` + `AbstractFollowApiService`） |
| `follow-common` | 4 | `FollowCodeError`、`FollowErrorCode`、`FollowErrorMessages`、`FollowGrpcError` |
| `follow-rpc` | 17 | `FollowRpcApplication`、`NacosRegistration`、`UserRpcClient`、`FollowRpcProperties`、`MybatisPlusOrderingBridgeAutoConfiguration`、`domain/`×2、`mapper/`×3、`FollowRpcMetrics`、`service/`×5、`service/impl/FollowServiceGrpcImpl` |
| **合计** | **48** | **48/48 全部出现在 B 列** |

### 7.4 映射类型分布（仅 4 种合法词）

| 映射类型 | 出现次数 | 说明 |
|---|---|---|
| `1:1` | **75** | 三列逐条对位（多数 DTO / Entity / Mapper / Metrics / Interceptor / config） |
| `1:N` | **15** | 1 个 Go 文件 → 多个 Java 文件（`errmsg.go`→4 类、`data_model.go`→2 Entity、`model/follow.go`→3 mapper、8 个 handler→1 Controller 等） |
| `N:1` | **23** | 多个 Go 文件 → 1 个 Java 文件（8 个 api logic→2 Service、8 个 rpc logic→1 `FollowDomainService`、8 个 handler→1 Controller 等） |
| `C 新增` | **1** | `common/src/main/java/com/rag/common/autoconfigure/RagGrpcExceptionMapperAutoConfiguration.java`（D2 的统一 gRPC 异常映射，A / B 两侧都没有对应物） |
| **合计带映射类型的表行** | **114** | §0（6）+ §1.1（5）+ §1.2（27）+ §1.3（9）+ §1.4（20）+ §2（13）+ §3（17）+ §4（17） |

> ⚠️ **无其它映射类型**（未出现 `N:M` / `1:1:1` 等自造词）；**114 行全部填了映射类型，无空缺**。

### 7.5 本表**不回答**的问题（移交其它台账）

| 问题 | 去哪查 |
|---|---|
| follow 域的行为是否与 Go 一致 | `docs/DRIFT/follow/`（本仓）或 RAG 侧的 `docs/DRIFT/follow/{api,rpc,common}.md` 的**证据强度列** |
| follow 域已签字的有意偏离 | `docs/Intentional_Deviations/` |
| 「跑绿 ≠ 对」的踩坑 | `docs/Pitfalls/` |
| C 列文件的具体实现形态 | 《完全spring化的文件树》§4 / §5 + `docs/File_Tree/follow.md` |
