# `favorite` 域 ↔ `common/` 共享层 —— 跨层对账台账

> **由 `align/favorite` 工作树产出** · **基线 commit `8de7818`**（本地 `main`）· 分支 `align/favorite`
> **核对日期**：2026-11-04
> **写权**：✅ 可写**本域** `service/favorite/**` ＋ `docs/DRIFT/favorite/**` ＋ `docs/Mapping_Table/favorite/**` ＋ `docs/Intentional_Deviations/favorite/**`；
> 🔴 **`common/**` 只读**（唯一写者 = 收口任务 —— AGENTS.md §五.3 规则 3：「`common/**` 只有收口任务能写」）。
> ⚠️ **本文件正文的「整合者」一律按「收口任务」理解**（`docs/Worktree.md` §1e）。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` / `docs/Intentional_Deviations/_INDEX.md` /
> `docs/Pitfalls/_INDEX.md` / `AGENTS.md` **一律不碰**。
> **配套文件**：[`verification-favorite-service-2026-11-04.md`](verification-favorite-service-2026-11-04.md)（本域八阶段审核报告）

> 📌 **lane 语义**：本文件是「**本域 ↔ 共享机制层 `common/`**」的跨层对账
> —— ⚠️ **不是**「本域自己的 `contract/` 包」（域内错误契约，见配套报告 §11.2）。

---

## 0. lane 身份

| 项 | 值 |
|---|---|
| 本域 | `service/favorite/`（artifactId **`favorite-service`** · 包 `com.rag.favorite`） |
| 共享层 | C 侧 `common/`（包 `com.rag.common`） |
| 工作树 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/favorite` |
| 分支 / 基点 | `align/favorite` / `8de7818` |
| 本轮改动 | **全部在工作树内、未提交** |
| 本轮核对日期 | **2026-11-04** |
| B 侧唯一 oracle | `/Users/pr/Documents/java&golang/RAG/service/favorite/`（**只读**；api 36 + common 4 + rpc 35 = **75** 个主代码 `.java` + 7 个 Flyway 脚本） |
| C 侧本域主代码 | **63** 个 `.java`（**0 空壳**） |

---

## 1. 本域**消费**的共享层能力（**25 个类 · 全部已实现 · 0 空壳**）

