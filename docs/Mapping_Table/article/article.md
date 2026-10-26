# Mapping_Table/article/article.md — article 域三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标形态）三棵树的 **article 域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> **A 列**：`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/article/`（真源，冻结，只读）
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/article/`（Go 部署形态版；api / rpc 分离）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/service/article/`（本仓；api / rpc 合并为 `article-service` 单 app，D1）
>
> **字段映射**：A 文件 → B 文件 → C 文件 + 映射类型（`1:1` / `1:N` / `N:1` / `C 新增`）

---

## ⚠️ 三条读表纪律（本表的前提，不可省略）

1. ⚠️ **B 列不是 Go 的替身** —— RAG 的 article 域 api 与 rpc **首次对照即判 `NOT EQUIVALENT`**（见 `RAG/docs/DRIFT/article/`）。
   **一切行为判断以 A 列 Go 源码为准**；本表里所有「值」都从 A 列源码逐条抄出，不从 B 列转抄。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」**，**不下「已对齐 / 未对齐」结论**。
   行为对齐状态看 `RAG/docs/DRIFT/article/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 Java 文件目前只有 package + 空类型 + TODO javadoc。
   **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

> ⚠️ **另一条口径声明**：任务简报中「指标无命名空间，`article_total` 等是裸名」这一条，**与 A 列 Go 源码不符** ——
> `metrics/metrics.go:6` 明确写着 `Namespace = "article_service"`。
> 本表按 **A 列源码**记录完整名（`article_service_article_total` 等），并在 §7 登记该文档缺陷。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| A (Go 真理之源) | B (RAG, Go 形态版) | C (RAG-V1, Spring 目标) | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/article/api/`（20 个非测试 `.go`） | `service/article/article-api/`（41 个 `.java`） | `service/article/` 的 `service/article/src/main/java/com/rag/article/advice + service/article/src/main/java/com/rag/article/config + service/article/src/main/java/com/rag/article/controller + service/article/src/main/java/com/rag/article/dto + service/article/src/main/java/com/rag/article/interceptor + service/article/src/main/java/com/rag/article/service` 包（21 个 `.java`） | 1:N | D1：api 进程并入单 app，HTTP 边界改由 `api/` 包承载 |
| `service/article/rpc/`（23 个非测试 `.go` + 2 个 `pb/` 生成物） | `service/article/article-rpc/`（39 个 `.java`） | `service/article/` 的 `service/article/src/main/java/com/rag/article/bootstrap + service/article/src/main/java/com/rag/article/client + service/article/src/main/java/com/rag/article/config + service/article/src/main/java/com/rag/article/domain/entity + service/article/src/main/java/com/rag/article/domain/service + service/article/src/main/java/com/rag/article/exception + service/article/src/main/java/com/rag/article/grpc + service/article/src/main/java/com/rag/article/mapper + service/article/src/main/java/com/rag/article/metrics` 包（25 个 `.java`） | 1:N | D1：rpc 进程并入单 app |
| `service/article/common/errmsg/`（1 个 `.go`） | `service/article/article-common/`（1 个 `.java`） | `service/article/src/main/java/com/rag/article/contract/`（4 个 `.java`） | 1:N | C 把「错误契约」并入契约模块；Go 侧 `article/common` 只有 errmsg 一个包 |
| `proto/article.proto`（仓库根，不在 `service/article/` 下） | `service/article/article-proto/src/main/proto/article.proto` | `proto/src/main/proto/article.proto` | 1:1 | L1 契约，逐条一致；仅 option 块不同（见 §6） |
| — | `service/article/article-api/etc/` + `article-rpc/etc/`（yaml） | `service/article/src/main/resources/application.yml` | N:1 | 两份 yaml 合并为一份（D1） |
| — | — | `service/article/src/main/java/com/rag/article/contract/`（proto + 错误契约独立模块） | C 新增 | 契约与实现分离：**proto 是 L1 跨服务契约，塞进 app 模块会让消费方依赖整个应用** |

### 0.1 进程拓扑对照

