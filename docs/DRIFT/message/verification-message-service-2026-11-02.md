# `message-service` 迁移审核报告 —— RAG（B 侧）→ RAG-V1（C 侧）

> **由 `align/message` 工作树产出** · **基线 commit `ae17325`**（本地 `main`）· 分支 `align/message`
> （`git diff main..HEAD` 为空 ⇒ 本轮改动**全部在工作树内、未提交**）
> **审核协议**：`.agents/verification.md`（八阶段：范围 → 承接清单 → 行为审核 → 框架差异 → 合并拆分 → 对拍 → 状态 → 报告）。
> **审核日期**：2026-11-02 · **审核对象**：`service/message/`（artifactId **`message-service`**，包 `com.rag.message`）
> **行为基线**：**B 侧（RAG 仓）** `/Users/pr/Documents/java&golang/RAG/service/message/` —— **D16 口径：B 侧 = 唯一判定基准**，差异默认判 C 侧错。
> **状态口径**：`PASS` / `FAIL` / `NOT_IMPLEMENTED` / `NOT_VERIFIED` / `FRAMEWORK_DIFFERENCE` / `BLOCKED` / `INTENTIONALLY_CHANGED`。
> ⚠️ **四种非 PASS 状态一律不计入 PASS**。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` 按 `docs/Worktree.md` §三·1 **不碰**（合并后由主会话统一更新）。

---

## 一、审核范围

### 1.1 两侧坐标

| 项 | B（迁移源 / 行为基线） | C（迁移目标 / 被审对象） |
|---|---|---|
| 仓库 | `/Users/pr/Documents/java&golang/RAG` | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/message` |
| 模块路径 | `service/message/`（**api + rpc 两个进程** ＋ `message-common` ＋ `message-proto`） | `service/message/`（**一个进程 · D1**） |
| 包 | `com.rag.message.{message.api,message.rpc,message.common}.**` | `com.rag.message.**`（**单层技术角色包树** · D7 + D11） |
| 源文件数 | **71** 个 `.java`（api **49** · common **3** · rpc **19**） | **53** 个 `.java`（主代码）· **0 空壳** |
| 主代码行数 | **9,381** 行 | **9,909** 行 |
| 测试文件数 | **52** 个 `.java` / 12,945 行（B 侧既有） | **6** 个 `.java` / **1,765** 行 / **60 例** |
| proto | `message-proto/src/main/proto/message.proto` | 根级 `proto/`（pb 包名 `com.rag.proto.message` · **不在本域文件数内**） |
| 错误契约 | `message-common/errmsg/`（`MessageErrorCode` + `MessageErrorMessages` + `MessageGrpcError`） | 同 ⇒ 域内 `contract/`（`ErrCodes` + `ErrMsg` + `CodeError` + `GrpcErrorMapper`） |
| DDL | `message-rpc/src/main/resources/db/migration/` V1~V5 | 同路径同内容（**逐字节复制** · 见 §三·3.3） |
| git commit | 冻结只读（未动） | 基线 `ae17325` · 本轮改动**未提交**（工作树内） |

### 1.2 实际解析版本（**读 classpath 取值，不是 pom 声明值** · 2026-11-02 实测）

| 组件 | B | C | 差异影响 |
|---|---|---|---|
| Spring Boot | **4.0.8** | **3.5.11** | 见 §四 |
| Spring Framework / spring-webmvc | 7.0.x | **6.2.16** | 见 §四 §五-③⑤ |
| Servlet API | 6.1.x | **jakarta.servlet-api 6.0.0** | `DispatcherType.ERROR` 语义一致 |
| Jackson | **3.1.5**（`tools.jackson`） | **2.19.4**（`com.fasterxml`） | D14 口径；本域 **0 处** `tools.jackson` 引用 |
| jjwt | 0.12.6 | **0.12.6** | 无差异 |
| MyBatis-Plus | — | **3.5.17** | 本域 `MybatisSqlSessionFactoryBean` 用法见 §七·7.3 |
| devh grpc | 3.1.0.RELEASE | **3.1.0.RELEASE** | ⚠️ `grpc.client.<X>` 是**通道名 map**（§五-③） |
| grpc core | — | **1.63.0**（`grpc-netty-shaded`） | — |
| go-zero | **v1.10.2** | —（行为基线） | 本轮 B 侧口径按 **v1.10.2** 源码逐行核对 |
| Tomcat | 11.x | **10.1.52**（`tomcat-embed-core`） | D14 回退；本域**不用 MockMvc**（§七） |
| Flyway | 11.x | **11.14.1** | — |
| nacos-client | 3.0.3 | **3.0.3** | — |
| PostgreSQL 驱动 | — | **42.7.10** | — |
| JUnit / Testcontainers / AssertJ | — | 5.12.2 / 1.21.4 · PG `postgres:16-alpine` | 测试基础设施 |

### 1.3 本次使用的资料

| 资料 | 用途 | 核对日期 |
|---|---|---|
| `docs/Mapping_Table/message/message.md` | B→C 落点对照（**抄写路线图** · §1.0c） | 2026-11-02 |
| `docs/决策记录.md`（D1/D2/D5/D7/D11/D14/D16/D18）· `docs/Lombok-使用规范.md` | 已批准决策与 Lombok 展开块规范 | 2026-11-02 |
| `docs/File_Tree/message.md` | C 侧目标结构约束（**由 `gen-file-tree.py` 按实际文件系统生成**） | 2026-11-02 |
| `docs/完全spring化的文件树.md` §0.1 · §5 · §9.1 | L1–L4 定义 · 目标树 · 断言 A4 的出处 | 2026-11-02 |
| `docs/架构符合性断言.md`（A1–A13） | 守门判据 | 2026-11-02 |
| `RAG/docs/DRIFT/message/{api,rpc,common,vs-common}.md`（**只读**） | **B 侧既有问题**（避免把 B 的老问题误报成 C 的迁移问题） | 2026-11-02 |
| `RAG/docs/DRIFT/follow/vs-common.md`（**只读**） | `DRIFT-G1-001`（Nacos group 写死）的**修法出处** | 2026-11-02 |
| `service/users/{user,admin}/`（已落地域） | 本仓 Nacos / Properties / 拦截器 / 测试设施的**既有惯例** | 2026-11-02 |

---

## 二、B → C 责任承接表（组件级 · AGENTS.md §3.5「审查粒度 = 组件级」）

> **覆盖口径**：B 侧 **71** 个 `.java` 的**全部职责**（api 49 · common 3 · rpc 19）+ 5 份 DDL + 1 份 proto。
> **实现状态** = 实现是否存在；**验证状态** = 有无运行证据（§七）。

### 2.1 `message-api`（49 个）→ C 侧

| B 源文件（行数） | B 职责 | C 承接文件 | C 承接方法 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|---|
| `MessageApiApplication.java`（81） | api 进程入口 | `MessageServiceApplication.java` | `main` | **N:1**（与 rpc 合并 · D1） | ✅ | ✅ | §五·5-⑨ |
| `advice/GlobalExceptionHandler.java`（315） | HTTP 异常出口（10 分支） | `advice/MessageApiExceptionHandler.java` | 9 个 `@ExceptionHandler` + 4 工具 | 1:1（改名） | ✅ | ✅ | §七·7.1 |
| `advice/GlobalResponseAdvice.java`（71） | 成功信封包装 | `advice/MessageApiResponseAdvice.java` | `supports` / `beforeBodyWrite` | 1:1（改名） | ✅ | ✅ | §七·7.1 |
| `auth/MessageJwtAuthFilter.java`（187） | Servlet Filter 验签 | `interceptor/AbstractMessageJwtAuthInterceptor.java` | `preHandle` | **1:N**（三合一） | ✅ | 🟡 | §六·6-6（§1.6 缺口未闭环） |
| `auth/MessageJwtSignatureVerifier.java`（163） | 两把 key 验签 | 同上 | `verify` / `VerificationResult` / `RouteGroup` | **1:N** | ✅ | 🟡 | 同上 |
| `client/MessageRpcClient.java`（113） | api → rpc 客户端 | **无**（D2：api 直连领域层） | — | **删除**（D2 语义内联） | ✅ | ✅ | §五·5-⑩ |
| `config/GoStyleMapStringValueDeserializer.java`（99） | `map<string,string>` 反序列化 | `common/web/MapStringValueDeserializer.java` | — | **上收**（D3 共享层） | ✅ | ✅ | `common` 模块 224 例绿 |
| `config/MessageApiProperties.java`（222） | 两组 JWT 配置 + fail-closed 守卫 | `config/MessageApiProperties.java` | `verifyAccessSecrets` / `requireSecret` | 1:1 | ✅ | ✅ | §七·7.1（`MessageServiceApplicationTests`） |
| `config/MessageGoStyleCoercion.java`（120） | go-zero 解析规则 | `config/WebMvcConfig.java` + `common/web/Coercion.java` | `applyMessageDomainCoercions` | **1:N**（共享 2 条 + 本域 4 条） | ✅ | ✅ | `WebMvcConfig` javadoc 载 6 组实测 |
| `config/WebMvcConfig.java`（86） | 拦截器注册 + Jackson 规则 | `config/WebMvcConfig.java` | `addInterceptors` / `goStyleCoercionCustomizer` | 1:1 | ✅ | ✅ | §三·3.5 |
| `controller/MessageController.java`（145） | user 侧 8 端点 | `controller/MessageController.java` | 8 个 `@PostMapping`/`@GetMapping` | 1:1 | ✅ | ✅ | **运行期注册表**（§三·3.1） |
| `controller/MessageAdminController.java`（143） | admin 侧 5 端点 | `controller/MessageAdminController.java` | 5 个 `@PostMapping` | 1:1 | ✅ | ✅ | **运行期注册表**（§三·3.1） |
| `dto/*.java`（**18** 个） | HTTP DTO | `dto/**`（**17** 个 record） | — | **17:17**（`GoStyleStringInt64` → 共享层 `StringInt64`） | ✅ | ✅ | §三·3.2 · `MessageDtoWireShapeTest` 25 例 |
| `interceptor/AbstractMessageJwtAuthInterceptor.java`（149） | 401 构造 + 鉴权 | `interceptor/AbstractMessageJwtAuthInterceptor.java` | `preHandle` / `unauthorized` | 1:1（与 `auth/` 2 类合并） | ✅ | ✅ | §七·7.1 |
| `interceptor/MessageAdminJwtAuthInterceptor.java`（43） | admin key | `interceptor/MessageAdminJwtAuthInterceptor.java` | — | 1:1 | ✅ | ✅ | 同上 |
| `interceptor/MessageJwtAuthInterceptor.java`（48） | user key | `interceptor/MessageJwtAuthInterceptor.java` | — | 1:1 | ✅ | ✅ | 同上 |
| `metrics/MessageApiMetrics.java`（127） | api 侧 3 个指标 | `metrics/MessageApiMetrics.java` | 3 个 | 1:1（承共享基座） | ✅ | ✅ | §三·3.4（8/8 指标名一致） |
| `service/AbstractMessageApiService.java`（203） | api 公共基类 | `service/MessageApiSupport.java` + `service/MessageApiService.java` | `requireIdentity` / `invoke` / `rpcError` | **1:2** | ✅ | ✅ | §五·5-⑪ |
| `service/Message*Service.java`（**13** 个） | 13 个 case service | `service/MessageApiService.java` | 13 个 public 方法 | **13:1** | ✅ | ✅ | §五·5-⑪ |
| `service/MessageApiSupport.java`（337） | api 侧支撑（身份 / 分页 / 投影） | `service/MessageApiSupport.java` | — | 1:1 | ✅ | ✅ | §七·7.1 |

### 2.2 `message-common`（3 个）→ C 侧

| B 源文件（行数） | B 职责 | C 承接文件 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|
| `errmsg/MessageErrorCode.java`（105） | 20 个错误码常量 | `contract/ErrCodes.java` | 1:1（enum 改名） | ✅ | ✅ | **20/20 逐条一致**（§三·3.2） |
| `errmsg/MessageErrorMessages.java`（92） | 20 条文案 + 未知码回落 | `contract/ErrMsg.java` | 1:1（改名） | ✅ | ✅ | **20/20 逐字节一致**（§三·3.2） |
| `errmsg/MessageGrpcError.java`（326） | gRPC 错误映射 + cause 链解包 | `contract/GrpcErrorMapper.java` + `contract/CodeError.java` | **1:2** | ✅ | ✅ | `MessageErrorContractTest` 13 例 |

### 2.3 `message-rpc`（19 个）→ C 侧

| B 源文件（行数） | B 职责 | C 承接文件 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|
| `MessageRpcApplication.java`（140） | rpc 进程入口 | `MessageServiceApplication.java` | **N:1**（与 api 合并 · D1） | ✅ | ✅ | §五·5-⑨ |
| `bootstrap/NacosRegistration.java`（196） | Nacos 手写注册（gRPC 端口） | `wiring/NacosRegistration.java` | 1:1（改包） | ✅ | ✅ | **运行期**（§七·7.1）＋ §五·5-⑤ |
| `client/AdminRpcClient.java`（122） | admin-rpc 客户端 | `client/AdminRpcClient.java` | 1:1 | ✅ | ✅ | §三·3.6 |
| `client/UserRpcClient.java`（61） | user-rpc 客户端 | `client/UserRpcClient.java` | 1:1 | ✅ | ✅ | §三·3.6 |
| `config/MessageProperties.java`（86） | `message.list.*` / `message.broadcast.*` | `config/MessageProperties.java` | 1:1 | ✅ | ✅ | §三·3.7 · 运行期断言 |
| `domain/ConversationEntity.java`（99） | 会话实体 | `domain/entity/ConversationEntity.java` | 1:1 | ✅ | ✅ | `javap` 核过公开方法集合 |
| `domain/ConversationMessageEntity.java`（92） | 消息实体 | `domain/entity/ConversationMessageEntity.java` | 1:1 | ✅ | ✅ | 同上 |
| `domain/NotificationEntity.java`（138） | 通知实体（`jsonb` extra） | `domain/entity/NotificationEntity.java` | 1:1 | ✅ | ✅ | 同上 ＋ 真 PG 往返 |
| `exception/MessageExceptions.java`（53） | 领域异常 | `exception/MessageExceptions.java` | 1:1 | ✅ | ✅ | `MessageErrorContractTest` |
| `mapper/ConversationMapper.java`（157） | 会话 mapper | `mapper/ConversationMapper.java` | 1:1 | ✅ | ✅ | 真 PG 用例 |
| `mapper/ConversationMessageMapper.java`（167） | 消息 mapper | `mapper/ConversationMessageMapper.java` | 1:1 | ✅ | ✅ | 同上 |
| `mapper/GoStylePagination.java`（69） | `limit/offset` 工具 | `mapper/GoStylePagination.java` | 1:1 | ✅ | ✅ | 同上 |
| `mapper/JsonbMapTypeHandler.java`（118） | `jsonb ↔ Map` | `mapper/JsonbMapTypeHandler.java` | 1:1（**域内副本**） | ✅ | ✅ | 真 PG 往返 ＋ §六·6-5 |
| `mapper/NotificationMapper.java`（222） | 通知 mapper | `mapper/NotificationMapper.java` | 1:1 | ✅ | ✅ | 真 PG 用例 |
| `metrics/MessageMetrics.java`（180） | rpc 5 个指标 + `observeRpc` | `metrics/MessageMetrics.java`（**共享基座**）+ `metrics/MessageRpcMetrics.java` | **1:2** | ✅ | ✅ | §三·3.4（8/8 指标名一致） |
| `service/MessageAtomicService.java`（268） | 事务边界 | `domain/service/MessageAtomicService.java` | 1:1 | ✅ | ✅ | **主体逐行零差异**（§三·3.8） |
| `service/MessageDomainService.java`（1028） | 9 用例领域层 | `domain/service/MessageDomainService.java` | 1:1 | ✅ | ✅ | **主体逐行零差异**（§三·3.8） |
| `service/MessageRpcSupport.java`（526） | rpc 侧支撑 | `service/MessageRpcSupport.java` | 1:1 | ✅ | ✅ | **主体逐行零差异**（§三·3.8） |
| `service/impl/MessageRpcServiceImpl.java`（168） | gRPC 服务实现 | `grpc/MessageGrpcService.java` | 1:1（改名 + 改包） | ✅ | ✅ | §五·5-⑫ |

