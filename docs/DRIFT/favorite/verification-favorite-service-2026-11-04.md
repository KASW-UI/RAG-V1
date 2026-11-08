# `favorite` 域迁移审核台账 —— RAG（B 侧）→ RAG-V1（C 侧）

> **核对日期 2026-11-04 · 2026-11-05 更新**（P6-c ① 闭合 + 测试设施缺陷修复）· **基线 commit** `8de7818`（`feat(common): 收口 D-09/D-10/D-11`）· 分支 `align/favorite`
> **B 侧真源**：`/Users/pr/Documents/java&golang/RAG/service/favorite/`（**只读**）
> **C 侧落点**：`/Users/pr/Documents/GitHub/RAG-V1.worktrees/favorite/service/favorite/`
> **判定基准**：**B 侧是唯一 oracle**（决策 D16）⇒ L1 契约层 / L2 业务语义层 / L4 机制层**全部以 B 侧为准** ⇒ 差异默认判 **C 侧错**，**不回溯第三方**。

---

## 〇 · 两侧坐标与规模（**实测**，非旧报数字）

| 项 | B 侧（RAG） | C 侧（RAG-V1） |
|---|---|---|
| 模块数 | **3** 个 jar 模块（`favorite-api` · `favorite-common` · `favorite-rpc`）＋ `favorite-proto` | **1** 个 jar 模块（`service/favorite`，artifactId `favorite-service`）＋ **根级 `proto/`** 模块 |
| 进程数 | **2** 个可执行进程（api · rpc）＋ **3** 个 fact 子命令（dispatch / authority / legacy-preflight） | **1** 个 JVM（**D1**：api + rpc 合并）＋ 3 个 fact 组件落成域内组件（dispatch / authority → `wiring/`；legacy-preflight → `service/` 报表 + `wiring/` Actuator 端点） |
| 主代码 | **75 个文件 / 12,574 行**（api 36/3,846 · common 4/872 · rpc 35/7,856） | **63 个文件 / 13,426 行**（**2026-11-05 实测**） |
| DDL | **7 个 Flyway 脚本** | **7 个 Flyway 脚本**（**SQL 正文逐字零差异**） |
| Boot | **Boot 4**（Spring 7） | **Boot 3.5.11**（Spring 6.2） |
| Servlet 容器 | **Tomcat 11** | **Tomcat 10.1** |
| Jackson | **Jackson 3**（`tools.jackson.*`） | **Jackson 2.19.4**（`com.fasterxml.jackson.*`）⇒ **0 处** `tools.jackson` 引用 |
| proto | `favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto`（pb 包 `com.rag.proto.favorite`） |

### C 侧包级明细（**63 个文件 · 13,426 行** · **2026-11-05 实测**）

| 包 | 文件数 | 行数 | 内容 |
|---|---|---|---|
| `service/` | 12 | 4,306 | api 用例（7 合 1）＋ 支撑（3）＋ fact 服务层（7）＋ **legacy 预检报表（1）** |
| `wiring/` | 7 | 1,813 | 进程装配层（nacos 注册 / schema gate / subject-ref gate / 3 个 logger 身份 / fact 调度 / 权威端点 / **legacy 预检 Actuator 端点**） |
| `domain/` | 6 | 1,665 | 4 个 Entity ＋ `domain/model/FavoriteAuthorityFact` ＋ `domain/service/FavoriteDomainService` |
| `dto/` | 16 | 1,214 | `dto/favorite/` 7 ＋ `dto/folder/` 9（immutable record） |
| `mapper/` | 4 | 849 | 4 个 Mapper（MP `BaseMapper` + 自定义 SQL） |
| `contract/` | 3 | 496 | `ErrCodes` + `ErrMsg` + `GrpcErrorMapper`（**域内**，D7） |
| `interceptor/` | 2 | 484 | JWT 鉴权 ＋ Go query 判据闸门 |
| `config/` | 4 | 703 | `FavoriteApiProperties` + `FavoriteRpcProperties` + `FavoriteRpcLabels` + `WebMvcConfig` |
| `metrics/` | 1 | 440 | **指标 + span 合一**（`FavoriteRpcMetrics`） |
| `advice/` | 2 | 437 | 异常处理器 + 响应包装 |
| `exception/` | 1 | 281 | `FavoriteExceptions`（`Rpc` + 4 个哨兵） |
| `client/` | 2 | 165 | 跨域 gRPC 客户端（user-rpc / article-rpc） |
| `grpc/` | 1 | 161 | `FavoriteGrpcService`（`FavoriteServiceImplBase`） |
| （根） | 1 | 412 | `FavoriteServiceApplication` |

> ⚠️ **2026-11-04 首版台账的这张表已过时**（`config/` 记 450 行 / 全域记 ~12,559 行）⇒ 那是 **2026-11-04「配置面整块缺失」修复轮之前**的数 ⇒ 本轮按**文件系统实测**重列（`config/` 703 行 = 6 块配置补齐 + 两个 Properties 类的增补）。

---

## 一 · B → C 责任承接表（**75 → 63**）

> 映射表：`docs/Mapping_Table/favorite/favorite.md`（**B↔C 两列覆盖 75/75 · 63/63 = 100%** · 机器复算 `tools/check-mapping-coverage.py` EXIT=0）。
> 下表按 **B 侧源文件**逐个展开「职责 → C 侧承接位置」。

### 1.1 `favorite-common/`（4 类 → `contract/` 3 类 + `metrics/` 1 类）

| B 侧源文件 | B 侧职责 | C 侧承接 | 映射 | 状态 | 证据强度 |
|---|---|---|---|---|---|
| `common/errmsg/FavoriteErrorCode.java` | 24 个业务码常量 | `contract/ErrCodes.java`（24 个 `int`） | 1:1 | ✅ | **E-2**（逐条常量值 + **声明顺序**照抄，`CODE_SERVER_BUSY=1015` 排在 5001 前） |
| `common/errmsg/FavoriteErrorMessages.java` | 24 条文案 + 未知码回落 | `contract/ErrMsg.java`（`LinkedHashMap` 保序） | 1:1 | ✅ | **E-2** + E-3（见 §二·2.2） |
| `common/errmsg/FavoriteGrpcError.java` | 业务码 ⇄ gRPC status 的**互逆往返**（**文案反查**，无 trailer） | `contract/GrpcErrorMapper.java` | 1:1 | ✅ | **E-2** + E-3（往返测试） |
| `common/span/FavoriteSpans.java` | 16 个 span 名 + 7 个 `biz.*` 属性 key + 3 个 traced 入口 | `metrics/FavoriteRpcMetrics.java`（**与指标合并**） | 1:1 | ✅ | **E-2**（映射表 §1.8） |

### 1.2 `favorite-api/`（36 类 → `controller/` + `service/` + `advice/` + `interceptor/` + `config/` + `dto/` + `client/`）

| B 侧源文件 | B 侧职责 | C 侧承接 | 映射 | 状态 | 证据强度 |
|---|---|---|---|---|---|
| `api/controller/FavoriteController.java`（265 行 · 7 端点） | 7 个 HTTP 端点 + **空 struct body 校验** | `controller/FavoriteController.java` | 1:1 | ✅ | **E-2** + E-3（真 Tomcat） |
| `api/service/` 7 个用例类（75+102+72+75+75+83+77 = **559 行**） | 7 个 HTTP 用例 | `service/FavoriteApiService.java`（7 方法） | **N:1** | ✅ | **E-2**（逐条判据见 §三） |
| `api/service/FavoriteApiSupport.java`（500 行） | 身份解析 / proto→DTO / 业务日志 / RPC 失败还原 | `service/FavoriteApiSupport.java` | 1:1 | ✅ | **E-2** |
| `api/advice/GlobalExceptionHandler.java` | 错误信封（**本域特有 5003**） | `advice/FavoriteApiExceptionHandler.java` | 1:1 | ✅ | **E-2** + E-3 |
| `api/advice/GlobalResponseAdvice.java` | 响应包一层 | `advice/FavoriteApiResponseAdvice.java` | 1:1 | ✅ | **E-2** |
| `api/interceptor/FavoriteJwtAuthInterceptor.java` + `auth/` 3 类（verifier + result + paths） | 验签 + 黑名单 + 受保护路由表 | `interceptor/FavoriteJwtAuthInterceptor.java`（**4 合 1 自包含**） | **N:1** | ⚠️ **有界偏离**（见 §五·漂移 A） | **E-2** |
| `api/interceptor/FavoriteGoQueryLimitInterceptor.java`（131 行） | Go query 判据闸门（**7/7 端点**） | `interceptor/QueryLimitInterceptor.java` | 1:1 | ✅ | **E-2**（判据复用共享层 `GetFormValuesFilter#goParseViolation`） |
| `api/config/WebMvcConfig.java`（133 行） | 拦截器注册 + 关闭响应侧内容协商 | `config/WebMvcConfig.java` | 1:1 | ✅ | **E-2** |
| `api/config/FavoriteApiProperties.java`（169 行） | `favorite-api.*` + 密钥 fail-closed 守卫 | `config/FavoriteApiProperties.java` | 1:1 | ✅ | **E-2** |
| `api/dto/` 15 个 DTO | HTTP 出入参 | `dto/favorite/` 7 + `dto/folder/` 9 | N:1 | ✅ | **E-2** + E-3（JSON 形状） |
| `api/client/FavoriteRpcClient.java`（130 行） | api → rpc 的 gRPC 客户端 | —— | **删除** | ✅ | **D2 域内直连**（见 §四·4.1） |
| `api/FavoriteApiApplication.java`（47 行） | api 进程入口 | `FavoriteServiceApplication.java` | **N:1**（与 rpc 入口合并） | ✅ | **E-2** |

### 1.3 `favorite-rpc/`（35 类 → `domain/` + `mapper/` + `service/` + `client/` + `grpc/` + `wiring/` + `exception/` + `config/`）

| B 侧源文件 | B 侧职责 | C 侧承接 | 映射 | 状态 | 证据强度 |
|---|---|---|---|---|---|
| `rpc/service/FavoriteDomainService.java` | 7 个用例（领域层） | `domain/service/FavoriteDomainService.java` | 1:1 | ✅ | **E-2** |
| `rpc/service/` 其余 8 个（`FavoriteRpcSupport` / `FavoriteUserGuard` / `FavoriteArticleSnapshotResolver` / `FavoriteFactEnvelopeFactory` / `FavoriteAssertionSchemaResolver` / `FavoriteFactWriteService` / `FavoriteDeliveryValidator` / `FavoriteAuthorityLookupService` / `FavoriteFactDispatchService`） | 支撑 + 事实链路 9 类 | `service/` 9 个类（包压平） | 1:1 | ✅ | **E-2** |
| `rpc/domain/` 5 个 Entity | 4 表映射 | `domain/entity/` 4 个 | 1:1 | ✅ | **E-2** + E-3（真 PG） |
| `rpc/mapper/` 4 个 + `JsonbTypeHandler` | SQL 逐条 | `mapper/` 4 个 | 1:1（handler → `common/`） | ✅ | **E-2** + E-3（真 PG） |
| `rpc/mapper/JsonbTypeHandler.java` | jsonb type handler | `common/persistence/JsonbTypeHandler.java`（**D3 收编**） | 1:1 | ✅ | **D3** ⇒ **域内 0 副本** |
| `rpc/config/FavoriteRpcProperties.java`（214 行） | `favorite.*` 配置 | `config/FavoriteRpcProperties.java` | 1:1 | ✅ | **E-2** |
| `rpc/config/FavoriteRpcLabels.java` | label 取值 | `config/FavoriteRpcLabels.java` | 1:1 | ✅ | **E-2** |
| `rpc/service/impl/FavoriteServiceGrpcImpl.java`（146 行） | gRPC 服务端 | `grpc/FavoriteGrpcService.java` | 1:1 | ✅ | **E-2** + E-3（真传输） |
| `rpc/exception/FavoriteExceptions.java` | 异常族 | `exception/FavoriteExceptions.java` | 1:1 | ✅ | **E-2** |
| `rpc/client/` 2 个（`UserRpcClient` / `ArticleRpcClient`） | 跨域 gRPC 客户端 | `client/` 2 个 | 1:1 | ✅ | **E-2** |
| `rpc/bootstrap/` 4 个（`FavoriteNacosRegistration` / `FavoriteSchemaGate` / `FavoriteSubjectRefGate` / `FavoriteFactProcessLogging`） | 进程装配 | `wiring/` 4 个 | 1:1 | ✅ | **E-2** |
| `rpc/fact/FavoriteAuthorityEndpointConfig.java`（585 行） | 权威读端点（Servlet） | `wiring/FavoriteAuthorityEndpointConfig.java` | 1:1 | ✅ | **E-2** |
| `rpc/fact/FavoriteFactDispatchScheduler.java`（175 行） | 事实投递调度 | `wiring/FavoriteFactDispatchScheduler.java` | 1:1 | ✅ | **E-2** |
| `rpc/fact/FavoriteLegacyPreflightRunner.java`（231 行） | 一次性预检报表 | `service/FavoriteLegacyPreflightService.java`（**报表计算**）+ `wiring/FavoriteLegacyPreflightEndpoint.java`（**Actuator 触发入口**） | **1:N** | ✅ | **E-2**（逐条照抄 Go `favorite_legacy_preflight.go:30-96` + B 侧 `:82-231`）· **E-3**（真 PG 7 例 + 真 Tomcat 5 例）⇒ **P6-c 拍板①（2026-11-05）⇒ 缺口闭合**，详见 §五·5.1 / §八·8.6 |
| `rpc/FavoriteRpcApplication.java`（113 行） | rpc 进程入口 | `FavoriteServiceApplication.java`（与 api 入口合并） | **N:1** | ✅ | **E-2** |
| `rpc/src/main/resources/db/migration/V1..V7` | 7 个 DDL 脚本 | `service/favorite/src/main/resources/db/migration/V1..V7` | 1:1 | ✅ | **E-2**（**SQL 正文逐字零差异**）+ E-3（真 PG） |

---

## 二 · L1 契约逐条核对

### 2.1 路由契约（**7 个端点 · method + path 逐条一致**）

| Method | Path | 入参通道 | C 侧承接 |
|---|---|---|---|
| `POST` | `/favorite/v1/folder/create` | JSON body | `@PostMapping("/folder/create")` |
| `DELETE` | `/favorite/v1/folder/delete` | JSON body | `@DeleteMapping("/folder/delete")` |
| `GET` | `/favorite/v1/folder/list` | 无 body（**但仍要解析并校验 body**） | `@GetMapping("/folder/list")` |
| `POST` | `/favorite/v1/folder/update` | JSON body | `@PostMapping("/folder/update")` |
| `POST` | `/favorite/v1/item/create` | JSON body | `@PostMapping("/item/create")` |
| `DELETE` | `/favorite/v1/item/delete` | JSON body | `@DeleteMapping("/item/delete")` |
| `GET` | `/favorite/v1/item/list` | **query string（form 通道）** | `@GetMapping("/item/list")` |

- **B 出处**：B 侧 `api/internal/handler/routes.go:15-60`（`rest.WithPrefix("/favorite/v1")` + 7 条 `rest.Route`）＋ `api/controller/FavoriteController.java:74-215`。
- **method 分布** = **Get2 / Post3 / Delete2**（**不是**「统一 POST」）—— 与 C 侧空壳 javadoc 写的「端点数 6」和「有 public 段」**都矛盾** ⇒ 已订正（映射表 §7.3 的 M1 / M2）。
- **鉴权划分**：**public 路径集为空**（7/7 受保护）⇒ C 侧 `WebMvcConfig` **不加** `excludePathPatterns`。
- ⚠️ **DELETE 也带 JSON body**：B 侧 `httpx.Parse` 对所有 method 都跑 `ParseJsonBody` ⇒ C 侧 `@DeleteMapping` + `@RequestBody` 同款。

### 2.2 错误码与文案（**24 条 · 逐条一致**）

| 段 | 码 | 说明 |
|---|---|---|
| 成功 / 兜底 | `200` / `500` | `SUCCESS` / `ERROR`（**不进反查表**） |
| 熔断兜底 | `1015` | `CODE_SERVER_BUSY` —— **声明顺序排在 5001 之前**（B 侧同款） |
| 通用 | `5001`–`5009` | server_common / not_found / invalid_param / unauthorized / forbidden / already_exist / db_select / db_update / generate_id |
| 用户 | `5101`–`5105` | user_not_found / user_exists / password_invalid / token_invalid / token_expired |
| 收藏夹 | `5201`–`5203` | folder_not_found / folder_name_empty / folder_name_exists |
| 收藏 | `5301`–`5304` | favorite_not_found / favorite_already_exist / favorite_target_empty / history_blocked |

- **文案**：24 条**逐字照抄**；**本域唯一英文 = `5002 = "not found"`**；未知码回落 **`"FAIL"`**；**24 条两两互不相同**（B 侧有断言钉死）。
- ⚠️ **本域特有**：**解析 / 校验失败 = `5003`**（不是 like 域的 5001）⇒ 由 `advice/FavoriteApiExceptionHandler` 的 4 个分支分别产出。

### 2.3 错误响应形状（**4 种**）

| 情形 | HTTP | body | C 侧落点 |
|---|---|---|---|
| 解析 / 校验失败 | **200** | `{code:5003, msg:"参数错误", data:null}` | `handleValidation` / `handleUnreadableBody` / `handleIllegalArgument` / `handleMediaTypeNotSupported` |
| 业务失败 | **200** | `{code:<业务码>, msg:<文案>, data:null}` | `handleBusiness` |
| 鉴权失败 | **401** | `{code:5004, msg:"未登录或登录已失效", data:null}` | `handleAuthentication` ⇒ **签字偏离 `DEV-FAV-API-001`** |
| 路径 / 方法 | **404 / 405** | text/plain（B 侧 router 形状） | `handleNoResourceFound` / `handleMethodNotSupported` ⇒ 共享层 `RouterErrorResponses` |

### 2.4 DTO 的 JSON 形状（**本域最隐蔽的 L1 面**）

| 项 | B 侧 | C 侧 |
|---|---|---|
| 出参 id | **字符串**（`folderId` / `favoriteId` ⇒ `"123"`） | ✅ 同（`String.valueOf(proto.getXxxId())`） |
| 出参时间戳 | **数字**（`createdAt` / `updatedAt`） | ✅ 同（`long`） |
| 入参 `,string` | **宽松**（`"123"` 与 `123` 都收；`1.5` / `true` 拒） | ✅ 同（共享层 `StringInt64.parse`） |
| 入参 `,optional` | `title` / `cover` 缺席与显式 null **都合法** | ✅ 同（**不加** `@NotNull`） |
| 列表 | **永远非 null**（空 = `[]`） | ✅ 同（**不加** `@JsonInclude(NON_NULL)`） |
| `user_id` | **不映射**到 HTTP（只在 rpc 层） | ✅ 同（`toFolderDto` / `toItemDto` 刻意不取） |

### 2.5 proto 契约

- `proto/src/main/proto/favorite.proto` —— **复制粘贴**（非翻译）：message 名 / 字段名 / **字段号** / 类型 10/10 逐条一致；改动**仅限 option 块**（`java_package = "com.rag.proto.favorite"` + `java_multiple_files = true`）。
- gRPC service 名 `favorite.FavoriteService` ⇒ 方法路径 `/favorite.FavoriteService/*`；**7 个 rpc**。
- ⚠️ **效力边界**（§3.4）：proto 一致 **推不出** wire 行为一致（`optional` presence · 字段号硬编码 · Jackson 配置差异）⇒ 需 wire 级证据（见 §七）。

### 2.6 表结构与约束

