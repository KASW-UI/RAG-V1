# P-11 · `@ConditionalOnMissingBean` 按**类型**判缺失 ⇒ 同类 Bean 一变多 ⇒ 本 Bean 被**静默挤掉**

> **登记时间**：2026-11-02（批次 B · 修 **DRIFT-COMMON-V03**「gRPC 服务端保护链 5 层全缺」时暴露）
> **性质**：框架机制静默失效（AGENTS.md §3.3 的经典形态）· **零报错**
> **发现者**：`ProtectionAutoConfigurationWiringTest#chainLayerOrderMatchesGo`（链序断言）

---

## 一、形态

`@ConditionalOnMissingBean` **不带参数**时，判据是 **`@Bean` 方法的返回类型**：

```java
@Bean
@ConditionalOnMissingBean                      // ← 判据 = 返回类型 ServerInterceptor（**接口**）
public ServerInterceptor grpcObservationInterceptor(...) { ... }
```

⇒ 它匹配的是「容器里**任意** `ServerInterceptor` 类型的 Bean」，**不是**「同名 Bean」。

⇒ 于是：**只要容器里多了一个 `ServerInterceptor` 类型的 Bean，这个 Bean 就会被判成「已存在」⇒ 静默不注册。**

## 二、实测代价（RAG-V1 · 批次 B）

| 时刻 | 容器里的 `ServerInterceptor` Bean | 结果 |
|---|---|---|
| 批次 B 之前 | 只有 `grpcObservationInterceptor`（业务观测层） | ✅ 正常注册 ⇒ 链 = `[business]` |
| 批次 B 之后（新增 5 层保护拦截器） | `rpcRecoverInterceptor` · `rpcStatInterceptor` · `rpcPrometheusInterceptor` · `rpcCircuitGateInterceptor` · `rpcSheddingInterceptor` ＋ `grpcObservationInterceptor` | 🔴 **`grpcObservationInterceptor` 被判「已存在」⇒ 静默跳过** ⇒ **业务观测层从链上消失**（超时 / slow / 业务码判定全丢） |

⇒ ⚠️ **零报错**：上下文正常启动、链正常挂载、A3 断言照样绿（只验「`server.intercept(...)` 被调了」）、
`ProtectionAutoConfigurationWiringTest` 的另外 4 条断言也照样绿 ⇒
⇒ **只有「把链真的搭出来、逐层读层名」这条断言能抓到它**。

## 三、判定式（可复用）

1. ⇒ ⇒ **凡 `@Bean` 方法的返回类型是「接口 / 父类 / 宽泛类型」，`@ConditionalOnMissingBean` 一律按 [bean 名] 判**：

   ```java
   @ConditionalOnMissingBean(name = "grpcObservationInterceptor")   // ← 按名字，不按类型
   ```

2. ⇒ ⇒ **判据要问的是「我要防的是「谁覆盖我」」**：
   - 防「用户自己定义同名 Bean 覆盖我」 ⇒ **按名字**判（`@ConditionalOnMissingBean(name = "...")`）；
   - 防「用户提供了同一职责的另一实现」 ⇒ 按**具体类型**判（`@ConditionalOnMissingBean(MyType.class)`）。
   ⇒ ⚠️ **按宽泛接口判 = 第三方任何 Bean 都能挤掉你**。

3. ⇒ ⇒ **「同类 Bean 会变多」的场景里，按类型判缺失是定时炸弹**：
   本仓的 `common/` 是被 **10 个域共享**的 starter ⇒ 共享层每加一个 `ServerInterceptor`，
   都会踩到所有「按接口判缺失」的 Bean。

4. ⇒ ⇒ **守门要断言「链的**构成**」而不只是「链挂上了」**：
   `verify(builder).intercept(any())` 只证「挂载点有效」；
   ⇒ 真正能抓到本坑的是 **逐层读层名**（`RpcProtectionChain.layerNames(chain)`）。

## 四、连带说明：为什么本仓的 `RpcLayer` 接口值得存在

`RpcLayer`（`common/protection/RpcLayer.java`）本身不解决本坑，
但它让「链的构成」**可观测** ⇒ ⇒ 有了可观测面，才谈得上「断言链序」。
⇒ **这是「守门要断言构成，不要断言存在」的一个实例。**

## 五、相关

- 报告：[`docs/DRIFT/common/verification-common-2026-11-02.md`](../docs/DRIFT/common/verification-common-2026-11-02.md) §11.5 `DRIFT-COMMON-V03` 的「✅ 修复状态」行
- 同族：P-02（注解存在 ≠ 机制生效）· P-04（`@Import` 与 auto-config 抢求值顺序 ⇒ `@ConditionalOnMissingBean` 语义改变）· P-10（断言的「自动消失」≠ 问题不存在）
- 守门：`tools/difftest/check-arch-invariants.py` 的 **A3**（拦截链真挂上）**不覆盖**本坑 ⇒ 本坑靠 `common` 模块的**链序断言**覆盖，不是靠 A3。
