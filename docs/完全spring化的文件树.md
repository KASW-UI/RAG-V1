# RAG：完全 Spring 化的理论 Java 文件树

> **本仓库**：`/Users/pr/Documents/GitHub/RAG-V1` —— 本文档是 **RAG-V1 的架构推导与决策依据**，本文档所在仓库即按 §5 的树生成。
> **推导范围**：RAG 全部 10 个业务域 + 共享层 `service/common`
> **对照对象**：
> - Go 真源：`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang`（冻结）
> - RAG 现状（Go 形态的第一版重构产物）：`/Users/pr/Documents/java&golang/RAG`（分支 `do-my-own-gh`）
> **推导原则**：Go 源码决定**迁移什么**；目标 Spring Boot 项目决定**如何组织**；Spring / Java 实际提供的机制决定**哪些实现职责可以被直接吸收**。
> **产出性质**：这是一棵**目标形态**的理论文件树（"完全 Spring 化"是什么样子）。**§5 的树已在本仓库落地为完整空文件树** —— **2026-10-26 D7 之后**的实测形态是 **16 个 pom / 559 个 Java 文件（549 主代码 + 10 测试骨架）/ 10 个 proto 集中在根级 `proto/` 模块**，实现待填。
> **配套文档**：[`决策记录.md`](决策记录.md)（**D1–D7 已定** / P1–P5 待拍板）· [`L3-降级清单.md`](L3-降级清单.md) · [`架构符合性断言.md`](架构符合性断言.md) · [`../AGENTS.md`](../AGENTS.md)（新 session 自动加载的根政策）
> **本文档同时是模板**：§1–§8 的结构与《Go → Spring Boot：基于目标项目既有架构与框架职责推导理论 Java 文件树》一致（12.1–12.7 输出要求 + 五问自检），可作为"推导读某个 Component"任务的投喂底本。

---

## 〇、推导前提

### 0.1 契约分层（L1–L4）

| 层 | 内容 | 本次推导中的处理 |
|---|---|---|
| **L1** 外部契约 | HTTP 路由与出入参、gRPC 方法与 proto、错误码取值、状态码 | **全部保留**。proto 不改一个字节号 |
| **L2** 业务语义 | 业务规则、状态转换、鉴权结果、读写含义、副作用与触发条件 | **全部保留** |
| **L3** 技术语义 | 事务边界、错误传播、超时重试降级、幂等、并发控制、序列化、资源生命周期、消息投递 | **部分保留，部分降级** —— 见 §0.4 的降级表 |
| **L4** 实现机制 | 依赖注入、配置绑定、拦截器、注册与生命周期、文件划分、样板代码 | **全面重选** —— 这正是"完全 Spring 化"的战场 |

### 0.2 基线数据（实测）

| 域 | Go 主代码文件 | RAG 主代码文件 | 倍率 |
|---|---|---|---|
| article | 43 | 81 | 1.88× |
| comment | 56 | 79 | 1.41× |
| favorite | 48 | 70 | 1.46× |
| follow | 44 | 48 | 1.09× |
| hot | 24 | 34 | 1.42× |
| like | 49 | 81 | 1.65× |
| message | 53 | 70 | 1.32× |
| task | 24 | 40 | 1.67× |
| users | 109 | 141 | 1.29× |
| **common** | **11** | **51** | **4.64×** |
| **合计** | **461** | **695** | **1.51×** |

RAG 侧另有 **63 个 `GoStyle*` 命名的类**（其中 19 个是测试文件 ⇒ **44 个主代码类**），`service/common/protection/` 一个包就占 **17 个主代码类**。

> **基线读法**：Go 侧 `service/common` 只有 11 个非测试 `.go`，因为熔断、降载、限连接、限体积、日志、HTTP 解析全是 **go-zero 内置**；RAG 侧 `service/common` 有 51 个主代码类，其中 17 个在重写 go-zero 的运行时。**这 4.64× 的倍率不是"翻译成本"，是"框架差距补齐成本"。**

### 0.3 六项关键决策（决定了整棵树）

| # | 决策 | 依据 | 代价 |
|---|---|---|---|
| **D1** | **api 与 rpc 合并为一个 app**（一域一进程） | Spring Cloud 的部署单元是"可部署服务"，不是"api 层 / rpc 层"；go-zero 的 api/rpc 分离源于 `.api` 文件与 `.proto` 两条 codegen 路径，是**语言产物**不是架构原则 | Go/RAG 20 进程 → 10 进程；Nacos 注册项减少；**rpc 进程不可用这一故障模式消失** |
| **D2** | **域内调用直连，不再自我 gRPC 往返** | 同进程内调 gRPC 是无意义的序列化开销与故障注入 | api 层的"gRPC code → 业务码"映射层消失；**同一份业务逻辑要能同时产出 HTTP 与 gRPC 两种错误表达**（见 §5.3 的统一错误模型） |
| **D3** | **`service/common` 升级为真 starter** | Spring 生态里共享能力层的标准形态是 starter（`AutoConfiguration.imports`），不是"普通 jar + 每个消费者手写 `@Import`" | 消除 10 份 `@Import` 清单（4~10 个 Bean 不等）与 **9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration`** |
| **D4** | **生态优先，手写只补生态缺口** | resilience4j / spring-cloud-stream / Micrometer Observation / SLF4J+MDC / Spring Data 已是成熟实现 | 部分 L3 语义降级（见 §0.4） |
| **D5** | **DTO 改 immutable record + Bean Validation** | Java 生态的数据契约形态 | 错误文案从 go-zero 的 `field "uid" is not set` 变为 Bean Validation 文案 ⇒ **L3 降级**，除非显式保留自定义 validator |
| **D6** | **跨域调用仍走 gRPC**（devh `@GrpcClient`） | proto 是 **L1 契约**，改传输就是改契约；且 devh starter 已是 Spring 生态做法 | 无。`api/client/` 因域内直连而清空，但跨域 client 需另置（§5.2 的 `client/`） |

> ⚠️ **D1 与 D4 共同决定了"完全 Spring 化"的核心特征**：**进程数减半、共享层瘦身后一半、每域少一套 Application/Properties/advice/metrics 样板**。

### 0.4 L3 降级表（"完全 Spring 化"要付的账）

| Go / RAG 现状 | 完全 Spring 化后的承载 | L3 状态 | 备注 |
|---|---|---|---|
| go-zero `httpx.Parse` 的必填校验文案 `field "uid" is not set` | Bean Validation（`@NotNull` + `ProblemDetail`） | 🔴 **降级** | 需逐字一致 ⇒ 自定义 `ConstraintValidator`，成本高于收益，**默认接受降级** |
| go-zero `mapping` 的类型强转判定（数字不进 String 字段等） | Jackson `CoercionConfig`（**RAG 已在用**，`GoStyleCoercion`） | 🟢 **保留** | 已是 Spring/Jackson 原生机制，只需改名与归位 |
| go-zero `withJsonBody` 判据（`ContentLength > 0` 且 Content-Type 字面含 `application/json`） | 自定义 `HttpMessageConverter.canRead` | 🟢 **保留**（L2 级等价） | 2 行判定，非框架能力，但极便宜 |
| go-zero `rest` 内置熔断（`Middlewares.Breaker`） | resilience4j `CircuitBreaker` | 🟡 **语义降级** | 失败判定窗口、half-open 探测策略不同；resilience4j 的默认值 ≠ go-zero 的默认值 |
| go-zero `rest` 内置 CPU 降载（`CpuThreshold` 900‰） | resilience4j **无等价** ⇒ 保留一个自研 `CpuThresholdHealthIndicator` | 🟡 **部分降级** | 生态缺口，只能自研，但只需 1~2 类（现状 17 类） |
| go-zero `rest` 内置 `MaxConns` / `MaxBytes` | Tomcat `max-connections` / `max-http-form-post-size` + `OncePerRequestFilter` | 🟢 **保留** | Spring Boot 已有对应配置项 |
| go-zero `logx` 输出格式（含 `call_chain` 字段） | **实现 SLF4J `Logger` 接口**的适配器 | 🟢 **保留** | 格式不变，但接入 Spring 的日志生态（配置/采集/traceId 自动注入） |
| go-zero `rest` 内置超时（`context.WithTimeout` 可取消在途 IO） | `DeadlineState` + `setQueryTimeout`（**RAG 已有**，E-4 修过） | 🟢 **保留** | Java 无法取消阻塞 IO，已用服务端取消语义等价替代 |
| go-zero `zrpc` 内置的 Trace/Recover/Stat/Prometheus/Breaker/Shedding 链 | Micrometer Observation + resilience4j + Spring Cloud Stream | 🟡 **语义降级** | 链路顺序、span 属性命名需逐条对拍 |
| `users` 域 `SchemaInitializer` 幂等重放 | 保留（已弃用 Flyway） | 🟢 **保留** | — |
| 事务边界 / 行级悲观锁 / 幂等键 / 游标编码 | 逐条保留 | 🟢 **保留** | 这些是业务语义，不是框架机制 |

> **结论**：完全 Spring 化 = **L1 全保、L2 全保、L3 保 6 降 4**。降级的 4 项全部集中在**框架内部行为**（错误文案、熔断策略、span 属性），**没有一项落在业务语义上**。

---

## 一、目标 Java 项目组织依据（spec 12.1）

### 现有包（RAG 现状）

```
api 模块                                     rpc 模块
├── advice/                                  ├── bootstrap/
├── config/                                  ├── config/
├── dto/                                     ├── domain/
├── metrics/                                 ├── exception/
├── client/                                  ├── mapper/
├── controller/                              ├── metrics/
├── interceptor/                             └── service/
└── service/                                     └── impl/
```

### 已有组件（可复用，无需重建）

| 组件 | 位置 | 承担的职责 |
|---|---|---|
| `GlobalResponseAdvice` / `GlobalExceptionHandler` | 各 api 模块 `advice/` | 统一信封 + 异常→HTTP 响应 |
| `AdminJwtAuthInterceptor` 等 | 各 api 模块 `interceptor/` | JWT 鉴权 + 黑名单 |
| `*Properties`（41 个文件用 `@ConfigurationProperties`） | 各模块 `config/` | 配置绑定 |
| `*GrpcImpl` | 各 rpc 模块 `service/impl/` | `@GrpcService` 实现 |
| `*DomainService` | 各 rpc 模块 `service/` | 业务用例 |
| `*Mapper`（`extends BaseMapper<Entity>`） | 各 rpc 模块 `mapper/` | 持久化 |
| `*Entity`（`@TableName`） | 各 rpc 模块 `domain/` | 实体 |
| `*Metrics` | 各模块 `metrics/` | Micrometer 指标 |
| `*RpcClient`（devh `@GrpcClient`） | 各 api 模块 `client/` | 跨域 gRPC 调用 |
| `*SchemaInitializer` / `*SeederBootstrap` / `NacosRegistration` | 各 rpc 模块 `bootstrap/` | 启动初始化 |
| `GoStyleJsonBodyConverter` | `common/http/` | 请求体读取判据 |
| `GoStyleCoercion` | `users/common/http/` | Jackson 强转配置（**已是原生机制**） |
| `GoStyleRequest` + `GoStyleRequiredFields` | `users/common/http/` | 必填校验（自研） |
| `MybatisPlusOrderingBridgeAutoConfiguration` | **9 个 rpc 模块各一份** | Boot 4 下 MP 自动配置排序桥 |

### 已确认的框架机制（RAG 已实测生效）

| 机制 | 证据 |
|---|---|
| `spring-boot-starter-web` + `spring-boot-starter-webmvc-test` | 10 个 api 模块 |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | 10+ 个模块 |
| `spring-boot-starter-data-redis` | 8 个模块 |
| `spring-boot-starter-validation` | rpc 侧 6 个模块（**api 侧 0 个**） |
| `spring-cloud-starter-alibaba-nacos-discovery` | **20 个模块（唯一在用的 Spring Cloud 组件）** |
| `grpc-server-spring-boot-starter`（devh） | 10 个 rpc 模块 |
| `grpc-netty-shaded` + `protobuf-maven-plugin` | 10 个 proto 模块 |
| MyBatis-Plus `BaseMapper` + `MybatisPlusInterceptor` | 持久化 |
| Boot 4 模块化迁移修复（`Jackson2ObjectMapperConfiguration`、`MybatisPlusOrderingBridgeAutoConfiguration`） | 已实测 |

### 当前 RAG 完全**没有**用到的 Spring 生态能力（= "完全 Spring 化"的增量空间）

| 能力 | RAG 现状 | 全仓引用数 |
|---|---|---|
| resilience4j / Spring Cloud CircuitBreaker | 自研 `GoStyle*Breaker` | **0** |
| spring-cloud-stream（Kafka binder） | 裸 `spring-kafka` + `@KafkaListener`（20 个类） | **0** |
| spring-retry（`@Retryable`） | 自研 `GoStyleRpcFailurePolicy` | **0** |
| Micrometer Observation（`ObservationRegistry`） | 自研 `GoStyle*Observability` | **0** |
| OpenFeign / `RestClient` / `WebClient` | 无（域内走 gRPC） | **0** |
| Bean Validation（`@Valid` / `@Validated`） | 自研 `GoStyleRequired*` | **0** |
| `ProblemDetail`（RFC 9457） | 自研 `Response` 信封 | **0** |
| ArchUnit / spring-modulith | 无 | **0** |

### 需要进一步确认的组织约定

| 事项 | 状态 |
|---|---|
| `rpc.*` 包在合并后是否改名 | ⬜ **UNKNOWN** —— 见 §5.2 的命名债说明 |
| `api/client/` 全部清空后是否保留空包 | ⬜ 待定 |
| 各域是否需要独立的 `-contract`（proto）模块 | ✅ **D7 已改** —— proto 是 L1 契约，但**不再各域一个模块**：10 个 `.proto` 集中到**根级 `proto/` 模块**（artifactId `proto`，pb 包名 `com.rag.proto.<域>`），域内 `contract/` 包只承载错误契约与事件 DTO |
| Flyway 账本表名策略在合并后的形态 | 🟡 合并后每域一套迁移 ⇒ 冲突面变小，但表名隔离仍需保留 |

---

## 二、Go 原始文件树（spec 12.2）

```
Sea-RideTheWind_golang/service/              # 13 个目录（knowledge/points/security 范围外）
├── common/                                   # 11 个非测试 .go —— 跨服务共享
│   ├── communityfact/                        # authority_http.go / process.go / wire.go / logging.go
│   ├── logger/logger.go
│   ├── observability/                        # observability.go / http.go / mq.go / rpc.go
│   ├── response/response.go
│   └── snowflake/snowflake.go
├── article/     ├── api/ └── rpc/
├── comment/     ├── api/ └── rpc/
├── favorite/    ├── api/ └── rpc/
├── follow/      ├── api/ └── rpc/
├── hot/         ├── api/ └── rpc/
├── knowledge/   ├── api/ └── rpc/             # 范围外
├── like/        ├── api/ └── rpc/
├── message/     ├── api/ └── rpc/
├── points/      ├── api/ └── rpc/             # 范围外
├── security/    ├── api/ └── rpc/             # 范围外
├── task/        ├── api/ └── rpc/
└── user/
    ├── admin/   ├── api/ └── rpc/
    ├── common/
    ├── rpc/
    └── user/    ├── api/ └── rpc/
