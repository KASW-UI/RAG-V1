# `common/` 迁移审核报告 —— RAG → RAG-V1

> **审核协议**：`.agents/verification.md`（八阶段：范围 → 承接清单 → 行为审核 → 框架差异 → 合并拆分 → 对拍 → 状态 → 报告）。
> **审核日期**：2026-11-02 · **审核对象**：`common/`（starter，**只审核不改代码**）· **本次未修改任何源码 / POM / 配置 / Mapping_Table / File_Tree**。
> **状态口径**（§十）：`PASS` / `FAIL` / `NOT_IMPLEMENTED` / `NOT_VERIFIED` / `FRAMEWORK_DIFFERENCE` / `BLOCKED` / `INTENTIONALLY_CHANGED`。
> ⚠️ **四种非 PASS 状态一律不计入 PASS**（§十）。
>
> ✅ **修复批次（2026-11-02 · 同日）**：用户选定「修 V01 + V02 + 扩守门扫描范围」⇒ 已修（修法见 §11.5 各条的「✅ 修法」）
> ⇒ **本报告的状态列已按修复后的实况更新**（V01 / V02 由 FAIL 转 PASS）；§11.6 的数字**从 §11.2 表逐行重数**，非手填。
>
> ✅ **修复批次 2（2026-11-02 · 同日 · 批次 A）**：**V05**（熔断回落语义）+ **V06**（窄接口职责断裂）⇒ 已修
> ⇒ 🔴 FAIL 3 → 1。**口径订正**：用脚本重数 HEAD 版的 §11.2，发现**旧表自报数与自己的表差 2**（旧报 89 行/91 标记/NOT_IMPLEMENTED 30 ⇒ 实测 91/93/32）⇒ 已按实测重写（根因见 §11.6）。
>
> ✅ **修复批次 3（2026-11-02 · 同日 · 批次 B）**：**V03**（gRPC 服务端保护链 5 层 ＋ 客户端熔断 ＋ 出向 deadline ＋ Prometheus 写入面 ＋ 失败判据 ＋ 链序）⇒ 已修
> ⇒ 🔴 FAIL **1 → 0**（**清零**）。⚠️ 伴随暴露并修掉一个**真实回归**：`@ConditionalOnMissingBean` 按**返回类型**判缺失 ⇒ 新增 5 个 `ServerInterceptor` 后**业务观测层被静默挤掉** ⇒ 改按 bean 名判 ⇒ 教训登记 `docs/Pitfalls/` **P-11**。
>
> ✅ **修复批次 4（2026-11-02 · 同日 · 批次 D）**：**V07**（`common/security/` 3 类：JWT 签发验签 / Bearer 提取 / bcrypt 口令）⇒ 已修（抄 RAG ＋ jjwt 0.12.6 适配）
> ⇒ ⚠️ **有意与 B 侧不同的一处**：B 侧的 `length() > 7` vs 上游的 `len(tok) > 6` ⇒ 当时按上游改（**L1 以上游为准**）⇒ **D16 后基准改为 B 侧** ⇒ 本条转为**签字偏离**（C ≠ B，已在 javadoc 留痕，登记见 `docs/Intentional_Deviations/global/`）。
>
> ✅ **修复批次 5（2026-11-02 · 同日 · 批次 E）**：`MaxConnsFilter`（并发闸门，**推翻「Tomcat 原生项 ⇒ 零 Java 代码」的旧口径**）＋ `MqConsumer`（MQ trace）＋ `ObservabilityRuntime` + `ObservabilityLifecycle`（两个池 ＋ 关闭钩子）＋ `RpcHandleRunner` / `RpcResult` / `RpcCallHandler`（非 gRPC 路径）＋ `LoggerFactoryBean` + `LoggerAutoConfiguration`（共享日志门面）＋ `GoStyleOtelConfig` 的 3 个字段 ⇒ 已修
> ⇒ **NOT_IMPLEMENTED 13 → 4**（只剩 **3 条 V04 同族** + **1 条 D-03 明确不实现**）。common 测试 **126 → 164 例**，**3 次变异实测**每次都变红。
>
> ✅ **修复批次 6（2026-11-02 · 同日 · 批次 C）**：🔴 **DRIFT-COMMON-V04**（HTTP 超时 503 ＋ 断连 499 ＋ 512 worker 池 ＋ `ClientDisconnectProbe`）⇒ 已修
> ⇒ **NOT_IMPLEMENTED 4 → 1**（只剩 `GoStyleOtelAgent`，**D-07 明确不实现**）。common 测试 **164 → 185 例**，**4 次变异实测**每次都变红。
> ⚠️ **其中一次变异「探针断连 499→500」最初<b>没有</b>变红** ⇒ ⇒ 说明那条分支当时<b>没被覆盖</b> ⇒ ⇒ 补了**探针接缝**（`HttpMiddleware.DisconnectProbe`，仅单测注入）⇒ ⇒ 再变异 ⇒ **变红** ⇒ ⇒ **「变异没变红」＝「这条断言没覆盖到」**。
>
> ✅ **修复批次 7（2026-11-02 · 同日 · 批次 F）**：🔴 **最后 1 条 NOT_IMPLEMENTED**（`GoStyleOtelAgent`）＋ **2 条 FRAMEWORK_DIFFERENCE**（`GoStyleOtelAutoConfiguration` / `GoStyleTraceparentFilter`）⇒ 已修
> ⇒ 新增 `observability/GoStyleOtelConfig` ＋ `observability/GoStyleOtelAgent` ＋ `autoconfigure/GoStyleOtelAutoConfiguration`（**BFPP 装配点**，登记进 `AutoConfiguration.imports`）⇒ 抄 RAG 整段（L2 · D13）
> ⇒ **NOT_IMPLEMENTED 1 → 0**（**清零**）· **FRAMEWORK_DIFFERENCE 2 → 0**。common 测试 **185 → 208 例**，**7 次变异实测**每次都变红。
> ⚠️ **连带修掉一个真实缺陷**：本模块在两个 devh starter 上 exclude 了 `grpc-core` ⇒ Maven 的 exclusion 作用于整棵传递子树 ⇒ `grpc-netty-shaded → grpc-core` 也被切断 ⇒ classpath 上没有 `grpc-core` ⇒ 只要 devh 的 gRPC server auto-config 出现就炸 `NoClassDefFoundError: io/grpc/internal/ObjectPool` ⇒ 已按域模块同款口径**显式声明** `grpc-core`（**test scope**，不传给下游）。
> ⚠️ **口径变更（需知悉）**：上游 `Telemetry` 段的 3 个字段原先挂在 `ObservabilityProperties`（`observability.*` 命名空间）但**只承载、不驱动装配** ⇒ 本轮**移出**该类、归位到 `GoStyleOtelConfig`（B 侧同名类的 **1:1** 对应物）⇒ **键名随之回到 B 侧的 `otel.*`** ⇒ L1 配置契约与 B 侧逐字节同构。
>
> ✅ **修复批次 8（2026-11-02 · 同日 · 批次 G）**：🔴 **最后 1 条 NOT_VERIFIED**（`JsonbTypeHandler` / `JsonTextTypeHandler`）⇒ 已闭合
> ⇒ 新增 `common/src/test/java/com/rag/common/persistence/JsonTypeHandlerPgTest.java`（**真 PostgreSQL 集成测试 · 10 例**）＋ `common/pom.xml` 补 **Testcontainers（test scope）**
> ⇒ **NOT_VERIFIED 1 → 0**（**清零**）· **PASS 87 → 88**。common 测试 **208 → 218 例**，**7 次变异实测**每次都变红。
> ⚠️ **为什么必须真 PG（mock / H2 都验不到要验的东西）**：这两个类的**全部业务语义都长在 PG 的列类型上** —— jsonb 列**规范化**键序与空白（读回文本 ≠ 写入原文）· 按 VARCHAR 绑定 jsonb 列 ⇒ PG 报 **42804** · jsonb 列**拒收** `\u0000` 转义 ⇒ **22P05** · **text 列照单全收** `\u0000` ⇒ 这正是「两个 handler 必须分开」的**唯一判据**（B 侧 `encoding/json` 产出的是 6 个 ASCII 字符 `\u0000`，**不是**裸 NUL 字节 ⇒ text 列必须收下 ⇒ 活体对拍证据见 B 侧 **DRIFT-ARPC-004①**）。
> ⚠️ **禁 `@EnabledIfDockerAvailable`**（AGENTS.md §五.8 守门脚本 4 硬规则 ③）⇒ Docker 不可用时**必须红**、不许静默跳过 ⇒ 守门脚本才能断言 `skipped=0`。
> ⚠️ **本轮无新缺陷**：两个 handler 的实现**未改一字节**（抄 RAG 整段 · L2 · D13）⇒ 本轮只补**证据**（此前是「有实现、无证据」⇒ 判 NOT_VERIFIED）。
>
> ✅ **修复批次 9（2026-11-02 · 同日 · 批次 H）**：🔴 **修掉一条被误判为「已登记降级」的真实漂移** ＋ **收口 V09 / V10**
> ⇒ 🔴 **REST 侧降载判据漂移**：C 侧的 REST filter 此前判据是「**只有 CPU 过载**」⇒ ⇒ **比 B 侧更早丢请求**（B 侧是「CPU 过载 **且** 在途超动态上限」**两个条件同时成立**才丢）⇒ ⇒ **B 侧 REST filter 本来就用完整的 `GoStyleAdaptiveShedder`** ⇒ ⇒ **C 丢了 B 已有的能力** ⇒ 按 §十 属 **FAIL**（不是已批准的降级）⇒ ⇒ **已修**：REST filter 改走 `AdaptiveShedder`（与 B 侧**逐行同构**）⇒ **双条件判据 + pass/fail 记账 + 浮点 RT 口径**回到 B 侧形态 ⇒ **D-04 撤销降级**。
> ⚠️ **旧登记的前提本身是错的，本轮一并纠正**（不掩盖）：D-04 / V10 原先都写「RAG 侧也只有 CPU 一半」⇒ **不成立**。
> ✅ **V09 收口**（登记）：HTTP 侧的熔断机制类型差异已扩写进 **D-02**，且**有效参数已实测并钉桩**（resilience4j 2.2.0 = COUNT_BASED / 100 / 100 / 50% / 60s / 10 ⇒ `HttpCircuitBreakerConfigPinTest` ⇒ 框架默认值变形 ⇒ 测试立刻红 ⇒ 逼着重登记）。
> ✅ **V10 收口**（登记）：新立 **D-08**「HTTP 侧降载的可观察面」⇒ ⚠️ **定性订正**：HTTP 侧统计面缺失是 **B 侧原生缺口**（B 的 REST filter 也没接 `SheddingStat`）⇒ **C ≡ B ⇒ 不是 C 的迁移缺陷** ⇒ 本仓**不改**（改了反而是未经批准的漂移）⇒ 但**必须登记**（否则对拍脚本会把它误判成回归 ⇒ 触发静默「修回」）。
> common 测试 **218 → 224 例**（新增 `CpuSheddingFilterTest` 4 例 ＋ `HttpCircuitBreakerConfigPinTest` 2 例），**变异实测 4 次每次都变红**（含「退回只看 CPU」这条**漂移形态**）。
>
> ✅ **批次 I（2026-11-02 · 同日）· 台账「状态自报 ≠ 现状」清理**：§11.5 的 14 条漂移条目**原先只在标题上挂严重度标记**，而 V01–V08 / V14 **早已修完**却仍顶着 🔴/🟠 ⇒ ⇒ 读者扫标题会以为还有 7 红 2 橙 ⇒ ⇒ **本轮给每条补「状态」行 + 把标题标记改成当前状态** ⇒ ⇒ 现状 = **✅ 已修 9 · ⚪ 已登记 4 · ⚪/🟠 治理待裁决 1（V11 域归属）· 🔴 0**。
> ✅ **2026-11-03（`global` 收口域）· V11 已裁决**：用户定「**行为侧 C ≡ B**（实测 favorite 两侧都走共享层）⇒ **不改代码归属**」
> ⇒ ⇒ **V11 从「治理待裁决」降为「✅ 已裁决 · 非漂移」** ⇒ ⇒ **AGENTS.md §2.3 挂着的 🔴 已解除**
> ⇒ ⇒ 现状 = **✅ 已修 9 · ✅ 已裁决 1（V11）· ⚪ 已登记 5（V10/V12/V13/V16/V17/V18 中除 V11 外）· 🔴 0**。
> ✅ 顺带**关闭 V08**：映射表 §7.4 #6 的 B 列「RAG 侧只有 6 个声明 `GrpcServerConfigurer`」**已过时**（实测：**10 个 rpc 应用全部**声明了）⇒ 且该行 C 列「全树 0 个」也已被批次 B 推翻 ⇒ ⇒ 已就地订正。
> ✅ **V11 定性**：B 侧本来就把它放在**共享层** ⇒ **C ≡ B ⇒ 行为侧无漂移** ⇒ 只登记治理张力（🏷 候选，建议签），**不升级为拍板**、**不阻塞**本次迁移。
> ✅ **2026-11-03（`global` 收口域 · 本轮）· V17 已修（PARITY-006 根因）**：在 `ObservabilityAutoConfiguration.GrpcObservationInterceptor` 的 listener **补 `onHalfClose()`**
> ⇒ ⇒ 在**真正跑 handler 的线程**上绑**入向** `DeadlineState`（绝对时刻 = 调用开始 ＋ `rpc-timeout`，与 B 侧 `RpcInterceptor.java:516-522` 同形）
> ⇒ ⇒ **在途 SQL 拿得到 `setQueryTimeout`** ⇒ ⇒ 预算到点 PG 收到 CancelRequest ⇒ 事务回滚 ⇒ ⇒ **在途写不再落库**
> ⇒ ⇒ **验收**：新 `GrpcDeadlineBindingTest`（3 例）＋ `GrpcInboundDeadlinePgTest`（**2 例 · 真 PG** · 服务端预算到点 / 客户端主动取消）⇒ **5 例全绿**；**变异实测** ⇒ 两条都**红在核心判据**（`expected: 0L but was: 1L`）⇒ ⇒ **判据精确、其余维仍绿** ⇒ 还原后同条件复跑回绿
> ⇒ ⇒ 现状 = **✅ 已修 10 · ✅ 已裁决 1（V11）· ⚪ 已登记 4（V10/V12/V13/V16/V18）· 🔴 0**。
> ⚠️ **诚实边界**：① **只绑入向载体** ⇒ 出向 `GoStyleRequestDeadline` **另案**；② 作用域随 `onHalfClose` 返回而结束 ⇒ **异步 handler 不在作用域内**（与 B 侧同款）；③ **跨域复测**（admin 域 E-4-R2 场景轮 ⇒ `admin_admin` 0 行）**待 admin 域 lane 落地**（本 lane 只写 `common/`）。
>
> ✅ **2026-11-05（`global` 收口域 · 本轮）· V16 已收口（保护层开关接线）**：B 侧 `GoStyleRpcProtectionChain.java:228/235` 用两个开关**决定这两层是否拼进链** ⇒ ⇒ **C 侧修前两个 Bean 恒在且不读开关** ⇒ ⇒ `Stat` 层**照记样本 ＋ 照打那条每 60s 的统计日志**、`Prometheus` 层**照写指标** ⇒ ⇒ **现行配置下两侧行为一致**（默认值同为 `true` ＋ 各域 yml 均未覆盖）⇒ ⇒ 本条闭合的是「**接线缺口**」，**不是**现行行为漂移
> ⇒ ⇒ **修法**：两层各在**构造期**接 `enabled` ⇒ 关掉时 **Stat 层不记样本 ＋ 不起那条 60s 上报线程**（B 侧那条线程的**唯一来源** = `GoStyleStatInterceptor.java:82` ⇒ 开关关掉时 B 侧也没有那行日志）、**Prometheus 层一条指标都不写** ⇒ ⇒ 与 B 侧「层不拼进链」**逐项等价**（沿用本类既有的 breaker / cpu-shedding 惯例 ⇒ 链的组成不变 ⇒ **客户端可观测面零差异**）
> ⇒ ⇒ **验收**：新 `ProtectionLayerSwitchWiringTest`（**4 例 · 真 Spring 装配 + 真 in-process gRPC 调用**）⇒ 4/4 绿；**变异两轮**：抽「配置→层」接线 ⇒ **2 红**（对照维仍绿）· 只抽「上报线程门」⇒ **1 红且红在 `reporterStarted()`** ⇒ 还原 4/4 绿
> ⇒ ⇒ **🔴 新增机器守门 A15**（保护层每个 `enabled` 开关都必须有消费点）⇒ 脚本**自己从属性类解析开关清单** ⇒ **将来新增的开关自动进清单**；⚠️ **判据先剥注释** ⇒ 本仓 javadoc 里大量引用 B 侧源码 ⇒ 不剥会**假绿**（fixture 里故意放了一句注释里的引用，自检仍判红 ⇒ 证明没被骗到）⇒ ⇒ **self-test 15/15 · 真仓变异实测**
> ⇒ ⇒ **全仓**：串行 `mvn -o test` ⇒ **BUILD SUCCESS · 16/16 模块 · 931 例 · 0/0/0**（common **247 → 251**）· 三道守门 **EXIT=0**（A15 已计入，共 15 条）· §11.2 状态表机器复算 **91 行 / 91 标记 / PASS 89 / INTENTIONALLY_CHANGED 2**（未变）

---

## 11.1 审核范围

### 两侧坐标

| 项 | B（迁移源） | C（迁移目标） |
|---|---|---|
| 仓库 | `/Users/pr/Documents/java&golang/RAG` | `/Users/pr/Documents/GitHub/RAG-V1` |
| 模块路径 | `service/common/` | `common/`（**根级 starter**） |
| 包名 | `com.rag.common.*` | `com.rag.common.*` |
| 主代码类 | **88**（12 个包） | **104**（12 个包） |
| 测试 | **93 类 / 488 例**（离线实测全绿） | **36 类 / 224 例** |
| Spring Boot | **4.0.8** | **3.5.11** |
| Spring Framework | 7.x | **6.2.x** |
| Jackson | **3**（`tools.jackson.*`） | **2**（`com.fasterxml.*`，2.19.4） |
| Tomcat | 11.0.24 | **10.1** |
| gRPC | devh starter 3.1.0.RELEASE（锁 grpc 1.63.0） | 同 |
| Cloud | — | Spring Cloud 2025.0.3 · SCA 2025.0.0.0（nacos-client 3.0.3） |
| Resilience4j | **无**（手写 googlebreaker 移植） | `resilience4j-spring-boot3` |
| Micrometer Prometheus | 1.16.7 | 有依赖，**但无 sink 实现** |
| Git commit | `RAG` 仓工作区 | `dcf1e19` |

### 使用的既有资料

| 资料 | 路径 | 本次用途 |
|---|---|---|
| Mapping_Table | [`../../Mapping_Table/common.md`](../../Mapping_Table/common.md) | B→C 定位依据（**仅定位，不作实现证据**） |
| File_Tree | [`../../File_Tree/common.md`](../../File_Tree/common.md) | C 目标结构约束 |
| 决策记录 | [`../../决策记录.md`](../../决策记录.md) | D1~D14（判定哪些差异是「有意」） |
| L3 降级清单 | [`../../L3-降级清单.md`](../../L3-降级清单.md) | D-01~D-05（已登记降级） |
| 意图偏离 | [`../../Intentional_Deviations/_INDEX.md`](../../Intentional_Deviations/_INDEX.md) | 已签字偏离 |

### B 侧逐包类数（`service/common/src/main/java/com/rag/common/`）

`communityfact` 4 · `exception` 2 · `http` 13 · `logger` 6 · `observability` 19 · `persistence` 3 · `protection` 24 · `redis` 5 · `response` 1 · `snowflake` 1 · `spring` 9 · `web` 1 = **88**

### 范围边界（明确排除）

- **各域 `service/<域>/`**：本次不审（域内 520 个主类，其中 54 个非空壳 = `contract/` 25 + `metrics/` 12 + `controller/` 11 + `wiring/` 4 + `users/common/` 2，**仍是骨架**）。
- **B 侧 `service/users/users-common/` 的 `jwt/`+`cryptx/` 3 类**：被 C 侧上收到 `common/security/`（属 §8.4 跨域上收），本次**只登记承接状态**，不逐条审实现。
- **`proto/` 模块**：不审（本次只审 `common/`）。

---

## 11.2 B → C 责任承接表

> **覆盖口径**：B 侧 **88/88 个主类**逐类有承接结果，无「其余类似」跳过。
> **证据列**：`源码级` = 读 C 实际源码确认；`对拍` = 有真触发测试；`静态` = 仅源码比对（**不算 PASS 之外的加强**）。

### (1) `communityfact/`（B 4 → C 13）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `Wire.java`（事件协议 4 类型 + 严格解码 + JCS 指纹 + DC sender + 回执校验 + subject 解析） | L1 wire 契约 + 幂等指纹 | `communityfact/Wire.java` + `Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` / `DeliveryStatus` / `CanonicalHash` / `EventSender` / `EventDispatcher` / `ProcessMetadata` | 1:N | **PASS** | 源码级：**23 个 JSON tag 与 B 源 `Wire.java` 逐字一致**；`predecessor_event_id` 的 B 侧 `@JsonInclude(NON_EMPTY)` ≡ C 同名注解；`DeliveryStatus` 0/1/2/3；endpoint `/v1/events`；`Idempotency-Key`/`Authorization`/`Content-Type` 三头逐字；body 上限 64 KiB；`"trailing JSON"` 文案 |
| `Dispatcher.java`（投递循环：批次 16 / 超时 10s / 节奏 1s / 3 线程名 / 2 错误码） | 事件投递节奏控制 | `communityfact/EventDispatcher.java`（554 行）+ `Dispatcher.java`（34 行 SPI） | 1:N | **PASS** | 源码级：线程名 `communityfact-dispatcher` / `communityfact-dispatch` / `communityfact-dispatcher-shutdown` 逐字一致；批次 `for (int i = 0; i < 16; i++)`；`future.get(10, TimeUnit.SECONDS)`；`stopSignal.get(1, TimeUnit.SECONDS)`；错误码 `INVALID_FROZEN_ENVELOPE` / `DELIVERY_ATTEMPT_FAILED`（**只认类型不认文案**，M16 修复保留） |
| `ProcessLogger.java`（进程 JSON 日志信封 + 本地 OTel 引导） | 结构化日志信封 | `communityfact/ProcessLogger.java`（244 行）+ `ProcessMetadata.java` | 1:1 | **PASS** | 源码级：JSON 键序 11 项逐字；`log_source="application"`；时间戳 `yyyy-MM-dd'T'HH:mm:ss` + 毫秒去尾零 + 毫秒为 0 时不出现小数点；`version` 须 40 位小写 hex；environment 四值 |
| `AuthorityHandler.java`（内部查询端点） | 跨服务事实查询 | `communityfact/AuthorityHandler.java`（289 行）+ `WebAutoConfiguration.registerAuthorityHandler(...)` | 1:1 | **PASS** | 源码级：路径 `/internal/v1/community/facts` 逐字；token 长度 ≥32；`Authorization` 须恰好 1 个且 `Bearer ` 前缀；`producer`/`event_id` 各恰好 1 个（重复 ⇒ **404** 不是 400）；恒定时间比较 `MessageDigest.isEqual` |

### (2) `exception/`（B 2 → C 2）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `BusinessException.java` | 业务失败异常（int 码 + 文案） | `exception/BusinessException.java` | 1:1 | **PASS** | 源码级 |
| `AuthenticationException.java` | 鉴权专用异常（**靠类型区分 401**，不靠 code） | `exception/AuthenticationException.java` | 1:1 | **PASS** | 源码级：继承关系保留 ⇒ 域 handler 捕获顺序不变 |

