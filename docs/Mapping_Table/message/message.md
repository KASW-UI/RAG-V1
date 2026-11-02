# Mapping_Table/message/message.md — RAG ↔ RAG-V1 两方对照表（message 域）

> **本表目的**：把 **B 列**（RAG，判定基准）→ **C 列**（RAG-V1，Spring 惯用目标形态）两棵树里 **message 域**的文件逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 路径 | 角色 |
> |---|---|---|---|
> | **B** | `RAG` | `service/message/` | **判定基准**（唯一真理） |
> | **C** | `RAG-V1` | `service/message/` | Spring 惯用目标形态（api/rpc 合并，D1） |
>
> **映射类型词表（固定 4 种，不发明新词）**：描述 **B → C** 的文件数关系 ——
> `1:1`（一个 B 文件 → 一个 C 文件）· `1:N`（一个 B 文件 → N 个 C 文件）· `N:1`（N 个 B 文件 → 一个 C 文件）· `C 新增`（B 侧无对应物，是 RAG-V1 形态本身需要的新文件）
>
> **格式契约**：[`README.MD`](../README.MD)　**章节骨架来源**：`RAG/docs/MappingTable/users.md`
>
> 📌 **2026-11-02 · D16**：判定基准改为「**B 侧是唯一真理**」⇒ **原第三列已物理删除**（见 [`README.MD`](../README.MD)）⇒ 本表为**两方**（B/C）。

### ⚠️ 三条读表纪律（必读）

1. **B 列是唯一判定基准**（RAG 仓）—— 本表回答「RAG 的哪个文件对应 RAG-V1 的哪个文件」；B 侧自身的漂移由 `RAG/docs/DRIFT/message/{api,rpc}.md` 台账负责，本表**只用于定位文件**。
2. **本表只回答「文件名 / 职责对应关系」**，**不下「已对齐 / 未对齐」结论**。行为对齐状态看 `RAG/docs/DRIFT/message/<组件>.md` 的**证据强度列**。
3. **C 列全是空壳。** RAG-V1 的每个 `.java` 目前只有 `package` + 空类型 + `TODO javadoc` ⇒ **C 列每一行写的是「这个位置该放什么职责」，不是「已经有什么」**。

---

## 0. 顶层模块对照

| B (RAG) | C (RAG-V1) | 映射类型 | 备注 |
|---|---|---|
| `service/message/message-api/` | `service/message/` 的 `service/message/src/main/java/com/rag/message/advice + service/message/src/main/java/com/rag/message/config + service/message/src/main/java/com/rag/message/controller + service/message/src/main/java/com/rag/message/dto + service/message/src/main/java/com/rag/message/interceptor + service/message/src/main/java/com/rag/message/metrics + service/message/src/main/java/com/rag/message/service` 包 | 1:N | C 把 api 折进单 app（D1） |
| `service/message/message-rpc/` | `service/message/` 的 `service/message/src/main/java/com/rag/message/wiring + service/message/src/main/java/com/rag/message/client + service/message/src/main/java/com/rag/message/config + service/message/src/main/java/com/rag/message/domain/entity + service/message/src/main/java/com/rag/message/domain/service + service/message/src/main/java/com/rag/message/exception + service/message/src/main/java/com/rag/message/grpc + service/message/src/main/java/com/rag/message/mapper + service/message/src/main/java/com/rag/message/metrics + service/message/src/main/java/com/rag/message/service` 包 | 1:N | 同上；`rpc.*` 命名债明确不改 |
| `service/message/message-common/` | `service/message/src/main/java/com/rag/message/contract/` | 1:1 | 模块改名 `common` → `contract`（承载域内错误契约）；⚠️ **proto 已随 D7 集中到根级 `proto/` 模块** |
| `service/message/message-proto/` | `service/message/src/main/java/com/rag/message/contract/`（4 个错误契约类）+ **生成物在根级 `proto/` 模块**（`proto/src/main/proto/message.proto`，pb 包名 `com.rag.proto.message`） | 1:1 | `.proto` 逐字一致，只差 `option java_package`；**D7 后 proto 已从域模块集中到根级 `proto/`** |
| `service/common/`（普通 jar） | 仓库根 `common/`（真 starter，D3） | 1:1 | B 侧 `service/common` → C 侧根级 starter |
| **2 个**（`message-api` / `message-rpc`） | **1 个**（`message-service`：HTTP `8883` + gRPC `8084` 同进程） | N:1 | D1；D2 域内调用改直连后自我 gRPC 往返消失 |
| `service/message/message-common/`（3 类） | `service/message/src/main/java/com/rag/message/contract/`（4 类） | 1:N | `MessageErrorCode/MessageErrorMessages/MessageGrpcError` → `ErrCodes/ErrMsg/CodeError/GrpcErrorMapper` |

> 📌 **C 侧 `service/message/` 的两个子包职责**：`advice + config + controller + dto + interceptor + metrics + service` = HTTP 边界（Controller/DTO/Interceptor/Advice）；`wiring + client + config + domain/entity + domain/service + exception + grpc + mapper + metrics + service` = 领域 / 服务端能力层（DomainService/Mapper/Entity/Client）。**合并后 HTTP 与 gRPC 共用同一份业务用例**（`domain/service/MessageDomainService.java`）。

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
| `message/api/dto/` | `dto/chat/` + `dto/common/` + `dto/conversation/` + `dto/notification/` + `dto/read/` | 直接压平后**按业务对象拆 5 个子包**（D11：`chat` 3 / `common` 2 / `conversation` 5 / `notification` 5 / `read` 2） |
| `message/api/interceptor/` | `interceptor/` | 直接压平（**2 组 JWT**）|
| `message/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `message/api/protection/` | `common/src/main/java/com/rag/common/protection/` | **上收 `common/`**（D3）|
| `message/api/service/` | `service/` | 与 rpc 侧合并 |
| `message/common/errmsg/` | `contract/` | 错误码（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）|
| `message/rpc/bootstrap/` | `wiring/` | 直接压平 |
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

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-api/.../MessageApiApplication.java` | `service/message/src/main/java/com/rag/message/MessageServiceApplication.java` | 1:1 | 入口；C 侧 `@SpringBootApplication` + `@EnableDiscoveryClient`，api/rpc 合一个进程（D1） |
| `message-api/.../advice/GlobalResponseAdvice.java` + `advice/GlobalExceptionHandler.java` | `service/message/src/main/java/com/rag/message/advice/MessageApiResponseAdvice.java` + `service/message/src/main/java/com/rag/message/advice/MessageApiExceptionHandler.java` | 1:N | B 侧 `advice/` 两个类（`GlobalResponseAdvice` + `GlobalExceptionHandler`）→ C 两个 advice 类。**HTTP 恒 200 + `{code,msg,data}` 信封**、业务失败码在 body 里，是 L1 契约 |
| `message-api/src/main/resources/application.yml` | `service/message/src/main/resources/application.yml` | 1:1 | yaml 里 `UserAuth` / `AdminAuth` 两段逐字搬到 C 的 `message-api.user-auth` / `message-api.admin-auth` |
| `message-api/.../config/MessageApiProperties.java` | `service/message/src/main/java/com/rag/message/config/MessageApiProperties.java` | 1:1 | B 侧 `@ConfigurationProperties(prefix="message-api")` + 两段 `@NestedConfigurationProperty`（`userAuth` / `adminAuth`）⇒ **C 侧沿用同一形态**，字段名与前缀逐字一致 |
| `message-api/.../client/MessageRpcClient.java` | `service/message/src/main/java/com/rag/message/service/MessageApiService.java`（直连注入 `MessageDomainService`） | 1:1 | ⚠️ C 侧 `api/client/` **0 个**（D2 域内直连）；B 侧的 `ServiceContext` 无独立 Java 文件（被 Spring DI 吸收） |
| `message-api/.../metrics/MessageApiMetrics.java` | `service/message/src/main/java/com/rag/message/metrics/MessageApiMetrics.java` | 1:1 | 3 个指标（见 §6）；C 侧 Micrometer 承担注册，**指标名 / 标签 / 调用点**是业务定义 |
| `message-api/.../config/WebMvcConfig.java` + `interceptor/MessageJwtAuthInterceptor.java` + `interceptor/MessageAdminJwtAuthInterceptor.java` | `service/message/src/main/java/com/rag/message/config/WebMvcConfig.java` + `service/message/src/main/java/com/rag/message/interceptor/MessageJwtAuthInterceptor.java` + `service/message/src/main/java/com/rag/message/interceptor/MessageAdminJwtAuthInterceptor.java` | **1:N** | ⚠️ **一组路由 → 两组 JWT 的 1:N 拆分**：`/message/v1` 用 `UserAuth.AccessSecret`、`/message/v1/admin` 用 `AdminAuth.AccessSecret`。C 的 user 拦截器**必须** `excludePathPatterns("/message/v1/admin/**")`，否则 admin token 会被 user key 先拒一遍 |

**1.1 决策锁**

**换「双拦截器 + 双 Auth 配置段」（一组 → 两组，6 处同时改）**：
1. `config/MessageApiProperties.java` 保留 `userAuth` / `adminAuth` 两段嵌套（**不合并成一段**）；
2. `interceptor/MessageJwtAuthInterceptor.java` —— user key，`addPathPatterns("/message/v1/**")`；
3. `interceptor/MessageAdminJwtAuthInterceptor.java` —— admin key，`addPathPatterns("/message/v1/admin/**")`；
4. `config/WebMvcConfig.java` —— user 拦截器加 `excludePathPatterns("/message/v1/admin/**")`；
5. `application.yml` 保留 `message-api.user-auth.*` 与 `message-api.admin-auth.*` **两套** secret / expire；
6. 两套 secret 的值必须与签发方一致（user 段 ← user-api，admin 段 ← admin-api），`AdminAuth` 的 15 字节 secret 需补齐到 32 字节（jjwt HS256 硬要求）。

