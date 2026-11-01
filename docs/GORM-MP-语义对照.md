# 持久层语义对照表 —— B 侧 MyBatis-Plus 用法 → 本仓 MyBatis-Plus 用法

> **来源**：B 侧（RAG 仓）的实测教训（`docs/Pitfalls/_cross/fix-round-lessons.md` · `docs/Intentional_Deviations/common/_signed-deviations.md`）
> **适用范围**：RAG-V1 全域（**同一套 MyBatis-Plus** ⇒ 同样的坑会原样复现）
> **性质**：**填实现时的强制参考**，不是可选参考。
> **核对日期**：2026-10-25（内容基准）· 2026-11-02（改为 **B 侧 → 本仓** 口径）

---

## 〇、为什么要有这张表

**B 侧（RAG 仓）已经在用 MyBatis-Plus** —— 本仓的持久层是**照抄 B 侧**来的 ⇒
**B 侧踩过的 MP 语义坑，本仓会原样复现**（同一个 ORM、同一个版本、同一种写法）。

⇒ 本表的读法是 **「B 侧怎么写的 ⇒ 本仓该怎么写」**：

| 列 | 含义 |
|---|---|
| **B 侧写法** | B 侧（RAG 仓）**已经落地**的、可直接抄的写法（含具体类名与位置） |
| **B 侧实测** | B 侧跑出来的**事实**（不是推断） |
| **本仓写法** | 本仓照抄 / 必须遵守的落地要求 |
| **⚠️ 坑** | 照抄时最容易踩空的那一条 |

⚠️ **B 侧没有对应写法的，本表显式写「B 侧亦无此写法 ⇒ 本仓不实现」** —— **不编造**。

B 侧的修复轮元教训里，**6 条是「写测试/跑全量撞出来的」，只有 2 条是审出来的**
⇒ 这个比例本身就是「**跑绿 ≠ 对**」的最强证据。

⚠️ **本表只覆盖 B 侧已实测坐实的部分**。未列出的组合**不代表等价**，只代表「还没踩到」。

---

## 一、写路径：实体形态决定修法

> **判据是「写路径的实体形态」，不是「哪个更地道」。**

| B 侧写法（RAG 仓现状） | B 侧实测 | 本仓写法 | ⚠️ 坑 |
|---|---|---|---|
| `article` 域 8 处写路径**全部** `updateById(entity)`（实体非 null） | 实体非 null | `updateById(entity)` + `MetaObjectHandler` | ⇒ `MetaObjectHandler` 的 `strictUpdateFill` **生效**（消费点：`if (metaObject.getValue(fieldName) == null)`） |
| `admin` / `message` / `user` 域写路径 `update(null, wrapper)`（实体为 null） | 实体为 null | `update(null, wrapper)` + `wrapper.setSql(...)` | ⇒ **`MetaObjectHandler` 根本不会被调用**（实体是 null）⇒ 必须用 `wrapper.setSql(...)` 或 `wrapper.set(...)` |

### 🔴 坑 1：`strictUpdateFill` 在读-改-写路径上**永远不生效**

```java
// B 侧 article 域的典型读-改-写（8 处写路径全是这个形态）
ArticleEntity e = mapper.selectById(id);      // 从库里读出来 ⇒ 字段非 null
e.setTitle("x");
mapper.updateById(e);                          // ← strictUpdateFill 跳过！
```

**根因**：MyBatis `strictFillStrategy` 的消费点是 `if (metaObject.getValue(fieldName) == null)` ——
实体从库里读出来 ⇒ 非 null ⇒ **跳过**。

**B 侧的最终修法**：`ArticleMetaObjectHandler.updateFill` 走 **无条件覆盖**
（`setFieldValByName("updatedAt", OffsetDateTime.now(ZoneOffset.UTC), metaObject)`），
**刻意不用 `strictUpdateFill`** ⇒ 且用 `setFieldValByName`（自带 `hasSetter` + 非空守卫）
而不是裸 `metaObject.setValue`（字段名写错时静默跳过而不是抛反射异常）。

> ⇒ **本仓写法**：`service/article/.../config/ArticleMetaObjectHandler` **照抄 B 侧的无条件覆盖写法**。
> ⚠️ **不要改回 `strictUpdateFill`** —— 读-改-写路径上它**永远不触发**，且**不报错**。
> ⇒ 读-改-写路径**不要依赖自动填充**，显式 `set`；
> 或改用 `MetaObjectHandler` 的 `updateFill`（无 null 判断）并自行保证幂等。

### 🔴 坑 2：同一个「更新时自动填时间」语义需要**两种 Java 修法**

**B 侧实测**（两个模块各踩一次）：

| B 侧落点 | 写路径形态 | 修法 |
|---|---|---|
| `article` 域 | `updateById(entity)`（实体非 null） | `MetaObjectHandler`（`ArticleMetaObjectHandler`） |
| `admin` 域 | `update(null, wrapper)`（实体为 null） | `setSql` —— **实体为 null，handler 根本不会被调用** |