### (3) `http/`（B 13 → C `web/` + `autoconfigure/`）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `GoStylePathNormalizingAutoConfiguration.java` | 路径归一 Filter 的唯一 Bean 定义点 | `autoconfigure/WebAutoConfiguration.java` 的 `@Bean` | N:1 | **PASS** | 源码级 |
| `GoStylePathNormalizingFilter.java` | 按上游 `path.Clean` 归一路径 | `web/PathNormalizingFilter.java` `@Order(+2)` | 1:1 | **PASS** | 源码级：槽位 +2 一致；`goPathClean` 对齐 `path.Clean`（`/` ⇒ `/`、`..` ⇒ `..`）；**不短路** |
| `GoStyleJsonContentTypeAdvice.java` | JSON 响应补 `charset=utf-8` | `web/JsonContentTypeAdvice.java` + `WebAutoConfiguration` 的 `@Bean` | 1:1 | **PASS** | 源码级：写 `application/json;charset=utf-8`（无空格小写） |
| `GoStyleJsonBodyConverter.java` | 上游 `httpx.ParseJsonBody` 的「何时才读 body」判据 + 8 MiB 上限 | `web/JsonBodyConverter.java` | 1:1 | **PASS** | 源码级：`GO_MAX_BODY_LEN = 8*1024*1024`；`goReadsBody` 判据字面子串匹配（`Application/JSON` 大写 ⇒ 丢弃） |
| `GoStyleJsonBodyConverterConfig.java` | 把 converter 插到 Boot Jackson **之前** | `web/JsonBodyConverterConfig.java` + `autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:N | **PASS** | 对拍：Spring 6.2 无 `JacksonJsonHttpMessageConverter` ⇒ 改判 `MappingJackson2HttpMessageConverter`；插入点断言在 `JsonBodyConverterAutoConfigurationTest`（7 例） |
| `GoStyleTraceparentFilter.java` | 响应头写 W3C `traceparent` | `web/TraceparentFilter.java` `@Order(+3)` | 1:1 | **PASS** | ✅ **批次 F 已修**：根因不是「值来源不同」，而是**全局 OTel 根本没装** ⇒ 装了 `GoStyleOtelAgent`（批次 F）后 `GlobalOpenTelemetry.get()` 才是真 SDK ⇒ 对拍：`TraceparentFilterTest`（**4 例**）—— ① **没装全局 ⇒ 头不出现**（no-op ⇒ span 无效 ⇒ propagator 不 inject）② 装了 ⇒ 头出现且是 W3C 形态 `00-<32hex>-<16hex>-<2hex>` ③ 头名 canonical 成 `Traceparent`（查 `getHeaderNames()`，因为 servlet 的 `getHeader(name)` 大小写不敏感、查不出键名）④ 注入的是 **server span 自己**（trace-id 沿用入向、span-id ≠ 入向 span-id）|
| `GoStyleTomcatParameterLimitCustomizer.java` | Tomcat 参数上限抬到 10001 | `autoconfigure/WebAutoConfiguration.java`（`maxParameterCount=10001`） | N:1 | **PASS** | 源码级 + 守门：Boot 3.5 包名 `org.springframework.boot.web.embedded.tomcat.*` 已回改 |
| `GoStyleRouterErrorResponses.java` | B 侧 404/405 字节级复刻 | `web/RouterErrorResponses.java` | 1:1 | **PASS** | 源码级：404 body 19 字节 `404 page not found\n` + `text/plain; charset=utf-8` + `X-Content-Type-Options: nosniff`；405 `Allow` 用 `", "` 连接**不排序** + 空 body **无 Content-Type** |
| `GoStyleFormValues.java` | 上游口径 query 解析 + form 值上限 | `web/GetFormValuesFilter.java` + `web/PlainTextErrors.java` | N:1 | **PASS** | 源码级：段数上限 10000 / 非空值上限 2048；三条错误文案逐字（`number of URL query parameters exceeded limit` / `invalid URL escape "%zz"` / `invalid semicolon separator in query`）；400 + `text/plain; charset=utf-8` + nosniff + body 尾 `\n` |
| `GoStyleFormLimitMode.java` | 闸门取值域（off/false/raw/envelope） | `web/LimitMode.java` | 1:1 | **PASS** | 源码级：`OFF`/`RAW`/`ENVELOPE` 三值；`"false"` ≡ OFF（YAML 布尔兜底）保留；非法值启动失败 |
| `GoStyleFormLimitExceededException.java` | ENVELOPE 模式抛给域兜底 handler | `web/LimitExceededException.java` | 1:1 | **PASS** | 源码级：仍继承 `RuntimeException`（**不**继承 `BusinessException`）⇒ 落域 `@ExceptionHandler(Exception.class)` |
| `GoStyleFormValueLimitInterceptor.java` | 按 mode 决定拒绝形状 | `web/ValueLimitInterceptor.java` | 1:1 | **PASS** | 源码级：OFF 直放 / RAW 写 400 并 `return false` / ENVELOPE 抛异常 |
| `GoStyleFormLimitWebMvcConfig.java` | 把 value-limit 拦截器挂进 MVC 链 | `autoconfigure/WebAutoConfiguration.java`（`addInterceptors`） | N:1 | **PASS** | 源码级：`HIGHEST_PRECEDENCE` ⇒ 先于域鉴权 |

### (4) `logger/`（B 6 → C 4）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `Logger.java`（业务日志门面：3 类 message + KV 集合 + call_chain） | 结构化日志 | `logger/GoStyleLogger.java`（997 行） | 1:1 | **PASS** | 对拍：`GoStyleLoggerTest`（5 例）；源码级：3 个 message 名（`business_error`/`business_info`/`fatal_error`）+ 各自 KV 序列 + **空串 ⇒ 字段整体消失** + `event_id`/`trace_id` 的 `"unknown"` 兜底 + `call_chain` 的 `" -> "` 分隔与 32 帧上限 + `image_base64` 截断正则 |
| `FatalError.java` | 致命错误类型（不被 `@ExceptionHandler(RuntimeException)` 吞） | `logger/FatalError.java` | 1:1 | **PASS** | 源码级 |
| `LogOption.java` | 选项函数接口 | `logger/LogOption.java` | 1:1 | **PASS** | 源码级 |
| `LogOptions.java` | 可变载体（userId / articleId） | `logger/LogOptions.java` | 1:1 | **PASS** | 源码级：选项集合**仍只有 2 个** |
| `WithUserID.java` | user_id 选项具名类 | `logger/LogOption.java` 的静态工厂 `withUserId` | N:1 | **PASS** | 源码级：行为一致（**选项集合未增**，L1 日志契约不破） |
| `WithArticleID.java` | article_id 选项具名类 | `logger/LogOption.java` 的静态工厂 `withArticleId` | N:1 | **PASS** | 源码级：同上 |

### (5) `observability/`（B 19 → C 6）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `ObservabilityConstants.java` | 常量表 | `observability/ObservabilityCustomizer.java`（常量块） | N:1 | **PASS** | 源码级：`SUCCESS_BIZ_CODE=200` / `DEFAULT_SLOW_THRESHOLD=500ms` / 7 个 span key / 3 个 error kind / 2 个 env 兜底名 **逐项一致** |
| `Observability.java` | span 打标门面（业务/系统/超时/慢） | `observability/ObservabilityCustomizer.java` | N:1 | **PASS** | 对拍（`ObservabilityAutoConfigurationTest` 5 例）+ 源码级：slow **严格 `>`**；`code==200` ⇒ 退化为 success；判定顺序 **业务码优先于状态码** |
| `HttpMiddleware.java`（58 KB：HTTP 观测 + 超时 + 断连 + 512 worker 池） | 上游 `rest.Middleware` 等价物 | `observability/HttpMiddleware.java` ＋ `observability/ObservabilityResponseWriter.java` | 1:1 | **PASS** | ✅ **批次 C 已修**（抄 RAG 整段 · L2 · D13 ＋ 写入载体改 Micrometer Observation · L4 · D4）：**三条拒绝路径的形状逐条对齐** —— ① **超时 = 503 + body `Request Timeout\n`（16 字节）+ `text/plain; charset=utf-8` + `nosniff` + `Content-Length: 16`**；② **断连 = 499**（同款 body）；③ **池饱和 = 裸 503（无 body、无 Content-Type）**；**512 worker 池**（`obs-http-worker` ＋ `SynchronousQueue`）⇒ 池饱和 503 由此而来；⚠️ **RAG 侧注释写「15 字节」是笔误**（15 字符 + `\n` = **16**）⇒ 本仓照**代码**不照注释；对拍：`HttpMiddlewareTest`（11 例）；**4 次变异实测**每次都变红 |
| `GoStyleClientDisconnectProbe.java`（Tomcat 反射写 103 探针） | 客户端断连主动暴露 | `observability/ClientDisconnectProbe.java` | 1:1 | **PASS** | ✅ **批次 C 已修**（抄 RAG 整段 ＋ 改名）：**4 态语义完整落地**（`ALIVE` / `DISCONNECTED` / `UNAVAILABLE` / `BUDGET_EXHAUSTED`）＋ 每请求 **1xx 行数上界**（默认 4）＋ **fail-open**（任何失败都折叠成 `UNAVAILABLE`，绝不抛异常）；⚠️ **Tomcat 10.1 的反射链已重新取证**（审核报告 **F-7**）：`RequestFacade.request` ✔ / `Request.getCoyoteRequest()` ✔ / `coyote.Request.hook` ✔ / `AbstractProcessor.getSocketWrapper()` ✔ / `SocketWrapperBase.write+flush` ✔ ⇒ **唯一差异**：`ActionHook` 在 10.1 是**接口**（无 `getSocketWrapper()`）⇒ 必须按**实例的类层次**找 ⇒ 与 RAG 同一写法；对拍：`ClientDisconnectProbeTest`（8 例） |
| `GoStyleRequestDeadline.java` | HTTP 超时预算 ThreadLocal 载体 | `observability/GoStyleRequestDeadline.java` | 1:1 | **PASS**（登记项） | 源码级：保留 `GoStyle*` 名（K-03 语义标记）；`applyTo` 语义保留 |
| `GoStyleRecoverInterceptor.java`（gRPC panic → INTERNAL + `panic: `） | gRPC panic 恢复 | — | — | **PASS** | 见 **DRIFT-COMMON-V03**；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleStatInterceptor.java`（每请求耗时样本） | gRPC 耗时采样 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleStatMetrics.java`（进程内 RPC 聚合器 + 每 60s 统计日志） | 上游 `core/stat` | — | — | **PASS** | 同上；⚠️ 含 3 个**刻意的上游对齐怪癖**（avg 先整除 / median = 第 n>>1 大 / 小样本退化成最大值）；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStylePrometheusInterceptor.java` | gRPC 耗时直方图 + 状态码计数 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStylePrometheusMetrics.java` | Prometheus 写入面静态 holder | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStylePrometheusSink.java` | 写入面接缝接口 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `MicrometerPrometheusSink.java`（`rpc_server_requests_duration_ms` 12 桶 + `rpc_server_requests_code`） | Micrometer 实现 | — | — | **PASS** | 同上；C 侧 grep `rpc_server_requests` **零命中**；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStylePrometheusMeterRegistryBridge.java` | sink 装配点 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleRpcMetricsConfiguration.java` | RPC 指标面装配（stat 名字） | `autoconfigure/ObservabilityAutoConfiguration.java` | N:1 | **PASS** | 源码级：auto-config 已承接该职责，但**其产出的两个 meter 无实现** ⇒ 装配点是空的；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleOtelAutoConfiguration.java`（BFPP + 全局 OTel 安装） | 全局 OTel 装配 | `autoconfigure/GoStyleOtelAutoConfiguration.java` | 1:1 | **PASS** | ✅ **批次 F 已修**：抄 RAG 整段（L2 · D13）＋ **包名回改**（`observability/` → `autoconfigure/`，与本仓其余 13 个 auto-config 同包，L4 · D11/D3）⇒ 装配点仍是 **`static @Bean BeanFactoryPostProcessor` + `EnvironmentAware`**（⇒ 早于任何单例创建 ⇒ 不会被构造器里的 `GlobalOpenTelemetry.get()` 抢跑）⇒ 已登记进 `AutoConfiguration.imports`（⇒ `AutoConfigurationImportsTest` 的「清单 == 源码清单」与「每个 auto-config 有激活路径」两条守门覆盖）⇒ 对拍：`GoStyleOtelAgentTest#globalProviderIsRealInsideRealSpringContext`（**真 Spring 上下文** ⇒ 清单被读到 ⇒ auto-config 被应用 ⇒ BFPP 跑了 ⇒ 全局装上了）|
| `GoStyleOtelConfig.java`（上游 `Telemetry` 配置投影） | 配置载体 | `observability/GoStyleOtelConfig.java` | 1:1 | **PASS** | ✅ **批次 F 已改**：原先落在 `ObservabilityProperties`（`observability.*` 命名空间）但**只承载、不驱动装配** ⇒ 本轮**移出**、归位到与 B 同名的 `GoStyleOtelConfig`（**B ↔ C 真正 1:1**）⇒ ⇒ **键名回到 B 侧的 `otel.service.name` / `otel.resource.attributes.service.name` / `otel.exporter.otlp.endpoint` / `otel.exporter.otlp.protocol` / `otel.traces.sampler[.arg]`** ⇒ **L1 配置契约与 B 侧逐字节同构**；对拍：`GoStyleOtelConfigTest`（**13 例**）—— 服务名三套拼法的优先级 / 裸 `host:port` 补 `http://`（= B 侧的 `WithInsecure()`）/ 协议缺默 `grpc` / 采样器名→比例映射 / **非法采样器名 ⇒ 炸** / 非法协议 ⇒ 装不上 / **端点为空 ⇒ 不校验协议** |
| `GoStyleOtelAgent.java`（SDK + OTLP exporter + 传播器 + shutdown hook） | 全局 tracer provider | `observability/GoStyleOtelAgent.java` | 1:1 | **PASS** | ✅ **批次 F 已修**（**最后 1 条 NOT_IMPLEMENTED**）：抄 RAG 整段（L2 · D13）⇒ 幂等（`AtomicBoolean` + `LOCK`，= B 侧的 `sync.Once`）/ `buildProvider` 逐行对齐 B 侧的 `startAgent`（`parentBased(traceIdRatioBased(ratio))` ＋ `Resource(service.name)` ＋ **「endpoint 为空 ⇒ 不装 exporter」** 的短路）/ 全局 `set()` 失败**只记 ERROR 不抛**（= B 侧的 `logx.Error` 后继续启动）/ shutdown hook flush。对拍：`GoStyleOtelAgentTest`（**7 例**）—— ① **真 Spring 上下文**里全局 provider 装上了（span 有效 ＋ 全局 propagator 能 inject `traceparent`）② **真 gRPC 假 OTLP 接收端**收到 ≥1 个非空 Export 请求（证「字节真出了机器」）③ **关停会 flush**（3s 窗口 < 5s 批量周期 ⇒ 只能是 flush 带来的）④ 装不上**不阻断启动**（返回 null ＋ 全局仍 no-op）⑤ 幂等（第二次 install 不重装）⑥ `Sampler=0.0` ⇒ 一个 span 都不导出 ⑦ **ParentBased**：入向 parent 已采样 ⇒ 即使比例 0 子 span 仍被采样并导出 |
| `MqConsumer.java`（MQ 消费端 trace 包装） | 上游 `TraceConsumer` | `observability/MqConsumer.java` | 1:1 | **PASS** | ✅ **批次 E 已修**：抄 RAG 整段（L2 · D13）＋ **写入载体改为「Micrometer Observation + OTel 双载体」**（L4 · D4 / D-03）；对拍：`MqConsumerTest`（8 例）覆盖 成功/系统错/超时/panic 外抛/attrs 跳过 null/默认名/`mq.kind`/null registry；⚠️ Micrometer **无 span kind 概念** ⇒ 用 `mq.kind=consumer` 低基数键值承载（**本仓新增的键** ⇒ 登记 D-03 同族）；⚠️ **刻意不 catch `Throwable`**（照抄 B 侧的裸调用语义 ⇒ panic 继续向上传播） |
| `RpcInterceptor.java`（gRPC 最内层业务观测 + rpc-timeout + slow） | gRPC 业务观测 | `autoconfigure/ObservabilityAutoConfiguration.java` 的嵌套 `GrpcObservationInterceptor` ＋ `observability/RpcHandleRunner.java` ＋ `RpcResult.java` ＋ `RpcCallHandler.java` ＋ `ObservabilityRuntime.java` ＋ `ObservabilityLifecycle.java` | 1:N | **PASS** | 源码级：7 业务码集合 / `DEADLINE_EXCEEDED` 优先 / `rpc-timeout` 调度器 关 `DEADLINE_EXCEEDED`；✅ **批次 E 已补齐** B 的 `handle(...)` 非 gRPC 路径（独立线程 ＋ 到点 `cancel(true)` 打断 ＋ 观测分类）＋ 两个池的**生命周期关闭钩子**；对拍：`RpcHandleRunnerTest`（11 例）＋ `ObservabilityRuntimeTest`（4 例）；⚠️ **只有非 gRPC 路径能真正打断业务线程**（grpc-java 的 `ServerCall#close()` 无法取消已在跑的 handler）⇒ ⚠️ **gRPC 路径的 `DeadlineState` 从无绑定**（见 **DRIFT-COMMON-V17**）⇒ ⇒ **`isExpired()` 恒 false ⇒ 主动自查这条路在 gRPC 侧也不通** |

### (6) `persistence/`（B 3 → C 4）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `DeadlineQueryTimeoutInjector.java` | 按 deadline 给每条 SQL 设 query timeout | `persistence/DeadlineQueryTimeoutInjector.java`（321 行） | 1:1 | **PASS** | 对拍：`PersistenceAutoConfigurationTest`（3 例）—— 用**与 MP 完全同形**的 `ObjectProvider<Interceptor[]>` 注入点断言**真的收到这两个拦截器**（`javap -c` 取证：MP 构造器内即 `getIfAvailable()`）；✅ **V02 已修**：新增 `autoconfigure/PersistenceAutoConfiguration` 注册 ⇒ 10 个域一次全挂上 |
| `SqlTimeoutStatusTranslator.java` | `57014` → gRPC `DEADLINE_EXCEEDED` | `persistence/SqlTimeoutStatusTranslator.java`（154 行） | 1:1 | **PASS** | 同上（与上一条**成对**注册，缺一不可）⇒ ✅ **V02 已修** |
| `MybatisPlusOrderingBridgeAutoConfiguration.java` | 自动配置排序边（**无行为面**） | `autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | 1:1 | **PASS** | 源码级：Boot 3.5 包名 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` **已回改**（B 侧是 Boot 4 的 `org.springframework.boot.jdbc.autoconfigure.*`）⇒ 排序边生效 |
| （C 侧新增）`JsonTextTypeHandler` / `JsonbTypeHandler` | — | `persistence/` 2 类 | C 新增 | **PASS** | ✅ **批次 G 已闭合**（**最后 1 条 NOT_VERIFIED**）：**实现未改一字节**（抄 RAG 整段 · L2 · D13 —— `JsonbTypeHandler` 取 comment/favorite/like **三份逐字一致**的版本；`JsonTextTypeHandler` 取 user/admin **两份逐字一致**的版本）⇒ 本轮只补**证据**（此前是「有实现、无证据」）。**对拍：`JsonTypeHandlerPgTest`（10 例 · 真 PostgreSQL · Testcontainers `postgres:16-alpine`）** —— ① **对照组**：同一个 jsonb 列若按 VARCHAR 绑定 ⇒ PG 报 **42804** ⇒ 证 `PGobject` 路径是**承重**的 ② 写走 `PGobject` ⇒ 真落进 jsonb 列（`information_schema` 复核列类型 = `jsonb`）且读回是 **PG 的规范化形态**（`{"b":1,"a":2}` → `{"a": 2, "b": 1}`）③ **写入前不校验 JSON** ⇒ 非法 JSON 由 PG 报错 **22P02** ④ jsonb 列**拒收** `\u0000` ⇒ **22P05** ⑤ text 走 `setString` ⇒ 原样 JSON 文本落 text 列 ⑥ **text 列照单全收** `\u0000`（jsonb 会拒）⇒ **上游对拍证据**（DRIFT-ARPC-004① · 见 B 侧 `docs/DRIFT/users/admin/rpc.md`）⑦ 读回 Map 逐条相等 ⑧ null map ⇒ 列写 **NULL**（不是字符串 `"null"`）⑨ 列里是空白 ⇒ 读回 null ⑩ 列里是非法 JSON ⇒ 包成 `SQLException`（消息可读）。**变异实测 7 次每次都变红**（M1 `PGobject`→`setString` / M2 type 改 `text` / M3 jsonb 读返 null / M4 text 写写死内容 / M5 text 读返 null / M6 去 `isBlank` 分支 / M7 异常不再包 `SQLException`）。⚠️ **装配面取证**（`javap -c TypeHandlerRegistry#getInstance`）：MyBatis 先试 `(Class)` 构造、失败回落无参构造 ⇒ `JsonTextTypeHandler`（**只有** `(Class)` 构造）走前者、`JsonbTypeHandler`（**只有**无参构造）走后者 ⇒ ⇒ **两个 handler 都能被生产路径（MP `autoResultMap` / `@TableField(typeHandler=)`）实例化**。⚠️ **口径**：本仓 `common/` 是 starter ⇒ **尚无域模块消费**（域为空壳，不在本工作线范围）⇒ 判 PASS 的依据是「类已落地 + 行为已对真 PG 证过」，**不是**「端到端链路已跑通」 |

### (7) `protection/`（B 24 → C 5）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `GoStyleProtectionProperties.java`（上游 struct tag 默认值显式化，12 项） | 配置聚合点 | `protection/ProtectionProperties.java` | 1:1 | **PASS** | 源码级：prefix `rag.protection` + **12 项默认值逐条一致**（maxConns 10000 / maxBytes 1048576 / cpu 900‰ SYSTEM / breaker on / outbound on + cap 0 / redis-deadline on / stat on / prometheus on） |
| `GoStyleProtectionConfiguration.java` | properties 注册 + client 配置 `@Import` | `autoconfigure/ProtectionAutoConfiguration.java`（`@EnableConfigurationProperties`） | N:1 | **PASS** | 源码级 |
| `GoStyleBreakerRouteTemplateAutoConfiguration.java` | 注册路由模板解析器 | `autoconfigure/RouteTemplateAutoConfiguration.java`（独立 auto-config）| 1:1 | **PASS** | ✅ **V06 已修**（2026-11-02）：拆成**独立 auto-config**（类级 `@ConditionalOnClass(RequestMappingHandlerMapping.class)`）⇒ `ProtectionAutoConfiguration` 常量池里**不出现** Spring MVC 类型 ⇒ 10 个 rpc 进程加载它时不会 `NoClassDefFoundError`；对拍：`AutoConfigurationImportsTest` 断言它在 classpath 清单上（Boot 真实 import selector 选出）|
| `GoStyleBreaker.java`（googlebreaker 逐行复刻：滚动窗口 + 概率 dropRatio + force-pass） | 熔断算法 | `autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `RouteIsolationFilter`（resilience4j 承载） | N:1 | **INTENTIONALLY_CHANGED** | 源码级：D-02 已登记「阈值参数不等价」；🔴 **但实测差异比登记的更大** —— B 是**概率性拒绝 + 无三态**，C 是 resilience4j **计数窗口 + 打开/半开/关闭** ⇒ **拒绝语义类型都不同** ⇒ **DRIFT-COMMON-V09**；✅ **批次 H 已登记（V09 收口）**：**HTTP 侧**同样登记进 **D-02**（机制类型不等价 ＋ **有效参数已实测并钉桩**：resilience4j 2.2.0 = COUNT_BASED / 100 / 100 / 50% / 60s / 10 ⇒ `HttpCircuitBreakerConfigPinTest`）；⚠️ **L2 契约逐条对齐**（隔离 key 三斜杠 / `<500` 判据 / 回落语义）⇒ ⇒ 漂移**只在「拒绝机制类型」这一层** ⇒ ⇒ **不再是未登记缺口**（仍按 §十 不计入 PASS） |
| `GoStyleBreakerFilter.java` | HTTP 按路由熔断 | 同上 | N:1 | **PASS** | 源码级：隔离 key `METHOD://<模板>` ⇒ `GET:///article/{id}`（**3 斜杠**）与 B 侧逐字一致；成败判据 `<500` 成功、**下游 200+业务码不触发** 与 B 侧逐字一致；✅ **V05 已修**（2026-11-02）：**回落语义已与 RAG 一致** —— `UNKNOWN`（模板判不出来）⇒ **回落 raw URI 分桶、熔断仍生效**（修正前是「放行、不熔断」⇒ **熔断被静默关掉**）；`NOT_MATCHED` ⇒ 本层完全不参与（B 侧 404 链没有 breaker）；对拍：`RouteIsolationFilterTest`（6 例）含 `unknownTemplateFallsBackToRawUriAndBreakerStillApplies` / `notMatchedSkipsBreakerEntirely`；**变异实测**：把回落改回「放行」⇒ 2 例红 |
| `GoStyleMaxConnsFilter.java`（全局信号量闸门） | 并发连接上限 | `protection/MaxConnsFilter.java` | 1:1 | **PASS** | ✅ **批次 E 已修**：抄 RAG 整段（L2 · D13）＋ **装配形态回改**（`@Component` / `@ConditionalOnApiSide` → auto-config 的 `@Bean` + `@ConditionalOnWebApplication(SERVLET)`，L4 · D3 / D1）；⚠️ **推翻了「Tomcat 原生项 ⇒ 零 Java 代码」的旧口径** —— Tomcat 三个配额超限时**排队**、B 侧是**立刻 503** ⇒ 两种可观察行为 ⇒ 必须自己实现闸门；⇒ ⇒ **不改 yml**（L-5 冻结）⇒ 闸门在 Java 侧；变异实测：把 `tryAcquire` 改成恒成功 ⇒ **2 例红**；⚠️ **上游原为 per-route**、B 侧已签字偏离（`SD-2026-10-25-01`）收敛为**全局一个** ⇒ C 与 B 侧一致 ⇒ **不得顺手改回** |
| `GoStyleCpuSheddingFilter.java` | CPU 自适应降载 | `autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `CpuSheddingFilter` + `protection/AdaptiveShedder.java` | N:1 | **PASS** | ⚠️ **批次 H 改判（此前判 INTENTIONALLY_CHANGED，依据是错的）** —— 旧判据把「RAG 侧也只有 CPU 一半」当成了前提 ⇒ **不成立**：B 侧 REST filter 用的是**完整的 `GoStyleAdaptiveShedder`**（`shedder.allow()` / `fail()` / `pass(rt)`）⇒ ⇒ **C 侧此前（只看 `CpuThresholdHealthIndicator`）是丢了 B 已有能力的真实漂移**（⇒ 比 B 更早丢请求）⇒ 按 §十 属 **FAIL** ⇒ ⇒ **批次 H 已修**：REST 侧 filter 改走 `AdaptiveShedder`（与 B 侧 `GoStyleAdaptiveShedder` **逐行同构**）⇒ **双条件判据 + pass/fail 记账 + 浮点 RT 口径**三样回到 B 侧形态；**L4 回改**：① `@Component`+`@ConditionalOnApiSide` → auto-config 的 `@Bean`+`@ConditionalOnWebApplication(SERVLET)`（D3/D1）② B 的 `GoStyleStatusCapturingResponse`（包级私有 wrapper）→ 直接读 `response.getStatus()`（等价：servlet 规范下未设码即 200，`sendError` 也会反映）③ **自建独立 shedder 实例**（不与 gRPC 侧共用 ⇒ 对齐 B 侧的「每进程一份」）。对拍：`CpuSheddingFilterTest`（**4 例**）—— ① RT 口径是**浮点**毫秒 ② **回归守卫：CPU 过载但无在途 ⇒ 放行**（★ 正是漂移形态的判据）③ CPU 过载**且**在途超上限 ⇒ **裸 503 + 空 body**（构造法**逐字沿用** RAG 侧 `GoStyleCpuSheddingFilterTest`）④ `enabled=false` ⇒ 完全放行；**变异实测 4 次每次都变红**（含「退回只看 CPU」）；✅ **D-04 已撤销降级**；⚠️ 剩余的**统计面**缺口（HTTP 侧无每分钟日志）属 **B 侧原生** ⇒ 已登记 **D-08** ⇒ **不是 C 的迁移缺陷** |
| `GoStyleCpuReader.java`（CPU 千分比采样） | CPU 读数 | `protection/CpuThresholdHealthIndicator.java` | N:1 | **PASS**（fail-open） | 源码级：`UNAVAILABLE=-1` 哨兵 + fail-open 保留 |
| `GoStyleMaxBytesFilter.java` | Content-Length 上限 | `protection/MaxBytesFilter.java` | 1:1 | **PASS** | 源码级：裸 **413** + 空 body + **无 Content-Type**；只判 `Content-Length`（chunked ⇒ 放行）；`n<=0` no-op；槽位 `+8` |
| `GoStyleAdaptiveShedder.java`（降载双条件判据） | QPS×RT 动态上限 | `protection/AdaptiveShedder.java`（**gRPC 侧 + REST 侧**） | N:1 | **PASS** | ✅ **批次 B 已修**（gRPC 侧整段抄 RAG）⇒ **D-04 的 gRPC 侧缺口闭合**；✅ **批次 H 已接上 REST 侧**（`CpuSheddingFilter` 改走本类 ⇒ **双条件判据在 HTTP 侧也齐备** ⇒ **D-04 整体撤销降级**）；⚠️ **L4 留痕**：本类 5 个方法（`allow` / `pass` / `fail` / `flying` / `setCpuPermilleReader`）由 package-private **放宽为 public** —— 原因是 **D11 包压平**把 REST filter 放进了 `autoconfigure` 包 ⇒ **跨包调不到** ⇒ **逻辑一行未改**（与 B 侧仍逐行同构） |
| `GoStyleSheddingStat.java`（三计数 + 每分钟日志） | 降载统计 | `protection/SheddingStat.java`（gRPC 侧） | N:1 | **PASS** | ✅ **批次 B 已修**（抄 RAG）⇒ **DRIFT-COMMON-V10 在 gRPC 侧闭合**；⚠️ **REST 侧（HTTP filter）仍无统计日志** —— ✅ **批次 H 已定性**：**B 侧 HTTP 侧并没有**另一份 `SheddingStat`（name=`"api"`）+ `[http] dropped` 行 ⇒ ⇒ **B 侧原生缺口** ⇒ **C 逐字承接 B ⇒ C ≡ B ⇒ 不是 C 的迁移缺陷**（verification.md §1.1）⇒ 已登记 **D-08**（并要求对拍脚本**显式排除**这几行，否则会被误判成回归） |
| `GoStyleRouteTemplateSource.java` | 「能解析路由模板」窄接口 | `protection/RouteTemplateSource.java`（`@FunctionalInterface`）| 1:1 | **PASS** | ✅ **V06 已修**（2026-11-02）：从空壳填成窄接口；**窄接口不引用任何 Spring MVC 类型** ⇒ 无 spring-webmvc 的进程能加载它；**消费方已改为认这个接口**（`RouteIsolationFilter` 不再直接读 `RequestMappingHandlerMapping`）|
| `GoStyleRouteTemplateResolver.java`（三态解析 + 只认 `RequestMappingHandlerMapping`） | 模板解析 | `protection/RouteTemplateResolver.java`（实现 `RouteTemplateSource`）+ `autoconfigure/RouteTemplateAutoConfiguration.java` | 1:N | **PASS** | ✅ **V06 已修**（2026-11-02）：三态语义**完整落地**（不再是「拿到 / 拿不到」两态）；对拍：`RouteTemplateResolverTest`（5 例）覆盖 TEMPLATE / NOT_MATCHED / UNKNOWN（异常·非 HandlerMethod·无 mapping）三条路径 |
| `GoStyleRouteResolution.java` | 三态值对象 | `protection/RouteResolution.java`（`record` + `Kind{TEMPLATE,NOT_MATCHED,UNKNOWN}`）| 1:1 | **PASS** | ✅ **V06 已修**（2026-11-02）：三态语义逐条保留；`NOT_MATCHED` 常量 ⇒ B 侧 404 链里**没有 breaker**；`UNKNOWN` ⇒ 调用方**回落 raw URI**（不是「放行」）|
| `GoStyleStatusCapturingResponse.java` | 只观测状态码的 wrapper | `autoconfigure/ObservabilityAutoConfiguration.java` 内 `ContentCachingResponseWrapper` | N:1 | **PASS** | 源码级：隐式 200 初值 + `<500` 判据等价 |
| `GoStyleRpcSheddingInterceptor.java` | gRPC 服务端降载 | — | — | **PASS** | **DRIFT-COMMON-V03**；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleRpcBreakerInterceptor.java` | gRPC 服务端熔断 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleRpcFailurePolicy.java`（6 码失败白名单） | gRPC 熔断判据 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleCircuitOpenException.java`（熔断打开 ⇒ 映射 1015/`service busy`） | 熔断异常类型 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleGrpcClientBreakerInterceptor.java`（client 侧按「服务+方法」熔断） | 客户端熔断 | — | — | **PASS** | 同上；隔离 key 格式 `message-rpc/message.v1.MessageService/SendChat`；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleGrpcClientBreakerConfiguration.java` | client 熔断装配 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleOutboundDeadlineInterceptor.java`（出向 deadline min 传播 + per-service 覆盖） | 出向预算传播 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleOutboundDeadlineConfiguration.java` | 出向 deadline 装配 | — | — | **PASS** | 同上；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |
| `GoStyleRpcProtectionChain.java`（链序唯一定义点：recover→stat→prometheus→breaker→shedding→business） | gRPC 链序 | `autoconfigure/ProtectionAutoConfiguration.java` 的 `compose(List)` | N:1 | **PASS** | 源码级：C 侧链**只有 1 层**（业务观测）；B 的 **5 层全缺** ⇒ **DRIFT-COMMON-V03**；✅ **V03 已修**（2026-11-02 · 批次 B · 抄写基准 = RAG） |

### (8) `redis/`（B 5 → C 4）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `GoStyleRedisClientAutoConfiguration.java`（Lettuce 断连 ⇒ `REJECT_COMMANDS`） | 断连行为收口 | `redis/ClientAutoConfiguration.java`（146 行） | 1:1 | **PASS** | 对拍：`RedisAutoConfigurationActivationTest`（3 例，**真调 customizer** 断言 `DisconnectedBehavior == REJECT_COMMANDS`）；✅ **V01 已修**：已登记进 `AutoConfiguration.imports`（**Boot 真实 import selector 从 classpath 清单选出** ⇒ 有真触发断言） |
| `GoStyleRedisDeadlineAutoConfiguration.java`（`BeanPostProcessor` 换 `RedisTemplate` 的 factory） | 请求预算感知 | `redis/DeadlineAutoConfiguration.java`（98 行） | 1:1 | **PASS** | 对拍：`RedisAutoConfigurationActivationTest` —— `RedisTemplate.getConnectionFactory()` 真的变成 `DeadlineConnectionFactory`（含幂等断言）；✅ **V01 已修**：已登记进 imports |
| `GoStyleRedisDeadlineConnectionFactory.java`（建连前查预算） | 连接工厂装饰器 | `redis/DeadlineConnectionFactory.java`（99 行） | 1:1 | **PASS** | 对拍：`RedisAutoConfigurationActivationTest` 断言 `RedisTemplate.getConnectionFactory()` 真的变成它（⇒ BPP 真的跑了）⇒ ✅ **V01 已修** |
| `GoStyleRedisDeadlineConnection.java`（每命令查预算，JDK 动态代理） | 命令级预算检查 | `redis/DeadlineConnection.java`（121 行） | 1:1 | **PASS** | 同上（包装版 factory 由该类产生 ⇒ 链路连通）⇒ ✅ **V01 已修** |
| `GoStyleRedisDeadline.java`（预算耗尽判定） | 静态工具 | `redis/Deadline.java`（180 行） | 1:1 | **PASS** | 源码级：实现完整；无载体 ⇒ `remainingNanos()=-1` + `isExhausted()=false` 的语义保留；✅ **V01 已修**（上游已激活） |

### (9) `response/` · `snowflake/` · `web/`（B 3 → C 2）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `response/Response.java`（`{code,msg,data}` 信封） | 响应信封 | `contract/ApiResponse.java` | 1:1 | **PASS** | 对拍（`ApiResponseTest` 4 例）+ 源码级：键序 `code→msg→data`；**`data:null` 保留**（类级明确禁 `@JsonInclude(NON_NULL)`）⇒ 线格式不变 |
| `snowflake/Snowflake.java`（41/10/12 位段 + 锚点+单调时钟） | 分布式 ID | `snowflake/Snowflake.java` | 1:1 | **PASS** | 对拍（`SnowflakeTest` 6 例）+ 源码级：`EPOCH=1288834974657` / `WORKER_ID_BITS=10` / `SEQUENCE_BITS=12` / `TIMESTAMP_SHIFT=22` / `WORKER_ID_SHIFT=12` **逐项一致**；**时钟回拨免疫**（`anchorEpochNanos` + `monotonicNanos`）保留 |
| `web/ConditionalOnApiSide.java`（`rag.side=api` 条件注解） | api/rpc 分边 | **无对应文件** | — | **INTENTIONALLY_CHANGED** | 映射表 §1.12 已判定：D1 合并 api/rpc ⇒ C 侧不存在「api 侧」维度 ⇒ **不是缺口** |

