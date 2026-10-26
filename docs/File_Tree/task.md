# task（task 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/task` |
| artifactId | `task-service` |
| 根包 | `com.rag.task` |
| Go 出处 | `service/task/` |
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
│   │   │           └── task/
│   │   │               ├── advice/
│   │   │               │   ├── TaskApiExceptionHandler.java
│   │   │               │   └── TaskApiResponseAdvice.java
│   │   │               ├── config/
│   │   │               │   ├── GoStyleRawBodyArgumentProcessor.java
│   │   │               │   ├── TaskApiJsonBodyConfig.java
│   │   │               │   ├── TaskApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── TaskApiRawBodyConfig.java
│   │   │               │   ├── TaskSinkProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── controller/
│   │   │               │   └── TaskController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── TaskProgressEntity.java
│   │   │               │   │   └── UserLikeCountEntity.java    # ⚠️ 位置说明（2026-10-25 第二轮深挖修正）
│   │   │               │   └── service/
│   │   │               │       └── TaskDomainService.java
│   │   │               ├── dto/
│   │   │               │   └── task/
│   │   │               │       ├── GetTaskReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── GetTaskResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       └── TaskInfo.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── grpc/
│   │   │               │   └── TaskGrpcService.java
│   │   │               ├── mapper/
│   │   │               │   ├── TaskProgressMapper.java
│   │   │               │   └── UserLikeCountMapper.java
│   │   │               ├── messaging/
│   │   │               │   ├── TaskKafkaTopics.java
│   │   │               │   └── TaskRewardPublisher.java
│   │   │               ├── service/
│   │   │               │   ├── RedisPipelines.java
│   │   │               │   ├── TaskApiService.java
│   │   │               │   ├── TaskApiSupport.java
│   │   │               │   ├── TaskDefs.java
│   │   │               │   ├── TaskProgressWriter.java
│   │   │               │   └── TaskRedisKeys.java
│   │   │               ├── wiring/
│   │   │               │   ├── NacosRegistration.java
│   │   │               │   └── TaskSchemaGate.java
│   │   │               └── TaskServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── task/
│                       └── TaskServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **⚠️ 全部端点免鉴权**（Go 无 `WithJwt` 组）。
- **前缀是 `/taskcenter/v1`** —— 不是 `/task/v1`。
- **Go 侧 task 没有错误码空间**（无 common 包）⇒ 本域**没有** `contract/` 包（不是漏搬）。
- **`UserLikeCountEntity` 在 Go 侧位于 `internal/sink/like_sink.go`**（不在 `internal/model/`）⇒ 按目录盘点会漏。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
