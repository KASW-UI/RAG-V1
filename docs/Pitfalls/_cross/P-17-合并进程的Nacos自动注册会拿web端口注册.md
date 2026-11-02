# P-17 · 合并进程里的「Nacos 自动注册」会拿 **web 端口**注册 ⇒ 同名双实例 ⇒ gRPC 链路断

> **登记时间**：2026-11-02（`align/users-admin` 工作树 · 收尾阶段跑全量 build 时，顺着 javadoc 里一句「待主控补」查出来的）
> **性质**：**配置漏抄** + **消费点误判**的坑（AGENTS.md §3.3.1 的**第三种形态**：**配置漏了 ⇒ 机制默默开着**）
> **发现者**：读域内 javadoc 时发现「**本仓的 application.yml 目前缺这一行 ⇒ 待主控补**」的措辞 ⇒ 回查 B 侧 yml ⇒ 确认是**真缺口**

---

## 一、形态

**一个进程里同时起 HTTP 容器与 gRPC server**（本仓 D1 把 api + rpc 合成一个进程）
⇒ ⇒ 启动时会发 `WebServerInitializedEvent`
⇒ ⇒ Spring Cloud Alibaba 的 `NacosAutoServiceRegistration` **拿 web 端口**去注册
⇒ ⇒ 而域内另有一个**显式**用 **gRPC 端口**注册的 `NacosRegistration`
⇒ ⇒ **同一个服务名下出现两个端口的实例**
⇒ ⇒ 消费方按名字寻址会连到错端口 ⇒ ⇒ **gRPC 链路断（业务码塌 1015）**。

⚠️ **它不会报错、不会告警** —— 注册两次都是「成功」的 ⇒ ⇒ **静默劣化**。

---

## 二、为什么会漏（本仓的实际成因）

1. **B 侧是两个进程**：`admin-api`（纯 HTTP，8889）与 `admin-rpc`（纯 gRPC，8081）
   ⇒ ⇒ **`register-enabled: false` 只写在 `admin-rpc` 那份 yml 里**（B 侧 `admin-rpc/.../application.yml:119`）。
2. **C 侧 D1 把两份 yml 合并成一份** ⇒ ⇒ 合并时**只抄了键、没抄这行**。
3. ⚠️ **更隐蔽的一层**：B 侧那行**带一大段注释**，注释里写明「**自动注册必须关掉**」——
   ⇒ **只读代码不看注释**的人会以为它是「可有可无的开关」。

---

## 三、⚠️ 最容易踩错的一步：**别用 `@ConditionalOnProperty` 判断这键有没有被读**

SCA 2025.0.0.0 里，`NacosServiceRegistryAutoConfiguration` 的 **bean gating** 是：

```java
@ConditionalOnProperty(value = "spring.cloud.service-registry.auto-registration.enabled", matchIfMissing = true)
```

⇒ **不是** `spring.cloud.nacos.discovery.register-enabled`！
⇒ ⇒ 只看这个类会得出「**这个键根本没被消费**」的**错误结论** ⇒ ⇒ 于是不加那行 ⇒ ⇒ 静默劣化。

**真正的消费点是运行期判定**（`com/alibaba/cloud/nacos/registry/NacosAutoServiceRegistration.java`）：

- `:71` `register()` → `if (!isRegisterEnabled()) { return; }`
- `:83` `registerManagement()` → 同
- `:97` `isEnabled()` → 交给 Spring Cloud Commons 的 `AbstractAutoServiceRegistration` 判定

⇒ ⇒ **键名没变**（仍是 `spring.cloud.nacos.discovery.register-enabled`，读 `NacosDiscoveryProperties#registerEnabled`，**默认值 `true`**），
但**它是「运行时判定」而不是「装配期判定」**。

⇒ ⚠️ **判据式**：**凡要确认某个机制「开没开」⇒ 必须找到它的 `if`**（消费点），
**不能只看它的 `@Bean` / `@ConditionalOn*`**（声明点）—— 这正是 AGENTS.md §3.3.1 第 3 条。

---

## 四、判定式（可复用）

1. ⇒ **凡一个进程同时起 >1 种监听端口（HTTP / gRPC / 自定义协议）⇒ 先问「服务注册面会注册哪个端口」**
   ⇒ ⇒ 框架的自动注册**通常只认一个**（多数是 web 端口）⇒ ⇒ 另一个端口必须**显式注册** ⇒ ⇒ **自动注册必须关掉**。
2. ⇒ **凡「合并两份 yml」⇒ 逐键对照，<b>连注释一起看</b>**
   ⇒ ⇒ 注释里常写着「这一行不是可选的」这种**语义线索**。
3. ⇒ **断言「注册面」时，不要断言「yml 里有没有这行」** ⇒ ⇒ 断言**外部可观测结果**：
   本仓的断言 = 「桩 `NamingService` 上被注册了**几次**」+「**端口是哪个**」。
4. ⇒ ⇒ **这类断言要配变异检验**（把配置改回去 ⇒ 断言**必须变红**）⇒ 见 `docs/Pitfalls/_cross/P-12-*.md`
   （「变异没变红 = 那条断言没覆盖到」）。

---

## 五、修法与验证（本仓落点）

- `service/users/admin/src/main/resources/application.yml` —— 补
  `spring.cloud.nacos.discovery.register-enabled: false`（**逐字照抄 B 侧**）+ 注释里写清**消费点行号**。
- `service/users/admin/src/test/java/com/rag/admin/AdminServiceApplicationTests.java` ——
  `nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered`，三条断言缺一不可：
  1. `NacosAutoServiceRegistration` bean **存在**（证明链路是活的，不是靠关整条链蒙混过关）；
  2. `NacosDiscoveryProperties#isRegisterEnabled()` 为 false（值来自**真实 yml**，测试未覆盖该键）；
  3. 桩 `NamingService` 上**只注册 1 次**，且端口 **= gRPC 端口 ≠ web 端口**。
- ⚠️ **测试基础设施的两个坑（同一轮踩到）**：
  - **不能**用 `spring.cloud.nacos.discovery.enabled=false` 来「关掉 Nacos」⇒
    `NacosDiscoveryProperties` 的**唯一 bean 定义处**在 `NacosDiscoveryAutoConfiguration#nacosProperties()`，
    而那个类带 `@ConditionalOnDiscoveryEnabled` ⇒ ⇒ 关掉 ⇒ **属性 bean 不存在** ⇒
    ⇒ ① `nacosServiceRegistry(...)` 缺参数 ⇒ **上下文起不来** ② **读不到那个键** ⇒ **无法断言**。
  - 桩 bean **不能**用默认名 `nacosServiceManager` ⇒ 真 bean 同名 ⇒ `BeanDefinitionOverrideException`
    ⇒ ⇒ 桩改名 + `@Primary`（类型注入全部拿到桩 ⇒ 不触网）。
- **变异检验实测**：注释掉那行 ⇒ 测试**变红**（日志出现**两次**注册：web 端口 + gRPC 端口）⇒ **缺陷被复现**。

---

## 六、同族

- 「**Bean 存在 ≠ 机制生效**」「**配置里没写 ≠ 没开**」（AGENTS.md §3.3.1）
  ⇒⇒ **本条补的是第三面：「配置里漏写 ⇒ 机制默默开着，而且没人报错」**。
- 「**变异没变红 = 那条断言没覆盖到**」（P-12）⇒⇒ 本条的断言**正是靠变异检验证明能红的**。