**为什么换**：B 侧 `WebMvcConfig.addInterceptors` 注册**两组拦截器**，各带一把 key 与一个前缀（`/message/v1/**` + `excludePathPatterns("/message/v1/admin/**")` / `/message/v1/admin/**`）；合成一把就等于取消鉴权边界。**注意这是「一组职责拆成两组」，不是「两份重复代码」**。

---

### 1.2 API 层用例（13 个用例 · DTO 17 个）

#### 1.2.1 handler / logic / Controller / Service

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-api/.../controller/MessageController.java`（8 个 `@PostMapping`） | `service/message/src/main/java/com/rag/message/controller/MessageController.java` | 1:N | B 侧 8 个 `@PostMapping` → B/C 各 1 个 Controller |
| `message-api/.../controller/MessageAdminController.java` | `service/message/src/main/java/com/rag/message/controller/MessageAdminController.java` | 1:N | admin 组独立 Controller（两套 JWT ⇒ 两套前缀） |
| `message-api/.../service/Message<Case>Service.java` × 8 + `service/AbstractMessageApiService.java` | `service/message/src/main/java/com/rag/message/service/MessageApiService.java` | N:1 | 13 用例内聚为 1 个 Service（判据见《完全spring化的文件树》§5.4，五条全中） |
| `message-api/.../service/MessageAdmin<Case>Service.java` × 5 | `service/message/src/main/java/com/rag/message/service/MessageApiService.java` | N:1 | 同上；**user 组硬编码 `SenderRole.USER`、admin 组硬编码 `SenderRole.ADMIN`** 的分工要保留 |
| `message-api/.../service/MessageApiSupport.java` | `service/message/src/main/java/com/rag/message/service/MessageApiSupport.java` | 1:1 | 9 个函数：`UserIDFromContext`（claim `userId`）+ `RPCError` + 6 个 `To*Resp` 投影 + `AdminNotificationKind` |
| `message-api/.../interceptor/AbstractMessageJwtAuthInterceptor.java` | `service/message/src/main/java/com/rag/message/interceptor/AbstractMessageJwtAuthInterceptor.java` | 1:1 | ✅ **已落地（2026-11-01）**：C 侧补上**抽象基类** ⇒ B 用基类收敛两份 JWT 逻辑的意图**在 C 侧兑现**（防两份安全边界各自演化）；⚠️ 两个子类 `MessageJwtAuthInterceptor` / `MessageAdminJwtAuthInterceptor` 仍须**继承**它（否则基类形同虚设） |

#### 1.2.2 DTO（B 侧 17 个 DTO 类 ⇒ C 17 个 immutable record）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-api/.../dto/*.java` × 18 | `service/message/src/main/java/com/rag/message/dto/{chat,common,conversation,notification,read}/*.java` × 17（**D11** 按业务对象拆 5 个子包） | 1:N | C 去掉 B 侧的 `GoStyleStringInt64`（降级到 `common/src/main/java/com/rag/common/web/`） |
| `dto/AdminSendChatReq.java` | `service/message/src/main/java/com/rag/message/dto/chat/AdminSendChatReq.java` | 1:1 | `recipient_id` / `conversation_id` 带 `,string` |
| `dto/AdminSendNotificationReq.java` | `service/message/src/main/java/com/rag/message/dto/notification/AdminSendNotificationReq.java` | 1:1 | `broadcast` / `target_id` / `kind` / `extra` 均 `optional` |
| `dto/AdminSendNotificationResp.java` | `service/message/src/main/java/com/rag/message/dto/notification/AdminSendNotificationResp.java` | 1:1 | ⚠️ `affected` 的 JSON 形态存在**已签字偏离**，见 §6 注 |
| `dto/ChatMessageItem.java` | `service/message/src/main/java/com/rag/message/dto/conversation/ChatMessageItem.java` | 1:1 | 9 字段；`id` / `sender_id` / `recipient_id` 带 `,string` |
| `dto/ConversationItem.java` | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationItem.java` | 1:1 | 11 字段 |
| `dto/ConversationListResp.java` | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationListResp.java` | 1:1 | `items` + `total` + `unread_count` |
| `dto/ConversationMessagesReq.java` | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationMessagesReq.java` | 1:1 | `offset` / `limit` 为 `optional` |
| `dto/ConversationMessagesResp.java` | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationMessagesResp.java` | 1:1 | 带 `status` / `can_send` / `pending_sender_*` |
| `dto/EmptyReq.java` | `service/message/src/main/java/com/rag/message/dto/common/EmptyReq.java` | 1:1 | 空 body（`/notifications/readall` 与 `/unread` 用） |
| `dto/MarkConversationReadReq.java` | `service/message/src/main/java/com/rag/message/dto/read/MarkConversationReadReq.java` | 1:1 |  |
| `dto/MarkNotificationReq.java` | `service/message/src/main/java/com/rag/message/dto/notification/MarkNotificationReq.java` | 1:1 |  |
| `dto/NotificationItem.java` | `service/message/src/main/java/com/rag/message/dto/notification/NotificationItem.java` | 1:1 | 含 `extra`（`map[string]string`） |
| `dto/NotificationListResp.java` | `service/message/src/main/java/com/rag/message/dto/notification/NotificationListResp.java` | 1:1 | `items` + `total` + `unread_count` |
| `dto/PageReq.java` | `service/message/src/main/java/com/rag/message/dto/common/PageReq.java` | 1:1 | `offset` / `limit` / `unread_only` 全 `optional` |
| `dto/SendChatResp.java` | `service/message/src/main/java/com/rag/message/dto/chat/SendChatResp.java` | 1:1 |  |
| `dto/UnreadSummaryResp.java` | `service/message/src/main/java/com/rag/message/dto/read/UnreadSummaryResp.java` | 1:1 | `notification_unread` / `conversation_unread` / `total_unread` |
| `dto/UserSendChatReq.java` | `service/message/src/main/java/com/rag/message/dto/chat/UserSendChatReq.java` | 1:1 | `recipient_id` / `conversation_id` 为 `optional` |
| `message-api/.../dto/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | 1:1 | D3：共享件下沉到根级 starter |

**1.2 决策锁**

**换「13 个 logic ⇒ 1 个 Service」（N:1，4 处同时改）**：
1. 删 B 侧 `message-api/.../service/Message<Case>Service.java` × 13 与 `AbstractMessageApiService.java`；
2. C 侧 `service/MessageApiService.java` 承接 13 个用例的**编排**（uid 解析 → 硬编码 role → 调 DomainService → 投影 DTO → 埋点）；
3. 业务规则下沉到 `domain/service/MessageDomainService.java`（HTTP 与 gRPC **共享同一份**）；
4. `service/MessageApiSupport.java` 保留 `UserIDFromContext` + `To*Resp` 投影 + 错误码映射。

**为什么换**：B 侧 13 个 `*Service` 类（每个用例一个）+ `AbstractMessageApiService` 是**用例级样板拆分**，不是职责边界；合并后「构造 service + 转交」这一层消失。⚠️ 但**业务规则密集的部分不适用合并**（rpc 侧的 `MessageRpcSupport` / `MessageAtomicService` 是协作者与事务边界类，必须保持独立）。

**换「可变 struct ⇒ immutable record」（D5，3 处同时改）**：
1. `dto/**/*.java` 全部改 `record`（不可变）；
2. JSON 字段名契约**逐字不变**（下划线式，如 `recipient_id` / `conversation_id`）⇒ C 侧用 `@JsonProperty` / 组件名显式声明；
3. `,string` 的整数字段用 `common/src/main/java/com/rag/common/web/StringInt64.java` 的解码口径统一处理。

**为什么换**：B 侧 DTO 是**可变 POJO**（setter 逐字段赋值）；Java 侧 record 表达「一次构造即完整」，且与 Bean Validation（`@NotNull` / `@NotBlank`）配合更顺。⚠️ 文案与「必填」判定属 L3 降级（Bean Validation 文案 ≠ B 侧 DTO 归一化文案）。

---

### 1.3 RPC / 领域层

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-rpc/.../MessageRpcApplication.java` + `rpc/bootstrap/NacosRegistration.java` | `service/message/src/main/java/com/rag/message/MessageServiceApplication.java` + `service/message/src/main/java/com/rag/message/wiring/NacosRegistration.java` | 1:N | C 侧入口 1 个（合并 D1）；Nacos 显式注册保留（B 侧 `rpc/bootstrap/NacosRegistration.java`，替代 etcd 自动注册） |
| `message-rpc/src/main/resources/application.yml` + `rpc/config/MessageProperties.java` | `service/message/src/main/resources/application.yml` + `service/message/src/main/java/com/rag/message/config/MessageProperties.java` | 1:1 | `List{DefaultLimit:20, MaxLimit:100}` + `Broadcast{PageSize:200}` 逐字对齐 |
| `message-rpc/.../config/MessageProperties.java` | `service/message/src/main/java/com/rag/message/config/MessageProperties.java` | 1:1 | ⚠️ B 侧**没有** `*RpcProperties` 这种命名，类名是 `MessageProperties`；B/C 都用 `@ConfigurationProperties(prefix="message")` 承载。`Postgres` / `UserRpc` / `AdminRpc` 由 `spring.datasource` / `grpc.client.*` 接管 |
| `message-rpc/.../client/UserRpcClient.java` + `client/AdminRpcClient.java` | `service/message/src/main/java/com/rag/message/client/UserRpcClient.java` + `service/message/src/main/java/com/rag/message/client/AdminRpcClient.java` | 1:1 | 两个都是**跨域**下游（user 域 / admin 域）⇒ D6 保持 gRPC；`loadProfile(role,id)` / `collectBroadcastRecipients(pageSize)` |
| `message-rpc/.../service/impl/MessageRpcServiceImpl.java` | `service/message/src/main/java/com/rag/message/grpc/MessageGrpcService.java` | 1:1 | 9 个方法薄转发（proto ↔ 领域模型转换 + 指标转发 + 异常映射） |
| `message-rpc/.../service/MessageDomainService.java` | `service/message/src/main/java/com/rag/message/domain/service/MessageDomainService.java` | N:1 | 9 用例合 1 个 DomainService；`sendChatMessage` 最复杂（会话状态机 + PENDING 单方连发拦截 + 首条 ≤ 50 rune 校验） |
| `message-rpc/.../service/MessageRpcSupport.java` | `service/message/src/main/java/com/rag/message/service/MessageRpcSupport.java` | 1:1 | 20+ helper：`canonicalParticipants` / `canSend` / `peerOf` / `loadProfile` / `collectBroadcastRecipients` / `dedupeRecipients` / `trimPreview` / `notificationKindLabel` / `successBaseResp` … |
| `message-rpc/.../mapper/ConversationMapper.java` + `mapper/ConversationMessageMapper.java` + `mapper/NotificationMapper.java` + `mapper/GoStylePagination.java` + `service/MessageAtomicService.java` | `service/message/src/main/java/com/rag/message/mapper/ConversationMapper.java` + `service/message/src/main/java/com/rag/message/mapper/ConversationMessageMapper.java` + `service/message/src/main/java/com/rag/message/mapper/NotificationMapper.java` + `service/message/src/main/java/com/rag/message/mapper/GoStylePagination.java` + `service/message/src/main/java/com/rag/message/domain/service/MessageAtomicService.java` | 1:N | 2 个 `conn.Transaction(...)` 闭包 + 3 个单条原子 UPDATE → `MessageAtomicService` 的 6 个 `@Transactional` 方法（**独立 Bean**，见 §2）；`GoStylePagination` 承接 B 侧的「offset / limit 各自有条件」分页口径 |
| `message-rpc/.../domain/ConversationEntity.java` + `.../ConversationMessageEntity.java` + `.../NotificationEntity.java` | `service/message/src/main/java/com/rag/message/domain/entity/ConversationEntity.java` + `.../ConversationMessageEntity.java` + `.../NotificationEntity.java` | 1:N | 3 张表；⚠️ B 侧手写的 `JsonbMapTypeHandler` **无法**用共享层的 `common/.../JsonbTypeHandler.java` 顶替 —— 后者是 **jsonb 列 ↔ `String`**（`BaseTypeHandler<String>`），而 B 侧那份是 **jsonb 列 ↔ `Map<String,String>`** ⇒ **类型不兼容** ⇒ 本域保留域内副本 `mapper/JsonbMapTypeHandler.java`（见 §1.5 + §4） |
| `message-rpc/.../mapper/ConversationMapper.java` + `mapper/ConversationMessageMapper.java` + `mapper/NotificationMapper.java` + `resources/db/migration/V1..V5*.sql` | `service/message/src/main/java/com/rag/message/mapper/ConversationMapper.java` + `service/message/src/main/java/com/rag/message/mapper/ConversationMessageMapper.java` + `service/message/src/main/java/com/rag/message/mapper/NotificationMapper.java` + `resources/db/migration/`（**当前仅 `.gitkeep`**） | 1:N | ⚠️ C 侧 DDL 目录**是空的**；设计文档 §5.2 要求的 `wiring/MessageSchemaInitializer.java`（幂等 DDL 重放）在 C 侧**不存在**（见 §7 缺口清单） |
| `message-rpc/.../metrics/MessageMetrics.java` | `service/message/src/main/java/com/rag/message/metrics/MessageRpcMetrics.java` | 1:1 | 5 个指标 `message_rpc_*` |
| — | `service/message/src/main/java/com/rag/message/metrics/MessageMetrics.java` | C 新增 | ✅ **已明确分工（2026-11-02）**：本类是**两个具体指标类的共享基座**（namespace 前缀常量 + `MeterRegistry` 注册公共方法 + 标签键常量）；`MessageApiMetrics` 承载 3 个 `message_api_*`、`MessageRpcMetrics` 承载 5 个 `message_rpc_*` ⇒ **职责重叠已消除，且不留空壳**（空壳在 A1–A13 断言里算 PENDING） |
| `message-api/.../client/MessageRpcClient.java` | `—（C 侧无对应手写文件）` | N:1 | D2 域内 api→rpc 改直连 ⇒ 域内 stub 无调用点；stub 仅作为对外契约留在根级 `proto/` 的生成物里 |
| `message-proto/`（`message.proto` + 生成物） | `service/message/src/main/java/com/rag/message/contract/`（`message.proto` + 生成物） | 1:1 | `.proto` 与 B 侧逐字一致（只差 `option java_package`）；生成物**不计入 C 列 51 个手写 `.java`** |
| `message-rpc/.../exception/MessageExceptions.java` | `service/message/src/main/java/com/rag/message/exception/MessageExceptions.java` | 1:1 | B 侧把 DB 错误包成 unchecked exception（`DbSelectException` / `DbUpdateException` / `TransactionRollbackException` / `DatabaseException`）⇒ **C 侧沿用同款 4 个内部类** |

