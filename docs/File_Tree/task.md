# task（task 域应用） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/task` |
| artifactId | `task-service` |
| 根包 | `com.rag.task` |
| B 侧出处 | `service/task/` |
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
│   │   │           └── task/
│   │   │               ├── advice/
│   │   │               │   ├── TaskApiExceptionHandler.java    # 本域唯一的错误响应出口 —— 对应 go-zero v1.10.2
│   │   │               │   └── TaskApiResponseAdvice.java    # 成功路径的「不包装」守卫 —— 本域刻意不给成功响应加信封。
│   │   │               ├── config/
│   │   │               │   ├── GoStyleRawBodyArgumentProcessor.java    # 让 {@code @RequestBody} 一律按 JSON 读原始 body —— 对齐 Go 的裸 decoder。
│   │   │               │   ├── TaskApiJsonBodyConfig.java    # 让 {@code @RequestBody} 无视 Content-Type 照样读 body —— 对齐 Go 的裸 decoder。
│   │   │               │   ├── TaskApiRawBodyConfig.java    # 把 Spring 默认的 {@code RequestResponseBodyMethodProcessor} 换成
│   │   │               │   ├── TaskRewardProperties.java    # task 域的奖励事件流配置 —— 对应 Go 端
│   │   │               │   ├── TaskSinkProperties.java    # 两个 sink 的运行参数 —— 对应 Go 端两处互相独立的常量块：
│   │   │               │   └── WebMvcConfig.java    # 本域 HTTP 面的「装配边界声明」 —— 两条否定式决定：
│   │   │               ├── controller/
│   │   │               │   └── TaskController.java    # 单端点 {@code POST /taskcenter/v1/task/get} —— 对应 Go
│   │   │               ├── domain/
│   │   │               │   ├── entity/
│   │   │               │   │   ├── TaskProgressEntity.java    # 用户任务进度 —— 对应 Go 端 {@code internal/Model/taskModel.go:28-41} 的 {@code TaskProgress}。
│   │   │               │   │   └── UserLikeCountEntity.java    # 用户点赞总数 —— 对应 Go 端 {@code internal/Model/taskModel.go:43-50} 的 {@code UserLikeCount}
│   │   │               │   └── service/
│   │   │               │       └── TaskDomainService.java    # {@code GetTask} 业务逻辑 —— 1:1 对应 Go 端
│   │   │               ├── dto/
│   │   │               │   └── task/
│   │   │               │       ├── GetTaskReq.java    # 请求体 —— 对应 Go {@code api/internal/types/types.go:52-54}：
│   │   │               │       ├── GetTaskResp.java    # 响应体 —— 对应 Go {@code api/internal/types/types.go:56-58}：
│   │   │               │       └── TaskInfo.java    # 单条任务 —— 对应 Go {@code api/internal/types/types.go:60-66}：
│   │   │               ├── grpc/
│   │   │               │   └── TaskGrpcService.java    # {@code TaskService} 的 gRPC 入口 —— 对应 Go 端
│   │   │               ├── mapper/
│   │   │               │   ├── TaskProgressMapper.java    # task_progress 数据访问 —— 1:1 对应 Go 端三处 GORM 调用。
│   │   │               │   └── UserLikeCountMapper.java    # user_like_count 数据访问 —— 对应 Go 端 {@code internal/sink/like_sink.go:406-422} 的
│   │   │               ├── messaging/
│   │   │               │   ├── TaskArticleCountConsumer.java    # 文章点赞计数 —— 1:1 对应 Go 端 {@code internal/Init/initGoKa.go:84-107} 的
│   │   │               │   ├── TaskArticleLikeProgressSink.java    # 文章获赞任务进度落库 —— 消费点赞进度事件、聚合文章计数、按 1 秒节拍 flush 到 Redis。
│   │   │               │   ├── TaskArticleProgressEvent.java    # 文章进度事件 —— 两个方向共用同一份契约，对应 Go 侧两处字段完全相同的类型：
│   │   │               │   ├── TaskCountStore.java    # goka {@code Persist} 的等价物 —— 对应 Go 端两个 processor 里的
│   │   │               │   ├── TaskGoCompatibleJson.java    # Go {@code encoding/json} 的类型判定还原工具。
│   │   │               │   ├── TaskKafkaTopics.java    # task 域 Kafka topic 与 consumer group —— 1:1 对应 Go 端
│   │   │               │   ├── TaskLogEvent.java    # 原始日志事件 —— 对应 Go 端 {@code internal/Init/initGoKa.go:23-32} 的 {@code LogEvent}。
│   │   │               │   ├── TaskRawDiagnosticConsumer.java    # 原始 topic 的诊断消费者 —— 1:1 对应 Go 端 {@code internal/Init/initKafkaRaw.go:12-18} 的
│   │   │               │   ├── TaskRawLogRouter.java    # 原始日志路由 —— 1:1 对应 Go 端 {@code internal/Init/initGoKa.go:60-70} 的
│   │   │               │   ├── TaskRewardPublisher.java    # 任务完成奖励事件发布 —— 1:1 对应 Go 端 {@code internal/reward/producer.go}
│   │   │               │   ├── TaskTraceAttributes.java    # 两个 sink 的 OTEL span 属性 —— 对应 Go 端
│   │   │               │   ├── TaskUserCountConsumer.java    # 用户点赞计数 —— 1:1 对应 Go 端 {@code internal/Init/initGoKa.go:72-82} 的
│   │   │               │   └── TaskUserLikeProgressSink.java    # 用户点赞任务进度落库 —— 消费点赞进度事件、聚合用户计数、按 1 秒节拍 flush 到 Redis + PG。
│   │   │               ├── service/
│   │   │               │   ├── RedisPipelines.java    # Redis 批量（pipeline）助手 —— 对应 Go 侧到处出现的
│   │   │               │   ├── TaskApiService.java    # 本域全部 HTTP 业务 —— 对应 Go {@code api/internal/logic/task/gettasklogic.go:30-56}。
│   │   │               │   ├── TaskApiSupport.java    # API 层的公共支撑 —— 三件事：proto 请求的构造、proto → DTO 的投影、
│   │   │               │   ├── TaskDefs.java    # 任务定义 —— 对应 Go 端 {@code internal/Model/taskModel.go:15-25} 的
│   │   │               │   ├── TaskProgressWriter.java    # task_progress 的写入器 —— 把 Go 两个 sink 里逐字相同的三段 upsert 收敛成唯一实现。
│   │   │               │   └── TaskRedisKeys.java    # task 域 Redis key 的唯一构造入口 —— 对应 Go 端散落在
│   │   │               ├── wiring/
│   │   │               │   ├── NacosRegistration.java    # task 域启动后注册到 Nacos —— 对应 Go 端 {@code service/task/rpc/task.go:37} 里
│   │   │               │   └── TaskSchemaGate.java    # 启动后校验 task 域两张表确实存在 —— 对应 Go 侧 {@code service_context.go:47} 的
│   │   │               └── TaskServiceApplication.java    # task 域的唯一启动入口 —— 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面），
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── .gitkeep
│   │       │       ├── V1__create_task_progress.sql
│   │       │       └── V2__create_user_like_count.sql
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── task/
│                       ├── dto/
│                       │   └── GetTaskReqNormalizationTest.java    # {@code GetTaskReq} 的两段归一化（真 Jackson 2 mapper，无 Spring 上下文）。
│                       ├── grpc/
│                       │   └── TaskGrpcNettyServerRealTest.java    # task 域 gRPC 的「真 netty 传输层」收发用例
│                       ├── http/
│                       │   └── TaskApiWireContractRealTomcatTest.java    # task-api 的 wire 契约（真 Tomcat） —— 真 Spring 上下文 ＋ 真 Tomcat ＋ 真 HTTP 请求。
│                       ├── mapper/
│                       │   └── TaskProgressMapperRealPgTest.java    # 三条 upsert 的 DB 级不变量（真 PostgreSQL）。
│                       ├── messaging/
│                       │   ├── TaskCountStoreRealRedisTest.java    # {@code TaskCountStore} 的 key 命名空间（真 Redis） —— 钉住 Go 侧
│                       │   ├── TaskKafkaConsumeRealTest.java    # 🔴 task 域 Kafka 消费面「端到端真跑」（真 broker ＋ 真 PG ＋ 真 Redis） ——
│                       │   └── TaskKafkaListenerAssemblyTest.java    # Kafka 消费面的装配层断言 —— 真 Spring 上下文，真
│                       ├── service/
│                       │   └── TaskRedisPipelineRealRedisTest.java    # Redis pipeline 的结果形状（真 Redis） —— 钉住
│                       ├── support/
│                       │   ├── GrpcServerPortCapture.java    # 捕获 devh 真 netty gRPC server 的「实际绑定端口」与「实际 server 实现类」。
│                       │   ├── NacosStubConfiguration.java    # Nacos 桩 —— 让 task 域的测试能起真实 Spring 上下文，而不连 Nacos。
│                       │   ├── TaskRealInfraTestBase.java    # task 域真上下文的共享底座 —— 真 Spring 上下文 + 真 PG + 真 Redis + 真 Tomcat
│                       │   ├── TestPostgres.java    # 测试用真 PostgreSQL（进程内单例容器）。
│                       │   └── TestRedis.java    # 测试用真 Redis（进程内单例容器）。
│                       └── TaskServiceApplicationTests.java    # task 域上下文装配自检 —— 证明 D1（api+rpc 合并）后的唯一入口
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **⚠️ 全部端点免鉴权**（B 侧 `TaskController` 无鉴权组）。
- **前缀是 `/taskcenter/v1`** —— 不是 `/task/v1`。
- **B 侧 task 没有错误码空间**（无 common 包）⇒ 本域**没有** `contract/` 包（不是漏搬）。
- **`UserLikeCountEntity` 在 B 侧位于 `sink/` 目录**（不在 `model/`）⇒ 按目录盘点会漏。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| B 侧 ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