| 项 | A (Go) | B (RAG) | C (RAG-V1) |
|---|---|---|---|
| 进程数 | 2（`article-api` :8889 + `article-rpc` :9001） | 2（同名同端口语义） | **1**（`article-service`，HTTP `ARTICLE_HTTP_PORT` + gRPC `ARTICLE_GRPC_PORT`） |
| 注册中心 | Etcd（`article.rpc`） | Nacos | Nacos |
| 同域调用 | api → rpc 走 gRPC（zrpc） | api → rpc 走 gRPC（devh `@GrpcClient`） | **域内直连**（D2）：`ArticleApiService` → `ArticleDomainService` |

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `article/api/`（`ArticleApiApplication`） | `com.rag.article`（`ArticleServiceApplication.java`） | 入口类合并（D1）|
| `article/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `ArticleApiResponseAdvice`）|
| `article/api/client/` | `client/` | 直接压平 |
| `article/api/config/` | `config/` | 与 rpc 侧合并 |
| `article/api/controller/` | `controller/` | 直接压平 |
| `article/api/dto/` | `dto/` | 直接压平（12 个同名类全落此）|
| `article/api/form/`（`GoFormValues`） | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` | **上收 `common/`**（D3）|
| `article/api/http/`（4 类） | `common/src/main/java/com/rag/common/web/`（4 个同名类）| **上收 `common/`**（D3）|
| `article/api/interceptor/` | `interceptor/` | 直接压平 |
| `article/api/service/` | `service/` | 与 rpc 侧合并 |
| `article/common/errmsg/` | `contract/` | 错误码 + 事件 DTO（`ErrCodes` / `ErrMsg` / `ArticleSyncEvent` / `CommunityArticleEvent`）|
| `article/rpc/bootstrap/` | `bootstrap/` | 直接压平 |
| `article/rpc/client/` | `client/` | 与 api 侧合并 |
| `article/rpc/config/` | `config/` | 合并 |
| `article/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `article/rpc/exception/` | `exception/` | 直接压平 |
| `article/rpc/mapper/` | `mapper/` | 直接压平 |
| `article/rpc/metrics/` | `metrics/` | 与 api 侧合并 |
| `article/rpc/mq/`（4 类） | **—（C 侧无 `messaging/` 包）** | ⚠️ **消费者 / 发件箱中继无落点**（见 §1.5）|
| `article/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `article/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `ArticleServiceGrpcImpl` → `ArticleGrpcService` |
| `article-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.article`）| **集中化**（D7）|

## 1. 包结构对照

> 表头固定：`| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |`
> B 列路径写到**文件名**（`…` 代表包路径），便于 `grep` 定位。

### 1.1 api 侧（HTTP 边界）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/article.go`（REST server + CORS + observability 装配） | `article-api/…/ArticleApiApplication.java` | `service/article/src/main/java/com/rag/article/ArticleServiceApplication.java` | N:1 | **D1**：与 `rpc/article.go` 合并为唯一入口（`@SpringBootApplication` + `@EnableDiscoveryClient`）；C 侧不再有第二个 Application |
| `api/internal/config/config.go`（`RestConf` + `Auth` + 3 个 `RpcClientConf`） | `article-api/…/config/ArticleApiProperties.java` | `service/article/src/main/java/com/rag/article/config/ArticleApiProperties.java` | 1:1 | 静态字段 → `@ConfigurationProperties`；`SecurityRpcConf` 两侧均不迁移（Go 侧死配置，见 §1.4-④） |
| `api/internal/handler/routes.go`（3 个 `AddRoutes` 块：JWT 组 / public 组 / upload 组） | `article-api/…/config/WebMvcConfig.java` + `article-api/…/controller/ArticleController.java` + `article-api/…/interceptor/JwtAuthInterceptor.java` | `service/article/src/main/java/com/rag/article/config/WebMvcConfig.java` + `service/article/src/main/java/com/rag/article/controller/ArticleController.java` + `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java` | 1:N | **代码注册路由 → 注解式路由**；`/v1` 前缀、4 组 method、public 段、10 MiB 上限、70s 超时**逐字保留** |
| `api/internal/handler/article/create_article_handler.go` | `article-api/…/controller/ArticleController.java`（`@PostMapping("/article")`） | `service/article/src/main/java/com/rag/article/controller/ArticleController.java` | 1:N | 7 个薄 handler 同质（parse → logic → 信封）⇒ 合 1 个 Controller |
| `api/internal/handler/article/update_article_handler.go` | 同上（`@PutMapping("/article/{id}")`） | 同上 | 1:N | |
| `api/internal/handler/article/delete_article_handler.go` | 同上（`@DeleteMapping("/article/{id}")`） | 同上 | 1:N | |
| `api/internal/handler/article/get_author_article_handler.go` | 同上（`@GetMapping("/me/article/{id}")`） | 同上 | 1:N | |
| `api/internal/handler/article/get_article_handler.go` | 同上（`@GetMapping("/article/{id}")`） | 同上 | 1:N | |
| `api/internal/handler/article/list_articles_handler.go` | 同上（`@GetMapping("/articles")`） | 同上 | 1:N | |
| `api/internal/handler/article/upload_image_handler.go` | 同上（`@PostMapping("/upload")` + `@RequestParam("image") MultipartFile`） | 同上 | 1:N | ⚠️ **全域唯一不走信封的端点**；multipart 字段名固定 `image` |
| `api/internal/logic/article/create_article_logic.go`（70s 超时 + gRPC code 映射 + status message 取 id） | `article-api/…/service/ArticleCreateService.java` | `service/article/src/main/java/com/rag/article/service/ArticleApiService.java` | N:1 | D2 后不再有「gRPC code → 业务码」中间层 |
| `api/internal/logic/article/get_article_logic.go`（`PublicOnly=true` + 非 PUBLISHED → 1002） | `article-api/…/service/ArticleGetService.java` | 同上 | N:1 | |
| `api/internal/logic/article/get_author_article_logic.go`（`RequesterId` + 作者双检 → 1006） | `article-api/…/service/ArticleGetAuthorService.java` | 同上 | N:1 | **保留双检**（Go 侧注释明示的向后兼容双检） |
| `api/internal/logic/article/list_articles_logic.go`（任一非 PUBLISHED → 整包 5001） | `article-api/…/service/ArticleListService.java` | 同上 | N:1 | **整包拒绝**，不是静默跳过该 item |
| `api/internal/logic/article/update_article_logic.go`（两阶段 + optional 指针语义） | `article-api/…/service/ArticleUpdateService.java` | 同上 | N:1 | `Status != 0` 才设；`Title`/`Brief`/… 非空才设 |
| `api/internal/logic/article/delete_article_logic.go`（两阶段，作者不匹配不发 RPC） | `article-api/…/service/ArticleDeleteService.java` | 同上 | N:1 | |
| `api/internal/logic/article/upload_image_logic.go`（multipart 读取 + 转发，**裸响应**） | `article-api/…/service/ArticleUploadService.java` | 同上 | N:1 | 裸响应契约需能单独表达 |
| `api/internal/logic/article/helper.go`（uid 提取 + 作者名补全 + 防御性强转 + `cloneStringMap`） | `article-api/…/service/ArticleApiSupport.java` + `article-api/…/service/ArticleAuthorResolver.java` | `service/article/src/main/java/com/rag/article/service/ArticleApiSupport.java` | 1:N | C 侧 `ArticleAuthorResolver`（作者名补全 + **吞错**策略）**无独立载体** ⇒ 缺口 G-2 |
| `api/internal/svc/service_context.go`（`ArticleRpc` 客户端） | `article-api/…/client/ArticleRpcClient.java` | （域内直连）`service/article/src/main/java/com/rag/article/service/ArticleApiService.java` | N:1 | **D2**：同域不再走网络；C 侧无 client 文件 |
| `api/internal/svc/service_context.go`（`UserRpc` 客户端 → 取作者名） | `article-api/…/client/UserRpcClient.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-2**：跨域调用仍在（D6），但 C 侧 article 域**没有 `UserRpcClient`** |
| `api/internal/types/types.go`（12 个 struct） | `article-api/…/dto/`（13 个文件：`Article.java` / `CreateArticleReq.java` / `CreateArticleResp.java` / `DeleteArticleReq.java` / `DeleteArticleResp.java` / `GetArticleReq.java` / `GetArticleResp.java` / `ListArticlesReq.java` / `ListArticlesResp.java` / `UpdateArticleReq.java` / `UpdateArticleResp.java` / `UploadImageResp.java` / `GoStyleStringFields.java`） | `service/article/src/main/java/com/rag/article/dto`（13 个文件：`Article.java` / `CreateArticleReq.java` / `CreateArticleResp.java` / `DeleteArticleReq.java` / `DeleteArticleResp.java` / `GetArticleReq.java` / `GetArticleResp.java` / `ListArticlesReq.java` / `ListArticlesResp.java` / `UpdateArticleReq.java` / `UpdateArticleResp.java` / `UploadImageReq.java` / `UploadImageResp.java`） | 1:N | 1 个 Go 文件 → 13 个 DTO 文件；C 侧全部改 **immutable record**（D5）；`UploadImageReq.java` 是 C 新增位置（B 侧无，因 Go 侧是空 struct） |
| （Go 的 `httpx.OkJson` + `response.Response` 信封，内联在 7 个 handler 里） | `article-api/…/advice/GlobalResponseAdvice.java` | `service/article/src/main/java/com/rag/article/advice/ArticleApiResponseAdvice.java` | 1:1 | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不能用 `ProblemDetail` 替换**；**必须排除 `/upload`** |
| （Go 的 `httpx.Error` + logic 里的 gRPC code switch） | `article-api/…/advice/GlobalExceptionHandler.java` | `service/article/src/main/java/com/rag/article/advice/ArticleApiExceptionHandler.java` | 1:1 | 框架级 400 / 405 / 415 → `text/plain`（Go 侧 `httpx.Error` 形状） |
| `api/internal/handler/routes.go` 的 `rest.WithJwt(...)` 绑定 | `article-api/…/interceptor/JwtAuthInterceptor.java` | `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java` | 1:1 | 验签 + Redis blacklist + public 前缀放行；**需独立 Bean**（密钥 / public 路径与 users 域不同） |

### 1.2 rpc 侧（领域 / 服务端能力层）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/article.go`（gRPC server + 2 个 Kafka consumer + outbox relay 的 `ServiceGroup` 编排） | `article-rpc/…/ArticleRpcApplication.java` | `service/article/src/main/java/com/rag/article/ArticleServiceApplication.java` | N:1 | **D1**：与 `api/article.go` 合并；四件套「同启同停」交给 Spring 容器生命周期 |
| `rpc/internal/config/config.go`（PG / Kafka×4 / MinIO / Security / Message / Outbox / HotEvent） | `article-rpc/…/config/ArticleRpcProperties.java` | `service/article/src/main/java/com/rag/article/config/ArticleRpcProperties.java` | 1:1 | `@ConfigurationProperties` 聚合；字段逐条对应 |
| `rpc/internal/config/config.go`（4 个 `kq.KqConf` 各自的 `Brokers`） | `article-rpc/…/config/ArticleKafkaChannelValidator.java` | `service/article/src/main/java/com/rag/article/config/ArticleKafkaChannelValidator.java` | 1:1 | 🔴 校验 4 通道 brokers 与全局 `spring.kafka.bootstrap-servers` 一致，不一致**启动失败**（Go 侧改某通道 brokers 会切 broker） |
| `rpc/internal/model/article.go`（`gorm:"autoUpdateTime"` 的 `updated_at` 自动推进） | `article-rpc/…/config/ArticleMetaObjectHandler.java` | `service/article/src/main/java/com/rag/article/config/ArticleMetaObjectHandler.java` | 1:1 | MyBatis-Plus `MetaObjectHandler` 复刻 GORM 的 `autoUpdateTime` 消费点 |
| `rpc/internal/svc/service_context.go`（snowflake 初始化） | `article-rpc/…/bootstrap/SnowflakeBootstrap.java` | `service/article/src/main/java/com/rag/article/bootstrap/SnowflakeBootstrap.java` | 1:1 | 位宽/epoch 与 Go 侧一致（`common/snowflake`） |
| `rpc/internal/svc/service_context.go`（MinIO bucket 幂等 bootstrap + `public-read` 策略） | `article-rpc/…/bootstrap/MinioBucketBootstrap.java` | `service/article/src/main/java/com/rag/article/bootstrap/MinioBucketBootstrap.java` | 1:1 | 幂等语义保留；`MakeBucket` 失败不 panic，只 warn |
| `rpc/article.go`（`zrpc.MustNewServer` 的服务注册） | `article-rpc/…/bootstrap/NacosRegistration.java` | `service/article/src/main/java/com/rag/article/bootstrap/NacosRegistration.java` | 1:1 | 显式注册，不依赖自动注册 |
| `rpc/internal/model/db.go`（`article_revision_immutable` 触发器启动门禁） | `article-rpc/…/bootstrap/ArticleSchemaGate.java` | `service/article/src/main/java/com/rag/article/bootstrap/ArticleSchemaGate.java` | 1:1 | 🔴 缺触发器必须 **fail-fast**（静默降级会让「不可变 revision」变成可写） |
| `rpc/internal/svc/service_context.go`（`MessageRpc` 客户端） | `article-rpc/…/client/MessageRpcClient.java` | `service/article/src/main/java/com/rag/article/client/MessageRpcClient.java` | 1:1 | **唯一的跨域 gRPC 依赖**（D6） |
| `rpc/internal/svc/service_context.go`（`MinioClient`） | `article-rpc/…/client/ArticleObjectStorageClient.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-3**：C 侧只有 `MinioBucketBootstrap`，**没有对象存储客户端** ⇒ MinIO put/get 无载体 |
| `rpc/internal/svc/service_context.go`（`SecurityRpc` = `SanitizeContent`） | `article-rpc/…/client/SecurityRpcClient.java`（+ `ContentSecurityPort.java` / `ContentSecurityAutoConfiguration.java` / `UnavailableContentSecurityClient.java`） | **无对应文件** | N:1 | ⚠️ **缺口 G-4**：C 侧无内容安全客户端 |
| `rpc/internal/svc/service_context.go`（`ImageSecurityRpc` = `DetectImageAd`） | `article-rpc/…/client/ImageSecurityRpcClient.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-4**（同上） |
| `rpc/internal/server/article_service_server.go`（6 个 gRPC 方法 → logic） | `article-rpc/…/service/impl/ArticleServiceGrpcImpl.java` | `service/article/src/main/java/com/rag/article/grpc/ArticleGrpcService.java` | 1:1 | `@GrpcService`；**proto 方法签名是 L1，一个字节都不改** |
| `rpc/internal/logic/create_article_logic.go`（snowflake + MinIO put + insert REVIEWING + Kafka push） | `article-rpc/…/service/ArticleDomainService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` | N:1 | 6 个 logic 合 1 |
| `rpc/internal/logic/update_article_logic.go`（三分支 + sha256 对象名 + `FOR UPDATE`） | `article-rpc/…/service/ArticleDomainService.java` + `article-rpc/…/service/ArticleAtomicService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` + `service/article/src/main/java/com/rag/article/domain/service/ArticleAtomicService.java` | N:1 | **多步事务必须独立 Bean**（`@Transactional` self-invocation 会失效） |
| `rpc/internal/logic/delete_article_logic.go`（retract + outbox + 软删，单事务） | 同上（`ArticleAtomicService`） | 同上 | N:1 | ⚠️ **不删 MinIO 对象**（保留期未定） |
| `rpc/internal/logic/get_article_logic.go`（3 分支：作者 / 公开 / `RequesterId`+`IncrView` 非法组合） | `article-rpc/…/service/ArticleDomainService.java` + `article-rpc/…/service/ArticlePublicDomainService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` + `service/article/src/main/java/com/rag/article/domain/service/ArticlePublicDomainService.java` | N:1 | 作者视角 vs 公开视角是**不同持久化语义**（读冻结 revision vs 读当前草稿） |
| `rpc/internal/logic/list_articles_logic.go`（`PublicOnly` 分支） | 同上（`ArticlePublicDomainService`） | 同上 | N:1 | |
| `rpc/internal/logic/upload_file_logic.go`（snowflake + mime 嗅探 + MinIO put） | `article-rpc/…/service/ArticleDomainService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` | N:1 | |
| `rpc/internal/model/article.go`（`Article` GORM 模型） | `article-rpc/…/domain/ArticleEntity.java` | `service/article/src/main/java/com/rag/article/domain/entity/ArticleEntity.java` | 1:1 | `@TableName("article")`；列类型 / 可空性 / 索引逐列对拍 |
| `rpc/internal/model/article.go`（仓库方法 `Insert`/`FindOne`/`Update`/`Delete`/`IncrViewCount`/`List`） | `article-rpc/…/mapper/ArticleMapper.java` | `service/article/src/main/java/com/rag/article/mapper/ArticleMapper.java` | 1:1 | `BaseMapper` + `@Select` / `@Update` |
| `rpc/internal/model/article.go`（`StringArray` / `JSONMap` 的 `Value`/`Scan`） | `article-rpc/…/mapper/JsonbTypeHandler.java` | （共享层）`common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | 1:N | Go struct tag 在 Java 无对应物 ⇒ C 侧归入 `common/src/main/java/com/rag/common/persistence/`（D3：跨域去重） |
| `rpc/internal/model/article_public.go`（复合公开谓词 + `ListPublic` 只读快照 + `IncrPublicViewCount`） | `article-rpc/…/mapper/ArticlePublicMapper.java` | `service/article/src/main/java/com/rag/article/mapper/ArticlePublicMapper.java` | 1:1 | **公开可见谓词是独立 SQL 资产**（含 legacy `status=2` 回退 + revision 标签过滤） |
| `rpc/internal/model/article_sync_outbox.go`（`ArticleSyncOutboxEvent` + 状态机 4 方法） | `article-rpc/…/domain/ArticleSyncOutboxEntity.java` + `article-rpc/…/mapper/ArticleSyncOutboxMapper.java` | `service/article/src/main/java/com/rag/article/domain/entity/ArticleSyncOutboxEntity.java` + `service/article/src/main/java/com/rag/article/mapper/ArticleSyncOutboxMapper.java` | 1:N | 1 个 Go 文件 → 2 个 Java 文件（实体 + Mapper）；`event_key` 唯一键保留 |
| `rpc/internal/model/community_revision.go`（`ArticleRevision` / `ArticlePublication` / `ArticleDomainOutbox`） | `article-rpc/…/domain/ArticleRevisionEntity.java` + `domain/ArticlePublicationEntity.java` + `domain/ArticleDomainOutboxEntity.java` | `service/article/src/main/java/com/rag/article/domain/entity/ArticleRevisionEntity.java` + `service/article/src/main/java/com/rag/article/domain/entity/ArticlePublicationEntity.java` + `service/article/src/main/java/com/rag/article/domain/entity/ArticleDomainOutboxEntity.java` | 1:N | 三表持久化用途不同 ⇒ 三实体分开 |
| `rpc/internal/model/community_revision.go`（三个表的仓库方法） | `article-rpc/…/mapper/ArticleRevisionMapper.java` + `mapper/ArticlePublicationMapper.java` + `mapper/ArticleDomainOutboxMapper.java` | `service/article/src/main/java/com/rag/article/mapper/ArticleRevisionMapper.java` + `service/article/src/main/java/com/rag/article/mapper/ArticlePublicationMapper.java` + `service/article/src/main/java/com/rag/article/mapper/ArticleDomainOutboxMapper.java` | 1:N | `article_domain_outbox` 的 `UNIQUE(aggregate_id, aggregate_version)` 是事件顺序保证，**不可省** |
| `rpc/internal/model/community_revision.go`（`WithArticleTx` 的 `FOR UPDATE`） | `article-rpc/…/service/ArticleAtomicService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleAtomicService.java` | 1:1 | GORM 事务闭包 → `@Transactional` + Mapper `last("FOR UPDATE")` |
| `rpc/internal/model/db.go`（连接池 + `AutoMigrate`） | `article-rpc/src/main/resources/db/migration/V8__create_article.sql` / `V9__create_article_sync_outbox.sql` / `V10__create_article_community_revision.sql` | `service/article/src/main/resources/db/migration/`（**仅 `.gitkeep`，DDL 未落地**） | 1:N | ⚠️ **缺口 G-5**：C 侧迁移目录为空 |
| `rpc/internal/mqs/article_consumer.go`（审核消费者：四元组校验 + `SanitizeContent` + `DetectImageAd` + 幂等 + `FOR UPDATE` 写 outbox） | `article-rpc/…/mq/ArticleReviewConsumer.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-1**：C 侧**没有 `mq/` 包** |
| `rpc/internal/mqs/article_sync_result_consumer.go`（同步结果消费者：revision + publication + domain outbox + 一次性通知） | `article-rpc/…/mq/ArticleSyncResultConsumer.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-1**（同上） |
| `rpc/internal/mqs/article_sync_outbox_sender.go`（outbox relay：1s ticker，batch=100，单条失败不中断批次） | `article-rpc/…/mq/ArticleSyncOutboxRelay.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-1**（同上） |
| `rpc/internal/mqs/community_article_event.go`（`RetractPublicationTx`） | `article-rpc/…/service/ArticleAtomicService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleAtomicService.java` | 1:1 | 事务载体（`FOR UPDATE` 取指针 + 写 retract 域事件，单事务） |
| `rpc/metrics/metrics.go`（6 个 Prometheus 指标） | `article-rpc/…/metrics/ArticleRpcMetrics.java` | `service/article/src/main/java/com/rag/article/metrics/ArticleRpcMetrics.java` | 1:1 | 指标**完整名**带 `article_service_` 前缀（Go `Namespace`，见 §6）；`article_status_total` 只有 `reviewing` 有调用点，**不许补全** |

