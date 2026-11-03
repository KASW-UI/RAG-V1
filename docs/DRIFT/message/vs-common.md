# `message` 域 ↔ `common/` 共享层 —— 跨层对账台账

> **由 `align/message` 工作树产出** · **基线 commit `ae17325`**（本地 `main`）· 分支 `align/message`
> **核对日期**：2026-11-02
> **写权**：✅ 可写**本域** `service/message/**` ＋ `docs/DRIFT/message/**`；
> 🔴 **`common/**` 只读**（唯一写者 = 合并收口执行者 —— AGENTS.md §五.3 规则 3：「`common/**` 只有收口任务能写」）。
> **本文件正文的「整合者」一律按「合并收口执行者」理解**（`docs/Worktree.md` §1e）。
> ⚠️ **本文件只由本工作树写**；`docs/DRIFT/_INDEX.md` / `docs/Intentional_Deviations/_INDEX.md` /
> `docs/Pitfalls/_INDEX.md` / `AGENTS.md` **一律不碰**（AGENTS.md §五.3 规则 3）。
> **配套文件**：[`verification-message-service-2026-11-02.md`](verification-message-service-2026-11-02.md)（本域八阶段审核报告）

> 📌 **lane 语义**：本文件是「**本域 ↔ 共享机制层 `common/`**」的跨层对账
> —— ⚠️ **不是**「本域自己的 `contract/` 包」（域内错误契约，见配套报告 §二·2.2）。

---

## 0. lane 身份

| 项 | 值 |
|---|---|
| 本域 | `service/message/`（artifactId **`message-service`** · 包 `com.rag.message`） |
| 共享层 | `service/common/` → C 侧 `common/`（C 侧包名 `com.rag.common`） |
| 工作树 | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/message` |
| 分支 / 基点 | `align/message` / `ae17325` |
| 本轮改动 | **全部在工作树内、未提交**（`git diff main..HEAD` 为空） |
| 本轮核对日期 | **2026-11-02** |

---

## 1. 本域**消费**的共享层能力（12 个类 · 全部已实现）

> **核法**：`grep -rhoE 'import com\.rag\.common\.[A-Za-z0-9_.]+' service/message/src/{main,test}/java` ⇒ 去重 ⇒ **12** 个。
> ⇒ 逐个确认**不是空壳**（`grep -c 'TODO：职责待补'` = 0）⇒ **零「上收了没实现」**（B 侧 `DRIFT-COMMON-V07` 同款风险在本域**不成立**）。

| # | 共享层类 | 行数 | 本域消费点 | 承担的 B 侧职责 | 实现状态 |
|---|---|---|---|---|---|
| 1 | `common/web/StringInt64.java` | 117 | 6 个 DTO 的 `,string` 入参解析（`AdminSendChatReq` / `UserSendChatReq` / `ConversationMessagesReq` / `AdminSendNotificationReq` / `MarkNotificationReq` / `MarkConversationReadReq`） | B 侧 `message-api/.../dto/GoStyleStringInt64.java`（109 行）**上收** | ✅ 非空壳 |
| 2 | `common/exception/BusinessException.java` | 45 | `MessageApiExceptionHandler` / `MessageApiService` / `MessageApiSupport` | B 侧领域异常基类 | ✅ |
| 3 | `common/exception/AuthenticationException.java` | 55 | `AbstractMessageJwtAuthInterceptor`（抛）＋ `MessageApiExceptionHandler`（接） | 同上 | ✅ |
| 4 | `common/contract/ApiResponse.java` | 67 | `MessageApiResponseAdvice` / `MessageApiExceptionHandler` | HTTP 信封载体（B 侧 `common/response/Response`）**上收** | ✅ |
| 5 | `common/web/RouterErrorResponses.java` | 152 | `MessageApiExceptionHandler`（404 / 405） | B 侧 `common/http/GoStyleRouterErrorResponses.java` **上收**（逐字节复刻 404 body） | ✅ |
| 6 | `common/web/MapStringValueDeserializer.java` | 145 | 共享层 Jackson 自动装配（本域经 DTO 的 `extra` map 间接消费） | B 侧 `api/config/GoStyleMapStringValueDeserializer.java`（99 行）**上收** | ✅ |
| 7 | `common/web/Coercion.java` | 179 | `WebMvcConfig#goStyleCoercionCustomizer`（共享 2 条解析规则） | B 侧 `api/config/MessageGoStyleCoercion.java`（120 行）的**共享部分** | ✅ |
| 8 | `common/snowflake/Snowflake.java` | 446 | `MessageAtomicService`（消息 id 生成） | 雪花 ID | ✅ |
| 9 | `common/security/JwtUtil.java` | 111 | `AbstractMessageJwtAuthInterceptor`（`padToHmacKeySize` 密钥补齐） | JWT 工具 | ✅ |
| 10 | `common/security/BearerTokenExtractor.java` | 105 | 同上（`tokenForSignatureVerification`） | B 侧 golang-jwt 的 `stripBearerPrefixFromTokenString` 口径 | ✅ |
| 11 | `common/protection/ServiceBusyException.java` | 36 | `MessageApiExceptionHandler`（熔断打开 ⇒ 1015） | B 侧 `GoStyleCircuitOpenException` **上收** | ✅ |
| 12 | `common/logger/FatalError.java` | 47 | `MessageApiExceptionHandler`（原样抛出，绝不转 envelope） | 致命错误 | ✅ |

