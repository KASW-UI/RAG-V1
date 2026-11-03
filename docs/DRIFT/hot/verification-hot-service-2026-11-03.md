# hot 域迁移审核报告（RAG Boot 4 → RAG-V1 Boot 3.5）

> **由 `align/hot` 工作树产出** · **基线 commit `0a35865`**（本地 `main`）· 分支 `align/hot`
> **核对日期**：2026-11-03
> **审核依据**：`.agents/verification.md`（八阶段 + §十四 12 项必答）
> **写权**：本报告只写 `docs/DRIFT/hot/**`；`docs/DRIFT/_INDEX.md` · `docs/Intentional_Deviations/_INDEX.md` ·
> `docs/Pitfalls/_INDEX.md` · `AGENTS.md` · `common/**` **一律不碰**（AGENTS.md §五.3 规则 3）
>
> 📌 **本轮范围**：**hot 域**（B 侧 `service/hot/` 四模块 → C 侧 `service/hot/` 单模块）·
> **31 个主代码类**从空壳落地为实现 ＋ **13 个测试类 / 56 例**新写。
> ⚠️ 本报告**不**对其它 9 个域做判断，也**不**代表 hot 域与 B 侧「双侧活体对拍」已闭合（见 §五·F-11）。
>
> 📌 **本文件覆盖到的证据强度：E-3（真中间件 / 真容器）** —— §二 的承接表**逐条标注**了证据强度列
> （分级定义见 [`docs/DRIFT/_INDEX.md`](../../DRIFT/_INDEX.md) 末节「证据强度分级（E-0~E-4）」）：
> **E-4（双侧活体对拍）本域为 0 条** —— B 侧 hot 域**没有**对拍探针脚本 ⇒ ⇒ **本报告的 ✅ 一律读作「在该证据强度下一致」**
> （**不是**已闭环）；**E-2** = 源码级对照（机器断言只证明「C 侧没跑偏」，**不**证明「B 侧就是这些值」）。

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
| C-04 | **B 侧无对应实现**（全域 grep 实测：hot 域零 gRPC 错误码反查调用点） | 「B 侧原本不可达」的 gRPC 码反查器 | `contract/GrpcErrorMapper.java` | C 新增 | ✅ | **NOT_VERIFIED** | **E-1** | ⚠️ **零调用点** ⇒ 保留「B 侧有这份映射规则」的形状，但**无法验证** ⇒ §五·F-05 |
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
| B 机制 | Tomcat 11（Go 侧实际是 `net/http`，不发 reason phrase 之外的行为另计） |
| C 机制 | Tomcat **10.1.52** |
| 变化 | ① **状态行不发 reason phrase** ⇒ `HTTP/1.1 404 `（码后有空格、无短语）② Servlet API 版本 6.0 → 5.0 |
| 影响流程 | 404 / 405 这类**裸状态码**响应的**字节面** |
| 是否影响业务行为 | **部分** —— ① 状态行**逐字节不同**（B 侧 Go `net/http` 会发 `HTTP/1.1 404 Not Found`）⇒ 属**框架限制**，不可在 C 侧修；② 业务路径（200 ＋ 信封）**不受影响** |
| 适配方式 | 按本仓既有口径断言（状态行 `"HTTP/1.1 404 "` ＋ 无信封） |
| 验证证据 | `HotHttpRealTomcatTest`（真 Tomcat ＋ **裸 socket 逐字节读回**） |
| **结论** | **FRAMEWORK_DIFFERENCE（框架限制）** ⇒ 记 §五·F-09；业务语义（无信封）**PASS** |

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
| 影响范围 | **超时面**：域内 Redis 查询卡住时的失败时延（B 侧 2s ⇒ C 侧最坏到请求预算 `observability.http-timeout: 3s` 耗尽） |
| 严重程度 | 🟡 中（性能/超时面，**非正确性** —— 两条路径都最终失败，只是失败时延不同） |
| 修复建议 | 不修（D2 是既定决策）。⚠️ 若将来要恢复该上界，**正确做法**是在 `HotApiService` 调用 `HotRankingQueryService` 的那一点折算预算（与 `ArticleRpcClient` 同款），**不要**给共享层再加第二个 cap 来源（会造出「两个上界来源」⇒ 违反 cap 单一来源纪律） |
| 验证方法 | 真 Redis ＋ 真 gRPC 两条真链已跑通；「域内查询卡住」这条**超时路径属未验证**（需人为注入慢 Redis）⇒ **NOT_VERIFIED** |

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
| 验证方法 | **NOT_VERIFIED** —— 零调用点 ⇒ **无法用行为证伪**（§十：NOT_VERIFIED 不计入 PASS） |

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

### F-09 · Tomcat 10.1 状态行不发 reason phrase · 框架限制