### 1.3 契约模块（proto + 错误契约）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `common/errmsg/errmsg.go`（15 个 const + `codeMsg` 表 + `GetErrMsg` 回落） | `article-common/…/errmsg/ErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:N | C 拆「码值表」与「文案表」两个类；⚠️ **错误契约逐域隔离，不可跨域统一**（article 的 5004=文件上传失败 / 5005=文件删除失败 / 5006=文件下载失败，与 users 域同值不同义） |
| `rpc/internal/mqs/article_sync_types.go`（`ArticleSyncEvent` + `ArticleReviewMessage` + `ArticleSyncResult` + 11 个 `Ext*`/op/reason 常量） | `article-rpc/…/mq/ArticleSyncEventTypes.java` | `service/article/src/main/java/com/rag/article/contract/ArticleSyncEvent.java` | 1:N | ⚠️ C 侧只有 1 个类承载 ⇒ **`ArticleReviewMessage` / `ArticleSyncResult` 无载体**（缺口 G-6） |
| `rpc/internal/mqs/community_article_event.go`（community 信封 + `communityRevisionPayload`） | `article-rpc/…/mq/ArticleSyncEventTypes.java`（payload 部分）+ `service/common/…/communityfact/Wire.java`（信封部分） | `service/article/src/main/java/com/rag/article/contract/CommunityArticleEvent.java` + `common/src/main/java/com/rag/common/communityfact/Event.java` | 1:N | 信封（`event_id`/`event_type`/`schema_version`/`producer`/`aggregate_id`/`aggregate_version`/`operation_id`/`occurred_at`/`payload`）是 **L1**；payload 是 article 特有 ⇒ 域内契约模块 |
| `proto/article.proto`（仓库根） | `article-proto/src/main/proto/article.proto` | `proto/src/main/proto/article.proto` | 1:1 | **message 名 / 字段名 / 字段号 / 类型 / 枚举值逐条一致**；仅 option 块不同（删 `option go_package` / 加 `java_package` + `java_multiple_files`） |
| `rpc/pb/article.pb.go` + `rpc/pb/article_grpc.pb.go`（protoc 生成物） | `article-proto`（protoc 插件段） | 根级 `proto/`（protoc 插件段） | 1:1 | 生成物，非手写源码；⚠️ **「有 `.proto`」≠「生成了代码」**（插件段必须接上） |
| `rpc/articleservice/article_service.go`（客户端桩） | （无独立文件；devh `@GrpcClient` 直接用生成 stub） | （无独立文件；根级 `proto/` 生成 stub） | 1:1 | 两侧都不生成手写桩 |

