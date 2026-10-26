# Pitfalls 索引 —— 踩坑档

> **记什么**：「为什么会踩」—— 框架机制静默失效、绿测试锁死错误答案、基础设施误用。
> **不记什么**：具体行为差异（→ `../DRIFT/`）、签字决策（→ `../Intentional_Deviations/`）。

## 档位结构

```
docs/Pitfalls/
├── _INDEX.md
└── _cross/            # 跨模块踩坑
```

## 已登记的坑（来自 RAG 形态的实测教训）

| # | 坑 | 一句话 |
|---|---|---|
| P-01 | **测试注入掩盖生产配置缺失** | `grpc.client.*.negotiation-type` 由 `@SpringBootTest(properties=...)` 在测试里注入 ⇒ 生产配置可以一直是错的 ⇒ 测试永远绿 ⇒ 没人发现 |
| P-02 | **注解存在 ≠ 机制生效** | `RpcServerInterceptorFactory` 只产出 Bean，不挂到 `server.intercept` 上 ⇒ 整条拦截链静默失效 |
| P-03 | **自动配置排序悬空** | Boot 4 挪了 `DataSourceAutoConfiguration` 的包名，MP 的 `@AutoConfigureAfter` 引用静默失效 ⇒ MP 自动配置不生效 |
| P-04 | **`@Import` 与 auto-config 抢求值顺序** | `@Import` 那份按 user config 处理（更早求值）⇒ `@ConditionalOnMissingBean` 语义改变 |
| P-05 | **复制模块模板 ⇒ 抄走错误接线** | 新 session 复制现有模块当模板，会连同那份 4~10 个 Bean 不等的 `@Import` 清单一起抄走 |
| P-06 | **中断对阻塞 IO 无效** | Java 的 `Future.cancel(true)` 对 JDBC 驱动 / grpc-java 阻塞 stub / `java.net.http` 均不响应 ⇒ 必须走服务端取消（`setQueryTimeout` → pgjdbc CancelRequest） |
| P-07 | **写错签名比不写好** | Spring Boot 4 / Jackson 3 的 `HttpMessageConverter.read` 签名已变 ⇒ 写错直接编译失败，反而比"照抄旧教程"安全 |
| P-08 | **「落地证据」没被机器复算 ⇒ 只是又一处沉默不变量** | 台账里写「三域各含 `rpc/bootstrap/<域>FactProcessLogging`」⇒ **comment 域实际没有** ⇒ 零编译错误 + 零测试红灯 ⇒ 直到守门脚本首次实跑才暴露 ⇒ 一句没人复算的「证据」与「愿望」在字节层面完全一样 |
| P-09 | **批量替换的三个静默失效** | macOS BSD `sed` **不支持 `\b`** ⇒ 替换零命中（文件名改了、类名没改）· perl 替换串里 `.` 是**字面点** ⇒ 产出 `web/.Xxx` 坏串 · 路径前缀写法不统一 ⇒ 漏网 ⇒ **三次全部零报错** ⇒ 批量改名必须自带「残留=0 / 文件名≡类名 / 引用表↔磁盘」三条守恒断言 |
| P-10 | **断言的「自动消失」≠ 问题不存在** | `return na("契约 B 下不适用")` 看着是保护措施，**实为断言的死亡证明** —— 把「命题在另一形态下不成立」这个**未经论证的假设**写进了代码。A3（拦截链有没有真挂上）与进程形态无关，却被判 N/A ⇒ 连同它本该抓的缺陷一起从门禁里消失 ⇒ 判形态相关要看**命题**，不看**实现形态** |

> 📖 详情 → [`_cross/P-08-落地证据未经机器复算.md`](_cross/P-08-落地证据未经机器复算.md) ·
> [`_cross/P-09-批量替换的三个静默失效.md`](_cross/P-09-批量替换的三个静默失效.md) ·
> [`_cross/P-10-断言的自动消失不等于问题不存在.md`](_cross/P-10-断言的自动消失不等于问题不存在.md)

## 纪律

1. **踩坑一旦确认，立刻写档** —— 隔一天细节就丢了。
2. **写清「为什么会踩」而非「怎么修」** —— 修法在代码里，原因才需要留存。
3. **跨模块的坑进 `_cross/`**；域内专属的进域目录。