> 📌 **消费面 15 个文件**（`service/message/src/main/java/`）：
> `advice/MessageApiExceptionHandler` · `advice/MessageApiResponseAdvice` · `config/WebMvcConfig` ·
> `controller/MessageController` · `controller/MessageAdminController` ·
> `domain/service/MessageDomainService` · 6 个 `dto/**` · `interceptor/AbstractMessageJwtAuthInterceptor` ·
> `service/MessageApiService` · `service/MessageApiSupport`。

---

## 2. 本域**向共享层上收**的清单（**3 项 · 零遗漏**）

| 上收项 | B 侧原位（各域私有副本） | C 侧落点 | 本域消费方 | 结论 |
|---|---|---|---|---|
| `,string` 整数解析 | `message-api/.../dto/GoStyleStringInt64.java`（109 行） | `common/web/StringInt64.java` | 6 个 DTO | ✅ **域内零副本** |
| HTTP 信封载体 | `common/response/Response`（B 侧共享层，本域经 `Response` 使用） | `common/contract/ApiResponse.java` | 2 个 advice | ✅ **域内零副本** |
| 路由级 404 / 405 响应 | `common/http/GoStyleRouterErrorResponses.java` | `common/web/RouterErrorResponses.java` | 1 个 advice | ✅ **域内零副本** |

> ⚠️ **B 侧有 11 个 Jackson/HTTP 工具类是「分散在 `article-api` / `like-api` / `message-api` / `users-common`」的**
> ⇒ 本域**只贡献了 `GoStyleMapStringValueDeserializer` 那一份**（B 侧 `message-api/.../config/`）⇒ 上收后**域内零副本**。

### 2.1 🔴 **待收口**（**属 `common/` 写权 · 不在本 lane**）

| 项 | 现象 | 为什么现在不能收 | 收口建议 |
|---|---|---|---|
| **`JsonbMapTypeHandler`**（`jsonb ↔ Map`） | B 侧 `message-rpc/.../mapper/JsonbMapTypeHandler.java`（118 行）<br>C 侧原先落在**域内** `service/message/src/main/java/com/rag/message/mapper/JsonbMapTypeHandler.java`（154 行） | 共享层现有两份**类型都不兼容**：<br>① `common/persistence/JsonbTypeHandler` = `BaseTypeHandler<String>`（**jsonb ↔ String**）<br>② `common/persistence/JsonTextTypeHandler` = **text ↔ Map**<br>⇒ ⇒ **两者都无法顶替 jsonb ↔ Map** ⇒ ⇒ 且 `common/` 非本 lane 可写 | ✅ **已收口（2026-11-03 · D-11 · `global` 收口域）** —— 共享层**新增** `common/persistence/JsonbMapTypeHandler.java`（**照抄 B 侧 118 行原类** ⇒ 代码体 **34 行逐行完全相同**；**类名保持不变** ⇒ 本域只需改 import）＋ **本域副本已删除** ⇒ **副本数 1 → 0**<br>⚠️ **不与共享层 `JsonbValueTypeHandler` 的 Map 版合并** —— 写 null 路径 / 非 null 绑定 / 读空白串三处**行为不同** ⇒ 合并即漂移 |