### 1.4 A 侧无独立对应物的 Go 文件（原因）

| A Go 文件 | 原因 |
|---|---|
| `api/internal/svc/service_context.go` 的 `SecurityRpc` 客户端 | Go 侧 **api 零调用点**（只 wire 不调）⇒ 死配置。B、C 两侧均不迁移 |
| `rpc/internal/svc/service_context.go` 的 `HotEventPusher` | Go 侧 **零调用点**（只在 `service_context.go` 里 new 出来）⇒ 死配置。B、C 两侧均不迁移；topic `article-hot-events` 仅在 yaml 里出现 |
| `rpc/articleservice/article_service.go` | 生成物 + 客户端桩 ⇒ 两侧都改用 starter 的生成 stub，无独立文件 |
| `rpc/pb/article.pb.go` / `rpc/pb/article_grpc.pb.go` | protoc 生成物 ⇒ 归契约模块，无独立手写文件 |

### 1.5 C 侧职责缺口清单（A/B 有、C 无）

> ⚠️ 这些是**填实现前必须先解决**的位置，否则 C 侧会静默丢职责。

| # | 缺口 | A 列出处 | B 列载体 | 后果 |
|---|---|---|---|---|
| **G-1** | **无 `mq/` 包** —— 审核消费者 / 同步结果消费者 / outbox relay 三个消息入口无载体 | `rpc/internal/mqs/article_consumer.go` · `article_sync_result_consumer.go` · `article_sync_outbox_sender.go` | `mq/ArticleReviewConsumer.java` · `mq/ArticleSyncResultConsumer.java` · `mq/ArticleSyncOutboxRelay.java` | 审核链路、发布落地链路、outbox 投递链路**整条丢失** |
| **G-2** | **无 `UserRpcClient`** —— 作者名补全无跨域出口 | `api/internal/logic/article/helper.go` 的 `resolveAuthorName`；`api/internal/svc/service_context.go` 的 `UserRpc` | `article-api/…/client/UserRpcClient.java` + `service/ArticleAuthorResolver.java` | 文章列表 / 详情**没有 `author_name`**；且 C 侧 `ArticleAuthorResolver` 这个独立职责点也不存在 |
| **G-3** | **无对象存储客户端** —— MinIO put/get 无载体 | `rpc/internal/logic/upload_file_logic.go` · `create_article_logic.go` · `update_article_logic.go`；`rpc/internal/svc/service_context.go` 的 `MinioClient` | `article-rpc/…/client/ArticleObjectStorageClient.java` | 上传、正文存取、哈希对象名全部无载体（只剩 `MinioBucketBootstrap` 建桶） |
| **G-4** | **无内容安全 / 图片安全客户端** | `rpc/internal/mqs/article_consumer.go` 的 `SanitizeContent` + `DetectImageAd`；`rpc/internal/svc/service_context.go` 的 `SecurityRpc` / `ImageSecurityRpc` | `client/SecurityRpcClient.java` · `client/ImageSecurityRpcClient.java` | 审核四元组里的两项检查无处执行 |
| **G-5** | **DDL 未落地** —— `db/migration/` 只有 `.gitkeep` | `rpc/internal/model/article.sql` + `002_community_revision.sql` | Flyway `V8` / `V9` / `V10` | 五张表 + 不可变触发器 + CHECK 约束全部缺失；`ArticleSchemaGate` 会 **fail-fast 起不来** |
| **G-6** | **`ArticleReviewMessage` / `ArticleSyncResult` 无载体** | `rpc/internal/mqs/article_sync_types.go` | `mq/ArticleSyncEventTypes.java` | 审核消息与同步结果两个 wire 载荷在 C 侧无处安放 |

> 本节决策锁：**L-1 / L-2**（详见 §5）。

---

## 2. 持久层差异

