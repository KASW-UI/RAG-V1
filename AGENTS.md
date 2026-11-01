# AGENTS.md: RAG-V1 项目政策

> 本文件是 RAG-V1 项目唯一由每个 agent 自动加载的根政策文件，**只放政策与索引**。
> **真源 = Go**（`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang`，已冻结；非 Spring Boot 的 Java 实现不在范围内，详见 §1.1）。
> **台账三档**：逐条漂移与修复记录 → [`docs/DRIFT/`](docs/DRIFT/_INDEX.md)·
> 踩坑与元教训 → [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)· 签字偏离 →
> [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)。
>
> 👉 **先读 §一「项目身份与目的」** —— 下面每一条政策都是在为它服务；不知道目的就改不动。
>
> ⚠️ **本文件由 RAG 仓库的 AGENTS.md 改写而来**（2026-10-25）。带 **📦** 标记的段落是 **RAG 仓库的历史存档**（参考，不描述 RAG-V1 现状）；**RAG-V1 自身状态一律以 §0 为准。**

## 〇、当前状态（2026-11-02）

**形态：共享层 `common/` 已落地 98/99 类；10 个业务域仍全为空壳**。

> **📌 往哪写** —— 只放**政策与索引**，**全文可进上下文（预算 32 KB）**：逐条漂移 ⇒ `docs/DRIFT/<域>/<组件>.md`；踩坑 ⇒ `docs/Pitfalls/`；签字偏离 ⇒ `docs/Intentional_Deviations/`。**只留结论 + 一行指针。超预算 = 没走这条约定。**

> **核对日期 2026-11-02**（`find` 实测）

| 项 | 数 |
|---|---|
| Java 文件 | **656**（619 主代码 + 37 测试）—— `service/` **520 仍全为空壳** + `common/` **99（98 已落地 · 1 空壳）** |
| Maven 模块 | **16** pom = **13 叶子** + **2 聚合**（`service`/`users`）+ 1 根 —— **D7 打平**：每域**一个** jar 模块（`service/<域>/`）；artifactId 保持长名唯一（`<域>-service`/`users-common`/`proto`/`common`）⇒ 依赖声明零改动 |
| proto / `application.yml` | **10 / 10**（10 个 `.proto` 集中在**根级 `proto/` 模块**，pb 包名 `com.rag.proto.<域>`；内容与 Go 侧逐字节一致，仅 `java_package` 属表达层） |
| `AutoConfiguration.imports` | **1**（`common/`，12 个 auto-config） |
| `docs/File_Tree/` · 三方对照 | **14 份 + `_INDEX.md`** 文件树 · **13 份**域文档（C **619/619**、A **482/482** 覆盖 **100%**；**90 条落点缺口** 🔴11） |
| 三档台账 | DRIFT **10 域未建立**（common **已建立**）· Intentional_Deviations **4 条**（like/comment 各 2）· Pitfalls **P-01~P-11** |

**已完成**：树结构、pom、proto、application.yml、断言清单、三方对照、**GitHub 仓**、**工具链（Jackson 2 + Boot 3.5）**、**共享层 `common/`**。**未完成**：10 个业务域的实现（⇒ §1.0c 抄 RAG）。

✅ **2026-10-26 · D7/D10/D11**（proto 集中/模块打平/包压平 · `bootstrap`→`wiring` · `dto/` 拆子包）· **2026-11-01 · D13**（L2 以 RAG 为基准）· **2026-11-02 · D14**（**工具链 = Jackson 2 + Boot 3.5**）⇒ [`docs/决策记录.md`](docs/决策记录.md)（**§1.0c** 抄写来源 · **§1.0d** 工具链口径）。

> 📌 **RAG 的 158 份台账留在 RAG 仓** ⇒ 本仓 `docs/DRIFT/` 从零填；其**「证据强度」分级方法论可借鉴**（§1.0c 约束 3）；教训见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。

> ⚠️ **「已落地」≠「已实现」；「源码级对照」≠「双侧活体对拍」** ⇒ 台账里的 ✅ 一律读作「**在该证据强度下一致**」，不是已闭环。**证据强度以该域台账的「证据强度」列为准**（见 `docs/DRIFT/_INDEX.md` 末节）。

## 一、项目身份与目的

RAG-V1 仓库路径：`/Users/pr/Documents/GitHub/RAG-V1`。

### 1.0 目的（两层，互不替代）

**一句话**：把冻结的 Go/go-zero 微服务 **Sea-RideTheWind**（识海社区后端），用 **Spring Boot 惯用形态**重新实现一遍 —— **行为对拍等价**，代码按 Java 生态重新组织。