> ⚠️ **本条不是「本域偷懒留副本」** —— 是**共享层缺一个对应物** ⇒ 已登记为签字偏离（配套报告 §六·6-5）。
> ✅ **销账状态（2026-11-03 · `global` 收口域 · `DRIFT-COMMON-V19`）**：本条**已收口** ⇒ 共享层补上对应物、**本域副本已删** ⇒ 签字偏离的那条「域内副本」理由随之消解（**是否仍需保留偏离登记，须由 `docs/Intentional_Deviations/` 侧复核** ⇒ 本 lane 不碰该文件）。

---

## 3. 9 条机制面轴 · 本域侧逐轴结论

> **轴的定义**沿用 B 侧 `docs/DRIFT/message/vs-common.md` 的口径（超时 / deadline · gRPC 拦截器链 · HTTP 保护链 ·
> 错误码 / envelope · 端口治理 · errmsg 契约 · proto 契约 · DDL 时间列 / 类型 · trace / otel）。
> ⚠️ **「✅ 一致」的含义 = 在该证据强度下一致**，**不是**已做双侧活体对拍（配套报告 §七·4 的诚实边界）。

| 轴 | 机制 | 本域侧的落点 / 调用 | 结论 | 证据强度 | 核对日期 |
|---|---|---|---|---|---|
| **A1** | 超时 / deadline | ⚠️ **本域的两个 gRPC client（`UserRpcClient` / `AdminRpcClient`）用 blocking stub 且<b>不设显式 deadline</b>**<br>⇒ **B 侧同款无 deadline**（`message-rpc/.../client/UserRpcClient.java` 全文无 deadline/timeout）<br>⇒ ⇒ **两侧一致**（**继承 B 侧形态，不是 C 侧引入的**）<br>📌 共享层的 `GoStyleRequestDeadline` / `Deadline` 在本域**无消费点**（本域不碰 Redis） | ✅ **一致**（两侧都无显式 deadline） | **L1 源码逐条对照**（两侧 client 主体 diff = 4 / 8 行，**全部**是 `LOG`→`log` 字段名 + 1 处 import 化 + 1 个死字段删除） | 2026-11-02 |
| **A2** | gRPC 拦截器链 | 共享层 `common/autoconfigure/ProtectionAutoConfiguration.java:240` ⇒ `server.intercept(chain)`（`seaGrpcServerConfigurer`）⇒ **对全部域生效**，本域**零重复**<br>⇒ 守门 **A3 PASS**（「`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复」） | ✅ **一致**（C 侧优于 B：B 侧三域曾静默失效） | **L2 装配层守门**（A3 PASS）＋ 共享层 224 例绿 | 2026-11-02 |
| **A3** | HTTP 保护链 | 共享层 `ProtectionAutoConfiguration` ⇒ `MaxBytesFilter`（`:130`）＋ `HttpMiddleware`（3s 预算）⇒ 本域**零重复**<br>⇒ 🔴 **本轮新修**：`application.yml` 曾**完全没有 `observability` 段** ⇒ `ObservabilityProperties` 的两个 timeout 缺省即 `Duration.ZERO` ⇒ **3s HTTP 预算与 2s gRPC 超时都不存在** ⇒ 已补 `http-timeout: 3s` ＋ `rpc-timeout: 2s`（配套报告 §五·5-⑩）<br>⇒ ⚠️ **本域仍有的差异**：**验签只执行一次**（C 侧在 3s 预算**内**）；B 侧**执行两次**（Filter 一次在预算**外** + interceptor 兜底一次在预算**内**）⇒ 差异面 = 验签次数与耗时分布，**响应形状逐条相同** ⇒ 见配套报告 §六·6-6（**本轮已更正其表述**） | 🟡 **部分一致**（保护链齐备；**验签次数面未闭环**） | **L2 装配层**（A3 PASS ＋ 运行期断言两个 timeout 真绑上）＋ **L1 源码**（B 侧 `MessageJwtAuthFilter.java:123-165`） | 2026-11-02 |
| **A4** | 错误码 / envelope | **HTTP 信封** = 共享层 `common/contract/ApiResponse`（`{code,msg,data}` 三键）<br>**业务错误契约** = **域内** `contract/ErrCodes` + `ErrMsg`（20 码 / 20 条**英文**文案）⇒ **逐域隔离，不共享**<br>⇒ 失败路径 **HTTP 200 + 信封**（唯一例外：401 + 空 body） | ✅ **一致** | **L1 源码逐条对照**（20/20 code→msg **逐字节**一致，机器 `diff` 空输出）＋ **L2 装配层**（真启动测试） | 2026-11-02 |
| **A5** | 端口治理 | HTTP **8883** ＋ gRPC **8084**（D1 合并 ⇒ B 侧 8892 / 8082 的合并产物）<br>⇒ **注册到 Nacos 的是 gRPC 端口**（`GrpcServerProperties.getPort()`），**不是** web 端口<br>⇒ ⚠️ `register-enabled: false` **必须开**（否则同名服务名下两个端口的实例 ⇒ gRPC 链路断） | ✅ **一致**（端口值本身是 D1 的**已签字**偏离） | **L2 装配层**（真启动断言：桩上**只注册 1 次**、端口 = gRPC 端口 ≠ web 端口、注册名 = `message-rpc`） | 2026-11-02 |
| **A6** | errmsg 契约 | **域内** `contract/`：20 码 + 20 条文案 + `CodeError` + `GrpcErrorMapper`（含 **cause 链解包**）<br>⇒ gRPC fallback 表（`InvalidArgument→5003` … default `1015`）与**判定顺序（先 `CodeError` 再 status）**逐条对齐 B 侧 | ✅ **一致**（**逐域隔离** · 不与其他域的中文文案统一） | **L1 源码逐条对照** + `MessageErrorContractTest` **13 例** | 2026-11-02 |
| **A7** | proto 契约 | 根级 `proto/`（D7 集中化）· pb 包名 `com.rag.proto.message` ⇒ **不在本域文件数内**<br>⇒ 本域的 9 个 gRPC 方法入参出参**就是 proto** ⇒ 领域层与 gRPC 层**零中转** | ✅ **一致** | **L1 逐字节 diff**（`.proto` 内容与 B 侧一致；`java_package` 属表达层） | 2026-11-02 |
| **A8** | DDL 时间列 / 类型 | **V1~V5 逐字节复制**（`cmp` ×5 = IDENTICAL）⇒ 含 **V5 `TIMESTAMPTZ`**（`ALTER COLUMN ... TYPE ... USING ... AT TIME ZONE ...`）与 **V4 `uk_conversation_pair`** 唯一索引<br>⇒ **真 PG 16** 容器实测（**H2 认不了 `TIMESTAMPTZ` / `JSONB` ⇒ H2 上「跑绿」是假绿**）<br>⇒ **独立账本** `flyway_schema_history_message` ＋ `baseline-version: 0` | ✅ **一致**（含时间列类型与独立账本） | **L3 真 PG 活体**（12 例）＋ **L1 字节对照**（`cmp` ×5） | 2026-11-02 |
| **A9** | trace / otel | 共享层 `common/observability/`（`GoStyleOtelAgent` / `ObservabilityLifecycle` / `RpcStatInterceptor` / `GoStyleRequestDeadline` …）⇒ 本域**零重复**<br>⇒ 本域的 9 个 `observeRpc` 埋点落点**与 B 侧一致**（**都在领域层** ⇒ **gRPC 层 0 = 0**）<br>⇒ ⚠️ 共享层 `GoStyleOtelAgent` 的 javadoc 明写「B 的合法 Batcher 集合 = [otlphttp, otlpgrpc, zipkin, file]，其余三种本仓未实现」 | ✅ **一致**（本域零重复；埋点落点与 B 侧逐条相等） | **L1 源码逐条对照**（`observeRpc` **9 = 9** · gRPC 层 **0 = 0**） | 2026-11-02 |

---

## 4. 域侧能自修的（**本域直接修**，不甩给收口）

| # | 项 | 状态 | 证据 |
|---|---|---|---|
| 4-1 | **`application.yml` 的 `grpc.client` 段只有注释、没有条目** ⇒ A4 红 + 上下文起不来 | ✅ **已修** | 配套报告 §五·5-③ |
| 4-2 | **`application.yml` 的 Nacos 段缺 `register-enabled: false` / `namespace` / `group`** ⇒ gRPC 链路断 | ✅ **已修** | 配套报告 §五·5-④ |
| 4-3 | **`NacosRegistration` 的 group 写死** ⇒ `NACOS_GROUP` 环境变量无效 | ✅ **已修**（读配置 · 默认值零变化） | 配套报告 §五·5-⑤ |
| 4-4 | **`MessageDomainService` 3 个 private `doXxx` 参数名写成 `req`** ⇒ 编译不过 | ✅ **已修**（改回 B 侧的 `in`） | 配套报告 §五·5-① |
| 4-5 | **`MessageApiService` 的 ambiguous import**（dto / proto 同名）⇒ 编译不过 | ✅ **已修**（只 import proto 版 + dto 版全限定名） | 配套报告 §五·5-② |
| 4-6 | **入口类缺 `@ConfigurationPropertiesScan`** ⇒ 两把密钥的 fail-closed 守卫与 `message.*` 全失效 | ✅ **已修** | 配套报告 §五·5-⑨ |

---

## 5. 需转派**「合并收口执行者」**的（**本域只登记：现象 + 证据 + 建议修法**）

| # | 项 | 现象 | 证据 | 建议修法 | 严重度 |
|---|---|---|---|---|---|
| 5-1 | **`JsonbMapTypeHandler` 应上收到 `common/`** | 共享层只有 jsonb↔String 与 text↔Map 两份，**类型都不兼容** ⇒ 域内被迫留副本 | 本域 `mapper/JsonbMapTypeHandler.java`（154 行）· 共享层 `common/persistence/` | ✅ **已完成（2026-11-03 · D-11 · `global` 收口域 · `DRIFT-COMMON-V19`）** —— 共享层新增 `common/persistence/JsonbMapTypeHandler.java`（照抄 B 侧）＋ **本域副本已删** ⇒ 本域主代码 **53 → 52** | ✅ 已闭合 |
| 5-2 | **跨域注册名不一致** | 本域 gRPC client 寻址 `discovery:///admin-rpc`（B 侧逐字）⇒ 找 Nacos 服务名 **`admin-rpc`**；而已落地的 `service/users/admin` 注册名是 **`admin-service`** ⇒ **寻址落空** | 本域 `client/AdminRpcClient.java`（`@GrpcClient("admin-rpc")`）· `service/users/admin/.../wiring/NacosRegistration.java:115`（`serviceName = "admin-service"`） | **归 users/admin lane**：注册名与 B 侧对齐为 `admin-rpc`，或本域改寻址 `admin-service`（**须先定哪一边是契约** ⇒ B 侧是 `admin-rpc`） | 🔴 P1 |
| 5-3 | **`docs/DRIFT/_INDEX.md` 未收录本域台账** | 本域台账已写好，但 `_INDEX.md` 本 lane **不碰** | 配套报告 §八·8.2 · U-5 | 由收口执行者统一更新 | 🟢 流程 |
| 5-4 | **`docs/Intentional_Deviations/_INDEX.md` 未收录 8 条偏离** | 本域已写清 8 条（配套报告 §六） | 同上 | 由收口执行者统一更新 | 🟢 流程 |

