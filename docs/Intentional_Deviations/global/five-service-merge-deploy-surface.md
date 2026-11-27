# DEV-GLOBAL-MERGE-001 · 五进程形态的「部署面」偏离：域不再是可执行 jar + 配置文件改名 + 进程级命名收敛

> **状态**：🏷 **候选，建议签**（2026-11-28 · S1 · 5 进程形态落地时产生）
> **层**：**L1（部署面）** ＋ **L4（机制层）**
> **域**：`global`（收口域 —— 跨 10 个域的形态变更）
> **组件**：`service/boot/*`（新增 5 个进程模块）＋ `service/*/pom.xml` ＋ `service/*/src/main/resources/application*.yml`
> **触发**：2026-11-27 用户拍板「**10 进程 → 5 进程**」⇒ 计划书 `docs/audit/07-五进程合并执行计划书.md` §一 / §2.6 P1 / P4 / D1–D5。

---

## 一、两侧确实不同（本仓 vs B 侧单域单进程形态）

| 维度 | B 侧（RAG 仓） | C 侧（S1 落地后） |
|---|---|---|
| 部署单元 | **每域 1 个可执行 jar**（api + rpc 两个进程） | **5 个可执行 jar**（boot 模块）＋ **10 个普通 jar**（域模块） |
| 域 jar 可执行性 | ✅ `java -jar <域>-service.jar` 直接跑 | ❌ **域 jar 不再含 `BOOT-INF/`** ⇒ 不可 `java -jar` 直接跑 |
| 配置文件 | 每域 1 份 `application.yml` | 每域 1 份 **`application-<域>.yml`**（改名）＋ 5 份进程级 `application.yml` |
| `spring.application.name` | 每域 1 个（`article-service` / `admin-service` / …） | **每进程 1 个**（`content-service` / `user-service` / `interaction-service` / `hot-service` / `task-service`） |
| `otel.service.name` | 每域 1 个 | **每进程 1 个**（取主域的值） |
| Bean 名 | 每域独立进程 ⇒ 无撞名问题 | **同进程内多域 ⇒ 需显式命名**（`articleWebMvcConfig` / `commentMessageRpcClient` / …） |
| Mapper bean 名 | 每域独立进程 ⇒ 无撞名问题 | 同进程内多域 ⇒ **加域前缀**（`userUserMapper` / `adminUserMapper`） |
| Kafka 容器工厂 | 每域独立进程 ⇒ Boot 自动配置的默认工厂被**域 bean 抑制**（**按名字**判空） | 同进程内多域 ⇒ 各域 factory **加域前缀** ⇒ ⇒ Boot 的默认工厂**冒出来** ⇒ ⇒ 共享层 `KafkaDefaultFactoryAutoConfiguration` **精准删掉它**（见 §五 #6） |
| **测试 support 类名** | 每域独立进程 ⇒ 各域的 `support/TestPostgres` / `support/TestRedis` / `support/NacosStubConfiguration` **同名不撞** | 同进程内多域 ⇒ **同名撞车** ⇒ ⇒ **按域后缀改名**（`TestPostgresLike` / `TestRedisComment` / `NacosStubConfigurationTask` …）⇒ ⇒ **零行为变化**（只改测试类的类名） |

---

## 二、为什么是「偏离」而不是「纯重构」

三条**可观测的外部变化**：

1. **可执行 jar 的路径变了** —— 部署脚本 / 运维手册 / 监控探针里指向「域 jar」的路径全部失效。
   ⇒ 本仓已同步改：`tools/difftest/{favorite,follow,hot,like,task,users-user}/run.sh` 的 `C_JAR`（6 处）改指 boot jar。
   ⇒ ⚠️ **仓库外的部署侧（compose / k8s manifest / CI）不在本仓范围内** ⇒ 需另行同步。
2. **配置文件名变了** —— `application.yml` ⇒ `application-<域>.yml`。
   ⇒ 本仓已同步：`tools/difftest/check-arch-invariants.py` 的 `module_yml()`（先找新名、找不到回退旧名）。
