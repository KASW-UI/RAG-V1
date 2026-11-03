# like 域迁移审核报告（RAG Boot 4 → RAG-V1 Boot 3.5）

> **由 `align/like` 工作树产出** · **基线 commit `116ce63`**（本地 `main`）· 分支 `align/like`
> **核对日期**：2026-11-03
> **审核依据**：`.agents/verification.md`（八阶段 + 12 项必答）
> **配套文件**：[`vs-common.md`](vs-common.md)（本域 ↔ 共享层 `common/` 跨层对账）
> **写权**：本报告只写 `docs/DRIFT/like/**`；`docs/DRIFT/_INDEX.md` · `docs/Intentional_Deviations/_INDEX.md` ·
> `docs/Pitfalls/_INDEX.md` · `AGENTS.md` · `common/**` **一律不碰**（AGENTS.md §五.3 规则 3）

---

## 一、审核范围

### 1.1 两个项目的角色

| 标识 | 项目 | 路径 | 作用 |
|---|---|---|---|
| **B** | RAG | `/Users/pr/Documents/java&golang/RAG`（只读） | **唯一行为基准**（D16）· Spring Boot 4 形态 |
| **C** | RAG-V1 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/like` | 被审对象 · Spring Boot 3.5 形态 |

### 1.2 审核的模块与文件规模（**实测**，非旧报告数字）

| 项 | B 侧 | C 侧 | 备注 |
|---|---|---|---|
| 模块数 | **4**（`like-common` / `like-api` / `like-rpc` / `like-proto`） | **1**（`service/like`）+ 根级 `proto/` | **D1** api+rpc 合并 + **D7** proto 集中化 |
| 主代码 `.java` | **83**（4 + 37 + 42） | **69** | 映射表 C 列 **69/69 = 100%** |
| 测试 `.java` | **48**（2 + 14 + 32） | **14**（9 个测试类 + 5 个 support） | 测试资产**另算一档**（§1.0c） |
| 迁移脚本 | **6**（`V1..V6`） | **6** | 逐字节复制 + C 侧脚注 |
| 配置文件 | `like-api/application.yml`（197 行）+ `like-rpc/application.yml`（476 行） | `service/like/src/main/resources/application.yml` | 两份合一 |

### 1.3 版本口径（**实际解析值**，非 POM 声明值）

| 组件 | B 侧 | C 侧 | 判定 |
|---|---|---|---|
| Spring Boot | **4.0.8** | **3.5.11** | ✅ D14 既定决策 |
| Spring Framework | 7.x | **6.2.16** | ✅ 由 Boot 3.5.11 BOM 锁定 |
| Tomcat | 11.x | **10.1.52** | ✅ like 触及的 3 个容器旋钮 10.1 全支持 |
| Jackson | **3.1.5**（`tools.jackson.*`） | **2.19.4**（`com.fasterxml.jackson.*`） | ✅ D14；**代码层 0 处 `tools.jackson` 引用** |
| Spring Cloud | 2025.1.0 | **2025.0.3** | ✅ |
| Spring Cloud Alibaba | 2025.1.0.0 | **2025.0.0.0**（nacos-client 3.0.3） | ✅ |
| Flyway | 10.22.0（+ `spring-boot-flyway` 模块） | **11.14.1**（`flyway-core` + `flyway-database-postgresql`） | ⚠️ 显式覆盖 BOM 的 11.7.2 ⇒ §四·B1 |
| MyBatis-Plus | 3.5.17 | **3.5.17** | ✅ 同版本 |
| gRPC（net.devh / grpc-java） | 3.1.0 / 1.63.0 | **3.1.0 / 1.63.0** | ✅ 同版本 |
| jjwt | 0.12.6 | **0.12.6** | ✅ 同版本 |

> ✅ **零 Boot 4 专有 artifact**（`spring-boot-kafka` / `spring-boot-flyway` / `spring-boot-data-redis` / `spring-boot-tomcat`）：
> 本地 `~/.m2` 实证这 5 个 artifact **只有 4.x** ⇒ 在 3.5 线**客观不存在** ⇒ C 侧不引是**必需**而非可选。
> ✅ **零 Jackson 2 exclusion**（nacos / minio / flyway 均原生依赖 Jackson 2）。

### 1.4 使用的资料与代码版本

| 项 | 值 |
|---|---|
| Mapping_Table | [`docs/Mapping_Table/like/like.md`](../../Mapping_Table/like/like.md)（451 行 · 81 行映射） |
| File_Tree | [`docs/File_Tree/like/like.md`](../../File_Tree/like.md)（194 行 · 本轮重跑生成器刷新） |
| 决策记录 | [`docs/决策记录.md`](../../决策记录.md)（D1 / D2 / D3 / D7 / D13 / D14 / D16 / D17 / D18） |
| 基础 commit | `116ce63`（本地 `main`）· 工作树内改动**未提交**（`git status`：72 改 ＋ 14 新增） |
| 架构断言 | [`docs/架构符合性断言.md`](../../架构符合性断言.md)（A1–A13） |

### 1.5 本轮的验证读数（**全部真跑**）

| 读数 | 值 | 命令 |
|---|---|---|
| like 域回归 | **110 例 · Failures 0 · Errors 0 · Skipped 0**（⚠️ **两次独立全量回归逐字一致**：`110` ⇒ 跨域那 4 例**两次都被执行** ⇒ **DRIFT-LIKE-019 已闭合**） | `mvn -o -pl service/like -am test` |
| common 回归 | **239 例 · Failures 0 · Errors 0 · Skipped 0** | 同上（`-am` 带上游） |
| **全仓合计** | **349 例 · 0/0/0 · Skipped 0 · BUILD SUCCESS**（110 ＋ common 239） | 取回归日志的**模块级汇总**（AGENTS §五.8 规则 ①） |
| 守门 ① | `check-mapping-coverage.py` ⇒ **EXIT=0**（**C 列 637/637 · B 列 752/752 · B 列全局未覆盖 0**）<br>like 行 **69/69 · 83/83 · 100% ✅**<br>📌 **上一轮记的「users 域 B 列误填 ⇒ EXIT=1」已被基线前进消解**（`main` 的 `85751b6` 修了那处）⇒ §七·7-12 | — |
| 守门 ② | `check-arch-invariants.py` ⇒ **EXIT=0**（PASS 10 · FAIL 0 · N/A 2 · PENDING 1） | PENDING 属 comment/favorite 空壳 |
| 守门 ③ | `check-docs-vs-tree.py` ⇒ **EXIT=0** | — |
| **双侧活体对拍** | **`tools/difftest/like/run.sh` ⇒ EXIT=0**（**两轮独立复跑读数一致**）<br>机器摘要（逐字）：`—— 探针摘要：❌ 真差异 0 ｜ ⚠️ 已知现象 1 ——` ＋ `✅ 三方逐字节一致（C 侧 == B 侧 == 冻结的 Go 字节）` | 8 条 HTTP **三方逐字节** ＋ 6 条 gRPC wire **双边逐字节** ＋ 四类场景轮（C/D/E/F）＋ 收尾**端口零残留** |
| **变异验证（对拍侧）** | **`tools/difftest/like/mutate-parity.sh` ⇒ EXIT=0** | M1~M6 **全部按预期转红**（每条都要求「目标用例红 **且** 对照用例仍绿」）＋ 还原后回绿 |
| **Kafka 三面真链路** | **`LikeKafkaThreeFaceRealTest` ⇒ 4 例全绿**（逐字：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.rag.like.messaging.LikeKafkaThreeFaceRealTest`）<br>📌 **多次独立真跑读数一致**（`4 / 0 failures / 0 errors / 0 skipped` 逐字相同；耗时随 broker 积压与 PG 冷热变化） | 真 broker `127.0.0.1:49992`（Sea-kafka **EXTERNAL** 发布口）＋ 真 `@KafkaListener` ＋ 真 `@Scheduled` 中继 ＋ 真 PG（Testcontainers）⇒ §四·4.I |
| **Kafka 三面变异验证** | **`tools/difftest/like/mutate-kafka-3face.sh` ⇒ EXIT=0**（**首轮**）<br>⚠️ **第二轮**：M2 的「幂等面仍绿」判据**偶发不成立**（1/2 轮）⇒ ⇒ 查为 **flaky**（有界轮询预算 30s 在多轮连跑时偏紧）⇒ ⇒ 预算提到 **60s** ＋ **连跑三轮 M2 读数逐字一致**（每次 `Failures: 1` ＝ 只推送面红 ⇒ 幂等面稳定绿）⇒ ⇒ ⚠️ **完整脚本未再整体复跑一次**（M1/M3 的判据在第二轮**仍按预期**）⇒ §八·8.6 | `bash tools/difftest/like/mutate-kafka-3face.sh` |

> 📌 **本轮回归数字的归因**（避免被误读成「like 域自己涨了 5 例」）：
> · **like 102 → 110** ＝ 本轮新增的 `LikeKafkaThreeFaceRealTest` **4 例** ＋ `LikeMessageRpcCrossDomainRealTest` **4 例**（**两次独立全量回归逐字一致**）；
>   📌 `LikeMessageRpcCrossDomainRealTest` 曾一度在一次全量回归里被 surefire **静默跳过**（like=106、0 ERROR、0 Skipped）⇒ ⇒ **DRIFT-LIKE-019**（静态块副作用 ⇒ 扫描期出问题 ⇒ 类被静默丢弃）⇒ ⇒ **已修**（挪到 `@BeforeAll`）⇒ ⇒ **两次全量回归均 110 例** ⇒ 该条
> · **common 234 → 239**（+5）＝ **基线前进**（`align/like` 已快进到 `main` 的 `85751b6`，含 common/follow/users 三个域的收口提交）⇒ **不是 like 域的改动**；
> · ⇒ 两处数字都取自**同一条命令的模块级汇总行**（`Tests run: 106, Failures: 0, Errors: 0, Skipped: 0` / `Tests run: 239, …`），不取 `surefire-reports/*.xml` 求和（AGENTS §五.8 规则 ①）。

---

## 二、B → C 责任承接

### 2.1 契约层（7 个 C 类 ← B 侧 4 个主类 ＋ 1 个 proto）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现状态 | 验证状态 | 证据 |
|---|---|---|---|---|---|---|---|
| C-01 | `like-common/.../errmsg/LikeErrorCode.java` | 29 个业务码常量（**枚举顺序即契约**） | `contract/ErrCodes.java` | 1:1 | ✅ | **PASS** | 机器比对 29/29 码值一致；`LikeErrorContractTest` |
| C-02 | `like-common/.../errmsg/LikeErrorMessages.java` | 29 条 code→msg 文案 ＋ 未知码回落 `"FAIL"` | `contract/ErrMsg.java` | 1:1 | ✅ | **PASS** | 机器比对 29/29 文案**逐字节**一致；未知码回落用例 |
| C-03 | `like-common/.../errmsg/CodeError.java` | 业务异常载体（`Code`/`Msg`） | `contract/CodeError.java` | 1:1 | ✅ | **PASS** | `LikeErrorContractTest`（新旧构造 + 往返） |
| C-04 | `like-common/.../errmsg/LikeGrpcError.java` | 业务码塞进 gRPC status code 字段 | `contract/GrpcErrFactory.java` | 1:1 | ✅ | **PASS** | trailer 往返用例 ＋ `LikeGrpcServiceContractTest` |
| C-05 | `like-rpc/.../mq/LikeEventContracts.java`（内嵌 3 个 record） | 3 个 Kafka payload 字段名与类型 | `contract/KafkaLikeMsg.java` | 1:N | ✅ | **PASS** | 15 个 payload 字段逐条比对 |
| C-06 | 同上（内嵌） | `ArticleHotEvent`（**`user_id` 是 string**） | `contract/ArticleHotEvent.java` | 1:N | ✅ | **PASS** | 字段类型逐条比对（string ≠ int64） |
| C-07 | 同上（内嵌） | `TaskArticleProgressMsg`（**`user_id` 是 string**） | `contract/TaskArticleProgressMsg.java` | 1:N | ✅ | **PASS** | 同上 |
| C-08 | `like-proto/src/main/proto/like.proto` | L1 wire 契约（9 message + 1 enum + 6 rpc） | `proto/src/main/proto/like.proto` | 1:1 | ✅ | **PASS** | **逐字节 diff 只输出 `java_package` 一行** |

### 2.2 API 侧（HTTP 边界）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现状态 | 验证状态 | 证据 |
|---|---|---|---|---|---|---|---|
| A-01 | `like-api/.../LikeApiApplication.java` | api 进程入口 | `LikeServiceApplication.java` | 1:N | ✅ | **PASS** | `LikeServiceApplicationTests`（上下文真起） |
| A-02 | `like-api/.../advice/GlobalResponseAdvice.java` | 成功出口包信封（只包一层） | `advice/LikeApiResponseAdvice.java` | 1:N | ✅ | **PASS** | 真 Tomcat 信封形状断言 |
| A-03 | `like-api/.../advice/GlobalExceptionHandler.java` | 9 类异常 → 各自出口 | `advice/LikeApiExceptionHandler.java` | 1:N | ✅ | **PASS** | 401/404/405/415/5001 全分支断言 |
| A-04 | `like-api/.../config/LikeApiProperties.java` | `like-api.*` 配置绑定 | `config/LikeApiProperties.java` | 1:1 | ✅ | **PASS** | 绑定断言（fail-closed 密钥） |
| A-05 | `like-api/.../config/WebMvcConfig.java` | 注册 interceptor 到 `/like/v1/**`（public 集为空） | `config/WebMvcConfig.java` | 1:1 | ✅ | **PASS** | 真 Tomcat：未匹配路径不遮蔽 404 |
| A-06 | `like-api/.../config/LikeGoStyleCoercion.java` | 域内 2 条 Jackson 强转补强 | `config/WebMvcConfig.java#likeGoStyleCoercionCustomizer` | 1:N | ✅ | **PASS** | 4 条逐条核对（`vs-common.md` §2.1） |
| A-07 | `like-api/.../controller/LikeController.java` | 6 个 `@PostMapping` 端点 | `controller/LikeController.java` | 1:N | ✅ | **PASS** | 6/6 path + method 逐条比对 ＋ 真 Tomcat |
| A-08 | `like-api/.../service/LikeCountService.java` 等 **6 个** | 6 个 HTTP 用例 | `service/LikeApiService.java` | **N:1** | ✅ | **PASS** | 6 个用例逐条走通 |
| A-09 | `like-api/.../service/LikeApiSupport.java` | uid 提取（1006）＋ reject 指标 ＋ 错误映射 | `service/LikeApiSupport.java` | 1:1 | ✅ | **PASS** | claim 形态 4 类 ＋ route 标签硬编码值 |
| A-10 | `like-api/.../service/GoStyleTargetIds.java` | `target_ids` 的 null 元素语义 | `service/LikeApiSupport.java#normalizeTargetIds`（**无独立文件**） | 1:N | ✅ | **PASS** | 4 形态逐条（空/全 null/部分 null/正常） |
| A-11 | `like-api/.../dto/` **15 个** | 请求/响应 DTO ＋ 字段级强转 | `dto/{action,count,list,state}/` **15 个** | 1:N | ✅ | **PASS** | 31 个 JSON tag 逐条比对 ＋ `LikeDtoWireShapeTest` |
| A-12 | `like-api/.../dto/GoStyleFloat64Deserializer.java` | `weight` 的 float64 强转 | `common/web/Float64Deserializer.java`（**上收**） | 1:N | ✅ | **PASS** | `1e400` 拒 |
| A-13 | `like-api/.../dto/GoStyleRawStringListDeserializer.java` | `target_ids` 原始数组解析 | `common/web/RawStringListDeserializer.java`（**上收**） | 1:N | ✅ | **PASS** | 字面量保留 / null 保留 / bool·object·array 拒 |
| A-14 | `like-api/.../metrics/LikeApiMetrics.java` | `like_api_reject_total{route,reason}` | `metrics/LikeApiMetrics.java` | 1:1 | ✅ | **PASS** | 指标名 ＋ 标签键 ＋ route 硬编码值 |
| A-15 | `like-api/.../client/LikeRpcClient.java` | 自我 gRPC 客户端 | **删除**（D2 域内直连） | — | ✅ | **PASS** | 域内 `service/` 无 `client/` 副本 |
| A-16 | `like-api/.../interceptor/LikeJwtAuthInterceptor.java` | claim 提取 ＋ 401 空 body | `interceptor/LikeJwtAuthInterceptor.java` | 1:1 | ✅ | **PASS** | 401 空 body ＋ claim 4 形态 |
| A-17 | `like-api/.../auth/LikeJwtAuthFilter.java` ＋ `LikeJwtSignatureVerifier.java` | 验签在 Servlet Filter（**预算外**） | `interceptor/LikeJwtAuthInterceptor.java`（**预算内**） | **N:1** | ✅ | ⚠️ **INTENTIONALLY_CHANGED** | 见 **DRIFT-LIKE-001** |
| A-18 | `like-api/.../dto/LikeActionReq.java` ＋ `config/LikeGoStyleCoercion.java` | `,string` int64 ＋ 2 条补强 | `dto/action/LikeActionReq.java` ＋ `common/web/StringInt64.java` ＋ `common/web/Coercion.java` | 1:N | ✅ | **PASS** | 4 条补强逐条核对 |

