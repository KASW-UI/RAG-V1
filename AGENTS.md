# AGENTS.md: RAG-V1 项目政策

> 本文件是 RAG-V1 项目唯一由每个 agent 自动加载的根政策文件，**只放政策与索引**。
> **真源 = Go**（`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang`，已冻结；非 Spring Boot 的 Java 实现不在范围内，详见 §1.1）。
> **台账三档**：逐条漂移与修复记录 → [`docs/DRIFT/`](docs/DRIFT/_INDEX.md)·
> 踩坑与元教训 → [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)· 签字偏离 →
> [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)。
>
> 👉 **先读 §一「项目身份与目的」** —— 下面每一条政策都是在为它服务；不知道目的就改不动。
>
> ⚠️ **本文件由 RAG 仓库的 AGENTS.md 改写而来**（2026-10-25）。带 **📦** 标记的段落是 **RAG 仓库的历史存档**（参考，不描述 RAG-V1 的现状）；**RAG-V1 自身的状态一律以 §0 为准。**

## 〇、当前状态（2026-10-26）

**形态：完整空文件树已落地，实现待填**。

> **📌 往哪写** —— 本文件只放**政策与索引**，且必须**全文可进上下文（预算 32 KB）**：逐条漂移 ⇒ `docs/DRIFT/<域>/<组件>.md`；踩坑 ⇒ `docs/Pitfalls/`；签字偏离 ⇒ `docs/Intentional_Deviations/`。**只留结论 + 一行指针。超预算 = 没走这条约定。**

> **核对日期 2026-10-26**（`find` 实测）

| 项 | 数 |
|---|---|
| Java 文件 | **559**（549 主代码 + 10 测试骨架）—— **每个文件只有 package + 空类型 + TODO javadoc**（559/559 全覆盖）；`service/` 503 + `common` 56 |
| Maven 模块 | **16** 个 pom = **13 叶子** + **2 聚合**（`service` / `users`）+ 1 根 pom —— **D7 打平**：每域**一个** jar 模块（`service/<域>/`）；**artifactId 保持长名唯一**（`<域>-service` / `users-common` / `proto` / `common`）⇒ 依赖声明零改动 |
| proto / `application.yml` | **10 / 10**（10 个 `.proto` 集中在**根级 `proto/` 模块**，pb 包名 `com.rag.proto.<域>`；内容与 Go 侧逐字节一致，仅 `java_package` 属表达层） |
| `AutoConfiguration.imports` | **1**（`common/`，注册 7 个 auto-config） |
| `docs/File_Tree/` · 三方对照 | **14 份 + `_INDEX.md`** 文件树（源树路径镜像）· **13 份**域文档（C 列 **549/549**、A 列 **482/482** 覆盖 **100%**；**75 条落点缺口** 🔴13） |
| 三档台账 | DRIFT **10 域全部未建立**（实现开始后逐条填）· Intentional_Deviations **4 条**（like/comment 各 2）· Pitfalls P-01~P-07 |

**已完成**：树结构、pom、proto、application.yml、断言清单、三方对照。**未完成**：全部实现（⇒ 走 §1.0c 从 RAG 抄）。

✅ **2026-10-26 · D7**（proto 集中 + 模块打平 + 包压平）⇒ 详见 [`docs/决策记录.md`](docs/决策记录.md) **D7**；**§1.0c**：RAG 实现可作抄写来源（Mapping_Table B↔C = 路线图）。

> 📌 **RAG 的 158 份台账留在 RAG 仓库** ⇒ RAG-V1 的 `docs/DRIFT/` 从零开始填；**但其「证据强度」分级方法论与已知未修清单值得借鉴**（§1.0c 约束 3）；教训已蒸馏到 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。

> ⚠️ **「已落地」≠「已实现」；「源码级对照」≠「双侧活体对拍」** ⇒ 台账里的 ✅ 一律读作「**在该证据强度下一致**」，不是已闭环。**证据强度以该域台账的「证据强度」列为准**（分层口径见 `docs/DRIFT/_INDEX.md` 末节）。

## 一、项目身份与目的

RAG-V1 仓库路径：`/Users/pr/Documents/GitHub/RAG-V1`。

### 1.0 目的（两层，互不替代）

