# hot（hot 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/hot` |
| artifactId | `hot-service` |
| 根包 | `com.rag.hot` |
| B 侧出处 | `service/hot/` |
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
- `org.testcontainers:kafka`
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
│   │   │           └── hot/
│   │   │               ├── advice/
│   │   │               │   ├── HotApiExceptionHandler.java    # 职责：hot 域 HTTP 面的异常出口 —— 把「业务层抛出的异常」翻译成
│   │   │               │   └── HotApiResponseAdvice.java    # 职责：hot 域 HTTP 面的成功信封包装 —— 把 controller 的返回值统一包成
│   │   │               ├── client/
│   │   │               │   └── ArticleRpcClient.java    # article-rpc 的 gRPC 客户端 —— hot 域唯一的跨域依赖。
│   │   │               ├── config/
│   │   │               │   ├── HotApiProperties.java    # hot 域 HTTP 层的配置聚合点 —— 只承载一个业务阈值：
│   │   │               │   ├── HotHydrationExecutorConfiguration.java    # 补全线程池 —— 为 hot 域的并发补全提供「有界的平台线程」，
│   │   │               │   ├── HotKafkaConfig.java    # Kafka 监听容器的两项容器级语义：分区分配回调 与 错误处理策略。
│   │   │               │   ├── HotRpcProperties.java    # hot 域的业务配置绑定点 —— 只承载三段业务参数：
│   │   │               │   └── WebMvcConfig.java    # hot 域的 Spring MVC 装配点 —— 显式「不注册任何 interceptor」。
│   │   │               ├── contract/
│   │   │               │   ├── ArticleHotEvent.java    # Kafka 消息体（{@code article-hot-events} topic 的 value）—— 只有 2 个字段。
│   │   │               │   ├── CodeError.java    # hot 域业务错误载体 —— 携带业务码 {@code code} + 业务文案 {@code msg} 的 unchecked 异常。
│   │   │               │   ├── ErrCodes.java    # hot 域业务错误码枚举 —— 11 个码值的唯一真源（L1 契约 · D16：B 侧 RAG 仓为唯一判定基准）。
│   │   │               │   ├── ErrMsg.java    # hot 域 code → msg 文案表 —— 11 条文案的唯一真源（L1 契约 · 含语言）。
│   │   │               │   └── GrpcErrorMapper.java    # hot 域 gRPC 错误转换工具 —— 业务码 ⇄ gRPC status 的双向转换 + status 提取。
│   │   │               ├── controller/
│   │   │               │   └── HotArticleController.java    # 单端点 {@code GET /hot/v1/articles} —— hot 域唯一的 HTTP 端点。
│   │   │               ├── domain/
│   │   │               │   └── model/
│   │   │               │       └── HeavyKeeper.java    # 带衰减的 Top-K sketch：FNV 指纹 + {@code depth × width} 桶表 + 最小堆。
│   │   │               ├── dto/
│   │   │               │   └── article/
│   │   │               │       ├── HotArticleItem.java    # 热门文章卡片的 HTTP 层投影 —— 13 个字段，与 B 侧 {@code HotArticleCard} 逐条一致。
│   │   │               │       ├── HotArticlesReq.java    # 分页入参 —— 对应 B 侧 {@code service/hot/hot-api/.../api/dto/HotArticlesReq.java}。
│   │   │               │       └── HotArticlesResp.java    # 热门文章列表响应 —— 对应 B 侧 {@code service/hot/hot-api/.../api/dto/HotArticlesResp.java}。
│   │   │               ├── grpc/
│   │   │               │   └── HotGrpcService.java    # hot 域的 gRPC 服务端 —— 5 个方法的薄转发。
│   │   │               ├── messaging/
│   │   │               │   ├── HotEventConsumer.java    # 消息入口：校验 → 计数 → registry.add → 触发快照。
│   │   │               │   ├── HotMergeScheduler.java    # 分布式锁 + 跨分片求和合并。
│   │   │               │   ├── HotPartitionSketchRegistry.java    # {@code partitionId → HeavyKeeper} 的分区注册表 —— 对应 B 侧的 {@code hkMap}
│   │   │               │   └── HotRedisSnapshotStore.java    # 全部 Redis keyspace + 分片快照 Lua + 30s 周期快照。
│   │   │               ├── metrics/
│   │   │               │   ├── HotApiMetrics.java    # hot 域 HTTP 面的 5 个指标 —— 对应 B 侧
│   │   │               │   └── HotRpcMetrics.java    # hot 域 rpc 面的 2 个业务指标 —— 对应 B 侧
│   │   │               ├── service/
│   │   │               │   ├── HotApiService.java    # 唯一业务用例的编排 + 并发补全 —— 对应 B 侧两个类：
│   │   │               │   ├── HotApiSupport.java    # hot 域的无状态辅助集 —— 分页数值契约 + query / body 的输入解释规则。
│   │   │               │   ├── HotRankingQueryService.java    # {@code GetHotArticles} 的唯一业务实现 —— 对应 B 侧
│   │   │               │   └── InteractionStubService.java    # 4 个互动方法的既定桩行为 —— 对应 B 侧
│   │   │               ├── wiring/
│   │   │               │   └── NacosRegistration.java    # 进程启动后显式注册到 Nacos —— 注册的是 gRPC 端口，不是 web 容器端口。
│   │   │               └── HotServiceApplication.java    # hot 域的唯一启动入口 —— 一个进程内同时起 Tomcat（HTTP 面 8895）
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       └── .gitkeep
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── hot/
│                       ├── contract/
│                       │   └── HotErrorContractTest.java    # hot 域错误契约测试 —— 把 L1 的三张表钉死：
│                       ├── domain/
│                       │   └── model/
│                       │       └── HeavyKeeperContractTest.java    # HeavyKeeper 的数值契约测试 —— 三组判据：① {@code uint32} 无符号语义；
│                       ├── grpc/
│                       │   ├── HotGrpcErrorMappingTest.java    # hot 域 gRPC 边界的「错误码映射」契约测试 —— 本轮补上的真实缺口。
│                       │   └── HotGrpcServiceRealGrpcTest.java    # hot 域 gRPC 面的「真 gRPC」测试 —— 把 5 个方法逐条钉死。
│                       ├── http/
│                       │   └── HotHttpRealTomcatTest.java    # hot 域 HTTP 面的「真 Tomcat」测试 —— 真 Spring 上下文 + 真 Tomcat
│                       ├── messaging/
│                       │   ├── HotEventConsumerContractTest.java    # 消费入口的「指标计数次序」与「drop 契约」 —— 纯单测（不连 Kafka / Redis）。
│                       │   ├── HotEventConsumerRealKafkaTest.java    # 真 Kafka 消费链路测试 —— hot 域 MQ 面的真触发点。
│                       │   ├── HotMergeSchedulerRealRedisTest.java    # 合并调度器（分布式锁 + 跨分片求和 + 3 段 Lua）的真 Redis 测试。
│                       │   ├── HotPartitionSketchRegistryLockTest.java    # 分区生命周期 + 「Redis 落盘发生在写锁之外」的守门测试。
│                       │   └── HotRedisSnapshotStoreRealRedisTest.java    # 分片快照 + Lua 脚本的真 Redis 测试。
│                       ├── service/
│                       │   └── HotApiSupportContractTest.java    # hot 域的输入解释与分页数值契约测试。
│                       ├── support/
│                       │   ├── ArticleRpcInProcessServer.java    # 测试用「真 article-rpc」（进程内 gRPC server） —— 让 hot 域的 HTTP 端点
│                       │   ├── HotKafkaTestBase.java    # 真 Kafka 容器 + 真 Redis + 真 Spring 上下文 —— hot 域「MQ 消息链路」测试的公共底座。
│                       │   ├── HotRealStackTestBase.java    # hot 域「真栈」测试底座 —— 真 Spring 上下文 + 真 Tomcat + 真 Redis
│                       │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 hot 域的测试能起真实 Spring 上下文，而不连 Nacos。
│                       │   ├── TestRedis.java    # 测试用真 Redis（进程内单例容器） —— hot 域的 Lua 脚本、ZSET 读写、
│                       │   └── ThrowingKafkaListenerConfiguration.java    # 「故意抛异常」的探针 listener —— 用来实测容器的错误处理策略是不是
│                       └── HotServiceApplicationTests.java    # hot 域合并进程（D1）的装配面冒烟测试 —— 证明「api + rpc 合并成一个进程」
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **⚠️ 全部端点免鉴权**（B 侧 `HotArticleController` 明确不引入 `JwtAuthInterceptor`）⇒ 本域**不该有** `*JwtAuthInterceptor`。
- **唯一端点是 GET**：`GET /hot/v1/articles`。
- **`heavykeeper` 是 hot 域私有算法包**（仅被 hot/rpc 的 config + mqs import）⇒ 非跨域共享。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