### 2.3 RPC 侧 —— 领域层与协作者

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现状态 | 验证状态 | 证据 |
|---|---|---|---|---|---|---|---|
| R-01 | `like-rpc/.../LikeRpcApplication.java` | rpc 进程入口 | `LikeServiceApplication.java` | 1:N | ✅ | **PASS** | 同 A-01 |
| R-02 | `like-rpc/.../config/LikeRpcProperties.java` | `like.*` 配置绑定 | `config/LikeRpcProperties.java` | 1:1 | ✅ | **PASS** | 绑定断言 |
| R-03 | `like-rpc/.../config/LikeKafkaTopics.java` | 4 个 topic ＋ consumers=8 | `messaging/LikeKafkaTopics.java` | 1:1 | ✅ | **PASS** | topic 逐条比对 ＋ registry 断言 |
| R-04 | `like-rpc/.../service/impl/LikeServiceGrpcImpl.java` | 6 个 gRPC 用例 | `grpc/LikeGrpcService.java` | 1:1 | ✅ | **PASS** | `LikeGrpcServiceContractTest`（in-process 真 gRPC） |
| R-05 | `like-rpc/.../service/LikeDomainService.java`（6 方法合 1） | 6 个 RPC 用例 | `domain/service/LikeDomainService.java` | **N:1** | ✅ | **PASS** | 6 方法全可分发 |
| R-06 | `like-rpc/.../service/LikeActionScript.java` | Lua 写脚本（**事务边界独立成类**） | `service/LikeActionScript.java` | 1:1 | ✅ | **PASS** | ARGV 顺序断言 |
| R-07 | `like-rpc/.../service/LikeCursorIdGenerator.java` | 域内自实现雪花游标 | `service/LikeCursorIdGenerator.java` | 1:1 | ✅ | **PASS** | 22 位位移往返 ＋ 裸 Unix 毫秒 |
| R-08 | `like-rpc/.../service/LikeRedisKeys.java` | 6 类 Redis key ＋ TTL | `service/LikeRedisKeys.java` | 1:1 | ✅ | **PASS** | 6 模板 ＋ 2 个 TTL 逐条 |
| R-09 | `like-rpc/.../service/LikeKafkaPushKey.java` | 推送 key 生成 | `service/LikeKafkaPushKey.java` | 1:1 | ✅ | **PASS** | — |
| R-10 | `like-rpc/.../service/LikeTaskProgressPublisher.java` | task 域跨域事件 | `messaging/LikeTaskProgressPublisher.java` | 1:1 | ✅ | **PASS** | topic 逐条 |
| R-11 | `like-rpc/.../client/MessageRpcClient.java` | 跨域 gRPC 客户端 | `client/MessageRpcClient.java` | 1:1 | ✅ | **PASS** | 两侧同形（BlockingStub ＋ 无 deadline）＋ **跨域真链路已跑**（真 netty server ＋ 真 discovery ＋ 请求**逐字段** == B 侧规格 ＋ 失败语义 ⇒ §四·4.J） |
| R-12 | `like-rpc/.../metrics/LikeRpcMetrics.java` ＋ `service/LikeSpans.java` | 2 个 Counter ＋ 13 个 span 属性 | `metrics/LikeRpcMetrics.java` | 1:N | ✅ | **PASS** | 5 指标名 ＋ 13 span 属性逐条 |
| R-13 | `like-rpc/.../metrics/LikeMqMetrics.java` | `like_mq_consume_total{action,result}` | `metrics/LikeMqMetrics.java` | 1:1 | ✅ | **PASS** | 5 个 result 取值域 |
| R-14 | `like-rpc/.../domain/` **5 个 Entity** | 5 张表的 ORM 实体 | `domain/entity/` **5 个** | 1:1 | ✅ | **PASS** | DDL 逐条 ＋ 真 PG |
| R-15 | `like-rpc/.../mapper/` **5 个 Mapper** | 数据访问 | `mapper/` **5 个** | 1:1 | ✅ | **PASS** | 全部显式 SQL 注解（**零 `BaseMapper` 零 `updateById`**） |
| R-16 | `like-rpc/.../domain/LikeConsumeInboxEntity.java` ＋ `mapper/LikeConsumeInboxMapper.java` | 收件箱（`MsgId` 主键 ＋ `PayloadHash` 指纹） | `domain/entity/` ＋ `mapper/` | 1:N | ✅ | **PASS** | 幂等 ＋ 同 msg_id 异载荷报错 |
| R-17 | `like-rpc/.../domain/LikeOutboxEventEntity.java` ＋ `mapper/LikeOutboxEventMapper.java` ＋ `mq/LikeHotEventOutboxRelay.java` | 发件箱 → `article-hot-events` | `domain/entity/` ＋ `mapper/` ＋ `messaging/LikeHotEventOutboxRelay.java` | 1:N | ✅ | **PASS** | 装配已到位 ＋ **推送面真跑**（真 broker ＋ 真 `@Scheduled` 中继 ⇒ 待发行事件真的推到 `article-hot-events`，payload **5 字段 ＋ 字段序**逐个钉住，见 §四·4.I） |
| R-18 | `like-rpc/.../domain/LikeDomainFactOutboxEntity.java` ＋ `LikeFactStreamEntity.java` ＋ `service/LikeDomainFactService.java` | 领域事实 ＋ 版本分配 ＋ 双信封 | `domain/entity/`（2 个）＋ `domain/service/LikeDomainFactService.java` | 1:N | ✅ | **PASS** | 4 个事实常量 ＋ 版本 ＋ 双信封 |
| R-19 | `like-rpc/.../service/LikeMessageDigest.java` ＋ `GoCompatibleJson.java` | 消息语义指纹（**JCS**） | `service/LikeMessageDigest.java` ＋ `service/GoCompatibleJson.java` | 1:N | ✅ | **PASS** | 7 字段敏感 ＋ Go HTML 转义 ＋ SHA-256 |
| R-20 | `like-rpc/.../service/LikeMessageIngestService.java` | **inbox ＋ record ＋ fact 单事务提交** | `service/LikeMessageIngestService.java` | 1:1 | ✅ | **PASS** | 真 PG 整批回滚 ＋ 乱序不回放 |
| R-21 | `like-rpc/.../service/LikeDeliveryValidator.java` | 冻结信封合法性校验 | `service/LikeDeliveryValidator.java` | 1:1 | ✅ | **PASS** | 4 常量 ＋ 严格解码拒未知字段 |
| R-22 | `like-rpc/.../service/LikeFactDispatchService.java` | 单条事实投递 ＋ 并发语义 | `service/LikeFactDispatchService.java` | 1:1 | ✅ | **PASS** | `TransactionTemplate` 显式 commit/rollback |
| R-23 | `like-rpc/.../service/LikeAuthorityLookupService.java` | 权威读 | `service/LikeAuthorityLookupService.java` | 1:1 | ✅ | **PASS** | — |
| R-24 | `like-rpc/.../exception/LikeExceptions.java` | 业务异常工厂 | `exception/LikeExceptions.java` | 1:1 | ✅ | **PASS** | — |
| R-25 | `like-rpc/.../bootstrap/NacosRegistration.java` | Nacos 注册（**gRPC 端口**） | `wiring/NacosRegistration.java` | 1:1 | ✅ | **PASS** | `register-enabled:false` ＋ 手写注册 |
| R-26 | `like-rpc/.../bootstrap/LikeSchemaGate.java` | 启动闸（表存在性） | `wiring/LikeSchemaGate.java` | 1:1 | ✅ | **PASS** | 同形（`@EventListener(ApplicationReadyEvent)` ＋ 5 表） |

### 2.4 事实流水线（B 侧 2 个独立 cmd → C 侧域内组件）

| ID | B 源文件 | B 原始职责 | C 承接文件 / 方法 | 映射类型 | 实现状态 | 验证状态 | 证据 |
|---|---|---|---|---|---|---|---|
| F-01 | `like-rpc/.../mq/LikeFactDispatchScheduler.java` | 事实流派发调度 | `wiring/LikeFactDispatchScheduler.java` | 1:1 | ✅ | **PASS** | 复用 `common/communityfact/EventDispatcher`（1s / 批次 16 / 10s deadline） |
| F-02 | `like-rpc/.../mq/LikeAuthorityEndpointConfig.java` | 权威读 Servlet（**B 侧是骨架**） | `wiring/LikeAuthorityEndpointConfig.java` | 1:1 | ✅ | ⚠️ **NOT_VERIFIED** | 端点已注册（路径 `/internal/v1/community/facts`）；**B 侧无真值可比** |
| F-03 | `like-rpc/.../bootstrap/LikeFactProcessLogging.java` | 进程级 logger 身份（**B 侧是骨架**） | `wiring/LikeFactProcessLogging.java` | 1:1 | ✅ | ⚠️ **INTENTIONALLY_CHANGED** | 见 **DRIFT-LIKE-008**（`like.fact.process.*` 三元组是 C 侧新增表达层） |
| F-04 | `like-rpc/.../mq/LikeUpdateKafkaListener.java` | 消费入口 ＋ ack 契约 ＋ 失败即丢弃 | `messaging/LikeUpdateKafkaListener.java` | 1:1 | ✅ | **PASS** | **装配层已证**（registry 断言：id/topic/group/并发度 8）＋ **消费面真跑**（真 broker ＋ 真 `@KafkaListener` ⇒ `like_consume_inbox` / `like_record` / `like_fact_stream` / `like_outbox_event` **四处齐全**，见 §四·4.I） |
| F-05 | `like-rpc/.../service/LikeDeliveryValidator.java` ＋ `EventSender`/`Dispatcher` | 投递协议 | `common/communityfact/`（**只接线不重写**） | 1:N | ✅ | **PASS** | A11 守门：9/9 落点齐备 |

### 2.5 承接表汇总（**机器复算**）

| 分类 | 条目数 | PASS | INTENTIONALLY_CHANGED | NOT_VERIFIED | FAIL | NOT_IMPLEMENTED |
|---|---|---|---|---|---|---|
| 契约层（C-01..C-08） | 8 | **8** | 0 | 0 | 0 | 0 |
| API 侧（A-01..A-18） | 18 | **17** | 1 | 0 | 0 | 0 |
| RPC 侧（R-01..R-26） | 26 | **26** | 0 | 0 | 0 | 0 |
| 事实流水线（F-01..F-05） | 5 | **4** | 1 | 1 | 0 | 0 |
| **合计** | **57** | **54** | **2** | **1** | **0** | **0** |

> 📌 **计数是机器复算的，不是手数的**（AGENTS §3.3 纪律）：
> ```bash
> # 复算命令（可复跑）
> python3 - <<'PY'
> import re, pathlib
> from collections import Counter
> t = pathlib.Path("docs/DRIFT/like/verification-like-2026-11-03.md").read_text(encoding="utf-8")
> sec = t.split("### 2.5 承接表汇总")[0]
> rows = re.findall(r'^\| ((?:C|A|R|F)-\d+) \|.*?\|\s*(?:✅|⚠️)?\s*\*\*(PASS|INTENTIONALLY_CHANGED|NOT_VERIFIED|NOT_IMPLEMENTED|FAIL)\*\*', sec, re.M)
> print(len(rows), dict(Counter(s for _, s in rows)))
> PY
> # ⇒ 57 {'PASS': 54, 'INTENTIONALLY_CHANGED': 2, 'NOT_VERIFIED': 1}
> ```
> 📌 **口径**：「承接表条目」= **B 侧职责级**（不是文件级）—— 映射表 81 行按职责归并为 57 条。
> ⚠️ **本表只统计「职责承接」这一个维度** —— **配置面 / 框架面 / 覆盖缺口的偏离不在本表内**
> （那些在 §五 的 13 条漂移清单里）⇒ **两表不相加**。
> ⚠️ **表 ≠ 命题**：表内 0 个 `NOT_IMPLEMENTED` **不等于**「行为完全等价」——
> 表外的剩余缺口见 §七·7.1。

---

## 三、多文件合并 / 拆分 / 架构调整专项（§8）

### 3.1 全部 N:1 映射（5 条）

| # | B 侧多文件 | C 侧承接 | 逐项核对结论 |
|---|---|---|---|
| N-1 | `like-api/.../service/` **6 个** `*Service`（`LikeCountService` / `LikeStateService` / `TargetLikerListService` / `UserLikeListService` / `UserTotalLikeService` / `LikeActionService`） | `service/LikeApiService.java` | ✅ 6 个用例逐条走通；**共享逻辑**（uid 提取 ＋ reject 指标 ＋ 错误映射）下沉到 `LikeApiSupport` ⇒ **无遗漏**。⚠️ 已核「**鉴权不对称**」：2 个公开读端点不取 uid、4 个取 uid ⇒ 两侧**逐条相同**（B 侧 `LikeCountService:21,33-35` / `TargetLikerListService:17,27-29` 不取） |
| N-2 | `like-rpc/.../service/LikeDomainService.java`（6 方法合 1） | `domain/service/LikeDomainService.java` | ✅ 6 个 RPC 用例合 1；**13 个协作者一个都没并进去**（各有独立变化原因，映射表 DL-5） |
| N-3 | `like-api/.../auth/LikeJwtAuthFilter.java` ＋ `LikeJwtSignatureVerifier.java` | `interceptor/LikeJwtAuthInterceptor.java` | ⚠️ **有行为差异** —— 见 **DRIFT-LIKE-001**（验签位置）。**响应形状（401 ＋ 空 body）逐条相同** |
| N-4 | `service/common/.../protection/` **17 类**手写复刻 | `common/protection/` **2 类** ＋ `ProtectionAutoConfiguration` | ✅ D4 生态优先（熔断 → resilience4j）⇒ 域内**零重复** |
| N-5 | `like-rpc/.../config/Jackson2ObjectMapperConfiguration.java`（B 侧 5 份副本） | `common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（**共享 1 份**） | ✅ D12 上收；域内零副本 |

### 3.2 全部 1:N 映射（**关键 6 条**；共 35 条）

| # | B 侧 1 个文件 | C 侧拆成 | 逐项核对结论 |
|---|---|---|---|
| S-1 | `like-rpc/.../mq/LikeEventContracts.java`（内嵌 3 record） | `contract/{KafkaLikeMsg,ArticleHotEvent,TaskArticleProgressMsg}.java` | ✅ 15 个字段的**名 ＋ 类型**逐条一致；⚠️ 特别核了 `user_id`：`KafkaLikeMsg` 是 **long**、另两个是 **String** ⇒ **两侧同型，无 string↔int64 漂移** |
| S-2 | `like-rpc/.../domain/LikeDomainFactOutboxEntity.java` ＋ `LikeFactStreamEntity.java` ＋ `service/LikeDomainFactService.java` | `domain/entity/`（2 个）＋ `domain/service/LikeDomainFactService.java` | ✅ 职责连续（版本分配 → 双信封 → 投递）；**事务边界未漂移** |
| S-3 | `like-api/.../dto/` 15 个 | `dto/{action,count,list,state}/` 15 个（D11 按业务对象拆子包） | ✅ **JSON tag 逐字保留**（31 个 tag 逐条比对）；**DTO 层零 Lombok**（A9 守门 PASS） |
| S-4 | `like-rpc/.../mapper/LikeRecordMapper.java` ＋ `service/LikeDomainService.java` ＋ `service/LikeMessageIngestService.java` | 同名 3 文件 | ✅ **inbox ＋ record ＋ fact 单事务提交**整段保留（真 PG 整批回滚已证） |
| S-5 | `like-rpc/.../bootstrap/LikeSchemaGate.java` ＋ `mapper/` 5 个 | `wiring/LikeSchemaGate.java` ＋ `mapper/` 5 个 | ✅ 启动闸职责从「表与进程同生共死」改为「Flyway 跑完再验表存在」 |
| S-6 | `like-api/.../service/GoStyleTargetIds.java` | `service/LikeApiSupport.java#normalizeTargetIds`（**无独立文件**） | ✅ null 元素语义 4 形态逐条（**长度不变**这条最容易做错，已钉住） |

