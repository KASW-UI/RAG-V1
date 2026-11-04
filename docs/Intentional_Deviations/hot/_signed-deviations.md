# Intentional_Deviations / hot —— 已签字偏离

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**（或**是既定决策的机械后果**）的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-04**（逐行核对 B 侧源文件 + 双侧活体对拍读数，非估算）· **依据基准 2026-11-04**：**B 侧为唯一真理**（D16）
>
> **本文件归属**：`service/hot/`（`hot-service`）域。
> ⚠️ **只写自己域**：`common/` 与 `users/users-common/` 的偏离不在本文件（各有其主）。
> ⚠️ **不碰 `_INDEX.md`**（AGENTS.md §五.2 / Worktree.md §三.1：三个 `_INDEX.md` 合并后统一更新）。
>
> **产出坐标**：`align/hot` 工作树（`/Users/pr/Documents/GitHub/RAG-V1.worktrees/hot`）· 基线 commit `7dbc283`。
>
> **📌 本文件的条目计数（核对日期 2026-11-05）**：共 **6 条编号**（DEV-HOT-001~006）⇒ **5 条仍生效** ＋ **1 条已修复**（**DEV-HOT-002**，2026-11-05 域内方向补回 2s 上界 ⇒ 差异消除 ⇒ 转历史登记）。
> ⇒ 对应 DRIFT 台账 [`../../DRIFT/hot/verification-hot-service-2026-11-03.md`](../../DRIFT/hot/verification-hot-service-2026-11-03.md)
> 的 **F-01 / F-02 / F-03 / F-12 / F-14** ＋ §四·4.6 里登记的 **Nacos namespace/group 部署面差异**。

---

## DEV-HOT-001 · HTTP 端口：`18897 / 8893` → `8895`（L1 · 部署面 · D1 的必然结果）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **已登记**（**D1**「api + rpc 合并压平」的机械后果 · 2026-10-26 用户定） |
| **分域** | hot 域（`service/hot/`） |
| **偏离类型** | **有意偏离**（L1 · 部署面 / 端口） |
| **提出 / 签字** | D1 决策（2026-10-26）⇒ 本条是它的**机械后果**，非本域自创 |

### 偏离内容

| | **B 侧（RAG 仓）** | **RAG-V1（C）** |
|---|---|---|
| 业务 HTTP 口 | `hot-api`：`server.port = ${HOT_API_PORT:18897}` | `server.port = ${HOT_HTTP_PORT:8895}`（**合并后单 HTTP 口**） |
| 第二个 web 口 | `hot-rpc`：`server.port = 19091`（**只承载 Actuator `/metrics`，无业务路由**） | **无**（与业务口合并；Actuator 走 `/actuator/**`） |
| Go 真源 | `service/hot/api/etc/hotcenter.yaml` 的 `Port=8893`（**B 侧 Java 侧未对齐** ⇒ B 侧自身就有 18897 / 8893 两套值） | — |

### B 侧出处（逐行核对）

- B 侧 `service/hot/hot-api/src/main/resources/application.yml` —— `server.port: ${HOT_API_PORT:18897}`
- B 侧 `service/hot/hot-rpc/src/main/resources/application.yml` —— `server.port: 19091`
- B 侧 Go 真源 `service/hot/api/etc/hotcenter.yaml` —— `Port: 8893`（⇒ **B 侧内部就有两个值** ⇒ C 侧取哪一个都不是「回溯第三方」）

### RAG-V1 落点

- `service/hot/src/main/resources/application.yml` —— `server.port: ${HOT_HTTP_PORT:8895}`

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **业务语义 / 错误契约 / 响应形状** | ✅ **零影响**（端口号不参与任何业务判定） |
| **HTTP 字节面** | ✅ **零影响** —— 端口换了，但状态行 / `Content-Type` / body **逐字节相同**（双侧活体对拍 **18 条** HTTP 断言全绿） |
| **部署** | ⚠️ 部署时用 `HOT_HTTP_PORT` 覆盖即可（默认值只是默认值） |

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖 HTTP 端口号**；**gRPC 端口**（`8083`）**逐字沿用 B 侧**，不在本条内。
- **不得**为了「和 B 侧端口一样」而改回 `18897` —— 那会把 D1 的合并结果拆回去。

---

