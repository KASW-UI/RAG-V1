# follow（follow 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/follow` |
| artifactId | `follow-service` |
| 根包 | `com.rag.follow` |
| Go 出处 | `service/follow/` |
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

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── follow/
│   │   │               ├── advice/
│   │   │               │   ├── FollowApiExceptionHandler.java
│   │   │               │   └── FollowApiResponseAdvice.java
│   │   │               ├── client/
│   │   │               │   └── UserRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── FollowApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── FollowRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   └── GrpcErrorMapper.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               ├── controller/
│   │   │               │   └── FollowController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── BlockRelationEntity.java
│   │   │               │   │   └── FollowRelationEntity.java
│   │   │               │   └── service/
│   │   │               │       └── FollowDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── list/
│   │   │               │   │   ├── FollowListReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── FollowUserListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── recommend/
│   │   │               │   │   ├── RecommendListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── RecommendUser.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── relation/
│   │   │               │       ├── RelationActionReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       └── RelationActionResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── grpc/
│   │   │               │   └── FollowGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   ├── FollowBlacklistInterceptor.java
│   │   │               │   └── FollowJwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── BlockRelationMapper.java
│   │   │               │   ├── FollowRelationMapper.java
│   │   │               │   └── GoStyleLimitOffset.java
│   │   │               ├── metrics/
│   │   │               │   ├── FollowApiMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   └── FollowRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── FollowApiSupport.java
│   │   │               │   ├── FollowBlockTransactionService.java
│   │   │               │   ├── FollowQueryService.java
│   │   │               │   ├── FollowRecommendationService.java
│   │   │               │   ├── FollowRelationService.java
│   │   │               │   ├── FollowRpcSupport.java
│   │   │               │   └── FollowUserExistenceService.java
│   │   │               ├── wiring/
│   │   │               │   └── NacosRegistration.java
│   │   │               └── FollowServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── follow/
│                       └── FollowServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **错误文案是英文**（`"service busy"`）—— 与 article/comment/favorite/like/user 的中文文案不同。
- **api 层用例不合并**：8 个用例中 2 个带专属业务规则（`forbidden_scope`）⇒ 拆 `FollowRelationService` + `FollowQueryService`。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