### 2.4 非 `.java` 资源

| B 资源 | C 落点 | 状态 |
|---|---|---|
| `message-proto/src/main/proto/message.proto` | 根级 `proto/`（**D7 集中化** · pb 包名 `com.rag.proto.message`） | ✅ 逐字节一致（`java_package` 属表达层） |
| `message-rpc/.../db/migration/V1~V5`（5 个） | `service/message/src/main/resources/db/migration/V1~V5` | ✅ **逐字节一致**（`cmp` ×5 = IDENTICAL） |
| `message-api/.../application.yml` + `message-rpc/.../application.yml` | `service/message/src/main/resources/application.yml`（**合一** · D1） | ⚠️ 见 §五·5-③④ 与 §六·6-4 |

---

## 三、L1 契约逐条核对

### 3.1 13 个端点（method / path）—— **运行期注册表**取证

> 取 Spring 的 `RequestMappingHandlerMapping#getHandlerMethods()` 的**真实注册表**（不是读源码）
> ⇒ 证据：`MessageServiceApplicationTests#httpEndpointTableMatchesBExactly`。
> ⚠️ **本条抓到过一处真实错误**：C 侧**空壳** javadoc 写的是 `/message/v1/conversation/list`（单数）、
> 且把 admin 的两个端点标成 `GET` ⇒ 那份 javadoc 是**按文件树推导的、不是 B 侧正推的** ⇒ 已更正（§五·5-⑥）。

| # | Method | Path | B 侧出处 | C 侧 |
|---|---|---|---|---|
| 1 | POST | `/message/v1/conversations/list` | `api/controller/MessageController.java:84-88` | ✅ |
| 2 | POST | `/message/v1/conversations/messages` | 同上 `:90-96` | ✅ |
| 3 | POST | `/message/v1/conversations/read` | 同上 `:98-103` | ✅ |
| 4 | POST | `/message/v1/conversations/send` | 同上 `:105-110` | ✅ |
| 5 | POST | `/message/v1/notifications/list` | 同上 `:114-118` | ✅ |
| 6 | POST | `/message/v1/notifications/read` | 同上 `:120-125` | ✅ |
| 7 | POST | `/message/v1/notifications/readall` | 同上 `:127-134` | ✅ |
| 8 | **GET** | `/message/v1/unread` | 同上 `:136-141`（**本域唯一的 GET**） | ✅ |
| 9 | POST | `/message/v1/admin/conversations/list` | `api/controller/MessageAdminController.java:82-83` | ✅ |
| 10 | POST | `/message/v1/admin/conversations/messages` | 同上 `:92-93` | ✅ |
| 11 | POST | `/message/v1/admin/conversations/read` | 同上 `:103-104` | ✅ |
| 12 | POST | `/message/v1/admin/conversations/send` | 同上 `:114-115` | ✅ |
| 13 | POST | `/message/v1/admin/notifications/send` | 同上 `:127-128` | ✅ |

**method 分布 = GET 1 + POST 12**（与 B 侧一致）。
⚠️ **两个路由组用两把 JWT**：`/message/v1/**` 用 UserAuth、`/message/v1/admin/**` 用 **AdminAuth**
⇒ C 侧 `WebMvcConfig` 的 user 拦截器**必须** `excludePathPatterns("/message/v1/admin/**")`
⇒ 否则拿 admin token 打 admin 端点会先被 user key 拒掉（凭空多出 401）。

### 3.2 错误码表（**L1 · wire 可见**）—— 20 个，非映射表原写的 22 个

| 常量 | 值 | 文案 | B 侧出处 | C 侧 |
|---|---|---|---|---|
| `SUCCESS` | 200 | `OK` | `MessageErrorCode.java:28` / `MessageErrorMessages.java:53` | ✅ |
| `ERROR` | 500 | `FAIL` | `:31` / `:54` | ✅ |
| `CODE_SERVER_BUSY` | 1015 | `service busy` | `:35` / `:55` | ✅ |
| `ERROR_SERVER_COMMON` | 5001 | `internal server error` | `:38` / `:56` | ✅ |
| `ERROR_NOT_FOUND` | 5002 | `record not found` | `:41` / `:57` | ✅ |
| `ERROR_INVALID_PARAM` | 5003 | `invalid parameter` | `:45` / `:58` | ✅ |
| `ERROR_UNAUTHORIZED` | 5004 | `unauthorized` | `:49` / `:59` | ✅ |
| `ERROR_FORBIDDEN` | 5005 | `forbidden` | `:53` / `:60` | ✅ |
| `ERROR_ALREADY_EXIST` | 5006 | `already exists` | `:57` / `:61` | ✅ |
| `ERROR_DB_SELECT` | 5007 | `database query failed` | `:60` / `:62` | ✅ |
| `ERROR_DB_UPDATE` | 5008 | `database update failed` | `:63` / `:63` | ✅ |
| `ERROR_USER_NOT_FOUND` | 5101 | `user not found` | `:66` / `:65` | ✅ |
| `ERROR_ADMIN_NOT_FOUND` | 5102 | `admin not found` | `:69` / `:66` | ✅ |
| `ERROR_SELF_CHAT` | 5201 | `self chat is not allowed` | `:72` / `:68` | ✅ |
| `ERROR_CONVERSATION_MISS` | 5202 | `conversation not found` | `:75` / `:69` | ✅ |
| `ERROR_NOTIFICATION_MISS` | 5203 | `notification not found` | `:78` / `:70` | ✅ |
| `ERROR_CHAT_PENDING` | 5204 | `waiting for the other side to reply` | `:81` / `:71` | ✅ |
| `ERROR_ADMIN_ONLY_INIT` | 5205 | `only admin can initiate this conversation` | `:84` / `:72` | ✅ |
| `ERROR_FIRST_MSG_TOO_LONG` | 5206 | `first private message cannot exceed 50 characters` | `:87` / `:73` | ✅ |
| `ERROR_UNSUPPORTED_ROLE` | 5207 | `unsupported sender role` | `:90` / `:74` | ✅ |

> **取证方式**：机器 diff（抽取两侧 `NAME → code` 与 `NAME → msg` 配对后 `diff`）
> ⇒ **20/20 逐字节一致**（`diff` 输出为空）。
> ⚠️ **映射表原写「22 个」是错的** ⇒ 已订正（§五·5-⑦）。
> ⚠️ **文案全英文**，**不与其它域的中文文案统一**（AGENTS.md §八「未统一化」· 逐域隔离）。

**gRPC status → 业务码 fallback**（B 侧 `MessageGrpcError.java:255-272`）：

| gRPC status | 业务码 |
|---|---|
| `InvalidArgument` | 5003 |
| `Unauthenticated` | 5004 |
| `PermissionDenied` | 5005 |
| `AlreadyExists` | 5006 |
| `NotFound` | 5002 |
| `Internal` | 5001 |
| 其它 / 不可识别 | **1015** |

**判定顺序**：**先 `CodeError` 再 status**（B 侧 `:217-248`）—— ⚠️ 顺序反了会把业务码降级成 gRPC 码。

### 3.3 表结构 / DDL（**L1**）

| 项 | B | C | 证据 |
|---|---|---|---|
| 迁移脚本 | `message-rpc/.../db/migration/V1~V5` | 同路径同内容 | **`cmp` ×5 = IDENTICAL**（逐字节） |
| 3 张表 | `message_notification` / `message_conversation` / `message_conversation_message` | 同名 | **运行期**：`information_schema.tables` 实查 3 张都在 |
| 唯一索引 | `uk_conversation_pair`（V4） | 同 | 真 PG 用例靠它**真冲突**（非 Mockito 桩） |
| 迁移账本 | `flyway_schema_history_message`（B 侧 `application.yml:124`） | 同 | **运行期**：独立账本存在 + **5 个版本全 success**；**默认账本 `flyway_schema_history` 不存在** |
| `baseline-version` | **0**（B 侧 `:121`） | 0 | 非空 schema + 默认 `baseline-version=1` ⇒ baseline 打在 V1 ⇒ **V1 的 SQL 从未执行** ⇒ 表建不出来 |

> ⚠️ **账本撞号是本仓多域共 schema 的病根**：`flyway_schema_history` 是 **schema 级**账本
> ⇒ message 与 like / article 的 V1~V3 **版本号撞号且内容不同** ⇒ 独立账本消除病根，**迁移文件名一字未动**。

### 3.4 指标名（**L1 · 可观测面**）—— **8/8 逐条一致**

| 指标名 | 归属 | B 侧出处 | C 侧 |
|---|---|---|---|
| `message_api_request_total` | api | `api/metrics/MessageApiMetrics.java:53` | ✅ |
| `message_api_reject_total` | api | 同上 `:55` | ✅ |
| `message_api_request_duration_seconds` | api | 同上 `:57` | ✅ |
| `message_rpc_request_total` | rpc | `rpc/metrics/MessageMetrics.java:45` | ✅ |
| `message_rpc_request_duration_seconds` | rpc | 同上 `:48` | ✅ |
| `message_rpc_notification_action_total` | rpc | 同上 `:51` | ✅ |
| `message_rpc_chat_action_total` | rpc | 同上 `:54` | ✅ |
| `message_rpc_db_error_total` | rpc | 同上 `:57` | ✅ |

> ⚠️ **埋点落点必须与 B 侧一致**：B 侧 `MessageRpcServiceImpl`（gRPC 服务层）**零指标调用**
> ⇒ 9 个 `observeRpc` 全在 `MessageDomainService` 的 9 个 `finally` 里
> ⇒ ⇒ **C 侧 gRPC 层若也记一次 ⇒ `message_rpc_request_total` 与 duration 直方图翻倍**
> ⇒ ⇒ **逐字继承 B 侧**（两侧各 **9** 处，gRPC 层各 **0** 处）—— 已逐处核对。

### 3.5 JWT claims 与鉴权边界（**L1 · 安全面**）

| 项 | B 侧 | C 侧 | 状态 |
|---|---|---|---|
| claim 名 | **`userId`** | **`userId`**（`AbstractMessageJwtAuthInterceptor.CLAIM_ATTRIBUTE`） | ✅ 逐字 |
| claim 取值判定归属 | **业务层**（`UserIDFromContext` ⇒ 5004） | 业务层（`MessageApiSupport#userId`） | ✅ |
| claim 缺失 / 非法的结果 | **HTTP 200 + `{code:5004}`** | 同 | ✅ |
| 头缺失 / Bearer 剥不出 / 签名错 / 过期 | **HTTP 401 + 空 body** | 同 | ✅ |
| user 拦截器路径 | `/message/v1/**`（不含 admin） | 同 + **`excludePathPatterns("/message/v1/admin/**")`** | ✅ |
| admin 拦截器路径 | `/message/v1/admin/**` | 同 | ✅ |
| 未注册路径 | **404**（不经 auth） | 同（`DispatcherType.ERROR` + `handler instanceof HandlerMethod` 两条判据） | ✅ |
| 黑名单 | **无**（B 侧 message 域没有；那是 user / admin 域的） | **无**（未凭空引入） | ✅ |
| admin secret 长度 | 15 字节（golang-jwt 不校验长度） | **zero-pad 到 32 字节**（jjwt 0.12 强制 ≥256 bit） | ⚠️ **已签字偏离**（§六·6-3） |

### 3.6 gRPC 客户端与服务注册名（**L1 · 跨域寻址**）

| 项 | 值 | B 侧出处 | C 侧 |
|---|---|---|---|
| user 通道名 | `user-rpc` | `message-rpc/.../client/UserRpcClient.java` + yml `:265` | ✅ |
| admin 通道名 | `admin-rpc` | `.../client/AdminRpcClient.java` + yml `:270` | ✅ |
| 地址写法 | `discovery:///user-rpc`（**三斜杠**） | yml `:266` / `:271` | ✅ |
| `negotiation-type` | `plaintext` | yml `:268` / `:273` | ✅ |
| 本域对外注册名 | **`message-rpc`**（like 域 `discovery:///message-rpc` 依赖它） | `rpc/bootstrap/NacosRegistration.java` | ✅ 逐字保留 |
| 注册端口 | **gRPC 端口**（`GrpcServerProperties.getPort()`），**不是** web 端口 | 同 | ✅ 运行期断言 |

> ⚠️ **三斜杠是硬要求**：devh 的 `DiscoveryClientResolverFactory#newNameResolver` 取
> `targetUri.getPath()`，要求**非 null、长度 > 1、以 `/` 开头** ⇒ `discovery://user-rpc` 解析出 `path=""`
> ⇒ 抛 `IllegalArgumentException` ⇒ **该检查在任何网络调用之前** ⇒ 与 Nacos 在不在线无关
> ⇒ **`grpcClientBeanPostProcessor` 先炸 ⇒ 连 Flyway 都跑不到** ⇒ 真 fat jar 起不来。

### 3.7 配置键名（**L1**）

| 键 | B 侧 | C 侧 | 状态 |
|---|---|---|---|
| `message-api.user-auth.access-secret` / `.access-expire` | `message-api/.../application.yml:98-104` | 同 | ✅ 逐字 |
| `message-api.admin-auth.access-secret` / `.access-expire` | 同上 | 同 | ✅ |
| `message.list.default-limit` / `.max-limit` | `message-rpc/.../application.yml:282-283` | 同 | ✅ 逐字 |
| `message.broadcast.page-size` | 同上 `:285` | 同 | ✅ |
| `spring.flyway.*`（5 键） | `message-rpc/.../application.yml:83-124` | 同 | ✅ |
| `grpc.server.port` | `${MESSAGE_RPC_PORT:8082}` | `${MESSAGE_GRPC_PORT:8084}` | ⚠️ **D1 合并带来的 L1 差异**（§六·6-1） |
| `server.port` | `message-api` = 8892 | `${MESSAGE_HTTP_PORT:8883}` | ⚠️ 同上 |
| `spring.cloud.nacos.discovery.namespace` / `.group` | `public` / `DEFAULT_GROUP` | `sea-rtw` / `USER_GROUP` | ⚠️ **部署面偏离**（§六·6-4） |
| `spring.cloud.nacos.discovery.register-enabled` | `false` | `false` | ✅ |
| `observability.http-timeout` | `message-api/.../application.yml:254` = `3s` | 同 = `3s` | 🔴 **曾缺失 ⇒ 3s 预算不存在**（已修 · §五·5-⑩） |
| `observability.rpc-timeout` | `message-rpc/.../application.yml:355` = `2s` | 同 = `2s` | 🔴 **曾缺失 ⇒ 2s 超时不存在**（已修 · §五·5-⑩） |
| `grpc.client.user-rpc.*` / `grpc.client.admin-rpc.*` | `message-rpc/.../application.yml:248-273` | 同（地址改 `discovery:///`，属 §六·6-4 部署面偏离） | ✅ 逐键 |

### 3.8 核心层「主体逐行零差异」实测（**本轮最硬的证据**）

> **口径**：把两侧文件去掉 `package` / `import` / 注释 / 空行，再把连续空白压成单空格后 `diff`。
> ⇒ 剩下的每一行差异都必须是**可解释的表达层改写**，**不允许**有未解释的业务差异。