### 3.3 API ＋ RPC 合并（§8.3）

| 检查项 | 结论 | 证据 |
|---|---|---|
| HTTP 入口完整保留 | ✅ | 6/6 端点 path ＋ method ＋ 入参 ＋ 出参逐条一致 |
| gRPC 入口完整保留 | ✅ | 6 个方法 ＋ proto 逐字节一致 ＋ in-process 真 gRPC 4 例 |
| RPC Server 正确启动 | ⚠️ **静态等价，运行未覆盖** | 测试里 `grpc.server.port=-1` ⇒ netty server **从未起过**（见 **DRIFT-LIKE-012**） |
| RPC Client 正确注册 | ✅ | `MessageRpcClient` ＋ yml `grpc.client` 4 处全 `plaintext`（A4 守门 PASS） |
| 域内 RPC 往返调整 | ✅ D2 | 自我 gRPC → 直连 `LikeDomainService`；**gRPC→业务码映射层删除** ⇒ `LikeApiSupport.rpcError` 逐字透传 `(bizCode,msg)` |
| 共用 Bean 正确装配 | ✅ | 真上下文测试 ＋ 5 张表存在 ＋ 两个 Properties 绑上 |
| HTTP 与 RPC 安全上下文互不影响 | ✅ | 两套入口的 claim 提取各自独立；`grpc.server.port` 独立于 `server.port` |
| 两类入口的启动/关闭 | ⚠️ | 上下文能起已证；**关闭顺序未单独验证** |

### 3.4 Common Starter 共享能力上收（§8.4）

**8 项上收 · 域内零副本**（明细见 [`vs-common.md`](vs-common.md) §2）⇒ 守门 A6「MP ordering bridge 全仓只 1 份」PASS。
⚠️ **唯一留在域内的 Jackson 补强**是 B 侧本就**域私有**的 2 条规则（`LikeGoStyleCoercion`）⇒ **不是漏收**。

### 3.5 独立进程合并（§8.5）

| B 侧独立进程 | C 侧落点 | 频率 | 启动条件 | 失败处理 | 结论 |
|---|---|---|---|---|---|
| `rtw-like-fact-dispatch` | `wiring/LikeFactDispatchScheduler.java` | **1s** | `like.fact.dispatch-enabled` | 共享层 `EventDispatcher` 承担 | ✅ 节奏 ＋ 批次 ＋ deadline 逐条对照 |
| `rtw-like-fact-authority` | `wiring/LikeAuthorityEndpointConfig.java` | 常驻 Servlet | `like.authority.*` 有值 | — | ⚠️ **B 侧是骨架** ⇒ 无真值可比 |
| B 侧 like-rpc 的 9098 观测口 | **并入主 app 端口** | — | — | — | ✅ D1 合并裁决（**DRIFT-LIKE-002**） |

---

## 四、框架版本差异报告（§7）

### 4.A 依赖体系

| 项 | B | C | 是否可观察地不同 | 适配方式 | 证据 | 结论 |
|---|---|---|---|---|---|---|
| Boot 4 → 3.5 | 4.0.8 | 3.5.11 | **否** | D14 既定 | `pom.xml:10` | ✅ |
| Boot 4 专有 artifact | `spring-boot-kafka` / `spring-boot-flyway` | **0 个** | **否** | 换成 Boot 3.5 形态（`flyway-core` + `flyway-database-postgresql`） | 本地 `~/.m2` 实证这 5 个 artifact 只有 4.x | ✅ |
| Jackson 3 → 2 | `tools.jackson.*` 3.1.5 | `com.fasterxml.jackson.*` 2.19.4 | **否**（逐条等价，见 4.B） | 强转规则走 `postConfigurer` 路线 | `common/web/Coercion.java:128-178` ＋ `CoercionTest` 10 例 | ✅ |
| `tools.jackson` 残留 | — | **0 处** | — | — | 全仓 grep | ✅ |
| Jackson 2 exclusion | — | **0 条** | — | — | 全仓 pom | ✅ |
| Flyway 11.14.1 覆盖 BOM 11.7.2 | — | 显式覆盖 | **未确认** | — | **同 major 内公开 API 兼容；本次未单独运行验证** | ⚠️ **FRAMEWORK_DIFFERENCE**（低） |

### 4.B 自动配置与 Bean 装配（**「Bean 存在」≠「机制生效」**）

| 机制 | B 怎么做 | C 怎么做 | 适配方式 | 装配层证据（**读消费点**） | 结论 |
|---|---|---|---|---|---|
| **Flyway** | Boot 4 需 `spring-boot-flyway`；`spring.flyway.*` 在 `spring:` 下 | Boot 3.5 只需 `flyway-core` + `flyway-database-postgresql`；**同样在 `spring:` 下** | 配置键逐条照抄 | ✅ **真 PG**：5 张表存在（`LikeServiceApplicationTests`）＋ `LikeFlywayBaselineRealPgTest` 4 例（账本表名 `flyway_schema_history_like` ＋ V1..V6 全 success ＋ `baseline-version:0`）<br>✅ **版本显式断言**：`LikeFlywayRuntimeVersionTest` 4 例（见 §四·4.B'） | ✅ **已证明等价** |
| **Kafka listener** | Boot 4 需 `spring-boot-kafka`，否则**静默不消费** | Boot 3.5：`KafkaAutoConfiguration` 在 `spring-boot-autoconfigure` ⇒ 只引 `spring-kafka` 即可 | 域内**显式自建**容器工厂 | ✅ **装配层已证**：`KafkaListenerEndpointRegistry` 真有 1 个端点，id=`like-update-consumer`、topic=`like_promotion_topic`、group=`like-update-group`、**并发度 8**（Boot 默认工厂只会读 `spring.kafka.listener.concurrency`，本域未配 ⇒ 1 ⇒ **并发度 8 本身就证明自建工厂被用上**） | ✅ **已证明等价**（装配层 ＋ **消费面真跑** ⇒ §四·4.I；**DRIFT-LIKE-013 覆盖缺口已闭合**） |
| **gRPC server** | devh 3.1.0 ＋ grpc 1.63.0 | **同版本** | 无需适配 | ✅ **真 netty server 已证**：`LikeGrpcNettyServerRealTest` 5 例（真 TCP 端口 ＋ 真 netty 工厂 ＋ 裸 socket 可连 ＋ 6 个 rpc 方法真发 ＋ 错误出口的 description/trailer 过 HTTP/2 线上序列化）—— **原「netty server 从未起过」的覆盖缺口已闭合** | ✅ **已证明等价**（**DRIFT-LIKE-012 覆盖缺口已闭合**） |
| **Jackson mapper** | `JsonMapperBuilderCustomizer` ＋ `withCoercionConfig` | `Jackson2ObjectMapperBuilderCustomizer` ＋ `postConfigurer` → `coercionConfigFor` | **Spring 6.2 的 `Jackson2ObjectMapperBuilder` 无 coercion API** ⇒ 只能走 `postConfigurer`；**不替换** Boot 的 mapper（替换会让 `spring.jackson.*` 绑定全失效） | ✅ `CoercionTest` 10 例 ＋ `JsonBodyConverterAutoConfigurationTest` | ✅ **已证明等价** |
| **Jackson DTO 注解** | 可变 class ＋ setter 上的 `@JsonDeserialize` | immutable record ＋ 显式 `@JsonCreator` 工厂，注解**两处都写** | 见 §四·4.E 的**区分性实验** | ✅ `LikeJsonCreatorAnnotationScopeTest` **5 例**（含 2 个区分性 fixture） | ✅ **已证明等价** |
| **Nacos** | SCA 2025.1.0.0 ＋ `register-enabled:false` ＋ 手写注册（**gRPC 端口**） | SCA 2025.0.0.0 ＋ nacos-client 3.0.3 ＋ 同款 | group 由硬编码改为读配置（**默认值同** ⇒ 生效值一致） | `wiring/NacosRegistration.java` | ✅ 等价（**DRIFT-LIKE-003**） |
| **Redis** | Boot 4 的 starter 不传递 `commons-pool2` ⇒ 显式声明 | Boot 3.5 **同样不传递** ⇒ 显式声明 | 沿用 B 侧同款处置 | `application.yml` 4 个池轴逐条对齐 | ✅ 等价 |
| **MyBatis-Plus** | `mybatis-plus-spring-boot3-starter` 3.5.17 | **同一 artifact、同一版本** | 无需适配 | 排序桥用 **Boot 3.5 的 FQN**（Boot 4 才挪包）⇒ 排序边**真实成立** | ✅ 等价 |
| **Actuator / Prometheus** | like-rpc 有 web 载体 ⇒ `/actuator/prometheus` 有出口 | **生产形态可用**（`PrometheusMeterRegistry` 存在 ＋ 端点 200 ＋ 真流量打出 `like_rpc_query_cache_total{...}`） | 无需适配 | ✅ **真 fat jar 活体**（2026-11-03 · **最强证据**）：`java -jar like-service-1.0-SNAPSHOT.jar` 起成功后 `GET /actuator/prometheus` = **HTTP 200 + `text/plain;version=0.0.4;charset=utf-8`**（**这个 content-type 只有 `PrometheusMeterRegistry` 会产出**）＋ scrape 里出现 `like_rpc_query_cache_total{result="miss",target_type="article"} 1.0`<br>✅ 另有**测试上下文**证据：`LikePrometheusEndpointRealTest` 3 例<br>⚠️ **原「404 ⇒ 装配顺序错」的归因已被证伪**，见 §四·4.F | ✅ **已证明等价**（**DRIFT-LIKE-010 系假阳性，已撤销**） |

### 4.C Web / Servlet / HTTP 行为

| 项 | 结论 | 证据 |
|---|---|---|
| `@RestControllerAdvice` / `ResponseBodyAdvice` / `HandlerInterceptor` **真生效** | ✅ **真 Tomcat 实测**（非 MockMvc） | 401 空 body / 404 `text/plain;charset=utf-8` ＋ `nosniff` ＋ body `"404 page not found\n"` / 405 ＋ `Allow: POST` / 415 / 信封形状 |
| Spring Security filter chain | ✅ **两侧都没有** ⇒ 鉴权语义等价（都是自定义 JWT 拦截器） | 全仓 grep `SecurityFilterChain` = 0 |
| Tomcat 11 → 10.1 | ✅ like 只触及 3 个旋钮（`relaxed-query-chars` / `max-parameter-count` / `max-http-request-header-size`），10.1.52 **全支持且两侧同值** | `application.yml` ≡ B 侧 `like-api/application.yml`；⚠️ `max-parameter-count` 两侧都被共享层抬到 10001 ⇒ **同形，非 C 侧漂移** |
| 404 的 Content-Type 字节形状 | ✅ **逐字节钉住**（去空格后 = `text/plain;charset=utf-8`）＋ body 精确匹配 | `LikeHttpWireContractRealTomcatTest`（**本轮收紧**） |

### 4.D 事务与数据库

| 项 | 结论 | 证据 |
|---|---|---|
| `@Transactional` 使用点 | ✅ **全域仅 1 处**：`service/LikeMessageIngestService.java:152`（`rollbackFor = Exception.class`）⇒ 与 B 侧 `like-rpc/...:124` **逐字相同** | — |
| **自调用风险** | ✅ **无** —— `LikeUpdateKafkaListener` 走**注入**的 bean，不是 `this.xxx()` | — |
| **代理真的生效** | ✅ **真 PG 整批回滚**：批内第 2 条非法 ⇒ 先落的那条 inbox ＋ record 计数均为 **0** | `LikeMessageIngestRealPgTest` |
| 编程式事务 | ✅ 两侧同款 `TransactionTemplate` ＋ 显式 commit/rollback | `LikeFactDispatchService` ≡ B 侧 |
| MP 写路径形态 | **N/A** —— like 域 5 个 Mapper **全部显式 SQL 注解**，**零 `BaseMapper`、零 `updateById`、零 `update(null, wrapper)`** ⇒ MP CRUD 语义差异**不构成风险面** | `mapper/LikeRecordMapper.java` 等 5 个 |
| Hikari | ✅ `maximum-pool-size:100` / `minimum-idle:10` 两侧同值（含「口令不加默认值」） | `application.yml` ≡ B 侧 |

### 4.E 🔴 **本轮自查改正**：一条**被证伪的机制断言**

> **触发**：框架差异核证时发现，3 个 DTO 的 javadoc 里以「**实测证据**」措辞断言
> 「**record 组件上的 `@JsonDeserialize` 不会被应用**」（AGENTS §3.3.2 点名的高危措辞），
> 而配套的 `LikeDtoWireShapeTest` 只断言**结果** ⇒ **分不清**是哪一处注解在生效 ⇒ **无区分性实验支撑**。

**本轮做了什么**：新写 `dto/LikeJsonCreatorAnnotationScopeTest`（**5 个同形 fixture，只挪注解位置**）：

| fixture | 组件注解 | 工厂参数注解 | 工厂参数 JSON 名 | 实测结果 |
|---|---|---|---|---|
| ① 无 `@JsonCreator` | ✅ | — | — | **组件注解生效** |
| ② 有 creator | ❌ | ✅ | `ids`（同名） | **工厂参数注解生效** |
| ③ 有 creator | ✅ | ✅ | `ids`（同名） | **生效** |
| ④ 有 creator | ✅ | ❌ | `ids`（同名） | 🔴 **组件注解被继承 ⇒ 也生效** ⇒ **证伪了原断言** |
| ⑤ 有 creator | ✅ | ❌ | `target_ids`（**异名**） | 🔴 **继承断链 ⇒ 不生效** |

**结论（已写回 3 个 DTO 的 javadoc）**：
1. ⚠️ **「组件上的 `@JsonDeserialize` 不会被应用」是错的** ⇒ **已更正**（AGENTS §3.3.2：错误的机制断言比没有断言更危险）。
2. ✅ **但工厂参数上仍然要写**：① 不依赖「同名继承」这一实现细节；② 实验 ⑤ 证明 JSON 名一改，继承就断链 ⇒ 组件注解沦为装饰品。
3. ✅ **行为未受影响**（两处都写 ⇒ 生效）⇒ **只改注释，不改实现**。
4. 📌 **原 javadoc 里那段「修复前解析成功」的证据描述的是实现过程中的中间态**（当时的 DTO 还是 TODO 空壳，**仓库里不可复现**）⇒ 一并删除。

---

### 4.F 🔴 **本轮自查改正（其二）**：DRIFT-LIKE-010 是**假阳性** —— `/actuator/prometheus` 404 的真因

> **触发**：本轮补「生产形态上下文的 `/actuator/prometheus`」证据时，先按**旧台账的结论**去复现 404，
> 结果**复现不出来** ⇒ 逐层反查 ⇒ 发现**旧结论的两条归因都是错的**。

#### 🔴 旧结论写了什么（两条，都错）

| 旧结论 | 错在哪 |
|---|---|
| 「Boot 3.5 **装配顺序**使 `simpleMeterRegistry` 先注册 ⇒ **无 `PrometheusMeterRegistry`**」 | **机制错**。实测 Boot 3.5.11 的 `PrometheusMetricsExportAutoConfiguration` 标着 `@AutoConfiguration(before = {CompositeMeterRegistryAutoConfiguration, SimpleMetricsExportAutoConfiguration})` ⇒ **Prometheus 在前**，「simple 顶掉 Prometheus」的说法不成立 |
| 「生产形态上下文**没有** Prometheus registry」 | **把「测试上下文」的特性当成了「生产特性」**。测试上下文里确实没有 registry，但**生产形态下有** |

#### 🔴 404 的真因：**Boot 测试框架的全局禁用**（不是本仓配置问题）

| 层 | 证据 |
|---|---|
| 症状 | 生产形态上下文（`@SpringBootTest` ＋ **不加** `@AutoConfigureObservability`）⇒ **无** `PrometheusMeterRegistry` ⇒ `/actuator/prometheus` **404** |
| 条件评估报告 | `management.defaults.metrics.export.enabled is considered false` |
| 属性来源 | property source **`test`** ⇒ 内容恰好 3 键：`management.defaults.metrics.export.enabled=false` / `management.simple.metrics.export.enabled=true` / `management.tracing.enabled=false` |
| 根因 | Boot `spring-boot-test-autoconfigure-3.5.11.jar` 的 **`ObservabilityContextCustomizerFactory`** —— 其 javadoc 明写「**globally disables metrics export and tracing in tests**」⇒ **Boot 测试框架的全局行为**，与本仓配置、与 C↔B 行为差异**都无关** |

#### ✅ 修法（**生产代码零改动**）

`@AutoConfigureObservability(metrics = true, tracing = false)`
—— 精确开启 metrics、**保持 tracing 关**（否则 OTLP exporter 会真去连 `127.0.0.1:1`）。

