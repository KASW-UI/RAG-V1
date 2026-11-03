# RAG → RAG-V1 迁移审核报告 —— `article` 域（`article-service`）

> **由 `align/article` 工作树产出** · **基线 commit `6a3acb4`**（本地 `main`）· 分支 `align/article`
> **核对日期**：2026-11-03
> **审核依据**：[`.agents/verification.md`](../../../.agents/verification.md)（八阶段审核 Prompt）
> **判定基准**：**B 侧 = 唯一真理**（D16）⇒ B 侧路径 `/Users/pr/Documents/java&golang/RAG/service/article/`（**只读**）
> **配套文件**：[`vs-common.md`](vs-common.md)（本域 ↔ `common/` 共享层跨层对账）
> **写权**：✅ 本域 `service/article/**` ＋ `docs/DRIFT/article/**`；
> 🔴 `common/**` **只读**（AGENTS.md §五.3 规则 3）· `docs/*/_INDEX.md` 与 `AGENTS.md` **不碰**

> ⚠️ **本报告的「✅ 一致」一律读作「在该证据强度下一致」**，**不是**「已做双侧活体对拍」。
> **证据强度分级**（E-4 双侧活体对拍 / E-3 真中间件 / E-2 源码级对照 / E-1 形态级 / E-0 未对照）见 `docs/DRIFT/_INDEX.md` 末节。

---

## 11.1 审核范围

| 项 | 内容 |
|---|---|
| **B 的模块和路径** | `service/article/`（RAG 仓）—— `article-api`（43 个主代码 `.java`）＋ `article-common`（1）＋ `article-rpc`（39）＝ **83** 个主代码类 |
| **C 的模块和路径** | `service/article/`（RAG-V1）—— artifactId **`article-service`** · 包 `com.rag.article` · **63** 个主代码 `.java`（**0 空壳**） |
| **Boot 版本（B）** | **Spring Boot 4.0.8**（Spring Framework **7.0.9**） |
| **Boot 版本（C）** | **Spring Boot 3.5.11**（Spring Framework 6.2.x） |
| **依赖版本（B → C）** | Spring Cloud `2025.1.0 → 2025.0.3` · SCA `2025.1.0.0 → 2025.0.0.0`（Nacos **3.0.3**）· MyBatis-Plus `3.5.17 → 3.5.17`（**同**）· MyBatis `3.5.19 → 3.5.19`（**同**）· gRPC `1.63.0 → 1.63.0`（**同**）· protobuf `3.25.5 → 3.25.5`（**同**）· jjwt `0.12.6 → 0.12.6`（**同**）· Flyway `— → 11.14.1` · Jackson **Boot 4 的 Jackson 3 → Boot 3.5 的 Jackson 2.19.4**（D14）· Java `21 → 21`（**同**） |
| **使用的 Mapping_Table 文档** | [`docs/Mapping_Table/article/article.md`](../../Mapping_Table/article/article.md)（**488** 行 · C 列 **63/63 = 100%** · B 列 **83/83 = 100%** · 映射行 **76**） |
| **使用的 File_Tree 文档** | [`docs/File_Tree/article.md`](../../File_Tree/article.md)（**按实际文件系统生成** · `python3 tools/gen-file-tree.py`） |
| **当前代码版本** | 基线 `6a3acb4`（本地 `main`）＋ 本工作树**未提交**改动（`git status` 全部为 `M` / `??`） |
| **审核方式** | §11.2 承接表（**83 行 · 逐个源文件**）· §11.3 合并/拆分专项 · §11.4 Boot 4→3.5 差异 · §11.5 漂移清单 · §11.6 完成度统计 |
| **守门读数** | `check-arch-invariants.py` = **PASS 10 · FAIL 0 · N/A 2 · PENDING 1 · EXIT=0**（PENDING 属 like/comment/favorite，**非本域**）· `check-docs-vs-tree.py` = **✅ 全部相符合 · EXIT=0** · `check-mapping-coverage.py` = article **C 63/63 · B 83/83**（⚠️ 脚本 `ROOT` 硬编码指向主工作区 ⇒ 本次**临时改 ROOT 指工作树**后跑 ⇒ 跑完**已还原**；⚠️ 该脚本 **EXIT=1**，2 条 B 列报错**全部落在 users 域**（`docs/Mapping_Table/users/user/user.md:132` 的 B 列写了 `CreateUserReq.java` / `CreateUserResp.java`，而 B 侧实际是 `RegisterReq.java`）⇒ **在基线主工作区复跑同样报这 2 条** ⇒ 与本域无关，按 §五.3 规则 2「只写自己域」**不改**） |

---

## 11.2 B → C 责任承接表

> **口径**：**83 个 B 侧主代码 `.java` 逐个列行**（不含 B 侧测试文件；B 侧另有 45 个测试类，期望值移植见 §11.3-4）。
> **实现状态**：`✅ 已实现` / `🟡 部分实现（附原因）` / `⛔ 有意不建（附依据）`。
> **验证状态**：`PASS` / `NOT_VERIFIED` / `INTENTIONALLY_CHANGED` / `FRAMEWORK_DIFFERENCE`。
> **证据强度**：`E-3` 真中间件 / `E-2` 源码级对照 / `E-1` 形态级。

### 11.2.1 B 侧 `article-api`（43 个）

| # | B 源文件 | B 源职责 | C 承接文件 | C 承接方法 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|---|---|
| 1 | `article-api/…/ArticleApiApplication.java` | api 进程入口 | `com.rag.article.ArticleServiceApplication` | `main` | N:1 | ✅ | PASS | E-2（与 rpc 侧入口合并，D1） |
| 2 | `article-api/…/advice/GlobalExceptionHandler.java` | 全局异常 → HTTP 映射（8 类分支） | `advice/ArticleApiExceptionHandler` | 各 `@ExceptionHandler` | 1:1 | ✅ | PASS | E-3（真 Tomcat 契约测试） |
| 3 | `article-api/…/advice/GlobalResponseAdvice.java` | 成功路径信封包装（5 条放行分支） | `advice/ArticleApiResponseAdvice` | `supports` · `beforeBodyWrite` | 1:1 | ✅ | PASS | E-2（放行 3 必须看 `returnType` 不能看 body） |
| 4 | `article-api/…/auth/ArticleJwtProtectedRoutes.java` | **受保护路由表 + public 前缀集合**（L1） | `interceptor/ArticleJwtAuthFilter` + `interceptor/JwtAuthInterceptor` | `isProtected` · `pathWithinContext` | N:1 | ✅ | PASS | E-2（逐条对照 `:73-77,88-101,112-119,122-125`） |
| 5 | `article-api/…/auth/ArticleJwtSignatureVerifier.java` | JWT 验签（HMAC + claims） | `interceptor/ArticleJwtAuthFilter` | `verify` | 1:N | ✅ | PASS | E-2（`:78-79,88-119,131-136`） |
| 6 | `article-api/…/auth/ArticleJwtVerificationResult.java` | 验签结果载体 | `interceptor/ArticleJwtAuthFilter` | 嵌套 `VerificationResult` record | 1:N | ✅ | PASS | E-2（`:16-34`） |
| 7 | `article-api/…/auth/GoStyleJwtAuthFilter.java` | Servlet Filter 段验签（**70s 预算外**） | `interceptor/ArticleJwtAuthFilter` | `doFilterInternal` · `unauthorized` | 1:1 | ✅ | PASS | E-3（真 Tomcat 401 空 body） |
| 8 | `article-api/…/client/ArticleRpcClient.java` | 同域 gRPC 客户端 | **无独立文件** → `service/ArticleApiService` | 直接注入域服务 | N:1 | ✅ | PASS | E-2（D2 域内直连） |
| 9 | `article-api/…/client/UserRpcClient.java` | 跨域作者名补全 | `service/ArticleApiSupport` | `enrichArticleAuthor` · `fallbackAuthorName` | N:1 | 🟡 | **INTENTIONALLY_CHANGED** | E-2（**无对端域** ⇒ 走 B 侧**失败分支** ⇒ 恒回落值） |
| 10 | `article-api/…/config/ArticleApiProperties.java` | HTTP 层配置聚合 | `config/ArticleApiProperties` | `@ConfigurationProperties` | 1:1 | ✅ | PASS | E-2（fail-closed 守卫保留） |
| 11 | `article-api/…/config/ArticleGoStyleCoercion.java` | 域内参数强制转换 | `common/web/Coercion`（`config/WebMvcConfig` 挂 `goStyleCoercionCustomizer`） | — | 1:N | ✅ | PASS | E-2（上收共享层 · **白名单条目**） |
| 12 | `article-api/…/config/ArticleGoStyleCorsFilter.java` | 域内 CORS（Go `rest.WithCors()` 无参 ⇒ **通配 + OPTIONS 恒 204**） | `config/ArticleCorsFilter` | `doFilter` · `setVaryHeaders` · `setCorsHeaders` | 1:1 | ✅ | PASS | E-3（真 Tomcat：7 个响应头逐字 + OPTIONS 204 空 body + 无 token 仍 204 + Vary 1/3 条 + 非 OPTIONS 方法不匹配仍 404）<br>🔴 **2026-11-03 由「假 PASS」改判并补齐**（见 §11.5.1 · D-13）：本行原写「`common/web/` 承担」⇒ **实测 `common/web/` 无 CORS 类** ⇒ 那条前提不成立 |
| 13 | `article-api/…/config/GoStyleJsonBodyLimitFilter.java` | JSON 体上限 | `common/web/JsonBodyLimitFilter`（自动装配） | — | 1:N | ✅ | PASS | E-2（上收共享层） |
| 14 | `article-api/…/config/WebMvcConfig.java` | MVC 装配（拦截器 + 白名单 + 强转） | `config/WebMvcConfig` | `addInterceptors` · `goStyleCoercionCustomizer` | 1:1 | ✅ | PASS | E-2（装配层 A5/A10 PASS） |
| 15 | `article-api/…/controller/ArticleController.java` | **7 个端点**（L1 路由） | `controller/ArticleController` | 7 个 `@*Mapping` handler | 1:1 | ✅ | PASS | E-2（`/v1` + 4 组 method + `:id` 段逐条一致；⚠️ 旧 TODO javadoc 的 5 条错路径已订正） |
| 16 | `article-api/…/dto/Article.java` | 文章响应投影（**17 字段 · 字节级顺序契约**） | `dto/article/Article` | record 组件 + `@JsonPropertyOrder` | 1:1 | ✅ | PASS | E-2（`author_name` 用 `NON_EMPTY`；`secondary_tags`/`ext_info` **保持 null**） |
| 17 | `article-api/…/dto/CreateArticleReq.java` | 创建入参 | `dto/article/CreateArticleReq` | record + `@JsonCreator` 次构造器 | 1:1 | ✅ | PASS | E-2（`title`/`content` 用 `@NotNull(message=…)`） |
| 18 | `article-api/…/dto/CreateArticleResp.java` | 创建响应 | `dto/article/CreateArticleResp` | record | 1:1 | ✅ | PASS | E-2（`article_id`） |
| 19 | `article-api/…/dto/DeleteArticleReq.java` | 删除入参 | `dto/article/DeleteArticleReq` | record | 1:1 | ✅ | PASS | E-2 |
| 20 | `article-api/…/dto/DeleteArticleResp.java` | 删除响应 | `dto/article/DeleteArticleResp` | record | 1:1 | ✅ | PASS | E-2（`success`） |
| 21 | `article-api/…/dto/GetArticleReq.java` | 读单篇入参 | `dto/article/GetArticleReq` | record | 1:1 | ✅ | PASS | E-2（path + query 两处拼成，**非 JSON**） |
| 22 | `article-api/…/dto/GetArticleResp.java` | 读单篇响应 | `dto/article/GetArticleResp` | record | 1:1 | ✅ | PASS | E-2（`article`） |
| 23 | `article-api/…/dto/GoStyleStringFields.java` | JSON 字段类型判定 | `web/ArticleStringFields` | `parse` | 1:1 | ✅ | **NOT_VERIFIED**（**待收口** · 共享层 `StringFields` 包私有） | E-2（逐行副本；⚠️ **两份不得分叉**） |
| 24 | `article-api/…/dto/ListArticlesReq.java` | 列表入参（8 个 query） | `dto/article/ListArticlesReq` | record + `of(...)` | 1:1 | ✅ | PASS | E-2（默认值 `page=1`/`page_size=20`/`sort_by=create_time`/`desc=true`） |
| 25 | `article-api/…/dto/ListArticlesResp.java` | 列表响应 | `dto/article/ListArticlesResp` | record | 1:1 | ✅ | PASS | E-2（`articles` 空 ⇒ **`null`** 非 `[]`；顺序 `articles,total,page,page_size`） |
| 26 | `article-api/…/dto/UpdateArticleReq.java` | 更新入参 | `dto/article/UpdateArticleReq` | record + `@JsonCreator` 次构造器 | 1:1 | ✅ | PASS | E-2（7 字段全 `,optional` ⇒ **不加 `@NotNull`**） |
| 27 | `article-api/…/dto/UpdateArticleResp.java` | 更新响应 | `dto/article/UpdateArticleResp` | record | 1:1 | ✅ | PASS | E-2（`success`） |
| 28 | `article-api/…/dto/UploadImageResp.java` | 上传响应 | `dto/upload/UploadImageResp` | record | 1:1 | ✅ | PASS | E-2（`image_url`） |
| 29 | `article-api/…/form/GoFormValues.java` | query/form 取值真值表 | `web/ArticleQueryValues` | `raw` · `first` · `bool` · `int32` · `text` | 1:1 | ✅ | **NOT_VERIFIED**（**待收口** · 共享层只有上限闸门） | E-2（5 条真值表逐条照抄） |
| 30 | `article-api/…/http/GoStyleBadRequestException.java` | 400 + text/plain | `common/web/BadRequestException` | — | 1:N | ✅ | PASS | E-2（上收共享层） |
| 31 | `article-api/…/http/GoStyleGrpcStatusMapper.java` | gRPC status → HTTP | `common/web/GrpcStatusMapper` | — | 1:N | ✅ | PASS | E-2（上收共享层） |
| 32 | `article-api/…/http/GoStyleHttpErrorException.java` | 任意状态 + body | `common/web/HttpErrorException` | — | 1:N | ✅ | PASS | E-2（上收共享层） |
| 33 | `article-api/…/http/GoStylePlainTextErrors.java` | text/plain 错误写出 | `common/web/PlainTextErrors` | — | 1:N | ✅ | PASS | E-2（上收共享层） |
| 34 | `article-api/…/interceptor/JwtAuthInterceptor.java` | HandlerInterceptor 段（预算内） | `interceptor/JwtAuthInterceptor` | `preHandle` | 1:1 | ✅ | PASS | E-2（失败**原样重抛**；写 `CLAIM_ATTRIBUTE="userId"`） |
| 35 | `article-api/…/service/ArticleApiSupport.java` | 6 个 helper（身份解析 / 投影 / clone） | `service/ArticleApiSupport` | `userId` · `toArticle` ×2 · `cloneStringList` · `cloneStringMap` · `isPublished` · `msg` | 1:1 | ✅ | PASS | E-2（`cloneStringMap` **必须 `TreeMap` + `CODE_POINT_ORDER`**，不能用 `LinkedHashMap`） |
| 36 | `article-api/…/service/ArticleAuthorResolver.java` | 作者名补全（**吞错**） | `service/ArticleApiSupport` | `enrichArticleAuthor` · `enrichArticleAuthors` · `fallbackAuthorName` | 1:N | 🟡 | **INTENTIONALLY_CHANGED** | E-2（**无 users 域对端** ⇒ 恒走失败分支） |
| 37 | `article-api/…/service/ArticleCreateService.java` | 创建（5 分支 + `CreateFailure`） | `service/ArticleApiService` | `createArticle` · `CreateFailure` | N:1 | ✅ | PASS | E-2（`CreateFailure` **带 data**） |
| 38 | `article-api/…/service/ArticleDeleteService.java` | 删除（4 分支） | `service/ArticleApiService` | `deleteArticle` | N:1 | ✅ | PASS | E-2 |
| 39 | `article-api/…/service/ArticleGetAuthorService.java` | 作者视角读（4 分支 + **双检**） | `service/ArticleApiService` | `getAuthorArticle` | N:1 | ✅ | PASS | E-2（**保留 B 侧注释明示的向后兼容双检**） |
| 40 | `article-api/…/service/ArticleGetService.java` | 公开读（3 分支） | `service/ArticleApiService` | `getArticle` | N:1 | ✅ | PASS | E-2 |
| 41 | `article-api/…/service/ArticleListService.java` | 列表（2 分支 + **整包拒绝**） | `service/ArticleApiService` | `listArticles` | N:1 | ✅ | PASS | E-2（**整包拒绝**，不是静默跳过该 item） |
| 42 | `article-api/…/service/ArticleUpdateService.java` | 更新（鉴权读 4 分支 + 写 3 分支） | `service/ArticleApiService` | `updateArticle` | N:1 | ✅ | PASS | E-2（**必须保留「先读后写」两段鉴权** ⇒ 否则「文章不存在」会从 1002 变 1015） |
| 43 | `article-api/…/service/ArticleUploadService.java` | 上传（裸响应） | `service/ArticleApiService` | `uploadImage` | N:1 | ✅ | PASS | E-2（multipart 字段名固定 `image`） |

### 11.2.2 B 侧 `article-common`（1 个）

| # | B 源文件 | B 源职责 | C 承接文件 | C 承接方法 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|---|---|
| 44 | `article-common/…/errmsg/ErrorCode.java` | 错误码 + 文案表 | `contract/ErrCodes` + `contract/ErrMsg` | `getCode` · `getMsg` · `of(int)` | 1:N | ✅ | PASS | E-3（`ArticleErrorCodeContractTest` **15 例**） |

### 11.2.3 B 侧 `article-rpc`（39 个）