- **4 张表**：`favorite_folder` / `favorite_item` / `favorite_fact_outbox` / `favorite_legacy_fact_marker`。
- **2 个不可变触发器**：`favorite_fact_identity_immutable` + `favorite_legacy_marker_immutable`。
- **7 个 Flyway 脚本**：SQL 正文**逐字零差异**（diff / sed 去注释 / tail-cmp **三层**验证全过）⇒ B 侧原文即 C 侧文件末段**连续字节后缀**，仅文件头新增 C 侧落点注释。
- ⚠️ **独立账本**：`flyway_schema_history_favorite` + `baseline-on-migrate: true` + **`baseline-version: 0`**（D17 已定 users 域不用 Flyway；**其余域按 B 侧做法 + 已有 4 域先例**落地）。
- ⚠️ **映射表 §2.1 漏记** `favorite_item_target_revision_scope` CHECK（V6 ③ 的**有意加强**，B 侧 `DEV-FAV-RPC-007`）⇒ **已补记**。

---

## 三 · L2 业务语义逐条核对

### 3.1 7 个 HTTP 用例的判据（**逐条保留**）

| 用例 | B 侧判据 | C 侧落点 |
|---|---|---|
| 建收藏夹 | 下游 `null` ⇒ **5001**（`nullResponse`） | `FavoriteApiService#createFavoriteFolder` |
| 建收藏 | 下游 `null` ⇒ **5001** | `#createFavorite` |
| 删收藏夹 | 下游 `null` **或** `success=false` ⇒ **5001**（`nullOrUnsuccessfulResponse`） | `#deleteFavoriteFolder` |
| 删收藏 | 同上 | `#deleteFavorite` |
| 改收藏夹名 | 同上 | `#updateFavoriteFolderName` |
| 列表收藏夹 | 下游 `null` ⇒ **5001**；`folders` **永远非 null** | `#getFavoriteFolders` |
| 列收藏 | 同上；`favorites` **永远非 null** | `#getFavoritesByFolder` |

> ⚠️ **`nullResponse` 与 `nullOrUnsuccessfulResponse` 是两条独立出口**（B 侧措辞不同：`"returned nil response"` vs `"returned unsuccessful response"`）⇒ **不合并** ⇒ 合并会少掉一条 B 侧存在的业务日志措辞。

### 3.2 span 属性「刻意不统一」的三处（**不可顺手统一**）

| 属性 | B 侧取值 | 下游请求里的值 |
|---|---|---|
| `biz.target_id` | **TrimSpace 过** | **原值**（不 trim） |
| `biz.target_type` | **原样**（不 lower、不 trim） | 同 |
| `biz.folder_name` | **原样**（不 trim） | 落库时用 `normalizeFolderName`（**另一个值**） |

⇒ C 侧 `FavoriteApiService` 逐条照抄 ⇒ **属性与请求体可以是两个不同的值**（这是 Go 的形状，不是 bug）。

### 3.3 鉴权不对称：**7/7 都要 uid**

- B 侧 7 个 logic **每个**第一件事都是 `extractUserID(ctx)` ⇒ claim 解析失败 ⇒ **HTTP 200 + `{code:5004}`**（**不是** 401；401 只由验签失败 / 黑名单命中产生）。
- C 侧 `FavoriteApiSupport#userId` 的 5 类型分支（`String` / `BigInteger` / 整型族 / **`Double`·`Float` 判失败** / 兜底）逐条照抄。

### 3.4 领域层的 12 条文章快照判定

- `FavoriteArticleSnapshotResolver`：`<id>:r<N>` 编码 · `Long.parseUnsignedLong` + **显式挡负号与前导 `+`** · `publication_gap` 规则 · `articleCover` **三级回退（顺序不可换）** ⇒ 逐条照抄。

### 3.5 事实链路（fact）

- **v1 / v2 两套 `subject_ref`**：v1 = **3 字段** `{authority_id, tenant_id, subject_id}`（favorite **自持**，不能用共享层的 v2 两字段）；v2 = **2 字段** `{issuer, subject_id}` ⇒ `toV2()` **必须丢弃 `tenant_id`**（保留它就是 wire 破坏）。
- **锁序** folder → item → assert/marker；`@Transactional` 落在**独立 Bean**（避免 self-invocation 静默失效）。
- **投递路径用 `TransactionTemplate` 显式 commit**；3 个写状态分支**刻意不** `setRollbackOnly`。

---

## 四 · L4 机制层核对（Boot 4 → 3.5 的**框架差异清单**）

| # | 项 | B 侧机制 | C 侧机制 | 是否影响业务行为 | 适配方式 |
|---|---|---|---|---|---|
| 4.1 | **域内自我 gRPC** | api → gRPC → rpc 进程 | **D2 域内直连** `FavoriteDomainService` | ✅ 行为等价（错误映射见下） | `FavoriteApiSupport#invokeRpc` 增 **`FavoriteExceptions.Rpc` 分支** ⇒ 业务码不被换掉；非 status 异常兜底 = **1015**（B 侧 `status.FromError` 的 `ok=false` 分支） |
| 4.2 | **Jackson 主版本** | Jackson 3（`tools.jackson`） | **Jackson 2**（`com.fasterxml.jackson`） | ⚠️ `JacksonException` 由 unchecked 变 checked ⇒ catch 块改写 | 业务 DTO 用 `@JsonCreator` + `@JsonProperty` 显式绑定；`assertGoEmptyStructBody` 的 catch 改为 `JsonProcessingException` + `IOException` 两层 |
| 4.3 | **Jackson coercion** | 域内 `JsonMapperBuilderCustomizer` | **共享层** `JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer` | ✅ 同一份规则 | favorite **无域内补强** ⇒ **不注册**域内 customizer（避免与共享层重复） |
| 4.4 | **必填校验** | 手写 `GoStyleRequired.field`（**只判 null**） | **Bean Validation** `@NotNull`（**只判 null**） | ✅ 等价（空串放行、缺席/显式 null 被拒） | **A10** 禁用手写校验器 |
| 4.5 | **Tomcat** | 11 | **10.1** | ⚠️ `max-parameter-count` / `relaxed-query-chars` / `max-http-request-header-size` 三旋钮 | yaml **显式写**三旋钮（照抄 B 侧 favorite-api 的 yaml）⇒ `max-parameter-count: 2048` 会被共享层抬到 **10001**（**两侧同形**） |
| 4.6 | **响应侧内容协商** | go-zero **不做**协商（无条件 `application/json`） | Spring 默认**按 `Accept` 选转换器** | 🔴 **会**：默认下 `Accept: text/plain` ⇒ **HTTP 500** | `WebMvcConfig#configureContentNegotiation` ⇒ `ignoreAcceptHeader(true)` + `FixedContentNegotiationStrategy` |
| 4.7 | **鉴权拆分** | 验签在 **Servlet Filter**（3s 预算**之外**）＋ 黑名单/响应在 Interceptor（预算**之内**） | **1 个 HandlerInterceptor** | ⚠️ **计时口径偏离**（见 §五·漂移 A） | 登记为有界偏离 |
| 4.8 | **gRPC 保护链** | 域内自建 `rpcInterceptorConfigurer` | **共享层** `ProtectionAutoConfiguration#seaGrpcServerConfigurer`（**唯一**挂载点） | ✅ 链序一致（recover → stat → prometheus → breaker → shedding） | **A3 断言 PASS**（域内 0 重复） |
| 4.9 | **错误契约载体** | **无 trailer**，业务码靠 **description 文案反查** | 同（**刻意不引入 trailer**） | ✅ 等价 | `GrpcErrorMapper` 逐条复刻；fallback 白名单**恰 6 个**（`FailedPrecondition` **刻意不在**） |
| 4.10 | **`grpc-inprocess` scope** | B 侧 pom **显式警告**：不得声明成 `test` scope | 🔴 **C 侧原写法 = `test`** | 🔴 **会**：生产 fat jar **起不来**（`NoClassDefFoundError`） | **已修为 `runtime` scope** + **B 侧警告块整段搬入** |

---

## 五 · 逐项缺口处置

### 5.1 ✅ 缺口 1 · **preflight 触发入口**（映射表 §1.6 / M3）—— **2026-11-05 闭合**

- **B 侧**：`rpc/fact/FavoriteLegacyPreflightRunner.java`（231 行）—— 一次性只读 RR 快照报表，用**退出码**表达结果（`exit 2` = 未裁决 / `exit 1` = 读取失败 / `exit 0` = clear）。
- **决策**：**P6-c 拍板 = ① Actuator 自定义端点**（2026-11-05 · 用户定）⇒ 缺口闭合。
- **C 侧落点（B 侧 1 个类 → C 侧 2 个文件）**：
  | 文件 | 职责 | 基准 |
  |---|---|---|
  | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteLegacyPreflightService.java`（224 行） | **报表计算**（`Report` 为 immutable record · 5 个字段名与顺序由 `@JsonProperty` 钉死） | Go `favorite_legacy_preflight.go:30-96` ＋ B 侧 `FavoriteLegacyPreflightRunner.java:82-231` |
  | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteLegacyPreflightEndpoint.java`（158 行） | **触发入口**（`@WebEndpoint(id = "favorite-legacy-preflight")` ＋ `@ConditionalOnProperty(favorite.fact.legacy-preflight.enabled=true)`） | B 侧 `@EventListener(ApplicationReadyEvent.class)`（`:107`）→ 改为**按需触发** |
- **退出码语义的替代**：Spring 进程内**没有退出码** ⇒ C 侧改用 **HTTP 状态码**（**200** = clear / **409** = 未裁决 / **500** = 读取失败）**＋** **结构化日志**（`event=favorite.legacy_preflight` ＋ `outcome=succeeded|not_reconciled|failed` ＋ `error_code` ＋ 5 个报表字段 · 进程身份 `rtw-favorite-legacy-preflight`）**双通道** ⇒ ⇒ 登记 **`DEV-FAV-RPC-009`**。
- **事务**：B 侧 `preflight()` 的 `@Transactional(readOnly=true, isolation=REPEATABLE_READ)` 在，但**只被同 bean 的 `run()` 自调用** ⇒ Spring AOP 代理被绕过 ⇒ **事务从未生效** ⇒ ⇒ C 侧**刻意不加** `@Transactional`（照抄 B 侧**实际行为**）⇒ ⇒ 登记 **`DEV-FAV-RPC-008`**。
- **默认不暴露**：两个开关（`favorite.fact.legacy-preflight.enabled` ＋ `management.endpoint.favorite-legacy-preflight.enabled`）都默认 **false** ⇒ 端点**不注册** ⇒ 访问得 **404** ⇒ ⇒ **健康检查 / K8s 探针不会误触发预检**（真触发断言见 `http/FavoriteLegacyPreflightEndpointTest#healthCheckDoesNotTriggerPreflight`）。
- **⚠️ 附带差异**：Actuator 的 JSON 响应被本域全局 `@RestControllerAdvice` **包成业务信封** ⇒ 但 B 侧 api 进程**同样如此** ⇒ ⇒ **C ≡ B（api 面）** ⇒ ⇒ **不收窄** advice 的作用域（收窄会让 C ≠ B）⇒ ⇒ 登记 `DEV-FAV-RPC-009` ＋ 守护断言 `http/FavoriteLegacyPreflightEndpointTest#actuatorJsonResponsesAreWrappedByTheDomainEnvelope`。
- **证据**：真 PG **7 例**（`mapper/FavoriteLegacyPreflightParityTest`）＋ 真 Tomcat **5 例**（`http/FavoriteLegacyPreflightEndpointTest`）＋ 默认配置 **404 对照 1 例**（`http/FavoriteApiRealTomcatGateTest`）⇒ ⇒ **12 例全绿** ⇒ 详见 **§八·8.6**。

### 5.2 ✅ 缺口 2 · **Flyway 账本拍板项** —— **2026-11-05 已签字**

- `docs/决策记录.md` **D17** 只定了「users 域不用 Flyway」，**其余 7 域待拍板**；favorite 属其中。
- **本轮落地口径**（按 **B 侧做法** + **已有 4 域先例**）：**独立账本表名** `flyway_schema_history_favorite` + `baseline-on-migrate: true` + **`baseline-version: 0` 必钉** + `locations: classpath:db/migration` + `schemas: public`。
- ⚠️ **`baseline-version` 必须钉 0**：默认 1 ⇒ baseline 打在 V1 ⇒ **V1 的 SQL 从未执行** ⇒ `favorite_folder` 表永远建不出来。
- ⚠️ **这 4 项失效时应用照样能起来、测试照样能跑绿**（schema 空时首次迁移正常）⇒ 只能靠「schema 预先非空 + 账本不存在」的真触发场景才暴露。
- ✅ **已签字（2026-11-05 · 用户）**：**同意按「B 侧做法 + 4 域先例」落地** —— 独立账本表名 `flyway_schema_history_favorite` + `baseline-version: 0` ⇒ ⇒ **本项不再是待拍板项** ⇒ ⇒ 本域**落点缺口与拍板缺口均已归零**。
  ⇒ ⇒ 口径若将来要改 ⇒ **回改 `application.yml` 一处**（`spring.flyway.table` + `spring.flyway.baseline-version`）即可 ⇒ **不需要**改任何迁移脚本（脚本文件名与正文一字不动）。

### 5.3 🟠 漂移 A · **鉴权计时口径偏离**（B 侧 4 类合成 1 个 Interceptor）

| | B 侧 | C 侧 |
|---|---|---|
| 验签 | **Servlet Filter**（在 3s 预算**之外**） | **HandlerInterceptor**（在预算**之内**） |
| 黑名单 / 响应 | Interceptor（预算**之内**） | 同一个 Interceptor |

⇒ **可观察差异**：验签耗时**计入** C 侧的 3s 请求预算 ⇒ 一个「验签极慢」的请求在 B 侧不受预算约束、在 C 侧会被预算掐断。
⇒ **处置**：**登记为有界偏离**（B 侧拆分的动机是「验签不该占业务预算」⇒ C 侧未拆）⇒ **不修**（修它要新增 1 个 `OncePerRequestFilter`，且 B 侧的拆分理由在 C 侧**未必成立** —— C 侧的验签是本地 HMAC，不含网络往返）。
⇒ ⚠️ **C 侧 File_Tree 无 `filter/` 包** ⇒ 与 like 域**同款取舍**。

### 5.4 🟠 漂移 B · **映射表 §2.1 漏记 `favorite_item_target_revision_scope` CHECK**

- B 侧 `DEV-FAV-RPC-007`（**已签字**）：`favorite_item` 上多一道 CHECK ⇒ **B 侧比 Go 更严**。
- C 侧 V6 ③ **逐字照抄** ⇒ 行为一致；**但映射表 §2.1 没记这一条** ⇒ **已补记**（映射表 M4 的「⚠️ 附带缺口」行 + 本台账 §二·2.6）。

### 5.5 🟠 漂移 C · **V6 的 `pg_constraint` 无 schema 限定**

- V6 脚本里 `pg_constraint` 的查询**未带 `connamespace` 限定** ⇒ 在**多 schema** 库里可能命中同名约束 ⇒ ⚠️ **已登记为有界风险**（本仓各域共用 `public` schema ⇒ 当前不可触发）。
- **处置**：**不修**（改了 ⇒ SQL 正文不再逐字零差异 ⇒ 引入新的偏离）⇒ 登记。

### 5.6 ✅ 已闭合的映射表缺口（M1 / M2 / M4 / M6 · **M3 于 2026-11-05 闭合 ⇒ 见 §5.1**）

| # | 缺口 | 闭合方式 |
|---|---|---|
| **M1** | Controller javadoc 漏 `POST /folder/create`（只列 6 条） | ✅ **重写**：7 条全列，method 分布写明 Get2/Post3/Delete2 |
| **M2** | Controller / WebMvcConfig javadoc 写「有 public 段」 | ✅ **重写**：写明「**7/7 受保护，public 集为空**」⇒ 实现**不加** `excludePathPatterns` |
| **M4** | `db/migration/` 只有 `.gitkeep` | ✅ **7 个脚本落地**（SQL 正文逐字零差异） |
| **M6** | `GetFavoritesByFolderReq` 未标 form 语义；`GetFormValuesFilter` 空壳 | ✅ DTO javadoc 已标 form + `,string` + 「先滤空值再取首值」；**共享层 `GetFormValuesFilter` 已实现**（`goParseViolation`） |

### 5.7 🔴🔴 漂移 D · **配置面整块缺失（6 处）—— 2026-11-04 真启动实测抓出并修复**

> ⚠️ **这一节的全部 6 条都不是「读代码看出来的」** ⇒ 都是**真 fat jar 起起来之后**才暴露的
> ⇒ ⇒ 是 AGENTS.md §3.3.1「**「配置里没写」≠「没开」**」的**六个实例**。
> ⇒ **共同形态**：B 侧把配置放在**两个面**（`favorite-api` / `favorite-rpc`）各自的 yaml 里
> ⇒ ⇒ C 侧合并成一个进程（**D1 + D7**）⇒ ⇒ **搬 yaml 时只搬了一面** ⇒ ⇒ 另一半的配置**整块没搬过来**。

| # | 缺失的配置 | B 侧出处 | C 侧缺失时的可观察后果 | 处置 |
|---|---|---|---|---|
| **D-1** | `rag.protection.outbound-deadline.cap-millis` | api `:194-197` / rpc `:219-221`（两侧都是 **2000**） | 共享层 `capMillis` 落回默认 **0 = 不启用** ⇒ **出向 gRPC 零期限** ⇒ 下游不可达时**永久挂起**（不是超时） | ✅ **已补** `2000` |
| **D-2** | `observability.http-timeout` / `rpc-timeout` | api `:243-246`（**3s**）/ rpc `:374-376`（**2s**） | 共享层两个 timeout **无 env 兜底**、缺省 `Duration.ZERO` ⇒ **3s HTTP 预算与 2s gRPC 超时都不存在** ⇒ 慢请求不会被切断 | ✅ **已补** `3s` / `2s` |
| **D-3** | `otel:`（service.name / exporter / resource / sampler） | api `:221-230` / rpc `:352-362` | OTel 不导出 trace；服务名不是合并后的名字 | ✅ **已补**（合并裁决见下） |
| **D-4** | `management.metrics.tags.application` | api `:216-217` / rpc `:341-344` | 抓取侧按标签过滤**一条都匹配不到**（B 侧 `FavoriteRpcPrometheusCarrierGateTest:232-233` 断言过这个标签） | ✅ **已补** `favorite-service` |
| **D-5** | `management.endpoints.enabled-by-default` + 三个 endpoint 的 `enabled` + `show-details` | api `:200-215` / rpc `:333-351` | 未显式开启的端点仍暴露；`show-details: always` 比 B 侧**两个面都松**（api 侧 Boot 默认 `never`、rpc 侧 `when_authorized`） | ✅ **已补**（取**更严**的一侧 `when_authorized`） |
| **D-6** | `spring.cloud.nacos.discovery.register-enabled: false` | **rpc `:140-156`（含完整理由）** | 🔴 **自动注册注册的是 web 容器端口** ⇒ 与手写的 gRPC 端口注册**同名两个端口** ⇒ 调用方按 Nacos 给出的地址连过来 ⇒ **gRPC 链路断**（业务码塌成 1015） | ✅ **已补** |

#### D-1 的实测证据（本条最严重：不是「配置不对」，是「行为完全不同」）

| | 修复前 | 修复后 |
|---|---|---|
| `POST /favorite/v1/folder/create`（下游不可达） | curl **10s 超时**（客户端先放弃） | **`total=2.508546s`** ⇒ `{"code":5001,"msg":"系统内部错误","data":null}` |
| 出向调用的 deadline | **无**（`capMillis=0`） | **2000ms**（= B 侧 zrpc 默认） |

⇒ **B 出处**：Go `go-zero@v1.10.2/zrpc/config.go:31`（`Timeout int64 json:",default=2000"`）
+ `zrpc/internal/client.go:113-117`（**无条件**装 `TimeoutInterceptor`）。
⇒ **回归守卫**：`config/FavoriteOutboundDeadlineContractTest`（6 条）——
其中第 2 条是**反向对照**（`capMillis=0` ⇒ 必须**没有** deadline）⇒ ⇒ 保证第 1 条不是恒绿。

#### D-6 的实测证据（同样是「起起来才看得见」）

