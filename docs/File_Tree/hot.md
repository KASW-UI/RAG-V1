# hot（hot 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/hot` |
| artifactId | `hot-service` |
| 根包 | `com.rag.hot` |
| Go 出处 | `service/hot/` |
| 说明 | api + rpc 合并后的单一 Spring Boot 应用，**域内包已压平为单层技术角色包树**（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `wiring` / `exception`）；域内错误契约与事件 DTO 在 `com.rag.<域>.contract` 包 |

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
│   │   │           └── hot/
│   │   │               ├── advice/
│   │   │               │   ├── HotApiExceptionHandler.java
│   │   │               │   └── HotApiResponseAdvice.java
│   │   │               ├── client/
│   │   │               │   └── ArticleRpcClient.java    # 跨域 gRPC 客户端 —— 域内调用已直连，此处只保留跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── HotApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── HotHydrationExecutorConfiguration.java
│   │   │               │   ├── HotKafkaConfig.java
│   │   │               │   ├── HotRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   ├── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               │   └── GrpcErrorMapper.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│   │   │               ├── controller/
│   │   │               │   └── HotArticleController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   └── model/
│   │   │               │       └── HeavyKeeper.java
│   │   │               ├── dto/
│   │   │               │   ├── HotArticleItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── HotArticlesReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── HotArticlesResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── grpc/
│   │   │               │   └── HotGrpcService.java
│   │   │               ├── metrics/
│   │   │               │   ├── HotApiMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   └── HotRpcMetrics.java
│   │   │               ├── service/
│   │   │               │   ├── HotApiService.java
│   │   │               │   ├── HotApiSupport.java
│   │   │               │   ├── HotRankingQueryService.java
│   │   │               │   └── InteractionStubService.java
│   │   │               ├── wiring/
│   │   │               │   └── NacosRegistration.java
│   │   │               └── HotServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── hot/
│                       └── HotServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **⚠️ 全部端点免鉴权**（Go 无 `WithJwt` 组）⇒ 本域**不该有** `*JwtAuthInterceptor`。
- **唯一端点是 GET**：`GET /hot/v1/articles`。
- **`heavykeeper` 是 hot 域私有算法包**（仅被 hot/rpc 的 config + mqs import）⇒ 非跨域共享。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