## DEV-HOT-002 · D2 域内直连 ⇒ **域内方向没有出向上界**（L2 · 超时面）· **✅ 2026-11-05 已修复 ⇒ 差异消除**

| 项 | 内容 |
|---|---|
| **状态** | ✅ **已修复**（2026-11-05）—— 域内方向**补回 2s 出向上界** ⇒ 与 B 侧**形状逐条一致**；本条转为**历史登记**（保留原文，供追溯）|
| **分域** | hot 域（`service/hot/`） |
| **偏离类型** | 原为 **有意偏离**（L2 · 业务语义层的**超时面**）⇒ **现已消除** |
| **提出 / 签字** | D2 决策（2026-10-26）⇒ 本条是它的**连带后果**，非本域自创；**2026-11-05 用户指令「修」⇒ 消除** |

### 偏离内容（**当时**）

B 侧 hot-api 调 hot-rpc 走**自我 gRPC 往返** ⇒ 该方向**也**受共享层出向封顶
（`rag.protection.outbound-deadline.cap-millis: 2000`）约束。
C 侧按 **D2** 把这条自我往返**改成本地方法直连** ⇒ **该方向不再经过任何出向拦截器** ⇒ **没有 2s 上界**。

### 🔴 修复前的实测读数（**历史** · 2026-11-04 · `tools/difftest/hot/run.sh` 的 M5 面）

| | **B 侧** | **C 侧** |
|---|---|---|
| 域内方向的上界 | **2s**（共享层 gRPC 出向封顶） | **无**（D2 直连 ⇒ 唯一兜底是 HTTP 预算 **3s**） |
| Redis 卡住时的**失败时延** | **2011ms** | **3014ms** |
| Redis 卡住时的**失败形状** | **`HTTP/1.1 200 ` ＋ `application/json` ＋ `{"code":5001,"msg":"internal server error","data":null}`** | **三候选集合**（非确定）|

> ⚠️ **C 侧的形状是「竞态」⇒ 非确定**（实测四轮命中过全部三种）：
> ① `200` ＋ `application/json` ＋ 5001 信封（干净）；
> ② `200` ＋ `application/json` ＋ body **`Request Timeout\n`**（**混血** ⇒ **信封契约被打断**）
> —— 根因是**共享层**的「HTTP 预算 × 业务失败」竞态 ⇒ **归属收口域**（见 DRIFT 台账 **F-15**）；
> ③ `503` ＋ `text/plain` ＋ `Request Timeout\n`（超时分支的干净形状）。
> ⇒ ⇒ **「C 侧会拿到哪种形状」客户端无法预知**。

### ✅ 修复落地（2026-11-05 · **向 B 侧对齐**）

| 项 | 落地 |
|---|---|
| **上界从哪来** | B 侧的 2s 封顶在**共享层的 gRPC 出向拦截器**上 ⇒ **D2 域内直连不经它** ⇒ ⇒ C 侧**域内自持**一个同值上界：配置键 `hot-api.domain-query-timeout`（env `HOT_API_DOMAIN_QUERY_TIMEOUT_MS`，默认 **2000** = **B 侧同一个数**）|
| **落点** | `service/hot/src/main/java/com/rag/hot/service/HotApiService.java` 的 `callDomainQuery()`：`CompletableFuture.supplyAsync(..., domainQueryExecutor).orTimeout(2000ms).join()` ⇒ 超时抛 `CompletionException`（**是 `RuntimeException`**）⇒ ⇒ 落进 `fetchTopK` 那个**放宽后的 catch** ⇒ ⇒ `hot_api_rpc_error_total{callee="hot_rpc_get_hot_articles"}` **照常 +1**（指标语义与 B 侧一致）|
| **执行器** | 新增 `service/hot/src/main/java/com/rag/hot/config/HotDomainQueryConfiguration.java`（bean `hotDomainQueryExecutor`）⇒ Spring `SimpleAsyncTaskExecutor` 的**虚拟线程**模式 ⇒ **不排队**（普通池排队 ⇒ 排队时间算进超时预算 ⇒ 偏离 B 侧 gRPC「直发」）；**不能用** `hotHydrationExecutor`（CallerRuns ⇒ 超时永远等不到 ⇒ 上界形同虚设）|
| **超时之后** | 原线程**继续跑**（Redis 读继续到自己的 socket timeout）⇒ ⇒ 与 B 侧**同形**（B 侧 gRPC deadline 到点后客户端放弃，hot-rpc 服务端那边照样在跑）⇒ 两侧都是「客户端先放弃、后台继续」|
| **形状变化** | C 侧失败**拉回 2s** ⇒ 早于 HTTP 预算 3s ⇒ ⇒ **不再撞上**「HTTP 预算 × 业务失败」的竞态 ⇒ ⇒ **失败形状回到 `200` ＋ 5001 业务信封**（= B 侧**逐条一致**）⇒ ⇒ 「503 + 纯文本」与「混血响应」两个候选**消失** |

