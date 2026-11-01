# RAG-V1 文档索引

> **本仓库**：RAG 仓（B 侧）既有 Java 实现的 **Spring Boot 惯用形态**重做产物。
> **唯一真理**：**B 侧（RAG 仓）** —— 行为判定的**唯一基准**；本仓一切实现以 B 侧逐条对照为准。
> **根政策**：[`../AGENTS.md`](../AGENTS.md) —— 每个 agent 自动加载，**先读它**。
> **⚙️ 全项目工具链 = Jackson 2 + Spring Boot 3.5**（D14 · 2026-11-02 用户定）—— 框架 · 驱动 · 中间件客户端 · 测试栈 · 第三方 SDK **全部**适配这条线 ⇒ 任何新代码 / 新依赖都必须落在这条线上；口径 ⇒ [`决策记录.md`](决策记录.md) **D14** + `../AGENTS.md` §1.0d。

---

## 一、文档分工

| 文档 | 管什么 | 什么时候看它 |
|---|---|---|
| [`../AGENTS.md`](../AGENTS.md) | **根政策**：**项目身份与目的**、真源与对照纪律、语义对齐纪律、协作规则、台账三档制、新增组件检查清单 | **每次开工前** |
| [`../包职责速查表.md`](../速查表/包职责速查表.md) | **包职责速查表** —— 域内 15 个技术角色包 + 3 个 `domain/` 子包 + `common/` 12 个包 + 根级模块的一句话职责（**已对磁盘实测 100% 一致**） | **新建类前先查：这个职责该放哪个包** |
| [`File_Tree/_INDEX.md`](File_Tree/_INDEX.md) | **逐模块文件树（13 份 + `_INDEX.md`）** —— 由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。目录是**源树路径的镜像**（`service/users/user` → `File_Tree/users/user.md`；`service/article` → `File_Tree/article.md`），含每个文件的职责注释 + 该模块的 L1 特殊约定 + **artifactId（≠ 目录名）** | 想知道某个模块长什么样；改结构后核对 |
| [`完全spring化的文件树.md`](完全spring化的文件树.md) | **推导全文**：B 侧原始树 → 职责矩阵 → 职责映射表 → 理论 Java 树 → 文件变化表 → 五问自检 → 覆盖检查 → 符合性断言 | 判定某个组件该长什么样、查某条决策的推导依据 |
| [`深挖修正记录.md`](深挖修正记录.md) | **B 侧正推后的 8 处修正**（method / 前缀 / 错误契约形态 / 码值文案语言 / DTO / MQ 契约 …）+ **5 条方法论纪律** + 6 项未完成待办 | **先读这份** —— 它记录了第一轮推导的方法缺陷（从 RAG 反推真源）与纠正过程 |
| [`第二轮深挖-契约盘点.md`](第二轮深挖-契约盘点.md) | **第二轮盘点（T-1~T-6）**：跨服务事件协议 `communityfact` / 15 组指标完整名 / 10 域鉴权划分 / 配置块 / 2 处 B 侧真缺陷 / 1 个新的生态缺口 + 5 项未盘点 | 判断"这个字段/这个指标/这条事件"该不该动时 |
| [`决策记录.md`](决策记录.md) | **D1–D14**（已定，含依据/代价/可回退性）+ **P1–P7**（待拍板）+ 决策间关系图（**D14 = 全项目工具链 Jackson 2 + Boot 3.5**） | 动结构前；或有人要回退某项决策时 |
| [`命名债与包形状.md`](命名债与包形状.md) | **形状债（✅ 已清零 · D7）+ 命名债（P4-④/P4-⑤ · 已改 23 个，余 6 个）** —— 含压平前后的**形态对照表**与实测代价 | 决定「彻底 Spring 化」还差什么时；动包路径前 |
| [`L3-降级清单.md`](L3-降级清单.md) | **4 项降级**（D-01~D-04）+ **6 项保留**（K-01~K-06）+ 变更登记规则 | 对拍出现差异时；或有人要"修回旧文案/旧策略"时 |
| [`架构符合性断言.md`](架构符合性断言.md) | **A1–A12** + 契约绑定矩阵 + RAG 现状的 2 颗哑弹 | 写测试 / 接新框架 / 质疑"这个结构对不对"时 |
| [`RAG-困境反哺.md`](RAG-困境反哺.md) | **RAG 已踩的坑 → RAG-V1 的处置**：12 条困境 + 4 条已避开 + 1 条 **Boot 4 专属**坑（Kafka 拆模块 ⇒ D14 后本仓不适用）+ RAG 自己的台账矛盾 | **填实现之前先读** —— RAG 花了 3 天 / 2109 条测试换来的结论 |
| **持久层语义对照表**（B 侧 MP → 本仓 MP · 8 条坑 + 速查表）—— 文件：`docs/` 目录下的 `*MP-语义对照.md`（索引见根 [`README.md`](../README.md)） | **写任何 Mapper / Entity / 事务代码时** |
| [`Worktree.md`](Worktree.md) | **并发工作树方法论**（建树 / 分支命名 / 基线核对 / 收口合回 / 冲突处置）—— 坐标已改写为 RAG-V1（基线 `main` · 远端 `origin` · 目录 `../RAG-V1.worktrees/`）；**不含** RAG 的 G1~G4 波次与收官历史 | **并行开工多个域之前必读**；每域收口回主干时对照 |
| [`SpringBoot4-Jackson3-Migration-Cost.md`](SpringBoot4-Jackson3-Migration-Cost.md) | ⛔ **已作废（D14）** —— 原为「Jackson 2 → 3 迁移成本评估」（206 个命中文件分类 ⇒ 真要动的是 **76 个**；增量 **13.5~27 人天**）—— **本仓已回退到 Jackson 2 + Boot 3.5 ⇒ 该评估不适用**（全文保留作历史存档） | **查历史决策过程时**；现行口径看 **D14** |
| [`SpringBoot4-Jackson3-Jackson2-Inventory.md`](SpringBoot4-Jackson3-Jackson2-Inventory.md) | ⛔ **已作废（D14）** —— 原为「Jackson 2 使用全量清单」（全扫 RAG 仓 1,381 个 `.java` ⇒ 命中 208 个，**逐个列出** + 7 种迁移模式分类）—— **本仓保持 Jackson 2 ⇒ 不需迁移**（全文保留作历史存档） | **查 RAG 侧 Jackson 使用面**时（回改 26 个 Jackson 3 文件要参照） |
| [`MinIO-Jackson2-Isolation-Experiment.md`](MinIO-Jackson2-Isolation-Experiment.md) | ⛔ **已作废（D14）** —— 原为「MinIO × Jackson 2 隔离实验」（排除 `jackson-databind` 后 MinIO SDK 7 个 API 仍可用）—— **D14 起 MinIO 的 Jackson 2 是同代原生依赖 ⇒ 不排除**（全文保留作历史存档） | **查「为什么当初要排除」时**；现行口径看 **D14** |
| [`Mapping_Table/_INDEX.md`](Mapping_Table/_INDEX.md) | **逐类文件名对照**（13 份域文档，**一域一文件夹** + 汇总索引；每份含 **§0.5「B → C 结构映射速查表」**）—— C 列 **589/589**、A 列 **482/482** 覆盖 100%，**B 列双向对账 + 指代消解已上线**（§六·五 登记 4 条已知例外），并登记 **90 条落点缺口** | **填实现前查「这个类从哪来」；查树里还有哪些职责没落点** |
| [`DRIFT/_INDEX.md`](DRIFT/_INDEX.md) | **漂移档**：与 B 侧不一致的逐条留档 | 判定某模块是否已对齐 |
| [`Intentional_Deviations/_INDEX.md`](Intentional_Deviations/_INDEX.md) | **有意偏离档**：明知不同、经签字批准 | 判断某条差异「要不要修」时 |
| [`Pitfalls/_INDEX.md`](Pitfalls/_INDEX.md) | **踩坑档**：为什么会踩（静默失效 / 绿测试锁死错误答案 / 基础设施误用） | 踩坑复现时；写新测试前 |