#### ✅ 修完的读数（`LikePrometheusEndpointRealTest`，3 例，全绿）

1. `PrometheusMeterRegistry` **存在**；
2. `/actuator/prometheus` **200**；
3. 真流量（被拒请求）打出 `like_api_reject_total{reason="token_invalid",route="/like/likeaction"}`。

#### 📌 附带确认（**不是**漂移）

**Micrometer 1.15.x ＋ 新 Prometheus client（1.x）**：Counter 渲染为 `like_api_reject_total`（**不再**多一个 `_total`）
⇒ 与 B 侧（Boot 4.0.8 同代 Micrometer）**同形** ⇒ **不是**漂移。

#### ⇒ 结论

**DRIFT-LIKE-010 系「生产形态无 Prometheus registry ⇒ 跨域共性 ⇒ 归 `global` 收口域」的结论，是一条假阳性，已撤销。**
⚠️ **跨域影响**：`docs/DRIFT/follow/verification-follow-2026-11-02.md:327-330` 是**同款错判**
⇒ **属 follow lane，本 lane 只在 `vs-common.md` 登记建议 follow 复核**（AGENTS §五.3「只写自己域」）。

---

### 4.G 🔴 本轮补齐：Flyway **11.14.1 覆盖 Boot BOM 11.7.2** 的**显式**断言

> **为什么要单独钉**：**Boot 3.5.11 的 BOM 自己管的是 `flyway.version = 11.7.2`**
> （`spring-boot-dependencies-3.5.11.pom`）⇒ 本仓根 pom 的 `<flyway.version>11.14.1</flyway.version>`
> **必须真的覆盖掉它**，运行时才是 11.14.1。
> ⚠️ **这个覆盖一旦失效不会立刻报错**：11.7.2 与 11.14.1 在本域这 6 个脚本上**都能跑通**
> ⇒ ⇒ 「迁移成功」**证不了版本** ⇒ 必须直接断言**运行时加载的那个 artifact**。

`LikeFlywayRuntimeVersionTest`（4 例，全绿）：

| 断言 | 判据 | 为什么这样取证据 |
|---|---|---|
| 运行时 artifact | `Flyway.class` 的 code source 路径**含 `flyway-core-11.14.1.jar`**、**不含** `flyway-core-11.7.2.jar` | ⚠️ `flyway-core-11.14.1.jar` 的 `MANIFEST.MF` **没有 `Implementation-Version`** ⇒ `getPackage().getImplementationVersion()` 返回 `null` ⇒ 用它断言会得到「null == null」的**假绿**。文件名里带版本 ⇒ 判据自带「是哪一版」 |
| jar 自报版本 | jar 内 `org/flywaydb/core/internal/version.txt` = `11.14.1` | 与上一条**来自同一个 jar、读的是另一个文件** ⇒ 两条互不依赖 |
| 跑迁移的引擎同源 | 容器里的 `Flyway` bean 的 code source **等于** `Flyway.class` 的 code source | ⇒ 「跑迁移的引擎 = 11.14.1」成立（不是「某个别处的 jar 是 11.14.1」） |
| 行为交叉验证 | `flyway_schema_history_like` 里 V1..V6 六条全 `success = true` | 版本与行为互为交叉验证（行为细节另由 `LikeFlywayBaselineRealPgTest` 覆盖） |

> ✅ **原缺口「7-6：Flyway 版本覆盖（**间接**覆盖）」已从「间接」升级为「显式」。**

---

### 4.H 🔴 本轮补齐：netty gRPC server **真启动**（含一个会带偏排查方向的坑）

`LikeGrpcNettyServerRealTest`（5 例，全绿）—— 补的是 `LikeGrpcServiceContractTest`（**in-process**）**验不到**的东西：

| # | 断言 | 补的是什么 |
|---|---|---|
| 1 | devh 选中的 server 工厂是 **`ShadedNettyGrpcServerFactory`**（`contains("Netty")` / `doesNotContain("InProcess")`）＋ 实际端口 > 0 ＋ **裸 socket 能连上** | 「netty server 真的被创建并 bind 了 TCP 端口」（in-process 的 `getPort()` 恒为 -1、根本没有端口） |
| 2 | classpath 上登记的 `ManagedChannelProvider` **只有 netty 那个**（扫 `META-INF/services/io.grpc.ManagedChannelProvider`） | 「默认传输层是 netty」（`ManagedChannelRegistry#providers()` 是包级私有 ⇒ 只能从服务文件取证） |
| 3 | **6 个 rpc 方法全部经真线路分发** | in-process 是「直接传对象」，真线路要过 **HTTP/2 帧编解码** |
| 4 | **业务错误经真线路后**：**description 文案 ＋ `rag-like-biz-code` trailer** 都完整活下来 | **trailer 在真线上必须过 HTTP/2 trailer 头编码** ⇒ in-process 测不出「trailer 没真发出去」 |

#### 📌 另有一条**更强的活体证据**（2026-11-03 · 真 fat jar）

| 项 | 读数 |
|---|---|
| 起法 | `java -jar service/like/target/like-service-1.0-SNAPSHOT.jar`（**独立 PG/Redis 容器 ＋ 独立端口段 18311/18312/18316/18318 ＋ 独立 Nacos 分组 E6C** ⇒ 不碰其它 lane 的资源） |
| 启动 | `Tomcat started on port 18311 (http)` ＋ `gRPC Server started, listening on address: 0.0.0.0, port: 18312` ＋ `Started LikeServiceApplication in 2.694 seconds` ⇒ **无** `NoClassDefFoundError` |
| 业务面 | `POST /like/v1/getlikecount` ⇒ `{"code":200,"msg":"OK","data":{"counts":{"9007199254740994":{"like_count":0,"dislike_count":0}}}}`（**与冻结 Go 锚同形**）；无 `Authorization` 头 ⇒ **HTTP 401**（**与 `no_token` 锚同形**） |
| 收尾 | 杀进程后 **18311/18312 实测已释放** ＋ 容器零残留（⇒「进程退了」要**实测端口**，不能只看探针退出） |

#### 🔴 踩坑记录：**gRPC 客户端目标被 devh 劫持**（会让人误判成「服务端没起来」）

| 项 | 内容 |
|---|---|
| 症状 | 客户端立刻报 **`UNAVAILABLE: No servers found for 127.0.0.1:64059`** ⇒ 长得像「服务端没起 / 端口不对」 |
| 真因 | devh 的 `DiscoveryClientResolverFactory.priority() = 6`（源码注释原文：**"More important than DNS"**，DNS 只有 5）⇒ 它被 `GrpcClientAutoConfiguration` 注册进**全局** `NameResolverRegistry` ⇒ `getDefaultScheme()` 变成 **`discovery`** ⇒ **任何不带显式 scheme 的 gRPC 目标**都被拼成 `discovery:///host:port` ⇒ 去 Nacos 查「host」这个服务 ⇒ **零实例** |
| 边界 | **与 discovery 是否 enabled 无关**（只要上下文里有 devh 客户端 starter ＋ 一个 `DiscoveryClient` bean 就成立）⇒ **整个 JVM** 生效 |
| 修法 | 客户端**必须**用 `NettyChannelBuilder.forAddress(new InetSocketAddress(host, port))`（**不经过名字解析**）或显式 `dns:///` scheme |
| 📌 对下游的约束 | `tools/difftest/like/` 的 gRPC 线探针**必须**遵守同一条约束 |

### 4.I 🔴 本轮补齐：Kafka **三面真链路**（生产 / 消费 / 推送 / 幂等）

> 📌 **这是 §七·7.2 那个「本轮唯一剩下的验证缺口」的闭合**（⇒ **DRIFT-LIKE-013 已闭合**）。

#### 🔴 为什么这一面之前是零覆盖 —— 以及旧归因**写反了**

| 项 | 内容 |
|---|---|
| 旧归因（**错**） | 「`spring.kafka.listener.auto-startup=false` ⇒ listener 没起 ⇒ 消费面零覆盖」 |
| **实测真因** | 那条键**在本仓没有消费点** —— 应用在 `LikeServiceApplication#kafkaListenerContainerFactory`（第 88-97 行）里**自己 `new`** 了 `ConcurrentKafkaListenerContainerFactory` ⇒ Boot 的 `ConcurrentKafkaListenerContainerFactoryConfigurer` **不参与** ⇒ **`spring.kafka.listener.*` 一整族键全部不生效** ⇒ `autoStartup` 取框架默认值 **true** ⇒ **listener 其实一直都在起**（只是 broker 不可达 ⇒ 一直重试）<br>⚠️ 证据：全仓**没有任何** `setAutoStartup(...)` 调用（`grep` 实测） |
| **零覆盖的真因** | **没有 broker 可连**（`application-test.properties` 把 `bootstrap-servers` 指向 `127.0.0.1:1`）⇒ ⇒ 与 listener 起没起**无关** |
| ⇒ 处置 | 删掉那条死键（**删它不改变任何行为** —— 它本来就不生效）＋ 注释改成如实描述 ⇒ 登记为 **DRIFT-LIKE-016**（§五） |

> ⚠️ **为什么这条值得单列**：一条**错误归因**会把下一个人引去「修一个不存在的开关」。
> ⇒ 本轮补了**区分性实验**（§八·8.6 的 M1）：listener 一停 ⇒ 消费面/推送面/幂等面**立刻转红**
> ⇒ ⇒ **反证**「那键从未生效过」（若它生效，listener 本来就不会消费，测试根本不会绿）。

#### 🔴 broker 地址：为什么是 `127.0.0.1:49992`

本机 Sea-kafka（`apache/kafka:3.7.2`）有两个 listener：`PLAINTEXT://kafka:9092`（**容器内**）与
`EXTERNAL://127.0.0.1:49992`（**宿主发布口**，由 `KAFKA_ADVERTISED_LISTENERS` 决定）。
⇒ ⇒ 宿主上的客户端**必须连 EXTERNAL 口** ⇒ 若连 `9092`，broker 在 metadata 里会把地址回写成
`kafka:9092` ⇒ 宿主解析不了 ⇒ **卡在 metadata 阶段**（表现为「超时」而非「连不上」）。

#### ✅ 四面的读数（`LikeKafkaThreeFaceRealTest` · 4 例全绿）

| 面 | 断言内容 | 机制证据 |
|---|---|---|
| **生产面** | `likeAction` ⇒ `KafkaLikeMsg` 真的落到 `like_promotion_topic`；**key 不得为 null**；**L1 字段序**逐个钉住（`msg_id,user_id,target_type,target_id,author_id,state,is_first,created_at`）＋ 每个字段的值 | 真 broker ＋ 真 `KafkaTemplate` |
| **消费面** | 一条消息 ⇒ `like_consume_inbox`（`status=1`）/ `like_record`（`state=1`）/ `like_fact_stream`（`aggregate_id=like-state/{uid}/article/{tid}`）/ `like_outbox_event`（`event_key=first_like:{uid}:article:{tid}`）**四处齐全** | 真 `@KafkaListener` ＋ 真 PG |
| **推送面** | outbox 待发行 ⇒ 被 `LikeHotEventOutboxRelay` 推到 `article-hot-events`；**key = Unix 纳秒不得为 null**；payload **5 字段 ＋ 字段序**逐个钉住（`article_id,type,user_id,timestamp,is_first`） | 真 `@Scheduled` 中继（间隔压到 300ms）＋ 真 broker |
| **幂等面** | 同 `msg_id` 重投 ⇒ `like_record` 仍 **1 行**、`like_outbox_event` 仍 **1 行**、`like_fact_stream.version` **不递增** | 真 inbox 去重 ＋ `uk_user_target` ＋ `uk_event_key` |

> 📌 **机器读数（逐字，取自最终回归日志）**：
> `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.rag.like.messaging.LikeKafkaThreeFaceRealTest`
> 📌 **多次独立真跑读数一致**：`4 / 0 failures / 0 errors / 0 skipped` 逐字相同（耗时随 broker 积压与 PG 冷热变化 ⇒ **耗时不是判据，判据读数才是**）。
> 📌 另：变异脚本自身跑 **5 轮**（基线 ＋ M1 ＋ M2 ＋ M3 ＋ 还原复跑）⇒ 这 4 个面**共真跑 9 次**，每次判据读数相同。
> ⚠️ **消费侧的两条纪律**（本轮踩到 ⇒ 见上面的踩坑记录 A/B）：① **consumer 用 `latest` ＋ 预热探针**（不消化历史积压）② **discovery 实例配置放全局**（devh 的 resolver 是**全局静态**）

#### 🔴 踩坑记录：`target_type` 超 `VARCHAR(32)` ⇒ 消息被**静默丢弃**

| 项 | 内容 |
|---|---|
| 症状 | 消费面与推送面**超时**，但日志里**看不到**「长度超限」这种直观错误（只有 `drop like message with invalid fields` 一类噪音） |
| 真因 | `like_record.target_type` 是 **`VARCHAR(32)`**（`V1__create_like_record.sql:17`，**与 B 侧逐字一致**）⇒ 夹具里 `article-kafka-consume-8400000000008`（**35 字符**）⇒ `insertSeedIfAbsent` 抛 `value too long for type character varying(32)` ⇒ ⇒ 消费事务回滚 ⇒ ⇒ 按 Go 的 **`ForceCommit=true`** 语义**位点前移、消息丢弃、不重投** |
| 为什么难定位 | 消息**被吃掉**了（不是报错，是"消失"）⇒ 现象是「inbox 没行」，而真因在**另一张表的列长**上 |
| 修法 | 夹具的 `target_type` 改用**真实取值** `"article"`（B 侧生产数据同形，7 字符）⇒ 顺带让生产面走到 `likeAction` 的 **`likeOnArticle` 真实主路径**（DB 回源 ＋ 任务进度推送）⇒ 覆盖反而更全 |

---

#### 🔴 踩坑记录（本轮补 Kafka 三面时真的踩到的两条）

| # | 症状 | 真因 | 修法 |
|---|---|---|---|
| **A** | 消费面 / 推送面 / 幂等面**三个一起超时**，而**单独跑那个测试类是绿的** | ⚠️ **topic 上存着对拍探针留下的几百条历史消息** ⇒ 若 consumer 从头部开始 ⇒ listener 必须**先消化全部积压**（每条都要落库 ＋ 事务）⇒ ⇒ 实测 30s 预算被积压吃光<br>📌 **取证**：broker 上 `CURRENT-OFFSET=355` == `LOG-END-OFFSET=355` ⇒ ⇒ **它最终追上了**，只是没赶上 30s ⇒ ⇒ 根因是「**追积压耗时**」，**不是**「消费失败」 | ① consumer 的 `auto-offset-reset` 改 **`latest`**（= `seek` 到 log 末尾，**O(1)** ⇒ **不消化积压**）<br>② ⚠️ `latest` 的代价 = **竞态**（定位完成前发的消息会丢）⇒ ⇒ 加**预热步骤**：先发一条探针消息并**等它真的落进 `like_consume_inbox`** ⇒ ⇒ 「listener 已定位且在消费」有了**运行证据**，之后的测试消息才发得出去<br>⚠️ 预热**不抛异常**（只准备工作）⇒ ⇒ 变异验证里「listener 停 ⇒ **生产面仍绿**」这条判据才成立<br>③ ⚠️ **有界轮询预算从 30s 提到 60s**：连跑多轮 mvn（变异验证一次 5~6 轮）时 broker 有积压与调度抖动 ⇒ 实测 30s **偶发**不足（同一轮变异复跑又绿 ⇒ ⇒ **flaky 而非结构性问题**）⇒ ⇒ 预算按「**最坏一轮的真实耗时**」给 ⇒ ⇒ 宽一点**只让失败更慢**，不会掩盖失败（超时仍判红） |
| **B** | 跨域通知报 `UNAVAILABLE: No servers found for message-rpc`，而**单独跑那个测试类是绿的** | ⚠️ devh 的 gRPC **名字解析器注册进全局静态的 `io.grpc.NameResolverRegistry`** ⇒ ⇒ resolver 持有的是「**第一个创建它的 Spring 上下文**」里的 `DiscoveryClient` bean ⇒ ⇒ 在**别的**上下文里配 `spring.cloud.discovery.client.simple.instances.*` **对该 resolver 无效** ⇒ ⇒ **跨上下文串味** | 把那族键从「测试类的 `@SpringBootTest(properties=...)`」提到 **`application-test.properties`**（**所有**上下文都读得到）⇒ ⇒ 无论哪个上下文先起，resolver 手里的 `DiscoveryClient` 都含 `message-rpc` ⇒ 登记为 **DRIFT-LIKE-018** |

