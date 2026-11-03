# `article` 域 ↔ `common/` 共享层 —— 跨层对账台账

> **由 `align/article` 工作树产出** · **基线 commit `6a3acb4`**（本地 `main`）· 分支 `align/article`
> **核对日期**：2026-11-03
> **写权**：✅ 可写**本域** `service/article/**` ＋ `docs/DRIFT/article/**`；
> 🔴 **`common/**` 只读**（唯一写者 = 合并收口执行者 —— AGENTS.md §五.3 规则 3：「`common/**` 只有收口任务能写」）。
> **本文件正文的「整合者」一律按「合并收口执行者」理解**（`docs/Worktree.md` §1e）。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` / `docs/Intentional_Deviations/_INDEX.md` /
> `docs/Pitfalls/_INDEX.md` / `AGENTS.md` **一律不碰**（AGENTS.md §五.3 规则 3）。
> **配套文件**：[`verification-article-service-2026-11-03.md`](verification-article-service-2026-11-03.md)（本域八阶段审核报告）

> 📌 **lane 语义**：本文件是「**本域 ↔ 共享机制层 `common/`**」的跨层对账
> —— ⚠️ **不是**「本域自己的 `contract/` 包」（域内错误契约，见配套报告 §11.2）。

---

## 0. lane 身份

| 项 | 值 |
|---|---|
| 本域 | `service/article/`（artifactId **`article-service`** · 包 `com.rag.article`） |
| 共享层 | `service/common/` → C 侧 `common/`（C 侧包名 `com.rag.common`） |
| 工作树 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/article` |
| 分支 / 基点 | `align/article` / `6a3acb4` |
| 本轮改动 | **全部在工作树内、未提交**（工作树 `git status` 全部为 `M` / `??`） |
| 本轮核对日期 | **2026-11-03** |
| B 侧唯一 oracle | `/Users/pr/Documents/java&golang/RAG/service/article/`（**只读**；api 43 + common 1 + rpc 39 = **83** 个主代码 `.java`） |
| C 侧本域主代码 | **63** 个 `.java`（**0 空壳**） |

---

## 1. 本域**消费**的共享层能力（15 个类 · 全部已实现）

> **核法**：`grep -rhoE 'import com\.rag\.common\.[A-Za-z0-9_.]+' service/article/src/main/java` ⇒ 去重 ⇒ **15** 个。
> ⇒ 逐个确认**不是空壳** ⇒ **零「上收了没实现」**。

| # | 共享层类 | 行数 | 本域消费点（`service/article/src/main/java/com/rag/article/…`） | 承担的 B 侧职责 | 实现状态 |
|---|---|---:|---|---|---|
| 1 | `common/contract/ApiResponse.java` | 67 | `advice/ArticleApiResponseAdvice` · `advice/ArticleApiExceptionHandler` | HTTP 信封 `{code,msg,data}`（B 侧 `common/response/Response`）**上收** | ✅ 非空壳 |
| 2 | `common/exception/BusinessException.java` | 45 | `service/ArticleApiService` · `service/ArticleApiSupport` · `advice/ArticleApiExceptionHandler` | 业务异常基类 | ✅ |
| 3 | `common/exception/AuthenticationException.java` | 55 | `interceptor/ArticleJwtAuthFilter` · `interceptor/JwtAuthInterceptor` · `advice/ArticleApiExceptionHandler` | 鉴权异常（⇒ **401 空 body**） | ✅ |
| 4 | `common/logger/FatalError.java` | 47 | `advice/ArticleApiExceptionHandler` | 致命错误（原样抛出，**绝不转信封**） | ✅ |
| 5 | `common/observability/MqConsumer.java` | 163 | `messaging/ArticleReviewConsumer` · `messaging/ArticleSyncResultConsumer` | MQ 消费 trace 载体 | ✅ |
| 6 | `common/protection/ServiceBusyException.java` | 36 | `service/ArticleApiService` | 熔断打开 ⇒ 共享层 breaker 在 `ClientCircuitGateInterceptor.java:145` 抛 `"circuit breaker is open"` | ✅ |
| 7 | `common/security/BearerTokenExtractor.java` | 105 | `interceptor/ArticleJwtAuthFilter` | B 侧 golang-jwt 的 `stripBearerPrefixFromTokenString` 口径 | ✅ |
| 8 | `common/security/JwtUtil.java` | 111 | `interceptor/ArticleJwtAuthFilter` | `padToHmacKeySize` 密钥补齐 | ✅ |
| 9 | `common/snowflake/Snowflake.java` | 446 | `wiring/SnowflakeBootstrap` · `domain/service/ArticleDomainService` · `messaging/ArticleReviewConsumer` | 雪花 ID（位宽/epoch 是业务契约） | ✅ |
| 10 | `common/web/BadRequestException.java` | 63 | `controller/ArticleController` · `service/ArticleApiService` · `advice/ArticleApiExceptionHandler` · `dto/article/CreateArticleReq` · `web/ArticleQueryValues` · `web/ArticleStringFields` | B 侧 `http/GoStyleBadRequestException` **上收**（**400 + text/plain + nosniff**） | ✅ |
| 11 | `common/web/Coercion.java` | 179 | `config/WebMvcConfig` | B 侧 `config/ArticleGoStyleCoercion`（域内副本）的**共享部分** | ✅ |
| 12 | `common/web/GrpcStatusMapper.java` | 147 | `service/ArticleApiService` | B 侧 `http/GoStyleGrpcStatusMapper` **上收**（产出 `rpc error: code = X desc = Y`） | ✅ |
| 13 | `common/web/HttpErrorException.java` | 64 | `service/ArticleApiService` · `advice/ArticleApiExceptionHandler` | B 侧 `http/GoStyleHttpErrorException` **上收** | ✅ |
| 14 | `common/web/PlainTextErrors.java` | 104 | `advice/ArticleApiExceptionHandler` | B 侧 `http/GoStylePlainTextErrors` **上收** | ✅ |
| 15 | `common/web/RouterErrorResponses.java` | 152 | `advice/ArticleApiExceptionHandler` | B 侧 `common/http/GoStyleRouterErrorResponses` **上收**（404 空 body / 405） | ✅ |