```

### 以 `message` 域为样板（Go 侧完整文件清单，53 个主代码文件）

```
service/message/
├── api/
│   ├── etc/messagecenter.yaml
│   ├── messagecenter.go                          # main 入口
│   └── internal/
│       ├── config/config.go
│       ├── handler/
│       │   ├── routes.go                         # 13 个 endpoint 注册 + middleware
│       │   ├── message/                          # 8 个 handler
│       │   └── messageadmin/                     # 5 个 handler
│       ├── logic/
│       │   ├── message/                          # 8 个 logic
│       │   ├── messageadmin/                     # 5 个 logic
│       │   └── shared/helper.go
│       ├── metrics/metrics.go
│       ├── svc/servicecontext.go
│       └── types/types.go
├── common/errmsg.go
└── rpc/
    ├── etc/message.yaml
    ├── message.go                                # main 入口
    ├── messageservice/messageservice.go
    ├── pb/                                       # 生成物（message.pb.go / message_grpc.pb.go）
    └── internal/
        ├── config/config.go
        ├── logic/                                # 10 个 logic + helper.go
        ├── metrics/metrics.go
        ├── model/                                # data_model.go / db.go / message.go
        ├── server/messageserviceserver.go
        └── svc/servicecontext.go
```

> **Go 侧结构特征（= Go-ism 的具体来源）**：
> 1. `handler/` `logic/` `types/` `svc/` `metrics/` —— goctl 模板的固定分层
> 2. `servicecontext.go` —— 手工 DI 容器（Go 无 DI 框架的补偿）
> 3. `pb/` —— proto 生成物
> 4. api 与 rpc 各一个 `main` 入口 ⇒ 两个进程

---

## 三、Go 文件职责矩阵（spec 12.3）

> 每行回答：「文件」「主要职责」「业务语义（L2）」「实现机制（L4，可换）」

| Go 文件（message 域为样板） | 主要职责 | 业务语义（L2，必须保留） | 实现机制（L4，可换） |
|---|---|---|---|
| `messagecenter.go` / `message.go` | 进程入口：读 yaml → 起 server → 注册 handler → 装载统一响应/错误处理 | 统一响应包装（成功 `Code=Success`、失败业务码）；错误码还原 `{code,msg}` | `flag.Parse` / `conf.MustLoad` / `logx.MustSetup` / `rest.MustNewServer` / `zrpc.MustNewServer` |
| `etc/*.yaml` | 端口 / JWT / Redis / etcd key / Kafka / DB | 端口号、JWT secret 与有效期、Redis 地址、Kafka topic、DB 连接 | go-zero 的 `rest.RestConf` / `zrpc.RpcClientConf` 结构约定 |
| `internal/config/config.go` | Config struct | 实际依赖的配置块 | go-zero 结构体嵌入 |
| `internal/handler/routes.go` | 挂载路由 + 绑定 middleware | **路由前缀 `/message/v1`（L1，客户端按它寻址）**；全部 POST；middleware 必须先于业务执行 | `rest.WithMiddlewares` / `rest.WithJwt` / `rest.WithPrefix` |
| `internal/handler/*/*.go` | HTTP entry：解析 req → new logic → 调 logic → 包装响应 | HTTP 契约 + ctx-aware 错误传递 | `httpx.Parse` / `httpx.ErrorCtx` / `httpx.OkJsonCtx` |
| `internal/logic/*/*.go` | 业务用例：取身份 → 调 rpc → 错误码转换 → 指标 → 业务日志 | **核心业务规则**、错误码语义、reject 上报条件 | `logx.WithContext` / 自包 metrics |
| `internal/logic/shared/helper.go` | 跨 logic 复用辅助函数 | uid 解析的多类型兼容、日志字段、**error → 业务码的映射规则** | 无 |
| `internal/middleware/checkblacklistmiddleware.go` | JWT blacklist：解析 Bearer → 查 Redis → 命中 401 | **blacklist 校验是本域特定能力，不是通用 auth**；无 token 直接放行 | `rest.Middleware` 的 `func(next) http.HandlerFunc` 签名 |
| `internal/metrics/metrics.go` | Prometheus 指标定义 + Observe helper | **指标名与标签是业务定义**（`message_api_request_total{route,result}` 等） | Prometheus client_golang + `sync.Once` 注册 |
| `internal/svc/servicecontext.go` | 装配根：Config / Model / Redis / Rpc client / Middleware | （无业务方法时）纯装配 | `redis.MustNewRedis` / `zrpc.MustNewClient` / 手工 new |
| `internal/types/types.go` | HTTP DTO 定义 | **HTTP 进出参契约（L1）** | 纯数据 + json tag |
| `rpc/internal/model/*.go` | 实体 + DB 初始化 + CRUD | **持久化语义**：查询条件、更新行为、唯一约束 | GORM / `database/sql` |
| `rpc/internal/server/*serviceserver.go` | gRPC 方法分发 | **gRPC 方法契约（L1）** | goctl 自动生成的 router |
| `rpc/internal/logic/*.go` | gRPC 侧业务逻辑 | 业务规则 + **抛 gRPC status code 的错误语义** | `errmsg.NewGrpcErr(code,msg)` → `status.Error` |
| `rpc/messageservice/messageservice.go` | proto 生成物 | **proto 契约（L1）** | protoc |
| `service/common/*` | 共享：日志 / 响应信封 / 雪花 ID / 可观测 / communityfact | 跨服务共享的**输出格式与基础设施行为** | go-zero `logx` / `rest` / `zrpc` 内置能力的项目级封装 |

---

## 四、职责 → Java 映射表（spec 12.4）

> 承载方式：`NEW`（新增）/ `REUSE`（现有组件）/ `ABSORB`（框架机制）/ `SPLIT`（拆到多处）/ `MERGE`（并入同一组件）/ `ADAPT`（职责保留、形态调整）

### 4.1 入口与装配

| Go 来源及职责 | Java 目标包或现有机制 | 承载方式 | 依据与原因 |
|---|---|---|---|
| `messagecenter.go` main 入口 | `MessageServiceApplication.java` | **ADAPT** | `@SpringBootApplication` + `@EnableDiscoveryClient` |
| `messagecenter.go` 统一响应包装 | `api/advice/MessageApiResponseAdvice.java` | **SPLIT** | 现有 `GlobalResponseAdvice` 已是 `@RestControllerAdvice`；信封语义保留 |
| `messagecenter.go` 错误码还原 | `api/advice/MessageApiExceptionHandler.java` | **REUSE** | 现有 `GlobalExceptionHandler` 承担；**合并后不再需要"gRPC code → 业务码"这一层**（见 §5.3） |
| `routes.go` 路由注册 | `@RequestMapping` / `@PostMapping` | **ABSORB** | Spring MVC 注解；**前缀 `/message/v1` 是 L1，必须逐字保留** |
| `routes.go` middleware 绑定 | `api/config/WebMvcConfig.java` + `api/interceptor/JwtAuthInterceptor.java` | **ADAPT** | `rest.WithMiddlewares` → `HandlerInterceptor`；**注册顺序（interceptor 先于业务）属于 L3，必须保留并有断言** |
| `servicecontext.go` 装配根 | Spring DI 容器 | **ABSORB** | 无业务方法 ⇒ 不生成 `ServiceContext.java` |
| `servicecontext.go` 里的 `ensureDefaultAdmin` 之类 | `rpc/bootstrap/*Seeder.java` | **REUSE** | 现有 `AdminSeederBootstrap` / `ArticleSchemaGate` 承担；**含业务方法故不吸收** |

### 4.2 HTTP 层

| Go 来源及职责 | Java 目标包或现有机制 | 承载方式 | 依据与原因 |
|---|---|---|---|
| `types/types.go` 13 个 DTO | `api/dto/*`（immutable record） | **ADAPT** | 契约不变（L1），形态从可变 POJO → record（D5） |
| `httpx.Parse` 必填校验 | Bean Validation `@NotNull/@NotBlank` + `api/advice` | **SPLIT** | ⚠️ **L3 降级**：错误文案从 go-zero 改为 Bean Validation 文案 |
| `httpx.Parse` 类型强转判定 | `common/autoconfigure/JsonBodyConverterAutoConfiguration`（`CoercionConfig`） | **ADAPT** | **已是 Jackson 原生机制**（现存 `Coercion`），只需归位到 starter 并改名 |
| `httpx.Parse` 读取 body 判据 | `common/web/JsonBodyConverter`（→ `JsonBodyConverter`） | **REUSE** | 保留 `withJsonBody` 的字面判据（`ContentLength>0` + Content-Type 字面含 `application/json`） |
| `internal/logic/*/*.go`（13 个用例） | `api/service/*Service.java` | **KEEP→MERGE 视边界** | 13 个用例**合并为 1~2 个 Service**（见 §5.2 的"用例内聚"判据），因为合并后用例不再需要"构造 logic + 转交"这一层 |
| `internal/logic/shared/helper.go` | `api/service/*ApiSupport.java` | **MERGE** | 辅助函数与用例同属 HTTP 编排层；uid 解析 / 错误码映射保留 |
| `internal/middleware/checkblacklistmiddleware.go` | `api/interceptor/JwtAuthInterceptor.java` | **SPLIT** | 中间件签名 → `HandlerInterceptor`；**Redis 查询 + 无 token 放行规则保留** |
| `internal/metrics/metrics.go` | `api/metrics/MessageApiMetrics.java` | **ADAPT** | Micrometer 承担注册；**指标名与标签保留**（业务定义） |

### 4.3 gRPC 与领域层

| Go 来源及职责 | Java 目标包或现有机制 | 承载方式 | 依据与原因 |
|---|---|---|---|
| `rpc/internal/server/*serviceserver.go` | `grpc/*GrpcService.java` | **REUSE** | 现有 `@GrpcService` 实现（**D7 后改名**：`*ServiceGrpcImpl` → `*GrpcService`，包从 `rpc/service/impl/` 上收到 `grpc/`）；**proto 方法签名是 L1，一个字节都不改** |
| `rpc/internal/logic/*.go`（10 个） | `rpc/service/*DomainService.java` | **MERGE** | 合并后 HTTP 与 gRPC **共享同一份业务用例** ⇒ 由一个 DomainService 承担，gRPC 侧做 proto ↔ 领域模型转换 |
| `rpc/internal/model/*.go` | `rpc/domain/*Entity.java` | **ADAPT** | `@TableName` + MyBatis-Plus；**列类型/可空性/索引逐列对拍（DDL 探针）** |
| `rpc/internal/model/db.go` | `rpc/mapper/*Mapper.java` | **REUSE** | `BaseMapper` + XML/注解 SQL；**查询条件必须逐条保留** |
| `errmsg.NewGrpcErr(code,msg)` → `status.Error` | `rpc/exception/*` + `common/autoconfigure/GrpcExceptionMapperAutoConfiguration` | **SPLIT** | ⚠️ **关键设计点**：合并后业务层抛 `BusinessException(code,msg)`；gRPC 边界统一转 `StatusRuntimeException`，HTTP 边界统一转 HTTP 响应（见 §5.3） |
| `rpc/internal/svc/servicecontext.go` | Spring DI | **ABSORB** | — |
| `rpc/pb/*`（生成物） | `proto/`（根级 proto 模块，artifactId `proto`；**D7 后 10 域共用一个模块**） | **KEEP** | **L1 契约，一个字节都不改** |

### 4.4 跨域调用

| Go 来源及职责 | Java 目标包或现有机制 | 承载方式 | 依据与原因 |
|---|---|---|---|
| `svcCtx.FollowRpc.*`（同域） | `rpc/service/*DomainService`（**直连**） | **ABSORB**（D2） | 合并后同域不再走网络 |
| `svcCtx.UserRpc.GetUser`（article/api 跨域） | `client/ArticleRpcClient.java` | **SPLIT** | 跨域调用是**业务能力**（取作者信息），不是 HTTP 层能力 ⇒ 归入业务层；包位置需按 §3.3.8 说明 |
| `svcCtx.ArticleRpc.GetArticle`（hot/api 跨域） | 同上 | **SPLIT** | 同上 |
| devh `@GrpcClient` 的 TLS 默认值 | `grpc.client.*.negotiation-type: plaintext` | **ADAPT** | ⚠️ **L3 必须显式配置**，devh 默认 TLS，Go 是明文 |

### 4.5 共享层 `service/common` → starter

| Go 来源及职责 | Java 目标包或现有机制 | 承载方式 | 依据与原因 |
|---|---|---|---|
| `common/logger/logger.go`（logx 封装） | `common/logger/GoStyleLogger implements org.slf4j.Logger` | **ADAPT** | **格式与字段保留**（`call_chain` 等是行为契约），但接入 SLF4J 生态 |
| `common/response/response.go`（信封） | `common/contract/ApiResponse.java` | **ADAPT** | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不能用 `ProblemDetail` 替换**，只作为内部异常表达可用 |
| `common/snowflake/snowflake.go` | `common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | **REUSE** | 现有实现；**epoch/位段是业务契约（like 域游标依赖 `>>22`）** |
| `common/observability/*.go`（http/mq/rpc 三条链） | Micrometer `Observation` + OTel bridge | **ABSORB** | ⚠️ **L3 部分降级**：span 名称/属性需逐条对拍 |
| go-zero `rest` 内置熔断 | resilience4j `CircuitBreaker` | **ABSORB** | ⚠️ **L3 降级**：失败窗口/half-open 策略不同 |
| go-zero `rest` 内置 CPU 降载 | `common/protection/CpuThresholdHealthIndicator`（**自研 1 类**） | **NEW** | 生态缺口。现状 17 类 → 目标 1~2 类 |
| go-zero `rest` 内置 `MaxConns`/`MaxBytes` | Tomcat 配置 + `OncePerRequestFilter` | **ABSORB + NEW(1 类)** | `MaxConns` 是 Tomcat 原生项；`MaxBytes` 需一个 filter |
| `common/communityfact/*.go` | `common/communityfact/*`（保留） | **REUSE** | 业务特有（社区事实），无生态等价物 |
| —— | `common/autoconfigure/*` + `AutoConfiguration.imports` | **NEW** | **D3 的载体**；消除 10 份 `@Import` 清单与 9 份重复的 MP 桥 |

---

## 五、理论 Java 文件树（spec 12.5）★ 核心产物

### 5.1 仓库级结构

> ✅ **本节已按 D7（2026-10-26）落地后的真实结构更新** —— 16 个 `pom.xml`，`.proto` 集中在根级 `proto/` 模块，每域**一个** jar 模块，域内包**已压平**为单层技术角色包树。

```
RAG-V1/
├── pom.xml                                     # 父 pom：Boot 4 + Spring Cloud Alibaba + MyBatis-Plus
│                                               #   + resilience4j + spring-cloud-stream + spring-retry
├── common/                                     # ★ 由 service/common 升级为 starter（artifactId = common）
│   └── src/main/
│       ├── java/com/rag/common/
│       │   ├── autoconfigure/                  # ★ 新增：D3 的载体（7 个 @AutoConfiguration）
│       │   │   ├── WebAutoConfiguration.java
│       │   │   ├── JsonBodyConverterAutoConfiguration.java
│       │   │   ├── ObservabilityAutoConfiguration.java
│       │   │   ├── ProtectionAutoConfiguration.java
│       │   │   ├── SnowflakeAutoConfiguration.java
│       │   │   ├── MybatisPlusOrderingBridgeAutoConfiguration.java   # ★ 收编 9 份重复的 ordering bridge
│       │   │   └── GrpcExceptionMapperAutoConfiguration.java  # §5.3 的统一错误模型
│       │   ├── contract/
│       │   │   └── ApiResponse.java            # 信封（L1）
│       │   ├── web/                            # ★ Go 的 JSON / 表单 / 错误形状兼容层（原 common/http）
│       │   │   ├── JsonBodyConverter.java
│       │   │   ├── JsonBodyConverterConfig.java
│       │   │   └── RouterErrorResponses.java
│       │   ├── communityfact/                  # 跨服务事件协议（comment·favorite·like 三域共用）
│       │   ├── exception/                      # BusinessException / AuthenticationException
│       │   ├── logger/                         # ★ 瘦身：实现 SLF4J（GoStyleLogger）
│       │   ├── observability/                  # ★ 瘦身
│       │   ├── persistence/                    # JsonbTypeHandler / JsonTextTypeHandler / 超时注入 / 状态翻译
│       │   ├── protection/                     # ★ 17 类 → 2 类
│       │   ├── security/                       # JwtUtil / PasswordUtil / BearerTokenExtractor
│       │   └── snowflake/
│       └── resources/META-INF/spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports   # ★ 关键
├── proto/                                      # ★ D7：10 个域的 .proto 契约集中于此（artifactId = proto）
│   └── src/main/proto/                         #   admin / article / comment / favorite / follow /
│       ├── admin.proto                         #   hot / like / message / task / users
│       ├── article.proto                       #   ⇒ pb 类包名 com.rag.proto.<域>
│       ├── comment.proto
│       ├── favorite.proto
│       ├── follow.proto
│       ├── hot.proto
│       ├── like.proto
│       ├── message.proto
│       ├── task.proto
│       └── users.proto
├── service/                                    # 聚合 pom（modules = 9）
│   ├── users/                                  # ★ users 域 = 一个域、两个子域
│   │   ├── common/                             #   公共库（artifactId = users-common）
│   │   ├── user/                               #   user 子域（artifactId = user-service）
│   │   └── admin/                              #   admin 子域（artifactId = admin-service）
│   ├── article/    ├── comment/    ├── favorite/
│   ├── follow/     ├── hot/        ├── like/
│   └── message/    └── task/                   # ★ 每域**一个** jar 模块（artifactId = <域>-service）
│                                               #   域内错误契约与事件 DTO 在 com.rag.<域>.contract 包
├── tools/                                       # difftest / semantic-probe / 守门脚本
└── docs/
```

> **proto 模块保留的理由**：proto 是 **L1 外部契约**，跨服务共享。把它塞进 app 模块会让消费方依赖整个应用。**契约模块与实现模块分离是 Spring 生态的常规做法，不是 Go-ism。**
> ⚠️ **D7 后与「每域一个契约模块」的区别**：错误码与事件 DTO 是**逐域隔离**的（AGENTS §五.2 R1）⇒ 放进 `common/` 是错的；但为每域单开一个 Maven 坐标收益不足 ⇒ **错误契约落进域模块的 `contract/` 包**，proto 单独集中到根级模块。

### 5.2 每域内部的包结构（以 `message` 域为样板，10 域同构）

> ✅ **D7（2026-10-26）落地后的形态** —— `api/` + `rpc/` 两层**已压平**为单层技术角色包树。

```
service/message/
├── pom.xml                                     # artifactId = message-service（目录 ≠ artifactId）
└── src/main/
    ├── java/com/rag/message/
    │   ├── MessageServiceApplication.java      # @SpringBootApplication @EnableDiscoveryClient
    │   │                                       #   （api + rpc 合并后只剩一个）
    │   │
    │   ├── advice/                             # ===== HTTP 边界（原 api/advice）=====
    │   │   ├── MessageApiResponseAdvice.java    # 统一信封 {code,msg,data}
    │   │   └── MessageApiExceptionHandler.java  # @RestControllerAdvice
    │   ├── config/                             # api + rpc 合并
    │   │   ├── MessageApiProperties.java       # @ConfigurationProperties(prefix="message-api")
    │   │   ├── MessageProperties.java          # @ConfigurationProperties(prefix="message")
    │   │   └── WebMvcConfig.java               # 注册 JwtAuthInterceptor + public 路径白名单
    │   ├── controller/                         # ===== HTTP 入口（原 api/controller）=====
    │   │   ├── MessageController.java           # @RequestMapping("/message/v1")
    │   │   └── MessageAdminController.java      # @RequestMapping("/message/v1/admin")
    │   ├── dto/                                # ★ 全部 immutable record
    │   │   ├── GetConversationMessagesReq.java
    │   │   ├── GetConversationMessagesResp.java
    │   │   └── …（17 个用例的 req/resp 对）
    │   ├── interceptor/                        # ===== HTTP 边界（原 api/interceptor）=====
    │   │   ├── MessageJwtAuthInterceptor.java   # JWT 验签 + Redis blacklist
    │   │   └── MessageAdminJwtAuthInterceptor.java
    │   ├── metrics/                            # api + rpc 合并
    │   │   ├── MessageApiMetrics.java
    │   │   ├── MessageRpcMetrics.java
    │   │   └── MessageMetrics.java
    │   ├── service/                            # api + rpc 合并
    │   │   ├── MessageApiService.java          # ★ 用例内聚（判据见 §5.4）
    │   │   ├── MessageApiSupport.java           # uid 解析 / 日志字段 / 业务码映射
    │   │   └── MessageRpcSupport.java
    │   │
    │   ├── bootstrap/                          # ===== 领域 / 服务端能力层（原 rpc/bootstrap）=====
    │   │   └── NacosRegistration.java           # 显式注册（不依赖自动注册）
    │   ├── client/                             # ★ 跨域 gRPC 客户端（原 rpc/client）
    │   │   ├── AdminRpcClient.java
    │   │   └── UserRpcClient.java
    │   ├── domain/                             # ===== 领域模型（原 rpc/domain）=====
    │   │   ├── entity/                         #   ★ 按是否 *Entity 拆分
    │   │   │   ├── ConversationEntity.java
    │   │   │   ├── ConversationMessageEntity.java
    │   │   │   └── NotificationEntity.java
    │   │   └── service/                        #   ★ 领域服务另出一层
    │   │       ├── MessageDomainService.java   # ★ 业务用例（HTTP 与 gRPC 共享）
    │   │       └── MessageAtomicService.java
    │   ├── exception/                          # 业务异常工厂（原 rpc/exception）
    │   │   └── MessageExceptions.java
    │   ├── grpc/                               # ★ gRPC 服务实现（原 rpc/service/impl）
    │   │   └── MessageGrpcService.java         #   @GrpcService（proto ↔ 领域模型转换）
    │   ├── mapper/                             # MyBatis-Plus BaseMapper（原 rpc/mapper）
    │   │   ├── ConversationMapper.java
    │   │   ├── ConversationMessageMapper.java
    │   │   ├── NotificationMapper.java
    │   │   └── GoStylePagination.java
    │   └── contract/                           # ★ 域内错误契约 + 事件 DTO（D7）
    │       ├── CodeError.java
    │       ├── ErrCodes.java
    │       ├── ErrMsg.java
    │       └── GrpcErrorMapper.java
    └── resources/
        ├── application.yml
        └── db/migration/                       # 幂等 DDL 脚本
```

> ⚠️ **与 §5.5 的对应关系**：`common/` 的结构在 §5.5；本节是**域内**结构。
> ⇒ **逐域 B（RAG）→ C（RAG-V1）包对照**（实测）⇒ 见 [`Mapping_Table/`](Mapping_Table/_INDEX.md) 各域文档 §0.5。

#### 关于 `client/` 这个包（§3.3.8 要求的说明）

> 📌 **时点标注（2026-10-26 · D7）** —— 本小节原文写的是「`rpc.client/`」（包在 `rpc/` 协议层之下）。
> **D7 已把 `api/` + `rpc/` 两层压平** ⇒ 该包现在的落点是**单层的 `client/`**（见 §5.2 的树）。下文的职责论证**未变**。

- **职责**：跨域调用下游 gRPC 服务（如 `message` 域取 `user` 信息、`hot` 域取 `article` 内容）。
- **现有包为何无法承载**：`api/client/` 在合并后**域内调用消失**，只对跨域调用适用；但跨域调用是**业务能力**（取作者资料、批量补齐文章），由 `api/service/` 承担会造成"HTTP 层依赖下游服务"的错误依赖方向。
- **实际依据**：Go 侧 `svcCtx.UserRpc.GetUser` 出现在 `article/api/internal/logic/article/helper.go` —— 是**业务逻辑的一部分**，不是 HTTP 入口的一部分。合并后该 helper 落在业务层。
- **最小范围**：新增 1 个包，不新增顶层模块，不改动既有包职责。

#### 关于 `rpc.*` 的命名债（**已由 D7 结清**）

> 📌 **时点标注（2026-10-26 · D7）** —— 本小节原文记录的是「合并后 `rpc.*` 承载的是『领域 / 服务端能力层』，语义上更准确的名字是 `domain.` 或 `service.`；**本次不改** ⇒ 记为技术债，在 `docs/` 单独立项」。
> ✅ **该技术债已结清** —— D7 把 `api/` + `rpc/` **两层协议层整体压平**，`rpc.*` 前缀**不复存在** ⇒ 不再需要「改 `rpc.*` 的名字」这件事。
> ⇒ 当时「不改」的理由（波及 38 个组件的全部 import + 11,011 行映射表）已由 D7 一次性付清；
> ⇒ **遗留的只有 P4-④ 的剩余 6 个**（`common/logger/` 1 · `common/observability/` 1 · 域内 4）—— 其余 23 个已改（D8），见 [`命名债与包形状.md`](命名债与包形状.md) §二。

### 5.3 统一错误模型（D2 带来的最关键设计）

Go 侧同一份业务错误在两处各写一遍：api 层把 gRPC status 映射成业务码，rpc 层抛原始 status。合并后只有一份业务逻辑，因此需要**一个业务异常 + 两个边界适配器**：

```
                    ┌─────────────────────────────┐
                    │  BusinessException(code,msg) │   ← 业务层唯一抛出的异常类型
                    └──────────┬──────────────────┘
                               │
              ┌────────────────┴────────────────┐
              ↓                                 ↓
   RagGrpcExceptionMapper              @RestControllerAdvice
   （gRPC 边界）                        （HTTP 边界）
   → StatusRuntimeException             → HTTP 200 + {code,msg}
     .withCode(code)                       或 400/401（按 Go 的形状）
```

| 边界 | Go 侧行为 | 完全 Spring 化后 | L3 |
|---|---|---|---|
| gRPC | `status.Error(codes.Code(code), msg)` —— **业务码被塞进 gRPC status code 字段** | `BusinessException` → mapper → `StatusRuntimeException` | 🟢 保留（code 值与 msg 逐字一致） |
| HTTP | api 层 `switch` 把 gRPC code 映射回业务码 | `@RestControllerAdvice` 直接读 `BusinessException.code` | 🟢 保留（结果等价，路径更短） |
| HTTP 错误形状 | `httpx.Error` ⇒ 400 + text/plain | `@RestControllerAdvice` ⇒ 400 + text/plain | 🟢 保留（已由 `RouterErrorResponses` 实现） |

> ⚠️ **注意**：`like` 域的 rpc 侧错误码空间是 like 自己的（2001/5001/…），**不是 gRPC 标准码**。这一条在合并后**必须原样保留** —— 它是 §4.3 中"业务码塞进 gRPC status code 字段"的行为契约。

### 5.4 "用例内聚"判据（§7.2 的落地规则）

合并后 API 层的 13 个用例是否合并成 1 个 Service？**判据如下（不是"文件少"）**：

| 判据 | 说明 |
|---|---|
| ① 同一入口聚合 | 全部 13 个用例挂在同一个 `@RequestMapping("/message/v1")` 的 Controller 上 ⇒ 同一 REST resource |
| ② 一致的变化原因 | 改路由前缀/鉴权/响应信封时，13 个用例**必然一起变** ⇒ 同属一个变更单元 |
| ③ 无独立生命周期 | 13 个用例无各自的初始化 / 关闭钩子 |
| ④ 无独立契约 | 13 个用例无独立接口（无第三方按用例复用） |
| ⑤ 合并后职责不模糊 | 合并后仍是"HTTP 编排层"，不含持久化、不含业务规则 |

⇒ **5 条全中 ⇒ 合并为 1 个 `MessageApiService`。**
⇒ 对照：Go 侧的 13 个 `logic` 文件之所以是 13 个，**是因为 goctl 按 RPC 生成**（codegen 产物），不是职责边界。

> ⚠️ **但业务规则密集的域不适用**。`follow` 域的 8 个用例中有 2 个带**专属业务规则**（`forbidden_scope` 私密性约束、proto → DTO 列表转换）⇒ 若合并，这些规则会埋进一个大类。判据 ⑤ 失败 ⇒ 拆为 `FollowRelationService`（4 个纯转发）+ `FollowQueryService`（4 个带规则）。

> ⚠️ **2026-10-25 修正：判据 ⑤ 的适用范围仅限 api 层。**
>
> 原表述暗示"每个域收敛成 1 个 Service"，落地时发现**rpc 层的协作者类不是用例，不能合并**：
>
> | 类型 | 例子 | 能否合并 | 理由 |
> |---|---|---|---|
> | 业务用例 | `LikeDomainService` / `MessageDomainService` | ✅ 合并 | 一个用例一个类是 goctl 生成模板的产物 |
> | 领域协作者 | `LikeRedisKeys` / `TaskDefs` / `CommentSensitiveFilter` / `FavoriteFactEnvelopeFactory` / `GoCompatibleJson` | ❌ **保留独立** | 它们**不是用例**，是常量表与算法片段，各自的变化原因独立（如 `TaskDefs` 只随 task 域的奖励规则变） |
> | 事务边界类 | `LikeActionScript` / `FollowBlockTransactionService` / `CommentTransactionalService` | ❌ 保留独立 | 事务边界是独立的 L3 关注点，必须能被单独审视 |
>
> ⇒ 实测落地：`like` 域 `rpc/service/` = 1 个 `LikeDomainService` + **12 个协作者**；`comment` 域 = 1 个 + **11 个**。
> ⇒ **这条修正直接推翻了 §6.1 中"降幅最大的是样板"的乐观预期** —— 业务侧几乎没降。

### 5.5 共享层 `common` 的目标结构

```
common/
└── src/main/
    ├── java/com/rag/common/
    │   ├── autoconfigure/                       # ★ 7 类（starter 的全部装配入口）
    │   ├── contract/                             # 1 类（全局信封）
    │   ├── web/                                  # ★ 17 类（见下方修正说明）
    │   ├── observability/                        # 3 类（Observation + Deadline）
    │   ├── protection/                           # 2 类（CPU 降载指标 + MaxBytes filter）
    │   ├── logger/                               # 4 类（SLF4J 适配器 + 日志选项）
    │   ├── security/                             # 3 类（JWT + 密码）
    │   ├── exception/                            # 2 类（业务异常 + 认证异常）
    │   ├── persistence/                          # 4 类（Deadline 注入 + JSONB TypeHandler）
    │   ├── snowflake/                            # 1 类
    │   └── communityfact/                        # 4 类（保留，业务特有）
    └── resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

> ⚠️ **2026-10-25 修正（原表把 `common` 估成 22 类，实测落地 47 类）**
>
> 偏差来源是原表把 `web/` 只数成 3 个。实际归位到 `web/` 的有 **17 个**：
> - `article-api` 的 4 个 http/ 异常类 + 3 个 filter（`GoStyleBadRequestException` / `GoStyleHttpErrorException` / `GoStylePlainTextErrors` / `GoStyleGrpcStatusMapper` / `GoStyleGetFormValuesFilter` / `GoStyleJsonBodyLimitFilter` / `GoStylePathNormalizingFilter`）
> - `users-common` 的 4 个 http/ 解析类（`GoStyleCoercion` / `GoStyleStringInt64` 等）
> - 各域的 5 个 Jackson deserializer（`GoStyleFloat64Deserializer` / `GoStyleRawStringListDeserializer` / `GoStyleMapStringValueDeserializer` / `GoStyleStringFields` / `GoStyleStringInt64`）
> - `GoStyleJsonBodyConverter` + `GoStyleJsonBodyConverterConfig` + `GoStyleRouterErrorResponses`
>
> 另有两个原表未列的包：`security/`（3 类，来自 `users-common/jwt` + `cryptx`）、`exception/`（2 类，来自 `common/exception`）。
>
> ⇒ **真实缩减是 51 → 47（−8%），不是 −57%。** 缩减幅度远小于原估算，因为**生态吸收主要发生在 `protection`（17 → 2），而 `web/` 这一层的 Go 语义等价物必须手写**（Jackson 的 `CoercionConfig` 是原生机制，但"何时读 body"与"错误文案形状"不是）。

#### 5.5.1 七个 `@AutoConfiguration` 的职责契约（2026-10-26 钉死）

> 559 个空壳的 javadoc 都写着「推导依据见《完全spring化的文件树》对应条目」⇒ **本表就是那 7 个 auto-config 的「对应条目」**。
> ⚠️ **每个 auto-config 都要写 `@ConditionalOnMissingBean`** ⇒ 消费方可用自己的 Bean 覆盖默认行为。

| auto-config | 收编自（RAG 侧） | 提供的 Bean / 装配 | Go 出处 |
|---|---|---|---|
| `WebAutoConfiguration` | `spring/HttpMiddlewareFilter` + `spring/AuthorityHandlerFactory` | servlet filter 注册 + `ServletRegistrationBean`（communityfact 权威端点） | `common/authority_http.go`（`/internal/v1/community/facts`） |
| `JsonBodyConverterAutoConfiguration` | `http/GoStyleJsonBodyConverterConfig` + `users-common/http/GoStyleCoercion` | Jackson `CoercionConfig`（K-01 类型强转判定）+ `JsonBodyConverter` 注册（K-02 body 读取判据） | `httpx.Parse` 内置强转 + `httpx.OkJsonCtx` |
| `ObservabilityAutoConfiguration` | `spring/ObservabilityLifecycle` + `spring/ObservabilityProperties` + `observability/RpcInterceptor` + `observability/HttpMiddleware` + `observability/MqConsumer` + `observability/GoStyleRecoverInterceptor` + `spring/RpcServerInterceptorFactory` | Micrometer `Observation` + **业务观测 `ServerInterceptor`**（`ServerInterceptor` bean，7 个业务 gRPC code 集）+ recover 拦截器 | `observability/rpc.go` + `observability/http.go` + `observability/mq.go` |
| `ProtectionAutoConfiguration` | `protection/GoStyleProtectionConfiguration` + `GoStyleProtectionProperties` + 熔断/降载/限连接/限体积族 | resilience4j `CircuitBreaker` + Tomcat `max-connections` + `MaxBytesFilter` + CPU 指标 **＋ `GrpcServerConfigurer`（gRPC 保护链的唯一挂载点，见 §5.5.2）** | go-zero `zrpc` 内置熔断/降载 + `rest` 内置 `MaxConns`/`MaxBytes`/`CpuThreshold` |
| `SnowflakeAutoConfiguration` | `spring/SnowflakeBean` + `spring/SnowflakeProperties` + `snowflake/Snowflake` | 唯一的 `Snowflake` Bean（`@ConditionalOnMissingBean` ⇒ 容器内**有且仅有 1 个**） | `common/snowflake/snowflake.go`（`Init` / `GetID` / `generateNodeId`） |
| `MybatisPlusOrderingBridgeAutoConfiguration` | **9 个 rpc 模块各一份**的 `MybatisPlusOrderingBridgeAutoConfiguration` | Boot 4 下 MP 自动配置排序桥（**消掉 9 份重复**） | ——（Boot 4 迁移产物） |
| `GrpcExceptionMapperAutoConfiguration` | `article-api/http/GoStyleGrpcStatusMapper` | gRPC code → 业务码的**统一错误模型**（§5.3） | `errmsg.NewGrpcErr(code,msg)` → `status.Error` |

#### 5.5.2 gRPC 保护链的挂载点 —— **合并形态下只需 1 份**（2026-10-26 新增）

> ⚠️ **这是 D1 合并带来的一处真实收益**，也是 A3 断言必须改写的原因。

**Go 侧（范围内 10 个服务逐条显式）**：

```go
// service/{like,comment,follow,message,favorite,task,hot,article}/rpc/*.go
// service/user/{user,admin}/rpc/*.go
s.AddUnaryInterceptors(observability.NewUnaryServerInterceptor(rpcTimeout, observability.SlowThreshold()))
```

> `security` / `points` 两个服务**不在迁移范围**（§1.0 范围外）⇒ 范围内 **10 个**，与 RAG-V1 的 **10 个域 / 10 个 `*GrpcService`** 一一对应。

**RAG 侧（形态是 api/rpc 分离 ⇒ 每 rpc 模块各写一遍）**：10 个 rpc 模块里**只有 6 个**真挂了 `GrpcServerConfigurer`（like / comment / follow / favorite / task / hot）⇒ **message / user / admin 三个域的拦截链静默失效**。RAG 自己的 javadoc 也承认「锁死用例 `LikeGrpcInterceptorWiringTest` **全仓不存在**」。

**RAG-V1 侧（合并形态 ⇒ 只需 1 份）**：

```
ObservabilityAutoConfiguration   →  提供 ServerInterceptor bean（业务观测拦截器）
        ↓ 依赖
ProtectionAutoConfiguration      →  提供 GrpcServerConfigurer bean
                                    server -> server.intercept(recover → breaker → shedding → 业务观测拦截器)
```

**为什么 1 份就够**：devh 的 `GrpcServerFactoryAutoConfiguration` 三个工厂方法**全都只注入 `List<GrpcServerConfigurer>`** ⇒ 容器里**每一个** `GrpcServerConfigurer` 都会被应用到它创建的 server；而「一域一进程」⇒ **每进程只有 1 个 gRPC server** ⇒ 1 个全局 Configurer 即完整覆盖 10 个域。

⚠️ **两条必须同时成立的约束**（缺任一条都静默失效）：

| # | 约束 | 失效表现 |
|---|---|---|
| ① | `common/` 必须提供 `ServerInterceptor` **bean** | Bean 不存在 ⇒ 链装不上 |
| ② | `common/` 必须提供 `GrpcServerConfigurer` **且真调 `server.intercept(...)`** | Bean 在、starter 不用它 ⇒ **超时 / deadline 传播 / gRPC span / `sea.slow` 标记全丢，且零报错** |

⚠️ **反向约束（防重复挂载）**：**域内不得再出现 `GrpcServerConfigurer`** —— 若域里也写一个，starter 会把**两个** Configurer 都应用 ⇒ **同一条链被挂 2 次** ⇒ span 重复、timeout 叠加。
⇒ 由此得到合并形态版的 **A3 断言**（见 [`架构符合性断言.md`](架构符合性断言.md) A3 · 契约 B）：**common 有挂载点 ＋ 域内 0 重复**。

| 指标 | RAG 现状 | RAG-V1 实测 | 变化 |
|---|---|---|---|
| `service/common` 主代码类 | 51 | **47** | **−8%** |
| ├ `common/protection` | 17 | **2** | **−88%**（生态吸收） |
| ├ `common/web` | 9 | **17** | **+89%**（归位 + 跨域去重前的合流） |
| ├ `common/logger` | 6 | **4** | −33% |
| ├ `common/persistence` | 2 | **4** | +100%（JSONB TypeHandler 去重后收进共享层） |
| ├ `common/security`（原 `users-common/jwt`+`cryptx`） | 3 | **3** | 持平（从 users 域提升为全局） |
| ├ `common/exception` | 2 | **2** | 持平 |
| 手写 `@Import` 清单 | 10 份（4~10 Bean 不等） | **0** | **消除** |
| 重复的 MP ordering bridge | 9 份 | **0**（收进 starter 的 1 份） | **−100%** |

---

## 六、文件变化表（spec 12.6）

> 关系：`KEEP` 仍需独立载体且保留独立文件 / `ADAPT` 职责保留、形态调整 / `ABSORB` 由框架机制直接承担 / `REUSE` 由现有组件承担 / `SPLIT` 拆到多个承载位置 / `MERGE` 并入同一组件

| Go 来源文件 | Java 理论文件或现有机制 | 关系 | 原因 |
|---|---|---|---|
| `*/api/*center.go`（main） | `*ServiceApplication.java` | **ADAPT** | main → Spring Boot 入口；**api 与 rpc 的两个入口合并为一个** |
| `*/rpc/*.go`（main） | 同上 | **MERGE** | **D1：两个进程 → 一个** |
| `*/api/etc/*.yaml` + `*/rpc/etc/*.yaml` | `application.yml`（合并为一个） | **MERGE** | 配置项并入一份；端口段区分 `server.port` 与 `grpc.server.port` |
| `*/api/internal/config/config.go` | `api/config/*ApiProperties.java` + `rpc/config/*RpcProperties.java` | **SPLIT** | 两套配置块本就不同；`@ConfigurationProperties` 聚合 |
| `*/rpc/internal/config/config.go` | 同上 | **REUSE** | — |
| `*/api/internal/handler/routes.go` | `@RequestMapping` + `api/config/WebMvcConfig.java` | **ABSORB + SPLIT** | 路由由注解；**前缀是 L1 必须逐字保留**；middleware 绑定 → interceptor 注册 |
| `*/api/internal/handler/*/*.go` | `api/controller/*Controller.java` | **MERGE** | 同一 REST resource ⇒ 单一 Controller |
| `*/api/internal/logic/*/*.go` | `api/service/*ApiService.java` | **MERGE** | 按 §5.4 五问判据合并；**业务规则密集的域例外拆分** |
| `*/api/internal/logic/shared/helper.go` | `api/service/*ApiSupport.java` | **MERGE** | 与用例同层 |
| `*/api/internal/middleware/*.go` | `api/interceptor/JwtAuthInterceptor.java` | **SPLIT** | 中间件签名 → `HandlerInterceptor`；业务语义保留 |
| `*/api/internal/metrics/metrics.go` | `api/metrics/*ApiMetrics.java` | **REUSE** | 指标名与标签 1:1 |
| `*/api/internal/svc/servicecontext.go` | （无独立文件） | **ABSORB** | 纯装配 ⇒ Spring DI |
| `*/api/internal/types/types.go` | `api/dto/*.java`（record） | **ADAPT** | 契约不变（L1），形态改 immutable |
| `*/rpc/internal/server/*server.go` | `grpc/*GrpcService.java` | **REUSE** | proto 方法是 L1（**D7 后改名 + 上收到 `grpc/` 包**） |
| `*/rpc/internal/logic/*.go` | `domain/service/*DomainService.java` | **MERGE** | **D2：HTTP 与 gRPC 共享同一份业务用例**（**D7 后领域服务另出 `domain/service/`**） |
| `*/rpc/internal/model/*.go`（实体） | `domain/entity/*Entity.java` | **REUSE** | `@TableName` + MyBatis-Plus（**D7 后拆到 `domain/entity/`**） |
| `*/rpc/internal/model/db.go` | `rpc/mapper/*Mapper.java` | **REUSE** | `BaseMapper` |
| `*/rpc/internal/svc/servicecontext.go` | （无独立文件） | **ABSORB** | 纯装配 |
| `*/rpc/pb/*` | `proto/`（根级 proto 模块） | **KEEP** | **L1 契约** |
| `common/logger/logger.go` | `common/logger/GoStyleLogger implements slf4j.Logger` | **ADAPT** | 格式保留，接入 SLF4J 生态 |
| `common/response/response.go` | `common/contract/ApiResponse.java` | **REUSE** | 信封是 L1，**不能用 `ProblemDetail` 替换** |
| `common/snowflake/snowflake.go` | `common/snowflake/Snowflake.java` | **REUSE** | epoch/位段是业务契约 |
| `common/observability/*.go` | Micrometer Observation + OTel | **ABSORB** | ⚠️ L3 部分降级（span 属性需对拍） |
| go-zero `rest` 熔断 | resilience4j | **ABSORB** | ⚠️ L3 降级 |
| go-zero `rest` CPU 降载 | `common/protection/CpuThresholdHealthIndicator.java` | **NEW** | 生态缺口；17 类 → 1 类 |
| go-zero `rest` `MaxBytes` | `common/protection/MaxBytesFilter.java` + Tomcat `max-connections` | **NEW + ABSORB** | `MaxConns` 是 Tomcat 原生项 |
| `common/communityfact/*.go` | `common/communityfact/*` | **KEEP** | 业务特有，无生态等价物 |
| ——（Go 无对应） | `common/autoconfigure/*` + `AutoConfiguration.imports` | **NEW** | **D3 的载体** |

### 6.1 全仓规模（实测,非估算）

> **2026-10-25 已在本仓库落地并实测** —— 下表右列是 `RAG-V1` 树的**真实文件数**（`src/main/java` 下的 Java 文件数，含各域 `contract/` 包的 `ErrCodes` / `ErrMsg`），不再是估算。

| 域 | Go | RAG 现状 | **RAG-V1 实测** | 主要缩减来源 |
|---|---|---|---|---|
| article | 43 | 81 | **47** | 合并省一套 app 样板；用例内聚 |
| comment | 56 | 79 | **64** | 合并 + 用例内聚（但 rpc 协作者多，抵消大半） |
| favorite | 48 | 70 | **55** | 同上 |
| follow | 44 | 48 | **34** | 用例内聚 + 合并 |
| hot | 24 | 34 | **23** | 合并 + 用例内聚 |
| like | 49 | 81 | **59** | 合并（但 like 的 rpc 协作者 13 个，抵消大半） |
| message | 53 | 70 | **47** | 合并 + 用例内聚 |
| task | 24 | 40 | **30** | 合并 + 用例内聚 |
| user | — | — | **44** | — |
| admin | — | — | **57** | — |
| **users 小计** | 109 | 141 | **101** | — |
| **common** | **11** | **51** | **47** | 见 §5.5 修正说明（实际只 −8%，不是 −57%） |
| **主代码合计** | **461** | **695** | **507** | **−27%** |
| 测试骨架 | — | — | +10 | 每域 1 个 `*ServiceApplicationTests` |
| **Java 文件总计** | — | — | **517**（**推导值**） | 另有 22 个 pom / 10 个 proto / 10 个 yml |
| ⚠️ **D7 落地后的实测** | — | — | **559**（549 主 + 10 测试） | **16 个 pom** / 10 个 proto 集中在根级 `proto/` 模块 / 10 个 yml —— 与上表推导值的差异来自 D7 把 10 个 `*-contract` 模块的类并进域模块 |

> ⚠️ **规模缩减的来源分布与直觉相反**：
> - **降幅最大的是"样板"** —— 每域一套 `Application` + `Properties` + `advice` ×2 + `metrics` ×2 + `bootstrap` 样板，合并后减半。
> - **降幅最小的是"业务"** —— rpc 层的**协作者类**（`LikeRedisKeys` / `TaskDefs` / `CommentSensitiveFilter` / `FavoriteFactEnvelopeFactory` 等）**不是用例，不能合并**，它们有独立的变化原因。
> - **`common` 几乎没降** —— 因为可被生态吸收的只有 `protection`（17 → 2），而 `web/` 层的 Go 语义等价物**必须手写**（"何时读 body"与"错误文案形状"都不是框架能力）。
>
> ⇒ **推论**：完全 Spring 化的收益**不在文件数**，而在 ①消除 `@Import` 清单 ②拿到 resilience4j / cloud-stream / Observation 的生态红利 ③消灭"复制模块模板 ⇒ 抄走错误接线"这类沉默失效。**用文件数衡量这件事会得出错误结论。**

---

## 七、关键 ABSORB / SPLIT 决策的五问自检

> 对每个"未生成独立 Java 文件 / 拆出独立 Java 文件"的决策，回答五问。

### 1. `servicecontext.go` → ABSORB（无独立 Java 文件）

1. **Go 文件原本负责什么？** 装配：`Config` / Model / Redis / Rpc client / Middleware 实例。
2. **哪些是行为语义？** 无业务方法 ⇒ 无 L2。
3. **哪些只是 Go 的实现机制？** 全部：`redis.MustNewRedis`、`zrpc.MustNewClient`、构造器把依赖塞给下游。
4. **Java 中哪个已有机制直接承担？** Spring DI 容器 + `@ConfigurationProperties` + `@Bean`。
5. **依据？** 现有 RAG 的 rpc 模块已经证明：10 个模块都没有 `ServiceContext.java`，全部靠 `@Import` + DI，行为等价。

> ⚠️ **例外**：若某域的 ServiceContext 含业务方法（如 `ensureDefaultAdmin`），则该方法落到 `rpc/bootstrap/*Seeder.java`，**不是**回退到造一个 ServiceContext。

### 2. `handler/routes.go` → ABSORB + SPLIT（路由部分吸收，prefix 与 middleware 保留）

1. **原本负责什么？** 挂载 N 个 endpoint 到 `/xxx/v1`；绑定 JWT 与 blacklist middleware；全部 POST。
2. **行为语义？** **前缀 `/xxx/v1` 与 HTTP method 是 L1**（客户端按它寻址）⇒ **必须逐字保留**。
3. **实现机制？** `rest.WithPrefix` / `rest.WithMiddlewares` 的注册顺序。
4. **Java 机制？** `@RequestMapping` / `@PostMapping` 承担路由；`WebMvcConfig#addInterceptors` 承担 middleware 注册；**注册顺序（interceptor 先于业务）必须保留并有断言**（现有 `check-grpc-interceptor-wiring.py` 是同类先例）。
5. **依据？** 现有 10 个 api 模块已按此形态落地并通过双侧对拍。

### 3. `internal/logic/*/*.go`（13 个）→ MERGE（1 个 `*ApiService`）

1. **原本负责什么？** 每个 logic 一个用例：取身份 → 调下游 → 错误码转换 → 指标 → 业务日志。
2. **行为语义？** **全部保留**（业务规则、错误码语义、reject 上报条件）。
3. **实现机制？** goctl 的一 RPC 一 logic 生成模板 + `new XxxLogic(ctx, svcCtx)` 的构造器。
4. **Java 机制？** 无需框架吸收 —— 是**职责内聚**问题，不是框架能力问题。
5. **判据（五问之外的关键一条）**？**§5.4 的五条全部成立** ⇒ 合并。⚠️ **但业务规则密集的域不合并**（`follow` 域 8 个用例中 2 个带专属规则 ⇒ 拆 2 个 Service）。

> ⚠️ **这一条的合并理由必须逐域复核**，不能一次性套用。合并后**必须**有回归测试守住每个用例的行为（现有 `*SemanticDriftApiRegressionTest` 是先例）。

### 4. `internal/middleware/checkblacklistmiddleware.go` → SPLIT（只生成 `JwtAuthInterceptor.java`）

1. **原本负责什么？** 解析 `Authorization: Bearer xxx` → 查 Redis key `user:jwt_blacklist:{token}` → 命中返回 401。
2. **行为语义？** **Redis 查询 + 无 token 直接放行的策略是业务语义，必须保留**；中间件函数签名不是。
3. **实现机制？** go-zero 的 `func(next http.HandlerFunc) http.HandlerFunc`。
4. **Java 机制？** `HandlerInterceptor#preHandle`（返回 false 即拒绝）+ `WebMvcConfig` 注册。
5. **依据？** 现有 RAG 的 `AdminJwtAuthInterceptor` 已按此形态实现并通过对拍。