> **核法**：`grep -rhE '^import com\.rag\.common\.' service/favorite/src/main/java` ⇒ 去重 ⇒ **25** 个
> ⇒ 逐个确认**不是空壳** ⇒ **零「上收了没实现」**。

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 状态 |
|---|---|---:|---|---|---|
| 1 | `common/contract/ApiResponse.java` | 68 | `advice/FavoriteApiExceptionHandler` · `advice/FavoriteApiResponseAdvice` | HTTP 信封 `{code,msg,data}`（B 侧 `common/response/Response`）**上收** | ✅ |
| 2 | `common/exception/BusinessException.java` | 45 | `service/FavoriteApiSupport` · `advice/FavoriteApiExceptionHandler` | 业务异常基类 | ✅ |
| 3 | `common/exception/AuthenticationException.java` | 55 | `advice/FavoriteApiExceptionHandler` | 鉴权异常（401 出口） | ✅ |
| 4 | `common/logger/FatalError.java` | 50 | `advice/FavoriteApiExceptionHandler` · `wiring/FavoriteSubjectRefGate` | **不可恢复**错误（B 侧 `FatalError` ⇒ 原样重抛，**不**折成信封） | ✅ |
| 5 | `common/logger/GoStyleLogger.java` | 992 | `service/FavoriteApiSupport` | 业务日志门面（B 侧 `common/spring/LoggerFactoryBean`）**上收** | ✅ |
| 6 | `common/logger/LogOption.java` | 75 | `service/FavoriteApiSupport` | `userLogOption` / `articleLogOption`（B 侧 `WithUserID` / `WithArticleID`）**上收** | ✅ |
| 7 | `common/security/JwtUtil.java` | 111 | `interceptor/FavoriteJwtAuthInterceptor` | HS256 验签 + `padToHmacKeySize` | ✅ |
| 8 | `common/security/BearerTokenExtractor.java` | 105 | `interceptor/FavoriteJwtAuthInterceptor` | `Authorization: Bearer` 提取 | ✅ |
| 9 | `common/web/RouterErrorResponses.java` | 152 | `advice/FavoriteApiExceptionHandler` | 404 / 405 的 **B 侧 router 形状**（text/plain + nosniff） | ✅ |
| 10 | `common/web/GetFormValuesFilter.java` | 759 | `interceptor/QueryLimitInterceptor` | **Go query 判据**（`goParseViolation`：段数 10000 / 分号 / 非法转义 / 非空值 2048）**上收** | ✅ |
| 11 | `common/web/StringInt64.java` | 117 | `dto/favorite/*` · `dto/folder/*` | `,string` int64 的**宽松**解析（B 侧 `GoStyleStringInt64`）**上收** | ✅ |
| 12 | `common/persistence/JsonbTypeHandler.java` | 84 | `domain/entity/FavoriteFactOutboxEntity` | jsonb 列的 type handler（B 侧**域内副本**）**上收** | ✅ |
| 13 | `common/snowflake/Snowflake.java` | 446 | `domain/service/FavoriteDomainService` | 雪花 ID 生成（**静态** `nextId()`） | ✅ |
| 14 | `common/observability/DeadlineState.java` | 230 | `wiring/FavoriteAuthorityEndpointConfig` | 入向 deadline 载体（B 侧 `RpcInterceptor.DeadlineState`） | ✅ |
| 15 | `common/communityfact/Wire.java` | 210 | `service/FavoriteFactEnvelopeFactory` · `service/FavoriteDeliveryValidator` · `service/FavoriteFactDispatchService` | wire event / receipt / hash / 错误常量 | ✅ |
| 16 | `common/communityfact/Event.java` | 40 | `service/FavoriteFactEnvelopeFactory` | wire event（`Wire.Event` 的 **1:N 拆分**） | ✅ |
| 17 | `common/communityfact/TechnicalReceipt.java` | 27 | `service/FavoriteDeliveryValidator` | 技术回执 | ✅ |
| 18 | `common/communityfact/SubjectRef.java` | 42 | `service/FavoriteDeliveryValidator` | **v2** 的 2 字段 `subject_ref` | ✅ |
| 19 | `common/communityfact/AuthorityFact.java` | 34 | `wiring/FavoriteAuthorityEndpointConfig` · `domain/model/FavoriteAuthorityFact` | **v2** 权威事实 | ✅ |
| 20 | `common/communityfact/DeliveryStatus.java` | 43 | `service/FavoriteFactDispatchService` | 投递状态 | ✅ |
| 21 | `common/communityfact/CanonicalHash.java` | 66 | `service/FavoriteDeliveryValidator` | JCS 指纹（**🔴 见 §3 的 G-2**） | ✅ |
| 22 | `common/communityfact/EventDispatcher.java` | 553 | `wiring/FavoriteFactDispatchScheduler` | 事实投递循环（B 侧 `Dispatcher`）**上收** | ✅ |
| 23 | `common/communityfact/EventSender.java` | 171 | `wiring/FavoriteFactDispatchScheduler` | DC 事件发送（B 侧 `Sender` / `DCEventSender`）**上收** | ✅ |
| 24 | `common/communityfact/ProcessLogger.java` | 244 | `wiring/FavoriteFactProcessLogging` | 独立 logger 名 + 独立 appender | ✅ |
| 25 | `common/communityfact/ProcessMetadata.java` | 68 | `wiring/FavoriteFactProcessLogging` | 进程元数据（`service` / `component` / `env`） | ✅ |

---

## 2. 本域**向共享层上收**的清单（**1 项 · ✅ 已于 2026-11-03 由收口任务完成**）