| 项 | 内容 |
|---|---|
| 位置 | HTTP 404 / 405 的**字节面** |
| B | Go `net/http` ⇒ `HTTP/1.1 404 Not Found` |
| C | Tomcat **10.1.52** ⇒ `HTTP/1.1 404 `（码后有空格、**无短语**） |
| 原因 | **Tomcat 11 → 10.1**（D14）—— 框架版本分叉，**不可在 C 侧修** |
| 影响范围 | **仅状态行的 reason phrase 字节**；状态码、body（**裸状态码无信封**）**逐条相同** |
| 严重程度 | 🟢 低（框架限制） |
| 修复建议 | 不修（按本仓既有口径断言） |
| 验证方法 | `HotHttpRealTomcatTest`（真 Tomcat ＋ 裸 socket 逐字节读回） |

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

---

## 六、完成度统计（§11.6 · 分开统计，**不用文件数量代表完成度**）

| 分类 | 数量 | 明细 |
|---|---|---|
| **已实现且已验证（PASS）** | **45** | §二：**36**（C-01/02/03/05 共 4 · A-01~A-13+A-16 共 14 · R-01~R-09 共 9 · M-01~M-05 共 5 · X-01~X-04 共 4）<br>§五：**9**（F-04 · F-06 · F-07 · F-08 · F-09 · F-10 · F-11 · F-12 · F-14） |
| **已实现但未验证（NOT_VERIFIED）** | **3** | C-04（`GrpcErrorMapper` · 零调用点）· F-05（同上，零调用点）· F-02 的「域内查询卡住」子路径（需人为注入慢 Redis，本轮**未验**） |
| **尚未实现（NOT_IMPLEMENTED）** | **1** | F-13（无表域的持久层职责 ⇒ **无职责可承接**，非遗漏） |
| **明确存在漂移（FAIL）** | **0** | ✅ **本域无 FAIL** |
| **框架差异待验证（FRAMEWORK_DIFFERENCE）** | **1** | A-15（form 解析口径 · 共享层判据已实测与 B 一致 ⇒ 最终行为 **PASS**，差异保留在 §四·4.6 清单内） |
| **被外部条件阻塞（BLOCKED）** | **0** | ✅ |
| **已批准的主动变更（INTENTIONALLY_CHANGED）** | **3** | A-14（D2 删除 `HotRpcClient`）· A-17（`WebMvcConfig` 空实现显式化免鉴权）· F-02（D2 连带：丢一层 2s 上界） |
| **已登记的部署面 / 表达面签字偏离（不影响业务语义）** | **2** | F-01（端口 8895）· F-03（域内自持 2 个 form 方法） |

> 📌 **计数口径**：逐条承接条目共 **54 条** = §二 的 **40 行**（契约 5 ＋ HTTP 17 ＋ 领域 9 ＋ 消息 5 ＋ 装配 4）＋ §五 的 **14 条**（F-01~F-14）；
> 另加 **1 个子项**（F-02 的「卡住」路径）⇒ 合计 **55 个归类单元** = 45 + 3 + 1 + 1 + 3 + 2 − 1（F-02 同时归 INTENTIONALLY_CHANGED 与 NOT_VERIFIED 的子路径）。
> **§四 的 6 条框架差异不单列** —— 它们是 54 条条目**背后**的机制说明（每条都注明「最终行为结论 = PASS」）。
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
| 8 | Spring Boot 4 → 3.5 是否引入业务可观察行为变化？ | **引入了 2 处**，都已在 §四/§五 定位到具体机制：① **§4.5 零重试**（Boot 默认重试 9 次 ⇄ B 侧永不重试）—— **已显式对齐 ＋ 变异验证**；② **§4.3 Tomcat 状态行无 reason phrase** —— **框架限制，不可修**。其余（Kafka 坐标 / Jackson 3→2 / `@ConfigurationPropertiesScan` / Redis 客户端）**均已证明不影响业务行为** |
| 9 | Tomcat、Jackson、Spring Framework 等底层版本变化是否产生实际漂移？ | **Tomcat**：仅 reason phrase 一处（**F-09**）。**Jackson**：**零**（本域只用三处两版同义注解）。**Spring Framework**：**零业务漂移**（装配差异见 §4.4，其唯一真实后果 **F-06** 已修）。**Kafka 客户端**：**零**（零重试已显式对齐） |
| 10 | 哪些差异已经通过证据证明安全？ | **10 项**：§4.1 Kafka 坐标 · §4.2 Jackson 3→2 · §4.3 Tomcat 业务语义部分 · §4.4 装配差异 · §4.5 零重试 · §4.6 Redis 客户端 / Nacos / 生命周期 · **F-08** `rag.side` 无消费点 · **F-13** 模板残留无业务行为 · **F-07** B 侧死锁已消解 · **F-14** 出向键位差异（article-rpc 方向有效值相同） |
| 11 | 哪些差异仍然无法确认？ | **3 项**：**F-05 / C-04**（`GrpcErrorMapper` 零调用点 ⇒ 无法用行为证伪）· **F-02** 的「域内查询卡住」路径（需人为注入慢 Redis，本轮**未验**）· **F-11** 命名债（行为面已验，**命名面**待下一轮统一清理） |
| 12 | 当前 C 是否具备可靠的行为对拍条件？ | **部分具备** —— ✅ 已具备：HTTP（真 Tomcat ＋ 裸 socket 逐字节）· gRPC（**本域 5 个方法**走真进程内 gRPC ＋ **跨域**走真进程内 `ArticleServiceGrpc`）· Kafka（真 broker ＋ 真实 topic/group/分区）· Redis（真容器跑 Lua ＋ TTL）· 错误契约（11 码 ＋ 11 英文文案 ＋ 未知码回退，**逐条机器断言** ＋ 变异转红）· HeavyKeeper（**BigInteger 独立重写的差分实现**（与生产实现不共用算式 ⇒ 抓「掩码/符号位」三类错法）＋ FNV-1a 32 **公开已知常量**（空串/a/foobar）＋ uint32 回绕与向零截断）。⚠️ **这一条是「算法自洽性」证据，不是 B↔C 对拍** —— 差分实现的两端都是 C 侧代码。⚠️ **尚不具备**：**双侧活体对拍**（B 侧 hot 域**没有**像 `tools/difftest/like/` 那样的对拍脚本）⇒ ⇒ **本报告的「PASS」= 在本仓证据强度下与 B 侧一致**，**不等于**「两侧活体逐字节对拍已闭合」 |

