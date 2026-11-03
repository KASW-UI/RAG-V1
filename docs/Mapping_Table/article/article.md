# Mapping_Table/article/article.md — article 域两方文件名对照表

> **本表目的**：把 **RAG**（B 列）→ **RAG-V1**（C 列，Spring 惯用目标形态）两棵树的 **article 域**文件逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 RAG 文件来的」。
>
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/article/`（判定基准；api / rpc 分离）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/service/article/`（本仓；api / rpc 合并为 `article-service` 单 app，D1）
>
> **字段映射**：B 文件 → C 文件 + 映射类型（`1:1` / `1:N` / `N:1` / `C 新增`）

---

## ⚠️ 三条读表纪律（本表的前提，不可省略）

1. ⚠️ **B 列是唯一行为基准（D16）** —— 本表里所有「值」都以 B 侧源码为准逐条抄出。
   B 侧与 C 侧不一致的地方，本表**保留差异说明**，不做抹平。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」**，**不下「已对齐 / 未对齐」结论**。
   行为对齐状态看 `docs/DRIFT/article/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 Java 文件目前只有 package + 空类型 + TODO javadoc。
   **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

> ⚠️ **另一条口径声明**：任务简报中「指标无命名空间，`article_total` 等是裸名」这一条，**与 B 侧源码不符** ——
> B 侧 `article-rpc/.../metrics/ArticleRpcMetrics.java` 明确写着 `NAMESPACE = "article_service"`。
> 本表按 **B 侧源码**记录完整名（`article_service_article_total` 等），并在 §7 登记该文档缺陷。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| B (RAG) | C (RAG-V1, Spring 目标) | 映射类型 | 备注 |
|---|---|---|
| `service/article/article-api/`（43 个 `.java`） | `service/article/` 的 `service/article/src/main/java/com/rag/article/advice + service/article/src/main/java/com/rag/article/config + service/article/src/main/java/com/rag/article/controller + service/article/src/main/java/com/rag/article/dto + service/article/src/main/java/com/rag/article/interceptor + service/article/src/main/java/com/rag/article/service` 包（21 个 `.java`） | 1:N | D1：api 进程并入单 app，HTTP 边界改由 `api/` 包承载 |
| `service/article/article-rpc/`（39 个 `.java`） | `service/article/` 的 `service/article/src/main/java/com/rag/article/wiring + service/article/src/main/java/com/rag/article/client + service/article/src/main/java/com/rag/article/config + service/article/src/main/java/com/rag/article/domain/entity + service/article/src/main/java/com/rag/article/domain/service + service/article/src/main/java/com/rag/article/exception + service/article/src/main/java/com/rag/article/grpc + service/article/src/main/java/com/rag/article/mapper + service/article/src/main/java/com/rag/article/metrics` 包（25 个 `.java`） | 1:N | D1：rpc 进程并入单 app |
| `service/article/article-common/`（1 个 `.java`） | `service/article/src/main/java/com/rag/article/contract/`（4 个 `.java`） | 1:N | C 把「错误契约」并入契约模块；B 侧 `article-common` 只有 errmsg 一个包 |
| `article-rpc/.../exception/ArticleExceptions.java` | `service/article/src/main/java/com/rag/article/exception/ArticleExceptions.java` | 1:N | 业务异常工厂；⚠️ **本域与 like 域的错误模型相反** —— rpc 侧发**标准 gRPC 码**，业务码不上线（api 侧在 logic 里显式映射）⇒ 只承载 gRPC status，**不引** `article-common` 的 `ErrorCode`；与上一行**同源**（B 侧 1 个 `ErrorCode.java` → C 侧 2 个类） |
| `service/article/article-proto/src/main/proto/article.proto` | `proto/src/main/proto/article.proto` | 1:1 | L1 契约，逐条一致；仅 option 块不同（见 §6） |
| `service/article/article-api/etc/` + `article-rpc/etc/`（yaml） | `service/article/src/main/resources/application.yml` | N:1 | 两份 yaml 合并为一份（D1） |
| — | `service/article/src/main/java/com/rag/article/contract/`（proto + 错误契约独立模块） | C 新增 | 契约与实现分离：**proto 是 L1 跨服务契约，塞进 app 模块会让消费方依赖整个应用** |

### 0.1 进程拓扑对照

| 项 | B (RAG) | C (RAG-V1) |
|---|---|---|
| 进程数 | 2（`article-api` :8889 + `article-rpc` :9001） | **1**（`article-service`，HTTP `ARTICLE_HTTP_PORT` + gRPC `ARTICLE_GRPC_PORT`） |
| 注册中心 | Nacos | Nacos |
| 同域调用 | api → rpc 走 gRPC（devh `@GrpcClient`） | **域内直连**（D2）：`ArticleApiService` → `ArticleDomainService` |

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
| `article/api/dto/` | `dto/article/` + `dto/upload/` ＋ **共享层** `common/web/StringFields.java`（承接 `GoStyleStringFields`） | 直接压平后**按业务对象拆 2 个子包**（D11：`article` 11 / `upload` 2）；⚠️ `GoStyleStringFields` **不是 DTO**（是取值/类型判定工具）⇒ ✅ **D-09 已收口（2026-11-03）**：原先落域内 `web/ArticleStringFields.java`（共享层那份**包私有** ⇒ 域内调不到）⇒ 现共享层放开可见性、**域内副本已删** |
| `article/api/form/`（`GoFormValues` · **取值真值表**：`raw`/`first`/`bool`/`int32`/`text`） | `common/src/main/java/com/rag/common/web/FormValues.java` | ✅ **D-10 已收口（2026-11-03）** —— 原先落域内 `web/ArticleQueryValues.java`（共享层**缺对应物**：`GetFormValuesFilter` 只是**上限闸门**、不提供取值方法）⇒ 现上收到共享层、**域内副本已删** |
| `common/http/GoStyleFormValues`（**上限闸门** —— 归位到共享层 `common/web/`） | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` | ⚠️ **与上一行不是同一个类**：B 侧 `common/http/GoStyleFormValues` 是**闸门/判据**，`article-api/…/form/GoFormValues` 是**取值真值表** ⇒ 🔴 **旧版把这两个 B 类并成一条映射 = 缺陷**（2026-11-03 已拆为两条）|
| `article/api/http/`（4 类） | `common/src/main/java/com/rag/common/web/`（4 个同名类）| **上收 `common/`**（D3）|
| `article/api/auth/`（4 类） | `interceptor/ArticleJwtAuthFilter.java`（Servlet Filter 段）＋ `interceptor/JwtAuthInterceptor.java`（HandlerInterceptor 段）| **域内自持**（B 侧「验签拆两段」是 article 域自己的口径，不上收共享层）|
| `article/api/interceptor/` | `interceptor/` | 直接压平 |
| `article/api/service/` | `service/` | 与 rpc 侧合并 |
| `article/common/errmsg/` | `contract/` | 错误码 + 事件 DTO（`ErrCodes` / `ErrMsg` / `ArticleSyncEvent` / `CommunityArticleEvent`）|
| `article/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `article/rpc/client/` | `client/` | 与 api 侧合并 |
| `article/rpc/config/` | `config/` | 合并 |
| `article/rpc/domain/` | `domain/entity/` | 按是否 `*Entity` 拆分 |
| `article/rpc/exception/` | `exception/` | 直接压平 |
| `article/rpc/mapper/` | `mapper/` | 直接压平 |
| `article/rpc/metrics/` | `metrics/` | 与 api 侧合并 |
| `article/rpc/mq/`（4 类） | `messaging/`（`ArticleReviewConsumer` / `ArticleSyncOutboxRelay` / `ArticleSyncResultConsumer`）＋ `contract/ArticleSyncEvent.java`（事件类型常量与载荷） | ✅ **已落地（2026-11-03）** —— C 侧新建 `messaging/` 包 ⇒ **缺口 G-1 关闭**；⚠️ `ArticleSyncEventTypes.java` 的常量与 `ArticleReviewMessage` / `ArticleSyncResult` 载荷拆进 `contract/ArticleSyncEvent.java`（**缺口 G-6 关闭**）|
| `article/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `article/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `ArticleServiceGrpcImpl` → `ArticleGrpcService` |
| `article-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.article`）| **集中化**（D7）|

## 1. 包结构对照

> 表头固定：`| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |`
> B 列路径写到**文件名**（`…` 代表包路径），便于 `grep` 定位。

### 1.1 api 侧（HTTP 边界）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `article-api/…/ArticleApiApplication.java` | `service/article/src/main/java/com/rag/article/ArticleServiceApplication.java` | N:1 | **D1**：与 `article-rpc/…/ArticleRpcApplication.java` 合并为唯一入口（`@SpringBootApplication` + `@EnableDiscoveryClient`）；C 侧不再有第二个 Application |
| `article-api/…/config/ArticleApiProperties.java` | `service/article/src/main/java/com/rag/article/config/ArticleApiProperties.java` | 1:1 | 静态字段 → `@ConfigurationProperties`；`SecurityRpcConf` 两侧均不迁移（B 侧死配置，见 §1.4-④） |
| `article-api/…/config/WebMvcConfig.java` + `article-api/…/controller/ArticleController.java` + `article-api/…/interceptor/JwtAuthInterceptor.java` | `service/article/src/main/java/com/rag/article/config/WebMvcConfig.java` + `service/article/src/main/java/com/rag/article/controller/ArticleController.java` + `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java` | 1:N | **代码注册路由 → 注解式路由**；`/v1` 前缀、4 组 method、public 段、10 MiB 上限、70s 超时**逐字保留** |
| `article-api/…/config/ArticleGoStyleCorsFilter.java` | `service/article/src/main/java/com/rag/article/config/ArticleCorsFilter.java` | 1:1 | ✅ **2026-11-03 已落地**（本轮新发现并补齐的**真实缺失**）—— CORS（Go `rest.WithCors()` **无参** ⇒ **通配 + OPTIONS 恒 204**）；⚠️ **刻意落域内、不上收**（message/like/hot 三域**明确不要**这个响应头 ⇒ 上收就得给所有 api 域加开关）；⚠️ **必须用 Servlet `Filter`、不能用 `addCorsMappings`**（Go 的 `corsRouter` 包在 router **外面** ⇒ 连**未注册路径**也要带 CORS 头）；`@Order(HIGHEST_PRECEDENCE + 1)` ⇒ 整条链**最外层**（比共享层 `TraceparentFilter`(+3) 更外 ⇒ 204 预检**不带** traceparent，与 Go 一致）|
| `article-api/…/controller/ArticleController.java`（`@PostMapping("/article")`） | `service/article/src/main/java/com/rag/article/controller/ArticleController.java` | 1:N | 7 个薄 handler 同质（parse → logic → 信封）⇒ 合 1 个 Controller |
| 同上（`@PutMapping("/article/{id}")`） | 同上 | 1:N |  |
| 同上（`@DeleteMapping("/article/{id}")`） | 同上 | 1:N |  |
| 同上（`@GetMapping("/me/article/{id}")`） | 同上 | 1:N |  |
| 同上（`@GetMapping("/article/{id}")`） | 同上 | 1:N |  |
| 同上（`@GetMapping("/articles")`） | 同上 | 1:N |  |
| 同上（`@PostMapping("/upload")` + `@RequestParam("image") MultipartFile`） | 同上 | 1:N | ⚠️ **全域唯一不走信封的端点**；multipart 字段名固定 `image` |
| `article-api/…/service/ArticleCreateService.java` | `service/article/src/main/java/com/rag/article/service/ArticleApiService.java` | N:1 | D2 后不再有「gRPC code → 业务码」中间层 |
| `article-api/…/service/ArticleGetService.java` | 同上 | N:1 |  |
| `article-api/…/service/ArticleGetAuthorService.java` | 同上 | N:1 | **保留双检**（B 侧注释明示的向后兼容双检） |
| `article-api/…/service/ArticleListService.java` | 同上 | N:1 | **整包拒绝**，不是静默跳过该 item |
| `article-api/…/service/ArticleUpdateService.java` | 同上 | N:1 | `Status != 0` 才设；`Title`/`Brief`/… 非空才设 |
| `article-api/…/service/ArticleDeleteService.java` | 同上 | N:1 |  |
| `article-api/…/service/ArticleUploadService.java` | 同上 | N:1 | 裸响应契约需能单独表达 |
| `article-api/…/service/ArticleApiSupport.java` + `article-api/…/service/ArticleAuthorResolver.java` | `service/article/src/main/java/com/rag/article/service/ArticleApiSupport.java` | 1:N | C 侧 `ArticleAuthorResolver`（作者名补全 + **吞错**策略）**无独立载体** ⇒ 缺口 G-2 |
| `article-api/…/client/ArticleRpcClient.java` | （域内直连）`service/article/src/main/java/com/rag/article/service/ArticleApiService.java` | N:1 | **D2**：同域不再走网络；C 侧无 client 文件 |
| `article-api/…/client/UserRpcClient.java` | **无对应文件** | N:1 | ⚠️ **缺口 G-2**：跨域调用仍在（D6），但 C 侧 article 域**没有 `UserRpcClient`** |
| `article-api/…/dto/`（13 个文件：`Article.java` / `CreateArticleReq.java` / `CreateArticleResp.java` / `DeleteArticleReq.java` / `DeleteArticleResp.java` / `GetArticleReq.java` / `GetArticleResp.java` / `ListArticlesReq.java` / `ListArticlesResp.java` / `UpdateArticleReq.java` / `UpdateArticleResp.java` / `UploadImageResp.java` / `GoStyleStringFields.java`） | `service/article/src/main/java/com/rag/article/dto/article`（11 个）＋ `dto/upload`（2 个）＝ **13 个 record**；**工具类已上收**到共享层 `common/web/StringFields.java`（承接 B 侧 `dto/GoStyleStringFields.java`） | 1:N | B 侧 13 个 DTO 文件 → C 侧 **13 个 record**（＋ 1 个工具类，**落在共享层**）；C 侧全部改 **immutable record**（D5）；`UploadImageReq.java` 是 C 新增位置（B 侧无，因 B 侧是空 struct）；⚠️ `GoStyleStringFields` **不是 DTO**（是 JSON 取值/类型判定工具）⇒ 落 `web/` 包而非 `dto/` |
| `article-api/…/dto/GoStyleStringFields.java` | `common/src/main/java/com/rag/common/web/StringFields.java` | 1:1 | ✅ **D-09 已收口（2026-11-03）** —— 原先 C 侧落**域内** `web/ArticleStringFields.java`（域内副本），因共享层 `common/web/StringFields` 是**包私有** `final class` + 包私有 `static parse` ⇒ **域内调不到**（实测编译产物 `final class com.rag.common.web.StringFields`）⇒ 现**共享层放开可见性**（`public final class` + `public static parse`）＋ **域内副本已删** ⇒ 副本数 1 → 0；⚠️ 可见性属 L4 表达层 ⇒ **方法体一字未改** ⇒ 错误文案逐字节相同 |
| `article-api/…/form/GoFormValues.java` | `common/src/main/java/com/rag/common/web/FormValues.java` | 1:1 | ✅ **D-10 已收口（2026-11-03）** —— 原先 C 侧落**域内** `web/ArticleQueryValues.java`（域内副本），因共享层 `common/web/GetFormValuesFilter` **只有上限闸门、无取值真值表** ⇒ 现**共享层新增 `FormValues`**（照抄 B 侧 195→**204 行**原类，方法体逐条照抄）＋ **域内副本已删** ⇒ 副本数 1 → 0；⚠️ **类名去 `Go` 前缀** + **异常类型换共享层 `BadRequestException`** ⇒ 两条错误文案**逐字未变** |
| `article-api/…/advice/GlobalResponseAdvice.java` | `service/article/src/main/java/com/rag/article/advice/ArticleApiResponseAdvice.java` | 1:1 | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不能用 `ProblemDetail` 替换**；**必须排除 `/upload`** |
| `article-api/…/advice/GlobalExceptionHandler.java` | `service/article/src/main/java/com/rag/article/advice/ArticleApiExceptionHandler.java` | 1:1 | 框架级 400 / 405 / 415 → `text/plain`（B 侧 `article-api/…/advice/GlobalExceptionHandler.java:34-52` 自记：article-api **未注册**错误信封处理器 ⇒ 解析失败与上传失败都走 400 + `text/plain`） |
| `article-api/…/interceptor/JwtAuthInterceptor.java` | `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java` | 1:1 | 验签 + Redis blacklist + public 前缀放行；**需独立 Bean**（密钥 / public 路径与 users 域不同）；⚠️ **本域无 Redis 黑名单**（B 侧 `routes.go` 未挂 `rest.WithMiddlewares`）⇒ C 侧**不补**黑名单分支 |
| `article-api/…/auth/GoStyleJwtAuthFilter.java` | `service/article/src/main/java/com/rag/article/interceptor/ArticleJwtAuthFilter.java` | 1:1 | ✅ **已落地（2026-11-03）** —— B 侧「验签拆两段」（`DRIFT-USERAPI-009②` 的修法）：验签在 **Servlet Filter**（70s 预算**之外**）＋ 黑名单/响应在 **HandlerInterceptor**（预算**之内**）⇒ C 侧补上 `OncePerRequestFilter` ⇒ **该缺口在 article 域闭环**；⚠️ 共享层 Filter 槽位取 **+6**（C 侧共享层已有 +2 PathNormalizing / +3 Traceparent / +4 MaxConns / +5 RouteIsolation / +6 CpuShedding / +7 HttpMiddleware / +8 MaxBytes / +9 JsonBodyLimit ⇒ 本域必须排在 CpuShedding 之后、HttpMiddleware 之前）|

### 1.2 rpc 侧（领域 / 服务端能力层）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `article-rpc/…/ArticleRpcApplication.java` | `service/article/src/main/java/com/rag/article/ArticleServiceApplication.java` | N:1 | **D1**：与 `article-api/…/ArticleApiApplication.java` 合并；四件套「同启同停」交给 Spring 容器生命周期 |
| `article-rpc/…/config/ArticleRpcProperties.java` | `service/article/src/main/java/com/rag/article/config/ArticleRpcProperties.java` | 1:1 | `@ConfigurationProperties` 聚合；字段逐条对应 |
| `article-rpc/…/config/ArticleKafkaChannelValidator.java` | `service/article/src/main/java/com/rag/article/config/ArticleKafkaChannelValidator.java` | 1:1 | 🔴 校验 4 通道 brokers 与全局 `spring.kafka.bootstrap-servers` 一致，不一致**启动失败**（B 侧改某通道 brokers 会切 broker） |
| `article-rpc/…/config/ArticleMetaObjectHandler.java` | `service/article/src/main/java/com/rag/article/config/ArticleMetaObjectHandler.java` | 1:1 | MyBatis-Plus `MetaObjectHandler` 复刻上游 ORM 的 `autoUpdateTime` 消费点（B 侧本身即 MyBatis-Plus；`article-rpc/…/config/ArticleMetaObjectHandler.java:13-14` 自记该复刻关系与逐条等价论证） |
| `article-rpc/…/bootstrap/SnowflakeBootstrap.java` | `service/article/src/main/java/com/rag/article/wiring/SnowflakeBootstrap.java` | 1:1 | 位宽/epoch 与 B 侧一致（`common/…/snowflake/`） |
| `article-rpc/…/bootstrap/MinioBucketBootstrap.java` | `service/article/src/main/java/com/rag/article/wiring/MinioBucketBootstrap.java` | 1:1 | 幂等语义保留；`MakeBucket` 失败不 panic，只 warn |
| `article-rpc/…/bootstrap/NacosRegistration.java` | `service/article/src/main/java/com/rag/article/wiring/NacosRegistration.java` | 1:1 | 显式注册，不依赖自动注册 |
| `article-rpc/…/bootstrap/ArticleSchemaGate.java` | `service/article/src/main/java/com/rag/article/wiring/ArticleSchemaGate.java` | 1:1 | 🔴 缺触发器必须 **fail-fast**（静默降级会让「不可变 revision」变成可写） |
| `article-rpc/…/client/MessageRpcClient.java` | `service/article/src/main/java/com/rag/article/client/MessageRpcClient.java` | 1:1 | **唯一的跨域 gRPC 依赖**（D6） |
| `article-rpc/…/client/ArticleObjectStorageClient.java` | `service/article/src/main/java/com/rag/article/client/ArticleObjectStorageClient.java` | 1:1 | ✅ **已落地（2026-11-01）**：C 侧 `client/` 包补上对象存储客户端 ⇒ **缺口 G-3 关闭**（MinIO put/get 有了载体）；⚠️ MinIO **必须排除 Jackson 2**（D12 · `docs/MinIO-Jackson2-Isolation-Experiment.md` 已实测可行） |
| `article-rpc/…/client/SecurityRpcClient.java`（+ `ContentSecurityPort.java` / `ContentSecurityAutoConfiguration.java` / `UnavailableContentSecurityClient.java`） | `service/article/src/main/java/com/rag/article/client/ContentAuditPort.java` + `service/article/src/main/java/com/rag/article/client/UnavailableContentAuditClient.java` + `service/article/src/main/java/com/rag/article/client/ContentAuditAutoConfiguration.java` | N:1 | ⚠️ **RAG-V1 迁移范围排除 `security` 域**（AGENTS.md §1.0）⇒ **不建 `SecurityRpcClient`**（无对端）；改为**端口 + 兜底实现 + 装配点**（`ContentSecurity*` → `ContentAudit*`，去 `Security` 域名含义）。⚠️ **fail-loud 语义必须保留**：B 侧 `article-rpc/…/mq/ArticleReviewConsumer.java` 是 `SecurityRpc == nil ⇒ return fmt.Errorf("content security client not initialized")` ⇒ `UnavailableContentAuditClient` **抛错而非放行**；⇒ **内容安全在 RAG-V1 恒不可用 ⇒ 审核链路恒 fail-loud** ⇒ 已登记为签字偏离（`docs/Intentional_Deviations/`） |
| `article-rpc/…/client/ImageSecurityRpcClient.java` | **无对应文件** | 1:1 | ⚠️ **有意不建**：RAG-V1 迁移范围排除 `security` 域 ⇒ **无对端** ⇒ 不建跨域客户端（B 侧 `article-rpc/…/mq/ArticleReviewConsumer.java` 的 `DetectImageAd` 调用随之**无对端** ⇒ 图片广告检测这一腿在 RAG-V1 恒不可用 ⇒ 同属上面那条签字偏离） |
| `article-rpc/…/service/impl/ArticleServiceGrpcImpl.java` | `service/article/src/main/java/com/rag/article/grpc/ArticleGrpcService.java` | 1:1 | `@GrpcService`；**proto 方法签名是 L1，一个字节都不改** |
| `article-rpc/…/service/ArticleDomainService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` | N:1 | 6 个 rpc Service 合 1 |
| `article-rpc/…/service/ArticleDomainService.java` + `article-rpc/…/service/ArticleAtomicService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` + `service/article/src/main/java/com/rag/article/domain/service/ArticleAtomicService.java` | N:1 | **多步事务必须独立 Bean**（`@Transactional` self-invocation 会失效） |
| 同上（`ArticleAtomicService`） | 同上 | N:1 | ⚠️ **不删 MinIO 对象**（保留期未定） |
| `article-rpc/…/service/ArticleDomainService.java` + `article-rpc/…/service/ArticlePublicDomainService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` + `service/article/src/main/java/com/rag/article/domain/service/ArticlePublicDomainService.java` | N:1 | 作者视角 vs 公开视角是**不同持久化语义**（读冻结 revision vs 读当前草稿） |
| 同上（`ArticlePublicDomainService`） | 同上 | N:1 |  |
| `article-rpc/…/service/ArticleDomainService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleDomainService.java` | N:1 |  |
| `article-rpc/…/domain/ArticleEntity.java` | `service/article/src/main/java/com/rag/article/domain/entity/ArticleEntity.java` | 1:1 | `@TableName("article")`；列类型 / 可空性 / 索引逐列对拍 |
| `article-rpc/…/mapper/ArticleMapper.java` | `service/article/src/main/java/com/rag/article/mapper/ArticleMapper.java` | 1:1 | `BaseMapper` + `@Select` / `@Update` |
| `article-rpc/…/mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbValueTypeHandler.java` | 1:1 | ✅ **D-11 已收口（2026-11-03）** —— 原先 C 侧落**域内** `mapper/JsonbTypeHandler.java`（域内副本），因共享层 `common/persistence/JsonbTypeHandler` 是 `extends BaseTypeHandler<String>`（jsonb ↔ **文本**），而 B 侧本类要 `List<String>`（`secondary_tags`）**与** `Map<String,String>`（`ext_info`）**两种**形态 ⇒ 现**共享层新增 `JsonbValueTypeHandler`**（照抄 B 侧 195 行原类，代码体逐行一致）＋ **域内副本已删** ⇒ 副本数 1 → 0；⚠️ **类名改了**（共享层已有同名 `JsonbTypeHandler` ⇒ 撞车）⇒ `JsonbValueTypeHandler`（**Value = jsonb 列的结构化值**）；⚠️ **三处带 `"article"` 前缀的异常文案逐字保留**（L1 以 B 侧为准）|
| `article-rpc/…/mapper/ArticlePublicMapper.java` | `service/article/src/main/java/com/rag/article/mapper/ArticlePublicMapper.java` | 1:1 | **公开可见谓词是独立 SQL 资产**（含 legacy `status=2` 回退 + revision 标签过滤） |
| `article-rpc/…/domain/ArticleSyncOutboxEntity.java` + `article-rpc/…/mapper/ArticleSyncOutboxMapper.java` | `service/article/src/main/java/com/rag/article/domain/entity/ArticleSyncOutboxEntity.java` + `service/article/src/main/java/com/rag/article/mapper/ArticleSyncOutboxMapper.java` | 1:N | B 侧实体与 Mapper 分列 2 个文件 ⇒ C 侧同样拆 2 个（实体 + Mapper）；`event_key` 唯一键保留 |
| `article-rpc/…/domain/ArticleRevisionEntity.java` + `domain/ArticlePublicationEntity.java` + `domain/ArticleDomainOutboxEntity.java` | `service/article/src/main/java/com/rag/article/domain/entity/ArticleRevisionEntity.java` + `service/article/src/main/java/com/rag/article/domain/entity/ArticlePublicationEntity.java` + `service/article/src/main/java/com/rag/article/domain/entity/ArticleDomainOutboxEntity.java` | 1:N | 三表持久化用途不同 ⇒ 三实体分开 |
| `article-rpc/…/mapper/ArticleRevisionMapper.java` + `mapper/ArticlePublicationMapper.java` + `mapper/ArticleDomainOutboxMapper.java` | `service/article/src/main/java/com/rag/article/mapper/ArticleRevisionMapper.java` + `service/article/src/main/java/com/rag/article/mapper/ArticlePublicationMapper.java` + `service/article/src/main/java/com/rag/article/mapper/ArticleDomainOutboxMapper.java` | 1:N | `article_domain_outbox` 的 `UNIQUE(aggregate_id, aggregate_version)` 是事件顺序保证，**不可省** |
| `article-rpc/…/service/ArticleAtomicService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleAtomicService.java` | 1:1 | B 侧事务闭包 → `@Transactional` + Mapper `last("FOR UPDATE")` |
| `article-rpc/src/main/resources/db/migration/V8__create_article.sql` / `V9__create_article_sync_outbox.sql` / `V10__create_article_community_revision.sql` | `service/article/src/main/resources/db/migration/V8__create_article.sql` / `V9__create_article_sync_outbox.sql` / `V10__create_article_community_revision.sql` | 1:1 | ✅ **已落地（2026-11-03）** —— **逐字节照抄** B 侧（`diff -q` ⇒ IDENTICAL）⇒ **缺口 G-5 关闭**；⚠️ **不重编号**（B 侧就是 V8/V9/V10 ⇒ 重编号会与 B 侧账本错位）；⚠️ 独立账本表名 `flyway_schema_history_article` + `baseline-on-migrate: true` + **`baseline-version: 0` 必钉**（schema 级账本共用会撞号，AGENTS.md §六）|
| `article-rpc/…/mq/ArticleReviewConsumer.java` | `service/article/src/main/java/com/rag/article/messaging/ArticleReviewConsumer.java` | 1:1 | ✅ **已落地（2026-11-01）**：C 侧新建 `messaging/` 包 ⇒ **缺口 G-1 关闭**；⚠️ 其内部的内容安全调用走 `ContentAuditPort`（恒 fail-loud，见上） |
| `article-rpc/…/mq/ArticleSyncResultConsumer.java` | `service/article/src/main/java/com/rag/article/messaging/ArticleSyncResultConsumer.java` | 1:1 | ✅ **已落地（2026-11-01）**：同上，落 `messaging/` |
| `article-rpc/…/mq/ArticleSyncOutboxRelay.java` | `service/article/src/main/java/com/rag/article/messaging/ArticleSyncOutboxRelay.java` | 1:1 | ✅ **已落地（2026-11-01）**：同上，落 `messaging/`；⚠️ `article_domain_outbox` 的 `UNIQUE(aggregate_id, aggregate_version)` 是事件顺序保证，**不可省** |
| `article-rpc/…/service/ArticleAtomicService.java` | `service/article/src/main/java/com/rag/article/domain/service/ArticleAtomicService.java` | 1:1 | 事务载体（`FOR UPDATE` 取指针 + 写 retract 域事件，单事务） |
| `article-rpc/…/metrics/ArticleRpcMetrics.java` | `service/article/src/main/java/com/rag/article/metrics/ArticleRpcMetrics.java` | 1:1 | 指标**完整名**带 `article_service_` 前缀（B 侧 `NAMESPACE`，见 §6）；`article_status_total` 只有 `reviewing` 有调用点，**不许补全** |

### 1.3 契约模块（proto + 错误契约）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `article-common/…/errmsg/ErrorCode.java` | `service/article/src/main/java/com/rag/article/contract/ErrCodes.java` + `service/article/src/main/java/com/rag/article/contract/ErrMsg.java` | 1:N | C 拆「码值表」与「文案表」两个类；⚠️ **错误契约逐域隔离，不可跨域统一**（article 的 5004=文件上传失败 / 5005=文件删除失败 / 5006=文件下载失败，与 users 域同值不同义）；⚠️ **本文档原先把落点误写成 `service/users/common/…`（2026-11-03 已订正）** —— article 的错误码**不**进 `users-common`，落**域内 `contract/`** |
| `article-rpc/…/mq/ArticleSyncEventTypes.java` | `service/article/src/main/java/com/rag/article/contract/ArticleSyncEvent.java` | 1:N | ✅ **已闭合（2026-11-03）** —— C 侧 **1 个顶层 record** 承载 ⇒ 内嵌 `ArticleReviewMessage` / `ArticleSyncResult` 两个嵌套 record ⇒ **缺口 G-6 关闭** |
| `article-rpc/…/mq/ArticleSyncEventTypes.java`（payload 部分）+ `service/common/…/communityfact/Wire.java`（信封部分） | `service/article/src/main/java/com/rag/article/contract/CommunityArticleEvent.java` + `common/src/main/java/com/rag/common/communityfact/Event.java` | 1:N | 信封（`event_id`/`event_type`/`schema_version`/`producer`/`aggregate_id`/`aggregate_version`/`operation_id`/`occurred_at`/`payload`）是 **L1**；payload 是 article 特有 ⇒ 域内契约模块 |
| `article-proto/src/main/proto/article.proto` | `proto/src/main/proto/article.proto` | 1:1 | **message 名 / 字段名 / 字段号 / 类型 / 枚举值逐条一致**；**B↔C 的 diff 只差 `java_package` 一行**（B = `com.rag.article.article.proto` ⇒ C = `com.rag.proto.article`，实测 diff 仅此一行）—— 两侧 option 块都只有 `java_package` + `java_multiple_files` |
| `service/article/article-proto/src/main/proto/`（protoc 插件段） | `proto/src/main/proto/`（protoc 插件段） | 1:1 | 生成物，非手写源码；⚠️ **「有 `.proto`」≠「生成了代码」**（插件段必须接上） |
| （无独立文件；`service/article/article-api/…/client/ArticleRpcClient.java` 直接用 devh `@GrpcClient` 的生成 stub） | （无独立文件；`proto/` 生成 stub） | 1:1 | 两侧都不生成手写桩 |

### 1.4 B 侧亦无独立对应物的项（原因）

> ⚠️ **本清单原先按「另一侧实现的文件」逐条列举**；那一侧已随对照表精简移除 ⇒ 改为**按 B 侧自身形态**记录「B、C 两侧都没有独立文件」的项。

| B 侧情况 | 原因 |
|---|---|
| `article-api` 侧**无** `SecurityRpc` 客户端（只 wire 不调） | **api 零调用点** ⇒ 死配置。B、C 两侧均不迁移 |
| `article-rpc` 侧**无** `HotEventPusher` | **零调用点**（只在装配里 new 出来）⇒ 死配置。B、C 两侧均不迁移；topic `article-hot-events` 仅在 yaml 里出现 |
| `article-proto`（protoc 生成物，无手写客户端桩） | 生成物 + 客户端桩 ⇒ 两侧都改用 starter 的生成 stub，无独立文件（见 §1.3 契约模块两行） |

### 1.5 C 侧职责缺口清单（B 有、C 无）—— ✅ 6/6 已闭合（2026-11-03）

> ⚠️ 这些是**填实现前必须先解决**的位置，否则 C 侧会静默丢职责。
> 📌 **本表状态列已按 2026-11-03 的实际落地情况复核**（核对日期见表内）。

| # | 缺口 | B 列载体 | 后果 | 状态（2026-11-03） |
|---|---|---|---|---|
| **G-1** | **无 `mq/` 包** —— 审核消费者 / 同步结果消费者 / outbox relay 三个消息入口无载体 | `mq/ArticleReviewConsumer.java` · `mq/ArticleSyncResultConsumer.java` · `mq/ArticleSyncOutboxRelay.java` | 审核链路、发布落地链路、outbox 投递链路**整条丢失** | ✅ **已闭合** —— C 侧 `messaging/` 3 个类落地（`ArticleReviewConsumer` / `ArticleSyncOutboxRelay` / `ArticleSyncResultConsumer`）|
| **G-2** | **无 `UserRpcClient`** —— 作者名补全无跨域出口 | `article-api/…/client/UserRpcClient.java` + `service/ArticleAuthorResolver.java` | 文章列表 / 详情**没有 `author_name`**；且 C 侧 `ArticleAuthorResolver` 这个独立职责点也不存在 | 🟠 **部分闭合 · 签字偏离** —— 本仓**不含 users 域**，跨域出口无对端 ⇒ 走 B 侧 `resolveAuthorName` 的**失败分支**（恒回落 `"用户 <id>"` / `"未知作者"`）⇒ ⚠️ **刻意不选「干脆不输出 `author_name`」**（那样连**键存在性**都变了，差异面更大）⇒ 登记为签字偏离 |
| **G-3** | **无对象存储客户端** —— MinIO put/get 无载体 | `article-rpc/…/client/ArticleObjectStorageClient.java` | 上传、正文存取、哈希对象名全部无载体（只剩 `MinioBucketBootstrap` 建桶） | ✅ **已闭合** —— C 侧 `client/ArticleObjectStorageClient.java` 落地 |
| **G-4** | **无内容安全 / 图片安全客户端** | `client/SecurityRpcClient.java` · `client/ImageSecurityRpcClient.java` | 审核四元组里的两项检查无处执行 | 🟠 **有意不建 · 签字偏离** —— 迁移范围排除 `security` 域 ⇒ 改为 `ContentAuditPort` + `UnavailableContentAuditClient`（**恒抛 · fail-loud**）+ `ContentAuditAutoConfiguration` ⇒ 审核链路**恒 fail-loud**（B 侧 `SecurityRpc == nil` 分支的等价物）⇒ 登记 `docs/Intentional_Deviations/article/content-audit-fail-loud.md` |
| **G-5** | **DDL 未落地** —— `db/migration/` 只有 `.gitkeep` | Flyway `V8` / `V9` / `V10` | 五张表 + 不可变触发器 + CHECK 约束全部缺失；`ArticleSchemaGate` 会 **fail-fast 起不来** | ✅ **已闭合** —— V8/V9/V10 **逐字节照抄** B 侧（`diff -q` ⇒ IDENTICAL）|
| **G-6** | **`ArticleReviewMessage` / `ArticleSyncResult` 无载体** | `mq/ArticleSyncEventTypes.java` | 审核消息与同步结果两个 wire 载荷在 C 侧无处安放 | ✅ **已闭合** —— C 侧 `contract/ArticleSyncEvent.java`（**顶层 record** + 嵌套 `ArticleReviewMessage` / `ArticleSyncResult`）|

> 本节决策锁：**L-1 / L-2**（详见 §5）。

### 1.6 🔴 RAG 侧新增类补录（2 条 · 覆盖 5 个 `.java`）

> ⚠️ **本节是 2026-11-01 的增量**：RAG 在首版映射表之后新增了 `article-api/…/auth/` 包（4 类）与 `ArticleSpans`（1 类）
> ⇒ 首版 §1.1 / §1.2 的 **B 列漏了这 5 个 `.java`** ⇒ 本节逐个落到 C 侧；
> 补齐后 `tools/check-mapping-coverage.py` 的 **B 列双向对账归零**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `article-api/…/auth/ArticleJwtProtectedRoutes.java` ＋ `article-api/…/auth/ArticleJwtSignatureVerifier.java` ＋ `article-api/…/auth/ArticleJwtVerificationResult.java` ＋ `article-api/…/auth/GoStyleJwtAuthFilter.java` | `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java`（HandlerInterceptor 段）＋ `service/article/src/main/java/com/rag/article/interceptor/ArticleJwtAuthFilter.java`（Servlet Filter 段） | N:1 | RAG 把 JWT **验签**拆成两段（`DRIFT-USERAPI-009②` 的修法）：验签在 **Servlet Filter**（3s 预算**之外**）＋ 黑名单/响应在 **HandlerInterceptor**（预算**之内**）⇒ ✅ **2026-11-03 已补 `ArticleJwtAuthFilter`（`OncePerRequestFilter`）承载验签** ⇒ **该缺口在 article 域闭环**；⚠️ **受保护路由表 ＋ public 前缀集合是 L1**（逐条一致，不可凭直觉改）|
| `article-rpc/…/service/ArticleSpans.java` | `service/article/src/main/java/com/rag/article/metrics/ArticleRpcMetrics.java` | 1:1 | B 侧把 span 名常量从指标类里**拆出** ⇒ C 侧并回 `ArticleRpcMetrics`；⚠️ 指标**完整名**带 `article_service_` 前缀（B 侧 `article-rpc/…/metrics/ArticleRpcMetrics.java:59` 的 `NAMESPACE`），`article_status_total` 只有 `reviewing` 有调用点，**不许补全** |

---

## 2. 持久层差异

| 维度 | B (RAG, MyBatis-Plus) | C (RAG-V1, MyBatis-Plus) | 备注 |
|---|---|---|---|
| ORM 注解 | `@TableName` + `@TableId(type=ASSIGN_ID)` + `@TableField` | 同 B | 表名显式声明 |
| 数据访问 | `Mapper extends BaseMapper<Entity>` + `@Select` / `@Update` | 同 B | 查询条件必须逐条保留 |
| 复合 SQL 谓词 | `ArticlePublicMapper` 的 `@Select` 原样 SQL | 同 B | 公开可见谓词 = 独立 SQL 资产 |
| jsonb 列 | `mapper/JsonbTypeHandler.java`（`StringListTypeHandler` + `StringMapTypeHandler`） | `common/src/main/java/com/rag/common/persistence/JsonbValueTypeHandler.java`（**共享层**，结构与 B 侧同构） | ✅ **D-11 已收口（2026-11-03）** —— 原先是域内副本（共享层 `common/persistence/JsonbTypeHandler` 是 `BaseTypeHandler<String>`（jsonb ↔ **文本**）⇒ **类型不兼容**）⇒ 现上收到共享层 `JsonbValueTypeHandler`、**域内副本已删** ⇒ 副本数 1 → 0 |
| Schema 演进 | Flyway `V8` / `V9` / `V10` + `ArticleSchemaGate` | Flyway `V8` / `V9` / `V10`（**逐字节照抄**）+ `wiring/ArticleSchemaGate.java` | ✅ **已落地（2026-11-03）**；⚠️ 独立账本 `flyway_schema_history_article` + `baseline-on-migrate: true` + `baseline-version: 0` |
| 不可变触发器 | Flyway `V10` | Flyway `V10`（**逐字节照抄**） | ✅ 触发器已落地 ⇒ `ArticleSchemaGate` 的 fail-fast 前提成立 |
| 事务边界 | `ArticleAtomicService` 的 `@Transactional` | 同 B | ⚠️ **必须独立 Bean**，否则 self-invocation 使 `@Transactional` 失效 |
| 行级悲观锁 | Mapper `last("FOR UPDATE")` | 同 B | 同 article 的指针推进 |
| 只读快照 | `ArticlePublicMapper` + 只读事务 | 同 B | ⚠️ 拆开就读到不一致快照 |
| 时间戳推进 | `ArticleMetaObjectHandler` | 同 B | ⚠️ `UpdateColumn`（`SkipHooks=true`）**不推进** —— 原生 `@Update` SQL 天然绕开 MP |
| 软删 | `ArticleEntity` 的逻辑删除 | 同 B | |
| 雪花 ID | `wiring/SnowflakeBootstrap.java` + `common/…/snowflake/` | 同 B（+ starter 的 `SnowflakeAutoConfiguration`） | epoch / 位段是业务契约 |
| 表名 | `article` · `article_sync_outbox` · `article_revision` · `article_publication` · `article_domain_outbox` | 同 | `article_sync_outbox` 有显式 `TableName()`（B 侧 `ArticleSyncOutboxEntity.java:22`） |

> 本节决策锁：**L-3**（详见 §5）。

---

## 3. HTTP / DI 差异

| 维度 | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|
| 路由注册 | `@RequestMapping("/v1")` + 6 个 `@*Mapping` | 同 B | **代码注册 → 注解声明**；前缀 `/v1` 是 L1 |
| HTTP method | **混合**：Get3 · Post2 · Put1 · Delete1（**不是**统一 POST） | 同 B | ⚠️ users 域的「统一 POST」是**已签字偏离**，article 域**不适用** |
| 端点清单 | `POST /v1/article` · `PUT /v1/article/:id` · `DELETE /v1/article/:id` · `GET /v1/me/article/:id` · `GET /v1/article/:id` · `GET /v1/articles` · `POST /v1/upload` | 同 B | ⚠️ C 侧 `ArticleController` 的 TODO javadoc **写错了 5 条路径**（见 §7） |
| 鉴权分组 | `JwtAuthInterceptor` + public 前缀放行 | 同 B | **有 public 段**（JWT 只覆盖一组） |
| 上传上限 | `spring.servlet.multipart.max-file-size` | 同 B | 配置替代 API |
| 调用超时 | stub deadline | 域内直连后 **不再是网络超时** ⇒ 语义位归位 | 合并后 create 不再跨网络 |
| Handler / Controller | 1 个 `ArticleController`（7 个 `@*Mapping`） | 同 B | 薄适配层同质 ⇒ 合 1 |
| Logic 中间层 | 7 个 `*Service` + `ArticleApiSupport` + `ArticleAuthorResolver` | **1 个 `ArticleApiService` + 1 个 `ArticleApiSupport`** | D2 + §5.4「用例内聚」五问判据 |
| 错误表达（业务失败） | `advice/GlobalResponseAdvice` 同一形状 | `service/article/src/main/java/com/rag/article/advice/ArticleApiResponseAdvice.java` | 信封是 L1 |
| 错误表达（裸响应） | `ResponseEntity<UploadImageResp>` 绕过 advice | 同 B | ⚠️ advice 必须排除 `/upload` |
| 错误表达（框架级） | `GoStyleRouterErrorResponses`（共享层） | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` | D3 收进共享层 |
| gRPC code → 业务码 | `GoStyleGrpcStatusMapper` + `GlobalExceptionHandler` | `service/article/src/main/java/com/rag/article/advice/ArticleApiExceptionHandler.java` | D2 后 **同一份逻辑要能同时产出 HTTP 与 gRPC 两种错误表达** |
| JWT 校验 | `JwtAuthInterceptor` | `service/article/src/main/java/com/rag/article/interceptor/JwtAuthInterceptor.java` | **claim 名 = `userId`**（L1） |
| 配置绑定 | `@ConfigurationProperties` | `service/article/src/main/java/com/rag/article/config/ArticleApiProperties.java` + `service/article/src/main/java/com/rag/article/config/ArticleRpcProperties.java` | 类型安全；两套配置块本就不同 ⇒ 仍分两个类 |
| ServiceContext | **删除**（Spring DI 接管） | **删除** | 无业务方法 ⇒ 不生成文件 |
| 进程拓扑 | 2 进程 | **1 进程**（D1） | 「rpc 进程不可用」这一故障模式消失 |