> ⚠️ **这条同时说明**：`like_record.target_type VARCHAR(32)` 是 **L1 契约**（C 侧与 B 侧逐字一致）
> ⇒ ⇒ **不是漂移**，不许为了「让夹具好过」去改列宽。

#### 📌 本节的证据边界

跑的是 **C 侧单侧** —— 证明「C 侧的 Kafka 三面真的通」。
「B 侧此刻真的产出同样字节」属**双侧活体对拍**（`tools/difftest/like/run.sh`），
而那个探针**两侧对称关掉了 listener** ⇒ **不覆盖消费面** ⇒ ⇒ 本节与该探针**互补，不重复**。

### 4.J 🔴 本轮补齐：like → **message 域**的跨域通知**真链路**（`notifyArticleLiked`）—— ✅ **已闭合**

> 📌 这是 §六·6.3 的第 ② 项「`notifyArticleLiked` 分支零覆盖」的闭合（⇒ **DRIFT-LIKE-017 已闭合**）。

#### 🔴 为什么这一面之前是零活体

| 项 | 内容 |
|---|---|
| 现象 | 本域**唯一**的出向跨域调用点（承接表 **R-11**：`client/MessageRpcClient.java`）**从未被真触发** |
| 直接原因 | 此前所有活体读数**刻意**用 `author_id = "0"`（对拍探针要两侧耗时可比）⇒ ⇒ B 侧的 `author_id > 0` 条件恒不成立 ⇒ 分支进不去 |
| ⚠️ 但**真正的障碍不是那个 `author_id`** | 而是**此前没有「能接住跨域调用」的对端** ⇒ 且若去连**别的 lane 的**真 message 域 ⇒ like 域回归会**跨 lane 耦合**（那个容器被重启/改代码 ⇒ 本域回归变红 ⇒ **红灯含义失真**） |

#### ✅ 方案：本域自带**真 netty 假 message-rpc**（不碰 Nacos、不依赖外部容器）

| 环节 | 做法 | 为什么 |
|---|---|---|
| 对端 | 本域测试里起一个**真 netty gRPC server**（`NettyServerBuilder`），实现 `MessageServiceGrpc.MessageServiceImplBase#sendNotification`，逐条记录收到的请求 | ⇒ 走**真 TCP ＋ 真 HTTP/2 ＋ 真 proto 编解码** ⇒ 不是 mock |
| 寻址 | `spring.cloud.discovery.client.simple.instances.message-rpc[0].host/port` ⇒ 把 `message-rpc` 指到 `127.0.0.1:18221` | devh 的 `@GrpcClient("message-rpc")` 走 Spring Cloud 的 `DiscoveryClient` ⇒ **`SimpleDiscoveryClient`** 即可 ⇒ **不碰 Nacos**（`NacosStubConfiguration` 只打桩了 `getNamingService()`，**没**打桩 `DiscoveryClient` ⇒ 两条路互不干扰） |
| 端口 | **钉死** `18221`（like 探针段 18211-18219 之外） | devh 客户端**启动即连** ⇒ 假 server 必须**先于** Spring 上下文就绪 ⇒ 端口必须提前可知 ⇒ 假 server 在**类加载**时就起 |

> 📌 **运行证据**（不是推断）：`DiscoveryClientNameResolver : Done updating server list for message-rpc`
> ⇒ ⇒ **devh 真的经 discovery 解析到了 `message-rpc`** ⇒ 寻址链成立。

#### ✅ 四个用例的读数（`LikeMessageRpcCrossDomainRealTest` · 4 例全绿）

| # | 用例 | 断言 | 期望值出处 |
|---|---|---|---|
| ① | **正例** | 通知**真的发出去**，且 **8 个字段逐个** == B 侧规格：`recipient_ids=[author_id]` · `broadcast=false` · `sender_id=liker` · `sender_role=USER` · `kind=ARTICLE_LIKED` · `title="文章收获新点赞"` · `content="用户 %d 点赞了你的文章。"` · `extra` 四键（`target_type`/`target_id`/`liker_id`/`author_id`，值全为字符串） | **B 侧** `like-rpc/.../client/MessageRpcClient.java:63-82`（**逐字**） |
| ② | 反例：`author_id = 0` | **不得**发出任何通知 | B 侧条件 `author_id > 0` |
| ③ | 反例：`user_id == author_id` | **不得**发出任何通知 | B 侧条件 `user_id != author_id` |
| ④ | **失败语义** | 对端**故意报错** ⇒ `likeAction` **仍** `success=true` ＋ `message="action success"` | B 侧：Go `likeactionlogic.go:395-397` 只 `logger.LogBusinessErr` ⇒ 不影响点赞结果 |

> 📌 **①的断言逐字对照**：C 侧 `client/MessageRpcClient.java` 与 B 侧同名文件**逐字一致**
> ⇒ ⇒ 断言的是「**C 侧抄出来的请求** == **B 侧会发的请求**」，且**经过真 gRPC 线路送达**
> ⇒ ⇒ 这比源码级对照强一档：**证的是「真的发出去的内容」**，不是「代码长得像」。

> 📌 **机器读数（逐字，取自最终回归日志）**：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.rag.like.service.LikeMessageRpcCrossDomainRealTest`

#### 🔴 变异验证（证明这 4 条断言**有牙齿**）

| 步骤 | 手法 | 读数 |
|---|---|---|
| 变异 | `MessageRpcClient#sendArticleLiked` 方法体开头插入 `if (true) { return false; }`（**不发请求**，片段命中 **1** 次 ⇒ 唯一性前置断言通过） | — |
| 变异后 | `Tests run: 4, Failures: 1, Errors: 0, Skipped: 0` ⇒ **只有①红**，②③④仍绿 | ✅ **变异精确**（只影响「真的发出去了」这一维） |
| 失败断言原文 | `LikeMessageRpcCrossDomainRealTest.notificationIsSentWithGoAlignedFields:120 [跨域通知必须在 15000ms 内真的发出去]` | — |
| 还原后 | **EXIT=0**（`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`） | ✅ **红确实是这次变异造成的** |
| 残留自检 | 变异片段命中 **0** 次 | ✅ 零残留 |

#### ✅ 本节**已闭合** —— **DRIFT-LIKE-019**（曾一度被 surefire 静默跳过，已修）

| 项 | 读数 |
|---|---|
| ✅ **单跑** | 4 例全绿（`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`） |
| ✅ **变异验证** | 变异后只有**正例**红（`Failures: 1`，另 3 例仍绿 ⇒ **变异精确**）＋ 还原后 **EXIT=0** |
| 🔴 **曾经的病征** | 同一条命令两次全量回归 ⇒ 一次 like=**110**（该类跑了）⇒ 一次 like=**106**（**该类没跑**）⇒ 而 **BUILD SUCCESS ＋ 0 ERROR ＋ 0 Skipped** ⇒ ⇒ **「不执行」比「失败」更危险**（连红灯都不给你） |
| **病根** | 该类的**静态块**在 surefire 的**类扫描阶段**起 netty server ⇒ ⇒ 扫描期一旦出问题 ⇒ 该类被**静默丢弃**（不报错、不执行、不计数） |
| ✅ **修法** | 把 server 启动从**静态块**挪到 **`@BeforeAll`** ⇒ ⇒ 扫描期**无副作用** ⇒ 必定被发现；且端口被占时异常发生在**测试期** ⇒ 变成**可见的 ERROR** |
| ✅ **修后验证** | **两次独立全量回归逐字一致**：`Tests run: 110, Failures: 0, Errors: 0, Skipped: 0` ⇒ ⇒ 跨域那 4 例**两次都被执行**（`Tests run: 4, Failures: 0, …`）⇒ ⇒ **闭合** |
#### 🟠 本节**尚未闭合** —— **DRIFT-LIKE-019**（必须先说清，不含糊）

| 项 | 读数 |
|---|---|
| ✅ **单跑** | 4 例全绿（`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`） |
| ✅ **变异验证** | 变异后只有**正例**红（`Failures: 1`，另 3 例仍绿 ⇒ **变异精确**）＋ 还原后 **EXIT=0** |
| ✅ **一次全量回归** | like 域汇总 **110** 例 ⇒ 该类**跑了**且 4/4 绿 |
| 🔴 **另一次全量回归** | like 域汇总 **106** 例 ⇒ 该类**没跑** ⇒ 而 **BUILD SUCCESS ＋ 0 ERROR ＋ 0 Skipped** |
已覆盖且两次全量回归均执行（真 netty 对端 ＋ 真 discovery ＋ 逐字段 == B 侧规格 ⇒ §四·4.J）

#### 📌 本节的证据边界（AGENTS §3.3.3）

* 跑的是 **C 侧单侧** —— 对端是**本域自带的假 message-rpc**。
* ⇒ 它**不**证明「**B 侧 message 域此刻真的收到了**」⇒ 那需要连 B 侧的 `Sea-message-rpc`
  （**跨 lane 依赖**，会让本域回归变脆）⇒ ⇒ 本类**不冒充**该证据。
* ⇒ ⇒ 本类的命题是：「**like 域这条跨域调用，真的按 B 侧的规格把请求发出去了**」——
  这**正是 like 域自己的职责边界**（R-11）。

---

## 五、语义漂移清单（§11.5）