| 维度 | A (Go, GORM) | B (RAG, MyBatis-Plus) | C (RAG-V1, MyBatis-Plus) | 备注 |
|---|---|---|---|---|
| ORM 注解 | GORM struct tag（`primaryKey` / `type:varchar(32)` / `index` / `default:0` / `autoCreateTime`） | `@TableName` + `@TableId(type=ASSIGN_ID)` + `@TableField` | 同 B | 表名显式声明 |
| 数据访问 | `ArticleRepo` 方法（`FindOne` / `List` / `IncrViewCount` …） | `Mapper extends BaseMapper<Entity>` + `@Select` / `@Update` | 同 B | 查询条件必须逐条保留 |
| 复合 SQL 谓词 | `model/article_public.go` 的 `publicArticlePredicate` | `ArticlePublicMapper` 的 `@Select` 原样 SQL | 同 B | 公开可见谓词 = 独立 SQL 资产 |
| jsonb 列 | `StringArray` / `JSONMap` 自实现 `Value`/`Scan` | `mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | **D3**：跨域去重后收进共享层 |
| Schema 演进 | `AutoMigrate(Article, ArticleSyncOutboxEvent)` + 手工 `article.sql` / `002_community_revision.sql` | Flyway `V8` / `V9` / `V10` + `ArticleSchemaGate` | `db/migration/` **空**（缺口 G-5） | ⚠️ C 侧尚未落地 |
| 不可变触发器 | `article_revision_immutable`（`reject_article_revision_mutation()`） | Flyway `V10` | 未落地（缺口 G-5） | 缺触发器 ⇒ 门禁 fail-fast |
| 事务边界 | `gorm.DB.Transaction` 闭包（`RunInTx` / `WithArticleTx`） | `ArticleAtomicService` 的 `@Transactional` | 同 B | ⚠️ **必须独立 Bean**，否则 self-invocation 使 `@Transactional` 失效 |
| 行级悲观锁 | `clause.Locking{Strength: "UPDATE"}` | Mapper `last("FOR UPDATE")` | 同 B | 同 article 的指针推进 |
| 只读快照 | `ListPublic` 把 count + 分页 + revision 取值包在**同一 repeatable-read 快照** | `ArticlePublicMapper` + 只读事务 | 同 B | ⚠️ 拆开就读到不一致快照 |
| 时间戳推进 | `gorm:"autoCreateTime"` / `autoUpdateTime` | `ArticleMetaObjectHandler` | 同 B | ⚠️ `UpdateColumn`（`SkipHooks=true`）**不推进** —— 原生 `@Update` SQL 天然绕开 MP |
| 软删 | `gorm.DeletedAt`（`deleted_at` + 索引） | `ArticleEntity` 的逻辑删除 | 同 B | |
| 雪花 ID | `common/snowflake`（`snowflake.Init()`） | `bootstrap/SnowflakeBootstrap.java` + `common/snowflake` | 同 B（+ starter 的 `SnowflakeAutoConfiguration`） | epoch / 位段是业务契约 |
| 表名 | `article` · `article_sync_outbox` · `article_revision` · `article_publication` · `article_domain_outbox` | 同 | 同 | `article_sync_outbox` 有显式 `TableName()`（`article_sync_outbox.go:29-31`） |

> 本节决策锁：**L-3**（详见 §5）。

---

## 3. HTTP / DI 差异

| 维度 | A (Go, go-zero) | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|---|
| 路由注册 | `server.AddRoutes(...)` × 3 块 + `rest.WithPrefix("/v1")` | `@RequestMapping("/v1")` + 6 个 `@*Mapping` | 同 B | **代码注册 → 注解声明**；前缀 `/v1` 是 L1 |
| HTTP method | **混合**：Get3 · Post2 · Put1 · Delete1 | 同 Go（**不是**统一 POST） | 同 Go | ⚠️ users 域的「统一 POST」是**已签字偏离**，article 域**不适用** |
| 端点清单 | `POST /v1/article` · `PUT /v1/article/:id` · `DELETE /v1/article/:id` · `GET /v1/me/article/:id` · `GET /v1/article/:id` · `GET /v1/articles` · `POST /v1/upload` | 同 Go | 同 Go | ⚠️ C 侧 `ArticleController` 的 TODO javadoc **写错了 5 条路径**（见 §7） |
| 鉴权分组 | 组1 `WithJwt`+`WithTimeout(70s)`（4 端点）· 组2 public（2 端点）· 组3 `WithMaxBytes(10MiB)`（1 端点） | `JwtAuthInterceptor` + public 前缀放行 | 同 B | **有 public 段**（`WithJwt` 只覆盖一组） |
| 上传上限 | `rest.WithMaxBytes(10*1024*1024)` | `spring.servlet.multipart.max-file-size` | 同 B | 配置替代 API |
| 调用超时 | `zrpc.WithCallTimeout(70s)`（仅 create 路径） | stub deadline | 域内直连后 **不再是网络超时** ⇒ 语义位归位 | 合并后 create 不再跨网络 |
| Handler / Controller | 7 个薄 handler | 1 个 `ArticleController`（7 个 `@*Mapping`） | 同 B | 薄适配层同质 ⇒ 合 1 |
| Logic 中间层 | 7 个 logic + `helper.go` | 7 个 `*Service` + `ArticleApiSupport` + `ArticleAuthorResolver` | **1 个 `ArticleApiService` + 1 个 `ArticleApiSupport`** | D2 + §5.4「用例内聚」五问判据 |
| 错误表达（业务失败） | `httpx.OkJson` + `response.Response{Code,Msg,Data}` ⇒ **HTTP 恒 200** | `advice/GlobalResponseAdvice` 同一形状 | `service/article/src/main/java/com/rag/article/advice/ArticleApiResponseAdvice.java` | 信封是 L1 |
| 错误表达（裸响应） | `POST /v1/upload` 调 `httpx.OkJsonCtx` ⇒ **裸 `{"image_url":…}`** | `ResponseEntity<UploadImageResp>` 绕过 advice | 同 B | ⚠️ advice 必须排除 `/upload` |
| 错误表达（框架级） | `httpx.Error` ⇒ 400 + `text/plain` | `GoStyleRouterErrorResponses`（共享层） | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` | D3 收进共享层 |
| gRPC code → 业务码 | api 侧 logic 里显式 `switch st.Code()`（5 条分支） | `GoStyleGrpcStatusMapper` + `GlobalExceptionHandler` | `service/article/src/main/java/com/rag/article/advice/ArticleApiExceptionHandler.java` | D2 后 **同一份逻辑要能同时产出 HTTP 与 gRPC 两种错误表达** |
| JWT 校验 | `rest.WithJwt(secret)` → ctx 键 `userId` | `JwtAuthInterceptor` | `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java` | **claim 名 = `userId`**（L1） |
| 配置绑定 | `config.Config` 静态结构体 + yaml | `@ConfigurationProperties` | `service/article/src/main/java/com/rag/article/config/ArticleApiProperties.java` + `service/article/src/main/java/com/rag/article/config/ArticleRpcProperties.java` | 类型安全；两套配置块本就不同 ⇒ 仍分两个类 |
| ServiceContext | `svc.ServiceContext`（纯装配） | **删除**（Spring DI 接管） | **删除** | 无业务方法 ⇒ 不生成文件 |
| 进程拓扑 | 2 进程 | 2 进程 | **1 进程**（D1） | 「rpc 进程不可用」这一故障模式消失 |

> 本节决策锁：**L-4 / L-5 / L-6**（详见 §5）。

---

## 4. 公共模块对照（article 域与 `common/` 的边界）

> 判据：**跨域共享 ⇒ `common/`；域内专有 ⇒ 留在 `article-service` 的域内 `contract/` 包。**