⇒ 真 fat jar 起来后，Nacos 的服务清单里**同时**出现
`favorite-service`（= 自动注册，指向 **web 端口** 18894）与 `favorite-rpc`（= 手写注册，指向 **gRPC 端口** 19003）
⇒ ⇒ 正是 B 侧 rpc 侧注释里警告的「**错端口实例**」。
⇒ **合并裁决**：`register-enabled: false` + 只保留 `wiring/FavoriteNacosRegistration` 的 gRPC 端口注册
⇒ 与 **article / like / follow 三域同款**。

#### D-3 / D-4 / D-5 的合并裁决（三处都取「显式优于隐式」或「更严的一侧」）

| 项 | B 侧 api | B 侧 rpc | C 侧合并取值 | 理由 |
|---|---|---|---|---|
| otel endpoint | `http://${OTEL_ADDR}:${OTLP_PORT}`（带 scheme） | `${OTEL_EXPORTER_OTLP_ENDPOINT}`（裸 host:port） | **带 scheme** 的那一侧 | 共享层两种都认 ⇒ 显式优于隐式 |
| otel service.name | `favorite-api` | `favorite-rpc` | `favorite-service` | 合并进程 ⇒ 取合并名（article 域同款） |
| otel sampler | `parentbased_always_on` | `parentbased_always_on` | `parentbased_always_on` | 两侧同值 ⇒ 无歧义 |
| `show-details` | 未写（Boot 默认 `never`） | `when_authorized` | **`when_authorized`** | 取**更严**的一侧（like / follow 域同款） |
| `tags.application` | `${spring.application.name}` = `favorite-api` | `favorite-rpc` | `favorite-service` | 合并进程 ⇒ 取合并名（article 域同款） |

#### ⚠️ 与 B 侧**同款**、**本域刻意不修**的两处（继承，不回溯）

1. 🔴 **`FavoriteNacosRegistration` 的 NPE 缺口**（`getNamingService()` 返回 null 时 NPE）：
   B 侧 `favorite-rpc/.../bootstrap/FavoriteNacosRegistration.java:91-98` **逐字同款**
   ⇒ ⇒ **B 侧也有这个缺口** ⇒ ⇒ 按 D16「B 侧已有的差异一律继承」⇒ **不修**
   ⇒ ⇒ 测试侧用 `NacosStubConfiguration` 同款桩绕开（article 域先例）。
2. 🔴 **B 侧 api 面**没有 `register-enabled: false`（因为它没有手写注册类）
   ⇒ C 侧合并后取 **rpc 侧**那一行 ⇒ 这不是「搬错」而是**合并裁决**，已在 D-6 记录。

### 5.8 🔴 漂移 E · **全局 name resolver 被 devh 接管** ⇒ 真传输用例「红绿取决于执行顺序」（2026-11-04 全量 verify 实测抓出并修复）

> **性质判定**：🔴 **测试设施缺陷**，**不是产品行为漂移**
> ⇒ ⇒ **不进 §11.5 的 DRIFT 清单** ⇒ ⇒ **§11.6 的 FAIL 仍然是 0**。
> **为什么值得单列**：它的症状**长得像一条真实的业务回归**（出向 deadline 硬上界失效）
> ⇒ ⇒ 若只查现象、不查根因，**几乎必然误判** ⇒ ⇒ 这正是 AGENTS.md §3.3 要防的那类「静默失效」。

#### 症状（读数本身自相矛盾 ⇒ 这是判据失效的第一个信号）

`FavoriteOutboundDeadlineContractTest#realCallAgainstBlackHoleServerIsCutOffByTheCap`

| 跑法 | 读数 |
|---|---|
| **单独跑**（`mvn -o -pl service/favorite -am test -Dtest=FavoriteOutboundDeadlineContractTest`） | ✅ `Tests run: 7, Failures: 0`（绿 · 耗时 2.296s ⇒ 真的等了 2s 上界） |
| **全量 `verify`** | 🔴 `expected: <DEADLINE_EXCEEDED> but was: <UNAVAILABLE>`（红 · 耗时 0.009s） |

⇒ ⇒ **同一份代码，红绿取决于测试类的执行顺序** ⇒ ⇒ 读数**不可复现**
⇒ ⇒ 违反 AGENTS.md §五·7「连续跑测试的读数要标注『跑法』」与「同一处红 ⇒ 先问是我的错还是环境在动」。

#### 根因（**逐层读源码核实，不是猜的**）

1. `NettyChannelBuilder.forAddress(host, port)` 产生的 target 是**裸 authority**（`"127.0.0.1:<port>"`，**没有 scheme**）
   —— 证据：`grpc-netty-shaded-1.63.0` 字节码
   `forAddress(String,int)` ⇒ `GrpcUtil.authorityFromHostAndPort(host,port)` ⇒ `forTarget(authority)`。
2. grpc 解析 target 时发现**没有 scheme** ⇒ 改用
   `NameResolverRegistry.getDefaultRegistry().getDefaultScheme()`
   —— 证据：`grpc-api-1.63.0` 的 `ManagedChannelImplBuilder#getTargetUri`。
3. `NameResolverRegistry#refreshProviders()` 的规则是
   「**defaultScheme = 优先级最高的那个 provider 的 scheme**」。
4. 优先级：grpc 自带 `DnsNameResolverProvider` = **5**；
   devh `DiscoveryClientResolverFactory` = **6**
   —— 证据：devh 3.1.0 源码注释原文 `return 6; // More important than DNS`。
5. devh 的 `NameResolverRegistration`（Spring bean）在**上下文创建期**把该 provider
   注册进**全局** registry
   —— 证据：devh 源码 `NameResolverRegistration#register(NameResolverRegistry)`。

⇒ ⇒ **只要本 JVM 里创建过任何一个 Spring 上下文**，全局默认 scheme 就**永久**变成 `"discovery"`
⇒ ⇒ 本机字面地址被当成**服务名**交给服务发现
⇒ ⇒ `DiscoveryClientNameResolver: No servers found for 127.0.0.1:<port>`
⇒ ⇒ 通道 `UNAVAILABLE` ⇒ ⇒ **server 明明在监听，客户端却连不上**。

> 📌 **触发条件就是「新增了一个测试类」** —— 它改变了测试类的执行顺序
> ⇒ ⇒ 老用例从「恰好排在所有 Spring 上下文之前」变成「排在后面」⇒ ⇒ 由绿变红。
> ⇒ ⇒ 这也解释了：**为什么这条用例此前一直是绿的**（不是它对，是它**碰巧**对）。

#### 影响面判定（🔴 关键：产品面**不受影响**）

| 面 | 判定 | 依据 |
|---|---|---|
| **产品链路** | ✅ **不受影响** | 本域出向客户端走 devh 的 `grpc.client.*` ⇒ target **显式带 scheme**（`discovery:///user-rpc` / `discovery:///article-rpc`）⇒ **显式 scheme 不会被全局默认覆盖** ⇒ ⇒ 不受该劫持影响 |
| **测试自建通道** | 🔴 **受影响** | `forAddress(String,int)` 走的正是「无 scheme ⇒ 用全局默认」这条路 ⇒ ⇒ 必须改 |

#### 修法（**收敛到本仓既有先例**，不另造平行机制）

- 改法 = `forAddress(new InetSocketAddress(host, port))` ⇒ **该重载不经过名字解析** ⇒ **免疫**该劫持。
- 📌 **本仓先例**：`service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java:79-99`
  （**类注释已完整记录这个机制**）+ `tools/difftest/like/grpc-wire/LikeWireProbe.java:54-63`。
- ⇒ ⇒ 本域**照抄 like 域写法** ⇒ ⇒ **不留第二套机制**
  （中途一度写过一个 `support/LiteralAddressResolverProvider` ⇒ **已删** ⇒ 不制造平行设施）。
- ➕ **新增可执行证据**：`FavoriteGrpcRealTransportTest#defaultNameResolverSchemeIsHijackedByDevh`
  ⇒ 断言本上下文里 `NameResolverRegistry.getDefaultRegistry().getDefaultScheme() != "dns"`
  ⇒ ⇒ **「为什么必须用 SocketAddress」从注释升级成断言**（like 域同款）。
- ➕ **断言消息自带诊断**：`FavoriteOutboundDeadlineContractTest` 第 3 条的失败消息里
  直接写明「拿到 UNAVAILABLE ⇒ 是全局 name resolver 被 devh 接管，**不是**硬上界失效」
  ⇒ ⇒ **下一次再踩，不需要重新查一遍根因**。

#### 残留（**跨域 · 本域写权外 ⇒ 只登记**）

`tools/difftest/follow/grpc-wire/FollowWireProbe.java:196` 与
`tools/difftest/users-user/grpc-wire/UserWireProbe.java:666` 仍用 `forAddress(host, port)`
⇒ ⇒ **当前安全**（独立进程探针 · **不跑 Spring 上下文** ⇒ 默认 scheme 仍是 `dns`）
⇒ ⇒ **潜在**风险：若将来让探针跑在 Spring 上下文里（或与其它用例同 JVM）⇒ ⇒ 会踩同一个坑
⇒ ⇒ **登记给 `global` 收口域 / 主会话**（机制与修法 ⇒ 本节）。

---

### 5.9 🔴🔴 漂移 F · **真 PG 测试类的上下文抢同一个 gRPC 端口** ⇒ 「24 例全红但栈里没有端口冲突」（2026-11-05 全量 verify 实测抓出并修复）

> ⚠️ **这一节是本轮 P6-c 落地时撞出来的** —— **不是 B↔C 的行为漂移**，是**测试基础设施缺陷** ⇒ 但它的排查成本极高 ⇒ ⇒ **必须留档**。

| | 事实 |
|---|---|
| **现象** | `mvn verify` ⇒ **4 个真 PG 测试类共 24 个用例全红**（`FavoriteSchemaParityTest` 13 · `FavoriteOutboxTimeColumnTest` 5 · `FavoriteFolderItemTimeColumnTest` 5 · `FavoriteDdlParityTest` 1）⇒ 报错栈里**只有一句** `ApplicationContext failure threshold (1) exceeded: skipping repeated attempt to load context` ⇒ ⇒ **没有 PG、没有 schema、没有端口冲突**的任何字样 ⇒ ⇒ 极像「PG 容器坏了 / 迁移脚本错了 / schema 漂了」 |
| **真因** | `mapper/FavoritePgParityTestBase`（`FavoriteSchemaParityTest.java:463`）的 `GRPC_PORT` 是**基类的 `private static final int`** ⇒ ⇒ **全子类共享同一个端口**；而新增的 `FavoriteLegacyPreflightParityTest` 的 `@SpringBootTest.properties` 与另外 4 个兄弟类**差两行**（`grpc.client.*.address` 的端口 + `access-secret` 的值）⇒ ⇒ Spring TestContext 的缓存键（`MergedContextConfiguration`）**不同** ⇒ ⇒ 框架**另起第二个上下文** ⇒ ⇒ 两个上下文抢**同一个** gRPC 端口 ⇒ ⇒ **EADDRINUSE** ⇒ ⇒ 第二个上下文 refresh 失败 ⇒ ⇒ Spring 的 **failure threshold 是全局的** ⇒ ⇒ **后续所有**上下文加载一并被跳过 ⇒ ⇒ 24 个用例同时红 |
| **为什么表象如此误导** | 失败点在**上下文加载期**，与「这个用例在验什么」**完全无关** ⇒ ⇒ 报错栈把排查方向指向「容器 / schema / 迁移」⇒ ⇒ 本轮实测**先查了 PG 日志、Flyway 日志、schema 指纹，全部正常**，才回头看上下文缓存键 |
| **修法（两处，缺一不可）** | ① **把 preflight 测试的 `properties` 改成与 4 个兄弟类逐字相同** ⇒ ⇒ 5 个类**共用同一个上下文** ⇒ ⇒ 不再抢端口（这与 4 个兄弟类**本来就共用一个上下文**的既有形态一致）；② **加一条机器守门** `mapper/FavoritePgTestContextSharingTest` —— 反射扫描 `mapper` 包下所有 `FavoritePgParityTestBase` 子类 ⇒ 断言它们的 `@SpringBootTest.properties` **集合数为 1** ⇒ ⇒ 下次有人改了一行 ⇒ **测试直接红**，而不是变成「偶发 24 红」 |
| **变异验证（判据有牙齿）** | 故意把 `FavoriteDdlParityTest` 的 `grpc.client.user-rpc.address` 从 `:1` 改成 `:65535` ⇒ ⇒ 守门**红**，报文精确列出实扫到的 5 个类与「不同 properties 集合数：**2**」⇒ ⇒ 还原 ⇒ **绿** |
| **⚠️ 为什么值得单独留档** | 它是 §五·5.8 的**同族**（都是「测试设施缺陷 ⇒ 失败表象与真因毫无关系」）⇒ ⇒ 家族规律：**「多个用例同时红」的第一嫌疑不是「被测代码坏了」，而是「测试基础设施自己坏了」**（全局静态状态 · 端口 · 上下文缓存键 · 全局注册表）⇒ ⇒ 排查顺序应当是：**先查共享状态，再查被测对象** |

---

### 5.10 ✅ 漂移 G · **DRIFT-FAV-014 · 慢下游时 api 面业务码 B=1015 / C=5001**（2026-11-05 场景轮实测抓出 · **已按方向 ② 修完 · 两侧已同为 1015**）

> ⚠️ **这一条是「场景轮」抓出来的** —— 它**不是**配置漏配、**不是**抄错 ⇒ ⇒ **它有一个结构性根因** ⇒ ⇒ 必须留档 + 必须让用户拍板（**已拍板，见下**）。
> ✅ **2026-11-05 用户拍板：方向 ②**（**C 侧 api 面自持同值 2s 上界、超时落 1015**）⇒ ⇒ **已实现 + 已验证**（下表「修后」两行 + §八·8.7.3 S3 行）⇒ ⇒ **本条不再是 FAIL**。

| | 事实 |
|---|---|
| **现象（抓出时）** | 场景轮 S3（下游桩慢 **5s**）⇒ 两侧**耗时桶相同**（**2000-2499**）⇒ ⇒ **出向上界同值 2s，没有漂**；但**业务码 B=1015 / C=5001** ⇒ **两侧不等** |
| **现象（修后）** | ✅ **两侧业务码同为 `1015`**（`CODE_SERVER_BUSY` · 文案「服务繁忙」两侧逐字一致）⇒ ⇒ **耗时桶仍相同** `2000-2499` ⇒ ⇒ **S3 由 🟠 转 ✅** ⇒ ⇒ **`KNOWN_DIFFS` 已清空**（表结构保留 ⇒ 现在**任何**两侧不等都真报红） |
| **B 侧的机制（oracle 自述）** | B 侧 `favorite-api/src/main/java/com/rag/favorite/favorite/api/client/FavoriteRpcClient.java:108-115` **自己写明了**：「Go 侧下游挂死时是 **2s 先到** ⇒ api 返回 **200 + 业务码 1015**；若只带 3s 的 ctx 预算，就会一路顶到 HTTP 超时分支 ⇒ 返回 503」⇒ ⇒ **1015 是 oracle 预期的行为，不是缺陷**<br>⇒ 落地链：api 面出向 cap = `favorite-api/src/main/resources/application.yml:196-197`（`cap-millis: 2000`）⇒ **api → favorite-rpc 这一跳是自我 gRPC 往返**（`favorite-api/src/main/resources/application.yml:138-139` ⇒ `grpc.client.favorite-rpc.address: discovery:///favorite-rpc`）⇒ ⇒ **这一跳自己也带 2s cap** ⇒ ⇒ 慢下游时 **api 那一跳的 cap 先到**（它在 t=0 起算，rpc 侧那一跳在 t=ε 起算 ⇒ 同为 2s ⇒ api 的更早）⇒ ⇒ api 收到的是**自己的 DEADLINE_EXCEEDED**（**没有业务 description**）⇒ ⇒ `FavoriteGrpcError.bizCodeFromError` 反查不出业务码 ⇒ 落 **`CODE_SERVER_BUSY(1015)`**（`favorite-common/.../FavoriteErrorCode.java:47` · Go `CodeServerBusy = 1015`） |
| **C 侧的机制（修前）** | **D2 域内直连** ⇒ **没有 api→rpc 这一跳** ⇒ ⇒ 2s cap 落在 **service→user-rpc** 那一跳（`service/favorite/src/main/java/com/rag/favorite/service/FavoriteUserGuard.java:99`）⇒ ⇒ 超时异常落进 **`FavoriteUserGuard#onDownstreamFailure`（`:116`）** ⇒ 非 `NOT_FOUND` ⇒ ⇒ `:132` `return FavoriteExceptions.internalServerCommon()` ⇒ **5001** |
| **修法（方向 ② · 已实现）** | **① api 面自持同值上界** —— `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiSupport.java`：构造器注入 `ProtectionProperties` ⇒ 取 **`rag.protection.outbound-deadline.cap-millis`（= 2000，与共享层/出向拦截器**同一个配置键** ⇒ 不是「第二个 cap 来源」）** ⇒ ⇒ `invokeRpc(...)` 用 **try-with-resources** 绑 `GoStyleRequestDeadline.bindAbsoluteNanos(...)`（**不换线程** ⇒ deadline 传播 / OTel 上下文 / 事务边界都不受影响 —— 共享层 `OutboundDeadlineInterceptor` 类头明写「业务自己换线程 ⇒ 读不到载体 ⇒ 本类不生效」）<br>⇒ ② **判据用 cause 链不用时钟** —— `FavoriteApiSupport#resolveBizCode(Exception)` + `#carriesDeadlineExceeded(Throwable)`：**异常链里有 gRPC `DEADLINE_EXCEEDED` ⇒ 落 `ErrCodes.CODE_SERVER_BUSY(1015)`**；否则领域异常走自己的码 ⇒ ⇒ ⚠️ **原判据（`now >= deadline`）已废弃**（绑定被去掉时该判据仍成立 ⇒ **无牙齿 + 有竞态**）<br>⇒ ③ **领域层保留原始 gRPC cause** —— `FavoriteUserGuard#onDownstreamFailure` / `FavoriteArticleSnapshotResolver` 改用 `FavoriteExceptions.internalServerCommon(e)` ⇒ 保留 cause（对齐 B 侧 `helper.go:100-107` / `:139-146` 把原始 gRPC error 交回上层）⇒ ⇒ ④ **不改变 gRPC 面 wire 字节** —— `grpc/FavoriteGrpcService#invoke` **先** catch `FavoriteExceptions.Rpc` ⇒ 只取 `getGrpcCode()` + `getBizCode()` ⇒ ⇒ **gRPC 面仍落 5001**（B 侧 gRPC 面也是 5001 ⇒ 两侧一致） |
| **慢 DB（非慢下游）那一格 —— ✅ 已闭合（2026-11-05 复核订正）** | 🔴 **此前误判为「未闭合」** ⇒ ⇒ **2026-11-05 工作树更新后复核：共享层的 SQL 超时载体 `DeadlineState` **早已落地** ⇒ ⇒ 那一格其实两侧同为 1015**：<br>· **共享层链路（已落地）**：`HttpMiddleware` 在整条 filter chain 上绑 `DeadlineState.bindAbsoluteNanos(min(http-timeout 3s, rpc-timeout 2s) = 2s)` ⇒ ⇒ `DeadlineQueryTimeoutInjector` 按线程上的 deadline 给每条 SQL 设 `queryTimeout` ⇒ ⇒ PG 取消 ⇒ ⇒ `SqlTimeoutStatusTranslator` 把 **57014** 翻成 **1015** ⇒ ⇒ **慢 DB ⇒ 1015**<br>· **B 侧**：api→rpc 那一跳的 2s cap 覆盖**整个 rpc 侧处理（含 DB 写）** ⇒ ⇒ 慢 DB 也落 1015<br>· ⇒ ⇒ **两侧同为 1015** ⇒ ⇒ **该格已闭合** ⇒ ⇒ ⚠️ **本行的错误来源**：`GoStyleRequestDeadline` 类头那句「本仓该引用**暂缺**」是**批次 3 之前的过期文案**（`DeadlineState` 早已落地）⇒ ⇒ **过期注释当成了现状** ⇒ ⇒ 已订正 |
| **根因** | **D2 域内直连的结构性代价** ⇒ 与 **hot 域同根因、两种表现**：<br>· **hot 域** = 把跨进程调用改成同进程直连 ⇒ **出向上界整体消失**（那一边连 cap 都没了）；<br>· **favorite 域** = 出向上界**还在**（合并后 yml 只剩一处 `cap-millis: 2000`，`service/favorite/src/main/resources/application.yml:232`）⇒ **但作用点变了**（从「api→rpc」挪到「service→user-rpc」）⇒ ⇒ 差异从「**有没有**上界」变成「**哪一跳**的 cap 先到」 |
| **判据（为什么不是「漏配」）** | 两侧**耗时桶逐字相同**（2000-2499）⇒ ⇒ **cap 的值与生效性都没漂** ⇒ ⇒ 漂的是**作用点** ⇒ ⇒ 这**不是**配置缺配（不是 DRIFT-FAV-008 那一族），**改 yaml 修不好** |
| **证据强度** | 🟠 **只有 B↔C 双侧活体**（场景轮无 Go 第三方锚 —— 本机无 `Sea-etcd`）⇒ ⇒ 强度**低于** 32 case 的「三方」⇒ ⇒ **不得**混称 E-4 三方 |
| **登记方式（抓出时 → 修后）** | 抓出时：场景轮 `KNOWN_DIFFS` **指纹式**登记（`tools/difftest/favorite/scenarios/scenarios.py`）⇒ 只有 (stage, 字段) **与两侧具体值都逐字相符**才走 **WARN**；**值一变立刻 FAILED** ⇒ ⇒ **它不是豁免，它也有牙齿**<br>⇒ ✅ **修后已清空该表**（**表结构保留** ⇒ 注释写明「表是已知差异的指纹，不是豁免」）⇒ ⇒ 现在**任何两侧不等都真报红** |