3. **应用名 / 追踪服务名收敛为进程级** ⇒ `spring.application.name` 从 10 个值变 5 个值
   ⇒ ⚠️ **监控与告警按 `application.name` 聚合的规则会变**（原先「按域」聚合，现在「按进程」）。

---

## 三、为什么**不改回去**

- **jar 形态**：域 jar 必须**不** repackage —— 否则 boot 模块**编译期引用不到域的类**
  （实测：javac 报「程序包 com.rag.task.controller 不存在」⇒ 域 jar 的类在 `BOOT-INF/classes/` 下
  ⇒ 不是标准 jar 布局 ⇒ 不可作依赖）⇒ ⇒ **这是 Spring Boot 多模块的标准做法**
  （库模块产普通 jar、可执行模块才 repackage）⇒ 不是可选项。
- **配置文件名**：classpath 上多个**同名** `application.yml` **只有一份生效**
  （实测：`user-service` 进程里 `user-api.*` 生效、**`admin-api.*` 整段丢失**
  ⇒ `AdminApiProperties` 的 fail-closed 守卫抛「access-secret 不能为空」）
  ⇒ ⇒ 不改名 ⇒ **多域合并必然丢配置** ⇒ 改名是**唯一可行解**。
  ⇒ 替代方案「合并成一份大 yml」已被实测否掉：域专属键（`admin-api.*` / `like-api.*` / …）
  会**互相覆盖**（import 顺序 = 后者覆盖前者）⇒ ⇒ 拆成「域 yml + 进程 yml」是必要分层。
- **应用名收敛**：D1 合并的必然结果（一个进程只能有一个 `spring.application.name`）
  ⇒ 取主域的值 ⇒ **已登记为 D1 连带偏离**（与计划书 §二 D1 的偏离清单一致）。

---

## 四、行为面（L1/L2）是否漂移

**否** —— 逐项自查：

| 面 | 结论 |
|---|---|
| HTTP path / method | ✅ **逐字未动**（article 的 `/v1` ⇒ `/article/v1` 属 D5，**已单独登记**） |
| 错误码值 / 文案 | ✅ 未动 |
| JWT claims / secret | ✅ **未动**（仍是 `userId` / 仍是 3 个 secret 变量） |
| 表结构 / 索引 | ✅ 未动（DDL 仍在域模块） |
| Kafka topic | ✅ 未动 |
| gRPC 寻址名 | ⚠️ 属 **P1（注册名 10⇒5）**，**已单独登记**（L1 · 不可灰度） |
| Bean 名 | ⚠️ **变了**（加域前缀）⇒ 但**全部按类型注入** ⇒ ⇒ **运行时行为不变**（全仓已核查：无按名注入 Mapper / client / config 的地方） |
| 配置文件名 | ⚠️ **变了** ⇒ 但**键名与取值逐条未动**（域 yml 是**纯重命名**，git 识别为 `R`） |

---

## 五、连带必须同步的清单（已在本仓完成）

