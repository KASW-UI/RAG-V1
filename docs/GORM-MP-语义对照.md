# GORM 语义 → MyBatis-Plus 语义 对照表

> **来源**：RAG 仓库的实测教训（`/Users/pr/Documents/java&golang/RAG/docs/Pitfalls/_cross/fix-round-lessons.md`）
> **适用范围**：RAG-V1 全域（**同一套 MyBatis-Plus** ⇒ 同样的坑会原样复现）
> **性质**：**填实现时的强制参考**，不是可选参考。
> **核对日期**：2026-10-25

---

## 〇、为什么要有这张表

Go 侧用 **GORM**，RAG-V1 用 **MyBatis-Plus**。两者**语义不等价**，且差异点全部落在
「看起来一样的写法上」⇒ **编译通过、测试全绿、行为却不同**。

RAG 的 8 条修复轮元教训里，**6 条是「写测试/跑全量撞出来的」，只有 2 条是审出来的**
⇒ 这个比例本身就是「**跑绿 ≠ 对**」的最强证据。

⚠️ **本表只覆盖 RAG 已实测坐实的部分**。未列出的组合**不代表等价**，只代表「还没踩到」。

---

## 一、写路径：实体形态决定修法

> **判据是「写路径的实体形态」，不是「哪个更地道」。**

| Go (GORM) 形态 | RAG 实测 | MyBatis-Plus 等价写法 | ⚠️ 坑 |
|---|---|---|---|
| `Save(&entity)` / `Updates(map)` / `Model(&X{}).Updates(...)` | **实体非 nil** | `updateById(entity)` | ⇒ `MetaObjectHandler` 的 `strictUpdateFill` **生效**（消费点：`if (metaObject.getValue(fieldName) == null)`） |
| `Where(...).Update("col", val)` / `UpdateColumn` | **实体为 nil** | `update(null, wrapper)` | ⇒ **`MetaObjectHandler` 根本不会被调用**（实体是 null）⇒ 必须用 `wrapper.setSql(...)` 或 `wrapper.set(...)` |

### 🔴 坑 1：`strictUpdateFill` 在读-改-写路径上**永远不生效**

```java
// 典型读-改-写
UserEntity e = mapper.selectById(id);      // 从库里读出来 ⇒ 字段非 null
e.setNickname("x");
mapper.updateById(e);                      // ← strictUpdateFill 跳过！
```

**根因**：MyBatis `strictFillStrategy` 的消费点是 `if (metaObject.getValue(fieldName) == null)` ——
实体从库里读出来 ⇒ 非 null ⇒ **跳过**。

**与 GORM 的差异**：GORM 问「**本次调用有没有赋值**」，MP 问「**字段现在是不是空**」。

> ⇒ **修法**：读-改-写路径**不要依赖自动填充**，显式 `set`；
> 或改用 `MetaObjectHandler` 的 `updateFill`（无 null 判断）并自行保证幂等。

### 🔴 坑 2：同一个 GORM 语义需要两种 Java 修法

RAG 实测：同一个「更新时自动填时间」的 GORM 语义，
`updateById(entity)` 形态 → `MetaObjectHandler`；
`update(null, wrapper)` 形态 → `setSql`（**后者实体为 null，handler 根本不会被调用**）。

> ⇒ **动手前先确认写路径的实体形态**（entity? wrapper?）。选错 ⇒ 静默不生效。

---

## 二、可空字段：`NULL` 读出来是 `0` 不是 `null`

### 🔴 坑 3：字段初始化值 + `callSettersOnNulls` 默认 false

```java
public class UserEntity {
    private Long deletedAt = 0L;   // ⚠️ 初始化值
}
```

- MyBatis `DefaultResultSetHandler` 遇 NULL **跳过 setter**
- ⇒ 字段保持初始化值 ⇒ **读出来是 `0` 而不是 `null`**
- ⇒ 后果：`if (x != null && …)` 这类**防御写法永远不触发**
- ⇒ RAG 当时的注释「绝不 fallback 成 0」**本身就是死代码**

> ⇒ **纪律**：**写 `!= null` 判据前，必须先证明 null 真能到**。
> ⇒ **守门测试里要有一条「前提断言」**（RAG 正是那条先红，直接证伪了原假设）。
> ⇒ **推荐**：可空字段**不写初始化值**，或用 `Optional`/包装类型且配 `callSettersOnNulls=true`。

---

## 三、字段名：MP lambda 与手写 OGNL 是**两套解析规则**

### 🔴 坑 4：同一个字段名，两套解析

| 写法 | 解析依据 | 示例 |
|---|---|---|
| `E::isRead`（**MP lambda**） | `TableInfo.propertyMap`，key = **字段名** | 字段叫 `isRead` ⇒ 能查到 |
| `#{isRead}`（**手写 `@Insert`/`@Update`**） | **JavaBeans 属性名**（由 getter 定） | 属性叫 `read` ⇒ **找不到** ⇒ `ReflectionException: There is no getter for property named 'isRead'` |

