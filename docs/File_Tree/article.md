# article（article 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/article` |
| artifactId | `article-service` |
| 根包 | `com.rag.article` |
| Go 出处 | `service/article/` |
| 说明 | api + rpc 合并后的单一 Spring Boot 应用，**域内包已压平为单层技术角色包树**（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `wiring` / `exception`）；域内错误契约与事件 DTO 在 `com.rag.<域>.contract` 包；**`dto/` 内按业务对象再拆一级子包**（D11：全仓 136 个 DTO → 26 个业务对象子包，判据 = Go logic 用例的 `types.Xxx` 引用图 + 类型所在聚合） |

## 二、依赖

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-validation`
- `org.springframework.boot:spring-boot-starter-actuator`
- `io.micrometer:micrometer-registry-prometheus`
- `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery`
- `net.devh:grpc-server-spring-boot-starter`
- `net.devh:grpc-client-spring-boot-starter`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `org.postgresql:postgresql`
- `com.rag:proto`
- `com.rag:common`
- `org.springframework.boot:spring-boot-kafka`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── article/
│   │   │               ├── advice/
│   │   │               │   ├── ArticleApiExceptionHandler.java
│   │   │               │   └── ArticleApiResponseAdvice.java
│   │   │               ├── client/
│   │   │               │   └── MessageRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── ArticleApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── ArticleKafkaChannelValidator.java
│   │   │               │   ├── ArticleMetaObjectHandler.java
│   │   │               │   ├── ArticleRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── ArticleSyncEvent.java    # Go 出处：article/rpc/internal/mqs/article_sync_types.go。
│   │   │               │   ├── CommunityArticleEvent.java    # Go 出处：article/rpc/internal/mqs/community_article_event.go。
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   └── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               ├── controller/
│   │   │               │   └── ArticleController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── ArticleDomainOutboxEntity.java
│   │   │               │   │   ├── ArticleEntity.java
│   │   │               │   │   ├── ArticlePublicationEntity.java
│   │   │               │   │   ├── ArticleRevisionEntity.java
│   │   │               │   │   └── ArticleSyncOutboxEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── ArticleAtomicService.java
│   │   │               │       ├── ArticleDomainService.java
│   │   │               │       └── ArticlePublicDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── article/
│   │   │               │   │   ├── Article.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── CreateArticleReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── CreateArticleResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── DeleteArticleReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── DeleteArticleResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── GetArticleReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── GetArticleResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── ListArticlesReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── ListArticlesResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── UpdateArticleReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── UpdateArticleResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── upload/
│   │   │               │       ├── UploadImageReq.java    # Go 侧同名 struct 存在 —— RAG 形态改用 `@RequestParam MultipartFile`（multipart 表单）承载，属 L4 idiomatization；本 DTO 若保留则用于承载 multipart 之外的其余字段。
│   │   │               │       └── UploadImageResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── ArticleExceptions.java
│   │   │               ├── grpc/
│   │   │               │   └── ArticleGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   └── JwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── ArticleDomainOutboxMapper.java
│   │   │               │   ├── ArticleMapper.java
│   │   │               │   ├── ArticlePublicMapper.java
│   │   │               │   ├── ArticlePublicationMapper.java
│   │   │               │   ├── ArticleRevisionMapper.java
│   │   │               │   └── ArticleSyncOutboxMapper.java
│   │   │               ├── metrics/
│   │   │               │   └── ArticleRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── ArticleApiService.java
│   │   │               │   └── ArticleApiSupport.java
│   │   │               ├── wiring/
│   │   │               │   ├── ArticleSchemaGate.java
│   │   │               │   ├── MinioBucketBootstrap.java
│   │   │               │   ├── NacosRegistration.java
│   │   │               │   └── SnowflakeBootstrap.java
│   │   │               └── ArticleServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── article/
│                       └── ArticleServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **前缀是 `/v1`** —— 唯一不带域名前缀的域。
- **method 混合**：Get3·Post2·Put1·Delete1 ⇒ **不是**统一 POST。
- **指标无命名空间**：`article_total` 等是裸名（无 `<subsystem>_` 前缀）⇒ 与其它域形态不一致，照抄。
- **唯一不走信封的端点**：`POST /v1/upload`（multipart，字段名固定 `image`）⇒ 返回裸 `{"image_url":…}`。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
