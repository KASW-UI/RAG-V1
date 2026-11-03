# AGENTS.md: RAG-V1 项目政策

> 本文件是 RAG-V1 项目唯一由每个 agent 自动加载的根政策文件，**只放政策与索引**。
> **真源 = B 侧 RAG 仓**（`/Users/pr/Documents/java&golang/RAG`，只读参考；D16 · 2026-11-02 用户定）。
> **台账三档**：逐条漂移与修复记录 → [`docs/DRIFT/`](docs/DRIFT/_INDEX.md)·
> 踩坑与元教训 → [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)· 签字偏离 →
> [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)。
>
> 👉 **先读 §一「项目身份与目的」** —— 下面每一条政策都是在为它服务；不知道目的就改不动。
>
> ⚠️ **本文件由 RAG 仓库的 AGENTS.md 改写而来**（2026-10-25）。带 **📦** 标记的段落是 **RAG 仓库的历史存档**（参考，不描述 RAG-V1 现状）；**RAG-V1 自身状态一律以 §0 为准。**

## 〇、当前状态（2026-11-02）

**形态：共享层 `common/` 已落地 104/104 类；10 个业务域仍全为空壳**。

> **📌 往哪写** —— 只放**政策与索引**，**全文可进上下文（预算 32 KB）**：逐条漂移 ⇒ `docs/DRIFT/<域>/<组件>.md`；踩坑 ⇒ `docs/Pitfalls/`；签字偏离 ⇒ `docs/Intentional_Deviations/`。**只留结论 + 一行指针；超预算 = 没走这条约定。**

> **核对日期 2026-11-02**（`find` 实测）—— **全量状态速查表 ⇒ [`docs/README.md`](docs/README.md) §四**（文件树 / 两方对照 / 实现内容 / 编译 / 工具链 / 台账 六项）。

| 项 | 数 |
|---|---|
| Java 文件 | **670**（624 主代码 + 46 测试）—— `service/` **520 仍全为空壳** + `common/` **104（0 空壳）** |
| Maven 模块 | **16** pom = **13 叶子** + **2 聚合**（`service`/`users`）+ 1 根 —— **D7 打平**：每域**一个** jar 模块（`service/<域>/`）；artifactId 保持长名唯一（`<域>-service`/`users-common`/`proto`/`common`） |
| proto / `application.yml` | **10 / 10**（10 个 `.proto` 集中在**根级 `proto/` 模块**，pb 包名 `com.rag.proto.<域>`；内容与 B 侧逐字节一致，仅 `java_package` 属表达层） |
| `AutoConfiguration.imports` | **1**（`common/`，14 个 auto-config） |
| `docs/File_Tree/` · 两方对照 | **14 份 + `_INDEX.md`** 文件树 · **13 份**域文档（C **624/624**、B **735/752** 覆盖 **97.9%**；**90 条落点缺口** 🔴11） |
| 三档台账 | DRIFT **10 域未建立**（common **已建立**）· Intentional_Deviations **4 条**（like/comment 各 2）· Pitfalls **P-01~P-18** |

**已完成**：树结构、pom、proto、application.yml、断言清单、两方对照、**GitHub 仓**、**工具链（Jackson 2 + Boot 3.5）**、**共享层 `common/`**。**未完成**：10 个业务域的实现（⇒ §1.0c）。
✅ **决策时间线**：**2026-10-26 · D7/D10/D11**（proto 集中/模块打平/包压平 · `bootstrap`→`wiring` · `dto/` 拆子包）· **2026-11-01 · D13**（L2 以 RAG 为基准）· **2026-11-02 · D14**（工具链 = Jackson 2 + Boot 3.5）· **2026-11-02 · D16**（判定基准 = B 侧）· **2026-11-02 · D17**（users 不用 Flyway）· **2026-11-02 · D18**（**Lombok 允许 + 鼓励 · 展开注释强制**）⇒ 全文见 [`docs/决策记录.md`](docs/决策记录.md)。

> 📌 **B 侧的 158 份台账留在 B 仓** ⇒ 本仓 `docs/DRIFT/` 从零填；其**「证据强度」分级方法论可借鉴**（§1.0c 约束 3）；教训见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。
> ⚠️ **「已落地」≠「已实现」；「源码级对照」≠「双侧活体对拍」** ⇒ 台账里的 ✅ 一律读作「**在该证据强度下一致**」，不是已闭环。**证据强度以该域台账的「证据强度」列为准**（见 `docs/DRIFT/_INDEX.md` 末节）。

## 一、项目身份与目的

RAG-V1 仓库路径：`/Users/pr/Documents/GitHub/RAG-V1`。

### 1.0 目的（两层，互不替代）

**一句话**：把 **RAG（B 侧）** 的识海社区后端实现，用 **Spring Boot 惯用形态**重新组织一遍 —— **行为等价**，代码按 Java 生态重新排布。

