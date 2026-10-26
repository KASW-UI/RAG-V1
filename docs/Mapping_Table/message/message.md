# Mapping_Table/message/message.md — Go ↔ RAG ↔ RAG-V1 三方对照表（message 域）

> **本表目的**：把 **A 列**（Go 真理之源）→ **B 列**（RAG，Go 形态版）→ **C 列**（RAG-V1，Spring 惯用目标形态）三棵树里 **message 域**的文件逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> | 列 | 仓库 | 路径 | 角色 |
> |---|---|---|---|
> | **A** | `Sea-RideTheWind_golang` | `service/message/` | **真理之源**（冻结，只读） |
> | **B** | `RAG` | `service/message/` | Go 部署形态版（api/rpc 分离） |
> | **C** | `RAG-V1` | `service/message/` | Spring 惯用目标形态（api/rpc 合并，D1） |
>
> **映射类型词表（固定 4 种，不发明新词）**：`1:1` · `1:N` · `N:1` · `C 新增`
>
> **格式契约**：[`README.MD`](../README.MD)　**章节骨架来源**：`RAG/docs/MappingTable/users.md`

### ⚠️ 三条读表纪律（必读）

1. **B 列不是 Go 的替身。** RAG 的 message 域已被判定 **api 侧 5 MISMATCH + 1 OBS**、**rpc 侧 1 MISMATCH + 5 OBS**（`RAG/docs/DRIFT/message/{api,rpc}.md`）⇒ **一切行为判断以 A 列 Go 源码为准**，B 列只提供「RAG 现在长什么样」。
2. **本表只回答「文件名 / 职责对应关系」**，**不下「已对齐 / 未对齐」结论**。行为对齐状态看 `RAG/docs/DRIFT/message/<组件>.md` 的**证据强度列**。
3. **C 列全是空壳。** RAG-V1 的每个 `.java` 目前只有 `package` + 空类型 + `TODO javadoc` ⇒ **C 列每一行写的是「这个位置该放什么职责」，不是「已经有什么」**。

---

## 0. 顶层模块对照

| A (Go) | B (RAG) | C (RAG-V1) | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/message/api/` | `service/message/message-api/` | `service/message/` 的 `service/message/src/main/java/com/rag/message/advice + service/message/src/main/java/com/rag/message/config + service/message/src/main/java/com/rag/message/controller + service/message/src/main/java/com/rag/message/dto + service/message/src/main/java/com/rag/message/interceptor + service/message/src/main/java/com/rag/message/metrics + service/message/src/main/java/com/rag/message/service` 包 | 1:N | C 把 api 折进单 app（D1） |
| `service/message/rpc/` | `service/message/message-rpc/` | `service/message/` 的 `service/message/src/main/java/com/rag/message/bootstrap + service/message/src/main/java/com/rag/message/client + service/message/src/main/java/com/rag/message/config + service/message/src/main/java/com/rag/message/domain/entity + service/message/src/main/java/com/rag/message/domain/service + service/message/src/main/java/com/rag/message/exception + service/message/src/main/java/com/rag/message/grpc + service/message/src/main/java/com/rag/message/mapper + service/message/src/main/java/com/rag/message/metrics + service/message/src/main/java/com/rag/message/service` 包 | 1:N | 同上；`rpc.*` 命名债明确不改 |
| `service/message/common/`（仅 `errmsg.go`） | `service/message/message-common/` | `service/message/src/main/java/com/rag/message/contract/` | 1:1 | 模块改名 `common` → `contract`（承载域内错误契约）；⚠️ **proto 已随 D7 集中到根级 `proto/` 模块** |
| `service/message/rpc/pb/`（goctl 生成物） | `service/message/message-proto/` | `service/message/src/main/java/com/rag/message/contract/`（4 个错误契约类）+ **生成物在根级 `proto/` 模块**（`proto/src/main/proto/message.proto`，pb 包名 `com.rag.proto.message`） | 1:1 | `.proto` 逐字一致，只差 `option java_package`；**D7 后 proto 已从域模块集中到根级 `proto/`** |
| `service/common/`（跨服务共享层） | `service/common/`（普通 jar） | 仓库根 `common/`（真 starter，D3） | 1:1 | B 侧 `service/common` → C 侧根级 starter |
| 进程拓扑：**2 个**（api `0.0.0.0:8892` / rpc `0.0.0.0:8082`） | **2 个**（`message-api` / `message-rpc`） | **1 个**（`message-service`：HTTP `8883` + gRPC `8084` 同进程） | N:1 | D1；D2 域内调用改直连后自我 gRPC 往返消失 |
| — | `service/message/message-common/`（3 类） | `service/message/src/main/java/com/rag/message/contract/`（4 类） | 1:N | `MessageErrorCode/MessageErrorMessages/MessageGrpcError` → `ErrCodes/ErrMsg/CodeError/GrpcErrorMapper` |

> 📌 **C 侧 `service/message/` 的两个子包职责**：`advice + config + controller + dto + interceptor + metrics + service` = HTTP 边界（Controller/DTO/Interceptor/Advice）；`bootstrap + client + config + domain/entity + domain/service + exception + grpc + mapper + metrics + service` = 领域 / 服务端能力层（DomainService/Mapper/Entity/Client）。**合并后 HTTP 与 gRPC 共用同一份业务用例**（`domain/service/MessageDomainService.java`）。

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
| `message/api/`（`MessageApiApplication`） | `com.rag.message`（`MessageServiceApplication.java`） | 入口类合并（D1）|
| `message/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `MessageApiResponseAdvice`）|
| `message/api/client/` | `client/` | 直接压平 |
| `message/api/config/` | `config/` | 与 rpc 侧合并 |
| `message/api/controller/` | `controller/` | 直接压平（**2 个**：Message + Admin）|
| `message/api/dto/` | `dto/` | 直接压平（17 个同名类全落此）|
| `message/api/interceptor/` | `interceptor/` | 直接压平（**2 组 JWT**）|
| `message/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `message/api/protection/` | `common/src/main/java/com/rag/common/protection/` | **上收 `common/`**（D3）|
| `message/api/service/` | `service/` | 与 rpc 侧合并 |
| `message/common/errmsg/` | `contract/` | 错误码（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）|
| `message/rpc/bootstrap/` | `bootstrap/` | 直接压平 |
| `message/rpc/client/` | `client/` | 与 api 侧合并 |
| `message/rpc/config/` | `config/` | 合并（`MessageProperties`）|
| `message/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `message/rpc/exception/` | `exception/` | 直接压平 |
| `message/rpc/mapper/` | `mapper/` | 直接压平（含 `GoStylePagination`）|
| `message/rpc/metrics/` | `metrics/` | 合并 |
| `message/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `message/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `MessageServiceGrpcImpl` → `MessageGrpcService` |
| `message-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.message`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 入口 / 配置 / 装配（api 侧 + 进程拓扑）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/messagecenter.go` | `message-api/.../MessageApiApplication.java` | `service/message/src/main/java/com/rag/message/MessageServiceApplication.java` | 1:1 | 入口；C 侧 `@SpringBootApplication` + `@EnableDiscoveryClient`，api/rpc 合一个进程（D1） |
| `api/messagecenter.go`（`httpx.SetOkHandler` / `httpx.SetErrorHandler` 两个闭包） | `message-api/.../advice/GlobalResponseAdvice.java` + `advice/GlobalExceptionHandler.java` | `service/message/src/main/java/com/rag/message/advice/MessageApiResponseAdvice.java` + `service/message/src/main/java/com/rag/message/advice/MessageApiExceptionHandler.java` | 1:N | 一个 Go main 里的两个闭包 → C 两个 advice 类。**HTTP 恒 200 + `{code,msg,data}` 信封**、业务失败码在 body 里，是 L1 契约 |
| `api/etc/messagecenter.yaml` | `message-api/src/main/resources/application.yml` | `service/message/src/main/resources/application.yml` | 1:1 | yaml 里 `UserAuth` / `AdminAuth` 两段逐字搬到 C 的 `message-api.user-auth` / `message-api.admin-auth` |
| `api/internal/config/config.go` | `message-api/.../config/MessageApiProperties.java` | `service/message/src/main/java/com/rag/message/config/MessageApiProperties.java` | 1:1 | Go `Config{rest.RestConf, UserAuth, AdminAuth, MessageRpc}` → C `@ConfigurationProperties(prefix="message-api")` + 两段 `@NestedConfigurationProperty` |
| `api/internal/svc/servicecontext.go` | `message-api/.../client/MessageRpcClient.java` | `service/message/src/main/java/com/rag/message/service/MessageApiService.java`（直连注入 `MessageDomainService`） | 1:1 | ⚠️ C 侧 `api/client/` **0 个**（D2 域内直连）；Go 的 `ServiceContext` 无独立 Java 文件（被 Spring DI 吸收） |
| `api/internal/metrics/metrics.go` | `message-api/.../metrics/MessageApiMetrics.java` | `service/message/src/main/java/com/rag/message/metrics/MessageApiMetrics.java` | 1:1 | 3 个指标（见 §6）；C 侧 Micrometer 承担注册，**指标名 / 标签 / 调用点**是业务定义 |
| `api/internal/handler/routes.go` | `message-api/.../config/WebMvcConfig.java` + `interceptor/MessageJwtAuthInterceptor.java` + `interceptor/MessageAdminJwtAuthInterceptor.java` | `service/message/src/main/java/com/rag/message/config/WebMvcConfig.java` + `service/message/src/main/java/com/rag/message/interceptor/MessageJwtAuthInterceptor.java` + `service/message/src/main/java/com/rag/message/interceptor/MessageAdminJwtAuthInterceptor.java` | **1:N** | ⚠️ **一组路由 → 两组 JWT 的 1:N 拆分**：`/message/v1` 用 `UserAuth.AccessSecret`、`/message/v1/admin` 用 `AdminAuth.AccessSecret`。C 的 user 拦截器**必须** `excludePathPatterns("/message/v1/admin/**")`，否则 admin token 会被 user key 先拒一遍 |

**1.1 决策锁**

**换「双拦截器 + 双 Auth 配置段」（一组 → 两组，6 处同时改）**：
1. `config/MessageApiProperties.java` 保留 `userAuth` / `adminAuth` 两段嵌套（**不合并成一段**）；
2. `interceptor/MessageJwtAuthInterceptor.java` —— user key，`addPathPatterns("/message/v1/**")`；
3. `interceptor/MessageAdminJwtAuthInterceptor.java` —— admin key，`addPathPatterns("/message/v1/admin/**")`；
4. `config/WebMvcConfig.java` —— user 拦截器加 `excludePathPatterns("/message/v1/admin/**")`；
5. `application.yml` 保留 `message-api.user-auth.*` 与 `message-api.admin-auth.*` **两套** secret / expire；
6. 两套 secret 的值必须与签发方一致（user 段 ← user-api，admin 段 ← admin-api），`AdminAuth` 的 15 字节 secret 需补齐到 32 字节（jjwt HS256 硬要求）。

**为什么换**：Go 侧 `routes.go` 是**两段 `server.AddRoutes`**，各带一把 key 与一个前缀（`rest.WithJwt` + `rest.WithPrefix`）；合成一把就等于取消鉴权边界。**注意这是「一组职责拆成两组」，不是「两份重复代码」**。

---

### 1.2 API 层用例（13 个用例 · DTO 17 个）

#### 1.2.1 handler / logic / Controller / Service

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/internal/handler/message/<8>.go` | `message-api/.../controller/MessageController.java`（8 个 `@PostMapping`） | `service/message/src/main/java/com/rag/message/controller/MessageController.java` | 1:N | Go 8 个 handler → B/C 各 1 个 Controller |
| `api/internal/handler/messageadmin/<5>.go` | `message-api/.../controller/MessageAdminController.java` | `service/message/src/main/java/com/rag/message/controller/MessageAdminController.java` | 1:N | admin 组独立 Controller（两套 JWT ⇒ 两套前缀） |
| `api/internal/logic/message/<8>.go` | `message-api/.../service/Message<Case>Service.java` × 8 + `service/AbstractMessageApiService.java` | `service/message/src/main/java/com/rag/message/service/MessageApiService.java` | N:1 | 13 用例内聚为 1 个 Service（判据见《完全spring化的文件树》§5.4，五条全中） |
| `api/internal/logic/messageadmin/<5>.go` | `message-api/.../service/MessageAdmin<Case>Service.java` × 5 | `service/message/src/main/java/com/rag/message/service/MessageApiService.java` | N:1 | 同上；**user 组硬编码 `SenderRole.USER`、admin 组硬编码 `SenderRole.ADMIN`** 的分工要保留 |
| `api/internal/logic/shared/helper.go` | `message-api/.../service/MessageApiSupport.java` | `service/message/src/main/java/com/rag/message/service/MessageApiSupport.java` | 1:1 | 9 个函数：`UserIDFromContext`（claim `userId`）+ `RPCError` + 6 个 `To*Resp` 投影 + `AdminNotificationKind` |
| （Go 侧无独立文件；`rest.WithJwt` 内置于 go-zero） | `message-api/.../interceptor/AbstractMessageJwtAuthInterceptor.java` | `—（C 侧无对应文件）` | N:1 | ⚠️ **C 侧缺口**：B 用抽象基类收敛两份 JWT 逻辑（防两份安全边界各自演化），C 只有两个独立拦截器类，共享逻辑暂无落点 |