> 📌 **消费面 15 个文件**（`service/article/src/main/java/`）：
> `advice/ArticleApiResponseAdvice` · `advice/ArticleApiExceptionHandler` · `config/WebMvcConfig` ·
> `controller/ArticleController` · `domain/service/ArticleDomainService` ·
> `dto/article/CreateArticleReq` · `interceptor/ArticleJwtAuthFilter` · `interceptor/JwtAuthInterceptor` ·
> `messaging/ArticleReviewConsumer` · `messaging/ArticleSyncResultConsumer` ·
> `service/ArticleApiService` · `service/ArticleApiSupport` ·
> `web/ArticleQueryValues` · `web/ArticleStringFields` · `wiring/SnowflakeBootstrap`。

> ⚠️ **共享层 `common/web/` 下还有 3 个类本域用不到**（`GetFormValuesFilter` · `JsonBodyLimitFilter` · `MapStringValueDeserializer`）⇒ **不是「本域漏接」**，是本域的落点不同（见 §2.1）。

---

## 2. 本域**向共享层上收**的清单（**3 项 🔴 待收口**）

> ⚠️ **这 3 项不是「本域偷懒留副本」** —— 是**共享层缺一个对应物**（或对应物不可见）⇒ 属**跨 lane 账单**，本域只登记。

| 上收项 | B 侧原位（域内私有副本） | C 侧落点 | 本域消费方 | 结论 |
|---|---|---|---|---|
| JSON 字段类型判定（`type mismatch for field "x"`） | `article-api/.../dto/GoStyleStringFields.java` | `service/article/src/main/java/com/rag/article/web/ArticleStringFields.java` | `dto/article/*`（`@JsonCreator` 次构造器）· `web/ArticleStringFields` | 🟠 **域内副本 · 待收口** |
| query/form 取值真值表（`raw`/`first`/`bool`/`int32`/`text`） | `article-api/.../form/GoFormValues.java` | `service/article/src/main/java/com/rag/article/web/ArticleQueryValues.java` | `controller/ArticleController` · `dto/article/ListArticlesReq` | 🟠 **域内副本 · 待收口** |
| jsonb TypeHandler（`List<String>` + `Map<String,String>`） | `article-rpc/.../mapper/JsonbTypeHandler.java` | `service/article/src/main/java/com/rag/article/mapper/JsonbTypeHandler.java` | `domain/entity/ArticleEntity`（`secondary_tags` / `ext_info`） | 🟠 **域内副本 · 待收口** |

### 2.1 🔴 **待收口**（**属 `common/` 写权 · 不在本 lane**）