| 文件 | B 主体行 | C 主体行 | 差异行 | 差异的**全部**内容 |
|---|---|---|---|---|
| `MessageDomainService` | 653 | 647 | **102** | ① `MessageErrorCode` → `ErrCodes`（改名 28 处）② `MessageErrorMessages.getMsg` → `ErrMsg.getMsg`（改名 10 处）③ `MessageMetrics` → `MessageRpcMetrics`（指标基座拆分）④ **删掉 2 个「只赋值、从不被读」的死字段**（`logger` / `tracer`，见下）⑤ 3 处换行排版 |
| `MessageRpcSupport` | 257 | 259 | **16** | ① 错误契约改名（5 处）② `java.time.OffsetDateTime` → `OffsetDateTime`（import vs 全限定名，1 处）③ 2 个 record 的排版（3 处） |
| `MessageAtomicService` | 54 | 54 | **12** | **全部**是构造器参数 / 字段的**声明顺序**（B：notification, conversation, message ⇒ C：conversation, message, notification）⇒ 构造器注入顺序**不影响任何行为** |

> **关于 ④ 的核实**：B 侧 `MessageDomainService` 的 `logger` 只出现在
> **字段声明（:97）/ 构造器参数（:108）/ 赋值（:117）** 三处，**从不被读**；
> `tracer` 只出现在 **字段声明（:99）/ 赋值（:122）** 两处，**从不被读**
> ⇒ ⇒ **零可观察行为** ⇒ C 侧不保留这两个死字段，并在类 javadoc 里写明这件事。

---

## 四、框架差异（Boot 4 / go-zero → Boot 3.5 / Spring）

| # | B 侧机制（版本） | C 侧机制（版本） | 具体变化 | 影响的执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 结论 |
|---|---|---|---|---|---|---|---|---|
| F-1 | **Boot 4.0.8** + Jackson **3.1.5**（`tools.jackson`） | **Boot 3.5.11** + Jackson **2.19.4**（`com.fasterxml`） | `JsonMapperBuilderCustomizer` 不存在 ⇒ 改 `Jackson2ObjectMapperBuilderCustomizer`；Jackson 3 API 全部回改 | 请求体解析 / 响应体序列化 | **否**（本域 **0 处** `tools.jackson` 引用；解析规则逐条等价） | 共享层 `Coercion` 2 条 ＋ 本域 `applyMessageDomainCoercions` 4 条 | `WebMvcConfig` javadoc 载 **6 组真 ObjectMapper 探针**（`{"offset":"10"}` / `{"unreadOnly":"true"}` / `{"broadcast":1}` / `{"title":123}` / `{"offset":null}` / 正常组合）逐条与 Go 侧一致 | **一致** |
| F-2 | **go-zero `httpx.Parse`** → 解析失败走 `SetErrorHandler` ⇒ **HTTP 200 + `{code:5001}`** | Spring `@RequestBody` + `HttpMessageNotReadableException` ⇒ 显式 handler ⇒ **HTTP 200 + `{code:5001}`** | 异常类型不同；**响应形状逐字节相同** | 畸形 JSON / 类型不匹配 / 空 body | **否** | `MessageApiExceptionHandler#handleUnreadableBody` + `#handleBeanValidation` | 真启动测试 + handler 逐分支 | **一致** |
| F-3 | go-zero `handler.Authorize`（**在 3s 预算之外**） | `HandlerInterceptor`（**在 3s 预算之内**） | 验签耗时是否计入 3s 预算 | 慢请求的 503 边界 | **可观测**（B 侧 `DRIFT-MESSAGEAPI-009②`） | 映射表 §1.6 记录的 C 侧缺口 | — | ⚠️ **未闭环**（§六·6-6 · 继承 B 侧漂移） |
| F-4 | go-zero `http.NotFound` / `http.Error`（裸 404/405） | Spring `NoResourceFoundException` / `HttpRequestMethodNotSupportedException` | 默认会落进兜底 advice ⇒ **404 被改写成 200+5001** | 未知路径 / 方法不支持 | **是**（若不改） | 共享层 `common/web/RouterErrorResponses`（**逐字节复刻** B 侧 404 body `404 page not found\n` + `nosniff` + `Content-Type`；405 裸状态码 + `Allow`） | 共享层 `common` 224 例绿 | **一致** |
| F-5 | devh `grpc.client.<X>` **是通道名 map**（不是属性名） | 同 | 在 `grpc.client:` 下写 `negotiation-type: plaintext` ⇒ **该配置进不了任何通道** ⇒ 静默 TLS | gRPC 出向调用 | **是**（链路不可用） | 每条通道各写一段 `negotiation-type: plaintext` | A4 守门 + 真启动测试 | **一致** |
| F-6 | **Tomcat 11.x** | **Tomcat 10.1.52** | `Content-Type` 归一化（分号后无空格） | 裸响应头的字节形状 | **否**（RFC 9110 OWS 不显著） | 共享层按「解析后比对」而非「比原始字符串」断言 | 共享层既有前科 | **一致** |
| F-7 | go-zero **etcd** 寻址（`user.rpc` / `admin.rpc`） | **Nacos discovery**（`discovery:///user-rpc`） | 注册中心换了 ⇒ namespace / group 变得**真的参与寻址** | 跨域 gRPC 寻址 | **是**（找错 namespace ⇒ 恒「No servers found」） | 对齐本仓已落地域（`sea-rtw` / `USER_GROUP`） | §六·6-4 | ⚠️ **部署面偏离**（已登记） |

---

## 五、本轮发现并修掉的漂移（**10 条 · 全部已修 + 已补真触发测试**）

### 5-① 🔴 P0 · `MessageDomainService` 的 3 个 private `doXxx` 参数名写成 `req` ⇒ **编译不过**

- **位置**：`service/message/src/main/java/com/rag/message/domain/service/MessageDomainService.java:897,959,1004`
- **B 出处**：`message-rpc/.../service/MessageDomainService.java:887,947,989` —— B 侧 9 个 `doXxx` **全部**用 `in`，
  而 9 个 public 方法用 `req`（B 侧 `:166,408,485,618,703,797,870,930,972`）。
- **现象**：C 侧 3 个 private 方法把参数写成 `req`，但方法体里仍有 `in.getUserId()` / `in.getNotificationId()`
  ⇒ **整模块编译失败**（`cannot find symbol: in`）。
- **修法**：把 3 个 private 参数**改回 `in`**（逐字继承 B 侧）⇒ 连带 5 处 `req.` → `in.`。
- **验证**：① 编译通过（`mvn -o -pl service/message -am compile` ⇒ BUILD SUCCESS）②
  **`in.` 出现次数 = 70 = B 侧同文件的 70**（逐字可数）③ 9 个 private 全 `in`、9 个 public 全 `req`（与 B 侧同构）。
- **教训**：**改名时只改签名不改方法体** ⇒ 编译期才发现 ⇒ ⇒ **参数名也属于「逐字照抄」的一部分**。

### 5-② 🔴 P0 · `MessageApiService` 的 **ambiguous import** ⇒ **整模块编译不过**

- **位置**：`service/message/src/main/java/com/rag/message/service/MessageApiService.java:17,19,25,26`
- **现象**：dto 层与 proto 层**同名** —— `MarkConversationReadReq` / `MarkNotificationReq` 各有两个
  ⇒ Java 不允许 import 两个同名类型 ⇒ `ambiguous import` ⇒ **整模块编译失败**。
- **修法**：**只 import proto 版**（保持 builder 写法与其它 10 个 proto 入参一致），
  **dto 版在方法签名里写全限定名**（3 处）⇒ 并在 import 块上方写明判据
  （「看到 `xxx.newBuilder()` ⇒ proto；看到方法签名里的参数类型 ⇒ dto」）。
- **验证**：编译通过 ＋ 真启动测试（controller → apiService → domainService 全链路跑通）。
- **教训**：**同名类型跨层碰撞**是 D7 包压平 + D5 双 DTO 契约叠加下的必然 ⇒ 必须在 import 块留判据注释。

### 5-③ 🔴 P0 · `application.yml` 的 `grpc.client` 段**只有注释、没有条目** ⇒ A4 红 + 上下文起不来

- **位置**：`service/message/src/main/resources/application.yml`
- **B 出处**：`message-rpc/src/main/resources/application.yml:248-273`
- **现象**：域内 2 个 `@GrpcClient`（`user-rpc` / `admin-rpc`）需要
  `grpc.client.<name>.negotiation-type: plaintext` ⇒ yml 段是空的 ⇒
  ① **A4 守门红**（「@GrpcClient 2 处 · yml grpc.client 0 处」）② **上下文启动失败**（devh 取不到通道配置）。
- **修法**：补两条通道（`address: discovery:///user-rpc` / `discovery:///admin-rpc` ＋
  `enable-keep-alive: true` ＋ `keep-alive-time: 30s` ＋ `negotiation-type: plaintext`）。
- **验证**：A4 **PASS**（「@GrpcClient 2 处 · yml grpc.client 2 处 · 全部 plaintext」）＋ 真启动测试绿。

### 5-④ 🔴 P0 · `application.yml` 的 Nacos 段缺 `register-enabled: false` ⇒ **gRPC 链路断**

- **位置**：`service/message/src/main/resources/application.yml`
- **B 出处**：`message-rpc/.../application.yml:156`（B 侧注释原文：「【本模块的注册是手写的】…
  ⇒ **自动注册必须关掉**，否则**同一服务名下会有两个端口的实例**」）
- **现象**：C 侧 **D1 合并后确实带 web 容器**（HTTP 8883）⇒
  `NacosAutoServiceRegistration`（监听 `WebServerInitializedEvent`）会注册一个 **web 端口**的实例
  ⇒ 而域内 `wiring/NacosRegistration` 注册的是 **gRPC 端口**（8084）
  ⇒ ⇒ **同一个服务名下两个端口的实例** ⇒ 调用方连到只提供 HTTP 路由的端口 ⇒ **gRPC 链路断**（业务码塌成 1015）。
- **修法**：补 `register-enabled: false` ＋ `namespace` ＋ `group` ＋ `server-addr`（**host/port 拆两个变量** · R1-ENV-001）。
- **验证**：**运行期**（`MessageServiceApplicationTests`）：① `isRegisterEnabled()` 为 false（值来自真实 yml）
  ② 桩 `NamingService` 上**只被注册 1 次** ③ 那一次的端口 **= gRPC 端口** 且 **≠ web 端口**
  ④ 注册名 = `message-rpc`。

### 5-⑤ 🔴 P1 · `NacosRegistration` 的 group **写死** ⇒ `NACOS_GROUP` 环境变量完全无效

- **位置**：`service/message/src/main/java/com/rag/message/wiring/NacosRegistration.java`
- **B 出处**：B 侧同形状的缺陷被 **B 侧自己**立为 🔴 条目 —— **`DRIFT-G1-001`**（「follow-rpc 的 group 写死」，
  记录在 `RAG/docs/DRIFT/follow/vs-common.md:40`）⇒ 修法 = **group 从配置读、默认值零变化**。
- **现象**：B 侧 message-rpc 把 group 写死成 `DEFAULT_GROUP` ⇒ `NACOS_GROUP` 环境变量对本域**完全无效**
  ⇒ 而本仓 yml 写的是 `group: ${NACOS_GROUP:USER_GROUP}`（读配置）⇒
  ⇒ 部署一旦设 `NACOS_GROUP=别的` ⇒ **注册到 A 组、调用方去 B 组找** ⇒ 恒「No servers found」。
- **修法**：`@Value("${spring.cloud.nacos.discovery.group:DEFAULT_GROUP}")` ⇒
  **默认值零变化**（不设环境变量时解析结果仍 = B 侧写死的 `DEFAULT_GROUP`）。
- **一致性**：与本仓已落地域 `service/users/{user,admin}` 的 `NacosRegistration` 同款写法。
- **⚠️ D16 口径说明**：本条**不是**「回溯第三方」—— B 侧台账的 `DRIFT-G1-001` 是 **B 侧自己的当前态**
  （AGENTS.md §3.1：「不要把 B 侧台账里的旧判定当当前结论 ⇒ 判定前先看它的『当前态指针』」）⇒
  ⇒ **继承 B 侧已修形态**才是 D16。

### 5-⑥ 🟡 P2 · C 侧**空壳 javadoc** 的路由清单与 B 侧逐条不符

- **位置**：`controller/MessageController.java` + `controller/MessageAdminController.java`（实现前的空壳 javadoc）
- **现象**：空壳 javadoc 写的是 `/message/v1/conversation/list`（**单数**）、
  `/notification/read/all`、`/unread/summary`、`/chat/send`，且把 admin 的 `conversation/list` 与
  `conversation/messages` 标成 **`GET`** ⇒ ⇒ **与 B 侧源码逐条不符**。
  **根因**：那份 javadoc 是**按《完全spring化的文件树》推导的、不是 B 侧正推的** ⇒ 属**错误信息**。
- **修法**：按 **B 侧源码**重写（复数 `/conversations/*`、`/notifications/*`，`/unread` 是 GET，admin 组 5 个全 POST）。
- **验证**：**运行期**取 `RequestMappingHandlerMapping` 的真实注册表 ⇒ **13/13 逐条相等**。

### 5-⑦ 🟡 P2 · 映射表的**事实错误**（3 处）

- **位置**：`docs/Mapping_Table/message/message.md`
- **改判**：
  ① 错误码 **22 → 20**（B 侧 `MessageErrorCode` 枚举实际只有 **20** 个常量，逐条数过）
  ② `ConversationItem` **12 → 11** 字段
  ③ **api 侧指标桶**：原写「钉 Go 的 `DefBuckets`」⇒ 按 D16 改为**逐字继承 B 侧 Java**（`registry().timer(...)` · **不钉桶**）
  ④ C 列路径错误（误写 `service/users/common/...`）
  ⑤ Flyway 决策锁改判（见 §六·6-2）
  ⑥ §7.3 缺口 #1/#3/#4/#5/#6 关闭
- **验证**：`check-mapping-coverage.py` ⇒ **C 53/53 · B 71/71 · EXIT=0**；
  `_INDEX.md` 用 `sync-mapping-index.py --apply` **重算**（机器生成物按纪律不手改）⇒ diff 精确到 **3 行**。

### 5-⑧ ✅ 已收口（2026-11-03） · `JsonbMapTypeHandler` 在共享层**无对应物** ⇒ 域内副本

- **原位置（该文件已被删除 · 2026-11-03 · 由 `global` 收口域执行 **D-11** 收口）**：`service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java`
- **现象（当时）**：共享层 `common/persistence/JsonbTypeHandler` 是 `BaseTypeHandler<String>`（**jsonb ↔ String**），
  `JsonTextTypeHandler` 是 **text ↔ Map** ⇒ ⇒ **两者都无法顶替 jsonb ↔ Map**。
- **修法（已实施 · 2026-11-03 · `global` 收口域 · `DRIFT-COMMON-V19`）**：共享层**新增**
  `common/src/main/java/com/rag/common/persistence/JsonbMapTypeHandler.java`
  （**照抄 B 侧 `message-rpc/…/mapper/JsonbMapTypeHandler.java` 118 行原类** ⇒ 代码体 **34 行逐行完全相同**；
  **类名保持不变** ⇒ 本域只需改 import）＋ **域内副本已删除** ⇒ **副本数 1 → 0** ⇒ 本域主代码 **53 → 52**。
- ⚠️ **不与共享层 `JsonbValueTypeHandler` 的 Map 版合并** —— 写 null 路径（`setNull(Types.OTHER)` vs MyBatis 默认）/
  非 null 绑定（`Types.OTHER` vs `PGobject(jsonb)`）/ 读空白串（抛 `SQLException` vs 返回 `null`）三处**行为不同**
  ⇒ ⇒ **合并 = 凭空制造数据层漂移**。