⇒ **判据是「写路径的实体形态」**，不是「哪个更地道」。

> ⇒ **本仓写法**：动手前先确认写路径的实体形态（entity? wrapper?）。选错 ⇒ 静默不生效。

---

## 二、可空字段：`NULL` 读出来是 `0` 不是 `null`

### 🔴 坑 3：字段初始化值 + `callSettersOnNulls` 默认 false

**B 侧实测**（`UserEntity`）：可空数值字段带**初始化值**，且 MyBatis `callSettersOnNulls` 默认 `false`：

```java
public class UserEntity {
    private Long deletedAt = 0L;   // ⚠️ 初始化值
}
```

- MyBatis `DefaultResultSetHandler` 遇 NULL **跳过 setter**
- ⇒ 字段保持初始化值 ⇒ **读出来是 `0` 而不是 `null`**
- ⇒ 后果：`if (x != null && …)` 这类**防御写法永远不触发**
- ⇒ B 侧当时的注释「绝不 fallback 成 0」**本身就是死代码**

> ⇒ **本仓写法**：**照抄 B 侧的同一形态**（同一套 MP ⇒ 同一行为）；
> ⇒ 但**写 `!= null` 判据前，必须先证明 null 真能到**。
> ⇒ **守门测试里要有一条「前提断言」**（B 侧正是那条先红，直接证伪了原假设）。
> ⇒ **推荐**：可空字段**不写初始化值**，或用 `Optional`/包装类型且配 `callSettersOnNulls=true`。

---

## 三、字段名：MP lambda 与手写 OGNL 是**两套解析规则**

### 🔴 坑 4：同一个字段名，两套解析

| 写法 | 解析依据 | 示例 |
|---|---|---|
| `E::isRead`（**MP lambda**） | `TableInfo.propertyMap`，key = **字段名** | 字段叫 `isRead` ⇒ 能查到 |
| `#{isRead}`（**手写 `@Insert`/`@Update`**） | **JavaBeans 属性名**（由 getter 定） | 属性叫 `read` ⇒ **找不到** ⇒ `ReflectionException: There is no getter for property named 'isRead'` |

**B 侧实测**：`message` 域的 `ConversationMessageEntity` 字段叫 `isRead`、getter 叫 `isRead()`、
setter 叫 `setRead(boolean)` ⇒ 字段名与属性名**不一致** ⇒ 两套解析规则同时生效 ⇒
**把字段从 `isRead` 改成 `read` 修好 lambda，同时打挂了 5 条既有测试**。
B 侧用 `MessageIsReadLambdaWiringTest` 把这个形态**锁死**。

> ⇒ **本仓写法**：照抄 B 侧的字段/属性命名形态 ⇒ **改字段名后必须跑全量**，
> 不能只跑新写的守门测试 —— 那 5 条红只有全量才看得见。

---

## 四、SQL 拼装：`<where>` 会生成**两个 `WHERE`**

### 🟢 本仓不实现（B 侧亦无此写法）

**B 侧实测**：`service/**/src/main/resources` 下 **0 个 mapper `.xml`** ⇒
B 侧的 SQL **全部**走 MP 的 Wrapper / 注解 ⇒ **B 侧从来没有踩过 `<where>` 这个坑**。

```xml
<!-- ❌ 基础 SQL 已含 WHERE，<where> 又来一次 -->
<select id="x">SELECT * FROM t WHERE a = #{a} <where> b = #{b} </where></select>
-- ⇒ SELECT * FROM t WHERE a = ? WHERE b = ?   ⇒ PG 语法错
```

> ⇒ **本仓写法**：**B 侧亦无 XML mapper 写法 ⇒ 本仓不实现**（保持 0 个 `.xml`，
> 全部走 Wrapper / 注解 ⇒ 这条坑对本仓**不成立**）。
> ⚠️ **若将来确要引 XML**（新需求），才需遵守这条纪律：
> 基础 SQL **不要自带 `WHERE`**（把条件全部交给 `<where>` / `<if>`），或全部手写、**不用 `<where>`** —— 二选一，不混用。
> ⚠️ 另注（仍成立）：**修掉外层错误才会露出内层错误，一轮只能走一层** ⇒ 别指望一次改完。

---

## 五、事务边界（与 ORM 写法强相关）

### 🔴 坑 6：`@Transactional` 的 self-invocation

**B 侧实测（`common/_signed-deviations.md` B-1 补记）**：拦截 `StatementHandler.prepare` 注入 `setQueryTimeout`
时发现 —— **MyBatis → Spring JDBC → pgjdbc → socket** 整条数据访问路径上
`javap -c` 数 `isInterrupted` 命中**均为 0** ⇒ **JDBC 驱动不响应线程 interrupt**。