**1.3 决策锁**

**换「事务边界独立成 Bean」（B 侧已独立成 Bean → C 侧沿用，4 处同时改）**：
1. `domain/service/MessageAtomicService.java` 承载 6 个 `@Transactional` 方法；
2. `domain/service/MessageDomainService.java` **通过注入调用**（不得 `this.` 自调用）；
3. 事务方法签名与 B 侧的 `createConversationWithMessageTx` / `createOrSendMessageTx` 一一对应；
4. 断言层：必须有「事务真的生效」的守门测试（真 PG），不能只看 `@Transactional` 注解存在。

**为什么换**：Spring 的 `@Transactional` 靠 AOP 代理生效，**代理只拦截从容器外部进来的调用**；B 侧的 `MessageAtomicService` 已是「独立 Bean + 代理」的形态 ⇒ **必须跨 Bean 调用**，自调用会让注解**静默失效**（代码看着有事务、运行时是 auto-commit、不报错）。

**换「rpc 侧协作者不合并」（1 处决策 + 3 处保持独立）**：
1. `MessageRpcSupport`（常量表 + 算法片段）保持独立；
2. `MessageAtomicService`（事务边界）保持独立；
3. `GoStylePagination`（分页子句口径）保持独立；
4. 只有 9 个**用例**合并进 `MessageDomainService`。

**为什么换**：协作者与事务边界的变化原因独立（`GoStylePagination` 只随分页口径变，`MessageRpcSupport` 只随角色/状态机变），合并会让这些规则埋进一个大类。

---

### 1.4 错误契约（`message-common/.../errmsg/`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-common/.../errmsg/MessageErrorCode.java` + `MessageErrorMessages.java` + `MessageGrpcError.java` | `service/message/src/main/java/com/rag/message/contract/ErrCodes.java` + `ErrMsg.java` + `CodeError.java` + `GrpcErrorMapper.java` | 1:N | ⚠️ **文案是英文**（`"service busy"` / `"internal server error"` / `"unauthorized"`）⇒ 与 article / comment / favorite / like / user 域的中文文案**逐域隔离，不可统一化**；C 侧 4 个类的 javadoc 已把这条写成硬约束（⚠️ 本行旧版把 C 路径误写成 `service/users/common/...` ⇒ 那是 **users 域**的路径，message 域的错误契约在**域内 `contract/` 包**（D7）· 核对日期 2026-11-02） |
| `MessageGrpcError.CodeError`（内部类） | `service/message/src/main/java/com/rag/message/contract/CodeError.java`（顶层类） | 1:1 | C 把它提到顶层 ⇒ 与 C 侧统一错误模型（`BusinessException`）并存 |
| `MessageGrpcError.bizCodeFromError(Throwable)` | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` | 1:1 | 反查顺序：先按 message 反查 code → 查不到才按 gRPC 标准码 fallback（`InvalidArgument→5003` / `Unauthenticated→5004` / `PermissionDenied→5005` / `AlreadyExists→5006` / `NotFound→5002` / `Internal→5001` / default→`1015`） |
| `MessageGrpcError.grpcError(Status.Code, int)` | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` | 1:1 | B 侧是 `MessageGrpcError.grpcError(grpcCode, bizCode)` —— **业务码塞进 gRPC status 的 description 字段** |
| `MessageErrorMessages`（静态 map） | `service/message/src/main/java/com/rag/message/contract/ErrMsg.java` | 1:1 | **20 条**文案逐字照抄 B 侧 |
| `MessageErrorCode`（enum，**20 常量**） | `service/message/src/main/java/com/rag/message/contract/ErrCodes.java` | 1:1 | 见 §6 码值表（⚠️ 本表旧版写「21 常量 + SUCCESS」与「22 个码」= **计数错**，B 侧实测 **20 个常量**；已按 D16「B 侧为准」更正 —— 核对日期 2026-11-02） |

**1.4 决策锁**

**换「错误契约逐域隔离」（C 新增约束，4 处同时改）**：
1. `ErrCodes.java` 只放 message 域的 **20 个**码（⚠️ 旧版写「22 个」= 计数错，B 侧实测 20 个常量 ⇒ 按 D16 更正 · 核对日期 2026-11-02），**不引入共享基座**；
2. `ErrMsg.java` 只放 message 域的英文文案，**不与中文域合并**；
3. `GrpcErrorMapper.java` 的 fallback 表按 message 域的码值空间写死；
4. `CodeError.java` 保持 `{code,msg}` 双角色（error + JSON 体）。

**为什么换**：同一语义在不同域的**码值不同、文案语言也不同**（`ErrorDbUpdate`：favorite=`5008` / comment·like·user·article=`5002`；`CodeServerBusy`：message=`"service busy"` / article·comment·favorite·like·user=`"服务繁忙"`）⇒ 统一化会直接改掉 8 个域的可见行为（L1）。

---

