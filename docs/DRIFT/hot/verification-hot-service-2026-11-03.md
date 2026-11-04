# hot 域迁移审核报告（RAG Boot 4 → RAG-V1 Boot 3.5）

> **由 `align/hot` 工作树产出** · **基线 commit `0a35865`**（本地 `main`）· 分支 `align/hot`
> **核对日期**：2026-11-03（**§九 为 2026-11-04 补做**）
> **审核依据**：`.agents/verification.md`（八阶段 + §十四 12 项必答）
> **写权**：本报告只写 `docs/DRIFT/hot/**`；`docs/DRIFT/_INDEX.md` · `docs/Intentional_Deviations/_INDEX.md` ·
> `docs/Pitfalls/_INDEX.md` · `AGENTS.md` · `common/**` **一律不碰**（AGENTS.md §五.3 规则 3）
>
> 📌 **本轮范围**：**hot 域**（B 侧 `service/hot/` 四模块 → C 侧 `service/hot/` 单模块）·
> **31 个主代码类**从空壳落地为实现 ＋ **15 个测试类 / 70 例**新写。
> ⚠️ 本报告**不**对其它 9 个域做判断。
>
> 📌 **本文件覆盖到的证据强度：E-4（双侧活体对拍）** —— 分级定义见
> [`docs/DRIFT/_INDEX.md`](../../DRIFT/_INDEX.md) 末节「证据强度分级（E-0~E-4）」：
> **E-4 = 31 条断言（B 侧 Boot 4 与 C 侧 Boot 3.5 同时活体、同请求、逐字节相等）**，见 **§九**；
> **E-3** = 真中间件 / 真容器（§二 的承接表逐条标注）；**E-2** = 源码级对照（机器断言只证明「C 侧没跑偏」，**不**证明「B 侧就是这些值」）。
> ⚠️ **E-4 的边界**：判定条件是 **C == B**，**没有 Go 锚**（本机无 Sea-etcd 容器 ⇒ Go 真源无法活体）⇒ ⇒ **不读作「三方一致」**（§九·9.1）。

---

## 一、审核范围

### 1.1 两个项目的角色