| 项 | 内容 |
|---|---|
| 产品 | **识海**社区后端 —— **内容 + 互动 + 任务 + 用户体系**一体化服务端；配套推荐系统 `BreakTheWaves` 与前端仓不在范围内 |
| Go 真源 | `Sea-RideTheWind_golang`（**已冻结 · 只读**，绝对路径见 §1.0b）|
| 迁移范围 | **10 个业务域**：article · comment · favorite · follow · hot · like · message · task · users（拆 `user`+`admin`）= **10 个 app** + 1 个 `common` starter + 1 个根级 `proto` 模块；**范围外**：`knowledge`/`points`/`security`（依据见 [`决策记录.md`](docs/决策记录.md)） |
| 基础设施 | Go 侧 PostgreSQL · Redis · Kafka · MinIO · Milvus · Neo4j · etcd；**Java 侧已接** PostgreSQL + Redis + Nacos |

1. **行为层 —— 严格复刻。** Go 是唯一 oracle。RAG-V1 必须在 **L1 契约 + L2 业务语义**上与 Go 等价；L3 可按已登记降级项降级，L4 可重选；**L1/L2 不可省、不可"顺手优化"**。
2. **认知层 —— 借复刻吃透 Java 生态。** 目的不是把 Go 翻译成 Java，而是借同一套业务逼出每个 Spring 组件的**职责边界** —— 谁负责什么、换成生态等价物后语义变了什么、哪些语义生态给不了（G-1~G-4）。

> ⚠️ **两层的调和规则**：认知层只允许体现在**注释与文档**；**行为层不允许被认知层改写**。看到"更地道的 Java 写法"想动 L1/L2 ⇒ 停，走决策流程。

> **为什么有 RAG-V1？** RAG 行为已反复对齐，但**文件树不是 Spring 的形状**：域内 `api`/`rpc` 是**两个独立进程**、`service/common` 塞了重写 go-zero 运行时的类 + 大量 `GoStyle*`、每个消费者手写 `@Import` 清单。
> ⇒ **存在理由 = 把文件树 Spring 化**：一域一进程 + 包压平（D1 + D7）+ 共享层 starter 化（D3）+ 生态优先（D4）。**行为不重做**（仍以 Go 为唯一依据），但**实现可抄 RAG**（§1.0c）。

### 1.0b 三个仓库的关系（别搞混）

| 仓库 | 路径 | 角色 |
|---|---|---|
| **Sea-RideTheWind_golang** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | **唯一真源** —— 已冻结，所有行为的唯一判定标准（go-zero + GORM） |
| **RAG**（第一版） | `/Users/pr/Documents/java&golang/RAG`（`do-my-own-gh`） | ⚠️ **不是行为基准**（五域 `NOT EQUIVALENT`）—— 但是**实现复用主力来源**（951 文件/157K 行 + 数百测试 + 逐条台账）⇒ **抄实现**（§1.0c） |
| **RAG-V1**（本仓库） | 本地 `/Users/pr/Documents/GitHub/RAG-V1` · GitHub `github.com/KASW-UI/RAG-V1` | **Spring Boot 惯用形态的重做** —— Go 行为用 Spring 生态重新组织（MP 分层 + starter 化 + api/rpc 合并压平，D1 + D7） |

⚠️ **禁止把 RAG 的实现当 Go 的替身**（§3.1 对照陷阱）—— RAG 自身就有 11 处已坐实的回归。**但这不等于「不可抄」** ⇒ 见 §1.0c。

### 1.0c RAG 实现复用：Mapping_Table 的 B↔C 对应就是抄写路线图（2026-10-26 用户定）

