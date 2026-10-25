# like（like 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/like` |
| artifactId | `like-service` |
| 根包 | `com.rag.like` |
| Go 出处 | `service/like/` |
| 说明 | api + rpc 合并后的单一 Spring Boot 应用，**域内包已压平为单层技术角色包树**（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `bootstrap` / `exception`）；域内错误契约与事件 DTO 在 `com.rag.<域>.contract` 包 |

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
│   │   │           └── like/
│   │   │               ├── advice/
│   │   │               │   ├── LikeApiExceptionHandler.java
│   │   │               │   └── LikeApiResponseAdvice.java
│   │   │               ├── bootstrap/
│   │   │               │   ├── LikeAuthorityEndpointConfig.java    # Go 出处
│   │   │               │   ├── LikeFactDispatchScheduler.java    # Go 出处
│   │   │               │   ├── LikeFactProcessLogging.java
│   │   │               │   ├── LikeSchemaGate.java
│   │   │               │   └── NacosRegistration.java
│   │   │               ├── client/
│   │   │               │   └── MessageRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── LikeApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── LikeRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── ArticleHotEvent.java    # Go 出处：service/like/rpc/internal/types/types.go。like → hot 的跨域事件
│   │   │               │   ├── CodeError.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── GrpcErrFactory.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── KafkaLikeMsg.java    # Go 出处：service/like/rpc/internal/types/types.go。like 域的 Kafka 消息载荷
│   │   │               │   └── TaskArticleProgressMsg.java    # Go 出处：service/like/rpc/internal/types/types.go。like → task 的跨域事件
│   │   │               ├── controller/
│   │   │               │   └── LikeController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── LikeConsumeInboxEntity.java
│   │   │               │   │   ├── LikeDomainFactOutboxEntity.java
│   │   │               │   │   ├── LikeFactStreamEntity.java
│   │   │               │   │   ├── LikeOutboxEventEntity.java
│   │   │               │   │   └── LikeRecordEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── LikeDomainFactService.java
│   │   │               │       └── LikeDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── GetLikeCountReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetLikeCountResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetLikeStateReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetLikeStateResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetTargetLikerListReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetTargetLikerListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserLikeListReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserLikeListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserTotalLikeReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserTotalLikeResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LikeActionReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LikeActionResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LikeCountItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LikeItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── LikeRecordItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── LikeExceptions.java
│   │   │               ├── grpc/
│   │   │               │   └── LikeGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   └── LikeJwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── LikeConsumeInboxMapper.java
│   │   │               │   ├── LikeDomainFactOutboxMapper.java
│   │   │               │   ├── LikeFactStreamMapper.java
│   │   │               │   ├── LikeOutboxEventMapper.java
│   │   │               │   └── LikeRecordMapper.java
│   │   │               ├── messaging/
│   │   │               │   ├── LikeKafkaTopics.java
│   │   │               │   └── LikeTaskProgressPublisher.java
│   │   │               ├── metrics/
│   │   │               │   ├── LikeApiMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   ├── LikeMqMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   └── LikeRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── GoCompatibleJson.java
│   │   │               │   ├── LikeActionScript.java
│   │   │               │   ├── LikeApiService.java
│   │   │               │   ├── LikeApiSupport.java
│   │   │               │   ├── LikeAuthorityLookupService.java
│   │   │               │   ├── LikeCursorIdGenerator.java
│   │   │               │   ├── LikeDeliveryValidator.java
│   │   │               │   ├── LikeFactDispatchService.java
│   │   │               │   ├── LikeKafkaPushKey.java
│   │   │               │   ├── LikeMessageDigest.java
│   │   │               │   ├── LikeMessageIngestService.java
│   │   │               │   └── LikeRedisKeys.java
│   │   │               ├── .DS_Store
│   │   │               └── LikeServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── like/
│                       └── LikeServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **⚠️ 指标名用连字符**：`like-rpc_action_total` ⇒ Prometheus 正则不允许 ⇒ 该指标不会出现在 `/metrics`。**照抄，不修正**。
- **rpc 侧另有独立 types 包**（`like/rpc/internal/types/types.go`）：`KafkaLikeMsg` / `ArticleHotEvent` / `TaskArticleProgressMsg`。
- **共享 communityfact 事件协议**。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
