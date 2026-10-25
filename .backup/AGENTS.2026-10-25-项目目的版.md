# AGENTS.md: RAG-V1 项目政策

> **本文件是本仓库唯一由每个 agent 自动加载的根政策文件,只放政策与索引。**
> 逐条推导 → [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md)
> 决策台账 → [`docs/决策记录.md`](docs/决策记录.md) · [`docs/L3-降级清单.md`](docs/L3-降级清单.md) · [`docs/架构符合性断言.md`](docs/架构符合性断言.md)
>
> **真源 = Go**（`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang`，已冻结）。
> **RAG-V1 = Spring 惯用形态的迁移产物**，不是 Go 文件树的 Java 翻译。
>
> 👉 **先读下一节「项目目的」** —— 下面每一条政策都是在为它服务；不知道目的就改不动。

---

## 项目目的

**一句话**：把冻结的 Go/go-zero 微服务 **Sea-RideTheWind**（识海社区后端），用 **Spring Boot 惯用形态**重新实现一遍 —— **行为对拍等价**，代码按 Java 生态重新组织。

### 这个系统是什么

| 项 | 内容 |
|---|---|
| 产品 | **识海**社区后端（Go 侧原名 `Sea-RideTheWind`）—— **内容 + 互动 + 任务 + 用户体系**的一体化服务端 |
| 配套系统 | 推荐系统 `BreakTheWaves`（"破浪"）与前端仓 —— **不在迁移范围** |
| Go 真源 | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang`（**已冻结 · 只读**）—— 13 个 `service/` 目录 |
| 迁移范围 | **10 个业务域**：article · comment · favorite · follow · hot · like · message · task · users（拆 `user` + `admin` 两子域） |
| 范围外 | `knowledge` / `points` / `security` 三域（Go 侧存在但**不迁**，依据见 [`docs/决策记录.md`](docs/决策记录.md)） |
| Go 侧编排 | PostgreSQL · Redis · Kafka · MinIO · Milvus · Neo4j · etcd + 观测 Prometheus · Grafana · Jaeger |
| Java 侧已接 | PostgreSQL + Redis + Nacos 注册发现（各域 `application.yml` 实测；Kafka 依赖已就位，配置走 Nacos） |

### 为什么要做（两层，互不替代）

1. **行为层 —— 严格复刻。** Go 是唯一 oracle。RAG-V1 必须在 **L1 契约 + L2 业务语义**上与 Go 等价；L3 可按已登记降级项降级，L4 可重选；**L1/L2 不可省、不可"顺手优化"**。
2. **认知层 —— 借复刻吃透 Java 生态。** Go 侧 README 自己写的：*"通过亲自复刻、深入实现和持续打磨，真正理解系统设计背后的取舍逻辑与工程方法。"* Java 侧同理：**目的不是把 Go 翻译成 Java，而是借同一套业务逼出每个 Spring 组件的职责边界** —— 谁负责什么、换成生态等价物后语义变了什么、哪些语义生态根本给不了（G-1~G-4）。

> ⚠️ **两层的调和规则**：认知层只允许体现在**注释与文档**（写清"对应 Go 的什么 + 这个框架负责什么"）；**行为层不允许被认知层改写**。看到"更地道的 Java 写法"想动 L1/L2 ⇒ 停，走决策流程。

### 三个仓库的关系（别搞混）

| 仓库 | 是什么 | 在本项目里的角色 |
|---|---|---|
| **Go 真源** `Sea-RideTheWind_golang` | go-zero 原始实现，**已冻结** | **唯一 oracle** —— 行为判断只认它 |
| **RAG** `/Users/pr/Documents/java&golang/RAG`（分支 `do-my-own-gh`） | Go 形态的**第一版 Java 重构产物** | ⚠️ **不是基准** —— users/like/message/common/article 五域已判定 `NOT EQUIVALENT`；只作**反例与踩坑来源**（§1.4 R1–R6） |
| **RAG-V1**（本仓） | Spring Boot 惯用形态的**重做** | 交付物 |

### 什么算完成

- L1 契约（proto / 路由 / method / 错误码值 / 错误文案 / MQ 消息字段）与 Go 逐字节或逐值对齐；
- L2 业务规则全部有承载位置，无静默省略；
- 每域可独立启动；每个新机制配一条**真触发**的测试（§1.3 —— 纯 mock 单测不算）；
- 对拍脚本绿 + A1–A12 断言绿；
- 三档台账写回（§五）。

### 不在范围内

- 推荐系统 `BreakTheWaves`、前端仓、`knowledge`·`points`·`security` 三域；
- Go 侧缺陷的"顺手修正"（登记不改，见 §1.1b）；
- 新功能、性能优化、架构改良 —— 除已签字的 D1–D6 与 [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)。

---

## 〇、当前状态（2026-10-25）

**形态：完整空文件树已落地，实现待填。**

> **核对日期 2026-10-25**（`find` 实测，非估算）

| 项 | 数 |
|---|---|
| Java 文件 | **558**（548 主代码类 + 10 个测试骨架） |
| ├ 域内主代码 | **492** |
| └ `common` 主代码 | **56** |
| Maven 模块 | **23**（22 子模块 + 1 父 pom） |
| proto | **10**（与 Go 侧逐字节一致，仅 `java_package` 属表达层） |
| `application.yml` | **10**（每域一份） |
| `AutoConfiguration.imports` | **1**（`common/`，注册 7 个 auto-config） |
| `docs/File_Tree/` | **23 份**（22 模块 + 1 索引，按域分目录） |

⚠️ **契约模块只有 proto、没有 Java 类是设计如此** ——
`admin-contract` / `task-contract` 各只有 `pom.xml` + `*.proto`；
`task` 域更特殊：**Go 侧 task 没有错误码空间**（无 common 包）⇒ 该域 `contract/` 的错误契约目录**故意为空**。

**已完成**：树结构、pom、proto、application.yml、断言清单。
**未完成**：全部实现内容（**每个 Java 文件都只有 package + 空类型 + TODO javadoc**，558/558 全覆盖）。
**✅ 2026-10-25**：全仓 `mvn -o -T1C compile` **22/22 BUILD SUCCESS**（protoc 离线可跑）；
已修 Boot 4 的 Kafka 拆模块缺口（`common` + 7 个域统一 `spring-boot-kafka`）→ [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) §四。
**✅ 2026-10-25（据 RAG 校准）**：**D1 的例外判定已撤销**（Go 的 `cmd/fact-*` 独立进程零对外服务 ⇒ 可并入域内组件），
三域补齐 6 个 `fact-*` 装配类；消 like 域重复类；新增 **A11 / A12** 两条断言。

---

## 一、真源与对照纪律

### 1.1 Go 决定迁移什么

Go 仓库 `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` 已冻结，**不接受反向同步**。

任何行为判断必须**逐字对照 Go 源码**。

> ⚠️ **正推，不反推**（2026-10-25 深挖修正，见 [`docs/深挖修正记录.md`](docs/深挖修正记录.md)）
>
> 第一轮推导曾**从 RAG 的 Java 侧反推 Go 的职责** —— 这是方法缺陷：**RAG 本身在 users/like/message/common/article 五域已被判定 `NOT EQUIVALENT`**，拿已知漂移的实现当 Go 的替身，会把漂移固化进新树。
> ⇒ **禁止**：把任何已有实现（哪怕同项目的前一版）当 Go 的替身。
> ⇒ **要求**：先读 Go 源码得出职责，再映射到 Java。

> ⚠️ **禁止的三种对照基准**：
> ① 现有实现（会「对齐到错」）② 代码注释（写的是 Java 视角，Go 视角可能不同）③ 读两遍 Go 取并集（两遍都错的机会不等于零）。

> ⚠️ **按「导出符号 / 函数面」归类，不按文件名 / 目录名**（2026-10-25）
> - `errmsg.go` 里可能有 **6 种职责**（8 个域实测出 **4 种不同形态**）
> - `types.go` 里可能有 28 个 DTO
> - 同一个文件名在不同域内容可以完全不同 ⇒ **必须逐域读**

### 1.1b L1 契约是**逐域隔离**的（2026-10-25）

> **同类项跨域逐一比对** —— 「各域都有的东西」恰恰是**最可能取值不同**的地方。

实测证据（Go 侧）：

| 隔离维度 | 证据 |
|---|---|
| **错误码值** | `ErrorDbUpdate`：favorite=5008 / comment=5002 |
| **错误文案** | `CodeServerBusy`：article·comment·favorite·like·user=`"服务繁忙"`（中文）/ follow·hot·message=`"service busy"`（英文） |
| **错误码段** | 「用户不存在」段：favorite·follow·message=5101+ / comment·like·user=1001+ |
| **HTTP method** | admin=Get3·Post9·Delete1 / article=Get3·Post2·Put1·Delete1 / hot=Get1（**只有 users 域被签字改成全 POST**） |
| **路由前缀** | article=`/v1`（唯一不带域名前缀）· task=`/taskcenter/v1` · message 有第二组 `/message/v1/admin` |
| **JWT secret** | message 域两组路由用 **不同** 的 secret（UserAuth / AdminAuth） |
| **MQ 消息字段** | `comment/rpc/common/types/kqueue.go` 的 `CommentKafkaMsg` 是跨服务契约 |
| **事件信封 / 回执** | `common/communityfact/wire.go` 的 `Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` —— **comment·favorite·like 三域共享的跨服务事件协议**（含 `SchemaVersion` / `AggregateVersion` / `OperationID` ⇒ 事件溯源 + 幂等） |
| **指标完整名** | `namespace_subsystem_name`。article/rpc **无命名空间**（裸名）；`like-rpc` **用连字符**（Prometheus 非法）；`admin_rpc_high_risk_action_logal` **拼写错误** |
| **鉴权边界** | hot / task **全部端点免鉴权**（无 `WithJwt` 组）；message **两组 JWT**（UserAuth / AdminAuth） |

⇒ **禁止「统一化」**。把各域的码值/文案/语言/method/前缀/指标名提取成共享基座 = **改掉 8 个域的可见行为**。
⇒ 共享的只有**真正共用**的：`user/common/jwt`（article/rpc + knowledge/api 也用）⇒ 放 `common/security/` ✅。

> ⚠️ **已知缺陷必须照抄，不得"顺手修正"**（2026-10-25 第二轮）：
> ① `like-rpc_action_total` 用连字符 ⇒ Prometheus 拒绝 ⇒ 该指标不会出现在 `/metrics`
> ② `admin_rpc_high_risk_action_logal` 拼写错误 ⇒ 监控看板依赖它
> ⇒ **改了就是引入 Go 没有的可观察差异。** 登记为已知缺陷，不改。

### 1.1c 「生态无等价物」清单（必须单独立项 + 逐字对拍）

> 完整清单 → [`docs/第二轮深挖-契约盘点.md`](docs/第二轮深挖-契约盘点.md) §七

| # | 能力 | 生态现状 | 处置 |
|---|---|---|---|
| G-1 | CPU 降载（`CpuThreshold` 900‰） | resilience4j 无等价 | 自研 1 类 |
| **G-2** | **canonical JSON 哈希（RFC 8785 JCS）** | **Jackson 不等价**（`SORT_PROPERTIES_ALPHABETICALLY` ≠ JCS） | **需引 JCS 库**；指纹不一致 ⇒ 幂等失效 |
| G-3 | go-zero `rest.Middleware` 链顺序 | 语义不同 | 显式链式配置 |
| G-4 | `logx` 的 `call_chain` 字段 | SLF4J 无等价 | 自研 `Logger implements slf4j.Logger` |

### 1.2 L1–L4 分层（每次改动都要先归层）

| 层 | 内容 | 约束 |
|---|---|---|
| **L1** 外部契约 | HTTP 路由/方法/出入参、gRPC 方法与 proto、错误码取值、状态码 | **不可改**。proto 字段号/message 名一个字节都不动 |
| **L2** 业务语义 | 业务规则、状态转换、鉴权结果、读写含义、副作用触发条件 | **不可省**。"Java 用了不同的类/注解"不等于可以省略 |
| **L3** 技术语义 | 事务边界、错误传播、超时重试降级、幂等、并发控制、序列化、资源生命周期 | **保 6 降 4**，降级项已登记，不得反向修回 |
| **L4** 实现机制 | 依赖注入、配置绑定、拦截器、注册与生命周期、文件划分 | **可重选**，但不得借此改变 L1–L3 |

### 1.3 "注解存在"≠"机制生效"

凡引入新的框架机制，必须配一条**能真正触发它**的测试。

**纯 Mockito mock 数据层的单测不算** —— 它不执行 SQL、不装配 Spring 上下文，天然测不到这一层。

**规则**：
1. 判断"一个注入/开关有没有真生效"**不能看启动日志** —— 注入点的 echo 照打、jar 照起、日志零告警，效果却是零。唯一可靠判据是**运行时证据**。
2. **"Bean 存在"≠"机制生效"**（H2 假绿）。
3. **"代码里写了"与"客户端收到"之间隔着一次 commit**。
4. **绿测试可能是"错误答案的守卫"**，比没有测试更危险。
5. **测试注入会掩盖生产配置的缺失**（实测：`grpc.client.*.negotiation-type` 由测试注入 ⇒ 生产配置可一直是错的 ⇒ 没人发现）。
6. **Boot 4 把自动配置拆成了独立模块** —— 「`pom` 里有依赖」不等于「自动配置在 classpath 上」。
   **每次引入 `spring-*-starter` 能力前，先确认它在 Boot 4 下仍在 `spring-boot-autoconfigure` 里；已被拆走的必须显式补对应模块。**
   ⚠️ **已实测坐实的样板**：`spring-boot-kafka` —— 只有 `spring-kafka`（底层库）时，
   `KafkaAutoConfiguration` 类**根本不存在** ⇒ `@Import(KafkaAnnotationDrivenConfiguration)` 不发生
   ⇒ `@KafkaListener` **永不扫描（静默不消费，零报错）**；而任何 `KafkaTemplate` 注入点 ⇒ `NoSuchBeanDefinitionException`（硬启动失败）。
   **同一缺陷两种症状**，取决于有没有注入点 ⇒ **不能凭形态推定，先实测启动**。详见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) §四 K-1。

### 1.4 RAG 反哺的硬规则（2026-10-25 · 全部来自 RAG 实测）

> 完整依据 → [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)

| # | 规则 | 为什么 |
|---|---|---|
| **R1** | **`common/` 的准入与归属**：只放**跨域共用且无域归属**的能力；每个子包标归属域；改 `common/` 必须**单写**并登记影响面 | RAG 的 `service/common` 被 16 组件依赖 ⇒ 凡修法落在这里的条目**自动从并发降级为串行** ⇒ **这是 10 域并发吞吐被卡死的那一层**（不是"漂移条目多"） |
| **R2** | **守门脚本 4 硬规则**：① 计数只取回归日志模块级汇总（**不取** `surefire-reports/*.xml` 求和）② 报「0 failures」须同时断言 `skipped=0` ③ **禁止 `@EnabledIfDockerAvailable`**（它把"拉不到镜像"压成"Docker 不可用"）④ 排除项用**前缀/类别匹配**，不逐个枚举 | RAG 实测：2109 条全绿里 **15 条真 PG 用例静默全跳**；XML 求和比真实执行**多 4 条**（陈旧孤儿报告） |
| **R3** | **GORM → MP 语义对照表是强制参考** → [`docs/GORM-MP-语义对照.md`](docs/GORM-MP-语义对照.md) | `strictUpdateFill` 在读-改-写路径**永不生效**；`update(null, wrapper)` **不调** `MetaObjectHandler`；`NULL` 读出来是 `0` 不是 `null`；MP lambda 与手写 OGNL **两套解析规则** |
| **R4** | **事务三判据**：① `@Transactional` 必须在**独立 Bean** 上（self-invocation 静默失效）② 写路径的**实体形态**决定修法，不是"哪个更地道" ③ SQL 超时只能用 `setQueryTimeout`（`socketTimeout` 会**报废连接**），且 `SQLState=57014` 必须翻成 `DEADLINE_EXCEEDED` | RAG 实测：`catch (SQLException)` **编译通过但永远匹配不上**（MyBatis 抛的是 `InvocationTargetException`）⇒ 翻译器静默失效 |
| **R5** | **HTTP 形状断言必须真 Tomcat**（`webEnvironment = RANDOM_PORT`），**MockMvc 不算** | 响应已 committed 后 `setHeader` 被**静默忽略** ⇒ RAG **4 轮对拍全绿仍漏** `Content-Type` |
| **R6** | **DDL 三约束**：① `db/*.sql` **只能单条语句**（`ScriptUtils` 按 `;` 切分，**不支持 `$$`**）② `timestamptz` 转换用 `SET TIME ZONE 'UTC'; … RESET TIME ZONE;` 包裹（`USING` 会重新解释取值 ⇒ 幂等重放**永不收敛**）③ `DEFAULT CURRENT_TIMESTAMP` **保留并签字**（两侧 INSERT 都显式绑定 ⇒ 默认永不触发） | RAG 实测：裸 `ALTER` 的重放漂移（差 8 小时才拿到硬证据）；`DO $$` 块 ⇒ **34 个测试红在启动阶段** |

---

## 二、六项结构性决策（不可静默回退）

> 完整依据与代价 → [`docs/决策记录.md`](docs/决策记录.md)

### 2.0 命名约定（Spring 惯例）

| 层级 | 形态 | 例子 |
|---|---|---|
| **全局共享库** | 顶层 `common/`（starter） | `common/` —— 原 `service/common` |
| **域** | `service/<域>/` | `service/article/`、`service/users/` |
| **域内子域** | `service/<域>/<子域>/` | `service/users/user/`、`service/users/admin/` |
| **域内公共库** | `service/<域>/<域>-common/` | `service/users/users-common/` —— **名字体现「库」，不是「域」** |
| **契约** | `<域>-contract/` | `service/article/article-contract/`（proto + 域内错误契约） |
| **应用** | `<域>-service/` | `service/article/article-service/`（api + rpc 合并） |

**规则**：
1. **一个「域」一个目录**。`service/users/` 是**一个域**，下面两个子域 `user/` / `admin/` —— **不是三个域**。
2. **库不冒充域**。共享代码叫 `*-common` / `*-shared`，不叫 `<something>-domain`。
3. **域用单数业务名**（`article` / `comment` / `follow`），不用复数泛称。
4. **`common` 是 starter**（带 `AutoConfiguration.imports`），**不是**普通 jar。
5. **`<域>-contract` 只放契约**（proto + 该域错误契约），**不放实现**。

| # | 决策 | 状态 |
|---|---|---|
| **D1** | **api 与 rpc 合并为一个 app**（一域一进程） | 已定 |
| **D2** | **域内调用直连**，不自我 gRPC 往返；跨域仍走 gRPC | 已定 |
| **D3** | **共享层是 starter**（`AutoConfiguration.imports`），**不是**普通 jar | 已定 |
| **D4** | **生态优先**，手写只补生态缺口（熔断/日志/观测用生态） | 已定 |
| **D5** | **DTO 全 immutable record** + Bean Validation，**无手写校验器** | 已定 |
| **D6** | **跨域调用用 devh `@GrpcClient`**，不改传输 | 已定 |

**回退任一项都必须先改 `docs/决策记录.md` 并说明理由。** 尤其：

⚠️ **D1 的连带效应**：A1 / A2 / A3 三条架构断言在合并形态下**不适用** —— 它们是 api/rpc 分离的派生物。**不要在合并形态下把它们改成红灯。**

⚠️ **D3 的连带效应**：`@Import` 与 auto-config **不能并存** —— `@Import` 那份按 user config 处理（更早求值），会改变 `@ConditionalOnMissingBean` 语义。**要么全删 `@Import`，要么保持现状。**

---

## 三、L3 降级项（决策，不是缺陷）

> 逐条 → [`docs/L3-降级清单.md`](docs/L3-降级清单.md)

**D-01** 必填校验错误文案 · **D-02** 熔断内部策略 · **D-03** span 名称与属性 · **D-04** CPU 降载实现路径

**三条硬规则**：
1. **不得反向"修回"旧行为** —— 要恢复必须走 P1–P5 重新拍板。
2. **不得静默扩大** —— 新增降级必须写进清单，不能只写代码注释。
3. **对拍脚本必须显式排除降级项** —— 否则会因文案差异全红 ⇒ 红了被误判为回归 ⇒ 触发"修回" ⇒ **降级被静默撤销**。⚠️ 最容易出问题的一环。

---

## 四、架构符合性断言（A1–A12 必须绿）

> 逐条 → [`docs/架构符合性断言.md`](docs/架构符合性断言.md)
> 脚本落点：`tools/difftest/check-arch-invariants.py`（**待实现**）

| # | 断言 | RAG-V1 现状 |
|---|---|---|
| A1 | `*-rpc` classpath 不得有 `spring-boot-starter-web` | ❌ 不适用（已合并） |
| A2 | `*-api` 必须有 `spring-boot-starter-web` | ❌ 不适用（已合并） |
| A3 | `*-rpc` 必须 `GrpcServerConfigurer` + `server.intercept` | ❌ 不适用（已合并） |
| A4 | 每个 `@GrpcClient("X")` 必须在同模块 yml 有 `negotiation-type: plaintext` | ⬜ 待实现 |
| A5 | 每个模块的 `@Import` 清单为空 | ✅ 0 个 |
| A6 | `MybatisPlusOrderingBridgeAutoConfiguration` 全仓只有一份 | ✅ 0 份副本 |
| A7 | 有 resilience4j ⇒ 无手写熔断器 | ✅ `protection/` 只剩 2 类 |
| A8 | 手写 `Logger` 必须实现 `org.slf4j.Logger` | ✅ |
| A9 | DTO 必须 immutable | ✅ 全 `record` |
| A10 | 无手写校验器时必须引入 Bean Validation | ✅ |
| **A11** | **Go 侧 `cmd/fact-*` 独立进程 ⇒ 域内必须有对应装配落点**（三域各 2 个 + 3 个日志身份类） | ✅ 2026-10-25 补齐 |
| **A12** | **一个模块内不得有同名简单类** | ✅ 2026-10-25 消（`LikeMqMetrics` 归位） |

**例外必须写进断言文件并附理由** —— 口头豁免不算。

> ⚠️ **A11 的由来**：Go 侧 `comment`/`favorite`/`like` 三域各有 `cmd/fact-dispatch` + `cmd/fact-authority`
> **独立进程**。经 RAG 的组件级对照判定，它们**零对外服务** ⇒ **可并入域内组件，D1 不设例外**。
> 但「可合并」≠「已合并」—— 树里没有落点 ⇒ **实现阶段会整个漏掉，且没有任何编译错误或测试红灯**
> （与 RAG `article-rpc` 2 颗哑弹同款形态）。详见 [`docs/决策记录.md`](docs/决策记录.md) D1 的例外判定撤销。

---

## 五、台账三档制

> 产出**必写回**同名档；**开工前必读**。不写台账的改动视为未完成。

| 档 | 位置 | 记什么 |
|---|---|---|
| **漂移档** | `docs/DRIFT/<域>/<组件>.md` | "RAG-V1 与 Go 不一致"的逐条留档（含证据强度） |
| **有意偏离档** | `docs/Intentional_Deviations/<域>/` | "明知与 Go 不同、经签字批准"。**是决策不是缺陷：不重复质疑，也不得"顺手修正回旧行为"** |
| **踩坑档** | `docs/Pitfalls/_cross/` | "为什么会踩"：框架机制静默失效、绿测试锁死错误答案、基础设施误用 |

**三类不可混谈**：有意偏离 / 框架限制 / 表述差异。

---

## 六、新增组件检查清单

> 每次新增一个域、一个模块或一个共享能力，**逐条过**：

- [ ] **职责有据** —— 新文件的存在理由来自实际职责，不是"Go 里有这个文件"
- [ ] **Go 正推** —— 职责清单来自**读 Go 源码**，不是从任何已有实现反推（§1.1）
- [ ] **逐域读** —— 同名文件在不同域内容可能完全不同 ⇒ 不得用一个域的结论推及另一个域（§1.1）
- [ ] **L1 未变** —— 若动了 proto / 路由 / method / 错误码值 / 错误文案 / MQ 消息字段 ⇒ 停，走决策流程
- [ ] **未统一化** —— 错误码值/文案/语言/method/前缀**逐域隔离**，禁止提取共享基座（§1.1b）
- [ ] **L2 已归位** —— 每项业务规则都有明确承载位置
- [ ] **生态优先** —— 先问"Spring/生态是否已提供"，再考虑自研（D4）
- [ ] **starter 优先** —— 共享能力进 `common` 且用 `@AutoConfiguration`，**不写 `@Import` 清单**（D3）
- [ ] **机制可证** —— 引入的新框架机制配一条**真正触发它**的测试（§1.3）
- [ ] **Boot 4 未拆走** —— 引入 `spring-*-starter` 能力前，确认它在 Boot 4 下仍在 `spring-boot-autoconfigure` 里（§1.3-6）
- [ ] **写路径形态已知** —— `updateById(entity)` 还是 `update(null, wrapper)`？两者修法不同（§1.4 R3/R4）
- [ ] **HTTP 断言走真 Tomcat** —— 断言 `Content-Type`/`nosniff`/状态行时不用 MockMvc（§1.4 R5）
- [ ] **守门脚本合规** —— 计数取回归日志汇总 + 断言 `skipped=0` + 排除项用前缀匹配（§1.4 R2）
- [ ] **断言仍绿** —— `tools/difftest/check-arch-invariants.py` 通过
- [ ] **台账已写** —— 偏离写 Intentional_Deviations，踩坑写 Pitfalls，漂移写 DRIFT
- [ ] **文档已更新** —— 状态表纪律：任何"已修/未修"断言必须带**核对日期**，否则按 `UNKNOWN` 处理

---

## 七、引用清单

| 位置 | 管什么 |
|---|---|
| [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md) | 推导全文：Go 树 → 职责矩阵 → 映射表 → Java 树 → 变化表 → 五问自检 → 覆盖检查 |
| [`docs/深挖修正记录.md`](docs/深挖修正记录.md) | **Go 正推后的 8 处修正 + 5 条方法论纪律 + 6 项未完成待办** —— 推导的自我纠错档案 |
| [`docs/决策记录.md`](docs/决策记录.md) | D1–D6（已定）+ P1–P5（待拍板）+ 决策间关系图 |
| [`docs/L3-降级清单.md`](docs/L3-降级清单.md) | 4 项降级 + 6 项保留 + 变更登记规则 |
| [`docs/架构符合性断言.md`](docs/架构符合性断言.md) | A1–A12 + 契约绑定 + RAG 的 2 颗哑弹 |
| [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) | **RAG 已踩的坑 → RAG-V1 的处置**（12 条困境 + 4 条已避开 + 1 条正在复现 + RAG 自己的台账矛盾） |
| [`docs/GORM-MP-语义对照.md`](docs/GORM-MP-语义对照.md) | **GORM → MyBatis-Plus 语义不等价对照表**（R3 · 填实现时强制参考） |
| [`docs/Mapping_Table/_INDEX.md`](docs/Mapping_Table/_INDEX.md) | **Go ↔ RAG ↔ RAG-V1 三方文件名对照**（13 份域文档，**一域一文件夹**：`Mapping_Table/<域>/<域>.md`；users 域拆四份：`_shared.md` + `users-common.md` + `user/user.md` + `admin/admin.md`）—— 填实现前查「这个类从哪来」；**另登记 75 条落点缺口**（空树里「Go 有职责、C 侧无落点」的地方） |
| [`docs/DRIFT/`](docs/DRIFT/_INDEX.md) · [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md) · [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md) | 三档台账 |
| `tools/difftest/` | 可执行守门脚本 |
| Go 真源 | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang`（冻结只读） |