### 1.5 B 有、C 无 / B 有、C 换位置（共享件下沉清单）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-api/.../config/GoStyleMapStringValueDeserializer.java` | `common/src/main/java/com/rag/common/web/MapStringValueDeserializer.java` | 1:1 | D3：下沉到根级 starter |
| `message-api/.../config/MessageGoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:N | 同上；C 侧改成 **Jackson 2** 的 `CoercionConfig` 装配点（D14：全项目 = Jackson 2 + Boot 3.5）|
| `message-rpc/.../mapper/JsonbMapTypeHandler.java` | `service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java` | 1:1 | ⚠️ **本表旧版写「→ `common/.../JsonbTypeHandler.java`（D3 上提）」= 不成立**：共享层那份是 **jsonb ↔ `String`**（`BaseTypeHandler<String>`），B 侧这份是 **jsonb ↔ `Map<String,String>`** ⇒ **类型不兼容**，顶替会编译不过。共享层是**收口域**写权（AGENTS.md §五.3 规则 3）⇒ 本 lane **不得改 `common/`** ⇒ 域内保留照抄副本，并登记为待收口项（核对日期 2026-11-02） |
| `message-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 1:1 | B 侧 9 份副本 → C 侧 1 份（D3 的直接账单） |
| `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | 共享层整体下沉；**信封是 L1，不能换成 `ProblemDetail`** |
| `service/common/.../observability/` | `common/src/main/java/com/rag/common/observability/`（3 类：Observation + `GoStyleRequestDeadline` + `ObservabilityCustomizer`） | 1:N | Micrometer `Observation` + OTel bridge 吸收 |
| `service/common/.../logger/` | `common/src/main/java/com/rag/common/logger/`（4 类：SLF4J 适配器 + 日志选项） | 1:N | 格式与字段保留，生态换成 SLF4J |
| `service/common/.../snowflake/` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration.java` | 1:1 | epoch / 位段是业务契约 |
| `service/common/.../errmsg/` + `xcode/` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` + `common/src/main/java/com/rag/common/exception/BusinessException.java` + `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | N:1 | C 侧全局信封与业务异常基类在**根级 common**，**message 域不得反向定义** |

### 1.6 🔴 RAG 侧新增类补录（1 条 · 覆盖 2 个 `.java`）

> ⚠️ **本节是 2026-11-01 的增量**：RAG 在首版映射表之后新增了 `message-api/…/auth/` 包（2 类）
> ⇒ 首版 §1.1 / §1.2 的 **B 列漏了这 2 个 `.java`** ⇒ 本节逐个落到 C 侧；
> 补齐后 `tools/check-mapping-coverage.py` 的 **B 列双向对账归零**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `message-api/…/auth/MessageJwtAuthFilter.java` ＋ `message-api/…/auth/MessageJwtSignatureVerifier.java` | `service/message/src/main/java/com/rag/message/interceptor/MessageJwtAuthInterceptor.java` ＋ `service/message/src/main/java/com/rag/message/interceptor/MessageAdminJwtAuthInterceptor.java` | 1:N | ⚠️ RAG 把 JWT **验签**拆成两段（`DRIFT-MESSAGEAPI-009②`）：B 侧 `MessageJwtAuthFilter`（**Servlet Filter**）先验一次、**只记录不写响应**、无条件放行（来源标记 `SOURCE_FILTER` = 「验签在 **3s 预算之外**」）；最终 401 由 `HandlerInterceptor` 抛，**该处会再兜底验签一次**（来源标记 `SOURCE_INLINE` = 「验签在 **3s 预算之内**」）⇒ ⇒ **B 侧验签执行两次（预算外 + 预算内）** ⇒ C 侧**只有 2 个 `HandlerInterceptor`**（user / admin 各一）⇒ ⇒ **C 侧验签执行一次（只在预算内）** ⇒ 🔴 **C 侧缺口**：验签**次数**面与 B 侧不同（响应面逐条相同：401 + 空 body）⇒ 已**显式登记为签字偏离**（配套报告 §六·6-6 · 2026-11-02 已更正「B 侧在预算外」的不准确表述）。<br>⚠️ **2026-11-02 补记**：本域 `application.yml` 曾**完全没有 `observability` 段** ⇒ 3s HTTP 预算与 2s gRPC 超时**都不存在**（缺省即 `Duration.ZERO` = 不限时）⇒ 已补 `http-timeout: 3s` ＋ `rpc-timeout: 2s`（B 侧逐字对齐）⇒ **预算本身现已存在**（配套报告 §五·5-⑩）。⚠️ **两把 key（`UserAuth.AccessSecret` / `AdminAuth.AccessSecret`）＋ 两段路由表是 L1**；⚠️ user 拦截器必须 `excludePathPatterns("/message/v1/admin/**")`，否则 admin token 会被 user key 先拒一遍 |

---

## 2. 持久层差异

| 维度 | B RAG（MyBatis-Plus + Flyway，判定基准） | C RAG-V1（目标形态） | 备注 |
|---|---|---|---|
| ORM | MyBatis-Plus `BaseMapper<Entity>` + `LambdaQueryWrapper` | 同 B | D4「生态优先」 |
| 实体注解 | `@TableName` + `@TableId(type=IdType.ASSIGN_ID)` + `@TableField` | 同 B | 表名/列名显式声明 |
| JSONB 列 | `JsonbMapTypeHandler`（手写 `BaseTypeHandler`） | `service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java`（**域内照抄 B 侧**） | ⚠️ **不能用 MP 自带 `JacksonTypeHandler`**：它走 `ps.setString()` ⇒ PG 收到 VARCHAR ⇒ 写 `jsonb` 列被拒。⚠️ 也**不能**用共享层的 `common/.../JsonbTypeHandler.java`（jsonb ↔ `String`，类型不兼容）⇒ 域内保留 `Map<String,String>` 版 |
| Schema 演进 | Flyway `V1..V5`（`table: flyway_schema_history_message` 独立账本，`baseline-version: 0`） | 幂等 DDL 重放（`resources/db/migration/`，**当前为空**） | ⚠️ **C 侧 DDL 脚本还没落地** |
| 建表责任 | message-rpc 独占（Flyway） | 待定（设计文档 §5.2 指向 `MessageSchemaInitializer`，**文件不存在**） | 共享库共存问题见 `RAG/docs/DRIFT/_cross/` |
| 雪花 ID | `SnowflakeBean` 注入（`common` starter） | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | epoch/位段是契约 |
| 事务边界 | `MessageAtomicService` **独立 Bean** + `@Transactional`（6 个方法） | 同 B | ⚠️ 自调用 ⇒ 注解静默失效 |
| 单条原子 UPDATE | `MessageAtomicService.markNotificationReadTx` / `markAllNotificationsReadTx` / `markConversationReadTx` 同款单条 UPDATE（**不先 SELECT**） | 待填 | ⚠️ B 侧曾把 `MarkConversationRead` 拆成「message 表 UPDATE + conversation 表 `touchUpdatedAt`」**两次写**（已修）⇒ **只改 message 表，不顺带改写 `conversation.updated_at`** |
| 分页子句 | `mapper/GoStylePagination.java`（显式复刻「offset / limit **各自有条件**」的判据） | `service/message/src/main/java/com/rag/message/mapper/GoStylePagination.java` | ⚠️ 无条件拼 `LIMIT 20 OFFSET -1` ⇒ PG 直接报错（`OFFSET must not be negative`）⇒ 非法参数被降级成基础设施故障 |
| 排序 | `ConversationMapper.listByParticipant`（双键 `last_message_at desc, updated_at desc`；次级键 `updated_at` 的取舍曾登记为漂移项） | 待填 | 次级键影响 `ConversationItem.updated_at` 的取值 |
| 计数口径 | `NotificationMapper` 同链三计数（`total` / `unread` 在**同一条 query 链**上算） | 待填 | ⚠️ `total` 被 `unread_only` 过滤过一次（已登记为 rpc 侧 MISMATCH） |
| 时间列 | Flyway `V5__message_time_columns_timestamptz.sql`（8 列 `TIMESTAMP ⇒ TIMESTAMPTZ`，转换时区 `Asia/Shanghai`） | 待填 | 会话时区必须钉 `Asia/Shanghai`（B 侧 DSN 就钉死它） |
| 唯一性对象 | Flyway `V4` 显式 `CREATE UNIQUE INDEX IF NOT EXISTS`（不是 `ADD CONSTRAINT`） | 待填 | 对象类型不同（约束 vs 索引）会被运维工具看出差别 |

**2 决策锁**

**换「B 侧 Flyway 配置照抄 + 独立账本表名」（3 处同时改 · 2026-11-02 落地）**：
1. `service/message/src/main/resources/db/migration/` 落地 **V1~V5 五个脚本**（**逐字节照抄 B 侧** `message-rpc/src/main/resources/db/migration/`，已 `cmp` 校验 IDENTICAL）；
2. **不新增** `wiring/MessageSchemaInitializer.java`（本表旧版要求新建 = **已撤销**，见下方「为什么这么定」）；
3. `application.yml` **保留并补全** `spring.flyway` 段：`table: flyway_schema_history_message` + `baseline-on-migrate: true` + **`baseline-version: 0`** + `locations: classpath:db/migration` + `schemas: public`。