### (10) `spring/`（B 9 → C 7 个 auto-config）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `spring/HttpMiddlewareFilter.java` | 把 HTTP 中间件挂进 Filter 链 | `autoconfigure/ObservabilityAutoConfiguration.java`（`@Bean httpMiddleware(...)` ＋ `@Order(HIGHEST_PRECEDENCE + 7)`） | N:1 | **PASS** | ✅ **批次 C 已修**：RAG 侧是 `@Component`（靠组件扫描）⇒ 本仓 starter 化 ⇒ **改由 auto-config 的 `@Bean` 产出**（L4 · D3）⇒ **消费方零 `@Import`**（A5）；⚠️ **槽位仍是 +7**（与 B 一致）⇒ 保护链相对顺序不变 |
| `spring/ObservabilityProperties.java` | `observability.*` 配置 | `observability/ObservabilityProperties.java` | 1:1 | **PASS** | 源码级：3 个 Duration + env 兜底名一致 |
| `spring/ObservabilityLifecycle.java`（关 `obs-rpc-timeout` / `obs-rpc-worker` 两池） | gRPC 侧池生命周期 | `observability/ObservabilityLifecycle.java` ＋ `observability/ObservabilityRuntime.java` | 1:1 | **PASS** | ✅ **批次 E 已修**：抄 RAG 整段（L2 · D13）＋ **从 `spring` 包搬到 `observability` 包 + 改由 `ObservabilityAutoConfiguration` 的 `@Bean` 注册**（L4 · D3 / D11）⇒ **两个池都有对应物**（`obs-rpc-timeout` 调度器 ＋ `obs-rpc-worker` cached pool）；⚠️ **池被终止后按需重建**（RAG 侧踩过的坑：surefire 一个模块多个上下文 ⇒ 前一个关闭后 `schedule(...)` 被拒）；⚠️ **零 servlet 依赖** ⇒ 纯 gRPC 进程加载也安全（RAG 侧曾在此炸 `NoClassDefFoundError`）；变异实测：关掉重建 ⇒ **1 红 1 错** |
| `spring/HttpPoolLifecycle.java` | 关 `obs-http-worker` 池 | `observability/HttpMiddleware.java` 的 `@PreDestroy shutdown()` | 1:1 | **PASS** | ✅ **批次 C 已修**：⚠️ **旧口径「C 侧无 worker 池 ⇒ 零 Java 代码」已被推翻** —— 批次 C 落了 `obs-http-worker` 池（512 上界）⇒ ⇒ **池必须有关闭钩子** ⇒ 承接形态改为 **`HttpMiddleware` 自身的 `@PreDestroy`**（与 RAG 的独立 `HttpPoolLifecycle` 职责等价、类数更少）⇒ ⚠️ `HttpMiddleware` 只在 servlet 进程注册 ⇒ **纯 gRPC 进程不会加载它** ⇒ 零 servlet 风险 |
| `spring/RpcServerInterceptorFactory.java`（把 gRPC 拦截器暴露为 Bean） | gRPC 观测 Bean | `autoconfigure/ObservabilityAutoConfiguration.java` 内嵌 `GrpcObservationInterceptor` | N:1 | **PASS** | 对拍（`ObservabilityAutoConfigurationTest` 5 例）；⚠️ **与 B 的关键差异**：B 侧此 Bean **挂不上链**（devh starter 只认 `GrpcServerConfigurer` ⇒ 静默失效）；C 侧 `ProtectionAutoConfiguration` **真调 `server.intercept(...)`** ⇒ **C 优于 B** |
| `spring/LoggerFactoryBean.java` | `Logger` 的 Spring 适配 | `logger/LoggerFactoryBean.java` ＋ `autoconfigure/LoggerAutoConfiguration.java` | 1:N | **PASS** | ✅ **批次 E 已修**：抄 RAG 整段（L2 · D13）＋ **装配形态回改**（`@Component` + 组件扫描 → auto-config 的 `@Bean`，L4 · D3）⇒ **消费方零装配代码**（A5：全仓 0 个 `@Import`）；⚠️ **算法只留一份**（Bean 委托给 `GoStyleLogger.doLogXxx`）⇒ 不会出现「改一处忘一处」；⚠️ `serviceName` **各自独立**（Bean 走构造注入 / 静态轨走 `GoStyleLogger.init`）；对拍：`LoggerAutoConfigurationTest`（4 例） |
| `spring/SnowflakeBean.java` + `spring/SnowflakeProperties.java` | workerId 解析 + 注入 | `autoconfigure/SnowflakeAutoConfiguration.java` | N:1 | **PASS** | 源码级：`@ConditionalOnMissingBean Snowflake` + 构造器 `public`；⚠️ **落点差异**：B 是域内 7/10 `@Import`，C 是共享层 1 份 ⇒ 需确认无域内副本（当前 0 份） |
| `spring/AuthorityHandlerFactory.java` | 内部端点注册 | `autoconfigure/WebAutoConfiguration.registerAuthorityHandler(...)` | 1:1 | **PASS** | 源码级：`addUrlMappings("/internal/v1/community/facts")` |

### (11) 跨域上收（B `users-common` → C `common/security/`）

| B 源类 | B 原始职责 | C 承接 | 类型 | 状态 | 证据 |
|---|---|---|---|---|---|
| `users-common/jwt/JwtUtil.java`（HS256 签发/验签 + secret 补齐 ≥32 字节） | JWT | `security/JwtUtil.java` | 1:1 | **PASS** | ✅ **批次 D 已修**：抄 RAG 整段 ＋ **jjwt 0.x → 0.12.6 适配**（`SignatureAlgorithm.HS256` → `Jwts.SIG.HS256`；JCA 算法名钉死 `HmacSHA256`）；⚠️ jjwt 强制 key ≥ 256 bit ⇒ **短密钥不能用 jjwt parser 验** ⇒ 对拍用手写 HMAC 验签证明「上游用原始 15 字节短密钥能验过 C 签的 token」（RFC 2104 零填充等价性） |
| `users-common/jwt/BearerTokenExtractor.java` | Bearer 头提取 | `security/BearerTokenExtractor.java` | 1:1 | **PASS** | ✅ **批次 D 已修**：⚠️ **有意与 B 侧不同的一处** —— B 侧用 `length() > 7`、上游用 `len(tok) > 6` ⇒ 「`Bearer ` 正好 7 字符时上游剥成空串、B 侧不剥」⇒ 当时**L1 以上游为准**（AGENTS.md §五.5）⇒ **D16 后基准改为 B 侧** ⇒ 本条转为**签字偏离**（C ≠ B，登记见 `docs/Intentional_Deviations/global/`）⇒ 已在 javadoc 留痕；⚠️ B 侧的**两套互不相同**的取法（验签 vs 黑名单 key）**各自保留**（合并即等于擅自修好 B 侧的 bug） |
| `users-common/cryptx/PasswordUtil.java` | 口令 | `security/PasswordUtil.java` | 1:1 | **PASS** | ✅ **批次 D 已修**：抄 RAG 整段（`Outcome{MATCHED,MISMATCHED,MALFORMED_HASH}`）⇒ ⚠️ **B 侧 `check()`** 把「密码不匹配」与「hash 本身非法」**压成同一个 false** ⇒ **畸形 hash 不得抛异常**；⚠️ 依赖 `spring-security-crypto`（**只取 bcrypt 那一小块** ⇒ 不引整个 spring-security ⇒ 共享层不引入 FilterChain / SecurityContext） |

---

## 11.3 多文件合并和拆分专项报告

### 11.3.1 N:1 合并（B 多 → C 1）

| 合并点 | B 源文件 | C 承接 | 逐项核对结论 |
|---|---|---|---|
| **HTTP 保护链** | `GoStyleBreakerFilter` + `GoStyleCpuSheddingFilter` + `GoStyleMaxBytesFilter` + `GoStyleMaxConnsFilter` + `GoStyleStatusCapturingResponse` + `GoStyleRouteTemplateResolver` + `GoStyleRouteResolution` + `GoStyleRouteTemplateSource` + `GoStyleProtectionConfiguration` + `GoStyleBreakerRouteTemplateAutoConfiguration` | `autoconfigure/ProtectionAutoConfiguration.java`（+ `protection/` 5 类） | ⚠️ **职责断裂**：B 的 4 层 Filter（MaxConns/Breaker/Shedding/MaxBytes）在 C 侧只剩 **3 层**（MaxConns 缺失）；模板解析被内联 ⇒ `RouteTemplateSource`/`RouteResolution` 成死代码；**唯一承接完整的是 MaxBytes 与 CPU 读数** |
| **gRPC 链** | `GoStyleRpcProtectionChain` + `GoStyleRecoverInterceptor` + `GoStyleStatInterceptor` + `GoStyleStatMetrics` + `GoStylePrometheusInterceptor` + `GoStylePrometheusMetrics` + `GoStylePrometheusSink` + `MicrometerPrometheusSink` + `GoStylePrometheusMeterRegistryBridge` + `GoStyleRpcBreakerInterceptor` + `GoStyleRpcFailurePolicy` + `GoStyleRpcSheddingInterceptor` + `GoStyleSheddingStat` | `autoconfigure/ProtectionAutoConfiguration.java` 的 `compose(List)` + `ObservabilityAutoConfiguration` 的 `GrpcObservationInterceptor` | 🔴 **5 层全缺**（recover/stat/prometheus/breaker/shedding）⇒ **DRIFT-COMMON-V03**。✅ **但合并本身做得对**：`compose(List)` **显式嵌套**（列表第 0 个最外层）+ 只调一次 `server.intercept(...)` ⇒ 不押「最后 add 的最外层」这个易记反的 API 事实 ⇒ **比 B 的 10 处分散挂载更健壮** |
| **社区事实类型** | `Wire.java`（1 文件含 4 wire 类型 + 校验 + sender + 投递循环） | 13 个 `communityfact/` 类 | ✅ **拆分无断裂**：调用链完整（`EventDispatcher` → `EventSender` → `Wire.strictDecode` / `CanonicalHash`）、数据完整传递、异常正确传播（`IOException("trailing JSON")` 保留）、无重复执行、无遗漏执行。⚠️ 初始化/销毁时机未变（无 `@PostConstruct`/`@PreDestroy`） |
| **雪花** | `Snowflake` + `SnowflakeBean` + `SnowflakeProperties` | `snowflake/Snowflake.java` + `autoconfigure/SnowflakeAutoConfiguration.java` | ✅ 无断裂：状态唯一（`STATE` 静态）、workerId first-wins、`nextId()` 前的初始化顺序保留 |
| **Redis** | `GoStyleRedisDeadline*` 4 类 + `GoStyleRedisClientAutoConfiguration` | `redis/` 4 类 | ✅ **拆分无断裂且已接线**（✅ V01 已修：2 个 auto-config 登记进 imports ⇒ 链路连通） |
| **日志选项** | `WithUserID` + `WithArticleID` | `logger/LogOption.java` 的 2 个静态工厂 | ✅ 行为一致、**L1 日志契约未破**（选项集合仍 2 个） |
| **Jackson 反序列化器** | B 侧散落 `article-api` / `like-api` / `message-api` / `users-common` | `web/` 5 个 deserializer + `Coercion` | ✅ 域内**零副本**（实测 11 类各 0 份）⇒ 无重复注册风险 |

### 11.3.2 1:N 拆分（C 多承接 1 个 B）

| B 源 | C 承接 | 调用链完整性 | 数据传递 | 异常传播 | 事务范围 | 重复执行 | 遗漏执行 | 初始化/销毁时机 |
|---|---|---|---|---|---|---|---|---|
| `Wire.java` | 13 类 | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `Logger.java` | `GoStyleLogger` + `LogOption` + `LogOptions` + `FatalError` | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `GoStyleProtectionProperties` | `ProtectionProperties`（独立成类，L-15） | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `RpcInterceptor.DeadlineState`（嵌套） | `observability/DeadlineState.java`（提升顶层，L-16） | ✅ | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |
| `GoStyleOtelConfig` | `observability/GoStyleOtelConfig.java`（**B ↔ C 1:1**，批次 F 从 `ObservabilityProperties` 归位） | ✅ **3 项齐备且真正驱动装配**（`protocol`/`sampler`/`endpoint` ⇒ exporter + sampler + 协议校验） | ✅ | ✅ | n/a | ✅ | ✅ | ✅ |

### 11.3.3 共享能力上收（§8.4）

| 上收项 | 原消费方（B） | C 侧覆盖情况 | 结论 |
|---|---|---|---|
| 11 个 Jackson/HTTP 工具类 | 分散在 `article-api`/`like-api`/`message-api`/`users-common` | 统一落 `common/web/`，**域内零副本** | ✅ **无覆盖遗漏** |
| JWT/口令 3 类 | `users-common` | 落 `common/security/`，**但全是空壳** | 🔴 **上收了没实现** ⇒ **DRIFT-COMMON-V07** |
| gRPC 拦截链挂载点 | **B 侧 10 个 rpc 模块各自 1 处** | C 侧共享层 **1 份** + 域内 0 重复 | ✅ **C 优于 B**（B 的 message/user/admin 三域曾静默失效；现已全部覆盖） |
| MP 排序桥 | B 侧 9 份域内副本 | C 侧共享层 1 份 | ✅ 无重复（守门 A6 PASS） |
| `AutoConfiguration.imports` 登记面 | B 侧 8 行 | C 侧 **10 行** | ✅ **已补齐**（8 个 `autoconfigure/` ＋ 2 个 `redis/`）⇒ ✅ **V01 已修**；守门已扩成「**每个 `@AutoConfiguration` 类都必须有激活路径**」 |
| `commonfact` 消费方 | B 侧 like/favorite/comment 三域 | C 侧**尚无消费方**（域为空壳） | ⬜ 待域实现时核 |

### 11.3.4 独立进程合并（§8.5）

B 侧 `service/common` **不是独立可执行进程**（是被 10 个 rpc + 10 个 api 应用的 `scanBasePackages` / `@Import` 消费的无状态 jar）⇒ **本节不适用**。C 侧 starter 化是同一形态的等价演进。

---

## 11.4 框架版本差异报告

| # | 机制 | B（Boot 4.0.8） | C（Boot 3.5.11） | 具体变化 | 影响的执行流程 | 是否影响业务行为 | 适配方式 | 验证证据 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| F-1 | Jackson 主版本 | Jackson 3（`tools.jackson.*`） | Jackson 2（`com.fasterxml.*` 2.19.4） | API 包名全换；coercion API 形态不同 | 请求体反序列化 + 响应序列化 | **可能**（数字/字符串强转、空值、未知字段、尾随 token） | `web/Coercion` 走 `Jackson2ObjectMapperBuilder#postConfigurer` → `ObjectMapper#coercionConfigFor`（**K-01，不替换 Boot 的 ObjectMapper**） | `CoercionTest`(10) + `JsonBodyConverterTest`(14) + `Float64DeserializerTest`(9) + `MapStringValueDeserializerTest`(7) + `RawStringListDeserializerTest`(12) = **52 例** | **已适配** |
| F-2 | Spring 转换器基类 | `JacksonJsonHttpMessageConverter`（Spring 7 独有） | **Spring 6.2 无此类** ⇒ 改判 `MappingJackson2HttpMessageConverter` | 覆写点签名从 `ResolvableType` 版改 `Type` 版 | 请求体读取 | 否（分支顺序保证 Generic 版即被调用） | `JsonBodyConverter` 的 `canRead`/`canWrite` 改 Type 版三参 | `JsonBodyConverterAutoConfigurationTest`(7) | **已适配（K-02）** |
| F-3 | `Jackson2ObjectMapperBuilder` coercion | Spring 7 有 coercion API | Spring 6.2 **无** | 只能走 `postConfigurer` | Boot `ObjectMapper` 的强转配置 | 否（同一 `MutableCoercionConfig` 语义） | 同 F-1 | 同 F-1 | **已适配（K-01）** |
| F-4 | `LettuceClientOptionsBuilderCustomizer` 包名 | `org.springframework.boot.data.redis.autoconfigure.*` | `org.springframework.boot.autoconfigure.data.redis.*` | `@ConditionalOnClass` 判定依据变了 | Redis 客户端选项定制 | **会**（不改 ⇒ 整份自动配置**静默不生效**） | ✅ **已回改**（`redis/ClientAutoConfiguration.java:13`） | 源码级：包名正确；⚠️ **但该类不在 imports ⇒ 回改了也不加载** ⇒ 被 **DRIFT-COMMON-V01** 掩盖 | **已回改，但当前无效** |
| F-5 | `DataSourceAutoConfiguration` 包名 | `org.springframework.boot.jdbc.autoconfigure.*` | `org.springframework.boot.autoconfigure.jdbc.*` | 排序边的字符串目标变了 | MP 自动配置求值顺序 | 否（不回改 ⇒ **排序边静默失效**，字符串不解析、不报错） | ✅ **已回改** | 源码级：`autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | **已适配** |
| F-6 | `AutoConfiguration.imports` 登记面 | 8 行 | **7 行** | **少 2 行** | starter 能力装载 | **会**（2 个 redis auto-config 永不加载） | ❌ **未适配** | 源码级：diff 两份清单 | 🔴 **DRIFT-COMMON-V01** |
| F-7 | Tomcat | 11.0.24 | **10.1** | 内部类/反射链可能变化 | `GoStyleClientDisconnectProbe` 的 socket 反射写 | **会**（B 的取证全部基于 Tomcat 11） | ⚠️ C 侧该类**尚未实现** ⇒ 暂不适用 | — | **BLOCKED**（实现时须在 10.1 重测） |
| F-8 | devh grpc starter | 3.1.0.RELEASE（grpc 1.63.0） | **同** | 无 | gRPC 拦截链挂载 | 否 | `ProtectionAutoConfiguration` 声明 `GrpcServerConfigurer` 并真调 `server.intercept(...)` | `ProtectionAutoConfigurationWiringTest`(5) | **已适配（A3 PASS）** |
| F-9 | `ResponseBodyAdvice` 收集机制 | Spring 7 | Spring 6.2：`RequestMappingHandlerAdapter#setResponseBodyAdvice` **无 `@Autowired`** ⇒ 不会自动收集 | `ResponseBodyAdvice` bean 需靠 `@RestControllerAdvice` 进 advice 链 | JSON 响应 Content-Type 补 charset | **会**（漏收集 ⇒ Content-Type 无 charset） | `JsonContentTypeAdvice` 同时挂 `@RestControllerAdvice` **且**由 `WebAutoConfiguration` 的 `@Bean` 显式注册 | 源码级：`WebAutoConfiguration.java:115-118` | **已适配** |
| F-10 | `SpringBoot4-Jackson3` 专属 artifact | `spring-boot-data-redis` / `spring-boot-tomcat` | **不存在** | 依赖坐标需换 | Redis / Tomcat 装配 | 否 | C 用 `spring-boot-starter-data-redis` / starter 自带 tomcat | 源码级：`common/pom.xml` | **已适配（D14）** |
| F-11 | Kafka 自动配置模块 | Boot 4 拆成 `spring-boot-kafka` | Boot 3.5 在 `spring-boot-autoconfigure` 内 | **Boot 3.5 无此风险** | `@KafkaListener` 消费 | 否 | C 用 `org.springframework.kafka:spring-kafka`（**零排除**） | `common/pom.xml` + AGENTS.md §3.3.3 订正 | **已适配（D14）** |
| F-12 | OTel / tracing | `GlobalOpenTelemetry` + OTLP exporter + `parentBased(traceIdRatio)` | ✅ **批次 F 已补**：`GoStyleOtelAgent`（SDK + OTLP exporter + 传播器 + shutdown hook）+ `GoStyleOtelConfig`（上游 `Telemetry` 四字段投影）+ `GoStyleOtelAutoConfiguration`（**BFPP** 装配点） | 无（全局 SDK 已装 ⇒ span 有效且可导出） | 观测埋点 / `traceparent` 响应头 | **不会**（`traceparent` 头已恢复；span 已可导出） | ① BFPP 装配点 ＋ ② `otel.*` 键名与 B 同构 ⇒ 配置契约零漂移 | `GoStyleOtelAgentTest`(7) + `GoStyleOtelConfigTest`(13) + `TraceparentFilterTest`(4) | **PASS（批次 F 已闭合；差异保留在本清单，§十「可判 PASS」）** |
| F-13 | Resilience4j | **B 无此依赖**（手写 googlebreaker 移植） | `resilience4j-spring-boot3` | 熔断实现换实现 | HTTP 熔断 | **会**（拒绝语义类型都不同） | D4 生态优先 | `RouteIsolationFilterTest`(4) 只覆盖 key + 判据 | **INTENTIONALLY_CHANGED（D-02 已登记，但差异比登记更大 ⇒ DRIFT-COMMON-V09）** |
| F-14 | Actuator | B 侧 `service/common` **无** actuator 依赖 | C 侧有 `spring-boot-starter-actuator` | 降载观测面换载体 | CPU 降载 | 否 | `CpuThresholdHealthIndicator` | 源码级 | **已适配（D4）** |
| F-15 | `@ConfigurationProperties` 注册 | B 侧 `GoStyleProtectionConfiguration` 用 `@EnableConfigurationProperties`（靠扫描） | C 侧 `ProtectionAutoConfiguration` 用 `@EnableConfigurationProperties(ProtectionProperties.class)`（靠 imports） | 注册面不同 | 保护层配置 | 否 | D3 starter 化 | `ProtectionProperties` 12 项默认值逐条核对一致 | **已适配** |

---

## 11.5 语义漂移清单

> **状态**：🔴 = 必须在填域实现前处置；🟠 = 登记后放行；⚪ = 已登记沿用。
> **纪律**（§13）：本报告**只记录证据与修复建议**，**未改任何代码**。

### ✅ DRIFT-COMMON-V01｜`redis/` 两个自动配置不在 `AutoConfiguration.imports` ⇒ 两项能力静默失效

**状态**：**✅ 已修复**（2026-11-02 · 批次 A）⇒ 两个 redis auto-config 已登记进 `AutoConfiguration.imports` ⇒ 对拍：`RedisAutoConfigurationActivationTest`（3 例）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/redis/ClientAutoConfiguration.java`、`redis/DeadlineAutoConfiguration.java` |
| **B 源** | `service/common/redis/GoStyleRedisClientAutoConfiguration.java`（imports **第 5 行**）· `GoStyleRedisDeadlineAutoConfiguration.java`（imports **第 6 行**） |
| **B 行为** | ① Lettuce 断连 ⇒ `ClientOptions.DisconnectedBehavior.REJECT_COMMANDS`（对齐上游「Redis 失败 ⇒ 尽快失败 ⇒ 上层 fail-open」）；② `BeanPostProcessor` 把各 `RedisTemplate` 的 connection factory 换成预算感知包装版 ⇒ **请求预算耗尽 ⇒ 不再下发 Redis 命令** |
| **C 行为** | 两类**实现完整**（146 行 / 98 行，Boot 3.5 包名已回改），但：① **不在** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；② **全仓零引用**（grep 除自身外 0 命中）；③ 域侧**无 `@Import`**；④ 域应用**无 `@ComponentScan("com.rag.common")`** ⇒ **`@AutoConfiguration` 永不加载 ⇒ 静默失效、零报错** |
| **差异原因** | 批次 1 落地时把类放进了 `redis/` 包，但只把 `autoconfigure/` 目录下的 7 个类登记进了 imports |
| **影响范围** | **全部 10 个域**的 Redis 访问：断连拒单语义丢失（fail-fast → 默认 `REJECT_COMMANDS` 之外的行为）、请求预算耗尽后仍下发 Redis 命令 ⇒ **超时预算失效、级联放大** |
| **严重程度** | **✅ 已修复**（原：🔴 严重 —— 静默失效 + 10 域受影响 + 与 B 侧语义直接相反） |
| **✅ 修法（2026-11-02 · 用户选定）** | ① 两行加进 `common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（清单 7 → **10 行**）；② **守门扫描范围同步扩大** —— `AutoConfigurationImportsTest` 的判据从「`autoconfigure/` 目录 == imports 清单」改成「**全模块每个 `@AutoConfiguration` 类都必须有激活路径**（清单登记 或 被某个 auto-config 的 `@Bean` 实例化）」⇒ 覆盖 `redis/` 等**别的包**下的 auto-config ⇒ **同类缺口当场变红** |
| **验证方法（已落地）** | ① **行为真触发**：`RedisAutoConfigurationActivationTest`（3 例）—— 真调 customizer 断言 `DisconnectedBehavior == REJECT_COMMANDS` ＋ 断言 `RedisTemplate.getConnectionFactory()` 真的变成 `DeadlineConnectionFactory` ＋ 幂等（不二次包装）；② **注册真触发**：`AutoConfigurationImportsTest` 用 **Boot 真实的 `AutoConfigurationImportSelector`** 从 **classpath** 清单选出本模块 auto-config ⇒ 与源码清单逐条一致；③ **变异实测**：把这两行从 imports 摘掉 ⇒ 激活路径不变式**立刻变红**（实测 104 例 1 红） |

### ✅ DRIFT-COMMON-V02｜SQL 超时链路两个 MyBatis 拦截器零注册 ⇒ 整条链不生效

**状态**：**✅ 已修复**（2026-11-02 · 批次 A）⇒ 新增 `autoconfigure/PersistenceAutoConfiguration` 注册两个 MyBatis 拦截器 ⇒ 对拍：`PersistenceAutoConfigurationTest`（3 例，用**与 MP 同形**的 `ObjectProvider<Interceptor[]>` 注入点断言真的收到了它们）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/persistence/DeadlineQueryTimeoutInjector.java`（321 行）、`SqlTimeoutStatusTranslator.java`（154 行） |
| **B 源** | 同名两文件；B 侧**不在 imports**，而是由 **5 个 rpc 应用的 `@Bean`** 逐个注册（`FollowRpcApplication:90` / `MessageRpcApplication:85` / `LikeRpcApplication:94` / `ArticleRpcApplication:88` / `UserRpcApplication:67`） |
| **B 行为** | ① `StatementHandler.prepare` 拦截 ⇒ 按线程 `DeadlineState` 设 `setQueryTimeoutMs` ⇒ **取消时刻 == deadline 时刻**（对齐 B 侧的 ctx-aware 查询）；② `Executor` 拦截 ⇒ pg `57014` ⇒ gRPC `DEADLINE_EXCEEDED` + description `"sql statement canceled at rpc deadline"` ⇒ 域侧落业务码 **1015**（而非 DB 故障 5001）。**两者必须成对** |
| **C 行为** | 两个类**实现完整**（含 `Wrapper.unwrap` 剥 HikariCP 池代理 ≤5 跳、毫秒精度 → 秒级向上取整回落、cause 链遍历），但**全仓零注册点**（不在 imports、不在任何 `@Bean`、MP 自动配置不会自动收 `Interceptor`）⇒ **SQL 不带超时、57014 不翻译** |
| **差异原因** | B 侧的注册义务在**域内**（域已实现）；C 侧域还是空壳 ⇒ 注册点无人写；而共享层**没有**承接这个注册义务（starter 化后应由 auto-config 承担） |
| **影响范围** | 全部走 DB 的域：SQL 超时**不生效**（长查询不被取消）、pg 取消被当 DB 故障上报（5001 而非 1015） |
| **严重程度** | **✅ 已修复**（原：🔴 严重 —— 两处行为同时丢失 + 超时预算链断） |
| **✅ 修法（2026-11-02 · 用户选定）** | 新增 `common/src/main/java/com/rag/common/autoconfigure/PersistenceAutoConfiguration.java`（`@AutoConfiguration` ＋ `@ConditionalOnClass(name="org.apache.ibatis.plugin.Interceptor")` ＋ 两个带 `@ConditionalOnMissingBean` 的 `@Bean`）⇒ **注册义务从 B 侧的「域内 5 份 `@Bean`」上收到共享层 1 份**（D3 starter 化 ⇒ 10 个域一次全挂上）；**不**放回域内（那会回到 B 的老路） |
| **验证方法（已落地）** | ① **机制取证**：`javap -c`（MP 3.5.17）⇒ `MybatisPlusAutoConfiguration` 构造器形参是 `ObjectProvider<Interceptor[]>` 且**构造器内即** `getIfAvailable()` ⇒ 注册成 Bean 即被收得到；② **真触发测试**：`PersistenceAutoConfigurationTest`（3 例）—— 用**与 MP 完全同形**的注入点断言**真的收到这两个拦截器**（不是「Bean 存在」这种弱证据）＋ 域内已有同类型时共享层让位；③ **变异实测**：把 `PersistenceAutoConfiguration` 从 imports 摘掉 ⇒ 激活路径不变式 + 「目录 ⊆ 清单」双红（实测 104 例 2 红） |

### ✅ DRIFT-COMMON-V03｜gRPC 服务端保护链 5 层全缺（recover / stat / prometheus / breaker / shedding）

**状态**：**✅ 已修复**（2026-11-02 · 批次 B）⇒ 5 层链 + 客户端熔断 + 出向 deadline + Prometheus 写入面 + 6 码失败白名单 + 链序全部落地 ⇒ 对拍：`ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo`（**真的搭出链**逐层断言）+ `ClientProtectionAutoConfigurationTest`（devh **真实收集器**）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 的 `compose(List)` |
| **B 源** | `service/common/protection/GoStyleRpcProtectionChain.java`（链序唯一定义点）+ `GoStyleRecoverInterceptor` / `GoStyleStatInterceptor` / `GoStyleStatMetrics` / `GoStylePrometheusInterceptor` / `GoStylePrometheusMetrics` / `GoStylePrometheusSink` / `MicrometerPrometheusSink` / `GoStylePrometheusMeterRegistryBridge` / `GoStyleRpcBreakerInterceptor` / `GoStyleRpcFailurePolicy` / `GoStyleRpcSheddingInterceptor` / `GoStyleSheddingStat` |
| **B 行为** | 链序（外→内）**recover → stat → prometheus → breaker → shedding → business(观测)**；① **recover**：`catch(Throwable)` ⇒ `Status.INTERNAL` + description 前缀 `"panic: "`（**wire 可见**）；② **stat**：每 60s 一行聚合日志；③ **prometheus**：两个 meter（`rpc_server_requests_duration_ms{method}` 12 桶 DistributionSummary + `rpc_server_requests_code{method,code}`）；④ **breaker**：隔离 key = `getFullMethodName()`（`/pkg.Svc/Method`）+ **6 码失败白名单**（`DEADLINE_EXCEEDED`/`INTERNAL`/`UNAVAILABLE`/`DATA_LOSS`/`UNIMPLEMENTED`/`RESOURCE_EXHAUSTED`）；⑤ **shedding**：`RESOURCE_EXHAUSTED` + description `"service overloaded"` |
| **C 行为** | 链上**只有 1 层**（业务观测 `GrpcObservationInterceptor`）⇒ panic 不被 recover（客户端拿到 grpc-java 默认的 `UNKNOWN` + **description 为 null**）⇒ 与 B 侧的 `codes.Unknown` + `err.Error()` **不同**；无熔断、无降载、无 stat、无 prometheus |
| **差异原因** | 批 3 只搬了 HTTP 侧的 D4 生态重写，gRPC 侧的保护/观测层未搬（已知「gRPC 侧 recover / breaker / shedding 拦截器未搬」）⇒ **但从未被登记进 L3 降级清单** |
| **影响范围** | 全部 10 个域的 gRPC 入口：panic 语义漂移、无熔断保护、无降载保护、无 RPC 指标面、无 RPC 统计日志 |
| **严重程度** | **🔴 严重**（panic 的 description 是 wire 级差异；保护能力整体缺席） |
| **修复建议** | ① **先把这条登记进 `docs/L3-降级清单.md`**（当前是「未登记的缺口」）；② 实现时按 B 的链序在 `compose(List)` 里**显式**列出 5 层（`compose` 已是显式嵌套 ⇒ 加层不需改机制）；③ 熔断沿用 resilience4j，但**成败判据必须换成 6 码白名单**（HTTP 的 `<500` 判据不能套到 gRPC）；④ recover 的 `"panic: "` 前缀是 **L1 wire 契约** ⇒ 必须保留 |
| **验证方法** | ① 造一个抛 `RuntimeException("boom")` 的 handler ⇒ 断言客户端收到 `INTERNAL` 且 description **以 `"panic: "` 开头**；② 断言 `compose(props).allLayerNames()` = `[recover, stat, prometheus, breaker, shedding]` |
| **✅ 修复状态** | **2026-11-02 已修（批次 B）** —— ① **服务端 5 层**：`RpcRecoverInterceptor` / `RpcStatInterceptor` / `RpcPrometheusInterceptor` / `RpcCircuitGateInterceptor` / `RpcSheddingInterceptor`（全部抄写 RAG，L2 逐条保留）；② **链序**：`ProtectionAutoConfiguration` 的 `RPC_LAYER_*` 常量（0..4）＋ `RpcProtectionChain.compose` 显式嵌套 ⇒ 链序 = `[recover, stat, prometheus, breaker, shedding, business]`（B 侧注册序，`business` = 业务观测层）；③ **失败判据**：`RpcFailurePolicy`（6 码白名单，**业务错误不参与熔断**）；④ **客户端侧**：`ClientCircuitGateInterceptor`（隔离 key 含下游服务身份）＋ `OutboundDeadlineInterceptor`（min 语义，per-service 上界是「覆盖」）；⑤ **装配**：`ClientProtectionAutoConfiguration`（starter 化，消费方零 `@Import`）。<br>**对拍**：`ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo`（真的搭出链逐层断言）、`RpcRecoverInterceptorTest`（4 例，锁 `"panic: "` 的 **L1 wire 契约**）、`RpcProtectionLayerTest`（8 例，锁降载拒绝形状 / 6 码白名单 / 客户端隔离 key / 出向 deadline 的 min 语义 / **降载双条件**）、`ClientProtectionAutoConfigurationTest`（2 例，锁 devh **真实收集器**收到两个拦截器）。<br>**变异实测**：把 `RPC_LAYER_BREAKER` 改成 0 ⇒ 链序断言**转红** ⇒ 判据活着。<br>⚠️ **伴随暴露的一个真实回归（已修）**：`ObservabilityAutoConfiguration.grpcObservationInterceptor` 的 `@ConditionalOnMissingBean` **按返回类型 `ServerInterceptor` 判缺失** ⇒ 新增 5 个 `ServerInterceptor` Bean 后**业务观测层被静默跳过**（零报错）⇒ 改按 **bean 名**判 ⇒ 教训登记 `docs/Pitfalls/`。<br>⚠️ **D4 代价（已登记 L3）**：Prometheus 写入面丢掉 RAG 的「接缝接口 ＋ 静态 holder」（改构造器注入 `MeterRegistry`）⇒ **指标桶布局不等价**（B 侧显式 12 桶 vs Micrometer 默认 summary）；熔断器本体统一用 resilience4j ⇒ **机制类型**与 B 侧的 googlebreaker 不同（挂 **D-02** 的 L3）。 |

### ✅ DRIFT-COMMON-V04｜HTTP 超时 / 客户端断连中间件缺失（503 + 499）

**状态**：**✅ 已修复**（2026-11-02 · 批次 C）⇒ HTTP 超时 503 ＋ body `Request Timeout`（**16** 字节）＋ 断连 **499** ＋ **512** worker 池 ＋ 池饱和裸 503 ＋ `ClientDisconnectProbe` 4 态 ⇒ 对拍：`HttpMiddlewareTest`（11 例）；**4 次变异实测**每次都变红（其中一次「探针断连 499→500」最初**没变红** ⇒ 补了探针接缝后才变红 ⇒ 记 **P-12**「变异没变红 ＝ 没覆盖」）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` |
| **B 源** | `service/common/observability/HttpMiddleware.java`（58 KB）+ `spring/HttpMiddlewareFilter.java` |
| **B 行为** | ① 静态 worker 池（`MAX_HTTP_WORKERS=512`，线程名 `obs-http-worker`）上跑业务；② `observability.http-timeout` 超时 ⇒ **503** + body `Request Timeout\n`（Content-Length 16）+ `sea.error.kind=timeout`；③ 客户端断连（`EOFException` / 类名含 `ClientAbort`）⇒ **499**（不是 500）；④ 异常从 worker 逃出 ⇒ **原样重抛**（`ServletException`/`IOException`/`RuntimeException`/`Error` 各按原类型）不转信封 |
| **C 行为** | 全仓 grep `499` / `EOFException` / `ClientAbort` / `SC_CLIENT_CLOSED` **零命中** ⇒ **无超时、无断连、无 worker 池、无异常重抛语义** ⇒ 业务直接在容器线程跑 |
| **差异原因** | Spring 无 499 概念；HTTP 超时中间件整块未搬（已知「HTTP 超时中间件（503 / 499）未实现 ⇒ 登记为 L3」⇒ **已登记**） |
| **影响范围** | HTTP 入口：慢请求不超时、客户端断连记 500 而非 499、异常传播路径不同 |
| **严重程度** | **🔴 严重**（但**已登记**为 L3 ⇒ 本条是「确认登记」而非新增） |
| **修复建议** | 确认 L3 登记条目在 `docs/L3-降级清单.md` 里可检索；实现时 499 需自造（Tomcat 无此状态码 ⇒ 需确认容器是否允许自定义 4xx） |
| **验证方法** | 真 Tomcat：`observability.http-timeout=100ms` + 慢端点 ⇒ 断言 503 + body `Request Timeout\n`；断连场景需真实 socket |