> 本节决策锁：**L-4 / L-5 / L-6**（详见 §5）。

---

## 4. 公共模块对照（article 域与 `common/` 的边界）

> 判据：**跨域共享 ⇒ `common/`；域内专有 ⇒ 留在 `article-service` 的域内 `contract/` 包。**

| 职责 | B (RAG) | C (RAG-V1) | 归属 |
|---|---|---|---|
| HTTP 信封 | `service/common/…/response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | **共享层**（L1，跨 10 域） |
| 业务异常载体 | `service/common/…/exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | **共享层**（D2 合并后统一抛） |
| 错误码 / 文案 | `article-common/…/errmsg/ErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` + `ErrMsg.java` | **域内**（逐域隔离，不可跨域统一） |
| gRPC 异常映射 | 无统一件 | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | **共享层**（D2 的关键设计点） |
| JWT 工具 | `service/common/…/jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` + `BearerTokenExtractor.java` | **共享层** |
| 雪花 ID | `service/common/…/snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | **共享层**（epoch / 位段是业务契约） |
| jsonb TypeHandler | `article-rpc/…/mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbValueTypeHandler.java` | **共享层**（D3 跨域去重）· ✅ **D-11 已收口（2026-11-03）**；⚠️ **本行旧版写 `common/persistence/JsonbTypeHandler.java` = 错映射**（那份是 **jsonb ↔ 文本**，与 B 侧本类的 jsonb ↔ List/Map **类型不兼容**）⇒ 已订正 |
| community 事件信封 | `service/common/…/communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/Event.java` + `Wire.java` | **共享层**（comment / favorite / like / article 共用） |
| community 域事件 payload | `article-rpc/…/mq/ArticleSyncEventTypes.java` | `service/article/src/main/java/com/rag/article/contract/CommunityArticleEvent.java` | **域内**（article 特有 payload） |
| MQ 事件载荷 | `article-rpc/…/mq/ArticleSyncEventTypes.java` | `service/article/src/main/java/com/rag/article/contract/ArticleSyncEvent.java` | **域内契约**（跨服务共享 ⇒ 放 contract 模块） |
| 运行时语义补齐（熔断 / CPU 降载 / MaxBytes / 路由错误形状 / Jackson 强转） | `service/common/protection/`（17 类）+ `web/`（9 类） | `common/src/main/java/com/rag/common/protection/`（2 类）+ `common/src/main/java/com/rag/common/web/`（17 类） | **共享层**（D3 + D4） |
| 观测 | `service/common/…/observability/` | `common/src/main/java/com/rag/common/observability/`（`Observation` + `GoStyleRequestDeadline`） | **共享层** |
| 业务规则 | 各域 `service/` | 各域 `service/`（`ArticleDomainService` / `ArticlePublicDomainService` / `ArticleAtomicService`） | **域内**（不共享） |

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

**为什么换**：Spring Cloud 的部署单元是「可部署服务」，不是「api 层 / rpc 层」；B 侧的 api/rpc 分离源于 `.api` 文件与 `.proto` 两条 codegen 路径，是**语言产物**不是架构原则。合并后「rpc 进程不可用」这一故障模式直接消失。

### L-2 · 域内调用直连，删除自我 gRPC 往返（**D2**）— 5 处同时改

1. 删 `article-api/…/client/ArticleRpcClient.java`（域内不再走网络）
2. `ArticleApiService` 直接注入 `ArticleDomainService` / `ArticlePublicDomainService`
3. `ArticleCreateService` 的 70s 调用超时改为**领域层事务超时**语义（不再是网络 deadline）
4. api 侧的「gRPC code → 业务码」5 分支映射表删除；业务层直接抛 `BusinessException(code,msg)`，由 `ArticleApiExceptionHandler`（HTTP 边界）与 `RagGrpcExceptionMapper`（gRPC 边界）各自适配
5. `ArticleApiSupport` 的 proto → DTO 投影逻辑删除（不再有 proto 中转）

**为什么换**：同进程内调 gRPC 是无意义的序列化开销与故障注入。代价是**同一份业务逻辑要能同时产出 HTTP 与 gRPC 两种错误表达** ⇒ 统一错误模型（`BusinessException` + 两个边界适配器）是 D2 的前置条件。

### L-3 · 持久层载体归位（含 jsonb 去重与 DDL 补齐）— 6 处同时改

1. `article-rpc/…/mapper/JsonbTypeHandler.java` 删掉，改注入 `common/src/main/java/com/rag/common/persistence/JsonbValueTypeHandler.java`（✅ **2026-11-03 D-11 已落地**：共享层新增该类 ＋ 域内副本删除）
2. `ArticleEntity` 的 `secondary_tags` / `ext_info` 两列挂上共享 TypeHandler
3. `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` 补 `article` 域需要的「数组 + 对象」两种形态（共享层已承载其它域）
4. **补齐 `db/migration/`**：五张表 + `article_revision_immutable` 触发器 + 两处 CHECK 约束（缺口 G-5）
5. `ArticleSchemaGate` 的断言从「触发器存在」扩展到「五张表 + 触发器全部存在」
6. `ArticleAtomicService` 的 3 处多步事务逐条核对：`@Transactional` 必须落在**独立 Bean 的 public 方法**上

**为什么换**：jsonb TypeHandler 是跨域同构件，按 D3 应收进 starter 层去重；DDL 缺失会让 `ArticleSchemaGate` 直接 fail-fast 起不来，是 C 侧最先会暴露的问题。

### L-4 · 7 个薄 handler → 1 个 Controller（**注解式路由**）— 3 处同时改

1. B 侧已是 1 个 `ArticleController` 承载 7 个 `@*Mapping` ⇒ C 侧沿用同一形态（无需再拆薄 handler）
2. `@RequestMapping("/v1")` + `POST /article` / `PUT /article/{id}` / `DELETE /article/{id}` / `GET /me/article/{id}` / `GET /article/{id}` / `GET /articles` / `POST /upload` —— **method 与路径逐条照抄 B 侧，不统一成 POST**
3. `POST /upload` 的 multipart 字段名固定 `image`（`@RequestParam("image") MultipartFile`），并用 `ResponseEntity<UploadImageResp>` 绕过信封 advice

**为什么换**：B 侧已是 Spring 注解声明式路由；注解式更直观且与 Swagger 自动集成。⚠️ **method 混合是 B 侧原样**——users 域的「统一 POST」是已签字偏离，article 域不适用。

### L-5 · 7 个 logic → 1 个 `ArticleApiService`（**用例内聚**）— 4 处同时改

1. 删 `ArticleCreateService` / `ArticleGetService` / `ArticleGetAuthorService` / `ArticleListService` / `ArticleUpdateService` / `ArticleDeleteService` / `ArticleUploadService` 七个类
2. 七个用例的方法体并入 `ArticleApiService`（**方法名与语义保留**：公开详情 / 作者详情是两个方法，不是两个类）
3. B 侧 `ArticleApiSupport` 的 uid 提取 / proto→DTO 投影 / 防御性强转并入 C 侧 `ArticleApiSupport`；作者名补全（**吞错**策略）并入同一处或独立 `ArticleAuthorResolver`（取决于 G-2 是否补齐）
4. `ArticleApiResponseAdvice` 加**排除项**：`/v1/upload` 不走信封

**为什么换**：B 侧 7 个 `*Service` 是 codegen 模板按 RPC 生成的**模板产物**，不是职责边界。五问判据（同一入口聚合 / 一致的变化原因 / 无独立生命周期 / 无独立契约 / 合并后职责不模糊）全中 ⇒ 合并。⚠️ 但**作者名补全的容错策略是显式业务决策**，若 G-2 补齐则应独立成 `ArticleAuthorResolver`。

### L-6 · 删除 `ServiceContext`（static → Spring Bean）— 4 处同时改

1. 删 B 侧 static `ServiceContext` 的等价物（B 侧已无 `ServiceContext.java`；C 侧本就无对应文件）
2. 每个 static 字段对应一个 `@Component` / `@Bean`：`ArticleRpcProperties` / `ArticleApiProperties` / `MessageRpcClient` / 对象存储客户端 / snowflake Bean
3. 调用点从 static 字段直取 → `@Autowired XxxClient; client.yyy(...)`
4. 入口从 static 装配改成 `@SpringBootApplication` 自动扫描装配

**为什么换**：static 全局变量在测试和多进程时难处理；Spring Bean 单例 + 依赖注入更标准。

### L-7 · 错误契约逐域隔离（**不建共享错误码基座**）— 3 处同时改

1. `service/article/src/main/java/com/rag/article/contract/ErrCodes.java` 的码值**逐条照抄** B 侧 `article-common/…/errmsg/ErrorCode.java`（15 个常量）
2. `service/article/src/main/java/com/rag/article/contract/ErrMsg.java` 的文案**逐条照抄** B 侧 `ErrorCode.java` 的枚举内嵌文案（中文；article / comment / favorite / like / user 的 `CodeServerBusy` 是中文，follow / hot / message 是英文 —— article 是中文）
3. `GetErrMsg` 的未知码回落语义（回落 `"FAIL"`，不是 `null` / 空串 / code 字符串）落到 `ErrMsg` 的查表方法

**为什么换**：8 个域的码值与文案**各不相同**（article 的 5004/5005/5006 是「文件上传/删除/下载失败」，users 的同值是「数据库插入失败 / 事务操作失败 / 雪花ID生成失败」）。合并成一个共享基座会改掉 8 个域的可见行为（L1）。

---

## 6. 跨模块全局约定（值逐条核对 B 侧源码）

> ⚠️ **本表的每个值都来自 B 侧源码**（RAG 仓 `service/article/`），逐条抄出，不是推测。
> 出处列写 **B 仓相对路径:行号**（RAG 仓）。

| 项 | 值 | B 侧出处（B 仓相对路径:行号） |
|---|---|---|
| **HTTP 前缀** | `/v1` —— **不带域名前缀**（全仓唯一） | `article-api/…/controller/ArticleController.java:80`（`@RequestMapping("/v1")`） |
| **HTTP method 分布** | **Get3 · Post2 · Put1 · Delete1**（7 端点）—— **不是**统一 POST | `article-api/…/controller/ArticleController.java:123,137,152,167,181,201,237`（7 个 `@*Mapping`） |
| **端点清单** | `POST /v1/article` · `PUT /v1/article/:id` · `DELETE /v1/article/:id` · `GET /v1/me/article/:id` · `GET /v1/article/:id` · `GET /v1/articles` · `POST /v1/upload` | 同上 |
| **鉴权分组** | 组1 = JWT（4 端点）· 组2 = public 无中间件（2 端点）· 组3 = 上传体积上限（1 端点） | `article-api/…/auth/ArticleJwtProtectedRoutes.java:17-30`（三段路由分组） |
| **JWT claim** | `userId` | `article-api/…/interceptor/JwtAuthInterceptor.java:110`（`CLAIM_ATTRIBUTE = "userId"`） |
| **JWT 配置** | `access-secret` + `access-expire = 31536000` | `article-api/src/main/resources/application.yml:127-128` · `article-api/…/config/ArticleApiProperties.java` |
| **错误码** | 15 个：`Success=200` `Error=500` `CodeServerBusy=1015` `1001` `1002` `1003` `1004` `1005` `1006` `5001` `5002` `5003` `5004` `5005` `5006` | `article-common/…/errmsg/ErrorCode.java:95`（枚举，15 个常量） |
| **错误文案** | 中文；`CodeServerBusy="服务繁忙"`；`ErrorArticleForbidden="只能编辑或删除自己的文章"`；未知码回落 `"FAIL"` | `article-common/…/errmsg/ErrorCode.java:121` 起（枚举内嵌文案）+ `getMsg(int)` |
| **信封形状** | `{"code":int,"msg":string,"data":…}`；**HTTP 恒 200** | `service/common/…/response/Response.java:22-24` · `article-api/…/advice/GlobalResponseAdvice.java` |
| **唯一裸响应** | `POST /v1/upload` → `{"image_url":string}`；multipart 字段名固定 `image` | `article-api/…/controller/ArticleController.java:237-240` · `article-api/…/dto/UploadImageResp.java`（`@JsonProperty("image_url")`） |
| **proto 字段号** | `article.proto` 的 message 名 / 字段名 / 字段号 / 类型 / 枚举值**逐条不变** | `article-proto/src/main/proto/article.proto`（C 侧仅改 option 块） |
| **gRPC 方法** | 6 个：`CreateArticle` `GetArticle` `UpdateArticle` `DeleteArticle` `ListArticles` `UploadFile` | `article-proto/src/main/proto/article.proto:128-133`（`service ArticleService`）· `article-rpc/…/service/impl/ArticleServiceGrpcImpl.java:76,82,88,94,100,106` |
| **枚举取值** | `UNSPECIFIED=0 DRAFT=1 PUBLISHED=2 REVIEWING=3 REJECTED=4 DELETED=5` | `article-proto/src/main/proto/article.proto:8` 起（`enum ArticleStatus`） |
| **表名** | `article` · `article_sync_outbox` · `article_revision` · `article_publication` · `article_domain_outbox` | `article-rpc/…/domain/ArticleEntity.java:47` · `ArticleSyncOutboxEntity.java:22` · `ArticleRevisionEntity.java:35` · `ArticlePublicationEntity.java:34` · `ArticleDomainOutboxEntity.java:35`（各自的 `@TableName`） |
| **关键索引 / 约束** | `article`：`idx_article_manual_type_tag` / `idx_article_author_id` / `idx_article_deleted_at`；`article_revision` PK`(article_id,revision)` + `revision_id`/`sync_event_id` 唯一；`article_publication` PK`article_id` + `state IN ('published','retracted')` CHECK；`article_domain_outbox` PK`event_id` + `UNIQUE(aggregate_id,aggregate_version)` + 部分索引 `WHERE status IN (0,2)` | `article-rpc/src/main/resources/db/migration/V8__create_article.sql` · `V10__create_article_community_revision.sql` |
| **Kafka topic** | `article-creation-topic`（审核：pusher + consumer）· `article-sync-events`（出）· `article-sync-results`（入）· `article-hot-events`（**零调用，死配置**） | `article-rpc/src/main/resources/application.yml:328,335,343,350` |
| **Kafka group** | `article-creation-group` / `dataclean` / `article-sync-pusher` / `article-sync-result` | `article-rpc/src/main/resources/application.yml:326,334,341,349` |
| **outbox 轮询参数** | `PollIntervalMs=1000` · `BatchSize=100` · `MaxRetry=10`；`FetchPending` 过滤 `status IN (0,2) AND retry_count < maxRetry` | `article-rpc/…/config/ArticleRpcProperties.java:321-347`（`ArticleSyncOutbox` 嵌套段） |
| **MinIO** | bucket `articles` · `ImagePath=articles/images/` · `ArticlePath=articles/content/` · 对象名 `<ArticlePath><id>.md` 与 `<ArticlePath><id>-<sha256>.md` | `article-rpc/src/main/resources/application.yml:377`（`bucket-name: articles`）· `article-rpc/…/client/ArticleObjectStorageClient.java` |
| **数据库** | PostgreSQL，schema `public`，库 `first_db` | `article-rpc/src/main/resources/application.yml:18`（`jdbc:postgresql://…/${POSTGRES_DB:first_db}`）+ `:92`（`schemas: public`） |
| **指标完整名** | `article_service_article_total` · `article_service_article_status_total` · `article_service_file_upload_total` · `article_service_minio_request_duration_seconds` · `article_service_minio_request_errors_total` · `article_service_kafka_push_errors_total` | `article-rpc/…/metrics/ArticleRpcMetrics.java:59`（`NAMESPACE = "article_service"`）+ `:88,101,110,149,161,178`（各 `Counter.builder` / `Timer.builder`） |
| **指标标签** | `action`∈{create,update,delete} · `status`∈{reviewing,published,rejected} · `type`∈{image,markdown} · `operation`∈{put,get,delete} · `topic` | `article-rpc/…/metrics/ArticleRpcMetrics.java:90,103,112,151,163,180`（各 `.tag(...)`） |
| **指标埋点完整性** | `article_status_total` **只有 `reviewing` 有 +1 调用点**（`published` / `rejected` 无调用点）⇒ **照搬这个不完整，不许补全** | `article-rpc/…/metrics/ArticleRpcMetrics.java:96-98`（类级说明：唯一调用点在 create） |
| **MinIO 直方图分桶** | `{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1}`（秒，8 个） | `article-rpc/…/metrics/ArticleRpcMetrics.java:62`（`MINIO_BUCKETS`） |
| **community 事件 `event_id`** | `community.article.<syncEventID>.<operation>`；`producer = rtw.community.article`；`event_type = community.article.<operation>`；`schema_version = 1` | `article-rpc/…/mq/ArticleSyncEventTypes.java:78`（`COMMUNITY_PRODUCER`）+ 同文件的信封字段常量 |
| **community `subject_ref`** | `rtw.identity` / `platform` / `<AuthorID>`；`wiki_module_id` **恒为 nil**（社区文章永不进知识库） | `article-rpc/…/mq/ArticleSyncEventTypes.java:79-80`（`COMMUNITY_SUBJECT_ISSUER` / `COMMUNITY_SUBJECT_TENANT`） |
| **`revision_id` 格式** | `<articleID>:r<revision>` | `article-rpc/…/mq/ArticleSyncResultConsumer.java`（revision id 拼装处） |
| **`ext_info` 键** | `publish_stage` `reco_sync_state` `last_sync_error` `last_sync_event_id` `last_sync_version_ms` `last_sync_reason` `published_once` `pending_sync_reason` `review_nonce`（+ `h03_publication_gap`） | `article-rpc/…/mq/ArticleSyncEventTypes.java:60-68`（9 个 `EXT_*` 常量）+ `:75`（`H03_GAP_KEY`） |
| **`sync_event` op / reason 取值** | op ∈ {upsert, delete}；reason ∈ {create, update, status_change, delete}；scope = `article_sync` | `article-rpc/…/mq/ArticleSyncEventTypes.java:48-54`（`OP_UPSERT` / `OP_DELETE` / `REASON_*`） |