#### 三个处置方向（**互斥 · 必须选一个**）—— ✅ **2026-11-05 用户拍板：选 ②**

| 方向 | 做法 | 代价 | 判定 |
|---|---|---|---|
| **①** | **登记签字偏离**（承认业务码漂移） | 本域多一条 `DEV-FAV-*`；调用方拿到 **1015 vs 5001** 两个不同的码 ⇒ 前端可能要分两套处理 | 🟡 可行 · 但**不修** ⇒ 「D2 的代价」永久留在行为面 · ❌ **未采用** |
| **②** | **C 侧 api 面自持同值 2s 上界、超时落 1015**（**复刻 B 的 api→rpc 那一跳**） | api 面多一个**域内自持**的上界 ⇒ **不引入共享层第二个 cap 来源**；超时异常仍落进原来那个 catch（⇒ 指标计数与 B 一致） | ✅ **推荐**（**与 hot 域修法同款**）· ✅ **已拍板并实现**（详见上方「修法（方向 ② · 已实现）」行 + **§8.7.3 S3 行**） |
| **③** | **把 domain 层下游超时映射改成 1015** | ❌ 会让 **C 侧 gRPC 面**与 **B 侧 gRPC 面**也不同（gRPC 面 B 侧仍是 5001）⇒ ⇒ **用一个面换另一个面** | ❌ **不推荐** · ❌ **未采用**（方向 ② 已在**不动 gRPC 面**的前提下达成同样效果） |

> 🔴 **方向 ② 的配套纪律**（照抄 hot 域的教训）：**能力与载体分开核对** ——「B 侧这个方向**有没有**某个上界」是**能力问题**（读 B 侧源码）；「本仓这个上界**挂在谁身上**」是**载体问题**（读本仓装配）⇒ ⇒ 补回时**别给共享层加第二个 cap 来源**（会造出「两个上界来源」）⇒ ⇒ **域内自持一个同值上界**。
>
> ✅ **本轮落地时的三条自检**（都踩过坑）：
> ① **单一来源** —— 域内自持的上界**复用同一个配置键** `rag.protection.outbound-deadline.cap-millis` ⇒ **不是**新写一个常量；
> ② **不换线程** —— 走 **绑定绝对 deadline 载体**（`GoStyleRequestDeadline.bindAbsoluteNanos`）⇒ ⇒ 共享层的出向拦截器仍能读到载体（换线程就读不到了）；
> ③ **判据不用耗时** —— 用 **cause 链里的 gRPC status**（`DEADLINE_EXCEEDED`）⇒ ⇒ **无竞态**（两个 deadline 谁先到是**构造出来**的，不是抢出来的）且与 B 侧 `status.FromError` 的形状**逐字对应**。

#### ✅ 真触发测试与变异验证（方向 ② 的判据有牙齿）

| | 证据 |
|---|---|
| **真触发测试** | `service/favorite/src/test/java/com/rag/favorite/http/FavoriteApiHopDeadlineContractTest.java` —— **5 条**（真 gRPC 慢下游服务器，`GetUser` 睡 5s；端口用 `RealTomcatPort.pick()` 自挑 ⇒ 不与兄弟类抢静态端口 ⇒ 避开「全局 failure threshold 把后续上下文一并跳过」的老坑）：<br>① api 面（经 `invokeRpc`）⇒ **1015** ② **反向对照** gRPC 面（直调领域层）⇒ **仍 5001** ③ 非超时对照 ⇒ **仍 5001** ④ api→领域层这一跳**必须自带预算载体**（`remainingNanos()` 在且 ≤ 2000ms）⑤ 慢下游服务器**真的在监听** |
| **三处机制变异（红 → 绿）** | mut1 去掉「`DEADLINE_EXCEEDED` ⇒ 1015」⇒ **红**，命中 `apiFaceBudgetExpiryYieldsServerBusy` ✓<br>mut2 领域层丢掉 gRPC cause ⇒ **红**，命中同一条 ✓<br>mut3 不绑预算载体 ⇒ **红**，命中 `apiHopCarriesItsOwnBudgetCarrier` ✓<br>⇒ 还原后复跑 ⇒ **绿（5 例）** ✓<br>⇒ ⚠️ 每轮**从干净态开始** + 还原后 **`cmp` 逐字节校验** + **编译失败判假红**（脚本崩在 `restore` 之前 ⇒ 源码会留在变异态 ⇒ 第一版脚本因 bash 在 UTF-8 locale 下把 `$rc）` 的全角括号算进变量名而崩 ⇒ 已改用 `${rc}` + ASCII 脚本消息并从备份恢复后重做） |

---

## 六 · 本域签字偏离（**12 条 · 9 条继承 B 侧已签字决策 ＋ 3 条 C 侧专属**）

见 **`docs/Intentional_Deviations/favorite/_signed-deviations.md`**（本轮新建）。

| ID | 一句话 | 状态 |
|---|---|---|
| `DEV-FAV-API-001` | 401 响应的 body 形态（三字段 envelope） | ✅ 仍成立 |
| `DEV-FAV-API-002` | 500 兜底的 body 形态（三字段 envelope） | ✅ 仍成立 |
| **`DEV-FAV-API-003`** | **api→领域层这一跳自持出向上界（复刻 B 侧 api→rpc 那一跳的 cap）＋ 领域层保留原始 gRPC cause** | 🟡 **C 侧专属**（2026-11-05 用户拍板方向 ② 后新增 · **DRIFT-FAV-014 的处置载体** · 行为已对齐） |
| `DEV-FAV-COMMON-001` | `GRPCError(codes.OK, …)` 的返回形态 | ✅ 仍成立 |
| `DEV-FAV-COMMON-002` | `status.FromError` 的「顶层 vs cause 链」判定 | ✅ 仍成立 |
| `DEV-FAV-RPC-001` | `favorite_request_seconds_counter` 的 Prometheus 渲染名 | ✅ 仍成立 |
| `DEV-FAV-RPC-003` | preflight 的退出码 → 结构化日志（U2） | ✅ **仍成立**（2026-11-05 P6-c 拍板①后落位 `wiring/FavoriteLegacyPreflightEndpoint`） |
| `DEV-FAV-RPC-008` | preflight 的**只读快照事务**（B 侧注解在但**自调用绕过代理** ⇒ 实际无事务） | 🟡 **C 侧专属**（照抄 B 侧实际行为） |
| `DEV-FAV-RPC-009` | preflight 的**触发形态**（Actuator 端点 + HTTP 状态码替代退出码）＋ **Actuator 响应被业务信封包裹** | 🟡 **C 侧专属**（D1 合并 + P6-c ① 的必然结果） |
| `DEV-FAV-RPC-004` | 权威读端点的部署形态（U1） | ✅ 仍成立 |
| `DEV-FAV-RPC-006` | 约束与索引的名字（12 处） | ✅ 仍成立 |
| `DEV-FAV-RPC-007` | `favorite_item` 多一道 `target_revision scope` CHECK | ✅ 仍成立 |

> ⚠️ **`docs/Intentional_Deviations/_INDEX.md` 里没有 `favorite` 行** ⇒ **需主会话补**（该索引不在本域写权范围）。

---

## 七 · 证据强度汇总

### ⚠️ 三条必读

1. ⚠️ **本台账里的 ✅ 一律读作「在该证据强度下一致」** —— **不是**已闭环。
2. ⚠️ **「E-2 源码级对照」≠「E-4 双侧活体对拍」** —— 后者才是**行为层**证据。
3. ⚠️ **测试全绿本身不构成证据** —— 必须能排除「假绿」（变异实测 / `skipped = 0` 断言）。

| 级别 | 名称 | 判据 | 本域覆盖 |
|---|---|---|---|
| **E-4** | 双侧活体对拍 | B 侧与 C 侧**同时运行**，同一请求矩阵 ⇒ 逐条比对可观察输出 | ✅ **本域覆盖**（**HTTP 面 32 case × 3 方** ⇒ §八·8.4 ＋ **gRPC 面 36 case × 3 方** ⇒ §八·8.5 ⇒ 落点 `tools/difftest/favorite/`）<br>🟠 **另加一层「场景轮」**（§八·8.7 · **6 类场景** · create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / **重复请求（幂等重放）**）⇒ ⇒ **但只有 B↔C 双侧活体、无第三方锚** ⇒ ⇒ **强度低于三方**<br>✅ **verification.md §九 的 11 项「对拍必须覆盖」已逐项有证据**（§11.7） |
| **E-3** | 真中间件 / 真容器 | 真 PostgreSQL / 真 Redis / 真 Tomcat / 真 gRPC 传输 | ✅ **本域覆盖**（见 §八·8.1） |
| **E-2** | 源码级对照 | 逐行读 B 侧源码并对照 C 侧 | ✅ **全域覆盖**（§一 / §二 / §三 / §四） |
| **E-1** | 形态级对照 | 只对齐文件树 / 依赖 / 配置形状 | ✅ **全域覆盖**（映射表 **63/63** = 100%） |
| **E-0** | 未对照 | 无证据 | — |

---

## 八 · 完成情况（**核对日期 2026-11-04 · 2026-11-05 更新**）

### 8.1 ✅ 本轮已落地

| 项 | 读数 |
|---|---|
| **主代码** | **63 个文件**（B 侧 75 ⇒ **N:1 合并不是丢职责**，见 §一）· **0 空壳** |
| **DDL** | 7 个 Flyway 脚本 · **SQL 正文逐字零差异**（diff / sed 去注释 / tail-cmp 三层） |
| **映射覆盖** | `tools/check-mapping-coverage.py` ⇒ **favorite C 63/63 = 100% · B 75/75 = 100%** · EXIT=0 |
| **架构断言** | `tools/difftest/check-arch-invariants.py` ⇒ **PASS 10 · FAIL 0 · N/A 2 · PENDING 1** · EXIT=0（⚠️ 唯一 PENDING 是 **comment 域**的 A8，**不属本域**） |
| **A4** | `@GrpcClient` ↔ yml `negotiation-type: plaintext` ⇒ **PASS**（本轮补 yml 的 `grpc.client.user-rpc` / `article-rpc` 段后转绿） |
| **A13** | 44/44 个 Lombok 类均带**展开块** ⇒ PASS |
| **pom 修复** | `io.grpc:grpc-inprocess`：`test` ⇒ **`runtime`**（B 侧警告块整段搬入） |
| **yaml 修复** | 补 `spring.flyway` 段（`baseline-version: 0` + 独立账本表名）· 补 `favorite-api.user-auth.*` · 补 `grpc.client.{user-rpc,article-rpc}`（三斜杠 + `plaintext`）· 补 Redis 超时与连接池三档 · 补 Tomcat 三旋钮 |
| **yaml 修复（第二轮 · 真启动实测抓出）** | 补 **6 块** B 侧两个面都有、C 侧整块没有的配置面 ⇒ 详见 §五·5.7：`rag.protection.outbound-deadline`（**2000ms** · 最严重：原为出向零期限）· `observability.{http-timeout,rpc-timeout}`（3s / 2s）· `otel:` · `management.metrics.tags.application` · `management.endpoints.*` + `show-details` · `spring.cloud.nacos.discovery.register-enabled: false` |
| **真 fat jar 真启动** | ✅ `java -jar` 真起来：Tomcat 18894 + gRPC 19003 双监听 · Flyway **V1–V7 全部执行**（4 张表 + 独立账本 `flyway_schema_history_favorite`）· 2 个不可变触发器就位 · PG / Redis / Nacos 全 **UP** · `POST /folder/create` 出向超时**2.51s 返回 5001**（修复前挂起） |
| **回归守卫** | `config/FavoriteOutboundDeadlineContractTest`（7 条 · **含反向对照** ⇒ 证明第 1 条不是恒绿）· `http/FavoriteApiRealTomcatGateTest`（16 条 · **真 Tomcat**） |
| **测试读数（核对日期 2026-11-05）** | `mvn -o -pl service/favorite -am test` ⇒ **Tests=210 · Failures=0 · Errors=0 · Skipped=0** · **EXIT=0**（⚠️ 守门硬规则 ②：报「0 failures」**同断 `skipped=0`**） |
| **verify 生命周期读数** | `mvn -o -pl service/favorite -am verify` ⇒ **BUILD SUCCESS** · EXIT=0 · 上游 `common` **241 例**全绿 + `favorite` **215 例**全绿 · **skipped=0** |
| **稳定性复跑（3 轮）** | `FavoriteApiRealTomcatGateTest` 连跑 3 次 ⇒ **16/16 · 16/16 · 16/16 全绿 · exit=0 全 0** ⇒ ⇒ 修掉共享层断连探针引发的随机红之后，**真 Tomcat 面已稳定**（§8.2 第 11 条） |
| **测试类构成（33 个文件 = 29 个测试类 + 4 个 support 工具类）** | `contract/` 7（错误码映射 14 · 错误码 8 · 错误文案 10 · gRPC 错误往返 10 · gRPC wire 保真 5 · proto 契约 6 · proto wire 对拍 6）· `http/` 9（**真 Tomcat** 17 条 + 输入契约 33 条 + 鉴权计时 10 条 + 密钥守卫 10 条 + 保护层接线 6 条 + query 闸门 10 条 + Redis 池/超时 5 条 + **preflight Actuator 端点 5 条** + **api→领域层出向上界 5 条 · 本轮新增（方向 ②）**）· `mapper/` 7（真 PG：schema 对拍 13 · DDL 指纹 1 · 时间列 10 · SQL 语句对拍 5 + **preflight 报表 7 条 · 本轮新增** + **上下文共享守门 1 条 · 本轮新增**）· **`grpc/` 1（真传输 5 条）** · `metrics/` 1（4 条）· `config/` 1（**出向 deadline 7 条 · 含反向对照**）· 根包 1（**上下文冒烟 3 条**）· `support/` 2 个测试类（宿主口守门 5 · 真 Tomcat 端口 4）+ 4 个工具类（`NacosStubConfiguration` / `RealTomcatHttp` / `RealTomcatPort` / `RealContainerHostPort`） |
| **真 fat jar 真启动复验（修复后配置）** | ✅ 五项全绿：① 无 token ⇒ **401 + 三字段信封** `{"code":5004,"msg":"未登录或登录已失效","data":null}` ② `GET /folder/list` ⇒ **200 + `{"folders":[]}`** ③ `POST /folder/create`（下游不可达）⇒ **2.100617s** 返回 5001 信封（= 2s 出向硬上界真生效）④ `/actuator/prometheus` ⇒ `favorite_request_total{...,application="favorite-service",...}` ⑤ Nacos 清单 ⇒ **只有 `favorite-rpc` @ 19003**，`favorite-service` **0 个实例**（修复前有错端口实例） |
| **E-4 双侧活体对拍 · HTTP 面（2026-11-04）** | ✅ **`tools/difftest/favorite/`**（本仓新建）⇒ **32 case × 3 方**（Go 真源冻结字节 ↔ B 侧活体 ↔ C 侧活体）⇒ **SAME 25 · SAME_CODE 1 · SAME_BODY 2 · SAME_STATUS 4 · OBS 0** ⇒ **EXIT=0**；**变异验证**（`ORDER BY create_time DESC ⇒ ASC`）⇒ **红（精确命中 `s01_list_folders`）→ 还原 → 绿** ⇒ **判据有牙齿** ⇒ 详见 §八·8.4 |
| **E-4 双侧活体对拍 · gRPC 面（2026-11-05）** | ✅ **`tools/difftest/favorite/grpc-wire/`**（本仓新建）⇒ **36 case × 3 方**（Go 真源 gRPC 级冻结锚 ↔ B 侧 `favorite-rpc` 活体 ↔ C 侧 `favorite-service` 活体）⇒ **① 锚校验 SAME 33 · SAME_CODE 2 · OBS 1**（B 侧活体 == Go 冻结锚 ⇒ **有锚**）+ **② BC 校验 SAME 36 · SAME_CODE 0 · OBS 0**（C 侧活体 == B 侧活体 ⇒ **逐字节全等**）⇒ **EXIT=0**；**变异验证**（同一变异点 + `SKIP_HTTP=1` 只跑 gRPC 面）⇒ **红（EXIT=1，精确命中 `c11_list_folders_ok` 且红在「BC 校验」段）→ 还原 → 绿** ⇒ **判据有牙齿** ⇒ 详见 §八·8.5 |
| **守门三道（重跑）** | ① `tools/difftest/check-arch-invariants.py` ⇒ **PASS 10 · FAIL 0 · N/A 2 · PENDING 1**（唯一 PENDING 属 comment 域 A8 · 不属本域）· EXIT=0 ② `tools/check-mapping-coverage.py` ⇒ **C 列 643/643 · B 列 752/752 · B 列全局未覆盖 0** · EXIT=0 ③ `tools/difftest/check-docs-vs-tree.py` ⇒ **全部相符合** · EXIT=0<br>⚠️ **② 必须用「改过 ROOT 的副本」跑** —— 该脚本的 `ROOT` **硬编码指向主仓** `ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")` ⇒ ⇒ 在域工作树里直接跑它 ⇒ **验的是主仓 `main` 分支** ⇒ ⇒ **假绿**（实测：工作树已 63 个主代码，而直跑仍报 61/61 · EXIT=0）⇒ ⇒ ① ③ 用 `__file__` 推导 ROOT ⇒ 天然验工作树 ✓ ⇒ ⇒ 详见 `tools/README.md` 的「在**域工作树**里跑守门脚本的约定」一节 |
| **P6-c ① 落地（2026-11-05）** | ✅ **preflight 的 Actuator 自定义端点**（决策 **P6-c** 拍板 ①）⇒ 映射表 §1.6 / M3 的**唯一落点缺口闭合** ⇒ 主代码 **61 → 63**（`service/FavoriteLegacyPreflightService` ＋ `wiring/FavoriteLegacyPreflightEndpoint`）· 测试 **196 → 210**（真 PG 7 ＋ 真 Tomcat 5 ＋ 默认 404 对照 1 ＋ 上下文共享守门 1）⇒ ⇒ 详见 **§五·5.1** 与 **§八·8.6**<br>✅ **P6-c 落地后对拍复跑仍绿**（**新增端点不碰 `/favorite/v1` 的 7 条路由 ⇒ 不影响既有 wire 契约**）：HTTP 面 **32 case** SAME 25 / SAME_CODE 1 / SAME_BODY 2 / SAME_STATUS 4 / OBS 0 · gRPC 面 **36 case** ① 锚校验 SAME 33 / SAME_CODE 2 / OBS 1 ② BC 校验 SAME 36 / OBS 0 ⇒ **EXIT=0** |
| **⚠️ 本轮撞出并已修的测试设施缺陷** | 🔴 **真 PG 测试类的上下文抢同一个 gRPC 端口** ⇒ 4 个 PG 测试类共 **24 例全红**，但报错栈里**没有端口冲突的字样**（只有一句 `ApplicationContext failure threshold (1) exceeded`）⇒ ⇒ 修法 = ① properties 与兄弟类逐字相同 ② 新增 `mapper/FavoritePgTestContextSharingTest`（反射断言 properties 集合数为 1）⇒ ⇒ **变异验证**：改一行 ⇒ **红** ⇒ 还原 ⇒ **绿** ⇒ ⇒ 详见 **§五·5.9** |
| **🟡 场景轮 · 6 类场景（2026-11-05 新增）** | ✅ **`tools/difftest/favorite/scenarios/` + `stub/DownstreamStub.java` + `mutate-scenarios.sh`**（本仓新建）⇒ **6 类场景全绿**：S1 create 成功路径（两侧 biz=200 · 各落 1 行）· S2 并发（两侧 `200:1,5203:7,` · 各 1 行）· S3 超时（**耗时桶两侧相同** `2000-2499` ⇒ **出向上界同值**；✅ **业务码两侧同为 1015** ⇒ **DRIFT-FAV-014 已修**）· S4 部分成功（两侧 biz=5008 · **各 0 行** ⇒ 无半截状态 · 恢复轮各 1 行）· S5 Redis 故障（两侧 status=200 / biz=200 ⇒ **fail-open**）· **S6 重复请求（幂等重放）**（建夹 **200 → 5203** · 收藏 **200 → 5302** · **同名改名 200 → 200 且 `update_time` 未推进** ⇒ 真·幂等）⇒ ⇒ **变异验证两轮**：① 删 C 侧唯一索引 ⇒ **红（精确命中 S2 C 侧分布）· B 侧未红** → 还原 ⇒ **绿**；② 改掉 C 侧 9002 的名字 ⇒ **红（精确命中 S6 `update_time` 被推进）· B 侧未红** → 还原 ⇒ **绿** ⇒ ⇒ **完整复跑 EXIT=0**（**0 条已知差异警告** ⇒ `KNOWN_DIFFS` 已清空）⇒ ⇒ 详见 **§八·8.7**<br>🟠 **证据强度**：**只有 B↔C 双侧活体、无第三方锚**（本机无 `Sea-etcd`）⇒ ⇒ **不得混称「E-4 三方」** |