| B 侧域内副本 | C 侧落点 | 状态 |
|---|---|---|
| `favorite-rpc/…/mapper/JsonbTypeHandler.java` | `common/persistence/JsonbTypeHandler.java` | ✅ **已上收**（**D3**）⇒ **本域 `mapper/` 包只有 4 个 Mapper，0 副本** |

⚠️ **本域的 `mapper/` 包里没有 jsonb handler** ⇒ 架构断言 **A6（MP ordering bridge 全仓只一份）** 与
「域内副本数」口径一致 ⇒ **本轮不需要再做任何上收动作**。

---

## 3. 机制面轴 · 本域侧逐轴结论

| 轴 | 本域侧结论 | 证据 |
|---|---|---|
| **HTTP 信封** | ✅ 用共享层 `ApiResponse`（`{code,msg,data}` 三字段）⇒ 与 B 侧 `common/response/Response` 同形 | `advice/FavoriteApiResponseAdvice` |
| **HTTP 错误出口** | ✅ 共享层 `BusinessException` / `AuthenticationException` 承载；**解析失败 = 200 + 5003**（本域特有） | `advice/FavoriteApiExceptionHandler` |
| **404 / 405** | ✅ 共享层 `RouterErrorResponses`（**B 侧 router 形状**：text/plain + nosniff）⇒ 不自造 | `handleNoResourceFound` / `handleMethodNotSupported` |
| **JWT 验签** | ✅ 共享层 `JwtUtil` + `BearerTokenExtractor`；本域只负责「验签结果 ⇒ 落 claim / 黑名单」 | `interceptor/FavoriteJwtAuthInterceptor` |
| **日志** | ✅ 共享层 `GoStyleLogger` **静态**入口 + `LogOption.withUserId/withArticleId` ⇒ **不建** `WithUserID` / `WithArticleID` 副本 | `service/FavoriteApiSupport#options` |
| **Go query 判据** | ✅ 共享层 `GetFormValuesFilter#goParseViolation`（**只读调用**）⇒ 本域只负责「渲染成什么形状」（200 + 5003） | `interceptor/QueryLimitInterceptor` |
| **` ,string` 解析** | ✅ 共享层 `StringInt64.parse` ⇒ 本域 **0 副本** | `dto/**` 的 `@JsonCreator` |
| **jsonb** | ✅ 共享层 `JsonbTypeHandler` ⇒ 本域 **0 副本** | `domain/entity/FavoriteFactOutboxEntity` |
| **雪花 ID** | ✅ 共享层 `Snowflake`（**静态** `nextId()`）⇒ 本域**不注入** Bean（共享层生成状态是静态的，Bean 只解析 workerId） | `domain/service/FavoriteDomainService` |
| **gRPC 保护链** | ✅ 共享层 `ProtectionAutoConfiguration#seaGrpcServerConfigurer`（**唯一**挂载点）⇒ 本域 **0 条自建链** ⇒ **架构断言 A3 PASS** | `FavoriteServiceApplication` |
| **事实链路** | ✅ 共享层 `communityfact` 11 个类（**1:N 拆分后**）⇒ 本域只持**favorite 特有**的 v1 三字段 `subject_ref` | `domain/model/FavoriteAuthorityFact` |
| **🔴 G-2 · `CanonicalHash` 的 JCS 指纹不一致** | 🟠 **本域侧无责** —— 共享层的 `CanonicalHash` 与 Go 的 JCS 指纹**不一致** ⇒ 幂等失效。**归 `common/` 收口任务** | 共享层 `common/communityfact/CanonicalHash.java` |
| **🔴 `GetFormValuesFilter` 曾是空壳** | ✅ **已实现**（759 行）⇒ 域内 `QueryLimitInterceptor` 的判据依赖**已就位** | `common/web/GetFormValuesFilter.java` |

---

## 4. 域侧能自修的（**本域直接修**，不甩给收口）

