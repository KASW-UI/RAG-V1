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
- `io.opentelemetry:opentelemetry-sdk-testing`
- `org.springframework:spring-test`
- `org.projectlombok:lombok`

## 三、目录树

```
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── rag/
│       │           └── common/
│       │               ├── autoconfigure/
│       │               │   ├── GrpcExceptionMapperAutoConfiguration.java
│       │               │   ├── JsonBodyConverterAutoConfiguration.java
│       │               │   ├── MybatisPlusOrderingBridgeAutoConfiguration.java
│       │               │   ├── ObservabilityAutoConfiguration.java
│       │               │   ├── ProtectionAutoConfiguration.java
│       │               │   ├── SnowflakeAutoConfiguration.java
│       │               │   └── WebAutoConfiguration.java
│       │               ├── communityfact/
│       │               │   ├── AuthorityFact.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│       │               │   ├── AuthorityHandler.java    # 对应 Go 版 service/common/communityfact/authority_http.go（80 行）
│       │               │   ├── CanonicalHash.java    # Go 出处：{@code service/common/communityfact/wire.go} 的
│       │               │   ├── DeliveryStatus.java    # ⚠️ L1 契约（取值 0/1/2/3 · D13「L1 以 Go 为准」） ——
│       │               │   ├── Dispatcher.java    # 对应 Go 版 {@code service/common/communityfact/process.go} 的
│       │               │   ├── Event.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│       │               │   ├── EventDispatcher.java    # 对应 Go 版 {@code service/common/communityfact/process.go}（69 行）的
│       │               │   ├── EventSender.java    # Go 出处：{@code service/common/communityfact/wire.go} 的
│       │               │   ├── ProcessLogger.java    # 对应 Go 版 service/common/communityfact/logging.go 的进程日志 + OTEL 本地追踪启动。
│       │               │   ├── ProcessMetadata.java    # Go 出处：{@code service/common/communityfact/logging.go} 的
│       │               │   ├── SubjectRef.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│       │               │   ├── TechnicalReceipt.java    # ⚠️ L1 wire 契约（D13「L1 以 Go 为准」） ——
│       │               │   └── Wire.java    # 共享层 wire 协议的门面 —— 对应 Go 侧
│       │               ├── contract/
│       │               │   └── ApiResponse.java    # 共享层的响应信封 —— 对应 Go 侧 {@code service/common/response/response.go}。
│       │               ├── exception/
│       │               │   ├── AuthenticationException.java    # 鉴权层失败 —— 与 {@link BusinessException} 的区别只有一条：HTTP 状态码是 401。
│       │               │   └── BusinessException.java    # Go 出处：{@code service/common/response/response.go:3-7}（信封三字段 code/msg/data）
│       │               ├── logger/
│       │               │   ├── FatalError.java    # H3 修复：致命错误的专用异常类型。
│       │               │   ├── GoStyleLogger.java    # Go 出处：{@code service/common/logger/logger.go}（整个包，356 行）
│       │               │   ├── LogOption.java    # Go 出处：{@code service/common/logger/logger.go:32}（{@code type LogOption func(*LogOptions)}）
│       │               │   └── LogOptions.java    # Go 出处：{@code service/common/logger/logger.go:26-29}
│       │               ├── observability/
│       │               │   ├── ClientDisconnectProbe.java    # 客户端断连的主动探测 —— 往 socket 写 1xx 探针，把断连提前暴露出来。
│       │               │   ├── DeadlineState.java    # 请求级 deadline 的服务端载体（ThreadLocal 绝对到期时刻，{@link System#nanoTime()} 域）。
│       │               │   ├── GoStyleRequestDeadline.java    # 请求级 deadline 的载体：把 {@link HttpMiddleware} 的 HTTP 超时预算，
│       │               │   ├── ObservabilityCustomizer.java
│       │               │   └── ObservabilityProperties.java
│       │               ├── persistence/
│       │               │   ├── DeadlineQueryTimeoutInjector.java    # B-1 缓解（第一半）：把当前 rpc 的 deadline 落到每条 SQL 上，让 SQL 的取消时刻 == deadline 时刻。
│       │               │   ├── JsonTextTypeHandler.java    # PostgreSQL {@code text} 列 ↔ {@code Map} 的 TypeHandler。
│       │               │   ├── JsonbTypeHandler.java    # jsonb 列 ↔ String 的 TypeHandler —— 对应 Go 端 PG 的 {@code jsonb} 列。
│       │               │   └── SqlTimeoutStatusTranslator.java    # B-1 缓解（第二半）：把「SQL 被 deadline 取消」翻译成 gRPC 的 {@code DEADLINE_EXCEEDED}。
│       │               ├── protection/
│       │               │   ├── CpuThresholdHealthIndicator.java
│       │               │   ├── MaxBytesFilter.java
│       │               │   ├── ProtectionProperties.java    # Go「请求保护层」四道中间件的配置聚合点。
│       │               │   ├── RouteResolution.java    # 路由模板解析的三态结果。
│       │               │   └── RouteTemplateSource.java    # 「注册期路由模板」解析能力的窄接口。
│       │               ├── redis/
│       │               │   ├── ClientAutoConfiguration.java    # L4 idiomatization：把 Lettuce 客户端的「断连行为」收进共享层，
│       │               │   ├── Deadline.java    # 共享层的「Redis 期限感知」入口（G1 反馈单 2/3 · 2026-10-30 · G4 lane）。
│       │               │   ├── DeadlineAutoConfiguration.java    # 把「Redis 请求期限感知」接到各域的 {@code RedisTemplate} / {@code StringRedisTemplate} 上。
│       │               │   ├── DeadlineConnection.java    # 把「请求预算耗尽 ⇒ 不再下发 Redis 命令」这一层包在 {@link RedisConnection} 上。
│       │               │   └── DeadlineConnectionFactory.java    # 把「请求预算」接到 {@link RedisConnectionFactory} 上（域侧零改动的那一环）。
│       │               ├── security/
│       │               │   ├── BearerTokenExtractor.java
│       │               │   ├── JwtUtil.java
│       │               │   └── PasswordUtil.java
│       │               ├── snowflake/
│       │               │   └── Snowflake.java    # 对应 Go 版 service/common/snowflake/snowflake.go 的整个包（102 行）。
│       │               └── web/
│       │                   ├── BadRequestException.java    # 承载「Go {@code httpx.ErrorCtx} 会写出的那种错误」—— 即裸状态码 + text/plain，
│       │                   ├── Coercion.java
│       │                   ├── Float64Deserializer.java
│       │                   ├── GetFormValuesFilter.java    # 🔴 Go 风格的 query 解析与 form 值上限判据 —— 对应 go-zero {@code rest/httpx} 的
│       │                   ├── GrpcStatusMapper.java    # Go {@code rest/internal/errcode/grpc.go} 的两条函数的 Java 等价物 ——
│       │                   ├── HttpErrorException.java    # Go {@code http.Error(w, err.Error(), )} 的载体。
│       │                   ├── JsonBodyConverter.java
│       │                   ├── JsonBodyConverterConfig.java    # ⚠️ K-02 · Go「何时才读请求体」的判据字面量（唯一真源）。
│       │                   ├── JsonBodyLimitFilter.java    # 🔴 DRIFT-002（已修）：JSON body 超过 8 MiB ⇒ 400。
│       │                   ├── JsonContentTypeAdvice.java    # 🔴🔴 **T-6**：让 JSON 响应的 {@code Content-Type} 与 Go 一致地带上 {@code charset=utf-8}。
│       │                   ├── LimitExceededException.java    # 🔴 「按 Go 的语义，这个 query 该被拒」 —— 由共享层的闸门抛出，
│       │                   ├── LimitMode.java    # 🔴 本域的 form 值上限闸门用哪种拒绝形状 —— 对齐 Go 侧「该 api 有没有注册
│       │                   ├── MapStringValueDeserializer.java
│       │                   ├── PathNormalizingFilter.java    # 🔴 共享层的路径规范化 Filter —— 对齐 Go 的 {@code path.Clean}。
│       │                   ├── PlainTextErrors.java    # Go {@code http.Error} 的形状 —— 本模块「解析层失败」与「上传失败」两条路径的
│       │                   ├── RawStringListDeserializer.java
│       │                   ├── RouterErrorResponses.java    # go-zero router 两种框架级错误响应的字节级复刻：404 与 405。
│       │                   ├── StringFields.java    # JSON 入参里 String 字段的输入解释规则 —— 对应 go-zero
│       │                   ├── StringInt64.java    # Go {@code json:",string"} int64 请求字段的逐条等价解析器。
│       │                   ├── TraceparentFilter.java    # 🔴 T-8：把 W3C {@code traceparent} 写进响应头 —— 对齐 go-zero 的
│       │                   └── ValueLimitInterceptor.java    # 🔴 Go 风格的 form 值上限闸门 —— 对齐 go-zero {@code rest/httpx} 的
│       └── resources/
│           └── META-INF/
│               └── spring/
│                   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
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