> ⚠️ **F-15（共享层竞态）本身仍在** —— 本域只是**不再触发**它（触发条件 = 「域内方向无上界」与「HTTP 预算同时到点」）⇒ ⇒ **共享层缺陷仍归 `global` 收口域**。

### B 侧出处（逐行核对）

- B 侧 `service/hot/hot-api/src/main/resources/application.yml` —— `rag.protection.outbound-deadline.cap-millis: 2000`
- B 侧共享层 `GoStyleOutboundDeadlineInterceptor`（`@GrpcGlobalClientInterceptor` ⇒ 装在**每个**出向 devh 调用上）
- B 侧 `service/hot/hot-api/.../api/service/HotArticleQueryService.java` —— `fetchTopK(...)` 走 `HotRpcClient`（自我 gRPC 往返）
- B 侧 `service/hot/hot-api/src/main/resources/application.yml` —— `observability.http-timeout: 3s`（go-zero `rest.RestConf.Timeout` 默认 3000）

### RAG-V1 落点（**修复后**）

- `service/hot/src/main/java/com/rag/hot/service/HotApiService.java` —— **直接注入** `HotRankingQueryService`（**无** `HotRpcClient` ⇒ D2）＋ `callDomainQuery()` 承担 2s 上界
- `service/hot/src/main/java/com/rag/hot/config/HotDomainQueryConfiguration.java` —— 虚拟线程执行器（**不排队**）
- `service/hot/src/main/java/com/rag/hot/config/HotApiProperties.java` —— `domainQueryTimeout`（默认 2000）
- `service/hot/src/main/resources/application.yml` —— `hot-api.domain-query-timeout`（**新增键**）＋ `observability.http-timeout: 3s`（**B 侧逐字保留**）
- 共享层 `common/observability/HttpMiddleware.java:438-448` —— 预算耗尽 ⇒ `writeTimeoutResponseQuietly(503)` ⇒ `Request Timeout
`（**未动**）

### 影响面（**修复后**）

| 项 | 结论 |
|---|---|
| **正常路径** | ✅ **零影响**（多一次虚拟线程调度 + 一个 `orTimeout` ⇒ 微秒级）|
| **Redis 卡住 / 变慢** | ✅ **失败时延 ~2s** ＋ **失败形状 = 与 B 侧逐条相同**（`200` + 5001 信封）⇒ **客户端不再需要区分两种失败** |
| **可重试语义** | ✅ **与 B 侧一致**（两侧都是「HTTP 200 + 业务码 5001」⇒ 客户端按业务码处理）|

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖「域内方向的上界与失败形态」**；**出向 article-rpc 方向**的上界见 **DEV-HOT-004**（承载键位不同，但**有效值相同** 2000ms）。
- 本条**不豁免** L1 的其它部分：proto / 错误码值 / HTTP path / Kafka topic **仍须逐字对齐 B 侧**。
- ⚠️ **不得**为了让两侧一致而改 C 侧的 HTTP 预算（`observability.http-timeout: 3s`）—— 那会动 L1 配置键的语义。

---

## DEV-HOT-003 · form 解析口径：域内自持 `int32` / `firstFormValue`（L4 · 表达面）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **已登记**（**D3/D5**「共享能力上收 `common/`」的机械后果） |
| **分域** | hot 域（`service/hot/`） |
| **偏离类型** | **表述差异**（L4 · 表达层 · **不统一化**） |
| **提出 / 签字** | D3/D5 决策（2026-10-26）⇒ 本条是**上收后的必然拆分** |

### 偏离内容

B 侧 `hot-api/.../form/GoFormValues.java` 一个类里含 Go 语义的 form 解析
（`int32` 强转 ＋ `firstFormValue` ＋ 2048 非空值计数 ＋ 10000 段上限）。
C 侧上收共享层后**拆成两处**：