| # | 项 | 处置 |
|---|---|---|
| 1 | `io.grpc:grpc-inprocess` 被声明成 `test` scope ⇒ 生产 fat jar 起不来 | ✅ **本域已修**（`runtime` scope + **B 侧警告块整段搬入 pom**）⇒ 登记为 `DRIFT-FAV-001` |
| 2 | `application.yml` 缺 `spring.flyway` 段 ⇒ 4 项 Flyway 配置**静默失效** | ✅ **本域已修**（独立账本 + `baseline-version: 0`） |
| 3 | `application.yml` 缺 `favorite-api.user-auth.*` ⇒ 密钥守卫拿不到值 | ✅ **本域已修** |
| 4 | `application.yml` 缺 `grpc.client.{user-rpc,article-rpc}` ⇒ **A4 断言 FAIL** | ✅ **本域已修**（三斜杠 + `negotiation-type: plaintext`）⇒ **A4 PASS** |
| 5 | `application.yml` 的 Redis 段缺超时 / 连接池 ⇒ 与 B 侧差 2 倍 / 无限等 | ✅ **本域已修**（`timeout: 3s` / `connect-timeout: 5s` / lettuce pool 四档） |
| 6 | `application.yml` 缺 Tomcat 三旋钮 | ✅ **本域已修**（照抄 B 侧 favorite-api 的 yaml） |

---

## 5. 需转派**收口任务**的（**本域只登记：现象 + 证据 + 建议修法**）

| # | 现象 | 证据 | 建议修法 |
|---|---|---|---|
| 1 | 🔴 **G-2 · `CanonicalHash` 的 JCS 指纹与 Go 不一致** ⇒ 幂等判定失效 | 共享层 `common/communityfact/CanonicalHash.java`（域内 `service/FavoriteDeliveryValidator` 消费） | 收口任务核对 Go 侧 JCS 实现（键序 / 数字 / 空白 / 转义），统一后**补真触发对拍** |
| 2 | 🟠 **`DRIFT-CF-003` / `DRIFT-CF-014` 落在共享层**（B 侧台账登记的共享层漂移） | 共享层 `communityfact/AuthorityHandler`（尾 `\n` + `escapeHTML`）· `EventSender.DCEventSender.create`（`URI.create` 直接抛） | 收口任务按 B 侧逐条修；**本域侧已照抄 B 侧语义** ⇒ **本域不复制** |
| 4 | 🟠 **框架级陷阱：devh 的 `DiscoveryClientResolverFactory`（priority 6 > DNS 5）会抢走 grpc 的**全局默认名字解析 scheme** ⇒ ⇒ 任何用 `forAddress(host, port)` 自建的通道都会把字面地址当**服务名**去查 ⇒ `UNAVAILABLE: No servers found for <host>:<port>`**（2026-11-04 全量 verify 实测抓出 · 症状长得像「服务端没起来」） | 触发条件 = **本 JVM 里创建过任何 Spring 上下文**（devh 的 `NameResolverRegistration` 在上下文创建期注册进**全局** registry）⇒ ⇒ **红绿取决于测试类执行顺序** ⇒ 逐层源码出处与实测读数 ⇒ 配套台账 §五·**5.8**；本仓先例 = `service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java:79-99` | **共享层现有代码里没有 `forAddress` 用法**（已全仓 grep 核实）⇒ ⇒ **无需改现有代码**；**建议收口任务**：共享层 / 后续域若要**自建 gRPC 通道**（测试探针或工具类）⇒ **必须**用 `forAddress(new InetSocketAddress(host, port))`（该重载**不经过名字解析**）⇒ ⇒ 否则会踩同一个坑 |
| 3 | 🟠 **共享层 `ClientDisconnectProbe` 的裸写 1xx 会落进 chunked body 中间 ⇒ 客户端随机读不全**（2026-11-04 实测） | `common/src/main/java/com/rag/common/observability/ClientDisconnectProbe.java:137`（裸写 `HTTP/1.1 103 Early Hints\r\n\r\n`）⇒ 写入时机不定 ⇒ 可能落在响应头之后、**chunked body 中间** ⇒ JDK `HttpClient` 的 chunk 解码器看到垃圾 ⇒ 抛 `java.io.IOException: Illegal character in chunk size: 72`（72 = `'H'`）⇒ ⇒ **真 Tomcat 用例随机红**（同一份代码上一轮绿、这一轮红） | **本域只登记**（共享层写权不在本域）⇒ **本域侧处置**：真 Tomcat 用例在 `@BeforeAll` 里把 `rag.common.http.disconnect-probe-max-lines` 设为 **0**（= 探针 fail-open 关闭）**并在 `@AfterAll` 还原** ⇒ ⚠️ **不削弱任何断言**（1xx 与本域判据无关）⇒ **建议收口任务**：评估「探针写入与响应体序列化」的互斥（否则任何 chunked 响应的客户端都可能随机读不全） |