**为什么这么定（推翻本表旧版「Flyway ⇒ 幂等 DDL 重放」）**：
- **旧版决策的三个前提已经变了**：① `service/message/pom.xml` **本来就引了** `flyway-core` + `flyway-database-postgresql`（文件树与 pom 都保留 Flyway）⇒ 「去掉 Flyway 配置」与文件树冲突；② AGENTS.md §六 写明「**其余 7 域**待拍板」时给的候选就是「**B 侧做法 = 各域独立账本表名 + `baseline-on-migrate: true` + `baseline-version: 0` 必钉**」⇒ **照抄 B 侧**才是与 AGENTS.md 一致的那个候选；③ **users 域弃用 Flyway 是因为 B 侧 users 自己就用的是 `SchemaInitializer`**（D17「照抄 B 侧」）⇒ **message 域的 B 侧用的是 Flyway** ⇒ 按 D16「B 侧是唯一 oracle」应当**照抄 Flyway**，而不是照抄 users 的形态。
- **独立账本表名已经把「撞号」这个原始病灶消掉了**：`flyway_schema_history` 是 schema 级账本，而本域改用 `flyway_schema_history_message` ⇒ 与 like / article 的账本**互不干扰** ⇒ 「幂等重放」这个替代方案**不再必要**。
- ⚠️ **`baseline-version: 0` 是硬要求**（B 侧 2026-10-24 实测修掉的真缺陷）：非空 schema + 默认 `baseline-version=1` ⇒ baseline 打在 V1 ⇒ **V1 的 SQL 从未执行** ⇒ `message_notification` 表永远建不出来 ⇒ 服务起不来。`baseline-version: 0` ⇒ baseline 打在 version 0 ⇒ **V1 之后全部迁移照常执行**。
- ⚠️ 本条与 `docs/File_Tree/message.md`（**文件树 = 权威**）一致：文件树里 `wiring/` 只有 `NacosRegistration.java`、`resources/db/migration/` 存在、pom 带 Flyway 依赖 ⇒ **本仓 message 域的落地形态就是 Flyway**。

**换「B 侧 MyBatis-Plus 写法落地到 C 侧」（5 处同时改）**：
1. `domain/entity/*Entity.java` 换注解（`@TableName` + `@TableId`），字段名不变；
2. `mapper/*Mapper.java` 继承 `BaseMapper<Entity>`，调用点用 `LambdaQueryWrapper`；
3. `mapper/GoStylePagination.java` 承担分页子句口径；
4. `service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java` 承担 jsonb 列（域内照抄 B 侧；共享层那份是 jsonb ↔ `String`，类型不兼容）；
5. 建表来源 = **B 侧的 V1~V5 Flyway 脚本**（逐字节照抄）⇒ 不改。

**为什么换**：MyBatis-Plus 的 `LambdaQueryWrapper` 比链式 Mapper 更直观，且显式 SQL 可被逐条审；但**写法换掉不等于语义换掉** —— 每条查询条件、排序键、计数口径都必须逐条回到 B 侧源码对照。

---

## 3. HTTP / DI 差异

| 维度 | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|
| 路由注册 | `WebMvcConfig.addInterceptors` + Controller 上的 `@RequestMapping` / `@PostMapping` | 同 B | Spring 注解式路由取代代码式注册 |
| 前缀 | `/message/v1`（8 个）+ `/message/v1/admin`（5 个） | 同（逐条一致） | **两组前缀都是 L1，逐字保留** |
| 端点总数 | **13**（8 user + 5 admin） | 13 | 方法分布 **GET 1 + POST 12** |
| Controller | `MessageController`（8 `@PostMapping`）+ `MessageAdminController`（5） | 同 B | controller 层不做业务，只做解析 + 信封 |
| Service 分层 | Controller → 13 个 `*Service` → `MessageRpcClient`（gRPC） | Controller → `MessageApiService` → `MessageDomainService`（**直连**） | D2：域内不再自我 gRPC 往返 |
| 错误表达 | `GlobalResponseAdvice` + `GlobalExceptionHandler` ⇒ **HTTP 恒 200** + `{code,msg,data}` 信封 | `MessageApiResponseAdvice` + `MessageApiExceptionHandler` | ⚠️ **不是 text/plain**：框架层错误（404 / 405 等）才是 text/plain，业务错误恒 200 + 信封 |
| 统一错误模型 | api 层把 gRPC status 映射回业务码（`MessageGrpcError.bizCodeFromError`） | `BusinessException(code,msg)` + 2 个边界适配器（HTTP advice / gRPC mapper） | D2 附带的统一错误模型（§5.3） |
| JWT 校验 | `AbstractMessageJwtAuthInterceptor` + 2 个子类 | `AbstractMessageJwtAuthInterceptor` + `MessageJwtAuthInterceptor` + `MessageAdminJwtAuthInterceptor`（✅ **抽象基类已于 2026-11-01 补齐**） | ⚠️ claim 取值判定属业务层（`UserIDFromContext` 失败 ⇒ 业务码 5004 + HTTP 200），**不是 401** |
| claim 名称 | `"userId"`（`AbstractMessageJwtAuthInterceptor.CLAIM_ATTRIBUTE`） | 待填 | 小写 u、驼峰式，**不能写成 `user_id`** |
| 配置绑定 | `@ConfigurationProperties`（`message-api` / `message` 两个前缀） | 同 B | 类型安全 + IDE 补全 |
| 装配根 | Spring DI（`@Component` / `@Bean`），**无 ServiceContext 类** | 同 B | `ServiceContext` 被 DI 容器吸收 |
| 服务发现 | Nacos（`discovery:///message-rpc` 等） | Nacos（`spring.cloud.nacos.discovery`） | 寻址语义等价 |
| gRPC 明文 | devh 默认 TLS ⇒ 必须显式 `negotiation-type: plaintext` | 同 B | ⚠️ 不写会静默发 TLS ClientHello |
| 进程拓扑 | **两个进程**：`message-api`（HTTP `8892`）+ `message-rpc`（gRPC `8082`） | **一个进程**（HTTP `8883` + gRPC `8084`） | D1（⚠️ **端口值两侧不同** —— 端口属 L1，须在 DRIFT 台账登记） |
| 熔断 | resilience4j | `common/src/main/java/com/rag/common/protection/` + resilience4j | ⚠️ 失败窗口 / half-open 策略不同（L3 降级） |
|CPU 降载|`common/protection/CpuThresholdHealthIndicator`|同 B|同上|
|请求体上限|`common/protection/MaxBytesFilter`|同 B|chunked 请求会绕过（刻意保留）|

**3 决策锁**

**换「api + rpc 两个进程 ⇒ 一个进程」（D1，8 处同时改）**：
1. `MessageServiceApplication.java` 一个入口（`@SpringBootApplication` + `@EnableDiscoveryClient`）；
2. `advice + config + controller + dto + interceptor + metrics + service` 与 `wiring + client + config + domain/entity + domain/service + exception + grpc + mapper + metrics + service` 两个子包共处一个模块；
3. `service/MessageApiService.java` **直连注入** `domain/service/MessageDomainService.java`（不注入 stub）；
4. `{advice,config,controller,dto,interceptor,metrics,service}/client/` 整个包清空；
5. `application.yml` 同时配 `server.port`（HTTP）与 `grpc.server.port`（gRPC）；
6. Nacos 注册只注册**一个**服务名；
7. 错误表达统一到一个 `BusinessException` + 2 个边界适配器；
8. 观测：api 与 rpc 的指标 namespace 仍是 `message_api_*` / `message_rpc_*`（**不改名**）。

**为什么换**：Spring Cloud 的部署单元是「可部署服务」；api/rpc 分离源于两条 codegen 路径，是**语言产物**而非架构原则。代价：「rpc 进程不可用」这一故障模式消失、域的独立扩容能力消失。

**换「gRPC code ⇒ 业务码的两层映射 ⇒ 一个业务异常」（D2 附带，4 处同时改）**：
1. 业务层只抛 `BusinessException(code,msg)`；
2. gRPC 边界由 `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` 转 `StatusRuntimeException`；
3. HTTP 边界由 `advice/MessageApiExceptionHandler.java` 直接读 `BusinessException.code`；
4. `contract/GrpcErrorMapper.java` 保留「message 反查 code → gRPC 标准码 fallback」的域内反查表。

**为什么换**：合并后同一份业务逻辑要同时产出 HTTP 与 gRPC 两种错误表达 ⇒ 业务码只能有一份，两个边界各做一次转换。

---

## 4. 公共模块对照（message 域 ↔ `common/` 的边界）

| 职责 | B RAG（`service/common/`） | C RAG-V1（根级 `common/` starter） | 边界规则 |
|---|---|---|---|
| HTTP 信封 `{code,msg,data}` | `common/response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | **信封是 L1**，客户端按它解析 ⇒ 不可换 `ProblemDetail` |
|业务异常基类|`common/exception/BusinessException.java`|`common/src/main/java/com/rag/common/exception/BusinessException.java` + `AuthenticationException.java`|域内**不重复定义**|
| JWT 工具 | `users-common/.../jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` + `BearerTokenExtractor.java` | message 域**只验签不签发** |
|雪花 ID|`common/snowflake/`|`common/src/main/java/com/rag/common/snowflake/Snowflake.java`|epoch / 位段是契约（like 域游标依赖 `>>22`）|
| 观测（http/rpc/mq） | `common/observability/` | `common/src/main/java/com/rag/common/observability/`（3 类） | span 名称/属性需逐条对拍 |
| 熔断 / CPU 降载 / MaxBytes | `common/protection/` | `common/src/main/java/com/rag/common/protection/`（2 类） | 生态缺口，策略差异属 L3 |
|日志|`common/logger/`|`common/src/main/java/com/rag/common/logger/`（4 类，SLF4J）|`call_chain` 等是行为契约|
| JSONB TypeHandler | `message-rpc/.../mapper/JsonbMapTypeHandler.java` | `service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java` | ⚠️ **无法上提到共享层** —— 共享层 `common/.../JsonbTypeHandler.java` 是 **jsonb ↔ `String`**（`BaseTypeHandler<String>`），与 B 侧这份 **jsonb ↔ `Map<String,String>`** **类型不兼容** ⇒ 域内保留照抄副本（待收口域处理） |
| 整数字段解码（`,string`） | `message-api/.../dto/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | 同上 |
| Jackson 类型强转口径 | `message-api/.../config/{GoStyleMapStringValueDeserializer,MessageGoStyleCoercion}.java` | `common/src/main/java/com/rag/common/web/MapStringValueDeserializer.java` + `common/src/main/java/com/rag/common/web/Coercion.java` + `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 同上 |
| MyBatis-Plus 装配桥 | `message-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | B 侧 9 份副本 → C 侧 1 份 |
| **域内错误码 / 文案 / gRPC 转换** | `message-common/.../errmsg/` | **`service/message/src/main/java/com/rag/message/contract/{ErrCodes,ErrMsg,CodeError,GrpcErrorMapper}.java`** | ⚠️ **不共享**！逐域隔离（英文文案） |