⇒ 由此确立的三条判据：

| # | 判据 | 出处 |
|---|---|---|
| 1 | **`@Transactional` 必须在独立 Bean 上**（跨 Bean 调用 ⇒ 走 Spring AOP 代理 ⇒ 真生效）；同 Bean 内 self-invocation ⇒ **注解形同虚设且无任何异常** | `common/_signed-deviations.md` B-1；B 侧 `message-rpc` 6 个 `@Transactional` 全在独立 `MessageAtomicService` 上 ⇒ **无 self-invocation 风险** |
| 2 | **超时取消只能靠 `setQueryTimeout`**（pgjdbc 发 CancelRequest，**连接保持可用**）⇒ `socketTimeout` **语义相反**（连接报废，污染连接池）⇒ **不可用** | 同上（三层实测：纯 JDBC / 端到端 / 手段定性） |
| 3 | **`SQLState=57014`（`canceling statement due to user request`）必须被翻译成 `DEADLINE_EXCEEDED`**，且**不能被 `catch (Exception e)` 吞成 INTERNAL** | 同上（46 处 `catch (StatusRuntimeException e) { throw e; }`） |

> ⚠️ **B 侧的踩坑**：翻译器写 `catch (SQLException)` —— 但 MyBatis `Invocation.proceed()` 声明抛的是
> `InvocationTargetException`（反射包装）⇒ **编译通过但永远匹配不上** ⇒ 翻译器**静默失效**。

> ⇒ **本仓写法**：`common/persistence/DeadlineQueryTimeoutInjector`（按线程 `DeadlineState` 给每条 SQL 设
> `setQueryTimeout`）＋ `common/persistence/SqlTimeoutStatusTranslator`（识别 PostgreSQL 的 `57014`
> ⇒ 翻成 `DEADLINE_EXCEEDED`）—— **B 侧同款两类的落点，B 侧在 `service/common/.../common/persistence/`，
> 本仓在 `common/persistence/`** ⇒ **抄 B 侧的实现，别自己重写**。

---

## 六、速查表（填实现时对照）

| 场景 | ❌ 不要 | ✅ 要（B 侧同款） |
|---|---|---|
| 读-改-写后更新 | 依赖 `strictUpdateFill` 自动填 | 显式 `set`；或用 `updateFill` 的**无条件覆盖**写法 |
| 条件更新（无实体） | `updateById(new X())` | `update(null, wrapper)` + `wrapper.setSql(...)` |
| 可空数值字段 | `private Long x = 0L;`（却按 `null` 写判据） | 不写初始化值（或配 `callSettersOnNulls=true`） |
| 布尔字段命名 | `isRead` 同时出现在 lambda 与手写 SQL | 统一；改字段名后**跑全量** |
| XML 拼装 | （**本仓不用 XML**） | B 侧亦无此写法 ⇒ 本仓不实现；若将来引入则基础 SQL 不自带 `WHERE` |
| 事务 | 同 Bean 内自调用 `@Transactional` 方法 | 抽到独立 Bean，跨 Bean 调用 |
| SQL 超时 | `socketTimeout` | `setQueryTimeout` + `SQLState=57014` 翻译 |
| 判空 | `if (x != null)` 而未证明 null 能到 | 先加「前提断言」证明 null 能到 |

---

## 附：出处

> 全部指向 **B 侧（RAG 仓）** 的文件（在本仓之外的 B 侧仓内相对路径）。

| 条目 | B 侧出处 |
|---|---|
| 坑 1（`strictUpdateFill` 不生效） | `docs/Pitfalls/_cross/fix-round-lessons.md:14-17`（元教训 2）· 实现形态见 `service/article/article-rpc/.../config/ArticleMetaObjectHandler.java` |
| 坑 2（两种 Java 修法） | 同上 `:18-20`（元教训 3）· `service/users/admin/admin-rpc/.../service/AdminDomainService.java`（`update(null, wrapper)` 侧） |
| 坑 3（NULL → 0） | 同上 `:36-45`（元教训 6）· 形态见 `service/users/user/user-rpc/.../domain/UserEntity.java` |
| 坑 4（两套解析规则） | 同上 `:30-36`（元教训 5）· 形态见 `service/message/message-rpc/.../domain/ConversationMessageEntity.java` + `MessageIsReadLambdaWiringTest` |
| 坑 5（两个 `WHERE`） | 同上 `:11-13`（元教训 1）—— ⚠️ **B 侧已无 mapper XML ⇒ 本仓不实现** |
| 坑 6（事务 / 超时 / 57014） | `docs/Intentional_Deviations/common/_signed-deviations.md` B-1 补记（三层实测）· 实现形态见 `service/common/.../common/persistence/{DeadlineQueryTimeoutInjector,SqlTimeoutStatusTranslator}.java` |