---

## 6. 未验证项（本轮明确留下的缺口，**不写成已闭环**）

| # | 项 | 为什么未验 | 什么时候能验 |
|---|---|---|---|
| 6-1 | **双侧活体对拍**（本域 ↔ B 侧同条件） | 本轮**跑的是「B 侧期望值移植到 C 侧」的探针**（B 侧 `*ParityTest` / `*TimeColumnParityTest` / `*SemanticEquivalenceTest` 的**断言与期望值逐字沿用 B 侧**）⇒ 比「只跑 C 侧探针」强一档（期望值不是本域自拟的）⇒ 但**仍不是「B 侧活体跑出同一读数」** ⇒ AGENTS.md §3.3 的告诫依然成立：它证明「C 侧符合 B 侧的既定期望」，**证不了「B 侧现在就是那个行为」** | 需在 B 侧起同条件探针（B 侧是 Boot 4 + go-zero 服务链，起真环境需 etcd/Nacos/PG） |
| 6-2 | **跨域 gRPC 链路（message → user-rpc / admin-rpc）** | 需要真 Nacos + 真 user-rpc / admin-rpc 服务 | §五·5-2 的注册名不一致修好之后 |
| 6-3 | **HTTP 响应头的字节形状**（`Content-Type` / `nosniff` / 状态行） | 本域测试走**真 Tomcat**（随机端口），但**未逐端点断言响应头字节面** | 补一条真 Tomcat 契约测试（**不用 MockMvc**） |
| 6-4 | **验签的 3s 预算边界**（A3 轴） | 🔴 **本轮已把「预算本身」补上**（§五·5-⑩：`observability.http-timeout: 3s`）⇒ 预算**存在**了；<br>剩下的是**验签次数面**：C 侧验签只执行 1 次（预算内），B 侧执行 2 次（Filter 预算外 ＋ interceptor 兜底预算内）⇒ 要对齐需新增独立 Servlet Filter ⇒ 属**形态改动**，需走决策流程 | 关联决策（见配套报告 §六·6-6） |
| 6-5 | **变异证据** | 本域断言**未配变异实验** ⇒ 「断言不是假绿」只做到**机制腿**（真 PG / 真 Tomcat / 真 Flyway），未做到**变异腿** | 参照仓内 P-12 方法论补 |

