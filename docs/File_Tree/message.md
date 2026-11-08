# message（message 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/message` |
| artifactId | `message-service` |
| 根包 | `com.rag.message` |
| B 侧出处 | `service/message/` |
| 说明 | api + rpc 合并后的单一 Spring Boot 应用，**域内包已压平为单层技术角色包树**（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `wiring` / `exception`）；域内错误契约与事件 DTO 在 `com.rag.<域>.contract` 包；**`dto/` 内按业务对象再拆一级子包**（D11：全仓 136 个 DTO → 26 个业务对象子包，判据 = B 侧 rpc 用例的 DTO 引用图 + 类型所在聚合） |

## 二、依赖

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-validation`
- `org.springframework.boot:spring-boot-starter-actuator`
- `io.micrometer:micrometer-registry-prometheus`
- `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery`
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `org.springframework.boot:spring-boot-starter-test`
- `org.junit.jupiter:junit-jupiter`
- `org.mockito:mockito-core`
- `io.grpc:grpc-inprocess`
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`
- `io.grpc:grpc-core`
- `net.devh:grpc-server-spring-boot-starter`
- `net.devh:grpc-client-spring-boot-starter`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `org.postgresql:postgresql`
- `com.rag:proto`
- `com.rag:common`
- `org.springframework.boot:spring-boot-starter-data-redis`
- `org.apache.commons:commons-pool2`
- `org.springframework.kafka:spring-kafka`
- `org.projectlombok:lombok`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── message/
│   │   │               ├── advice/
│   │   │               │   ├── MessageApiExceptionHandler.java    # 职责：message 域 HTTP 面的异常出口 —— 把「业务层抛出的异常」翻译成
│   │   │               │   └── MessageApiResponseAdvice.java    # 职责：message 域 HTTP 面的成功信封包装 —— 把 controller 的返回值统一包成
│   │   │               ├── client/
│   │   │               │   ├── AdminRpcClient.java    # 职责：跨域 gRPC 客户端 —— 调 admin 域的
│   │   │               │   └── UserRpcClient.java    # 职责：跨域 gRPC 客户端 —— 调 user 域的 {@code UserService.GetUser}，
│   │   │               ├── config/
│   │   │               │   ├── MessageApiProperties.java    # message 域 HTTP 层配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   ├── MessageProperties.java    # message 域业务配置 —— 对应 Go 端 {@code service/message/rpc/internal/config/config.go}：
│   │   │               │   └── WebMvcConfig.java    # message 域的 Spring MVC 装配点 —— 挂两条鉴权路径 + 注册 go-zero 对齐的 Jackson 解析规则。
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # message 域业务错误载体 —— 携带业务码 {@code code} + 业务文案 {@code msg} 的 unchecked 异常，
│   │   │               │   ├── ErrCodes.java    # message 域业务错误码枚举 —— 20 个码值的唯一真源（L1 契约 · D16：B 侧 RAG 仓为唯一判定基准）。
│   │   │               │   ├── ErrMsg.java    # message 域 code → msg 文案映射 —— 20 条文案的唯一真源（L1 契约 · 含语言）。
│   │   │               │   └── GrpcErrorMapper.java    # message 域 gRPC 错误转换工具 —— 业务码 ⇄ gRPC status 的双向转换 + status 提取。
│   │   │               ├── controller/
│   │   │               │   ├── MessageAdminController.java    # 职责：message 域管理侧的 5 个 HTTP 端点 —— 只做「解析 + 转交」，
│   │   │               │   └── MessageController.java    # 职责：message 域用户侧的 8 个 HTTP 端点 —— 只做「解析 + 转交」，
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── ConversationEntity.java    # 会话表 ORM 实体 —— {@code message_conversation} 表的 MyBatis-Plus 映射。
│   │   │               │   │   ├── ConversationMessageEntity.java    # 会话内单条消息表 ORM 实体 —— {@code message_conversation_message} 表的 MyBatis-Plus 映射。
│   │   │               │   │   └── NotificationEntity.java    # 通知表 ORM 实体 —— {@code message_notification} 表的 MyBatis-Plus 映射。
│   │   │               │   └── service/
│   │   │               │       ├── MessageAtomicService.java    # 职责：message 域多步写的事务边界 —— 6 个 {@code @Transactional} 方法。
│   │   │               │       └── MessageDomainService.java    # 职责：message 域 9 个领域用例 —— HTTP 与 gRPC 共享同一份业务规则。
│   │   │               ├── dto/
│   │   │               │   ├── chat/
│   │   │               │   │   ├── AdminSendChatReq.java    # 职责：管理员发私信请求 —— 对应 Go {@code types.go} 的 {@code AdminSendChatReq}。
│   │   │               │   │   ├── SendChatResp.java    # 职责：发私信响应 —— 对应 Go {@code types.go} 的 {@code SendChatResp}。
│   │   │               │   │   └── UserSendChatReq.java    # 职责：用户发私信请求 —— 对应 Go {@code types.go} 的 {@code UserSendChatReq}。
│   │   │               │   ├── common/
│   │   │               │   │   ├── EmptyReq.java    # 职责：空请求 / 空响应 —— 对应 Go {@code types.go} 的 {@code type EmptyReq struct{}}。
│   │   │               │   │   └── PageReq.java    # 职责：分页请求 —— 对应 Go {@code service/message/api/internal/types/types.go} 的 {@code PageReq}。
│   │   │               │   ├── conversation/
│   │   │               │   │   ├── ChatMessageItem.java    # 职责：单条私信 —— 对应 Go {@code types.go} 的 {@code ChatMessageItem}。
│   │   │               │   │   ├── ConversationItem.java    # 职责：会话列表项 —— 对应 Go {@code types.go} 的 {@code ConversationItem}。
│   │   │               │   │   ├── ConversationListResp.java    # 职责：会话列表响应 —— 对应 Go {@code types.go} 的 {@code ConversationListResp}。
│   │   │               │   │   ├── ConversationMessagesReq.java    # 职责：会话消息列表请求 —— 对应 Go {@code types.go} 的 {@code ConversationMessagesReq}。
│   │   │               │   │   └── ConversationMessagesResp.java    # 职责：会话消息列表响应 —— 对应 Go {@code types.go} 的 {@code ConversationMessagesResp}。
│   │   │               │   ├── notification/
│   │   │               │   │   ├── AdminSendNotificationReq.java    # 职责：管理员发通知请求 —— 对应 Go {@code types.go} 的 {@code AdminSendNotificationReq}。
│   │   │               │   │   ├── AdminSendNotificationResp.java    # 职责：管理员发通知响应 —— 对应 Go {@code types.go} 的 {@code AdminSendNotificationResp}。
│   │   │               │   │   ├── MarkNotificationReq.java    # 职责：标记单条通知已读请求 —— 对应 Go {@code types.go} 的 {@code MarkNotificationReq}。
│   │   │               │   │   ├── NotificationItem.java    # 职责：单条通知 —— 对应 Go {@code types.go} 的 {@code NotificationItem}。
│   │   │               │   │   └── NotificationListResp.java    # 职责：通知列表响应 —— 对应 Go {@code types.go} 的 {@code NotificationListResp}。
│   │   │               │   └── read/
│   │   │               │       ├── MarkConversationReadReq.java    # 职责：标记会话已读请求 —— 对应 Go {@code types.go} 的 {@code MarkConversationReadReq}。
│   │   │               │       └── UnreadSummaryResp.java    # 职责：未读汇总响应 —— 对应 Go {@code types.go} 的 {@code UnreadSummaryResp}。
│   │   │               ├── exception/
│   │   │               │   └── MessageExceptions.java    # message 域业务异常聚合 —— 4 个 unchecked 异常的容器类（自身不可实例化）。
│   │   │               ├── grpc/
│   │   │               │   └── MessageGrpcService.java    # 职责：message 域的 gRPC 服务端 —— 9 个 RPC 方法的 薄转发。
│   │   │               ├── interceptor/
│   │   │               │   ├── AbstractMessageJwtAuthInterceptor.java    # 职责：message 域两份 JWT 逻辑的共享基类 —— 持有全部鉴权逻辑，
│   │   │               │   ├── MessageAdminJwtAuthInterceptor.java    # 职责：管理员侧 JWT 鉴权拦截器 —— 挂 {@code /message/v1/admin/**}。
│   │   │               │   └── MessageJwtAuthInterceptor.java    # 职责：用户侧 JWT 鉴权拦截器 —— 挂 {@code /message/v1/**}（不含 {@code /admin/**}）。
│   │   │               ├── mapper/
│   │   │               │   ├── ConversationMapper.java    # {@code message_conversation} 表 Mapper 接口（MyBatis-Plus）。
│   │   │               │   ├── ConversationMessageMapper.java    # {@code message_conversation_message} 表 Mapper 接口（MyBatis-Plus）。
│   │   │               │   ├── GoStylePagination.java    # 🔴 分页子句的 Go 复刻 —— 对应 GORM 的
│   │   │               │   └── NotificationMapper.java    # {@code message_notification} 表 Mapper 接口（MyBatis-Plus）。
│   │   │               ├── metrics/
│   │   │               │   ├── MessageApiMetrics.java    # message 域 HTTP 层指标 —— 承载 3 个 {@code message_api_*} 指标。
│   │   │               │   ├── MessageMetrics.java    # message 域指标类的共享基座 —— 承载「指标名前缀常量 / 标签键常量 / MeterRegistry 注册的公共方法 /
│   │   │               │   └── MessageRpcMetrics.java    # message 域 RPC 侧业务指标 —— 承载 5 个 {@code message_rpc_*} 指标。
│   │   │               ├── service/
│   │   │               │   ├── MessageApiService.java    # 职责：message 域 HTTP 层 13 个用例的编排 —— 身份解析 → 硬编码 role →
│   │   │               │   ├── MessageApiSupport.java    # 职责：message 域 HTTP 层的共享行为 —— 身份解析、错误映射、proto → DTO 投影。
│   │   │               │   └── MessageRpcSupport.java    # 职责：message 域跨用例共享的领域算法与响应工厂（不持外部依赖）。
│   │   │               ├── wiring/
│   │   │               │   └── NacosRegistration.java    # 职责：message 域进程启动后显式注册到 Nacos —— 注册的是
│   │   │               └── MessageServiceApplication.java    # 职责：message 域的唯一启动入口 —— 一个进程内同时起 Tomcat（HTTP 面 8883）
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V1__create_message_notification.sql
│   │       │       ├── V2__create_message_conversation.sql
│   │       │       ├── V3__create_message_conversation_message.sql
│   │       │       ├── V4__conversation_pair_unique_index.sql
│   │       │       └── V5__message_time_columns_timestamptz.sql
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── message/
│                       ├── config/
│                       │   └── MessageCoercionContractTest.java    # 🔴 JSON 解析面（请求体契约）的可执行断言 —— 把「规则写在代码里」变成「规则生效且可回归」。
│                       ├── contract/
│                       │   └── MessageErrorContractTest.java    # message 域错误契约（码值 / 文案 / gRPC 双向转换）的锁定测试。
│                       ├── domain/
│                       │   └── service/
│                       │       ├── MessageDomainServiceRealPgTest.java    # message 域领域层在「真 PostgreSQL」上的行为对齐测试。
│                       │       └── MessageSemanticEquivalenceTest.java    # message 域「行为对拍」用例 —— 锁住 DRIFT-MSG-V-001 / -002 / -004 / -005 / -006 / -007。
│                       ├── dto/
│                       │   └── MessageDtoWireShapeTest.java    # message 域 17 个 DTO 的 wire 形状锁定测试（Jackson 2）。
│                       ├── service/
│                       │   ├── MessageListConversationsParityTest.java    # 移植说明（本文件来自 B 侧测试资产 · AGENTS.md §1.0c「测试资产单独一档」· D13）。
│                       │   ├── MessageMarkNotificationReadTest.java    # 移植说明（本文件来自 B 侧测试资产 · AGENTS.md §1.0c「测试资产单独一档」· D13）。
│                       │   ├── MessageRpcSupportTimeZoneTest.java    # 移植说明（本文件来自 B 侧测试资产 · AGENTS.md §1.0c「测试资产单独一档」）。
│                       │   └── MessageRpcSupportTrimPreviewTest.java    # 移植说明（本文件来自 B 侧测试资产 · AGENTS.md §1.0c「测试资产单独一档」）。
│                       ├── support/
│                       │   ├── MessageTimeColumnParityTest.java    # DRIFT-MSG-V-020 的真 PG 守门：断言**运行中的库**里 8 个时间列确实是
│                       │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 message 域的测试能起真实 Spring 上下文，而不连 Nacos。
│                       │   └── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│                       └── MessageServiceApplicationTests.java    # message 域「真启动」契约测试 —— 进程起得来这件事本身就是契约。
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **两组 JWT**：`/message/v1` 用 UserAuth、`/message/v1/admin` 用 **AdminAuth** ⇒ 两套 secret、两套 interceptor、两套 Properties。
- **错误文案是英文**。
- **rpc 侧无 `*RpcProperties`**，配置块名 `MessageProperties`。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地（本文件由 `tools/gen-file-tree.py` **按磁盘实际生成** ⇒ 不与代码漂） |
| 文件内容 | ✅ **无空壳** —— 本模块 **65** 个 `.java`（含测试源）· **待填空壳 0 个**（待填空壳 = 类型体为空 **且** 留有 `TODO`；**有意为空**的类型 —— 空 struct DTO（决策 D5）· MP `BaseMapper` 接口 · 无成员 auto-config —— **不计**） |
| B 侧 ↔ Java 逐文件对照 | 📄 [`../Mapping_Table/message/message.md`](../Mapping_Table/message/message.md) —— 逐文件对照的**唯一权威副本**；由 `tools/check-mapping-coverage.py` **机器双向核验**（B 列 ⇄ C 列 ⇄ 磁盘） |