> 📌 **「RAG 仓只读参考」的升级**：RAG 侧有 **951 文件 / 157,377 行**已实现代码（本仓 559 个**空壳**）⇒ **从零重写既慢又不如抄**。
> ⇒ **[`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md) 的 B 列 ↔ C 列 = 逐类抄写路线图**；各域 **§0.5** 给出「RAG 的哪个包 → 本仓的哪个包」。

**抄写三档**（按 §3.1 分层）：

| 档 | 内容 | 从哪取 | 纪律 |
|---|---|---|---|
| **① L4 机制层**（Jackson 强转 / Redis key / 雪花 ID / 错误码工厂 / 响应信封） | 可**近乎直抄** | RAG B 列同类 | §3.2 标注 Go 出处（**注释级**，不回 Go 跑） |
| **② L2 业务语义层**（Lua / 事务边界 / outbox 双信封 / 事实投递） | **以 RAG 为基准整段抄**（D13） | RAG B 列 | ⚠️ 继承 RAG 已知漂移（like 域 8 条编号漂移）⇒ 登记为签字偏离；**发现与 Go 冲突才回 Go** |
| **③ L1 契约层**（proto / 错误码值 / HTTP path / JWT claims） | **不可抄** | 只从 Go A 列取 | RAG 侧仅作**反例参考** |

**测试资产单独一档**：RAG 侧 `*WireTest` / `*GoAlignmentTest` / `*RealTomcatTest` 等**测试形态可直接借**（满足 §3.3）⇒ **期望值沿用 RAG**（D13）⇒ ⚠️ **Tomcat 10.1 ≠ 11**（D14 回退）⇒ HTTP 层断言（`Content-Type`/`nosniff`/状态行）要**重跑重校**。

⚠️ **四条硬约束**：① **不整域搬**（RAG 侧 api/rpc 分离 + `Global*` + `*ServiceGrpcImpl` 命名与本仓不兼容）② **不抄 RAG 的 `common/communityfact`**（其台账自陈 5 个可比包**3 个 NOT EQUIVALENT**）③ **RAG 台账比代码更值钱**（其「证据强度」分级本仓尚未建立 ⇒ 借鉴方法论）④ **分层定基准**（D13）：**L1 契约层**（proto / 错误码值 / JWT claims / HTTP path / Kafka topic / 表结构）**以 Go 为准**；**L2/L4 以 RAG 为基准** ⇒ 与 Go 冲突**以 Go 改**。

### 1.0d 工具链口径：**全项目 = Jackson 2 + Boot 3.5**（D14 · 2026-11-02 用户定）

> **全项目适配这条线**：框架 · 驱动 · 中间件客户端 · 测试栈 · 第三方 SDK **全部**在 Jackson 2 + Boot 3.5 上 ⇒ **任何**新代码/新依赖都必须落在这条线上。
> **只 Jackson 2**：业务代码只用 `com.fasterxml.jackson.*`（**2.19.4**）⇒ 与 RAG 仓 187 文件写法一致 ⇒ 抄写零改写。
> **零排除**：Nacos 3.0.3 / MinIO / `jjwt-jackson` / Flyway 都**原生依赖** Jackson 2 ⇒ pom 里**不排除**任何 Jackson 2 · **0 处** `tools.jackson` 引用。
> **版本**：Boot **3.5.11** · Spring Cloud **2025.0.3** · SCA **2025.0.0.0**（原生 nacos-client **3.0.3**）· Flyway **11.14.1** · Tomcat **10.1** · `jjwt-jackson` **可用**。
> **Boot 4 专有 artifact 在 Boot 3.5 不存在**：`spring-boot-kafka`→`org.springframework.kafka:spring-kafka`；`spring-boot-flyway`/`spring-boot-data-redis`/`spring-boot-tomcat` → 撤（Testcontainers 坐标改名见 D14）。
> ⚠️ **D12「全面 Jackson 3」已作废**（保留作历史）⇒ 3 份 Jackson 3 报告结论作废。
> 详细规则与证据 ⇒ [`docs/决策记录.md`](docs/决策记录.md) **D14**（含已知代价）。

> 📦 **RAG 仓历史**（2026-10-20）：真源曾三切 ⇒ 只留一个源（审计：Go 0 缺陷 / RAG 11 处回归）⇒ 蒸馏 ⇒ [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。


## 二、模块阅读指南与目录形状

### 2.1 模块阅读指南

> ⚠️ RAG-V1 无 `HUMAN_ONLY.MD`（RAG 仓产物），**对应物**是 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md) —— **14 份文件树 + `_INDEX.md`**，由 `gen-file-tree.py` **按实际文件系统生成** ⇒ 不与代码漂；每份含 ① 职责注释（javadoc 抽取）② 该模块**「特殊约定（L1 契约 · 勿凭直觉改）」**。
> ⇒ 改完目录结构**必跑** `python3 tools/gen-file-tree.py`。

RAG-V1 的目标架构（落地以 `docs/File_Tree/` 为准）：
`{controller,dto,advice,config,interceptor,metrics,service,grpc,domain,messaging,mapper,client,wiring,exception,contract}` + `resources/db/migration/*.sql`
⇒ **D11**：`dto/` 内**按业务对象再拆一级** `dto/<业务对象>/`（全仓 136 DTO → 26 子包）⇒ 写 DTO 时**落到业务对象子包**，不落平铺 `dto/`。
⇒ 推导全文 ⇒ [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md) §5

### 2.2 目录形状：哪些是 Spring 惯例，哪些是 Go 惯性投影

> **结论**（2026-10-26 目录实测）：RAG-V1 = **Spring Cloud 多模块 + 每域一个独立 Spring Boot app**；**D7 已把域内 `api`/`rpc` 协议层压平** ⇒ 域内是**单层技术角色包树**（包名见 §2.1）。

| 层 | 判定 |
|---|---|
| **模块/构建层** | ✅ 非常像 —— 13 个叶子模块（+2 聚合 pom 嵌套 reactor）；`common/` 是**真 starter**（`AutoConfiguration.imports` 注册 12 个 auto-config） |
| **进程/部署层** | ✅ 完全像 —— 10 个 `*Application.java` + 10 份 `application.yml`（Actuator）+ Flyway + Nacos |
| **包/代码组织层** | ✅ **接近惯例** —— 域内**单层**技术角色包树（包名见 §2.1）⇒ **不再按传输协议纵向切** |

**三个「不是 Spring 惯例」的具体点**（新增目录/包前必问）：
1. **没有 `repository` 层** —— 用 `mapper`（MP `BaseMapper`）+ `domain/entity` ⇒ COLA/DDD 分层；社区默认 `repository`。
2. **`contract/` 是域内包、非独立模块** —— 域错误契约（`ErrCodes`/`ErrMsg`）+ 事件 DTO；proto 另在**根级 `proto/`**（pb 包名 `com.rag.proto.<域>`）。
3. **D1 把 api+rpc 合进同一进程** —— 一个 app 同起 Tomcat 与 gRPC ⇒ 异常处理**必须分两套**：`advice`（`@RestControllerAdvice`）vs `exception` + `GrpcExceptionMapperAutoConfiguration`（§3.5）。

⚠️ **artifactId 未对齐生态惯例**：`common` / `proto` / `users-common` / `*-service`，而非 `xxx-spring-boot-starter` 形态 —— 属 **L4 实现机制，可重选**，**未拍板**。
> ⚠️ **物理目录 ≠ artifactId** —— 目录 `service/users/user/` 的 artifactId 是 `user-service`（reactor 内须唯一 ⇒ 改目录不改坐标）⇒ **别用目录名反推 artifactId**。**形状债已清零（P4-③ · D7）**；**命名债 P4-④ 余 6**（`Rag*` 已清）⇒ [`docs/命名债与包形状.md`](docs/命名债与包形状.md)。

### 2.3 `common/` 的准入（实测 · 有待裁决项）

`common/` 共 **99** 个主代码类 / **12 个包**（**98 已落地 · 1 空壳**：断连探针 1），按包：`web` 21 · `communityfact` 13 · `autoconfigure` 11 · `redis` 5 · `protection` 18 · `observability` 15 · `persistence` 4 · `logger` 5 · `security` 3 · `exception` 2 · `contract` 1 · `snowflake` 1。

🔴 **待裁决**：`communityfact/` 13 类 = **7 个协议 DTO** ＋ **6 个行为类**（`Dispatcher`/`EventDispatcher`/`EventSender`/`AuthorityHandler`/`ProcessLogger`/`Wire`）。行为类**带域归属**（comment·favorite·like 共享）⇒ 与 §五.3 R1「`common/` 只放无域归属能力」**有张力**；10 个 app 全依赖 `common/` ⇒ 改一行锁住 10 域。⇒ **填实现前必须裁决**这 6 类归 `common/` 还是域内（另：G-2 `CanonicalHash` 在此 —— JCS 指纹不一致 ⇒ 幂等失效）

## 三、语义对齐纪律（Spring idiomatization 的唯一防线）
RAG-V1 与 Go 的差异只允许出现在**表达层**。

### 3.1 Go ↔ RAG-V1 对照（强制）

行为一致性审查**按层定基准**（D13）：**L1 契约层**以 Go 为准；**L2/L4 以 RAG 为基准** ⇒ 差异按层判定，不再全量回 Go。
**审查方式**：对每一个行为决策点，读 Go 对应位置的源文件 → 与 RAG-V1 端对应实现**逐字对照**。

**对照必须覆盖**（不限于）：proto 字段号 / message 名 / 错误码语义；JWT claim 名与必需字段（`userId`、`status` 的 presence 语义）；表结构 / 索引 / 唯一约束 / NOT NULL / DEFAULT；行级悲观锁与事务边界；字符串解析失败行为；HTTP 路径与方法。

**避免的对照陷阱**（历史教训）：

- ⚠️ **不要拿「现有 RAG 仓库代码」当对照基准** —— 那里有 **11 处已坐实的回归**（RAG 自身的实现状态），
  对照它会「对齐到错」。**但 RAG 的实现可作「抄写来源」**（§1.0c）—— 抄的是**代码**，**基准永远是 Go**。
- ⚠️ **不要拿代码注释当对照基准**（注释写的是 Java 视角，Go 视角可能不同）
- ⚠️ **不要「读两遍 Go 取并集」**（两遍都错的机会不等于零，必须有一遍回到原文件 line-by-line）
- ⚠️ **不要拿 RAG-V1 自己已写的实现当基准**（自举陷阱：一旦写歪，后面的对照全部对齐到错）

### 3.2 idiomatization 留痕（强制）

RAG-V1 每次选择「用更地道的 Spring 写法」时，必须能回答：**这段比 Go 多做了什么？语义还一样吗？**
回答不了 = 无出处的 idiomatization = **不可 review，不得合入**。落法：写入该类/方法的 javadoc，三段式 ——

```java
/** <p>L4 idiomatization：HandlerInterceptor 取代 Go 的 rest.WithJwt + CheckBlacklistMiddleware。
 * <p>Go 出处：service/user/user/api/internal/handler/routes.go（public 路径不带 JWT）。
 * <p>语义等价论证：public 路径集合逐条一致；blacklist key 前缀与 fail-open 一致；
 *   <b>HTTP 状态码差异</b>：Go 写 401，本实现走异常处理器 —— 见已知差异。 */
```

**11 处历史回归（RAG 仓库实测）全部属于「无出处 idiomatization」** —— 注释写得很完整，唯独没一处标注 Go 出处，review 时无从判断「这是必要表达差异，还是悄悄改了行为」⇒ **RAG-V1 从第一天就要标注 Go 出处**。

### 3.3 框架机制必须验证实际生效（强制）

**3.3.1（2026-10-22，全仓适用）「配置里没写」≠「没开」，也≠「没做」**

Go 侧默认值**藏在 struct tag 里**（不在 yaml），Java 侧**藏在框架里**（不在注解）⇒ 只看配置/注解名双向推断，两头都会错。

⇒ **强制要求**：
1. 判断某个 Go 默认行为是否生效，**必须读到消费该 tag 的那段控制流**，只贴 struct 定义行不算证据；
2. 判断某个 Java 框架能力是否生效，**必须有运行证据**（真实管线 / 真容器），「pom 里有依赖」「类上有注解」都不算；
3. 写 `文件:行号` 证据时，行号指向**消费点**而非**声明点**。

**3.3.2（2026-10-22）以「Go: …」形式写在 `@DisplayName` / javadoc 里的断言，本身是高危项** ⇒ 任何以该措辞出现的断言，必须满足 §3.3.1 的三条，否则**不得**用该措辞。

#### 3.3.3 原有纪律（2026-10-20 起）

**不得凭注解名称断定机制已启用。** Spring Boot 隐式机制（`@Transactional` AOP 代理、`@ResponseBodyAdvice`、MP typeHandler）皆运行期动态装配，出错**不报错**（四类静默失效实证 ⇒ RAG 仓 `docs/Pitfalls/`）⇒ 引入新框架机制必须配一条**能真正触发它**的测试；纯 Mockito 单测**不算**（不执行 SQL、不装配上下文）。

**两条补充（2026-10-21~22 坐实）**：① 判断注入/开关**真没生效不能看启动日志**（echo 照打、零告警，效果却是零）⇒ 唯一可靠判据 = **运行时证据**；② **「Bean 存在」≠「机制生效」**（H2 假绿）·「脚本批量改 N 处」**必须配守恒断言**。

**强制三条**：① 每个 Go 事实带 `文件:行号`，**不许只写结论**；② 凡写「与 Go 一致」必须有**两侧同时跑的探针**（Go 侧调真源解析包，本仓用**真实** mapper）；③ 修漂移**先改/删错误断言，再改实现**（否则会误以为是自己改错）。

⚠️ **「只跑本仓探针」不算数**：它只证明「代码写成了这样」，证不了「Go 是这样」。变异测试是另一半 —— 能证测试**不是假绿**，**证不了期望值对**。两者缺一不可。

**Kafka 自动配置补充（D14 订正）** —— 「只引底层 `spring-kafka` ⇒ `@KafkaListener` 静默不消费 / 缺 `KafkaTemplate` 硬启动失败」是 **Boot 4 现象**（Boot 4 把 Kafka 自动配置拆成独立模块 `spring-boot-kafka`）⇒ **Boot 3.5 无此风险**（`KafkaAutoConfiguration` 在 `spring-boot-autoconfigure` 里）；**纪律不变**：用 `@KafkaListener` 必配**装配层**测试断言 listener 真进 registry，**不得**拿「编译通过/能启动」当「注解已生效」证据（§八）⇒ 见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) §四 K-1（RAG-V1 已修：`common` + 7 个域统一 `org.springframework.kafka:spring-kafka`；7 条已蒸馏坑 ⇒ `docs/Pitfalls/` P-01~P-10）。

### 3.4 proto 是**复制粘贴**，不是翻译（2026-10-25 实测 · 2026-10-26 集中化）

RAG-V1 侧 10 个 `*.proto` **全部从 Go 侧复制**，非翻译。改动**严格限定在 option 块与空白层面**（删 `option go_package` / 加 `java_package` + `java_multiple_files=true` / 缩进统一）。
**message 名 / 字段名 / 字段号 / 类型 / 枚举值 10/10 逐条一致 = 行为层。**
⚠️ **D7 后**：10 个 `.proto` 集中在**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`）；**域内 `contract/` 包**承载该域错误契约与事件 DTO（`task` 域**故意没有** —— Go 侧 task 无错误码空间）。

⚠️ **效力边界（三条，越界即失效）**：① **复制忠实 ≠ 消费方式已验**（只证 `.proto` 一致，**不证**本仓用生成代码的方式同 Go：`optional` presence · 字段号硬编码 · Jackson/protobuf 配置差异）；② **不外推到域内组件**（proto 一致 **推不出** wire 行为一致）；③ **不覆盖范围外的 3 个 proto**（**不是待办**）。
⇒ 逐条对照见 [`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md)（**抄写路线图** §1.0c）与 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md)。

### 3.5 审查范围：RAG-V1 的形态（2026-10-26 · 10 域 / 10 app）

**审查粒度 = 组件级**，不是微服务级。**contract / common 不是「可选项」**（同域对照的默认公理：共享 DTO、错误码契约、wire 契约）—— 少审它们 = **拿一面没人校过的镜子照自己**。

**RAG-V1 的组件清单（权威，审查任务按此拆分）** —— **10 个 app / 16 个模块**：
应用 **10**（`article`·`comment`·`favorite`·`follow`·`hot`·`like`·`message`·`task`·`users/user`·`users/admin` —— **每域一进程，api+rpc 压平，D1+D7**）· 契约 **1**（**根级 `proto/`** —— 10 个 `.proto`；错误契约与事件 DTO 落域内 `contract/`）· 共享 **2**（`common` starter + `users/users-common`）。

⚠️ **落地 ≠ 已对照** ⇒ 逐组件状态见 [`docs/DRIFT/_INDEX.md`](docs/DRIFT/_INDEX.md)（**当前全「⬜ 未建立」**）。**为何不能退到微服务粒度**：域粒度下 `hot` 与 `article` 都会打成 ✅ —— **假绿灯**。

📦 **`global` 收口域（RAG 首创，沿用）** —— **不是第 11 个域，是「收口域」**：修法落共享层、或影响多域的搁置漂移全归它（§五.3）。

## 四、实现组织（真理之源 = Go）

### 4.1 锁定的决策（表达层怎么组织）

**这些决策只决定「表达层怎么组织」，不决定「行为是什么」。** 每一项都必须按 §3.2 标注 Go 出处，并证明语义等价。

| 维度 | 决策 | 依据 |
|---|---|---|
| ORM | **严格**：JPA → MyBatis-Plus（`@TableName` + Mapper `extends BaseMapper<Entity>`） | 目标树写 `@TableName` |
| Logic 类 | **严格**：合并为 1 个 `<域>DomainService` | 目标树描述单一 DomainService，不是多个独立 Logic |
| DataModel | **严格**：删除，按实体拆成多个 Mapper 接口 | 目标树里无 DataModel 痕迹 |

⇒ 依据见 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md) 与 [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md) §5。

### 4.2 RAG-V1 的推进方式（无 phase 划分）

> ⚠️ **RAG-V1 不走 RAG 的「Phase 1–5」路线** —— 那是 RAG 仓从零实现 10 个域时的 review 节奏。RAG-V1 形态不同：**空文件树已落地**（§0），实现按**域**推进。

| 阶段 | 内容 | 完成信号 |
|---|---|---|
| **按域填实现** | 每域：**先按 Mapping_Table B↔C 抄 RAG 实现**（§1.0c 三档）→ L2 以 RAG 为基准（D13）→ 填各技术角色包 → 建该域 DRIFT 台账 | 该域能**独立启动** + 关键路径有**真触发**测试（§3.3）+ 台账写回 |
| **全量收口** | `mvn -o -T1C clean verify` + A1–A12 断言脚本绿 + 对拍脚本绿 | 绿且 `skipped=0`（§五.8 守门四规则） |

每域独立 stop point：跑绿后停下等用户 review，再决定继续。

### 4.3 用户问答原话

> 📖 RAG 仓库的原话存档 ⇒ `docs/DRIFT/_cross/user-qa-verbatim.md`（**在 RAG 仓库里，不在 RAG-V1**）；RAG-V1 自己的拍板记录 ⇒ [`docs/决策记录.md`](docs/决策记录.md)（**D1–D14** 已定 + P1–P7 待拍板）。

## 五、用户协作规则（精简）

1. **每域一个 PR**，PR 自带 spec（提交前已落仓）；issue 在 PR 之前，PR body 引用 issue。⚠️ **RAG-V1 已 `git init`**（2026-10-26，`main`）⇒ PR / 分支 / worktree 适用；并发工作树方法**可借 RAG**（⇒ [`docs/Worktree.md`](docs/Worktree.md)），但基线/远端/目录**按本仓**（`main`/`origin`/`../RAG-V1.worktrees/`）⇒ 不照抄 RAG 的 commit 号与分支名。
2. **三档台账：每域必读 + 必写**（2026-10-23 用户定）—— 开工前**必读** `docs/DRIFT/` + `docs/Intentional_Deviations/` + `docs/Pitfalls/` 的**本域子目录 + `_cross/`**（否则重复已修项 / 误把签字偏离当新漂移「顺手修回」）；产出**必写回** `<台账>/<域>/<组件>.md`。⚠️ **只写自己域**；三个 `_INDEX.md` 合并后统一更新。
3. **`global` 收口域** —— 跨域 + `common/**` 的搁置漂移**全归它**（§3.5）⇒ **`common/**` 只有收口任务能写**，别的域只读；修法同时涉及 `common/` 与别域代码时**必须同一次提交**。
4. **作用范围**：仅限本仓。`Sea-RideTheWind_golang` 已冻结只读；`RAG` 仓**只读参考**（不反写）—— 其**实现可抄进来**（§1.0c）；其他非 Spring Boot 的 Java 实现不在范围内。
5. **基准分层**（D13）。**L1 契约层**（proto 字段号、错误码、JWT claims、表结构、HTTP path、Kafka topic）一律以 Go 对应子模块为准，差异默认判 **RAG-V1 错**；**L2/L4 以 RAG 为基准**，与 Go 冲突以 Go 改。
6. **session 语言**：用户用中文；技术术语、代码、文件路径保留英文。
7. **review**：每域走完 `mvn -pl <module> -am package` + 全量 `mvn -o clean verify` 后等 review，**且必须含 RAG ↔ RAG-V1 对照**（L1 契约层另加 Go ↔ RAG-V1）。
8. **守门脚本 4 硬规则**（RAG 反哺）：① 计数只取**回归日志的模块级汇总**（**不取** `surefire-reports/*.xml` 求和 —— 孤儿报告虚高）② 报「0 failures」须**同断 `skipped=0`** ③ **禁 `@EnabledIfDockerAvailable`** ④ 排除项用**前缀/类别匹配**。

## 六、架构决策与其他留档

> **状态表纪律（2026-10-21）**：任何「已修 / 未修」断言必须带**核对日期**，否则按 `UNKNOWN` 处理。

> ⚠️ **本节原记 RAG 仓的决策过程（📦 存档）** ⇒ 已由 [`docs/决策记录.md`](docs/决策记录.md)（**D1–D14** + P1–P7）取代，**冲突以其为准**；其中「拦截器注册用 `@Import`」RAG-V1 **不用**（D3）。

🔴 **Flyway 账本撞号 —— 尚未决策**：`flyway_schema_history` 是 **schema 级**账本 ⇒ 同库同 schema 只能一本；10 个 `*-service` **都有 `db/migration/`** ⇒ 共用 schema 时**任一 app 先启动，其他 app 的表就永远建不出来**。⇒ **填实现前必须拍板**（独立表名 / 独立 schema / 独立库）；RAG 的定案 = 每模块独立账本表名。

## 七、引用清单

> 📖 **完整索引（文档分工 + 阅读路线 + 状态速查）在 [`docs/README.md`](docs/README.md)**；工具清单在 [`tools/README.md`](tools/README.md)。本节只列**必读**。
### 7.1 真理之源（判定行为）

Go 真源路径见 §1.0b（**已冻结**，不接受写入）。RAG-V1 不是从 Go「推测」行为，而是**逐字对照 Go 源码**（§3.1）。
> 📖 锚点表（路由/事务/行锁/身份/JWT/建表）⇒ RAG 仓 `docs/DRIFT/_cross/go-anchors.md` —— **参考资料，非政策**

### 7.2 动手前必读的 6 份

- **[`docs/决策记录.md`](docs/决策记录.md)** —— **D1–D14** + P1–P6 + 关系图 ⇒ 动结构前必读
- **[`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md)** —— **抄写路线图**（§1.0c：B↔C 逐类对应 + 各域 §0.5 速查表）⇒ 填实现前必读
- **[`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)** —— RAG 的坑 → 本仓处置 ⇒ 填实现前先读
- **[`docs/架构符合性断言.md`](docs/架构符合性断言.md)** —— A1–A12 ⇒ 写测试 / 接框架时
- **[`docs/GORM-MP-语义对照.md`](docs/GORM-MP-语义对照.md)** —— GORM → MP ⇒ 写 Mapper / Entity / 事务时
- **[`docs/Worktree.md`](docs/Worktree.md)** —— 并发工作树方法论（基线/远端/目录按本仓）⇒ 并行开工前必读

**三档台账**（§五.3）：[`docs/DRIFT/`](docs/DRIFT/_INDEX.md) · [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)（P-01~P-10）· [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)
**结构产物**：[`docs/File_Tree/`](docs/File_Tree/_INDEX.md) · [`docs/L3-降级清单.md`](docs/L3-降级清单.md) · [`docs/第二轮深挖-契约盘点.md`](docs/第二轮深挖-契约盘点.md)（G-1~G-4）

### 7.3 工具与孤儿

完整工具清单见 [`tools/README.md`](tools/README.md) 与 [`tools/difftest/README.md`](tools/difftest/README.md)。**改完目录结构必跑** `python3 tools/gen-file-tree.py`（按实际文件系统生成 14 份文件树 + `_INDEX.md` ⇒ 不会与代码漂）。

- **三道守门** —— `check-mapping-coverage.py`（三方对照八重校验）· `check-arch-invariants.py`（**A1–A12**，`--self-test` 自证 **12/12 判据能红**，空壳类 `PENDING` **不算 PASS**）· `check-docs-vs-tree.py`（docs ↔ 文件树对账）· ⬜ 待实现 3 个

> ⚠️ **RAG 仓有 4 个已跑通的守门脚本本仓还没有**：`semantic-probe/` · `errmsg-probe/` · `check-plaintext400-headers.sh` · `check-{grpc-interceptor-wiring,jar-executable,tomcat-limits}.py` ⇒ 移植清单见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。

## 八、新增组件检查清单

> 每次新增域 / 模块 / 共享能力，**逐条过**。RAG-V1 独有。

- [ ] **职责有据** —— 存在理由来自实际职责，不是"Go 里有这个文件"
- [ ] **形状有据** —— 新增目录/包前问：是 **Spring 生态惯例**还是 **go-zero 二分惯性投影**？（§2.2）· 新增 `common/` 类前问：**协议 DTO**（无域归属）还是**行为类**（带域归属）？后者需裁决（§2.3 · R1）
- [ ] **Go 正推** —— 职责清单来自**读 Go 源码**，不从已有实现反推（§3.1）· **抄 RAG 实现时（§1.0c / D13）**：L2 **以 RAG 为基准整段抄**（不逐行回 Go）；L1 契约层仍以 Go 为准
- [ ] **逐域读** —— 同名文件在不同域内容可能完全不同 ⇒ 不得跨域推论
- [ ] **L1 未变** —— 动了 proto / 路由 / method / 错误码值 / 文案 / MQ 字段 ⇒ 停，走决策流程
- [ ] **未统一化** —— 错误码值/文案/语言/method/前缀**逐域隔离**，禁止提取共享基座
- [ ] **L2 已归位** —— 每项业务规则都有明确承载位置
- [ ] **生态优先** —— 先问"Spring/生态是否已提供"，再考虑自研（D4）
- [ ] **starter 优先** —— 共享能力进 `common` 且用 `@AutoConfiguration`，**不写 `@Import` 清单**（D3）
- [ ] **机制可证** —— 引入的新框架机制配一条**真正触发它**的测试（§3.3）
- [ ] **域验收过 verification** —— 每域做完**必过** `.agents/verification.md`（`/Users/pr/Documents/GitHub/RAG-V1/.agents/verification.md`）检测 ⇒ **未过 = 不算做完**（不许报「完成」）
- [ ] **starter 带自动配置** —— 引入 `spring-*-starter` 前确认它在 Boot 3.5 下仍带 `spring-boot-autoconfigure`（Kafka/Redis/Flyway 自动配置在此）
- [ ] **写路径形态已知** —— `updateById(entity)` 还是 `update(null, wrapper)`？两者修法不同
- [ ] **HTTP 断言走真 Tomcat** —— 断言 `Content-Type`/`nosniff`/状态行时不用 MockMvc
- [ ] **守门脚本合规** —— 计数取回归日志汇总 + 断言 `skipped=0` + 排除项用前缀匹配（§五.8）
- [ ] **断言仍绿** —— `check-arch-invariants.py`（A1–A12）+ `check-docs-vs-tree.py`（docs ↔ 文件树）
- [ ] **工具链 = Jackson 2 + Boot 3.5**（D14）—— 只用 `com.fasterxml.jackson.*`（Jackson 2）· pom 里**零** Jackson 2 排除项 · `jjwt-jackson` 可用 · **不引 Boot 4 专有 artifact**
- [ ] **台账已写** —— 偏离写 Intentional_Deviations，踩坑写 Pitfalls，漂移写 DRIFT
- [ ] **文档已更新** —— 「已修/未修」断言必须带**核对日期**，否则按 `UNKNOWN` 处理

> 📖 L1–L4 定义 ⇒ `docs/完全spring化的文件树.md` §0.1 · D1–D13 ⇒ `docs/决策记录.md`