#### 1.2.2 DTO（Go `types.go` 的 17 个 struct ⇒ C 17 个 immutable record）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/internal/types/types.go`（文件级） | `message-api/.../dto/*.java` × 18 | `service/message/src/main/java/com/rag/message/dto/*.java` × 17 | 1:N | C 去掉 B 侧的 `GoStyleStringInt64`（降级到 `common/src/main/java/com/rag/common/web/`） |
| `types.go` → `AdminSendChatReq` | `dto/AdminSendChatReq.java` | `service/message/src/main/java/com/rag/message/dto/AdminSendChatReq.java` | 1:1 | `recipient_id` / `conversation_id` 带 `,string` |
| `types.go` → `AdminSendNotificationReq` | `dto/AdminSendNotificationReq.java` | `service/message/src/main/java/com/rag/message/dto/AdminSendNotificationReq.java` | 1:1 | `broadcast` / `target_id` / `kind` / `extra` 均 `optional` |
| `types.go` → `AdminSendNotificationResp` | `dto/AdminSendNotificationResp.java` | `service/message/src/main/java/com/rag/message/dto/AdminSendNotificationResp.java` | 1:1 | ⚠️ `affected` 的 JSON 形态存在**已签字偏离**，见 §6 注 |
| `types.go` → `ChatMessageItem` | `dto/ChatMessageItem.java` | `service/message/src/main/java/com/rag/message/dto/ChatMessageItem.java` | 1:1 | 9 字段；`id` / `sender_id` / `recipient_id` 带 `,string` |
| `types.go` → `ConversationItem` | `dto/ConversationItem.java` | `service/message/src/main/java/com/rag/message/dto/ConversationItem.java` | 1:1 | 12 字段 |
| `types.go` → `ConversationListResp` | `dto/ConversationListResp.java` | `service/message/src/main/java/com/rag/message/dto/ConversationListResp.java` | 1:1 | `items` + `total` + `unread_count` |
| `types.go` → `ConversationMessagesReq` | `dto/ConversationMessagesReq.java` | `service/message/src/main/java/com/rag/message/dto/ConversationMessagesReq.java` | 1:1 | `offset` / `limit` 为 `optional` |
| `types.go` → `ConversationMessagesResp` | `dto/ConversationMessagesResp.java` | `service/message/src/main/java/com/rag/message/dto/ConversationMessagesResp.java` | 1:1 | 带 `status` / `can_send` / `pending_sender_*` |
| `types.go` → `EmptyReq` | `dto/EmptyReq.java` | `service/message/src/main/java/com/rag/message/dto/EmptyReq.java` | 1:1 | 空 body（`/notifications/readall` 与 `/unread` 用） |
| `types.go` → `MarkConversationReadReq` | `dto/MarkConversationReadReq.java` | `service/message/src/main/java/com/rag/message/dto/MarkConversationReadReq.java` | 1:1 | |
| `types.go` → `MarkNotificationReq` | `dto/MarkNotificationReq.java` | `service/message/src/main/java/com/rag/message/dto/MarkNotificationReq.java` | 1:1 | |
| `types.go` → `NotificationItem` | `dto/NotificationItem.java` | `service/message/src/main/java/com/rag/message/dto/NotificationItem.java` | 1:1 | 含 `extra`（`map[string]string`） |
| `types.go` → `NotificationListResp` | `dto/NotificationListResp.java` | `service/message/src/main/java/com/rag/message/dto/NotificationListResp.java` | 1:1 | `items` + `total` + `unread_count` |
| `types.go` → `PageReq` | `dto/PageReq.java` | `service/message/src/main/java/com/rag/message/dto/PageReq.java` | 1:1 | `offset` / `limit` / `unread_only` 全 `optional` |
| `types.go` → `SendChatResp` | `dto/SendChatResp.java` | `service/message/src/main/java/com/rag/message/dto/SendChatResp.java` | 1:1 | |
| `types.go` → `UnreadSummaryResp` | `dto/UnreadSummaryResp.java` | `service/message/src/main/java/com/rag/message/dto/UnreadSummaryResp.java` | 1:1 | `notification_unread` / `conversation_unread` / `total_unread` |
| `types.go` → `UserSendChatReq` | `dto/UserSendChatReq.java` | `service/message/src/main/java/com/rag/message/dto/UserSendChatReq.java` | 1:1 | `recipient_id` / `conversation_id` 为 `optional` |
| `types.go` 的 `,string` 整数字段编码 | `message-api/.../dto/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | 1:1 | D3：共享件下沉到根级 starter |

**1.2 决策锁**

**换「13 个 logic ⇒ 1 个 Service」（N:1，4 处同时改）**：
1. 删 B 侧 `message-api/.../service/Message<Case>Service.java` × 13 与 `AbstractMessageApiService.java`；
2. C 侧 `service/MessageApiService.java` 承接 13 个用例的**编排**（uid 解析 → 硬编码 role → 调 DomainService → 投影 DTO → 埋点）；
3. 业务规则下沉到 `domain/service/MessageDomainService.java`（HTTP 与 gRPC **共享同一份**）；
4. `service/MessageApiSupport.java` 保留 `UserIDFromContext` + `To*Resp` 投影 + 错误码映射。

**为什么换**：Go 侧 13 个 `logic` 文件是 **goctl 按 RPC 生成的样板产物**，不是职责边界；合并后「构造 logic + 转交」这一层消失。⚠️ 但**业务规则密集的部分不适用合并**（rpc 侧的 `MessageRpcSupport` / `MessageAtomicService` 是协作者与事务边界类，必须保持独立）。

**换「可变 struct ⇒ immutable record」（D5，3 处同时改）**：
1. `dto/*.java` 全部改 `record`（不可变）；
2. JSON 字段名从 Go 的 `json:"xxx_yyy"` tag 迁到 `@JsonProperty` / 组件名；
3. `,string` 的整数字段用 `common/src/main/java/com/rag/common/web/StringInt64.java` 的解码口径统一处理。

**为什么换**：Go 的 struct 是可变容器（logic 里逐字段赋值）；Java 侧 record 表达「一次构造即完整」，且与 Bean Validation（`@NotNull` / `@NotBlank`）配合更顺。⚠️ 文案与「必填」判定属 L3 降级（Bean Validation 文案 ≠ go-zero `httpx.Parse` 文案）。

---

### 1.3 RPC / 领域层

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/message.go` | `message-rpc/.../MessageRpcApplication.java` + `rpc/bootstrap/NacosRegistration.java` | `service/message/src/main/java/com/rag/message/MessageServiceApplication.java` + `service/message/src/main/java/com/rag/message/bootstrap/NacosRegistration.java` | 1:N | C 侧入口 1 个（合并 D1）；Nacos 显式注册保留（替代 Go 的 etcd） |
| `rpc/etc/message.yaml` | `message-rpc/src/main/resources/application.yml` + `rpc/config/MessageProperties.java` | `service/message/src/main/resources/application.yml` + `service/message/src/main/java/com/rag/message/config/MessageProperties.java` | 1:1 | `List{DefaultLimit:20, MaxLimit:100}` + `Broadcast{PageSize:200}` 逐字对齐 |
| `rpc/internal/config/config.go` | `message-rpc/.../config/MessageProperties.java` | `service/message/src/main/java/com/rag/message/config/MessageProperties.java` | 1:1 | ⚠️ Go 侧**没有** `*RpcProperties` 这种命名，配置块名是 `Config`；RAG/C 用 `@ConfigurationProperties(prefix="message")` 承载。`Postgres` / `UserRpc` / `AdminRpc` 由 `spring.datasource` / `grpc.client.*` 接管 |
| `rpc/internal/svc/servicecontext.go` | `message-rpc/.../client/UserRpcClient.java` + `client/AdminRpcClient.java` | `service/message/src/main/java/com/rag/message/client/UserRpcClient.java` + `service/message/src/main/java/com/rag/message/client/AdminRpcClient.java` | 1:1 | 两个都是**跨域**下游（user 域 / admin 域）⇒ D6 保持 gRPC；`loadProfile(role,id)` / `collectBroadcastRecipients(pageSize)` |
| `rpc/internal/server/messageserviceserver.go` | `message-rpc/.../service/impl/MessageRpcServiceImpl.java` | `service/message/src/main/java/com/rag/message/grpc/MessageGrpcService.java` | 1:1 | 9 个方法薄转发（proto ↔ 领域模型转换 + 指标转发 + 异常映射） |
| `rpc/internal/logic/<9 个用例>.go` | `message-rpc/.../service/MessageDomainService.java` | `service/message/src/main/java/com/rag/message/domain/service/MessageDomainService.java` | N:1 | 9 用例合 1 个 DomainService；`sendchatmessagelogic.go` 最复杂（会话状态机 + PENDING 单方连发拦截 + 首条 ≤ 50 rune 校验） |
| `rpc/internal/logic/helper.go` | `message-rpc/.../service/MessageRpcSupport.java` | `service/message/src/main/java/com/rag/message/service/MessageRpcSupport.java` | 1:1 | 20+ helper：`canonicalParticipants` / `canSend` / `peerOf` / `loadProfile` / `collectBroadcastRecipients` / `dedupeRecipients` / `trimPreview` / `notificationKindLabel` / `successBaseResp` … |
| `rpc/internal/model/message.go`（15 个 `MessageModel` 方法） | `message-rpc/.../mapper/ConversationMapper.java` + `mapper/ConversationMessageMapper.java` + `mapper/NotificationMapper.java` + `mapper/GoStylePagination.java` + `service/MessageAtomicService.java` | `service/message/src/main/java/com/rag/message/mapper/ConversationMapper.java` + `service/message/src/main/java/com/rag/message/mapper/ConversationMessageMapper.java` + `service/message/src/main/java/com/rag/message/mapper/NotificationMapper.java` + `service/message/src/main/java/com/rag/message/mapper/GoStylePagination.java` + `service/message/src/main/java/com/rag/message/domain/service/MessageAtomicService.java` | 1:N | 2 个 `conn.Transaction(...)` 闭包 + 3 个单条原子 UPDATE → `MessageAtomicService` 的 6 个 `@Transactional` 方法（**独立 Bean**，见 §2）；`GoStylePagination` 承接 Go 的「offset / limit 各自有条件」分页口径 |
| `rpc/internal/model/data_model.go` | `message-rpc/.../domain/ConversationEntity.java` + `.../ConversationMessageEntity.java` + `.../NotificationEntity.java` | `service/message/src/main/java/com/rag/message/domain/entity/ConversationEntity.java` + `.../ConversationMessageEntity.java` + `.../NotificationEntity.java` | 1:N | 3 张表；Go 的 `JSONMap`（`driver.Valuer`/`Scanner`）→ `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` |
| `rpc/internal/model/db.go`（`InitDB` + `AutoMigrate`） | `message-rpc/.../mapper/ConversationMapper.java` + `mapper/ConversationMessageMapper.java` + `mapper/NotificationMapper.java` + `resources/db/migration/V1..V5*.sql` | `service/message/src/main/java/com/rag/message/mapper/ConversationMapper.java` + `service/message/src/main/java/com/rag/message/mapper/ConversationMessageMapper.java` + `service/message/src/main/java/com/rag/message/mapper/NotificationMapper.java` + `resources/db/migration/`（**当前仅 `.gitkeep`**） | 1:N | ⚠️ C 侧 DDL 目录**是空的**；设计文档 §5.2 要求的 `rpc/bootstrap/MessageSchemaInitializer.java`（幂等 DDL 重放）在 C 侧**不存在**（见 §7 缺口清单） |
| `rpc/internal/metrics/metrics.go` | `message-rpc/.../metrics/MessageMetrics.java` | `service/message/src/main/java/com/rag/message/metrics/MessageRpcMetrics.java` | 1:1 | 5 个指标 `message_rpc_*` |
| — | — | `service/message/src/main/java/com/rag/message/metrics/MessageMetrics.java` | C 新增 | ⚠️ C 侧**多出一个空壳** `MessageMetrics.java`，与 `MessageRpcMetrics.java` 职责重叠（后者已列全 5 个指标名）⇒ 待清理或明确分工 |
| `rpc/messageservice/messageservice.go`（goctl client stub，9 方法） | `message-api/.../client/MessageRpcClient.java` | `—（C 侧无对应手写文件）` | N:1 | D2 域内 api→rpc 改直连 ⇒ 域内 stub 无调用点；stub 仅作为对外契约留在根级 `proto/` 的生成物里 |
| `rpc/pb/message.pb.go` + `rpc/pb/message_grpc.pb.go` | `message-proto/`（`message.proto` + 生成物） | `service/message/src/main/java/com/rag/message/contract/`（`message.proto` + 生成物） | 1:1 | `.proto` 与 Go 侧逐字一致（只差 `option java_package`）；生成物**不计入 C 列 51 个手写 `.java`** |
| —（Go 侧无独立文件：GORM 错误直接 err 透传） | `message-rpc/.../exception/MessageExceptions.java` | `service/message/src/main/java/com/rag/message/exception/MessageExceptions.java` | 1:1 | B 侧把 GORM 错误包成 unchecked exception（`DbSelectException` / `DbUpdateException` / `TransactionRollbackException` / `DatabaseException`）⇒ Go 侧无对应文件 |

**1.3 决策锁**

**换「事务边界独立成 Bean」（Go 闭包 → Java 独立 Bean，4 处同时改）**：
1. `domain/service/MessageAtomicService.java` 承载 6 个 `@Transactional` 方法；
2. `domain/service/MessageDomainService.java` **通过注入调用**（不得 `this.` 自调用）；
3. 事务方法签名与 Go 的 `CreateConversationWithMessageTx` / `CreateOrSendMessageTx` 一一对应；
4. 断言层：必须有「事务真的生效」的守门测试（真 PG），不能只看 `@Transactional` 注解存在。

**为什么换**：Spring 的 `@Transactional` 靠 AOP 代理生效，**代理只拦截从容器外部进来的调用**；Go 的 `m.conn.Transaction(func(tx *gorm.DB) error {...})` 是显式闭包，跨对象调用不受此限。⇒ 自调用会让注解**静默失效**（代码看着有事务、运行时是 auto-commit、不报错）。

**换「rpc 侧协作者不合并」（1 处决策 + 3 处保持独立）**：
1. `MessageRpcSupport`（常量表 + 算法片段）保持独立；
2. `MessageAtomicService`（事务边界）保持独立；
3. `GoStylePagination`（分页子句口径）保持独立；
4. 只有 9 个**用例**合并进 `MessageDomainService`。

**为什么换**：协作者与事务边界的变化原因独立（`GoStylePagination` 只随分页口径变，`MessageRpcSupport` 只随角色/状态机变），合并会让这些规则埋进一个大类。

---

### 1.4 错误契约（`service/message/common/errmsg.go`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `common/errmsg.go`（const 块 22 个码 + `codeMsg` map + `GRPCError` + `BizCodeFromError`） | `message-common/.../errmsg/MessageErrorCode.java` + `MessageErrorMessages.java` + `MessageGrpcError.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `ErrMsg.java` + `CodeError.java` + `GrpcErrorMapper.java` | 1:N | ⚠️ **文案是英文**（`"service busy"` / `"internal server error"` / `"unauthorized"`）⇒ 与 article / comment / favorite / like / user 域的中文文案**逐域隔离，不可统一化**；C 侧 4 个类的 javadoc 已把这条写成硬约束 |
| `common/errmsg.go` 的 `CodeError{Code,Msg}`（同时是 error 与 JSON 体 `{code,msg}`） | `MessageGrpcError.CodeError`（内部类） | `service/message/src/main/java/com/rag/message/contract/CodeError.java`（顶层类） | 1:1 | C 把它提到顶层 ⇒ 与 C 侧统一错误模型（`BusinessException`）并存 |
| `common/errmsg.go` 的 `BizCodeFromError` 反查 | `MessageGrpcError.bizCodeFromError(Throwable)` | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` | 1:1 | 反查顺序：先按 message 反查 code → 查不到才按 gRPC 标准码 fallback（`InvalidArgument→5003` / `Unauthenticated→5004` / `PermissionDenied→5005` / `AlreadyExists→5006` / `NotFound→5002` / `Internal→5001` / default→`1015`） |
| `common/errmsg.go` 的 `GRPCError(grpcCode, bizCode)` | `MessageGrpcError.grpcError(Status.Code, int)` | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` | 1:1 | Go 是 `status.Error(grpcCode, GetErrMsg(bizCode))` —— **业务码塞进 gRPC status 的 description 字段** |
| `common/errmsg.go` 的 `codeMsg` map | `MessageErrorMessages`（静态 map） | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | 22 条文案逐字照抄 Go |
| `common/errmsg.go` 的 const 块 | `MessageErrorCode`（enum，21 常量 + `SUCCESS`） | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | 见 §6 码值表 |

**1.4 决策锁**

**换「错误契约逐域隔离」（C 新增约束，4 处同时改）**：
1. `ErrCodes.java` 只放 message 域的 22 个码，**不引入共享基座**；
2. `ErrMsg.java` 只放 message 域的英文文案，**不与中文域合并**；
3. `GrpcErrorMapper.java` 的 fallback 表按 message 域的码值空间写死；
4. `CodeError.java` 保持 `{code,msg}` 双角色（error + JSON 体）。

**为什么换**：同一语义在不同域的**码值不同、文案语言也不同**（`ErrorDbUpdate`：favorite=`5008` / comment·like·user·article=`5002`；`CodeServerBusy`：message=`"service busy"` / article·comment·favorite·like·user=`"服务繁忙"`）⇒ 统一化会直接改掉 8 个域的可见行为（L1）。

---

### 1.5 B 有、C 无 / B 有、C 换位置（共享件下沉清单）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| （Go `httpx.Parse` 类型判定） | `message-api/.../config/GoStyleMapStringValueDeserializer.java` | `common/src/main/java/com/rag/common/web/MapStringValueDeserializer.java` | 1:1 | D3：下沉到根级 starter |
| （Go `httpx.Parse` 类型判定） | `message-api/.../config/MessageGoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:N | 同上；C 侧改成 Jackson 3 的 `CoercionConfig` 装配点 |
| `rpc/internal/model/data_model.go` 的 `JSONMap` | `message-rpc/.../mapper/JsonbMapTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | 1:1 | 同上 |
| （Spring Boot 4 装配桥） | `message-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 1:1 | B 侧 9 份副本 → C 侧 1 份（D3 的直接账单） |
| `service/common/response/response.go`（信封） | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | 共享层整体下沉；**信封是 L1，不能换成 `ProblemDetail`** |
| `service/common/observability/*`（http/mq/rpc 三条链） | `service/common/.../observability/` | `common/src/main/java/com/rag/common/observability/`（3 类：Observation + `GoStyleRequestDeadline` + `ObservabilityCustomizer`） | 1:N | Micrometer `Observation` + OTel bridge 吸收 |
| `service/common/logger/logger.go` | `service/common/.../logger/` | `common/src/main/java/com/rag/common/logger/`（4 类：SLF4J 适配器 + 日志选项） | 1:N | 格式与字段保留，生态换成 SLF4J |
| `service/common/snowflake/snowflake.go` | `service/common/.../snowflake/` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration.java` | 1:1 | epoch / 位段是业务契约 |
| `service/common/errmsg` / `xcode`（跨域共享信封与码） | `service/common/.../errmsg/` + `xcode/` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` + `common/src/main/java/com/rag/common/exception/BusinessException.java` + `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | N:1 | C 侧全局信封与业务异常基类在**根级 common**，**message 域不得反向定义** |

---

## 2. 持久层差异

| 维度 | A Go（GORM） | B RAG（MyBatis-Plus + Flyway） | C RAG-V1（目标形态） | 备注 |
|---|---|---|---|---|
| ORM | `gorm.io/driver/postgres` v1.6.0 + GORM | MyBatis-Plus `BaseMapper<Entity>` + `LambdaQueryWrapper` | 同 B | D4「生态优先」 |
| 实体注解 | `gorm:"column:...;not null;uniqueIndex:..."` struct tag | `@TableName` + `@TableId(type=IdType.ASSIGN_ID)` + `@TableField` | 同 B | 表名/列名显式声明 |
| JSONB 列 | `JSONMap`（`driver.Valuer` / `Scanner` 自写） | `JsonbMapTypeHandler`（手写 `BaseTypeHandler`） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | ⚠️ **不能用 MP 自带 `JacksonTypeHandler`**：它走 `ps.setString()` ⇒ PG 收到 VARCHAR ⇒ 写 `jsonb` 列被拒 |
| Schema 演进 | `db.AutoMigrate(&Notification{}, &Conversation{}, &ConversationMessage{})`（每次启动校验/补建，无账本） | Flyway `V1..V5`（`table: flyway_schema_history_message` 独立账本，`baseline-version: 0`） | 幂等 DDL 重放（`resources/db/migration/`，**当前为空**） | ⚠️ Go 无账本 ⇒ **C 侧 DDL 脚本还没落地** |
| 建表责任 | Go 侧 `InitDB` 独占 | message-rpc 独占（Flyway） | 待定（设计文档 §5.2 指向 `MessageSchemaInitializer`，**文件不存在**） | 共享库共存问题见 `RAG/docs/DRIFT/_cross/` |
| 雪花 ID | `snowflake.Init()` + `model` 内 `snowflake.GetID()` | `SnowflakeBean` 注入（`common` starter） | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | epoch/位段是契约 |
| 事务边界 | `m.conn.WithContext(ctx).Transaction(func(tx *gorm.DB) error {...})` **显式闭包**（`model/message.go:187` / `:205`），跨对象调用 | `MessageAtomicService` **独立 Bean** + `@Transactional`（6 个方法） | 同 B | ⚠️ 自调用 ⇒ 注解静默失效 |
| 单条原子 UPDATE | `MarkNotificationRead` / `MarkAllNotificationsRead` / `MarkConversationRead` 都是**单条 UPDATE** + `WHERE`，不先 SELECT | `MessageAtomicService.markNotificationReadTx` / `markAllNotificationsReadTx` / `markConversationReadTx` 同款单条 UPDATE | 待填 | ⚠️ B 侧曾把 `MarkConversationRead` 拆成「message 表 UPDATE + conversation 表 `touchUpdatedAt`」**两次写**（已修）—— Go 侧只改 message 表，**不顺带改写 `conversation.updated_at`** |
| 分页子句 | `if offset > 0 { db.Offset(...) }` / `if limit > 0 { db.Limit(...) }` —— **两段各自有条件**（`model/message.go:59-64` / `:127-131` / `:164-168`） | `mapper/GoStylePagination.java`（显式复刻该判据） | `service/message/src/main/java/com/rag/message/mapper/GoStylePagination.java` | ⚠️ 无条件拼 `LIMIT 20 OFFSET -1` ⇒ PG 直接报错（`OFFSET must not be negative`）⇒ 非法参数被降级成基础设施故障 |
| 排序 | `ListConversations` 双键：`ORDER BY last_message_at desc, updated_at desc` | `ConversationMapper.listByParticipant`（次级键 `updated_at` 的取舍曾登记为漂移项） | 待填 | 次级键影响 `ConversationItem.updated_at` 的取值 |
| 计数口径 | `ListNotifications` 的 `total` / `unread` 在**同一条 query 链**上算（`unread` **不受** `unread_only` 过滤影响） | `NotificationMapper` 同链三计数 | 待填 | ⚠️ `total` 被 `unread_only` 过滤过一次（已登记为 rpc 侧 MISMATCH） |
| 时间列 | Go 侧 `time.Time` ⇒ `timestamptz`（driver v1.6.0 消费点） | Flyway `V5__message_time_columns_timestamptz.sql`（8 列 `TIMESTAMP ⇒ TIMESTAMPTZ`，转换时区 `Asia/Shanghai`） | 待填 | 会话时区必须钉 `Asia/Shanghai`（Go 的 DSN 就钉死它） |
| 唯一性对象 | GORM `uniqueIndex:uk_conversation_pair` ⇒ 建出来是**唯一索引**（`pg_constraint` 里没有） | Flyway `V4` 显式 `CREATE UNIQUE INDEX IF NOT EXISTS`（不是 `ADD CONSTRAINT`） | 待填 | 对象类型不同（约束 vs 索引）会被运维工具看出差别 |

**2 决策锁**

**换「Flyway ⇒ 幂等 DDL 重放」（3 处同时改）**：
1. `service/message/src/main/resources/db/migration/` 补 3 张表的建表脚本 + 索引（**当前只有 `.gitkeep`**）；
2. 新增 `bootstrap/MessageSchemaInitializer.java`（**当前 C 侧不存在**）执行幂等重放；
3. `application.yml` 去掉 Flyway 相关配置，改为 `SchemaInitializer` 装配。

**为什么换**：Go 的 `AutoMigrate` 是「每次启动校验/补建、无账本」；Flyway 是「一次性迁移 + schema 级账本」，而 message 与 like / article 共用同一个 `first_db` / `public` schema ⇒ 账本会撞号（`RAG` 的 AGENTS.md §6.3 问题 4）。幂等重放接近 `AutoMigrate` 语义，且没有账本。

**换「GORM ⇒ MyBatis-Plus」（5 处同时改）**：
1. `domain/entity/*Entity.java` 换注解（`@TableName` + `@TableId`），字段名不变；
2. `mapper/*Mapper.java` 继承 `BaseMapper<Entity>`，调用点从 GORM 链式改 `LambdaQueryWrapper`；
3. `mapper/GoStylePagination.java` 承担分页子句口径；
4. `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` 承担 jsonb 列；
5. 建表来源从 `AutoMigrate` 改幂等脚本。

**为什么换**：MyBatis-Plus 的 `LambdaQueryWrapper` 比 GORM 链式更直观，且显式 SQL 可被逐条审；但**ORM 换掉不等于语义换掉** —— 每条查询条件、排序键、计数口径都必须逐条回到 Go 源码对照。

---

## 3. HTTP / DI 差异

| 维度 | A Go | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| 路由注册 | `handler/routes.go` 两段 `server.AddRoutes(...)` + `rest.WithJwt` + `rest.WithPrefix` | `WebMvcConfig.addInterceptors` + Controller 上的 `@RequestMapping` / `@PostMapping` | 同 B | Spring 注解式路由吸收 go-zero 代码式注册 |
| 前缀 | `/message/v1`（8 个）+ `/message/v1/admin`（5 个） | 同（逐条一致） | 待填 | **两组前缀都是 L1，逐字保留** |
| 端点总数 | **13**（8 user + 5 admin） | 13 | 13 | 方法分布 **GET 1 + POST 12** |
| Controller | 13 个 handler（每 ~30 行：parse → 调 logic → `httpx.OkJson`） | `MessageController`（8 `@PostMapping`）+ `MessageAdminController`（5） | 同 B | handler 层不做业务，只做 parse + 信封 |
| Service 分层 | Handler → Logic（13 个）→ gRPC → rpc logic | Controller → 13 个 `*Service` → `MessageRpcClient`（gRPC） | Controller → `MessageApiService` → `MessageDomainService`（**直连**） | D2：域内不再自我 gRPC 往返 |
| 错误表达 | `httpx.Error` ⇒ **HTTP 恒 200** + `{code,msg,data}` 信封（`messagecenter.go` 的 `SetErrorHandler`） | `GlobalResponseAdvice` + `GlobalExceptionHandler` | `MessageApiResponseAdvice` + `MessageApiExceptionHandler` | ⚠️ **不是 text/plain**：go-zero 路由级错误（404 / 框架层）才是 text/plain，业务错误恒 200 + 信封 |
| 统一错误模型 | api 层把 gRPC status 映射回业务码（`shared.RPCError` + `BizCodeFromError`） | 同款映射（`MessageGrpcError`） | `BusinessException(code,msg)` + 2 个边界适配器（HTTP advice / gRPC mapper） | D2 附带的统一错误模型（§5.3） |
| JWT 校验 | `rest.WithJwt(secret)`（go-zero 内置，验签 + 解析 claims 写 ctx） | `AbstractMessageJwtAuthInterceptor` + 2 个子类 | `MessageJwtAuthInterceptor` + `MessageAdminJwtAuthInterceptor`（**无抽象基类**） | ⚠️ claim 取值判定属业务层（`UserIDFromContext` 失败 ⇒ 业务码 5004 + HTTP 200），**不是 401** |
| claim 名称 | `"userId"`（`api/internal/logic/shared/helper.go:14` `ctx.Value("userId").(json.Number)`） | 同 | 待填 | 小写 u、驼峰式，**不能写成 `user_id`** |
| 配置绑定 | `conf.MustLoad(*configFile, &c)` 强转进 `config.Config` struct | `@ConfigurationProperties`（`message-api` / `message` 两个前缀） | 同 B | 类型安全 + IDE 补全 |
| 装配根 | `svc.NewServiceContext(c)`（static 风格 struct） | Spring DI（`@Component` / `@Bean`），**无 ServiceContext 类** | 同 B | go-zero 的 `ServiceContext` 被 DI 容器吸收 |
| 服务发现 | etcd（`message.rpc` / `user.rpc` / `admin.rpc` flat key） | Nacos（`discovery:///message-rpc` 等） | Nacos（`spring.cloud.nacos.discovery`） | 寻址语义等价 |
| gRPC 明文 | `zrpc.MustNewServer` 全仓无 TLS 配置 ⇒ 明文 | devh 默认 TLS ⇒ 必须显式 `negotiation-type: plaintext` | 同 B | ⚠️ 不写会静默发 TLS ClientHello |
| 进程拓扑 | api（8892）/ rpc（8082）**两个进程** | 两个进程 | **一个进程**（HTTP `8883` + gRPC `8084`） | D1 |
| 熔断 | go-zero `rest` 内置（默认开启，`zrpc` 侧 `Breaker default=true`） | resilience4j | `common/src/main/java/com/rag/common/protection/` + resilience4j | ⚠️ 失败窗口 / half-open 策略不同（L3 降级） |
|CPU 降载|go-zero `rest` 内置 `CpuThreshold`（默认 900‰）|`common/protection/CpuThresholdHealthIndicator`|同 B|同上|
|请求体上限|go-zero `MaxBytes`（默认 1 MiB，只看 Content-Length）|`common/protection/MaxBytesFilter`|同 B|chunked 请求会绕过（刻意保留）|

**3 决策锁**

**换「api + rpc 两个进程 ⇒ 一个进程」（D1，8 处同时改）**：
1. `MessageServiceApplication.java` 一个入口（`@SpringBootApplication` + `@EnableDiscoveryClient`）；
2. `advice + config + controller + dto + interceptor + metrics + service` 与 `bootstrap + client + config + domain/entity + domain/service + exception + grpc + mapper + metrics + service` 两个子包共处一个模块；
3. `service/MessageApiService.java` **直连注入** `domain/service/MessageDomainService.java`（不注入 stub）；
4. `{advice,config,controller,dto,interceptor,metrics,service}/client/` 整个包清空；
5. `application.yml` 同时配 `server.port`（HTTP）与 `grpc.server.port`（gRPC）；
6. Nacos 注册只注册**一个**服务名；
7. 错误表达统一到一个 `BusinessException` + 2 个边界适配器；
8. 观测：api 与 rpc 的指标 namespace 仍是 `message_api_*` / `message_rpc_*`（**不改名**）。

**为什么换**：Spring Cloud 的部署单元是「可部署服务」；go-zero 的 api/rpc 分离源于 `.api` 文件与 `.proto` 两条 codegen 路径，是**语言产物**而非架构原则。代价：「rpc 进程不可用」这一故障模式消失、域的独立扩容能力消失。

**换「gRPC code ⇒ 业务码的两层映射 ⇒ 一个业务异常」（D2 附带，4 处同时改）**：
1. 业务层只抛 `BusinessException(code,msg)`；
2. gRPC 边界由 `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` 转 `StatusRuntimeException`；
3. HTTP 边界由 `advice/MessageApiExceptionHandler.java` 直接读 `BusinessException.code`；
4. `contract/GrpcErrorMapper.java` 保留「message 反查 code → gRPC 标准码 fallback」的域内反查表。

**为什么换**：合并后同一份业务逻辑要同时产出 HTTP 与 gRPC 两种错误表达 ⇒ 业务码只能有一份，两个边界各做一次转换。

---

## 4. 公共模块对照（message 域 ↔ `common/` 的边界）

| 职责 | A Go（`service/common/`） | B RAG（`service/common/`） | C RAG-V1（根级 `common/` starter） | 边界规则 |
|---|---|---|---|---|
| HTTP 信封 `{code,msg,data}` | `common/response/response.go` | `common/response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | **信封是 L1**，客户端按它解析 ⇒ 不可换 `ProblemDetail` |
|业务异常基类|（Go 无基类，用 `*CodeError`）|`common/exception/BusinessException.java`|`common/src/main/java/com/rag/common/exception/BusinessException.java` + `AuthenticationException.java`|域内**不重复定义**|
| JWT 工具 | `common/jwt/` | `users-common/.../jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` + `BearerTokenExtractor.java` | message 域**只验签不签发** |
|雪花 ID|`common/snowflake/snowflake.go`|`common/snowflake/`|`common/src/main/java/com/rag/common/snowflake/Snowflake.java`|epoch / 位段是契约（like 域游标依赖 `>>22`）|
| 观测（http/rpc/mq） | `common/observability/` | `common/observability/` | `common/src/main/java/com/rag/common/observability/`（3 类） | span 名称/属性需逐条对拍 |
| 熔断 / CPU 降载 / MaxBytes | go-zero `rest` 内置 | `common/protection/` | `common/src/main/java/com/rag/common/protection/`（2 类） | 生态缺口，策略差异属 L3 |
|日志|`common/logger/logger.go`|`common/logger/`|`common/src/main/java/com/rag/common/logger/`（4 类，SLF4J）|`call_chain` 等是行为契约|
| JSONB TypeHandler | （Go 侧在 `message/rpc/internal/model/data_model.go`） | `message-rpc/.../mapper/JsonbMapTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | **从域内提升到共享层** |
| 整数字段解码（`,string`） | （Go 侧在 `types.go` 的 tag） | `message-api/.../dto/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | 同上 |
| Jackson 类型强转口径 | （Go 侧在 `httpx.Parse`） | `message-api/.../config/{GoStyleMapStringValueDeserializer,MessageGoStyleCoercion}.java` | `common/src/main/java/com/rag/common/web/MapStringValueDeserializer.java` + `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 同上 |
| MyBatis-Plus 装配桥 | — | `message-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | B 侧 9 份副本 → C 侧 1 份 |
| **域内错误码 / 文案 / gRPC 转换** | `service/message/common/errmsg.go` | `message-common/.../errmsg/` | **`service/message/src/main/java/com/rag/message/contract/{ErrCodes,ErrMsg,CodeError,GrpcErrorMapper}.java`** | ⚠️ **不共享**！逐域隔离（英文文案） |

**4 决策锁**

**换「共享件从域内提到 starter」（D3，6 处同时改）**：
1. `JsonbMapTypeHandler` → `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`；
2. `GoStyleStringInt64` → `common/src/main/java/com/rag/common/web/StringInt64.java`；
3. `GoStyleMapStringValueDeserializer` + `MessageGoStyleCoercion` → `common/src/main/java/com/rag/common/web/`；
4. `MybatisPlusOrderingBridgeAutoConfiguration` → `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`；
5. 删各域的 `@Import` 清单，改由 `AutoConfiguration.imports` 接管（`@ConditionalOnMissingBean` 语义随之变化）；
6. 域内**不**保留这些类的副本。

**为什么换**：B 侧 `service/common` 是普通 jar ⇒ 每个消费者手写 `@Import` 清单 ⇒ 同一个 MP 装配桥被复制 9 遍。⚠️ **`@Import` 与 auto-config 不能混用**（`@Import` 的那一份按 user config 更早求值，会改变 `@ConditionalOnMissingBean` 的语义）⇒ 要么全删 `@Import`，要么保持现状。

---

## 5. 决策锁总表

> 各条的完整「N 处同时改」清单见对应小节：§1.1（双 JWT）· §1.2（用例内聚 / DTO record）· §1.3（事务独立 Bean / 协作者不合并）· §1.4（错误契约隔离）· §2（幂等 DDL / MyBatis-Plus）· §3（D1 合并进程 / 统一错误模型）· §4（共享件下沉）。

| # | 决策 | 涉及节 | N 处同时改 | 为什么换（一句话） |
|---|---|---|---|---|
| 1 | **双 JWT 拆成两组**（`/message/v1` UserAuth · `/message/v1/admin` AdminAuth） | §1.1 | 6 | Go 是两段 `AddRoutes` 各带一把 key，合成一把等于取消鉴权边界 |
| 2 | **13 个 logic ⇒ 1 个 `MessageApiService`** | §1.2 | 4 | Go 的 13 个 logic 是 goctl 生成样板，不是职责边界 |
| 3 | **DTO ⇒ immutable record**（D5） | §1.2 | 3 | 可变 struct → record，配合 Bean Validation |
| 4 | **事务边界独立成 `MessageAtomicService`** | §1.3 / §2 | 4 | Spring `@Transactional` 自调用会**静默失效**，Go 闭包无此问题 |
| 5 | **rpc 侧协作者保持独立**（`MessageRpcSupport` / `GoStylePagination` / `MessageAtomicService`） | §1.3 | 4 | 协作者与事务边界的变化原因独立 |
| 6 | **错误契约逐域隔离**（英文文案） | §1.4 | 4 | 8 个域的码值与文案语言都不同，统一化会改掉可见行为 |
| 7 | **Flyway ⇒ 幂等 DDL 重放** | §2 | 3 | Go 的 `AutoMigrate` 无账本；Flyway 的 schema 级账本在共享库会撞号 |
| 8 | **GORM ⇒ MyBatis-Plus** | §2 | 5 | 显式 SQL 可逐条审；但每条查询条件仍要回 Go 源码对照 |
| 9 | **api + rpc 合并为单进程**（D1） | §3 | 8 | Spring 的部署单元是「可部署服务」，go-zero 的 api/rpc 分离是语言产物 |
| 10 | **统一错误模型**（`BusinessException` + 2 个边界适配器） | §3 | 4 | 合并后同一份业务逻辑要产出 HTTP 与 gRPC 两种错误表达 |
| 11 | **共享件下沉到根级 `common/` starter**（D3） | §4 | 6 | 普通 jar ⇒ 每个消费者手写 `@Import` ⇒ MP 装配桥被复制 9 份 |
| 12 | **域内 api→rpc 改直连**（D2） | §0 / §3 | 3（`api/client/` 清空 + 直接注入 + 无 stub） | 同进程内自我 gRPC 往返是无意义的序列化开销与故障注入点 |

---

## 6. 跨模块全局约定（逐条核对 A 列 Go 源码）

| 项 | 值（Go 真源） | A 列出处（`文件:行号`） | C 列落点 |
|---|---|---|---|
| **proto 字段号** | `message.proto` 逐条一致（RAG 与 RAG-V1 两份 `.proto` **只差 `option java_package`**：`com.rag.message.message.proto` → `com.rag.message.contract.proto`） | `service/message/rpc/pb/message.pb.go`（goctl 生成物） | `proto/src/main/proto/message.proto` |
| ↳ `SendNotificationResp.affected` | `int64 affected = 3;`（**无 `,string`**） | `message.proto:98`；HTTP 侧 `api/internal/types/types.go:21-23` 的 `Affected int64 \`json:"affected"\`` ⇒ Go 输出 JSON **数字** | ⚠️ RAG 的 `service/message/src/main/java/com/rag/message/dto/AdminSendNotificationResp.java` 加了 `@JsonFormat(Shape.STRING)` ⇒ 输出**字符串**。这在 RAG 是**已签字的有意偏离**（`SD-2026-10-23-03`）—— **本表只点明差异存在，不下「该修 / 不该修」的结论** |
| **错误码** | 22 个常量：`Success=200` / `Error=500` / `CodeServerBusy=1015` / `ErrorServerCommon=5001` / `ErrorNotFound=5002` / `ErrorInvalidParam=5003` / `ErrorUnauthorized=5004` / `ErrorForbidden=5005` / `ErrorAlreadyExist=5006` / `ErrorDbSelect=5007` / `ErrorDbUpdate=5008` / `ErrorUserNotFound=5101` / `ErrorAdminNotFound=5102` / `ErrorSelfChat=5201` / `ErrorConversationMiss=5202` / `ErrorNotificationMiss=5203` / `ErrorChatPending=5204` / `ErrorAdminOnlyInit=5205` / `ErrorFirstMsgTooLong=5206` / `ErrorUnsupportedRole=5207` | `service/message/common/errmsg.go:27-49` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` |
| **错误文案** | **英文**：`"service busy"` / `"internal server error"` / `"record not found"` / `"invalid parameter"` / `"unauthorized"` / `"forbidden"` / `"already exists"` / `"database query failed"` / `"database update failed"` / `"user not found"` / `"admin not found"` / `"self chat is not allowed"` / `"conversation not found"` / `"notification not found"` / `"waiting for the other side to reply"` / `"only admin can initiate this conversation"` / `"first private message cannot exceed 50 characters"` / `"unsupported sender role"` / `"OK"` / `"FAIL"` | `service/message/common/errmsg.go:51-73` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java`（⚠️ **逐域隔离，不可与中文域统一**） |
| **gRPC fallback 码表** | `InvalidArgument→5003` / `Unauthenticated→5004` / `PermissionDenied→5005` / `AlreadyExists→5006` / `NotFound→5002` / `Internal→5001` / `default→1015` | `service/message/common/errmsg.go:117-132` | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` |
| **JWT claims** | `userId`（小写 u、驼峰）；类型断言为 `json.Number`，失败 ⇒ `ErrorUnauthorized(5004)` | `service/message/api/internal/logic/shared/helper.go:14-22` | `service/message/src/main/java/com/rag/message/interceptor/*.java`（写 request attribute）+ `service/message/src/main/java/com/rag/message/service/MessageApiSupport.java`（取用） |
| ↳ JWT secret / expire | user：`ae0536f9-6450-4606-8e13-5a19ed505da0` / `31536000`（365 天）；admin：`yU-hCm)2*?NQmWG` / `2592000`（30 天） | `service/message/api/etc/messagecenter.yaml:10-16` | `service/message/src/main/java/com/rag/message/config/MessageApiProperties.java` + `application.yml` |
| **表名** | `message_notification` / `message_conversation` / `message_conversation_message` | `service/message/rpc/internal/model/data_model.go`（`TableName()` 三处） | `service/message/src/main/java/com/rag/message/domain/entity/*Entity.java` 的 `@TableName` |
| ↳ 唯一性对象 | `uk_conversation_pair` ⇒ GORM 建的是**唯一索引**（不在 `pg_constraint`） | `data_model.go` 的 `uniqueIndex:` tag | `service/message/src/main/java/com/rag/message/mapper/*` + DDL 脚本（**待落地**） |
| **HTTP path** | **13 个端点**：<br>**user 组 `/message/v1`**（`rest.WithJwt(UserAuth.AccessSecret)`）：`POST /conversations/list`、`POST /conversations/messages`、`POST /conversations/read`、`POST /conversations/send`、`POST /notifications/list`、`POST /notifications/read`、`POST /notifications/readall`、**`GET /unread`**<br>**admin 组 `/message/v1/admin`**（`rest.WithJwt(AdminAuth.AccessSecret)`）：`POST /conversations/list`、`POST /conversations/messages`、`POST /conversations/read`、`POST /conversations/send`、`POST /notifications/send`<br>⇒ 方法分布 **GET 1 + POST 12** | `service/message/api/internal/handler/routes.go:17-94` | `service/message/src/main/java/com/rag/message/controller/MessageController.java` + `service/message/src/main/java/com/rag/message/controller/MessageAdminController.java` |
| ↳ ⚠️ C 侧 javadoc 与 Go 不一致 | C 侧两个 Controller 的 javadoc 写的是 `/message/v1/conversation/list`、`/notification/read/all`、`/unread/summary`、`/chat/send`，并把 admin 的 `conversation/list` 与 `conversation/messages` 标成 `GET` —— **与 A 列 Go 源码逐条不符**（Go 是复数 `/conversations/*`、`/notifications/*`，`/unread` 是 GET，admin 组 5 个端点**全是 POST**） | 同上 | ⚠️ 实现时以 A 列为准；这两段 javadoc 需要更正 |
| **Kafka topic** | **无 —— message 域完全不涉及 MQ。** Go 侧 `service/message/**` 对 `kafka` / `kqueue` / `mq` 的引用数为 **0** | `grep -rn "kafka\|Kafka\|kqueue\|mq\|MQ" service/message/**/*.go` ⇒ 0 命中 | 无对应物 |
| ↳ `CommentKafkaMsg` 的归属澄清 | `CommentKafkaMsg` 定义在 **`service/comment/rpc/common/types/kqueue.go`**（**不在 message 域目录下**），全仓 13 处引用**全部在 `service/comment/**` 内部** ⇒ 它是 comment 域的域内 MQ 契约，**message 域既不定义也不引用** | `service/comment/rpc/common/types/kqueue.go:3` | 无对应物（不属 message 域） |
| **指标名（api 3 个）** | `message_api_request_total{route,result}` / `message_api_reject_total{route,reason}` / `message_api_request_duration_seconds{route}`（Histogram，`DefBuckets`） | `service/message/api/internal/metrics/metrics.go` | `service/message/src/main/java/com/rag/message/metrics/MessageApiMetrics.java` |
| **指标名（rpc 5 个）** | `message_rpc_request_total{method,result}` / `message_rpc_request_duration_seconds{method}` / `message_rpc_notification_action_total{kind,result}` / `message_rpc_chat_action_total{action,result}` / `message_rpc_db_error_total{action,type}` | `service/message/rpc/internal/metrics/metrics.go` | `service/message/src/main/java/com/rag/message/metrics/MessageRpcMetrics.java` |
| **gRPC 方法（9 个）** | `SendNotification` / `ListNotifications` / `MarkNotificationRead` / `MarkAllNotificationsRead` / `GetUnreadSummary` / `ListConversations` / `GetConversationMessages` / `SendChatMessage` / `MarkConversationRead` | `service/message/rpc/messageservice/messageservice.go`（接口定义）+ `rpc/internal/server/messageserviceserver.go`（分发） | `service/message/src/main/java/com/rag/message/grpc/MessageGrpcService.java`（9 个 override） |
| **枚举** | `NotificationKind`（UNSPECIFIED=0 / ADMIN_BROADCAST=1 / ADMIN_NOTICE=2 / ARTICLE_COMMENT=3 / COMMENT_REPLY=4 / ARTICLE_PUBLISHED=5 / ARTICLE_LIKED=6）· `SenderRole`（UNSPECIFIED=0 / USER=1 / ADMIN=2 / SYSTEM=3）· `ConversationStatus`（UNSPECIFIED=0 / PENDING=1 / OPEN=2） | `service/message/rpc/pb/message.pb.go` | `proto/src/main/proto/message.proto:7-28` |
| **角色写入规则** | user 组 API 恒 `SenderRole.USER`；admin 组恒 `SenderRole.ADMIN`（admin 发消息的 `RecipientRole` 恒 `USER`） | `api/internal/logic/message/*.go` / `api/internal/logic/messageadmin/*.go` | `service/message/src/main/java/com/rag/message/service/MessageApiService.java`（硬编码，不从 JWT 推导） |
| **配置键（rpc 业务段）** | `List.DefaultLimit=20` / `List.MaxLimit=100` / `Broadcast.PageSize=200` | `service/message/rpc/etc/message.yaml:48-53` | `service/message/src/main/java/com/rag/message/config/MessageProperties.java`（`@ConfigurationProperties(prefix="message")`） |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏（RAG-V1 `service/message/` 全部手写 `.java`）

自查命令：

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/message" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u | wc -l
# ⇒ 51
```

**逐条落位（51 / 51 = 100%）**

| # | C 列文件 | 落位小节 |
|---|---|---|
| 1 | `service/message/src/main/java/com/rag/message/contract/CodeError.java` | §1.4 |
| 2 | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | §1.4 |
| 3 | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | §1.4 |
| 4 | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` | §1.4 |
| 5 | `service/message/src/main/java/com/rag/message/MessageServiceApplication.java` | §1.1 / §1.3 |
| 6 | `service/message/src/main/java/com/rag/message/advice/MessageApiExceptionHandler.java` | §1.1 |
| 7 | `service/message/src/main/java/com/rag/message/advice/MessageApiResponseAdvice.java` | §1.1 |
| 8 | `service/message/src/main/java/com/rag/message/config/MessageApiProperties.java` | §1.1 |
| 9 | `service/message/src/main/java/com/rag/message/config/WebMvcConfig.java` | §1.1 |
| 10 | `service/message/src/main/java/com/rag/message/controller/MessageAdminController.java` | §1.2.1 |
| 11 | `service/message/src/main/java/com/rag/message/controller/MessageController.java` | §1.2.1 |
| 12 | `service/message/src/main/java/com/rag/message/dto/AdminSendChatReq.java` | §1.2.2 |
| 13 | `service/message/src/main/java/com/rag/message/dto/AdminSendNotificationReq.java` | §1.2.2 |
| 14 | `service/message/src/main/java/com/rag/message/dto/AdminSendNotificationResp.java` | §1.2.2 |
| 15 | `service/message/src/main/java/com/rag/message/dto/ChatMessageItem.java` | §1.2.2 |
| 16 | `service/message/src/main/java/com/rag/message/dto/ConversationItem.java` | §1.2.2 |
| 17 | `service/message/src/main/java/com/rag/message/dto/ConversationListResp.java` | §1.2.2 |
| 18 | `service/message/src/main/java/com/rag/message/dto/ConversationMessagesReq.java` | §1.2.2 |
| 19 | `service/message/src/main/java/com/rag/message/dto/ConversationMessagesResp.java` | §1.2.2 |
| 20 | `service/message/src/main/java/com/rag/message/dto/EmptyReq.java` | §1.2.2 |
| 21 | `service/message/src/main/java/com/rag/message/dto/MarkConversationReadReq.java` | §1.2.2 |
| 22 | `service/message/src/main/java/com/rag/message/dto/MarkNotificationReq.java` | §1.2.2 |
| 23 | `service/message/src/main/java/com/rag/message/dto/NotificationItem.java` | §1.2.2 |
| 24 | `service/message/src/main/java/com/rag/message/dto/NotificationListResp.java` | §1.2.2 |
| 25 | `service/message/src/main/java/com/rag/message/dto/PageReq.java` | §1.2.2 |
| 26 | `service/message/src/main/java/com/rag/message/dto/SendChatResp.java` | §1.2.2 |
| 27 | `service/message/src/main/java/com/rag/message/dto/UnreadSummaryResp.java` | §1.2.2 |
| 28 | `service/message/src/main/java/com/rag/message/dto/UserSendChatReq.java` | §1.2.2 |
| 29 | `service/message/src/main/java/com/rag/message/interceptor/MessageAdminJwtAuthInterceptor.java` | §1.1 / §1.2.1 |
| 30 | `service/message/src/main/java/com/rag/message/interceptor/MessageJwtAuthInterceptor.java` | §1.1 / §1.2.1 |
| 31 | `service/message/src/main/java/com/rag/message/metrics/MessageApiMetrics.java` | §1.1 |
| 32 | `service/message/src/main/java/com/rag/message/service/MessageApiService.java` | §1.2.1 |
| 33 | `service/message/src/main/java/com/rag/message/service/MessageApiSupport.java` | §1.2.1 |
| 34 | `service/message/src/main/java/com/rag/message/bootstrap/NacosRegistration.java` | §1.3 |
| 35 | `service/message/src/main/java/com/rag/message/client/AdminRpcClient.java` | §1.3 |
| 36 | `service/message/src/main/java/com/rag/message/client/UserRpcClient.java` | §1.3 |
| 37 | `service/message/src/main/java/com/rag/message/config/MessageProperties.java` | §1.3 |
| 38 | `service/message/src/main/java/com/rag/message/domain/entity/ConversationEntity.java` | §1.3 |
| 39 | `service/message/src/main/java/com/rag/message/domain/entity/ConversationMessageEntity.java` | §1.3 |
| 40 | `service/message/src/main/java/com/rag/message/domain/entity/NotificationEntity.java` | §1.3 |
| 41 | `service/message/src/main/java/com/rag/message/exception/MessageExceptions.java` | §1.3 |
| 42 | `service/message/src/main/java/com/rag/message/mapper/ConversationMapper.java` | §1.3 |
| 43 | `service/message/src/main/java/com/rag/message/mapper/ConversationMessageMapper.java` | §1.3 |
| 44 | `service/message/src/main/java/com/rag/message/mapper/GoStylePagination.java` | §1.3 |
| 45 | `service/message/src/main/java/com/rag/message/mapper/NotificationMapper.java` | §1.3 |
| 46 | `service/message/src/main/java/com/rag/message/metrics/MessageMetrics.java` | §1.3（C 新增 · 空壳重叠） |
| 47 | `service/message/src/main/java/com/rag/message/metrics/MessageRpcMetrics.java` | §1.3 |
| 48 | `service/message/src/main/java/com/rag/message/domain/service/MessageAtomicService.java` | §1.3 |
| 49 | `service/message/src/main/java/com/rag/message/domain/service/MessageDomainService.java` | §1.3 |
| 50 | `service/message/src/main/java/com/rag/message/service/MessageRpcSupport.java` | §1.3 |
| 51 | `service/message/src/main/java/com/rag/message/grpc/MessageGrpcService.java` | §1.3 |

> **不计入分母**：proto 生成物（根级 `proto/` 模块的 `target/generated-sources/**`）与 `target/classes/**` 下的 `.class` —— 它们不是手写源码。

### 7.2 A 列不遗漏职责（Go `service/message/` 全部非测试文件）

自查命令：

```bash
find "/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/message" \
  -type f -not -name '*_test.go' | wc -l
# ⇒ 57（55 个 .go + 2 个 .yaml）
```

| A 列文件 | 落位小节 |
|---|---|
| `api/messagecenter.go` | §1.1 |
| `api/etc/messagecenter.yaml` | §1.1 |
| `api/internal/config/config.go` | §1.1 |
| `api/internal/handler/routes.go` | §1.1 |
| `api/internal/handler/message/getconversationmessageshandler.go` | §1.2.1 |
| `api/internal/handler/message/getunreadsummaryhandler.go` | §1.2.1 |
| `api/internal/handler/message/listconversationshandler.go` | §1.2.1 |
| `api/internal/handler/message/listnotificationshandler.go` | §1.2.1 |
| `api/internal/handler/message/markallnotificationsreadhandler.go` | §1.2.1 |
| `api/internal/handler/message/markconversationreadhandler.go` | §1.2.1 |
| `api/internal/handler/message/marknotificationreadhandler.go` | §1.2.1 |
| `api/internal/handler/message/senduserchatmessagehandler.go` | §1.2.1 |
| `api/internal/handler/messageadmin/getadminconversationmessageshandler.go` | §1.2.1 |
| `api/internal/handler/messageadmin/listadminconversationshandler.go` | §1.2.1 |
| `api/internal/handler/messageadmin/markadminconversationreadhandler.go` | §1.2.1 |
| `api/internal/handler/messageadmin/sendadminchatmessagehandler.go` | §1.2.1 |
| `api/internal/handler/messageadmin/sendadminnotificationhandler.go` | §1.2.1 |
| `api/internal/logic/message/getconversationmessageslogic.go` | §1.2.1 |
| `api/internal/logic/message/getunreadsummarylogic.go` | §1.2.1 |
| `api/internal/logic/message/listconversationslogic.go` | §1.2.1 |
| `api/internal/logic/message/listnotificationslogic.go` | §1.2.1 |
| `api/internal/logic/message/markallnotificationsreadlogic.go` | §1.2.1 |
| `api/internal/logic/message/markconversationreadlogic.go` | §1.2.1 |
| `api/internal/logic/message/marknotificationreadlogic.go` | §1.2.1 |
| `api/internal/logic/message/senduserchatmessagelogic.go` | §1.2.1 |
| `api/internal/logic/messageadmin/getadminconversationmessageslogic.go` | §1.2.1 |
| `api/internal/logic/messageadmin/listadminconversationslogic.go` | §1.2.1 |
| `api/internal/logic/messageadmin/markadminconversationreadlogic.go` | §1.2.1 |
| `api/internal/logic/messageadmin/sendadminchatmessagelogic.go` | §1.2.1 |
| `api/internal/logic/messageadmin/sendadminnotificationlogic.go` | §1.2.1 |
| `api/internal/logic/shared/helper.go` | §1.2.1 |
| `api/internal/metrics/metrics.go` | §1.1 |
| `api/internal/svc/servicecontext.go` | §1.1 |
| `api/internal/types/types.go` | §1.2.2 |
| `common/errmsg.go` | §1.4 |
| `rpc/message.go` | §1.3 |
| `rpc/etc/message.yaml` | §1.3 |
| `rpc/internal/config/config.go` | §1.3 |
| `rpc/internal/logic/getconversationmessageslogic.go` | §1.3 |
| `rpc/internal/logic/getunreadsummarylogic.go` | §1.3 |
| `rpc/internal/logic/helper.go` | §1.3 |
| `rpc/internal/logic/listconversationslogic.go` | §1.3 |
| `rpc/internal/logic/listnotificationslogic.go` | §1.3 |
| `rpc/internal/logic/markallnotificationsreadlogic.go` | §1.3 |
| `rpc/internal/logic/markconversationreadlogic.go` | §1.3 |
| `rpc/internal/logic/marknotificationreadlogic.go` | §1.3 |
| `rpc/internal/logic/sendchatmessagelogic.go` | §1.3 |
| `rpc/internal/logic/sendnotificationlogic.go` | §1.3 |
| `rpc/internal/metrics/metrics.go` | §1.3 |
| `rpc/internal/model/data_model.go` | §1.3 |
| `rpc/internal/model/db.go` | §1.3 |
| `rpc/internal/model/message.go` | §1.3 |
| `rpc/internal/server/messageserviceserver.go` | §1.3 |
| `rpc/internal/svc/servicecontext.go` | §1.3 |
| `rpc/messageservice/messageservice.go` | §1.3（⚠️ C 侧无手写对应物：域内 stub 随 D2 消失） |
| `rpc/pb/message.pb.go` | §1.3（生成物 → 根级 `proto/`） |
| `rpc/pb/message_grpc.pb.go` | §1.3（生成物 → 根级 `proto/`） |

**A 列覆盖率 = 57 / 57 = 100%**（55 个 `.go` + 2 个 `.yaml`，逐条落位；`rpc/messageservice/messageservice.go` 已注明「C 侧无对应手写文件」的原因）。

### 7.3 映射类型分布

| 映射类型 | 行数 | 主要落在哪 |
|---|---|---|
| `1:1` | 44 | 入口 / 配置 / Metrics / Entity / Mapper（4 个）/ DTO（17 个 struct 逐条）/ proto / 错误契约 / `MessageApiSupport` / `MessageRpcSupport` / `MessageAtomicService` / 3 个 `Entity` |
| `1:N` | 15 | ① **一组路由 → 两组 JWT**（§1.1）；② 13 个 handler → 2 个 Controller（2 行）；③ Go `types.go` → 17 个 record（1 行文件级）；④ Go `errmsg.go` → 4 个契约类 + `CodeError` + `GrpcErrorMapper`（4 行）；⑤ Go `model/message.go` → 4 Mapper + `MessageAtomicService`；⑥ Go `model/db.go` → 3 Mapper + DDL；⑦ Go api `config.go` → Properties + yaml；⑧ Go rpc `config.go` → Properties；⑨ Go rpc `svc/servicecontext.go` → 2 个跨域 client；⑩ Go `message.go` → 入口 + Nacos；⑪ Go `messagecenter.go` → 2 个 advice；⑫ B 侧 `MessageGoStyleCoercion` → `common/src/main/java/com/rag/common/web/Coercion` + `JsonBodyConverterAutoConfiguration` |
| `N:1` | 7 | ① 8 个 user logic → `MessageApiService`；② 5 个 admin logic → `MessageApiService`；③ 9 个 rpc logic → `MessageDomainService`；④ Go `messageservice.go` stub → C 侧无手写文件；⑤ B 侧 `AbstractMessageJwtAuthInterceptor` → C 侧无对应文件；⑥ B 侧 `AbstractMessageApiService` → `MessageApiService`；⑦ Go `service/common`（跨域共享信封 + 异常基类）→ C 根级 `common/` 4 个类 |
| `C 新增` | 2 | ① `rpc/metrics/MessageMetrics.java`（与 `MessageRpcMetrics` 重叠的空壳）；② `rpc/exception/MessageExceptions.java`（B 侧同名同职责，C 侧作为独立类保留） |
| **合计** | **68** | §1 全部映射行均填了映射类型，且只用 4 种合法词（机器核对：`illegal types = ∅`） |

### 7.4 三列之间「职责对不上」的清单（供实现前处理）

> 本表**不下「已对齐 / 未对齐」结论**，只标出**文件名 / 职责落位对不上**的地方。

| # | 位置 | 现象 | 影响面 |
|---|---|---|---|
| 1 | C 侧 `api/controller/MessageController.java` + `MessageAdminController.java` 的 javadoc | 路由清单写成 `/message/v1/conversation/list`、`/notification/read/all`、`/unread/summary`、`/chat/send`，并把 admin 的 `conversation/list` 与 `conversation/messages` 标成 `GET`；**A 列 Go 是复数 `/conversations/*`、`/notifications/*`，`/unread` 是 GET，admin 组 5 个端点全是 POST** | **L1 路由契约**；若照 javadoc 实现即产生 wire 漂移 |
| 2 | C 侧 `api/interceptor/` | B 侧的 `AbstractMessageJwtAuthInterceptor`（两份 JWT 逻辑的共享基类）在 C 侧**没有落点**，只剩两个独立拦截器类 | 安全边界可能各自演化（DRIFT-010 同款形态） |
| 3 | C 侧 `rpc/metrics/` | `MessageMetrics.java`（空壳）与 `MessageRpcMetrics.java`（已列全 5 个指标）职责重叠 | 指标名可能双份定义、漂移 |
| 4 | C 侧 `rpc/bootstrap/` | 设计文档 §5.2 要求的 `MessageSchemaInitializer.java`（幂等 DDL 重放）**不存在**；`resources/db/migration/` 只有 `.gitkeep` | 3 张表没有建表来源 ⇒ 服务起不来（Go 的 `AutoMigrate` 职责无对应物） |
| 5 | C 侧 `rpc/service/` | `MessageDomainService` 的 9 个用例**尚未与 `api/service/MessageApiService` 划清边界**（谁做业务规则、谁做编排） | 合并后职责边界易糊 |
| 6 | C 侧 `service/message/src/main/java/com/rag/message/contract/` | `CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper` 与根级 `common/src/main/java/com/rag/common/contract/ApiResponse` + `common/src/main/java/com/rag/common/exception/BusinessException` 的关系未标定 | 统一错误模型（D2 附带）落地时会双轨 |
| 7 | 任务简报中的前提 | `CommentKafkaMsg` 在 **`service/comment/rpc/common/types/kqueue.go`**（comment 域），且**仅被 comment 域引用**（全仓 13 处引用全在 `service/comment/**`）；`service/message/**` 对 MQ 的引用数为 **0** | 本表据此把 Kafka topic 记为「无」；**message 域无 MQ 契约** |
| 8 | C 侧 `api/dto/AdminSendNotificationResp.java` | `affected` 的 JSON 形态：Go = 数字，RAG = 字符串（**已签字偏离 `SD-2026-10-23-03`**） | 本表只点明差异，**不表结论**；proto 侧两侧均为 `int64 affected = 3`（无 `,string`） |