| # | B 源文件 | B 源职责 | C 承接文件 | C 承接方法 | 映射类型 | 实现 | 验证 | 证据 |
|---|---|---|---|---|---|---|---|---|
| 45 | `article-rpc/…/ArticleRpcApplication.java` | rpc 进程入口 | `com.rag.article.ArticleServiceApplication` | `main` | N:1 | ✅ | PASS | E-2（与 api 侧入口合并，D1） |
| 46 | `article-rpc/…/bootstrap/ArticleSchemaGate.java` | **缺触发器 ⇒ fail-fast** | `wiring/ArticleSchemaGate` | `run` | 1:1 | ✅ | PASS | E-3（真 PG 上 DDL 建出触发器 ⇒ 门禁放行） |
| 47 | `article-rpc/…/bootstrap/MinioBucketBootstrap.java` | 幂等建桶（**失败只 warn 不 panic**） | `wiring/MinioBucketBootstrap` | 构造期建桶 | 1:1 | ✅ | PASS | E-2（幂等语义保留） |
| 48 | `article-rpc/…/bootstrap/NacosRegistration.java` | 显式注册 | `wiring/NacosRegistration` | 构造期注册 | 1:1 | ✅ | PASS | E-2（注册 **gRPC 端口**，非 web 端口） |
| 49 | `article-rpc/…/bootstrap/SnowflakeBootstrap.java` | 雪花位段/epoch | `wiring/SnowflakeBootstrap` | 初始化 | 1:1 | ✅ | PASS | E-2（位段是业务契约） |
| 50 | `article-rpc/…/client/ArticleObjectStorageClient.java` | MinIO put/get + 公开 URL + 幂等建桶 | `client/ArticleObjectStorageClient` | put / get / 建桶 | 1:1 | ✅ | PASS | E-2（⚠️ MinIO **必须排除 Jackson 2**） |
| 51 | `article-rpc/…/client/ContentSecurityAutoConfiguration.java` | 内容安全兜底装配 | `client/ContentAuditAutoConfiguration` | `@Bean` 兜底注册 | 1:1 | ✅ | PASS | E-2（`ContentSecurity*` → `ContentAudit*` 去域名含义） |
| 52 | `article-rpc/…/client/ContentSecurityPort.java` | 审核端口 | `client/ContentAuditPort` | `auditText` · `detectImageAd` | 1:1 | ✅ | PASS | E-2 |
| 53 | `article-rpc/…/client/ImageSecurityRpcClient.java` | 图片广告检测客户端 | **无对应文件** | — | 1:1 | ⛔ | **INTENTIONALLY_CHANGED** | E-2（**security 域不在迁移范围** ⇒ 无对端 ⇒ 不建） |
| 54 | `article-rpc/…/client/MessageRpcClient.java` | 跨域 gRPC（发通知） | `client/MessageRpcClient` | `sendMessage` | 1:1 | ✅ | PASS | E-2（**唯一跨域 gRPC 依赖**） |
| 55 | `article-rpc/…/client/SecurityRpcClient.java` | 内容安全客户端 | `client/UnavailableContentAuditClient` | 恒抛 | N:1 | 🟡 | **INTENTIONALLY_CHANGED** | E-2（**fail-loud** 语义保留） |
| 56 | `article-rpc/…/client/UnavailableContentSecurityClient.java` | 不可用兜底（**抛错不放行**） | `client/UnavailableContentAuditClient` | 恒抛 | 1:1 | ✅ | PASS | E-2（B 侧 `SecurityRpc == nil ⇒ return error` 的等价物） |
| 57 | `article-rpc/…/config/ArticleKafkaChannelValidator.java` | **4 通道 brokers 与全局不一致 ⇒ 启动失败** | `config/ArticleKafkaChannelValidator` | 启动校验 | 1:1 | ✅ | PASS | E-2 |
| 58 | `article-rpc/…/config/ArticleMetaObjectHandler.java` | `article.updated_at` 自动推进 | `config/ArticleMetaObjectHandler` | `updateFill` | 1:1 | ✅ | PASS | E-3（真 PG 上时间戳推进实测） |
| 59 | `article-rpc/…/config/ArticleRpcProperties.java` | rpc 层配置聚合 | `config/ArticleRpcProperties` | `@ConfigurationProperties` | 1:1 | ✅ | PASS | E-2 |
| 60 | `article-rpc/…/config/Jackson2ObjectMapperConfiguration.java` | Jackson Bean 配置 | `common/autoconfigure/JsonBodyConverterAutoConfiguration` | — | 1:N | ✅ | PASS | E-2（Boot 3.5 **自带** Jackson 2 自动配置 ⇒ 域内**不再需要**补 Bean） |
| 61 | `article-rpc/…/domain/ArticleEntity.java` | 文章主表实体 | `domain/entity/ArticleEntity` | Lombok 化（**展开注释齐全**） | 1:1 | ✅ | PASS | E-3（真 PG 读写） |
| 62 | `article-rpc/…/domain/ArticlePublicationEntity.java` | source 指针表实体 | `domain/entity/ArticlePublicationEntity` | 同上 | 1:1 | ✅ | PASS | E-3 |
| 63 | `article-rpc/…/domain/ArticleRevisionEntity.java` | 不可变 revision 实体 | `domain/entity/ArticleRevisionEntity` | 同上 | 1:1 | ✅ | PASS | E-3（**触发器拦 UPDATE/DELETE**） |
| 64 | `article-rpc/…/domain/ArticleDomainOutboxEntity.java` | community 事件发件箱实体 | `domain/entity/ArticleDomainOutboxEntity` | 同上 | 1:1 | ✅ | PASS | E-3 |
| 65 | `article-rpc/…/domain/ArticleSyncOutboxEntity.java` | 同步事件发件箱实体 | `domain/entity/ArticleSyncOutboxEntity` | 同上（**显式 `TableName()`**） | 1:1 | ✅ | PASS | E-3 |
| 66 | `article-rpc/…/exception/ArticleExceptions.java` | gRPC 状态异常工厂 | `exception/ArticleExceptions` | `Rpc` · `Raw` · `articleNotFoundRaw` | 1:1 | ✅ | PASS | E-2（⚠️ `Raw` ⇒ gRPC **UNKNOWN** ⇒ 业务码 **1015** 而非 5001） |
| 67 | `article-rpc/…/mapper/ArticleMapper.java` | 主表数据访问 | `mapper/ArticleMapper` | `selectAuthorView`（**3 分支 `<choose>`**）· `selectByIdForUpdate` · `softDelete` | 1:1 | ✅ | PASS | E-3（真 PG script 实测） |
| 68 | `article-rpc/…/mapper/ArticlePublicMapper.java` | 公开读数据访问 | `mapper/ArticlePublicMapper` | `countPublic` · `selectPublicPage` | 1:1 | ✅ | PASS | E-3（⚠️ **`PUBLIC_ARTICLE_PREDICATE` 含 `<>` ⇒ script 内用 `…_CDATA` 包裹**；⚠️ **禁用 `<where>` 元素**） |
| 69 | `article-rpc/…/mapper/ArticlePublicationMapper.java` | 指针表数据访问 | `mapper/ArticlePublicationMapper` | — | 1:1 | ✅ | PASS | E-3 |
| 70 | `article-rpc/…/mapper/ArticleRevisionMapper.java` | revision 表数据访问 | `mapper/ArticleRevisionMapper` | — | 1:1 | ✅ | PASS | E-3 |
| 71 | `article-rpc/…/mapper/ArticleDomainOutboxMapper.java` | 发件箱数据访问 | `mapper/ArticleDomainOutboxMapper` | — | 1:1 | ✅ | PASS | E-3（`UNIQUE(aggregate_id, aggregate_version)` 是**事件顺序保证**，不可省） |
| 72 | `article-rpc/…/mapper/ArticleSyncOutboxMapper.java` | 同步发件箱数据访问 | `mapper/ArticleSyncOutboxMapper` | — | 1:1 | ✅ | PASS | E-3（`event_key` 唯一键保留） |
| 73 | `article-rpc/…/mapper/JsonbTypeHandler.java` | jsonb ↔ List/Map | `mapper/JsonbTypeHandler` | `StringListTypeHandler` · `StringMapTypeHandler` · `readList` · `readMap` | 1:1 | ✅ | **NOT_VERIFIED**（**待收口** · 共享层类型不兼容） | E-3（真 PG jsonb 往返） |
| 74 | `article-rpc/…/metrics/ArticleRpcMetrics.java` | 指标（**`NAMESPACE = "article_service"`**） | `metrics/ArticleRpcMetrics` | 各埋点 | 1:1 | ✅ | PASS | E-3（真 registry 抓完整名） |
| 75 | `article-rpc/…/service/ArticleSpans.java` | span 名常量（从指标类拆出） | `metrics/ArticleRpcMetrics` | 内嵌常量 | 1:1 | ✅ | PASS | E-2（**并入** ⇒ C 侧无 `ArticleSpans` 类） |
| 76 | `article-rpc/…/mq/ArticleReviewConsumer.java` | 审核消费者（四元组 + 幂等 + FOR UPDATE 写 outbox） | `messaging/ArticleReviewConsumer` | `@KafkaListener` | 1:1 | ✅ | PASS | E-3（真 Kafka） |
| 77 | `article-rpc/…/mq/ArticleSyncEventTypes.java` | 事件类型常量 + 载荷 | `contract/ArticleSyncEvent` | 顶层 record + 嵌套 `ArticleReviewMessage` / `ArticleSyncResult` | 1:N | ✅ | PASS | E-2（`ArticleSyncEventContractTest` **14 例**） |
| 78 | `article-rpc/…/mq/ArticleSyncOutboxRelay.java` | 发件箱中继（定时） | `messaging/ArticleSyncOutboxRelay` | 定时拉取 + 投递 | 1:1 | ✅ | PASS | E-3 |
| 79 | `article-rpc/…/mq/ArticleSyncResultConsumer.java` | 同步结果消费者 | `messaging/ArticleSyncResultConsumer` | `@KafkaListener` | 1:1 | ✅ | PASS | E-3（真 Kafka） |
| 80 | `article-rpc/…/service/ArticleDomainService.java` | 6 个 rpc Service | `domain/service/ArticleDomainService` | `createArticle` · `getArticle` · `updateArticle` · `deleteArticle` · `listArticles` · `uploadFile` | N:1 | ✅ | PASS | E-3（真 PG） |
| 81 | `article-rpc/…/service/ArticlePublicDomainService.java` | 公开读路径 | `domain/service/ArticlePublicDomainService` | `getPublicArticle` · `listPublicArticles` | 1:1 | ✅ | PASS | E-3（⚠️ 只读事务 `REPEATABLE_READ, readOnly=true`；⚠️ **`getPublicArticle` 刻意不加事务** —— B 侧同款） |
| 82 | `article-rpc/…/service/ArticleAtomicService.java` | **多步写事务载体** | `domain/service/ArticleAtomicService` | `retractAndUpdate` · `retractAndDelete` · `queueSyncOutboxWithLock` · `acceptSyncResult` | 1:1 | ✅ | PASS | E-3（⚠️ **必须独立 Bean**，否则 `@Transactional` self-invocation 失效） |
| 83 | `article-rpc/…/service/impl/ArticleServiceGrpcImpl.java` | gRPC 6 方法 thin wrapper | `grpc/ArticleGrpcService` | 6 个 `@Override` | 1:1 | ✅ | PASS | E-2（**proto 方法签名是 L1，一个字节都不改**） |

> **承接表统计**：**83 / 83 行**，无遗漏；`✅ 已实现` **79** · `🟡 部分实现` **2**（#9 / #36，均因**无对端域**）· `⛔ 有意不建` **1**（#53）· `🟡 域内副本待收口` **3**（#23 / #29 / #73，**实现状态为 ✅ 但共享层收口状态为 NOT_VERIFIED**）。

---

## 11.3 多文件合并和拆分专项报告

### 11.3.1 所有 **N:1** 映射（B 多 → C 一）

| B 侧合并进来的文件 | C 承接 | 合并依据 |
|---|---|---|
| `ArticleApiApplication` + `ArticleRpcApplication` | `ArticleServiceApplication` | **D1**：api + rpc 合并为单 app（**两进程 → 一进程**） |
| `article-api/…/auth/`（4 类） | `ArticleJwtAuthFilter` + `JwtAuthInterceptor` | B 侧自己就拆两段 ⇒ C 侧**保留两段**（不合并成一个） |
| `article-api/…/service/`（7 个 `*Service`） | `ArticleApiService` | **D2 + 用例内聚五问判据**：7 个用例共享同一组依赖（域服务 + 身份 + 投影）⇒ 拆 7 个 Bean 反而要多注入 |
| `article-api/…/service/ArticleApiSupport` + `ArticleAuthorResolver` | `ArticleApiSupport` | 6 个 helper 共用同一组数据 ⇒ 合 1 个 support 类（与 B 侧自己的收敛口径一致） |
| `article-api/…/client/ArticleRpcClient` | `ArticleApiService`（域内直连） | **D2**：同域不再走网络 ⇒ client 文件无存在理由 |
| `article-api/…/client/UserRpcClient` | `ArticleApiSupport`（回落分支） | **无对端域** ⇒ 无 client 文件 |
| `article-rpc/…/service/ArticleDomainService`（6 个 rpc Service） | `ArticleDomainService` | B 侧 codegen 产物 ⇒ C 侧按用例收敛 |
| `article-rpc/…/service/ArticleSpans` | `ArticleRpcMetrics` | span 名常量**从指标类拆出** ⇒ C 侧并回（同一职责域） |
| `article-common/…/errmsg/ErrorCode` | `contract/ErrCodes` + `contract/ErrMsg` | 拆「码值表」与「文案表」 |
| `article-rpc/…/client/SecurityRpcClient` + `UnavailableContentSecurityClient` | `UnavailableContentAuditClient` | **无对端域** ⇒ 只保留 fail-loud 兜底 |

### 11.3.2 所有 **1:N** 映射（B 一 → C 多）

| B 侧源文件 | C 侧拆出 | 拆分依据 |
|---|---|---|
| `article-api/…/dto/GoStyleStringFields` | `web/ArticleStringFields` | **它不是 DTO**（是取值/类型判定工具）⇒ 落 `web/` 工具包（D11 的「按业务对象拆子包」精神） |
| `article-api/…/form/GoFormValues` | `web/ArticleQueryValues` | 同上（query 取值 ≠ DTO） |
| `article-rpc/…/mq/ArticleSyncEventTypes` | `contract/ArticleSyncEvent`（顶层 record + 2 个嵌套 record） | 一份文件装 3 类载荷 ⇒ 拆成「顶层事件 + 嵌套载荷」 |
| `article-api/…/config/ArticleGoStyleCoercion` | `common/web/Coercion` | **上收共享层**（D3） |
| `article-api/…/http/`（4 类） | `common/web/`（4 个同名类） | **上收共享层**（D3） |
| `article-rpc/…/config/Jackson2ObjectMapperConfiguration` | `common/autoconfigure/JsonBodyConverterAutoConfiguration` | Boot 3.5 **自带** Jackson 2 自动配置 ⇒ 域内不需要补 Bean |

### 11.3.3 多对多映射

**B 侧 `article-api/…/dto/`（13 个文件）↔ C 侧 `dto/article/`（11）+ `dto/upload/`（2）+ `web/ArticleStringFields`（1）** —— 13 → 14 的多对多（D11 的子包拆分 + 1 个「不是 DTO 的工具」移出）。

### 11.3.4 共享能力上收

| B 侧原位 | C 侧 `common/` 落点 | 本域消费点 |
|---|---|---|
| `article-api/…/http/`（4 类） | `common/web/{BadRequestException,GrpcStatusMapper,HttpErrorException,PlainTextErrors}` | 6 个文件 |
| `article-api/…/config/ArticleGoStyleCoercion` | `common/web/Coercion` | `config/WebMvcConfig` |
| `article-api/…/config/GoStyleJsonBodyLimitFilter` | `common/web/JsonBodyLimitFilter` | 自动装配（零直接引用） |
| B 侧 `common/http/GoStyleRouterErrorResponses` | `common/web/RouterErrorResponses` | `advice/ArticleApiExceptionHandler` |
| `article-rpc/…/config/Jackson2ObjectMapperConfiguration` | `common/autoconfigure/JsonBodyConverterAutoConfiguration` | 零直接引用 |

> ⚠️ **反向账单（3 份域内副本）** ⇒ 见 [`vs-common.md`](vs-common.md) §2.1（`StringFields` 包私有 · `GetFormValuesFilter` 无取值真值表 · `JsonbTypeHandler` 类型不兼容）⇒ **待收口**。

### 11.3.5 Spring 接管

