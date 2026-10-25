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
- `org.springframework.boot:spring-boot-kafka`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `io.github.resilience4j:resilience4j-spring-boot3`
- `io.grpc:grpc-stub`
- `io.grpc:grpc-protobuf`
- `io.jsonwebtoken:jjwt-api`
- `io.jsonwebtoken:jjwt-impl`
- `io.jsonwebtoken:jjwt-jackson`

## 三、目录树

```
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── rag/
│       │           └── common/
│       │               ├── autoconfigure/
│       │               │   ├── RagGrpcExceptionMapperAutoConfiguration.java
│       │               │   ├── RagJacksonAutoConfiguration.java
│       │               │   ├── RagMybatisPlusAutoConfiguration.java
│       │               │   ├── RagObservabilityAutoConfiguration.java
│       │               │   ├── RagProtectionAutoConfiguration.java
│       │               │   ├── RagSnowflakeAutoConfiguration.java
│       │               │   └── RagWebAutoConfiguration.java
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
│       │               │   └── MaxBytesFilter.java
│       │               ├── security/
│       │               │   ├── BearerTokenExtractor.java
│       │               │   ├── JwtUtil.java
│       │               │   └── PasswordUtil.java
│       │               ├── snowflake/
│       │               │   └── Snowflake.java
│       │               └── web/
│       │                   ├── GoStyleBadRequestException.java
│       │                   ├── GoStyleCoercion.java
│       │                   ├── GoStyleFloat64Deserializer.java
│       │                   ├── GoStyleGetFormValuesFilter.java
│       │                   ├── GoStyleGrpcStatusMapper.java
│       │                   ├── GoStyleHttpErrorException.java
│       │                   ├── GoStyleJsonBodyConverter.java
│       │                   ├── GoStyleJsonBodyConverterConfig.java
│       │                   ├── GoStyleJsonBodyLimitFilter.java
│       │                   ├── GoStyleMapStringValueDeserializer.java
│       │                   ├── GoStylePathNormalizingFilter.java
│       │                   ├── GoStylePlainTextErrors.java
│       │                   ├── GoStyleRawStringListDeserializer.java
│       │                   ├── GoStyleRouterErrorResponses.java
│       │                   ├── GoStyleStringFields.java
│       │                   └── GoStyleStringInt64.java
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
