# message（message 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/message` |
| artifactId | `message-service` |
| 根包 | `com.rag.message` |
| Go 出处 | `service/message/` |
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
│   │   │           └── message/
│   │   │               ├── advice/
│   │   │               │   ├── MessageApiExceptionHandler.java
│   │   │               │   └── MessageApiResponseAdvice.java
│   │   │               ├── client/
│   │   │               │   ├── AdminRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               │   └── UserRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── MessageApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── MessageProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   └── GrpcErrorMapper.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               ├── controller/
│   │   │               │   ├── MessageAdminController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               │   └── MessageController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── ConversationEntity.java
│   │   │               │   │   ├── ConversationMessageEntity.java
│   │   │               │   │   └── NotificationEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── MessageAtomicService.java
│   │   │               │       └── MessageDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── chat/
│   │   │               │   │   ├── AdminSendChatReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── SendChatResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── UserSendChatReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── common/
│   │   │               │   │   ├── EmptyReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── PageReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── conversation/
│   │   │               │   │   ├── ChatMessageItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── ConversationItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── ConversationListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── ConversationMessagesReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── ConversationMessagesResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── notification/
│   │   │               │   │   ├── AdminSendNotificationReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── AdminSendNotificationResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── MarkNotificationReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── NotificationItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── NotificationListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── read/
│   │   │               │       ├── MarkConversationReadReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       └── UnreadSummaryResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── MessageExceptions.java
│   │   │               ├── grpc/
│   │   │               │   └── MessageGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   ├── MessageAdminJwtAuthInterceptor.java
│   │   │               │   └── MessageJwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── ConversationMapper.java
│   │   │               │   ├── ConversationMessageMapper.java
│   │   │               │   ├── GoStylePagination.java
│   │   │               │   └── NotificationMapper.java
│   │   │               ├── metrics/
│   │   │               │   ├── MessageApiMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   ├── MessageMetrics.java
│   │   │               │   └── MessageRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── MessageApiService.java
│   │   │               │   ├── MessageApiSupport.java
│   │   │               │   └── MessageRpcSupport.java
│   │   │               ├── wiring/
│   │   │               │   └── NacosRegistration.java
│   │   │               └── MessageServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── message/
│                       └── MessageServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **两组 JWT**：`/message/v1` 用 UserAuth、`/message/v1/admin` 用 **AdminAuth** ⇒ 两套 secret、两套 interceptor、两套 Properties。
- **错误文案是英文**。
- **rpc 侧无 `*RpcProperties`**，配置块名 `MessageProperties`。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