| B 侧机制 | C 侧接管方式 | 行为等价性 |
|---|---|---|
| 代码注册路由（`server.AddRoutes(...)`） | 注解式路由（`@RequestMapping` / `@*Mapping`） | ✅ 路径 + method + 4 组分组逐条一致 |
| 域内 codegen 的 7 个 api Service | **1 个** `ArticleApiService` | ✅ 逐用例对照（§11.2 #37~#43） |
| 域内 codegen 的 6 个 rpc Service | **1 个** `ArticleDomainService` + 2 个专用 | ✅ 逐用例对照 |
| 手工 `@Import` 清单 | **`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**（D3 starter 化） | ✅ 守门 **A5 PASS**（域内 **0 个** `@Import`） |
| B 侧可变 struct DTO | **immutable record**（D5） | ✅ 守门 **A9 PASS**（138/138 DTO immutable） |
| 手写必填校验 | **Bean Validation `@NotNull`**（A10） | ⚠️ **文案有差**（见 §11.5 · D-05） |
| 手写 jsonb TypeHandler | MyBatis-Plus `BaseTypeHandler` | ✅ 域内副本（结构与 B 侧同构） |

### 11.3.6 独立进程合并

| 项 | B 侧 | C 侧 | 连带后果 |
|---|---|---|---|
| 进程数 | **2**（`article-api` :8889 ＋ `article-rpc` :9001） | **1**（`article-service`，HTTP + gRPC 同进程） | ① 同域调用**不再跨网络** ⇒ B 侧的 stub deadline **语义位无处可落**（**拓扑产物，不是丢失**）② 异常处理**必须分两套**（`advice` vs `exception` + `GrpcExceptionMapperAutoConfiguration`）③ 端口治理：**注册到 Nacos 的是 gRPC 端口**，不是 web 端口 |

### 11.3.7 职责缺失

**C 侧无缺失**：B 侧 **83 / 83** 全部有承接点（§11.2 逐行可查）。
⇒ 但有 **3 处「实现存在、共享层收口未完成」**（`web/ArticleStringFields` · `web/ArticleQueryValues` · `mapper/JsonbTypeHandler`）⇒ 属**跨 lane 账单**，不是本域漏抄。

---

## 11.4 框架版本差异报告

| # | B 使用的机制和版本 | C 使用的机制和版本 | 具体变化 | 影响的执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 最终结论 |
|---|---|---|---|---|---|---|---|---|
| **F-1** | **Spring Boot 4.0.8** / Spring Framework **7.0.9** | **Spring Boot 3.5.11** / Spring Framework **6.2.x** | Servlet / WebMVC API 面、`PathPattern` 等 | 全部 HTTP 请求路径 | ⚠️ **潜在** | 逐处编译期核对 API 面 | **实测：Spring 6.2.16 与 7.0.9 的 `PathPattern.matches` 签名完全一致**（都只收 `PathContainer`；`matches(String)` 在 Spring 6.0 已移除）⇒ **B 侧代码零回改** | ✅ **无行为影响** |
| **F-2** | Boot 4 的 **Jackson 3** | **Jackson 2.19.4**（D14：全项目只 Jackson 2） | 包名 `tools.jackson.*` → `com.fasterxml.jackson.*`；注解/行为细节 | JSON 序列化 / 反序列化 / 校验 | ⚠️ **可能** | 全部 DTO **显式 `@JsonProperty`（snake_case）** + **`@JsonPropertyOrder` 按 B 侧 Go struct 声明序** | DTO 运行期绑定实测（key 名 / 顺序 / `omitempty` 语义 / 类型不符文案） | ✅ **无行为影响**（**逐字段实测**） |
| **F-3** | Boot 4 专有 artifact（`spring-boot-kafka` / `spring-boot-flyway` / `spring-boot-data-redis` / `spring-boot-tomcat`） | Boot 3.5 的对应物（`org.springframework.kafka:spring-kafka` 等） | 依赖坐标不同 | 容器装配 | ✅ **否** | 按 D14 换坐标 ⇒ **零排除项** | `mvn -o -pl service/article -am compile` = BUILD SUCCESS；容器能起 | ✅ **无行为影响** |
| **F-4** | B 侧 **双进程**（api / rpc 分离） | C 侧 **单进程**（D1） | 同域 gRPC 调用消失；两个端口同进程 | 创建 / 读 / 更新 / 列表 / 上传 全链路 | ⚠️ **形态变** | D2 域内直连；**B 侧的「gRPC code → 业务码」映射仍在 API 层**（领域层抛 `ArticleExceptions.Rpc`/`Raw` 而非 `StatusRuntimeException`）⇒ C 侧用 `statusCodeOf()` **逐条复刻** `grpc/ArticleGrpcService` 的三条 catch 分支 | 错误码面真触发测试 | ✅ **行为等价**（**错误码逐条相同**） |
| **F-5** | B 侧 `ArticleServiceGrpcImpl` 抛 `StatusRuntimeException` | C 侧领域层抛 `ArticleExceptions.Rpc` / `Raw` | 异常类型不同 ⇒ 映射点不同 | 错误响应面 | ⚠️ **可能** | `Rpc` ⇒ 取其 `Status.Code`；`StatusRuntimeException` ⇒ **原样透传**；`Raw` / 其它 ⇒ **UNKNOWN** | `ArticleExceptions` 契约测试 | ✅ **行为等价**（⚠️ **`Raw` ⇒ UNKNOWN ⇒ 业务码 1015 而非 5001**） |
| **F-6** | B 侧 `GetFormValues` 自己的取值真值表 | C 侧共享层 `GetFormValuesFilter` **只有上限闸门** | 能力缺口 | query 参数解析（`page`/`page_size`/`sort_by`/`desc` + 4 个筛选） | ⚠️ **会** | 域内副本 `web/ArticleQueryValues`（**待收口**） | 真 Tomcat 查询契约测试 | ✅ **行为等价**（副本逐条照抄 B 侧 5 条真值表） |
| **F-7** | B 侧 `GoStyleStringFields`（域内可见） | C 侧共享层 `StringFields` **包私有** | 可见性 | JSON 类型不符文案 | ⚠️ **会** | 域内副本 `web/ArticleStringFields`（**待收口**） | 真 Tomcat 400 文案断言 | ✅ **行为等价** |
| **F-8** | B 侧 `article-rpc/.../mapper/JsonbTypeHandler`（jsonb↔List/Map） | C 侧共享层 `JsonbTypeHandler` 是 **jsonb↔String** | **类型不兼容** | `secondary_tags` / `ext_info` 持久化 | ⚠️ **会** | 域内副本 `mapper/JsonbTypeHandler`（结构与 B 侧同构） | 真 PG jsonb 往返 | ✅ **行为等价** |
| **F-9** | B 侧 `article-api` 走 go-zero 的 `rest` 链（`WithCors()`） | C 侧 Spring MVC + 共享层 filter 链 | 框架级错误处理不同 | 解析失败 / 方法不匹配 / 上传失败 | ⚠️ **会** | 逐条复刻：解析失败 = **400 + text/plain + nosniff**（**不是 200+5001**）· 方法不匹配 = **404 空 body**（**不是 405**） | 真 Tomcat 契约测试 | ✅ **行为等价** |
| **F-10** | B 侧 Flyway 账本表名（**schema 级**） | C 侧 `flyway_schema_history_article` + `baseline-on-migrate: true` + **`baseline-version: 0`** | 账本隔离 | 启动建表 | ⚠️ **会**（若沿用默认表名 ⇒ **共用 schema 时任一 app 先启动，其他 app 的表永远建不出来**） | 按 AGENTS.md §六 的**各域独立账本表名**口径 | 真 PG 上 Flyway 实跑（V8/V9/V10 建成） | ✅ **无行为影响** |
| **F-11** | B 侧 `@EnabledIfDockerAvailable`（**10 个测试类**带） | C 侧**一律不抄** | 测试可选化 | CI 上「静默跳过」 | ⚠️ **会**（跳过 = 假绿） | **禁 `@EnabledIfDockerAvailable`**（AGENTS.md §五.8 硬规则 ③）⇒ Docker 不可用时**直接失败** | 真 PG 容器实测（全部真跑） | ✅ **无行为影响**（**且更严**） |
| **F-12** | **配置键名（L1）**：B 侧 `article-api/application.yml`（207 键）＋ `article-rpc/application.yml` 合并为 C 侧 `application.yml`（180 键） | 同左 | **37 个键 B 有、C 无** —— 逐个判定见下方 F-12 附表 | 启动装配 / 超时预算 / 指标导出 / OTel / Nacos 认证 | ⚠️ **逐个判定** | 见 F-12 附表 | **键名比对脚本**（B 两份 yaml ∪ vs C 一份）+ **逐条读消费点** | ✅ **零漂移**（37/37 全部解释清楚） |
| **F-13** | Spring Framework **7.0.10**（B 侧 · Boot 4.0.8）的 `DefaultCorsProcessor.processRequest`：`config == null` 时**提前 return**（`:75-80`）⇒ **不写任何 `Vary`** | Spring Framework **6.2.16**（C 侧 · Boot 3.5.11）的同方法：**先无条件写 3 条 `Vary`**（`:77` / `:80` / `:83`），`config == null` 的守卫在**其后**（`:103`） | 静态资源处理器（`ResourceHttpRequestHandler`，**实现了 `CorsConfigurationSource`**）命中 ⇒ `AbstractHandlerMapping.hasCorsConfigurationSource` 为真 ⇒ 响应链**必然**挂 `CorsInterceptor` ⇒ 404 路径的 `Vary` 条数不同 | **仅「未注册路径」的 404 响应头**（多 2 条 `Vary`）<br>⇒ 200 / 400 / 401 命中的是 `HandlerMethod` ⇒ **不受影响** | 🟢 **否**（`Vary` 多列一个**不参与协商**的请求头 ⇒ 按 RFC 9110 §12.5.3 只可能让缓存**更保守**）<br>⚠️ **字节面确实不同** | **不修**（换版本会推翻 D14；应用层替换 `CorsProcessor` 要动全局 handler mapping，而 C 侧的 CORS 本就完全由域内 `ArticleCorsFilter` 承担 ⇒ `DefaultCorsProcessor` 除了这 2 条冗余 `Vary` 之外无作用）⇒ **登记为签字偏离 D-15** | ✅ **真 Tomcat + 裸 socket 字节面**（`http/ArticleHttpEndpointHeaderPlaneRealTomcatTest` · **2/2 绿**）⇒ 404 的 `Vary` **3 条**被逐条钉死<br>⇒ ⚠️ **B 侧没有现成断言可引**（B 侧 `like-api/LikeApiRouterErrorRealTomcatTest:130-137` 只断言 404 的 body / Content-Type / nosniff）⇒ ⇒ 本条**不声称**已做 B 侧活体对拍，**只声称**「两侧代码路径同构 ＋ 唯一变量是 Spring 版本 ＋ 该版本源码逐行可查」 | 🟡 **语义等价 · 字节面有差** ⇒ **D-15 已登记待签** |

### 11.4.1 F-12 附表 —— 配置键名差异的逐条判定（37/37）

> 📌 **口径**：B 侧 `article-api` + `article-rpc` 两份 yaml 的键路径取**并集**（**B 侧 api 与 rpc 分离 ⇒ 两份都有**）⇒ 与 C 侧合并后的**一份** yaml 比对。
> ⚠️ **「B 有、C 无」≠「漂移」** —— AGENTS.md §3.3.1 的两面都成立：**「配置里没写」≠「没开」**，**「配置里写了」≠「开了」**。
> ⇒ 下表**逐条给出判定与依据**，**零条悬空**。

| B 有、C 无的键（归并后 9 组） | 判定 | 依据 |
|---|---|---|
| `grpc.client.article-rpc.*`（6 键） | ✅ **正确 —— D2 域内直连** | 同域调用不再走网络 ⇒ 该 client 无存在理由 |
| `grpc.client.user-rpc.*`（6 键）· `grpc.client.security-rpc.*`（4 键） | ✅ **正确 —— 无对端域** | `users` / `security` 域**不在本仓迁移范围**（AGENTS.md §1.0）⇒ 配一条指向不存在服务的 channel 只会凭空多一个「必须能解析」的启动前置条件 |
| `grpc.client.message-rpc.deadline` | ✅ **正确 —— B 侧自记「死配置」** | 🔴 B 侧 `article-rpc/application.yml:266-273` **自记**：devh **没有 per-client deadline 旋钮** ⇒ 「下面两个 `deadline:` 键在本仓是**死配置**」⇒ ⇒ **别把「yml 里写了 deadline」读成「出向调用有了 deadline」**（AGENTS.md §3.3.1）⇒ ⇒ 真正生效的上界 = `rag.protection.outbound-deadline.cap-millis` ⇒ **C 侧该键在位（`cap-millis: 2000`）** |
| `rag.protection.outbound-deadline.cap-millis-by-service.{article.ArticleService, user.UserService}` | ✅ **正确 —— 无对端 client** | 同上：无 outbound client ⇒ 无 per-service 上界 ⇒ 真正生效的仍是**模块级** `cap-millis` |
| `spring.jpa.{hibernate.ddl-auto, show-sql}` | ✅ **正确 —— 本仓不用 JPA** | AGENTS.md §4.1「ORM **严格**：JPA → MyBatis-Plus」⇒ JPA 配置是 B 侧遗留 |
| `logging.level.com.rag.article.article.api` · `logging.level.com.rag.common` | ✅ **正确 —— 包名变更** | B 侧包名 `com.rag.article.article.api` / `com.rag.common` ⇒ C 侧压平为 `com.rag.article` / `com.rag.common` ⇒ C 侧用 `logging.level.com.rag: INFO` 覆盖 |
| `management.prometheus.metrics.export.enabled` | ✅ **框架版本差异（Boot 4 → 3.5 键名）** | Boot 3.5 的键名是 `management.endpoint.prometheus.enabled` + `management.metrics.enable.prometheus` ⇒ **C 侧两个都在** ⇒ 抓取路径仍可用 |
| `otel.service.name` · `otel.traces.exporter` | ✅ **行为等价（共享层两键都读）** | 共享层 `common/…/observability/GoStyleOtelConfig.java:100-104`：`KEY_SERVICE_NAME = "otel.service.name"` **优先**，回落 `KEY_RESOURCE_SERVICE_NAME = "otel.resource.attributes.service.name"`；`KEY_SAMPLER = "otel.traces.sampler"` ⇒ C 侧 yml 写的是**回落键** ⇒ **照样被读到** |
| `rag.side: rpc` | ✅ **正确 —— D1 落地差异** | B 侧 `article-rpc` 是**独立进程** ⇒ 必须 `rag.side=rpc` 以免挂上 api 侧 web bean；C 侧 article 是 **api+rpc 合并进程** ⇒ **本就该挂** api 侧 web bean ⇒ 共享层**已刻意去掉** `@ConditionalOnApiSide`（`ValueLimitInterceptor.java:76-77` / `JsonContentTypeAdvice.java:106-107` / `TraceparentFilter.java:200-201` 三处 javadoc 均自记该 D1 差异）⇒ 改用 `@ConditionalOnWebApplication(type = SERVLET)` ⇒ **合并进程正是 Servlet web 应用 ⇒ bean 照挂** ✓ |

---

## 11.5 语义漂移清单

> **口径**（Drift ID 与 B 侧缺陷 ID **两套编号，不要混**）：
> - **D-01 ~ D-04 ＋ D-13 = 已修**（本轮改的；其中 **D-13 = CORS 能力面缺失**，是补出来的**真实职责缺失**，不是记账错误）。
> - **D-05 ~ D-12 ＋ D-14 ＋ D-15 = 已登记的签字偏离 / 待收口**（不在本轮改；共 **10** 条）。
> - **B-01 ~ B-10 = B 侧既有缺陷**（照抄不修，登记备查；**编号刻意与 D 系列分开** ⇒ 避免「D-13 既在已修表又在 B 侧缺陷表」这种歧义）。

### 11.5.1 本轮已修（5 条）

| Drift ID | 问题位置 | B 文件和方法 | C 文件和方法 | B 行为 | C 行为（修后） | 差异原因 | 影响范围 | 严重度 | 修复建议 | 验证方法 | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **D-01** | `service/article/pom.xml` | —（B 侧 pom 无此问题） | `service/article/pom.xml` `grpc-inprocess` | B 侧 compile scope | C 侧原为 `test` scope ⇒ **运行期 classpath 消失** ⇒ `NoClassDefFoundError: InProcessChannelBuilder` ⇒ **服务起不来** | 抄 pom 时误加 scope | **全进程起不来** | 🔴 P0 | 改 compile scope（已抄 follow 域 30 行注释） | 真启动装配测试 | ✅ **已修** |
| **D-02** | `service/article/pom.xml` | — | `service/article/pom.xml` `postgresql` | B 侧 compile（默认） | C 侧原为 `runtime` scope | 同上 | 驱动不可用面 | 🟡 P1 | 去掉 `<scope>runtime</scope>` | 编译 + 真 PG 连通 | ✅ **已修** |
| **D-03** | `ArticleServiceApplication` + 2 个 javadoc | — | `META-INF/spring/…AutoConfiguration.imports` | B 侧手工 `@Import` 清单 | C 侧域内残留 `@Import` ⇒ **守门 A5 红** | 未按 D3 starter 化 | **守门红 ⇒ 不得合入** | 🔴 P0 | ① 新建域内 `.imports` 文件（含 `com.rag.article.client.ContentAuditAutoConfiguration`）② 删 `@Import` ③ **javadoc 里的 `@Import(...)` 字面量也要清**（⚠️ **A5 判据是源码文本级扫描，连 javadoc 都匹配** ⇒ 安全写法是 `{@code @Import}`，**不能**写 `{@code @Import(X.class)}`） | `check-arch-invariants.py` A5 = PASS（**0 个** `@Import`） | ✅ **已修** |
| **D-04** | `ArticleMapper.selectAuthorView` + `selectByIdForUpdate` + `softDelete` | `article-rpc/…/mapper/ArticleMapper.java` | `mapper/ArticleMapper.java` | 排序 **3 分支** `<choose>`；`selectByIdForUpdate` / `softDelete` **存在** | C 侧原为单分支 + 缺 2 个方法 | 抄写时简化 | 列表排序 / 行锁 / 软删 | 🔴 P0 | 逐条补齐 | 真 PG script 实测（`ArticleMapperScriptRealPgTest` / `ArticleMapperSortContractTest`） | ✅ **已修** |
| **D-13** | **CORS 能力面缺失** —— C 侧 article 域**零 CORS 实现** | `article-api/…/config/ArticleGoStyleCorsFilter.java:77-146`（`@Order(HIGHEST_PRECEDENCE + 1)`）· Go `rest/internal/cors/handlers.go:10-27,61-66` | `config/ArticleCorsFilter.java`（**本轮新建**） | ① 响应头带 `Access-Control-Allow-Origin: *` 等 **7 个头** + `Vary`（OPTIONS 时 3 条）<br>② **`OPTIONS` 任意路径（含未注册）⇒ 204 空 body**，且**不进业务链**（短路）<br>③ CORS filter 包在 router **外面** ⇒ 连 **404** 都带 CORS 头 | ① **响应头无** `Access-Control-*`<br>② `OPTIONS` ⇒ **404**（不是 204）<br>③ 预检会被鉴权拦 | **白名单前提不成立** —— 映射表原写「C 侧由 `common/web/` 承担」⇒ **实测 `common/web/` 无 CORS 类**（对 `common/src/main/java` 递归搜 `cors` 与 `Access-Control` ⇒ **0 命中**）⇒ ⇒ 那条「已覆盖」是**假 PASS** ⇒ 属**真实职责缺失 + 行为漂移** | 浏览器跨域调用不可用；`PUT`/`DELETE` 前置预检直接失败 | 🔴 P0 | **域内新建** `config/ArticleCorsFilter`（**刻意不上收**：message/like/hot 三域**明确不要**这个响应头 ⇒ 上收就得给所有 api 域加开关）<br>⚠️ **必须用 Servlet `Filter`、不能用 `addCorsMappings`**（Go 的 `corsRouter` 包在 router 外面 ⇒ 未注册路径也要有 CORS 头）<br>⚠️ `@Order(+1)` ⇒ 排在共享层 `TraceparentFilter`(+3) **之前** ⇒ 204 预检**不带** traceparent（与 Go 一致）<br>⚠️ **刻意不写** `Access-Control-Allow-Credentials`（Go 的 `"*"` 分支条件写入 ⇒ 不写）<br>⚠️ 与本域「方法不匹配 ⇒ 404 空 body」规则**共存**：本 filter 只短路 `OPTIONS`，其它方法照常下行 | **真 Tomcat**（`ArticleHttpCorsRealTomcatTest` **7 例**）：3 条路径 OPTIONS 全 204 + 空 body + `Allow-Origin: *` · 无 token 仍 204（**短路在鉴权之前**）· 5 个头取值逐字 · `Allow-Credentials` 缺席 · `Vary` 1 条 vs 3 条 · 非 OPTIONS 方法不匹配仍 404 空 body **且带 CORS 头** | ✅ **已修** |

### 11.5.2 已登记的签字偏离 / 待收口（10 条）

| Drift ID | 问题位置 | B 文件和方法 | C 文件和方法 | B 行为 | C 行为 | 差异原因 | 影响范围 | 严重度 | 修复建议 | 验证方法 | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **D-05** | 必填字段文案 | `users-common/…/GoRequiredFieldException.java:22`（`field "x" is not set`）/ `:28`（`field "x" mustn't be nil`） | `dto/article/CreateArticleReq` `@NotNull(message=…)` | 「缺席」⇒ `is not set`；「显式 null」⇒ `mustn't be nil`（**两种文案**） | **折叠为一种**（`is not set`） | **A10 禁手写校验器** ⇒ 只能用 Bean Validation；且 record + Jackson 绑定下「缺席」与「显式 null」**都落成 `null`** ⇒ 无法区分 | 创建接口的 400 文案 | 🟡 P1 | **不可修**（record 的 presence 语义限制）⇒ 登记签字偏离 | 真 Tomcat 400 文案断言（body 逐字节） | **INTENTIONALLY_CHANGED** |
| **D-06** | 内容审核链路 | `article-rpc/…/mq/ArticleReviewConsumer.java`（`SecurityRpc == nil ⇒ return error`） | `client/UnavailableContentAuditClient` + `ContentAuditPort` + `ContentAuditAutoConfiguration` | 有 security 域对端 ⇒ 真正审核 | **恒 fail-loud**（审核链路在 RAG-V1 **恒不可用**） | **security 域不在迁移范围**（AGENTS.md §1.0）⇒ 无对端 | 审核四元组里的 2 项检查 | 🟡 P1 | 无对端 ⇒ 不可修 ⇒ 登记签字偏离（`docs/Intentional_Deviations/article/content-audit-fail-loud.md`） | 消费者真触发（恒抛 ⇒ 不落 outbox） | **INTENTIONALLY_CHANGED** |
| **D-07** | 作者昵称补全 | `article-api/…/client/UserRpcClient.java` + `service/ArticleAuthorResolver.java` | `service/ArticleApiSupport.enrichArticleAuthor` / `fallbackAuthorName` | 跨域取真实昵称 | **恒回落**（`"用户 <id>"` / `"未知作者"`） | **users 域不在本仓** ⇒ 无对端 | 列表 / 详情的 `author_name` 值 | 🟡 P1 | 无对端 ⇒ 不可修<br>⚠️ **刻意不选「干脆不输出 `author_name`」** —— 那样**键存在性**也变了，**差异面更大** | 真 PG 列表断言（`author_name` 键在、值为回落值） | **INTENTIONALLY_CHANGED** |
| **D-08** | 图片广告检测 | `article-rpc/…/client/ImageSecurityRpcClient.java` | **无对应文件** | 跨域调用 | **不建** | 同 D-06 | 审核四元组 | 🟡 P1 | 同 D-06 | 同 D-06 | **INTENTIONALLY_CHANGED** |
| **D-09** | `common/web/StringFields` 可见性 | `article-api/…/dto/GoStyleStringFields.java` | `web/ArticleStringFields.java`（域内副本） | 域内可见 | 共享层那份**包私有** ⇒ 域内副本 | 共享层可见性不足 | JSON 类型判定 | 🟡 P2 | **收口**：共享层提升为 `public` ⇒ 本域删副本<br>⚠️ **两份不得分叉** | 真 Tomcat 类型不符文案断言 | **NOT_VERIFIED**（待收口） |
| **D-10** | `common/web/GetFormValuesFilter` 能力 | `article-api/…/form/GoFormValues.java` | `web/ArticleQueryValues.java`（域内副本） | 5 条取值真值表 | 共享层**只有上限闸门** | 共享层缺能力 | query 解析 | 🟡 P2 | **收口**：共享层补 `FormValues` | 真 Tomcat query 契约测试 | **NOT_VERIFIED**（待收口） |
| **D-11** | jsonb TypeHandler | `article-rpc/…/mapper/JsonbTypeHandler.java` | `mapper/JsonbTypeHandler.java`（域内副本） | jsonb↔List / Map | 共享层是 **jsonb↔String** ⇒ **类型不兼容** | 共享层类型不匹配 | `secondary_tags` / `ext_info` 持久化 | 🟡 P2 | **收口**：新建 `JsonbListTypeHandler` / `JsonbMapTypeHandler`（**建议与 message 域同一次收口**） | 真 PG jsonb 往返 | **NOT_VERIFIED**（待收口） |
| **D-12** | 上传 DTO 形态 | —（B 侧**无** `UploadImageReq`） | `dto/upload/UploadImageReq.java` | 直接用 `MultipartFile` | 新增一个 record 承载字段名与内容 | C 侧 DTO 层统一 record 化（D5） | 无（**multipart 字段名 `image` 逐字未改**） | 🟢 P3 | 无需修（结构性新增，javadoc 已留痕） | 真 Tomcat 上传契约测试 | **INTENTIONALLY_CHANGED** |
| **D-14** | **Nacos `namespace` 默认值**（L1 契约层 · **跨域**） | `service/article/article-rpc/src/main/resources/application.yml:125` = `${NACOS_NAMESPACE:public}`<br>（`article-api/…/application.yml:33` 同款） | `service/article/src/main/resources/application.yml:133` = `${NACOS_NAMESPACE:sea-rtw}` | 默认注册到 / 查询 **`public`** | 默认注册到 / 查询 **`sea-rtw`** | **两侧的部署约定本来就不统一** ⇒ C 侧抄的是「另一套默认值」 | 跨域 gRPC 对端解析（`grpc.client.message-rpc.address = discovery:///message-rpc`，`application.yml:199`） | 🟡 P1 | **归 `global` 收口域**（AGENTS.md §五.3 规则 3）—— **本域只登记，不改码**：<br>① **C 侧内部自洽**：C 侧 `message` 域也是 `sea-rtw` ⇒ article ↔ message 默认**能**互相解析 ⇒ **单改 article 会反过来弄断它与 C 侧 message 的连接**；<br>② **B 侧也不统一**：B 侧 **users 域本来就是 `sea-rtw`**（`users/admin/admin-api/…:55` · `admin-rpc/…:95` · `users/user/user-api/…:52`），其余 8 个域是 `public` ⇒ ⇒ **两侧各有一套混用**，**全仓统一**才能收口；<br>③ **C 侧 `message` 域有同一个漂移**（C=`sea-rtw` vs B=`public`）⇒ ⇒ 证明这**不是 article 单域问题** | ✅ **U-2 真 Nacos + 真 message-rpc 实测**（`grpc/ArticleToMessageRpcCrossDomainTest` · 3/3 绿）⇒ **显式设 `namespace=public` 后跨域链路通**<br>⇒ ⚠️ 该实测同时**反证**了默认 `sea-rtw` 下解析不到 `public` 注册的对端 | **NOT_VERIFIED**（跨域待收口） |
| **D-15** | **未注册路径（404）的响应头多 2 条 `Vary`**（**框架限制** · **跨域**） | `service/article/article-api/src/main/java/…/advice/GlobalExceptionHandler.java:280-283`（`@ExceptionHandler(NoResourceFoundException.class)` → `GoStyleRouterErrorResponses.notFound`）<br>＋ `article-api/…/config/ArticleGoStyleCorsFilter.java:125-131`（`setVaryHeaders`：**无条件**写 1 条 `Vary: Origin`） | `service/article/src/main/java/…/advice/ArticleApiExceptionHandler.java:324-327`（**同构**）<br>＋ `service/article/src/main/java/…/config/ArticleCorsFilter.java:156-162`（**逐字同形**） | 404 响应 **`Vary` 只有 1 条**（`Origin`） | 404 响应 **`Vary` 有 3 条**（`Origin` ＋ `Access-Control-Request-Method` ＋ `Access-Control-Request-Headers`） | 🔴 **唯一变量 = Spring 版本** —— Spring **`6.2.16`**（C 侧 · Boot 3.5.11）的 `DefaultCorsProcessor.processRequest` 在 **`:72` 入口**后**先无条件写 3 条 Vary**（`:77` / `:80` / `:83`），`config == null` 的守卫在 **`:103`**（**写完之后**）；Spring **`7.0.10`**（B 侧 · Boot 4.0.8）把同一守卫提前到 **`:75-80`** ⇒ **`config == null` 时直接 return，一条 Vary 都不写**<br>⇒ 触发路径两侧同构：404 由 **静态资源处理器**（`ResourceHttpRequestHandler`，**实现了 `CorsConfigurationSource`** —— 6.2.16 `:97` / 7.0.10 `:98`）命中 ⇒ `AbstractHandlerMapping.hasCorsConfigurationSource` 为真（`:645-650` / `:712-717`）⇒ 响应链**必然**挂 `CorsInterceptor`<br>⇒ ⚠️ **200 / 400 / 401 命中的是 `HandlerMethod`（不是 `CorsConfigurationSource`）⇒ 这三种形态的 `Vary` 两侧都是 1 条** ⇒ **只有 404 多** | **仅未注册路径的 404 响应头**多 2 个 `Vary`<br>⇒ **语义无害**（`Vary` 多列一个不参与协商的请求头 ⇒ 按 RFC 9110 §12.5.3 只可能让缓存**更保守**）<br>⇒ **L1 契约层零影响**（状态码 / body `"404 page not found\n"` 19 字节 / Content-Type / nosniff **两侧逐字节相同**） | 🟢 P3 | **登记为签字偏离，不改码**（`docs/Intentional_Deviations/article/http-404-extra-vary-headers.md` · `DEV-ARTICLE-HTTP-002`）—— 理由：① **换版本会推翻 D14**（工具链 = Jackson 2 + Boot 3.5 已拍板）② **在应用层替换 `CorsProcessor` 要动全局 handler mapping** ⇒ 覆盖整个 app 的预检路径，而 C 侧的 CORS 本来就**完全由域内 `ArticleCorsFilter` 承担**（OPTIONS 直接 204 短路）⇒ `DefaultCorsProcessor` 除了这 2 条冗余 `Vary` 之外**无任何作用** ⇒ 收益远小于风险 ③ **改路由语义**（把 `ResourceHttpRequestHandler` 排出 handler mapping）属 L2 行为改写 ⇒ 需另走决策流程<br>⚠️ **同一形态会出现在其它域** ⇒ **归 `global` 收口域**（AGENTS.md §五.3 规则 3）—— **本域只登记，不改码** | ✅ **U-3 真 Tomcat + 裸 socket 字节面**（`http/ArticleHttpEndpointHeaderPlaneRealTomcatTest` · **2/2 绿**）⇒ 7 个端点各自的成功形态名单 **逐条**钉死 ＋ 200/400/401/404 四种形态各自名单钉死<br>⇒ ⚠️ **B 侧没有现成断言可引**（B 侧 `like-api/LikeApiRouterErrorRealTomcatTest:130-137` 只断言 404 的 body / Content-Type / nosniff，**不断言 `Vary` 条数**）⇒ ⇒ 按 §3.3.3「凡写『与 B 侧一致』必须有两侧同时跑的探针」，本条**不声称**已做 B 侧活体对拍，**只声称**「两侧**代码路径同构** ＋ **唯一变量是 Spring 版本** ＋ **该版本源码逐行可查**」 | 🏷 **INTENTIONALLY_CHANGED**（框架限制 · 已登记待签） |