| # | 位置 | 改动 |
|---|---|---|
| 1 | `tools/difftest/{favorite,follow,hot,like,task,users-user}/run.sh` | `C_JAR` 改指 boot jar（6 处） |
| 2 | `tools/difftest/check-arch-invariants.py` | `module_yml()` 兼容新文件名 ＋ 重复键扫描 glob 放宽 |
| 3 | `docs/Mapping_Table/common.md` ＋ `_INDEX.md` | 新增 **4** 个 common 类（`mybatis/` 包 3 个 ＋ `autoconfigure/` 1 个）的 C 列登记 ＋ 自报数字订正（112 ⇒ **116**） |
| 4 | `docs/File_Tree/*` | `python3 tools/gen-file-tree.py` 重跑（13 份 + `_INDEX.md`） |
| 5 | `docs/audit/07-五进程合并执行计划书.md` | §三 S1 任务卡改为「已实施」＋ 记录 **12** 条实测必答题 ＋ S1 回归结论表 |
| 6 | `common/src/main/java/com/rag/common/autoconfigure/KafkaDefaultFactoryAutoConfiguration.java`（**新增**） | 🔴 **移除 Boot 自动配置的默认 Kafka 容器工厂**（`kafkaListenerContainerFactory`）⇒ 详见 §五·附 与 `docs/Pitfalls/_cross/P-32-改bean名导致Boot按名字判空的自动配置失效.md`；⚠️ **为什么必须做**：各域 factory 加域前缀后，Boot 的 `@ConditionalOnMissingBean(name=["kafkaListenerContainerFactory"])`（**按名字**判空）**不再命中** ⇒ **Boot 默认工厂冒出来** ⇒ ⇒ 它的错误处理器**与 B 侧位点提交契约方向相反** ⇒ ⇒ **静默翻转、零报错**；⚠️ **零行为变化**：各域 listener 都**显式点名**了 `containerFactory="<域前缀>…"` ⇒ 删掉的是**没人用的**那个 |
| 7 | 8 个 `@KafkaListener`（`comment`×1 · `like`×1 · `task`×6） | `containerFactory=` 的值同步改成**域前缀名** ⇒ ⚠️ **不改的话会静默命中 Boot 的默认工厂** ⇒ ⇒ 域的错误处理器（位点提交契约）**静默失效** |
| 8 | 2 个直读 yml 的测试（`CommentOversizedBodyRealTomcatTest` / `CommentGrpcInterceptorWiringRealTest`） | 资源路径 `application.yml` ⇒ `application-comment.yml` ⇒ ⚠️ 这类测试**不走 Spring 配置体系** ⇒ 配置改名它们**不会自动跟随** |
| 9 | 2 个产物层守门（`CommentArtifactClasspathGateTest` / `CommentRuntimeBootGateTest`） | 守门对象从「域 fat jar」改指「**interaction 进程**的 fat jar」（域模块不再 repackage ⇒ 域 jar 无 `BOOT-INF/lib/`）⇒ ⇒ **判据等价**（仍验「comment 域跑起来所依赖的真 classpath + 真 yaml 能不能起」） |

### S2 追加（第 10–13 行）

| # | 位置 | 改动 |
|---|---|---|
| 10 | **26 个测试 support 类** ＋ **200 处引用点** | 🔴 **D4 · 按域后缀改名**：`TestPostgresXxx`（7 域）/ `TestRedisXxx`（5 域）/ `NacosStubConfigurationXxx`（9 域）/ `PrometheusTestRegistryConfigurationXxx`（3 域）/ `GrpcServerPortCaptureXxx`（2 域）⇒ ⇒ **零行为变化**（只改测试类的类名与文件路径）⇒ ⇒ 带**守恒断言**（代码里的旧名残留 = 0） |
| 11 | **5 个 boot 启动类**（`service/boot/*/src/main/java/.../*ServiceApplication.java`） | 🔴 **补回 `@ComponentScan` 的隐含 `excludeFilters`**（`TypeExcludeFilter` ＋ `AutoConfigurationExcludeFilter`）⇒ ⇒ 实测：修前 boot 启动类下 `ContentAuditAutoConfiguration` 的 bean 名 = **短名** `contentAuditAutoConfiguration` ⇒ ⇒ **绕过 `.imports` 机制**；修后 = **FQCN** `com.rag.article.client.ContentAuditAutoConfiguration`（与域启动类一致） |
| 12 | `service/boot/content/.../ContentServiceApplicationAssemblyTest.java` | 新增永久断言 `domainAutoConfigurationBeanNameIsFqcn` ⇒ **正反两条都断言**（FQCN **存在** ＋ 短名 **不存在** ⇒ 否则「两个都不存在」也会绿）⇒ ⇒ ✅ **变异实测**：临时撤掉 `excludeFilters` ⇒ 断言**如实转红** ⇒ ⇒ **不是假绿** |
| 13 | **域测试归属**（P3 的落地口径修正） | ⚠️ **P3 原文的「指向 boot 启动类」在 Maven 里不可实现**（实测 `ProjectCycleException` ⇒ **连 test scope 也拒**）⇒ ⇒ 改判为：**域测试留域模块**（测「单域上下文能不能起」）＋ **boot 模块另立装配层测试**（测「合并上下文里域 bean 在不在、他域有没有串进来」）⇒ ⇒ **保住 P3 的意图**（域测试仍可独立跑）**换掉不可行的手法** |