- **验证**：真 PG 的 jsonb 往返 + **PG 键序规范化**用例（`MessageDomainServiceRealPgTest`）
  ＋ **归一化 diff（代码体逐行完全相同）** ＋ 编译 EXIT=0。

### 5-⑨ 🟡 P2 · 入口类的 `@ConfigurationPropertiesScan` 缺失 ⇒ **两把密钥的守卫与 `message.*` 全失效**

- **位置**：`service/message/src/main/java/com/rag/message/MessageServiceApplication.java`
- **现象**：`config/MessageApiProperties` 与 `config/MessageProperties` 按本仓 C 侧惯例**去掉了 `@Component`**
  ⇒ ⇒ **不会被组件扫描注册** ⇒ ⇒ 缺 `@ConfigurationPropertiesScan("com.rag.message.config")` 时
  ⇒ **`MessageApiProperties` 的 fail-closed 守卫（空密钥 ⇒ 启动失败）整个不生效**
  ⇒ ⇒ `message.list.*` / `message.broadcast.*` 也全不绑定。
- **修法**：入口类加 `@ConfigurationPropertiesScan("com.rag.message.config")`
  （与已落地域 `AdminServiceApplication` 同款）。
- **验证**：**运行期**断言两个 Properties bean 都在 ＋ **两段密钥真的绑上**
  ＋ `message.list.default-limit=20` / `max-limit=100` / `broadcast.page-size=200` 全部生效。

### 5-⑩ 🔴 P1 · `application.yml` **完全没有 `observability` 段** ⇒ 3s HTTP 预算与 2s gRPC 超时**都不存在**

- **位置**：`service/message/src/main/resources/application.yml`（修前顶层键只有
  `spring` / `message-api` / `message` / `server` / `grpc` / `mybatis-plus` / `management` / `logging`）
- **B 出处**：
  - `service/message/message-api/src/main/resources/application.yml:248-254` ⇒ `observability.http-timeout: 3s`
    （取值依据：Go 侧 `etc/messagecenter.yaml` **未配置** Timeout ⇒ 实际生效值来自 go-zero v1.10.2
    `rest/config.go:55` 的 `Timeout int64 json:",default=3000"` ⇒ 3000ms；且 `DisableNativeRestTimeout`
    已把 go-zero 原生超时中间件关掉 ⇒ **超时只有一个执行者**）
  - `service/message/message-rpc/src/main/resources/application.yml:352-355` ⇒ `observability.rpc-timeout: 2s`
    （取值依据：Go yaml 未配 Timeout ⇒ go-zero `zrpc/config.go:46` 默认 2000ms）
- **现象**：`ObservabilityProperties` 的两个 timeout **没有 env 兜底**，缺省即 `Duration.ZERO`（不限时）
  ⇒ ⇒ **不写这两个键 = 3s HTTP 预算与 2s gRPC 超时都不存在**
  ⇒ ⇒ 慢请求**不会**返回 503 + `Request Timeout`（B 侧会），gRPC 调用也**不会**被服务端超时打断（B 侧会）。
  ⚠️ 这是 **AGENTS.md §3.3.1 的教科书案例**：「配置里没写」≠「没开」——
  **键缺失 ⇒ 机制静默不生效，且不报错**。
- **⚠️ 与本仓已落地域的对照**：`service/users/user/src/main/resources/application.yml:75-77`
  **已经显式写了**这两个键，且其注释原文写着「⚠️ **这两个键缺失 = 机制静默不生效**… 必须显式写」
  ⇒ ⇒ **同一个坑，users/user lane 踩过并显式写了；message 域漏了** ⇒ 本条是**真实的回归**，不是设计差异。
- **修法**：在 yml 里补 `observability:` 段（`http-timeout: 3s` ＋ `rpc-timeout: 2s`），
  并把 B 出处与「缺键即静默失效」的判据写进注释。
  ⚠️ **D1 合并 ⇒ 两个键都要写**：B 侧 api 进程只管 http-timeout、rpc 进程只管 rpc-timeout；
  本仓是**单一进程**（Tomcat 8883 + gRPC 8084 同起）⇒ 两个预算**同时存在** ⇒ 两个键都必须显式写
  （与 `service/users/user` 的写法同款）。
- **验证**（**运行期**，AGENTS.md §3.3.1 要求「读到消费点的那段控制流」）：
  - ① `ObservabilityProperties#getHttpTimeout()` == `Duration.ofSeconds(3)`；
  - ② `getRpcTimeout()` == `Duration.ofSeconds(2)`；
  - ③ **不只看 Properties**：断言**消费它的两个机制真在执行链里** ——
    `HttpMiddleware`（filter · `HIGHEST_PRECEDENCE + 7`）在容器里 ＋
    `GrpcObservationInterceptor` 在容器里（它被 `ProtectionAutoConfiguration#seaGrpcServerConfigurer`
    用 `interceptors.orderedStream()` 收进 gRPC 服务端链 ⇒ **不是「Bean 存在」而是「在链里」**）。
- **连带更正**：本条同时**推翻了本台账原先 §六·6-6 的表述前提**（见 §六·6-6 的更正说明）。
- **教训**：**合并进程时，两个来源进程的配置段要「合起来」逐键核对** ——
  B 侧 api yml 的 `observability` 只覆盖 HTTP 面、rpc yml 的只覆盖 gRPC 面，
  **任何一份单独看都「有这一段」** ⇒ 合并后如果只抄一份，另一半的预算就静默消失了。

---

## 六、登记为 Intentional Deviation / 已批准降级的项

> 📌 **本节是交付物**（登记完就躺着，不逐条上抛拍板）。
> ⚠️ **`docs/Intentional_Deviations/_INDEX.md` 本 lane 不碰**（AGENTS.md §五.3）⇒ 由收口执行者统一更新。

| # | 项 | 性质 | 依据 | 影响面 | 状态 |
|---|---|---|---|---|---|
| 6-1 | **端口**：B 侧 api = HTTP `8892` / rpc = gRPC `8082` ⇒ C 侧合并为 HTTP `8883` + gRPC `8084` | **部署面**（D1 合并的连带效应） | 决策锁 D1 | 端口发现 | 🏷 已登记 |
| 6-2 | **DDL 建表来源**：**照抄 B 侧 Flyway**（独立账本 `flyway_schema_history_message` ＋ `baseline-version: 0`）⇒ **不**新增 `MessageSchemaInitializer` | **口径改判**（推翻映射表原「幂等 DDL 重放」决策锁） | 依据：message pom 本就带 Flyway 依赖 · 文件树**无** `MessageSchemaInitializer` · AGENTS.md §六 给出的其余 7 域候选就是「B 侧做法」· users 域弃用 Flyway 是因为 **B 侧 users 自己就用 `SchemaInitializer`**（D16 ⇒ message 抄 Flyway） | 建表 | 🏷 已登记 |
| 6-3 | **admin secret 15 字节 ⇒ zero-pad 到 32 字节** | **安全面**（运行环境约束） | jjwt 0.12 强制 ≥256 bit · golang-jwt 不校验长度 | **B 侧签的 admin token 在 C 侧验不过**（两套部署各自闭环）；payload / claim / 过期语义**完全一致** | 🏷 已登记（B 侧已登记 · N-5） |
| 6-4 | **Nacos `namespace` / `group`**：B 侧 message-rpc = `public` / `DEFAULT_GROUP` ⇒ C 侧 = `sea-rtw` / `USER_GROUP` | **部署面**（非行为面） | B 侧 message→user/admin 的 gRPC 调用走 **etcd key `user.rpc` / `admin.rpc`** ⇒ **etcd 没有 namespace / group 概念** ⇒ ⇒ B 侧那两个值**不承载任何行为**；而 C 侧走 **Nacos discovery** ⇒ namespace / group **真的参与寻址** ⇒ 不对齐 ⇒ 已落地的 `users/user`（注册名 `user-rpc` · group `USER_GROUP` · namespace `sea-rtw`）**在本域寻址空间里不存在** ⇒ 链路断 | 跨域寻址 | 🏷 已登记 |
| 6-5 | **`JsonbMapTypeHandler` 留在域内**（共享层无对应物） | **归属待收口** | 共享层 `JsonbTypeHandler` = jsonb↔String · `JsonTextTypeHandler` = text↔Map ⇒ **类型都不兼容** | 收口任务应上收到 `common/` | 🏷 已登记 |
| 6-6 | **验签只在 3s 预算之内执行一次**（B 侧**执行两次**：Filter 一次在预算**外** + interceptor 兜底一次在预算**内**） | **继承 B 侧漂移**（本轮**更正了原表述** ⇒ 见下方说明） | B 侧 `message-api/.../auth/MessageJwtAuthFilter.java:123-165`（`SOURCE_FILTER` / `SOURCE_INLINE` 两个常量 + `doFilter` **只记录不写响应**、无条件 `chain.doFilter` ⇒ **最终 401 由 interceptor 抛**）· B 侧台账 `DRIFT-MESSAGEAPI-009②` · 映射表 §1.6 记录的 **C 侧缺口**（C 侧无独立 Servlet Filter ⇒ 验签与 401 合在 `HandlerInterceptor` 内） | **验签耗时全部计入 3s 预算**（B 侧有一部分在预算外）；**响应形状逐条相同** | 🏷 已登记（**未闭环**） |

> **⚠️ 关于 6-6 的表述更正（2026-11-02 · 本轮）**：
> 原表述「B 侧在预算外」**不准确** —— B 侧**也**在预算内验签。
> B 侧源码原文（`MessageJwtAuthFilter.java:129-133`）自证：
> - `SOURCE_FILTER = "filter"` ⇒ 「来源 = 本 Filter（**验签在 3s 预算之外**）」
> - `SOURCE_INLINE = "inline"` ⇒ 「来源 = interceptor 兜底就地验签（**验签在 3s 预算之内** ⇒ ② 未闭环）」
> ⇒ ⇒ **B 侧验签执行两次**（Filter 一次 + interceptor 兜底一次），**C 侧只执行一次**（interceptor 内）。
> ⇒ ⇒ **差异面 = 验签次数与耗时分布**，**不是「一侧有预算、一侧没有」**；
> ⇒ ⇒ **响应面逐条相同**（401 + 空 body；claim 缺失 ⇒ 5004 + HTTP 200）⇒ 不改变任何 L1 契约。
> ⇒ ⚠️ **连带影响**：本条的前提是「C 侧存在 3s 预算」——
> 而本轮新发现的 **5-⑩**（`observability` 段缺失）恰恰让这个前提**不成立** ⇒ **5-⑩ 修完后本条才真正成立**。
> ⇒ 📌 **顺序依赖**：先补 `observability.http-timeout: 3s`（5-⑩）⇒ 本条才从「连预算都没有」升级为「预算内的验签次数少一次」。
| 6-7 | **Bean Validation 替换 `GoRequiredFieldException`**（D5） | **L3 降级** | 响应**形状**一致（**HTTP 200 + `{code:5001}`**）⇒ 仅**错误文本**不同（Go 是 `field "x" is not set` ⇒ C 是 Hibernate Validator 的文案） | 错误文本 | 🏷 已登记（D-01） |
| 6-8 | **415 / 404 / 405 保留 Spring / 共享层的框架级裸响应** | **框架差异** | B 侧 go-zero 没有媒体类型协商（`withJsonBody` 只判 Content-Type **包含** `application/json`）⇒ 两侧都是**拒绝**、都不产生业务副作用 | 仅在客户端 Content-Type 写错时 | 🏷 已登记 |
| 6-9 | **D2：域内 gRPC 往返被消掉** —— B 侧 `message-api` → `message-rpc` 的那一跳 gRPC **不再存在**（api 直连领域层，`MessageRpcClient` 删除） | **传输层删除**（D2 决策锁） | D2 决策（api 直连领域层 · gRPC 层薄转发）⇒ 协议 §8.3 第 ⑤ 项「原有域内 RPC 往返是否按既定设计调整」的登记项 | **调用栈少一跳**；错误映射落点从「网络边界」移到「进程内」⇒ **业务码与响应形状逐条不变**（错误码仍走同一套 `ErrCodes` + 同一套 gRPC fallback 表） | 🏷 已登记 |

> 📌 **message 域无 MQ / Kafka 契约** —— **不是偏离**：B 侧 `service/message/**` 对 MQ 的引用数为 **0**
> （无 `org.apache.kafka` / `springframework.kafka` 依赖）⇒ 两侧一致。

---

## 七、测试资产与证据强度

### 7.1 本域测试（**11 个测试类 · 103 例 · 全部真触发**）

> 📌 **其中 5 个类是「B 侧期望值移植」**（§十）—— 断言与期望值**逐字沿用 B 侧**（D13）。

| 测试类 | 例数 | 类型 | 守的是什么 |
|---|---|---|---|
| `MessageDtoWireShapeTest` | **25** | 纯单元 | 17 个 record 的**键名 / `,string` 口径 / 空值口径**逐个（wire 可见面） |
| `MessageErrorContractTest` | **13** | 纯单元 | 20 码 / 20 文案 / 未知码回落 / **gRPC fallback 表** / **判定顺序（先 `CodeError` 再 status）** / cause 链解包 |
| `MessageDomainServiceRealPgTest` | **12** | **真 PG**（Testcontainers `postgres:16-alpine` ＋ 真 Flyway ＋ 真 MP ＋ 真 `@Transactional` 代理） | 领域层 9 用例的关键分支：会话 id 写回 · `5204` pending · **5206 首条 50 码点** · `limit/offset` 传反 · `total/items` 同口径 · `5203` 不存在 · 全标已读 · **jsonb 往返 + PG 键序规范化** |
| `MessageServiceApplicationTests` | **11** | **真启动**（真 Tomcat ＋ 真 gRPC ＋ 真 PG ＋ 真 Flyway ＋ Nacos 桩） | 上下文起得来 · `@MapperScan` · `@ConfigurationPropertiesScan` · `@GrpcService` · 两段拦截器 · **13 端点运行期注册表** · **3 张表真建出来** · **独立账本 + 5 版本全 success + 默认账本不存在** · **Nacos 只注册 gRPC 端口** · 🔴 **`observability` 两个超时预算真绑上（3s + 2s）** |
| `MessageRpcSupportTrimPreviewTest` | **11** | **B 侧移植** · 纯单元 | 预览文本裁剪口径（B 侧 `MessageRpcSupportTrimPreviewTest`） |
| `MessageRpcSupportTimeZoneTest` | **7** | **B 侧移植** · 纯单元 | `DRIFT-MSG-V-020` 墙钟→epoch 换算与时区无关 |
| `MessageSemanticEquivalenceTest` | **7** | **B 侧移植** · **真 PG** | `DRIFT-MSG-V-001/-002/-004/-005/-006/-007` 六条语义漂移：`total`/`items` 同口径 · **6000 条批量（>5461 参数阈值）** · **分片后仍是单一事务（中途失败全回滚）** · `created_at` 排序 · `updated_at` 次级排序键 · 标已读**不**改写 `conversation.updated_at` · 墙钟往返逐秒相等 |
| `MessageCoercionContractTest` | **7** | **真启动 + 生产 `ObjectMapper`** | JSON 解析面 **6 组规则**（字符串→整数/布尔、数字→布尔、数字→String、显式 null→零值、正常组合）＋ **解析失败 ⇒ HTTP 200 + `{code:5001}`** |
| `MessageListConversationsParityTest` | **4** | **B 侧移植** · 纯 Mockito | `DRIFT-MSG-V-018`：`peerOf` 判定**顺序短路**（失败时**根本不查** `count_unread`）＋ `observeDbError("conversation","count_unread")` 埋点 ＋ `ERROR_FORBIDDEN=5005` |
| `MessageMarkNotificationReadTest` | **4** | **B 侧移植** · 纯 Mockito | 标通知已读的 UPDATE **WHERE 必须同时含 `id` 与 `recipient_id`** · `SET` 用 `.set()` 而非 `update(entity)`（避免丢 typeHandler）· 受影响行数原样返回 · 实体字段名必须是 `read`（不是 `isRead`） |
| `MessageTimeColumnParityTest` | **2** | **B 侧移植** · **真 PG** | `DRIFT-MSG-V-020`：**8 个时间列在真库里确实是 `timestamp with time zone`** ＋ 可空性与真源一致 ＋ **写入 +08:00 的瞬时读回同一瞬时** |
| `support/TestPostgres` + `support/NacosStubConfiguration` | 0 | 设施 | 单例真 PG 容器（**无** `@EnabledIfDockerAvailable`）· Nacos 桩（bean 名错开 + `@Primary` ⇒ 不触网） |