### 11.5.3 B 侧既有缺陷（照抄不修 · 登记备查 · **10** 条）

> ⚠️ **这 10 条不是 C 侧引入的** —— B 侧源码里本来就这样 ⇒ **照抄**（D13：L2 以 B 侧为基准）⇒ **不回溯修正**（D16：B 侧自身就是基准）⇒ 登记备查，供将来**两侧一起**修。

| # | 缺陷 | B 侧位置 | C 侧对应（照抄） |
|---|---|---|---|
| B-01 | `ArticleStatus.forNumber()` 未知值返 `null` | `article-rpc/…/domain/ArticleEntity.java`（枚举解析） | `domain/entity/ArticleEntity.java` |
| B-02 | `getPublicArticle` 读方法含 UPDATE 且**刻意不加事务** | `article-rpc/…/service/ArticlePublicDomainService.java` | `domain/service/ArticlePublicDomainService.java` |
| B-03 | 作者视角 `markdown_content` 装的是 **MinIO 对象 key** | `article-rpc/…/service/ArticleDomainService.java` | `domain/service/ArticleDomainService.java` |
| B-04 | `retractAndUpdate` **锁序与 Go 相反** | `article-rpc/…/service/ArticleAtomicService.java` | `domain/service/ArticleAtomicService.java` |
| B-05 | `readLegacyMarkdown` 的 `articleId` **未使用** | `article-rpc/…/service/ArticleDomainService.java` | `domain/service/ArticleDomainService.java` |
| B-06 | `buildPublicFileURL` 的**尾斜杠处理**（⚠️ **措辞已按实测收敛**，2026-11-03） | `article-rpc/…/client/ArticleObjectStorageClient.java:270-280` | `client/ArticleObjectStorageClient.java`（**代码逐字节一致**） |
| B-07 | `KafkaConf.brokers` **无消费点** | `article-rpc/…/config/ArticleRpcProperties.java` | `config/ArticleRpcProperties.java` |
| B-08 | `normalize` **不 trim** | `article-rpc/…/mq/ArticleSyncEventTypes.java` | `contract/ArticleSyncEvent.java` |
| B-09 | outbox 超 `maxRetry` **静默搁置** | `article-rpc/…/mq/ArticleSyncOutboxRelay.java` | `messaging/ArticleSyncOutboxRelay.java` |
| B-10 | **JWT Filter 的 `@Order(+6)` 与 `CpuSheddingFilter`(+6) 同序（并列）** —— ⇒ 二者的相对顺序**不由 `@Order` 保证** | B 侧 `article-api/…/auth/GoStyleJwtAuthFilter.java:101`（`@Order(Ordered.HIGHEST_PRECEDENCE + 6)`）＋ B 侧 `service/common/…/protection/GoStyleCpuSheddingFilter.java:67`（同为 `+6`） | `interceptor/ArticleJwtAuthFilter.java:136`（同为 `+6`）<br>⇒ ✅ **行为等价**：本 filter **绝不写响应**（失败时把异常对象**原样重抛**给下游）⇒ **无论谁先谁后，响应结果完全相同**；且真正的不变式「验签在 70s 预算**之外**」只要求 order **< +7**（`HttpMiddleware`）⇒ `+6` 满足<br>⚠️ C 侧 javadoc 已**显式记录**该并列与安全性论证（`ArticleJwtAuthFilter.java:95-98`） |

> 📌 **另两条 B 侧「不完整」也照抄**：`MetaObjectHandler` 用**无条件覆盖**而非 `strictUpdateFill` · `article_status_total` **只有 `reviewing` 有调用点**（**不许补全**）。
>
> 📌 **B-06 的精确语义（2026-11-03 真 MinIO 实测后收敛，行为层两侧零差异）**：
> `buildPublicFileURL` 的三段拼装里，**base 段**去尾斜杠（`baseURL.replaceAll("/+$","")`，作用在**拼好的 baseURL** 上 ⇒ 含 `public-base-url` 为空时回落出的 `scheme://endpoint`）· **bucket 段**两侧都去（`trimSlashes`）· **object 段只去左侧**（`trimLeadingSlashes`）⇒ ⇒ **对象名的尾斜杠会保留**。
> ⚠️ **B 侧自己那段 javadoc 与它自己的代码矛盾**（写「Endpoint 不去尾斜杠」「Go 产出双斜杠」，而代码明明去掉了）⇒ C 侧**代码逐字节照抄**（行为零差异），**但注释已改成与代码相符**（否则下一个人会被注释误导）⇒ 登记为**文档层**出入，不计入行为漂移。

---

## 11.6 完成度统计

> **口径**（AGENTS.md §五.8 硬规则 ①）：计数**只取回归日志的模块级汇总**，**不取** `surefire-reports/*.xml` 求和（孤儿报告虚高）。
> ⚠️ **不得用文件数量或代码行数代表迁移完成度**（`.agents/verification.md` §11.6）。

### 11.6.1 承接表状态分布（**83 行逐行重数**，非手数）

| 状态 | 行数 | 说明 |
|---|---:|---|
| **PASS** | **77** | 职责已完整承接，行为一致且有证据（E-2 源码级对照 / E-3 真中间件） |
| **INTENTIONALLY_CHANGED** | **3** | D-06 / D-07 / D-08（**无对端域** ⇒ 已签字偏离） |
| **NOT_VERIFIED** | **3** | D-09 / D-10 / D-11（**域内副本 ⇒ 共享层收口未完成**） |
| **FAIL** | **0** | — |
| **NOT_IMPLEMENTED** | **0** | — |
| **FRAMEWORK_DIFFERENCE** | **0** | F-1 ~ F-11 全部有适配 + 验证证据 |
| **BLOCKED** | **0** | — |
| **合计** | **83** | = B 侧主代码 `.java` 总数 ✅ **零遗漏** |

### 11.6.2 分层完成度（按 `.agents/verification.md` §11.6 要求分档）

| 档 | 项 | 数量 | 依据 |
|---|---|---:|---|
| **已实现且已验证** | 承接行 | **77 / 83** | 逐行见 §11.2 |
| **已实现但未验证** | 承接行（共享层收口未完成） | **3 / 83** | D-09 / D-10 / D-11 |
| **尚未实现** | 承接行 | **0 / 83** | §11.3.7「C 侧无缺失」 |
| **明确存在漂移** | C 侧引入的业务行为漂移 | **0** | 本轮修掉 **5** 条（D-01 ~ D-04 ＋ **D-13 = CORS 能力面缺失**）；**B 侧既有缺陷 10 条不计入**（非 C 侧引入） |
| **框架差异待验证** | Boot 4→3.5 差异项 | **0 / 11** | F-1 ~ F-11 全部有适配 + 验证证据 + 最终结论<br>⇒ ⚠️ **另加 F-13**（Spring 版本副产物 ⇒ 404 多 2 条 `Vary`）—— **语义等价、字节面有差** ⇒ 已归 **D-15** 登记（只登记不改码）⇒ ⇒ **不计入「待验证」**（根因已到源码行） |
| **被外部条件阻塞** | 承接行 | **0 / 83** | — |
| **已批准的主动变更** | 承接行 | **3 / 83** | D-06 / D-07 / D-08 |