### 附：为什么「删掉 Boot 默认 Kafka 工厂」也算「偏离」

B 侧是 **10 个独立进程** ⇒ 每个进程里只有本域一个 factory ⇒ **不存在**同名撞车、也不存在 Boot 默认工厂
⇒ ⇒ C 侧的 5 进程形态**必然引入**这两个问题 ⇒ ⇒ 修法（域前缀命名 ＋ 精准删除 Boot 默认 factory）
**是形态合并的必然产物** ⇒ 登记在此。

### 附二：为什么「测试 support 类改名」也算「偏离」

B 侧**没有**测试 support 层（测试形态不同）⇒ ⇒ C 侧这层是 C 独有的
⇒ ⇒ 而 5 进程形态让**多个域的同名 support 类进同一 classpath** ⇒ ⇒ **必须改名**
⇒ ⇒ 改名是**形态合并的必然产物** ⇒ 登记在此（D4 · 2026-11-27 用户拍板「统一改名，按域后缀」）。

⚠️ **可观测面**：**测试类的类名与文件路径变了** ⇒ ⇒ 任何**按类名/路径引用**这些类的外部脚本
（如 CI 里单独跑某个测试类、IDE 的 run configuration）需同步改。
⇒ ⇒ 仓库内已全部同步（**26 个文件 + 200 处引用**，并带**守恒断言**：代码里的旧名残留 = 0）。

### 附三：为什么「boot 启动类补 `excludeFilters`」也算「偏离」

⚠️ **不是偏离，是「补回 S1 漏掉的隐含语义」** —— 记录在此是为了说明
**S1 与 S2 两次都踩了同一条坑**（`P-31` §五 ＋ §九）：
⇒ ⇒ **域启动类**（S1 降级）与 **boot 启动类**（S2 新建）**都需要**逐字补回
`@SpringBootApplication` 的 `@ComponentScan` 隐含的两个 `excludeFilters`
⇒ ⇒ 漏了 ⇒ ⇒ **auto-config 的 bean 名从 FQCN 变短名** ⇒ ⇒ **绕过 `.imports` 机制**
⇒ ⇒ **同一份域代码、两种启动入口、两种机制** ⇒ ⇒ 已加永久断言钉住（变异实测能转红）。

---

## 六、⚠️ 仓库外的同步项（本仓管不到，需部署侧跟进）

1. **可执行 jar 路径**：任何指向「域 jar」的启动命令 / compose / k8s manifest。
2. **配置文件名**：若有外部挂载 `application.yml` 的机制 ⇒ 需改为 `application-<域>.yml`。
3. **监控聚合规则**：`spring.application.name` 从 10 个值变 5 个值 ⇒ 按它聚合的面板 / 告警需复核。
4. **Nacos 注册名**：属 P1 ⇒ 5 个进程级名字（`content-rpc` / `user-rpc` / `interaction-rpc` / `hot-rpc` / `task-rpc`）⇒ **不可灰度**（调用方与被调方必须同批发布）。

---

## 七、📌 关联

- 计划书：`docs/audit/07-五进程合并执行计划书.md` §一 1.2 / §二 D1–D5 / §2.6 P1–P6 / §三 S1–S3
- 决策记录：`docs/决策记录.md`（五进程合并形态 = 重大决策 ⇒ **S5-3 回写**）
- 台账三档：`docs/DRIFT/global/`（本条对应「逐条漂移」侧的记录）· `docs/Pitfalls/_cross/`（本条踩到的坑）
