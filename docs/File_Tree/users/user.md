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
│   │   │               │   ├── UserApiExceptionHandler.java    # 全局异常处理器 —— {@code @RestControllerAdvice} ＋ {@code @ExceptionHandler}。
│   │   │               │   └── UserApiResponseAdvice.java    # 全局响应包装 —— {@code @RestControllerAdvice} ＋ {@code ResponseBodyAdvice}。
│   │   │               ├── config/
│   │   │               │   ├── UserApiProperties.java    # user 域 HTTP 层配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   ├── UserRpcProperties.java    # user 域 gRPC / 领域层配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   └── WebMvcConfig.java    # Spring MVC 装配点 —— 把 {@link JwtAuthInterceptor} 注册到 {@code /user/v1/**}。
│   │   │               ├── contract/
│   │   │               │   ├── ErrCodes.java    # user 子域错误码常量集（域内私有视图）。
│   │   │               │   └── ErrMsg.java    # user 子域错误文案查询入口（域内私有视图）。
│   │   │               ├── controller/
│   │   │               │   └── UserController.java    # user 域 HTTP 网关 —— 9 个 REST 端点。
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── UserAvatarHistoryEntity.java    # {@code users_user_avatar_history} 表实体 —— MyBatis-Plus {@code @TableName} 风格。
│   │   │               │   │   └── UserEntity.java    # {@code users_user} 表实体 —— MyBatis-Plus {@code @TableName} ＋ {@code JsonTextTypeHandler}。
│   │   │               │   └── service/
│   │   │               │       ├── AvatarDomainService.java    # 头像域服务 —— 头像字段归一化 ＋ proto 转换工具。
│   │   │               │       └── UserDomainService.java    # 用户域服务 —— 把 B 侧 9 个 Logic 类合并到一个类。
│   │   │               ├── dto/
│   │   │               │   ├── auth/
│   │   │               │   │   ├── LoginReq.java    # 登录请求体 —— immutable record（决策 D5：DTO 全部改 record ＋ Bean Validation）。
│   │   │               │   │   ├── LoginResp.java    # 登录响应体 —— immutable record（决策 D5）。
│   │   │               │   │   ├── LogoutReq.java    # 注销请求体 —— 空 record（决策 D5）。
│   │   │               │   │   └── LogoutResp.java    # 注销响应体 —— immutable record（决策 D5）。
│   │   │               │   ├── avatar/
│   │   │               │   │   ├── AvatarHistoryItem.java    # 头像历史条目（HTTP 层） —— immutable record（决策 D5）。
│   │   │               │   │   ├── GetAvatarHistoryResp.java    # 头像历史列表响应体 —— immutable record（决策 D5）。
│   │   │               │   │   ├── SelectAvatarReq.java    # 选定历史头像请求体 —— immutable record（决策 D5）。
│   │   │               │   │   ├── SelectAvatarResp.java    # 选定历史头像响应体 —— immutable record（决策 D5）。
│   │   │               │   │   ├── UploadAvatarReq.java    # 上传头像请求体 —— immutable record（决策 D5）。
│   │   │               │   │   └── UploadAvatarResp.java    # 上传头像响应体 —— immutable record（决策 D5）。
│   │   │               │   └── user/
│   │   │               │       ├── CreateUserReq.java    # 注册（创建用户）请求体 —— immutable record（决策 D5）。
│   │   │               │       ├── CreateUserResp.java    # 注册（创建用户）响应体 —— immutable record（决策 D5）。
│   │   │               │       ├── DeleteUserReq.java    # 删除用户请求体 —— 空 record（决策 D5）。
│   │   │               │       ├── DeleteUserResp.java    # 删除用户响应体 —— immutable record（决策 D5）。
│   │   │               │       ├── GetUserReq.java    # 查询用户请求体 —— 空 record（决策 D5）。
│   │   │               │       ├── GetUserResp.java    # 查询用户响应体 —— immutable record（决策 D5）。
│   │   │               │       ├── UpdateUserReq.java    # 更新用户请求体（部分更新） —— immutable record（决策 D5）。
│   │   │               │       ├── UpdateUserResp.java    # 更新用户响应体 —— immutable record（决策 D5）。
│   │   │               │       └── UserInfo.java    # 用户信息（HTTP 层） —— immutable record（决策 D5）。
│   │   │               ├── exception/
│   │   │               │   └── UserExceptions.java    # user 域业务异常 sentinel —— 领域层抛出，gRPC 服务入口统一捕获后映射成 {@code io.grpc.Status}。
│   │   │               ├── grpc/
│   │   │               │   └── UserGrpcService.java    # UserService gRPC 实现 —— 9 个 RPC 方法的 thin wrapper。
│   │   │               ├── interceptor/
│   │   │               │   ├── JwtAuthFilter.java    # JWT 验签 Filter —— 把验签搬出 3s 预算，对齐 go-zero 的链序。
│   │   │               │   ├── JwtAuthInterceptor.java    # JWT 鉴权拦截器 —— 承担 Go 端 {@code rest.WithJwt}（验签结果消费）+
│   │   │               │   ├── JwtAuthPaths.java    # user 域的 JWT 路径判据 —— Filter 与 Interceptor 共用同一份。
│   │   │               │   ├── JwtSignatureVerifier.java    # JWT 验签 —— 只做「解析 ＋ 验签名 ＋ 取 claim」，不写响应、不查 Redis。
│   │   │               │   └── JwtVerificationResult.java    # 一次 JWT 验签的结果 —— 数据载体，不含行为。
│   │   │               ├── mapper/
│   │   │               │   ├── UserAvatarHistoryMapper.java    # {@code users_user_avatar_history} 表的 MyBatis-Plus Mapper。
│   │   │               │   └── UserMapper.java    # {@code users_user} 表的 MyBatis-Plus Mapper。
│   │   │               ├── metrics/
│   │   │               │   ├── UserApiMetrics.java    # user 域 HTTP 层业务指标收集。
│   │   │               │   └── UserRpcMetrics.java    # user 域领域层业务指标收集。
│   │   │               ├── service/
│   │   │               │   ├── UserApiService.java    # user 域 HTTP 层用例集 —— 把 B 侧 9 个 api Service 合并到一个类。
│   │   │               │   └── UserApiSupport.java    # user 域 HTTP 层共用 helper —— 跨用例共享的工具方法聚合点。
│   │   │               ├── wiring/
│   │   │               │   ├── NacosRegistration.java    # 显式注册 user 域的 gRPC 实例到 Nacos（服务名 {@code user-rpc}）。
│   │   │               │   ├── SnowflakeBootstrap.java    # Snowflake workerId 启动期校验 —— {@code @EventListener(ApplicationReadyEvent)}。
│   │   │               │   └── UserSchemaBootstrap.java    # user 域启动期建表 —— 把 users 域全部 DDL 幂等重放一遍。
│   │   │               └── UserServiceApplication.java    # user 域唯一入口 —— B 侧 {@code user-api} / {@code user-rpc} 两个进程合并后的单一 app（D1）。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       ├── java/
│       │   └── com/
│       │       └── rag/
│       │           └── user/
│       │               ├── api/
│       │               │   └── UserApiHttpContractTest.java    # user 域 HTTP 契约（真 Tomcat · 真 PostgreSQL · 真 JWT）。
│       │               ├── config/
│       │               │   └── UserApiPropertiesSecretGuardTest.java    # 🔴 密钥 fail-closed 守门 —— 缺变量 / 空串 / 未解析占位符，一律启动失败。
│       │               ├── grpc/
│       │               │   └── UserGrpcServiceContractTest.java    # UserService gRPC 契约（真 gRPC server · 真 proto 编解码 · 真 starter 分发）。
│       │               ├── mapper/
│       │               │   └── AvatarTransactionWiringTest.java    # 「4 步 / 5 步原子」的事务边界真实测 —— 把 D-005 / D-015 从「源码上看着像」推到
│       │               ├── support/
│       │               │   └── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│       │               ├── wiring/
│       │               │   └── UserNacosRegistrationWiringTest.java    # 🔴 Nacos 注册面守门 —— 注册名、group、端口三样都必须是对的。
│       │               └── UserServiceApplicationTests.java    # user 域上下文装配自检 —— 证明 D1 合并后的唯一入口真能把全链条装配起来。
│       └── resources/
│           └── application-test.properties
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **路由前缀**：B 侧 `UserController` 的 `@RequestMapping("/user/v1")` ⇒ 本仓库一致（**不是**签字偏离）。
- **method 统一 POST**：B 侧 `UserController` 实测 **9 个 `@PostMapping`**（无 GET/PUT/DELETE）⇒ 本仓库统一 POST 与 B 侧一致。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地（本文件由 `tools/gen-file-tree.py` **按磁盘实际生成** ⇒ 不与代码漂） |
| 文件内容 | ✅ **无空壳** —— 本模块 **55** 个 `.java`（含测试源）· **待填空壳 0 个**（待填空壳 = 类型体为空 **且** 留有 `TODO`；**有意为空**的类型 —— 空 struct DTO（决策 D5）· MP `BaseMapper` 接口 · 无成员 auto-config —— **不计**） |
| B 侧 ↔ Java 逐文件对照 | 📄 [`../../Mapping_Table/users/user/user.md`](../../Mapping_Table/users/user/user.md) —— 逐文件对照的**唯一权威副本**；由 `tools/check-mapping-coverage.py` **机器双向核验**（B 列 ⇄ C 列 ⇄ 磁盘） |