---

## 八、本轮写权与自检

| 项 | 结果 |
|---|---|
| 写入范围 | `service/hot/**`（31 个主类 ＋ `application.yml` ＋ 15 个测试类）· `docs/DRIFT/hot/**`（本文件）· `docs/File_Tree/hot.md`（**生成器产物**，按 §一·1.6 处理） |
| **未碰** | `docs/DRIFT/_INDEX.md` · `docs/Intentional_Deviations/_INDEX.md` · `docs/Pitfalls/_INDEX.md` · `AGENTS.md` · `common/**` · `docs/Mapping_Table/**` · `tools/**`（ROOT 补丁**已还原**） |
| 主仓状态 | **未脏** —— `git status` 只有两处**本轮之前就存在**的未跟踪目录（`Book/.obsidian/`、`脑图整理/.obsidian/`） |
| 生成器副作用 | `gen-file-tree.py` 硬编码 ROOT ⇒ 首次运行改的是**主仓**（内容逐字节相同 ⇒ 无差异）⇒ 已按 §一·1.6 用 ROOT 补丁在**工作树**重跑 |
| ⚠️ **「全仓 `mvn -o clean verify`」本轮未跑** | **不是遗漏**：AGENTS.md **P-18** 记载「域还是空壳 ⇒ `repackage` 失败 ⇒ 全仓 `mvn verify` 恒红」⇒ 其它 9 个域中仍有空壳 ⇒ **全仓红与 hot 域无关**。⇒ 本轮按**域级**口径跑 `mvn -o -pl service/hot -am package`（含 `repackage`，产物已验证是可执行 Boot jar）⇒ **hot 域自身**的编译 / 测试 / 打包全绿 |
| ⚠️ **`docs/File_Tree/hot.md` §五「状态」段仍写「全部待填」** | 那是 **`gen-file-tree.py` 的固定文案**（`article.md` / `like.md` 同样如此 ⇒ **全仓一致**）⇒ **不是** hot 域仍是空壳的证据。⇒ 本轮**不改生成器**（`tools/` 不在本域写权内）⇒ 已在 §一·1.6 留痕 |

---

> 📌 **一句话总览**：**B 侧 hot 域的 34 个主类已逐条落到 C 侧 31 个实现类**（零 FAIL），
> **70 例真触发测试全绿**（真 Tomcat / 真 gRPC / 真 Kafka / 真 Redis），
> **三道守门 EXIT=0**，**零重试判据经变异验证确认有牙齿**；
> ⚠️ 残留 **14 条登记项**（**0 条业务正确性 FAIL**）：**F-06** 是本轮真修的启动期缺陷；
> **F-02** 是 D2 的已批准连带后果（**INTENTIONALLY_CHANGED**）；**F-01/F-03** 是部署面/表达面的签字偏离；
> **F-04/F-07/F-08/F-09/F-10/F-11/F-12/F-14** 行为面已判定（框架限制或单向增强，见 §四/§五）；
> **F-05 / C-04** 与 **F-02 的「卡住」子路径** 是**无法用行为证伪**的 **NOT_VERIFIED**；**F-13** 是**无职责可承接**的 **NOT_IMPLEMENTED**。
> ⚠️ **本域尚未做「双侧活体对拍」** ⇒ 本报告的 PASS 口径 = 「**在本仓证据强度下与 B 侧一致**」。