> **读数**（**模块级汇总**，来自回归日志 —— AGENTS.md §五.8 规则 ①）：
> ```
> mvn -o -pl service/message -am clean test -fae
> → Tests run: 103, Failures: 0, Errors: 0, Skipped: 0    （BUILD SUCCESS）
> ```
> **同断 `skipped=0`**（规则 ②）—— 本域 **0 skipped**。
> ⚠️ **不使用 `@EnabledIfDockerAvailable`**（规则 ③）—— 容器不可用时**直接红**（跳过 = 假绿）。
> ⚠️ **不使用 MockMvc** —— 断言 `Content-Type` / `nosniff` / 状态行一律走**真 Tomcat**。
> ⚠️ **不使用 `@Disabled`** —— 没有任何一条用例被关掉。
> ⚠️ **共享单例 PG ⇒ 数据必须互不冲突** —— 各测试类用**互不重叠的 id 段**
> （`MessageSemanticEquivalenceTest` 用 `@BeforeEach` ＋ `@AfterEach` **双向清表**；
> `MessageTimeColumnParityTest` 用独占 id `9_900_001`）。
> 📌 **顺序无关性已验**：`MessageSemanticEquivalenceTest` 的 `SELECT COUNT(*) FROM message_notification == 6000`
> 是**绝对表计数** ⇒ 会被别的类残留污染 ⇒ 已用 `-Dsurefire.runOrder=alphabetical` /
> `reversealphabetical` **两轮乱序回归**验证不变量（两轮均 `BUILD SUCCESS`）。

### 7.2 全量回归（**2026-11-02 实测** · `mvn -o clean test -fae` · 全仓 16 模块）

| 模块 | tests | failures | errors | skipped |
|---|---|---|---|---|
| admin-service | 39 | 0 | 0 | 0 |
| common | 224 | 0 | 0 | 0 |
| **message-service** | **103** | **0** | **0** | **0** |
| user-service | 39 | 0 | 0 | 0 |
| users-common | 46 | 0 | 0 | 0 |
| article / comment / favorite / follow / hot / like / task | 0 | 0 | 0 | 0 |
| **合计（模块级汇总求和）** | **451** | **0** | **0** | **0** |

**Reactor：16/16 SUCCESS · BUILD SUCCESS · EXIT=0**（`-fae` ⇒ 失败面**未被截断**）。
📌 **本轮新增 43 例**（message 域 60 → 103）：B 侧期望值移植 **35 例** ＋ JSON 解析面 **7 例** ＋ 超时预算运行期断言 **1 例**。

### 7.3 三道守门（**EXIT=0**）

| 守门 | 读数 | EXIT |
|---|---|---|
| `check-arch-invariants.py`（A1–A13） | **PASS 10 · FAIL 0 · N/A 2 · PENDING 1**（PENDING = like/comment/favorite 三个域的空壳日志载体 ⇒ **不属本域**） | **0** |
| `check-mapping-coverage.py message` | **C 53/53 = 100% · B 71/71 = 100%** · 自报数字与实测一致 · B 列全局未覆盖 0 | **0** |
| `check-docs-vs-tree.py` | File_Tree 双向 · 幂等 · 路径 · 落点计数 · 包名 全相符合 | **0** |

### 7.4 ⚠️ **证据强度的诚实边界**（**不计入 PASS 的部分**）

| 项 | 状态 | 为什么 |
|---|---|---|
| **双侧活体对拍** | ❌ **未做**（🟡 **但期望值已换成 B 侧的**） | 本轮**移植了 35 例 B 侧 `*ParityTest` / `*SemanticEquivalenceTest` 的断言**（期望值逐字沿用 B 侧 · D13）⇒ ⇒ 比「只跑 C 侧探针」强一档：期望值**不是本域自拟**的。⚠️ 但 AGENTS.md §3.3 的告诫**依然成立**：它证明「**C 侧符合 B 侧的既定期望**」，**证不了「B 侧现在就是这个行为」** ⇒ §三 的「一致」结论仍是 **L1 源码逐条对照 + B 侧期望值移植**级，**不是**双侧活体对拍级。⇒ 见 §十·N-1。 |
| **B 侧真启动 A/B** | ❌ **未做** | B 侧是 Boot 4 + go-zero 服务链，本轮**未在 B 侧启动 message 域**做同条件对照。 |
| **变异测试** | ❌ **未做** | 本域断言**未配变异证据**（仓库内已有 P-12 承载该方法论）⇒ 「断言不是假绿」只做到**机制腿**（真 PG / 真 Tomcat / 真 Flyway / 真 `@Transactional` 代理），未做到**变异腿**。⇒ B 侧自带的变异记录（5 条各自变红）已原样保留进 C 侧类 javadoc 作为**出处**，但**不等于 C 侧已验过变异**。 |
| **HTTP 层真 Tomcat 契约** | 🟡 **部分** | `MessageServiceApplicationTests` 走了**真 Tomcat + 随机端口**，`MessageCoercionContractTest` 走了**真启动 + 生产 `ObjectMapper`**（JSON 解析面 6 组规则 ＋ **5001 信封**）⇒ 但**未逐端点断言响应头字节形状**（`Content-Type` / `nosniff` / 状态行）⇒ 该面**未覆盖**，不计入 PASS。 |
| **gRPC 端到端对拍** | 🟡 **部分** | 9 个 gRPC 方法的**服务实现**已落地且埋点落点与 B 侧一致（§三·3.4），`@GrpcService` 真注册、gRPC server 真监听 ⇒ 但**未做真 gRPC 客户端 → 服务端的活体对拍**。 |
| **超时预算** | 🟡 **部分** | 🔴 **本轮新修**：3s HTTP ＋ 2s gRPC 两个预算**已真绑上**（运行期断言 ＋ 消费点在链里）⇒ 但**未做「真触发」**（没有故意慢的处理路径去观察 503）。⇒ 见 §十·N-5。 |
| **跨域 gRPC 链路** | 🔴 **已知不通** | `discovery:///admin-rpc` 找的是 Nacos 服务名 **`admin-rpc`**，而已落地的 `service/users/admin` 注册名是 **`admin-service`** ⇒ **寻址落空** ⇒ **归 users/admin lane**（§六·6-4 附注）。 |

> ⇒ **据此，本域的实现状态 = 「全部落地且本侧有运行证据（含 35 例 B 侧期望值）」；
> 但「与 B 侧行为等价」的最终结论仍受上表第一条限制。**

---

## 八、状态汇总

### 8.1 本轮关键数字（**核对日期 2026-11-02**）

| 指标 | 数 | 口径 |
|---|---|---|
| B 侧源文件 → C 侧承接 | **71 → 53**（api 49 · common 3 · rpc 19） | §二 |
| C 侧主代码行数 | **9,909** | `find … -name '*.java' -exec cat` |
| **空壳数** | **0** | 53/53 全部有实现 |
| DTO 数 | **17**（B 侧 18 ⇒ `GoStyleStringInt64` 上收到共享层 `StringInt64`） | §二·2.1 |
| 错误码 / 文案 | **20 / 20 · 逐字节一致** | §三·3.2 |
| HTTP 端点 | **13/13 · 运行期注册表逐条相等** | §三·3.1 |
| 指标名 | **8/8 逐条一致** | §三·3.4 |
| `observeRpc` 埋点 | **9（B）= 9（C）· gRPC 层 0 = 0** | §三·3.4 |
| DDL | **5/5 逐字节一致（`cmp`）** | §三·3.3 |
| 核心层主体差异行 | `MessageDomainService` **102** · `MessageRpcSupport` **16** · `MessageAtomicService` **12** —— **全部可解释为表达层改写** | §三·3.8 |
| 本域测试 | **11 类 · 103 例 · 0 失败 · 0 错误 · 0 跳过**（其中 **35 例是 B 侧期望值移植**） | §七·7.1 |
| 全仓回归 | **16/16 模块 SUCCESS · 451 例 · 0/0/0 · BUILD SUCCESS** | §七·7.2 |
| 三道守门 | **3/3 EXIT=0** | §七·7.3 |
| 本轮修掉的漂移 | **10 条**（P0 ×4 · P1 ×2 · P2 ×4） | §五 |
| 登记的偏离 / 降级 | **9 条** | §六 |
| Lombok 类 | **20/20 带展开块**（A13 PASS） | §七·7.3 |
| **对拍资产** | **6 个 B 侧测试类 · 35 例移植**（期望值逐字沿用 B 侧 · D13） | §十·11.3 |

### 8.2 未闭合项（**移交主会话 / 其它 lane**）

| # | 项 | 归属 | 严重度 |
|---|---|---|---|
| U-1 | ~~**跨域注册名不一致**~~ —— 本域 `discovery:///admin-rpc` vs `service/users/admin` 注册名 `admin-service` ⇒ 寻址落空<br>✅ **2026-11-02 已由 users/admin lane 修正**：admin 的 Nacos 注册名改回 **B 侧逐字值 `admin-rpc`**（B 出处 `admin-rpc/.../NacosRegistration.java:88`）⇒ 本域的 `discovery:///admin-rpc` **寻址命中**；namespace `sea-rtw` / group `USER_GROUP` 两侧本就一致 ⇒ **三轴全对齐**<br>⚠️ 尚未做的只有**三方活体**（真 Nacos + 真 message + 真 admin 同跑） | **已修正**（users/admin lane · admin 台账 §五·5-⑪） | ✅ 已闭合 |
| U-2 | **`JsonbMapTypeHandler` 上收到 `common/`**（jsonb↔Map 无对应物） | **收口任务**（`common/` 写权） | 🟡 P2 |
| U-3 | **§1.6 缺口**：验签耗时计入 3s 预算（B 侧在预算外） | 本域已登记 · 需独立 Filter 才能闭环 | 🟡 P2 |
| U-4 | **双侧活体对拍 + 变异证据缺失** ⇒ §七·7.4 的诚实边界<br>🟡 **本轮已推进**：移植 **35 例 B 侧期望值**并在 C 侧跑绿（**期望值不再是本域自拟**）⇒ 但**仍未做「B 侧活体跑出同一读数」**（N-1） | 后续轮次（需在 B 侧起同条件探针） | 🟡 P2 |
| U-5 | **`docs/DRIFT/_INDEX.md` 未更新**（本 lane 按 §五.3 不碰） | **主会话** | 🟢 流程 |
| U-6 | **`docs/Intentional_Deviations/_INDEX.md` 未更新**（同上） | **主会话** | 🟢 流程 |

### 8.3 对 `.agents/verification.md` 十四问的逐条回答

| # | 问题 | 回答 |
|---|---|---|
| 1 | C 是否完整承接 B 的全部实际职责？ | **是** —— 71 个源文件逐个有落点（§二），**0 空壳**；唯一的「删除」是 `MessageRpcClient`（D2 语义内联，B 侧 api→rpc 调用被 C 侧的「api 直连领域层」取代）。 |
| 2 | Mapping_Table 的映射是否与真实代码一致？ | **是** —— C 53/53 · B 71/71（守门 EXIT=0）；本轮**改判了 6 处事实错误**（§五·5-⑦）。 |
| 3 | File_Tree 规定的目标结构是否落地？ | **是** —— `gen-file-tree.py` 按实际文件系统重算，**只改 message + proto 两份**（域隔离干净）；`check-docs-vs-tree.py` EXIT=0。 |
| 4 | 多文件合并是否造成职责 / 分支 / 异常路径遗漏？ | **否** —— 合并处（13→1 case service、3→1 拦截器、`auth/` 2 类 → 基类、B 2 个 metrics → 基座 + 2 子类）逐个核过；核心层**主体差异 130 行全部可解释**（§三·3.8）。 |
| 5 | 文件拆分是否造成调用链 / 数据传递 / 事务边界断裂？ | **否** —— 事务边界由 `MessageAtomicService` 承载，**真 PG 用例靠真 UNIQUE 冲突证明回滚**（非 Mockito 桩）；`@Transactional` 自调用缺陷（B 侧 `DRIFT-MSG-001`）已按 B 侧形态继承。 |
| 6 | API + RPC 合并是否改变入口和执行行为？ | **入口变了**（D1：1 个进程 / 2 个 `main` → 1 个），**行为没变** —— 13 端点 + 9 gRPC 方法的 **method / path / 错误契约 / 指标名逐条相等**；端口差异已登记（§六·6-1）。 |
| 7 | Common Starter 上收是否遗漏原有消费方？ | **否** —— 本域上收 3 项（`MapStringValueDeserializer` / `Coercion` / `StringInt64`），**零遗漏**（`common` 224 例绿）；**反向**的 `JsonbMapTypeHandler` **无处可归** ⇒ 域内副本 + 待收口（§六·6-5）。 |
| 8 | Boot 4 → 3.5 是否引入业务可观察行为变化？ | **一处**（且是**继承**而非**引入**）：验签是否计入 3s 预算（B 侧在预算外 · C 侧在内）⇒ §六·6-6。其余 6 项框架差异（§四）**不改变业务可观察行为**。 |
| 9 | Tomcat / Jackson / Spring 版本变化是否产生实际漂移？ | **否** —— §四 F-1/F-2/F-4/F-5/F-6 逐条有适配与证据；本域 **0 处** `tools.jackson` 引用。 |
| 10 | 哪些差异已通过证据证明安全？ | **10 条**：§三·3.1（13 端点）· §三·3.2（20 码 20 文案 + fallback + 判定顺序）· §三·3.3（DDL + 独立账本）· §三·3.4（8 指标 + 9 埋点落点）· §三·3.5（claim 名 + 401/5004 边界 + 路径鉴权）· §三·3.6（通道名 + 三斜杠 + 注册名）· §三·3.7（配置键名）· §三·3.8（核心层主体零行为差异）。 |
| 11 | 哪些差异仍无法确认？ | **§七·7.4 的 6 项** —— 双侧活体对拍（未做）· B 侧真启动 A/B（未做）· 变异证据（未做）· HTTP 响应头字节面（未覆盖）· gRPC 端到端活体对拍（未做）· 跨域 gRPC 链路（🔴 已知不通 · 归 users/admin lane）。 |
| 12 | 当前 C 是否具备可靠的行为对拍条件？ | **C 侧已具备**（103 例真触发 ＋ 独立账本 ＋ 真 PG ＋ 真 Tomcat ＋ **35 例 B 侧期望值已移植跑绿**）；**缺口在 B 侧一侧**（需在 B 侧起同条件探针 · §十·N-1）。 |

### 8.4 对 `.agents/verification.md` **八阶段**的逐条判定（**本轮的总账**）

> 📌 **判定口径严格按协议 §十的状态表**：
> `PASS` = 职责已完整承接、行为一致**且有充分证据**；
> **`NOT_VERIFIED` / `NOT_IMPLEMENTED` / `BLOCKED` / `FRAMEWORK_DIFFERENCE` 都不能计入 `PASS`**。