### 8.2 🟡 仍待补（**不阻塞本域交付，但不得声称已闭环**）

> 📌 **原第 1 条（E-4 双侧活体对拍）已于 2026-11-04 闭合**（HTTP 面）⇒ 已移至 **§8.4**；
> **gRPC 面已于 2026-11-05 闭合** ⇒ 移至 **§8.5**；
> **原第 2 条（preflight 触发入口）已于 2026-11-05 闭合**（P6-c 拍板①）⇒ 已移至 **§8.6**；
> **原第 3 条（Flyway 账本拍板）已于 2026-11-05 签字** ⇒ 已移至 **§5.2**。
> ⇒ **其余条目的编号保持不变**（第 11 条仍是 `ClientDisconnectProbe`）⇒ ⇒ 引用不失效。

| # | 项 | 性质 |
|---|---|---|
| 1 | ~~**E-4 双侧活体对拍**~~ | ✅ **已闭合**（2026-11-04 HTTP 面 / 2026-11-05 gRPC 面）⇒ 详见 **§8.4** 与 **§8.5**（编号保持不变，其余条目序号不动） |
| 2 | ~~**preflight 触发入口**（P6-c 未拍板）~~ | ✅ **已闭合**（2026-11-05 · P6-c 拍板 **① Actuator 自定义端点**）⇒ 详见 **§8.6** 与 **§五·5.1**（编号保持不变，其余条目序号不动） |
| 3 | ~~**Flyway 账本拍板**（D17 只定了 users 域）~~ | ✅ **已签字**（2026-11-05 · 用户「同意」）⇒ 独立账本 `flyway_schema_history_favorite` + `baseline-version: 0`（默认 1 会让 V1 从未执行、表永远建不出来）⇒ 详见 **§五·5.2** |
| 4 | **鉴权计时口径偏离**（验签计入 3s 预算） | 🟠 已登记 · §五·5.3 |
| 5 | **V6 `pg_constraint` 无 schema 限定** | 🟠 已登记 · §五·5.5 |
| 6 | **`docs/Intentional_Deviations/_INDEX.md` 缺 `favorite` 行** | 🟠 需主会话补 |
| 7 | **`docs/DRIFT/_INDEX.md` 缺 `favorite` 行** | 🟠 需主会话补（本域写权外） |
| 8 | **B 侧 `FavoriteApiMvcPipelineTest`（822 行）未移植** | 🟠 **显式留白**：它 mock 掉下游客户端 + 用**真 Redis** 验黑名单语义（`user:jwt_blacklist:<token>`）⇒ C 侧因 **D2 域内直连**（无 favorite-rpc 客户端）而需改写「下游错误 ⇒ 业务码」那一族断言 ⇒ **本轮未做** ⇒ 连带把 `RealContainerHostPort` 登记册里那个 Redis 口**撤掉**（登记册必须与源码同形 · 守门 `RealContainerHostPortTest` 的「每个登记口恰好被引用一次」）⇒ ⇒ **不是「藏起来」**：本条就是它的登记处 |
| 9 | **B 侧 `FavoriteApiFullParityTest` / `FavoriteApiJwtVerificationScopeTest` / `FavoriteApiSpanWiringTest` / `FavoriteRpcSpanWiringTest` / `FavoriteRpcProductionStartupTest` / `FavoriteRpcProductionAddressTest` / `FavoriteProtectionChainWiringTest` / `FavoriteRpcSharedLayerWebBeanGateTest` / `FavoriteRpcReflectionExposureTest` / `FavoriteRpcPrometheusCarrierGateTest` / `FavoriteOutboundDeadlineCarrierGateTest` / `FavoriteSqlTimeoutProductionPathTest` / `FavoriteFactSemanticsTest` / `FavoriteAuthority*`（4 个）/ `FavoriteFactDispatchSchedulerGracefulShutdownTest` 等未移植** | 🟠 **显式留白**：本域已有 **29 个测试类 / 215 例**覆盖 L1 契约 · L2 语义 · L4 机制 · 真 PG · 真 Tomcat；但 B 侧 **54 个**测试类**未逐个移植** ⇒ ⇒ 本域的证据强度**不等于** B 侧测试面的覆盖度 ⇒ ⇒ **不声称「B 侧测试已全部移植」** |
| 10 | 🟡 **D2 直连 ⇒ B 侧测试的 mock 挡住了一族断言**（`FavoriteApiRealTomcatGateTest` 移植时踩到） | B 侧那个测试有 {@code @MockitoBean FavoriteRpcClient} ⇒ 请求**到不了 rpc 层** ⇒ {@code folderId=0} 无人判 ⇒ 「2048 个非空值放行 ⇒ 不是 5003」成立；C 侧 **D2 域内直连** ⇒ 请求**真的进领域层** ⇒ 而两侧领域层对 {@code folderId <= 0} 的判定**逐字一致**（B `FavoriteDomainService.java:756-759` / C `:429-432` ⇒ {@code invalidParam()} = 5003）⇒ ⇒ **两侧行为无漂移**，只是 B 侧那个测试**能不能走到领域层**不同 ⇒ ⇒ C 侧把 form 取值改成**从 1 开始**（全为正数）⇒ ⇒ 被测对象（Go 的 {@code maxFormParamCount=2048} 闸门）**没变**，只是去掉了与该判据无关的干扰项 |
| 12 | 🟠 **跨域残留**：2 个对拍探针仍用 `forAddress(host, port)`（`tools/difftest/follow/grpc-wire/FollowWireProbe.java:196` · `tools/difftest/users-user/grpc-wire/UserWireProbe.java:666`） | **当前安全**（独立进程探针 · **不跑 Spring 上下文** ⇒ 全局默认 scheme 仍是 `dns`）⇒ ⇒ **潜在**风险：若将来让探针跑在 Spring 上下文里（或与其它用例同 JVM）⇒ ⇒ 会踩「全局 name resolver 被 devh 接管」⇒ ⇒ **跨域 · 本域写权外** ⇒ 登记给 `global` 收口域 / 主会话（机制与修法 ⇒ §五·5.8） |
| 11 | 🟠 **共享层 `ClientDisconnectProbe` 的裸写 1xx 会落进 chunked body 中间 ⇒ 真 Tomcat 用例随机红**（2026-11-04 `verify` 实测） | 共享层 `common/.../observability/ClientDisconnectProbe.java:137` 裸写 `HTTP/1.1 103 Early Hints\r\n\r\n`，**时机不定** ⇒ 可能落在响应头之后、chunked body **中间** ⇒ JDK `HttpClient` 的 chunk 解码器见垃圾 ⇒ 抛 `IOException: Illegal character in chunk size: 72`（72 = `'H'`）⇒ ⇒ **同一份代码上一轮绿、这一轮红**<br>**本域只登记**（共享层写权在收口任务）⇒ **本域侧处置**：真 Tomcat 用例在 `@BeforeAll` 把 `rag.common.http.disconnect-probe-max-lines` 设为 **0**（探针 fail-open 关闭）**并在 `@AfterAll` 还原** ⇒ ⚠️ **不削弱任何断言**（1xx 与本域判据无关）⇒ 详见 `vs-common.md` §5 第 3 条 |
| **13** | ✅ **DRIFT-FAV-014 · 慢下游时 api 面业务码 B=1015 / C=5001**（2026-11-05 场景轮实测抓出） | ✅ **已修**（2026-11-05 用户拍板**方向 ②**）：**C 侧 api 面自持同值 2s 上界、超时落 1015**（**复刻 B 的 api→rpc 那一跳** ⇒ **与 hot 域修法同款**）⇒ ⇒ **api 面两侧已同为 1015**（gRPC 面**不受影响** · 仍 5001 · 与 B 侧 gRPC 面同形）⇒ ⇒ **真触发测试 5 条 + 三处机制变异红→绿 + 场景轮 S3 双侧同为 1015** ⇒ ⇒ 载体差异登记为 **`DEV-FAV-API-003`** ⇒ ⇒ 详见 **§五·5.10** |
| **14** | ✅ ~~**HTTP 面的重复请求（幂等重放）未覆盖**~~ | ✅ **已补（2026-11-05）** ⇒ **场景轮 S6** 覆盖**三个子场景**（建夹重放 ⇒ 5203 · 收藏重放 ⇒ 5302 · **同名改名 ⇒ 200 且 `update_time` 不动** ⇒ 真·幂等）⇒ ⇒ 详见 **§八·8.7.3** |

### 8.4 ✅ **E-4 双侧活体对拍**（2026-11-04 闭合）

> **落点**：`tools/difftest/favorite/`（本仓新建 · 本域私有目录）
> —— `run.sh` · `probe/FavoriteApiParityProbe.java` · `compare.py` · `mint-jwt.py` ·
> `expected.txt`（B 侧冻结锚的逐字节副本 + sha256 溯源核对）· `seed.sql` · `mutate-parity.sh` · `README.md`

#### 三方结构

```
        ┌──────── 第三方锚 ─────────┐   ┌─── B 侧活体 ───┐   ┌─── C 侧活体 ───┐
        │ expected.txt = Go 真源     │   │ favorite-api   │   │ favorite-      │
        │ favorite-api 的活体渲染字节 │   │ + favorite-rpc │   │ service（D1    │
        │ （B 侧仓 2026-10-30 录）    │   │ Boot 4.0.8     │   │ 合并进程）     │
        └────────────────────────────┘   │ 真 Flyway+PG   │   │ Boot 3.5.11    │
                                         └────────────────┘   └────────────────┘
```

| 项 | 读数 |
|---|---|
| **矩阵** | **32 case × 8 组**（鉴权 6 · 路由 3 · 解析 7 · 业务 7 · 只读成功 4 · 响应头面 1 · 写入 3 · 写入生效验证 1） |
| **判定** | **SAME 25 · SAME_CODE 1 · SAME_BODY 2 · SAME_STATUS 4 · OBS 0 · SKIP 0** ⇒ **三方一致 · EXIT=0** |
| **基线** | ✅ **B 侧活体与 Go 冻结字节全等**（0 条基线漂移）⇒ ⇒ 「C == B」这条结论**有锚**（否则「两边一起错」也会绿） |
| **变异验证** | ✅ C 侧 `ORDER BY create_time DESC ⇒ ASC` ⇒ **红（EXIT=1，精确命中 `s01_list_folders`）** → 还原 ⇒ **绿（EXIT=0）** ⇒ **判据有牙齿** ⇒ 净改动 = 0 |
| **冻结锚溯源** | ✅ 每次运行前核对 **B 侧原件** sha256（`422ac805…f97`）⇒ 副本漂了 ⇒ **中止** ⇒ **不会拿陈旧冻结值判「三方一致」** |
| **两侧形态** | B 侧 = **两个** `java -jar`（api/rpc 分离）· C 侧 = **一个** `java -jar`（D1 合并进程）⇒ ⇒ **D1 的结构性差异在 HTTP 响应字节上不可见**（业务码两侧都是 5001/5201/5301/5005/5304…） |

#### 验到了什么（**这就是 E-4 的增量**）

- ✅ **7 个 HTTP 端点的完整响应体逐字节**（含 `int64 以字符串出参` + `createdAt`/`updatedAt` 毫秒时间戳）
- ✅ **业务码经 gRPC 往返后仍可反查**（favorite 域的业务码**不在 gRPC status 里**，只在 `description` 文案里 ⇒ 只有打通 HTTP→gRPC→回程才验得到）
- ✅ **JWT 中间件的 6 种失败形态**（无头 / 签名错 / 过期 / 只有前缀 / **小写 bearer 放行** / **被拉黑**）
- ✅ **黑名单 key 的拼法**（`user:jwt_blacklist:<token>` ⇒ 两侧**共用同一个 Redis** ⇒ 同一枚 token 两侧必然查到同一条）
- ✅ **解析层的 7 档**（含「**有 body 但没有 JSON Content-Type** ⇒ 走 form 通道」这条语义）
- ✅ **路由层**（未知路径 404 / method 不匹配 405 ⇒ **404/405 不经鉴权** ⇒ 两侧都 404/405 而不是 401）
- ✅ **完整响应头 key 集合**（h01 ⇒ Go / B / C 三方都是 `content-length,content-type,date,traceparent`）
- ✅ **写入副作用**（改名 / 删条目 / 删收藏夹 + **写完再读一次** ⇒ 副作用真的一致）

#### 🔴 本轮**没有**覆盖的（**证据边界** · 必须连着结论一起引用）

| 项 | 状态 | 说明 |
|---|---|---|
| **gRPC 面（rpc 对 rpc）** | ✅ **已闭合（2026-11-05）** | 原为「🔴 未验」⇒ 已由 **§8.5** 补齐（36 case × 3 方 · 独立冻结锚 · 独立变异验证）⇒ ⇒ 本表其余各条**仍是本面（HTTP）的边界** |
| **create 成功路径** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮 S1** 补齐（下游在场 ⇒ 两侧 biz=200 · 各落 1 行 · 体逐字节相同）⇒ ⇒ 本表其余各条**仍是本面（HTTP 单请求契约面）的边界** |
| **真源 user-rpc / article-rpc 在场** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮**补齐（下游桩在场 ⇒ S1/S2/S4 都跑在「下游在场」路径上） |
| **Redis 故障 / 变慢** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮 S5** 补齐（停 Redis ⇒ 两侧 status=200 / biz=200 ⇒ **fail-open**） |
| **并发 / 超时 / 部分成功 / 重复请求** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮 S2/S3/S4/S6** 补齐（🟠 但**只有双侧、无第三方锚** ⇒ 见 §8.7.4） |
| **Go 侧此刻的行为** | ❌ **不证** | 冻结锚只证明「与**当时**的 Go 一致」；Go 之后若变了，探针**看不见**（B 侧原件 sha256 变了会被拦住 ⇒ 那是**换锚**不是**换行为**） |
| **`Date` / `Content-Length` 响应头** | ⚪ **不比** | `Date` 每次都变 ⇒ 比它必然假红；`Content-Length` 由 body 长度决定（body 已逐字节比）⇒ 冗余 |

⇒ ⇒ **HTTP 面的证据强度自此由 E-3 升到 E-4**
⇒ ⇒ §二 / §三 的 ✅ 在 **HTTP 面**上**已到 E-4**；**gRPC 面**见 **§8.5**；**6 类场景**见 **§8.7**（🟠 那里**只有双侧**）。

---

### 8.5 ✅ **E-4 双侧活体对拍 · gRPC 面（rpc 对 rpc）**（2026-11-05 闭合）

> **落点**：`tools/difftest/favorite/grpc-wire/`（本仓新建 · 本域私有目录）
> —— `FavoriteWireProbe.java` · `compare-grpc.py` · `expected.txt`（B 侧 gRPC 冻结锚的逐字节副本）·
> `kinds-bc.txt`（「Java ↔ Java」档位表）· `check-anchor-copy.py`（副本一致性核对）
> —— 编排接在 `tools/difftest/favorite/run.sh` 的 **step 11**（HTTP 面之后）·
> 变异脚本 `tools/difftest/favorite/mutate-grpc-parity.sh`

#### 🔴 为什么必须**单独一轮**（不能靠 HTTP 面推）

HTTP 面的 JSON 字节一致 **推不出** gRPC wire 字节一致 ——
proto 编码 / 字段号 / status description / metadata key 全是**另一套面**
⇒ ⇒ gRPC 面有自己的 Go 冻结锚、自己的探针、自己的变异验证。

#### 三方结构

```
     ┌── 第三方锚（Go 真源）──────┐   ┌── B 侧活体 ──────┐   ┌── C 侧活体 ──────┐
     │ grpc-wire/expected.txt     │   │ favorite-rpc     │   │ favorite-service │
     │ = Go 真源 favorite-rpc 的   │   │ gRPC : 2xxxx     │   │ gRPC : 2xxxx     │
     │ **gRPC 级可观测契约渲染值**  │   │ Boot 4.0.8       │   │ Boot 3.5.11      │
     │ （B 侧仓 2026-10-25 录）     │   │ 真 Flyway + PG   │   │ 真 Flyway + PG   │
     │ 36 case · SAME 33 /         │   └──────────────────┘   └──────────────────┘
     │ SAME_CODE 2 / OBS 1         │
     └──────────────────────────────┘
```