### 5. `service/common` → 升级为 starter（D3）

1. **原本负责什么？** Go 侧 11 个文件：logger / response / snowflake / observability / communityfact。
2. **行为语义？** **日志格式、信封形状、雪花 ID 位段**是业务契约，必须保留；**熔断/降载/限流的内部策略**是 L3，可降级。
3. **实现机制？** go-zero 的内置运行时（熔断、降载、MaxConns、MaxBytes、logx、httpx）。
4. **Java 机制？** resilience4j / Tomcat 配置 / SLF4J+MDC / Jackson `CoercionConfig` / 自定义 `HttpMessageConverter`。
5. **依据？** **RAG 现状是 51 个主代码类 vs Go 的 11 个 —— 这 4.64× 就是"框架差距补齐成本"**；生态吸收后目标 22 类。

### 6. `internal/metrics/metrics.go` → REUSE（`Micrometer` 承担注册，helper 保留）

1. **原本负责什么？** 定义指标名与标签 + `ObserveRequest` / `ObserveReject` helper。
2. **行为语义？** **指标名与标签是业务定义**（`message_api_request_total{route,result}`）⇒ 必须逐字保留。
3. **实现机制？** Prometheus client_golang + `sync.Once` 注册。
4. **Java 机制？** Micrometer `MeterRegistry` 承担注册与暴露；**helper 的调用点与触发条件保留为业务代码**。
5. **依据？** 现有 RAG 的 `*Metrics` 类已按此形态落地。

