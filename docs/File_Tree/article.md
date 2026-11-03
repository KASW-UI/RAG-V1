# article（article 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/article` |
| artifactId | `article-service` |
| 根包 | `com.rag.article` |
| B 侧出处 | `service/article/` |
| 说明 | api + rpc 合并后的单一 Spring Boot 应用，**域内包已压平为单层技术角色包树**（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `wiring` / `exception`）；域内错误契约与事件 DTO 在 `com.rag.<域>.contract` 包；**`dto/` 内按业务对象再拆一级子包**（D11：全仓 136 个 DTO → 26 个业务对象子包，判据 = B 侧 rpc 用例的 DTO 引用图 + 类型所在聚合） |

## 二、依赖

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-validation`
- `org.springframework.boot:spring-boot-starter-actuator`
- `io.micrometer:micrometer-registry-prometheus`
- `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery`
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `io.minio:minio`
- `org.springframework.boot:spring-boot-starter-test`
- `org.junit.jupiter:junit-jupiter`
- `org.mockito:mockito-core`
- `io.grpc:grpc-inprocess`
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`
- `org.testcontainers:kafka`
- `io.grpc:grpc-core`
- `net.devh:grpc-server-spring-boot-starter`
- `net.devh:grpc-client-spring-boot-starter`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `org.postgresql:postgresql`
- `com.rag:proto`
- `com.rag:common`
- `org.springframework.kafka:spring-kafka`
- `org.projectlombok:lombok`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── article/
│   │   │               ├── advice/
│   │   │               │   ├── ArticleApiExceptionHandler.java    # 全局异常处理 —— 本模块与 message / like / hot 三域最不一样的一层。
│   │   │               │   └── ArticleApiResponseAdvice.java    # 成功路径的信封包装 —— 对应 Go 各 handler 里手写的那一行
│   │   │               ├── client/
│   │   │               │   ├── ArticleObjectStorageClient.java    # 职责：对象存储（MinIO）客户端 —— 写对象 / 读对象 / 拼公开 URL / 幂等建桶。
│   │   │               │   ├── ContentAuditAutoConfiguration.java    # 职责：内容审核端口的兜底注册 —— 只做一件事：当容器里还没有任何
│   │   │               │   ├── ContentAuditPort.java    # 职责：内容安全审核端口（port）—— 文本审核 + 图片广告检测两条腿。
│   │   │               │   ├── MessageRpcClient.java    # 职责：message 域的 gRPC 客户端 —— 文章首次发布成功后给作者发通知。
│   │   │               │   └── UnavailableContentAuditClient.java    # 职责：内容安全审核的兜底实现 —— 让审核链路 fail-loud 而不是 fail-open。
│   │   │               ├── config/
│   │   │               │   ├── ArticleApiProperties.java    # 职责：article 域的 api 侧配置聚合点 —— Spring Boot {@code @ConfigurationProperties}
│   │   │               │   ├── ArticleCorsFilter.java    # CORS —— Go {@code rest.MustNewServer(c.RestConf, rest.WithCors())} 的等价物。
│   │   │               │   ├── ArticleKafkaChannelValidator.java    # 职责：🔴 校验「四个 Kafka 通道各自的 {@code brokers}」与全局
│   │   │               │   ├── ArticleMetaObjectHandler.java    # 职责：🔴 {@code article.updated_at} 的自动推进 —— MyBatis-Plus 侧对 Go
│   │   │               │   ├── ArticleRpcProperties.java    # 职责：article 域的 rpc 侧配置聚合点 —— Spring Boot {@code @ConfigurationProperties}
│   │   │               │   └── WebMvcConfig.java    # 职责：article 域的 Spring MVC 装配点 —— 挂鉴权拦截器 + 注册 go-zero 对齐的
│   │   │               ├── contract/
│   │   │               │   ├── ArticleSyncEvent.java    # 职责：article 域的同步事件契约 —— outbox 冻结载荷 ＋ 两个 wire 消息 ＋ 常量 ＋ 事件工厂。
│   │   │               │   ├── CommunityArticleEvent.java    # 职责：article 域的社区事件契约 —— community 信封 ＋ revision payload ＋ subject。
│   │   │               │   ├── ErrCodes.java    # 职责：article 域的错误码表 —— 码值本身（{@code int}）。
│   │   │               │   └── ErrMsg.java    # 职责：article 域的错误文案查表 —— {@code int 码 → 文案}。
│   │   │               ├── controller/
│   │   │               │   └── ArticleController.java    # article-api 的 7 个端点 —— 对应 Go {@code internal/handler/routes.go} 的三段
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── ArticleDomainOutboxEntity.java    # 职责：H03 域事件发件箱 ORM 实体 —— 承载 community 事件的待投递行。
│   │   │               │   │   ├── ArticleEntity.java    # 职责：文章主表 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── ArticlePublicationEntity.java    # 职责：source 域指针表 ORM 实体 —— 记录每篇文章当前指向哪个已发布 revision。
│   │   │               │   │   ├── ArticleRevisionEntity.java    # 职责：不可变的已批准文章版本 ORM 实体 —— 对应 B 侧
│   │   │               │   │   └── ArticleSyncOutboxEntity.java    # 职责：文章同步事件发件箱 ORM 实体 —— 审核通过后的「文章同步事件」待投递行。
│   │   │               │   └── service/
│   │   │               │       ├── ArticleAtomicService.java    # 职责：article-rpc 的三处多步写事务的载体 —— 刻意独立成 Bean。
│   │   │               │       ├── ArticleDomainService.java    # 职责：article 域的六个用例（作者视角 + 文件上传）—— 对应 Go 端 {@code internal/logic/}
│   │   │               │       └── ArticlePublicDomainService.java    # 职责：公开读路径 —— 对应 Go 端
│   │   │               ├── dto/
│   │   │               │   ├── article/
│   │   │               │   │   ├── Article.java    # 职责：文章响应投影 —— article 域对外输出的文章结构体（L1 wire 契约）。
│   │   │               │   │   ├── CreateArticleReq.java    # 职责：创建文章的入参（JSON body）。
│   │   │               │   │   ├── CreateArticleResp.java    # 职责：创建文章的响应体（信封 {@code data} 字段的内容）。
│   │   │               │   │   ├── DeleteArticleReq.java    # 职责：删除文章的入参 —— 携带两个 id。
│   │   │               │   │   ├── DeleteArticleResp.java    # 职责：删除文章的响应体（信封 {@code data} 字段的内容）。
│   │   │               │   │   ├── GetArticleReq.java    # 职责：读单篇文章的入参 —— 不来自 JSON body，由 path + query 两处拼成。
│   │   │               │   │   ├── GetArticleResp.java    # 职责：读单篇的响应体（信封 {@code data} 字段的内容）。
│   │   │               │   │   ├── ListArticlesReq.java    # 职责：文章列表的入参 —— 8 个 query 参数（4 个筛选 + 4 个分页/排序）。
│   │   │               │   │   ├── ListArticlesResp.java    # 职责：文章列表的响应体（信封 {@code data} 字段的内容）。
│   │   │               │   │   ├── UpdateArticleReq.java    # 职责：更新文章的入参（JSON body）。
│   │   │               │   │   └── UpdateArticleResp.java    # 职责：更新文章的响应体（信封 {@code data} 字段的内容）。
│   │   │               │   └── upload/
│   │   │               │       ├── UploadImageReq.java    # 职责：图片上传的入参 —— multipart 的字段名与文件内容。
│   │   │               │       └── UploadImageResp.java    # 职责：图片上传的响应体 —— 全域唯一不走信封的响应。
│   │   │               ├── exception/
│   │   │               │   └── ArticleExceptions.java    # 职责：article 域的 gRPC 状态异常集合（异常工厂）。
│   │   │               ├── grpc/
│   │   │               │   └── ArticleGrpcService.java    # 职责：ArticleService 的 gRPC 实现 —— 6 个 RPC 方法的 thin wrapper。
│   │   │               ├── interceptor/
│   │   │               │   ├── ArticleJwtAuthFilter.java    # 🔴 article-api 的 JWT 验签 Filter —— 把验签搬出 70s 预算，对齐 Go 的链序。
│   │   │               │   └── JwtAuthInterceptor.java    # article-api 的 JWT 鉴权 —— Go 端第一段 {@code server.AddRoutes(..., rest.WithJwt(secret), ...)}
│   │   │               ├── mapper/
│   │   │               │   ├── ArticleDomainOutboxMapper.java    # 职责：H03 域事件发件箱的数据访问 —— 对应 B 侧
│   │   │               │   ├── ArticleMapper.java    # 职责：文章主表数据访问 —— 对应 B 侧
│   │   │               │   ├── ArticlePublicMapper.java    # 职责：公开读路径的数据访问 —— 对应 B 侧
│   │   │               │   ├── ArticlePublicationMapper.java    # 职责：source 域指针表的数据访问 —— 对应 B 侧
│   │   │               │   ├── ArticleRevisionMapper.java    # 职责：不可变 revision 表的数据访问 —— 对应 B 侧
│   │   │               │   ├── ArticleSyncOutboxMapper.java    # 职责：文章同步事件发件箱的数据访问 —— 对应 B 侧
│   │   │               │   └── JsonbTypeHandler.java    # 职责：article 域 jsonb 列的 TypeHandler —— {@code secondary_tags}（数组）
│   │   │               ├── messaging/
│   │   │               │   ├── ArticleReviewConsumer.java    # 职责：审核消费者 —— 消费「待审核文章」消息，判四元组、读正文、跑内容审核、
│   │   │               │   ├── ArticleSyncOutboxRelay.java    # 职责：文章同步发件箱中继 —— 每秒扫一次 {@code article_sync_outbox}，
│   │   │               │   └── ArticleSyncResultConsumer.java    # 职责：同步结果消费者 —— 消费「审核/同步结果」消息，把结果落库
│   │   │               ├── metrics/
│   │   │               │   └── ArticleRpcMetrics.java    # 职责：article 域的 6 个指标 ＋ 手写 span 的常量与工具。
│   │   │               ├── service/
│   │   │               │   ├── ArticleApiService.java    # article 域的 API 层业务入口 —— 合并 B 侧 7 个 {@code *Service}。
│   │   │               │   └── ArticleApiSupport.java    # API 层的公共支撑 —— 身份解析、作者昵称补全、proto → DTO 投影、文案查表。
│   │   │               ├── web/
│   │   │               │   ├── ArticleQueryValues.java    # 职责：query string 的输入解释规则 —— Go {@code httpx.ParseForm} 的 Java 等价物。
│   │   │               │   └── ArticleStringFields.java    # Go 风格的 JSON 字符串字段解析 —— 对应 B 侧
│   │   │               ├── wiring/
│   │   │               │   ├── ArticleSchemaGate.java    # 职责：启动期校验 article 域的五张表与不可变触发器确实存在 ——
│   │   │               │   ├── MinioBucketBootstrap.java    # 职责：启动期幂等创建对象存储桶并设置公开读策略 —— 失败不阻止启动。
│   │   │               │   ├── NacosRegistration.java    # 职责：article 域进程启动后显式注册到 Nacos —— 注册的是
│   │   │               │   └── SnowflakeBootstrap.java    # 职责：雪花 ID 生成器就绪确认 —— 对应 Go 端
│   │   │               └── ArticleServiceApplication.java    # 职责：article 域的唯一启动入口 —— 一个进程内同时起 Tomcat（HTTP 面）
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── spring/
│   │       │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V10__create_article_community_revision.sql
│   │       │       ├── V8__create_article.sql
│   │       │       └── V9__create_article_sync_outbox.sql
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── article/
│                       ├── assembly/
│                       │   ├── ArticleAssemblyAutoConfigAndValidationTest.java    # 装配 / 机制门禁测试（A5 · A10 · A13） —— 全部是运行期证据，
│                       │   ├── ArticleAssemblyMetricsTest.java    # 「指标真注册」门禁 —— 真 {@link PrometheusMeterRegistry} 抓取，
│                       │   ├── ArticleGrpcChainAssemblyTest.java    # A3 · gRPC 拦截链真挂到 server 上的运行期证据。
│                       │   └── ArticleSchemaGateFailFastTest.java    # {@code ArticleSchemaGate} 的 fail-fast 真生效 ——
│                       ├── contract/
│                       │   ├── ArticleErrorCodeContractTest.java    # article 域错误码契约 —— 逐条对照 B 侧
│                       │   └── ArticleSyncEventContractTest.java    # 消息 / 事件契约的单测 —— 全部是 L1 跨服务契约的锁定。
│                       ├── domain/
│                       │   └── service/
│                       │       ├── ArticleAtomicServiceRealPgTest.java    # 真触发：{@code ArticleAtomicService.acceptSyncResult} 的
│                       │       ├── ArticleConcurrencyRealPgTest.java    # 真触发：article 域写路径的并发语义 —— 同一篇文章被「降级」与「删除」同时命中时
│                       │       ├── ArticleDomainServiceRealPgTest.java    # 真触发：{@code ArticleDomainService} 的分支判定顺序与
│                       │       ├── ArticlePublicDomainServiceRealPgTest.java    # 真触发：{@code ArticlePublicDomainService} 的公开读路径 ——
│                       │       ├── ArticlePublicListSnapshotRealPgTest.java    # 真触发：公开列表的 count 与 page 是否真的来自同一快照 ——
│                       │       ├── ArticleWritePathRealPgTest.java    # 真触发：article 域**其余五条写路径** —— 创建 / 删除 / 撤回发布 / 撤回并删除 /
│                       │       └── PublicListSnapshotProbeConfiguration.java    # 公开列表「同一快照」探针 —— 一个只在测试上下文里存在的 MyBatis 拦截器，
│                       ├── grpc/
│                       │   ├── ArticleToMessageRpcCrossDomainTest.java    # 🔴 U-2 · 跨域 gRPC 链路（article → message-rpc）—— 真 Nacos + 真对端，实测闭合。
│                       │   ├── CrossDomainLinkSupport.java    # 「真 Nacos + 真 message-rpc」跨域链路的测试期支撑工具（不是测试类）。
│                       │   └── MessageRpcAbsentPeerCrossDomainTest.java    # 🔴 U-2 的反例：对端不在注册表里时，这条链路必须真的断
│                       ├── http/
│                       │   ├── ArticleErrorCodeMappingRealTomcatTest.java    # 真触发：领域层异常 → gRPC code → 业务码 → HTTP 响应这条完整映射链，
│                       │   ├── ArticleHttpAuthRealTomcatTest.java    # article 域 HTTP 层的「鉴权边界」契约 —— 真 Tomcat ＋ 真 Spring 上下文 ＋ 真 HTTP 请求。
│                       │   ├── ArticleHttpCorsRealTomcatTest.java    # CORS 的真 Tomcat 契约测试 —— 对应 C 侧 {@code config/ArticleCorsFilter}。
│                       │   ├── ArticleHttpEndpointHeaderPlaneRealTomcatTest.java    # U-3 · 逐端点穷举「响应头字节面」 —— 7 个端点各自的响应头名单逐条钉死。
│                       │   ├── ArticleHttpEnvelopeRealTomcatTest.java    # article 域 HTTP 层的「信封 / 裸响应 / 键序」契约 —— 真 Tomcat ＋ 真 Spring 上下文
│                       │   ├── ArticleHttpParseFailureRealTomcatTest.java    # article 域 HTTP 层的「解析失败 + query 真值表」契约 —— 真 Tomcat ＋ 真 Spring 上下文
│                       │   └── ArticleHttpRoutingRealTomcatTest.java    # article 域 HTTP 层的「路由形状 + 字节面」契约 —— 真 Tomcat ＋ 真 Spring 上下文
│                       ├── kafka/
│                       │   ├── ArticleKafkaOutboxRelayRealKafkaTest.java    # 真 Kafka 上的「同步发件箱中继」投递语义测试 ——
│                       │   ├── ArticleKafkaReviewConsumerRealKafkaTest.java    # 真 Kafka 上的「审核消费者」消息链路测试 —— 审核四元组校验、
│                       │   └── ArticleKafkaSyncResultConsumerRealKafkaTest.java    # 真 Kafka 上的「同步结果消费者」消息链路测试 ——
│                       ├── mapper/
│                       │   ├── ArticleJsonbResultMapRealPgTest.java    # 真触发：article 域 6 个自定义 {@code @Select} 的 jsonb 列
│                       │   ├── ArticleMapperScriptRealPgTest.java    # 真触发：{@code ArticleMapper.countAuthorView} / {@code selectAuthorView}
│                       │   ├── ArticleMapperSortContractTest.java    # 排序白名单的结构守卫 —— 把 {@code ArticleMapper.selectAuthorView} 的
│                       │   ├── ArticlePublicMapperScriptRealPgTest.java    # 真触发：{@code ArticlePublicMapper.countPublic} / {@code selectPublicPage}
│                       │   ├── ArticleSchemaAndTimestampRealPgTest.java    # 真触发：article 域 DDL 的三条强制手段（不可变触发器 / CHECK 约束 / Flyway 真跑）
│                       │   └── ArticleUpdateStrategyRealPgTest.java    # 真触发：MyBatis-Plus {@code updateStrategy = NOT_NULL} 的写入语义，
│                       ├── storage/
│                       │   ├── ArticleObjectStorageClientRealMinioTest.java    # U-5 真触发：{@code client/ArticleObjectStorageClient} 的
│                       │   ├── ArticlePublicLegacyMarkdownRealMinioTest.java    # U-12 真触发：{@code ArticlePublicMapper.selectPublicById} 取到 legacy 行
│                       │   ├── ArticleUploadFileRealMinioTest.java    # U-11 剩余的 1/6 · 真触发：{@code domain/service/ArticleDomainService#uploadFile}
│                       │   ├── MinioSpringTestBase.java    # U-12 的 Spring 上下文底座 —— 继承 {@link ArticleRealPgTestBase}（真 PG + 真装配），
│                       │   └── TestMinio.java    # 测试用真 MinIO（进程内单例容器） —— 本包「MinIO 真链路」两个缺口的公共底座。
│                       ├── support/
│                       │   ├── ArticleRealPgTestBase.java    # 真 PostgreSQL + 真 Spring 上下文的共享基类 —— 本仓 article 域所有
│                       │   ├── AssemblyTestBase.java    # 真 PostgreSQL + 真 Spring 上下文（含真 Tomcat 与真 gRPC server） ——
│                       │   ├── HttpRealTomcatTestBase.java    # article 域 HTTP 层的「真 Tomcat」测试底座 —— 真 Spring 上下文 + 真 Tomcat
│                       │   ├── KafkaTestBase.java    # 真 Kafka 容器 + 真 PostgreSQL + 真 Spring 上下文 —— 本仓 article 域
│                       │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 article 域的测试能起真实 Spring 上下文，而不连 Nacos。
│                       │   └── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│                       └── ArticleServiceApplicationTests.java    # article 域上下文装配自检 —— 证明 D1/D7 合并后的唯一入口真能把全链条装配起来。
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **前缀是 `/v1`** —— 唯一不带域名前缀的域。
- **method 混合**：Get3·Post2·Put1·Delete1 ⇒ **不是**统一 POST。
- **指标无命名空间**：`article_total` 等是裸名（无 `<subsystem>_` 前缀）⇒ 与其它域形态不一致，照抄。
- **唯一不走信封的端点**：`POST /v1/upload`（multipart，字段名固定 `image`）⇒ 返回裸 `{"image_url":…}`。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