---

## 6. 未验证项（本轮明确留下的缺口，**不写成已闭环**）

| # | 项 | 性质 |
|---|---|---|
| 1 | ✅ **E-4 双侧活体对拍**（B 侧进程 × C 侧进程同时跑，逐条比对响应体 / 状态码 / 副作用） | **HTTP 面已闭合**（2026-11-04 · `tools/difftest/favorite/` · 32 case × 3 方：Go 冻结字节 == B 侧活体 == C 侧活体 · 变异验证红→绿）**且 gRPC 面已闭合**（2026-11-05 · `tools/difftest/favorite/grpc-wire/` · 36 case × 3 方：Go gRPC 冻结锚 == B 侧 `favorite-rpc` 活体 == C 侧 `favorite-service` 活体 · **BC 校验 36/36 逐字节全等** · 变异验证红→绿）⇒ 🟠 **另加一层「场景轮」**（2026-11-05 · `tools/difftest/favorite/scenarios/` · 6 类场景：create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / **重复请求（幂等重放）** ⇒ **6/6 全绿** · 绝对断言全过 · 变异验证红→绿（两轮））⇒ ⇒ 🟠 **但场景轮只有 B↔C 双侧活体、无第三方锚**（本机无 `Sea-etcd`）⇒ ⇒ **强度低于三方** ⇒ ⇒ ✅ **verification.md §九 的 11 项「对拍必须覆盖」已逐项有证据** |
| 2 | 🟠 **共享层 `CanonicalHash` 的 JCS 指纹** | 需收口任务修 + 对拍 ⇒ 本域**不能**声称幂等语义已对齐 |
| 3 | ✅ **preflight 触发入口**（**已闭合** · 2026-11-05） | 决策 **P6-c 拍板 ① Actuator 自定义端点** ⇒ 落 `service/FavoriteLegacyPreflightService`（报表计算）+ `wiring/FavoriteLegacyPreflightEndpoint`（触发入口）⇒ 真 PG 7 例 + 真 Tomcat 5 例 ⇒ **本项不再是缺口** ⇒ 详见配套台账 §五·5.1 / §八·8.6 |
| 4 | 🟡 **`favorite-api` / `favorite` 两套配置源并存**（`FavoriteRpcProperties` 与 `wiring/` 的 `@Value` 读**同一批键**） | 当前默认值逐条一致 ⇒ **无分叉**；建议后续统一到 `FavoriteRpcProperties`（**需拍板**，不在本域写权内）⇒ 登记为 `DRIFT-FAV-007` |

---

## 7. 交付物清单（**本域侧**）

