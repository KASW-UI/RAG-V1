# P-16 · Boot 3.4+ **嵌套 `@TestConfiguration` 默认不被发现** ⇒ 测试配置**静默不注册**

> **登记时间**：2026-11-02（`align/users-admin` 工作树 · 写 admin 域的真 Tomcat 契约测试时暴露）
> **性质**：**框架默认值变更**的坑（**没有报错、没有警告** ⇒ 表现为「我明明写了配置，怎么没生效」）
> **发现者**：测试类里写了个嵌套 `@TestConfiguration` 提供 `NacosServiceManager` 桩，
> 结果上下文报 **`No qualifying bean of type 'NacosServiceManager' available`**

---

## 一、形态

```java
@SpringBootTest(classes = AdminServiceApplication.class)
class AdminHttpContractRealTomcatTest {

    @TestConfiguration(proxyBeanMethods = false)     // ← **没有生效**
    static class NacosStubConfiguration {
        @Bean NacosServiceManager nacosServiceManager() { ... }
    }
}
```

⇒ ⇒ **这个配置类根本没被注册** ⇒ ⇒ 上下文启动失败，报「找不到那个 bean」。

### 根因（读 Boot 3.5.11 源码确认）

- 开关：`spring.test.nested-test-configuration`
- 默认值：**`INHERIT`** —— 含义是「**继承父上下文的嵌套配置**」= **本类不被注册**
- 历史：Boot 3.4 之前，嵌套 `@Configuration` / `@TestConfiguration` **默认自动被扫描进上下文**；
  Boot 3.4 起**改成了默认不扫描**（要显式设置 `spring.test.nested-test-configuration=OVERRIDE` 才恢复旧行为）。

⇒ ⇒ **升级 Boot 小版本 ⇒ 一批老测试的「嵌套配置」静默失效** ⇒
⇒ ⇒ 且**报错信息完全指不到「嵌套配置」这三个字**（它只会说「缺 bean」）。

---

## 二、实测代价（RAG-V1 · admin 域）

- admin 的 HTTP 契约测试需要一个 `NacosServiceManager` 桩（真上下文 + 不连 Nacos）
  ⇒ 首版写成**嵌套 `@TestConfiguration`** ⇒ ⇒ 上下文起不来：
  `No qualifying bean of type 'com.alibaba.cloud.nacos.NacosServiceManager' available`
- ⇒ 差点误判成「Nacos 自动配置没生效」或「`@EnableDiscoveryClient` 有问题」（都**不是**）。

---

## 三、判定式（可复用）

1. ⇒ **凡在测试里提供「替身 bean」（stub / mock 的容器内 bean）⇒ 用「顶层类 + `classes = {...}` 显式点名」**
   ⇒ ⇒ **不要**依赖嵌套类的自动发现。
2. ⇒ **诊断「我明明注册了这个 bean，怎么找不到」时 ⇒ 先查它是不是**嵌套**在测试类里** ⇒
   ⇒ ⇒ 再查 `spring.test.nested-test-configuration` 的值。
3. ⇒ **升级 Spring Boot 小版本时 ⇒ 回头扫一遍所有测试里的嵌套 `@Configuration` / `@TestConfiguration`**。

---

## 四、修法（本仓落点）

- `service/users/admin/src/test/java/com/rag/admin/http/NacosStubConfiguration.java` ——
  **顶层 `@TestConfiguration`**，并在
  `AdminHttpContractRealTomcatTest` 与 `AdminServiceApplicationTests` 的
  `classes = {AdminServiceApplication.class, NacosStubConfiguration.class}` 里**显式点名**
  ⇒ ⇒ **不依赖任何默认值**。
- 两个测试类的 `classes` / `properties` **完全一致** ⇒ ⇒ Spring 的**上下文缓存**能复用**同一份上下文**
  ⇒ ⇒ 两个测试类不会各起一遍 Tomcat + gRPC server。

---

## 五、同族

- 「**『配置里没写』≠『没开』**」⇒⇒ 本条是它的**镜像**：**『配置里写了』也可能没被读到**
  （默认值的语义变了 ⇒ 读的是另一条分支）。
- 「**框架默认值会随版本变**」⇒⇒ **D14（Boot 4 → 3.5）这条工具链回退里，所有默认值都要重新核对**，
  ⇒⇒ **不能拿「B 侧 Boot 4 的行为」直接推 C 侧 Boot 3.5 的行为**。