---

## 八、职责覆盖与组织合理性检查（spec 12.7）

| # | 检查项 | 结果 | 说明 |
|---|---|---|---|
| 1 | L1 外部契约均已识别 | ✅ | HTTP 前缀/方法/出入参、proto 方法与字段号、错误码取值、状态码 —— 逐条列入 §4 |
| 2 | L2 业务语义均有明确承载位置 | ✅ | 业务规则、状态转换、鉴权结果、读写含义、副作用触发条件 —— 见 §3 职责矩阵 + §4 映射 |
| 3 | L3 技术语义均有承载位置或方案 | 🟡 **部分降级** | 降级 4 项已在 §0.4 逐条列出并标注原因与代价 |
| 4 | 实际业务分支与重要异常路径已纳入 | ✅ | §3 逐文件职责分析覆盖 handler / logic / helper / middleware / model |
| 5 | 被框架吸收的职责都有真实机制依据 | ✅ | 每条 ABSORB 均在 §7 五问中给出机制名 + 现有 RAG 代码佐证 |
| 6 | 复用的 Java 组件都已明确指出 | ✅ | §1「已有组件」表逐项列出 |
| 7 | 新增 Java 文件都有明确职责依据 | ✅ | 新增项仅 4 处：`common/autoconfigure/*`、`common/protection/CpuThresholdHealthIndicator`、`common/protection/MaxBytesFilter`、各域 `client/` —— 均有 §4 / §5.2 的依据说明 |
| 8 | 合并的文件都有组织依据 | ✅ | 合并项集中在三类：api+rpc 入口（D1）、用例内聚（§5.4 五判据）、common 生态吸收（D4） |
| 9 | 拆分的文件都能解释职责边界 | ✅ | 拆分项：`config` 分 api/rpc 两份、`domain/exception/mapper` 与 `api` 分立、跨域 client 独立 |
| 10 | 没有仅为对应 Go 文件而创建的 Java 文件 | ✅ | 无 `Handler.java` / `Logic.java` / `Repository.java` / `Middleware.java` / `ServiceContext.java` / `Types.java` |
| 11 | 没有为减少文件数而不合理合并 | 🟡 **需逐域复核** | §5.4 判据 ⑤ 是闸门；`follow` 域已识别为不合并的例外 |
| 12 | 没有把未实现的业务职责误判为框架吸收 | ✅ | L3 降级项**全部**如实标注，未计入 ABSORB |
| 13 | 没有把尚未实现的能力描述成现有机制 | ✅ | §1 分「已确认的框架机制」与「完全没用的生态能力」两栏，后者标为增量空间 |
| 14 | 无法确认的组织决策已标记 UNKNOWN | ✅ | `rpc.*` 改名、`api/client` 空包处置已标 UNKNOWN / 待定 |