⇒ **把字段从 `isRead` 改成 `read` 修好 lambda，同时打挂了 5 条既有测试。**

> ⇒ **纪律**：**改字段名后必须跑全量**，不能只跑新写的守门测试 —— 那 5 条红只有全量才看得见。

---

## 四、SQL 拼装：`<where>` 会生成**两个 `WHERE`**

### 🔴 坑 5：`<where>` 元素在已有 `WHERE` 的基础 SQL 上会生成两个 `WHERE`

```xml
<!-- ❌ 基础 SQL 已含 WHERE，<where> 又来一次 -->
<select id="x">SELECT * FROM t WHERE a = #{a} <where> b = #{b} </where></select>
-- ⇒ SELECT * FROM t WHERE a = ? WHERE b = ?   ⇒ PG 语法错
```

⚠️ **而且它被更早的一道错误挡在后面**（参数名 typo 在解析期就抛 `BindingException`）
⇒ **修掉外层错误会露出内层错误，一轮只能走一层。**

> ⇒ **纪律**：写 MP 的 XML 时，**基础 SQL 不要自带 `WHERE`**（把条件全部交给 `<where>` / `<if>`），
> 或全部手写、**不用 `<where>`** —— 二选一，不混用。

---

## 五、事务边界（与 ORM 写法强相关）

### 🔴 坑 6：`@Transactional` 的 self-invocation

**RAG 实测（`common/_signed-deviations.md` B-1 补记）**：拦截 `StatementHandler.prepare` 注入 `setQueryTimeout`
时发现 —— **MyBatis → Spring JDBC → pgjdbc → socket** 整条数据访问路径上
`javap -c` 数 `isInterrupted` 命中**均为 0** ⇒ **JDBC 驱动不响应线程 interrupt**。

⇒ 由此确立的三条判据：

| # | 判据 | 出处 |
|---|---|---|
| 1 | **`@Transactional` 必须在独立 Bean 上**（跨 Bean 调用 ⇒ 走 Spring AOP 代理 ⇒ 真生效）；同 Bean 内 self-invocation ⇒ **注解形同虚设且无任何异常** | `common/_signed-deviations.md` B-1；RAG `message-rpc` 6 个 `@Transactional` 全在独立 `MessageAtomicService` 上 ⇒ **无 self-invocation 风险** |
| 2 | **超时取消只能靠 `setQueryTimeout`**（pgjdbc 发 CancelRequest，**连接保持可用**）⇒ `socketTimeout` **语义相反**（连接报废，污染连接池）⇒ **不可用** | 同上（三层实测：纯 JDBC / 端到端 / 手段定性） |
| 3 | **`SQLState=57014`（`canceling statement due to user request`）必须被翻译成 `DEADLINE_EXCEEDED`**，且**不能被 `catch (Exception e)` 吞成 INTERNAL** | 同上（46 处 `catch (StatusRuntimeException e) { throw e; }`） |

> ⚠️ **RAG 的踩坑**：翻译器写 `catch (SQLException)` —— 但 MyBatis `Invocation.proceed()` 声明抛的是
> `InvocationTargetException`（反射包装）⇒ **编译通过但永远匹配不上** ⇒ 翻译器**静默失效**。

---

## 六、速查表（填实现时对照）

| 场景 | ❌ 不要 | ✅ 要 |
|---|---|---|
| 读-改-写后更新 | 依赖 `strictUpdateFill` 自动填 | 显式 `set`；或用 `updateFill` |
| 条件更新（无实体） | `updateById(new X())` | `update(null, wrapper)` + `wrapper.setSql(...)` |
| 可空数值字段 | `private Long x = 0L;` | 不写初始化值（或配 `callSettersOnNulls=true`） |
| 布尔字段命名 | `isRead` 同时出现在 lambda 与手写 SQL | 统一；改字段名后**跑全量** |
| XML 拼装 | 基础 SQL 自带 `WHERE` + `<where>` | 二选一，不混用 |
| 事务 | 同 Bean 内自调用 `@Transactional` 方法 | 抽到独立 Bean，跨 Bean 调用 |
| SQL 超时 | `socketTimeout` | `setQueryTimeout` + `SQLState=57014` 翻译 |
| 判空 | `if (x != null)` 而未证明 null 能到 | 先加「前提断言」证明 null 能到 |

---

## 附：出处

| 条目 | RAG 出处 |
|---|---|
| 坑 1（`strictUpdateFill` 不生效） | `docs/Pitfalls/_cross/fix-round-lessons.md:14-17`（元教训 2） |
| 坑 2（两种 Java 修法） | 同上 `:18-20`（元教训 3） |
| 坑 3（NULL → 0） | 同上 `:36-45`（元教训 6） |
| 坑 4（两套解析规则） | 同上 `:30-36`（元教训 5） |
| 坑 5（两个 `WHERE`） | 同上 `:11-13`（元教训 1） |
| 坑 6（事务 / 超时 / 57014） | `docs/Intentional_Deviations/common/_signed-deviations.md` B-1 补记（三层实测） |
