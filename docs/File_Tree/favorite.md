# favorite（favorite 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/favorite` |
| artifactId | `favorite-service` |
| 根包 | `com.rag.favorite` |
| B 侧出处 | `service/favorite/` |
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
│   │   │           └── favorite/
│   │   │               ├── advice/
│   │   │               │   ├── FavoriteApiExceptionHandler.java    # favorite 域全局异常处理器 —— 对应 B 侧
│   │   │               │   └── FavoriteApiResponseAdvice.java    # 全局响应包装 —— 把所有 Controller 返回值包成
│   │   │               ├── client/
│   │   │               │   ├── ArticleRpcClient.java    # article-rpc gRPC 客户端 —— 持有 {@code ArticleServiceBlockingStub}。
│   │   │               │   └── UserRpcClient.java    # user-rpc gRPC 客户端 —— 持有 {@code UserServiceBlockingStub}。
│   │   │               ├── config/
│   │   │               │   ├── FavoriteApiProperties.java    # favorite-api 模块配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
│   │   │               │   ├── FavoriteRpcLabels.java    # favorite 域的 Prometheus label 取值常量 —— 逐条对应 Go
│   │   │               │   ├── FavoriteRpcProperties.java    # favorite 域专属配置 —— 对应 Go 侧三处配置源：
│   │   │               │   └── WebMvcConfig.java    # favorite 域的 MVC 装配 —— 对应 B 侧
│   │   │               ├── contract/
│   │   │               │   ├── ErrCodes.java    # favorite 域业务错误码常量集（域内私有 · L1 契约层）。
│   │   │               │   ├── ErrMsg.java    # favorite 域 code → msg 文案映射 —— 24 条文案的唯一真源（L1 契约 · 含语言）。
│   │   │               │   └── GrpcErrorMapper.java    # favorite 域 gRPC 错误契约 —— 业务码 ⇄ gRPC status 的互逆往返。
│   │   │               ├── controller/
│   │   │               │   └── FavoriteController.java    # favorite 域全部 7 个 HTTP 端点 —— B 侧
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── FavoriteFactOutboxEntity.java    # 收藏事实发件箱 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── FavoriteFolderEntity.java    # 收藏夹 ORM 实体 —— 对应 B 侧
│   │   │               │   │   ├── FavoriteItemEntity.java    # 收藏条目 ORM 实体 —— 对应 B 侧
│   │   │               │   │   └── FavoriteLegacyFactMarkerEntity.java    # legacy 事实标记 ORM 实体 —— 对应 B 侧
│   │   │               │   ├── model/
│   │   │               │   │   └── FavoriteAuthorityFact.java    # 🔴 权威事实的 v1 形状 —— 对应 Go
│   │   │               │   └── service/
│   │   │               │       └── FavoriteDomainService.java    # favorite 域的 7 个业务用例 —— 对应 B 侧
│   │   │               ├── dto/
│   │   │               │   ├── favorite/
│   │   │               │   │   ├── CreateFavoriteReq.java    # 新增收藏的请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── CreateFavoriteResp.java    # 新增收藏的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── DeleteFavoriteReq.java    # 删除收藏的请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── DeleteFavoriteResp.java    # 删除收藏的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── FavoriteItem.java    # 收藏条目（{@link GetFavoritesByFolderResp} 的元素）—— immutable record（决策 D5 · A9）。
│   │   │               │   │   ├── GetFavoritesByFolderReq.java    # 查询收藏夹下收藏的入参 —— form 通道（query string），不是 JSON body
│   │   │               │   │   └── GetFavoritesByFolderResp.java    # 查询收藏夹下收藏的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               │   └── folder/
│   │   │               │       ├── CreateFavoriteFolderReq.java    # 新建收藏夹的请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── CreateFavoriteFolderResp.java    # 新建收藏夹的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               │       ├── DeleteFavoriteFolderReq.java    # 删除收藏夹的请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │       ├── DeleteFavoriteFolderResp.java    # 删除收藏夹的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               │       ├── FavoriteFolder.java    # 收藏夹条目（{@link GetFavoriteFoldersResp} 的元素）—— immutable record（决策 D5 · A9）。
│   │   │               │       ├── GetFavoriteFoldersReq.java    # 查询收藏夹列表的入参 —— 空 body 契约（C 侧新增 · B 侧无此 DTO）。
│   │   │               │       ├── GetFavoriteFoldersResp.java    # 查询收藏夹列表的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               │       ├── UpdateFavoriteFolderNameReq.java    # 重命名收藏夹的请求体 —— immutable record（决策 D5 · A9）。
│   │   │               │       └── UpdateFavoriteFolderNameResp.java    # 重命名收藏夹的响应体（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
│   │   │               ├── exception/
│   │   │               │   └── FavoriteExceptions.java    # favorite 域异常集合 —— 承载 B 侧两类完全不同的错误。
│   │   │               ├── grpc/
│   │   │               │   └── FavoriteGrpcService.java    # gRPC 服务入口 —— 对应 B 侧
│   │   │               ├── interceptor/
│   │   │               │   ├── FavoriteJwtAuthInterceptor.java    # favorite 域的 JWT 鉴权拦截器 —— 对应 B 侧
│   │   │               │   └── QueryLimitInterceptor.java    # 🔴 favorite 域的 Go query 判据闸门 —— 逐字复刻 go-zero {@code rest/httpx} 的
│   │   │               ├── mapper/
│   │   │               │   ├── FavoriteFactOutboxMapper.java    # 事实发件箱数据访问 —— 对应 B 侧
│   │   │               │   ├── FavoriteFolderMapper.java    # 收藏夹数据访问 —— 对应 B 侧
│   │   │               │   ├── FavoriteItemMapper.java    # 收藏条目数据访问 —— 对应 B 侧
│   │   │               │   └── FavoriteLegacyFactMarkerMapper.java    # legacy 事实标记数据访问 —— 对应 B 侧
│   │   │               ├── metrics/
│   │   │               │   └── FavoriteRpcMetrics.java    # favorite 域的观测契约 —— Micrometer 指标 ＋ 手写 otel span。
│   │   │               ├── service/
│   │   │               │   ├── FavoriteApiService.java    # favorite 域 7 个 HTTP 用例 —— 对应 B 侧
│   │   │               │   ├── FavoriteApiSupport.java    # favorite-api 的共享支撑：身份解析 + proto→DTO 映射 + 业务日志 + RPC 失败还原。
│   │   │               │   ├── FavoriteArticleSnapshotResolver.java    # 文章公开修订冻结 —— 对应 B 侧
│   │   │               │   ├── FavoriteAssertionSchemaResolver.java    # 🔴 断言版本解析 —— 对应 Go
│   │   │               │   ├── FavoriteAuthorityLookupService.java    # 🔴 权威读的 17 道校验 —— 对应 Go
│   │   │               │   ├── FavoriteDeliveryValidator.java    # 🔴 冻结信封的 12 条交叉校验 —— 对应 Go
│   │   │               │   ├── FavoriteFactDispatchService.java    # 🔴 事实投递的一次尝试 —— 对应 Go
│   │   │               │   ├── FavoriteFactEnvelopeFactory.java    # 🔴 冻结信封的单点构造 —— 对应 Go
│   │   │               │   ├── FavoriteFactWriteService.java    # 🔴 三个事务写 —— 对应 Go
│   │   │               │   ├── FavoriteLegacyPreflightService.java    # 🔴🔴 **legacy 只读预检的报表计算** —— 对应 Go
│   │   │               │   ├── FavoriteRpcSupport.java    # 跨 7 个 logic 的共享片段 —— 对应 B 侧
│   │   │               │   └── FavoriteUserGuard.java    # 跨域身份校验 —— 对应 B 侧
│   │   │               ├── wiring/
│   │   │               │   ├── FavoriteAuthorityEndpointConfig.java    # 权威读 HTTP 端点（favorite 自持 servlet） —— 对应 B 侧
│   │   │               │   ├── FavoriteFactDispatchScheduler.java    # 事实投递的编排层 —— 对应 B 侧
│   │   │               │   ├── FavoriteFactProcessLogging.java    # 进程级日志身份隔离 —— 对应 B 侧
│   │   │               │   ├── FavoriteLegacyPreflightEndpoint.java    # 🔴🔴 **legacy 只读预检的触发入口** —— Actuator 自定义端点（决策 P6-c 拍板：①）。
│   │   │               │   ├── FavoriteNacosRegistration.java    # favorite 域注册到 Nacos —— 对应 B 侧
│   │   │               │   ├── FavoriteSchemaGate.java    # 启动后校验「Go 侧有、RAG 侧容易静默丢失」的 schema 资产 —— 对应 B 侧
│   │   │               │   └── FavoriteSubjectRefGate.java    # 启动期硬门禁 —— 对应 B 侧
│   │   │               └── FavoriteServiceApplication.java    # favorite 域应用入口 —— 对应 B 侧两个入口
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V1__create_favorite_folder.sql
│   │       │       ├── V2__create_favorite_item.sql
│   │       │       ├── V3__create_favorite_fact_outbox.sql
│   │       │       ├── V4__create_favorite_legacy_fact_marker.sql
│   │       │       ├── V5__add_favorite_fact_delivery.sql
│   │       │       ├── V6__align_favorite_schema_with_go.sql
│   │       │       └── V7__align_favorite_outbox_time_columns_with_go.sql
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── favorite/
│                       ├── config/
│                       │   └── FavoriteOutboundDeadlineContractTest.java    # 🔴🔴 **回归守卫：出向 gRPC 客户端硬上界（{@code rag.protection.outbound-deadline}）**
│                       ├── contract/
│                       │   ├── FavoriteErrorCodeContractTest.java    # favorite 域错误码码值契约测试 —— 真源
│                       │   ├── FavoriteErrorCodeMappingTest.java    # 🔴 错误码翻译表的回归网 —— 逐条钉住 Go 侧 7 个 logic 的
│                       │   ├── FavoriteErrorMessagesContractTest.java    # favorite 域错误码文案契约测试 —— 真源
│                       │   ├── FavoriteGrpcErrorRoundTripTest.java    # 真 in-process gRPC server + 真 channel 的 favorite 业务码往返守门。
│                       │   ├── FavoriteGrpcErrorWireFidelityTest.java    # 🔴 补上 favorite 域错误契约的最后一环：
│                       │   ├── FavoriteProtoContractTest.java    # 🔴 favorite proto 的契约回归网 —— 把「{@code .proto} 文件两侧一致」升级为
│                       │   └── FavoriteProtoWireParityTest.java    # 🔴🔴 favorite proto 的 protobuf 消息体 wire 字节双侧对拍。
│                       ├── grpc/
│                       │   └── FavoriteGrpcRealTransportTest.java    # 🔴🔴 favorite 域**自身 gRPC 面**的**真传输**测试 —— 补上「Bean 存在 ≠ 机制生效」的那一层。
│                       ├── http/
│                       │   ├── FavoriteApiHopDeadlineContractTest.java    # 🔴 真触发：api 面自持的出向上界到了 ⇒ 业务码落 1015（DRIFT-FAV-014 的修法）
│                       │   ├── FavoriteApiInputContractTest.java    # favorite 域的入参契约测试 —— 把 Go 侧 {@code httpx.Parse} 的判定矩阵钉死。
│                       │   ├── FavoriteApiPropertiesSecretGuardTest.java    # 🔴 favorite 域的 **验签密钥 fail-closed** 守门（P0 #8 · 2026-10-29 · G3 阶段 1；
│                       │   ├── FavoriteApiProtectionWiringTest.java    # 证明 favorite 域的**请求保护层真的装配上了**，而不是「配置里写了 / 类上标了注解」。
│                       │   ├── FavoriteApiRealTomcatGateTest.java    # 🔴 favorite 域的真实 Tomcat 门禁 —— 补上 {@link FavoriteApiProtectionWiringTest}
│                       │   ├── FavoriteApiRedisPoolContractTest.java    # 🔴 Redis **连接池形态**守门（favorite 域）—— 必须与 Go 侧的 go-zero node 客户端同形，且**不得无限等**。
│                       │   ├── FavoriteApiRedisTimeoutContractTest.java    # 🔴 Redis **超时口径**守门（favorite 域）—— TCP 连接超时必须 = Go 的 DialTimeout **5s**。
│                       │   ├── FavoriteLegacyPreflightEndpointTest.java    # 🔴 **legacy 只读预检的 Actuator 端点** —— 真 Tomcat 上的**可观测契约**。
│                       │   └── FavoriteQueryLimitInterceptorTest.java    # 🔴 favorite 域的 Go query 判据闸门（{@link QueryLimitInterceptor}）的判别用例。
│                       ├── mapper/
│                       │   ├── FavoriteDdlParityTest.java    # 🔴🔴 真 PG 上的 DDL 双侧对拍守门（C 侧那一半）。
│                       │   ├── FavoriteFolderItemTimeColumnTest.java    # 🔴🔴 真 PG 上的 folder / item 时间列校验 —— 守住「时间戳来自应用层时钟、
│                       │   ├── FavoriteLegacyPreflightParityTest.java    # 🔴 **legacy 只读预检的报表算法** —— 真 PostgreSQL 上的逐条判定。
│                       │   ├── FavoriteOutboxTimeColumnTest.java    # 🔴🔴 真 PG 上的 outbox 时间列校验 —— 守住「投递元数据的时间戳来自应用层时钟、
│                       │   ├── FavoritePgTestContextSharingTest.java    # 🔴🔴 **真 PG 测试类的 Spring 上下文必须共用同一个** —— 机器守门（2026-11-05 实测踩坑后补）。
│                       │   ├── FavoriteSchemaParityTest.java    # 🔴 真 PostgreSQL 上的 schema 资产校验 —— 校 C 侧 Flyway 建出的 4 张表 +
│                       │   └── FavoriteSqlStatementParityTest.java    # 🔴🔴🔴 SQL 语句层的双侧对照守门 —— favorite 域（静态形态）。
│                       ├── metrics/
│                       │   └── FavoriteRpcMetricsResultTagTest.java    # 守门：favorite-rpc 的 {@code result} 标签必须按真实结果记
│                       ├── support/
│                       │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 favorite 域的测试能起真实 Spring 上下文，而不连 Nacos。
│                       │   ├── RealContainerHostPort.java    # favorite 域 Testcontainers 宿主口段 —— 把「容器发布到宿主机的那个端口」
│                       │   ├── RealContainerHostPortTest.java    # {@link RealContainerHostPort} 的守门测试 —— 防止「钉宿主口」这个机制自己被改坏。
│                       │   ├── RealTomcatHttp.java    # 🔴 真 Tomcat 守门用的 HTTP 传输层 —— 只用 JDK {@link HttpClient}，刻意不回落。
│                       │   ├── RealTomcatPort.java    # 真 Tomcat 测试的端口选取 + 归属诊断。
│                       │   └── RealTomcatPortTest.java    # {@link RealTomcatPort} 的守门测试 —— 防止「端口选取」这个机制自己被改坏。
│                       └── FavoriteServiceApplicationTests.java    # 🔴 favorite 域的**上下文冒烟测试** —— 证明**合并后的这一个进程真的能起起来**。
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **共享 communityfact 事件协议**。
- **错误契约形态独有**：本域是唯一「只有常量 + `codeMsg` + 反查索引 + `GRPCError` + `BizCodeFromError`，无 `CodeError` 类型」的域。
- **注意**：`GET /favorite/v1/folder/list` 是 GET 端点，但 B 侧仍解析并校验 body（`FavoriteController` 的 `assertGoEmptyStructBody`）⇒ 必须保留（否则非法 body 会从 5003 放过成 200）。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