### ✅ DRIFT-COMMON-V05｜HTTP 熔断「模板取不到」的回落语义与 B 相反

**状态**：**✅ 已修复**（2026-11-02 · 批次 A）⇒ 回落语义已与 B 一致：`UNKNOWN`（模板判不出来）⇒ **回落 raw URI 分桶、熔断仍生效**；`NOT_MATCHED` ⇒ 本层完全不参与 ⇒ 对拍：`RouteIsolationFilterTest`（6 例）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 的 `RouteIsolationFilter.isolationKeyOf` / `routeTemplateOf` |
| **B 源** | `service/common/protection/GoStyleBreakerFilter.java`（`resolveRoute` + `routeKey`）· `GoStyleRouteTemplateResolver.java`（三态） |
| **B 行为** | 三态：`TEMPLATE` ⇒ 按模板分桶；**`NOT_MATCHED`（404/405）⇒ 完全跳过 breaker**（对齐 B 侧）；**`UNKNOWN`（mapping 列表为空 / 抛异常 / handler 非 `HandlerMethod` / 拿不到 pattern）⇒ 回落按 raw URI 分桶**（`GET:///article/1`）⇒ **熔断保护仍生效**（B 的注释明写「绝不静默关掉熔断」） |
| **C 行为** | 两态：`isolationKeyOf` 返回 `null` ⇒ **`chain.doFilter` 放行** ⇒ **熔断完全不生效**。⚠️ **C 的 javadoc 自称「绝不静默关掉熔断保护」，与实现相反** |
| **差异原因** | C 侧把 B 的三态压成「拿到 / 拿不到」，把 `UNKNOWN` 误并入「放行」 |
| **影响范围** | 路由模板解析失败的所有场景（容器初始化早期、`HandlerMapping` 非 `RequestMappingHandlerMapping`、handler 非 `HandlerMethod`）⇒ **熔断静默消失** |
| **严重程度** | **🔴 严重**（保护能力的静默消失 + 注释与实现自相矛盾） |
| **修复建议** | 恢复三态：`NOT_MATCHED` ⇒ 跳过 breaker；`UNKNOWN` ⇒ **回落 raw URI 分桶**（与 B 一致）。⚠️ 修正 javadoc 那句自相矛盾的表述 |
| **验证方法** | 构造一个 `RequestMappingHandlerMapping.getHandler` 抛异常 ⇒ 断言请求仍被 breaker 计数（而非直接放行） |
| **✅ 修复状态** | **2026-11-02 已修（批次 A）** —— `RouteIsolationFilter` 改经窄接口 `RouteTemplateSource` 取三态；`UNKNOWN` ⇒ **回落 raw URI 分桶**（熔断仍生效）、`NOT_MATCHED` ⇒ 本层完全不参与。对拍：`RouteIsolationFilterTest` 6 例；**变异实测**：把回落改回「放行」⇒ **2 例红**（`unknownTemplateFallsBackToRawUriAndBreakerStillApplies` / `absentTemplateSourceFallsBackToRawUri`）|

### ✅ DRIFT-COMMON-V06｜三个「窄接口」是空类且零引用；模板解析职责断裂

**状态**：**✅ 已修复**（2026-11-02 · 批次 A）⇒ 三个窄接口落地为真实现（`RouteTemplateSource` / `RouteResolution` / `RouteTemplateResolver`）＋ 消费方改走窄接口 ⇒ 对拍：`RouteTemplateResolverTest`（5 例）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/observability/ClientDisconnectProbe.java` · `common/src/main/java/com/rag/common/protection/RouteResolution.java` · `common/src/main/java/com/rag/common/protection/RouteTemplateSource.java` |
| **B 源** | `GoStyleClientDisconnectProbe.java`（探针，4 态 + 反射链）· `GoStyleRouteResolution.java`（三态 record）· `GoStyleRouteTemplateSource.java`（`@FunctionalInterface` 窄接口） |
| **B 行为** | 三个类**各有实质内容**：探针写 `HTTP/1.1 103 Early Hints\r\n\r\n` 探针（4 态 `ALIVE`/`DISCONNECTED`/`UNAVAILABLE`/`BUDGET_EXHAUSTED`）；三态 record（`Kind{TEMPLATE,NOT_MATCHED,UNKNOWN}`）；窄接口让 Filter 不直接依赖 spring-webmvc |
| **C 行为** | 三个类**类体为空**（只有 javadoc）⇒ **全仓零引用**（grep 除自身外 0 命中）⇒ **死代码**。且 `RouteIsolationFilter` **绕过** `RouteTemplateSource` 直接注入 `RequestMappingHandlerMapping` ⇒ **映射表 §1.12 声称的「路由模板解析不能省这条前提已被载体兑现」不成立** |
| **差异原因** | 批 1 建了接口占位，批 3 的 D4 重写走了内联实现 ⇒ 接口未被消费 |
| **影响范围** | ① `ClientDisconnectProbe` 能力缺席（与 **DRIFT-COMMON-V04** 同源）；② `RouteTemplateSource`/`RouteResolution` 是死代码 ⇒ 映射表 §1.12 登记表与实际不符；③ 与 **DRIFT-COMMON-V05** 叠加（正因为绕过了窄接口，三态能力一并丢失） |
| **严重程度** | **🔴 严重**（登记表与实现不符 + 职责断裂） |
| **修复建议** | ① 修 **DRIFT-COMMON-V05** 时让 `RouteIsolationFilter` **经由** `RouteTemplateSource` 抽象取模板 ⇒ 一举恢复三态与窄接口的职责；② `RouteResolution` 落成三态值对象；③ `ClientDisconnectProbe` 要么实现（**注意 F-7：Tomcat 11→10.1 的反射链未验证**），要么**从 `common/` 移除并在映射表登记为「D4 生态吸收 / 暂不实现」** —— 现在这个「空类 + 映射表标 ✅ 已落地」的中间态最糟 |
| **验证方法** | 修完后：断言 `RouteTemplateSource` 有 1 个实现 Bean；断言三态可区分 |
| **✅ 修复状态** | **2026-11-02 部分修复（批次 A）** —— ① `RouteResolution` 落成三态 `record`（`Kind{TEMPLATE,NOT_MATCHED,UNKNOWN}`）；② `RouteTemplateSource` 落成 `@FunctionalInterface` 窄接口；③ **新增** `protection/RouteTemplateResolver.java`（实现类）+ `autoconfigure/RouteTemplateAutoConfiguration.java`（独立 auto-config，类级 `@ConditionalOnClass` ⇒ 不让 MVC 类型进 `ProtectionAutoConfiguration` 常量池）；④ `RouteIsolationFilter` **改为经窄接口取模板** ⇒ 职责断裂闭合。对拍：`RouteTemplateResolverTest` 5 例（三态全覆盖）+ `RouteIsolationFilterTest` 6 例。<br>⚠️ **仍未修**：`observability/ClientDisconnectProbe.java` 仍为空壳 ⇒ 归 **V04**（HTTP 断连 499）批次 C 处理，**Tomcat 10.1 反射链须重测（F-7）** |

### ✅ DRIFT-COMMON-V07｜空壳类实际 6 类，文档口径 3 类；`common/security/` 3 类上收了未实现

**状态**：**✅ 已修复**（2026-11-02 · 批次 D）⇒ `common/security/` 3 类落地（JWT 签发验签 / Bearer 提取 / bcrypt 口令 · jjwt 0.12.6 适配）⇒ 对拍：`SecurityUtilTest`（7 例）；⚠️ 「空壳类实际 6 类 vs 文档口径 3 类」的**口径**也已订正（当前 **0 空壳**）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/security/{JwtUtil,BearerTokenExtractor,PasswordUtil}.java`（TODO 空壳）+ 上文的 3 个窄接口 |
| **B 源** | `service/users/users-common/jwt/JwtUtil.java`（HS256 + secret 补齐 ≥32 字节）· `BearerTokenExtractor.java` · `cryptx/PasswordUtil.java` |
| **B 行为** | `JwtUtil.getToken(secretKey, ...)` 用 `SecretKeySpec(padToHmacKeySize(secretKey), "HmacSHA256")` + `SignatureAlgorithm.HS256`；secret 补齐到 ≥32 字节；**注意 `jjwt` 的 `SignatureAlgorithm` 是 0.x API**（0.12+ 改为 `Jwts.SIG.HS256`）⇒ C 侧实现时须确认版本 |
| **C 行为** | 3 类**全是空壳**（仅 TODO javadoc）⇒ **上收了但未实现**。⚠️ `common/pom.xml` 已引入 `jjwt-api`/`jjwt-impl`/`jjwt-jackson` ⇒ **依赖已到位、代码未写** |
| **差异原因** | 上收（§8.4）只搬了位置，未搬实现（与 `common/` 整体「68/71 已落地」的口径冲突） |
| **影响范围** | `AGENTS.md` §0 / §2.3 与 `docs/README.md` 的「**71 类中 68 类已落地 · 3 类空壳**」⇒ **实测是 65 已落地 · 6 空壳**（空壳 = security 3 + 窄接口 3）；JWT 能力缺席 ⇒ 10 个域的鉴权都无载体 |
| **严重程度** | **🟠 中**（能力缺席是已知的「域尚未实现」，但**文档口径错误**会误导后续判断） |
| **修复建议** | ① **改口径**：`AGENTS.md` §0/§2.3、`docs/README.md` 状态速查、`docs/Mapping_Table/common.md` §1.1~§1.11 的落点数 ⇒ 统一改成「**71 类中 65 已落地 · 6 空壳**」；② `ClientDisconnectProbe`/`RouteResolution`/`RouteTemplateSource` 若短期不实现 ⇒ 在映射表把 ✅ 改成 ⬜（§2.1 要求「映射表有记录、C 没实现」必须登记）；③ JWT 3 类随域实现推进 |
| **验证方法** | 口径核对用 `find common/src/main -name '*.java' -exec sh -c '[ $(wc -l < "$1") -le 12 ] && echo "$1"' _ {} \;` 逐条列（**当前 6 条**） |

### ✅ DRIFT-COMMON-V08｜映射表 §7.4 #6 的 B 列实况已过时

**状态**：**✅ 已修复**（2026-11-02 · 批次 H）⇒ 映射表 `docs/Mapping_Table/common.md` **§7.4 #6 的 B / C 两列实况已就地订正**：B 侧现在 **10 个 rpc 应用全部**声明了 `GrpcServerConfigurer`（原文「只有 6 个 ⇒ message / user / admin 静默失效」已过时）· C 侧批次 B 已修 ⇒ ⇒ 本条随之关闭。

| 项 | 内容 |
|---|---|
| **位置** | `docs/Mapping_Table/common.md` §7.4 第 6 行 |
| **映射表原文** | 「B：RAG 侧 10 个 rpc 模块**只有 6 个**声明了 `GrpcServerConfigurer`（like/comment/follow/favorite/task/hot）⇒ **message / user / admin 三个域的拦截链静默失效**」 |
| **实测** | `grep -rln "GrpcServerConfigurer" service/*/*/src/main` ⇒ **10/10 全部有**（article / comment / favorite / follow / hot / like / message / task / users-admin / users-user）⇒ **RAG 后来补齐了**，该登记已过时 |
| **影响** | 该条的「影响」结论（3 个域静默失效）**不再成立** ⇒ C 侧「共享层 1 份覆盖 10 域」相对 B 的优势，从「修复 3 个域的缺口」降级为「消除 10 处分散挂载」⇒ **结论方向不变但论据须改** |
| **严重程度** | **🟠 中**（历史资料与实况脱节；`verification.md` §2.1 明确要求「映射表中的历史路径必须检查是否仍然有效」） |
| **修复建议** | 映射表**本次未改**（§13 禁止修改 Mapping_Table）⇒ **仅在此登记**，由映射表 owner 更新 |
| **验证方法** | `grep -rln "GrpcServerConfigurer" --include=*.java service/ \| grep -v test` |

### ⚪ DRIFT-COMMON-V09｜HTTP 熔断实现换生态 ⇒ 拒绝语义**类型**都不同（比 D-02 登记的更重）

**状态**：**⚪ 已登记**（2026-11-02 · 批次 H ⇒ 已扩写进 `docs/L3-降级清单.md` **D-02** 的「HTTP 侧追加」行）⇒ **不再是未登记缺口**。
⚠️ **仍按 §十 不计入 PASS**（INTENTIONALLY_CHANGED）—— 机制类型不等价是**有批准依据的主动变更**（D4 生态优先）。
✅ **本次额外把「差距」变成可测量的数字**：HTTP 侧的**有效参数已实测并钉桩**（resilience4j 2.2.0：滑动窗口 **COUNT_BASED / 100** · 最小请求数 **100** · 失败率阈值 **50%** · open 等待 **60s** · 半开放放行 **10** · open→半开自动转换 **关**）⇒ 由 `HttpCircuitBreakerConfigPinTest` 守门 ⇒ **框架默认值一旦变形 ⇒ 测试立刻红 ⇒ 逼着回来重登记 D-02**。
⚠️ **刻意不把参数写进本仓配置**：那会**覆盖** resilience4j 的自动配置 ⇒ 等于**制造一次未经批准的行为漂移** ⇒ 保持「**继承生态默认值 + 钉桩**」。


| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java` 内嵌 `RouteIsolationFilter`（resilience4j） |
| **B 源** | `service/common/protection/GoStyleBreaker.java`（手写 googlebreaker 的**逐行复刻**） |
| **B 行为** | **概率性拒绝 + 无三态**：40 桶 × 250ms（窗口 10s，但 `history()` **跳过当前桶** ⇒ 实际 39 桶 ≈ **9.75s**）；`dropRatio = (total-5-weightedAccepts)/(total+1)`；`rand() < dropRatio` ⇒ 拒绝；**`FORCE_PASS_DURATION_MS=1000`** ⇒ 最长拒绝 1s 后**强制放行一个探测请求**（保底）；`markDrop()` 计入 total 但不计入 accepts |
| **C 行为** | resilience4j `CircuitBreaker`：滑动窗口**计数** + **打开/半开/关闭三态** + `tryAcquirePermission()` |
| **差异原因** | D4 生态优先（B 侧的手写熔断是补框架差距，本仓改用生态件） |
| **影响范围** | HTTP 熔断：拒绝**概率性 vs 计数性**、**有无三态**、**有无 force-pass 保底**、**窗口 9.75s vs resilience4j 默认 100s 计数窗口** |
| **严重程度** | **🟠 中**（**D-02 已登记「阈值参数不等价」，但实测差异不止参数 —— 语义类型都不同** ⇒ 需把 D-02 的表述扩写） |
| **修复建议** | ① 扩写 `docs/L3-降级清单.md` **D-02** 条目：把「阈值参数不等价」改成「**拒绝机制类型不等价**（概率性+保底放行 vs 计数+三态）」；② 显式配置 resilience4j 的窗口/阈值，使「连续 N 次失败即开」与 B 侧的行为差距**可测量**；③ 对拍脚本**显式排除**熔断差异（映射表 §1.13 已写这条纪律 ⇒ 照做） |
| **验证方法** | 固定随机源的 B 侧行为（`random=0.0` ⇒ 必拒绝）对照 C 侧同场景 ⇒ 记录**具体差距**，不追求一致 |

### ⚪ DRIFT-COMMON-V10｜降载统计日志缺席（CPU 判据那一半已登记 D-04，但日志那半没登记）

**状态**：**⚪ 已登记**（2026-11-02 · 批次 H ⇒ 新立 `docs/L3-降级清单.md` **D-08**「HTTP 侧降载的可观察面」）⇒ **不再是未登记缺口**。
⚠️ **⚠️ 定性订正（本次实测发现旧条目的前提有两处错）**：
① **「两个统计面都没有」已过时** —— gRPC 侧早在批次 B 就抄全了 `GoStyleSheddingStat`（三计数 + 每分钟日志）⇒ 缺口**仅限 HTTP 侧**；
② **它不是「C 丢了 B 的能力」，而是「B 侧原生就缺」** —— **B 侧 HTTP 侧并没有**自己那一份统计（`GoStyleCpuSheddingFilter` 根本没接 `SheddingStat` ⇒ 无 `serviceType="api"` + `IncrementTotal/Drop/Pass` + `[http] dropped` 行 + 1 分钟上报）⇒ ⇒ **C 逐字承接 B ⇒ C ≡ B ⇒ 不是 C 的迁移缺陷**（verification.md §1.1「B 本身存在已知问题 ⇒ 仍以 B 为基线，但必须记录」）⇒ ⇒ **本仓不改**（改了反而是未经批准的行为漂移）。
⚠️ **为什么仍要登记**：B↔C 的对拍脚本会因「B 侧基准行、C 侧缺」的这几行而全红 ⇒ 红了会被误判成回归 ⇒ 触发「修回旧行为」⇒ **降级被静默撤销** ⇒ ⇒ **D-08 已写明对拍脚本必须显式排除**。
✅ **与 D-04 拆开**：D-04 是**判据**缺口（已于批次 H 撤销）；本条是**统计面**缺口 ⇒ ⇒ **不挂 D-04**。


| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/protection/CpuThresholdHealthIndicator.java` |
| **B 源** | `service/common/protection/GoStyleSheddingStat.java`（降载三计数 + 每分钟一行日志）· `service/common/observability/GoStyleStatMetrics.java`（RPC 耗时聚合 + 每 60s 一行日志） |
| **B 行为** | ① 降载日志行逐字：`"(%s) %s [1m], cpu: %d, total: %d, pass: %d, drop: %d"`，tag = `drop==0 ? "shedding_stat" : "shedding_stat_drop"`，**先 reset 再打**；② RPC 统计日志行逐字：`"(name) - qps: %.1f/s, drops: %d, avg time: %.1fms, med: %.1fms, 90th: %.1fms, 99th: %.1fms, 99.9th: %.1fms"`，线程名 `go-style-stat-metrics`，且含 **3 个刻意的上游对齐怪癖**（avg 先整除再平均 / median = 第 `size>>1` 大 / 样本数 1 时 median/90/99/99.9 全等于最大值） |
| **C 行为** | 两个统计面**都没有** ⇒ 每分钟的降载统计行与 RPC 统计行**不产生** |
| **差异原因** | 「每分钟一行日志」是 B 侧 `GoStyleSheddingStat` 与 `GoStyleStatMetrics` 的**行为输出**（不是可观测性装饰）⇒ 抄写时只搬了判据没搬统计面 |
| **影响范围** | 运维可观察面：降载次数与 RPC 耗时分位数**无日志可查** ⇒ 线上无法判断是否触发过降载 |
| **严重程度** | **🟠 中**（⚠️ **D-04 只登记了「CPU 判据那一半」，日志这半从未登记** ⇒ 属未登记缺口） |
| **修复建议** | 登记进 `docs/L3-降级清单.md`（**新条目**，不要挂 D-04 —— D-04 是判据缺口，本条是统计面缺口）；实现时**逐字**对齐日志格式串与线程名（`go-style-shedding-stat` / `go-style-stat-metrics`）；⚠️ **那 3 个聚合怪癖是刻意的上游对齐，不是 bug** ⇒ 「顺手改成直觉语义」= 漂移 |
| **验证方法** | 日志捕获测试：断言行格式逐字 + `drop` 从 0 变 1 后 tag 切换 + 报告后计数归零 |

### ✅ DRIFT-COMMON-V11｜`common/` 内 `communityfact/` 的**域归属已裁决**（2026-11-03 · 用户定）⇒ **非漂移**

**状态**：**✅ 已裁决 · 非漂移**（2026-11-03 · 用户定）⇒ **行为侧 C ≡ B**（实测 favorite 两侧都走共享层）
⇒ ⇒ **不改代码归属**：`common/communityfact/` **维持现状**（7 个协议 DTO ＋ 6 个行为类）
⇒ ⇒ ⇒ **AGENTS.md §2.3 挂着的 🔴「填实现前必须裁决 communityfact 域归属」已解除**
（治理原则「`common/` 只放无域归属能力」**继续保留**，本条只裁决**归属不动**）⇒ ⇒ **不阻塞**任何后续工作。
⚠️ **为什么行为侧可以直接判「无漂移」**：B 侧（RAG）本来就把它放在**共享层** `service/common/communityfact/`（7 个协议 DTO ＋ 行为类，comment / favorite / like 三域共用）⇒ ⇒ C 侧放 `common/communityfact/` **与 B 同构** ⇒ ⇒ 符合 §1.0c「L2 以 RAG 为基准」⇒ ⇒ **不是 C 引入的漂移**。
⚠️ **剩下的是「治理口径」问题，不是行为问题**：AGENTS.md §2.3 的原则是「`common/` 只放无域归属能力」，而这 6 个**行为类**（`Dispatcher` / `EventDispatcher` / `EventSender` / `AuthorityHandler` / `ProcessLogger` / `Wire`）**携带 comment·favorite·like 三域归属** ⇒ ⇒ 与该原则**有张力**（⇒ 10 个 app 全依赖 `common/` ⇒ 改一行锁住 10 域）。
⚠️ **按「登记类事项不升级为拍板」的纪律** ⇒ 本仓**只登记、不追拍板** ⇒ ⇒ 该张力**不阻塞**本次迁移（行为层已与 B 对齐）⇒ ⇒ **2026-11-03 裁决 ⇒ 该张力**只作为**治理口径备注**保留**，不再作为待裁决项挂起**（行为层已与 B 对齐 ⇒ 迁移不受影响）。
⇒ `common/communityfact/` 的 **7 个协议 DTO**（无域归属）＋ **6 个行为类**（`Dispatcher` / `EventDispatcher` / `EventSender` / `AuthorityHandler` / `ProcessLogger` / `Wire` · 携带 comment·favorite·like 三域归属）与 AGENTS.md §2.3「`common/` 只放无域归属能力」**有张力** ⇒ ⇒ 10 个 app 全依赖 `common/` ⇒ 改一行锁住 10 域 ⇒ ⇒ ✅ **已裁决：归属不动**（AGENTS.md §2.3 的 🔴 已解除）。⚠️ **本条不计入 §11.2 的 91 承接行**（它是**归属裁决**，不是某个 B 源类的承接状态）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/src/main/java/com/rag/common/communityfact/`（13 类，其中 6 个**带域归属**的行为类：`Dispatcher`/`EventDispatcher`/`EventSender`/`ProcessLogger`/`ProcessMetadata`/`AuthorityHandler`） |
| **B 源** | 同名 4 类在 `service/common/communityfact/`，**但消费方只有 like + comment 两域**（favorite 在 B 侧是**平行实现**、不 import 共享层） |
| **差异原因** | C 侧按「三域共享」设计 ⇒ 会改变 favorite 的 wire 行为（B 侧的 favorite 走自己的 sender）⇒ **属 L1/L2 变更，不是形态调整** |
| **严重程度** | **✅ 已裁决 · 非漂移**（2026-11-03 · 用户定）⇒ 原「⚪ 已登记 ＋ `AGENTS.md` §2.3 🔴 待裁决」**已销账** |
| **修复建议** | **无需修复**（行为侧 C ≡ B ⇒ 非漂移）⇒ ⇒ 若将来要动归属，走映射表 §7.4 #1 的**治理流程**（**不是漂移修复**，不触发 §五.3 收口） |
| **验证方法** | ✅ **已验证**（2026-11-03）：实测 favorite 域两侧都走共享层 ⇒ **行为侧 C ≡ B** ⇒ ⇒ 裁决成立；⇒ 映射表 §7.4 #1 的处置措辞已同步改写 |

### ⚪ DRIFT-COMMON-V12｜`GrpcStatusMapper` 与 `GrpcExceptionMapperAutoConfiguration` 职责重叠（映射表 §7.4 #2）—— ✅ **裁决：两个都保留，等 article / task 域落地时再判**（2026-11-03 · 用户定）

C 侧同时存在 `web/GrpcStatusMapper.java` 与 `autoconfigure/GrpcExceptionMapperAutoConfiguration.java` ⇒ 两者都落在「gRPC code → 业务码」这条主题上 ⇒ ⇒ **能力重复**。
**状态**：**⚪ 已登记 · 裁决保留**（2026-11-03 · 用户定）⇒ **保留两个类**；映射表 §7.4 #2 的处置措辞由
「**建议删 `web/GrpcStatusMapper.java`**」改为「**等 article / task 域落地时再判**」
⇒ ⇒ 映射表已同步改写（`docs/Mapping_Table/common.md` §7.4 #2）。

⚠️ **口径订正（2026-11-03 · 本轮复核）**：本条原先写「两处都做『gRPC code → 业务码』⇒ 装配顺序不同 ⇒ **同一异常可能映射出两个不同 code**」
⇒ ⇒ **该描述与当前代码对不上**（实测：全仓 grep + 逐类查引用面）：

| 类 | 生产引用面（2026-11-03 实测） |
|---|---|
| `web/GrpcStatusMapper` | **零生产引用**（唯一命中是 `HttpErrorException` 的 javadoc `{@link}`）⇒ **它现在不参与任何请求路径** ⇒ 产不出 code |
| `GrpcExceptionMapperAutoConfiguration.toBusinessException` | **零生产调用**（只有 `GrpcExceptionMapperAutoConfigurationTest` 用它）⇒ 它是「从异常还原业务异常」的**辅助函数**，不是映射器 |
| `GrpcExceptionMapperAutoConfiguration` **本体** | ✅ **活的** —— 它注册 devh 的 `GrpcExceptionMapper`（走 trailer `sea-biz-code`）⇒ ⇒ **当前只有一条映射路径** |

⇒ ⇒ **「同一异常映射出两个 code」当前不成立**（那是**理论风险**：若将来有人开始调 `GrpcStatusMapper`），
⇒ ⇒ 且真正跑着的那条路径（devh mapper）**不受影响** ⇒ ⇒ 本条**不是当前漂移**，是**遗留的重复能力**。

⚠️ **为什么「等 article / task 域落地时再判」与事实自洽**：`HttpErrorException`（裸 HTTP 状态码路径）同样**零生产引用**
⇒ 因为它的消费方 **article 域还是空壳**（`service/article/` 未实现）⇒ ⇒ article 域落地时，
「裸 HTTP 状态码」那条路径会**真正跑起来** ⇒ ⇒ **那时才需要决定 `GrpcStatusMapper` 要不要接上去**
⇒ ⇒ **恰恰在那时不能删** ⇒ ⇒ 裁决与事实自洽。

⚠️ **连带订正（本轮）**：`web/GrpcStatusMapper.java` 的类注释原先逐字复述了「同一异常可能映射出两个不同 code」
⇒ ⇒ **已按上表改写**（避免注释继续教错）。

### ✅ DRIFT-COMMON-V14｜C 侧 `common/` 缺 `grpc-core` ⇒ devh gRPC server auto-config 一出现就崩

**状态**：**✅ 已修复**（2026-11-02 · 批次 F）⇒ 本模块在两个 devh starter 上的 `<exclusion>` 把 `grpc-core` 一并切断了（Maven 的 exclusion 作用于**整棵传递子树** ⇒ `grpc-netty-shaded → grpc-core` 也被切断）⇒ classpath 上没有 `grpc-core` ⇒ 只要 devh 的 gRPC server auto-config 出现就炸 `NoClassDefFoundError: io/grpc/internal/ObjectPool` ⇒ ⇒ 已按**域模块同款口径**显式声明（**test scope** ⇒ 零下游影响）⇒ 对拍：`GoStyleOtelAgentTest` 的**真 Spring 上下文**用例（修前直接炸，修后通过）。

| 项 | 内容 |
|---|---|
| **位置** | C：`common/pom.xml`（两个 devh starter 上的 `<exclusion>`） |
| **B 源** | RAG `service/common/pom.xml`（无此 exclusion） |
| **B 行为** | `service/common` 的 classpath 上**有** `grpc-core` ⇒ devh 的 gRPC server auto-config 能启动 |
| **C 行为** | 两个 devh starter 上都 exclude 了 `grpc-core`（理由：版本由根 pom 的 `grpc.version` 统一管）⇒ 但 **Maven 的 exclusion 作用于整棵传递子树** ⇒ `grpc-netty-shaded → grpc-core` 也被切断 ⇒ ⇒ **C 侧 `common/` 的 classpath 上没有 `grpc-core`** ⇒ 任何启动 devh 的 gRPC server auto-config 的进程都会在启动时炸 `NoClassDefFoundError: io/grpc/internal/ObjectPool`（**实测：`GoStyleOtelAgentTest` 起真 Spring 上下文时炸的就是这个**） |
| **差异原因** | exclusion 的**意图是「版本不让 devh 拉」**（正确），但**实现意图下变成了「把它整个剔掉」**（不正确）⇒ 10 个域模块后来都用同一套口径补了「显式声明 `grpc-core`」，**但 `common/` 自己漏了** |
| **影响范围** | 任何依赖 `common/` 且 classpath 上有 devh gRPC server starter 的进程（= 10 个域）⇒ 启动期崩弩（模拟实测时由本模块的测试触发） |
| **严重程度** | **✅ 已修复**（原：🔴 严重 —— 启动期崩弩） |
| **✅ 修法（2026-11-02 · 批次 F）** | 按 **10 个域模块的同款口径**（见 `service/article/pom.xml`）在 `common/pom.xml` 里**显式声明 `grpc-core`**，版本仍由根 pom 的 `grpc.version`（1.63.0）管 ⇒ ⚠️ **采用 `test scope`**：`common` 是 starter ⇒ 不得把下游不需要的依赖塞给下游（与 spring-web / devh starter 的 optional 口径同款）⇒ 下游保持零影响 |
| **验证方法（已落地）** | ① `GoStyleOtelAgentTest#globalProviderIsRealInsideRealSpringContext` 起**真 Spring 上下文** → 修前直接炸 `NoClassDefFoundError`，修后通过；② 全量 `mvn -o -pl common test` ⇒ **218 例全绿**（批次 G 后）；③ 域模块的同款显式声明**未动**（域 pom 本轮不改） |