**一句话**：把冻结的 Go/go-zero 微服务 **Sea-RideTheWind**（识海社区后端），用 **Spring Boot 惯用形态**重新实现一遍 —— **行为对拍等价**，代码按 Java 生态重新组织。

| 项 | 内容 |
|---|---|
| 产品 | **识海**社区后端 —— **内容 + 互动 + 任务 + 用户体系**的一体化服务端；配套推荐系统 `BreakTheWaves` 与前端仓不在范围内 |
| Go 真源 | `Sea-RideTheWind_golang`（**已冻结 · 只读**，绝对路径见 §1.0b）|
| 迁移范围 | **10 个业务域**：article · comment · favorite · follow · hot · like · message · task · users（拆 `user` + `admin`）＝ **10 个 app** + 1 个 `common` starter + 1 个根级 `proto` 模块；**范围外**：`knowledge`/`points`/`security`（依据见 [`docs/决策记录.md`](docs/决策记录.md)） |
| 基础设施 | Go 侧 PostgreSQL · Redis · Kafka · MinIO · Milvus · Neo4j · etcd**Java 侧已接** PostgreSQL + Redis + Nacos |

1. **行为层 —— 严格复刻。** Go 是唯一 oracle。RAG-V1 必须在 **L1 契约 + L2 业务语义**上与 Go 等价；L3 可按已登记降级项降级，L4 可重选；**L1/L2 不可省、不可"顺手优化"**。
2. **认知层 —— 借复刻吃透 Java 生态。** Go 侧 README 自己写的：*"通过亲自复刻、深入实现和持续打磨，真正理解系统设计背后的取舍逻辑与工程方法。"* Java 侧同理：**目的不是把 Go 翻译成 Java，而是借同一套业务逼出每个 Spring 组件的职责边界** —— 谁负责什么、换成生态等价物后语义变了什么、哪些语义生态根本给不了（G-1~G-4）。

> ⚠️ **两层的调和规则**：认知层只允许体现在**注释与文档**；**行为层不允许被认知层改写**。看到"更地道的 Java 写法"想动 L1/L2 ⇒ 停，走决策流程。

> **为什么有 RAG-V1？** RAG 的价值是**行为已反复对齐**；但**它的文件树不是 Spring 的形状**：域内 `api`/`rpc` 是**两个独立进程**、`service/common` 塞了重写 go-zero 运行时的类 + 大量 `GoStyle*`、每个消费者手写 `@Import` 清单。
> ⇒ **RAG-V1 的存在理由 = 彻底把文件树 Spring 化**：一域一进程 + 包压平（D1 + D7）+ 共享层 starter 化（D3）+ 生态优先（D4）。**行为不重做** —— 仍以 Go 为唯一依据；但**实现可抄 RAG**（§1.0c）。

### 1.0b 三个仓库的关系（别搞混）

| 仓库 | 路径 | 角色 |
|---|---|---|
| **Sea-RideTheWind_golang** | `/Users/pr/Documents/java&golang/Sea-RideTheWind_golang` | **唯一真源** —— 已冻结，所有行为的唯一判定标准（go-zero + GORM） |
| **RAG**（第一版） | `/Users/pr/Documents/java&golang/RAG`（分支 `do-my-own-gh`） | ⚠️ **不是行为基准**（五域 `NOT EQUIVALENT`）—— 却是**实现复用的主力来源**（951 文件 / 157K 行实现 + 数百测试 + 逐条 DRIFT 台账）⇒ **抄实现，回 Go 复核语义**（§1.0c） |
| **RAG-V1**（本仓库） | `/Users/pr/Documents/GitHub/RAG-V1` | **Spring Boot 惯用形态的重做** —— Go 行为用 Spring 生态重新组织（MP 分层 + starter 化 + api/rpc 合并压平，D1 + D7） |

⚠️ **禁止把 RAG 的实现当 Go 的替身**（§3.1 对照陷阱）—— RAG 自身就有 11 处已坐实的回归。**但这不等于「不可抄」** ⇒ 见 §1.0c。

### 1.0c RAG 实现复用：Mapping_Table 的 B↔C 对应就是抄写路线图（2026-10-26 用户定）