---

## 7. 覆盖度自查

> 判据来自 `docs/Mapping_Table/README.MD` §四。

### 7.1 C 列零遗漏 —— ✅ 60/60（100%）

```bash
find "service/article/src/main" -name '*.java' | xargs -n1 basename | sort -u
```

实测 **60** 个唯一文件名（= `service/article/src/main` 下 **60** 个 `.java`，**无重名**），逐条比对结果：

| # | C 列文件 | 出现在 | # | C 列文件 | 出现在 |
|---|---|---|---|---|---|
| 1 | `Article.java` | §1.1 dto 行 | 31 | `GetArticleReq.java` | §1.1 dto 行 |
| 2 | `ArticleApiExceptionHandler.java` | §1.1 advice 行 | 32 | `ArticleKafkaChannelValidator.java` | §1.2 |
| 3 | `ArticleSyncEvent.java` | §1.3 | 33 | `GetArticleResp.java` | §1.1 dto 行 |
| 4 | `ArticleApiProperties.java` | §1.1 config 行 | 34 | `ArticleMapper.java` | §1.2 |
| 5 | `ArticleSyncOutboxEntity.java` | §1.2 | 35 | `ArticleMetaObjectHandler.java` | §1.2 |
| 6 | `ArticleApiResponseAdvice.java` | §1.1 advice 行 | 36 | `JwtAuthInterceptor.java` | §1.1 |
| 7 | `ArticleSyncOutboxMapper.java` | §1.2 | 37 | `ArticleObjectStorageClient.java` | §1.2 |
| 8 | `ArticleApiService.java` | §1.1 logic 行 | 38 | `ListArticlesReq.java` | §1.1 dto 行 |
| 9 | `ArticleSyncOutboxRelay.java` | §1.2 mq 行 | 39 | `ArticlePublicDomainService.java` | §1.2 |
| 10 | `ArticleApiSupport.java` | §1.1 helper 行 | 40 | `ListArticlesResp.java` | §1.1 dto 行 |
| 11 | `ArticleSyncResultConsumer.java` | §1.2 mq 行 | 41 | `ArticlePublicMapper.java` | §1.2 |
| 12 | `ArticleAtomicService.java` | §1.2 | 42 | `MessageRpcClient.java` | §1.2 |
| 13 | `CommunityArticleEvent.java` | §1.3 | 43 | `ArticlePublicationEntity.java` | §1.2 |
| 14 | `ArticleController.java` | §1.1 | 44 | `MinioBucketBootstrap.java` | §1.2 |
| 15 | `ContentAuditAutoConfiguration.java` | §1.2 client 行 | 45 | `ArticlePublicationMapper.java` | §1.2 |
| 16 | `ArticleCorsFilter.java` | §1.1（`ArticleGoStyleCorsFilter` 行） | 46 | `NacosRegistration.java` | §1.2 |
| 17 | `ContentAuditPort.java` | §1.2 client 行 | 47 | `SnowflakeBootstrap.java` | §1.2 |
| 18 | `ArticleDomainOutboxEntity.java` | §1.2 | 48 | `ArticleReviewConsumer.java` | §1.2 mq 行 |
| 19 | `CreateArticleReq.java` | §1.1 dto 行 | 49 | `UnavailableContentAuditClient.java` | §1.2 client 行 |
| 20 | `ArticleDomainOutboxMapper.java` | §1.2 | 50 | `ArticleRevisionEntity.java` | §1.2 |
| 21 | `CreateArticleResp.java` | §1.1 dto 行 | 51 | `UpdateArticleReq.java` | §1.1 dto 行 |
| 22 | `ArticleDomainService.java` | §1.2 | 52 | `ArticleRevisionMapper.java` | §1.2 |
| 23 | `DeleteArticleReq.java` | §1.1 dto 行 | 53 | `UpdateArticleResp.java` | §1.1 dto 行 |
| 24 | `ArticleEntity.java` | §1.2 | 54 | `ArticleRpcMetrics.java` | §1.2 |
| 25 | `DeleteArticleResp.java` | §1.1 dto 行 | 55 | `UploadImageReq.java` | §1.1 dto 行 |
| 26 | `ArticleExceptions.java` | §1.2 | 56 | `ArticleRpcProperties.java` | §1.2 |
| 27 | `ErrCodes.java` | §1.3 | 57 | `UploadImageResp.java` | §1.1 dto 行 |
| 28 | `ArticleGrpcService.java` | §1.2 | 58 | `ArticleSchemaGate.java` | §1.2 |
| 29 | `ErrMsg.java` | §1.3 | 59 | `WebMvcConfig.java` | §1.1 |
| 30 | `ArticleJwtAuthFilter.java` | §1.1（`GoStyleJwtAuthFilter` 行） | 60 | `ArticleServiceApplication.java` | §0 / §1.1 |