| 阶段 | 协议要求（章节） | 本域落点 | **判定** | 未闭合项 |
|---|---|---|---|---|
| **一** | 确认迁移范围和现状（§四） | 台账 **§一**（两侧坐标 · **实际解析版本** · 使用的资料）；B 侧 Boot **4.0.8** / C 侧 Boot **3.5.11** | ✅ **PASS** | 无 |
| **二** | 建立 B → C 职责承接清单（§五） | 台账 **§二**：B 侧 **71** 个源文件（api 49 · common 3 · rpc 19）+ 5 份 DDL + 1 份 proto **逐个有落点**；C 侧 **53** 个文件、**0 空壳** | ✅ **PASS** | 无（唯一的「删除」是 M-10 `MessageRpcClient`，D2 决策锁 ⇒ 已登记 §六·6-9） |
| **三** | 完整行为审核（§六：外部契约 / 执行流程 / 异常语义 / 状态与副作用） | 台账 **§三**（13 端点运行期注册表 · 20 码 20 文案逐字节 · DDL `cmp` ×5 · 8 指标 · JWT 边界 · gRPC 通道与注册名 · 配置键名 · 核心层主体 diff） | ✅ **PASS** | 无（L1 全部有运行期或逐字节级证据） |
| **四** | Boot 4 → 3.5 专项（§七） | 台账 **§四** F-1~F-7 **八字段齐全** | ✅ **PASS** | F-3（验签预算归属）与 F-7（Nacos 寻址）**分别以 §六·6-6 / 6-4 登记**，不计入「无差异」 |
| **五** | 文件合并 / 拆分 / 架构调整（§八 · 报告项 §11.3） | 台账 **§九**（**本轮新写**）：§8.1 的 **10 个 N:1** ＋ §8.2 的 **4 个 1:N** ＋ §8.3 的 **9 项** ＋ §8.4 的 **8 项** ＋ §8.5 的 **9 项** ＋ §11.3 的 **7 类** —— **逐项有结论，无一处留空** | ✅ **PASS** | 本阶段**直接暴露并修掉 2 条真缺口**（§五·5-④ Nacos 双注册、§五·5-⑩ 超时预算缺失）＋ **更正 1 条错误表述**（§六·6-6） |
| **六** | 行为对拍和验证（§九） | 台账 **§十**（**本轮新写**）：12 项优先行为逐项 ＋ 11 类场景逐项 ＋ **35 例 B 侧期望值移植** ＋ **7 项未闭合面逐项说明缺什么环境** | 🟡 **PARTIAL —— 不可判 PASS** | **7 项**：N-1 B 侧活体对拍（NOT_VERIFIED）· N-2 响应头字节面（NOT_VERIFIED）· N-3 gRPC 端到端（NOT_VERIFIED）· N-4 并发（NOT_VERIFIED）· N-5 真实超时触发（NOT_VERIFIED）· N-6 变异证据（NOT_VERIFIED）· N-7 跨域 gRPC 链路（**BLOCKED** · 归 users/admin lane） |
| **七** | 审核结果状态（§十） | 台账 **§八**（本节 8.1 数字 ＋ 8.2 未闭合项 ＋ 8.3 十四问 ＋ **8.4 八阶段**）；状态词严格取自协议状态表 | ✅ **PASS** | 无 |
| **八** | 审核报告（§十一：11.1~11.6） | 台账 §一(=11.1) · §二(=11.2) · **§九(=11.3)** · §四(=11.4) · §五(=11.5) · §八(=11.6) —— **六项齐备** | ✅ **PASS** | 无 |

> ### 结论
> **八个阶段里 7 个 PASS、1 个（阶段六）PARTIAL。**
> ⇒ ⇒ **若问题是「本域的实现与台账能不能通过 `verification.md`」** ⇒ **能**：
> 协议要求的**六项报告内容全部齐备**，**每个状态词都有对应证据**，
> 唯一未达 `PASS` 的是**第六阶段（行为对拍）**，且**协议本身就允许**它以
> `NOT_VERIFIED` / `BLOCKED` 收尾（§九末段：「如果某项行为无法运行验证：明确说明缺少什么环境…
> **不得用静态代码相似度替代运行验证**」）⇒ **本报告没有拿静态相似度冒充对拍**。
> ⇒ ⇒ **但**：「C 与 B 的行为**已被活体证明等价**」这句话**本轮仍不成立** —— 那需要 N-1（在 B 侧起同条件探针）。

---

## 九、第五阶段专项报告：多文件合并 / 拆分 / 架构调整（**协议 §8.1–§8.5 · 报告项 §11.3**）

> 📌 **本节是 `.agents/verification.md` **第五阶段**（协议 §八）与**报告项 §11.3** 的专节** ——
> 协议原文：「**这部分必须单独形成审核结果**」。
> 📌 **口径**：§8.1–§8.5 的**每一项子要求都在下面逐条出现**（不省略、不合并、不「同上」）；
> §11.3 点名要显式列出的 7 类见 **§10.6**。

### 10.0 本域命中的架构调整全景

| 决策 | B 侧形态 | C 侧形态 | 影响面 | 本节对应 |
|---|---|---|---|---|
| **D1** | api / rpc **两个独立进程**（两个 `main`、两份 `application.yml`） | **一个进程**（Tomcat `8883` ＋ gRPC `8084` 同起） | 入口 · 配置 · 端口 · 超时预算 · 账本 | 10.1-M3/M7 · 10.3 · 10.5 |
| **D2** | api → rpc **走 gRPC**（`MessageRpcClient`） | api **直连领域层** | 域内 gRPC 往返消失 | 10.1-M10 · 10.3-⑤ · §六·6-9 |
| **D3** | 共享能力靠 `scanBasePackages` / `@Import` **清单** | **common starter**（`AutoConfiguration.imports`） | Bean 装配 · 生命周期 | 10.4 |
| **D7** | `com.rag.message.message.rpc.*` **多层包** | `com.rag.message.*` **包压平** | 全部 71 个落点 | 10.1 · 10.2 |
| **D5** | 不可变 DTO（Go 语义） | **immutable record** | 13 端点的响应形状 | 10.1-M8 |

### 10.1 §8.1 多文件合并（**N:1**）—— 10 个合并点逐项核查

> 协议 §8.1 对每个 N:1 映射要求 8 项：列出所有 B 源文件 · 列出每个文件的全部实际职责 · 找出 C 承接位置 ·
> 逐项核对执行流程 · 检查异常边界 · 检查事务边界 · 检查副作用 · 检查生命周期。
> ⇒ 下表**八个核查列全部有内容**，无一处留空。

| # | 映射类型 | 全部 B 源文件（各自的实际职责） | C 承接位置 | 执行流程核对 | 异常边界 | 事务边界 | 副作用 | 生命周期 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| **M-1** | **13:1** | 13 个 case service，每个只做「取身份 → 调领域层 → 投影 → 包信封」一件薄事：<br>`MessageAdminGetMessagesService` · `MessageAdminListConversationsService` · `MessageAdminMarkConversationReadService` · `MessageAdminSendChatService` · `MessageAdminSendNotificationService` · `MessageGetMessagesService` · `MessageGetUnreadSummaryService` · `MessageListConversationsService` · `MessageListNotificationsService` · `MessageMarkAllNotificationsReadService` · `MessageMarkConversationReadService` · `MessageMarkNotificationReadService` · `MessageSendUserChatService` | `service/MessageApiService.java` —— **13 个 public 方法，方法名逐条对应**：<br>`listConversations` · `getConversationMessages` · `markConversationRead` · `sendUserChatMessage` · `listNotifications` · `markNotificationRead` · `markAllNotificationsRead` · `getUnreadSummary` · `listAdminConversations` · `getAdminConversationMessages` · `markAdminConversationRead` · `sendAdminChatMessage` · `sendAdminNotification` | **13 条链路逐条核过**：每个 C 方法内部保持 B 侧同名 case service 的四段式（身份 → 领域调用 → 投影 → 信封） | 统一经 `MessageApiSupport.rpcError(Throwable)`（B 侧 `AbstractMessageApiService` 的同一入口 `:147/:168/:189`）⇒ **边界未变** | **无事务**（13 个方法皆单读或单写；写事务在领域层 `MessageAtomicService`）⇒ **未变** | 读路径 0 副作用；写路径副作用全在领域层 ⇒ **未变** | 无自有生命周期（普通 bean）⇒ **未变** | **PASS** — §三·3.1 运行期注册表 13/13 ＋ §七·7.1 真启动 |
| **M-2** | **3:1** | `auth/MessageJwtAuthFilter.java`(187 · Servlet Filter 验签 · **只记录不写响应**) · `auth/MessageJwtSignatureVerifier.java`(163 · 两把 key 验签 ＋ `RouteGroup` ＋ `VerificationResult`) · `interceptor/AbstractMessageJwtAuthInterceptor.java`(149 · 401 构造 ＋ `unauthorized`) | `interceptor/AbstractMessageJwtAuthInterceptor.java`（基类：验签 ＋ 401 构造 ＋ `RouteGroup`）＋ `MessageJwtAuthInterceptor`(user key) ＋ `MessageAdminJwtAuthInterceptor`(admin key) | **顺序未变**：解析 Bearer → 取 key（子类给出）→ 验签 → 放行 / 401 | **未变**：401 ＋ **空 body**（B 侧 `rest/handler/authhandler.go:106` 只 `WriteHeader(401)`）⇒ C 侧同样只写状态码 | 无 | 只读 | **执行位置变了**（Filter 链 → Interceptor 链）⇒ 见 **§六·6-6** 的更正说明（响应面无差异，差异在验签次数与预算归属） | **PASS**（机制差异已登记） |
| **M-3** | **2:1** | `MessageApiApplication.java`(81) ＋ `MessageRpcApplication.java`(140) | `MessageServiceApplication.java`（`main` 一段） | 两个 `main` 体**逐字相同**（都是 `SpringApplication.run(XxxApplication.class, args)`）⇒ 合并为**一个** `main` | 启动期异常语义不变（`SpringApplication.run` 抛出 ⇒ 启动失败） | 无 | 见 10.5 | 见 10.5 | **PASS**（⚠️ 曾缺 `observability` 段 ⇒ 已修 §五·5-⑩） |
| **M-4** | **1:2** | `errmsg/MessageGrpcError.java`(326 · gRPC 错误映射 ＋ cause 链解包 ＋ `StatusException` 构造) | `contract/GrpcErrorMapper.java`（6 个静态方法：`newCode` / `newCodeMsg` / `grpcError` / `statusMessage` / `bizCodeFromError`）＋ `contract/CodeError.java`（业务码载体：`getCode` / `getMsg` / `getMessage`） | 拆分后调用链：`bizCodeFromError(err)` → 命中 `CodeError` ⇒ 取 code/msg；未命中 ⇒ 按 gRPC status 回落 | **未变**：cause 链解包顺序 ＋ **先 `CodeError` 再 status** 的判定顺序 | 无 | 无 | 无 | **PASS**（`MessageErrorContractTest` 13 例） |
| **M-5** | **1:2** | `metrics/MessageMetrics.java`(180 · rpc 侧 5 个指标 ＋ `observeRpc` ＋ `observeDbError` ＋ `observeNotification` ＋ `observeChat`) | `metrics/MessageMetrics.java`（**共享基座**）＋ `metrics/MessageRpcMetrics.java`（rpc 侧子类） | 基座持 `MeterRegistry` 与公共计数器；子类补 rpc 侧 5 个指标 | 无 | 无 | **指标名与埋点位置逐条一致**：8/8 指标名；`observeRpc` **9 = 9**；gRPC 层 **0 = 0** | 无 | **PASS**（§三·3.4） |
| **M-6** | **1:N** | `config/MessageGoStyleCoercion.java`(120 · go-zero 解析规则) | `config/WebMvcConfig.java`（本域 **4** 条 `applyMessageDomainCoercions`）＋ `common/web/Coercion.java`（共享 **2** 条） | 本域 4 ＋ 共享 2 = **6 组规则**，逐条与 B 侧一致 | 无 | 无 | 请求体解析行为（哪些输入被拒/被接受） | 无 | **PASS**（`WebMvcConfig` javadoc 载 **6 组真 ObjectMapper 探针**） |
| **M-7** | **2:1** | `message-api/application.yml` ＋ `message-rpc/application.yml` | `service/message/src/main/resources/application.yml` | 两份合一，**键名全部保留**（`message-api.*` / `message.*` / `observability.*` / `grpc.*` / `spring.flyway.*` / `spring.cloud.nacos.*`） | n/a | n/a | n/a | n/a | **PASS**（⚠️ 曾漏 `observability` 段 ⇒ 已修 §五·5-⑩） |
| **M-8** | **17:17 + 1 上收** | 18 个 DTO（`GoStyleStringInt64` 在 B 侧是**域内私有**） | **17** 个 immutable record ＋ 共享层 `common/web/StringInt64` | 逐个 record 的**键名 / `,string` 口径 / 空值口径**逐条一致 | n/a | n/a | 响应形状 | 无 | **PASS**（`MessageDtoWireShapeTest` 25 例） |
| **M-9** | **1 上收** | `config/GoStyleMapStringValueDeserializer.java`(99) | `common/web/MapStringValueDeserializer.java` | 见 10.4-① | | | | | **PASS** |
| **M-10** | **删除** | `client/MessageRpcClient.java`(113 · api → rpc 的 gRPC 客户端) | **无**（D2：api 直连领域层） | B 侧链路 `controller → case service → MessageRpcClient →(gRPC)→ message-rpc → 领域层`；C 侧 `controller → MessageApiService → 领域层` ⇒ **中间一跳被消掉** | 错误映射从「gRPC status → 业务码」变成「领域层异常 → 业务码」⇒ **落点从网络边界移到进程内** | 无 | 无 | 无 | **PASS**（D2 决策锁）⚠️ **但这是本域唯一的「传输层删除」⇒ 已登记为 §六·6-9** |

### 10.2 §8.2 一对多拆分（**1:N**）—— 4 个拆分点逐项核查

> 协议 §8.2 要求 6 项：找出所有 C 承接文件 · 检查各文件之间的调用关系 · 检查数据传递 ·
> 检查异常传播 · 检查事务范围 · 检查是否存在职责断裂。