**4 决策锁**

**换「共享件从域内提到 starter」（D3，6 处同时改）**：
1. `JsonbMapTypeHandler` → ⚠️ **留在域内** `service/message/.../mapper/JsonbMapTypeHandler.java`（照抄 B 侧）；**不能**上提到 `common/`（共享层那份是 jsonb ↔ `String`，类型不兼容，且 `common/` 是收口域写权）；
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
| 1 | **双 JWT 拆成两组**（`/message/v1` UserAuth · `/message/v1/admin` AdminAuth） | §1.1 | 6 | B 侧是两组拦截器各带一把 key，合成一把等于取消鉴权边界 |
| 2 | **13 个 `*Service` ⇒ 1 个 `MessageApiService`** | §1.2 | 4 | B 侧 13 个 `*Service` 是用例级样板拆分，不是职责边界 |
| 3 | **DTO ⇒ immutable record**（D5） | §1.2 | 3 | 可变 POJO → record，配合 Bean Validation |
| 4 | **事务边界独立成 `MessageAtomicService`** | §1.3 / §2 | 4 | Spring `@Transactional` 自调用会**静默失效**，必须跨 Bean 调用 |
| 5 | **rpc 侧协作者保持独立**（`MessageRpcSupport` / `GoStylePagination` / `MessageAtomicService`） | §1.3 | 4 | 协作者与事务边界的变化原因独立 |
| 6 | **错误契约逐域隔离**（英文文案） | §1.4 | 4 | 8 个域的码值与文案语言都不同，统一化会改掉可见行为 |
| 7 | **Flyway ⇒ 幂等 DDL 重放** | §2 | 3 | Flyway 的 schema 级账本在共享库会撞号 ⇒ 改无账本的幂等重放 |
| 8 | **MyBatis-Plus 显式 SQL** | §2 | 5 | 显式 SQL 可逐条审；但每条查询条件仍要回 B 侧源码对照 |
| 9 | **api + rpc 合并为单进程**（D1） | §3 | 8 | Spring 的部署单元是「可部署服务」，api/rpc 分离源于两条 codegen 路径，是**语言产物** |
| 10 | **统一错误模型**（`BusinessException` + 2 个边界适配器） | §3 | 4 | 合并后同一份业务逻辑要产出 HTTP 与 gRPC 两种错误表达 |
| 11 | **共享件下沉到根级 `common/` starter**（D3） | §4 | 6 | 普通 jar ⇒ 每个消费者手写 `@Import` ⇒ MP 装配桥被复制 9 份 |
| 12 | **域内 api→rpc 改直连**（D2） | §0 / §3 | 3（`api/client/` 清空 + 直接注入 + 无 stub） | 同进程内自我 gRPC 往返是无意义的序列化开销与故障注入点 |

---

## 6. 跨模块全局约定（逐条核对 **B 侧源码**）

| 项 | 值 | 来源（B 侧源码 `文件:行号`） | C 列落点 |
|---|---|---|---|
| **proto 字段号** | `message.proto` 逐条一致（B 侧与 RAG-V1 两份 `.proto` **只差 `option java_package`**：`com.rag.message.message.proto` → `com.rag.proto.message`） | `service/message/message-proto/src/main/proto/message.proto` | `proto/src/main/proto/message.proto` |
| ↳ `SendNotificationResp.affected` | `int64 affected = 3;`（**无 `,string`**） | `service/message/message-proto/src/main/proto/message.proto:98`；HTTP 侧 `message-api/.../dto/AdminSendNotificationResp.java:43`（`@JsonFormat(shape = STRING)` ⇒ **B 侧输出 JSON 字符串**） | ⚠️ **B 侧输出 JSON 字符串**（`@JsonFormat(Shape.STRING)`）—— 该形态相对更早的源实现是**已签字的有意偏离**（`SD-2026-10-23-03`，详见 B 侧 javadoc）⇒ C 侧 `service/message/src/main/java/com/rag/message/dto/notification/AdminSendNotificationResp.java` 照抄 B 侧形态。**本表只点明差异存在，不下「该修 / 不该修」的结论** |
| **错误码** | **20 个**常量：`Success=200` / `Error=500` / `CodeServerBusy=1015` / `ErrorServerCommon=5001` / `ErrorNotFound=5002` / `ErrorInvalidParam=5003` / `ErrorUnauthorized=5004` / `ErrorForbidden=5005` / `ErrorAlreadyExist=5006` / `ErrorDbSelect=5007` / `ErrorDbUpdate=5008` / `ErrorUserNotFound=5101` / `ErrorAdminNotFound=5102` / `ErrorSelfChat=5201` / `ErrorConversationMiss=5202` / `ErrorNotificationMiss=5203` / `ErrorChatPending=5204` / `ErrorAdminOnlyInit=5205` / `ErrorFirstMsgTooLong=5206` / `ErrorUnsupportedRole=5207`（⚠️ 本行旧版写「22 个常量」而列举的**恰是 20 个** ⇒ 计数错，按 D16 以 B 侧为准更正 · 核对日期 2026-11-02） | `service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageErrorCode.java:25-106` | `service/message/src/main/java/com/rag/message/contract/ErrCodes.java` |
| **错误文案** | **英文**：`"service busy"` / `"internal server error"` / `"record not found"` / `"invalid parameter"` / `"unauthorized"` / `"forbidden"` / `"already exists"` / `"database query failed"` / `"database update failed"` / `"user not found"` / `"admin not found"` / `"self chat is not allowed"` / `"conversation not found"` / `"notification not found"` / `"waiting for the other side to reply"` / `"only admin can initiate this conversation"` / `"first private message cannot exceed 50 characters"` / `"unsupported sender role"` / `"OK"` / `"FAIL"` | `service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageErrorMessages.java:53-74` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java`（⚠️ **逐域隔离，不可与中文域统一**） |
| **gRPC fallback 码表** | `InvalidArgument→5003` / `Unauthenticated→5004` / `PermissionDenied→5005` / `AlreadyExists→5006` / `NotFound→5002` / `Internal→5001` / `default→1015` | `service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageGrpcError.java:255-271`（`mapStatusCodeToBizCode`） | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` |
| **JWT claims** | `userId`（小写 u、驼峰）；claim 取值失败 ⇒ `ErrorUnauthorized(5004)` | `service/message/message-api/src/main/java/com/rag/message/message/api/interceptor/AbstractMessageJwtAuthInterceptor.java:70, 114` + `message-api/.../service/MessageApiSupport.java:85` | `service/message/src/main/java/com/rag/message/interceptor/*.java`（写 request attribute）+ `service/message/src/main/java/com/rag/message/service/MessageApiSupport.java`（取用） |
| ↳ JWT secret / expire | user：`${USER_JWT_SECRET}` / `${USER_JWT_EXPIRE:31536000}`（365 天）；admin：`${ADMIN_JWT_SECRET}` / `${ADMIN_JWT_EXPIRE:2592000}`（30 天） | `service/message/message-api/src/main/resources/application.yml:99-104` | `service/message/src/main/java/com/rag/message/config/MessageApiProperties.java` + `application.yml` |
| **表名** | `message_notification` / `message_conversation` / `message_conversation_message` | `service/message/message-rpc/src/main/java/com/rag/message/message/rpc/domain/{NotificationEntity,ConversationEntity,ConversationMessageEntity}.java` 的 `@TableName` | `service/message/src/main/java/com/rag/message/domain/entity/*Entity.java` 的 `@TableName` |
| ↳ 唯一性对象 | `uk_conversation_pair` ⇒ 建出来是**唯一索引**（不在 `pg_constraint`） | `service/message/message-rpc/src/main/resources/db/migration/V4__conversation_pair_unique_index.sql:33` | `service/message/src/main/java/com/rag/message/mapper/*` + DDL 脚本（**待落地**） |
| **HTTP path** | **13 个端点**：<br>**user 组 `/message/v1`**：`POST /conversations/list`、`POST /conversations/messages`、`POST /conversations/read`、`POST /conversations/send`、`POST /notifications/list`、`POST /notifications/read`、`POST /notifications/readall`、**`GET /unread`**<br>**admin 组 `/message/v1/admin`**：`POST /conversations/list`、`POST /conversations/messages`、`POST /conversations/read`、`POST /conversations/send`、`POST /notifications/send`<br>⇒ 方法分布 **GET 1 + POST 12** | `service/message/message-api/src/main/java/com/rag/message/message/api/controller/MessageController.java:52-136` + `MessageAdminController.java:56-127` | `service/message/src/main/java/com/rag/message/controller/MessageController.java` + `service/message/src/main/java/com/rag/message/controller/MessageAdminController.java` |
| ↳ ⚠️ C 侧 javadoc 与 B 侧不一致 | C 侧两个 Controller 的 javadoc 写的是 `/message/v1/conversation/list`、`/notification/read/all`、`/unread/summary`、`/chat/send`，并把 admin 的 `conversation/list` 与 `conversation/messages` 标成 `GET` —— **与 B 侧源码逐条不符**（B 侧是复数 `/conversations/*`、`/notifications/*`，`/unread` 是 GET，admin 组 5 个端点**全是 POST**） | 同上 | ⚠️ 实现时以 B 侧为准；这两段 javadoc 需要更正 |
| **Kafka topic** | **无 —— message 域完全不涉及 MQ。** B 侧 `service/message/**` 对 `kafka` / `kqueue` / `mq` 的引用数为 **0** | `grep -rln "org.apache.kafka\|springframework.kafka" service/message/` ⇒ 0 命中 | 无对应物 |
| ↳ comment 域 MQ 载荷的归属澄清 | B 侧 comment 域的 MQ 载荷契约是 **`service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditMessage.java`**（**不在 message 域目录下**），topic 定义在 `comment-rpc/.../config/CommentKafkaTopics.java` ⇒ 它是 comment 域的域内 MQ 契约，**message 域既不定义也不引用** | `service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditMessage.java:52` | 无对应物（不属 message 域） |
| **指标名（api 3 个）** | `message_api_request_total{route,result}` / `message_api_reject_total{route,reason}` / `message_api_request_duration_seconds{route}`（⚠️ **B 侧 Java 未钉桶** ⇒ Micrometer 默认桶 ⇒ Prometheus 导出为 **summary**（`_count`/`_sum`/`_max`），**没有** `_bucket` 系列；**与 Go 的 `DefBuckets` 直方图形状不同 —— 这是 B 侧自带的差异，C 侧逐字继承，不回溯 Go** · 核对日期 2026-11-02） | `service/message/message-api/src/main/java/com/rag/message/message/api/metrics/MessageApiMetrics.java:81-84` | `service/message/src/main/java/com/rag/message/metrics/MessageApiMetrics.java` |
| **指标名（rpc 5 个）** | `message_rpc_request_total{method,result}` / `message_rpc_request_duration_seconds{method}` / `message_rpc_notification_action_total{kind,result}` / `message_rpc_chat_action_total{action,result}` / `message_rpc_db_error_total{action,type}` | `service/message/message-rpc/src/main/java/com/rag/message/message/rpc/metrics/MessageMetrics.java:45-57` | `service/message/src/main/java/com/rag/message/metrics/MessageRpcMetrics.java` |
| **gRPC 方法（9 个）** | `SendNotification` / `ListNotifications` / `MarkNotificationRead` / `MarkAllNotificationsRead` / `GetUnreadSummary` / `ListConversations` / `GetConversationMessages` / `SendChatMessage` / `MarkConversationRead` | `service/message/message-rpc/src/main/java/com/rag/message/message/rpc/service/impl/MessageRpcServiceImpl.java:72-113`（9 个 `@Override`） | `service/message/src/main/java/com/rag/message/grpc/MessageGrpcService.java`（9 个 override） |
| **枚举** | `NotificationKind`（UNSPECIFIED=0 / ADMIN_BROADCAST=1 / ADMIN_NOTICE=2 / ARTICLE_COMMENT=3 / COMMENT_REPLY=4 / ARTICLE_PUBLISHED=5 / ARTICLE_LIKED=6）· `SenderRole`（UNSPECIFIED=0 / USER=1 / ADMIN=2 / SYSTEM=3）· `ConversationStatus`（UNSPECIFIED=0 / PENDING=1 / OPEN=2） | `service/message/message-proto/src/main/proto/message.proto:7-28` | `proto/src/main/proto/message.proto:7-28` |
| **角色写入规则** | user 组 API 恒 `SenderRole.USER`；admin 组恒 `SenderRole.ADMIN`（admin 发消息的 `RecipientRole` 恒 `USER`） | `service/message/message-api/src/main/java/com/rag/message/message/api/service/Message*Service.java`（user 组 8 个 + admin 组 5 个） | `service/message/src/main/java/com/rag/message/service/MessageApiService.java`（硬编码，不从 JWT 推导） |
| **配置键（rpc 业务段）** | `List.DefaultLimit=20` / `List.MaxLimit=100` / `Broadcast.PageSize=200` | `service/message/message-rpc/src/main/resources/application.yml:279-285` | `service/message/src/main/java/com/rag/message/config/MessageProperties.java`（`@ConfigurationProperties(prefix="message")`） |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏（RAG-V1 `service/message/` 全部手写 `.java`）