> **结论：60/60，无遗漏。**
> ⚠️ **与旧版 51/51 的差额 12 个**（2026-11-03 补登）：`ArticleJwtAuthFilter` · `ArticleQueryValues` · `ArticleStringFields` · `JsonbTypeHandler` · `ArticleReviewConsumer` · `ArticleSyncOutboxRelay` · `ArticleSyncResultConsumer` · `ContentAuditPort` · `ContentAuditAutoConfiguration` · `UnavailableContentAuditClient` · `ArticleObjectStorageClient` · **`ArticleCorsFilter`**（后者是本轮新发现并补齐的**真实缺失**，见 §1.1 与配套报告 §11.5.1 · D-13）。
> ✅ **2026-11-03 · D-09 / D-10 / D-11 收口后重算为 60/60** —— 上述 12 个里的 **`ArticleQueryValues` / `ArticleStringFields` / `JsonbTypeHandler` 三份域内副本已上收共享层并删除** ⇒ C 侧文件数 63 → 60（**其余 9 个补登项仍在**）。

### 7.2 映射类型分布（实测计数）

> 统计口径：**全文所有主表**（表头第 3 格为「映射类型」的表）的**数据行**；§2 / §3 / §4 / §6 是按维度组织的**差异对照表**，不设「映射类型」列，故不计入。

