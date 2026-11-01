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
│       │               │   ├── AuthorityFact.java    # Go 出处：wire.go 的 `type AuthorityFact struct`。
│       │               │   ├── AuthorityHandler.java
│       │               │   ├── CanonicalHash.java    # Go 出处：wire.go 的 `CanonicalHash(raw []byte) (string, error)` + `ValidHash(value)`。
│       │               │   ├── DeliveryStatus.java    # Go 出处：wire.go 的 `DeliveryPending / DeliveryAccepted / DeliveryFailed / DeliveryBlocked`（iota）。
│       │               │   ├── Dispatcher.java
│       │               │   ├── Event.java    # ⚠️ communityfact 是 comment / favorite / like 三域共享的跨服务事件协议
│       │               │   ├── EventDispatcher.java    # Go 出处：process.go 的 `type Dispatcher interface` + `RunDispatcher(...)`。
│       │               │   ├── EventSender.java    # Go 出处：wire.go 的 `type Sender interface` + `DCEventSender`。
│       │               │   ├── ProcessLogger.java
│       │               │   ├── ProcessMetadata.java    # Go 出处：logging.go 的 `type ProcessMetadata struct` + `Validate()`。
│       │               │   ├── SubjectRef.java    # Go 出处：wire.go 的 `type SubjectRef struct` + `ParseRTWSubject(value)`。
│       │               │   ├── TechnicalReceipt.java    # Go 出处：wire.go 的 `type TechnicalReceipt struct`。
│       │               │   └── Wire.java
│       │               ├── contract/
│       │               │   └── ApiResponse.java
│       │               ├── exception/
│       │               │   ├── AuthenticationException.java
│       │               │   └── BusinessException.java
│       │               ├── logger/
│       │               │   ├── FatalError.java
│       │               │   ├── GoStyleLogger.java
│       │               │   ├── LogOption.java
│       │               │   └── LogOptions.java
│       │               ├── observability/
│       │               │   ├── ClientDisconnectProbe.java    # 客户端断连的主动探测 —— 往 socket 写 1xx 探针，把断连提前暴露出来。
│       │               │   ├── GoStyleRequestDeadline.java
│       │               │   ├── ObservabilityCustomizer.java
│       │               │   └── ObservabilityProperties.java
│       │               ├── persistence/
│       │               │   ├── DeadlineQueryTimeoutInjector.java
│       │               │   ├── JsonTextTypeHandler.java
│       │               │   ├── JsonbTypeHandler.java
│       │               │   └── SqlTimeoutStatusTranslator.java
│       │               ├── protection/
│       │               │   ├── CpuThresholdHealthIndicator.java
│       │               │   ├── MaxBytesFilter.java
│       │               │   ├── RouteResolution.java    # 路由模板解析的三态结果。
│       │               │   └── RouteTemplateSource.java    # 「注册期路由模板」解析能力的窄接口。
│       │               ├── redis/
│       │               │   ├── ClientAutoConfiguration.java    # Lettuce 断连行为收口 —— 断连期立即拒单（对齐 Go 侧 fail-open 语义）。
│       │               │   ├── Deadline.java    # 共享层的 Redis 期限感知入口 —— 请求预算耗尽即拒发。
│       │               │   ├── DeadlineAutoConfiguration.java    # 把 Redis 期限感知接到各域的 RedisTemplate 上。
│       │               │   ├── DeadlineConnection.java    # 预算耗尽则不再下发 Redis 命令的连接包装。
│       │               │   └── DeadlineConnectionFactory.java    # 把请求预算接到 RedisConnectionFactory 上（域侧零改动的那一环）。
│       │               ├── security/
│       │               │   ├── BearerTokenExtractor.java
│       │               │   ├── JwtUtil.java
│       │               │   └── PasswordUtil.java
│       │               ├── snowflake/
│       │               │   └── Snowflake.java
│       │               └── web/
│       │                   ├── BadRequestException.java
│       │                   ├── Coercion.java
│       │                   ├── Float64Deserializer.java
│       │                   ├── GetFormValuesFilter.java
│       │                   ├── GrpcStatusMapper.java
│       │                   ├── HttpErrorException.java
│       │                   ├── JsonBodyConverter.java
│       │                   ├── JsonBodyConverterConfig.java
│       │                   ├── JsonBodyLimitFilter.java
│       │                   ├── JsonContentTypeAdvice.java    # JSON 响应的 Content-Type 统一带 charset=utf-8（对齐 go-zero httpx.doWriteJson）。
│       │                   ├── LimitExceededException.java    # 按 Go 语义「这个 query 该被拒」 —— 由各域兜底 handler 渲染成该域错误出口。
│       │                   ├── LimitMode.java    # form 值上限闸门的拒绝形状（RAW / ENVELOPE / OFF），由各域配置段给出。
│       │                   ├── MapStringValueDeserializer.java
│       │                   ├── PathNormalizingFilter.java
│       │                   ├── PlainTextErrors.java
│       │                   ├── RawStringListDeserializer.java
│       │                   ├── RouterErrorResponses.java
│       │                   ├── StringFields.java
│       │                   ├── StringInt64.java
│       │                   ├── TraceparentFilter.java    # W3C traceparent 头的透传 Filter。
│       │                   └── ValueLimitInterceptor.java    # Go 语义的 form 值上限闸门 —— 须是 HandlerInterceptor 才能把异常抛到域的 advice。
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
