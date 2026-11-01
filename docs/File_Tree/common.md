# common（全局共享 starter） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `common` |
| artifactId | `common` |
| 根包 | `com.rag.common` |
| Go 出处 | `service/common/` |
| 说明 | 由 Go 的 `service/common`（11 个 .go）升级而来的 **starter**；同时承载 RAG 侧 `users-common` 的通用部分（jwt / cryptx） |

## 二、依赖

- `org.springframework.boot:spring-boot-starter`
- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-actuator`
- `io.micrometer:micrometer-registry-prometheus`
- `org.springframework.boot:spring-boot-starter-data-redis`
- `org.springframework.kafka:spring-kafka`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `org.postgresql:postgresql`
- `io.github.resilience4j:resilience4j-spring-boot3`
- `io.grpc:grpc-stub`
- `io.grpc:grpc-protobuf`
- `org.springframework.security:spring-security-crypto`
- `io.jsonwebtoken:jjwt-api`
- `io.jsonwebtoken:jjwt-impl`
- `com.fasterxml.jackson.core:jackson-databind`
- `com.fasterxml.jackson.core:jackson-annotations`
- `io.jsonwebtoken:jjwt-jackson`
- `org.slf4j:slf4j-api`
- `ch.qos.logback:logback-classic`
- `io.opentelemetry:opentelemetry-api`
- `io.opentelemetry:opentelemetry-sdk`
- `io.opentelemetry:opentelemetry-context`
- `io.opentelemetry:opentelemetry-exporter-otlp`
- `org.mybatis:mybatis`
- `org.mybatis:mybatis-spring`
- `io.micrometer:micrometer-core`
- `jakarta.servlet:jakarta.servlet-api`
- `org.springframework:spring-web`
- `org.springframework:spring-webmvc`
- `io.lettuce:lettuce-core`
- `io.github.erdtman:java-json-canonicalization`
- `org.apache.tomcat.embed:tomcat-embed-core`
- `net.devh:grpc-client-spring-boot-starter`
- `net.devh:grpc-server-spring-boot-starter`
- `org.springframework.boot:spring-boot-starter-test`
- `io.opentelemetry:opentelemetry-sdk-testing`
- `org.springframework:spring-test`
- `org.projectlombok:lombok`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── common/
│   │   │               ├── autoconfigure/
│   │   │               │   ├── ClientProtectionAutoConfiguration.java    # gRPC 客户端侧的共享装配点：出向熔断 ＋ 出向 deadline 传播。
│   │   │               │   ├── GrpcExceptionMapperAutoConfiguration.java    # D2 / D5「统一错误模型」的 gRPC 边界适配器 ——
│   │   │               │   ├── JsonBodyConverterAutoConfiguration.java    # Jackson 侧的共享装配点 —— 把「K-01 强转规则」与「K-02 请求体判据」两条
│   │   │               │   ├── LoggerAutoConfiguration.java    # 共享日志门面的装配点 —— 产出 {@link LoggerFactoryBean}。
│   │   │               │   ├── MybatisPlusOrderingBridgeAutoConfiguration.java    # Go 出处：无 —— Go 侧用 GORM，没有「MyBatis-Plus 自动配置排序」这个概念
│   │   │               │   ├── ObservabilityAutoConfiguration.java    # 观测层的共享装配点 —— 把 {@link ObservabilityCustomizer}（业务标记的唯一载体）
│   │   │               │   ├── PersistenceAutoConfiguration.java    # 持久层超时链路的唯一装配点（MyBatis 拦截器 ⇒ MP 自动配置收集 ⇒ 真正生效）。
│   │   │               │   ├── ProtectionAutoConfiguration.java    # 保护层的共享装配点，同时是 A3 断言点名的 gRPC 拦截链挂载点。
│   │   │               │   ├── RouteTemplateAutoConfiguration.java    # 注册「注册期路由模板」解析器 —— 让共享层的 HTTP 熔断器
│   │   │               │   ├── SnowflakeAutoConfiguration.java    # Go 出处：{@code service/common/snowflake/snowflake.go:22-38}
│   │   │               │   └── WebAutoConfiguration.java    # Web 层的共享装配点 —— 把「批 1 已落地、但因为 starter 化而没人注册」的那些
│   │   │               ├── communityfact/
│   │   │               │   ├── AuthorityFact.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│   │   │               │   ├── AuthorityHandler.java    # 对应 Go 版 service/common/communityfact/authority_http.go（80 行）
│   │   │               │   ├── CanonicalHash.java    # Go 出处：{@code service/common/communityfact/wire.go} 的
│   │   │               │   ├── DeliveryStatus.java    # ⚠️ L1 契约（取值 0/1/2/3 · D13「L1 以 Go 为准」） ——
│   │   │               │   ├── Dispatcher.java    # 对应 Go 版 {@code service/common/communityfact/process.go} 的
│   │   │               │   ├── Event.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│   │   │               │   ├── EventDispatcher.java    # 对应 Go 版 {@code service/common/communityfact/process.go}（69 行）的
│   │   │               │   ├── EventSender.java    # Go 出处：{@code service/common/communityfact/wire.go} 的
│   │   │               │   ├── ProcessLogger.java    # 对应 Go 版 service/common/communityfact/logging.go 的进程日志 + OTEL 本地追踪启动。
│   │   │               │   ├── ProcessMetadata.java    # Go 出处：{@code service/common/communityfact/logging.go} 的
│   │   │               │   ├── SubjectRef.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│   │   │               │   ├── TechnicalReceipt.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│   │   │               │   └── Wire.java    # 共享层 wire 协议的门面 —— 对应 Go 侧
│   │   │               ├── contract/
│   │   │               │   └── ApiResponse.java    # 共享层的响应信封 —— 对应 Go 侧 {@code service/common/response/response.go}。
│   │   │               ├── exception/
│   │   │               │   ├── AuthenticationException.java    # 鉴权层失败 —— 与 {@link BusinessException} 的区别只有一条：HTTP 状态码是 401。
│   │   │               │   └── BusinessException.java    # Go 出处：{@code service/common/response/response.go:3-7}（信封三字段 code/msg/data）
│   │   │               ├── logger/
│   │   │               │   ├── FatalError.java    # H3 修复：致命错误的专用异常类型。
│   │   │               │   ├── GoStyleLogger.java    # Go 出处：{@code service/common/logger/logger.go}（整个包，356 行）
│   │   │               │   ├── LogOption.java    # Go 出处：{@code service/common/logger/logger.go:32}（{@code type LogOption func(*LogOptions)}）
│   │   │               │   ├── LogOptions.java    # Go 出处：{@code service/common/logger/logger.go:26-29}
│   │   │               │   └── LoggerFactoryBean.java    # {@code Logger} 的 Spring 适配 —— 把 {@code spring.application.name} 注入的
│   │   │               ├── observability/
│   │   │               │   ├── ClientDisconnectProbe.java    # 客户端断连的主动探测 —— 往 socket 写 1xx 探针，把断连提前暴露出来。
│   │   │               │   ├── DeadlineState.java    # 请求级 deadline 的服务端载体（ThreadLocal 绝对到期时刻，{@link System#nanoTime()} 域）。
│   │   │               │   ├── GoStyleRequestDeadline.java    # 请求级 deadline 的载体：把 {@link HttpMiddleware} 的 HTTP 超时预算，
│   │   │               │   ├── MqConsumer.java    # MQ 消费端 trace 包装 —— 对齐 Go 版的 {@code TraceConsumer}。
│   │   │               │   ├── ObservabilityCustomizer.java    # 业务观测标记的唯一载体 —— 对齐 go-zero {@code service/common/observability/observability.go} 的
│   │   │               │   ├── ObservabilityLifecycle.java    # Spring 容器关闭时回收RPC 侧的观测层线程池。
│   │   │               │   ├── ObservabilityProperties.java    # Observability 配置绑定（{@code application.yml}）：
│   │   │               │   ├── ObservabilityRuntime.java    # 观测层的进程级线程池持有者（本仓对 RAG {@code RpcInterceptor} 里那两个
│   │   │               │   ├── RpcCallHandler.java    # gRPC 服务端业务调用处理器（Java 版与 Go 版 {@code grpc.UnaryHandler} 等价）。
│   │   │               │   ├── RpcHandleRunner.java    # 非 gRPC Server 场景下的业务调用入口 —— 跑一次 handler，带超时控制与观测标记。
│   │   │               │   ├── RpcPrometheusInterceptor.java    # Prometheus 层 —— 对齐 go-zero 的 {@code UnaryPrometheusInterceptor}：
│   │   │               │   ├── RpcRecoverInterceptor.java    # gRPC 服务端 panic 恢复拦截器 —— 对齐 go-zero 的 {@code UnaryRecoverInterceptor}。
│   │   │               │   ├── RpcResult.java    # gRPC 调用结果（resp + 错误），对应 Go 版 {@code (any, error)}。
│   │   │               │   ├── RpcStatInterceptor.java    # Stat 层 —— 对齐 go-zero 的 {@code UnaryStatInterceptor}：
│   │   │               │   └── RpcStatMetrics.java    # Go {@code core/stat} 的 Java 复刻 —— 进程内 RPC 耗时聚合器 ＋
│   │   │               ├── persistence/
│   │   │               │   ├── DeadlineQueryTimeoutInjector.java    # B-1 缓解（第一半）：把当前 rpc 的 deadline 落到每条 SQL 上，让 SQL 的取消时刻 == deadline 时刻。
│   │   │               │   ├── JsonTextTypeHandler.java    # PostgreSQL {@code text} 列 ↔ {@code Map} 的 TypeHandler。
│   │   │               │   ├── JsonbTypeHandler.java    # jsonb 列 ↔ String 的 TypeHandler —— 对应 Go 端 PG 的 {@code jsonb} 列。
│   │   │               │   └── SqlTimeoutStatusTranslator.java    # B-1 缓解（第二半）：把「SQL 被 deadline 取消」翻译成 gRPC 的 {@code DEADLINE_EXCEEDED}。
│   │   │               ├── protection/
│   │   │               │   ├── AdaptiveShedder.java    # Go {@code core/load/adaptiveshedder.go} 的 Java 复刻 —— CPU 自适应降载的判据部分。
│   │   │               │   ├── ClientCircuitGateInterceptor.java    # L4 idiomatization：gRPC client 侧按方法的熔断，对齐 Go
│   │   │               │   ├── CpuThresholdHealthIndicator.java    # CPU 降载的观测面：把 Go {@code core/stat/usage.go:51} {@code CpuUsage()}
│   │   │               │   ├── MaxBytesFilter.java    # L4 idiomatization：请求体大小上限，对齐 Go {@code handler.MaxBytesHandler}。
│   │   │               │   ├── MaxConnsFilter.java    # L4 idiomatization：并发连接数上限，对齐 Go {@code handler.MaxConnsHandler}。
│   │   │               │   ├── OutboundDeadlineInterceptor.java    # L4 idiomatization：把「请求 deadline 传播给出向 gRPC 调用」做成 client 侧拦截器。
│   │   │               │   ├── ProtectionProperties.java    # Go「请求保护层」四道中间件的配置聚合点。
│   │   │               │   ├── RouteResolution.java    # 路由模板解析的三态结果。
│   │   │               │   ├── RouteTemplateResolver.java    # L4 idiomatization：在 Servlet Filter 里解析出「注册期路由模板」，
│   │   │               │   ├── RouteTemplateSource.java    # 「注册期路由模板」解析能力的窄接口。
│   │   │               │   ├── RpcCircuitGateInterceptor.java    # gRPC 服务端熔断层 —— 对齐 go-zero 的 {@code UnaryBreakerInterceptor}。
│   │   │               │   ├── RpcFailurePolicy.java    # zrpc 服务端熔断的「失败判据」 —— 对齐 Go {@code serverSideAcceptable}。
│   │   │               │   ├── RpcLayer.java    # gRPC 服务端拦截链上的一层 —— 自报层名，可被只读查询入口观测。
│   │   │               │   ├── RpcLayerChainNode.java    # 链上的一个节点 —— 在 {@link RpcLayer} 之外，额外暴露内层，
│   │   │               │   ├── RpcProtectionChain.java    # gRPC 服务端拦截链的装配与观测（链序的唯一计算点 + 只读观测入口）。
│   │   │               │   ├── RpcSheddingInterceptor.java    # gRPC 服务端 CPU 自适应降载 —— 对齐 go-zero 的 {@code UnarySheddingInterceptor}。
│   │   │               │   ├── ServiceBusyException.java    # 熔断打开时抛出的异常 —— 刻意不是 {@code StatusRuntimeException}。
│   │   │               │   └── SheddingStat.java    # Go {@code core/load/sheddingstat.go} 的 Java 复刻 —— 降载的三个计数 +
│   │   │               ├── redis/
│   │   │               │   ├── ClientAutoConfiguration.java    # L4 idiomatization：把 Lettuce 客户端的「断连行为」收进共享层，
│   │   │               │   ├── Deadline.java    # 共享层的「Redis 期限感知」入口（G1 反馈单 2/3 · 2026-10-30 · G4 lane）。
│   │   │               │   ├── DeadlineAutoConfiguration.java    # 把「Redis 请求期限感知」接到各域的 {@code RedisTemplate} / {@code StringRedisTemplate} 上。
│   │   │               │   ├── DeadlineConnection.java    # 把「请求预算耗尽 ⇒ 不再下发 Redis 命令」这一层包在 {@link RedisConnection} 上。
│   │   │               │   └── DeadlineConnectionFactory.java    # 把「请求预算」接到 {@link RedisConnectionFactory} 上（域侧零改动的那一环）。
│   │   │               ├── security/
│   │   │               │   ├── BearerTokenExtractor.java    # Authorization 头的 token 提取 —— Go 侧两套互不相同的取法的逐字等价实现。
│   │   │               │   ├── JwtUtil.java    # L1 契约类：HS256 JWT 的签发。
│   │   │               │   └── PasswordUtil.java    # bcrypt 口令散列工具。
│   │   │               ├── snowflake/
│   │   │               │   └── Snowflake.java    # 对应 Go 版 service/common/snowflake/snowflake.go 的整个包（102 行）。
│   │   │               └── web/
│   │   │                   ├── BadRequestException.java    # 承载「Go {@code httpx.ErrorCtx} 会写出的那种错误」—— 即裸状态码 + text/plain，
│   │   │                   ├── Coercion.java    # E-3 根因 **R1** 的修复规则：关掉 Jackson 的标量强转与浮点截断，对齐 go-zero 的解析层。
│   │   │                   ├── Float64Deserializer.java    # {@code float64} 字段的「Go 风格」反序列化 —— 修 DRIFT-LIKEAPI-016。
│   │   │                   ├── GetFormValuesFilter.java    # 🔴 Go 风格的 query 解析与 form 值上限判据 —— 对应 go-zero {@code rest/httpx} 的
│   │   │                   ├── GrpcStatusMapper.java    # Go {@code rest/internal/errcode/grpc.go} 的两条函数的 Java 等价物 ——
│   │   │                   ├── HttpErrorException.java    # Go {@code http.Error(w, err.Error(), )} 的载体。
│   │   │                   ├── JsonBodyConverter.java    # 复刻 go-zero {@code rest/httpx/requests.go:117-124,153-155} 的
│   │   │                   ├── JsonBodyConverterConfig.java    # ⚠️ K-02 · Go「何时才读请求体」的判据字面量（唯一真源）。
│   │   │                   ├── JsonBodyLimitFilter.java    # 🔴 DRIFT-002（已修）：JSON body 超过 8 MiB ⇒ 400。
│   │   │                   ├── JsonContentTypeAdvice.java    # 🔴🔴 **T-6**：让 JSON 响应的 {@code Content-Type} 与 Go 一致地带上 {@code charset=utf-8}。
│   │   │                   ├── LimitExceededException.java    # 🔴 「按 Go 的语义，这个 query 该被拒」 —— 由共享层的闸门抛出，
│   │   │                   ├── LimitMode.java    # 🔴 本域的 form 值上限闸门用哪种拒绝形状 —— 对齐 Go 侧「该 api 有没有注册
│   │   │                   ├── MapStringValueDeserializer.java    # {@code map[string]string} 的元素级解析器 —— 对齐 go-zero {@code generateMap} 的判据。
│   │   │                   ├── PathNormalizingFilter.java    # 🔴 共享层的路径规范化 Filter —— 对齐 Go 的 {@code path.Clean}。
│   │   │                   ├── PlainTextErrors.java    # Go {@code http.Error} 的形状 —— 本模块「解析层失败」与「上传失败」两条路径的
│   │   │                   ├── RawStringListDeserializer.java    # {@code []string} 字段的「Go 风格」反序列化 —— 修 DRIFT-LIKEAPI-012。
│   │   │                   ├── RouterErrorResponses.java    # go-zero router 两种框架级错误响应的字节级复刻：404 与 405。
│   │   │                   ├── StringFields.java    # JSON 入参里 String 字段的输入解释规则 —— 对应 go-zero
│   │   │                   ├── StringInt64.java    # Go {@code json:",string"} int64 请求字段的逐条等价解析器。
│   │   │                   ├── TraceparentFilter.java    # 🔴 T-8：把 W3C {@code traceparent} 写进响应头 —— 对齐 go-zero 的
│   │   │                   └── ValueLimitInterceptor.java    # 🔴 Go 风格的 form 值上限闸门 —— 对齐 go-zero {@code rest/httpx} 的
│   │   └── resources/
│   │       └── META-INF/
│   │           └── spring/
│   │               └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── common/
│                       ├── autoconfigure/
│                       │   ├── AutoConfigurationImportsTest.java    # §3.3「机制可证」守门：starter 的装配清单真的生效。
│                       │   ├── ClientProtectionAutoConfigurationTest.java    # §3.3「机制可证」守门：客户端拦截器真的进了 devh 的收集器——
│                       │   ├── GrpcExceptionMapperAutoConfigurationTest.java    # §3.3「机制可证」守门：业务异常在 gRPC 边界被映射成 Go 形状的 status。
│                       │   ├── JsonBodyConverterAutoConfigurationTest.java    # §3.3「机制可证」守门：K-01 的强转规则真的施加到了 Boot 的 mapper 上。
│                       │   ├── LoggerAutoConfigurationTest.java    # §3.3「机制可证」守门：共享日志门面真的被 auto-config 装配出来（不需要域侧任何代码）。
│                       │   ├── ObservabilityAutoConfigurationTest.java    # §3.3「机制可证」守门：观测的判定顺序真的按 Go 的口径。
│                       │   ├── PersistenceAutoConfigurationTest.java    # §3.3「机制可证」守门：SQL 超时链路真的挂上了（DRIFT-COMMON-V02 的修法验证）。
│                       │   ├── ProtectionAutoConfigurationWiringTest.java    # §3.3「机制可证」守门：gRPC 拦截链真的挂到了 server 上（A3 的运行时证据），
│                       │   └── RouteIsolationFilterTest.java    # §3.3「机制可证」守门：熔断隔离 key 真的是 {@code METHOD://}，
│                       ├── contract/
│                       │   └── ApiResponseTest.java    # §3.3 真触发测试 —— {@link ApiResponse} 这个全局 L1 信封的序列化形状。
│                       ├── logger/
│                       │   └── GoStyleLoggerTest.java    # §3.3 真触发测试 —— 断言 **A8**：{@link GoStyleLogger} 是一个真正的
│                       ├── observability/
│                       │   ├── MqConsumerTest.java    # §3.3「机制可证」守门：MQ 消费端包装真的产出观测、真的按 Go 的口径分类错误。
│                       │   ├── ObservabilityPropertiesTest.java    # §3.3「机制可证」守门：Go {@code Telemetry} 段的 3 个配置字段真的能被绑定。
│                       │   ├── ObservabilityRuntimeTest.java    # §3.3「机制可证」守门：观测层的两个进程级池真的能被关闭钩子回收，且关掉后能重建。
│                       │   ├── RpcHandleRunnerTest.java    # §3.3「机制可证」守门：非 gRPC 路径的业务调用真的带超时控制 + 观测分类。
│                       │   └── RpcRecoverInterceptorTest.java    # §3.3「机制可证」守门：gRPC handler panic ⇒ {@code INTERNAL + "panic: "}。
│                       ├── protection/
│                       │   ├── MaxConnsFilterTest.java    # §3.3「机制可证」守门：并发连接闸门真的在超限时立刻 503（而不是排队）。
│                       │   ├── RouteTemplateResolverTest.java    # §3.3「机制可证」守门：路由模板解析真的能区分三态
│                       │   └── RpcProtectionLayerTest.java    # §3.3「机制可证」守门：gRPC 降载拒绝的 wire 形状 ＋ 熔断失败判据 ＋
│                       ├── redis/
│                       │   └── RedisAutoConfigurationActivationTest.java    # §3.3「机制可证」守门：Redis 侧两个自动配置真的被激活（DRIFT-COMMON-V01 的修法验证）。
│                       ├── security/
│                       │   └── SecurityUtilTest.java    # §3.3「机制可证」守门：**JWT / Bearer / 口令** 三条 L1 契约。
│                       ├── snowflake/
│                       │   └── SnowflakeTest.java    # §3.3 真触发测试 —— {@link Snowflake} 的位布局与单调性。
│                       └── web/
│                           ├── CoercionTest.java    # §3.3「机制可证」的真触发测试 —— {@link Coercion} 的两条规则真的作用在 Jackson 上。
│                           ├── Float64DeserializerTest.java    # §3.3 真触发测试 —— {@link Float64Deserializer} 的逐条 Go 判据。
│                           ├── JsonBodyConverterTest.java    # §3.3 真触发测试 —— {@link JsonBodyConverter} 的「Go 何时才读 body」判据 + 8 MiB 上限。
│                           ├── MapStringValueDeserializerTest.java    # §3.3 真触发测试 —— {@link MapStringValueDeserializer} 的 map 元素级判据。
│                           └── RawStringListDeserializerTest.java    # §3.3 真触发测试 —— {@link RawStringListDeserializer} 的 go-zero {@code json.Number} 语义。
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **starter 形态**：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册 7 个 `@AutoConfiguration` ⇒ 消费方**零 `@Import` 清单**。
- **错误契约逐域隔离**：各域错误码值/文案/语言**不可统一**（详见《第二轮深挖-契约盘点》§四）。
- **communityfact = 跨服务事件协议**：`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` 是 **L1 wire 类型**（comment·favorite·like 三域共享）。
- **CanonicalHash 用 RFC 8785 JCS** ⇒ Jackson 的 `SORT_PROPERTIES_ALPHABETICALLY` **不等价** ⇒ 指纹不一致会破坏幂等。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
