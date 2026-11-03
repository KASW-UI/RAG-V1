# like（like 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/like` |
| artifactId | `like-service` |
| 根包 | `com.rag.like` |
| B 侧出处 | `service/like/` |
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
│   │   │           └── like/
│   │   │               ├── advice/
│   │   │               │   ├── LikeApiExceptionHandler.java    # 全局异常处理器 —— 对应 B 侧
│   │   │               │   └── LikeApiResponseAdvice.java    # 成功出口的响应信封包装 —— 对应 B 侧
│   │   │               ├── client/
│   │   │               │   └── MessageRpcClient.java    # message 域 gRPC 客户端 —— 对应 B 侧
│   │   │               ├── config/
│   │   │               │   ├── LikeApiProperties.java    # like-api 模块配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   ├── LikeRpcProperties.java    # like 域配置 —— 对应 Go 端 {@code service/like/rpc/internal/config/config.go} 的
│   │   │               │   └── WebMvcConfig.java    # like 域的 MVC 装配 —— 对应 B 侧
│   │   │               ├── contract/
│   │   │               │   ├── ArticleHotEvent.java    # 文章热点事件 —— B 侧
│   │   │               │   ├── CodeError.java    # like 域业务错误载体 —— 携带业务码 {@code code} + 业务文案 {@code msg} 的 unchecked 异常。
│   │   │               │   ├── ErrCodes.java    # like 域业务错误码常量集（域内私有 · L1 契约层）。
│   │   │               │   ├── ErrMsg.java    # like 域 code → msg 文案映射 —— 29 条文案的唯一真源（L1 契约 · 含语言）。
│   │   │               │   ├── GrpcErrFactory.java    # like 域 gRPC 错误构造与业务码还原（L1 契约层 · 域内私有）—— 对应 B 侧
│   │   │               │   ├── KafkaLikeMsg.java    # 点赞动作事件 —— B 侧
│   │   │               │   └── TaskArticleProgressMsg.java    # 任务进度事件 —— B 侧
│   │   │               ├── controller/
│   │   │               │   └── LikeController.java    # like 域全部 6 个 HTTP 端点 —— B 侧
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── LikeConsumeInboxEntity.java    # 消费收件箱 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── LikeDomainFactOutboxEntity.java    # 领域事实发件箱 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── LikeFactStreamEntity.java    # 事实流版本号 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── LikeOutboxEventEntity.java    # 热点事件发件箱 ORM 实体 —— 对应 B 侧
│   │   │               │   │   └── LikeRecordEntity.java    # 点赞记录表 ORM 实体 —— 对应 B 侧
│   │   │               │   └── service/
│   │   │               │       ├── LikeDomainFactService.java    # 领域事实追加 —— 1:1 对应 Go 端
│   │   │               │       └── LikeDomainService.java    # like 域 6 个 gRPC 用例的业务实现 —— 逐个对应 Go 端
│   │   │               ├── dto/
│   │   │               │   ├── action/
│   │   │               │   │   ├── LikeActionReq.java    # 点赞动作请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   └── LikeActionResp.java    # 点赞动作响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   ├── count/
│   │   │               │   │   ├── GetLikeCountReq.java    # 批量点赞 / 点踩数查询请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetLikeCountResp.java    # 批量点赞 / 点踩数查询响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetUserTotalLikeReq.java    # 某用户总获赞数查询请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetUserTotalLikeResp.java    # 某用户总获赞数响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   └── LikeCountItem.java    # 点赞 / 点踩计数项 —— immutable record（决策 D5 · A9）。
│   │   │               │   ├── list/
│   │   │               │   │   ├── GetTargetLikerListReq.java    # 某稿件的点赞人列表查询请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetTargetLikerListResp.java    # 某稿件的点赞人列表响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetUserLikeListReq.java    # 某用户点赞列表查询请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetUserLikeListResp.java    # 某用户点赞列表响应体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── LikeItem.java    # 点赞人条目 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   └── LikeRecordItem.java    # 点赞记录条目 —— immutable record（决策 D5 · A9）。
│   │   │               │   └── state/
│   │   │               │       ├── GetLikeStateReq.java    # 某用户对一批目标的点赞状态查询请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │       └── GetLikeStateResp.java    # 某用户对一批目标的点赞状态响应体 —— immutable record（决策 D5 · A9）。
│   │   │               ├── exception/
│   │   │               │   └── LikeExceptions.java    # like 域业务异常集合 —— 承载 B 侧各处 {@code errmsg.NewGrpcErr(code, msg)} 的那两个参数。
│   │   │               ├── grpc/
│   │   │               │   └── LikeGrpcService.java    # gRPC 服务入口 —— 对应 B 侧
│   │   │               ├── interceptor/
│   │   │               │   └── LikeJwtAuthInterceptor.java    # like 域的 JWT 验签拦截器 —— 对应 B 侧
│   │   │               ├── mapper/
│   │   │               │   ├── LikeConsumeInboxMapper.java    # 消费收件箱数据访问 —— 对应 B 侧
│   │   │               │   ├── LikeDomainFactOutboxMapper.java    # 领域事实发件箱 + 事实流版本号数据访问 —— 对应 B 侧
│   │   │               │   ├── LikeFactStreamMapper.java    # 事实流版本号数据访问 —— 对应 B 侧
│   │   │               │   ├── LikeOutboxEventMapper.java    # 热点事件发件箱数据访问 —— 对应 B 侧
│   │   │               │   └── LikeRecordMapper.java    # 点赞记录数据访问 —— 对应 B 侧
│   │   │               ├── messaging/
│   │   │               │   ├── LikeHotEventOutboxRelay.java    # 热点事件发件箱中继 —— 对应 B 侧
│   │   │               │   ├── LikeKafkaTopics.java    # like 域 Kafka 主题 —— 对应 Go 端各处 {@code PusherConf} / {@code KqConf} 的 Topic 字段。
│   │   │               │   ├── LikeTaskProgressPublisher.java    # 推广任务进度推送 —— 1:1 对应 Go 端
│   │   │               │   └── LikeUpdateKafkaListener.java    # 点赞消息消费入口 —— 对应 B 侧
│   │   │               ├── metrics/
│   │   │               │   ├── LikeApiMetrics.java    # like 域的 网关层拒绝计数 —— 对应 B 侧
│   │   │               │   ├── LikeMqMetrics.java    # like 域的 MQ 消费计数 —— 对应 B 侧
│   │   │               │   └── LikeRpcMetrics.java    # like 域的 RPC 侧指标 + span 属性埋点 —— 对应 B 侧两个文件：
│   │   │               ├── service/
│   │   │               │   ├── GoCompatibleJson.java    # 与 Go {@code encoding/json} 输出字节一致的 JSON 序列化 + SHA-256。
│   │   │               │   ├── LikeActionScript.java    # 点赞写操作的 Lua 脚本 —— 逐字对应 B 侧
│   │   │               │   ├── LikeApiService.java    # like 域 6 个 HTTP 用例 —— 对应 B 侧
│   │   │               │   ├── LikeApiSupport.java    # api 侧的共享支撑 —— 对应 B 侧
│   │   │               │   ├── LikeAuthorityLookupService.java    # 权威读 —— 对应 Go 端 {@code service/like/rpc/internal/model/fact_authority.go:12-146} 的
│   │   │               │   ├── LikeCursorIdGenerator.java    # 列表游标 ID 生成 —— 1:1 对应 Go 端 {@code service/like/rpc/internal/logic/likeactionlogic.go:26-54}。
│   │   │               │   ├── LikeDeliveryValidator.java    # 冻结信封合法性校验 —— 对应 Go 端
│   │   │               │   ├── LikeFactDispatchService.java    # 单条事实投递 —— 对应 Go 端
│   │   │               │   ├── LikeKafkaPushKey.java    # 推送 key 的生成 —— 对齐 Go {@code go-queue@v1.2.2/kq/pusher.go:123-125}。
│   │   │               │   ├── LikeMessageDigest.java    # 消费消息的语义哈希 —— 1:1 对应 Go 端
│   │   │               │   ├── LikeMessageIngestService.java    # 点赞消息落库 —— 1:1 对应 Go 端
│   │   │               │   └── LikeRedisKeys.java    # like 域 Redis key 构造 —— 对应 Go 端散落在 6 个 logic 里的 {@code fmt.Sprintf} 拼键。
│   │   │               ├── wiring/
│   │   │               │   ├── LikeAuthorityEndpointConfig.java    # 权威读端点的 Servlet 注册 —— 对应 B 侧
│   │   │               │   ├── LikeFactDispatchScheduler.java    # 事实流派发调度器 —— 对应 B 侧
│   │   │               │   ├── LikeFactProcessLogging.java    # 进程级日志身份隔离 —— 对应 B 侧
│   │   │               │   ├── LikeSchemaGate.java    # 表存在性启动闸 —— 对应 B 侧
│   │   │               │   └── NacosRegistration.java    # Nacos 服务注册 —— 对应 B 侧
│   │   │               └── LikeServiceApplication.java    # like 域应用入口 —— 对应 B 侧两个入口
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V1__create_like_record.sql
│   │       │       ├── V2__create_like_consume_inbox.sql
│   │       │       ├── V3__create_like_outbox_event.sql
│   │       │       ├── V4__create_like_domain_fact_outbox.sql
│   │       │       ├── V5__create_like_fact_stream.sql
│   │       │       └── V6__align_like_domain_fact_outbox_with_go.sql
│   │       └── application.yml
│   └── test/
│       ├── java/
│       │   └── com/
│       │       └── rag/
│       │           └── like/
│       │               ├── contract/
│       │               │   └── LikeErrorContractTest.java    # like 域错误契约的逐条冻结断言（纯单元，不起 Spring 上下文）。
│       │               ├── dto/
│       │               │   ├── LikeDtoWireShapeTest.java    # like 域 DTO 的「线上 wire 形状」逐条断言（纯单元，不起 Spring 上下文）。
│       │               │   └── LikeJsonCreatorAnnotationScopeTest.java    # 🔴 「record 的 {@code @JsonCreator} 工厂会不会顶替组件上的 {@code @JsonDeserialize}」——
│       │               ├── grpc/
│       │               │   ├── LikeGrpcNettyServerRealTest.java    # like 域 gRPC server 的「真 netty 传输层」启动守门
│       │               │   └── LikeGrpcServiceContractTest.java    # like 域 6 个 gRPC 用例的契约（真 gRPC server · 真 proto 编解码 · 真 PG · 真 Redis）。
│       │               ├── http/
│       │               │   ├── LikeHttpWireContractRealTomcatTest.java    # like 域 HTTP 层的「线上形状」逐条断言（真 Tomcat · 真 PG · 真 Redis）。
│       │               │   └── LikePrometheusEndpointRealTest.java    # 🔴 {@code /actuator/prometheus} 在生产形态上下文里的可用性
│       │               ├── messaging/
│       │               │   └── LikeKafkaThreeFaceRealTest.java    # like 域 Kafka 三面（生产 / 消费 / 推送）的真链路守门
│       │               ├── service/
│       │               │   ├── LikeFlywayBaselineRealPgTest.java    # Flyway 守门：共享 schema 已被别的 app 占用时，like 域的 V1..V6 仍必须全部执行
│       │               │   ├── LikeFlywayRuntimeVersionTest.java    # Flyway 运行时版本的显式断言（不靠「迁移跑通了 ⇒ 版本肯定对」的间接覆盖）。
│       │               │   ├── LikeMessageIngestRealPgTest.java    # like 域「MQ 消费落库」链路的事务与幂等断言（真 PostgreSQL 16 · 真 MyBatis-Plus）。
│       │               │   ├── LikeMessageRpcCrossDomainRealTest.java    # like 域 → message 域的跨域通知：真链路守门。
│       │               │   └── LikeUnitSemanticsTest.java    # like 域「纯逻辑协作者」的逐条断言（纯单元，不起 Spring 上下文）。
│       │               ├── support/
│       │               │   ├── FakeMessageRpcServer.java    # 假 message-rpc 的真 netty server —— 给 like 域测「跨域通知」用。
│       │               │   ├── GrpcServerPortCapture.java    # 捕获 devh 真 netty gRPC server 的「实际绑定端口」与「实际 server 实现类」。
│       │               │   ├── LikeTestSupport.java    # like 域测试用 JWT 构造器。
│       │               │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 like 域的测试能起真实 Spring 上下文，而不连 Nacos。
│       │               │   ├── PrometheusTestRegistryConfiguration.java    # 测试上下文里注入「真 Prometheus registry」 —— 让 Micrometer 真正吐出
│       │               │   ├── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│       │               │   └── TestRedis.java    # 测试用真 Redis（进程内单例容器） —— like 域的 Redis 用途是
│       │               └── LikeServiceApplicationTests.java    # like 域应用上下文能真正起来（真 PG ＋ 真 Redis ＋ 真 Flyway ＋ 真 Tomcat）。
│       └── resources/
│           └── application-test.properties
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **⚠️ 指标名用连字符**：`like-rpc_action_total` ⇒ Prometheus 正则不允许 ⇒ 该指标不会出现在 `/metrics`。**照抄，不修正**。
- **rpc 侧另有独立事件契约类**（B 侧 `service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeEventContracts.java`）：`KafkaLikeMsg` / `ArticleHotEvent` / `TaskArticleProgressMsg`。
- **共享 communityfact 事件协议**。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
