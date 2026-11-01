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
| `common` | common | 🟡 **已建立** —— [`common/verification-common-2026-11-02.md`](common/verification-common-2026-11-02.md)（RAG → RAG-V1 迁移审核 · 八阶段全覆盖 · B 88 类逐类承接 · **PASS 80 / FAIL 0 / NOT_IMPLEMENTED 4 / NOT_VERIFIED 1 / FRAMEWORK_DIFFERENCE 2 / INTENTIONALLY_CHANGED 4**（按 §11.2 状态列**逐行重数** · **91 承接行 / 91 标记**；⚠️ **口径订正**：旧版自报数与自己的表差 2，已按实测重写，根因见报告 §11.6）；✅ **🔴 FAIL 清零** —— **V01**（Redis 两项未登记）/ **V02**（SQL 超时链零注册）/ **V03**（gRPC 保护链 5 层 + 客户端侧）/ **V05**（熔断回落语义相反）/ **V06**（窄接口职责断裂）/ **V07**（`security/` 3 类）已于 2026-11-02 **全部**修复并补真触发测试 + 变异实测；✅ **批次 E 追加**：`MaxConnsFilter`（并发闸门 · **推翻「Tomcat 原生项 ⇒ 零 Java 代码」的旧口径**）· `MqConsumer`（MQ trace）· `ObservabilityRuntime` + `ObservabilityLifecycle`（两个池 ＋ 关闭钩子）· `RpcHandleRunner`（非 gRPC 路径）· `LoggerFactoryBean`（共享日志门面）· OTel 配置 3 字段 ⇒ **NOT_IMPLEMENTED 13 → 4**；⏳ 剩余 **4** 条 ＝ **3 条 V04 同族**（HTTP 超时 503 + body `Request Timeout` + 512 worker 池 `obs-http-worker` + `ClientDisconnectProbe` + `spring/HttpMiddlewareFilter`）＋ **1 条 D-07 明确不实现**（`GoStyleOtelAgent` OTel SDK ＋ OTLP exporter ⇒ 本仓统一走 Micrometer Observation 路线） |
| `proto` | proto | ⬜ 未建立（根级 proto 契约模块，10 个 `.proto`） |

## 纪律

1. **开工前必读**本域台账 —— 否则会重复已修项、误把签字偏离当新漂移「顺手修回」。
2. **产出必写回**本域目录。
3. **每条必带证据强度**：「源码级对照」与「双侧活体对拍」不是一回事，后者才是行为层证据。
4. **状态必带核对日期** —— 否则按 `UNKNOWN` 处理。
5. **⚠️「已改」≠「已验证」** —— 绿测试可能是「错误答案的守卫」。
