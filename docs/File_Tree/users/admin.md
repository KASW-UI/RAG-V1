# admin（admin 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/users/admin` |
| artifactId | `admin-service` |
| 根包 | `com.rag.admin` |
| Go 出处 | `service/user/admin/` |
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
│   │   │           └── admin/
│   │   │               ├── advice/
│   │   │               │   ├── AdminApiExceptionHandler.java
│   │   │               │   └── AdminApiResponseAdvice.java
│   │   │               ├── config/
│   │   │               │   ├── AdminApiProperties.java    # @ConfigurationProperties —— 聚合本域 HTTP 层配置。
│   │   │               │   ├── AdminRpcProperties.java
│   │   │               │   └── WebMvcConfig.java    # 注册 JwtAuthInterceptor + public 路径白名单 —— 替代 Go 的 rest.WithMiddlewares 绑定。
│   │   │               ├── controller/
│   │   │               │   └── AdminController.java    # ⚠️ L1 路由契约（Go 正推，勿凭直觉改）
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── AdminEntity.java
│   │   │               │   │   ├── AdminInviteEntity.java
│   │   │               │   │   └── UserEntity.java
│   │   │               │   └── service/
│   │   │               │       ├── AdminCreateAtomicService.java
│   │   │               │       └── AdminDomainService.java
│   │   │               ├── dto/
│   │   │               │   ├── AdminInfo.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── BanUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── BanUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── CreateAdminReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── CreateAdminResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── CreateInviteReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── CreateInviteResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── DeleteUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── DeleteUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetSelfReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetSelfResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserListReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserListResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── GetUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LoginReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LoginResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LogoutReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── LogoutResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── ResetUserPasswordReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── ResetUserPasswordResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UnBanUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UnBanUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UpdateSelfReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UpdateSelfResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UpdateUserReq.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   ├── UpdateUserResp.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               │   └── UserInfo.java    # immutable record —— 替代 Go 的可变 struct（见《完全spring化的文件树》D5）。
│   │   │               ├── exception/
│   │   │               │   └── AdminExceptions.java
│   │   │               ├── grpc/
│   │   │               │   └── AdminGrpcService.java
│   │   │               ├── interceptor/
│   │   │               │   └── AdminJwtAuthInterceptor.java
│   │   │               ├── mapper/
│   │   │               │   ├── AdminInviteMapper.java
│   │   │               │   ├── AdminMapper.java
│   │   │               │   └── UserMapper.java
│   │   │               ├── metrics/
│   │   │               │   ├── AdminApiMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               │   └── AdminRpcMetrics.java    # ⚠️ L1 观测契约：指标完整名（= namespace_subsystem_name）
│   │   │               ├── service/
│   │   │               │   ├── AdminApiService.java
│   │   │               │   ├── AdminApiSupport.java
│   │   │               │   ├── AdminHelpers.java
│   │   │               │   └── AdminSpans.java
│   │   │               ├── wiring/
│   │   │               │   ├── AdminSchemaBootstrap.java
│   │   │               │   ├── AdminSeederBootstrap.java
│   │   │               │   ├── NacosRegistration.java
│   │   │               │   └── SnowflakeBootstrap.java
│   │   │               └── AdminServiceApplication.java    # @SpringBootApplication + @EnableDiscoveryClient —— api 与 rpc 合并后的唯一入口。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── admin/
│                       └── AdminServiceApplicationTests.java
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **路由前缀是签字偏离**：Go `/admincenter/v1/admin/*` ⇒ 本仓库 `/admin/v1/*`（中间那层 `/admin` 段被去掉）。
- **method 是签字偏离**：Go `Get3·Post9·Delete1` ⇒ 本仓库统一 POST。
- **⚠️ 指标名拼写错误必须照抄**：`admin_rpc_high_risk_action_logal`（应为 log/audit）⇒ 监控看板依赖它。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
