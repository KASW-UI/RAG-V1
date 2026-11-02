# Mapping_Tree/common.md — `common`（全局共享层）两方文件名对照表

> **本表目的**：把 **RAG**（B 列，api/rpc 分离形态）→ **RAG-V1**（C 列，Spring 惯用目标架构）
> 两棵树里**全局共享层**的文件逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 B 侧文件来的」。
>
> 📌 **2026-11-02 · D16**：判定基准由「上游是唯一 oracle」改为「**B 侧是唯一真理**」⇒ **上游那一列已移除** ⇒ 本表由**三列（A/B/C）**改为**两列（B/C）**。
> ⇒ 表内凡讲上游来源之处，一律改读为 **B 侧**；B 侧与 C 侧的既有差异**保留原样并登记**，不回溯。
>
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/common/`（**88 个主代码类**，2026-11-01 实测）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/common/`（**104 个主代码类**，2026-11-02 实测；`common` 在仓库根，不在 `service/` 下）
>
> **格式契约**：[`README.MD`](README.MD) · **本目录其他子文档**：`users.md` / `article.md` / … 同格式

---

## 读表前的三条纪律（务必先读）

1. ⚠️ **B 列（RAG）自身带已登记的差异。**
   RAG 的 `service/common` 在对齐审计中 **3 个可比包 `NOT EQUIVALENT`、27 条编号漂移未修 + 5 项未编号**
   （[`RAG/docs/DRIFT/common/alignment-2026-10-23.md`](https://github.com/) §十二·一；`communityfact` 单独 14 条见同文件 §五）。
   ⇒ **一切行为判断以 B 列源码为准**；B 侧与第三方的既有差异 ⇒ **继承并登记为签字偏离**，**不回溯**。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 `docs/DRIFT/<域>/<组件>.md` 的**证据强度列**，不在本表。
3. ⚠️ **C 列只登记「这个位置该放什么职责」。**
   `common/` 的类已陆续落地（每行带「✅ 已落地（日期 · 批次）」标注）
   ⇒ **C 列的每一行是「职责位」，不是「已完成清单」**；落地与行为状态仍看 `docs/DRIFT/common/` 的证据强度列。

### 映射类型词表（固定 4 种，不发明新词）

| 词 | 含义 |
|---|---|
| **1:1** | 一个 B 文件 → 一个 C 文件 |
| **1:N** | 一个 B 文件拆成 N 个 C 文件 |
| **N:1** | 多个 B 文件合到 1 个 C 文件 |
| **C 新增** | B 侧没有对应物，是 RAG-V1 形态本身需要的新文件 |

> ⚠️ **本表的类型判定约定**：类型描述 **B → C** 的文件数关系（D16 后本表只有 B/C 两列）。
> 逐行判定**沿用原三方版的结论**（不因删列而重判）⇒ 分布见 §7.2。
> ⚠️ **B 侧落点写不出的行**（如「各域 `@Import` 清单」这类**跨模块清单**）仍按 4 种词表内取值，并在备注里写明落点。

### B 列「越界吸收」清单（读表前要知道）

本表的 B 列主轴是 `RAG/service/common/`，但 C 列的 `common/` **吸收了 RAG 里不属于 `service/common/` 的文件**。
这些在表里都会用**模块前缀**标出来（如 `article-api/http/...`），列成一张速查：

| C 列归属 | 吸收自 RAG 的非 `service/common` 位置 | 数量 |
|---|---|---|
| `common/src/main/java/com/rag/common/web/` | `article-api`（4 http 异常 + 3 filter + 1 dto）、`users-common/http/`（2）、`like-api/dto/`（2）、`message-api/`（2）、`common/http/`（3） | 16 |
| `common/src/main/java/com/rag/common/security/` | `users-common/jwt/`（2）+ `users-common/cryptx/`（1） | 3 |
| `common/src/main/java/com/rag/common/persistence/` | 5 个 rpc 模块各一份 `JsonbTypeHandler` + 2 个 rpc 模块各一份 `JsonTextTypeHandler` | 4（合 7 份） |
| `common/src/main/java/com/rag/common/autoconfigure/` | 9 个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration` | 1（收编 9 份） |
| `common/src/main/java/com/rag/common/web/GrpcStatusMapper.java` | `article-api/http/GoStyleGrpcStatusMapper` | 1 |

> ⚠️ **这正是 RAG-V1 的一个关键动作**：RAG 的共享能力**散在 6 个模块**（`service/common` + `users-common` + 各域 api/rpc），
> RAG-V1 把它们**收敛成一个 starter**（D3）。
> ⇒ 这也是 RAG 的 **R1 硬规则**所指的风险面：改 `common/` = 自动从并发降级为串行。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| B (RAG, 真理之源) | C (RAG-V1, Spring 目标) | 映射类型 | 备注 |
|---|---|---|
| `service/common/`（**12 个包 / 88 个主代码类**，2026-11-01 实测） | `common/`（**仓库根**，**12 个包 / 104 个主代码类**，2026-11-02 实测） | 1:1 | 包**路径**变（`service/common` → `common`），职责集合变；⚠️ C 侧第 **12** 个包 `redis/` 是 **2026-11-01 新建** |
| `service/common/communityfact/`（4 类） | `common/src/main/java/com/rag/common/communityfact/`（**13 类**） | 1:N | B 侧的「单文件多类型」被拆成 1 类型 1 文件（L1 wire 类型必须逐字段保真） |
| `service/common/logger/`（6 类） | `common/src/main/java/com/rag/common/logger/`（**5 类**） | N:1 | B 的 `Logger` + `LoggerFactoryBean` 双轨 → C 单个 `GoStyleLogger implements slf4j.Logger` |
| `service/common/observability/`（**19 类**）+ `spring/`（2 类） | `common/src/main/java/com/rag/common/observability/`（**19 类**，2026-11-02 实测） | N:1 | Micrometer Observation 承担（D-03 降级）；⚠️ 含 `ClientDisconnectProbe`（探针式断连暴露，§1.12）· `DeadlineState`（B 侧嵌套类提升，**L-16**） |
| `service/common/response/`（1 类） | `common/src/main/java/com/rag/common/contract/`（1 类） | 1:1 | 类名 `Response` → `ApiResponse`，**信封形状 `{code,msg,data}` 是 L1**（K-06） |
| `service/common/snowflake/`（1 类）+ `spring/`（2 类） | `common/src/main/java/com/rag/common/snowflake/`（1 类）+ `autoconfigure/`（1 类） | 1:N | epoch / 位段是业务契约（like 域游标依赖 `>>22`） |
| `service/common/protection/`（**24 类**）+ `spring/`（9 类） | `common/src/main/java/com/rag/common/protection/`（**18 类**）+ `autoconfigure/`（**3 类**） | N:1 | B 侧手写复刻的熔断/降载/限连接/限体积 ⇒ 生态吸收（D-04）；**gRPC 保护链的挂载点也在 `autoconfigure/`**（共享层 1 份 ⇒ 覆盖 10 个域，见 §7.4 #6） |
| `service/common/http/`（**13 类**）+ 各域 api/http、users-common/http | `common/src/main/java/com/rag/common/web/`（**21 类**） | N:1 | B 侧语义等价物**必须手写**（「何时读 body」「错误文案形状」都不是框架能力） |
| `service/common/exception/`（2 类） | `common/src/main/java/com/rag/common/exception/`（2 类） | 1:1 | B 侧用 `BusinessException(code,msg)`（承自上游 `(resp, error)` 双返回形态）⇒ 异常是 idiomatization |
| `service/<域>/<域>-rpc/src/main/java/com/rag/<域>/<域>/rpc/<Dom>RpcApplication.java` 上的 `@Import` 清单（**10 份**）＋ 9 份重复的 MP ordering bridge | `common/src/main/java/com/rag/common/autoconfigure/`（**12 类**）+ `AutoConfiguration.imports` | C 新增 | **D3 的载体**：消费方零 `@Import` 清单 |
| `service/users/users-common/jwt/` + `cryptx/`（3 类） | `common/src/main/java/com/rag/common/security/`（3 类） | 1:1 | 从 users 域**提升为全局**（鉴权是安全边界，同一规则不该有第二份实现） |
| `service/users/users-common/errmsg/ErrorCode.java` | **无** ⇒ 下沉到**各域模块内的 `contract/` 包**（`service/<域>/src/main/java/com/rag/<域>/contract/`；**D7 后不再是独立 `<域>-contract` 模块**） | N:1 | ⚠️ 见 §4 与 §7.4 的「两列职责对不上 #3」 |

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `service/common/`（模块） | `common/`（artifactId `common`）| **升级为真 starter**（D3：`AutoConfiguration.imports` 注册 **14** 个 `@AutoConfiguration`）|
| `service/common/…/communityfact/` | `common/src/main/java/com/rag/common/communityfact/` | 直接压平（13 个类）|
| `service/common/…/exception/`（2 类）| `common/src/main/java/com/rag/common/exception/`（2 类）| 直接压平 |
| `service/common/…/http/` | `common/src/main/java/com/rag/common/web/` | 压平 + **包改名 `http` → `web`**（`（无前缀）*` HTTP 兼容层归入 `web/`）|
| `service/common/…/logger/`（6 类）| `common/src/main/java/com/rag/common/logger/`（5 类）| 直接压平 |
| `service/common/…/observability/`（19 类）| `common/src/main/java/com/rag/common/observability/`（**19 类**）| 直接压平 |
| `service/common/…/persistence/`（3 类）| `common/src/main/java/com/rag/common/persistence/`（4 类）| 直接压平 |
| `service/common/…/redis/`（5 类） | `common/src/main/java/com/rag/common/redis/`（**5 类**）| ★ **C 侧第 12 个包，2026-11-01 新建**：断连行为收口 + 请求预算传播；**类名去 `Redis` 前缀**（包名已含 `redis`）|
| `service/common/…/protection/`（24 类） | `common/src/main/java/com/rag/common/protection/`（**18 类**）| ★ **D4 生态优先**：熔断 → resilience4j，降载/限体积各 1 个自研类；⚠️ 第 3/4 类 `RouteResolution` + `RouteTemplateSource` 是 **2026-11-01 补的路由模板解析载体** · 第 5 类 `ProtectionProperties` 是 **2026-11-02 独立配置载体**（**L-15**）· 第 6 类 `RouteTemplateResolver` 是 **2026-11-02 补的模板解析实现**（**V06 修法**）|
| `service/common/…/response/`（`Response`） | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 压平 + **改名**（信封是 L1）|
| `service/common/…/snowflake/` | `common/src/main/java/com/rag/common/snowflake/` | 直接压平 |
| `service/common/…/spring/` | `common/src/main/java/com/rag/common/autoconfigure/` | 压平 + **改名**（`@Configuration` → `@AutoConfiguration`，D3）|
| **—（B 侧无）** | `common/src/main/java/com/rag/common/contract/`（`ApiResponse`）| C 侧唯一的 `contract/` 包（**全局信封**，与各域错误契约无关）|

## 1. 包结构对照

### 1.1 `autoconfigure/` —— starter 的全部装配入口（C: 12 类）

> **D3 的载体。** 12 个 `@AutoConfiguration` 由
> `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册（**清单共 14 行**：另有 `redis/` 包的 2 个 —— 见 §1.12）。
> ⇒ **消费方零 `@Import` 清单**（RAG 侧有 10 份）。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `spring/HttpMiddlewareFilter.java` + `spring/AuthorityHandlerFactory.java` | `common/src/main/java/com/rag/common/autoconfigure/WebAutoConfiguration.java` | N:1 | 注册 servlet filter + `ServletRegistrationBean`；⚠️ Boot 4 的 `addUrlMappings` API<br>✅ **已落地（2026-11-02 · 批 3）**：批 1 落的 4 个 web filter/interceptor/advice（`PathNormalizingFilter` / `JsonBodyLimitFilter` / `TraceparentFilter` / `JsonContentTypeAdvice`）＋ `ValueLimitInterceptor` ＋ Tomcat `maxParameterCount=10001` ＋ `AuthorityHandler` 的 `ServletRegistrationBean` 工厂 全部在此注册 ⇒ **消费方零装配代码**；⚠️ Boot 4 → 3.5 包名回改（`org.springframework.boot.tomcat.*` → `org.springframework.boot.web.embedded.tomcat.*`） |
| `http/GoStyleJsonBodyConverterConfig.java` + `users-common/http/GoStyleCoercion.java` | `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | N:1 | 收编 Jackson `CoercionConfig`（K-01，**已是原生机制**）+ `JsonBodyConverter` 注册（K-02）<br>✅ **已落地（2026-11-02 · 批 3）**：K-01 走 **`postConfigurer` 路线**（Spring 6.2 的 `Jackson2ObjectMapperBuilder` **无** coercion API ⇒ 改 `ObjectMapper#coercionConfigFor`，探针实测改动生效）⇒ **不替换 Boot 的 `ObjectMapper`**（保住 `spring.jackson.*` 绑定）；⚠️ 插入点判据从 `JacksonJsonHttpMessageConverter`（Spring 7 才有）改成 **`MappingJackson2HttpMessageConverter`** |
| `spring/ObservabilityLifecycle.java` + `spring/ObservabilityProperties.java` + `observability/RpcInterceptor.java` + `observability/HttpMiddleware.java` + `observability/MqConsumer.java` + `observability/GoStyleRecoverInterceptor.java` + `spring/RpcServerInterceptorFactory.java` | `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | N:1 | ⚠️ **B 侧 `RpcServerInterceptorFactory` 曾经「Bean 在、没挂上」**（`DRIFT-COMMON-004`）⇒ C 侧必须断言真的进了 `server.intercept`<br>✅ **已落地（2026-11-02 · 批 3）**：HTTP 侧 = filter（`ContentCachingResponseWrapper` 抓信封 + 状态码 ⇒ 按 **B 侧的判定顺序**分类）＋ gRPC 侧 = `ServerInterceptor`（7 个业务码集合 / `DEADLINE_EXCEEDED` 优先 / `rpc-timeout` 到点关 `DEADLINE_EXCEEDED`）⇒ **判定与写入都在 `ObservabilityCustomizer`**（本 auto-config 只做装配）⇒ ⚠️ **真正挂链的是 `ProtectionAutoConfiguration` 的 `GrpcServerConfigurer`**（A3）⇒ 已由真触发测试断言 |
| `protection/`（24 类）+ `protection/GoStyleProtectionConfiguration.java` + `protection/GoStyleProtectionProperties.java` + `spring/RpcServerInterceptorFactory.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` | N:1 | resilience4j `CircuitBreaker` + Tomcat `max-connections` + 1 个 `MaxBytesFilter` + 1 个 CPU 指标（D-02 / D-04）**＋ `GrpcServerConfigurer`（gRPC 保护链的唯一挂载点，见 §7.4 #6 与设计文档 §5.5.2）**<br>✅ **已落地（2026-11-02 · 批 3）**：`GrpcServerConfigurer` **真调 `server.intercept(...)`** ⇒ **断言 A3 由 PENDING 转 PASS**；熔断隔离 key = `METHOD://<路由模板>`（**三个斜杠**，**与 B 侧同构**）＋ 失败判据 = 状态码 ≥ 500；⚠️ **限连接（Tomcat `max-connections`）要改 yml ⇒ 用户已叫停 yml ⇒ 本轮不落** |
| `spring/SnowflakeBean.java` + `spring/SnowflakeProperties.java` + `snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | N:1 | ⚠️ 与各域 `wiring/SnowflakeBootstrap.java`（3 个域有）**落点重复**，见 §7.4「职责对不上 #4」<br>✅ **已落地（2026-11-02 · 批 3）**：RAG 两类合一为 1 个 auto-config（配置载体作嵌套类）＋ 去 `@Component` 改 `@EnableConfigurationProperties`；⚠️ `Snowflake` 构造器 `private` → **`public`**（框架要 `new` 一个 Bean）⇒ **生成状态仍在唯一的静态 `STATE` 里 ⇒ 不变式不变** |
| 9 个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | N:1 | **消掉 9 份重复**（Boot 4 下 MP 自动配置排序桥）；断言 A6<br>✅ **已落地（2026-11-02 · 批 3）**：`afterName` 回改成 **Boot 3.5 的包名**（`org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`）⇒ Boot 3.5 下**不需要**救场 ⇒ 保留为「断言 A6 的唯一载体 + 升级护栏」 |
| `article-api/http/GoStyleGrpcStatusMapper.java` | `common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | 1:1 | D2/D5 的统一错误模型：`BusinessException(code,msg)` → gRPC 边界 / HTTP 边界两个适配器<br>✅ **已落地（2026-11-02 · 批 3）**：**gRPC 边界** = server 侧把 `BusinessException` 映射成 `UNKNOWN` + description（业务文案逐字）+ trailer `sea-biz-code`（业务码），client 侧从 `Listener#onClose` 还原；⚠️ **grpc-java 承载不了任意数值 status code** ⇒ 业务码走 trailer ⇒ **L3 新增 D-05**；⚠️ **HTTP 边界不在本类**（业务码空间逐域隔离 ⇒ L-11 ⇒ 留在各域 `advice/`） |
| `spring/LoggerFactoryBean.java` | `common/src/main/java/com/rag/common/autoconfigure/LoggerAutoConfiguration.java` | N:1 | 产出 `logger/LoggerFactoryBean.java`（共享日志门面的 Spring 适配）<br>✅ **已落地（2026-11-02 · 批次 D/E）**：RAG 侧本类是 `@Component`（靠各 api 模块 `scanBasePackages` 扫 `com.rag.common` 注册，rpc 模块只能靠 `@Import` 清单显式带上 ⇒ **两种注册方式并存**）⇒ 本仓 D1 合并 api/rpc + D3 starter 化 ⇒ ⇒ 改由 auto-config 的 `@Bean` 产出 ⇒ **消费方零装配代码**（A5：全仓 0 个 `@Import`） |
| 5 个 rpc 模块各一份 `@Bean` 声明（`FollowRpcApplication:90` / `MessageRpcApplication:85` / `LikeRpcApplication:94` / `ArticleRpcApplication:88` / `UserRpcApplication:67`）＋ `persistence/DeadlineQueryTimeoutInjector.java` ＋ `persistence/SqlTimeoutStatusTranslator.java` | `common/src/main/java/com/rag/common/autoconfigure/PersistenceAutoConfiguration.java` | N:1 | 🔴 **DRIFT-COMMON-V02 的修法**（2026-11-02 审核实测）：两个拦截器**批 1 就实现完整**，但**零注册点** ⇒ MP 收不到 ⇒ **SQL 超时链整条静默失效**（SQL 不带超时、pg `57014` 不翻译成 `DEADLINE_EXCEEDED`）⇒ B 侧的注册义务在**域内**（5 份 `@Bean`）⇒ C 侧 starter 化（D3）后**必须上收到共享层** ⇒ 落在本类<br>✅ **已落地（2026-11-02）**：`@ConditionalOnClass(name="org.apache.ibatis.plugin.Interceptor")` ＋ 两个 `@Bean`（各带 `@ConditionalOnMissingBean`）⇒ **10 个域一次全挂上**；⚠️ 生效机制已用 `javap -c` 取证：MP 3.5.17 的 `MybatisPlusAutoConfiguration` 构造器形参是 `ObjectProvider<Interceptor[]>` 且**构造器内即** `getIfAvailable()` ⇒ 注册成 Bean 即被收得到；守门见 `PersistenceAutoConfigurationTest`（**与 MP 同一个注入点**取证，3 例） |
### 1.2 `communityfact/` —— comment · favorite · like 三域共享的跨服务事件协议（C: 13 类）

> ⚠️ **三域归属（重要）**：`communityfact` 是 **comment / favorite / like 三域共享**的
> **L1 wire 协议**（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` 四个 wire 类型）。
> **本表只写共享层本体**；各域的接线（投递调度器 / authority 端点 / outbox 模型）**在各域表里**
> （`comment.md` / `favorite.md` / `like.md`），本表不重复。
>
> ⚠️ **B 侧（2026-11-02 `grep` 实测）的消费方是 comment + like + favorite 三域** ——
> 三域各有 **4 / 4 / 10** 个主代码文件 `import com.rag.common.communityfact.*`
> （`article` 域仅 javadoc 提及，**0 个 import**）。
> ⇒ **C 侧的「三域共享」与 B 侧实况一致**；原「favorite 是平行实现」是**旧形态的事实**，见 §7.4「职责对不上 #1」。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `communityfact/Wire.java`（嵌套 `Event`） | `common/src/main/java/com/rag/common/communityfact/Event.java` | 1:N | 9 字段；`aggregate_version` 是 `int64` **非指针**；B 侧 `payload` 是 `json.RawMessage`（**逐字输出**） |
| `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/TechnicalReceipt.java` | 1:N |  |
| `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/SubjectRef.java` | 1:N | ⚠️ 要求 `uid>0` **且** `FormatInt(uid,10)==id`（拒绝 `+1001` / `01001`） |
| `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/AuthorityFact.java` | 1:N |  |
| `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/DeliveryStatus.java` | 1:N | 取值 0/1/2/3 |
| `communityfact/Wire.java#canonicalHash/validHash` | `common/src/main/java/com/rag/common/communityfact/CanonicalHash.java` | 1:N | ⚠️ **RFC 8785 JCS 规范化后再 SHA-256**；Jackson 的 `SORT_PROPERTIES_ALPHABETICALLY` **不等价** ⇒ 指纹不一致会破坏幂等（AGENTS.md §1.1c **G-2**） |
| `communityfact/Wire.java` | `common/src/main/java/com/rag/common/communityfact/Wire.java` | 1:1 | C 保留 `Wire` 作门面；`StrictDecode` = `FAIL_ON_UNKNOWN_PROPERTIES` + 拒尾随 JSON |
| `communityfact/Wire.java`（内部类 `DCEventSender`） | `common/src/main/java/com/rag/common/communityfact/EventSender.java` | 1:N | 6 条 endpoint/token 校验 + 64KB body 限流 + 丢弃 body + `Idempotency-Key = event.eventId` |
| `communityfact/Dispatcher.java`（内部 `DispatcherImpl`） | `common/src/main/java/com/rag/common/communityfact/Dispatcher.java` | 1:1 | **域侧 store SPI** —— 由 comment / favorite / like 各域实现 |
| `communityfact/Dispatcher.java#run` | `common/src/main/java/com/rag/common/communityfact/EventDispatcher.java` | 1:N | 批次 16 / 1s ticker / 单次 10s deadline / `once` 模式；失败用**同一个 frozen event** 重试 |
| `communityfact/AuthorityHandler.java` + `spring/AuthorityHandlerFactory.java` | `common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java` | 1:1 | `GET /internal/v1/community/facts`；**404 vs 503 的区分是 fail-closed 设计**（authority 不可用不能伪装成「事实不存在」） |
| `communityfact/ProcessLogger.java`（内部 `ProcessMetadata`） | `common/src/main/java/com/rag/common/communityfact/ProcessMetadata.java` | 1:N | `version` 匹配 `^[0-9a-f]{40}$`；`environment ∈ {local,test,staging,production}` |
| `communityfact/ProcessLogger.java` | `common/src/main/java/com/rag/common/communityfact/ProcessLogger.java` | 1:1 | ⚠️ **不得使用全局 MDC**（三域合并成一个 JVM 后会跨线程插值）⇒ B 侧 `WithAttrs` 绑实例的语义必须保留。⚠️ **B 侧的 `InstallFrameworkLogger`（框架日志并入 OBS-r3 + 键改名 `trace→trace_id` 等）两侧都缺**（`DRIFT-CF-017` 未修）⇒ C 侧不得把它当成「已实现」，也不得擅自补（补即新增行为） |

### 1.3 `contract/` —— 全局信封（C: 1 类）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 1:1 | ⚠️ **L1 契约**：字段名 / 顺序 / 无 `omitempty`（`data:null` 恒出现）必须逐字保留；**不可用 `ProblemDetail` 替换**（K-06） |

### 1.4 `exception/` —— 业务异常（C: 2 类）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | 1:1 | 只接 `int` 业务码 + `String` 文案；**不耦合** Web / gRPC |
| `exception/AuthenticationException.java` | `common/src/main/java/com/rag/common/exception/AuthenticationException.java` | 1:1 | 与 `BusinessException` 只差一条：**HTTP 状态码是 401**（`DRIFT-004` 引入） |

### 1.5 `logger/` —— SLF4J 适配器（C: 5 类）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `logger/Logger.java` | `common/src/main/java/com/rag/common/logger/GoStyleLogger.java` | 1:1 | ⚠️ **待执行的改名**：设计已定「改名与归位」（`Logger` → SLF4J 适配器），**树里仍是 `GoStyleLogger`** ⇒ 归位时须同步改 import |
| `logger/LogOption.java` | `common/src/main/java/com/rag/common/logger/LogOption.java` | 1:1 | `@FunctionalInterface` |
| `logger/LogOptions.java` | `common/src/main/java/com/rag/common/logger/LogOptions.java` | 1:1 | `UserID` / `ArticleID`；空串 ⇒ **整字段不出现** |
| `logger/FatalError.java` + `Logger.doLogFatal` | `common/src/main/java/com/rag/common/logger/FatalError.java` | 1:1 | ⚠️ **B 侧 `FatalError` 是修复产物**（普通 `IllegalStateException` 会被 `@ExceptionHandler` 吞掉 ⇒ fatal 后进程继续服务） |
| `logger/WithUserID.java` + `logger/WithArticleID.java` | （并入 `common/src/main/java/com/rag/common/logger/LogOption.java` 的静态工厂 / lambda） | N:1 | C 无独立文件；`LogOption` 是 `@FunctionalInterface` ⇒ 调用方仍可写 lambda |
| `spring/LoggerFactoryBean.java`（369 行，与 `Logger` **零行为差异**） | `common/src/main/java/com/rag/common/logger/LoggerFactoryBean.java`（由 `common/src/main/java/com/rag/common/autoconfigure/LoggerAutoConfiguration.java` 注册） | N:1 | B 侧「静态轨 + Bean 轨」双轨并存 ⇒ C **保留双轨**（静态入口 `GoStyleLogger` + Bean 入口 `LoggerFactoryBean`）但**算法只留一份**（Bean 委托给 `GoStyleLogger.doLogXxx`）⇒ ⇒ 不会出现「改一处忘一处」<br>✅ **已落地（2026-11-02 · 批次 D/E）**：抄 RAG 整段（L2 · D13）＋ **装配形态回改**（`@Component` → auto-config 的 `@Bean`，L4 · D3）；⚠️ `serviceName` **各自独立**（Bean 走构造注入，静态轨走 `GoStyleLogger.init`）⇒ 委托只复用算法、不共享状态 |
| `logger/Logger.java#getCallChain/isFiltered` | （并入 `common/src/main/java/com/rag/common/logger/GoStyleLogger.java`） | 1:1 | ⚠️ `call_chain` 是**行为契约**（SLF4J 无等价 ⇒ AGENTS.md §1.1c **G-4**）；过滤失效会让它变成不可读的栈转储 |

### 1.6 `observability/` —— Observation + Deadline（C: 19 类）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `observability/Observability.java` + `observability/ObservabilityConstants.java` | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | N:1 | Micrometer Observation + OTel bridge 承担 span 写入（D-03）；⚠️ `sea.slow=true` 与 `sea.biz_code` 是**业务定义的标记，必须保留**<br>✅ **已落地（2026-11-02 · 批 3）**：**业务标记的唯一载体**（判定是纯函数 + 写入双载体：OTel span 带类型 ＋ Micrometer `Observation.Context`）⇒ ⚠️ Micrometer `KeyValue` **只有 String 值** ⇒ Observation 侧属性类型退化为字符串（**D-03 已登记**）；OTel 侧类型逐字保住 |
| `observability/Observability.java#slowThreshold` + `spring/ObservabilityProperties.java` | `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` | 1:1 | ⚠️ **B 侧** `MarkBusinessError` 读的是 **env** 而不是 yml ⇒ 这条路径上 yml 不生效，**不能顺手修正**<br>✅ **已落地（2026-11-02 · 批 3）**：去 `@Component` 改由 `ObservabilityAutoConfiguration` 的 `@EnableConfigurationProperties` 注册（starter 化 ⇒ 消费方零装配代码） |
| `observability/HttpMiddleware.java` + `spring/HttpMiddlewareFilter.java` + `observability/GoStyleClientDisconnectProbe.java` | `common/src/main/java/com/rag/common/observability/HttpMiddleware.java` ＋ `common/src/main/java/com/rag/common/observability/ObservabilityResponseWriter.java` ＋ `common/src/main/java/com/rag/common/observability/ClientDisconnectProbe.java` | 1:N | ⚠️ **499（客户端取消）/ 503（服务端超时）两分支是行为契约**，但 **B 侧漏了 499 写回**（`DRIFT-COMMON-011` 未修）<br>✅ **已落地（2026-11-02 · 批次 C）**：抄 RAG 整段（L2 · D13）＋ **写入载体改为 Micrometer Observation**（L4 · D4 / D-03）⇒ **三条拒绝路径的形状逐条对齐**：超时 = **503 + body `Request Timeout\n`（16 字节）+ `text/plain; charset=utf-8` + `nosniff` + `Content-Length: 16`**；断连 = **499**（同款 body）；池饱和 = **裸 503（无 body、无 Content-Type）**；⇒ ⇒ **512 worker 池**（`obs-http-worker` ＋ `SynchronousQueue` ⇒ 满了直接拒绝）⇒ **池饱和 503** 这条路径由此而来；⚠️ **RAG 侧注释写「15 字节」是笔误**（`Request Timeout` 15 字符 + `\n` 1 字节 = **16**）⇒ 本仓照**代码**不照注释 |
| `observability/RpcInterceptor.java` + `spring/RpcServerInterceptorFactory.java` + `observability/GoStyleRecoverInterceptor.java` | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | N:1 | 7 个业务 code 集合（`InvalidArgument/NotFound/AlreadyExists/PermissionDenied/Unauthenticated/FailedPrecondition/OutOfRange`）是行为语义 |
| `observability/RpcInterceptor.java`（`handle(...)` 静态方法 + `RpcResult` + `RpcCallHandler` 嵌套类 + `TIMEOUT_SCHEDULER` / `HANDLE_EXECUTOR` 两个 static 池） | `common/src/main/java/com/rag/common/observability/RpcHandleRunner.java` + `common/src/main/java/com/rag/common/observability/RpcResult.java` + `common/src/main/java/com/rag/common/observability/RpcCallHandler.java` + `common/src/main/java/com/rag/common/observability/ObservabilityRuntime.java` | 1:N | **非 gRPC 路径**的业务调用入口（独立线程跑 handler + 到点 `cancel(true)` 打断 + 观测分类）<br>✅ **已落地（2026-11-02 · 批次 E）**：抄 RAG 的 `handle(...)` 整段（L2 · D13）＋ **写入载体改为 Micrometer Observation**（L4 · D4）＋ **嵌套类提到顶层 / 静态池抽到 `ObservabilityRuntime`**（L4 · D11 / 跨包可见性）<br>⚠️ **只有这一条路径能真正打断业务线程**（`cancel(true)` ⇒ interrupt）⇒ gRPC 拦截器路径**没有**（grpc-java 的 `ServerCall#close()` 无法取消已在跑的 handler）⇒ 业务超时感知靠 `DeadlineState.isExpired()` 主动自查 |
| `spring/ObservabilityLifecycle.java`（`@PreDestroy` → `RpcInterceptor.shutdown()`） | `common/src/main/java/com/rag/common/observability/ObservabilityLifecycle.java` | 1:1 | 容器关闭时回收 `obs-rpc-timeout` / `obs-rpc-worker` 两个池<br>✅ **已落地（2026-11-02 · 批次 E）**：抄 RAG 整段（L2 · D13）＋ **从 `spring` 包搬到 `observability` 包 + 改由 `ObservabilityAutoConfiguration` 的 `@Bean` 注册**（L4 · D3 / D11）<br>⚠️ **零 servlet 依赖** ⇒ 纯 gRPC 进程加载也安全（RAG 侧曾在这里炸 `NoClassDefFoundError: jakarta/servlet/Filter` ⇒ 本仓用**包结构**从根上避开）；⚠️ **池被终止后按需重建**（RAG 侧踩过的坑：surefire 一个模块多个上下文 ⇒ 前一个关闭后 `schedule(...)` 被拒 ⇒ 拦截器返回 `INTERNAL`） |
| `observability/MqConsumer.java` | `common/src/main/java/com/rag/common/observability/MqConsumer.java` | 1:1 | ⚠️ **B 侧不含** ack / drop / 重试 / offset 提交 ⇒ C 侧同样不能加（加了就是新增行为）<br>✅ **已落地（2026-11-02 · 批次 E）**：抄 RAG 整段（L2 · D13）＋ **写入载体由「手写 OTel span」改为「Micrometer Observation + OTel 双载体」**（L4 · D4 / D-03）⇒ ⚠️ Micrometer **无 span kind 概念** ⇒ 用 `mq.kind=consumer` 低基数键值承载「这是一次消费」⇒ **这是本仓新增的一个键**（已登记 D-03 同族）；⚠️ caller 的 attrs 走 `highCardinalityKeyValue`（只进 trace 不进指标 ⇒ 对齐 OTel 的 span attribute 语义）；⚠️ **刻意不 catch `Throwable`**（照抄上游的裸调用语义 ⇒ 异常继续向上传播） |
| `observability/GoStyleRequestDeadline.java` | `common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java` | 1:1 | ⚠️ **K-03 保留项**：Java 的 `Future.cancel(true)` 对阻塞 IO 无效 ⇒ 必须走服务端取消语义（`setQueryTimeout`） |
| `observability/RpcInterceptor.java#DeadlineState`（**嵌套类**） | `common/src/main/java/com/rag/common/observability/DeadlineState.java` | 1:1 | ✅ **已落地（2026-11-02 · 批 1）**：从 RAG 的**嵌套类提升为顶层类**（决策锁 **L-16**）—— 188 行**零外部依赖**（纯 `ThreadLocal`）⇒ 可独立编译；`persistence/DeadlineQueryTimeoutInjector` **硬依赖**它（读当前 rpc 的 deadline 落进每条 SQL 的 `setQueryTimeout`）⇒ 不拆则批 1 不可编译 |

### 1.7 `persistence/` —— 超时注入 + JSON TypeHandler（C: 4 类）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `persistence/DeadlineQueryTimeoutInjector.java` | `common/src/main/java/com/rag/common/persistence/DeadlineQueryTimeoutInjector.java` | 1:1 | 把当前 rpc 的 deadline 落到每条 SQL 的 `setQueryTimeout`；⚠️ **无 deadline 时不注入** |
| `persistence/SqlTimeoutStatusTranslator.java` | `common/src/main/java/com/rag/common/persistence/SqlTimeoutStatusTranslator.java` | 1:1 | `SQLState=57014` → `DEADLINE_EXCEEDED`；⚠️ **必须拦 `Executor` 而非 `StatementHandler`**，且 `catch(SQLException)` 匹配不上 MyBatis 抛的 `InvocationTargetException`；🔴 **2026-11-03 补签 `Executor.queryCursor`**（MP 的 `BaseMapper.selectOne(Wrapper, boolean)` 走的游标路径此前**完全不经过本翻译器** ⇒ 57014 原样冒到域层 ⇒ 5001）；⚠️ **不可签 3 参的 `Executor.query`**（MyBatis 3.5.19 无此方法 ⇒ `Plugin.getSignatureMap` 启动期抛 `PluginException`） |
| （B 侧**无对应类** —— B 靠 ctx 掐住整个 DB 调用） | `common/src/main/java/com/rag/common/persistence/DeadlineBoundedConnectionTimeout.java` | **C 独有** | 🔴 **DRIFT-COMMON-V15 修法 ③**（2026-11-03 · `global` 收口域）：把 Hikari 的 `connectionTimeout` **只收紧不放宽**地收到内层预算（`min(http-timeout, rpc-timeout)`）⇒ ⇒ **PG 不可用时不再干等 30s**；⚠️ `DeadlineQueryTimeoutInjector` 只管「已拿到连接上的语句」，**管不到「取连接」** ⇒ ⇒ 两者必须成对 |
| （B 侧**无对应类**） | `common/src/main/java/com/rag/common/persistence/DeadlineTranslatingDataSource.java` | **C 独有** | 🔴 **DRIFT-COMMON-V15 修法 ④**（2026-11-03）：把「取连接等超时」（Hikari `08001` ＋ 文案 `request timed out after`）翻成 `StatusRuntimeException(DEADLINE_EXCEEDED)`；⚠️ 继承 `DelegatingDataSource` ⇒ **Boot 3.5 的 `DataSourceUnwrapper` 专门认识它** ⇒ 不丢 Hikari 池指标；⚠️ **共享层不硬编码 1015**（§八 错误码逐域隔离）⇒ 由域层既有 `catch (StatusRuntimeException e) { throw e; }` 放行 ⇒ api 层 `default` 分支落 1015 |
| （B 侧**无对应类** —— B 的 gRPC 中间件不做异常包装） | `common/src/main/java/com/rag/common/persistence/StatusAwareSqlSession.java` ＋ `common/src/main/java/com/rag/common/persistence/StatusAwareSqlSessionFactory.java` ＋ `common/src/main/java/com/rag/common/persistence/StatusAwareSqlSessionFactoryPostProcessor.java` | **C 独有** | 🔴 **DRIFT-COMMON-V15 修法 ④**（2026-11-03）：**解掉 MyBatis 的 `PersistenceException` 包装** ⇒ ⇒ 持久层翻出的 `DEADLINE_EXCEEDED` 能以 `StatusRuntimeException` 的形态**直达域层**（否则落 `catch (Exception e)` ⇒ 被改写成 **5001**）；⚠️ 解包点选在 **`SqlSessionFactory`**（本仓两条 mapper 路径都经它）⇒ ⚠️ **只解包 `DEADLINE_EXCEEDED`** ⇒ 域层 46 处放行分支的既有行为**一个都不变** |
| 5 份 `JsonbTypeHandler.java`（`like-rpc` / `comment-rpc` / `favorite-rpc` / `admin-rpc` / `article-rpc` 各一份） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | N:1 | **7 份副本合 1**；⚠️ **5 份并非同一份** —— comment/favorite/like **三份代码体逐字一致**（本类取 comment 版）⇒ **article 那份不是 TypeHandler**（是工具类 `final class` ＋ 静态方法 ＋ 内嵌 `StringListTypeHandler`/`StringMapTypeHandler`）⇒ **不并入**（其落点属 article 域）；✅ **批次 G 已用真 PostgreSQL 证过行为**（见 §7.10） |
| 2 份 `JsonTextTypeHandler.java`（`user-rpc` / `admin-rpc` 各一份） | `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java` | N:1 | **副本合 1**（两份代码体逐字一致 ⇒ 取 admin 版）；⚠️ **它不是 `JsonbTypeHandler` 的重复**：**B 侧 `extra_info` 列是 text**（`serializer:json` ⇒ 按 String 建列）⇒ jsonb 的 unicode escape 校验会拒收 ` `（**22P05**）而 text 照单全收 ⇒ **两个 handler 必须分开**（活体对拍证据 RAG 侧 **DRIFT-ARPC-004①**）；✅ **批次 G 已用真 PostgreSQL 证过行为**（见 §7.10） |

### 1.8 `protection/` —— 请求保护链（C: 18 类）

> ⚠️ **B 侧 24 类 → C 侧 4 类**（其中 2 类是 **2026-11-01 补的路由模板解析载体**），仍是全表最大的形态变化。
> 根因：**B 侧这些能力全是逐行复刻 go-zero 内置实现**（熔断 / CPU 降载 / 限连接 / 限体积），
> RAG-V1 按 **D4 生态优先**把它们交回生态。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `protection/GoStyleBreaker.java` + `GoStyleBreakerFilter.java` + `GoStyleCircuitOpenException.java` + `GoStyleStatusCapturingResponse.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（resilience4j `CircuitBreaker` + `CircuitBreakerRegistry`） | N:1 | ⚠️ **D-02 L3 降级**：失败判定窗口 / half-open 策略与 go-zero 不同；隔离 key 仍是 `METHOD://path`（**每路由一个实例**） |
| `protection/GoStyleRpcBreakerInterceptor.java` + `GoStyleRpcFailurePolicy.java` + `GoStyleRpcProtectionChain.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（gRPC server interceptor 装配） | N:1 | ⚠️ **链序是行为语义**：**B 侧的保护链本体是 recover → breaker → shedding → business（最内）**（stat / prometheus 在 B 侧是独立 gRPC interceptor，不在同一条链里）；失败判据**只保留 6 码白名单浅查**（`DEADLINE_EXCEEDED` / `INTERNAL` / `UNAVAILABLE` / `DATA_LOSS` / `UNIMPLEMENTED` / `RESOURCE_EXHAUSTED`） |
| `protection/GoStyleGrpcClientBreakerInterceptor.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java`（devh `@GrpcGlobalClientInterceptor`） | N:1 | ⚠️ `@GrpcGlobalClientInterceptor` + `@Component` **缺一即静默失效**（Bean 在、channel 里没有） |
| `protection/GoStyleCpuReader.java` + `GoStyleAdaptiveShedder.java` + `GoStyleCpuSheddingFilter.java` + `GoStyleSheddingStat.java` | `common/src/main/java/com/rag/common/protection/CpuThresholdHealthIndicator.java`（CPU 读数）＋ `common/src/main/java/com/rag/common/protection/AdaptiveShedder.java`（**双条件判据**）＋ `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `CpuSheddingFilter`（**REST filter**） | N:1 | ⚠️ **生态无等价物**（AGENTS.md §1.1c **G-1**）⇒ 自研；**拒绝响应形状（裸状态码 + 空 body + 无 Content-Type）必须保留**（D-04）<br>✅ **已落地（2026-11-02 · 批 3）**：Actuator `HealthIndicator` 形态（不可用 ⇒ `-1` 哨兵 + **fail-open**）<br>🔴 **⚠️ 2026-11-02 批次 H 改判（此前那行的前提是错的）** —— 原写「生态无第二个条件的等价物 ⇒ **本仓只提供 CPU 这一半判据** ⇒ L3 缺口（D-04 已登记）」⇒ **前提不成立**：**B 侧 REST filter（`GoStyleCpuSheddingFilter`）本来就用完整的 `GoStyleAdaptiveShedder`** ⇒ ⇒ 那不是「生态缺口」，是 **C 侧丢了 B 已有能力的真实漂移**（⇒ 比 B 更早丢请求）⇒ ⇒ **已修**：REST filter 改走 `AdaptiveShedder`（与 B 侧**逐行同构**）⇒ **双条件判据 + pass/fail 记账 + 浮点 RT 口径**回到 B 侧形态 ⇒ ⇒ **D-04 撤销降级**（守门 `CpuSheddingFilterTest` 4 例 ＋ 变异 4 次全红）；⚠️ **仅剩「HTTP 侧的可观察面」缺口**（无每分钟统计行 ＋ 无 `[http] dropped` 行）⇒ **B 侧原生** ⇒ 已登记 **D-08** |
| `protection/GoStyleMaxBytesFilter.java` | `common/src/main/java/com/rag/common/protection/MaxBytesFilter.java` | 1:1 | 生态无等价 ⇒ 1 个 filter；⚠️ 判据是 `ContentLength > n` ⇒ 413 空 body<br>✅ **已落地（2026-11-02 · 批 3）**：去 `@Component` / `@ConditionalOnApiSide`（D1 合并进程 ⇒ 「api 侧」这个维度不存在）⇒ 条件改 `@ConditionalOnWebApplication(SERVLET)`；**注册动作交 `ProtectionAutoConfiguration`** |
| `protection/GoStyleMaxConnsFilter.java` | `common/src/main/java/com/rag/common/protection/MaxConnsFilter.java` | 1:1 | ⚠️ **「Tomcat 原生项 ⇒ 零 Java 代码」这个口径已被推翻**（2026-11-02 · 批次 E）—— Tomcat 的三个配额**没有一个**与**上游**语义等价：`max-threads` 超限**排队**、`max-connections` 超限**停止 accept**、`accept-count` 只是队列长度 ⇒ ⇒ **上游**是「立刻 503 空 body」，Tomcat 是「变慢」⇒ **两种不同的可观察行为** ⇒ ⇒ 必须自己实现闸门（`Semaphore.tryAcquire`）<br>✅ **已落地（2026-11-02 · 批次 E）**：抄 RAG 整段（L2 · D13）＋ **装配形态回改**（`@Component` / `@ConditionalOnApiSide` → auto-config 的 `@Bean` + `@ConditionalOnWebApplication(SERVLET)`，L4 · D3 / D1）⇒ **不改 yml**（L-5：yml 冻结）⇒ 闸门在 Java 侧 ⇒ 零 yml 改动<br>⚠️ **B 侧的上限是「全进程共享一个闸门」**（上游原为 per-route，每路由各 new 一个 latch ⇒ B 侧已收敛为全局，属 B 侧已签字的偏离 `SD-2026-10-25-01`）⇒ **C 侧照抄 B（同样全局一个）⇒ 与 B 侧一致** ⇒ **不得顺手改回** |
| `protection/GoStyleProtectionProperties.java` | `common/src/main/java/com/rag/common/protection/ProtectionProperties.java` | 1:1 | ✅ **已落地（2026-11-02 · 批 1）**：**由原 N:1 拆为独立配置载体**（决策锁 **L-15**）—— 理由：① `redis/DeadlineAutoConfiguration` **硬依赖**它（跨批硬依赖 ⇒ 不能压到批 3 的 auto-config 才落）② Spring 生态惯例 = `@ConfigurationProperties` 类独立 + auto-config 用 `@EnableConfigurationProperties` 引用 ③ **纯配置载体不属 D4 生态吸收对象**（D4 吸收的是「能力」，不是「配置」） |
| `protection/GoStyleProtectionConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` | N:1 | `@EnableConfigurationProperties` 由 auto-config 接管 ⇒ 消费方零 `@Import`（B 侧曾因漏声明导致 23 条测试全红） |

### 1.9 `security/` —— JWT + 口令（C: 3 类）

> ⚠️ **B 侧出处不在 `service/common/`，而在 `service/users/users-common/`。**
> 即：**C 的 `common/src/main/java/com/rag/common/security/` 对应 B 的 users 域共享包**（RAG-V1 有意把它提升为全局）。
> ⇒ 这三行**不计入 §7.2 的「common 域 B 列覆盖」**（归 users 域），但**计入 C 列覆盖**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `users-common/jwt/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | 1:1 | ⚠️ claim 名 `userId`（`Long`）+ `exp` + `iat`；签名算法必须**钉死 HS256**（jjwt 的 `Keys.hmacShaKeyFor` 会随密钥长度漂到 HS384/HS512） |
| `users-common/jwt/BearerTokenExtractor.java` | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | 1:1 | ⚠️ **B 侧两套互不相同**的取法（验签 vs 黑名单 key）必须**各自保留**，合并即等于擅自修好 B 侧的 bug |
| `users-common/cryptx/PasswordUtil.java` | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | 1:1 | ⚠️ **B 侧 `check()`** 把「密码不匹配」与「hash 本身非法」**压成同一个 false**（另有 `checkDetailed()` 可区分三态）⇒ 畸形 hash 不得抛异常 |

### 1.10 `snowflake/` —— 分布式 ID（C: 1 类）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` | 1:1 | ⚠️ epoch / 位段是**业务契约**（like 域游标编码依赖 `>>22`） |
| `spring/SnowflakeBean.java` + `spring/SnowflakeProperties.java` | `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | N:1 | `SNOWFLAKE_NODE_ID` 优先，parse 失败**继续走 hash 不报错**；否则 `sha256("SERVICE_NAME-PID")` 取前 2 字节大端 ⇒ `%1024` |

### 1.11 `web/` —— B 侧语义的 HTTP 层（C: 21 类）

> ⚠️ **B 侧 `service/common/http/` 现在 13 类**（原 3 类 ＋ 2026-10-25 第十四轮起新增的 10 类），C 侧 `web/` **21 类** ⇒ **两侧不是一对一**：C 侧 21 类 = B 侧 `common/http/` 的 5 类 ＋ **各域 api 模块归位的 11 类** ＋ **2026-11-01 补的 5 类**（`LimitMode` / `ValueLimitInterceptor` / `LimitExceededException` / `JsonContentTypeAdvice` / `TraceparentFilter`，见 §1.12）。
> 根因（设计文档 §5.5）：Jackson 的 `CoercionConfig` 是原生机制，但**「何时才读请求体」与「错误文案 / 状态码形状」不是框架能力** ⇒ 必须手写。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `common/http/GoStyleJsonBodyConverter.java` | `common/src/main/java/com/rag/common/web/JsonBodyConverter.java` | 1:1 | ⚠️ **K-02**：判据是 `ContentLength > 0` 且 Content-Type **字面**含 `application/json` ⇒ `application/vnd.api+json` 在 **B 侧不匹配**<br>✅ **已落地（2026-11-02 · 批 2）**：Jackson 3 → 2 + Spring 7 → 6.2 的 **5 处回改**（基类 / `read` 签名 / mapper 类型 / `canRead`·`canWrite` 覆写点 / 包名）⇒ 决策锁 **L-17** |
| `common/http/GoStyleJsonBodyConverterConfig.java` | `common/src/main/java/com/rag/common/web/JsonBodyConverterConfig.java` | 1:1 | 注册点；⚠️ C 侧最终应并入 `JsonBodyConverterAutoConfiguration`（见 §7.4「职责对不上 #5」） |
| `common/http/GoStyleRouterErrorResponses.java` | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` | 1:1 | 400 + **text/plain** 的错误形状（D-01 只降级**文案**，形状不受影响） |
| `users-common/http/GoStyleCoercion.java` | `common/src/main/java/com/rag/common/web/Coercion.java` | 1:1 | ⚠️ **K-01**：Jackson 原生 `CoercionConfig` ⇒ **不是自研**；数字/布尔不进 String 字段<br>✅ **已落地（2026-11-02 · 批 2）**：**回改量 = 0** —— Jackson 2.19.4 的 `withCoercionConfig(LogicalType, …)` / `MutableCoercionConfig.setCoercion(…)` / `CoercionInputShape` / `LogicalType` / `CoercionAction` **与 Jackson 3 逐项同构** ⇒ 只换包名；⚠️ **订正**：批 1 评估时写「Jackson 2 无此 API ⇒ 真重写」是**错的**（javap 实测） |
| `users-common/http/GoStyleStringInt64.java` + `message-api/dto/GoStyleStringInt64.java`（**2 份**） | `common/src/main/java/com/rag/common/web/StringInt64.java` | N:1 | 副本合一 |
| `like-api/dto/GoStyleFloat64Deserializer.java` | `common/src/main/java/com/rag/common/web/Float64Deserializer.java` | 1:1 | 归位<br>✅ **已落地（2026-11-02 · 批 2）**：包名回改 + **补 `throws IOException`**（Jackson 2 的 `JsonDeserializer#deserialize` 声明 checked 异常，Jackson 3 是 unchecked） |
| `like-api/dto/GoStyleRawStringListDeserializer.java` | `common/src/main/java/com/rag/common/web/RawStringListDeserializer.java` | 1:1 | 归位<br>✅ **已落地（2026-11-02 · 批 2）**：包名回改 + `throws IOException`；⚠️ **实测发现**：RAG 类注释写「整个字段 null ⇒ 空列表」，**实际** Jackson 走 `getNullValue` ⇒ 返回 **null** ⇒ **RAG 侧同样如此** ⇒ 照抄**行为**、订正注释 |
| `message-api/config/GoStyleMapStringValueDeserializer.java` | `common/src/main/java/com/rag/common/web/MapStringValueDeserializer.java` | 1:1 | 归位<br>✅ **已落地（2026-11-02 · 批 2）**：基类 Jackson 3 `ValueDeserializer` → Jackson 2 **`JsonDeserializer`**（Jackson 2 **没有** `ValueDeserializer` 这个类）＋ `getNullValue` 补 `throws JsonMappingException` |
| `article-api/dto/GoStyleStringFields.java` | `common/src/main/java/com/rag/common/web/StringFields.java` | 1:1 | 归位 |
| `article-api/http/GoStyleBadRequestException.java` | `common/src/main/java/com/rag/common/web/BadRequestException.java` | 1:1 | 归位 |
| `article-api/http/GoStyleHttpErrorException.java` | `common/src/main/java/com/rag/common/web/HttpErrorException.java` | 1:1 | 归位 |
| `article-api/http/GoStylePlainTextErrors.java` | `common/src/main/java/com/rag/common/web/PlainTextErrors.java` | 1:1 | 归位；⚠️ **真 Tomcat 下 400 的 `Content-Type` / `nosniff`**（MockMvc 测不到） |
| `article-api/http/GoStyleGrpcStatusMapper.java` | `common/src/main/java/com/rag/common/web/GrpcStatusMapper.java` | 1:1 | ⚠️ **与 `GrpcExceptionMapperAutoConfiguration` 职责重叠**，见 §7.4「职责对不上 #2」 |
| `common/http/GoStyleFormValues.java`（⚠️ **原 `GoStyleGetFormValuesFilter` 已被 RAG 收编进共享层，该类名在 RAG 侧已不存在**） | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` | 1:1 | 归位；收编详情见 §1.12 第 1 行 |
| `article-api/config/GoStyleJsonBodyLimitFilter.java` | `common/src/main/java/com/rag/common/web/JsonBodyLimitFilter.java` | 1:1 | 归位；⚠️ 与 `protection/MaxBytesFilter.java` **语义相邻但不同**，别合并 |
| `article-api/config/GoStylePathNormalizingFilter.java` | `common/src/main/java/com/rag/common/web/PathNormalizingFilter.java` | 1:1 | 归位 |

### 1.12 🔴 RAG 侧 `service/common` 增量补录（19 条 · 覆盖 35 个 `.java`）

> ⚠️ **本节是 2026-11-01 的增量**：RAG 仓在映射表首版之后继续生长
> （`service/common` 从 3 个包长到 **12 个包**）⇒ 首版 §1.1–§1.11 的 **B 列漏了 35 个 `.java`**。
> ⇒ 本节把它们**逐个落到 C 侧**；补齐后 `tools/check-mapping-coverage.py` 的 **B 列双向对账（正反两向）归零**。
>
> **抄写纪律（D13）**：这 35 个类 **L2 以 RAG 为基准整段抄**；
> 但**落点按 C 侧包结构重新归位** ⇒ 抄的是**行为**，不是**路径**。
> ⚠️ 其中原 **9 行标「无对应文件」** ⇒ **2026-11-01 已补 7 行**（form 族 3 + `JsonContentTypeAdvice` + `TraceparentFilter` + `ClientDisconnectProbe` + `RouteResolution`/`RouteTemplateSource` + `redis/` 5 类）⇒ **余 2 行仍标「无对应文件」**：① `common/spring/HttpPoolLifecycle.java`（**D4 生态吸收** ⇒ Tomcat 自管连接池，零 Java 代码）② `common/web/ConditionalOnApiSide.java`（**D1 合并 api/rpc** ⇒ C 侧不存在「api 侧」这个维度）—— **两条都不是缺口**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `common/http/GoStyleFormValues.java` | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` | 1:1 | ⚠️ **RAG 已把域内 `GoStyleGetFormValuesFilter`（2026-10-25 第十四轮）收编进共享层** ⇒ B 侧旧类名**已不存在**；本行即 §1.11 旧行的改写 |
| `common/http/GoStyleFormLimitMode.java` ＋ `common/http/GoStyleFormValueLimitInterceptor.java` ＋ `common/http/GoStyleFormLimitExceededException.java` | `common/src/main/java/com/rag/common/web/LimitMode.java` ＋ `common/src/main/java/com/rag/common/web/ValueLimitInterceptor.java` ＋ `common/src/main/java/com/rag/common/web/LimitExceededException.java` | 1:1 | ✅ **已落地（2026-11-01）**：三个类落 `common/web/`（**去 `Form` 前缀**，命名债 P4-④ 先例）。⚠️ **枚举在共享层、文案在域里**：`LimitMode` 只承载**拒绝形状**（RAW / ENVELOPE / OFF），**错误码与文案仍由各域 `advice/` 渲染** ⇒ 共享层不自带域级文案；闸门用 `HandlerInterceptor`（非 servlet `Filter`）才能把异常抛到 `@RestControllerAdvice` |
| `common/http/GoStyleFormLimitWebMvcConfig.java` ＋ `common/http/GoStyleTomcatParameterLimitCustomizer.java` ＋ `common/http/GoStylePathNormalizingAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/WebAutoConfiguration.java` | N:1 | 3 个注册点归 **starter**（D3）；⚠️ 拦截器必须 `HIGHEST_PRECEDENCE` ⇒ 先于域鉴权（**与 B 侧同款** ⇒ C ≡ B） |
| `common/http/GoStyleJsonContentTypeAdvice.java` | `common/src/main/java/com/rag/common/web/JsonContentTypeAdvice.java` | 1:1 | ✅ **已落地（2026-11-01）**：落 `common/web/` —— 共享层 1 份 `@ResponseBodyAdvice`，给 `Content-Type` 补 `charset=utf-8`（对齐 go-zero `httpx.doWriteJson`）；⚠️ 各域 `advice/<Dom>ApiResponseAdvice.java` 的**信封与错误码**不受影响 |
| `common/http/GoStyleTraceparentFilter.java` | `common/src/main/java/com/rag/common/web/TraceparentFilter.java` | 1:1 | ✅ **已落地（2026-11-01）**：落 `common/web/` —— servlet `Filter` 形态透传 `traceparent`；⚠️ 与 `common/observability/ObservabilityCustomizer.java`（Observation 侧）**分工不同**：前者是 HTTP 头透传，后者是埋点装配 |
| `common/observability/GoStyleOtelAgent.java` | `common/src/main/java/com/rag/common/observability/GoStyleOtelAgent.java` | 1:1 | ✅ **已落地（2026-11-02 · 批次 F）**：抄 RAG 整段（L2 · D13）⇒ 幂等（`AtomicBoolean` ≡ 上游的 `sync.Once`）/ `buildProvider` 逐行对齐上游的 `startAgent`（`parentBased(traceIdRatioBased(ratio))` ＋ `Resource(service.name)` ＋ **「endpoint 为空 ⇒ 不装 exporter」** 的短路）/ 全局 `set()` 失败**只记 ERROR 不抛**（= 上游 `logx.Error` 后继续启动）/ shutdown hook flush ⇒ **【DRIFT 报告】的最后 1 条 NOT_IMPLEMENTED 清零**；⚠️ **旧口径已被批次 F 推翻**（「`GoStyleOtelAgent` 是 javaagent 形态 ⇒ 归到 `ObservabilityAutoConfiguration`（N:1）」）⇒ 本行改回 **1:1**；对拍：`GoStyleOtelAgentTest`（7 例，含**真 Spring 上下文**＋**真 gRPC 假 OTLP 接收端**） |
| `common/observability/GoStyleOtelAutoConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/GoStyleOtelAutoConfiguration.java` | 1:1 | ✅ **已落地（2026-11-02 · 批次 F）**：抄 RAG 整段（L2 · D13）＋ **包名回改**（`observability/` → `autoconfigure/`，与本仓其余 11 个 auto-config 同包，L4 · D11/D3）⇒ 仍用 `static @Bean BeanFactoryPostProcessor` + `EnvironmentAware`（⇒ 早于任何单例创建 ⇒ 不会被构造器里的 `GlobalOpenTelemetry.get()` 抢跑）⇒ 已登记进 `AutoConfiguration.imports`（清单 13 → **14 行**）；⚠️ **旧口径已被批次 F 推翻**（「归到 `ObservabilityAutoConfiguration`（N:1）」）⇒ 本行改回 **1:1** |
| `common/observability/GoStyleOtelConfig.java` | `common/src/main/java/com/rag/common/observability/GoStyleOtelConfig.java` | 1:1 | ✅ **已落地（2026-11-02 · 批次 F）**：**B ↔ C 真正 1:1**（原先 C 侧挂在 `ObservabilityProperties` 的 `observability.*` 命名空间下但**只承载、不驱动装配** ⇒ 本轮移出、归位到本类）⇒ **键名随之回到 B 侧的 `otel.service.name` / `otel.resource.attributes.service.name` / `otel.exporter.otlp.endpoint` / `otel.exporter.otlp.protocol` / `otel.traces.sampler[.arg]`** ⇒ **L1 配置契约与 B 侧逐字节同构**；对拍：`GoStyleOtelConfigTest`（13 例） |
| `common/observability/GoStylePrometheusInterceptor.java` ＋ `common/observability/GoStyleStatInterceptor.java` ＋ `common/observability/GoStyleStatMetrics.java` ＋ `common/observability/GoStylePrometheusMetrics.java` | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | N:1 | Micrometer `Observation` ＋ `Timer` 承担（D4）；⚠️ B 侧 `GoStylePrometheusMetrics` 是**静态 holder** ⇒ C 侧 registry 是 **Bean** ⇒ **无静态 holder**（形态差，非行为差） |
| `common/observability/GoStylePrometheusSink.java` ＋ `common/observability/MicrometerPrometheusSink.java` ＋ `common/observability/GoStylePrometheusMeterRegistryBridge.java` | `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | N:1 | ⚠️ **D4 生态吸收**：C 侧 Boot 原生 `micrometer-registry-prometheus` ⇒ **不复制 RAG 的接缝接口**（零 Java 代码） |
| `common/observability/GoStyleRpcMetricsConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | 1:1 | 指标名 / 标签 / 调用点是**业务定义** ⇒ 由各域 `metrics/<Dom>RpcMetrics.java` 承载（见各域表） |
| `common/observability/GoStyleClientDisconnectProbe.java` | `common/src/main/java/com/rag/common/observability/ClientDisconnectProbe.java` | 1:1 | ✅ **已落地（2026-11-01）**：落 `common/observability/` —— 往 socket 写 1xx 探针，把客户端断连**提前暴露**出来（探针式断连暴露在 Java 侧终于有载体） |
| `common/protection/GoStyleBreakerRouteTemplateAutoConfiguration.java` ＋ `common/protection/GoStyleRouteTemplateResolver.java` | `common/src/main/java/com/rag/common/autoconfigure/RouteTemplateAutoConfiguration.java` ＋ `common/src/main/java/com/rag/common/protection/RouteTemplateResolver.java` | 1:N | ✅ **已落地（2026-11-02 · V06 修法）**：拆成**独立的窄接口实现 + 独立 auto-config** ⇒ ① `RouteTemplateResolver` 引用 Spring MVC 类型 ⇒ 必须独立成类 + 类级 `@ConditionalOnClass` ⇒ 否则 10 个 rpc 进程加载 `ProtectionAutoConfiguration` 时 `NoClassDefFoundError`；② `RouteTemplateAutoConfiguration` 独立 ⇒ `ProtectionAutoConfiguration` 常量池里不出现 MVC 类型；⚠️ **V05 同批修**：`RouteIsolationFilter` 改走窄接口 ⇒ 模板判不出来（`UNKNOWN`）时**回落 raw URI 分桶**（熔断仍生效）⇒ 修正前是「放行、不熔断」⇒ **熔断被静默关掉** |
| `common/protection/GoStyleGrpcClientBreakerConfiguration.java` ＋ `common/protection/GoStyleOutboundDeadlineConfiguration.java` ＋ `common/protection/GoStyleOutboundDeadlineInterceptor.java` | `common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` | N:1 | resilience4j `CircuitBreakerRegistry`（D-02）；隔离 key 仍是 `METHOD://path` ⇒ **路由模板解析不能省** |
| `common/protection/GoStyleRouteResolution.java` ＋ `common/protection/GoStyleRouteTemplateSource.java` | `common/src/main/java/com/rag/common/protection/RouteResolution.java` ＋ `common/src/main/java/com/rag/common/protection/RouteTemplateSource.java` | 1:1 | ✅ **已落地（2026-11-01 建壳 → 2026-11-02 填实现 · V06 修法）**：三态（`TEMPLATE` / `NOT_MATCHED` / `UNKNOWN`）语义逐条保留 ⇒ ① `NOT_MATCHED` ⇒ **B 侧 404 链里没有 breaker** ⇒ 本层完全不参与；② `UNKNOWN` ⇒ **回落 raw URI**（不是「放行」）⇒ 熔断不消失；⚠️ 隔离 key 仍是 `METHOD://path`（resilience4j `CircuitBreakerRegistry`）⇒ **路由模板解析不能省**这条前提已被载体兑现 |
| `common/redis/GoStyleRedisClientAutoConfiguration.java` | `common/src/main/java/com/rag/common/redis/ClientAutoConfiguration.java` | 1:1 | ✅ **已落地（2026-11-01）**：C 侧新建 **`common/redis/`**（= `common/` 第 **12** 个包）⇒ 断连行为收口（`ClientOptions.DisconnectedBehavior`，对齐 **B 侧** fail-open 语义）；⚠️ **去 `Redis` 前缀**（包名已含 `redis`，类名再带前缀即冗余）；⚠️ RAG 已把域内 5 份 `RedisDisconnectedBehaviorConfig` 收进本类（**B 侧旧类名已不存在**） |
| `common/redis/GoStyleRedisDeadline.java` ＋ `common/redis/GoStyleRedisDeadlineAutoConfiguration.java` ＋ `common/redis/GoStyleRedisDeadlineConnection.java` ＋ `common/redis/GoStyleRedisDeadlineConnectionFactory.java` | `common/src/main/java/com/rag/common/redis/Deadline.java` ＋ `common/src/main/java/com/rag/common/redis/DeadlineAutoConfiguration.java` ＋ `common/src/main/java/com/rag/common/redis/DeadlineConnection.java` ＋ `common/src/main/java/com/rag/common/redis/DeadlineConnectionFactory.java` | 1:1 | ✅ **已落地（2026-11-01）**：4 个类落 `common/redis/`（**去 `Redis` 前缀**）；请求预算耗尽 ⇒ 不再下发 Redis 命令；⚠️ 与 `common/persistence/DeadlineQueryTimeoutInjector.java` **语义相邻但不同**（一个拦 SQL，一个拦 Redis 命令）⇒ 两个载体都要在 |
| `common/spring/HttpPoolLifecycle.java` | **无对应文件** | 1:1 | ⚠️ **D4 生态吸收**：C 侧 Tomcat 自管连接池 ⇒ **零 Java 代码**（B 侧该类只为显式关闭钩子 ⇒ 语义等价） |
| `common/web/ConditionalOnApiSide.java` | **无对应文件** | 1:1 | ⚠️ **D1 合并 api/rpc** ⇒ C 侧**不存在「api 侧」这个维度** ⇒ 条件注解无对应物（**不是缺口**） |

### 1.13 §1 决策锁

**换 starter 装配形态（D3 · 19 处同时改）**：
1. 删 10 个 rpc 模块入口类上的 `@Import({...})` 清单（`LikeRpcApplication` / `CommentRpcApplication` / `FollowRpcApplication` / `MessageRpcApplication` / `FavoriteRpcApplication` / `AdminRpcApplication` / `UserRpcApplication` / `TaskRpcApplication` / `HotRpcApplication` / `ArticleRpcApplication`）；
2. 删 9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration`（`like-rpc` / `comment-rpc` / `follow-rpc` / `message-rpc` / `favorite-rpc` / `admin-rpc` / `user-rpc` / `task-rpc` / `article-rpc`），收编进 `MybatisPlusOrderingBridgeAutoConfiguration`；
3. `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 写全 7 行；
4. 7 个 `@AutoConfiguration` 全部加 `@ConditionalOnMissingBean`；
5. `common/pom.xml` 加 `spring-boot-autoconfigure` + `spring-boot-starter-web` + `spring-boot-starter-actuator` + `micrometer-registry-prometheus` + `spring-boot-starter-data-redis` + `spring-boot-kafka` + `mybatis-plus-spring-boot3-starter` + `resilience4j-spring-boot3` + `grpc-stub` + `jjwt-api/impl/jackson`；
6. 消费方 `pom.xml` 依赖 `common` 时**不得**再出现 `@Import`（D3 的连带效应：两者不能并存）；
7. 守门脚本加断言「A5：每个模块的 `@Import` 清单为空」+「A6：MP bridge 全仓只有一份」。

**为什么换**：Spring 生态里共享能力层的标准形态是 starter（`AutoConfiguration.imports`），不是「普通 jar + 每个消费者手写 `@Import` 清单」。
RAG 形态实测 10 种 `@Import` 写法、9 份 MP bridge 副本 —— 这就是「普通 jar 放不下 auto-config」的直接账单。

**换生态保护层（D4 · 15 处同时改）**：
1. 删 `protection/` 的 15 个手写类（保留 2 个：`CpuThresholdHealthIndicator` + `MaxBytesFilter`）；
2. `ProtectionAutoConfiguration` 引入 resilience4j `CircuitBreakerRegistry`，按 `METHOD://path` 建实例；
3. 熔断失败判据按 **B 侧**的「深查 + 6 码白名单」重写为 resilience4j 的 `ignoreExceptions` / `recordException`；
4. `MaxConns` 从 `GoStyleMaxConnsFilter` 改为 Tomcat `server.tomcat.max-connections`；
5. `MaxBytes` 保留 1 个 `OncePerRequestFilter`；
6. CPU 降载保留 1 个 `CpuThresholdHealthIndicator`（读 `/proc/stat` 千分比，**不可用时 fail-open**）；
7. gRPC server 侧的 Breaker / Shedding 拦截器在 `ProtectionAutoConfiguration` 里装配，并**断言真的进了 `server.intercept`**；
8. gRPC client 侧的 Breaker 拦截器必须同时带 `@GrpcGlobalClientInterceptor` + `@Component`；
9. 把 D-02 / D-04 登记进 [`L3-降级清单.md`](../L3-降级清单.md)；
10. 对拍脚本**显式排除**熔断策略差异，否则全红 ⇒ 会被误判为回归 ⇒ 触发「修回」⇒ 降级被静默撤销。

**为什么换**：上游的熔断 / 降载 / 限连接是**框架内置**（白送），B 侧手写复刻是「补框架差距」的成本，不是「翻译成本」；
能交给生态的都交出去（D4），**生态无等价物的只有 2 处**（G-1 CPU 降载、G-4 `call_chain`）⇒ 自研 2 类。

**换 communityfact 类型粒度（1:N · 13 处同时改）**：
1. `Wire.java` 里的 4 个嵌套 wire 类型拆成 `Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` 4 个文件；
2. `DeliveryStatus` 常量块拆成独立枚举文件；
3. `CanonicalHash` / `ValidHash` 拆成独立类（**JCS 依赖必须显式**）；
4. `Sender` 接口 + `DCEventSender` 拆成 `EventSender`；
5. `Dispatcher` 接口（域侧 SPI）留在 `Dispatcher.java`；
6. `RunDispatcher` 循环拆到 `EventDispatcher.java`；
7. `ProcessMetadata` 从 `ProcessLogger` 里拆出；
8. `Wire.java` 保留为门面（`StrictDecode` / `ValidateReceipt` / 2 个 sentinel）；
9. 4 个 wire 类型的 **JSON tag 逐个核对**（L1）；
10. `CanonicalHash` 的规范化顺序**先规范化再哈希**；
11. `ParseRTWSubject` 的字面量规范化校验保留；
12. `Event.payload` 在 **B 侧是 `Object`**（`@JsonProperty`）⇒ C 侧**逐字输出**（`DRIFT-CF-004` 未决）；
13. `communityfact` 的消费者接线**不在本层**（各域表）。

**为什么换**：B 侧把 4 个 L1 wire 类型 + 校验 + sender 全塞进一个 `Wire.java`（嵌套静态类）；拆成 1 类型 1 文件后，
「哪个类型是 L1 wire 契约」一眼可见，域侧接线时不会漏掉字段号 / tag。

**拆 2 个跨批硬依赖载体（2 处 · 2026-11-02 批 1 落地 · L-15 / L-16）**：
1. `ProtectionProperties` 从 `ProtectionAutoConfiguration` 里**独立成类**（`protection/ProtectionProperties.java`）
   —— 因为 `redis/DeadlineAutoConfiguration` 在批 1 就要读它（**跨批硬依赖** ⇒ 不能等批 3）；
2. `DeadlineState` 从 RAG `RpcInterceptor` 的**嵌套类提升为顶层类**（`observability/DeadlineState.java`）
   —— 188 行**零外部依赖**（纯 `ThreadLocal`）⇒ 可独立编译；`persistence/DeadlineQueryTimeoutInjector` 硬依赖它。

**为什么拆**：批 1 的边界是「纯抄 + 1:1/N:1 落点」，而这两处若按原样留在批 3 的 auto-config / RAG 的嵌套类里，
**批 1 根本编译不过** ⇒ 「先抄后改」的原则在此让位于「可编译」⇒ 拆的都是**载体位置**，不改任何行为。

**换 Jackson 3 → 2 ＋ Spring 7 → 6.2（5 类 / 11 处 API · 2026-11-02 批 2 落地 · L-17）**：
1. `web/Coercion`：**回改量 = 0** —— Jackson 2.19.4 的 `withCoercionConfig(LogicalType, Consumer<MutableCoercionConfig>)`
   ＋ `MutableCoercionConfig.setCoercion(shape, action)` ＋ `CoercionInputShape` ＋ `LogicalType` ＋ `CoercionAction`
   **与 Jackson 3 逐项同构** ⇒ 只换包名；
2. `web/Float64Deserializer` / `web/RawStringListDeserializer` / `web/MapStringValueDeserializer`：
   包名回改 ＋ **补 `throws IOException`**（Jackson 2 的 `JsonDeserializer#deserialize` 声明 checked 异常）
   ＋ `MapStringValueDeserializer` 的**基类换掉**（Jackson 2 **没有** `ValueDeserializer` 这个类 ⇒ 用 `JsonDeserializer`）；
3. `web/JsonBodyConverter`：**5 处回改** —— 基类（`JacksonJsonHttpMessageConverter` → `AbstractJackson2HttpMessageConverter`）
   ＋ `read` 签名（`read(ResolvableType, HttpInputMessage, Map)` → `read(Type, Class<?>, HttpInputMessage)`）
   ＋ mapper 类型（`JsonMapper` → **`ObjectMapper`**，理由见类 javadoc）
   ＋ **`canRead` / `canWrite` 覆写点**（Spring 7 的 `ResolvableType` 版 → Spring 6.2 的 `Type` 版）
   ＋ 包名。

**为什么换**：D14 全项目 = Jackson 2 + Boot 3.5 ⇒ RAG 侧（Boot 4.0.8）的 Jackson 3 / Spring 7 写法**不可直用**。

**⚠️ 其中 1 处是「覆写点漂移」—— 照抄 RAG 的方法签名会静默失效**：
Spring 6.2 的 `AbstractJackson2HttpMessageConverter` **不实现 `SmartHttpMessageConverter`**（只实现 `GenericHttpMessageConverter`）
⇒ Spring 的转换器选择分支顺序（`javap -c` 实测）是 **Generic → Smart → PLAIN**
⇒ ⇒ RAG 覆写的 `canWrite(ResolvableType, Class, MediaType)` / `canRead(ResolvableType, MediaType)` 在 Spring 6.2 下**根本不存在**
⇒ ⇒ 必须改成 **Type 版三参**（`canWrite(Type, Class<?>, MediaType)` / `canRead(Type, Class<?>, MediaType)`）
⇒ ⇒ **语义等价**（Spring 6.2 的分支顺序保证 Generic 版就是被调用的那一版）。
⚠️ 若照抄 RAG 的签名 ⇒ **编译期就红**（`方法不会覆盖或实现超类型的方法`）⇒ 这条**不会静默**。

---

## 2. 持久层差异

> **本层的持久面很窄**：`common/` 本身**零 ORM 模型、零 SQL、零事务**。
> 它只提供 4 类**跨域复用的持久层配件**（超时注入 + 状态翻译 + 2 个 TypeHandler）+ 1 个 MP 自动配置。
>
> 📌 **2026-11-02 · D16**：原表还有一列上游形态，随第三列一并移除 ⇒ 改写为 **B/C 两列**。
> 上游形态中**仍有语义价值**的部分（默认值 / 列类型 / 超时来源）已**并入 B 列括号**，不随删列丢失。

| 维度 | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|
| **ORM** | MyBatis-Plus `BaseMapper` + `@TableName`（上游是 GORM `AutoMigrate` 建表） | MyBatis-Plus `BaseMapper` + `@TableName` | 表达层差异，L2 语义（查询条件 / 唯一约束 / 读改写语义）逐条保留 |
| **建表机制** | `*SchemaInitializer` 幂等重放（**Flyway 已弃用**；上游是进程内 `AutoMigrate` 幂等重放） | `wiring/*SchemaGate.java` / `*SchemaBootstrap.java` 幂等重放；`resources/db/migration/` **只有 `.gitkeep`（无 Flyway 文件）** | ⚠️ C 侧**无 Flyway** ⇒ 不要再按「Flyway vs SchemaInitializer」写决策 |
| **MP 自动配置** | 9 个 rpc 模块各一份 `MybatisPlusOrderingBridgeAutoConfiguration`（Boot 4 排序桥） | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java`（**1 份**） | 断言 A6 |
| **JSON 列 typeHandler** | `JsonbTypeHandler` × 5 份 + `JsonTextTypeHandler` × 2 份（上游是 GORM 直接读写 `jsonb` / `json`） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` + `common/src/main/java/com/rag/common/persistence/JsonTextTypeHandler.java`（**各 1 份**） | ⚠️ RAG-V1 侧 `common/src/main/java/com/rag/common/persistence/` 的 2 个 TypeHandler 是**归位**，不是新增能力 |
| **SQL 超时** | `DeadlineQueryTimeoutInjector`（`StatementHandler#prepare` 拦截）+ `SqlTimeoutStatusTranslator`（`Executor` 拦截）（上游靠 ctx 传播，**天然**超时） | 同 B（1:1 保留） | ⚠️ **K-03**：Java 不能取消阻塞 IO ⇒ 必须走 pgjdbc `CancelRequest`；且 `catch(SQLException)` **匹配不上** MyBatis 抛的 `InvocationTargetException` |
| **事务边界 / 行锁 / 幂等键** | 逐条保留 | 逐条保留（**K-05，不属本层**） | 本层**零 `@Transactional`** ⇒ 无 self-invocation 风险 |
| **雪花 ID** | `Snowflake` + `SnowflakeBean` + `SnowflakeProperties`（**静态轨 + Bean 轨双路径**；41/10/12，epoch `1288834974657`） | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `SnowflakeAutoConfiguration` | ⚠️ **全进程只有一份生成状态**（B 侧 `bindBean` 静态委托有 ID 双计数器风险）；⚠️ **C 侧另有 3 个域级 `SnowflakeBootstrap`** ⇒ 落点重复（§7.4 #4） |

### 2.1 §2 决策锁

**换 typeHandler 归属（7 处同时改）**：
1. 删 `like-rpc/mapper/JsonbTypeHandler.java`；
2. 删 `comment-rpc/mapper/JsonbTypeHandler.java`；
3. 删 `favorite-rpc/mapper/JsonbTypeHandler.java`；
4. 删 `admin-rpc/mapper/JsonbTypeHandler.java`；
5. 删 `article-rpc/mapper/JsonbTypeHandler.java`；
6. 删 `user-rpc/mapper/JsonTextTypeHandler.java` + `admin-rpc/mapper/JsonTextTypeHandler.java`（合计 2 个文件，合计 7 个文件）；
7. 各域 `Mapper` 的 `@TableField(typeHandler = ...)` 改为引用 `com.rag.common.persistence.*`（**包名变了 ⇒ import 要全改**）。

**为什么换**：同一份 typeHandler 有 7 个副本 ⇒ 任何一处修 bug 都会漏另外 6 处（这正是 RAG 的 `DRIFT-010` 类问题的来源：
「同一规则的第二份实现本身就是缺陷来源」）。

**换雪花 ID 装配（4 处同时改）**：
1. `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` 声明唯一的 `Snowflake` Bean；
2. 删 3 个域级 `wiring/SnowflakeBootstrap.java`（`article` / `user` / `admin`），改由 starter 提供；
3. `worker-id` 配置从各域 `application.yml` 移到 starter 的配置前缀；
4. 加断言「容器内 `Snowflake` **有且仅有 1 个**」（双 Bean ⇒ ID 双计数器 ⇒ 撞号）。

**为什么换**：雪花 ID 的生成状态必须**全进程唯一**；B 侧的「静态轨 + Bean 轨」和 C 侧「starter + 域级 wiring」
都是两份落点 ⇒ 一旦同时生效就会出现两个独立计数器 ⇒ **ID 重复**。

---

## 3. HTTP / DI 差异

### 3.1 请求保护链（recover → breaker → shedding → 业务观测）

> 📌 **2026-11-02 · D16**：原表还有一列上游形态，随第三列一并移除 ⇒ 改写为 **B/C 两列**。
> 上游形态中**仍有语义价值**的部分（默认值 / 判据 / 链序）已**并入 B 列括号或备注**，不随删列丢失。

| 维度 | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|
| **HTTP 中间件形态** | `jakarta.servlet.Filter`（`GoStyleBreakerFilter` / `GoStyleCpuSheddingFilter` / `GoStyleMaxBytesFilter` / `GoStyleMaxConnsFilter`） | resilience4j + Tomcat + `MaxBytesFilter` + Actuator 健康检查 | **链序是行为语义**：B 侧的 `Middlewares` 顺序（Trace → Recover → Breaker → Shedding → Prom → Timeout …）必须逐条对拍（G-3） |
| **熔断** | `GoStyleBreaker`（逐行复刻上游内置熔断：window 10s / buckets 40 / k 1.5） | resilience4j `CircuitBreaker` | ⚠️ **D-02 L3 降级**（P2 待拍板） |
| **CPU 降载** | `GoStyleAdaptiveShedder` + `GoStyleCpuReader`（判据是 **CPU 过载 ＋ 在途超动态上限** 两个条件） | `AdaptiveShedder`（**双条件**，gRPC 侧 + **REST 侧**，批次 H 起）+ `CpuThresholdHealthIndicator`（CPU 读数 + Actuator 健康面） | ✅ **批次 H 起两侧判据一致**（REST filter 也走 `AdaptiveShedder`）⇒ 不再「单看 CPU 会误丢请求」；⚠️ 残留：**HTTP 侧的可观察面**（每分钟统计行 / `[http] dropped` 行）仍缺 ⇒ **B 侧原生** ⇒ **D-08** |
| **限连接** | `GoStyleMaxConnsFilter`（上游默认 10000；**B 侧是整进程共享一个闸门**） | `common/src/main/java/com/rag/common/protection/MaxConnsFilter.java`（批次 E 起自研闸门；**不改 yml**，L-5 冻结） | ⚠️ `DRIFT-HOTAPI-030`（总参数个数 vs 非空值个数）**仍未决** |
| **限体积** | `GoStyleMaxBytesFilter`（上游默认 1048576） | `common/src/main/java/com/rag/common/protection/MaxBytesFilter.java` | 413 空 body |
| **超时** | `HttpMiddleware`（goroutine + select）+ `GoStyleRequestDeadline`（ThreadLocal） | `GoStyleRequestDeadline` + `DeadlineQueryTimeoutInjector` | ⚠️ **K-03**；⚠️ **499（客户端取消）/ 503（服务端超时）** 两分支，**B 侧漏 499 写回** |
| **Recover** | `GoStyleRecoverInterceptor`（gRPC 侧） | `ObservabilityAutoConfiguration` 装配 | ⚠️ grpc-java 的 `interceptCall` **不在 handler 执行栈上** ⇒ 回调是清理路径不是捕获路径 |
| **业务观测** | `HttpMiddleware` + `Observability` + `ObservabilityConstants`（上游是 `Mark*` ＋ 全 buffer writer） | Micrometer Observation + OTel bridge（`ObservabilityCustomizer`） | ⚠️ **D-03 L3 降级**；⚠️ 判定顺序「envelope 业务码 → status≥400 → 慢调用」**业务码优先** |

### 3.2 拦截器 / 错误响应 / 配置绑定 / starter 装配

| 维度 | B (RAG) | C (RAG-V1) | 备注 |
|---|---|---|---|
| **错误响应形状** | `GoStyleRouterErrorResponses` + `GoStylePlainTextErrors`（上游是 `httpx.Error` ⇒ **400 + text/plain**） | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` + `common/src/main/java/com/rag/common/web/PlainTextErrors.java` | ⚠️ D-01 只降级**文案**，**状态码与结构不受影响**；⚠️ 真 Tomcat 下 400 的 `Content-Type` / `nosniff` **MockMvc 测不到**（R4/R5） |
| **错误表达模型** | `BusinessException(code,msg)` + `AuthenticationException`（上游是 `(resp, error)` 双返回 ＋ `status.Error`） | 同 B；边界由 `GrpcExceptionMapperAutoConfiguration` + 各域 `@RestControllerAdvice` 承担 | ⚠️ D2/D5：合并后**一份业务逻辑、两个边界适配器**；⚠️ `like` 域的 rpc 侧错误码空间是 like 自己的（**业务码塞进 gRPC status code 字段**），必须原样保留 |
|**鉴权**|各域 `interceptor/JwtAuthInterceptor` + `common/security/BearerTokenExtractor`（上游是 `rest.WithJwt` + `CheckBlacklistMiddleware`，**两套不同的 token 取法**）|`common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` + 各域 interceptor|⚠️ **「先验签、后黑名单」的顺序是行为契约**；⚠️ **B 侧的前缀比较是「先 `toUpperCase` 再与 `"BEARER "` 比对」**|
| **配置绑定** | `@ConfigurationProperties`（41 个文件）+ `@Value`（上游是 yaml + struct tag，**默认值藏在 tag 里**） | `@ConfigurationProperties`（`ObservabilityProperties` 等）+ auto-config 的 `@EnableConfigurationProperties` | ⚠️ §3.3 纪律：「配置里没写」≠「没开」；判定必须读到**消费点** |
| **装配机制** | **10 份 `@Import` 清单**（4~10 Bean 不等；上游是手工 `new` + `ServiceContext`） | **`AutoConfiguration.imports`（14 行）** | ⚠️ **`@Import` 与 auto-config 不能并存**（D3 连带效应） |
| **启动期副作用** | `spring/LoggerFactoryBean` + `spring/ObservabilityLifecycle` + `spring/SnowflakeBean` | `SnowflakeAutoConfiguration` + `ObservabilityAutoConfiguration` | ⚠️ **B 侧 api/rpc 分离 ⇒ 一个域有多个进程级 logger 身份**（业务 RPC / fact-dispatch / fact-authority）⇒ D1 合并成 1 JVM 后**必须保住多份独立 logger 名与 appender** |
| **Servlet 注册** | `ServletRegistrationBean`（`AuthorityHandlerFactory`） | `WebAutoConfiguration` | ⚠️ P6-b：独立端口是否保留**待拍板** |

### 3.3 §3 决策锁

**换 DI 装配（`@Import` → auto-config · 19 处同时改）**：**同 §1.12 第一条**，此处不重复列举
（10 份 `@Import` 清单 + 9 份重复的 `MybatisPlusOrderingBridgeAutoConfiguration`）。

**换统一错误模型（D2/D5 · 6 处同时改）**：
1. 各域业务层统一抛 `BusinessException(code,msg)`；
2. gRPC 边界统一由 `GrpcExceptionMapperAutoConfiguration` 装配的 mapper 转 `StatusRuntimeException.withCode(code)`；
3. HTTP 边界统一由各域 `@RestControllerAdvice` 读 `BusinessException.code`；
4. `web/GrpcStatusMapper.java` 与 `GrpcExceptionMapperAutoConfiguration` **只留一处**（见 §7.4 #2）；
5. `like` 域的「业务码塞进 gRPC status code 字段」逐字保留（**不是 gRPC 标准码**）；
6. `AuthenticationException` ⇒ 401；`BusinessException` ⇒ 200 + `{code,msg}`（按 **B 侧**的形状）。

**为什么换**：**B 侧**同一份业务错误在 api / rpc 两处各写一遍（api 侧还要把 gRPC code 映射回业务码）；
合并成一个进程后只剩一份逻辑 ⇒ 两处映射必然变成一处。

**换观测链路（Micrometer Observation · 4 处同时改）**：
1. 删 `observability/HttpMiddleware.java` 的自研 span 写入，改由 `ObservationRegistry` 统一；
2. `ObservabilityCustomizer` 承载 `Mark*` 的**业务语义**（`sea.slow` / `sea.biz_code` / `sea.error.kind`）；
3. gRPC server / client / MQ consumer 三条链各注册一个 Observation；
4. D-03 登记进 `L3-降级清单.md`；**指标名与标签是业务定义，不降级**。

**为什么换**：上游的 `zrpc` 内置了一条完整的 Trace → Recover → Stat → Prometheus → Breaker → Shedding → Timeout 链；
Micrometer 的观察模型与它**不是一对一映射**（D-03）⇒ 用生态拿标准化，但**业务定义的标记必须自己补**。

---

## 4. 公共模块对照

### 4.1 共享层的边界：什么归 `common/`，什么归 `<域>-common`

> 📌 **2026-11-02 · D16**：原表还有一列上游归属，随第三列一并移除 ⇒ 改写为 **B/C 两列**。

| 能力 | B（RAG）归属 | C（RAG-V1）归属 | 判定理由 |
|---|---|---|---|
| **社区事实事件协议**（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact`） | `service/common/communityfact/`（**comment + like + favorite 三域消费**，2026-11-02 实测） | `common/src/main/java/com/rag/common/communityfact/` | 跨服务 L1 wire 契约 ⇒ 全局 |
| **投递编排**（`RunDispatcher` 循环 / authority HTTP 端点） | `service/common/communityfact/` | `common/src/main/java/com/rag/common/communityfact/` | 纯库（**B 侧由各域 rpc 模块的调度器 / authority 端点类调用**）⇒ 全局 |
| **各域的 outbox 模型 / 投递调度器 / authority 端点装配** | 各域 `rpc/`（`fact` 包 + `service` 包 + `bootstrap` 包） | 各域 `wiring/` + `service/` | **有域归属** ⇒ 归域，不归 `common/` |
| **业务日志门面** | `service/common/logger/` | `common/src/main/java/com/rag/common/logger/` | 全部 10 域共用 |
| **响应信封** | `service/common/response/` | `common/src/main/java/com/rag/common/contract/` | L1 |
| **雪花 ID** | `service/common/snowflake/` | `common/src/main/java/com/rag/common/snowflake/` | 全域共用 |
| **JWT / 口令** | `service/users/users-common/`（`jwt/` + `cryptx/`） | `common/src/main/java/com/rag/common/security/`（**提升为全局**） | 鉴权是安全边界 ⇒ 第二份实现即缺陷来源 |
| **错误码**（`ErrorCode`） | `users/common/errmsg/` + 各域 `exception/` | **各域模块内的 `contract/` 包**（`common/` 里**没有**） | ⚠️ **错误码是逐域隔离的**（RAG-V1 AGENTS.md §1.1b）⇒ **不能进全局 `common/`** |
| **HTTP 解析 / 错误形状** | 散在 `service/common/http/` + 各域 api | `common/src/main/java/com/rag/common/web/` | 无域归属的 HTTP 语义 ⇒ 全局 |
| **请求保护（熔断 / 降载 / 限连接 / 限体积）** | `service/common/protection/` | `common/src/main/java/com/rag/common/protection/` + starter | 无域归属 ⇒ 全局 |
| **DDL / schema 建表** | 各域 `bootstrap/*SchemaInitializer` | 各域 `wiring/*SchemaGate` | **有域归属** ⇒ 归域 |
| **业务实体 / Mapper / 指标** | 各域 `rpc/{domain,mapper,metrics}` | 各域 `rpc/{domain,mapper,metrics}` | 有域归属 |

> ⚠️ **RAG-V1 AGENTS.md §1.4 R1 硬规则**：`common/` 的准入 = **只放跨域共用且无域归属的能力**；每个子包要标归属域；
> 改 `common/` 必须**单写**并登记影响面。
> ⇒ RAG 侧 `service/common` 被 16 个组件依赖 ⇒ 凡修法落在这里的条目**自动从并发降级为串行**。

### 4.2 `communityfact` 的三域归属（专节）

> 📌 **2026-11-02 · D16**：原表还有一列上游形态，随第三列一并移除 ⇒ 改写为 **B/C 两列**；
> B 列的引用数**按 2026-11-02 `grep` 实测重算**（口径 = `import com.rag.common.communityfact.*` 的**主代码**文件数）。

| 域 | B（RAG）侧 | RAG-V1 侧 | 本表覆盖 |
|---|---|---|---|
| `comment` | ✅ **4** 个主代码文件 import 共享层（`Dispatcher` / `Wire` / `AuthorityHandler`） | 域内 `wiring/` + `service/` | **共享层本体** ✅ / 域内接线 ➡️ `comment.md` |
| `like` | ✅ **4** 个主代码文件 import 共享层（`Dispatcher` / `Wire`；另 1 个仅 javadoc 提及） | 域内 `wiring/` + `service/` | **共享层本体** ✅ / 域内接线 ➡️ `like.md` |
| `favorite` | ✅ **10** 个主代码文件 import 共享层（`Dispatcher` / `Wire` / `ProcessLogger`） | 域内 `wiring/` + `service/` | **共享层本体** ✅ / 域内接线 ➡️ `favorite.md` |

> ⚠️ **本表只写共享层本体**（`common/src/main/java/com/rag/common/communityfact/` 的 13 个类）；**各域的接线**（投递调度器、authority 端点、outbox 模型、
> 多份独立 logger 身份）在 `comment.md` / `favorite.md` / `like.md` 里，**本表不重复**。
>
> ⚠️ **「三域共享」在 B 侧已成立**（2026-11-02 实测：comment 4 / like 4 / favorite 10 个主代码文件 import 共享层；`article` 域 **0** import，仅 javadoc 提及）
> ⇒ **C 侧的「三域共享」与 B 侧一致**。
> ⚠️ 旧口径「favorite 是平行实现、只有两域消费」是**上游形态**的事实，已被 B 侧自身的演进取代 ⇒ 原「是否把 favorite 收敛到共享层」的待拍板项**前提已变**（见 §7.4 #1）。

### 4.3 §4 决策锁

> 📌 **时点标注（2026-10-26 · D7）** —— 本小节原文的落点是「各域 `<域>-contract/` **子模块**」
> （锁 1 与锁 10）。**D7 删除了 10 个 `*-contract` 子模块** ⇒ 同一批类现在落在
> **域模块内的 `contract/` 包**（`service/<域>/src/main/java/com/rag/<域>/contract/`）。
> ⇒ **锁的意图（错误码逐域隔离）未变**，只是模块归属换了一层。

**换错误码归属（10 处同时改）**：
1. `service/<域>/<域>-contract/` 放该域的错误码契约（`ErrorCode` / 错误文案 / 语言）；
2. `common/` **不放** `ErrorCode`（⚠️ 与设计文档 §5.1 的 `contract/ErrorCode.java` 冲突，以**实际树**为准）；
3. 各域 `rpc/exception/*Exceptions.java` 只做该域的异常工厂；
4. 错误文案**逐域隔离**（不得统一）⇒ 对拍脚本必须**按域**取错误码表；
5. `like` 域的 rpc 侧错误码空间单独保留；
6. `message` / `article` / `task` / `hot` / `follow` 各自的错误码表各自独立；
7. `comment` / `favorite` / `like` 三域共享的**事件协议错误码**（`DELIVERY_ATTEMPT_FAILED` / `INVALID_FROZEN_ENVELOPE` / `SOURCE_READ_FAILED` / `RESPONSE_WRITE_FAILED` / `AUTHORITY_UNAVAILABLE`）**留在 `common/src/main/java/com/rag/common/communityfact/`**；
8. `users` 域双子域（user / admin）的错误码**分开**（admin 的码空间不与 user 混）；
9. `global` 收口域的跨域不变量登记在 `docs/DRIFT/global/`；
10. 对拍脚本的错误码来源从 `common/` 改为各域 `*-contract/`。

**为什么换**：错误码是 **L1 外部契约**且**逐域隔离** ⇒ 放进全局 `common/` 会被误当成「统一错误码空间」，
进而诱发「统一文案」的破坏性改动。

**换 JWT / 口令归属（4 处同时改）**：
1. `common/src/main/java/com/rag/common/security/JwtUtil.java` 提供签发 + 验签；
2. `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` 保留**两套** token 取法；
3. `common/src/main/java/com/rag/common/security/PasswordUtil.java` 保留「不匹配 = 非法 hash = false」的压平语义；
4. 删 `service/users/users-common/{jwt,cryptx}/`（3 个文件）⇒ 各域改为依赖 `common`。

**为什么换**：鉴权与口令是**安全边界**；同一规则的两份实现本身就是缺陷来源（RAG 的 `DRIFT-010` 就是这么来的）。

---

## 5. 决策锁（总表）

> 本节把 §1.12 / §2.1 / §3.3 / §4.3 的锁集中列一遍 —— **复刻到下一个域时按这张清单改，不漏**。

| # | 换什么 | N 处同时改 | 为什么换 | 关联决策 / 降级项 |
|---|---|---|---|---|
| L-1 | **共享层 → starter 装配** | **19**（10 份 `@Import` 清单 + 9 份 MP bridge 副本） | Spring 生态里共享能力层的标准形态是 starter，不是「普通 jar + 手写清单」 | **D3** · 断言 A5/A6 |
| L-2 | **手写保护层 → 生态** | **15**（`protection/` 24 类 → 2 类） | go-zero 内置能力是白送的；生态无等价物只有 2 处（G-1 / G-4） | **D4** · D-02 / D-04 · P2 |
| L-3 | **日志 → SLF4J 适配器** | **3**（`Logger`→`GoStyleLogger`、`LoggerFactoryBean` 合并、`With*` 合并） | 格式（`call_chain`）是行为契约，但**要接进 SLF4J 生态** ⇒ 自研 `implements org.slf4j.Logger` | **D4** · K-04 · G-4 · 断言 A8 |
| L-4 | **观测 → Micrometer Observation** | **4** | Micrometer 的观察模型与 go-zero 的中间件链不是一对一映射 | **D4** · **D-03** |
| L-5 | **限连接 → Tomcat 原生** | **2**（删 filter + 改 yml） | `MaxConns` 是 Tomcat 原生项 ⇒ 零 Java 代码 | **D4** · `DRIFT-HOTAPI-030` 仍未决 |
| L-6 | **CPU 降载 → 自研 1 类** | **1**（4 类 → 1 类） | resilience4j **无等价** ⇒ 不自研就没有这个能力 | **G-1** · **D-04** |
| L-7 | **communityfact 类型拆分** | **13** | 1 类型 1 文件后，「哪个是 L1 wire 契约」一眼可见 | — |
| L-8 | **TypeHandler 合流** | **7** | 7 份副本 ⇒ 修一处漏六处 | — |
| L-9 | **雪花 ID 单一落点** | **4** | 生成状态必须全进程唯一，否则 ID 重复 | — |
| L-10 | **统一错误模型** | **6** | 合并成一个进程后只剩一份业务逻辑 ⇒ 两处映射必然变成一处 | **D2 / D5** |
| L-11 | **错误码下沉到各域 `contract/` 包** | **10** | 错误码是 L1 且**逐域隔离** ⇒ 不能进全局 `common/` | §1.1b |
| L-12 | **JWT / 口令提为全局** | **4** | 鉴权是安全边界 ⇒ 第二份实现即缺陷来源 | — |
| L-13 | **HTTP 层归位到 `web/`** | **13**（从各域 api 归位） | **B 侧**语义等价物**必须手写**（「何时读 body」「错误形状」不是框架能力） | K-01 / K-02 |
| L-14 | **观测 / 保护 / 雪花 / Jackson / gRPC mapper 的 auto-config 化** | **7**（7 个 `@AutoConfiguration`） | 消费方零 `@Import`；`@ConditionalOnMissingBean` 让消费方可覆盖 | **D3** |
| L-15 | **`ProtectionProperties` 独立成类**（不再并入 auto-config） | **2**（B 侧 1 个类 → C 侧 1 个载体 + 1 个装配点） | 跨批硬依赖（`redis/DeadlineAutoConfiguration` 要它）＋ Spring 生态惯例（`@ConfigurationProperties` 类独立）＋ 纯配置载体不属 D4 生态吸收对象 | **2026-11-02 批 1 落地** |
| L-16 | **`DeadlineState` 从嵌套类提升为顶层类** | **1**（RAG `RpcInterceptor#DeadlineState` → 独立文件） | 188 行零外部依赖（纯 `ThreadLocal`）⇒ 可独立编译；`DeadlineQueryTimeoutInjector` 硬依赖它 ⇒ 不拆则批 1 不可编译 | **2026-11-02 批 1 落地** |
| L-17 | **Jackson 3 → 2 ＋ Spring 7 → 6.2 的机制层回改** | **5 类 / 11 处 API** | D14 全项目 = Jackson 2 + Boot 3.5 ⇒ RAG 侧的 Jackson 3 / Spring 7 写法**不可直用**；本条锁定「哪些 API 必须换、哪些**逐项同构不用换**、哪些**覆写点会漂**」 | **2026-11-02 批 2 落地** |
| L-18 | **批 3 的 D4 生态重写（11 类）**：starter 装配形态 + 5 处生态替换 | **11 类 / 8 处实测回改** | ① **7 个 `@AutoConfiguration` 由 `AutoConfiguration.imports` 注册**（消费方零 `@Import`，A5）⇒ **gRPC 拦截链的挂载点收进共享层 1 份**（A3）⇒ ② **熔断器由 resilience4j 承载**（D-02，隔离 key 与成败判据逐字对齐 **B 侧**）③ **观测由 Micrometer Observation 承载**（D-03，业务标记键名与判定顺序逐字保留）④ **CPU 降载自研 1 类**（D-04，生态无等价物）⑤ **gRPC 业务码走 trailer**（D-05，grpc-java 承载不了任意数值 status code） | **2026-11-02 批 3 落地** |

> ⚠️ **三条执行前提**：
> ① **L-2 / L-4 / L-6 未完成对拍前不得判为完成**（D-02 明写：「未做对拍前，不得把 P2 判为 ③ 完成」）。
> ② **降级项不得反向修回**；对拍脚本必须**显式排除**降级项（否则全红 ⇒ 被误判为回归 ⇒ 触发「修回」⇒ 降级被静默撤销）。
> ③ **`@Import` 与 auto-config 不能并存** ⇒ L-1 与 L-14 必须**同一次提交**走完（否则留下半截装配）。

---

## 6. 跨模块全局约定

> 这几项**所有域共享**，改任何一域都不许漂移。
> ⚠️ **本表只列共享层承载的项**；proto 字段号 / 错误码取值 / 表名 / HTTP path / Kafka topic 的**逐域清单在各域表**。

| 项 | 值 / 规则 | 承载位置 | 备注 |
|---|---|---|---|
| **proto 字段号 / message 名** | 一个字节都不改；**根级 `proto/` 模块只放契约不放实现**（D7 后 10 域共用一个 proto 模块） | `proto/src/main/proto/*.proto` + 各域 `contract/` 包（错误契约 / 事件 DTO） | L1；本层**不承载 proto** |
| **错误码** | **逐域隔离**：码值 / 文案 / 语言**不可统一** | 各域 `contract/` 包（`ErrCodes` / `ErrMsg`）+ 各域 `exception/` 包 | ⚠️ `common/` 里**没有** `ErrorCode`（设计文档 §5.1 与实际树冲突，见 §7.4 #3） |
| **JWT claims** | `userId`（`Long`）+ `exp` + `iat`；签名算法 **HS256 钉死** | `common/src/main/java/com/rag/common/security/JwtUtil.java` | ⚠️ jjwt 的 `Keys.hmacShaKeyFor` 会随密钥长度漂到 HS384/HS512 ⇒ 必须显式指定 |
| **Authorization 头解析** | **两套互不相同**的取法：验签用 `tokenForSignatureVerification`，黑名单 key 用 `trimBearerPrefix`；顺序「先验签、后黑名单」 | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | ⚠️ 合并即等于擅自修好 B 侧的 bug |
| **响应信封** | `{code, msg, data}` —— 字段名 / 顺序 / 无 `omitempty`（`data:null` 恒出现） | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | L1 · **K-06**（不可用 `ProblemDetail` 替换） |
| **HTTP 错误形状** | 400 + **text/plain**（含 `nosniff`） | `common/src/main/java/com/rag/common/web/RouterErrorResponses.java` + `common/src/main/java/com/rag/common/web/PlainTextErrors.java` | ⚠️ D-01 只降级**文案**；⚠️ 必须**真 Tomcat** 验（MockMvc 测不到） |
| **HTTP path** | `/<域>/v1/{op}` POST 风格 | 各域 `api/controller/` | 本层不承载 |
| **表名** | `<域>_<实体>` 显式 `@TableName` | 各域 `rpc/domain/` | 本层不承载 |
| **Kafka topic** | 逐域定义 | 各域 `mq/` | 本层不承载；⚠️ Boot 4 必须引 `spring-boot-kafka`（只引 `spring-kafka` ⇒ `@KafkaListener` **静默不消费**） |
| **指标名 / 标签** | 逐字保留（如 `message_api_request_total{route,result}`） | 各域 `rpc/metrics/` + `api/metrics/` | ⚠️ **指标名是业务定义，不属 D-03 降级范围** |
| **span 属性** | `sea.slow` / `sea.biz_code` / `sea.error.kind` 属**业务定义标记**，必须保留 | `common/src/main/java/com/rag/common/observability/ObservabilityCustomizer.java` | 其余 span 名/属性属 **D-03** 降级 |
| **communityfact wire 字段** | `Event` 9 字段 / `DeliveryStatus` 0-3 / `SubjectRef` v2（`issuer` + `subject_id`，**无 realm / tenant**） | `common/src/main/java/com/rag/common/communityfact/` | L1；`CanonicalHash` 走 **RFC 8785 JCS**（**G-2**） |
| **幂等键** | `Idempotency-Key = event.eventId` | `common/src/main/java/com/rag/common/communityfact/EventSender.java` | L1 |
| **雪花 ID 位段** | 41 + 10 + 12，epoch `1288834974657` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` | like 域游标编码依赖 `>>22` |
| **SQL 超时语义** | `setQueryTimeout`（**不是** `socketTimeout`）；`SQLState=57014` ⇒ `DEADLINE_EXCEEDED` | `common/src/main/java/com/rag/common/persistence/` | K-03 · RAG R4 |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏（104/104 = 100%）

自查命令：

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/common" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

| 子包 | 实际 `.java` 数 | 出现在本表 C 列的数 | 缺口 |
|---|---|---|---|
| `autoconfigure/` | 12 | 12 | 0 |
| `communityfact/` | 13 | 13 | 0 |
| `contract/` | 1 | 1 | 0 |
| `exception/` | 2 | 2 | 0 |
| `logger/` | 5 | 5 | 0 |
| `observability/` | 19 | 19 | 0 |
| `persistence/` | 4 | 4 | 0 |
| `protection/` | 18 | 18 | 0 |
| `redis/` | 5 | 5 | 0 |
| `security/` | 3 | 3 | 0 |
| `snowflake/` | 1 | 1 | 0 |
| `web/` | 21 | 21 | 0 |
| **合计** | **104** | **104** | **0** |

> ⚠️ **核对日期 2026-11-02（批次 F 后重算 · D16 删列后复核仍为 104/104）** —— 上一版自报 `72` 是**批 1 时的旧值**，批 2（+13）/ 批 3（+5）/ 批次 B（+5）/ 批次 D（+1）/ 批次 E（+8）/ **批次 F（+3）** 都没回写 ⇒ **自报数长期漂着**。⇒ ⇒ 与 P-08 同病：**没人复算的数**。现由 `tools/check-mapping-coverage.py` 从文件系统反向取实际清单对账。

> C 列逐条清单（按子包，C 侧**真实类名**）：
> `autoconfigure/`：ClientProtectionAutoConfiguration · GoStyleOtelAutoConfiguration · GrpcExceptionMapperAutoConfiguration · JsonBodyConverterAutoConfiguration ·
> LoggerAutoConfiguration · MybatisPlusOrderingBridgeAutoConfiguration · ObservabilityAutoConfiguration · PersistenceAutoConfiguration ·
> ProtectionAutoConfiguration · RouteTemplateAutoConfiguration · SnowflakeAutoConfiguration · WebAutoConfiguration
> `communityfact/`：AuthorityFact · AuthorityHandler · CanonicalHash · DeliveryStatus · Dispatcher · Event · EventDispatcher · EventSender ·
> ProcessLogger · ProcessMetadata · SubjectRef · TechnicalReceipt · Wire
> `contract/`：ApiResponse
> `exception/`：AuthenticationException · BusinessException
> `logger/`：FatalError · GoStyleLogger · LogOption · LogOptions · LoggerFactoryBean
> `observability/`：ClientDisconnectProbe · DeadlineState · GoStyleOtelAgent · GoStyleOtelConfig · GoStyleRequestDeadline · HttpMiddleware · MqConsumer ·
> ObservabilityCustomizer · ObservabilityLifecycle · ObservabilityProperties · ObservabilityResponseWriter · ObservabilityRuntime ·
> RpcCallHandler · RpcHandleRunner · RpcPrometheusInterceptor · RpcRecoverInterceptor · RpcResult · RpcStatInterceptor · RpcStatMetrics
> `persistence/`：DeadlineQueryTimeoutInjector · JsonTextTypeHandler · JsonbTypeHandler · SqlTimeoutStatusTranslator
> `protection/`：AdaptiveShedder · ClientCircuitGateInterceptor · CpuThresholdHealthIndicator · MaxBytesFilter · MaxConnsFilter ·
> OutboundDeadlineInterceptor · ProtectionProperties · RouteResolution · RouteTemplateResolver · RouteTemplateSource ·
> RpcCircuitGateInterceptor · RpcFailurePolicy · RpcLayer · RpcLayerChainNode · RpcProtectionChain · RpcSheddingInterceptor ·
> ServiceBusyException · SheddingStat
> `redis/`：ClientAutoConfiguration · Deadline · DeadlineAutoConfiguration · DeadlineConnection · DeadlineConnectionFactory
> `security/`：BearerTokenExtractor · JwtUtil · PasswordUtil
> `snowflake/`：Snowflake
> `web/`：BadRequestException · Coercion · Float64Deserializer · GetFormValuesFilter · GrpcStatusMapper · HttpErrorException · JsonBodyConverter ·
> JsonBodyConverterConfig · JsonBodyLimitFilter · JsonContentTypeAdvice · LimitExceededException · LimitMode · MapStringValueDeserializer ·
> PathNormalizingFilter · PlainTextErrors · RawStringListDeserializer · RouterErrorResponses · StringFields · StringInt64 · TraceparentFilter ·
> ValueLimitInterceptor

> 📌 **2026-11-02 · D16**：原 §7.2「上游列不遗漏职责」（**11 个上游源文件逐条对照表** + 「上游无对应物（原因）」清单）
> **随第三列一并移除** ⇒ 本节不再有第三列的自查；**B 侧的零遗漏改由 `tools/check-mapping-coverage.py` 的「B 列双向对账」机械保证**
> （当前：**精确 663 · 通配 3 · 目录 65 · 白名单命中 4 条 · 全局未覆盖 0**，2026-11-02 实测）。
> ⇒ 原 §7.3（映射类型分布）**重编号为 §7.2**。

### 7.2 映射类型分布

> **统计口径**：本表**§0 + §1.1 – §1.13** 的 **4 列**对照表（`B | C | 映射类型 | 备注`）里**填了映射类型的行** = **105 行**，全部落在 4 种合法词内。
> 统计脚本（**只数本节范围内的 4 列主表**，避开 §2 之后章节里的其它表）：
> ```bash
> python3 - <<'PY'
> from collections import Counter
> from pathlib import Path
> doc = Path('docs/Mapping_Table/common.md').read_text(encoding='utf-8')
> c = Counter()
> for ln in doc.split('\n'):
>     if ln.startswith('## 2. '): break            # 只数 §0 – §1.13
>     if not ln.startswith('|'): continue
>     cells = [x.strip() for x in ln.strip().strip('|').split('|')]
>     if len(cells) == 4:
>         t = cells[2].replace('`', '').replace('**', '').strip()
>         if t in ('1:1', '1:N', 'N:1', 'C 新增'):
>             c[t] += 1
> print(dict(c), '合计', sum(c.values()))
> PY
> ```
> ⚠️ **核对日期 2026-11-02（D16 删列后重算）**。
> ⚠️ **旧脚本在删列后失效，且范围开关本身是坏的** —— 旧脚本是 `awk -F'|' ... NF==7 {print $5}`（**5 列**口径）；删列后主表已变 4 列 ⇒ `NF==7` / `$5` **再也取不到类型列**（须改 `NF==6` / `$4`）；
> 且旧脚本的范围开关 `NR>=FNR{s=1}` 在**单文件**输入下**每行都把 `s` 重置为 1** ⇒ `/^## 2\. /{s=0}` 只对它**紧随的那一行**生效 ⇒ **实际扫的是全文**，把 §6 / §7.1 的 4 列小表也算了进来 ⇒ 现改为 **`break` 硬截断**。
> ⇒ 实测（**105 行 = 1:1 60 / 1:N 14 / N:1 30 / C 新增 1**）与本表逐行判定一致。
> ⚠️ **口径区分**：本表的「105 行」是 **§0 – §1.13** 的对照行；而 `_INDEX.md` 里 common 的「映射行」是**全文扫描**的口径（`tools/check-mapping-coverage.py` 的 `count_map_rows` = **135**，会把 §7.x 的小表也算进去）⇒ **两个数不同口径**，不要混用。
> ⚠️ **不要用 `awk -F'|'` 复算**：本文件里有 **3 个 NUL 字节**（§1.7 的 `\u0000` 字面量），`awk` 会把记录截断 ⇒ **少算 1 行**（§1.7 的 `JsonTextTypeHandler` 行）⇒ 以 Python 口径（与 `check-mapping-coverage.py` 的 `_split_row` 一致）为准。

| 映射类型 | 行数 | 主要集中在 |
|---|---|---|
| **1:1** | **60** | `web/` 15 个（12 个从各域 api 归位 + 3 个来自 `common/http`）、**§1.12 增量 14 个**（含批次 F 新增的 `GoStyleOtelAgent` / `GoStyleOtelConfig` / `GoStyleOtelAutoConfiguration`）、`logger/` 5 个、`communityfact/` 4 个、`observability/` 5 个、`protection/` 3 个、`security/` 3 个、§0 顶层 4 个、`exception/` 2 个、`persistence/` 2 个、`autoconfigure/` / `contract/` / `snowflake` 各 1 个 |
| **1:N** | **14** | 9 个在 `communityfact/` 的 wire 类型拆分（`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `DeliveryStatus` / `CanonicalHash` / `EventSender` / `EventDispatcher` / `ProcessMetadata`）+ §0 顶层 2 个 + `observability/` 2 个（`HttpMiddleware` 一行、`RpcInterceptor#handle` 一行）+ **§1.12 的「1 个 B 类 拆成 3 个 C 类」（`HttpMiddleware` 一行）** |
| **N:1** | **30** | `autoconfigure/` 8 个、`protection/` 5 个、§0 顶层 5 个、**§1.12 增量 4 个**（批次 F 推翻了旧口径的 1 行 N:1）、`observability/` 2 个、`logger/` 2 个、`persistence/` 2 个、`snowflake` 1 个、`web/` 1 个（`GoStyleStringInt64` 合 2 份） |
| **C 新增** | **1** | §0 顶层的 `autoconfigure/`（**B 侧只有 `@Import` 清单形态、无 auto-config 形态** ⇒ 记 `C 新增`；其余 11 个 auto-config 因 B 侧有对应落点故记 `N:1`） |
| **合计** | **105** | — |

> ⚠️ **分布读法**：`N:1` 偏多（30 行，占 29%）**不是缺陷** —— 它正是 D3（starter 化）与 D4（生态替代）的直接账单：
> **B 侧 88 类里，有相当一部分是「逐行复刻上游框架内置能力」的产物**（熔断 / 降载 / 限连接 / 限体积 / HTTP 解析 / 错误形状；
> 其中 `protection/` 一个包就占 24 类），RAG-V1 再交回生态或合流 ⇒ **C 列 104 类里有相当一部分是「形态」而非「能力」的重新分配**。
>
> ⚠️ **反过来说**：`1:N` 只有 14 行（**9 行在 `communityfact/`** ＋ 2 行在 §0 顶层 ＋ 2 行在 `observability/` ＋ 1 行在 §1.12）⇒ **本层的形态变化集中在「合流 + 生态替代」，不在「拆细」**。
> 这与《完全spring化的文件树》§6.1 的结论一致：`common` **反而变大**（B 88 → C 104，**C 侧比 B 多 16**），**收益不在文件数，在消除 `@Import` 清单与拿到生态红利**。
>
> ⚠️ **§1.12 带来的新事实**：B 侧 `service/common` 现有 **12 个包 / 88 个主代码类**，而 C 侧 `common/` 是 **12 个包 / 104 个类**（2026-11-02 `find` 实测）
> ⇒ **差异不是「C 侧漏建」，而是「C 侧有意改形」**（生态吸收 ＋ starter 化 ＋ 类型粒度拆分）；§1.12 原先标「无对应文件」的 9 行里 **7 行已于 2026-11-01 补齐**（`web` 5 类 + `observability` 1 类 + `protection` 2 类 + `redis` 5 类）⇒ **余 2 行是「有意不建」**（`HttpPoolLifecycle` = D4 生态吸收 · `ConditionalOnApiSide` = D1 合并 api/rpc）⇒ **本域已无未裁决的落点缺口**。

### 7.4 🔴 两列职责对不上的地方（8 条：**4 条待拍板 + 4 条已定死**）

> ⚠️ **2026-11-02 批次 H 订正**：**#6 的 B / C 两列实况都已过时**（B 侧现在 10 个 rpc 应用**全部**声明了 `GrpcServerConfigurer`；C 侧批次 B 已修）⇒ ⇒ 已就地订正 ⇒ ⇒ **DRIFT-COMMON-V08 随之关闭**。
> ⚠️ 本节标题里的「🔴 / 待拍板」只针对**仍未定死**的那几条 ⇒ **#1 / #6 / #7 / #8 已定死** ⇒ 别把本节的标记整段当成未决项。
> 📌 **2026-11-02 · D16**：本节原是**三列**（A/B/C）对照 ⇒ 改为**两列**（B/C）；**#1 的前提随之变化**（见下）。

> 这几条是本表**最需要人拍板**的部分 —— 它们不是文件缺漏，而是**两棵树对同一职责给了两个不同的落点**。
> ⚠️ **#7 / #8 已定**（批 1 落地，锁 **L-15 / L-16**）—— 留档是为了让后人知道「当初为什么这么落」。

| # | 不一致 | 两列实况 | 影响 | 建议 |
|---|---|---|---|---|
| **1** | ~~**`communityfact` 的「三域共享」在上游只有两域**~~ ⇒ ✅ **前提已变（D16 + 2026-11-02 实测）** | B：**2026-11-02 `grep` 实测** —— `import com.rag.common.communityfact.*` 的主代码文件数 = **comment 4 / like 4 / favorite 10**（`article` 0，仅 javadoc 提及）⇒ **B 侧已经是「三域共享」**（favorite 走共享层的 `Wire` / `Dispatcher` / `ProcessLogger`）。C：`common/src/main/java/com/rag/common/communityfact/` 按 comment / favorite / like 三域共享设计 | C 的三域共享**与 B 侧实况一致** ⇒ 原「收敛 favorite 会改变 wire 行为」的顾虑**不成立**（B 侧早已收敛）⇒ **不是 L1/L2 变更，是照抄 B** | ✅ **已定（2026-11-02 · D16）**：**C 侧按三域共享设计 = 照抄 B 侧**，不再拍板；⚠️ **「C 侧 favorite 域的接线是否已走共享层」归 `favorite.md` 核**（本表只判共享层本体，不判域内接线） |
| **2** | **`GrpcStatusMapper` 与 `GrpcExceptionMapperAutoConfiguration` 职责重叠** | B：`article-api/http/GoStyleGrpcStatusMapper.java`（1 份）。C：**同时**有 `web/GrpcStatusMapper.java` **和** `autoconfigure/GrpcExceptionMapperAutoConfiguration.java` | 两处都会做「gRPC code → 业务码」映射 ⇒ 装配顺序不同 ⇒ 同一异常可能映射出**两个不同 code** | 🔴 二选一：**建议**删 `web/GrpcStatusMapper.java`，统一由 auto-config 的 mapper 承担（与 D2/D5 的「一份业务逻辑 + 两个边界适配器」一致） |
| **3** | **设计文档 §5.1 列了 `contract/ErrorCode.java`，实际树里没有** | 实际：`common/src/main/java/com/rag/common/contract/` **只有 `ApiResponse.java`**；全仓 **0 个 `ErrorCode*.java`**（错误码在各域模块的 `contract/` 包 —— `ErrCodes` / `ErrMsg`） | 文档与树不一致 ⇒ 按文档实现的人会去建一个**全局** `ErrorCode` ⇒ **违反「错误码逐域隔离」** | 🔴 **以实际树为准**，并修正 `docs/完全spring化的文件树.md` §5.1 / §5.5（§5.5 还把 `communityfact/` 估成 4 类、实际 13 类；`web/` 估成 17 类、实际 16 类） |
| **4** | **雪花 ID 有两个落点** | C：`common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` **和** 3 个域级 `wiring/SnowflakeBootstrap.java`（`article` / `user` / `admin`） | 两个 `Snowflake` Bean ⇒ **两个独立计数器** ⇒ ID 重复 | 🔴 删 3 个域级 `SnowflakeBootstrap`，只留 starter；加断言「容器内 `Snowflake` 有且仅有 1 个」 |
| **5** | **`JsonBodyConverterConfig` 是「注册点」还是「配置类」** | B：RAG 仓 `service/common/src/main/java/com/rag/common/http/GoStyleJsonBodyConverterConfig.java` 是一个独立 `@Configuration`。C：同时存在 `web/JsonBodyConverterConfig.java` **和** `autoconfigure/JsonBodyConverterAutoConfiguration.java` | 若两者都注册 converter ⇒ **同一个 `HttpMessageConverter` 注册两次** ⇒ 请求体被读两次 / 判据被覆盖 | 🟡 建议：`web/JsonBodyConverterConfig.java` **只保留配置常量**（判据字面量），注册动作交 `JsonBodyConverterAutoConfiguration` |
| **6** | ~~**gRPC 拦截链的挂载点在 C 侧完全缺位**（2026-10-26 新发现）~~ ⇒ ✅ **已于 2026-11-02 批次 B 修完**（**DRIFT-COMMON-V03**） | B：**2026-11-02 实测已订正** —— RAG 侧现在 **10 个 rpc 应用全部**声明了 `GrpcServerConfigurer`（`{article,comment,favorite,follow,hot,like,message,task}-rpc` + `users/{user,admin}-rpc`）⇒ **原文「只有 6 个 ⇒ message / user / admin 静默失效」已过时** ⇒ ⇒ 即 B 侧自己也已修好这一条（⚠️ 这条曾登记为 **DRIFT-COMMON-V08**）。C：**已修**（批次 B）—— `common/autoconfigure/ProtectionAutoConfiguration` 提供 `GrpcServerConfigurer`，链序 `recover → stat → prometheus → breaker → shedding → business` 由 `ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo` **真的搭出链**逐层断言 | devh starter 的三个工厂方法**全都只注入 `List<GrpcServerConfigurer>`** ⇒ 只产 `ServerInterceptor` Bean 而不产 Configurer ⇒ **超时 / deadline 传播 / gRPC span / `sea.slow` 标记全丢，且零报错** | 🔴 **落点 = `common/autoconfigure/ProtectionAutoConfiguration`**（**共享层 1 份**，D1 合并的收益：一域一进程 ⇒ 每进程 1 个 gRPC server ⇒ 1 个全局 Configurer 即覆盖 10 个域）＋ **域内 0 重复**（重复 ⇒ 链被挂 2 次 ⇒ span 重复 / timeout 叠加）。⇒ 依据 [`完全spring化的文件树.md`](../完全spring化的文件树.md) §5.5.2；断言改写见 [`架构符合性断言.md`](../架构符合性断言.md) A3 · 契约 B |
| **7** | **`ProtectionProperties` 的落点：本表原写「并入 `ProtectionAutoConfiguration`」（N:1），实际独立成类** | B：`protection/GoStyleProtectionProperties.java` + `GoStyleProtectionConfiguration.java`（**2 个 RAG 类**）。C：**独立成** `protection/ProtectionProperties.java`（1 个配置载体）＋ `autoconfigure/ProtectionAutoConfiguration.java`（1 个装配点） | ① `redis/DeadlineAutoConfiguration` **硬依赖**它 ⇒ 若按原表并入 auto-config，批 1 的 redis 族**编译不过**；② 生态惯例 = `@ConfigurationProperties` 类独立 + auto-config 用 `@EnableConfigurationProperties` 引用 | ✅ **已定（2026-11-02 · L-15）**：独立成 `protection/ProtectionProperties.java`；**纯配置载体不属 D4 生态吸收对象**（D4 吸收的是「能力」，不是「配置」） |
| **8** | **`DeadlineState` 在 RAG 是嵌套类，本表原无独立落点** | B：`observability/RpcInterceptor.java#DeadlineState`（**嵌套类**，不在文件顶层）。C：**提升为顶层类** `observability/DeadlineState.java` | 188 行**零外部依赖**（纯 `ThreadLocal`）⇒ 可独立编译；但 `persistence/DeadlineQueryTimeoutInjector` **硬依赖**它 ⇒ 不提升则批 1 **编译不过** | ✅ **已定（2026-11-02 · L-16）**：提升为顶层类；与 `GoStyleRequestDeadline` 构成「入向 / 出向」两载体 |

### 7.5 ✅ 改名已执行（2026-11-02 批 1 · 18 → 2）

RAG-V1 的 `common/` 里**曾保留 18 个 `GoStyle*` 命名的类**（实测 `find common -name 'GoStyle*.java' | wc -l` = **18**）
⇒ **2026-11-02 批 1 已执行改名与归位**（去 `GoStyle` 前缀 + 归位到 §2.1 的技术角色包）⇒ **实测现剩 2 个**：

| 子包 | 仍带 `GoStyle*` 的类 | 为什么保留 |
|---|---|---|
| `logger/` | `GoStyleLogger` | ⚠️ **断言 A8 锁死了这个名字**（`GoStyleLogger implements org.slf4j.Logger`）⇒ 改名即改断言 |
| `observability/` | `GoStyleRequestDeadline` | ⚠️ **K-03 保留项**：Java `Future.cancel(true)` 对阻塞 IO 无效 ⇒ 这类是「为对齐**上游**语义而手写」的**语义标记**（**D16 后读作「为对齐 B 侧语义」**） |

**已改名的 16 个**（`web/` 全族）：`GoStyleBadRequestException` → `BadRequestException` · `GoStyleCoercion` → `Coercion` ·
`GoStyleFloat64Deserializer` → `Float64Deserializer` · `GoStyleGetFormValuesFilter` → `GetFormValuesFilter` ·
`GoStyleGrpcStatusMapper` → `GrpcStatusMapper` · `GoStyleHttpErrorException` → `HttpErrorException` ·
`GoStyleJsonBodyConverter` → `JsonBodyConverter` · `GoStyleJsonBodyConverterConfig` → `JsonBodyConverterConfig` ·
`GoStyleJsonBodyLimitFilter` → `JsonBodyLimitFilter` · `GoStyleMapStringValueDeserializer` → `MapStringValueDeserializer` ·
`GoStylePathNormalizingFilter` → `PathNormalizingFilter` · `GoStylePlainTextErrors` → `PlainTextErrors` ·
`GoStyleRawStringListDeserializer` → `RawStringListDeserializer` · `GoStyleRouterErrorResponses` → `RouterErrorResponses` ·
`GoStyleStringFields` → `StringFields` · `GoStyleStringInt64` → `StringInt64`

> ✅ **改名纪律（本轮实测过的坑）**：批量类名替换**必须「最长优先 + 词边界 + 一次性」**
> ⇒ 否则 `GoStyleFormValueLimitInterceptor` 的前缀会先命中 `GoStyleFormValue…`，
> 把 `GoStyleFormValues` 的静态调用错改成别的方法（正确应为 `LimitMode.parse` / `GetFormValuesFilter.*`）。
> ⇒ 改名**必须同步改 import**（`common` 被 10 个域依赖 ⇒ 一次改名 = 10 个域的编译面）。
> ⚠️ **`GoStyle` 前缀本身不是缺陷** —— 它的含义是「为对齐**上游**语义而手写」（**D16 后读作「为对齐 B 侧语义」**），改名会**丢失这条信息**；
> ⇒ 改名后的类**必须在类级 javadoc 里保留该语义标注**（否则后人不会知道这些类为什么长这样）。

---

### 7.6 ✅ V03 修法（2026-11-02 · 批次 B）：gRPC 保护链 5 层 ＋ 客户端侧两件套

> **背景**：`docs/DRIFT/common/verification-common-2026-11-02.md` 的 **DRIFT-COMMON-V03**
> —— gRPC 服务端保护链 **5 层全缺**（recover / stat / prometheus / breaker / shedding），
> 且客户端侧熔断 ＋ 出向 deadline 传播也缺 ⇒ 保护能力比 **B 侧**弱一整层。
> ⇒ 本节登记**逐类**的 B → C 落点。

| B 源类（RAG `service/common/`） | 职责 | C 承接（RAG-V1 `common/`） | 类型 | 备注 |
|---|---|---|---|---|
| `observability/GoStyleRecoverInterceptor.java` | gRPC panic ⇒ `INTERNAL` ＋ `"panic: "` | `common/src/main/java/com/rag/common/observability/RpcRecoverInterceptor.java` | 1:1 | ⚠️ **`"panic: "` 是 L1 wire 契约**（客户端可见）⇒ 不得改写；守门 `RpcRecoverInterceptorTest`（4 例）逐字断言 |
| `observability/GoStyleStatInterceptor.java` | 每请求耗时样本 | `common/src/main/java/com/rag/common/observability/RpcStatInterceptor.java` | 1:1 | 抄写基准 = RAG |
| `observability/GoStyleStatMetrics.java` | 进程内 RPC 聚合器 ＋ 每 60s 统计日志（含 3 个**上游对齐怪癖**） | `common/src/main/java/com/rag/common/observability/RpcStatMetrics.java` | 1:1 | 抄写基准 = RAG（avg 先整除 / median = 第 `size>>1` 大 / 小样本退化） |
| `observability/GoStylePrometheusInterceptor.java` | gRPC 耗时直方图 ＋ 状态码计数 | `common/src/main/java/com/rag/common/observability/RpcPrometheusInterceptor.java` | 1:1 | ⚠️ **D4 生态吸收**：不走 RAG 的「接缝接口 ＋ 静态 holder」，改**构造器注入 `MeterRegistry`** ⇒ 零静态状态；⚠️ **代价（已登记 L3）**：指标**桶布局**不等价（**上游**显式 12 桶 vs Micrometer 默认 summary） |
| `observability/GoStylePrometheusMetrics.java` ＋ `GoStylePrometheusSink.java` ＋ `MicrometerPrometheusSink.java` ＋ `GoStylePrometheusMeterRegistryBridge.java` | Prometheus 写入面的接缝接口 ＋ 静态 holder ＋ Micrometer 实现 ＋ 装配点 | **无对应文件**（4 个 B 类合并为 1 个 C 类） | N:1 | ★ **D4 生态吸收** ⇒ **零 Java 代码**（C 侧 Boot 原生 `micrometer-registry-prometheus`）；⚠️ 连带代价见上一行的「桶布局不等价」 |
| `protection/GoStyleRpcFailurePolicy.java` | gRPC 熔断的 6 码失败白名单 | `common/src/main/java/com/rag/common/protection/RpcFailurePolicy.java` | 1:1 | ⚠️ **业务错误（NOT_FOUND / PERMISSION_DENIED …）不参与熔断** ⇒ 6 码一个不多一个不少；守门 `RpcProtectionLayerTest` 逐码断言 |
| `protection/GoStyleCircuitOpenException.java` | 熔断打开时抛的**普通**异常（⇒ 调用方映射成 1015 / `service busy`） | `common/src/main/java/com/rag/common/protection/ServiceBusyException.java` | 1:1 | ⚠️ **刻意不是 `StatusRuntimeException`** ⇒ description 才不会变成 "circuit breaker is open" |
| `protection/GoStyleRpcBreakerInterceptor.java` | gRPC 服务端熔断（隔离 key = `info.FullMethod`） | `common/src/main/java/com/rag/common/protection/RpcCircuitGateInterceptor.java` | 1:1 | ⚠️ **类名避开 `Breaker`**（守门 A7 的文件名判据）⇒ 但**确实**用 resilience4j ⇒ A7 的意图也满足；⚠️ 拒绝形状 = `UNAVAILABLE` ＋ `"circuit breaker is open"`（wire 契约） |
| `protection/GoStyleAdaptiveShedder.java` | 降载**双条件**判据（CPU 过载 **且** 在途超上限） | `common/src/main/java/com/rag/common/protection/AdaptiveShedder.java` | 1:1 | ✅ **D-04 的缺口在 gRPC 侧闭合**（REST 侧仍只有 CPU 一半）⇒ 自研 1 类（生态无等价物 · G-1）；✅ **批次 H：REST 侧也接上了** ⇒ **双条件判据两侧齐备** ⇒ **D-04 整体撤销降级**；⚠️ **L4 留痕**：5 个方法由 package-private 放宽为 `public`（**D11 包压平** ⇒ REST filter 在 `autoconfigure` 包够不着）⇒ **逻辑一行未改** |
| `protection/GoStyleSheddingStat.java` | 三计数 ＋ 每分钟统计日志 | `common/src/main/java/com/rag/common/protection/SheddingStat.java` | 1:1 | ✅ **V10 在 gRPC 侧闭合**；⚠️ **REST 侧仍无统计日志** —— 但 **B 侧 REST filter 也没接** ⇒ **B 侧原生缺口** ⇒ **C ≡ B ⇒ 不是 C 的迁移缺陷** ⇒ 已登记 **D-08** |
| `protection/GoStyleRpcSheddingInterceptor.java` | gRPC 服务端降载 | `common/src/main/java/com/rag/common/protection/RpcSheddingInterceptor.java` | 1:1 | ⚠️ 拒绝形状 = `RESOURCE_EXHAUSTED` ＋ `"service overloaded"`（wire 契约）⇒ 且**必须走 `call.close(...)`** ⇒ 才能冒泡到外层 breaker |
| `protection/GoStyleRpcProtectionChain.java` | 链序的唯一计算点 ＋ 只读观测入口 | `common/src/main/java/com/rag/common/protection/RpcProtectionChain.java` ＋ `RpcLayer.java` ＋ `RpcLayerChainNode.java` | 1:N | ⚠️ **L4 形态差**：B 侧手工拼链 ⇒ C 侧改为「各层是 `@Bean` ＋ `@Order` 定序 ＋ 本类显式嵌套」⇒ ⇒ **链序必须真的搭出来断言**（`ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo`）⇒ 期望 `[recover, stat, prometheus, breaker, shedding, business]` |
| `protection/GoStyleGrpcClientBreakerInterceptor.java` | 客户端熔断（隔离 key = `path.Join(target, method)`） | `common/src/main/java/com/rag/common/protection/ClientCircuitGateInterceptor.java` | 1:1 | ⚠️ **隔离 key 含下游服务身份** ⇒ 同名方法在不同服务下**不共用**熔断器；⚠️ 拒绝时抛**普通异常** ⇒ 调用方映射成 1015 / `service busy` |
| `protection/GoStyleGrpcClientBreakerConfiguration.java` ＋ `GoStyleOutboundDeadlineConfiguration.java` | 两个客户端拦截器的装配点（RAG 用 `@Configuration` ＋ `@Import`） | `common/src/main/java/com/rag/common/autoconfigure/ClientProtectionAutoConfiguration.java` | N:1 | ★ **D3 starter 化** ⇒ 改 `@AutoConfiguration` ＋ `AutoConfiguration.imports` ⇒ **消费方零 `@Import`**（守门 A5） |
| `protection/GoStyleOutboundDeadlineInterceptor.java` | 出向 deadline 传播（min(请求剩余, 调用点自带, 客户端上界)） | `common/src/main/java/com/rag/common/protection/OutboundDeadlineInterceptor.java` | 1:1 | ⚠️ **per-service 上界是「覆盖」组件级上界**（不是 min）⇒ 否则「按方向分档」表达不出来 |

> ⚠️ **A7 的类名纪律**：`RpcCircuitGateInterceptor` ＋ `ClientCircuitGateInterceptor` 的文件名
> **不含** `Breaker|CircuitBreaker|CircuitOpen` ⇒ 守门 A7 的**文件名判据**不会把它们误判成
> 「手写熔断器」；而它们**确实**用 resilience4j 承载熔断器 ⇒ A7 的**意图**同样满足。
> ⇒ ⇒ **机制类型与上游的 googlebreaker 不同**（概率 drop ＋ 无三态 vs 计数窗口 ＋ 三态）
> ⇒ 继续挂在 **D-02** 的 L3 降级下（与 REST 侧同一份登记）。

---

### 7.7 ✅ V07 + 批次 E 修法（2026-11-02 · 批次 D/E）：`security/` 三类 ＋ 观测层收口 ＋ 并发闸门

> 本节逐条记录 **批次 D（security 三类）** 与 **批次 E（观测层收口 ＋ 并发闸门）** 的 B → C 落点。
> 抄写基准 = **RAG**（L2 业务语义层整段抄 · D13）；装配形态与写入载体按本仓的 **D3 / D1 / D4** 回改。

| B 源类（RAG `service/common/`） | 职责 | C 承接（RAG-V1 `common/`） | 类型 | 抄写与回改要点 |
|---|---|---|---|---|
| `users-common/jwt/JwtUtil.java` | HS256 签发 / 验签 ＋ secret 补齐 ≥ 32 字节 | `common/src/main/java/com/rag/common/security/JwtUtil.java` | 1:1 | ✅ **已落地（批次 D）**：抄 RAG 整段 ＋ **jjwt 0.x → 0.12.6 适配**（`SignatureAlgorithm.HS256` → `Jwts.SIG.HS256`；JCA 算法名钉死 `HmacSHA256`）⇒ ⚠️ jjwt 强制 key ≥ 256 bit ⇒ **短密钥不能用 jjwt parser 验** ⇒ 守门用手写 HMAC 验签证明「**B 侧用原始 15 字节短密钥**能验过 C 签的 token」（RFC 2104 零填充等价性） |
| `users-common/jwt/BearerTokenExtractor.java` | `Authorization` 头提取 | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | 1:1 | ✅ **已落地（批次 D）**：⚠️ **按上游改了一处** —— B 侧用 `length() > 7`，上游用 `len(tok) > 6` ⇒ 「Bearer 」正好 7 字符时上游剥成空串、**B 侧不剥** ⇒ **C 侧当前跟上游（`> 6`）⇒ 与 B 侧存在差异**（原判「L1 以上游为准」；⚠️ **D16 后基准是 B 侧** ⇒ 该差异**应回归 B 侧的 `> 7`**，本表只登记、不改码）⇒ 已在 javadoc 留痕；⚠️ **B 侧两套互不相同**的取法（验签 vs 黑名单 key）**各自保留** |
| `users-common/cryptx/PasswordUtil.java` | bcrypt 口令比对 | `common/src/main/java/com/rag/common/security/PasswordUtil.java` | 1:1 | ✅ **已落地（批次 D）**：抄 RAG 整段（`Outcome{MATCHED,MISMATCHED,MALFORMED_HASH}`）⇒ ⚠️ **B 侧 `check()`** 把「密码不匹配」与「hash 本身非法」**压成同一个 false**（另有 `checkDetailed()` 可区分三态）⇒ **畸形 hash 不得抛异常**；⚠️ 依赖 `spring-security-crypto`（**只取 bcrypt 那一小块**，不引整个 spring-security ⇒ 共享层不引入 FilterChain / SecurityContext） |
| `spring/LoggerFactoryBean.java` | `Logger` 的 Spring 适配 | `common/src/main/java/com/rag/common/logger/LoggerFactoryBean.java` ＋ `common/src/main/java/com/rag/common/autoconfigure/LoggerAutoConfiguration.java` | 1:N | ✅ **已落地（批次 E）**：抄 RAG 整段 ＋ **装配形态回改**（`@Component` → auto-config 的 `@Bean`，L4 · D3）⇒ **消费方零装配代码**（A5）；⚠️ **算法只留一份**（委托给 `GoStyleLogger.doLogXxx`）⇒ 不会出现「改一处忘一处」 |
| `observability/MqConsumer.java` | MQ 消费端 trace 包装（上游的 `TraceConsumer`） | `common/src/main/java/com/rag/common/observability/MqConsumer.java` | 1:1 | ✅ **已落地（批次 E）**：抄 RAG 整段 ＋ **写入载体由「手写 OTel span」改为「Micrometer Observation + OTel 双载体」**（L4 · D4 / D-03）⇒ ⚠️ Micrometer 无 span kind ⇒ 用 `mq.kind=consumer` 承载（**本仓新增的键**，登记 D-03 同族）；⚠️ **刻意不 catch `Throwable`**（上游的裸调用语义 ⇒ 异常继续向上传播） |
| `observability/RpcInterceptor.java#handle` ＋ 嵌套 `RpcResult` / `RpcCallHandler` ＋ 两个 static 池 | 非 gRPC 路径的业务调用（独立线程 ＋ 到点 `cancel(true)` 打断 ＋ 观测分类） | `common/src/main/java/com/rag/common/observability/RpcHandleRunner.java` ＋ `RpcResult.java` ＋ `RpcCallHandler.java` ＋ `ObservabilityRuntime.java` | 1:N | ✅ **已落地（批次 E）**：抄 RAG 整段 ＋ **嵌套类提到顶层 / 静态池抽到 `ObservabilityRuntime`**（L4 · D11 / 跨包可见性）⇒ ⚠️ **只有这一条路径能真正打断业务线程** ⇒ gRPC 拦截器路径没有（grpc-java 限制） |
| `spring/ObservabilityLifecycle.java` | 容器关闭时回收 `obs-rpc-timeout` / `obs-rpc-worker` 两池 | `common/src/main/java/com/rag/common/observability/ObservabilityLifecycle.java` | 1:1 | ✅ **已落地（批次 E）**：抄 RAG 整段 ＋ **从 `spring` 包搬到 `observability` 包 + 改由 auto-config 注册**（L4 · D3 / D11）⇒ ⚠️ **零 servlet 依赖** ⇒ 纯 gRPC 进程加载也安全（RAG 侧曾在此炸 `NoClassDefFoundError`）⇒ ⚠️ **池被终止后按需重建**（RAG 侧踩过的坑） |
| `protection/GoStyleMaxConnsFilter.java` | 并发连接上限（全局 `Semaphore` 闸门 ＋ 超限裸 503） | `common/src/main/java/com/rag/common/protection/MaxConnsFilter.java` | 1:1 | ✅ **已落地（批次 E）**：抄 RAG 整段 ＋ **装配形态回改**（L4 · D3 / D1）⇒ ⚠️ **推翻了「Tomcat 原生项 ⇒ 零 Java 代码」这个旧口径**（Tomcat 超限是**排队**、**上游**是**立刻 503** ⇒ 两种可观察行为）⇒ ⇒ **不改 yml**（L-5 冻结）⇒ 闸门在 Java 侧 |

> ⚠️ **批次 D/E 的验证口径**：common 模块 **126 → 164 例**（新增 38 例）·
> **3 次变异实测**（把回落改成放行 / 超时改成 `INTERNAL` / 关掉池重建）⇒ **每次都变红**
> ⇒ ⇒ 证明这些断言**不是假绿**（AGENTS.md §3.3「变异测试是另一半」）。

---

### 7.8 ✅ V04 修法（2026-11-02 · 批次 C）：HTTP 超时 503 ＋ 断连 499 ＋ 512 worker 池 ＋ 断连探针

> 本节逐条记录 **批次 C**（🔴 **DRIFT-COMMON-V04**）的 B → C 落点。
> 抄写基准 = **RAG**（L2 业务语义层整段抄 · D13）；写入载体与装配形态按本仓的 **D3 / D1 / D4** 回改。

| B 源类（RAG `service/common/`） | 职责 | C 承接（RAG-V1 `common/`） | 类型 | 抄写与回改要点 |
|---|---|---|---|---|
| `observability/HttpMiddleware.java` | HTTP 观测中间件（超时 503 ＋ 断连 499 ＋ worker 池 ＋ 观测标记） | `common/src/main/java/com/rag/common/observability/HttpMiddleware.java` | 1:1 | ✅ **已落地（批次 C）**：抄 RAG 整段（L2 · D13）＋ **写入载体改为「Micrometer Observation + OTel 双载体」**（L4 · D4 / D-03）＋ **静态工厂改为 Spring Bean**（L4 · D3）⇒ **三条拒绝路径的形状逐条对齐**：① 超时 = **503 + body `Request Timeout\n`（16 字节）+ `text/plain; charset=utf-8` + `nosniff` + `Content-Length: 16`**；② 断连 = **499**（同款 body）；③ **池饱和 = 裸 503（无 body、无 Content-Type）** —— ③ 刻意与 ①② 不同（容量不足是「现在别来」，超时是「来不及了」）<br>⚠️ **RAG 侧注释写「15 字节」是笔误**（15 字符 + `\n` = **16**）⇒ 本仓照**代码**不照注释 |
| `observability/HttpMiddleware.java`（嵌套 `ObservabilityResponseWriter`） | 响应记录器（捕获 body ＋ 超时后掐掉底层提交 ＋ 底层写锁） | `common/src/main/java/com/rag/common/observability/ObservabilityResponseWriter.java` | 1:1 | ✅ **已落地（批次 C）**：抄 RAG 整段（L2 · D13）＋ **从嵌套类提到顶层**（L4 · D11）⇒ ⚠️ **不能直接用 Spring 的 `ContentCachingResponseWrapper`** —— 它的 `flushBuffer()` 会透传到底层 ⇒ worker 线程能抢先提交响应 ⇒ 两个写者 ⇒ 状态码被抢 + Tomcat `MimeHeaders` 留 null 洞 ⇒ NPE ⇒ 客户端只看到 EOF |
| `observability/GoStyleClientDisconnectProbe.java` | 客户端断连主动探测（往 socket 写 103 探针 ＋ 4 态 ＋ 行数预算） | `common/src/main/java/com/rag/common/observability/ClientDisconnectProbe.java` | 1:1 | ✅ **已落地（批次 C）**：抄 RAG 整段（L2 · D13）＋ **改名**（去 `GoStyle` 前缀，L4 · D11）⇒ ⚠️ **Tomcat 10.1 的反射链已重新取证**（审核报告 **F-7**）：`RequestFacade.request` ✔ / `Request.getCoyoteRequest()` ✔ / `coyote.Request.hook` ✔ / `AbstractProcessor.getSocketWrapper()` ✔ / `SocketWrapperBase.write+flush` ✔ ⇒ **唯一差异**：`ActionHook` 在 10.1 是**接口**（无 `getSocketWrapper()`）⇒ 必须按**实例的类层次**找 ⇒ 与 RAG 同一写法 |
| `spring/HttpMiddlewareFilter.java` | 把 HTTP 中间件挂进 Filter 链 | `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java`（`@Bean httpMiddleware(...)` ＋ `@Order(HIGHEST_PRECEDENCE + 7)`） | N:1 | ✅ **已落地（批次 C）**：RAG 侧是 `@Component`（靠组件扫描）⇒ 本仓 starter 化 ⇒ **改由 auto-config 的 `@Bean` 产出**（L4 · D3）⇒ **消费方零 `@Import`**（A5） |

> ⚠️ **批次 C 的验证口径**：common 测试 **167 → 185 例**（新增 18 例：`HttpMiddlewareTest` 11 例 ＋ `ClientDisconnectProbeTest` 8 例 ＋ 调整 1 例）·
> **4 次变异实测**（超时 503→500 / 池饱和改成写 body / panic 路径 499→500 / 探针断连 499→500）⇒ **每次都变红**。
> ⚠️ **其中一次变异「探针断连 499→500」最初<b>没有</b>变红** ⇒ ⇒ 说明那条分支当时<b>没被覆盖</b>
> ⇒ ⇒ 补了**探针接缝**（`HttpMiddleware.DisconnectProbe`，**仅单测注入**，生产路径恒走 `ClientDisconnectProbe.probe`）
> ⇒ ⇒ 再变异 ⇒ **变红** ⇒ ⇒ **「变异没变红」＝「这条断言没覆盖到」**（§3.3 的另一半）。

---

### 7.9 ✅ 批次 F 修法（2026-11-02）：全局 OTel 装配 ＋ `traceparent` 响应头恢复

> 本节逐条记录 **批次 F**（**DRIFT 报告里最后 1 条 NOT_IMPLEMENTED** 与 **2 条 FRAMEWORK_DIFFERENCE**）的 B → C 落点。
> 抄写基准 = **RAG**（L2 业务语义层整段拇 · D13）；装配形态按本仓的 **D3 / D1 / D4 / D11** 回改。

| B 源类（RAG `service/common/`） | 职责 | C 承接（RAG-V1 `common/`） | 类型 | 抄写与回改要点 |
|---|---|---|---|---|
| `observability/GoStyleOtelAgent.java` | 全局 tracer provider（SDK ＋ OTLP exporter ＋ 传播器 ＋ shutdown hook） | `common/src/main/java/com/rag/common/observability/GoStyleOtelAgent.java` | 1:1 | ✅ **已落地（批次 F）**：抄 RAG 整段（L2 · D13）⇒ 幂等（`AtomicBoolean` ≡ 上游的 `sync.Once`）/ `buildProvider` 逐行对齐上游的 `startAgent`（`parentBased(traceIdRatioBased(ratio))` ＋ `Resource(service.name)` ＋ **「endpoint 为空 ⇒ 不装 exporter」**的短路）/ 全局 `set()` 失败**只记 ERROR 不抛**（= 上游 `logx.Error` 后继续启动） |
| `observability/GoStyleOtelAutoConfiguration.java` | 全局装配点（`BeanFactoryPostProcessor` + `EnvironmentAware`） | `common/src/main/java/com/rag/common/autoconfigure/GoStyleOtelAutoConfiguration.java` | 1:1 | ✅ **已落地（批次 F）**：抄 RAG 整段＋ **包名回改**（`observability/` → `autoconfigure/`，与本仓其余 11 个 auto-config 同包，L4 · D11/D3）⇒ 已登记进 `AutoConfiguration.imports`（清单 13 → **14 行**）；⚠️ **为什么仍用 BFPP**：OTel 的 `GlobalOpenTelemetry.set()` **只能调一次**，且**先调 `get()` 会让后续 `set()` 抛 `IllegalStateException`** ⇒ 装配点必须早于**任何单例创建** |
| `observability/GoStyleOtelConfig.java` | 上游的 `Telemetry` 四字段投影 | `common/src/main/java/com/rag/common/observability/GoStyleOtelConfig.java` | 1:1 | ✅ **已落地（批次 F）**：**B ↔ C 真正 1:1**（原先挂在 `ObservabilityProperties` 但**只承载、不驱动装配**）⇒ **键名随之回到 B 侧的 `otel.*`** ⇒ **L1 配置契约与 B 侧逐字节同构** |
| （连带）`spring/ObservabilityProperties.java` 的 3 个 Telemetry 字段 | 配置彽数 | —（**移出**） | — | ⚠️ **口径变更**：`observability.endpoint` / `observability.protocol` / `observability.sampler-ratio` 三字段**移出** `ObservabilityProperties`（它们必须真正驱动装配，不能「只承载」）⇒ 该类现只留 `slow-threshold` / `http-timeout` / `rpc-timeout` 三个 Duration |

> ⚠️ **批次 F 的验证口径**：common 测试 **185 → 208 例**（新增 `GoStyleOtelAgentTest` 7 例 ＋ `GoStyleOtelConfigTest` 13 例 ＋ `TraceparentFilterTest` 4 例 ＋ 调整 `ObservabilityPropertiesTest` −1 例）·
> **7 次变异实测**（去 `GlobalOpenTelemetry.set` / 去 exporter / shutdown 不 close / 去 `ParentBased` / 端点不补 `http://` / 不校验 batcher / imports 摘掉那一行）⇒ **每次都变红**。
> ⚠️ **连带修掉的真实缺陷（DRIFT-COMMON-V14）**：`common/pom.xml` 在两个 devh starter 上 exclude 了 `grpc-core` ⇒ Maven 的 exclusion 作用于**整棵传递子树** ⇒ `grpc-netty-shaded → grpc-core` 也被切断 ⇒ classpath 上没有 `grpc-core` ⇒ 只要 devh 的 gRPC server auto-config 出现就炸 `NoClassDefFoundError: io/grpc/internal/ObjectPool` ⇒ 已按**域模块同款口径**显式声明（**test scope** ⇒ 零下游影响）。

### 7.10 ✅ 批次 G 修法（2026-11-02）：真 PostgreSQL 对拍 —— 闭合**最后 1 条 NOT_VERIFIED**

> 本节记录**批次 G** 的落点。**本轮没有新增任何主代码类** —— `JsonbTypeHandler` / `JsonTextTypeHandler`
> **一字未改**（抄 RAG 整段 · L2 · D13）⇒ 本轮补的是**证据**：此前判 `NOT_VERIFIED` 的理由是
> 「**有实现、无证据**」，不是「实现缺失」。

| 项 | 内容 |
|---|---|
| **新增测试** | `common/src/test/java/com/rag/common/persistence/JsonTypeHandlerPgTest.java`（**10 例**）⇒ common 测试 **208 → 218 例** |
| **新增依赖** | `common/pom.xml` 补 **Testcontainers**（`org.testcontainers:postgresql` + `junit-jupiter`，**test scope**）⇒ 版本由 **Boot BOM**（`spring-boot-dependencies` 的 `<testcontainers.version>` = **1.21.4**）管 ⇒ 与 10 个域模块的既有口径**逐字一致**（`service/like/pom.xml:74-82`） |
| **为什么必须真 PG** | 这两个类的**全部业务语义都长在 PG 的列类型上**：jsonb 列**规范化**键序与空白（读回文本 ≠ 写入原文）· 按 VARCHAR 绑定 jsonb 列 ⇒ PG 报 **42804** · jsonb 列**拒收** ` ` ⇒ **22P05** · **text 列照单全收** ` ` ⇒ ⇒ mock 不执行 SQL、H2 没有 JSONB 类型 ⇒ **都验不到要验的东西** |
| **禁 `@EnabledIfDockerAvailable`** | AGENTS.md §五.8 守门脚本 4 硬规则 ③ ⇒ Docker 不可用时**必须红**、不许静默跳过 ⇒ 守门脚本才能断言 `skipped=0` |

**10 条对拍（真 PG · `postgres:16-alpine`）**

| # | 断言 | 证的是什么 |
|---|---|---|
| 1 | 对照组：同一 jsonb 列按 VARCHAR 绑定 ⇒ PG 报 **42804** | `PGobject` 路径是**承重**的（不是可有可无的包装） |
| 2 | 写走 `PGobject` ⇒ `information_schema` 复核列类型 = `jsonb`，且读回 `{"a": 2, "b": 1}`（写入原文是 `{"b":1,"a":2}`） | 列真是 jsonb ＋ **PG 的规范化语义**（javadoc 已预警的「读回 ≠ 写入原文」） |
| 3 | 写入非法 JSON ⇒ PG 报 **22P02** | **写入前不校验**（B 侧也不校验 ⇒ 让 PG 报错而非静默吞掉） |
| 4 | 写 `{"k":"\u0000"}` 进 jsonb ⇒ PG 报 **22P05** | jsonb 的 unicode escape 校验 |
| 5 | text 列走 `setString` ⇒ 原样 JSON 文本落库（`information_schema` 复核列类型 = `text`） | 写路径确实是 **text 原生绑定**（不是 jsonb 味） |
| 6 | text 列收下 `\u0000`（jsonb 会拒）⇒ 读回 Map 值逐条相等 | **两个 handler 必须分开的唯一判据**（**B 侧**对拍证据 DRIFT-ARPC-004①） |
| 7 | 读回 Map 字段逐条相等 | Jackson `TypeReference<Map<String,String>>` 解析 |
| 8 | null map ⇒ 列写 **NULL**（不是字符串 `"null"`） | 空值语义（与上游的 nil map 一致） |
| 9 | 列里是空白 ⇒ 读回 null | `parse` 的 `isBlank()` 分支 |
| 10 | 列里是非法 JSON ⇒ 包成 `SQLException`（消息 `Failed to deserialize JSON to Map: …`） | 异常包装契约 |

> ⚠️ **装配面取证**（`javap -c org.apache.ibatis.type.TypeHandlerRegistry#getInstance`）：
> MyBatis **先试 `(Class)` 构造、失败回落无参构造** ⇒ `JsonTextTypeHandler`（**只有** `(Class)` 构造）走前者、
> `JsonbTypeHandler`（**只有**无参构造）走后者 ⇒ ⇒ **两个 handler 都能被生产路径实例化**
> （MyBatis-Plus 的 `autoResultMap` / `@TableField(typeHandler=)` 走的正是 `registry.getInstance(javaType, handlerClass)`）。
>
> ⚠️ **变异实测 7 次，每次都变红**（AGENTS.md §3.3.3「变异测试是另一半」）：
> M1 `PGobject` → `setString`（2 红 1 错）· M2 `PGobject.setType("jsonb")` → `"text"`（2 红 1 错）· M3 jsonb 读返 null（1 红）·
> M4 text 写写死内容（4 红）· M5 text 读返 null（4 红）· M6 去 `isBlank` 分支（1 错）· M7 异常不再包 `SQLException`（1 红）。

### 7.11 ✅ 批次 H 修法（2026-11-02）：REST 侧降载判据回到 B 侧形态 ＋ **V09 / V10 收口**

> 本节记录**批次 H**。本轮**修掉一条被误判为「已登记降级」的真实漂移**，并把 **V09 / V10** 两条
> 开放登记项**收口**（登记 ＋ 前提纠正）。

| 项 | 内容 |
|---|---|
| **🔴 漂移本体** | C 侧 REST filter 的判据是「**只有 CPU 过载**」⇒ ⇒ **比 B 侧更早丢请求**。**B 侧** `GoStyleCpuSheddingFilter` 用的是**完整的 `GoStyleAdaptiveShedder`**（`shedder.allow()` / `shedder.fail()` / `shedder.pass(rt)`）⇒ ⇒ **C 丢了 B 已有的能力** ⇒ 按 verification.md §十 属 **FAIL**（**不是**已批准的降级）。 |
| **⚠️ 旧登记的前提本身是错的** | D-04 与 V10 原先都写「RAG 侧也只有 CPU 一半」⇒ **不成立** ⇒ ⇒ 本轮**一并纠正**（不掩盖、不静默改写：L3 清单里保留了原文 + 变更理由）。 |
| **修法（代码）** | ① `ProtectionAutoConfiguration.CpuSheddingFilter` 改走 `AdaptiveShedder`（与 B 侧 `GoStyleAdaptiveShedder` **逐行同构**）⇒ **双条件判据 + pass/fail 记账 + 浮点 RT 口径**三样回到 B 侧形态；② `AdaptiveShedder` 的 5 个方法由 package-private **放宽为 `public`**（**L4 · D11 包压平** ⇒ REST filter 在 `autoconfigure` 包够不着）⇒ **逻辑一行未改**；③ filter **自建独立 shedder 实例**（不与 gRPC 侧共用 ⇒ **与 B 侧同款**「每进程一份」）。 |
| **L4 回改清单** | ① `@Component` + `@ConditionalOnApiSide` → auto-config 的 `@Bean` + `@ConditionalOnWebApplication(SERVLET)`（D3 / D1）；② B 的 `GoStyleStatusCapturingResponse`（包级私有 wrapper）→ 直接读 `response.getStatus()`（等价：servlet 规范下未设码即 **200**，`sendError` 也会反映到 `getStatus()` ⇒ 两侧判据同值）；③ 逐字跟随 B ⇒ **不**加「`cpuThreshold > 0` 才启用」的守卫（默认 900 ⇒ 该分支实际不可达）。 |
| **守门** | `CpuSheddingFilterTest`（**4 例**）：① RT 口径是**浮点**毫秒（1.2ms 原样保留）② **回归守卫：CPU 过载但无在途 ⇒ 放行**（★ 正是漂移形态的判据）③ CPU 过载 **且** 在途超上限 ⇒ **裸 503 + 空 body**（构造法**逐字沿用** RAG 侧 `GoStyleCpuSheddingFilterTest`）④ `enabled=false` ⇒ 完全放行。**变异实测 4 次每次都变红**（M1 **退回只看 CPU**（= 漂移形态）/ M2 忽略 `allow()` / M3 RT 改整型截断 / M4 不做 pass/fail 记账）。 |
| **✅ V09 收口（登记）** | HTTP 侧的熔断机制类型差异（概率 drop + force-pass + 无三态 vs 计数窗口 + 三态）已扩写进 **D-02**；**有效参数已实测并钉桩**（resilience4j 2.2.0 = `COUNT_BASED` / 100 / 100 / 50% / 60s / 10 / 自动转换关 ⇒ `HttpCircuitBreakerConfigPinTest`）⇒ 框架默认值变形 ⇒ 测试立刻红 ⇒ **逼着回来重登记**。⚠️ **刻意不把参数写进本仓配置**（会覆盖 resilience4j 自动配置 ⇒ 制造未经批准的漂移）。 |
| **✅ V10 收口（登记）** | 新立 **D-08**「HTTP 侧降载的可观察面」⇒ ⚠️ **定性订正**：上游的 HTTP 侧**本来就有**自己那一份 `SheddingStat`（name=`"api"`）+ `[http] dropped` 行，但 **B 侧 REST filter 也没接** ⇒ ⇒ **B 侧原生缺口** ⇒ **C ≡ B ⇒ 不是 C 的迁移缺陷** ⇒ 本仓**不改**；但**必须登记**（否则对拍脚本会把它误判成回归 ⇒ 触发静默「修回」）。 |
| **台账联动** | `docs/L3-降级清单.md`：**D-02** 扩写（HTTP 侧）· **D-04 撤销降级**（保留原文 + 纠正前提）· **D-08 新增**（降级项 7 → **8**）；`docs/DRIFT/common/verification-common-2026-11-02.md`：§11.2 的 `GoStyleCpuSheddingFilter` 行 **INTENTIONALLY_CHANGED → PASS**（⇒ §11.6 的 INTENTIONALLY_CHANGED **3 → 2**）· §11.5 的 V09 / V10 **🟠 → ⚪ 已登记**。 |

---

## 附：本表引用

| 来源 | 用途 |
|---|---|
| `RAG/service/common/`（**88 个主代码类 / 12 个包**，2026-11-01 实测） | **B 列落点 · 唯一真理**；逐文件核过文件名与包结构 |
| `RAG-V1/common/`（**104 个主代码类 / 12 个包**，2026-11-02 实测） | **C 列清单**（实测 `find`，非估算） |
| `RAG/docs/FileTree/common.md` | B 列的现成职责映射（`§12.4` 职责→Java 映射表、`§12.7` 不通过项 #1–#12） |
| `RAG/docs/DRIFT/common/alignment-2026-10-23.md` | B 列的漂移事实（§三 日志 KV 丢失 · §四 communityfact 消费者不一致 · §五 14 条未修 · §十二 27 条编号 + 5 项未编号） |
| `RAG-V1/docs/完全spring化的文件树.md` | C 列的推导依据（§4.5 共享层映射表 · §5.5 目标结构 · §6 文件变化表） |
| `RAG-V1/docs/决策记录.md` | D1–D9 已定 / P1–P6 待拍板 |
| `RAG-V1/docs/L3-降级清单.md` | D-01 – D-04 降级项 · K-01 – K-06 保留项 |
| `RAG-V1/docs/File_Tree/common.md` | C 列的实际文件系统树（由 `tools/gen-file-tree.py` 生成） |
| `RAG-V1/AGENTS.md` | §1.1b L1 逐域隔离 · §1.1c 生态无等价物 G-1 – G-4 · §1.3 注解≠生效 · §1.4 R1 – R6 · §2.0 命名约定 |
