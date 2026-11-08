# comment（comment 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/comment` |
| artifactId | `comment-service` |
| 根包 | `com.rag.comment` |
| B 侧出处 | `service/comment/` |
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
- `com.google.protobuf:protobuf-java-util`
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
│   │   │           └── comment/
│   │   │               ├── advice/
│   │   │               │   ├── CommentApiExceptionHandler.java    # comment 域异常 → HTTP 响应折叠 —— 对应 B 侧
│   │   │               │   └── CommentApiResponseAdvice.java    # comment 域成功响应包装 —— 对应 B 侧
│   │   │               ├── client/
│   │   │               │   ├── ArticleRpcClient.java    # article-rpc 客户端 —— 对应 B 侧
│   │   │               │   └── MessageRpcClient.java    # message-rpc 客户端 —— 对应 B 侧
│   │   │               ├── config/
│   │   │               │   ├── CommentApiProperties.java    # comment-api 侧配置 —— 对应 B 侧
│   │   │               │   ├── CommentRpcProperties.java    # comment-rpc 侧配置 —— 对应 B 侧
│   │   │               │   └── WebMvcConfig.java    # comment 域 Web MVC 配置 —— 对应 B 侧
│   │   │               ├── contract/
│   │   │               │   ├── CodeError.java    # comment 域业务错误载体 —— 携带业务码 {@code code} + 业务文案 {@code msg} 的 unchecked 异常。
│   │   │               │   ├── CommentKafkaMsg.java    # 评论审计消息 —— 生产侧 wire 契约（L1 跨服务契约层 · 域内私有）。
│   │   │               │   ├── ErrCodes.java    # comment 域业务错误码常量集（域内私有 · L1 契约层）。
│   │   │               │   ├── ErrMsg.java    # comment 域 code → msg 文案映射 —— 27 条文案的唯一真源（L1 契约 · 含语言）。
│   │   │               │   └── GrpcErrFactory.java    # comment 域 gRPC 错误双向转换 —— 对应 B 侧
│   │   │               ├── controller/
│   │   │               │   └── CommentController.java    # comment 域 HTTP 入口 —— 对应 B 侧
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── CommentContentEntity.java    # 评论正文表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── CommentDomainFactOutboxEntity.java    # 领域事实 outbox 表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── CommentFactStreamEntity.java    # 事实流版本号表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── CommentIndexEntity.java    # 评论索引表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── CommentLikeEntity.java    # 评论点赞表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── ReportRecordEntity.java    # 评论举报记录表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   └── SubjectEntity.java    # 评论区主体表 ORM 实体 —— 对应 B 侧
│   │   │               │   └── service/
│   │   │               │       ├── CommentDomainFactService.java    # 事实追加 —— 对应 Go 端 {@code service/comment/rpc/internal/model/domain_fact.go:55-146} 的
│   │   │               │       ├── CommentDomainService.java    # 七个 RPC 用例 —— 对应 B 侧
│   │   │               │       └── CommentTransactionalService.java    # comment 域事务边界 —— 对应 B 侧
│   │   │               ├── dto/
│   │   │               │   └── comment/
│   │   │               │       ├── CommentItem.java    # 评论条目 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── CreateCommentReq.java    # 建评论请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── CreateCommentResp.java    # 建评论响应 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── DeleteCommentReq.java    # 删评论请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── DeleteCommentResp.java    # 删评论响应 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── GetCommentReq.java    # 拉评论请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── GetCommentResp.java    # 拉评论响应 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── LikeCommentReq.java    # 点赞/点踩评论请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── LikeCommentResp.java    # 点赞/点踩评论响应 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── ManageCommentReq.java    # 管理评论请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── ManageCommentResp.java    # 管理评论响应 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── ReportCommentReq.java    # 举报评论请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── ReportCommentResp.java    # 举报评论响应 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── SubjectInfo.java    # 评论区信息 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── UpdateSubjectStateReq.java    # 更新评论区状态请求 —— immutable record（决策 D5 · A9）。
│   │   │               │       └── UpdateSubjectStateResp.java    # 更新评论区状态响应 —— immutable record（决策 D5 · A9）。
│   │   │               ├── exception/
│   │   │               │   └── CommentExceptions.java    # comment 域业务异常集合 —— 承载 B 侧各处
│   │   │               ├── grpc/
│   │   │               │   ├── CommentGrpcService.java    # comment 域 gRPC 服务实现 —— 对应 B 侧
│   │   │               │   └── PbCommentServiceAlias.java    # {@code pb.CommentService} 兼容别名 —— 对应 B 侧
│   │   │               ├── interceptor/
│   │   │               │   └── CommentJwtAuthInterceptor.java    # comment 域的 JWT 验签拦截器 —— 对应 B 侧
│   │   │               ├── mapper/
│   │   │               │   ├── CommentContentMapper.java    # 评论正文数据访问 —— 对应 B 侧
│   │   │               │   ├── CommentDomainFactOutboxMapper.java    # 评论域事实 outbox 数据访问 —— 对应 B 侧
│   │   │               │   ├── CommentFactStreamMapper.java    # 事实流版本号数据访问 —— 对应 B 侧
│   │   │               │   ├── CommentIndexMapper.java    # 评论索引数据访问 —— 对应 B 侧
│   │   │               │   ├── CommentLikeMapper.java    # 评论互动（赞/踩）记录数据访问 —— 对应 B 侧
│   │   │               │   ├── ReportRecordMapper.java    # 举报记录数据访问 —— 对应 B 侧
│   │   │               │   └── SubjectMapper.java    # 评论区（subject）数据访问 —— 对应 B 侧
│   │   │               ├── messaging/
│   │   │               │   ├── CommentAuditListener.java    # 评论审计消费者 —— 对应 B 侧
│   │   │               │   ├── CommentAuditMessage.java    # 评论审计消息 —— 消费侧 DTO（反序列化方向）。
│   │   │               │   ├── CommentAuditPublisher.java    # 评论审计消息生产端 —— 对应 B 侧
│   │   │               │   └── CommentKafkaTopics.java    # comment 域 Kafka topic 绑定 —— 对应 B 侧
│   │   │               ├── metrics/
│   │   │               │   └── CommentRpcMetrics.java    # comment 域指标 + span 名常量 —— 对应 B 侧两个文件：
│   │   │               ├── service/
│   │   │               │   ├── CommentApiService.java    # comment 域 7 个 HTTP 用例 —— 对应 B 侧
│   │   │               │   ├── CommentApiSupport.java    # comment 域 api 侧协作者 —— 对应 B 侧
│   │   │               │   ├── CommentAuthorityLookupService.java    # 权威读 —— 对应 Go 端 {@code service/comment/rpc/internal/model/fact_authority.go:12-214} 的
│   │   │               │   ├── CommentCacheService.java    # 评论缓存读穿与失效 —— 对应 Go 端 {@code service/comment/rpc/internal/cache/} 的六个文件、
│   │   │               │   ├── CommentDeliveryValidator.java    # 冻结信封合法性校验 —— 对应 Go 端
│   │   │               │   ├── CommentFactDeliveryService.java    # 单条事实投递 —— 对应 Go 端
│   │   │               │   ├── CommentRedisKeys.java    # comment 域 Redis key 构造 —— 对应 Go 端 {@code service/comment/rpc/internal/cache/keys.go:5-20}。
│   │   │               │   └── CommentSensitiveFilter.java    # 敏感词判定 —— 对应 Go 端 {@code service/comment/rpc/internal/utils/sensitive.go:33-42}
│   │   │               ├── wiring/
│   │   │               │   ├── CommentAuthorityEndpointConfig.java    # 权威读端点装配 —— 对应 B 侧
│   │   │               │   ├── CommentFactDispatchScheduler.java    # 事实流派发调度 —— 对应 B 侧
│   │   │               │   ├── CommentFactProcessLogging.java    # 进程级日志身份隔离 —— B 侧 comment 域没有这个类。
│   │   │               │   ├── CommentSchemaGate.java    # 启动后校验 7 张 comment 表确实存在 —— 对应 B 侧
│   │   │               │   └── NacosRegistration.java    # Nacos 服务注册 —— 对应 B 侧
│   │   │               └── CommentServiceApplication.java    # comment 域的唯一启动入口 —— 一个进程内同时起 Tomcat（HTTP 面）
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V1__create_comment_subject.sql
│   │       │       ├── V2__create_comment_index.sql
│   │       │       ├── V3__create_comment_content.sql
│   │       │       ├── V4__create_comment_like.sql
│   │       │       ├── V5__create_comment_report_record.sql
│   │       │       ├── V6__create_comment_domain_fact_outbox.sql
│   │       │       ├── V7__create_comment_fact_stream.sql
│   │       │       └── V8__align_comment_fact_delivery_with_go.sql
│   │       └── application.yml
│   └── test/
│       ├── java/
│       │   └── com/
│       │       └── rag/
│       │           └── comment/
│       │               ├── artifact/
│       │               │   ├── CommentArtifactClasspathGateTest.java    # 🔴 产物层守门（不需要 Docker / 不需要起进程） —— 断言本模块的
│       │               │   └── CommentRuntimeBootGateTest.java    # 🔴 真启动守门（进程级） —— 用真 fat jar 起本模块的真 {@code CommentServiceApplication} 进程，
│       │               ├── config/
│       │               │   ├── CommentApiPropertiesSecretGuardTest.java    # 🔴 comment 域 JWT 验签密钥 fail-closed 的守门网（生产同款启动 4 条 ＋ 判据边界 ＋ 真 yaml）。
│       │               │   └── CommentRuntimeConfigContractTest.java    # 🔴 C 组（L4 配置面）守门 —— comment 域的 Redis 连接池 / Redis 超时 / gRPC 出向地址 /
│       │               ├── contract/
│       │               │   ├── CommentErrorCodeContractTest.java    # comment 域错误码「数值 + 顺序」的 1:1 守门测试（纯单元，不起 Spring 上下文）。
│       │               │   ├── CommentErrorMessagesContractTest.java    # comment 域错误码「文案 + 业务错误载体」的 1:1 守门测试（纯单元，不起 Spring 上下文）。
│       │               │   └── CommentGrpcErrorRoundTripTest.java    # 真 in-process gRPC server + 真 channel 的 comment 业务码往返守门
│       │               ├── domain/
│       │               │   └── service/
│       │               │       ├── CommentFactPayloadContractTest.java    # 事实载荷与冻结信封的 JSON 契约（C 侧移植） —— 对应 Go
│       │               │       └── CommentMetaNormalizationTest.java    # 评论 meta 归一化的纯语义测试 —— 对应 Go {@code model/comment.go:222-240} 的
│       │               ├── dto/
│       │               │   └── comment/
│       │               │       └── CommentDtoWireShapeTest.java    # comment 域 16 个 DTO 的「线上 wire 形状」逐条断言（纯单元，不起 Spring 上下文）。
│       │               ├── grpc/
│       │               │   ├── CommentGrpcErrorWireRealTest.java    # 🔴 comment 域 gRPC 面的「业务码跨层往返 ＋ 跨栈别名」守门
│       │               │   ├── CommentGrpcInterceptorWiringRealTest.java    # 🔴 运行期守门：comment 域的 gRPC 业务观测拦截器真的挂到了 server 上，
│       │               │   ├── CommentOutboundDeadlineCapGateTest.java    # 🔴 B 组（L4 装配面 · 出向 2s deadline 硬上界）守门网 —— 锁住「本域出向 gRPC 调用的 2 秒
│       │               │   ├── CommentOutboundDeadlineCarrierGateTest.java    # 🔴 B 组（B 组第 9/10/11/12/16 点 · L4 载体面与装配面）守门网
│       │               │   └── CommentSpanExportRealTest.java    # 🔴 comment 域的「手写 span 必须真的被导出」守门，
│       │               ├── http/
│       │               │   ├── CommentHttpWireContractRealTomcatTest.java    # comment 域 HTTP 出口的「线上形状」逐条断言（真 Tomcat · 真拦截链 · 真 advice）。
│       │               │   ├── CommentJwtTokenMatrixRealTomcatTest.java    # 🔴 comment 域 JWT 接受矩阵的守门网（真 Tomcat · 真线路） —— 锁住 comment-api 的
│       │               │   └── CommentOversizedBodyRealTomcatTest.java    # 🔴 超限 body ⇒ 413（压过 401）的守门（真 Tomcat ＋ 真 HTTP 线路）。
│       │               ├── messaging/
│       │               │   ├── CommentAuditMessageContractTest.java    # 评论审计消息的 JSON 契约 —— 对应 Go
│       │               │   ├── CommentAuditPreviewTest.java    # 评论内容预览的纯语义测试 —— 对应 Go {@code mqs/audit_consumer.go:122-132} 的
│       │               │   ├── CommentKafkaAuditRealTest.java    # comment 域 Kafka 审计链路的真 broker 守门测试
│       │               │   └── CommentKafkaFailureDirectionRealTest.java    # 🔴 Kafka 消费侧「四条出口方向」的真链路取证 —— 对应 B 侧
│       │               ├── proto/
│       │               │   ├── CommentProtoContractTest.java    # 🔴 comment proto 的契约回归网 —— 把「{@code .proto} 文件两侧一致」升级为
│       │               │   └── CommentProtoWireParityTest.java    # 🔴🔴 comment proto 的 protobuf 消息体 wire 字节双侧对拍。
│       │               ├── service/
│       │               │   ├── CommentCacheRedisMetricGateTest.java    # 🔴 D6 / D7 的守门网（C 侧移植） —— 锁住「comment 缓存层的 Redis 指标 label 与降级语义」。
│       │               │   ├── CommentDomainFactRealPgTest.java    # 🔴 真 PostgreSQL ＋ 真 MyBatis ＋ 真 Spring 事务上的事实投递语义守门测试。
│       │               │   ├── CommentFactEventTimeRealPgTest.java    # 🔴 事实载荷的 {@code event_time} 必须归一化到 UTC —— 守门网。
│       │               │   ├── CommentFlywayBaselineRealPgTest.java    # 🔴 真 PostgreSQL 16 ＋ 真 Flyway 引擎上的 DDL / 账本守门测试。
│       │               │   ├── CommentGoSemanticsGateTest.java    # 🔴 comment 域的 Go 语义根因条 ＋ zero-pad HMAC 互通性 守门网。
│       │               │   ├── CommentIdempotentDeleteRealPgTest.java    # 🔴 幂等删除只扣一次计数的守门（真 PostgreSQL ＋ 真 MyBatis ＋ 真 Spring 事务）。
│       │               │   ├── CommentProtectionChainWiringTest.java    # 🔴 保护链「包装过的 call」的守门：链上真的有 breaker / shedding / stat 的包装层。
│       │               │   ├── CommentRedisDeadlineCarrierRealRedisTest.java    # 🔴 Redis 期限载体（deadline carrier）在真 Redis 上的活体判据：
│       │               │   ├── CommentSensitiveFilterAndKeysTest.java    # 敏感词判定与 Redis key 的契约测试 —— 对应 Go
│       │               │   └── CommentTransactionalSqlRealPgTest.java    # 🔴 真 PostgreSQL ＋ 真 MyBatis ＋ 真 Spring 事务上的动态 SQL守门测试。
│       │               ├── support/
│       │               │   ├── CommentTestSupport.java    # comment 域测试用 JWT 构造器。
│       │               │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 comment 域的测试能起真实 Spring 上下文，而不连 Nacos。
│       │               │   ├── PrometheusTestRegistryConfiguration.java    # 测试上下文里注入「真 Prometheus registry」 —— 让 Micrometer 真正吐出
│       │               │   ├── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│       │               │   └── TestRedis.java    # 测试用真 Redis（进程内单例容器） —— comment 域的 Redis 用途是
│       │               └── CommentServiceApplicationTests.java    # comment 域应用上下文能真正起来（真 PG ＋ 真 Redis ＋ 真 Flyway ＋ 真 Tomcat）。
│       └── resources/
│           └── application-test.properties
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **共享 communityfact 事件协议**（与 favorite/like 三方共用）。
- **Kafka 契约**：`CommentAuditMessage`（B 侧 `service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditMessage.java`）是跨服务 L1。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地（本文件由 `tools/gen-file-tree.py` **按磁盘实际生成** ⇒ 不与代码漂） |
| 文件内容 | ✅ **无空壳** —— 本模块 **109** 个 `.java`（含测试源）· **待填空壳 0 个**（待填空壳 = 类型体为空 **且** 留有 `TODO`；**有意为空**的类型 —— 空 struct DTO（决策 D5）· MP `BaseMapper` 接口 · 无成员 auto-config —— **不计**） |
| B 侧 ↔ Java 逐文件对照 | 📄 [`../Mapping_Table/comment/comment.md`](../Mapping_Table/comment/comment.md) —— 逐文件对照的**唯一权威副本**；由 `tools/check-mapping-coverage.py` **机器双向核验**（B 列 ⇄ C 列 ⇄ 磁盘） |
