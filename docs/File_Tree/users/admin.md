# admin（admin 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/users/admin` |
| artifactId | `admin-service` |
| 根包 | `com.rag.admin` |
| B 侧出处 | `service/users/admin/` |
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
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`
- `io.grpc:grpc-core`
- `net.devh:grpc-server-spring-boot-starter`
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
│   │   │           └── admin/
│   │   │               ├── advice/
│   │   │               │   ├── AdminApiExceptionHandler.java    # 职责：admin 域 HTTP 面的异常出口 —— 把「业务层抛出的异常」翻译成
│   │   │               │   └── AdminApiResponseAdvice.java    # 职责：admin 域 HTTP 面的响应信封 —— 把任意 Controller 返回值包成
│   │   │               ├── config/
│   │   │               │   ├── AdminApiProperties.java    # 职责：admin 域 HTTP 面的配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   ├── AdminRpcProperties.java    # 职责：admin 域 业务/领域面的配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   └── WebMvcConfig.java    # 职责：admin 域的 Spring MVC 装配 —— 把 {@link AdminJwtAuthInterceptor} 挂到
│   │   │               ├── controller/
│   │   │               │   └── AdminController.java    # 职责：admin Center 的 HTTP 出口 —— 13 个端点，全部 POST，前缀 {@code /admin/v1}。
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── AdminEntity.java    # {@code admin_admin} 表的 MyBatis-Plus 实体。
│   │   │               │   │   ├── AdminInviteEntity.java    # {@code admin_invite} 表的 MyBatis-Plus 实体。
│   │   │               │   │   └── UserEntity.java    # {@code users_user} 表的 MyBatis-Plus 实体（admin 子域独立持有）。
│   │   │               │   └── service/
│   │   │               │       ├── AdminCreateAtomicService.java    # 职责：CreateAdmin 的 4 步原子事务（W1 锁 invite → W2 查 username 冲突 → bcrypt →
│   │   │               │       └── AdminDomainService.java    # 职责：admin 域的领域服务 —— 13 个用例（login / logout / getSelf / updateSelf /
│   │   │               ├── dto/
│   │   │               │   ├── admin/
│   │   │               │   │   ├── AdminInfo.java    # 职责：管理员信息 DTO —— 4 字段（uid / username / email / extra_info）；被
│   │   │               │   │   ├── CreateAdminReq.java    # 职责：admin createAdmin 用例的 HTTP 请求 DTO —— 账号 / 密码 / 邮箱 / 邀请码 / 扩展信息。
│   │   │               │   │   ├── CreateAdminResp.java    # 职责：admin createAdmin 用例的 HTTP 响应 DTO —— 新建管理员的 UID。
│   │   │               │   │   ├── GetSelfReq.java    # 职责：admin getSelf 用例的 HTTP 请求 DTO —— 空 struct（uid 从 JWT claim 解析）。
│   │   │               │   │   ├── GetSelfResp.java    # 职责：admin getSelf 用例的 HTTP 响应 DTO —— 当前登录管理员的 {@link AdminInfo}。
│   │   │               │   │   ├── UpdateSelfReq.java    # 职责：admin updateSelf 用例的 HTTP 请求 DTO —— 更新自己的用户名 / 密码 / 邮箱 / 扩展信息。
│   │   │               │   │   └── UpdateSelfResp.java    # 职责：admin updateSelf 用例的 HTTP 响应 DTO —— 更新结果 + 最新管理员信息。
│   │   │               │   ├── auth/
│   │   │               │   │   ├── LoginReq.java    # 职责：admin login 用例的 HTTP 请求 DTO —— 账号 / 密码。
│   │   │               │   │   ├── LoginResp.java    # 职责：admin login 用例的 HTTP 响应 DTO —— HS256 JWT 令牌。
│   │   │               │   │   ├── LogoutReq.java    # 职责：admin logout 用例的 HTTP 请求 DTO —— 空 struct（token 从 JWT 解析）。
│   │   │               │   │   └── LogoutResp.java    # 职责：admin logout 用例的 HTTP 响应 DTO —— 是否登出成功。
│   │   │               │   ├── invite/
│   │   │               │   │   ├── CreateInviteCodeReq.java    # 职责：admin createInviteCode 用例的 HTTP 请求 DTO —— 空 struct
│   │   │               │   │   └── CreateInviteCodeResp.java    # 职责：admin createInviteCode 用例的 HTTP 响应 DTO —— 邀请码 + 过期时间（unix 秒）。
│   │   │               │   └── user/
│   │   │               │       ├── BanUserReq.java    # 职责：admin banUser 用例的 HTTP 请求 DTO —— 目标用户 UID
│   │   │               │       ├── BanUserResp.java    # 职责：admin banUser 用例的 HTTP 响应 DTO —— 是否封禁成功。
│   │   │               │       ├── DeleteUserReq.java    # 职责：admin deleteUser 用例的 HTTP 请求 DTO —— 目标用户 UID
│   │   │               │       ├── DeleteUserResp.java    # 职责：admin deleteUser 用例的 HTTP 响应 DTO —— 是否删除成功。
│   │   │               │       ├── GetUserListReq.java    # 职责：admin getUserList 用例的 HTTP 请求 DTO —— 分页（page / page_size）+ 搜索关键字。
│   │   │               │       ├── GetUserListResp.java    # 职责：admin getUserList 用例的 HTTP 响应 DTO —— 用户列表 + 总数。
│   │   │               │       ├── GetUserReq.java    # 职责：admin getUser 用例的 HTTP 请求 DTO —— 目标用户 UID。
│   │   │               │       ├── GetUserResp.java    # 职责：admin getUser 用例的 HTTP 响应 DTO —— 用户信息 + 是否找到。
│   │   │               │       ├── ResetUserPasswordReq.java    # 职责：admin resetUserPassword 用例的 HTTP 请求 DTO —— 目标用户 UID
│   │   │               │       ├── ResetUserPasswordResp.java    # 职责：admin resetUserPassword 用例的 HTTP 响应 DTO —— 是否重置成功。
│   │   │               │       ├── UnBanUserReq.java    # 职责：admin unBanUser 用例的 HTTP 请求 DTO —— 目标用户 UID
│   │   │               │       ├── UnBanUserResp.java    # 职责：admin unBanUser 用例的 HTTP 响应 DTO —— 是否解封成功。
│   │   │               │       ├── UpdateUserReq.java    # 职责：admin updateUser 用例的 HTTP 请求 DTO —— 目标用户 UID + 用户名 / 密码 / 邮箱 / 扩展信息
│   │   │               │       ├── UpdateUserResp.java    # 职责：admin updateUser 用例的 HTTP 响应 DTO —— 更新结果 + 最新用户信息。
│   │   │               │       └── UserInfo.java    # 职责：用户信息 DTO —— 5 字段（uid / status / username / email / extra_info）；
│   │   │               ├── exception/
│   │   │               │   └── AdminExceptions.java    # admin 域的业务 sentinel 异常 + PG 唯一约束判定工具。
│   │   │               ├── grpc/
│   │   │               │   └── AdminGrpcService.java    # 职责：admin 域 13 个 gRPC RPC 的thin wrapper —— proto req → {@link AdminDomainService} → proto resp。
│   │   │               ├── interceptor/
│   │   │               │   ├── AdminJwtAuthInterceptor.java    # 职责：admin 域的 JWT 鉴权拦截器 —— 承担 B 侧 {@code rest.WithJwt}
│   │   │               │   ├── AdminJwtSignatureFilter.java    # 职责：把 admin JWT 的验签搬出 3s 预算 —— 只验签、只把结果放进 request attribute、
│   │   │               │   └── AdminJwtSignatureVerifier.java    # 职责：admin JWT 验签 —— 只做「解析 + 验签名 + 取 claim」，不写响应、不查 Redis。
│   │   │               ├── mapper/
│   │   │               │   ├── AdminInviteMapper.java    # {@code admin_invite} 表的 MyBatis-Plus Mapper。
│   │   │               │   ├── AdminMapper.java    # {@code admin_admin} 表的 MyBatis-Plus Mapper。
│   │   │               │   └── UserMapper.java    # {@code users_user} 表的 MyBatis-Plus Mapper（admin 子域独立持有）。
│   │   │               ├── metrics/
│   │   │               │   ├── AdminApiMetrics.java    # 职责：admin 域 HTTP 面的业务指标收集（Micrometer Counter）。
│   │   │               │   └── AdminRpcMetrics.java    # 职责：admin 域 业务/领域面的 Prometheus 计数器（Micrometer Counter）。
│   │   │               ├── service/
│   │   │               │   ├── AdminApiService.java    # 职责：admin 域 HTTP 层的 13 个用例（登录 / 注销 / 查自己 / 改自己 / 建 admin / 建邀请码 /
│   │   │               │   ├── AdminApiSupport.java    # admin 域共用 helper —— 跨 13 个用例 / 拦截器 / 控制器的工具方法聚合点。
│   │   │               │   ├── AdminHelpers.java    # 业务逻辑共享的字符串归一化工具。
│   │   │               │   └── AdminSpans.java    # admin 域的手写 span。
│   │   │               ├── wiring/
│   │   │               │   ├── AdminSchemaBootstrap.java    # 职责：admin 域启动期建表 —— 把 users 域全部 DDL 幂等重放一遍。
│   │   │               │   ├── AdminSeederBootstrap.java    # 职责：admin 域启动期 bootstrap —— 创建默认 admin 账号（用户名 / 密码均硬编码 {@code admin}）。
│   │   │               │   ├── NacosRegistration.java    # 职责：显式注册 admin 域的 gRPC 实例到 Nacos（用 gRPC 端口，不是 web 端口）。
│   │   │               │   └── SnowflakeBootstrap.java    # 职责：Snowflake 初始化触发器 —— 保证 Snowflake 在 admin 域启动期已就绪
│   │   │               └── AdminServiceApplication.java    # 职责：admin 域的唯一启动入口 —— 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面）。
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
│                       ├── domain/
│                       │   └── service/
│                       │       ├── AdminCreatePasswordOrderRegressionTest.java    # R5 防回归测试 —— {@code admin.create} 里 bcrypt 哈希与前置业务检查的先后顺序。
│                       │       ├── AdminDomainServiceCatchStructureTest.java    # 结构性元测试：把「每个 {@code catch (Exception)} 前面都必须有
│                       │       ├── AdminDomainServiceRealPgTest.java    # admin 域领域层在「真 PostgreSQL + 真 Redis」上的行为对齐测试。
│                       │       ├── AdminRpcColumnTypeParityTest.java    # 🔴 DRIFT-ARPC-004 ①② 守门测试 —— admin 域 DDL 列类型与 Go 真源同构。
│                       │       ├── AdminRpcPaginationGormSemanticsTest.java    # E-3 根因 **R4** 的回归网 —— admin-rpc {@code getUserList} 的分页边界，在真 PG 上。
│                       │       ├── AdminSemanticDriftRegressionTest.java    # Go↔RAG 语义一致性回归网 —— admin-rpc 侧（issue #71186443）。
│                       │       └── AdminUpdateSelfAndCreateAdminRegressionTest.java    # admin-rpc 两个已确认缺陷的回归测试：
│                       ├── dto/
│                       │   └── ApiTypesSnakeCaseTest.java    # DTO 序列化 wire 契约测试 —— 验证 snake_case 字段名 + uid 序列化为字符串。
│                       ├── grpc/
│                       │   ├── AdminRpcGoWireParityTest.java    # 🔴 DRIFT-ARPC-007 / DRIFT-ARPC-008 守门测试 —— admin-rpc 的 gRPC wire 层与 Go 真源逐条对齐。
│                       │   ├── AdminRpcLogoutClientVisibleGrpcStatusDefaultBehaviorTest.java    # 🔴 **admin-rpc 的 B 组反例：断连策略 = {@code DEFAULT} ⇒ 客户端看到 1015（DEADLINE_EXCEEDED）而不是 INTERNAL**
│                       │   └── AdminRpcLogoutClientVisibleGrpcStatusTest.java    # 🔴 **admin-rpc 的 A 组：Redis 断连 ⇒ 客户端可见的 gRPC status 必须是 INTERNAL「注销失败,Redis写入异常」**
│                       ├── http/
│                       │   ├── AdminApiPlainText400ContentTypeTest.java    # 🔴 DRIFT-ADMINAPI-011 守门测试 —— 400 响应的 {@code Content-Type} 必须与 Go 逐字节相同。
│                       │   ├── AdminApiPlainText400HeaderProbeTest.java    # E-1 取证（2026-10-22）：定位「RAG 的 text/plain 400 丢了 Content-Type」的机制层成因。
│                       │   ├── AdminApiRealTomcatResponseBytesTest.java    # 🔴🔴 admin 真 Tomcat（RANDOM_PORT）响应字节级探针 ——
│                       │   ├── AdminDbTimeoutBudgetRealTomcatTest.java    # admin 域 · DB 超时预算的「域级守卫」（真 PG · 真 Tomcat）。
│                       │   ├── AdminHttpContractRealTomcatTest.java    # admin 域 HTTP 契约测试 —— 真 Tomcat · 真 PG · 真 Redis。
│                       │   ├── AdminSemanticDriftApiRegressionTest.java    # Go↔RAG 语义一致性回归网 —— admin-api 侧（issue #71186443）。
│                       │   └── NacosStubConfiguration.java    # Nacos 桩 —— 让 admin 域的测试能起真实 Spring 上下文，而不连 Nacos。
│                       ├── parity/
│                       │   └── GrpcParityProbe.java    # admin 域 · gRPC 面双侧活体对拍探针（E-4） —— 用 **C 侧生成的 proto stub** 同时打
│                       ├── service/
│                       │   └── AdminSpanWiringTest.java    # 🔴 DRIFT-ARPC-006 守门 —— admin-rpc 的手写 span 必须真的被导出。
│                       ├── wiring/
│                       │   └── AdminRpcNacosGroupTest.java    # 🔴 DRIFT-G1-001 守门 —— admin-rpc 注册到 Nacos 的 group 必须来自配置，
│                       └── AdminServiceApplicationTests.java    # admin 域「真启动」契约测试 —— 进程起得来这件事本身就是契约。
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **路由前缀**：B 侧 `AdminController` 的 `@RequestMapping("/admin/v1")` ⇒ 本仓库一致（**不是**签字偏离）。
- **method 统一 POST**：B 侧 `AdminController` 实测 **13 个 `@PostMapping`**（无 GET/PUT/DELETE）⇒ 本仓库统一 POST 与 B 侧一致。
- **⚠️ 指标名拼写错误必须照抄**：`admin_rpc_high_risk_action_logal`（应为 log/audit）⇒ 监控看板依赖它。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