自查命令：

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/message" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u | wc -l
# ⇒ 53
```

> 📌 **2026-11-02**：旧版自报 **52** ⇒ 本轮新增 `mapper/JsonbMapTypeHandler.java`（B 侧同名文件的**域内照抄副本**，见 §1.5 / §4）⇒ **53**。
> ⚠️ 该文件**不**计入「proto 生成物」—— 它是手写源码。

**逐条落位（53 / 53 = 100%）**

| # | C 列文件 | 落位小节 |
|---|---|---|
| 1 | `service/message/src/main/java/com/rag/message/contract/CodeError.java` | §1.4 |
| 2 | `service/message/src/main/java/com/rag/message/contract/ErrCodes.java` | §1.4 |
| 3 | `service/message/src/main/java/com/rag/message/contract/ErrMsg.java` | §1.4 |
| 4 | `service/message/src/main/java/com/rag/message/contract/GrpcErrorMapper.java` | §1.4 |
| 5 | `service/message/src/main/java/com/rag/message/MessageServiceApplication.java` | §1.1 / §1.3 |
| 6 | `service/message/src/main/java/com/rag/message/advice/MessageApiExceptionHandler.java` | §1.1 |
| 7 | `service/message/src/main/java/com/rag/message/advice/MessageApiResponseAdvice.java` | §1.1 |
| 8 | `service/message/src/main/java/com/rag/message/config/MessageApiProperties.java` | §1.1 |
| 9 | `service/message/src/main/java/com/rag/message/config/WebMvcConfig.java` | §1.1 |
| 10 | `service/message/src/main/java/com/rag/message/controller/MessageAdminController.java` | §1.2.1 |
| 11 | `service/message/src/main/java/com/rag/message/controller/MessageController.java` | §1.2.1 |
| 12 | `service/message/src/main/java/com/rag/message/dto/chat/AdminSendChatReq.java` | §1.2.2 |
| 13 | `service/message/src/main/java/com/rag/message/dto/notification/AdminSendNotificationReq.java` | §1.2.2 |
| 14 | `service/message/src/main/java/com/rag/message/dto/notification/AdminSendNotificationResp.java` | §1.2.2 |
| 15 | `service/message/src/main/java/com/rag/message/dto/conversation/ChatMessageItem.java` | §1.2.2 |
| 16 | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationItem.java` | §1.2.2 |
| 17 | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationListResp.java` | §1.2.2 |
| 18 | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationMessagesReq.java` | §1.2.2 |
| 19 | `service/message/src/main/java/com/rag/message/dto/conversation/ConversationMessagesResp.java` | §1.2.2 |
| 20 | `service/message/src/main/java/com/rag/message/dto/common/EmptyReq.java` | §1.2.2 |
| 21 | `service/message/src/main/java/com/rag/message/dto/read/MarkConversationReadReq.java` | §1.2.2 |
| 22 | `service/message/src/main/java/com/rag/message/dto/notification/MarkNotificationReq.java` | §1.2.2 |
| 23 | `service/message/src/main/java/com/rag/message/dto/notification/NotificationItem.java` | §1.2.2 |
| 24 | `service/message/src/main/java/com/rag/message/dto/notification/NotificationListResp.java` | §1.2.2 |
| 25 | `service/message/src/main/java/com/rag/message/dto/common/PageReq.java` | §1.2.2 |
| 26 | `service/message/src/main/java/com/rag/message/dto/chat/SendChatResp.java` | §1.2.2 |
| 27 | `service/message/src/main/java/com/rag/message/dto/read/UnreadSummaryResp.java` | §1.2.2 |
| 28 | `service/message/src/main/java/com/rag/message/dto/chat/UserSendChatReq.java` | §1.2.2 |
| 29 | `service/message/src/main/java/com/rag/message/interceptor/MessageAdminJwtAuthInterceptor.java` | §1.1 / §1.2.1 |
| 30 | `service/message/src/main/java/com/rag/message/interceptor/MessageJwtAuthInterceptor.java` | §1.1 / §1.2.1 |
| 31 | `service/message/src/main/java/com/rag/message/interceptor/AbstractMessageJwtAuthInterceptor.java` | §1.2.1 |
| 32 | `service/message/src/main/java/com/rag/message/metrics/MessageApiMetrics.java` | §1.1 |
| 33 | `service/message/src/main/java/com/rag/message/service/MessageApiService.java` | §1.2.1 |
| 34 | `service/message/src/main/java/com/rag/message/service/MessageApiSupport.java` | §1.2.1 |
| 35 | `service/message/src/main/java/com/rag/message/wiring/NacosRegistration.java` | §1.3 |
| 36 | `service/message/src/main/java/com/rag/message/client/AdminRpcClient.java` | §1.3 |
| 37 | `service/message/src/main/java/com/rag/message/client/UserRpcClient.java` | §1.3 |
| 38 | `service/message/src/main/java/com/rag/message/config/MessageProperties.java` | §1.3 |
| 39 | `service/message/src/main/java/com/rag/message/domain/entity/ConversationEntity.java` | §1.3 |
| 40 | `service/message/src/main/java/com/rag/message/domain/entity/ConversationMessageEntity.java` | §1.3 |
| 41 | `service/message/src/main/java/com/rag/message/domain/entity/NotificationEntity.java` | §1.3 |
| 42 | `service/message/src/main/java/com/rag/message/exception/MessageExceptions.java` | §1.3 |
| 43 | `service/message/src/main/java/com/rag/message/mapper/ConversationMapper.java` | §1.3 |
| 44 | `service/message/src/main/java/com/rag/message/mapper/ConversationMessageMapper.java` | §1.3 |
| 45 | `service/message/src/main/java/com/rag/message/mapper/GoStylePagination.java` | §1.3 |
| 46 | `service/message/src/main/java/com/rag/message/mapper/NotificationMapper.java` | §1.3 |
| 47 | `service/message/src/main/java/com/rag/message/metrics/MessageMetrics.java` | §1.3（C 新增 · **共享基座**，2026-11-02 明确分工） |
| 48 | `service/message/src/main/java/com/rag/message/metrics/MessageRpcMetrics.java` | §1.3 |
| 49 | `service/message/src/main/java/com/rag/message/domain/service/MessageAtomicService.java` | §1.3 |
| 50 | `service/message/src/main/java/com/rag/message/domain/service/MessageDomainService.java` | §1.3 |
| 51 | `service/message/src/main/java/com/rag/message/service/MessageRpcSupport.java` | §1.3 |
| 52 | `service/message/src/main/java/com/rag/message/grpc/MessageGrpcService.java` | §1.3 |
| 53 | `service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java` | §1.5 / §4（**2026-11-02 新增**：B 侧 `message-rpc/.../mapper/JsonbMapTypeHandler.java` 的**域内照抄副本**；共享层 `common/.../JsonbTypeHandler.java` 是 jsonb ↔ `String`，**类型不兼容** ⇒ 不可顶替） |

