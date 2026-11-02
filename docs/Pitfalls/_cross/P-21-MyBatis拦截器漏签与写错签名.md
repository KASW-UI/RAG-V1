# P-21 · MyBatis 拦截器的 `@Intercepts` **漏签一个方法** ⇒ 那条路径**整条绕过**；而**签错一个方法** ⇒ **服务起不来**

> **一句话**：`@Intercepts` 的每个 `@Signature` 决定「哪些 `Executor` 方法会被拦」⇒ ⇒ **漏掉一个 ⇒ 那条调用路径完全不经过拦截器**（零报错、零日志、翻译器像不存在）；而**写错一个 ⇒ `Plugin.getSignatureMap` 在启动期抛 `PluginException`** ⇒ **进程直接起不来**。

## 一、漏签（本仓实测 · 2026-11-03 · admin 域 S4）

**现象**：共享层的 `SqlTimeoutStatusTranslator`（把 PG 的 `57014` 翻成 `DEADLINE_EXCEEDED`）对 `selectList` / `update` 一直有效，
**但对「游标查询」完全无效** ⇒ ⇒ S4（外部持锁卡住 W1 的 `SELECT ... FOR UPDATE`）的响应面是 **`200 + 5001`**（应为 1015）。

**定位到的栈**（决定了改哪里）：

```
at org.apache.ibatis.executor.BaseExecutor.queryCursor(BaseExecutor.java:180)
at org.apache.ibatis.executor.CachingExecutor.queryCursor(CachingExecutor.java:82)
at org.apache.ibatis.plugin.Plugin.invoke(Plugin.java:61)
at org.apache.ibatis.session.defaults.DefaultSqlSession.selectCursor(DefaultSqlSession.java:125)
```

⇒ ⇒ **走的是 `Executor.queryCursor`** ⇒ ⇒ 而原 `@Intercepts` 只签了 `query`（4 参 / 6 参）与 `update` ⇒ ⇒ **漏签**。
⇒ ⇒ 而 **MP 的 `BaseMapper.selectOne(Wrapper, boolean)` 恰恰走游标路径**（源码实测：`BaseMapper.java:344` ⇒ `factory.openSession()` + `sqlSession.selectCursor(...)`）⇒ ⇒ **生产上最常用的那条查法完全绕过了翻译器**。

**修法**：补 `@Signature(type = Executor.class, method = "queryCursor", args = {MappedStatement.class, Object.class, RowBounds.class})` ⇒ S4 立刻转绿。

## 二、写错（本仓实测 · 同一天 · 修完立刻踩）

**为什么会想到补 3 参的 `query`**：MyBatis 老版本的 `Executor` 接口**曾经**有 `query(MappedStatement, Object, RowBounds)`（3 参）⇒
⇒ 本仓第一次补签名时顺手加了它 ⇒ ⇒ **admin 服务直接起不来**：

```
org.apache.ibatis.plugin.PluginException: Could not find method on interface
    org.apache.ibatis.executor.Executor named query.
    Cause: java.lang.NoSuchMethodException:
    org.apache.ibatis.executor.Executor.query(MappedStatement, Object, RowBounds)
    at org.apache.ibatis.plugin.Plugin.getSignatureMap(Plugin.java:82)
    at org.apache.ibatis.plugin.Plugin.wrap(Plugin.java:45)
    at com.rag.common.persistence.SqlTimeoutStatusTranslator.plugin(...)
```

⇒ ⚠️ **`@Intercepts` 的签名是**运行期**校验的**（`Plugin.wrap` 时反射查方法）⇒ ⇒ **写错 ⇒ 启动期炸**；
⇒ ⚠️ **漏签 ⇒ 运行期无感**（那个方法就是没被拦）⇒ ⇒ **两者都零「功能性报错」**，只是炸的时机不同。

## 三、判据（**怎么避免**）

1. **签之前先读接口**（MyBatis 3.5.19 的 `org.apache.ibatis.executor.Executor` 实测只有）：
   `update(MappedStatement, Object)` · `query(MappedStatement, Object, RowBounds, ResultHandler)` ·
   `query(MappedStatement, Object, RowBounds, ResultHandler, CacheKey, BoundSql)` ·
   `queryCursor(MappedStatement, Object, RowBounds)` + 一堆非查询方法。
   ⇒ ⇒ **3 参的 `query` 在 3.5.19 里不存在**。
2. **凡是「按调用形态分流」的框架（拦截器 / 过滤器 / advice），先列全「调用形态」的全集再签** ——
   ⇒ 本仓的调用形态全集是：**常规 `query` / 游标 `queryCursor` / `update`** ⇒ ⇒ **三个都要签**。
3. **判「拦截器到底覆盖了哪些形态」不能靠读注解** ⇒ 要**读栈**（`Plugin.invoke` 下面那一行是哪个 `Executor` 方法）⇒
   ⇒ 本次就是靠栈里的 `BaseExecutor.queryCursor` 定位的。

## 四、⇒ 同族

- 与 P-02（「注解存在 ≠ 机制生效」）同源：**声明 ≠ 覆盖**；
- 与 P-11（按类型判缺失的 Bean 被同类新增静默挤掉）同源：**「集合少了一个成员」是静默失效的常见形态**；
- 与 P-20（MyBatis 包装异常）同族：**都是 MyBatis 这一层的「看不见的边界」**。
