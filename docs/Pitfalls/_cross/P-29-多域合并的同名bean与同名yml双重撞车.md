# P-29 · 多域合并进同一 Spring 上下文 ⇒ 「同名 bean」与「同名配置文件」双双撞车

> 档位：跨模块（`_cross/`）· 首次登记 **2026-11-28** · 触发场景：**S1 · 10 进程 → 5 进程**落地

---

## 一、现象（三条独立的撞车）

### 现象 1 · 同名 `@Configuration` ⇒ bean 名撞车

```
ConflictingBeanDefinitionException: Annotation-specified bean name 'webMvcConfig'
for bean class [com.rag.admin.config.WebMvcConfig]
conflicts with existing, non-compatible bean definition of same name
and class [com.rag.user.config.WebMvcConfig]
```

⇒ 本仓 **10 个域各有一个 `WebMvcConfig`**（`service/<域>/config/WebMvcConfig.java`）
⇒ Spring 的默认 bean 名 = **简单类名首字母小写** ⇒ ⇒ 合并进同一上下文时**全部撞车**。

同款还有：`MessageRpcClient`（comment + like）· `ArticleRpcClient`（comment + favorite + hot）
· `UserRpcClient`（favorite + follow + message）· `JwtAuthInterceptor`（article + user）
· `SnowflakeBootstrap`（article + user + admin）· `kafkaListenerContainerFactory`（comment + like + task）。

### 现象 2 · 同名 MyBatis Mapper ⇒ mapper bean 名撞车

users/user 与 users/admin **各有一个 `UserMapper`**（都映射 `users_user` 表 —— admin 子域独立持有）
⇒ 合并进 `user-service` 后 bean 名 `userMapper` 撞车。

### 现象 3 · 同名 `application.yml` ⇒ **只有一份生效**

classpath 上多个**同名** `application.yml`，Spring Boot **只加载第一个**
⇒ ⇒ `user-service` 进程里 `user-api.*` 段生效、**`admin-api.admin-auth.access-secret` 整段丢失**
⇒ ⇒ `AdminApiProperties` 的 fail-closed 守卫抛
`IllegalStateException: admin-api.admin-auth.access-secret 不能为空：请设置环境变量 ADMIN_JWT_SECRET`。

---

## 二、为什么没拦住（这才是本档要记的）

| 环节 | 为什么没拦住 |
|---|---|
| **编译时** | 10 个域各自编译，**各自单独跑** ⇒ **零冲突** ⇒ 编译期**完全看不见** |
| **单域测试时** | 域测试只起**一个**域的上下文 ⇒ 同名 bean 不在同一上下文 ⇒ **不撞** |
| **打包时** | 5 个 boot jar 各自只含本进程的域 ⇒ 同名 bean **不在同一 jar** ⇒ **不撞** |
| **守门脚本时** | `check-arch-invariants.py` 做的是**源码文本级扫描** ⇒ 它能判「形状对不对」，**判不了「运行时会不会撞」**（AGENTS §3.3.3） |
| ⚠️ **最阴的一点** | 现象 3（yml 只生效一份）**不报错** —— 它表现为「某段配置没生效」⇒ 而本仓的配置**大量带 fail-closed 守卫** ⇒ ⇒ **幸而是守卫把它炸出来了**；若该配置无守卫 ⇒ ⇒ **静默丢失**（连报错都没有） |

---

## 三、怎么解（已落地，2026-11-28）

| 撞车类型 | 解法 | 代价 |
|---|---|---|
| `@Component` / `@Configuration` / `@Service` 同名 | **显式 bean 名**（`<域><类名>`）⇒ 例：`articleWebMvcConfig` / `commentMessageRpcClient` | **零行为变化**（只改 bean 名；本仓**全部按类型注入** ⇒ 不受影响） |
| `@Bean` 方法同名 | **显式 `@Bean("<名>")`** ⇒ 例：`commentKafkaListenerContainerFactory` | 零行为变化 |
| MyBatis mapper 同名 | `@MapperScan(nameGenerator = ...)` ＋ `common/mybatis/DomainPrefixedMapperBeanNameGenerator` | ⚠️ **MyBatis 的 `nameGenerator` 属性类型是 `Class` ⇒ 反射实例化要求无参构造器** ⇒ ⇒ 需 1 个父类 + N 个无参子类（实测 `NoSuchMethodException: …<init>()`） |
| `application.yml` 同名 | **域 yml 改名 `application-<域>.yml`** ＋ boot yml 用 `spring.config.import` 导入 | ⚠️ **import 顺序 = 后导入的覆盖先导入的**（实测 PS 顺序 b → a → 主文件）⇒ ⇒ **进程级键必须写在主 yml 里**（主 yml 优先级高于所有 imported） |

---

## 四、⚠️ 元教训（比坑本身更重要）

1. **「单域全绿」≠「合并后能起」** ⇒ ⇒ **必须有一层「合并态」的运行期测试**。
   本仓的做法：每个 boot 模块配一个**装配层测试**，断言「本域 bean **在** ＋ 他域 bean **不在**」
   ⇒ ⇒ 这条断言能在**域测试全绿**的同时，把「扫描边界放宽」与「同名 bean 撞车」当场抓出来。
2. **同名文件是最隐蔽的一类冲突** —— 因为它**不产生任何编译/运行错误**，
   只让「后一份」静默失效 ⇒ ⇒ **凡是「每域各有一份同名资源」的场景，合并前必须先查。**
3. **fail-closed 守卫是资产** —— 本仓的 `*Properties` 类在配置缺失时**主动炸**
   ⇒ ⇒ 正是它把「yml 静默丢失」从「静默」变成了「明确失败」⇒ ⇒ **新增配置项时保留这个守卫，不要给它加默认值。**