> **检查未全过的两项（#3、#11）已在上表说明原因，且都给出了后续动作：#3 对应 §9 的降级拍板清单；#11 对应 §9 的逐域复核清单。**

---

## 九、可执行符合性检查（把"完全 Spring 化"变成红灯）

> 这一节回应一个前置问题：**"文件树像不像 Spring"不是差分问题，是符合性问题**——它没有 Go 那样的冻结真源可比，但**可以写成断言**。

### 9.1 新增断言（`tools/difftest/check-arch-invariants.py`）

| # | 断言 | 判定方式 | 现状 |
|---|---|---|---|
| A1 | `*-rpc` 的 classpath 不得有 `spring-boot-starter-web` | `mvn dependency:tree` | 🟢 10/10 通过 |
| A2 | `*-api` 必须有 `spring-boot-starter-web` | 同上 | 🟢 10/10 通过 |
| A3 | 契约 A：每 `*-rpc` 有 `GrpcServerConfigurer` 且挂 `server.intercept`<br>**契约 B**：`common/` 声明 `GrpcServerConfigurer` 且真调 `server.intercept` ＋ **域内 0 重复** | 解析源码 | 🟡 RAG **10 个 rpc 只有 6 个真挂**（message / user / admin 静默失效）<br>🟢 RAG-V1 已改写为契约 B 版（挂载点 = 共享层 1 份）|
| A4 | 每个 `@GrpcClient("X")` 必须在同模块 yml 有 `negotiation-type: plaintext` | 交叉引用 | 🟡 **2 处哑弹**（`article-rpc` 的 `security-rpc` / `message-rpc` 配了但无 bean，且缺 plaintext） |
| A5 | 每个 rpc 模块的 `@Import` 清单为空（starter 化后） | 解析 Application 类 | 🔴 10/10 不通过（当前 4~10 个 Bean 不等） |
| A6 | `MybatisPlusOrderingBridgeAutoConfiguration` 全仓只有一份 | 文件名扫描 | 🔴 9 份重复 |
| A7 | classpath 上有 resilience4j ⇒ 不存在手写熔断器 | classpath + 类名扫描 | 🔴 不通过（`GoStyleBreaker` 等 17 类） |
| A8 | 手写 `Logger` 必须实现 `org.slf4j.Logger` | 类型检查 | 🔴 不通过 |
| A9 | DTO 必须是 immutable（无 setter） | 反射/静态检查 | 🔴 不通过（现为可变 POJO） |
| A10 | 不存在 Bean Validation 缺席但有手写校验器的域 | 静态检查 | 🔴 不通过（`GoStyleRequired*` 手写） |