| 项 | 读数 |
|---|---|
| **矩阵** | **36 case × 5 组**（入参与下游 9 · 只读成功 9 · 删除改名 11 · 写入组 4 · 服务面 3） |
| **① 锚校验（Go 冻结锚 ↔ B 侧活体）** | **SAME 33 · SAME_CODE 2 · OBS 1 · SKIP 0** ⇒ **B 侧活体 == Go 冻结锚**（0 条基线漂移）⇒ ⇒ 「C == B」这条结论**有锚**（否则「两边一起错」也会绿） |
| **② BC 校验（B 侧活体 ↔ C 侧活体）** | **SAME 36 · SAME_CODE 0 · OBS 0 · SKIP 0** ⇒ **C 侧活体 == B 侧活体，逐字节全等** ⇒ **EXIT=0** |
| **变异验证** | ✅ C 侧 `ORDER BY create_time DESC ⇒ ASC` + **`SKIP_HTTP=1`（只跑 gRPC 面）** ⇒ **红（EXIT=1，精确命中 `c11_list_folders_ok`，且红在「BC 校验」段）** → 还原 ⇒ **绿（EXIT=0）** ⇒ **判据有牙齿** ⇒ 净改动 = 0 |
| **冻结锚溯源** | ✅ **两道**核对：① B 侧原件 sha256（`cf6c25be…518a`）② **本仓副本的数据行与原件逐行相同**（`check-anchor-copy.py`）⇒ ⇒ **改了副本里的冻结值**也会被抓住（否则 = 假绿） |
| **探针依赖** | **classpath 取自 C 侧 fat jar 的 `BOOT-INF/lib`**（`grpc-api 1.63.0` 等）⇒ ⇒ 「探针量到的是哪一版依赖」**不是问题** |
| **两侧形态** | B 侧 = `favorite-rpc` **一个** `java -jar`（Boot 4.0.8）· C 侧 = `favorite-service` **一个** `java -jar`（Boot 3.5.11 · **D1 合并**）⇒ ⇒ **D1 的结构性差异在 gRPC 响应字节上不可见** |

#### 🔴 为什么判据分**两段**（不能一张 kind 表判三方）

同一个 kind 在两段里**含义不同** ⇒ 混用会**判反**：

| 段 | 判什么 | 档位表 | OBS 的含义 |
|---|---|---|---|
| ① **锚校验** | Go 冻结锚 ↔ **B 侧活体** | `grpc-wire/expected.txt` 自带 kind 列（**Go ↔ Java**） | 「Go ≠ Java」= **已登记的库级差异**（变成一样反而是回归） |
| ② **BC 校验** | **B 侧活体 ↔ C 侧活体** | `grpc-wire/kinds-bc.txt`（**本仓自撰** · **Java ↔ Java**） | 「B ≠ C」= **已登记的 C 侧偏离** |

🔴 **实测锚点（这条正是两段设计的价值所在）**：
`c30_response_header_surface`（完整响应头面）在锚里是 **OBS** ⇒

| | 完整响应头 key 集合 |
|---|---|
| **Go 真源**（锚） | `content-type=application/grpc` |
| **B 侧活体**（Java） | `content-type=application/grpc,grpc-accept-encoding=gzip,grpc-encoding=identity` |
| **C 侧活体**（Java） | `content-type=application/grpc,grpc-accept-encoding=gzip,grpc-encoding=identity` |

⇒ ⇒ 「Go ≠ Java」成立（① OBS 段 ✅）+「Java == Java」成立（② SAME 段 ✅）
⇒ ⇒ **若拿锚的 OBS 去判 B↔C，就会把「两侧相同」判成「回归」⇒ 判反**。

#### 验到了什么（**这就是 gRPC 面的 E-4 增量**）

- ✅ **7 个 RPC 方法的响应 protobuf 字节逐字节**（含 `FavoriteFolder` / `FavoriteItem` 的**列表顺序**与**时间毫秒**）
- ✅ **status code + description 全等** ⇒ **业务码载体**（favorite 域的业务码**不在 status 里**，只在 description 文案里 ⇒ 5003 / 5201 / 5202 / 5301 / 5303 / 5304 / 5005 / 5001 逐条验到）
- ✅ **错误响应的 wire 形态**（trailers-only ⇒ `rhdr=-` + `rtrl=content-type=application/grpc`）⇒ **两侧同形**
- ✅ **服务面**：`grpc.health.v1.Health/Check` ⇒ 两侧都注册了 health 服务 ⇒ **SERVING**（`hex:0801`）；未知方法 / 未知服务 ⇒ 两侧措辞**逐字相同**（`Method not found: …` ⇒ grpc-java **不区分**「方法 vs 服务」⇒ 与 Go 的两种措辞不同 ⇒ 锚里的 SAME_CODE 段）
- ✅ **7 个方法 × 4 类错误出口**（入参 / 下游缺席 / 不存在 / 归属不符）+ 3 条写路径（删夹 / 删收藏 / 改名）

#### 🔴 本轮**没有**覆盖的（**证据边界** · 必须连着结论一起引用）

| 项 | 状态 | 说明 |
|---|---|---|
| **create 成功路径** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮 S1** 补齐（下游在场 ⇒ 两侧 biz=200 · 各落 1 行）⇒ ⇒ 本表其余各条**仍是本面（gRPC 单请求契约面）的边界** |
| **真源 user-rpc / article-rpc 在场** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮**补齐 |
| **gRPC 帧层原始字节**（HPACK / 帧序 / WINDOW_UPDATE / PING） | 🟡 **有意不比** | 由 **gRPC 传输库**决定（grpc-go vs grpc-java 必然不同）且**客户端不可观测** ⇒ 比它没有意义 ⇒ 本面比的是「帧层之上、消息层之下」的**客户端可观测**那一层 |
| **并发 / 超时 / 部分成功 / 重复请求** | ✅ **已补（2026-11-05）** | 原为「🔴 未验」⇒ ⇒ 已由 **§8.7 场景轮 S2/S3/S4/S6** 补齐（🟠 **只有双侧、无第三方锚** ⇒ 见 §8.7.4）<br>✅ **S3 的业务码两侧已相等**（**同为 1015**）⇒ ⇒ **DRIFT-FAV-014 已修**（§五·5.10 · 方向 ②）⇒ ⇒ **gRPC 面仍为 5001**（B 侧 gRPC 面亦 5001 ⇒ 两侧同形） |
| **Go 侧此刻的行为** | ❌ **不证** | 冻结锚只证明「与**当时**的 Go 一致」；Go 之后若变了，探针**看不见**（B 侧原件 sha256 变了会被拦住 ⇒ 那是**换锚**不是**换行为**） |

⇒ ⇒ **gRPC 面的证据强度自此由 E-3 升到 E-4**
⇒ ⇒ §二 / §三 的 ✅ 在 **gRPC 面**上也**已到 E-4**（范围见上表）。


---

### 8.6 ✅ **P6-c ① · preflight 的 Actuator 自定义端点**（2026-11-05 闭合）

> **落点**：`service/favorite/src/main/java/com/rag/favorite/service/FavoriteLegacyPreflightService.java`（**报表计算** · 224 行）
> ＋ `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteLegacyPreflightEndpoint.java`（**触发入口** · 158 行）
> ⇒ 映射表 §1.6 / M3 的缺口**闭合** ⇒ 本域**已无落点缺口**。

#### 8.6.1 决策与落点

| 项 | 内容 |
|---|---|
| **决策** | **P6-c 拍板 = ① Actuator 自定义端点**（2026-11-05 · 用户定）⇒ 备选 ② CLI profile / ③ 独立 jar **不采用** |
| **基准** | Go `Sea-RideTheWind_golang/service/favorite/rpc/internal/model/favorite_legacy_preflight.go:30-96`（判定）+ `:22-25`（`Clear()` 四条判据）+ `:35`（RR 只读事务）· B 侧 `service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/FavoriteLegacyPreflightRunner.java:82-231`（报表）+ `:107`（`@EventListener(ApplicationReadyEvent.class)`） |
| **为什么拆成 2 个文件** | B 侧 1 个类同时管「**算报表**」与「**什么时候跑**」⇒ C 侧把「算」放 `service/`（业务语义层 · 可被单测直接调）、把「什么时候跑」放 `wiring/`（装配层 · 挂 Actuator）⇒ ⇒ **报表算法可被真 PG 测试直接覆盖**（B 侧**没有任何 preflight 测试**） |
| **URL** | `GET /actuator/favorite-legacy-preflight`（Actuator 默认 base path `/actuator`） |
| **开关** | `favorite.fact.legacy-preflight.enabled`（**默认 false** · 与 B 侧 yml 的字面量一致）+ `management.endpoint.favorite-legacy-preflight.enabled`（**默认 false**）⇒ ⇒ **两个开关都关 ⇒ bean 不注册 ⇒ 404** |
| **为什么不收窄 advice 作用域** | 收窄能让 Actuator 变裸响应（更像 Go 的 stdout 形状）⇒ ⇒ 但会让 **C ≠ B（api 面）**（B 侧 `GlobalResponseAdvice` 同样全局）⇒ ⇒ 与 D16 冲突 ⇒ ⇒ **不收窄**，登记 `DEV-FAV-RPC-009` + 守护断言 |

#### 8.6.2 替代信号（Go 的退出码在 Spring 进程内无对应物）

| Go / B 侧 | C 侧替代 |
|---|---|
| `exit 0` = clear | **HTTP 200** |
| `exit 2` = 未裁决 | **HTTP 409** |
| `exit 1` = 读取失败 | **HTTP 500** |
| —— | ＋ **结构化日志**（`event=favorite.legacy_preflight` ＋ `outcome=succeeded\|not_reconciled\|failed` ＋ `error_code=LEGACY_RECONCILIATION_REQUIRED\|PREFLIGHT_READ_FAILED` ＋ 5 个报表字段 · 进程身份 `rtw-favorite-legacy-preflight` / 组件 `legacy-preflight`）⇒ **双通道** ⇒ 日志侧与 B 侧同形 |

#### 8.6.3 证据（真触发 · 非 mock）

| 测试类 | 例数 | 验什么 |
|---|---|---|
| `mapper/FavoriteLegacyPreflightParityTest`（**真 PG**） | **7** | ① 全有断言 ⇒ `clear=true` ② 无 marker ⇒ `blocked` ③ marker 全对 ⇒ `approved_legacy` ④ **哈希错 ⇒ blocked**（证明哈希真被比对）⑤ **user_id 不符 ⇒ blocked** ⑥ **revision 有无不符 ⇒ blocked** ⑦ **孤儿撤回计数** |
| `http/FavoriteLegacyPreflightEndpointTest`（**真 Tomcat** ＋ mock 服务层） | **5** | ① 200 + Go 形报表（**信封内层逐字断言**）② 409 ③ 未知端点 404（对照）④ **健康检查不触发预检**（`mock never called`）⑤ **Actuator JSON 被业务信封包裹**（守护偏离登记） |
| `http/FavoriteApiRealTomcatGateTest`（**真 Tomcat**） | **+1** | **默认配置下** `/actuator/favorite-legacy-preflight` ⇒ **404**（证明「默认不暴露」） |

> ⚠️ **哈希那条的取证纪律**：`FavoriteFactEnvelopeFactory#legacySnapshotHash` 用**生产组件**算「正确哈希」，另用一条**错哈希**的用例证明它**真的被比对** ⇒ ⇒ 单靠「哈希正确 ⇒ approved」是**恒真**的（算错也能自洽）⇒ ⇒ 必须配「错哈希 ⇒ blocked」这条**反向对照**。
> ⚠️ **事务**：C 侧**刻意不加** `@Transactional`（B 侧的自调用绕过代理 ⇒ 实际无事务）⇒ 登记 `DEV-FAV-RPC-008` ⇒ **不得**「顺手补上」。

#### 8.6.4 撞出的测试基础设施缺陷（**已修 + 已加守门**）

⇒ 详见 **§五·5.9**（真 PG 测试类的上下文抢同一个 gRPC 端口 ⇒ 24 例全红但栈里没有端口冲突）⇒ 修法 = ① properties 与兄弟类逐字相同 ② 新增 `mapper/FavoritePgTestContextSharingTest`（反射断言 properties 集合数为 1）⇒ ⇒ **变异验证**：改一行 ⇒ **红** ⇒ 还原 ⇒ **绿**。

---

### 8.7 🟡 **场景轮 · 6 类场景（B↔C 双侧活体 · 无第三方锚）**（2026-11-05 新增）

> **落点**：`tools/difftest/favorite/stub/DownstreamStub.java`（**下游桩** · 一个进程同时供 user-rpc + article-rpc）
> ＋ `tools/difftest/favorite/scenarios/FavoriteScenarioProbe.java`（**场景动作探针** · JDK-only）
> ＋ `tools/difftest/favorite/scenarios/scenarios.py`（**编排 + 判据 + `KNOWN_DIFFS`**）
> ＋ `tools/difftest/favorite/mutate-scenarios.sh`（**变异验证**）
> ＋ `tools/difftest/favorite/run.sh` 的 **step 12（§12 场景轮阶段）**
> —— 编排接在 HTTP 面 + gRPC 面**之后**（**须换下游地址重启两侧** ⇒ 抽成 `start_sides` / `stop_sides`）

#### 8.7.1 🔴 **为什么要有这一层**（矩阵**结构性**覆盖不到）

| 场景 | 32 case 矩阵为什么覆盖不到 |
|---|---|
| **create 成功路径** | 矩阵里 user-rpc **故意缺席** ⇒ `ensureUserExists` 必失败 ⇒ 建夹落「下游不可用」5001 ⇒ **成功建夹没被测到** |
| **并发** | 矩阵是**串行**的 ⇒ 碰不到「先查重后插入」的竞态 |
| **超时** | 下游缺席是**立即失败** ⇒ 压根不触发**出向预算** ⇒ 测不出「两侧预算同不同」 |
| **部分成功** | 矩阵没有「多步写在中途失败」 |
| **Redis 故障** | 矩阵里 Redis 一直在 ⇒ **fail-open 那条分支**没测过 |
| **重复请求（幂等重放）** | 矩阵是**单请求**的 ⇒ ⇒ 同一个请求发两次会得到什么，**测不到**（verification.md §九 明列「幂等和重复请求」且「对拍必须覆盖：重复请求」） |

⇒ ⇒ 场景轮**必须另起一轮**（两侧**重启**并改指下游桩）⇒ ⇒ 且**下游必须在场** ⇒ ⇒ 与矩阵的「下游缺席」**不能共存**。

#### 8.7.2 🔴 **判据 = 绝对断言 + 两侧对称（两条都要过）**

> **只比两侧相等是假绿**：两侧**都坏**也算相等 ⇒ ⇒ 每个场景都另给一条**绝对断言**（期望的行为是什么）⇒ ⇒ 两侧对称 **AND** 绝对断言 ⇒ 才算过。

> 🔴 **`KNOWN_DIFFS` 是「指纹」不是「豁免」**（`tools/difftest/favorite/scenarios/scenarios.py`）：
> 只有 **(stage, 字段)** 与**两侧具体值都逐字相符**才走 **WARN**；**值一变立刻 FAILED** ⇒ ⇒ 它**也有牙齿**。
> ⇒ ✅ **修完 DRIFT-FAV-014 后该表已清空**（**表结构保留** ⇒ 注释写明「表是已知差异的指纹，不是豁免」）⇒ ⇒ **现在任何两侧不等都真报红**。

#### 8.7.3 六个场景的**实测读数**（2026-11-05）

| 场景 | 故障注入 | 绝对断言（两侧各查） | 两侧对称断言 | 读数 |
|---|---|---|---|---|
| **S1 · create 成功路径** | 桩 `mode=normal`（下游在场） | biz=**200** · 响应里有**正的 folderId** · **库里真落 1 行** | 响应体（id 归一化后）**逐字节相同** | ✅ **两侧 biz=200 · 各落 1 行 · 体逐字节相同** |
| **S2 · 并发** | 8 个**同名**建夹**同时**发起 | 分布 = **`200:1,5203:7,`** · 成功体 **1** 种 · 失败体 **1** 种 · 库里**恰好 1 行**（无重名） | 两侧**分布相同** | ✅ **两侧 `200:1,5203:7,` · 各 1 行** |
| **S3 · 超时** | 桩 `mode=slow, slow_ms=5000` | biz **≠ 200** · 耗时桶 **≥ 1000ms**（确实等到了预算，不是立即失败） | 两侧**耗时桶相同** ⇒ **出向上界同值** · 两侧**业务码相同** ⇒ **行为等价** | ✅ **两侧 biz=1015**（`CODE_SERVER_BUSY` · 文案「服务繁忙」逐字一致）· **耗时桶两侧相同** `2000-2499` ⇒ **上界同值** ⇒ ⇒ 🟠→🟢 **DRIFT-FAV-014 已修**（方向 ② · §五·5.10）⇒ ⇒ **`KNOWN_DIFFS` 已清空**（现在任何两侧不等都真报红） |
| **S4 · 部分成功** | 在 `favorite_fact_outbox` 挂 **BEFORE INSERT 触发器**（两侧各挂）⇒ `POST /item/create` 的**第 2 步**被打断 | 第 1 步写的 item **必须回滚** ⇒ `favorite_item` 行数 **= 0** | 两侧**业务码相同** | ✅ **两侧 biz=5008 · 各 0 行**（**没有半截状态**）· 撤触发器后**恢复轮**两侧 biz=200 且**恰好 1 行** |
| **S5 · Redis 故障** | `docker stop` 探针**自带**的 Redis（两侧**共用** ⇒ 天然对称） | —— | 两侧 **status** 与 **biz** 都相同 | ✅ **两侧 status=200 / biz=200** ⇒ ⇒ **fail-open**（Redis 挂了仍放行）· 恢复轮两侧 biz=200 |
| **S6 · 重复请求（幂等重放）** | **同一个请求连发两次**（三个子场景各一次） | ① 建夹重放：biz `200 → 5203`（重名预查命中）· 库里 `ReplayFolder` **恰好 1 行**<br>② 收藏重放：biz `200 → 5302`（重复收藏判定命中）· 库里 `item(90002)` **恰好 1 行**<br>③ **改名幂等**：biz `200 → 200`（同名 ⇒ 直接成功）· **`update_time` 未推进**（⇒ 真的没写库） | 两侧**六个业务码全部相同** + **改名响应体逐字节相同** | ✅ **两侧三个子场景全同**（200→5203 / 200→5302 / 200→200）· 两侧各**恰好 1 行** · 两侧 `update_time` 均**未推进**（`2026-01-01 19:04:06.789000`） |

> 🔴 **S6 为什么是三个子场景，不是一个**：favorite 域真实存在**三种不同的重复语义** ——
> ① **重复建夹** ⇒ 第二次撞**重名预查** ⇒ 5203（B `create_favorite_folder_logic.go:75-87`）
> ② **重复收藏** ⇒ 第二次撞**重复收藏判定** ⇒ 5302（B `create_favorite_logic.go:122-134`）
> ③ **同名改名** ⇒ **直接成功、且不写库** ⇒ 200 且 `updated_at` **不动**（B `update_favorite_folder_name_logic.go:91-95`）
> ⇒ ⇒ 前两个是「重复 ⇒ **被拒**且不产生第二条副作用」；**第三个才是真·幂等**（重复 ⇒ 返回同样结果**且无任何副作用**，连 `updated_at` 都不推进）⇒ ⇒ 只测一个都答不出「重复请求」这一行。
> 🔴 **S6 的 `update_time` 断言是全场最容易被写成恒真的那条** ⇒ ⇒ 所以**专门为它配了一轮变异**（见 §8.7.5 的②）。

> 🔴 **S4 为什么选「写路径」而不是「删路径」**：级联删除要先过 `FavoriteAssertionSchemaResolver`（legacy 分支要 marker 行）⇒ ⇒ 种子里那两条 item 没有 marker ⇒ ⇒ 会在**任何写入之前**就以 5304 失败 ⇒ ⇒ **断言会空转**（「没留下半截状态」恒真）⇒ ⇒ 改用 `POST /item/create`（写 item + 写 outbox **同一事务**）⇒ ⇒ 真正打到「第 2 步失败」。
> 🔴 **S5 为什么停一个 Redis 就对称**：两侧**共用**探针自带的 Redis 容器（**不是**共享栈的 `Sea-redis`）⇒ ⇒ 停它**就是**同时停两侧。

#### 8.7.4 🟠 **证据强度：只有双侧，没有第三方锚**

> ⚠️ **本轮没有 Go 第三方锚**（本机无 `Sea-etcd` ⇒ Go 真源跑不起来）⇒ ⇒ 这 6 类场景**只有 B↔C 双侧活体**
> ⇒ ⇒ 强度**低于** 32 case 的「三方」⇒ ⇒ **不得**与 §8.4 / §8.5 混称「E-4 三方」⇒ ⇒ **台账与 README 都如实标注**。

