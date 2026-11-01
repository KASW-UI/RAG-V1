# DRIFT 索引 —— 语义漂移台账

> **记什么**：RAG-V1 与 Go 真源**不一致**的逐条留档。
> **不记什么**：明知不同且已签字的（→ `../Intentional_Deviations/`）、框架做不到的（→ `../Pitfalls/`）、语义已对齐仅字面不同的。

## 档位结构

```
docs/DRIFT/
├── _INDEX.md          # 本文件
└── <域>/<组件>.md     # 每域一目录，组件级台账
```

> 📌 **组件名按 D7（2026-10-26）后的真实模块坐标**：`<域>-service`（每域一个 jar 模块）
> + `users-common`（users 域公共库）+ `common`（starter）+ `proto`（**根级 proto 契约模块**）。
> ⇒ **不再有 `*-contract` 组件**（10 个 `*-contract` 子模块已随 D7 删除，错误契约落进域内 `contract/` 包）。

## 域目录

| 域 | 组件 | 台账 |
|---|---|---|
| `user` | user-service | ⬜ 未建立 |
| `users` | users-common | ⬜ 未建立（users 域公共库） |
| `admin` | admin-service | ⬜ 未建立 |
| `article` | article-service | ⬜ 未建立 |
| `comment` | comment-service | ⬜ 未建立 |
| `favorite` | favorite-service | ⬜ 未建立 |
| `follow` | follow-service | ⬜ 未建立 |
| `hot` | hot-service | ⬜ 未建立 |
| `like` | like-service | ⬜ 未建立 |
| `message` | message-service | ⬜ 未建立 |
| `task` | task-service | ⬜ 未建立 |
| `common` | common | 🟡 **已建立** —— [`common/verification-common-2026-11-02.md`](common/verification-common-2026-11-02.md)（RAG → RAG-V1 迁移审核 · 八阶段全覆盖 · B 88 类逐类承接 · **PASS 89 / FAIL 0 / NOT_IMPLEMENTED 0 / NOT_VERIFIED 0 / FRAMEWORK_DIFFERENCE 0 / INTENTIONALLY_CHANGED 2**（按 §11.2 状态列**逐行重数** · **91 承接行 / 91 标记**；⚠️ **口径订正**：旧版自报数与自己的表差 2，已按实测重写，根因见报告 §11.6）；✅ **🔴 FAIL 清零** —— **V01**（Redis 两项未登记）/ **V02**（SQL 超时链零注册）/ **V03**（gRPC 保护链 5 层 + 客户端侧）/ **V05**（熔断回落语义相反）/ **V06**（窄接口职责断裂）/ **V07**（`security/` 3 类）已于 2026-11-02 **全部**修复并补真触发测试 + 变异实测；✅ **批次 E 追加**：`MaxConnsFilter`（并发闸门 · **推翻「Tomcat 原生项 ⇒ 零 Java 代码」的旧口径**）· `MqConsumer`（MQ trace）· `ObservabilityRuntime` + `ObservabilityLifecycle`（两个池 ＋ 关闭钩子）· `RpcHandleRunner`（非 gRPC 路径）· `LoggerFactoryBean`（共享日志门面）· OTel 配置 3 字段 ⇒ **NOT_IMPLEMENTED 13 → 4**；✅ **批次 C 追加**：🔴 **V04**（HTTP 超时 503 ＋ body `Request Timeout`（16 字节）+ 断连 499 + 512 worker 池 + 池饱和裸 503 + `ClientDisconnectProbe` 4 态 + Tomcat 10.1 反射链重取证（F-7 闭合））⇒ **NOT_IMPLEMENTED 4 → 1**；✅ **批次 F 追加（最后 1 条）**：`GoStyleOtelAgent`（OTel SDK ＋ OTLP exporter ＋ 传播器 ＋ shutdown hook）＋ `GoStyleOtelConfig`（Go `Telemetry` 四字段，**键名与 B 侧同构**）＋ `GoStyleOtelAutoConfiguration`（**BFPP 装配点**）⇒ **NOT_IMPLEMENTED 1 → 0**（**清零**）· **FRAMEWORK_DIFFERENCE 2 → 0**（`traceparent` 响应头随之恢复）；⚠️ **口径变更**：D-07 的「明确不实现」已撤销（**用户指令**）⇒ `docs/L3-降级清单.md` 的 D-07 已改写（仅剩「MQ span kind」仍降级）；⚠️ **连带修掉真实缺陷 V14**：`common/pom.xml` 缺 `grpc-core`（Maven exclusion 作用于整棵传递子树）⇒ devh gRPC server auto-config 一出现就炸 ⇒ 已显式声明（test scope）；⚠️ **剩余缺口是「配置值」而不是「能力」**：10 个域的 `application.yml` 没有 `otel:` 段（**yml 搬运已由用户叫停**）⇒ 本进程不向 jaeger 导出（Go 侧写死 `Endpoint: jaeger:4317`）；✅ **批次 G 追加（最后 1 条）**：`JsonbTypeHandler` ＋ `JsonTextTypeHandler` ⇒ **真 PostgreSQL 对拍**（Testcontainers `postgres:16-alpine` · `JsonTypeHandlerPgTest` **10 例**）⇒ 证 jsonb 列**规范化**键序与空白 / 按 VARCHAR 绑定 jsonb ⇒ PG 报 **42804** / 非法 JSON ⇒ **22P02** / jsonb 拒收 `\u0000` ⇒ **22P05** / **text 列照单全收** `\u0000`（**两个 handler 必须分开的唯一判据** · Go 对拍证据 DRIFT-ARPC-004①）⇒ **NOT_VERIFIED 1 → 0**（**清零**）· **PASS 87 → 88**；common 测试 **208 → 218 例**，**7 次变异实测**每次都变红；⚠️ **本轮实现一字未改**（抄 RAG 整段 · L2 · D13）⇒ 补的是**证据**（此前判 NOT_VERIFIED 的理由是「有实现、无证据」）；⚠️ **禁 `@EnabledIfDockerAvailable`**（§五.8 硬规则 ③）⇒ Docker 不可用时必须红；🔴 **✅ 批次 H 追加：修掉一条被误判为「已登记降级」的真实漂移** —— C 侧 REST 降载 filter 此前判据是「**只有 CPU 过载**」⇒ ⇒ **比 B 侧更早丢请求**（**B 侧 REST filter 本来就用完整的 `GoStyleAdaptiveShedder`** ⇒ ⇒ **C 丢了 B 已有的能力** ⇒ 按 §十 属 **FAIL**，不是已批准的降级）⇒ ⇒ **已修**（REST filter 改走 `AdaptiveShedder` ⇒ **双条件判据 + pass/fail 记账 + 浮点 RT 口径**回到 B 侧形态）⇒ ⇒ **D-04 撤销降级**；⚠️ **旧登记的前提本身是错的，已一并纠正**（D-04 / V10 原先都写「RAG 侧也只有 CPU 一半」⇒ **不成立**）；✅ **V09 收口**（登记）：HTTP 侧熔断机制类型差异已扩写进 **D-02**，**有效参数已实测并钉桩**（resilience4j 2.2.0 = COUNT_BASED / 100 / 100 / 50% / 60s / 10 ⇒ `HttpCircuitBreakerConfigPinTest` ⇒ 框架默认值变形 ⇒ 测试立刻红）；✅ **V10 收口**（登记）：新立 **D-08**「HTTP 侧降载的可观察面」⇒ ⚠️ **定性订正**：HTTP 侧统计面缺失是 **B 侧原生缺口** ⇒ **C ≡ B ⇒ 不是 C 的迁移缺陷** ⇒ 本仓不改但**必须登记**；⇒ **INTENTIONALLY_CHANGED 3 → 2**（剩下 2 条 = HTTP 熔断 + gRPC 侧 CPU 降载，均已登记）；common 测试 **218 → 224 例**，**变异实测 4 次每次都变红**（含「退回只看 CPU」这条漂移形态）|
| `proto` | proto | ⬜ 未建立（根级 proto 契约模块，10 个 `.proto`） |

## 纪律

1. **开工前必读**本域台账 —— 否则会重复已修项、误把签字偏离当新漂移「顺手修回」。
2. **产出必写回**本域目录。
3. **每条必带证据强度**：「源码级对照」与「双侧活体对拍」不是一回事，后者才是行为层证据。
4. **状态必带核对日期** —— 否则按 `UNKNOWN` 处理。
5. **⚠️「已改」≠「已验证」** —— 绿测试可能是「错误答案的守卫」。