---

## 二、三档台账的关系

```
「RAG-V1 与 B 侧不一致」        → DRIFT/          （待查）
「明知不同、签字批准」          → Intentional_Deviations/  （决策，不是缺陷）
「为什么会踩」                 → Pitfalls/       （元教训）
「主动放弃某一层语义」         → L3-降级清单.md  （不可反向修回）
```

⚠️ **四者不可混谈。** 把降级项写进 Intentional_Deviations，会让它看起来"可以修回"。

---

## 三、阅读路线

| 你要做的事 | 读 |
|---|---|
| **新 session 开工** | `../AGENTS.md` → `决策记录.md` → 本域 DRIFT 台账 |
| **并行开工多个域** | `Worktree.md`（建树 / 基线核对 / 分支命名）→ `../AGENTS.md` §五.1（域验收必过 `.agents/verification.md`） |
| **实现某个组件** | `完全spring化的文件树.md` §4 映射表 + §5 树 → 本域 DRIFT 台账 → **B 侧源码逐字对照** |
| **对拍出现差异** | `L3-降级清单.md`（先排除降级项）→ DRIFT 台账 |
| **改结构** | `决策记录.md`（**D1–D14** + 依赖关系图）→ `完全spring化的文件树.md` §0.3 |
| **质疑某段代码不像 Spring** | `命名债与包形状.md`（剩余命名债 P4-④）→ `决策记录.md` P4 → `架构符合性断言.md`（A1–A12）→ D4 |
| **加新模块/新共享能力** | `../AGENTS.md` §8 检查清单 → `决策记录.md` D3（starter 优先） |
| **写 Mapper / Entity / 事务** | **持久层语义对照表**（`docs/*MP-语义对照.md`）→ **B 侧对应 Mapper / Entity 逐字对照** |
| **碰共享层 `common/`** | `RAG-困境反哺.md` §二·R1（准入与归属规则） |
| **查工具链口径** | `决策记录.md` **D14**（Jackson 2 + Boot 3.5 版本矩阵 + 落地状态）→ `../AGENTS.md` §1.0d ⇒ 三份 Jackson 3 报告**已作废**，仅作历史 |