| # | 项 | 现象（实测证据） | 为什么现在不能收 | 收口建议 | 严重度 |
|---|---|---|---|---|---|
| **2.1-1** | **`common/web/StringFields` 是**包私有** ⇒ 域内调不到** | `javap -p -cp common/target/classes com.rag.common.web.StringFields` ⇒ `final class com.rag.common.web.StringFields`（**无 `public`**）· `static java.lang.String parse(java.lang.String, java.lang.Object)`（**包私有静态**）⇒ ⇒ **域内任何包都调不到** | 共享层该类是**包私有** ⇒ 提升可见性属于 `common/` 写权 ⇒ 本 lane 不可写 | 把 `common/web/StringFields` 提升为 `public final class` + `public static String parse(...)` ⇒ 本域删 `web/ArticleStringFields` 改 import；⚠️ **两份不得分叉**（本域副本是 B 侧 `GoStyleStringFields.java` 的逐行副本 ⇒ 上收时以 B 侧为基准校对） | 🟡 P2 |
| **2.1-2** | **`common/web/GetFormValuesFilter` 只有「上限闸门」、无取值真值表** | `javap`/源码：公开面只有 `goParseViolation(String)` + `writeGoStyleBadRequest(resp,msg)` ⇒ **没有任何取值方法** ⇒ B 侧 `GoFormValues` 的 `raw`/`first`/`bool`/`int32`/`text` 五条真值表**无处安放** | 共享层**缺对应物**（不是可见性问题，是**能力缺口**）⇒ 补能力属于 `common/` 写权 | 在 `common/web/` 增 `FormValues`（或把 `GetFormValuesFilter` 泛化出取值方法）⇒ 本域删 `web/ArticleQueryValues` 改 import；⚠️ **两份不得分叉** | 🟡 P2 |
| **2.1-3** | **`common/persistence/JsonbTypeHandler` 的**类型不兼容** ⇒ 顶不了本域的 jsonb | 共享层那版 = `public class JsonbTypeHandler extends BaseTypeHandler<String>`（**jsonb ↔ 文本**）⇒ 而本域要 `List<String>`（`secondary_tags`）**与** `Map<String,String>`（`ext_info`）**两种**形态 ⇒ ⇒ **类型不兼容** ⇒ 共享层另有一份 `JsonTextTypeHandler` = **text ↔ Map**（也不是 jsonb） | 共享层**无对应物** ⇒ 同 2.1-2 | 新建 `common/persistence/JsonbListTypeHandler` + `JsonbMapTypeHandler`（或把 `JsonbTypeHandler` 泛化成 `jsonb ↔ Object`）⇒ 本域删 `mapper/JsonbTypeHandler` 改 import；⚠️ 本域副本的类结构（`final class` + 两个嵌套 handler + `readList`/`readMap`）与 **B 侧逐条同构** ⇒ 上收时以 B 侧为基准 | 🟡 P2 |

> 📌 **先例**：message 域的 `JsonbMapTypeHandler` 是**完全同型**的账单（共享层无对应物 ⇒ 域内副本 ⇒ 待收口）⇒ **两域的同一笔账** ⇒ 建议收口任务**一次处理两个域**。

### 2.2 ✅ **已上收到共享层的（B 侧域内副本 → `common/`，本域只消费）**

| B 侧原位 | C 侧共享层落点 | 本域消费点 | 状态 |
|---|---|---|---|
| `article-api/.../http/GoStyleBadRequestException.java` | `common/web/BadRequestException.java` | 6 个文件（见 §1 #10） | ✅ |
| `article-api/.../http/GoStyleGrpcStatusMapper.java` | `common/web/GrpcStatusMapper.java` | `service/ArticleApiService` | ✅ |
| `article-api/.../http/GoStyleHttpErrorException.java` | `common/web/HttpErrorException.java` | 2 个文件 | ✅ |
| `article-api/.../http/GoStylePlainTextErrors.java` | `common/web/PlainTextErrors.java` | `advice/ArticleApiExceptionHandler` | ✅ |
| `common/http/GoStyleRouterErrorResponses.java`（B 侧共享层） | `common/web/RouterErrorResponses.java` | `advice/ArticleApiExceptionHandler` | ✅ |
| `article-api/.../config/GoStyleJsonBodyLimitFilter.java` | `common/web/JsonBodyLimitFilter.java`（自动配置装配） | 本域**零直接引用**（由共享层自动配置生效） | ✅ |
| `article-api/.../config/ArticleGoStyleCoercion.java` | `common/web/Coercion.java` | `config/WebMvcConfig`（`goStyleCoercionCustomizer`） | ✅ |
| `article-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | `common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（**Boot 3.5 自带 Jackson 2 自动配置** ⇒ 域内**不再需要**补 Bean） | 本域**零直接引用** | ✅ |