> 📌 **本条是「RAG 仓只读参考」的升级**：RAG 侧有 **951 文件 / 157,377 行**已实现代码（本仓 559 个**空壳**）⇒ **从零重写既慢又不如抄**。
> ⇒ **[`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md) 的 B 列（RAG）↔ C 列（RAG-V1）对应 = 逐类抄写路线图**；各域 **§0.5「B → C 结构映射速查表」**给出「RAG 的哪个包 → 本仓的哪个包」。

**抄写三档**（按 §3.1 分层）：

| 档 | 内容 | 从哪取 | 纪律 |
|---|---|---|---|
| **① L4 机制层**（Jackson 强转 / Redis key / 雪花 ID / 错误码工厂 / 响应信封） | 可**近乎直抄** | RAG B 列同类 | 回 Go 复核 + §3.2 三段式标注 Go 出处 |
| **② L2 业务语义层**（Lua / 事务边界 / outbox 双信封 / 事实投递） | **只抄骨架**，业务规则回 Go 逐行核对 | RAG B 列 + Go A 列 | RAG 已坐实漂移主要落这层（like 域 8 条编号漂移） |
| **③ L1 契约层**（proto / 错误码值 / HTTP path / JWT claims） | **不可抄** | 只从 Go A 列取 | RAG 侧仅作**反例参考** |

**测试资产单独一档**：RAG 侧 `*WireTest` / `*GoAlignmentTest` / `*RealTomcatTest` 等**测试形态可直接借**（满足 §3.3）⇒ **但期望值必须回 Go 重算**。

⚠️ **四条硬约束**：① **不整域搬**（RAG 侧 api/rpc 分离 + `Global*` 共享 + `*ServiceGrpcImpl` 命名，与本仓结构不兼容）② **不抄 RAG 的 `common/communityfact`**（其台账自陈 5 个可比包**3 个 NOT EQUIVALENT**）③ **RAG 台账比代码更值钱** —— 其「证据强度」分级（源码级对照 / 双侧活体对拍 / 变异验证）本仓尚未建立 ⇒ 借鉴其方法论 ④ **仍以 Go 为唯一行为基准** —— 抄 RAG 只是**加速**，抄来的代码若与 Go 冲突，**以 Go 改**。

### 1.1 三次真理之源切换的结论

> 📦 **RAG 仓库历史存档**（2026-10-20 决策，RAG-V1 沿用其结论）

真源三次切换（10-18 前 Go → 10-18~10-20 Java 镜像 → **10-20 起回到 Go**）。依据：2026-10-20 行为一致性审计实测 —— **Go 0 缺陷，RAG 11 处回归**，且 11 处**全部**集中在「把 Java 写法换成更地道的 Spring 写法」的位置。
⇒ **教训**：多一跳的代价不是「多一次翻译」，而是多一整层可以无声漂移的地方 ⇒ 政策结论：**只留一个源**（行为判定只留 Go；实现可抄 RAG，§1.0c）。逐条清单 ⇒ RAG 仓 `docs/DRIFT/_cross/truth-source-switches.md` ／ 蒸馏见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)

## 二、模块阅读指南与目录形状

### 2.1 模块阅读指南

> ⚠️ **RAG-V1 没有 `HUMAN_ONLY.MD`**（RAG 仓产物），**对应物**是 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md) —— **14 份逐模块文件树 + `_INDEX.md`**，由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂。每份含：① 每个文件的**职责注释**（从 javadoc 抽取）② 该模块的**「特殊约定（L1 契约 · 勿凭直觉改）」**。
> ⇒ 改完目录结构**必跑** `python3 tools/gen-file-tree.py`。

RAG-V1 的目标架构（落地以 `docs/File_Tree/` 为准）：
`{controller,dto,advice,config,interceptor,metrics,service,grpc,domain,messaging,mapper,client,bootstrap,exception,contract}` + `resources/db/migration/*.sql`
⇒ 推导全文 ⇒ [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md) §5

### 2.2 目录形状：哪些是 Spring 惯例，哪些是 Go 惯性投影

> **结论**（2026-10-26 目录实测）：RAG-V1 = **Spring Cloud 多模块 + 每域一个独立 Spring Boot app**；**D7 已把域内 `api`/`rpc` 协议层压平** ⇒ 域内是**单层技术角色包树**（包名见 §2.1）。

| 层 | 判定 |
|---|---|
| **模块/构建层** | ✅ 非常像 —— 13 个叶子模块（+2 聚合 pom 嵌套 reactor）；`common/` 是**真 starter**（`AutoConfiguration.imports` 注册 7 个 auto-config） |
| **进程/部署层** | ✅ 完全像 —— 10 个 `*Application.java` + 10 份 `application.yml`（Actuator）+ Flyway + Nacos |
| **包/代码组织层** | ✅ **接近惯例** —— 域内**单层**技术角色包树（包名见 §2.1）⇒ **不再按传输协议纵向切** |

**三个「不是 Spring 惯例」的具体点**（新增目录/包前必问）：
1. **没有 `repository` 层** —— 用 `mapper`（MP `BaseMapper`）+ `domain/entity` ⇒ COLA/DDD 分层；社区默认是 `repository`。
2. **`contract/` 是域内包、不是独立模块** —— 域错误契约（`ErrCodes`/`ErrMsg`）+ 事件 DTO；proto 另在**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`）。
3. **D1 把 api+rpc 合并进同一进程** —— 一个 app 同起 Tomcat 与 gRPC ⇒ 异常处理**必须分两套**：`advice`（`@RestControllerAdvice`）vs `exception` + `RagGrpcExceptionMapperAutoConfiguration`（§3.5）。

⚠️ **artifactId 未对齐生态惯例**：`common` / `proto` / `users-common` / `*-service`，而非 `xxx-spring-boot-starter` 形态 —— 属 **L4 实现机制，可重选**，**未拍板**。
> ⚠️ **物理目录 ≠ artifactId** —— 目录 `service/users/user/` 的 artifactId 是 `user-service`（reactor 内须唯一 ⇒ 改目录不改坐标）。**不要用目录名反推 artifactId**。**形状债已清零（P4-③ · D7）**；剩余**命名债 P4-④**（22 个 `GoStyle*`）⇒ [`docs/命名债与包形状.md`](docs/命名债与包形状.md)。

### 2.3 `common/` 的准入（实测 · 有待裁决项）

`common/` 共 **56** 个主代码类，按包：`web` 16 · `communityfact` 13 · `autoconfigure` 7 · `persistence` 4 · `logger` 4 · `security` 3 · `observability` 3 · `protection` 2 · `exception` 2 · `contract` 1 · `snowflake` 1。

🔴 **待裁决**：`communityfact/` 13 类 = **7 个协议 DTO** ＋ **6 个行为类**（`Dispatcher`/`EventDispatcher`/`EventSender`/`AuthorityHandler`/`ProcessLogger`/`Wire`）。**行为类带域归属**（comment·favorite·like 三域共享）⇒ 与 §五.3 R1「`common/` 只放跨域共用**且无域归属**的能力」**存在张力**；且 **10 个 app 全部直接依赖 `common/`** ⇒ 改一行要锁住全部 10 域构建。
⇒ **填实现前必须裁决**：这 6 个行为类归 `common/` 还是归域内？（另：G-2 的 `CanonicalHash` 在此 —— JCS 指纹不一致 ⇒ 幂等失效）

## 三、语义对齐纪律（Spring idiomatization 的唯一防线）
RAG-V1 与 Go 的差异只允许出现在**表达层**。

### 3.1 Go ↔ RAG-V1 对照（强制）

任何行为一致性审查必须**以 Go 为唯一基准**核对 RAG-V1。Go 已冻结，行为漂移只可能从 RAG-V1 一侧发生 ⇒ 两方对照足以发现所有行为问题。
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

Go 侧大量行为的默认值**藏在 struct tag 里**而不是 yaml 里；反之，Java 侧大量行为**藏在框架里**而不是注解里。只看配置文件/注解名称双向推断，两头都会错。

⇒ **强制要求**：
1. 判断某个 Go 默认行为是否生效，**必须读到消费该 tag 的那段控制流**，只贴 struct 定义行不算证据；
2. 判断某个 Java 框架能力是否生效，**必须有运行证据**（真实管线 / 真容器），「pom 里有依赖」「类上有注解」都不算；
3. 写 `文件:行号` 证据时，行号指向**消费点**而非**声明点**。

**3.3.2（2026-10-22）以「Go: …」形式写在 `@DisplayName` / javadoc 里的断言，本身是高危项** ⇒ 任何以该措辞出现的断言，必须满足 §3.3.1 的三条，否则**不得**用该措辞。

#### 3.3.3 原有纪律（2026-10-20 起）

**不得凭注解名称断定机制已启用。** Spring Boot 的隐式机制（`@Transactional` AOP 代理、`@ResponseBodyAdvice`、MP 的 typeHandler）全部是运行期动态装配，出错时**不报错**（四类静默失效的实证 ⇒ RAG 仓库 `docs/Pitfalls/`）。
**规则**：凡引入新的框架机制，必须配一条**能真正触发该机制**的测试。纯 Mockito mock 数据层的单测**不算**——它不执行 SQL、不装配 Spring 上下文，天然测不到本节三类问题。

**两条补充规则（2026-10-21~22 坐实）**：① 判断「一个注入/开关有没有真生效」**不能看启动日志** —— 注入点的 echo 照打、jar 照起、日志零告警，效果却是零 ⇒ 唯一可靠的判据是**运行时证据**；② **断言「Bean 存在」≠ 断言「机制生效」**（H2 假绿）；**「脚本批量改 N 处」必须配守恒断言**。

**强制三条**：① 论证里的每个 Go 事实都必须带 `文件:行号`，**不许只写结论**；② 凡写「与 Go 一致」，必须有**两侧同时跑的探针**（不是只跑 RAG-V1）—— Go 侧直接调用真源的解析包，RAG-V1 侧用项目**真实的** mapper；③ 修复漂移时**先改/删错误断言，再改实现** —— 否则会先撞上「测试红了」而误以为自己改错了。

⚠️ **「只跑 RAG-V1 的探针」同样不算数**：它证明的只是「代码写成了这样」，证明不了「Go 是这样」。变异测试是同一问题的另一半 —— 能证明测试**不是假绿**，但**不能证明测试的期望值是对的**。两者缺一不可。

**Boot 4 补充（2026-10-22）** —— Boot 4 把 Kafka 自动配置拆到独立模块 ⇒ **`@KafkaListener` 会静默不消费**（listener 永不扫描 ⇒ 零报错零消费），或注入点缺 `KafkaTemplate` ⇒ 硬启动失败。⇒ **强制**：① 凡用 `@KafkaListener`，必配一条**装配层**测试断言 listener 真进了容器 registry；② **不得**把「编译通过 / 上下文能启动」当作「注解已生效」的证据（另见 §八「Boot 4 未拆走」）。
⇒ RAG-V1 已修（`common` + 7 个域统一 `spring-boot-kafka`）→ [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) §四 K-1；全部 7 条已蒸馏坑 ⇒ [`docs/Pitfalls/_INDEX.md`](docs/Pitfalls/_INDEX.md) P-01~P-07

### 3.4 proto 是**复制粘贴**，不是翻译（2026-10-25 实测 · 2026-10-26 集中化）

RAG-V1 侧 10 个 `*.proto` **全部从 Go 侧复制**，非翻译。改动**严格限定在 option 块与空白层面**（删 `option go_package` / 加 `java_package` + `java_multiple_files=true` / 缩进统一）。
**message 名 / 字段名 / 字段号 / 类型 / 枚举值 10/10 逐条一致 = 行为层。**
⚠️ **D7 后**：10 个 `.proto` 集中在**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`）；**域内 `contract/` 包**承载该域错误契约与事件 DTO（`task` 域**故意没有** —— Go 侧 task 无错误码空间）。

⚠️ **效力边界（三条，越界即失效）**：① **复制忠实 ≠ 消费方式已验** —— 只证明 `.proto` **文件本身**两侧一致，**不证明 RAG-V1 侧使用生成代码的方式**与 Go 一致（`optional` presence 语义、字段号是否被硬编码覆盖、Jackson/protobuf 配置差异）；② **不外推到域内组件** —— proto 一致 **推不出** wire 层行为一致；③ **不覆盖范围外的 3 个 proto**（`knowledge`/`points`/`security`）—— 它们**不是待办**。
⇒ 逐条对照见 [`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md)（**抄写路线图** §1.0c）与 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md)。

### 3.5 审查范围：RAG-V1 的形态（2026-10-26 · 10 域 / 10 app）

**审查粒度 = 组件级**，不是微服务级。**contract / common 不是「可选项」**（同域对照的默认公理：共享 DTO、错误码契约、wire 契约）—— 少审它们 = **拿一面没人校过的镜子照自己**。

**RAG-V1 的组件清单（权威，审查任务按此拆分）** —— **10 个 app / 16 个模块**：
应用 **10**（`article`·`comment`·`favorite`·`follow`·`hot`·`like`·`message`·`task`·`users/user`·`users/admin` —— **每域一进程，api + rpc 合并压平，D1 + D7**）· 契约 **1**（**根级 `proto/` 模块** —— 10 个 `.proto`；错误契约与事件 DTO 落域内 `contract/` 包）· 共享 **2**（`common` starter + `users/users-common`）。

⚠️ **落地 ≠ 已对照** ⇒ 逐组件状态见 [`docs/DRIFT/_INDEX.md`](docs/DRIFT/_INDEX.md)（**当前全部「⬜ 未建立」**）。**为什么不能退到微服务粒度**：域粒度下 `hot` 与 `article` 都会被打成 ✅ —— **那是假绿灯**。

📦 **`global` 收口域（RAG 首创，RAG-V1 沿用）** —— **不是第 11 个微服务域，是「收口域」**：修法落在共享层、或影响多个域的搁置漂移全部归它（§五.3）。

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
| **按域填实现** | 每域：**先按 Mapping_Table B↔C 抄 RAG 实现**（§1.0c 三档）→ 回 Go 复核业务规则 → 填各技术角色包 → 建该域 DRIFT 台账 | 该域能**独立启动** + 关键路径有**真触发**测试（§3.3）+ 台账写回 |
| **全量收口** | `mvn -o -T1C clean verify` + A1–A12 断言脚本绿 + 对拍脚本绿 | 绿且 `skipped=0`（§五.8 守门四规则） |

每域独立 stop point：跑绿后停下等用户 review，再决定继续。

### 4.3 用户问答原话

> 📖 RAG 仓库的原话存档 ⇒ `docs/DRIFT/_cross/user-qa-verbatim.md`（**在 RAG 仓库里，不在 RAG-V1**）；RAG-V1 自己的拍板记录 ⇒ [`docs/决策记录.md`](docs/决策记录.md)（**D1–D7** 已定 + P1–P6 待拍板）。

## 五、用户协作规则（精简）

1. **每域一个 PR**，PR 自带 spec（提交前已落仓）；issue 在 PR 之前，PR body 引用 issue。⚠️ **RAG-V1 已 `git init`**（2026-10-26，`main` 分支）⇒ PR / 分支 / worktree 适用；**别照抄 RAG 的 git 流程**。
2. **三档台账：每域必读 + 必写**（2026-10-23 用户定）—— 开工前**必读** `docs/DRIFT/` + `docs/Intentional_Deviations/` + `docs/Pitfalls/` 的**本域子目录 + `_cross/`**（否则会重复已修项、误把签字偏离当新漂移「顺手修回」）；产出**必写回** `<台账>/<域>/<组件>.md`。⚠️ **只写自己域，三个 `_INDEX.md` 合并后统一更新**；台账结构按 **RAG-V1 的组件名**重建（组件 = 域的 `*-service` / `proto` / `common`）。
3. **`global` 收口域（2026-10-25 用户定）** —— 跨微服务 + `common/` 影响的搁置漂移**全部归它**（§3.5）⇒ **`common/**` 只有收口任务能写**，别的域只读；修法同时涉及 `common/` 与别的域代码时**必须同一次提交**。
4. **作用范围**：仅限 RAG-V1 仓库。`Sea-RideTheWind_golang` 已冻结只读；`RAG` 仓库**只读参考**（不反向同步、不写入）—— 但其**实现可抄进本仓**（§1.0c）；其他非 Spring Boot 的 Java 实现不在范围内。
5. **Go 是真理之源**（全子模块适用）。proto 字段号、错误码、JWT claims、表结构、HTTP path、Kafka topic 一律以 Go 对应子模块为准；差异**只允许出现在表达层**，出现行为差异默认判 **RAG-V1 错**。
6. **session 语言**：用户用中文；技术术语、代码、文件路径保留英文。
7. **review**：每域走完 `mvn -pl <module> -am package` + 全量 `mvn -o clean verify` 后等 review，**且必须含 Go ↔ RAG-V1 对照**。
8. **守门脚本 4 硬规则**（RAG 实测反哺）：① 计数只取**回归日志的模块级汇总**（**不取** `surefire-reports/*.xml` 求和 —— 陈旧孤儿报告虚高）② 报「0 failures」须**同时断言 `skipped=0`** ③ **禁止 `@EnabledIfDockerAvailable`** ④ 排除项用**前缀/类别匹配**，不逐个枚举。

## 六、架构决策与其他留档

> **状态表纪律（2026-10-21）**：任何「已修 / 未修」断言必须带**核对日期**，否则按 `UNKNOWN` 处理。

> ⚠️ **本节记的是 RAG 仓库的决策过程**（📦 存档）—— **RAG-V1 自己的架构决策在 [`docs/决策记录.md`](docs/决策记录.md)**（**D1–D7** + P1–P6）。**冲突时以 `docs/决策记录.md` 为准**。
> **RAG 的四条结论（📦）**：① 丢掉 Flyway 改幂等重放 ② `admin-rpc` 不转发、直连（沿用 ✅）③ 拦截器注册用 `@Import` ⇒ ⚠️ **RAG-V1 不用**（D3）④ **Flyway 账本撞号** ⇒ 见下。

🔴 **Flyway 账本撞号 —— RAG-V1 尚未决策**：`flyway_schema_history` 是 **schema 级**账本 ⇒ 同库同 schema 只能有一本账；10 个 `*-service` **都有 `db/migration/`** ⇒ 若共用一个 schema，**任一 app 先启动，其他 app 的表就永远建不出来**。
⇒ **填实现前必须先拍板**（独立账本表名 / 独立 schema / 独立库）。RAG 的定案是每模块独立账本表名。

## 七、引用清单

> 📖 **完整索引（文档分工 + 阅读路线 + 状态速查）在 [`docs/README.md`](docs/README.md)**；工具清单在 [`tools/README.md`](tools/README.md)。本节只列**必须先读的**。
### 7.1 真理之源（判定行为）

Go 真源仓库路径见 §1.0b（**已冻结**，不接受写入）。RAG-V1 不是从 Go「推测」行为，而是**逐字对照 Go 源码**（§3.1）。
> 📖 锚点表（路由 / 事务 / 行锁 / 身份 / JWT / 建表）⇒ RAG 仓 `docs/DRIFT/_cross/go-anchors.md` —— **参考资料，非政策**

### 7.2 动手前必读的 5 份

- **[`docs/决策记录.md`](docs/决策记录.md)** —— **D1–D7** + P1–P6 + 决策关系图 ⇒ 动结构前必读
- **[`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md)** —— **抄写路线图**（§1.0c）：B 列 RAG ↔ C 列 RAG-V1 的逐类对应 + 各域 **§0.5 B→C 结构映射速查表** ⇒ **填实现前必读**
- **[`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)** —— RAG 的坑 → RAG-V1 的处置 ⇒ **填实现前先读**
- **[`docs/架构符合性断言.md`](docs/架构符合性断言.md)** —— A1–A12 ⇒ 写测试 / 接框架时
- **[`docs/GORM-MP-语义对照.md`](docs/GORM-MP-语义对照.md)** —— GORM → MP 对照表 ⇒ **写 Mapper / Entity / 事务代码时**

**三档台账**（§五.3）：[`docs/DRIFT/`](docs/DRIFT/_INDEX.md) · [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)（P-01~P-07）· [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)
**结构产物**：[`docs/File_Tree/`](docs/File_Tree/_INDEX.md) · [`docs/L3-降级清单.md`](docs/L3-降级清单.md) · [`docs/第二轮深挖-契约盘点.md`](docs/第二轮深挖-契约盘点.md)（G-1~G-4）

### 7.3 工具与孤儿

完整工具清单见 [`tools/README.md`](tools/README.md) 与 [`tools/difftest/README.md`](tools/difftest/README.md)。**改完目录结构必跑** `python3 tools/gen-file-tree.py`（按实际文件系统生成 14 份文件树 + `_INDEX.md` ⇒ 不会与代码漂）。

- `tools/check-mapping-coverage.py` —— 三方对照文档三重校验（A/C 列覆盖率 · `_INDEX.md` 对账 · 映射行计数）
- `tools/difftest/check-arch-invariants.py` —— **架构符合性 A1–A12**；`--contract A|B` 契约绑定 · `--self-test` 造违规假树自证 **12/12 条判据均能红** · 空壳类报 `PENDING`（**不算 PASS**）
- ⬜ 待实现：`check-proto-static.sh`（proto 契约对照）· `check-ddl-static.py`（DDL 列级对照）· `run-parity.sh`（双侧语义探针）

> ⚠️ **RAG 仓库有 4 个已跑通的守门脚本 RAG-V1 还没有**：`semantic-probe/` · `errmsg-probe/` · `check-plaintext400-headers.sh` · `check-{grpc-interceptor-wiring,jar-executable,tomcat-limits}.py` ⇒ **移植清单见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)**。

**Go 侧孤儿（勿照搬）**：`service/user/rpc/internal/logic/`（7 个 `_test.go`，无引用）。

## 八、新增组件检查清单

> 每次新增一个域、一个模块或一个共享能力，**逐条过**。RAG-V1 独有（承接自 RAG-V1 原政策文件）。

- [ ] **职责有据** —— 新文件的存在理由来自实际职责，不是"Go 里有这个文件"
- [ ] **形状有据** —— 新增目录/包前问：这是 **Spring 生态惯例**，还是 **go-zero 二分的惯性投影**？（§2.2）· 新增 `common/` 类前问：是**协议 DTO**（无域归属）还是**行为类**（带域归属）？后者需裁决（§2.3 · R1）
- [ ] **Go 正推** —— 职责清单来自**读 Go 源码**，不是从任何已有实现反推（§3.1）· **抄 RAG 实现时（§1.0c）**：业务规则仍回 Go 逐行核对，RAG 只提供骨架
- [ ] **逐域读** —— 同名文件在不同域内容可能完全不同 ⇒ 不得用一个域的结论推及另一域
- [ ] **L1 未变** —— 若动了 proto / 路由 / method / 错误码值 / 错误文案 / MQ 消息字段 ⇒ 停，走决策流程
- [ ] **未统一化** —— 错误码值/文案/语言/method/前缀**逐域隔离**，禁止提取共享基座
- [ ] **L2 已归位** —— 每项业务规则都有明确承载位置
- [ ] **生态优先** —— 先问"Spring/生态是否已提供"，再考虑自研（D4）
- [ ] **starter 优先** —— 共享能力进 `common` 且用 `@AutoConfiguration`，**不写 `@Import` 清单**（D3）
- [ ] **机制可证** —— 引入的新框架机制配一条**真正触发它**的测试（§3.3）
- [ ] **Boot 4 未拆走** —— 引入 `spring-*-starter` 前，确认它在 Boot 4 下仍在 `spring-boot-autoconfigure` 里
- [ ] **写路径形态已知** —— `updateById(entity)` 还是 `update(null, wrapper)`？两者修法不同
- [ ] **HTTP 断言走真 Tomcat** —— 断言 `Content-Type`/`nosniff`/状态行时不用 MockMvc
- [ ] **守门脚本合规** —— 计数取回归日志汇总 + 断言 `skipped=0` + 排除项用前缀匹配（§五.8）
- [ ] **断言仍绿** —— `tools/difftest/check-arch-invariants.py` 通过（A1–A12）
- [ ] **台账已写** —— 偏离写 Intentional_Deviations，踩坑写 Pitfalls，漂移写 DRIFT
- [ ] **文档已更新** —— "已修/未修"断言必须带**核对日期**，否则按 `UNKNOWN` 处理

> 📖 L1–L4 定义 ⇒ `docs/完全spring化的文件树.md` §0.1 · D1–D7 ⇒ `docs/决策记录.md`