| 项 | 内容 |
|---|---|
| 产品 | **识海**社区后端 —— **内容 + 互动 + 任务 + 用户体系**一体化服务端；推荐系统 `BreakTheWaves` 与前端仓不在范围内 |
| 真源 | **B 侧 RAG 仓**（`/Users/pr/Documents/java&golang/RAG`，只读；绝对路径见 §1.0b）|
| 迁移范围 | **10 个业务域**：article · comment · favorite · follow · hot · like · message · task · users（拆 `user`+`admin`）= **10 个 app** + 1 个 `common` starter + 1 个根级 `proto` 模块；**范围外**：`knowledge`/`points`/`security` |
| 基础设施 | PostgreSQL · Redis · Kafka · MinIO · Milvus · Neo4j；**Java 侧已接** PG + Redis + Nacos |

1. **行为层 —— 严格复刻。** **B 侧是唯一 oracle**（D16）⇒ 必须在 **L1 契约 + L2 业务语义 + L4 机制**上与 B 等价；**B 侧已有的差异一律继承**（登记为签字偏离），**不回溯第三方**。
2. **认知层 —— 借复刻吃透 Java 生态。** 目的不是把 B 翻译成 Java，而是借同一套业务逼出每个 Spring 组件的**职责边界** —— 谁负责什么、换成生态等价物后语义变了什么、哪些语义生态给不了（G-1~G-4）。

> ⚠️ **两层的调和规则**：认知层只允许体现在**注释与文档**；**行为层不允许被认知层改写**。看到"更地道的 Java 写法"想动 L1/L2 ⇒ 停，走决策流程。
> **为什么有 RAG-V1？** B 侧行为已对齐，但**文件树不是 Spring 的形状**（域内 `api`/`rpc` 两个独立进程 · 大量 `GoStyle*` · 消费者手写 `@Import` 清单）⇒ **存在理由 = 把文件树 Spring 化**：一域一进程 + 包压平（D1 + D7）+ 共享层 starter 化（D3）+ 生态优先（D4）。**行为不重做**，**实现直接抄 B**（§1.0c）。

### 1.0b 两个仓库的关系（别搞混）

| 仓库 | 路径 | 角色 |
|---|---|---|
| **RAG**（B 侧 · 真源） | `/Users/pr/Documents/java&golang/RAG` | **唯一真源** —— 行为与实现的唯一判定标准（Boot 4 形态，api/rpc 分离） |
| **RAG-V1**（本仓库） | 本地 `/Users/pr/Documents/GitHub/RAG-V1` · GitHub `github.com/KASW-UI/RAG-V1` | **Spring Boot 3.5 形态**（Boot **3.5.11**）的惯用重做 —— B 侧行为用 Spring 生态重新组织（MP 分层 + starter 化 + api/rpc 合并压平，D1 + D7）；⚠️ **B 侧是 Boot 4 ⇒ 抄它的实现要按 3.5 的 API 面改写**（§1.0d） |
⚠️ **B 侧只读**（不反写）；**本仓不引入 B 侧之外的实现来源**。

### 1.0c B 侧实现复用：Mapping_Table 的 B↔C 对应就是抄写路线图（2026-10-26 用户定 · D16 改写）