| 职责 | A (Go) | B (RAG) | C (RAG-V1) | 归属 |
|---|---|---|---|---|
| HTTP 信封 | `service/common/response/response.go` | `service/common/…/response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | **共享层**（L1，跨 10 域） |
| 业务异常载体 | 无统一载体（api 返回 `(resp, code)` 双返回） | `service/common/…/exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | **共享层**（D2 合并后统一抛） |
| 错误码 / 文案 | `service/article/common/errmsg/errmsg.go` | `article-common/…/errmsg/ErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `ErrMsg.java` | **域内**（逐域隔离，不可跨域统一） |
| gRPC 异常映射 | 无 | 无统一件 | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | **共享层**（D2 的关键设计点） |
| JWT 工具 | go-zero `rest.WithJwt` 内置 | `service/common/…/jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` + `BearerTokenExtractor.java` | **共享层** |
| 雪花 ID | `service/common/snowflake` | `service/common/…/snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | **共享层**（epoch / 位段是业务契约） |
| jsonb TypeHandler | Go struct tag（无独立件） | `article-rpc/…/mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | **共享层**（D3 跨域去重） |
| community 事件信封 | `service/common/communityfact/wire.go` | `service/common/…/communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/Event.java` + `Wire.java` | **共享层**（comment / favorite / like / article 共用） |
| community 域事件 payload | `rpc/internal/mqs/community_article_event.go` 的 `communityRevisionPayload` | `article-rpc/…/mq/ArticleSyncEventTypes.java` | `service/article/src/main/java/com/rag/article/contract/CommunityArticleEvent.java` | **域内**（article 特有 payload） |
| MQ 事件载荷 | `rpc/internal/mqs/article_sync_types.go` | `article-rpc/…/mq/ArticleSyncEventTypes.java` | `service/article/src/main/java/com/rag/article/contract/ArticleSyncEvent.java` | **域内契约**（跨服务共享 ⇒ 放 contract 模块） |
| go-zero 运行时语义补齐（熔断 / CPU 降载 / MaxBytes / 路由错误形状 / Jackson 强转） | go-zero 内置 | `service/common/protection/`（17 类）+ `web/`（9 类） | `common/src/main/java/com/rag/common/protection/`（2 类）+ `common/src/main/java/com/rag/common/web/`（17 类） | **共享层**（D3 + D4） |
| 观测 | `service/common/observability` | `service/common/…/observability/` | `common/src/main/java/com/rag/common/observability/`（`Observation` + `GoStyleRequestDeadline`） | **共享层** |
| 业务规则 | 各域 `logic/` | 各域 `service/` | 各域 `service/`（`ArticleDomainService` / `ArticlePublicDomainService` / `ArticleAtomicService`） | **域内**（不共享） |

> 本节决策锁：**L-7**（详见 §5）。

---

## 5. 决策锁

> 复刻到下一个域时按这个清单改，不漏。每条给出「**N 处同时改**」与「**为什么换**」。

### L-1 · 合并 api 与 rpc 两个进程（**D1**）— 8 处同时改

1. 删 `article-api` 的 `ArticleApiApplication.java`，只保留 `ArticleServiceApplication.java`（`@SpringBootApplication` + `@EnableDiscoveryClient`）
2. 删 `article-rpc` 的 `ArticleRpcApplication.java`，其 gRPC server 注册并入同一个入口
3. `ArticleApiProperties.java` + `ArticleRpcProperties.java` **保留两个**（配置块本就不同），但共用一份 `application.yml`（端口段用 `server.port` / `grpc.server.port` 区分）
4. `ArticleController` 里的 `ArticleRpcClient` 调用改成**域内直连** `ArticleDomainService`
5. `article-api` 的 `pom.xml` 与 `article-rpc` 的 `pom.xml` 合成一个 `service/article/pom.xml`（`spring-boot-starter-web` + `grpc-server` + `grpc-client` + `mybatis-plus` + `spring-boot-kafka`）
6. Nacos 注册项从 2 个（`article-api` / `article-rpc`）减为 1 个（`article-service`）
7. `ArticleSchemaGate` / `MinioBucketBootstrap` / `SnowflakeBootstrap` / `NacosRegistration` 的启动顺序断言改为单进程内排序
8. 部署清单（端口 / 健康检查 / 服务名）从 2 条并为 1 条

**为什么换**：Spring Cloud 的部署单元是「可部署服务」，不是「api 层 / rpc 层」；go-zero 的 api/rpc 分离源于 `.api` 文件与 `.proto` 两条 codegen 路径，是**语言产物**不是架构原则。合并后「rpc 进程不可用」这一故障模式直接消失。

### L-2 · 域内调用直连，删除自我 gRPC 往返（**D2**）— 5 处同时改

1. 删 `article-api/…/client/ArticleRpcClient.java`（域内不再走网络）
2. `ArticleApiService` 直接注入 `ArticleDomainService` / `ArticlePublicDomainService`
3. `ArticleCreateService` 的 70s 调用超时改为**领域层事务超时**语义（不再是网络 deadline）
4. api 侧的「gRPC code → 业务码」5 分支映射表删除；业务层直接抛 `BusinessException(code,msg)`，由 `ArticleApiExceptionHandler`（HTTP 边界）与 `RagGrpcExceptionMapper`（gRPC 边界）各自适配
5. `ArticleApiSupport` 的 proto → DTO 投影逻辑删除（不再有 proto 中转）

**为什么换**：同进程内调 gRPC 是无意义的序列化开销与故障注入。代价是**同一份业务逻辑要能同时产出 HTTP 与 gRPC 两种错误表达** ⇒ 统一错误模型（`BusinessException` + 两个边界适配器）是 D2 的前置条件。

### L-3 · 持久层载体归位（含 jsonb 去重与 DDL 补齐）— 6 处同时改

1. `article-rpc/…/mapper/JsonbTypeHandler.java` 删掉，改注入 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`
2. `ArticleEntity` 的 `secondary_tags` / `ext_info` 两列挂上共享 TypeHandler
3. `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` 补 `article` 域需要的「数组 + 对象」两种形态（共享层已承载其它域）
4. **补齐 `db/migration/`**：五张表 + `article_revision_immutable` 触发器 + 两处 CHECK 约束（缺口 G-5）
5. `ArticleSchemaGate` 的断言从「触发器存在」扩展到「五张表 + 触发器全部存在」
6. `ArticleAtomicService` 的 3 处多步事务逐条核对：`@Transactional` 必须落在**独立 Bean 的 public 方法**上

**为什么换**：jsonb TypeHandler 是跨域同构件，按 D3 应收进 starter 层去重；DDL 缺失会让 `ArticleSchemaGate` 直接 fail-fast 起不来，是 C 侧最先会暴露的问题。

### L-4 · 7 个薄 handler → 1 个 Controller（**注解式路由**）— 3 处同时改

1. 删 7 个 Go 侧 handler 的等价物（若已按 §5.2 合并，直接改 `ArticleController` 的 7 个 `@*Mapping`）
2. `@RequestMapping("/v1")` + `POST /article` / `PUT /article/{id}` / `DELETE /article/{id}` / `GET /me/article/{id}` / `GET /article/{id}` / `GET /articles` / `POST /upload` —— **method 与路径逐条照抄 Go，不统一成 POST**
3. `POST /upload` 的 multipart 字段名固定 `image`（`@RequestParam("image") MultipartFile`），并用 `ResponseEntity<UploadImageResp>` 绕过信封 advice

**为什么换**：go-zero 代码注册路由 vs Spring 注解声明式路由；注解式更直观且与 Swagger 自动集成。⚠️ **method 混合是 Go 原样**——users 域的「统一 POST」是已签字偏离，article 域不适用。

### L-5 · 7 个 logic → 1 个 `ArticleApiService`（**用例内聚**）— 4 处同时改

1. 删 `ArticleCreateService` / `ArticleGetService` / `ArticleGetAuthorService` / `ArticleListService` / `ArticleUpdateService` / `ArticleDeleteService` / `ArticleUploadService` 七个类
2. 七个用例的方法体并入 `ArticleApiService`（**方法名与语义保留**：公开详情 / 作者详情是两个方法，不是两个类）
3. `helper.go` 的 uid 提取 / proto→DTO 投影 / 防御性强转并入 `ArticleApiSupport`；作者名补全（**吞错**策略）并入同一处或独立 `ArticleAuthorResolver`（取决于 G-2 是否补齐）
4. `ArticleApiResponseAdvice` 加**排除项**：`/v1/upload` 不走信封

**为什么换**：7 个 logic 文件是 goctl 按 RPC 生成的**模板产物**，不是职责边界。五问判据（同一入口聚合 / 一致的变化原因 / 无独立生命周期 / 无独立契约 / 合并后职责不模糊）全中 ⇒ 合并。⚠️ 但**作者名补全的容错策略是显式业务决策**，若 G-2 补齐则应独立成 `ArticleAuthorResolver`。

### L-6 · 删除 `ServiceContext`（static → Spring Bean）— 4 处同时改

1. 删 `api/internal/svc/service_context.go` 与 `rpc/internal/svc/service_context.go` 的等价物（C 侧本就无对应文件）
2. 每个 static 字段对应一个 `@Component` / `@Bean`：`ArticleRpcProperties` / `ArticleApiProperties` / `MessageRpcClient` / 对象存储客户端 / snowflake Bean
3. 调用点 `svcCtx.XxxRpc.Yyy(...)` → `@Autowired XxxClient; client.yyy(...)`
4. 入口从 `svc.NewServiceContext(c)` 改成 `@SpringBootApplication` 自动扫描装配

**为什么换**：static 全局变量在测试和多进程时难处理；Spring Bean 单例 + 依赖注入更标准。

### L-7 · 错误契约逐域隔离（**不建共享错误码基座**）— 3 处同时改

1. `service/article/src/main/java/com/rag/article/contract/ErrCodes.java` 的码值**逐条照抄** `service/article/common/errmsg/errmsg.go:3-19`
2. `service/article/src/main/java/com/rag/article/contract/ErrMsg.java` 的文案**逐条照抄** `errmsg.go:21-37`（中文；article / comment / favorite / like / user 的 `CodeServerBusy` 是中文，follow / hot / message 是英文 —— article 是中文）
3. `GetErrMsg` 的未知码回落语义（回落 `"FAIL"`，不是 `null` / 空串 / code 字符串）落到 `ErrMsg` 的查表方法

**为什么换**：8 个域的码值与文案**各不相同**（article 的 5004/5005/5006 是「文件上传/删除/下载失败」，users 的同值是「数据库插入失败 / 事务操作失败 / 雪花ID生成失败」）。合并成一个共享基座会改掉 8 个域的可见行为（L1）。

---

## 6. 跨模块全局约定（值逐条核对 A 列 Go 源码）

> ⚠️ **本表的每个值都来自 A 列源码，不是从 B 列转抄。**

