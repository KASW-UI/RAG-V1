# follow（follow 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/follow` |
| artifactId | `follow-service` |
| 根包 | `com.rag.follow` |
| B 侧出处 | `service/follow/` |
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
- `org.projectlombok:lombok`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── follow/
│   │   │               ├── advice/
│   │   │               │   ├── FollowApiExceptionHandler.java    # 全局异常处理器 —— 对应 B 侧
│   │   │               │   └── FollowApiResponseAdvice.java    # 全局响应包装 —— 对应 B 侧
│   │   │               ├── client/
│   │   │               │   └── UserRpcClient.java    # user-rpc gRPC 客户端门面 —— 逐条照抄 B 侧
│   │   │               ├── config/
│   │   │               │   ├── FollowApiProperties.java    # follow 域 HTTP 层配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   ├── FollowRpcProperties.java    # follow 域 gRPC 侧的业务配置 —— 逐条对应 B 侧
│   │   │               │   └── WebMvcConfig.java    # Spring MVC 装配点 —— 挂两条鉴权路径 ＋ 注册本域那条 Jackson 强转补强。
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # follow 域业务错误载体（L1 契约层） —— 对应 B 侧
│   │   │               │   ├── ErrCodes.java    # follow 域业务错误码常量集（域内私有 · L1 契约层）。
│   │   │               │   ├── ErrMsg.java    # follow 域 code → msg 查表（L1 契约层 · 域内私有）。
│   │   │               │   └── GrpcErrorMapper.java    # follow 域 gRPC 错误双向转换（L1 契约层 · 域内私有） —— 对应 B 侧
│   │   │               ├── controller/
│   │   │               │   └── FollowController.java    # follow 域全部 8 个端点 —— B 侧
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── BlockRelationEntity.java    # 拉黑关系表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   └── FollowRelationEntity.java    # 关注关系表 ORM 实体 —— 对应 B 侧
│   │   │               │   └── service/
│   │   │               │       └── FollowDomainService.java    # follow 域 8 个 gRPC 用例的业务编排 —— 逐条照抄 B 侧
│   │   │               ├── dto/
│   │   │               │   ├── list/
│   │   │               │   │   ├── FollowListReq.java    # 列表类请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   └── FollowUserListResp.java    # 用户 id 列表响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   ├── recommend/
│   │   │               │   │   ├── RecommendListResp.java    # 推荐列表响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   └── RecommendUser.java    # 推荐列表里的一项 —— immutable record（决策 D5 · A9）。
│   │   │               │   └── relation/
│   │   │               │       ├── RelationActionReq.java    # 关注 / 取关 / 拉黑 / 解拉黑四个动作的请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │       └── RelationActionResp.java    # 动作类响应体 —— immutable record（决策 D5 · A9）。
│   │   │               ├── grpc/
│   │   │               │   └── FollowGrpcService.java    # gRPC 服务入口 —— 逐条照抄 B 侧
│   │   │               ├── interceptor/
│   │   │               │   ├── FollowBlacklistInterceptor.java    # follow 域的 JWT 黑名单闸门 —— 对应 B 侧
│   │   │               │   └── FollowJwtAuthInterceptor.java    # follow 域的 JWT 验签拦截器 —— 对应 B 侧
│   │   │               ├── mapper/
│   │   │               │   ├── BlockRelationMapper.java    # 拉黑关系数据访问 —— 对应 B 侧
│   │   │               │   ├── FollowRelationMapper.java    # 关注关系数据访问 —— 对应 B 侧
│   │   │               │   └── GoStyleLimitOffset.java    # 🔴 分页子句的 Go 复刻 —— 对应 GORM 的
│   │   │               ├── metrics/
│   │   │               │   ├── FollowApiMetrics.java    # follow 域 HTTP 层三个 metric —— B 侧
│   │   │               │   └── FollowRpcMetrics.java    # follow-rpc 业务指标（L1 观测契约） —— 对应 B 侧
│   │   │               ├── service/
│   │   │               │   ├── FollowApiSupport.java    # follow 域 HTTP 层跨 8 个端点共享的行为 —— B 侧
│   │   │               │   ├── FollowBlockTransactionService.java    # follow 域唯一一笔多语句事务 —— 对应 B 侧
│   │   │               │   ├── FollowQueryService.java    # follow 域 HTTP 层「读用例组」 —— 4 个列表端点（follow/list · follower/list · block/list · recommendations）的编排。
│   │   │               │   ├── FollowRecommendationService.java    # 二度人脉推荐算法 —— 逐条照抄 B 侧
│   │   │               │   ├── FollowRelationService.java    # follow 域 HTTP 层「写用例组」 —— 4 个动作端点（follow / unfollow / block / unblock）的编排。
│   │   │               │   ├── FollowRpcSupport.java    # follow 域 gRPC 侧的跨用例共享业务工具 —— 逐条照抄 B 侧
│   │   │               │   └── FollowUserExistenceService.java    # 用户存在性校验 —— 逐条照抄 B 侧
│   │   │               ├── wiring/
│   │   │               │   └── NacosRegistration.java    # follow 域 gRPC 实例注册到 Nacos —— 逐条照抄 B 侧
│   │   │               └── FollowServiceApplication.java    # follow 域唯一入口 —— B 侧 {@code follow-api} / {@code follow-rpc} 两个进程合并后的单一 app（D1）。
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V1__create_follow_relation.sql
│   │       │       └── V2__create_block_relation.sql
│   │       └── application.yml
│   └── test/
│       ├── java/
│       │   └── com/
│       │       └── rag/
│       │           └── follow/
│       │               ├── contract/
│       │               │   └── FollowErrorContractTest.java    # follow 域 L1 错误契约（纯单测 · 不需要容器） —— 16 个码值 + 16 条文案 + 回落 + gRPC 反查。
│       │               ├── grpc/
│       │               │   └── FollowGrpcServiceContractTest.java    # follow 域 8 个 gRPC 用例的契约（真 gRPC server · 真 proto 编解码 · 真 PG）。
│       │               ├── http/
│       │               │   ├── FollowHttpBusinessCodeRealTomcatTest.java    # follow 域 HTTP 层的「业务码还原链路」端到端断言（真 Tomcat · 真 PG · 真 user-rpc gRPC）。
│       │               │   └── FollowHttpContractRealTomcatTest.java    # follow 域 HTTP 契约（真 Tomcat · 真 PostgreSQL · 真 Redis · 真 JWT）。
│       │               ├── metrics/
│       │               │   └── FollowMetricsContractTest.java    # follow 域的 5 个 rpc 指标（L1 观测契约 · 真 PG 触发 + 真 Prometheus 文本格式断言）。
│       │               ├── service/
│       │               │   ├── FollowBlockTransactionRealPgTest.java    # 🔴 P0-1 · 「插 block + 双向删 follow」是一笔事务 —— 真 PG + 真约束冲突 + 变异证据。
│       │               │   ├── FollowConcurrencyRealPgTest.java    # follow 域「并发请求」场景的真触发测试（真 Tomcat · 真 PostgreSQL · 真 Redis · 真 user-rpc gRPC）。
│       │               │   ├── FollowDomainServiceRealPgTest.java    # follow 域 8 个 gRPC 用例的业务语义（真 PostgreSQL + 真 user-rpc gRPC）。
│       │               │   └── FollowFlywayBaselineRealPgTest.java    # Flyway 守门：共享 schema 已被别的 app 占用时，follow 域的 V1 仍必须执行。
│       │               ├── support/
│       │               │   ├── FakeUserRpcServer.java    # 「假 user-rpc」—— 真 gRPC server（随机 TCP 端口）。
│       │               │   ├── FollowTestSupport.java    # follow 域测试用 JWT 构造器。
│       │               │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 follow 域的测试能起真实 Spring 上下文，而不连 Nacos。
│       │               │   ├── PrometheusTestRegistryConfiguration.java    # 测试上下文里注入「真 Prometheus registry」 —— 让 Micrometer 真正吐出
│       │               │   ├── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│       │               │   └── TestRedis.java    # 测试用真 Redis（进程内单例容器） —— follow 域唯一的 Redis 用途是
│       │               └── FollowServiceApplicationTests.java    # follow 域上下文装配自检 —— 证明 D1 合并后的唯一入口真能把全链条装配起来。
│       └── resources/
│           └── application-test.properties
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
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
