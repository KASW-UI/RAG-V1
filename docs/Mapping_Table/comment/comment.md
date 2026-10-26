# Mapping_Table/comment/comment.md — A(Go) ↔ B(RAG) ↔ C(RAG-V1) 三方对照表

> **本表目的**：把 comment 域的**三棵树**逐文件对应起来 —— A 列 Go 真理之源、B 列 RAG（Go 部署形态版）、
> C 列 RAG-V1（Spring 惯用目标形态）—— 便于在 RAG-V1 填实现时逐条反查「这个 Java 类是从哪个 Go 文件来的」。
>
> **格式契约**：[`README.MD`](../README.MD) · **章节骨架**照抄 RAG 侧 [`docs/MappingTable/users.md`](../../../../../java&golang/RAG/docs/MappingTable/users.md)（列换成 A/B/C 三棵树）。
>
> | 列 | 仓库 | 该域路径 |
> |---|---|---|
> | **A (Go 真理之源)** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | `service/comment/` |
> | **B (RAG, Go 形态版)** | `/Users/pr/Documents/java&golang/RAG` | `service/comment/` |
> | **C (RAG-V1, 本仓目标)** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/comment/` |

**路径简写约定**（便于 `grep` 定位）：

| 写法 | 含义 |
|---|---|
| A 列 `api/internal/logic/comment/helper.go` | 相对 A 列仓库根的 `service/comment/` |
| B 列 `comment-api/…/api/service/CommentApiService.java` | `…` = `src/main/java/com/rag/comment/comment` |
| C 列 `service/comment/src/main/java/com/rag/comment/service/CommentApiService.java` | `…` = `src/main/java/com/rag/comment` |

---

## ⚠️ 三条读表纪律（先读，再看表）

1. ⚠️ **B 列不是 Go 的替身。** comment 域 api 组件首轮判 `SEMANTICALLY NOT EQUIVALENT`（3 条 MISMATCH，已全修），
   rpc 组件 7 条 MISMATCH（已全修，各配真 PG 守门网 + 变异验证）。
   ⇒ **一切行为判断以 A 列 Go 源码为准**（台账：`RAG/docs/DRIFT/comment/{api,rpc,common,proto}.md`）。
2. ⚠️ **本表只回答「文件名 / 职责对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 DRIFT 台账的**证据强度**列；本表的 ✅ 只表示「文件名/职责有对应」。
3. ⚠️ **C 列目前全是空壳** —— 每个 `.java` 只有 package + 空类型 + TODO javadoc。
   ⇒ **C 列写的���「这个位置该放什么职责」，不是「已经有什么」。**

**映射类型词表（固定 4 种，不发明新词）**：`1:1` · `1:N` · `N:1` · `C 新增`。
（类型描述 **A → C** 的文件数关系；C 侧无独立文件时，在 C 列写明被吸收到哪个文件。）

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| A Go 目录 | B RAG 目录 | C RAG-V1 目录 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/comment/api/`（20 个 `.go` + 1 个 yaml） | `service/comment/comment-api/`（33 个 `.java`） | `service/comment/`（api 部分：`service/comment/src/main/java/com/rag/comment/{advice,config,controller,dto,interceptor,service}/**`） | 1:1 | **D1**：api 与 rpc 合并进 `comment-service` 单 app ⇒ C 的 api 侧不再独立进程 |
| `service/comment/rpc/`（37 个 `.go`，含 3 个生成物） | `service/comment/comment-rpc/`（42 个 `.java`） | `service/comment/`（rpc 部分：`service/comment/src/main/java/com/rag/comment/{wiring,client,config,domain/entity,domain/service,exception,grpc,mapper,messaging,metrics,service}/**`） | 1:1 | 同上 |
| `service/comment/common/errmsg/`（1 个 `.go`） | `service/comment/comment-common/`（4 个 `.java`） | `service/comment/src/main/java/com/rag/comment/contract/`（5 个 `.java`） | 1:1 | ⚠️ C 把错误契约放进 **contract 模块**（合并后 api/rpc 同进程共享，且与 proto 同模块）；命名从 `errmsg` → `contract` |
| `proto/comment.proto`（仓库根，245 行） | `service/comment/comment-proto/src/main/proto/comment.proto` | `proto/src/main/proto/comment.proto` | 1:1 | 模块名 `comment-proto` → 根级 `proto` |
| （Go 侧无独立 contract 模块） | — | `service/comment/src/main/java/com/rag/comment/contract/` 的 5 个手写类 | C 新增 | C 新增契约模块承载：错误码 + 错误文案 + Kafka 载荷 + proto |
| （Go 侧 **3 个进程**：api / rpc / 2 个 `cmd`） | **2 个进程**：`comment-api` + `comment-rpc` | **1 个进程**：`comment-service` | 1:1 | D1 的进程拓扑后果；`cmd/fact-*` 两个独立进程进一步收成域内组件（见 §1.4） |
| （Go 侧 etcd 服务发现：`Etcd.Key: comment.rpc`） | （B 侧 Nacos：`@EnableDiscoveryClient`） | （C 侧 Nacos：`@EnableDiscoveryClient`） | 1:1 | 注册中心替换属表达层；⚠️ 需保证 `grpc.client.*.negotiation-type: plaintext`（devh 默认 TLS，Go 是明文） |

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `comment/api/`（`CommentApiApplication`） | `com.rag.comment`（`CommentServiceApplication.java`） | 入口类合并（D1）|
| `comment/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `CommentApiResponseAdvice`）|
| `comment/api/client/` | `client/` | 直接压平 |
| `comment/api/config/` | `config/` | 与 rpc 侧合并 |
| `comment/api/controller/` | `controller/` | 直接压平 |
| `comment/api/dto/` | `dto/` | 直接压平（16 个同名类全落此）|
| `comment/api/interceptor/` | `interceptor/` | 直接压平 |
| `comment/api/service/` | `service/` | 与 rpc 侧合并 |
| `comment/common/errmsg/` | `contract/` | 错误码 + 事件 DTO（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrFactory` / `CommentKafkaMsg`）|
| `comment/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `comment/rpc/client/` | `client/` | 与 api 侧合并 |
| `comment/rpc/config/` | `config/` + `messaging/` | Kafka topic 落到 `messaging/`（`CommentKafkaTopics`）|
| `comment/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `comment/rpc/exception/` | `exception/` | 直接压平 |
| `comment/rpc/mapper/` | `mapper/` | 直接压平 |
| `comment/rpc/metrics/` | `metrics/` | 与 api 侧合并 |
| `comment/rpc/service/` | `service/` + `domain/service/` + `messaging/` | 领域服务另出 `domain/service/`；审计链路（`CommentAuditListener` / `CommentAuditMessage` / `CommentAuditPublisher`）落 `messaging/` |
| `comment/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `CommentServiceGrpcImpl` → `CommentGrpcService`；**`PbCommentServiceAlias` 保留原名**（Go 兼容别名，把同一实现再挂到 `/pb.CommentService/*`）|
| `comment-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.comment`）| **集中化**（D7）；proto `package` 由 `pb` 改为 `comment`（**L1 签字偏离** `DEV-COMMENT-PROTO-001`）|

## 1. 包结构对照

### 1.1 契约层（proto + 错误契约 + Kafka 载荷）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `proto/comment.proto`（仓库根，245 行） | `comment-proto/…/comment.proto` | `proto/src/main/proto/comment.proto` | 1:1 | 7 RPC / 4 enum / 16 message，字段号逐条一致。表达层差异：`package pb;` → `package comment;`（已签字 `SD-2026-10-23-04`）、`option go_package` → `option java_package` + `java_multiple_files` |
| `rpc/pb/comment.pb.go` + `rpc/pb/comment_grpc.pb.go`（protoc 生成物） | `comment-proto/target/generated-sources/…`（生成物） | `proto/target/generated-sources/…`（生成物） | 1:1 | 生成物不迁移；Java 侧由 protoc 插件重新生成（实测产出 `CommentServiceGrpc` + 16 message 类） |
| `rpc/commentservice/comment_service.go`（goctl 生成的 client 桩，7 方法） | （B 无 —— 直接用 devh `@GrpcClient`） | （C 无 —— 同款） | 1:1 | 生成物不迁移 |
| `common/errmsg/errmsg.go`（`CodeError` struct，`:10-13`） | `comment-common/…/errmsg/CommentCodeError.java` | `service/comment/src/main/java/com/rag/comment/contract/CodeError.java` | 1:1 | ⚠️ 域内隔离，**禁止跨域统一**（8 个域的码值/文案/语言各不相同）。C 壳注释已写明该硬约束 |
| `common/errmsg/errmsg.go`（27 个常量块，`:37-65`） | `comment-common/…/errmsg/CommentErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | 27 个码值是**跨服务契约**，删项等于改契约 |
| `common/errmsg/errmsg.go`（`codeMsg` 表 + `GetErrMsg`，`:67-103`） | `comment-common/…/errmsg/CommentErrorMessages.java` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | 文案含**语言**（中文）；未知码回退 `"FAIL"` 是 L1 |
| `common/errmsg/errmsg.go`（`NewGrpcErr`，`:33-35`） | `comment-common/…/errmsg/CommentGrpcError.java` | `service/comment/src/main/java/com/rag/comment/contract/GrpcErrFactory.java` | 1:1 | ⚠️ **C 壳注释待订正**：C 写「Go 的实现是**业务码进 message 字段**、gRPC 标准码进 code 字段」—— Go 源码是 `status.Error(codes.Code(code), msg)` = **业务码进 code 字段**（`grpc-status`）、msg 进 description |
| `rpc/common/types/kqueue.go`（`CommentKafkaMsg`，11 字段） | `comment-rpc/…/rpc/service/CommentAuditMessage.java`（消费侧 DTO）+ `comment-rpc/…/rpc/service/CommentAuditPublisher.java`（生产端） | `service/comment/src/main/java/com/rag/comment/contract/CommentKafkaMsg.java`（契约）+ `service/comment/src/main/java/com/rag/comment/messaging/CommentAuditMessage.java` + `service/comment/src/main/java/com/rag/comment/messaging/CommentAuditPublisher.java` | 1:N | ⚠️ 跨服务 Kafka 载荷 = **L1**（字段名与顺序即消费方解析依据）。C 提到 contract 模块，但**同时保留了 `CommentAuditMessage`** ⇒ 三处职责边界未定义（见 §7 责任对不上清单） |

**§1.1 决策锁**

**换契约模块（`-common` → `-contract`）4 处同时改**：
1. 模块目录 `service/comment/comment-common/` → `service/comment/src/main/java/com/rag/comment/contract/`（含 `pom.xml` 的 `<artifactId>`）
2. 根包 `com.rag.comment.comment.common.errmsg` → `com.rag.comment.contract`
3. 5 个类名：`CommentCodeError`→`CodeError`、`CommentErrorCode`→`ErrCodes`、`CommentErrorMessages`→`ErrMsg`、`CommentGrpcError`→`GrpcErrFactory`，外加新增 `CommentKafkaMsg`
4. proto 从 `comment-proto` 迁进 `comment-contract`（`java_package` 从 `com.rag.comment.comment.proto` 改成 `com.rag.comment.contract.proto`）

**为什么换**：D1 合并后 api 与 rpc 同进程、且与 proto 同属「对外契约」⇒ 契约类不该住在名叫 `common` 的服务模块里；
同时把「错误码 / 错误文案 / gRPC 错误工厂 / Kafka 载荷」四类契约件收进一个模块，便于按「契约」而非按「进程」检索。
⚠️ **代价**：`comment-common` 的独立性消失，跨域误引的风险由目录名兜底改为包名兜底 ⇒ 保留「逐域隔离」注释是硬要求。

---

### 1.2 api 侧：入口 / 路由 / 鉴权 / 响应 / DTO / 用例编排

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/commentservice.go`（入口 67 行：`SetOkHandler` + `SetErrorHandler` + 建 ctx + 注册路由） | `comment-api/…/api/CommentApiApplication.java` + `comment-api/…/api/advice/CommentGlobalResponseAdvice.java` + `comment-api/…/api/advice/CommentGlobalExceptionHandler.java` | `service/comment/src/main/java/com/rag/comment/CommentServiceApplication.java` + `service/comment/src/main/java/com/rag/comment/advice/CommentApiResponseAdvice.java` + `service/comment/src/main/java/com/rag/comment/advice/CommentApiExceptionHandler.java` | 1:N | Go 的 1 个 `SetOkHandler` + 1 个 `SetErrorHandler` 拆成 C 的 2 个 advice；类名去 `Global` 前缀（域内隔离） |
| `api/internal/handler/routes.go`（1 个 `AddRoutes` 块、7 条 POST、`rest.WithJwt` + `rest.WithPrefix("/comment/v1")`） | `comment-api/…/api/controller/CommentController.java` + `comment-api/…/api/config/WebMvcConfig.java` | `service/comment/src/main/java/com/rag/comment/controller/CommentController.java` + `service/comment/src/main/java/com/rag/comment/config/WebMvcConfig.java` | 1:N | 路由声明进 Controller 注解；前缀与拦截器注册进 `WebMvcConfig`。⚠️ 路径里的 `comment` 出现**两次**（Go 原样，勿「顺手改好」） |
| `api/internal/handler/comment/createcommenthandler.go`<br>`api/internal/handler/comment/deletecommenthandler.go`<br>`api/internal/handler/comment/getcommenthandler.go`<br>`api/internal/handler/comment/likecommenthandler.go`<br>`api/internal/handler/comment/managecommenthandler.go`<br>`api/internal/handler/comment/reportcommenthandler.go`<br>`api/internal/handler/comment/updatesubjectstatehandler.go`（7 个，实测逐字节同构） | `comment-api/…/api/controller/CommentController.java`（7 个 `@PostMapping`） | `service/comment/src/main/java/com/rag/comment/controller/CommentController.java`（7 个 `@PostMapping`） | N:1 | 7 个 handler 无独立依赖边界 / 生命周期 / 契约 ⇒ 合 1 个 Controller |
| （Go 侧 `rest.WithJwt` 装饰器 —— 无独立文件） | `comment-api/…/api/interceptor/CommentJwtAuthInterceptor.java` | `service/comment/src/main/java/com/rag/comment/interceptor/CommentJwtAuthInterceptor.java` | 1:1 | ⚠️ Go 侧 comment-api **没有** blacklist 中间件（`routes.go` 全文只有 `WithJwt` 一个装饰器）⇒ public 路径集 = **空集**；C 壳注释写「**有 public 段**」⇒ 待订正 |
| `api/internal/types/types.go`（**16 个 DTO**：7 `Req` + 7 `Resp` + `CommentItem` + `SubjectInfo`） | `comment-api/…/api/dto/*.java` × 16 | `service/comment/src/main/java/com/rag/comment/dto/*.java` × 16 | 1:N | **D5**：Go 可变 struct → C 的 immutable record；契约（字段名 / 类型）不变 |
| `api/internal/types/types.go` 的 `CommentItem`（`:6`） | `comment-api/…/api/dto/CommentItem.java` | `service/comment/src/main/java/com/rag/comment/dto/CommentItem.java` | 1:1 | 递归 `children` 字段（Go 侧恒为 `[]`） |
| `api/internal/types/types.go` 的 `SubjectInfo`（`:97`） | `comment-api/…/api/dto/SubjectInfo.java` | `service/comment/src/main/java/com/rag/comment/dto/SubjectInfo.java` | 1:1 | ⚠️ Go 侧 `owner_id` **恒为 0**（两端都不填） |
| `api/internal/types/types.go` 的 7 个 `Req` + 7 个 `Resp`（`:22`–`:113`） | `comment-api/…/api/dto/CreateCommentReq.java`<br>`comment-api/…/api/dto/CreateCommentResp.java`<br>`comment-api/…/api/dto/DeleteCommentReq.java`<br>`comment-api/…/api/dto/DeleteCommentResp.java`<br>`comment-api/…/api/dto/GetCommentReq.java`<br>`comment-api/…/api/dto/GetCommentResp.java`<br>`comment-api/…/api/dto/LikeCommentReq.java`<br>`comment-api/…/api/dto/LikeCommentResp.java`<br>`comment-api/…/api/dto/ManageCommentReq.java`<br>`comment-api/…/api/dto/ManageCommentResp.java`<br>`comment-api/…/api/dto/ReportCommentReq.java`<br>`comment-api/…/api/dto/ReportCommentResp.java`<br>`comment-api/…/api/dto/UpdateSubjectStateReq.java`<br>`comment-api/…/api/dto/UpdateSubjectStateResp.java`（14 个） | `service/comment/src/main/java/com/rag/comment/dto/CreateCommentReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/CreateCommentResp.java`<br>`service/comment/src/main/java/com/rag/comment/dto/DeleteCommentReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/DeleteCommentResp.java`<br>`service/comment/src/main/java/com/rag/comment/dto/GetCommentReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/GetCommentResp.java`<br>`service/comment/src/main/java/com/rag/comment/dto/LikeCommentReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/LikeCommentResp.java`<br>`service/comment/src/main/java/com/rag/comment/dto/ManageCommentReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/ManageCommentResp.java`<br>`service/comment/src/main/java/com/rag/comment/dto/ReportCommentReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/ReportCommentResp.java`<br>`service/comment/src/main/java/com/rag/comment/dto/UpdateSubjectStateReq.java`<br>`service/comment/src/main/java/com/rag/comment/dto/UpdateSubjectStateResp.java`（14 个） | 1:N | ⚠️ DTO 里的 `success` 字段来源**逐个不对称**：`DeleteCommentResp.success` 恒 `true`（Go 硬编码），其余 4 个透传 rpc 回值 |
| `api/internal/logic/comment/helper.go`（`extractUserID`，6 类型分支 + 2 类失败） | `comment-api/…/api/service/CommentApiSupport.java` | `service/comment/src/main/java/com/rag/comment/service/CommentApiSupport.java` | 1:1 | ⚠️ `float64` 分支**静默截断**；失败 ⇒ `code=1006`「TOKEN已过期」 |
| `api/internal/logic/comment/createcommentlogic.go`<br>`api/internal/logic/comment/deletecommentlogic.go`<br>`api/internal/logic/comment/getcommentlogic.go`<br>`api/internal/logic/comment/likecommentlogic.go`<br>`api/internal/logic/comment/managecommentlogic.go`<br>`api/internal/logic/comment/reportcommentlogic.go`<br>`api/internal/logic/comment/updatesubjectstatelogic.go`（7 个） | `comment-api/…/api/service/CreateCommentService.java`<br>`comment-api/…/api/service/DeleteCommentService.java`<br>`comment-api/…/api/service/GetCommentService.java`<br>`comment-api/…/api/service/LikeCommentService.java`<br>`comment-api/…/api/service/ManageCommentService.java`<br>`comment-api/…/api/service/ReportCommentService.java`<br>`comment-api/…/api/service/UpdateSubjectStateService.java`（7 个） | `service/comment/src/main/java/com/rag/comment/service/CommentApiService.java`（1 个，7 用例合并） | N:1 | 「用例内聚」判据 5 条全中 ⇒ 合并为 1 个 `CommentApiService`。⚠️ `/comment/list` 在 Go 侧**不做身份绑定**（`getcommentlogic.go` 不调 `extractUserID`） |
| `api/internal/svc/servicecontext.go`（`zrpc.MustNewClient` → `CommentServiceClient`） | `comment-api/…/api/client/CommentRpcClient.java` | `service/comment/src/main/java/com/rag/comment/service/CommentApiService.java` + `service/comment/src/main/java/com/rag/comment/domain/service/CommentDomainService.java` | 1:N | **D2**：域内直连 ⇒ C 的 `api/client/` 整包消失（全仓 0 个）；HTTP 编排层直接注入领域服务 |
| `api/internal/config/config.go`（`RestConf` + `UserAuth` + `CommentRpc`） | `comment-api/…/api/config/CommentApiProperties.java` | `service/comment/src/main/java/com/rag/comment/config/CommentApiProperties.java` | 1:1 | `@ConfigurationProperties("comment-api")` 替代 static field |
| `api/etc/commentservice.yaml`（`Port: 8888` / `UserAuth.AccessSecret` / `CommentRpc.NonBlock`） | `comment-api/src/main/resources/application.yml` | `service/comment/src/main/resources/application.yml` | 1:1 | ⚠️ C 的 yml **未带** `user-auth.access-secret` / `access-expire` / `rag.protection` 四旋钮 ⇒ 待补（密钥必须与签发方 `user-api` 同值） |
| （Go 侧无文件 —— go-zero `httpx.Parse` 的类型强转由框架承担） | `comment-api/…/api/config/CommentGoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（**common starter，不在本域**） | 1:1 | **D3/D4**：收进 starter ⇒ C 的 comment 域零文件 |

**§1.2 决策锁**

**7 个 api 用例合 1 个 `CommentApiService`（N:1）3 处同时改**：
1. 删 `comment-api/…/api/service/{7 个}Service.java`，新建 `service/CommentApiService.java`（7 个方法）
2. `CommentController` 的 7 个注入点从 7 个 Service 改成 1 个 Service（方法名一一对应）
3. `CommentApiSupport`（uid 解析 / 业务码映射）保持独立 —— 它**不是用例**，是协作者（§5.4 修正）

**为什么换**：D1/D2 合并后用例不再需要「构造 logic + 转交」这一层 ⇒ goctl 的「一用例一文件」是 codegen 产物，不是职责边界。

**删 `{advice,config,controller,dto,interceptor,service}/client/` 包（D2 域内直连）3 处同时改**：
1. 删 `comment-api/…/api/client/CommentRpcClient.java`（C 侧从一开始就不建）
2. `CommentApiService` 改为直接注入 `domain/service/CommentDomainService.java`
3. `application.yml` 的 `grpc.client.comment-rpc.*` 段删除（域内不再走网络）

**为什么换**：同进程内自我 gRPC 往返是无意义的序列化开销与故障注入点；跨域调用仍走 gRPC（D6）。

---

### 1.3 rpc 侧：入口 / gRPC 实现 / 装配 / 配置

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/comment.go`（P1 入口：`InitMetrics` + promhttp `:9091` goroutine + kq consumer + `zrpc.MustNewServer` + unary interceptor） | `comment-rpc/…/rpc/CommentRpcApplication.java` | `service/comment/src/main/java/com/rag/comment/CommentServiceApplication.java` | 1:1 | ⚠️ Go 的 promhttp `:9091` 独立 goroutine 在 C 无对应物（C 走 actuator `/actuator/prometheus`）；Go 的 `InitMetrics` 显式 `Unregister` 了 `go_*` / `process_*` collector，Java 默认会暴露 `jvm_*` / `process_*` ⇒ **可观测面差异待拍板** |
| `rpc/internal/server/comment_service_server.go`（7 个 RPC → logic 的 thin wrapper） | `comment-rpc/…/rpc/service/impl/CommentServiceGrpcImpl.java` | `service/comment/src/main/java/com/rag/comment/grpc/CommentGrpcService.java` | 1:1 | `@GrpcService` + `extends CommentServiceGrpc.CommentServiceImplBase` |
| （Go 侧靠 `zrpc` 命名空间；Go 的 proto package 是 `pb`） | `comment-rpc/…/rpc/service/impl/PbCommentServiceAlias.java` | `service/comment/src/main/java/com/rag/comment/grpc/PbCommentServiceAlias.java` | 1:1 | Go 兼容别名：把同一实现再挂到 `/pb.CommentService/*`（C 目前是空壳，待填）。⚠️ 只解决**连通性**，不解决业务码（grpc-java 发不出越界 `grpc-status`） |
| `rpc/internal/config/config.go`（`RpcServerConf` + `DB` + `KqPusherConf` + `BizRedis` + `KqConsumerConf` + `MessageRpc` + `ArticleRpc`） | `comment-rpc/…/rpc/config/CommentRpcProperties.java` + `comment-rpc/…/rpc/config/CommentKafkaTopics.java` | `service/comment/src/main/java/com/rag/comment/config/CommentRpcProperties.java` + `service/comment/src/main/java/com/rag/comment/messaging/CommentKafkaTopics.java` | 1:N | 1 个 Go config → 2 个 `@ConfigurationProperties`（Kafka 段独立成 `comment.kafka`） |
| `rpc/internal/svc/service_context.go`（`InitDB` + `InitRedis` + `kq.Pusher` + `SensitiveFilter` + 2 个 rpc client） | `comment-rpc/…/rpc/CommentRpcApplication.java`（装配）+ `comment-rpc/…/rpc/client/{ArticleRpcClient,MessageRpcClient}.java` | `service/comment/src/main/java/com/rag/comment/CommentServiceApplication.java`（装配）+ `service/comment/src/main/java/com/rag/comment/client/{ArticleRpcClient,MessageRpcClient}.java` | 1:N | Go 的 `ServiceContext` 5 个字段 → Spring DI |
| `rpc/internal/svc/service_context.go` 的 `MessageRpc` 字段 | `comment-rpc/…/rpc/client/MessageRpcClient.java` | `service/comment/src/main/java/com/rag/comment/client/MessageRpcClient.java` | 1:1 | **D6**：跨域仍走 gRPC；⚠️ 必须显式 `negotiation-type: plaintext` |
| `rpc/internal/svc/service_context.go` 的 `ArticleRpc` 字段 | `comment-rpc/…/rpc/client/ArticleRpcClient.java` | `service/comment/src/main/java/com/rag/comment/client/ArticleRpcClient.java` | 1:1 | 同上 |
| `rpc/etc/comment.yaml`（`ListenOn: 0.0.0.0:9001` / `DB.DataSource` / `KqPusherConf` / `KqConsumerConf` / `BizRedis` / `MessageRpc` / `ArticleRpc`） | `comment-rpc/src/main/resources/application.yml` | `service/comment/src/main/resources/application.yml` | 1:1 | ⚠️ C 的 yml **未带** `TimeZone=Asia/Shanghai`（Go 的 `DB.DataSource` 显式钉了时区，而 `created_at` 是 `TIMESTAMPTZ`）⇒ 待补 |
| （Go 侧用 etcd） | `comment-rpc/…/rpc/bootstrap/NacosRegistration.java` | `service/comment/src/main/java/com/rag/comment/wiring/NacosRegistration.java` | C 新增 | 注册中心替换属表达层；B 显式 `@Component("commentRpcNacosRegistration")`，**不依赖自动注册**（AGENTS.md §6.3 问题 3） |
| （Go 侧无 —— Jackson 3 的 `ObjectMapper` 需自行装配） | `comment-rpc/…/rpc/config/Jackson2ObjectMapperConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（**common starter，不在本域**） | 1:1 | **D3**：域内副本收进 starter |
| （Go 侧无 —— MyBatis-Plus ordering bridge） | `comment-rpc/…/rpc/config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`（**common starter，不在本域**） | 1:1 | **D3**：RAG 侧此文件被复制 9 份 ⇒ D3 的账单 |

**§1.3 决策锁**

**gRPC 方法全名兼容（`/comment.*` + `/pb.*` 双挂）2 处同时改**：
1. `CommentGrpcService` 保持 `@GrpcService`（挂 `/comment.CommentService/*`）
2. `PbCommentServiceAlias` 额外注册 `/pb.CommentService/*`，`ServerMethodDefinition` 只改 `fullMethodName`、handler 原样复用

**为什么换**：RAG 的 proto `package` 已签字归一为 `comment`（`SD-2026-10-23-04`），改回 `pb` 会推翻已签字偏离；
⇒ 只能在服务端「加别名」补连通性。⚠️ 代价：业务码仍退化（grpc-java 只认 17 个标准码）⇒ 灰度必须**同栈配对**。

---

### 1.4 rpc 侧：领域服务 / 事务 / 缓存 / 指标 / 事实链路 / `cmd/fact-*`

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/internal/logic/create_comment_logic.go`<br>`rpc/internal/logic/delete_comment_logic.go`<br>`rpc/internal/logic/get_comment_logic.go`<br>`rpc/internal/logic/like_comment_logic.go`<br>`rpc/internal/logic/manage_comment_logic.go`<br>`rpc/internal/logic/report_comment_logic.go`<br>`rpc/internal/logic/update_subject_state_logic.go`（7 个） | `comment-rpc/…/rpc/service/CommentDomainService.java`（7 个 public 方法） | `service/comment/src/main/java/com/rag/comment/domain/service/CommentDomainService.java` | N:1 | 7 个 logic 合 1 个 DomainService；**本类刻意不含 `@Transactional`**（Go 侧 7 个 logic 无一开事务） |
| `rpc/internal/logic/subject_fallback.go`（`targetType=="article"` 时向 article-rpc 取 ownerId） | `comment-rpc/…/rpc/service/CommentDomainService.java`（类内方法） | `service/comment/src/main/java/com/rag/comment/domain/service/CommentDomainService.java`（类内方法） | 1:1 | 跨域回落；⚠️ Go 侧 `create` 逻辑出错时返回的是原始 `err` 而非 `fallbackErr`（当前结果等价，意图不显式） |
| `rpc/internal/model/comment.go` 的 3 个事务方法（`InsertCommentTx` / `DeleteCommentTx` / `LikeCommentTx`） | `comment-rpc/…/rpc/service/CommentTransactionalService.java` | `service/comment/src/main/java/com/rag/comment/domain/service/CommentTransactionalService.java` | 1:1 | 事务边界是独立 L3 关注点 ⇒ **保留独立类**（§5.4 修正：事务边界类不参与用例合并） |
| `rpc/internal/model/comment.go` 的 10 个非事务方法 | `comment-rpc/…/rpc/service/CommentDomainService.java` + `comment-rpc/…/rpc/service/CommentCacheService.java` | `service/comment/src/main/java/com/rag/comment/domain/service/CommentDomainService.java` + `service/comment/src/main/java/com/rag/comment/service/CommentCacheService.java` | 1:N | 读路径一半走缓存、一半走 DB ⇒ 落在两个类 |
| `rpc/internal/model/domain_fact.go`（`appendCommentFact` + 事实追加） | `comment-rpc/…/rpc/service/CommentDomainFactService.java` | `service/comment/src/main/java/com/rag/comment/domain/service/CommentDomainFactService.java` | 1:1 | ⚠️ Go 的 fact 用 `.UTC()`，而 `get_comment_logic.go:139` 的 wire 时间用本地时区 ⇒ 两个时间基准并存 |
| `rpc/internal/model/fact_delivery.go`（`DispatchFactOnce` + `validCommentDelivery`） | `comment-rpc/…/rpc/service/CommentFactDeliveryService.java` + `comment-rpc/…/rpc/service/CommentDeliveryValidator.java` | `service/comment/src/main/java/com/rag/comment/service/CommentFactDeliveryService.java` + `service/comment/src/main/java/com/rag/comment/service/CommentDeliveryValidator.java` | 1:N | 1 个 Go 文件 → 2 个 C 类（投递 / 校验职责分开） |
| `rpc/internal/model/fact_authority.go`（`AuthoritativeFact` + `authoritativeCommentInteraction`） | `comment-rpc/…/rpc/service/CommentAuthorityLookupService.java` | `service/comment/src/main/java/com/rag/comment/service/CommentAuthorityLookupService.java` | 1:1 | 实现 `common/src/main/java/com/rag/common/communityfact/AuthorityHandler.AuthorityLookup` |
| `rpc/internal/model/vars.go`（3 个 sentinel error） | `comment-rpc/…/rpc/exception/CommentExceptions.java` | `service/comment/src/main/java/com/rag/comment/exception/CommentExceptions.java` | 1:1 | 3 个 sentinel 映射到 3 个不同业务码（1012 / 1013 / 1008） |
| `rpc/internal/utils/sensitive.go`（Aho-Corasick 多模式匹配） | `comment-rpc/…/rpc/service/CommentSensitiveFilter.java` | `service/comment/src/main/java/com/rag/comment/service/CommentSensitiveFilter.java` | 1:1 | ⚠️ Go 侧词库在 `service_context.go` 硬编码 `["傻逼","我草"]`；B 把词库外提到 `comment.sensitive-words`（默认值与 Go 逐字一致） |
| `rpc/internal/cache/keys.go`（4 个 key 构造） | `comment-rpc/…/rpc/service/CommentRedisKeys.java` | `service/comment/src/main/java/com/rag/comment/service/CommentRedisKeys.java` | 1:1 | ⚠️ key 格式是**缓存契约**；`comment:subject:%s` 只含 subjectID 不含 targetType（Go 既有缺陷，B 保留，C 待定） |
| `rpc/internal/cache/comment_cache.go`<br>`rpc/internal/cache/comment_subject_cache.go`<br>`rpc/internal/cache/comment_ids_page_cache.go`<br>`rpc/internal/cache/comment_index_cache.go`<br>`rpc/internal/cache/comment_content_cache.go`<br>`rpc/internal/cache/comment_invalidation_cache.go`（6 个） | `comment-rpc/…/rpc/service/CommentCacheService.java`（12 个 public 方法） | `service/comment/src/main/java/com/rag/comment/service/CommentCacheService.java` | N:1 | Go 侧本就是同一 struct `CommentCache` 的方法集 ⇒ 合 1 类。⚠️ 四类读穿的错误语义**各不相同**（2 个降级回源 / 2 个直接失败） |
| `rpc/internal/cache/redis.go`（`InitRedis`，3s 超时 Ping，失败 `log.Fatalln`） | `comment-rpc/…/rpc/CommentRpcApplication.java` + `comment-rpc/…/rpc/service/CommentCacheService.java` | `service/comment/src/main/java/com/rag/comment/CommentServiceApplication.java` + `service/comment/src/main/java/com/rag/comment/service/CommentCacheService.java` | 1:1 | ⚠️ Go 是「起不来」，C 的失败模式（fail-open / fail-fast）待定 |
| `rpc/internal/mqs/audit_consumer.go`（Kafka 消费者：敏感词判定 → 落库 → 失效缓存 → 通知） | `comment-rpc/…/rpc/service/CommentAuditListener.java` | `service/comment/src/main/java/com/rag/comment/messaging/CommentAuditListener.java` | 1:1 | ⚠️ **本域「创建评论」不写库** —— 落库发生在 Kafka 消费侧 ⇒ `CreateCommentResp.subject_count` 恒为 0 |
| `rpc/internal/metrics/metrics.go`（5 个 Prometheus 向量 + `InitMetrics`） | `comment-rpc/…/rpc/metrics/CommentRpcMetrics.java` | `service/comment/src/main/java/com/rag/comment/metrics/CommentRpcMetrics.java` | 1:1 | ⚠️ B 用 `registry.timer("comment_request_seconds")`；Go/A 与 C 壳注释都是 **`comment_request_seconds_counter`**（Counter）⇒ B 侧命名与 A 列有差，C 应按 A 列 |
| `rpc/internal/trace/trace.go`（`InitOTel` —— 实测**全仓零调用点**，整个文件是死代码） | （B 无对应文件） | （C 无对应文件） | 1:1 | Go 侧 7 个 logic 的手工 span 拿到的都是 no-op tracer ⇒ **从未真正导出**。⚠️ C 若正确 `@Import` gRPC 拦截器会产生**真实 span** ⇒ 新增可观测面，须显式承认 |
| **`rpc/cmd/fact-dispatch/main.go`**（独立进程 P3：env 校验 + `DCEventSender(8s)` + `RunDispatcher(-once)`） | **`comment-rpc/…/rpc/bootstrap/CommentFactDispatchScheduler.java`** | **`service/comment/src/main/java/com/rag/comment/wiring/CommentFactDispatchScheduler.java`** | **1:1** | ★ **Go 独立进程 → B/C 域内组件**，落点一致（论证见 RAG `docs/FileTree/like/rpc.md:79-99` 同款机制 + RAG-V1 `docs/决策记录.md` D1「例外判定已撤销」）。⚠️ 遗留子项 P6-a（`-once` 模式）待拍板 |
| **`rpc/cmd/fact-authority/main.go`**（独立进程 P2：env 校验 + `AuthorityHandler` servlet + 3s/5s 超时 + 优雅停机） | **`comment-rpc/…/rpc/bootstrap/CommentAuthorityEndpointConfig.java`** | **`service/comment/src/main/java/com/rag/comment/wiring/CommentAuthorityEndpointConfig.java`** | **1:1** | ★ **Go 独立进程 → B/C 域内组件**，落点一致。⚠️ B 侧默认**不装配**（`comment.authority.enabled=false`）；遗留子项 P6-b（监听端口）待拍板 |
| `service/common/communityfact/logging.go`（`ProcessMetadata{Service:"rtw-comment-fact-dispatch" / "rtw-comment-fact-authority"}` + `NewProcessLogger` ⇒ **2 份独立进程级 logger 身份**） | （B 无对应文件） | **`service/comment/src/main/java/com/rag/comment/wiring/CommentFactProcessLogging.java`** | 1:1 | ★ Go 侧两个 `cmd/fact-*` **各自建一份**进程级 logger ⇒ 合并进域内组件后，这两份身份仍须各自独立。⚠️ **不得用 SLF4J 全局 MDC 承载**（MDC 跨线程插值会把两份身份混成一份）。✅ **2026-10-25 补齐**（原为落点缺口，见落点缺口表 #5）；形态对齐 like 域 `LikeFactProcessLogging` / favorite 域 `FavoriteFactProcessLogging`（A11） |
| `rpc/internal/model/db.go`（`InitDB` + `AutoMigrate` 6 张表，失败 `log.Fatalln`） | `comment-rpc/…/rpc/bootstrap/CommentSchemaGate.java` + `comment-rpc/src/main/resources/db/migration/V1..V8` | `service/comment/src/main/java/com/rag/comment/wiring/CommentSchemaGate.java` + `service/comment/src/main/resources/db/migration/`（**仅 `.gitkeep`，空**） | 1:1 | Go「表与进程同生共死」；Java 改用 Flyway 后引入了 Go 没有的新失败模式 ⇒ `CommentSchemaGate` 是补的闸。⚠️ **C 的 8 个迁移脚本一个都没落地** |

**§1.4 决策锁**

**`cmd/fact-*` 两个独立进程 → 域内组件（1:1）3 处同时改**：
1. Go 的 `cmd/fact-dispatch/main.go` → `wiring/CommentFactDispatchScheduler.java`（`@Component`，不是独立可执行 jar）
2. Go 的 `cmd/fact-authority/main.go` → `wiring/CommentAuthorityEndpointConfig.java`（`@Configuration` + `@ConditionalOnProperty`）
3. Go 侧「三份 logger 身份」（业务 RPC / fact-dispatch / fact-authority）⇒ 合并后必须保住三份独立 logger 名与 appender

**为什么换**：两个 cmd **零对外服务 / 零 gRPC 注册** ⇒ 没有强类型契约需要独立进程承载；`Dispatcher` / `AuthorityHandler`
本来就是设计给「宿主进程」用的纯库。⚠️ **代价**：① 失去独立扩缩容；② `-once` 模式与 authority 监听端口需另找触发入口（P6）；③ ~~C 缺 `CommentFactProcessLogging`（like 域有）⇒ 三份 logger 身份目前**没有落点**~~ —— ✅ **2026-10-25 已补齐**（`wiring/CommentFactProcessLogging.java`），三份 logger 身份现有落点，与 like / favorite 域形态对齐（A11）。

---

### 1.5 rpc 侧：持久层（模型 / 表 / Mapper / DDL）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/internal/model/data_model.go` 的 `Subject`（表 `subject`） | `comment-rpc/…/rpc/domain/SubjectEntity.java`（`@TableName("subject")`） | `service/comment/src/main/java/com/rag/comment/domain/entity/SubjectEntity.java` | 1:1 | 评论区主体表；⚠️ Go 的 `AutoMigrate` **不含** `report_record` |
| `rpc/internal/model/data_model.go` 的 `CommentIndex`（表 `comment_index`） | `comment-rpc/…/rpc/domain/CommentIndexEntity.java` | `service/comment/src/main/java/com/rag/comment/domain/entity/CommentIndexEntity.java` | 1:1 | 评论索引表；主键**不自增**（值来自雪花算法，由 Kafka 消息携带落库） |
| `rpc/internal/model/data_model.go` 的 `CommentContent`（表 `comment_content`） | `comment-rpc/…/rpc/domain/CommentContentEntity.java` | `service/comment/src/main/java/com/rag/comment/domain/entity/CommentContentEntity.java` | 1:1 | 评论正文表 |
| `rpc/internal/model/data_model.go` 的 `ReportRecord`（表 `report_record`） | `comment-rpc/…/rpc/domain/ReportRecordEntity.java` | `service/comment/src/main/java/com/rag/comment/domain/entity/ReportRecordEntity.java` | 1:1 | ⚠️ Go 的 `AutoMigrate` 不建这张表（`report_comment_logic.go` 直接写）⇒ RAG 补了 `V5__create_comment_report_record.sql` |
| `rpc/internal/model/data_model.go` 的 `CommentLike`（表 `comment_like`） | `comment-rpc/…/rpc/domain/CommentLikeEntity.java` | `service/comment/src/main/java/com/rag/comment/domain/entity/CommentLikeEntity.java` | 1:1 | 点赞表 |
| `rpc/internal/model/data_model.go` 的 `CommentDomainFactOutbox`（表 `comment_domain_fact_outbox`） | `comment-rpc/…/rpc/domain/CommentDomainFactOutboxEntity.java`（`autoResultMap = true`） | `service/comment/src/main/java/com/rag/comment/domain/entity/CommentDomainFactOutboxEntity.java` | 1:1 | 事实 outbox；含 jsonb 列 |
| `rpc/internal/model/data_model.go` 的 `CommentFactStream`（表 `comment_fact_stream`） | `comment-rpc/…/rpc/domain/CommentFactStreamEntity.java` | `service/comment/src/main/java/com/rag/comment/domain/entity/CommentFactStreamEntity.java` | 1:1 | 事实流表 |
| `rpc/internal/model/data_model.go` 的 `ReplySort` + `GetReplyIDsPageReq`（Go 常量 / 请求结构） | （B 无独立类 —— 进 mapper 方法签名） | （C 无独立类 —— 进 mapper 方法签名） | 1:1 | `ReplySortHot` / `ReplySortTime` 两个常量 |
| `rpc/internal/model/data_model.go` 的 GORM 实体 → 隐式 CRUD | `comment-rpc/…/rpc/mapper/SubjectMapper.java`<br>`comment-rpc/…/rpc/mapper/CommentIndexMapper.java`<br>`comment-rpc/…/rpc/mapper/CommentContentMapper.java`<br>`comment-rpc/…/rpc/mapper/CommentLikeMapper.java`<br>`comment-rpc/…/rpc/mapper/ReportRecordMapper.java`<br>`comment-rpc/…/rpc/mapper/CommentDomainFactOutboxMapper.java`<br>`comment-rpc/…/rpc/mapper/CommentFactStreamMapper.java`（7 个） | `service/comment/src/main/java/com/rag/comment/mapper/SubjectMapper.java`<br>`service/comment/src/main/java/com/rag/comment/mapper/CommentIndexMapper.java`<br>`service/comment/src/main/java/com/rag/comment/mapper/CommentContentMapper.java`<br>`service/comment/src/main/java/com/rag/comment/mapper/CommentLikeMapper.java`<br>`service/comment/src/main/java/com/rag/comment/mapper/ReportRecordMapper.java`<br>`service/comment/src/main/java/com/rag/comment/mapper/CommentDomainFactOutboxMapper.java`<br>`service/comment/src/main/java/com/rag/comment/mapper/CommentFactStreamMapper.java`（7 个） | 1:1 | 一表一 Mapper；全仓零 XML mapper（注解式原生 SQL） |
| `rpc/internal/model/data_model.go` 的 jsonb 列 | `comment-rpc/…/rpc/mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（**common starter，不在本域**） | 1:1 | **D3**：type handler 收进 starter ⇒ C 的 `mapper/` 包只有 7 个 Mapper、无 type handler |
| `rpc/internal/model/migrations/001_comment_domain_fact_outbox.sql` / `002_comment_dc_wire.sql` / `003_comment_fact_delivery.sql` | `comment-rpc/src/main/resources/db/migration/V6..V8`（+ `V1..V5` 建 5 张业务表） | `service/comment/src/main/resources/db/migration/`（**空**，仅 `.gitkeep`） | 1:1 | ⚠️ C 未落地任何迁移脚本 ⇒ `CommentSchemaGate` 必然失败 |

**§1.5 决策锁**

**GORM 实体 → MyBatis-Plus Entity + Mapper（1:1 / 1:N）4 处同时改**：
1. 每个 GORM struct 一个 `@TableName("xxx")` 的 Entity（`@TableId(type = IdType.ASSIGN_ID)`，因为主键来自雪花）
2. 每个 Entity 一个 `Mapper extends BaseMapper<Entity>`，动态 SQL 用 `@Select("<script>…")`
3. jsonb 列走 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler` + `@TableName(autoResultMap = true)`；⚠️ 写入必须 `ps.setObject(i, json, Types.OTHER)`
4. 建表来源：Go 的 `AutoMigrate` → Flyway `V1..V8`（C 侧**尚未落地**）+ `CommentSchemaGate` 兜底

**为什么换**：MyBatis-Plus 的 `LambdaQueryWrapper` 比 GORM 链式调用更直观；显式迁移脚本可管 schema 历史。
⚠️ **代价**：Go 的 `AutoMigrate` 是「表与进程同生共死」，Flyway 引入了 Go 没有的新失败模式 ⇒ 必须补 `CommentSchemaGate`。

---

## 2. 持久层差异

| 维度 | A Go（真理之源） | B RAG（Go 形态版） | C RAG-V1（Spring 目标） | 备注 |
|---|---|---|---|---|
| ORM | GORM + `AutoMigrate`（`model/db.go:15`） | MyBatis-Plus `BaseMapper` + Flyway `V1..V8` | MyBatis-Plus `BaseMapper` + **Flyway 迁移脚本尚未落地**（`db/migration/` 仅 `.gitkeep`） | 框架换代，非行为换代 |
| 实体注解 | 无（GORM struct tag） | `@TableName("xxx")` + `@TableId(type = IdType.ASSIGN_ID)` | 同 B | 主键**不自增**（雪花 ID 由 Kafka 消息携带） |
| 数据访问 | `CommentModel` 13 个方法（3 个事务） | `Mapper` 接口 + `CommentDomainService` / `CommentTransactionalService` / `CommentCacheService` | 同 B（3 个 Service 同款落点） | 查询条件必须逐条保留 |
| jsonb | GORM `datatypes` 隐式 | `mapper/JsonbTypeHandler`（域内） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（starter） | **D3**：收进 starter |
| Schema 演进 | `AutoMigrate` 6 张表（**不含 `report_record`**） | Flyway 8 个脚本（**含 `report_record`**） | 待落地 | ⚠️ 表集合差异 1 张（`report_record`） |
| 事务边界 | 3 个方法自带事务（`InsertCommentTx` / `DeleteCommentTx` / `LikeCommentTx`）；7 个 logic **无事务** | `CommentTransactionalService` 3 个 `@Transactional` 方法 | `CommentTransactionalService` 3 个 `@Transactional` 方法 | ⚠️ 事务边界**只在**事务类上，不在 DomainService 上 |
| 行级锁 / 悲观锁 | `SELECT ... FOR UPDATE`（在 `comment.go` 的事务方法内） | 同语义（mapper 注解 SQL 内） | 待填 | **L2 语义，必须逐条保留** |
| 分页 | `GetReplyIDsByPage` 手工拼 `LIMIT/OFFSET` | `mapper/GoStylePagination`（**在 `service/common`，非本域**） | `common` starter 同款 | 无 MyBatis-Plus 分页拦截器 |
| 雪花 ID | `snowflake.GetID()`（`common/snowflake`） | `common/spring/SnowflakeBean` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | epoch / 位段是业务契约 |
| SQL 超时 | go-zero `context` 取消 | `@Import{DeadlineQueryTimeoutInjector, SqlTimeoutStatusTranslator}` | `common/src/main/java/com/rag/common/persistence/` 同款（starter） | ⚠️ 不配 ⇒ 在途 SQL 不会被取消 |

**§2 决策锁**

**持久层换 MyBatis-Plus + Flyway（5 处同时改）**：
1. `pom.xml`：加 `mybatis-plus-spring-boot3-starter` + `flyway-core`；`datasource` 段从 go-zero 的 `DataSource` 串改成 JDBC URL
2. 7 个 GORM struct → 7 个 Entity（`@TableName` + `@TableId`）
3. `CommentModel` 13 个方法 → 7 个 Mapper + 3 个 Service（领域 / 事务 / 缓存）
4. 建表来源：`AutoMigrate` → `V1..V8` 迁移脚本 + `CommentSchemaGate` 兜底
5. JDBC URL 补 `TimeZone=Asia/Shanghai`（Go 的 `DB.DataSource` 显式钉了时区，而 `created_at` 是 `TIMESTAMPTZ`）

**为什么换**：MyBatis-Plus 的注解式原生 SQL + 显式迁移脚本，比 GORM 的隐式 DDL 更适合多人多环境；
但**必须补回 Go 侧「表缺失 = 进程起不来」这道闸**，否则失败模式从启动期漂到运行期。

---

## 3. HTTP / DI 差异

| 维度 | A Go（真理之源） | B RAG（Go 形态版） | C RAG-V1（Spring 目标） | 备注 |
|---|---|---|---|---|
| 进程拓扑 | **3 个进程**：api（`:8888`）/ rpc（`:9001`）/ 2 个 `cmd` | **2 个进程**：`comment-api` + `comment-rpc` | **1 个进程**：`comment-service`（HTTP `:8892` + gRPC `:9002`） | **D1** |
| 路由注册 | `rest.AddRoutes(...)` + `rest.WithPrefix("/comment/v1")` + `rest.WithJwt(...)` | `@RequestMapping("/comment/v1")` + 7 个 `@PostMapping` + `WebMvcConfig.addInterceptors` | 同 B | 前缀 `/comment/v1` 是 **L1**，路径里 `comment` 出现两次 |
| 路由条数 | 7 条**全部 POST** | 7 条 `@PostMapping` | 7 条 | ⚠️ C 壳注释里的路径清单与 A 列不一致（见 §7） |
| public 路径集 | **空集**（`routes.go:54` 的 `WithJwt` 覆盖 7/7） | 空集 | ⚠️ C 壳注释写「有 public 段」⇒ 待订正 | `WebMvcConfig` 的白名单必须为空 |
| Handler / Controller | 7 个 handler（逐字节同构）→ 7 个 logic | 1 个 `CommentController`（7 `@PostMapping`）→ 7 个 Service | 1 个 `CommentController` → **1 个 `CommentApiService`** | 用例内聚 |
| 依赖注入 | `svc.ServiceContext` static 字段 | Spring Bean（`ServiceContext` 已删） | Spring Bean（`ServiceContext` 已删） | — |
| 同域调用 | api → rpc 走 zrpc（自我往返） | api → rpc 走 `@GrpcClient` | **直连**（`CommentApiService` → `CommentDomainService`） | **D2** |
| 跨域调用 | `zrpc.MustNewClient`（message / article） | devh `@GrpcClient` | devh `@GrpcClient` | **D6**；⚠️ 必须 `negotiation-type: plaintext` |
| 错误表达 | `httpx.SetErrorHandler` + `errors.As(&CodeError)` ⇒ **HTTP 恒 200** + `{code,msg,data}` | `CommentGlobalExceptionHandler` + `CommentGlobalResponseAdvice` | `CommentApiExceptionHandler` + `CommentApiResponseAdvice` | C 侧命名去 `Global` |
| 业务码上行 | `errmsg.NewGrpcErr(code,msg)` = `status.Error(codes.Code(code), msg)` ⇒ **业务码进 `grpc-status`** | `CommentGrpcError.grpcError`（业务码进自定义 trailer `rag-comment-biz-code`） | `service/comment/src/main/java/com/rag/comment/contract/GrpcErrFactory.java`（待填） | 🔴 **平台级硬限制**：grpc-java 只认 17 个标准码 ⇒ 业务码必须载体迁移 |
| 配置绑定 | yaml → `config.Config` struct | `@ConfigurationProperties` | `@ConfigurationProperties` | 类型安全 |
| 服务发现 | etcd（`Etcd.Key: comment.rpc`） | Nacos | Nacos | 表达层替换 |
| Kafka 消费 | `kq.MustNewQueue` + `go queue.Start()`（8 并发消费者） | `@KafkaListener` | `@KafkaListener` | ⚠️ Boot 4 必须引 `spring-boot-kafka`（不是 `spring-kafka`） |
| 指标暴露 | 独立 goroutine 起 `:9091` 的 `promhttp.Handler()` | actuator `/actuator/prometheus` | actuator `/actuator/prometheus` | ⚠️ Go 侧显式 `Unregister` 了 `go_*` / `process_*` collector |

**§3 决策锁**

**api 与 rpc 合并为单 app（D1）6 处同时改**：
1. 删 `comment-api/` 与 `comment-rpc/` 两个模块，新建 `service/comment/`
2. 删 `CommentApiApplication` / `CommentRpcApplication`，新建唯一的 `CommentServiceApplication`
3. 两个 `application.yml` 合成一个（HTTP 端口 + gRPC 端口 + datasource + redis + kafka + nacos）
4. `comment-api/…/client/` 整包删除，改为直连注入（D2）
5. `comment-proto` 合并进 `comment-contract`
6. 两个 `@ConfigurationProperties`（`comment-api` / `comment`）合并到 `CommentApiProperties` + `CommentRpcProperties`

**为什么换**：Spring Cloud 的部署单元是「可部署服务」，不是「api 层 / rpc 层」；go-zero 的 api/rpc 分离源于 `.api` / `.proto`
两条 codegen 路径，是**语言产物**不是架构原则。⚠️ **代价**：域的独立扩容能力消失；「rpc 进程不可用」这一故障模式消失。

---

## 4. 公共模块对照（comment 域 ↔ `common/` 的边界）

> ⚠️ **两个 `common` 不是同一个东西**：
> `comment/common/errmsg/`（Go 侧 **comment 域专属**，1 个文件）≠ `service/common/`（Go 侧**跨服务共享**）。
> 本节只写 comment 域**与 `service/common`（C 列的 `common/` starter）** 的边界。

| A Go 文件（`service/common/` 与 `service/comment/` 的交叉点） | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/common/response/response.go`（信封 `{code,msg,data}`） | `service/common/…/response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | **L1**：客户端按 `{code,msg,data}` 解析 ⇒ 不得用 `ProblemDetail` 替换 |
| `service/common/errmsg`（**Go 的 `service/common` 里没有 errmsg** —— 那是 comment 域专属） | `service/common/…/exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | 1:1 | Go 的 `CodeError{Code,Msg}` 与 `BusinessException(code,msg)` **形状同构** ⇒ 不自建异常类 |
| `service/common/snowflake/snowflake.go` | `service/common/…/snowflake/Snowflake.java` + `spring/SnowflakeBean` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | 1:1 | epoch / 位段是业务契约（游标依赖 `>>22`） |
| `service/common/communityfact/*.go`（`Wire` / `Dispatcher` / `AuthorityHandler` / `ProcessLogger`） | `service/common/…/communityfact/*.java` | `common/src/main/java/com/rag/common/communityfact/*.java`（13 个类） | 1:1 | ⚠️ **共享事件协议**（与 favorite / like 三方共用）⇒ 协议本体归 `common.md`；本表只记 comment 侧接线 |
| `service/common/communityfact` —— comment 侧接线：投递 | `comment-rpc/…/rpc/service/CommentFactDeliveryService.java` + `CommentDeliveryValidator.java` | `service/comment/src/main/java/com/rag/comment/service/CommentFactDeliveryService.java` + `CommentDeliveryValidator.java` | 1:1 | `SKIP LOCKED` + 前序屏障 + 回执校验 |
| `service/common/communityfact` —— comment 侧接线：权威读 | `comment-rpc/…/rpc/service/CommentAuthorityLookupService.java` | `service/comment/src/main/java/com/rag/comment/service/CommentAuthorityLookupService.java` | 1:1 | 实现 `AuthorityHandler.AuthorityLookup` |
| `service/common/communityfact` —— comment 侧接线：调度 | `comment-rpc/…/rpc/bootstrap/CommentFactDispatchScheduler.java` | `service/comment/src/main/java/com/rag/comment/wiring/CommentFactDispatchScheduler.java` | 1:1 | ★ Go 的 `cmd/fact-dispatch` 独立进程 → 域内组件 |
| `service/common/communityfact` —— comment 侧接线：权威端点 | `comment-rpc/…/rpc/bootstrap/CommentAuthorityEndpointConfig.java` | `service/comment/src/main/java/com/rag/comment/wiring/CommentAuthorityEndpointConfig.java` | 1:1 | ★ Go 的 `cmd/fact-authority` 独立进程 → 域内组件 |
| `service/common/observability/*.go`（http / mq / rpc 三条链） | `service/common/…/observability/*` + `spring/ObservabilityLifecycle` | `common/src/main/java/com/rag/common/observability/*` + `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | 1:1 | **D4**：Micrometer Observation + OTel bridge ⇒ 部分 L3 降级（span 名 / 属性需对拍） |
| `service/common/logger/logger.go`（logx 封装） | `service/common/…/logger/GoStyleLogger` | `common/src/main/java/com/rag/common/logger/GoStyleLogger.java` | 1:1 | `call_chain` 等字段是行为契约 |
| `service/common/protection/*`（熔断 / 降载 / 限连接 / 限体积） | `service/common/…/protection/*`（17 个类） | `common/src/main/java/com/rag/common/protection/{CpuThresholdHealthIndicator,MaxBytesFilter}.java`（2 个） | N:1 | **D4**：17 → 2 ⇒ 熔断迁 resilience4j（**P2 待拍板**） |
| `service/common/web/JsonBodyConverter` 等 | `service/common/…/http/GoStyleJsonBodyConverter*` | `common/src/main/java/com/rag/common/web/JsonBodyConverter.java` 等 | 1:1 | go-zero `withJsonBody` 判据逐字保留 |
| `service/common/grpc` 拦截器工厂（`RpcServerInterceptorFactory`） | `service/common/…/spring/RpcServerInterceptorFactory` | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java`（+ `ObservabilityAutoConfiguration`） | 1:1 | ⚠️ gRPC 拦截器**不自动注册** ⇒ 必须显式 `intercept(...)`，否则 span / deadline / 超时**静默消失** |
| （Go 侧 comment 域无 redis blacklist） | `comment-api/…/api/interceptor/CommentJwtAuthInterceptor.java`（无 blacklist 分支） | `service/comment/src/main/java/com/rag/comment/interceptor/CommentJwtAuthInterceptor.java` | 1:1 | ⚠️ Go 侧无 blacklist ⇒ 引入即为**新增行为** |

**§4 决策锁**

**`service/common` → `common` starter（D3）3 处同时改**：
1. `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 建档（7 行），全部 Bean 由 auto-config 提供
2. 删 10 个域各自的 `@Import` 清单与 9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration`
3. 域内引用的类改为 `com.rag.common.*`（`JsonbTypeHandler` / `GoStyleCoercion` / `ApiResponse` / `BusinessException` …）

**为什么换**：Spring 生态里共享能力层的标准形态是 starter，不是「普通 jar + 每个消费者手写 `@Import`」。
⚠️ **代价**：**`@Import` 与 auto-config 不能混用**（`@Import` 那份按 user config 更早求值，会改变 `@ConditionalOnMissingBean` 语义）
⇒ 要么全删 `@Import`，要么保持现状，不能并存。

---

## 5. 决策锁（汇总）

| # | 决策 | 状态 | 落到本域的 N 处同时改 |
|---|---|---|---|
| **D1** | api 与 rpc 合并为单一 Spring Boot 应用 | ✅ 已定 | 6 处（§3） |
| **D1 撤销** | `cmd/fact-*` **不设例外** ⇒ 三域的 fact 能力作域内组件 | ✅ 已定 | 3 处（§1.4）；**遗留 P6-a / P6-b 待拍板** |
| **D2** | 域内调用直连，不再自我 gRPC 往返 | ✅ 已定 | 3 处（§1.2） |
| **D3** | `service/common` 升级为真 starter | ✅ 已定 | 3 处（§4） |
| **D4** | 生态优先，手写只补生态缺口 | ✅ 已定（熔断子集见 **P2 待拍板**） | protection 17 → 2（§4） |
| **D5** | DTO 改 immutable record + Bean Validation | ✅ 已定 | 16 个 DTO 改 record（§1.2）；⚠️ 校验文案属 L3 降级（**P1 待拍板**） |
| **D6** | 跨域调用仍走 gRPC | ✅ 已定 | 2 个 client（§1.3）；⚠️ 必须显式 `negotiation-type: plaintext` |
| **P4** | `rpc.*` 包是否改名 | ⏳ 待拍板（建议不改，记技术债） | 0 处（不改） |
| **P6** | `cmd/fact-*` 合并后的三个遗留子项 | ⏳ 待拍板 | P6-a `-once` 模式 / P6-b authority 端口 / P6-c preflight 入口 |

**为什么换（一句话总览）**：Go / RAG 的形态是「go-zero 的 codegen 产物」——api/rpc 分离、一用例一 logic、一进程一 main；
RAG-V1 的形态是「Spring 生态的默认形态」——一域一 app、用例内聚、共享层 starter 化。
**L1（路由 / proto / 错误码 / 指标名 / Kafka topic / 表名）逐字保留；L3（熔断 / 降载 / 校验文案 / span 属性）允许降级并登记。**

---

## 6. 跨模块全局约定（值逐条核对 A 列 Go 源码）

| 项 | 值（逐字） | A 列来源（`file:line`） | 备注 |
|---|---|---|---|
| **proto service 名 / 方法数** | `CommentService`，7 个 RPC | `proto/comment.proto:237-245` | `CreateComment` / `GetComment` / `DeleteComment` / `LikeComment` / `ReportComment` / `ManageComment` / `UpdateSubjectState` |
| **proto message / enum 数** | 16 message / 4 enum | `proto/comment.proto:11,19,25,47,59…233` | `CommentState` / `SubjectState` / `ReportReason` / `ManageType` |
| **proto `package`** | Go = `pb`；B = `comment`；C = `comment` | `proto/comment.proto:4` | ⚠️ **实测 B↔C 的 proto 只差 `java_package` 一行 ⇒ C 同样归一**（与已签字 `SD-2026-10-23-04` 同款），并非「C 未归一」 |
| **gRPC 方法全名** | Go = `/pb.CommentService/*`；B / C = `/comment.CommentService/*`（+ `PbCommentServiceAlias` 补挂 `/pb.*`） | `service/comment/rpc/pb/comment_grpc.pb.go:316` | 跨栈混跑必须**同栈配对**（业务码在 Java 侧走 trailer） |
| **HTTP path** | `/comment/v1/comment/create` · `/comment/v1/comment/delete` · `/comment/v1/comment/like` · `/comment/v1/comment/list` · `/comment/v1/comment/manage` · `/comment/v1/comment/report` · `/comment/v1/subject/state`（**7 条全 POST**） | `service/comment/api/internal/handler/routes.go:20,25,30,35,40,45,50` + `:55`（`WithPrefix("/comment/v1")`） | ⚠️ 路径里 `comment` 出现两次（Go 原样） |
| **JWT 密钥** | `ae0536f9-6450-4606-8e13-5a19ed505da0`（`AccessExpire: 31536000`） | `service/comment/api/etc/commentservice.yaml:5-7` | 与 `like` / `message` / `article` 的 api **同值** ⇒ 必须与签发方 `user-api` 同密钥 |
| **JWT claim** | `userId`（api 侧从 ctx 取；6 个 logic 消费，`/comment/list` **不消费**） | `service/comment/api/internal/logic/comment/helper.go:11` | 失败 ⇒ `code=1006`「TOKEN已过期」 |
| **public 路径集** | **空集** | `service/comment/api/internal/handler/routes.go:54`（`WithJwt` 覆盖唯一那个 `AddRoutes` 块） | 7/7 全部要 JWT |
| **错误码（27 个）** | `Success=200` / `Error=500` / `CodeServerBusy=1015` / `ErrorServerCommon=5001` / `ErrorDbUpdate=5002` / `ErrorDbSelect=5003` / `ErrorDbInsert=5004` / `ErrorDbTransaction=5005` / `ErrorSnowflakeID=5006` / `ErrorJsonMarshal=5007` / `ErrorJsonUnmarshal=5008` / `ErrorKafkaPush=5009` / `ErrorDelayMsg=5010` / `ErrorUserExist=1001` / `ErrorLoginWrong=1002` / `ErrorUserNotExist=1003` / `ErrorTokenNotExist=1004` / `ErrorTokenTypeWrong=1005` / `ErrorTokenRuntime=1006` / `ErrorTokenRefresh=1007` / `ErrorUserNoRight=1008` / `ErrorUserNoLogin=1009` / `ErrorUserLogined=1010` / `ErrorUserBanned=1011` / `ErrorSubjectNotExist=1012` / `ErrorCommentNotExist=1013` / `ErrorInputWrong=2001` | `service/comment/common/errmsg/errmsg.go:37-65` | 🔴 **comment 必须自持码表**，禁止 REUSE 任何既有 `ErrorCode`（与 hot 域的 5002/5003/5004 语义完全不同） |
| **错误文案（27 条）** | 全中文（如 `5001`=`系统内部错误`、`2001`=`输入有误`、`1012`=`评论区不存在`、`1013`=`评论不存在`） | `service/comment/common/errmsg/errmsg.go:67-95` | ⚠️ 未知码回退 `"FAIL"`（`:97-103`）；**文案含语言**（与 hot 域英文文案不同） |
| **业务码上行** | `status.Error(codes.Code(code), msg)` ⇒ **业务码进 `grpc-status` 字段**，msg 进 description | `service/comment/common/errmsg/errmsg.go:33-35` | 🔴 grpc-java 只认 17 个标准码 ⇒ Java 侧必须载体迁移（trailer） |
| **表名（7 张）** | `subject` / `comment_index` / `comment_content` / `comment_like` / `report_record` / `comment_domain_fact_outbox` / `comment_fact_stream` | `service/comment/rpc/internal/model/data_model.go:19,39,52,67,100` + `model/db.go:15` + `model/migrations/001…003` | ⚠️ Go 的 `AutoMigrate` **只建 6 张**（不含 `report_record`） |
| **Kafka topic** | `comment-audit-topic`（生产 + 消费同 topic） | `service/comment/rpc/etc/comment.yaml:24,30` | — |
| **Kafka 消费组 / 偏移 / 并发** | `comment-audit-group` / `offset: last` / `consumers: 8` | `service/comment/rpc/etc/comment.yaml:29-32` | — |
| **Kafka 载荷** | `CommentKafkaMsg`：11 字段，snake_case（`comment_id` / `target_type` / `target_id` / `user_id` / `root_id` / `parent_id` / `content` / `meta` / `owner_id` / `attribute` / `create_time`） | `service/comment/rpc/common/types/kqueue.go:3-15` | 🔴 **跨服务 L1**：字段名与顺序即消费方解析依据 |
| **指标名（5 个）** | `comment_request_total`（Counter，`[module,action,result]`）/ `comment_request_seconds_counter`（Counter，`[module,action]`）/ `comment_list_size`（Gauge，`[module,action]`）/ `comment_postgres_error_total`（Counter，`[module,action,type]`）/ `comment_redis_error_total`（Counter，`[module,action,type]`） | `service/comment/rpc/internal/metrics/metrics.go:10-38`（`Subsystem: "comment"`） | ⚠️ `request_seconds_counter` **名字像 Histogram，实际是累加秒数的 Counter**；⚠️ B 侧用 `comment_request_seconds`（Timer）⇒ **B 与 A 有命名差** |
| **指标 `module` 标签值** | `comment_rpc`（6 个 logic）/ `comment_list`（list_size gauge）/ `comment_redis`（缓存错误）/ `comment_postgres`（DB 错误） | `logic/*.go` 的 `WithLabelValues(...)` + `cache/*.go` + `model/comment.go` | ⚠️ `result` 标签取值集合不一致：6 个 logic = `{ok,biz_fail,sys_fail}`，`get_comment_logic.go` 只有 `{ok,sys_fail}` |
| **时间格式** | wire 用 `2006-01-02 15:04:05`（**本地时区、无时区后缀**）；DB URL 钉 `TimeZone=Asia/Shanghai` | `logic/create_comment_logic.go`（`time.DateTime`）+ `logic/get_comment_logic.go:139` + `rpc/etc/comment.yaml:14` | ⚠️ 同一个域里**两个时间基准**（fact 用 `.UTC()`，wire 用本地时区） |
| **敏感词词库** | `["傻逼","我草"]`（硬编码在 `service_context.go`） | `service/comment/rpc/internal/svc/service_context.go:29` | B 外提到 `comment.sensitive-words`（默认值逐字一致） |
| **Redis key** | `comment:subject:%s`（**只含 subjectID，不含 targetType**） | `service/comment/rpc/internal/cache/keys.go` | ⚠️ Go 既有缺陷；改格式会让存量 key 变孤儿 |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/comment" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u | wc -l
# ⇒ 69
```

| 模块 | C 列 `.java` 数 | 本表覆盖 | 覆盖率 |
|---|---|---|---|
| `service/comment/src/main/java/com/rag/comment/contract/` | 5 | 5 | **100%**（`CodeError` / `CommentKafkaMsg` / `ErrCodes` / `ErrMsg` / `GrpcErrFactory`） |
| `service/comment/src/main/java/com/rag/comment/`（技术角色包） | 63 | 63 | **100%**（advice 2 + wiring 5 + client 2 + config 3 + controller 1 + domain/entity 7 + domain/service 3 + dto 16 + exception 1 + grpc 2 + interceptor 1 + mapper 7 + messaging 4 + metrics 1 + service 8） |
| `service/comment/src/main/java/com/rag/comment/CommentServiceApplication.java`（启动类） | 1 | 1 | **100%** |
| **合计** | **69** | **69** | **100%** |

### 7.2 A 列不遗漏职责

```bash
find "/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/comment" \
  -name '*.go' -not -name '*_test.go' | wc -l
# ⇒ 58（api 20 + common 1 + rpc 37；含 3 个生成物）
```

| 分类 | 文件数 | 落点 | 覆盖 |
|---|---|---|---|
| api 侧 `.go` | 20 | §1.2（`commentservice.go` / `config.go` / `servicecontext.go` / `routes.go` / 7 handler / `helper.go` / 7 logic / `types.go`） | **20/20** |
| api 侧非 `.go`（yaml） | 1 | §1.2（`api/etc/commentservice.yaml`） | **1/1** |
| common 侧 `.go` | 1 | §1.1（`common/errmsg/errmsg.go`，拆 4 行） | **1/1** |
| rpc 侧 `.go`（手写） | 34 | §1.3 / §1.4 / §1.5 | **34/34** |
| rpc 侧生成物 | 3 | §1.1（`pb/*.pb.go` ×2、`commentservice/comment_service.go`） | **3/3** |
| rpc 侧非 `.go` | 6 | §1.4（`etc/comment.yaml`、3 个 `.sql`）+ §1.1/§1.4（`COMMUNITY_FACTS_ACCEPTANCE.md`、`test-community-postgres.sh`） | **6/6** |
| **合计** | **58 `.go` + 7 非 `.go`** | — | **100%**（无「无对应物」遗留项） |

> **测试文件说明**：`rpc/internal/model/{domain_fact_test.go,fact_delivery_logging_test.go,fact_process_fixture_test.go}`
> 3 个 `_test.go` **不计入 A 列**（覆盖度要求只算非测试 `.go`）。

### 7.3 映射类型分布

> 统计口径：全文所有带「映射类型」列的表格行（§0 顶层模块 7 行 + §1 包结构 61 行（§1.1 8 / §1.2 14 / §1.3 11 / §1.4 17 / §1.5 11）+ §4 公共模块 14 行 = **82 行**）。

| 映射类型 | 行数 | 占比 | 主要来源 |
|---|---|---|---|
| `1:1` | 65 | 79% | 绝大多数 Go 文件 1:1 落到 1 个 C 类（7 个 Entity、7 个 Mapper、13 个 rpc 协作者、16 个 DTO…） |
| `1:N` | 10 | 12% | Go 1 个文件 → Spring 多类：`errmsg.go` 拆 4 类、`data_model.go` 拆 7 Entity + 7 Mapper、`types.go` 拆 16 个 DTO、`fact_delivery.go` 拆 2 类、`config.go` 拆 2 个 `@ConfigurationProperties`、`model/comment.go` 拆 3 个 Service |
| `N:1` | 5 | 6% | goctl 的「一用例一文件」被合并：7 handler → 1 `CommentController`、7 api logic → 1 `CommentApiService`、7 rpc logic → 1 `CommentDomainService`、6 cache 文件 → 1 `CommentCacheService`、protection 17 类 → 2 类（§4） |
| `C 新增` | 2 | 2% | `NacosRegistration`（Go 用 etcd）、C 的 contract 包行（Go 侧无独立契约模块） |
| **合计** | **82 行** | **100%** | — |

> ⚠️ **词表合规**：全文只出现 `1:1` / `1:N` / `N:1` / `C 新增` 四种，未发明新词（`N:M` / `1:1:1` 等一律未出现）。
> ⚠️ **数量不等不是缺陷**（README §一）：C 比 B 少 10 个 `.java`（79 → 69），主要来自 D1 合并（少 2 个 Application / 少 7 个 api Service / 少 1 个 client）
> 与 D3/D4（少 `CommentGoStyleCoercion` / `Jackson2ObjectMapperConfiguration` / `MybatisPlusOrderingBridgeAutoConfiguration` / `JsonbTypeHandler` 4 个域内副本）。

### 7.4 ⚠️ 三列之间「职责对不上」清单（本表产出的待订正项）

> 以下都是**实测**发现的 C 列壳注释 / 树结构与 A 列 Go 源码（或 B 列）不一致的地方。
> **本表不下「该不该修」的结论**，只登记事实与落点。

| # | 位置 | 现象 | A 列事实 | 落点建议 |
|---|---|---|---|---|
| 1 | C `service/comment/src/main/java/com/rag/comment/contract/GrpcErrFactory.java` 的 javadoc | 写「Go 的实现是**业务码进 message 字段**、gRPC 标准码进 code 字段」 | Go 是 `status.Error(codes.Code(code), msg)` ⇒ **业务码进 code 字段**（`errmsg.go:33-35`） | 壳注释与 A 列相反 ⇒ 填实现前必须订正，否则会照着错的描述写 |
| 2 | C `service/comment/src/main/java/com/rag/comment/controller/CommentController.java` 的 javadoc | 路由清单写成 `/comment/v1/comment`、`/comment/v1/comment/subject/state` 等 7 条 | Go 是 `/comment/v1/comment/create` … `/comment/v1/subject/state`（**第 1 条是 `create` 不是空段；第 7 条不含第二个 `comment`**） | 路由清单必须按 `routes.go:20,25,30,35,40,45,50` 逐条订正 |
| 3 | C `service/comment/src/main/java/com/rag/comment/controller/CommentController.java` 的 javadoc | 写「**鉴权划分（L1）：有 public 段**」 | Go 的 `AddRoutes` **只有一个块**，`rest.WithJwt` 覆盖 7/7 ⇒ **public 集为空集**（`routes.go:54`） | `WebMvcConfig` 的白名单必须是空集，否则多放行一条未鉴权路径 |
| 4 | C `service/comment/src/main/java/com/rag/comment/contract/CommentKafkaMsg.java` + `rpc/service/CommentAuditMessage.java` | 两个文件都在，但**职责边界未定义**（谁消费、谁生产、是否重复） | Go 只有 1 个 struct（`kqueue.go:3`），生产端与消费端共用 | B 的形态是「消费侧 DTO 在 `CommentAuditMessage`、生产端在 `CommentAuditPublisher`」；C 把载荷提到 contract 后，`CommentAuditMessage` 的定位需明确（否则 3 个文件 2 份契约） |
| 5 | C `service/comment/src/main/java/com/rag/comment/wiring/` | ~~缺 `CommentFactProcessLogging`（like 域的同款有）~~ | Go 侧一个域有 **3 个进程级 logger 身份**（业务 RPC / fact-dispatch / fact-authority） | ✅ **2026-10-25 已补齐**（`CommentFactProcessLogging.java` 落点到位，形态对齐 like / favorite 域）⇒ 原缺口关闭。⚠️ 遗留：`docs/决策记录.md` D1「落地证据」写「三域各含 `wiring/<域>FactProcessLogging`」——**补齐后该表述才成立**（此前对 comment 域不成立，B、C 两边都没有）；A11 断言随之转绿 |
| 6 | C `service/comment/src/main/resources/db/migration/` | 只有 `.gitkeep`，**8 个迁移脚本一个都没落地** | Go 的 `AutoMigrate` 建 6 张表，失败即退出（`model/db.go:15`） | `CommentSchemaGate` 必然失败 ⇒ 建表脚本是实现的硬前置 |
| 7 | C `service/comment/src/main/resources/application.yml` | JDBC URL **未带** `TimeZone=Asia/Shanghai` | Go 的 `DB.DataSource` 显式钉了 `TimeZone=Asia/Shanghai`（`rpc/etc/comment.yaml:14`），而 `created_at` 是 `TIMESTAMPTZ` | 不带 ⇒ wire 上 `created_at` 差一个时区 |
| 8 | C `service/comment/src/main/resources/application.yml` | **未带** `comment-api.user-auth.{access-secret,access-expire}` 与 `rag.protection` 四旋钮 | Go 的 `api/etc/commentservice.yaml:5-7` 有密钥；`rag.protection` 四项在 Go 侧**由 go-zero struct tag 默认值开启**（`MaxConns 10000` / `MaxBytes 1048576` / `CpuThreshold 900` / Breaker+Shedding） | ⚠️ 「yaml 里没写」≠「没开」⇒ C 侧需显式写出，否则默认关闭 = 行为差异 |
| 9 | B `comment-rpc/…/metrics/CommentRpcMetrics.java` | 用 `registry.timer("comment_request_seconds")` | Go/A 与 C 壳注释都是 **`comment_request_seconds_counter`**（Counter，`metrics.go:16-20`） | **B 与 A 列有命名差** ⇒ C 填实现时按 A 列（`comment_request_seconds_counter`），不要照抄 B |
| 10 | 本表任务简报的前提 | 简报写「RAG-V1 的 proto 与 Go 逐字节一致，未归一」 | **实测**：C 的 `comment.proto` 是 `package comment;` ⇒ **C 与 B 同样归一**；B↔C 的 diff **只差 `java_package` 一行** | 前提与实测不符 ⇒ 本表按实测写「B 归一 / C 也归一」；若另有「C 应保持 `pb`」的意图，需单独立项 |
| 11 | C `service/comment/src/main/java/com/rag/comment/service/`（13 个协作者） | `CommentRedisKeys` / `CommentSensitiveFilter` / `CommentTransactionalService` 等**独立成类**，未并入 `CommentDomainService` | Go 侧它们本来就是独立文件 / 独立 struct | ✅ 这一项**对得上**（§5.4 修正：领域协作者与事务边界类不参与用例合并）⇒ 记录为「已正确，无需动」 |
| 12 | C `service/comment/src/main/java/com/rag/comment/mapper/` | 无 `JsonbTypeHandler`（B 有） | Go 的 `comment_domain_fact_outbox` 等含 jsonb 列 | ✅ **不是缺口**：`common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` 已在 starter 里（D3 收编） |

---

## 附：一句话总览

**A 列 Go** = 3 进程 / 58 个 `.go` / goctl 生成形态（一用例一 logic、一 handler 一文件、16 个 DTO 结构体混在一个 `types.go`）；
**B 列 RAG** = 2 进程 / 79 个 `.java` / Spring 复刻（api/rpc 分离、用例仍按 codegen 拆、`common` 是普通 jar）；
**C 列 RAG-V1** = 1 进程 / 68 个 `.java` / Spring 惯用形态（api+rpc 合并、用例内聚、`common` 是真 starter、契约落在域内 `contract/` 包（proto 在根级 `proto/`））。
⇒ **文件名与职责逐条对应；行为一律以 A 列 Go 源码为准；本表不下对齐结论。**