| 项 | 值 | A 列出处（文件:行号） |
|---|---|---|
| **HTTP 前缀** | `/v1` —— **不带域名前缀**（全仓唯一） | `api/internal/handler/routes.go:43,60,71`（三处 `rest.WithPrefix("/v1")`） |
| **HTTP method 分布** | **Get3 · Post2 · Put1 · Delete1**（7 端点）—— **不是**统一 POST | `routes.go:22,28,33,38`（JWT 组）+ `:49,56`（public 组）+ `:66`（upload 组） |
| **端点清单** | `POST /v1/article` · `PUT /v1/article/:id` · `DELETE /v1/article/:id` · `GET /v1/me/article/:id` · `GET /v1/article/:id` · `GET /v1/articles` · `POST /v1/upload` | 同上 |
| **鉴权分组** | 组1 = JWT + `WithTimeout(70s)`（4 端点）· 组2 = public 无中间件（2 端点）· 组3 = `WithMaxBytes(10 MiB)`（1 端点） | `routes.go:42-44` / `:60` / `:72` |
| **JWT claim** | `userId` | `api/internal/logic/article/helper.go:18`（`ctx.Value("userId")`） |
| **JWT 配置** | `Auth.AccessSecret` + `Auth.AccessExpire = 31536000` | `api/internal/config/config.go:13-16` |
| **错误码** | 15 个：`Success=200` `Error=500` `CodeServerBusy=1015` `1001` `1002` `1003` `1004` `1005` `1006` `5001` `5002` `5003` `5004` `5005` `5006` | `common/errmsg/errmsg.go:3-19` |
| **错误文案** | 中文；`CodeServerBusy="服务繁忙"`；`ErrorArticleForbidden="只能编辑或删除自己的文章"`；未知码回落 `"FAIL"` | `errmsg.go:21-37` / `:39-45` |
| **信封形状** | `{"code":int,"msg":string,"data":…}`；**HTTP 恒 200** | 7 个 handler 的 `httpx.OkJson(w, &response.Response{Code,Msg,Data})` |
| **唯一裸响应** | `POST /v1/upload` → `{"image_url":string}`；multipart 字段名固定 `image` | `api/internal/handler/article/upload_image_handler.go:16,28` · `api/internal/types/types.go:89-94` |
| **proto 字段号** | `article.proto` 的 message 名 / 字段名 / 字段号 / 类型 / 枚举值**逐条不变** | `proto/article.proto`（C 侧仅改 option 块） |
| **gRPC 方法** | 6 个：`CreateArticle` `GetArticle` `UpdateArticle` `DeleteArticle` `ListArticles` `UploadFile` | `rpc/internal/server/article_service_server.go:26,31,36,41,46,51` · `proto/article.proto` 的 `service ArticleService` |
| **枚举取值** | `UNSPECIFIED=0 DRAFT=1 PUBLISHED=2 REVIEWING=3 REJECTED=4 DELETED=5` | `proto/article.proto` 的 `enum ArticleStatus` |
| **表名** | `article` · `article_sync_outbox` · `article_revision` · `article_publication` · `article_domain_outbox` | `model/article.go`（GORM 默认）· `model/article_sync_outbox.go:29-31` · `model/community_revision.go:30,43,57` |
| **关键索引 / 约束** | `article`：`idx_article_manual_type_tag` / `idx_article_author_id` / `idx_article_deleted_at`；`article_revision` PK`(article_id,revision)` + `revision_id`/`sync_event_id` 唯一；`article_publication` PK`article_id` + `state IN ('published','retracted')` CHECK；`article_domain_outbox` PK`event_id` + `UNIQUE(aggregate_id,aggregate_version)` + 部分索引 `WHERE status IN (0,2)` | `rpc/internal/model/article.sql` · `rpc/internal/model/002_community_revision.sql` |
| **Kafka topic** | `article-creation-topic`（审核：pusher + consumer）· `article-sync-events`（出）· `article-sync-results`（入）· `article-hot-events`（**零调用，死配置**） | `rpc/etc/article.yaml` |
| **Kafka group** | `article-creation-group` / `dataclean` / `article-sync-pusher` / `article-sync-result` | `rpc/etc/article.yaml` |
| **outbox 轮询参数** | `PollIntervalMs=1000` · `BatchSize=100` · `MaxRetry=10`；`FetchPending` 过滤 `status IN (0,2) AND retry_count < maxRetry` | `rpc/etc/article.yaml` · `rpc/internal/model/article_sync_outbox.go:55,57` |
| **MinIO** | bucket `articles` · `ImagePath=articles/images/` · `ArticlePath=articles/content/` · 对象名 `<ArticlePath><id>.md` 与 `<ArticlePath><id>-<sha256>.md` | `rpc/etc/article.yaml` · `rpc/internal/logic/create_article_logic.go:59` · `rpc/internal/logic/update_article_logic.go:194` · `rpc/internal/logic/upload_file_logic.go:56` |
| **数据库** | PostgreSQL，schema `public`，库 `first_db` | `rpc/etc/article.yaml` |
| **指标完整名** | `article_service_article_total` · `article_service_article_status_total` · `article_service_file_upload_total` · `article_service_minio_request_duration_seconds` · `article_service_minio_request_errors_total` · `article_service_kafka_push_errors_total` | `rpc/metrics/metrics.go:6`（`Namespace = "article_service"`）+ `:13,23,33,43,54,64` |
| **指标标签** | `action`∈{create,update,delete} · `status`∈{reviewing,published,rejected} · `type`∈{image,markdown} · `operation`∈{put,get,delete} · `topic` | `rpc/metrics/metrics.go:17,27,37,48,58,68`（注释明写取值域） |
| **指标埋点完整性** | `article_status_total` **只有 `reviewing` 有 +1 调用点**（`published` / `rejected` 无调用点）⇒ **照搬这个不完整，不许补全** | `rpc/internal/logic/create_article_logic.go:115`（唯一调用点） |
| **MinIO 直方图分桶** | `{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1}`（秒，8 个） | `rpc/metrics/metrics.go:46` |
| **community 事件 `event_id`** | `community.article.<syncEventID>.<operation>`；`producer = rtw.community.article`；`event_type = community.article.<operation>`；`schema_version = 1` | `rpc/internal/mqs/community_article_event.go`（`articleDomainEvent`） |
| **community `subject_ref`** | `rtw.identity` / `platform` / `<AuthorID>`；`wiki_module_id` **恒为 nil**（社区文章永不进知识库） | `rpc/internal/mqs/community_article_event.go`（`communitySubject{...}` + `WikiModule: nil`） |
| **`revision_id` 格式** | `<articleID>:r<revision>` | `rpc/internal/mqs/article_sync_result_consumer.go:117`（`fmt.Sprintf("%s:r%d", article.ID, highest+1)`） |
| **`ext_info` 键** | `publish_stage` `reco_sync_state` `last_sync_error` `last_sync_event_id` `last_sync_version_ms` `last_sync_reason` `published_once` `pending_sync_reason` `review_nonce`（+ `h03_publication_gap`） | `rpc/internal/mqs/article_sync_types.go:24-32` · `rpc/internal/mqs/community_article_event.go`（`h03_publication_gap`） |
| **`sync_event` op / reason 取值** | op ∈ {upsert, delete}；reason ∈ {create, update, status_change, delete}；scope = `article_sync` | `rpc/internal/mqs/article_sync_types.go:14-22` |

---

## 7. 覆盖度自查

> 判据来自 `docs/Mapping_Table/README.MD` §四。

### 7.1 C 列零遗漏 —— ✅ 51/51（100%）

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/article" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

实测 **51** 个唯一文件名，逐条比对结果：