| 映射类型 | §0 顶层 | §1 包结构 | 合计 | 占比 |
|---|---|---|---|---|
| `1:1` | 1 | 34 | **35** | 46.1% |
| `1:N` | 4 | 16 | **20** | 26.3% |
| `N:1` | 1 | 19 | **20** | 26.3% |
| `C 新增` | 1 | 0 | **1** | 1.3% |
| **合计** | **7** | **69** | **76** | 100% |

> 每行都填了映射类型，且只用 `1:1` / `1:N` / `N:1` / `C 新增` 四种词（未发明新词）。
> ⇒ `N:1` 与 `1:N` 合计 **40/76（52.6%）** 是 article 域的常态：**B 侧按 codegen 模板切成 7 个 api Service + 6 个 rpc Service，RAG-V1 按职责收敛成 1 个 `ArticleApiService` + 1 个 `ArticleDomainService`**。
> ⚠️ **核对日期 2026-11-03**（补 4 行 + 改判 2 行后**机器重算**，非手数）。
> **重算依据**：脚本逐行扫主表（`§0` 7 行 + `§1.1` 28 + `§1.2` 33 + `§1.3` 6 + `§1.6` 2 = **76 行**），按第 3 格取值计数。
> ⚠️ **口径自证**：同一脚本在**改前的原文件**上复算得 **72 行 / 1:1=29 / 1:N=22 / N:1=20 / C 新增=1** ⇒ 与旧文档**逐项一致** ⇒ ⇒ 脚本口径可信 ⇒ 本次差额来自真实编辑：
> **① +4 行**（`GoStyleStringFields` / `GoFormValues` / `GoStyleJwtAuthFilter` / **`ArticleGoStyleCorsFilter`** 各 1 行）⇒ **② 改判 2 行**（`JsonbTypeHandler` 与 `db/migration` 由 `1:N` 改判 `1:1` —— 它们**现在真的是 1:1**：副本与 B 侧同构、DDL 逐字节照抄）。