### ✅ DRIFT-COMMON-V15｜HTTP 路径没绑入向 `DeadlineState` ⇒ DB 超时链在 HTTP 面上**整条失效**

**状态**：**✅ 已修复**（2026-11-03 · `global` 收口域）⇒ 跨域漂移（admin 域 E-4-R2 双侧活体对拍照出）**已销账**：
S3b（PG 停）= `200 + 信封 1015` @ 2.05s · S4（持锁）= `200 + 信封 1015` @ 2.04s ＋ **`admin_admin` 里 `r2%to` 0 行** ⇒ **两侧逐字一致**。
⇒ 触发方台账：[`docs/DRIFT/users/admin/verification-admin-2026-11-02.md`](../users/admin/verification-admin-2026-11-02.md) §9.10.3（**销账块**）。
⇒ ⚠️ **本条不是 admin 域特有** —— 凡 D2「域内直连」（DB 调用在 HTTP 边界内）的域都同一条 ⇒ 本修复**全域复用**。

| 项 | 内容 |
|---|---|
| **位置（C）** | ① `common/observability/HttpMiddleware.java`（**HTTP 路径的唯一绑点**）· ② `common/observability/ObservabilityProperties.java#resolveInnerBudget()`（**全仓唯一内层预算口径**）· ③ `common/persistence/DeadlineBoundedConnectionTimeout.java`（**新**）· ④ `common/persistence/DeadlineTranslatingDataSource.java`（**新**）· ⑤ `common/persistence/StatusAwareSqlSession.java` ＋ `StatusAwareSqlSessionFactory.java` ＋ `StatusAwareSqlSessionFactoryPostProcessor.java`（**新**）· ⑥ `common/persistence/SqlTimeoutStatusTranslator.java`（**补 `@Intercepts` 漏签**）· ⑦ `common/autoconfigure/PersistenceAutoConfiguration.java`（**注册 2 个 BPP**）|
| **B 源** | `service/common/.../persistence/SqlTimeoutStatusTranslator.java`（类头：「ctx 到期 ⇒ 落 api logic 的 `default` ⇒ **1015**」）· `zrpc.RpcServerConf.Timeout = 2s`（= 本仓 `observability.rpc-timeout`）· `rest.RestConf.Timeout = 3s`（= `observability.http-timeout`）|
| **B 行为** | `admin-api`（HTTP，3s）→ **gRPC `admin-rpc`（2s rpc deadline）** → SQL。**gRPC 中间件那一层**既提供 2s 的内层预算，又在 ctx 到期时把 `context.DeadlineExceeded` **原样**交给 api 层（**中间件不做异常包装**）⇒ api logic 的 `default` 分支 ⇒ **1015 信封**；同时 SQL 被 ctx 取消 ⇒ 事务回滚 ⇒ **无副作用** |
| **C 行为（修前）** | D2「域内直连」把 DB 调用搬进 HTTP 边界 ⇒ **内层那一层缺位**：① `DeadlineState` 在 C 侧**生产路径上零绑定点** ⇒ HTTP 路径**没绑**（⚠️ **口径订正 2026-11-03**：原写「**唯一生产绑定点**在 gRPC 服务端路径（`RpcHandleRunner.java:121`）」⇒ 经全仓源码 ＋ git 历史核对为**假** ⇒ ⇒ `RpcHandleRunner` 生产零引用、gRPC 服务端绑定点从未存在 ⇒ 见 **DRIFT-COMMON-V17**） ⇒ `DeadlineQueryTimeoutInjector` 读到「无预算」⇒ **SQL 根本不设 query timeout** ⇒ 阻塞 SQL **永不被取消** ⇒ 事务照常提交（**副作用已落库**）；② HTTP 的 3s 预算先到 ⇒ 中间件超时面接管 ⇒ **`503 + 裸文本 "Request Timeout\n"`**（无信封）；③ **额外一层**：MyBatis 的 `DefaultSqlSession` 把 mapper 调用里的**任何**异常包成 `PersistenceException` ⇒ 共享层翻出的 `DEADLINE_EXCEEDED` **在离开持久层前就被吃掉一层** ⇒ 域层的 `catch (StatusRuntimeException e) { throw e; }` **看不见它** ⇒ 落 `catch (Exception e)` ⇒ 被改写成 `INTERNAL`（**5001**）|
| **差异原因** | **架构差异**（D2 域内直连 ⇒ 少了一层 gRPC 中间件）**＋ 两处「静默失效」**（`DeadlineState` 绑点只有一处 · `@Intercepts` 漏签 `queryCursor`）|
| **影响范围** | **凡 D2 域内直连的域**（= 全部 10 个域）⇒ ① DB 超时 ⇒ 客户端拿到 503 裸文本而非 1015 信封；② **更严重**：客户端拿到失败而**服务端已提交** ⇒ **重试撞唯一约束 / 邀请码已用** ⇒ 属「**失败-成功不一致**」型风险 |
| **严重程度** | **🔴 严重**（原判定）⇒ **✅ 已修复** |
| **✅ 修法（4 件事，缺一不可）** | ① **`HttpMiddleware` 在 worker 线程上绑入向 `DeadlineState`**（**绝对时刻** = 请求开始 + 内层预算 ⇒ 排队时间也计入）⇒ SQL 终于会被 CancelRequest ⇒ 事务回滚；② **新增内层预算口径** `resolveInnerBudget()` = **`min(http-timeout, rpc-timeout)`**（B 的 ctx 3s 与 gRPC 2s 取更早者 ⇒ **2s**）⇒ **必须严格早于** HTTP 的 3s ⇒ 才不会与中间件的 503 面撞同一刻；③ **`DeadlineBoundedConnectionTimeout`**：把 Hikari 的 `connectionTimeout` **只收紧不放宽**地收到内层预算 ⇒ PG 不可用时不再干等 30s（`DeadlineQueryTimeoutInjector` 只管「已拿到连接上的语句」，**管不到「取连接」**）；④ **`DeadlineTranslatingDataSource` ＋ `StatusAwareSqlSession` ＋ `StatusAwareSqlSessionFactory`**：把「取连接超时」与「SQL 被取消」都翻成 `StatusRuntimeException(DEADLINE_EXCEEDED)`，并**解掉 MyBatis 的 `PersistenceException` 包装** ⇒ 域层放行它 ⇒ api 层 `mapRPCStatusToCode` 的 `default` 分支 ⇒ **1015**。⚠️ **共享层不硬编码 1015**（§八 错误码逐域隔离）|
| **⚠️ 顺带修掉的老洞** | `SqlTimeoutStatusTranslator` 的 `@Intercepts` **漏签 `Executor.queryCursor`** ⇒ MP 的 `BaseMapper.selectOne(Wrapper, boolean)`（**游标路径**）**完全不经过翻译器** ⇒ 57014 原样冒到域层 ⇒ **5001**（S4 的真实失败点）。⇒ 补签后 S4 转绿。⚠️ **不要**再加 3 参的 `Executor.query(MappedStatement, Object, RowBounds)`：MyBatis 3.5.19 的 `Executor` 接口**没有**这个方法 ⇒ `Plugin.getSignatureMap` 会在**启动时**抛 `PluginException`（实测：admin 服务起不来）|
| **验证方法（已落地）** | ① **活体对拍**（C 侧 admin 服务 · 真 PG · 真 Tomcat）：S3b = `200 + {code:1015,msg:"服务繁忙"}` @ 2.05s；S4 = `200 + {code:1015}` @ 2.04s ＋ `admin_admin` 里 `r2%to` **0 行** ⇒ **副作用消失**；② **真 PG 守门** `common/src/test/java/com/rag/common/persistence/DeadlineBudgetPersistencePgTest.java`（**6 例** · Testcontainers `postgres:16-alpine`）—— ⚠️ **每条断言都写明「到达调用方的是 `StatusRuntimeException` 且 code = `DEADLINE_EXCEEDED`」**（若只断言「抛了异常」，翻译器坏掉时测试**照样绿** ⇒ **不可判别**）；③ `HttpMiddlewareTest#httpPathBindsInnerDeadlineOnWorkerThread`（**在业务体内**读 `DeadlineState` ⇒ 钉住「绑在 worker 线程上」）；④ `ObservabilityPropertiesTest` 的内层预算 3 例（min 语义 / 单项回落 / 全未配 = null）|
| **⚠️ 缝的选取（读源码确定，不是猜的）** | 解包点选在 **`SqlSessionFactory`**：本仓两条 mapper 路径**都**经它 —— 常规路径 `SqlSessionTemplate.SqlSessionInterceptor` → `SqlSessionUtils.getSqlSession(factory, …)` → `factory.openSession(execType)`；MP 的游标路径 `BaseMapper.selectOne(Wrapper, boolean)` → `MybatisUtils.getSqlSessionFactory(sqlSession)` → `SqlSessionTemplate.getSqlSessionFactory()`（public getter）→ `factory.openSession()`。⇒ ⇒ **包装工厂一个 bean 即全覆盖**；⚠️ **不能**改 mapper 代理（会破坏 MP 的 `MybatisUtils.getMapperProxy(proxy)` 的代理身份识别）|

### ⚪ DRIFT-COMMON-V13｜gRPC 业务码承载方式变更（**D-05 已登记**）

上游 = **status code 本身就是业务码** + description 业务文案；grpc-java 的 `Status.fromCodeValue(int)` 对未知码回落 `UNKNOWN` ⇒ **承载不了任意数值业务码** ⇒ C 侧走 **trailer `sea-biz-code`**。
**状态**：**⚪ 已登记**（`docs/L3-降级清单.md` **D-05**）⇒ 本次实测确认该判断成立（`GrpcExceptionMapperAutoConfigurationTest` 5 例覆盖 trailer 业务码与 description 逐字）。

### ✅ DRIFT-COMMON-V16｜`ProtectionProperties` 的 **Stat / Prometheus 两个开关未接线**（层恒装）—— **已收口（2026-11-05）**

**状态**：**✅ 已收口**（2026-11-05 · `global` 收口域 · 落点 `common/` ⇒ 符合 AGENTS.md §五.3 规则 3）
⇒ ⇒ **只改共享层**（4 个主代码文件 ＋ 1 个新测试文件）⇒ ⇒ **未改任何域的代码或 yml**（本条不需要动域侧）。

**登记时的定性（2026-11-03 · 原文保留）**：
⚠️ **定性**：B 侧 `GoStyleRpcProtectionChain.build` 用 `props.getStat().isEnabled()`（`:235`）与
`props.getPrometheus().isEnabled()`（`:228`）**决定这两层装不装**；C 侧的装配点
`ProtectionAutoConfiguration#rpcStatInterceptor` **不读本开关** ⇒ ⇒ **Stat 层恒装**。
（`Prometheus.enabled` 同形：**全仓零消费点** ⇒ ⇒ Prometheus 层也恒装。）
⇒ ⇒ 但 **B 侧默认值同为 `true`**（`zrpc/internal/config.go:23-24`）且 **真源各域 yaml 未覆盖**
（实测：B 侧 `service/**/src/main/resources/*.yml` 里**没有** `Stat:` / `Prometheus:` 顶层键 ——
唯一命中的 `tools/difftest/conf/go/hot-rpc.yaml` 是 **zrpc 的 `Prometheus.Host/Port/Path` 配置**，同名不同物）
⇒ ⇒ ⇒ **现行配置下两侧行为一致** ⇒ ⇒ **不判行为漂移**，只记「**接线缺口**」。
⇒ ⇒ **触发条件**：一旦某个域把 `stat.enabled` / `prometheus.enabled` 置 `false`
⇒ B 侧不装该层、C 侧仍装 ⇒ **那一刻起才是漂移**。
⇒ ⇒ **同类已接线的对照**：`breaker.enabled` **有**消费点（`ProtectionAutoConfiguration:321` / `:468` · `ClientProtectionAutoConfiguration:61`）⇒ ⇒ 说明「开关未接线」是**逐个开关**的状态，不是整体缺口。
⚠️ **连带订正（本轮）**：`ProtectionProperties` 的 `Stat` 类注释原先写「观测层的开关在 `GoStyleRpcProtectionChain#build` 内直接读本属性」
⇒ ⇒ **该说法在 C 侧不成立** ⇒ ⇒ 已改写为「C 侧**未接线**」＋ 指向本条。

**本轮收口（2026-11-05）**：

| 项 | 内容 |
|---|---|
| **B 侧消费点（逐字读过 · 本轮复核）** | B `service/common/src/main/java/com/rag/common/protection/GoStyleRpcProtectionChain.java`<br>· `:228` → `if (props.getPrometheus().isEnabled()) { chain = prometheusThen(chain); }`<br>· `:235` → `if (props.getStat().isEnabled()) { chain = statThen(chain); }`<br>⇒ ⇒ **两个开关决定这两层是否被拼进链**；⚠️ **全仓消费点就这两处**（`grep` 实测：B 侧 `getStat().isEnabled()` / `getPrometheus().isEnabled()` **零其它调用**）⇒ ⇒ **B 侧没有第二个开关源** ⇒ ⇒ 语义唯一 |
| **C 侧修前现状（实测）** | ① `ProtectionAutoConfiguration#rpcStatInterceptor`（修前 `:290`）**签名里收了 `ProtectionProperties`，函数体却从不读 `getStat()`** ⇒ ⇒ **Stat 层恒装**；② `#rpcPrometheusInterceptor`（修前 `:303`）**只读 registry、不读 `getPrometheus()`** ⇒ ⇒ **Prometheus 层恒装**<br>⇒ ⇒ **唯一的「关」是「没有 registry ⇒ 整段跳过」⇒ 那是另一根轴**（**有没有地方可写** ≠ **配置意图**）<br>⇒ ⇒ **C 侧已接线的同类开关** = `breaker.enabled`（修前 `:321`）＋ `cpu-shedding.enabled`（修前 `:336`）⇒ ⇒ 「开关未接线」是**逐个开关**的状态，不是整体缺口 |
| **修法（已实施）** | ① 两层各加 `enabled` 字段，**在构造期**接收；② 装配点把 `properties.getStat().isEnabled()` / `properties.getPrometheus().isEnabled()` **传进去**（`ProtectionAutoConfiguration.java:304-306` / `:329-334`）；③ **关掉时完全不做事**：<br>　· **Stat 层**（`RpcStatInterceptor.java:97` / `:140-142` / `:161`）：**不记样本** ＋ **不起那条每 60s 的统计日志线程**；<br>　· **Prometheus 层**（`RpcPrometheusInterceptor.java:131`）：**一条指标都不写**<br>⇒ ⇒ **沿用本类既有惯例**（`breaker` / `cpu-shedding` 同款：「Bean 恒在 ＋ 构造时传 `enabled` ＋ 关掉即透传」）⇒ ⇒ **链的组成不变**（B = 「层不在链上」· C = 「层在但透传」⇒ 只影响内部断言的**层名列表**，**不影响任何客户端可观测行为**） |
| **🔴 「连线程一起不起」这一半为什么是必须的** | B 侧那条**每 60s 一行统计日志**的线程，**唯一来源** = `GoStyleStatInterceptor.java:82` 的 `metrics.startReporter()` ⇒ ⇒ 而 `GoStyleStatInterceptor` **只在开关为真时才被构造** ⇒ ⇒ **开关关掉 ⇒ B 侧既没有样本、也没有那行日志**。<br>⚠️ **差点读错的一处（已逐字排除）**：`GoStyleRpcSheddingInterceptor.java:126` 也有一句 `this.stat.startReporter();` ⇒ 但它的 `stat` 字段是 **`GoStyleSheddingStat`**（**另一套统计**，降载用的）⇒ **不是** stat-metrics 那条 ⇒ ⇒ 「Stat 关掉 ⇒ 无 60s 日志」的结论成立<br>⇒ ⇒ C 侧若只做「不记样本」而仍起线程 ⇒ **C 会比 B 多打一行日志** ⇒ ⇒ **线程门必须一起关** |
| **三段式（AGENTS.md §3.2）** | **① 比 B 侧多做了什么**：B 侧用「**层不拼进链**」表达「这层关着」；本仓用「**层在链上但透传**」（与本类既有的 breaker / cpu-shedding 惯例同款）⇒ ⇒ 差别只在**链的组成**。<br>**② 语义还一样吗**：**一样** —— 逐项对齐三处可观测面：<b>① 无样本</b>（B 不在链上 ⇒ 不记；C 透传 ⇒ 不记）· <b>② 无那条 60s 的统计日志</b>（B 无线程；C 开关关掉时**不启动线程**）· <b>③ 降载 drop 计数口径不变</b>（两侧都是 `...getInstance().addDrop()`：都只增计数、都没有上报线程去打印它 ⇒ ⇒ **两边都「计了但看不见」**）⇒ ⇒ **客户端可观测面零差异**。<br>**③ B 出处**：`GoStyleRpcProtectionChain.java:228` / `:235`（消费点）＋ `GoStyleStatInterceptor.java:82`（上报线程**唯一**来源）＋ `zrpc/internal/config.go:23-24`（两个开关的默认值 `true`） |
| **配置键名（L1 面）** | 两侧**前缀与键名完全一致**：`rag.protection`（B `GoStyleProtectionProperties.java:94` ＋ C `ProtectionProperties.java:95`）⇒ ⇒ 键名 = `rag.protection.stat.enabled` / `rag.protection.prometheus.enabled` ⇒ ⇒ **L1 未变**（本条只补「消费点」，不动键名、不动默认值） |
| **⚠️ 现行配置下两侧行为一致（本条不判行为漂移的理由）** | ① 两个开关的**默认值**两侧同为 `true`（B `zrpc/internal/config.go:23-24` ＋ C `ProtectionProperties.Stat/Prometheus.enabled`）；② **真源各域 yaml 未覆盖**（B 侧 `service/**/src/main/resources/*.yml` 里**没有** `Stat:` / `Prometheus:` 顶层键 —— 唯一命中的 `tools/difftest/conf/go/hot-rpc.yaml` 是 **zrpc 的 `Prometheus.Host/Port/Path` 配置**，**同名不同物**）；③ **C 侧各域 yml 同样未覆盖**（本轮 `grep` 实测：各域 `application.yml` 里**没有** `rag.protection.stat.enabled` / `rag.protection.prometheus.enabled`）⇒ ⇒ ⇒ **修前修后行为一致** ⇒ ⇒ 本条闭合的是「**接线缺口**」，**不是**现行行为漂移 |
| **验收判据** | `common/src/test/java/com/rag/common/observability/ProtectionLayerSwitchWiringTest.java` —— **4 例**（2 条目标维 ＋ 2 条对照维）<br>① `stat.enabled=false` ⇒ **一条样本都不记** **且** **那条 60s 上报线程没起**（**真 Spring 装配** ＋ **真 in-process gRPC server 上真发一次 unary 调用**）<br>② 对照：`stat` 开关默认 true ⇒ **记一条样本** ＋ **线程已起**<br>③ `prometheus.enabled=false` ⇒ **registry 里一个 meter 都没有**<br>④ 对照：`prometheus` 开关默认 true ⇒ **写出那两个 meter**（`rpc_server_requests_duration_ms` ＋ `rpc_server_requests_code`）<br>⇒ ⇒ **判据落在「真执行期」**（不是「构造器接受了这个参数」）；⇒ ⇒ **两条对照维是必需的**（否则「关掉就不做」在「层压根没跑起来」时也是绿的 ⇒ 守门恒真） |
| **⚠️ 守门为什么放在 `com.rag.common.observability` 包** | Stat 层的读数取自**进程级单例** `RpcStatMetrics` ⇒ 必须先重置成干净状态 ⇒ 而重置口 `resetInstanceForTest()` 是 **package-private**（**B 侧同款**：`GoStyleStatMetrics.resetInstanceForTest()` 同样包私有 ⇒ **不为测试放宽可见性**）⇒ ⇒ 守门必须与它同包 |
| **新增的观察点（生产代码的一处小开口）** | `RpcStatMetrics#reporterStarted()`（**public · 「仅测试用」**）：读「这条 60s 上报线程起没起」⇒ ⇒ **为什么不用线程名判**：同 JVM 里别的测试可能已经起过同名线程 ⇒ **会假红**；**为什么不等 60s**：守门必须快 ⇒ ⇒ 读**本实例**的调度器引用（`startReporter()` 幂等、只置位一次）⇒ ⇒ **纯观察点，不改任何行为**（与本类既有 3 个 public「仅测试用」方法同款） |
| **变异实测（两轮 · 红灯精确）** | **变异 A（抽掉「配置 → 层」的接线）**：两个 Bean 改成写死 `true` ⇒ ⇒ **机器读数：`Tests run: 4, Failures: 2`** ⇒ 红的是 `statDisabledIsFullyInert`（`expected: 0L but was: 1L`）＋ `prometheusDisabledWritesNoMeter`（`Expecting empty but was: [...]`）⇒ ⇒ **两条对照维仍绿** ⇒ ⇒ **「接线」这半有牙**<br>**变异 B（只抽掉「上报线程门」，接线保持正确）** ⇒ ⇒ **机器读数：`Tests run: 4, Failures: 1`** ⇒ 红的是 `statDisabledIsFullyInert`，且**红在 `reporterStarted()` 那条断言**（`Expecting value to be false but was true`）⇒ ⇒ 样本那条断言这次**是绿的** ⇒ ⇒ **「线程门」这半独立有牙**（不是被样本那条顺带盖住的）<br>⇒ ⇒ **还原后同条件复跑 = `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`** |

#### ✅ 读数（2026-11-05）

| 项 | 读数 |
|---|---|
| **行为层守门** | `ProtectionLayerSwitchWiringTest` —— **4 例**（2 条目标维 ＋ 2 条对照维）⇒ **4/4 绿**；**变异 A**（抽接线）⇒ **`Tests run: 4, Failures: 2`**（红：`statDisabledIsFullyInert` `expected: 0L but was: 1L` ＋ `prometheusDisabledWritesNoMeter` `Expecting empty but was: [...]`；两条对照维仍绿）；**变异 B**（只抽线程门）⇒ **`Tests run: 4, Failures: 1`**（红在 `reporterStarted()` 那条：`Expecting value to be false but was true`）⇒ ⇒ **还原 = `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`** |
| **🔴 机器守门（新增 A15）** | `tools/difftest/check-arch-invariants.py` 新增 **A15**「保护层每个 `enabled` 开关都必须有消费点（接线守门）」：脚本**自己从 `ProtectionProperties.java` 解析出带 `enabled` 字段的嵌套类** ⇒ ⇒ **将来新增的开关自动进清单**（不腐烂）⇒ 逐枚核对**共享层**里有没有人读它的 `isEnabled()`<br>⚠️ **🔴 判据会先剥掉注释与字符串字面量** —— 本仓 javadoc 里**大量引用 B 侧源码**（`if (props.getStat().isEnabled())` 之类）⇒ ⇒ 不剥离的话「接线被摘掉」也照样绿 ⇒ ⇒ **假绿** ⇒ ⇒ **fixture 里故意放了一句注释里的引用，自检仍判红 ⇒ 证明它不会被注释骗到**<br>⇒ **实测 PASS**（6 枚开关：`RedisDeadline` / `OutboundDeadline` / `CpuShedding` / `Breaker` / `Stat` / `Prometheus`）· **self-test 15/15 判据均能红**（含 A15）· **真仓变异实测**（摘掉 `rpcStatInterceptor` 的接线 ⇒ A15 当场报「`Stat` 共享层零消费点 ⇒ 该层恒装（零编译错误、零测试红灯 ⇒ 静默失效）」⇒ 还原后复绿）⇒ ⇒ 配套更新 `docs/架构符合性断言.md`（新增 A15 段 ＋ 状态行 ＋ 契约矩阵补两列）＋ `docs/README.md`（A1–A14 ⇒ **A1–A15**）＋ `AGENTS.md`（4 处 A1–A13 ⇒ **A1–A15**） |
| **全仓回归** | **串行**（`mvn -o test`，**不带 `-T1C`**）⇒ **BUILD SUCCESS**（**6 分 36 秒**）⇒ **16/16 模块 SUCCESS** ⇒ **931 例 · Failures 0 · Errors 0 · Skipped 0**（模块级汇总：common **251**（247 → 251，+4 条新守门）· article **157** · follow **72** · like **110** · message **103** · users-common **46** · user **40** · admin **152** · proto/comment/favorite/hot/task **0**）⇒ ⇒ ⚠️ **计数只取回归日志的模块级汇总**（AGENTS.md §五.8 规则 ①） |
| **三道守门** | `check-arch-invariants.py` ⇒ **EXIT=0**（**PASS 12 · FAIL 0 · N/A 2 · PENDING 1**，共 **15** 条 ⇒ **A15 已计入**）；`--self-test` ⇒ **15/15 判据均能红**<br>· `check-docs-vs-tree.py` ⇒ **EXIT=0**（新增测试文件 ⇒ 已重跑 `tools/gen-file-tree.py`）<br>· `check-mapping-coverage.py` ⇒ **EXIT=0**（C **641/641** · B **752/752**） |
| **§11.2 状态表（机器复算）** | **承接行 = 91 · 标记数 = 91 · 复合状态行 = 0 · PASS 89 / NOT_IMPLEMENTED 0 / INTENTIONALLY_CHANGED 2 / FAIL 0 / FRAMEWORK_DIFFERENCE 0 / NOT_VERIFIED 0 / BLOCKED 0** ⇒ ⇒ **与本轮改动前一致**（本条不改 §11.2 的任何一行） |
| **🧠 元教训落档** | [`docs/Pitfalls/_cross/P-25-配置开关没接线也是哑弹且关掉它要连构造期副作用一起关.md`](../../../Pitfalls/_cross/P-25-配置开关没接线也是哑弹且关掉它要连构造期副作用一起关.md) —— 三条可复用教训：① **「开关存在」≠「开关被读」**（配置面有**两类**哑弹：配置项缺失 / **消费点缺失**）；② **「关掉一个层」≠「主路径不做事」**（**构造期副作用**照样在 ⇒ 「等价于不装」不成立）；③ **机器守门不能数注释里的引用**（本仓 javadoc 里大量引用 B 侧源码 ⇒ 不剥注释 ⇒ **假绿** ⇒ 自检 fixture 里要**故意**放一句注释引用来证明没被骗） |

### ✅ DRIFT-COMMON-V17｜gRPC 服务端路径**零绑定点** ⇒ `DeadlineState` 绑不到 handler 线程 ⇒ **PARITY-006 根因** —— **已修（2026-11-03）**

**状态**：**✅ 已修 · 端到端判据已闭合**（2026-11-03 · `global` 收口域）⇒ 落点在 `common/`（§五.3）。
⇒ ⇒ **关联**：admin 域台账 **PARITY-006**（现象侧 · `docs/DRIFT/users/admin/verification-admin-2026-11-02.md`）⇒ **已销账**。