| 类别 | 路径 | 状态 |
|---|---|---|
| 主代码 | `service/favorite/src/main/java/com/rag/favorite/**`（**63** 个 `.java`） | ✅ **0 空壳** |
| DDL | `service/favorite/src/main/resources/db/migration/V1..V7` | ✅ **SQL 正文逐字零差异** |
| 配置 | `service/favorite/src/main/resources/application.yml` | ✅ 补 6 处（见 §4）**+ 第二轮补 6 块整块缺失的配置面**（`rag.protection.outbound-deadline` / `observability.*` / `otel:` / `management.metrics.tags.application` / `management.endpoints.*` / `nacos.discovery.register-enabled` ⇒ 见配套台账 §五·5.7） |
| 对拍探针 | `tools/difftest/favorite/`（**本域私有目录** · 不属共享层） | ✅ **E-4 · HTTP 面 32 case × 3 方**（Go 冻结字节 == B 侧活体 == C 侧活体）· 变异验证红→绿 ⇒ 详见配套台账 §八·**8.4**<br>✅ **E-4 · gRPC 面 36 case × 3 方**（Go gRPC 冻结锚 == B 侧 `favorite-rpc` 活体 == C 侧 `favorite-service` 活体 · **BC 校验 36/36 逐字节全等**）· 变异验证红→绿 ⇒ 落点 `tools/difftest/favorite/grpc-wire/` ⇒ 详见配套台账 §八·**8.5**<br>🟠 **场景轮 6 类场景**（create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / **重复请求（幂等重放）** ⇒ **6/6 全绿** · 绝对断言全过 · 变异验证红→绿两轮）⇒ **只有 B↔C 双侧、无第三方锚** ⇒ 落点 `tools/difftest/favorite/scenarios/` ⇒ 详见配套台账 §八·**8.7** |
| 测试 | `service/favorite/src/test/java/com/rag/favorite/**` | ✅ **29 个测试类 · Tests=215 · Failures=0 · Errors=0 · Skipped=0**（含 `config/FavoriteOutboundDeadlineContractTest` 7 条 · **含反向对照** + `http/FavoriteApiRealTomcatGateTest` 17 条 · **真 Tomcat** + **`grpc/FavoriteGrpcRealTransportTest` 5 条 · 真 TCP + 真 HTTP/2 + 真 protobuf** + **`mapper/FavoriteLegacyPreflightParityTest` 7 条 · 真 PG** + **`http/FavoriteLegacyPreflightEndpointTest` 5 条 · 真 Tomcat** + **`mapper/FavoritePgTestContextSharingTest` 1 条 · 上下文共享守门** + **`http/FavoriteApiHopDeadlineContractTest` 5 条 · 真 gRPC 慢下游（方向 ②）**）⇒ 读数见配套报告 §八 |
| 迁移审核台账 | `docs/DRIFT/favorite/verification-favorite-service-2026-11-04.md` | ✅ |
| 跨层对账台账 | `docs/DRIFT/favorite/vs-common.md`（本文件） | ✅ |
| 映射表 | `docs/Mapping_Table/favorite/favorite.md` | ✅ 闭合 M1 / M2 / M4 / M6（**M3 preflight 于 2026-11-05 闭合**）· C 列 **63/63** |
| 签字偏离 | `docs/Intentional_Deviations/favorite/_signed-deviations.md`（**新建**） | ✅ **12 条**（9 条继承 B 侧已签字 ＋ 3 条 C 侧专属：`DEV-FAV-RPC-008` 事务 · `DEV-FAV-RPC-009` 触发形态 · `DEV-FAV-API-003` api→领域层自持出向上界） |
| 文件树 | `docs/File_Tree/favorite.md` | ✅ 重生成（`gen-file-tree.py` 用**改过 ROOT 的副本**跑 ⇒ 未改仓内脚本）· **2026-11-05 再次重生成**（新增 2 个主代码 + 3 个测试类进树） |

---

## ⚠️ 三条必读

1. ⚠️ **本文件里的 ✅ 一律读作「在该证据强度下一致」** —— **不是**已闭环。
2. ⚠️ **「E-2 源码级对照」≠「E-4 双侧活体对拍」** —— 后者才是行为层证据。
3. ⚠️ **测试全绿本身不构成证据** —— 必须能排除「假绿」（变异实测 / `skipped = 0` 断言）。