---

## 7. 交付物清单（**本域侧**）

| 项 | 文件 | 状态 |
|---|---|---|
| 域级审核报告 | [`docs/DRIFT/message/verification-message-service-2026-11-02.md`](verification-message-service-2026-11-02.md) | ✅ 已写 |
| **本文件**（跨层对账） | `docs/DRIFT/message/vs-common.md` | ✅ 已写 |
| 共享层能力消费面 | 12 个 `com.rag.common.*` 类 · **零空壳** | ✅ 已核 |
| 域内零副本核对 | `common/web/StringInt64` · `common/contract/ApiResponse` · `common/web/RouterErrorResponses` | ✅ 已核 |
| 待收口登记 | `JsonbMapTypeHandler`（§二·2.1） | ✅ 已登记 ⇒ ✅ **已收口（2026-11-03 · D-11）** |
| 守门读数 | A1–A13 **PASS 10 · FAIL 0 · N/A 2 · PENDING 1**（PENDING 属 like/comment/favorite）· **EXIT=0** | ✅ 2026-11-02 |
| 回归读数 | 本域 **103 例 · 0/0/0**（含 **35 例 B 侧期望值移植**）；全仓 **451 例 · 0/0/0** · **BUILD SUCCESS** | ✅ 2026-11-02 |