### 7.3 ⚠️ 两方之间「职责对不上」的地方（单独列出）

> 本表产出的待订正项，分两类：**① B 有、C 无**（§1.5 的 6 处缺口，G-1 ~ G-6 —— **2026-11-03 已全部闭合**）· **② C 列现有文档 / TODO 与 B 侧源码不符**（D-1 / D-2 —— **已随实现回修**）。

> 📌 **2026-11-03 · D16 复核（已过期，保留作历史）· ③ B 侧有类、但本表 B 列未登记（9 个）** —— 当时 `python3 tools/check-mapping-coverage.py article` 实测本域 B 列覆盖 **74/83（89%）**。逐个如下（**全部是「域内副本上收共享层」的账单，不是漏抄**）：
>
> - `ArticleGoStyleCoercion` · `ArticleGoStyleCorsFilter` —— **故意不写**：两者登记在 `tools/check-mapping-coverage.py` 的 `B_COLUMN_WHITELIST` 里（「RAG 域内局部副本 ⇒ C 侧由 `common/web/` 承担」）⇒ 写进 B 列会让白名单条目被判「已失效」；
> - `GoStyleJsonBodyLimitFilter`（B：`article-api/…/config/`）· `GoFormValues`（B：`article-api/…/form/`）· `GoStyleBadRequestException` / `GoStyleGrpcStatusMapper` / `GoStyleHttpErrorException` / `GoStylePlainTextErrors`（B：`article-api/…/http/`）· `Jackson2ObjectMapperConfiguration`（B：`article-rpc/…/config/`）—— 这 7 个类的 C 侧落点**全部在 `common/` 共享层**（D3 上收）：`common/src/main/java/com/rag/common/web/{JsonBodyLimitFilter,GetFormValuesFilter,BadRequestException,GrpcStatusMapper,HttpErrorException,PlainTextErrors}.java`，以及 `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（Boot 3.5 自带 Jackson 2 自动配置 ⇒ 不再需要域内补 Bean）；
>
> ✅ **2026-11-03 现状**：该脚本的 `_covered_by` 已改为**目录 token = 前缀语义**（D16 · 2026-11-02）⇒ **本域 B 列实测 83/83 = 100%**（见 §7.1 与 `_INDEX.md`）⇒ 上面的「74/83」**已过期**。
> ✅ **另已补登进 §1 主表**：`GoFormValues` ⇒ **共享层 `common/web/FormValues.java`**（**取值真值表** —— 共享层 `GetFormValuesFilter` 只有上限闸门 ⇒ 原先域内副本是必需的；**2026-11-03 D-10 收口后副本已删、只留共享层一份**）⇒ **映射行 72 → 75**。

| # | 位置 | 问题 | 处置 |
|---|---|---|---|
| **D-1** | `RAG-V1/docs/第二轮深挖-契约盘点.md:134,150` **与** `RAG-V1/service/article/article/service/.../metrics/ArticleRpcMetrics.java` 的 TODO javadoc | 声称「article/rpc **完全没有** Namespace/Subsystem ⇒ 指标名是裸名（`article_total` 等）」。**与 B 侧源码不符**：B 侧 `article-rpc/…/metrics/ArticleRpcMetrics.java:59` 明确写着 `NAMESPACE = "article_service"` ⇒ 完整名应为 `article_service_article_total` 等。 | 本表 §6 按 **B 侧源码**记录带前缀的完整名；✅ **2026-11-03 已随实现落地** —— C 侧 `metrics/ArticleRpcMetrics.java` 按 B 侧 `NAMESPACE = "article_service"` 实现（**含前缀**）⇒ C 侧无此漂移 |
| **D-2** | `RAG-V1/service/article/article/service/.../controller/ArticleController.java` 的 TODO javadoc | 端点清单写成 `GET /v1/article` / `PUT /v1/article` / `DELETE /v1/article` / `GET /v1/user/articles` —— **4 条缺 `:id`、1 条路径写错**。B 侧真值是 `GET /v1/article/:id` / `PUT /v1/article/:id` / `DELETE /v1/article/:id` / `GET /v1/me/article/:id`（`ArticleController.java:123,137,152,167,181,201,237`）。method 分布（Get3·Post2·Put1·Delete1）✅ 正确 | 本表 §6 按 B 侧记录；✅ **2026-11-03 已随实现落地** —— C 侧 `controller/ArticleController.java` 的 7 条路由逐条对齐 B 侧（`/v1` 前缀 + `:id` 段 + `GET /v1/me/article/{id}`）⇒ C 侧无此漂移 |
| **D-3** | C 列**整体**（详见 §1.5） | 6 处职责缺口：**G-1** `mq/` 包 · **G-2** `UserRpcClient` · **G-3** 对象存储客户端 · **G-4** 内容安全 / 图片安全客户端 · **G-5** DDL 未落地 · **G-6** `ArticleReviewMessage` / `ArticleSyncResult` 载荷无载体 | ✅ **2026-11-03 已闭合 4 项**（G-1 / G-3 / G-5 / G-6）· 🟠 **G-2 与 G-4 为签字偏离**（无对端域 ⇒ 回落分支 / 恒 fail-loud）⇒ 详见 §1.5 与 `docs/Intentional_Deviations/article/` |

### 7.4 一句话总览

`B 列 RAG` = 2 进程（api + rpc）· 注解式路由 + MyBatis-Plus + Flyway V8/V9/V10 + api/rpc 分离 + 7 个 api Service + 3 个 domain Service + `mq/` 包 4 类 + `article_service_` 指标前缀 ·
`C 列 RAG-V1` = api/rpc 合并为单 app + `advice + config + controller + dto + interceptor + service` 与 `wiring + client + config + domain/entity + domain/service + exception + grpc + mapper + metrics` 两个包内分区 + **7 个用例合 1 个 `ArticleApiService`** + 契约落在域内 `contract/` 包（proto 在根级 `proto/`） + **6 处职责缺口 4 项已闭合 · 2 项签字偏离**（G-2 / G-4）+ ✅ **3 份域内副本已全部收口**（2026-11-03 · **D-09** `web/ArticleStringFields` → 共享层 `common/web/StringFields`（放开可见性）· **D-10** `web/ArticleQueryValues` → 共享层 `common/web/FormValues`（新增）· **D-11** `mapper/JsonbTypeHandler` → 共享层 `common/persistence/JsonbValueTypeHandler`（新增）⇒ **副本数 3 → 0**）。
proto 字段号 / 错误码 / JWT claim / 表名 / HTTP path / Kafka topic / 指标完整名 —— **全部以 B 侧源码为准逐条核对**。