| # | 映射类型 | 全部 C 承接文件 | 调用关系 | 数据传递 | 异常传播 | 事务范围 | 职责断裂？ | 结论 |
|---|---|---|---|---|---|---|---|---|
| **S-1** | **1:2** | `service/MessageApiSupport.java`（11 个 public 方法：`userId` / `rpcError` / `adminNotificationKind` / `recipientIds` / 5 个 `to*Resp` 投影）＋ `service/MessageApiService.java`（13 个 case 方法 ＋ 1 个构造器） | `MessageApiService` → `MessageApiSupport`（**单向**：support 不反向依赖 service）⇒ 无环 | 支撑类产出 **DTO / proto 投影对象**，case 类消费后交给 advice 包信封 ⇒ 数据流完整 | `MessageApiSupport.rpcError(Throwable)` 统一把领域异常转成 `BusinessException` ⇒ 由 `MessageApiExceptionHandler` 收口 ⇒ **异常链完整** | 两者**均无事务** ⇒ 未变 | **无** —— B 侧 `AbstractMessageApiService`(203) 的「公共基类」职责被拆成「纯函数支撑」＋「case 编排」两块，**每块职责单一** | **PASS** |
| **S-2** | **1:2** | `contract/GrpcErrorMapper.java` ＋ `contract/CodeError.java` | `GrpcErrorMapper`（静态工具）→ `CodeError`（数据载体）⇒ 单向 | `bizCodeFromError(err)` 解包 cause 链后**构造并返回** `CodeError` ⇒ 数据完整 | 映射异常在 `GrpcErrorMapper` 内部消化，不外泄 ⇒ 未变 | 无 | **无** —— 「查表 / 兜底」与「承载结果」分离 | **PASS**（13 例） |
| **S-3** | **1:2** | `metrics/MessageMetrics.java`（基座）＋ `metrics/MessageRpcMetrics.java`（rpc 子类）＋ `metrics/MessageApiMetrics.java`（api 子类） | 两个子类各自继承基座 ⇒ **互不知晓** ⇒ 无共享可变状态 | 基座持 `MeterRegistry`（进程级单例）⇒ 指标注册只发生一次 | 指标采集**不抛**（Micrometer 吞掉内部异常）⇒ 未变 | 无 | **无** | **PASS**（8/8 指标名） |
| **S-4** | **1:N** | `config/WebMvcConfig.java`（本域 4 条）＋ `common/web/Coercion.java`（共享 2 条） | `WebMvcConfig#goStyleCoercionCustomizer` → 先调共享 2 条、再调本域 4 条 ⇒ **顺序固定** | Jackson `ObjectMapper` 配置在 customizer 链上累加 ⇒ 逐条生效 | 解析失败 → `HttpMessageNotReadableException` → `MessageApiExceptionHandler` ⇒ **HTTP 200 ＋ `{code:5001}`** | 无 | **无** | **PASS**（6 组真 ObjectMapper 探针） |

### 10.3 §8.3 **API ＋ RPC 合并** —— 9 项逐条核查

| # | 协议要求 | 本域落点 | 证据 | 结论 |
|---|---|---|---|---|
| ① | **HTTP 入口是否完整保留** | **13 个端点**（user 8 ＋ admin 5），method + path **逐条相等** | §三·3.1（**运行期** `RequestMappingHandlerMapping` 注册表，13/13） | **PASS** |
| ② | **gRPC 入口是否完整保留** | **9 个方法**（proto `service MessageService` 逐条对应） | proto 逐字节一致 ＋ `@GrpcService` 被 starter 扫到（真启动） | **PASS** |
| ③ | **RPC Server 是否正确启动** | gRPC server 随进程起，端口 = `grpc.server.port` | 真启动日志：`gRPC Server started, listening on address: 0.0.0.0, port: 63735` | **PASS** |
| ④ | **RPC Client 是否正确注册** | 2 个 `@GrpcClient`（`user-rpc` / `admin-rpc`）＋ yml 2 段（`discovery:///` 三斜杠 ＋ `negotiation-type: plaintext`） | A4 守门 **PASS**（「@GrpcClient 2 处 · yml grpc.client 2 处 · 全部 plaintext」）＋ 真启动 | **PASS** |
| ⑤ | **原有域内 RPC 往返是否按既定设计调整** | **D2：域内往返被消掉** —— B 侧 api → rpc 的那一跳 gRPC **不再存在**（api 直连领域层） | §二·2.1 `client/MessageRpcClient.java` 行 ＋ §九·10.1-M10 | ⚠️ **INTENTIONALLY_CHANGED**（D2 决策锁）⇒ 登记为 **§六·6-9** |
| ⑥ | **API 与 RPC 共用的 Bean 是否正确装配** | 共享层 starter 提供**一份**（`AutoConfiguration.imports`）⇒ 两面共用同一套 bean（Snowflake / Logger / Observability / Protection / Persistence） | A3 守门 PASS ＋ 真启动上下文 | **PASS** |
| ⑦ | **HTTP 与 RPC 的安全上下文是否受到影响** | HTTP 面：JWT 拦截器（`"userId"` claim）；**gRPC 面无鉴权**（B 侧 message-rpc 同样无）⇒ 合并后**两侧各自独立**，不串 | §三·3.5（claim 名 ＋ 401/5004 边界 ＋ admin 路径豁免 `/message/v1/admin/**`） | **PASS** |
| ⑧ | **两类入口是否出现不必要的共享状态** | **无** —— 指标按面分离（api 3 ＋ rpc 5，基座只持 `MeterRegistry`）；`observeRpc` **只在领域层**（9 处）⇒ **gRPC 层 0 处** ⇒ 不翻倍 | §三·3.4（`observeRpc` 9=9 · gRPC 层 0=0） | **PASS** |
| ⑨ | **应用启动和关闭时两类服务是否都正确工作** | 启动：Tomcat ＋ gRPC 同起（真启动日志双份）；关闭：`@PreDestroy`（`ObservabilityLifecycle` 回收进程级线程池 ＋ `NacosRegistration` 反注册） | 真启动测试 ＋ 共享层 `ObservabilityLifecycle` javadoc | **PASS** |

### 10.4 §8.4 **Common Starter 共享能力上收** —— 8 项逐条核查

| # | 协议要求 | 本域落点 | 证据 | 结论 |
|---|---|---|---|---|
| ① | **原有各消费方是否都被覆盖** | 本域消费共享层 **12 个类**（`StringInt64` / `BusinessException` / `AuthenticationException` / `ApiResponse` / `RouterErrorResponses` / `MapStringValueDeserializer` / `Coercion` / `Snowflake` / `JwtUtil` / `BearerTokenExtractor` / `ServiceBusyException` / `FatalError`）⇒ 覆盖 **15 个本域文件** | `vs-common.md` §1（逐类核实**非空壳**：`TODO：职责待补` 计数 = 0） | **PASS**（零「上收了没实现」） |
| ② | **原有配置是否全部保留** | B 侧 api/rpc 两份 yml 的**全部键**都在 C 侧合并 yml 里 | §三·3.7（配置键名逐条） | **PASS**（⚠️ 曾漏 `observability` ⇒ 已修 §五·5-⑩） |
| ③ | **原有 Bean 是否仍然存在** | 共享层 14 个 auto-config；本域依赖的那几个（Snowflake / Logger / Observability / Protection / Persistence）**真在容器里** | 真启动上下文 ＋ `common` 224 例绿 | **PASS** |
| ④ | **是否出现重复 Bean** | **零重复** —— 本域**刻意不扫 `com.rag.common`**（D3：共享层是 starter，再扫一遍会双注册）⇒ 域内**零** `GrpcServerConfigurer` / **零** `ServerInterceptor` 副本 | A3 守门 **PASS**（「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」） | **PASS** |
| ⑤ | **是否改变自动配置条件** | `@ConditionalOnMissingBean` 条件**逐条保留**（含 V03 修法的「按 bean 名判」） | `ObservabilityAutoConfiguration:171-191`（修法注释自带证据） | **PASS** |
| ⑥ | **是否改变装配顺序** | gRPC 链序由共享层 `RpcProtectionChain` 的**数值常量**决定（recover → stat → prometheus → breaker → shedding）⇒ 本域**不参与** ⇒ 顺序未被本域扰动 | `ProtectionAutoConfiguration:245-262` | **PASS** |
| ⑦ | **是否改变线程和资源生命周期** | 两个**进程级**线程池（`obs-rpc-timeout` / `obs-rpc-worker`）由共享层 `ObservabilityLifecycle` 统一 `@PreDestroy` 回收 ⇒ **单份**（合并前也是单份） | `ObservabilityAutoConfiguration:128-140` | **PASS** |
| ⑧ | **是否出现某些域能用、某些域不能用** | **正向**：上收后**所有域**共享同一实现 ⇒ 无分裂；**反向**：共享层**缺** `jsonb ↔ Map` 的对应物（`JsonbTypeHandler` = jsonb↔String · `JsonTextTypeHandler` = text↔Map ⇒ **类型都不兼容**）⇒ 本域**留域内副本** | §五·5-⑧ ＋ §六·6-5 ＋ `vs-common.md` §2.1 | **PASS**（缺口已登记为待收口） |

### 10.5 §8.5 **独立进程合并** —— 9 项逐条核查

> B 侧 `message-api` 与 `message-rpc` 是**两个可独立启动的进程**（各一个 `main`、各一份 `application.yml`）⇒ D1 合并后逐项核查：

| # | 协议要求 | 本域落点 | 证据 | 结论 |
|---|---|---|---|---|
| ① | **原有任务入口** | B 侧 message 域**无定时任务、无 MQ 监听**（全域 grep `@Scheduled` / `@KafkaListener` / `@RocketMQMessageListener` / `ApplicationRunner` / `CommandLineRunner` = **0**）⇒ **无需承接** | grep 实测（`service/message/**/src/main`） | **PASS**（无遗漏） |
| ② | **执行频率** | n/a（无任务） | 同上 | **PASS**（n/a） |
| ③ | **启动条件** | **1 个** `@PostConstruct`（B 侧 `MessageApiProperties:90` 的密钥 fail-closed 守卫）＋ **1 个** `NacosRegistration` 注册钩子 ⇒ 合并后**两个都在** | 真启动：两段密钥真的绑上 ＋ 桩上**只注册 1 次** | **PASS** |
| ④ | **关闭条件** | `@PreDestroy`：`ObservabilityLifecycle`（共享层，回收两个进程级线程池）＋ `NacosRegistration` 反注册 | 共享层 `ObservabilityAutoConfiguration:128-140` | **PASS** |
| ⑤ | **并发控制** | n/a（无任务） | — | **PASS**（n/a） |
| ⑥ | **重复执行保护** | n/a（无任务） | — | **PASS**（n/a） |
| ⑦ | **任务失败处理** | n/a（无任务） | — | **PASS**（n/a） |
| ⑧ | **资源隔离** | **这是合并的真正风险面**，逐项核：<br>① **端口**：HTTP `8883` ＋ gRPC `8084` ⇒ **不同端口，不冲突**；<br>② **Nacos 注册**：自动注册会注册 **web 端口**，与手写的 **gRPC 端口**注册 ⇒ 同名服务名下两个端口 ⇒ **链路断** ⇒ `register-enabled: false` **必须开**（§五·5-④ 已补）；<br>③ **超时预算**：B 侧 api 进程带 **3s HTTP**、rpc 进程带 **2s gRPC** ⇒ 合并后**两个预算同时存在** ⇒ **两个键都必须写**（§五·5-⑩ 已补）；<br>④ **Flyway 账本**：B 侧**只有 rpc 进程**有账本（api yml **无** flyway 段）⇒ 合并后**仍只有一本** ⇒ 不撞号；<br>⑤ **JWT 密钥**：B 侧**只有 api 进程**有 ⇒ 合并后密钥守卫对全进程生效（gRPC 面不用它） | ① §六·6-1 · ② §五·5-④ · ③ §五·5-⑩ · ④ §六·6-2 ＋ 真启动（5 版本全 success ＋ **默认账本不存在**）· ⑤ §五·5-⑨ | **PASS**（⚠️ ②③ 两项**曾是真缺口**，本轮补齐） |
| ⑨ | **资源隔离的副作用** | 合并后**进程内两个端口** ⇒ 部署侧要保证两个端口都暴露 ⇒ 已登记为部署面偏离 | §六·6-1 | **PASS**（已登记） |

### 10.6 §11.3 点名要显式列出的 **7 类** —— 汇总

| # | §11.3 要求 | 本域落点 | 结论 |
|---|---|---|---|
| 1 | **所有 N:1 映射** | **10 个** —— M-1(13:1) · M-2(3:1) · M-3(2:1) · M-4(1:2) · M-5(1:2) · M-6(1:N) · M-7(2:1) · M-8(17:17+1上收) · M-9(1上收) · M-10(删除) | 逐项见 **10.1** |
| 2 | **所有 1:N 映射** | **4 个** —— S-1 · S-2 · S-3 · S-4 | 逐项见 **10.2** |
| 3 | **所有多对多映射** | **0 个** —— 本域**没有**「B 侧 N 个文件 ↔ C 侧 M 个文件」的**双向**多对多；最接近的是 M-8（18 DTO ↔ 17 record ＋ 共享层 1 个）与 M-1（13 case service ↔ 13 方法），二者都是**单向可枚举**的 | **PASS**（无遗漏） |
| 4 | **共享能力上收** | **3 项**（`,string` 整数解析 / HTTP 信封载体 / 路由级 404·405 响应）＋ **1 项反向缺口**（`JsonbMapTypeHandler` 无对应物 ⇒ 域内副本） | 逐项见 **10.4** ＋ `vs-common.md` §2 |
| 5 | **Spring 接管** | **4 处** —— ① 共享层 starter 接管 B 侧的 `@Import` 清单 8 项；② devh starter 接管 gRPC server 启动与 `@GrpcService` 注册；③ Spring `@ResponseBodyAdvice` 接管成功信封；④ Spring `@RestControllerAdvice` 接管异常出口 ⇒ **每处都有「执行链」级证据**，非「Bean 存在」级 | 10.3-②⑥ · 10.4-③④ |
| 6 | **独立进程合并** | **1 处** —— `message-api` ＋ `message-rpc` → `message-service`（D1） | 逐项见 **10.5**（9 项全过） |
| 7 | **职责缺失** | **0 条** —— 71 个 B 源文件逐个有落点、**0 空壳**；唯一的「删除」是 M-10（`MessageRpcClient`，D2 决策锁）⇒ 已登记 §六·6-9 | **PASS** |

> **本阶段结论**：**PASS**（协议 §8.1–§8.5 全部逐项核查完毕；10 个 N:1 ＋ 4 个 1:N ＋ 1 处独立进程合并 ＋ 3 项上收 ＋ 4 处 Spring 接管全部有结论；
> 本轮由本阶段直接暴露并修掉 **2 条真缺口**（§五·5-④ Nacos 双注册、§五·5-⑩ 超时预算缺失），
> 另有 **1 条表述错误被更正**（§六·6-6 的「B 侧在预算外」））。

---

## 十、第六阶段专项报告：行为对拍和验证（**协议 §九**）

> 📌 **本节是 `.agents/verification.md` **第六阶段**（协议 §九）的专节** ——
> 协议原文：「对关键行为使用相同输入比较 B 与 C」；「**不得用静态代码相似度替代运行验证**」。
> 📌 **本轮的做法**：把 **B 侧测试资产**（`*ParityTest` / `*TimeColumnParityTest` / `*SemanticEquivalenceTest`）
> **移植到 C 侧**，**断言与期望值逐字沿用 B 侧**（AGENTS.md §1.0c「测试资产单独一档」＋ D13）
> ⇒ ⇒ **期望值不是本域自拟的** ⇒ 比「只跑 C 侧探针」强一档。

### 11.1 协议 §九「优先验证的 12 项行为」—— 逐项判定