| Drift ID | 位置 | B 行为 | C 行为 | 原因 | 影响面 | 严重度 | 状态 |
|---|---|---|---|---|---|---|---|
| **DRIFT-LIKE-001** | B `like-api/.../auth/LikeJwtAuthFilter.java:140-156`<br>C `interceptor/LikeJwtAuthInterceptor.java:133` | 验签在 **Servlet Filter**（**3s 预算外**）⇒ 验签耗时**不计入** 3s | 验签在 **HandlerInterceptor**（**3s 预算内**）⇒ 验签耗时**计入** 3s | **D7 包压平的文件清单锁定**（like 域**无 `filter/` 目录**）⇒ **不是** Boot 4→3.5 的框架差异 | **仅耗时分布面**；**401 ＋ 空 body 的响应形状逐条相同**；两侧**都是 1 次验签** | 🟡 **P2** | **INTENTIONALLY_CHANGED**（已自登记 ＋ 本台账） |
| **DRIFT-LIKE-002** | `application.yml` | api `18887` ＋ rpc 观测 `9098` ＋ gRPC `8082`；应用名 `likecenter` / `like-rpc` | HTTP `8896` ＋ gRPC `9005`；应用名 `like-service` | **D1 合并裁决**（映射表 §4 钉死） | 端口 ＋ 注册面 | 🟢 **已签字** | INTENTIONALLY_CHANGED |
| **DRIFT-LIKE-003** | `wiring/NacosRegistration.java:63-64` | group **硬编码** `DEFAULT_GROUP` | group 读 `${spring.cloud.nacos.discovery.group:DEFAULT_GROUP}` | 采 message 域 house style（可配置化） | **生效值相同** ⇒ 只多一个配置面 | 🟢 低 | INTENTIONALLY_CHANGED |
| **DRIFT-LIKE-004** | `application.yml` actuator 段 | api `health,info,prometheus` ＋ `show-details:always`<br>rpc `health,info,prometheus,metrics` ＋ `show-details:when_authorized` | 取**并集** `health,info,prometheus,metrics` ＋ 取**更严**的 `when_authorized` | **D1 合并裁决** | Actuator 暴露面 ＋ 健康详情可见性 | 🟢 **已签字** | INTENTIONALLY_CHANGED |
| **DRIFT-LIKE-005** | `application.yml` otel 段 | rpc `otel.service.name: like-rpc` | `like-service`（**两套拼法都写**） | **D1 合并裁决** | trace 服务名 | 🟢 **已签字** | INTENTIONALLY_CHANGED |
| **DRIFT-LIKE-006** | `controller/LikeController.java:136,144,155,163,174,183` | **6/6** `@RequestBody(required = false)` ＋ 手写必填校验器 | **5** 个 `@Valid @RequestBody`（required 默认 true）＋ **1** 个 `required = false` | **A10 禁手写校验器** ＋ **D5 DTO 改 immutable record** ⇒ 必填校验改 `@NotNull` | ⚠️ **wire 层面完全等价**：<br>· B 侧「必填缺失」→ `GoRequiredFieldException` → `okEnvelope(serverCommon(), serverCommonMsg())` = **5001** ＋ `"系统内部错误"` ＋ HTTP 200<br>· C 侧 → `MethodArgumentNotValidException` → 同一出口<br>· B 侧「空 body」→ `req=null` → NPE → 兜底分支 → 同出口<br>· C 侧「空 body」→ `HttpMessageNotReadableException` → 同出口<br>⇒ ⇒ **四个失败出口的 code/msg/status 逐条相同** | 🟢 **等价**（已用真 Tomcat 断言 ＋ B 侧源码逐条对照） | **INTENTIONALLY_CHANGED**（表达层，wire 等价） |
| **DRIFT-LIKE-007** | `proto/src/main/proto/like.proto:5` | `java_package = "com.rag.like.like.proto"` | `java_package = "com.rag.proto.like"` | **D7 proto 集中化**的机械后果（模块坐标 `com.rag:proto`） | **wire 零影响**（`java_package` 不参与方法路径推导） | 🟢 **已签字** | INTENTIONALLY_CHANGED（`DEV-LIKE-PROTO-002`，状态「已落地待确认」） |
| **DRIFT-LIKE-008** | `wiring/LikeFactProcessLogging.java:65` | `LikeFactProcessLogging` 是**骨架占位**（只写「计划建 3 份 ProcessLogger」） | 落地 4 份独立进程级 logger ＋ `like.fact.process.{environment,instance-id,version}` 三元组 | B 侧**没有真值** ⇒ C 侧必须给 `ProcessMetadata#validate()` 提供 **40 位小写 hex** 的 version ⇒ **自造了一个** | 表达层（进程身份标识） | 🟢 低 | INTENTIONALLY_CHANGED（**待收口登记** ⇒ `vs-common.md` §5·5-4） |
| **DRIFT-LIKE-009** | `metrics/LikeApiMetrics.java:88` | `InitMetrics()` **从未被调用** ⇒ `like_api_reject_total` **不会**出现在 `/metrics` | Counter 正常注册 ⇒ **会**出现 | 机制被真正激活 | **指标可见性面**（C 侧多一个可见指标） | 🟢 低 | INTENTIONALLY_CHANGED（**待收口登记** ⇒ `vs-common.md` §5·5-5） |
| **DRIFT-LIKE-010** | `/actuator/prometheus`（原记：生产形态上下文） | like-rpc 有 web 载体 ⇒ `/actuator/prometheus` **有出口** | ✅ **生产形态可用**：`PrometheusMeterRegistry` 存在 ＋ 端点 **200** ＋ 真流量打出 `like_api_reject_total{...}` | ⚠️ **原归因（Boot 3.5 装配顺序 / 生产形态无 registry）两条都错** ⇒ 真因是 **Boot 测试框架的全局禁用**（`ObservabilityContextCustomizerFactory`）⇒ **与 C↔B 行为无关** | **无**（**不是漂移**） | 🟢 无 | ✅ **已撤销（假阳性 · 2026-11-03 复核）** ⇒ 详见 §四·4.F |
| **DRIFT-LIKE-011** | `docs/Mapping_Table/like/like.md:373` | — | — | **映射表自报「30 个常量」**，但它自己列出的值域恰好 **29** 个；**B 侧 `LikeErrorCode` 类注释明写「共 29 个……写的『30 个』是笔误」**；**C 侧代码也是 29** | **文档自报数不一致**（不影响运行行为） | 🟡 **P2** | **只登记**（AGENTS §2.3「禁止修改 Mapping_Table」）⇒ `vs-common.md` §5·5-2 |
| **DRIFT-LIKE-012** | 测试配置 `grpc.server.port=-1` | netty gRPC server 真启动 | ✅ **已补真 netty server 用例**（`grpc.server.port=0` ＋ `grpc.server.address=127.0.0.1`）⇒ server 真起、真 bind、真收发 | **覆盖缺口已闭合** | **无**（**不是漂移**） | 🟢 无 | ✅ **已闭合**（`LikeGrpcNettyServerRealTest` 5 例 ⇒ §四·4.H） |
| **DRIFT-LIKE-013** | `messaging/LikeUpdateKafkaListener.java` ＋ `messaging/LikeHotEventOutboxRelay.java` | 消费落库 ＋ 发件箱中继 | ✅ **已补真 Kafka 三面**：真 broker（`127.0.0.1:49992`）＋ 真 `@KafkaListener` ＋ 真 `@Scheduled` 中继 ⇒ **生产 / 消费 / 推送 / 幂等 四面全跑通** | 本轮无 Kafka 容器（**已补** Sea-kafka `apache/kafka:3.7.2`） | **覆盖缺口**（非行为漂移） | 🟠 中 | ✅ **已闭合**（`LikeKafkaThreeFaceRealTest` 4 例 ＋ `tools/difftest/like/mutate-kafka-3face.sh` M1~M3 ⇒ §四·4.I ＋ §八·8.6）<br>⚠️ **原归因写错了一处**：不是「`auto-startup=false` 让 listener 没起」，而是**那键根本没有消费点**（详见 **DRIFT-LIKE-016**）⇒ 真正原因是**无 broker 可连** |
| **DRIFT-LIKE-014** | `service/like/pom.xml`（`io.grpc:grpc-inprocess` 的 scope） | B 侧 `service/like/like-rpc/pom.xml:313-344` **明文要求**：`🔴 **scope 必须是 compile（默认），不能是 test**`（2026-10-26 真启动实测修正） | ❌ **C 侧写成 `<scope>test</scope>`** ⇒ Maven「最近者优先」压过 devh starter 的传递 compile 依赖 ⇒ **运行时 classpath 上整个 artifact 消失** ⇒ devh 的 channel factory 链接 `InProcessChannelBuilder` ⇒ **`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`** ⇒ **真 fat jar 起不来**（`MessageRpcClient` Bean 创建失败 ⇒ 整个服务起不来，硬失败） | **与 B 侧逐字相反**（D16：B 侧是基准）⇒ ⚠️ **测试全绿也看不见**（测试跑在 test classpath 上 ⇒ grpc-inprocess 本来就在）⇒ AGENTS §3.3.3「**跑绿 ≠ 本来就绿**」的教科书形态 | 🔴 **服务起不来**（生产形态**完全不可用**，不是可观察面差异） | 🔴 **严重** | ✅ **已修**（`service/like/pom.xml` 去掉 `<scope>test</scope>` ＋ 补 B 侧同款根因注释）⇒ **活体证据**：真 fat jar 起成功（`Tomcat started on port 18311` ＋ `gRPC Server started … port: 18312` ＋ `Started LikeServiceApplication in 2.694 seconds`，**无** `NoClassDefFoundError`） |
| **DRIFT-LIKE-016** | `service/like/src/test/resources/application-test.properties`（`spring.kafka.listener.auto-startup=false`） | B 侧**无对应配置**（Go 侧是 `mq.yaml` 的 `Kafka.Consumers: 8`，**没有 autoStartup 开关**） | ❌ 该键**在本仓没有消费点** —— 应用在 `LikeServiceApplication#kafkaListenerContainerFactory`（第 88-97 行）里**自己 `new`** 了 `ConcurrentKafkaListenerContainerFactory` ⇒ Boot 的 `ConcurrentKafkaListenerContainerFactoryConfigurer` **不参与** ⇒ `spring.kafka.listener.*` **一整族键全部不生效** ⇒ `autoStartup` 取框架默认值 **true** ⇒ **listener 其实一直都在起**（只是 broker 不可达 ⇒ 一直重试） | ⚠️ **AGENTS §3.3.1 的教科书形态**：配置里写了 ≠ 机制生效；而台账旧结论**把归因写反了**（说「listener 没起」⇒ 其实它一直起着，零覆盖的真因是**无 broker**）⇒ ⇒ **一条错误归因会把下一个人引去改一个不存在的开关** | **不影响生产行为**（生产面 `application.yml` 无此键）⇒ 只影响**测试配置面 ＋ 台账归因** | 🟡 **P2**（文档/归因面） | ✅ **已修**（删除该死键 ＋ 注释改成如实描述 ⇒ §四·4.I）＋ **区分性实验**已配（`mutate-kafka-3face.sh` M1：listener 一停 ⇒ 消费面/推送面/幂等面立刻转红 ⇒ **反证该键从未生效过**） |
| **DRIFT-LIKE-017** | `client/MessageRpcClient.java` ＋ `domain/service/LikeDomainService.java` 的 `notifyArticleLiked` 分支 | 点赞成功后给作者发通知（Go `notifyArticleLiked`：recipient_ids=[author_id] / broadcast=false / sender_id=liker / kind=ARTICLE_LIKED / title / content / extra 四键） | ❌ **零活体**：此前所有活体读数**刻意**用 `author_id="0"`（对拍探针要两侧耗时可比）⇒ ⇒ 跨域调用路径**从未被真触发** ⇒ ⇒ 本域唯一的出向跨域调用点**没有运行证据** | ⚠️ **此前缺一个「能接住跨域调用」的对端** ⇒ 且若去连**别的 lane 的**真 message 域 ⇒ 本域回归会**跨 lane 耦合**（红灯含义失真） | **覆盖缺口**（非行为漂移） | 🟠 中 | ✅ **已闭合**（本域自带**真 netty 假 message-rpc** ＋ 真 discovery ⇒ 通知真发出、**逐字段** == B 侧规格、失败语义不误伤点赞结果 ⇒ §四·4.J） |
| **DRIFT-LIKE-018** | `service/like/src/test/resources/application-test.properties`（`spring.cloud.discovery.client.simple.instances.message-rpc[*]` 的**放置位置**） | B 侧**无对应配置**（Go 侧 `zrpc.MustNewClient` 直接按 etcd key 寻址，不经服务发现客户端配置面） | ❌ 原本写在**跨域测试类的 `@SpringBootTest(properties=...)`** 里 ⇒ ⇒ **全量回归时失效**，**单跑那个类是绿的** ⇒ ⇒ 现象：跨域通知报 `UNAVAILABLE: No servers found for message-rpc` | ⚠️ devh 的 gRPC **名字解析器注册进全局静态的 `io.grpc.NameResolverRegistry`** ⇒ ⇒ resolver 持有的是「**第一个创建它的 Spring 上下文**」里的 `DiscoveryClient` bean ⇒ ⇒ **别的上下文里再配 discovery 实例，对该 resolver 无效** ⇒ ⇒ **跨上下文串味**（单跑绿 / 全量红）⇒ ⚠️ 与「**单测绿 ≠ 全量绿**」是同族但**更隐蔽**（连「单跑那个类」都是绿的） | **不影响生产行为**（生产面 `application.yml` 无此键）⇒ 只影响**测试配置面** | 🟡 **P2**（测试面） | ✅ **已修**（把那族键提到 `application-test.properties` ⇒ **所有**上下文都读得到）⇒ **证据**：全量回归 **EXIT=0**（改前该用例红：`No servers found for message-rpc`） |
| **DRIFT-LIKE-019** | `service/like/src/test/java/com/rag/like/service/LikeMessageRpcCrossDomainRealTest.java` ＋ `support/FakeMessageRpcServer.java`（**类带静态副作用**：静态块起 netty server） | — | 🟠 **曾一度在一次全量回归里被 surefire「静默跳过」**：同一条命令两次全量回归 ⇒ 一次 like=**110**（该类跑了，4/4 绿）⇒ 一次 like=**106**（**该类没跑**）⇒ ⇒ **BUILD SUCCESS 且 0 ERROR、0 Skipped** | ⚠️ **病根**：该类的**静态块**在 surefire 的**类扫描阶段**起 netty server ⇒ ⇒ 扫描期一旦出问题 ⇒ 该类被**静默丢弃**（不报错、不执行、不计数）⇒ ⇒ **「不执行」比「失败」更危险** | **回归覆盖面**（4 条断言一度**不稳定地在回归里执行**）⇒ ⚠️ **不是**行为漂移 | 🟠 中 | ✅ **已闭合**（把 server 启动从**静态块**挪到 **`@BeforeAll`** ⇒ 扫描期无副作用 ⇒ 必定被发现；起不来时变成**可见的 ERROR**）⇒ **修后验证**：**两次独立全量回归逐字一致**（均 like=**110**，跨域 4 例均被执行）⇒ §四·4.J |
| **DRIFT-LIKE-015** | 场景轮 F · **超时失败形态**（`tools/difftest/like/` 实测） | B 侧 **api→rpc 两跳** ⇒ 外层 API 预算 **2s** 先到 ⇒ 业务码 **1015** @**2098ms** | C 侧 **D2 域内直连**（D1 合并成单进程）⇒ 外层 HTTP 预算 **3s** + Redis 3s 命令超时后**走 DB 降级** ⇒ HTTP **503** · 无信封 @**3091ms** | **D1（api+rpc 合并）＋ D2（域内直连）的拓扑产物** —— ⚠️ **两侧都观测的是「外层预算」**，**不是领域语义** ⇒ 码值差异**不由错误映射决定，而由失败形态决定** | **客户端观测到的码/形态不同**（业务**结果**两侧一致：都没挂死，unpause 后都回 200） | 🟡 **P2**（形态面） | **INTENTIONALLY_CHANGED**（**拓扑产物**，已在 `tools/difftest/like/README.md` 的「已知现象 1」登记，**不计失败**）⇒ §八·8.3 |

### 5.1 本轮**修掉**的缺陷（不是漂移，是 C 侧实现期抓出的问题）

| # | 缺陷 | 影响 | 状态 |
|---|---|---|---|
| 1 | `@JsonDeserialize` 只写在 record 组件上 | 反序列化器未生效 ⇒ `target_ids` 接住 bool ⇒ **与 B 侧 `errTypeMismatch` 不符** | ✅ 已修（两处都写）**＋ 机制断言已按 §四·4.E 更正** |
| 2 | `LikeAuthorityEndpointConfig` 的 `@ConditionalOnProperty(prefix = "like.fact.authority")` | 绑不上 `LikeRpcProperties.Authority`（前缀实为 `like.authority`）⇒ 该 Bean 永远不装配 | ✅ 已修 |
| 3 | 入口类 `@EnableConfigurationProperties` 漏 `LikeKafkaTopics` | D7 包压平后漏注册 ⇒ **应用起不来** | ✅ 已修 |
| 4 | `application.yml` 缺 5 段（`like:` / `like-api:` / `observability` / `server.tomcat.*` / `spring.flyway`） | 默认行为全丢（TTL / topic / 3s 预算 / 容器旋钮 / 迁移账本） | ✅ 已修（B 侧两份 yml 合并，逐段标 B 出处） |
| 5 | 迁移脚本 C 侧脚注丢了 `--` 前缀 | 第 56 行变裸 SQL ⇒ `syntax error at or near "service"` ⇒ **启动失败** | ✅ 已修 |
| 6 | `like-rpc_action_total`（连字符） | 不符合 Prometheus 指标名正则 ⇒ **该指标不会出现在 `/metrics`** | ✅ 已修（按 B 侧写下划线版） |
| 7 | `spring.kafka.producer.acks` 未显式配（B 侧 `like-rpc/application.yml:207` 显式 `acks: 1`） | 生效值虽同（Kafka 3.x 默认 1），但那是**依赖客户端默认值** | ✅ 已修（显式补齐，零行为差） |
| 8 | 3 个 DTO 的 javadoc 里一条**被证伪的机制断言** | AGENTS §3.3.2 高危措辞 | ✅ 已更正 ＋ 补**区分性实验**（5 例，见 §四·4.E） |
| 9 | **`@KafkaListener` 缺装配层断言**（AGENTS §3.3 明文要求「用 `@KafkaListener` 必配装配层测试断言 listener 真进 registry」） | 原先只有「类上有注解」，**没有**「端点真进 registry」的证据 ⇒ 违反 AGENTS §3.3 | ✅ 已修（`LikeServiceApplicationTests#kafkaListenerIsRegistered`）⇒ 断言 id / topic / group / **并发度 8**（并发度 8 本身即证明域内自建工厂被用上） |
| 10 | **404 的 Content-Type 断言只到 `startsWith("text/plain")`** | 未锁住字符集与分隔符 ⇒ 共享层 javadoc 引 Tomcat 11.0.24 字节码、C 侧跑 10.1.52 ⇒ 证据链与运行版本不匹配 | ✅ 已修（收紧为「去空格后逐字节 = `text/plain;charset=utf-8`」＋ body 精确等于 `"404 page not found\n"`）⇒ 真 Tomcat 实测通过 |
| 11 | **DRIFT-LIKE-010 是假阳性**（把「Boot 测试框架的全局禁用」误判成「Boot 3.5 装配顺序」＋「生产形态无 registry」） | 台账会给出**错误归因** ⇒ 读者会去 `global` 收口域改一个**并不存在**的缺陷 | ✅ 已撤销（§四·4.F）＋ 生产代码零改动（`@AutoConfigureObservability(metrics=true, tracing=false)`）＋ 补 3 例真证据 |
| 12 | **Flyway 版本只被「间接」覆盖** | 「迁移跑通」**证不了版本**（11.7.2 与 11.14.1 在本域 6 个脚本上都能跑通） | ✅ 已补**显式**断言（`LikeFlywayRuntimeVersionTest` 4 例 ⇒ §四·4.G） |
| 13 | **netty gRPC server 从未真启动过**（测试一律 `grpc.server.port=-1`） | 传输层、TCP bind、HTTP/2 帧编解码、trailer 线上序列化**全部零覆盖** | ✅ 已补（`LikeGrpcNettyServerRealTest` 5 例 ⇒ §四·4.H） |
| 15 | **`spring.kafka.listener.auto-startup=false` 是**死配置**（**DRIFT-LIKE-016**）⇒ 台账把「消费面零覆盖」的归因**写反了** | 该键**没有消费点**（域内自建 `ConcurrentKafkaListenerContainerFactory` ⇒ Boot configurer 不参与）⇒ `autoStartup` 一直是框架默认的 **true** ⇒ **listener 一直都在起**；零覆盖的真因是**无 broker 可连** | ✅ 已修（删掉死键 ＋ 注释改为如实描述）＋ 补**区分性实验**（M1 ⇒ 见 §八·8.6） |
| 14 | 🔴 **`grpc-inprocess` 声明成 `test` scope ⇒ 真 fat jar 起不来**（**DRIFT-LIKE-014**） | **生产形态完全不可用**（`NoClassDefFoundError` ⇒ `MessageRpcClient` Bean 创建失败 ⇒ 服务起不来）⇒ 而 **102/102 测试全绿**（**当时的读数**；本轮加完 Kafka 三面后为 **106/106**）（缺陷藏在生产 classpath 上） | ✅ 已修（`service/like/pom.xml` 去 `test` scope ＋ B 侧同款根因注释）⇒ **活体证据**：真 fat jar 起成功（Tomcat + gRPC server 都起、无 `NoClassDefFoundError`） |

---

## 六、完成度统计（§11.6 · **不用文件数/行数代表完成度**）

### 6.1 维度一：职责承接（**57 条 · 机器复算**）

| 档位 | 数量 | 明细 |
|---|---|---|
| ✅ **已实现且已验证**（PASS） | **54** | 见 §2.5 汇总表 |
| ⚠️ **已实现但未验证**（NOT_VERIFIED） | **1** | F-02（权威读 Servlet 暴露面 —— **B 侧是骨架，无真值可比**）<br>~~R-17（发件箱中继推送面）~~ · ~~F-04（Kafka 消费面）~~ **本轮闭合**（§四·4.I） |
| ❌ **尚未实现**（NOT_IMPLEMENTED） | **0** | — |
| 🔴 **明确存在漂移**（FAIL） | **0** | 无「C 侧少承接 B 侧职责」的情形 |
| 🏷 **已批准的主动变更**（INTENTIONALLY_CHANGED） | **2** | A-17（验签位置）· F-03（进程身份三元组） |
| **合计** | **57** | 与 §2.5 机器复算结果**一致** |

### 6.2 维度二：语义漂移清单（**19 条登记项 · 独立于上表**）