| # | C 列文件 | 出现在 | # | C 列文件 | 出现在 |
|---|---|---|---|---|---|
| 1 | `Article.java` | §1.1 dto 行 | 27 | `ArticleGrpcService.java` | §1.2 |
| 2 | `ArticleApiExceptionHandler.java` | §1.1 advice 行 | 28 | `ArticleSyncEvent.java` | §1.3 |
| 3 | `ArticleApiProperties.java` | §1.1 config 行 | 29 | `ArticleSyncOutboxEntity.java` | §1.2 |
| 4 | `ArticleApiResponseAdvice.java` | §1.1 advice 行 | 30 | `ArticleSyncOutboxMapper.java` | §1.2 |
| 5 | `ArticleApiService.java` | §1.1 logic 行 | 31 | `CommunityArticleEvent.java` | §1.3 |
| 6 | `ArticleApiSupport.java` | §1.1 helper 行 | 32 | `CreateArticleReq.java` | §1.1 dto 行 |
| 7 | `ArticleAtomicService.java` | §1.2 | 33 | `CreateArticleResp.java` | §1.1 dto 行 |
| 8 | `ArticleController.java` | §1.1 | 34 | `DeleteArticleReq.java` | §1.1 dto 行 |
| 9 | `ArticleDomainOutboxEntity.java` | §1.2 | 35 | `DeleteArticleResp.java` | §1.1 dto 行 |
| 10 | `ArticleDomainOutboxMapper.java` | §1.2 | 36 | `ErrCodes.java` | §1.3 |
| 11 | `ArticleDomainService.java` | §1.2 | 37 | `ErrMsg.java` | §1.3 |
| 12 | `ArticleEntity.java` | §1.2 | 38 | `GetArticleReq.java` | §1.1 dto 行 |
| 13 | `ArticleExceptions.java` | §1.2 | 39 | `GetArticleResp.java` | §1.1 dto 行 |
| 14 | `ArticleKafkaChannelValidator.java` | §1.2 | 40 | `JwtAuthInterceptor.java` | §1.1 |
| 15 | `ArticleMapper.java` | §1.2 | 41 | `ListArticlesReq.java` | §1.1 dto 行 |
| 16 | `ArticleMetaObjectHandler.java` | §1.2 | 42 | `ListArticlesResp.java` | §1.1 dto 行 |
| 17 | `ArticlePublicDomainService.java` | §1.2 | 43 | `MessageRpcClient.java` | §1.2 |
| 18 | `ArticlePublicMapper.java` | §1.2 | 44 | `MinioBucketBootstrap.java` | §1.2 |
| 19 | `ArticlePublicationEntity.java` | §1.2 | 45 | `NacosRegistration.java` | §1.2 |
| 20 | `ArticlePublicationMapper.java` | §1.2 | 46 | `SnowflakeBootstrap.java` | §1.2 |
| 21 | `ArticleRevisionEntity.java` | §1.2 | 47 | `UpdateArticleReq.java` | §1.1 dto 行 |
| 22 | `ArticleRevisionMapper.java` | §1.2 | 48 | `UpdateArticleResp.java` | §1.1 dto 行 |
| 23 | `ArticleRpcMetrics.java` | §1.2 | 49 | `UploadImageReq.java` | §1.1 dto 行 |
| 24 | `ArticleRpcProperties.java` | §1.2 | 50 | `UploadImageResp.java` | §1.1 dto 行 |
| 25 | `ArticleSchemaGate.java` | §1.2 | 51 | `WebMvcConfig.java` | §1.1 |
| 26 | `ArticleServiceApplication.java` | §0 / §1.1 | | | |

> **结论：51/51，无遗漏。**

### 7.2 A 列不遗漏职责 —— ✅ 45/45（100%）

```bash
find "/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/article" \
  -name '*.go' -not -name '*_test.go' | sort
```

实测 **45** 个（43 个手写 + 2 个 `pb/` 生成物）。全部出现在 §1.1 / §1.2 / §1.3 的 A 列；§1.4 另列 4 项「无独立对应物」并注明原因。

| A 列分组 | 文件数 | 覆盖位置 |
|---|---|---|
| `api/`（入口 + 配置 + svc + types） | 4 | §1.1 |
| `api/internal/handler/`（7 handler + routes） | 8 | §1.1 |
| `api/internal/logic/article/`（7 logic + helper） | 8 | §1.1 |
| `common/errmsg/` | 1 | §1.3 |
| `rpc/`（入口 + `articleservice/` + `pb/`） | 5 | §1.2 / §1.3 |
| `rpc/internal/config/` + `internal/svc/` | 2 | §1.2 |
| `rpc/internal/logic/` | 6 | §1.2 |
| `rpc/internal/model/` | 5 | §1.2 |
| `rpc/internal/mqs/` | 5 | §1.2 / §1.3 |
| `rpc/internal/server/` | 1 | §1.2 |
| `rpc/metrics/` | 1 | §1.2 |

> **结论：45/45，无遗漏。**（`.sql` 两份 —— `model/article.sql` / `model/002_community_revision.sql` —— 不是 `.go`，但已在 §2 与缺口 G-5 中登记。）

### 7.3 映射类型分布（实测计数）

| 映射类型 | §0 顶层 | §1 包结构 | 合计 | 占比 |
|---|---|---|---|---|
| `1:1` | 1 | 22 | **23** | 33.3% |
| `1:N` | 3 | 18 | **21** | 30.4% |
| `N:1` | 1 | 23 | **24** | 34.8% |
| `C 新增` | 1 | 0 | **1** | 1.4% |
| **合计** | **6** | **63** | **69** | 100% |

> 每行都填了映射类型，且只用 `1:1` / `1:N` / `N:1` / `C 新增` 四种词（未发明新词）。
> ⇒ `N:1` 与 `1:N` 合计 **65/69（94%）** 是 article 域的常态：**Go 侧按 codegen 模板切成 45 个文件，RAG-V1 按职责收敛成 21 个**。

### 7.4 ⚠️ 三列之间「职责对不上」的地方（单独列出）

> 本表产出的待订正项，分两类：**① A/B 有、C 无**（§1.5 的 6 处缺口，G-1 ~ G-6）· **② C 列现有文档 / TODO 与 A 列源码不符**（D-1 / D-2）。

| # | 位置 | 问题 | 处置 |
|---|---|---|---|
| **D-1** | `RAG-V1/docs/第二轮深挖-契约盘点.md:134,150` **与** `RAG-V1/service/article/article/service/.../metrics/ArticleRpcMetrics.java` 的 TODO javadoc | 声称「article/rpc **完全没有** Namespace/Subsystem ⇒ 指标名是裸名（`article_total` 等）」。**与 A 列源码不符**：`service/article/rpc/metrics/metrics.go:6` 明确写着 `Namespace = "article_service"` ⇒ 完整名应为 `article_service_article_total` 等。B 列 `ArticleRpcMetrics.java` 也按前缀实现（`NAMESPACE = "article_service"`）。 | 本表 §6 按 **A 列源码**记录带前缀的完整名；C 侧填实现时以本表为准，并应回修这两处文档/TODO |
| **D-2** | `RAG-V1/service/article/article/service/.../controller/ArticleController.java` 的 TODO javadoc | 端点清单写成 `GET /v1/article` / `PUT /v1/article` / `DELETE /v1/article` / `GET /v1/user/articles` —— **4 条缺 `:id`、1 条路径写错**。A 列真值是 `GET /v1/article/:id` / `PUT /v1/article/:id` / `DELETE /v1/article/:id` / `GET /v1/me/article/:id`。method 分布（Get3·Post2·Put1·Delete1）✅ 正确 | 本表 §6 按 A 列记录；C 侧填实现时以本表为准，并应回修该 TODO |
| **D-3** | C 列**整体**（详见 §1.5） | 6 处职责缺口：**G-1** `mq/` 包（3 个消费者：审核 / 同步结果 / outbox relay）· **G-2** `UserRpcClient`（作者名补全）· **G-3** 对象存储客户端（MinIO put/get）· **G-4** 内容安全 / 图片安全客户端 · **G-5** DDL 未落地（`db/migration/` 仅 `.gitkeep`）· **G-6** `ArticleReviewMessage` / `ArticleSyncResult` 载荷无载体 | §1.5 已逐条登记 A 列出处 + B 列载体 + 后果；**填实现前须先补齐**，否则会静默丢职责（其中 G-5 会让 `ArticleSchemaGate` 直接 fail-fast 起不来） |

### 7.5 一句话总览

`A 列 Go` = 2 进程（api + rpc）· go-zero `AddRoutes` + GORM + 3 块 `AddRoutes` 分组 · 裸 `Namespace` 之外的 `article_service` 指标前缀 ·
`B 列 RAG` = 同名 Java 包 + MyBatis-Plus + Flyway V8/V9/V10 + api/rpc 分离 + 9 个 api Service + 3 个 domain Service + `mq/` 包 4 类 ·
`C 列 RAG-V1` = api/rpc 合并为单 app + `advice + config + controller + dto + interceptor + service` 与 `bootstrap + client + config + domain/entity + domain/service + exception + grpc + mapper + metrics` 两个包内分区 + **7 个用例合 1 个 `ArticleApiService`** + 契约落在域内 `contract/` 包（proto 在根级 `proto/`） + **6 处职责缺口待补**。
proto 字段号 / 错误码 / JWT claim / 表名 / HTTP path / Kafka topic / 指标完整名 —— **全部以 A 列 Go 源码为准逐条核对**。