#### 8.7.5 ✅ **变异验证（判据有牙齿）** —— `mutate-scenarios.sh`（**两轮**）

| 轮 | 变异（**只动 C 侧**） | 期望 | 实测 |
|---|---|---|---|
| **① S2 并发** | `DROP INDEX IF EXISTS uk_user_folder_name` | **红**（EXIT=1）且**精确命中** `S2 C 侧并发结果分布` | ✅ EXIT=1 + 命中该条 |
| ① 额外确认 | —（**只断 B 侧**） | **B 侧断言不得红**（⇒ 证明判据真的在**比两侧**，不是「抓住两侧都坏」） | ✅ B 侧未红 |
| ① 不带变异再跑 | —（**原样重跑**） | **绿**（EXIT=0）**且必须看到** `S2 侧并发结果分布相同` | ✅ EXIT=0 + 看到该标识 |
| **② S6 幂等** | `UPDATE favorite_folder SET name='BetaMutated' WHERE folder_id=9002` | **红**（EXIT=1）且**精确命中** `S6 C 侧 folder 9002 的 update_time 被推进了` | ✅ EXIT=1 + 命中该条 |
| ② 额外确认 | —（**只断 B 侧**） | **B 侧断言不得红** | ✅ B 侧未红 |
| ② 不带变异再跑 | —（**原样重跑**） | **绿**（EXIT=0）**且必须看到** `C 侧 folder 9002 的 update_time 未推进` | ✅ EXIT=0 + 命中该条 |
| 退出前 | —（**收尾核对**） | **净改动 = 0** | ✅（**变异是 DB 级的** ⇒ 容器每次现建 ⇒ 无需「还原」） |

> 🔴 **为什么只动一侧**：若两侧同时坏 ⇒ 场景轮只能证明「两侧相等」⇒ 证不了「它在比两侧」⇒ ⇒ **只动 C 侧** ⇒ 判据必须靠「两侧不等」抓出来。
> 🔴 **为什么必须 `ONLY_SCENARIOS=1`**：变异会让矩阵**先红** ⇒ 抢在前面 `exit` ⇒ ⇒ 红的就不是被验的那一面 ⇒ ⇒ 证据不可信。
> 🔴 **为什么「不带变异再跑」还要额外 grep 一个绿色标识**：只断 `EXIT=0` ⇒ 可能「判据根本没跑到」也是 0 ⇒ ⇒ **假绿** ⇒ ⇒ 必须**看到那条断言的绿色输出**才算数。
> 🔴 **② 这轮为什么值得单独配**：它是**唯一**「业务码不变、只有副作用变」的变异 ⇒ ⇒ **六个业务码断言照旧全绿** ⇒ ⇒ 只有「`update_time` 不动」那条能抓住它 ⇒ ⇒ **正好证明那条断言不是恒真**（它在测「有没有写库」，不是「码对不对」）。

#### 8.7.6 **完整复跑读数**（2026-11-05）

```
HTTP 面（32 case × 3 方）：SAME 25 / SAME_CODE 1 / SAME_BODY 2 / SAME_STATUS 4 / OBS 0
gRPC 面（36 case × 3 方）：① 锚校验 SAME 33 / SAME_CODE 2 / OBS 1 ② BC 校验 SAME 36
场景轮（6 类场景 · 双侧）：6/6 全绿 · 绝对断言全过 · ✅ 0 条已知差异警告（S3 biz 两侧已同为 1015 ⇒ KNOWN_DIFFS 已清空）
⇒ FULL_RUN_EXIT=0
```

> ✅ **修复 DRIFT-FAV-014 后（2026-11-05 · 方向 ②）重跑**：HTTP 面 **SAME 25 / SAME_CODE 1 / SAME_BODY 2 / SAME_STATUS 4 / OBS 0** · gRPC 面 **① 锚校验 SAME 33 / SAME_CODE 2 / OBS 1 ② BC 校验 SAME 36** · 场景轮 **6/6 全绿 · 0 条已知差异警告** ⇒ ⇒ **FULL_RUN_EXIT=0**。

---

### 8.3 🔴 本轮**不**声称已对齐的项（**明确留白**）

- **B 侧 `docs/DRIFT/favorite/` 的 7 条未决项**（U1 authority wire 形态 · U2 preflight 退出码 · U3 `Mode` 取值域 · U4 authority lookup 的 3s 超时 · U5 `ObserveRPC` 的 `result` 标签 · U6 `favorite_request_seconds_counter` 指标名 · U7 双侧活体对拍）—— 映射表 §7.4 明确「**本表不为它们下任何「已对齐 / 未对齐」结论**」。
  ⇒ 本台账亦然：U1 / U6 已由 §六 的签字偏离条目**覆盖**（C ≡ B）；**U3 / U4 / U5 仍未决**；
  **U2（preflight 退出码语义）✅ 已于 2026-11-05 消解**（P6-c 拍板① ⇒ 改用 **HTTP 状态码 + 结构化日志** ⇒ 登记 `DEV-FAV-RPC-003` 转正 ＋ `DEV-FAV-RPC-009` 新增）；
  **U7（双侧活体对拍）✅ 已于 2026-11-04 闭合**（§八·8.4 · HTTP 面 32 case × 3 方）
  **并于 2026-11-05 补齐 gRPC 面**（§八·8.5 · 36 case × 3 方）⇒ ⇒ **U7 移出本清单**。
- 🔴 **E-4 的范围**（2026-11-05 更新）：**HTTP 面已闭合**（§八·8.4）**且 gRPC 面已闭合**（§八·8.5）
  ⇒ ⇒ **场景轮（§八·8.7）补齐了 6 类场景**（create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / **重复请求（幂等重放）**）⇒ ⇒ **但它只有 B↔C 双侧活体、无第三方锚** ⇒ ⇒ 强度**低于**三方
  ⇒ ⇒ 「两侧行为一致」这句话现在成立于**三个面**（HTTP 单请求契约面 / gRPC 单请求契约面 / 6 类场景），**但三个面的证据强度不同**
  ⇒ ⇒ 引用本台账的 ✅ 时**必须连着这几条边界**（§8.4 与 §8.5 的证据边界表 + §8.7.4）。
- ✅ **verification.md §九 的 11 项「对拍必须覆盖」现已逐项有证据**（§11.7 覆盖度表）—— 🟠 其中「并发 / 超时 / 部分成功 / 恢复和清理 / 重复请求」来自**场景轮**（**只有双侧**），其余来自 **E-4 三方**。

---

## 九 · 本域 ↔ `common/` 跨层对账

见 **`docs/DRIFT/favorite/vs-common.md`**。

---

## 十 · 迁移审核报告（按 `.agents/verification.md` 第八阶段 · 核对日期 2026-11-04 · **2026-11-05 更新**）

### 11.1 审核范围

见 §〇（两侧坐标与规模）。

### 11.2 B → C 责任承接表

见 §一（按 B 侧源文件逐项展开 · **覆盖 B 侧 75 个主代码文件 + 7 个 DDL 脚本**）。

### 11.3 多文件合并和拆分专项报告

| 类型 | B → C | 审核结论 |
|---|---|---|
| **N:1** | api 7 个用例类 → `FavoriteApiService` | ✅ 7 个方法逐条对照（§三·3.1） |
| **N:1** | api 入口 + rpc 入口 → `FavoriteServiceApplication` | ✅ 见 §一·1.2 / §一·1.3 |
| **N:1** | api 鉴权 4 类 → `FavoriteJwtAuthInterceptor` | ⚠️ **有界偏离**（§五·5.3） |
| **1:N** | B 侧 `FavoriteFactWriteService` 等 9 类 → C 侧 `service/` 9 个（**包压平**） | ✅ 逐条承接 |
| **1:N** | B 侧 `favorite-common` 4 类 → C 侧 `contract/` 3 + `metrics/` 1 | ✅ 逐条承接 |
| **1:N** | B 侧 `FavoriteLegacyPreflightRunner` 1 类 → C 侧 `service/` 1 + `wiring/` 1（**报表计算 / 触发入口**分离） | ✅ 逐条承接（**2026-11-05 · P6-c ①** · §五·5.1 / §八·8.6） |
| **共享能力上收** | `JsonbTypeHandler` → `common/persistence/`（**D3**） | ✅ **域内 0 副本** |
| **Spring 接管** | gRPC 保护链 → `ProtectionAutoConfiguration` | ✅ **A3 PASS**（域内 0 重复） |
| **独立进程合并** | api + rpc + 3 个 fact 子命令 → 1 个 JVM | ✅ 见 §一·1.3（**preflight 已于 2026-11-05 一并落位** · §五·5.1） |
| **职责缺失** | —— | ✅ **0 条**（原 1 条「preflight 触发入口」⇒ **2026-11-05 闭合** · §五·5.1 / §八·8.6） |

### 11.4 框架版本差异报告

见 §四（10 项 · 逐项含「B 机制 / C 机制 / 具体变化 / 影响流程 / 是否影响业务 / 适配方式 / 验证证据 / 最终结论」）。

### 11.5 语义漂移清单

| Drift ID | 位置 | B 行为 | C 行为 | 原因 | 影响范围 | 严重度 | 修复建议 | 验证方法 |
|---|---|---|---|---|---|---|---|---|
| **DRIFT-FAV-001** | `pom.xml` `grpc-inprocess` scope | B 侧 pom **显式警告**：不得声明成 `test` scope（会顶掉 devh 传递来的 compile 作用域） | 原为 `test` ⇒ 生产 fat jar **起不来**（`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`） | 迁移时只搬了依赖坐标，**没搬警告块** | **整个域的可部署产物** | 🔴 **P0** | ✅ **已修**：改 `runtime` scope + **B 侧警告块整段搬入 pom** | 真 `java -jar` 起真应用 |
| **DRIFT-FAV-002** | `interceptor/FavoriteJwtAuthInterceptor` | 验签在 Servlet Filter（**3s 预算之外**） | 验签在 HandlerInterceptor（**预算之内**） | B 侧拆分的动机是「验签不占业务预算」；C 侧 File_Tree **无 `filter/` 包** | 极端慢验签场景下的 3s 预算判定 | 🟠 **P2** | 登记为有界偏离（like 域同款取舍）；若要修 ⇒ 新增 1 个 `OncePerRequestFilter` | 计时口径对照（未做） |
| **DRIFT-FAV-003** | `resources/db/migration/V6` | B 侧 `pg_constraint` 查询**无 schema 限定** | 逐字照抄 ⇒ **同款** | 继承 | 多 schema 库可能命中同名约束 | 🟠 **P3** | **不修**（改了 ⇒ SQL 不再逐字一致 ⇒ 引入新偏离）⇒ 登记 | — |
| **DRIFT-FAV-004** | `docs/Mapping_Table/favorite/favorite.md` §2.1 | B 侧 `DEV-FAV-RPC-007`：`favorite_item` 多一道 CHECK | C 侧 V6 **逐字照抄** ⇒ 行为一致，但**映射表漏记** | 映射表编写时漏项 | 文档层 | 🟢 **P4** | ✅ **已修**：映射表 M4 + 本台账 §二·2.6 已补记 | 文档对账 |
| **DRIFT-FAV-005** | `application.yml` `spring.flyway` 段 | B 侧 favorite-api **无** flyway 段（只有 rpc 侧有） | 合并为 1 个 app 后由**本段**承担 | **D1 合并的连带** | 无（语义等价） | 🟢 **P4** | ✅ 已落地（独立账本 + `baseline-version: 0`） | 真 PG + 真 Flyway |
| **DRIFT-FAV-006** | `application.yml` `grpc.client.*` | B 侧 favorite-api 的 `grpc.client.favorite-rpc`（**域内自我 gRPC**） | **无** `favorite-rpc` 客户端（**D2 直连**）；只有 `user-rpc` / `article-rpc` | **D2** | 无（域内往返取消） | 🟢 **P4** | ✅ 已落地 | **A4 断言 PASS** |
| **DRIFT-FAV-007** | `config/FavoriteApiProperties` / `config/FavoriteRpcProperties` vs `wiring/*` | B 侧：配置**只有一处** | C 侧：`FavoriteRpcProperties`（`@ConfigurationProperties`）**与** `wiring/` 6 个类的 `@Value` **读同一批键** | 装配层拆分时的落点选择 | ⚠️ 理论风险：**两处绑定同一键** ⇒ 若一处的默认值与另一处不一致 ⇒ 行为分叉 | 🟡 **P3** | ⚠️ **已核对**：两侧默认值与 B 侧 yml 逐条一致（`false` / `false` / 空串 / `8s` / `false`）⇒ 当前**无分叉**；建议后续统一到 `FavoriteRpcProperties`（**需主会话拍板**，不在本域写权内） | 逐键默认值对照 |
| **DRIFT-FAV-008** | `application.yml` `rag.protection.outbound-deadline` | B 侧 **两个面都有**（api `:194-197` / rpc `:219-221`）⇒ **2000ms 硬上界**（Go zrpc `default=2000`） | 🔴 **整块缺失** ⇒ 共享层 `capMillis` 落回默认 **0 = 不启用** ⇒ **出向 gRPC 零期限** ⇒ 下游不可达时**永久挂起** | 🔴 **全部出向 gRPC 调用**（建夹 / 收藏 / 列表 / 删除 / 改名）—— 下游不可达时**永久挂起**（零期限 ⇒ 请求不返回） | **D1 合并的连带**：搬 yaml 时只搬了一面 | 🔴 **P0**（出向调用行为完全不同） | ✅ **已修**：补 `cap-millis: 2000` | 真 fat jar + 不可达下游 ⇒ `POST /folder/create` 从「10s 超时」变成 **2.51s 返回 5001**；回归守卫 `config/FavoriteOutboundDeadlineContractTest`（含**反向对照**） |
| **DRIFT-FAV-009** | `application.yml` `observability.http-timeout` / `rpc-timeout` | B 侧 **两个面都有**（api `:243-246` = **3s** / rpc `:374-376` = **2s**） | 🔴 **整块缺失** ⇒ 共享层两个 timeout **无 env 兜底**、缺省 `Duration.ZERO` ⇒ **两个预算都不存在** | 🔴 **慢请求不被切断**（HTTP 3s / RPC 2s 两个预算都不存在 ⇒ 慢下游可把请求拖到上游超时） | 同上 | 🔴 **P1**（慢请求不被切断） | ✅ **已修**：补 `3s` / `2s` | `config/FavoriteOutboundDeadlineContractTest#productionYamlCarriesBothObservabilityBudgets` |
| **DRIFT-FAV-010** | `application.yml` `otel:` 段 | B 侧 **两个面都有**（api `:221-230` / rpc `:352-362`） | 🔴 **整块缺失** ⇒ 无 OTLP exporter / 无 sampler / 服务名缺省 | 🟠 **可观测性**（无 OTLP exporter / 无 sampler / 服务名缺省 ⇒ 本进程的 trace 出不出去） | 同上 | 🟠 **P2**（可观测性） | ✅ **已修**：按合并裁决补齐（带 scheme 的 endpoint · `favorite-service` · `parentbased_always_on`） | `config/FavoriteOutboundDeadlineContractTest#productionYamlCarriesOtelServiceName` |
| **DRIFT-FAV-011** | `application.yml` `management.metrics.tags.application` | B 侧 **两个面都有**（api `:216-217` = `${spring.application.name}` / rpc `:341-344` = `favorite-rpc`） | 🔴 **缺失** ⇒ 抓取侧按标签过滤**一条都匹配不到**（B 侧 `FavoriteRpcPrometheusCarrierGateTest:232-233` 断言过这个标签） | 🟠 **抓取侧的标签过滤**（`application` 标签缺失 ⇒ 按该标签抓取**一条都匹配不到** ⇒ 指标等于没有） | 同上 | 🟠 **P2**（可观测性 · 有 B 侧测试守着） | ✅ **已修**：补 `application: favorite-service`（合并名 · article 域同款） | 真 fat jar 抓 `/actuator/prometheus` ⇒ `favorite_request_total{...,application="favorite-service",...}` |
| **DRIFT-FAV-012** | `application.yml` `spring.cloud.nacos.discovery.register-enabled` | B 侧 **rpc 面有**（`:140-156`，含完整理由）⇒ 自动注册关掉，只留手写的 gRPC 端口注册 | 🔴 **缺失** ⇒ 自动注册**注册的是 web 容器端口** ⇒ 与手写注册**同名两个端口** ⇒ 调用方按 Nacos 地址连过来 ⇒ **gRPC 链路断**（业务码塌成 1015） | 🔴 **服务发现语义**（gRPC 链路断 ⇒ 所有跨进程调用方拿到的业务码**塌成 1015**） | 同上 | 🔴 **P1**（服务发现语义） | ✅ **已修**：补 `register-enabled: false`（article / like / follow 三域同款） | 真 fat jar ⇒ Nacos 清单里**只剩** `favorite-rpc`（修复前**同时**有 `favorite-service` ⇒ 指向 web 端口） |
| **DRIFT-FAV-013** | `application.yml` `management.endpoints.enabled-by-default` / `show-details` | B 侧 api 未写 `show-details`（Boot 默认 `never`）/ rpc 写 `when_authorized` | C 侧原为 `show-details: always` ⇒ **比 B 侧两个面都松** | 🟠 **健康端点细节泄露**（`show-details: always` ⇒ 未授权也能看到端点明细） | 同上 | 🟠 **P2**（健康端点细节泄露） | ✅ **已修**：取**更严**的一侧 `when_authorized` + 显式关掉未开启的端点（like / follow 域同款） | `application.yml` 逐键对照 + 文档对账 |
| **DRIFT-FAV-014** | `service/FavoriteApiSupport.java`（`#invokeRpc` / `#resolveBizCode` / `#carriesDeadlineExceeded`）· `service/FavoriteUserGuard.java`（`#onDownstreamFailure`）· B 侧 `favorite-api/.../FavoriteRpcClient.java:108-115` | B 侧 api → rpc 是**自我 gRPC 往返** ⇒ **那一跳也带 2s 出向 cap** ⇒ 慢下游时 **api 自己的 cap 先到** ⇒ 收到的是**自己的 DEADLINE_EXCEEDED**（**无业务 description**）⇒ ⇒ 反查不出业务码 ⇒ 落 **`CODE_SERVER_BUSY(1015)`**（Go `CodeServerBusy = 1015`）⇒ **oracle 自述**：「下游挂死时是 2s 先到 ⇒ api 返回 200 + 业务码 1015」 | **修前**：**D2 域内直连** ⇒ **没有 api→rpc 这一跳** ⇒ ⇒ 2s cap 落在 **service→user-rpc** 那一跳 ⇒ ⇒ 超时落进 `FavoriteUserGuard#onDownstreamFailure:116` ⇒ 非 `NOT_FOUND` ⇒ ⇒ `:132` `internalServerCommon()` ⇒ **5001**<br>**修后（方向 ②）**：① api 面在 `FavoriteApiSupport#invokeRpc` **自持同值 2s 上界**（**复用同一个配置键** `rag.protection.outbound-deadline.cap-millis` ⇒ **不是「第二个 cap 来源」**；**不换线程** ⇒ deadline 传播 / OTel 上下文 / 事务边界都不受影响）② **判据用 cause 链**（`DEADLINE_EXCEEDED` ⇒ 1015）⇒ **无竞态**且与 B 侧 `status.FromError` 形状逐字对应 ③ 领域层两处下游失败分支改 `internalServerCommon(e)` ⇒ **保留原始 gRPC cause** ⇒ ⇒ **api 面落 1015**、**gRPC 面不受影响**（仍 5001 · 与 B 侧 gRPC 面同形） | 🟠 **慢下游时的业务码**（调用方要分两套码处理 —— B 侧 1015 / 修前 C 侧 5001；**现已同为 1015**） | **D2 域内直连的结构性代价**（与 hot 域**同根因、两种表现**：hot 域 = 上界**整体消失**；favorite 域 = 上界**还在但作用点变了**）⇒ ⇒ **不是漏配**（两侧耗时桶逐字相同 `2000-2499` ⇒ **cap 的值与生效性都没漂**）⇒ ⇒ **改 yaml 修不好** ⇒ ⇒ **方向 ② = 在域内复刻 B 侧 api→rpc 那一跳的 cap** | 🟠 **P2**（慢下游时的**业务码**不同；调用方要分两套码处理） | ✅ **已修**（2026-11-05 用户拍板**方向 ②**）⇒ ⇒ **api 面两侧已同为 1015** ⇒ ⇒ 载体差异登记为 **`DEV-FAV-API-003`**（§六）⇒ ⇒ 详见 **§五·5.10** | ① 场景轮 S3（下游桩慢 5s）⇒ 两侧耗时桶**相同** `2000-2499` · 业务码**两侧同为 1015** ⇒ ⇒ **`KNOWN_DIFFS` 已清空**（表结构保留 ⇒ 现在任何两侧不等都真报红）<br>② `http/FavoriteApiHopDeadlineContractTest` —— **5 条真触发**（真 gRPC 慢下游 · `GetUser` 睡 5s）：api 面 ⇒ **1015** / **反向对照** gRPC 面 ⇒ **仍 5001** / 非超时对照 ⇒ **仍 5001** / api→领域层这一跳**必须自带预算载体** / 慢下游服务器**真的在监听**<br>③ **三处机制变异红→绿**：去掉「`DEADLINE_EXCEEDED` ⇒ 1015」⇒ **红**；领域层丢掉 gRPC cause ⇒ **红**；不绑预算载体 ⇒ **红** ⇒ ⇒ 还原后复跑**绿** |

