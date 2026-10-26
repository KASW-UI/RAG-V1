# user（user 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/users/user` |
| artifactId | `user-service` |
| 根包 | `com.rag.user` |
| Go 出处 | `service/user/user/` |
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
- `com.rag:users-common`
- `com.rag:common`
- `org.springframework.boot:spring-boot-starter-data-redis`

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
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── contract/
│   │   │               │   ├── ErrCodes.java    # 本域错误码常量集 —— Go 侧 errmsg.go 的 const 块（通用段 + 域专属段）。
│   │   │               │   └── ErrMsg.java    # code → msg 映射与 GetErrMsg(code)。⚠️ **错误文案是 L1 契约**。
│   │   │               ├── controller/
│   │   │               │   └── UserController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── UserAvatarHistoryEntity.java
│   │   │               │   │   └── UserEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── AvatarDomainService.java
│   │   │               │       └── UserDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── AvatarHistoryItem.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── CreateUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── CreateUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── DeleteUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── DeleteUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetAvatarHistoryResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LoginReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LoginResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LogoutReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LogoutResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── SelectAvatarReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── SelectAvatarResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UpdateUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UpdateUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UploadAvatarReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UploadAvatarResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── UserInfo.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
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

- **路由前缀是签字偏离**：Go `/usercenter/v1/user/*` ⇒ 本仓库 `/user/v1/*`（见 `docs/Intentional_Deviations/_cross/signed-deviations.md`）。
- **method 是签字偏离**：Go `Get2·Post6·Delete1` ⇒ 本仓库统一 POST。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