> **A4 的哑弹是本文件树推导过程中实测到的现存问题**：配置齐了但缺 `negotiation-type`，且没有 `@GrpcClient` bean ⇒ 现在无害，**一旦有人补上那行 `@GrpcClient` 就会静默变 TLS**，而所有测试都是绿的（测试用 `@SpringBootTest(properties=...)` 在测试里注入了该值）。

> **RAG-V1 的达标状态（2026-10-25 实测）**：空文件树已满足 **A5 / A6 / A7 / A9 / A10** 的结构前提 —— `api/client` 目录 0 个、`@Import` 清单 0 个、MP bridge 副本 0 个、DTO 全为 `record`、`protection` 只剩 2 类。
> ⚠️ **A1 / A2 在 RAG-V1 不适用**（api/rpc 已合并，见 §9.2）—— 它们是「沉默不变量」：RAG 形态下 10/10 通过，一旦合并就自动消失。
> ⚠️ **但 A3 不是** —— 「拦截链有没有真挂到 server 上」**与进程形态无关**：Go 侧范围内 10 个服务逐条显式 `AddUnaryInterceptors(...)` ⇒ 合并后仍要挂，只是**挂载点从「每 rpc 模块」变成「共享层 1 份」**。⇒ **A3 已改写为契约 B 版**（2026-10-26）：正向「`common/` 有挂载点」＋ 反向「域内 0 重复」⇒ 详见 §5.5.2 与 [`架构符合性断言.md`](架构符合性断言.md) A3。
> ⇒ 断言脚本落点：`tools/difftest/check-arch-invariants.py`（**待实现**，清单见 [`架构符合性断言.md`](架构符合性断言.md)）。