> ⚠️ **`ArticleGoStyleCorsFilter`（B 侧 `article-api/.../config/`）** —— 登记在 `tools/check-mapping-coverage.py` 的 `B_COLUMN_WHITELIST`（「RAG 域内局部副本 ⇒ C 侧由 `common/web/` 过滤器承担」）⇒ **故意不进 B 列**（写进去会让白名单条目被判「已失效」）⇒ 本域**零 C 侧对应物**（共享层 `common/web/` 承担 CORS 过滤）。

---

## 3. 9 条机制面轴 · 本域侧逐轴结论

> **轴的定义**沿用 B 侧 `docs/DRIFT/message/vs-common.md` 的口径（超时 / deadline · gRPC 拦截器链 · HTTP 保护链 ·
> 错误码 / envelope · 端口治理 · errmsg 契约 · proto 契约 · DDL 时间列 / 类型 · trace / otel）。
> ⚠️ **「✅ 一致」的含义 = 在该证据强度下一致**，**不是**已做双侧活体对拍（配套报告 §11.6 的诚实边界）。

| 轴 | 机制 | 本域侧的落点 / 调用 | 结论 | 证据强度 | 核对日期 |
|---|---|---|---|---|---|
| **A1** | 超时 / deadline | ⚠️ **D1 合并后同域调用不再跨网络** ⇒ 本域**无同域 gRPC client**（B 侧 `article-api/.../client/ArticleRpcClient.java` 被 D2 域内直连取代）⇒ ⇒ **B 侧的 stub deadline 语义位在 C 侧「无处可落」**（不是丢失，是**拓扑产物**）<br>⇒ 跨域只剩 `client/MessageRpcClient`（`@GrpcClient("message-rpc")`）⇒ 共享层 `ClientCircuitGateInterceptor` 负责 outbound deadline<br>⇒ 📌 共享层 `common/observability/GoStyleRequestDeadline` 在本域**零直接引用**（由共享层 `HttpMiddleware` 统一绑内层预算） | ✅ **一致**（本域无同域网络调用 ⇒ deadline 语义位归位） | **L1 源码逐条对照**（B 侧 `ArticleRpcClient.java` 全文 → C 侧无对应文件，D2 是**已签字**的架构决策） | 2026-11-03 |
| **A2** | gRPC 拦截器链 | 共享层 `common/autoconfigure/ProtectionAutoConfiguration` 声明 `GrpcServerConfigurer` ⇒ `server.intercept(chain)` ⇒ **对全部域生效** ⇒ 本域**零重复**<br>⇒ 守门 **A3 PASS** | ✅ **一致**（C 侧优于 B：B 侧曾静默失效） | **L2 装配层守门**（A3 PASS：「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」） | 2026-11-03 |
| **A3** | HTTP 保护链 | 共享层 `ProtectionAutoConfiguration` ⇒ `MaxBytesFilter` + `HttpMiddleware`（**70s 预算起算**）+ `MaxConnsFilter` + `RouteIsolationFilter` + `CpuSheddingFilter` + `JsonBodyLimitFilter` + `PathNormalizingFilter` + `TraceparentFilter`<br>⇒ 本域新增 `interceptor/ArticleJwtAuthFilter`（`OncePerRequestFilter` · `@Order(HIGHEST_PRECEDENCE + 6)`）⇒ **排在 CpuShedding(+6) 之后、HttpMiddleware(+7) 之前** ⇒ **验签在 70s 预算之外**<br>⇒ 🔴 **B 侧同款两段式**（`auth/GoStyleJwtAuthFilter` 预算外 + `interceptor/JwtAuthInterceptor` 预算内）⇒ ⇒ **形态同构**<br>⇒ ⚠️ **已核实**：本域 filter 的 `+6` 与共享层 `CpuSheddingFilter`(+6) **同序（并列）** ⇒ 相对顺序不由 `@Order` 保证 ⇒ **但 B 侧同款并列**（B 侧 `GoStyleJwtAuthFilter.java:101` 与 `GoStyleCpuSheddingFilter.java:67` **都是 +6**）⇒ 且本 filter **绝不写响应** ⇒ **先后不影响结果** ⇒ **行为等价**（详见配套报告 §11.5.3 · B-10） | ✅ **一致**（**两段式验签在 article 域闭环** ⇒ `DRIFT-USERAPI-009②` 在本域**不复现**） | **L1 源码逐条对照**（B 侧 `auth/ArticleJwtProtectedRoutes.java:73-77,88-101,112-119,122-125` ↔ C 侧 `ArticleJwtAuthFilter`）＋ **L2 装配层**（A3/A5/A10/A13 PASS） | 2026-11-03 |
| **A4** | 错误码 / envelope | **HTTP 信封** = 共享层 `common/contract/ApiResponse`（`{code,msg,data}` 三键）<br>**业务错误契约** = **域内** `contract/ErrCodes`（15 码）+ `contract/ErrMsg`（**逐域隔离，不共享**）<br>⇒ 失败路径 **HTTP 200 + 信封**；**唯一例外**：HTTP **401 + 空 body**（只由鉴权层产生）<br>⇒ ⚠️ **`POST /v1/upload` 是全域唯一不走信封的端点**（裸 `{"image_url":…}`）<br>⇒ ⚠️ **方法不匹配 ⇒ 404 空 body**（**不是 405**）—— 因 article-api 是**唯一**用 `rest.WithCors()` 的域 | ✅ **一致** | **L1 源码逐条对照**（15/15 code→msg 逐字节一致）＋ `contract/ArticleErrorCodeContractTest` **15 例** | 2026-11-03 |
| **A5** | 端口治理 | HTTP + gRPC **同进程**（D1 合并）⇒ **两个端口**（`ARTICLE_HTTP_PORT` / `ARTICLE_GRPC_PORT`）<br>⇒ 注册到 Nacos 的是 **gRPC 端口**（`GrpcServerProperties.getPort()`），**不是** web 端口<br>⇒ `wiring/NacosRegistration` **显式注册**（不依赖自动注册）⇒ 注册名 = **gRPC 服务名** | ✅ **一致**（端口值本身是 D1 的**已签字**偏离） | **L2 装配层**（真启动断言：桩上**只注册 1 次**、端口 = gRPC 端口 ≠ web 端口） | 2026-11-03 |
| **A6** | errmsg 契约 | **域内** `contract/`：`ErrCodes`（15 码）＋ `ErrMsg`（文案表）<br>⇒ ⚠️ **错误码 1005 = HTTP 200 + 信封**（业务层路径）；**HTTP 401 只由鉴权层产生**且 body 为空<br>⇒ ⚠️ **逐域隔离**（article 的 5004=文件上传失败 / 5005=文件删除失败 / 5006=文件下载失败，与 users 域**同值不同义**）<br>⇒ **未知码回落 `"FAIL"`** | ✅ **一致**（逐域隔离 · 不与其他域统一） | **L1 源码逐条对照** + `contract/ArticleErrorCodeContractTest` **15 例** | 2026-11-03 |
| **A7** | proto 契约 | 根级 `proto/`（D7 集中化）· pb 包名 **`com.rag.proto.article`**（⚠️ **与 B 侧 `com.rag.article.article.proto` 不同**）⇒ **不在本域文件数内**<br>⇒ 本域的 6 个 gRPC 方法入参出参**就是 proto** ⇒ 领域层与 gRPC 层**零中转**（D2）<br>⇒ ⚠️ **B 侧的「gRPC code → 业务码」映射仍在 API 层**（因为领域层抛 `ArticleExceptions.Rpc`/`Raw` 而非 `StatusRuntimeException`）⇒ C 侧用 `statusCodeOf()` **逐条复刻** `grpc/ArticleGrpcService` 的三条 catch 分支 | ✅ **一致**（`java_package` 属表达层） | **L1 逐字节 diff**（`.proto` 内容与 B 侧一致，仅 `java_package` 一行不同） | 2026-11-03 |
| **A8** | DDL 时间列 / 类型 | **V8 / V9 / V10 逐字节照抄 B 侧**（`diff -q` ⇒ **IDENTICAL**）⇒ 五张表（`article` · `article_sync_outbox` · `article_revision` · `article_publication` · `article_domain_outbox`）+ **不可变触发器** + CHECK 约束 + 唯一索引<br>⇒ ⚠️ **不重编号**（B 侧就是 V8/V9/V10）<br>⇒ **真 PG 16 容器**实测（**H2 认不了 `BIGSERIAL`/`TIMESTAMPTZ`/`jsonb`/`ON CONFLICT`/`CREATE TRIGGER` ⇒ H2 上「跑绿」是假绿**）<br>⇒ **独立账本** `flyway_schema_history_article` + `baseline-on-migrate: true` + **`baseline-version: 0` 必钉** | ✅ **一致**（含时间列类型与独立账本） | **L3 真 PG 活体** + **L1 字节对照**（`diff -q` ×3 = IDENTICAL） | 2026-11-03 |
| **A9** | trace / otel | 共享层 `common/observability/`（`GoStyleOtelAgent` / `MqConsumer` / `RpcStatInterceptor` / `ObservabilityLifecycle` …）⇒ 本域**零重复**<br>⇒ 本域 3 个消费者用共享层 `MqConsumer` 承载 MQ trace<br>⇒ ⚠️ **指标完整名带 `article_service_` 前缀**（B 侧 `NAMESPACE = "article_service"`）⇒ **不是裸名**<br>⇒ ⚠️ **`article_status_total` 只有 `reviewing` 有调用点** ⇒ **不许补全** | ✅ **一致**（本域零重复；埋点落点与 B 侧逐条相等） | **L1 源码逐条对照**（`NAMESPACE` 逐字）＋ **L3 真 registry 抓取** | 2026-11-03 |

