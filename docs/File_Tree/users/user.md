# user（user 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/users/user` |
| artifactId | `user-service` |
| 根包 | `com.rag.user` |
| B 侧出处 | `service/users/user/` |
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
- `com.rag:users-common`
- `com.rag:common`
- `org.springframework.boot:spring-boot-starter-data-redis`
- `org.apache.commons:commons-pool2`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── user/
│   │   │               ├── advice/
│   │   │               │   ├── UserApiExceptionHandler.java
│   │   │               │   └── UserApiResponseAdvice.java
│   │   │               ├── config/
│   │   │               │   ├── RedisDisconnectedBehaviorConfig.java
│   │   │               │   ├── UserApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── UserRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 对齐 B 侧同名配置类的注册形态。
│   │   │               ├── contract/
│   │   │               │   ├── ErrCodes.java    # 本域错误码常量集 —— B 侧 errmsg.go 的 const 块（通用段 + 域专属段）。
│   │   │               │   └── ErrMsg.java    # code → msg 映射与 GetErrMsg(code)。⚠️ **错误文案是 L1 契约**。
│   │   │               ├── controller/
│   │   │               │   └── UserController.java    # ⚠️ L1 路由契约（B 侧 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── UserAvatarHistoryEntity.java
│   │   │               │   │   └── UserEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── AvatarDomainService.java
│   │   │               │       └── UserDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── auth/
│   │   │               │   │   ├── LoginReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── LoginResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── LogoutReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── LogoutResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── avatar/
│   │   │               │   │   ├── AvatarHistoryItem.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── GetAvatarHistoryResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── SelectAvatarReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── SelectAvatarResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   ├── UploadAvatarReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   │   └── UploadAvatarResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── user/
│   │   │               │       ├── CreateUserReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── CreateUserResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── DeleteUserReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── DeleteUserResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── GetUserReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── GetUserResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── UpdateUserReq.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       ├── UpdateUserResp.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │       └── UserInfo.java    # immutable record —— 替代上游的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── UserExceptions.java
│   │   │               ├── grpc/
│   │   │               │   └── UserGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   └── JwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── UserAvatarHistoryMapper.java
│   │   │               │   └── UserMapper.java
│   │   │               ├── metrics/
│   │   │               │   ├── UserApiMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   └── UserRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── UserApiService.java
│   │   │               │   └── UserApiSupport.java
│   │   │               ├── wiring/
│   │   │               │   ├── NacosRegistration.java
│   │   │               │   ├── SnowflakeBootstrap.java
│   │   │               │   └── UserSchemaBootstrap.java
│   │   │               └── UserServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── user/
│                       └── UserServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **路由前缀**：B 侧 `UserController` 的 `@RequestMapping("/user/v1")` ⇒ 本仓库一致（**不是**签字偏离）。
- **method 统一 POST**：B 侧 `UserController` 实测 **9 个 `@PostMapping`**（无 GET/PUT/DELETE）⇒ 本仓库统一 POST 与 B 侧一致。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