### 9.2 分化提醒：断言绑定契约，不可跨契约复用

| 契约 | A1 | A2 | A3 | A5 | A7 | A9 |
|---|---|---|---|---|---|---|
| **A（Go 部署等价，RAG 现状）** | ✅ 适用 | ✅ 适用 | ✅ 适用 | ✅ 适用 | 🔴 待修 | 🔴 待修 |
| **B（Spring 惯用，本文目标）** | ❌ **不适用** | ❌ **不适用** | ✅ **适用（已改写）** —— 挂载点 = `common/` 1 份 ＋ 域内 0 重复 | ✅ 适用 | ✅ 适用 | ✅ 适用 |

⇒ **A1/A2 是 api/rpc 分离的派生物，合并后自动消失**（§0.4 之外新增的两条）；**A3 例外** —— 它管的是「拦截链有没有真挂上」，与进程形态无关 ⇒ 合并形态下**改写而非消失**（§5.5.2）。
⇒ **新项目若采用契约 B，只能继承 A4/A7/A8/A9/A10，不能继承 A1/A2/A3/A5。**

### 9.3 需要拍板的取舍（否则 §5 落不了地）

| # | 取舍 | 选项 | 建议 |
|---|---|---|---|
| P1 | 错误文案保真度 | ① 接受 Bean Validation 文案（L3 降级）② 自定义 validator 逐字复刻 go-zero | **①**。② 的成本高于收益，但需在 §0.4 明确记录为已知降级 |
| P2 | 熔断语义 | ① 迁 resilience4j（语义降级）② 保留自研（17 类）③ 迁 resilience4j + 逐条对拍关键参数 | **③**，但**必须排在 `global` 收口域销账之后** |
| P3 | Kafka 接入方式 | ① 保留裸 `spring-kafka` ② 迁 Spring Cloud Stream binder | **①**。Stream 的 binding 抽象对本项目（单消费者、无 broker 切换需求）没有收益 |
| P4 | `rpc.*` 是否改名 | ① 不改（记技术债）② 改 `domain.*` | **①**，改名成本与收益不成比例 |
| P5 | api/rpc 合并是否进 RAG 本体 | ① RAG 不动，新项目用合并形态 ② RAG 也合并 | **①**。RAG 的职责是「Go 字节级真源」，部署拓扑属 Go 事实，改了就不是迁移了 |