| 标识 | 项目 | 路径 | 作用 |
|---|---|---|---|
| **B** | RAG | `/Users/pr/Documents/java&golang/RAG`（**只读**） | **唯一行为基准**（D16）· Spring Boot **4.0.8** 形态 |
| **C** | RAG-V1 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/hot` | 被审对象 · Spring Boot **3.5.11** 形态 |

> 📌 **B 侧是真源，不是「另一种语言」** —— B 侧的类名几乎全带 `GoStyle*` 前缀
> （`GoStyleGrpcCallDeadline` / `GoFormValues`），但其 `pom.xml` 的 parent 是
> `spring-boot-starter-parent:4.0.8` ⇒ **B 侧是 Java/Spring Boot 4**，不是 Go。
> （本条与 article 域 U-3 的教训同源：曾因类名带 `GoStyle` 而误判 oracle 侧的技术栈。）

### 1.2 审核的模块与文件规模（**实测**，非旧报告数字）

| 项 | B 侧 | C 侧 | 备注 |
|---|---|---|---|
| 模块数 | **4**（`hot-common` / `hot-api` / `hot-rpc` / `hot-proto`） | **1**（`service/hot`，artifactId `hot-service`）+ 根级 `proto/` | **D1** api+rpc 合并 + **D7** proto 集中化 |
| 主代码 `.java` | **34**（hot-api 17 + hot-common 2 + hot-rpc 15） | **31** | 映射表 C 列 **31/31 = 100%** |
| 测试 `.java` | **51** | **15**（8 个测试类 + 6 个 support + 1 个探针配置） | 测试资产**另算一档**（§1.0c） |
| 配置文件 | `hot-api/application.yml` + `hot-rpc/application.yml` | `service/hot/src/main/resources/application.yml` | **两份合一**（D1） |
| proto | `hot-proto/src/main/proto/hot.proto` | `proto/src/main/proto/hot.proto` | 逐字节一致（仅 option 块表达层差异） |
| 数据库表 | **零**（无 Entity / 无 Mapper / 无 DDL） | **零** | 两域皆「纯 Redis + Kafka」域 |

> 📌 **C 侧 31 的算式（逐条可核）** = **34 − 4（删除/上收）− 1（两个入口 N:1 合并）＋ 2（C 新增）**
> · **删除/上收 4 个**：`HotRpcClient`（**D2** 域内直连）· `GoStyleGrpcCallDeadline`（落进 `HotApiProperties` ＋ `application.yml`）·
>   `GoFormValues`（**上收 `common/web/GetFormValuesFilter`**）· `Jackson2ObjectMapperConfiguration`（Boot 4 拆模块补丁 ⇒ 共享层 `JsonBodyConverterAutoConfiguration` 承担）；
> · **N:1 合并 1 个**：`HotApiApplication` ＋ `HotRpcApplication` → **1 个** `HotServiceApplication`；
> · **C 新增 2 个**：`GrpcErrorMapper`（B 侧**按可达性刻意不建**，C 建的是「B 侧原本不可达」的映射器 ⇒ §五·F-05）·
>   `WebMvcConfig`（B 侧无对应文件；C 侧**刻意保留空实现**以显式化「免鉴权」⇒ §五·F-04）。

### 1.3 版本口径（**实际解析值**，非 POM 声明值）

| 组件 | B 侧 | C 侧（`dependency:list` 实测） | 判定 |
|---|---|---|---|
| Spring Boot | **4.0.8** | **3.5.11** | ✅ D14 既定决策 |
| Spring Framework | 7.0.10 | **6.2.16** | ✅ 由 Boot 3.5.11 BOM 锁定 |
| Tomcat | 11.x | **10.1.52** | ⚠️ **状态行不发 reason phrase** ⇒ §五·F-09 |
| Jackson | **3.x**（`tools.jackson.*`） | **2.19.4**（`com.fasterxml.jackson.*`） | ✅ D14；**代码层 0 处 `tools.jackson` 引用** |
| Spring Cloud | 2025.1.0 | **2025.0.3** | ✅ |
| Spring Cloud Alibaba | 2025.1.0.0 | **2025.0.0.0**（nacos-client **3.0.3**） | ✅ |
| Spring Kafka | `spring-boot-kafka`（**Boot 4 专有**）⇒ 用 `org.springframework.kafka:spring-kafka` | **spring-kafka 3.3.13** | ✅ D14：**Boot 3.5 无 `spring-boot-kafka`** ⇒ 坐标回改 |
| gRPC（net.devh / grpc-java） | 3.1.0 / 1.63.0 | **3.1.0 / 1.63.0** | ✅ 同版本 |
| Spring Data Redis / Lettuce | — | **3.5.9 / 6.6.0.RELEASE** | ✅ |
| MyBatis-Plus | 3.5.17 | **3.5.17** | ✅ 同版本（**本域无表**） |

> ✅ **零 Boot 4 专有 artifact**：`spring-boot-kafka` 在 Boot 3.5 线**客观不存在**
> ⇒ 本域改引 `org.springframework.kafka:spring-kafka` 是**必需**，不是可选。
> ✅ **零 Jackson 2 exclusion**：nacos-client 3.0.3 / minio / flyway 均原生依赖 Jackson 2 ⇒ pom 里不排除任何 Jackson 2。

### 1.4 使用的资料与代码版本

| 项 | 值 |
|---|---|
| Mapping_Table | [`docs/Mapping_Table/hot/hot.md`](../../Mapping_Table/hot/hot.md)（519 行 · 56 行映射 · §7.3 类型分布 1:1=44 / 1:N=8 / N:1=3 / C 新增=1） |
| File_Tree | [`docs/File_Tree/hot.md`](../../File_Tree/hot.md)（**本轮按工作树重跑生成器刷新** —— 见 §一·1.6） |
| 决策记录 | [`docs/决策记录.md`](../../决策记录.md)（D1 / D2 / D3 / D5 / D7 / D9 / D11 / D13 / D14 / D16 / D18） |
| 基础 commit | `0a35865`（本地 `main`）· 工作树内改动**未提交**（`git status`：**34 改 + 5 新增目录**） |
| 架构断言 | [`docs/架构符合性断言.md`](../../架构符合性断言.md)（A1–A13） |

### 1.5 本轮的验证读数（**全部真跑**，非静态推断）

| 读数 | 值 | 命令 |
|---|---|---|
| **hot 域回归** | **70 例 · Failures 0 · Errors 0 · Skipped 0** | `mvn -o -pl service/hot -am package` |
| common 回归 | **241 例 · Failures 0 · Errors 0 · Skipped 0** | 同上（`-am` 带上游） |
| **全仓合计** | **311 例 · 0/0/0 · Skipped 0 · BUILD SUCCESS**（hot 70 ＋ common 241） | 取回归日志的**模块级汇总**（AGENTS §五.8 规则 ①：日志行 `Tests run: 70, Failures: 0, Errors: 0, Skipped: 0`，**不取** `surefire-reports/*.xml` 求和） |
| 逐类分布 | `HotApiSupportContractTest` **10** · `HeavyKeeperContractTest` **9** · `HotHttpRealTomcatTest` **9** · `HotMergeSchedulerRealRedisTest` **9** · `HotRedisSnapshotStoreRealRedisTest` **8** · `HotEventConsumerRealKafkaTest` **7** · `HotGrpcServiceRealGrpcTest` **7** · `HotErrorContractTest` **7** · `HotServiceApplicationTests` **4** | 同上（取回归日志的**逐类汇总行**） |
| **打包** | `hot-service-1.0-SNAPSHOT.jar` **110 MB 可执行 Boot jar**（含 `org/springframework/boot/loader/`）⇒ **P-18 的 `repackage` 失败已消解** | 同上 |
| 守门 ① | `check-mapping-coverage.py` ⇒ **EXIT=0**（**C 列 641/641 · B 列 752/752 · B 列全局未覆盖 0**）<br>**hot 行 C 31/31 · B 34/34 · 100% ✅** | — |
| 守门 ② | `check-arch-invariants.py` ⇒ **EXIT=0**（PASS 10 · FAIL 0 · N/A 2 · PENDING 1）<br>📌 **A4 PASS**（`@GrpcClient` 6 处 · yml `grpc.client` 6 处 · 全部 plaintext）· **A5 PASS**（0 个 `@Import`）· **A13 PASS**（38/38 个 Lombok 类均带展开块）· **A12 PASS**（无模块内同名类） | PENDING 属 comment/favorite 域空壳，**与 hot 无关** |
| 守门 ③ | `check-docs-vs-tree.py` ⇒ **EXIT=0**（File_Tree 双向 · 幂等 · 路径 · 落点计数 · 包名全绿） | — |
| **变异验证（零重试判据）** | ✅ **有牙齿**：把 `HotKafkaConfig` 的 `FixedBackOff(0,0)` 改成 `FixedBackOff(0,9)` ⇒ `HotEventConsumerRealKafkaTest#containerDoesNotRetryFailedRecord` **转红**（逐字：`expected: 1 but was: 10`）⇒ ⇒ 还原后回绿 | 见 §四·4.5 |
| **变异验证（分页溢出判据）** | ✅ **有牙齿**：`HotApiSupportContractTest` 用 `paginate(100, 1073741825, 4)` 把 **int32 乘法溢出**钉死（改成长数运算即红） | — |
| **变异验证（错误契约判据）** | ✅ **有牙齿**：给 `ErrMsg` 的 `"internal server error"` 加一个<b>尾随空格</b> ⇒ `HotErrorContractTest` **7 例里 4 例转红**（逐字：`文案漂移：码 5001 ==> expected: <internal server error> but was: <internal server error >`）⇒ ⇒ 还原后回绿 | — |

### 1.6 ⚠️ 本轮踩到并消解的一个工具坑：`gen-file-tree.py` 的 ROOT 是硬编码的

> **症状**：在 worktree 里跑 `python3 tools/gen-file-tree.py` ⇒ 打印「DONE 13 个模块文档」，
> **但 worktree 的 `docs/File_Tree/hot.md` 的 mtime 纹丝不动**。
>
> **根因**：`tools/gen-file-tree.py:25` 是 `ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")`
> ⇒ **硬编码到主工作区** ⇒ 在 worktree 里跑，量的是**主仓那棵树**（主仓的 hot 域此刻仍只有
> 空壳测试 `HotServiceApplicationTests.java`）⇒ ⇒ 产物写进了**主仓**（本轮实测：内容逐字节相同 ⇒ **主仓未脏**）。
>
> **处置**：**临时**把 ROOT 改到工作树 ⇒ 重跑 ⇒ 只留 `docs/File_Tree/hot.md` 的差异 ⇒
> **还原工具文件**（`tools/` 不在本域写权内）。⚠️ 顺带发现 `docs/File_Tree/proto.md` 也会变
> ⇒ 差异来源是**主仓里一个未跟踪的空目录** `proto/src/test/java/`（git 不跟踪空目录）
> ⇒ 与 hot 无关 ⇒ **已还原**。
>
> 📌 **对照**：`tools/difftest/check-arch-invariants.py:86` 与 `tools/difftest/check-docs-vs-tree.py:26`
> 用的是 `pathlib.Path(__file__).resolve().parents[2]` ⇒ **相对 ROOT** ⇒ 这两个守门脚本**量的是工作树** ✅。
> ⚠️ `tools/check-mapping-coverage.py:32` 同样是**硬编码 ROOT** ⇒ 本轮的映射覆盖读数
> **量的是主仓的映射表**（主仓与工作树的映射表内容一致 ⇒ 读数等价，但**归因要写准**）。

---

## 二、B → C 责任承接

> 状态词取自 `.agents/verification.md` §十：**PASS / FAIL / NOT_IMPLEMENTED / NOT_VERIFIED /
> FRAMEWORK_DIFFERENCE / BLOCKED / INTENTIONALLY_CHANGED**。
> ⚠️ **FRAMEWORK_DIFFERENCE / NOT_VERIFIED 不计入 PASS**（该文件 §十 明文）。

### 2.1 契约层（错误契约 + proto）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据强度 | 证据 |
|---|---|---|---|---|---|---|---|---|
| C-01 | `hot-common/.../errmsg/HotErrorCode.java` | 11 个业务码常量（**枚举顺序即契约**） | `contract/ErrCodes.java` | 1:1 | ✅ | **PASS** | **E-2** | 码值逐条抄自 B 侧 `:41-73`（`200/500/1015/5001-5008`）；`HotApiSupportContractTest` |
| C-02 | `hot-common/.../errmsg/HotErrorMessages.java` | 11 条 code→msg（**英文**）＋ 未知码回落 `"FAIL"` | `contract/ErrMsg.java` | 1:1 | ✅ | **PASS** | **E-2** | 文案逐字（`service busy` / `internal server error` / …）＋ 回退值 `"FAIL"` 保留 |
| C-03 | `hot-common/.../HotErrorCode.java` ＋ `hot-api/.../exception/HotCodeError.java` | 业务异常载体（**同时是 JSON 响应体** `{code,msg}`） | `contract/CodeError.java` | 1:N | ✅ | **PASS** | **E-2** | `getMessage()` 覆写为 `ErrCode:%d, Errmsg:%s` 整串（**只进日志**）逐字保留 |
| C-04 | **B 侧无对应实现**（全域 grep 实测：hot 域零 gRPC 错误码反查调用点） | 「B 侧原本不可达」的 gRPC 码反查器 | `contract/GrpcErrorMapper.java` | C 新增 | ✅ | **PASS** | **E-3** | ✅ **2026-11-04 收口**：① **静态** —— B 侧 hot 域**无该类**（全域 grep 只命中一个测试文件里的 javadoc 提及）＋ C 侧 hot 域**零调用点**（仅 2 处 javadoc `{@link}`）⇒ **两侧都永远不会做业务码反查**；② **行为** —— 借 **B 侧同款** `HotServiceGrpcImplErrorMappingTest` 的期望值（AGENTS.md §1.0c）⇒ C 侧 hot 的 gRPC 边界「裸异常 ⇒ **UNKNOWN**（非 INTERNAL）＋ description = **原始错误文本**」⇒ ⇒ **可观察面本就与它无关**；③ **守卫** —— `HotGrpcErrorMappingTest#hotDomainHasNoBusinessCodeReverseLookupCallSite` 把「零调用点」做成**可执行断言**（变异注入调用点 ⇒ **恰好 1 红**）⇒ §五·F-05 |
| C-05 | `hot-proto/src/main/proto/hot.proto` | L1 wire 契约（5 message + 5 rpc） | `proto/src/main/proto/hot.proto` | 1:1 | ✅ | **PASS** | **E-2** | 逐字节 diff 只输出 `java_package` 一行；pb 包名 `com.rag.proto.hot` |

### 2.2 HTTP 边界（api 侧）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据强度 | 证据 |
|---|---|---|---|---|---|---|---|---|
| A-01 | `hot-api/.../HotApiApplication.java` ＋ `hot-rpc/.../HotRpcApplication.java` | 两个独立进程入口 | `HotServiceApplication.java` | **N:1** | ✅ | **PASS** | **E-3** | `@SpringBootApplication` ＋ `@ConfigurationPropertiesScan("com.rag.hot.config")` ＋ `@EnableScheduling` ＋ `@EnableDiscoveryClient`；**`@Import` 清单为空**（A5 PASS） |
| A-02 | `hot-api/.../advice/GlobalResponseAdvice.java` | 成功出口包信封 `{code:200,msg:"OK",data}` | `advice/HotApiResponseAdvice.java` | 1:1 | ✅ | **PASS** | **E-3** | 真 Tomcat：信封形状 ＋ **键序**（`code`/`msg`/`data`）逐字断言 |
| A-03 | `hot-api/.../advice/GlobalExceptionHandler.java` | **恒 HTTP 200** ＋ `{code,msg,null}` | `advice/HotApiExceptionHandler.java` | 1:1 | ✅ | **PASS** | **E-3** | 业务失败恒 200 + `code:5001` + `msg:"internal server error"`（**英文**）真 Tomcat 断言 |
| A-04 | `hot-api/.../controller/HotArticleController.java` | 唯一端点 `GET /hot/v1/articles` | `controller/HotArticleController.java` | 1:1 | ✅ | **PASS** | **E-3** | path ＋ method 逐条；真 Tomcat 实打 |
| A-05 | `hot-api/.../service/HotArticleQueryService.java` | 编排（拉排名 → 补全 → 分页） | `service/HotApiService.java` | **N:1**（3→2） | ✅ | **PASS** | **E-3** | 见 §三·3.1 |
| A-06 | `hot-api/.../service/HotArticleHydrator.java` | 并发补全（`Semaphore(8)` ＋ 4 条 skip 分支） | `service/HotApiService.java#hydrate` | 1:N | ✅ | **PASS** | **E-3** | 4 条 skip 原因逐条（`empty_article_id` / `article_rpc_error` / `article_missing` / `article_not_published`）＋ `hot_api_article_skip_total{reason}` 指标读数 |
| A-07 | `hot-api/.../service/HotRankPaginator.java` | 分页数值（page=1 / size=20 / maxSize=50 / maxHot=100）＋ 稠密 rank | `service/HotApiSupport.java` | 1:N | ✅ | **PASS** | **E-3** | `HotApiSupportContractTest`（含 **int32 乘法溢出**钉子）＋ rank 跨页连续的真 Tomcat 断言 |
| A-08 | `hot-api/.../dto/HotArticlesReq.java` | form tag `page` / `page_size`（**下划线**）＋ 两层默认值 | `dto/article/HotArticlesReq.java` | 1:1 | ✅ | **PASS** | **E-3** | form tag 默认 1/20 ＋ logic 层 default 1/20 **两层都在** |
| A-09 | `hot-api/.../dto/HotArticlesResp.java` | `items`/`total`/`page`/`page_size`/`scope`（`scope` 恒 `"rolling"`） | `dto/article/HotArticlesResp.java` | 1:1 | ✅ | **PASS** | **E-3** | 真 Tomcat 响应体键序与取值断言 |
| A-10 | `hot-api/.../dto/HotArticleCard.java` | 补全后的文章卡片（13 分量） | `dto/article/HotArticleItem.java` | 1:1 | ✅ | **PASS** | **E-3** | ⚠️ **命名债**：B 刻意改名 `HotArticleCard` 以避开与 proto message `hot.HotArticleItem` 同名；C 沿用 `HotArticleItem` ⇒ §五·F-11 |
| A-11 | `hot-api/.../metrics/HotApiMetrics.java` | 5 个 api 指标名（**L1**） | `metrics/HotApiMetrics.java` | 1:1 | ✅ | **PASS** | **E-3** | `hot_api_request_total{route,result}` / `hot_api_reject_total{route,reason}` / `hot_api_request_duration_seconds{route}` / `hot_api_rpc_error_total{callee}` / `hot_api_article_skip_total{reason}` 逐条；Timer **只开 SLO**（`GO_DEF_BUCKETS` 11 桶）**禁** `publishPercentileHistogram()` |
| A-12 | `hot-api/.../client/ArticleRpcClient.java` | 跨域 gRPC 取文章（`public_only=true` / `incr_view=false`） | `client/ArticleRpcClient.java` | 1:1 | ✅ | **PASS** | **E-3** | `@GrpcClient("article-rpc")`（A4 配了 plaintext）；**真进程内 gRPC**（`ArticleRpcInProcessServer`）跑通 |
| A-13 | `hot-api/.../client/GoStyleGrpcCallDeadline.java` | 把请求剩余预算挂到 stub 上 | `config/HotApiProperties.java` ＋ `client/ArticleRpcClient#stubWithDeadline` | 1:N | ✅ | **PASS** | **E-2** | deadline = `min(2000ms, 请求剩余预算)`；**每次调用新建 stub**（不缓存带 deadline 的 stub ⇒ 缓存会把过期 deadline 固化） |
| A-14 | `hot-api/.../client/HotRpcClient.java` | 域内自我 gRPC 往返 | **删除**（**D2** 域内直连） | — | ✅ | **INTENTIONALLY_CHANGED** | **E-2** | §五·F-02 |
| A-15 | `hot-api/.../form/GoFormValues.java` | Go 语义 form 解析（`int32` 强转 / `firstFormValue` / 段数上限） | `common/.../web/GetFormValuesFilter.java`（**上收**）＋ `service/HotApiSupport.java`（域内自持 2 个方法） | 1:N | ✅ | **FRAMEWORK_DIFFERENCE** | **E-2** | ⚠️ 共享层 `GetFormValuesFilter` **没有** `int32` / `firstFormValue` ⇒ 这两个只能域内自持 ⇒ §五·F-03 |
| A-16 | `hot-api/.../config/HotHydrationExecutorConfiguration.java` | 有界补全池（池 64 ＋ 队列 64 ＋ `CallerRunsPolicy`） | `config/HotHydrationExecutorConfiguration.java` | 1:1 | ✅ | **PASS** | **E-3** | ⚠️ **额外补回 Boot 的 `applicationTaskExecutor`** ⇒ §五·F-06 |
| A-17 | **B 侧无 `WebMvcConfig`**（hot-api 全模块零 interceptor） | —— | `config/WebMvcConfig.java`（**`addInterceptors` 刻意为空**） | C 新增 | ✅ | **INTENTIONALLY_CHANGED** | **E-3** | §五·F-04 |

### 2.3 领域 / 服务端能力层（rpc 侧）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据强度 | 证据 |
|---|---|---|---|---|---|---|---|---|
| R-01 | `hot-rpc/.../service/impl/HotServiceGrpcImpl.java` | 5 个 gRPC 用例 | `grpc/HotGrpcService.java` | 1:1 | ✅ | **PASS** | **E-3** | `@GrpcService` ＋ 5 个 override（`getHotArticles` ＋ 4 个互动）；**3×UNIMPLEMENTED**（文案逐字）＋ **comment → `success=true`**（B 侧不对称，照抄不「修齐」）<br>证据：`HotGrpcServiceRealGrpcTest`（**真进程内 gRPC** ⇒ 真 protobuf 序列化 ＋ 真状态码 ＋ 真 description） |
| R-02 | `hot-rpc/.../service/HotRankingQueryService.java` | 唯一有逻辑的 rpc 方法 | `service/HotRankingQueryService.java` | 1:1 | ✅ | **PASS** | **E-3** | `topK ≤0 → 20` / `>100 → 100`；`redis.Nil → 空 items`（**不冒泡**）；`hot_score = uint32(score)` **向零截断**（**不 `Math.round`**） |
| R-03 | `hot-rpc/.../service/InteractionStubService.java` | 4 个互动用例 | `service/InteractionStubService.java` | N:1 | ✅ | **PASS** | **E-3** | 3×UNIMPLEMENTED ＋ 1×`success=true`；兜底 `Status.UNKNOWN` |
| R-04 | `hot-rpc/.../config/HotRpcProperties.java` | `HeavyKeeper{width,depth,decay,topK}` ＋ `Interaction{syncEvery,ttl,partitionCount,weights[]}` | `config/HotRpcProperties.java` | 1:1 | ✅ | **PASS** | **E-3** | 字段名与默认值逐条照抄（B 侧 `:77-84` / `:128-135` / `:179-182` / `:211`）；`Weight#getCode()` 这个**零读取死字段**逐字保留 |
| R-05 | `hot-rpc/.../config/HotKafkaConfig.java` | L3「MQ 失败处理」：分区分配回调 ＋ 错误处理策略 | `config/HotKafkaConfig.java` | 1:1 | ✅ | **PASS** | **E-3** | 零重试 `FixedBackOff(0,0)` ＋ `setAckAfterHandle(true)`（**变异验证有牙齿**，§一·1.5）；分区回调**只处理本 topic**；`@Lazy` 解循环依赖的时序论证逐字保留 |
| R-06 | `hot-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | Boot 4 拆模块（Jackson 2/3）补丁 | `common/.../autoconfigure/JsonBodyConverterAutoConfiguration.java`（**上收**） | 1:1 | ✅ | **PASS** | **E-2** | 域内不建；共享层统一承担 |
| R-07 | `hot-rpc/.../bootstrap/NacosRegistration.java` | etcd flat key `hot.rpc` → 注册 `hot-rpc` | `wiring/NacosRegistration.java` | 1:1 | ✅ | **PASS** | **E-2** | 注册的是 **gRPC 端口**（8083）**不是 web 端口**；group **从配置读**（默认 `DEFAULT_GROUP`）；**注册失败 ⇒ health `NOT_SERVING`（不崩进程）**；`spring.cloud.nacos.discovery.register-enabled=false` 关掉自动注册（否则同名下出现两个端口的实例） |
| R-08 | `hot-rpc/.../metrics/HotRpcMetrics.java` | 2 个 rpc 指标名（**L1**） | `metrics/HotRpcMetrics.java` | 1:1 | ✅ | **PASS** | **E-3** | `article_hot_kafka_consume_total{type}` ＋ `article_hot_redis_sync_total{status}`；标签取值域逐条未动 |
| R-09 | `hot-rpc/.../domain/HeavyKeeper.java` | FNV 指纹 ＋ `depth×width` 桶表 ＋ 最小堆 Top-K | `domain/model/HeavyKeeper.java` | 1:1 | ✅ | **PASS** | **E-2** | **逐字抄写**；三条语义精确复刻：① `uint32` 回绕（B 侧 `HeavyKeeper.java:25-27` / `:133` / `:236`）② 概率衰减（不可断言精确值）③ 同 count 顺序不稳定（B 侧 `:63-64`）；列号取模用 `Long.remainderUnsigned`（B 侧 `:254` / `:268` / `:289`）；`uint32 → double` 用 `Integer.toUnsignedLong`（B 侧 `:292-299`）；测试证据见 §七·12 的**强度说明**（算法自洽性，**非 B↔C 对拍**） |

### 2.4 消息面（B 侧 `hot-rpc/.../mq/` 5 类 —— G-1~G-5）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据强度 | 证据 |
|---|---|---|---|---|---|---|---|---|
| M-01 | `hot-rpc/.../mq/HotEventConsumer.java` | **热度数据的唯一入口**；**任何异常都不外抛** | `messaging/HotEventConsumer.java` | 1:1 | ✅ | **PASS** | **E-3** | 校验顺序逐条（反序列化 → `article_id` → 权重 → **先计数** → 再查分区）；**「先计数后查分区」**的次序逐字保留（分区未持有 ⇒ **已计数、已 mark、只是不入 sketch**）；payload 用 `String` 接收 + 手动反序列化（让解析失败落进吞异常边界） |
| M-02 | `hot-rpc/.../mq/HotPartitionSketchRegistry.java` | 分区 sketch 生命周期（assign 回灌 / revoke 落盘） | `messaging/HotPartitionSketchRegistry.java` | 1:1 | ✅ | **PASS** | **E-3** | 不变量逐条（每分区恰好一个 sketch / 重复 assign 不回灌 / revoke 后不再持有）；**撤销路径的「持写锁再取读锁」死锁已消解** ⇒ §五·F-07 |
| M-03 | `hot-rpc/.../mq/HotRedisSnapshotStore.java` | Redis 影子 ＋ `RENAME` 原子快照（4 段 Lua ＋ `EXISTS` 守卫 ＋ `EXPIRE 300`） | `messaging/HotRedisSnapshotStore.java` | 1:1 | ✅ | **PASS** | **E-3** | **真 Redis**（Testcontainers）跑 Lua：TTL 300 **硬编码**、首次快照不丢、TopK 空不写、`loadInto` 回灌、keyspace 名字逐条 |
| M-04 | `hot-rpc/.../mq/HotMergeScheduler.java` | 分布式锁 ＋ 看门狗续期 ＋ 跨分片求和（10s tick） | `messaging/HotMergeScheduler.java` | 1:1 | ✅ | **PASS** | **E-3** | **真 Redis**：锁 TTL 10、CAS 释放**不偷别家锁**、跨片求和、TTL 3600、`PartitionCount=3`、抢不到锁**整轮跳过** |
| M-05 | `hot-rpc/.../mq/ArticleHotEvent.java` | Kafka 载荷（2 字段 ＋ **忽略未知字段**） | `contract/ArticleHotEvent.java` | 1:1 | ✅ | **PASS** | **E-3** | `@JsonIgnoreProperties(ignoreUnknown=true)` **必须**（生产侧实测多发 `user_id`/`timestamp` ⇒ 缺它 ⇒ 现有生产端事件会被**全部 drop**） |

### 2.5 装配与配置

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现 | 验证 | 证据强度 | 证据 |
|---|---|---|---|---|---|---|---|---|
| X-01 | `hot-api/application.yml` ＋ `hot-rpc/application.yml` | 两份运行时配置 | `service/hot/src/main/resources/application.yml` | N:1 | ✅ | **PASS** | **E-2** | 两份**并成一段**，**键名逐字保留**（L1）：`spring.kafka.*` 全族 / `hot-rpc.*` 全族 / `hot-api.article-rpc.timeout` / `observability.{http-timeout:3s,rpc-timeout:2s}` / `rag.*` |
| X-02 | B 侧 `zrpc.RpcServerConf{ListenOn}` | gRPC 监听口 | `grpc.server.port`（**8083**） | 1:1 | ✅ | **PASS** | **E-3** | **端口与 B 侧一致**（B 侧三副本 8083/8084/8085，C 侧同一部署 × 副本，端口走环境变量） |
| X-03 | B 侧 `RedisConf{Host,Pass}` | Redis 连接 | `spring.data.redis.*` | 1:1 | ✅ | **PASS** | **E-3** | `connect-timeout: 5s` **必须显式写**（不配 ⇒ Lettuce 默认 10s ⇒ **漂移 2 倍**）；`timeout: 3s`；连接池 `max-active:64 / max-idle:16 / min-idle:0 / max-wait:4s`（`max-wait` **不可省** ⇒ 不配 ⇒ commons-pool2 默认 -1 = 无限等） |
| X-04 | B 侧 `KqConsumerConf{Brokers,Group,Topic}` | Kafka 连接与订阅 | `spring.kafka.*` ＋ `hot-rpc.kafka.topic` | 1:1 | ✅ | **PASS** | **E-3** | `bootstrap-servers` / `group-id: article-hot-group` / `auto-offset-reset: latest` / `enable-auto-commit: false` / `ack-mode: record` / `RoundRobinAssignor` 逐条；`topic: article-hot-events` |

---

## 三、多文件合并与拆分专项（§八）

### 3.1 N:1 合并 —— **B 侧 api+rpc 两个进程 → C 侧一个 app**（D1）

| B 源文件（全部） | 各自承担的职责 | C 承接位置 | 逐项核对结果 |
|---|---|---|---|
| `hot-api/.../HotApiApplication.java` | api 进程入口（扫 `com.rag.hot.hot.api` ＋ `com.rag.common`） | `HotServiceApplication.java` | ✅ 入口职责合并；⚠️ **刻意不扫 `com.rag.common`**（共享层在本仓是 starter ⇒ 扫一遍会双注册）⇒ §五·F-08 |
| `hot-rpc/.../HotRpcApplication.java` | rpc 进程入口（**显式 `@Import` 4 个共享层类**） | 同上 | ✅ `@Import` 清单**清空**（D3 的直接结果 · A5 PASS）；**gRPC 拦截链由共享层 `ProtectionAutoConfiguration#seaGrpcServerConfigurer` 唯一承担**（域内再来一份会挂 2 条链） |
| `hot-api/.../HotApiApplication.java`（Spring DI 取代） | `ServiceContext` | — | ✅ B 侧 C 侧都无独立文件（Spring Bean 承担） |
| `hot-rpc/.../bootstrap/NacosRegistration.java` | 注册 gRPC 端口 | `wiring/NacosRegistration.java` | ✅ 合并后**注册面只有 gRPC 端口**（自动注册已关）⇒ 不会出现「同名下两个端口的实例」 |
| `hot-rpc/.../mq/` 5 类 | 消费 / 分区 / 快照 / 合并 / 锁 | `messaging/` 4 类 ＋ `contract/ArticleHotEvent` | ✅ 逐项见 §二·2.4 |
| `hot-rpc/.../service/impl/HotServiceGrpcImpl.java` | 5 个 gRPC 用例 | `grpc/HotGrpcService.java` | ✅ 5/5 override 全在 |
| `hot-api/.../advice/` 2 类 | 信封 ＋ 异常出口 | `advice/` 2 类 | ✅ 逐条见 §二·2.2 |

> **合并后新增的共享状态检查**（§3.2 要求）：
> ① **两个 `HotApiProperties` / `HotRpcProperties` 绑定点** —— 前缀 `hot-api` / `hot-rpc` **不冲突** ⇒ 无覆盖；
> ② **两个 `@ConfigurationProperties` 类都用 `@ConfigurationPropertiesScan("com.rag.hot.config")` 注册** ⇒
> **刻意不带 `@Component`**（否则同类型注册两个 bean ⇒ `NoUniqueBeanDefinitionException`）；
> ③ **一个 `TaskExecutor` 注入点**（`HotEventConsumer` / `HotRedisSnapshotStore` / `HotMergeScheduler` 都要）⇒
> ⚠️ **合并进程里补全池会抑制 Boot 的 `applicationTaskExecutor`** ⇒ 已显式补回 ⇒ §五·F-06；
> ④ **HTTP 与 gRPC 两个入口共存** —— 真 Tomcat + 真 gRPC server 同进程（`grpc.server.port=0` 交 OS 分配）⇒
> 已在 `HotHttpRealTomcatTest` 与 `HotEventConsumerRealKafkaTest` 两个上下文里分别真跑 ⇒ 无端口/生命周期互扰。

### 3.2 1:N 拆分 —— **B 侧 api 的 3 个 service 类 → C 侧 2 个**

| B 源文件 | 职责 | C 承接 | 结论 |
|---|---|---|---|
| `hot-api/.../service/HotArticleQueryService.java` | 编排（拉排名 → 补全 → 分页） | `service/HotApiService.java` | ✅ 编排次序不变 |
| `hot-api/.../service/HotArticleHydrator.java` | 并发补全（`Semaphore(8)`） | `service/HotApiService.java#hydrate` | ✅ **每请求一个 Semaphore** ＋ **先 acquire 再 submit**（次序未动） |
| `hot-api/.../service/HotRankPaginator.java` | 分页数值 ＋ 稠密 rank | `service/HotApiSupport.java` | ✅ 阈值 1/20/50/100 ＋ 跨页连续 rank 逐条 |
| `hot-common/.../errmsg/` 2 类 | 错误契约 | `contract/` 4 类 | ✅ 码值 ＋ 文案 ＋ 载体 ＋ 反查器 |
| `hot-api/.../client/GoStyleGrpcCallDeadline.java` | deadline 挂载 | `config/HotApiProperties` ＋ `client/ArticleRpcClient#stubWithDeadline` | ✅ 口径 `min(cap, 剩余预算)` 与 B 侧 `context.WithTimeout(ctx,2s)` 等价 |

> **拆分后是否职责断裂**（§3.3 检查）：
> ① **调用链完整**：`HotArticleController → HotApiService → {HotRankingQueryService（直连）| ArticleRpcClient（gRPC）} → HotApiSupport` ⇒ 链路闭合；
> ② **数据传递**：proto `hot.HotArticleItem` → `dto/article/HotArticleItem`（13 分量）→ `HotArticlesResp.items` ⇒ 逐字段在；
> ③ **异常传播**：hot-rpc 失败 = **fatal**（整包 5001）vs article-rpc 失败 = **best-effort**（skip 该条）⇒ **不对称逐条保留**（D2 后 catch 放宽到 `RuntimeException`，理由见类注释）；
> ④ **事务边界**：本域**无事务**（无表）⇒ 不涉及；
> ⑤ **无重复执行 / 无遗漏执行**：补全的 4 条 skip 分支与 2 条 fatal 分支逐条对到；
> ⑥ **初始化时机**：`@EnableScheduling` 打开 30s 快照 ＋ 10s 合并两条 tick；`spring.task.scheduling.pool.size=2`（B 侧是两条**独立 goroutine** ⇒ 单线程调度器会让二者排队）。

### 3.3 Common Starter 上收（§8.4）

| B 侧 `service/common/` 的能力 | C 侧落点 | 原消费方是否都被覆盖 | 结论 |
|---|---|---|---|
| `response/Response.java`（`{code,msg,data}` 信封） | `common/contract/ApiResponse.java` | ✅ hot 的 2 个 advice 都用它 | ✅ |
| `logger/Logger.java`（`LogBusinessErr` / `WithArticleID` / `call_chain`） | `common/logger/*` | ✅ hot 的业务错误日志走它 | ✅ |
| `observability/HttpMiddleware.java` ＋ `MqConsumer.TraceConsumer` | `common/observability/*` | ✅ hot 的消费 span 走 `MqConsumer.traceConsumer` | ✅ **D4**：B 侧传 OTel `Context.current()` ⇒ C 侧共享层签名是 `traceConsumer(ObservationRegistry, …)` ⇒ 域内改为**注入 `ObservationRegistry`** |
| `observability/RpcServerInterceptorFactory` | `common/autoconfigure/ProtectionAutoConfiguration#seaGrpcServerConfigurer` | ✅ **唯一挂载点**（域内 0 重复） | ✅ A3 PASS |
| `http/GoStyleFormValueLimitInterceptor` ＋ `GoStyleFormValues` | `common/web/ValueLimitInterceptor` ＋ `GetFormValuesFilter` | ⚠️ **判据口径**见 §五·F-03 | ⚠️ **FRAMEWORK_DIFFERENCE** |
| `snowflake` / `communityfact` / `security` | 共享层存在 | ✅ **hot 域零引用**（无表 / 无 fact / 免鉴权） | ✅ **不构成死依赖**（hot 域不引这些包） |

> 📌 **本轮实测修正了一条旧记录**：B 侧 `hot-rpc` 的 yml 写 `rag.side: rpc`，
> 而 RAG-V1 的共享层**已整体去掉 `@ConditionalOnApiSide`**
> （`common/web/ValueLimitInterceptor.java:76-83` 等 3 处 javadoc 明写「D1 落地差异（去 `@ConditionalOnApiSide`）」）
> ⇒ ⇒ **`rag.side` 在本仓无消费点**（全仓 grep 仅 3 处 javadoc 提及，无一处是条件判断）
> ⇒ ⇒ C 侧不配 `rag.side` **无可观察差异** ⇒ §五·F-08 结论从「行为漂移」降级为「无消费点的键」。

### 3.4 独立进程合并（§8.5）—— B 侧 hot-rpc 的两条裸 goroutine ticker

| B 侧 goroutine | C 侧落点 | 执行频率 | 启动/关闭 | 资源隔离 | 结论 |
|---|---|---|---|---|---|
| `go h.periodicSync(...)`（30s 分片快照） | `HotRedisSnapshotStore#periodicSync`（`@Scheduled`） | **30s** | `@EnableScheduling` ⇒ 随容器起停 | 调度池 2 线程 | ✅ |
| `go h.periodicMerge(...)`（10s 跨片合并） | `HotMergeScheduler#periodicMerge`（`@Scheduled(fixedRate=10_000)`） | **10s** | 同上 | 同上（**2 线程 ⇒ 两条 tick 互不排队**） | ✅ |

> ⚠️ **失败处理**：B 侧 goroutine 内的错误只打日志；C 侧 `@Scheduled` 的方法若抛异常 ⇒
> Spring 会记录并继续下一次调度（**不取消后续**）⇒ 等价。
> ⚠️ **重复执行保护**：合并侧的重复执行由 **Redis 分布式锁**（`SETNX` ＋ 看门狗 ＋ CAS 释放）承担 ⇒ 已真 Redis 验证。

---

## 四、框架版本差异清单（§七）

> 每项含：B 的机制与版本 / C 的机制与版本 / 具体变化 / 影响的执行流程 / 是否影响业务行为 / 适配方式 / 验证证据 / 最终结论。

### 4.1 Boot 4 → 3.5：Kafka 坐标

| 项 | 内容 |
|---|---|
| B 机制 | `org.springframework.boot:spring-boot-kafka`（**Boot 4 专有**，把 Kafka auto-config 拆成独立模块） |
| C 机制 | `org.springframework.kafka:spring-kafka` **3.3.13**（Boot 3.5 线**不存在** `spring-boot-kafka`） |
| 变化 | auto-config 的 artifact 坐标变了；`ConcurrentKafkaListenerContainerFactoryConfigurer` 的包路径也变了（B 侧 `org.springframework.boot.kafka.autoconfigure.*` ⇒ C 侧 `org.springframework.boot.autoconfigure.kafka.*`） |
| 影响流程 | Kafka listener 装配 ＋ 容器工厂装配 |
| 是否影响业务行为 | **否** —— 两个版本的 `configure()` 都只读 `spring.kafka.listener.*` 与 `spring.kafka.consumer.*` 两族键 ⇒ **键名与语义逐条相同** ⇒ 本域的「零重试 ＋ 分区回调」两项**不依赖** configurer 的细节 |
| 适配方式 | `HotKafkaConfig` 里用 Boot 3.5 的 configurer；**零重试与分区回调仍显式设置**（不依赖 configurer） |
| 验证证据 | 真 Kafka 容器 + 零重试**变异验证转红**（`expected: 1 but was: 10`） |
| **结论** | **FRAMEWORK_DIFFERENCE** ⇒ 已证明不影响行为 ⇒ 最终行为结论 **PASS** |

### 4.2 Boot 4 → 3.5：Jackson 3 → Jackson 2

| 项 | 内容 |
|---|---|
| B 机制 | Jackson **3.x**（`tools.jackson.*`） |
| C 机制 | Jackson **2.19.4**（`com.fasterxml.jackson.*`）—— D14 全项目口径 |
| 变化 | 包名整体不同；B 侧 `hot-rpc` 因此需要一个**补丁类** `Jackson2ObjectMapperConfiguration` |
| 影响流程 | Kafka 载荷反序列化（`ArticleHotEvent`）＋ HTTP 请求/响应序列化 |
| 是否影响业务行为 | **否** —— 本域只用到 `@JsonProperty` / `@JsonIgnoreProperties` / `record` 这三个在两版**语义相同**的注解 |
| 适配方式 | 域内**零** `tools.jackson` 引用；Jackson 相关的 auto-config 由共享层 `JsonBodyConverterAutoConfiguration` 统一承担 |
| 验证证据 | 真 Kafka 消费（载荷 `{"article_id":…,"type":…}` 真被解析进 sketch ＋ 指标）＋ 真 Tomcat 信封断言 |
| **结论** | **FRAMEWORK_DIFFERENCE** ⇒ 已证明不影响行为 ⇒ **PASS** |

### 4.3 Tomcat 11 → 10.1

| 项 | 内容 |
|---|---|
| B 机制 | Tomcat **11.0.24**（B 侧是 **Java / Spring Boot 4**，**不是** Go —— 见 §一·1.1 的同源提醒） |
| C 机制 | Tomcat **10.1.52** |
| 变化 | ① **状态行不发 reason phrase** ⇒ `HTTP/1.1 404 `（码后有空格、无短语）② Servlet API 版本 6.0 → 5.0 |
| 影响流程 | 404 / 405 这类**裸状态码**响应的**字节面** |
| 是否影响业务行为 | **否** —— ⚠️ **原判定（2026-11-03）写的是「状态行逐字节不同（B 侧 Go `net/http` 会发 `HTTP/1.1 404 Not Found`）」⇒ 该归因经 2026-11-04 的双侧活体对拍**证伪**（见 §九·9.3）**：<br>**B 侧也是 Tomcat，同样不发 reason phrase** ⇒ **两侧状态行逐字节相同** ⇒ **对 B 侧无漂移**。<br>⚠️ 与 **Go 真源**相比确有差异（Go 的 `net/http` 会写标准 reason phrase）⇒ 但**基准是 B 侧**（D16）⇒ 那不是漂移。 |
| 适配方式 | 按本仓既有口径断言（状态行 `"HTTP/1.1 404 "` ＋ 无信封） |
| 验证证据 | `HotHttpRealTomcatTest`（真 Tomcat ＋ **裸 socket 逐字节读回**）＋ **§九·M1 的 18 条双侧逐字节对拍**（`unknown_path` / `method_not_allowed` 两条） |
| **结论** | **PASS**（**不是** FRAMEWORK_DIFFERENCE）—— 原 F-09 条目已按 §九·9.3 更正为「原归因错误 ⇒ 实测两侧相同 ⇒ 非漂移」 |

### 4.4 `@ConfigurationPropertiesScan` + `@ConditionalOnMissingBean` 的装配差异

| 项 | 内容 |
|---|---|
| B 机制 | 每个 `@ConfigurationProperties` 类自带 `@Component` ＋ `@ConfigurationProperties`；`@Import` 显式列共享层 |
| C 机制 | 入口类 `@ConfigurationPropertiesScan("com.rag.hot.config")` 统一注册；**`@Import` 清单为空**；共享层走 `AutoConfiguration.imports` |
| 变化 | 装配入口从「显式 Import + 组件扫描」变成「starter 自动配置 ＋ 属性扫描」 |
| 影响流程 | 全部 bean 的创建时机与条件 |
| 是否影响业务行为 | **否** —— 但**有装配陷阱**（见 §五·F-06：`Executor` bean 抑制 Boot 的 `applicationTaskExecutor`） |
| 适配方式 | `@ConfigurationProperties` 类**刻意不带 `@Component`**；`HotHydrationExecutorConfiguration` **显式补回** `applicationTaskExecutor` |
| 验证证据 | 上下文真起（`HotServiceApplicationTests`）+ 56 例回归全绿 ⇒ 缺 bean 会直接起不来 |
| **结论** | **PASS**（陷阱已修） |

### 4.5 零重试错误处理（本域最易漂移面）

| 项 | 内容 |
|---|---|
| B 机制 | sarama 消费循环**无条件** `sess.MarkMessage(msg, "")` ⇒ 坏消息**静默丢弃、位点照常推进、永不重试** |
| C 机制 | Spring Kafka 的默认 `DefaultErrorHandler` 是 `FixedBackOff(0, 9)` ⇒ **重试 9 次**才跳过 ⇒ 与 B 侧**相反** |
| 变化 | 默认行为直接与 B 侧相反（一条坏消息能卡住分区 9 轮） |
| 影响流程 | 消费失败 → 重试 → 位点推进 |
| 是否影响业务行为 | **是**（若不改）⇒ **必须显式对齐** |
| 适配方式 | **两层**：① `HotEventConsumer` 永不外抛（把「解析失败 / 缺 id / 未知 type」三类坏消息**吞在方法内部**）② 容器级 `FixedBackOff(0,0)` ＋ `setAckAfterHandle(true)` 作**兜底** |
| 验证证据 | ✅ 真 Kafka ＋ **零重试探针**（`ThrowingKafkaListenerConfiguration`：每次收到消息都抛）⇒ 断言「失败记录**只被投递 1 次**」；<br>✅ **变异验证**：改成 `FixedBackOff(0,9)` ⇒ 投递次数变 **10** ⇒ 用例**转红** ⇒ 判据**有牙齿**；<br>✅ 真消费面：坏 payload 之后的好消息**仍被消费** ⇒ 分区**没被卡住** |
| **结论** | **PASS**（含变异验证） |

### 4.6 其它已核对的框架面

| 项 | 结论 |
|---|---|
| Redis 客户端 | Spring Data Redis **3.5.9** / Lettuce **6.6.0**；`connect-timeout` **必须显式配 5s**（不配 ⇒ Lettuce 默认 10s ⇒ **漂移 2 倍**）⇒ 已配 ⇒ **PASS** |
| MyBatis-Plus | **本域无表** ⇒ MP / DataSource 是**模板残留**（映射表 §2 决策锁）⇒ C 侧**沿用模板未删**（不在本轮写权内）⇒ **NOT_IMPLEMENTED（无表 ⇒ 无职责可承接）** |
| 鉴权 / Security | hot 域**全端点免鉴权**（B 侧零 interceptor）⇒ C 侧**零 Security 依赖**、零 JWT claim ⇒ **PASS** |
| Nacos | 注册名 `hot-rpc` ＋ **gRPC 端口** ＋ group 从配置读 ＋ 注册失败 ⇒ health `NOT_SERVING`（不崩进程）⇒ **PASS**；namespace/group 的**部署面差异**记 §五·F-01 |
| 生命周期 | `@EnableScheduling` 打开两条 tick；`@PostConstruct`/`SmartLifecycle` 无额外副作用；gRPC server 由 devh starter 起（`grpc.server.port=0` ⇒ OS 分配，避免并行跑撞口）⇒ **PASS** |

---

## 五、语义漂移清单（§11.5）

> 字段：Drift ID / 问题位置 / B 文件与方法 / C 文件与方法 / 源项目行为 / 目标项目行为 / 差异原因 /
> 影响范围 / 严重程度 / 修复建议 / 验证方法。
> ⚠️ **「B 侧既有缺陷」单列在 F-07/F-10，不计入 C 侧的迁移漂移。**

### F-01 · HTTP 端口（D1 合并的必然结果）· 部署面

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/resources/application.yml:139` |
| B | `hot-api`：`server.port = ${HOT_API_PORT:18897}`；`hot-rpc`：另有 `server.port = 19091`（**只承载 Actuator `/metrics`，无业务路由**） |
| C | `server.port = ${HOT_HTTP_PORT:8895}`（**合并后单 HTTP 口**） |
| 原因 | **D1**（api+rpc 合并 ⇒ 两个 web 口合一） |
| 影响范围 | **仅部署面**（端口号）—— 业务语义、错误契约、响应形状**零影响** |
| 严重程度 | 🟡 低（部署面） |
| 修复建议 | 不修。**登记为签字偏离**；部署时用 `HOT_HTTP_PORT` 覆盖 |
| 验证方法 | `HotHttpRealTomcatTest`（真 Tomcat 起在随机口 ⇒ 行为面已验）；端口值由 `application.yml` 单点决定 |

### F-02 · D2 域内直连 ⇒ 域内查询方向**不再有**出向上界 · 性能面

| 项 | 内容 |
|---|---|
| 位置 | `service/HotApiService.java`（`getHotArticles` 直接注入 `HotRankingQueryService`） |
| B | B 侧 hot-api 有**两个**出向 devh client（`hot-rpc` ＋ `article-rpc`）⇒ 共享层的 `GoStyleOutboundDeadlineInterceptor`（`@GrpcGlobalClientInterceptor` ⇒ 装在**每个**出向调用上）按 `rag.protection.outbound-deadline.cap-millis: 2000` **统一封顶** ⇒ ⇒ **域内方向（hot-rpc）也受这同一个 2000ms 封顶**。<br>（`GoStyleGrpcCallDeadline` 只**传播请求预算**、**不封顶** ⇒ 二者合起来 = `min(2s, 请求剩余预算)`。） |
| C | **D2 域内直连** ⇒ `HotApiService` **本地方法调用** `HotRankingQueryService` ⇒ **不再经过任何出向拦截器** ⇒ ⇒ **域内查询方向没有 2s 上界**；而共享层 cap **刻意未配置**（默认 0 = 不启用）⇒ 也没有第二个来源兜着 |
| 原因 | **D2**（同域调用改直连，消除自我 gRPC 往返）—— **既定决策** ＋ **F-04**（出向上界的承载键位从共享层搬到域侧，**覆盖范围随之收窄到只剩 article-rpc 方向**） |
| 影响范围 | **超时面 ＋ 响应形状面**（**2026-11-04 实测升级**）：域内 Redis 查询卡住时 ——<br>· **B 侧**：`2014ms` ⇒ **`HTTP/1.1 200 ` ＋ `{"code":5001,"msg":"internal server error","data":null}`**（2s gRPC 出向封顶 ⇒ 业务信封，**干净**）<br>· **C 侧**：`3013ms` ⇒ **失败响应的形状非确定**（三候选，见 §九·M5）⇒ ⇒ **不只是时延不同，连「失败长什么样」都不同** ⇒ ⇒ 客户端若按信封解，C 侧可能拿到 `503 + text/plain`，**或** `200 + application/json` 但 body 是纯文本（⇒ **信封契约被打断**） |
| 严重程度 | 🟠 **中高**（2026-11-04 实测**升级**：原判「🟡 中 · 性能/超时面 · **非正确性** —— 两条路径都最终失败，只是失败时延不同」⇒ **该定性不成立** ⇒ 失败**形状**也不同、且 C 侧**非确定** ⇒ 归**响应契约面**）<br>⚠️ **但仍不改 D2**（既定决策）⇒ 且「两条路径都最终失败」这一半仍成立 |
| 修复建议 | 不修（D2 是既定决策）。⚠️ 若将来要恢复该上界，**正确做法**是在 `HotApiService` 调用 `HotRankingQueryService` 的那一点折算预算（与 `ArticleRpcClient` 同款），**不要**给共享层再加第二个 cap 来源（会造出「两个上界来源」⇒ 违反 cap 单一来源纪律）<br>⚠️ **附带**：把失败时延从 3s 拉回 2s 之下，**顺带**会让 C 侧**不再命中**共享层的「预算竞态窗口」⇒ ⇒ **恢复上界是唯一能同时消掉「形状差异」与「混血响应」的修法**（F-15 的混血形状只在「业务失败时延 ≈ HTTP 预算」时出现） |
| 验证方法 | ✅ **PASS（2026-11-04 · 双侧活体实测 · §九·M5）** —— 用 `CLIENT PAUSE` 让两侧 Redis **卡住**（服务端收下命令、延迟应答 ⇒ 客户端命令超时被真触发）⇒ 两侧并发发同一请求：<br>· **B 侧 `2014ms`** ＋ `HTTP/1.1 200 ` ＋ 5001 信封（**逐字节**）<br>· **C 侧 `3013ms`** ＋ 失败响应（三候选集合，**逐字节冻结**）<br>⇒ ⇒ **时延关系与登记一致**（2s vs 3s）⇒ ⇒ **失败形状的差异是实测事实**（不再是推断）⇒ ⚠️ 场景有效性断言（请求必须真的卡住 ≥1.5s，否则 `exit 2` 不给结论）+ 恢复校验（暂停窗口过后两侧恢复正常且逐字节一致） |

### F-03 · form 解析与闸门判据的口径差异（域内自持 2 个方法）· 正确性面

| 项 | 内容 |
|---|---|
| 位置 | `service/HotApiSupport.java`（域内自持 `int32` 强转 ＋ `firstFormValue`） |
| B | `hot-api/.../form/GoFormValues.java`：Go 语义的 form 解析（含 `int32` 强转 ＋ `firstFormValue` ＋ 2048 非空值计数 ＋ 10000 段上限） |
| C | 共享层 `common/web/GetFormValuesFilter`（**段数上限 ＋ 2048 非空值计数 ＋ B 侧逐字文案**）＋ 域内 `HotApiSupport`（`int32` ＋ `firstFormValue`） |
| 原因 | **上收 `common/`**（D3/D5）⇒ 共享层 `GetFormValuesFilter` **没有** `int32` / `firstFormValue` 这两个方法 ⇒ 只能域内自持 |
| 影响范围 | **仅** `page` / `page_size` 两个 query 参数的解析（`int32` 溢出 ＋ 首个值语义） |
| 严重程度 | 🟢 低（两套实现逐条一致，且**域内自持的部分已被契约测试钉死**） |
| 修复建议 | 不修。若要把 `int32` / `firstFormValue` 上收到共享层 ⇒ 属**收口域（`global`）**的活，**不在本域写权内** |
| 验证方法 | `HotApiSupportContractTest`（9 例：normalize / paginate / **int32 乘法溢出钉子** / assignRanks / slice / parseInt32 / firstFormValue） |

> 📌 **共享层闸门判据与 B 侧的一致性**（实测 `common/web/GetFormValuesFilter.java`）：
> ① **10000 段上限先判**（`countQuerySegments > MAX_URL_QUERY_PARAMS`）② 再判 **2048 非空值**（`countNonEmptyValues`）③
> 文案产出 **B 侧逐字**。⇒ **判据口径与 B 侧一致**；残留差异是「10001 段时 body 取的是 2048 那条（顺序反了）」——
> 那是**共享层**层面的既有差异，**非本域引入**。

### F-04 · `WebMvcConfig` 的免鉴权决策（原映射表「自相矛盾」已消解）· 表达面

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/java/com/rag/hot/config/WebMvcConfig.java` |
| B | **无 `WebMvcConfig`**（hot-api 全模块零 interceptor、零 WebMvcConfig ⇒ 全端点免鉴权） |
| C | **保留文件**，但 `addInterceptors` **刻意为空**（并写明「这个空方法就是『免鉴权』这条决策的落点」） |
| 原因 | 映射表 §1.2 把该文件标为「🔴 待确认 / 疑似多余」（其 C 侧 javadoc 原文写「注册 JwtAuthInterceptor」，与免鉴权事实**矛盾**） |
| 影响范围 | **无行为影响**（空实现 ⇒ 不注册任何 interceptor） |
| 严重程度 | 🟢 低 |
| 修复建议 | 不修（**刻意保留**，让「免鉴权」在代码里**可见、可 review、可被断言锁住**） |
| 验证方法 | `HotHttpRealTomcatTest` 的「免鉴权」用例（不带任何 token 即可拿到 200 ＋ 信封） |

### F-05 · `GrpcErrorMapper` 零调用点 · 表达面

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/java/com/rag/hot/contract/GrpcErrorMapper.java` |
| B | **无对应实现**（全域 grep 实测：hot 域零 gRPC 错误码反查调用点）⇒ B 侧**按可达性刻意不建** |
| C | 建了「B 侧原本不可达」的映射器（先按 message 反查 code，查不到才按 gRPC 标准码 fallback） |
| 原因 | 映射表 §1.1 判定「C 建的是 B 侧原本不可达的映射器」 |
| 影响范围 | **零**（无调用点 ⇒ 无行为影响） |
| 严重程度 | 🟢 低 |
| 修复建议 | 不修（保留「B 侧有这份映射规则」的形状，供将来接入时用） |
| 验证方法 | ✅ **PASS（2026-11-04 收口 · 三证齐）** —— ① **静态**：B 侧 hot 域**无该类**（全域 grep 只命中一个测试文件里的 javadoc 提及）＋ C 侧 hot 域**零调用点**（仅 `ErrCodes.java:61` / `ErrMsg.java:89` 两处 javadoc `{@link}`）⇒ **两侧都永远不会做业务码反查**（⇒ 与本域 F-10「`detachWithinWriteLock` 零调用点」同款判据：静态证据在本域是够的）；② **行为**：借 **B 侧同款** `HotServiceGrpcImplErrorMappingTest`（AGENTS.md §1.0c「测试资产单独一档 · 期望值沿用 B 侧」）⇒ C 侧 `HotGrpcErrorMappingTest`（**7 例**）断言 hot 的 gRPC 边界「裸异常 ⇒ **UNKNOWN**（**不是 INTERNAL**）＋ description = **原始错误文本**（**不是业务码文案**）」⇒ ⇒ **可观察面本就与它无关**；③ **守卫**：`hotDomainHasNoBusinessCodeReverseLookupCallSite` 扫 `src/main/java` 断言**零调用点** ⇒ **变异验证**：注入一个 `GrpcErrorMapper.bizCodeFromError(e)` ⇒ **恰好 1 红**（该守卫）＋ 其余 6 例仍绿 ⇒ ⇒ **判据有牙齿** |
| **结论** | **PASS**（原 NOT_VERIFIED「零调用点 ⇒ 无法用行为证伪」**已消解**：不是「无法证伪」，而是「**该条要证的不是『它做什么』，而是『两侧都不做它』**」⇒ 用**静态 + 行为 + 可执行守卫**三证闭合） |

### F-06 · D1 合并 ⇒ `hotHydrationExecutor` 抑制 Boot 的 `applicationTaskExecutor`（**本轮真修的缺陷**）· 正确性面

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/java/com/rag/hot/config/HotHydrationExecutorConfiguration.java` |
| B | B 侧 hot-api 与 hot-rpc 是**两个进程** ⇒ hot-rpc 进程里**没有**补全池 ⇒ Boot 的 `applicationTaskExecutor` 正常自动装配 |
| C | D1 合并后**同一个进程**里既有补全池（`Executor` 类型 bean）⇒ Boot 的 `TaskExecutionAutoConfiguration#applicationTaskExecutor` 条件是 **`@ConditionalOnMissingBean(Executor.class)`** ⇒ **被抑制** ⇒ 消息面 3 个类 `@Qualifier("applicationTaskExecutor")` 注入失败 ⇒ **上下文起不来** |
| 原因 | **D1 合并的连带效应**（不是抄写错误） |
| 影响范围 | **启动期**（上下文起不来 ⇒ 整个域不可用）—— **本轮已修** |
| 严重程度 | 🔴 高（未修则整域不可用） |
| 修复建议 | ✅ **已修**：在同一配置类里**显式补回** `@Bean("applicationTaskExecutor")`，用 Boot 自己的 `org.springframework.boot.task.ThreadPoolTaskExecutorBuilder` 构建（与 B 侧 Boot 默认同语义）；因本 bean 本身是 `Executor`，Boot 永远不会再建 ⇒ **不会撞名** |
| 验证方法 | 真上下文起（`HotServiceApplicationTests` ＋ `HotEventConsumerRealKafkaTest`）—— 若未修，**上下文直接起不来** ⇒ 用例必红 |

### F-07 · B 侧撤销路径的**必然死锁**（B 侧既有缺陷，C 侧已消解）· 正确性面

| 项 | 内容 |
|---|---|
| 位置 | `messaging/HotPartitionSketchRegistry.java#detach`（对照 B 侧 `hot-rpc/.../mq/HotPartitionSketchRegistry.java` 的 `OnPartitionsRevoked`） |
| B | `hkMu.Lock()`（持**写锁**）→ `syncToRedis()` → 内部 `hkMu.RLock()` ⇒ Go 的 `sync.RWMutex` **不可重入** ⇒ **第一次 rebalance 撤销分区就必然死锁** ⇒ `delete` 永不执行 ⇒ **分区快照从未真正落盘过** |
| C | `detach()` 在**写锁内只做「取出引用 ＋ 从 map 移除」**，**落盘由调用方在锁外完成**（B 侧另外两个调用点 `periodicSync` / `consumeMessage` 已用的正确形态） |
| 原因 | Java 的 `ReentrantReadWriteLock` **允许**写锁持有者再取读锁 ⇒ 1:1 直译会把 B 侧的死锁**悄悄变成能正常跑** ⇒ 两侧行为不一致且无告警 ⇒ ⇒ **刻意不照抄** |
| 影响范围 | 分区撤销时的落盘（B 侧：死锁 ⇒ 永不落盘；C 侧：正常落盘） |
| 严重程度 | 🟡 中（**B 侧缺陷** ⇒ 已按 verification.md §1.1「B 原有问题不误报为 C 的迁移问题」单独登记） |
| 修复建议 | 不修（C 侧已是对的行为） |
| 验证方法 | `HotMergeSchedulerRealRedisTest` / `HotRedisSnapshotStoreRealRedisTest`（真 Redis 落盘 ＋ 回灌） |

### F-08 · `rag.side` 在本仓**无消费点**（合并进程的必然结果）· 无行为影响

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/resources/application.yml`（**刻意不配** `rag.side`） |
| B | hot-api 与 hot-rpc 两个进程 ⇒ hot-rpc 的 yml 配 `rag.side: rpc` ⇒ 共享层那批 api 侧 web 能力（CPU 降载 / max-conns / max-bytes / breaker）在 rpc 进程**不注册** |
| C | D1 合并 ⇒ **不存在「api 侧 / rpc 侧」这个维度** ⇒ 共享层**已整体去掉 `@ConditionalOnApiSide`**（实测：`common/web/ValueLimitInterceptor.java:76-83`、`JsonContentTypeAdvice.java:106`、`TraceparentFilter.java:200` 三处 javadoc 明写「D1 落地差异（去 `@ConditionalOnApiSide`）」；全仓 grep **无一处**是真正的条件判断） |
| 原因 | **D1 合并**（共享层侧的同一决策） |
| 影响范围 | **零** —— 该键在本仓**没有消费点** ⇒ 不配 `rag.side` 无可观察差异 ⇒ 共享层那批 web 能力**无条件注册**在 hot 的 HTTP 口上（B 侧 hot-api 进程**有**这批能力 ⇒ **等价**） |
| 严重程度 | 🟢 低（无行为影响） |
| 修复建议 | 不修 |
| 验证方法 | 全仓 grep `rag.side` ⇒ 仅 3 处 javadoc 提及；共享层条件注解已不存在 ⇒ **PASS** |

### F-09 · ~~Tomcat 10.1 状态行不发 reason phrase~~ · **原归因错误 ⇒ 实测两侧相同 ⇒ 非漂移**（2026-11-04 更正）

| 项 | 内容 |
|---|---|
| 位置 | HTTP 404 / 405 的**字节面** |
| **原判定（2026-11-03 · 已作废）** | ~~B = Go `net/http` ⇒ `HTTP/1.1 404 Not Found`；C = Tomcat 10.1.52 ⇒ `HTTP/1.1 404 ` ⇒ 状态行逐字节不同 ⇒ FRAMEWORK_DIFFERENCE（框架限制）~~ |
| **更正后的判定（2026-11-04 · 双侧活体对拍实测）** | **B 侧 = Tomcat 11.0.24，同样不发 reason phrase** ⇒ **两侧状态行逐字节相同**：<br>· 404：`HTTP/1.1 404 ` ＋ `text/plain;charset=utf-8` ＋ body `404 page not found\n`<br>· 405：`HTTP/1.1 405 ` ＋ **空** `Content-Type` ＋ **空** body<br>⇒ ⇒ **对 B 侧（唯一 oracle · D16）不构成漂移** ⇒ 本条**降级为 PASS** |
| **原判定错在哪** | ⚠️ **把 B 侧当成了 Go 真源** —— B 侧是 **Java / Spring Boot 4**（`spring-boot-starter-parent:4.0.8`），只是**类名带 `GoStyle*` 前缀**、`GoStyle` 的 javadoc 里引 Go 的 API 名 ⇒ **极易据此误判 oracle 侧的语言**。<br>⇒ 与 article 域 U-3 的教训**同源**（「先读 oracle 侧的 `pom.xml` 确认语言与框架版本」）。 |
| **与 Go 真源的差异去哪了** | **仍然存在**（Go 的 `net/http` 会写标准 reason phrase）⇒ 但**基准是 B 侧**（D16）⇒ 那不是「C 侧漂移」，而是「**B 侧与 Go 真源之间**的既有差异」⇒ ⇒ 归**B 侧的继承差异**，不归 C 侧 |
| 原因 | **不是** Tomcat 11 → 10.1 的框架分叉（两侧都是 Tomcat，且行为一致） |
| 影响范围 | **无** |
| 严重程度 | 🟢 无（**原判定已作废**） |
| 修复建议 | **无需修复**（原「不可修」的结论前提不成立 —— 本来就没有差异） |
| 验证方法 | ① `HotHttpRealTomcatTest`（真 Tomcat ＋ 裸 socket 逐字节读回）<br>② **§九·M1 的 `unknown_path` / `method_not_allowed` 两条双侧活体逐字节对拍**（真 B 侧 Boot 4 ＋ 真 C 侧 Boot 3.5 同时在跑） |
| **结论** | **PASS（原 FRAMEWORK_DIFFERENCE 判定作废）** ⇒ §四·4.3 同步更正；§六 的分类计数同步更正 |

### F-10 · B 侧 `detachWithinWriteLock` 是**零调用点的死代码**（照抄保留）· 表达面

| 项 | 内容 |
|---|---|
| 位置 | `messaging/HotPartitionSketchRegistry.java#detachWithinWriteLock` |
| B | B 侧全仓 grep：**只有它自己的声明命中** ⇒ 零调用点 ⇒ 且它**正是** F-07 那个「持写锁做 Redis I/O」的形态 |
| C | **逐字保留**，但**刻意不调用**（并在 javadoc 写「一旦有人调用它，就会把 C 侧修掉的 B 侧死锁请回来」） |
| 原因 | D16（保留「B 侧有这个方法」这件事**可对照**） |
| 影响范围 | **零** |
| 严重程度 | 🟢 低 |
| 修复建议 | 不修；**要落盘请用 `detach()`** |
| 验证方法 | 静态（零调用点） |

### F-11 · 命名债：`HotArticleItem` 与 proto message 同名 · 表达面

| 项 | 内容 |
|---|---|
| 位置 | `dto/article/HotArticleItem.java` vs `com.rag.proto.hot.HotArticleItem` |
| B | B 侧**刻意改名** `HotArticleCard` 以避开与 proto message `hot.HotArticleItem`（**只有 2 字段**）同名 |
| C | 沿用 `HotArticleItem` ⇒ 两个同名不同形的类型并存 |
| 原因 | C 侧 DTO 命名沿用**端点语义**（`HotArticlesResp.items` 的元素）⇒ 与 proto 同名 |
| 影响范围 | **零行为影响**（两个类型在不同包，无 import 冲突）⇒ 但**易误用** |
| 严重程度 | 🟢 低（命名债） |
| 修复建议 | 不在本轮改（改名会动 5 处 + JSON 契约面）。**建议下一轮命名债清理统一处理**（`docs/命名债与包形状.md` 已登记 P4-④ 余 6） |
| 验证方法 | 真 Tomcat 响应体断言（13 分量） |

### F-12 · Java 侧 Actuator **单向增强**：B 侧注册的 5 个 api 指标**没有任何端点暴露**

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/resources/application.yml:284-294`（`management.endpoints.web.exposure.include: health,info,prometheus`） |
| B | B 侧的 5 个 `hot_api_*` 指标虽然注册进了 registry，但 **Prometheus 块的 Host 为空 ⇒ `prometheus.StartAgent` 提前 return**；`DevServer` 块在 go-zero v1.10.2 里没有对应字段 ⇒ **没有任何端点暴露它们** |
| C | 接 Actuator ⇒ 它们**真的**出现在 `/actuator/prometheus` |
| 原因 | **表达层替换**（B 侧用独立的 `promhttp` 端口，C 侧用 Actuator） |
| 影响范围 | **只增不改**：多了可观测面，**业务语义与既有指标值零变化** |
| 严重程度 | 🟢 低（单向增强） |
| 修复建议 | 不修（**明确登记为「有意单向增强」**，避免下一个人误以为 C 侧漏了指标暴露） |
| 验证方法 | 指标名与标签取值域逐条核对（`HotApiMetrics` / `HotRpcMetrics`）；`HotEventConsumerRealKafkaTest` 真读到 `article_hot_kafka_consume_total{type}` 的计数 |

### F-13 · 无表域的持久层模板残留（MyBatis-Plus / DataSource）

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/pom.xml`（MP + PG）＋ `application.yml`（`spring.datasource` ＋ `mybatis-plus` 段） |
| B | hot-api / hot-rpc **均无** `domain/*Entity`、**无** `mapper/`、**无** DDL |
| C | 同样**无表**，但**保留了**模板带来的 MP / PG 依赖与配置 |
| 原因 | **全仓模板批量生成**的残留（映射表 §2 决策锁「3 处同时改」）⇒ 属**跨域统一决策**，不在本域写权内 |
| 影响范围 | 装配面（多一个 DataSource ＋ 空 MP 扫描）—— **无业务行为**（本域零 SQL） |
| 严重程度 | 🟢 低 |
| 修复建议 | **不在本域改**（10 个域同款 ⇒ 需一次统一决策）⇒ **NOT_IMPLEMENTED（无表 ⇒ 无职责可承接）** |
| 验证方法 | 上下文真起（MP 自动配置装配成功 ＋ 无 mapper bean） |

### F-14 · 出向上界的**承载键位**从共享层搬到域侧 · 配置面

| 项 | 内容 |
|---|---|
| 位置 | `service/hot/src/main/resources/application.yml`（`hot-api.article-rpc.timeout: 2000` ＋ **刻意不配** `rag.protection.outbound-deadline.cap-millis`） |
| B | `rag.protection.outbound-deadline.cap-millis: 2000` —— **共享层键**；封顶逻辑由共享层 `GoStyleOutboundDeadlineInterceptor`（`@GrpcGlobalClientInterceptor` ⇒ 装在**每个**出向 devh 调用上）**唯一**承担；域侧 `GoStyleGrpcCallDeadline` 只**传播请求预算**、**不封顶** ⇒ 两者合起来 = `min(2s, 请求剩余预算)`。<br>B 侧 yaml 明确写着「**域侧不得再造私有副本**」「共享层 cap **默认 0 = 不启用** ⇒ 不配 = 出向零上限 = **比 Go 松**」 |
| C | `hot-api.article-rpc.timeout: 2000` —— **域侧键**，由 `ArticleRpcClient#stubWithDeadline` 在**唯一出向出口**消费（`min(cap, 剩余预算)`）；共享层 cap **刻意保持未配置**（默认 0 = 不启用） |
| 原因 | **cap 单一来源纪律** —— 若域侧私有常量与共享层 cap 同时生效 ⇒ 出现**两个上界来源** ⇒ 行为不可预期（B 侧共享层自己的棘轮判据 `GoStyleClientCallTimeoutSingleSourceGateTest` 判据④ 即禁止此形态） |
| 影响范围 | ① **键位不同**（配置面）；② **覆盖范围不同** —— B 侧一个共享层 cap 管**两个**出向方向，C 侧域侧键**只管 article-rpc 方向** ⇒ ⇒ **域内直连方向因此没有上界** ⇒ ⇒ **这就是 F-02 的根因**（已单列，不在此重复计数） |
| 严重程度 | 🟡 低（配置面）；**其行为面后果已在 F-02 单独登记** |
| 修复建议 | 不修。⚠️ **不要**同时配两处（那会造出第二个上界来源）；域内断言已把这条纪律**锁住**（`HotServiceApplicationTests#outboundDeadlineCapHasSingleSource`：断言共享层 cap-millis **为 null** ＋ 域侧值为 **2000**） |
| 验证方法 | ✅ `HotServiceApplicationTests`（运行期读 `Environment` ＋ 绑定的 `HotApiProperties`）⇒ 键位与「单一来源」两条都断言 |

### F-15 · 🔴 共享层的「HTTP 预算」与「业务失败」**竞态** ⇒ 客户端可能收到**混血响应**（200 + `application/json` ＋ 纯文本 body）· 响应契约面

| 项 | 内容 |
|---|---|
| 位置 | **共享层**（`common/`）：<br>· `common/src/main/java/com/rag/common/observability/HttpMiddleware.java:438-448`（超时分支：`rec.markTimedOut()` → `workerFuture.cancel(true)` → `rec.writeTimeoutResponseQuietly(503)` ⇒ **直接写底层响应**）<br>· `common/src/main/java/com/rag/common/observability/ObservabilityResponseWriter.java:315-322`（`writeTimeoutResponseQuietly` ⇒ 绕过 `writeToOriginal`，**直写 original**）<br>· 同文件 `:264-275`（`flushBuffer()` ⇒ **异常处理路径**会调它 ⇒ **把响应 commit 掉**，而此时 body 还在缓冲里）<br>· 同文件 `:178-213`（`getOutputStream()` ⇒ body 写进**捕获缓冲**，**不直发**） |
| 现象 | 业务失败时延 **≈ HTTP 预算**时，**两条写路径并发写同一个响应**：<br>① **容器线程**（预算耗尽）⇒ `setStatus(503)` + `setContentType(text/plain)` + 写 `Request Timeout\n` + `flush()` ⇒ **commit**；<br>② **worker 线程**（业务异常）⇒ `@ExceptionHandler` 路径调 `flushBuffer()` ⇒ **先把「200 + application/json + 空 body」commit 掉**（body 还在捕获缓冲里）；<br>⇒ ⇒ 谁先落定，客户端就拿到**不同形状**：<br>· ① 先 ⇒ `503 + text/plain + "Request Timeout\n"`（干净）<br>· ② 先 ⇒ `200 + application/json + body "Request Timeout\n"` ⇒ ⇒ **混血**：状态码与 Content-Type 是业务信封的，**body 却是纯文本** ⇒ ⇒ **信封契约被打断**（客户端按 JSON 解会失败）<br>· （第三种：`200 + application/json + 5001 信封` —— worker 在预算到点前正常收尾并 flush ⇒ **干净**） |
| B | **B 侧是同一份共享层**（同一套代码）⇒ **同样有这个竞态**；但 B 侧 hot 的业务失败落在 **2s**（gRPC 出向封顶）⇒ **远早于** 3s HTTP 预算 ⇒ ⇒ **B 侧永远命中不到这个窗口** ⇒ B 侧读数恒为 `200 + 5001 信封` |
| C | **C 侧因 F-02（D2 直连 ⇒ 域内方向无 2s 上界）把业务失败时延推到了 3s 预算线上** ⇒ ⇒ **正好落进这个竞态窗口** ⇒ ⇒ C 侧形状**非确定** |
| 归属 | 🔴 **共享层（`common/observability/`）** ⇒ ⇒ **修法属收口域（`global`）** ⇒ ⚠️ **本域只登记、不改**（AGENTS.md §五.3 规则 3：`common/**` 只有收口任务能写）<br>⚠️ **不是 hot 域引入的缺陷**（两侧共享层代码同源）⇒ hot 域的角色 = **把它暴露出来并登记** |
| 影响范围 | **响应契约面**：受影响的那个请求，客户端可能拿到**非信封**的 body ⇒ 客户端的「解信封」逻辑会抛异常（而不是拿到 `code:5001`） |
| 严重程度 | 🔴 高（**响应契约面**；触发条件苛刻 ⇒ **难复现** ⇒ 这也是它此前一直藏着的理由） |
| 修复建议 | **不在本域改**。收口域的修法方向（供 `global` 裁决，**本域不预设结论**）：<br>① 让超时分支与 worker 的收尾**互斥且幂等**（谁先落定，另一个必须**完全放弃**，不得再写 status / CT / body）；<br>② 或：worker 的 `flushBuffer()` 路径**不要先 commit 空 body**（让捕获缓冲里的 body 一起提交）；<br>③ ⚠️ **修共享层时必须同时验「hot 域 + 其它有 HTTP 预算的域」** ⇒ 这是**全仓**的面 ⇒ ⇒ 归 `global` 的验收范围 |
| 验证方法 | ✅ **实测存在**（§九·M5 · 2026-11-04）—— 三轮跑出**三种形状**：候选①（200＋信封）/ 候选②（200＋json＋纯文本 body）**实测命中** / 候选③（503＋纯文本）**实测命中** ⇒ ⇒ 三形状**全部冻结**在探针里（形状变了 ⇒ 判失败）<br>⚠️ **尚未证伪「只有 hot 域会命中」** —— 那需要**收口域**在别的域上复现 ⇒ **本域不作结论** |

---

## 六、完成度统计（§11.6 · 分开统计，**不用文件数量代表完成度**）

| 分类 | 数量 | 明细 |
|---|---|---|
| **已实现且已验证（PASS）** | **48** | §二：**37**（C-01/02/03/**05** 共 5 · A-01~A-13+A-16 共 14 · R-01~R-09 共 9 · M-01~M-05 共 5 · X-01~X-04 共 4）<br>§五：**10**（F-04 · F-06 · F-07 · F-08 · **F-09**〔2026-11-04 更正：**原归因错误 ⇒ 实测两侧相同 ⇒ 非漂移**，见 §九·9.3 · 分类计数不变〕 · F-10 · F-11 · F-12 · F-14）<br>＋ **F-02 的「域内查询卡住」子路径**（2026-11-04 · §九·M5 实测 ⇒ 由 NOT_VERIFIED 转 PASS） |
| **已实现但未验证（NOT_VERIFIED）** | **0** | ✅ **本轮归零** —— 三条全部收口：① **C-04 / F-05**（`GrpcErrorMapper` 零调用点）⇒ **静态 ＋ 行为 ＋ 可执行守卫**三证（§五·F-05）② **F-02 的「域内查询卡住」子路径** ⇒ `CLIENT PAUSE` 注入慢 Redis ⇒ **双侧活体实测**（§九·M5） |
| **尚未实现（NOT_IMPLEMENTED）** | **1** | F-13（无表域的持久层职责 ⇒ **无职责可承接**，非遗漏） |
| **明确存在漂移（FAIL）** | **0** | ✅ **本域无 FAIL** |
| **框架差异待验证（FRAMEWORK_DIFFERENCE）** | **1** | A-15（form 解析口径 · 共享层判据已实测与 B 一致 ⇒ 最终行为 **PASS**，差异保留在 §四·4.6 清单内） |
| **被外部条件阻塞（BLOCKED）** | **0** | ✅ |
| **已批准的主动变更（INTENTIONALLY_CHANGED）** | **3** | A-14（D2 删除 `HotRpcClient`）· A-17（`WebMvcConfig` 空实现显式化免鉴权）· F-02（D2 连带：丢一层 2s 上界）—— ⚠️ **2026-11-04 实测升级**：其可观察后果**不止时延**，还有**失败形状**（§五·F-02 / §九·M5） |
| **已登记的部署面 / 表达面签字偏离（不影响业务语义）** | **2** | F-01（端口 8895）· F-03（域内自持 2 个 form 方法） |
| **🔴 共享层缺陷（归属收口域 · 不计入本域 PASS/FAIL）** | **1** | **F-15**（共享层「HTTP 预算 × 业务失败」竞态 ⇒ **混血响应**）⇒ ⚠️ **两侧共享层代码同源** ⇒ **不是本域引入** ⇒ **修法属 `global`**（AGENTS.md §五.3 规则 3） |

> 📌 **计数口径**：逐条承接条目共 **55 条** = §二 的 **40 行**（契约 5 ＋ HTTP 17 ＋ 领域 9 ＋ 消息 5 ＋ 装配 4）＋ §五 的 **15 条**（F-01~F-15）；
> 另加 **1 个子项**（F-02 的「卡住」路径）⇒ 合计 **56 个归类单元** = 48 + 0 + 1 + 1 + 3 + 2 + 1。
> ⚠️ **§四 的 6 条框架差异不单列** —— 它们是 55 条条目**背后**的机制说明（每条都注明「最终行为结论 = PASS」）。
> ⚠️ **不按文件数量、不按代码行数**统计完成度（§11.6 明文要求）。
> ⚠️ **FRAMEWORK_DIFFERENCE / NOT_VERIFIED / NOT_IMPLEMENTED / BLOCKED 均不计入 PASS**（§十 明文）。
> 📌 **「已实现且已验证」的每一项都带**行号可点验的证据（源码位置 ＋ 测试类 ＋ 机器读数）。

---

## 七、§十四 · 12 项必答

| # | 问题 | 回答 |
|---|---|---|
| 1 | C 是否完整承接 B 的全部实际职责？ | **是** —— **29 个 C 类承接 30 个 B 类**（**28 条 1:1** ＋ **1 条 N:1**：两个入口合到 `HotServiceApplication`）＋ **4 个删除/上收**（`HotRpcClient`/D2 · `GoStyleGrpcCallDeadline`/落配置 · `GoFormValues`/上收 `common/` · `Jackson2ObjectMapperConfiguration`/上收共享层）＋ **2 个 C 新增**（`GrpcErrorMapper` · `WebMvcConfig`）⇒ **29 + 2 = 31 个 C 类**，B 侧 **34 = 30 承接 + 4 删除**，**零遗漏、零 FAIL**。⚠️ 残留：**F-02** 的「域内 rpc 2s 上界」是 D2 的**已批准连带后果**，**F-05/F-13** 是零调用点 / 无职责 ⇒ 非遗漏 |
| 2 | Mapping_Table 的 RAG → RAG-V1 映射是否与当前真实代码一致？ | **是** —— 守门 ① `check-mapping-coverage.py` ⇒ **EXIT=0**，**hot 行 C 31/31 · B 34/34 · 100%**。⚠️ 映射表 §1.2 把 `WebMvcConfig` 标为「🔴 待确认 / 疑似多余」⇒ **本轮已消解**（F-04：刻意保留空实现，把免鉴权显式化）⇒ **该行需在映射表侧更新措辞**（**本轮不写映射表**） |
| 3 | File_Tree 规定的目标结构是否正确落地？ | **是** —— 31 个主类**全部在 `docs/File_Tree/hot.md` 列出的位置**（`contract/` 5 ＋ `dto/article/` 3 ＋ `advice/` 2 ＋ `config/` 5 ＋ `client/` 1 ＋ `controller/` 1 ＋ `domain/model/` 1 ＋ `grpc/` 1 ＋ `messaging/` 4 ＋ `metrics/` 2 ＋ `service/` 4 ＋ `wiring/` 1 ＋ 入口 1）；守门 ③ **EXIT=0**（双向覆盖 ＋ 幂等 ＋ 路径 ＋ 落点计数 ＋ 包名） |
| 4 | 多文件合并是否造成职责、分支或异常路径遗漏？ | **否** —— §三·3.1 逐条核对了 B 侧两个入口的全部职责；§二·2.4 把 `mq/` 5 类的**每一条分支**（校验顺序、先计数后查分区、锁协议、TTL 硬编码、抢不到锁整轮跳过）对到了 C 侧；**fatal（hot-rpc 失败 ⇒ 整包 5001）vs best-effort（article-rpc 失败 ⇒ skip 该条）的不对称逐条保留** |
| 5 | 文件拆分是否造成调用链、数据传递或事务边界断裂？ | **否** —— §三·3.2：① 调用链闭合；② proto item（2 字段）→ DTO（13 分量）→ 响应 `items` 逐字段在；③ 异常传播不对称**保留**；④ **本域无事务**（无表）；⑤ 无重复/遗漏执行；⑥ 初始化时机（两条 tick ＋ 池 2 线程）**B 侧是两条独立 goroutine ⇒ C 侧也给 2 个调度线程** |
| 6 | API + RPC 合并是否改变原有入口和执行行为？ | **入口数变了**（2 → 1，D1）；**入口行为没变** —— HTTP 端点仍只有 `GET /hot/v1/articles`，gRPC 仍 5 个方法，**两个口在同一进程里真跑过**（真 Tomcat ＋ 真 gRPC server）。⚠️ **两处可观察差异已登记**：**F-01**（HTTP 端口）**F-02**（域内 rpc 少一层 2s 上界，根因见 **F-14**）。⚠️ **一处真实缺陷本轮已修**：**F-06**（补全池抑制 `applicationTaskExecutor` ⇒ 上下文起不来） |
| 7 | Common Starter 上收是否遗漏原有消费方？ | **否** —— §三·3.3 逐条列了 hot 域消费的 6 项共享能力（信封 / logger / observability+`MqConsumer` / RPC 拦截链 / form 闸门 / Jackson 补丁），**原消费方全部覆盖**。⚠️ 唯一缺口 = A-15：`GetFormValuesFilter` **没有** `int32` / `firstFormValue` ⇒ 域内自持（**F-03**） |
| 8 | Spring Boot 4 → 3.5 是否引入业务可观察行为变化？ | **引入了 1 处**（2026-11-03 原写 2 处，其中 §4.3 那条已由 §九·9.3 **证伪**）：**§4.5 零重试**（Boot 默认重试 9 次 ⇄ B 侧永不重试）—— **已显式对齐 ＋ 变异验证**。⚠️ **§4.3 Tomcat 状态行**：**实测两侧逐字节相同**（B 侧也是 Tomcat 11.0.24，同样不发 reason phrase）⇒ **不是行为变化** ⇒ 原「框架限制、不可修」的判定**前提不成立**。其余（Kafka 坐标 / Jackson 3→2 / `@ConfigurationPropertiesScan` / Redis 客户端）**均已证明不影响业务行为** |
| 9 | Tomcat、Jackson、Spring Framework 等底层版本变化是否产生实际漂移？ | **Tomcat**：**零** —— 404 / 405 的状态行、`Content-Type`、body **两侧逐字节相同**（**F-09 原判定已作废**，见 §九·9.3）。**Jackson**：**零**（本域只用三处两版同义注解）。**Spring Framework**：**零业务漂移**（装配差异见 §4.4，其唯一真实后果 **F-06** 已修）。**Kafka 客户端**：**零**（零重试已显式对齐） |
| 10 | 哪些差异已经通过证据证明安全？ | **10 项**：§4.1 Kafka 坐标 · §4.2 Jackson 3→2 · §4.3 Tomcat 业务语义部分 · §4.4 装配差异 · §4.5 零重试 · §4.6 Redis 客户端 / Nacos / 生命周期 · **F-08** `rag.side` 无消费点 · **F-13** 模板残留无业务行为 · **F-07** B 侧死锁已消解 · **F-14** 出向键位差异（article-rpc 方向有效值相同） |
| 11 | 哪些差异仍然无法确认？ | **1 项**（**2026-11-04 由 3 项收口到 1 项**）：**F-11 命名债**（`HotArticleItem` 与 proto message 同名 ⇒ 行为面已验，**命名面**待下一轮统一清理，`docs/命名债与包形状.md` 已登记 P4-④）。<br>⚠️ **本轮另外两条已收口**：**F-05 / C-04**（零调用点 ⇒ 静态＋行为＋可执行守卫三证）· **F-02 的「域内查询卡住」**（⇒ §九·M5 双侧活体实测）。<br>⚠️ **新增一条「尚未证伪」**（不是本域差异，是**共享层缺陷的边界**）：**F-15** 的混血响应**是否只有 hot 域会命中** —— 需**收口域**在别的域上复现 ⇒ **本域不作结论** |
| 12 | 当前 C 是否具备可靠的行为对拍条件？ | **已具备（E-4）** —— ✅ **2026-11-04 补做双侧活体对拍**：`tools/difftest/hot/`（**31 条逐字节断言 ＋ 1 个超时面 · C == B**）：HTTP（真 Tomcat ＋ **裸 socket** 读原始状态行 + body hex · 18 条）· gRPC（**手构 proto3 字节 + `byte[]` marshaller** · 9 条）· Redis（真容器 · 成员 + 分数）· Kafka（三面：发同款事件 → 两侧消费 → HeavyKeeper → 合并 → 比 `hot:articles` ＋ 合并后 HTTP）· **M5 超时面**（`CLIENT PAUSE` 注入慢 Redis ⇒ 比**失败形状 + 失败时延**）⇒ 详见 **§九**。<br>✅ 另具备：HTTP（真 Tomcat ＋ 裸 socket 逐字节）· gRPC（**本域 5 个方法**走真进程内 gRPC ＋ **跨域**走真进程内 `ArticleServiceGrpc`）· Kafka（真 broker ＋ 真实 topic/group/分区）· Redis（真容器跑 Lua ＋ TTL）· 错误契约（11 码 ＋ 11 英文文案 ＋ 未知码回退，**逐条机器断言** ＋ 变异转红）· **gRPC 错误映射**（借 B 侧 `HotServiceGrpcImplErrorMappingTest` 的期望值 ⇒ 裸异常 ⇒ **UNKNOWN 非 INTERNAL** ＋ description = 原始错误文本）· HeavyKeeper（**BigInteger 独立重写的差分实现**（与生产实现不共用算式 ⇒ 抓「掩码/符号位」三类错法）＋ FNV-1a 32 **公开已知常量**（空串/a/foobar）＋ uint32 回绕与向零截断）。<br>⚠️ **仍不具备**：**Go 真源活体**（本机无 Sea-etcd 容器）⇒ ⇒ **本报告读作「与 B 侧逐字节一致（E-4）」，不读作「三方一致」** |

---

## 八、本轮写权与自检

| 项 | 结果 |
|---|---|
| 写入范围 | `service/hot/**`（31 个主类 ＋ `application.yml` ＋ 15 个测试类）· `docs/DRIFT/hot/**`（本文件）· `docs/File_Tree/hot.md`（**生成器产物**，按 §一·1.6 处理）<br>⚠️ **2026-11-04 追加**：`service/hot/pom.xml`（**修一个真实缺陷**，见 §九·9.4）＋ `tools/difftest/hot/**`（**新增对拍探针**，见 §九） |
| **未碰** | `docs/DRIFT/_INDEX.md` · `docs/Intentional_Deviations/_INDEX.md` · `docs/Pitfalls/_INDEX.md` · `AGENTS.md` · `common/**` · `docs/Mapping_Table/**` · `tools/**`（ROOT 补丁**已还原**；2026-11-04 **仅新增** `tools/difftest/hot/` 子目录，**未改**任何既有工具） |
| 主仓状态 | **本轮未碰主仓的代码与文档** ⇒ `git status --porcelain -- proto docs service common tools` = **空**（零产物）<br>⚠️ 主仓工作区另有 `Book/**`（17+13 个 `.md` 改动）与 `Book/.obsidian/`、`脑图整理/.obsidian/` ⇒ **不是本轮产物**（mtime 01:48 / 01:57，落在本轮**变异验证**那段窗口，但本轮**没有任何命令写过 `Book/`**）⇒ 属**另一条 lane** 的作业 ⇒ **本轮不碰、不回滚** |
| 生成器副作用 | `gen-file-tree.py` 硬编码 ROOT ⇒ 首次运行改的是**主仓**（内容逐字节相同 ⇒ 无差异）⇒ 已按 §一·1.6 用 ROOT 补丁在**工作树**重跑<br>⚠️ **2026-11-04（E-4 轮）再撞同一处，处置已升级**：本轮用 ROOT 补丁跑 `check-docs-vs-tree.py` ⇒ 它的 **②「File_Tree 幂等」判据第一次真正生效**（生成器把产物**写回工作树**再逐字节比对 ⇒ 不再是「比对同一份文件自己」的**空转判据**）⇒ ⇒ 随即报出 **`docs/File_Tree/proto.md` 漂移**（**只少了 `proto/src/test/java/`**）<br>⇒ **根因**：`proto/src/test/java/` 是**主仓工作区里的一个未跟踪空目录**（git 不跟踪空目录 ⇒ **从未进过仓**）⇒ 而提交 `118c255`（「重跑文件树生成器修正 proto.md 回归」）把**含 `src/test/java/` 的版本定为规范版**<br>⇒ **处置**：在工作树补出该空目录（`mkdir -p proto/src/test/java` ⇒ **git 不可见**、零足迹）⇒ 重跑 ⇒ **三道守门全绿** ＋ `docs/File_Tree/` **零改写**（⇒ 本轮的 `pom.xml` 改动与 `tools/difftest/hot/` 新增**都不影响文件树**：`pom_deps()` 只取 `groupId:artifactId`（**不含 scope**）· 且 `tools/` 不在生成器的扫描根内）<br>⚠️ **proto.md 本身已还原**（跨模块 · **不在本域写权内**）⇒ **不提交**它的任何改动 |
| **三道守门读数（2026-11-04 · E-4 轮 · 在工作树跑）** | ① `tools/difftest/check-arch-invariants.py`（A1–A13）⇒ **`EXIT=0`**（A3/A4/A5/A6/A7/A9/A10/A11/A12/A13 全绿）<br>② `tools/difftest/check-docs-vs-tree.py` ⇒ **`EXIT=0`**（File_Tree 双向 · **幂等** · 路径 · 落点计数 · 包名）<br>③ `tools/check-mapping-coverage.py` ⇒ **`EXIT=0`** —— **hot 域 C 列 31/31（100%）· B 列 34/34（100%）**；全仓 C 642/642 · B 752/752 · B 列全局未覆盖 0<br>⚠️ ① 与 ② 的 ROOT 是**相对**的（`pathlib.Path(__file__).resolve().parents[2]`）⇒ **量的是工作树** ✅；③ 的 ROOT **硬编码主仓** ⇒ 本轮**同样临时打补丁**到工作树后跑（读完数即还原 ⇒ 已断言「与 HEAD 逐字节一致」） |
| ⚠️ **「全仓 `mvn -o clean verify`」本轮未跑** | **不是遗漏**：AGENTS.md **P-18** 记载「域还是空壳 ⇒ `repackage` 失败 ⇒ 全仓 `mvn verify` 恒红」⇒ 其它 9 个域中仍有空壳 ⇒ **全仓红与 hot 域无关**。⇒ 本轮按**域级**口径跑 `mvn -o -pl service/hot -am package`（含 `repackage`，产物已验证是可执行 Boot jar）⇒ **hot 域自身**的编译 / 测试 / 打包全绿 |
| ⚠️ **`docs/File_Tree/hot.md` §五「状态」段仍写「全部待填」** | 那是 **`gen-file-tree.py` 的固定文案**（`article.md` / `like.md` 同样如此 ⇒ **全仓一致**）⇒ **不是** hot 域仍是空壳的证据。⇒ 本轮**不改生成器**（`tools/` 不在本域写权内）⇒ 已在 §一·1.6 留痕 |

---

## 九、双侧活体对拍（E-4 · 2026-11-04 补做）

> 📌 **本节是本文件证据强度从 E-3 升到 E-4 的唯一依据**。
> **探针脚本**：`tools/difftest/hot/`（`run.sh` ＋ `http_probe.py` ＋ `grpc-wire/HotWireProbe.java` ＋
> `article-rpc-stub/ArticleRpcStub.java` ＋ `mutate-parity.sh` ＋ `README.md`）。
> **读数**：`2026-11-04` 全量跑 ⇒ **`EXIT=0`** ⇒ **31 条对拍断言 + 3 条自检闸门全部通过**。

### 9.1 判定口径与**边界**

| 项 | 内容 |
|---|---|
| 被测两侧 | **B 侧** = `/Users/pr/Documents/java&golang/RAG/service/hot/{hot-api,hot-rpc}`（**Boot 4.0.8** · Tomcat 11.0.24）<br>**C 侧** = 本仓 `service/hot`（**Boot 3.5.11** · Tomcat 10.1.52） |
| 判定条件 | **C == B**（两侧**同时起、同时活、同请求、逐字节相等**） |
| 证据强度 | **E-4** |
| ⚠️ **没有第三方锚** | 本机**没有 Go 真源可活体**（无 Sea-etcd 容器）⇒ ⇒ **本节读作「与 B 侧逐字节一致」，不读作「三方一致」**。<br>Go 侧的冻结字节锚在 B 仓 `tools/api-full-parity-probe/hot/` —— 本探针**刻意不引用**它：<br>**引用一个本机不可复现的锚 ⇒ 锚坏了也看不出来 ⇒ 那是「摆设锚」**。 |
| B 侧只读 | 只在 B 仓跑 `mvn package`（产物落 `target/`，已被 `.gitignore` 排除）⇒ **不碰 B 侧任何源码 / 配置**（脚本第 0 步与第 2 步**各断言一次** `git status` 干净） |

### 9.2 隔离拓扑（**每条隔离都是正确性要求，不是「省资源」的取舍**）

| 隔离项 | 为什么必须 |
|---|---|
| **Nacos 专属命名空间** `hotprobe` | 与其它 lane（`public` / `probe-b-smoke` / `probe-c-smoke`）完全隔离 |
| **Nacos 分组**：B 侧 `DEFAULT_GROUP` / C 侧 `E5C` | 两侧的服务名**都叫 `hot-rpc`** ⇒ 不分组 ⇒ 解析池里混进对面的实例 ⇒ **对拍对象静默地错了** |
| ⚠️ **B 侧的组是「半隔离」** | B 侧 hot-rpc 的 `NacosRegistration` **硬编码 `DEFAULT_GROUP`**（`bootstrap/NacosRegistration.java:58,113`）⇒ 探针**不能改 B 侧任何文件** ⇒ 只能让 B 侧 hot-api 也用 `DEFAULT_GROUP`（它的 yml 是 `${NACOS_GROUP:DEFAULT_GROUP}` ⇒ 探针**不传** `NACOS_GROUP` 给 B 侧）⇒ 两者**天然对上** |
| **2 个独立 Redis 容器** | `hot:articles` 是**写路径**（Kafka 合并会 `RENAME` 覆盖它）⇒ 共用一个 ⇒ B 侧合并完 C 侧再合并 ⇒ 读数**必然不同** ⇒ **假漂移** |
| **2 个独立 Kafka broker** | 两侧的 `spring.kafka.consumer.group-id` 都是**字面量** `article-hot-group`、topic 都是**字面量** `article-hot-events`（**B 侧不可覆盖**）⇒ 共用一个 broker ⇒ 两侧互吃对方的消息 ⇒ **假漂移** |
| **article-rpc 桩 = 同一个进程、两个组各注册一次** | 若两侧各连一个**不同的** article-rpc ⇒ 返回的 article 投影只要有任意差异（哪怕随机 `create_time`）⇒ 热榜卡片就不同 ⇒ **差异被归因到 hot 域头上** ⇒ 假漂移。<br>⇒ 同一进程 ⇒ 两侧拿到**逐字节相同**的投影 ⇒ 任何差异**只能**来自 hot 域自身 |

### 9.3 读数（**2026-11-04 · 全量跑 · `EXIT=0`**）

| 面 | 比什么 | 条数 | 读数 |
|---|---|---|---|
| **M1 · HTTP 字节面** | **原始状态行**（含 HTTP 版本与 reason phrase）＋ `Content-Type` ＋ body 的 hex | **18** | ✅ **18/18 逐字节一致** |
| **M2 · gRPC wire 字节面** | **原始 proto3 字节**（手构请求 ＋ `byte[]` marshaller 原样取响应） | **9** ＋ 1 自检 | ✅ **9/9 逐字节一致** ＋ ✅ top_k 边界**可分辨** |
| **M3 · Redis 状态面** | `hot:articles` 的 `TYPE` / `ZCARD` / 成员 / 分数 | **1** | ✅ 逐字节一致（33 个成员 · 含 `probe-a3` 的 `3000000000`） |
| **M4 · Kafka 三面** | 发同款事件 → 两侧消费 → HeavyKeeper → 合并 → 比 `hot:articles` ＋ 合并后 HTTP | **3** | ✅ 逐字节一致（两侧都合并到 **600** 分） |
| **M5 · 超时面** | `CLIENT PAUSE` 让两侧 Redis **卡住** ⇒ 比**失败形状 + 失败时延**（**已登记的差异 F-02** ⇒ 判据 = **形状冻结**，不是「两侧相等」） | **1 面 · 4 条判据** | ✅ **形状符合登记**（B：`200` ＋ 5001 信封 @**2011ms** · C：`503` ＋ `Request Timeout\n` @**3014ms**）＋ ✅ 场景有效性（两侧都 ≥1.5s）＋ ✅ 恢复校验（窗口过后两侧逐字节一致）⚠️ 计入**已知现象**（两侧本来就不同） |
| **合计** | | **31 条逐字节断言 ＋ 1 个超时面（4 条判据）＋ 3 条自检闸门** | ✅ **真差异 0 · 已知现象 1**（**2026-11-04 第四轮 · EXIT=0**） |

**关键读数（逐字）**：

| 断言 | 读数 |
|---|---|
| `unknown_path`（404） | 两侧**同为** `HTTP/1.1 404 ` ＋ `text/plain;charset=utf-8` ＋ body `404 page not found\n` ⇒ **逐字节相同** |
| `method_not_allowed`（405） | 两侧**同为** `HTTP/1.1 405 ` ＋ **空** `Content-Type` ＋ **空** body |
| `page_not_a_number` / `page_int32_overflow` / `malformed_json_body` / `json_body_not_object` | 两侧**同为** `HTTP/1.1 200` ＋ `{"code":5001,"msg":"internal server error","data":null}` |
| `default` 的 `hot_score` | `probe-a3` 的 `hot_score` = **`3000000000`**（> 2^31）⇒ **uint32 的无符号还原在两侧都生效**（若有一侧写成负数 ⇒ 字节必不同 ⇒ 本条即证伪） |
| `page2_size2` 的 `rank` | 第 2 页首条 `rank` = **3**（不是 1）⇒ **rank 跨页连续**在两侧都成立 |
| `page20_out_of_range` | 两侧**同为** `items:[]` ＋ `total:29` |
| `wire_topk_0` | 请求体 **0 字节**（proto3 默认值不上 wire）⇒ 两侧响应字节一致（716 字节行） |
| `wire_topk_neg1` | 请求体 **10 字节**（int32 负值按 64 位符号扩展编码）⇒ 两侧响应字节一致（719 字节行） |
| `wire_topk_20` vs `wire_topk_100` | 响应行长 **717 vs 1212** ⇒ **两条 clamp 边界可分辨**（20 条 vs 33 条）⇒ 「`k<=0 ⇒ 20`」与「`k>100 ⇒ 100`」**两条契约都被覆盖** |
| `wire_like_article` / `wire_coin_article` / `wire_share_article` | 两侧**同为** `UNIMPLEMENTED` ＋ description 字节**逐字相同**（`LikeArticle not implemented yet` 等） |
| `wire_comment_article` | 两侧**同为** `success=true`（B 侧那个**不对称**的桩，逐字保留） |
| M4 合并终态 | 两侧**同为** `probe-kafka-1 600`（TTL B=3592 / C=3598 ⇒ **各自从本侧合并时刻起算 ⇒ 不相等是正常的**） |

### 9.4 探针**抓到并修掉**的一个真实缺陷（**不是探针的问题，是 C 侧的问题**）

| 项 | 内容 |
|---|---|
| 现象 | `java -jar service/hot/target/hot-service-1.0-SNAPSHOT.jar` **启动即失败**：<br>`BeanCreationException: Error creating bean with name 'shadedNettyGrpcChannelFactory'`<br>→ `Caused by: java.lang.NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder` |
| 根因 | `service/hot/pom.xml` 把 `io.grpc:grpc-inprocess` 写成了 **`test` scope** ⇒ **直接声明压过传递声明** ⇒ 运行时 classpath 上整个 artifact 消失（fat jar 的 `BOOT-INF/lib` 里也没有）⇒ devh 的 `shadedNettyGrpcChannelFactory` 链接 `InProcessChannelFactory` 失败 ⇒ **`ArticleRpcClient` Bean 创建失败 ⇒ 整个服务起不来** |
| 为什么测试没发现 | `mvn -o -pl service/hot -am test` = **70/70 全绿** —— 测试跑在 **test classpath** 上 ⇒ `grpc-inprocess` 本来就在 ⇒ **缺陷看不见** ⇒ AGENTS.md §3.3.3「**跑绿 ≠ 本来就绿**」的教科书形态 |
| 修法 | `grpc-inprocess` 的 scope 改回 **compile（默认）**；注释逐条对齐 **like 域 2026-11-03 的同款修复**（B 出处 = B 侧 `service/like/like-rpc/pom.xml:313-344`） |
| **发现者** | **本探针**（E-4）—— 因为它起的是**真 jar**（`java -jar`），而**不是**测试 classpath |

### 9.5 探针**自己**的两个判据空洞（**都是被变异验证逼出来的**）

> ⚠️ 这一节是本轮**最值钱**的部分：**「全绿」不等于「验到了东西」** —— 探针自己也会有洞。

#### 9.5.1 数据面空洞 —— 第一轮 E-4 的 HTTP 面**全在比两个空壳**

| 项 | 内容 |
|---|---|
| 现象 | 第一轮 E-4 跑出来 **「31 条全绿」** ⇒ 但**读数里 `items:[]`、`total:0`** ⇒ **两侧都在比「空热榜」** ⇒ 那是**假绿** |
| 根因 | 探针的 `free_probe_ports`（会 `kill -9` 占用端口的进程）**在 article-rpc 桩启动之后**才被调用 ⇒ **把自己的桩踢掉** ⇒ 两侧的补全**整片 skip**（`article_rpc_error` 分支）⇒ 热榜恒空<br>⇒ ⚠️ **wire 面读的是 ZSet（仍有 33 条）⇒ wire 面的「有数据」掩盖了 HTTP 面的空洞** |
| **怎么发现的** | 🚨 **变异验证的 V4**：「只给 B 侧多播一个成员」⇒ **预期** `default` 会红（两侧 items 不同）⇒ **实得绿** ⇒ ⇒ **暴露了 HTTP 面根本没在读数据** |
| 修法 ① | `free_probe_ports` 移到**桩启动之前** |
| 修法 ②（更关键） | 加**数据面闸门**：播种后断言「**C/B 两侧返回的 `total` ≥ 期望存活条数**（33 − 4 = 29）」⇒ 数据面没跑起来 ⇒ **直接 die（exit 2），不给结论** |
| ⚠️ **闸门为什么用「≥」而不是「==」** | 判据只管「**数据面在不在**」，**不管「两侧一不一样」** ⇒ ⇒ 必须容许 V4 那种**刻意不对称**的播种（B 侧多播一个成员 ⇒ B 侧 `total`=30、C 侧 29）⇒ 若用 `==` ⇒ V4 会被闸门误判成「数据面没跑起来」⇒ ⇒ **误 die**（2026-11-04 第一轮 V4 即踩此坑）。<br>⇒ 「两侧一不一样」由 **M1/M2/M3 三面的逐字节比对**负责 ⇒ **两个职责不许混在一个判据里** |

#### 9.5.2 `top_k` 边界空洞 —— 两条 clamp 契约**根本没验到**

| 项 | 内容 |
|---|---|
| 现象 | 种子只有 8 条 ⇒ `top_k` 取 20 / 0 / -1 / 100 / 101 **返回的是同一批 8 条** ⇒ 响应字节**完全相同** ⇒ 「`k<=0 ⇒ 20`」与「`k>100 ⇒ 100`」**两条契约验不到** ⇒ 那是**判据空洞**（不是「跑绿」） |
| 修法 ① | 种子补 **25 条填充成员**（分数 1..25，**低于**空成员的 40 ⇒ 不打乱前 4 名的 rank 连续性断言）⇒ 库里 33 条 ⇒ `top_k∈{20,0,-1}` 返回 **20** 条、`top_k∈{100,101}` 返回 **33** 条 ⇒ **两条边界各自显形** |
| 修法 ② | 加**自检断言**：`top_k=20` 与 `top_k=100` 的响应字节**必须不同** ⇒ 否则直接报红 ⇒ **种子退化了不会默默「全绿」** |

#### 9.5.3 🔴 解析器空洞 —— 慢请求的响应**前面带 1xx** ⇒ 整份读数错位（**本轮第三处 · 由 M5 面逼出来**）

| 项 | 内容 |
|---|---|
| 现象 | M5 面第一次跑出来，两侧读数是**一长串 hex**（状态行取到了 `103 Early Hints`）⇒ 场景有效性闸门判定「读数非法」⇒ **exit 2 · 不给结论**（✅ 闸门按设计生效） |
| 根因 | 共享层的 `ClientDisconnectProbe` 会**直接往 socket 写** `HTTP/1.1 103 Early Hints\r\n\r\n` 作断连探针（**行数随 socket 写时机变**）⇒ **慢请求**（M5 要 2~3s）的响应流**前面带若干个 1xx 块**；而 `http_probe.py` **按第一个 `\r\n\r\n` 切分** ⇒ 把 1xx 当成最终响应 ⇒ **状态行 / Content-Type / body 三项全部错位** |
| 为什么以前没暴露 | M1 的请求都是**毫秒级**返回 ⇒ 探针一个 1xx 都没写出来 ⇒ 解析器「恰好对」⇒ ⇒ **缺陷只在慢请求上显形** ⇒ 而本域此前**没有慢请求用例** |
| 修法 ① | 解析器改成「**跳过所有 1xx、只取最终响应**」（RFC 9110 §15.2）⇒ 1xx 块后面紧跟下一个块 ⇒ 循环里把「本块 body 起点」当下一块起点 |
| 修法 ② | **把解析器提成有自检的判据**：`http_probe_selftest.py` 用**合成字节流**喂 5 类形态（普通 / 1xx 前缀 / 1xx 前缀 + chunked / 无响应 / `100 Continue`）⇒ **每次探针运行都执行**（挂在 step 3 · **起服务之前**）⇒ ⇒ 解析器回退 ⇒ **在起服务之前就死**，而不是跑到一半拿着错读数给结论 |
| 附带（探针自己的第二个错） | M5 面的字段索引**整体错位一格**（读数格式是 `TIMEOUT|<状态行>|<CT>|<body hex>|<耗时>` ⇒ 第 1 段是字面量 `TIMEOUT`，**不是数据** ⇒ 早先写成 1..4 ⇒ 状态行取到 `TIMEOUT`、耗时取到 body hex）⇒ ⇒ **症状同样是「形状对但字段乱」** ⇒ ⇒ 与 ① 的症状**同型** ⇒ ⇒ 两者都被「场景有效性断言」拦下 ⇒ ⇒ **这正是那条断言的价值：它不问你「对不对」，只问「这还是不是我能读的数」** |

### 9.6 变异验证（**证明探针不是假绿**）

`bash tools/difftest/hot/mutate-parity.sh` ⇒ **五条变异**，每条**先定判据、再看结果**（AGENTS.md §3.3.3「变异没变红 = 没覆盖到」）：

| 变异 | 改动位置 | 期望变红 | 期望保持绿 | **实跑结果（2026-11-04）** |
|---|---|---|---|---|
| **V1** | `HotApiSupport.DEFAULT_PAGE_SIZE` 20 → 21 | HTTP `page_size_zero` | HTTP `default`（**缺省值写在 controller 里**，不是这个常量）· `page_size_over_max` · wire | ✅ `EXIT=1` · 红名单 = {`page_size_zero`} |
| **V2** | `InteractionStubService.LIKE_UNIMPLEMENTED_MSG` 加 `!` | wire `wire_like_article` | HTTP · wire `wire_comment_article` | ✅ `EXIT=1` · 红名单 = {`wire:wire_like_article`} |
| **V3** | `HotApiSupport.ROLLING_SCOPE` `"rolling"` → `"rolling_v2"` | HTTP `default`（`scope` 字段） | HTTP `page_not_a_number` · wire（message 里没有 scope） | ✅ `EXIT=1` · 红名单 = {`default`} |
| **V4** | **探针自身**：只给 B 侧多播一个成员（`PROBE_SEED_ASYMMETRIC=1`） | HTTP `default` · wire `wire_topk_20` · **Redis `hot:articles`** | wire `wire_like_article` | ✅ `EXIT=1` · 红名单 = {`redis:hot:articles`, `default`, `wire:wire_topk_20`} |
| **V5** | `HotRedisSnapshotStore.HOT_ARTICLES_KEY` 改键名 | **数据面闸门 die（exit 2）** —— **不许**报「全绿」 | —— | ✅ `EXIT=2` ＋ 输出含「数据面没跑起来」 |
| **基线复跑** | 全部还原 | —— | —— | ✅ `EXIT=0` ＋ **红名单为空** |

**实跑输出（逐字 · 2026-11-04 · 五条全部按预期表现）**：

```
== V1 · HotApiSupport.DEFAULT_PAGE_SIZE 20 → 21 ==
   ✅ 退出码 = 1（V1 生效 ⇒ 探针必须报红）
   ✅ page_size_zero ⇒ 红（page_size<=0 归一到默认值 ⇒ 跟着变）
   ✅ default ⇒ 绿（缺省值写在 controller 里（字面量 20）⇒ 不受影响）
   ✅ page_size_over_max ⇒ 绿（显式给的 page_size 走 MAX_PAGE_SIZE 上限 ⇒ 不受影响）
   ✅ wire:wire_like_article ⇒ 绿（wire 面与分页无关）
== V2 · InteractionStubService.LIKE_UNIMPLEMENTED_MSG 加 '!' ==
   ✅ 退出码 = 1（V2 生效 ⇒ 探针必须报红）
   ✅ wire:wire_like_article ⇒ 红（status description 的字节变了）
   ✅ default ⇒ 绿（HTTP 面不调 likeArticle ⇒ 不受影响）
   ✅ wire:wire_comment_article ⇒ 绿（只改了 like 的文案 ⇒ comment 不受影响）
== V3 · HotApiSupport.ROLLING_SCOPE "rolling" → "rolling_v2" ==
   ✅ 退出码 = 1（V3 生效 ⇒ 探针必须报红）
   ✅ default ⇒ 红（响应里的 scope 字段变了）
   ✅ page_not_a_number ⇒ 绿（5001 信封里没有 scope ⇒ 不受影响）
   ✅ wire:wire_topk_20 ⇒ 绿（wire 的 message 里没有 scope ⇒ 不受影响）
== V4 · 探针钩子 PROBE_SEED_ASYMMETRIC=1（只给 B 侧多播一个成员）==
   ✅ 退出码 = 1（V4 生效 ⇒ 探针必须报红）
   ✅ redis:hot:articles ⇒ 红（两侧种子不同 ⇒ Redis 状态面必须不等）
   ✅ default ⇒ 红（两侧 items 不同 ⇒ HTTP 面必须不等）
   ✅ wire:wire_topk_20 ⇒ 红（wire 面读的就是 hot:articles ⇒ 也必须不等）
   ✅ wire:wire_like_article ⇒ 绿（wire 的互动桩不看种子 ⇒ 不受影响）
== V5 · HotRedisSnapshotStore.HOT_ARTICLES_KEY 改键名（数据面闸门必须拦下）==
   ✅ 退出码 = 2（V5 生效 ⇒ 数据面闸门必须 die（exit 2），**不许**报「全绿」）
   ✅ 数据面闸门确实拦下了（输出含「数据面没跑起来」）
== 基线复跑（全部还原 ⇒ 必须转绿）==
   ✅ 退出码 = 0（还原后必须全绿）
   ✅ 还原后红名单为空
```

⚠️ **V4 是「M3 判据不是摆设」的证据**：两侧种子相同时，若探针读错了容器 / 读错了 key，读数也会**相等** ⇒ 判据空转。
⚠️ **V5 是「探针不会在数据面没跑起来时给结论」的证据**（正是 §九·9.5.1 那条洞的**回归守卫**）。
⚠️ **V1 是「读代码 ≠ 读行为」的例子**：`DEFAULT_PAGE_SIZE` 在 HTTP 路径**几乎不可达**（controller 的缺省值是**字面量 20**）⇒ 只有 `?page_size=0` 那条能触发它 ⇒ ⇒ **「这个常量被谁用」不能靠读代码下结论**。
⚠️ **「红」的集合要能区分「哪个机制在支撑哪条断言」** —— 上面每条变异的**红 + 绿**两侧都断言了 ⇒ ⇒ 否则「只红了一大片」无法归因（AGENTS.md §3.3.3 强制三条的 ①「每个事实带文件:行号」与 ②「两侧同时跑的探针」）。

### 9.7 覆盖边界（**本探针不覆盖什么** —— 不许拿「全绿」当「全对」）

| 不覆盖 | 为什么 |
|---|---|
| **Go 真源** | 本机无 Sea-etcd 容器 ⇒ Go 侧无法活体 ⇒ **不读作「三方一致」** |
| **出向 2s 上界的时序形态** | B 侧是 **api → rpc 两跳**（客户端预算先到）；C 侧是 **D2 域内直连** ⇒ 故障注入下的**耗时与失败形态**天然不同 ⇒ 本探针**不做故障注入**（只测正常路径的字节） |
| **多 key 的 HeavyKeeper 分数** | HeavyKeeper 是**有损概率 sketch**：桶冲突时按 `decay^count` **概率**让位（两侧**都是**随机 —— B 侧 `HeavyKeeper.java:138` 用 `ThreadLocalRandom`，其 javadoc:31 明写「随机分支不可断言精确值」）⇒ 多 key 场景下两侧分数**天生不可复现**<br>⇒ M4 用**单 key** ⇒ 每个分区里该 key 的 5 行计数都只走「指纹匹配累加」分支 ⇒ 分数 = 事件总条数（**确定性**）⇒ 字节可比 |
| **Kafka 的分区分布** | 生产端的分区器决定事件落到哪个分区 ⇒ 两侧的**分片分数**必然不同 ⇒ M4 只比**合并后**的 `hot:articles`（= 各分片之和 ⇒ 与分布无关） |
| **响应头全集** | `Date` / `Server` 等易变头 ⇒ 只比状态行 + `Content-Type` + body |
| **压测 / 并发** | 单请求串行 |
| **M5 不覆盖：失败**「**业务语义**」 | M5 只比**形状 + 时延** ⇒ 它**不证明**「失败原因相同」⇒ 两侧的失败根因（Redis 超时 vs gRPC deadline）**结构上就不同**（B 侧根本没有 Redis 卡住这回事 —— 它卡在 **hot-rpc** 的 gRPC 上）⇒ ⇒ 这是 F-02 的**定义**，不是漏测 |
| **M5 不覆盖：竞态窗口的**分布** | C 侧三形状是**毫秒级抖动**决定的 ⇒ 本轮四轮跑出**三种**形状各至少一次 ⇒ ⇒ **无法给出概率**（要估概率需上百轮）⇒ ⇒ **形状按集合冻结**，不做频率断言 |
| **M5 不覆盖：F-15 的**波及范围** | 只在 hot 域复现 ⇒ 「是否只有 hot 域会命中」**未证伪** ⇒ 归**收口域** |

---

### 9.8 阶段 M5 · 超时面（Redis 卡住 ⇒ 失败形状与时延）· **2026-11-04 新增**

> 📌 **这一面测的是「已登记的差异」（F-02）**，**不是**「两侧应当一致」
> ⇒ ⇒ 判据 = 「**差异的形状必须与登记一致**」⇒ ⇒ **两侧各自的状态行 + body 字节 + 时延区间**全部冻结
> ⇒ ⇒ 「差异还在但形状变了」⇒ 判失败；「差异消失了」⇒ 也判失败。

#### 9.8.1 注入手段：为什么是 `CLIENT PAUSE`

| 项 | 内容 |
|---|---|
| 手段 | `docker exec <容器> redis-cli CLIENT PAUSE 6000 ALL`（**两侧各一次**）⇒ 服务端**收下命令、延迟应答** ⇒ 客户端的**命令超时**（两侧都是 3s）**真的被触发** |
| 为什么不用 kill 容器 / 断网 | 那是「**连不上**」⇒ 客户端**立刻**失败（连接被拒）⇒ 测到的是**连接失败面**，**不是超时面** ⇒ ⇒ **测不到 F-02 说的那个场景**（且症状像「服务挂了」） |
| 为什么两侧各一次 | 两侧 Redis **物理隔离**（否则 Kafka 合并会互相覆盖 ⇒ 假漂移）⇒ 暂停 B 侧完全不影响 C 侧 |
| 窗口长度 | **6s** ⇒ 盖住两次请求（B ≈2s / C ≈3s）⇒ 且**两侧并发发起**（串行 ⇒ 第二个请求落在窗口外 ⇒ 场景不对称 ⇒ 假差） |

#### 9.8.2 读数（**2026-11-04 第四轮 · `EXIT=0` · 真差异 0 · 已知现象 1**）

| 侧 | 状态行 | Content-Type | body | 耗时 |
|---|---|---|---|---|
| **B** | `HTTP/1.1 200 ` | `application/json;charset=utf-8` | `{"code":5001,"msg":"internal server error","data":null}` | **2011ms**（2s gRPC 出向封顶） |
| **C** | `HTTP/1.1 503 ` | `text/plain;charset=utf-8` | `Request Timeout\n`（16 字节） | **3014ms**（3s HTTP 预算 / Redis 命令超时） |

⇒ ⇒ **时延关系与登记完全一致**（B 2s vs C 3s）⇒ ⇒ **失败形状不同**（B = 业务信封 200；C = 503 纯文本）⇒ ⇒ **F-02 的定性升级为实测事实**（§五·F-02）。

#### 9.8.3 🔴 探针抓到的第三种形状 ⇒ **混血响应** ⇒ **F-15**

三轮跑出**三种**形状（第四轮命中候选③）：

| 候选 | 形状 | 触发顺序 | 性质 |
|---|---|---|---|
| ① | `200` ＋ `application/json` ＋ **5001 信封** | 业务失败**先**到，worker 正常收尾并 flush | ✅ **干净** |
| ② | `200` ＋ `application/json` ＋ body **`Request Timeout\n`** | worker 的异常路径 `flushBuffer()` **先 commit 了「200 + json + 空 body」**，超时分支**再往已提交的响应里追加**纯文本 | 🔴 **混血** ⇒ **信封契约被打断**（客户端按 JSON 解会失败） |
| ③ | `503` ＋ `text/plain` ＋ `Request Timeout\n` | 超时分支**先**落定 | ✅ **干净** |

⇒ ⇒ **机制**（`common/observability/`）：`ObservabilityResponseWriter#getOutputStream()` 把 body 写进**捕获缓冲**（`:178-213`）· `flushBuffer()` **只 commit 响应**、不带 body（`:264-275`）· 超时分支 `writeTimeoutResponseQuietly` **直写底层**（`:315-322` + `HttpMiddleware:438-448`）⇒ ⇒ 两条写路径**并发写同一个响应** ⇒ 谁先落定决定形状。
⇒ ⇒ **归属**：**共享层**（`common/observability/`）⇒ **修法属收口域（`global`）** ⇒ ⚠️ **本域只登记、不改**（AGENTS.md §五.3 规则 3）⇒ ⇒ 台账：**F-15**。
⇒ ⇒ **为什么 B 侧永远命中不到**：B 侧业务失败落在 **2s**（gRPC 出向封顶）⇒ **远早于** 3s HTTP 预算 ⇒ ⇒ **F-02（D2 直连把失败时延推到预算线上）才是打开这个窗口的原因**。

#### 9.8.4 本面的判据设计（**三条，缺一不可**）

| 判据 | 内容 | 为什么 |
|---|---|---|
| **场景有效性** | 两侧耗时**必须 ≥ 1.5s** ⇒ 否则 **exit 2 · 不给结论** | 「我测的是这个场景吗」与「行为对不对」**分开判**（[`P-FOLLOW-01`](../Pitfalls/follow/_follow-domain-pitfalls.md)）⇒ 本轮它**真的拦下过两次坏读数**（§9.5.3） |
| **形状冻结** | B = `200` ＋ 5001 信封；C = **三候选集合**（①/②/③）⇒ 不在集合里 ⇒ 判失败 | 「已登记的差异」**不是豁免**，是**更严的断言**（[`P-HOT-08`](../Pitfalls/hot/_hot-domain-pitfalls.md)） |
| **恢复校验** | 暂停窗口过后两侧**必须恢复**且**逐字节一致** | 证明「卡住」**没把服务打死**、也**没改坏数据** ⇒ 否则 M5 之后的所有读数都不可信 |

---

> 📌 **一句话总览**：**B 侧 hot 域的 34 个主类已逐条落到 C 侧 31 个实现类**（零 FAIL），
> **77 例真触发测试全绿**（真 Tomcat / 真 gRPC / 真 Kafka / 真 Redis；2026-11-04 新增 **7 例** gRPC 错误映射契约测试，含「零调用点」静态守卫），
> **双侧活体对拍 31 条逐字节断言 ＋ 1 个超时面全绿**（B 侧 Boot 4 ↔ C 侧 Boot 3.5 · **逐字节** · 见 §九），
> **三道守门 EXIT=0**，**零重试判据经变异验证确认有牙齿**；
> ⚠️ 残留 **15 条登记项**（**0 条业务正确性 FAIL**）：**F-06** 是本轮真修的启动期缺陷；
> **F-02** 是 D2 的已批准连带后果（**INTENTIONALLY_CHANGED**）；**F-01/F-03** 是部署面/表达面的签字偏离；
> **F-04/F-07/F-08/F-10/F-11/F-12/F-14** 行为面已判定（框架限制或单向增强，见 §四/§五）；
> ⚠️ **F-09 已按 §九·9.3 更正**：原判「Tomcat 10.1 状态行与 Go `net/http` 不同 ⇒ 框架限制」——
> **B 侧不是 Go，而是 Tomcat 11.0.24** ⇒ **两侧状态行逐字节相同** ⇒ **原判定作废，改为 PASS**；
> ⚠️ **2026-11-04 本轮把 NOT_VERIFIED 清零**：**F-05 / C-04**（零调用点）⇒ **静态 ＋ 行为 ＋ 可执行守卫**三证；
> **F-02 的「域内查询卡住」** ⇒ `CLIENT PAUSE` 注入慢 Redis ⇒ **双侧活体实测**（§九·M5）⇒ ⇒ **并且实测推翻了 F-02 的原定性**：
> 失败**形状**也不同（B `200`＋5001 信封 @2s ⇄ C `503`＋纯文本 @3s），且 **C 侧形状非确定**（三候选）⇒ ⇒ 由此**新增 F-15**（共享层的
> 「HTTP 预算 × 业务失败」竞态 ⇒ **混血响应**）⇒ ⚠️ **归属收口域（`global`）⇒ 本域只登记、不改**（AGENTS.md §五.3 规则 3）。
> ⚠️ **F-13** 是**无职责可承接**的 **NOT_IMPLEMENTED**；**F-11** 是唯一仍未确认的**命名面**债。
> 📌 **本报告的 PASS 口径** = 「**与 B 侧逐字节一致（E-4）**」—— 但**不读作「三方一致」**（**无 Go 锚**，见 §九·9.1）。
> ⚠️ **E-4 探针自己也踩过三个判据空洞**（§九·9.5：数据面空洞 ＋ `top_k` 边界空洞 ＋ **1xx 解析错位**）⇒ ⇒ **「全绿」≠「验到了东西」**——
> 本轮已补**数据面闸门** ＋ **`top_k` 边界自检** ＋ **解析器自检**（`http_probe_selftest.py` · 挂在起服务之前），
> 并用**五条变异**守住（**V4 是纯探针自身的**（只给 B 侧多播一个成员）·
> **V5 验的是探针的闸门**（C 侧改键名 ⇒ 闸门必须 `die(exit 2)`，不许报「全绿」））⇒ ⇒ 五条**实跑全部按预期** ＋ **还原后基线复跑 `EXIT=0`、红名单为空**（§九·9.6）。
> 📌 **2026-11-05 追加 §十（M6 轮 · 守护网补齐与文档订正）**：**+18 条真触发用例**（95 例全绿 · skipped=0）⇒ **4 条变异**逐条转红（证明不是假绿）⇒
> **3 处 javadoc 措辞订正**（`HotApiService` / `HotApiSupport` / `HotPartitionSketchRegistry`）⇒ **只动测试与注释，不动实现** ⇒ 域级行为读数不变。
> 📌 **本域的两档台账已补齐**（2026-11-04）：[`docs/Intentional_Deviations/hot/_signed-deviations.md`](../../../Intentional_Deviations/hot/_signed-deviations.md)（**6 条**）·
> [`docs/Pitfalls/hot/_hot-domain-pitfalls.md`](../../../Pitfalls/hot/_hot-domain-pitfalls.md)（**11 条** —— 2026-11-05 追加 **P-HOT-10**「变异没红有两种可能」＋ **P-HOT-11**「会被截断切掉的脆弱断言」）⇒ ⚠️ 两个 `_INDEX.md` **不归本 lane**（Worktree.md §三.1）⇒ **待主会话合并**。

---

## 十、阶段 M6 · 守护网补齐与文档订正（2026-11-05 · 独立复审后的收口）

> **触发**：[`docs/DRIFT/hot/verification-hot-2026-11-05.md`](verification-hot-2026-11-05.md)（独立复审报告）发现
> **6 条「B 侧有、C 侧无」的守护网缺口** + **3 处 javadoc 措辞与代码事实不符** ⇒ ⇒ 本节记录本轮的补齐与订正。
> ⚠️ **本轮不动实现**（只补测试 + 订正注释）⇒ ⇒ 域级行为读数**不变**。

### 10.1 补齐的 18 条用例（**全部真触发**，不是 mock 单测）

| # | 补的判据 | 落在哪 | 类型 | 状态 |
|---|---|---|---|---|
| 1 | **撤销落盘发生在写锁之外**（flush 被卡住时读锁仍可用）| `messaging/HotPartitionSketchRegistryLockTest` | 纯单测（mock store + latch）| ✅ |
| 2 | assign→detach 不死锁 / detach 原子摘除 | 同上 | 纯单测 | ✅ |
| 3 | 持写锁取 sketch 引用不阻塞（write→read 降级）| 同上 | 纯单测 | ✅ |
| 4 | detach 后并发读者不被阻塞 | 同上 | 纯单测 | ✅ |
| 5 | assign 幂等（已存在分区不重建）| 同上 | 纯单测 | ✅ |
| 6 | detach 不存在的分区是空操作 | 同上 | 纯单测 | ✅ |
| 7 | sketch 按配置四参数创建 | 同上 | 纯单测 | ✅ |
| 8 | **分区未持有时指标照样计数**（先计数 → 后查分区的次序）| `messaging/HotEventConsumerContractTest` | 纯单测 | ✅ |
| 9 | 分区已持有且 type 合法 ⇒ 计数 + 入草图 | 同上 | 纯单测 | ✅ |
| 10 | **多字段载荷（`user_id`/`timestamp`）不丢**（`ignoreUnknown` 硬要求）| 同上 + `messaging/HotEventConsumerRealKafkaTest` | 纯单测 **＋ 真 Kafka** | ✅ |
| 11 | 三类坏消息返回非 null error（否则 span 被误标成功）| 同上 | 纯单测 | ✅ |
| 12 | 正常路径与「分区未持有」返回 null error | 同上 | 纯单测 | ✅ |
| 13 | **合并 >100 条 ⇒ 只留分数最高的 100 条**（边界分数断言）| `messaging/HotMergeSchedulerRealRedisTest` | 真 Redis | ✅ |
| 14 | **看门狗续期 Lua 的 CAS**（值匹配才续期、不匹配返 0 且不动锁）| 同上 | 真 Redis | ✅ |
| 15 | **分区分配策略真的落到了 consumer**（3 分区 2 消费者 ⇒ [0,2]/[1]；带 Range 对照组）| `messaging/HotEventConsumerRealKafkaTest` | 真 Kafka | ✅ |
| 16 | 多字段载荷在真 broker 上不被丢 | 同上 | 真 Kafka | ✅ |
| 17 | **form 值 2048 个放行 / 2049 个被拒**（真 Tomcat 端到端）| `http/HotHttpRealTomcatTest` | 真 Tomcat | ✅ |
| 18 | 2048 个放行时仍是正常业务信封（`code=200`）| 同上 | 真 Tomcat | ✅ |

> 📌 **为什么 #8 只能靠纯单测**：真 Kafka 上分区**总是**会被分配 ⇒ 「分区未持有」分支**不可达** ⇒ ⇒ 只能用纯单测守住这条次序。
> 📌 **为什么 #17/#18 用真 Tomcat + 裸 socket**：请求行约 20 KB ⇒ 必须确认它**没被 Tomcat 的请求头上限先拒**
> （本域已把 `server.max-http-request-header-size` 抬到 1028KB，对齐 B 侧 Go 的 `DefaultMaxHeaderBytes(1<<20)`）
> ⇒ ⇒ 该配置若被回退，本用例会变成「拿到 Tomcat 的拒绝」⇒ **正好起到守门作用**。

### 10.2 变异验证（**证明新断言不是假绿** · 4 条实跑）

| 变异 | 改了什么 | 期望 | 实跑读数 | 判定 |
|---|---|---|---|---|
| **V1** | 把 `HotEventConsumer` 的指标自增挪到 `if (hk == null) return null;` **之后** | 「分区未持有仍计数」转红 | **1 红**（`metricIsIncrementedEvenWhenPartitionIsNotAssigned`），其余 4 例绿 | ✅ 有牙齿 |
| **V2** | 把 `HotMergeScheduler` 的合并排序**改成升序** | 截断用例转红 | **红**：`留存的最低分` = **1.0**（应为 51.0）⇒ **正是「条数断言抓不到、边界断言才抓得到」** | ✅ 有牙齿 |
| **V3** | 把 `application.yml` 的 `RoundRobinAssignor` 改成 `RangeAssignor` | 分配策略用例转红 | **1 红**（实际 `[0,1]` 而非 `[0,2]`）⇒ **「配置里写了 ≠ 生效了」现在有牙齿** | ✅ 有牙齿 |
| **V4** | 把 `RENEW_SCRIPT` 的 CAS 去掉（无条件 `EXPIRE`）| 续期用例转红 | **红**：`值不匹配 ⇒ 返回 0` 实得 **1** | ✅ 有牙齿 |

> ✅ 4 条变异**全部按预期转红**，**全部已还原**（`git diff` 为空），**还原后基线复跑 95 例全绿**。
> ⚠️ **V1 的第一次尝试没红**（把计数挪到分区查找之后但仍在 `null` 检查之前 ⇒ **等价变换**）⇒ ⇒
> 「**没红**」有**两种**可能：**断言没牙齿** / **变异没打到点上** ⇒ ⇒ 判定前必须先自问「**这行改动的语义后果是什么**」。
> ⇒ 已登记为 [`P-HOT-10`](../../../Pitfalls/hot/_hot-domain-pitfalls.md)。

### 10.3 订正的 3 处 javadoc（**只改注释，不改行为**）

| # | 位置 | 原措辞的问题 | 订正后 |
|---|---|---|---|
| 1 | `service/HotApiService.java`（类注释「一处传输层差异」段）| 说「**只剩 HTTP 层的请求预算兜底**」⇒ **不准确**：HTTP 预算**不约束**那次 Redis 调用 | 改成按 §九·M5 的**双侧实测读数**写清：B = `200`＋5001 信封 @2011ms；C = `503`＋纯文本 @3014ms 且**形状非确定** ⇒ ⇒ 明确「**没有兜底，只有更晚 ＋ 形状不同**」|
| 2 | `service/HotApiSupport.java`（类注释「上限闸门」段）| 说共享层判据与 B 侧「**不完全相同**」⇒ **与 F-03 台账口径相左** | 改成「**判据口径（阈值 ＋ 文案）与 B 侧一致**；**覆盖面**收窄（query+body → query）且**对 hot 不可达**」+ 指向新增的真 Tomcat 守护用例 |
| 3 | `messaging/HotPartitionSketchRegistry.java`（类注释标题 ＋ `detachWithinWriteLock` 方法注释）| ①标题「**与 B 侧**的一处有意偏离」⇒ **把 Go 真源当成了 B 侧 Java**（B 侧 Java **已经是**「锁内取引用 → 锁外落盘」）；②方法注释说「把 **C 侧修掉的 B 侧死锁**请回来」⇒ **在 Java 里不构成死锁**（`ReentrantReadWriteLock` 支持 write→read 降级）| ① 标题改成「与 **Go 真源**的偏离」并**显式区分两个「B 侧」**；② 改成「**它不构成死锁**，真实代价是**持写锁做 Redis I/O ⇒ 阻塞所有读者**」+ 指向新增的并发守护用例 |

### 10.4 顺手加固的 1 处**既存**脆弱断言

`HotMergeSchedulerRealRedisTest#mergeReadsOnlyConfiguredPartitionCount` 原来用「成员在不在合并结果里」来断言「哪几片参与了合并」，
而合并结果会按分数降序**截断到 100 条** ⇒ ⇒ 若那两条的分数落在截断边缘，会被切掉 ⇒ ⇒ 用例会**假红**
（实测：V2 把排序改成升序时它随之误红 ⇒ **红的原因不是「参与片数不对」**）⇒ ⇒ 已把两条的分数从 `1.0` 提到 `100.0`（远离截断边缘），
并在用例注释里写明**为什么必须给高**。

### 10.5 本轮读数（**全部真跑**）

| 项 | 读数 |
|---|---|
| 域级测试 | **95 例全绿**（原 77 ＋ 本轮 18），**skipped = 0**（真 Tomcat / 真 gRPC / 真 Kafka / 真 Redis）|
| 变异验证 | **4 条全部按预期转红** ⇒ 还原后基线复跑 **95 例全绿** |
| 三道守门 | **EXIT = 0**（① 架构断言 38/38 Lombok 展开块 · ② docs↔树对账 · ③ 映射覆盖 C 642/642 · B 752/752）|
| 文件树 | 已用生成器重生成（`docs/File_Tree/hot.md` ＋2 个新测试类）|
| 写权面 | 只碰 `service/hot/`（测试 ＋ 注释）＋ `docs/DRIFT/hot/` ＋ `docs/File_Tree/hot.md` ＋ `docs/Pitfalls/hot/` ⇒ **未碰共享面**（`common/**` 只读）|

### 10.6 本轮**没有**动的（留给下一手）

| 项 | 归属 | 理由 |
|---|---|---|
| **OTLP 追踪导出整体缺失**（hot 域不装任何 exporter ⇒ span 一条都导不出去）| **`global`**（`comment`/`favorite`/`message`/`task` **同样零 otel 段** ⇒ 共 5 域）| 共享层写权面 · 建议统一处置，域内只写 yml |
| `health.show-details` 取严 / actuator 暴露集取并集 / gRPC 端口 env 名双名兼容 | hot 域（配置面取舍）| 本轮指令只含「补用例 ＋ 修注释」⇒ 未动 |
| 三个 `_INDEX.md` 并入主干 | **主会话** | 共享面（AGENTS.md §五.3 规则 3）|
| **F-15** 共享层「HTTP 预算 × 业务失败」竞态 ⇒ 混血响应 | **`global`** | 共享层缺陷 · hot 只登记 |
| **F-02** 是否恢复域内方向 2s 上界 | 用户拍板 | D2 既定决策的例外 |
| **F-11** 命名债 | 用户拍板 | —— |
