# comment（comment 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/comment` |
| artifactId | `comment-service` |
| 根包 | `com.rag.comment` |
| Go 出处 | `service/comment/` |
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
│   │   │           └── comment/
│   │   │               ├── advice/
│   │   │               │   ├── CommentApiExceptionHandler.java
│   │   │               │   └── CommentApiResponseAdvice.java
│   │   │               ├── client/
│   │   │               │   ├── ArticleRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               │   └── MessageRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── CommentApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── CommentRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── CommentKafkaMsg.java    # Go 出处：service/comment/rpc/common/types/kqueue.go 的 `kqtypes.CommentKafkaMsg`。⚠️ **跨服务 Kafka 消息契约 = L1**（字段名与顺序即消费方解析依据）。
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   └── GrpcErrFactory.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               ├── controller/
│   │   │               │   └── CommentController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── CommentContentEntity.java
│   │   │               │   │   ├── CommentDomainFactOutboxEntity.java
│   │   │               │   │   ├── CommentFactStreamEntity.java
│   │   │               │   │   ├── CommentIndexEntity.java
│   │   │               │   │   ├── CommentLikeEntity.java
│   │   │               │   │   ├── ReportRecordEntity.java
│   │   │               │   │   └── SubjectEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── CommentDomainFactService.java
│   │   │               │       ├── CommentDomainService.java
│   │   │               │       └── CommentTransactionalService.java
│   │   │               ├── dto/
│   │   │               │   └── comment/
│   │   │               │       ├── CommentItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── CreateCommentReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── CreateCommentResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── DeleteCommentReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── DeleteCommentResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── GetCommentReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── GetCommentResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── LikeCommentReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── LikeCommentResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── ManageCommentReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── ManageCommentResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── ReportCommentReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── ReportCommentResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── SubjectInfo.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── UpdateSubjectStateReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       └── UpdateSubjectStateResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── CommentExceptions.java
│   │   │               ├── grpc/
│   │   │               │   ├── CommentGrpcService.java
│   │   │               │   └── PbCommentServiceAlias.java
│   │   │               ├── interceptor/
│   │   │               │   └── CommentJwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── CommentContentMapper.java
│   │   │               │   ├── CommentDomainFactOutboxMapper.java
│   │   │               │   ├── CommentFactStreamMapper.java
│   │   │               │   ├── CommentIndexMapper.java
│   │   │               │   ├── CommentLikeMapper.java
│   │   │               │   ├── ReportRecordMapper.java
│   │   │               │   └── SubjectMapper.java
│   │   │               ├── messaging/
│   │   │               │   ├── CommentAuditListener.java
│   │   │               │   ├── CommentAuditMessage.java
│   │   │               │   ├── CommentAuditPublisher.java
│   │   │               │   └── CommentKafkaTopics.java
│   │   │               ├── metrics/
│   │   │               │   └── CommentRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── CommentApiService.java
│   │   │               │   ├── CommentApiSupport.java
│   │   │               │   ├── CommentAuthorityLookupService.java
│   │   │               │   ├── CommentCacheService.java
│   │   │               │   ├── CommentDeliveryValidator.java
│   │   │               │   ├── CommentFactDeliveryService.java
│   │   │               │   ├── CommentRedisKeys.java
│   │   │               │   └── CommentSensitiveFilter.java
│   │   │               ├── wiring/
│   │   │               │   ├── CommentAuthorityEndpointConfig.java
│   │   │               │   ├── CommentFactDispatchScheduler.java
│   │   │               │   ├── CommentFactProcessLogging.java    # 对应 Go 侧 {@code service/comment/rpc/cmd/fact-dispatch/main.go}（身份 {@code rtw-comment-fact-dispatch}）
│   │   │               │   ├── CommentSchemaGate.java
│   │   │               │   └── NacosRegistration.java
│   │   │               └── CommentServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── comment/
│                       └── CommentServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **共享 communityfact 事件协议**（与 favorite/like 三方共用）。
- **Kafka 契约**：`CommentKafkaMsg`（`comment/rpc/common/types/kqueue.go`）是跨服务 L1。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
