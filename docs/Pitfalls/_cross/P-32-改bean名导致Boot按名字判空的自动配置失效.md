# P-32 · 改 bean 名 ⇒ **Boot 的「按名字判空」自动配置失效** ⇒ 冒出一个**语义相反**的默认 bean

> 档位：跨模块（`_cross/`）· 首次登记 **2026-11-28** · 触发场景：**S1 · 10 个域的 Kafka 工厂加域前缀**

---

## 一、现象

5 进程形态下，comment / like / task 三域各有一个 `kafkaListenerContainerFactory` `@Bean`
⇒ 合并进同一上下文后 **bean 名撞车** ⇒ 须加域前缀改名：

```java
@Bean("commentKafkaListenerContainerFactory")     // 改名前：方法名 = kafkaListenerContainerFactory
public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(...) { ... }
```

改名后，**域自己的上下文测试红了**：

```
[ERROR] CommentServiceApplicationTests.kafkaAutoConfigurationIsActive:149
  » NoUniqueBeanDefinition
    No qualifying bean of type 'org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory'
    available: expected single matching bean but found 2:
    commentKafkaListenerContainerFactory, kafkaListenerContainerFactory
```

⚠️ **关键**：报错里那个 `kafkaListenerContainerFactory` **不是本仓的** ⇒
它是 **Spring Boot 自动配置提供的默认工厂**。

---

## 二、根因：Boot 的判空是「**按名字**」，不是「按类型」

反编译 `spring-boot-autoconfigure-3.5.11.jar` 的 `KafkaAnnotationDrivenConfiguration.class`：

```
javap -v KafkaAnnotationDrivenConfiguration.class | grep -A3 ConditionalOnMissingBean

  org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
    name=["kafkaListenerContainerFactory"]        ← ★ 按「名字」判空
  )
```

⇒ ⇒ **改名前**，域的 bean 恰好就叫 `kafkaListenerContainerFactory`
⇒ ⇒ Boot 的判空**命中** ⇒ Boot 的默认工厂**被抑制** ⇒ 上下文里**只有 1 个**工厂。

⇒ ⇒ **改名后**，判空**不再命中** ⇒ Boot 的默认工厂**被创建** ⇒ 上下文里变成**2 个**。

### 🔴 比「多一个 bean」更严重的是**语义相反**

| | 域自建的工厂 | Boot 的默认工厂 |
|---|---|---|
| 错误处理器 | 域自己的 `DefaultErrorHandler`（**逐字抄 B 侧**） | Boot 默认（**默认重试语义**） |
| 位点提交契约 | article/hot = `ForceCommit=true`（失败也提交）<br>comment/like/task = **失败不提交 ⇒ 重投** | **与两者都不同** |

⇒ ⇒ 这个默认工厂一旦被某个 listener 用上 ⇒ ⇒ **位点提交契约静默翻转**
⇒ ⇒ 且**零报错**（listener 照样消费、照样提交）⇒ ⇒ 这正是 AGENTS.md §3.3.3 说的那类**静默失效**。

---

## 三、🔴 三条「看起来对」的路，全是错的

### ❌ 路 1：整体排除 `KafkaAnnotationDrivenConfiguration`

```yaml
spring:
 .autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.kafka.KafkaAnnotationDrivenConfiguration
```

⇒ ⇒ **hot 域启动即失败**（`NoSuchBeanDefinitionException`）：
`hot` 的 factory 依赖 `ConcurrentKafkaListenerContainerFactoryConfigurer`
（`configurer.configure(factory, consumerFactory)`）⇒ ⇒ 而那个 configurer **也由同一个 auto-config 提供**
⇒ ⇒ 整体排除会**连带删掉它** ⇒ ⇒ **必须只删一个 bean，不能整体排除**。

### ❌ 路 2：域 factory 挂 `@ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")`

⇒ ⇒ 那是**按名字**判空 ⇒ ⇒ 合并进程里 **comment + like 两个域**都带这个条件
⇒ ⇒ **只有一个能注册** ⇒ ⇒ 另一个域的 `@KafkaListener(containerFactory = "...")` **找不到 bean**
⇒ ⇒ **启动即失败**。

### ❌ 路 3：给域 factory 加 `@Primary`