| 档位 | 数量 | 明细 |
|---|---|---|
| 🏷 **已批准的主动变更** | **10** | DRIFT-LIKE-001 ~ 009 ＋ **015**（超时形态 = D1/D2 的拓扑产物，**观测的是外层预算、不是领域语义**） |
| ✅ **已撤销的假阳性**（**不是漂移**） | **1** | **DRIFT-LIKE-010**（`/actuator/prometheus` 404）—— 原归因（Boot 3.5 装配顺序）**两条都错** ⇒ 真因 = **Boot 测试框架的全局禁用** ⇒ 与 C↔B 行为无关 ⇒ **已撤销**（§四·4.F） |
| ✅ **已闭合的覆盖缺口**（**不是漂移**） | **4** | **DRIFT-LIKE-012**（netty gRPC server 真启动）⇒ §四·4.H ｜ **DRIFT-LIKE-013**（Kafka 消费/推送面）⇒ §四·4.I ｜ **DRIFT-LIKE-017**（跨域通知面）⇒ §四·4.J ｜ **DRIFT-LIKE-019**（跨域面被 surefire 静默跳过）⇒ §四·4.J |
| ✅ **本轮发现并已修的 C 侧漂移** | **1** | **DRIFT-LIKE-014**（`grpc-inprocess` 的 scope 与 B 侧相反 ⇒ 真 fat jar 起不来）⇒ **已修 + 活体验证**（§五·5.1 #14） |
| 🔧 **测试配置面的坑**（**不影响生产行为**，但**推翻了旧归因 / 造成单跑绿全量红**） | **2** | **DRIFT-LIKE-016**（`spring.kafka.listener.auto-startup=false` **没有消费点** ⇒ listener 其实一直都在起）⇒ 已修 ＋ 区分性实验 ｜ **DRIFT-LIKE-018**（discovery 实例配置**放错位置** ⇒ devh 的**全局静态** resolver 串味）⇒ 已修 ⇒ §四·4.I ＋ §八·8.6 |
| 🟡 **文档/台账不一致（不影响运行）** | **1** | DRIFT-LIKE-011（映射表自报数 30 vs 实际 29） |
| 🟠 **未闭合（测试执行不稳定）** | **0** | ~~DRIFT-LIKE-019~~（跨域 4 例曾被 surefire **静默跳过**）**本轮已闭合**（§四·4.J）⇒ 已修 ＋ **两次全量回归逐字一致** |
| ⚠️ **仍开放的覆盖缺口**（NOT_VERIFIED，非行为漂移） | **0** | ~~DRIFT-LIKE-013~~（Kafka 三面）**本轮闭合**（§四·4.I）｜ ~~DRIFT-LIKE-019~~（跨域面）**本轮闭合**（§四·4.J） |
| **登记项合计** | **19** | 10 已批准/表达层 ＋ 1 已撤销假阳性 ＋ **4 已闭合覆盖缺口**（012/013/017/019）＋ 1 已修 C 侧漂移 ＋ 2 测试配置坑 ＋ 1 文档不一致 ＋ **0 未闭合** = **19**<br>⚠️ **本表此前漏计 DRIFT-LIKE-012**（已闭合项没有单列一档 ⇒ 分类只覆盖 14/15）⇒ 本轮补上 ⇒ **零漏项** |

> ⚠️ **两个维度的计数不相加** —— 6.1 数的是「职责承接」，6.2 数的是「差异」；
> 同一条目可以同时出现在两边（例如 R-17 既在 6.1 的 NOT_VERIFIED，也在 6.2 的 DRIFT-LIKE-013）。
> ⇒ **按 AGENTS §3.3 的纪律，汇总表只报「分档计数」，不报「总数」**（避免被读成「完成度 %」）。

### 6.3 ⚠️ **表 ≠ 命题**（AGENTS §3.3 纪律）

> **上表的「PASS 54」是**逐条承接表的计数**，**不是**「行为与 B 侧完全等价」的证明。
> ⇒ **表外的剩余缺口**（**不得**因为表内没有 `NOT_IMPLEMENTED` 就当成已闭合）：
> ① ~~**Kafka 生产/消费/推送三面**零活体~~ ⇒ **本轮已划掉**（真 broker ＋ 真 listener ＋ 真中继 ⇒ 四面跑通 ⇒ §四·4.I）；
> ② ~~**`notifyArticleLiked` 分支**零覆盖~~ ⇒ **本轮已划掉**（真 netty 假 message-rpc ＋ 真 discovery ⇒ 通知真发出且逐字段 == B 侧规格 ⇒ §四·4.J；⚠️ 对拍探针仍用 `author_id="0"`，那是**探针的取舍**，不是覆盖缺口）；
> ③ **fact-dispatch / DC 投递链路**零覆盖（B 侧探针的阶段 B 未移植）；
> ④ **gRPC wire 层无 Go 锚** ⇒ 只算「双侧一致」，**不计入三方**；
> ⑤ **`getusertotallike` 的 DB 回源分支**只在校验 E60 注入期间被碰到（正常轮走缓存命中）；
> ⑥ **超时场景的失败形态差异没有字节级门禁**（只记录、不分判 ⇒ DRIFT-LIKE-015）。
>
> ✅ **本轮已从这张表里划掉的五项**（原 ①③⑥ ＋ Kafka 三面 ＋ `notifyArticleLiked`）：
> · **双侧活体对拍** ⇒ 已建 `tools/difftest/like/`，**8 条 HTTP 三方逐字节 ＋ 6 条 gRPC wire 双边逐字节**，两轮复跑一致（§八）；
> · **Kafka 三面** ⇒ 已补 `LikeKafkaThreeFaceRealTest` 4 例（真 broker ＋ 真 `@KafkaListener` ＋ 真 `@Scheduled` 中继 ＋ 真 PG）＋ 变异验证 M1~M3（§四·4.I ＋ §八·8.6）；
> · **netty gRPC server 真启动** ⇒ 已补 `LikeGrpcNettyServerRealTest` 5 例 ＋ 真 fat jar 活体（§四·4.H）；
> · **Flyway 11.14.1 覆盖 BOM** ⇒ 已由「间接」升级为「显式」（§四·4.G）。
>
> ✅ **本轮已更正的两项**：
> · **`/actuator/prometheus` 不是缺口，是假阳性**（§四·4.F）；
> · **真 fat jar 起不来**（DRIFT-LIKE-014）⇒ 已修 ＋ 活体验证（§五·5.1 #14）。
>
> ✅ **本轮补上的「变异腿」**：`tools/difftest/like/mutate-parity.sh`（M1~M6，每条都要求「目标用例红 **且** 对照用例仍绿」⇒ 证明判据有牙齿）⇒ §八·8.4。

---

## 七、遗留缺口与待办

### 7.1 本轮明确留下的缺口（**不写成已闭环**）

| # | 项 | 为什么未验 | 什么时候能验 |
|---|---|---|---|
| 7-1 | ~~**双侧活体对拍**~~ | — | ✅ **本轮已闭合**：`tools/difftest/like/`（13 阶段）⇒ **8 条 HTTP 三方逐字节 ＋ 6 条 gRPC wire 双边逐字节**，**两轮独立复跑读数一致**，退出码 **0** ⇒ §八 |
| 7-2 | ~~**Kafka 三面**（生产 / 消费 / outbox 推送）~~ | — | ✅ **本轮已闭合**：起 Sea-kafka `apache/kafka:3.7.2`（宿主口 `127.0.0.1:49992`）⇒ `LikeKafkaThreeFaceRealTest` **4 例全绿**（生产 / 消费 / 推送 / 幂等）＋ `tools/difftest/like/mutate-kafka-3face.sh` M1~M3 变异验证 ⇒ §四·4.I ＋ §八·8.6 |
| 7-3 | ~~**netty gRPC server 真启动**~~ | — | ✅ **本轮已闭合**：`LikeGrpcNettyServerRealTest` 5 例（真 TCP 端口 ＋ 真 netty 工厂 ＋ 裸 socket 可连 ＋ 6 个 rpc 方法真发 ＋ 错误出口过 HTTP/2 线上序列化）＋ **真 fat jar 的 gRPC server 真启动**（`gRPC Server started … port: 18312`）⇒ §四·4.H |
| 7-4 | ~~**并发 / 超时 / 外部依赖失败 / 恢复**四类场景轮~~ | — | ✅ **本轮已闭合**：探针阶段 C/D/E/F（依赖失败 / 恢复 / 并发 / 超时），**两侧的码值 ＋ 耗时都记**，并逐条登记 ⇒ §八·8.3（超时那条的形态差异登记为 **DRIFT-LIKE-015**，**不计失败**） |
| 7-5 | ~~**`/actuator/prometheus` 可用性**~~ | — | ✅ **本轮已查明**：**不是缺口，是假阳性** —— 真因 = Boot 测试框架的全局禁用（`ObservabilityContextCustomizerFactory`）⇒ 生产形态**可用** ⇒ §四·4.F |
| 7-6 | ~~**Flyway 11.14.1 覆盖 BOM 11.7.2**~~ | — | ✅ **本轮已由「间接」升级为「显式」**：`LikeFlywayRuntimeVersionTest` 4 例 ⇒ §四·4.G |

### 7.2 转派**「合并收口执行者」**的（**本域只登记**）

| # | 项 | 建议修法 | 严重度 |
|---|---|---|---|
| 7-7 | `docs/DRIFT/_INDEX.md:31` 仍是 `like` 域「⬜ 未建立」 | 由收口执行者更新索引（**本 lane 不碰**） | 🟢 流程 |
| 7-8 | `docs/Intentional_Deviations/_INDEX.md` 未收录本域 8 条偏离（`like/` 子目录已有 2 条 proto 偏离） | 由收口执行者统一更新 | 🟢 流程 |
| 7-9 | 映射表 §6 错误码自报数（30 → 29） | 由收口执行者改（**本 lane 不改映射表** —— AGENTS §2.3） | 🟡 P2 |
| 7-10 | ~~`/actuator/prometheus` 在生产上下文 404~~ | ⚠️ **本轮撤销**：原「归 `global` 收口域」的建议**基于一条假阳性** ⇒ 收口域**不需要**改这个 ⇒ §四·4.F。📌 **跨域提醒**：`docs/DRIFT/follow/verification-follow-2026-11-02.md:327-330` 是**同款错判** ⇒ **属 follow lane** ⇒ 本 lane 只在 `vs-common.md` 登记建议 follow 复核 | 🟢 无（**已撤销**） |
| 7-11 | `like.fact.process.*` 三元组 ＋ `like_api_reject_total` 可见性 | 登记为签字偏离 | 🟢 低 |
| 7-12 | ✅ ~~**守门 ①（`check-mapping-coverage.py`）在本轮 EXIT=1 —— 但失败点不在 like 域**~~：本域行 **like 69/69（C）· 83/83（B）· 100% ✅**；失败在 **users 域** —— 映射表 `docs/Mapping_Table/users/user/user.md:132` 的 **B 列列了两个 B 侧不存在的类** `CreateUserReq.java` / `CreateUserResp.java`（B 侧实际叫 **`RegisterReq.java` / `RegisterResp.java`**，见 B 仓 `service/users/user/user-api/.../dto/`；**该单元格自己的括号里就写着「B 的 `RegisterReq`/`RegisterResp` 在 C 改名 `CreateUserReq`/`CreateUserResp`」** ⇒ **B 列误填了 C 侧的名字**）。⇒ **与本 lane 的改动无关**（本 lane 只动 `service/like/**` ＋ `docs/DRIFT/like/**` ＋ `docs/File_Tree/like.md`）⇒ ⇒ **本 lane 不修**（AGENTS §2.3「禁止修改 Mapping_Table」＋ §五.3「只写自己域」） | ~~由 **users lane / 收口执行者**把 B 列的 `CreateUserReq.java`/`CreateUserResp.java` 改回 `RegisterReq.java`/`RegisterResp.java`~~ ⇒ ✅ **已被基线前进消解**（`main` 的 `85751b6` 已修）⇒ **实测守门 ① 现为 EXIT=0** | 🟢 **已消解**（**不影响 like 域**） |

---

## 八、双侧活体对拍 ＋ 四类场景轮 ＋ 变异验证 ＋ **Kafka 三面**（2026-11-03 收口）

### 8.1 探针是什么（**证据边界先说清**）

| 项 | 内容 |
|---|---|
| 工具 | [`tools/difftest/like/`](../../../../tools/difftest/like/)（`run.sh` 13 阶段 · `expected.txt` · `mint-jwt.py` · `grpc-wire/LikeWireProbe.java` · `mutate-parity.sh` · `README.md`） |
| 被测两侧 | **B 侧** `/Users/pr/Documents/java&golang/RAG/service/like/{like-api,like-rpc}`（Boot 4.0.8）· **C 侧** 本工作树 `service/like`（Boot 3.5.11，单进程 D1 合并） |
| **第三方锚** | **B 侧已冻结的 Go 真源字节**（`/Users/pr/Documents/java&golang/RAG/tools/api-full-parity-probe/like/expected.txt`，9 条 `CASE\|<name>\|<code>\|<hex>`，sha256 `3646e841…f75e2`） |
| **判定条件** | **C == B == 冻结的 Go 字节** 三方相等（**期望值本身参与判定**，不是摆设） |
| ⚠️ **为什么不跑 Go 侧** | 本机**无 Sea-etcd 容器** ⇒ **Go 真源不可活体** ⇒ 改用 B 侧 2026-10-29 录下的冻结字节作锚 ⇒ ⇒ **三方等价的强度不降**（C == B == Go 字节，等价于 B 侧原探针的 Go == B == Go 字节） |
| ⚠️ **本探针不证什么** | **不证「Go 此刻真的产出同样字节」**（Go 不可活体）⇒ 报告表述一律是「**C == B == 冻结的 Go 字节**」 |
| ⚠️ **环境约束** | Docker Desktop(macOS) **宿主不可达容器 IP** ⇒ 探针向 Nacos **手工注册 127.0.0.1 形态实例**（ephemeral ＋ 5s 保活 ＋ **分组隔离** E5B/E5C）⇒ 否则「同名服务互拨 ⇒ **对拍对象静默地错了**」 |
| ⚠️ **gRPC 客户端硬约束** | 必须用 `NettyChannelBuilder.forAddress(InetSocketAddress)`（**不经过名字解析**）⇒ 免疫 devh 把全局默认 scheme 抢成 `discovery` 的劫持（§四·4.H） |

### 8.2 阶段 A/B 的读数（**三方逐字节**）

| 阶段 | 用例 | 读数 |
|---|---|---|
| **A · HTTP 业务面**（8 条） | `getlikecount` · `getlikestate` · `gettargetlikerlist` · `getuserlikelist` · `getusertotallike` · `likeaction` · `no_token`(401 空 body) · `missing_required`(200 + `{"code":5001,"msg":"系统内部错误"}`) | ✅ **全部 C == B == 冻结的 Go 字节**（逐字节比 `<http_code>\|<hex body>`） |
| **B · gRPC wire 层**（6 条） | `likeAction` · `getLikeCount` · `getLikeState` · `getUserLikeList` · `getTargetLikerList` · `getUserTotalLike` | ✅ **全部双侧逐字节一致**（⚠️ **无 Go 锚** ⇒ 只算「双侧一致」，**不计入三方**） |

### 8.3 四类场景轮（**两侧的码值 ＋ 耗时都记**）

| 轮 | C 侧读数 | B 侧读数 | 判定 |
|---|---|---|---|
| **C · 依赖失败**（E60 双失败：Redis `ACL SETUSER default -zcard` ＋ PG `DROP TABLE like_record`，**两侧对称注入**） | HTTP **200** · `like_count=4` · **83ms** | HTTP **200** · `like_count=4` · **33ms** | ✅ **两侧 == 锚**（`CASE\|likeaction_double_failure`）；两侧各记 1 行 `business_error`（B=1 行 · C=1 行） |
| **D · 恢复**（撤掉注入 ⇒ 复原 ACL ＋ 重建表 ＋ 回灌种子行） | 业务码 **200** · **26ms** | 业务码 **200** · **28ms** | ✅ **连续 3 轮 200**（稳定态判据）后**逐字节一致**（`like_count=4`）；**第二轮独立复跑**：C **28ms** · B **30ms** · body 逐字节 = `{"code":200,"msg":"OK","data":{"counts":{"9007199254740994":{"like_count":4,"dislike_count":1}}}}` |
| **E · 并发**（两侧各 **8 路**并发 `likeaction`） | 码集合 `[200]` · 终值 **1**（HTTP **28ms** / Redis **1**） | 码集合 `[200]` · 终值 **1**（HTTP **28ms** / Redis **1**） | ✅ **码集合相同 ＋ HTTP/Redis 终态相同** |
| **F · 超时**（两侧 Redis 容器**同时** `pause` ⇒ 制造真实的「下游无响应」） | HTTP **503** · 无信封 · **3091ms** · `Request Timeout`<br>（**第二轮**：C=**?**·**3114ms**） | HTTP **200** · 业务码 **1015** · **2098ms**<br>（**第二轮**：B=**1015**·**2095ms**） | ⚠️ **形态不同 ⇒ 记为已知现象（DRIFT-LIKE-015 · 不计失败）**<br>根因：**B 侧 api→rpc 两跳**（2s 客户端预算先到）· **C 侧 D2 域内直连**（Redis 3s 命令超时后走 DB 降级）⇒ **拓扑产物**<br>✅ 两侧**都没挂死**；`unpause` 后两侧都回 **200**（C **30ms** / B **32ms**；第二轮 C **28ms** / B **29ms**） |

> 📌 **两轮独立复跑的读数一致**（除耗时抖动 ±20ms）⇒ 读数稳定，不是偶然绿。
>
> ⚠️ **场景有效性断言**：每条场景判据都配了「我测的是这个场景吗」的断言（容器还 running / 两侧进程还活着）
> ⇒ 不满足即判**环境问题**退出（exit 2），**绝不产出误导性读数**。

