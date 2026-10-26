# favorite（favorite 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/favorite` |
| artifactId | `favorite-service` |
| 根包 | `com.rag.favorite` |
| Go 出处 | `service/favorite/` |
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
- `org.springframework.boot:spring-boot-starter-data-redis`
- `org.springframework.boot:spring-boot-kafka`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── favorite/
│   │   │               ├── advice/
│   │   │               │   ├── FavoriteApiExceptionHandler.java
│   │   │               │   └── FavoriteApiResponseAdvice.java
│   │   │               ├── client/
│   │   │               │   ├── ArticleRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               │   └── UserRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── FavoriteApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── FavoriteRpcLabels.java
│   │   │               │   ├── FavoriteRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   └── GrpcErrorMapper.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               ├── controller/
│   │   │               │   └── FavoriteController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── FavoriteFactOutboxEntity.java
│   │   │               │   │   ├── FavoriteFolderEntity.java
│   │   │               │   │   ├── FavoriteItemEntity.java
│   │   │               │   │   └── FavoriteLegacyFactMarkerEntity.java
│   │   │               │   ├── model/
│   │   │               │   │   └── FavoriteAuthorityFact.java
│   │   │               │   └── service/
│   │   │               │       └── FavoriteDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── favorite/
│   │   │               │   │   ├── CreateFavoriteReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── CreateFavoriteResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── DeleteFavoriteReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── DeleteFavoriteResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── FavoriteItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── GetFavoritesByFolderReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── GetFavoritesByFolderResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── folder/
│   │   │               │       ├── CreateFavoriteFolderReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── CreateFavoriteFolderResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── DeleteFavoriteFolderReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── DeleteFavoriteFolderResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── FavoriteFolder.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── GetFavoriteFoldersReq.java    # Go 侧同名 struct 存在（空 struct）—— RAG 形态改用 `assertGoEmptyStructBody()` 手工复刻 Go 的「GET 端点也解析并校验 body」行为，属 L4。
│   │   │               │       ├── GetFavoriteFoldersResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── UpdateFavoriteFolderNameReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       └── UpdateFavoriteFolderNameResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── FavoriteExceptions.java
│   │   │               ├── grpc/
│   │   │               │   └── FavoriteGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   └── FavoriteJwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── FavoriteFactOutboxMapper.java
│   │   │               │   ├── FavoriteFolderMapper.java
│   │   │               │   ├── FavoriteItemMapper.java
│   │   │               │   └── FavoriteLegacyFactMarkerMapper.java
│   │   │               ├── metrics/
│   │   │               │   └── FavoriteRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── FavoriteApiService.java
│   │   │               │   ├── FavoriteApiSupport.java
│   │   │               │   ├── FavoriteArticleSnapshotResolver.java
│   │   │               │   ├── FavoriteAssertionSchemaResolver.java
│   │   │               │   ├── FavoriteAuthorityLookupService.java
│   │   │               │   ├── FavoriteDeliveryValidator.java
│   │   │               │   ├── FavoriteFactDispatchService.java
│   │   │               │   ├── FavoriteFactEnvelopeFactory.java
│   │   │               │   ├── FavoriteFactWriteService.java
│   │   │               │   ├── FavoriteRpcSupport.java
│   │   │               │   └── FavoriteUserGuard.java
│   │   │               ├── wiring/
│   │   │               │   ├── FavoriteAuthorityEndpointConfig.java    # Go 出处
│   │   │               │   ├── FavoriteFactDispatchScheduler.java    # Go 出处
│   │   │               │   ├── FavoriteFactProcessLogging.java
│   │   │               │   ├── FavoriteNacosRegistration.java
│   │   │               │   ├── FavoriteSchemaGate.java
│   │   │               │   └── FavoriteSubjectRefGate.java
│   │   │               └── FavoriteServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── favorite/
│                       └── FavoriteServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **共享 communityfact 事件协议**。
- **错误契约形态独有**：本域是唯一「只有常量 + `codeMsg` + 反查索引 + `GRPCError` + `BizCodeFromError`，无 `CodeError` 类型」的域。
- **注意**：`GET /favorite/v1/folder/list` 是 GET 端点，但 Go 仍解析并校验 body ⇒ 必须保留（否则非法 body 会从 5003 放过成 200）。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