⇒ ⇒ 只解决**症状**（`getBean(工厂类型)` 不再抛歧义）⇒ ⇒ **Boot 的默认工厂仍然存在**
⇒ ⇒ **语义相反的 bean 仍在上下文里** ⇒ ⇒ 隐患保留。

---

## 四、✅ 正解：精准删掉 Boot 那**一个** bean 定义

```java
@AutoConfiguration
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
public class KafkaDefaultFactoryAutoConfiguration {

    /** Boot 自动配置的默认工厂的 bean 名（= Boot 的方法名）。 */
    static final String BOOT_DEFAULT_FACTORY_BEAN_NAME = "kafkaListenerContainerFactory";

    @Bean
    static BeanDefinitionRegistryPostProcessor removeBootDefaultKafkaListenerContainerFactory() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry beanRegistry) {
                if (beanRegistry.containsBeanDefinition(BOOT_DEFAULT_FACTORY_BEAN_NAME)) {
                    beanRegistry.removeBeanDefinition(BOOT_DEFAULT_FACTORY_BEAN_NAME);
                }
            }
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) { }
        };
    }
}
```

⚠️ **为什么必须是 `BeanDefinitionRegistryPostProcessor`**：它拿到的是**可写**的
`BeanDefinitionRegistry` ⇒ 才能 `removeBeanDefinition`；`BeanFactoryPostProcessor` 只拿到
`ConfigurableListableBeanFactory`（`BeanDefinitionRegistry` 只是它的可选子接口）⇒ 类型不稳。

⚠️ **方法签名里不能注入 `BeanDefinitionRegistry`** —— 它**不是一个可注入的 bean**
（它是容器内部的注册表）⇒ ⇒ 实测报错：
`NoSuchBeanDefinitionException: No qualifying bean of type 'BeanDefinitionRegistry' available`
⇒ ⇒ 正确写法 = **直接实现 `BeanDefinitionRegistryPostProcessor`**，由框架把注册表**作为方法参数**传进来。

⚠️ **执行时机对不对**：`BeanDefinitionRegistryPostProcessor` 跑在**所有 `@Bean` 方法
（含自动配置的）都注册完毕之后**、**任何 bean 被实例化之前** ⇒ ⇒ 此刻 Boot 的默认 factory
定义若存在 ⇒ **一定删得掉**。

⚠️ **落位**：归**共享层**（`common/autoconfigure/`）⇒ 理由 = 它对**全部 10 个域**一视同仁
（任何域只要自建了 factory，Boot 的默认那个就不该存在）⇒ **无域归属** ⇒ 符合 AGENTS.md §2.3 R1。

---

## 五、⚠️ 连带：listener 的 `containerFactory=` 引用**必须同步改名**

改名后，各域 listener 里**仍写旧名**的 ⇒ **会静默命中 Boot 的默认工厂**（因为旧名 = Boot 的名字）
⇒ ⇒ **域的错误处理器被悄悄换掉** ⇒ ⇒ **位点提交契约静默失效**。

⇒ ⇒ 本轮实测共 **8 个 listener** 需要改：`comment`×1 · `like`×1 · `task`×6。

---

## 六、⚠️ 元教训

1. **改 bean 名 ⇒ 必须重查「按名字判空」的那套自动配置** ——
   Boot 的 `@ConditionalOnMissingBean` **默认按类型**，但**显式写了 `name=` 时按名字**
   ⇒ ⇒ 改名会**静默解除**它的抑制效果。
2. **「多了一个 bean」不只是「多了一个 bean」** ——
   要看它的**语义**是否与被测对象**方向相反** ⇒ 本例是**位点提交契约翻转** ⇒ **零报错**。
3. **抑制一个 bean 的三种手段各有代价**：
   ① 整体排除 auto-config ⇒ **连带删掉同类的其它 bean**（本例 = `Configurer`）；
   ② 自己挂条件 ⇒ **按名字判空 ⇒ 多方互相排斥**（本例 = 合并进程里只剩一个域）；
   ③ **精准删 bean 定义** ⇒ 只删那一个 ⇒ ✅ 本例正解。
4. **可复用的自检**：**任何 `@Bean` 改名后**，在上下文里
   `getBeansOfType(该类型)` 数一下 ⇒ 数量比预期多 ⇒ 就是「自动配置冒出来了」。