| | **B 侧** | **RAG-V1（C）** |
|---|---|---|
| 段数上限 + 2048 非空值计数 + 文案 | `GoFormValues`（域内） | 共享层 `common/web/GetFormValuesFilter` |
| `int32` 强转 ＋ `firstFormValue` | `GoFormValues`（域内） | 域内 `service/HotApiSupport.java` |

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **可观察行为** | ✅ **零影响** —— 共享层闸门判据与 B 侧**逐条一致**（10000 段先判 → 再判 2048 非空值 → 文案逐字）；域内自持的两方法由 `HotApiSupportContractTest`（9 例，含 int32 乘法溢出钉子）钉死 |
| **残留差异** | ⚠️ 「10001 段时 body 取的是 2048 那条（顺序反了）」—— 那是**共享层**层面的既有差异，**非本域引入**（⇒ 归 `global` 收口域） |

### ⚠️ 边界声明（不得越界）

- **不得**把 `int32` / `firstFormValue` 上收到共享层 —— 那是**收口域（`global`）**的活，**不在本域写权内**。
- **不得**把两个 query 参数的解析口径与其它域「统一化」⇒ AGENTS.md §八「未统一化」。

---

## DEV-HOT-004 · 出向上界的**承载键位**：共享层 `rag.protection.outbound-deadline.cap-millis` → 域侧 `hot-api.article-rpc.timeout`（L1 配置键 · 配置面）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **已登记**（**cap 单一来源纪律** ⇒ 2026-11-02 D14 收口轮的机械后果） |
| **分域** | hot 域（`service/hot/`） |
| **偏离类型** | **配置键位差异**（L1 配置键名层 · **有效值相同**） |
| **提出 / 签字** | cap 单一来源纪律（B 侧共享层自带棘轮判据 `GoStyleClientCallTimeoutSingleSourceGateTest` 判据④） |

### 偏离内容

| | **B 侧** | **RAG-V1（C）** |
|---|---|---|
| 承载键位 | `rag.protection.outbound-deadline.cap-millis: 2000`（**共享层键**） | `hot-api.article-rpc.timeout: 2000`（**域侧键**） |
| 消费点 | 共享层 `GoStyleOutboundDeadlineInterceptor`（`@GrpcGlobalClientInterceptor`） | 域内 `ArticleRpcClient#stubWithDeadline`（**唯一出向出口**） |
| 覆盖范围 | **两个**出向方向（hot-rpc ＋ article-rpc） | **只管 article-rpc 方向**（域内方向是 D2 直连 ⇒ 无出向调用） |
| 有效值 | **2000ms** | **2000ms**（**相同**） |

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **article-rpc 方向的出向上界** | ✅ **有效值相同**（2000ms）⇒ 行为等价 |
| **域内方向的上界** | ⚠️ **覆盖范围收窄** ⇒ 后果**已单列**为 **DEV-HOT-002**（不重复计数） |
| **纪律** | ✅ 共享层 cap **刻意保持未配置**（默认 0 = 不启用）⇒ **单一来源**；由 `HotServiceApplicationTests#outboundDeadlineCapHasSingleSource` **运行期断言**锁住（共享层为 null ＋ 域侧为 2000） |

### ⚠️ 边界声明（不得越界）

- ⚠️ **绝不可同时配两处**（那会造出「两个上界来源」⇒ 行为不可预期）⇒ 已有运行期断言守门。
- 本条**不豁免**「出向上界必须存在」这件事 —— **上界还在**（只是承载键位换了）。

---

## DEV-HOT-005 · Nacos `namespace` / `group` 对齐**本仓已落地域**（部署面 · 非行为面）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **已登记**（B 侧那两个值**在 B 侧不承载行为** ⇒ C 侧对齐本仓已落地域才让跨域链路成立） |
| **分域** | hot 域（`service/hot/`） |
| **偏离类型** | **部署面差异**（**非行为面**） |
| **提出 / 签字** | 本域 lane（2026-11-01 收口轮 · R1-ENV-001 同款口径） |

### 偏离内容

| | **B 侧** | **RAG-V1（C）** |
|---|---|---|
| `namespace` | `public` | `${NACOS_NAMESPACE:sea-rtw}` |
| `group` | `DEFAULT_GROUP` | `${NACOS_GROUP:DEFAULT_GROUP}`（**同值**） |
| 服务注册名 | `hot-rpc` | `hot-rpc`（**逐字相同**） |