| 项 | 内容 |
|---|---|
| **现象（旧读数 · ⚠️ 2026-11-03 已订正为探针假差）** | 客户端在 **1s** 取消一个**写路径** gRPC 调用 ⇒ 两侧都回 `CANCELLED`（一致）⇒ 探针读到 **B 侧 `admin_admin` 0 行 / C 侧 1 行** ⇒ 曾判为「取消在 C 侧只取消了回包、**没取消已发生的写入**」。判别性变体（取消推迟到 5s > 服务端 2s 预算）⇒ 结果不变 ⇒ ⇒ 曾归因于「服务端预算到点后有没有把在途 SQL 取消掉」。<br>🔴 **订正**：该读数来自**探针的顺序注入**（先发 B 的调用、等它返回、再发 C 的调用 ⇒ **B 的调用比 C 早约 1 秒开始** ⇒ ⇒ 放行 PG 那一刻 **B 的 2s 预算已到、C 还剩约 1 秒**）⇒ ⇒ **「那条写落不落库」由放行那一刻还剩多少预算决定** ⇒ ⇒ 旧读数是**注入时序假差**，**不是**行为差异 ⇒ ⇒ **代码缺口是真的（已修），行为差异不成立（已闭合）** |
| **B 侧做法（逐字读过消费点）** | B `service/common/.../observability/RpcInterceptor.java:516-522`（在 `newUnaryServerInterceptor` 的 **`onHalfClose`** 里）⇒ **同一个绝对时刻**同时绑两个载体：`DeadlineState.bindAbsoluteNanos(absoluteDeadlineNanos)` ＋ `GoStyleRequestDeadline.bindAbsoluteNanos(...)` ⇒ ⇒ 绑在**真正跑 handler 的线程**上 ⇒ ⇒ `DeadlineQueryTimeoutInjector` 读到剩余预算 ⇒ 每条 SQL 设 `setQueryTimeout` ⇒ ⇒ 2s 到点 PG 收到 CancelRequest ⇒ SQLState `57014` ⇒ 事务回滚 ⇒ **0 行** |
| **C 侧现状（实测）** | ① `ObservabilityAutoConfiguration.GrpcObservationInterceptor`（`:206`）**只重写了 `onCancel()`**，**没有 `onHalfClose()`** ⇒ ⇒ handler 线程上 `DeadlineState` 恒空；② C 侧**从无** `newUnaryServerInterceptor` 这个方法（`git log --all -S` 全仓历史：它**只作为注释出现过**，从未有实现）⇒ ⇒ B 侧那两行绑定**整块丢失**；③ C 侧 `DeadlineState` 的**生产绑定点只有 HTTP 路径两处**（`HttpMiddleware.java:283` / `:324`，2026-11-03 · V15 新增）；④ `RpcHandleRunner.java:121` 那处绑定所在的类**在生产代码里零引用**（死代码）⇒ **不是**生产绑定点 |
| **修后（2026-11-03 · 已实施）** | ① `ObservabilityAutoConfiguration.GrpcObservationInterceptor` 的 listener **补 `onHalfClose()`** ⇒ 在**真正跑 handler 的线程**上 `DeadlineState.bindAbsoluteNanos(绝对时刻)`；② 绝对时刻 = **调用开始** ＋ `rpc-timeout`（**在 `registry == null` 早退之前**算 ⇒ ⇒ 绑 deadline 与「装没装 Micrometer registry」**无关** ⇒ ⇒ 「没装观测的进程」也不会没有 deadline）；③ **绑定层包在观测层外面** ⇒ scope 包住整个 handler 执行期（含观测层收尾）⇒ ⇒ 与 B 侧 `RpcInterceptor.java:516-522` 的嵌套次序同形；④ 预算取 `rpc-timeout`（**不是** `resolveInnerBudget()`）—— gRPC 边界上**没有 HTTP 预算** ⇒ ⇒ 与 B 侧 `zrpc.RpcServerConf.Timeout = 2s` 一致，且与 `scheduleTimeout` 同一刻 |
| **假断言订正（本轮）** | 台账原先（含 admin 台账 4 处 + common 台账 1 处）写「`DeadlineState` 的**生产绑定点只有 gRPC 服务端路径**（`RpcHandleRunner.java:121` `bindRemainingNanos` ＋ `newUnaryServerInterceptor`）」⇒ ⇒ **两半都是假的** ⇒ ⇒ 修前的准确表述 = 「**C 侧 `DeadlineState` 在生产路径上零绑定点**」⇒ ⇒ **5 处已逐一订正** |
| **影响范围** | 凡走 **gRPC 入口**的写路径（10 个域的 rpc 侧）⇒ ⇒ 客户端取消 / 服务端预算到点 ⇒ **服务端仍提交** ⇒ ⇒ 属「**失败-成功不一致**」型风险（同 V15 的 HTTP 侧，但方向相反：V15 是 HTTP 路径**没绑**，本条是 gRPC 路径**也没绑**） |
| **修法（**已实施**，2026-11-03）** | 在 `GrpcObservationInterceptor` 的 listener 里**补 `onHalfClose()`** ⇒ 在**真正跑 handler 的线程**上 `DeadlineState.bindAbsoluteNanos(绝对时刻)`（绝对时刻 = 调用开始 + `rpc-timeout`）⇒ ⇒ 在途 SQL 会被 CancelRequest ⇒ 写路径回滚（B=0 行 / C=0 行）。⚠️ **只绑入向载体 `DeadlineState`**（出向 `GoStyleRequestDeadline` 的绑定**不在本条范围**，爆炸半径更大 ⇒ 另案） |
| **验收判据** | ① admin 域 E-4-R2 场景轮（客户端 1s 取消写路径）⇒ **跨域复测已跑**（users-user 域 · 2026-11-03 · 见下一行）；② **可判别**的回归测试（必须写明「到达调用方的是 `StatusRuntimeException` 且 code = `DEADLINE_EXCEEDED`」——只断言「抛了异常」在包装层还在时**照样绿** ⇒ 不可判别）；③ **变异验证**（拿掉 `onHalfClose` 的绑定 ⇒ 该维转红、其余维仍绿）—— ✅ **已在两个层次各做一次**（单元/PG 层 ＋ **跨侧探针层**） |
| **✅ 跨侧验证（2026-11-03 · users-user 域 · **对称注入扫描**）** | **探针**：`tools/difftest/users-user/`（阶段 **D / D2**）⇒ **两侧真进程**（B 侧 Boot 4 双进程 ＋ C 侧 Boot 3.5 单进程）⇒ **同一 JVM 同时向两侧发调用**、**同一绝对时刻取消**、**放行点相对两侧等距**（= 调用开始 + 固定偏移）⇒ **机器读数逐字**：<br>`· 放行@+1200ms ⇒ B=1 · C=1` ／ `· 放行@+1800ms ⇒ B=1 · C=1` ／ `· 放行@+2400ms ⇒ B=0 · C=0` ／ `· 放行@+3000ms ⇒ B=0 · C=0` ／ `· 翻转点（1 行 → 0 行）：B 侧=2400 · C 侧=2400` ／ `✅ 两侧翻转点重合（=2400）⇒ 预算语义等价`<br>⇒ ⇒ **两侧逐点相同 + 翻转点重合** ⇒ ⇒ **C 侧与 B 侧预算语义等价**（**不是「某一个点恰好相等」**）⇒ ⇒ **V17 的修复达成**；⚠️ 翻转点读数 2400 比 2s 预算晚 ⇒ 因 `docker unpause` 自身有几百毫秒延迟 ⇒ **两侧延迟相同 ⇒ 不影响判据** |
| **✅ 变异验证 · 探针层（2026-11-03）** | 拿掉 `onHalfClose` 的绑定后**复跑同一探针** ⇒ 机器读数逐字：`· 放行@+2400ms ⇒ B=0 · C=1` ／ `· 放行@+3000ms ⇒ B=0 · C=1` ／ `· 翻转点（1 行 → 0 行）：B 侧=2400 · C 侧=None` ⇒ ⇒ **C 侧不再跟随 B 侧翻转** ⇒ ⇒ **判据有牙齿** ⇒ ⇒ **该修复是必要的**；⇒ ⇒ ⚠️ **推论**：变异态下 C 在**所有**偏移都读 1 ⇒ ⇒ **旧（顺序注入）探针读到的「C=1」在修前修后完全相同** ⇒ ⇒ **旧探针对这个修复【完全不敏感】**（读数恒定 ⇒ 既证不了差异、也证不了修复）⇒ ⇒ **这正是必须把探针改成对称注入的理由**；⇒ ⇒ **还原后同条件复跑 = 绿**（翻转点重合 =2400 · 摘要「❌ 真差异 1 ｜ ⚠️ 对称异常/已知现象 3」） |
| **✅ 落地与验证（2026-11-03 · 本轮）** | **实现**：`common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java`（`GrpcObservationInterceptor.withDeadline` + `rpcDeadlineNanos`）<br>**测试**：① `common/src/test/java/com/rag/common/autoconfigure/GrpcDeadlineBindingTest.java`（**3 例** · 无 PG）——「handler 体内读 `DeadlineState.remainingMillis()` 必须是**有限值**」＋「无预算 ⇒ 不绑 ⇒ `Long.MAX_VALUE`」＋「**真 gRPC server**（`InProcessServerBuilder` ＋ `ServerCalls.asyncUnaryCall`）⇒ handler 线程 ≠ 调用方线程」；② `common/src/test/java/com/rag/common/autoconfigure/GrpcInboundDeadlinePgTest.java`（**2 例** · **真 PG** Testcontainers `postgres:16-alpine`）——「服务端预算到点」＋「**客户端主动取消**」两场景<br>**读数**：5 例全绿（`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`）⇒ **变异实测**：拿掉 `onHalfClose` 的绑定 ⇒ **两条都在核心判据上转红**（`expected: 0L but was: 1L`）⇒ ⇒ **红灯精确、其余维仍绿** ⇒ 还原后同条件复跑回绿<br>⚠️ **诚实边界**：① **只绑入向载体**，出向 `GoStyleRequestDeadline` **另案**；② 作用域随 `onHalfClose` 返回而结束 ⇒ ⇒ **异步 handler 不在作用域内**（与 B 侧同款 · 本仓 10 个域的 proto 全是 unary）；③ **无预算不绑**（`rpc-timeout` 未配 ⇒ 恒 `Long.MAX_VALUE`） |
| **连带订正（本轮）** | ① `DeadlineState.java:103` 那段注释（**藏在 `/* */` 块注释里 ⇒ javadoc 工具扫不到 ⇒ 一直是断链**）原先写「实际生效路径：`{@link #newUnaryServerInterceptor}` 的 `onHalfClose`」⇒ ⇒ **该方法在 C 侧从未存在** ⇒ 是**照抄 B 侧时丢了归属**（B 侧那段 `#newUnaryServerInterceptor` 指的是**外层类** `RpcInterceptor` 的成员）⇒ ⇒ 已改写为缺口记录；② `Book/users/users/01-用户登录流程.md:427-430` 的同一断言**也已过时**（它还写「HTTP 侧不写 `DeadlineState`」—— V15 之后 HTTP 侧**已写**）⇒ ⇒ **归 Book 域，本轮未改**（该文件正被并发 lane 修改 ⇒ 避免冲突）⇒ 登记在此 |

### ⚪ DRIFT-COMMON-V18｜出向 deadline 的**运行期守门缺口**（装配层有网 · 运行期差分无网）

**状态**：**⚪ 已登记**（守门缺口，**非行为漂移**）⇒ 落点在 `common/` ⇒ 归 `global` 收口域。
⚠️ **缺口本身**：`OutboundDeadlineInterceptor` 的类注释原先点名三个「运行期锁定用例」
（`GoStyleOutboundDeadlineRpcAssemblyTest` / `GoStyleDevhOutboundDeadlineAssemblyGateTest` /
`GoStyleRpcInterceptorOutboundDeadlineChainGateTest`）⇒ ⇒ **实测：这 3 个测试名在 C 侧全仓只出现在那段注释里，一个都不存在** ⇒ ⇒ 注释宣称「已锁定」的**运行期行为差分**，C 侧**无网**。
⇒ ⇒ **C 侧现有的是**：① **装配层**守门（`ClientProtectionAutoConfigurationTest#devhCollectorReallyPicksUpBothInterceptors` —— 断言它真进了 devh 的收集器 registry）；② **单元级**守门（`RpcProtectionLayerTest` 的出向 deadline 3 例：三项都没预算 ⇒ 原样返回 `CallOptions` / 客户端硬上界生效 / per-service 上界是「覆盖」）。
⇒ ⇒ **仍无网的两条**：③ **devh 真实链路上的运行期差分**；④ 「**`>= 0L` 这道守卫**」（已过期 ⇒ 预算压成 0 ⇒ 命中「立即失败」分支）—— **这行离「语义正确」只差一个字符**（改成 `> 0L` ⇒ 过期值被跳过 ⇒ 出向调用**静默退回「挂满」**）⇒ ⇒ 高危。
⇒ ⇒ **连带订正（本轮）**：该类注释里的三个失效测试名已改为「**应该**补」＋ 标明 C 侧现状（**不再宣称已锁定**）。

---

### ✅ DRIFT-COMMON-V19｜article / message 两域的三份**域内副本**（共享层缺对应物 ⇒ 域内各留一份）—— **已收口（2026-11-03）**

**状态**：**✅ 已收口**（2026-11-03 · `global` 收口域 · 一次提交同时改 `common/` 与两域代码 ⇒ 符合 AGENTS.md §五.3 规则 3）。
⇒ ⇒ **副本数 4 → 0**（article 3 份 ＋ message 1 份）。
⇒ ⇒ **不属于「行为漂移」** —— 是**「同一份实现有两处落点」的形态债**（两份实现一旦分叉就是漂移）⇒ 收口本身是**消除分叉风险**，不是修行为。

**为什么记进 `common/` 台账**：这三条账的**根都在共享层**（缺对应物 / 可见性不够）⇒ 按 AGENTS.md §五.3 规则 3 属 `common/` 写权 ⇒ 由 `global` 收口域统一销账；article / message 两域的台账只登记「现象 + 证据 + 修法建议」，**销账状态由本条统一给出**。

| 项 | 内容 |
|---|---|
| **三条账的来源** | article 域台账 `docs/DRIFT/article/vs-common.md` §2.1 的 **2.1-1 / 2.1-2 / 2.1-3** ＋ message 域台账 `docs/DRIFT/message/vs-common.md` §2.1 的 **`JsonbMapTypeHandler`** 行 ⇒ ⇒ **共 4 条**（D-09 / D-10 / D-11 ＋ message 同型账） |

#### D-09｜`common/web/StringFields` 是**包私有** ⇒ 域内调不到 ⇒ article 抄了一份

| 项 | 内容 |
|---|---|
| **现象（实测）** | `javap -p -cp common/target/classes com.rag.common.web.StringFields` ⇒ `final class com.rag.common.web.StringFields`（**无 `public`**）· `static java.lang.String parse(java.lang.String, java.lang.Object)`（**包私有静态**）⇒ ⇒ **域内任何包都调不到**<br>⇒ 且实测 `grep -rn "StringFields" --include=*.java`（收口前）**只命中共享层那一个文件自身** ⇒ ⇒ **共享层那份全仓零消费**，而 article 域被迫留了 `web/ArticleStringFields.java`（**逐行副本**）⇒ ⇒ **同一份实现两处落点** |
| **B 侧基准（逐字读过）** | RAG `service/article/article-api/src/main/java/com/rag/article/article/api/dto/GoStyleStringFields.java:50-94` —— 类声明 `final class GoStyleStringFields`（**B 侧也是包私有**）+ `static String parse(...)` + `private static String goTypeName(...)` |
| **修法（已实施）** | ① 共享层 `common/web/StringFields.java`：`final class` → **`public final class`**、`static String parse` → **`public static String parse`**（**方法体一字未改** ⇒ 错误文案 `type mismatch for field "title", expect "string", actual "number"` 逐字节不变）；② **删** `service/article/.../web/ArticleStringFields.java`；③ article 域 **2 个** DTO（`CreateArticleReq` / `UpdateArticleReq`）改 import `com.rag.common.web.StringFields` |
| **验收判据** | ① **归一化 diff**：共享层那份 vs article 副本 vs B 侧原类 —— **代码体逐行相同**（唯一差异 = 可见性 ＋ 异常类名 ＋ `Locale` 的 import 写法）；② 编译：`mvn -o -pl common,service/article -am -DskipTests compile` ⇒ **EXIT=0**；③ article 域 **157 例**真触发测试（含真 Tomcat 的 400 文案断言）⇒ 见下「读数」 |

#### D-10｜`common/web/GetFormValuesFilter` 只有**上限闸门**、无取值真值表 ⇒ article 抄了 5 条规则

| 项 | 内容 |
|---|---|
| **现象（实测）** | 共享层 `GetFormValuesFilter` 的公开面只有 `goParseViolation(String)` ＋ `writeGoStyleBadRequest(resp,msg)` ⇒ **没有任何取值方法** ⇒ ⇒ B 侧 `raw`/`first`/`bool`/`int32`/`text` 五条真值表**无处安放** ⇒ ⇒ article 域被迫留 `web/ArticleQueryValues.java` |
| **B 侧基准（逐字读过）** | RAG `service/article/article-api/src/main/java/com/rag/article/article/api/form/GoFormValues.java`（**204 行** · 类声明 `:72` · `raw` `:83-88` · `first` `:133-144` · `bool` `:154-168` · `int32` `:177-193` · `text` `:201-204`）<br>⚠️ **不是** RAG `service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java`（**750 行**）—— 那是**闸门/判据**，与取值真值表**不是同一个类** ⇒ ⇒ 见下「🔴 连带修掉的映射表缺陷」 |
| **修法（已实施）** | ① 共享层**新增** `common/web/FormValues.java`（**照抄 B 侧 204 行原类**；**类名去 `Go` 前缀** —— C 侧既有惯例 `GoStyleBadRequestException` → `BadRequestException`；**异常类型换共享层 `BadRequestException`** = B 侧 `GoStyleBadRequestException` 的 C 侧落点）；② **删** `service/article/.../web/ArticleQueryValues.java`；③ article 域 **6 个**文件改引用（`controller/ArticleController` · `dto/article/ListArticlesReq` · `dto/article/GetArticleReq` · `service/ArticleApiService` ＋ 2 处 javadoc） |
| **验收判据** | ① **归一化 diff**：共享层 `FormValues` vs B 侧 `GoFormValues` ⇒ **方法体 32 行逐行相同**，**唯一差异 = 2 处异常类名**（`GoStyleBadRequestException` → `BadRequestException`）⇒ ⇒ **两条错误文案逐字未变**（`type mismatch for field "x"` / `strconv.ParseInt: parsing "…": invalid syntax (field "…")`）；② 编译 EXIT=0；③ article 域真 Tomcat 测试逐字节断言 400 body |

#### D-11｜共享层缺「jsonb ↔ 结构化值」这一格 ⇒ article 与 message 各留一份 jsonb handler

| 项 | 内容 |
|---|---|
| **现象（实测）** | 共享层既有两份 JSON handler **都不兼容**：① `common/persistence/JsonbTypeHandler` = `extends BaseTypeHandler<String>`（**jsonb ↔ 文本**）⇒ 顶不了 article 域要的 `List<String>`（`secondary_tags`）**与** `Map<String,String>`（`ext_info`）；② `common/persistence/JsonTextTypeHandler` = **text ↔ Map** ⇒ **列绑定不对**（`ps.setString` ⇒ pgjdbc 按 VARCHAR 发送 ⇒ PG 拒收 jsonb 列）<br>⇒ ⇒ article 与 message **各留一份域内副本** ⇒ ⇒ **两份副本的 null 语义还不一样**（见下「🔴 为什么不能合并」） |
| **B 侧基准（逐字读过）** | ① RAG `service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/JsonbTypeHandler.java`（**195 行** · 工具类形态：`final class` + `PG_JSONB` + `MAPPER` + `toJsonb` + 内嵌 `StringListTypeHandler` / `StringMapTypeHandler` + `writeJson` / `readList` / `readMap`）；② RAG `service/message/message-rpc/src/main/java/com/rag/message/message/rpc/mapper/JsonbMapTypeHandler.java`（**118 行** · 单个 handler + `setParameter` 覆写 + 自带 `MAPPER` / `TypeReference<LinkedHashMap<String,String>>`） |
| **修法（已实施）** | ① 共享层**新增** `common/persistence/JsonbValueTypeHandler.java`（照抄 B 侧 article 那份 ⇒ **类名改**：`JsonbTypeHandler` 已被共享层的「jsonb ↔ 文本」版占用 ⇒ 撞车 ⇒ 改名 **`JsonbValueTypeHandler`**（**Value = jsonb 列的结构化值**））；② 共享层**新增** `common/persistence/JsonbMapTypeHandler.java`（照抄 B 侧 message 那份 ⇒ **类名保持不变** ⇒ message 域只需改 import）；③ **删** article 的 `mapper/JsonbTypeHandler.java` ＋ message 的 `mapper/JsonbMapTypeHandler.java`；④ 两域 **14 处**引用改指（article **10 处** · message **4 处**，含 `insertBatch` 里 `#{item.extra,typeHandler=…}` 的**全限定名**） |
| **⚠️ 三处带 `"article"` 前缀的异常文案逐字保留** | B 侧原文就是 `"article jsonb column value is not serializable"` / `"article jsonb array column is not a JSON string array: "` / `"article jsonb object column is not a JSON string map: "` ⇒ ⇒ **L1 文案以 B 侧为准（D16）** ⇒ **不因类上收到共享层而改写**（哪怕类名已不带 article） |
| **🔴 为什么两个 Map 版不能合并** | 两者面向**同一对 Java 类型**（`Map<String,String>`）但**行为不同**：<br>· **写 null**：article 版**不覆写 `setParameter`** ⇒ 走 MyBatis 默认路径；message 版覆写 ⇒ `ps.setNull(i, Types.OTHER)`<br>· **写非 null**：article 版 `ps.setObject(i, PGobject(jsonb))`；message 版 `ps.setObject(i, jsonString, Types.OTHER)`<br>· **读空白串**：article 版返回 `null`；message 版 Jackson 抛 ⇒ `SQLException`<br>⇒ ⇒ **合并 = 凭空制造数据层漂移** ⇒ ⇒ **各自照抄 B 侧对应文件，不合并** |
| **验收判据** | ① **归一化 diff**：article 版 **54 行**代码体**逐行完全相同**；message 版 **34 行**代码体**逐行完全相同**（两份均零差异）；② 编译 EXIT=0；③ article / message 域的**真 PG** 测试（`ArticleJsonbResultMapRealPgTest` / `MessageDomainServiceRealPgTest`）⇒ 读回列内容逐条比对 |

#### 🔴 连带修掉的**映射表缺陷**（本条账的副产品）

| 项 | 内容 |
|---|---|
| **缺陷** | `docs/Mapping_Table/article/article.md:69` 曾把 B 侧**两个不同的类** —— `article-api/…/form/GoFormValues.java`（**取值真值表**）与 `service/common/…/http/GoStyleFormValues.java`（**闸门/判据**，750 行）—— **并成一条映射行** ⇒ ⇒ **那条映射是错的**（把「取值」和「限长」当成同一个职责） |
| **修法** | **拆成两条**：`article/api/form/`（`GoFormValues` · 取值真值表）→ 共享层 `common/web/FormValues.java`；`common/http/GoStyleFormValues`（上限闸门）→ 共享层 `common/web/GetFormValuesFilter.java` ⇒ ⇒ 并在两行都写明「**两者不是同一个类**」 |
| **另一条错映射** | `article.md` §4 能力矩阵原写「`article-rpc/…/mapper/JsonbTypeHandler.java` → `common/persistence/JsonbTypeHandler.java`（共享层）」⇒ ⇒ **错**（那份是 jsonb ↔ **文本**，与 B 侧本类**类型不兼容**）⇒ 已订正为 `JsonbValueTypeHandler.java` |

#### ⚠️ 顺带记一条**守门口径**的坑（**子串假绿**）

`tools/check-mapping-coverage.py` 的 C 列覆盖判据是**子串计数**（`low.count(name.lower()) + low.count(stem.lower())`）⇒ ⇒ 共享层新类 `FormValues.java` 的 stem = `formvalues` ⇒ ⇒ **被已存在的 `GetFormValuesFilter` 里的子串 `FormValues` 顺带命中** ⇒ ⇒ **守门会报「已覆盖」而实际那一行根本没写** ⇒ ⇒ 本次**仍显式补了映射行** ⇒ ⇒ 判据本身在这类「短名 + 长名互为子串」的情形下**偏松**，登记在此（**不改判据** —— 改判据会影响全部 10 个域的读数口径 ⇒ 属另案）。

#### ✅ 读数（2026-11-03）

| 项 | 读数 |
|---|---|
| **编译** | `mvn -o -pl common,service/article,service/message -am -DskipTests compile` ⇒ **EXIT=0**；`… test-compile` ⇒ **EXIT=0** |
| **映射覆盖守门** | `python3 tools/check-mapping-coverage.py` ⇒ **EXIT=0** · C 列 **641/641 = 100%** · B 列 **752/752 = 100%** · B 列全局未覆盖 **0** · 「B 列双向对账」精确 665 · 通配 3 · 目录 66 · 白名单命中 **0** 条 |
| **C 列文件数变化** | `common/` **110 → 112**（+`FormValues` +`JsonbValueTypeHandler` +`JsonbMapTypeHandler`）· `article/` **63 → 60**（−3 份域内副本）· `message/` **53 → 52**（−1 份域内副本）⇒ ⇒ **全仓 C 列 638 → 641**（净 +3） |
| **证据强度** | **E-2（源码级对照）** 为主 —— 「归一化 diff 逐行相同」是**静态**证据；⚠️ **不是 E-4 双侧活体对拍**（本条**未**起 B 侧进程对拍 ⇒ ⇒ 行为等价性由两侧**各自已有**的测试证据支撑，**不新增**对拍证据）⇒ ⇒ 依 E-2 读作「**在该静态层一致**」 |

---

### ✅ DRIFT-COMMON-V20｜gRPC 服务端**出向载体 `GoStyleRequestDeadline` 从未绑上** ＋ **有效期限不含客户端 deadline** —— **已收口（2026-11-04）**

**状态**：**✅ 已收口**（2026-11-04 · `global` 收口域 · 落点 `common/` ⇒ 符合 AGENTS.md §五.3 规则 3）
⇒ ⇒ **闭合 `DRIFT-COMMON-V17` 留下的「另案」**（V17 只绑了**入向**载体，出向载体当时被判「爆炸半径更大 ⇒ 另案」⇒ **本条即该另案**）。
⇒ ⇒ **关联**：comment 域台账 `docs/DRIFT/comment/verification-comment-service-2026-11-03.md` 残留的 **B 组第 ⑨ 点 = 本条 D-15**
⇒ ⇒ ⚠️ **该域的棘轮会按设计转红**（见下「🔴 连带效应」）。

| 项 | 内容 |
|---|---|
| **现象（实测 · 修前）** | ① **全仓 `src/main` 里 `GoStyleRequestDeadline.bindAbsoluteNanos` / `.begin(` 零调用点** —— `git grep -n "GoStyleRequestDeadline\.\(bindAbsoluteNanos\|begin\)" HEAD -- '*/src/main/*'` **只命中 1 处**，且是 `common/…/observability/DeadlineState.java:107` 的**一段 javadoc 提及**（不是调用）⇒ ⇒ **出向载体在生产里从不绑**；② ⇒ 两个消费点因此**恒读到 -1**：① `common/protection/OutboundDeadlineInterceptor:258`（出向 gRPC 上界）② `common/redis/Deadline:114`（Redis 期限闸门）⇒ ⇒ **出向 gRPC 无上界、Redis 期限闸门形同虚设**；③ C 侧 gRPC 服务端**有效期限 = 服务端自己的 `rpc-timeout`**（`rpcDeadlineNanos(long)`）⇒ ⇒ **客户端 deadline 完全不参与** |
| **B 侧基准（逐字读过消费点）** | RAG `service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java`：`:302-303`（`serverBudgetNanos` = `toNanosSaturated(timeout)`）· **`:304`（`effectiveTimeoutNanos = Math.min(serverBudgetNanos, clientDeadlineRemainingNanos())`）** · `:311-312`（`absoluteDeadlineNanos = saturatingAddNanos(monotonicNanos, effectiveTimeoutNanos)` · **入口算一次**）· `:317`（`hasDeadlineBudget = effectiveTimeoutNanos != Long.MAX_VALUE`）· `:339-340`（**调度器延时用 effective**）· `:501`（无预算**早退** ⇒ 两个载体都不绑）· **`:516-522`（两个载体绑同一绝对时刻）** · `:751-769`（`clientDeadlineRemainingNanos` · 纳秒精度 · 已过期返 0）· `:775-784`（`saturatingAddNanos`） |
| **🔴 B 侧注释与代码不一致 ⇒ **以代码为准** | B 侧 `:298-301` 的注释写「`timeout<=0` 时**保持旧行为**（不引入客户端 deadline）」⇒ 但 B 侧**代码** `:304` 是 `Math.min(serverBudgetNanos, clientDeadlineRemainingNanos())` ⇒ **无条件参与** ⇒ 按 AGENTS.md §3.1「不要拿代码注释当对照基准」⇒ ⇒ **本仓照抄代码**。<br>⚠️ **这条不是边角**：本仓 **comment / favorite / task / hot 四域的 `application.yml` 没配 `observability.rpc-timeout`**（实测）⇒ ⇒ 这四域的客户端 deadline 是**唯一**的预算来源 ⇒ ⇒ 不实现 min ⇒ 这四域的客户端 deadline **完全不参与** ⇒ ⇒ 与 B 侧漂移 |
| **修法（已实施 · 2026-11-04）** | ① `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` —— **入口**算一次 `effectiveTimeoutNanos()`（= `min(serverBudgetNanos(), clientDeadlineRemainingNanos())`）⇒ `absoluteDeadlineNanos = saturatingAddNanos(startNanos, effectiveTimeoutNanos)`（**早于 `registry == null` 早退** ⇒ ⇒ 与「装没装 Micrometer registry」无关）；② `withDeadline` 的 `onHalfClose` 里**两个载体绑同一绝对时刻**（`DeadlineState.bindAbsoluteNanos` ＋ `GoStyleRequestDeadline.bindAbsoluteNanos`）⇒ ⇒ 不会出现「SQL 已过期但出向还活着」；③ **调度器延时改用 min 后的值**（B 侧 `:339-340`），**注册条件不变**（服务端预算为正才注册 · 同 B 侧）；④ **删除**旧 `rpcDeadlineNanos(long)`（其语义 = 只取服务端预算 ⇒ 已被 `serverBudgetNanos()` 取代） |
| **三段式（AGENTS.md §3.2）** | **① 比 B 侧多做了什么**：C 侧因 D11 包压平，「观测＋超时调度」在 `ObservabilityAutoConfiguration.GrpcObservationInterceptor`、「**绑载体**」在同一类的 `withDeadline`（B 侧两者都在 `RpcInterceptor` 一个类里）⇒ ⇒ **纯形态差异**。<br>**② 语义还一样吗**：**一样** —— 逐点对照 B 侧 `:302-304` / `:311-312` / `:317` / `:339-340` / `:501` / `:516-522` / `:751-769` / `:775-784` 全部同形；「**无预算 ⇒ 两个载体都不绑**」由 C 侧 `withDeadline` 的 `absoluteDeadlineNanos == Long.MAX_VALUE` 早退实现（= B 侧 `:501` 早退；B 侧 `:517-520` 那个三元分支**其自身注释已写明「不可达 · 只是冗余防护」**）⇒ ⇒ **等价**。<br>**③ B 出处**：同上行号 |
| **验收判据** | ① **真 socket**（`NettyServerBuilder.forPort(0)` ＋ `NettyChannelBuilder`）的 **4 例**新测试 ⇒ 断言点落在**handler 体内**（证「绑在真正跑 handler 的线程上」而不是「代码里写了 bind」）⇒ 见「读数」；② **变异双向**：变异 A（摘掉出向载体绑定）⇒ **4/4 红**；变异 B（`effectiveTimeoutNanos` 退回只取服务端预算）⇒ **①④ 红、②③ 绿** ⇒ ⇒ **两次都还原复绿**；③ 全仓回归；④ 三道守门 EXIT=0 |
| **⚠️ 诚实边界** | ① **作用域随 `onHalfClose` 返回而结束** ⇒ ⇒ **异步 handler 不在作用域内**（与 B 侧同款 · 本仓 10 个域的 proto 全是 unary ⇒ handler 在 `onHalfClose` 内同步跑完）；② **无预算 ⇒ 两个载体都不绑**（服务端预算与客户端 deadline **都**没有 ⇒ 恒 `Long.MAX_VALUE`）⇒ 与 B 侧 `:501` 早退同款；③ **`clientDeadlineRemainingNanos()` 只在 `interceptCall` 入口读一次** ⇒ ⇒ 「入口 → `onHalfClose`」那一小段**不计入**（与 B 侧同款 —— B 侧也在入口算）；④ **grpc-java 的服务端 `Context` 里的 deadline 由客户端 `grpc-timeout` 头派生** ⇒ ⇒ **in-process 传输也传播**（实测见下）⇒ ⇒ 「客户端没带 deadline ⇒ 服务端读到 `null` ⇒ effective = 服务端预算」⇒ ⇒ **不带 deadline 的调用方不受本条影响**（对照用例 ③ 钉住） |