### 11.6 完成度统计（**按 verification.md §11.6 分开统计**）

> 口径：**逐行重数**（不是估的）· 依据 §一的承接表 **75 行 B 侧职责**。

| 分类 | 条数 | 说明 |
|---|---|---|
| **已实现且已验证**（PASS，E-3 级） | **69** | 真 PG / 真 Tomcat / 真 gRPC 传输 / 真 Flyway 层（**2026-11-05 +1** = preflight 预检报表 · 真 PG 7 例 + 真 Tomcat 5 例） |
| **已实现但未验证**（NOT_VERIFIED） | **5** | 事实链路的 4 个 + 权威端点的字节口径（**两个面的 E-4 探针都不覆盖 fact 链路与权威端点** ⇒ 这 5 条仍缺 E-4） |
| **尚未实现**（NOT_IMPLEMENTED） | **0** | ✅ **原 1 条（preflight 触发入口 · P6-c）已于 2026-11-05 闭合**（见 §五·5.1 / §八·8.6） |
| **明确存在漂移**（FAIL） | **0** | 🔴 **DRIFT-FAV-001 已修**；**DRIFT-FAV-008 ~ 013（本轮真启动抓出的 6 条配置面缺失）已修**；**DRIFT-FAV-014（慢下游业务码 B=1015 / C=5001）已修**（2026-11-05 用户拍板方向 ② ⇒ api 面两侧同为 1015 · §五·5.10）；其余 6 条已登记为**有界偏离 / 文档缺口**，均**不改变可观察行为** ⇒ ⇒ **无待裁决项** |
| **框架差异待验证**（FRAMEWORK_DIFFERENCE） | **0** | §四 10 项**全部**已给出「是否影响业务行为」判定与适配方式 |
| **被外部条件阻塞**（BLOCKED） | **0** | ✅ 原 1 条（E-4 双侧活体对拍）已于 2026-11-04 闭合（**HTTP 面** · §八·8.4）· **2026-11-05 补齐 gRPC 面**（§八·8.5） |
| **已批准的主动变更**（INTENTIONALLY_CHANGED） | **12** | §六 的 12 条签字偏离（9 条继承 B 侧已签字 ＋ 3 条 C 侧专属：`DEV-FAV-RPC-008` 事务 · `DEV-FAV-RPC-009` 触发形态 · `DEV-FAV-API-003` api→领域层自持出向上界） |

### 11.7 verification.md §九「对拍必须覆盖」的覆盖度核对

| 必须覆盖 | C 侧单侧证据 | 双侧证据（**强度分两档**：🟢 三方 / 🟠 只有 B↔C） |
|---|---|---|
| 正常路径 | ✅ 真 Tomcat + **真 gRPC（`grpc/FavoriteGrpcRealTransportTest` · 真 TCP + 真 HTTP/2 + 真 protobuf · 5 条）** + 真 PG | 🟢 **E-4 三方**（HTTP：s01–s04 只读成功 · z01 写入生效验证 ／ **gRPC：c11 c17 c29 c31 c33 c34 成功路径**）<br>🟠 **场景轮 S1**（下游在场 ⇒ create **真的成功** ⇒ 两侧各落 1 行 · **只有双侧**） |
| 边界输入 | ✅ 重复参数 / 空值 / 越界 | 🟢 **E-4 三方**（HTTP：a04 只有前缀 · a05 小写 bearer · p02 缺必填 · p04 缺 form 参数 ／ **gRPC：c02 c07 空串 · c03 c08 纯空白**） |
| 空值和缺省值 | ✅ 空 body / 缺字段 / `?folderId=` 空值 | 🟢 **E-4 三方**（HTTP：p02 / p04 / p07 缺字段 · s03 空集合 ／ **gRPC：c02 c07 · c12 c18 空集合**） |
| 非法输入 | ✅ 类型不匹配 / 语法错误 / 根非 object | 🟢 **E-4 三方**（HTTP：p01 非法 JSON · p03 类型不对 · p05 非数字 · p06 无 JSON Content-Type ／ **gRPC：c01 c05 c06 c10 c13 c14 c19 c22 c25 入参越界 ⇒ 5003**） |
| 类型不匹配 | ✅ 共享层 `Coercion`（数字进 String 一律拒） | 🟢 **E-4 三方**（HTTP：p03 `name:123` ⇒ 5003）<br>⚪ **gRPC 面不适用**（proto3 **强类型** ⇒ 类型不匹配在**编解码期**就失败 ⇒ 不是服务端行为） |
| 重复请求 | 🟡 领域层唯一约束（真 PG） | 🟠 **场景轮 S6**（**同一请求连发两次** ⇒ 三个子场景：建夹重放 **200 → 5203**（重名预查）· 收藏重放 **200 → 5302**（重复收藏判定）· **同名改名 200 → 200 且 `update_time` 未推进** ⇒ 真·幂等）⇒ 两侧**六个业务码 + 改名响应体全部相同** · 两侧各**恰好 1 行** ⇒ **只有双侧，无第三方锚** |
| 并发请求 | 🟡 行级锁 + `SKIP LOCKED`（真 PG） | 🟠 **场景轮 S2**（8 个同名建夹**同时**发起 ⇒ 两侧分布 **`200:1,5203:7,`** · 两侧各**恰好 1 行** ⇒ 无重名）⇒ **只有双侧，无第三方锚** |
| 外部依赖失败 | ✅ 下游 gRPC 失败 ⇒ 1015 / 业务码 | 🟢 **E-4 三方**（HTTP：b01 / b02 ／ **gRPC：c04 c09** 下游缺席 ⇒ 5001）<br>🟠 **场景轮 S1/S4**（下游**在场**时的失败路径 ⇒ 两侧 biz=5008） |
| 超时 | ✅ **出向 2s 硬上界真触发**（真 fat jar + 不可达下游 ⇒ 2.100617s 返回 5001）· `FavoriteOutboundDeadlineContractTest` 第 3 条（真 TCP 黑洞服务端 ⇒ DEADLINE_EXCEEDED）· 共享层预算链 · ✅ `FavoriteApiHopDeadlineContractTest`（5 条真触发） | 🟠 **场景轮 S3**（下游桩慢 5s ⇒ 两侧**耗时桶相同** `2000-2499` ⇒ **出向上界同值**）⇒ ✅ **业务码两侧同为 1015** ⇒ ⇒ **DRIFT-FAV-014 已修**（§五·5.10 · 方向 ②）<br>✅ **慢 DB（非慢下游）那一格也两侧同为 1015**（共享层 `DeadlineState` → `DeadlineQueryTimeoutInjector` → `SqlTimeoutStatusTranslator`（57014 ⇒ 1015）**已落地** ⇒ ⇒ 共享层 2026-11-03 的 V15 已修 ⇒ ⇒ **该格已闭合** · §五·5.10） |
| 部分成功 | 🟡 事务回滚（真 PG） | 🟠 **场景轮 S4**（outbox 写被打断 ⇒ 两侧 biz=5008 · **各 0 行** ⇒ **没有半截状态** · 恢复轮各恰好 1 行）⇒ **只有双侧** |
| 恢复和清理 | 🟡 调度器优雅关闭 | 🟠 **场景轮 S4 / S5 的恢复轮**（故障撤掉 / Redis 回来 ⇒ 两侧都恢复正常）<br>🔴 **但调度器优雅关闭仍是 E-3 单侧** |
| **服务面 / 框架面** | ✅ devh 的 gRPC server 真起（真 TCP · 真 HTTP/2） | 🟢 **E-4 三方**（**gRPC：c34 health check ⇒ SERVING · c35 未知方法 · c36 未知服务** ⇒ 两侧措辞逐字相同） |
| **Redis 故障（fail-open）** | ✅ 域内单测覆盖其语义 | 🟠 **场景轮 S5**（停 Redis ⇒ 两侧 **status=200 / biz=200** ⇒ **fail-open** · 恢复轮正常）⇒ **只有双侧** |

---

## 十一 · verification.md §十四「最终必须回答的问题」（**逐条回答 · 核对日期 2026-11-04 · **2026-11-05 更新**）

> ⚠️ 编号说明：§十 下面的 **11.1 ~ 11.7** 是 **verification.md §11「审核报告」**的小节映射（不是本台账的第十一节）⇒ ⇒ 本节才是本台账的**第十一节**。

| # | 问题 | 回答 |
|---|---|---|
| 1 | C 是否完整承接 B 的全部实际职责？ | ✅ **是（落点缺口已归零）**：75 个 B 侧主代码文件 ⇒ **75 个全部有承接点**；**原唯一缺口 = preflight 触发入口** ⇒ 已于 2026-11-05 闭合（P6-c 拍板① ⇒ `service/FavoriteLegacyPreflightService` ＋ `wiring/FavoriteLegacyPreflightEndpoint`，见 §五·5.1 / §八·8.6）⇒ ⇒ **C 列 61 → 63** |
| 2 | Mapping_Table 的 B → C 映射是否与当前真实代码一致？ | ✅ **一致**：`tools/check-mapping-coverage.py` ⇒ **C 列 643/643（100%）· B 列 752/752（100%）· B 列全局未覆盖 0** · EXIT=0；本轮闭合 M1 / M2 / M4 / M6，且 **M3（preflight）已于 2026-11-05 闭合** |
| 3 | File_Tree 规定的目标结构是否正确落地？ | ✅ **已落地**：`tools/difftest/check-docs-vs-tree.py` ⇒ **全部相符合**（File_Tree 双向 · 幂等 · 路径 · 落点计数 · 包名）· EXIT=0 |
| 4 | 多文件合并是否造成职责、分支或异常路径遗漏？ | ✅ **未发现**：3 处 N:1 合并（api 7 用例 → `FavoriteApiService` · api+rpc 入口 → `FavoriteServiceApplication` · api 鉴权 4 类 → `FavoriteJwtAuthInterceptor`）逐条对照（§三·3.1 / §一）；**唯一有界偏离**是鉴权计时口径（§五·5.3 · 已登记） |
| 5 | 文件拆分是否造成调用链、数据传递或事务边界断裂？ | ✅ **未发现**：2 处 1:N 拆分（B 侧 `favorite-common` 4 类 → `contract/` 3 + `metrics/` 1；rpc 侧 9 类 → `service/` 9 个，**包压平**）⇒ 事务边界仍在 `FavoriteDomainService` 单一入口（§一·1.3） |
| 6 | API + RPC 合并是否改变原有入口和执行行为？ | 🟡 **改了入口、没改行为**：D1 把 2 个进程并成 1 个 ⇒ **7 个 HTTP 端点与 7 个 gRPC 方法**都还在（§二·2.1 / §二·2.5）；**D2 域内直连**取消了域内自我 gRPC 往返（§二·2.5 / DRIFT-FAV-006）⇒ **可观察差异 = 少了 1 次进程内往返**，其余行为一致 |
| 7 | Common Starter 上收是否遗漏原有消费方？ | ✅ **未遗漏**：`JsonbTypeHandler` → `common/persistence/`（D3）⇒ **域内 0 副本**；共享层 25 个被消费类**全部已实现 · 0 空壳**（`vs-common.md` §1） |
| 8 | Spring Boot 4 → 3.5 是否引入业务可观察行为变化？ | ✅ **未引入**：§四 的 10 项差异**全部**给出「是否影响业务行为」判定与适配方式；**唯一被真启动抓到的行为级问题**是本域自己的配置缺失（DRIFT-FAV-008 ~ 013），**不是框架差异** |
| 9 | Tomcat / Jackson / Spring Framework 的版本变化是否产生实际漂移？ | 🟡 **3 处已登记、0 处改变可观察行为**：Tomcat 三旋钮（**两侧同形** · 共享层把 `maxParameterCount` 抬到 10001 ⇒ 域内 yaml 写 2048 是同形不是漂移）· Jackson 2 vs 3（`JacksonException extends IOException` ⇒ catch 块不同，已适配）· Jackson 3 的 HTML 转义**刻意不加**（B 侧 Java 端口用 Jackson 2 默认不转义 ⇒ 加了反而 C ≠ B） |
| 10 | 哪些差异已经通过证据证明安全？ | ✅ **13 条 DRIFT 条目**：002（鉴权计时 · 有界偏离）· 003（`pg_constraint` 无 schema 限定）· 004（映射表漏记）· 005（flyway 段合并承担）· 006（D2 无自我 gRPC 客户端）· 007（两套配置源 · 当前无分叉）· 008 ~ 013（**本轮真启动抓出的 6 条配置面缺失 · 已修**）· 014（**慢下游业务码 · 已按方向 ② 修完**）⇒ ⇒ 另加 §六 的 **12 条**签字偏离（9 条继承 B 侧 ＋ 3 条 C 侧专属） |
| 11 | 哪些差异仍然无法确认？ | 🟠 **4 类**：① **对拍的范围**：🟢 **HTTP 面已闭合**（32 case × 3 方 · 变异验证红→绿 · §八·8.4）**且 gRPC 面已闭合**（36 case × 3 方 · 变异验证红→绿 · §八·8.5）⇒ 🟠 **场景轮补齐 6 类场景**（create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / **重复请求（幂等重放）** · §八·8.7）⇒ ⇒ 🔴 **但场景轮只有 B↔C 双侧、无第三方锚**（本机无 `Sea-etcd`）⇒ ⇒ **「两侧行为一致」在三个面上成立，但三面强度不同**；✅ **verification.md §九 的 11 项「对拍必须覆盖」已逐项有证据**（§11.7）② ✅ **慢 DB 那一格已闭合**（复核订正 2026-11-05：共享层 `DeadlineState` SQL 超时链**早已落地** ⇒ ⇒ 慢 DB 两侧同为 1015 ⇒ ⇒ **DRIFT-FAV-014 无遗留格** ⇒ §五·5.10）③ **B 侧 54 个测试类未逐个移植**（本域 **29 个测试类 / 215 例** ⇒ **不声称测试覆盖度已对齐**）④ **共享层 `CanonicalHash` 的 JCS 指纹**（G-2 · 归 `common/` 收口任务 · 本域只登记） |
| 12 | 当前 C 是否具备可靠的行为对拍条件？ | ✅ **具备「双侧」条件（HTTP 面 + gRPC 面 + 场景轮）**：单侧所需全齐（真 PG 容器 · 真 Redis · 真 Nacos · 真 Tomcat · 真 gRPC 传输 · 真 fat jar 产物）⇒ **C 侧 215 用例全绿**；**双侧**条件已具备（✅ `tools/difftest/favorite/` ⇒ B 侧 + C 侧 + 真 PG + 真 Redis 同时跑 ⇒ **HTTP 面 32 case** + **gRPC 面 36 case** 两轮三方一致 ＋ **场景轮 6 类场景全绿**）⇒ ⇒ **三个面都可下「两侧等价」的结论**；⚠️ **但结论的范围仍受 §8.4 / §8.5 / §8.7 的证据边界表约束**（🟠 场景轮**只有双侧**） |

---

## 一句话总览

**B 侧 75 个主代码文件 / 12,574 行 / 7 个 DDL ⇒ C 侧 63 个文件 / 13,426 行 / 7 个 DDL（逐字零差异）**；
**N:1 合并 3 处 · 1:N 拆分 3 处 · 共享能力上收 1 处 · 独立进程合并 1 处**；
**L1 契约（7 端点 / 24 错误码 / 4 种响应形状 / DTO JSON 形状 / proto / 4 表 2 触发器）逐条一致**；
**L2 语义（7 用例判据 / span 的三处「刻意不统一」/ 两条独立错误出口 / 12 条文章快照判定 / legacy 预检的四条判据）逐条保留**；
**L4 的 10 项 Boot 4 → 3.5 差异全部给出判定与适配**；
**🔴 P0 漂移 1 条（`grpc-inprocess` scope）已修**；
**🔴 第二轮真启动又抓出 6 条配置面缺失（DRIFT-FAV-008 ~ 013）⇒ 已修并有回归守卫**；
**✅ 落点缺口 0 条**（原「preflight 触发入口」1 条 ⇒ **2026-11-05 P6-c 拍板①（Actuator 自定义端点）后闭合** ⇒ C 列 61 → 63 ⇒ 见 §五·5.1 / §八·8.6）；
**测试 29 类 / 215 例 · Failures=0 · Errors=0 · Skipped=0**；
**🔴 第三轮又抓出 1 条「红绿取决于执行顺序」的测试设施缺陷（全局 name resolver 被 devh 接管）⇒ 已修（收敛到 like 域先例 · 机制与证据见 §五·5.8）**；
**🔴 第四轮又抓出 1 条「失败表象与真因完全无关」的测试设施缺陷（真 PG 测试类抢同一个 gRPC 端口 ⇒ 24 例全红但栈里没有端口冲突）⇒ 已修并加了机器守门（机制与证据见 §五·5.9）**；
**🟡 第五轮新增「场景轮」（6 类场景：create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / **重复请求（幂等重放）**）⇒ 6/6 全绿 · 绝对断言全过 · 变异验证红→绿（两轮）⇒ 完整复跑 EXIT=0**（机制与证据见 §八·8.7）；
**✅ 场景轮同时抓出的 1 条真漂移（DRIFT-FAV-014：慢下游时业务码 B=1015 / C=5001 ⇒ D2 域内直连的结构性代价）已按用户拍板的「方向 ②」修完 ⇒ ⇒ api 面两侧同为 1015 · gRPC 面不受影响 ⇒ ⇒ 载体差异登记为 `DEV-FAV-API-003`（见 §五·5.10）**；
**证据强度：HTTP 面 = E-4（32 case × 3 方 · Go 冻结字节 == B 侧活体 == C 侧活体 · 变异验证红→绿）/ gRPC 面 = E-4（36 case × 3 方 · Go gRPC 冻结锚 == B 侧 favorite-rpc 活体 == C 侧 favorite-service 活体 · 变异验证红→绿）/ 场景轮 = 🟠 只有 B↔C 双侧活体（无第三方锚）**；
**🔴 本域无 E-4 的说法已作废（2026-11-04 HTTP 面闭合，见 §八·8.4；2026-11-05 gRPC 面闭合，见 §八·8.5）—— 但场景轮的强度低于三方（§8.7.4）；DRIFT-FAV-014 已闭合（§五·5.10 · 慢下游与慢 DB 两格均两侧同为 1015）**。