---

## 四、当前状态速查

| 项 | 状态 |
|---|---|
| 文件树 | ✅ 完整落地（**615** Java = 591 主代码 + 24 测试 / **16** pom / **10** proto（根级 `proto/` 模块）/ 10 `application.yml`）—— **D7 打平后**（proto 集中 + 模块打平 + 包压平） |
| 三方对照 | ✅ **13 份域文档**（`docs/Mapping_Table/<域>/<域>.md`，**按域分文件夹**；users 域拆四份：`_shared.md` + `users-common.md` + `user/user.md` + `admin/admin.md`）—— C 列 **624/624**、A 列 **482/482** 覆盖 **100%**；每份含 **§0.5「B → C 结构映射速查表」**；另登记 **90 条落点缺口**（🔴11 / 🟠 / 🟡） |
| 实现内容 | 🟡 **共享层已开工** —— `common/` **104 类**中 **104 类已落地**（2026-11-02 批 1 = 49 类 ＋ 批 2 = 5 类 ＋ **批 3 = D4 生态重写 11 类** ＋ **批次 B = gRPC 保护链 5 层 + 客户端侧 5 类** ＋ **批次 D = `security/` 3 类** ＋ **批次 E = 8 类** ＋ **批次 C = 2 类** ＋ **批次 F = 全局 OTel 装配 3 类** ＋ **批次 G = 0 类（只补证据：真 PG 对拍 `JsonbTypeHandler` / `JsonTextTypeHandler`）** ＋ **批次 H = 0 类新类（修真实漂移：REST 侧降载 filter 改走 `AdaptiveShedder`）**）· **0 类空壳**⇒ **口径按 2026-11-02 审核实测校正**（原写 68/3）⇒ 见 [`DRIFT/common/verification-common-2026-11-02.md`](DRIFT/common/verification-common-2026-11-02.md)；**10 个业务域仍全为空壳**<br>✅ `common/` 已是**真 starter**（`AutoConfiguration.imports` 注册 **14 行** · 消费方零 `@Import`）⇒ **断言 A3 由 PENDING 转 PASS**（gRPC 拦截链真挂 `server.intercept(...)`）· §3.3 真触发测试 **36 类 / 224 例**（含 **真 PostgreSQL** 对拍 10 例 + **真 Spring 上下文**的熔断参数钉桩 2 例）|
| proto 契约 | ✅ 与 B 侧逐字节一致（仅 `java_package` 属表达层）；**10 个 `.proto` 集中在根级 `proto/` 模块**，pb 包名 `com.rag.proto.<域>` |
| 编译 | ✅ `mvn -o clean test-compile` 全仓 **16/16 BUILD SUCCESS**（protoc 离线可跑） |
| Kafka 装配 | ✅ 已适配 Boot 3.5（`common` + 7 个域统一 `org.springframework.kafka:spring-kafka`；**Boot 3.5 的 `KafkaAutoConfiguration` 在 `spring-boot-autoconfigure` 里 ⇒ 无 Boot 4 的拆模块缺口**）—— 见 `RAG-困境反哺.md` §四 K-1 |
| 断言脚本 | ⬜ 待实现（A1–A4 已手工跑通） |
| 台账 | ⬜ 三档已建索引 —— Intentional_Deviations 已有 **5** 条（like 2 · comment 2 · article 1）；**DRIFT 10 域仍为空**、Pitfalls **P-01~P-10**（实现开始后逐条填） |
| **工具链** | ✅ **D14 已定并落地（2026-11-02）** —— **全项目 = Jackson 2 + Spring Boot 3.5**：Boot **3.5.11** · Jackson **2.19.4** · Spring Cloud **2025.0.3** · SCA **2025.0.0.0**（原生 nacos-client **3.0.3**）· Flyway **11.14.1** · Tomcat **10.1** · `jjwt-jackson` **可用** ⇒ **13 个 pom 已改造**，`mvn -o clean test-compile` **16/16 BUILD SUCCESS**；⚠️ **D12「全面 Jackson 3」已作废**（三份 Jackson 3 报告结论作废，文件留档） |
| **lombok** | ✅ **已抄入（2026-11-02 用户定）** —— `common/` = **provided**、`service/users/` 聚合 = **optional**（RAG 同款落点），**注解处理器显式配 `annotationProcessorPaths`**（JDK 21+ 兜底）⇒ 字节码实测真生效；另补 `maven-enforcer-plugin` **构建期守门**（禁 `protobuf-java`/`javalite` ≤ 3.25.4，变异测试证明能红）—— 见 `决策记录.md` **D14 落地状态之三** |
| **`common/` 搬运** | 🟡 **批 1 + 批 2 已落地（2026-11-02）** —— 批 1 = **49 类**（纯抄 + 1:1/N:1 落点）+ 2 个跨批依赖载体（锁 **L-15/L-16**）；批 2 = **5 类**（Jackson 3→2 + Spring 7→6.2 回改，锁 **L-17**）；**`common/src/test/` 由 0 → 8 类 / 67 用例（§3.3 真触发）**；`mvn -o clean test` **16/16 BUILD SUCCESS** · 三道守门 **EXIT=0**；⚠️ **批 3 未开工** · **真 Tomcat 守门未写** —— 见 `决策记录.md` **D14 落地状态之四 / 之五** |
| 待拍板 | ⬜ P1–P7 + **P4-④**（P4-③ ✅ 已实施，见 `决策记录.md` D7 · `命名债与包形状.md`） |