#### 🔴 一次**并行跑法**造成的假红（本条副产品 · 读数纪律）

| 项 | 内容 |
|---|---|
| **现象** | 首次全量回归用 `mvn -o -T1C test`（**并行**）⇒ **like-service** 与 **admin-service** 各红 1 条，**两条都是 `DEADLINE_EXCEEDED`** |
| **⚠️ 差点被误判成「D-15 引入回归」** | 那两条红**恰好**是本条改动会影响的语义（服务端提前掐断）⇒ ⇒ 若不复核，就会把一次**环境假红**当成**行为回归** ⇒ ⇒ 差点回滚掉正确的修复 |
| **归因（三步实测）** | ① 用 **HEAD 版（无 D-15）单独**跑 like 那条 ⇒ **4/4 绿** ⇒ ⇒ 「本来红」不成立；② 用 **D-15 单独**跑同样两条 ⇒ like **4/4 绿** ＋ admin **1/1 绿**（机器读数 `code=DEADLINE_EXCEEDED description=rpc deadline exceeded elapsedMs=2022` ≈ 该域 2s `rpc-timeout` ⇒ **正是设计行为**）⇒ ⇒ **D-15 不是根因**；③ **根因 = `-T1C` 并行争抢** —— admin 那条用例**本身要跑 30s**，而它的预算只有 **2s** ⇒ ⇒ 稍有 CPU/IO 争抢就超时；like 那条同理 |
| **⇒ 判定式（可复用）** | **凡用例断言「到点断连 / 超时 / 预算耗尽」，就不能在并行 reactor 下判红** ⇒ ⇒ 必须**单独串行复跑**再归因 ⇒ ⇒ **已入账 [`docs/Pitfalls/_cross/P-24-并行reactor下时间预算类用例会假红.md`](../../Pitfalls/_cross/P-24-并行reactor下时间预算类用例会假红.md)**（**P-24**） |

#### 🔴 连带效应：comment 域的**棘轮**按设计转红

comment 域工作树里 `service/comment/src/test/java/com/rag/comment/grpc/CommentOutboundDeadlineCarrierGateTest.java`（**未入库新文件**）把「出向载体缺席」钉成了**三条会红的断言**：
① `outboundCarrierPresent` 期望 `isFalse()`（`:405-411`）② 入向剩余期望 `> CLIENT_DEADLINE_MILLIS`（`:415-422`）③ 出向剩余期望 `isEqualTo(-1L)`（`:426-430`）。
⇒ ⇒ **本条落地后三条同时转红** —— 且断言文案**自己就写着**「📌 已登记差异（台账 D-15 · 归属 global 收口域）…… **本条变红 = 共享层已修** ⇒ ⇒ 届时请把本条改回 B 侧真值断言并撤掉台账 D-15」。
⇒ ⇒ **这是棘轮按设计响铃，不是回归** ⇒ ⇒ **由 comment lane 翻断言并撤 D-15**（本轮**不代改他 lane 的在制品**）。

#### ✅ 读数（2026-11-04）

| 项 | 读数 |
|---|---|
| **新增测试** | `common/src/test/java/com/rag/common/autoconfigure/GrpcOutboundCarrierBindingTest.java` —— **4 例** · **真 socket**（`NettyServerBuilder.forPort(0)` ＋ `NettyChannelBuilder`）⇒ ⇒ 验的是「真 wire 上的 deadline 传播」<br>① `outboundCarrierIsBoundAndEffectiveBudgetIsTheMin`（客户端 400ms ＋ 服务端 5s ⇒ **两载体剩余都 ≤400ms**）② `bothCarriersPointToTheSameInstant`（两载体剩余差 ≤2ms）③ `withoutClientDeadlineTheServerBudgetIsUnchanged`（**对照**：客户端不带 deadline ⇒ 仍 5s）④ `clientDeadlineAppliesEvenWithoutServerBudget`（服务端无 `rpc-timeout` ⇒ 客户端 deadline 独立生效） |
| **变异 A** | 拿掉 `withDeadline` 里的 `GoStyleRequestDeadline.bindAbsoluteNanos` ⇒ **4/4 全红**（`Tests run: 4, Failures: 4`）⇒ ⇒ 「出向载体绑定」这条有牙齿；⇒ ⇒ 还原后同条件复跑 = **4/4 绿** |
| **变异 B** | `effectiveTimeoutNanos()` 退回只取 `serverBudgetNanos()`（去掉 min）⇒ **2/4 红** —— ① `Expecting actual: 4999733791L to be less than or equal to: 400000000L` · ④ `Expecting actual: -1L to be greater than: 0L` ⇒ ⇒ **min 语义有牙齿**，且②③（对照）**仍绿** ⇒ ⇒ 还原后同条件复跑 = **4/4 绿** |
| **服务端 ctx deadline 实测** | 临时探针（已删）量「服务端 interceptor 里 `io.grpc.Context.current().getDeadline()` 看到什么」⇒ **客户端不带 deadline ⇒ `null`**（⇒ effective = 服务端预算）· **客户端带 5s ⇒ `4.994771750s from now`**（**netty 与 in-process 都传播**）⇒ ⇒ 证明「不带 deadline 的调用方不受影响」这条对照成立 |
| **common 全量** | `mvn -o -pl common -am test` ⇒ **`Tests run: 245, Failures: 0, Errors: 0, Skipped: 0`**（241 → 245，+4 新例） |
| **三道守门** | `check-arch-invariants.py` ⇒ **EXIT=0**（PASS 10 · FAIL 0 · N/A 2 · PENDING 1）· `check-docs-vs-tree.py` ⇒ **EXIT=0**（新增测试文件 ⇒ 已重跑 `python3 tools/gen-file-tree.py` ⇒ `docs/File_Tree/common.md` 已更新）· `check-mapping-coverage.py` ⇒ **EXIT=0**（C **641/641** · B **752/752**） |
| **§11.2 状态表复算** | 按台账自述口径（逐行扫 §11.2 · 取**第 5 格**状态）机器复算 ⇒ **承接行 91 / 标记数 91 / 复合状态行 0 / PASS 89 / NOT_IMPLEMENTED 0 / INTENTIONALLY_CHANGED 2 / FAIL 0 / FRAMEWORK_DIFFERENCE 0 / NOT_VERIFIED 0 / BLOCKED 0** ⇒ ⇒ 与自报数**逐项一致**（本条**不新增 B 承接行** ⇒ §11.2 不动） |
| **⚠️ 全仓回归读数** | **串行**（`mvn -o test`，**不带 `-T1C`**）⇒ **BUILD SUCCESS · 16/16 模块 SUCCESS** ⇒ **925 例 · Failures 0 · Errors 0 · Skipped 0**（模块级汇总：common **245** · article **157** · follow **72** · like **110** · message **103** · users-common **46** · user **40** · admin **152** · proto/comment/favorite/hot/task **0**）⇒ ⇒ ⚠️ **计数只取回归日志的模块级汇总**（AGENTS.md §五.8 规则 ① · 不取 `surefire-reports/*.xml` 求和）<br>⚠️ **同一份代码在 `-T1C` 并行下会红 2 条**（like / admin 各 1 条 `DEADLINE_EXCEEDED`）⇒ ⇒ **已判定为环境假红**，三步实测见上「并行跑法造成的假红」⇒ ⇒ **本条读数取串行** |

---

### ✅ DRIFT-COMMON-V21｜共享层「HTTP 预算 × 业务失败」竞态（F-15）＋ **追踪导出配置 5 域统一** —— **已收口（2026-11-05）**

**状态**：**✅ 已收口**（2026-11-05 · `global` 收口域 · 落点 `common/` ＋ 4 个域的 `application.yml` ⇒ 符合 AGENTS.md §五.3 规则 3）
⇒ ⇒ **F-15 首次由 hot 域登记**（`docs/DRIFT/hot/verification-hot-service-2026-11-03.md` §五·F-15 · 该台账在 `align/hot` 分支上）
⇒ ⇒ 本条**只改共享层与配置**，**未改 hot 域任何一行**（hot 域只登记、不改）。

本条含**两件事**：

| # | 事 | 一句话 |
|---|---|---|
| **F-15** | 共享层的「HTTP 预算」与「业务失败」**竞态** ⇒ 客户端可能收到**混血响应** | 两条写路径并发写同一个响应 ⇒ 状态码/CT 是业务信封的、body 却是纯文本 ⇒ **信封契约被打断** |
| **OTEL-5** | **5 个域**的追踪导出配置（`otel:` 段）**整段缺失** ⇒ span 一条都导不出去 | 共享层按 B 侧 `if len(c.Endpoint) > 0` 口径 ⇒ **端点为空 ⇒ 不装 exporter** ⇒ **零编译错误、零测试红灯** |

---

#### F-15｜共享层「HTTP 预算 × 业务失败」竞态 ⇒ **混血响应**

| 项 | 内容 |
|---|---|
| **现象** | **两条写路径并发写同一个响应**：<br>① **容器线程**（预算耗尽 · `HttpMiddleware:441-445`）⇒ `rec.markTimedOut()` → `cancel(true)` → `rec.writeTimeoutResponseQuietly(503)` ⇒ 写 `status=503` + `Content-Type=text/plain` + `"Request Timeout\n"` + `flush()` ⇒ **commit**；<br>② **worker 线程**（业务异常 ⇒ `@ExceptionHandler` ⇒ `ObservabilityResponseWriter#flushBuffer`）⇒ `original.flushBuffer()` ⇒ **commit**（此刻 body 还在**捕获缓冲**里 ⇒ wire 上是空的）<br>⇒ ⇒ 谁先落定，客户端拿到哪个形状：**① 先** ⇒ `503 + text/plain + "Request Timeout\n"`（干净）· **② 先** ⇒ `200 + application/json + body 被 ① 追加成 "Request Timeout\n"` ⇒ ⇒ **混血**（状态码与 CT 是业务信封的、body 是纯文本 ⇒ **客户端按 JSON 解会失败**） |
| **位置（共享层）** | `common/src/main/java/com/rag/common/observability/ObservabilityResponseWriter.java`（`writeTimeoutResponseQuietly` · `flushBuffer` · `getWriter`/`getOutputStream` 捕获缓冲）＋ `common/src/main/java/com/rag/common/observability/HttpMiddleware.java:438-448`（超时分支） |
| **B 侧同源核对（🔴 关键）** | RAG `service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java:1153`（`ObservabilityResponseWriter#flushBuffer`）与 `:1222`（`flushTo`）**与 C 侧逐字同形** ⇒ ⇒ **竞态两侧都有** ⇒ ⇒ **不是 C 侧引入的漂移** ⇒ 是**双方共有的共享层缺陷**（本仓 B 侧只读 ⇒ 只在 C 侧修） |
| **修法（已实施）** | 在 `ObservabilityResponseWriter.writeTimeoutResponseQuietly` 里加一道闸：**底层响应已 commit ⇒ 超时分支完全弃权**（不得再写 status / CT / body）⇒ 改为**把已捕获的业务 body 补写出去**（只写 body：头已随 commit 上 wire）⇒ ⇒ 客户端拿到**干净的「200 + 业务信封」**；⚠️ **只动超时分支，<b>不碰 worker 的提交路径</b>** ⇒ ⇒ 异常路径的字节形状（commit-then-append、Content-Length 是否生效）**一字未变** ⇒ ⇒ **B 侧字节级不动** |
| **三段式（AGENTS.md §3.2）** | **① 比 B 侧多做了什么**：B 侧**没有**「已提交就弃权」这道检查 ⇒ 本仓多了一道**互斥且幂等**的闸。**② 语义还一样吗**：**一样，且更安全** —— commit 之后**头已上 wire** ⇒ 再改 status / CT **对客户端无效**、而 body **会被追加** ⇒ ⇒ 那只会造出「混血」这个**非法形状**；本仓改成「弃权 + 补写业务 body」⇒ ⇒ 客户端拿到的是 B 侧在该窗口里的**干净候选之一**（另一个干净候选 = 503 纯文本，由「超时先落定」那条分支产出）⇒ ⇒ **只删掉非法形状，不新增任何形状** ⇒ ⇒ 在「B 侧行为确定」的所有场景下**逐字节一致**。**③ B 出处**：同上行号 |
| **验收判据** | **确定性**守卫（真 Tomcat 复现竞态必 flaky ⇒ 不用）：`common/src/test/java/com/rag/common/observability/HttpTimeoutRaceNoMixedResponseTest.java` —— **2 例** · **显式控制两个写者的先后**（不是靠竞态碰运气）⇒ ① worker 先提交 ⇒ body 必须是业务信封且**不得**含 `"Request Timeout"` ② 超时先落定（**反向对照**）⇒ 必须是干净的 `503 + "Request Timeout\n"` 且**不得**混进信封 |
| **⚠️ 替身为什么必须仿 Tomcat 的 commit 语义** | Spring 的 `MockHttpServletResponse` **不冻结**已提交响应的 status / CT ⇒ ⇒ 用它断言会**看不到混血**（status 会被改成 503 ⇒ 看着像正常超时）⇒ ⇒ 守门自造 `TomcatLikeResponse`：commit（`flushBuffer()` **与** `getWriter().flush()` —— Tomcat 的 `PrintWriter.flush()` ⇒ `OutputBuffer.flush()` ⇒ 状态行与头上 wire）**冻结** status / CT，commit 后**仍可追加 body**（这正是混血的成因） |
| **变异实测** | 拆掉「已提交 ⇒ 完全弃权」那道闸 ⇒ **① 红、② 仍绿**（红灯精确）⇒ 机器读数：`Expecting actual: "Request Timeout\n" not to contain: "Request Timeout"` ⇒ ⇒ **判据有牙齿** ⇒ ⇒ 还原后同条件复跑 = **2/2 绿** |

#### OTEL-5｜**5 个域**的追踪导出配置（`otel:` 段）统一补齐

| 项 | 内容 |
|---|---|
| **现象（实测）** | `comment` / `favorite` / `message` / `task` / `hot` 五域的 `application.yml` **没有 `otel:` 段** ⇒ ⇒ 共享层 `GoStyleOtelConfig` 读到的 `otel.exporter.otlp.endpoint` = **null** ⇒ ⇒ 按 B 侧的 `if len(c.Endpoint) > 0` 口径**不装 exporter** ⇒ ⇒ 该域的 span **一条都导不出去**（跨域链路在 Jaeger 侧断链）⇒ ⇒ **零编译错误、零测试红灯**（配置面缺一整段 ⇒ 没有任何代码路径会报错） |
| **B 侧基准（逐字读过）** | B 侧各域的**服务自己的 yml** 里都有 `otel:` 段：<br>· **api 侧**：`comment-api:195-204` / `favorite-api:221-229` / `message-api:230-238` / `task-api:275-284` ⇒ 形状 = `exporter.otlp.endpoint: http://${OTEL_ADDR:jaeger}:${OTLP_PORT:4317}`（**带 scheme**）+ `protocol: grpc` + `resource.attributes.service.name: <域>-api` + `traces.sampler: always_on`（favorite 的 api 侧写 `parentbased_always_on`）<br>· **rpc 侧**：`comment-rpc:364-372` / `favorite-rpc:352-361` / `message-rpc:337-345` / `task-rpc:341-349` ⇒ 形状 = `service.name: <域>-rpc` + `endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:jaeger:4317}`（**裸**）+ `traces.sampler: parentbased_always_on`<br>⇒ ⇒ **两套拼法合并成一个进程（D1）** ⇒ ⇒ 本仓取**统一形状**（与 like / hot 同款）：**两套服务名都写**（值一致 ⇒ 无歧义）+ **endpoint 取 api 侧**（带 scheme ⇒ 显式优于隐式）+ **protocol = grpc**（= B 侧 `Batcher: otlpgrpc`）+ **sampler = `always_on`**（共享层 `toRatio` 把 `always_on` 与 `parentbased_always_on` **都**映射成 ratio = 1.0 ⇒ **行为等价**） |
| **服务名的取值（🔴 一处登记偏离）** | D1 合并 ⇒ B 侧 api / rpc **两个** `Telemetry.Name`（`<域>-api` / `<域>-rpc`）只能填**一个** ⇒ ⇒ 取 `<域>-service`（= `spring.application.name`）⇒ ⇒ **登记为 D1 连带偏离**（**值侧**；键名与形状逐条对齐 B 侧） |
| **修法（已实施）** | `comment` / `favorite` / `message` / `task` 四域的 `src/main/resources/application.yml` **各补一段** `otel:`（插在顶层 `logging:` 之前 ⇒ 与 like / hot 同位）⇒ ⇒ **5 域同构**（hot 那份在 `align/hot` 分支上，**值与本轮四域逐条一致**） |
| **⚠️ `hot` 域为何没在主干改** | `hot` 域的实现**尚未合入 main**（主干里还是空壳 ＋ **48 行**的 yml 桩；分支上是 **365 行**）⇒ ⇒ 改主干的 hot yml ⇒ **合并时必冲突** ⇒ ⇒ **不动它** ⇒ ⇒ 它那份在 `align/hot` 分支上，**合入后自动被下面的通用规则覆盖** |
| **第 6 个缺口：`user-service`（users-user 子域）** | 它**同样没有** `otel:` 段 ⇒ ⇒ **2026-11-05 用户指示后已补齐**（用户原话「补」）⇒ ⇒ 形状与 like / hot / 本轮四域**逐条同构**（B 侧基准 = RAG `service/users/user/user-api/src/main/resources/application.yml:192-201` · `service.name: user-api` + 带 scheme 的 endpoint + `sampler: always_on` ⇒ 与 B 侧 **admin-api** 那份同款）⇒ ⇒ **6 域全部齐备** ⇒ ⇒ A14 白名单相应从 8 域扩到 **9 域** |
| **🔴 机器守门（新增 A14）** | `tools/difftest/check-arch-invariants.py` 新增 **A14**「追踪导出配置（`otel:` 段）齐备且服务名自洽」：<br>① **白名单**：清单内各域（article / comment / favorite / follow / like / message / task / users-admin）**必须**有 `otel:` 段，且端点非空 + `protocol = grpc` + sampler 属两族 + **服务名 == 该域 `spring.application.name`**（**钉不变量而非字面值** ⇒ 域改名自动跟随 ⇒ 不会假红）+ 两套拼法同时出现时**须彼此相等**；<br>② **通用规则**：**任何**域只要 yml 里**有** `otel:` 段就必须合规 ⇒ ⇒ 清单外的域（hot 合入后 / user-service 补齐后）**写出来即刻被覆盖** ⇒ ⇒ **白名单不会随每次合入而腐烂**<br>⇒ **实测 PASS 8/8** · **self-test 14/14 判据均能红**（含 A14）· **变异实测**（删掉 message 域的 endpoint ⇒ A14 当场报「缺失或为空 ⇒ 不装 exporter」⇒ 还原后复绿）⇒ ⇒ 配套更新 `docs/架构符合性断言.md`（新增 A14 段 ＋ 状态行）＋ `docs/README.md`（A1–A13 ⇒ **A1–A14**） |

#### ✅ 读数（2026-11-05）

| 项 | 读数 |
|---|---|
| **F-15 守卫** | `HttpTimeoutRaceNoMixedResponseTest` —— **2 例**（① worker 先提交 ⇒ 干净信封 ② 超时先落定 ⇒ 干净 503）⇒ **2/2 绿**；**变异**（拆掉弃权闸）⇒ **① 红 ② 绿** ⇒ 还原复绿 |
| **otel 统一** | **5 域** yml 各补一段（comment / favorite / message / task + **user-service**）⇒ **A14 PASS 9/9**；**self-test 14/14**；**变异实测** ⇒ 删 endpoint 即红、还原即绿 |
| **全仓回归** | **串行**（`mvn -o test`，**不带 `-T1C`**）⇒ **BUILD SUCCESS · 16/16 模块 SUCCESS** ⇒ **927 例 · Failures 0 · Errors 0 · Skipped 0**（模块级汇总：common **247**（245 → 247，+2 条 F-15 新例）· article **157** · follow **72** · like **110** · message **103** · users-common **46** · user **40** · admin **152** · proto/comment/favorite/hot/task **0**）⇒ ⇒ ⚠️ **计数只取回归日志的模块级汇总**（AGENTS.md §五.8 规则 ①） |
| **三道守门** | `check-arch-invariants.py` ⇒ **EXIT=0**（**PASS 11 · FAIL 0 · N/A 2 · PENDING 1**，共 **14** 条 ⇒ **A14 已计入**；`--self-test` ⇒ **14/14 判据均能红**）<br>⚠️ **一次环境假红（2026-11-05 · user-service 那轮）**：首次串行全仓回归里 **article 的 6 条 Kafka 用例全红**（`NoClassDefFound: Could not initialize class …KafkaTestBase` ⇒ 根因 `Container startup failed for image apache/kafka:3.7.2` ⇒ `Container exited with code 126` ⇒ **Kafka 容器起不来** ⇒ 与本轮改动**无因果**）⇒ ⇒ **单独复跑那 6 条 ⇒ 全绿**（`Tests run: 6, Failures: 0`）⇒ ⇒ **复跑整轮串行全仓 ⇒ 16/16 SUCCESS · 927 例全绿**⇒ ⇒ **机器当时的状态**：`docker ps` 有 **25 个运行中容器 ＋ 924 个卷（32.7GB）** ⇒ 资源吃紧 ⇒ Kafka 容器偶发 exit 126⇒ ⇒ **判定式（接 P-24）**：**「容器起不来」类红（`ContainerLaunchException` / exit 126 / `NoClassDefFound …TestBase`）⇒ 同样不能直接归因于代码 ⇒ 必须单独复跑**· `check-docs-vs-tree.py` ⇒ **EXIT=0**（新增测试文件 ⇒ 已重跑 `tools/gen-file-tree.py`）· `check-mapping-coverage.py` ⇒ **EXIT=0**（C **641/641** · B **752/752**） |

## 11.6 完成度统计

> **口径**：按**职责**统计，**不按文件数或行数**（§11.6 明令）。

### 按状态（88 个 B 源类的承接结果）

> **计数口径（§「状态自报 ≠ 现状」纪律）**：本表数字**从 §11.2 承接表的「状态」列逐行重数**
> ⇒ ⇒ **不是手填**、也不是文件数/行数。
>
> ⚠️ **口径订正（2026-11-02 · 批次 A）**：用同一脚本重数 **HEAD 版本**的 §11.2，发现**旧表自报数与自己的表差 2**：
> 旧报「89 行 / 91 标记 / NOT_IMPLEMENTED 30」，**实测为 91 行 / 93 标记 / NOT_IMPLEMENTED 32**（PASS / FAIL 等其余各档一致）。
> ⇒ 根因：**3 个 `users-common` 跨域上收行被当 1 行合并算**（−2 行），**2 个复合状态行（`PASS/FAIL`）的重复计数没对上**（−2 标记）。
> ⇒ ⇒ 现全部按**实测**写，并给出可复算脚本。

| 状态 | 数量 | 说明 |
|---|---|---|
| **PASS** | **89** | 职责已完整承接 + 行为一致 + 有证据（源码级/对拍）。**含已修项**：V01 的 2 个 redis auto-config + V02 的 2 个 MyBatis 拦截器 + **V05**（熔断回落语义）+ **V06**（窄接口 2 类 + 模板解析实现 + 消费方改走窄接口）+ **V03**（gRPC 保护链 5 层 + 客户端熔断 + 出向 deadline + Prometheus 写入面 + 失败判据 + 链序 + 降载双条件 + 降载统计）+ **V07**（`security/` 3 类）+ **批次 E**（`MaxConnsFilter` / `MqConsumer` / `ObservabilityLifecycle` + 两个池 / `LoggerFactoryBean` / `handle(...)` 非 gRPC 路径）+ **批次 C**（`HttpMiddleware` 三条拒绝路径 + `ObservabilityResponseWriter` + `ClientDisconnectProbe` 4 态 + `HttpMiddlewareFilter` 装配 + `HttpPoolLifecycle`）+ **批次 F**（`GoStyleOtelConfig` + `GoStyleOtelAgent` + `GoStyleOtelAutoConfiguration` + `traceparent` 响应头）+ **批次 G**（`JsonbTypeHandler` + `JsonTextTypeHandler` · 真 PG 对拍）+ **批次 H**（REST 侧降载 filter 改走 `AdaptiveShedder` ⇒ **双条件判据回到 B 侧形态**） |
| **NOT_IMPLEMENTED** | **0** | ✅ **清零**（批次 F）。**最后 1 条**（`GoStyleOtelAgent.java`：OTel SDK ＋ OTLP exporter ＋ 传播器 ＋ shutdown hook）已实现 ⇒ 并带动合上 `GoStyleOtelConfig`（上游 `Telemetry` 四字段投影）与 `GoStyleOtelAutoConfiguration`（BFPP 装配点）⇒ 并使 `traceparent` 响应头恢复（既往头缺失——根因是「全局 SDK 没装」）。⚠️ **剩下的是「配置值」而不是「能力」**：10 个域的 `application.yml` 里**没有** `otel:` 段（**yml 搬运已由用户叫停**）⇒ 端点为空 ⇒ 按 B 侧的 `len(c.Endpoint) > 0` 不装 exporter ⇒ span 有效、可导出，但**本进程不向 jaeger 导出**（B 侧 yml 写死 `Endpoint: jaeger:4317`） |
| **INTENTIONALLY_CHANGED** | **2** | 有明确批准依据（D3 starter 化 / D4 生态吸收 / D1 合并 api-rpc / D2+L-13+L-15+L-16 落点决策）。⚠️ **批次 H 由 3 → 2** —— 原来的第 3 条是 REST 侧降载 filter（判「只有 CPU 一半」）⇒ **那条登记的前提本身是错的**（把「上游的自适应 shedder」当成了「B 侧的 REST filter」）⇒ ⇒ 它不是已批准的降级，而是**真实漂移** ⇒ ⇒ **已修**（双条件判据回到 B 侧形态）⇒ ⇒ **D-04 已撤销降级**；⚠️ 剩下的 2 条 = HTTP 熔断（resilience4j vs googlebreaker · **D-02 + V09 已登记**）＋ CPU 降载的 **gRPC 侧**（resilience4j 无等价物 ⇒ 自研 `AdaptiveShedder` · **D-04 已登记**） |
| **FAIL** | **0** | ✅ **无**。⚠️ **V01 / V02 / V03 / V05 / V06 / V07 已于 2026-11-02 全部修复**（批次 A / B / D）⇒ 🔴 FAIL 清零 |
| **FRAMEWORK_DIFFERENCE** | **0** | ✅ **清零**（批次 F）。既往 2 条：① `TraceparentFilter`（值来源不同 ⇒ 实际根因是「全局 OTel 没装」，装了就恢复，已用 `TraceparentFilterTest`（4 例）对拍）② `GoStyleOtelAutoConfiguration`（缺 `GlobalOpenTelemetry.set` / exporter / sampler 映射）⇒ 已补齐（对拍：真 Spring 上下文 + 真 gRPC 假 OTLP 接收端）。⚠️ **差异本身仍保留在 § 11.4 框架差异清单**（§十：已被测试证明不影响业务可观察行为 ⇒ 可判 PASS） |
| **NOT_VERIFIED** | **0** | ✅ **清零**（批次 G）。最后 1 条（`JsonbTypeHandler` / `JsonTextTypeHandler`，跨域上收）已用**真 PostgreSQL** 闭合（`JsonTypeHandlerPgTest` 10 例）—— 此前判 NOT_VERIFIED 的理由是「**有实现、无证据**」，不是「实现缺失」⇒ 本轮**实现未改一字节**、只补证据。⚠️ gRPC 指标装配点已随 **V03** 转 PASS（两个 meter 已有实现） |
| **BLOCKED** | **0** | 无（Tomcat 10.1 反射链一项因 C 侧未实现而**不适用**，实现时才会 BLOCKED） |
| **合计标记数** | **91**（**91 个承接行**，V05/V06 修复后**无复合状态行** ⇒ 行数 = 标记数） | 覆盖 B 侧 **88/88** 个主类（88 行 = 87 行 B 类（`SnowflakeBean`+`SnowflakeProperties` 合并 1 行）+ 1 行 C 侧新增 typeHandler）**＋ 3 行 `users-common` 跨域上收** |