### 为什么「不是行为漂移」（B 侧那两个值**不承载行为**）

- B 侧 hot → article 的 gRPC 调用走 **etcd key `article.rpc`**（`zrpc.RpcClientConf{Etcd:{Key:"article.rpc"}}`）
  ⇒ **etcd 没有 namespace / group 概念** ⇒ ⇒ B 侧那两个值**不参与寻址**。
- C 侧 `grpc.client.article-rpc` 走 **Nacos discovery**（`discovery:///article-rpc`）
  ⇒ **namespace / group 真的参与寻址** ⇒ 若沿用 `public` ⇒ 本仓已落地的 article 域
  （注册名 `article-rpc` · namespace `sea-rtw` · group `DEFAULT_GROUP`）**在 hot 域的寻址空间里根本不存在**
  ⇒ ⇒ gRPC 链路直接断 ⇒ 业务码塌成 1015。
- ⇒ ⇒ **对齐到本仓已落地域的取值 ⇒ 跨域链路成立**。

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **业务语义** | ✅ 零影响（B 侧那两个值本就不承载行为） |
| **跨域链路** | ✅ **必需**（不这样对齐，article-rpc 不可达） |
| **注册名 / 端口** | ✅ 逐字沿用 B 侧（`hot-rpc` ＋ gRPC 端口 `8083`） |

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖 namespace / group 两个键**；**注册名**与**端口**逐字沿用 B 侧 ⇒ 不在本条内。
- ⚠️ 本条**不是**「可以随便改 namespace」—— 改动必须**跟着本仓已落地域**走。

---

## DEV-HOT-006 · Actuator **单向增强**：5 个 `hot_api_*` 指标**真的被暴露**（L4 · 表达面 · 只增不改）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **已登记**（**有意单向增强** —— 防止下一个人误以为 C 侧「漏了指标暴露」） |
| **分域** | hot 域（`service/hot/`） |
| **偏离类型** | **单向增强**（L4 · 表达层 · **只增不改**） |
| **提出 / 签字** | 本域 lane（2026-11-03 迁移审核轮） |

### 偏离内容

| | **B 侧** | **RAG-V1（C）** |
|---|---|---|
| `hot_api_*` 5 个指标 | 注册进了 registry，但 **Prometheus 块 Host 为空 ⇒ `promhttp.StartAgent` 提前 return**；`DevServer` 块在 go-zero v1.10.2 里没有对应字段 ⇒ **没有任何端点暴露它们** | 接 Actuator（`management.endpoints.web.exposure.include: health,info,prometheus`）⇒ **它们真的出现在 `/actuator/prometheus`** |

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **指标名 / 标签取值域** | ✅ 逐条核对（`HotApiMetrics` / `HotRpcMetrics`）⇒ **零变化** |
| **业务语义** | ✅ 零影响（**只增不改**） |
| **真读到计数** | ✅ `HotEventConsumerRealKafkaTest` 真读到 `article_hot_kafka_consume_total{type}` 的计数 |

### ⚠️ 边界声明（不得越界）

- 本条**不豁免**「指标值必须与 B 侧一致」⇒ 值仍然逐条对齐，**只是多了一个暴露口**。
- ⚠️ **不得**反过来把 Actuator 的暴露面**收窄**回 B 侧那样（那会丢掉 Java 侧的观测能力）。

---

## 引用

- 决策台账 → [`../../决策记录.md`](../../决策记录.md)（**D1 · D2 · D3 · D5 · D14**）
- 漂移档（逐条漂移与修复记录） → [`../../DRIFT/hot/verification-hot-service-2026-11-03.md`](../../DRIFT/hot/verification-hot-service-2026-11-03.md)
- 两方对照（抄写路线图） → [`../../Mapping_Table/_INDEX.md`](../../Mapping_Table/_INDEX.md)
- 踩坑档 → [`../../Pitfalls/hot/_hot-domain-pitfalls.md`](../../Pitfalls/hot/_hot-domain-pitfalls.md)
- 降级档 → [`../../L3-降级清单.md`](../../L3-降级清单.md)
- **B 侧台账（原始逐条来源下沉处）** → `/Users/pr/Documents/java&golang/RAG` 的 `docs/Intentional_Deviations/`（本域的每条都逐行核对了 B 侧源码，**未搬结论**）