| # | 行为 | 本域的验证手段 | 状态 |
|---|---|---|---|
| 1 | **HTTP 请求和响应** | 13 端点的 method + path 取自**运行期注册表**（`RequestMappingHandlerMapping`）⇒ 13/13 相等；**真 Tomcat**（随机端口）起得来；**JSON 解析面 6 组规则**有可执行断言；**解析失败 ⇒ HTTP 200 + `{code:5001}`** 有断言 | 🟡 **PARTIAL** —— 端点表 ＋ 请求体解析已验；**逐端点的响应头字节形状（`Content-Type` / `nosniff` / 状态行）未逐端点断言**（§七·7.4） |
| 2 | **RPC 请求和响应** | 9 个 gRPC 方法入参出参**就是 proto**（proto 逐字节一致）；`@GrpcService` 被 starter 扫到；gRPC server 真启动 | 🟡 **PARTIAL** —— 服务实现与 proto 契约已验；**未做「真 gRPC 客户端 → 服务端」活体对拍** |
| 3 | **JSON 解析和序列化** | 🔴 **本轮新补** `MessageCoercionContractTest`（**7 例**）—— 6 组规则 ＋ 5001 信封；断言的是**容器里那个生产 `ObjectMapper`**（不是另抄一份规则跑一遍） | ✅ **PASS**（本轮由「只有 javadoc 读数」升级为「可回归断言」） |
| 4 | **错误码和异常映射** | `MessageErrorContractTest` **13 例**（20 码 ＋ 20 文案 ＋ fallback 表 ＋ 判定顺序 ＋ cause 链解包）＋ `MessageListConversationsParityTest`（`ERROR_FORBIDDEN=5005`） | ✅ **PASS** |
| 5 | **数据库最终状态** | `MessageDomainServiceRealPgTest` **12 例**（真 PG ＋ 真 Flyway ＋ 真 MP）＋ `MessageTimeColumnParityTest` **2 例**（8 个时间列的类型与可空性 ＋ 瞬时往返）＋ `MessageSemanticEquivalenceTest` **7 例**（含 6000 条批量落库 ＋ `total`/`items` 同口径 ＋ 标已读**不**改写 `conversation.updated_at`） | ✅ **PASS** |
| 6 | **事务提交和回滚** | `MessageSemanticEquivalenceTest` 的「分片后仍是单一事务，中途失败必须全部回滚」—— **用真主键冲突**注入失败（**不是 Mockito 桩**）⇒ 断言 `COUNT(*) == 0`；且 `MessageAtomicService` 的真 `@Transactional` 代理靠**容器取 bean**（不 `new`） | ✅ **PASS** |
| 7 | **Kafka 消息内容** | **n/a** —— B 侧 `service/message/**` 对 MQ 的引用数为 **0**（无 `org.apache.kafka` / `springframework.kafka` 依赖）⇒ 两侧一致 | ✅ **PASS**（两侧都无） |
| 8 | **消息发送时机** | **n/a**（无 MQ / 无定时任务 ⇒ 无「消息发送时机」可对） | ✅ **PASS**（n/a） |
| 9 | **幂等和重复请求** | `uk_conversation_pair` 唯一索引（V4）⇒ 重复插入**真冲突**；`MessageSemanticEquivalenceTest` 的批量分片用例用**真主键冲突**证明「冲突 ⇒ 整批回滚」⇒ **幂等面由唯一约束承担** | 🟡 **PARTIAL** —— 约束面已验；**未做「同一请求重放两次」的行为级对拍** |
| 10 | **JWT** | claim 名 `"userId"`；**头缺失 / 验签失败 ⇒ 401 + 空 body**；**claim 缺失 ⇒ 5004 + HTTP 200**；admin 路径豁免 `/message/v1/admin/**`；`MessageApiProperties` 的 **fail-closed 守卫**（两段密钥缺失 ⇒ 启动失败）真在运行期生效 | ✅ **PASS** |
| 11 | **Redis 状态** | **n/a** —— 本域**不碰 Redis**（B 侧同样不碰：共享层的 `GoStyleRequestDeadline` 在本域**无消费点**） | ✅ **PASS**（两侧都无） |
| 12 | **定时任务和生命周期** | 本域**无定时任务 / 无 MQ 监听**（B 侧全域 grep = 0）⇒ 生命周期面 = **启动**（`@PostConstruct` × 1 ＋ Nacos 注册）＋ **关闭**（`@PreDestroy` × 2：观测层线程池回收 ＋ Nacos 反注册）⇒ 全部**运行期**断言 | ✅ **PASS** |

### 11.2 协议 §九「对拍必须覆盖的 11 类场景」—— 逐项判定

| # | 场景 | 本域覆盖情况 | 状态 |
|---|---|---|---|
| 1 | **正常路径** | 13 端点全部注册；领域层 9 用例；批量落库 6000 条；会话消息读取 | ✅ |
| 2 | **边界输入** | `limit/offset` 传反 · `5204` pending · `5206` 首条 50 码点 · `5203` 不存在 · 全部已读 | ✅ |
| 3 | **空值和缺省值** | `{"offset":null}` ⇒ 零值（补强 4）· 缺席 = 零值（`offset`/`limit`/`unread_only`）· 显式 `null` 进引用类型 | ✅ |
| 4 | **非法输入** | 字符串→整数 · 字符串→布尔 · 数字→布尔 · 数字→String（四组，全部有断言） | ✅ |
| 5 | **类型不匹配** | 同上 4 组（`MismatchedInputException` / `InvalidFormatException` 逐类区分） | ✅ |
| 6 | **重复请求** | `uk_conversation_pair` 真唯一索引 ＋ 批量分片的**真主键冲突** ⇒ 冲突 ⇒ 整批回滚 | 🟡 约束面已验，**行为级重放未做** |
| 7 | **并发请求** | `MessageAtomicService` 的悲观锁 ＋ `uk_conversation_pair` 承担并发去重 | ❌ **未做**（协议允许标 `NOT_VERIFIED`，但**必须说明缺什么** ⇒ 见 §11.4） |
| 8 | **外部依赖失败** | `count_unread` 抛异常 ⇒ `observeDbError("conversation","count_unread")` ＋ **异常原样上抛**（`MessageListConversationsParityTest`） | ✅ |
| 9 | **超时** | 🔴 **本轮新修** —— `observability.http-timeout: 3s` ＋ `rpc-timeout: 2s` **真绑上**（运行期断言 ＋ 消费点在链里）⇒ **超时预算从「不存在」变成「存在」** | ✅（配置面）🟡（**真实超时触发未做**） |
| 10 | **部分成功** | 批量分片的中途失败 ⇒ **一条不留**（不是留前 N 条） | ✅ |
| 11 | **恢复和清理** | Nacos 注册/反注册 ＋ 观测层线程池 `@PreDestroy` 回收 ＋ 测试侧双向清表 | ✅ |

### 11.3 本轮「B 侧期望值移植」的对拍资产 —— **6 个类 · 35 例**

| # | B 侧源文件（B 出处） | C 侧落点 | 例数 | 守的是什么（**期望值逐字沿用 B 侧**） |
|---|---|---|---|---|
| 1 | `message-rpc/.../service/MessageRpcSupportTrimPreviewTest.java`（226 行） | `service/MessageRpcSupportTrimPreviewTest.java` | **11** | 预览文本裁剪口径 |
| 2 | `message-rpc/.../service/MessageRpcSupportTimeZoneTest.java`（216 行） | `service/MessageRpcSupportTimeZoneTest.java` | **7** | `DRIFT-MSG-V-020` 墙钟→epoch 换算与时区无关 |
| 3 | `message-rpc/.../MessageSemanticEquivalenceTest.java`（503 行） | `domain/service/MessageSemanticEquivalenceTest.java`（**643 行**） | **7** | `DRIFT-MSG-V-001/-002/-004/-005/-006/-007` 六条语义漂移 |
| 4 | `message-rpc/.../service/MessageListConversationsParityTest.java`（208 行） | `service/MessageListConversationsParityTest.java`（234 行） | **4** | `DRIFT-MSG-V-018` 判定顺序短路 ＋ 埋点 ＋ 5005 |
| 5 | `message-rpc/.../service/MessageMarkNotificationReadTest.java`（153 行） | `service/MessageMarkNotificationReadTest.java`（178 行） | **4** | UPDATE 的 WHERE/SET 口径 ＋ 受影响行数 ＋ 实体字段名 |
| 6 | `message-rpc/.../MessageRpcTimeColumnParityTest.java`（175 行） | `support/MessageTimeColumnParityTest.java`（221 行） | **2** | `DRIFT-MSG-V-020` 8 个时间列的类型/可空性 ＋ 瞬时往返 |
| | | **合计** | **35** | |

> **机械照抄的偏差共 6 类，全部是「B 侧形态在 C 侧不存在」导致的必然改写**：
> ① B 侧 `MessageDomainService` 的 9 参构造器含 `LoggerFactoryBean logger`（**只赋值从不读的死字段**）⇒ C 侧 8 参（D16，已删）；
> ② `MessageMetrics` → `MessageRpcMetrics`；③ `MessageErrorCode` → `ErrCodes`；
> ④ B 侧自带容器 / `RealContainerPort` / `GrpcTestPort` ⇒ C 侧用共享单例 `TestPostgres`；
> ⑤ B 侧 `@EnabledIfDockerAvailable` **必须删**（守门规则 ③）；⑥ 包压平（D7）的 import 改写。
> ⇒ **断言值与 `@DisplayName` 逐字未改**（已用 `diff` 机械校验：7 条 DisplayName 集合 diff 为空）。

### 11.4 ⚠️ **无法运行验证的部分**（协议 §九：明确说明缺什么环境 ＋ 保留静态证据 ＋ 标记状态）

| # | 项 | **缺什么** | 保留的静态证据 | 状态 |
|---|---|---|---|---|
| N-1 | **B 侧活体对拍**（真正「相同输入比较 B 与 C」） | B 侧是 **Boot 4 + go-zero 服务链**（`zrpc` / `rest` / etcd），起真环境需 **etcd + Nacos + PG + 两条 B 侧服务链**；本轮**未在 B 侧启动 message 域** | 71 个源文件逐个对照 ＋ 核心层主体 diff（102/16/12 行全部可解释）＋ **35 例 B 侧期望值已移植并在 C 侧跑绿** | **NOT_VERIFIED**（协议允许：说明缺环境 ＋ 保留静态证据 ＋ **不得用静态相似度替代** ⇒ 本报告**没有**用它冒充对拍） |
| N-2 | **HTTP 响应头字节形状**（逐端点） | 需要一条**逐端点**的真 Tomcat 契约测试（本域已有真 Tomcat 基础设施，但**未逐端点断言** `Content-Type` / `nosniff` / 状态行） | §三·3.1 的 13 端点运行期注册表 ＋ 共享层 `RouterErrorResponses` 的 404 body 逐字节复刻 | **NOT_VERIFIED** |
| N-3 | **gRPC 端到端活体对拍** | 需要真 gRPC 客户端 → 真服务端（本域 gRPC server 真启动了，但**无活体客户端调用**） | 9 个方法 ＋ proto 逐字节一致 ＋ `@GrpcService` 真注册 ＋ gRPC server 真监听 | **NOT_VERIFIED** |
| N-4 | **并发请求对拍** | 需要并发压测装置（多线程打同一会话） | `uk_conversation_pair` 唯一索引（V4）＋ `MessageAtomicService` 的事务边界 ＋ 悲观锁 | **NOT_VERIFIED** |
| N-5 | **真实超时触发**（3s / 2s） | 需要一个**故意慢**的处理路径（>3s）来观察 503 ＋ `Request Timeout` | 🔴 **配置面已验**（运行期断言两个 timeout 真绑上 ＋ 消费点在链里）＋ 共享层 `HttpMiddleware` 的 503 分支实现 | **NOT_VERIFIED**（配置 ✅ / 触发 ❌） |
| N-6 | **变异证据**（证「断言不是假绿」） | 变异必然要改 `src/main/**`（本轮改动 `src/main/**` 的权限在本域内，但**逐条变异 + 还原 35+ 例**的代价需单独授权） | B 侧自带的变异记录（`MessageSemanticEquivalenceTest` javadoc `:57-60` 记了 5 条各自变红）已**原样保留**进 C 侧类 javadoc | **NOT_VERIFIED**（B 侧侧已完成 ⇒ 证据链在出处，不在 C 侧） |
| N-7 | **跨域 gRPC 链路** | 需真 Nacos ＋ 真 `user-rpc` / `admin-rpc` 服务同跑 ⇒ **缺陷侧已修**（admin 注册名回到 B 侧逐字值 `admin-rpc` · §八·8.2 的 U-1 已闭合）⇒ 剩下的是**三方活体验证**这一步 | §六·6-4 ＋ §八·8.2 的 U-1（已闭合） | **NOT_VERIFIED**（原判 **BLOCKED** ⇒ 阻塞源已消除，只差活体验） |

> **本阶段结论**：**🟡 PARTIAL — 不可判 PASS**（协议 §十：「`NOT_VERIFIED` 不能计入 `PASS`」）。
> **已闭合**：12 项优先行为中的 **7 项 PASS** ＋ **3 项 PARTIAL** ＋ **2 项 n/a（两侧都无）**；
> 11 类对拍场景中的 **8 类已覆盖**；**35 例 B 侧期望值移植**全部跑绿。
> **未闭合**：**7 项**（N-1~N-7），其中 **N-7 为外部条件阻塞**（BLOCKED），其余 6 项为 **NOT_VERIFIED**。
> ⇒ ⇒ **「C 是否具备可靠的行为对拍条件」＝ C 侧已具备（103 例真触发 ＋ 独立账本 ＋ 真 PG ＋ 真 Tomcat），
> 缺口在 B 侧一侧**（需在 B 侧起同条件探针，见 N-1）**。

---

## 十一、本轮未改动 / 刻意未做的事（**诚实边界**）

| 项 | 说明 |
|---|---|
| **未 commit / 未 push** | 本轮全部改动留在工作树内（`git diff main..HEAD` 为空）。**由用户决定何时提交**。 |
| **未碰 `tools/` 的 ROOT 硬编码** | `check-mapping-coverage.py` 与 `gen-file-tree.py` 的 `ROOT` 期间被指向本工作树 ⇒ **提交前须 `git checkout -- tools/` 还原**（否则会量到别处的树 ⇒ 假绿）。<br>⚠️ **本轮实测到一个坑**：`sync-mapping-index.py` **读映射表用「相对 `__file__`」的 ROOT**、**枚举 C 侧文件却用 `check-mapping-coverage.py` 里「硬编码」的 ROOT** ⇒ ⇒ 两个 ROOT **可以指不同的树** ⇒ ⇒ **还原时序错了，它会把「另一棵树」的数字写进 `_INDEX.md`**（本轮真踩到：一度把 message 的 C 列写成 `52/52`，而工作树实测是 `53/53`）。<br>⇒ 📌 **正确姿势**：① `sed` 把 `check-mapping-coverage.py` 的 ROOT 指向工作树 → ② 跑 `sync-mapping-index.py --apply` → ③ **再**把 ROOT 还原 ⇒ ④ 用 `check-mapping-coverage.py` 复验（**守门脚本把这次写歪抓出来了 ⇒ 守门有效**）。<br>⇒ 📌 `tools/check-mapping-coverage.py` 保留 **1 行**实质修正（`MessageErrorCode（enum，21常量+SUCCESS）` → `20常量`），**其余已还原**（`git diff tools/` 只剩这 1 行）。 |
| **未写 `docs/DRIFT/_INDEX.md`** | AGENTS.md §五.3 规则 3：只写自己域 ⇒ `_INDEX.md` 归主会话。 |
| **未写 `docs/Intentional_Deviations/_INDEX.md` / `docs/Pitfalls/_INDEX.md`** | 同上。 |
| **未碰 `common/`** | 共享层写权不在本 lane ⇒ §六·6-5 的 `JsonbMapTypeHandler` 上收留给收口任务。 |
| **未改 B 侧** | B 侧仓只读（AGENTS.md §五.4）。 |
| **未用 `@Disabled` / `@EnabledIfDockerAvailable` 掩盖任何失败** | §五.8 守门硬规则 ②③（并已作为移植规则写进三个 worker 的任务书）。 |
| **未回溯第三方（Go）** | D16：B 侧是唯一 oracle；B 侧自身的差异一律**继承并登记**。 |
| **未做 C 侧变异实验** | 变异必然要改 `src/main/**` ⇒ 需单独授权（改后必须还原）。B 侧自带的变异记录（5 条各自变红）已**原样保留**进 C 侧类 javadoc 作为证据链出处（§十·N-6）。 |
| **未在 B 侧起活体探针** | 需 B 侧 Boot 4 + go-zero 服务链（etcd/Nacos/PG）⇒ 本轮未投入该 infra ⇒ 按协议标记 `NOT_VERIFIED`（§十·N-1），**没有**用静态相似度冒充对拍。 |