---

## 4. 域侧能自修的（**本域直接修**，不甩给收口）

| # | 项 | 状态 | 证据 |
|---|---|---|---|
| 4-1 | **`service/article/pom.xml` 的 `org.postgresql:postgresql` 用了 `<scope>runtime</scope>`** | ✅ **已修**（照抄 B 侧，去掉 scope） | `service/article/pom.xml` |
| 4-2 | **`grpc-inprocess` 是 `<scope>test</scope>` ⇒ 运行期 classpath 消失 ⇒ `NoClassDefFoundError: InProcessChannelBuilder` ⇒ 服务起不来** | ✅ **已修**（改 compile scope · 已抄 follow 域 30 行注释） | `service/article/pom.xml` |
| 4-3 | **A5 守门红：域内残留 `@Import`** | ✅ **已修** —— 新建 `service/article/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（含 `com.rag.article.client.ContentAuditAutoConfiguration`）＋ 删 `ArticleServiceApplication` 的 `@Import` ＋ 清掉两个文件 javadoc 里的 `@Import(...)` 字面量<br>⚠️ **A5 判据是源码文本级扫描（连 javadoc 都匹配）⇒ 安全写法是 `{@code @Import}`（不能写 `{@code @Import(X.class)}`）** | `check-arch-invariants.py` A5 = PASS（0 个 `@Import`） |
| 4-4 | **`ArticleEntity.java` 缺 `import lombok.*`** | ✅ **已修** | `domain/entity/ArticleEntity.java` |
| 4-5 | **`ArticleMapper.selectAuthorView` 排序方向应是 3 分支 `<choose>`；且 `selectByIdForUpdate` / `softDelete` 缺失** | ✅ **已修** | `mapper/ArticleMapper.java` |
| 4-6 | **`CreateArticleReq` 的 `@NotNull` 缺自定义文案** | ✅ **已修** —— `@NotNull(message = "field \"title\" is not set")` / `"field \"content\" is not set"`（与 B 侧 `GoRequiredFieldException` 逐字节一致） | `dto/article/CreateArticleReq.java` |

---

## 5. 需转派**「合并收口执行者」**的（**本域只登记：现象 + 证据 + 建议修法**）

| # | 项 | 现象 | 证据 | 建议修法 | 严重度 |
|---|---|---|---|---|---|
| 5-1 | **3 份域内副本应收口到 `common/`**（§2.1 的 2.1-1 / 2.1-2 / 2.1-3） | ① `common/web/StringFields` 包私有 ⇒ 域内调不到 ② `GetFormValuesFilter` 无取值真值表 ③ `JsonbTypeHandler` 类型不兼容（jsonb↔String vs 本域要 List/Map） | `service/article/src/main/java/com/rag/article/{web/ArticleStringFields,web/ArticleQueryValues,mapper/JsonbTypeHandler}.java` | 见 §2.1 三行「收口建议」⇒ **建议与 message 域的 `JsonbMapTypeHandler` 同一次收口** | 🟡 P2 |
| 5-2 | **`docs/DRIFT/_INDEX.md` 未收录本域台账** | 本域台账已写好，但 `_INDEX.md` 本 lane **不碰** | 配套报告 §11.6 | 由收口执行者统一更新 | 🟢 流程 |
| 5-3 | **`docs/Intentional_Deviations/_INDEX.md` 未收录本域偏离** | 本域已写清（见配套报告 §11.5） | 同上 | 由收口执行者统一更新 | 🟢 流程 |

---

## 6. 未验证项（本轮明确留下的缺口，**不写成已闭环**）

| # | 项 | 为什么未验 | 什么时候能验 |
|---|---|---|---|
| 6-1 | **双侧活体对拍**（本域 ↔ B 侧同条件） | 本轮跑的是「**B 侧期望值移植到 C 侧**」的探针（B 侧 `*ContractTest` / `*RealPgTest` / `*ParityTest` 的**断言与期望值逐字沿用 B 侧**）⇒ 比「只跑 C 侧探针」强一档（期望值不是本域自拟的）⇒ 但**仍不是「B 侧活体跑出同一读数」** ⇒ AGENTS.md §3.3 的告诫依然成立 | ✅ **已闭合**（2026-11-03）—— 探针落 `tools/parity-probe/article/` ⇒ **B 侧 Java 栈（本地现成镜像 `sea/article-api:latest` 与 `sea/article-rpc:latest`）↔ C 侧 Java 栈同时起服务** ⇒ 同一批请求 ⇒ **`xxd` 逐字节比对** ⇒ **12/12 条逐字节一致** ⇒ 基线实跑后才冻结 ⇒ **3/3 变异精确转红** ⇒ 读数见配套报告 §11.6.4.4 |
| 6-2 | **跨域 gRPC 链路**（article → message-rpc） | 需要真 Nacos + 真 message-rpc 服务 | ✅ **已闭合**（2026-11-03）—— **真 Nacos ＋ 真 message-rpc 容器** ⇒ 正例 2 例 ＋ **反例 1 例** ⇒ `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`（两遍连跑均绿）⇒ 证据锚在**对端自己写的库行 ＋ 对端自己的 Prometheus 计数**上 ⇒ 读数见配套报告 §11.6.4.5<br>⚠️ **默认回归不含这 3 例**（`@Tag("external-env")` ＋ pom 类别排除）⇒ 显式跑 `-Pexternal-env` |
| 6-3 | **HTTP 响应头的字节形状**（`Content-Type` / `nosniff` / 状态行）逐端点断言 | 本域测试走**真 Tomcat**（随机端口），但**未逐端点断言响应头字节面** | ✅ **已闭合**（2026-11-03 第三轮）—— 新增 `http/ArticleHttpEndpointHeaderPlaneRealTomcatTest`（**2 例** · **真 Tomcat + 裸 socket 逐字节**）⇒ **7 个端点各自**的成功响应头名单**逐条**钉死（应用层精确 ＋ 容器层有界：分帧头 `Content-Length` / `Transfer-Encoding` **恰有一个**）＋ **200 / 400 / 401 / 404 四种形态**各自名单钉死 ⇒ 读数见配套报告 §11.6.4.6<br>⚠️ **过程中发现并登记 D-15**（404 比 B 侧多 2 条 `Vary` ⇒ **Spring 版本副产物** ⇒ 只登记不改码） |
| 6-4 | **源码变异证据**（断言不是假绿） | P0 批次做了**测试侧变异探针**（改种子数据 ⇒ 2 次都变红 ⇒ 断言绑定在真数据流上）⇒ **源码侧**变异也在本轮补做（见右） | ✅ **本轮已闭合**（2026-11-03）—— **MUT-A** 删 `@ResultMap` ⇒ `ArticleJsonbResultMapRealPgTest` `Tests run: 5, Failures: 1`；**MUT-B** 删 `@Transactional` ⇒ `ArticleAtomicServiceRealPgTest` `Tests run: 6, Failures: 1`；**MUT-C** 排序三分支→两分支 ⇒ `Tests run: 8, Failures: 2` ⇒ 三条**还原后全部复跑为绿** ⇒ 读数见配套报告 §11.6.4.1<br>⚠️ **效力边界**：只证明**那三条断言**不是假绿 ⇒ **不外推**到其余 149 例 |
| 6-5 | **MinIO 对象存储的真链路**（put/get + 公开 URL 拼装） | 需要真 MinIO 实例 | ✅ **已闭合**（2026-11-03）—— 新增 `storage/` 包（真 MinIO 容器底座 + 7 例 ＋ 3 例 legacy 正文回落）⇒ `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` ⇒ 读数见配套报告 §11.6.4.4<br>⚠️ **期望值来源是 E-2 级**（B 侧对 `ArticleObjectStorageClient` **无任何测试** ⇒ 只能从 B 侧**源码**逐字读出）⇒ **不得单独当「B 侧活体证据」** |
| 6-6 | **`listPublicArticles` 的「同一快照」并发语义** | 只验了**注解属性真被运行期使用**（`readOnly=true` / `ISOLATION_REPEATABLE_READ`）+ count/page 同口径的**行为**⇒ **并发编辑/撤回下的快照一致性未验** | ✅ **已闭合**（2026-11-03）—— 新增 `ArticlePublicListSnapshotRealPgTest`（2 例：运行期隔离级别事实 ＋ count/page 之间插入并发撤回的**行为后果**）⇒ 另配**同条件对照**（READ COMMITTED 下**确实**撕裂）⇒ 读数见配套报告 §11.6.4.2 |

---

## 7. 交付物清单（**本域侧**）

| 项 | 文件 | 状态 |
|---|---|---|
| 域级审核报告 | [`docs/DRIFT/article/verification-article-service-2026-11-03.md`](verification-article-service-2026-11-03.md) | ✅ 已写 |
| **本文件**（跨层对账） | `docs/DRIFT/article/vs-common.md` | ✅ 已写 |
| 共享层能力消费面 | **15** 个 `com.rag.common.*` 类 · **零空壳** | ✅ 已核 |
| 域内副本核对 | **3 份**（`web/ArticleStringFields` · `web/ArticleQueryValues` · `mapper/JsonbTypeHandler`）· 全部**待收口** | ✅ 已核 |
| 守门读数 | A1–A13 **PASS 10 · FAIL 0 · N/A 2 · PENDING 1**（PENDING 属 like/comment/favorite，**非本域**）· **EXIT=0** · `check-docs-vs-tree.py` **✅ 全部相符合 · EXIT=0** | ✅ 2026-11-03 |
| 映射表读数 | article **C 列 63/63 = 100%** · **B 列 83/83 = 100%**（`tools/check-mapping-coverage.py`，ROOT 指向工作树）· 全仓 **C 642/642 · B 752/752** · `_INDEX.md` 自报数字对账 ✅ · **EXIT=0**<br>⚠️ **更正**：第一轮记的「脚本 EXIT=1 · 2 条报错全落 users 域」**已不成立** —— 根因是**主工作区**当时（users 域未收口）的映射表只列了 `CreateUserReq.java`；users 域收口后（主工作区 HEAD `85751b6`）**两个 ROOT 都 EXIT=0** ⇒ ⇒ 本轮**复跑确认**，不是「基线问题所以不管」 | ✅ 2026-11-03（复跑） |
| 回归读数 | `mvn -o -pl service/article -am clean verify` ⇒ **BUILD SUCCESS**（**01:37** min · 日志 `/tmp/article-final-verify3.log`）· article **`Tests run: 157, Failures: 0, Errors: 0, Skipped: 0`**（默认回归 **32 类 / 157 例**）· common 同轮 `Tests run: 234, Failures: 0, Errors: 0, Skipped: 0`<br>⚠️ **默认回归不含** `external-env` 那 **2 类 / 3 例**（依赖宿主外部容器）⇒ 显式跑 `-Pexternal-env` ⇒ `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` | ✅ 2026-11-03（第三轮） |
| 源码变异读数 | **MUT-A / MUT-B / MUT-C** 三条**变异红 / 还原绿**（详见配套报告 §11.6.4.1）· **MUT-F**（第三轮 · U-3 头面穷举）同样**变异红 2/2 + 还原绿 2/2**（详见配套报告 §11.6.4.6） | ✅ 2026-11-03 |
| 第二轮补做读数 | **U-6 ~ U-11 / U-13**（真 PG / 真并发 / 真 Tomcat）= **19 例** · **MUT-D / MUT-E** 变异验证 · **U-1** 双侧活体对拍 **12/12 逐字节一致 + 3/3 变异转红** · **U-5 / U-12** 真 MinIO **10 例** · **U-2** 跨域 gRPC **3 例**（详见配套报告 §11.6.4.2 ~ §11.6.4.5） | ✅ 2026-11-03 |
| **第三轮补做读数** | **U-3** 头面穷举 **2 例**（7 端点 × 4 形态 · 真 Tomcat + 裸 socket）· **U-11 剩余 1/6** = `uploadFile` 真 MinIO **3 例** · **MUT-F** 变异验证（详见配套报告 §11.6.4.6） | ✅ 2026-11-03 |
| **已登记的签字偏离（本域侧）** | **D-15**（404 多 2 条 `Vary` · Spring 版本副产物 · **只登记不改码**）⇒ [`docs/Intentional_Deviations/article/http-404-extra-vary-headers.md`](../../Intentional_Deviations/article/http-404-extra-vary-headers.md) · `DEV-ARTICLE-HTTP-002`<br>⚠️ **跨域索引 `_INDEX.md` 属 `global` 收口域** ⇒ 本 lane **未改** | 🏷 待签 |