> 📌 **B 侧只读参考**：B 侧有 **951 文件 / 157,377 行**已实现代码（本仓 559 个**空壳**）⇒ **从零重写既慢又不如抄**。
> ⇒ **[`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md) 的 B 列 ↔ C 列 = 逐类抄写路线图**；各域 **§0.5** 给出「B 的哪个包 → 本仓的哪个包」。

**抄写三档**（按 §3.1 分层）—— **① L4 机制层**（Jackson 强转 / Redis key / 雪花 ID / 错误码工厂 / 响应信封）可**近乎直抄**，§3.2 标注 **B 出处**（**注释级**，不回 B 跑）· **② L2 业务语义层**（Lua / 事务边界 / outbox 双信封 / 事实投递）**以 B 侧为基准整段抄**（D13），⚠️ 继承 B 侧已知漂移 ⇒ 登记为签字偏离，**B 侧自身就是基准，不再回溯** · **③ L1 契约层**（proto / 错误码值 / HTTP path / JWT claims / 表结构 / Kafka topic / 端口 / 注册名 / 配置键名）**以 B 侧为准**（D16），B 侧即为契约的**唯一权威副本**。
**测试资产单独一档**：B 侧 `*WireTest` / `*GoAlignmentTest` / `*RealTomcatTest` 等**测试形态可直接借**（满足 §3.3）⇒ **期望值沿用 B 侧**（D13）⇒ ⚠️ **Tomcat 10.1 ≠ 11**（D14 回退）⇒ HTTP 层断言（`Content-Type`/`nosniff`/状态行）要**重跑重校**。

⚠️ **四条硬约束**（全文见 [`docs/决策记录.md`](docs/决策记录.md) **D16**）：① **不整域搬**（B 侧 api/rpc 分离 + `Global*` + `*ServiceGrpcImpl` 命名与本仓不兼容）② **B 侧 `common/communityfact`** 的 5 个可比包**3 个 NOT EQUIVALENT** ⇒ 只抄已判定等价的 2 个，其余登记偏离 ③ **B 侧台账比代码更值钱**（其「证据强度」分级本仓尚未建立 ⇒ 借鉴方法论）④ **分层定基准**（D13 + D16）：**L1/L2/L4 全部以 B 侧为基准** ⇒ 与 B 冲突即 **C 错**。

### 1.0d 工具链口径：**全项目 = Jackson 2 + Boot 3.5**（D14 · 2026-11-02 用户定）

> **全项目适配这条线**：框架 · 驱动 · 中间件客户端 · 测试栈 · 第三方 SDK **全部**在 Jackson 2 + Boot 3.5 上 ⇒ **任何**新代码/新依赖都必须落在这条线上。
> **只 Jackson 2**：业务代码只用 `com.fasterxml.jackson.*`（**2.19.4**）⇒ 与 B 侧 187 文件写法一致 ⇒ 抄写零改写；**零排除**（Nacos 3.0.3 / MinIO / `jjwt-jackson` / Flyway 都**原生依赖** Jackson 2 ⇒ pom 里**不排除**任何 Jackson 2 · **0 处** `tools.jackson` 引用）。
> **版本**：Boot **3.5.11** · Spring Cloud **2025.0.3** · SCA **2025.0.0.0**（原生 nacos-client **3.0.3**）· Flyway **11.14.1** · Tomcat **10.1** · `jjwt-jackson` **可用**。
> **Boot 4 专有 artifact 在 Boot 3.5 不存在**：`spring-boot-kafka`→`org.springframework.kafka:spring-kafka`；`spring-boot-flyway`/`spring-boot-data-redis`/`spring-boot-tomcat` → 撤（Testcontainers 坐标改名见 D14）；⚠️ **D12「全面 Jackson 3」已作废**（保留作历史）⇒ 3 份 Jackson 3 报告结论作废。
> 详细规则与证据 ⇒ [`docs/决策记录.md`](docs/决策记录.md) **D14**（含已知代价）。

### 1.0e Lombok 口径：**允许 + 鼓励**，但**原本的完整代码必须写在注解上**（D18 · 2026-11-02 用户定）

> **一句话**：**能用 Lombok 的语句就用 Lombok**；但**凡被 Lombok 简化掉的「原本的完整代码」，必须以 `/** */` 注释原样写在该 Lombok 注解的正上方**（多注解合并成**一个**展开块，**不许**「其余同上 / getter 略」）。

- **为什么** —— Lombok 属 **L4 机制层**（表达层）⇒ 消除纯样板代码**不改业务语义** ⇒ 不违反 §三；但 B 侧是唯一 oracle（D16）⇒ review 的人必须**在同一个文件里**看到「B 出处 + 最终形态 + Lombok 前的完整代码」⇒ 否则 Lombok 化 = **不可对照的黑盒**（§3.1 的「拿没人校过的镜子照自己」）。
- **禁止** —— **DTO 层**（D5 + **A9** 要 immutable record ⇒ `@Data` 生成 setter ⇒ 违规）· `@Slf4j`（拿不到 `GoStyleLogger` 的 B 侧 `call_chain` 字段 · A8）· `@SneakyThrows`（吞异常 ⇒ 破坏错误契约）。
- **依赖 + 判定** —— 各模块自带 `lombok`（`provided`）+ **显式** `annotationProcessorPaths`（JDK 23+ 否则**静默失效**）⇒ 2026-11-02 已补齐 8 个业务域；落地由 🤖 **A13 机器断言**把关（不靠自觉）。

⇒ **完整规范（逐场景表 / 写法细则 R1–R7 / 展开模板 / 依赖落点 / 正反例）** ⇒ [`docs/Lombok-使用规范.md`](docs/Lombok-使用规范.md)。

> 📦 **RAG 仓历史**（2026-10-20）：真源曾三切 ⇒ 只留一个源 ⇒ 蒸馏 ⇒ [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。

## 二、模块阅读指南与目录形状
### 2.1 模块阅读指南

> ⚠️ RAG-V1 无 `HUMAN_ONLY.MD`（B 仓产物），**对应物**是 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md) —— **14 份文件树 + `_INDEX.md`**，由 `gen-file-tree.py` **按实际文件系统生成** ⇒ 不与代码漂；每份含 ① 职责注释（javadoc 抽取）② 该模块**「特殊约定（L1 契约 · 勿凭直觉改）」**。⇒ 改完目录结构**必跑** `python3 tools/gen-file-tree.py`。

RAG-V1 的目标架构（落地以 `docs/File_Tree/` 为准）：
`{controller,dto,advice,config,interceptor,metrics,service,grpc,domain,messaging,mapper,client,wiring,exception,contract}` + `resources/db/migration/*.sql`
⇒ **D11**：`dto/` 内**按业务对象再拆一级** `dto/<业务对象>/`（136 DTO → 26 子包）⇒ 写 DTO 时**落到业务对象子包**，不落平铺 `dto/`。
⇒ 推导全文 ⇒ [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md) §5
### 2.2 目录形状：哪些是 Spring 惯例，哪些是 B 侧惯性投影

> **结论**（2026-10-26 目录实测）：RAG-V1 = **Spring Cloud 多模块 + 每域一个独立 Spring Boot app**；**D7 已把域内 `api`/`rpc` 协议层压平** ⇒ 域内是**单层技术角色包树**（包名见 §2.1）。
> **三层判定**（模块/构建层 ✅ 非常像 · 进程/部署层 ✅ 完全像 · 包/代码组织层 ✅ 接近惯例 ⇒ **不再按传输协议纵向切**）。

**三个「不是 Spring 惯例」的具体点**（新增目录/包前必问）：① **没有 `repository` 层** —— 用 `mapper`（MP `BaseMapper`）+ `domain/entity` ⇒ COLA/DDD 分层；社区默认 `repository`。② **`contract/` 是域内包、非独立模块** —— 域错误契约（`ErrCodes`/`ErrMsg`）+ 事件 DTO；proto 另在**根级 `proto/`**（pb 包名 `com.rag.proto.<域>`）。③ **D1 把 api+rpc 合进同一进程** —— 一个 app 同起 Tomcat 与 gRPC ⇒ 异常处理**必须分两套**：`advice`（`@RestControllerAdvice`）vs `exception` + `GrpcExceptionMapperAutoConfiguration`（§3.5）。

⚠️ **artifactId 未对齐生态惯例**（`common` / `proto` / `users-common` / `*-service`，而非 `xxx-spring-boot-starter` 形态）—— 属 **L4 实现机制，可重选**，**未拍板**。
> ⚠️ **物理目录 ≠ artifactId** —— 目录 `service/users/user/` 的 artifactId 是 `user-service`（reactor 内须唯一 ⇒ 改目录不改坐标）⇒ **别用目录名反推 artifactId**。**形状债已清零（P4-③ · D7）**；**命名债 P4-④ 余 6**（`Rag*` 已清）⇒ [`docs/命名债与包形状.md`](docs/命名债与包形状.md)。

### 2.3 `common/` 的准入（实测 · 归属已裁决）

`common/` 共 **104** 个主代码类 / **12 个包**（**104 已落地 · 0 空壳**）—— 包级明细 ⇒ [`docs/README.md`](docs/README.md) §四。

✅ **已裁决（2026-11-03 · 用户定）**：`communityfact/` 13 类 = **7 个协议 DTO** ＋ **6 个行为类**（`Dispatcher`/`EventDispatcher`/`EventSender`/`AuthorityHandler`/`ProcessLogger`/`Wire`）。行为类**带域归属**（comment·favorite·like 共享）⇒ 与 §五.3 R1「`common/` 只放无域归属能力」**有张力**；10 个 app 全依赖 `common/` ⇒ 改一行锁住 10 域。⇒ ⇒ **裁决：行为侧 C ≡ B（实测 favorite 两侧都走共享层）⇒ 不是漂移 ⇒ 归属不动** ⇒ `common/communityfact/` **维持现状** ⇒ ⇒ **本条不再阻塞任何后续工作**（治理原则 R1 继续保留，将来若要动归属走映射表 §7.4 #1 的治理流程，**不是漂移修复**）。⇒ 台账：[`docs/DRIFT/common/verification-common-2026-11-02.md`](docs/DRIFT/common/verification-common-2026-11-02.md) **DRIFT-COMMON-V11**（另：G-2 `CanonicalHash` 在此 —— JCS 指纹不一致 ⇒ 幂等失效）
## 三、语义对齐纪律（Spring idiomatization 的唯一防线）
RAG-V1 与 B 侧的差异只允许出现在**表达层**。

### 3.1 B ↔ RAG-V1 对照（强制）

行为一致性审查**按层定基准**（D13 + D16）：**L1 契约层 / L2 业务语义层 / L4 机制层全部以 B 侧为准** ⇒ 差异一律判 **RAG-V1 错**。**审查方式**：对每一个行为决策点，读 B 侧对应位置的源文件 → 与 RAG-V1 端对应实现**逐字对照**。

**对照必须覆盖**（不限于）：proto 字段号 / message 名 / 错误码语义；JWT claim 名与必需字段（`userId`、`status` 的 presence 语义）；表结构 / 索引 / 唯一约束 / NOT NULL / DEFAULT；行级悲观锁与事务边界；字符串解析失败行为；HTTP 路径与方法；端口与服务注册名；配置键名。

**避免的对照陷阱**（历史教训）：⚠️ **不要拿 RAG-V1 自己已写的实现当基准**（自举陷阱：一旦写歪，后面的对照全部对齐到错）· ⚠️ **不要拿代码注释当对照基准**（注释写的是实现者视角，可能已过期 ⇒ 以**代码**为准）· ⚠️ **不要把 B 侧台账里的旧判定当当前结论**（B 侧台账按轮次追加、历史不删 ⇒ 判定前先看它的**「当前态指针」**）。

### 3.2 idiomatization 留痕（强制）

RAG-V1 每次选择「用更地道的 Spring 写法」时，必须能回答：**这段比 B 侧多做了什么？语义还一样吗？**
回答不了 = 无出处的 idiomatization = **不可 review，不得合入**。落法：写入该类/方法的 javadoc，**三段式** —— ① **比 B 侧多做了什么** ② **语义还一样吗** ③ **B 出处**（示例与 Lombok 写法见 [`docs/Lombok-使用规范.md`](docs/Lombok-使用规范.md) §3.3）。

**「无出处 idiomatization」是历史回归的共同形态** —— 注释写得很完整，唯独没一处标注出处，review 时无从判断「这是必要表达差异，还是悄悄改了行为」⇒ **RAG-V1 从第一天就要标注 B 出处**。
⚠️ **Lombok 场景额外要求**：三段式之外，还要按 **§1.0e / D18** 把「原本的完整代码」写成展开注释 ⇒ [`docs/Lombok-使用规范.md`](docs/Lombok-使用规范.md)。

### 3.3 框架机制必须验证实际生效（强制）

**3.3.1（2026-11-02，全仓适用）「配置里没写」≠「没开」，也≠「没做」**

B 侧实现里的默认值**藏在框架与配置绑定里**（不在注解上），Java 侧**藏在框架里**（不在注解）⇒ 只看配置/注解名双向推断，两头都会错。

⇒ **强制要求**：
1. 判断某个默认行为是否生效，**必须读到消费该配置/注解的那段控制流**，只贴声明行不算证据；
2. 判断某个 Java 框架能力是否生效，**必须有运行证据**（真实管线 / 真容器），「pom 里有依赖」「类上有注解」都不算；
3. 写 `文件:行号` 证据时，行号指向**消费点**而非**声明点**。

**3.3.2（2026-11-02）以「B 侧: …」形式写在 `@DisplayName` / javadoc 里的断言，本身是高危项** ⇒ 任何以该措辞出现的断言，必须满足 §3.3.1 的三条，否则**不得**用该措辞。

**3.3.3 原有纪律（2026-10-20 起）** —— **不得凭注解名称断定机制已启用**：Spring Boot 隐式机制（`@Transactional` AOP 代理、`@ResponseBodyAdvice`、MP typeHandler）皆运行期动态装配，出错**不报错**（四类静默失效实证 ⇒ B 仓 `docs/Pitfalls/`）⇒ 引入新框架机制必须配一条**能真正触发它**的测试；纯 Mockito 单测**不算**（不执行 SQL、不装配上下文）；判断注入/开关**真没生效不能看启动日志**（echo 照打、零告警，效果却是零）⇒ 唯一可靠判据 = **运行时证据**；**「Bean 存在」≠「机制生效」**（H2 假绿）·「脚本批量改 N 处」**必须配守恒断言**。
**强制三条**：① 每个事实带 `文件:行号`，**不许只写结论**；② 凡写「与 B 侧一致」必须有**两侧同时跑的探针**；③ 修漂移**先改/删错误断言，再改实现**（否则会误以为是自己改错）。
⚠️ **「只跑本仓探针」不算数**：它只证明「代码写成了这样」，证不了「B 侧是这样」。变异测试是另一半 —— 能证测试**不是假绿**，**证不了期望值对**。两者缺一不可。
**Kafka 补充（D14 订正）** —— 「只引底层 `spring-kafka` ⇒ `@KafkaListener` 静默不消费」是 **Boot 4 现象** ⇒ **Boot 3.5 无此风险**；**纪律不变**：用 `@KafkaListener` 必配**装配层**测试断言 listener 真进 registry，**不得**拿「编译通过/能启动」当「注解已生效」证据（§八）⇒ 详见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) §四 K-1。

### 3.4 proto 是**复制粘贴**，不是翻译（2026-10-25 实测 · 2026-10-26 集中化）

RAG-V1 侧 10 个 `*.proto` **全部从 B 侧复制**，非翻译。改动**严格限定在 option 块与空白层面**（删 `option go_package` / 加 `java_package` + `java_multiple_files=true` / 缩进统一）⇒ **message 名 / 字段名 / 字段号 / 类型 / 枚举值 10/10 逐条一致 = 行为层**。
⚠️ **D7 后**：10 个 `.proto` 集中在**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`）；**域内 `contract/` 包**承载该域错误契约与事件 DTO（`task` 域**故意没有** —— B 侧 task 无错误码空间）。
⚠️ **效力边界（三条，越界即失效）**：① **复制忠实 ≠ 消费方式已验**（只证 `.proto` 一致，**不证**本仓用生成代码的方式同 B 侧：`optional` presence · 字段号硬编码 · Jackson/protobuf 配置差异）；② **不外推到域内组件**（proto 一致 **推不出** wire 行为一致）；③ **不覆盖范围外的 3 个 proto**（**不是待办**）。
⇒ 逐条对照见 [`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md) 与 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md)。

### 3.5 审查范围：RAG-V1 的形态（2026-10-26 · 10 域 / 10 app）

**审查粒度 = 组件级**，不是微服务级。**contract / common 不是「可选项」**（同域对照的默认公理：共享 DTO、错误码契约、wire 契约）—— 少审它们 = **拿没人校过的镜子照自己**。
**组件清单（权威，审查任务按此拆分）** —— **10 个 app / 16 个模块**：应用 **10**（`article`·`comment`·`favorite`·`follow`·`hot`·`like`·`message`·`task`·`users/user`·`users/admin` —— **每域一进程，api+rpc 压平，D1+D7**）· 契约 **1**（**根级 `proto/`** —— 10 个 `.proto`；错误契约与事件 DTO 落域内 `contract/`）· 共享 **2**（`common` + `users/users-common`）。⚠️ **落地 ≠ 已对照** ⇒ 逐组件状态见 [`docs/DRIFT/_INDEX.md`](docs/DRIFT/_INDEX.md)。**为何不能退到微服务粒度**：域粒度下 `hot` 与 `article` 都会打成 ✅ —— **假绿灯**。
📦 **`global` 收口域（RAG 首创，沿用）** —— **不是第 11 个域，是「收口域」**：修法落共享层、或影响多域的搁置漂移全归它（§五.3）。

## 四、实现组织（真理之源 = B 侧）

### 4.1 锁定的决策（表达层怎么组织）

**这些决策只决定「表达层怎么组织」，不决定「行为是什么」。** 每一项都必须按 §3.2 标注 B 出处，并证明语义等价。

| 维度 | 决策 | 依据 |
|---|---|---|
| ORM | **严格**：JPA → MyBatis-Plus（`@TableName` + Mapper `extends BaseMapper<Entity>`） | 目标树写 `@TableName` |
| Logic 类 | **严格**：合并为 1 个 `<域>DomainService` | 目标树描述单一 DomainService |
| DataModel | **严格**：删除，按实体拆成多个 Mapper 接口 | 目标树里无 DataModel 痕迹 |
| **样板代码** | **Lombok**（**允许 + 鼓励**）—— 但**原本的完整代码**必须以 `/** */` 写在注解正上方 | **D18** · §1.0e |

⇒ 依据见 [`docs/File_Tree/`](docs/File_Tree/_INDEX.md) 与 [`docs/完全spring化的文件树.md`](docs/完全spring化的文件树.md) §5。

### 4.2 RAG-V1 的推进方式（无 phase 划分）

> ⚠️ **RAG-V1 不走 B 仓的「Phase 1–5」路线** —— 那是 B 仓从零实现 10 个域时的 review 节奏。RAG-V1 形态不同：**空文件树已落地**（§0），实现按**域**推进。

| 阶段 | 内容 | 完成信号 |
|---|---|---|
| **按域填实现** | 每域：**先按 Mapping_Table B↔C 抄 B 侧实现**（§1.0c 三档）→ 各层均以 B 侧为基准（D13 + D16）→ 填各技术角色包 → 建该域 DRIFT 台账 | 该域能**独立启动** + 关键路径有**真触发**测试（§3.3）+ 台账写回 |
| **全量收口** | `mvn -o -T1C clean verify` + A1–A13 断言脚本绿 + 对拍脚本绿 | 绿且 `skipped=0`（§五.8 守门四规则） |

每域独立 stop point：跑绿后停下等用户 review，再决定继续。

### 4.3 用户问答原话

> 📖 B 仓的原话存档 ⇒ `docs/DRIFT/_cross/user-qa-verbatim.md`（**在 B 仓里，不在 RAG-V1**）；RAG-V1 自己的拍板记录 ⇒ [`docs/决策记录.md`](docs/决策记录.md)（**D1–D18** 已定 + P1–P7 待拍板）。

## 五、用户协作规则（精简）

1. **每域一个 PR**，PR 自带 spec（提交前已落仓）；issue 在 PR 之前，PR body 引用 issue。⚠️ **RAG-V1 已 `git init`**（2026-10-26，`main`）⇒ PR / 分支 / worktree 适用；并发工作树方法**可借 B 仓**（⇒ [`docs/Worktree.md`](docs/Worktree.md)），但基线/远端/目录**按本仓**（`main`/`origin`/`../RAG-V1.worktrees/`）。
2. **三档台账：每域必读 + 必写**（2026-10-23 用户定）—— 开工前**必读** `docs/DRIFT/` + `docs/Intentional_Deviations/` + `docs/Pitfalls/` 的**本域子目录 + `_cross/`**（否则重复已修项 / 误把签字偏离当新漂移「顺手修回」）；产出**必写回** `<台账>/<域>/<组件>.md`。⚠️ **只写自己域**。
3. **`global` 收口域** —— 跨域 + `common/**` 的搁置漂移**全归它**（§3.5）⇒ **`common/**` 只有收口任务能写**，别的域只读；修法同时涉及 `common/` 与别域代码时**必须同一次提交**。
4. **作用范围**：仅限本仓。`RAG`（B 侧）仓**只读参考**（不反写）—— 其**实现可抄进来**（§1.0c）。
5. **基准统一**（D13 + D16）—— **L1 / L2 / L4 一律以 B 侧为准** ⇒ 差异默认判 **RAG-V1 错**。
6. **session 语言**：用户用中文；技术术语、代码、文件路径保留英文。
7. **review**：每域走完 `mvn -pl <module> -am package` + 全量 `mvn -o clean verify` 后等 review，**且必须含 B ↔ RAG-V1 对照**。
8. **守门脚本 4 硬规则**（B 仓反哺）：① 计数只取**回归日志的模块级汇总**（**不取** `surefire-reports/*.xml` 求和 —— 孤儿报告虚高）② 报「0 failures」须**同断 `skipped=0`** ③ **禁 `@EnabledIfDockerAvailable`** ④ 排除项用**前缀/类别匹配**。

## 六、架构决策与其他留档

> **状态表纪律（2026-10-21）**：任何「已修 / 未修」断言必须带**核对日期**，否则按 `UNKNOWN` 处理。

> ⚠️ **本节原记 B 仓的决策过程（📦 存档）** ⇒ 已由 [`docs/决策记录.md`](docs/决策记录.md)（**D1–D18** + P1–P7）取代，**冲突以其为准**；其中「拦截器注册用 `@Import`」RAG-V1 **不用**（D3）。

🔴 **Flyway 账本撞号 —— users 域已定（D17），其余 7 域待拍板**：`flyway_schema_history` 是 **schema 级**账本 ⇒ 共用 schema 时**任一 app 先启动，其他 app 的表就永远建不出来**。✅ **users 域 = 不用 Flyway**（照抄 B 侧：`SchemaInitializer` 幂等重放 `db/01..04_*.sql`）⇒ 无版本账本 ⇒ **不撞号**。🟠 **其余 7 域仍待拍板**：B 侧做法 = **各域独立账本表名**（`flyway_schema_history_<域>`）+ `baseline-on-migrate: true` + **`baseline-version: 0` 必钉**；⚠️ 本仓现状 = 7 个域 pom 已引 Flyway + yml **无 `spring.flyway` 段**（全部用默认表名）⇒ **写域迁移脚本前必须先定**。

## 七、引用清单

> 📖 **完整索引（文档分工 + 阅读路线 + 状态速查）在 [`docs/README.md`](docs/README.md)**；工具清单在 [`tools/README.md`](tools/README.md)。本节只列**必读**。
### 7.1 真理之源（判定行为）

B 侧 RAG 仓路径见 §1.0b（**只读**，不接受写入）。RAG-V1 不是从 B 侧「推测」行为，而是**逐字对照 B 侧源码**（§3.1）。
> 📖 B 侧台账（`docs/DRIFT/` 158 份）**留在 B 仓** —— 本仓只借鉴其**「证据强度」分级方法论**与**元教训**，不搬其判定结论。
### 7.2 动手前必读的 7 份

| 文档 | 何时读 |
|---|---|
| **[`docs/决策记录.md`](docs/决策记录.md)** —— **D1–D18** + P1–P7 + 关系图 | 动结构前 |
| **[`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md)** —— **抄写路线图**（§1.0c） | 填实现前 |
| **[`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)** —— B 侧的坑 → 本仓处置 | 填实现前 |
| **[`docs/架构符合性断言.md`](docs/架构符合性断言.md)** —— A1–A13 | 写测试 / 接框架时 |
| **[`docs/GORM-MP-语义对照.md`](docs/GORM-MP-语义对照.md)** —— B 侧持久层写法 → 本仓 MP 用法 | 写 Mapper / Entity / 事务时 |
| **[`docs/Worktree.md`](docs/Worktree.md)** —— 并发工作树方法论 | 并行开工前 |
| **[`docs/Lombok-使用规范.md`](docs/Lombok-使用规范.md)** —— **D18** 展开注释强制 | 写实体 / 用 Lombok 时 |

**三档台账**（§五.3）：[`docs/DRIFT/`](docs/DRIFT/_INDEX.md) · [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)（P-01~P-18）· [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)
**结构产物**：[`docs/File_Tree/`](docs/File_Tree/_INDEX.md) · [`docs/L3-降级清单.md`](docs/L3-降级清单.md) · [`docs/第二轮深挖-契约盘点.md`](docs/第二轮深挖-契约盘点.md)（G-1~G-4）

### 7.3 工具与孤儿

完整工具清单见 [`tools/README.md`](tools/README.md) 与 [`tools/difftest/README.md`](tools/difftest/README.md)。**改完目录结构必跑** `python3 tools/gen-file-tree.py`（按实际文件系统生成 14 份文件树 ⇒ 不会与代码漂）。
- **三道守门** —— `check-mapping-coverage.py`（两方对照八重校验）· `check-arch-invariants.py`（**A1–A13**，`--self-test` 自证判据能红，空壳类 `PENDING` **不算 PASS**）· `check-docs-vs-tree.py`（docs ↔ 文件树对账）· ⬜ 待实现 3 个
> ⚠️ **B 仓有 4 个已跑通的守门脚本本仓还没有**：`semantic-probe/` · `errmsg-probe/` · `check-plaintext400-headers.sh` · `check-{grpc-interceptor-wiring,jar-executable,tomcat-limits}.py` ⇒ 移植清单见 [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md)。

## 八、新增组件检查清单

> 每次新增域 / 模块 / 共享能力，**逐条过**。RAG-V1 独有。**每条都带出处（展开说明见括号里的章节）。**

- [ ] **职责有据** —— 存在理由来自实际职责，不是"B 侧里有这个文件"
- [ ] **形状有据** —— 是 **Spring 生态惯例**还是 **B 侧二分惯性投影**？（§2.2）· 新增 `common/` 类：协议 DTO（无域归属）还是行为类（带域归属）？后者需裁决（§2.3 · R1）
- [ ] **B 侧正推** —— 职责清单来自**读 B 侧源码**，不从已有实现反推（§3.1）· 抄 B 侧实现时各层**均以 B 侧为基准**（§1.0c / D13 / D16）
- [ ] **逐域读** —— 同名文件在不同域内容可能完全不同 ⇒ 不得跨域推论
- [ ] **L1 未变** —— 动了 proto / 路由 / method / 错误码值 / 文案 / MQ 字段 / 端口 / 注册名 ⇒ 停，走决策流程
- [ ] **未统一化** —— 错误码值/文案/语言/method/前缀**逐域隔离**，禁止提取共享基座
- [ ] **L2 已归位** —— 每项业务规则都有明确承载位置
- [ ] **生态优先** —— 先问"Spring/生态是否已提供"，再考虑自研（D4）
- [ ] **Lombok 展开注释**（D18）—— 用了 Lombok ⇒ **原本的完整代码**以 `/** */` 写在**该注解正上方**（不许「其余同上」）⇒ 机器判定 = **A13**
- [ ] **starter 优先** —— 共享能力进 `common` 且用 `@AutoConfiguration`，**不写 `@Import` 清单**（D3）
- [ ] **机制可证** —— 引入的新框架机制配一条**真正触发它**的测试（§3.3）
- [ ] **域验收过 verification** —— 每域做完**必过** `.agents/verification.md` ⇒ **未过 = 不算做完**
- [ ] **starter 带自动配置** —— 引入 `spring-*-starter` 前确认它在 Boot 3.5 下仍带 `spring-boot-autoconfigure`（Kafka/Redis/Flyway 自动配置在此）
- [ ] **写路径形态已知** —— `updateById(entity)` 还是 `update(null, wrapper)`？两者修法不同
- [ ] **HTTP 断言走真 Tomcat** —— 断言 `Content-Type`/`nosniff`/状态行时不用 MockMvc
- [ ] **守门脚本合规** —— 计数取回归日志汇总 + 断言 `skipped=0` + 排除项用前缀匹配（§五.8）
- [ ] **断言仍绿** —— `check-arch-invariants.py`（A1–A13）+ `check-docs-vs-tree.py`（docs ↔ 文件树）
- [ ] **工具链 = Jackson 2 + Boot 3.5**（D14）—— 只用 `com.fasterxml.jackson.*` · pom 里**零** Jackson 2 排除项 · **不引 Boot 4 专有 artifact**
- [ ] **台账已写** —— 偏离写 Intentional_Deviations，踩坑写 Pitfalls，漂移写 DRIFT
- [ ] **文档已更新** —— 「已修/未修」断言必须带**核对日期**，否则按 `UNKNOWN` 处理

> 📖 L1–L4 定义 ⇒ `docs/完全spring化的文件树.md` §0.1 · D1–D18 ⇒ `docs/决策记录.md`