> **可复算脚本**（从 §11.2 的「状态」列逐行重数，**禁止手填**）：
> ```python
> # 逐行扫 §11.2 区间 → 每行取第 5 格（状态）→ 匹配 **STATE** 标记 → 计数
> # 复合状态（**PASS**…/ **FAIL**…）两边都计
> ```
> **本轮实测输出（2026-11-02 · 批次 H 后）**：`承接行 = 91 · 标记数 = 91 · 复合状态行 = 0 · PASS **89** / NOT_IMPLEMENTED **0** / INTENTIONALLY_CHANGED 2 / FAIL **0** / FRAMEWORK_DIFFERENCE **0** / NOT_VERIFIED **0** / BLOCKED 0`
>
> ⚠️ **口径边界（2026-11-03 · `global` 收口域补）**：本表**只统计 §11.2 的 91 个承接行**
> ⇒ ⇒ **漂移条目（V01–V18）不在表内** ⇒ ⇒ 「**状态表清零 ≠ 漂移清零**」。
> ⇒ ⇒ **本轮（2026-11-03）改判/新登记 5 条**，均**不在**上表计数内：
> | 条目 | 本轮状态 |
> |---|---|
> | **V11** | ✅ **已裁决 · 非漂移**（用户定：行为侧 C ≡ B ⇒ 归属不动 ⇒ AGENTS.md §2.3 的 🔴 解除） |
> | **V12** | ⚪ **裁决保留**（两个类都留 ⇒ 处置措辞改为「等 article / task 域落地时再判」＋ **口径订正**：原「同一异常映射出两个 code」当前**不成立**） |
> | **V16** | ✅ **已收口（2026-11-05 · `global` 收口域）** · Stat / Prometheus 两个开关**已接线**（原为「接线缺口」，现行配置下行为等价 ⇒ 非漂移）⇒ 两个 Bean 改为在**构造期**接开关 ⇒ 关掉时 **Stat 层不记样本 ＋ 不起那条 60s 上报线程**、**Prometheus 层一条指标都不写** ⇒ ⇒ 与 B 侧「层不拼进链」逐项等价（B 出处：`GoStyleRpcProtectionChain.java:228/235` ＋ `GoStyleStatInterceptor.java:82`）⇒ 守门 `ProtectionLayerSwitchWiringTest`（4 例 · 真 gRPC 调用）⇒ **变异两轮**（抽接线 ⇒ 2 红 / 只抽线程门 ⇒ 1 红且红在 `reporterStarted()`）⇒ 还原 4/4 绿 |
> | **V17** | ✅ **已修（本轮 · 2026-11-03）** · gRPC 服务端路径**零绑定点** ⇒ **PARITY-006 根因** —— 已在 `GrpcObservationInterceptor` 补 `onHalfClose()` ⇒ 在 handler 线程上绑**入向** `DeadlineState`（绝对时刻 = 调用开始 ＋ `rpc-timeout`）⇒ 真 PG 两场景（服务端预算到点 / 客户端主动取消）⇒ 在途写 **0 行**；变异实测 ⇒ 两条都**红在核心判据**（`0L` vs `1L`）⇒ ⇒ 跨域复测（admin 域 E-4-R2）**待 admin 域 lane 落地** |
> | **V18** | ⚪ **新登记** · 出向 deadline 的**运行期守门缺口**（装配层有网 · 运行期差分无网） |
>
> ⚠️ **重数脚本（本轮实际运行，不是手填）**：
> ```python
> import re
> STATES = ['NOT_IMPLEMENTED','INTENTIONALLY_CHANGED','FRAMEWORK_DIFFERENCE','NOT_VERIFIED','BLOCKED','PASS','FAIL']
> # 逐行扫 §11.2 区间 → 每行拆成格 → 取**状态格**（正则以 ‘**STATE**’ 开头的那个格）
> # ↳ 复合状态两边都计 → 行数 / 标记数 / 各档计数
> ```
> ⚠️ **订正说明**：此前**同一处并存过两行读数** —— 一行是批次 F 前的 `PASS 84 / NOT_IMPLEMENTED 1 / FRAMEWORK_DIFFERENCE 2 / NOT_VERIFIED 1`，另一行是更早的 `PASS 53 / NOT_IMPLEMENTED 29 / FAIL 1` ⇒ ⇒ **彼此自相矛盾**（且都低于上方状态表）⇒ 属「状态自报 ≠ 现状」的老毛病
> ⇒ ⇒ 现**只保留一行权威读数**，且**每次改判 §11.2 后必须重跑同一脚本覆盖它**（禁手填、禁并存两行）。

### 按「能力面」看（更能反映真实完成度）

| 能力面 | 状态 | 证据 |
|---|---|---|
| 社区事实协议（L1 wire + JCS 指纹 + 投递 + 端点） | 🟢 **已承接** | 23 个 JSON tag 与 B 侧逐字一致；JCS **同库**（`org.erdtman.jcs`）⇒ 幂等指纹不会漂；投递节奏 3 线程名/批次 16/10s/1s 逐字一致 |
| 响应信封 + 错误形状（404/405/400/413/503） | 🟢 **已承接** | `text/plain; charset=utf-8` + nosniff + body 尾 `\n` 逐字一致；`data:null` 保留 |
| 雪花 ID | 🟢 **已承接** | 位段 41/10/12 + EPOCH 逐项一致；**时钟回拨免疫**保留 |
| 保护层配置 + 默认值 | 🟢 **已承接** | `rag.protection` 12 项默认值逐条一致 |
| Filter 槽位 | 🟢 **已承接** | +2/+3/+5/+6/+8 与 B 一致 |
| 观测常量与判定顺序 | 🟢 **已承接** | 7 key / 3 kind / 500ms / 严格 `>` / 业务码优先 / `http.route`=原始 URI |
| HTTP 熔断（key + 判据 + 回落语义） | 🟢 **已承接** | key 与判据一致；✅ **回落语义已与 B 一致**（V05 已修：`UNKNOWN` ⇒ 回落 raw URI、熔断仍生效；`NOT_MATCHED` ⇒ 不进 breaker）；⚠️ **机制类型不同**（V09，仍挂 D-02 的 L3 降级） |
| 路由模板解析（三态 + 窄接口） | 🟢 **已承接** | ✅ **V06 已修**：`RouteResolution`（三态 record）+ `RouteTemplateSource`（窄接口）+ `RouteTemplateResolver`（实现）+ `RouteTemplateAutoConfiguration`（独立 auto-config）⇒ 消费方**经窄接口**取模板 |
| HTTP 熔断（resilience4j 接线） | 🟢 **已承接** | `compose` 显式嵌套 + 单次 `server.intercept(...)` ⇒ 比 B 的 10 处分散更健壮 |
| CPU 降载 | 🟢 **已承接** | ✅ **批次 H 已闭合**：**gRPC 侧**（批次 B：双条件判据 ＋ 三计数统计日志）＋ **REST 侧**（批次 H：filter 改走 `AdaptiveShedder` ⇒ **双条件判据 + pass/fail 记账 + 浮点 RT 口径**回到 B 侧形态）⇒ ⇒ **D-04 撤销降级**；⚠️ **仅剩「HTTP 侧的可观察面」缺口**（无每分钟统计行 ＋ 无 `[http] dropped` 行）⇒ **B 侧原生** ⇒ 已登记 **D-08** ⇒ **不是 C 的迁移缺陷** |
| MaxBytes | 🟢 **已承接** | 裸 413 + 空 body + 无 Content-Type + 只判 Content-Length |
| MaxConns | 🟢 **已承接** | ✅ **批次 E 已修**：抄 RAG 整段（全局 `Semaphore` 闸门 ＋ 超限**裸 503 空 body**）⇒ ⚠️ **推翻了「Tomcat 原生项 ⇒ 零 Java 代码」的旧口径**（Tomcat 超限**排队**、**B 侧立刻 503** ⇒ 两种可观察行为）⇒ ⇒ **不改 yml**（L-5 冻结）⇒ 闸门在 Java 侧；变异实测：把 `tryAcquire` 改成恒成功 ⇒ **2 例红** |
| Redis 断连收口 | 🟢 **已承接** | ✅ **V01 已修**：真调 customizer 断言 `REJECT_COMMANDS`（`RedisAutoConfigurationActivationTest`） |
| Redis 预算感知 | 🟢 **已承接** | ✅ **V01 已修**：断言 `RedisTemplate.getConnectionFactory()` 真的变成 `DeadlineConnectionFactory` |
| SQL 超时注入 + 57014 翻译 | 🟢 **已承接** | ✅ **V02 已修**：新增 `PersistenceAutoConfiguration`；用**与 MP 同形**的 `ObjectProvider<Interceptor[]>` 断言真的收到两个拦截器 |
| jsonb / text 列的 TypeHandler | 🟢 **已承接** | ✅ **批次 G 已闭合**：`JsonbTypeHandler`（jsonb ↔ String · 写走 `PGobject`）＋ `JsonTextTypeHandler`（text ↔ Map · 写走 `setString`）⇒ 对拍：**真 PostgreSQL**（Testcontainers `postgres:16-alpine`）10 例 ⇒ 证 ① jsonb 列**规范化**键序与空白（读回 ≠ 写入原文）② 按 VARCHAR 绑定 jsonb ⇒ PG 报 **42804**（⇒ `PGobject` 路径承重）③ 非法 JSON 由 PG 报错 **22P02**（写入前不校验）④ jsonb 拒收 `\u0000` ⇒ **22P05** ⑤ **text 照单全收** `\u0000` ⇒ **两个 handler 必须分开的唯一判据**（**上游对拍证据** DRIFT-ARPC-004① · 见 B 侧 `docs/DRIFT/users/admin/rpc.md`）⑥ null map ⇒ 列写 NULL ⑦ 列里非法 JSON ⇒ 包成 `SQLException`；**变异实测 7 次每次都变红**；⚠️ **尚无域模块消费**（域为空壳 ⇒ 不在本工作线范围）|
| gRPC 保护链（recover/stat/prometheus/breaker/shedding） | 🟢 **已承接** | ✅ **V03 已修**：链序 = `recover → stat → prometheus → breaker → shedding → business`（B 侧注册序），由 `ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo` **真的搭出链**逐层断言；⚠️ 熔断器本体由 resilience4j 承载（机制类型差异仍挂 **D-02** 的 L3） |
| gRPC 客户端熔断 + 出向 deadline | 🟢 **已承接** | ✅ **V03 已修**：客户端熔断隔离 key = `(下游服务, 方法)`；出向 deadline = min(请求剩余, 调用点自带, 客户端上界)，**per-service 上界是「覆盖」组件级**；装配走 `ClientProtectionAutoConfiguration`（starter 化，消费方零 `@Import`）⇒ 已由 devh **真实收集器**断言（`ClientProtectionAutoConfigurationTest`）|
| HTTP 超时（503）+ 断连（499）+ 池饱和（裸 503） | 🟢 **已承接** | ✅ **批次 C 已修**：抄 RAG 整段 ⇒ **三条拒绝路径的形状逐条对齐**（超时 503 + body `Request Timeout\n`（**16** 字节）+ `text/plain` + `nosniff` + `Content-Length: 16` / 断连 499 / 池饱和裸 503 无 body 无 Content-Type）＋ **512 worker 池**（`obs-http-worker`）；对拍：`HttpMiddlewareTest`（11 例）；**4 次变异实测**每次都变红 |
| Prometheus 写入面（2 个 meter） | 🟢 **已承接** | ✅ **V03 已修**：耗时分布 ＋ 状态码计数 ＋ `code` 标签；⚠️ **D4 生态吸收** ⇒ 丢弃 RAG 的「接缝接口 + 静态 holder」，改构造器注入 `MeterRegistry` ⇒ 零静态状态；⚠️ **L3**：指标**桶布局**不等价（B 侧显式 12 桶 vs Micrometer 默认 summary） |
| RPC 统计面（每分钟日志） | 🟢 **已承接** | ✅ **V03 已修**：`RpcStatMetrics` 抄写 RAG（含 3 个上游对齐怪癖：avg 先整除 / median = 第 `size>>1` 大 / 小样本退化） |
| OTel 全局装配（provider/exporter/sampler/传播器） | 🟢 **已承接** | ✅ **批次 F 已修**：`GoStyleOtelAgent`（SDK ＋ OTLP exporter ＋ W3C 传播器 ＋ shutdown hook ＋ 幂等 ＋ 装不上不阻断启动）＋ `GoStyleOtelConfig`（上游 `Telemetry` 四字段 ＋ 键名与 B 同构）＋ `GoStyleOtelAutoConfiguration`（**BFPP**，早于任何单例创建）⇒ 对拍：`GoStyleOtelAgentTest`(7) + `GoStyleOtelConfigTest`(13) + `TraceparentFilterTest`(4)；⚠️ **依然归类的两个差异**：① `traceparent` 响应头的**规范化**（头名 canonical）与 B 一致，但 **span 名**用 URI 而非 B 侧的路由模板（不可 wire 观察））② **导出端点**（域 yml 的 `otel:` 段）尚未搬运（用户已叫停 yml 搬运）⇒ 本进程不向 jaeger 导出 |
| MQ 消费端 trace | 🟢 **已承接** | ✅ **批次 E 已修**：抄 RAG 整段 ⇒ **Micrometer Observation + OTel 双载体**；⚠️ Micrometer 无 span kind ⇒ 用 `mq.kind=consumer` 承载（**本仓新增的键** ⇒ D-03 同族）；⚠️ **刻意不 catch `Throwable`**（panic 外抛） |
| JWT / 口令 | 🟢 **已承接** | ✅ **批次 D 已修**：`JwtUtil`（jjwt 0.12.6 适配 ＋ 短密钥手写 HMAC 验签取证）/ `BearerTokenExtractor`（**按上游改 `>6`**）/ `PasswordUtil`（bcrypt ＋ 畸形 hash 不抛）；对拍：`SecurityUtilTest`（7 例） |
| 共享日志门面（`LoggerFactoryBean`） | 🟢 **已承接** | ✅ **批次 E 已修**：抄 RAG 整段 ＋ **装配形态回改**（`@Component` → auto-config 的 `@Bean`）⇒ 消费方零装配代码（A5）；⚠️ **算法只留一份**（委托 `GoStyleLogger`） |
| 观测层池生命周期（两池关闭钩子） | 🟢 **已承接** | ✅ **批次 E 已修**：`ObservabilityRuntime`（两个池 ＋ 终止后按需重建）＋ `ObservabilityLifecycle`（`@PreDestroy`）⇒ **零 servlet 依赖**（纯 gRPC 进程加载也安全）；变异实测：关掉重建 ⇒ **1 红 1 错** |
| 非 gRPC 路径的业务调用（`handle`） | 🟢 **已承接** | ✅ **批次 E 已修**：`RpcHandleRunner` ＋ `RpcResult` ＋ `RpcCallHandler` ⇒ **独立线程 ＋ 到点 `cancel(true)` 打断 ＋ 观测分类**；⚠️ **只有这一条路径能真正打断业务线程** |
| 客户端断连探针 | 🟢 **已承接** | ✅ **批次 C 已修**：4 态（`ALIVE`/`DISCONNECTED`/`UNAVAILABLE`/`BUDGET_EXHAUSTED`）＋ 每请求 1xx 行数上界 ＋ **fail-open**；⚠️ **Tomcat 10.1 反射链已重新取证**（F-7 已闭合）：`RequestFacade.request` / `getCoyoteRequest()` / `coyote.Request.hook` / `AbstractProcessor.getSocketWrapper()` / `SocketWrapperBase.write+flush` **逐项存在** ⇒ 唯一差异：`ActionHook` 在 10.1 是接口 ⇒ 按实例类层次找 |

### 测试覆盖对照

| 指标 | B（`service/common`） | C（`common/`） | 比率 |
|---|---|---|---|
| 测试类 | **93** | **36** | 39% |
| 测试例 | **488**（离线实测全绿） | **224** | 46% |
| 有测试的 B 能力面 | 12 个包全覆盖 | **28 个能力面**（**27** 🟢 已承接 ＋ **1** 🟡 部分＝CPU 降载）—— 明细见上方「按『能力面』看」表 | — |

> ⚠️ **测试覆盖仍是本次最大的量化缺口**：B 侧 488 例里有 **264 例**在 C 侧**没有对应测试**（含 communityfact 41 例、protection 约 140 例、observability 约 130 例、redis 约 16 例、persistence 约 21 例、spring 34 例）。
> ⇒ C 侧目前的 **218 例**（批次 G 后）**证明了「已实现的部分是对的」**，**不能证明「没实现的部分不需要」**。

---

## §十四 最终必须回答的问题

| # | 问题 | 结论 |
|---|---|---|
| 1 | **C 是否完整承接 B 的全部实际职责？** | ❌ **否**（**但比审核当日好一大截**）。88 个 B 源类中 **1 个 NOT_IMPLEMENTED** + **0 个 FAIL**（🔴 FAIL 已清零）。**唯一缺口** = **`GoStyleOtelAgent`**（OTel SDK + OTLP exporter + 传播器 + shutdown hook）—— **D-03 / D-07 已登记且明确不实现**（`micrometer-tracing-bridge-otel` 离线不可得 ⇒ 本仓统一走 Micrometer Observation 路线 ⇒ 装了 SDK 也没有 bridge 导出 ⇒ 反而多一条断头路）。<br>✅ **已修**：**Redis 两项**（V01）、**SQL 超时链**（V02）、**熔断回落语义**（V05）、**窄接口职责断裂**（V06）、**gRPC 保护链 5 层 + 客户端侧**（V03）、**JWT/口令/Bearer**（V07）、**MaxConns / MQ trace / 池生命周期 / 共享日志门面 / OTel 配置 3 字段**（批次 E）、**HTTP 超时 503 + 断连 499 + 512 worker 池 + 断连探针**（批次 C · **V04**）—— 均于 2026-11-02 修完并补了真触发测试 ＋ 变异实测 |
| 2 | **Mapping_Table 的 RAG→RAG-V1 映射是否与当前真实代码一致？** | ⚠️ **部分不一致**。§7.4 #6 的 B 列实况**已过时**（B 现为 10/10 都有 `GrpcServerConfigurer`）⇒ **DRIFT-COMMON-V08**；§1.12 把 `ClientDisconnectProbe`/`RouteResolution`/`RouteTemplateSource` 标为「✅ 已落地」但**实际是空类** ⇒ **DRIFT-COMMON-V06**；§1.11 的 `web/` 落点数与 §2.3 的「68 已落地」口径需按 65/6 校正 ⇒ **DRIFT-COMMON-V07** |
| 3 | **File_Tree 规定的目标结构是否正确落地？** | ✅ **基本落地**。`common/` 12 个包与 File_Tree 一致；`gen-file-tree.py` 幂等；守门 `check-docs-vs-tree.py` EXIT=0。⚠️ **空类数已清零**（批次 A 填实 `RouteResolution` / `RouteTemplateSource`，批次 D 填实 `security/` 3 类，批次 C 填实 `ClientDisconnectProbe`）⇒ **0 个空类** |
| 4 | **多文件合并是否造成职责、分支或异常路径遗漏？** | ⚠️ **是（已从 3 处降到 1 处）**。① ~~gRPC 链合并 ⇒ 5 层全缺~~ ⇒ ✅ **V03 已修**（5 层齐备 ＋ 链序可断言 ＋ 客户端侧两件套）；② HTTP 保护链合并 ⇒ ~~MaxConns 层缺~~ ⇒ ✅ **批次 E 已修**（**不用 Tomcat 参数** ⇒ Java 侧闸门 ⇒ **不改 yml**）；~~HTTP 超时/断连/worker 池缺~~ ⇒ ✅ **批次 C 已修**（**V04**）⇒ ⇒ **合并遗漏已清零**；③ ~~模板解析三态压成两态且回落语义相反~~ ⇒ ✅ **V05 已修**；④ ~~窄接口被绕过~~ ⇒ ✅ **V06 已修**；⑤ ~~JWT/口令 3 类上收成空壳~~ ⇒ ✅ **V07 已修**（批次 D）。✅ 社区事实 1→13 拆分**无断裂**（调用链/数据/异常/事务/重复/遗漏/时机七项全过） |
| 5 | **文件拆分是否造成调用链、数据传递或事务边界断裂？** | ✅ **否**（就已拆分的部分）。`Wire`→13 类、`Logger`→5 类、`Snowflake`→3 类、`GoStyleOtelConfig`→`ObservabilityProperties` 均链路完整。✅ ~~`GoStyleOtelConfig` 的 `protocol`/`sampler`/`endpoint` **3 项丢失**~~ ⇒ **批次 E 已补齐**（`endpoint` / `protocol` / `samplerRatio`）⇒ ⚠️ 但这三项**只承载配置、尚未驱动装配**（全局 OTel 装配走 **D-03**） |
| 6 | **API + RPC 合并是否改变原有入口和执行行为？** | ⚠️ **本次范围外**（只审 `common/`）。但已取得一条相关证据：**gRPC 拦截链挂载点上收到共享层 1 份后，10 个域全覆盖**，而 B 侧是 10 处分散（且历史上曾有 3 域漏挂）⇒ **合并方向是改善，不是破坏**。✅ **gRPC 链已补齐 6 层**（recover/stat/prometheus/breaker/shedding/business）⇒ 保护能力**不再弱于 B**（V03 已修） |
| 7 | **Common Starter 上收是否遗漏原有消费方？** | ⚠️ **部分是**。① 11 个 Jackson/HTTP 工具类上收后**域内零副本** ⇒ ✅ 无覆盖遗漏；② ✅ **JWT/口令 3 类上收到 `common/security/` 的空壳已填实**（V07 · 批次 D）⇒ 消费方有载体了；③ ✅ **`redis/` 2 个 auto-config 已修**（V01：登记进 imports ⇒ 上收不再是「搁浅」）；④ **SQL 超时链的注册义务已从「B 侧域内 5 份 `@Bean`」上收到共享层 1 份**（V02）；⑤ ✅ **路由模板解析的上收已闭环**（V06：窄接口 + 实现 + 独立 auto-config 三件套 ⇒ 消费方**真的经窄接口**取模板，不再绕过）；⑥ ✅ **gRPC 客户端侧的上收已闭环**（V03：出向熔断 ＋ 出向 deadline ⇒ `ClientProtectionAutoConfiguration` 1 份 ⇒ 10 域一次全挂）；⑦ ✅ `communityfact` **归属已裁决（2026-11-03 · 用户定）**：行为侧 C ≡ B ⇒ **非漂移**、归属不动 ⇒ AGENTS.md §2.3 的 🔴 已解除（V11，映射表 §7.4 #1） |
| 8 | **Spring Boot 4 → 3.5 是否引入业务可观察行为变化？** | ⚠️ **是，且有 1 项曾未被察觉 —— 现已修复**。已适配 11 项（F-1~F-5、F-7~F-15）；**曾未被察觉的 1 项 = F-6**：imports 登记面从 8 行变 7 行 ⇒ **2 个已实现的能力静默失效**（V01）⇒ ✅ **2026-11-02 已修**（登记回 10 行）**且守门已扩**：新判据「每个 `@AutoConfiguration` 类都必须有激活路径」⇒ 覆盖**别的包**下的 auto-config ⇒ 同类缺口当场变红。其余：**Jackson 3→2** 经 52 例对拍覆盖；**Spring 7→6.2** 的 converter 覆写点漂移编译期即红（不会静默） |
| 9 | **Tomcat / Jackson / Spring Framework 的底层版本变化是否产生实际漂移？** | ✅ **Jackson / Spring 已回改且有对拍**（F-1~F-3）。⚠️ **Tomcat 11→10.1 的实际漂移尚未验证** —— B 的 `GoStyleClientDisconnectProbe` 反射链取证全部基于 Tomcat 11 ⇒ C 侧实现该能力时**必须重测**（F-7，当前因未实现而不适用） |
| 10 | **哪些差异已经通过证据证明安全？** | ① communityfact 的 23 个 L1 JSON tag + `omitempty` 语义 + `DeliveryStatus` 取值；② JCS 同库 ⇒ 幂等指纹不漂；③ 响应信封 `{code,msg,data}` 与 `data:null` 保留；④ 404/405/400/413/503 的状态码 + 头 + body 字节；⑤ 雪花位段与时钟回拨免疫；⑥ `rag.protection` 12 项默认值；⑦ Filter 槽位；⑧ 观测 7 个 span key + 3 个 kind + 500ms + 严格 `>` + 判定顺序 + `http.route` 语义；⑨ HTTP 熔断隔离 key 与成败判据；⑩ 三处 Boot 4→3.5 包名回改；⑪ A3 的 gRPC 挂载点（有真触发测试）；⑫ A5/A6/A12 三条守门；⑬ ✅ **熔断回落语义**（`UNKNOWN` ⇒ 回落 raw URI、`NOT_MATCHED` ⇒ 不进 breaker）＋ **三态模板解析**（`RouteTemplateResolverTest` 5 例）；⑭ ✅ **V01/V02 的装配面**（Boot 真实 `AutoConfigurationImportSelector` 从 classpath 清单选出）；⑮ ✅ **V03 的链序**（真的搭出链 ⇒ 逐层断言 `[recover, stat, prometheus, breaker, shedding, business]`）；⑯ ✅ **V03 的 wire 契约**（`"panic: "` / `UNAVAILABLE + "circuit breaker is open"` / `RESOURCE_EXHAUSTED + "service overloaded"` / 6 码失败白名单）；⑰ ✅ **V03 的客户端装配面**（devh **真实收集器** `AnnotationGlobalClientInterceptorConfigurer` 收到了两个拦截器）；⑱ ✅ **V07 的 JWT 短密钥验签**（手写 HMAC 验签 ⇒ 证明「B 侧的 15 字节短密钥能验过 C 签的 token」的 RFC 2104 零填充等价性）；⑲ ✅ **批次 E 的并发闸门**（把 `tryAcquire` 改成恒成功 ⇒ **2 例红**）；⑳ ✅ **批次 E 的超时语义**（把 `DEADLINE_EXCEEDED` 改成 `INTERNAL` ⇒ **1 例红**）；㉑ ✅ **批次 E 的池重建**（关掉重建 ⇒ **1 红 1 错**） |
| 11 | **哪些差异仍然无法确认？** | ① ~~Redis 2 项的运行期行为~~ ⇒ ✅ **V01 已修并有真触发断言**；② ~~SQL 超时链的运行期行为~~ ⇒ ✅ **V02 已修并有真触发断言**；③ OTel span 的**真实**落地（bridge 离线不可得）；④ `Traceparent` 响应头的**真实**取值（依赖 OTel）；⑤ Tomcat 10.1 下 `ClientDisconnectProbe` 反射链是否可用（C 侧尚未实现 ⇒ 不适用）；⑥ ~~跨域上收的 `JsonbTypeHandler`/`JsonTextTypeHandler`（不在本次范围）~~ ⇒ ✅ **批次 G 已闭合**（真 PostgreSQL 对拍 10 例 · 变异 7 次全红）；⑦ ~~REST 侧降载判据「只判 CPU」~~ ⇒ ✅ **批次 H 已修**（**真实漂移**，非降级 ⇒ 双条件判据回到 B 侧形态 · 变异 4 次全红）；⑧ ~~HTTP 侧熔断机制类型差异~~ ⇒ ✅ **批次 H 已登记**（D-02 扩写 ＋ 有效参数实测钉桩） |
| 12 | **当前 C 是否具备可靠的行为对拍条件？** | 🟡 **部分具备**。✅ 具备：B 侧 488 例可离线跑通（已实测）；C 侧 **224 例**可离线跑通（批次 H 后）；两侧共用同一套 JCS 库 ⇒ 指纹可比；**Boot 真实的 `AutoConfigurationImportSelector` 可在 C 侧跑**（⇒ 装配面可对拍）；**真 gRPC 假 OTLP 接收端**可在 C 侧跑（⇒ 导出面可对拍，批次 F 已用）；**真 PostgreSQL 可在 C 侧跑**（⇒ 数据面可对拍，批次 G 已用 —— jsonb/text 列的类型语义、PG 错误码、列规范化全部可对拍）。⚠️ **不具备**：① 两侧**同 JVM 同输入**的对拍（Boot 4 + Boot 3.5 不能共载）⇒ 只能「B 的断言值 → C 的期望值」逐条搬运；② C 侧域为空壳 ⇒ **跳域的端到端对拍**（HTTP/gRPC/DB/Kafka）无法做；③ 真 Tomcat 对拍需补（AGENTS.md §八「HTTP 断言走真 Tomcat」尚未满足） |

---

## 附：本次审核的实测动作（可复现）

```bash
# B 侧（Boot 4.0.8）离线全量测试 —— 488 例全绿
cd "/Users/pr/Documents/java&golang/RAG" && mvn -o -pl service/common test
#   ⇒ Tests run: 488, Failures: 0, Errors: 0, Skipped: 0 · BUILD SUCCESS

# C 侧（Boot 3.5.11）离线全量测试 —— 218 例全绿（批次 G 后实测）
cd /Users/pr/Documents/GitHub/RAG-V1 && mvn -o clean test
#   ⇒ common 模块 Tests run: 218, Failures: 0, Errors: 0, Skipped: 0 · 16/16 BUILD SUCCESS
#   ⚠️ 批次 G 起 common 的测试**依赖 Docker**（Testcontainers 起真 PG）⇒ Docker 不可用 ⇒ 直接红
#      （刻意不用 @EnabledIfDockerAvailable ⇒ §五.8 硬规则 ③「禁跳过」⇒ 守门才能断言 skipped=0）

# ✅ V01 修复后的判定命令（差集应为空 —— web/JsonContentTypeAdvice 由 @Bean 显式注册，故在「激活路径」里）
# ⚠️ 计数核对日期 2026-11-02（批次 G 后实测）：源码里 @AutoConfiguration 注解 **15 处**，
#    但 imports 清单只有 **14 行** ⇒ 差的那 1 个是 web/JsonContentTypeAdvice
#    ⇒ 它由 WebAutoConfiguration#jsonContentTypeAdvice 的 @Bean 显式注册（**不重复进清单**）
#    ⇒ 由 AutoConfigurationImportsTest#everyAutoConfigurationHasAnActivationPath 覆盖（清单登记 或 被 @Bean 实例化，二者其一）
grep -rn "^@AutoConfiguration" common/src/main --include=*.java   # 15 处
grep -v "^#" common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports   # 14 行

# ✅ V02 修复后的判定命令（应命中 PersistenceAutoConfiguration）
grep -rn "DeadlineQueryTimeoutInjector\|SqlTimeoutStatusTranslator" --include=*.java . | grep -v "common/src/main/java/com/rag/common/persistence/"

# 🔴 变异实测（证明守门不是假绿）—— 摘掉 2 行 redis ⇒ 激活路径不变式变红
grep -v "redis.ClientAutoConfiguration\|redis.DeadlineAutoConfiguration" \
  common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports > /tmp/i && \
  cp /tmp/i common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
mvn -o -pl common test     # ⇒ 实测（**2026-11-02 · 批次 A 当时**）：Tests run: 104, Failures: 1（everyAutoConfigurationHasAnActivationPath）

# 🔴 V06 的判定命令（三个空类 —— 批次 A 已把后两个填实 ⇒ 现在只剩 1 个）
for f in observability/ClientDisconnectProbe protection/RouteResolution protection/RouteTemplateSource; do
  echo "$f: $(wc -l < common/src/main/java/com/rag/common/$f.java) 行"; done
#   ⇒ 现为 9 行（ClientDisconnectProbe 仍空） / 27 行 / 21 行
```

> ⚠️ **本次未执行**：修改任何源码 / POM / 配置 / Mapping_Table / File_Tree（§13 禁止）。
> ⚠️ **本次未覆盖**：各域 `service/<域>/`（10 个域的 520 个类）、`proto/` 模块、B 侧 `service/users/users-common/` 的其余部分。