> **不计入分母**：proto 生成物（根级 `proto/` 模块的 `target/generated-sources/**`）与 `target/classes/**` 下的 `.class` —— 它们不是手写源码。

> 📌 **原 §7.2「第三列不遗漏职责（57 个源文件）」整段已随第三列移除**（D16 · 判定基准改为 B 侧）——
> 该清单列举的全是第三列的文件，B 侧无对应物 ⇒ **无独立价值，整段删除**（不留悬空引用）。
> ⇒ **B 列的逐类落位**改由 `tools/check-mapping-coverage.py` 机器核对
> （`python3 tools/check-mapping-coverage.py message`，从 RAG 磁盘反查，不采信文档自报数）。

### 7.2 映射类型分布

> 📌 **统计口径**：只统计**带映射类型列的表格行**（§0 / §1.1 / §1.2.1 / §1.2.2 / §1.3 / §1.4 / §1.5 / §1.6）；
> §2 / §3 / §4 / §6 / §7.1 是**维度对照表与约定表**，按契约不设映射类型列。
> 📌 **重算依据**：D16 后表仍为 4 列（B / C / 映射类型 / 备注），**行数不变**；
> 本次只改「来源列」的指针（第三列 ⇒ B 侧），**逐行复核映射类型判定依据未变**。
> ⚠️ **旧表把 `MessageExceptions.java` 记成 `C 新增`、把 `rpc/messageservice` stub 记成 `N:1` —— 与 §1.3 的实际行标签不符**；
> 本次**以表内实际行标签为准**重算（§1.3 的 `MessageExceptions.java` 行是 `1:1`；stub 行是 `N:1`）⇒ `N:1` 6 处、`C 新增` 1 处。

| 映射类型 | 行数 | 占比 | 分布（§0 / §1.1 / §1.2.1 / §1.2.2 / §1.3 / §1.4 / §1.5 / §1.6） |
|---|---|---|---|
| `1:1` | **46** | 66.7% | 3 / 5 / 2 / 18 / 8 / 5 / 5 / 0 |
| `1:N` | **16** | 23.2% | 3 / 1 / 2 / 1 / 4 / 1 / 3 / 1 |
| `N:1` | **6** | 8.7% | 1 / 0 / 2 / 0 / 2 / 0 / 1 / 0 |
| `C 新增` | **1** | 1.4% | 0 / 0 / 0 / 0 / 1 / 0 / 0 / 0 |
| **合计** | **69** | **100%** | 7 / 6 / 6 / 19 / 15 / 6 / 9 / 1 |

> 📌 **主要落点**：`1:1` = 入口 / 配置 / Metrics / Entity / Mapper（4 个）/ DTO（17 个逐条）/ proto / 错误契约 / `MessageApiSupport` / `MessageRpcSupport` / `MessageAtomicService`；
> `1:N` = ① **一组路由 → 两组 JWT**（§1.1）② 13 个 handler → 2 个 Controller（2 行）③ DTO 17 个类 → 17 个 record（1 行文件级）④ `errmsg/` 3 个类 → 4 个契约类 + `CodeError` + `GrpcErrorMapper`（4 行）⑤ `MessageDomainService` + 3 个 Mapper → 4 Mapper + `MessageAtomicService` ⑥ 3 个 Mapper + Flyway 脚本 → 3 Mapper + DDL ⑦ `MessageApiProperties`（api 侧）→ Properties + yaml ⑧ `MessageProperties`（rpc 侧）→ Properties ⑨ rpc 侧 `ServiceContext` 的 2 个跨域 client ⑩ `MessageRpcApplication` → 入口 + Nacos ⑪ `message-api` 的 2 个 advice ⑫ B 侧 `MessageGoStyleCoercion` → `common/src/main/java/com/rag/common/web/Coercion` + `JsonBodyConverterAutoConfiguration`；
> `N:1` = ① 8 个 user `*Service` → `MessageApiService` ② 5 个 admin `*Service` → `MessageApiService` ③ 9 个 rpc 用例 → `MessageDomainService` ④ `message-api/.../client/MessageRpcClient.java`（域内 stub 随 D2 消失）→ C 侧无手写文件 ⑤ `AbstractMessageApiService` → `MessageApiService` ⑥ `service/common`（跨域共享信封 + 异常基类）→ C 根级 `common/` 4 个类；
> `C 新增` = ① `rpc/metrics/MessageMetrics.java`（**2026-11-02 定为两个指标类的共享基座**，消除与 `MessageRpcMetrics` 的职责重叠）。
>
> ✅ §1 全部映射行均填了映射类型，且只用 4 种合法词（机器核对：`illegal types = ∅`；**核对日期 2026-11-02 · D16 重算**）。

### 7.3 两侧之间「职责对不上」的清单（供实现前处理）

> 本表**不下「已对齐 / 未对齐」结论**，只标出**文件名 / 职责落位对不上**的地方。

| # | 位置 | 现象 | 影响面 |
|---|---|---|---|
| 1 | C 侧 `api/controller/MessageController.java` + `MessageAdminController.java` 的 javadoc | ✅ **已关闭（2026-11-02）**：路由清单已按 **B 侧源码**重写 —— 复数 `/conversations/*`、`/notifications/*`，`/unread` 是 **GET**，admin 组 5 个端点**全是 POST**（方法分布 **GET 1 + POST 12**） | **L1 路由契约**已对齐 B 侧（由真 Tomcat 契约测试逐条钉住） |
| 2 | C 侧 `api/interceptor/` | ✅ **已关闭（2026-11-01）**：B 侧的 `AbstractMessageJwtAuthInterceptor`（两份 JWT 逻辑的共享基类）在 C 侧**已有落点** `service/message/src/main/java/com/rag/message/interceptor/AbstractMessageJwtAuthInterceptor.java` | 安全边界各自演化的风险已消除（⚠️ 实现时两个子类**必须继承**它，否则基类空转） |
| 3 | C 侧 `rpc/metrics/` | ✅ **已关闭（2026-11-02）**：`MessageMetrics.java` 定为**共享基座**（namespace 前缀常量 + `MeterRegistry` 注册公共方法 + 标签键常量），`MessageApiMetrics`（3 个 `message_api_*`）与 `MessageRpcMetrics`（5 个 `message_rpc_*`）各自承载自己的指标 ⇒ **职责重叠消除**，且**不留空壳**（空壳在 A1–A13 断言里算 PENDING） | 指标名不再双份定义 |
| 4 | C 侧 `wiring/` | ✅ **已关闭（2026-11-02 · 口径改判）**：**不新增** `MessageSchemaInitializer.java`；建表来源 = **B 侧 V1~V5 Flyway 脚本逐字节照抄**（已 `cmp` 校验 IDENTICAL）+ `application.yml` 补 `spring.flyway` 段（**独立账本表名 `flyway_schema_history_message`** + `baseline-on-migrate: true` + **`baseline-version: 0`**）⇒ 见 §2 决策锁「为什么这么定」 | 3 张表有建表来源；**独立账本**消除与 like / article 的账本撞号 |
| 5 | C 侧 `rpc/service/` | ✅ **已关闭（2026-11-02）**：边界划定 = **`MessageDomainService`（9 用例 · 业务规则 · HTTP 与 gRPC 共享）** ↔ **`MessageApiService`（13 用例 · 编排：身份解析 → 硬编码 role → 调领域层 → 投影 DTO → 埋点）** ↔ **`MessageRpcSupport` / `MessageAtomicService` / `GoStylePagination`（协作者与事务边界，保持独立）** | 合并后职责边界已定 |
| 6 | C 侧 `service/message/src/main/java/com/rag/message/contract/` | ✅ **已关闭（2026-11-02）**：`contract/` = **域内错误契约**（`ErrCodes` 20 码 / `ErrMsg` 20 条英文文案 / `CodeError` / `GrpcErrorMapper`，**逐域隔离不共享**）；`common/contract/ApiResponse` = **HTTP 信封**（`{code,msg,data}`）；`common/exception/BusinessException` = **业务异常基类**（域内**不重复定义**）⇒ **三层不重叠** | 统一错误模型（D2 附带）无双轨 |
| 7 | 任务简报中的前提 | B 侧 comment 域的 MQ 载荷契约是 **`service/comment/comment-rpc/.../service/CommentAuditMessage.java`**（comment 域），topic 定义在 `comment-rpc/.../config/CommentKafkaTopics.java`；**B 侧 `service/message/**` 对 MQ 的引用数为 0**（无 `org.apache.kafka` / `springframework.kafka` 依赖） | 本表据此把 Kafka topic 记为「无」；**message 域无 MQ 契约** |
| 8 | C 侧 `api/dto/AdminSendNotificationResp.java` | `affected` 的 JSON 形态：**B 侧 = 字符串**（`@JsonFormat(Shape.STRING)`，**已签字偏离 `SD-2026-10-23-03`**）⇒ C 侧照抄 B 侧形态 | 本表只点明差异，**不表结论**；proto 侧两侧均为 `int64 affected = 3`（无 `,string`） |