> **P5 是总闸门**：若 RAG 保持契约 A，则 §5 的树是**新项目**的目标形态，RAG 侧只需做 **D3（starter 化）+ D4 的低风险子集（SLF4J 适配器）** 这两项**契约无关**的改进。

---

## 十、最终判定

| 判定项（§15） | 结论 |
|---|---|
| **业务职责完整**（§15.1） | ✅ L1 全保、L2 全保、L3 保 6 降 4，降级项逐条列明且不落在业务语义上 |
| **符合目标项目的组织方式**（§15.2） | ✅ **D7 后**：域内不再有 `api.*` / `rpc.*` 协议层，改为单层技术角色包树（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / `grpc` / `domain` / `messaging` / `mapper` / `client` / `bootstrap` / `exception` / `contract`）；跨域 client 落**单层 `client/`**（依据见 §5.2）；未新增顶层模块 |
| **框架吸收有真实依据**（§15.3） | ✅ 每条 ABSORB 均给出机制名 + 现有 RAG 代码佐证；现有组件复用标 `REUSE` 而非 `ABSORB` |
| **合并与拆分有明确理由**（§15.4） | ✅ 三类合并（入口合并 D1、用例内聚 §5.4 五判据、生态吸收 D4）各有独立判据；拆分项逐条说明边界 |
| **不把推导伪装成事实**（§15.5） | ✅ `rpc.*` 改名（**已由 D7 结清**）、`api/client` 空包处置标 UNKNOWN；规模数字明确标为估算并给出不确定性来源 |
| **最终交付物是理论文件树**（§15.6） | ✅ §5 给出仓库级 + 域内级 + 共享层三棵树，§6 给出变化表，§9 给出可执行验收 |

### 一句话结论

> **完全 Spring 化 = api/rpc 合并成 10 个 app + `service/common` 升级为 starter（51 → 22 类）+ 生态吸收（protection 17 → 2 类）+ DTO immutable + 统一错误模型。**
>
> **代价是 4 项 L3 降级（错误文案 / 熔断策略 / span 属性 / 降载语义），全部落在框架内部行为上，没有一项落在业务语义上。**
>
> **而这棵树是否可信，不取决于它像不像 Spring，而取决于 §9 的 10 条断言能否变绿。**

---

## 附：本文档作为模板的使用方式

1. **投喂给 AI 做单 Component 推导**：把 §0（契约分层与决策）作为前置约束，把 §1（目标组织依据）替换为"当前 Component 相关的实际检查结果"，其余章节结构（§2 Go 树 → §3 职责矩阵 → §4 映射表 → §5 Java 树 → §6 变化表 → §7 五问 → §8 覆盖检查）保持不变。
2. **作为评审清单**：§9.1 的 10 条断言是"完全 Spring 化"的客观判据，可直接接入 CI —— 落点 `tools/difftest/check-arch-invariants.py`。
3. **与台账的关系**：§0.4 的降级表已独立成 [`L3-降级清单.md`](L3-降级清单.md)；§9.3 的取舍清单已独立成 [`决策记录.md`](决策记录.md) 的 P1–P5；逐条漂移写 `docs/DRIFT/<域>/`，签字偏离写 `docs/Intentional_Deviations/<域>/`。**不要只留在本文档里。**
4. **本文档的修订**：落地实测后已修正三处偏差并标注「2026-10-25 修正」——
   ① §5.5 `common` 实际 47 类（原估 22）
   ② §5.4「用例内聚」仅适用于 api 层，rpc 层协作者类保留独立
   ③ §6.1 规模数字改为实测（主代码 507，不是估算 450）

> ⚠️ **2026-10-25 第二次修正（重要）**：本文档 §2–§4 的 Go 侧职责盘点**是从 RAG 的 Java 侧反推的**，而非 Go 正推。
> **Go 正推后发现 8 处偏差**（HTTP method 分布、路由前缀、错误契约形态、错误码值/文案/语言、task 域无错误码空间、DTO 清单、users 域契约归属、MQ 消息契约），
> 其中 **6 处属 L1 风险**。⇒ 完整清单与 5 条方法论纪律 ⇒ **[`深挖修正记录.md`](深挖修正记录.md)**。
> ⇒ **本仓库的树已按 Go 正推结果修正**；本文档的 §2–§4 段落请以深挖修正记录为准。