### 11.6.3 代码与测试规模（**仅供参考，不代表完成度**）

| 项 | B 侧 | C 侧 | 说明 |
|---|---:|---:|---|
| 主代码 `.java` | **83** | **63** | 差额来自 **N:1 合并**（7 api Service→1 · 6 rpc Service→1 · 2 进程→1）＋ **C 侧新增 5 类**（`ArticleCorsFilter` · `ArticleJwtAuthFilter` · `JsonbTypeHandler` · `web/ArticleStringFields` · `web/ArticleQueryValues`） |
| 空壳类 | **0** | **0** | ✅ |
| 迁移脚本 | `V8` / `V9` / `V10` | `V8` / `V9` / `V10` | ✅ **逐字节照抄**（`diff -q` = IDENTICAL） |
| proto | `article.proto`（`com.rag.article.article.proto`） | `proto/` 根级（`com.rag.proto.article`） | ✅ 内容逐字节一致，仅 `java_package` 属表达层 |
| 测试类 / 用例 | **45** 个测试类 | **34** 个测试类 / **160** 例（**默认回归 32 类 / 157 例**；另有 **2 类 / 3 例** 属 `external-env` —— 依赖宿主外部容器 ⇒ **默认不含**，显式跑法见 §11.6.4.5） | B 侧 45 个测试类中，**期望值可移植**的部分已移植；**B↔C 期望值沿用**（AGENTS.md §1.0c） |

> 📌 **第二轮补上的 12 个测试类**（相对第一轮的 22 类 / 118 例）：
> `ArticleServiceApplicationTests`（5）· `mapper/ArticleSchemaAndTimestampRealPgTest`（6）·
> `domain/service/ArticleConcurrencyRealPgTest`（2）· `domain/service/ArticlePublicListSnapshotRealPgTest`（2）·
> `domain/service/ArticleWritePathRealPgTest`（5）· `http/ArticleErrorCodeMappingRealTomcatTest`（4）·
> `storage/ArticleObjectStorageClientRealMinioTest`（7）· `storage/ArticlePublicLegacyMarkdownRealMinioTest`（3）·
> `grpc/ArticleToMessageRpcCrossDomainTest`（2 · external-env）· `grpc/MessageRpcAbsentPeerCrossDomainTest`（1 · external-env）·
> **`storage/ArticleUploadFileRealMinioTest`（3 · 第三轮补 · 闭合 U-11 剩余 1/6）**·
> **`http/ArticleHttpEndpointHeaderPlaneRealTomcatTest`（2 · 第三轮补 · 闭合 U-3）**
> ⇒ 22 类 / 118 例 ＋ **12 类 / 42 例** = **34 类 / 160 例**（默认回归 **32 类 / 157 例**）。
> ⚠️ **另外填掉了一个 8 行空壳桩**：`ArticleServiceApplicationTests` 原本是
> 「`TODO：职责待补` + 空类体」的**空壳**（与 `like` / `comment` / `favorite` / `task` 四个**尚未落地**的域同款）⇒
> 按**仓内约定**（已落地域都把它填成真正的应用上下文自检：`follow` 166 行 / `message` 458 行 / `admin` 253 行 / `users-user` 133 行）
> ⇒ 本轮填成 **5 例**（生产入口上下文自检 + `@MapperScan` 生效 + 域内 `.imports` 真被读到 + **迁移账本里有 V8/V9/V10 且未被重编号** + 实体表名是 B 侧那五张）
> ⇒ ⚠️ 它**继承** `support/ArticleRealPgTestBase` ⇒ 与本域其它集成测试**共享同一个 Spring 上下文**（不额外多起一次装配）。

### 11.6.4 守门与回归读数