### 8.4 变异验证（**证明探针不是假绿**）

`tools/difftest/like/mutate-parity.sh` —— **M1~M6**，每条都要求「**目标用例红** ＋ **对照用例仍绿**」（⇒ 变异**只**影响一维）：

```
   ✅ 基线绿（exit 0）
   ✅ 精确转红（exit 1 · missing_required 与 B 侧/冻结值不符）
   ✅ 精确转红（exit 1 · getlikecount 与冻结值不符）
   ✅ 精确转红（exit 1 · 两侧数据不同被抓到）
   ✅ 精确转红（exit 1 · 请求失败被当成失败，而不是读残留 body）
   ✅ 精确转红（exit 1 · 字段名漂移被抓到）
   ✅ 精确转红（exit 1 · 双失败分支没走到 ⇒ 两侧都没记 business_error）
   ✅ 还原后绿（exit 0）
✅ 全部变异按预期表现 + 还原后回绿 ⇒ 探针非假绿
```

📌 另有**启动前残留自检**：发现上一轮变异没还原 ⇒ **立刻拒绝启动**（⇒「读数没有意义」）。

### 8.5 探针的机器摘要（**逐字引用**）

```
—— 探针摘要：❌ 真差异 0 ｜ ⚠️ 已知现象 1 ——
✅ 三方逐字节一致（C 侧 == B 侧 == 冻结的 Go 字节）
```

退出码：`run.sh` = **0**（派发入口 `tools/difftest/run-parity.sh like` = **0**）· `mutate-parity.sh` = **0**。

📌 **两轮独立复跑**（构建轮 ＋ 主 lane 复跑轮）⇒ **摘要逐字相同**、**退出码逐字相同** ⇒ 读数稳定。
📌 **收尾零残留**（两轮实测）：探针端口段 **18211-18219 零残留** ＋ 探针容器零残留。
📌 **B 侧只读**：探针全程只在 B 仓跑 `mvn package`（产物落 `target/`，已 gitignore）⇒ B 仓 `service/like` ＋ `service/common` **干净**。

### 8.6 Kafka 三面的**变异验证**（证明 §四·4.I 的四个面**不是假绿**）

脚本：`tools/difftest/like/mutate-kafka-3face.sh` ⇒ **EXIT=0**。

> 📌 **为什么必须有这一节**：§四·4.I 的四个面都是**异步**的（Kafka 推送、消费、中继都是异步）
> ⇒ ⇒ 「断言没报错」**不等于**「那条链路真跑过」⇒ ⇒ 必须证明每条断言都**真的咬在对应机制上**。

#### 🔴 判据组合必须**互斥**

| 变异 | 改什么 | 生产面 | 消费面 | 推送面 | 幂等面 |
|---|---|---|---|---|---|
| **基线**（未变异） | — | 🟢 | 🟢 | 🟢 | 🟢 |
| **M1** | `@KafkaListener(autoStartup = "false")` ⇒ **listener 停** | 🟢 | 🔴 | 🔴 | 🔴 |
| **M2** | `@Scheduled` 间隔改 `3600000` ⇒ **中继停** | 🟢 | 🟢 | 🔴 | 🟢 |
| **M3** | 撤掉测试里的 `bootstrap-servers` 覆盖 ⇒ **断真 broker** | 🔴 | 🔴 | 🔴 | 🔴 |

⇒ ⇒ **三条变异的红集合互不相同** ⇒ ⇒「全部一起红」这种**无区分度**的读数被排除。

| 变异 | 读数（机器摘要逐字） |
|---|---|
| 基线 | `✅ 基线绿（exit 0 · Tests run: 4, Failures: 0）` |
| M1 | `✅ 整体转红（exit 1）` ＋ `✅ consumerWritesAllFourPlaces ⇒ red（listener 停 ⇒ inbox 无行）` ＋ `✅ relayPushesOutboxEventToHotTopic ⇒ red（listener 停 ⇒ outbox 无行 ⇒ 中继无从推）` ＋ `✅ redeliveryIsIdempotent ⇒ red（listener 停 ⇒ inbox 无行）` ＋ `✅ producerSendsKafkaLikeMsgToTopic ⇒ green（生产面不依赖 listener ⇒ 仍绿（证明变异精确））` |
| M2 | `✅ 整体转红（exit 1）` ＋ `✅ relayPushesOutboxEventToHotTopic ⇒ red（中继停 ⇒ article-hot-events 无新消息）` ＋ 其余三例 **green** |
| M3 | `✅ 整体转红（exit 1）` ＋ **四例全 red** |
| 收尾 | `✅ 还原后绿（exit 0）⇒ 之前的红确实是**变异**造成的` |

#### 📌 三条变异各自证明了什么

| 变异 | 证明的命题 |
|---|---|
| **M1**（listener 停） | 「消费面那四处落库」**真的**由 `LikeUpdateKafkaListener` 造成（不是别的路径写的）⇒ ⇒ 同时**反证** `spring.kafka.listener.auto-startup=false` **从未生效过**（否则基线根本不会绿）⇒ 坐实 **DRIFT-LIKE-016** |
| **M2**（中继停） | 「推送面」与「消费面」是**两个独立机制**（停中继只让推送面红，消费面仍绿）⇒ ⇒ 两条断言各自咬在对应机制上 |
| **M3**（断 broker） | 四个面**真的在连真 Kafka**，**不是**跑在「无 broker 的降级路径」上 |

#### 📌 操作纪律（沿用 `mutate-parity.sh` 的规矩）

| 项 | 做法 | 为什么 |
|---|---|---|
| **唯一性前置断言** | 变异片段在目标文件里必须**恰好命中 1 次**，否则脚本**中止**（exit 3） | 「改了 N 处 ⇒ 读数不可归因」 |
| **还原方式** | **文件备份 ＋ `cp` 复制回来**，**绝不用 `git checkout --`** | 本 lane 的改动**还没提交** ⇒ `git checkout` 会把它们一起回滚掉 |
| **启动前残留自检** | 三个靶点逐个查原始形态 ⇒ 有残留**拒绝启动**（exit 2） | 脚本被杀掉（超时/手动/重启）时，变异会留在文件里 ⇒ 下一轮读数**没有意义** |
| **判红判绿的读数源** | **surefire 的 XML 报告**（逐 `<testcase>` 看 `<failure>`/`<error>`），**不用 grep 日志** | 日志里的 `FAILURE!` 行会被框架噪声淹没 |
| **还原后必须复跑** | 每条变异都要求「变异红 **且** 对照用例仍绿」＋ **还原后复跑为绿** | 只红不够 ⇒ 要证明「红是这次变异造成的，不是本来就红」 |

⚠️ **本次实测的收尾零残留**：`autoStartup = "false"` 命中 **0** 次 · `fixedRateString = "3600000"` 命中 **0** 次 · `bootstrap-servers=127.0.0.1:49992` 命中 **1** 次 ⇒ 三个靶点全部恢复原状。

---

## 九、审核结论（§十四 的 12 项必答）

| # | 问题 | 结论 |
|---|---|---|
| **1** | **C 是否完整承接 B 的全部实际职责？** | 🟡 **是，但有 1 条「已实现未验证」**（**F-02** 权威读 Servlet 暴露面 —— **B 侧本身是骨架、无真值可比**）。承接表 57 条**零 `NOT_IMPLEMENTED`**、**零 `FAIL`**；映射表 C 列 **69/69 = 100%**、B 列 **83/83 = 100%**。<br>（~~DRIFT-LIKE-012 netty gRPC server 真启动~~ ⇒ §四·4.H；~~DRIFT-LIKE-013 Kafka 三面~~ ⇒ §四·4.I —— **两条覆盖缺口本轮均已闭合**） |
| **2** | **Mapping_Table 的 RAG → RAG-V1 映射是否与当前真实代码一致？** | ✅ **一致**（守门 ① EXIT=0 · 双向对账 0 缺口）—— ⚠️ **唯一例外**：§6「错误码自报 30 个」与两侧代码（29）不符 ⇒ **只登记不改表**（DRIFT-LIKE-011） |
| **3** | **File_Tree 规定的目标结构是否正确落地？** | ✅ **已落地**（守门 ③ EXIT=0）⇒ **69 个 `.java` 落在映射表指定的 14 个技术角色包 ＋ 入口**；A11 的 3 个 fact 落点齐备 |
| **4** | **多文件合并是否造成职责、分支或异常路径遗漏？** | ✅ **无遗漏**（5 条 N:1 逐条核对 ＋ 35 条 1:N 的关键 6 条逐条核对）—— 尤其「**鉴权不对称**」（2 个公开读端点不取 uid）两侧逐条相同 |
| **5** | **文件拆分是否造成调用链、数据传递或事务边界断裂？** | ✅ **无断裂** —— 关键证据：**inbox ＋ record ＋ fact 单事务提交**由**真 PG 整批回滚**证明（`LikeMessageIngestRealPgTest`）⇒ 代理生效 ＋ 无自调用 |
| **6** | **API ＋ RPC 合并是否改变原有入口和执行行为？** | ✅ **入口形状未变**（6 HTTP ＋ 6 gRPC 全通）；⚠️ **2 条面变了**：① **验签预算位置**（DRIFT-LIKE-001，响应形状同）② **端口/应用名**（DRIFT-LIKE-002，D1 已签字） |
| **7** | **Common Starter 上收是否遗漏原有消费方？** | ✅ **无遗漏** —— 上收 **8 项 · 域内零副本**；26 个共享类**零空壳**；A6 守门 PASS；⚠️ 唯一留在域内的 Jackson 2 条补强**本就该留域内**（B 侧域私有） |
| **8** | **Spring Boot 4 → 3.5 是否引入业务可观察行为变化？** | ✅ **0 条** —— 原报的「`/actuator/prometheus` 404」是**假阳性**（真因 = Boot 测试框架的全局禁用 ⇒ 生产形态**可用**）⇒ 已撤销（§四·4.F）。其余 **12 组框架差异逐条核证后判定为「已证明等价」**（含本轮补证的 netty gRPC server 与 Flyway 版本） |
| **9** | **Tomcat / Jackson / Spring Framework 等底层版本变化是否产生实际漂移？** | ✅ **无漂移**（Tomcat 3 旋钮同值 ＋ 404 字节形状逐字节钉住；Jackson 强转规则逐条等价 ＋ 区分性实验 5 例；Spring 6.2 无 `withCoercionConfig` ⇒ 走 `postConfigurer` 路线，**不替换** Boot 的 mapper） |
⑯ **like → message 跨域通知真链路**（真 netty 对端 ＋ 真 discovery ⇒ 通知**逐字段** == B 侧规格 ＋ 失败语义 ⇒ §四·4.J；**两次全量回归均执行** ⇒ DRIFT-LIKE-019 已闭合） |
| **11** | **哪些差异仍然无法确认？** | **§七·7.1 里已列的缺口 = 0 项** |
| **12** | **当前 C 是否具备可靠的行为对拍条件？** | ✅ **具备「双侧活体对拍」条件（本轮已用上）** ——<br>✅ 具备：真 PG 16 ＋ 真 Redis 7 ＋ 真 Tomcat ＋ 真 Flyway ＋ 真 gRPC in-process **＋ 真 netty** ＋ 真 Kafka **broker**（`apache/kafka:3.7.2`）＋ 真 Kafka 客户端 jar<br>✅ **本轮已建成并跑通**：`tools/difftest/like/`（13 阶段）⇒ **C == B == 冻结的 Go 字节**，**两轮复跑读数一致**，退出码 **0**<br>⚠️ **仍不具备**「Go 真源活体对拍」条件（本机无 Sea-etcd）⇒ ⇒ 结论表述为「**C == B == 冻结的 Go 字节**」，**不表述**为「Go 此刻产出同样字节」 |

### 9.1 最终判定

> **C 不需要在代码结构上复制 B，但必须完整承接 B 的全部实际职责和需要保留的行为；框架实现可以不同，但不能以框架差异为理由放任未经批准的行为漂移。**
>
· ✅ **跨域通知面已跑通**（真 netty 对端 ＋ 变异验证 ＋ **两次全量回归逐字一致 = 110 例** ⇒ DRIFT-LIKE-019 已闭合）
>
> ⚠️ **诚实边界（分层说清，不含糊）**：
> · ✅ **「双侧活体对拍」** —— **本轮已达成**：C 侧与 B 侧**同时起、同时活、同请求、同字节**，且与**冻结的 Go 字节**三方相等（§八）⇒ 报告里**可以**表述「两侧活体读数相同 ＋ 与 Go 冻结字节一致」。
> · ⚠️ **「Go 此刻活体」** —— **仍未达成**（本机无 Sea-etcd）⇒ **不能**表述「Go 此刻真的产出同样字节」。
> · ✅ **Kafka 三面** —— **本轮已达成**：真 broker ＋ 真 `@KafkaListener` ＋ 真 `@Scheduled` 中继 ⇒ 生产/消费/推送/幂等四面跑通（§四·4.I）⇒ **不再**列为缺口。
> · ✅ **`notifyArticleLiked` 跨域面** —— **本轮已达成**（真 netty 假 message-rpc ＋ 真 discovery ＋ 逐字段 == B 侧规格 ⇒ §四·4.J）。<br>· ⚠️ **探针边界内的 4 项** —— 仍未覆盖 ⇒ **不写成已闭环**（§六·6.3）。
> ⇒ 按 AGENTS §0：「**『已落地』≠『已实现』；『源码级对照』≠『双侧活体对拍』**」—— 本轮把后半句**真的兑现了**（在可兑现的边界内）。
>
> 📌 **本轮一次「自我证伪」**：DRIFT-LIKE-010 原本是一条**跨域共性**的漂移（`/actuator/prometheus` 404 ⇒ 建议归 `global` 收口域），
> **本轮实测把它证伪了** ⇒ 真因是 **Boot 测试框架的全局禁用** ⇒ **生产形态可用** ⇒ **收口域不需要动**。
> ⇒ 这是 §3.3.2「以『实测证据』措辞出现的断言必须能被区分性实验支撑」的**反向示范**：**错误断言比没有断言更危险**。
>
> 📌 **本轮第二次「自我证伪」**：**DRIFT-LIKE-016** —— 台账旧结论写「`spring.kafka.listener.auto-startup=false` ⇒ listener 没起 ⇒ 消费面零覆盖」。
> **实测**：该键**没有消费点**（域内自建 `ConcurrentKafkaListenerContainerFactory` ⇒ Boot configurer 不参与）⇒ `autoStartup` 一直是框架默认的 **true** ⇒ **listener 一直都在起**；零覆盖的真因是**没有 broker 可连**。
> ⇒ ⇒ **一条错误归因会把下一个人引去改一个不存在的开关** ⇒ 已删死键 ＋ 改注释 ＋ 补**区分性实验**（M1：listener 一停，消费面/推送面/幂等面立刻转红 ⇒ **反证该键从未生效过**）。

---

## 十、引用清单

- 决策台账 → [`docs/决策记录.md`](../../决策记录.md)（**D1 · D2 · D3 · D7 · D13 · D14 · D16 · D17 · D18**）
- 两方对照 → [`docs/Mapping_Table/like/like.md`](../../Mapping_Table/like/like.md)
- 文件树 → [`docs/File_Tree/like/like.md`](../../File_Tree/like.md)
- 架构断言 → [`docs/架构符合性断言.md`](../../架构符合性断言.md)（A1–A13）
- 跨层对账 → [`docs/DRIFT/like/vs-common.md`](vs-common.md)
- 签字偏离（既有）→ [`docs/Intentional_Deviations/like/_signed-deviations.md`](../../Intentional_Deviations/like/_signed-deviations.md)（`DEV-LIKE-PROTO-001/002`）
- Lombok 规范 → [`docs/Lombok-使用规范.md`](../../Lombok-使用规范.md)（D18 展开注释强制）
- 对拍探针 → [`tools/difftest/like/`](../../../tools/difftest/like/)（`run.sh` · `expected.txt` · `mint-jwt.py` · `grpc-wire/LikeWireProbe.java` · `mutate-parity.sh` · **`mutate-kafka-3face.sh`** · `README.md`）
- Kafka 三面测试 → [`service/like/src/test/java/com/rag/like/messaging/LikeKafkaThreeFaceRealTest.java`](../../../service/like/src/test/java/com/rag/like/messaging/LikeKafkaThreeFaceRealTest.java)（4 例 · 真 broker ＋ 真 listener ＋ 真中继 ＋ 真 PG）
- B 侧真源（只读）→ `/Users/pr/Documents/java&golang/RAG/service/like/`