| 闸门 | 读数 | 判定 |
|---|---|---|
| `tools/difftest/check-arch-invariants.py` | **PASS 10 · FAIL 0 · N/A 2 · PENDING 1 · EXIT=0**（PENDING = A8 属 like/comment/favorite，**非本域**；A13 = **31/31** 个 Lombok 类均带展开块） | ✅ |
| `tools/difftest/check-docs-vs-tree.py` | **✅ 全部相符合（File_Tree 双向 · 幂等 · 路径 · 落点计数 · 包名）· EXIT=0** | ✅ |
| `tools/check-mapping-coverage.py`（ROOT 临时指工作树） | article **C 列 63/63 = 100%** · **B 列 83/83 = 100%** · 全仓 **C 642/642 · B 752/752** · `_INDEX.md` 自报数字对账 = **✅ 全部一致（10 个域 × 4 项）** · 小节自报条数对账 ✅ · B 列双向对账 ✅ · **EXIT=0** | ✅ |
| `mvn -o -pl service/article -am clean verify` | **BUILD SUCCESS**（Total time **01:37** min）—— 63 个主代码 `.java` 全通过编译 | ✅ |
| **article-service 模块级回归** | `Tests run: 157, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | ✅ |
| **external-env 组（**默认回归不含**）** | `mvn -o -pl service/article -am test -Pexternal-env` ⇒ `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` ⇒ 逐类：`MessageRpcAbsentPeerCrossDomainTest` **1** · `ArticleToMessageRpcCrossDomainTest` **2**（日志 `/tmp/u2-profile-run-final.log` · **最终代码状态**） | ✅（另跑） |
| **common 模块（同轮）** | `Tests run: 234, Failures: 0, Errors: 0, Skipped: 0` | ✅ |
| **proto 模块（同轮）** | `SUCCESS`（无测试） | ✅ |

> 📌 **计数口径**：取 **回归日志的模块级汇总行**（AGENTS.md §五.8 规则 ①）⇒ **未**对 `surefire-reports/*.xml` 求和（孤儿报告虚高）。
> 📌 **报「0 failures」同断 `Skipped: 0`**（规则 ②）。
> 📌 **回归命令（本轮最终读数来源）**：`MAVEN_OPTS=-Xmx1024m mvn -o -pl service/article -am clean verify`（**clean + verify** ⇒ 复编译 + 全量测试一次跑完，**不是**增量 `test`）。
> 📌 **日志落盘**：`/tmp/article-final-verify3.log`（**最终读数来源** · 第三轮 · 157 例）· `/tmp/article-final-verify2.log`（第二轮 · 152 例）· `/tmp/article-final-verify.log`（第一轮：147 例，无新填的应用自检类）· `/tmp/u2-profile-run-final.log`（external-env 组）⇒ 上表数字**逐字引用**这些日志的模块级汇总行与 `in com.rag.article.*` 逐类行。
> ⚠️ **`@EnabledIfDockerAvailable` 核对**：`grep -rn "EnabledIfDockerAvailable" service/article/src/test/` ⇒ **2 处命中，但都在 `support/TestPostgres.java:21,23` 的 javadoc 里**（**记录禁令本身**）⇒ **真实注解用法 0 处** ⇒ `Skipped: 0` 有意义（Docker 不可用时直接红，不静默跳过）。
> 🔴 **⚠️ 第一轮的一处读数已更正（2026-11-03）**：第一轮记的是「映射覆盖脚本整体 **EXIT=1**，2 条 B 列报错全落 users 域（`users/user/user.md:132` 的 B 列写了 `CreateUserReq.java`）」。
> ⇒ **复核后：该结论不成立** —— 那 2 条报错的根因是**主工作区**当时（users 域尚未收口）的映射表只列了 `CreateUserReq.java`；users 域收口后（主工作区 HEAD `85751b6`）**两个 ROOT 都 EXIT=0**（工作树口径 642/642 + 752/752 · 主工作区口径 637/637 + 752/752）⇒ ⇒ **不是**「基线问题所以不管」，而是**已被别人修掉**，本轮**复跑确认**。
> 📌 **教训**：**「脚本整体红」不等于「本域红」** —— 但反过来也成立：**「脚本整体红」也不能自动归给「别人的问题」** ⇒ 必须**在结论落笔时复跑并写明复跑时点**。

**32 个测试类 / 157 例逐类读数**（**逐字引用 surefire 输出** · 日志 `/tmp/article-final-verify3.log`）：

| 测试类 | 形态 | 例数 | 读数 |
|---|---|---:|---|
| `contract/ArticleErrorCodeContractTest` | 纯单测（锁错误码表） | 15 | `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` |
| `contract/ArticleSyncEventContractTest` | 纯单测（锁事件字段名） | 14 | `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` |
| `assembly/ArticleSchemaGateFailFastTest` | **真启动 + 真 PG + 真 Flyway** | 1 | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` |
| `assembly/ArticleGrpcChainAssemblyTest` | 真启动 + **反射读运行中 server 的拦截器登记表** | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `assembly/ArticleAssemblyMetricsTest` | **真 `PrometheusMeterRegistry` scrape** | 3 | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| `assembly/ArticleAssemblyAutoConfigAndValidationTest` | 真 PG + 自动配置真装配 | 4 | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` |
| `mapper/ArticleJsonbResultMapRealPgTest` | 真 PG | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| `mapper/ArticleUpdateStrategyRealPgTest` | 真 PG | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| `mapper/ArticleMapperScriptRealPgTest` | 真 PG | 4 | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` |
| `mapper/ArticleMapperSortContractTest` | 纯单测（读 SQL 文本） | 4 | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` |
| `mapper/ArticlePublicMapperScriptRealPgTest` | 真 PG | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| `http/ArticleHttpCorsRealTomcatTest` | **真 Tomcat + 裸 socket 字节面** | 7 | `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` |
| `http/ArticleHttpAuthRealTomcatTest` | 真 Tomcat | 4 | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` |
| `http/ArticleHttpParseFailureRealTomcatTest` | 真 Tomcat | 11 | `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` |
| `http/ArticleHttpRoutingRealTomcatTest` | 真 Tomcat + 裸 socket 字节面 | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| `http/ArticleHttpEnvelopeRealTomcatTest` | 真 Tomcat | 6 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| `kafka/ArticleKafkaSyncResultConsumerRealKafkaTest` | **真 Kafka 容器** | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `kafka/ArticleKafkaOutboxRelayRealKafkaTest` | 真 Kafka 容器 | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `kafka/ArticleKafkaReviewConsumerRealKafkaTest` | 真 Kafka 容器 | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `domain/service/ArticleDomainServiceRealPgTest` | 真 PG | 6 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| `domain/service/ArticleAtomicServiceRealPgTest` | 真 PG + 真 AOP 代理 | 6 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| `domain/service/ArticlePublicDomainServiceRealPgTest` | 真 PG | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| **`ArticleServiceApplicationTests`**（**第二轮补**） | **真启动 + 真 PG + 真 Flyway** —— 生产入口上下文自检 | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| **`mapper/ArticleSchemaAndTimestampRealPgTest`**（**第二轮补**） | 真 PG（DDL / 时间列 / 软删可见性） | 6 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| **`domain/service/ArticleConcurrencyRealPgTest`**（**第二轮补**） | 真 PG + 真并发 + `pg_blocking_pids` 锁序探针 | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| **`domain/service/ArticlePublicListSnapshotRealPgTest`**（**第二轮补**） | 真 PG + MyBatis 拦截器钩子（并发撤回插在 count 与 page 之间） | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| **`domain/service/ArticleWritePathRealPgTest`**（**第二轮补**） | 真 PG + 真事务 | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| **`http/ArticleErrorCodeMappingRealTomcatTest`**（**第二轮补**） | **真 Tomcat** + 真 PG（改表名制造真 SQL 错误） | 4 | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` |
| **`storage/ArticleObjectStorageClientRealMinioTest`**（**第二轮补**） | **真 MinIO 容器** | 7 | `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` |
| **`storage/ArticlePublicLegacyMarkdownRealMinioTest`**（**第二轮补**） | **真 MinIO 容器** | 3 | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| **`storage/ArticleUploadFileRealMinioTest`**（**第三轮补** · 闭合 U-11 剩余 1/6） | **真 MinIO 容器** ＋ 真 PG（继承 `MinioSpringTestBase`） | 3 | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| **`http/ArticleHttpEndpointHeaderPlaneRealTomcatTest`**（**第三轮补** · 闭合 U-3） | **真 Tomcat + 裸 socket 字节面**（7 端点 × 4 形态） | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| **合计（**默认回归**）** | | **157** | **0 failures · 0 errors · 0 skipped** |

**默认回归**之外那 **2 类 / 3 例**（`external-env` —— 依赖宿主外部容器，**默认排除**）：

| 测试类 | 形态 | 例数 | 读数（`-Pexternal-env` 显式跑） |
|---|---|---:|---|
| `grpc/ArticleToMessageRpcCrossDomainTest` | **真 Nacos + 真 message-rpc 容器 + 真对端 PG** | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `grpc/MessageRpcAbsentPeerCrossDomainTest` | 同上 + **对端不在注册表里**（反例） | 1 | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` |
| **合计** | | **3** | **0 failures · 0 errors · 0 skipped** |

> ⚠️ **真 PG 容器**：`postgres:16-alpine`（与 B 侧同镜像）⇒ **H2 一个都不认**（`BIGSERIAL` / `TIMESTAMPTZ` / `jsonb` / `ON CONFLICT` / `CREATE UNIQUE INDEX` / `CHECK` / `CREATE TRIGGER`）⇒ **H2 上「跑绿」是假绿**。
> ⚠️ **真 Kafka 容器**：`apache/kafka:3.7.2`（本机已有该镜像）⇒ 消息**真的被投递/消费**。
> ⚠️ **真 Tomcat**：`RANDOM_PORT` + `TestRestTemplate` + **裸 socket 字节面**（`TestRestTemplate` 会把「零长度 body」与「null body」折叠 ⇒ 拿不到字节数 ⇒ 必须裸 socket 手写 HTTP/1.1 逐字节读回）。
> ⚠️ **禁 `@EnabledIfDockerAvailable`** ⇒ Docker 不可用时**直接失败**，不静默跳过 ⇒ `Skipped: 0` 有意义。
> ⚠️ **测试侧变异探针**（证明断言不是假绿）：① T3 种子 `secondary_tags` 改成 `["probe-marker"]` ⇒ `Tests run: 5, Failures: 3`；② T1 种子 `created_at` 改到 4 月 ⇒ `Tests run: 4, Failures: 1`；③ HTTP 侧把 `GO_ARTICLE_ORDER` 改成 Jackson 默认**字母序** ⇒ 键序用例变红 ⇒ ⇒ **三条都绑定在真数据流上**，探针后**已完整还原**。

#### 11.6.4.1 **源码变异探针**（2026-11-03 补做 · 闭合 §11.6.5 的 U-4）

> **为什么必须做**：上面三条是**测试侧**变异（改种子数据）⇒ 只证明「断言绑定在真数据流上」；**源码侧**变异才证明「断言绑在**那个实现点**上」⇒ 否则「测的是不是那个现象」仍未钉死。
> **做法**（每条都是：**备份 → 应用变异 → 跑指定测试类 → 还原 → 同条件复跑** ⇒ **必须同时看到「变异红」与「还原绿」**，只红不算数）：
> ① 文件备份 + `cp` 还原（**不依赖 git** ⇒ 不会因 `git checkout` 误伤工作树其它未提交改动）；
> ② 变异片段**必须唯一** ⇒ 脚本前置断言「片段出现次数 == 1」，否则**中止**（避免改到多处而读数不可归因）；
> ③ 每次只改**一个维度** ⇒ 红在哪个用例，就说明那个用例真的在看那个维度。

| 变异 | 改哪里（`src/main`） | 预期红 | **实测读数** | 失败断言（逐字） | 还原后复跑 | 结论 |
|---|---|---|---|---|---|---|
| **MUT-A** | `mapper/ArticleMapper.java` `selectByIdUnscoped` —— **删掉 `@ResultMap("articleEntityResultMap")`** | `ArticleJsonbResultMapRealPgTest` | `Tests run: 5, Failures: 1, Errors: 0, Skipped: 0` + `BUILD FAILURE` | `ArticleJsonbResultMapRealPgTest.articleMapperReadsJsonb:119 expected: <[alpha, beta]> but was: <null>` | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | ✅ **静默 null 被抓到**（正是 javadoc 预测的「不报错、不抛异常、字段恒 null」） |
| **MUT-B** | `domain/service/ArticleAtomicService.java` `acceptSyncResult` —— **删掉 `@Transactional`** | `ArticleAtomicServiceRealPgTest` | `Tests run: 6, Failures: 1, Errors: 0, Skipped: 0` + `BUILD FAILURE` | `ArticleAtomicServiceRealPgTest.domainOutboxFailureRollsBackEverything:234 revision 在失败事务后残留 ⇒ 没有真的回滚 ==> expected: <0> but was: <1>` | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | ✅ **事务边界被钉死**（注解**真被运行期使用**，不是装饰） |
| **MUT-C** | `mapper/ArticleMapper.java` `selectAuthorView` 的排序方向 —— **三分支 `<choose>` 简化成两分支** | `ArticleMapperSortContractTest` ＋ `ArticleMapperScriptRealPgTest` | `Tests run: 8, Failures: 2, Errors: 0, Skipped: 0` + `BUILD FAILURE` | ① `ArticleMapperScriptRealPgTest.authorViewDefaultsToCreatedAtDescAndIgnoresDescFlag:152 sortBy="" ⇒ 恒 created_at DESC（最新在前） ==> expected: <[901001003, 901001002, 901001001]> but was: <[901001001, 901001002, 901001003]>`<br>② `ArticleMapperSortContractTest.directionHasExactlyThreeBranches:159 🔴 方向必须是**三分支**（2 个 <when> + 1 个 <otherwise>）… 实际 <when> 数：1 ⇒ [desc] ==> expected: <2> but was: <1>` | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | ✅ **行为层 + 结构层双红** —— ① 证明**默认调用整页颠倒**（正是 javadoc 预测的现象）② 证明**结构断言**也在看同一维度 |

> 📌 **还原核对**：三条全部 `diff -q` 判定**文件已完整还原** ⇒ 工作树与变异前逐字节一致；变异后重新 `git diff --stat` 与变异前一致。
> 📌 **日志落盘**：`/tmp/mut-MUT-A-JSONB-RESULTMAP-{red,green}.log` · `/tmp/mut-MUT-B-TRANSACTIONAL-{red,green}.log` · `/tmp/mut-MUT-C-SORT-3BRANCH-{red,green}.log`；探针脚本 `/tmp/mut-run.sh`（备份 → 唯一性前置断言 → 变异 → 跑测 → 还原 → 复跑）。
> ⚠️ **口径**：**MUT-A/B/C 只证明「这三条断言不是假绿」** ⇒ **不外推**到其余 115 例（AGENTS.md §3.4「效力边界」）。

#### 11.6.4.2 **第二轮补做：闭合 §11.6.5 的 U-6 / U-7 / U-8 / U-9 / U-10 / U-11 / U-13（2026-11-03）**

> **这一轮的定位**：第一轮把「承接表 83 行」做满了，但 §11.6.5 的 **13 条表外缺口**里还有 **7 条**是**本地就能闭合**的（不需要对端环境）⇒ 这一轮把它们补掉。
> **做法**：全部从 **B 侧 oracle 逐条移植**，期望值**逐字沿用 B 侧**（AGENTS.md §1.0c「测试资产单独一档」）⇒ **不是本域自拟的期望值**。

**新增 5 个测试类 / 19 例（读数逐字引用 surefire 输出）**：

| 测试类 | 闭合的缺口 | 形态 | 例数 | 读数 |
|---|---|---|---:|---|
| `mapper/ArticleSchemaAndTimestampRealPgTest` | **U-8**(3) + **U-9**(2) + **U-10**(1) | 真 PG | 6 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| `domain/service/ArticleConcurrencyRealPgTest` | **U-7**(2) | 真 PG + **真并发** + `pg_blocking_pids` 锁序探针 | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `domain/service/ArticlePublicListSnapshotRealPgTest` | **U-6**(2) | 真 PG + **MyBatis 拦截器钩子**（并发撤回插在 count 与 page 之间） | 2 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `domain/service/ArticleWritePathRealPgTest` | **U-11**(5/6) | 真 PG + 真事务（MinIO / Kafka 桩掉，只断言「发起没发起」） | 5 | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| `http/ArticleErrorCodeMappingRealTomcatTest` | **U-13**(4) | **真 Tomcat** + 真 PG（改表名制造**真** SQL 错误） | 4 | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` |
| **合计** | **7 条缺口** | | **19** | **0 failures · 0 errors · 0 skipped** |

> 📌 **B 侧 oracle 的逐条出处**（全部**逐字移植**，期望值沿用 B 侧）：
> `ArticleTransactionWiringTest.java:146-165`（Flyway）/ `:169-185`（触发器）/ `:189-198`（CHECK）/
> `:869-892`（S-3 `updated_at`）/ `:912-925`（S-4 SkipHooks）/ `:591-607`（软删除 split）/
> `:966-1045`（CASE-R12）/ `:1090-1141`（锁序探针）。
> ⚠️ **锁序那条 C 侧比 B 侧更精确**：B 侧靠 `application_name='article-lockorder'` 过滤被阻塞会话
> （要改 JDBC URL ⇒ 会波及共享的 `support/TestPostgres`）；C 侧改用 **`pg_blocking_pids(pid)`**
> —— 直接问 PG「谁在挡我」⇒ **唯一确定**那个被阻塞的后端 ⇒ 不误抓别的会话。

**这一轮新写的判据里，有三条是「**读注解/读文本证明不了**」的**运行期**断言**：

| 判据 | 为什么必须运行期 | 怎么验的 |
|---|---|---|
| `listPublicArticles` 真的在 **REPEATABLE_READ + readOnly** 事务里 | 读注解只能证明「写成了这样」；AGENTS.md §3.3.1 要求**运行期证据** | 探针在事务内直接问 **PG 自己**：`SHOW transaction_isolation` = `repeatable read`、`SHOW transaction_read_only` = `on` |
| count 与 page **同一快照** | 只验注解 ⇒ 隔离级别被改（降成 DEFAULT）也照样绿 | 拦截器在 `countPublic` 与 `selectPublicPage` **之间**插入并发撤回 ⇒ 页内容**仍看得到** ⇒ 同源 |
| 锁序 = **article → publication** | CASE-R12 测的是「最终结果」，**证伪不了锁序**（B 侧实测：拿掉 article 行锁，CASE-R12 仍全绿） | 同时扣住两把锁 ⇒ 被阻塞后端执行的 SQL **唯一确定**第一把锁 |

> ⚠️ **同条件对照**（证明判据有区分力，不是恒绿）：`ArticlePublicListSnapshotRealPgTest` 的第二条用例用
> **同一套 mapper 方法 + 同一个并发撤回**，但**不经过服务方法** ⇒ 每次调用各自 autocommit
> （PG 默认 READ COMMITTED）⇒ **确实会撕裂**（`total=2` 而页内容只有 1 条）
> ⇒ ⇒ 由此可知第一条的「不撕裂」是**隔离级别的功劳**，而不是「探针看不见撤回」。

**这一轮自己也踩到并修掉的 4 个坑（都是**测试侧**的错，不是生产代码缺陷）**：

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| 1 | MP 的 `updateFill` **原地改写**实体的 `updatedAt` | `updated_at` 断言读的是**被改过**的对象 ⇒ `isAfter` 恒 false ⇒ **假红** | 在 `updateById` **之前**从库里取 `before` |
| 2 | 自建 `@SpringBootTest` 上下文时**漏了** `@DynamicPropertySource` | `IllegalStateException: article-api.auth.access-secret 未配置` ⇒ 上下文起不来 | 补 `TestPostgres.register` + `POSTGRES_PASSWORD` + `USER_JWT_SECRET` + `grpc.client.message-rpc.address` |
| 3 | 断言「软删**不得**物理删行」时**期望值写反** | `expected: <0> but was: <1>` | 软删后**行仍在**（`deleted_at` 非空）⇒ 期望值应是 1，并补一条 `selectById` 查不到 |
| 4 | 以为 `article_sync_outbox.event_type` 存的是 op | `expected: <upsert> but was: <article_sync>` | 该列存的是**事件域**（`ARTICLE_SYNC_SCOPE`）⇒ op 在 **payload** 里 ⇒ 改断言 payload 的 `op` |

#### 11.6.4.3 **第二轮新增测试的源码变异验证**（MUT-D / MUT-E）

> 沿用 §11.6.4.1 的四条细则（备份 → 唯一性前置断言 → 变异 → 跑测 → 还原 → 同条件复跑）。
> ⚠️ **本轮第一次跑被「并行 worker 的中间态编译错误」打断**（worker 正在改的
> `storage/ArticleObjectStorageClientRealMinioTest.java` 有一行编译不过 ⇒ **整个 testCompile 挂掉**
> ⇒ 变异信号与还原信号都读不出来）⇒ **等 worker 收工后重跑**才拿到下面的读数。
> ⇒ **教训**：**并行写同一工作树 ⇒ 任何一方的中间态都会污染另一方的验证读数** ⇒
> 验证类跑批必须**独占**工作树，或至少**等写方收工**。

| 变异 | 改哪里（`src/main`） | 预期红 | **实测读数** | 失败断言（逐字） | 还原后复跑 | 结论 |
|---|---|---|---|---|---|---|
| **MUT-D** | `mapper/ArticleMapper.java` `selectByIdForUpdate` —— **去掉 `FOR UPDATE`** | `ArticleConcurrencyRealPgTest` | `Tests run: 2, Failures: 1, Errors: 0, Skipped: 0` + `BUILD FAILURE` | `ArticleConcurrencyRealPgTest.lockOrderIsArticleThenPublication:235 锁序必须是 article → publication ⇒ 它应该先卡在 **article** 的 FOR UPDATE 上；实际被阻塞的语句：SELECT * FROM article_publication WHERE article_id = $1 FOR UPDATE ==> expected: <true> but was: <false>` | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | ✅ **锁序探针有牙齿** —— 拿掉 article 行锁 ⇒ 它改为卡在 publication 上 ⇒ **被阻塞的那条 SQL 唯一确定**了第一把锁 ⇒ 这条判据**不是**靠「CASE-R12 的结果」反推的 |
| **MUT-E** | `domain/service/ArticlePublicDomainService.java` `listPublicArticles` —— **隔离级别降成 `Isolation.DEFAULT`** | `ArticlePublicListSnapshotRealPgTest` | `Tests run: 2, Failures: 1, Errors: 0, Skipped: 0` + `BUILD FAILURE` | `ArticlePublicListSnapshotRealPgTest.countAndPageComeFromTheSameRuntimeSnapshot:172 运行期隔离级别必须是 REPEATABLE_READ（Go: sql.TxOptions{Isolation: LevelRepeatableRead}） ==> expected: <4> but was: <null>` | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | ✅ **运行期隔离级别断言有牙齿**（降级被当场抓住）<br>⚠️ **效力边界（诚实）**：变异是**在「运行期事实」那条断言上**被抓住的（Spring 对 `DEFAULT` 根本不发布隔离级别 ⇒ 读出来是 `null`）⇒ **「行为上撕裂」那条断言本轮没被这条变异直接验证** ⇒ 它的区分力由**同条件对照用例**（READ COMMITTED 下**确实**撕裂）承担 |

#### 11.6.4.4 **U-1 双侧活体对拍 + U-5 / U-12 真 MinIO 链路（2026-11-03）**

##### ① U-1：`tools/parity-probe/article/`（**E-4 级 · 两侧同时跑**）

| 项 | 内容 |
|---|---|
| **比对双方** | **B 侧 Java 栈**（Boot 4 · 本地现成镜像 `sea/article-api:latest` / `sea/article-rpc:latest` ⇒ **B 仓一个字节都不改**，连 `target/` 都不写）↔ **C 侧 Java 栈**（Boot 3.5 · 本仓 fat jar） |
| **比对方式** | 同一批 HTTP 请求 ⇒ **`xxd` 逐字节**比对（**body hex + HTTP 状态码 + Content-Type** 三样）+ 与**实跑后冻结**的基线三方全等 |
| **读数** | **12/12 条逐字节一致** · `EXIT=0` · 共跑 **4 轮**全绿（冻结 / 校验 / 最终确认 + 3 轮变异）· 基线 sha256 三轮一致 `05e6f9576d91b1563ceddda223fe4e2a090703bbd4731dfcf343737dfbe663b5` |
| **变异验证** | **3/3 精确转红**：M1 改 **C 侧**库的 title（⇒ 报差异的用例 = 5 条读路径）· M2 改 **B 侧**库的 title（⇒ 检测**不是单向的**）· M3 改冻结基线里一枚 hex（⇒ 证明基线**确实参与判定**）⇒ 全部 `EXIT=1` + 基线已还原（sha256 校验）⇒ **探针不是恒绿** |
| **隔离设计** | **V1** 两个 Nacos 命名空间（🔴 两侧注册的服务名**都是 `article-rpc`** ⇒ 共用会互相串台 ⇒ **假绿**）· **V2** 两侧各自 PG（Flyway 账本各一个库）· **V3** 端口租约 · **V4** 构建锁 · 探针**自带 MinIO**（两侧必然同凭据） |
| **清理** | `trap` + **按端口反查 PID**（`lsof -nP -iTCP:<port> -sTCP:LISTEN -t`）⇒ 跑完**零残留容器**、五个端口全空、租约目录为空 |

**覆盖的 12 个用例**（两侧路由表 7 个端点逐条对齐）：成功信封与业务码信封（1002）· **HTTP 200 携业务错误码** · **HTTP 401 + 空 body + 无 Content-Type** · **HTTP 404 + text/plain** · 分页（`total`/`page`/`page_size`、越界页 `articles:null`、非法页码 → 1002）· 未知 query 参数被忽略 · **legacy 分支**（正文读 MinIO 对象 + `ext_info={"publication_gap":"legacy_revision_missing"}`）· **revision 分支**（正文取 `revision.markdown` + `ext_info={"published_revision_id":…}` + `update_time` 取 `revision.published_at`）· **作者视角**（取原始 article 行而非 revision + 正文读 MinIO + `ext_info:null`）。

> 📌 **过程中排掉的两个「疑似差异」（都不是漂移）** —— **这是本轮最有价值的部分**：
> ① **两侧都返回 1015** ⇒ 追到底是**探针 env 的 MinIO 密钥给错** ⇒ `storage.readString()` 失败 ⇒ 抛 `ArticleExceptions.raw(e)`（**刻意不带 gRPC status**）⇒ 落 UNKNOWN ⇒ 1015 ⇒ **两侧同因同映射**（B 侧日志原文：`article-rpc handler failed with a raw error (mapped to UNKNOWN): The request signature we calculated does not match the signature you provided.`）⇒ 改对凭据后两侧**同时**翻成 200 且字节相同 ⇒ **真等价，不是撞码**；
> ② **一次偶发「跑红」** ⇒ B 侧 `author_name` 补全去连隔离命名空间里**不存在**的 `user-rpc` ⇒ 每次失败 ⇒ 触发共享层 gRPC **熔断** ⇒ 熔断打开时抛 `GoStyleCircuitOpenException`（**普通 `RuntimeException`，不是 `StatusRuntimeException`**）⇒ B 侧 `resolveAuthorName` 的 catch **接不住** ⇒ 冒泡 ⇒ 列表端点 1015 而 C 侧恒 200 ⇒ **假红** ⇒ 修法：种子 `author_id='0'` ⇒ 两侧同走「uid ≤ 0 ⇒ 直接回落」⇒ 不发 RPC ⇒ 无熔断 ⇒ 确定性。
> ⇒ **教训**：**「偶发跑红」必须追到根因**（否则一个 flaky 探针毫无价值）；且**熔断异常不是 `StatusRuntimeException`** ⇒ 它的 catch 链是另一条路。

##### ② U-5 / U-12：`storage/` 包（**真 MinIO 容器**）

| 项 | 内容 |
|---|---|
| **产物** | `TestMinio`（真 MinIO 单例容器底座 · `minio/minio:latest` · `/minio/health/live` HTTP 等待）· `ArticleObjectStorageClientRealMinioTest`（**U-5 · 7 例**）· `MinioSpringTestBase` · `ArticlePublicLegacyMarkdownRealMinioTest`（**U-12 · 3 例**） |
| **读数** | `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（`Skipped: 0` 成立 ⇒ **无** `@EnabledIfDockerAvailable`） |
| **期望值来源** | ⚠️ **B 侧对 `ArticleObjectStorageClient` 没有任何测试**（`article-rpc/src/test` 里命中处**全是 mock**）⇒ 期望值只能从 B 侧**实现源码**逐字读出 ⇒ **E-2 级**，**不得**单独当「B 侧活体证据」 |

**顺带查清并写回台账的 4 件事**（不是 C 侧缺陷）：

| # | 事实 | 处理 |
|---|---|---|
| 1 | **B-06 措辞与代码实际不符** —— 台账原写「`buildPublicFileURL` 不去尾斜杠」，实测 **base 段的尾斜杠其实会被去掉**（`baseURL.replaceAll("/+$","")` 作用在**拼好的 baseURL** 上）⇒ **只有对象名的尾斜杠保留** | ✅ 台账 B-06 行**措辞已按实测收敛**；C 侧 javadoc 里那段**照抄自 B 侧的错误注释**也一并改成与代码相符（**代码逐字节未动**） |
| 2 | **MinIO 服务端会规范化桶策略**：空 `"Sid": ""` 被**服务端丢掉**（键序也重排） | 桶策略断言分两层：「发送侧逐字」（反射取 package-private 的 `bucketPublicReadPolicy`，与 B 侧 `:180-184` 逐字相等）+「存储侧结构」 |
| 3 | **桶无策略时 `getBucketPolicy` 返回空文档（不是 404）** | 「策略还在不在」按「空 **or** `NoSuchBucketPolicy`」判定；其它异常编码成**可见字符串**（连不上不会被当成「没有策略」蒙混过去） |
| 4 | **「桶已存在 ⇒ 不修正策略」这条不对称在真 MinIO 上被证实** | 删掉策略后再调 `ensureBucketWithPublicRead()` 返回 `false` 且**策略仍然不存在** |

> ⚠️ **一个必须记下来的环境事实**：`mvn -o -pl service/article surefire:test`（**不带 `-am`**）会从 `~/.m2` 解析**过期的** `common-1.0-SNAPSHOT`
> ⇒ `NoClassDefFoundError: io/opentelemetry/api/OpenTelemetry` ⇒ **跑 Spring 上下文用例必须带 `-am`**。
> ⇒ 这与 AGENTS.md §五.8 的「模块级汇总只取回归日志」是**两件不同的事**（前者是构建作用域，后者是计数口径）⇒ 都得守住。

#### 11.6.4.5 **U-2 跨域 gRPC 链路（真 Nacos + 真 message-rpc）**

> **这一节闭合的是 §11.6.5 的 U-2** —— 「承接行只管『有没有实现』，不管『**对端在不在**』」。

##### ① 产物（3 个文件 · 全在 `service/article/src/test/java/com/rag/article/grpc/`）

| 文件 | 作用 |
|---|---|
| `CrossDomainLinkSupport.java` | 测试期支撑工具：发布/撤销对端**镜像实例**（nacos-client SDK）、读 Nacos 注册表、读**对端自己的库**、删自己 uid 段、读**对端自己的 Prometheus**、轮询等待、注销 gRPC 全局 discovery provider |
| `ArticleToMessageRpcCrossDomainTest.java` | **正例**（2 例）：真入口 `ArticleServiceApplication` + 生产类 `MessageRpcClient` |
| `MessageRpcAbsentPeerCrossDomainTest.java` | **反例**（1 例）：对端不在注册表里 ⇒ 必须返 false 且**对端库零行** |

##### ② 链路是怎么真打通的

| 环节 | 做法 |
|---|---|
| **真 Nacos** | 宿主 `127.0.0.1:49848`（容器 `Sea-nacos`）⇒ **不是** `support/NacosStubConfiguration` 那个空操作 mock |
| **真对端** | 容器 `Sea-message-rpc`（gRPC `127.0.0.1:8086` · 管理口 `16067`）—— **它自注册在 `public/DEFAULT_GROUP/message-rpc`**（**只读**，B 侧部署镜像，一个字节没改） |
| **可达性** | Docker Desktop 里容器 IP **宿主不可路由** ⇒ 在**隔离组** `public/U2_ARTICLE_LINK` 发布一条**镜像实例** `127.0.0.1:8086`（nacos-client SDK 注册 · 带心跳 · ephemeral）⇒ 镜像的只是**地址** ⇒ gRPC 字节、服务发现机制、对端业务代码、对端自己的 INSERT **全真** |
| **分组隔离** | 正例组 `U2_ARTICLE_LINK` · 反例组 `U2_ARTICLE_LINK_ABSENT`（**刻意为空**）⇒ 共享组 `public/DEFAULT_GROUP` **一个字没动**（跑完复核：hosts 仍只有对端自己那条） |
| **被测配置刻意不覆盖** | `grpc.client.message-rpc.address` 保持**生产值** `discovery:///message-rpc`（`application.yml:199`）· `negotiation-type=plaintext`（`:203`）⇒ **被测的那段配置就是生产值** |

##### ③ 🔴 证据锚在「**对端自己写的**」上 —— 不是「返回 true」

> `MessageRpcClient#sendArticlePublished` 的实现是**吞掉所有 `StatusRuntimeException` 并返回 false**（B 侧同款，Go 侧只记日志不 panic）⇒ ⇒ **返回 true 只说明「gRPC 没抛异常」，不说明对端真的处理了这条消息**（对端完全可能在业务层吞掉）。
> ⇒ ⇒ 故断言锚在**对端进程自己写的库行** + **对端进程自己的 Prometheus 计数**上。

**对端自己的 PG 库行**（`sea_user.message_notification` · 第二轮 run2 · 逐字）：

```
 id                   | recipient_id | sender_id | sender_role | kind | title       | content                                        | extra                                                            | is_read | created_at
 2117541102897631232  | 905000001    | 0         | 3           | 5    | 文章发布成功 | 《跨域链路实测标题》已经完成审核并成功入库。     | {"author_id": "author-905000001", "article_id": "article-905000001"} | f       | 2026-11-03 10:44:41.788907+00
 2117541103061209088  | 905000002    | 0         | 3           | 5    | 文章发布成功 | 《标题 A》已经完成审核并成功入库。               | {"author_id": "author-905000002", "article_id": "article-905000002-a"} | f       | 2026-11-03 10:44:41.828158+00
 2117541103086374912  | 905000002    | 0         | 3           | 5    | 文章发布成功 | 《标题 B》已经完成审核并成功入库。               | {"author_id": "author-905000002", "article_id": "article-905000002-b"} | f       | 2026-11-03 10:44:41.833828+00
```

**对端自己的计数**（`http://127.0.0.1:16067/actuator/prometheus` · 三轮累加 3 → 6 → 9）：
`message_rpc_request_total{method="SendNotification",result="success"} 9.0` · `message_rpc_notification_action_total{kind="article_published",result="success"} 9.0`
⇒ **反例 uid `905000003`：零行**（对端根本没被调到）。

##### ④ 本轮攻克的根因：**同 JVM 两个 Spring 上下文互相串扰**（框架层，非本域缺陷）

| 环节 | 证据（**源码级已核实**） |
|---|---|
| devh 把 resolver 注册进 **gRPC 全局单例** | `net/devh/boot/grpc/client/autoconfigure/GrpcClientAutoConfiguration.java:123-124` ⇒ `nameResolverRegistration.register(NameResolverRegistry.getDefaultRegistry())`（本机 `grpc-client-spring-boot-starter-3.1.0.RELEASE-sources.jar`） |
| gRPC 的 provider 容器是 **`LinkedHashSet`** | `io/grpc/NameResolverRegistry`（grpc-api **1.63.0**）⇒ `private final LinkedHashSet<NameResolverProvider> allProviders` + `effectiveProviders`（**每 scheme 只有一个生效者**）⇒ ⇒ **同 scheme 先注册者胜出** |
| ⇒ 后果 | 同 JVM 第二个 Spring 上下文的 `discovery:///message-rpc` channel 用的是**第一个上下文的 DiscoveryClient**（**另一个 group**）⇒ 永远 `No servers found for message-rpc` |
| ⇒ 修法 | 两个类各自在 `@DynamicPropertySource`（**早于 context refresh** ⇒ 早于本上下文 channel 创建）**注销 gRPC 全局 registry 里所有 devh discovery provider** ⇒ 本上下文的 provider 成为唯一生效者；已建好的 channel 持有 resolver 实例，不受影响。日志实证：`[U2] 已从 gRPC 全局 NameResolverRegistry 注销 discovery provider × 0` / `× 1`，随后正例 `Done updating server list for message-rpc` |

> ⚠️ **这是 devh + gRPC 的框架行为**（生产单进程单上下文不受影响）⇒ **不是**本域缺陷 ⇒ 解法写在 `CrossDomainLinkSupport#unregisterGlobalDiscoveryResolverProviders` 的 javadoc 里。

##### ⑤ 🔴 本轮**发现**的 L1 漂移（只报不修 ⇒ 已登记 **D-14**）

- **B 侧** `service/article/article-rpc/src/main/resources/application.yml:125` = `${NACOS_NAMESPACE:public}`
- **C 侧** `service/article/src/main/resources/application.yml:133` = `${NACOS_NAMESPACE:sea-rtw}`
- ⇒ **C 侧内部自洽**（C 侧 `message` 域也是 `sea-rtw`）⇒ **单改 article 会反过来弄断它与 C 侧 message 的连接** ⇒ ⇒ 属**跨域**问题 ⇒ 归 **`global` 收口域**（AGENTS.md §五.3 规则 3）⇒ **本域只登记、不改码** ⇒ 详见 §11.5.2 的 **D-14** 行。
- ⇒ ⚠️ 本节的实测**显式设了** `namespace=public` 才跑通 ⇒ 它**反证**了默认 `sea-rtw` 下解析不到注册在 `public` 的对端。

##### ⑥ ⚠️ 环境依赖与隔离（**这一段是本节的必要组成，不是脚注**）

| 项 | 事实与处置 |
|---|---|
| **不是自包含** | 镜像 `sea/message-rpc:latest` 是 B 侧**整套部署镜像**（env 指向 `postgres/redis/kafka/minio` 一整套容器）⇒ **无法用 Testcontainers 自包含** |
| **三个后果** | ① 没有这套容器的机器上**必然红**（不是「跳过」）⇒ §五.8 规则 ③ **禁止** `@EnabledIfDockerAvailable` ⇒ 只能按规则 ④ 用**类别**排除<br>② 它会**写真实数据**（对端库每次多 3 行）⇒ 不该由**别人的**回归顺手触发<br>③ 它要**独占 Nacos 注册表** ⇒ 与其它域的并行回归会互相干扰 ⇒ **污染别人的读数** |
| **处置** | 类级 `@Tag("external-env")` ＋ **本域自己的** `service/article/pom.xml` 配 `<excludedGroups>`（默认排除）⇒ **默认回归不含这 3 例**；显式跑：`mvn -o -pl service/article -am test -Pexternal-env` |
| **🔴 一条踩坑（CLI 覆盖不了 pom）** | surefire 的 `groups`（includeTags）与 `excludedGroups`（excludeTags）是**两个独立过滤器** ⇒ 命中条件 = **include ∩ exclude** ⇒ 把 `excludedGroups` 写死后，`-Dgroups=external-env` 跑出 **`Tests run: 0`**（2026-11-03 实测）⇒ 且 **CLI 的 `-DexcludedGroups=`（空值）也覆盖不了 pom 里写死的值**（同样 `Tests run: 0`）⇒ ⇒ 排除项必须做成**属性 + profile**（`article.test.excludedGroups` ⇒ profile 改成 `__none__`）⇒ **本节读数就是用 `-Pexternal-env` 跑出来的** |
| **读数** | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（**两遍连跑均绿**）· 逐类：`MessageRpcAbsentPeerCrossDomainTest` **1** · `ArticleToMessageRpcCrossDomainTest` **2** · 日志 **`/tmp/u2-profile-run-final.log`**（**最终代码状态**）<br>⇒ 📌 **profile 是模块级作用域** ⇒ 同轮 `common` 的 **234 例照跑**（36 个类）⇒ **不影响别的模块**（对比：CLI 的 `-Dgroups` 是**全局**用户属性 ⇒ 实测把 `common` 的 234 例**一起清成 0** ⇒ ⇒ 故取 profile 而非 CLI） |
| **默认回归口径（勿当已验）** | `mvn clean verify` 的汇总行**不含**这 3 例 ⇒ ⇒ 台账必须把这条口径写明，否则「跑绿」会被读成「跨域链路也验过了」 |

##### ⑦ 仍未闭合 / 风险（如实列）

1. **环境依赖**：这两个类需要本机 docker 的 `Sea-nacos(49848)` · `Sea-message-rpc(8086/16067)` · `Sea-postgres(49432)` ⇒ 没有这套容器的机器上**会红，不是跳过**。
2. **对端库残留**：每轮留 3 行（905 号段，被测代码自己写的证据行）⇒ 只删自己 uid 段，**绝不碰对端其它数据**。
3. **未做**：B 侧 `article-rpc` 的**活体**对拍（本次以 B 侧**源码**为行为基准逐条对照）；article → comment/favorite/hot 的其它跨域链路。
4. **兜底**：容器口令硬编码在 `CrossDomainLinkSupport.PEER_DB_PASSWORD`（可用 `U2_PEER_DB_PASSWORD` 覆盖）。

#### 11.6.4.6 **第三轮补做：闭合 §11.6.5 的 U-3 ＋ U-11 剩余 1/6**（2026-11-03）

> **这一轮的定位**：第一轮把「承接表 83 行」做满了，第二轮把 **13 条表外缺口**里的 7 条补掉 ⇒
> 本轮只剩 **2 条**有明确残留：**U-3**（7 个端点**各自**的响应头名单没穷举）与 **U-11 剩余 1/6**（`uploadFile` 的真 MinIO 链路）。

##### ① U-3 · 7 个端点 × 响应头字节面穷举（`http/ArticleHttpEndpointHeaderPlaneRealTomcatTest` · **2 例**）

**产物**：新测试类 `service/article/src/test/java/com/rag/article/http/ArticleHttpEndpointHeaderPlaneRealTomcatTest.java`
（继承 `HttpRealTomcatTestBase` ⇒ **真 Tomcat ＋ 真 PG ＋ 裸 socket 逐字节读回**）。

**判据分两层**（这是本轮最关键的设计决定）：

| 层 | 头 | 判据 | 为什么这么分 |
|---|---|---|---|
| **应用层** | CORS 5 头 ＋ `Vary` ＋ `Content-Type` ＋ `Traceparent` ＋（错误形态）`X-Content-Type-Options` | **逐条精确相等**（**保留 `Vary` 的重复次数**） | 两侧源码都能指到出处 ⇒ **跨侧契约面** |
| **容器层** | `Date` ＋ `Connection` ＋ `Content-Length` **或** `Transfer-Encoding` | **有界集合**：前两个必在；分帧头**恰有一个**；**没有**第四个容器层头 | 分帧头由 **Tomcat 写 body 的方式**决定 ⇒ **不是跨侧契约**（B 侧同为 Tomcat）⇒ 钉「恰有一个」而不是钉具体是哪个 |

**7 个端点各自的成功形态名单全部逐条钉死**：
`POST /v1/article` · `PUT /v1/article/{id}` · `DELETE /v1/article/{id}` ·
`GET /v1/me/article/{id}` · `GET /v1/article/{id}` · `GET /v1/articles` · `POST /v1/upload`；
**四种形态**（200 / 400 / 401 / 404）各自钉死 + 401 的 **body 零字节** + 404 的 body **逐字节** = `"404 page not found\n"`。

##### ② 🔴 三条根因（全部**源码级**坐实，不是猜的）

| # | 现象 | 根因 | B 侧是否同款 |
|---|---|---|---|
| **①** | 裸报文里混着 `HTTP/1.1 103 Early Hints` ⇒ 状态行取到的是 1xx | 共享层断连探针**往 socket 直写** 28 字节（`common/…/observability/ClientDisconnectProbe.java` 的 `PROBE_BYTES`） | ✅ **两侧同款** —— B 侧 `service/common/…/GoStyleClientDisconnectProbe.java:234` 同样写（已签字偏离 **SD-2026-10-25-04**）⇒ **不是漂移**<br>⇒ **解析惯例照抄 B 侧**：`FavoriteApiRealTomcatGateTest.java:569,609-610`（「共享层会连写 **4 个** 103 ⇒ ⇒ 状态码必须取**最后一个**」）⇒ 本仓取「**第一个非 1xx 块**」（等价，且不会被 body 里的巧合文本带偏） |
| **②** | 404 的 `Vary` 有 **3 条**（B 侧只有 1 条） | **Spring 版本差异** —— `DefaultCorsProcessor.processRequest` 的**首个分支**不同（**6.2.16** 写完 Vary 才判 `config == null`（`:72`→`:77/:80/:83`→`:103`）；**7.0.10** 在 `:75-80` 就提前 return）<br>⇒ 触发路径：404 由 **静态资源处理器**（`ResourceHttpRequestHandler` **实现了 `CorsConfigurationSource`**）命中 ⇒ `AbstractHandlerMapping.hasCorsConfigurationSource` 为真 ⇒ 必然挂 `CorsInterceptor` | 🔴 **不同款** ⇒ **C 侧多 2 条** ⇒ **已登记 D-15**（见 §11.5.2）⇒ **只登记不改码** |
| **③** | 部分端点是 `Transfer-Encoding: chunked`（不是 `Content-Length`） | Tomcat 的**分帧**由写 body 的方式决定 | ✅ **两侧同款** —— B 侧 `FavoriteApiRealTomcatGateTest.java:613-614` 明写「**chunked 编码**：最终响应是 `Transfer-Encoding: chunked` ⇒ ⇒ body 要**解块**」⇒ **不是漂移** ⇒ 本仓**照抄**该解块逻辑 |

> ⚠️ **② 的边界必须写清**：本条**不声称**做了 B 侧活体对拍 —— B 侧 `like-api/LikeApiRouterErrorRealTomcatTest:130-137` **只断言 404 的 body / Content-Type / nosniff**，**不断言 `Vary` 条数** ⇒
> ⇒ 按 §3.3.3「凡写『与 B 侧一致』必须有两侧同时跑的探针」，D-15 只声称「**两侧代码路径同构 ＋ 唯一变量是 Spring 版本 ＋ 该版本源码逐行可查**」。

##### ③ 顺带发现的一处事实（**不是**漂移，但原台账没记）

`POST /v1/article` 在 **Kafka 不可达**时返回 **HTTP 200 ＋ 信封 `code=5001`**（`LoggingProducerListener` 的 `TimeoutException`：`article-creation-topic not present in metadata after 2000 ms`）
⇒ ⚠️ 现有 `ArticleHttpEnvelopeRealTomcatTest` **只断言 HTTP 200** ⇒ **从未发现**信封里的业务码是 5001
⇒ ⇒ 「**HTTP 200 ≠ 业务成功**」这条已在本轮台账与用例注释中写明（**HTTP 头面不受影响** ⇒ U-3 的判据仍成立）。

##### ④ 判据有牙齿（**MUT-F** · 变异探针）

| 步骤 | 读数 |
|---|---|
| **变异** | 在 `ArticleCorsFilter#setCorsHeaders` 凭空多写 1 个响应头（`X-Mutation-Probe`）<br>⚠️ **唯一性前置断言**：片段在目标文件里**恰好命中 1 次**（否则读数不可归因 ⇒ 脚本 `ABORT`） |
| **变异态** | `Tests run: 2, Failures: 2, Errors: 0, Skipped: 0` ⇒ **两条用例都红**<br>逐字失败断言：<br>`🔴 POST /v1/article 的**应用层**响应头名单必须**逐条**一致（多一个 / 少一个都是字节面差异）；期望=[Access-Control-Allow-Headers, Access-Control-Allow-Methods, Access-Control-Allow-Origin, Access-Control-Expose-Headers, Access-Control-Max-Age, Content-Type, Traceparent, Vary]；实际=[…, Vary, X-Mutation-Probe]`<br>`🔴 200 形态（GET /v1/articles） 的**应用层**响应头名单必须**逐条**一致…；实际=[…, Vary, X-Mutation-Probe]` |
| **还原** | 文件备份 + `cp`（**不用 `git checkout`**）⇒ 还原后与备份**逐字节一致** |
| **还原态** | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` ⇒ **同条件复跑为绿** |

⇒ ⇒ **「凭空多一个响应头 ⇒ 立刻红」这条判据不是假绿** ✔（对应类注释 ⑦ 的第 1 条自证）

##### ⑤ U-11 剩余 1/6 · `uploadFile` 真 MinIO 链路（`storage/ArticleUploadFileRealMinioTest` · **3 例**）

**产物**：新测试类 `service/article/src/test/java/com/rag/article/storage/ArticleUploadFileRealMinioTest.java`
（继承 `MinioSpringTestBase` ⇒ **真 MinIO 容器 ＋ 真 PG** ⇒ **不额外多起一次 Spring 装配**）。

| 用例 | 断言 |
|---|---|
| **png 上传** | 对象**真的进了 MinIO**（`stat` 拿到的大小 ＋ 内容**逐字节**相等 ＋ `contentType=image/png` ＋ `Cache-Control: public, max-age=31536000, immutable`） |
| **无扩展名** | 回落 `application/octet-stream` |
| **多点文件名** | 取**最后一个**点之后的扩展名 |

**B 出处**（期望值来源 = **E-2 级** · 源码级逐字读出）：B 侧 `article-rpc/…/service/ArticleDomainService.java:852-858`（`uploadFile`）/ `:859`（扩展名）/ `:861`（contentType）/ `:871-872` / `:884-886`（缓存头）/ `:966-975`（`guessContentType`）
⇒ ⚠️ **B 侧对 `uploadFile` 没有任何测试** ⇒ ⇒ 与 U-5 / U-12 同款：**不得单独拿它当「B 侧活体证据」**（U-1 的对拍探针才是）

##### ⑥ 本轮读数（**逐字引用模块级汇总行** · 日志 `/tmp/article-final-verify3.log`）

| 项 | 读数 |
|---|---|
| **article-service 模块级回归** | `Tests run: 157, Failures: 0, Errors: 0, Skipped: 0` ＋ `BUILD SUCCESS`（`Total time: 01:37 min`） |
| `ArticleHttpEndpointHeaderPlaneRealTomcatTest` | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` |
| `ArticleUploadFileRealMinioTest` | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| **common 模块（同轮）** | `Tests run: 234, Failures: 0, Errors: 0, Skipped: 0` |
| **守门 A**（`check-arch-invariants.py`） | `PASS 10 · FAIL 0 · N/A 2 · PENDING 1` · **EXIT=0**（A9 = **138/138** 个 DTO immutable · A13 = **31/31** 个 Lombok 类均带展开块） |
| **守门 B**（`check-docs-vs-tree.py`） | ✅ 全部相符合（File_Tree 双向 · 幂等 · 路径 · 落点计数 · 包名）· **EXIT=0** |
| **守门 C**（`check-mapping-coverage.py`） | **两个 ROOT 都 EXIT=0**：工作树口径 article **C 63/63 · B 83/83** · 全仓 **642/642 + 752/752**；主工作区口径 article C 58/58 · 全仓 **637/637 + 752/752** |

> ⚠️ **本轮踩过的坑（已修，勿重犯）**：
> ① **裸 HTTP/1.1 请求必须带 `Content-Length`** ⇒ 否则 Tomcat 按「无 body」处理 ⇒ 假 400；
> ② **测「解析失败 400」必须带合法 token** ⇒ 鉴权 filter 在 body 解析**之前** ⇒ 无 token 先 401；
> ③ **裸报文必须解 chunked**（body 末尾是 `0\r\n\r\n` ⇒ 按「最后一个 \r\n\r\n」切会切到块终止符 ⇒ body 变空）；
> ④ **生成器对 `proto/` 有盲区** ⇒ `gen-file-tree.py` 会把 `docs/File_Tree/proto.md` 改坏 ⇒ **跑完必须 `git checkout -- docs/File_Tree/proto.md` 还原**；
> ⑤ **`check-mapping-coverage.py` 的 `ROOT` 硬编码主工作区** ⇒ 必须**内存补丁**指工作树；且补丁时**要把 `sys.argv` 复位**（否则 ROOT 路径会被当成「域名」⇒ 跑出 `0/0` 的假绿）。

### 11.6.5 诚实边界（**表内没有的缺口**）
> ⚠️ **「承接表 83/83 有行」≠「迁移完成」** —— 以下缺口**不出现在任何一行里**（元命题在表外）：

| # | 缺口 | 为什么表里看不到 | 怎么才能闭合 |
|---|---|---|---|
| **U-1** | **双侧活体对拍（E-4）** | 本轮是「B 侧**期望值移植**到 C 侧」的探针 ⇒ 期望值**不是本域自拟的**，但**证不了「B 侧现在就是这个行为」** | ✅ **本轮已闭合**（2026-11-03 补做）—— 探针落 `tools/parity-probe/article/`（`run.sh` + `probe-isolation.sh` + `mutate-parity.sh` + `expected.txt` + `README.md`）<br>⇒ **B 侧 Java 栈（Boot 4 · 本地现成镜像 `sea/article-api:latest` 与 `sea/article-rpc:latest`）↔ C 侧 Java 栈（Boot 3.5 · 本仓 fat jar）同时起服务** ⇒ 同一批请求 ⇒ **`xxd` 逐字节比对**（body hex + 状态码 + Content-Type）⇒ **12/12 条逐字节一致**<br>⇒ 基线**实跑之后**才冻结（sha256 三轮一致）⇒ **3/3 变异精确转红**（改 C 侧库 / 改 B 侧库 / 改基线）+ 基线已还原 ⇒ **探针不是恒绿**<br>⚠️ **不可比面（明确排除，非隐藏）**：gRPC 面（B 双进程 vs C 单进程 ⇒ **拓扑产物**）· `author_name` 的 RPC 成功支（C 侧无 `UserRpcClient` ⇒ 缺口 **G-2**）· 写路径（雪花 ID 不可逐字节）· `/actuator/*`<br>📌 详见 §11.6.4.4 |
| **U-2** | **跨域 gRPC 链路**（article → message-rpc） | 承接行只管「有没有实现」，不管「对端在不在」 | ✅ **本轮已闭合**（2026-11-03 补做）—— **真 Nacos**（`127.0.0.1:49848` · 容器 `Sea-nacos`）**＋ 真对端 message-rpc 容器**（`127.0.0.1:8086` · B 侧部署镜像，只读）⇒ 生产类 `MessageRpcClient` 打**生产地址** `discovery:///message-rpc`（`application.yml:199`）<br>⇒ 🔴 **证据锚在「对端自己写的库行 ＋ 对端自己的 Prometheus 计数」上** ⇒ **不锚在「返回 true」上**（那证明不了对端真处理了 —— 生产类按 B 侧语义吞异常返 false）<br>⇒ **正例 2 例 ＋ 反例 1 例**（反例 = 对端**不在**注册表里 ⇒ 必须返 false 且**对端库零行** ⇒ 证明正例的落库断言**不是恒真**）⇒ `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`（**两遍连跑均绿**）<br>⚠️ **默认回归不含这 3 例**（类级 `@Tag("external-env")` ＋ 本域 pom 按**类别**排除 ⇒ 不污染其它域的回归读数）⇒ 显式跑：`mvn -o -pl service/article -am test -Pexternal-env`<br>⚠️ 本轮**顺带发现**一处 L1 漂移并**只登记不修**（**D-14** · Nacos `namespace` 默认值）⇒ 归 `global` 收口域<br>📌 详见 §11.6.4.5 |
| **U-3** | **HTTP 响应头字节面** | 「有端点」与「端点响应头逐字节相同」是两件事 | ✅ **本轮已闭合**（2026-11-03 第三轮补做）—— 新增 `http/ArticleHttpEndpointHeaderPlaneRealTomcatTest`（**2 例** · **真 Tomcat + 裸 socket 逐字节**）⇒ ① **7 个端点各自**的成功响应头名单**逐条**钉死（应用层精确 ＋ 容器层有界：分帧头 `Content-Length` / `Transfer-Encoding` **恰有一个**）② **200 / 400 / 401 / 404 四种形态**各自的名单钉死 ＋ 401 **body 零字节** ＋ 404 **body 逐字节** = `"404 page not found\n"`（19 字节）<br>⇒ 🔴 **过程中坐实三条根因**：**1xx 前缀**（共享层探针写 103 ⇒ **两侧同款**，已签字偏离 SD-2026-10-25-04 ⇒ 照抄 B 侧 `FavoriteApiRealTomcatGateTest:609-610` 的解析惯例）· **chunked**（**两侧同款**，B 侧 `:613-614` 已记载 ⇒ 照抄其解块逻辑）· **404 的 3 条 Vary**（🔴 **C 侧多 2 条** ⇒ **D-15**，只登记不改码）<br>⇒ **判据有牙齿**：**MUT-F**（凭空多写 1 个响应头 ⇒ **两条用例都红** ⇒ 还原后**复跑为绿**）<br>📌 详见 §11.6.4.6 |
| **U-4** | **源码变异证据**（断言不是假绿） | 绿测试只证明「测过了」，不证明「测的是那个现象」 | ✅ **本轮已闭合**（2026-11-03 补做）—— **MUT-A / MUT-B / MUT-C 三条源码变异全部「变异红 / 还原绿」**，读数见 §11.6.4.1<br>⚠️ **效力边界**：这**只证明那三条断言不是假绿** ⇒ **不外推**到其余 149 例 |
| **U-5** | **MinIO 真链路** | `client/ArticleObjectStorageClient` 的实现已抄，但 put/get 未对真 MinIO 实跑 | ✅ **本轮已闭合**（2026-11-03 补做）—— 新增 `storage/` 包（**4 个文件**）：`TestMinio`（真 MinIO 单例容器底座）+ `ArticleObjectStorageClientRealMinioTest`（**7 例**：`ensureBucketWithPublicRead` / `put` / `get` / `readString` / `buildPublicFileURL`）+ `MinioSpringTestBase` + `ArticlePublicLegacyMarkdownRealMinioTest`（**3 例**）<br>⇒ **模块级汇总行 = `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`**（`Skipped: 0` 成立 ⇒ **无** `@EnabledIfDockerAvailable`）<br>⚠️ **期望值来源要说清**：**B 侧对 `ArticleObjectStorageClient` 没有任何测试**（`article-rpc/src/test` 里命中处**全是 mock**）⇒ 期望值只能从 B 侧**实现源码**逐字读出（D16 下唯一可用 oracle）⇒ 这组用例是 **E-2 级**（源码级对照），**不是** E-4 ⇒ ⇒ **不得单独拿它当「B 侧活体证据」**（U-1 的对拍探针才是）<br>⚠️ **B-06 的措辞已按实测收敛**（见 §11.5.3 末的说明） |
| **U-6** | **`listPublicArticles` 的「同一快照」并发语义** | P0 批次验的是**注解属性真被运行期使用**（`AnnotationTransactionAttributeSource` 解析出 `readOnly=true` / `ISOLATION_REPEATABLE_READ`）+ count/page 同口径的**行为**断言 ⇒ **并发编辑/撤回下的快照一致性未验证** | ✅ **本轮已闭合**（2026-11-03 补做）—— 新增 `domain/service/ArticlePublicListSnapshotRealPgTest`（**2 例**）：① **运行期事实**（探针在事务内直接问 PG：{@code SHOW transaction_isolation} = `repeatable read`、`SHOW transaction_read_only` = `on`）② **行为后果**（用 MyBatis 拦截器在 `countPublic` 与 `selectPublicPage` **之间**插入一次并发撤回 ⇒ 页内容仍看得到 ⇒ 同一快照）<br>⚠️ **同条件对照**也在同一测试类里（② 之外那条）：同一套 mapper + 同一个撤回在 **READ COMMITTED** 下**确实会撕裂** ⇒ 证明判据有区分力，不是恒绿<br>📌 详见 §11.6.4.2 |
| **U-7** | **并发降级 + 锁序探针** | B 侧 `ArticleTransactionWiringTest` 的 CASE-R12（并发降级+删除）与 U-2（`pg_stat_activity` 锁序探针）**未移植** | ✅ **本轮已闭合**（2026-11-03 补做）—— 新增 `domain/service/ArticleConcurrencyRealPgTest`（**2 例**）：① CASE-R12「同篇文章并发降级 + 并发删除」⇒ 无死锁（SQLSTATE 40P01）+ **恰好一个成功** + 指针版本**单调到 2** + 域事件**只有 1 条** ② **锁序实测**（同时扣住两把锁 ⇒ 看被阻塞后端执行的是哪条 SQL）<br>📌 C 侧**比 B 侧更精确**的一处：不用 `application_name` 过滤，改用 **`pg_blocking_pids(pid)`**（直接问 PG「谁在挡我」）⇒ 不误抓别的会话，也不用改共享的 `support/TestPostgres`<br>📌 详见 §11.6.4.2 |
| **U-8** | **DDL 断言未直接做**（3 个） | B 侧 `flywayMigrationsActuallyRan` / `pgTriggerRejectsRevisionMutation` / `publicationCheckConstraintsAreEnforced` 未移植 ⇒ 本轮靠 `ArticleSchemaGate` **隐式**依赖，但**没直接断言** | ✅ **本轮已闭合**（2026-11-03 补做）—— `mapper/ArticleSchemaAndTimestampRealPgTest` 的 ①②③：5 张表 + `article_revision_immutable` 触发器**真的存在** / 触发器**真的拒绝** UPDATE 与 DELETE / `article_publication` 的 `state` 与 `pointer_version` **CHECK 真的拦得住**<br>⚠️ 特意核过：`article_publication` **没有**指向 `article` 的外键 ⇒ 那两条失败**只可能**来自 CHECK 约束本身（否则会「假通过」） |
| **U-9** | **`updated_at` 自动推进 / `view_count` 自增不推进 `updated_at`** | B 侧 S-3 / S-4 未移植 | ✅ **本轮已闭合**（2026-11-03 补做）—— 同上测试类的 ④⑤：`updateById` 推进 `updated_at`（S-3）/ `view_count` 自增**不**推进（S-4 · Go `UpdateColumn` 的 `SkipHooks` 语义）<br>⚠️ 踩过一个**假红**：MP 的 `updateFill` 会**原地改写**传入实体的 `updatedAt` ⇒ 必须在 `updateById` **之前**从库里取 `before`，否则 `isAfter` 恒 false |
| **U-10** | **软删除可见性 split 未单独成例** | `selectById` vs `selectByIdUnscoped` vs `ForUpdate` 只被 T3 间接用到 | ✅ **本轮已闭合**（2026-11-03 补做）—— 同上测试类的 ⑥：`selectById` 看不到已删行 / `selectByIdUnscoped` **仍能看到**（迟到同步结果的唯一入口）/ 二次删除是 **no-op**（影响 0 行，不是错误） |
| **U-11** | **其余写路径未覆盖** | `retractAndUpdate` / `retractAndDelete` / `queueSyncOutboxWithLock`（T5 只覆盖 `acceptSyncResult`）· `createArticle` / `deleteArticle` / `uploadFile`（T7 只覆盖 get/update） | ✅ **本轮已闭合 6/6**（2026-11-03 第三轮补完）—— 第二轮已补 5/6（`domain/service/ArticleWritePathRealPgTest` **5 例**：创建（落库 `REVIEWING` + `ext_info` **8 个初始键** + 推审核消息 key=articleId）/ 删除（**软删** + 撤指针 + 两条 outbox）/ 撤回发布（行**保留** + 撤指针）/ 撤回并删除 / 审核入队（**两层幂等** ⇒ 第二次同 eventId 被事务内复查挡掉））<br>⇒ 🔴 **第三轮补上剩余 1/6 = `uploadFile`** —— 新增 `storage/ArticleUploadFileRealMinioTest`（**3 例** · **真 MinIO 容器 + 真 PG** · 继承 `MinioSpringTestBase` ⇒ 不多起装配）：png 上传 ⇒ 对象**真的进了 MinIO**（`stat` 大小 ＋ 内容**逐字节**相等 ＋ `contentType=image/png` ＋ `Cache-Control: public, max-age=31536000, immutable`）/ 无扩展名 ⇒ 回落 `application/octet-stream` / 多点文件名 ⇒ 取**最后一个**点之后的扩展名<br>⚠️ **期望值来源同 U-5 / U-12**：**B 侧对 `uploadFile` 没有任何测试** ⇒ 期望值从 B 侧**实现源码**逐字读出（`article-rpc/…/service/ArticleDomainService.java:852-858 / :859 / :861 / :871-872 / :884-886 / :966-975`）⇒ **E-2 级**，**非** E-4 ⇒ ⇒ **不得单独拿它当「B 侧活体证据」** |
| **U-12** | **`ArticlePublicMapper.selectPublicById` 的 legacy 正文回落** | `readLegacyMarkdown` 需 MinIO 桩 | ✅ **本轮已闭合**（2026-11-03 补做）—— `storage/ArticlePublicLegacyMarkdownRealMinioTest`（**3 例**）⇒ **真 MinIO 里放对象** ⇒ legacy 支**真的**去读并真的读到内容（不再需要桩）<br>⚠️ **期望值来源同 U-5**：B 侧 `ArticlePublicDomainService.java:129-136`（legacy 支）+ `:311-317`（`readLegacyMarkdown`）+ `ArticlePublicMapper.java:87-93`（谓词含 legacy 支）+ B 侧测试 `ArticlePublicDomainServiceViewTest.java:114-131` ⇒ **E-2 级**（源码级 + B 侧测试期望值沿用），**非** E-4 |
| **U-13** | **错误码映射链（api 层）** | `ArticleExceptions` 的 gRPC code ↔ `contract/ErrCodes` 业务码的映射链 | ✅ **本轮已闭合**（2026-11-03 补做）—— 新增 `http/ArticleErrorCodeMappingRealTomcatTest`（**4 例** · **真 Tomcat + 真 PG** · 让领域层**真的**失败）：NOT_FOUND→**1002** / PERMISSION_DENIED→**1006** / INTERNAL→**5001** / 未覆盖的码→落 default→**1015**<br>⚠️ 两条 DB 失败用例用**改表名**制造**真** SQL 错误（`42P01`）⇒ 真 `DataAccessException` ⇒ 才验得到「异常翻译有没有到消费者」<br>📌 详见 §11.6.4.2 |

> #### ✅ §11.6.5 十三条缺口的最终状态（2026-11-03 **第三轮** 收口时点）
>
> | 状态 | 条目 | 说明 |
> |---|---|---|
> | ✅ **已闭合** | **U-1 · U-2 · U-3 · U-4 · U-5 · U-6 · U-7 · U-8 · U-9 · U-10 · U-11 · U-12 · U-13**（**13 条 · 全闭合**） | 各自有**独立小节**给出产物、读数（逐字）、证伪手段 ⇒ §11.6.4.1 ~ §11.6.4.6 |
>
> ⚠️ **「表内归零」≠「命题闭合」** —— 本表是**台账自己**对自己 13 条的结算，**不是**「迁移已完美」的声明。仍然存在、且**故意不写进任何一行**的东西：
> ① **U-2 那 3 例默认回归不含**（环境依赖）⇒ 「跑绿」**不代表**跨域链路被验过 ⇒ 必须显式 `-Pexternal-env`；
> ② **§11.5.2 的 D-09 / D-10 / D-11** 是**共享层收口未完成**（`NOT_VERIFIED`）⇒ 它们**不是**本域能关的；
> ③ **D-14**（Nacos `namespace` 默认值）是**跨域**漂移 ⇒ 归 `global` 收口域；
> ④ **D-15**（404 多 2 条 `Vary`）是 **Spring 版本副产物**（框架限制）⇒ **已登记、只登记不改码** ⇒ 归 `global` 收口域（同一形态会出现在其它域）；
> ⑤ **U-1 的不可比面**（gRPC 面 / `author_name` 的 RPC 成功支 / 写路径 / `/actuator/*`）**明确排除**，不是忘了；
> ⑥ **B-01 ~ B-10** 是 B 侧既有缺陷 ⇒ 照抄不修（D16）；
> ⑦ **U-3 的容器层分帧头**（`Content-Length` vs `Transfer-Encoding`）**刻意不钉具体是哪个** ⇒ 只钉「恰有一个」⇒ **分帧方式不是跨侧契约**（B 侧同为 Tomcat，且 B 侧测试自己就记载了 chunked）。
>
> 📌 **一句话口径**：本域 **83 行承接表全有据 · 13/13 条表外缺口已闭 · 2 条跨域待 `global` 收口（D-14 / D-15）· 3 条待共享层收口（D-09 ~ D-11）** ⇒ **可进 review**，**不等于**「无缺口」。
